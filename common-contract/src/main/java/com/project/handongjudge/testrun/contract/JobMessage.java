package com.project.handongjudge.testrun.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@link Streams#JOBS} 메시지의 payload (testrun-service → runner).
 * 테스트케이스 본문은 싣지 않는다. runner가 problemHash로 캐시를 찾고 없으면 backend 내부 API에서 받는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobMessage {
    @Builder.Default
    private int v = Streams.CONTRACT_VERSION;
    private String jobId;
    private Long userId;
    private Long sectionId;
    private Long problemId;
    private String langId;
    private String code;
    private Double timeLimitSec;
    private Integer memoryLimitMb;
    private String compareArgs;
    private String problemHash;
    @Builder.Default
    private Enums.Mode mode = Enums.Mode.NORMAL;
    /** epoch ms */
    private Long enqueuedAt;
}
