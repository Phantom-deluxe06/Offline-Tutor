package com.offlinetutor.data

import android.content.Context
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.Prompts
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.RoadmapParser
import com.offlinetutor.domain.Subjects
import com.offlinetutor.llm.GenRequest
import com.offlinetutor.llm.LlmEngine
import com.offlinetutor.llm.MemoryProbe
import com.offlinetutor.llm.TaskType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EvalSummary(
    val run: String,
    val model: String,
    val runs: Int,
    val validJsonRate: Double?,
    val duplicateRate: Double?,
    val avgTtftMs: Double?,
    val avgDecodeTps: Double?,
    val peakRssMb: Double?,
    val guardStops: Int,
)

data class RunnerState(
    val running: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val partial: String = "",
    val summary: EvalSummary? = null,
    val error: String? = null,
)

/**
 * Runs the quick benchmark and the fixed eval set (5 subjects x 3 grades) in the app
 * scope, so it survives leaving the screen. Eval questions never touch quiz history.
 */
class BenchmarkRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: LlmEngine,
    private val ai: TutorAi,
    private val mcq: McqGenerator,
    private val diag: DiagnosticGenerator,
    private val templates: TemplateGenerator,
    private val flags: FeatureFlags,
    private val metrics: MetricsRepository,
) {
    private val _state = MutableStateFlow(RunnerState())
    val state: StateFlow<RunnerState> = _state.asStateFlow()
    private var job: Job? = null

    val grades = listOf(6, 8, 10)

    fun loadModel() = launch("Loading model", 1) {
        val before = MemoryProbe.read(context)
        try {
            val ms = engine.ensureLoaded()
            val after = MemoryProbe.read(context)
            if (ms > 0) ai.logLoad(engine.activeModelName(), ms, after.vmHwmKb, after.vmRssKb, after.deviceAvailKb, null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ai.logLoad(engine.activeModelName(), 0, before.vmHwmKb, before.vmRssKb, before.deviceAvailKb, t.message)
            throw t
        }
        tick()
        null
    }

    /** Same short prompt 3 times: wall clock, TTFT, decode tok/s, peak RSS. */
    fun quickBench(runs: Int = 3) = launch("Quick benchmark", runs) { run ->
        repeat(runs) {
            ai.run(GenRequest(TaskType.BENCH, Prompts.bench()), LogMeta(evalRun = run)) { p -> partial(p) }
            tick()
        }
        summarize(run)
    }

    /** MCQ eval: perSubjectGrade questions for each of 5 subjects x 3 grades, difficulty cycling easy→hard. */
    fun mcqEval(perCombo: Int) = launch(if (flags.isOn(Feature.DIAGNOSTIC_MCQ)) "MCQ diag eval" else "MCQ eval",
        Subjects.EVAL.size * grades.size * perCombo) { run ->
        val diagnostic = flags.isOn(Feature.DIAGNOSTIC_MCQ)
        var combo = 0
        for (subject in Subjects.EVAL) for (grade in grades) {
            val stems = ArrayList<String>()
            val history = HashSet<String>()
            combo++
            for (k in 0 until perCombo) {
                // Rotate the start level per combo so easy, medium and hard are all covered even at 1-2 per combo.
                val level = ((combo + k) % 3) + AdaptiveDifficulty.EASY
                label("MCQ · $subject · Grade $grade · ${Prompts.difficultyText(level).substringBefore(':')}")
                // Diagnostic toggle on → eval the diagnostic schema (tagged wrong options), else the plain MCQ.
                val question = if (diagnostic) {
                    diag.attempt(grade, subject, subject, com.offlinetutor.domain.Concepts.slug(subject), subject, level, stems,
                        emptyList(), history, LogMeta(run, subject, grade, level)) { p -> partial(p) }.mcq?.question
                } else {
                    mcq.attempt(grade, subject, subject, level, stems, emptyList(), history,
                        LogMeta(run, subject, grade, level), onPartial = { p -> partial(p) }).mcq?.question
                }
                question?.let { stems.add(0, it); history.add(QuestionDeduper.normalize(it)) }
                tick()
            }
        }
        summarize(run)
    }

    /**
     * 2-fix-3 A/B: the same 15 (subject, grade, level) pairs, plain MCQs (no tagging), three arms interleaved per
     * pair so thermal state is shared: "expr" = old prompt with the expression field, "noexpr" = same prompt
     * without it, "prod" = current prompt (no expression, conceptual rule). Each arm logs under "<run>:<arm>".
     */
    fun mcqAbEval() = launch("MCQ AB eval", Subjects.EVAL.size * grades.size * 3) { run ->
        data class Arm(val name: String, val withExpression: Boolean, val conceptual: Boolean)
        val arms = listOf(Arm("expr", true, false), Arm("noexpr", false, false), Arm("prod", false, true))
        var combo = 0
        for (subject in Subjects.EVAL) for (grade in grades) {
            combo++
            val level = (combo % 3) + AdaptiveDifficulty.EASY // 5 pairs per level
            for (arm in arms) {
                label("A/B ${arm.name} · $subject · Grade $grade · L$level")
                mcq.attempt(grade, subject, subject, level, emptyList(), emptyList(), emptySet(),
                    LogMeta("$run:${arm.name}", subject, grade, level), onPartial = { p -> partial(p) },
                    withExpression = arm.withExpression, conceptualOverride = arm.conceptual)
                tick()
            }
        }
        summarize("$run:prod")
    }

    /**
     * 2a template eval: every template type x level once. Each row = one LLM wording attempt; "valid" means the
     * text kept every given number and added none (otherwise the app falls back to its own wording).
     */
    fun templateEval() = launch("Template eval", com.offlinetutor.domain.TemplateType.entries.size * 6) { run ->
        // 2-fix-1: at least 5 samples per type → 2 per level x 3 levels.
        for (type in com.offlinetutor.domain.TemplateType.entries) for (level in listOf(1, 1, 2, 2, 3, 3)) {
            label("Template · ${type.name.lowercase()} · L$level")
            templates.build(8, type, level, type.name.lowercase(), emptySet(), LogMeta(run, type.name, 8), onPartial = { p -> partial(p) })
            tick()
        }
        summarize(run)
    }

    /** Roadmap eval: one roadmap per subject x grade; valid-JSON rate here, 1–5 rubric by team review in the CSV. */
    fun roadmapEval() = launch("Roadmap eval", Subjects.EVAL.size * grades.size) { run ->
        for (subject in Subjects.EVAL) for (grade in grades) {
            label("Roadmap · $subject · Grade $grade")
            ai.run(
                GenRequest(TaskType.ROADMAP, Prompts.roadmap(grade, subject), assistantPrefix = Prompts.ROADMAP_PREFIX,
                    stopWhen = Prompts::roadmapDone),
                LogMeta(run, subject, grade),
                validate = { r -> (RoadmapParser.parse(r.text) != null) to null },
            ) { p -> partial(p) }
            tick()
        }
        summarize(run)
    }

    fun cancel() {
        job?.cancel()
    }

    private fun launch(label: String, total: Int, block: suspend (run: String) -> EvalSummary?) {
        if (job?.isActive == true) return
        val run = "${label.lowercase().replace(' ', '-')}-${System.currentTimeMillis()}"
        _state.value = RunnerState(running = true, label = label, total = total)
        job = scope.launch {
            try {
                val s = block(run)
                _state.value = _state.value.copy(running = false, summary = s ?: _state.value.summary, partial = "")
            } catch (ce: CancellationException) {
                _state.value = _state.value.copy(running = false, label = "Stopped", partial = "")
                throw ce
            } catch (t: Throwable) {
                _state.value = _state.value.copy(running = false, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun tick() { _state.value = _state.value.copy(done = _state.value.done + 1) }
    private fun label(l: String) { _state.value = _state.value.copy(label = l) }
    private fun partial(p: String) { _state.value = _state.value.copy(partial = p.takeLast(400)) }

    private suspend fun summarize(run: String): EvalSummary {
        val rows = metrics.forRun(run)
        val json = rows.mapNotNull { it.validJson }
        val dup = rows.mapNotNull { it.duplicate }
        val ttft = rows.mapNotNull { it.ttftMs?.toDouble() }
        val tps = rows.mapNotNull { it.decodeTokPerSec }
        val peak = rows.maxOfOrNull { it.peakRssKb }?.takeIf { it > 0 }
        return EvalSummary(
            run = run,
            model = rows.firstOrNull()?.model ?: engine.activeModelName(),
            runs = rows.size,
            validJsonRate = json.takeIf { it.isNotEmpty() }?.let { l -> l.count { it }.toDouble() / l.size },
            duplicateRate = dup.takeIf { it.isNotEmpty() }?.let { l -> l.count { it }.toDouble() / l.size },
            avgTtftMs = ttft.takeIf { it.isNotEmpty() }?.average(),
            avgDecodeTps = tps.takeIf { it.isNotEmpty() }?.average(),
            peakRssMb = peak?.let { it / 1024.0 },
            guardStops = rows.count { it.stopReason == "REPETITION" },
        )
    }
}
