package com.nfcguard.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nfcguard.server.model.AnomalyEvent;
import com.nfcguard.server.model.AnomalyEventRequest;
import com.nfcguard.server.repository.AnomalyEventRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class AnomalyEventService {

    /** 2.5초 @ 50Hz = 125개. 여유를 두되 과도한 페이로드는 거절 */
    static final int MAX_SENSOR_SAMPLES = 1000;
    private static final Set<String> SOURCES =
            Set.of(AnomalyEvent.SOURCE_FIELD_DETECTION, AnomalyEvent.SOURCE_ONBOARDING);

    public enum ConfirmResult { OK, NOT_FOUND, FORBIDDEN }

    private final AnomalyEventRepository repository;
    private final ObjectMapper objectMapper;

    public AnomalyEventService(AnomalyEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** @throws IllegalArgumentException 페이로드가 학습 데이터로 쓸 수 없는 형태일 때 */
    public AnomalyEvent record(String deviceToken, AnomalyEventRequest req) {
        if (req.source == null || !SOURCES.contains(req.source)) {
            throw new IllegalArgumentException("unknown source: " + req.source);
        }
        JsonNode raw = req.rawSensorWindow;
        if (raw != null && (!raw.isArray() || raw.size() > MAX_SENSOR_SAMPLES)) {
            throw new IllegalArgumentException("rawSensorWindow must be an array of <= " + MAX_SENSOR_SAMPLES + " samples");
        }
        String rawJson = raw == null ? null : raw.toString();
        AnomalyEvent event = new AnomalyEvent(deviceToken, req, rawJson, Instant.now());
        // 온보딩 샘플은 사용자가 "지금 태그했다"고 직접 확인한 것이므로 정상(의도적) 라벨로 바로 편입.
        // 의심 이벤트만 쌓이면 학습 데이터가 한쪽 클래스로 치우치기 때문에 이 경로가 꼭 필요하다.
        if (AnomalyEvent.SOURCE_ONBOARDING.equals(req.source)) {
            event.setConfirmedIntentional(true);
        }
        return repository.save(event);
    }

    /** 셀프리포트/사용자 확인으로 라벨 확정 -> 파인튜닝 데이터셋에 편입. 본인 기기 이벤트만 가능 */
    public ConfirmResult confirmLabel(String deviceToken, Long eventId, boolean wasIntentional) {
        var found = repository.findById(eventId);
        if (found.isEmpty()) return ConfirmResult.NOT_FOUND;
        AnomalyEvent event = found.get();
        if (!event.getDeviceToken().equals(deviceToken)) return ConfirmResult.FORBIDDEN;
        event.setConfirmedIntentional(wasIntentional);
        repository.save(event);
        return ConfirmResult.OK;
    }

    /**
     * Python 파인튜닝 스크립트가 읽을 JSONL(줄바꿈 구분 JSON) 덤프.
     * CSV가 아니라 JSONL인 이유: raw_sensor_window 자체가 배열이라 CSV 셀 하나에 못 넣음.
     * 각 줄 = {"id":.., "raw_sensor_window": [...], "confirmed_intentional": true/false, ...}
     * afterId를 주면 그 이후 이벤트만 (증분 수집).
     */
    public String exportLabeledDatasetAsJsonl(long afterId) {
        List<AnomalyEvent> labeled =
                repository.findByConfirmedIntentionalIsNotNullAndIdGreaterThanOrderByIdAsc(afterId);
        StringBuilder sb = new StringBuilder();
        for (AnomalyEvent e : labeled) {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("id", e.getId());
            line.put("source", e.getSource());
            line.put("risk_score", e.getRiskScore());
            line.put("reason", e.getReason());
            line.put("repeat_count", e.getRepeatCountAtReport());
            line.put("net_displacement", e.getNetDisplacement());
            line.put("orientation_shift", e.getOrientationShift());
            line.put("pocket_score", e.getPocketScore());
            line.put("proximity_near", e.getProximityNear());
            line.put("ambient_lux", e.getAmbientLux());
            line.put("screen_interactive", e.getScreenInteractive());
            line.put("model_version", e.getModelVersion());
            line.put("confirmed_intentional", e.getConfirmedIntentional());
            line.set("raw_sensor_window", parseRaw(e.getRawSensorWindowJson()));
            sb.append(line.toString()).append('\n');
        }
        return sb.toString();
    }

    private JsonNode parseRaw(String rawJson) {
        if (rawJson == null) return objectMapper.nullNode();
        try {
            return objectMapper.readTree(rawJson);
        } catch (JsonProcessingException ex) {
            return objectMapper.nullNode();
        }
    }
}
