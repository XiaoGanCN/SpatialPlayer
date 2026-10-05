package com.gan.spatialplayer.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Pill background for a status chip: a translucent fill with a hairline rim.
 *
 * Drawn in code rather than as a shape drawable so the corner radius is expressed in pixels and
 * stays a true pill regardless of the text length.
 */
class ChipDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val radius: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val r = RectF(bounds)
        r.inset(strokePaint.strokeWidth / 2f, strokePaint.strokeWidth / 2f)
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        canvas.drawRoundRect(r, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
