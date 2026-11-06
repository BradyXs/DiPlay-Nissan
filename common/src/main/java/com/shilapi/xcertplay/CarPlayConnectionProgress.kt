package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** Milestones actually reported by the connection path; counts are never estimated from time. */
internal class CarPlayConnectionProgress(var wireless: Boolean) {
    val totalSteps: Int = 5
    var completedSteps: Int = 0
        private set

    fun reset(wireless: Boolean = this.wireless) {
        this.wireless = wireless
        completedSteps = 0
    }

    fun onStatus(status: CarPlayStatus) {
        val reached = when (status) {
            CarPlayStatus.MfiReady -> 1
            is CarPlayStatus.HotspotReady -> if (wireless) 2 else 0
            CarPlayStatus.RunningWireless,
            CarPlayStatus.WirelessActive,
            CarPlayStatus.WirelessActiveFallback -> if (wireless) 3 else 0
            CarPlayStatus.Pairing -> if (wireless) 0 else 2
            CarPlayStatus.ConnectingControl,
            CarPlayStatus.RunningControl -> if (wireless) 0 else 3
            else -> 0
        }
        completedSteps = maxOf(completedSteps, reached)
    }

    fun onSessionActive() {
        completedSteps = maxOf(completedSteps, totalSteps - 1)
    }

    fun onFirstFrame() { completedSteps = totalSteps }
}
