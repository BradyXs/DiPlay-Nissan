package com.shilapi.xcertplay.airplay

/** TCP receive and serial frame-processing timing; contains no payloads or endpoint data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var lastSenderTimestampNs = 0L
    private var maxSenderFrameGapNs = 0L
    private var senderFrameGapSamples = 0
    private var senderTimestampNonIncreasing = 0
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0
    private var sequenceSamples = 0
    private var decryptSamples = 0
    private var decryptBytes = 0L
    private var decryptSumNs = 0L
    private var maxDecryptNs = 0L
    private var conscryptFrames = 0
    private var otherProviderFrames = 0
    private var javaFallbackFrames = 0
    private val javaFallbackReasons = linkedMapOf<String, Int>()
    private var queueWaitSamples = 0
    private var queueWaitSumNs = 0L
    private var maxQueueWaitNs = 0L
    private var conversionSamples = 0
    private var conversionSumNs = 0L
    private var maxConversionNs = 0L
    private var dispatchSamples = 0
    private var dispatchSumNs = 0L
    private var maxDispatchNs = 0L

    @Synchronized fun reading() { readStart = nowNs() }

    @Synchronized fun received(
        size: Int,
        sequence: Int? = null,
        timestamp: Int? = null,
        senderTimestampNanos: Long? = null,
    ) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        if (senderTimestampNanos != null && senderTimestampNanos > 0L) {
            val previousSenderTimestampNs = lastSenderTimestampNs
            if (previousSenderTimestampNs > 0L) {
                val senderGapNs = senderTimestampNanos - previousSenderTimestampNs
                if (senderGapNs > 0L) {
                    maxSenderFrameGapNs = maxOf(maxSenderFrameGapNs, senderGapNs)
                    senderFrameGapSamples++
                } else {
                    senderTimestampNonIncreasing++
                }
            }
            lastSenderTimestampNs = senderTimestampNanos
        }
        packets++
        bytes += size
        if (sequence != null) {
            sequenceSamples++
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    /** One payload opened in [durationNs]; reported only for streams that call it. */
    @Synchronized fun decrypted(
        durationNs: Long,
        size: Int,
        provider: String? = null,
        fallbackReason: String? = null,
    ) {
        decryptSamples++
        decryptBytes += size
        decryptSumNs += durationNs
        maxDecryptNs = maxOf(maxDecryptNs, durationNs)
        when (provider) {
            "Conscrypt" -> conscryptFrames++
            "BouncyCastle" -> {
                javaFallbackFrames++
                val reason = fallbackReason ?: "unknown"
                javaFallbackReasons[reason] = (javaFallbackReasons[reason] ?: 0) + 1
            }
            null -> Unit
            else -> otherProviderFrames++
        }
    }

    @Synchronized fun queueWaited(durationNs: Long) {
        queueWaitSamples++
        queueWaitSumNs += durationNs
        maxQueueWaitNs = maxOf(maxQueueWaitNs, durationNs)
    }

    @Synchronized fun converted(durationNs: Long) {
        conversionSamples++
        conversionSumNs += durationNs
        maxConversionNs = maxOf(maxConversionNs, durationNs)
    }

    @Synchronized fun dispatched(durationNs: Long) {
        dispatchSamples++
        dispatchSumNs += durationNs
        maxDispatchNs = maxOf(maxDispatchNs, durationNs)
    }

    @Synchronized fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    /** Record serial decrypt/dispatch work on its worker, separate from the TCP reader thread. */
    @Synchronized fun processed(durationNs: Long) {
        maxProcessNs = maxOf(maxProcessNs, durationNs)
        flush()
    }

    @Synchronized fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        val sequenceSummary = if (sequenceSamples == 0) {
            "seqForwardGaps=not-sampled"
        } else {
            "seqForwardGaps=$forwardGapPackets lateOrDuplicate=$lateOrDuplicate " +
                "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
                "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs"
        }
        val longReadGapSummary = if (sequenceSamples == 0) {
            "seqGapAfterReadOver250Ms=not-sampled seqMissingAfterReadOver250Ms=not-sampled"
        } else {
            "seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
                "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms"
        }
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} $sequenceSummary " +
            "interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} seqTrackingSamples=$sequenceSamples ended=$ended " +
            "senderFrameGapMaxMs=${maxSenderFrameGapNs / 1_000_000} senderFrameGapSamples=$senderFrameGapSamples " +
            "senderTimestampNonIncreasing=$senderTimestampNonIncreasing " +
            "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
            "readsOver250Ms=$readsOver250Ms $longReadGapSummary" + timingSummary() + decryptSummary()) }
        windowStart = now
        decryptSamples = 0
        decryptBytes = 0
        decryptSumNs = 0
        maxDecryptNs = 0
        conscryptFrames = 0
        otherProviderFrames = 0
        javaFallbackFrames = 0
        javaFallbackReasons.clear()
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxSenderFrameGapNs = 0
        senderFrameGapSamples = 0
        senderTimestampNonIncreasing = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
        sequenceSamples = 0
        queueWaitSamples = 0
        queueWaitSumNs = 0
        maxQueueWaitNs = 0
        conversionSamples = 0
        conversionSumNs = 0
        maxConversionNs = 0
        dispatchSamples = 0
        dispatchSumNs = 0
        maxDispatchNs = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private fun decryptSummary(): String {
        if (decryptSamples == 0) return ""
        val mbPerSecond = if (decryptSumNs == 0L) 0L else decryptBytes * 1_000L / decryptSumNs
        val reasons = if (javaFallbackReasons.isEmpty()) "none" else
            javaFallbackReasons.entries.joinToString(",") { "${it.key}:${it.value}" }
        return " decryptAvgUs=${decryptSumNs / decryptSamples / 1000} decryptMaxUs=${maxDecryptNs / 1000} " +
            "decryptMBps=$mbPerSecond decryptConscryptFrames=$conscryptFrames " +
            "decryptOtherProviderFrames=$otherProviderFrames decryptJavaFallbackFrames=$javaFallbackFrames " +
            "decryptJavaFallbackReasons=$reasons"
    }

    private fun timingSummary(): String = buildString {
        if (queueWaitSamples > 0) {
            append(" ingressWaitAvgUs=${queueWaitSumNs / queueWaitSamples / 1000}")
            append(" ingressWaitMaxMs=${maxQueueWaitNs / 1_000_000}")
        }
        if (conversionSamples > 0) {
            append(" nalConvertAvgUs=${conversionSumNs / conversionSamples / 1000}")
            append(" nalConvertMaxUs=${maxConversionNs / 1000}")
        }
        if (dispatchSamples > 0) {
            append(" decoderEnqueueAvgUs=${dispatchSumNs / dispatchSamples / 1000}")
            append(" decoderEnqueueMaxUs=${maxDispatchNs / 1000}")
        }
    }

    private companion object { const val LONG_READ_NS = 250_000_000L }

}
