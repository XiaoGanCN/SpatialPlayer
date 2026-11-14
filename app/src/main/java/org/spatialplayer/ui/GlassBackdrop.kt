package org.spatialplayer.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewTreeObserver
import java.util.IdentityHashMap

/** GPU display-list backdrops. No software screenshots, bitmap blur, or polling of UI windows. */
object GlassBackdrop {
    private class Scene(val root: View, val activityRoot: View?) {
        val panes = LinkedHashSet<View>()
        var video: VideoGlassBackdrop? = null
        var videoOwner: View? = null
        val visibleBounds = android.graphics.Rect()
        lateinit var redraw: ViewTreeObserver.OnPreDrawListener
        lateinit var scroll: ViewTreeObserver.OnScrollChangedListener
    }
    private val scenes = IdentityHashMap<View, Scene>()
    private val owners = IdentityHashMap<View, Scene>()
    private val capture = BackdropCapture()
    private var depth = 0
    val capturing: Boolean get() = depth > 0

    fun watch(view: View) {
        val root = view.rootView
        if (owners[view]?.root === root) return
        if (owners.containsKey(view)) unwatch(view)
        val scene = scenes[root] ?: Scene(root, activityRoot(view)).also { scene ->
            scenes[root] = scene
            scene.scroll = ViewTreeObserver.OnScrollChangedListener { invalidatePanes(scene,positionOnly=true) }
            root.viewTreeObserver.addOnScrollChangedListener(scene.scroll)
            scene.redraw=ViewTreeObserver.OnPreDrawListener {
                if(scene.video?.geometryChanged()==true)related(root).forEach {invalidatePanes(it)}
                if(capture.contentDirty(root))invalidatePanes(scene)
                scene.panes.forEach {org.spatialplayer.ui.backdrop.NativeBackdropRenderer.invalidateMoved(it)}
                true
            }
            root.viewTreeObserver.addOnPreDrawListener(scene.redraw)
        }
        scene.panes.add(view)
        owners[view] = scene
        (scene.video ?: scene.activityRoot?.let {scenes[it]?.video})?.wake()
    }
    fun unwatch(view: View) {
        val scene = owners.remove(view) ?: return
        val root = scene.root
        scene.panes.remove(view)
        if (scene.panes.isNotEmpty() || scene.video != null) return
        if (root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnScrollChangedListener(scene.scroll)
            root.viewTreeObserver.removeOnPreDrawListener(scene.redraw)
        }
        scenes.remove(root)
    }
    internal fun video(owner: View, video: VideoGlassBackdrop?) {
        watch(owner)
        scenes[owner.rootView]?.let { scene ->
            if (video != null || scene.videoOwner === owner) {
                scene.video = video
                scene.videoOwner = if (video != null) owner else null
            }
        }
    }
    internal var videoPreviewFrames = 0L
        private set
    private fun related(owner: View) = scenes.values.filter { it.root === owner.rootView || it.activityRoot === owner.rootView }
    internal fun videoDebug(owner: View) = related(owner).mapNotNull { it.video?.debugState() }.joinToString()
    internal fun videoReady(owner: View): Boolean = (scenes[owner.rootView]?.video ?: activityRoot(owner)?.let { scenes[it]?.video })?.hasFrame == true
    internal fun invalidateVideo(owner: View) {
        videoPreviewFrames++
        related(owner).forEach { invalidatePanes(it) }
    }
    private fun invalidatePanes(scene: Scene,positionOnly:Boolean=false) {
        scene.panes.forEach { pane ->
            if(pane.isShown && pane.getLocalVisibleRect(scene.visibleBounds)) {
                if(positionOnly)org.spatialplayer.ui.backdrop.NativeBackdropRenderer.invalidatePosition(pane)
                else org.spatialplayer.ui.backdrop.NativeBackdropRenderer.invalidate(pane)
                pane.invalidate()
            }
        }
    }
    internal fun invalidateSource(owner:View) {owners[owner]?.let {invalidatePanes(it)}}
    internal fun hasVisiblePanes(owner: View): Boolean = related(owner).any { scene -> scene.panes.any { it.isShown && it.alpha > .01f } }

    internal fun draw(canvas: Canvas, owner: View) {
        val root = owner.rootView
        val scene = scenes[root]
        val video = scene?.video ?: activityRoot(owner)?.let { scenes[it]?.video }
        if (video?.hasFrame == true) {
            canvas.drawColor(Color.BLACK)
            video.draw(canvas, owner)
            return
        }
        if (root === owner) { canvas.drawColor(0xFF14171B.toInt()); return }
        val source = scene?.activityRoot?.takeIf { it !== root } ?: root
        val origin = IntArray(2)
        val position = IntArray(2)
        source.getLocationOnScreen(origin)
        owner.getLocationOnScreen(position)
        val save = canvas.save()
        canvas.translate((origin[0] - position[0]).toFloat(), (origin[1] - position[1]).toFloat())
        depth++
        try { capture.draw(canvas, source, owner, includeGlass = source !== root) } finally { depth--; canvas.restoreToCount(save) }
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
