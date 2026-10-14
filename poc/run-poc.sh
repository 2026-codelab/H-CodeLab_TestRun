#!/bin/bash
# 테스트하기 경량 실행기 1단계 PoC
# judgedaemon 없이 DOMjudge compile.sh / testcase_run.sh를 직접 호출해 판정 결과와 소요 시간을 잰다.
# judgehost 컨테이너 안에서 domjudge 사용자로 실행한다 (run-in-local-dev.sh가 호출).
#
# 사용법: run-poc.sh <case_dir> [source.c ...]
#   <case_dir>  data/<n>.in, data/<n>.ans 와 소스 파일이 있는 디렉토리
#   소스를 생략하면 <case_dir>/*.c 전부 실행
#
# 환경변수 (judgedaemon.main.php:570-582, 1335-1344와 같은 값)
#   CPU          runguard CPU 번호 (-n). 기본 1 (judgedaemon -n 0과 겹치지 않게)
#   SLOT         슬롯 번호. 작업 디렉토리 judgings/testrun/slot-<SLOT>
#   RUNUSER      실행 사용자. 기본 domjudge-run-101
#   TIMELIMIT    문제 시간 제한(초). 기본 1
#   MEMLIMIT     메모리 한도(kB). 기본 262144
set -uo pipefail

CASE_DIR=${1:?case_dir 필요}
shift
SOURCES=("$@")
if [ ${#SOURCES[@]} -eq 0 ]; then
  SOURCES=("$CASE_DIR"/*.c)
fi

CPU=${CPU:-1}
SLOT=${SLOT:-0}
TIMELIMIT=${TIMELIMIT:-1}

export DJ_BINDIR=/opt/domjudge/judgehost/bin
export DJ_ETCDIR=/opt/domjudge/judgehost/etc
export DJ_JUDGEDIR=/opt/domjudge/judgehost/judgings
export DJ_LIBDIR=/opt/domjudge/judgehost/lib
export DJ_LIBJUDGEDIR=/opt/domjudge/judgehost/lib/judge
export DJ_LOGDIR=/opt/domjudge/judgehost/log
export RUNUSER=${RUNUSER:-domjudge-run-101}
export RUNGROUP=domjudge-run

# 종료 코드 → 결과 (judgehost-static.php:24-34)
export E_CORRECT=0 E_COMPILER_ERROR=101 E_TIMELIMIT=102 E_RUN_ERROR=103 \
       E_NO_OUTPUT=104 E_WRONG_ANSWER=105 E_MEMORY_LIMIT=107 E_OUTPUT_LIMIT=108 \
       E_COMPARE_ERROR=120

# 한도 (domserver GET /api/v4/config 값, 메모리는 문제 기본값 256MB)
export SCRIPTTIMELIMIT=30 SCRIPTMEMLIMIT=2097152 SCRIPTFILELIMIT=2621440
export MEMLIMIT=${MEMLIMIT:-262144} FILELIMIT=8192 PROCLIMIT=64
export CREATE_WRITABLE_TEMP_DIR=

# 이전 채점으로 캐시된 실행파일 (C 컴파일 스크립트 해시는 GET /api/v4/languages의 compile_executable_hash)
EXE=$DJ_JUDGEDIR/judgehost-0-0/endpoint-default/executable
pick() { ls -d "$EXE"/"$1"/*/*/build/run 2>/dev/null | head -1; }
COMPILE_RUN=${COMPILE_RUN:-$(ls -d "$EXE"/compile/*/"${C_COMPILE_HASH:-ac6b2c3be82211958c91cde21f27fd26}"/build/run 2>/dev/null | head -1)}
RUN_RUN=${RUN_RUN:-$(pick run)}
COMPARE_RUN=${COMPARE_RUN:-$(pick compare)}
for v in COMPILE_RUN RUN_RUN COMPARE_RUN; do
  if [ ! -x "${!v}" ]; then
    echo "실행파일 없음: $v (judgehost가 C 제출을 한 번 채점해야 캐시가 생김)" >&2
    exit 2
  fi
done

# 시간 제한: soft = TIMELIMIT × time_factor(c=1.0), hard = soft + overshoot("1s|10%" → max(1s, 10%))
HARD=$(awk -v t="$TIMELIMIT" 'BEGIN { o = t * 0.1; if (o < 1) o = 1; print t + o }')
TL_STR="$TIMELIMIT:$HARD,$TIMELIMIT:$HARD"

now_ms() { date +%s%3N; }

verdict_of() {
  case "$1" in
    0) echo correct ;; 101) echo compiler-error ;; 102) echo timelimit ;;
    103) echo run-error ;; 104) echo no-output ;; 105) echo wrong-answer ;;
    107) echo memory-limit ;; 108) echo output-limit ;; 120) echo compare-error ;;
    *) echo "internal-error($1)" ;;
  esac
}

short_of() {
  case "$1" in
    correct) echo AC ;; compiler-error) echo CE ;; timelimit) echo TLE ;;
    run-error) echo RTE ;; no-output) echo NO ;; wrong-answer) echo WA ;;
    memory-limit) echo MLE ;; output-limit) echo OLE ;; *) echo ERR ;;
  esac
}

meta() { grep -E "^$2:" "$1" 2>/dev/null | head -1 | cut -d' ' -f2; }

W=$DJ_JUDGEDIR/testrun/slot-$SLOT
mkdir -p "$W"
cd "$W" || exit 1

# 슬롯당 1회 chroot 마운트 (작업마다 하지 않음)
t0=$(now_ms)
"$DJ_LIBJUDGEDIR"/chroot-startstop.sh start || { echo "chroot start 실패" >&2; exit 3; }
CHROOT_MS=$(( $(now_ms) - t0 ))
trap 'cd "$W" && "$DJ_LIBJUDGEDIR"/chroot-startstop.sh stop >/dev/null 2>&1' EXIT

TESTCASES=$(ls "$CASE_DIR"/data/*.in | sort -V)

echo "chroot start: ${CHROOT_MS}ms (슬롯당 1회)  cpu=$CPU runuser=$RUNUSER timelimit=$TL_STR"
printf '%-8s %-8s %10s %s %10s\n' source verdict compile_ms "testcases(rank:verdict:ms:cpu_s:mem_kb)" total_ms

for src in "${SOURCES[@]}"; do
  name=$(basename "$src")
  # 이전 작업 흔적 정리 (chroot 마운트 디렉토리는 유지)
  rm -rf "$W"/compile "$W"/testcase* "$W"/compile.out "$W"/compile.meta
  mkdir -p "$W"/compile
  cp "$src" "$W"/compile/main.c

  job_start=$(now_ms)
  "$DJ_LIBJUDGEDIR"/compile.sh -n "$CPU" "$COMPILE_RUN" "$W" main.c >"$W"/compile.stdout 2>&1
  rc=$?
  compile_ms=$(( $(now_ms) - job_start ))

  results=""
  final=AC
  if [ $rc -ne 0 ]; then
    final=$(short_of "$(verdict_of "$rc")")
    [ $rc -eq 1 ] && final=CE
    results="(compile.out: $(head -c 120 "$W"/compile.out 2>/dev/null | tr '\n' ' '))"
  else
    rank=0
    for in_file in $TESTCASES; do
      rank=$((rank + 1))
      ans_file=${in_file%.in}.ans
      P=$W/testcase$(printf '%05d' "$rank")/1
      mkdir -p "$P"/execdir
      cp -PRl "$W"/compile/* "$P"/execdir/

      tc_start=$(now_ms)
      "$DJ_LIBJUDGEDIR"/testcase_run.sh -n "$CPU" "$in_file" "$ans_file" "$TL_STR" "$P" \
        "$RUN_RUN" "$COMPARE_RUN" "" >"$P"/../run.stdout 2>&1
      trc=$?
      tc_ms=$(( $(now_ms) - tc_start ))

      v=$(verdict_of "$trc")
      results="$results $rank:$(short_of "$v"):${tc_ms}:$(meta "$P"/program.meta cpu-time):$(( $(meta "$P"/program.meta memory-bytes) / 1024 ))"
      if [ "$v" != correct ]; then
        final=$(short_of "$v")
        break   # 첫 실패에서 중단 (EVAL_LAZY)
      fi
    done
  fi
  total_ms=$(( $(now_ms) - job_start ))
  printf '%-8s %-8s %10s %s %10s\n' "$name" "$final" "$compile_ms" "$results" "$total_ms"
done
