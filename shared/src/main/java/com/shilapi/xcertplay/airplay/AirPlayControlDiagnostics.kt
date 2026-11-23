package com.shilapi.xcertplay.airplay

/** Only fixed protocol routes/methods and byte counts survive into the diagnostic report. */
internal object AirPlayControlDiagnostics {
    fun request(method: String, path: String, bytes: Int): String {
        return "airplay control request ${requestLabel(method, path)} contentBytes=$bytes"
    }

    fun requestLabel(method: String, path: String): String {
        val verb = method.takeIf { it in methods } ?: "OTHER"
        val route = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            .takeIf { it in routes } ?: "other"
        return "method=$verb route=$route"
    }

    private val methods = setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER")
    private val routes = setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup")
}
