package com.gan.spatialplayer.ui

import android.graphics.Rect

/** Convenience: the rect a video occupies inside a container, honouring aspect ratio. */
object VideoRectCalculator {

    /**
     * @param mode one of [SCALE_FIT], [SCALE_FILL], [SCALE_ZOOM], [SCALE_STRETCH]
     */
    fun compute(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        pixelWidthHeightRatio: Float,
        rotationDegrees: Int,
        mode: Int,
        userZoom: Float,
    ): Rect {
        if (containerWidth <= 0 || containerHeight <= 0) return Rect()

        val rotated = rotationDegrees % 180 != 0
        val effectiveWidth = if (rotated) videoHeight else videoWidth
        val effectiveHeight = if (rotated) videoWidth else videoHeight

        if (effectiveWidth <= 0 || effectiveHeight <= 0) {
            return Rect(0, 0, containerWidth, containerHeight)
        }

        if (mode == SCALE_STRETCH) {
            return Rect(0, 0, containerWidth, containerHeight)
        }

        val par = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
        val videoAspect = (effectiveWidth * par) / effectiveHeight
        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()

        // "Fill" and "Zoom" both crop; Fill keeps the picture fully covering the container, Zoom
        // allows the user to push further in. Both preserve aspect ratio.
        val cover = mode == SCALE_FILL || mode == SCALE_ZOOM

        var width: Float
        var height: Float
        if (cover) {
            if (videoAspect > containerAspect) {
                height = containerHeight.toFloat()
                width = height * videoAspect
            } else {
                width = containerWidth.toFloat()
                height = width / videoAspect
            }
        } else {
            if (videoAspect > containerAspect) {
                width = containerWidth.toFloat()
                height = width / videoAspect
            } else {
                height = containerHeight.toFloat()
                width = height * videoAspect
            }
        }

        if (mode == SCALE_ZOOM && userZoom > 1f) {
            width *= userZoom
            height *= userZoom
        }

        val left = (containerWidth - width) / 2f
        val top = (containerHeight - height) / 2f
        return Rect(
            Math.round(left),
            Math.round(top),
            Math.round(left + width),
            Math.round(top + height),
        )
    }

    const val SCALE_FIT = 0
    const val SCALE_FILL = 1
    const val SCALE_ZOOM = 2
    const val SCALE_STRETCH = 3
}
