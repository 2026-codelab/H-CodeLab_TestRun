package com.project.handongjudge.testrun.runner.testcase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.handongjudge.testrun.contract.Enums;
import com.project.handongjudge.testrun.runner.RunnerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 테스트케이스 캐시. {workRoot}/testcases/{hash}/{sample|secret}/{rank}.in, .ans
 * 캐시에 없으면 backend 내부 API {@code GET /internal/testrun/problems/{id}/testcases}에서 받아 저장한다.
 * 응답: {hash, sample:[{rank, name, input, answer}], secret:[...]}
 */
@Slf4j
@Component
public class TestcaseStore {

    private static final Pattern SAFE_HASH = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String COMPLETE_MARKER = ".complete";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final RunnerProperties properties;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TestcaseStore(ObjectMapper mapper, RunnerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public TestcaseSet get(long problemId, String problemHash) throws IOException, InterruptedException {
        if (problemHash == null || !SAFE_HASH.matcher(problemHash).matches()) {
            throw new IOException("invalid problemHash: " + problemHash);
        }
        Path dir = root().resolve(problemHash);
        if (!Files.exists(dir.resolve(COMPLETE_MARKER))) {
            synchronized (locks.computeIfAbsent(problemHash, k -> new Object())) {
                if (!Files.exists(dir.resolve(COMPLETE_MARKER))) {
                    fetch(problemId, problemHash);
                }
            }
        }
        return read(problemHash, dir);
    }

    private Path root() {
        return properties.getWorkRoot().resolve("testcases");
    }

    private TestcaseSet read(String hash, Path dir) throws IOException {
        List<TestcaseSet.Testcase> sample = list(dir.resolve("sample"));
        if (!sample.isEmpty()) {
            return new TestcaseSet(hash, Enums.TestcaseSource.SAMPLE, sample);
        }
        List<TestcaseSet.Testcase> secret = list(dir.resolve("secret"));
        if (secret.isEmpty()) {
            throw new IOException("no testcases for problemHash " + hash);
        }
        return new TestcaseSet(hash, Enums.TestcaseSource.SECRET, secret);
    }

    private List<TestcaseSet.Testcase> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<TestcaseSet.Testcase> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path in : files.filter(p -> p.getFileName().toString().endsWith(".in")).toList()) {
                String name = in.getFileName().toString();
                int rank = Integer.parseInt(name.substring(0, name.length() - 3));
                Path ans = dir.resolve(rank + ".ans");
                if (Files.exists(ans)) {
                    result.add(new TestcaseSet.Testcase(rank, in, ans));
                }
            }
        }
        result.sort(Comparator.comparingInt(TestcaseSet.Testcase::rank));
        return result;
    }

    private void fetch(long problemId, String problemHash) throws IOException, InterruptedException {
        RunnerProperties.Internal internal = properties.getInternal();
        String base = internal.getBaseUrl().endsWith("/") ? internal.getBaseUrl() : internal.getBaseUrl() + "/";
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "internal/testrun/problems/" + problemId + "/testcases"))
                .timeout(Duration.ofSeconds(10))
                .header("X-Internal-Token", internal.getToken())
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("testcase API problem " + problemId + " -> HTTP " + response.statusCode());
        }
        JsonNode body = mapper.readTree(response.body());
        String hash = body.path("hash").asText();
        if (!problemHash.equals(hash)) {
            // 작업이 큐에 있는 동안 문제가 수정됐다. 요청한 해시와 다른 테스트케이스로 실행하지 않는다.
            throw new IOException("testcase hash changed for problem " + problemId + ": job=" + problemHash + " api=" + hash);
        }

        Path tmp = Files.createTempDirectory(Files.createDirectories(root()), ".tmp-" + problemHash + "-");
        write(tmp.resolve("sample"), body.path("sample"));
        write(tmp.resolve("secret"), body.path("secret"));
        Files.createFile(tmp.resolve(COMPLETE_MARKER));
        Path target = root().resolve(problemHash);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        log.info("cached testcases problem={} hash={}", problemId, problemHash);
    }

    private void write(Path dir, JsonNode cases) throws IOException {
        Files.createDirectories(dir);
        for (JsonNode tc : cases) {
            int rank = tc.path("rank").asInt();
            Files.writeString(dir.resolve(rank + ".in"), tc.path("input").asText(""), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(rank + ".ans"), tc.path("answer").asText(""), StandardCharsets.UTF_8);
        }
    }
}
