package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.util.Collections
import java.util.WeakHashMap

/** KitKat exposes only the first configuration and omits alternate-setting IDs in Java. */
internal object KitKatUsbDescriptors {
    data class Endpoint(val address: Int, val attributes: Int, val packetSize: Int, val interval: Int)
    data class Interface(val id: Int, val alternate: Int, val clazz: Int, val subclass: Int,
        val protocol: Int, val endpoints: MutableList<Endpoint> = mutableListOf())
    data class Configuration(val id: Int, val interfaces: MutableList<Interface> = mutableListOf())
    private val alternates = Collections.synchronizedMap(WeakHashMap<UsbInterface, Int>())

    fun alternate(iface: UsbInterface): Int? = alternates[iface]

    fun read(connection: UsbDeviceConnection): List<CarPlayUsbConfiguration> {
        val raw = connection.rawDescriptors ?: return emptyList()
        var configurations = parse(raw)
        // Read all configurations through the authorized endpoint 0 if the vendor framework
        // returns only the device descriptor. Never guess configuration 1 or 6.
        if (raw.size >= 18 && raw[1].toInt() == 1 && configurations.size < (raw[17].toInt() and 255)) {
            configurations = (0 until (raw[17].toInt() and 255).coerceAtMost(8)).flatMap { index ->
                val header = ByteArray(9)
                val got = connection.controlTransfer(0x80, 6, 0x0200 or index, 0, header, 9, 1000)
                val size = if (got == 9) u16(header, 2) else 0
                if (size !in 9..65535) emptyList() else {
                    val bytes = ByteArray(size)
                    val count = connection.controlTransfer(0x80, 6, 0x0200 or index, 0, bytes, size, 2000)
                    if (count != size) emptyList() else parse(bytes)
                }
            }
        }
        return configurations.map { config -> CarPlayUsbConfiguration(config.id, config.interfaces.map(::platformInterface)) }
    }

    fun parse(bytes: ByteArray): List<Configuration> {
        val result = mutableListOf<Configuration>()
        var cursor = 0
        var end = 0
        var configuration: Configuration? = null
        var iface: Interface? = null
        while (cursor + 2 <= bytes.size) {
            val length = bytes[cursor].toInt() and 255
            if (length < 2 || cursor + length > bytes.size) return emptyList()
            if (configuration != null && cursor < end && cursor + length > end) return emptyList()
            when (bytes[cursor + 1].toInt() and 255) {
                2 -> {
                    if (length < 9) return emptyList()
                    if (configuration != null && cursor < end) return emptyList()
                    val total = u16(bytes, cursor + 2)
                    if (total < length || cursor + total > bytes.size) return emptyList()
                    configuration = Configuration(bytes[cursor + 5].toInt() and 255).also(result::add)
                    end = cursor + total
                    iface = null
                }
                4 -> {
                    if (configuration == null || cursor >= end || length < 9) return emptyList()
                    iface = Interface(bytes[cursor + 2].toInt() and 255, bytes[cursor + 3].toInt() and 255,
                        bytes[cursor + 5].toInt() and 255, bytes[cursor + 6].toInt() and 255,
                        bytes[cursor + 7].toInt() and 255).also(configuration.interfaces::add)
                }
                5 -> {
                    if (iface == null || cursor >= end || length < 7) return emptyList()
                    iface.endpoints += Endpoint(bytes[cursor + 2].toInt() and 255,
                        bytes[cursor + 3].toInt() and 255, u16(bytes, cursor + 4), bytes[cursor + 6].toInt() and 255)
                }
            }
            cursor += length
        }
        return if (cursor == bytes.size) result else emptyList()
    }

    internal fun platformInterface(info: Interface): UsbInterface {
        val primitive = Int::class.javaPrimitiveType!!
        val endpoints = info.endpoints.map {
            UsbEndpoint::class.java.getDeclaredConstructor(primitive, primitive, primitive, primitive)
                .apply { isAccessible = true }.newInstance(it.address, it.attributes, it.packetSize, it.interval)
        }.toTypedArray()
        val iface = UsbInterface::class.java.getDeclaredConstructor(primitive, primitive, primitive, primitive,
            Array<android.os.Parcelable>::class.java).apply { isAccessible = true }
            .newInstance(info.id, info.clazz, info.subclass, info.protocol, endpoints)
        alternates[iface] = info.alternate
        return iface
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
}
