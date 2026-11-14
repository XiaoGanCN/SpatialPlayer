package org.spatialplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.spatialplayer.ui.GlassInstaller
import org.spatialplayer.ui.backdrop.NativeBackdropRenderer

/** Tests live RenderNode pixels through the actual cached Gaussian/lens, not just invalidations. */
class LiveGlassProbeActivity:AppCompatActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        var blue=false
        val source=object:View(this) {
            override fun onDraw(canvas:Canvas) {canvas.drawColor(if(blue)0xFF1166DD.toInt() else 0xFFDD2244.toInt())}
        }
        val pane=FrameLayout(this).apply {tag=R.id.glass_role_panel;setBackgroundResource(R.drawable.bg_glass_panel)}
        val root=FrameLayout(this).apply {
            addView(source,FrameLayout.LayoutParams(-1,-1))
            addView(pane,FrameLayout.LayoutParams(dp(240),dp(160),Gravity.CENTER))
        }
        setContentView(root);GlassInstaller.apply(root)
        val handler=Handler(mainLooper)
        val samples=mutableListOf<Int>()
        val initialRecords=NativeBackdropRenderer.recordings
        fun capture(step:Int) {
            val bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(window,bitmap,{result->
                try {
                    check(result==PixelCopy.SUCCESS)
                    val position=IntArray(2);pane.getLocationInWindow(position)
                    val pixel=bitmap.getPixel(position[0]+pane.width/2,position[1]+pane.height/2)
                    samples.add(pixel)
                    check(if(blue)Color.blue(pixel)>Color.red(pixel)+60 else Color.red(pixel)>Color.blue(pixel)+60) {
                        "Cached lens has stale pixels at step $step: ${Integer.toHexString(pixel)}"
                    }
                    if(step<3) {
                        blue=!blue;source.invalidate()
                        handler.postDelayed({capture(step+1)},100L)
                    } else {
                        Log.i("LiveGlassProbe","PASS: four live source flips through cached GPU lens; records=${NativeBackdropRenderer.recordings-initialRecords}; pixels=${samples.map {Integer.toHexString(it)}}")
                        finish()
                    }
                } catch(error:Throwable) {Log.e("LiveGlassProbe","FAIL: live source",error);finish()}
                finally {bitmap.recycle()}
            },handler)
        }
        handler.postDelayed({capture(0)},900L)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
}
