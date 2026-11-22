package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.host.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Sends CarPlay metadata to the optional P350 AutoCore MCU text bridge. */
internal object NissanClusterMetadataPublisher {
    const val MODE_MEDIA = 1
    const val MODE_NAVIGATION = 2

    private const val TAG = "P350ClusterBridge"
    private const val SERVICE = "com.hsae.auto.IBINDER_SERVER"
    private const val DESCRIPTOR = "com.hsae.autosdk.server.IServer"
    private const val TRANSACTION_CLUSTER_METADATA = 3
    private const val API_VERSION = 1
    private const val OP_QUERY = 0
    private const val OP_PUBLISH = 1
    private const val OP_CLEAR = 2

    private data class Metadata(val mode: Int, val title: String, val album: String, val artist: String)

    private val token = Binder()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "nissan-cluster-metadata").apply { isDaemon = true }
    }
    @Volatile private var logger: ((String) -> Unit)? = null
    private var sessionActive = false
    private var supported: Boolean? = null
    private var desiredMetadata: Metadata? = null
    private var lastLoggedMetadata: Metadata? = null
    private var lastLoggedStatus: Int? = null
    private var generation = 0L
    private var reportedUnavailable = false

    fun start(onDiagnostic: (String) -> Unit) {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return
        val currentGeneration = synchronized(this) {
            logger = onDiagnostic
            sessionActive = true
            generation++
            desiredMetadata = null
            lastLoggedMetadata = null
            lastLoggedStatus = null
            supported = null
            reportedUnavailable = false
            generation
        }
        worker.execute {
            if (!isCurrentSession(currentGeneration)) return@execute
            val ready = ensureSupported()
            if (ready && isCurrentSession(currentGeneration)) log("metadata bridge api=$API_VERSION state=ready")
        }
    }

    fun publish(mode: Int, title: String?, album: String?, artist: String?) {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE || mode !in MODE_MEDIA..MODE_NAVIGATION) return
        val metadata = Metadata(mode, title.orEmpty(), album.orEmpty(), artist.orEmpty())
        val currentGeneration = synchronized(this) {
            if (!sessionActive || desiredMetadata == metadata) return
            desiredMetadata = metadata
            generation
        }
        worker.execute { publishDesired(currentGeneration, metadata) }
    }

    private fun publishDesired(expectedGeneration: Long, metadata: Metadata) {
        if (!isCurrentMetadata(expectedGeneration, metadata)) return
        val ready = ensureSupported()
        val result = if (ready) transact(OP_PUBLISH, metadata) else RESULT_TRANSPORT_ERROR
        if (!isCurrentMetadata(expectedGeneration, metadata)) return
        val shouldLog = synchronized(this) {
            val changed = lastLoggedStatus != result ||
                (result == RESULT_OK && lastLoggedMetadata != metadata)
            lastLoggedStatus = result
            if (result == RESULT_OK) lastLoggedMetadata = metadata
            changed
        }
        if (shouldLog) {
            if (result == RESULT_OK) {
                log("metadata bridge publish mode=${metadata.mode} titleBytes=${metadata.title.utf8Length()} " +
                    "albumBytes=${metadata.album.utf8Length()} artistBytes=${metadata.artist.utf8Length()} result=ok")
            } else {
                log("metadata bridge publish mode=${metadata.mode} result=$result")
            }
        }
        // The source can become USB after the first metadata update. A light heartbeat lets
        // AutoCore clear/reapply its own text on source transitions; unchanged text is deduped
        // by the firmware bridge, so it does not repeatedly write to the MCU.
        val retry = synchronized(this) { supported != false } &&
            (result == RESULT_OK || result == RESULT_TRANSPORT_ERROR || result == -17 || result == -19)
        if (retry) worker.schedule({ publishDesired(expectedGeneration, metadata) }, 10, TimeUnit.SECONDS)
    }

    private fun isCurrentSession(expectedGeneration: Long): Boolean = synchronized(this) {
        sessionActive && generation == expectedGeneration
    }

    private fun isCurrentMetadata(expectedGeneration: Long, metadata: Metadata): Boolean = synchronized(this) {
        sessionActive && generation == expectedGeneration && desiredMetadata == metadata
    }

    fun clear() {
        if (!BuildConfig.NISSAN_ANDROID_44_PROFILE) return
        val shouldClear = synchronized(this) {
            if (!sessionActive) false else {
                sessionActive = false
                generation++
                desiredMetadata = null
                lastLoggedMetadata = null
                lastLoggedStatus = null
                true
            }
        }
        if (!shouldClear) return
        worker.execute {
            if (!ensureSupported()) return@execute
            val result = transact(OP_CLEAR, null)
            log("metadata bridge clear result=$result")
        }
    }

    private fun ensureSupported(): Boolean {
        synchronized(this) { supported?.let { return it } }
        val reply = transactRaw(OP_QUERY, null) ?: return false
        val apiVersion = reply.second
        val ready = reply.first == RESULT_OK && apiVersion == API_VERSION
        synchronized(this) { supported = ready }
        if (!ready && !reportedUnavailable) {
            reportedUnavailable = true
            log("metadata bridge unavailable status=${reply.first} api=$apiVersion")
        }
        return ready
    }

    private fun transact(operation: Int, metadata: Metadata?): Int =
        transactRaw(operation, metadata)?.first ?: RESULT_TRANSPORT_ERROR

    private fun transactRaw(operation: Int, metadata: Metadata?): Pair<Int, Int>? {
        val binder = serviceBinder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(API_VERSION)
            data.writeInt(operation)
            data.writeStrongBinder(token)
            if (operation == OP_PUBLISH) {
                val value = metadata ?: return null
                data.writeInt(value.mode)
                data.writeString(value.title)
                data.writeString(value.album)
                data.writeString(value.artist)
            }
            if (!binder.transact(TRANSACTION_CLUSTER_METADATA, data, reply, 0)) {
                synchronized(this) { supported = false }
                if (!reportedUnavailable) {
                    reportedUnavailable = true
                    log("metadata bridge unavailable reason=transaction-unsupported")
                }
                return RESULT_UNSUPPORTED to 0
            }
            reply.readException()
            val status = reply.readInt()
            val api = reply.readInt()
            return status to api
        } catch (failure: Exception) {
            synchronized(this) { supported = null }
            log("metadata bridge transport failure class=${failure.javaClass.simpleName}")
            return null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun serviceBinder(): IBinder? = runCatching {
        val manager = Class.forName("android.os.ServiceManager")
        manager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    }.getOrNull()

    private fun log(message: String) {
        logger?.invoke("$TAG: $message")
    }

    private fun String.utf8Length(): Int = toByteArray(Charsets.UTF_8).size

    private const val RESULT_OK = 0
    private const val RESULT_UNSUPPORTED = -1
    private const val RESULT_TRANSPORT_ERROR = -2
}
