# H-CodeLab TestRun

H-CodeLab "테스트하기"를 DOMjudge 채점 파이프라인에서 분리하는 경량 실행기입니다.
DOMjudge의 채점 도구(runguard, `compile.sh`, `testcase_run.sh`)와 실행파일은 그대로 쓰고, 작업 배정·결과 보고만 Redis Streams로 바꿉니다.

- 설계: [`../docs/TESTRUN_EXECUTOR_DESIGN.md`](../docs/TESTRUN_EXECUTOR_DESIGN.md) (워크스페이스 루트)
- 결과 저장: 테스트하기 결과(코드, 판정, 컴파일 출력, 테스트케이스별 판정·실행시간·메모리)를 `testrun-service`가 MariaDB의 별도 스키마 `testrun`에 저장해 분석·통계에 씁니다. 학생 출력·diff·테스트케이스 입출력은 저장하지 않습니다.

## 구성

| 모듈 | 역할 | 상태 |
|---|---|---|
| `poc/` | 1단계 PoC: judgedaemon 없이 DOMjudge 스크립트 직접 호출 | ✅ |
| `common-contract/` | 작업·이벤트·결과 요약 메시지 계약, Redis 키 이름 (Java 11 바이트코드, backend도 의존) | ✅ v1 |
| `testrun-runner/` | 워커. `FROM domjudge/judgehost:9.0.0` (amd64) | ✅ 2단계 MVP |
| `testrun-service/` | 작업 접수, 결과 저장 consumer, 미완료 작업 정리, 보존 기간 정리 | ✅ 골격 (통계 API는 3.5단계) |
| `scripts/` | 개발 도구: 작업 1건 실행(`try-job.py`), 게이트 검증(`gate-100.py`), 테스트케이스 캐시 투입 | ✅ |

```
backend ──POST /v1/jobs──▶ testrun-service ──XADD testrun:jobs──▶ testrun-runner (슬롯 N개)
                              │  (DB 행 먼저 저장)                     │ compile.sh / testcase_run.sh
                              │                                        ├─ XADD testrun:events:{jobId} ─▶ backend SSE 중계 (3단계)
                              └◀── testrun:results (consumer group) ───┘ 마무리는 한 트랜잭션(MULTI)
```

## 흐름과 보장

| 단계 | 동작 |
|---|---|
| 접수 (`POST /v1/jobs`) | 토큰 확인 → 코드 64KB 이하 → runner 생존 키 확인 → 미완료 작업 수 확인 → 사용자당 동시 1건 잠금 → **DB 행(QUEUED) 먼저 저장** → 큐 등록 + 소유자 메타를 한 트랜잭션으로 |
| 실행 (runner) | 큐 대기가 60초를 넘은 작업은 실행하지 않고 `QUEUE_TIMEOUT`. 테스트케이스는 `problemHash` 캐시, 없으면 backend 내부 API. 샘플이 있으면 샘플(입출력 공개), 없으면 secret(판정만). 첫 실패에서 중단 |
| 마무리 (runner) | 마지막 이벤트 + 이벤트 TTL + 결과 요약 + 사용자 잠금 해제 + XACK를 **한 MULTI**로 → 화면 결과와 저장 메시지가 함께 생기거나 함께 안 생김 |
| 저장 (service) | `testrun-persist` group. DB 저장 후에만 XACK, 재시작 시 PEL부터 재처리, `job_id` 기준 멱등 upsert |
| 장애 | runner가 죽으면 다른(또는 재기동한) runner가 45초 뒤 회수해 `RUNNER_LOST` error로 끝냄 (학생 코드 재실행 안 함). 결과가 끝내 안 오면 120초 뒤 `LOST` |

판정 정합성: 접수 건수 = DONE + ERROR + LOST.

## 응답 코드 (`POST /v1/jobs`)

