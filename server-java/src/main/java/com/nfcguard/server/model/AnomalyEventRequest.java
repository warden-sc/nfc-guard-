package com.nfcguard.server.model;

public class AnomalyEventRequest {
    public String deviceToken;
    public double riskScore;
    public String reason;
    public int repeatCount;
    public long occurredAtEpochMs;
    /** MotionBuffer.snapshot()을 JSON 배열로 직렬화한 원본 시계열. 파인튜닝의 실제 학습 재료. */
    public String rawSensorWindowJson;
}
