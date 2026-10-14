package com.project.handongjudge.testrun.runner.judge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DOMjudge compile.sh / testcase_run.sh와 같은 일을 셸 스크립트 없이 한다.
 * <p>
 * 같은 runguard(샌드박스), 같은 runguard 옵션, 같은 run·compare 실행파일, 같은 파일 이름, 같은 결과 판정 순서를 쓰고
 * 같은 종료 코드를 돌려준다. 바뀌는 것은 스크립트가 테스트케이스마다 띄우던 약 120개의 보조 프로세스
 * (로그, grep·sed로 메타 읽기, 디스크 정리, 대화형 문제용 복사 등)를 Java 코드로 대신하는 것뿐이다.
 * (설계 문서 2-6 선택 최적화. 근거: judgehost 9.0.0의 lib/judge/compile.sh:115-210, testcase_run.sh:130-345)
 * <p>
 * 대화형(combined run/compare) 문제와 ENTRY_POINT 지정은 지원하지 않는다 (테스트하기에서 쓰지 않음).
 */
public final class DirectJudge {

    private static final String RUNGROUP = "domjudge-run";
    private static final int E_CORRECT = 0;
    private static final int E_COMPILER_ERROR = 101;
    private static final int E_TIMELIMIT = 102;
    private static final int E_RUN_ERROR = 103;
    private static final int E_NO_OUTPUT = 104;
    private static final int E_WRONG_ANSWER = 105;
    private static final int E_OUTPUT_LIMIT = 108;
    private static final int E_COMPARE_ERROR = 120;
    /** compile.sh는 E_INTERNAL_ERROR가 없으면 1로 끝난다. 호출한 쪽은 compile.meta의 internal-error로 구분한다. */
    private static final int INTERNAL = 1;

    private DirectJudge() {
    }

    /**
     * compile.sh와 같다. workdir/compile/{sourceName}에 소스가 있어야 한다.
     * 결과 파일: workdir/compile.out, compile.meta, compile/program
     *
     * @return 0(성공), 101(컴파일 에러), 1(내부 오류: compile.meta에 internal-error), 또는 {@link ProcessRunner#TIMED_OUT}
     */
    public static int compile(Path workdir, String sourceName, Path compileRun, int cpu, String runuser,
                              Map<String, String> env, long timeoutMs) throws IOException, InterruptedException {
        Path compileDir = workdir.resolve("compile");
        Path compileOut = workdir.resolve("compile.out");
        Path compileMeta = workdir.resolve("compile.meta");
        Path compileTmp = workdir.resolve("compile.tmp");

        chmod(compileDir, "rwxrwxrwx");
        touch(compileOut);
        touch(compileMeta);

        // 컴파일 실행파일을 chroot 안으로 복사 (cp -a "$(dirname "$COMPILE_SCRIPT")"/* compile-script/)
        Path scriptDir = workdir.resolve("compile-script");
        Files.createDirectories(scriptDir);
        chmod(scriptDir, "rwxrwxrwx");
        copyDir(compileRun.getParent(), scriptDir);
        chmod(compileDir.resolve(sourceName), addRead(compileDir.resolve(sourceName)));

        String bin = env.get("DJ_BINDIR");
        List<String> command = List.of("sudo", "-n", bin + "/runguard", "-P", String.valueOf(cpu),
                "-u", runuser, "-g", RUNGROUP, "-r", workdir.toString(), "-d", "/compile",
                "-m", env.get("SCRIPTMEMLIMIT"), "-t", env.get("SCRIPTTIMELIMIT"), "--no-core",
                "-f", env.get("SCRIPTFILELIMIT"), "-s", env.get("SCRIPTFILELIMIT"),
                "-M", compileMeta.toString(), "--",
                "/compile-script/" + compileRun.getFileName(), "program", env.get("MEMLIMIT"), sourceName);
        int exit = ProcessRunner.run(command, compileDir, env, null, compileTmp, null, timeoutMs);
        if (exit == ProcessRunner.TIMED_OUT) {
            return exit;
        }

        chownBack(compileDir);
        chmodRecursive(compileDir, false);

        String tmp = read(compileTmp);
        try {
            if (exit != 0 && Files.size(compileMeta) == 0) {
                Files.writeString(compileMeta, "internal-error: runguard crashed\n");
                Files.writeString(compileOut, "Runguard exited with code " + exit
                        + " and 'compile.meta' is empty, it likely crashed.\nCompilation output:\n" + tmp);
                return INTERNAL;
            }
            List<String> internal = tmp.lines().filter(l -> l.toLowerCase().startsWith("internal-error: "))
                    .map(l -> "internal-error: compile script:" + l.substring("internal-error:".length()))
                    .collect(Collectors.toList());
            if (!internal.isEmpty()) {
                Files.writeString(compileMeta, String.join("\n", internal) + "\n", StandardOpenOption.APPEND);
                Files.writeString(compileOut, "The compile script threw an internal error. Compilation output:\n" + tmp);
                return INTERNAL;
            }
            if (timedOut(RunMetaFile.read(compileMeta))) {
                Files.writeString(compileOut, "Compiling aborted after " + env.get("SCRIPTTIMELIMIT")
                        + " seconds, compiler output:\n" + tmp);
                return E_COMPILER_ERROR;
            }
            if (exit != 0) {
                Files.writeString(compileOut, "Compiling failed with exitcode " + exit + ", compiler output:\n" + tmp);
                return E_COMPILER_ERROR;
            }
            Path program = compileDir.resolve("program");
            if (!Files.isRegularFile(program) || !Files.isExecutable(program)) {
                Files.writeString(compileOut, "Compiling failed: no executable was created; compiler output:\n" + tmp);
                return E_COMPILER_ERROR;
            }
            // 성공 시 entry point 감지 메시지는 빼고 남긴다
            String kept = tmp.lines().filter(l -> !l.matches(".*[Dd]etected entry_point: .*"))
                    .collect(Collectors.joining("\n"));
            Files.writeString(compileOut, kept.isEmpty() ? "" : kept + "\n", StandardOpenOption.APPEND);
            return E_CORRECT;
        } finally {
            chmod(compileDir, "rwx------");
            chmod(scriptDir, "rwx------");
        }
    }

