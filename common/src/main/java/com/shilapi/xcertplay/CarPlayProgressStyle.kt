package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ProgressBar
import android.widget.SeekBar

/** Draw the complete track at the drawable's own level on API 19 and newer. */
internal object CarPlayProgressStyle {
    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun seekBar(context: Context): SeekBar = FlatSeekBar(context)

    fun progressBar(context: Context): ProgressBar = FlatProgressBar(context)

    fun track(context: Context): Drawable = FlatTrack(dp(context, 6).toFloat())

    private fun drawTrack(view: android.view.View, canvas: Canvas, progress: Int, max: Int,
        track: FlatTrack) {
        track.setBounds(view.paddingLeft, 0, view.width - view.paddingRight, view.height)
        track.level = if (max > 0) ((progress.toLong() * 10_000L) / max).toInt() else 0
        track.draw(canvas)
    }

    private class FlatSeekBar(context: Context) : SeekBar(context) {
        private val track = FlatTrack(dp(context, 6).toFloat())

        init {
            minimumHeight = dp(context, 44)
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
            thumb = null
            thumbOffset = 0
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawTrack(this, canvas, progress, max, track)
        }
    }

    private class FlatProgressBar(context: Context) : ProgressBar(context, null,
        android.R.attr.progressBarStyleHorizontal) {
        private val track = FlatTrack(dp(context, 6).toFloat())

        init {
            isIndeterminate = false
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawTrack(this, canvas, progress, max, track)
        }
    }

    private class FlatTrack(private val height: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = RectF()
        private var opacity = 255

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) return
            val centerY = bounds.exactCenterY()
            val radius = height / 2f
            bar.set(bounds.left.toFloat(), centerY - radius, bounds.right.toFloat(), centerY + radius)
            paint.color = Color.rgb(57, 75, 95)
            paint.alpha = opacity
            canvas.drawRect(bar, paint)

            val fraction = level.coerceIn(0, 10_000) / 10_000f
            if (fraction <= 0f) return
            bar.right = bounds.left + bounds.width() * fraction
            paint.color = Color.rgb(64, 211, 227)
            paint.alpha = opacity
            canvas.drawRect(bar, paint)
        }

        override fun onLevelChange(level: Int): Boolean {
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) {
            opacity = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicHeight(): Int = height.toInt()
    }
}
