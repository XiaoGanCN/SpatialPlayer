package com.gan.spatialplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The YouTube-style ambient wash.
 *
 * Two constraints shape this view:
 *
 *  1. Media3 draws into a `SurfaceView`, which is punched through the window. Anything composed
 *     *behind* that surface is hidden, so this view sits **above** the video instead of behind it.
 *  2. Consequently it cannot tint the picture itself. What it does is fill the empty space around
 *     the picture - the letterbox bands top and bottom in portrait, the pillarbox bars left and
 *     right in landscape - with a soft gradient of the colours sampled from the nearest edge of
 *     the frame. That is the part of the screen that would otherwise be dead black.
 *
 * Colours are pushed in by [setEdgeColors] from a PixelCopy sampler; this view only draws them,
 * easing between samples so the wash breathes instead of flickering.
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The rectangle actually occupied by the picture, in this view's coordinates. */
    private var videoRect: Rect? = null

    private var topColor = Color.TRANSPARENT
    private var bottomColor = Color.TRANSPARENT
    private var leftColor = Color.TRANSPARENT
    private var rightColor = Color.TRANSPARENT

    private var animatedTop = Color.TRANSPARENT
    private var animatedBottom = Color.TRANSPARENT
    private var animatedLeft = Color.TRANSPARENT
    private var animatedRight = Color.TRANSPARENT

    private var colorAnimator: ValueAnimator? = null

    /** 0 = fully off, 1 = full strength. */
    var intensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Master switch, wired to the user's ambient-glow toggle.
     *  Named `glowEnabled` rather than `enabled` because View already defines `setEnabled`. */
    var glowEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                colorAnimator?.cancel()
                animatedTop = Color.TRANSPARENT
                animatedBottom = Color.TRANSPARENT
                animatedLeft = Color.TRANSPARENT
                animatedRight = Color.TRANSPARENT
            }
            invalidate()
        }

    /** Where the picture sits. Passing null means "unknown", and the wash fades out. */
    fun setVideoRect(rect: Rect?) {
        if (rect == videoRect) return
        videoRect = rect?.let { Rect(it) }
        invalidate()
    }

    /** New edge samples. Colours are blended over [DURATION_MS] so the wash drifts. */
    fun setEdgeColors(top: Int, bottom: Int, left: Int, right: Int) {
        if (!glowEnabled) return
        topColor = top
        bottomColor = bottom
        leftColor = left
        rightColor = right
        startColorAnimation()
    }

    private fun startColorAnimation() {
        colorAnimator?.cancel()
        val fromTop = animatedTop
        val fromBottom = animatedBottom
        val fromLeft = animatedLeft
        val fromRight = animatedRight
        val toTop = topColor
        val toBottom = bottomColor
        val toLeft = leftColor
        val toRight = rightColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedFraction
                animatedTop = blend(fromTop, toTop, t)
                animatedBottom = blend(fromBottom, toBottom, t)
                animatedLeft = blend(fromLeft, toLeft, t)
                animatedRight = blend(fromRight, toRight, t)
                invalidate()
            }
            start()
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.rgb(
            r.coerceIn(0, 255),
            g.coerceIn(0, 255),
            b.coerceIn(0, 255),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = videoRect
        if (!glowEnabled || intensity <= 0f || rect == null || rect.isEmpty) return

        val alpha = (MAX_ALPHA * intensity).toInt().coerceIn(0, 255)
        val fade = (height * BAND_FRACTION).coerceAtLeast(1f)

        // Top band: colour bleeds upward from the top edge of the picture.
        val topBandHeight = rect.top
        if (topBandHeight > 0) {
            drawBand(
                canvas = canvas,
                fromY = rect.top.toFloat(),
                toY = (rect.top - fade).coerceAtLeast(0f),
                bands = BandSet(
                    nearColor = animatedTop,
                    farColor = Color.BLACK,
                ),
                alpha = alpha,
                horizontal = true,
            )
        }

        // Bottom band.
        val bottomBandHeight = height - rect.bottom
        if (bottomBandHeight > 0) {
            drawBand(
                canvas = canvas,
                fromY = rect.bottom.toFloat(),
                toY = (rect.bottom + fade).coerceAtMost(height.toFloat()),
                bands = BandSet(
                    nearColor = animatedBottom,
                    farColor = Color.BLACK,
                ),
                alpha = alpha,
                horizontal = true,
            )
        }

        // Left pillar box (landscape).
        val leftBandWidth = rect.left
        if (leftBandWidth > 0) {
            drawBand(
                canvas = canvas,
                fromY = rect.left.toFloat(),
                toY = (rect.left - fade).coerceAtLeast(0f),
                bands = BandSet(
                    nearColor = animatedLeft,
                    farColor = Color.BLACK,
                ),
                alpha = alpha,
                horizontal = false,
            )
        }

        // Right pillar box.
        val rightBandWidth = width - rect.right
        if (rightBandWidth > 0) {
            drawBand(
                canvas = canvas,
                fromY = rect.right.toFloat(),
                toY = (rect.right + fade).coerceAtMost(width.toFloat()),
                bands = BandSet(
                    nearColor = animatedRight,
                    farColor = Color.BLACK,
                ),
                alpha = alpha,
                horizontal = false,
            )
        }
    }

    private class BandSet(val nearColor: Int, val farColor: Int)

    /**
     * Draws one gradient band.
     *
     * @param horizontal true when [fromY]/[toY] are vertical screen coordinates (top/bottom bands),
     *                   false when they are horizontal ones (left/right bands).
     */
    private fun drawBand(
        canvas: Canvas,
        fromY: Float,
        toY: Float,
        bands: BandSet,
        alpha: Int,
        horizontal: Boolean,
    ) {
        if (fromY == toY) return
        val near = withAlpha(bands.nearColor, alpha)
        val far = withAlpha(bands.farColor, 0)

        val shader = if (horizontal) {
            LinearGradient(
                0f, fromY, 0f, toY,
                near, far,
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                fromY, 0f, toY, 0f,
                near, far,
                Shader.TileMode.CLAMP,
            )
        }
        paint.shader = shader
        if (horizontal) {
            val top = minOf(fromY, toY)
            val bottom = maxOf(fromY, toY)
            // Cover the full width, with a little horizontal overshoot so corners read solid.
            canvas.drawRect(-OVERDRAW, top, width + OVERDRAW, bottom, paint)
        } else {
            val left = minOf(fromY, toY)
            val right = maxOf(fromY, toY)
            canvas.drawRect(left, -OVERDRAW, right, height + OVERDRAW, paint)
        }
        paint.shader = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onDetachedFromWindow() {
        colorAnimator?.cancel()
        colorAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DURATION_MS = 900L

        /** Peak opacity of the wash; higher starts to fight the picture. */
        const val MAX_ALPHA = 168

        /** How far the glow reaches into the empty band, as a fraction of screen height. */
        const val BAND_FRACTION = 0.34f

        /** Horizontal overshoot so gradient corners do not band. */
        const val OVERDRAW = 4f
    }
}
