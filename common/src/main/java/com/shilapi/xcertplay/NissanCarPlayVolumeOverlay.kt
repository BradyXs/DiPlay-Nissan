package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import com.shilapi.xcertplay.host.R
import java.lang.ref.WeakReference

/** Non-focusable volume feedback anchored to the top of CarPlay when its window is visible. */
internal object NissanCarPlayVolumeOverlay {
    private data class Content(
        val view: LinearLayout,
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
                            ViewGroup.LayoutParams.WRAP_CONTENT,
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
            }.onFailure { Log.w("NiPlayVolume", "Could not show OEM volume feedback", it) }
        }
    }

    private fun update(content: Content, context: Context, target: NissanSeekAudioRouting.Target, percent: Int) {
        content.title.text = context.getString(
            if (target == NissanSeekAudioRouting.Target.CALL) R.string.settings_carplay_call_volume
            else R.string.settings_carplay_navigation_volume,
        )
        content.value.text = "${percent.coerceIn(0, 100)}%"
        content.progress.progress = percent.coerceIn(0, 100)
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun createContent(context: Context): Content {
        fun dp(value: Int) = dp(context, value)
        val width = (context.resources.displayMetrics.widthPixels * 0.56f).toInt()
            .coerceAtLeast(dp(320)).coerceAtMost(dp(720))
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = width
            minimumHeight = dp(56)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(226, 28, 35, 46))
                cornerRadius = dp(14).toFloat()
            }
        }
        view.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(25), dp(25)).apply { rightMargin = dp(12) })
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
        }.also { view.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(14) }) }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            val background = GradientDrawable().apply {
                setColor(Color.argb(210, 226, 232, 239))
                cornerRadius = dp(5).toFloat()
            }
            val filled = GradientDrawable().apply {
                setColor(Color.rgb(255, 207, 76))
                cornerRadius = dp(5).toFloat()
            }
            progressDrawable = LayerDrawable(arrayOf(
                background,
                ClipDrawable(filled, Gravity.LEFT, ClipDrawable.HORIZONTAL),
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }.also { view.addView(it, LinearLayout.LayoutParams(0, dp(12), 1f).apply { rightMargin = dp(14) }) }
        val value = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.END
            minWidth = dp(54)
        }.also { view.addView(it) }
        return Content(view, title, value, progress)
    }
}
