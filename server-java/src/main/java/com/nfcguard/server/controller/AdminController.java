package com.nfcguard.server.controller;

import com.nfcguard.server.auth.DeviceAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 관리자 전용 (X-Admin-Key). 통제 실험용 테스트 휴대폰 지정 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final DeviceAuthService authService;

    public AdminController(DeviceAuthService authService) {
        this.authService = authService;
    }

    @PutMapping("/devices/{deviceToken}/test-device")
    public ResponseEntity<Void> setTestDevice(@PathVariable String deviceToken, @RequestParam boolean enabled) {
        return authService.setTestDevice(deviceToken, enabled)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
