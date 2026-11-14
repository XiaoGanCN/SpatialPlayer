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
        fun invalidatePosition(view:View) {renderers[view]?.toList()?.forEach {it.owner.invalidate()}}
        fun invalidateMoved(view:View) {renderers[view]?.toList()?.forEach {it.checkPosition()}}
        fun invalidateLight(view:View) {renderers[view]?.toList()?.forEach {it.refreshLight()}}
    }
    private var host: BridgeView? = null
    private var hostRoot: ViewGroup? = null
    private var forwardingTouch=false
    private var angle by mutableFloatStateOf(30f)
    private var drawing = false
    private val visibleBounds = android.graphics.Rect()
    private val displayList = android.graphics.RenderNode("native-backdrop")
    private var displayListDirty = true
    private val sourceNode = android.graphics.RenderNode("live-glass-source")
    private var sourceDirty = true
    private var wasVisible = false
    private val sourcePosition = IntArray(2)
    private var sourceX = Int.MIN_VALUE
    private var sourceY = Int.MIN_VALUE
    private val outset = (96 * owner.resources.displayMetrics.density).toInt()
    private val backdrop = object : Backdrop {
        override val isCoordinatesDependent = true
        override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
            recordings++
            val offset = coordinates?.positionInRoot() ?: androidx.compose.ui.geometry.Offset.Zero
            withTransform({ translate(-offset.x,-offset.y) }) {
                drawContext.canvas.nativeCanvas.drawRenderNode(sourceNode)
            }
        }
    }
    private inner class BridgeView(context: Context) : AbstractComposeView(context) {
        init {clipChildren=false;clipToPadding=false}
        override fun onWindowVisibilityChanged(visibility:Int) {
            super.onWindowVisibilityChanged(visibility)
            if(visibility==View.VISIBLE)owner.post {refreshLight();refresh()}
            else wasVisible=false
        }
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
        refreshLight()
        refresh()
        owner.invalidate()
    }
    fun refresh() {
        // ScrollView keeps offscreen children attached. Invalidating their compositions on every
        // scroll/sensor sample forces work for the entire settings page instead of its viewport.
        if(!visible())return
        sourceDirty=true
        owner.invalidate()
    }
    private fun visible():Boolean {
        if(owner.windowVisibility!=View.VISIBLE || !owner.isShown)return false
        var view:View?=owner
        while(view!=null) {
            if(view.alpha<=.001f)return false
            view=view.parent as? View
        }
        return owner.getLocalVisibleRect(visibleBounds)
    }
    private fun checkPosition() {
        if(!visible()) {wasVisible=false;return}
        if(!wasVisible) {wasVisible=true;sourceDirty=true;refreshLight()}
        owner.getLocationOnScreen(sourcePosition)
        if(sourceDirty || sourcePosition[0]!=sourceX || sourcePosition[1]!=sourceY)owner.invalidate()
    }
    private fun refreshLight() {
        if(!visible())return
        val light=LightSourceController.lightFor(owner,LightSourceController.DEFAULT_X,LightSourceController.DEFAULT_Y)
        val next=atan2(light.second,light.first)*180f/Math.PI.toFloat()
        if(kotlin.math.abs(next-angle)>.1f)angle=next
    }
    fun draw(canvas: Canvas): Boolean {
        val view=host ?: return false
        if (!canvas.isHardwareAccelerated || owner.width<=0 || owner.height<=0) return false
        layoutHost(view)
        owner.getLocationOnScreen(sourcePosition)
        if(sourceNode.width!=owner.width+outset*2 || sourceNode.height!=owner.height+outset*2) {
            sourceNode.setPosition(-outset,-outset,owner.width+outset,owner.height+outset)
            sourceDirty=true
        }
        if(sourceDirty || sourceX!=sourcePosition[0] || sourceY!=sourcePosition[1]) {
            sourceX=sourcePosition[0];sourceY=sourcePosition[1]
            val sourceCanvas=sourceNode.beginRecording(owner.width+outset*2,owner.height+outset*2)
            try {
                sourceCanvas.translate(outset.toFloat(),outset.toFloat())
                GlassBackdrop.draw(sourceCanvas,owner)
            } finally {sourceNode.endRecording()}
            sourceDirty=false
        }
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
    fun drawCached(canvas:Canvas):Boolean {
        if(!canvas.isHardwareAccelerated || !displayList.hasDisplayList() ||
            displayList.width!=owner.width || displayList.height!=owner.height)return false
        canvas.drawRenderNode(displayList)
        return true
    }
    private fun layoutHost(view: BridgeView, forTouch: Boolean = false) {
        val width=owner.width;val height=owner.height
        if(view.layoutParams.width!=width || view.layoutParams.height!=height) {
            view.layoutParams=view.layoutParams.apply {this.width=width;this.height=height}
        }
        if(view.measuredWidth!=width || view.measuredHeight!=height) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        }
        // Drawing is local; only forwarded input needs a physical origin aligned to its owner.
        var x=view.left;var y=view.top
        if(forTouch) {
            val position=IntArray(2);val origin=IntArray(2)
            owner.getLocationOnScreen(position);hostRoot?.getLocationOnScreen(origin)
            x=position[0]-origin[0];y=position[1]-origin[1]
        }
        if(view.left!=x || view.top!=y || view.width!=width || view.height!=height) {
            view.layout(x,y,x+width,y+height)
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
        sourceNode.discardDisplayList()
        sourceDirty=true
        displayList.discardDisplayList()
        displayListDirty=true
        view.disposeComposition()
        val root=hostRoot
        hostRoot=null
        android.os.Handler(android.os.Looper.getMainLooper()).post { if(root!=null && view.parent===root)root.removeView(view) }
        renderers[owner]?.let { it.remove(this);if(it.isEmpty())renderers.remove(owner) }
    }
}