    /**
     * testcase_run.sh와 같다. passdir/execdir/program이 있어야 하고, chroot 루트는 passdir/../.. (슬롯 디렉토리).
     * 결과 파일: passdir/program.out, program.err, program.meta, compare.meta, feedback/judgemessage.txt
     *
     * @return 0(정답), 102, 103, 104, 105, 108, 120(비교 오류), 또는 {@link ProcessRunner#TIMED_OUT}
     */
    public static int runTestcase(Path passdir, Path testIn, Path testOut, String cpuLimit, String wallLimit,
                                  Path runScript, Path compareScript, String compareArgs, int cpu, String runuser,
                                  Map<String, String> env, long deadline) throws IOException, InterruptedException {
        Path root = passdir.getParent().getParent();
        String prefix = "/" + passdir.getParent().getFileName() + "/" + passdir.getFileName();
        String bin = env.get("DJ_BINDIR");

        chmod(passdir, "rwxr-xr-x");
        chmod(passdir.resolve("execdir"), "rwxr-xr-x");
        for (String f : new String[] {"system.out", "program.out", "program.err", "program.meta", "runguard.err",
                "compare.meta", "compare.err"}) {
            touch(passdir.resolve(f));
        }
        Files.copy(testIn, passdir.resolve("testdata.in"), StandardCopyOption.REPLACE_EXISTING);
        for (String d : new String[] {"bin", "dj-bin", "dev"}) {
            Path dir = root.resolve(d);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                chmod(dir, "rwx--x--x");
            }
        }

        // 프로그램 실행: run 실행파일이 입출력을 연결하고 runguard 안에서 프로그램을 실행한다.
        List<String> run = new ArrayList<>(List.of(runScript.toString(), "testdata.in", "program.out",
                "sudo", "-n", bin + "/runguard", "-P", String.valueOf(cpu), "-r", root.toString(),
                "--nproc=" + env.get("PROCLIMIT"), "--no-core", "--streamsize=" + env.get("FILELIMIT"),
                "--user=" + runuser, "--group=" + RUNGROUP, "--walltime=" + wallLimit, "--cputime=" + cpuLimit,
                "--memsize=" + env.get("MEMLIMIT"), "--filesize=" + env.get("FILELIMIT"),
                "--stderr=program.err", "--outmeta=program.meta", "--", prefix + "/execdir/program"));
        if (ProcessRunner.run(run, passdir, env, null, null, passdir.resolve("runguard.err"), remaining(deadline))
                == ProcessRunner.TIMED_OUT) {
            return ProcessRunner.TIMED_OUT;
        }

