package com.nfcguard.server.controller;

import com.nfcguard.server.auth.DeviceAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 앱 최초 실행 시 1회 호출 -> 익명 기기 토큰 + API 키 발급 (앱은 EncryptedSharedPreferences 등에 보관) */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceAuthService authService;

    public DeviceController(DeviceAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<DeviceAuthService.Registration> register() {
        return ResponseEntity.ok(authService.register());
    }
}
