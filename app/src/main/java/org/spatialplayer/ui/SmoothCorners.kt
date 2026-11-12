package org.spatialplayer.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.*

/** Continuous-curvature corner profile, shared with the lens SDF. Capsules remain circular. */
internal object SmoothCorners {
    fun path(rect: RectF, radius: Float, result: Path = Path()): Path {
        result.rewind()
        val r = radius.coerceIn(0f, min(rect.width(),rect.height())/2f)
        if (r == 0f || r >= min(rect.width(),rect.height())/2f - .5f) {
            result.addRoundRect(rect,r,r,Path.Direction.CW)
            return result
        }
        val centres = arrayOf(rect.right-r to rect.top+r, rect.right-r to rect.bottom-r,
            rect.left+r to rect.bottom-r, rect.left+r to rect.top+r)
        centres.forEachIndexed { corner, (cx,cy) ->
            for (i in 0..32) {
                val angle = (-PI/2 + corner*PI/2 + i*PI/64)
                val c = cos(angle); val s = sin(angle)
                val x = cx + r * (sign(c)*abs(c).pow(2.0/2.4)).toFloat()
                val y = cy + r * (sign(s)*abs(s).pow(2.0/2.4)).toFloat()
                if (corner==0 && i==0) result.moveTo(x,y) else result.lineTo(x,y)
            }
        }
        result.close()
        return result
    }
}
internal class SmoothMask(private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val path = Path()
    override fun onBoundsChange(bounds: android.graphics.Rect) { SmoothCorners.path(RectF(bounds),radius,path) }
    override fun draw(canvas: Canvas) { canvas.drawPath(path,paint) }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
