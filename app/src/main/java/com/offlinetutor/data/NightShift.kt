package com.offlinetutor.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.offlinetutor.domain.Concepts
import com.offlinetutor.domain.ExpressionVerifier
import com.offlinetutor.domain.MasteryModel
import com.offlinetutor.domain.NightPlan
import com.offlinetutor.domain.NightTask
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.Subjects
import com.offlinetutor.domain.TemplateType
import com.offlinetutor.llm.GenPriority
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.llm.PreemptedException
import com.offlinetutor.llm.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 3c: Night Shift. While the phone is charging and idle (WorkManager) — or on "Simulate night" — it fills
 * tomorrow's pool (weak concepts, review targets), pre-writes pending micro-fix lessons, then runs the report's
 * evaluation batch (50 tagged LLM MCQs + 30 template problems), logging every outcome and failure reason.
 *
 * Everything runs at P2: a live request preempts it (the task is retried), the thermal governor can pause it.
 * The plan and a cursor are stored in SharedPreferences so a run that ends early resumes where it stopped.
 */
class NightShift(
    private val context: Context,
    private val profiles: ProfileDao,
    private val mastery: MasteryDao,
    private val roadmaps: RoadmapDao,
    private val quiz: QuizRepository,
    private val fixes: FixService,
    private val diag: DiagnosticGenerator,
    private val templates: TemplateGenerator,
    private val ai: TutorAi,
    private val pausedFor: (Priority) -> Boolean = { false },
) {
    data class Progress(val running: Boolean = false, val night: String? = null, val done: Int = 0, val total: Int = 0,
                        val current: String? = null, val lastSummary: String? = null)

    private val prefs = context.getSharedPreferences("night_shift", Context.MODE_PRIVATE)
    private val _progress = MutableStateFlow(Progress(lastSummary = prefs.getString(KEY_SUMMARY, null)))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Counters for the night summary (persisted with the cursor). */
    private class Counts(
        var poolAdded: Int = 0, var fixesWritten: Int = 0, var mcqUsable: Int = 0, var mcqTagged: Int = 0, var mcqDone: Int = 0,
        var tplDone: Int = 0, var tplLlmStory: Int = 0, var tplKeyVerified: Int = 0, var preempted: Int = 0, var skippedStorage: Int = 0,
    ) {
        fun json() = JSONObject().put("poolAdded", poolAdded).put("fixesWritten", fixesWritten).put("mcqDone", mcqDone)
            .put("mcqUsable", mcqUsable).put("mcqTagged", mcqTagged).put("tplDone", tplDone).put("tplLlmStory", tplLlmStory)
            .put("tplKeyVerified", tplKeyVerified).put("preempted", preempted).put("skippedStorage", skippedStorage)

        companion object {
            fun from(s: String?): Counts = s?.let {
                runCatching {
                    val o = JSONObject(it)
                    Counts(o.optInt("poolAdded"), o.optInt("fixesWritten"), o.optInt("mcqUsable"), o.optInt("mcqTagged"),
                        o.optInt("mcqDone"), o.optInt("tplDone"), o.optInt("tplLlmStory"), o.optInt("tplKeyVerified"),
                        o.optInt("preempted"), o.optInt("skippedStorage"))
                }.getOrNull()
            } ?: Counts()
        }
    }

    /**
     * Runs tasks until the plan is finished or [budgetMs] is used up. Returns true when tonight's plan is complete.
     * [evalMcqs]/[evalTemplates] size the evaluation batch (spec default 50 + 30).
     */
    suspend fun run(budgetMs: Long, evalMcqs: Int = NightPlan.EVAL_MCQS, evalTemplates: Int = NightPlan.EVAL_TEMPLATES): Boolean =
        withContext(GenPriority(Priority.P2)) {
            val start = SystemClock.elapsedRealtime()
            val night = prefs.getString(KEY_NIGHT, null) ?: newNight(evalMcqs, evalTemplates)
            val plan = loadPlan()
            var index = prefs.getInt(KEY_INDEX, 0)
            val counts = Counts.from(prefs.getString(KEY_COUNTS, null))
            val history = HashSet<String>()
            _progress.value = Progress(true, night, index, plan.size, null, _progress.value.lastSummary)
            try {
                while (index < plan.size && SystemClock.elapsedRealtime() - start < budgetMs) {
                    while (pausedFor(Priority.P2)) delay(15_000) // 3e: wait while hot
                    val task = NightTask.decode(plan[index])
                    _progress.value = _progress.value.copy(done = index, current = task?.let { describe(it) })
                    try {
                        if (task != null) execute(task, night, counts, history)
                    } catch (_: PreemptedException) {
                        counts.preempted++
                        save(index, counts)
                        delay(2_000)
                        continue // a live request took the worker: retry the same task
                    } catch (e: ModelMissingException) {
                        throw e
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Log.w(TAG, "night task failed: ${plan[index]}", t)
                    }
                    index++
                    save(index, counts)
                }
                val finished = index >= plan.size
                if (finished) finish(night, counts)
                _progress.value = _progress.value.copy(running = false, done = index, current = null)
                finished
            } catch (t: Throwable) {
                _progress.value = _progress.value.copy(running = false, current = null)
                throw t
            }
        }

    /** Forget tonight's plan (Settings "Simulate night" restarts from the beginning). */
    fun reset() {
        prefs.edit().remove(KEY_NIGHT).remove(KEY_PLAN).remove(KEY_INDEX).remove(KEY_COUNTS).apply()
    }

    private suspend fun newNight(evalMcqs: Int, evalTemplates: Int): String {
        val night = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val work = profiles.all().map { p ->
            val rows = mastery.all(p.id).filter { it.attempts > 0 && it.rating < MasteryModel.MASTERED_AT }.sortedBy { it.rating }
            val steps = p.courseList.flatMap { roadmaps.forCourse(p.id, it) }
            val reviews = steps.filter { it.stepKind == StepKind.REVIEW && !it.done }.map { s ->
                val c = s.targetConcept ?: s.concept
                Triple(s.course, c, quiz.currentLevel(p.id, s.course, c))
            }
            NightPlan.ProfileWork(
                profileId = p.id,
                weak = rows.map { m -> Triple(m.course, m.conceptId, m.level) },
                reviews = reviews,
                pendingFixes = steps.filter { it.stepKind == StepKind.MICRO_FIX && !it.done && it.body.isNullOrBlank() }.map { it.id },
            )
        }
        val plan = NightPlan.build(work, evalMcqs, evalTemplates)
        prefs.edit().putString(KEY_NIGHT, night).putString(KEY_PLAN, JSONArray(plan.map { it.encode() }).toString())
            .putInt(KEY_INDEX, 0).remove(KEY_COUNTS).apply()
        return night
    }

    private fun loadPlan(): List<String> = prefs.getString(KEY_PLAN, null)?.let { s ->
        runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
    }.orEmpty()

    private fun save(index: Int, c: Counts) {
        prefs.edit().putInt(KEY_INDEX, index).putString(KEY_COUNTS, c.json().toString()).apply()
    }

    private suspend fun finish(night: String, c: Counts) {
        val summary = c.json().put("night", night).toString()
        ai.logNote("NIGHT_SUMMARY", "night-summary-$night", summary)
        prefs.edit().putString(KEY_SUMMARY, summary).putString(KEY_LAST_DONE, night).apply()
        reset()
        _progress.value = _progress.value.copy(lastSummary = summary)
    }

    private fun describe(t: NightTask) = when (t) {
        is NightTask.Pool -> "pool · ${t.concept} · L${t.level}"
        is NightTask.Fix -> "fix lesson · step ${t.stepId}"
        is NightTask.EvalMcq -> "eval MCQ ${t.index + 1}"
        is NightTask.EvalTemplate -> "eval template ${t.index + 1}"
    }

    private fun storageFull(): Boolean {
        val db = context.getDatabasePath("tutor.db")
        val bytes = db.length() + java.io.File(db.path + "-wal").length()
        return bytes > STORAGE_LIMIT_BYTES
    }

    private suspend fun execute(t: NightTask, night: String, c: Counts, history: MutableSet<String>) {
        when (t) {
            is NightTask.Pool -> {
                if (storageFull()) { c.skippedStorage++; return }
                val p = profiles.get(t.profileId) ?: return
                c.poolAdded += quiz.fillPool(p, t.course, t.concept, t.level)
            }
            is NightTask.Fix -> {
                val p = profiles.get(t.profileId) ?: return
                val step = roadmaps.get(t.stepId) ?: return
                if (step.body.isNullOrBlank() && fixes.lesson(p, step) {}.isNotBlank()) c.fixesWritten++
            }
            is NightTask.EvalMcq -> {
                val (si, gi, level) = NightPlan.mcqSlot(t.index, Subjects.EVAL.size, EVAL_GRADES.size)
                val subject = Subjects.EVAL[si]
                val grade = EVAL_GRADES[gi]
                val o = diag.attempt(grade, subject, subject, Concepts.slug(subject), subject, level, emptyList(), emptyList(),
                    history, LogMeta("night-eval-$night", subject, grade, level))
                c.mcqDone++
                o.mcq?.let { m ->
                    c.mcqUsable++
                    if (m.options.count { it.code != null } == 3) c.mcqTagged++
                    history.add(QuestionDeduper.normalize(m.question))
                }
            }
            is NightTask.EvalTemplate -> {
                val (ti, level) = NightPlan.templateSlot(t.index, TemplateType.entries.size)
                val type = TemplateType.entries[ti]
                val r = templates.build(8, type, level, type.name.lowercase(), emptySet(), LogMeta("night-template-$night", type.name, 8))
                c.tplDone++
                r?.let {
                    if (it.llmText) c.tplLlmStory++
                    // Independent check of the Kotlin answer key: exp4j re-evaluates the template's expression.
                    val v = ExpressionVerifier.verify(it.mcq.expression, it.mcq.options.map { o -> o.text }, it.mcq.answer)
                    if (v.verdict == com.offlinetutor.domain.Verdict.VERIFIED) c.tplKeyVerified++
                }
            }
        }
    }

    companion object {
        private const val TAG = "NightShift"
        private const val KEY_NIGHT = "night"
        private const val KEY_PLAN = "plan"
        private const val KEY_INDEX = "index"
        private const val KEY_COUNTS = "counts"
        private const val KEY_SUMMARY = "summary"
        private const val KEY_LAST_DONE = "lastDone"
        private val EVAL_GRADES = listOf(6, 8, 10)
        /** Storage limit for pool growth: stop adding pool items above this database size. */
        const val STORAGE_LIMIT_BYTES = 64L * 1024 * 1024
    }
}
