package com.offlinetutor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetutor.R
import com.offlinetutor.data.LogMeta
import com.offlinetutor.data.RoadmapStep
import com.offlinetutor.data.StepKind
import com.offlinetutor.domain.KuttyPrompts
import com.offlinetutor.domain.KuttyScoring
import com.offlinetutor.llm.GenRequest
import com.offlinetutor.llm.TaskType
import com.offlinetutor.ui.PaperTopBar
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.container
import kotlinx.coroutines.launch

/**
 * 6a Teach Kutty: the student explains a concept to Kutty (a confused younger kid). 3–5 key points are generated
 * BEFORE the student writes; the explanation is scored by keyword / phrase match, missed points are shown, and Kutty
 * asks one naive follow-up question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeachKuttyScreen(onBack: () -> Unit) {
    val c = container()
    val profile by c.activeProfile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var steps by remember { mutableStateOf<List<RoadmapStep>>(emptyList()) }
    var picked by remember { mutableStateOf<RoadmapStep?>(null) }
    var points by remember { mutableStateOf<List<String>?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var score by remember { mutableStateOf<KuttyScoring.Score?>(null) }
    var question by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val p = profile ?: return

    LaunchedEffect(p.id) {
        steps = p.courseList.flatMap { c.db.roadmaps().forCourse(p.id, it) }.filter { it.stepKind == StepKind.NORMAL }
        picked = steps.firstOrNull { !it.done } ?: steps.firstOrNull()
    }

    fun prepare(step: RoadmapStep) {
        picked = step; points = null; score = null; question = null; preparing = true
        scope.launch {
            // Key points come first, before the student explains. Without the model, the step's own goal and
            // subtopics stand in.
            val fallback = (listOf(step.goal) + step.subtopicList.map { "Explain $it" }).filter { it.length > 8 }.take(4)
            points = runCatching {
                val r = c.ai.run(GenRequest(TaskType.KUTTY_POINTS, KuttyPrompts.keyPoints(p.grade, step.title, step.course),
                    assistantPrefix = KuttyPrompts.POINTS_PREFIX), LogMeta())
                KuttyScoring.parsePoints(r.text)
            }.getOrNull() ?: fallback
            preparing = false
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { PaperTopBar(stringResource(R.string.teach_kutty), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(stringResource(R.string.kutty_pick)) {
                Text(stringResource(R.string.kutty_intro), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    steps.take(8).forEach { s -> FilterChip(selected = picked?.id == s.id, onClick = { prepare(s) }, label = { Text(s.title) }) }
                }
                if (points == null && !preparing) picked?.let { s -> Button(onClick = { prepare(s) }) { Text(stringResource(R.string.kutty_ready)) } }
                if (preparing) Text(stringResource(R.string.kutty_preparing))
            }
            if (points != null) {
                SectionCard(stringResource(R.string.kutty_explain, picked?.title ?: "")) {
                    OutlinedTextField(text, { text = it.take(1200) }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder = { Text(stringResource(R.string.kutty_hint)) })
                    Button(enabled = text.trim().length >= 20 && !asking, onClick = {
                        val pts = points ?: return@Button
                        val s = KuttyScoring.score(text, pts)
                        score = s; asking = true; question = null
                        scope.launch {
                            question = runCatching {
                                val r = c.ai.run(GenRequest(TaskType.KUTTY_ASK, KuttyPrompts.followUp(picked?.title ?: "", text,
                                    s.points.filter { !it.covered }.map { it.point })), LogMeta())
                                KuttyScoring.parseQuestion(r.text)
                            }.getOrNull()
                            asking = false
                        }
                    }) { Text(stringResource(R.string.kutty_send)) }
                }
            }
            score?.let { s ->
                SectionCard(stringResource(R.string.kutty_score, s.covered, s.total)) {
                    s.points.forEach { r ->
                        Text((if (r.covered) "✓ " else "✗ ") + r.point, fontWeight = if (r.covered) FontWeight.Normal else FontWeight.Bold,
                            color = if (r.covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        if (!r.covered && r.missing.isNotEmpty()) Text(stringResource(R.string.kutty_missing, r.missing.take(4).joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.kutty_asks), style = MaterialTheme.typography.titleSmall)
                    Text(question ?: if (asking) "…" else stringResource(R.string.kutty_no_question), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
