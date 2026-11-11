package org.spatialplayer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/** Reference GPU lens material for ordinary controls; bitmap/software canvases get a safe fill. */
class GlassDrawable(
    private val owner: View,
    material: GlassStyle = GlassStyle(),
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x40FFFFFF
    }
    private val rect = RectF()
    private val renderer = GlassLensRenderer()
    internal var renderedFrames = 0L
        private set

    /**
     * Press state, in two parts.
     *
     * [pressAmount] is what the shader sees (0..1, ramped); [touchAmp] is separate because the local
     * bulge under the finger only exists while the finger does, so releasing must fade the bulge with
     * the ramp rather than leaving it behind.
     *
     * The press is driven by the drawable's own state, not by a touch listener: `RippleDrawable`
     * above it reports the press through `setState`, so this needs no hook in the view's touch path
     * and cannot steal a gesture from a control.
     */
    private var pressAmount = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touchAmp = 0f
    private var pressAnimator: ValueAnimator? = null
    private var pressedState = false

    /** Latched rather than read per press: it is a settings-provider query. */
    private var animationsDisabled: Boolean? = null

    /** Where the finger is, in this pane's coordinates. Ignored unless it is down. */
    fun setTouchPoint(x: Float, y: Float) {
        if (touchX == x && touchY == y) return
        touchX = x
        touchY = y
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = state.contains(android.R.attr.state_pressed)
        val wasPressed = pressedState
        pressedState = pressed
        if (pressed != wasPressed) animatePress(pressed)
        return super.onStateChange(state)
    }

    private fun animatePress(pressed: Boolean) {
        pressAnimator?.cancel()
        val disabled = animationsDisabled
            ?: LightSourceController.animationsDisabled(owner.context).also { animationsDisabled = it }
        if (disabled) {
            touchAmp = if (pressed) 1f else 0f
            pressAmount = if (pressed) 1f else 0f
            invalidateSelf()
            return
        }
        if (pressed) touchAmp = 1f
        pressAnimator = ValueAnimator.ofFloat(pressAmount, if (pressed) 1f else 0f).apply {
            // Asymmetric: down is a displacement, up is a spring. See LiquidGlassView.animatePress.
            duration = if (pressed) 180L else 320L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                pressAmount = it.animatedValue as Float
                invalidateSelf()
            }
            if (!pressed) {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        touchAmp = 0f
                        invalidateSelf()
                    }
                })
            }
            start()
        }
    }

    /** Corner radius in px; a pill passes half its height. */
    var radiusPx: Float = material.cornerRadiusPx

    /** The tunable look; reassigning it restyles the pane in place. */
    var style: GlassStyle = material
        set(value) {
            if (field == value) return
            field = value
            radiusPx = value.cornerRadiusPx
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (!canvas.isHardwareAccelerated || GlassBackdrop.capturing) {
            drawFallback(canvas)
            return
        }
        val params = GlassOptics.parameters(owner, style, pressAmount, touchX, touchY)
        if (renderer.draw(canvas, owner, params, GlassOptics.margin(style))) renderedFrames++ else drawFallback(canvas)
    }

    fun release() { pressAnimator?.cancel(); renderer.release() }

    private fun drawFallback(canvas: Canvas) {
        fallbackPaint.color = style.fallbackFill
        val radius = radiusPx.coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, radius, radius, fallbackPaint)
        canvas.drawRoundRect(rect, radius, radius, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fallbackPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        fallbackPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

}
