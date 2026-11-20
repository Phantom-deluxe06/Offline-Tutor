# Feature plan — adaptive brain + symbolic verifier

Branch `feature/adaptive-brain`, one commit per phase. Every feature sits behind a Settings toggle (default ON),
stored in `FeatureFlags` (SharedPreferences, excluded from backup). Pure logic lives in `domain/` with JVM tests.

> The pasted spec ends mid-sentence in Phase 2b ("This checks the arithmetic, not whether…"). Phases after 2 were not
> received and are not planned here.

## Cross-cutting

| Item | Where | Notes |
|---|---|---|
| Feature toggles | new `data/FeatureFlags.kt`, `SettingsScreen` "Smart features" section | default ON; read at call time |
| DB v1 → v2 (Phase 1) | `AppDatabase` (`MIGRATION_1_2`), `Entities`, `Daos` | `fallbackToDestructiveMigration` removed; additive `ALTER TABLE` + `CREATE TABLE` only |
| DB v2 → v3 (Phase 2) | same | adds `verdict` columns |
| Concept ids | `domain/Concepts.kt` | stable slug of the roadmap step title; old rows fall back to the slug at read time |

## Phase 1 — adaptive brain

| Feature | Files / classes | New data | Risks |
|---|---|---|---|
| 1a taxonomy | `assets/misconceptions.json`, `domain/Misconceptions.kt` | — | model invents codes → validator rejects unknown codes |
| 1b diagnostic MCQ | `domain/DiagnosticMcq.kt` (parser + validator for the spec schema, tag parser), `Prompts.diagnosticTagLines`, `data/DiagnosticGenerator` | `quiz_questions.optionMeta/concept` | **Changed after on-device test:** the one-shot nested schema failed (model copied `"..."`/`"CODE"`, invented options E–G), and "B) CODE - reason" lines failed 0/8. Now two steps: plain MCQ (validated path) → one call tagging each wrong option in the block shape the model writes on its own (`B) …` / `Mistake type:` / `Reason:`). Arithmetic codes on word answers map to CONCEPT_CONFUSION. The spec's `options` array schema is still parsed. Measured rates: `docs/eval/RESULTS.md` |
| 1c confidence | `QuizScreen` (select → Sure / Not sure), `domain/Confidence.kt` | `quiz_questions.confident/responseMs` | none |
| 1d Elo + rules | `domain/Mastery.kt` (rating update, next level, frustration) | `concept_mastery` table | thresholds are design constants, not performance claims |
| 1e self-healing | `domain/Healing.kt` policy, `data/AdaptiveBrain`, `RoadmapDao.insertAfter`, new `FixScreen` | `misconception_hits`, `roadmap_steps.kind/misconception/body/reason/targetConcept` | fix text is generated lazily when opened, so answering stays fast |
| 1f backtracking | `domain/PrereqGraph.kt` (DAG validation, depths), `domain/Backtrack.kt`, roadmap prompt `Needs:` field | `roadmap_steps.prereqs`, `pending_probes` | the model may omit `Needs:` → linear-chain fallback; it may form cycles → edges dropped |
| 1f skill tree | `ui/components/SkillTree.kt` (Canvas edges + positioned nodes) in `RoadmapScreen` | — | wide DAG rows → horizontal scroll |
| 1g evidence | node sheet in `RoadmapScreen`, `QuizDao.answeredForConcept` | — | none |
| demo seed | `data/DemoSeeder.kt`, Settings → Debug | — | replays attempts through the real `AdaptiveBrain` |

## Phase 2 — hybrid symbolic verifier

| Feature | Files / classes | New data | Risks |
|---|---|---|---|
| 2a templates | `domain/ProblemTemplates.kt` (8 types; Kotlin picks numbers, computes the answer, builds tagged distractors), `data/TemplateGenerator` (LLM writes text only) | `quiz_questions.source = "template"` | the LLM may alter numbers → exact-number check, fall back to Kotlin's own text |
| 2b verifier | `domain/ExpressionVerifier.kt` (exp4j), `"expression"` field in both MCQ prompts | `quiz_questions.verdict`, `generation_logs.verdict` | checks the arithmetic only, not pedagogy; options that are not numeric → `UNVERIFIABLE` |
| library | `net.objecthunter:exp4j` | — | re-check the merged manifest for INTERNET |
