package com.project.handongjudge.testrun.runner.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.project.handongjudge.testrun.runner.RunnerProperties;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 채점 레시피 관리. 기동 시 1회 domserver에서 받아 빌드하고, 1시간마다 해시를 확인해 바뀌면 다시 빌드한다.
 * 공식 채점(judgehost)과 같은 실행파일·한도를 쓰는 것이 결과 일치성의 근거다.
 */
@Slf4j
@Service
public class RecipeService {

    private final DomjudgeClient domjudge;
    private final RunnerProperties properties;
    private final AtomicReference<Recipe> current = new AtomicReference<>();

    public RecipeService(DomjudgeClient domjudge, RunnerProperties properties) {
        this.domjudge = domjudge;
        this.properties = properties;
    }

    public Recipe current() {
        Recipe recipe = current.get();
        if (recipe == null) {
            throw new IllegalStateException("recipe not loaded yet");
        }
        return recipe;
    }

    /** domserver에서 레시피를 받아 빌드한다. 실패하면 예외 (기동 실패 = 준비 안 됨). */
    public Recipe load() throws Exception {
        JsonNode config = domjudge.config();
        Recipe.Limits limits = new Recipe.Limits(
                config.path("memory_limit").asLong(2_097_152),
                config.path("output_limit").asLong(8192),
                config.path("process_limit").asInt(64),
                config.path("script_timelimit").asInt(30),
                config.path("script_memory_limit").asLong(2_097_152),
                config.path("script_filesize_limit").asLong(2_621_440));
        String overshoot = config.path("timelimit_overshoot").asText("1s|10%");
        int outputStorageLimit = config.path("output_storage_limit").asInt(50_000);

        Path exeRoot = properties.getWorkRoot().resolve("executable");
        Path libJudge = properties.getJudgehostRoot().resolve("lib/judge");
        Map<String, String> env = JudgeEnv.base(properties.getJudgehostRoot(), properties.runuserForSlot(0), limits);

        Map<String, Recipe.Language> languages = new LinkedHashMap<>();
        for (JsonNode lang : domjudge.languages()) {
            String langId = lang.path("id").asText();
            String execId = properties.getCompileExecutables().get(langId);
            if (execId == null || !lang.path("allow_judge").asBoolean(true)) {
                continue;
            }
            String expectedHash = lang.path("compile_executable_hash").asText();
            List<ExecutableBuilder.ZipFile> files = ExecutableBuilder.unzip(domjudge.executableZip(execId));
            String hash = ExecutableBuilder.hash(files);
            if (!hash.equals(expectedHash)) {
                // 언어 ↔ 컴파일 실행파일 매핑이 domserver 설정과 다르다. 공식 채점과 다른 컴파일러를 쓰게 되므로 쓰지 않는다.
                log.error("compile executable mismatch for {}: exec={} hash={} expected={}", langId, execId, hash, expectedHash);
                continue;
            }
            Path runPath = ExecutableBuilder.build(exeRoot, "compile", execId, hash, files, libJudge, env);
            String extension = lang.path("extensions").path(0).asText(langId);
            languages.put(langId, new Recipe.Language(langId, lang.path("time_factor").asDouble(1.0), extension,
                    new Recipe.Executable("compile", execId, hash, runPath)));
        }
        if (languages.isEmpty()) {
            throw new IllegalStateException("no usable language from domserver");
        }

        Recipe.Executable run = buildDefault("run", config.path("default_run").asText("run"), exeRoot, libJudge, env);
        Recipe.Executable compare = buildDefault("compare", config.path("default_compare").asText("compare"),
                exeRoot, libJudge, env);

        Recipe recipe = new Recipe(limits, overshoot, outputStorageLimit, Map.copyOf(languages), run, compare);
        current.set(recipe);
        log.info("recipe loaded: languages={} run={} compare={} overshoot={}",
                languages.keySet(), run.getHash(), compare.getHash(), overshoot);
        return recipe;
    }

    private Recipe.Executable buildDefault(String type, String execId, Path exeRoot, Path libJudge,
                                           Map<String, String> env) throws Exception {
        List<ExecutableBuilder.ZipFile> files = ExecutableBuilder.unzip(domjudge.executableZip(execId));
        String hash = ExecutableBuilder.hash(files);
        return new Recipe.Executable(type, execId, hash,
                ExecutableBuilder.build(exeRoot, type, execId, hash, files, libJudge, env));
    }

    /** 1시간마다 domserver 설정·실행파일 해시를 다시 확인한다. 바뀐 것만 새 디렉토리에 빌드되고 레시피가 교체된다. */
    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    public void refresh() {
        if (current.get() == null) {
            return;
        }
        try {
            load();
        } catch (Exception e) {
            log.error("recipe refresh failed; keeping previous recipe", e);
        }
    }
}
