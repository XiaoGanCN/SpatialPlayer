package org.spatialplayer.ui

/**
 * The tunable qualities of the material, in one place so every surface can be given the same look.
 *
 * Values are the ones the player's glass was tuned to, with the frost that was missing and a bevel
 * wide enough to see on a control-sized pane.
 */
data class GlassStyle(
    val cornerRadiusPx: Float = 0f,
    val bevelWidthPx: Float = 48f,
    val refractionPx: Float = 160f,
    val falloff: Float = 1.3f,
    val dispersion: Float = 0.10f,
    val specularStrength: Float = 1f,
    val saturation: Float = 1.03f,
    val blurRadiusPx: Float = 14f,
    val tint: Int = 0x18FFFFFF,
    val adaptiveTint: Boolean = true,
    val body: Int = 0x00000000,
    val dimAmount: Float = 0f,
    val fallbackFill: Int = 0x1FFFFFFF,
)
