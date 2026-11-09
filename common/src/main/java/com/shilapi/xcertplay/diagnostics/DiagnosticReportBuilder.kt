package com.shilapi.xcertplay.diagnostics

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AsyncDiagnosticLog
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayMediaKeys
import com.shilapi.xcertplay.CrashDiagnosticSnapshot
import com.shilapi.xcertplay.DiagnosticRedactor
import com.shilapi.xcertplay.DisplayDiagnosticSnapshot
import com.shilapi.xcertplay.LegacyConnectionProbe
import com.shilapi.xcertplay.NissanOemPhoneDiagnostics
import com.shilapi.xcertplay.NissanOemSourceDiagnostics
import com.shilapi.xcertplay.NissanOemVolumeDiagnostics
import com.shilapi.xcertplay.NissanPhoneKeyProbe
import com.shilapi.xcertplay.ProcessExitDiagnostics
import com.shilapi.xcertplay.SessionLogFile
import com.shilapi.xcertplay.StartupDiagnosticSnapshot
import com.shilapi.xcertplay.connection.DiPlayPreferences
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.UiPageDiagnostics
import java.io.File

/** Collects and redacts app diagnostics without depending on any Activity or View. */
internal object DiagnosticReportBuilder {
    fun build(context: Context, appVersion: String, setupErrorPresent: Boolean,
        nissanAndroid44Profile: Boolean): String {
        val appContext = context.applicationContext
        val sessionLogFlushed = AsyncDiagnosticLog.awaitIdle(5_000L)
        return buildString {
            appendLine("DiPlay $appVersion · diagnostic report")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Detailed playback logs: ${AirPlayPersistence.loadDetailedDiagnosticsEnabled(appContext)}; " +
                "sessionLogFlushed=$sessionLogFlushed queueDrops=${AsyncDiagnosticLog.droppedCount}")
            appendLine("--- Legacy USB / wireless / TLS provider diagnostics ---")
            append(LegacyConnectionProbe.report(appContext))
            appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
            appendLine("Authentication: local experimental beta identity; no remote fallback")
            val setupSummary = if (!setupErrorPresent) {
                "authentication check deferred until connection startup"
            } else {
                "authentication unavailable"
            }
            appendLine("CarPlay setup: $setupSummary")
            appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
            appendLine("CarPlay size: ${CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
            appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
            appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
            appendLine(runCatching { CarPlayMediaKeys.diagnosticStatus() }
                .getOrElse { "Media keys: status unavailable ${it.javaClass.simpleName}" })
            if (nissanAndroid44Profile) appendLine(NissanOemSourceDiagnostics.summary())
            if (nissanAndroid44Profile) appendLine(NissanPhoneKeyProbe.summary(appContext))
            if (nissanAndroid44Profile) appendLine(NissanOemVolumeDiagnostics.summary())
            if (nissanAndroid44Profile) appendLine(NissanOemPhoneDiagnostics.summary())
            appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
            appendLine()
            appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
            appendLine(DisplayDiagnosticSnapshot.report(appContext))
            appendLine()
            appendLine("--- Last received boot and app-launch result ---")
            appendLine(StartupDiagnosticSnapshot.report(appContext))
            appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
            appendLine()
            appendLine("--- Recent own-app process exits (Android 11+) ---")
            appendLine(ProcessExitDiagnostics.report(appContext))
            appendLine("--- Latest captured Java crash (Android 4.4 compatible) ---")
            appendLine(CrashDiagnosticSnapshot.report(appContext))
            appendLine()
            appendLine("--- UI page transitions, first draw, and black-screen suspects ---")
            append(UiPageDiagnostics.report(appContext))
            appendLine()
            for (name in SessionLogFile.REPORT_NAMES) {
                val file = File(appContext.filesDir, "logs/$name")
                if (file.isFile) {
                    appendLine("--- $name ---")
                    file.useLines { lines ->
                        lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } }
                    }
                }
            }
        }
    }
}
