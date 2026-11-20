package com.nfcguard.server.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 안드로이드 AnomalyReporter가 보내는 NFC 감지 이벤트.
 * 개인정보 최소화: 사용자 식별정보 대신 익명 디바이스 토큰만, 위치는 서버에 저장하지 않음(기기 내 기록만).
 */
@Entity
@Table(name = "anomaly_events")
public class AnomalyEvent {

    public static final String SOURCE_FIELD_DETECTION = "field_detection";
    public static final String SOURCE_ONBOARDING = "onboarding";
    /** 테스트 휴대폰으로 진행한 통제 실험 수집 (PROJECT_SCOPE 3절). 라벨은 실험 조건에서 정해짐 */
    public static final String SOURCE_CONTROLLED_EXPERIMENT = "controlled_experiment";

    // 라벨 출처: 사용자 응답, 온보딩, 통제 실험은 같은 증거가 아니므로 구분해 남긴다 (PROJECT_SCOPE 3절)
    public static final String LABEL_SOURCE_USER_RESPONSE = "user_response";
    public static final String LABEL_SOURCE_ONBOARDING = "onboarding";
    public static final String LABEL_SOURCE_CONTROLLED_EXPERIMENT = "controlled_experiment";

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
    private String labelSource;           // LABEL_SOURCE_* (라벨이 없으면 null)
    private String experimentCondition;   // 통제 실험 조건 메모 (예: "backpack_walking"), 그 외 null

    // 파인튜닝의 실제 원재료. MotionBuffer.snapshot()을 JSON 배열로 직렬화한 것.
    // risk_score 같은 요약값만으로는 모델을 재학습시킬 수 없음 - 원본 시계열이 필요.
    // @Lob을 쓰면 PostgreSQL에서 oid(Large Object)로 매핑되어 트랜잭션 밖 조회 시
    // "Unable to access lob stream" 오류가 남 -> PostgreSQL text / H2 varchar 로 매핑되는 LONG32VARCHAR 사용
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
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
        this.experimentCondition = req.experimentCondition;
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
    public String getLabelSource() { return labelSource; }
    public void setLabel(boolean intentional, String labelSource) {
        this.confirmedIntentional = intentional;
        this.labelSource = labelSource;
    }
    public String getExperimentCondition() { return experimentCondition; }
    public String getRawSensorWindowJson() { return rawSensorWindowJson; }
}
