package com.nfcguard.server.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Python 파인튜닝 파이프라인 산출물(ml-python/model_export.py)을 안드로이드 앱이 내려받는 엔드포인트.
 * 앱은 metadata(서명 검증) -> latest(sha256 검증) 순서로 받는다.
 * 실배포 시에는 파일시스템 직접 서빙 대신 S3/GCS presigned URL 방식 권장.
 */
@RestController
@RequestMapping("/api/v1/model")
public class ModelDistributionController {

    private static final String LATEST_FILENAME = "gesture_classifier.tflite";
    private static final String METADATA_FILENAME = "gesture_classifier.json";
    private static final String SIGNATURE_FILENAME = METADATA_FILENAME + ".sig";

    // Python 스크립트(ml-python/finetune.py)가 이 경로에 결과물을 씀.
    // (이전 System.getProperty 방식은 application.properties 값을 읽지 못했음)
    private final String modelDir;

    public ModelDistributionController(@Value("${nfcguard.model.dir:./models}") String modelDir) {
        this.modelDir = modelDir;
    }

    @GetMapping("/latest")
    public ResponseEntity<Resource> downloadLatest() {
        File file = new File(modelDir, LATEST_FILENAME);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + LATEST_FILENAME)
                .header("X-Model-LastModified", String.valueOf(file.lastModified()))
                .body(resource);
    }

    /**
     * 메타데이터 원문(버전, sha256, 입력 스펙, 임계값)을 바이트 그대로 내려준다 - 서명이 원문 바이트 기준이므로
     * 서버에서 파싱/재직렬화하면 안 됨. 서명은 X-Model-Signature 헤더 (서명 파일이 없으면 생략).
     */
    @GetMapping("/metadata")
    public ResponseEntity<byte[]> metadata() throws IOException {
        File meta = new File(modelDir, METADATA_FILENAME);
        if (!meta.exists()) return ResponseEntity.notFound().build();
        var response = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
        File sig = new File(modelDir, SIGNATURE_FILENAME);
        if (sig.exists()) {
            response.header("X-Model-Signature", Files.readString(sig.toPath(), StandardCharsets.US_ASCII).trim());
        }
        return response.body(Files.readAllBytes(meta.toPath()));
    }

    @GetMapping("/version")
    public ResponseEntity<Long> version() {
        File file = new File(modelDir, LATEST_FILENAME);
        if (!file.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(file.lastModified());
    }
}
