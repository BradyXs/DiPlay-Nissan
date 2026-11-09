package com.shilapi.xcertplay.connection

/** Resolves connection prerequisites before the Activity starts or replaces a CarPlay session. */
internal object ConnectionLaunchPolicy {
    data class Request(
        val wireless: Boolean,
        val automaticLaunch: Boolean,
        val nissanDefaultsReady: Boolean,
        val hotspotSetupPending: Boolean,
        val setupError: String?,
        val manualHotspotCredentialsInvalid: Boolean,
        val phoneSelectionRequired: Boolean,
        val notificationPermissionRequired: Boolean,
        val sessionAlreadyRunning: Boolean,
    )

    sealed class Decision {
        data class WaitForNissanDefaults(val wireless: Boolean, val automaticLaunch: Boolean) : Decision()
        data class CompleteHotspotSetup(val credentialsInvalid: Boolean) : Decision()
        data class ShowSetupError(val message: String) : Decision()
        object ChoosePhone : Decision()
        data class RequestNotificationPermission(val wireless: Boolean) : Decision()
        data class Launch(val wireless: Boolean, val replaceSession: Boolean) : Decision()
    }

    fun decide(request: Request): Decision = when {
        !request.nissanDefaultsReady ->
            Decision.WaitForNissanDefaults(request.wireless, request.automaticLaunch)
        request.wireless && request.hotspotSetupPending ->
            Decision.CompleteHotspotSetup(credentialsInvalid = false)
        request.setupError != null ->
            Decision.ShowSetupError(request.setupError)
        request.wireless && request.manualHotspotCredentialsInvalid ->
            Decision.CompleteHotspotSetup(credentialsInvalid = true)
        request.wireless && request.phoneSelectionRequired ->
            Decision.ChoosePhone
        request.notificationPermissionRequired ->
            Decision.RequestNotificationPermission(request.wireless)
        else ->
            Decision.Launch(request.wireless, replaceSession = request.sessionAlreadyRunning)
    }
}
