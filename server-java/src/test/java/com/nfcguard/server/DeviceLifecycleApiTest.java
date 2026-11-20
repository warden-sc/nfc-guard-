package com.nfcguard.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nfcguard.server.repository.AnomalyEventRepository;
import com.nfcguard.server.retention.RetentionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1",
        "nfcguard.admin.api-key=test-admin",
        "nfcguard.rate-limit.per-device-per-hour=1000"
})
@AutoConfigureMockMvc
class DeviceLifecycleApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AnomalyEventRepository events;
    @Autowired RetentionJob retentionJob;

    private static final String EXPERIMENT = """
            {"source":"controlled_experiment","experimentIntentional":false,"experimentCondition":"backpack_walking",
             "riskScore":0.9,"reason":"x","occurredAtEpochMs":1700000000000,"rawSensorWindow":[]}
            """;

    private JsonNode register() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/devices/register"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postEvent(JsonNode dev, String body) throws Exception {
        return mvc.perform(post("/api/v1/events")
                .header("X-Device-Token", dev.get("deviceToken").asText())
                .header("Authorization", "Bearer " + dev.get("apiKey").asText())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void controlledExperimentOnlyForAdminFlaggedTestDevices() throws Exception {
        JsonNode dev = register();
        postEvent(dev, EXPERIMENT).andExpect(status().isForbidden());

        String token = dev.get("deviceToken").asText();
        mvc.perform(put("/api/v1/admin/devices/" + token + "/test-device").param("enabled", "true"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/admin/devices/" + token + "/test-device").param("enabled", "true")
                        .header("X-Admin-Key", "test-admin"))
                .andExpect(status().isNoContent());

        long id = mapper.readTree(postEvent(dev, EXPERIMENT).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        String jsonl = mvc.perform(get("/api/v1/events/dataset/export")
                        .param("afterId", String.valueOf(id - 1)).header("X-Admin-Key", "test-admin"))
                .andReturn().getResponse().getContentAsString();
        JsonNode line = mapper.readTree(jsonl.strip().split("\n")[0]);
        assertEquals("controlled_experiment", line.get("label_source").asText());
        assertEquals("backpack_walking", line.get("experiment_condition").asText());
        assertFalse(line.get("confirmed_intentional").asBoolean());
    }

    @Test
    void experimentFieldsRejectedOnNormalEvents() throws Exception {
        postEvent(register(), "{\"riskScore\":0.5,\"experimentIntentional\":true}").andExpect(status().isBadRequest());
    }

    @Test
    void deleteMeRemovesEventsAndInvalidatesCredentials() throws Exception {
        JsonNode dev = register();
        postEvent(dev, "{\"riskScore\":0.8,\"reason\":\"x\",\"occurredAtEpochMs\":1}").andExpect(status().isOk());
        String token = dev.get("deviceToken").asText();
        assertEquals(1, events.findByDeviceTokenAndOccurredAtAfter(token, java.time.Instant.EPOCH).size());

        String body = mvc.perform(delete("/api/v1/devices/me")
                        .header("X-Device-Token", token).header("Authorization", "Bearer " + dev.get("apiKey").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(body).get("deletedEvents").asInt());
        assertTrue(events.findByDeviceTokenAndOccurredAtAfter(token, java.time.Instant.EPOCH).isEmpty());
        // 등록 정보도 지워져 같은 키로는 더 이상 인증되지 않음
        postEvent(dev, "{\"riskScore\":0.8}").andExpect(status().isUnauthorized());
    }

    @Test
    void nonceEndpointReportsIntegrityOffByDefault() throws Exception {
        JsonNode n = mapper.readTree(mvc.perform(get("/api/v1/devices/nonce"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(n.get("integrityRequired").asBoolean());
        assertTrue(n.get("nonce").asText().length() >= 24);
    }

    @Test
    void retentionJobRunsWithoutDeletingFreshEvents() throws Exception {
        postEvent(register(), "{\"riskScore\":0.8,\"occurredAtEpochMs\":1}").andExpect(status().isOk());
        long before = events.count();
        assertEquals(0, retentionJob.purgeExpired()); // 방금 받은 이벤트는 보관 기간 안
        assertEquals(before, events.count());
    }
}
