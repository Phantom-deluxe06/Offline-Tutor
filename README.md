# Guru · Offline AI Tutor (Android)

A 100% offline tutor for Indian school students (Grades 6–12), running **Llama 3.2 1B Instruct (INT4)** on-device
with **ExecuTorch 1.5.1** (XNNPACK). Kotlin, Jetpack Compose, Room, coroutines. No INTERNET permission.

## Features

| Feature | Where | Notes |
|---|---|---|
| Local profiles (grade + courses, optional PIN) | `ui/screens/ProfileScreens.kt` | PIN stored as salted PBKDF2-SHA256 hash |
| LLM step-by-step roadmap | `RoadmapRepository` | numbered plain-text lines (nested JSON failed at 1B), lenient parser, retry once, template fallback |
| Adaptive MCQ quiz | `QuizRepository`, `domain/Adaptive.kt` | 2-up/1-down staircase (easy→medium→hard), weak-subtopic targeting |
| No repeated questions | `domain/QuestionDeduper.kt` | normalised exact + Jaccard + Levenshtein vs. full Room history; prompt line is 2nd layer |
| MCQ validation | `domain/McqParser.kt` | 4 unique options, answer in range, explanation; retry once → seed bank (`assets/seed_questions.json`) |
| Tutor chat | `ChatRepository` | Indian everyday analogies; history trimmed to the token budget |
| Tamil / Bengali explanations (beta) | `domain/Prompts.kt` | Llama 3.2 does not officially support ta/bn — labelled beta in UI |
| Hand-translated UI | `res/values-ta`, `res/values-bn` | per-app language via AppCompat (works below Android 13 too) |
| Progress graphs | `ui/screens/ProgressScreen.kt` | daily accuracy, difficulty trail, per-level / per-subject bars, weak spots |
| Benchmark + eval set | `ui/screens/BenchmarkScreen.kt`, `data/BenchmarkRunner.kt` | peak RAM (VmHWM), TTFT, tok/s, valid-JSON & duplicate rate, CSV export |
| Import model | `ui/screens/ModelsScreen.kt` | system file picker → copy into `filesDir/models`; adb push for dev |

## LLM engine (`llm/LlmEngine.kt`)

* One `LlmModule`, one **single-thread executor** (`llm-worker`), one `Mutex` → never two generations at once.
* `generate()` is blocking native code. On coroutine cancel we call `stop()` and **wait** for `generate()` to return
  before releasing the lock (and before `close()`).
* `load()` returns `Int` in 1.5.x and `Unit` (throws) on newer builds — both handled (`val rc: Any? = m.load()`).
* Llama 3 chat template with `<|begin_of_text|>` in the prompt text and `numBos = 0`.
* `resetContext()` before every task: each request carries its full (budgeted) prompt.
* Context budget (export context = 1024): prompt ≤ ~600 tokens, maxNewTokens 300–400 per task.
* Temperatures: 0.5 JSON tasks (roadmap, MCQ), 0.6 explanations, 0.7 chat.
* **App-level repetition guard** (`llm/RepetitionGuard.kt`) — the API has no repetition penalty: stops on repeated lines,
  repeated 6-grams, back-to-back tail loops, character runs and blank output.
* **JSON early stop** — generation stops as soon as the first JSON object closes.
* **Thermals** (`llm/DeviceProbes.kt`): PowerManager thermal status + `getThermalHeadroom`. MODERATE → 75 % tokens +3 s
  cool-down; SEVERE → 50 % tokens + 15 s cool-down; CRITICAL → 35 % + 30 s.
* `onStats` JSON is parsed defensively; wall-clock TTFT/tok/s are used if fields are missing.

## Build

Requirements: JDK 17+ (Android Studio's JBR works), Android SDK with platform 36 + build-tools 35.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a only).

## Model export (WSL2 Ubuntu)

```bash
bash /mnt/d/Projects/OfflineTutor/scripts/export_models.sh
```

Produces `tutor_1b_spinquant.pte`, `tutor_1b_8da4w.pte` and `tokenizer.model` (pip `executorch==1.5.1`, same as the AAR).
EOS ids `128009, 128001` are baked into the `.pte` metadata, and context is set to 1024 (the export default is 128).

Install on the phone: **AI model → Import .pte / Import tokenizer**, or for dev:

```powershell
.\scripts\push_model.ps1 -Dir model-export\out
```

## Offline compliance

* `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` removed with `tools:node="remove"` (check
  `app/build/intermediates/merged_manifests/debug/.../AndroidManifest.xml`).
* `allowBackup="false"` + `data_extraction_rules.xml` / `backup_rules.xml` exclude every domain.
* Local profiles only; no accounts anywhere.

## Measured on-device

See `docs/eval/RESULTS.md` (motorola edge 40): SpinQuant INT4 peaks at ~1.46 GB RAM, ~16–19 tok/s, 93 % valid MCQs and
100 % parsed roadmaps on the 5 × 3 eval set.

## Report metrics

See `docs/REPORT_TEMPLATE.md`. Peak RAM = `VmHWM` from `/proc/self/status` (logged per run) plus
`adb shell dumpsys meminfo com.offlinetutor`. Every generation is logged in Room (`generation_logs`) and exported as CSV
from the Benchmark screen, with empty review columns for the team's manual scoring.

Minimum device: **4 GB RAM** arm64 phone (INT4 1B at 1024 context). 2 GB is not realistic.
