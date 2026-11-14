package org.spatialplayer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.spatialplayer.ui.GlassInstaller

/** Physical stripe boundaries must stay stationary as the lens grows on a held press. */
class ScreenSpaceGlassProbeActivity:AppCompatActivity() {
    private var probe:android.content.BroadcastReceiver?=null
    override fun onDestroy() {probe?.let {unregisterReceiver(it)};super.onDestroy()}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        probe=DebugProbes.registerSettings(this)
        val density=resources.displayMetrics.density
        val root=FrameLayout(this)
        root.addView(object:View(this) {
            val paint=Paint()
            override fun onDraw(canvas:Canvas) {
                val stripe=(32*density).toInt()
                for(i in 0..width/stripe) {
                    paint.color=if(i%2==0)Color.WHITE else Color.BLACK
                    canvas.drawRect((i*stripe).toFloat(),0f,((i+1)*stripe).toFloat(),height.toFloat(),paint)
                }
            }
        },FrameLayout.LayoutParams(-1,-1))
        root.addView(TextView(this).apply {
            tag="sampling_probe_button";setTag(R.id.glass_role,R.id.glass_role_button)
            setOnClickListener {};contentDescription="Sampling probe"
        },FrameLayout.LayoutParams((320*density).toInt(),(64*density).toInt(),Gravity.CENTER))
        setContentView(root);GlassInstaller.apply(root)
    }
}
