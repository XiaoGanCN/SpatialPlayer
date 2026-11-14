// Adapted from QWEA0/Liquid-Glass-Android, MIT (see bundled license).
package org.spatialplayer.ui

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.IdentityHashMap

/** Record the backdrop without drawing active ancestors or replaying ScrollView edge effects. */
internal class BackdropCapture {
    private val visible=android.graphics.Rect()
    private data class TextContent(val text:String,val color:Int,val size:Float,
        val face:android.graphics.Typeface?,val flags:Int,val spacing:Float,val gravity:Int,
        val width:Int,val height:Int,val padding:android.graphics.Rect,val layout:android.text.Layout?,
        val maxLines:Int,val ellipsize:android.text.TextUtils.TruncateAt?,val scaleX:Float)
    private val textContent=java.util.WeakHashMap<android.widget.TextView,TextContent>()
    private fun cacheableText(view:android.widget.TextView)=view.text is String &&
        (view.background==null || GlassInstaller.material(view)!=null) && view.compoundDrawables.all {it==null}
    private fun textContent(view:android.widget.TextView)=TextContent(view.text.toString(),view.currentTextColor,
        view.textSize,view.typeface,view.paintFlags,view.letterSpacing,view.gravity,view.width,view.height,
        android.graphics.Rect(view.paddingLeft,view.paddingTop,view.paddingRight,view.paddingBottom),
        view.layout,view.maxLines,view.ellipsize,view.textScaleX)
    /** Ignore container/material invalidation, but inspect text/images inside native glass rows. */
    fun foregroundDirty(view:View):Boolean {
        if(!view.isShown || view.alpha<=0f || view is androidx.compose.ui.platform.AbstractComposeView || view is TopFadeBlurView)return false
        if(view is ViewGroup)return (0 until view.childCount).any {foregroundDirty(view.getChildAt(it))}
        // Slider/switch display lists reference their live Compose nodes directly.
        if(view is GlassSlider || view is GlassSwitch)return false
        if(!(view.isDirty || view.isLayoutRequested) || !view.getLocalVisibleRect(visible))return false
        if(view.isLayoutRequested)return true
        // A material redraw can dirty an otherwise unchanged button label. Plain labels have
        // a stable presentation; editable/spanned text retains full native dirty semantics.
        if(view is android.widget.TextView && cacheableText(view))return textContent[view]!=textContent(view)
        return true
    }
    fun contentDirty(view: View): Boolean {
        if(!view.isShown || view.alpha<=0f || view is androidx.compose.ui.platform.AbstractComposeView || view is TopFadeBlurView || GlassInstaller.isGlass(view))return false
        if(view is ViewGroup)return (0 until view.childCount).any { contentDirty(view.getChildAt(it)) }
        return view.isDirty || view.isLayoutRequested
    }
    fun draw(canvas: Canvas, source: View, glass: View, includeGlass: Boolean = false) {
        val ancestors = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        var parent = glass.parent
        while (parent is View) { ancestors.add(parent); parent = parent.parent }
        record(canvas, source, glass, ancestors, includeGlass)
    }

    private fun record(canvas: Canvas, view: View, glass: View, ancestors: Set<View>, includeGlass: Boolean) {
        if (view is androidx.compose.ui.platform.AbstractComposeView || view is TopFadeBlurView || view === glass || view.visibility != View.VISIBLE || view.alpha <= 0f) return
        if (canvas.quickReject(0f,0f,view.width.toFloat(),view.height.toFloat())) return
        // A neighboring glass display list can refer back to this pane. Never sample it.
        if (!includeGlass && GlassInstaller.isGlass(view) && view !in ancestors) return
        if (view !is ViewGroup) {
            val dirty = view.isDirty
            view.draw(canvas)
            if(GlassBackdrop.snapshotting && view is android.widget.TextView && cacheableText(view))textContent[view]=textContent(view)
            // Public draw clears dirty flags. A pending text/image change must still reach its
            // normal display list, rather than being consumed by this offscreen recording.
            if (dirty) view.invalidate()
            return
        }
        // Calling an ancestor's draw() clears its dirty state and replays stateful edge effects.
        // Record its background and descendants explicitly instead. Its real display list stays intact.
        view.background?.draw(canvas)
        val save = canvas.save()
        if (view.clipChildren) canvas.clipRect(0, 0, view.width, view.height)
        if (view.clipToPadding) canvas.clipRect(view.paddingLeft, view.paddingTop,
            view.width-view.paddingRight, view.height-view.paddingBottom)
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child === glass || child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            val childSave = canvas.save()
            canvas.translate((child.left-view.scrollX).toFloat(), (child.top-view.scrollY).toFloat())
            canvas.concat(child.matrix)
            if (child.alpha < 1f) canvas.saveLayerAlpha(0f, 0f, child.width.toFloat(), child.height.toFloat(), (child.alpha*255).toInt())
            record(canvas, child, glass, ancestors, includeGlass)
            canvas.restoreToCount(childSave)
        }
        canvas.restoreToCount(save)
        view.foreground?.draw(canvas)
    }
}
