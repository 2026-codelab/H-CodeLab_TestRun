package com.project.handongjudge.testrun.runner.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.handongjudge.testrun.contract.EventType;
import com.project.handongjudge.testrun.contract.JobMessage;
import com.project.handongjudge.testrun.contract.Streams;
import com.project.handongjudge.testrun.runner.RunnerProperties;
import com.project.handongjudge.testrun.runner.judge.JobOutcome;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis Streams 접근. 블로킹 XREADGROUP은 연결을 점유하므로 슬롯마다 전용 연결을 쓴다.
 */
@Slf4j
@Component
public class RedisStreams {

    private final RedisClient client;
    private final ObjectMapper mapper;
    private final StatefulRedisConnection<String, String> shared;

    public RedisStreams(RunnerProperties properties, ObjectMapper mapper) {
        RunnerProperties.Redis redis = properties.getRedis();
        RedisURI.Builder uri = RedisURI.builder().withHost(redis.getHost()).withPort(redis.getPort())
                .withTimeout(Duration.ofSeconds(10));
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            uri.withPassword(redis.getPassword().toCharArray());
        }
        this.client = RedisClient.create(uri.build());
        this.mapper = mapper;
        this.shared = client.connect();
    }

    /** 슬롯 전용 연결. */
    public StatefulRedisConnection<String, String> connect() {
        return client.connect();
    }

    public RedisCommands<String, String> shared() {
        return shared.sync();
    }

    /** 작업 큐 consumer group을 만든다 (이미 있으면 무시). */
    public void ensureJobsGroup() {
        try {
            shared().xgroupCreate(XReadArgs.StreamOffset.from(Streams.JOBS, "0"), Streams.JOBS_GROUP,
                    XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    public JobMessage parseJob(Map<String, String> body) throws JsonProcessingException {
        return mapper.readValue(body.get(Streams.FIELD_PAYLOAD), JobMessage.class);
    }

    /** 진행 중 이벤트 (total, testcase). */
    public void emit(RedisCommands<String, String> redis, String jobId, EventType type, Map<String, Object> data) {
        redis.xadd(Streams.events(jobId), eventFields(type, data));
    }

    /**
     * 마무리: 마지막 이벤트 + 이벤트 TTL + 저장용 요약 + 사용자 잠금 해제 + 작업 확인을 한 트랜잭션으로.
     * 학생 화면에 보이는 결과와 저장 메시지가 함께 생기거나 함께 안 생긴다.
     */
    public void finish(RedisCommands<String, String> redis, String messageId, JobMessage job, JobOutcome outcome)
            throws JsonProcessingException {
        Map<String, String> result = Map.of(
                Streams.FIELD_VERSION, String.valueOf(Streams.CONTRACT_VERSION),
                Streams.FIELD_PAYLOAD, mapper.writeValueAsString(outcome.summary()));
        String events = Streams.events(job.getJobId());
        redis.multi();
        redis.xadd(events, eventFields(outcome.terminalEvent(), outcome.terminalData()));
        redis.expire(events, Streams.EVENTS_TTL_SECONDS);
        redis.xadd(Streams.RESULTS, XAddArgs.Builder.maxlen(Streams.RESULTS_MAXLEN).approximateTrimming(), result);
        if (job.getUserId() != null) {
            redis.del(Streams.active(job.getUserId()));
        }
        redis.xack(Streams.JOBS, Streams.JOBS_GROUP, messageId);
        TransactionResult tx = redis.exec();
        if (tx.wasDiscarded()) {
            throw new IllegalStateException("finish transaction discarded for job " + job.getJobId());
        }
    }

    private Map<String, String> eventFields(EventType type, Map<String, Object> data) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(Streams.FIELD_VERSION, String.valueOf(Streams.CONTRACT_VERSION));
            fields.put(Streams.FIELD_EVENT, type.value());
            fields.put(Streams.FIELD_DATA, mapper.writeValueAsString(data));
            return fields;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    public void close() {
        shared.close();
        client.shutdown();
    }
}
