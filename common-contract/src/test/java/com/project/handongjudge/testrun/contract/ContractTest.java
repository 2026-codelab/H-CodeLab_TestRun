package com.project.handongjudge.testrun.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** 계약 예시 JSON(src/test/resources/contract)을 기준으로 직렬화 형식을 고정한다. */
class ContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private InputStream example(String name) {
        return getClass().getResourceAsStream("/contract/" + name);
    }

    @Test
    void jobMessageExampleIsReadableAndIgnoresUnknownFields() throws Exception {
        JobMessage job = mapper.readValue(example("job-message.v1.json"), JobMessage.class);

        assertEquals(1, job.getV());
        assertEquals("c", job.getLangId());
        assertEquals(256, job.getMemoryLimitMb());
        assertEquals(Enums.Mode.NORMAL, job.getMode());
    }

    @Test
    void resultSummaryRoundTripsWithoutOutputBodies() throws Exception {
        ResultSummary summary = mapper.readValue(example("result-summary.v1.json"), ResultSummary.class);

        assertEquals(Verdict.WA, summary.getVerdict());
        assertEquals(2, summary.getCases().size());
        assertEquals(2, summary.getFirstFailRank());

        JsonNode written = mapper.readTree(mapper.writeValueAsString(summary));
        // 저장 범위: 학생 출력·diff·테스트케이스 입출력은 요약에 없다.
        for (String forbidden : new String[] {"output", "outputDiff", "testcaseInput", "expectedOutput"}) {
            assertFalse(written.has(forbidden), forbidden);
            assertFalse(written.get("cases").get(0).has(forbidden), forbidden);
        }
    }

    @Test
    void verdictMatchesDomjudgeExitCodes() {
        assertEquals(Verdict.AC, Verdict.fromExitCode(0));
        assertEquals(Verdict.CE, Verdict.fromExitCode(101));
        assertEquals(Verdict.TLE, Verdict.fromExitCode(102));
        assertEquals(Verdict.WA, Verdict.fromExitCode(105));
        assertEquals(Verdict.ERR, Verdict.fromExitCode(120));
        assertEquals(Verdict.ERR, Verdict.fromExitCode(42));
        assertEquals("wrong-answer", Verdict.WA.longForm());
        assertEquals("correct", Verdict.AC.longForm());
    }
}
