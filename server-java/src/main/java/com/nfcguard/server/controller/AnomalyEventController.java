package com.nfcguard.server.controller;

import com.nfcguard.server.auth.DeviceAuthInterceptor;
import com.nfcguard.server.model.AnomalyEventRequest;
import com.nfcguard.server.service.AnomalyEventService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
public class AnomalyEventController {

    private final AnomalyEventService service;

    public AnomalyEventController(AnomalyEventService service) {
        this.service = service;
    }

    /** 안드로이드 AnomalyReporter(의심 이벤트) / 온보딩(정상 샘플) 업로드. 응답 id는 나중에 confirm에 사용 */
    @PostMapping
    public ResponseEntity<?> ingest(@RequestAttribute(DeviceAuthInterceptor.DEVICE_TOKEN_ATTR) String deviceToken,
                                    @RequestBody AnomalyEventRequest req) {
        try {
            var saved = service.record(deviceToken, req);
            return ResponseEntity.ok(Map.of("id", saved.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 알림의 "내가 한 거 맞음 / 아님" 버튼 -> 파인튜닝 정답 라벨 */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@RequestAttribute(DeviceAuthInterceptor.DEVICE_TOKEN_ATTR) String deviceToken,
                                        @PathVariable Long id, @RequestParam boolean wasIntentional) {
        return switch (service.confirmLabel(deviceToken, id, wasIntentional)) {
            case OK -> ResponseEntity.ok().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        };
    }

    /** Python 파인튜닝 스크립트가 주기적으로 호출해서 최신 라벨링 데이터를 가져감 (X-Admin-Key 필요) */
    @GetMapping(value = "/dataset/export", produces = "application/x-ndjson")
    public ResponseEntity<String> exportDataset(@RequestParam(defaultValue = "0") long afterId) {
        return ResponseEntity.ok(service.exportLabeledDatasetAsJsonl(afterId));
    }
}
