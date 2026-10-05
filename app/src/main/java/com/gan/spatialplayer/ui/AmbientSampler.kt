package com.gan.spatialplayer.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View

/**
 * Samples the colours along the edges of the live picture and hands them to [AmbientGlowView].
 *
 * The picture lives in a `SurfaceView`, whose contents are not readable through the normal view
 * hierarchy, so this uses [PixelCopy] - the supported way to read back a surface. To keep the cost
 * negligible it copies one tiny downscaled frame (24x14 by default) and derives all four edge
 * colours from that single buffer, rather than issuing four copies.
 *
 * Rate-limited by [intervalMs]; at the default that is a little over one small copy per second,
 * which is far below the cost of a frame of video decoding.
 */
class AmbientSampler(
    private val surfaceView: SurfaceView,
    private val intervalMs: Long = 900L,
    private val onColors: (top: Int, bottom: Int, left: Int, right: Int) -> Unit,
    private val onUnavailable: () -> Unit = {},
) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var inFlight = false
    private var bitmap: Bitmap? = null
    private var videoRect: Rect = Rect()
    private var failures = 0
    private var reported = false

    private val tick = object : Runnable {
        override fun run() {
            sample()
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    /** The region of the surface actually occupied by picture, in surface coordinates. */
    fun setVideoRect(rect: Rect) {
        videoRect = Rect(rect)
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, intervalMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        bitmap?.recycle()
        bitmap = null
    }

    private fun sample() {
        if (inFlight) return
        if (surfaceView.width <= 0 || surfaceView.height <= 0) return
        if (!surfaceView.isAttachedToWindow) return

        if (surfaceView.width <= 0 || surfaceView.height <= 0) return

        val target = bitmap ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
            .also { bitmap = it }

        inFlight = true
        // Copy the whole surface rather than `region`. A rect-limited PixelCopy of a video layer is
        // rejected with ERROR_UNKNOWN on this hardware, whereas a full-surface copy succeeds; the
        // requested region is used only to select which edges to read.
        runCatching {
            PixelCopy.request(
                surfaceView,
                target,
                { result ->
                    inFlight = false
                    if (result == PixelCopy.SUCCESS) {
                        emitEdges(target)
                    } else {
                        failures++
                        if (failures == 1 || failures >= MAX_FAILURES) {
                            Log.w(TAG, "PixelCopy failed result=$result (failure $failures)")
                        }
                        if (failures >= MAX_FAILURES && !reported) {
                            reported = true
                            onUnavailable()
                        }
                    }
                },
                handler,
            )
        }.onFailure {
            inFlight = false
        }
    }

    /**
     * Averages each edge of the downscaled frame. Averaging (rather than taking a single pixel)
     * keeps a bright highlight or a dark letterbox edge from dominating the wash.
     */
    private fun emitEdges(source: Bitmap) {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return

        // The buffer covers the whole surface; `videoRect` says where the picture is inside it, in
        // surface pixels. Map that into buffer pixels and read the picture's own edges, so the
        // sample is never taken from the black bars.
        val surfaceW = surfaceView.width.coerceAtLeast(1)
        val surfaceH = surfaceView.height.coerceAtLeast(1)
        val rect = videoRect.takeIf { !it.isEmpty } ?: Rect(0, 0, surfaceW, surfaceH)

        val left = ((rect.left.toLong() * w) / surfaceW).toInt().coerceIn(0, w - 1)
        val right = ((rect.right.toLong() * w) / surfaceW).toInt().coerceIn(0, w - 1)
        val top = ((rect.top.toLong() * h) / surfaceH).toInt().coerceIn(0, h - 1)
        val bottom = ((rect.bottom.toLong() * h) / surfaceH).toInt().coerceIn(0, h - 1)

        val topColor = averageRow(source, y = top, fromX = left, toX = right)
        val bottomColor = averageRow(source, y = bottom, fromX = left, toX = right)
        val leftColor = averageColumn(source, x = left, fromY = top, toY = bottom)
        val rightColor = averageColumn(source, x = right, fromY = top, toY = bottom)

        failures = 0
        onColors(
            punch(topColor),
            punch(bottomColor),
            punch(leftColor),
            punch(rightColor),
        )
    }

    private fun averageRow(source: Bitmap, y: Int, fromX: Int, toX: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (x in fromX..toX) {
            if (x < 0 || x >= source.width) continue
            val pixel = source.getPixel(x, y)
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
            count++
        }
        if (count == 0) return Color.BLACK
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun averageColumn(source: Bitmap, x: Int, fromY: Int, toY: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in fromY..toY) {
            if (y < 0 || y >= source.height) continue
            val pixel = source.getPixel(x, y)
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
            count++
        }
        if (count == 0) return Color.BLACK
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /**
     * Nudges a colour toward something worth glowing with: lifts very dark colours so a night scene
     * still casts a little light, and desaturates the extremes so the wash never looks like a
     * colour filter was applied to the picture.
     */
    private fun punch(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 0.92f).coerceIn(0f, 1f)
        hsv[2] = hsv[2].coerceIn(MIN_VALUE, 1f)
        return Color.HSVToColor(hsv)
    }

    companion object {
        private const val TAG = "AmbientSampler"

        /** Tiny buffer: this is a colour average, not an image. */
        const val SAMPLE_W = 24
        const val SAMPLE_H = 14

        /** Floor on brightness so dark scenes still glow faintly rather than going pure black. */
        const val MIN_VALUE = 0.10f

        /** Give up on the ambient wash after this many consecutive copy failures. */
        const val MAX_FAILURES = 5
    }
}

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
