package org.spatialplayer.ui

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
        set(value) {
            if (field === value) return
            field = value
            generation++
            front = null; back = null
        }
    val hasFrame: Boolean get() = front != null && frameWidth > 0 && frameHeight > 0 && source?.holder?.surface?.isValid == true
    var ambientColors: ((Int,Int,Int,Int)->Unit)?=null
    private var lastAmbient=0L
    private var lastCopyResult = -1
    internal fun debugState() = "running=$running inFlight=$inFlight frame=${frameWidth}x$frameHeight source=${source?.width}x${source?.height} valid=${source?.holder?.surface?.isValid} shown=${source?.isShown} window=${owner.windowVisibility} panes=${GlassBackdrop.hasVisiblePanes(owner)} result=$lastCopyResult"
    private var generation = 0
    fun setFrameSize(width: Int, height: Int) {
        if (frameWidth == width && frameHeight == height) return
        frameWidth = width; frameHeight = height
        generation++
        front = null; back = null
    }
    private var frameWidth = 0
    private var frameHeight = 0
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
            val visible = owner.windowVisibility == View.VISIBLE
            val panes=visible && GlassBackdrop.hasVisiblePanes(owner)
            if (panes || (visible && ambientColors!=null)) copy()
            if (running) handler.postDelayed(this, if (panes) 33L else if(ambientColors!=null) 900L else 150L)
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
        ambientColors=null
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
        val requestGeneration = generation
        inFlight = true
        try {
            PixelCopy.request(surface, Rect(0, 0, frameWidth, frameHeight), target, { result ->
                inFlight = false
                lastCopyResult = result
                if (result == PixelCopy.SUCCESS && running && requestGeneration == generation && source === surface) {
                    back = front
                    front = target
                    GlassBackdrop.invalidateVideo(owner)
                    val now=android.os.SystemClock.uptimeMillis()
                    if(ambientColors!=null && now-lastAmbient>=850) {
                        lastAmbient=now
                        fun edge(horizontal:Boolean,far:Boolean):Int {
                            var r=0;var g=0;var b=0
                            for(i in 0 until 24) {
                                val x=if(horizontal)i*(target.width-1)/23 else if(far)target.width-1 else 0
                                val y=if(!horizontal)i*(target.height-1)/23 else if(far)target.height-1 else 0
                                val color=target.getPixel(x,y);r+=android.graphics.Color.red(color);g+=android.graphics.Color.green(color);b+=android.graphics.Color.blue(color)
                            }
                            return android.graphics.Color.rgb(r/24,g/24,b/24)
                        }
                        ambientColors?.invoke(edge(true,false),edge(true,true),edge(false,false),edge(false,true))
                    }
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
