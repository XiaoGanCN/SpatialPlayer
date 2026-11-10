package com.gan.spatialplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatSeekBar

/** Broad touch target, precise native seeking/accessibility, and an expanding glass handle. */
class GlassSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatSeekBar(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val lens = GlassLensRenderer()
    private var engagement = 0f
    private var animator: ValueAnimator? = null
    private var lastTick = -1
    init {
        minimumHeight = dp(44f).toInt()
        setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        splitTrack = false
        thumb = null
        background = null
    }
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w,h)
        setMeasuredDimension(measuredWidth, resolveSize(dp(44f).toInt(),h))
    }
    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        val fraction = (progress-min).toFloat() / (max-min).coerceAtLeast(1)
        val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) right-(right-left)*fraction else left+(right-left)*fraction
        val y = height/2f
        val half = dp(2f + engagement)
        paint.color = 0x32FFFFFF
        rect.set(left,y-half,right,y+half); canvas.drawRoundRect(rect,half,half,paint)
        paint.color = if (isEnabled) Color.WHITE else 0x66FFFFFF
        rect.set(if (layoutDirection == View.LAYOUT_DIRECTION_RTL) x else left,y-half,
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) right else x,y+half)
        canvas.drawRoundRect(rect,half,half,paint)
        val hw = dp(11f + engagement*3f)
        val hh = dp(8f + engagement*2f)
        rect.set(x-hw,y-hh,x+hw,y+hh)
        paint.color = if (isPressed) 0x55FFFFFF else 0xF2FFFFFF.toInt()
        canvas.drawRoundRect(rect,hh,hh,paint)
        if (canvas.isHardwareAccelerated && isPressed && !GlassBackdrop.capturing) {
            val style = GlassStyle(cornerRadiusPx=hh, blurRadiusPx=dp(4f), bevelWidthPx=dp(8f), tint=0x30FFFFFF)
            lens.draw(canvas,this,GlassOptics.parameters(this,style,engagement,x,y,rect),GlassOptics.margin(style))
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); Haptics.touch(this); animateEngagement(1f); lastTick = progress }
            MotionEvent.ACTION_UP -> { Haptics.release(this); animateEngagement(0f); parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { animateEngagement(0f); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && progress != lastTick) {
            val step = maxOf(1,(max-min)/30)
            if (lastTick < 0 || kotlin.math.abs(progress-lastTick) >= step) { Haptics.tick(this);lastTick=progress }
        }
        return handled
    }
    private fun animateEngagement(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(engagement,target).apply {
            duration=160L
            addUpdateListener { engagement=it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { animator?.cancel();lens.release();super.onDetachedFromWindow() }
    private fun dp(value: Float) = value*resources.displayMetrics.density
}
