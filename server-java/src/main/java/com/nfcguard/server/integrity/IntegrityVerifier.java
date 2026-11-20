package com.nfcguard.server.integrity;

/** 기기 등록 시 Play Integrity 토큰 검증. nfcguard.integrity.mode=off 이면 항상 통과(개발용). */
public interface IntegrityVerifier {

    boolean enabled();

    /** @return 통과하면 null, 실패하면 거절 사유 */
    String verify(String integrityToken, String expectedNonce);
}
