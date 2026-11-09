package com.gan.spatialplayer.ui

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
