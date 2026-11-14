package org.spatialplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import org.spatialplayer.ui.GlassInstaller

/** Exercises bitmap rendering with a real initialized backdrop, not just an empty fallback. */
class GlassRenderProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pane = FrameLayout(this).apply {
            tag = R.id.glass_role_button
            setBackgroundResource(R.drawable.bg_glass_button)
        }
        setContentView(pane)
        GlassInstaller.apply(pane)
        pane.postDelayed({
            check(org.spatialplayer.ui.backdrop.NativeBackdropRenderer.recordings > 0) { "GPU lens was not initialized" }
            val glass=checkNotNull(GlassInstaller.material(pane))
            // Production uses the stateful glass directly. Keep the historical masked-ripple
            // regression explicit so future wrappers cannot reintroduce software shader crashes.
            val ripple=RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF),glass,
                org.spatialplayer.ui.SmoothMask(50f))
            check(ripple.findDrawableByLayerId(android.R.id.mask) != null) { "No explicit ripple mask" }
            val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                check(!canvas.isHardwareAccelerated)
                // Draw the shader-backed content directly, then with a pressed ripple around it.
                ripple.getDrawable(0).apply {
                    setBounds(0, 0, 160, 100)
                    draw(canvas)
                }
                ripple.setBounds(0, 0, 160, 100)
                ripple.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
                ripple.draw(canvas)
                Log.i("GlassRenderProbe", "PASS: initialized glass and pressed ripple render on software canvas")
            } finally {
                bitmap.recycle()
                finish()
            }
        }, 500L)
    }
}
