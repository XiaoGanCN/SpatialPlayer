package org.spatialplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/** Glass container using the reference GPU lens renderer; the native HDR surface stays separate. */
class LiquidGlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    var cornerRadiusPx = dp(28f)
    var bevelWidthPx = dp(18f)
    var refractionPx = dp(12f)
    var refractionFalloff = 1.6f
    var dispersionStrength = .10f
    var blurRadiusPx = dp(12f)
    var specularStrength = 1f
    var saturation = 1.06f
    var tintColor = 0x18FFFFFF
    var glassTintColor = 0x00000000
    var dimAmount = 0f
    private val glass = GlassDrawable(this).apply {
        callback = object : android.graphics.drawable.Drawable.Callback {
            override fun invalidateDrawable(who: android.graphics.drawable.Drawable) { invalidate() }
            override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, whenMs: Long) { postDelayed(what, (whenMs-android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)) }
            override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { removeCallbacks(what) }
        }
    }
    private val video = VideoGlassBackdrop(this)
    var backdropSource: SurfaceView?
        get() = video.source
        set(value) { video.source = value; if (isAttachedToWindow) start() }
    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setPath(SmoothCorners.path(android.graphics.RectF(0f,0f,width.toFloat(),height.toFloat()),cornerRadiusPx))
            }
        }
        clipToOutline = true
    }
    fun setBackdropFrameSize(width: Int, height: Int) { video.setFrameSize(width,height); start() }
    fun start() { if (isAttachedToWindow) video.start() }
    fun stop() = video.stop()
    fun setAmbientColors(listener: ((Int,Int,Int,Int)->Unit)?) { video.ambientColors=listener;if(listener!=null)start() }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassBackdrop.watch(this)
        LightSourceController.register(this)
        start()
    }
    override fun onDetachedFromWindow() {
        video.release()
        GlassBackdrop.unwatch(this)
        LightSourceController.unregister(this)
        glass.release()
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        glass.setBounds(0,0,w,h)
        invalidateOutline()
    }
    override fun draw(canvas: Canvas) { if (!GlassBackdrop.capturing) super.draw(canvas) }
    override fun onDraw(canvas: Canvas) {
        glass.style = GlassStyle(cornerRadiusPx = cornerRadiusPx, bevelWidthPx = bevelWidthPx,
            refractionPx = refractionPx, falloff = refractionFalloff, dispersion = dispersionStrength,
            specularStrength = specularStrength, saturation = saturation, blurRadiusPx = blurRadiusPx,
            tint = tintColor, adaptiveTint = dimAmount == 0f, body = glassTintColor, dimAmount = dimAmount)
        glass.draw(canvas)
        super.onDraw(canvas)
    }
    fun pressForTest(amount: Float, x: Float, y: Float) {
        glass.setTouchPoint(x,y)
        glass.state = if (amount > 0f) intArrayOf(android.R.attr.state_pressed) else intArrayOf()
        invalidate()
    }
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
