package com.project.handongjudge.testrun.runner.judge;

import java.nio.file.Path;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** domserver에서 받아 빌드한 채점 레시피. 불변이며, 갱신 시 통째로 교체한다. */
@Getter
@AllArgsConstructor
public class Recipe {

    private final Limits limits;
    private final String overshoot;
    /** 화면에 보낼 출력 최대 바이트 (config output_storage_limit). */
    private final int outputStorageLimit;
    private final Map<String, Language> languages;
    private final Executable run;
    private final Executable compare;

    public Language language(String langId) {
        return languages.get(langId);
    }

    @Getter
    @AllArgsConstructor
    public static class Limits {
        /** kB */
        private final long memoryLimit;
        /** kB */
        private final long outputLimit;
        private final int processLimit;
        private final int scriptTimeLimit;
        /** kB */
        private final long scriptMemoryLimit;
        /** kB */
        private final long scriptFilesizeLimit;
    }

    @Getter
    @AllArgsConstructor
    public static class Language {
        private final String id;
        private final double timeFactor;
        /** 소스 파일 확장자 (첫 번째 확장자). */
        private final String extension;
        private final Executable compile;
    }

    @Getter
    @AllArgsConstructor
    public static class Executable {
        private final String type;
        private final String execId;
        private final String hash;
        /** 빌드된 run 파일 경로. */
        private final Path runPath;
    }
}
