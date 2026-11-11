package com.nfcguard.server.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 안드로이드 AnomalyReporter / 온보딩 업로드 페이로드.
 * deviceToken은 본문이 아니라 인증 헤더에서 가져온다 (사칭 방지).
 */
public class AnomalyEventRequest {
    /** "field_detection"(실제 NFC 필드 감지) 또는 "onboarding"(사용자가 직접 태그한 정상 샘플) */
    public String source = AnomalyEvent.SOURCE_FIELD_DETECTION;
    public double riskScore;
    public String reason;
    public int repeatCount;
    public long occurredAtEpochMs;

    // GestureMatcher 중간값 (휴리스틱 vs 모델 비교/분석용)
    public Double netDisplacement;
    public Double orientationShift;
    public Double pocketScore;

    // DeviceContext (가방/주머니 판단 원재료)
    public Boolean proximityNear;
    public Float ambientLux;
    public Boolean screenInteractive;

    /** 판정 당시 사용한 온디바이스 모델 버전 (휴리스틱만 썼으면 null) */
    public Long modelVersion;

    /** MotionBuffer.snapshot() 원본 시계열: [{"t":..,"ax":..,"ay":..,"az":..,"gx":..,"gy":..,"gz":..}, ...] */
    public JsonNode rawSensorWindow;
}
