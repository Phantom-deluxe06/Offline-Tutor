package com.offlinetutor.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SelfImprovement
import com.offlinetutor.AppContainer
import com.offlinetutor.R
import com.offlinetutor.data.BrainResult
import com.offlinetutor.data.Feature
import com.offlinetutor.data.QuizQuestion
import com.offlinetutor.domain.AttemptClass
import com.offlinetutor.domain.Misconception
import com.offlinetutor.domain.OptionMeta
import com.offlinetutor.domain.OptionTag
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.ExplainLanguage
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.ui.BetaChip
import com.offlinetutor.ui.ModelStatusCard
import com.offlinetutor.ui.appViewModel
import com.offlinetutor.ui.courseLabel
import com.offlinetutor.ui.design.ChoiceChip
import com.offlinetutor.ui.design.ChunkyButton
import com.offlinetutor.ui.design.ChunkyOutlineButton
import com.offlinetutor.ui.design.ConfettiBurst
import com.offlinetutor.ui.design.EmptyState
import com.offlinetutor.ui.design.Pill
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.ThinkingCard
import com.offlinetutor.ui.design.TypingDots
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.design.pop
import com.offlinetutor.ui.design.shake
import com.offlinetutor.ui.design.subjectLook
import com.offlinetutor.ui.difficultyColor
import com.offlinetutor.ui.difficultyLabel
import com.offlinetutor.ui.languageLabel
import com.offlinetutor.ui.theme.LocalExtras
import com.offlinetutor.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuizUi(
    val course: String? = null,
    val subtopic: String? = null,
    val level: Int = AdaptiveDifficulty.EASY,
    val question: QuizQuestion? = null,
    val loading: Boolean = false,
    val partial: String = "",
    val fallbackNote: String? = null,
    val error: String? = null,
    val noModel: Boolean = false,
    val sessionCorrect: Int = 0,
    val sessionTotal: Int = 0,
    val explain: String? = null,
    val explaining: Boolean = false,
    /** Increments drive one-shot animations (confetti / shake). */
    val correctTick: Int = 0,
    val wrongTick: Int = 0,
    /** +1 level up, -1 level down, 0 none: shown as a banner after an answer. */
    val levelDelta: Int = 0,
    /** 1c: option picked but not yet submitted (waiting for Sure / Not sure). */
    val selected: Int? = null,
    /** elapsedRealtime when the question appeared, for responseMs. */
    val shownAt: Long = 0,
    /** Adaptive-brain reaction to the last answer. */
    val brain: BrainResult? = null,
)

