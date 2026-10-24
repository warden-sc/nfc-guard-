package com.nfcguard.server.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 안드로이드 AnomalyReporter.postToBackendStub()에서 실제로 보낼 페이로드에 대응.
 * 개인정보(정확한 위치, 사용자 식별정보)는 최소화 - 익명 디바이스 토큰만 사용.
 */
@Entity
@Table(name = "anomaly_events")
public class AnomalyEvent {

    public static final String SOURCE_FIELD_DETECTION = "field_detection";
    public static final String SOURCE_ONBOARDING = "onboarding";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceToken;       // 사용자 식별 대신 익명 디바이스 토큰
    private double riskScore;         // GestureMatcher.Result.riskScore
    private String reason;            // GestureMatcher.Result.reason
    private int repeatCountAtReport;  // 신고 시점의 로컬 반복횟수
    private Instant occurredAt;
    private Instant receivedAt;
    private String source;            // SOURCE_FIELD_DETECTION / SOURCE_ONBOARDING

    private Double netDisplacement;
    private Double orientationShift;
    private Double pocketScore;
    private Boolean proximityNear;
    private Float ambientLux;
    private Boolean screenInteractive;
    private Long modelVersion;

    // 사용자가 나중에 셀프리포트로 확정한 라벨 (파인튜닝 데이터로 사용)
    private Boolean confirmedIntentional; // null = 아직 미확정

    // 파인튜닝의 실제 원재료. MotionBuffer.snapshot()을 JSON 배열로 직렬화한 것.
    // risk_score 같은 요약값만으로는 모델을 재학습시킬 수 없음 - 원본 시계열이 필요.
    @Lob
    private String rawSensorWindowJson;

    protected AnomalyEvent() {}

    public AnomalyEvent(String deviceToken, AnomalyEventRequest req, String rawSensorWindowJson, Instant receivedAt) {
        this.deviceToken = deviceToken;
        this.source = req.source;
        this.riskScore = req.riskScore;
        this.reason = req.reason;
        this.repeatCountAtReport = req.repeatCount;
        this.occurredAt = Instant.ofEpochMilli(req.occurredAtEpochMs);
        this.receivedAt = receivedAt;
        this.netDisplacement = req.netDisplacement;
        this.orientationShift = req.orientationShift;
        this.pocketScore = req.pocketScore;
        this.proximityNear = req.proximityNear;
        this.ambientLux = req.ambientLux;
        this.screenInteractive = req.screenInteractive;
        this.modelVersion = req.modelVersion;
        this.rawSensorWindowJson = rawSensorWindowJson;
    }

    // getters/setters
    public Long getId() { return id; }
    public String getDeviceToken() { return deviceToken; }
    public double getRiskScore() { return riskScore; }
    public String getReason() { return reason; }
    public int getRepeatCountAtReport() { return repeatCountAtReport; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getSource() { return source; }
    public Double getNetDisplacement() { return netDisplacement; }
    public Double getOrientationShift() { return orientationShift; }
    public Double getPocketScore() { return pocketScore; }
    public Boolean getProximityNear() { return proximityNear; }
    public Float getAmbientLux() { return ambientLux; }
    public Boolean getScreenInteractive() { return screenInteractive; }
    public Long getModelVersion() { return modelVersion; }
    public Boolean getConfirmedIntentional() { return confirmedIntentional; }
    public void setConfirmedIntentional(Boolean confirmedIntentional) {
        this.confirmedIntentional = confirmedIntentional;
    }
    public String getRawSensorWindowJson() { return rawSensorWindowJson; }
}
