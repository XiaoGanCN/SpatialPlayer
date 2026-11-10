package com.gan.spatialplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.CompoundButton
import android.view.accessibility.AccessibilityNodeInfo
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/** Current Apple-style oval handle: springing travel, optical rim, and a transparent pressed lens. */
class GlassSwitch(context: Context) : CompoundButton(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = RectF()
    private val lens = GlassLensRenderer()
    private var position = 0f
    private var expansion = 0f
    private var travel: ValueAnimator? = null
    private var press: ValueAnimator? = null
    private var startX = 0f
    private var startPosition = 0f
    private var dragging = false
    init { buttonDrawable=null; background=null; isClickable=true; isFocusable=true; setLayerType(View.LAYER_TYPE_NONE,null) }
    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        if (!changed) return
        travel?.cancel()
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) { position=if(checked)1f else 0f; invalidate();return }
        travel=ValueAnimator.ofFloat(position,if(checked)1f else 0f).apply {
            duration=260L;interpolator=FastOutSlowInInterpolator()
            addUpdateListener { position=it.animatedValue as Float;invalidate() };start()
        }
    }
    override fun onMeasure(w: Int,h: Int) { setMeasuredDimension(resolveSize(dp(64f).toInt(),w),resolveSize(dp(44f).toInt(),h)) }
    private fun drawTrack(canvas: Canvas) {
        val cx=width/2f;val cy=height/2f
        val halfWidth=dp(29f);val halfHeight=dp(16f)
        val track=RectF(cx-halfWidth,cy-halfHeight,cx+halfWidth,cy+halfHeight)
        paint.style=Paint.Style.FILL
        paint.shader=null
        paint.color=blend(0xFF3A3D43.toInt(),0xFF28C76F.toInt(),position)
        canvas.drawRoundRect(track,halfHeight,halfHeight,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(.5f);paint.color=0x28FFFFFF
        canvas.drawRoundRect(track,halfHeight,halfHeight,paint)
        paint.style=Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        val cx=width/2f;val cy=height/2f
        drawTrack(canvas)
        val directional=if(layoutDirection==LAYOUT_DIRECTION_RTL)1-position else position
        val x=cx+dp(11f)*(directional*2-1)
        val hw=dp(15.5f+expansion*2.5f);val hh=dp(12.5f+expansion*2f)
        shape.set(x-hw,cy-hh,x+hw,cy+hh)
        paint.alpha=255
        paint.shader=LinearGradient(0f,shape.top,0f,shape.bottom,intArrayOf(Color.WHITE,0xFFE4E8EB.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(shape,hh,hh,paint);paint.shader=null
        if (canvas.isHardwareAccelerated && expansion>.01f && !GlassBackdrop.capturing) {
            val style=GlassStyle(cornerRadiusPx=hh,blurRadiusPx=dp(4f),tint=0x25FFFFFF,bevelWidthPx=dp(10f),specularStrength=1.2f)
            lens.draw(canvas,this,GlassOptics.parameters(this,style,expansion,x,cy,shape),GlassOptics.margin(style)) { backdrop ->
                GlassBackdrop.draw(backdrop,this)
                drawTrack(backdrop)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX=event.x;startPosition=position;dragging=false;isPressed=true;travel?.cancel();parent?.requestDisallowInterceptTouchEvent(true);Haptics.touch(this);expand(1f);return true }
            MotionEvent.ACTION_MOVE -> {
                if(kotlin.math.abs(event.x-startX)>dp(4f))dragging=true
                if(dragging) { val direction=if(layoutDirection==LAYOUT_DIRECTION_RTL)-1 else 1;position=(startPosition+(event.x-startX)/dp(22f)*direction).coerceIn(0f,1f);invalidate() };return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed=false;expand(0f);parent?.requestDisallowInterceptTouchEvent(false)
                if(dragging) { val target=position>=.5f;if(target!=isChecked)isChecked=target else settle() } else performClick()
                Haptics.release(this);return true
            }
            MotionEvent.ACTION_CANCEL -> { isPressed=false;expand(0f);settle();parent?.requestDisallowInterceptTouchEvent(false);return true }
        }
        return false
    }
    override fun performClick(): Boolean { val result=super.performClick();Haptics.release(this);return result }
    private fun settle() { position=if(isChecked)1f else 0f;invalidate() }
    private fun expand(target: Float) { press?.cancel();press=ValueAnimator.ofFloat(expansion,target).apply { duration=160;addUpdateListener { expansion=it.animatedValue as Float;invalidate() };start() } }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(info);info.isCheckable=true;info.isChecked=isChecked }
    override fun onDetachedFromWindow() { travel?.cancel();press?.cancel();lens.release();super.onDetachedFromWindow() }
    private fun dp(value:Float)=value*resources.displayMetrics.density
    private fun blend(a:Int,b:Int,t:Float):Int=Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
}
