package com.nfcguard.server.retention;

import com.nfcguard.server.repository.AnomalyEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** 개인정보처리방침의 보유 기간을 실제로 지키는 정기 삭제 (기본 365일, 매일 04:00) */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final AnomalyEventRepository repository;
    private final Clock clock;
    private final int retentionDays;

    public RetentionJob(AnomalyEventRepository repository, Clock clock,
                        @Value("${nfcguard.retention.days:365}") int retentionDays) {
        this.repository = repository;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${nfcguard.retention.cron:0 0 4 * * *}")
    public int purgeExpired() {
        int deleted = repository.deleteReceivedBefore(clock.instant().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) log.info("retention: deleted {} events older than {} days", deleted, retentionDays);
        return deleted;
    }
}
