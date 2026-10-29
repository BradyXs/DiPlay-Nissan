package com.shilapi.xcertplay.airplay

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A complete on-wire screen message, kept in stream order until its AEAD counter is consumed. */
internal data class ScreenIngressMessage(
    val header: ByteArray,
    val body: ByteArray,
    val arrivalNanos: Long,
    /** Number of wire bytes in [body]; [body] may be a larger pooled array. */
    val bodySize: Int = body.size,
) {
    init {
        require(bodySize in 0..body.size)
    }

    val byteCount: Long get() = header.size.toLong() + bodySize
}

/** Small per-connection pool for wire buffers; large keyframes are not retained. */
internal class ScreenIngressBufferPool(
    private val maxRetainedBufferBytes: Int = 1024 * 1024,
    private val maxRetainedBytes: Int = 4 * 1024 * 1024,
    private val maxRetainedBuffers: Int = 8,
) {
    data class WindowMetrics(
        val reusedBuffers: Int,
        val allocatedBuffers: Int,
        val retainedBuffers: Int,
        val retainedBytes: Int,
    )

    private val buffers = ArrayList<ByteArray>()
    private var retainedBytes = 0
    private var reusedWindow = 0
    private var allocatedWindow = 0

    init {
        require(maxRetainedBufferBytes > 0)
        require(maxRetainedBytes > 0)
        require(maxRetainedBuffers > 0)
    }

    @Synchronized
    fun acquire(minimumSize: Int): ByteArray = takeBuffer(minimumSize, exact = false)

    @Synchronized
    fun acquireExact(size: Int): ByteArray = takeBuffer(size, exact = true)

    private fun takeBuffer(size: Int, exact: Boolean): ByteArray {
        require(size >= 0)
        var candidate = -1
        for (index in buffers.indices) {
            val buffer = buffers[index]
            val fits = if (exact) buffer.size == size else buffer.size >= size
            if (fits && (candidate < 0 || buffer.size < buffers[candidate].size)) {
                candidate = index
            }
        }
        if (candidate < 0) {
            allocatedWindow++
            return ByteArray(size)
        }
        reusedWindow++
        return buffers.removeAt(candidate).also { retainedBytes -= it.size }
    }

    @Synchronized
    fun release(buffer: ByteArray) {
        if (buffer.size > maxRetainedBufferBytes || buffers.size >= maxRetainedBuffers ||
            retainedBytes + buffer.size > maxRetainedBytes
        ) return
        buffers.add(buffer)
        retainedBytes += buffer.size
    }

    @Synchronized
    fun takeWindowMetrics(): WindowMetrics = WindowMetrics(
        reusedBuffers = reusedWindow,
        allocatedBuffers = allocatedWindow,
        retainedBuffers = buffers.size,
        retainedBytes = retainedBytes,
    ).also {
        reusedWindow = 0
        allocatedWindow = 0
    }
}

/**
 * Bounded handoff between the blocking TCP reader and the serial decrypt/dispatch worker.
 *
 * It never drops encrypted messages: the ChaCha frame counter is implicit in the byte stream, so
 * dropping one before authentication would desynchronize every following frame. A full queue
 * applies TCP backpressure until the worker catches up.
 */
internal class ScreenIngressQueue(
    private val maxMessages: Int = 4,
    private val maxBytes: Long = 4L * 1024 * 1024,
) {
    data class WindowMetrics(
        val maxDepth: Int,
        val maxBytes: Long,
        val maxProducerWaitNs: Long,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val messages = ArrayDeque<ScreenIngressMessage>()
    private var queuedBytes = 0L
    private var closed = false
    private var maxDepthWindow = 0
    private var maxBytesWindow = 0L
    private var maxProducerWaitNsWindow = 0L

    init {
        require(maxMessages > 0)
        require(maxBytes > 0)
    }

    @Throws(InterruptedException::class)
    fun offer(message: ScreenIngressMessage): Boolean = lock.withLock {
        val size = message.byteCount
        val waitStarted = System.nanoTime()
        while (!closed && messages.isNotEmpty() &&
            (messages.size >= maxMessages || queuedBytes + size > maxBytes)
        ) {
            changed.await()
        }
        if (closed) return@withLock false

        val waited = System.nanoTime() - waitStarted
        maxProducerWaitNsWindow = maxOf(maxProducerWaitNsWindow, waited)
        messages.addLast(message)
        queuedBytes += size
        maxDepthWindow = maxOf(maxDepthWindow, messages.size)
        maxBytesWindow = maxOf(maxBytesWindow, queuedBytes)
        changed.signalAll()
        true
    }

    @Throws(InterruptedException::class)
    fun take(): ScreenIngressMessage? = lock.withLock {
        while (messages.isEmpty() && !closed) changed.await()
        if (messages.isEmpty()) return@withLock null

        messages.removeFirst().also {
            queuedBytes -= it.byteCount
            changed.signalAll()
        }
    }

    /** Stop accepting messages and let the worker drain those already queued. */
    fun finish() = lock.withLock {
        closed = true
        changed.signalAll()
    }

    /** Stop accepting and discard queued work when the stream is closing or has failed. */
    fun abort() = lock.withLock {
        closed = true
        messages.clear()
        queuedBytes = 0
        changed.signalAll()
    }

    fun takeWindowMetrics(): WindowMetrics = lock.withLock {
        WindowMetrics(maxDepthWindow, maxBytesWindow, maxProducerWaitNsWindow).also {
            maxDepthWindow = messages.size
            maxBytesWindow = queuedBytes
            maxProducerWaitNsWindow = 0
        }
    }
}
