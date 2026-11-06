package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ScaleDrawable
import android.view.Gravity
import android.widget.ProgressBar
import android.widget.SeekBar

/** API 19-compatible progress styling shared with the Nissan head-unit manager toolbox. */
internal object CarPlayProgressStyle {
    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun seekBar(context: Context): SeekBar = SeekBar(context).apply {
        minimumHeight = dp(context, 44)
        progressDrawable = progressDrawable(context, verticalInsetDp = 14, cornerRadiusDp = 8)
        // The toolbox uses a transparent native thumb and zero offset. SeekBar still handles
        // touch tracking and level updates; only its visual handle is hidden.
        thumb = ColorDrawable(Color.TRANSPARENT)
        thumbOffset = 0
    }

    fun progressBar(context: Context): ProgressBar = ProgressBar(
        context,
        null,
        android.R.attr.progressBarStyleHorizontal,
    ).apply {
        isIndeterminate = false
        // The floating volume HUD is only 10 dp tall, so it cannot use the 14 dp inset
        // required by the 44 dp settings SeekBar.
        progressDrawable = progressDrawable(context, verticalInsetDp = 0, cornerRadiusDp = 5)
    }

    fun track(context: Context): Drawable = progressDrawable(context, verticalInsetDp = 14, cornerRadiusDp = 8)

    private fun progressDrawable(
        context: Context,
        verticalInsetDp: Int,
        cornerRadiusDp: Int,
    ): LayerDrawable {
        val track = GradientDrawable().apply {
            setColor(TRACK_COLOR)
            cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(FILL_START_COLOR, FILL_END_COLOR),
        ).apply {
            cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }
        val scaledFill = ScaleDrawable(fill, Gravity.LEFT, 1f, -1f)
        val verticalInset = dp(context, verticalInsetDp)
        return LayerDrawable(arrayOf(track, scaledFill)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            setLayerInset(0, 0, verticalInset, 0, verticalInset)
            setLayerInset(1, 0, verticalInset, 0, verticalInset)
        }
    }

    private const val TRACK_COLOR = 0xff69717b.toInt()
    private const val FILL_START_COLOR = 0xff66e0eb.toInt()
    private const val FILL_END_COLOR = 0xff2c97f5.toInt()
}
