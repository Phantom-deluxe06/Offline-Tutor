# Features spec, part 2: Phase 2 follow-ups + Phases 3–7

(Pasted by the team on 21 Nov 2026; saved here verbatim as the source for this work.)

The ground rules from part 1 still apply: 100% offline, a single LLM thread, a 1024-token context, a Settings toggle per feature, Room migrations, `./gradlew test assembleDebug` passing before each commit, and no assumed performance numbers.
Do the Phase 2 follow-ups first, then Phases 3–7 in order. Commit after each numbered item.

## Phase 2 follow-ups (based on the measurements from the first Phase 2 run)

### 2-fix-1. Placeholder stories (stop the model changing numbers)

* The model writes only the setting, using placeholders `{A}`, `{B}`, `{C}`… with no digits and no number words. Kotlin substitutes the real numbers afterwards.
* Accept the story only if every placeholder appears exactly once and the text contains no digits or number words ("five", "twice", "half"…). Otherwise, use our own wording, as now.
* The final question sentence ("What is the average score?") is Kotlin-owned and appended after the story, so an accepted story can't change what is being asked.
* Keep the fix that strips "Here is a word problem…" openers, and apply it to every template.
* Re-run every template type with at least 5 samples and report acceptance per type. Averages must stop scoring 0. If it's still low, log 3 rejected outputs verbatim so we can see why.

### 2-fix-2. Take the expression checker out of the main path

* Remove the `expression` field from the MCQ generation prompt.
* Numeric maths and physics questions come only from templates. Free-form LLM MCQs are for conceptual (non-arithmetic) questions, which don't need the checker.
* Keep the checker code and its measured results (3 of 19 checkable: 1 correct key fix, 1 correct discard, 1 wrong discard) as a documented finding for the report. Don't run it at generation time.

### 2-fix-3. Find the cause of the yield drop before changing anything else

* Log a failure reason for every rejected MCQ:
   * JSON parse error
   * truncated: hit maxNewTokens before the top-level object closed
   * validator rule failed (record which rule)
   * duplicate
* A/B test on the same 15 topic × difficulty pairs, with and without the `expression` field. Report usable counts and failure reasons, both overall and for hard questions only.
* If hard questions are mostly truncated, raise maxNewTokens for hard only (staying inside the 1024-token context) and ask for shorter options.

### 2-fix-4. Cheaper misconception tagging

* Ask for all three wrong options in one call, in a compact line format instead of JSON: `B|CODE|why (12 words max)`, one line per wrong option. List the option letters in the prompt and start the assistant turn with `B|` so the model continues from there.
* Keep the existing second call as a fallback for missing lines.
* Template questions are already tagged by Kotlin, so never send them to the tagger.
* Report the time per fully tagged question before and after. Tagging moves off the critical path in Phase 3, so correctness matters more than speed here.

### Housekeeping

* Delete the "Migrate" test profile. Keep "Demo Student" for the demo.

## Phase 3: zero-wait generation scheduler

Size everything to the measured cost: a fully tagged LLM question takes about 16–25 s, and a student spends about 20–30 s per question.

### 3a. Priority queue in front of the single LLM worker

* P0: live user request (chat, explain, a micro-fix the student opened)
* P1: pool refill / shadow pre-generation
* P2: night batch
* A higher-priority job preempts a lower one: call `stop()`, wait for it to return, and requeue the lower job.

### 3b. Ready pool instead of live generation

* Each active concept keeps a buffer of 3 ready questions (validated and tagged) per difficulty level near the student's current rating.
* A quiz serves from the pool, and background generation refills it.
* If the pool is empty when a quiz starts, serve a template question first (instant) while the first LLM question is generated.
* Shadow pre-generation: while question N is on screen, refill one item at the predicted next difficulty. Generate two candidates (one level up, one down) only when the device is cool and the pool is already full.
* Warm-up: when the app opens or the roadmap screen is shown, start refilling the pool for the current step.

### 3c. Night Shift

* A WorkManager job with `requiresCharging` + `requiresDeviceIdle` fills tomorrow's pool (questions per weak concept, pending micro-fixes, review items) and refreshes roadmap suggestions.
* Respect a pool cap and a storage limit.
* The same job runs the evaluation batch for the report: e.g. 50 LLM MCQs across subjects and difficulties, plus 30 template problems, logging every outcome and failure reason. That way the report isn't built on samples of 3.
* Add a "Simulate night" button in Settings for demos.

### 3d. Latency tricks

1. Prefill the static system prompt with `prefillPrompt()` and measure time to first token with and without it. Keep it only if it helps measurably and the output stays correct.
2. Stop generation as soon as the top-level JSON object closes (track brace depth).

### 3e. Thermal governor

* Read the PowerManager thermal status (API 29+) and `getThermalHeadroom` (API 30+), and show a status chip.
* MODERATE or above: shrink maxNewTokens and pause P2. SEVERE or above: pause P1 and P2.

### 3f. Dedupe

