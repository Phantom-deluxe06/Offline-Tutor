package com.offlinetutor.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.PriorityQueue

/** 3a: who is asking for the single LLM worker. Lower ordinal = more urgent. */
enum class Priority {
    /** Live user request: chat, explain, a micro-fix the student opened, the question they are waiting for. */
    P0,
    /** Pool refill / shadow pre-generation. */
    P1,
    /** Night batch (pool for tomorrow, eval batch). */
    P2,
}

/** Thrown to a lower-priority caller whose generation was stopped for a more urgent one; the caller requeues. */
class PreemptedException : IllegalStateException("preempted by a higher-priority request")

/**
 * 3a: a lock in front of the single LLM worker that is granted in priority order (FIFO within a priority) and
 * preempts: when a more urgent caller arrives while a less urgent one holds the lock, the holder's [Holder.onPreempt]
 * runs once (the engine calls `stop()` there and waits for the native call to return before releasing).
 * Pure Kotlin so it is unit-tested on the JVM.
 */
class PriorityGate {
    class Holder internal constructor(val priority: Priority) {
        @Volatile var preempted: Boolean = false
            internal set
        /** Set by the holder while it runs something stoppable; invoked at most once on preemption. */
        @Volatile var onPreempt: (() -> Unit)? = null
    }

    private class Waiter(val holder: Holder, val seq: Long) {
        val granted = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private var current: Holder? = null
    private var seq = 0L
    private val waiters = PriorityQueue<Waiter>(compareBy<Waiter>({ it.holder.priority.ordinal }, { it.seq }))

    /** Priority of the caller currently holding the gate, or null when idle. */
    val holderPriority: Priority? get() = synchronized(lock) { current?.priority }

    val waiting: Int get() = synchronized(lock) { waiters.size }

    suspend fun <T> withPermit(priority: Priority, block: suspend (Holder) -> T): T {
        val h = acquire(priority)
        try {
            return block(h)
        } finally {
            release(h)
        }
    }

    private suspend fun acquire(priority: Priority): Holder {
        val h = Holder(priority)
        val w: Waiter
        var toPreempt: Holder? = null
        synchronized(lock) {
            if (current == null && waiters.isEmpty()) {
                current = h
                return h
            }
            w = Waiter(h, seq++)
            waiters.add(w)
            val cur = current
            if (cur != null && cur.priority.ordinal > priority.ordinal && !cur.preempted) {
                cur.preempted = true
                toPreempt = cur
            }
        }
        toPreempt?.onPreempt?.let { runCatching(it) }
        try {
            w.granted.await()
        } catch (ce: CancellationException) {
            val stillWaiting = synchronized(lock) { waiters.remove(w) }
            if (!stillWaiting) release(h) // granted concurrently with the cancel: hand the gate on
            throw ce
        }
        return h
    }

    private fun release(h: Holder) {
        synchronized(lock) {
            if (current !== h) return
            val next = waiters.poll()
            current = next?.holder
            next?.granted?.complete(Unit)
        }
    }
}

/**
 * 3a: marks every engine call made inside `withContext(GenPriority(P1)) { … }` with that priority, so background
 * jobs (pool refill, night batch) need no extra parameters through the generators.
 */
class GenPriority(val priority: Priority) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<GenPriority>
}

/**
 * 3e: thermal governor rules (same thresholds as [ThermalMonitor.policy], which already shrinks maxNewTokens and adds
 * cool-downs from MODERATE): MODERATE or above pauses P2 (night batch); SEVERE or above pauses P1 and P2. Live
 * requests (P0) are never paused. Headroom (API 30+) forecasts throttling: >= 0.85 counts as MODERATE, >= 1.0 as SEVERE.
 */
object ThermalRules {
    fun level(status: Int, headroom: Float?): Int {
        val h = headroom ?: 0f
        return when {
            status >= ThermalMonitor.STATUS_SEVERE || h >= 1.0f -> ThermalMonitor.STATUS_SEVERE
            status >= ThermalMonitor.STATUS_MODERATE || h >= 0.85f -> ThermalMonitor.STATUS_MODERATE
            else -> status
        }
    }

    fun paused(priority: Priority, status: Int, headroom: Float?): Boolean {
        val l = level(status, headroom)
        return when (priority) {
            Priority.P0 -> false
            Priority.P1 -> l >= ThermalMonitor.STATUS_SEVERE
            Priority.P2 -> l >= ThermalMonitor.STATUS_MODERATE
        }
    }
}
