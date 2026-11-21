package com.offlinetutor.data

import android.util.Log
import com.offlinetutor.llm.GenPriority
import com.offlinetutor.llm.ModelMissingException
import com.offlinetutor.llm.PreemptedException
import com.offlinetutor.llm.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

/**
 * 3a: background work (P1 pool refill, P2 night batch) in front of the single LLM worker. Live requests (P0)
 * don't queue here: they call the engine directly and preempt whatever background generation is running; the
 * preempted job is put back in this queue and runs again from the start.
 *
 * Jobs with the same [Job.key] are not queued twice. [pausedFor] lets the thermal governor (3e) hold
 * background priorities.
 */
class GenerationScheduler(
    private val scope: CoroutineScope,
    private val pausedFor: (Priority) -> Boolean = { false },
) {
    class Job(val priority: Priority, val key: String, val run: suspend () -> Unit) {
        internal var seq = 0L
        internal var preemptions = 0
    }

    data class State(val queued: Int = 0, val running: String? = null, val runningPriority: Priority? = null,
                     val preempted: Int = 0, val done: Int = 0, val paused: Boolean = false)

    private val lock = Any()
    private val queue = PriorityQueue<Job>(compareBy<Job>({ it.priority.ordinal }, { it.seq }))
    private val keys = HashSet<String>()
    private var seq = 0L
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        scope.launch { loop() }
    }

    /** Queues [job] unless a job with the same key is queued or running. Returns true if it was queued. */
    fun submit(job: Job): Boolean {
        require(job.priority != Priority.P0) { "P0 requests call the engine directly" }
        synchronized(lock) {
            if (!keys.add(job.key)) return false
            job.seq = seq++
            queue.add(job)
            _state.value = _state.value.copy(queued = queue.size)
        }
        wake.trySend(Unit)
        return true
    }

    fun isQueued(key: String): Boolean = synchronized(lock) { key in keys }

    fun cancelAll(priority: Priority) {
        synchronized(lock) {
            queue.removeAll { j -> (j.priority == priority).also { if (it) keys.remove(j.key) } }
            _state.value = _state.value.copy(queued = queue.size)
        }
    }

    private suspend fun loop() {
        while (true) {
            val job = synchronized(lock) { queue.poll() }
            if (job == null) {
                _state.value = _state.value.copy(queued = 0, running = null, runningPriority = null)
                wake.receive()
                continue
            }
            // 3e: background priorities wait while the device is hot.
            while (pausedFor(job.priority)) {
                _state.value = _state.value.copy(paused = true, queued = synchronized(lock) { queue.size + 1 })
                delay(PAUSE_POLL_MS)
            }
            _state.value = _state.value.copy(paused = false, running = job.key, runningPriority = job.priority,
                queued = synchronized(lock) { queue.size })
            var requeue = false
            try {
                withContext(GenPriority(job.priority)) { job.run() }
            } catch (_: PreemptedException) {
                // A live request took the worker: put the job back and run it again later.
                job.preemptions++
                requeue = job.preemptions <= MAX_PREEMPTIONS
                _state.value = _state.value.copy(preempted = _state.value.preempted + 1)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: ModelMissingException) {
                // no model: nothing to pre-generate
            } catch (t: Throwable) {
                Log.w(TAG, "background job ${job.key} failed", t)
            }
            synchronized(lock) {
                if (requeue) { job.seq = seq++; queue.add(job) } else keys.remove(job.key)
            }
            if (!requeue) _state.value = _state.value.copy(done = _state.value.done + 1)
        }
    }

    private companion object {
        const val TAG = "GenScheduler"
        const val PAUSE_POLL_MS = 15_000L
        const val MAX_PREEMPTIONS = 5
    }
}
