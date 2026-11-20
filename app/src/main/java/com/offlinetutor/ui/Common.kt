package com.offlinetutor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.offlinetutor.AppContainer
import com.offlinetutor.R
import com.offlinetutor.TutorApp
import com.offlinetutor.domain.AdaptiveDifficulty
import com.offlinetutor.domain.ExplainLanguage
import com.offlinetutor.llm.EngineState
import com.offlinetutor.ui.design.ChoiceChip
import com.offlinetutor.ui.design.ChunkyButton
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.design.subjectLook
import com.offlinetutor.ui.theme.LocalExtras
import com.offlinetutor.ui.theme.Palette

@Composable
fun container(): AppContainer = (LocalContext.current.applicationContext as TutorApp).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, noinline create: (AppContainer) -> VM): VM {
    val c = container()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SoftCard(modifier) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun BetaChip() {
    Text(
        stringResource(R.string.beta),
        Modifier.background(Palette.marigold, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF3A2300),
    )
}

@Composable
fun languageLabel(l: ExplainLanguage): String = when (l) {
    ExplainLanguage.EN -> stringResource(R.string.lang_english)
    ExplainLanguage.TA -> stringResource(R.string.lang_tamil)
    ExplainLanguage.BN -> stringResource(R.string.lang_bengali)
}

@Composable
fun difficultyLabel(level: Int): String = when (level) {
    AdaptiveDifficulty.EASY -> stringResource(R.string.level_easy)
    AdaptiveDifficulty.MEDIUM -> stringResource(R.string.level_medium)
    else -> stringResource(R.string.level_hard)
}

fun difficultyColor(level: Int) = when (level) {
    AdaptiveDifficulty.EASY -> Palette.easy
    AdaptiveDifficulty.MEDIUM -> Palette.medium
    else -> Palette.hard
}

/** Known course names are stored in English (they go into prompts) but shown translated. */
@Composable
fun courseLabel(course: String): String = when (course) {
    "Mathematics" -> stringResource(R.string.course_mathematics)
    "Science" -> stringResource(R.string.course_science)
    "English" -> stringResource(R.string.course_english)
    "Social Science" -> stringResource(R.string.course_social)
    "Computer Science" -> stringResource(R.string.course_computer)
    "Physics" -> stringResource(R.string.course_physics)
    "Chemistry" -> stringResource(R.string.course_chemistry)
    "Biology" -> stringResource(R.string.course_biology)
    "History" -> stringResource(R.string.course_history)
    "Geography" -> stringResource(R.string.course_geography)
    else -> course
}

val SUGGESTED_COURSES = listOf(
    "Mathematics", "Science", "English", "Social Science", "Computer Science",
    "Physics", "Chemistry", "Biology", "History", "Geography",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseEditor(selected: List<String>, onChange: (List<String>) -> Unit) {
    var custom by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (SUGGESTED_COURSES + selected.filter { it !in SUGGESTED_COURSES }).forEach { course ->
            val on = course in selected
            val look = subjectLook(course)
            ChoiceChip(courseLabel(course), on, { onChange(if (on) selected - course else selected + course) }, look.color, look.icon)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.take(40) },
            label = { Text(stringResource(R.string.add_course)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = paperFieldColors(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(52.dp).bounceClick {
                val c = custom.trim()
                if (c.isNotEmpty() && selected.none { it.equals(c, true) }) onChange(selected + c)
                custom = ""
            }.background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, stringResource(R.string.add_course), tint = Color.White) }
    }
}

@Composable
fun paperFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor = LocalExtras.current.cardEdge,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradePicker(grade: Int, onChange: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (6..12).forEach { g ->
            ChoiceChip(stringResource(R.string.grade_n, g), g == grade, { onChange(g) }, MaterialTheme.colorScheme.secondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePicker(code: String, onChange: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExplainLanguage.entries.forEach { l ->
            Box {
                ChoiceChip(languageLabel(l), l.code == code, { onChange(l.code) })
                if (l.beta) Box(Modifier.align(Alignment.TopEnd).padding(end = 2.dp)) { BetaChip() }
            }
        }
    }
}

/** Shown wherever the model is needed but missing or failed to load. */
@Composable
fun ModelStatusCard(state: EngineState, onOpenModels: () -> Unit) {
    val (title, body) = when (state) {
        EngineState.NoModel -> stringResource(R.string.model_missing_title) to stringResource(R.string.model_missing_body)
        is EngineState.Failed -> stringResource(R.string.model_failed_title) to state.message
        else -> return
    }
    SoftCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Palette.marigold, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CloudOff, null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        ChunkyButton(stringResource(R.string.open_models), onOpenModels, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
    }
}

/** Small status pill for the AI model (Home top bar). */
@Composable
fun EngineChip(state: EngineState, onClick: () -> Unit) {
    val (color, label) = when (state) {
        is EngineState.Ready, is EngineState.Busy, EngineState.Unloaded -> Palette.correct to stringResource(R.string.ai_ready_offline)
        is EngineState.Loading -> Palette.marigold to engineLabel(state)
        EngineState.NoModel, is EngineState.Failed -> Palette.wrong to stringResource(R.string.engine_no_model)
    }
    Row(
        Modifier.bounceClick(onClick = onClick).background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Memory, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun engineLabel(state: EngineState): String = when (state) {
    EngineState.NoModel -> stringResource(R.string.engine_no_model)
    EngineState.Unloaded -> stringResource(R.string.engine_unloaded)
    is EngineState.Loading -> stringResource(R.string.engine_loading, state.model)
    is EngineState.Ready -> stringResource(R.string.engine_ready, state.model)
    is EngineState.Busy -> stringResource(R.string.engine_busy, state.task.name.lowercase())
    is EngineState.Failed -> stringResource(R.string.engine_failed)
}

/** Raw stream tail; only used on the Benchmark screen now (users see ThinkingCard instead). */
@Composable
fun StreamingPreview(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    Text(
        text.takeLast(280),
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 6,
    )
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) = com.offlinetutor.ui.design.LetterAvatar(name)

/** Screen padding column helper. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}