class QuizVm(private val c: AppContainer, initialCourse: String?, private val initialSubtopic: String?) : ViewModel() {
    val ui = MutableStateFlow(QuizUi(subtopic = initialSubtopic))
    val engine = c.engine.state
    val profile = c.activeProfile
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val p = c.activeProfile.filterNotNull().first()
            val course = initialCourse?.takeIf { it in p.courseList } ?: p.courseList.firstOrNull() ?: return@launch
            applyCourse(p.id, course, keepSubtopic = true)
            // Resume an unanswered question (e.g. after the app was closed mid-quiz).
            val pending = c.quiz.pending(p.id)
            if (pending != null && pending.course == course && initialSubtopic == null && pending.purpose?.startsWith("FIX:") != true) {
                ui.update { it.copy(question = pending, shownAt = SystemClock.elapsedRealtime()) }
            }
        }
    }

    val confidenceOn get() = c.flags.isOn(Feature.CONFIDENCE)

    fun setCourse(course: String) {
        val p = profile.value ?: return
        if (job?.isActive == true) return
        viewModelScope.launch { applyCourse(p.id, course, keepSubtopic = false) }
    }

    private suspend fun applyCourse(profileId: Long, course: String, keepSubtopic: Boolean) {
        val level = c.quiz.displayLevel(profileId, course)
        ui.update {
            it.copy(course = course, level = level, question = null, explain = null, fallbackNote = null, error = null,
                subtopic = if (keepSubtopic) it.subtopic else null, levelDelta = 0)
        }
    }

    fun next() {
        val p = profile.value ?: return
        val course = ui.value.course ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            ui.update { it.copy(loading = true, partial = "", error = null, noModel = false, explain = null, fallbackNote = null, question = null, levelDelta = 0, selected = null, brain = null) }
            try {
                val n = c.quiz.next(p, course, ui.value.subtopic, { partial -> ui.update { it.copy(partial = partial) } })
                ui.update { it.copy(question = n.question, level = n.question.difficulty, fallbackNote = n.note, loading = false, shownAt = SystemClock.elapsedRealtime()) }
            } catch (e: CancellationException) {
                ui.update { it.copy(loading = false) }
                throw e
            } catch (e: ModelMissingException) {
                ui.update { it.copy(loading = false, noModel = true) }
            } catch (e: Throwable) {
                ui.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun stop() { job?.cancel() }

    /** 1c: with confidence marking on, a tap only selects; Sure / Not sure submits. */
    fun select(index: Int) {
        val q = ui.value.question ?: return
        if (q.chosenIndex != null) return
        if (confidenceOn) ui.update { it.copy(selected = index) } else submit(index, null)
    }

    fun submit(index: Int, confident: Boolean?) {
        val q = ui.value.question ?: return
        if (q.chosenIndex != null) return
        val p = profile.value ?: return
        val responseMs = (SystemClock.elapsedRealtime() - ui.value.shownAt).takeIf { ui.value.shownAt > 0 }
        viewModelScope.launch {
            val before = ui.value.level
            val (a, br) = c.quiz.answer(p, q, index, confident, responseMs)
            val newLevel = if (c.flags.isOn(Feature.ELO_DIFFICULTY)) br.mastery.level else c.quiz.state(p.id, q.course).level
            val ok = a.correct == true
            ui.update {
                it.copy(
                    question = a, level = newLevel, brain = br, selected = null,
                    sessionCorrect = it.sessionCorrect + if (ok) 1 else 0,
                    sessionTotal = it.sessionTotal + 1,
                    correctTick = it.correctTick + if (ok) 1 else 0,
                    wrongTick = it.wrongTick + if (ok) 0 else 1,
                    levelDelta = (newLevel - before).coerceIn(-1, 1),
                )
            }
        }
    }

    fun clearLevelDelta() = ui.update { it.copy(levelDelta = 0) }

    fun explain() {
        val p = profile.value ?: return
        val q = ui.value.question ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            ui.update { it.copy(explaining = true, explain = "") }
            try {
                val text = c.chat.explain(p, ExplainLanguage.of(p.language), q) { partial -> ui.update { it.copy(explain = partial) } }
                ui.update { it.copy(explain = text, explaining = false) }
            } catch (e: CancellationException) {
                ui.update { it.copy(explaining = false) }
                throw e
            } catch (e: Throwable) {
                ui.update { it.copy(explaining = false, explain = e.message) }
            }
        }
    }
}

@Composable
fun QuizScreen(initialCourse: String?, initialSubtopic: String?, openChat: (String) -> Unit, openModels: () -> Unit, openFix: (Long) -> Unit = {}) {
    val vm = appViewModel(key = "quiz:$initialCourse:$initialSubtopic") { QuizVm(it, initialCourse, initialSubtopic) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val p = profile ?: return
    val lang = ExplainLanguage.of(p.language)

    LaunchedEffect(ui.levelDelta) { if (ui.levelDelta != 0) { delay(2600); vm.clearLevelDelta() } }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.courseList.forEach { course ->
                    val look = subjectLook(course)
                    ChoiceChip(courseLabel(course), course == ui.course, { vm.setCourse(course) }, look.color, look.icon)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelMeter(ui.level, Modifier.weight(1f))
                if (ui.sessionTotal > 0) {
                    Pill(stringResource(R.string.session_short, ui.sessionCorrect, ui.sessionTotal), Palette.correct, icon = Icons.Filled.Check)
                }
            }
            ui.subtopic?.let { Pill(stringResource(R.string.topic_x, it), MaterialTheme.colorScheme.primary) }
            if (ui.noModel) ModelStatusCard(engine, openModels)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val state = when {
                ui.loading -> "loading"
                ui.question == null -> "intro"
                else -> "q${ui.question!!.id}"
            }
            AnimatedContent(
                targetState = state,
                transitionSpec = { (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 8 }) togetherWith fadeOut(tween(150)) },
                label = "quiz",
            ) { s ->
                when {
                    s == "loading" -> ThinkingCard(
                        listOf(stringResource(R.string.think_q1), stringResource(R.string.think_q2), stringResource(R.string.think_q3)),
                        onStop = vm::stop, stopLabel = stringResource(R.string.stop),
                    )
                    s == "intro" -> EmptyState(Icons.Filled.Quiz, stringResource(R.string.adaptive_quiz), stringResource(R.string.quiz_intro),
                        color = ui.course?.let { subjectLook(it).color } ?: MaterialTheme.colorScheme.primary) {
                        ChunkyButton(stringResource(R.string.start_quiz), vm::next, Modifier.fillMaxWidth(),
                            enabled = ui.course != null, icon = Icons.Filled.PlayArrow)
                    }
                    else -> ui.question?.let { q ->
                        QuestionBlock(q, ui, lang, vm.confidenceOn, vm::select, vm::submit, vm::next, vm::explain, openFix) {
                            openChat("I didn't understand this question: ${q.stem}")
                        }
                    }
                }
            }
        }

        ConfettiBurst(ui.correctTick)

        AnimatedVisibility(
            ui.levelDelta != 0,
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        ) { LevelBanner(ui.levelDelta, ui.level) }
    }
}

