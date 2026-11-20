package com.offlinetutor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offlinetutor.AppContainer
import com.offlinetutor.R
import com.offlinetutor.data.QuizQuestion
import com.offlinetutor.data.RoadmapStep
import com.offlinetutor.domain.Misconception
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.ui.ModelStatusCard
import com.offlinetutor.ui.appViewModel
import com.offlinetutor.ui.design.ChunkyButton
import com.offlinetutor.ui.design.ChunkyOutlineButton
import com.offlinetutor.ui.design.ConfettiBurst
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.ThinkingCard
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.theme.LocalExtras
import com.offlinetutor.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FixUi(
    val step: RoadmapStep? = null,
    val lesson: String? = null,
    val lessonLoading: Boolean = false,
    val question: QuizQuestion? = null,
    val questionLoading: Boolean = false,
    val resolvedTick: Int = 0,
    val error: String? = null,
    val noModel: Boolean = false,
)

class FixVm(private val c: AppContainer, private val stepId: Long) : ViewModel() {
    val ui = MutableStateFlow(FixUi())
    val engine = c.engine.state
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val step = c.fixes.step(stepId) ?: return@launch
            ui.update { it.copy(step = step, lesson = step.body) }
            if (step.body.isNullOrBlank()) loadLesson()
            else {
                val p = c.activeProfile.filterNotNull().first()
                c.quiz.latestForPurpose(p.id, "FIX:$stepId")?.let { q -> ui.update { it.copy(question = q) } }
            }
        }
    }

    fun loadLesson() = withStep { p, step ->
        ui.update { it.copy(lessonLoading = true, lesson = "") }
        val text = c.fixes.lesson(p, step) { partial -> ui.update { it.copy(lesson = partial) } }
        ui.update { it.copy(lesson = text, lessonLoading = false, step = c.fixes.step(stepId)) }
    }

    fun loadQuestion() = withStep { p, step ->
        ui.update { it.copy(questionLoading = true, question = null) }
        val q = c.fixes.checkQuestion(p, step) { }
        ui.update { it.copy(question = q, questionLoading = false) }
    }

    fun answer(i: Int) {
        val q = ui.value.question ?: return
        if (q.chosenIndex != null) return
        val p = c.activeProfile.value ?: return
        viewModelScope.launch {
            val (a, br) = c.quiz.answer(p, q, i, null, null)
            ui.update {
                it.copy(question = a, step = c.fixes.step(stepId),
                    resolvedTick = it.resolvedTick + if (br.fixResolved != null) 1 else 0)
            }
        }
    }

    private fun withStep(block: suspend (com.offlinetutor.data.Profile, RoadmapStep) -> Unit) {
        if (job?.isActive == true) return
        val step = ui.value.step ?: return
        job = viewModelScope.launch {
            val p = c.activeProfile.filterNotNull().first()
            try {
                block(p, step)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelMissingException) {
                ui.update { it.copy(noModel = true, lessonLoading = false, questionLoading = false) }
            } catch (e: Throwable) {
                ui.update { it.copy(error = e.message, lessonLoading = false, questionLoading = false) }
            }
        }
    }

    fun stop() { job?.cancel(); ui.update { it.copy(lessonLoading = false, questionLoading = false) } }
}

@Composable
fun FixScreen(stepId: Long, openModels: () -> Unit) {
    val vm = appViewModel(key = "fix:$stepId") { FixVm(it, stepId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val step = ui.step ?: return
    val code = Misconception.of(step.misconception)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Healing, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.fix_title, code?.let { misconceptionLabel(it) } ?: step.title),
                        style = MaterialTheme.typography.titleLarge)
                    if (step.goal.isNotBlank()) Text(step.goal, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (step.done) Icon(Icons.Filled.CheckCircle, stringResource(R.string.fix_resolved), tint = Palette.correct, modifier = Modifier.size(32.dp))
            }
            if (ui.noModel) ModelStatusCard(engine, openModels)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // Lesson: explanation + analogy (generated once, then saved on the step).
            if (ui.lessonLoading && ui.lesson.isNullOrBlank()) {
                ThinkingCard(listOf(stringResource(R.string.think_fix1), stringResource(R.string.think_fix2)),
                    onStop = vm::stop, stopLabel = stringResource(R.string.stop))
            } else if (!ui.lesson.isNullOrBlank()) {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.mini_lesson), style = MaterialTheme.typography.titleMedium)
                    }
                    ui.lesson!!.lines().filter { it.isNotBlank() }.forEach { line ->
                        val analogy = line.trim().startsWith("Analogy", ignoreCase = true)
                        Text(
                            line.trim(),
                            Modifier.then(if (analogy) Modifier.background(Palette.marigold.copy(alpha = 0.18f), RoundedCornerShape(10.dp)).padding(10.dp) else Modifier),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (analogy) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            } else {
                ChunkyButton(stringResource(R.string.mini_lesson), vm::loadLesson, Modifier.fillMaxWidth(), icon = Icons.Filled.Lightbulb)
            }

            // Check question: answering it correctly resolves the fix.
            val q = ui.question
            when {
                ui.questionLoading -> ThinkingCard(listOf(stringResource(R.string.think_q2), stringResource(R.string.think_q3)),
                    onStop = vm::stop, stopLabel = stringResource(R.string.stop))
                q == null && !ui.lessonLoading && !ui.lesson.isNullOrBlank() && !step.done ->
                    ChunkyButton(stringResource(R.string.check_understanding), vm::loadQuestion, Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondary, icon = Icons.Filled.Quiz)
                q != null -> CheckQuestion(q, vm::answer)
            }
            if (q?.correct == false && !step.done) {
                ChunkyOutlineButton(stringResource(R.string.try_another), vm::loadQuestion, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh)
            }
            AnimatedVisibility(step.done, enter = slideInVertically { it / 2 } + fadeIn()) {
                SoftCard(color = Palette.correctBg, edge = Palette.correct.copy(alpha = 0.5f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Palette.correct, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.fix_resolved), style = MaterialTheme.typography.titleLarge, color = Palette.correct)
                    }
                }
            }
        }
        ConfettiBurst(ui.resolvedTick)
    }
}

@Composable
private fun CheckQuestion(q: QuizQuestion, onAnswer: (Int) -> Unit) {
    val answered = q.chosenIndex != null
    SoftCard {
        Text(stringResource(R.string.check_understanding), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(q.stem, style = MaterialTheme.typography.titleLarge)
    }
    q.optionList.forEachIndexed { i, opt ->
        val color = when {
            answered && i == q.answerIndex -> Palette.correct
            answered && i == q.chosenIndex -> Palette.wrong
            else -> LocalExtras.current.cardEdge
        }
        Row(
            Modifier.fillMaxWidth().bounceClick(enabled = !answered) { onAnswer(i) }
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .border(2.dp, color, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${'A' + i}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(opt, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (answered) Text(q.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
