package com.nfcguard.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "nfcguard.admin.api-key=test-admin",
        "nfcguard.rate-limit.per-device-per-hour=1000"
})
@AutoConfigureMockMvc
class AnomalyEventApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final String EVENT = """
            {"riskScore":0.95,"reason":"tapped_while_in_bag_or_pocket \\"q\\"","repeatCount":1,
             "occurredAtEpochMs":1700000000000,"pocketScore":1.0,"proximityNear":true,"ambientLux":1.5,
             "screenInteractive":false,
             "rawSensorWindow":[{"t":0,"ax":0.1,"ay":0,"az":0,"gx":0,"gy":0,"gz":0}]}
            """;

    private JsonNode register() throws Exception {
        String body = mvc.perform(post("/api/v1/devices/register"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private long ingest(JsonNode dev, String json) throws Exception {
        String body = mvc.perform(post("/api/v1/events")
                        .header("X-Device-Token", dev.get("deviceToken").asText())
                        .header("Authorization", "Bearer " + dev.get("apiKey").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    @Test
    void rejectsUnauthenticatedEvents() throws Exception {
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isUnauthorized());
        JsonNode dev = register();
        mvc.perform(post("/api/v1/events")
                        .header("X-Device-Token", dev.get("deviceToken").asText())
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmIsOwnerOnlyAnd404ForMissing() throws Exception {
        JsonNode owner = register();
        JsonNode other = register();
        long id = ingest(owner, EVENT);

        mvc.perform(post("/api/v1/events/" + id + "/confirm").param("wasIntentional", "false")
                        .header("X-Device-Token", other.get("deviceToken").asText())
                        .header("Authorization", "Bearer " + other.get("apiKey").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/events/999999/confirm").param("wasIntentional", "false")
                        .header("X-Device-Token", owner.get("deviceToken").asText())
                        .header("Authorization", "Bearer " + owner.get("apiKey").asText()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/events/" + id + "/confirm").param("wasIntentional", "false")
                        .header("X-Device-Token", owner.get("deviceToken").asText())
                        .header("Authorization", "Bearer " + owner.get("apiKey").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void exportProducesValidJsonlIncludingOnboardingPositives() throws Exception {
        JsonNode dev = register();
        long suspicious = ingest(dev, EVENT);
        mvc.perform(post("/api/v1/events/" + suspicious + "/confirm").param("wasIntentional", "false")
                .header("X-Device-Token", dev.get("deviceToken").asText())
                .header("Authorization", "Bearer " + dev.get("apiKey").asText()));
        long onboarding = ingest(dev, EVENT.replace("{\"riskScore\"", "{\"source\":\"onboarding\",\"riskScore\""));

        mvc.perform(get("/api/v1/events/dataset/export")).andExpect(status().isUnauthorized());

        String jsonl = mvc.perform(get("/api/v1/events/dataset/export")
                        .param("afterId", String.valueOf(suspicious - 1))
                        .header("X-Admin-Key", "test-admin"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String[] lines = jsonl.strip().split("\n");
        assertEquals(2, lines.length);
        JsonNode first = mapper.readTree(lines[0]);   // 따옴표가 든 reason도 깨지지 않아야 함
        assertEquals(suspicious, first.get("id").asLong());
        assertFalse(first.get("confirmed_intentional").asBoolean());
        assertTrue(first.get("raw_sensor_window").isArray());
        assertEquals("tapped_while_in_bag_or_pocket \"q\"", first.get("reason").asText());
        JsonNode second = mapper.readTree(lines[1]);
        assertEquals(onboarding, second.get("id").asLong());
        assertTrue(second.get("confirmed_intentional").asBoolean());
    }

    @Test
    void rejectsNonArraySensorWindow() throws Exception {
        JsonNode dev = register();
        mvc.perform(post("/api/v1/events")
                        .header("X-Device-Token", dev.get("deviceToken").asText())
                        .header("Authorization", "Bearer " + dev.get("apiKey").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskScore\":0.5,\"rawSensorWindow\":{\"x\":1}}"))
                .andExpect(status().isBadRequest());
    }
}
