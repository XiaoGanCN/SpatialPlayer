package org.spatialplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.spatialplayer.ui.backdrop.NativeBackdropRenderer
import org.spatialplayer.ui.components.LiquidSlider
import kotlin.math.roundToInt

/** Backdrop's real spring/lens slider, with native SeekBar settings and accessibility contracts. */
class GlassSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatSeekBar(context,attrs) {
    private var model: androidx.compose.runtime.MutableFloatState? = null
    private var held=false
    private var fromCompose=false
    private var callback: SeekBar.OnSeekBarChangeListener?=null
    internal val glassEngaged get()=held
    private val renderer=NativeBackdropRenderer(this) { backdrop ->
        Box(Modifier.fillMaxSize().padding(horizontal=32.dp)) {
            LiquidSlider({ model?.floatValue ?: progress.toFloat() }, { value ->
                val snapped=value.roundToInt().coerceIn(min,max)
                model?.floatValue=value.coerceIn(min.toFloat(),max.toFloat())
                fromCompose=true
                try { progress=snapped } finally { fromCompose=false }
            },min.toFloat()..max.coerceAtLeast(min+1).toFloat(),.01f,backdrop,Modifier.fillMaxSize())
        }
    }
    init {
        model=mutableFloatStateOf(progress.toFloat())
        thumb=null;background=null;minimumHeight=dp(48f)
        setPadding(0,0,0,0)
        GlassInstaller.watch(this)
    }
    override fun setProgress(progress: Int) {
        super.setProgress(progress)
        if(!fromCompose)model?.floatValue=super.getProgress().toFloat()
        GlassBackdrop.materialChanged(this)
        invalidate()
    }
    override fun setOnSeekBarChangeListener(listener: SeekBar.OnSeekBarChangeListener?) {
        callback=listener
        super.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if(!fromCompose)model?.floatValue=value.toFloat()
                listener?.onProgressChanged(this@GlassSlider,value,fromUser || fromCompose)
            }
            override fun onStartTrackingTouch(bar: SeekBar?)=Unit
            override fun onStopTrackingTouch(bar: SeekBar?)=Unit
        })
    }
    override fun onMeasure(w: Int,h: Int) { super.onMeasure(w,h);setMeasuredDimension(measuredWidth,resolveSize(dp(48f),h)) }
    override fun onDraw(canvas: Canvas) {
        if(!GlassBackdrop.capturing && canvas.isHardwareAccelerated) {
            if(GlassBackdrop.snapshotting)renderer.drawCached(canvas) else renderer.draw(canvas)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled)return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN) { held=true;isPressed=true;parent?.requestDisallowInterceptTouchEvent(true);callback?.onStartTrackingTouch(this) }
        // Keep a full-height native touch target, including the generous track-end inset.
        val projected=MotionEvent.obtain(event)
        val inset=dp(32f).toFloat()
        projected.setLocation(event.x.coerceIn(inset+1,(width-inset-1).coerceAtLeast(inset+1)),height/2f)
        try { renderer.touch(projected) } finally { projected.recycle() }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
            held=false;isPressed=false;parent?.requestDisallowInterceptTouchEvent(false);callback?.onStopTrackingTouch(this)
            post { if(!held)model?.floatValue=progress.toFloat() }
        }
        return true
    }
    private fun dp(value:Float)=(value*resources.displayMetrics.density).roundToInt()
}
