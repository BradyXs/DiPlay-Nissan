package com.shilapi.xcertplay

import com.shilapi.xcertplay.ui.components.DiPlayCardUi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.shilapi.xcertplay.host.R
import java.lang.ref.WeakReference

/** Non-focusable volume feedback anchored to the top of CarPlay when its window is visible. */
internal object NissanCarPlayVolumeOverlay {
    private data class Content(
        val view: LinearLayout,
        val width: Int,
        val icon: ImageView,
        val title: TextView,
        val value: TextView,
        val progress: ProgressBar,
    )

    private val main = Handler(Looper.getMainLooper())
    private var host: WeakReference<FrameLayout>? = null
    private var hostContent: Content? = null
    private var toast: Toast? = null
    private var toastContent: Content? = null
    private val hideHost = Runnable { hostContent?.view?.let { (it.parent as? ViewGroup)?.removeView(it) } }

    fun attachHost(container: FrameLayout) {
        main.post {
            hideHost.run()
            host = WeakReference(container)
            hostContent = null
        }
    }

    fun detachHost(container: FrameLayout) {
        main.post {
            if (host?.get() === container) {
                main.removeCallbacks(hideHost)
                hideHost.run()
                host = null
                hostContent = null
            }
        }
    }

    fun show(context: Context, target: NissanSeekAudioRouting.Target, percent: Int) {
        if (target != NissanSeekAudioRouting.Target.NAVIGATION &&
            target != NissanSeekAudioRouting.Target.CALL) return
        val app = context.applicationContext
        main.post {
            runCatching {
                val container = host?.get()?.takeIf { it.isAttachedToWindow }
                if (container != null) {
                    val content = hostContent ?: createContent(container.context).also { hostContent = it }
                    update(content, app, target, percent)
                    main.removeCallbacks(hideHost)
                    if (content.view.parent == null) {
                        container.addView(content.view, FrameLayout.LayoutParams(
                            content.width,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                        ).apply { topMargin = dp(container.context, 6) })
                    }
                    content.view.bringToFront()
                    main.postDelayed(hideHost, 1_600L)
                } else {
                    if (toast == null) {
                        val content = createContent(app)
                        toastContent = content
                        toast = Toast(app).apply {
                            view = content.view
                            duration = Toast.LENGTH_SHORT
                            val statusBarId = app.resources.getIdentifier("status_bar_height", "dimen", "android")
                            val statusBarHeight = if (statusBarId != 0)
                                app.resources.getDimensionPixelSize(statusBarId) else 0
                            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0,
                                maxOf(statusBarHeight, dp(app, 24)) + dp(app, 8))
                        }
                    }
                    toastContent?.let { update(it, app, target, percent) }
                    toast?.show()
                }
            }.onFailure { Log.w("DiPlayVolume", "Could not show OEM volume feedback", it) }
        }
    }

    private fun update(content: Content, context: Context, target: NissanSeekAudioRouting.Target, percent: Int) {
        content.icon.setImageDrawable(AppCompatResources.getDrawable(context,
            if (target == NissanSeekAudioRouting.Target.CALL) R.drawable.ic_dp_call
            else R.drawable.ic_dp_navigation))
        content.title.text = context.getString(
            if (target == NissanSeekAudioRouting.Target.CALL) R.string.settings_carplay_call_volume
            else R.string.settings_carplay_navigation_volume,
        )
        content.value.text = "${percent.coerceIn(0, 100)}%"
        content.progress.progress = percent.coerceIn(0, 100)
        content.view.contentDescription = "${content.title.text}, ${content.value.text}"
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun createContent(context: Context): Content {
        fun dp(value: Int) = dp(context, value)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = (screenWidth * 0.56f).toInt().coerceIn(dp(320), dp(720))
            .coerceAtMost((screenWidth - dp(24)).coerceAtLeast(dp(240)))
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = width
            layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(234, 49, 53, 61))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), DiPlayCardUi.BORDER)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply {
            setColorFilter(Color.WHITE)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { header.addView(it, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }) }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
        }.also { header.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        val value = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.END
            minWidth = dp(54)
        }.also { header.addView(it) }
        view.addView(header, LinearLayout.LayoutParams(-1, -2))
        val progress = CarPlayProgressStyle.progressBar(context).apply {
            max = 100
        }.also { view.addView(it, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(10) }) }
        return Content(view, width, icon, title, value, progress)
    }
}
