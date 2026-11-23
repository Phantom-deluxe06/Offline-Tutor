package com.offlinetutor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetutor.R
import com.offlinetutor.data.Profile
import com.offlinetutor.domain.PinHasher
import com.offlinetutor.ui.CourseEditor
import com.offlinetutor.ui.GradePicker
import com.offlinetutor.ui.LanguagePicker
import com.offlinetutor.ui.WorldPicker
import com.offlinetutor.ui.PaperTopBar
import com.offlinetutor.ui.container
import com.offlinetutor.ui.design.ChunkyButton
import com.offlinetutor.ui.design.EmptyState
import com.offlinetutor.ui.design.GuruAvatar
import com.offlinetutor.ui.design.LetterAvatar
import com.offlinetutor.ui.design.SoftCard
import com.offlinetutor.ui.design.SubjectBadge
import com.offlinetutor.ui.design.enterAnimation
import com.offlinetutor.ui.design.shake
import com.offlinetutor.ui.paperFieldColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfilesScreen(autoEnter: Boolean, onCreate: () -> Unit, onEnter: () -> Unit, onJoinBattle: () -> Unit = {}) {
    val c = container()
    val profilesFlow = remember { c.profiles.observeAll() }
    val profiles by profilesFlow.collectAsStateWithLifecycle(initialValue = null)
    val activeId by c.session.activeProfileId.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var autoTried by rememberSaveable { mutableStateOf(!autoEnter) }

    LaunchedEffect(profiles) {
        val list = profiles ?: return@LaunchedEffect
        if (!autoTried) {
            autoTried = true
            val p = list.firstOrNull { it.id == activeId }
            if (p != null && !p.hasPin) onEnter()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!profiles.isNullOrEmpty()) {
                ChunkyButton(stringResource(R.string.new_profile), onCreate,
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), icon = Icons.Filled.PersonAdd)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).statusBarsPadding().padding(horizontal = 20.dp).fillMaxSize()) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.enterAnimation(0)) {
                GuruAvatar(52.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.enterAnimation(1))
            Text(stringResource(R.string.profiles_subtitle), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.enterAnimation(2))
            // 5a model-less mode: classmates can join a battle without a profile or the AI model.
            androidx.compose.material3.TextButton(onClick = onJoinBattle) { Text(stringResource(R.string.join_battle_nomodel)) }
            Spacer(Modifier.height(20.dp))
            val list = profiles.orEmpty()
            if (profiles != null && list.isEmpty()) {
                Box(Modifier.enterAnimation(3)) {
                    EmptyState(Icons.Filled.School, stringResource(R.string.welcome_title), stringResource(R.string.welcome_body)) {
                        ChunkyButton(stringResource(R.string.new_profile), onCreate, Modifier.fillMaxWidth(), icon = Icons.Filled.PersonAdd)
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                    SoftCard(Modifier.enterAnimation(3 + i), onClick = {
                        if (p.hasPin) pinFor = p else { c.session.setActive(p.id); onEnter() }
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LetterAvatar(p.name, 56.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.grade_n, p.grade), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    p.courseList.take(5).forEach { SubjectBadge(it, 28.dp) }
                                }
                            }
                            Icon(if (p.hasPin) Icons.Filled.Lock else Icons.AutoMirrored.Filled.ArrowForward,
                                if (p.hasPin) stringResource(R.string.pin_locked) else null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    pinFor?.let { p ->
        PinDialog(
            title = stringResource(R.string.enter_pin_for, p.name),
            onDismiss = { pinFor = null },
            check = { pin -> withContext(Dispatchers.Default) { c.profiles.checkPin(p, pin) } },
            onSuccess = { pinFor = null; c.session.setActive(p.id); onEnter() },
        )
    }
}

@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, check: suspend (String) -> Boolean, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var shakes by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6); wrong = false },
                label = { Text(stringResource(R.string.pin)) },
                isError = wrong,
                supportingText = { if (wrong) Text(stringResource(R.string.pin_wrong)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.shake(shakes),
            )
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { if (check(pin)) onSuccess() else { wrong = true; shakes++ } } }) {
                Text(stringResource(R.string.unlock), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
fun IntakeScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val c = container()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var grade by rememberSaveable { mutableStateOf(8) }
    var courses by remember { mutableStateOf(listOf("Mathematics", "Science")) }
    var language by rememberSaveable { mutableStateOf("en") }
    var world by rememberSaveable { mutableStateOf("village") }
    var usePin by rememberSaveable { mutableStateOf(false) }
    var pin by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val pinOk = !usePin || PinHasher.isValidPin(pin)
    val canSave = name.isNotBlank() && courses.isNotEmpty() && pinOk && !saving

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PaperTopBar(stringResource(R.string.intake_title), navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            })
        },
        bottomBar = {
            ChunkyButton(
                stringResource(R.string.start_learning),
                {
                    saving = true
                    scope.launch {
                        withContext(Dispatchers.Default) { c.profiles.create(name, grade, courses, language, if (usePin) pin else null, world) }
                        onDone()
                    }
                },
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                enabled = canSave, icon = Icons.Filled.RocketLaunch,
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StepCard(1, stringResource(R.string.your_name), 0) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) },
                    placeholder = { Text(stringResource(R.string.your_name)) }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), colors = paperFieldColors(),
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            StepCard(2, stringResource(R.string.which_grade), 1) { GradePicker(grade) { grade = it } }
            StepCard(3, stringResource(R.string.which_courses), 2) { CourseEditor(courses) { courses = it } }
            StepCard(4, stringResource(R.string.explain_language), 3) {
                LanguagePicker(language) { language = it }
                AnimatedVisibility(com.offlinetutor.domain.ExplainLanguage.of(language).beta, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(stringResource(R.string.beta_language_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StepCard(5, stringResource(R.string.pick_world), 4) {
                WorldPicker(world) { world = it }
                Text(stringResource(R.string.pick_world_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StepCard(6, stringResource(R.string.protect_with_pin), 5) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pin_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = usePin, onCheckedChange = { usePin = it })
                }
                AnimatedVisibility(usePin, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.pin_4_6)) }, singleLine = true,
                        isError = pin.isNotEmpty() && !pinOk, shape = RoundedCornerShape(16.dp), colors = paperFieldColors(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepCard(n: Int, title: String, index: Int, content: @Composable () -> Unit) {
    SoftCard(Modifier.enterAnimation(index)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}
