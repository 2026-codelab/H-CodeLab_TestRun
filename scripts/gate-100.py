"""2단계 게이트: 작업 N건(기본 100)이 모두 기대한 판정으로 처리·저장되는지 확인한다.

사용법: python3 scripts/gate-100.py [N] [WINDOW]
동시에 진행 중인 작업을 WINDOW(기본 8)건으로 제한한다. 처리 용량을 넘겨 쏟아부으면
큐 대기 상한(60초)을 넘긴 작업이 QUEUE_TIMEOUT으로 끝나므로, 정확성 검증과 과부하 검증을 분리한다.
판정 기대값: ac.c=AC, wa.c=WA, ce.c=CE, tle.c=TLE (A+B 문제, 캐시 해시 poc-sum)
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

SERVICE = "http://localhost:8090/v1/jobs"
TOKEN = "local-testrun-token"
HERE = os.path.dirname(os.path.abspath(__file__))
CASES = os.path.join(HERE, "..", "poc", "cases", "sum")
# 시간 초과는 건당 3초 이상 걸리므로 비중을 낮춘다.
MIX = [("ac.c", "AC")] * 5 + [("wa.c", "WA")] * 2 + [("ce.c", "CE")] * 2 + [("tle.c", "TLE")]


def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"X-Service-Token": TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
    window = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    codes = {name: open(os.path.join(CASES, name), encoding="utf-8").read() for name, _ in MIX}
    run_id = int(time.time()) % 100000
    jobs = []
    pending = {}
    results = {}
    t0 = time.time()
    deadline = t0 + 60 + n * 10
    i = 0
    while (i < n or pending) and time.time() < deadline:
        while i < n and len(pending) < window:
            name, expected = MIX[i % len(MIX)]
            status, body = call("POST", SERVICE, {
                "userId": run_id * 1000 + i, "sectionId": 1, "problemId": 1, "langId": "c", "code": codes[name],
                "timeLimitSec": 1.0, "memoryLimitMb": 256, "problemHash": "poc-sum"})
            if status != 202:
                print(f"submit {i} failed: {status} {body}")
                sys.exit(1)
            job = (body["jobId"], name, expected)
            jobs.append(job)
            pending[job[0]] = job
            i += 1
        for job_id in list(pending):
            _, row = call("GET", f"{SERVICE}/{job_id}")
            if row.get("status") in ("DONE", "ERROR", "LOST"):
                results[job_id] = row
                del pending[job_id]
        if pending:
            time.sleep(0.5)
    elapsed = time.time() - t0
    print(f"submitted {len(jobs)}/{n} jobs (window {window})")

    wrong = [(j, n_, exp, results[j].get("status"), results[j].get("verdict"))
             for j, n_, exp in jobs if j in results and (results[j].get("status") != "DONE" or results[j].get("verdict") != exp)]
    by_verdict = {}
    for row in results.values():
        by_verdict[row.get("verdict")] = by_verdict.get(row.get("verdict"), 0) + 1
    print(f"finished {len(results)}/{n} in {elapsed:.1f}s, verdicts={by_verdict}")
    print(f"missing (not persisted): {len(pending)}")
    print(f"wrong verdict/status: {len(wrong)}")
    for w in wrong[:10]:
        print("  ", w)
    ok = not pending and not wrong
    print("GATE", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
