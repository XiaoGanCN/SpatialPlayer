package org.spatialplayer.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle

/** The exact Kyant0 Shapes G2 contour, also used for native clipping and ripple masks. */
internal object SmoothCorners {
    fun path(rect: RectF, radius: Float, result: Path=Path()): Path {
        result.rewind()
        if(rect.isEmpty)return result
        val outline=RoundedRectangle(radius.dp).createOutline(Size(rect.width(),rect.height()),LayoutDirection.Ltr,Density(1f))
        when(outline) {
            is Outline.Generic -> result.set(outline.path.asAndroidPath())
            is Outline.Rectangle -> result.addRect(0f,0f,rect.width(),rect.height(),Path.Direction.CW)
            is Outline.Rounded -> result.addRoundRect(RectF(0f,0f,rect.width(),rect.height()),radius,radius,Path.Direction.CW)
        }
        result.offset(rect.left,rect.top)
        return result
    }
}
internal class SmoothMask(private val radius: Float):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=android.graphics.Color.WHITE}
    private val path=Path()
    override fun onBoundsChange(bounds:android.graphics.Rect) {SmoothCorners.path(RectF(bounds),radius,path)}
    override fun draw(canvas:Canvas){canvas.drawPath(path,paint)}
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
