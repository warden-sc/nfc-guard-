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
