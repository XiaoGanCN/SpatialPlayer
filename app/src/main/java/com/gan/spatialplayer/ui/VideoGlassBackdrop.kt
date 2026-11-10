package com.gan.spatialplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import kotlin.math.roundToInt

/** Only native video needs readback. The copied SDR preview is blurred by the same GPU lens chain. */
internal class VideoGlassBackdrop(private val owner: View) {
    var source: SurfaceView? = null
    var frameWidth = 0
    var frameHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var inFlight = false
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sourcePosition = IntArray(2)
    private val ownerPosition = IntArray(2)
    private val destination = RectF()
    private val tick = object : Runnable {
        override fun run() {
            val visible = owner.windowVisibility == View.VISIBLE && GlassBackdrop.hasVisiblePanes(owner)
            if (visible) copy()
            if (running) handler.postDelayed(this, if (visible) 33L else 150L)
        }
    }
    fun start() {
        GlassBackdrop.video(owner, this)
        if (running) return
        running = true
        handler.post(tick)
    }
    fun stop() { running = false; handler.removeCallbacks(tick) }
    fun release() {
        stop()
        GlassBackdrop.video(owner, null)
        // Render-thread display lists may still retain the previous bitmap; let GC release it.
        front = null
        back = null
    }
    private fun copy() {
        val surface = source ?: return
        if (inFlight || !surface.isShown || !surface.holder.surface.isValid || frameWidth <= 0 || frameHeight <= 0) return
        val scale = minOf(1f, 768f / maxOf(frameWidth, frameHeight))
        val w = (frameWidth * scale).roundToInt().coerceAtLeast(2)
        val h = (frameHeight * scale).roundToInt().coerceAtLeast(2)
        val target = back?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        inFlight = true
        try {
            PixelCopy.request(surface, Rect(0, 0, frameWidth, frameHeight), target, { result ->
                inFlight = false
                if (result == PixelCopy.SUCCESS && running) {
                    back = front
                    front = target
                    GlassBackdrop.invalidateVideo(owner)
                }
            }, handler)
        } catch (_: IllegalArgumentException) { inFlight = false }
    }
    fun draw(canvas: Canvas, pane: View) {
        val surface = source ?: return
        val bitmap = front ?: return
        surface.getLocationOnScreen(sourcePosition)
        pane.getLocationOnScreen(ownerPosition)
        val x = (sourcePosition[0] - ownerPosition[0]).toFloat()
        val y = (sourcePosition[1] - ownerPosition[1]).toFloat()
        destination.set(x, y, x + surface.width * surface.scaleX, y + surface.height * surface.scaleY)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}
