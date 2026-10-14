#!/bin/bash
# local-dev judgehost 컨테이너에 PoC를 복사하고 실행한다.
# 사용법: poc/run-in-local-dev.sh [반복 횟수]
set -euo pipefail

CONTAINER=${CONTAINER:-hcodelab-judgehost-1}
REPEAT=${1:-1}
POC_DIR=$(cd "$(dirname "$0")" && pwd)
DEST=/opt/domjudge/judgehost/judgings/testrun-poc

# 실행 사용자 준비 (judgehost start.sh와 같은 방식, 운영 judgedaemon 사용자와 겹치지 않게 101번)
docker exec "$CONTAINER" bash -c '
  id domjudge-run-101 >/dev/null 2>&1 || useradd -u 62961 -N -d /nonexistent -g nogroup -s /bin/false domjudge-run-101
  rm -rf '"$DEST"' && mkdir -p '"$DEST"' && chown domjudge: '"$DEST"'
'
docker cp "$POC_DIR"/. "$CONTAINER":"$DEST"/
docker exec "$CONTAINER" chown -R domjudge: "$DEST"

for i in $(seq "$REPEAT"); do
  echo "=== run $i/$REPEAT"
  docker exec -u domjudge "$CONTAINER" bash "$DEST"/run-poc.sh "$DEST"/cases/sum
done
