package com.shilapi.xcertplay.connection

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.util.Locale

/** Coordinates startup transport discovery; the activity remains responsible for launching UI. */
internal class AutomaticConnectionCoordinator(
    context: Context,
    private val onConnect: (wireless: Boolean) -> Unit,
    private val onBluetoothUnavailable: () -> Unit,
    private val log: (String) -> Unit,
    private val recordBootResult: (String) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val appContext = context.applicationContext
    private var generation = 0
    private var scanRunnable: Runnable? = null
    private var bluetoothWaitThread: Thread? = null
    private var bootConnectionInProgress = false

    fun start(afterBoot: Boolean) {
        cancel()
        bootConnectionInProgress = afterBoot
        val token = generation
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scanWindowMillis = if (afterBoot) BOOT_USB_SCAN_WINDOW_MS else APP_USB_SCAN_WINDOW_MS
        val firstScanDelayMillis = if (afterBoot) BOOT_FIRST_SCAN_DELAY_MS else 0L
        recordBoot(afterBoot, "scanning_usb")
        log(
            "UI_STARTUP stage=auto-connect source=${if (afterBoot) "boot" else "app-open"} " +
                "usbScanWindowMs=$scanWindowMillis delayMs=$firstScanDelayMillis",
        )

        val scan = object : Runnable {
            override fun run() {
                if (token != generation) return
                val manager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
                val appleDevice = runCatching {
                    manager?.deviceList?.values?.firstOrNull {
                        it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
                    }
                }.getOrNull()

                if (appleDevice != null) {
                    scanRunnable = null
                    val product = "0x%04x".format(Locale.US, appleDevice.productId)
                    recordBoot(afterBoot, "selected_usb")
                    log("UI_STARTUP stage=auto-connect transport=usb reason=apple-device-attached product=$product")
                    recordBoot(afterBoot, "connecting_usb")
                    bootConnectionInProgress = false
                    onConnect(false)
                    return
                }

                if (SystemClock.elapsedRealtime() - scanStartedAt < scanWindowMillis) {
                    handler.postDelayed(this, USB_SCAN_INTERVAL_MS)
                    return
                }

                scanRunnable = null
                val wireless = DiPlayPreferences.autoConnectWireless(appContext)
                val mode = DiPlayPreferences.defaultConnectionMode(appContext)
                recordBoot(afterBoot, if (wireless) "selected_default_wireless" else "selected_default_usb")
                log(
                    "UI_STARTUP stage=auto-connect transport=${if (wireless) "wireless" else "usb"} " +
                        "reason=default-mode mode=${mode.key} " +
                        "savedIphone=${DiPlayPreferences.phoneAddress(appContext) != null}",
                )
                startConnection(wireless, token, afterBoot)
            }
        }
        scanRunnable = scan
        handler.postDelayed(scan, firstScanDelayMillis)
    }

    fun cancel() {
        if (bootConnectionInProgress) recordBootResult("cancelled")
        bootConnectionInProgress = false
        generation++
        scanRunnable?.let(handler::removeCallbacks)
        scanRunnable = null
        bluetoothWaitThread?.interrupt()
        bluetoothWaitThread = null
    }

    private fun startConnection(wireless: Boolean, token: Int, afterBoot: Boolean) {
        if (!wireless) {
            recordBoot(afterBoot, "connecting_usb")
            bootConnectionInProgress = false
            onConnect(false)
            return
        }

        recordBoot(afterBoot, "waiting_bluetooth")
        val worker = Thread({
            var wasEnabled = false
            var enableRequested = false
            var enabled = false
            var failure: String? = null
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val adapter = CarPlayBluetoothManager.get(appContext)
                if (!adapter.isSupported()) {
                    failure = "unsupported"
                } else {
                    wasEnabled = adapter.isEnabled()
                    enableRequested = wasEnabled || adapter.enable()
                    val deadline = SystemClock.elapsedRealtime() + BLUETOOTH_READY_TIMEOUT_MS
                    while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                        if (adapter.isEnabled()) {
                            enabled = true
                            break
                        }
                        Thread.sleep(BLUETOOTH_POLL_INTERVAL_MS)
                    }
                }
            } catch (error: Exception) {
                failure = error.javaClass.simpleName
            }
            val waitedMillis = SystemClock.elapsedRealtime() - startedAt
            log(
                "UI_STARTUP stage=auto-connect-bluetooth before=$wasEnabled " +
                    "enableRequested=$enableRequested ready=$enabled waitedMs=$waitedMillis " +
                    "failure=${failure ?: "none"} thread=background",
            )
            handler.post {
                if (token != generation) return@post
                bluetoothWaitThread = null
                if (enabled) {
                    recordBoot(afterBoot, "connecting_wireless")
                    bootConnectionInProgress = false
                    onConnect(true)
                } else {
                    recordBoot(afterBoot, "bluetooth_not_ready")
                    bootConnectionInProgress = false
                    onBluetoothUnavailable()
                }
            }
        }, "diplay-auto-connect-bluetooth").apply {
            isDaemon = true
            bluetoothWaitThread = this
            start()
        }
    }

    private fun recordBoot(afterBoot: Boolean, result: String) {
        if (afterBoot) recordBootResult(result)
    }

    private companion object {
        const val BOOT_FIRST_SCAN_DELAY_MS = 1_500L
        const val BOOT_USB_SCAN_WINDOW_MS = 5_000L
        const val APP_USB_SCAN_WINDOW_MS = 750L
        const val USB_SCAN_INTERVAL_MS = 500L
        const val BLUETOOTH_READY_TIMEOUT_MS = 20_000L
        const val BLUETOOTH_POLL_INTERVAL_MS = 400L
    }
}
