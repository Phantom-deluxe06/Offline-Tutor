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
| Adaptive brain (phase 1) | `data/AdaptiveBrain.kt`, `domain/Mastery.kt`, `domain/DiagnosticMcq.kt` | misconception-tagged options, Sure / Not sure, Elo per concept, self-healing fix steps, prerequisite backtracking, skill tree |
| Symbolic verifier (phase 2) | `domain/ProblemTemplates.kt`, `domain/Verifier.kt` | 8 Kotlin-owned problem types (app computes the answer, LLM writes the story); exp4j re-checks every numeric MCQ key |
| Feature toggles | `data/FeatureFlags.kt`, Settings → Smart features | each phase-1/2 feature can be switched off for on/off demos and benchmarks; plan in `docs/FEATURES_PLAN.md` |

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

## Get the model (not in this repo)

The model files are too large for git (and `*.pte` / `tokenizer.model` are git-ignored). Download them:

| File | Size | Link |
|---|---|---|
| `tutor_1b_spinquant.pte` (default, Llama 3.2 1B Instruct SpinQuant INT4) | 1,025 MB | `<GOOGLE_DRIVE_LINK_SPINQUANT>` |
| `tokenizer.model` | ~2 MB | `<GOOGLE_DRIVE_LINK_TOKENIZER>` |
| `tutor_1b_8da4w.pte` (optional, for the RAM comparison) | 765 MB | `<GOOGLE_DRIVE_LINK_8DA4W>` |

Llama 3.2 is under the Llama 3.2 Community License; accept it on Hugging Face before using the weights.

Setup:

1. Build and install the APK (`.\gradlew.bat installDebug`, or copy `app-debug.apk` to the phone).
2. Copy the downloaded files to the phone, then in the app: **⋮ → AI model → Import .pte** and **Import tokenizer**
   (or `.\scripts\push_model.ps1 -Dir <folder with the files>` over adb).
3. Create a profile, or **Settings → Debug → Seed demo student** for the demo data.

Or export the files yourself:

## Model export (WSL2 Ubuntu)

```bash
bash scripts/export_models.sh   # run from the repo root inside WSL
```

Produces `tutor_1b_spinquant.pte`, `tutor_1b_8da4w.pte` and `tokenizer.model` (pip `executorch==1.5.1`, same as the AAR).
EOS ids `128009, 128001` are baked into the `.pte` metadata, and context is set to 1024 (the export default is 128).

Install on the phone: **AI model → Import .pte / Import tokenizer**, or for dev:

```powershell
.\scripts\push_model.ps1 -Dir model-export\out
```

## Offline compliance

* `INTERNET` and `ACCESS_NETWORK_STATE` removed with `tools:node="remove"`, so no library can merge them back (check
  `app/build/intermediates/merged_manifests/debug/.../AndroidManifest.xml`).
* The classroom mesh (Phase 5) declares the Nearby Connections permissions (Bluetooth, Wi-Fi state, nearby Wi-Fi
  devices, location up to Android 12) for phone-to-phone links only; CAMERA is for the teacher's QR scan.
  WorkManager (Night Shift) adds WAKE_LOCK, RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE.
* `allowBackup="false"` + `data_extraction_rules.xml` / `backup_rules.xml` exclude every domain.
* Local profiles only; no accounts anywhere.

## Measured on-device

See `docs/eval/RESULTS.md` (motorola edge 40): SpinQuant INT4 peaks at ~1.46 GB RAM, ~16–19 tok/s, 93 % valid MCQs and
100 % parsed roadmaps on the 5 × 3 eval set. Feature phases (same file): diagnostic tagging completes on 15/15 parsed
MCQs, template word problems keep the app-computed answer on 24/24, and the verifier can check only the few MCQs where
the model writes a usable expression.

## Report metrics

See `docs/REPORT_TEMPLATE.md`. Peak RAM = `VmHWM` from `/proc/self/status` (logged per run) plus
`adb shell dumpsys meminfo com.offlinetutor`. Every generation is logged in Room (`generation_logs`) and exported as CSV
from the Benchmark screen, with empty review columns for the team's manual scoring.

Minimum device: **4 GB RAM** arm64 phone (INT4 1B at 1024 context). 2 GB is not realistic.
