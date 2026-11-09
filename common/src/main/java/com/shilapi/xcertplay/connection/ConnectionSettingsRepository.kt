package com.shilapi.xcertplay.connection

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible

/** Persists and validates wireless connection choices without depending on page Views. */
internal class ConnectionSettingsRepository(
    context: Context,
    private val nissanAndroid44Profile: Boolean,
) {
    private val appContext = context.applicationContext

    fun hotspotMode(): WirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(appContext)

    fun saveHotspotMode(mode: WirelessHotspotMode) =
        AirPlayPersistence.saveWirelessHotspotMode(appContext, mode)

    fun carHotspotOff(): Boolean =
        hotspotMode() == WirelessHotspotMode.MANUAL && CarHotspotStatus.isEnabled(appContext) == false

    fun manualHotspotCredentials() = WifiCredentials(
        AirPlayPersistence.loadManualHotspotSsid(appContext),
        AirPlayPersistence.loadManualHotspotPassphrase(appContext),
    )

    fun existingWifiCredentials() = WifiCredentials(
        AirPlayPersistence.loadExistingWifiSsid(appContext),
        AirPlayPersistence.loadExistingWifiPassphrase(appContext),
    )

    fun validateHotspotCredentials(ssid: String, passphrase: String): ManualHotspotValidation.Error? =
        ManualHotspotValidation.error(ssid, passphrase)

    fun saveManualHotspotCredentials(ssid: String, passphrase: String) {
        AirPlayPersistence.saveManualHotspotSsid(appContext, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(appContext, passphrase)
        val currentSecurity = AirPlayPersistence.loadManualHotspotSecurity(appContext)
        if (nissanAndroid44Profile ||
            (currentSecurity == ManualHotspotSecurity.OPEN) != passphrase.isEmpty()) {
            AirPlayPersistence.saveManualHotspotSecurity(appContext,
                ManualHotspotValidation.securityFor(passphrase))
        }
        if (nissanAndroid44Profile) {
            AirPlayPersistence.saveManualHotspotBand(appContext, ManualHotspotBand.AUTO)
            AirPlayPersistence.saveManualHotspotChannel(appContext, 0)
        }
    }

    fun saveExistingWifiCredentials(ssid: String, passphrase: String) =
        AirPlayPersistence.saveExistingWifiCredentials(appContext, ssid, passphrase)

    fun preferredWifiDirectChannel(): Int = AirPlayPersistence.loadWifiP2pPreferredChannel(appContext)

    fun savePreferredWifiDirectChannel(channel: Int) =
        AirPlayPersistence.saveWifiP2pPreferredChannel(appContext, channel)

    fun manualHotspotBand(): ManualHotspotBand = AirPlayPersistence.loadManualHotspotBand(appContext)

    fun saveManualHotspotBand(band: ManualHotspotBand) {
        AirPlayPersistence.saveManualHotspotBand(appContext, band)
        val channel = manualHotspotChannel()
        if (channel != 0 && !isManualHotspotChannelCompatible(band, channel)) {
            AirPlayPersistence.saveManualHotspotChannel(appContext, 0)
        }
    }

    fun manualHotspotChannel(): Int = AirPlayPersistence.loadManualHotspotChannel(appContext)

    fun isManualHotspotChannelValid(channel: Int): Boolean = channel == 0 ||
        isManualHotspotChannelCompatible(manualHotspotBand(), channel)

    fun saveManualHotspotChannel(channel: Int) =
        AirPlayPersistence.saveManualHotspotChannel(appContext, channel)

    fun manualHotspotSecurity(): ManualHotspotSecurity =
        AirPlayPersistence.loadManualHotspotSecurity(appContext)

    fun canUseManualHotspotSecurity(security: ManualHotspotSecurity): Boolean =
        (security == ManualHotspotSecurity.OPEN) == manualHotspotCredentials().passphrase.isEmpty()

    fun saveManualHotspotSecurity(security: ManualHotspotSecurity) =
        AirPlayPersistence.saveManualHotspotSecurity(appContext, security)
}

internal data class WifiCredentials(val ssid: String, val passphrase: String)
