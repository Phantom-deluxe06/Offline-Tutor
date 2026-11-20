package com.offlinetutor.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.offlinetutor.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Per-task generation settings. 1B models break JSON at high temperature, so JSON
 * tasks run at 0.5; chat runs at 0.7. maxNewTokens is capped per task (context 1024).
 */
enum class TaskType(val temperature: Float, val maxNewTokens: Int, val isJson: Boolean) {
    ROADMAP(0.5f, 450, false), // numbered plain-text lines; nested JSON failed at 1B
    MCQ(0.5f, 300, true),
    MCQ_ANSWER(0.3f, 120, true), // follow-up: pick the answer when the MCQ stopped after option D
    CHAT(0.7f, 350, false),
    EXPLAIN(0.6f, 320, false),
    BENCH(0.5f, 128, false),
}

data class GenRequest(
    val task: TaskType,
    val prompt: String,
    /** Text already appended after the assistant header (JSON prefill); it is prepended to the result. */
    val assistantPrefix: String = "",
    val maxNewTokens: Int = task.maxNewTokens,
    val temperature: Float = task.temperature,
    /** Optional early stop on the accumulated text (e.g. "5 roadmap steps written"). */
    val stopWhen: ((String) -> Boolean)? = null,
)

enum class StopReason { END, MAX_TOKENS, REPETITION, JSON_COMPLETE, CONDITION, CANCELLED, ERROR }

data class GenResult(
    val text: String,
    val stopReason: StopReason,
    val guardReason: String?,
    val model: String,
    val promptChars: Int,
    val promptTokensEst: Int,
    val maxNewTokens: Int,
    val temperature: Float,
    val wallMs: Long,
    val appTtftMs: Long?,
    val callbackTokens: Int,
    val stats: GenStats?,
    val rawStats: String?,
    val mem: MemSnapshot,
    val thermalStatus: Int,
    val thermalPolicy: String,
    val error: String?,
) {
    val ttftMs: Long? get() = stats?.ttftMs ?: appTtftMs
    val generatedTokens: Int get() = stats?.generatedTokens ?: callbackTokens
    val decodeTokPerSec: Double?
        get() = stats?.decodeTokPerSec ?: run {
            val ttft = appTtftMs ?: return@run null
            val decodeMs = wallMs - ttft
            if (decodeMs > 0 && callbackTokens > 1) (callbackTokens - 1) * 1000.0 / decodeMs else null
        }
}

sealed interface EngineState {
    data object NoModel : EngineState
    data object Unloaded : EngineState
    data class Loading(val model: String) : EngineState
    data class Ready(val model: String, val loadMs: Long) : EngineState
    data class Busy(val model: String, val task: TaskType) : EngineState
    data class Failed(val message: String) : EngineState
}

class ModelMissingException : IllegalStateException("No model installed. Import a .pte and tokenizer.model first.")

/**
 * Owns the single ExecuTorch LlmModule. Everything native runs on ONE dedicated thread,
 * a Mutex guarantees one generation at a time, and cancelling the calling coroutine calls
 * stop() and waits for the blocking generate() to return before releasing the lock.
 */
