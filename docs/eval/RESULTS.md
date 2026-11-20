# On-device eval results — 20 Nov 2026

Device: **motorola edge 40** (MediaTek, 8 GB RAM, Android 15, arm64-v8a). Runtime: ExecuTorch Android AAR 1.5.1, XNNPACK,
context 1024. Both `.pte` files were exported locally with pip `executorch==1.5.1` (`scripts/export_models.sh`).

> This phone has 8 GB. Re-run on a 4 GB device before claiming the 4 GB minimum: peak RAM below is ~1.2–1.5 GB,
> which should fit, but Android's low-memory killer on 4 GB phones is the real test.

Raw data: `eval_2026-11-20_edge40.csv` (one row per generation, with empty team-review columns) and
`analysis_2026-11-20_edge40.txt` (output of `scripts/analyze_eval.py`, including earlier runs).

## Performance (clean app start per model)

| Metric | SpinQuant INT4 | 8da4w (local, BF16 → INT4) |
|---|---|---|
| .pte size | 1,025 MB | 765 MB |
| Model load | 5.8 s | 4.0 s |
| Peak RAM, VmHWM | **1,461 MB** | **1,173 MB** |
| `dumpsys meminfo` TOTAL PSS (during generation) | 1,163 MB | 985 MB |
| TTFT, MCQ prompt (median) | 2.5 s | 2.0 s |
| Decode speed, MCQ eval (median) | 16.2 tok/s | 19.8 tok/s |
| Decode speed, quick bench | 18–19 tok/s | — |
| Wall time per MCQ (median) | 6.1 s (+2.8 s when the answer follow-up runs) | 6.0 s |
| Thermal status during all evals | 0 (none) | 0 (none) |

## Quality — fixed eval set (5 subjects × grades 6, 8, 10)

Final pipeline (after the fixes below). MCQ: 2 questions per subject × grade, difficulty rotated so easy/medium/hard
each get 10. Roadmap: 1 per subject × grade.

| Metric | SpinQuant INT4 | 8da4w |
|---|---|---|
| Valid MCQ (4 unique options, answer in range, explanation) | **93 % (28/30)** | 37 % (11/30) |
| — needed the answer follow-up call | 10/30 (all succeeded) | 2/30 |
| — stopped by the repetition guard | 1/30 | 14/30 |
| Valid MCQ by level (easy / medium / hard) | 100 % / 90 % / 90 % | 30 % / 40 % / 40 % |
| Duplicate rate (deduper, within subject × grade) | 4 % (1/28) | 18 % (2/11) |
| Valid roadmap (≥ 3 parsed steps) | **100 % (15/15)** | 93 % (14/15) |
| Answer-key correctness | team review → CSV | team review → CSV |
| Difficulty match | team review → CSV | team review → CSV |
| Roadmap rubric 1–5 | team review → CSV | team review → CSV |

**Takeaway:** SpinQuant costs ~290 MB more RAM and ~3 tok/s, but produces far more usable structured output
(the 8da4w export loops much more often). SpinQuant is the app default.

## What changed during on-device testing (and why)

| Problem seen on the phone | Fix | Effect |
|---|---|---|
| Runtime ignored `maxNewTokens` (875 tokens / 62 s with cap 300) | App counts tokens and calls `stop()` | Bounded latency |
| MCQ options array ran away (`"60","90","30",…`) | Fixed keys `"A".."D"` + letter answer | No runaway arrays |
| Repetition guard missed loops without spaces | Split words on any punctuation | Loops caught |
| Model copied "do not repeat" stems verbatim | Pass only stem keywords; concrete subtopic from template when no roadmap | Fewer copies (deduper still catches them) |
| Model closed the object after option D (no answer) | Short follow-up call: "which option is correct?" | MCQ valid 47 % → 93 % |
| Re-opened object / doubled quotes | Two targeted JSON repairs | Fewer parse failures |
| Nested JSON roadmaps 0–13 % valid | Numbered plain-text lines + lenient parser + stop after 5 steps | Roadmap valid 13 % → 100 % |
| Repeated phrasing across roadmap steps tripped the guard | Looser n-gram rule for structured tasks | No false stops |

Run history (same device, SpinQuant): MCQ 70 % (array format) → 47 % (letter keys, hard level added, before follow-up)
→ 93 % (with follow-up); roadmap 13 % (nested JSON) → 0 % (JSON, comma subtopics) → 100 % (line format).

## Not measured yet

* Answer-key correctness / difficulty match / roadmap rubric — needs the team review in the CSV. A spot check found
  wrong keys (e.g. "12 loaves × 7 days" with no 84 option), so expect this to be the weakest metric.
* Tamil / Bengali output quality.
* A 4 GB phone.
