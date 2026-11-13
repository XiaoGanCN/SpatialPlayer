package org.spatialplayer.ui.backdrop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.kyant.backdrop.Backdrop
import org.spatialplayer.ui.GlassBackdrop
import org.spatialplayer.ui.LightSourceController
import java.util.IdentityHashMap
import kotlin.math.atan2

internal val LocalGlassAngle = staticCompositionLocalOf<() -> Float> { { 30f } }

/** Real Backdrop compositions recorded into native backgrounds; native HDR stays on SurfaceView.
 * The attached, transparent decor bridge supplies a lifecycle/Compose owner, never a second visible UI.
 * Public draw() renders its local display list into the requesting pane's hardware canvas.
 */
internal class NativeBackdropRenderer(private val owner: View, private val content: @Composable (Backdrop) -> Unit) {
    companion object {
        private val renderers = IdentityHashMap<View, MutableSet<NativeBackdropRenderer>>()
        internal var frames = 0L; private set
        internal var recordings=0L;private set
        internal val hostCount get()=renderers.values.sumOf {it.size}
        fun invalidate(view: View) { renderers[view]?.toList()?.forEach { it.refresh() } }
    }
    private var host: BridgeView? = null
    private var hostRoot: ViewGroup? = null
    private var forwardingTouch=false
    private var revision by mutableIntStateOf(0)
    private var angle by mutableFloatStateOf(30f)
    private var drawing = false
    private val visibleBounds = android.graphics.Rect()
    private val displayList = android.graphics.RenderNode("native-backdrop")
    private var displayListDirty = true
    private val backdrop = object : Backdrop {
        override val isCoordinatesDependent = true
        override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
            recordings++
            revision // observed by the draw node, refreshed by scroll/video/source invalidation
            val offset = coordinates?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero
            withTransform({ translate(-offset.x,-offset.y) }) {
                GlassBackdrop.draw(drawContext.canvas.nativeCanvas,owner)
            }
        }
    }
    private inner class BridgeView(context: Context) : AbstractComposeView(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = if(forwardingTouch) super.dispatchTouchEvent(event) else false
        @Composable override fun Content() {
            CompositionLocalProvider(LocalGlassAngle provides { angle }) {
                Box(Modifier.fillMaxSize()) { content(backdrop) }
            }
        }
        override fun onDescendantInvalidated(child: View, target: View) {
            super.onDescendantInvalidated(child,target)
            displayListDirty=true
            if (!drawing && owner.isAttachedToWindow) owner.postInvalidateOnAnimation()
        }
    }
    init {
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { owner.post { attach() } }
            override fun onViewDetachedFromWindow(v: View) { detach() }
        })
        if (owner.isAttachedToWindow) owner.post { attach() }
    }
    private fun attach() {
        if (host != null || !owner.isAttachedToWindow) return
        var ctx: Context = owner.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx=ctx.baseContext
        val activity = ctx as? androidx.appcompat.app.AppCompatActivity ?: return
        val decor = owner.rootView as? ViewGroup ?: return
        // Platform Dialog windows do not install ViewTree owners themselves. The window
        // recomposer resolves from its decor root, not just from our individual bridge.
        if(decor.findViewTreeLifecycleOwner()==null) {
            decor.setViewTreeLifecycleOwner(activity)
            decor.setViewTreeSavedStateRegistryOwner(activity)
            decor.setViewTreeViewModelStoreOwner(activity)
        }
        val view = BridgeView(owner.context)
        view.setViewTreeLifecycleOwner(activity)
        view.setViewTreeSavedStateRegistryOwner(activity)
        view.setViewTreeViewModelStoreOwner(activity)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.alpha=0f
        view.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        decor.addView(view,android.widget.FrameLayout.LayoutParams(owner.width.coerceAtLeast(1),owner.height.coerceAtLeast(1)))
        hostRoot=decor
        host=view
        renderers.getOrPut(owner) { LinkedHashSet() }.add(this)
        refresh()
        owner.invalidate()
    }
    fun refresh() {
        // ScrollView keeps offscreen children attached. Invalidating their compositions on every
        // scroll/sensor sample forces work for the entire settings page instead of its viewport.
        if(!owner.isShown || !owner.getLocalVisibleRect(visibleBounds))return
        displayListDirty=true
        revision++
        val light=LightSourceController.lightFor(owner,LightSourceController.DEFAULT_X,LightSourceController.DEFAULT_Y)
        angle=(atan2(light.second,light.first)*180f/Math.PI.toFloat())
        owner.invalidate()
    }
    fun draw(canvas: Canvas): Boolean {
        val view=host ?: return false
        if (!canvas.isHardwareAccelerated || owner.width<=0 || owner.height<=0) return false
        layoutHost(view)
        if(displayList.width!=owner.width || displayList.height!=owner.height) {
            displayList.setPosition(0,0,owner.width,owner.height)
            displayListDirty=true
        }
        // A pane can be drawn normally and sampled again by the fade in the same frame.
        // Reuse its GPU display list; do not traverse the Compose tree for every sample.
        if(displayListDirty || !displayList.hasDisplayList()) {
            displayListDirty=false
            val recording=displayList.beginRecording(owner.width,owner.height)
            drawing=true
            try { view.draw(recording) } finally {drawing=false;displayList.endRecording()}
        }
        canvas.drawRenderNode(displayList)
        frames++
        return true
    }
    private fun layoutHost(view: BridgeView, forTouch: Boolean = false) {
        if(view.layoutParams.width!=owner.width || view.layoutParams.height!=owner.height) {
            view.layoutParams=view.layoutParams.apply {width=owner.width;height=owner.height}
        }
        if(view.measuredWidth!=owner.width || view.measuredHeight!=owner.height) {
            view.measure(View.MeasureSpec.makeMeasureSpec(owner.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(owner.height,View.MeasureSpec.EXACTLY))
        }
        // Drawing uses local coordinates. Moving this invisible decor sibling on every scroll
        // would run Compose global-position/layout work for each pane. Only input needs its
        // physical screen origin aligned with the native control's raw pointer coordinates.
        var x=view.left;var y=view.top
        if(forTouch) {
            val position=IntArray(2);val origin=IntArray(2)
            owner.getLocationOnScreen(position);hostRoot?.getLocationOnScreen(origin)
            x=position[0]-origin[0];y=position[1]-origin[1]
        }
        if(view.left!=x || view.top!=y || view.width!=owner.width || view.height!=owner.height) {
            view.layout(x,y,x+owner.width,y+owner.height)
        }
    }
    fun touch(event: MotionEvent): Boolean {
        val view=host ?: return false
        layoutHost(view,forTouch=true)
        // Compose consumes both local and raw screen coordinates. Rebase both after a native
        // control projects a generous hit target onto its visual track, including dialog offsets.
        val position=IntArray(2);owner.getLocationOnScreen(position)
        val properties=Array(event.pointerCount) { index -> MotionEvent.PointerProperties().also {event.getPointerProperties(index,it)} }
        val coordinates=Array(event.pointerCount) { index -> MotionEvent.PointerCoords().also {
            event.getPointerCoords(index,it);it.x+=position[0];it.y+=position[1]
        } }
        val forwarded=MotionEvent.obtain(event.downTime,event.eventTime,event.action,event.pointerCount,properties,coordinates,
            event.metaState,event.buttonState,event.xPrecision,event.yPrecision,event.deviceId,event.edgeFlags,event.source,event.flags)
        forwarded.offsetLocation(-position[0].toFloat(),-position[1].toFloat())
        forwardingTouch=true
        try { return view.dispatchTouchEvent(forwarded) } finally {forwardingTouch=false;forwarded.recycle()}
    }
    fun detach() {
        val view=host ?: return
        host=null
        displayList.discardDisplayList()
        displayListDirty=true
        view.disposeComposition()
        val root=hostRoot
        hostRoot=null
        android.os.Handler(android.os.Looper.getMainLooper()).post { if(root!=null && view.parent===root)root.removeView(view) }
        renderers[owner]?.let { it.remove(this);if(it.isEmpty())renderers.remove(owner) }
    }
}