@Composable
private fun LevelMeter(level: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..3).forEach { l ->
            val on = l <= level
            val w by animateFloatAsState(if (on) 1f else 0f, tween(450), label = "lvl$l")
            Box(Modifier.width(34.dp).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
                Box(Modifier.fillMaxWidth(w).height(10.dp).background(difficultyColor(level), RoundedCornerShape(5.dp)))
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(difficultyLabel(level), style = MaterialTheme.typography.labelLarge, color = difficultyColor(level))
    }
}

@Composable
private fun LevelBanner(delta: Int, level: Int) {
    val up = delta > 0
    Row(
        Modifier.background(if (up) Palette.marigold else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (up) Icons.Filled.EmojiEvents else Icons.Filled.TrendingDown, null, tint = if (up) Color(0xFF3A2300) else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(
            if (up) stringResource(R.string.level_up, difficultyLabel(level)) else stringResource(R.string.level_down, difficultyLabel(level)),
            style = MaterialTheme.typography.titleMedium, color = if (up) Color(0xFF3A2300) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private enum class OptState { NEUTRAL, SELECTED, CORRECT, WRONG, DIM }

@Composable
private fun QuestionBlock(
    q: QuizQuestion,
    ui: QuizUi,
    lang: ExplainLanguage,
    confidenceOn: Boolean,
    onSelect: (Int) -> Unit,
    onSubmit: (Int, Boolean?) -> Unit,
    onNext: () -> Unit,
    onExplain: () -> Unit,
    openFix: (Long) -> Unit,
    onAsk: () -> Unit,
) {
    val answered = q.chosenIndex != null
    val tags = OptionMeta.parse(q.optionMeta)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(difficultyLabel(q.difficulty), difficultyColor(q.difficulty))
                Text(q.subtopic, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Pill(
                    stringResource(if (q.source == "seed") R.string.source_seed else R.string.source_ai),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = if (q.source == "seed") Icons.Filled.Inventory2 else Icons.Filled.AutoAwesome,
                )
            }
            Text(q.stem, style = MaterialTheme.typography.headlineSmall)
        }

        q.optionList.forEachIndexed { i, opt ->
            val st = when {
                !answered -> if (ui.selected == i) OptState.SELECTED else OptState.NEUTRAL
                i == q.answerIndex -> OptState.CORRECT
                i == q.chosenIndex -> OptState.WRONG
                else -> OptState.DIM
            }
            val mod = when (st) {
                OptState.CORRECT -> Modifier.pop(ui.correctTick + ui.wrongTick)
                OptState.WRONG -> Modifier.shake(ui.wrongTick)
                else -> Modifier
            }
            // 1b: once answered, every wrong option shows the misconception that leads to it.
            val tag = if (answered && i != q.answerIndex) tags.getOrNull(i) else null
            OptionCard('A' + i, opt, st, enabled = !answered, tag = tag, modifier = mod) { onSelect(i) }
        }

        // 1c: confidence marking before submitting.
        AnimatedVisibility(!answered && confidenceOn && ui.selected != null, enter = slideInVertically { it / 2 } + fadeIn()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.how_sure), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChunkyOutlineButton(stringResource(R.string.not_sure), { ui.selected?.let { onSubmit(it, false) } },
                        Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.HelpOutline)
                    ChunkyButton(stringResource(R.string.sure), { ui.selected?.let { onSubmit(it, true) } },
                        Modifier.weight(1f), icon = Icons.Filled.Check)
                }
            }
        }

        AnimatedVisibility(answered, enter = slideInVertically { it / 2 } + fadeIn(tween(300))) {
            FeedbackPanel(q, ui, lang, onNext, onExplain, openFix, onAsk)
        }
    }
}

