package com.shilapi.xcertplay.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Starts the Nissan car hotspot through the firmware's token-owned bridge, then discovers it. */
internal class NissanFirmwareHotspotManager(
    context: Context,
    private val delegate: ManualHotspotManager,
    onDiagnostic: (String) -> Unit,
    private val isCancelled: () -> Boolean,
) : WirelessHotspotManager, CancellableHotspotStartup {
    private val appContext = context.applicationContext
    private val report = onDiagnostic
    private val lease = NissanFirmwareHotspotLease(appContext, report)
    private val closed = AtomicBoolean()

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (closed.get() || isCancelled()) {
            close()
            throw InterruptedIOException("Hotspot startup cancelled")
        }
        val startedAt = System.nanoTime()
        val result = if (CarHotspotStatus.isEnabled(appContext) == true) {
            NissanFirmwareHotspotLease.Result.ALREADY_ENABLED
        } else {
            lease.enableIfOff()
        }
        if (closed.get() || isCancelled() || result == NissanFirmwareHotspotLease.Result.CANCELLED) {
            close()
            throw InterruptedIOException("Hotspot startup cancelled")
        }

        val carHotspotEnabled = CarHotspotStatus.isEnabled(appContext)
        report("Nissan hotspot auto-start result=${result.name.lowercase()} state=${carHotspotEnabled ?: "unknown"}")
        if ((result == NissanFirmwareHotspotLease.Result.UNAVAILABLE ||
                result == NissanFirmwareHotspotLease.Result.FAILED) && carHotspotEnabled == false) {
            throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_CONTROL_UNAVAILABLE,
                "Automatic car-hotspot control is unavailable. Turn on the hotspot in car settings and reconnect.",
            )
        }

        val elapsedMillis = (System.nanoTime() - startedAt).coerceAtLeast(0L) / 1_000_000L
        val remainingMillis = (timeoutMillis - elapsedMillis).coerceAtLeast(1L)
        return delegate.start(remainingMillis, alreadyEnabled = result == NissanFirmwareHotspotLease.Result.ALREADY_ENABLED)
    }

    override fun validateReady() = delegate.validateReady()

    override fun onCarPlayConfirmed() = delegate.onCarPlayConfirmed()

    override fun connectionDiagnosticSnapshot(): String = delegate.connectionDiagnosticSnapshot()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        delegate.close()
        lease.close()
    }

    override fun cancelStartup() = close()
}

