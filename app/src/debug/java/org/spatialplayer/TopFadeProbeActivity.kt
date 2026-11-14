package org.spatialplayer

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import org.spatialplayer.ui.TopFadeBlurView

/** Real GPU stripe fixture: a contrast ramp exposes whether blur progressively becomes sharp. */
class TopFadeProbeActivity : Activity() {
    private var reversed = false
    private lateinit var pattern: View
    internal fun changePattern() { reversed = !reversed; pattern.invalidate() }
    private var probe: android.content.BroadcastReceiver? = null
    override fun onDestroy() { probe?.let { unregisterReceiver(it) }; super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = DebugProbes.registerSettings(this)
        pattern = object : View(this) {
            val paint = Paint()
            override fun onDraw(canvas: Canvas) {
                if(intent.getBooleanExtra("solid_color",false)) {
                    canvas.drawColor(if(reversed)Color.BLUE else Color.RED)
                    return
                }
                val step = 6*resources.displayMetrics.density
                for (i in 0..(width/step).toInt()) {
                    paint.color = if((i%2==0) != reversed) Color.WHITE else Color.BLACK
                    canvas.drawRect(i*step,0f,(i+1)*step,height.toFloat(),paint)
                }
            }
        }
        // Invalidate a descendant, as a loaded thumbnail would inside RecyclerView.
        val sourceGroup = FrameLayout(this).apply {
            addView(FrameLayout(this@TopFadeProbeActivity).apply {
                if(intent.getBooleanExtra("nested_glass",false))background=org.spatialplayer.ui.GlassDrawable(this)
                addView(pattern,FrameLayout.LayoutParams(-1,-1))
            },FrameLayout.LayoutParams(-1,-1))
        }
        setContentView(FrameLayout(this).apply {
            addView(sourceGroup,FrameLayout.LayoutParams(-1,-1))
            addView(TopFadeBlurView(this@TopFadeProbeActivity).apply { sourceView = sourceGroup },FrameLayout.LayoutParams(-1,(300*resources.displayMetrics.density).toInt()))
        })
    }
}
