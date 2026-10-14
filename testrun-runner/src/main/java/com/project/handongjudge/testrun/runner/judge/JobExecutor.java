package com.project.handongjudge.testrun.runner.judge;

import com.project.handongjudge.testrun.contract.Enums;
import com.project.handongjudge.testrun.contract.EventType;
import com.project.handongjudge.testrun.contract.JobMessage;
import com.project.handongjudge.testrun.contract.ResultSummary;
import com.project.handongjudge.testrun.contract.Streams;
import com.project.handongjudge.testrun.contract.Verdict;
import com.project.handongjudge.testrun.runner.RunnerProperties;
import com.project.handongjudge.testrun.runner.testcase.TestcaseSet;
import com.project.handongjudge.testrun.runner.testcase.TestcaseStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 테스트하기 1건 실행. DOMjudge compile.sh / testcase_run.sh를 judgedaemon과 같은 인자로 직접 호출한다.
 * 첫 실패에서 중단한다 (VERIFY 모드는 전부 실행).
 */
@Slf4j
@Component
public class JobExecutor {

    private static final Pattern JAVA_PUBLIC_CLASS = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)*class\\s+(\\w+)");
    private static final String RETRY_MESSAGE = "테스트 실행 중 오류가 발생했습니다. 다시 시도해 주세요.";

    private final RunnerProperties properties;
    private final TestcaseStore testcases;

    public JobExecutor(RunnerProperties properties, TestcaseStore testcases) {
        this.properties = properties;
        this.testcases = testcases;
    }

    public JobOutcome execute(JobMessage job, Slot slot, Recipe recipe, EventSink sink, long dequeuedAt) {
        long deadline = dequeuedAt + properties.getJobTimeoutSec() * 1000L;
        ResultSummary summary = baseSummary(job, dequeuedAt);
        try {
            return run(job, slot, recipe, sink, deadline, summary);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(summary, "INTERRUPTED", "runner shutting down");
        } catch (Exception e) {
            log.error("job {} failed", job.getJobId(), e);
            return error(summary, "INTERNAL", e.getMessage());
        }
    }

    private JobOutcome run(JobMessage job, Slot slot, Recipe recipe, EventSink sink, long deadline,
                           ResultSummary summary) throws IOException, InterruptedException {
        Recipe.Language language = recipe.language(job.getLangId());
        if (language == null) {
            return error(summary, "UNSUPPORTED_LANGUAGE", "language " + job.getLangId());
        }

        TestcaseSet set;
        try {
            set = testcases.get(job.getProblemId(), job.getProblemHash());
        } catch (IOException e) {
            return error(summary, "TESTCASES_UNAVAILABLE", e.getMessage());
        }
        summary.setTestcaseSource(set.source());
        summary.setTcTotal(set.testcases().size());
        sink.emit(EventType.TOTAL, Map.of("count", set.testcases().size()));

        // 컴파일
        slot.clean();
        Path workdir = slot.getWorkdir();
        Files.createDirectories(workdir.resolve("compile"));
        String sourceName = sourceFileName(language, job.getCode());
        Files.writeString(workdir.resolve("compile").resolve(sourceName), job.getCode(), StandardCharsets.UTF_8);

        Map<String, String> env = JudgeEnv.forJob(
                JudgeEnv.base(properties.getJudgehostRoot(), slot.getRunuser(), recipe.getLimits()), job.getMemoryLimitMb());
        int rc = ProcessRunner.run(List.of(
                        slot.getLibJudgeDir().resolve("compile.sh").toString(), "-n", String.valueOf(slot.getCpu()),
                        language.getCompile().getRunPath().toString(), workdir.toString(), sourceName),
                workdir, env, workdir.resolve("compile.stdout"), remaining(deadline));
        summary.setCompiledAt(System.currentTimeMillis());
        if (rc == ProcessRunner.TIMED_OUT) {
            return error(summary, "JOB_TIMEOUT", "compile exceeded job timeout");
        }
        if (rc > 128) {
            return killed(summary, "compile", rc);
        }
        if (rc != 0) {
            String meta = readLimited(workdir.resolve("compile.meta"), 4096);
            if (meta.contains("internal-error")) {
                return error(summary, "COMPILE_INTERNAL_ERROR", meta.trim());
            }
            return compileError(summary, workdir.resolve("compile.out"));
        }

        // 테스트케이스 실행
        double soft = (job.getTimeLimitSec() != null ? job.getTimeLimitSec() : 1.0) * language.getTimeFactor();
        String timeLimit = TimeLimits.argument(soft, TimeLimits.hard(soft, recipe.getOvershoot()));
        boolean sample = set.source() == Enums.TestcaseSource.SAMPLE;
        boolean stopOnFailure = job.getMode() != Enums.Mode.VERIFY;
        Verdict finalVerdict = Verdict.AC;
        int passed = 0;
        int maxRuntimeMs = 0;
        int maxMemoryKb = 0;

        for (TestcaseSet.Testcase tc : set.testcases()) {
            Path passdir = workdir.resolve(String.format("testcase%05d", tc.rank())).resolve("1");
            Path execdir = passdir.resolve("execdir");
            Files.createDirectories(execdir);
            linkProgram(workdir.resolve("compile"), execdir);

            rc = ProcessRunner.run(List.of(
                            slot.getLibJudgeDir().resolve("testcase_run.sh").toString(), "-n", String.valueOf(slot.getCpu()),
                            tc.input().toString(), tc.answer().toString(), timeLimit, passdir.toString(),
                            recipe.getRun().getRunPath().toString(), recipe.getCompare().getRunPath().toString(),
                            job.getCompareArgs() == null ? "" : job.getCompareArgs()),
                    workdir, env, passdir.getParent().resolve("run.stdout"), remaining(deadline));
            if (rc == ProcessRunner.TIMED_OUT) {
                return error(summary, "JOB_TIMEOUT", "testcase " + tc.rank() + " exceeded job timeout");
            }
            if (rc > 128) {
                return killed(summary, "testcase " + tc.rank(), rc);
            }
            Verdict verdict = Verdict.fromExitCode(rc);
            if (verdict == Verdict.ERR) {
                return error(summary, rc == 120 ? "COMPARE_ERROR" : "RUN_INTERNAL_ERROR",
                        "testcase " + tc.rank() + " exit " + rc);
            }

            RunMeta meta = RunMeta.read(passdir.resolve("program.meta"));
            maxRuntimeMs = Math.max(maxRuntimeMs, meta.runtimeMs());
            maxMemoryKb = Math.max(maxMemoryKb, meta.memoryKb());
            summary.getCases().add(new ResultSummary.CaseResult(tc.rank(), verdict, meta.runtimeMs(), meta.memoryKb()));
            sink.emit(EventType.TESTCASE, testcaseEvent(tc, verdict, meta, passdir, sample, recipe.getOutputStorageLimit()));

            if (verdict == Verdict.AC) {
                passed++;
            } else {
                if (summary.getFirstFailRank() == null) {
                    summary.setFirstFailRank(tc.rank());
                    finalVerdict = verdict;
                }
                if (stopOnFailure) {
                    break;
                }
            }
        }

        summary.setStatus(Enums.JobStatus.DONE);
        summary.setVerdict(finalVerdict);
        summary.setTcPassed(passed);
        summary.setMaxRuntimeMs(maxRuntimeMs);
        summary.setMaxMemoryKb(maxMemoryKb);
        summary.setFinishedAt(System.currentTimeMillis());
        return new JobOutcome(EventType.COMPLETE, timing(summary, Map.of("result", finalVerdict.name())), summary);
    }

    /**
     * 처리 중이던 runner가 사라진 작업. 학생 코드를 두 번 실행하지 않기 위해 재실행하지 않고 error로 끝낸다 (설계 문서 2-5).
     */
    public JobOutcome lost(JobMessage job) {
        return error(baseSummary(job, System.currentTimeMillis()), "RUNNER_LOST", "job reclaimed from a dead runner");
    }

    /** 큐에서 상한보다 오래 기다린 작업. 실행하지 않는다. */
    public JobOutcome expired(JobMessage job, long dequeuedAt) {
        return error(baseSummary(job, dequeuedAt), "QUEUE_TIMEOUT",
                "waited " + (dequeuedAt - job.getEnqueuedAt()) + "ms in queue");
    }

    private JobOutcome compileError(ResultSummary summary, Path compileOut) throws IOException {
        long bytes = Files.exists(compileOut) ? Files.size(compileOut) : 0;
        String output = readLimited(compileOut, Streams.MAX_COMPILE_OUTPUT_BYTES);
        summary.setStatus(Enums.JobStatus.DONE);
        summary.setVerdict(Verdict.CE);
        summary.setTcPassed(0);
        summary.setCompileOutput(output);
        summary.setCompileOutputBytes((int) bytes);
        summary.setCompileOutputTruncated(bytes > Streams.MAX_COMPILE_OUTPUT_BYTES);
        summary.setFinishedAt(System.currentTimeMillis());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", "CE");
        data.put("output_compile", output);
        return new JobOutcome(EventType.CE, timing(summary, data), summary);
    }

    /**
     * DOMjudge 스크립트가 신호로 종료됨 (예: 143 = SIGTERM, 컨테이너 종료 시). 채점 오류와 구분해 통계가 왜곡되지 않게 한다.
     */
    private JobOutcome killed(ResultSummary summary, String stage, int rc) {
        return error(summary, "KILLED_BY_SIGNAL", stage + " killed by signal " + (rc - 128));
    }

    private JobOutcome error(ResultSummary summary, String code, String detail) {
        log.warn("job {} error {}: {}", summary.getJobId(), code, detail);
        summary.setStatus(Enums.JobStatus.ERROR);
        summary.setVerdict(Verdict.ERR);
        summary.setErrorCode(code);
        summary.setErrorMessage(detail);
        summary.setFinishedAt(System.currentTimeMillis());
        return new JobOutcome(EventType.ERROR, timing(summary, Map.of("message", RETRY_MESSAGE, "code", code)), summary);
    }

    private ResultSummary baseSummary(JobMessage job, long dequeuedAt) {
        ResultSummary summary = new ResultSummary();
        summary.setJobId(job.getJobId());
        summary.setUserId(job.getUserId());
        summary.setSectionId(job.getSectionId());
        summary.setProblemId(job.getProblemId());
        summary.setLangId(job.getLangId());
        summary.setMode(job.getMode());
        summary.setRunnerId(properties.getId());
        summary.setEnqueuedAt(job.getEnqueuedAt());
        summary.setDequeuedAt(dequeuedAt);
        summary.setCases(new ArrayList<>());
        return summary;
    }

    private Map<String, Object> testcaseEvent(TestcaseSet.Testcase tc, Verdict verdict, RunMeta meta, Path passdir,
                                              boolean sample, int limit) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("index", tc.rank());
        data.put("result", verdict.longForm());
        data.put("runtime", meta.runtimeMs());
        data.put("memoryUsed", meta.memoryBytes());
        if (sample) {
            // 샘플만 입출력을 공개한다. secret이면 판정·시간·메모리만 (설계 문서 5장).
            data.put("output", readLimited(passdir.resolve("program.out"), limit));
            data.put("outputError", readLimited(passdir.resolve("program.err"), limit));
            data.put("outputDiff", readLimited(passdir.resolve("feedback/judgemessage.txt"), limit));
            data.put("testcaseInput", readLimited(tc.input(), limit));
            data.put("expectedOutput", readLimited(tc.answer(), limit));
        }
        return data;
    }

    private static Map<String, Object> timing(ResultSummary summary, Map<String, Object> data) {
        Map<String, Object> withTiming = new LinkedHashMap<>(data);
        withTiming.put("t_dequeued", summary.getDequeuedAt());
        withTiming.put("t_compiled", summary.getCompiledAt());
        withTiming.put("t_finished", summary.getFinishedAt());
        return withTiming;
    }

    /** 컴파일 결과를 테스트케이스 실행 디렉토리로 하드링크 (judgedaemon과 같은 cp -PRl). */
    private static void linkProgram(Path compileDir, Path execdir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("cp", "-PRl"));
        try (Stream<Path> files = Files.list(compileDir)) {
            files.forEach(p -> command.add(p.toString()));
        }
        command.add(execdir.toString());
        int rc = ProcessRunner.run(command, compileDir, Map.of(), null, 30_000);
        if (rc != 0) {
            throw new IOException("could not link program into " + execdir);
        }
    }

    /** Java는 public class 이름과 파일명이 같아야 한다 (backend CodeExtenstion과 같은 규칙). */
    static String sourceFileName(Recipe.Language language, String code) {
        if ("java".equals(language.getId())) {
            Matcher m = JAVA_PUBLIC_CLASS.matcher(code);
            return (m.find() ? m.group(1) : "Main") + ".java";
        }
        return "main." + language.getExtension();
    }

    private static long remaining(long deadline) {
        return Math.max(deadline - System.currentTimeMillis(), 1);
    }

    private static String readLimited(Path path, int maxBytes) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        try (InputStream in = Files.newInputStream(path)) {
            return new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8);
        }
    }
}
