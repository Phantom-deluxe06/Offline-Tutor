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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.remember
import com.offlinetutor.data.ConceptMastery
import com.offlinetutor.data.Feature
import com.offlinetutor.data.MisconceptionHit
import com.offlinetutor.data.QuizQuestion
import com.offlinetutor.data.RoadmapStep
import com.offlinetutor.data.StepKind
import com.offlinetutor.domain.MasteryModel
import com.offlinetutor.domain.Misconception
import com.offlinetutor.domain.PrereqGraph
import com.offlinetutor.domain.SkillTreeRules
import com.offlinetutor.domain.StudyPlanner
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.ui.text.style.TextOverflow
import com.offlinetutor.ui.components.SkillTree
import com.offlinetutor.ui.components.TreeEdge
import com.offlinetutor.ui.components.TreeNode
import com.offlinetutor.ui.difficultyLabel
import com.offlinetutor.ui.theme.Palette
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
    val flags = c.flags.state
    val mastery = profile.flatMapLatest { p -> p?.let { c.brain.observeMastery(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ConceptMastery>())
    /** Answered quiz questions: every new answer re-runs the study plan. */
    val answered = profile.flatMapLatest { p -> p?.let { c.quiz.observeAnswered(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<QuizQuestion>())

    fun setMinutes(m: Int) {
        val p = profile.value ?: return
        viewModelScope.launch { c.profiles.update(p.copy(minutesPerDay = m)) }
    }

    /** 1g: evidence for the node opened in the sheet. */
    val evidence = MutableStateFlow<Evidence?>(null)
    private var job: Job? = null

    data class Evidence(val step: RoadmapStep, val concept: String, val attempts: List<QuizQuestion>, val mastery: ConceptMastery?, val hits: List<MisconceptionHit>)

    fun openEvidence(step: RoadmapStep) {
        val p = profile.value ?: return
        val concept = step.targetConcept?.takeIf { step.stepKind == StepKind.REVIEW } ?: step.concept
        viewModelScope.launch {
            evidence.value = Evidence(step, concept, c.brain.attempts(p.id, concept), mastery.value.firstOrNull { it.conceptId == concept },
                c.brain.misconceptionHits(p.id, concept))
        }
    }

    fun closeEvidence() { evidence.value = null }

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

    /** 3b warm-up: the roadmap is shown, so start filling the ready pool for its current step (background P1). */
    fun warmUp(course: String) {
        val p = profile.value ?: return
        c.appScope.launch { runCatching { c.quiz.warmUp(p, course) } }
    }

    fun stop() { job?.cancel() }

    fun toggle(step: RoadmapStep) {
        viewModelScope.launch { c.roadmaps.setDone(step.id, !step.done) }
    }
}

@Composable
fun RoadmapScreen(openQuiz: (String, String) -> Unit, openChat: (String) -> Unit, openModels: () -> Unit, openFix: (Long) -> Unit = {}) {
    val vm = appViewModel { RoadmapVm(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val gen by vm.gen.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val flags by vm.flags.collectAsStateWithLifecycle()
    val mastery by vm.mastery.collectAsStateWithLifecycle()
    val evidence by vm.evidence.collectAsStateWithLifecycle()
    val answered by vm.answered.collectAsStateWithLifecycle()
    val p = profile ?: return
    val courses = p.courseList
    if (courses.isEmpty()) return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val course = courses[tab.coerceIn(0, courses.lastIndex)]
    val look = subjectLook(course)
    val courseSteps = steps.filter { it.course == course }
    val currentIndex = courseSteps.indexOfFirst { !it.done }
    LaunchedEffect(p.id, course) { vm.warmUp(course) }
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
        if (steps.isNotEmpty()) {
            item {
                val titles = repairTitles(steps)
                val today = StudyPlanner.todayEpochDay()
                val plan = remember(steps, answered, p, today) {
                    val correct = answered.filter { it.correct == true && it.concept != null }.groupingBy { it.concept!! }.eachCount()
                    val tasks = steps.filter { it.course in courses }.map { s ->
                        StudyPlanner.Task(
                            s.id, s.course, s.stepIndex, s.title,
                            when (s.stepKind) { StepKind.MICRO_FIX -> StudyPlanner.Kind.FIX; StepKind.REVIEW -> StudyPlanner.Kind.REVIEW; else -> StudyPlanner.Kind.STEP },
                            s.concept, s.done, s.subtopicList.firstOrNull() ?: s.title,
                        )
                    }
                    StudyPlanner.schedule(tasks, courses, p.levelMap, correct, today, p.dailyMinutes, p.targetDate)
                }
                WeekPlanCard(
                    plan, p.dailyMinutes, vm::setMinutes, titles, answered.maxOfOrNull { it.answeredAt ?: 0L }?.takeIf { it > 0 },
                    onSlot = { s -> if (s.kind == StudyPlanner.Kind.FIX) openFix(s.stepId) else openQuiz(s.course, s.subtopic) },
                )
            }
        }

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
            if (flags[Feature.SKILL_TREE] != false) {
                item {
                    val titles = repairTitles(courseSteps)
                    val (nodes, edges) = remember(courseSteps, mastery, titles) { buildTree(courseSteps, mastery, titles) }
                    SoftCard {
                        Text(stringResource(R.string.skill_tree), style = MaterialTheme.typography.titleMedium)
                        SkillTree(nodes, edges, look.color, onNode = { n ->
                            val s = courseSteps.firstOrNull { it.id == n.stepId } ?: return@SkillTree
                            if (s.stepKind == StepKind.MICRO_FIX) openFix(s.id) else vm.openEvidence(s)
                        })
                    }
                }
            }
            itemsIndexed(courseSteps, key = { _, s -> s.id }) { i, s ->
                JourneyStep(
                    step = s, index = i, last = i == courseSteps.lastIndex, current = i == currentIndex, color = look.color,
                    modifier = Modifier.enterAnimation(i),
                    onToggle = { vm.toggle(s) },
                    onQuiz = { sub -> if (s.stepKind == StepKind.MICRO_FIX) openFix(s.id) else openQuiz(course, sub) },
                    onAsk = { openChat("Explain \"${s.title}\" in $course for my grade.") },
                    onOpen = { if (s.stepKind == StepKind.MICRO_FIX) openFix(s.id) else vm.openEvidence(s) },
                )
            }
            item {
                ChunkyOutlineButton(stringResource(R.string.regenerate), { vm.generate(course) }, Modifier.fillMaxWidth(),
                    icon = Icons.Filled.Refresh, enabled = g == null)
            }
        }
    }

    evidence?.let { ev -> EvidenceSheet(ev, look.color, onDismiss = vm::closeEvidence, onQuiz = { vm.closeEvidence(); openQuiz(course, ev.step.subtopicList.firstOrNull() ?: ev.step.title) }) }
}

/** Translated titles for repair steps (stored titles are English). */
@Composable
private fun repairTitles(steps: List<RoadmapStep>): Map<Long, String> = steps.filter { it.stepKind != StepKind.NORMAL }.associate { s ->
    s.id to when (s.stepKind) {
        StepKind.MICRO_FIX -> stringResource(R.string.fix_title, Misconception.of(s.misconception)?.let { misconceptionLabel(it) } ?: s.goal)
        else -> stringResource(R.string.review_badge) + ": " + (steps.firstOrNull { it.stepKind == StepKind.NORMAL && it.concept == s.targetConcept }?.title ?: s.title)
    }
}

/** Builds skill-tree nodes (rows = prerequisite depth) and edges from the course's steps. */
private fun buildTree(steps: List<RoadmapStep>, mastery: List<ConceptMastery>, titles: Map<Long, String>): Pair<List<TreeNode>, List<TreeEdge>> {
    val normal = steps.filter { it.stepKind == StepKind.NORMAL }
    val ids = normal.map { it.concept }
    val edges = normal.flatMap { s -> s.prereqList.map { s.concept to it } }
    val graph = if (edges.isEmpty()) PrereqGraph.linear(ids) else PrereqGraph.build(ids, edges).graph
    val m = mastery.associateBy { it.conceptId }
    fun mastered(c: String) = m[c]?.let { MasteryModel.isMastered(MasteryModel.State(it.rating, it.level, it.attempts, it.correct, it.fastWrongStreak)) } == true
    val doneOrMastered = normal.filter { it.done || mastered(it.concept) }.map { it.concept }.toSet()
    val nodes = ArrayList<TreeNode>()
    val outEdges = ArrayList<TreeEdge>()
    for (s in normal) {
        val ok = graph.prereqs(s.concept).all { it in doneOrMastered }
        nodes += TreeNode(s.concept, s.title, SkillTreeRules.state(s.kind, s.done, mastered(s.concept), ok), graph.depth(s.concept), s.id)
        graph.prereqs(s.concept).forEach { outEdges += TreeEdge(it, s.concept, dashed = false) }
    }
    // Repair steps sit in the row of the concept they repair and link to it with a dashed edge.
    for (s in steps.filter { it.stepKind != StepKind.NORMAL }) {
        val target = s.targetConcept ?: continue
        if (target !in ids) continue
        val id = "${s.kind}-${s.id}"
        val title = titles[s.id] ?: s.title
        nodes += TreeNode(id, title, SkillTreeRules.state(s.kind, s.done, false, true), graph.depth(target), s.id)
        if (s.stepKind == StepKind.MICRO_FIX) outEdges += TreeEdge(target, id, dashed = true)
        else {
            // REVIEW: feeds into the step right after it (the stuck one).
            val stuck = steps.firstOrNull { it.stepIndex > s.stepIndex && it.stepKind == StepKind.NORMAL }?.concept
            outEdges += TreeEdge(id, stuck ?: target, dashed = true)
        }
    }
    return nodes to outEdges
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EvidenceSheet(ev: RoadmapVm.Evidence, color: androidx.compose.ui.graphics.Color, onDismiss: () -> Unit, onQuiz: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(ev.step.title, style = MaterialTheme.typography.titleLarge)
            ev.step.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = color) }
            val m = ev.mastery
            if (m != null) {
                val state = MasteryModel.State(m.rating, m.level, m.attempts, m.correct, m.fastWrongStreak)
                Text(
                    stringResource(if (MasteryModel.isMastered(state)) R.string.mastered else R.string.not_mastered_yet) + " · " +
                        stringResource(R.string.mastery_numbers, m.rating.toInt(), m.correct, m.attempts),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(stringResource(R.string.why_app_thinks), style = MaterialTheme.typography.titleMedium)
            // Evidence = correct answers; confident and fast ones count most.
            val evidence = ev.attempts.filter { it.correct == true }.takeLast(8).reversed()
            if (evidence.isEmpty()) Text(stringResource(R.string.no_evidence), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
            evidence.forEach { a ->
                val fast = MasteryModel.isFast(a.responseMs, a.stem.split(Regex("\\s+")).size)
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Palette.correct, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.stem, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                difficultyLabel(a.difficulty),
                                when (a.confident) { true -> stringResource(R.string.sure); false -> stringResource(R.string.not_sure); null -> null },
                                a.responseMs?.let { "${it / 1000}s" + if (fast) " ⚡" else "" },
                                a.answeredAt?.let { fmt.format(java.util.Date(it)) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val open = ev.hits.filter { !it.resolved }
            if (open.isNotEmpty()) {
                Text(stringResource(R.string.open_misconceptions), style = MaterialTheme.typography.titleMedium)
                open.groupBy { it.code }.forEach { (code, list) ->
                    Text("• " + (Misconception.of(code)?.let { misconceptionLabel(it) } ?: code) + " ×${list.size} — " + list.last().why,
                        style = MaterialTheme.typography.bodyMedium, color = Palette.wrong)
                }
            }
            ChunkyButton(stringResource(R.string.quiz_me), onQuiz, Modifier.fillMaxWidth(), color = color, icon = Icons.Filled.Quiz)
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
    onOpen: () -> Unit = {},
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
            // 1e / 1f badges for repair steps.
            when (step.stepKind) {
                StepKind.MICRO_FIX -> com.offlinetutor.ui.design.Pill(
                    stringResource(R.string.fix_title, Misconception.of(step.misconception)?.let { misconceptionLabel(it) } ?: step.title),
                    Palette.marigold, icon = Icons.Filled.Healing, textColor = MaterialTheme.colorScheme.onSurface,
                )
                StepKind.REVIEW -> com.offlinetutor.ui.design.Pill(stringResource(R.string.review_badge), Color(0xFF8E5BA8), icon = Icons.Filled.Replay)
                StepKind.NORMAL -> Unit
            }
            // Fix steps: the badge already names the mistake; the stored English title is not shown.
            if (step.stepKind != StepKind.MICRO_FIX) {
                Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.bounceClick(onClick = onOpen))
            }
            if (step.stepKind == StepKind.REVIEW) step.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E5BA8))
            }
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

/** "This week's plan": Kotlin-scheduled steps, fixes and reviews per day, with the minutes-per-day setting. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekPlanCard(
    plan: StudyPlanner.Plan, minutes: Int, onMinutes: (Int) -> Unit, titles: Map<Long, String>,
    lastQuizAt: Long?, onSlot: (StudyPlanner.Slot) -> Unit,
) {
    val today = plan.days.firstOrNull()?.epochDay ?: StudyPlanner.todayEpochDay()
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DateRange, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.plan_this_week), style = MaterialTheme.typography.titleMedium)
        }
        val target = plan.targetDay
        val finish = plan.finishDay ?: today
        val (status, warn) = when {
            plan.allDone -> stringResource(R.string.plan_all_done) to false
            target != null && plan.targetPassed -> stringResource(R.string.plan_target_passed, StudyPlanner.format(target)) to true
            target == null -> stringResource(R.string.plan_no_target, StudyPlanner.format(finish)) to false
            plan.onTrack -> stringResource(R.string.plan_on_track, StudyPlanner.format(finish), StudyPlanner.format(target)) to false
            else -> stringResource(R.string.plan_behind, plan.neededPerDay ?: minutes, StudyPlanner.format(target)) to true
        }
        Text(status, style = MaterialTheme.typography.bodyMedium,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.minutes_per_day), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (StudyPlanner.MINUTE_CHOICES + minutes).distinct().sorted().forEach { m ->
                ChoiceChip(stringResource(R.string.min_n, m), m == minutes, { onMinutes(m) })
            }
        }
        plan.days.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    if (d.epochDay == today) stringResource(R.string.plan_today) else StudyPlanner.formatShort(d.epochDay),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp),
                    fontWeight = if (d.epochDay == today) FontWeight.Bold else FontWeight.Normal,
                    color = if (d.epochDay == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Column(Modifier.weight(1f)) {
                    if (d.slots.isEmpty()) Text(stringResource(R.string.plan_free), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.slots.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { onSlot(s) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(subjectLook(s.course).color, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            val name = (titles[s.stepId] ?: s.title) + if (s.parts > 1) " (${s.part}/${s.parts})" else ""
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.min_n, s.minutes), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.plan_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lastQuizAt?.let {
            Text(stringResource(R.string.plan_updated, java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
