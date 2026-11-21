package com.offlinetutor.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinetutor.R
import com.offlinetutor.domain.ServeStats
import com.offlinetutor.ui.SectionCard
import com.offlinetutor.ui.container
import kotlinx.coroutines.delay
import java.util.Locale

private fun secs(ms: Long?) = ms?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "–"

/** Phase 3 metrics: perceived wait (pool vs live), pool hit rate, time per tagged question, background queue. */
@Composable
fun ZeroWaitCard() {
    val c = container()
    val sched by c.scheduler.state.collectAsStateWithLifecycle()
    val poolFlow = remember { c.db.pool().observeTotal() }
    val poolSize by poolFlow.collectAsStateWithLifecycle(initialValue = 0)
    var stats by remember { mutableStateOf<ServeStats?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            stats = ServeStats.from(c.quiz.recentServed(200).map {
                ServeStats.Row(it.servedFrom, it.waitMs, it.genMs, it.source, !it.optionMeta.isNullOrBlank())
            })
            delay(3000)
        }
    }
    SectionCard(stringResource(R.string.zero_wait)) {
        val s = stats
        KV(stringResource(R.string.pool_ready), poolSize.toString())
        KV(stringResource(R.string.pool_hit_rate, s?.window ?: 10),
            s?.hitRateLast?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "–")
        KV(stringResource(R.string.wait_pool), secs(s?.medianWaitPoolMs))
        KV(stringResource(R.string.wait_instant), secs(s?.medianWaitInstantMs))
        KV(stringResource(R.string.wait_live), secs(s?.medianWaitLiveMs))
        KV(stringResource(R.string.tagged_gen_time), secs(s?.medianTaggedGenMs))
        KV(stringResource(R.string.bg_queue), buildString {
            append(sched.queued)
            sched.running?.let { append(" · ${sched.runningPriority} running") }
            if (sched.paused) append(" · paused (hot)")
            append(" · preempted ${sched.preempted}")
        })
        Text(stringResource(R.string.zero_wait_note, s?.served ?: 0), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
