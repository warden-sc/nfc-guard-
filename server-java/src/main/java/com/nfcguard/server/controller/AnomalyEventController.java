package com.nfcguard.server.controller;

import com.nfcguard.server.model.AnomalyEventRequest;
import com.nfcguard.server.service.AnomalyEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
public class AnomalyEventController {

    private final AnomalyEventService service;

    public AnomalyEventController(AnomalyEventService service) {
        this.service = service;
    }

    /** 안드로이드 AnomalyReporter.postToBackendStub()가 실제로 호출하게 될 엔드포인트 */
    @PostMapping
    public ResponseEntity<Long> ingest(@RequestBody AnomalyEventRequest req) {
        var saved = service.record(
                req.deviceToken, req.riskScore, req.reason,
                req.repeatCount, req.occurredAtEpochMs, req.rawSensorWindowJson);
        return ResponseEntity.ok(saved.getId());
    }

    /** 셀프리포트(안드로이드에도 iOS처럼 "이거 내가 한 거 맞음/의심됨" UI 추가 시 호출) */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable Long id, @RequestParam boolean wasIntentional) {
        service.confirmLabel(id, wasIntentional);
        return ResponseEntity.ok().build();
    }

    /** Python 파인튜닝 스크립트가 주기적으로 호출해서 최신 라벨링 데이터를 가져감 */
    @GetMapping(value = "/dataset/export", produces = "application/x-ndjson")
    public ResponseEntity<String> exportDataset() {
        return ResponseEntity.ok(service.exportLabeledDatasetAsJsonl());
    }
}
