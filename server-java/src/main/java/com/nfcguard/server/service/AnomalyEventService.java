package com.nfcguard.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nfcguard.server.model.AnomalyEvent;
import com.nfcguard.server.model.AnomalyEventRequest;
import com.nfcguard.server.model.Device;
import com.nfcguard.server.repository.AnomalyEventRepository;
import com.nfcguard.server.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class AnomalyEventService {

    /** 2.5초 @ 50Hz = 125개. 여유를 두되 과도한 페이로드는 거절 */
    static final int MAX_SENSOR_SAMPLES = 1000;
    private static final Set<String> SOURCES = Set.of(
            AnomalyEvent.SOURCE_FIELD_DETECTION, AnomalyEvent.SOURCE_ONBOARDING, AnomalyEvent.SOURCE_CONTROLLED_EXPERIMENT);

    public enum ConfirmResult { OK, NOT_FOUND, FORBIDDEN }

    private final AnomalyEventRepository repository;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public AnomalyEventService(AnomalyEventRepository repository, DeviceRepository deviceRepository,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
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
        boolean experiment = AnomalyEvent.SOURCE_CONTROLLED_EXPERIMENT.equals(req.source);
        if (experiment) {
            // 라벨을 직접 지정하는 경로라 아무 기기나 쓰면 학습 데이터 오염 -> 관리자가 지정한 테스트 기기만
            boolean testDevice = deviceRepository.findByDeviceToken(deviceToken).map(Device::isTestDevice).orElse(false);
            if (!testDevice) throw new SecurityException("controlled_experiment is allowed only for test devices");
            if (req.experimentIntentional == null) {
                throw new IllegalArgumentException("controlled_experiment requires experimentIntentional");
            }
        } else if (req.experimentIntentional != null || req.experimentCondition != null) {
            throw new IllegalArgumentException("experiment fields are only for controlled_experiment");
        }
        String rawJson = raw == null ? null : raw.toString();
        AnomalyEvent event = new AnomalyEvent(deviceToken, req, rawJson, Instant.now());
        if (experiment) {
            event.setLabel(req.experimentIntentional, AnomalyEvent.LABEL_SOURCE_CONTROLLED_EXPERIMENT);
        } else if (AnomalyEvent.SOURCE_ONBOARDING.equals(req.source)) {
            // 사용자가 "지금 태그했다"고 직접 누른 샘플 -> 의도적 태깅 라벨 (출처: onboarding)
            event.setLabel(true, AnomalyEvent.LABEL_SOURCE_ONBOARDING);
        }
        return repository.save(event);
    }

    /** 셀프리포트/사용자 확인으로 라벨 확정 -> 파인튜닝 데이터셋에 편입. 본인 기기 이벤트만 가능 */
    public ConfirmResult confirmLabel(String deviceToken, Long eventId, boolean wasIntentional) {
        var found = repository.findById(eventId);
        if (found.isEmpty()) return ConfirmResult.NOT_FOUND;
        AnomalyEvent event = found.get();
        if (!event.getDeviceToken().equals(deviceToken)) return ConfirmResult.FORBIDDEN;
        event.setLabel(wasIntentional, AnomalyEvent.LABEL_SOURCE_USER_RESPONSE);
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
            line.put("label_source", e.getLabelSource());
            line.put("experiment_condition", e.getExperimentCondition());
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
