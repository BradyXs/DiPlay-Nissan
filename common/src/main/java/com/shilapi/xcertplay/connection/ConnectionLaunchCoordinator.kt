package com.shilapi.xcertplay.connection

/** Retains the request while a connection prerequisite is being completed asynchronously. */
internal class ConnectionLaunchCoordinator {
    data class PendingLaunch(val wireless: Boolean, val automaticLaunch: Boolean)

    private var pendingNissanDefaultsLaunch: PendingLaunch? = null
    private var pendingPhoneSelectionTransport: Boolean? = null
    private var pendingNotificationTransport: Boolean? = null

    fun decide(request: ConnectionLaunchPolicy.Request): ConnectionLaunchPolicy.Decision {
        clear()
        val decision = ConnectionLaunchPolicy.decide(request)
        when (decision) {
            is ConnectionLaunchPolicy.Decision.WaitForNissanDefaults -> {
                pendingNissanDefaultsLaunch = PendingLaunch(decision.wireless, decision.automaticLaunch)
            }
            ConnectionLaunchPolicy.Decision.ChoosePhone -> {
                pendingPhoneSelectionTransport = request.wireless
            }
            is ConnectionLaunchPolicy.Decision.RequestNotificationPermission -> {
                pendingNotificationTransport = decision.wireless
            }
            else -> Unit
        }
        return decision
    }

    fun takeNissanDefaultsLaunch(): PendingLaunch? = pendingNissanDefaultsLaunch.also {
        pendingNissanDefaultsLaunch = null
    }

    fun takePhoneSelectionTransport(): Boolean? = pendingPhoneSelectionTransport.also {
        pendingPhoneSelectionTransport = null
    }

    fun cancelPhoneSelection() {
        pendingPhoneSelectionTransport = null
    }

    /** Notification permission result retries the pending transport; wireless remains the legacy fallback. */
    fun takeNotificationTransport(): Boolean = (pendingNotificationTransport ?: true).also {
        pendingNotificationTransport = null
    }

    fun clear() {
        pendingNissanDefaultsLaunch = null
        pendingPhoneSelectionTransport = null
        pendingNotificationTransport = null
    }
}
