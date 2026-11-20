package com.offlinetutor.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offlinetutor.AppContainer
import com.offlinetutor.R
import com.offlinetutor.data.RoadmapStep
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.ui.ModelStatusCard
import com.offlinetutor.ui.appViewModel
import com.offlinetutor.ui.courseLabel
import com.offlinetutor.ui.design.ChoiceChip
import com.offlinetutor.ui.design.ChunkyButton
import com.offlinetutor.ui.design.ChunkyOutlineButton
import com.offlinetutor.ui.design.EmptyState
import com.offlinetutor.ui.design.ProgressRing
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.ThinkingCard
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.design.enterAnimation
import com.offlinetutor.ui.design.subjectLook
import com.offlinetutor.ui.theme.LocalExtras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoadmapGen(val course: String, val partial: String = "")

sealed interface RoadmapNote {
    data object Template : RoadmapNote
    data object NoModel : RoadmapNote
    data class Error(val msg: String) : RoadmapNote
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoadmapVm(private val c: AppContainer) : ViewModel() {
    val profile = c.activeProfile
    val engine = c.engine.state
    val steps = profile.flatMapLatest { p -> p?.let { c.roadmaps.observe(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<RoadmapStep>())
    val gen = MutableStateFlow<RoadmapGen?>(null)
    val note = MutableStateFlow<RoadmapNote?>(null)
    private var job: Job? = null

    fun generate(course: String) {
        val p = profile.value ?: return
        if (job?.isActive == true) return
        note.value = null
        job = viewModelScope.launch {
            gen.value = RoadmapGen(course)
            try {
                val o = c.roadmaps.generate(p, course) { partial -> gen.update { it?.copy(partial = partial) } }
                if (o.source == "template") note.value = RoadmapNote.Template
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelMissingException) {
                note.value = RoadmapNote.NoModel
            } catch (e: Throwable) {
                note.value = RoadmapNote.Error(e.message ?: e.javaClass.simpleName)
            } finally {
                gen.value = null
            }
        }
    }

    fun stop() { job?.cancel() }

    fun toggle(step: RoadmapStep) {
        viewModelScope.launch { c.roadmaps.setDone(step.id, !step.done) }
    }
}

@Composable
fun RoadmapScreen(openQuiz: (String, String) -> Unit, openChat: (String) -> Unit, openModels: () -> Unit) {
    val vm = appViewModel { RoadmapVm(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val gen by vm.gen.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val p = profile ?: return
    val courses = p.courseList
    if (courses.isEmpty()) return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val course = courses[tab.coerceIn(0, courses.lastIndex)]
    val look = subjectLook(course)
    val courseSteps = steps.filter { it.course == course }
    val currentIndex = courseSteps.indexOfFirst { !it.done }
    val g = gen

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                courses.forEachIndexed { i, cName ->
                    val l = subjectLook(cName)
                    ChoiceChip(courseLabel(cName), i == tab, { tab = i }, l.color, l.icon)
                }
            }
        }
        item { ModelStatusCard(engine, openModels) }

        if (g != null && g.course == course) {
            item {
                ThinkingCard(
                    listOf(stringResource(R.string.think_r1), stringResource(R.string.think_r2), stringResource(R.string.think_r3)),
                    progressText = stringResource(R.string.steps_written, g.partial.lines().count { it.trim().firstOrNull()?.isDigit() == true }),
                    onStop = vm::stop, stopLabel = stringResource(R.string.stop),
                )
            }
        }
        when (val n = note) {
            RoadmapNote.Template -> item { NoteText(stringResource(R.string.roadmap_template_note)) }
            RoadmapNote.NoModel -> item { NoteText(stringResource(R.string.model_missing_body)) }
            is RoadmapNote.Error -> item { NoteText(n.msg) }
            null -> Unit
        }

        if (courseSteps.isEmpty() && (g == null || g.course != course)) {
            item {
                EmptyState(Icons.Filled.Map, stringResource(R.string.roadmap_empty, courseLabel(course)), stringResource(R.string.roadmap_empty_body), look.color) {
                    ChunkyButton(stringResource(R.string.generate_roadmap), { vm.generate(course) }, Modifier.fillMaxWidth(),
                        color = look.color, icon = Icons.Filled.AutoAwesome, enabled = g == null)
                }
            }
        } else if (courseSteps.isNotEmpty()) {
            item {
                val done = courseSteps.count { it.done }
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(done.toFloat() / courseSteps.size, 72.dp, 9.dp, look.color) {
                            Text("${done * 100 / courseSteps.size}%", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(courseLabel(course), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.steps_done, done, courseSteps.size), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            itemsIndexed(courseSteps, key = { _, s -> s.id }) { i, s ->
                JourneyStep(
                    step = s, index = i, last = i == courseSteps.lastIndex, current = i == currentIndex, color = look.color,
                    modifier = Modifier.enterAnimation(i),
                    onToggle = { vm.toggle(s) },
                    onQuiz = { sub -> openQuiz(course, sub) },
                    onAsk = { openChat("Explain \"${s.title}\" in $course for my grade.") },
                )
            }
            item {
                ChunkyOutlineButton(stringResource(R.string.regenerate), { vm.generate(course) }, Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Refresh, enabled = g == null)
            }
        }
    }
}

@Composable
private fun NoteText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JourneyStep(
    step: RoadmapStep,
    index: Int,
    last: Boolean,
    current: Boolean,
    color: Color,
    modifier: Modifier,
    onToggle: () -> Unit,
    onQuiz: (String) -> Unit,
    onAsk: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.outline
    val pulse = rememberInfiniteTransition(label = "node")
    val ring by pulse.animateFloat(1f, if (current) 1.25f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
    Row(modifier.height(IntrinsicSize.Min)) {
        // Path column: node + connector to the next step.
        Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!last) {
                Canvas(Modifier.width(4.dp).fillMaxHeight().padding(top = 44.dp)) {
                    drawLine(
                        if (step.done) color else muted, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height + 16.dp.toPx()),
                        strokeWidth = 5f, cap = StrokeCap.Round,
                        pathEffect = if (step.done) null else PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                    )
                }
            }
            if (current) Box(Modifier.padding(top = 2.dp).size(44.dp).scale(ring).background(color.copy(alpha = 0.18f), CircleShape))
            Box(
                Modifier.padding(top = 6.dp).size(36.dp).bounceClick(onClick = onToggle)
                    .background(if (step.done) color else MaterialTheme.colorScheme.surface, CircleShape)
                    .border(3.dp, if (step.done || current) color else muted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (step.done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                else Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = if (current) color else muted, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        SoftCard(
            Modifier.weight(1f).padding(bottom = 4.dp),
            edge = if (current) color.copy(alpha = 0.6f) else LocalExtras.current.cardEdge,
        ) {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            if (step.goal.isNotBlank()) Text(step.goal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                step.subtopicList.forEach { sub ->
                    Text(
                        sub,
                        Modifier.bounceClick { onQuiz(sub) }.background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, color = color,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionLink(Icons.Filled.Quiz, stringResource(R.string.quiz_me), color) { onQuiz(step.subtopicList.firstOrNull() ?: step.title) }
                ActionLink(Icons.Filled.Forum, stringResource(R.string.ask_tutor), MaterialTheme.colorScheme.primary, onAsk)
            }
        }
    }
}

@Composable
private fun ActionLink(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.bounceClick(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