@Composable
fun misconceptionLabel(code: Misconception): String = stringResource(
    when (code) {
        Misconception.SIGN_ERROR -> R.string.mis_sign_error
        Misconception.UNIT_CONVERSION -> R.string.mis_unit_conversion
        Misconception.FORMULA_SWAP -> R.string.mis_formula_swap
        Misconception.OPERATION_SWAP -> R.string.mis_operation_swap
        Misconception.CONCEPT_CONFUSION -> R.string.mis_concept_confusion
        Misconception.PARTIAL_STEP -> R.string.mis_partial_step
        Misconception.MISREAD_QUESTION -> R.string.mis_misread_question
        Misconception.OVERGENERALISATION -> R.string.mis_overgeneralisation
    },
)

@Composable
private fun OptionCard(letter: Char, text: String, st: OptState, enabled: Boolean, tag: OptionTag?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier) {
        OptionRow(letter, text, st, enabled, onClick)
        if (tag != null) {
            Row(Modifier.padding(start = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Psychology, null, tint = if (st == OptState.WRONG) Palette.wrong else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    misconceptionLabel(tag.code) + " · " + tag.why,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (st == OptState.WRONG) FontWeight.Bold else FontWeight.Normal,
                    color = if (st == OptState.WRONG) Palette.wrong else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OptionRow(letter: Char, text: String, st: OptState, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier
    val edge = LocalExtras.current.cardEdge
    val target = when (st) {
        OptState.CORRECT -> Palette.correct
        OptState.WRONG -> Palette.wrong
        OptState.SELECTED -> MaterialTheme.colorScheme.primary
        else -> edge
    }
    val border by animateColorAsState(target, tween(250), label = "ob")
    val bg by animateColorAsState(
        when (st) {
            OptState.CORRECT -> Palette.correctBg
            OptState.WRONG -> Palette.wrongBg
            OptState.SELECTED -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }, tween(250), label = "obg",
    )
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth()
            .alpha(if (st == OptState.DIM) 0.55f else 1f)
            .bounceClick(enabled = enabled, onClick = onClick)
            .background(bg, shape)
            .border(2.dp, border, shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bubble = when (st) { OptState.CORRECT -> Palette.correct; OptState.WRONG -> Palette.wrong; else -> MaterialTheme.colorScheme.primary }
        Box(Modifier.size(36.dp).background(bubble.copy(alpha = if (st == OptState.NEUTRAL || st == OptState.DIM) 0.12f else 1f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text("$letter", style = MaterialTheme.typography.titleMedium,
                color = when (st) {
                    OptState.CORRECT, OptState.WRONG -> Color.White
                    OptState.SELECTED -> MaterialTheme.colorScheme.onPrimary
                    else -> bubble
                })
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            color = if (st == OptState.CORRECT || st == OptState.WRONG) Color(0xFF1E1B16) else MaterialTheme.colorScheme.onSurface)
        AnimatedVisibility(st == OptState.CORRECT || st == OptState.WRONG, enter = scaleIn() + fadeIn()) {
            Icon(if (st == OptState.CORRECT) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null,
                tint = if (st == OptState.CORRECT) Palette.correct else Palette.wrong, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun FeedbackPanel(q: QuizQuestion, ui: QuizUi, lang: ExplainLanguage, onNext: () -> Unit, onExplain: () -> Unit, openFix: (Long) -> Unit, onAsk: () -> Unit) {
    // AnimatedVisibility lays children out like a Box, so everything must sit in one Column.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.brain?.let { BrainCards(it, openFix) }
        FeedbackContent(q, ui, lang, onNext, onExplain, onAsk)
    }
}

/** 1c–1f reactions: attempt class, misconception, fix step added, review/probe, frustration nudge. */
@Composable
private fun BrainCards(b: BrainResult, openFix: (Long) -> Unit) {
    val (clsColor, clsText) = when (b.attemptClass) {
        AttemptClass.MASTERY -> Palette.correct to stringResource(R.string.cls_mastery)
        AttemptClass.FRAGILE -> Palette.marigold to stringResource(R.string.cls_fragile)
        AttemptClass.DEEP_MISCONCEPTION -> Palette.wrong to stringResource(R.string.cls_deep)
        AttemptClass.GAP -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.cls_gap)
        AttemptClass.UNRATED -> Color.Unspecified to ""
    }
    if (clsText.isNotEmpty()) Pill(clsText, clsColor, icon = Icons.Filled.Psychology)
    b.misconception?.let { m ->
        SoftCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
            Text(stringResource(R.string.your_mistake, misconceptionLabel(m.code)), style = MaterialTheme.typography.titleMedium)
            Text(m.why, style = MaterialTheme.typography.bodyMedium)
        }
    }
    b.fixStep?.let { s ->
        SoftCard(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Healing, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fix_added, s.misconception?.let { Misconception.of(it) }?.let { misconceptionLabel(it) } ?: s.title),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            ChunkyButton(stringResource(R.string.fix_now), { openFix(s.id) }, Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary, icon = Icons.Filled.Healing)
        }
    }
    b.fixResolved?.let {
        Pill(stringResource(R.string.fix_resolved), Palette.correct, icon = Icons.Filled.CheckCircle)
    }
    if (b.reviewStep != null && b.stuckTitle != null && b.shakyTitle != null) {
        SoftCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text(stringResource(R.string.stuck_because, b.stuckTitle, b.shakyTitle), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.review_added), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (b.probeTitle != null) {
        SoftCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text(stringResource(R.string.probe_added, b.probeTitle), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (b.frustrated) {
        SoftCard(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SelfImprovement, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.break_nudge), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FeedbackContent(q: QuizQuestion, ui: QuizUi, lang: ExplainLanguage, onNext: () -> Unit, onExplain: () -> Unit, onAsk: () -> Unit) {
    val ok = q.correct == true
    SoftCard(color = if (ok) Palette.correctBg else Palette.wrongBg, edge = if (ok) Palette.correct.copy(alpha = 0.5f) else Palette.wrong.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = if (ok) Palette.correct else Palette.wrong, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (ok) R.string.correct else R.string.not_quite), style = MaterialTheme.typography.headlineSmall,
                color = if (ok) Palette.correct else Palette.wrong)
        }
        if (!ok) {
            Text(stringResource(R.string.right_answer_was, "${'A' + q.answerIndex}) ${q.optionList.getOrElse(q.answerIndex) { "" }}"),
                style = MaterialTheme.typography.titleMedium, color = Color(0xFF1E1B16))
        }
        Text(q.explanation, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF2B2620))
        ui.fallbackNote?.let {
            Text(stringResource(R.string.fallback_note), style = MaterialTheme.typography.bodySmall, color = Color(0xFF5E5547))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
        ui.explain?.let { e ->
            SoftCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.guru_explains), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (lang.beta) BetaChip()
                }
                if (e.isBlank()) TypingDots(MaterialTheme.colorScheme.secondary)
                else Text(e, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChunkyOutlineButton(
                if (lang == ExplainLanguage.EN) stringResource(R.string.explain_short) else stringResource(R.string.explain_in, languageLabel(lang)),
                onExplain, Modifier.weight(1f), icon = Icons.Filled.Lightbulb, enabled = !ui.explaining,
            )
            ChunkyButton(stringResource(R.string.next_question), onNext, Modifier.weight(1f),
                color = if (q.correct == true) Palette.correct else MaterialTheme.colorScheme.primary,
                icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = !ui.explaining)
        }
        Row(Modifier.bounceClick(onClick = onAsk).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Forum, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ask_tutor), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
