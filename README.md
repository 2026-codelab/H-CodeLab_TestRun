# H-CodeLab TestRun

H-CodeLab "테스트하기"를 DOMjudge 채점 파이프라인에서 분리하는 경량 실행기 저장소입니다.
DOMjudge의 채점 도구(runguard, `compile.sh`, `testcase_run.sh`)는 그대로 쓰고, 작업 배정·결과 보고만 Redis Streams로 바꿉니다.

- 설계: [`docs/TESTRUN_EXECUTOR_DESIGN.md`](../docs/TESTRUN_EXECUTOR_DESIGN.md) (워크스페이스 루트)
- 결과 저장: 테스트하기 결과(코드, 판정, 컴파일 출력, 테스트케이스별 판정·실행시간·메모리)는 `testrun-service`가 기존 MariaDB 인스턴스의 별도 스키마 `testrun`에 저장해 분석·통계에 씁니다.

## 구성 (예정)

| 모듈 | 역할 | 상태 |
|---|---|---|
| `poc/` | 1단계 PoC: judgedaemon 없이 DOMjudge 스크립트 직접 호출 | ✅ 완료 |
| `common-contract/` | 작업·이벤트·결과 요약 메시지 계약 (Java 11 호환) | 예정 (2단계) |
| `testrun-service/` | 작업 접수, 결과 저장, 통계 API | 예정 (2~3.5단계) |
| `testrun-runner/` | DOMjudge 도구로 컴파일·실행하는 워커 (`FROM domjudge/judgehost:9.0.0`) | 예정 (2단계) |

## PoC 실행

local-dev 스택(`../local-dev`)이 떠 있고 judgehost가 C 제출을 한 번 이상 채점한 상태에서:

```bash
poc/run-in-local-dev.sh 3     # 3회 반복
```

결과와 해석은 [`poc/README.md`](poc/README.md)에 있습니다.
