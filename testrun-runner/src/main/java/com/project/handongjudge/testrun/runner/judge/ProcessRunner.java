package com.project.handongjudge.testrun.runner.judge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** DOMjudge 스크립트 실행. 출력은 파일로 보내고, 제한 시간을 넘기면 프로세스 트리를 종료한다. */
@Slf4j
public final class ProcessRunner {

    /** 제한 시간 초과로 종료됨. */
    public static final int TIMED_OUT = -2;

    private ProcessRunner() {
    }

    public static int run(List<String> command, Path cwd, Map<String, String> env, Path outputFile,
                          long timeoutMs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile != null ? outputFile.toFile() : new File("/dev/null"));
        pb.environment().putAll(env);
        Process process = pb.start();
        if (!process.waitFor(Math.max(timeoutMs, 1), TimeUnit.MILLISECONDS)) {
            log.warn("process timed out after {}ms: {}", timeoutMs, command);
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return TIMED_OUT;
        }
        return process.exitValue();
    }
}
