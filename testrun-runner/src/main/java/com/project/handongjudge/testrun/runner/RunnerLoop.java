package com.project.handongjudge.testrun.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.handongjudge.testrun.contract.JobMessage;
import com.project.handongjudge.testrun.contract.Streams;
import com.project.handongjudge.testrun.runner.judge.JobExecutor;
import com.project.handongjudge.testrun.runner.judge.JobOutcome;
import com.project.handongjudge.testrun.runner.judge.JudgeEnv;
import com.project.handongjudge.testrun.runner.judge.RecipeService;
import com.project.handongjudge.testrun.runner.judge.Slot;
import com.project.handongjudge.testrun.runner.redis.RedisStreams;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 워커 본체. 기동 시 레시피 준비 → 슬롯 chroot 마운트 → 생존 신호 → 슬롯마다 XREADGROUP 루프.
 */
@Slf4j
@Component
public class RunnerLoop implements SmartLifecycle {

    private static final Path READY_FILE = Path.of("/tmp/runner-ready");
    private static final Path HEARTBEAT_FILE = Path.of("/tmp/runner-heartbeat");

    private final RunnerProperties properties;
    private final RecipeService recipes;
    private final JobExecutor executor;
    private final RedisStreams redis;
    private final ObjectMapper mapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final List<Slot> slots = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private Thread starter;
    private StatefulRedisConnection<String, String> reclaimConnection;

    public RunnerLoop(RunnerProperties properties, RecipeService recipes, JobExecutor executor,
                      RedisStreams redis, ObjectMapper mapper) {
        this.properties = properties;
        this.recipes = recipes;
        this.executor = executor;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void start() {
        running.set(true);
        starter = new Thread(this::bootstrap, "runner-bootstrap");
        starter.start();
    }

    private void bootstrap() {
        // domserver가 아직 안 떴을 수 있으므로 준비될 때까지 재시도한다.
        long backoffMs = 2000;
        while (running.get()) {
            try {
                recipes.load();
                break;
            } catch (Exception e) {
                log.warn("recipe load failed, retrying in {}ms: {}", backoffMs, e.getMessage());
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 30_000);
            }
        }
        if (!running.get()) {
            return;
        }
        try {
            redis.ensureJobsGroup();
            reclaimConnection = redis.connect();
            Path libJudge = properties.getJudgehostRoot().resolve("lib/judge");
            for (int i = 0; i < properties.getSlots(); i++) {
                Slot slot = new Slot(i, properties.cpuForSlot(i), properties.runuserForSlot(i),
                        properties.getWorkRoot(), libJudge);
                slot.start(slotEnv(slot));
                slots.add(slot);
            }
            Files.writeString(READY_FILE, String.valueOf(System.currentTimeMillis()));
            ready.set(true);
            heartbeat();
            for (Slot slot : slots) {
                Thread t = new Thread(() -> loop(slot), "runner-slot-" + slot.getIndex());
                threads.add(t);
                t.start();
            }
            log.info("runner {} ready with {} slots", properties.getId(), slots.size());
        } catch (Exception e) {
            log.error("runner bootstrap failed", e);
            System.exit(1);
        }
    }

    private void loop(Slot slot) {
        Consumer<String> consumer = Consumer.from(Streams.JOBS_GROUP, properties.getId() + "-" + slot.getIndex());
        try (StatefulRedisConnection<String, String> connection = redis.connect()) {
            RedisCommands<String, String> cmd = connection.sync();
            while (running.get()) {
                try {
                    List<StreamMessage<String, String>> messages = cmd.xreadgroup(consumer,
                            XReadArgs.Builder.block(5000).count(1), XReadArgs.StreamOffset.lastConsumed(Streams.JOBS));
                    for (StreamMessage<String, String> message : messages) {
                        handle(cmd, slot, message);
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("slot {} loop error", slot.getIndex(), e);
                        sleep(1000);
                    }
                }
            }
        }
    }

