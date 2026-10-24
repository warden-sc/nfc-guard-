package com.nfcguard.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:modeltest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ModelDistributionApiTest {

    static Path modelDir;

    @BeforeAll
    static void setUp() throws Exception {
        modelDir = Files.createTempDirectory("models");
        Files.writeString(modelDir.resolve("gesture_classifier.json"), "{\"version\": 1,\n  \"sha256\": \"ab\"}");
        Files.writeString(modelDir.resolve("gesture_classifier.json.sig"), "c2ln\n");
        Files.write(modelDir.resolve("gesture_classifier.tflite"), new byte[]{1, 2, 3});
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("nfcguard.model.dir", () -> modelDir.toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void servesMetadataBytesVerbatimWithSignatureHeader() throws Exception {
        mvc.perform(get("/api/v1/model/metadata"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Model-Signature", "c2ln"))
                .andExpect(content().string("{\"version\": 1,\n  \"sha256\": \"ab\"}"));
    }

    @Test
    void usesConfiguredModelDir() throws Exception {
        mvc.perform(get("/api/v1/model/latest"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }
}
