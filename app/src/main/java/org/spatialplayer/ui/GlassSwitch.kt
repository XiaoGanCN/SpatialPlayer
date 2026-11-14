package org.spatialplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.spatialplayer.ui.backdrop.NativeBackdropRenderer
import org.spatialplayer.ui.components.LiquidToggle

/** Backdrop's real deforming switch. CompoundButton retains native row/accessibility behavior. */
class GlassSwitch(context: Context): CompoundButton(context) {
    private var selected: androidx.compose.runtime.MutableState<Boolean>? = null
    private val renderer=NativeBackdropRenderer(this) { backdrop ->
        Box(Modifier.size(80.dp,48.dp),contentAlignment=Alignment.Center) {
            LiquidToggle({selected?.value ?: isChecked},{isChecked=it},backdrop,Modifier.size(64.dp,48.dp))
        }
    }
    init { selected=mutableStateOf(isChecked);buttonDrawable=null;background=null;isClickable=true;isFocusable=true;GlassInstaller.watch(this) }
    override fun setChecked(checked: Boolean) {
        super.setChecked(checked);selected?.value=checked
        GlassBackdrop.materialChanged(this)
        invalidate()
    }
    override fun onMeasure(w: Int,h: Int) {setMeasuredDimension(resolveSize(dp(80),w),resolveSize(dp(48),h))}
    override fun onDraw(canvas: Canvas) {
        if(!GlassBackdrop.capturing && canvas.isHardwareAccelerated) {
            if(GlassBackdrop.snapshotting)renderer.drawCached(canvas) else renderer.draw(canvas)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!isEnabled)return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN)parent?.requestDisallowInterceptTouchEvent(true)
        renderer.touch(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false)
        return true
    }
    override fun getAccessibilityClassName():CharSequence="android.widget.Switch"
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {super.onInitializeAccessibilityNodeInfo(info);info.isCheckable=true;info.isChecked=isChecked}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
