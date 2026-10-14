#!/bin/bash
# testrun-runner 시작 스크립트. judgehost 이미지의 /scripts/start.sh에서 judgedaemon 실행 부분만 바꿨다.
# root로 cgroup·실행 사용자를 준비한 뒤 domjudge 사용자로 워커를 실행한다.
set -euo pipefail

RUNNER_SLOTS=${RUNNER_SLOTS:-2}
RUNNER_RUNUSER_BASE=${RUNNER_RUNUSER_BASE:-101}
# 실행 사용자 UID. judgehost(62860+)·다른 runner와 겹치면 runguard가 서로의 프로세스를 종료할 수 있어 범위를 나눈다.
RUNNER_UID_BASE=${RUNNER_UID_BASE:-63000}
WORK_ROOT=/opt/domjudge/judgehost/judgings/testrun

echo "[..] Setting timezone"
ln -snf "/usr/share/zoneinfo/${CONTAINER_TIMEZONE:-Asia/Seoul}" /etc/localtime

echo "[..] Setting up cgroups"
/opt/domjudge/judgehost/bin/create_cgroups

echo "[..] Copying resolv.conf to chroot"
cp /etc/resolv.conf /chroot/domjudge/etc/resolv.conf

echo "[..] Creating run users"
getent group domjudge-run >/dev/null || groupadd -g 62860 domjudge-run
for i in $(seq 0 $((RUNNER_SLOTS - 1))); do
  user="domjudge-run-$((RUNNER_RUNUSER_BASE + i))"
  if ! id "$user" >/dev/null 2>&1; then
    useradd -u $((RUNNER_UID_BASE + RUNNER_RUNUSER_BASE + i)) -N -d /nonexistent -g nogroup -s /bin/false "$user" 2>/dev/null
  fi
done

mkdir -p "$WORK_ROOT" "$WORK_ROOT/testcases"
chown domjudge: "$WORK_ROOT" "$WORK_ROOT/testcases"   # testcases는 볼륨으로 마운트될 수 있다 (재기동해도 캐시 유지)

# 비밀번호 파일은 root만 읽을 수 있는 경우가 많다 (domserver etc). root일 때 읽어 환경변수로 넘긴다.
if [ -n "${DOMSERVER_PASSWORD_FILE:-}" ] && [ -r "$DOMSERVER_PASSWORD_FILE" ]; then
  DOMSERVER_PASSWORD=$(tr -d '\r\n' < "$DOMSERVER_PASSWORD_FILE")
  export DOMSERVER_PASSWORD
  unset DOMSERVER_PASSWORD_FILE
fi

echo "[ok] starting testrun-runner (slots=$RUNNER_SLOTS)"
exec sudo -E -u domjudge java ${JAVA_OPTS:--Xmx256m} -jar /opt/testrun/testrun-runner.jar