/** Binder client for FirmwareBridgeService's token-scoped hotspot lease (protocol v1, capability 2). */
private class NissanFirmwareHotspotLease(
    private val context: Context,
    private val report: (String) -> Unit,
) : Closeable {
    enum class Result { STARTED_BY_DIPLAY, ALREADY_ENABLED, UNAVAILABLE, FAILED, CANCELLED }

    private val token = Binder()
    private val closed = AtomicBoolean()
    private val released = AtomicBoolean()
    private val bindingLock = Any()
    @Volatile private var binder: IBinder? = null
    @Volatile private var leaseBinder: IBinder? = null
    @Volatile private var readyLatch: CountDownLatch? = null
    private var connection: ServiceConnection? = null
    private var bound = false
    @Volatile private var acquired = false

    fun enableIfOff(): Result {
        if (closed.get()) return Result.CANCELLED
        val component = ComponentName(PACKAGE, SERVICE)
        if (!runCatching { context.packageManager.getServiceInfo(component, 0) }.isSuccess) {
            report("Nissan hotspot bridge unavailable")
            return Result.UNAVAILABLE
        }

        val ready = CountDownLatch(1)
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                synchronized(bindingLock) {
                    if (connection === this) binder = service
                }
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(bindingLock) {
                    if (connection === this) binder = null
                }
                ready.countDown()
                report("Nissan hotspot bridge disconnected")
            }
        }
        synchronized(bindingLock) {
            if (closed.get()) return Result.CANCELLED
            connection = next
            readyLatch = ready
            bound = try {
                context.bindService(Intent().setComponent(component), next, Context.BIND_AUTO_CREATE)
            } catch (error: RuntimeException) {
                report("Nissan hotspot bridge bind failed error=${error.javaClass.simpleName}")
                false
            }
            if (!bound) {
                connection = null
                readyLatch = null
            }
        }
        if (!bound) return Result.UNAVAILABLE

        return try {
            if (!ready.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                report("Nissan hotspot bridge bind timeout")
                return if (closed.get()) Result.CANCELLED else Result.UNAVAILABLE
            }
            if (closed.get()) return Result.CANCELLED
            val service = binder ?: run {
                report("Nissan hotspot bridge returned no binder")
                return Result.UNAVAILABLE
            }
            val info = transact(service, TX_INFO) { reply -> reply.readInt() to reply.readInt() }
            if (info.first != VERSION || info.second and CAP_HOTSPOT == 0) {
                report("Nissan hotspot bridge capability unavailable version=${info.first} capabilities=${info.second}")
                return Result.UNAVAILABLE
            }

            when (val state = transact(service, TX_HOTSPOT_STATE) { it.readInt() }) {
                HOTSPOT_ON -> {
                    report("Nissan car hotspot already enabled; ownership unchanged")
                    Result.ALREADY_ENABLED
                }
                HOTSPOT_OFF -> {
                    if (closed.get()) return Result.CANCELLED
                    when (val enabled = transact(service, TX_HOTSPOT_SET, {
                        it.writeStrongBinder(token)
                        it.writeInt(1)
                    }) { it.readInt() }) {
                        REQUEST_ACCEPTED -> {
                            leaseBinder = service
                            acquired = true
                            report("Nissan car hotspot enable accepted")
                            if (closed.get()) {
                                releaseIfAcquired()
                                Result.CANCELLED
                            } else Result.STARTED_BY_DIPLAY
                        }
                        HOTSPOT_ALREADY_ENABLED -> {
                            report("Nissan car hotspot became enabled; ownership unchanged")
                            Result.ALREADY_ENABLED
                        }
                        else -> {
                            report("Nissan car hotspot enable failed result=$enabled")
                            Result.FAILED
                        }
                    }
                }
                else -> {
                    report("Nissan car hotspot state unavailable result=$state")
                    Result.FAILED
                }
            }
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            report("Nissan hotspot bridge request failed error=${error.javaClass.simpleName}")
            if (closed.get()) Result.CANCELLED else Result.FAILED
        } finally {
            if (!acquired) unbind(next)
            if (closed.get()) releaseIfAcquired()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        readyLatch?.countDown()
        releaseIfAcquired()
        unbind()
    }

    private fun releaseIfAcquired() {
        if (!acquired || !released.compareAndSet(false, true)) return
        val current = leaseBinder ?: binder
        if (current == null) {
            report("Nissan hotspot lease released by binder death")
        } else {
            val result = runCatching {
                transact(current, TX_HOTSPOT_SET, {
                    it.writeStrongBinder(token)
                    it.writeInt(0)
                }) { it.readInt() }
            }.getOrElse {
                report("Nissan hotspot lease release failed error=${it.javaClass.simpleName}")
                -1
            }
            report("Nissan hotspot lease released result=$result")
        }
        acquired = false
        leaseBinder = null
    }

    private fun unbind(expected: ServiceConnection? = null) {
        val old = synchronized(bindingLock) {
            if (expected != null && connection !== expected) return
            val previous = connection
            val shouldUnbind = bound
            connection = null
            readyLatch = null
            bound = false
            binder = null
            if (shouldUnbind) previous else null
        }
        if (old != null) runCatching { context.unbindService(old) }
    }

    private fun <T> transact(
        service: IBinder,
        code: Int,
        write: (Parcel) -> Unit = {},
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            check(service.transact(code, data, reply, 0)) { "unsupported firmware bridge transaction $code" }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val PACKAGE = "com.bradyxu.headunitmanager"
        const val SERVICE = "com.bradyxu.headunitmanager.FirmwareBridgeService"
        const val DESCRIPTOR = "com.bradyxu.headunitmanager.IFirmwareBridge.v1"
        const val VERSION = 1
        const val CAP_HOTSPOT = 2
        const val TX_INFO = 1
        const val TX_HOTSPOT_STATE = 4
        const val TX_HOTSPOT_SET = 5
        const val BIND_TIMEOUT_MS = 1_500L
        const val HOTSPOT_OFF = 0
        const val HOTSPOT_ON = 1
        const val REQUEST_ACCEPTED = 1
        const val HOTSPOT_ALREADY_ENABLED = 2
    }
}
