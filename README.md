<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.2</version>
        <relativePath/>
    </parent>

    <groupId>com.nfcguard</groupId>
    <artifactId>server</artifactId>
    <version>0.1-scaffold</version>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <!-- 스캐폴딩 기본값: H2 파일 DB. 실배포시 PostgreSQL 등으로 교체 -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
package com.nfcguard.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NfcGuardServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NfcGuardServerApplication.class, args);
    }
}
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceToken;       // 사용자 식별 대신 익명 디바이스 토큰
    private double riskScore;         // GestureMatcher.Result.riskScore
    private String reason;            // GestureMatcher.Result.reason
    private int repeatCountAtReport;  // 신고 시점의 로컬 반복횟수
    private Instant occurredAt;

    // 사용자가 나중에 셀프리포트로 확정한 라벨 (파인튜닝 데이터로 사용)
    private Boolean confirmedIntentional; // null = 아직 미확정

    // 파인튜닝의 실제 원재료. MotionBuffer.snapshot()을 JSON 배열로 직렬화한 것.
    // risk_score 같은 요약값만으로는 모델을 재학습시킬 수 없음 - 원본 시계열이 필요.
    @Lob
    private String rawSensorWindowJson;

    protected AnomalyEvent() {}

    public AnomalyEvent(String deviceToken, double riskScore, String reason,
                         int repeatCountAtReport, Instant occurredAt, String rawSensorWindowJson) {
        this.deviceToken = deviceToken;
        this.riskScore = riskScore;
        this.reason = reason;
        this.repeatCountAtReport = repeatCountAtReport;
        this.occurredAt = occurredAt;
        this.rawSensorWindowJson = rawSensorWindowJson;
    }

    // getters/setters
    public Long getId() { return id; }
    public String getDeviceToken() { return deviceToken; }
    public double getRiskScore() { return riskScore; }
    public String getReason() { return reason; }
    public int getRepeatCountAtReport() { return repeatCountAtReport; }
    public Instant getOccurredAt() { return occurredAt; }
    public Boolean getConfirmedIntentional() { return confirmedIntentional; }
    public void setConfirmedIntentional(Boolean confirmedIntentional) {
        this.confirmedIntentional = confirmedIntentional;
    }
    public String getRawSensorWindowJson() { return rawSensorWindowJson; }
}
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
package com.nfcguard.server.repository;

import com.nfcguard.server.model.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    List<AnomalyEvent> findByDeviceTokenAndOccurredAtAfter(String deviceToken, Instant after);

    // 파인튜닝 데이터셋 추출용: 라벨이 확정된 것만
    List<AnomalyEvent> findByConfirmedIntentionalIsNotNull();
}
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
package com.nfcguard.server.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

/**
 * Python 파인튜닝 파이프라인 산출물(TFLite 파일)을 안드로이드 앱이 내려받는 엔드포인트.
 * 실배포 시에는 파일시스템 직접 서빙 대신 S3/GCS presigned URL 방식 권장.
 */
@RestController
@RequestMapping("/api/v1/model")
public class ModelDistributionController {

    // Python 스크립트(ml-python/finetune.py)가 이 경로에 결과물을 씀
    private static final String MODEL_DIR = System.getProperty("nfcguard.model.dir", "./models");
    private static final String LATEST_FILENAME = "gesture_classifier.tflite";

    @GetMapping("/latest")
    public ResponseEntity<Resource> downloadLatest() {
        File file = new File(MODEL_DIR, LATEST_FILENAME);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + LATEST_FILENAME)
                .header("X-Model-LastModified", String.valueOf(file.lastModified()))
                .body(resource);
    }

    @GetMapping("/version")
    public ResponseEntity<Long> version() {
        File file = new File(MODEL_DIR, LATEST_FILENAME);
        if (!file.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(file.lastModified());
    }
}
spring.datasource.url=jdbc:h2:file:./data/nfcguard
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
nfcguard.model.dir=./models
server.port=8080
target/
*.class
*.jar
*.war
.idea/
*.iml
.vscode/
.DS_Store
application-local.properties
*.log
