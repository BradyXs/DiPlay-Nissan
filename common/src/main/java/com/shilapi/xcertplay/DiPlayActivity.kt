// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.view.Window
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.shilapi.xcertplay.compat.BluetoothCompat
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.bluetooth.BluetoothAddressResolver
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothManager
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, DIAGNOSTICS, ADVANCED,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    DISPLAY_AND_PERFORMANCE,
    ADVANCED_MEDIA,
    CAR_BUTTON,
    AUDIO_ROUTING,
    LOCATION,
    PERMISSIONS_AND_HELP,
    ABOUT,
    LANGUAGE,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.OVERVIEW to setOf(SettingsSection.ABOUT, SettingsSection.LANGUAGE),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.PERMISSIONS_AND_HELP,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.LOCATION),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.CAR_BUTTON,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.ADVANCED to setOf(
            SettingsSection.ADVANCED_MEDIA,
        ),
    )
}

internal const val SETUP_ERROR_EXTRA = "com.shilapi.xcertplay.extra.SETUP_ERROR"

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val hardwareDiagnosticLog by lazy { SessionLogFile(File(File(filesDir, "logs"), "diplay.log")) }
    private val startupProbeFallback = Runnable {
        // A diagnostic probe is optional while the home screen is entering. CarPlay's host
        // performs its own authentication bootstrap if the driver connects first.
        if (uiActivityResumed && page == "home") runLegacyProbeInBackground()
    }
    private var startupProbeDispatched = false
    private val nissanAndroid44Profile =
        com.shilapi.xcertplay.host.BuildConfig.NISSAN_ANDROID_44_PROFILE
    private var nissanDefaultsReady = !nissanAndroid44Profile
    private var nissanDefaultsInProgress = false
    private var pendingNissanConnect: Boolean? = null
    private var nissanSettingsRenderPending = false
    private var nissanBluetoothStatusNeedsRefresh = true
    private var nissanBluetoothStatusButton: Button? = null
    private var nissanBluetoothStatusDevice: Pair<String, Boolean>? = null
    private var nissanBluetoothStatusLoaded = false
    private val afterNissanDefaults = mutableListOf<() -> Unit>()
    private val pendingNissanConnectAction: () -> Unit = {
        val wireless = pendingNissanConnect
        pendingNissanConnect = null
        if (wireless != null && !isFinishing && !isDestroyed) connect(wireless)
    }
    private var windowLearning: WindowKeyLearning? = null
    private val windowLearningPresses = WheelKeyPresses()
    private val endWindowLearning = Runnable { cancelKeyLearning() }
    private var page = "home"
    private var settingsCategory = SettingsCategory.OVERVIEW
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var settingsSectionFilter: Set<SettingsSection>? = null
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var skipNextResumeRefresh = false
    private var notificationTransport = true
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var settingsRailScroll: ScrollView? = null
    private var reconnectBar: View? = null
    private var readinessCard: LinearLayout? = null
    private var searchIndexSink: MutableList<String>? = null
    private var renderedReadiness: SettingsReadiness? = null
    private var renderedPage: String? = null
    private var renderedSettingsCategory: SettingsCategory? = null
    private var renderedUiDestination: String? = null
    private var uiPageProbe: UiPageDrawProbe? = null
    private var uiActivityResumed = false
    private var activityCreateStartedAtNs = 0L
    private var pendingScrollY: Int? = null
    private var pendingRailScrollY: Int? = null
    private var pendingRailFocus = false
    private var carButtonCard: LinearLayout? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent(), ::cropIcon)
    private val iconDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropIcon)
    private fun cropIcon(uri: Uri?) {
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM
    private var userNavigatedFromCarPlay = false

    private var interfaceOverride: Configuration? = null
    private var interfaceSystemDensityDpi = 0
    private var interfaceRecreateRequested = false

    private fun enforceInterfaceSize(): Boolean {
        val language = AppLocale.enforce(this)
        if (language) android.util.Log.i("DiPlayUi", "app language re-applied")
        val override = interfaceOverride ?: return language
        val found = resources.displayMetrics.densityDpi
        if (!InterfaceSize.enforce(resources, override)) return language
        android.util.Log.i("DiPlayUi", "interface size re-applied: $found -> ${override.densityDpi} dpi")
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val base = AppLocale.wrap(newBase)
        super.attachBaseContext(base)
        interfaceSystemDensityDpi = base.resources.configuration.densityDpi
        interfaceOverride = InterfaceSize.attach(this, base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityCreateStartedAtNs = SystemClock.elapsedRealtimeNanos()
        setupError = intent.getStringExtra(SETUP_ERROR_EXTRA)
        UiPageDiagnostics.initialize(applicationContext)
        UiPageDiagnostics.activityEvent("create", javaClass.simpleName, intent.getStringExtra("page") ?: "home")
        // Back on the home page finishes this activity while the session runs on, so the icon lands here.
        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            openProjection(); finish(); return
        }
        if (intent.hasExtra("page")) {
            userNavigatedFromCarPlay = true
        }
        val localeAndSizeStartedAtNs = SystemClock.elapsedRealtimeNanos()
        enforceInterfaceSize()
        languagePreferenceAtCreate = AppLocale.preference(this)
        logStartupStage("locale-and-interface-size", localeAndSizeStartedAtNs)
        val wheelRestoreStartedAtNs = SystemClock.elapsedRealtimeNanos()
        logStartupStage("wheel-key-restore-schedule", wheelRestoreStartedAtNs)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        runCatching {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
            ?: SettingsCategory.OVERVIEW
        connectionSettingsReturnCategory = savedInstanceState?.getString("connection_settings_return_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        if (savedInstanceState == null) {
            scheduleLegacyProbeAfterFirstDraw(rootScroll ?: window.decorView)
        }
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") navigateBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        // Android may deliver the new intent before onResume; its render is already fresh.
        if (!uiActivityResumed) skipNextResumeRefresh = true
        intent.getStringExtra(SETUP_ERROR_EXTRA)?.let { setupError = it }
        connectionSettingsReturnCategory = null
        // CarPlay runs in its own task, so the launcher icon resumes this one. Settings opened from
        // CarPlay carry a "page" extra, which isLauncherIntent rejects.
        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            userNavigatedFromCarPlay = false
            page = "home"; render(); openProjection(); return
        }
        val explicitPage = intent.getStringExtra("page")
        if (explicitPage != null) {
            userNavigatedFromCarPlay = true
            page = explicitPage
            render()
        } else if (CarPlayBackgroundSession.hasSession() && !userNavigatedFromCarPlay) {
            page = "home"; render(); openProjection(); return
        } else {
            page = "home"; render()
        }
        handleWirelessRecovery()
    }

    /** Run the startup hardware/TLS probe off the UI thread after the first home frame is drawn. */
    private fun scheduleLegacyProbeAfterFirstDraw(view: View) {
        // Auto-connect or a system permission screen may cover the home window before its first draw.
        // Leave the first frames clear of MFi validation and the hardware/TLS probe on API 19.
        handler.postDelayed(startupProbeFallback, 8_000L)
        val observer = view.viewTreeObserver
        if (!observer.isAlive) {
            handler.removeCallbacks(startupProbeFallback)
            runLegacyProbeInBackground()
            return
        }
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            private var dispatched = false

            override fun onDraw() {
                if (dispatched) return
                dispatched = true
                handler.post {
                    val currentObserver = view.viewTreeObserver
                    if (currentObserver.isAlive) {
                        runCatching { currentObserver.removeOnDrawListener(this) }
                    }
                    handler.removeCallbacks(startupProbeFallback)
                    runLegacyProbeInBackground()
                }
            }
        }
        observer.addOnDrawListener(listener)
    }

    private fun runLegacyProbeInBackground() {
        if (startupProbeDispatched) return
        startupProbeDispatched = true
        ensureNissanDefaults()
        if (nissanAndroid44Profile && AirPlayPersistence.loadWirelessEnabled(this) &&
            !CarPlayBackgroundSession.hasSession()) {
            Thread({
                runCatching {
                    val adapter = CarPlayBluetoothManager.get(applicationContext)
                    val before = adapter.isEnabled()
                    val requested = if (before) true else adapter.enable()
                    val after = adapter.isEnabled()
                    UiPageDiagnostics.log(
                        "UI_STARTUP stage=nissan-bluetooth-prepare before=$before " +
                            "requested=$requested after=$after backend=${adapter.getAdapterName()} thread=background",
                    )
                }.onFailure { Log.w("DiPlayBluetooth", "Nissan Bluetooth startup preparation failed", it) }
            }, "diplay-nissan-bluetooth-prepare").apply { isDaemon = true; start() }
        }
        Thread({
            val authStartedAtNs = SystemClock.elapsedRealtimeNanos()
            val authTarget = AirPlayPersistence.loadMfiTarget(applicationContext)
            val authFailure = runCatching {
                DiPlayBootstrap.ensure(applicationContext, authTarget)
            }.exceptionOrNull()
            val authElapsedMs = (SystemClock.elapsedRealtimeNanos() - authStartedAtNs)
                .coerceAtLeast(0L) / 1_000_000L
            UiPageDiagnostics.log(
                "UI_STARTUP stage=mfi-bootstrap target=$authTarget elapsedMs=$authElapsedMs " +
                    "result=${if (authFailure == null) "ready" else "failed"} thread=background",
            )
            if (authFailure != null) {
                Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", authFailure)
                handler.post {
                    if (!isFinishing && !isDestroyed) {
                        setupError = getString(R.string.setup_error_auth)
                        render()
                    }
                }
            }
            val startedAtNs = SystemClock.elapsedRealtimeNanos()
            LegacyConnectionProbe.log(applicationContext)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs)
                .coerceAtLeast(0L) / 1_000_000L
            UiPageDiagnostics.log(
                "UI_STARTUP stage=legacy-connection-probe elapsedMs=$elapsedMs thread=background",
            )
        }, "diplay-startup-probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun logStartupStage(stage: String, startedAtNs: Long) {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs)
            .coerceAtLeast(0L) / 1_000_000L
        UiPageDiagnostics.log("UI_STARTUP stage=$stage elapsedMs=$elapsedMs thread=main")
    }

    private fun ensureNissanDefaults(afterReady: (() -> Unit)? = null) {
        if (!nissanAndroid44Profile || nissanDefaultsReady) {
            afterReady?.invoke()
            return
        }
        afterReady?.let(afterNissanDefaults::add)
        if (nissanDefaultsInProgress) return
        nissanDefaultsInProgress = true
        Thread({
            val startedAtNs = SystemClock.elapsedRealtimeNanos()
            val failure = runCatching {
                AirPlayPersistence.applyNissanAndroid44DefaultsOnce(this, profileEnabled = true)
            }.exceptionOrNull()
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAtNs)
                .coerceAtLeast(0L) / 1_000_000L
            UiPageDiagnostics.log(
                "UI_STARTUP stage=nissan-defaults elapsedMs=$elapsedMs " +
                    "result=${if (failure == null) "ready" else "failed"} thread=background",
            )
            handler.post {
                nissanDefaultsReady = true
                nissanDefaultsInProgress = false
                if (failure != null) Log.w("DiPlayStartup", "Nissan playback defaults could not be applied", failure)
                val callbacks = afterNissanDefaults.toList()
                afterNissanDefaults.clear()
                callbacks.forEach { callback -> runCatching(callback) }
            }
        }, "diplay-nissan-profile-init").apply {
            isDaemon = true
            start()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("settings_category", settingsCategory.name)
        connectionSettingsReturnCategory?.let { outState.putString("connection_settings_return_category", it.name) }
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    /** True while a density change is recreating this activity. */
    private fun updateInterfaceSize(configuration: Configuration): Boolean {
        if (interfaceRecreateRequested) return true
        // The activity handles real system density changes too. Its callback contains our installed
        // density, while application resources retain the current unscaled system density.
        applicationContext.resources.configuration.densityDpi.takeIf { it > 0 }?.let {
            interfaceSystemDensityDpi = it
        }
        val next = InterfaceSize.configurationChange(configuration, interfaceSystemDensityDpi,
            InterfaceSize.preference(this))
        if (InterfaceSize.needsRecreate(interfaceOverride, next)) {
            interfaceRecreateRequested = true
            recreate()
            return true
        }
        interfaceOverride = next
        return false
    }
    override fun onStart() {
        super.onStart()
        // Results from the image picker and crop screens arrive before onResume.
        enforceInterfaceSize()
    }

    override fun onStop() {
        cancelUsbPermissionSetup()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val refreshedForNewIntent = skipNextResumeRefresh
        skipNextResumeRefresh = false
        nissanBluetoothStatusNeedsRefresh = true
        uiActivityResumed = true
        uiPageProbe?.onForeground()
        UiPageDiagnostics.activityEvent("resume", javaClass.simpleName, uiPageLabel())
        // Returning from another activity can bring the head unit's own density back.
        val sizeChanged = enforceInterfaceSize()
        if (sizeChanged) render()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        if (!initialLaunch && !refreshedForNewIntent && !sizeChanged) {
            if (nissanAndroid44Profile && (page == "home" || page == "settings")) {
                // Replacing the root here blanks an already visible page on the API 19 head unit.
                // Session/readiness and the paired Bluetooth label can be refreshed in place.
                refreshStatus()
                if (page == "settings") refreshNissanBluetoothStatus()
            } else if (page == "home" || page == "settings" || page == "connection") {
                render()
            }
        }
        if (!initialLaunch && CarPlayBackgroundSession.hasSession() && page == "home" && !userNavigatedFromCarPlay) {
            openProjection()
            return
        }
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }
        }
    }
    override fun onPause() {
        uiActivityResumed = false
        uiPageProbe?.onBackground()
        UiPageDiagnostics.activityEvent("pause", javaClass.simpleName, uiPageLabel())
        userNavigatedFromCarPlay = false
        cancelKeyLearning()
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        uiPageProbe?.cancel("activity-destroyed")
        uiPageProbe = null
        handler.removeCallbacks(startupProbeFallback)
        afterNissanDefaults.clear()
        pendingNissanConnect = null
        cancelUsbPermissionSetup()
        cancelKeyLearning()
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (updateInterfaceSize(newConfig)) return
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Some head units put their own density back without any other callback.
        if (hasFocus && enforceInterfaceSize()) render()
    }

    private val isExpandedSettingsLayout: Boolean
        get() = resources.configuration.let {
            SettingsLayoutPolicy.isExpanded(it.screenWidthDp, it.screenHeightDp, it.fontScale)
        }

    private fun render() {
        if (page == "settings" && nissanAndroid44Profile && !nissanDefaultsReady) {
            if (!nissanSettingsRenderPending) {
                nissanSettingsRenderPending = true
                ensureNissanDefaults {
                    nissanSettingsRenderPending = false
                    if (!isFinishing && !isDestroyed && page == "settings") render()
                }
            }
            return
        }
        val renderStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val destination = uiPageLabel()
        val previousDestination = renderedUiDestination ?: "launch"
        val transitionReason = when {
            renderedUiDestination == null -> "initial-render"
            renderedUiDestination == destination -> "view-refresh"
            else -> "navigation"
        }
        uiPageProbe?.cancel("superseded-by-$destination")
        val createToRenderMs = if (activityCreateStartedAtNs > 0L && renderedUiDestination == null) {
            (renderStartedAtNs - activityCreateStartedAtNs).coerceAtLeast(0L) / 1_000_000L
        } else {
            -1L
        }
        val pageProbe = UiPageDiagnostics.beginPage(
            this,
            from = previousDestination,
            to = destination,
            reason = transitionReason,
            activityCreateToRenderMs = createToRenderMs,
        )
        uiPageProbe = pageProbe
        enforceInterfaceSize()
        // A pending assignment belongs to the widgets being replaced, never to another page.
        cancelKeyLearning()
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val sameDestination = renderedPage == page &&
            (page != "settings" || renderedSettingsCategory == settingsCategory)
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { sameDestination }
        // The rail is one destination even when its selected category changes.
        val keepRailPosition = renderedPage == "settings" && page == "settings"
        val previousRailScrollY = (pendingRailScrollY ?: settingsRailScroll?.scrollY)
            ?.takeIf { keepRailPosition }
        val restoreRailFocus = keepRailPosition &&
            (pendingRailFocus || settingsRailScroll?.hasFocus() == true)
        settingsRailScroll = null
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        reconnectBar = null
        readinessCard = null
        exportButton = null
        nissanBluetoothStatusButton = null
        val compact = isCompactLayout
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column()
        scroll.addView(content)
        val header = if (page == "settings") settingsHeader(compact) else standardHeader(compact)
        val root: View = if (page == "settings" && isExpandedSettingsLayout) {
            content.setPadding(0, 0, 0, dp(32))
            settingsCategoryContent(content)
            expandedSettingsShell(header, scroll)
        } else {
            if (compact) content.setPadding(dp(12), dp(10), dp(12), dp(12))
            else content.setPadding(dp(32), dp(24), dp(32), dp(32))
            content.addView(header)
            content.addView(space(if (compact) 8 else 24))
            if (page == "settings") content.addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
            })
            when (page) {
                "connection" -> connectionSetup(content)
                "settings" -> settingsCategoryContent(content)
                "about" -> about(content)
                else -> home(content)
            }
            scroll
        }
        val viewPreparedAtNs = SystemClock.elapsedRealtimeNanos()
        val setContentStartedAtNs = viewPreparedAtNs
        setContentView(root)
        val contentSetAtNs = SystemClock.elapsedRealtimeNanos()
        pageProbe.contentReady(
            root,
            viewBuildMs = (viewPreparedAtNs - renderStartedAtNs).coerceAtLeast(0L) / 1_000_000L,
            setContentViewMs = (contentSetAtNs - setContentStartedAtNs).coerceAtLeast(0L) / 1_000_000L,
        )
        if (uiActivityResumed) pageProbe.onForeground()
        renderedPage = page
        renderedSettingsCategory = settingsCategory.takeIf { page == "settings" }
        renderedUiDestination = destination
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
        val rail = settingsRailScroll
        pendingRailScrollY = previousRailScrollY.takeIf { rail != null }
        pendingRailFocus = restoreRailFocus && rail != null
        rail?.doOnLayout {
            if (settingsRailScroll !== rail) return@doOnLayout
            previousRailScrollY?.let { rail.scrollTo(0, it) }
            val destinations = rail.getChildAt(0) as ViewGroup
            val selected = (0 until destinations.childCount)
                .map(destinations::getChildAt).firstOrNull { it.isSelected }
            if (selected != null) {
                if (restoreRailFocus) selected.requestFocus()
                selected.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
            }
            pendingRailScrollY = null
            pendingRailFocus = false
        }
    }

    private fun uiPageLabel(): String = when (page) {
        "settings" -> "settings-${settingsCategory.name.lowercase(Locale.US)}"
        else -> page
    }

    private fun standardHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(if (compact) dp(24) else dp(36), if (compact) dp(24) else dp(36)))
        addView(label(getString(R.string.diplay), if (compact) 18 else 26, TEXT, true).apply {
            setPadding(if (compact) dp(8) else dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, if (compact) dp(36) else dp(56), 1f))
        if (page != "home" || !compact) {
            addView(button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
                if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                else navigateBack()
            }, LinearLayout.LayoutParams(if (compact) dp(80) else dp(130), if (compact) dp(36) else dp(56)))
        }
    }

    private fun navigateBack() {
        val returnCategory = connectionSettingsReturnCategory
        when {
            page == "connection" && returnCategory != null -> {
                page = "settings"
                settingsCategory = returnCategory
                connectionSettingsReturnCategory = null
            }
            page == "settings" && !isExpandedSettingsLayout && settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }
            else -> {
                page = "home"
                connectionSettingsReturnCategory = null
            }
        }
        render()
    }

    private fun openConnectionSetupFromSettings() {
        connectionSettingsReturnCategory = settingsCategory
        page = "connection"
        render()
    }

    private fun settingsHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(button(getString(R.string.back), false, ::navigateBack),
            LinearLayout.LayoutParams(if (compact) dp(78) else dp(112), if (compact) dp(44) else dp(52)))
        addView(label(getString(R.string.settings), if (compact) 20 else 26, TEXT, true).apply {
            setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(0, if (compact) dp(44) else dp(52), 1f))
        addView(button(getString(R.string.settings_search), false) { showSettingsSearch() },
            LinearLayout.LayoutParams(if (compact) dp(92) else dp(140), if (compact) dp(44) else dp(52)))
    }

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        if (compact) {
            val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            status = label(getString(R.string.ready_when_you_are), 16, TEXT, true).apply {
                setPadding(0, 0, 0, dp(8))
            }
            card.addView(status)
            connectButton = button(getString(R.string.connect_phone), true) {
                if (CarPlayBackgroundSession.hasSession()) openProjection()
                else connect(true)
            }
            card.addView(connectButton, matchButton(0, 44))

            val buttonRow = row().apply {
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val usbBtn = button(getString(R.string.connect_with_usb), false) { connect(false) }
            val settingsBtn = button(getString(R.string.settings), false) { page = "settings"; render() }
            buttonRow.addView(usbBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            buttonRow.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(buttonRow)

            disconnectButton = button(getString(R.string.disconnect), false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.apply { visibility = View.GONE }
            card.addView(disconnectButton, matchButton(8, 38))

            content.addView(card)
            setupError?.let { content.addView(label(it, 13, WARNING).apply { setPadding(0, dp(6), 0, 0) }) }
            return
        }

        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.your_phone_your_drive), 12, ACCENT, true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .16f
        })
        left.addView(label(getString(R.string.a_familiar_drive), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(getString(R.string.your_maps_music_and_conversations_carplay_right_here_on_yo), 19, MUTED))
        val card = card()
        card.addView(label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .12f
        })
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (carHotspotOff()) {
            card.addView(label(getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.make_diplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${getString(R.string.home_public_preview)}${version()}", 12, MUTED).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = .08f
        })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    // Only the category scrolls, so the rail keeps showing where the driver is.
    private fun expandedSettingsShell(header: View, categoryScroll: ScrollView): LinearLayout = column().apply {
        setBackgroundColor(BG)
        setPadding(dp(32), dp(24), dp(32), 0)
        addView(header)
        addView(space(24))
        addView(reconnectBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) })
        val split = row().apply { gravity = Gravity.TOP }
        // The rail scrolls on its own only when the window is too short for every destination.
        split.addView(ScrollView(this@DiPlayActivity).apply {
            addView(settingsRail())
            settingsRailScroll = this
        },
            LinearLayout.LayoutParams(dp(SettingsLayoutPolicy.railWidthDp(resources.configuration.fontScale)), -2))
        split.addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
        split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
        addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun settingsRail(): LinearLayout = column().apply {
        background = rounded(SURFACE, BORDER)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        SettingsCategory.entries.forEach { category ->
            addView(settingsRailDestination(category), matchButton(0, 52).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun settingsRailDestination(category: SettingsCategory): View {
        val selected = settingsCategory == category
        val title = settingsCategoryTitle(category)
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = focusRing(12)
            contentDescription = getString(R.string.settings_open_category, title)
            background = compatibleRipple(
                ColorStateList.valueOf(RIPPLE),
                GradientDrawable().apply {
                    setColor(if (selected) RAIL_SELECTED else Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                    if (selected) setStroke(dp(1), RAIL_SELECTED_BORDER)
                },
                null,
            )
            setPadding(dp(8), 0, dp(12), 0)
            addView(View(this@DiPlayActivity).apply {
                background = GradientDrawable().apply {
                    setColor(if (selected) ACCENT else Color.TRANSPARENT)
                    cornerRadius = dp(2).toFloat()
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) })
            addView(ImageView(this@DiPlayActivity).apply {
                setImageResource(settingsCategoryIcon(category))
                androidx.core.widget.ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(if (selected) ACCENT else TEXT))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            addView(label(title, 16, if (selected) ACCENT else TEXT, true),
                LinearLayout.LayoutParams(0, -1, 1f))
            setOnClickListener {
                settingsCategory = category
                render()
            }
        }
    }

    private fun settingsCategoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.AUDIO -> R.drawable.ic_dp_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_dp_navigation
        SettingsCategory.VEHICLE -> R.drawable.ic_dp_vehicle
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
    }

    private fun settingsCategoryTitle(category: SettingsCategory): String = getString(when (category) {
        SettingsCategory.OVERVIEW -> R.string.settings_overview
        SettingsCategory.CONNECTION -> R.string.connection
        SettingsCategory.DISPLAY -> R.string.settings_display
        SettingsCategory.AUDIO -> R.string.audio
        SettingsCategory.NAVIGATION -> R.string.settings_navigation
        SettingsCategory.VEHICLE -> R.string.settings_vehicle
        SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
        SettingsCategory.ADVANCED -> R.string.settings_advanced
    })

    private fun settingsCategoryContent(content: LinearLayout) {
        when (settingsCategory) {
            SettingsCategory.OVERVIEW -> settingsOverview(content)
            SettingsCategory.CONNECTION -> connectionSettings(content)
            SettingsCategory.DISPLAY -> displaySettings(content)
            SettingsCategory.AUDIO -> audioSettings(content)
            SettingsCategory.NAVIGATION -> navigationSettings(content)
            SettingsCategory.VEHICLE -> vehicleSettings(content)
            SettingsCategory.DIAGNOSTICS -> diagnosticsSettings(content)
            SettingsCategory.ADVANCED -> advancedSettings(content)
        }
    }

    private fun settingsOverview(content: LinearLayout) {
        val buildStartedAtNs = SystemClock.elapsedRealtimeNanos()
        settingsPageTitle(content, getString(R.string.settings_overview), getString(R.string.settings_overview_subtitle))
        val readiness = card().apply {
            setPadding(dp(20), dp(if (isExpandedSettingsLayout) 16 else 12), dp(20), dp(if (isExpandedSettingsLayout) 16 else 12))
        }
        readinessCard = readiness
        renderedReadiness = null
        refreshReadiness()
        content.addView(readiness, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        val readinessBuiltAtNs = SystemClock.elapsedRealtimeNanos()

        val destinations = listOf(
            SettingsCategory.CONNECTION to R.string.settings_connection_summary,
            SettingsCategory.DISPLAY to R.string.settings_display_summary,
            SettingsCategory.AUDIO to R.string.settings_audio_summary,
            SettingsCategory.NAVIGATION to R.string.settings_navigation_summary,
            SettingsCategory.VEHICLE to R.string.settings_vehicle_summary,
        )
        val twoColumns = isExpandedSettingsLayout && resources.configuration.let {
            SettingsLayoutPolicy.overviewHasTwoColumns(it.screenWidthDp, it.fontScale)
        }
        if (twoColumns) {
            val columns = row().apply { gravity = Gravity.TOP }
            val setup = column()
            setup.addView(settingsSectionHeading(R.string.settings_your_setup))
            destinations.forEach { item ->
                setup.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            columns.addView(setup, LinearLayout.LayoutParams(0, -2, 1f))
            columns.addView(space(14), LinearLayout.LayoutParams(dp(14), 1))
            val quick = column()
            quick.addView(settingsSectionHeading(R.string.settings_quick_settings))
            quick.addView(quickSettingsCard())
            columns.addView(quick, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(columns, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        } else {
            content.addView(settingsSectionHeading(R.string.settings_your_setup))
            destinations.forEach { item ->
                content.addView(settingsSummaryCard(item.first, getString(item.second)),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            content.addView(settingsSectionHeading(R.string.settings_quick_settings).apply { setPadding(0, dp(10), 0, dp(8)) })
            content.addView(quickSettingsCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        }

        val cardsBuiltAtNs = SystemClock.elapsedRealtimeNanos()
        content.addView(settingsUtilitiesCard(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
        })
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.OVERVIEW))
        val buildFinishedAtNs = SystemClock.elapsedRealtimeNanos()
        UiPageDiagnostics.log(
            "UI_BUILD page=settings-overview " +
                "readinessMs=${(readinessBuiltAtNs - buildStartedAtNs) / 1_000_000L} " +
                "cardsMs=${(cardsBuiltAtNs - readinessBuiltAtNs) / 1_000_000L} " +
                "footerMs=${(buildFinishedAtNs - cardsBuiltAtNs) / 1_000_000L} " +
                "totalMs=${(buildFinishedAtNs - buildStartedAtNs) / 1_000_000L} thread=main",
        )
    }

    private fun currentReadiness() = SettingsReadiness.of(
        setupError = setupError != null,
        active = CarPlayBackgroundSession.active,
        running = CarPlayBackgroundSession.hasSession(),
        wireless = AirPlayPersistence.loadWirelessEnabled(this),
        phoneChosen = DiPlayPreferences.phoneAddress(this) != null,
        hotspotSetupNeeded = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            (pendingCarHotspotSetup || hotspotError(storedSsid(), storedPassword()) != null),
    )

    private fun refreshReadiness() {
        val card = readinessCard ?: return
        val state = currentReadiness()
        if (state == renderedReadiness) return
        renderedReadiness = state
        card.removeAllViews()
        val title = getString(when (state) {
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> R.string.setup_needs_attention
            SettingsReadiness.CONNECTED -> R.string.carplay_connected
            SettingsReadiness.CONNECTING -> R.string.connecting_to_your_iphone
            SettingsReadiness.CHOOSE_IPHONE -> R.string.settings_choose_iphone_title
            SettingsReadiness.READY_WIRELESS, SettingsReadiness.READY_USB -> R.string.ready
        })
        val detail = when (state) {
            SettingsReadiness.SETUP_ERROR -> setupError.orEmpty()
            SettingsReadiness.HOTSPOT_SETUP -> getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings)
            SettingsReadiness.CONNECTED -> getString(R.string.settings_ready_connected)
            SettingsReadiness.CONNECTING -> getString(R.string.settings_connecting_description)
            SettingsReadiness.CHOOSE_IPHONE -> getString(R.string.settings_choose_iphone_description)
            SettingsReadiness.READY_WIRELESS -> getString(R.string.settings_ready_wireless, DiPlayPreferences.phoneName(this))
            SettingsReadiness.READY_USB -> getString(R.string.settings_ready_usb)
        }
        val tint = when {
            state.needsAction -> WARNING
            state == SettingsReadiness.CONNECTED -> READY
            else -> TEXT
        }
        card.addView(label(title, 22, tint, true))
        card.addView(label(detail, 15, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        when (state) {
            SettingsReadiness.CHOOSE_IPHONE -> card.addView(button(getString(R.string.settings_choose_iphone_action), true) {
                choosePhone()
            }, matchButton(12, 56))
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> card.addView(button(getString(R.string.open_connection_setup), true) {
                openConnectionSetupFromSettings()
            }, matchButton(12, 56))
            else -> Unit
        }
    }

    private fun settingsUtilitiesCard(): LinearLayout = card().apply {
        setPadding(0, 0, 0, 0)
        addView(settingsUtilityRow(
            SettingsCategory.DIAGNOSTICS,
            R.drawable.ic_dp_diagnostics,
            getString(R.string.settings_diagnostics_summary),
        ), LinearLayout.LayoutParams(-1, dp(72)))
        addView(View(this@DiPlayActivity).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        addView(settingsUtilityRow(
            SettingsCategory.ADVANCED,
            R.drawable.ic_dp_advanced,
            getString(R.string.settings_advanced_subtitle),
            warning = true,
        ), LinearLayout.LayoutParams(-1, dp(72)))
    }

    private fun settingsUtilityRow(
        category: SettingsCategory,
        icon: Int,
        description: String,
        warning: Boolean = false,
    ): LinearLayout = row().apply {
        val tint = if (warning) WARNING else ACCENT
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.settings_open_category, settingsCategoryTitle(category))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(
                android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), focusRing(12)))
        }
        setPadding(dp(16), 0, dp(16), 0)
        addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(icon)
            androidx.core.widget.ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tint))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
        addView(column().apply {
            addView(label(settingsCategoryTitle(category), 17, if (warning) WARNING else TEXT, true))
            addView(label(description, 13, MUTED).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@DiPlayActivity).apply {
            setImageResource(R.drawable.ic_dp_chevron)
            androidx.core.widget.ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(ACCENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) })
        setOnClickListener {
            settingsCategory = category
            render()
        }
    }

    private fun quickSettingsCard(): LinearLayout = card().also(::buildQuickSettings).also(::normalizeSpacing)

    private fun buildQuickSettings(card: LinearLayout): Unit = card.run {
        toggle(this, getString(R.string.connect_when_diplay_opens),
            getString(R.string.default_connection_description),
            DiPlayPreferences.autoConnect(this@DiPlayActivity)) { DiPlayPreferences.saveAutoConnect(this@DiPlayActivity, it) }
        appearanceControl(this)
        toggle(this, getString(R.string.full_screen), getString(R.string.settings_full_screen_description),
            AirPlayPersistence.loadHideTopBar(this@DiPlayActivity) && AirPlayPersistence.loadHideBottomBar(this@DiPlayActivity)) { enabled ->
            AirPlayPersistence.saveHideTopBar(this@DiPlayActivity, enabled)
            AirPlayPersistence.saveHideBottomBar(this@DiPlayActivity, enabled)
        }
        toggle(this, getString(R.string.report_location_to_iphone),
            "${getString(R.string.location_reporting_reconnects)} ${getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th)}",
            AirPlayPersistence.loadLocationReportingEnabled(this@DiPlayActivity), save = ::onLocationReportingChanged)
        Unit
    }

    private fun settingsSectionHeading(title: Int) = label(getString(title), 22, TEXT, true).apply {
        setPadding(0, 0, 0, dp(10))
    }

    private fun settingsSummaryCard(category: SettingsCategory, summary: String): LinearLayout = card().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = android.graphics.drawable.LayerDrawable(arrayOf<android.graphics.drawable.Drawable>(
                android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(RIPPLE), null, null), focusRing(12)))
        }
        contentDescription = getString(R.string.settings_open_category, settingsCategoryTitle(category))
        addView(label(settingsCategoryTitle(category), 17, TEXT, true))
        addView(label(summary, 13, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        setOnClickListener { settingsCategory = category; render() }
    }

    private fun settingsPageTitle(content: LinearLayout, title: String, subtitle: String) {
        content.addView(label(title, if (isExpandedSettingsLayout) 34 else 26, TEXT, true))
        content.addView(label(subtitle, 16, MUTED).apply { setPadding(0, dp(6), 0, dp(20)) })
    }

    private fun connectionSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.connection), getString(R.string.settings_connection_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CONNECTION))
    }

    private fun displaySettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_display), getString(R.string.settings_display_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DISPLAY))
    }

    private fun nightModeLabels() = CarPlayNightMode.entries.map { mode ->
        getString(when (mode) {
            CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
            CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
            CarPlayNightMode.DAY -> R.string.carplay_night_day
            CarPlayNightMode.NIGHT -> R.string.carplay_night_night
            CarPlayNightMode.SCHEDULE -> R.string.carplay_night_schedule
        })
    }

    private fun appearanceControl(parent: LinearLayout) {
        val modes = CarPlayNightMode.entries
        choice(parent, getString(R.string.carplay_night_mode), nightModeLabels(),
            modes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)), reconnects = false) { index ->
            AirPlayPersistence.saveCarPlayNightMode(this, modes[index])
        }
    }

    private fun audioSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.audio), getString(R.string.settings_audio_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.AUDIO))
    }

    private fun navigationSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_navigation), getString(R.string.settings_navigation_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.NAVIGATION))
    }

    private fun vehicleSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_vehicle), getString(R.string.settings_vehicle_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.VEHICLE))
        section(content, getString(R.string.settings_advanced), R.drawable.ic_dp_advanced) { card ->
            card.addView(label(getString(R.string.settings_vehicle_description), 16, MUTED))
            card.addView(button(getString(R.string.settings_open_advanced), false) {
                settingsCategory = SettingsCategory.ADVANCED
                render()
            }, matchButton(14, 56))
        }
    }

    private fun diagnosticsSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.diagnostics), getString(R.string.settings_diagnostics_summary))
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.DIAGNOSTICS))
    }

    internal data class SettingsSearchResult(val title: String, val category: SettingsCategory)

    // Renders every category into a detached view and records what the builders label.
    // render() afterwards replaces every widget reference those detached builds assigned.
    // ponytail: indexes the default state only; controls behind an expander (vehicle data) are not found.
    private fun buildSettingsSearchIndex(): List<SettingsSearchResult> {
        val selected = settingsCategory
        // Category names first, so a card that only links to a category cannot claim its name.
        val index = SettingsCategory.entries.associateByTo(linkedMapOf(), ::settingsCategoryTitle)
        try {
            for (category in SettingsCategory.entries.filter { it != SettingsCategory.OVERVIEW } + SettingsCategory.OVERVIEW) {
                settingsCategory = category
                val titles = mutableListOf(settingsCategoryTitle(category))
                searchIndexSink = titles
                settingsCategoryContent(column())
                titles.forEach { index.putIfAbsent(it, category) }
            }
        } finally {
            searchIndexSink = null
            settingsCategory = selected
        }
        render()
        return index.map { SettingsSearchResult(it.key, it.value) }
    }

    private fun searchSettings(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        return index.filter { result ->
            val haystack = "${result.title} ${settingsCategoryTitle(result.category)}".lowercase(Locale.getDefault())
            words.all(haystack::contains)
        }
    }

    private fun openSearchResult(result: SettingsSearchResult) {
        settingsCategory = result.category
        render()
        val scroll = rootScroll ?: return
        val match = descendants(scroll).filterIsInstance<TextView>().firstOrNull {
            it.text.toString() == result.title || it.text.startsWith(result.title + VALUE_SEPARATOR)
        } ?: return
        val target = generateSequence<View>(match) { it.parent as? View }.firstOrNull { it.isClickable } ?: match
        scroll.doOnLayout {
            var top = 0
            var view: View? = target
            while (view != null && view !== scroll) { top += view.top; view = view.parent as? View }
            scroll.smoothScrollTo(0, (top - dp(24)).coerceAtLeast(0))
            target.requestFocus()
            target.isPressed = true
            handler.postDelayed({ target.isPressed = false }, SEARCH_HIGHLIGHT_MILLIS)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun showSettingsSearch() {
        val index = buildSettingsSearchIndex()
        val input = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.settings_search_hint)
            // Landscape head units would otherwise cover the results with a full-screen editor.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val results = mutableListOf<SettingsSearchResult>()
        val adapter = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val list = android.widget.ListView(this).apply { this.adapter = adapter }
        val empty = label(getString(R.string.settings_search_empty), 15, MUTED).apply {
            setPadding(dp(8), dp(12), dp(8), dp(12)); visibility = View.GONE
        }
        fun update() {
            results.clear(); results += searchSettings(index, input.text.toString())
            adapter.clear()
            adapter.addAll(results.map { "${it.title} — ${settingsCategoryTitle(it.category)}" })
            empty.visibility = if (results.isEmpty() && input.text.isNotBlank()) View.VISIBLE else View.GONE
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = update()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        val body = column().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(empty)
            // Short screens keep the results above the keyboard.
            addView(list, LinearLayout.LayoutParams(-1, dp(if (resources.configuration.screenHeightDp < 600) 160 else 320)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_search))
            .setView(body)
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        list.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            openSearchResult(results[position])
        }
        input.requestFocus()
    }

    private fun advancedSettings(content: LinearLayout) {
        settingsPageTitle(content, getString(R.string.settings_advanced), getString(R.string.settings_advanced_subtitle))
        val caution = card().apply {
            addView(label(getString(R.string.settings_advanced_caution_title), 18, WARNING, true))
            addView(label(getString(R.string.settings_advanced_caution_description), 14, MUTED).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        content.addView(caution, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        renderSections(content, SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED))
    }

    private fun renderSections(
        content: LinearLayout,
        sections: Set<SettingsSection>,
    ) {
        val previous = settingsSectionFilter
        settingsSectionFilter = sections
        try {
            allSettingsSections(content)
        } finally {
            settingsSectionFilter = previous
        }
    }

    private fun allSettingsSections(content: LinearLayout) {
        filteredSection(content, SettingsSection.CARPLAY_CONTROLS,
            getString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            val gestureFingers = listOf(2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        filteredSection(content, SettingsSection.WHEEL_KEYS,
            getString(R.string.settings_wheel_keys), R.drawable.ic_dp_controls, ::wheelKeysSettings)
        filteredSection(content, SettingsSection.CONNECTION_SETUP,
            getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false, ::openConnectionSetupFromSettings), matchButton(12, 60))
            if (nissanAndroid44Profile) {
                val statusButton = button(nissanBluetoothStatusText(), false, ::openCarBluetoothSettings)
                statusButton.setOnLongClickListener { promptCarBluetoothMac(); true }
                nissanBluetoothStatusButton = statusButton
                card.addView(statusButton, matchButton(12, 60))
                card.addView(label(getString(R.string.settings_nissan_bluetooth_long_press), 14, MUTED).apply {
                    setPadding(0, dp(4), 0, 0)
                })
                refreshNissanBluetoothStatus()
            } else {
                val customMac = AirPlayPersistence.loadCustomBluetoothMac(this)
                val detectedMac = BluetoothAddressResolver.resolve(this)
                val displayValue = when {
                    !customMac.isNullOrBlank() -> customMac
                    !detectedMac.isNullOrBlank() -> getString(R.string.settings_car_bluetooth_mac_auto, detectedMac)
                    else -> getString(R.string.settings_car_bluetooth_mac_not_detected)
                }
                card.addView(button("${getString(R.string.settings_car_bluetooth_mac_title)} · $displayValue", false) {
                    promptCarBluetoothMac()
                }, matchButton(12, 60))
                card.addView(label(getString(R.string.settings_car_bluetooth_mac_subtitle), 14, MUTED).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
        filteredSection(content, SettingsSection.DIAGNOSTICS,
            getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            toggle(card, getString(R.string.settings_detailed_diagnostics_title),
                getString(R.string.settings_detailed_diagnostics_description),
                AirPlayPersistence.loadDetailedDiagnosticsEnabled(this)) {
                AirPlayPersistence.saveDetailedDiagnosticsEnabled(this, it)
            }
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                exportDiagnostics()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        filteredSection(content, SettingsSection.AUTOMATIC_CONNECTION,
            getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.default_connection_description), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            val connectionModes = DefaultConnectionMode.entries
            choice(card, getString(R.string.default_connection_mode), listOf(
                getString(R.string.default_connection_last_used),
                getString(R.string.default_connection_wireless),
                getString(R.string.default_connection_usb)
            ), connectionModes.indexOf(DiPlayPreferences.defaultConnectionMode(this)), reconnects = false) {
                DiPlayPreferences.saveDefaultConnectionMode(this, connectionModes[it])
            }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    button(
                        getString(R.string.btn_auto_apply_permissions),
                        true,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(label(getString(R.string.usb_auto_confirm_active_hint), 14, READY).apply {
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        filteredSection(content, SettingsSection.DISPLAY_AND_PERFORMANCE,
            getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            val nightModes = CarPlayNightMode.entries
            val nightMode = AirPlayPersistence.loadCarPlayNightMode(this)
            val ambientControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
            }
            val scheduleControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
            }
            choice(
                card,
                getString(R.string.carplay_night_mode),
                nightModeLabels(),
                nightModes.indexOf(nightMode),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
                ambientControls.visibility = if (nightModes[index] == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
                scheduleControls.visibility = if (nightModes[index] == CarPlayNightMode.SCHEDULE) View.VISIBLE else View.GONE
            }
            card.addView(label(getString(R.string.carplay_night_hint), 14, MUTED))
            card.addView(label(getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientControls.addView(label(getString(R.string.carplay_night_ambient_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(ambientControls)
            nightDelaySettingControl(ambientControls, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(ambientControls)
            scheduleControls.addView(label(getString(R.string.carplay_night_schedule_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            scheduleTimeControl(scheduleControls, start = true)
            scheduleTimeControl(scheduleControls, start = false)
            card.addView(scheduleControls)
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it) },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it) },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
            card.addView(button("${getString(R.string.settings_interface_size)} · ${InterfaceSize.displayName(this, InterfaceSize.preference(this))}", false) {
                InterfaceSize.showPicker(this)
            }, matchButton(12, 60))
            card.addView(label(getString(R.string.settings_interface_size_hint), 14, MUTED))
        }
        // Opt-in controls that can cost sound or video on some head units.
        filteredSection(content, SettingsSection.ADVANCED_MEDIA,
            getString(R.string.settings_advanced_media), R.drawable.ic_dp_advanced) { card ->
            if (nissanAndroid44Profile) {
                toggle(card, getString(R.string.settings_nissan_phone_key_probe),
                    getString(R.string.settings_nissan_phone_key_probe_description),
                    NissanPhoneKeyProbe.enabled(this)) {
                    if (!NissanPhoneKeyProbe.setEnabled(this, it)) {
                        toast(getString(R.string.settings_nissan_phone_key_probe_failed))
                    }
                    render()
                }
            }
            toggle(card, getString(R.string.call_echo_cancellation), getString(R.string.call_echo_cancellation_description),
                AirPlayPersistence.loadCallEchoCancellation(this)) {
                AirPlayPersistence.saveCallEchoCancellation(this, it)
                markReconnectNeeded()
            }
            toggle(card, getString(R.string.call_voice_filter), getString(R.string.call_voice_filter_description),
                AirPlayPersistence.loadCallVoiceFilter(this)) {
                AirPlayPersistence.saveCallVoiceFilter(this, it)
                markReconnectNeeded()
            }
        }
        filteredSection(content, SettingsSection.CAR_BUTTON,
            getString(R.string.car_button_in_carplay), R.drawable.ic_dp_car) { card -> carButtonCard = card; carButtonControls(card) }
        filteredSection(content, SettingsSection.AUDIO_ROUTING,
            getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            if (nissanAndroid44Profile) {
                choice(card, getString(R.string.settings_nissan_audio_mode),
                    listOf(getString(R.string.settings_nissan_audio_bluetooth),
                        getString(R.string.settings_nissan_audio_carplay)),
                    if (AirPlayPersistence.loadCarPlayAudioOutputEnabled(this)) 1 else 0,
                    reconnects = false) { selected ->
                    AirPlayPersistence.saveCarPlayAudioOutputEnabled(this, selected == 1)
                    markReconnectNeeded()
                }
                card.addView(label(getString(R.string.settings_nissan_audio_mode_description), 14, MUTED))
            } else {
                toggle(card, getString(R.string.settings_carplay_audio_output),
                    getString(R.string.settings_carplay_audio_output_description),
                    AirPlayPersistence.loadCarPlayAudioOutputEnabled(this)) {
                    AirPlayPersistence.saveCarPlayAudioOutputEnabled(this, it)
                    markReconnectNeeded()
                }
            }
            if (nissanAndroid44Profile) {
                carPlayVolumeSlider(card, R.string.settings_carplay_media_volume,
                    AirPlayPersistence.loadMediaVolumePercent(this),
                    apply = { applyCarPlayRelativeVolumes(media = it) },
                    save = { AirPlayPersistence.saveMediaVolumePercent(this, it) })
                carPlayVolumeSlider(card, R.string.settings_carplay_navigation_volume,
                    AirPlayPersistence.loadNavigationVolumePercent(this),
                    apply = { applyCarPlayRelativeVolumes(navigation = it) },
                    save = { AirPlayPersistence.saveNavigationVolumePercent(this, it) })
                nissanOemNavigationVolumeSlider(card)
                carPlayVolumeSlider(card, R.string.settings_carplay_call_volume,
                    AirPlayPersistence.loadCallVolumePercent(this),
                    apply = { applyCarPlayRelativeVolumes(call = it) },
                    save = { AirPlayPersistence.saveCallVolumePercent(this, it) })
                val callModes = com.shilapi.xcertplay.media.NissanCallStreamMode.entries
                choice(card, getString(R.string.settings_nissan_call_stream_mode),
                    listOf(getString(R.string.settings_nissan_call_stream_auto),
                        getString(R.string.settings_nissan_call_stream_media),
                        getString(R.string.settings_nissan_call_stream_voice)),
                    AirPlayPersistence.loadNissanCallStreamMode(this).ordinal,
                    reconnects = false) { selected ->
                    AirPlayPersistence.saveNissanCallStreamMode(this, callModes[selected])
                    markReconnectNeeded()
                }
                card.addView(label(getString(R.string.settings_nissan_call_stream_mode_description), 14, MUTED))
                nissanOemPhoneVolumeSlider(card)
                card.addView(label(getString(R.string.settings_carplay_volume_description), 14, MUTED))
            }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
        }
        filteredSection(content, SettingsSection.LOCATION,
            getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
        }
        filteredSection(content, SettingsSection.PERMISSIONS_AND_HELP,
            getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false, ::openCarBluetoothSettings), matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        filteredSection(content, SettingsSection.ABOUT,
            getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
        }
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
    }

    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false, ::openCarBluetoothSettings), matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc),
            getString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        pendingCarHotspotSetup = false
                        applyWirelessLink(candidate)
                    }
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity) else storedSsid())
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity) else storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(getString(R.string.usb_auto_confirm_status_on))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.usb_auto_confirm_title))
            .setMessage(getString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(getString(R.string.btn_auto_apply_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(getString(R.string.btn_open_accessibility_setting)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(getString(R.string.wheel_keys_no_settings))
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        Thread({
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> getString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> getString(R.string.adb_pairing_only)
                        else -> getString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }, "diplay-auto-permission").start()
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) getString(R.string.usb_auto_confirm_status_on) else getString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(0, 56))

        UsbPermissionSetup.snapshot(this).filterKeys { it == UsbPermissionSetup.Permission.ACCESSIBILITY }.forEach { (permission, granted) ->
            val title = getString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                else -> return@forEach
            })
            body.addView(label("$title: ${getString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(getString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x22FFFFFF)
        })
        body.addView(button(getString(R.string.copy_command), false) {
            val clipboard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getSystemService(android.content.ClipboardManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            }
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("DiPlay ADB Command", adbCmd)
            )
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_and_connection_help))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close)) { _, _ -> render() }
            .show()
    }

    private fun wheelKeysSettings(card: LinearLayout) {
        siriKeyControls(card)
    }

    /** A wheel key that opens Siri on any car whose key reaches the key service. */
    private fun siriKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_siri_key), getString(R.string.wheel_siri_key_description),
            WheelKeySettings.siriKey(this)) {
            WheelKeySettings.setSiriKey(this, it)
            render()
        }
        if (!WheelKeySettings.siriKey(this)) return
        wheelKeyAssignButton(card)
    }

    private fun wheelKeyAssignButton(card: LinearLayout) {
        val name = getString(R.string.wheel_key_role_siri)
        fun current(key: WheelKey? = WheelKeySettings.key(this)) =
            getString(R.string.wheel_key_assign, name, key?.toString() ?: getString(R.string.wheel_key_none))
        lateinit var assign: android.widget.Button
        assign = button(current(), false) {
            val cancelled = { runOnUiThread { assign.text = current() } }
            val started = learnInWindow(cancelled) { assign.text = current(it) }
            if (started) assign.text = getString(R.string.wheel_key_press, name)
        }
        card.addView(assign, matchButton(10, 56))
    }

    private class WindowKeyLearning(
        val cancelled: () -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private fun learnInWindow(cancelled: () -> Unit, done: (WheelKey) -> Unit): Boolean {
        cancelKeyLearning()
        windowLearning = WindowKeyLearning(cancelled, done)
        handler.postDelayed(endWindowLearning, 10_000L)
        return true
    }

    private fun cancelKeyLearning() {
        handler.removeCallbacks(endWindowLearning)
        windowLearning?.cancelled?.invoke()
        windowLearning = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        if (nissanAndroid44Profile && event.repeatCount == 0) {
            AsyncDiagnosticLog.append(hardwareDiagnosticLog,
                "Hardware key: settings action=${event.action} code=${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "scan=${event.scanCode} device=${event.deviceId} source=${event.source}")
        }
        val action = windowLearningPresses.filter(
            Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            val learning = windowLearning
            if (learning == null || event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelKeyPresses.Action.PASS
            if (inCall(this) || !WheelKeySettings.siriKey(this)) {
                cancelKeyLearning()
                return@filter WheelKeyPresses.Action.PASS
            }
            handler.removeCallbacks(endWindowLearning)
            windowLearning = null
            val key = WheelKey.of(event)
            WheelKeySettings.assignSiriKey(this, key)
            Log.i("DiPlay-WheelKeys", "Siri key is now $key (learnt in settings)")
            learning.done(key)
            WheelKeyPresses.Action.CONSUME
        }
        return action != WheelKeyPresses.Action.PASS || super.dispatchKeyEvent(event)
    }

    private fun hasPreciseLocation() =
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // Saved without dropping CarPlay; the reconnect bar lets the driver choose when.
    private fun markReconnectNeeded() {
        CarPlayBackgroundSession.snapshot()?.controller?.let(PendingReconnect::mark)
        refreshReconnectBar()
    }

    private fun refreshReconnectBar() {
        reconnectBar?.visibility =
            if (PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) View.VISIBLE else View.GONE
    }

    private fun reconnectBar(): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(RAIL_SELECTED, RAIL_SELECTED_BORDER)
        setPadding(dp(20), dp(10), dp(12), dp(10))
        addView(label(getString(R.string.settings_reconnect_pending), 16, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        addView(button(getString(R.string.settings_reconnect_now), true) {
            // No clear(): if connect() stops early (hotspot off, a permission prompt), the change is still pending.
            reconnectIfRunning()
        }, LinearLayout.LayoutParams(-2, dp(52)).apply { marginStart = dp(12) })
        reconnectBar = this
        refreshReconnectBar()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            fields.addView(label(getString(R.string.ambient_light_threshold_value), 16, MUTED))
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun carButtonControls(parent: LinearLayout) {
        val custom = AirPlayPersistence.loadCustomAirPlayIconBitmap(this)
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageBitmap(custom ?: AirPlayPersistence.defaultAirPlayIconBitmap(this@DiPlayActivity))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            if (Build.VERSION.SDK_INT >= 21) clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(getString(R.string.car_button_icon), 18, TEXT, true))
        text.addView(label(getString(if (custom != null) R.string.car_button_icon_custom else R.string.default_icon), 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)
        parent.addView(button(getString(R.string.choose_image), false) {
            launchCarButtonImagePicker(
                openDocument = { iconDocumentPicker.launch(arrayOf("image/*")) },
                getContent = { iconPicker.launch("image/*") },
                documentPickerIsSystem = documentPickerIsSystem(),
            ).onFailure { toast(getString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(10, 60))
        if (custom != null) parent.addView(button(getString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${getString(R.string.car_button_name)} · $name", false) {
            textInput(getString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(getString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        markReconnectNeeded()
        if (CarPlayBackgroundSession.hasSession()) toast(getString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
        normalizeSpacing(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun scheduleTimeControl(parent: LinearLayout, start: Boolean) {
        val title = getString(if (start) R.string.carplay_night_start else R.string.carplay_night_end)
        fun labelFor(minute: Int) = "$title · ${String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)}"
        val initial = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val control = button(labelFor(if (start) initial.startMinute else initial.endMinute), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadCarPlayNightSchedule(this)
            val selected = if (start) current.startMinute else current.endMinute
            TimePickerDialog(this, { _, hour, minute ->
                val updatedMinute = hour * 60 + minute
                val other = if (start) current.endMinute else current.startMinute
                if (updatedMinute == other) {
                    toast(getString(R.string.carplay_night_schedule_same_time))
                } else {
                    val updated = if (start) current.copy(startMinute = updatedMinute)
                        else current.copy(endMinute = updatedMinute)
                    AirPlayPersistence.saveCarPlayNightSchedule(this, updated)
                    control.text = labelFor(updatedMinute)
                }
            }, selected / 60, selected % 60, true).show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun connect(wireless: Boolean) {
        if (nissanAndroid44Profile && !nissanDefaultsReady) {
            pendingNissanConnect = wireless
            if (afterNissanDefaults.none { it === pendingNissanConnectAction }) {
                ensureNissanDefaults(pendingNissanConnectAction)
            }
            return
        }
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        // OEM Android 8 head units can report hotspot state=false even while SoftAP is active.
        // Always let the runtime probe the real hotspot interface instead of blocking here.
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    data class DiscoveredBluetoothDevice(
        val address: String,
        val name: String,
        val isConnected: Boolean,
    )

    private val bluetoothExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun nissanBluetoothStatusText(): String {
        val value = nissanBluetoothStatusDevice?.let { (name, connected) ->
            if (connected) "$name · ${getString(R.string.bluetooth_device_connected)}"
            else getString(R.string.settings_nissan_bluetooth_paired, name)
        } ?: getString(if (nissanBluetoothStatusLoaded) R.string.settings_nissan_bluetooth_none
            else R.string.settings_nissan_bluetooth_checking)
        return "${getString(R.string.settings_nissan_bluetooth_title)} · $value"
    }

    private fun refreshNissanBluetoothStatus() {
        if (!nissanAndroid44Profile || !nissanBluetoothStatusNeedsRefresh) return
        nissanBluetoothStatusNeedsRefresh = false
        Thread({
            val devices = runCatching {
                fetchSystemBluetoothDevices(BluetoothCompat.getAdapter(applicationContext))
            }.onFailure { Log.w("DiPlayBluetooth", "Could not read current Nissan pairing", it) }
                .getOrDefault(emptyList())
            val selectedAddress = DiPlayPreferences.phoneAddress(applicationContext)
            val shown = devices.firstOrNull { it.isConnected }
                ?: devices.firstOrNull { it.address.equals(selectedAddress, ignoreCase = true) }
                ?: devices.firstOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                nissanBluetoothStatusLoaded = true
                nissanBluetoothStatusDevice = shown?.let { it.name to it.isConnected }
                nissanBluetoothStatusButton?.text = nissanBluetoothStatusText()
            }
        }, "diplay-nissan-bluetooth-status").apply { isDaemon = true; start() }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = BluetoothCompat.getAdapter(this)
        if (adapter != null && !adapter.isEnabled) {
            runCatching { adapter.enable() }
        }

        bluetoothExecutor.execute {
            val devices = fetchSystemBluetoothDevices(adapter)
            runOnUiThread {
                if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@runOnUiThread
                if (devices.isNotEmpty()) {
                    showChoosePhoneDialog(devices)
                } else {
                    showNoPairedPhonesDialog()
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fetchSystemBluetoothDevices(adapter: BluetoothAdapter?): List<DiscoveredBluetoothDevice> {
        val result = LinkedHashMap<String, DiscoveredBluetoothDevice>()

        // Nissan's ROM may keep pairing records outside Android's public BluetoothAdapter.
        // Read the vendor pairing list first, then merge in any devices visible to Android.
        val vendorBluetooth = CarPlayBluetoothManager.get(this)
        val vendorDevices = runCatching { vendorBluetooth.getPairedDevices() }
            .onFailure { Log.w("DiPlayBluetooth", "Could not read Nissan paired-device list", it) }
            .getOrDefault(emptyList())
        for (device in vendorDevices) {
            val addr = device.address.uppercase(java.util.Locale.US)
            if (!BluetoothAdapter.checkBluetoothAddress(addr)) continue
            val name = device.name.takeIf { it.isNotBlank() } ?: getString(R.string.paired_device)
            result[addr] = DiscoveredBluetoothDevice(addr, name, device.isConnected)
        }

        if (adapter != null) {
            // Bonded devices from Android's public adapter.
            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            for (device in bonded) {
                val addr = device.address?.uppercase(java.util.Locale.US) ?: continue
                val connected = isBluetoothDeviceConnected(device)
                val name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.paired_device)
                val previous = result[addr]
                result[addr] = DiscoveredBluetoothDevice(addr, name, connected || previous?.isConnected == true)
            }

            // Query GATT connected devices via BluetoothManager if available.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val gattDevices = BluetoothCompat.getConnectedGattDevices(this)
                for (device in gattDevices) {
                    val addr = device.address?.uppercase(java.util.Locale.US) ?: continue
                    val existing = result[addr]
                    val name = device.name?.takeIf { it.isNotBlank() }
                        ?: existing?.name
                        ?: getString(R.string.paired_device)
                    result[addr] = DiscoveredBluetoothDevice(addr, name, isConnected = true)
                }
            }

            // Query A2DP & HEADSET profile proxies for connected devices.
            runCatching {
                val profileConnected = queryConnectedProfileDevices(adapter)
                for (device in profileConnected) {
                    val addr = device.address?.uppercase(java.util.Locale.US) ?: continue
                    val existing = result[addr]
                    val name = device.name?.takeIf { it.isNotBlank() }
                        ?: existing?.name
                        ?: getString(R.string.paired_device)
                    result[addr] = DiscoveredBluetoothDevice(addr, name, isConnected = true)
                }
            }
        }

        return result.values.sortedWith(
            compareByDescending<DiscoveredBluetoothDevice> { it.isConnected }
                .thenByDescending { it.name.contains("iPhone", ignoreCase = true) }
                .thenBy { it.name }
        )
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

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

        val hasHeadset = runCatching { adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET) }.getOrDefault(false)
        if (!hasHeadset) latch.countDown()

        val hasA2dp = runCatching { adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP) }.getOrDefault(false)
        if (!hasA2dp) latch.countDown()

        runCatching { latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return synchronized(connected) { connected.toSet() }
    }

    private fun showChoosePhoneDialog(devices: List<DiscoveredBluetoothDevice>) {
        val items = devices.map { device ->
            val status = if (device.isConnected) " · ${getString(R.string.bluetooth_device_connected)}" else ""
            val duplicate = devices.count { it.name == device.name } > 1
            val disambiguation = if (duplicate) " (${device.address.takeLast(5)})" else ""
            "${device.name}$disambiguation$status"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_your_iphone))
            .setItems(items) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name)
                nissanBluetoothStatusNeedsRefresh = true
                val start = pendingWireless
                pendingWireless = false
                render()
                if (start) connect(true)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }
            .show()
    }

    private fun showNoPairedPhonesDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_your_iphone))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ ->
                openCarBluetoothSettings()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }
            .show()
    }

    private fun promptCarBluetoothMac() {
        val input = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            setText(AirPlayPersistence.loadCustomBluetoothMac(this@DiPlayActivity).orEmpty())
            setSingleLine()
            setSelection(text.length)
        }
        val dialogView = column().apply {
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(label(getString(R.string.settings_car_bluetooth_mac_dialog_message), 14, MUTED))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_car_bluetooth_mac_dialog_title))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isBlank()) {
                    AirPlayPersistence.saveCustomBluetoothMac(this, null)
                    markReconnectNeeded()
                    render()
                } else {
                    val normalized = BluetoothAddressResolver.normalize(entered)
                    if (normalized != null) {
                        AirPlayPersistence.saveCustomBluetoothMac(this, normalized)
                        markReconnectNeeded()
                        render()
                    } else {
                        toast(getString(R.string.settings_car_bluetooth_mac_invalid))
                    }
                }
            }
            .setNeutralButton(getString(R.string.settings_car_bluetooth_mac_clear)) { _, _ ->
                AirPlayPersistence.saveCustomBluetoothMac(this, null)
                markReconnectNeeded()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    @SuppressLint("MissingPermission")
    private fun resetWirelessGroup() {
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? android.net.wifi.p2p.WifiP2pManager
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { closeP2pChannel(channel); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { closeP2pChannel(channel); if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeP2pChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { closeP2pChannel(channel); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            closeP2pChannel(channel); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun closeP2pChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= 27) channel.close()
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        val statusText = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        status?.let { if (it.text.toString() != statusText) it.text = statusText }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.let {
            val enabled = setupError == null
            if (it.isEnabled != enabled) it.isEnabled = enabled
        }
        refreshReconnectBar()
        refreshReadiness()
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private var preferredReportRoot: File? = null

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // Use our own USB volume picker: KitKat DocumentsUI often hides vendor mounts.
            val choices = DiagnosticExportStore.availableUsbVolumes(applicationContext)
            if (choices.isEmpty()) {
                AlertDialog.Builder(this).setMessage(getString(R.string.report_no_usb_drive))
                    .setPositiveButton(getString(R.string.save_diagnostic_report)) { _, _ -> exportDiagnostics() }
                    .setNegativeButton(getString(R.string.done), null).show()
            } else {
                val labels = arrayOf(getString(R.string.report_auto_save_locations)) +
                    choices.map { getString(R.string.report_usb_location, it.absolutePath) }
                AlertDialog.Builder(this).setTitle(getString(R.string.choose_save_location))
                    .setItems(labels) { _, selected ->
                        preferredReportRoot = choices.getOrNull(selected - 1)
                        exportDiagnostics()
                    }.setNegativeButton(getString(R.string.done), null).show()
            }
            return
        }
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        val requestedRoot = preferredReportRoot
        preferredReportRoot = null
        Thread({
            val result = runCatching {
                val sessionLogFlushed = AsyncDiagnosticLog.awaitIdle(5_000L)
                val report = buildString {
                    appendLine("DiPlay ${version()} · diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Detailed playback logs: ${AirPlayPersistence.loadDetailedDiagnosticsEnabled(appContext)}; " +
                        "sessionLogFlushed=$sessionLogFlushed queueDrops=${AsyncDiagnosticLog.droppedCount}")
                    appendLine("--- Legacy USB / wireless / TLS provider diagnostics ---")
                    append(LegacyConnectionProbe.report(appContext))
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    val setupSummary = if (setupError == null) {
                        "authentication check deferred until connection startup"
                    } else {
                        "authentication unavailable"
                    }
                    appendLine("CarPlay setup: $setupSummary")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
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
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report, requestedRoot)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath) +
                                if (savedReport.copyPaths.isEmpty()) "\n" + getString(R.string.report_usb_copy_unavailable) else "\n" + getString(R.string.report_usb_copy_paths, savedReport.copyPaths.joinToString("\n"))
                            uri == null -> "Downloads/DiPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun openCarBluetoothSettings() {
        if (nissanAndroid44Profile) {
            val nissanSettings = Intent().setClassName(
                "com.hsae.d531mc.systemsetting",
                "com.hsae.d531mc.systemsetting.connect.activity.BlueActivity",
            )
            runCatching { startActivity(nissanSettings) }
                .onFailure {
                    Log.w("DiPlayBluetooth", "Could not open Nissan Bluetooth settings", it)
                    toast(getString(R.string.open_this_setting_from_your_car_s_settings_app))
                }
        } else {
            openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        filteredSection(content, SettingsSection.LANGUAGE,
            getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        searchIndexSink?.add(title)
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageDrawable(AppCompatResources.getDrawable(this@DiPlayActivity, icon)?.let {
                DrawableCompat.wrap(it.mutate()).apply { DrawableCompat.setTint(this, ACCENT) }
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        if (page == "settings") normalizeSpacing(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun filteredSection(
        parent: LinearLayout,
        key: SettingsSection,
        title: String,
        icon: Int? = null,
        build: (LinearLayout) -> Unit,
    ) {
        if (settingsSectionFilter?.contains(key) == false) return
        section(parent, title, icon, build)
    }
    // Routing changes can affect the active CarPlay session when its host resumes.
    // Ask before saving them instead of promising to defer only part of the change.
    private fun reconnectingToggle(parent: LinearLayout, title: String, description: String,
        value: Boolean, save: (Boolean) -> Unit) {
        toggle(parent, title, description, value) { selected ->
            if (!CarPlayBackgroundSession.hasSession()) {
                save(selected)
            } else {
                AlertDialog.Builder(this).setTitle(title).setMessage(description)
                    .setPositiveButton(R.string.apply_and_reconnect) { _, _ ->
                        save(selected)
                        reconnectIfRunning()
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> render() }
                    .setOnCancelListener { render() }
                    .show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        searchIndexSink?.add(title)
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = SettingsTheme.CARD,
            contentDescription = title,
            enabled = enabled,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }

    private fun applyCarPlayRelativeVolumes(media: Int? = null, navigation: Int? = null, call: Int? = null) {
        CarPlayBackgroundSession.snapshot()?.sink?.updateRelativeVolumes(
            media ?: AirPlayPersistence.loadMediaVolumePercent(this),
            navigation ?: AirPlayPersistence.loadNavigationVolumePercent(this),
            call ?: AirPlayPersistence.loadCallVolumePercent(this),
        )
    }

    private fun carPlayVolumeSlider(parent: LinearLayout, titleId: Int, initial: Int,
        apply: (Int) -> Unit, save: (Int) -> Unit) {
        val title = getString(titleId)
        var dragging = false
        val commit: (Int) -> Unit = { percent ->
            save(percent)
            AsyncDiagnosticLog.append(hardwareDiagnosticLog,
                "Audio volume: $title percent=$percent active=${CarPlayBackgroundSession.active}")
        }
        searchIndexSink?.add(title)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 18, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label("${initial.coerceIn(0, 100)}%", 18, ACCENT)
        header.addView(value)
        parent.addView(header)
        val slider = SeekBar(this).apply {
            max = 100
            progress = initial.coerceIn(0, 100)
            contentDescription = title
            isFocusableInTouchMode = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = "$progress%"
                    if (fromUser) {
                        apply(progress)
                        if (!dragging) commit(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    dragging = true
                    seekBar.requestFocus()
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragging = false
                    commit(seekBar.progress)
                }
            })
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    progress = (progress + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5).coerceIn(0, 100)
                    apply(progress)
                    commit(progress)
                }
                true
            }
        }
        parent.addView(slider, LinearLayout.LayoutParams(-1, -2))
        parent.addView(space(12))
    }

    private fun nissanOemNavigationVolumeSlider(parent: LinearLayout) = nissanOemVolumeSlider(
        parent, R.string.settings_nissan_navigation_hardware_volume,
        R.string.settings_nissan_navigation_hardware_volume_description,
        NissanOemNavigationVolume.MAX_LEVEL, "navigation",
        NissanOemNavigationVolume::readAsync, NissanOemNavigationVolume::setAsync,
    )

    private fun nissanOemPhoneVolumeSlider(parent: LinearLayout) = nissanOemVolumeSlider(
        parent, R.string.settings_nissan_phone_hardware_volume,
        R.string.settings_nissan_phone_hardware_volume_description,
        NissanOemPhoneVolume.MAX_LEVEL, "phone",
        NissanOemPhoneVolume::readAsync, NissanOemPhoneVolume::setAsync,
    )

    private fun nissanOemVolumeSlider(
        parent: LinearLayout,
        titleId: Int,
        descriptionId: Int,
        maxLevel: Int,
        logType: String,
        readAsync: (((Int?, String?) -> Unit) -> Unit),
        setAsync: ((Int, (Int?, String?) -> Unit) -> Unit),
    ) {
        val title = getString(titleId)
        searchIndexSink?.add(title)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 18, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label(getString(R.string.settings_nissan_navigation_hardware_volume_loading), 18, ACCENT)
        header.addView(value)
        parent.addView(header)
        var revision = 0
        val slider = SeekBar(this).apply {
            max = maxLevel
            isEnabled = false
            isFocusableInTouchMode = true
            contentDescription = title
        }
        fun commit(level: Int) {
            val requested = level.coerceIn(0, maxLevel)
            revision++
            val thisRevision = revision
            value.text = "$requested/$maxLevel"
            setAsync(requested) { actual, error ->
                AsyncDiagnosticLog.append(hardwareDiagnosticLog,
                    "OEM $logType volume: requested=$requested actual=${actual ?: "unknown"} error=${error ?: "none"}")
                handler.post {
                    if (isFinishing || isDestroyed || thisRevision != revision) return@post
                    if (actual == null) {
                        value.text = getString(R.string.settings_nissan_navigation_hardware_volume_unavailable)
                    } else {
                        slider.progress = actual.coerceIn(0, maxLevel)
                        value.text = "${slider.progress}/$maxLevel"
                    }
                }
            }
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) value.text = "$progress/$maxLevel"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { seekBar.requestFocus() }
            override fun onStopTrackingTouch(seekBar: SeekBar) { commit(seekBar.progress) }
        })
        slider.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                slider.progress = (slider.progress + if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                    .coerceIn(0, maxLevel)
                commit(slider.progress)
            }
            true
        }
        parent.addView(slider, LinearLayout.LayoutParams(-1, -2))
        parent.addView(label(getString(descriptionId), 14, MUTED))
        parent.addView(space(12))
        readAsync { current, error ->
            AsyncDiagnosticLog.append(hardwareDiagnosticLog,
                "OEM $logType volume: initial=${current ?: "unknown"} error=${error ?: "none"}")
            handler.post {
                if (isFinishing || isDestroyed || revision != 0) return@post
                if (current == null) {
                    value.text = getString(R.string.settings_nissan_navigation_hardware_volume_unavailable)
                } else {
                    slider.progress = current.coerceIn(0, maxLevel)
                    value.text = "${slider.progress}/$maxLevel"
                    slider.isEnabled = true
                }
            }
        }
    }
    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = SettingButton(this).apply {
        isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = compatibleRipple(ColorStateList.valueOf(RIPPLE), rounded(if (primary) ACCENT else BUTTON, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56)
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        compoundDrawablePadding = dp(12)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = focusRing()
        text = title
        searchIndexSink?.add(title.substringBefore(VALUE_SEPARATOR))
        setOnClickListener { click() }
    }

    // For an action whose label happens to contain the separator, such as "Turn on … · ADB".
    private fun actionButton(title: String, primary: Boolean, click: () -> Unit) =
        button(title, primary, click).apply { action = true; text = title }

    /**
     * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
     * start-aligned, muted value, chevron. Any other text stays a centred action button.
     * The plain text is unchanged, so callers and tests keep matching "Title · Value".
     */
    private class SettingButton(context: android.content.Context) : Button(context) {
        var action = false

        override fun setText(text: CharSequence?, type: BufferType?) {
            val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
            if (text == null || split <= 0) {
                super.setText(text, type)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(null, null, null, null)
                return
            }
            val styled = android.text.SpannableString(text).apply {
                setSpan(android.text.style.ForegroundColorSpan(MUTED), split, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            super.setText(styled, BufferType.SPANNABLE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val chevron = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_dp_chevron)?.mutate()?.apply {
                androidx.core.graphics.drawable.DrawableCompat.setTint(this, ACCENT)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
        }
    }
    // Remote and D-pad users need to see where they are; touch mode never shows it.
    private fun focusRing(radiusDp: Int = 20) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(3), ACCENT)
        })
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    private fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is Switch }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = container.getChildAt(i + 1)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = dp(CONTROL_HEIGHT_DP); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                // Toggle rows carried their own vertical padding, which doubled the gap after a button.
                isToggleRow(child) -> {
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }
                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    // Lines of one note stay together; the gap after the last line closes the group.
                    lp.bottomMargin = dp(when {
                        isNote(next) -> NOTE_LINE_GAP_DP
                        noteFollowsButton -> GROUP_GAP_DP
                        else -> CONTROL_GAP_DP
                    })
                }
                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }
                // Any other row (an icon preview, a status line) gets the same gap; the card heading keeps its padding.
                i > 0 || !isCard -> { lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP) }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        // A nested group keeps its trailing gap so it does not touch the control after it.
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let { it.bottomMargin = 0; last.layoutParams = it }
        }
    }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    companion object {
        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")

        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        // One step lighter than a card, so a button reads as a button even where its 1 px border is faint.
        private val BUTTON = Color.rgb(31, 43, 61)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = com.shilapi.xcertplay.settings.SettingsTheme.CARD.accent
        private val RAIL_SELECTED = Color.rgb(24, 54, 92)
        private val RAIL_SELECTED_BORDER = Color.rgb(42, 82, 130)
        private val TEXT = com.shilapi.xcertplay.settings.SettingsTheme.CARD.textPrimary
        private val MUTED = com.shilapi.xcertplay.settings.SettingsTheme.CARD.textSecondary
        private val WARNING = Color.rgb(255, 196, 128)
        private val READY = Color.rgb(127, 205, 154)
        private const val RIPPLE = 0x336F9FD9
        private const val VALUE_SEPARATOR = " · "
        private const val SEARCH_HIGHLIGHT_MILLIS = 900L
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = 60
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        private const val SETTINGS_BLOCK_GAP_DP = 18
    }
}
