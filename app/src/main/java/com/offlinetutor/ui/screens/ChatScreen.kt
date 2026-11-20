package com.offlinetutor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.offlinetutor.AppContainer
import com.offlinetutor.R
import com.offlinetutor.data.ChatMessage
import com.offlinetutor.domain.ExplainLanguage
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.ui.BetaChip
import com.offlinetutor.ui.ModelStatusCard
import com.offlinetutor.ui.appViewModel
import com.offlinetutor.ui.design.EmptyState
import com.offlinetutor.ui.design.GuruAvatar
import com.offlinetutor.ui.design.TypingDots
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.design.enterAnimation
import com.offlinetutor.ui.languageLabel
import com.offlinetutor.ui.theme.LocalExtras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatVm(private val c: AppContainer) : ViewModel() {
    val profile = c.activeProfile
    val engine = c.engine.state
    val messages = profile.flatMapLatest { p -> p?.let { c.chat.observe(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ChatMessage>())
    val streaming = MutableStateFlow<String?>(null)
    val noModel = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var job: Job? = null

    fun send(text: String) {
        val p = profile.value ?: return
        if (text.isBlank() || job?.isActive == true) return
        error.value = null
        job = viewModelScope.launch {
            streaming.value = ""
            try {
                c.chat.send(p, text, null) { partial -> streaming.value = partial }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelMissingException) {
                noModel.value = true
            } catch (e: Throwable) {
                error.value = e.message
            } finally {
                streaming.value = null
            }
        }
    }

    fun stop() { job?.cancel() }

    fun clear() {
        val p = profile.value ?: return
        viewModelScope.launch { c.chat.clear(p.id) }
    }
}

@Composable
fun ChatScreen(prefill: String, openModels: () -> Unit) {
    val vm = appViewModel { ChatVm(it) }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val noModel by vm.noModel.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    var input by rememberSaveable(prefill) { mutableStateOf(prefill) }
    val listState = rememberLazyListState()
    val p = profile ?: return
    val lang = ExplainLanguage.of(p.language)
    val busy = streaming != null

    LaunchedEffect(messages.size, streaming?.length) {
        val count = messages.size + if (streaming != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val suggestions = buildList {
        add(stringResource(R.string.sugg_example))
        add(stringResource(R.string.sugg_simpler))
        add(stringResource(R.string.sugg_practice))
        if (lang != ExplainLanguage.EN) add(stringResource(R.string.sugg_in_lang, languageLabel(lang)))
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GuruAvatar(42.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guru_name), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reply_language, languageLabel(lang)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (lang.beta) { Spacer(Modifier.width(6.dp)); BetaChip() }
                }
            }
            IconButton(onClick = vm::clear, enabled = !busy) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.clear_chat)) }
        }
        if (noModel) Box(Modifier.padding(16.dp)) { ModelStatusCard(engine, openModels) }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && streaming == null) {
                item {
                    EmptyState(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_empty_title), stringResource(R.string.chat_empty))
                    if (lang.beta) Text(stringResource(R.string.beta_language_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m.text, fromUser = m.role == "user", Modifier.enterAnimation()) }
            streaming?.let { s -> item(key = "stream") { Bubble(s, fromUser = false, Modifier.enterAnimation(), typing = s.isBlank()) } }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }

        AnimatedVisibility(input.isBlank() && !busy && messages.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    Text(
                        s,
                        Modifier.bounceClick { vm.send(s) }
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input,
                onValueChange = { input = it.take(600) },
                placeholder = { Text(stringResource(R.string.ask_anything)) },
                modifier = Modifier.weight(1f).border(1.5.dp, LocalExtras.current.cardEdge, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(10.dp))
            val canSend = busy || input.isNotBlank()
            Box(
                Modifier.size(54.dp)
                    .bounceClick(enabled = canSend) { if (busy) vm.stop() else { vm.send(input); input = "" } }
                    .background(
                        if (busy) MaterialTheme.colorScheme.tertiary else if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    stringResource(if (busy) R.string.stop else R.string.send),
                    tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, modifier: Modifier = Modifier, typing: Boolean = false) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!fromUser) { GuruAvatar(30.dp); Spacer(Modifier.width(8.dp)) }
        val shape = RoundedCornerShape(
            topStart = 20.dp, topEnd = 20.dp,
            bottomStart = if (fromUser) 20.dp else 6.dp, bottomEnd = if (fromUser) 6.dp else 20.dp,
        )
        Box(
            Modifier.widthIn(max = 300.dp)
                .background(if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
                .then(if (fromUser) Modifier else Modifier.border(1.5.dp, LocalExtras.current.cardEdge, shape))
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            if (typing) TypingDots(MaterialTheme.colorScheme.primary)
            else Text(text, color = if (fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}
