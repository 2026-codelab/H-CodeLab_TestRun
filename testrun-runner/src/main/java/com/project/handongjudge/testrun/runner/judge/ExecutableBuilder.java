package com.project.handongjudge.testrun.runner.judge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * DOMjudge 실행파일(zip)을 풀어 build_executable.sh로 빌드한다.
 * 디렉토리 구조와 해시 계산은 judgedaemon fetch_executable_internal(:331-460)과 같다.
 */
@Slf4j
public final class ExecutableBuilder {

    private ExecutableBuilder() {
    }

    public record ZipFile(String filename, byte[] content, boolean executable) {
    }

    /**
     * 실행 권한 비트는 zip 중앙 디렉토리(external attributes)에만 있으므로, 스트림 방식이 아니라 ZipFile로 읽는다.
     */
    public static List<ZipFile> unzip(byte[] zip) throws IOException {
        List<ZipFile> files = new ArrayList<>();
        try (org.apache.commons.compress.archivers.zip.ZipFile archive =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder()
                             .setSeekableByteChannel(new SeekableInMemoryByteChannel(zip)).get()) {
            for (ZipArchiveEntry entry : Collections.list(archive.getEntries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.contains("/") || name.contains("..")) {
                    throw new IOException("unexpected path in executable zip: " + name);
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    files.add(new ZipFile(name, in.readAllBytes(), (entry.getUnixMode() & 0111) != 0));
                }
            }
        }
        return files;
    }

    /** md5( 파일명 순으로 md5(내용) + 파일명 + (실행 가능이면 "1") 을 이어 붙인 것 ). */
    public static String hash(List<ZipFile> files) {
        StringBuilder joined = new StringBuilder();
        files.stream()
                .sorted(Comparator.comparing(ZipFile::filename))
                .forEach(f -> joined.append(md5(f.content())).append(f.filename()).append(f.executable() ? "1" : ""));
        return md5(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {root}/{type}/{execId}/{hash}/build 에 풀고 빌드한다. 이미 .deployed가 있으면 건너뛴다.
     *
     * @return 빌드된 run 파일 경로
     */
    public static Path build(Path root, String type, String execId, String hash, List<ZipFile> files,
                             Path libJudgeDir, Map<String, String> env) throws IOException, InterruptedException {
        Path execDir = root.resolve(type).resolve(execId).resolve(hash);
        Path buildDir = execDir.resolve("build");
        Path runPath = buildDir.resolve("run");
        if (Files.exists(execDir.resolve(".deployed")) && Files.isExecutable(runPath)) {
            return runPath;
        }
        deleteRecursively(execDir);
        Files.createDirectories(buildDir);
        for (ZipFile f : files) {
            Path target = buildDir.resolve(f.filename());
            Files.write(target, f.content());
            if (f.executable()) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
        if (!Files.exists(buildDir.resolve("build"))) {
            // DOMjudge 기본 실행파일은 모두 build 스크립트를 포함한다. 없는 경우의 언어 자동 감지는 지원하지 않는다.
            if (!Files.isExecutable(runPath)) {
                throw new IOException("executable " + type + "/" + execId + " has neither 'build' nor 'run'");
            }
        } else {
            int rc = ProcessRunner.run(
                    List.of(libJudgeDir.resolve("build_executable.sh").toString(), execDir.toString()),
                    execDir, env, execDir.resolve("build_executable.out"), 120_000);
            if (rc != 0 || !Files.isExecutable(runPath)) {
                String buildLog = Files.exists(execDir.resolve("build.log"))
                        ? Files.readString(execDir.resolve("build.log")) : "";
                throw new IOException("building " + type + "/" + execId + " failed (exit " + rc + "): " + buildLog);
            }
        }
        Files.createFile(execDir.resolve(".deployed"));
        log.info("built executable {}/{} hash={}", type, execId, hash);
        return runPath;
    }

    static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
