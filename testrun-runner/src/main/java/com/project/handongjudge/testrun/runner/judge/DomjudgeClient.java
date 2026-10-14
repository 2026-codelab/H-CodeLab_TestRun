package com.project.handongjudge.testrun.runner.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.handongjudge.testrun.runner.RunnerProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * domserver 읽기 전용 호출. 테스트하기 1건마다 호출하지 않고, 기동 시와 주기적 해시 확인 때만 쓴다.
 * 운영에서는 jury 권한의 전용 읽기 계정을 쓴다 (설계 문서 2-3).
 */
@Component
public class DomjudgeClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final RunnerProperties properties;

    public DomjudgeClient(ObjectMapper mapper, RunnerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public JsonNode config() throws IOException, InterruptedException {
        return mapper.readTree(get("api/v4/config"));
    }

    public JsonNode languages() throws IOException, InterruptedException {
        return mapper.readTree(get("api/v4/languages?strict=false"));
    }

    /** 실행파일 zip. 응답 본문은 base64 문자열(JSON string). */
    public byte[] executableZip(String execId) throws IOException, InterruptedException {
        String base64 = mapper.readValue(get("api/v4/executables/" + execId), String.class);
        return Base64.getDecoder().decode(base64);
    }

    private byte[] get(String path) throws IOException, InterruptedException {
        RunnerProperties.Domserver ds = properties.getDomserver();
        String base = ds.getBaseUrl().endsWith("/") ? ds.getBaseUrl() : ds.getBaseUrl() + "/";
        String credentials = ds.getUser() + ":" + ds.resolvedPassword();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("domserver GET " + path + " -> HTTP " + response.statusCode());
        }
        return response.body();
    }
}
