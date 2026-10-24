package com.nfcguard.server.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 익명 기기 등록 정보. 사용자 식별정보는 저장하지 않는다.
 * apiKey 원문은 등록 응답으로 한 번만 내려주고 서버에는 SHA-256 해시만 보관.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String deviceToken;

    @Column(nullable = false)
    private String apiKeyHash;

    private Instant registeredAt;

    protected Device() {}

    public Device(String deviceToken, String apiKeyHash, Instant registeredAt) {
        this.deviceToken = deviceToken;
        this.apiKeyHash = apiKeyHash;
        this.registeredAt = registeredAt;
    }

    public Long getId() { return id; }
    public String getDeviceToken() { return deviceToken; }
    public String getApiKeyHash() { return apiKeyHash; }
    public Instant getRegisteredAt() { return registeredAt; }
}
