package com.project.handongjudge.testrun.contract;

/**
 * 판정 코드. 설계 문서 부록 B와 같다.
 * <ul>
 *   <li>{@link #name()}: short id. SSE {@code complete.result}와 DB {@code verdict} 컬럼에 쓴다.</li>
 *   <li>{@link #longForm()}: DOMjudge 결과 이름. SSE {@code testcase.result}에 쓴다 (프론트 RESULT_MAPPING_OUTPUT).</li>
 *   <li>{@link #exitCode()}: compile.sh / testcase_run.sh 종료 코드 (judgehost-static.php EXITCODES).</li>
 * </ul>
 */
public enum Verdict {
    AC("correct", 0),
    CE("compiler-error", 101),
    TLE("timelimit", 102),
    RTE("run-error", 103),
    NO("no-output", 104),
    WA("wrong-answer", 105),
    MLE("memory-limit", 107),
    OLE("output-limit", 108),
    /** 비교 프로그램 오류(120), 내부 오류, 시간 초과 등 채점 자체가 실패한 경우. */
    ERR("internal-error", -1);

    private final String longForm;
    private final int exitCode;

    Verdict(String longForm, int exitCode) {
        this.longForm = longForm;
        this.exitCode = exitCode;
    }

    public String longForm() {
        return longForm;
    }

    public int exitCode() {
        return exitCode;
    }

    /** testcase_run.sh 종료 코드 → 판정. 알 수 없는 코드와 compare-error(120)는 ERR. */
    public static Verdict fromExitCode(int code) {
        for (Verdict v : values()) {
            if (v != ERR && v.exitCode == code) {
                return v;
            }
        }
        return ERR;
    }
}