| 코드 | 의미 | backend 처리 (3단계) |
|---|---|---|
| 202 `{jobId, enqueuedAt}` | 접수됨 | jobId를 sessionKey로 사용 |
| 401 `UNAUTHORIZED` | 서비스 토큰 불일치 | 설정 오류 |
| 400 `INVALID_REQUEST`, 413 `CODE_TOO_LARGE` | 요청 오류 | 학생에게 오류 표시 |
| 409 `ALREADY_RUNNING` | 같은 사용자의 이전 테스트 진행 중 | "이전 테스트가 끝난 뒤 다시 시도" |
| 503 `NO_RUNNER`, `QUEUE_FULL`, `ENQUEUE_FAILED` | 실행 불가 | **기존 DOMjudge 경로로 폴백** |

## 로컬 실행 (local-dev)

```bash
cd ../local-dev
docker compose exec -T db mariadb -uroot -proot < scripts/testrun-db-init.sql   # 기존 DB 볼륨이면 1회
docker compose up -d --build testrun-service testrun-runner
cd ../H-CodeLab_TestRun
scripts/seed-poc-testcases.sh                       # backend 내부 API(3단계) 전까지 A+B 테스트케이스 투입
python3 scripts/try-job.py poc/cases/sum/ac.c       # 이벤트와 DB 저장 결과 출력
python3 scripts/gate-100.py 100 8                   # 2단계 게이트
```

빌드 (로컬에 Gradle 불필요):

```bash
docker run --rm -e GRADLE_USER_HOME=/cache -v hcodelab-testrun-gradle:/cache -v "$PWD":/work -w /work \
  gradle:8.10.2-jdk17 ./gradlew build
```

## 2단계 게이트 결과 (2026-10-14, local-dev, Apple Silicon + Rosetta, 슬롯 2개)

| 게이트 | 결과 |
|---|---|
| 작업 100건 정확 처리·저장 (AC 50, WA 20, CE 20, TLE 10, 동시 진행 8건) | ✅ 100/100, 누락 0, 오판정 0 (180초) |
| C·C++·Java(public class Solution)·Python | ✅ 모두 AC |
| 처리 중 runner 강제 종료(SIGKILL) → 재기동 | ✅ 50초 뒤 `error` 이벤트 + `ERROR / RUNNER_LOST` 저장 |
| service가 멈춘 동안 나온 결과 6건 → service 재시작 | ✅ 6/6 저장 |
| 접수 방어 (토큰, 64KB, 동시 1건, 필수값, 테스트케이스 없음) | ✅ 401 / 413 / 409 / 400 / `TESTCASES_UNAVAILABLE` |
| 전체 정합성 (317건) | ✅ 미정리 0, CE 42건 = 컴파일 출력 42건 |

## 설계 문서와 달라진 점 (구현하며 확인)

| 항목 | 내용 |
|---|---|
| 실행파일 확보 | `GET /api/v4/executables/{id}` zip을 judgedaemon과 같은 공식으로 해시 계산 → 언어 API의 `compile_executable_hash`와 **불일치하면 그 언어를 쓰지 않음**. zip의 실행 권한 비트는 중앙 디렉토리에만 있어 스트림 방식으로 읽으면 해시가 틀린다 (회귀 테스트 있음) |
| 큐 대기 상한 | 설계에 없던 규칙. 학생 화면이 끊긴 뒤의 작업을 실행하지 않고, LOST 판정 기준을 "대기 상한 60 + 실행 30 + 여유 30초"로 정함 |
| 슬롯 재사용 | runner가 SIGKILL로 죽으면 마운트는 사라져도 chroot 심볼릭 링크가 남아 재기동이 실패했다. 기동 시 `/proc/mounts`로 마운트가 없음을 확인한 뒤에만 슬롯 디렉토리를 새로 만든다 |
| 시간 제한 인자 | judgehost 9.0.0은 `soft:hard,soft:hard` 형식 (설계 문서의 `1:2`가 아님) |
| 신호 종료 | 스크립트가 신호로 종료되면(컨테이너 종료 등) `KILLED_BY_SIGNAL`로 기록해 채점 오류와 구분 |

## 다음 단계

- **3단계 backend 어댑터** (H-CodeLab_BE): `TestRunRouter`(기능 플래그·폴백), 이벤트 dispatcher(SSE 중계), 스트림 소유권 확인, 내부 API `/internal/testrun/problems/{id}/testcases`, `Problem.testcaseHash`
- **3.5단계** 통계 API (`/v1/stats/*`)
- amd64 환경 재측정 (PoC 속도 게이트)
