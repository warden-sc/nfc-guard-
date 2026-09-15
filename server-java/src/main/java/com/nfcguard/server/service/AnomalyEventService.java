package com.nfcguard.server.service;

import com.nfcguard.server.model.AnomalyEvent;
import com.nfcguard.server.repository.AnomalyEventRepository;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

@Service
public class AnomalyEventService {

    private final AnomalyEventRepository repository;

    public AnomalyEventService(AnomalyEventRepository repository) {
        this.repository = repository;
    }

    public AnomalyEvent record(String deviceToken, double riskScore, String reason,
                                int repeatCount, long occurredAtEpochMs, String rawSensorWindowJson) {
        AnomalyEvent event = new AnomalyEvent(
                deviceToken, riskScore, reason, repeatCount,
                Instant.ofEpochMilli(occurredAtEpochMs), rawSensorWindowJson);
        return repository.save(event);
    }

    /** 셀프리포트/사용자 확인으로 라벨 확정 -> 파인튜닝 데이터셋에 편입 */
    public void confirmLabel(Long eventId, boolean wasIntentional) {
        repository.findById(eventId).ifPresent(event -> {
            event.setConfirmedIntentional(wasIntentional);
            repository.save(event);
        });
    }

    /**
     * Python 파인튜닝 스크립트가 읽을 JSONL(줄바꿈 구분 JSON) 덤프.
     * CSV가 아니라 JSONL인 이유: raw_sensor_window 자체가 배열이라 CSV 셀 하나에 못 넣음.
     * 각 줄 = {"raw_sensor_window": [...], "confirmed_intentional": true/false, ...}
     */
    public String exportLabeledDatasetAsJsonl() {
        List<AnomalyEvent> labeled = repository.findByConfirmedIntentionalIsNotNull();
        StringWriter sw = new StringWriter();
        for (AnomalyEvent e : labeled) {
            sw.write(String.format(
                "{\"risk_score\":%.4f,\"reason\":\"%s\",\"repeat_count\":%d,\"confirmed_intentional\":%s,\"raw_sensor_window\":%s}%n",
                e.getRiskScore(), e.getReason(), e.getRepeatCountAtReport(),
                e.getConfirmedIntentional(),
                e.getRawSensorWindowJson() == null ? "null" : e.getRawSensorWindowJson()
            ));
        }
        return sw.toString();
    }
}
