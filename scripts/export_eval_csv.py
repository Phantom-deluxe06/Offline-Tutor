"""Export eval runs from the app database to CSV for the team review columns.

Usage: python scripts/export_eval_csv.py <tutor.db> <out.csv> [run-id ...]
With no run ids, exports the latest MCQ and roadmap eval run for each model.
"""
import csv
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1])
db.row_factory = sqlite3.Row
runs = sys.argv[3:]
if not runs:
    for kind in ("mcq-eval-%", "roadmap-eval-%"):
        for (model,) in db.execute("select distinct model from generation_logs where evalRun like ?", (kind,)):
            r = db.execute(
                "select evalRun from generation_logs where evalRun like ? and evalRun not like '%:answer' and model=? order by id desc limit 1",
                (kind, model),
            ).fetchone()
            if r:
                runs += [r[0], r[0] + ":answer"]

cols = ["id", "evalRun", "model", "task", "evalSubject", "evalGrade", "difficulty", "validJson", "duplicate",
        "stopReason", "guardReason", "generatedTokens", "ttftMs", "wallMs", "decodeTokPerSec", "peakRssKb", "output"]
with open(sys.argv[2], "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(cols + ["review_answer_correct", "review_difficulty_match", "review_roadmap_score_1to5"])
    n = 0
    for run in runs:
        for row in db.execute(f"select {','.join(cols)} from generation_logs where evalRun=? order by id", (run,)):
            w.writerow(list(row) + ["", "", ""])
            n += 1
print(f"wrote {n} rows from {len(runs)} runs -> {sys.argv[2]}")
