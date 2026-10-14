# 1단계 PoC — DOMjudge 스크립트 직접 호출

설계 문서 7장 1단계. judgedaemon 없이 `compile.sh`와 `testcase_run.sh`를 직접 호출해 판정이 맞는지, 몇 초 걸리는지 잰다.

## 파일

| 파일 | 내용 |
|---|---|
| `run-poc.sh` | judgehost 컨테이너 안에서 domjudge 사용자로 실행. 슬롯 디렉토리에서 chroot를 1회 마운트하고, 소스마다 컴파일 → 테스트케이스 실행(첫 실패에서 중단) → 시간 기록 |
| `run-in-local-dev.sh` | 호스트에서 실행. 실행 사용자 `domjudge-run-101` 생성, PoC 복사, 반복 실행 |
| `spawn-bench.sh` | 프로세스 생성 비용 측정 (에뮬레이션 영향 확인) |
| `cases/sum/` | A+B 문제. `ac.c`(정답), `wa.c`(오답), `ce.c`(컴파일 에러), `tle.c`(무한 루프), `data/1~3.in/.ans` |

## 확인한 호출 규약 (judgehost 9.0.0 컨테이너 기준)

설계 문서의 예시와 다른 부분이 있어 실제 `judgedaemon.main.php`와 대조했다.

| 항목 | 값 | 근거 |
|---|---|---|
| 컴파일 | `compile.sh -n <cpu> <compile run> <workdir> main.c` | `judgedaemon.main.php:1248` |
| 실행 | `testcase_run.sh -n <cpu> <in> <ans> <tl> <passdir> <run> <compare> <compare-args>` | `:1480` |
| 시간 제한 형식 | **`soft:hard,soft:hard`** (CPU, wall). 설계 문서의 `1:2`가 아님 | `:1413-1478` |
| hard 시간 | `soft + overshoot("1s\|10%")` = max(1초, 10%) → 1초 문제면 2초 | `:1413` |
| 실행파일 경로 | `judgings/<host>/endpoint-default/executable/<type>/<id>/<hash>/build/run` | `:350` |
| chroot 루트 | 작업 디렉토리 자체 (`compile.sh`는 `-r $PWD/..`, `testcase_run.sh`는 `-r $PWD/../..`) | `compile.sh:152`, `testcase_run.sh:209` |
| 한도 | domserver `GET /api/v4/config`: script 30s / 2GB / 2.5GB, output 8192kB, process 64 | local-dev 조회값 |
| C `time_factor` | 1.0, 컴파일 해시 `ac6b2c3b…` | `GET /api/v4/languages` |

## 결과 (2026-10-14, Apple Silicon Mac, amd64 이미지 Rosetta 에뮬레이션)

### 판정 정확성 — 게이트 통과

| 코드 | 기대 | 결과 |
|---|---|---|
| `ac.c` | 모든 테스트케이스 종료 코드 0 | ✅ AC 3/3 |
| `wa.c` | 종료 코드 105 | ✅ WA (1번에서 중단) |
| `ce.c` | `compile.sh` 실패 + 컴파일러 메시지 | ✅ CE, `compile.out`에 `error: expected '=', ',', ';'` |
| `tle.c` | 종료 코드 102 | ✅ TLE (CPU 1.99초, hard 2초에서 종료) |

### 소요 시간 (3회 반복, 안정적)

| 단계 | 시간 |
|---|---|
| chroot 마운트 (슬롯당 1회) | 0.50초 |
| 컴파일 | 0.80~0.86초 |
| 테스트케이스 1개 실행·비교 | 0.96~1.09초 (프로그램 CPU 시간은 0.005초) |

### 기존 DOMjudge 경로와 비교 (같은 Mac, 같은 코드·테스트케이스)

기존 경로는 local-dev DOMjudge 데모 대회에 같은 문제(`pocsum`)를 올려 제출하고, judgehost 로그상 채점 완료까지 쟀다. 백엔드의 결과 폴링(0.5→3초)은 포함하지 않았으므로 실제 서비스에서는 차이가 더 크다.

| 코드 | 기존 DOMjudge 경로 | PoC | 비율 |
|---|---|---|---|
| AC (3케이스) | 7.8초 (judgehost 유휴 시 9.0초, 큐 대기 3.1초 포함) | 4.0초 | 51% |
| WA (1케이스에서 중단) | 4.8초 | 1.9초 | 40% |
| CE | 2.6초 | 0.6초 | 24% |

기존 경로에서 사라진 구간: 큐 대기(유휴 시 3.1초), 작업마다 chroot 마운트(0.6초), 버전 확인과 파일 수신(약 0.7초), 테스트케이스당 HTTP 보고(약 0.3초).

### 속도 게이트 — 로컬에서는 판단 불가, amd64 재측정 필요

설계 문서 게이트는 "컴파일 + 샘플 3개 ≤ 1초"인데 로컬은 4.0초다. 하지만 원인이 에뮬레이션으로 보인다.

| 환경 | 프로세스 1개 생성 비용 (`spawn-bench.sh`) |
|---|---|
| amd64 judgehost 컨테이너 (Rosetta) | 8.07ms |
| arm64 네이티브 컨테이너 | 0.35ms |

`testcase_run.sh`는 테스트케이스마다 grep·sed·sudo·runguard 등 프로세스를 많이 띄우는 셸 스크립트다. 1.0초 ÷ 8ms로 역산하면 약 120회로 추정되며, 로컬의 테스트케이스당 1초는 대부분 에뮬레이션 비용으로 보인다. **운영과 같은 amd64 환경(스테이징 또는 운영 서버의 유휴 CPU)에서 같은 스크립트로 다시 재야 게이트를 판단할 수 있다.**

amd64에서도 테스트케이스당 0.25초를 넘으면, 설계 문서 2-6의 최적화(`execdir` 재사용, `program.meta`를 워커에서 직접 파싱, 결과 판정 부분만 경량 스크립트로)를 먼저 적용한다.

## 다음 단계

1. amd64 환경에서 `run-poc.sh` 재측정 (운영 서버라면 judgehost가 쓰지 않는 CPU를 `CPU=`로 지정)
2. 2단계 워커 MVP: 이 스크립트의 흐름을 Java 워커로 옮기고, 실행파일은 domserver `GET /api/v4/executables/{id}`에서 받아 `build_executable.sh`로 빌드 (지금은 judgehost 캐시를 빌려 씀). C++·Java·Python은 아직 미검증
