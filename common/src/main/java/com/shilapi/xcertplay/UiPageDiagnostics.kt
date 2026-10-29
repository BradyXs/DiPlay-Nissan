package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Lightweight timing log for app page navigation and first visible root draws. */
internal object UiPageDiagnostics {
    private data class Entry(
        val message: String,
        val timestampMillis: Long,
        val uptimeMillis: Long,
    )

    private val lock = Any()
    private val nextId = AtomicLong(0)
    private val writer = BoundedDiagnosticWriter<Entry>(maxPending = 128, write = ::writeEntry)
    @Volatile private var currentFile: File? = null
    @Volatile private var previousFile: File? = null
    @Volatile private var processStartedAtNs = 0L
    @Volatile private var header = ""
    // Read and written only by the single diagnostic writer thread.
    private var diskInitialized = false

    fun initialize(context: Context) {
        synchronized(lock) {
            if (currentFile != null) return
            val directory = File(context.applicationContext.filesDir, "logs")
            currentFile = File(directory, CURRENT_LOG_NAME)
            previousFile = File(directory, PREVIOUS_LOG_NAME)
            processStartedAtNs = SystemClock.elapsedRealtimeNanos()
            header = "UI page diagnostics started " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        }
        log("UI_PROCESS start")
    }

    fun activityEvent(event: String, activity: String, page: String) {
        log("UI_ACTIVITY event=${token(event)} activity=${token(activity)} page=${token(page)}")
    }

    fun beginPage(
        activity: Activity,
        from: String,
        to: String,
        reason: String,
        activityCreateToRenderMs: Long = -1L,
    ): UiPageDrawProbe {
        initialize(activity.applicationContext)
        val id = nextId.incrementAndGet()
        val startedAtNs = SystemClock.elapsedRealtimeNanos()
        log(
            "UI_PAGE start id=$id activity=${token(activity.javaClass.simpleName)} " +
                "from=${token(from)} to=${token(to)} reason=${token(reason)} " +
                "activityCreateToRenderMs=$activityCreateToRenderMs",
        )
        return UiPageDrawProbe(
            id = id,
            activityName = activity.javaClass.simpleName,
            from = from,
            page = to,
            reason = reason,
            activityCreateToRenderMs = activityCreateToRenderMs,
            startedAtNs = startedAtNs,
            report = ::log,
        )
    }

    fun log(message: String) {
        val target = currentFile ?: return
        val safe = DiagnosticRedactor.redact(message) ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val startedAtNs = processStartedAtNs
        val uptimeMs = if (startedAtNs > 0L) (nowNs - startedAtNs).coerceAtLeast(0L) / 1_000_000L else 0L
        writer.enqueue(Entry(safe, System.currentTimeMillis(), uptimeMs))
    }

