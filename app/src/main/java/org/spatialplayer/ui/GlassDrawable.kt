package org.spatialplayer.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import org.spatialplayer.ui.backdrop.BackdropSurface
import org.spatialplayer.ui.backdrop.NativeBackdropRenderer

/** Native drawable contract around Backdrop's actual effects; software masks use a safe G2 fill. */
class GlassDrawable(private val owner:View,material:GlassStyle=GlassStyle()):Drawable() {
    private val fallbackPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=1f;color=0x40FFFFFF}
    private val rect=RectF()
    private val path=Path()
    private var pathRadius=Float.NaN
    private val materialState=mutableStateOf(material)
    private val pressed=mutableStateOf(false)
    private val touch=mutableStateOf(Offset.Zero)
    private val renderer=NativeBackdropRenderer(owner) {backdrop->BackdropSurface(backdrop,materialState.value,pressed.value,owner.resources.displayMetrics.density,touch.value,owner.isClickable)}
    internal var renderedFrames=0L;private set
    var radiusPx=material.cornerRadiusPx
    var style:GlassStyle=material
        set(value){if(field==value)return;field=value;materialState.value=value;radiusPx=value.cornerRadiusPx;GlassBackdrop.invalidateSource(owner);invalidateSelf()}
    fun setTouchPoint(x:Float,y:Float){touch.value=Offset(x,y)}
    override fun isStateful()=true
    override fun onStateChange(state:IntArray):Boolean {
        val changed=pressed.value!=state.contains(android.R.attr.state_pressed)
        pressed.value=state.contains(android.R.attr.state_pressed)
        return changed
    }
    override fun onBoundsChange(bounds:android.graphics.Rect){rect.set(bounds);path.rewind()}
    override fun draw(canvas:Canvas){
        if(bounds.isEmpty)return
        if(canvas.isHardwareAccelerated && !GlassBackdrop.capturing && renderer.draw(canvas)){renderedFrames++;return}
        fallbackPaint.color=style.fallbackFill
        if(path.isEmpty || pathRadius!=radiusPx) {
            SmoothCorners.path(rect,radiusPx,path)
            pathRadius=radiusPx
        }
        canvas.drawPath(path,fallbackPaint);canvas.drawPath(path,rimPaint)
    }
    fun release()=renderer.detach()
    override fun setAlpha(alpha:Int){fallbackPaint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:android.graphics.ColorFilter?){fallbackPaint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Drawable")override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}
