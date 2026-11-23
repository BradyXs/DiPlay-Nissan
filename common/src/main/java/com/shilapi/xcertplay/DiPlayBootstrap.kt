package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
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
            try {
                // The host also calls ensure() so it receives the actual error if preparation fails.
                runCatching { ensure(appContext, target) }
            } finally {
                prewarmInProgress.set(false)
            }
        }, "diplay-mfi-prewarm").apply { isDaemon = true; start() }
    }

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (validatedIdentity != null) return
        val privateRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        val staging = File(privateRoot, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        val prepared = try {
            for (name in AUTH_FILES) {
                val file = File(staging, name)
                context.assets.open("offline-mfi/$name").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }

            // Validate the APK-bundled identity before it can replace a previous installation.
            val identity = LocalMfiAuthenticationClient.load(staging)
            // Identical files have just passed validation in staging. A different or incomplete
            // installed identity is replaced by that validated copy.
            if (!sameAuthenticationFiles(target, staging)) {
                val previous = File(privateRoot, "offline-mfi-previous")
                previous.deleteRecursively()
                if (target.exists()) {
                    check(target.renameTo(previous)) { "Could not rotate previous local authentication" }
                }
                try {
                    check(staging.renameTo(target)) { "Could not install local authentication" }
                    previous.deleteRecursively()
                } catch (error: Throwable) {
                    if (!target.exists() && previous.exists()) previous.renameTo(target)
                    throw error
                }
            }
            identity
        } finally {
            staging.deleteRecursively()
        }
        // Reuse the validated material for the life of this process. Each session still
        // signs the phone's challenge and keeps independent diagnostic counters.
        validatedIdentity = prepared
    }

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
