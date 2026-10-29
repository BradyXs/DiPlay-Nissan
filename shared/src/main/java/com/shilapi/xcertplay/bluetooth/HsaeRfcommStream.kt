package com.shilapi.xcertplay.bluetooth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.anwsdk.service.IAnwPhoneLink
import com.anwsdk.service.IAnwSPPDataCallBack
import com.anwsdk.service.IAnwSocketDataCallBack
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HsaeRfcommStream private constructor(
    private val context: Context,
    private val service: IAnwPhoneLink,
    private val connectionIndex: Int,
    private val isSocketMode: Boolean,
    private val initializedByStream: Boolean,
    private val connection: ServiceConnection,
    private val socketCallback: IAnwSocketDataCallBack.Stub?,
    private val sppCallback: IAnwSPPDataCallBack.Stub?
) : BlockingDuplexByteStream {

    private val lock = Object()
    private val sendLock = Object()
    private val pending: ArrayDeque<ByteArray> = ArrayDeque()
    private var pendingBytes: Int = 0
    private var closed: Boolean = false
    private var peerEnded: Boolean = false
    private var failure: IOException? = null

    fun onDataReceived(data: ByteArray, length: Int) {
        val payload = if (data.size == length) data else data.copyOf(length)
        synchronized(lock) {
            if (!closed && !peerEnded) {
                pending.addLast(payload)
                pendingBytes += payload.size
                lock.notifyAll()
            }
        }
    }

    fun onDisconnected() {
        synchronized(lock) {
            peerEnded = true
            lock.notifyAll()
        }
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) {
                    throw IOException("HSAE RFCOMM stream is closed")
                }
            }
            val written = IntArray(1)
            var offset = 0
            while (offset < data.size) {
                val chunkSize = Math.min(data.size - offset, 4096)
                val chunk = if (offset == 0 && chunkSize == data.size) {
                    data
                } else {
                    data.copyOfRange(offset, offset + chunkSize)
                }
                val ret = try {
                    if (isSocketMode) {
                        service.ANWBT_SocketWrite(connectionIndex, chunk, chunk.size, written)
                    } else {
                        service.ANWBT_SPPWrite(connectionIndex, chunk, chunk.size, written)
                    }
                } catch (e: RemoteException) {
                    val io = IOException("RemoteException during HSAE RFCOMM send", e)
                    synchronized(lock) {
                        failure = io
                    }
                    throw io
                }

                if (ret < 0) {
                    val io = IOException("HSAE RFCOMM send returned error: ret=$ret")
                    synchronized(lock) {
                        failure = io
                    }
                    throw io
                }

                val nWritten = if (written[0] > 0) written[0] else chunkSize
                offset += nWritten
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(lock) {
            while (true) {
                val chunk = takePendingLocked(maxBytes)
                if (chunk != null) {
                    return chunk
                }
                failure?.let { throw it }
                if (peerEnded || closed) {
                    return EMPTY
                }
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    return null
                }
                try {
                    lock.wait(remainingNanos / 1_000_000L, (remainingNanos % 1_000_000L).toInt())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val first = pending.peekFirst() ?: return null
        return if (first.size <= maxBytes) {
            pending.removeFirst()
            pendingBytes -= first.size
            first
        } else {
            val chunk = first.copyOf(maxBytes)
            pending.removeFirst()
            pending.addFirst(first.copyOfRange(maxBytes, first.size))
            pendingBytes -= maxBytes
            chunk
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            lock.notifyAll()
        }
        try {
            if (isSocketMode) {
                if (socketCallback != null) {
                    runCatching {
                        service.ANWBT_UnRegistrySocketDataCallback(socketCallback)
                    }
                }
                service.ANWBT_SocketDisconnect(connectionIndex)
                if (initializedByStream) service.ANWBT_SocketDenit()
            } else {
                if (sppCallback != null) {
                    runCatching {
                        service.ANWBT_UnRegistrySPPDataCallback(sppCallback)
                    }
                }
                service.ANWBT_SPPDisconnect(connectionIndex)
                if (initializedByStream) service.ANWBT_SPPDenit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting HSAE RFCOMM index=$connectionIndex", e)
        }
        runCatching {
            context.unbindService(connection)
        }
    }

    companion object {
        private const val TAG = "HsaeRfcommStream"
        private const val SERVICE_ACTION = "com.anwsdk.service.AnwPhoneLink"
        private const val SERVICE_PACKAGE = "com.anwsdk.service"
        private const val SERVICE_CLASS = "com.anwsdk.service.AnwSdkService"
        private val EMPTY = ByteArray(0)

        fun connect(context: Context, address: String, uuidString: String): BlockingDuplexByteStream {
            Log.i(TAG, "Connecting HSAE RFCOMM to $address uuid=$uuidString")
            val latch = CountDownLatch(1)
            var phoneLinkService: IAnwPhoneLink? = null
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    phoneLinkService = IAnwPhoneLink.Stub.asInterface(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    phoneLinkService = null
                }
            }

            val intent = Intent(SERVICE_ACTION).apply {
                setClassName(SERVICE_PACKAGE, SERVICE_CLASS)
            }
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                throw IOException("Could not bind to AnwSdkService ($SERVICE_ACTION)")
            }

            if (!latch.await(5000L, TimeUnit.MILLISECONDS)) {
                context.unbindService(serviceConnection)
                throw IOException("Timed out waiting for AnwPhoneLink binding")
            }

            val service = phoneLinkService ?: run {
                context.unbindService(serviceConnection)
                throw IOException("AnwPhoneLink interface is null after connect")
            }

            val rfcBytes = uuidToBytes(uuidString)
            val guidBytes = uuidToGuidBytes(uuidString)

            val socketData = CallbackDataQueue("socket")
            val socketCallback = object : IAnwSocketDataCallBack.Stub() {
                override fun SocketDataIND(nIndex: Int, data: ByteArray?, dataLength: Int) {
                    if (data != null && dataLength > 0) {
                        socketData.onDataReceived(nIndex, data, dataLength)
                    }
                }
            }

            // Try Socket mode first
            var socketIndex = -1
            var socketInitializedByStream = false
            try {
                service.ANWBT_RegistrySocketDataCallback(socketCallback)
                if (!service.ANWBT_GetSocketInitStatus()) {
                    socketInitializedByStream = true
                    val initResult = service.ANWBT_SocketInit()
                    if (initResult < 0) throw IOException("Socket init failed with $initResult")
                }
                val index = intArrayOf(-1)
                var ret = service.ANWBT_SocketConnect(1, address, guidBytes, 0, 0L, 0L, index)
                Log.i(TAG, "ANWBT_SocketConnect(GUID) returned $ret index=${index[0]}")
                if (ret < 0 || index[0] < 0) {
                    ret = service.ANWBT_SocketConnect(1, address, rfcBytes, 0, 0L, 0L, index)
                    Log.i(TAG, "ANWBT_SocketConnect(RFC) returned $ret index=${index[0]}")
                }
                socketIndex = index[0]
                if (ret >= 0 && index[0] >= 0) {
                    awaitConnected(service.asBinder(), PROFILE_SOCKET, index[0])
                    val stream = HsaeRfcommStream(
                        context = context,
                        service = service,
                        connectionIndex = index[0],
                        isSocketMode = true,
                        initializedByStream = socketInitializedByStream,
                        connection = serviceConnection,
                        socketCallback = socketCallback,
                        sppCallback = null
                    )
                    socketData.attach(stream, index[0])
                    Log.i(TAG, "HSAE Socket connect successful index=${index[0]}")
                    return stream
                }
                throw IOException("Socket RFCOMM connect returned ret=$ret index=${index[0]}")
            } catch (e: Exception) {
                Log.w(TAG, "SocketConnect failed, falling back to SPP: ${e.message}")
                socketData.close()
                runCatching { service.ANWBT_UnRegistrySocketDataCallback(socketCallback) }
                if (socketIndex >= 0) runCatching { service.ANWBT_SocketDisconnect(socketIndex) }
                if (socketInitializedByStream) runCatching { service.ANWBT_SocketDenit() }
            }

            // Fallback to SPP mode
            val sppData = CallbackDataQueue("SPP")
            val sppCallback = object : IAnwSPPDataCallBack.Stub() {
                override fun SPPDataIND(nIndex: Int, data: ByteArray?, dataLength: Int) {
                    if (data != null && dataLength > 0) {
                        sppData.onDataReceived(nIndex, data, dataLength)
                    }
                }
            }

            var sppIndex = -1
            var sppInitializedByStream = false
            try {
                service.ANWBT_RegistrySPPDataCallback(sppCallback)
                if (!service.ANWBT_GetSPPInitStatus()) {
                    sppInitializedByStream = true
                    val initResult = service.ANWBT_SPPInit(guidBytes)
                    if (initResult < 0) throw IOException("SPP init failed with $initResult")
                }
                val index = intArrayOf(-1)
                var ret = service.ANWBT_SPPConnect(address, guidBytes, index)
                Log.i(TAG, "ANWBT_SPPConnect(GUID) returned $ret index=${index[0]}")
                if (ret < 0 || index[0] < 0) {
                    if (sppInitializedByStream) service.ANWBT_SPPInit(rfcBytes)
                    ret = service.ANWBT_SPPConnect(address, rfcBytes, index)
                    Log.i(TAG, "ANWBT_SPPConnect(RFC) returned $ret index=${index[0]}")
                }
                sppIndex = index[0]
                if (ret >= 0 && index[0] >= 0) {
                    awaitConnected(service.asBinder(), PROFILE_SPP, index[0])
                    val stream = HsaeRfcommStream(
                        context = context,
                        service = service,
                        connectionIndex = index[0],
                        isSocketMode = false,
                        initializedByStream = sppInitializedByStream,
                        connection = serviceConnection,
                        socketCallback = null,
                        sppCallback = sppCallback
                    )
                    sppData.attach(stream, index[0])
                    Log.i(TAG, "HSAE SPP connect successful index=${index[0]}")
                    return stream
                }
                throw IOException("SPP connect returned ret=$ret index=${index[0]}")
            } catch (e: Exception) {
                Log.w(TAG, "SPPConnect failed: ${e.message}")
                sppData.close()
                runCatching { service.ANWBT_UnRegistrySPPDataCallback(sppCallback) }
                if (sppIndex >= 0) runCatching { service.ANWBT_SPPDisconnect(sppIndex) }
                if (sppInitializedByStream) runCatching { service.ANWBT_SPPDenit() }
            }

            runCatching { context.unbindService(serviceConnection) }
            throw IOException("HSAE Bluetooth RFCOMM connect failed for $address ($uuidString)")
        }

        internal fun uuidToBytes(uuidString: String): ByteArray {
            val uuid = UUID.fromString(uuidString)
            val bytes = ByteArray(16)
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            for (i in 0 until 8) {
                bytes[i] = ((msb ushr ((7 - i) * 8)) and 0xffL).toByte()
            }
            for (i in 8 until 16) {
                bytes[i] = ((lsb ushr ((15 - i) * 8)) and 0xffL).toByte()
            }
            return bytes
        }

        internal fun uuidToGuidBytes(uuidString: String): ByteArray {
            val raw = uuidToBytes(uuidString)
            val guid = ByteArray(16)
            guid[0] = raw[3]
            guid[1] = raw[2]
            guid[2] = raw[1]
            guid[3] = raw[0]
            guid[4] = raw[5]
            guid[5] = raw[4]
            guid[6] = raw[7]
            guid[7] = raw[6]
            System.arraycopy(raw, 8, guid, 8, 8)
            return guid
        }

        private fun awaitConnected(binder: IBinder, profile: Int, index: Int) {
            val deadline = System.nanoTime() + CONNECT_TIMEOUT_MILLIS * 1_000_000L
            var status = 0
            while (System.nanoTime() < deadline) {
                status = AnwPhoneLinkSnapshot.connectStatus(binder, profile, index)
                if (status == CONNECTED) return
                Thread.sleep(CONNECT_POLL_MILLIS)
            }
            throw IOException("Vendor RFCOMM did not reach CONNECTED; profile=$profile index=$index status=$status")
        }

        private const val PROFILE_SPP = 4
        private const val PROFILE_SOCKET = 17
        private const val CONNECTED = 1
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val CONNECT_POLL_MILLIS = 200L
    }
}

