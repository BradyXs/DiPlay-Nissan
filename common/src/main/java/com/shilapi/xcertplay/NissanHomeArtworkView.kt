package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Lightweight, local artwork for the Nissan connection home. */
internal class NissanHomeArtworkView(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { CAR, WIRELESS, USB }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / 128f, side / 128f)
        when (kind) {
            Kind.CAR -> drawCar(canvas)
            Kind.WIRELESS -> drawWireless(canvas)
            Kind.USB -> drawUsb(canvas)
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawCar(canvas: Canvas) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(64f, 64f, 64f,
            intArrayOf(Color.argb(100, 52, 199, 89), Color.argb(22, 52, 199, 89), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(64f, 64f, 64f, paint)
        paint.shader = LinearGradient(0f, 12f, 0f, 116f,
            Color.rgb(60, 219, 100), Color.rgb(40, 167, 69), Shader.TileMode.CLAMP)
        canvas.drawPath(rounded(12f, 12f, 116f, 116f, 28f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawPath(rounded(13f, 13f, 115f, 115f, 27f), paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 4.8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val roof = Path().apply {
            moveTo(41f, 48f)
            lineTo(43f, 43f)
            cubicTo(45f, 38f, 49f, 37f, 55f, 37f)
            lineTo(73f, 37f)
            cubicTo(79f, 37f, 83f, 38f, 85f, 43f)
            lineTo(87f, 48f)
        }
        canvas.drawPath(roof, paint)
        val body = Path().apply {
            moveTo(39f, 58f)
            lineTo(89f, 58f)
            lineTo(96f, 78f)
            lineTo(96f, 91f)
            lineTo(32f, 91f)
            lineTo(32f, 78f)
            close()
        }
        canvas.drawPath(body, paint)
        canvas.drawLine(34f, 77f, 94f, 77f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(44f, 83f, 3.5f, paint)
        canvas.drawCircle(84f, 83f, 3.5f, paint)
        canvas.drawPath(rounded(36f, 89f, 44f, 100f, 3f), paint)
        canvas.drawPath(rounded(84f, 89f, 92f, 100f, 3f), paint)
    }

    private fun drawWireless(canvas: Canvas) {
        drawTile(canvas, Color.rgb(26, 116, 211), Color.rgb(82, 158, 232))
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 5f
        canvas.drawCircle(64f, 64f, 7f, paint)
        canvas.drawArc(RectF(46f, 46f, 82f, 82f), -60f, 120f, false, paint)
        canvas.drawArc(RectF(46f, 46f, 82f, 82f), 120f, 120f, false, paint)
        canvas.drawArc(RectF(36f, 36f, 92f, 92f), -55f, 110f, false, paint)
        canvas.drawArc(RectF(36f, 36f, 92f, 92f), 125f, 110f, false, paint)
    }

    private fun drawUsb(canvas: Canvas) {
        drawTile(canvas, Color.argb(37, 255, 255, 255), Color.argb(48, 255, 255, 255))
        paint.color = Color.rgb(224, 232, 240)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5.5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawLine(18f, 64f, 95f, 64f, paint)
        canvas.drawLine(49f, 64f, 49f, 39f, paint)
        canvas.drawLine(49f, 39f, 70f, 39f, paint)
        canvas.drawLine(49f, 64f, 49f, 91f, paint)
        canvas.drawLine(49f, 91f, 68f, 91f, paint)
        canvas.drawCircle(78f, 39f, 8f, paint)
        canvas.drawPath(rounded(68f, 80f, 88f, 102f, 2f), paint)
        paint.style = Paint.Style.FILL
        val arrow = Path().apply {
            moveTo(92f, 50f); lineTo(112f, 64f); lineTo(92f, 78f); close()
        }
        canvas.drawPath(arrow, paint)
    }

    private fun drawTile(canvas: Canvas, fill: Int, outline: Int) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = fill
        canvas.drawPath(rounded(8f, 8f, 120f, 120f, 25f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = outline
        canvas.drawPath(rounded(9f, 9f, 119f, 119f, 24f), paint)
        paint.style = Paint.Style.FILL
    }

    private fun rounded(left: Float, top: Float, right: Float, bottom: Float, radius: Float) = Path().apply {
        addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW)
    }
}
