package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScreenIngressQueueTest {
    @Test fun preservesOrderAndDrainsQueuedMessagesWhenFinished() {
        val queue = ScreenIngressQueue(maxMessages = 2, maxBytes = 32)
        val first = message(1)
        val second = message(2)
        assertTrue(queue.offer(first))
        assertTrue(queue.offer(second))

        queue.finish()

        assertSame(first, queue.take())
        assertSame(second, queue.take())
        assertNull(queue.take())
        assertFalse(queue.offer(message(3)))
    }

    @Test fun appliesBackpressureWithoutDroppingEncryptedMessages() {
        val queue = ScreenIngressQueue(maxMessages = 1, maxBytes = 12)
        val first = message(1, bodyBytes = 8)
        val second = message(2, bodyBytes = 8)
        assertTrue(queue.offer(first))

        val executor = Executors.newSingleThreadExecutor()
        try {
            val offered = executor.submit<Boolean> { queue.offer(second) }
            Thread.sleep(30)
            assertFalse("producer should wait while the bounded queue is full", offered.isDone)
            assertSame(first, queue.take())
            assertTrue(offered.get(1, TimeUnit.SECONDS))
            assertSame(second, queue.take())
        } finally {
            queue.abort()
            executor.shutdownNow()
        }
    }

    @Test fun abortWakesWaitersAndDiscardsPendingEncryptedWork() {
        val queue = ScreenIngressQueue(maxMessages = 1, maxBytes = 16)
        val first = message(1)
        assertTrue(queue.offer(first))

        queue.abort()

        assertNull(queue.take())
        assertFalse(queue.offer(message(2)))
    }

    private fun message(value: Byte, bodyBytes: Int = 1) = ScreenIngressMessage(
        header = byteArrayOf(value),
        body = ByteArray(bodyBytes) { value },
        arrivalNanos = value.toLong(),
    )
}
