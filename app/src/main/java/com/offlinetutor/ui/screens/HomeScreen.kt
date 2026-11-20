package com.offlinetutor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offlinetutor.AppContainer
import com.offlinetutor.R
import com.offlinetutor.data.QuizQuestion
import com.offlinetutor.data.RoadmapStep
import com.offlinetutor.domain.StudyStats
import com.offlinetutor.ui.EngineChip
import com.offlinetutor.ui.ModelStatusCard
import com.offlinetutor.ui.appViewModel
import com.offlinetutor.ui.courseLabel
import com.offlinetutor.ui.design.ChunkyButton
import com.offlinetutor.ui.design.ProgressRing
import com.offlinetutor.ui.design.SectionTitle
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.StreakFlame
import com.offlinetutor.ui.design.SubjectBadge
import com.offlinetutor.ui.design.enterAnimation
import com.offlinetutor.ui.design.subjectLook
import com.offlinetutor.ui.theme.LocalExtras
import com.offlinetutor.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class HomeVm(c: AppContainer) : ViewModel() {
    val profile = c.activeProfile
    val engine = c.engine.state
    val steps = profile.flatMapLatest { p -> p?.let { c.roadmaps.observe(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<RoadmapStep>())
    val answered = profile.flatMapLatest { p -> p?.let { c.quiz.observeAnswered(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<QuizQuestion>())
}

@Composable
fun HomeScreen(
    openRoadmap: () -> Unit,
    openQuiz: (String?) -> Unit,
    openChat: () -> Unit,
    openProgress: () -> Unit,
    openModels: () -> Unit,
) {
    val vm = appViewModel { HomeVm(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val answered by vm.answered.collectAsStateWithLifecycle()
    val p = profile ?: return

    val times = remember(answered) { answered.mapNotNull { it.answeredAt } }
    val today = remember(times) { StudyStats.todayCount(times) }
    val streak = remember(times) { StudyStats.streak(times) }
    val nextStep = steps.firstOrNull { !it.done }
    val dateText = remember { DateFormat.getDateInstance(DateFormat.FULL).format(Date()) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.enterAnimation(0)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hello_name, p.name), style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EngineChip(engine, openModels)
        }

        HeroCard(
            today = today, streak = streak,
            onContinue = { openQuiz(nextStep?.course ?: p.courseList.firstOrNull()) },
            modifier = Modifier.enterAnimation(1),
        )

        Box(Modifier.enterAnimation(2)) { ModelStatusCard(engine, openModels) }

        SectionTitle(stringResource(R.string.up_next), Modifier.enterAnimation(2))
        if (nextStep != null) {
            val total = steps.count { it.course == nextStep.course }
            SoftCard(Modifier.enterAnimation(3), onClick = openRoadmap) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubjectBadge(nextStep.course)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nextStep.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.step_x_of_y, courseLabel(nextStep.course), nextStep.stepIndex + 1, total),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            SoftCard(Modifier.enterAnimation(3), onClick = openRoadmap) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.roadmap_empty_short), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        SectionTitle(stringResource(R.string.your_subjects), Modifier.enterAnimation(4))
        p.courseList.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.enterAnimation(5 + row)) {
                pair.forEach { course ->
                    val qs = answered.filter { it.course == course }
                    SubjectTile(course, qs.size, qs.count { it.correct == true }, Modifier.weight(1f)) { openQuiz(course) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.enterAnimation(8)) {
            QuickCard(Icons.Filled.Forum, stringResource(R.string.ask_tutor), stringResource(R.string.chat_card_short),
                MaterialTheme.colorScheme.tertiary, Modifier.weight(1f), openChat)
            QuickCard(Icons.Filled.Insights, stringResource(R.string.tab_progress),
                if (answered.isEmpty()) stringResource(R.string.progress_card_short) else
                    stringResource(R.string.pct_correct, answered.count { it.correct == true } * 100 / answered.size),
                Color(0xFF3D8BD9), Modifier.weight(1f), openProgress)
        }
    }
}

@Composable
private fun HeroCard(today: Int, streak: Int, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val ex = LocalExtras.current
    val goal = StudyStats.DAILY_GOAL
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(ex.heroStart, ex.heroEnd)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(today.toFloat() / goal, 96.dp, 11.dp, Palette.marigold, Color.White.copy(alpha = 0.2f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${minOf(today, goal)}/$goal", style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.todays_goal), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    if (today >= goal) stringResource(R.string.goal_done) else stringResource(R.string.goal_body, goal - today),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StreakFlame(streak)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.day_streak), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                }
            }
        }
        ChunkyButton(stringResource(R.string.continue_learning), onContinue, Modifier.fillMaxWidth(),
            color = Palette.marigold, contentColor = Color(0xFF3A2300), icon = Icons.Filled.PlayArrow)
    }
}

@Composable
private fun SubjectTile(course: String, answered: Int, correct: Int, modifier: Modifier, onClick: () -> Unit) {
    val look = subjectLook(course)
    SoftCard(modifier, onClick = onClick, padding = 14.dp) {
        SubjectBadge(course, 46.dp)
        Text(courseLabel(course), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (answered == 0) {
            Text(stringResource(R.string.tap_to_start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.subject_stats, answered, correct * 100 / answered), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { if (answered == 0) 0f else correct.toFloat() / answered },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = look.color, trackColor = look.color.copy(alpha = 0.15f), strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun QuickCard(icon: ImageVector, title: String, body: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    SoftCard(modifier, onClick = onClick, padding = 14.dp) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}
