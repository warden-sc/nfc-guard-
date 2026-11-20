package com.nfcguard.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nfcguard.server.integrity.IntegrityVerdictEvaluator;
import com.nfcguard.server.integrity.NonceService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class IntegrityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nonceValidOnlyWithinTtlAndUntampered() {
        Instant t0 = Instant.parse("2026-11-20T00:00:00Z");
        NonceService issuer = new NonceService("secret", Clock.fixed(t0, ZoneOffset.UTC));
        String nonce = issuer.issue();

        assertTrue(new NonceService("secret", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC)).isValid(nonce));
        assertFalse(new NonceService("secret", Clock.fixed(t0.plus(Duration.ofMinutes(6)), ZoneOffset.UTC)).isValid(nonce));
        assertFalse(new NonceService("other", Clock.fixed(t0, ZoneOffset.UTC)).isValid(nonce));
        char last = nonce.charAt(nonce.length() - 1);
        String tampered = nonce.substring(0, nonce.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertFalse(issuer.isValid(tampered));
        assertFalse(issuer.isValid("not-base64!!"));
    }

    private String verdict(String pkg, String nonce, String app, String device) {
        return """
                {"tokenPayloadExternal":{
                  "requestDetails":{"requestPackageName":"%s","nonce":"%s","timestampMillis":"1"},
                  "appIntegrity":{"appRecognitionVerdict":"%s"},
                  "deviceIntegrity":{"deviceRecognitionVerdict":[%s]}}}
                """.formatted(pkg, nonce, app, device);
    }

    @Test
    void evaluatorChecksPackageNonceAppAndDevice() throws Exception {
        var eval = new IntegrityVerdictEvaluator("com.nfcguard.app", true);
        String ok = verdict("com.nfcguard.app", "n1", "PLAY_RECOGNIZED", "\"MEETS_DEVICE_INTEGRITY\"");
        assertNull(eval.evaluate(mapper.readTree(ok), "n1"));
        assertEquals("nonce_mismatch", eval.evaluate(mapper.readTree(ok), "n2"));
        assertEquals("package_mismatch",
                eval.evaluate(mapper.readTree(verdict("evil.app", "n1", "PLAY_RECOGNIZED", "\"MEETS_DEVICE_INTEGRITY\"")), "n1"));
        assertEquals("app_not_recognized:UNRECOGNIZED_VERSION",
                eval.evaluate(mapper.readTree(verdict("com.nfcguard.app", "n1", "UNRECOGNIZED_VERSION", "\"MEETS_DEVICE_INTEGRITY\"")), "n1"));
        assertEquals("device_integrity_failed",
                eval.evaluate(mapper.readTree(verdict("com.nfcguard.app", "n1", "PLAY_RECOGNIZED", "")), "n1"));
        // 사이드로드 개발 빌드 허용 모드
        assertNull(new IntegrityVerdictEvaluator("com.nfcguard.app", false)
                .evaluate(mapper.readTree(verdict("com.nfcguard.app", "n1", "UNEVALUATED", "\"MEETS_DEVICE_INTEGRITY\"")), "n1"));
    }
}
