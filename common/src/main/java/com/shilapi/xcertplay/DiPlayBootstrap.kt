package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.os.Process
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var validatedIdentity: LocalMfiAuthenticationClient? = null

    private val prewarmInProgress = AtomicBoolean(false)

    fun localIdentity(): LocalMfiAuthenticationClient? = validatedIdentity

    fun prewarmLocalIdentity(context: Context, target: MfiTarget) {
        if (target != MfiTarget.LOCAL || validatedIdentity != null || !prewarmInProgress.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({
            val startedAtNs = System.nanoTime()
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                logStage("prewarm-start", thread = Thread.currentThread().name)
                // The host also calls ensure() so it receives the actual error if preparation fails.
                val failure = runCatching { ensure(appContext, target) }.exceptionOrNull()
                logStage(
                    "prewarm-finish",
                    elapsedMs = elapsedMillis(startedAtNs),
                    result = if (failure == null) "ready" else failure.javaClass.simpleName,
                    thread = Thread.currentThread().name,
                )
            } finally {
                prewarmInProgress.set(false)
            }
        }, "diplay-mfi-prewarm").apply { isDaemon = true; start() }
    }

    fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        UiPageDiagnostics.initialize(context.applicationContext)
        val lockStartedAtNs = System.nanoTime()
        synchronized(this) {
            val lockWaitMs = elapsedMillis(lockStartedAtNs)
            if (validatedIdentity != null) {
                logStage("identity-process-cache", lockWaitMs = lockWaitMs)
                return
            }
            prepareLocalIdentity(context.applicationContext, lockWaitMs)
        }
    }

    private fun prepareLocalIdentity(context: Context, lockWaitMs: Long) {
        val bootstrapStartedAtNs = System.nanoTime()
        val privateRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        val staging = File(privateRoot, "offline-mfi-staging")
        timeStage("staging-clean") { staging.deleteRecursively() }
        check(timeStage("staging-create") { staging.mkdirs() }) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        val prepared = try {
            for (name in AUTH_FILES) {
                val file = File(staging, name)
                timeStage("copy-${name.substringAfterLast('.')}") {
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }

            // Validate the APK-bundled identity before it can replace a previous installation.
            val identity = LocalMfiAuthenticationClient.load(
                directory = staging,
                onSignature = {},
                onTiming = { stage, elapsedMs -> logStage("identity-$stage", elapsedMs = elapsedMs) },
            )
            // Identical files have just passed validation in staging. A different or incomplete
            // installed identity is replaced by that validated copy.
            val sameFiles = timeStage("compare-installed-files") { sameAuthenticationFiles(target, staging) }
            if (!sameFiles) {
                val previous = File(privateRoot, "offline-mfi-previous")
                timeStage("remove-previous-staging") { previous.deleteRecursively() }
                if (target.exists()) {
                    check(timeStage("rotate-installed-identity") { target.renameTo(previous) }) {
                        "Could not rotate previous local authentication"
                    }
                }
                try {
                    check(timeStage("install-identity") { staging.renameTo(target) }) {
                        "Could not install local authentication"
                    }
                    timeStage("remove-previous-identity") { previous.deleteRecursively() }
                } catch (error: Throwable) {
                    if (!target.exists() && previous.exists()) previous.renameTo(target)
                    throw error
                }
            } else {
                logStage("installed-files-unchanged")
            }
            identity
        } finally {
            timeStage("staging-clean-finish") { staging.deleteRecursively() }
        }
        // Reuse the validated material for the life of this process. Each session still
        // signs the phone's challenge and keeps independent diagnostic counters.
        validatedIdentity = prepared
        logStage(
            "bootstrap-ready",
            elapsedMs = elapsedMillis(bootstrapStartedAtNs),
            lockWaitMs = lockWaitMs,
        )
    }

    private inline fun <T> timeStage(name: String, block: () -> T): T {
        val startedAtNs = System.nanoTime()
        return try {
            block()
        } finally {
            logStage(name, elapsedMs = elapsedMillis(startedAtNs))
        }
    }

    private fun logStage(
        stage: String,
        elapsedMs: Long? = null,
        lockWaitMs: Long? = null,
        result: String? = null,
        thread: String = Thread.currentThread().name,
    ) {
        val fields = buildString {
            append("UI_MFI stage=$stage")
            elapsedMs?.let { append(" elapsedMs=$it") }
            lockWaitMs?.let { append(" lockWaitMs=$it") }
            result?.let { append(" result=$it") }
            append(" thread=$thread")
        }
        runCatching { UiPageDiagnostics.log(fields) }
    }

    private fun elapsedMillis(startedAtNs: Long): Long =
        (System.nanoTime() - startedAtNs).coerceAtLeast(0L) / 1_000_000L

    private fun sameAuthenticationFiles(first: File, second: File): Boolean =
        AUTH_FILES.all { name ->
            val left = File(first, name)
            val right = File(second, name)
            left.isFile && right.isFile &&
                MessageDigest.isEqual(sha256(left), sha256(right))
        }

    private fun sha256(file: File): ByteArray =
        file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    private val AUTH_FILES = listOf("identity.pk8", "certificate.p7b")
}
