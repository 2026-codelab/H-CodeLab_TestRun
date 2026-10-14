package com.project.handongjudge.testrun.runner.judge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** runguard 메타 파일(program.meta) 해석. */
public record RunMeta(double timeUsedSec, long memoryBytes) {

    public static RunMeta read(Path metaFile) throws IOException {
        if (!Files.exists(metaFile)) {
            return new RunMeta(0, 0);
        }
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(metaFile)) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        // time-used가 가리키는 값(cpu-time 또는 wall-time)이 판정에 쓰인 시간이다.
        String timeKey = values.getOrDefault("time-used", "cpu-time");
        return new RunMeta(parseDouble(values.get(timeKey)), parseLong(values.get("memory-bytes")));
    }

    public int runtimeMs() {
        return (int) Math.round(timeUsedSec * 1000);
    }

    public int memoryKb() {
        return (int) (memoryBytes / 1024);
    }

    private static double parseDouble(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
