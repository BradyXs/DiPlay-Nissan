package com.shilapi.xcertplay.bluetooth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Read-only access to pairing metadata exposed by Nissan's existing AnwPhoneLink service. */
internal object AnwPhoneLinkSnapshot {
    private const val SERVICE_ACTION = "com.anwsdk.service.AnwPhoneLink"
    private const val SERVICE_PACKAGE = "com.anwsdk.service"
    private const val SERVICE_CLASS = "com.anwsdk.service.AnwSdkService"
    private const val DESCRIPTOR = "com.anwsdk.service.IAnwPhoneLink"

    private const val TX_READ_LOCAL_ADDRESS = 7
    // Verified against the Nissan firmware's AnwSdkService.apk IAnwPhoneLink Binder stub.
    private const val TX_BT_POWER_ON = 1
    private const val TX_BT_POWER_OFF = 2
    private const val TX_GET_BT_POWER_STATUS = 3
    private const val TX_GET_PAIRED_LIST = 16
    private const val TX_GET_CONNECT_STATUS = 23
    private const val TX_GET_CONNECTED_DEVICE_INFO = 24
    private const val MAX_PAIRED_DEVICES = 16

    data class Device(val name: String, val address: String, val classOfDevice: Int)
    data class Snapshot(
        val localAddress: String,
        val powerStatus: Int,
        val pairedDevices: List<Device>,
        val connectedDevice: Device?,
    )

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
    }.isSuccess

    fun read(context: Context, timeoutMillis: Long = 5_000L): Snapshot {
        return withBinder(context, timeoutMillis) { binder ->
            Snapshot(
                localAddress = call(binder, TX_READ_LOCAL_ADDRESS) { readString().orEmpty() },
                powerStatus = call(binder, TX_GET_BT_POWER_STATUS) { readInt() },
                pairedDevices = readPairedDevices(binder),
                connectedDevice = runCatching { readConnectedDevice(binder) }.getOrNull(),
            )
        }
    }

    fun connectedDevice(context: Context, timeoutMillis: Long = 5_000L): Device? =
        withBinder(context, timeoutMillis, ::readConnectedDevice)

    fun localAddress(context: Context, timeoutMillis: Long = 5_000L): String =
        withBinder(context, timeoutMillis) { binder ->
            call(binder, TX_READ_LOCAL_ADDRESS) { readString().orEmpty() }
        }

    /** Firmware returns 1 for a successful power request; the Binder call can block. */
    fun setPower(context: Context, enabled: Boolean, timeoutMillis: Long = 5_000L): Boolean =
        withBinder(context, timeoutMillis) { binder ->
            call(binder, if (enabled) TX_BT_POWER_ON else TX_BT_POWER_OFF) { readInt() } == 1
        }

    fun powerStatus(context: Context, timeoutMillis: Long = 5_000L): Int =
        withBinder(context, timeoutMillis) { binder ->
            call(binder, TX_GET_BT_POWER_STATUS) { readInt() }
        }

    private inline fun <T> withBinder(context: Context, timeoutMillis: Long, block: (IBinder) -> T): T {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        val appContext = context.applicationContext
        val ready = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>()
        val failureRef = AtomicReference<Throwable?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                binderRef.set(binder)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                failureRef.compareAndSet(null, IOException("AnwPhoneLink service disconnected"))
            }
        }
        val intent = Intent(SERVICE_ACTION).setClassName(SERVICE_PACKAGE, SERVICE_CLASS)
        val bound = try {
            // The factory service is already started by the head unit; avoid changing its lifecycle.
            appContext.bindService(intent, connection, 0)
        } catch (error: Throwable) {
            throw IOException("Could not bind to Nissan AnwPhoneLink", error)
        }
        if (!bound) throw IOException("Nissan AnwPhoneLink service rejected the bind request")

        try {
            val connected = try {
                ready.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Nissan AnwPhoneLink", error)
            }
            if (!connected) throw IOException("Timed out waiting for Nissan AnwPhoneLink")
            failureRef.get()?.let { throw IOException("Nissan AnwPhoneLink is unavailable", it) }
            val binder = binderRef.get() ?: throw IOException("Nissan AnwPhoneLink returned no Binder")
            val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
            if (descriptor != DESCRIPTOR) {
                throw IOException("Unexpected AnwPhoneLink Binder descriptor: ${descriptor ?: "<null>"}")
            }
            return block(binder)
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    fun connectStatus(binder: IBinder, profile: Int, index: Int): Int = call(
        binder,
        TX_GET_CONNECT_STATUS,
        write = {
            writeInt(profile)
            writeInt(index)
        },
    ) { readInt() }

    /** Same profile 0 / index 0 query used by the factory BlueModeImpl. */
    private fun readConnectedDevice(binder: IBinder): Device? {
        if (connectStatus(binder, profile = 0, index = 0) != 1) return null
        val addresses = arrayOfNulls<String>(1)
        val names = arrayOfNulls<String>(1)
        val result = call(
            binder,
            TX_GET_CONNECTED_DEVICE_INFO,
            write = {
                writeInt(0)
                writeInt(addresses.size)
                writeInt(names.size)
                writeInt(0)
            },
        ) {
            val status = readInt()
            readStringArray(addresses)
            readStringArray(names)
            status
        }
        val address = BluetoothAddressResolver.normalize(addresses[0]) ?: return null
        if (result < 0) return null
        return Device(names[0].orEmpty(), address, 0)
    }

    private fun readPairedDevices(binder: IBinder): List<Device> {
        val count = intArrayOf(0)
        val names = arrayOfNulls<String>(MAX_PAIRED_DEVICES)
        val addresses = arrayOfNulls<String>(MAX_PAIRED_DEVICES)
        val classes = IntArray(MAX_PAIRED_DEVICES)
        val result = call(
            binder,
            TX_GET_PAIRED_LIST,
            write = {
                writeInt(count.size)
                writeInt(names.size)
                writeInt(addresses.size)
                writeInt(classes.size)
            },
        ) {
            val code = readInt()
            readIntArray(count)
            readStringArray(names)
            readStringArray(addresses)
            readIntArray(classes)
            code
        }
        if (result < 0) throw RemoteException("ANWBT_GetPairedList returned $result")
        return (0 until count[0].coerceIn(0, MAX_PAIRED_DEVICES)).map { index ->
            Device(names[index].orEmpty(), addresses[index].orEmpty(), classes[index])
        }
    }

    private inline fun <T> call(
        binder: IBinder,
        transaction: Int,
        crossinline write: Parcel.() -> Unit = {},
        crossinline read: Parcel.() -> T,
    ): T {
        if (!binder.isBinderAlive) throw RemoteException("AnwPhoneLink Binder is no longer alive")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) {
                throw RemoteException("AnwPhoneLink transaction $transaction is unsupported")
            }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
