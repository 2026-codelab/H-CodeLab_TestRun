"""local-dev에서 테스트하기 작업 1건을 넣고 이벤트·저장 결과를 확인하는 개발 도구.

사용법:
  python3 scripts/try-job.py <source-file> [--lang c] [--hash poc-sum] [--problem 1] [--user 1001]

순서: POST /v1/jobs → Redis testrun:events:{jobId} 를 마지막 이벤트(complete/ce/error)까지 읽음
      → GET /v1/jobs/{jobId} 로 testrun DB에 저장된 행 확인
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

SERVICE = "http://localhost:8090"
TOKEN = "local-testrun-token"
REDIS_CONTAINER = "hcodelab-redis-1"
TERMINAL = {"complete", "ce", "error"}


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(SERVICE + path, data=data, method=method,
                                 headers={"X-Service-Token": TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def xrange(key, start):
    out = subprocess.check_output(["docker", "exec", REDIS_CONTAINER, "redis-cli", "--json", "XRANGE", key, start, "+"])
    return json.loads(out or b"[]")


def wait_events(job_id, timeout=60):
    key = f"testrun:events:{job_id}"
    seen, last, deadline = [], "-", time.time() + timeout
    while time.time() < deadline:
        for entry_id, fields in xrange(key, last):
            if entry_id == last:
                continue
            last = entry_id
            f = dict(zip(fields[::2], fields[1::2]))
            seen.append((f["event"], json.loads(f["data"])))
            if f["event"] in TERMINAL:
                return seen
        time.sleep(0.1)
    return seen


def main():
    p = argparse.ArgumentParser()
    p.add_argument("source")
    p.add_argument("--lang", default="c")
    p.add_argument("--hash", default="poc-sum")
    p.add_argument("--problem", type=int, default=1)
    p.add_argument("--user", type=int, default=1001)
    p.add_argument("--quiet", action="store_true", help="한 줄 요약만 출력")
    a = p.parse_args()

    code = open(a.source, encoding="utf-8").read()
    t0 = time.time()
    status, body = call("POST", "/v1/jobs", {
        "userId": a.user, "sectionId": 1, "problemId": a.problem, "contextType": "ASSIGNMENT", "contextId": 1,
        "langId": a.lang, "code": code, "timeLimitSec": 1.0, "memoryLimitMb": 256, "compareArgs": "",
        "problemHash": a.hash})
    if status != 202:
        print(f"POST /v1/jobs -> {status} {body}")
        sys.exit(1)
    job_id = body["jobId"]
    events = wait_events(job_id)
    elapsed = int((time.time() - t0) * 1000)
    time.sleep(0.5)  # 저장 consumer가 처리할 시간
    _, row = call("GET", f"/v1/jobs/{job_id}")

    terminal = events[-1] if events else ("(none)", {})
    if a.quiet:
        print(f"{a.source} job={job_id} event={terminal[0]} result={terminal[1].get('result', terminal[1].get('code'))} "
              f"db_status={row.get('status')} db_verdict={row.get('verdict')} e2e_ms={elapsed}")
        return
    print(f"job {job_id}  e2e {elapsed}ms")
    for name, data in events:
        shown = {k: (v[:60] + "…" if isinstance(v, str) and len(v) > 60 else v) for k, v in data.items()}
        print(f"  {name:9s} {shown}")
    print("  db:", {k: row.get(k) for k in ("status", "verdict", "error_code", "testcase_source", "tc_total",
                                             "tc_passed", "first_fail_rank", "max_runtime_ms", "max_memory_kb")})


if __name__ == "__main__":
    main()
