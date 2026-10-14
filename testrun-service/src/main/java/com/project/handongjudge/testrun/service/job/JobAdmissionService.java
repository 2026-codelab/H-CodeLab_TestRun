package com.project.handongjudge.testrun.service.job;

import com.project.handongjudge.testrun.contract.CreateJobRequest;
import com.project.handongjudge.testrun.contract.CreateJobResponse;
import com.project.handongjudge.testrun.contract.Enums;
import com.project.handongjudge.testrun.contract.JobMessage;
import com.project.handongjudge.testrun.contract.Streams;
import com.project.handongjudge.testrun.service.ServiceProperties;
import com.project.handongjudge.testrun.service.api.ApiException;
import com.project.handongjudge.testrun.service.redis.JobQueue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 작업 접수. 순서: 검증 → runner 생존 → 큐 깊이 → 사용자 잠금 → DB 행 저장 → 큐 등록.
 * 503(NO_RUNNER, QUEUE_FULL, ENQUEUE_FAILED)을 받으면 backend는 기존 DOMjudge 경로로 폴백한다.
 */
@Slf4j
@Service
public class JobAdmissionService {

    private final JobRepository jobs;
    private final JobQueue queue;
    private final ServiceProperties properties;

    public JobAdmissionService(JobRepository jobs, JobQueue queue, ServiceProperties properties) {
        this.jobs = jobs;
        this.queue = queue;
        this.properties = properties;
    }

    public CreateJobResponse create(CreateJobRequest req) {
        validate(req);
        byte[] code = req.getCode().getBytes(StandardCharsets.UTF_8);
        if (code.length > Streams.MAX_CODE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CODE_TOO_LARGE",
                    "code exceeds " + Streams.MAX_CODE_BYTES + " bytes");
        }
        if (!queue.anyRunnerAlive()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_RUNNER", "no live runner");
        }
        if (jobs.countQueued() >= properties.getMaxQueueDepth()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_FULL", "too many queued jobs");
        }

        String jobId = UUID.randomUUID().toString();
        if (!queue.tryLockUser(req.getUserId(), jobId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_RUNNING", "previous test run is still running");
        }

        long enqueuedAt = System.currentTimeMillis();
        try {
            jobs.insertQueued(jobId, req, code.length, sha256(code), enqueuedAt);
        } catch (RuntimeException e) {
            queue.unlockUser(req.getUserId(), jobId);
            throw e;
        }

        JobMessage message = JobMessage.builder()
                .jobId(jobId)
                .userId(req.getUserId())
                .sectionId(req.getSectionId())
                .problemId(req.getProblemId())
                .langId(req.getLangId())
                .code(req.getCode())
                .timeLimitSec(req.getTimeLimitSec())
                .memoryLimitMb(req.getMemoryLimitMb())
                .compareArgs(req.getCompareArgs() == null ? "" : req.getCompareArgs())
                .problemHash(req.getProblemHash())
                .mode(req.getMode() == null ? Enums.Mode.NORMAL : req.getMode())
                .enqueuedAt(enqueuedAt)
                .build();
        try {
            queue.enqueue(message);
        } catch (Exception e) {
            log.error("enqueue failed for job {}", jobId, e);
            jobs.markError(jobId, "ENQUEUE_FAILED", e.getMessage());
            queue.unlockUser(req.getUserId(), jobId);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENQUEUE_FAILED", "could not enqueue job");
        }
        return new CreateJobResponse(jobId, enqueuedAt);
    }

    private static void validate(CreateJobRequest req) {
        if (req.getUserId() == null || req.getProblemId() == null || req.getLangId() == null
                || req.getCode() == null || req.getProblemHash() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "userId, problemId, langId, code, problemHash are required");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
