package com.project.handongjudge.testrun.service.job;

import com.project.handongjudge.testrun.service.ServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JobMaintenance {

    private final JobRepository jobs;
    private final ServiceProperties properties;

    public JobMaintenance(JobRepository jobs, ServiceProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    /**
     * 작업 최대 수명(큐 대기 상한 + 실행 제한 + 여유)이 지나도 결과가 오지 않은 작업을 LOST로 표시한다.
     * 정합성: 접수 = DONE + ERROR + LOST. 늦게 결과가 오면 저장 consumer가 덮어쓴다.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reapLost() {
        int n = jobs.markLost(properties.lostAfterSec());
        if (n > 0) {
            log.warn("marked {} job(s) LOST (no result within {}s)", n, properties.lostAfterSec());
        }
    }

    /** 매일 04:00 보존 기간이 지난 코드·컴파일 출력 삭제. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void purgeCode() {
        int n = jobs.purgeCode(properties.getCodeRetentionDays());
        log.info("purged {} code/compile-output row(s) older than {} days", n, properties.getCodeRetentionDays());
    }
}
