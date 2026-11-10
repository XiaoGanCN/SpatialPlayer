package com.gan.spatialplayer.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewTreeObserver
import java.util.IdentityHashMap

/** GPU display-list backdrops. No software screenshots, bitmap blur, or polling of UI windows. */
object GlassBackdrop {
    private class Scene(val root: View) {
        val panes = LinkedHashSet<View>()
        var video: VideoGlassBackdrop? = null
        lateinit var scroll: ViewTreeObserver.OnScrollChangedListener
    }
    private val scenes = IdentityHashMap<View, Scene>()
    private val owners = IdentityHashMap<View, Scene>()
    private val capture = BackdropCapture()
    private var depth = 0
    val capturing: Boolean get() = depth > 0

    fun watch(view: View) {
        if (owners.containsKey(view)) return
        val root = view.rootView
        val scene = scenes[root] ?: Scene(root).also { scene ->
            scenes[root] = scene
            scene.scroll = ViewTreeObserver.OnScrollChangedListener { scene.panes.forEach { it.invalidate() } }
            root.viewTreeObserver.addOnScrollChangedListener(scene.scroll)
        }
        scene.panes.add(view)
        owners[view] = scene
    }
    fun unwatch(view: View) {
        val scene = owners.remove(view) ?: return
        val root = scene.root
        scene.panes.remove(view)
        if (scene.panes.isNotEmpty() || scene.video != null) return
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnScrollChangedListener(scene.scroll)
        scenes.remove(root)
    }
    internal fun video(owner: View, video: VideoGlassBackdrop?) {
        watch(owner)
        scenes[owner.rootView]?.video = video
    }
    internal fun invalidateVideo(owner: View) { scenes[owner.rootView]?.panes?.forEach { if (it.isShown) it.invalidate() } }
    internal fun hasVisiblePanes(owner: View): Boolean = scenes[owner.rootView]?.panes?.any { it.isShown && it.alpha > .01f } == true

    internal fun draw(canvas: Canvas, owner: View) {
        val root = owner.rootView
        val scene = scenes[root]
        val video = scene?.video ?: activityRoot(owner)?.let { scenes[it]?.video }
        if (video != null) {
            canvas.drawColor(Color.BLACK)
            video.draw(canvas, owner)
            return
        }
        if (root === owner) { canvas.drawColor(0xFF14171B.toInt()); return }
        val origin = IntArray(2)
        val position = IntArray(2)
        root.getLocationOnScreen(origin)
        owner.getLocationOnScreen(position)
        val save = canvas.save()
        canvas.translate((origin[0] - position[0]).toFloat(), (origin[1] - position[1]).toFloat())
        depth++
        try { capture.draw(canvas, root, owner) } finally { depth--; canvas.restoreToCount(save) }
    }
    private fun activityRoot(view: View): View? {
        var context = view.context
        while (context is ContextWrapper) {
            if (context is Activity) return context.window.decorView
            context = context.baseContext
        }
        return null
    }
}
