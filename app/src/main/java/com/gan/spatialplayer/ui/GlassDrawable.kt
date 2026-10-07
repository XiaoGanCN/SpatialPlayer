package com.gan.spatialplayer.ui

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
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

        val bitmap = GlassBackdrop.image
        val shader = sharedShader()
        if (bitmap == null || shader == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            drawFallback(canvas)
            return
        }

        owner.getLocationInWindow(location)
        // Where this pane sits in the captured image, and how many texels a view pixel is.
        val x0 = (location[0] - GlassBackdrop.originX) * GlassBackdrop.imageScale
        val y0 = (location[1] - GlassBackdrop.originY) * GlassBackdrop.imageScale
        val texW = bounds.width() * GlassBackdrop.imageScale
        val texH = bounds.height() * GlassBackdrop.imageScale

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
        shader.setFloatUniform("uLightDir", style.lightX, style.lightY)
        shader.setFloatUniform("uTint", style.tintR, style.tintG, style.tintB, style.tintA)
        shader.setFloatUniform(
            "uGlassTint",
            style.bodyR,
            style.bodyG,
            style.bodyB,
            style.bodyA,
        )
        shader.setFloatUniform("uDim", style.dimAmount)
        shader.setFloatUniform("uPress", 0f)
        shader.setFloatUniform("uBackdropOrigin", x0, y0)
        shader.setFloatUniform("uBackdropScale", GlassBackdrop.imageScale, GlassBackdrop.imageScale)
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
                    shared = runCatching { RuntimeShader(LiquidGlassView.GLASS_SHADER) }.getOrNull()
                }
            }
            return shared
        }
    }
}

/**
 * The tunable qualities of the material, in one place so every surface can be given the same look.
 *
 * Values are the ones the player's glass was tuned to, with the frost that was missing and a bevel
 * wide enough to see on a control-sized pane.
 */
data class GlassStyle(
    val cornerRadiusPx: Float = 0f,
    val bevelWidthPx: Float = 14f,
    val refractionPx: Float = 6f,
    val falloff: Float = 1.6f,
    val dispersion: Float = 0.06f,
    val specularStrength: Float = 0.9f,
    val saturation: Float = 1.06f,
    val blurTexels: Float = 7f,
    val tint: Int = 0x14FFFFFF,
    val body: Int = 0x00000000,
    val dimAmount: Float = 0f,
    val lightX: Float = -0.7071f,
    val lightY: Float = -0.7071f,
    val fallbackFill: Int = 0x1FFFFFFF,
) {
    val tintA: Float get() = ((tint ushr 24) and 0xFF) / 255f
    val tintR: Float get() = ((tint shr 16) and 0xFF) / 255f
    val tintG: Float get() = ((tint shr 8) and 0xFF) / 255f
    val tintB: Float get() = (tint and 0xFF) / 255f
    val bodyA: Float get() = ((body ushr 24) and 0xFF) / 255f
    val bodyR: Float get() = ((body shr 16) and 0xFF) / 255f
    val bodyG: Float get() = ((body shr 8) and 0xFF) / 255f
    val bodyB: Float get() = (body and 0xFF) / 255f
}
