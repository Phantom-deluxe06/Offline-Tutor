package com.offlinetutor.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.offlinetutor.R
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.container
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    val files = remember(refresh) { c.modelStore.listPte() }
    val tokenizer = remember(refresh) { c.modelStore.tokenizer() }
    val active = remember(refresh) { c.modelStore.activeModel()?.pte?.absolutePath }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            progress = 0f
            message = null
            message = try {
                val f = c.modelStore.import(uri) { p -> progress = p }
                ctx.getString(R.string.imported_x, f.name)
            } catch (e: Exception) {
                e.message
            }
            progress = null
            c.engine.refreshAvailability()
            refresh++
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.offlinetutor.ui.PaperTopBar(stringResource(R.string.models_title), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.installed_models)) {
                if (files.isEmpty()) Text(stringResource(R.string.no_pte))
                files.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = f.absolutePath == active, onClick = {
                            c.modelStore.setActive(f); c.engine.refreshAvailability(); refresh++
                        })
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                String.format(Locale.US, "%.0f MB · %s", f.length() / 1_048_576.0,
                                    if (f.absolutePath.startsWith(c.modelStore.internalDir.absolutePath)) ctx.getString(R.string.app_storage) else "adb"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { toDelete = f }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                }
                Text(
                    if (tokenizer != null) stringResource(R.string.tokenizer_ok, tokenizer.name) else stringResource(R.string.tokenizer_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tokenizer != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.switch_model_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard(stringResource(R.string.import_title)) {
                Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = progress == null) { Text(stringResource(R.string.import_pte)) }
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = progress == null) { Text(stringResource(R.string.import_tokenizer)) }
                }
                progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            SectionCard(stringResource(R.string.adb_title)) {
                Text(stringResource(R.string.adb_body), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(
                        "adb shell mkdir -p /sdcard/Android/data/${ctx.packageName}/files/models\n" +
                            "adb push tutor_1b_spinquant.pte /sdcard/Android/data/${ctx.packageName}/files/models/\n" +
                            "adb push tokenizer.model /sdcard/Android/data/${ctx.packageName}/files/models/",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                TextButton(onClick = { c.engine.refreshAvailability(); refresh++ }) { Text(stringResource(R.string.rescan)) }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_model)) },
            text = { Text(stringResource(R.string.delete_model_confirm, f.name)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        if (c.modelStore.activeModel()?.pte == f) c.engine.unload()
                        c.modelStore.delete(f)
                        c.engine.refreshAvailability()
                        refresh++
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
