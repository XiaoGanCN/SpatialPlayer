package com.gan.spatialplayer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View

/**
 * The glass material as a background for an ordinary view.
 *
 * `LiquidGlassView` is a container and only makes sense around the video. A button, a chip, a list row
 * or a settings card is not a container - it has a background, and that background should be the same
 * material. This is that background: it renders the identical shader over the same backdrop, with the
 * pane positioned by the view's own place in the window.
 *
 * The backdrop comes from [GlassBackdrop], so every glass surface on a screen shares one capture.
 */
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
    private val location = IntArray(2)

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

    /**
     * Strength of the local bulge under the finger, relative to the rim refraction.
     *
     * The same number the player's capsule uses - the material is one material, and a button that
     * pressed differently from the capsule would be two.
     */
    var pressBulgeStrength: Float = 2.4f

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
            field = value
            radiusPx = value.cornerRadiusPx
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        // Standing down during the capture is what stops the glass from sampling itself.
        if (GlassBackdrop.capturing) return
        if (bounds.isEmpty) return
        // RippleDrawable may rasterize its content into a software mask during a press.
        // RuntimeShader is hardware-only, even when the owning window is accelerated.
        if (!canvas.isHardwareAccelerated) {
            drawFallback(canvas)
            return
        }

        val frame = GlassBackdrop.frameFor(owner)
        val bitmap = frame?.image
        val shader = sharedShader()
        if (bitmap == null || shader == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            drawFallback(canvas)
            return
        }

        owner.getLocationInWindow(location)
        // Where this pane sits in the captured image, and how many texels a view pixel is.
        val x0 = (location[0] - (frame?.originX ?: 0)) * (frame?.scale ?: 1f)
        val y0 = (location[1] - (frame?.originY ?: 0)) * (frame?.scale ?: 1f)
        val texW = bounds.width() * (frame?.scale ?: 1f)
        val texH = bounds.height() * (frame?.scale ?: 1f)

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val radius = radiusPx.coerceAtMost(minOf(w, h) / 2f)

        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uBevel", style.bevelWidthPx)
        shader.setFloatUniform("uRefract", style.refractionPx)
        shader.setFloatUniform("uFalloff", style.falloff)
        shader.setFloatUniform("uDispersion", style.dispersion)
        shader.setFloatUniform("uSpec", style.specularStrength)
        shader.setFloatUniform("uSaturation", style.saturation)
        shader.setFloatUniform("uBlur", style.blurTexels)
        // The sensor wins when there is one: a highlight welded to one corner is what makes glass
        // read as a printed gradient. See LightSourceController.
        val (lightX, lightY) = LightSourceController.lightFor(owner, style.lightX, style.lightY)
        shader.setFloatUniform("uLightDir", lightX, lightY)
        shader.setFloatUniform("uTint", style.tintR, style.tintG, style.tintB, style.tintA)
        shader.setFloatUniform(
            "uGlassTint",
            style.bodyR,
            style.bodyG,
            style.bodyB,
            style.bodyA,
        )
        shader.setFloatUniform("uDim", style.dimAmount)
        shader.setFloatUniform("uPress", pressAmount)
        shader.setFloatUniform("uTouchPos", touchX, touchY)
        shader.setFloatUniform("uTouchAmp", touchAmp)
        shader.setFloatUniform("uPressBulge", pressBulgeStrength)
        shader.setFloatUniform("uBackdropOrigin", x0, y0)
        shader.setFloatUniform("uBackdropScale", (frame?.scale ?: 1f), (frame?.scale ?: 1f))
        shader.setFloatUniform(
            "uBackdropTexSize",
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
        )
        shader.setInputShader(
            "uBackdrop",
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )

        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        // A hairline on the silhouette: the shader's own rim is a light model, and at button sizes it
        // needs the edge defined or the pane dissolves into the background.
        val inset = rect.left + 0.5f
        canvas.drawRoundRect(
            RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f),
            radius,
            radius,
            rimPaint,
        )
        canvas.restoreToCount(save)
    }

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

    private companion object {
        /**
         * One shader for every pane on the device.
         *
         * A `RuntimeShader` is stateless between draws - the uniforms are set immediately before use -
         * and compiling one per button meant a few dozen compilations per screen, each with its own
         * pipeline. Sharing it is both faster to set up and cheaper on GPU state changes.
         */
        private var shared: RuntimeShader? = null
        private var compiled = false

        fun sharedShader(): RuntimeShader? {
            if (!compiled) {
                compiled = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    shared = runCatching { RuntimeShader(GlassShader.SOURCE) }.getOrNull()
                }
            }
            return shared
        }
    }
}
