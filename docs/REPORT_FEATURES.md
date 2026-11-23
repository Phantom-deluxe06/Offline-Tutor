# Feature report — what was built and what it measured

Device: **motorola edge 40** (MediaTek, 8 GB RAM, Android 15, arm64-v8a), Llama 3.2 1B Instruct SpinQuant INT4 via
ExecuTorch 1.5.1, context 1024. Every number below was measured on this phone; raw data and analysis output are in
`docs/eval/` (`analysis_2026-11-23_fixes_edge40.txt` for the Phase 2 follow-ups). Where something was **not measured**,
it says so — no assumed numbers.

## Summary table

| Area | Result (measured) | Data |
|---|---|---|
| Template problems — answer keys | Kotlin computes every key; 25 seeds x 8 types x 3 levels re-checked by exp4j in unit tests (all VERIFIED); on the phone 24 of 24 and later 48 of 48 template questions kept the Kotlin answer | unit tests, template evals |
| Template stories (LLM wording accepted) | Number-check prompt: 15/24 → 16/24. Placeholder prompt v1: **19/48 (40 %)** | template evals |
| Expression checker | 3 of 19 parsed MCQs checkable: 1 correct key fix, 1 correct discard, 1 wrong discard | Phase 2 run |
| MCQ yield, expression A/B (same 15 pairs) | with field **10/15**, without **10/15**, current conceptual prompt **11/15** | A/B run |
| Diagnostic MCQ (tagged) yield | 19/30 parsed; all 17 that reached tagging were fully tagged | diag run after 2-fix-4 |
| Time per fully tagged question | before 2-fix-4: median **20.9 s** (tags 14.1 s); after: **20.8 s** (tags 14.3 s) | diag runs |
| Ready pool | 1 pool hit, served in **150 ms** (n = 1 — smoke test only) | quiz_questions |
| Peak RAM (VmHWM) during the eval batches | **1,441 MB** | every 23 Nov run |

## Phase 2 follow-ups

**2-fix-1 Placeholder stories.** The model writes the setting with `{A}`, `{B}` placeholders; Kotlin inserts the numbers
and owns the question sentence. First version: 19/48 accepted. Rejections: placeholder missing 23, number word 4,
placeholder repeated 2 (long descriptive sentences that never used the placeholders). Per type: speed 0/6,
percent 2/6, simple interest 4/6, area/perimeter 1/6, Ohm's law 3/6, density 2/6, unit conversion 5/6, average 2/6
(averages no longer score 0). A tighter prompt (one sentence, units inside placeholders, lower temperature) and a
repeated-unit check (2-fix-1b) are built but **not measured yet**. Rejected stories fall back to the app's own wording,
so the answer key is never affected.

**2-fix-2 Expression checker — finding.** We asked the 1B model for an `"expression"` (e.g. `120/1.5`) and evaluated it
with exp4j. Measured: only 3 of 19 parsed MCQs had a usable expression (1 correct key fix, 1 correct discard of a broken
question, 1 wrong discard of a correct question); the rest were empty, equations with letters or chemical formulas.
So arithmetic moved to Kotlin templates, LLM MCQs became conceptual, and the checker no longer runs at generation time
(its code and tests stay).

**2-fix-3 Yield — cause.** Same 15 pairs, three arms: with the expression field 10/15, without 10/15, current prompt
11/15. Hard questions: 5/5, 4/5, 4/5. No run hit maxNewTokens (no truncation), so maxNewTokens for hard questions was
**not** raised. Failure reasons logged: the answer follow-up copied the prompt's example text ("placeholder text copied
from the prompt") — the largest single cause; then duplicate options, two repetition-guard stops and one parse error.
The expression field was not the cause.

**2-fix-4 One-call tagging.** The compact `B|CODE|why` call completed all three lines on its own for only 1 of 17
questions; the block-format fallback completed the rest (17/17 fully tagged). Time per fully tagged question did not
improve (20.9 s → 20.8 s median). Tagging is now done in the background (Phase 3 pool), so it is off the critical path.

## Phase 3 — zero-wait scheduler

Built: priority gate with preemption (P0 live, P1 pool refill, P2 night) — unit-tested; ready pool (3 per level per
concept, cap 60) with shadow pre-generation and warm-up; Night Shift (WorkManager, charging + idle, 8-minute chunks)
with "Simulate night"; prompt prefill experiment (off by default); thermal governor; dedupe at serve time.
Measured on the phone so far: the pool refilled in the background after launch, and the next question was served from
the pool in 150 ms (one sample). **Not measured yet:** pool hit rate over a 10-question quiz, perceived wait pool vs live
(the Benchmark "Zero-wait" card shows both once questions are served), TTFT with vs without prefill (Benchmark →
Prefill A/B), the full Night Shift batch (50 MCQs + 30 templates), peak RAM during the night batch.

## Phase 4 — language bridge and analogy worlds

Built: English / Tanglish / Benglish / Tamil (beta) / Bengali (beta) explanation modes (questions and keys stay English);
12 concept areas x 7 worlds of hand-curated analogies injected into explanations, chat and fix lessons; UI strings in
Tamil and Bengali. **Not measured yet:** tokens and time per language mode (Benchmark → Language eval).

## Phase 5 — classroom mesh (Nearby Connections)

Built: quiz battle (host advertises with P2P_STAR and a join code, versioned JSON payloads, score = correctness + speed
bonus, per-round misconception breakdown, class heatmap), model-less joiner mode, question sharing (template items
rebuilt from parameters, LLM items re-validated), QR fallback (progress QR measured at 243 characters for the demo
student; limit 2,500). **Not tested with two phones yet:** join time, max joiners, questions shared, seconds saved,
peak RAM while hosting.

## Phase 6 — stretch

Built: Teach Kutty (key points generated first, keyword scoring, one naive follow-up question) and textbook mode
(ML Kit on-device OCR for Latin and Devanagari, Room FTS4, top 1–2 passages added to the quiz prompt). Tamil / Bengali
OCR (Tesseract) is not included. **Not run on the phone yet.**

## Claims we can make

* "Template answer keys are computed by the app: 48 of 48 template questions in the evaluation kept the Kotlin answer."
* "The AI writes the story, not the numbers: the model never sees or changes the quantities."
* "100 % offline: no INTERNET permission in the merged manifest."
* Not "no hallucinations": LLM conceptual questions are validated for structure, not for factual correctness.
