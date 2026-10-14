package com.project.handongjudge.testrun.service.job;

import com.project.handongjudge.testrun.contract.CreateJobRequest;
import com.project.handongjudge.testrun.contract.Enums;
import com.project.handongjudge.testrun.contract.ResultSummary;
import com.project.handongjudge.testrun.contract.Streams;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 접수: 코드 실행 전에 행을 먼저 만든다. 결과가 안 오면 reaper가 LOST로 표시한다. */
    @Transactional
    public long insertQueued(String jobId, CreateJobRequest req, int codeBytes, String codeSha256, long enqueuedAt) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO testrun_job (job_id, user_id, section_id, problem_id, context_type, context_id,
                        is_manager, mode, lang_id, code_bytes, code_sha256, problem_hash, status, contract_v, enqueued_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, jobId);
            ps.setLong(2, req.getUserId());
            ps.setObject(3, req.getSectionId());
            ps.setLong(4, req.getProblemId());
            ps.setString(5, req.getContextType() == null ? null : req.getContextType().name());
            ps.setObject(6, req.getContextId());
            ps.setBoolean(7, req.isManager());
            ps.setString(8, req.getMode() == null ? Enums.Mode.NORMAL.name() : req.getMode().name());
            ps.setString(9, req.getLangId());
            ps.setInt(10, codeBytes);
            ps.setString(11, codeSha256);
            ps.setString(12, req.getProblemHash());
            ps.setInt(13, Streams.CONTRACT_VERSION);
            ps.setTimestamp(14, new Timestamp(enqueuedAt));
            return ps;
        }, key);
        long pk = key.getKey().longValue();
        jdbc.update("INSERT INTO testrun_source (job_pk, code) VALUES (?, ?)", pk, req.getCode());
        return pk;
    }

    public void markError(String jobId, String errorCode, String message) {
        jdbc.update("UPDATE testrun_job SET status = 'ERROR', verdict = 'ERR', error_code = ?, error_message = ? WHERE job_id = ?",
                errorCode, truncate(message, 500), jobId);
    }

    public int countQueued() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM testrun_job WHERE status = 'QUEUED'", Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<Map<String, Object>> findByJobId(String jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT job_id, user_id, section_id, problem_id, lang_id, mode, status, verdict, error_code,
                       testcase_source, tc_total, tc_passed, first_fail_rank, max_runtime_ms, max_memory_kb,
                       enqueued_at, dequeued_at, compiled_at, finished_at, persisted_at
                FROM testrun_job WHERE job_id = ?
                """, jobId);
        return rows.stream().findFirst();
    }

    /**
     * 결과 저장 (멱등). 같은 결과 메시지를 두 번 처리해도 상태가 같다.
     * 작업 행이 없으면(수동 투입 등) 요약 정보로 행을 만든다.
     */
    @Transactional
    public void upsertResult(ResultSummary s) {
        jdbc.update("""
                INSERT INTO testrun_job (job_id, user_id, section_id, problem_id, mode, lang_id, code_bytes, code_sha256,
                    status, contract_v, enqueued_at, testcase_source, verdict, error_code, error_message, tc_total, tc_passed,
                    first_fail_rank, max_runtime_ms, max_memory_kb, runner_id, dequeued_at, compiled_at, finished_at, persisted_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status), testcase_source = VALUES(testcase_source), verdict = VALUES(verdict),
                    error_code = VALUES(error_code), error_message = VALUES(error_message), tc_total = VALUES(tc_total),
                    tc_passed = VALUES(tc_passed), first_fail_rank = VALUES(first_fail_rank),
                    max_runtime_ms = VALUES(max_runtime_ms), max_memory_kb = VALUES(max_memory_kb),
                    runner_id = VALUES(runner_id), dequeued_at = VALUES(dequeued_at), compiled_at = VALUES(compiled_at),
                    finished_at = VALUES(finished_at), persisted_at = NOW(3)
                """,
                s.getJobId(), s.getUserId(), s.getSectionId(), s.getProblemId(),
                s.getMode() == null ? Enums.Mode.NORMAL.name() : s.getMode().name(),
                s.getLangId() == null ? "" : s.getLangId(),
                s.getStatus().name(), s.getV(), ts(s.getEnqueuedAt() != null ? s.getEnqueuedAt() : System.currentTimeMillis()),
                s.getTestcaseSource() == null ? null : s.getTestcaseSource().name(),
                s.getVerdict() == null ? null : s.getVerdict().name(),
                s.getErrorCode(), truncate(s.getErrorMessage(), 500), s.getTcTotal(), s.getTcPassed(),
                s.getFirstFailRank(), s.getMaxRuntimeMs(), s.getMaxMemoryKb(), s.getRunnerId(),
                ts(s.getDequeuedAt()), ts(s.getCompiledAt()), ts(s.getFinishedAt()));

        Long pk = jdbc.queryForObject("SELECT id FROM testrun_job WHERE job_id = ?", Long.class, s.getJobId());

        if (s.getCompileOutput() != null && !s.getCompileOutput().isEmpty()) {
            jdbc.update("""
                    INSERT INTO testrun_compile_output (job_pk, output, truncated, original_bytes) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE output = VALUES(output), truncated = VALUES(truncated),
                        original_bytes = VALUES(original_bytes)
                    """, pk, s.getCompileOutput(), s.isCompileOutputTruncated(),
                    s.getCompileOutputBytes() == null ? 0 : s.getCompileOutputBytes());
        }

        jdbc.update("DELETE FROM testrun_case_result WHERE job_pk = ?", pk);
        if (s.getCases() != null && !s.getCases().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO testrun_case_result (job_pk, `rank`, verdict, runtime_ms, memory_kb) VALUES (?, ?, ?, ?, ?)",
                    s.getCases(), s.getCases().size(), (ps, c) -> {
                        ps.setLong(1, pk);
                        ps.setInt(2, c.getRank());
                        ps.setString(3, c.getVerdict().name());
                        ps.setObject(4, c.getRuntimeMs());
                        ps.setObject(5, c.getMemoryKb());
                    });
        }
    }

    /** 결과가 오지 않은 작업을 LOST로 표시. 늦게 결과가 오면 upsert가 DONE/ERROR로 덮어쓴다. */
    public int markLost(int olderThanSec) {
        return jdbc.update("""
                UPDATE testrun_job SET status = 'LOST', error_code = 'NO_RESULT'
                WHERE status = 'QUEUED' AND enqueued_at < NOW(3) - INTERVAL ? SECOND
                """, olderThanSec);
    }

    /** 보존 기간이 지난 코드·컴파일 출력 삭제 (요약은 남김). */
    @Transactional
    public int purgeCode(int retentionDays) {
        int sources = jdbc.update("""
                DELETE s FROM testrun_source s JOIN testrun_job j ON j.id = s.job_pk
                WHERE j.enqueued_at < NOW() - INTERVAL ? DAY
                """, retentionDays);
        int outputs = jdbc.update("""
                DELETE c FROM testrun_compile_output c JOIN testrun_job j ON j.id = c.job_pk
                WHERE j.enqueued_at < NOW() - INTERVAL ? DAY
                """, retentionDays);
        return sources + outputs;
    }

    private static Timestamp ts(Long epochMs) {
        return epochMs == null ? null : new Timestamp(epochMs);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
