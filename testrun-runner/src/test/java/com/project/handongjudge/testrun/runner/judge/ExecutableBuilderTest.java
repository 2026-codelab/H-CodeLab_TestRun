package com.project.handongjudge.testrun.runner.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;

class ExecutableBuilderTest {

    private static byte[] zip(Object[][] entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            for (Object[] e : entries) {
                ZipArchiveEntry entry = new ZipArchiveEntry((String) e[0]);
                entry.setUnixMode((Integer) e[2]);
                out.putArchiveEntry(entry);
                out.write(((String) e[1]).getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void readsExecutableBitFromCentralDirectory() throws Exception {
        // 회귀: 스트림 방식으로 읽으면 실행 비트가 항상 false가 되어 DOMjudge 해시와 달라졌다.
        List<ExecutableBuilder.ZipFile> files = ExecutableBuilder.unzip(zip(new Object[][] {
                {"run", "#!/bin/sh\n", 0100755},
                {"compare.cc", "int main(){}\n", 0100644},
        }));

        assertTrue(files.stream().filter(f -> f.filename().equals("run")).findFirst().orElseThrow().executable());
        assertFalse(files.stream().filter(f -> f.filename().equals("compare.cc")).findFirst().orElseThrow().executable());
    }

    @Test
    void hashFollowsJudgedaemonFormula() throws Exception {
        List<ExecutableBuilder.ZipFile> files = ExecutableBuilder.unzip(zip(new Object[][] {
                {"run", "R", 0100755},
                {"build", "B", 0100755},
                {"src.c", "S", 0100644},
        }));
        // 파일명 순(build, run, src.c)으로 md5(내용)+파일명+(실행이면 "1")
        String joined = ExecutableBuilder.md5("B".getBytes()) + "build1"
                + ExecutableBuilder.md5("R".getBytes()) + "run1"
                + ExecutableBuilder.md5("S".getBytes()) + "src.c";
        assertEquals(ExecutableBuilder.md5(joined.getBytes(StandardCharsets.UTF_8)), ExecutableBuilder.hash(files));
    }
}
