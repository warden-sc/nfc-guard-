package com.nfcguard.server.integrity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Play Integrity 요청에 넣을 nonce를 서버가 발급/검증한다 (재사용 공격 방지).
 * DB 없이 동작하도록 "발급시각 + 난수 + HMAC" 형태로 서명. 유효시간 TTL_MS.
 * 여러 서버 인스턴스에서 쓰려면 NFCGUARD_NONCE_SECRET 을 같은 값으로 설정해야 한다.
 */
@Service
public class NonceService {

    static final long TTL_MS = 5 * 60 * 1000L;
    private static final int RANDOM_BYTES = 16;
    private static final int MAC_BYTES = 32;

    private final byte[] secret;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public NonceService(@Value("${nfcguard.integrity.nonce-secret:}") String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            byte[] generated = new byte[32];
            random.nextBytes(generated);
            this.secret = generated; // 단일 인스턴스 개발용
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.clock = clock;
    }

    /** URL-safe base64 (Play Integrity nonce 형식 요구사항 충족: 16바이트 이상, base64url, no wrap) */
    public String issue() {
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + RANDOM_BYTES + MAC_BYTES);
        byte[] rnd = new byte[RANDOM_BYTES];
        random.nextBytes(rnd);
        buf.putLong(clock.millis()).put(rnd);
        buf.put(mac(buf.array(), Long.BYTES + RANDOM_BYTES));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
    }

    public boolean isValid(String nonce) {
        if (nonce == null) return false;
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(nonce);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (raw.length != Long.BYTES + RANDOM_BYTES + MAC_BYTES) return false;
        int body = Long.BYTES + RANDOM_BYTES;
        byte[] expected = mac(raw, body);
        byte[] given = new byte[MAC_BYTES];
        System.arraycopy(raw, body, given, 0, MAC_BYTES);
        if (!MessageDigest.isEqual(expected, given)) return false;
        long issuedAt = ByteBuffer.wrap(raw, 0, Long.BYTES).getLong();
        long age = clock.millis() - issuedAt;
        return age >= 0 && age <= TTL_MS;
    }

    private byte[] mac(byte[] data, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(data, 0, length);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