    fun report(context: Context): String {
        initialize(context.applicationContext)
        writer.awaitIdle(5_000L)
        val files = listOfNotNull(previousFile, currentFile)
        return buildString {
            files.forEach { file ->
                if (!file.isFile) return@forEach
                appendLine("--- ${file.name} ---")
                runCatching {
                    file.useLines { lines ->
                        lines.forEach { line -> DiagnosticRedactor.redact(line)?.let(::appendLine) }
                    }
                }.onFailure { appendLine("UI page log read failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    fun processElapsedMillis(): Long {
        val startedAtNs = processStartedAtNs
        return if (startedAtNs > 0L) {
            (SystemClock.elapsedRealtimeNanos() - startedAtNs).coerceAtLeast(0L) / 1_000_000L
        } else {
            -1L
        }
    }

    private fun writeEntry(entry: Entry) {
        val file = currentFile ?: return
        val archive = previousFile
        runCatching {
            synchronized(lock) {
                file.parentFile?.mkdirs()
                if (!diskInitialized) {
                    if (file.isFile && file.length() > 0L && archive != null) {
                        file.copyTo(archive, overwrite = true)
                    }
                    file.writeText("$header\n", Charsets.UTF_8)
                    diskInitialized = true
                }
                if (file.length() > MAX_BYTES) {
                    if (archive != null) file.copyTo(archive, overwrite = true)
                    file.writeText("$header (continued)\n", Charsets.UTF_8)
                }
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMillis))
                file.appendText("$time  uptimeMs=${entry.uptimeMillis} ${entry.message}\n", Charsets.UTF_8)
            }
        }
    }

    private fun token(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.:/-]"), "_").take(96).ifBlank { "unknown" }

    private const val CURRENT_LOG_NAME = "ui-pages.log"
    private const val PREVIOUS_LOG_NAME = "ui-pages.previous.log"
    private const val MAX_BYTES = 256 * 1024L
}

/** Measures a page's view preparation, layout, pre-draw and first draw without sampling pixels. */
internal class UiPageDrawProbe(
    private val id: Long,
    private val activityName: String,
    private val from: String,
    private val page: String,
    private val reason: String,
    private val activityCreateToRenderMs: Long,
    private val startedAtNs: Long,
    private val report: (String) -> Unit,
) : ViewTreeObserver.OnGlobalLayoutListener,
    ViewTreeObserver.OnPreDrawListener,
    ViewTreeObserver.OnDrawListener,
    View.OnAttachStateChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var foreground = false
    private var foregroundStartedAtNs = 0L
    private var foregroundElapsedNs = 0L
    private var firstLayout = false
    private var firstPreDraw = false
    private var firstDraw = false
    private var blackSuspectLogged = false
    private var blackStillLogged = false
    private var missingDrawCallbackLogged = false
    private var finished = false
    private var viewTreeObserver: ViewTreeObserver? = null

    private val firstBlackCheck = Runnable { reportBlackThresholds() }
    private val sustainedBlackCheck = Runnable { reportBlackThresholds() }

    fun contentReady(view: View, viewBuildMs: Long, setContentViewMs: Long) {
        if (finished) return
        root = view
        report(
            "UI_PAGE content_ready id=$id activity=${activityName.token()} page=${page.token()} " +
                "viewBuildMs=$viewBuildMs setContentViewMs=$setContentViewMs " +
                "root=${view.javaClass.simpleName} size=${view.width}x${view.height}",
        )
        view.addOnAttachStateChangeListener(this)
        attachObservers(view.viewTreeObserver)
        reportBlackThresholds()
    }

    fun onForeground() {
        if (finished || foreground) return
        root?.let { attachObservers(it.viewTreeObserver) }
        foreground = true
        foregroundStartedAtNs = SystemClock.elapsedRealtimeNanos()
        report("UI_PAGE foreground id=$id activity=${activityName.token()} page=${page.token()}")
        reportBlackThresholds()
        handler.removeCallbacks(firstBlackCheck)
        handler.removeCallbacks(sustainedBlackCheck)
        handler.postDelayed(firstBlackCheck, BLACK_SUSPECT_MS)
        handler.postDelayed(sustainedBlackCheck, BLACK_STILL_MS)
    }

    fun onBackground() {
        if (!foreground || finished) return
        foregroundElapsedNs += SystemClock.elapsedRealtimeNanos() - foregroundStartedAtNs
        foreground = false
        handler.removeCallbacks(firstBlackCheck)
        handler.removeCallbacks(sustainedBlackCheck)
        report(
            "UI_PAGE background id=$id activity=${activityName.token()} page=${page.token()} " +
                "visibleMs=${visibleElapsedNs() / 1_000_000L} firstDraw=$firstDraw",
        )
    }

    fun cancel(reason: String) {
        if (finished) return
        report(
            "UI_PAGE incomplete id=$id activity=${activityName.token()} page=${page.token()} " +
                "reason=${reason.token()} visibleMs=${visibleElapsedNs() / 1_000_000L} " +
                "layout=$firstLayout preDraw=$firstPreDraw firstDraw=$firstDraw",
        )
        finished = true
        detach()
    }

    /** MediaCodec released the first frame to SurfaceView; actual panel presentation is not observable here. */
    fun onVideoFrameReleased() {
        if (finished || page != "carplay-host") return
        val visibleMs = visibleElapsedNs() / 1_000_000L
        report(
            "UI_PAGE first_video_release id=$id activity=${activityName.token()} page=${page.token()} " +
                "elapsedMs=${elapsedMs()} visibleMs=$visibleMs rootDraw=$firstDraw " +
                "surfacePresentConfirmed=false",
        )
        if (blackSuspectLogged) {
            report("UI_VIDEO_WAIT recovered id=$id visibleMs=$visibleMs stage=first-video-release")
        }
        finished = true
        detach()
    }

    override fun onGlobalLayout() {
        if (finished || firstLayout) return
        firstLayout = true
        val view = root
        report(
            "UI_PAGE first_layout id=$id elapsedMs=${elapsedMs()} " +
                "size=${view?.width ?: 0}x${view?.height ?: 0}",
        )
        detachGlobalLayoutListener()
    }

    override fun onViewAttachedToWindow(view: View) {
        if (!finished && root === view) attachObservers(view.viewTreeObserver)
    }

    override fun onViewDetachedFromWindow(view: View) = Unit

    override fun onPreDraw(): Boolean {
        if (!finished && !firstPreDraw) {
            firstPreDraw = true
            report("UI_PAGE first_pre_draw id=$id elapsedMs=${elapsedMs()}")
            detachPreDrawListener()
        }
        return true
    }

    override fun onDraw() {
        if (finished || firstDraw) return
        firstDraw = true
        val visibleMs = visibleElapsedNs() / 1_000_000L
        val firstDrawElapsedMs = elapsedMs()
        report(
            "UI_PAGE first_draw id=$id activity=${activityName.token()} " +
                "from=${from.token()} to=${page.token()} reason=${reason.token()} " +
                "elapsedMs=$firstDrawElapsedMs pageTransitionToFirstDrawMs=$firstDrawElapsedMs " +
                "activityCreateToFirstDrawMs=${if (activityCreateToRenderMs >= 0L) activityCreateToRenderMs + firstDrawElapsedMs else -1L} " +
                "visibleMs=$visibleMs processElapsedMs=${UiPageDiagnostics.processElapsedMillis()} " +
                "layout=$firstLayout preDraw=$firstPreDraw",
        )
        if (blackSuspectLogged && page != "carplay-host") {
            report(
                "UI_PAGE first_draw_after_wait id=$id activity=${activityName.token()} page=${page.token()} " +
                    "visibleMs=$visibleMs stage=first-root-draw",
            )
        }
        val observer = viewTreeObserver
        if (observer?.isAlive == true) handler.post { runCatching { observer.removeOnDrawListener(this) } }
        if (page != "carplay-host") {
            finished = true
            detach()
        }
    }

    private fun reportBlackThresholds() {
        if (finished || (firstDraw && page != "carplay-host") || !foreground) return
        val visibleMs = visibleElapsedNs() / 1_000_000L
        if (page != "carplay-host" && firstPreDraw) {
            // KitKat can omit OnDraw for a cold-start ScrollView even when it is visible and
            // clickable. A completed pre-draw makes "black screen" an unsupported conclusion.
            if (!missingDrawCallbackLogged && visibleMs >= BLACK_STILL_MS) {
                missingDrawCallbackLogged = true
                report(
                    "UI_DRAW_CALLBACK_MISSING id=$id activity=${activityName.token()} " +
                        "page=${page.token()} visibleMs=$visibleMs layout=$firstLayout preDraw=true",
                )
            }
            return
        }
        val event = when {
            page == "carplay-host" -> "UI_VIDEO_WAIT"
            firstPreDraw -> "UI_DRAW"
            else -> "UI_BLACK"
        }
        val waitReason = if (page == "carplay-host") "no-video-release" else "no-root-draw"
        if (!blackSuspectLogged && visibleMs >= BLACK_SUSPECT_MS) {
            blackSuspectLogged = true
            report(
                "$event suspect id=$id activity=${activityName.token()} page=${page.token()} " +
                    "visibleMs=$visibleMs reason=$waitReason layout=$firstLayout preDraw=$firstPreDraw rootDraw=$firstDraw",
            )
        }
        if (!blackStillLogged && visibleMs >= BLACK_STILL_MS) {
            blackStillLogged = true
            report(
                "$event still_waiting id=$id activity=${activityName.token()} page=${page.token()} " +
                    "visibleMs=$visibleMs reason=$waitReason layout=$firstLayout preDraw=$firstPreDraw rootDraw=$firstDraw",
            )
        }
    }

    private fun visibleElapsedNs(nowNs: Long = SystemClock.elapsedRealtimeNanos()): Long =
        foregroundElapsedNs + if (foreground) (nowNs - foregroundStartedAtNs).coerceAtLeast(0L) else 0L

    private fun elapsedMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNs).coerceAtLeast(0L) / 1_000_000L

