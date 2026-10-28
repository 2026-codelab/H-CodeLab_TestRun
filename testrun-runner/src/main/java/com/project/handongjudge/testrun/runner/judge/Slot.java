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
    /** start-runner.sh의 sudoers 항목과 같은 경로여야 한다. */
    private static final String PKILL = "/usr/bin/pkill";

    private final int index;
    private final int cpu;
    private final String runuser;
    private final Path workdir;
    private final Path libJudgeDir;
    /** 실행 사용자의 프로세스가 남아 있을 수 있음. 정리될 때까지 새 작업을 받지 않는다. */
    private volatile boolean dirty;

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

    /**
     * 이 슬롯 실행 사용자의 프로세스를 모두 종료한다. 워커(domjudge)는 sudo·runguard(root)와 학생 프로그램(runuser)에
     * 신호를 보낼 권한이 없어, start-runner.sh가 슬롯 사용자별로 sudoers에 등록한 pkill을 쓴다.
     * 학생 프로그램·컴파일러·비교 프로그램이 끝나면 그 위의 runguard와 sudo도 따라 끝난다.
     * 실행 사용자는 슬롯마다 따로라 다른 슬롯이나 judgehost의 프로세스는 건드리지 않는다.
     *
     * @return 남은 프로세스가 없으면 true. false면 {@link #isDirty()}가 true로 남는다.
     */
    public boolean killProcesses() {
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (ProcessRunner.run(List.of("pgrep", "-u", runuser), workdir, Map.of(), null, 5_000) == 1) {
                    dirty = false;   // pgrep 1 = 일치하는 프로세스 없음
                    return true;
                }
                ProcessRunner.run(List.of("sudo", "-n", PKILL, "-KILL", "-u", runuser), workdir, Map.of(), null, 5_000);
                Thread.sleep(500);
            }
        } catch (IOException e) {
            log.warn("slot {}: could not stop processes of {}", index, runuser, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dirty = true;
        log.error("slot {}: processes of {} remain; not accepting jobs until they are gone", index, runuser);
        return false;
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
