#!/bin/bash
# PoC A+B 테스트케이스를 runner 캐시(problemHash=poc-sum)에 넣는다.
# backend 내부 API(3단계) 없이 runner를 검증할 때 쓴다. 캐시는 볼륨이라 runner를 재빌드해도 남는다.
set -euo pipefail

CONTAINER=${CONTAINER:-hcodelab-testrun-runner-1}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEST=/opt/domjudge/judgehost/judgings/testrun/testcases/poc-sum

docker exec "$CONTAINER" mkdir -p "$DEST/sample"
for f in "$ROOT"/poc/cases/sum/data/*.in "$ROOT"/poc/cases/sum/data/*.ans; do
  docker cp "$f" "$CONTAINER:$DEST/sample/$(basename "$f")"
done
docker exec "$CONTAINER" sh -c "touch $DEST/.complete && chown -R domjudge: $DEST"
echo "seeded poc-sum: $(docker exec "$CONTAINER" ls "$DEST/sample" | tr '\n' ' ')"
