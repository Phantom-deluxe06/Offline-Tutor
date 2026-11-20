package com.offlinetutor.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.offlinetutor.R
import com.offlinetutor.data.GenerationLog
import com.offlinetutor.llm.MemoryProbe
import com.offlinetutor.llm.MemSnapshot
import com.offlinetutor.llm.ThermalMonitor
import com.offlinetutor.ui.ModelStatusCard
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.StreamingPreview
import com.offlinetutor.ui.container
import com.offlinetutor.ui.engineLabel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun mb(kb: Long) = if (kb < 0) "–" else String.format(Locale.US, "%.0f MB", kb / 1024.0)
private fun f1(d: Double?) = d?.let { String.format(Locale.US, "%.1f", it) } ?: "–"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BenchmarkScreen(onBack: () -> Unit, openModels: () -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner by c.bench.state.collectAsStateWithLifecycle()
    val engine by c.engine.state.collectAsStateWithLifecycle()
    val thermal by c.thermal.status.collectAsStateWithLifecycle()
    val logsFlow = remember { c.metrics.observeRecent(25) }
    val logs by logsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var mem by remember { mutableStateOf<MemSnapshot?>(null) }
    var headroom by remember { mutableStateOf<Float?>(null) }
    var perCombo by remember { mutableIntStateOf(2) }
    var confirmClear by remember { mutableStateOf(false) }
    val model = remember(engine) { c.modelStore.activeModel() }

    LaunchedEffect(Unit) {
        while (true) {
            mem = withContext(Dispatchers.IO) { MemoryProbe.read(ctx) }
            headroom = c.thermal.headroom()
            delay(2000)
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val n = withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.use { c.metrics.exportCsv(it) } ?: 0
            }
            Toast.makeText(ctx, ctx.getString(R.string.exported_rows, n), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.offlinetutor.ui.PaperTopBar(stringResource(R.string.benchmark_title), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModelStatusCard(engine, openModels)
            SectionCard(stringResource(R.string.device)) {
                KV(stringResource(R.string.device_model), "${Build.MANUFACTURER} ${Build.MODEL}" +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " · ${Build.SOC_MODEL}" else "")
                KV(stringResource(R.string.android_version), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}")
                KV(stringResource(R.string.ram_total_avail), mem?.let { "${mb(it.deviceTotalKb)} / ${mb(it.deviceAvailKb)}" } ?: "–")
                KV(stringResource(R.string.peak_rss), mem?.let { mb(it.vmHwmKb) } ?: "–")
                KV(stringResource(R.string.current_rss), mem?.let { mb(it.vmRssKb) } ?: "–")
                KV(stringResource(R.string.thermal), ThermalMonitor.label(thermal) + (headroom?.let { String.format(Locale.US, " · headroom %.2f", it) } ?: ""))
            }
            SectionCard(stringResource(R.string.model)) {
                KV(stringResource(R.string.status), engineLabel(engine))
                KV(stringResource(R.string.file), model?.let { "${it.pte.name} (${mb(it.pte.length() / 1024)})" } ?: "–")
                KV(stringResource(R.string.context_len), c.engine.contextLength.toString())
                KV(stringResource(R.string.load_time), if (c.engine.lastLoadMs > 0) "${c.engine.lastLoadMs} ms" else "–")
            }

            SectionCard(stringResource(R.string.run)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.bench.loadModel() }, enabled = !runner.running) { Text(stringResource(R.string.load_model)) }
                    Button(onClick = { c.bench.quickBench() }, enabled = !runner.running) { Text(stringResource(R.string.quick_bench)) }
                    Button(onClick = { c.bench.mcqEval(perCombo) }, enabled = !runner.running) { Text(stringResource(R.string.mcq_eval)) }
                    Button(onClick = { c.bench.roadmapEval() }, enabled = !runner.running) { Text(stringResource(R.string.roadmap_eval)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.per_combo), Modifier.padding(top = 8.dp))
                    (1..3).forEach { n -> FilterChip(selected = n == perCombo, onClick = { perCombo = n }, label = { Text("$n") }) }
                }
                Text(stringResource(R.string.eval_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (runner.running || runner.total > 0) {
                    Text("${runner.label}  ${runner.done}/${runner.total}", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(
                        progress = { if (runner.total == 0) 0f else runner.done.toFloat() / runner.total },
                        Modifier.fillMaxWidth(),
                    )
                }
                if (runner.running) {
                    StreamingPreview(runner.partial)
                    OutlinedButton(onClick = { c.bench.cancel() }) { Text(stringResource(R.string.stop)) }
                }
                runner.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            runner.summary?.let { s ->
                SectionCard(stringResource(R.string.summary)) {
                    KV(stringResource(R.string.model), s.model)
                    KV(stringResource(R.string.runs), s.runs.toString())
                    KV(stringResource(R.string.valid_json_rate), s.validJsonRate?.let { "${(it * 100).toInt()}%" } ?: "–")
                    KV(stringResource(R.string.duplicate_rate), s.duplicateRate?.let { "${(it * 100).toInt()}%" } ?: "–")
                    KV(stringResource(R.string.avg_ttft), s.avgTtftMs?.let { "${it.toInt()} ms" } ?: "–")
                    KV(stringResource(R.string.avg_tps), f1(s.avgDecodeTps))
                    KV(stringResource(R.string.peak_rss), s.peakRssMb?.let { String.format(Locale.US, "%.0f MB", it) } ?: "–")
                    KV(stringResource(R.string.guard_stops), s.guardStops.toString())
                }
            }

            SectionCard(stringResource(R.string.report_tools)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exporter.launch("tutor-runs-${System.currentTimeMillis()}.csv") }) { Text(stringResource(R.string.export_csv)) }
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_logs)) }
                }
                Text(stringResource(R.string.dumpsys_hint), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text("adb shell dumpsys meminfo ${ctx.packageName}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }

            SectionCard(stringResource(R.string.recent_runs)) {
                if (logs.isEmpty()) Text(stringResource(R.string.no_runs), style = MaterialTheme.typography.bodyMedium)
                logs.forEach { LogRow(it); HorizontalDivider() }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_logs)) },
            text = { Text(stringResource(R.string.clear_logs_confirm)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { c.metrics.clear() } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogRow(l: GenerationLog) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            buildString {
                append(l.task)
                l.evalSubject?.let { append(" · $it") }
                l.evalGrade?.let { append(" · G$it") }
                append(" · ${l.model}")
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            buildString {
                append("${l.wallMs} ms")
                l.ttftMs?.let { append(" · TTFT $it ms") }
                l.decodeTokPerSec?.let { append(" · ${f1(it)} tok/s") }
                append(" · ${l.generatedTokens} tok")
                append(" · peak ${mb(l.peakRssKb)}")
                append(" · ${l.stopReason}")
                l.validJson?.let { append(if (it) " · JSON ok" else " · JSON bad") }
                if (l.duplicate == true) append(" · DUP")
                l.error?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
