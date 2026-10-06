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
 *  * **Refraction** - the backdrop is resampled inward along a normal taken from the view's own
 *    rounded-rectangle signed distance field, so the rim mirrors the content just inside it. The
 *    bend follows a **bevel depth profile**: an inverse-power falloff concentrates nearly all of it
 *    into the outermost pixels and leaves a long gentle tail inside, which is what reads as a lens
 *    rather than as a blurred edge.
 *  * **Dispersion** - red, green and blue are bent by slightly different amounts, which is what puts
 *    a spectral fringe on a real glass edge.
 *  * **Rim light from the same normal field** - two symmetric angular lobes (`pow 4.5`), so the lit
 *    and shadow sides peak equally, plus a softer glow inward on the lit side only. There is
 *    deliberately **no direction-independent term**, because that is exactly what leaves a constant
 *    outline all the way around and makes a pane look like a sticker.
 *
 * There is no procedural noise anywhere in the model. An earlier version faked caustics with
 * cellular noise advected by the refraction offset; it read as grain rather than as glass, and the
 * optics below produce the real highlight structure without it.
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

    /**
     * Qualities of the glass, all tunable so the look can be adjusted without touching the shader.
     *
     * The names and meanings follow the reference optical model, so the same numbers can be carried
     * between implementations without reinterpretation.
     */
    var cornerRadiusPx: Float = dp(28f)

    /** Width of the refracting bevel, in px. This is the width of the "edge" of the pane. */
    var bevelWidthPx: Float = dp(18f)

    /** How far the rim pulls the backdrop inward, in px. */
    var refractionPx: Float = dp(14f)

    /**
     * Bevel depth profile.
     *
     * Above zero uses an inverse-power (gravity-lens) decay, so the bend is concentrated at the very
     * edge; zero uses a squared profile that spreads it evenly across the bevel.
     */
    var refractionFalloff: Float = 1.6f

    /** Per-channel split at the rim. Around 0.10 reads as glass; beyond ~0.25 it reads as rainbow. */
    var dispersionStrength: Float = 0.10f

    /** Rim highlight strength. */
    var specularStrength: Float = 1.0f

    /** 1.0 leaves colour alone; above 1 applies the vibrancy curve. */
    var saturation: Float = 1.06f

    /** Straight-alpha base tint mixed over the refracted backdrop. */
    var tintColor: Int = 0x12FFFFFF

    /** Coloured body, modelled as absorption plus a little scattering. Alpha 0 disables it. */
    var glassTintColor: Int = 0x00000000

    /** Darkening applied under the glass, for the "clear" material over bright content. */
    var dimAmount: Float = 0.0f

    /** 0..1 press state; boosts the bend slightly so a touch feels like it deforms the surface. */
    var pressAmount: Float = 0f

    /** Direction the light comes from, as an angle in radians. */
    var lightAngleRad: Float = Math.toRadians(135.0).toFloat()

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
        active.setFloatUniform("uBevel", bevelWidthPx)
        active.setFloatUniform("uRefract", refractionPx)
        active.setFloatUniform("uFalloff", refractionFalloff)
        active.setFloatUniform("uDispersion", dispersionStrength)
        // Light direction as a unit vector pointing FROM the surface TOWARD the light.
        active.setFloatUniform(
            "uLightDir",
            kotlin.math.cos(lightAngleRad),
            kotlin.math.sin(lightAngleRad),
        )
        active.setFloatUniform("uSpec", specularStrength)
        active.setFloatUniform("uSaturation", saturation)
        active.setFloatUniform("uTint", tintRed, tintGreen, tintBlue, tintAlpha)
        active.setFloatUniform(
            "uGlassTint",
            glassTintRed,
            glassTintGreen,
            glassTintBlue,
            glassTintAlpha,
        )
        active.setFloatUniform("uDim", dimAmount)
        active.setFloatUniform("uPress", pressAmount)
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

    private val glassTintAlpha: Float get() = ((glassTintColor ushr 24) and 0xFF) / 255f
    private val glassTintRed: Float get() = ((glassTintColor shr 16) and 0xFF) / 255f
    private val glassTintGreen: Float get() = ((glassTintColor shr 8) and 0xFF) / 255f
    private val glassTintBlue: Float get() = (glassTintColor and 0xFF) / 255f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Backdrop is a light source, not an image: a small buffer is plenty. */
        /**
         * Backdrop capture size.
         *
         * The glass is roughly a tenth of the screen, so this is already sampling above the display
         * density of the pane; going higher costs a bigger readback for no visible gain, and going
         * lower makes the rim shimmer because the refraction is magnifying a handful of texels.
         * 96x54 was too coarse: the bend pulled in visible banding.
         */
        const val CAPTURE_W = 192
        const val CAPTURE_H = 108

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
            uniform float2 uSize;
            uniform float  uRadius;
            uniform float  uBevel;        // width of the refracting bevel, px
            uniform float  uRefract;      // refraction strength, px
            uniform float  uFalloff;      // > 0 inverse-power (gravity-lens) profile, 0 = squared bevel
            uniform float  uDispersion;   // per-channel split at the rim
            uniform float2 uLightDir;     // normalised
            uniform float  uSpec;         // rim highlight strength
            uniform float  uSaturation;
            uniform vec4   uTint;         // straight alpha
            uniform vec4   uGlassTint;    // coloured body; a = 0 disables
            uniform float  uDim;
            uniform float  uPress;        // 0..1, a press boosts the bend slightly

            uniform shader uBackdrop;

            // Signed distance to a rounded rectangle, negative inside. The gradient of this field is
            // the surface normal, which is what drives refraction AND the rim highlight, so the two
            // stay locked together as the shape changes.
            float sdRoundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 fragCoord) {
                float2 halfSize = uSize * 0.5;
                float2 p = fragCoord - halfSize;

                float d = sdRoundRect(p, halfSize, uRadius);

                // Coverage with ~1.5px anti-aliasing, so the silhouette edge is smooth instead of a
                // hard cut. Outside the shape nothing is written.
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                // Screen-space outward normal from the SDF gradient.
                float2 grad = float2(
                    sdRoundRect(p + float2(1.0, 0.0), halfSize, uRadius) - d,
                    sdRoundRect(p + float2(0.0, 1.0), halfSize, uRadius) - d
                );
                float gLen = length(grad);
                float2 n = (gLen > 0.0001) ? (grad / gLen) : float2(0.0, -1.0);

                // Thickness profile: t = 1 in the flat interior, 0 at the rim.
                float t = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);
                float edge = 1.0 - t;

                // How sharply the surface bends, as a function of depth into the bevel.
                //
                // The inverse-power profile concentrates almost all of the bend into the outermost
                // pixels and leaves a long gentle tail inside, which is what reads as glass. The
                // squared profile spreads the bend evenly across the band. Both replace the previous
                // pow(edge) ramp, which bent too uniformly to look like a lens.
                float slope;
                if (uFalloff > 0.001) {
                    float gB = pow(5.0, -uFalloff);
                    slope = (pow(1.0 + 4.0 * t, -uFalloff) - gB) / (1.0 - gB);
                } else {
                    slope = edge * edge;
                }

                // Refraction: sample inward along the normal, so the rim mirrors the content just
                // inside it. This is the whole lens effect.
                float refr = uRefract * (1.0 + 0.6 * uPress);
                float2 offset = n * (slope * refr);

                // Dispersion: blue bends most, red least, giving the spectral fringe at the rim.
                float2 cR = fragCoord + offset * (1.0 - uDispersion * slope);
                float2 cG = fragCoord + offset;
                float2 cB = fragCoord + offset * (1.0 + uDispersion * slope);

                // Keep sampling inside the captured backdrop.
                float2 lo = float2(0.5, 0.5);
                float2 hi = uSize - float2(0.5, 0.5);
                cR = clamp(cR, lo, hi);
                cG = clamp(cG, lo, hi);
                cB = clamp(cB, lo, hi);

                vec3 col = vec3(
                    uBackdrop.eval(cR).r,
                    uBackdrop.eval(cG).g,
                    uBackdrop.eval(cB).b
                );

                // Vibrancy rather than a flat saturation multiply: low-saturation pixels gain more,
                // already-saturated pixels gain less, and near-white pixels are protected so rich
                // colour is not pushed into clipping.
                float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
                if (uSaturation <= 1.0) {
                    col = mix(vec3(lum), col, uSaturation);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hi = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (uSaturation - 1.0) * mix(0.3, 1.0, room * hi);
                    col = clamp(mix(vec3(lum), col, amount), vec3(0.0), vec3(1.0));
                }

                // Body tint, modelled as a coloured medium: absorption keeps the backdrop's
                // luminance structure, plus a little scattering so the hue shows even when dark.
                // Applied before the highlight, because tint belongs to transmission and the
                // specular belongs to the surface.
                col = mix(col, uTint.rgb, uTint.a);
                if (uGlassTint.a > 0.002) {
                    float lumTint = dot(col, vec3(0.2126, 0.7152, 0.0722));
                    vec3 absorbed = col * mix(vec3(1.0), uGlassTint.rgb, 0.85);
                    vec3 scattered = uGlassTint.rgb * (0.38 * (1.0 - lumTint));
                    col = mix(col, clamp(absorbed + scattered, vec3(0.0), vec3(1.0)), uGlassTint.a);
                }
                col = col * (1.0 - uDim);

                // Rim light from the same normal field. Two symmetric angular lobes, so the
                // lit side and the shadow side peak equally - the shadow side is the inner wall
                // reflection of a transparent medium. There is deliberately NO direction-independent
                // constant term: that is what leaves a fixed outline all the way round, which is the
                // single biggest difference from a real glass edge.
                float facing = dot(n, -uLightDir);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);

                // A ~2px hairline centred just inside the edge, plus a softer glow inward from it on
                // the lit side only. The glow is offset so it never stacks on the hairline.
                float bandW = clamp(uBevel * 0.3, 2.0, 6.0);
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF)
                             * uSpec * (1.0 - 0.35 * uPress);
                col += vec3(spec);

                col = clamp(col, vec3(0.0), vec3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """.trimIndent()
    }
}
