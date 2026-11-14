package org.spatialplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.ceil

/** Scroll-edge frosting: four GPU Gaussian levels blend continuously toward a transparent foot. */
class TopFadeBlurView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var sourceView: View? = null
        set(value) { field = value; invalidateContent() }
    var contentOnly=false
        set(value) {if(field!=value){field=value;sizeKey=0;invalidateContent()}}
    var solidHeightPx=0f
        set(value) {if(field!=value){field=value;sizeKey=0L;invalidate()}}
    private val capture = BackdropCapture()
    private val source = RenderNode("scroll-edge-source")
    private val foreground=RenderNode("scroll-edge-content")
    private var contentDirty=true
    private var contentX=Int.MIN_VALUE
    private var contentY=Int.MIN_VALUE
    private val levels = Array(4) { RenderNode("scroll-edge-$it") }
    private val masks = Array(4) { RuntimeShader("""
        uniform shader image;
        uniform float height;
        uniform float margin;
        uniform float solidHeight;
        uniform float level;
        uniform float contentOnly;
        half4 main(float2 p) {
            float y = clamp((p.y-margin-solidHeight) / max(1.0,height-solidHeight), 0.0, 1.0);
            float ramp = 1.0 - smoothstep(0.0, 1.0, y);
            float weight = max(0.0, 1.0 - abs(ramp * 3.0 - level));
            float fade = contentOnly > 0.5 ? smoothstep(0.0,0.85,y) : 1.0 - smoothstep(0.60, 1.0, y);
            return image.eval(p) * half(weight * fade);
        }
    """) }
    private val additive = android.graphics.Paint().apply { blendMode = android.graphics.BlendMode.PLUS }
    private var sizeKey = 0L
    // Observe frames already scheduled by source changes; do not schedule an idle polling loop.
    private val redraw = ViewTreeObserver.OnPreDrawListener {
        sourceView?.let { if (it.isLayoutRequested || capture.foregroundDirty(it)) invalidateContent() }
        true
    }
    private val scroll = ViewTreeObserver.OnScrollChangedListener { invalidateContent() }
    internal fun invalidateContent() {contentDirty=true;invalidate()}
    internal var renderedFrames = 0L; private set
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.viewTreeObserver.addOnScrollChangedListener(scroll)
        rootView.viewTreeObserver.addOnPreDrawListener(redraw)
        GlassBackdrop.watch(this)
    }
    override fun onDetachedFromWindow() {
        GlassBackdrop.unwatch(this)
        if (rootView.viewTreeObserver.isAlive) {
            rootView.viewTreeObserver.removeOnScrollChangedListener(scroll)
            rootView.viewTreeObserver.removeOnPreDrawListener(redraw)
        }
        source.discardDisplayList();foreground.discardDisplayList(); levels.forEach { it.discardDisplayList() }
        sizeKey=0L;contentDirty=true
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        val backdrop = sourceView ?: return
        if (!canvas.isHardwareAccelerated || width == 0 || height == 0 || GlassBackdrop.capturing) return
        val scale = 1f / 3f
        val w = ceil(width * scale).toInt(); val h = ceil(height * scale).toInt()
        val margin=ceil(72f*resources.displayMetrics.density*scale).toInt()
        val recW=w+margin*2;val recH=h+margin*2
        val key = (w.toLong() shl 32) or h.toLong()
        if (key != sizeKey) {
            sizeKey = key
            contentDirty=true
            source.setPosition(0,0,recW,recH)
            foreground.setPosition(0,0,recW,recH)
            levels.forEachIndexed { i, node ->
                node.setPosition(-margin,-margin,w+margin,h+margin)
                node.setUseCompositingLayer(true, additive)
                val mask = masks[i]
                mask.setFloatUniform("contentOnly",if(contentOnly)1f else 0f)
                mask.setFloatUniform("solidHeight",solidHeightPx*scale);mask.setFloatUniform("height", h.toFloat()); mask.setFloatUniform("margin",margin.toFloat()); mask.setFloatUniform("level", i.toFloat())
                val opacity = RenderEffect.createRuntimeShaderEffect(mask, "image")
                val radius = 24f * resources.displayMetrics.density * scale * i / 3f
                node.setRenderEffect(if (i == 0) opacity else RenderEffect.createChainEffect(opacity,
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)))
                // These commands only reference a stable live source node. Re-recording all four
                // effect display lists for every video frame needlessly dirties the effect graph.
                val content=node.beginRecording(recW,recH)
                try {content.drawRenderNode(source)}finally {node.endRecording()}
            }
        }
        val a = IntArray(2); val b = IntArray(2)
        backdrop.getLocationOnScreen(a); getLocationOnScreen(b)
        val x=a[0]-b[0];val y=a[1]-b[1]
        if(contentDirty || x!=contentX || y!=contentY || !foreground.hasDisplayList()) {
            contentX=x;contentY=y;contentDirty=false
            val content=foreground.beginRecording(recW,recH)
            try {
                content.translate(margin.toFloat(),margin.toFloat());content.scale(scale,scale)
                content.translate(x.toFloat(),y.toFloat())
                GlassBackdrop.snapshot {capture.draw(content,backdrop,this,includeGlass=true)}
            }finally {foreground.endRecording()}
        }
        val recording = source.beginRecording(recW,recH)
        try {
            if(!contentOnly)recording.drawColor(androidx.core.content.ContextCompat.getColor(context,org.spatialplayer.R.color.bg_deep))
            val base=recording.save()
            recording.translate(margin.toFloat(),margin.toFloat())
            recording.scale(scale, scale)
            // Transparent header padding still needs the physical scene beneath the sheet.
            // Filling it with bg_deep made a dark strip that did not join the shell's blur.
            if(!contentOnly)GlassBackdrop.draw(recording,this)
            recording.restoreToCount(base)
            recording.drawRenderNode(foreground)
        } finally { source.endRecording() }
        val save = canvas.save(); canvas.clipRect(0,0,width,height);canvas.scale(1f/scale, 1f/scale)
        val blend = canvas.saveLayer(0f,0f,w.toFloat(),h.toFloat(),null)
        for (node in levels)canvas.drawRenderNode(node)
        canvas.restoreToCount(blend)
        canvas.restoreToCount(save)
        renderedFrames++
    }
}