        // 출력 비교 (프로그램이 끝난 뒤에야 정답 파일을 둔다)
        Files.copy(testOut, passdir.resolve("testdata.out"), StandardCopyOption.REPLACE_EXISTING);
        Path feedback = passdir.resolve("feedback");
        Files.createDirectories(feedback);
        chmod(feedback, "rwxrwxrwx");
        List<String> compare = new ArrayList<>(List.of("sudo", "-n", bin + "/runguard", "-P", String.valueOf(cpu),
                "-u", runuser, "-g", RUNGROUP, "-m", env.get("SCRIPTMEMLIMIT"), "-t", env.get("SCRIPTTIMELIMIT"),
                "--no-core", "-f", env.get("SCRIPTFILELIMIT"), "-s", env.get("SCRIPTFILELIMIT"), "-M", "compare.meta",
                "--", compareScript.toString(), "testdata.in", "testdata.out", "feedback/"));
        if (compareArgs != null && !compareArgs.isBlank()) {
            compare.addAll(Arrays.asList(compareArgs.trim().split("\\s+")));
        }
        int compareExit = ProcessRunner.run(compare, passdir, env, passdir.resolve("program.out"),
                passdir.resolve("compare.tmp"), null, remaining(deadline));
        if (compareExit == ProcessRunner.TIMED_OUT) {
            return ProcessRunner.TIMED_OUT;
        }

        try {
            chownBack(feedback);
            chmodRecursive(feedback, false);
            Path judgeMessage = feedback.resolve("judgemessage.txt");
            touch(judgeMessage);
            String judgeError = read(feedback.resolve("judgeerror.txt"));
            if (!judgeError.isEmpty()) {
                Files.writeString(judgeMessage, "\n---------- output validator (error) messages ----------\n"
                        + judgeError, StandardOpenOption.APPEND);
            }
            if (timedOut(RunMetaFile.read(passdir.resolve("compare.meta")))) {
                return E_COMPARE_ERROR;
            }
            String compareTmp = read(passdir.resolve("compare.tmp"));
            if (!compareTmp.isEmpty()) {
                Files.writeString(judgeMessage, "\n---------- output validator stdout/stderr messages ----------\n"
                        + compareTmp, StandardOpenOption.APPEND);
            }
            if (compareExit != 42 && compareExit != 43) {
                return E_COMPARE_ERROR;
            }

            Map<String, String> meta = RunMetaFile.read(passdir.resolve("program.meta"));
            if (timedOut(meta)) {
                return E_TIMELIMIT;
            }
            if (!"0".equals(meta.get("exitcode"))) {
                return E_RUN_ERROR;
            }
            String truncated = meta.getOrDefault("output-truncated", "");
            if (Arrays.asList(truncated.split(",")).contains("stdout")) {
                return E_OUTPUT_LIMIT;
            }
            if (compareExit == 42) {
                return E_CORRECT;
            }
            return Files.size(passdir.resolve("program.out")) == 0 ? E_NO_OUTPUT : E_WRONG_ANSWER;
        } finally {
            chmod(passdir, "rwx------");   // 다음 실행이 접근하지 못하게 (testcase_run.sh cleanup)
        }
    }

    /** runguard 메타 파일 → key/value. */
    static final class RunMetaFile {
        static Map<String, String> read(Path file) throws IOException {
            if (!Files.exists(file)) {
                return Map.of();
            }
            return Files.readAllLines(file).stream()
                    .filter(l -> l.indexOf(':') > 0)
                    .collect(Collectors.toMap(l -> l.substring(0, l.indexOf(':')).trim(),
                            l -> l.substring(l.indexOf(':') + 1).trim(), (a, b) -> a));
        }
    }

    private static boolean timedOut(Map<String, String> meta) {
        return meta.getOrDefault("time-result", "").contains("timelimit");
    }

    /** sudo chown -R {현재 사용자}: dir — runguard 실행 사용자가 만든 파일을 다시 읽고 지울 수 있게. */
    private static void chownBack(Path dir) throws IOException, InterruptedException {
        ProcessRunner.run(List.of("sudo", "-n", "/bin/chown", "-R", System.getProperty("user.name") + ":", dir.toString()),
                dir.getParent(), Map.of(), null, null, null, 30_000);
    }

    private static void chmodRecursive(Path dir, boolean groupOtherWrite) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.collect(Collectors.toList())) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(p));
                if (!groupOtherWrite) {
                    perms.remove(PosixFilePermission.GROUP_WRITE);
                    perms.remove(PosixFilePermission.OTHERS_WRITE);
                }
                Files.setPosixFilePermissions(p, perms);
            }
        }
    }

    private static Set<PosixFilePermission> addRead(Path file) throws IOException {
        Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
        perms.addAll(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        return perms;
    }

    private static void copyDir(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path src : paths.collect(Collectors.toList())) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void chmod(Path path, String perms) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
    }

    private static void chmod(Path path, Set<PosixFilePermission> perms) throws IOException {
        Files.setPosixFilePermissions(path, perms);
    }

    private static void touch(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }

    /** UTF-8이 아닌 바이트가 섞여 있어도 예외 없이 읽는다 (컴파일러·비교 프로그램 출력). */
    private static String read(Path file) throws IOException {
        return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
    }

    private static long remaining(long deadline) {
        return Math.max(deadline - System.currentTimeMillis(), 1);
    }
}
