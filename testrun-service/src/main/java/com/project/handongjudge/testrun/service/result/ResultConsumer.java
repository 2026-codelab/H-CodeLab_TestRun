package com.project.handongjudge.testrun.service.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.handongjudge.testrun.contract.ResultSummary;
import com.project.handongjudge.testrun.contract.Streams;
import com.project.handongjudge.testrun.service.job.JobRepository;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 결과 저장 consumer ({@link Streams#RESULTS}, group {@link Streams#RESULTS_GROUP}).
 * DB에 저장한 뒤에만 XACK 한다. 재시작하면 확인하지 못한 자기 메시지(PEL)부터 다시 처리하므로 저장 누락이 없다.
 * 저장은 job_id 기준 멱등이라 같은 메시지를 다시 처리해도 결과가 같다.
 */
@Slf4j
@Component
public class ResultConsumer implements SmartLifecycle {

    private static final Duration CLAIM_IDLE = Duration.ofSeconds(60);

    private final RedisClient client;
    private final JobRepository jobs;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public ResultConsumer(RedisClient client, JobRepository jobs, ObjectMapper mapper) {
        this.client = client;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Override
    public void start() {
        running.set(true);
        thread = new Thread(this::run, "result-consumer");
        thread.start();
    }

    private void run() {
        Consumer<String> consumer = Consumer.from(Streams.RESULTS_GROUP, consumerName());
        boolean replayPending = true;
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> cmd = connection.sync();
            ensureGroup(cmd);
            while (running.get()) {
                try {
                    List<StreamMessage<String, String>> messages;
                    if (replayPending) {
                        // 죽은 다른 인스턴스가 붙잡고 있던 메시지를 가져온 뒤, 내 PEL을 처음부터 다시 처리한다.
                        cmd.xautoclaim(Streams.RESULTS, XAutoClaimArgs.Builder.xautoclaim(consumer, CLAIM_IDLE, "0-0").count(100));
                        messages = cmd.xreadgroup(consumer, XReadArgs.Builder.count(100),
                                XReadArgs.StreamOffset.from(Streams.RESULTS, "0"));
                        if (messages.isEmpty()) {
                            replayPending = false;
                            continue;
                        }
                    } else {
                        messages = cmd.xreadgroup(consumer, XReadArgs.Builder.block(5000).count(50),
                                XReadArgs.StreamOffset.lastConsumed(Streams.RESULTS));
                    }
                    for (StreamMessage<String, String> message : messages) {
                        persist(cmd, message);
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("result consumer error; will replay pending", e);
                        replayPending = true;
                        sleep(2000);
                    }
                }
            }
        }
    }

    private void persist(RedisCommands<String, String> cmd, StreamMessage<String, String> message) {
        if (message.getBody() == null || message.getBody().isEmpty()) {
            cmd.xack(Streams.RESULTS, Streams.RESULTS_GROUP, message.getId());
            return;
        }
        ResultSummary summary;
        try {
            summary = mapper.readValue(message.getBody().get(Streams.FIELD_PAYLOAD), ResultSummary.class);
        } catch (Exception e) {
            // 읽을 수 없는 메시지는 재시도해도 같으므로 확인하고 넘어간다.
            log.error("malformed result message {} — acknowledging and skipping", message.getId(), e);
            cmd.xack(Streams.RESULTS, Streams.RESULTS_GROUP, message.getId());
            return;
        }
        jobs.upsertResult(summary);   // 실패하면 예외 → XACK 하지 않음 → PEL에서 재처리
        cmd.xack(Streams.RESULTS, Streams.RESULTS_GROUP, message.getId());
        log.debug("persisted job {} verdict={} status={}", summary.getJobId(), summary.getVerdict(), summary.getStatus());
    }

    private void ensureGroup(RedisCommands<String, String> cmd) {
        try {
            cmd.xgroupCreate(XReadArgs.StreamOffset.from(Streams.RESULTS, "0"), Streams.RESULTS_GROUP,
                    XGroupCreateArgs.Builder.mkstream());
        } catch (RedisCommandExecutionException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private static String consumerName() {
        try {
            return "service-" + InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "service-local";
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
