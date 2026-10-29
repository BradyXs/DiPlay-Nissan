package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** Export only the latest Java crash's type and stack frames, never raw exception messages. */
internal object CrashDiagnosticSnapshot {
    private const val MAX_TAIL_BYTES = 32 * 1024L
    private const val MAX_STACK_FRAMES = 48
    private const val CRASH_MARKER = "FATAL EXCEPTION"
    private val exceptionType = Regex("[A-Za-z_$][A-Za-z0-9_$.]*Exception|[A-Za-z_$][A-Za-z0-9_$.]*Error")
    private val missingClass = Regex("NoClassDefFoundError: ([A-Za-z0-9_.$/]+)")
    private val stackFrame = Regex("\\s*at ([A-Za-z0-9_.$]+)\\(([A-Za-z0-9_.$]+(?::[0-9]+)?|Unknown Source|Native Method)\\)")

    fun report(context: Context): String {
        val file = File(context.filesDir, "logs/diplay_crash.log")
        if (!file.isFile || file.length() == 0L) return "Java crash record available=false"
        return runCatching {
            val tail = RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                val size = minOf(length, MAX_TAIL_BYTES).toInt()
                input.seek(length - size)
                ByteArray(size).also { input.readFully(it) }
            }
            val text = String(tail, Charsets.UTF_8)
            val latest = text.lastIndexOf(CRASH_MARKER).takeIf { it >= 0 }
                ?.let { text.substring(it) } ?: return@runCatching "Java crash record available=false marker_missing"
            var frames = 0
            buildString {
                appendLine("Java crash record available=true (latest retained record)")
                latest.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    val safe = when {
                        trimmed.startsWith("Time: ") -> trimmed.take(40)
                        trimmed.startsWith("Thread: ") ->
                            "Thread: " + trimmed.removePrefix("Thread: ")
                                .replace(Regex("[^A-Za-z0-9 _().=-]"), "?").take(100)
                        trimmed.startsWith("Exception: ") || trimmed.startsWith("Caused by: ") ->
                            exceptionType.find(trimmed)?.value?.let { "Exception type: $it" }
                        frames < MAX_STACK_FRAMES -> stackFrame.matchEntire(line)?.let {
                            frames++
                            "at ${it.groupValues[1]}(${it.groupValues[2]})"
                        }
                        else -> null
                    }
                    if (safe != null) DiagnosticRedactor.redact(safe)?.let { appendLine(it) }
                    if (trimmed.startsWith("Exception: ") || trimmed.startsWith("Caused by: ")) {
                        missingClass.find(trimmed)?.groupValues?.get(1)?.take(120)?.let {
                            appendLine("Missing class: $it")
                        }
                    }
                }
            }.trimEnd()
        }.getOrElse { "Java crash record available=false error=${it.javaClass.simpleName}" }
    }
}
