package com.project.handongjudge.testrun.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * backend → testrun-service {@code POST /v1/jobs} 요청.
 * backend가 인증·제출 검증(언어, 마감, 수강)을 끝낸 뒤 보낸다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateJobRequest {
    private Long userId;
    private Long sectionId;
    private Long problemId;
    private Enums.ContextType contextType;
    /** 과제 ID 또는 퀴즈 ID. */
    private Long contextId;
    /** 분반 관리자(교수·튜터)의 실행이면 true. 통계에서 기본 제외. */
    private boolean manager;
    /** DOMjudge 언어 ID (c, cpp, java, python3). */
    private String langId;
    private String code;
    private Double timeLimitSec;
    private Integer memoryLimitMb;
    /** strictWhitespaceGrading이면 "space_change_sensitive", 아니면 빈 문자열. */
    private String compareArgs;
    /** 테스트케이스 묶음 해시. runner 캐시 키. */
    private String problemHash;
    @Builder.Default
    private Enums.Mode mode = Enums.Mode.NORMAL;
}
