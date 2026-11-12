// Adapted from QWEA0/Liquid-Glass-Android, MIT (see bundled license).
package org.spatialplayer.ui

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.IdentityHashMap

/** Record the backdrop without drawing active ancestors or replaying ScrollView edge effects. */
internal class BackdropCapture {
    fun draw(canvas: Canvas, source: View, glass: View, includeGlass: Boolean = false) {
        val ancestors = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        var parent = glass.parent
        while (parent is View) { ancestors.add(parent); parent = parent.parent }
        record(canvas, source, glass, ancestors, includeGlass)
    }

    private fun record(canvas: Canvas, view: View, glass: View, ancestors: Set<View>, includeGlass: Boolean) {
        if (view is TopFadeBlurView || view === glass || view.visibility != View.VISIBLE || view.alpha <= 0f) return
        if (canvas.quickReject(0f,0f,view.width.toFloat(),view.height.toFloat())) return
        // A neighboring glass display list can refer back to this pane. Never sample it.
        if (!includeGlass && GlassInstaller.isGlass(view) && view !in ancestors) return
        if (view !is ViewGroup) {
            val dirty = view.isDirty
            view.draw(canvas)
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
