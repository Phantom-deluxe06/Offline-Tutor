package com.offlinetutor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.offlinetutor.R
import com.offlinetutor.domain.CourseLevels
import com.offlinetutor.domain.GoalType
import com.offlinetutor.domain.StudyPlanner
import com.offlinetutor.ui.design.ChoiceChip

private const val DAY_MS = 86_400_000L

@Composable
fun goalTypeLabel(t: GoalType): String = stringResource(
    when (t) {
        GoalType.EXAM -> R.string.goal_exam
        GoalType.SKILL -> R.string.goal_skill
        GoalType.CAREER -> R.string.goal_career
        GoalType.CERTIFICATION -> R.string.goal_certification
    },
)

@Composable
fun levelLabel(level: Int): String = stringResource(
    when (level) {
        CourseLevels.BEGINNER -> R.string.level_beginner
        CourseLevels.CONFIDENT -> R.string.level_confident
        else -> R.string.level_some
    },
)

/** Goal type + goal in the learner's words + target date + self-rated level per course. Used at intake and in Settings. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GoalEditor(
    goalType: String?, onGoalType: (String) -> Unit,
    goalText: String, onGoalText: (String) -> Unit,
    targetDate: Long?, onTargetDate: (Long?) -> Unit,
    courses: List<String>, levels: Map<String, Int>, onLevel: (String, Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.goal_kind), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalType.entries.forEach { t -> ChoiceChip(goalTypeLabel(t), t.code == goalType, { onGoalType(t.code) }, MaterialTheme.colorScheme.secondary) }
        }
        OutlinedTextField(
            value = goalText, onValueChange = { onGoalText(it.take(200)) },
            placeholder = { Text(stringResource(R.string.goal_hint)) }, maxLines = 3,
            shape = RoundedCornerShape(16.dp), colors = paperFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.target_date), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = true }) {
                Icon(Icons.Filled.DateRange, null)
                Text("  " + (targetDate?.let { StudyPlanner.format(it) } ?: stringResource(R.string.pick_date)))
            }
            if (targetDate != null) TextButton(onClick = { onTargetDate(null) }) { Text(stringResource(R.string.clear_date)) }
        }
        if (courses.isNotEmpty()) {
            Text(stringResource(R.string.current_level), style = MaterialTheme.typography.labelLarge)
            courses.forEach { course ->
                Text(courseLabel(course), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CourseLevels.BEGINNER, CourseLevels.SOME, CourseLevels.CONFIDENT).forEach { l ->
                        ChoiceChip(levelLabel(l), levels[course] == l, { onLevel(course, l) }, MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
    if (picking) {
        val today = StudyPlanner.todayEpochDay()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = targetDate?.times(DAY_MS),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS >= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onTargetDate(it / DAY_MS) }
                    picking = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}
