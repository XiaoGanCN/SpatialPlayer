package org.spatialplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
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
            front = null; previousFront=null; buffers.fill(null)
        }
    val hasFrame: Boolean get() = front != null && frameWidth > 0 && frameHeight > 0 && source!=null
    var ambientColors: ((Int,Int,Int,Int)->Unit)?=null
    private var sourceFrameRate=30f
    private var rateSampleAt=0L
    private var rateSampleFrames=0L
    fun observeFrameRate(declared:Float,rendered:Long) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(declared.isFinite() && declared>0) {
            sourceFrameRate=declared
            rateSampleAt=now;rateSampleFrames=rendered
        } else if(rateSampleAt==0L || rendered<rateSampleFrames) {
            rateSampleAt=now;rateSampleFrames=rendered
        } else if(now-rateSampleAt>=750L) {
            val frames=rendered-rateSampleFrames
            // Matroska often omits Format.frameRate. Use observed hardware output cadence.
            if(frames>=4)sourceFrameRate=(frames*1000f/(now-rateSampleAt)).coerceIn(1f,120f)
            rateSampleAt=now;rateSampleFrames=rendered
        }
    }
    private var lastUiRequest=0L
    private var lastAmbient=0L
    private var lastCopyResult = -1
    internal fun debugState() = "running=$running inFlight=$inFlight frame=${frameWidth}x$frameHeight source=${source?.width}x${source?.height} valid=${source?.holder?.surface?.isValid} shown=${source?.isShown} window=${owner.windowVisibility} panes=${GlassBackdrop.hasVisiblePanes(owner)} copyMs=$copyLatencyMs sourceFps=$sourceFrameRate result=$lastCopyResult"
    private var generation = 0
    private var waitingForFormat=false
    private val lastGeometry=RectF()
    fun setFrameSize(width: Int, height: Int) {
        // Codec rebuilds briefly report UNKNOWN. Retain the last SDR preview for this surface;
        // new sources still clear it, and copy() waits for a valid surface/positive dimensions.
        if(width<=0 || height<=0) {
            if(!waitingForFormat)generation++
            waitingForFormat=true
            return
        }
        waitingForFormat=false
        if (frameWidth == width && frameHeight == height) return
        frameWidth = width; frameHeight = height
        generation++
        front = null; previousFront=null; buffers.fill(null)
    }
    private var frameWidth = 0
    private var frameHeight = 0
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val pending=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap,Boolean>())
    private val inFlight get()=pending.isNotEmpty()
    private var requestedSerial=0L
    private var displayedSerial=0L
    private var previousFront:Bitmap?=null
    private var front: Bitmap? = null
    private val buffers = arrayOfNulls<Bitmap>(5)
    private var bufferIndex=0
    private var copyLatencyMs=0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sourcePosition = IntArray(2)
    private val ownerPosition = IntArray(2)
    private val destination = RectF()
    private val geometryPosition=IntArray(2)
    private var geometryX=Int.MIN_VALUE
    private var geometryY=Int.MIN_VALUE
    private var geometryWidth=0
    private var geometryHeight=0
    private var geometryScaleX=0f
    private var geometryScaleY=0f
    fun geometryChanged():Boolean {
        val view=source ?: return false
        view.getLocationOnScreen(geometryPosition)
        val changed=geometryX!=geometryPosition[0] || geometryY!=geometryPosition[1] ||
            geometryWidth!=view.width || geometryHeight!=view.height || geometryScaleX!=view.scaleX || geometryScaleY!=view.scaleY
        geometryX=geometryPosition[0];geometryY=geometryPosition[1]
        geometryWidth=view.width;geometryHeight=view.height;geometryScaleX=view.scaleX;geometryScaleY=view.scaleY
        return changed
    }
    private val choreographer = Choreographer.getInstance()
    private var lastAmbientRequest = 0L
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if(!running)return
            val visible=owner.windowVisibility==View.VISIBLE
            val panes=visible && GlassBackdrop.hasVisiblePanes(owner)
            // Up to two opportunities per source frame, while controls still animate at display rate.
            // This avoids redundant HDR readbacks overwhelming the GPU on a 24fps film at 120Hz.
            val interval=if(sourceFrameRate>0) (1_000_000_000.0/(sourceFrameRate*2).coerceIn(30f,120f)).toLong()-500_000L else 0L
            if(panes && frameTimeNanos-lastUiRequest>=interval) {
                copy();lastUiRequest=frameTimeNanos;lastAmbientRequest=frameTimeNanos
            } else if(!panes && visible && ambientColors!=null && frameTimeNanos-lastAmbientRequest>900_000_000L) {
                copy();lastAmbientRequest=frameTimeNanos
            }
            if(panes)choreographer.postFrameCallback(this)
            else choreographer.postFrameCallbackDelayed(this,if(ambientColors!=null)150L else 300L)
        }
    }
    fun start() {
        GlassBackdrop.video(owner, this)
        if (running) {wake();return}
        running = true
        choreographer.postFrameCallback(tick)
    }
    fun wake() {lastUiRequest=0L;if(running){choreographer.removeFrameCallback(tick);choreographer.postFrameCallback(tick)}}
    fun stop() { running = false; choreographer.removeFrameCallback(tick) }
    fun release() {
        stop()
        ambientColors=null
        GlassBackdrop.video(owner, null)
        // Render-thread display lists may still retain the previous bitmap; let GC release it.
        front = null
        previousFront=null
        buffers.fill(null)
    }
    private fun copy() {
        val surface = source ?: return
        if(waitingForFormat)return
        if (pending.size>=2 || !surface.isShown || !surface.holder.surface.isValid || frameWidth <= 0 || frameHeight <= 0) return
        val scale = minOf(1f, 768f / maxOf(frameWidth, frameHeight))
        val w = (frameWidth * scale).roundToInt().coerceAtLeast(2)
        val h = (frameHeight * scale).roundToInt().coerceAtLeast(2)
        while(buffers[bufferIndex]?.let {it===front || it===previousFront || it in pending}==true)bufferIndex=(bufferIndex+1)%buffers.size
        val index=bufferIndex
        val target = buffers[index]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffers[index]=target
        bufferIndex=(index+1)%buffers.size
        val copyStarted=android.os.SystemClock.elapsedRealtimeNanos()
        val serial=++requestedSerial
        val requestGeneration = generation
        pending.add(target)
        try {
            PixelCopy.request(surface, Rect(0, 0, frameWidth, frameHeight), target, { result ->
                pending.remove(target)
                lastCopyResult = result
                if (result == PixelCopy.SUCCESS && running && requestGeneration == generation && source === surface && serial>displayedSerial) {
                    displayedSerial=serial
                    copyLatencyMs=(android.os.SystemClock.elapsedRealtimeNanos()-copyStarted)/1_000_000L
                    val previous=front
                    // Native pixel comparison is exact; small scene changes are never missed by a
                    // sparse checksum. Unchanged copies must not invalidate every Gaussian layer.
                    if(previous==null || !target.sameAs(previous)) {
                        previousFront=previous
                        front=target
                        GlassBackdrop.invalidateVideo(owner)
                    }
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
        } catch (_: IllegalArgumentException) {pending.remove(target)}
    }
    fun draw(canvas: Canvas, pane: View) {
        val surface = source ?: return
        val bitmap = front ?: return
        pane.getLocationOnScreen(ownerPosition)
        if(!waitingForFormat && surface.holder.surface.isValid || lastGeometry.isEmpty) {
            surface.getLocationOnScreen(sourcePosition)
            lastGeometry.set(sourcePosition[0].toFloat(),sourcePosition[1].toFloat(),
                sourcePosition[0]+surface.width*surface.scaleX,sourcePosition[1]+surface.height*surface.scaleY)
        }
        destination.set(lastGeometry)
        destination.offset(-ownerPosition[0].toFloat(),-ownerPosition[1].toFloat())
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}
