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
        set(value) { field = value; invalidate() }
    private val capture = BackdropCapture()
    private val source = RenderNode("scroll-edge-source")
    private val levels = Array(4) { RenderNode("scroll-edge-$it") }
    private val masks = Array(4) { RuntimeShader("""
        uniform shader image;
        uniform float height;
        uniform float level;
        half4 main(float2 p) {
            float y = clamp(p.y / height, 0.0, 1.0);
            float ramp = 1.0 - smoothstep(0.0, 1.0, y);
            float weight = max(0.0, 1.0 - abs(ramp * 3.0 - level));
            float fade = 1.0 - smoothstep(0.60, 1.0, y);
            return image.eval(p) * half(weight * fade);
        }
    """) }
    private val additive = android.graphics.Paint().apply { blendMode = android.graphics.BlendMode.PLUS }
    private var sizeKey = 0L
    // Observe frames already scheduled by source changes; do not schedule an idle polling loop.
    private val redraw = ViewTreeObserver.OnPreDrawListener {
        sourceView?.let { if (it.isDirty || it.isLayoutRequested) invalidate() }
        true
    }
    private val scroll = ViewTreeObserver.OnScrollChangedListener { invalidate() }
    internal var renderedFrames = 0L; private set
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.viewTreeObserver.addOnScrollChangedListener(scroll)
        rootView.viewTreeObserver.addOnPreDrawListener(redraw)
    }
    override fun onDetachedFromWindow() {
        if (rootView.viewTreeObserver.isAlive) {
            rootView.viewTreeObserver.removeOnScrollChangedListener(scroll)
            rootView.viewTreeObserver.removeOnPreDrawListener(redraw)
        }
        source.discardDisplayList(); levels.forEach { it.discardDisplayList() }
        super.onDetachedFromWindow()
    }
    override fun onDraw(canvas: Canvas) {
        val backdrop = sourceView ?: return
        if (!canvas.isHardwareAccelerated || width == 0 || height == 0 || GlassBackdrop.capturing) return
        val scale = 1f / 3f
        val w = ceil(width * scale).toInt(); val h = ceil(height * scale).toInt()
        val key = (w.toLong() shl 32) or h.toLong()
        if (key != sizeKey) {
            sizeKey = key
            source.setPosition(0, 0, w, h)
            levels.forEachIndexed { i, node ->
                node.setPosition(0, 0, w, h)
                node.setUseCompositingLayer(true, additive)
                val mask = masks[i]
                mask.setFloatUniform("height", h.toFloat()); mask.setFloatUniform("level", i.toFloat())
                val opacity = RenderEffect.createRuntimeShaderEffect(mask, "image")
                val radius = 24f * resources.displayMetrics.density * scale * i / 3f
                node.setRenderEffect(if (i == 0) opacity else RenderEffect.createChainEffect(opacity,
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)))
            }
        }
        val a = IntArray(2); val b = IntArray(2)
        backdrop.getLocationOnScreen(a); getLocationOnScreen(b)
        val recording = source.beginRecording(w, h)
        try {
            recording.scale(scale, scale)
            recording.translate((a[0]-b[0]).toFloat(), (a[1]-b[1]).toFloat())
            capture.draw(recording, backdrop, this, includeGlass = true)
        } finally { source.endRecording() }
        val save = canvas.save(); canvas.scale(1f/scale, 1f/scale)
        val blend = canvas.saveLayer(0f,0f,w.toFloat(),h.toFloat(),null)
        for (node in levels) {
            val content = node.beginRecording(w,h)
            try { content.drawRenderNode(source) } finally { node.endRecording() }
            canvas.drawRenderNode(node)
        }
        canvas.restoreToCount(blend)
        canvas.restoreToCount(save)
        renderedFrames++
    }
}