    private void handle(RedisCommands<String, String> cmd, Slot slot, StreamMessage<String, String> message)
            throws Exception {
        long dequeuedAt = System.currentTimeMillis();
        JobMessage job;
        try {
            job = redis.parseJob(message.getBody());
        } catch (Exception e) {
            log.error("malformed job message {} — acknowledging and skipping", message.getId(), e);
            cmd.xack(Streams.JOBS, Streams.JOBS_GROUP, message.getId());
            return;
        }
        boolean expired = job.getEnqueuedAt() != null
                && dequeuedAt - job.getEnqueuedAt() > properties.getMaxQueueWaitSec() * 1000L;
        JobOutcome outcome = expired
                ? executor.expired(job, dequeuedAt)
                : executor.execute(job, slot, recipes.current(),
                        (type, data) -> redis.emit(cmd, job.getJobId(), type, data), dequeuedAt);
        redis.finish(cmd, message.getId(), job, outcome);
        var s = outcome.summary();
        log.info("job {} slot={} lang={} verdict={} status={} queue={}ms total={}ms",
                job.getJobId(), slot.getIndex(), job.getLangId(), s.getVerdict(), s.getStatus(),
                job.getEnqueuedAt() == null ? -1 : dequeuedAt - job.getEnqueuedAt(), s.getFinishedAt() - dequeuedAt);
    }

    /** 생존 신호. 이 키가 하나도 없으면 testrun-service가 작업을 받지 않는다 (backend는 DOMjudge 경로로 폴백). */
    @Scheduled(fixedRate = 5000)
    public void heartbeat() {
        if (!ready.get()) {
            return;
        }
        try {
            String value = mapper.writeValueAsString(Map.of("slots", slots.size(), "at", System.currentTimeMillis()));
            redis.shared().setex(Streams.runner(properties.getId()), Streams.RUNNER_TTL_SECONDS, value);
            Files.writeString(HEARTBEAT_FILE, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("heartbeat failed: {}", e.getMessage());
        }
    }

    /** 다른 runner가 처리하다 죽은 작업을 회수해 error로 끝낸다. */
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void reclaim() {
        if (!ready.get() || reclaimConnection == null) {
            return;
        }
        try {
            RedisCommands<String, String> cmd = reclaimConnection.sync();
            ClaimedMessages<String, String> claimed = cmd.xautoclaim(Streams.JOBS,
                    XAutoClaimArgs.Builder.xautoclaim(Consumer.from(Streams.JOBS_GROUP, properties.getId() + "-reclaim"),
                            Duration.ofMillis(properties.getReclaimIdleMs()), "0-0").count(20));
            for (StreamMessage<String, String> message : claimed.getMessages()) {
                if (message.getBody() == null || message.getBody().isEmpty()) {
                    cmd.xack(Streams.JOBS, Streams.JOBS_GROUP, message.getId());
                    continue;
                }
                JobMessage job = redis.parseJob(message.getBody());
                log.warn("reclaiming job {} (message {}) from a dead runner", job.getJobId(), message.getId());
                redis.finish(cmd, message.getId(), job, executor.lost(job));
            }
        } catch (Exception e) {
            log.warn("reclaim failed: {}", e.getMessage());
        }
    }

    @Override
    public void stop() {
        running.set(false);
        ready.set(false);
        // 생존 신호부터 지운다. 진행 중 작업을 기다리는 동안(최대 jobTimeout+10초) 컨테이너가 먼저 강제 종료될 수 있어,
        // 마지막에 지우면 TTL(10초) 동안 testrun-service가 멈춘 runner에 작업을 계속 넣는다.
        try {
            redis.shared().del(Streams.runner(properties.getId()));
            Files.deleteIfExists(READY_FILE);
        } catch (Exception e) {
            log.warn("heartbeat cleanup failed: {}", e.getMessage());
        }
        for (Thread t : threads) {
            try {
                t.join(Duration.ofSeconds(properties.getJobTimeoutSec() + 10).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Slot slot : slots) {
            slot.stop(slotEnv(slot));
        }
        if (reclaimConnection != null) {
            reclaimConnection.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private Map<String, String> slotEnv(Slot slot) {
        return JudgeEnv.base(properties.getJudgehostRoot(), slot.getRunuser(), recipes.current().getLimits());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
