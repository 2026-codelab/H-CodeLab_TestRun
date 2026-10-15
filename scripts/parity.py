"""판정 일치성 비교: 같은 코드 세트를 runner의 두 실행 방식(direct / script)으로 돌려 결과가 같은지 확인한다.

사용법 (local-dev):
  RUNNER_JUDGE_MODE=script docker compose up -d testrun-runner   # 방식 바꾸기 (local-dev 폴더에서)
  python3 scripts/parity.py run script
  RUNNER_JUDGE_MODE=direct docker compose up -d testrun-runner
  python3 scripts/parity.py run direct
  python3 scripts/parity.py compare script direct

비교 항목: 테스트케이스별 판정, 최종 판정, 학생에게 보이는 컴파일 메시지 첫 줄, 오답 diff 메시지 첫 줄.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

SERVICE = "http://localhost:8090/v1/jobs"
TOKEN = "local-testrun-token"
HERE = os.path.dirname(os.path.abspath(__file__))
CASES = os.path.join(HERE, "..", "poc", "cases", "sum")
OUT = os.path.join(HERE, "..", "build", "parity")

# (소스, 언어, 비교 옵션, 기대 판정)
SUITE = [
    ("ac.c", "c", "", "AC"), ("wa.c", "c", "", "WA"), ("ce.c", "c", "", "CE"), ("tle.c", "c", "", "TLE"),
    ("rte.c", "c", "", "RTE"), ("exit3.c", "c", "", "NO"), ("mle.c", "c", "", "RTE"), ("ole.c", "c", "", "OLE"),
    ("noout.c", "c", "", "NO"), ("space.c", "c", "", "AC"), ("space.c", "c", "space_change_sensitive", "WA"),
    ("ac.cpp", "cpp", "", "AC"), ("ce.cpp", "cpp", "", "CE"),
    ("Solution.java", "java", "", "AC"), ("Main.java", "java", "", "CE"),
    ("ac.py", "python3", "", "AC"), ("wa.py", "python3", "", "WA"),
]


def post(body):
    req = urllib.request.Request(SERVICE, data=json.dumps(body).encode(), method="POST",
                                 headers={"X-Service-Token": TOKEN, "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def events(job_id, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = subprocess.check_output(["docker", "exec", "hcodelab-redis-1", "redis-cli", "--json",
                                       "XRANGE", f"testrun:events:{job_id}", "-", "+"])
        evs = [dict(zip(e[1][::2], e[1][1::2])) for e in json.loads(out or b"[]")]
        if evs and evs[-1]["event"] in ("complete", "ce", "error"):
            return [(e["event"], json.loads(e["data"])) for e in evs]
        time.sleep(0.2)
    return []


def first_line(s):
    return (s or "").strip().splitlines()[0] if (s or "").strip() else ""


def run(label):
    os.makedirs(OUT, exist_ok=True)
    base = int(time.time()) % 100000
    results = []
    for i, (src, lang, args, expected) in enumerate(SUITE):
        t0 = time.time()
        job = post({"userId": 8_000_000 + base * 100 + i, "sectionId": 1, "problemId": 1, "langId": lang,
                    "code": open(os.path.join(CASES, src), encoding="utf-8").read(), "timeLimitSec": 1.0,
                    "memoryLimitMb": 256, "compareArgs": args, "problemHash": "poc-sum"})
        evs = events(job["jobId"])
        ms = int((time.time() - t0) * 1000)
        last_name, last = evs[-1] if evs else ("none", {})
        row = {
            "case": f"{src}{'+' + args if args else ''}",
            "final": last.get("result") if last_name != "error" else "ERROR:" + str(last.get("code")),
            "testcases": [d["result"] for n, d in evs if n == "testcase"],
            "compile_msg": first_line(last.get("output_compile")) if last_name == "ce" else "",
            "diff_msg": first_line(next((d.get("outputDiff") for n, d in evs
                                         if n == "testcase" and d["result"] != "correct"), "")),
            "expected": expected,
            "ms": ms,
        }
        results.append(row)
        print(f"{label:7s} {row['case']:32s} final={row['final']:6s} expected={expected:4s} "
              f"tc={row['testcases']} {ms}ms")
    json.dump(results, open(os.path.join(OUT, f"{label}.json"), "w"), indent=1, ensure_ascii=False)


def compare(a, b):
    ra = json.load(open(os.path.join(OUT, f"{a}.json")))
    rb = json.load(open(os.path.join(OUT, f"{b}.json")))
    mismatches = 0
    for x, y in zip(ra, rb):
        keys = ("final", "testcases", "compile_msg", "diff_msg")
        diff = {k: (x[k], y[k]) for k in keys if x[k] != y[k]}
        ok_expected = y["final"] == y["expected"]
        status = "OK " if not diff and ok_expected else "DIFF"
        if diff or not ok_expected:
            mismatches += 1
        print(f"{status} {x['case']:32s} {a}={x['final']:6s} {b}={y['final']:6s} "
              f"{a}_ms={x['ms']:5d} {b}_ms={y['ms']:5d} {diff if diff else ''}"
              f"{'' if ok_expected else ' (expected ' + y['expected'] + ')'}")
    total_a = sum(r["ms"] for r in ra)
    total_b = sum(r["ms"] for r in rb)
    print(f"\n{len(ra)} cases, mismatches={mismatches}, total {a}={total_a / 1000:.1f}s {b}={total_b / 1000:.1f}s")
    sys.exit(1 if mismatches else 0)


if __name__ == "__main__":
    if sys.argv[1] == "run":
        run(sys.argv[2])
    else:
        compare(sys.argv[2], sys.argv[3])
