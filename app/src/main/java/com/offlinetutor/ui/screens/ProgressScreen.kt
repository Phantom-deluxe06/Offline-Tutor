package com.offlinetutor.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.offlinetutor.domain.WeakSpots
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.appViewModel
import com.offlinetutor.ui.components.Bar
import com.offlinetutor.ui.components.BarList
import com.offlinetutor.ui.components.DifficultyTrail
import com.offlinetutor.ui.components.LegendDot
import com.offlinetutor.ui.components.LineChart
import com.offlinetutor.ui.components.WeeklyBars
import com.offlinetutor.ui.courseLabel
import com.offlinetutor.ui.design.EmptyState
import com.offlinetutor.ui.design.ProgressRing
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.design.enterAnimation
import com.offlinetutor.ui.design.subjectLook
import com.offlinetutor.ui.difficultyColor
import com.offlinetutor.ui.difficultyLabel
import com.offlinetutor.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressVm(c: AppContainer) : ViewModel() {
    val answered = c.activeProfile.flatMapLatest { p -> p?.let { c.quiz.observeAnswered(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<QuizQuestion>())
    val steps = c.activeProfile.flatMapLatest { p -> p?.let { c.roadmaps.observe(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<RoadmapStep>())
}

private fun pct(n: Int, d: Int) = if (d == 0) 0f else n.toFloat() / d

@Composable
fun ProgressScreen(openQuiz: (String, String) -> Unit) {
    val vm = appViewModel { ProgressVm(it) }
    val answered by vm.answered.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()

    val times = remember(answered) { answered.mapNotNull { it.answeredAt } }
    val streak = remember(times) { StudyStats.streak(times) }
    val week = remember(times) { StudyStats.last7(times) }
    val weekLabels = remember { StudyStats.last7Labels() }
    val daily = remember(answered) { dailyAccuracy(answered, 14) }
    val correct = answered.count { it.correct == true }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enterAnimation(0)) {
            StatTile(Icons.Filled.Quiz, answered.size, "", stringResource(R.string.stat_answered), Color(0xFF3D8BD9), Modifier.weight(1f))
            StatTile(Icons.Filled.TrackChanges, (pct(correct, answered.size) * 100).toInt(), "%", stringResource(R.string.stat_accuracy), Palette.correct, Modifier.weight(1f))
            StatTile(Icons.Filled.LocalFireDepartment, streak, "", stringResource(R.string.day_streak), Palette.flame, Modifier.weight(1f))
        }

        if (answered.isEmpty()) {
            EmptyState(Icons.Filled.Quiz, stringResource(R.string.no_answers_title), stringResource(R.string.no_answers_yet))
            return@Column
        }

        SectionCard(stringResource(R.string.this_week), Modifier.enterAnimation(1)) {
            Text(stringResource(R.string.week_summary, week.sum()), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            WeeklyBars(week, weekLabels, MaterialTheme.colorScheme.primary)
        }

        SectionCard(stringResource(R.string.chart_daily), Modifier.enterAnimation(2)) {
            LineChart(daily.map { it.second }, daily.map { it.first })
            Text(stringResource(R.string.chart_daily_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val recent = answered.takeLast(30)
        SectionCard(stringResource(R.string.chart_difficulty), Modifier.enterAnimation(3)) {
            DifficultyTrail(
                levels = recent.map { it.difficulty },
                correct = recent.map { it.correct == true },
                colors = listOf(difficultyColor(1), difficultyColor(2), difficultyColor(3)),
                ok = Palette.correct, bad = Palette.wrong,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot(Palette.correct, stringResource(R.string.correct))
                LegendDot(Palette.wrong, stringResource(R.string.not_quite))
            }
        }

        SectionCard(stringResource(R.string.chart_by_level), Modifier.enterAnimation(4)) {
            BarList((1..3).map { l ->
                val atLevel = answered.filter { it.difficulty == l }
                val ok = atLevel.count { it.correct == true }
                Bar(difficultyLabel(l), pct(ok, atLevel.size), "$ok/${atLevel.size}", difficultyColor(l))
            })
        }

        val byCourse = answered.groupBy { it.course }
        SectionCard(stringResource(R.string.chart_by_course), Modifier.enterAnimation(5)) {
            BarList(byCourse.map { (course, qs) ->
                val ok = qs.count { it.correct == true }
                Bar(courseLabel(course), pct(ok, qs.size), "${(pct(ok, qs.size) * 100).toInt()}%", subjectLook(course).color)
            })
        }

        val roadmap = steps.groupBy { it.course }
        if (roadmap.isNotEmpty()) {
            SectionCard(stringResource(R.string.chart_roadmap), Modifier.enterAnimation(6)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    roadmap.forEach { (course, s) ->
                        val d = s.count { it.done }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ProgressRing(pct(d, s.size), 66.dp, 8.dp, subjectLook(course).color) {
                                Text("$d/${s.size}", style = MaterialTheme.typography.titleSmall)
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(courseLabel(course), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(80.dp))
                        }
                    }
                }
            }
        }

        val weak = WeakSpots.stats(answered.map { it.subtopic to (it.correct == true) })
            .filter { it.attempts >= 2 && it.accuracy < 0.6 }.sortedBy { it.accuracy }.take(5)
        val subtopicCourse = answered.associate { it.subtopic to it.course }
        SectionCard(stringResource(R.string.weak_spots), Modifier.enterAnimation(7)) {
            if (weak.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Palette.correct)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.weak_spots_none), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                weak.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.subtopic, style = MaterialTheme.typography.titleSmall)
                            Text("${w.correct}/${w.attempts} · ${(w.accuracy * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                                color = Palette.wrong)
                        }
                        val course = subtopicCourse[w.subtopic]
                        if (course != null) {
                            Text(
                                stringResource(R.string.practice),
                                Modifier.bounceClick { openQuiz(course, w.subtopic) }
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                color = Color.White, style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: Int, suffix: String, label: String, color: Color, modifier: Modifier) {
    val a = remember(value) { Animatable(0f) }
    LaunchedEffect(value) { a.animateTo(value.toFloat(), tween(900)) }
    SoftCard(modifier, padding = 12.dp) {
        Box(Modifier.size(36.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text("${a.value.toInt()}$suffix", style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Accuracy per day for days that had answers, within the last [days] days. */
private fun dailyAccuracy(answered: List<QuizQuestion>, days: Int): List<Pair<String, Float>> {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -days)
    val since = cal.timeInMillis
    val key = SimpleDateFormat("yyyyMMdd", Locale.US)
    val label = SimpleDateFormat("d MMM", Locale.getDefault())
    return answered.filter { (it.answeredAt ?: 0) >= since }
        .groupBy { key.format(Date(it.answeredAt ?: 0)) }
        .toSortedMap()
        .map { (_, qs) -> label.format(Date(qs.first().answeredAt ?: 0)) to pct(qs.count { it.correct == true }, qs.size) }
}
