package com.nfcguard.server.repository;

import com.nfcguard.server.model.AnomalyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface AnomalyEventRepository extends JpaRepository<AnomalyEvent, Long> {
    List<AnomalyEvent> findByDeviceTokenAndOccurredAtAfter(String deviceToken, Instant after);

    // 파인튜닝 데이터셋 추출용: 라벨이 확정된 것만, afterId 이후 증분으로
    List<AnomalyEvent> findByConfirmedIntentionalIsNotNullAndIdGreaterThanOrderByIdAsc(Long afterId);

    /** 사용자 데이터 삭제 요청 */
    @Transactional
    @Modifying
    @Query("delete from AnomalyEvent e where e.deviceToken = :deviceToken")
    int deleteAllByDeviceToken(@Param("deviceToken") String deviceToken);

    /** 보관 기간이 지난 이벤트 일괄 삭제 */
    @Transactional
    @Modifying
    @Query("delete from AnomalyEvent e where e.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") Instant cutoff);
}
