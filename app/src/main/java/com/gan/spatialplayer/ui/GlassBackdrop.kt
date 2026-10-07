package com.gan.spatialplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The window's own content, captured small, for glass that is not over the video.
 *
 * `LiquidGlassView` reads its backdrop from a `SurfaceView` with `PixelCopy`, which is the only way to
 * see the picture. Everything else in the app - the library, the settings screen, every chip and
 * button - has only the window's own drawing behind it, and that cannot be read back with `PixelCopy`
 * without asking the compositor for the very buffer being drawn into.
 *
 * It *can* be re-rendered: the view tree is asked to draw itself into a small bitmap, exactly as the
 * framework does for a screenshot. The one thing that would ruin it is recursion - the glass draws
 * over the backdrop it is sampling - so [capturing] is raised while the tree is drawn and every glass
 * surface draws nothing at that moment. The capture therefore contains the wallpaper, the ambient
 * wash, the list, the artwork: everything except glass, which is precisely what glass should refract.
 *
 * The capture is throttled and only happens while a glass surface is on screen. It costs a software
 * re-render of the hierarchy at reduced resolution, not a frame of GPU work.
 */
object GlassBackdrop {

    /** Longest edge of the captured bitmap. Above this the frost has detail it cannot show. */
    private const val CAPTURE_MAX = 768

    /** Minimum gap between captures. Screens change at human speed; this is not a frame loop. */
    private const val MIN_INTERVAL_MS = 80L

    /** True while the tree is being drawn into the capture, so glass can stand down. */
    @Volatile
    var capturing: Boolean = false
        private set

    private var bitmap: Bitmap? = null
    private var scale = 1f
    private var rootLeft = 0
    private var rootTop = 0
    private var lastCaptureAt = 0L
    private var root: View? = null
    private var watchers = 0
    private var listener: ViewTreeObserver.OnPreDrawListener? = null

    val image: Bitmap? get() = bitmap
    val imageScale: Float get() = scale
    val originX: Int get() = rootLeft
    val originY: Int get() = rootTop

    /** Called by each glass surface as it appears; the capture runs while at least one is attached. */
    fun watch(view: View) {
        watchers++
        val decor = view.rootView
        if (root !== decor) {
            detachListener()
            root = decor
            rootLeft = 0
            rootTop = 0
        }
        attachListener()
        requestCapture(force = true)
    }

    fun unwatch(view: View) {
        watchers = max(0, watchers - 1)
        if (watchers == 0) detachListener()
    }

    private fun attachListener() {
        val target = root ?: return
        if (listener != null) return
        val created = ViewTreeObserver.OnPreDrawListener {
            // Before the frame is drawn, so the capture reflects this frame's layout. At most every
            // MIN_INTERVAL_MS, which is what keeps a scrolling list from re-rendering per frame.
            requestCapture(force = false)
            true
        }
        listener = created
        target.viewTreeObserver.addOnPreDrawListener(created)
    }

    private fun detachListener() {
        val target = root
        val created = listener
        if (target != null && created != null && target.viewTreeObserver.isAlive) {
            target.viewTreeObserver.removeOnPreDrawListener(created)
        }
        listener = null
    }

    /** Renders the tree into [bitmap]. Safe to call from anywhere on the main thread. */
    fun requestCapture(force: Boolean) {
        val target = root ?: return
        if (target.width <= 0 || target.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastCaptureAt < MIN_INTERVAL_MS) return
        lastCaptureAt = now

        val w = target.width
        val h = target.height
        val s = (CAPTURE_MAX.toFloat() / max(w, h)).coerceAtMost(1f)
        val bw = (w * s).roundToInt().coerceAtLeast(2)
        val bh = (h * s).roundToInt().coerceAtLeast(2)

        val current = bitmap
        val target2 = if (current != null && current.width == bw && current.height == bh) {
            current
        } else {
            current?.recycle()
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }

        capturing = true
        try {
            val canvas = Canvas(target2)
            canvas.scale(bw.toFloat() / w, bh.toFloat() / h)
            target.draw(canvas)
        } catch (_: Throwable) {
            // A capture that fails leaves the previous one in place, which is still the right
            // backdrop a frame out of date.
        } finally {
            capturing = false
        }
        scale = bw.toFloat() / w
    }
}
