"""Summarise eval runs from the app's Room database (pulled with adb run-as).

Usage (WSL or any Python 3):
  python scripts/analyze_eval.py model-export/devdb/tutor.db
"""
import collections
import json
import sqlite3
import statistics
import sys


def pct(xs):
    xs = [x for x in xs if x is not None]
    return f"{100 * sum(xs) / len(xs):.0f}% ({sum(xs)}/{len(xs)})" if xs else "-"


def med(xs):
    xs = [x for x in xs if x is not None]
    return f"{statistics.median(xs):.1f}" if xs else "-"


db = sqlite3.connect(sys.argv[1])
db.row_factory = sqlite3.Row
rows = db.execute(
    "select * from generation_logs where evalRun like 'mcq-eval-%' or evalRun like 'mcq-diag-eval-%' or evalRun like 'roadmap-eval-%' or evalRun like 'template-eval-%' order by id"
).fetchall()

runs = collections.OrderedDict()
for r in rows:
    runs.setdefault(r["evalRun"], []).append(r)

for run, rs in runs.items():
    print(f"\n=== {run}  model={rs[0]['model']}  n={len(rs)}")
    print("valid JSON      :", pct([r["validJson"] for r in rs]))
    print("duplicate       :", pct([r["duplicate"] for r in rs]))
    print("median TTFT ms  :", med([r["ttftMs"] for r in rs]))
    print("median decode/s :", med([r["decodeTokPerSec"] for r in rs]))
    print("median wall ms  :", med([r["wallMs"] for r in rs]))
    print("median gen tok  :", med([r["generatedTokens"] for r in rs]))
    print("peak RSS MB     :", max(r["peakRssKb"] for r in rs) / 1024)
    print("stop reasons    :", dict(collections.Counter(r["stopReason"] for r in rs)))
    print("max thermal     :", max(r["thermalStatus"] for r in rs))
    by_subj = collections.defaultdict(list)
    for r in rs:
        by_subj[r["evalSubject"]].append(r["validJson"])
    print("valid by subject:", {k: pct(v) for k, v in by_subj.items()})
    if rs[0]["task"] == "MCQ":
        by_lvl = collections.defaultdict(list)
        for r in rs:
            by_lvl[r["difficulty"]].append(r["validJson"])
        print("valid by level  :", {k: pct(v) for k, v in sorted(by_lvl.items())})
    print("--- invalid samples")
    for r in [r for r in rs if not r["validJson"]][:4]:
        print(f"  [{r['evalSubject']} g{r['evalGrade']} {r['stopReason']}/{r['guardReason']}] {r['output'][:220]!r}")
    print("--- valid samples")
    for r in [r for r in rs if r["validJson"]][:3]:
        print(f"  [{r['evalSubject']} g{r['evalGrade']} L{r['difficulty']}] {r['output'][:300]!r}")
    # Phase 2b: verifier verdicts (DB schema v3+). TEMPLATE rows are LLM wording for Kotlin-built problems.
    if "verdict" in rs[0].keys():
        verdicts = collections.Counter(r["verdict"] for r in rs if r["verdict"])
        if verdicts:
            print("verdicts        :", dict(verdicts))

# Per-item view of diagnostic runs: each base MCQ row, then the :tags rows logged after it (id order).
for run in [k for k in runs if k.startswith("mcq-diag-eval-") and ":" not in k]:
    items = []
    for r in db.execute("select * from generation_logs where evalRun in (?, ?) order by id", (run, run + ":tags")):
        if r["evalRun"] == run:
            items.append({"base": r, "tags": []})
        elif items:
            items[-1]["tags"].append(r)
    has_verdict = bool(items) and "verdict" in items[0]["base"].keys()
    parsed = [i for i in items if i["base"]["validJson"] or (has_verdict and i["base"]["verdict"] == "DISCARD")]
    reached = [i for i in items if i["tags"]]
    complete = [i for i in reached if any(t["validJson"] for t in i["tags"])]
    calls = collections.Counter(len(i["tags"]) for i in reached)
    print(f"\n=== {run} per item")
    print(f"items           : {len(items)}; parsed before verifier {len(parsed)}; final valid {sum(1 for i in items if i['base']['validJson'])}")
    print(f"tags complete   : {len(complete)}/{len(reached)} items that reached tagging; tag calls per item {dict(sorted(calls.items()))}")
