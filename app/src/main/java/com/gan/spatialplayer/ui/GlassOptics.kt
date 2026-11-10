package com.gan.spatialplayer.ui

import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Reference-library optical parameters, including its small-control scaling. */
internal object GlassOptics {
    fun parameters(owner: View, style: GlassStyle, press: Float = 0f, touchX: Float = owner.width / 2f,
                   touchY: Float = owner.height / 2f, shape: RectF = RectF(0f, 0f, owner.width.toFloat(), owner.height.toFloat())): GlassLensRenderer.LensParams {
        val shortSide = min(shape.width(), shape.height())
        val radius = style.cornerRadiusPx.coerceAtMost(shortSide / 2f)
        val bevel = min(style.bevelWidthPx, shortSide * 0.3f).coerceAtLeast(2f)
        val reference = 110f * owner.resources.displayMetrics.density
        val refract = min(style.refractionPx, .7f * shortSide * min(1f, shortSide/reference))
        val light = LightSourceController.lightFor(owner, LightSourceController.DEFAULT_X, LightSourceController.DEFAULT_Y)
        return GlassLensRenderer.LensParams(
            blurRadius = style.blurRadiusPx,
            shape1CX = shape.centerX(), shape1CY = shape.centerY(), shape1HW = shape.width()/2, shape1HH = shape.height()/2,
            radius1TL = radius, radius1TR = radius, radius1BR = radius, radius1BL = radius,
            lens1CX = shape.centerX(), lens1CY = shape.centerY(), lens1HW = shape.width()/2, lens1HH = shape.height()/2,
            rimSoft = 1.5f, shape2CX = 0f, shape2CY = 0f, shape2HW = 0f, shape2HH = 0f, radius2 = 0f, blendK = 0f,
            bevel = bevel, refract = refract, falloff = style.falloff, outward = false,
            rimBandMax = min(6f, shortSide * .05f).coerceAtLeast(2f), dispersion = style.dispersion,
            lightX = (light.first * 200).toInt()/200f, lightY = (light.second * 200).toInt()/200f,
            spec = style.specularStrength, tint = style.tint, adaptiveTint = style.adaptiveTint,
            glassTint = style.body, dim = style.dimAmount, saturation = style.saturation * 100f,
            press = (press * 100).toInt()/100f, touchX = touchX, touchY = touchY, touchAmp = press,
        )
    }
    fun margin(style: GlassStyle): Int = maxOf(32f, style.blurRadiusPx * 3).toInt()
}
