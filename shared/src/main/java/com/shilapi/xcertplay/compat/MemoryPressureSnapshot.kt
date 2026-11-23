package com.shilapi.xcertplay.compat

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File

/** Small, privacy-safe memory snapshot for connection and playback diagnostics. */
internal object MemoryPressureSnapshot {
    private const val MIB = 1024L * 1024L

    fun describe(context: Context): String = runCatching { snapshot(context) }
        .getOrElse { "unavailable failureClass=${it.javaClass.simpleName.take(64)}" }

    private data class SystemMemory(
        val availableMb: Long?,
        val totalMb: Long?,
        val heapClassMb: Int?,
        val lowMemory: Boolean?,
        val thresholdMb: Long?,
    )

    private fun snapshot(context: Context): String {
        val info = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val systemMemory = runCatching {
            val reported = manager != null
            manager?.getMemoryInfo(info)
            val available = info.availMem.takeIf { it > 0L }?.div(MIB)
                ?: procMemFieldMb("MemAvailable")
                ?: procMemFieldMb("MemFree")
            val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                info.totalMem.takeIf { it > 0L }?.div(MIB) ?: procMemFieldMb("MemTotal")
            } else {
                procMemFieldMb("MemTotal")
            }
            SystemMemory(
                availableMb = available,
                totalMb = total,
                heapClassMb = runCatching { manager?.memoryClass }.getOrNull(),
                lowMemory = if (reported) info.lowMemory else null,
                thresholdMb = info.threshold.takeIf { it > 0L }?.div(MIB),
            )
        }.getOrNull()

        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L) / MIB
        val heapMaxMb = runtime.maxMemory().coerceAtLeast(0L) / MIB
        val nativeHeapMb = runCatching { Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L) / MIB }
            .getOrNull()

        return "availableMb=${systemMemory?.availableMb ?: "unknown"} " +
            "totalMb=${systemMemory?.totalMb ?: "unknown"} " +
            "heapClassMb=${systemMemory?.heapClassMb ?: "unknown"} " +
            "appHeapUsedMb=$heapUsedMb appHeapMaxMb=$heapMaxMb " +
            "nativeHeapMb=${nativeHeapMb ?: "unknown"} " +
            "systemLowMemory=${systemMemory?.lowMemory ?: "unknown"} " +
            "systemLowMemoryThresholdMb=${systemMemory?.thresholdMb ?: "unknown"}"
    }

    private fun procMemFieldMb(field: String): Long? = runCatching {
        val line = File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("$field:") }
        } ?: return null
        Regex("\\d+").find(line)?.value?.toLongOrNull()?.div(1024L)
    }.getOrNull()
}
