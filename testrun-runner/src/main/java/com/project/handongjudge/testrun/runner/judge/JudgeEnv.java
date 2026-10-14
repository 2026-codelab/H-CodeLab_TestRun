package com.project.handongjudge.testrun.runner.judge;

import com.project.handongjudge.testrun.contract.Verdict;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * DOMjudge 스크립트(compile.sh, testcase_run.sh, build_executable.sh)에 넘기는 환경변수.
 * judgedaemon.main.php:570-582, 1335-1344, 1438-1440과 같은 값을 만든다.
 */
public final class JudgeEnv {

    private JudgeEnv() {
    }

    public static Map<String, String> base(Path judgehostRoot, String runuser, Recipe.Limits limits) {
        Map<String, String> env = new HashMap<>();
        env.put("DJ_BINDIR", judgehostRoot.resolve("bin").toString());
        env.put("DJ_ETCDIR", judgehostRoot.resolve("etc").toString());
        env.put("DJ_JUDGEDIR", judgehostRoot.resolve("judgings").toString());
        env.put("DJ_LIBDIR", judgehostRoot.resolve("lib").toString());
        env.put("DJ_LIBJUDGEDIR", judgehostRoot.resolve("lib/judge").toString());
        env.put("DJ_LOGDIR", judgehostRoot.resolve("log").toString());
        env.put("RUNUSER", runuser);
        env.put("RUNGROUP", "domjudge-run");
        for (Verdict v : Verdict.values()) {
            if (v != Verdict.ERR) {
                env.put("E_" + v.longForm().toUpperCase().replace('-', '_'), String.valueOf(v.exitCode()));
            }
        }
        env.put("E_COMPARE_ERROR", "120");
        env.put("SCRIPTTIMELIMIT", String.valueOf(limits.getScriptTimeLimit()));
        env.put("SCRIPTMEMLIMIT", String.valueOf(limits.getScriptMemoryLimit()));
        env.put("SCRIPTFILELIMIT", String.valueOf(limits.getScriptFilesizeLimit()));
        env.put("FILELIMIT", String.valueOf(limits.getOutputLimit()));
        env.put("PROCLIMIT", String.valueOf(limits.getProcessLimit()));
        env.put("MEMLIMIT", String.valueOf(limits.getMemoryLimit()));
        env.put("CREATE_WRITABLE_TEMP_DIR", "");
        return env;
    }

    /** 문제 메모리 제한(MB)을 MEMLIMIT(kB)로 덮어쓴 환경. */
    public static Map<String, String> forJob(Map<String, String> base, Integer memoryLimitMb) {
        Map<String, String> env = new HashMap<>(base);
        if (memoryLimitMb != null && memoryLimitMb > 0) {
            env.put("MEMLIMIT", String.valueOf(memoryLimitMb * 1024L));
        }
        return env;
    }
}
