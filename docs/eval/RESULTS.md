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

## Feature phases — 21 Nov 2026 (same phone, SpinQuant)

Raw data: `eval_2026-11-21_phase2_edge40.csv` and `analysis_2026-11-21_phase2_edge40.txt` (all runs, including the
Phase 1 diagnostic runs). Thermal status 0 and peak RSS ≤ 1,449 MB in every run below.

### 1b — diagnostic MCQ (plain MCQ, then a tagging call)

| Run | Base MCQ parsed | Wrong options fully tagged | Tag calls per tagged item |
|---|---|---|---|
| Phase 1, "B) CODE - reason" lines (15 items) | 8/15 | 0/8 | 1 |
| Phase 1, block format (15 items) | 12/15 | 6/12 | 1 |
| **Phase 2 build, block format + continuation call (30 items)** | **19/30** | **15/15** | 1 call: 6 · 2 calls: 9 |

Continuing from the first tagged block fixed the "stops after B" failure. Cost: median 9.2 s per tag call (vs 6.7 s per
MCQ), so a fully diagnostic question takes roughly 16–25 s. 2 of the 17 accepted questions were duplicates. Base MCQ
validity by level: easy 7/10, medium 7/10, hard 3/10.

### 2b — answer verifier (exp4j on the model's `"expression"`)

Of the 19 parsed MCQs: **1 FIXED, 2 DISCARD, 0 VERIFIED, 16 UNVERIFIABLE**.

* FIXED was right: "2x + 5 = 11" keyed 9; expression `(11 - 5) / 2` → the key moved to 3.
* DISCARD #1 was right: a "mode" question over a list with no repeated value.
* DISCARD #2 was a false reject: the key (10, for 15 − 5) was correct but the model wrote `10/2`.
* UNVERIFIABLE: the expression was empty, an equation with letters (`2x + 5 - 5/2 = 11 - 5`), a chemical equation, or the
  options were words.

**Takeaway:** the 1B model rarely writes a usable expression, so the verifier catches few keys on free-form questions.
Numeric reliability comes mainly from 2a templates; the verifier is a safety net.

### 2a — Kotlin-owned templates (8 types × 3 levels, LLM writes the story only)

| Run | LLM text accepted | Median wall per text | Notes |
|---|---|---|---|
| before preamble fix | 15/24 (62 %) | 3.1 s | Ohm's law 0/3: "Here is a word problem for a Grade 8 student…" — the 8 counts as an extra number |
| **after preamble fix** | **16/24 (67 %)** | 3.0 s | Ohm's law 3/3; average 0/3 |

Rejected texts fall back to the app's own wording, so **24/24 questions keep the Kotlin-computed answer** either way.
Rejections were real problem changes (e.g. "120 packets at Rs 80 … buys 25 packets", "90-minute ride … 1.5 hours …
60 km/h"). Limitation: the check is on numbers only — an accepted text can still change the meaning ("40% of the total
sales is Rs 240").

## Not measured yet

* Answer-key correctness / difficulty match / roadmap rubric — needs the team review in the CSV. A spot check found
  wrong keys (e.g. "12 loaves × 7 days" with no 84 option), so expect this to be the weakest metric.
* Tamil / Bengali output quality.
* A 4 GB phone.
