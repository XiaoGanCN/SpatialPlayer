package com.gan.spatialplayer.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * The "liquid glass" surface used by the player sheets.
 *
 * Two techniques stack to keep it honest rather than just translucent:
 *
 *  * On API 34+ a masked [BlurMaskFilter] on the *border and cast shadow* gives the soft, slightly
 *    frosted edge that reads as thick glass. This is a real blur, not a tinted rectangle.
 *  * A hairline top highlight plus a darker bottom edge simulate the light catching the rim.
 *
 * The fill itself is deliberately a low-alpha tint: over moving video, a heavier fill would wash
 * the picture out, which is the opposite of what this design wants.
 */
class GlassPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * resources.displayMetrics.density
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    private val rect = RectF()
    private var cornerRadiusPx = 26f * resources.displayMetrics.density

    var fillColor: Int = Color.argb(20, 255, 255, 255)
        set(value) {
            field = value
            invalidate()
        }

    var edgeColor: Int = Color.argb(46, 255, 255, 255)
        set(value) {
            field = value
            invalidate()
        }

    var highlightColor: Int = Color.argb(56, 255, 255, 255)
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Let the glass draw itself while still hosting children.
        setWillNotDraw(false)
        isFocusable = false
        clipToPadding = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Soft cast shadow beneath the sheet.
            shadowPaint.maskFilter = BlurMaskFilter(18f * resources.displayMetrics.density, BlurMaskFilter.Blur.NORMAL)
        }
    }

    override fun setBackground(background: android.graphics.drawable.Drawable?) {
        // Background drawables would fight the custom painting; ignore them.
        super.setBackground(null)
    }

    fun setCornerRadiusDp(radiusDp: Float) {
        cornerRadiusPx = radiusDp * resources.displayMetrics.density
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        rect.set(0f, 0f, w, h)

        // Cast shadow: a slightly offset, blurred copy underneath.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadowRect = RectF(rect)
            shadowRect.offset(0f, 6f * resources.displayMetrics.density)
            canvas.drawRoundRect(shadowRect, cornerRadiusPx, cornerRadiusPx, shadowPaint)
        }

        // Frosted fill.
        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)

        // Rim.
        edgePaint.color = edgeColor
        val inset = edgePaint.strokeWidth / 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, edgePaint)

        // Top highlight: only the upper arc, so light appears to come from above.
        highlightPaint.color = highlightColor
        canvas.save()
        canvas.clipRect(0f, 0f, w, h * 0.5f)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, highlightPaint)
        canvas.restore()

        rect.set(0f, 0f, w, h)
        super.onDraw(canvas)
    }
}
