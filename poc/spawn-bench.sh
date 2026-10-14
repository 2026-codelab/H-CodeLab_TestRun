#!/bin/bash
# 프로세스 생성 비용 측정 (에뮬레이션 영향 확인용): /bin/true 를 N회 실행한 시간
N=${1:-200}
start=$(date +%s%3N)
for _ in $(seq "$N"); do /bin/true; done
end=$(date +%s%3N)
elapsed=$((end - start))
echo "arch=$(uname -m) n=$N total_ms=$elapsed per_spawn_ms=$(awk -v t="$elapsed" -v n="$N" 'BEGIN { printf "%.2f", t / n }')"
