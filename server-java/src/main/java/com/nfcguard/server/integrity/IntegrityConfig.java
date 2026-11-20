package com.nfcguard.server.integrity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Configuration
public class IntegrityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * nfcguard.integrity.mode
     *  - off(기본): 검증 안 함. 개발/에뮬레이터용
     *  - google: Play Integrity API로 검증. 서비스 계정 키는 GOOGLE_APPLICATION_CREDENTIALS 로 지정
     *    (Google Cloud 프로젝트에서 Play Integrity API 사용 설정 + Play Console에 프로젝트 연결 필요)
     */
    @Bean
    IntegrityVerifier integrityVerifier(@Value("${nfcguard.integrity.mode:off}") String mode,
                                        @Value("${nfcguard.integrity.package-name:com.nfcguard.app}") String packageName,
                                        @Value("${nfcguard.integrity.require-play-recognized:true}") boolean requirePlay,
                                        ObjectMapper mapper) {
        if (!"google".equals(mode)) {
            return new IntegrityVerifier() {
                public boolean enabled() { return false; }
                public String verify(String token, String nonce) { return null; }
            };
        }
        IntegrityVerdictEvaluator evaluator = new IntegrityVerdictEvaluator(packageName, requirePlay);
        RestClient client = RestClient.create("https://playintegrity.googleapis.com");
        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of("https://www.googleapis.com/auth/playintegrity"));
        } catch (Exception e) {
            throw new IllegalStateException("Play Integrity 모드인데 서비스 계정 자격 증명을 찾지 못함", e);
        }
        return new IntegrityVerifier() {
            public boolean enabled() { return true; }

            public String verify(String token, String nonce) {
                if (token == null || token.isBlank()) return "missing_token";
                try {
                    credentials.refreshIfExpired();
                    JsonNode decoded = client.post()
                            .uri("/v1/{pkg}:decodeIntegrityToken", packageName)
                            .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("integrity_token", token))
                            .retrieve()
                            .body(JsonNode.class);
                    return decoded == null ? "empty_response" : evaluator.evaluate(decoded, nonce);
                } catch (Exception e) {
                    return "verify_error";
                }
            }
        };
    }
}
