package com.offlinetutor.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class MemSnapshot(
    val vmHwmKb: Long,   // peak resident set size of this process (VmHWM)
    val vmRssKb: Long,   // current resident set size (VmRSS)
    val deviceTotalKb: Long,
    val deviceAvailKb: Long,
    val lowMemory: Boolean,
)

object MemoryProbe {
    fun read(context: Context): MemSnapshot {
        var hwm = -1L
        var rss = -1L
        try {
            File("/proc/self/status").forEachLine { line ->
                when {
                    line.startsWith("VmHWM:") -> hwm = kb(line)
                    line.startsWith("VmRSS:") -> rss = kb(line)
                }
            }
        } catch (_: Exception) {
        }
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return MemSnapshot(hwm, rss, mi.totalMem / 1024, mi.availMem / 1024, mi.lowMemory)
    }

    private fun kb(line: String): Long =
        line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L
}

/** What the engine should do under the current thermal state. */
data class ThermalPolicy(val tokenScale: Float, val coolDownMs: Long, val label: String)

/**
 * Watches PowerManager thermal status / headroom. Coroutines alone do not prevent
 * throttling, so at SEVERE+ we shorten outputs and insert a cool-down before generating.
 */
class ThermalMonitor(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<Int> = _status.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            pm.addThermalStatusListener(context.mainExecutor) { _status.value = it }
        }
    }

    fun currentStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) pm.currentThermalStatus else 0

    /** Forecast headroom over 10 s: 1.0 means the device is about to throttle. Null if unsupported. */
    fun headroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pm == null) return null
        val h = pm.getThermalHeadroom(10)
        return if (h.isNaN() || h < 0f) null else h
    }

    fun policy(): ThermalPolicy {
        val s = currentStatus()
        val h = headroom() ?: 0f
        return when {
            s >= STATUS_CRITICAL -> ThermalPolicy(0.35f, 30_000, "critical")
            s >= STATUS_SEVERE || h >= 1.0f -> ThermalPolicy(0.5f, 15_000, "severe")
            s >= STATUS_MODERATE || h >= 0.85f -> ThermalPolicy(0.75f, 3_000, "moderate")
            else -> ThermalPolicy(1f, 0, "normal")
        }
    }

    companion object {
        // PowerManager.THERMAL_STATUS_* values (API 29), inlined so minSdk 26 code compiles cleanly.
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4

        fun label(status: Int) = when (status) {
            0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"
            4 -> "critical"; 5 -> "emergency"; 6 -> "shutdown"; else -> "unknown"
        }
    }
}

/** 3e: is background work of [priority] paused right now? */
fun ThermalMonitor.paused(priority: Priority): Boolean = ThermalRules.paused(priority, currentStatus(), headroom())
