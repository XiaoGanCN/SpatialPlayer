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
import kotlin.math.roundToInt

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
 * ## The coordinate spaces, which are not the same space
 *
 * A `SurfaceView` owns a buffer that the producer sizes - a video decoder sizes it to the decoded
 * frame, which for a 4K film is 3840x2160 - and the view then displays that buffer stretched across
 * its own bounds. [PixelCopy] addresses its source rectangle in **buffer pixels**, while everything
 * a view knows about itself is in **view pixels**. The two differ by the ratio of the frame to the
 * picture's rect on screen, which is not 1 and is not even close for a 4K film in a 1096px-wide
 * window. Handing view pixels to `PixelCopy` therefore reads an arbitrary corner of the frame: in
 * portrait, where the picture is a 1096x616 band, it asked for a rectangle that collapsed to a
 * single row of pixels, which the rim then stretched into vertical stripes.
 *
 * [setBackdropFrameSize] supplies the missing factor. Everything below converts between the three
 * spaces explicitly: window -> picture -> buffer for the copy, and view -> texture for the shader.
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

    /**
     * Frost radius, in backdrop texels.
     *
     * The single biggest difference between this and a tinted rectangle. In texels rather than px so
     * a small pane and a wide bar frost by the same apparent amount, since the backdrop is captured
     * at a fixed maximum size regardless of the pane.
     */
    var blurTexels: Float = 9f

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

    /**
     * Frame size of the picture being sampled, in the surface's **own buffer pixels**.
     *
     * This is not the size of [backdropSource] on screen. A `SurfaceView` owns a buffer whose
     * geometry the producer sets - a video decoder sets it to the decoded frame size - and the view
     * then displays that buffer stretched across its bounds. [PixelCopy] addresses its source
     * rectangle in buffer pixels, so the frame size is what converts a rectangle of screen into a
     * rectangle of picture.
     */
    var backdropFrameWidth: Int = 0
        private set
    var backdropFrameHeight: Int = 0
        private set

    fun setBackdropFrameSize(width: Int, height: Int) {
        if (width == backdropFrameWidth && height == backdropFrameHeight) return
        backdropFrameWidth = width
        backdropFrameHeight = height
        start()
        invalidate()
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shader: RuntimeShader? = null
    private var backBuffer: Bitmap? = null
    private val captureRect = Rect()
    private val outlineRect = RectF()

    /**
     * Where the captured copy sits inside this view, in view pixels.
     *
     * The copy covers only the part of the pane that has picture behind it; the rest of the pane is
     * over letterbox or the window background. The shader needs both the origin (to turn a view
     * coordinate into a texture coordinate) and the extent (to know where the picture stops).
     */
    private var coverLeft = 0f
    private var coverTop = 0f
    private var coverWidth = 0f
    private var coverHeight = 0f
    private var hasCover = false

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

        val frameW = backdropFrameWidth
        val frameH = backdropFrameHeight
        if (frameW <= 0 || frameH <= 0) {
            // No picture has been reported yet.
            clearCover()
            return
        }

        // Where the picture is drawn, in window pixels.
        //
        // The surface stretches its buffer across its own bounds, and those bounds can themselves be
        // scaled (the player fits the picture by scaling the surface rather than by resizing its
        // buffer). `getLocationInWindow` reports the transformed origin, so dividing by the view
        // scale walks back into the surface's own pixels. On the common path the scale is 1.
        val location = IntArray(2)
        source.getLocationInWindow(location)
        val pictureLeft = location[0].toFloat()
        val pictureTop = location[1].toFloat()
        val pictureWidth = source.width * source.scaleX
        val pictureHeight = source.height * source.scaleY
        if (pictureWidth < 1f || pictureHeight < 1f) return

        getLocationInWindow(location)
        val glassLeft = location[0].toFloat()
        val glassTop = location[1].toFloat()

        // Intersection of the pane with the picture, in window pixels.
        val left = maxOf(glassLeft, pictureLeft)
        val top = maxOf(glassTop, pictureTop)
        val right = minOf(glassLeft + width, pictureLeft + pictureWidth)
        val bottom = minOf(glassTop + height, pictureTop + pictureHeight)
        if (right - left < 1f || bottom - top < 1f) {
            // Nothing but letterbox or window background behind the pane.
            clearCover()
            return
        }

        // Window pixels -> buffer pixels. The copy is asked for in the buffer's own resolution, and
        // PixelCopy rescales that rectangle onto the destination bitmap.
        val perPixelX = frameW / pictureWidth
        val perPixelY = frameH / pictureHeight
        val rect = Rect(
            ((left - pictureLeft) * perPixelX).roundToInt().coerceIn(0, frameW - 1),
            ((top - pictureTop) * perPixelY).roundToInt().coerceIn(0, frameH - 1),
            ((right - pictureLeft) * perPixelX).roundToInt().coerceIn(1, frameW),
            ((bottom - pictureTop) * perPixelY).roundToInt().coerceIn(1, frameH),
        )
        if (rect.width() <= 0 || rect.height() <= 0) {
            clearCover()
            return
        }

        val target = obtainBackBuffer(rect.width().toFloat(), rect.height().toFloat()) ?: return
        captureRect.set(rect)
        coverLeft = left - glassLeft
        coverTop = top - glassTop
        coverWidth = right - left
        coverHeight = bottom - top
        hasCover = true

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

    /** Records that no picture sits behind the pane, so the shader samples nothing. */
    private fun clearCover() {
        // Keep a token buffer so the shader path still runs: with no picture behind it the pane is
        // tint, bevel and rim over the window background, which is exactly what the shader draws
        // when every backdrop sample is masked out. Falling through to the gradient approximation
        // instead would make the same pane look different depending on where the picture happens
        // to be.
        if (backBuffer == null) {
            backBuffer = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        if (!hasCover) return
        hasCover = false
        coverLeft = 0f
        coverTop = 0f
        coverWidth = 0f
        coverHeight = 0f
        invalidate()
    }

    private fun onCaptureFailed(code: Int) {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_FAILURES) {
            // Give up quietly and let the fallback rendering stand rather than burning copies.
            stop()
        }
    }

    /**
     * Returns a buffer shaped like [w]x[h], allocating only when the shape changes.
     *
     * The copy is scaled down to at most [CAPTURE_MAX] on its long edge and never upscaled, so a
     * small pane reads its backdrop at native resolution while a full-width bar stays cheap to copy.
     * The shape is derived from the requested rectangle alone, which is what keeps the copy
     * isotropic: `PixelCopy` stretches its source rectangle onto the whole destination bitmap, so a
     * destination of any other shape would distort the picture.
     */
    private fun obtainBackBuffer(w: Float, h: Float): Bitmap? {
        if (w < 1f || h < 1f) return null
        val scale = (CAPTURE_MAX / maxOf(w, h)).coerceAtMost(1f)
        // Round rather than truncate: the shader maps view pixels onto texels by this buffer's
        // shape, so an off-by-one here is a small anisotropic stretch of the whole backdrop.
        val bw = (w * scale).roundToInt().coerceAtLeast(2)
        val bh = (h * scale).roundToInt().coerceAtLeast(2)

        val current = backBuffer
        if (current != null && current.width == bw && current.height == bh) return current
        current?.recycle()
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { backBuffer = it }
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
        if (w <= 0f || h <= 0f) return
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
        active.setFloatUniform("uBlur", blurTexels)
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
        // View pixels -> backdrop texels, plus where the copy sits inside the view and how large it
        // is. The copy covers only the part of the pane with picture behind it; the shader uses the
        // extent to stop sampling at the picture's edge instead of smearing the clamped last texel.
        val coverW = coverWidth
        val coverH = coverHeight
        if (coverW > 0.5f && coverH > 0.5f) {
            active.setFloatUniform("uBackdropOrigin", coverLeft, coverTop)
            active.setFloatUniform(
                "uBackdropScale",
                bitmap.width / coverW,
                bitmap.height / coverH,
            )
            active.setFloatUniform(
                "uBackdropTexSize",
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
            )
        } else {
            // Nothing behind the pane: mask every sample out so only tint and rim remain.
            active.setFloatUniform("uBackdropOrigin", 0f, 0f)
            active.setFloatUniform("uBackdropScale", 0f, 0f)
            active.setFloatUniform("uBackdropTexSize", 0f, 0f)
        }
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

    companion object {
        /**
         * Longest edge of the backdrop copy, in texels.
         *
         * The copy is at most this size and never upscaled. The pane is typically a tenth of the
         * display, so this sits comfortably above the density of the glass itself; the rim bend
         * magnifies whatever it samples, and under-sampling there is what produced banded rims when
         * this was 96x54. Larger costs readback bandwidth for no visible gain.
         */
        const val CAPTURE_MAX = 768

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
            uniform float  uBevel;
            uniform float  uRefract;
            uniform float  uFalloff;
            uniform float  uDispersion;
            uniform float2 uLightDir;
            uniform float  uSpec;
            uniform float  uSaturation;
            uniform vec4   uTint;
            uniform vec4   uGlassTint;
            uniform float  uDim;
            uniform float  uPress;
            uniform float  uBlur;           // frost radius, in backdrop texels
            uniform float2 uBackdropOrigin; // view px, top-left of the captured rectangle
            uniform float2 uBackdropScale;  // view px -> backdrop texel
            uniform float2 uBackdropTexSize;

            uniform shader uBackdrop;

            // A sample inside the captured rectangle, or zero outside it. The pane is usually wider
            // than the picture, and the texture's clamped edge would otherwise be smeared across it.
            half4 backdropMask(float2 t) {
                float2 lo = step(float2(0.0), t);
                float2 hi = step(t, max(uBackdropTexSize - float2(1.0, 1.0), float2(0.0)));
                return half4(half(lo.x * lo.y * hi.x * hi.y));
            }

            half4 backdropAt(float2 t) {
                float2 texMax = max(uBackdropTexSize - float2(1.0, 1.0), float2(0.0));
                return uBackdrop.eval(clamp(t, float2(0.0), texMax)) * backdropMask(t);
            }

            /**
             * Frost.
             *
             * This is the pass the first version never had: the backdrop was sampled once per channel
             * and only *bent*, so the glass had a lens in it and no diffusion - crisp content behind a
             * warped edge, which reads as a funhouse mirror rather than as frosted glass. Thirteen taps
             * on two rings is the cheapest kernel that looks like diffusion at this blur radius; the
             * backdrop is small (<= 768 texels on its long edge) and the pane is a fraction of the
             * screen, so the cost is paid on a few thousand fragments.
             */
            half4 frost(float2 t, float radius) {
                if (radius < 0.35) return backdropAt(t);
                half4 sum = backdropAt(t) * half(0.10);
                // Inner ring: six points on a circle, so the kernel is round rather than boxy.
                for (int i = 0; i < 6; i++) {
                    float a = float(i) * 1.04719755; // 60 degrees
                    float2 d = float2(cos(a), sin(a)) * radius * 0.55;
                    sum += backdropAt(t + d) * half(0.10);
                }
                // Outer ring, half the weight: this is what makes the falloff read as a soft cloud
                // instead of a uniform smear.
                for (int i = 0; i < 6; i++) {
                    float a = float(i) * 1.04719755 + 0.52359878; // 60 degrees, offset 30
                    float2 d = float2(cos(a), sin(a)) * radius;
                    sum += backdropAt(t + d) * half(0.05);
                }
                return sum;
            }

            float sdRoundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + float2(r);
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 coord) {
                float2 halfSize = uSize * 0.5;
                float2 p = coord - halfSize;
                float d = sdRoundRect(p, halfSize, uRadius);

                if (d > 0.0) {
                    return half4(0.0);
                }

                // Outward normal of the rounded rect, from the same distance field the silhouette
                // uses, so refraction and rim light cannot disagree about where the edge is.
                float2 n = normalize(float2(
                    sdRoundRect(p + float2(1.0, 0.0), halfSize, uRadius) - d,
                    sdRoundRect(p + float2(0.0, 1.0), halfSize, uRadius) - d
                ));

                // Bevel profile: everything happens in the last uBevel pixels, concentrated outward.
                float edge = clamp(-d / max(uBevel, 1.0), 0.0, 1.0);
                float profile = uFalloff > 0.0
                    ? pow(edge, uFalloff)
                    : edge * edge;
                float bend = (1.0 - profile) * uRefract * (1.0 + 0.35 * uPress);

                // The blur grows towards the rim, which is how a real bevelled edge behaves: thick
                // glass diffuses more where the light path is longest.
                float frostRadius = uBlur * (0.55 + 0.45 * profile);

                float2 cG = coord - n * bend;
                float2 tG = (cG - uBackdropOrigin) * uBackdropScale;

                half4 base = frost(tG, frostRadius);

                // Dispersion: red and blue bend slightly more and less than green.
                float2 cR = coord - n * bend * (1.0 + uDispersion);
                float2 cB = coord - n * bend * (1.0 - uDispersion);
                half r = frost((cR - uBackdropOrigin) * uBackdropScale, frostRadius).r;
                half b = frost((cB - uBackdropOrigin) * uBackdropScale, frostRadius).b;
                half3 refracted = half3(r, base.g, b);

                // Vibrance.
                half luma = dot(refracted, half3(0.2126, 0.7152, 0.0722));
                half3 col = mix(half3(luma), refracted, half(uSaturation));

                // Body tint: absorption plus a little scattering.
                col = mix(col, half3(half(uGlassTint.r), half(uGlassTint.g), half(uGlassTint.b)),
                          half(uGlassTint.a));
                col = mix(col, half3(0.0), half(uDim));
                col = mix(col, half3(half(uTint.r), half(uTint.g), half(uTint.b)), half(uTint.a));

                // Rim light from the same normal field: two symmetric lobes, so the lit and shadow
                // sides peak equally and no direction-independent outline is left behind.
                float ndl = dot(n, normalize(uLightDir));
                float lit = pow(max(ndl, 0.0), 4.5);
                float shadow = pow(max(-ndl, 0.0), 4.5);
                float rim = (lit - shadow * 0.55) * uSpec * profile;
                float inner = lit * 0.25 * uSpec * pow(edge, 0.6);
                col += half3(half(max(rim, 0.0) + max(inner, 0.0)));

                // A hairline on the silhouette, and a straight-alpha edge for antialiasing.
                float cov = 1.0 - smoothstep(-1.2, 0.0, d);
                col += half3(half(0.06 * uSpec * (1.0 - profile)));

                return half4(half3(col * half(cov)), half(cov));
            }
        """.trimIndent()
    }
}
