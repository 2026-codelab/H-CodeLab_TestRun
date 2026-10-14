package com.project.handongjudge.testrun.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** testrun-service {@code POST /v1/jobs} 202 응답. backend는 jobId를 sessionKey로 쓴다. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateJobResponse {
    private String jobId;
    /** epoch ms */
    private long enqueuedAt;
}
