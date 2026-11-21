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
    "select * from generation_logs where evalRun like 'mcq-eval-%' or evalRun like 'mcq-diag-eval-%' or evalRun like 'roadmap-eval-%' or evalRun like 'template-eval-%' or evalRun like 'mcq-ab-eval-%' order by id"
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
    # 2-fix-3: failure reasons (DB schema v4+), overall and for hard questions only.
    if "failReason" in rs[0].keys():
        bad = [r for r in rs if not r["validJson"] or r["duplicate"]]
        cats = collections.Counter((r["failReason"] or "(none logged)").split(":")[0] for r in bad)
        print("fail categories :", dict(cats))
        print("fail reasons    :", dict(collections.Counter(r["failReason"] or "(none logged)" for r in bad)))
        hard = [r for r in bad if r["difficulty"] == 3]
        if hard:
            print("hard fail cats  :", dict(collections.Counter((r["failReason"] or "(none logged)").split(":")[0] for r in hard)),
                  f"(hard usable {sum(1 for r in rs if r['difficulty'] == 3 and r['validJson'] and not r['duplicate'])}/{sum(1 for r in rs if r['difficulty'] == 3)})")

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
    # 2-fix-4: time per fully tagged question = the MCQ call (+ answer follow-up) + every tag call for it.
    answers = {r["id"]: r for r in db.execute("select * from generation_logs where evalRun=?", (run + ":answer",))}
    def total_ms(i):
        base = i["base"]
        prev = answers.get(base["id"] - 1)  # the follow-up is logged just before its main row
        return base["wallMs"] + (prev["wallMs"] if prev else 0) + sum(t["wallMs"] for t in i["tags"])
    if complete:
        print(f"tag time / fully tagged q : median {statistics.median(sum(t['wallMs'] for t in i['tags']) for i in complete) / 1000:.1f} s"
              f"; total incl. MCQ median {statistics.median(total_ms(i) for i in complete) / 1000:.1f} s"
              f" (min {min(total_ms(i) for i in complete) / 1000:.1f}, max {max(total_ms(i) for i in complete) / 1000:.1f})")
        tasks = collections.Counter(t["task"] for i in reached for t in i["tags"])
        print(f"tag calls by task: {dict(tasks)}; pipe-only complete: "
              f"{sum(1 for i in reached if i['tags'][0]['task'] == 'MCQ_TAGS_PIPE' and i['tags'][0]['validJson'])}/{len(reached)}")

# 3d prefill A/B: pairs of greedy generations, cold (reset + full prompt) vs warm (system prompt prefilled first).
for run in sorted({r[0].rsplit(":", 1)[0] for r in db.execute("select evalRun from generation_logs where evalRun like 'prefill-ab-eval-%:cold'")}):
    cold = db.execute("select * from generation_logs where evalRun=? order by id", (run + ":cold",)).fetchall()
    warm = db.execute("select * from generation_logs where evalRun=? order by id", (run + ":warm",)).fetchall()
    pre = [int(r["output"]) for r in db.execute("select output from generation_logs where evalRun=?", (run + ":prewarm",)) if r["output"].strip().lstrip("-").isdigit()]
    n = min(len(cold), len(warm))
    same = sum(1 for a, b in zip(cold, warm) if a["output"] == b["output"])
    print(f"\n=== {run} prefill A/B: {n} pairs")
    print(f"identical output: {same}/{n}")
    print(f"TTFT median cold {med([r['ttftMs'] for r in cold[:n]])} ms, warm {med([r['ttftMs'] for r in warm[:n]])} ms")
    print(f"prompt tokens (est) median cold {med([r['promptTokensEst'] for r in cold[:n]])}; prewarm prefill median {med(pre)} ms over {len(pre)}")