* Normalise the question text and fuzzy-match it against history before showing it; regenerate on a hit.

### Metrics

* Perceived wait per question (from tap to question shown): pool hit vs live generation.
* Pool hit rate across a 10-question quiz.
* Time per fully tagged question.
* Show all of these on the benchmark screen.

## Phase 4: code-mixed language bridge + analogy worlds

### 4a. Explanation language

* Options: English | Tanglish | Benglish | தமிழ் (beta) | বাংলা (beta).
* Tanglish/Benglish means Tamil/Bengali written in English letters, mixed with English. Keep all scientific terms, formulas, numbers and units in English. Put 2–3 short examples in the system prompt.
* Questions, options and answer keys stay in English so validation keeps working. Code-mixing is for explanations, hints and micro-fixes only.

### 4b. Analogy worlds

* At intake, the student picks a world: Village, City, Cricket, Cinema, Kitchen, Farm or Festivals.
* Ship `assets/analogies.json`, a hand-curated bank of local analogies keyed by concept area × world (fractions → dosa slices, symmetry → kolam, water cycle → monsoon, current → water in a pipe).
* Inject the matching hint into the prompt and have the model build the explanation around it.

### 4c. UI strings

* Hand-translate the UI strings (`values-ta`, `values-bn`).

### 4d. Metric

* Tokens generated and generation time per explanation for each language mode (from `onStats`).

## Phase 5: zero-internet classroom mesh (Nearby Connections)

Use Google Play services Nearby Connections (`play-services-nearby`). It works over Bluetooth/Wi-Fi with no internet. Follow the current official permission list for our target SDK, request permissions at runtime, and re-check the merged manifest for an INTERNET permission. If Play services is missing, hide the feature and offer the QR fallback (5c).

### 5a. Live quiz battle

* The host taps "Start battle" and picks a concept. Five questions come from the ready pool; generate only what's missing.
* The host advertises with `Strategy.P2P_STAR`, and joiners discover it and connect. Show a join code on the host screen to confirm.
* Joiners need the app but not the model, so add a model-less mode for them.
* Protocol: versioned JSON byte payloads, each well under the bytes-payload size limit.
   * `HELLO{nickname}`
   * `QUIZ{questions without answer flags}`
   * `ROUND_START{i, deadlineMs}`
   * `ANSWER{i, option, ms, confidence}`
   * `ROUND_RESULT`, `LEADERBOARD`, `END`
* Scoring: correctness plus a speed bonus. After each question, the host shows the results and the misconception behind each wrong answer.
* Host end screen: a class misconception heatmap (category × question), e.g. "60% picked FORMULA_SWAP on Q3".
* Privacy: nicknames only. No profiles or history leave the phone.

### 5b. Distributed question cache

* "Share questions" mode between nearby app users: exchange manifests (hash, concept, grade, difficulty), send only missing items, and cap the number per session.
* Share template items as (type, parameters, story), and have the receiver recompute the answer from the parameters.
* LLM items: the receiver re-runs schema validation and dedupe before storing them (source = PEER).
* Never share attempts or profiles.
* Count questions received and generation seconds saved, using the measured time per tagged question.

### 5c. Fallback without Play services

* A "Share my progress" QR code (ZXing; compressed mastery + misconception counts, under about 2.5 KB).
* A teacher scan mode that aggregates scanned codes into the same heatmap.

### Testing

* Test with at least 3 physical phones, and record the maximum number of joiners actually achieved.

## Phase 6: stretch (only if time remains, in this order)

### 6a. Teach Kutty

* The AI plays a confused younger kid. The student explains a concept in text, and Kutty asks one naive follow-up question.
* Score the explanation against 3–5 key points generated beforehand (keyword/phrase match), and show which points were missed.

### 6b. Textbook-grounded mode

* The student photographs a textbook page. Offline OCR reads it: the ML Kit bundled model for Latin and Devanagari scripts, Tesseract for Tamil and Bengali.
* Store the text in a Room FTS4 table and feed the top 1–2 matching passages into the quiz prompt.

## Phase 7: report + README

Update the benchmark screen and write `docs/REPORT_FEATURES.md` with measured numbers:

* Template acceptance per type and answer-key correctness (from the night evaluation batch).
* The expression-checker finding from 2-fix-2: what was tried, what it measured, and why arithmetic moved to templates.
* MCQ yield with failure reasons, plus the A/B result for the `expression` field.
* Time per tagged question before and after 2-fix-4.
* Perceived wait from the pool vs live, and the pool hit rate.
* Time to first token with and without the prompt prefill.
* Tokens and time per language mode.
* Mesh: join time, max joiners, questions shared, generation seconds saved.
* Peak RAM (VmHWM) during normal use, while hosting a battle, and during the night batch.

README: how to demo each feature (seed demo student, simulate night, start battle).
Every pitch claim must match the measurements, e.g. "24 of 24 template answer keys correct" or "N of N in the evaluation batch", never "no hallucinations".
