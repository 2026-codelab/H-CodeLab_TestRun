package com.project.handongjudge.testrun.service.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.handongjudge.testrun.contract.JobMessage;
import com.project.handongjudge.testrun.contract.Streams;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 작업 큐 쪽 Redis 연산. MULTI는 연결 단위이므로 이 연결은 한 번에 한 스레드만 쓴다 (synchronized).
 */
@Component
public class JobQueue {

    private final StatefulRedisConnection<String, String> connection;
    private final ObjectMapper mapper;

    public JobQueue(RedisClient client, ObjectMapper mapper) {
        this.connection = client.connect();
        this.mapper = mapper;
        ensureGroup(Streams.JOBS, Streams.JOBS_GROUP);
    }

    public synchronized boolean anyRunnerAlive() {
        RedisCommands<String, String> cmd = connection.sync();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.matches(Streams.RUNNER_KEY_PATTERN).limit(100);
        do {
            KeyScanCursor<String> page = cmd.scan(cursor, args);
            if (!page.getKeys().isEmpty()) {
                return true;
            }
            cursor = page;
        } while (!cursor.isFinished());
        return false;
    }

    /** 사용자당 동시 1건 잠금. 이미 진행 중이면 false. */
    public synchronized boolean tryLockUser(long userId, String jobId) {
        String ok = connection.sync().set(Streams.active(userId), jobId,
                SetArgs.Builder.nx().ex(Streams.ACTIVE_TTL_SECONDS));
        return "OK".equals(ok);
    }

    public synchronized void unlockUser(long userId, String jobId) {
        RedisCommands<String, String> cmd = connection.sync();
        if (jobId.equals(cmd.get(Streams.active(userId)))) {
            cmd.del(Streams.active(userId));
        }
    }

    /** 큐 등록 + 소유자 메타를 한 트랜잭션으로. */
    public synchronized void enqueue(JobMessage job) throws JsonProcessingException {
        String payload = mapper.writeValueAsString(job);
        RedisCommands<String, String> cmd = connection.sync();
        cmd.multi();
        cmd.xadd(Streams.JOBS, XAddArgs.Builder.maxlen(Streams.JOBS_MAXLEN).approximateTrimming(),
                Map.of(Streams.FIELD_VERSION, String.valueOf(Streams.CONTRACT_VERSION), Streams.FIELD_PAYLOAD, payload));
        String meta = Streams.jobMeta(job.getJobId());
        cmd.hset(meta, Map.of(
                "userId", String.valueOf(job.getUserId()),
                "sectionId", String.valueOf(job.getSectionId()),
                "problemId", String.valueOf(job.getProblemId())));
        cmd.expire(meta, Streams.JOB_META_TTL_SECONDS);
        TransactionResult tx = cmd.exec();
        if (tx.wasDiscarded()) {
            throw new IllegalStateException("enqueue transaction discarded");
        }
    }

    private void ensureGroup(String stream, String group) {
        try {
            connection.sync().xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    @PreDestroy
    public void close() {
        connection.close();
    }
}
