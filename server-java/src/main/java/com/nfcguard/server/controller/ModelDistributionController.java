package com.nfcguard.server.controller;

import org.springframework.beans.factory.annotation.Value;
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

    private static final String LATEST_FILENAME = "gesture_classifier.tflite";

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

    @GetMapping("/version")
    public ResponseEntity<Long> version() {
        File file = new File(modelDir, LATEST_FILENAME);
        if (!file.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(file.lastModified());
    }
}
