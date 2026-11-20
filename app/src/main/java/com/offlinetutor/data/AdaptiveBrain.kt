package com.offlinetutor.data

import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.AttemptClass
import com.offlinetutor.domain.Backtrack
import com.offlinetutor.domain.BacktrackPlanner
import com.offlinetutor.domain.Concepts
import com.offlinetutor.domain.Confidence
import com.offlinetutor.domain.HealingPolicy
import com.offlinetutor.domain.MasteryModel
import com.offlinetutor.domain.Misconception
import com.offlinetutor.domain.OptionMeta
import com.offlinetutor.domain.OptionTag
import com.offlinetutor.domain.PrereqGraph

/** Everything the quiz UI needs to react to one answer. */
data class BrainResult(
    val attemptClass: AttemptClass,
    val misconception: OptionTag?,
    val mastery: MasteryModel.State,
    val masteryDelta: Double,
    val frustrated: Boolean,
    val fixStep: RoadmapStep? = null,
    val reviewStep: RoadmapStep? = null,
    val stuckTitle: String? = null,
    val shakyTitle: String? = null,
    val probeTitle: String? = null,
    val fixResolved: RoadmapStep? = null,
)

/**
 * Phase 1 "adaptive brain": runs after every answer. Mastery is always recorded; the behaviours
 * (self-healing, backtracking) are gated by their Settings toggles.
 */
