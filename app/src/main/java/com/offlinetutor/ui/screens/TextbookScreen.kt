package com.offlinetutor.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetutor.R
import com.offlinetutor.data.TextbookService
import com.offlinetutor.ui.PaperTopBar
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.container
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/** 6b: photograph a textbook page; it is read on the phone (no internet) and grounds quiz questions on that topic. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextbookScreen(onBack: () -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val profile by c.activeProfile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var script by remember { mutableStateOf(TextbookService.Script.LATIN) }
    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val p = profile ?: return
    var course by remember { mutableStateOf(p.courseList.firstOrNull() ?: "") }
    val pagesFlow = remember(p.id) { c.textbook.observe(p.id) }
    val passages by (pagesFlow ?: flowOf(emptyList())).collectAsStateWithLifecycle(initialValue = emptyList())

    fun read(uri: Uri) {
        status = ctx.getString(R.string.textbook_reading); preview = null
        scope.launch {
            runCatching {
                val text = c.textbook.ocr(uri, script)
                val n = if (text.isBlank()) 0 else c.textbook.addPage(p, course, text)
                preview = text.take(400)
                status = ctx.getString(R.string.textbook_saved, n)
            }.onFailure { status = ctx.getString(R.string.textbook_failed, it.message ?: "") }
        }
    }

    val photoFile = remember { File(ctx.cacheDir, "textbook/page.jpg").apply { parentFile?.mkdirs() } }
    val photoUri = remember { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", photoFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) read(photoUri) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { read(it) } }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { PaperTopBar(stringResource(R.string.textbook), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(stringResource(R.string.textbook_add)) {
                Text(stringResource(R.string.textbook_body), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.courseList.forEach { cs -> FilterChip(selected = course == cs, onClick = { course = cs }, label = { Text(cs) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = script == TextbookService.Script.LATIN, onClick = { script = TextbookService.Script.LATIN },
                        label = { Text(stringResource(R.string.script_latin)) })
                    FilterChip(selected = script == TextbookService.Script.DEVANAGARI, onClick = { script = TextbookService.Script.DEVANAGARI },
                        label = { Text(stringResource(R.string.script_devanagari)) })
                }
                Text(stringResource(R.string.textbook_scripts_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { camera.launch(photoUri) }) { Text(stringResource(R.string.take_photo)) }
                    OutlinedButton(onClick = { gallery.launch("image/*") }) { Text(stringResource(R.string.pick_image)) }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            SectionCard(stringResource(R.string.textbook_pages, passages.map { it.page }.distinct().size)) {
                passages.groupBy { it.page }.forEach { (page, parts) ->
                    Text("$page · ${parts.first().course} · ${parts.size} ${stringResource(R.string.passages)}", style = MaterialTheme.typography.titleSmall)
                    Text(parts.first().text.take(140) + "…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { c.textbook.deletePage(p.id, page) } }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}
