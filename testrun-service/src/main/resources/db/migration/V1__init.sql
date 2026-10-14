-- 테스트하기 결과 저장 (분석·통계용).
-- 저장 범위: 학생 코드, 판정, 컴파일 출력, 테스트케이스별 판정·실행시간·메모리, 시각 기록.
-- 저장하지 않음: 학생 출력 본문, diff, 테스트케이스 입력·기대 출력.

CREATE TABLE testrun_job (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    job_id           CHAR(36)     NOT NULL,
    user_id          BIGINT       NOT NULL,
    section_id       BIGINT       NULL,
    problem_id       BIGINT       NOT NULL,
    context_type     VARCHAR(16)  NULL,
    context_id       BIGINT       NULL,
    is_manager       BOOLEAN      NOT NULL DEFAULT FALSE,
    mode             VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    lang_id          VARCHAR(32)  NOT NULL,
    code_bytes       INT          NOT NULL,
    code_sha256      CHAR(64)     NOT NULL,
    problem_hash     VARCHAR(128) NULL,
    testcase_source  VARCHAR(16)  NULL,
    status           VARCHAR(16)  NOT NULL,
    verdict          VARCHAR(8)   NULL,
    error_code       VARCHAR(64)  NULL,
    error_message    VARCHAR(500) NULL,
    tc_total         INT          NULL,
    tc_passed        INT          NULL,
    first_fail_rank  INT          NULL,
    max_runtime_ms   INT          NULL,
    max_memory_kb    INT          NULL,
    runner_id        VARCHAR(128) NULL,
    contract_v       INT          NOT NULL DEFAULT 1,
    enqueued_at      DATETIME(3)  NOT NULL,
    dequeued_at      DATETIME(3)  NULL,
    compiled_at      DATETIME(3)  NULL,
    finished_at      DATETIME(3)  NULL,
    persisted_at     DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_testrun_job_job_id (job_id),
    KEY idx_testrun_job_section_time (section_id, enqueued_at),
    KEY idx_testrun_job_problem_time (problem_id, enqueued_at),
    KEY idx_testrun_job_user_problem_time (user_id, problem_id, enqueued_at),
    KEY idx_testrun_job_time_verdict (enqueued_at, verdict),
    KEY idx_testrun_job_status_time (status, enqueued_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE testrun_source (
    job_pk BIGINT     NOT NULL,
    code   MEDIUMTEXT NOT NULL,
    PRIMARY KEY (job_pk),
    CONSTRAINT fk_testrun_source_job FOREIGN KEY (job_pk) REFERENCES testrun_job (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE testrun_compile_output (
    job_pk         BIGINT     NOT NULL,
    output         MEDIUMTEXT NOT NULL,
    truncated      BOOLEAN    NOT NULL DEFAULT FALSE,
    original_bytes INT        NOT NULL DEFAULT 0,
    PRIMARY KEY (job_pk),
    CONSTRAINT fk_testrun_compile_output_job FOREIGN KEY (job_pk) REFERENCES testrun_job (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE testrun_case_result (
    job_pk     BIGINT     NOT NULL,
    `rank`     INT        NOT NULL,
    verdict    VARCHAR(8) NOT NULL,
    runtime_ms INT        NULL,
    memory_kb  INT        NULL,
    PRIMARY KEY (job_pk, `rank`),
    CONSTRAINT fk_testrun_case_result_job FOREIGN KEY (job_pk) REFERENCES testrun_job (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