/** Queues Binder callbacks that can arrive after connect but before the stream is returned. */
private class CallbackDataQueue(private val route: String) {
    private val lock = Any()
    private val events = ArrayDeque<Event>()
    private var queuedBytes = 0
    private var stream: HsaeRfcommStream? = null
    private var connectionIndex = -1
    private var closed = false

    fun onDataReceived(index: Int, data: ByteArray, length: Int) {
        val safeLength = length.coerceIn(0, data.size)
        if (safeLength == 0) return
        val payload = data.copyOf(safeLength)
        synchronized(lock) {
            if (closed) return
            val target = stream
            if (target != null) {
                if (index == connectionIndex) {
                    target.onDataReceived(payload, payload.size)
                } else {
                    Log.w(TAG, "Ignoring $route callback for unexpected connection index=$index")
                }
                return
            }
            while (events.size >= MAX_EVENTS || queuedBytes + payload.size > MAX_QUEUED_BYTES) {
                val discarded = events.pollFirst() ?: break
                queuedBytes -= discarded.bytes.size
            }
            if (payload.size > MAX_QUEUED_BYTES) {
                Log.w(TAG, "Discarding oversized $route callback bytes=${payload.size}")
            } else {
                events.addLast(Event(index, payload))
                queuedBytes += payload.size
            }
        }
    }

    fun attach(target: HsaeRfcommStream, index: Int) {
        synchronized(lock) {
            if (closed) return
            connectionIndex = index
            stream = target
            while (events.isNotEmpty()) {
                val event = events.removeFirst()
                queuedBytes -= event.bytes.size
                if (event.index == index) {
                    target.onDataReceived(event.bytes, event.bytes.size)
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            events.clear()
            queuedBytes = 0
            stream = null
        }
    }

    private data class Event(val index: Int, val bytes: ByteArray)

    private companion object {
        const val MAX_EVENTS = 128
        const val MAX_QUEUED_BYTES = 512 * 1024
        const val TAG = "HsaeRfcommStream"
    }
}
