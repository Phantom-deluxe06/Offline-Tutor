# Offline AI Tutor — Evaluation Report (template)

Fill every `…` from the CSV exported on the Benchmark screen (`tutor-runs-*.csv`) and from `adb shell dumpsys meminfo`.

## 1. Setup

| Item | Value |
|---|---|
| Phone | … (RAM …, SoC …, Android …) |
| Runtime | ExecuTorch Android AAR 1.5.1, XNNPACK, arm64-v8a |
| Export | pip `executorch==1.5.1`, `max_seq_length = max_context_length = 1024` |
| Model A | Llama 3.2 1B Instruct **SpinQuant INT4** (`llama_xnnpack_spinquant.yaml`: preq 8da4w out 8da8w, group 32, embeddings 8-bit) |
| Model B | Llama 3.2 1B Instruct **BF16 → 8da4w** quantized locally (`llama_xnnpack.yaml`: group 128, embeddings 4,32) |
| Minimum RAM | 4 GB (INT4 only; 2 GB is not realistic for a 1B model) |

## 2. Performance (Quick benchmark ×3 + eval runs)

| Metric | SpinQuant INT4 | 8da4w (local) |
|---|---|---|
| .pte size | … MB | … MB |
| Model load time | … ms | … ms |
| Peak RAM, VmHWM | … MB | … MB |
| `dumpsys meminfo` TOTAL PSS | … MB | … MB |
| Time to first token (median) | … ms | … ms |
| Decode speed (median) | … tok/s | … tok/s |
| Prefill speed (median) | … tok/s | … tok/s |
| Thermal status after eval | … | … |

`dumpsys` command: `adb shell dumpsys meminfo com.offlinetutor` (run while a generation is in progress).

## 3. Quality — fixed eval set (5 subjects × grades 6, 8, 10)

| Metric | How it is measured | SpinQuant | 8da4w |
|---|---|---|---|
| Valid MCQ JSON rate | `valid_json` column, MCQ rows (parser + validator: 4 unique options, answer index in range, explanation) | …% | …% |
| Answer-key correctness | team review → `review_answer_correct` (y/n) | …% | …% |
| Duplicate rate | `duplicate` column (deduper vs. earlier questions in the same subject × grade) | …% | …% |
| Difficulty match | team review → `review_difficulty_match` (y/n vs. requested easy/medium/hard) | …% | …% |
| Valid roadmap JSON rate | `valid_json` column, ROADMAP rows | …% | …% |
| Roadmap rubric (1–5) | team review: coverage, order, grade fit → `review_roadmap_score_1to5` | … | … |
| Repetition-guard stops | `stop_reason = REPETITION` | … | … |

## 4. Engineering mitigations (what the app does on top of the model)

* **App-level repetition guard** — the ExecuTorch LLM API has no repetition penalty. The stream is watched for repeated
  lines, repeated 6-grams, tail loops, character runs and blank output, and `stop()` is called.
* **Code-level question dedupe** — normalised exact match + Jaccard (≥ 0.75) + Levenshtein ratio (≥ 0.85) against the
  student's whole quiz history; on a hit the question is regenerated. The prompt's "never repeat" line is a second layer.
* **JSON robustness** — assistant prefill (`{"question":"` / `{"steps":[{"title":"`), temperature 0.5, early stop when the
  object closes, truncation repair, validation, one retry, then the seed bank / template roadmap.
* **Answer shuffling** — 1B models over-pick option A; options are shuffled and the key is remapped.
* **Context budget** — 1024-token context: prompt ≤ ~600 tokens (last 10 question stems + weak subtopics only), 300–400 new
  tokens per task, `resetContext()` between tasks.
* **Thermals** — PowerManager thermal status / headroom: shorter outputs and cool-down at MODERATE/SEVERE+.
* **Threading** — one dedicated native thread, one generation at a time, cancel = `stop()` + wait.

**Measured results with both models side by side: `docs/eval/RESULTS.md`.**

### Findings from the first on-device test (motorola edge 40, 8 GB, Android 15, 20 Nov 2026)

* SpinQuant .pte: load 6.3 s, peak RSS 1,434 MB, decode 18–19 tok/s, TTFT ~1.5 s; EOS stops generation (`END`);
  output was clean English, so the Llama 3 template with `numBos = 0` is confirmed.
* **The 1.5.1 runner ignored `maxNewTokens`** (875 tokens / 62 s with a cap of 300; it fills to `seqLen`), so the cap is
  enforced in the app (`StopReason.MAX_TOKENS`).
* **MCQ options as a JSON array ran away** ("60","90","30","45",… repeated). Switched to fixed keys `"A".."D"` + letter
  answer; the repetition guard now also splits on punctuation, so space-less loops are caught.
* **Verbatim "do not repeat" stems were copied** by the 1B model. Only keywords of earlier stems are passed now, and a
  concrete subtopic from the template roadmap is used when the student has no roadmap yet. The code-level deduper
  caught every copy before these fixes.
* After the fixes: valid MCQ in ~5 s, but the answer key can still be wrong (e.g. 12 loaves/day × 7 with no "84"
  option) — this is what the team-review column measures.
* This phone has 8 GB; repeat the benchmark on a 4 GB device for the minimum-spec claim.

## 5. Languages

* Default: English with Indian everyday analogies.
* Tamil / Bengali model output is **beta**: Llama 3.2 officially supports Hindi but not Tamil or Bengali, and a 1B model is
  weak in them. Indic scripts also cost far more tokens (≈1 token per character vs. ≈4 characters per token in English),
  so answers are slower and shorter within the same context. Observed quality: …
* UI strings are hand-translated (`values-ta`, `values-bn`).

## 6. Privacy / offline

No `INTERNET` permission (removed even if a library merges it), backups disabled, local profiles only, model imported
from local storage. Verified with: `aapt dump permissions app-debug.apk` → …

## 7. Limitations

…
