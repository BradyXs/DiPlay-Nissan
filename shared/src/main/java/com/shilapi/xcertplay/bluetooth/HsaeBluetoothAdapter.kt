package com.shilapi.xcertplay.bluetooth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import com.hsae.bluetoothsdk.IBluetoothCallback
import com.hsae.bluetoothsdk.IBluetoothManager
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HsaeBluetoothAdapter(
    private val context: Context
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "HsaeBluetoothAdapter"
    }

    private val adapterName = "HsaeBluetoothAdapter"
    private val lock = Any()
    private var isBinding = false
    private var service: IBluetoothManager? = null
    private var initLatch = CountDownLatch(1)

    private fun hasHsaeBluetoothManager(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.hsae.bluetoothservice", 0)
    }.isSuccess

    private val callback = object : IBluetoothCallback.Stub() {
        override fun onPowerStateChanged(state: Int) {
            Log.i(TAG, "HSAE onPowerStateChanged: $state")
        }

        override fun onConnectStateChanged(profile: Int, state: Int, reason: Int) {
            Log.i(TAG, "HSAE onConnectStateChanged profile=$profile state=$state reason=$reason")
        }

        override fun onPairStateChanged(address: String?, state: Int) {
            Log.i(TAG, "HSAE onPairStateChanged address=$address state=$state")
        }

        override fun onDeviceConnectRequest(address: String?, name: String?, cod: Int, profile: Int) {}
        override fun onDevicePairRequest(address: String?, name: String?, ssp: Boolean) {}
        override fun onDeviceInquiried(address: String?, name: String?, cod: Int, rssi: Int, complete: Boolean) {}
        override fun onDeviceNameChanged(name: String?) {}
        override fun onBatterLevelChanged(level: Int) {}
        override fun onSignelLevelChanged(level: Int) {}
        override fun onBtStateChanged(state: Int, address: String?) {}
        override fun onServiceConnected() {}
        override fun onServiceDisconnected() {}
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "HSAE BluetoothService connected")
            synchronized(lock) {
                val mgr = IBluetoothManager.Stub.asInterface(binder)
                service = mgr
                isBinding = false
                runCatching {
                    mgr?.registCallback(callback)
                }
                initLatch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "HSAE BluetoothService disconnected")
            synchronized(lock) {
                service = null
                isBinding = false
                initLatch = CountDownLatch(1)
            }
        }
    }

    init {
        if (hasHsaeBluetoothManager()) {
            bindService()
        }
    }

    override fun isSupported(): Boolean {
        // The Nissan 4.4.3 image has AnwSdkService but may not install the newer HSAE manager app.
        return hasHsaeBluetoothManager() || AnwPhoneLinkSnapshot.isInstalled(context)
    }

    private fun bindService() {
        if (!hasHsaeBluetoothManager()) return
        synchronized(lock) {
            if (service != null || isBinding) {
                return
            }
            isBinding = true
        }
        try {
            val intent = Intent("com.hsae.bluetoothsdk.IBluetoothManager").apply {
                setPackage("com.hsae.bluetoothservice")
            }
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService to HSAE BluetoothService result=$bound")
            if (!bound) {
                synchronized(lock) {
                    isBinding = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to HSAE BluetoothService", e)
            synchronized(lock) {
                isBinding = false
            }
        }
    }

    private fun ensureService(timeoutMs: Long = 1500L): IBluetoothManager? {
        if (!hasHsaeBluetoothManager()) return null
        synchronized(lock) {
            service?.let { return it }
            if (!isBinding) {
                bindService()
            }
        }
        try {
            initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            return service
        }
    }

    override fun isEnabled(): Boolean {
        val mgr = ensureService()
        if (mgr != null) {
            try {
                val status = mgr.powerStatus
                Log.i(TAG, "HSAE getPowerStatus=$status")
                return status == 1
            } catch (e: RemoteException) {
                Log.e(TAG, "RemoteException reading HSAE power status", e)
            }
        }
        return runCatching { AnwPhoneLinkSnapshot.powerStatus(context) == 1 }
            .onFailure { Log.w(TAG, "Could not read Nissan AnwPhoneLink power status", it) }
            .getOrDefault(false)
    }

    override fun enable(): Boolean {
        val mgr = ensureService()
        if (mgr != null) {
            return try {
                mgr.btPowerOn()
                Log.i(TAG, "Bluetooth power-on requested through HSAE")
                true
            } catch (e: RemoteException) {
                Log.e(TAG, "RemoteException calling HSAE btPowerOn", e)
                false
            }
        }
        return anwSetPower(true)
    }

    override fun disable(): Boolean {
        val mgr = ensureService()
        if (mgr != null) {
            return try {
                mgr.btPowerOff()
                Log.i(TAG, "Bluetooth power-off requested through HSAE")
                true
            } catch (e: RemoteException) {
                Log.e(TAG, "RemoteException calling HSAE btPowerOff", e)
                false
            }
        }
        return anwSetPower(false)
    }

    private fun anwSetPower(enabled: Boolean): Boolean {
        if (!AnwPhoneLinkSnapshot.isInstalled(context)) return false
        return runCatching { AnwPhoneLinkSnapshot.setPower(context, enabled) }
            .onSuccess { Log.i(TAG, "Nissan AnwPhoneLink Bluetooth power=${if (enabled) "on" else "off"} requested=$it") }
            .onFailure { Log.w(TAG, "Nissan AnwPhoneLink Bluetooth power request failed", it) }
            .getOrDefault(false)
    }

    override fun getAdapterName(): String = adapterName

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        val mgr = ensureService()
        if (mgr != null) {
            try {
                val paired = mgr.pairedDevices ?: emptyList()
                val connectedMac = runCatching { mgr.connectDeviceAddress }.getOrNull()
                Log.i(TAG, "HSAE getPairedDevices count=${paired.size} connectedMac=$connectedMac")
                if (paired.isNotEmpty()) {
                    return paired.map { dev ->
                        val mac = dev.mac ?: ""
                        val name = dev.name?.takeIf { it.isNotBlank() } ?: "iPhone"
                        val isConnected = dev.status == 5 || (!connectedMac.isNullOrBlank() && connectedMac.equals(mac, ignoreCase = true))
                        CarPlayBluetoothDevice(
                            name = name,
                            address = mac,
                            isConnected = isConnected
                        )
                    }
                }
            } catch (e: RemoteException) {
                Log.e(TAG, "RemoteException calling HSAE getPairedDevices", e)
            }
        }
        return runCatching {
            val snapshot = AnwPhoneLinkSnapshot.read(context)
            snapshot.pairedDevices.map { device ->
                CarPlayBluetoothDevice(
                    name = device.name.ifBlank { "iPhone" },
                    address = device.address,
                    isConnected = device.address.equals(snapshot.connectedDevice?.address, ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "Could not read Nissan AnwPhoneLink paired devices", it) }
            .getOrDefault(emptyList())
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val mgr = ensureService()
        if (mgr != null) {
            try {
                val addr = mgr.connectDeviceAddress?.takeIf { it.isNotBlank() }
                if (addr != null) {
                    val name = mgr.connectDeviceName?.takeIf { it.isNotBlank() } ?: "iPhone"
                    return CarPlayBluetoothDevice(name = name, address = addr, isConnected = true)
                }
            } catch (e: RemoteException) {
                Log.e(TAG, "RemoteException calling HSAE getConnectedDevice", e)
            }
        }
        return runCatching { AnwPhoneLinkSnapshot.connectedDevice(context) }.getOrNull()?.let { device ->
            CarPlayBluetoothDevice(
                name = device.name.ifBlank { "iPhone" },
                address = device.address,
                isConnected = true,
            )
        }
    }

    override fun localAddress(context: Context): String? {
        // Prefer the address advertised by Nissan's own Bluetooth service.
        val vendorAddress = if (AnwPhoneLinkSnapshot.isInstalled(context)) {
            runCatching { AnwPhoneLinkSnapshot.localAddress(context) }
                .getOrNull()
                ?.let(BluetoothAddressResolver::normalize)
        } else null
        if (vendorAddress != null) return vendorAddress
        val settingAddr = runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        BluetoothAddressResolver.normalize(settingAddr)?.let { return it }
        val globalAddr = runCatching {
            Settings.Global.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        return BluetoothAddressResolver.normalize(globalAddr)
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        return HsaeRfcommStream.connect(context, deviceAddress, uuid)
    }
}
