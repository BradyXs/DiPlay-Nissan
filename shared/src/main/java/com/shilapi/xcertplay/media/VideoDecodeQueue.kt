package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        /** When the already-decrypted frame was handed to the decoder work queue. */
        val enqueuedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
        /** A recovery anchor selected from the queue; submit it even if its timestamp is old. */
        val recoveryAnchor: Boolean = false,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Allows the first decodable GOP to survive a slow hardware codec start. */
internal class VideoFirstFrameGrace(private val enabled: Boolean) {
    private var startedAtNanos: Long? = null
    private var firstFrameReleased = false

    fun onCodecConfigured(configureStartedAtNanos: Long) {
        if (enabled && startedAtNanos == null) startedAtNanos = configureStartedAtNanos
    }

    fun onFirstFrameReleased() {
        firstFrameReleased = true
    }

    fun frameAgeLimitNanos(nowNanos: Long): Long {
        val startedAt = startedAtNanos ?: return NORMAL_FRAME_AGE_NS
        return if (!firstFrameReleased && nowNanos - startedAt in 0 until GRACE_WINDOW_NS) {
            STARTUP_FRAME_AGE_NS
        } else NORMAL_FRAME_AGE_NS
    }

    companion object {
        const val NORMAL_FRAME_AGE_NS = 250_000_000L
        private const val STARTUP_FRAME_AGE_NS = 500_000_000L
        private const val GRACE_WINDOW_NS = 1_500_000_000L
    }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    data class RecoveryPrune(
        val droppedFrames: Int,
        val retainedFrames: Int,
        val anchorQueued: Boolean,
    )

    private val jobs = LinkedBlockingQueue<VideoJob>()

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            if (frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is VideoJob.Frame || item is VideoJob.Resync) iterator.remove()
        }
    }

    /**
     * On a stale decoder backlog, keep the newest queued IDR/IRAP and the dependent frames after it.
     * A later codec config or overflow marker invalidates any earlier random-access frame.
     */
    @Synchronized fun retainLatestRandomAccess(codec: VideoCodec): RecoveryPrune {
        val snapshot = jobs.toList()
        val frameCount = snapshot.count { it is VideoJob.Frame }
        var lastReferenceReset = -1
        snapshot.forEachIndexed { index, job ->
            if (job is VideoJob.Config || job is VideoJob.Resync) lastReferenceReset = index
        }
        val anchorIndex = snapshot.indices.lastOrNull { index ->
            index > lastReferenceReset &&
                (snapshot[index] as? VideoJob.Frame)?.let { MediaCodecSupport.isRandomAccess(it.nalus, codec) } == true
        }
        if (anchorIndex == null) {
            discardFrames()
            return RecoveryPrune(droppedFrames = frameCount, retainedFrames = 0, anchorQueued = false)
        }

        val retained = ArrayList<VideoJob>(snapshot.size)
        var retainedFrames = 0
        snapshot.forEachIndexed { index, job ->
            when {
                job is VideoJob.Resync -> Unit
                job is VideoJob.Frame && index < anchorIndex -> Unit
                job is VideoJob.Frame -> {
                    retainedFrames++
                    retained += if (index == anchorIndex) job.copy(recoveryAnchor = true) else job
                }
                else -> retained += job
            }
        }
        jobs.clear()
        jobs.addAll(retained)
        return RecoveryPrune(
            droppedFrames = frameCount - retainedFrames,
            retainedFrames = retainedFrames,
            anchorQueued = true,
        )
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Removes and returns every queued job, for a worker that is shutting down. */
    @Synchronized fun drain(): List<VideoJob> = ArrayList<VideoJob>().also { jobs.drainTo(it) }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
