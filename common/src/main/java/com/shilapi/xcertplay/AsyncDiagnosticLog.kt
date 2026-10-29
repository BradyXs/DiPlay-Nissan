package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    private data class Entry(
        val target: SessionLogFile,
        val line: String? = null,
        val resetHeader: String? = null,
        val close: Boolean = false,
    )
    private val writer = BoundedDiagnosticWriter<Entry>(maxPending = 512) {
        when {
            it.close -> it.target.close()
            it.resetHeader != null -> it.target.reset(it.resetHeader)
            else -> it.line?.let(it.target::append)
        }
    }

    fun reset(target: SessionLogFile, header: String) {
        writer.enqueue(Entry(target, resetHeader = header))
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))
        runCatching { writer.enqueue(Entry(target, line = "$timestamp  $safe")) }
    }

    /** Queue closure after earlier entries from this activity. */
    fun close(target: SessionLogFile?) {
        if (target != null) writer.enqueue(Entry(target, close = true))
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
    internal val droppedCount: Long get() = writer.droppedCount
}
