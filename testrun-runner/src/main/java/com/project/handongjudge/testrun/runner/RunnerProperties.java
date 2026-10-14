package com.project.handongjudge.testrun.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "runner")
public class RunnerProperties {

    /** runner 식별자. consumer 이름과 생존 신호 키에 쓴다. */
    private String id;
    /** 동시 처리 슬롯 수. 슬롯 = judgedaemon -n 하나와 같은 단위. */
    private int slots = 2;
    /** 슬롯별 runguard CPU 번호. 슬롯 i는 cpus[i % size]. */
    private List<Integer> cpus = List.of(1, 2);
    /** 실행 사용자 domjudge-run-{runuserBase + i}. 운영 judgedaemon(0~16)과 겹치지 않게 101부터. */
    private int runuserBase = 101;
    /** 슬롯 작업 디렉토리·실행파일·테스트케이스 캐시 위치. runguard CHROOT_PREFIX(judgings) 아래여야 한다. */
    private Path workRoot = Path.of("/opt/domjudge/judgehost/judgings/testrun");
    private Path judgehostRoot = Path.of("/opt/domjudge/judgehost");
    /** 작업 전체 제한 시간. 넘으면 실행 중인 프로세스를 종료하고 error. */
    private int jobTimeoutSec = 30;
    /**
     * 큐 대기 상한. 이보다 오래 기다린 작업은 실행하지 않고 QUEUE_TIMEOUT으로 끝낸다.
     * 학생 화면(SSE)은 이미 연결을 끊었으므로 볼 사람이 없는 코드를 실행하지 않는다.
     */
    private int maxQueueWaitSec = 60;
    /** 이 시간 이상 확인(XACK)되지 않은 작업은 다른 runner가 회수한다. jobTimeout보다 커야 한다. */
    private long reclaimIdleMs = 45_000;
    /** 언어 ID → DOMjudge 컴파일 실행파일 ID. 내려받은 해시를 언어 API의 compile_executable_hash와 대조한다. */
    private Map<String, String> compileExecutables = new LinkedHashMap<>(Map.of(
            "c", "c", "cpp", "cpp", "java", "java_javac_detect", "python3", "py3"));

    private Redis redis = new Redis();
    private Domserver domserver = new Domserver();
    private Internal internal = new Internal();

    @Getter
    @Setter
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
    }

    @Getter
    @Setter
    public static class Domserver {
        /** 예: http://domserver/ */
        private String baseUrl = "http://domserver/";
        private String user = "admin";
        private String password = "";
        /** password 대신 파일에서 읽기 (local-dev: domserver가 생성한 initial_admin_password.secret). */
        private String passwordFile = "";

        public String resolvedPassword() {
            if (passwordFile != null && !passwordFile.isBlank()) {
                try {
                    return Files.readString(Path.of(passwordFile)).trim();
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read domserver password file " + passwordFile, e);
                }
            }
            return password;
        }
    }

    @Getter
    @Setter
    public static class Internal {
        /** backend 내부 API. 예: http://backend:8080/ */
        private String baseUrl = "http://backend:8080/";
        private String token = "";
    }

    public int cpuForSlot(int slot) {
        return cpus.get(slot % cpus.size());
    }

    public String runuserForSlot(int slot) {
        return "domjudge-run-" + (runuserBase + slot);
    }
}
