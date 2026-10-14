#!/bin/bash
# 테스트케이스 1개 실행 시간이 어디에 쓰이는지 분해한다. runner 컨테이너 안에서 domjudge 사용자로 실행.
#   docker exec -u root <runner> useradd ... domjudge-run-150   (run-overhead-bench.sh가 준비)
#   docker exec -u domjudge <runner> bash overhead-bench.sh <case_dir>
# 프로세스 수는 PID 카운터(ns_last_pid) 증가량으로 센다 (strace가 없는 이미지라서). runner가 놀고 있을 때 잰다.
set -uo pipefail

CASE_DIR=${1:?case_dir}
CPU=${CPU:-3}
export RUNUSER=domjudge-run-150 RUNGROUP=domjudge-run
export DJ_BINDIR=/opt/domjudge/judgehost/bin DJ_ETCDIR=/opt/domjudge/judgehost/etc \
       DJ_JUDGEDIR=/opt/domjudge/judgehost/judgings DJ_LIBDIR=/opt/domjudge/judgehost/lib \
       DJ_LIBJUDGEDIR=/opt/domjudge/judgehost/lib/judge DJ_LOGDIR=/opt/domjudge/judgehost/log
export E_CORRECT=0 E_COMPILER_ERROR=101 E_TIMELIMIT=102 E_RUN_ERROR=103 E_NO_OUTPUT=104 \
       E_WRONG_ANSWER=105 E_MEMORY_LIMIT=107 E_OUTPUT_LIMIT=108 E_COMPARE_ERROR=120
export SCRIPTTIMELIMIT=30 SCRIPTMEMLIMIT=2097152 SCRIPTFILELIMIT=2621440 \
       MEMLIMIT=262144 FILELIMIT=8192 PROCLIMIT=64 CREATE_WRITABLE_TEMP_DIR=

EXE=/opt/domjudge/judgehost/judgings/testrun/executable
COMPILE_RUN=$(ls -d $EXE/compile/c/*/build/run | head -1)
RUN_RUN=$(ls -d $EXE/run/run/*/build/run | head -1)
COMPARE_RUN=$(ls -d $EXE/compare/compare/*/build/run | head -1)

ms() { date +%s%3N; }
pid() { cat /proc/sys/kernel/ns_last_pid; }
measure() {   # measure <label> <repeat> <command...>
  local label=$1 n=$2; shift 2
  local p0 t0 t1 p1
  p0=$(pid); t0=$(ms)
  for _ in $(seq "$n"); do "$@" >/dev/null 2>&1; done
  t1=$(ms); p1=$(pid)
  printf '%-34s %8.1fms  processes≈%s\n' "$label" "$(awk -v a=$((t1 - t0)) -v n=$n 'BEGIN{print a/n}')" \
    "$(( (p1 - p0 - n * 2) / n ))"   # seq·서브셸 몫을 대략 뺌
}

W=$DJ_JUDGEDIR/testrun/bench
rm -rf "$W" && mkdir -p "$W/compile" && cd "$W" || exit 1
$DJ_LIBJUDGEDIR/chroot-startstop.sh start >/dev/null 2>&1 || { echo "chroot start failed"; exit 1; }
trap 'cd "$W" && $DJ_LIBJUDGEDIR/chroot-startstop.sh stop >/dev/null 2>&1' EXIT
cp "$CASE_DIR/ac.c" "$W/compile/main.c"
echo "arch=$(uname -m) cpu=$CPU"
echo "--- 기본 비용"
measure "프로세스 1개 (/bin/true)"            50 /bin/true
measure "sudo 1회 (sudo -n true)"            10 sudo -n /bin/true
measure "runguard 1회 (빈 프로그램)"         10 sudo -n "$DJ_BINDIR/runguard" -u "$RUNUSER" -g "$RUNGROUP" -P "$CPU" -- /bin/true
echo "--- 컴파일"
measure "gcc 직접 (샌드박스 없이)"           3 gcc -x c -O2 -std=gnu11 -o /tmp/bench-prog "$W/compile/main.c" -lm
measure "compile.sh (공식 컴파일 스크립트)"  3 "$DJ_LIBJUDGEDIR/compile.sh" -n "$CPU" "$COMPILE_RUN" "$W" main.c
echo "--- 테스트케이스 1개"
measure "프로그램 직접 실행 (샌드박스 없이)" 20 sh -c "/tmp/bench-prog < $CASE_DIR/data/1.in"
run_tc() {
  rm -rf "$W/testcase00001"; mkdir -p "$W/testcase00001/1/execdir"
  cp -PRl "$W"/compile/* "$W/testcase00001/1/execdir/"
  "$DJ_LIBJUDGEDIR/testcase_run.sh" -n "$CPU" "$CASE_DIR/data/1.in" "$CASE_DIR/data/1.ans" "1:2,1:2" \
    "$W/testcase00001/1" "$RUN_RUN" "$COMPARE_RUN" ""
}
measure "testcase_run.sh (공식 실행·비교)"    5 run_tc
echo "--- runguard가 잰 실제 실행 시간 (마지막 testcase)"
grep -E "^(wall-time|cpu-time)" "$W/testcase00001/1/program.meta" | sed 's/^/  program: /'
grep -E "^(wall-time|cpu-time)" "$W/testcase00001/1/compare.meta" | sed 's/^/  compare: /'
