package com.offlinetutor.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetutor.R
import com.offlinetutor.data.Feature
import com.offlinetutor.domain.PinHasher
import com.offlinetutor.ui.CourseEditor
import com.offlinetutor.ui.GradePicker
import com.offlinetutor.ui.LanguagePicker
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, switchProfile: () -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    val profile by c.activeProfile.collectAsStateWithLifecycle()
    var pinDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var seeded by remember { mutableStateOf(false) }
    var nightJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val flags by c.flags.state.collectAsStateWithLifecycle()
    val p = profile

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            com.offlinetutor.ui.PaperTopBar(stringResource(R.string.settings_title), navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(stringResource(R.string.app_language)) {
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to R.string.lang_system, "en" to R.string.lang_english, "ta" to R.string.lang_tamil, "bn" to R.string.lang_bengali)
                        .forEach { (tag, label) ->
                            FilterChip(
                                selected = current == tag,
                                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                                label = { Text(stringResource(label)) },
                            )
                        }
                }
            }

            if (p != null) {
                SectionCard(stringResource(R.string.profile_x, p.name)) {
                    Text(stringResource(R.string.which_grade), style = MaterialTheme.typography.labelLarge)
                    GradePicker(p.grade) { g -> scope.launch { c.profiles.update(p.copy(grade = g)) } }
                    Text(stringResource(R.string.which_courses), style = MaterialTheme.typography.labelLarge)
                    CourseEditor(p.courseList) { list ->
                        if (list.isNotEmpty()) scope.launch { c.profiles.update(p.copy(courses = list.joinToString("|"))) }
                    }
                    Text(stringResource(R.string.explain_language), style = MaterialTheme.typography.labelLarge)
                    LanguagePicker(p.language) { code -> scope.launch { c.profiles.update(p.copy(language = code)) } }
                    Text(stringResource(R.string.pick_world), style = MaterialTheme.typography.titleSmall)
                    com.offlinetutor.ui.WorldPicker(p.world) { w -> scope.launch { c.profiles.update(p.copy(world = w)) } }
                    if (com.offlinetutor.domain.ExplainLanguage.of(p.language).beta) {
                        Text(stringResource(R.string.beta_language_note), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pinDialog = true }) {
                            Text(stringResource(if (p.hasPin) R.string.change_pin else R.string.set_pin))
                        }
                        if (p.hasPin) TextButton(onClick = { scope.launch { c.profiles.setPin(p, null) } }) { Text(stringResource(R.string.remove_pin)) }
                    }
                }
            }

            // Every new feature can be turned off to demo / benchmark it on vs off.
            SectionCard(stringResource(R.string.smart_features)) {
                Feature.entries.filter { it.phase <= Feature.IMPLEMENTED_PHASE }.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(featureTitle(f), style = MaterialTheme.typography.titleSmall)
                            Text(featureBody(f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(checked = flags[f] != false, onCheckedChange = { c.flags.set(f, it) })
                    }
                }
            }

            // 3c: Night Shift demo. The real run happens via WorkManager while charging + idle.
            SectionCard(stringResource(R.string.night_shift)) {
                val night by c.night.progress.collectAsStateWithLifecycle()
                Text(stringResource(R.string.night_shift_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (night.running) {
                    Text(stringResource(R.string.night_running, night.done, night.total, night.current ?: "…"),
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { nightJob?.cancel() }) { Text(stringResource(R.string.stop)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            nightJob = c.appScope.launch { c.night.reset(); runCatching { c.night.run(Long.MAX_VALUE, evalMcqs = 5, evalTemplates = 8) } }
                        }) { Text(stringResource(R.string.simulate_night)) }
                        OutlinedButton(onClick = {
                            nightJob = c.appScope.launch { c.night.reset(); runCatching { c.night.run(Long.MAX_VALUE) } }
                        }) { Text(stringResource(R.string.simulate_night_full)) }
                    }
                }
                night.lastSummary?.let { s ->
                    Text(stringResource(R.string.night_last, s), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(stringResource(R.string.debug)) {
                Text(stringResource(R.string.seed_demo_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.Default) { c.demo.seed() }; seeded = true } }) {
                    Text(stringResource(R.string.seed_demo))
                }
                if (seeded) Text(stringResource(R.string.seed_demo_done), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            SectionCard(stringResource(R.string.privacy)) {
                Text(stringResource(R.string.privacy_body), style = MaterialTheme.typography.bodyMedium)
            }

            Button(onClick = switchProfile) { Text(stringResource(R.string.switch_profile)) }
            if (p != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.delete_profile), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (pinDialog && p != null) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text(stringResource(R.string.set_pin)) },
            text = {
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.pin_4_6)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            },
            confirmButton = {
                TextButton(enabled = PinHasher.isValidPin(pin), onClick = {
                    pinDialog = false
                    scope.launch { withContext(Dispatchers.Default) { c.profiles.setPin(p, pin) } }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete && p != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_profile)) },
            text = { Text(stringResource(R.string.delete_profile_confirm, p.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { c.profiles.delete(p); switchProfile() }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun featureTitle(f: Feature): String = stringResource(
    when (f) {
        Feature.DIAGNOSTIC_MCQ -> R.string.ff_diag
        Feature.CONFIDENCE -> R.string.ff_confidence
        Feature.ELO_DIFFICULTY -> R.string.ff_elo
        Feature.SELF_HEALING -> R.string.ff_healing
        Feature.BACKTRACKING -> R.string.ff_backtracking
        Feature.SKILL_TREE -> R.string.ff_tree
        Feature.TEMPLATES -> R.string.ff_templates
        Feature.READY_POOL -> R.string.ff_pool
        Feature.SHADOW_PREGEN -> R.string.ff_shadow
        Feature.NIGHT_SHIFT -> R.string.ff_night
        Feature.PROMPT_PREFILL -> R.string.ff_prefill
        Feature.THERMAL_GOVERNOR -> R.string.ff_thermal
        Feature.ANALOGY_WORLDS -> R.string.ff_analogy
    },
)

@Composable
private fun featureBody(f: Feature): String = stringResource(
    when (f) {
        Feature.DIAGNOSTIC_MCQ -> R.string.ff_diag_body
        Feature.CONFIDENCE -> R.string.ff_confidence_body
        Feature.ELO_DIFFICULTY -> R.string.ff_elo_body
        Feature.SELF_HEALING -> R.string.ff_healing_body
        Feature.BACKTRACKING -> R.string.ff_backtracking_body
        Feature.SKILL_TREE -> R.string.ff_tree_body
        Feature.TEMPLATES -> R.string.ff_templates_body
        Feature.READY_POOL -> R.string.ff_pool_body
        Feature.SHADOW_PREGEN -> R.string.ff_shadow_body
        Feature.NIGHT_SHIFT -> R.string.ff_night_body
        Feature.PROMPT_PREFILL -> R.string.ff_prefill_body
        Feature.THERMAL_GOVERNOR -> R.string.ff_thermal_body
        Feature.ANALOGY_WORLDS -> R.string.ff_analogy_body
    },
)