class LlmEngine(
    private val context: Context,
    private val store: ModelStore,
    private val thermal: ThermalMonitor,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-worker").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()

    private var module: LlmModule? = null
    private var loaded: ModelSelection? = null

    private val _state = MutableStateFlow<EngineState>(if (store.activeModel() == null) EngineState.NoModel else EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    var lastLoadMs: Long = 0
        private set

    val contextLength: Int = BuildConfig.MODEL_CONTEXT

    fun refreshAvailability() {
        if (module == null) {
            _state.value = if (store.activeModel() == null) EngineState.NoModel else EngineState.Unloaded
        }
    }

    /** Loads (or reloads when the active model changed). Returns load time in ms, 0 if already loaded. */
    suspend fun ensureLoaded(): Long = mutex.withLock { loadLocked().second }

    private suspend fun loadLocked(): Pair<LlmModule, Long> {
        val sel = store.activeModel() ?: run {
            _state.value = EngineState.NoModel
            throw ModelMissingException()
        }
        val current = module
        if (current != null && loaded?.key == sel.key) return current to 0L

        _state.value = EngineState.Loading(sel.name)
        return withContext(NonCancellable + dispatcher) {
            module?.let { old ->
                runCatching { old.stop() }
                runCatching { old.close() }
            }
            module = null
            loaded = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                val m = LlmModule(sel.pte.absolutePath, sel.tokenizer.absolutePath, 0.5f)
                // 1.5.x returns Int (non-zero = error); newer builds return Unit and throw.
                val rc: Any? = m.load()
                if (rc is Int && rc != 0) {
                    runCatching { m.close() }
                    throw IllegalStateException("LlmModule.load() failed with code $rc")
                }
                val ms = SystemClock.elapsedRealtime() - t0
                module = m
                loaded = sel
                lastLoadMs = ms
                _state.value = EngineState.Ready(sel.name, ms)
                m to ms
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
                throw t
            }
        }
    }

    fun activeModelName(): String = loaded?.name ?: store.activeModel()?.name ?: "none"

    suspend fun generate(req: GenRequest, onPartial: (String) -> Unit = {}): GenResult = mutex.withLock {
        val (m, _) = loadLocked()
        val sel = loaded!!

        // Thermal policy: shorten outputs and cool down when the phone is hot.
        val policy = thermal.policy()
        if (policy.coolDownMs > 0) delay(policy.coolDownMs)

        val fullPrompt = req.prompt
        val promptTokensEst = TokenEstimator.estimate(fullPrompt)
        val scaled = max(32, (req.maxNewTokens * policy.tokenScale).toInt())
        val room = contextLength - promptTokensEst - 16
        val maxNew = max(16, min(scaled, room))

        val config = LlmGenerationConfig.create()
            .seqLen(contextLength)
            .maxNewTokens(maxNew)
            .temperature(req.temperature)
            .echo(false)
            .numBos(0) // <|begin_of_text|> is already in the prompt text
            .numEos(0)
            .build()

        val out = StringBuilder(req.assistantPrefix)
        // Structured JSON legitimately repeats phrases across items ("To understand the concept of ..."),
        // so JSON tasks use a looser n-gram rule; tail-loop and char-run checks stay strict.
        val structured = req.task.isJson || req.task == TaskType.ROADMAP
        val guard = if (structured) RepetitionGuard(ngram = 10, maxNgramHits = 4) else RepetitionGuard()
        val json = if (req.task.isJson) JsonCompletionDetector().also { it.feed(req.assistantPrefix) } else null
        val stopRequested = AtomicBoolean(false)
        val stopReason = java.util.concurrent.atomic.AtomicReference(StopReason.END)
        val tokens = AtomicInteger(0)
        val firstTokenAt = AtomicLong(-1)
        var rawStats: String? = null
        var nativeError: String? = null

        _state.value = EngineState.Busy(sel.name, req.task)
        val start = SystemClock.elapsedRealtime()

        val callback = object : LlmCallback {
            override fun onResult(result: String) {
                if (stopRequested.get()) return
                firstTokenAt.compareAndSet(-1, SystemClock.elapsedRealtime())
                val n = tokens.incrementAndGet()
                val piece = SpecialTokens.clean(result)
                var text: String? = null
                if (piece.isNotEmpty()) {
                    text = synchronized(out) { out.append(piece); out.toString() }
                    onPartial(text)
                }
                when {
                    SpecialTokens.isEnd(result) -> requestStop(StopReason.END)
                    guard.feed(piece) -> requestStop(StopReason.REPETITION)
                    json?.feed(piece) == true -> requestStop(StopReason.JSON_COMPLETE)
                    text != null && req.stopWhen?.invoke(text) == true -> requestStop(StopReason.CONDITION)
                    // Measured on-device: the 1.5.1 runner fills up to seqLen and ignores
                    // maxNewTokens, so the cap is enforced here.
                    n >= maxNew -> requestStop(StopReason.MAX_TOKENS)
                }
            }

            private fun requestStop(reason: StopReason) {
                if (stopRequested.compareAndSet(false, true)) {
                    stopReason.set(reason)
                    runCatching { m.stop() } // allowed from inside onResult
                }
            }

            override fun onStats(stats: String) {
                rawStats = stats
            }

            override fun onError(errorCode: Int, message: String) {
                nativeError = "native error $errorCode: $message"
            }
        }

        val done = CompletableDeferred<Unit>()
        executor.execute {
            try {
                m.resetContext() // every task sends its full prompt; never reuse a stale KV cache
                m.generate(fullPrompt, config, callback)
            } catch (t: Throwable) {
                Log.e(TAG, "generate failed", t)
                nativeError = t.message ?: t.javaClass.simpleName
            } finally {
                done.complete(Unit)
            }
        }
        try {
            done.await()
        } catch (ce: CancellationException) {
            stopRequested.set(true)
            stopReason.set(StopReason.CANCELLED)
            runCatching { m.stop() }
            withContext(NonCancellable) { done.await() } // never release the lock while native code runs
            _state.value = EngineState.Ready(sel.name, lastLoadMs)
            throw ce
        }

        val wall = SystemClock.elapsedRealtime() - start
        val first = firstTokenAt.get()
        _state.value = EngineState.Ready(sel.name, lastLoadMs)
        val reason = if (nativeError != null && tokens.get() == 0) StopReason.ERROR else stopReason.get()
        GenResult(
            text = synchronized(out) { out.toString() }.trim(),
            stopReason = reason,
            guardReason = guard.reason,
            model = sel.name,
            promptChars = fullPrompt.length,
            promptTokensEst = promptTokensEst,
            maxNewTokens = maxNew,
            temperature = req.temperature,
            wallMs = wall,
            appTtftMs = if (first > 0) first - start else null,
            callbackTokens = tokens.get(),
            stats = StatsParser.parse(rawStats),
            rawStats = rawStats,
            mem = MemoryProbe.read(context),
            thermalStatus = thermal.currentStatus(),
            thermalPolicy = policy.label,
            error = nativeError,
        )
    }

    /** Stops any generation, waits for it, then frees native memory. */
    suspend fun unload() = mutex.withLock {
        withContext(NonCancellable + dispatcher) {
            module?.let { m ->
                runCatching { m.stop() }
                runCatching { m.close() }
            }
            module = null
            loaded = null
        }
        refreshAvailability()
    }

    private companion object { const val TAG = "LlmEngine" }
}
