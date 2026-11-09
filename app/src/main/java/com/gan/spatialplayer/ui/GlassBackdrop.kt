package com.gan.spatialplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** One SDR backdrop per window. Video remains on its independent native HDR surface. */
object GlassBackdrop {
    data class Frame(val image: Bitmap, val scale: Float, val originX: Int, val originY: Int)
    private class Scene(val root: View) {
        val views = java.util.Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        var frame: Frame? = null
        var lastCapture = 0L
        var dirty = true
        lateinit var draw: ViewTreeObserver.OnPreDrawListener
        lateinit var scroll: ViewTreeObserver.OnScrollChangedListener
        lateinit var layout: ViewTreeObserver.OnGlobalLayoutListener
    }
    private val scenes = IdentityHashMap<View, Scene>()
    private val owners = IdentityHashMap<View, Scene>()
    private var latest: Frame? = null
    var capturing = false
        private set
    val image: Bitmap? get() = latest?.image
    fun frameFor(view: View): Frame? = owners[view]?.frame
    fun watch(view: View) {
        if (owners.containsKey(view)) return
        val root = view.rootView
        val scene = scenes[root] ?: Scene(root).also { s ->
            scenes[root] = s
            s.draw = ViewTreeObserver.OnPreDrawListener { capture(s, false); true }
            s.scroll = ViewTreeObserver.OnScrollChangedListener { s.dirty = true }
            s.layout = ViewTreeObserver.OnGlobalLayoutListener { s.dirty = true }
            root.viewTreeObserver.addOnPreDrawListener(s.draw)
            root.viewTreeObserver.addOnScrollChangedListener(s.scroll)
            root.viewTreeObserver.addOnGlobalLayoutListener(s.layout)
        }
        owners[view] = scene
        scene.views.add(view)
        scene.dirty = true
    }
    fun unwatch(view: View) {
        val scene = owners.remove(view) ?: return
        scene.views.remove(view)
        if (scene.views.isNotEmpty()) return
        val observer = scene.root.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnPreDrawListener(scene.draw)
            observer.removeOnScrollChangedListener(scene.scroll)
            observer.removeOnGlobalLayoutListener(scene.layout)
        }
        scenes.remove(scene.root)
        // Display lists can still reference a previous frame. Let the bitmap be reclaimed safely.
        if (latest === scene.frame) latest = null
    }
    fun requestCapture(force: Boolean) { scenes.values.toList().forEach { capture(it, force) } }
    private fun capture(scene: Scene, force: Boolean) {
        val root = scene.root
        if (capturing || root.width <= 0 || root.height <= 0 || !root.isShown) return
        val now = SystemClock.uptimeMillis()
        if (!force && (now - scene.lastCapture < 48L || (!scene.dirty && now - scene.lastCapture < 500L))) return
        val scale = (640f / max(root.width, root.height)).coerceAtMost(1f)
        val w = (root.width * scale).roundToInt().coerceAtLeast(2)
        val h = (root.height * scale).roundToInt().coerceAtLeast(2)
        val previous = scene.frame?.image
        val bitmap = if (previous != null && previous.width == w && previous.height == h) previous
            else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val hidden = scene.views.map { it to it.alpha }
        capturing = true
        try {
            // Exclude each pane's entire subtree, not only its background: otherwise a pane
            // refracts its own labels and creates a blurred double image beneath the real text.
            hidden.forEach { (view, _) -> view.alpha = 0f }
            bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            root.draw(canvas)
            val location = IntArray(2)
            root.getLocationInWindow(location)
            val frame = Frame(bitmap, scale, location[0], location[1])
            scene.frame = frame
            latest = frame
            scene.lastCapture = now
            scene.dirty = false
        } catch (error: IllegalArgumentException) {
            android.util.Log.w("GlassBackdrop", "Backdrop capture unavailable", error)
            scene.lastCapture = now
        } finally {
            hidden.forEach { (view, alpha) -> view.alpha = alpha }
            capturing = false
        }
    }
}
