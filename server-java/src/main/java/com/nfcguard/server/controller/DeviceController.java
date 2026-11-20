package com.nfcguard.server.controller;

import com.nfcguard.server.auth.DeviceAuthInterceptor;
import com.nfcguard.server.auth.DeviceAuthService;
import com.nfcguard.server.integrity.IntegrityVerifier;
import com.nfcguard.server.integrity.NonceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 기기 등록/삭제.
 * 등록 흐름: GET /nonce -> 앱이 Play Integrity 토큰 요청(nonce 포함) -> POST /register {nonce, integrityToken}
 * nfcguard.integrity.mode=off 이면 본문 없이 등록 가능 (개발용).
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    public record RegisterRequest(String nonce, String integrityToken) {}

    private final DeviceAuthService authService;
    private final NonceService nonceService;
    private final IntegrityVerifier integrityVerifier;

    public DeviceController(DeviceAuthService authService, NonceService nonceService, IntegrityVerifier integrityVerifier) {
        this.authService = authService;
        this.nonceService = nonceService;
        this.integrityVerifier = integrityVerifier;
    }

    @GetMapping("/nonce")
    public Map<String, Object> nonce() {
        return Map.of("nonce", nonceService.issue(), "integrityRequired", integrityVerifier.enabled());
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody(required = false) RegisterRequest req) {
        if (integrityVerifier.enabled()) {
            if (req == null || !nonceService.isValid(req.nonce())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid_nonce"));
            }
            String failure = integrityVerifier.verify(req.integrityToken(), req.nonce());
            if (failure != null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "integrity_" + failure));
            }
        }
        return ResponseEntity.ok(authService.register());
    }

    /** 앱 "내 데이터 삭제": 서버에 있는 이 기기의 이벤트·라벨·등록 정보를 모두 삭제 */
    @DeleteMapping("/me")
    public Map<String, Object> deleteMe(@RequestAttribute(DeviceAuthInterceptor.DEVICE_TOKEN_ATTR) String deviceToken) {
        return Map.of("deletedEvents", authService.deleteDeviceAndData(deviceToken));
    }
}
