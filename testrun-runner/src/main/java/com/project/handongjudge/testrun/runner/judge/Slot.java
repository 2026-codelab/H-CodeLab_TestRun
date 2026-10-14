package com.project.handongjudge.testrun.runner.judge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 동시 처리 칸. 슬롯마다 실행 사용자·CPU·작업 디렉토리가 따로 있고, chroot 마운트는 슬롯 시작 시 1회만 한다.
 * (작업마다 마운트·해제하는 비용을 없앰. 설계 문서 4.5)
 */
@Slf4j
@Getter
public class Slot {

    /** 작업마다 지우는 것. chroot 마운트(etc usr lib bin lib64 proc dev)와 dj-bin은 남긴다. */
    private static final Set<String> JOB_FILES = Set.of(
            "compile", "compile-script", "compile.out", "compile.meta", "compile.tmp", "compile.stdout");

    private final int index;
    private final int cpu;
    private final String runuser;
    private final Path workdir;
    private final Path libJudgeDir;

    public Slot(int index, int cpu, String runuser, Path workRoot, Path libJudgeDir) {
        this.index = index;
        this.cpu = cpu;
        this.runuser = runuser;
        this.workdir = workRoot.resolve("slot-" + index);
        this.libJudgeDir = libJudgeDir;
    }

    /**
     * 슬롯 준비. 이전에 비정상 종료(SIGKILL 등)됐다면 마운트는 사라졌어도 chroot 스크립트가 만든
     * 디렉토리·심볼릭 링크(lib64 → lib 등)가 남아 chroot start가 실패한다. 그래서 마운트가 없음을 확인한 뒤
     * 슬롯 디렉토리를 새로 만든다. 마운트가 남아 있는데 지우면 원본 chroot를 건드릴 수 있으므로 그때는 실패한다.
     */
    public void start(Map<String, String> env) throws IOException, InterruptedException {
        if (hasMounts()) {
            chroot("stop", env);
        }
        if (hasMounts()) {
            throw new IOException("stale mounts remain under " + workdir + "; refusing to reset slot " + index);
        }
        ExecutableBuilder.deleteRecursively(workdir);
        Files.createDirectories(workdir);
        int rc = chroot("start", env);
        if (rc != 0) {
            throw new IOException("chroot start failed for slot " + index + " (exit " + rc + ")");
        }
        log.info("slot {} ready: cpu={} runuser={} workdir={}", index, cpu, runuser, workdir);
    }

    public void stop(Map<String, String> env) {
        try {
            chroot("stop", env);
        } catch (Exception e) {
            log.warn("chroot stop failed for slot {}", index, e);
        }
    }

    /** 이전 작업 흔적을 지운다. */
    public void clean() throws IOException {
        try (Stream<Path> entries = Files.list(workdir)) {
            for (Path p : entries.toList()) {
                String name = p.getFileName().toString();
                if (JOB_FILES.contains(name) || name.startsWith("testcase")) {
                    ExecutableBuilder.deleteRecursively(p);
                }
            }
        }
    }

    private int chroot(String action, Map<String, String> env) throws IOException, InterruptedException {
        // 로그는 슬롯 밖에 둔다 (슬롯 디렉토리를 초기화해도 원인을 볼 수 있게).
        return ProcessRunner.run(List.of(libJudgeDir.resolve("chroot-startstop.sh").toString(), action),
                workdir, env, workdir.resolveSibling("slot-" + index + "-chroot-" + action + ".log"), 60_000);
    }

    /** /proc/mounts 기준으로 슬롯 디렉토리 아래에 마운트가 남아 있는지. */
    boolean hasMounts() throws IOException {
        String prefix = workdir.toAbsolutePath().normalize() + "/";
        for (String line : Files.readAllLines(Path.of("/proc/mounts"))) {
            String[] fields = line.split(" ");
            if (fields.length > 1 && (fields[1] + "/").startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
