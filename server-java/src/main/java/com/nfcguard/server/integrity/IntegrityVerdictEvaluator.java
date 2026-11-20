package com.nfcguard.server.integrity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * decodeIntegrityToken 응답(tokenPayloadExternal)의 판정 규칙. 네트워크와 분리해 단위 테스트 가능.
 * 참고: https://developer.android.com/google/play/integrity/verdicts
 */
public class IntegrityVerdictEvaluator {

    private final String packageName;
    private final boolean requirePlayRecognized;

    public IntegrityVerdictEvaluator(String packageName, boolean requirePlayRecognized) {
        this.packageName = packageName;
        this.requirePlayRecognized = requirePlayRecognized;
    }

    /** @return 통과하면 null, 실패하면 사유 */
    public String evaluate(JsonNode decoded, String expectedNonce) {
        JsonNode payload = decoded.path("tokenPayloadExternal");
        JsonNode request = payload.path("requestDetails");
        if (!packageName.equals(request.path("requestPackageName").asText())) return "package_mismatch";
        if (!expectedNonce.equals(request.path("nonce").asText())) return "nonce_mismatch";

        if (requirePlayRecognized) {
            // 플레이스토어에서 설치된 정식 앱만 (사이드로드/변조 앱 거절). 내부 테스트 트랙도 PLAY_RECOGNIZED.
            String app = payload.path("appIntegrity").path("appRecognitionVerdict").asText();
            if (!"PLAY_RECOGNIZED".equals(app)) return "app_not_recognized:" + app;
        }
        for (JsonNode v : payload.path("deviceIntegrity").path("deviceRecognitionVerdict")) {
            if ("MEETS_DEVICE_INTEGRITY".equals(v.asText()) || "MEETS_STRONG_INTEGRITY".equals(v.asText())) {
                return null;
            }
        }
        return "device_integrity_failed";
    }
}
