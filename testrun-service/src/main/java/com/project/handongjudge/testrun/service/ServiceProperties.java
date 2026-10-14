package com.project.handongjudge.testrun.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "testrun")
public class ServiceProperties {

    /** backend가 X-Service-Token 헤더로 보내는 값. 비어 있으면 모든 /v1 요청을 거부한다. */
    private String serviceToken = "";
    /** 아직 끝나지 않은(QUEUED) 작업이 이 수 이상이면 503으로 거절한다 (backend는 DOMjudge 경로로 폴백). */
    private int maxQueueDepth = 200;
    private int jobTimeoutSec = 30;
    /** runner의 큐 대기 상한과 같은 값. 이보다 오래 기다린 작업은 runner가 실행하지 않고 QUEUE_TIMEOUT으로 끝낸다. */
    private int maxQueueWaitSec = 60;
    /** QUEUED가 maxQueueWait + jobTimeout + grace를 넘기면 LOST로 표시한다. */
    private int reaperGraceSec = 30;

    /** 작업 하나의 최대 수명: 큐 대기 상한 + 실행 제한 + 여유. */
    public int lostAfterSec() {
        return maxQueueWaitSec + jobTimeoutSec + reaperGraceSec;
    }
    /** 코드·컴파일 출력 보존 기간. 요약은 남긴다. */
    private int codeRetentionDays = 365;
    private Redis redis = new Redis();

    @Getter
    @Setter
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
    }
}
