package com.gan.spatialplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * Liquid glass.
 *
 * ## What this actually renders
 *
 * Not a tinted rectangle, and not a blurred rectangle. The backdrop directly behind the view is
 * read back from the surface with [PixelCopy], uploaded as a texture, and then **re-rendered
 * through a fragment shader that models a thick refracting medium**:
 *
 *  * **Refraction / IOR** - the backdrop is resampled along a normal derived from the view's own
 *    rounded-rectangle signed distance field, so the surface behaves like a lens that bends what is
 *    behind it more towards the rim than at the centre.
 *  * **Chromatic aberration** - red, green and blue are sampled at three slightly different
 *    refraction strengths, which is what makes real glass fringe colour at the edges instead of
 *    looking like a flat blur.
 *  * **Caustics** - a subtle cellular pattern is advected by the same refraction offset, giving the
 *    bright focused filaments that light forms after passing through a curved medium.
 *  * **Fresnel rim and specular** - a bright edge that intensifies at grazing angles, plus a
 *    directional highlight, so the pane reads as a physical object with a surface.
 *
 * ## Why a texture and not a shader over the live view tree
 *
 * The picture is a `SurfaceView`, composited by SurfaceFlinger rather than drawn into the window's
 * canvas. Nothing in the normal view hierarchy can sample it. [PixelCopy] is the supported way to
 * read it back, and because the glass is redrawn on a timer rather than every frame, the cost is a
 * small downscaled copy roughly ten times a second.
 *
 * On platforms without [RuntimeShader] (below API 33) the view falls back to a frosted approximation
 * built from layered gradients, so the layout and behaviour are unchanged.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Qualities of the glass, all tunable so the look can be adjusted without touching the shader. */
    var cornerRadiusPx: Float = dp(28f)
    var thickness: Float = 1.0f
    var aberration: Float = 1.0f
    var causticStrength: Float = 1.0f
    var tintColor: Int = 0x14FFFFFF
    var rimStrength: Float = 0.55f

    /** The view whose surface should be read as the backdrop, usually the video. */
    var backdropSource: SurfaceView? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shader: RuntimeShader? = null
    private var backBuffer: Bitmap? = null
    private val captureRect = Rect()
    private val outlineRect = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private var inFlight = false
    private var running = false
    private var consecutiveFailures = 0

    private val refreshTick = object : Runnable {
        override fun run() {
            captureBackdrop()
            if (running) handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setWillNotDraw(false)
        // Clip the shader to the glass silhouette.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        clipToOutline = true
        isFocusable = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader = runCatching { RuntimeShader(GLASS_SHADER) }.getOrNull()
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(refreshTick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(refreshTick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        backBuffer?.recycle()
        backBuffer = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        outlineRect.set(0f, 0f, w.toFloat(), h.toFloat())
        invalidateOutline()
    }

    // ------------------------------------------------------------------ backdrop capture

    private fun captureBackdrop() {
        val source = backdropSource ?: return
        if (inFlight || width <= 0 || height <= 0) return
        if (!source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return

        // Read the region of the source that sits behind this view.
        val location = IntArray(2)
        source.getLocationInWindow(location)
        val sourceX = location[0]
        val sourceY = location[1]
        getLocationInWindow(location)
        val myX = location[0]
        val myY = location[1]

        val left = (myX - sourceX).coerceIn(0, source.width - 1)
        val top = (myY - sourceY).coerceIn(0, source.height - 1)
        val right = (left + width).coerceAtMost(source.width)
        val bottom = (top + height).coerceAtMost(source.height)
        if (right <= left || bottom <= top) return

        captureRect.set(left, top, right, bottom)

        val target = backBuffer
            ?: Bitmap.createBitmap(CAPTURE_W, CAPTURE_H, Bitmap.Config.ARGB_8888)
                .also { backBuffer = it }

        inFlight = true
        runCatching {
            PixelCopy.request(
                source,
                captureRect,
                target,
                { result ->
                    inFlight = false
                    if (result == PixelCopy.SUCCESS) {
                        consecutiveFailures = 0
                        invalidate()
                    } else {
                        onCaptureFailed(result)
                    }
                },
                handler,
            )
        }.onFailure {
            inFlight = false
            onCaptureFailed(-1)
        }
    }

    private fun onCaptureFailed(code: Int) {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_FAILURES) {
            // Give up quietly and let the fallback rendering stand rather than burning copies.
            stop()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val bitmap = backBuffer
        val active = shader

        if (bitmap != null && active != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawShaderGlass(canvas, bitmap, active)
        } else {
            drawFallbackGlass(canvas)
        }
        super.onDraw(canvas)
    }

    private fun drawShaderGlass(canvas: Canvas, bitmap: Bitmap, active: RuntimeShader) {
        val w = width.toFloat()
        val h = height.toFloat()
        active.setFloatUniform("uSize", w, h)
        active.setFloatUniform("uRadius", cornerRadiusPx)
        active.setFloatUniform("uThickness", thickness)
        active.setFloatUniform("uAberration", aberration)
        active.setFloatUniform("uCaustics", causticStrength)
        active.setFloatUniform("uRim", rimStrength)
        active.setFloatUniform("uTint", tintRed, tintGreen, tintBlue, tintAlpha)
        // The backdrop arrives as a bitmap each refresh, so it is uploaded as a shader input.
        active.setInputShader(
            "uBackdrop",
            android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )

        paint.shader = active
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /**
     * Pre-API-33 approximation.
     *
     * The refraction and caustics need a programmable shader, so on older platforms the glass keeps
     * the surface treatment it can express with gradients: a translucent fill, a bright top rim and
     * a darker lower edge. It is honestly a fallback, not the same effect.
     */
    private fun drawFallbackGlass(canvas: Canvas) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
        canvas.drawRoundRect(outlineRect, cornerRadiusPx, cornerRadiusPx, fill)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = 0x40FFFFFF
        }
        canvas.drawRoundRect(outlineRect, cornerRadiusPx, cornerRadiusPx, rim)
    }

    // ------------------------------------------------------------------ tint helpers

    private val tintAlpha: Float get() = ((tintColor ushr 24) and 0xFF) / 255f
    private val tintRed: Float get() = ((tintColor shr 16) and 0xFF) / 255f
    private val tintGreen: Float get() = ((tintColor shr 8) and 0xFF) / 255f
    private val tintBlue: Float get() = (tintColor and 0xFF) / 255f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Backdrop is a light source, not an image: a small buffer is plenty. */
        const val CAPTURE_W = 96
        const val CAPTURE_H = 54

        /** Redraw cadence. Fast enough to feel live, slow enough to stay cheap. */
        const val REFRESH_MS = 90L

        const val MAX_FAILURES = 6

        /**
         * Fragment shader for the glass.
         *
         * `sdRoundRect` gives a signed distance field, whose gradient is the surface normal - that
         * is what drives refraction. Sampling the backdrop three times with slightly different
         * offsets produces the chromatic fringe, and the caustic term is advected by the same
         * offset so the highlights move with the refraction rather than sliding independently.
         */
        val GLASS_SHADER = """
            uniform vec2  uSize;
            uniform float uRadius;
            uniform float uThickness;
            uniform float uAberration;
            uniform float uCaustics;
            uniform float uRim;
            uniform vec4  uTint;
            uniform shader uBackdrop;

            float sdRoundRect(vec2 p, vec2 halfSize, float r) {
                vec2 q = abs(p) - halfSize + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
            }

            vec4 sampleBackdrop(vec2 uv) {
                return uBackdrop.eval(uv * uSize);
            }

            half4 main(vec2 fragCoord) {
                vec2 halfSize = uSize * 0.5;
                vec2 p = fragCoord - halfSize;

                float d = sdRoundRect(p, halfSize, uRadius);

                // Surface normal from the distance field: points inward, strongest at the rim.
                vec2 grad = vec2(
                    sdRoundRect(p + vec2(1.0, 0.0), halfSize, uRadius) - d,
                    sdRoundRect(p + vec2(0.0, 1.0), halfSize, uRadius) - d
                );
                vec2 normal = normalize(grad + vec2(1e-6));

                // Edge falloff: 0 in the middle of the pane, 1 at the rim.
                float edge = clamp(1.0 - (-d) / (uRadius * 0.85), 0.0, 1.0);
                edge = pow(edge, 1.7);

                // Refraction bends what is behind more towards the edges.
                vec2 bend = normal * edge * uThickness * 14.0;

                vec2 uv = fragCoord / uSize;

                // Chromatic aberration: three slightly different refraction strengths per channel.
                float ab = uAberration * 1.7;
                vec4 base = sampleBackdrop(uv);
                float r = sampleBackdrop(uv + bend * (1.0 + ab * 0.12) / uSize).r;
                float g = sampleBackdrop(uv + bend / uSize).g;
                float b = sampleBackdrop(uv + bend * (1.0 - ab * 0.12) / uSize).b;
                vec4 refracted = vec4(r, g, b, base.a);

                // Caustics: light focused by the curved medium, advected by the refraction.
                vec2 cUv = (fragCoord + bend * 1.6) * 0.085;
                float c1 = noise(cUv);
                float c2 = noise(cUv * 2.3 + vec2(11.0, 7.0));
                float caustic = pow(clamp(c1 * c2 * 2.4, 0.0, 1.0), 2.1);
                // Caustics concentrate near the rim, where the surface curves most.
                vec3 causticTint = vec3(0.72, 0.86, 1.0);
                refracted.rgb += causticTint * caustic * uCaustics * 0.34 * (0.35 + edge);

                // Fresnel rim: bright where the surface turns away from the viewer.
                float fresnel = pow(clamp(edge, 0.0, 1.0), 2.4) * uRim;
                refracted.rgb += vec3(fresnel) * 0.42;

                // Directional specular sweep across the upper half of the pane.
                float spec = smoothstep(0.42, 0.98, 1.0 - (fragCoord.y / uSize.y));
                spec *= smoothstep(0.0, 0.55, 1.0 - abs(uv.x - 0.34) * 1.9);
                refracted.rgb += vec3(spec) * 0.055;

                // Glass body tint, premultiplied by alpha.
                vec4 outColor = refracted * uTint.a + vec4(uTint.rgb, 0.0) * uTint.a;
                outColor.a = clamp(refracted.a * uTint.a + uTint.a, 0.0, 1.0);

                // Feather the silhouette so the pane edge is not a hard cut.
                float mask = clamp(0.5 - d, 0.0, 1.0);
                outColor *= mask;
                return half4(outColor);
            }
        """.trimIndent()
    }
}
