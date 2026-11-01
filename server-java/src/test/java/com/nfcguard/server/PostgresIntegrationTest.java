package com.nfcguard.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 PostgreSQL에서 수집 -> 라벨 확정 -> export 전체 흐름 검증.
 * NFCGUARD_TEST_PG_URL 이 있을 때만 실행 (예: jdbc:postgresql://localhost:5432/nfcguard).
 * H2에서는 통과해도 PostgreSQL 타입 매핑(@Lob 등) 문제는 여기서만 드러난다.
 */
@SpringBootTest(properties = {
        "nfcguard.admin.api-key=pg-admin",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("postgres")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "NFCGUARD_TEST_PG_URL", matches = ".+")
class PostgresIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("NFCGUARD_TEST_PG_URL"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void ingestConfirmExportRoundTrip() throws Exception {
        JsonNode dev = mapper.readTree(mvc.perform(post("/api/v1/devices/register"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = dev.get("deviceToken").asText();
        String key = dev.get("apiKey").asText();

        // 2.5초 @ 50Hz 수준의 큰 시계열 (긴 텍스트 컬럼 매핑 검증)
        StringBuilder window = new StringBuilder("[");
        for (int i = 0; i < 125; i++) {
            if (i > 0) window.append(',');
            window.append("{\"t\":").append(i * 20_000_000L)
                  .append(",\"ax\":0.123456,\"ay\":-0.5,\"az\":9.0,\"gx\":0.01,\"gy\":0.02,\"gz\":0.03}");
        }
        window.append(']');
        String body = "{\"riskScore\":0.9,\"reason\":\"tapped_while_in_bag_or_pocket\",\"repeatCount\":1,"
                + "\"occurredAtEpochMs\":1700000000000,\"rawSensorWindow\":" + window + "}";

        long id = mapper.readTree(mvc.perform(post("/api/v1/events")
                        .header("X-Device-Token", token).header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(post("/api/v1/events/" + id + "/confirm").param("wasIntentional", "false")
                        .header("X-Device-Token", token).header("Authorization", "Bearer " + key))
                .andExpect(status().isOk());

        String jsonl = mvc.perform(get("/api/v1/events/dataset/export").header("X-Admin-Key", "pg-admin"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode line = mapper.readTree(jsonl.strip().split("\n")[0]);
        assertEquals(id, line.get("id").asLong());
        assertEquals(125, line.get("raw_sensor_window").size());
        assertFalse(line.get("confirmed_intentional").asBoolean());
    }
}
