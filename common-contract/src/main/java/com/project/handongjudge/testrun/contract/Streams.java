package com.project.handongjudge.testrun.contract;

/**
 * Redis 키·스트림 이름과 메시지 필드 이름.
 * backend, testrun-service, testrun-runner가 같은 이름을 쓰도록 여기서만 정의한다.
 */
public final class Streams {

    /** 계약 버전. 메시지마다 {@link #FIELD_VERSION}으로 실린다. v1 안에서는 필드 추가만 허용한다. */
    public static final int CONTRACT_VERSION = 1;

    /** 작업 큐 (testrun-service → runner). */
    public static final String JOBS = "testrun:jobs";
    public static final String JOBS_GROUP = "testrun-runners";
    public static final long JOBS_MAXLEN = 10_000;

    /** 결과 요약 (runner → testrun-service 저장 consumer). 작업 1건당 메시지 1개. */
    public static final String RESULTS = "testrun:results";
    public static final String RESULTS_GROUP = "testrun-persist";
    public static final long RESULTS_MAXLEN = 200_000;

    /** 메시지 필드 이름. */
    public static final String FIELD_VERSION = "v";
    public static final String FIELD_PAYLOAD = "payload";
    /** 이벤트 스트림의 SSE 이벤트 이름 ({@link EventType#value()}). */
    public static final String FIELD_EVENT = "event";
    /** 이벤트 스트림의 SSE data(JSON 문자열). backend는 변환 없이 그대로 중계한다. */
    public static final String FIELD_DATA = "data";

    public static final long EVENTS_TTL_SECONDS = 600;
    public static final long JOB_META_TTL_SECONDS = 600;
    public static final long ACTIVE_TTL_SECONDS = 60;
    public static final long RUNNER_TTL_SECONDS = 10;

    public static final int MAX_CODE_BYTES = 64 * 1024;
    public static final int MAX_COMPILE_OUTPUT_BYTES = 64 * 1024;

    public static final String RUNNER_KEY_PATTERN = "testrun:runner:*";

    private Streams() {
    }

    /** 작업별 이벤트 스트림 (runner → backend SSE 중계). */
    public static String events(String jobId) {
        return "testrun:events:" + jobId;
    }

    /** 작업 소유자 메타 해시 (userId, sectionId, problemId). backend가 스트림 소유권 확인에 사용. */
    public static String jobMeta(String jobId) {
        return "testrun:job:" + jobId;
    }

    /** 사용자당 동시 1건 잠금. 값은 진행 중인 jobId. */
    public static String active(long userId) {
        return "testrun:active:" + userId;
    }

    /** runner 생존 신호. */
    public static String runner(String runnerId) {
        return "testrun:runner:" + runnerId;
    }
}