class AdaptiveBrain(
    private val roadmaps: RoadmapDao,
    private val quiz: QuizDao,
    private val masteryDao: MasteryDao,
    private val hits: MisconceptionDao,
    private val probes: ProbeDao,
    private val flags: FeatureFlags,
) {
    fun observeMastery(profileId: Long) = masteryDao.observe(profileId)

    /** 1g: the answered attempts on a concept, used as evidence in the skill tree. */
    suspend fun attempts(profileId: Long, concept: String) = quiz.answeredForConcept(profileId, concept)

    suspend fun misconceptionHits(profileId: Long, concept: String) = hits.forConcept(profileId, concept)

    suspend fun masteryState(profileId: Long, concept: String): MasteryModel.State? =
        masteryDao.get(profileId, concept)?.let { MasteryModel.State(it.rating, it.level, it.attempts, it.correct, it.fastWrongStreak) }

    /** Prerequisite graph over the course's NORMAL steps (stored edges, or a linear chain if none). */
    suspend fun graph(profileId: Long, course: String): Pair<PrereqGraph, List<RoadmapStep>> {
        val steps = roadmaps.forCourse(profileId, course).filter { it.stepKind == StepKind.NORMAL }
        val nodes = steps.map { it.concept }
        val edges = steps.flatMap { s -> s.prereqList.map { s.concept to it } }
        val g = if (edges.isEmpty()) PrereqGraph.linear(nodes) else PrereqGraph.build(nodes, edges).graph
        return g to steps
    }

    suspend fun onAnswer(profile: Profile, q: QuizQuestion): BrainResult {
        val correct = q.correct == true
        val concept = q.concept?.takeIf { it.isNotBlank() } ?: Concepts.slug(q.subtopic)
        val words = q.stem.split(Regex("\\s+")).size
        val old = masteryState(profile.id, concept) ?: MasteryModel.State(level = q.difficulty)
        val out = MasteryModel.update(old, q.difficulty, correct, q.confident, q.responseMs, words)
        masteryDao.upsert(
            ConceptMastery(profile.id, concept, q.course, out.state.rating, out.state.level, out.state.attempts,
                out.state.correct, out.state.fastWrongStreak, System.currentTimeMillis()),
        )

        val tag = if (!correct) OptionMeta.parse(q.optionMeta).getOrNull(q.chosenIndex ?: -1) else null
        var result = BrainResult(
            attemptClass = Confidence.classify(correct, q.confident),
            misconception = tag, mastery = out.state, masteryDelta = out.delta, frustrated = out.frustrated,
        )

        // A fix step's check question: answering it correctly resolves the fix.
        val purpose = q.purpose.orEmpty()
        if (purpose.startsWith("FIX:") && correct) {
            purpose.removePrefix("FIX:").toLongOrNull()?.let { id ->
                roadmaps.get(id)?.let { step ->
                    roadmaps.update(step.copy(done = true))
                    step.misconception?.let { code -> hits.resolve(profile.id, step.targetConcept ?: concept, code) }
                    result = result.copy(fixResolved = step)
                }
            }
        }

        // 1e: record the misconception and maybe add a MICRO_FIX step.
        if (tag != null) {
            val history = hits.forConcept(profile.id, concept).map { HealingPolicy.Hit(it.code, it.confident, it.resolved) }
            hits.insert(MisconceptionHit(profileId = profile.id, course = q.course, conceptId = concept, code = tag.code.name,
                why = tag.why, questionId = q.id, confident = q.confident))
            if (flags.isOn(Feature.SELF_HEALING) && !purpose.startsWith("FIX:")) {
                val steps = roadmaps.forCourse(profile.id, q.course)
                val openFixCodes = steps.filter { it.stepKind == StepKind.MICRO_FIX && !it.done && it.targetConcept == concept }
                    .mapNotNull { it.misconception }.toSet()
                if (HealingPolicy.shouldInsertFix(history, HealingPolicy.Hit(tag.code.name, q.confident, false), openFixCodes)) {
                    result = result.copy(fixStep = insertFix(profile.id, q.course, concept, tag, steps))
                }
            }
        }

        // 1f: probes, then backtracking when the concept stays below threshold.
        if (flags.isOn(Feature.BACKTRACKING)) {
            if (purpose == "PROBE") {
                probes.get(profile.id, concept)?.let { p ->
                    if (p.remaining <= 1) {
                        probes.delete(profile.id, concept)
                        result = backtrack(profile.id, q.course, p.stuckConcept, result)
                    } else {
                        probes.upsert(p.copy(remaining = p.remaining - 1))
                    }
                }
            } else if (MasteryModel.isStuck(out.state)) {
                result = backtrack(profile.id, q.course, concept, result)
            }
        }
        return result
    }

    private suspend fun insertFix(profileId: Long, course: String, concept: String, tag: OptionTag, steps: List<RoadmapStep>): RoadmapStep {
        val anchor = steps.indexOfFirst { it.stepKind == StepKind.NORMAL && it.concept == concept }
            .takeIf { it >= 0 } ?: steps.indexOfFirst { !it.done }.takeIf { it >= 0 } ?: (steps.size - 1)
        val anchorStep = steps.getOrNull(anchor)
        val step = RoadmapStep(
            profileId = profileId, course = course, stepIndex = 0,
            title = "Fix: " + label(tag.code), goal = tag.why, subtopics = anchorStep?.subtopics ?: concept,
            source = "brain", conceptId = "fix-$concept-${tag.code.name.lowercase()}", kind = StepKind.MICRO_FIX.name,
            misconception = tag.code.name, reason = "Fix: " + label(tag.code), targetConcept = concept,
        )
        val index = (anchorStep?.stepIndex ?: steps.size) + 1
        return step.copy(id = roadmaps.insertAt(step, index), stepIndex = index)
    }

    private suspend fun backtrack(profileId: Long, course: String, stuck: String, base: BrainResult): BrainResult {
        val (g, normal) = graph(profileId, course)
        if (stuck !in g.nodes) return base
        val all = roadmaps.forCourse(profileId, course)
        val reviewing = all.filter { it.stepKind == StepKind.REVIEW && !it.done }.mapNotNull { it.targetConcept }.toSet()
        if (all.any { it.stepKind == StepKind.REVIEW && !it.done && it.reason?.contains(titleOf(normal, stuck)) == true }) return base
        val mastery = g.nodes.mapNotNull { n -> masteryState(profileId, n)?.let { n to it } }.toMap()
        val titleStuck = titleOf(normal, stuck)
        return when (val d = BacktrackPlanner.decide(stuck, g, mastery, reviewing)) {
            Backtrack.None -> base
            is Backtrack.Probe -> {
                probes.upsert(PendingProbe(profileId, d.concept, course, PROBE_QUESTIONS, stuck))
                base.copy(probeTitle = titleOf(normal, d.concept), stuckTitle = titleStuck)
            }
            is Backtrack.Review -> {
                val shaky = normal.first { it.concept == d.concept }
                val stuckStep = normal.first { it.concept == stuck }
                val reason = "Stuck on $titleStuck because ${shaky.title} is shaky"
                val step = RoadmapStep(
                    profileId = profileId, course = course, stepIndex = 0, title = "Review: ${shaky.title}",
                    goal = shaky.goal, subtopics = shaky.subtopics, source = "brain",
                    conceptId = "review-${d.concept}", kind = StepKind.REVIEW.name, reason = reason, targetConcept = d.concept,
                )
                val id = roadmaps.insertAt(step, stuckStep.stepIndex)
                base.copy(reviewStep = step.copy(id = id, stepIndex = stuckStep.stepIndex), stuckTitle = titleStuck, shakyTitle = shaky.title)
            }
        }
    }

    private fun titleOf(steps: List<RoadmapStep>, concept: String) = steps.firstOrNull { it.concept == concept }?.title ?: concept

    companion object {
        const val PROBE_QUESTIONS = 2

        /** English label stored in step titles; the UI shows the translated label from resources. */
        fun label(code: Misconception): String = code.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}
