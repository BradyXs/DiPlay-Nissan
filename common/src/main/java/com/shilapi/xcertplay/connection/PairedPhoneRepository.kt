package com.shilapi.xcertplay.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager
import com.shilapi.xcertplay.compat.BluetoothCompat

/** Merges phone pairings exposed by Android and Nissan's vendor Bluetooth service. */
internal class PairedPhoneRepository(context: Context) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    fun load(adapter: BluetoothAdapter?, fallbackName: String): List<PairedPhone> {
        val result = LinkedHashMap<String, PairedPhone>()

        val vendorBluetooth = CarPlayBluetoothManager.get(appContext)
        val vendorDevices = runCatching { vendorBluetooth.getPairedDevices() }
            .onFailure { Log.w(TAG, "Could not read Nissan paired-device list", it) }
            .getOrDefault(emptyList())
        for (device in vendorDevices) {
            val address = device.address.uppercase(java.util.Locale.US)
            if (!BluetoothAdapter.checkBluetoothAddress(address)) continue
            val name = device.name.takeIf { it.isNotBlank() } ?: fallbackName
            result[address] = PairedPhone(address, name, device.isConnected)
        }

        if (adapter != null) {
            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            for (device in bonded) {
                val address = device.address?.uppercase(java.util.Locale.US) ?: continue
                val connected = isConnected(device)
                val name = device.name?.takeIf { it.isNotBlank() }
                    ?: fallbackName
                val previous = result[address]
                result[address] = PairedPhone(address, name, connected || previous?.isConnected == true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                for (device in BluetoothCompat.getConnectedGattDevices(appContext)) {
                    val address = device.address?.uppercase(java.util.Locale.US) ?: continue
                    val existing = result[address]
                    val name = device.name?.takeIf { it.isNotBlank() }
                        ?: existing?.name
                        ?: fallbackName
                    result[address] = PairedPhone(address, name, isConnected = true)
                }
            }

            runCatching { queryConnectedProfileDevices(adapter) }
                .onFailure { Log.w(TAG, "Could not read connected Bluetooth profiles", it) }
                .getOrDefault(emptySet())
                .forEach { device ->
                    val address = device.address?.uppercase(java.util.Locale.US) ?: return@forEach
                    val existing = result[address]
                    val name = device.name?.takeIf { it.isNotBlank() }
                        ?: existing?.name
                        ?: fallbackName
                    result[address] = PairedPhone(address, name, isConnected = true)
                }
        }

        return result.values.sortedWith(
            compareByDescending<PairedPhone> { it.isConnected }
                .thenByDescending { it.name.contains("iPhone", ignoreCase = true) }
                .thenBy { it.name },
        )
    }

    @SuppressLint("MissingPermission")
    private fun isConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    @SuppressLint("MissingPermission")
    private fun queryConnectedProfileDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> {
        val connected = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val latch = java.util.concurrent.CountDownLatch(2)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.connectedDevices?.let { connected.addAll(it) }
                } catch (_: Throwable) {
                } finally {
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                latch.countDown()
            }
        }

        val hasHeadset = runCatching {
            adapter.getProfileProxy(appContext, listener, BluetoothProfile.HEADSET)
        }.getOrDefault(false)
        if (!hasHeadset) latch.countDown()

        val hasA2dp = runCatching {
            adapter.getProfileProxy(appContext, listener, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!hasA2dp) latch.countDown()

        runCatching { latch.await(PROFILE_QUERY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return synchronized(connected) { connected.toSet() }
    }

    private companion object {
        const val TAG = "DiPlayBluetooth"
        const val PROFILE_QUERY_TIMEOUT_MS = 600L
    }
}

internal data class PairedPhone(
    val address: String,
    val name: String,
    val isConnected: Boolean,
)