    private fun detach() {
        handler.removeCallbacks(firstBlackCheck)
        handler.removeCallbacks(sustainedBlackCheck)
        detachGlobalLayoutListener()
        detachPreDrawListener()
        val observer = viewTreeObserver
        if (observer?.isAlive == true) runCatching { observer.removeOnDrawListener(this) }
        viewTreeObserver = null
        root?.removeOnAttachStateChangeListener(this)
        root = null
    }

    private fun attachObservers(observer: ViewTreeObserver) {
        if (!observer.isAlive || viewTreeObserver === observer) return
        viewTreeObserver?.takeIf { it.isAlive }?.let { previous ->
            if (!firstLayout) runCatching { previous.removeOnGlobalLayoutListener(this) }
            if (!firstPreDraw) runCatching { previous.removeOnPreDrawListener(this) }
            if (!firstDraw) runCatching { previous.removeOnDrawListener(this) }
        }
        viewTreeObserver = observer
        if (!firstLayout) observer.addOnGlobalLayoutListener(this)
        if (!firstPreDraw) observer.addOnPreDrawListener(this)
        if (!firstDraw) observer.addOnDrawListener(this)
    }

    private fun detachGlobalLayoutListener() {
        val observer = viewTreeObserver
        if (observer?.isAlive == true) runCatching { observer.removeOnGlobalLayoutListener(this) }
    }

    private fun detachPreDrawListener() {
        val observer = viewTreeObserver
        if (observer?.isAlive == true) runCatching { observer.removeOnPreDrawListener(this) }
    }

    private fun String.token(): String = replace(Regex("[^A-Za-z0-9_.:/-]"), "_").take(96).ifBlank { "unknown" }

    private companion object {
        const val BLACK_SUSPECT_MS = 1_000L
        const val BLACK_STILL_MS = 3_000L
    }
}
