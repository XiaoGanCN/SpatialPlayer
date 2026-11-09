package com.gan.spatialplayer.ui

/**
 * The tunable qualities of the material, in one place so every surface can be given the same look.
 *
 * Values are the ones the player's glass was tuned to, with the frost that was missing and a bevel
 * wide enough to see on a control-sized pane.
 */
data class GlassStyle(
    val cornerRadiusPx: Float = 0f,
    val bevelWidthPx: Float = 8f,
    val refractionPx: Float = 3f,
    val falloff: Float = 1.3f,
    val dispersion: Float = 0.03f,
    val specularStrength: Float = 0.3f,
    val saturation: Float = 1.03f,
    val blurTexels: Float = 5f,
    val tint: Int = 0x18FFFFFF,
    val body: Int = 0x2410151B,
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
