package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var codecOutput = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var lastSenderTimestampNs = 0L
    private var maxSenderFrameGapNs = 0L
    private var senderFrameGapSamples = 0
    private var senderTimestampNonIncreasing = 0
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val decodeMicros = IntArray(1024)
    private var decodeSamples = 0
    private val queueAgeMicros = IntArray(1024)
    private var queueAgeSamples = 0
    private val inputWaitMicros = IntArray(1024)
    private var inputWaitSamples = 0
    private var late = 0
    private var pacingDelayNanos = 0L

    @Synchronized fun onReceived(size: Int, senderTimestampNs: Long = 0L) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        if (senderTimestampNs > 0L) {
            if (lastSenderTimestampNs > 0L) {
                val senderGapNs = senderTimestampNs - lastSenderTimestampNs
                if (senderGapNs > 0L) {
                    maxSenderFrameGapNs = maxOf(maxSenderFrameGapNs, senderGapNs)
                    senderFrameGapSamples++
                } else {
                    senderTimestampNonIncreasing++
                }
            }
            lastSenderTimestampNs = senderTimestampNs
        }
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() { rendered++ }

    @Synchronized fun onCodecOutput() { codecOutput++ }

    @Synchronized fun onRecovery() { recoveries++ }

    /** Time from queueing a frame into the decoder to dequeueing its output. */
    @Synchronized fun onDecodeLatency(ns: Long) {
        if (decodeSamples < decodeMicros.size) decodeMicros[decodeSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Time a received frame waited in the app's decoder work queue before processing began. */
    @Synchronized fun onDecoderQueueAge(ns: Long) {
        if (queueAgeSamples < queueAgeMicros.size) {
            queueAgeMicros[queueAgeSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** Time spent waiting for a MediaCodec input slot, including output draining between polls. */
    @Synchronized fun onInputWait(ns: Long) {
        if (inputWaitSamples < inputWaitMicros.size) {
            inputWaitMicros[inputWaitSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** A paced frame left the decoder after its display time and was shown at once. */
    @Synchronized fun onLate() { late++ }

    /** Smooth video's current display delay ([PacingDelay]). */
    @Synchronized fun onPacingDelay(ns: Long) { pacingDelayNanos = ns }

    private fun decodeSummary(): String {
        if (decodeSamples == 0 && queueAgeSamples == 0 && inputWaitSamples == 0) return ""
        fun summary(name: String, samples: Int, values: IntArray): String {
            if (samples == 0) return ""
            val sorted = values.copyOf(samples).also { it.sort() }
            fun percentileMs(p: Int) = sorted[(samples - 1) * p / 100] / 1000
            return " $name p50=${percentileMs(50)}ms p90=${percentileMs(90)}ms max=${sorted.last() / 1000}ms"
        }
        val delay = if (pacingDelayNanos > 0) " delay=%dms".format(pacingDelayNanos / 1_000_000) else ""
        val decodeStatus = if (decodeSamples > 0) " late=$late$delay" else ""
        return summary("decoderQueueAge", queueAgeSamples, queueAgeMicros) +
            summary("codecInputWait", inputWaitSamples, inputWaitMicros) +
            summary("decode", decodeSamples, decodeMicros) +
            decodeStatus
    }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && codecOutput == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps codecOut=%.1ffps shown=%.1ffps maxGap=%dms " +
            "senderFrameGapMaxMs=%d senderFrameGapSamples=%d senderTimestampNonIncreasing=%d kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, codecOutput / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            maxSenderFrameGapNs / 1_000_000, senderFrameGapSamples, senderTimestampNonIncreasing,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + decodeSummary()
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; codecOutput = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        maxSenderFrameGapNs = 0; senderFrameGapSamples = 0; senderTimestampNonIncreasing = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        decodeSamples = 0; queueAgeSamples = 0; inputWaitSamples = 0; late = 0
        return line
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}
