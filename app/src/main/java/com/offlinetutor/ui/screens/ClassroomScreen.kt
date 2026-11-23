package com.offlinetutor.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.offlinetutor.R
import com.offlinetutor.data.BattleHost
import com.offlinetutor.domain.ClassHeatmap
import com.offlinetutor.domain.ProgressQr
import com.offlinetutor.ui.PaperTopBar
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runtime permissions Nearby Connections needs on this Android version (official list for targetSdk 35). */
private fun nearbyPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
    Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
}

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

/**
 * Phase 5: zero-internet classroom. [standalone] = opened from the profile picker (no profile, no model needed):
 * only joining a battle and the teacher scan are offered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomScreen(onBack: () -> Unit, standalone: Boolean) {
    val c = container()
    val hasNearby = remember { c.nearby.available() }
    // Without Play services the mesh is hidden and only the QR fallback (5c) is offered.
    val tabs = buildList {
        if (hasNearby) add(R.string.class_battle)
        if (hasNearby && !standalone) add(R.string.share_questions)
        add(R.string.progress_qr)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { PaperTopBar(stringResource(R.string.classroom), navigationIcon = { IconButton(onClick = { c.nearby.stop(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasNearby) Text(stringResource(R.string.no_play_services), style = MaterialTheme.typography.bodyMedium)
            TabRow(selectedTabIndex = tab.coerceIn(0, tabs.lastIndex)) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
            }
            when (tabs.getOrNull(tab)) {
                R.string.class_battle -> BattleTab(standalone)
                R.string.share_questions -> ShareTab()
                else -> QrTab(standalone)
            }
            Text(stringResource(R.string.mesh_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rememberPermissionGate(onGranted: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) onGranted()
    }
    return { launcher.launch(nearbyPermissions()) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BattleTab(standalone: Boolean) {
    val c = container()
    val profile by c.activeProfile.collectAsStateWithLifecycle()
    val host by c.battleHost.state.collectAsStateWithLifecycle()
    val client by c.battleClient.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf<String?>(null) }
    var nick by rememberSaveable { mutableStateOf(profile?.name ?: "") }
    val p = profile

    val startHost = rememberPermissionGate {
        val prof = p ?: return@rememberPermissionGate
        val course = prof.courseList.firstOrNull() ?: return@rememberPermissionGate
        scope.launch {
            loading = "…"
            val qs = runCatching { withContext(Dispatchers.Default) { c.quiz.battleQuestions(prof, course, null) { loading = it.takeLast(60) } } }.getOrDefault(emptyList())
            loading = null
            if (qs.isNotEmpty()) c.battleHost.start(prof.name, qs)
        }
    }
    val startJoin = rememberPermissionGate { c.battleClient.discover() }

    // ---- Host ----
    if (!standalone && p != null && client.hosts.isEmpty() && !client.connected) {
        SectionCard(stringResource(R.string.host_battle)) {
            when (host.phase) {
                BattleHost.Phase.IDLE -> {
                    Text(stringResource(R.string.host_battle_body), style = MaterialTheme.typography.bodySmall)
                    loading?.let { Text(stringResource(R.string.preparing_questions, it)) }
                    Button(onClick = startHost, enabled = loading == null) { Text(stringResource(R.string.start_battle)) }
                }
                BattleHost.Phase.LOBBY -> {
                    Text(stringResource(R.string.join_code), style = MaterialTheme.typography.labelLarge)
                    Text(host.code, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.players_joined, host.players.size))
                    host.players.values.forEach { Text("• ${it.nickname}") }
                    host.pending.values.forEach { Text("… $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = { c.battleHost.begin() }, enabled = host.players.isNotEmpty()) { Text(stringResource(R.string.begin_battle)) }
                }
                BattleHost.Phase.ROUND -> {
                    val q = host.questions[host.round]
                    Text(stringResource(R.string.question_n_of, host.round + 1, host.questions.size), style = MaterialTheme.typography.labelLarge)
                    Text(q.stem, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.answered_n_of, host.answered, host.players.size))
                    OutlinedButton(onClick = { c.battleHost.endRound() }) { Text(stringResource(R.string.end_round)) }
                }
                BattleHost.Phase.RESULT, BattleHost.Phase.END -> {
                    val q = host.questions[host.round]
                    Text(q.stem, style = MaterialTheme.typography.titleMedium)
                    q.options.forEachIndexed { i, o ->
                        val n = host.lastCounts.getOrElse(i) { 0 }
                        val tag = q.codes.getOrNull(i)?.let { " — $it" } ?: ""
                        Text("${'A' + i}) $o: $n${if (i == q.answer) " ✓" else tag}",
                            color = if (i == q.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    Leaderboard(host.players.values.sortedByDescending { it.score }.map { it.nickname to it.score })
                    if (host.phase == BattleHost.Phase.RESULT) Button(onClick = { c.battleHost.next() }) { Text(stringResource(R.string.battle_next)) }
                    else {
                        Heatmap(host.rounds)
                        OutlinedButton(onClick = { c.battleHost.stop() }) { Text(stringResource(R.string.close_battle)) }
                    }
                }
            }
        }
    }

    // ---- Join (model-less) ----
    if (host.phase == BattleHost.Phase.IDLE) {
        SectionCard(stringResource(R.string.join_battle)) {
            when {
                !client.connected -> {
                    OutlinedTextField(nick, { nick = it.take(20) }, label = { Text(stringResource(R.string.nickname)) }, singleLine = true)
                    Button(onClick = startJoin, enabled = nick.isNotBlank()) { Text(stringResource(R.string.find_battles)) }
                    client.hosts.forEach { h ->
                        OutlinedButton(onClick = { c.battleClient.join(h, nick) }, enabled = client.connecting == null) {
                            Text(stringResource(R.string.join_host, h.nickname, h.code))
                        }
                    }
                    client.digits?.let { Text(stringResource(R.string.confirm_digits, it)) }
                }
                client.ended -> { Text(stringResource(R.string.battle_over)); Leaderboard(client.board) }
                client.round < 0 -> Text(stringResource(R.string.waiting_for_host))
                else -> {
                    val q = client.questions.getOrNull(client.round)
                    if (q != null) {
                        Text(stringResource(R.string.question_n_of, client.round + 1, client.questions.size), style = MaterialTheme.typography.labelLarge)
                        Text(q.stem, style = MaterialTheme.typography.titleMedium)
                        q.options.forEachIndexed { i, o ->
                            val chosen = client.myAnswer == i
                            val r = client.result
                            Button(onClick = { c.battleClient.answer(i, null) }, enabled = client.myAnswer == null && r == null,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("${'A' + i}) $o" + when {
                                    r != null && i == r.correct -> " ✓"
                                    r != null && chosen -> " ✗"
                                    chosen -> " •"
                                    else -> ""
                                })
                            }
                        }
                        client.result?.let { r ->
                            val mine = client.myAnswer
                            Text(if (r.yourCorrect == true) stringResource(R.string.battle_correct, r.yourPoints) else stringResource(R.string.battle_wrong))
                            mine?.let { r.codes.getOrNull(it) }?.let { code -> Text(stringResource(R.string.likely_mistake_was, code)) }
                            Leaderboard(client.board)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Leaderboard(entries: List<Pair<String, Int>>) {
    if (entries.isEmpty()) return
    Text(stringResource(R.string.leaderboard), style = MaterialTheme.typography.titleSmall)
    entries.take(10).forEachIndexed { i, (n, s) -> Text("${i + 1}. $n — $s") }
}

/** Class misconception heatmap: category x question, share of the class. */
@Composable
private fun Heatmap(rounds: List<ClassHeatmap.Round>) {
    val cells = ClassHeatmap.cells(rounds)
    if (cells.isEmpty()) return
    Text(stringResource(R.string.class_heatmap), style = MaterialTheme.typography.titleSmall)
    ClassHeatmap.headline(rounds)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
    cells.keys.map { it.first }.distinct().sorted().forEach { code ->
        val row = rounds.indices.joinToString("  ") { qi -> cells[code to qi]?.let { "Q${qi + 1} ${Math.round(it * 100)}%" } ?: "Q${qi + 1} –" }
        Text("$code: $row", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ShareTab() {
    val c = container()
    val profile by c.activeProfile.collectAsStateWithLifecycle()
    val s by c.share.state.collectAsStateWithLifecycle()
    val start = rememberPermissionGate { profile?.let { c.share.start(it) } }
    SectionCard(stringResource(R.string.share_questions)) {
        Text(stringResource(R.string.share_questions_body), style = MaterialTheme.typography.bodySmall)
        if (!s.running) Button(onClick = start, enabled = profile != null) { Text(stringResource(R.string.start_sharing)) }
        else OutlinedButton(onClick = { c.share.stop() }) { Text(stringResource(R.string.stop)) }
        Text(stringResource(R.string.share_stats, s.peers, s.received, s.llm, s.template, s.rejected, s.sent))
        s.secondsSaved?.let { Text(stringResource(R.string.share_saved, Math.round(it).toInt())) }
    }
}

@Composable
private fun QrTab(standalone: Boolean) {
    val c = container()
    val profile by c.activeProfile.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf<String?>(null) }
    val scans = remember { mutableStateListOf<ProgressQr.Progress>() }
    val scope = rememberCoroutineScope()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { ProgressQr.decode(it) }?.let { if (scans.none { s -> s.nickname == it.nickname }) scans.add(it) }
    }
    val p = profile
    if (!standalone && p != null) {
        SectionCard(stringResource(R.string.share_progress)) {
            Text(stringResource(R.string.share_progress_body), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                scope.launch {
                    val mastery = c.db.mastery().all(p.id).map { Triple(it.conceptId, it.rating.toInt(), it.attempts) }
                    val hits = c.db.misconceptions().observe(p.id).first().filter { !it.resolved }.groupingBy { it.code }.eachCount()
                    code = ProgressQr.encode(ProgressQr.Progress(p.name, mastery, hits))
                }
            }) { Text(stringResource(R.string.show_qr)) }
            code?.let { s ->
                val bmp = remember(s) { qrBitmap(s) }
                Image(bmp.asImageBitmap(), null, Modifier.size(280.dp))
                Text(stringResource(R.string.qr_size, s.length), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    SectionCard(stringResource(R.string.teacher_scan)) {
        Text(stringResource(R.string.teacher_scan_body), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false)) }) {
            Text(stringResource(R.string.scan_student))
        }
        Text(stringResource(R.string.scanned_n, scans.size))
        ProgressQr.aggregate(scans).entries.sortedByDescending { it.value.first }.forEach { (codeName, v) ->
            Text(stringResource(R.string.scan_row, codeName, v.first, scans.size, v.second), style = MaterialTheme.typography.bodySmall)
        }
    }
}
