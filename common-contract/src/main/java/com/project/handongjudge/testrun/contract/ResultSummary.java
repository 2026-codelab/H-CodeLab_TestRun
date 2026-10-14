package com.project.handongjudge.testrun.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@link Streams#RESULTS} 메시지의 payload (runner → testrun-service 저장 consumer).
 * 분석·통계용 요약이다. 학생 출력·diff·테스트케이스 입출력은 싣지 않는다. 코드는 작업 행에 이미 저장돼 있다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResultSummary {
    @Builder.Default
    private int v = Streams.CONTRACT_VERSION;
    private String jobId;
    private Long userId;
    private Long sectionId;
    private Long problemId;
    private String langId;
    private Enums.Mode mode;
    /** DONE 또는 ERROR. */
    private Enums.JobStatus status;
    private Verdict verdict;
    /** ERROR일 때 원인 코드 (RUNNER_LOST, JOB_TIMEOUT, TESTCASES_UNAVAILABLE, UNSUPPORTED_LANGUAGE, INTERNAL ...). */
    private String errorCode;
    private String errorMessage;
    private Enums.TestcaseSource testcaseSource;
    private Integer tcTotal;
    private Integer tcPassed;
    private Integer firstFailRank;
    private Integer maxRuntimeMs;
    private Integer maxMemoryKb;
    /** 컴파일러 출력. {@link Streams#MAX_COMPILE_OUTPUT_BYTES}에서 자른다. */
    private String compileOutput;
    private boolean compileOutputTruncated;
    private Integer compileOutputBytes;
    @Builder.Default
    private List<CaseResult> cases = new ArrayList<>();
    private String runnerId;
    /** epoch ms */
    private Long enqueuedAt;
    private Long dequeuedAt;
    private Long compiledAt;
    private Long finishedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CaseResult {
        private int rank;
        private Verdict verdict;
        private Integer runtimeMs;
        private Integer memoryKb;
    }
}
