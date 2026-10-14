package com.project.handongjudge.testrun.contract;

/**
 * 작업별 이벤트 스트림의 이벤트 이름. 기존 SSE 계약(TestOutputSseService)과 같다.
 * <pre>
 * total    {count}
 * testcase {index, result(long-form), output, outputError, outputDiff, testcaseInput, expectedOutput,
 *           runtime(ms), memoryUsed(bytes)}   secret 정책이면 result/runtime/memoryUsed만 채움
 * complete {result(short id)}
 * ce       {result: "CE", output_compile}
 * error    {message}
 * </pre>
 * 마지막 이벤트(complete/ce/error)에는 계측 필드 t_dequeued, t_compiled, t_finished(epoch ms)가 함께 실린다.
 */
public enum EventType {
    TOTAL("total"),
    TESTCASE("testcase"),
    COMPLETE("complete"),
    CE("ce"),
    ERROR("error");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isTerminal() {
        return this == COMPLETE || this == CE || this == ERROR;
    }
}
