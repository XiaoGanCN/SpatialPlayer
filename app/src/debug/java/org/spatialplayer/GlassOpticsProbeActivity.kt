package org.spatialplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.spatialplayer.ui.GlassInstaller
import kotlin.math.sqrt

/** Measures the real GPU effect on a known high-frequency backdrop. No software capture substitute. */
class GlassOpticsProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=FrameLayout(this)
        root.background=object:Drawable() {
            val paint=Paint()
            override fun draw(canvas:Canvas) {
                for (x in 0 until bounds.width() step 8) {
                    paint.color=if(x/8%2==0)Color.WHITE else Color.BLACK
                    canvas.drawRect(x.toFloat(),0f,x+8f,bounds.height().toFloat(),paint)
                }
            }
            override fun setAlpha(alpha:Int)=Unit
            override fun setColorFilter(filter:android.graphics.ColorFilter?)=Unit
            @Deprecated("Deprecated") override fun getOpacity()=PixelFormat.OPAQUE
        }
        val pane=FrameLayout(this).apply {
            tag=R.id.glass_role_panel
            setBackgroundResource(R.drawable.bg_glass_panel)
        }
        root.addView(pane,FrameLayout.LayoutParams(dp(280),dp(180),Gravity.CENTER))
        setContentView(root)
        GlassInstaller.apply(root)
        root.postDelayed({
            val bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
            PixelCopy.request(window,bitmap,{ result ->
                try {
                    check(result==PixelCopy.SUCCESS)
                    val position=IntArray(2);pane.getLocationInWindow(position)
                    fun deviation(x:Int,y:Int):Double {
                        val pixels=(0 until 64).map { Color.red(bitmap.getPixel(x+it,y)).toDouble() }
                        val mean=pixels.average();return sqrt(pixels.sumOf { (it-mean)*(it-mean) }/pixels.size)
                    }
                    val raw=deviation(dp(12),position[1]+pane.height/2)
                    val blurred=deviation(position[0]+pane.width/2-32,position[1]+pane.height/2)
                    check(raw>100) { "Backdrop lost stripe contrast: $raw" }
                    check(blurred<20) { "Backdrop is not actually blurred: $blurred" }
                    Log.i("GlassOpticsProbe","PASS: GPU Gaussian blur measured sourceStd=$raw glassStd=$blurred")
                } catch(error:Throwable) { Log.e("GlassOpticsProbe","FAIL: GPU optics measurement",error) }
                finally { bitmap.recycle();finish() }
            },android.os.Handler(mainLooper))
        },900)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
}
