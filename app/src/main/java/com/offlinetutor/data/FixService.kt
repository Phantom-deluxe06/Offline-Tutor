package com.offlinetutor.data

import com.offlinetutor.domain.ExplainLanguage
import com.offlinetutor.domain.Misconception
import com.offlinetutor.domain.MisconceptionInfo
import com.offlinetutor.domain.Prompts
import com.offlinetutor.llm.GenRequest
import com.offlinetutor.llm.TaskType

/**
 * 1e: a MICRO_FIX step = short lesson (explanation + one analogy) + one check question. The lesson is
 * generated the first time the step is opened (keeps answering fast) and saved on the step.
 * The step is resolved by AdaptiveBrain when the check question is answered correctly.
 */
class FixService(
    private val roadmaps: RoadmapDao,
    private val quiz: QuizRepository,
    private val ai: TutorAi,
    private val taxonomy: List<MisconceptionInfo>,
) {
    suspend fun step(id: Long) = roadmaps.get(id)

    fun describe(code: String?): String {
        val m = Misconception.of(code) ?: return code.orEmpty()
        return taxonomy.firstOrNull { it.code == m }?.description ?: m.name
    }

    suspend fun lesson(profile: Profile, step: RoadmapStep, onPartial: (String) -> Unit): String {
        step.body?.takeIf { it.isNotBlank() }?.let { return it }
        val target = step.targetConcept?.let { c -> roadmaps.forCourse(profile.id, step.course).firstOrNull { it.concept == c }?.title } ?: step.course
        val r = ai.run(
            GenRequest(TaskType.MICRO_FIX, Prompts.microFix(profile.grade, ExplainLanguage.of(profile.language), target,
                describe(step.misconception), step.goal, null)),
            onPartial = onPartial,
        )
        val text = r.text.trim()
        if (text.isNotBlank()) roadmaps.update(step.copy(body = text))
        return text
    }

    /** Reuses an unanswered check question for this step, otherwise generates one focused on the misconception. */
    suspend fun checkQuestion(profile: Profile, step: RoadmapStep, onPartial: (String) -> Unit): QuizQuestion {
        val purpose = "FIX:${step.id}"
        quiz.latestForPurpose(profile.id, purpose)?.takeIf { it.correct == null }?.let { return it }
        val focus = describe(step.misconception) + (step.goal.takeIf { it.isNotBlank() }?.let { " (e.g. $it)" } ?: "")
        return quiz.next(profile, step.course, null, onPartial, forcedConcept = step.targetConcept ?: step.concept,
            purpose = purpose, focus = focus).question
    }
}
