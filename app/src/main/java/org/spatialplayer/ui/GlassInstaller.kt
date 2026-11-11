package org.spatialplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import org.spatialplayer.R

/**
 * Turns the app's placeholder backgrounds into real glass.
 *
 * Every control was styled with `bg_glass_button`, `bg_glass_button_active`, `bg_glass_bar` or
 * `bg_glass_panel` - XML gradients that stand in for glass and cannot be more than a tint, because a
 * shape drawable has no idea what is behind it. This walks a view tree once and swaps each of them
 * for a [GlassDrawable] of the matching shape, so the material does not have to be installed by hand
 * in five layouts, two sheet builders and the settings screen.
 *
 * ## A view says what it is, and that is what gets matched
 *
 * The layout tags each surface with its role (`glass_role_button`, `glass_role_bar`, ...) and that
 * tag is the only thing consulted. It used to try to recognise the background instead - load the
 * resource, compare `constantState` - and that **cannot work for a `RippleDrawable`**: every
 * `getDrawable` call returns a fresh drawable with a fresh constant state, even for the same resource
 * id. Measured on the main screen: `glass walk matched nothing: 9 painted view(s)`, with every
 * candidate's constant state a different hash from the background's. So every XML-declared surface in
 * the app was left as the flat gradient this class exists to replace, and only the code-built ones
 * (the settings screen, `InspectorSheet`'s rows) ever became glass.
 *
 * The ripple is kept: it is re-wrapped *around* the glass rather than replaced, because its press
 * state is what the glass reads to run the press animation. Losing that would have been the sort of
 * regression that makes a "revamp" worse than what it replaced.
 */
object GlassInstaller {

    /** The material for one role, built per context so dp becomes px. */
    private data class Role(val style: (Context) -> GlassStyle)

    /**
     * The material for each role, keyed by the tag the layout puts on the view.
     *
     * The roundness of a control is a decision about that control, not about which drawable it used
     * to wear - which is why the tag names the *role* and the role carries the shape. `bar` is wider
     * than `button` because a 40 dp control and a full-width bar are not the same object.
     */
    private fun roles(context: Context): Map<Int, Role> = mapOf(
        R.id.glass_role_button to Role { ctx ->
            GlassStyle(cornerRadiusPx = dp(ctx, 1000f), fallbackFill = 0x2BFFFFFF)
        },
        R.id.glass_role_button_active to Role { ctx ->
            GlassStyle(
                cornerRadiusPx = dp(ctx, 1000f),
                tint = 0x32FFFFFF,
                body = 0x12FFFFFF,
                specularStrength = 1f,
                fallbackFill = 0x32FFFFFF,
            )
        },
        R.id.glass_role_bar to Role { ctx ->
            GlassStyle(
                cornerRadiusPx = dp(ctx, 30f),
                bevelWidthPx = 48f,
                refractionPx = 160f,
                blurRadiusPx = 18f,
                tint = 0x1AFFFFFF,
                fallbackFill = 0x2BFFFFFF,
            )
        },
        R.id.glass_role_sheet to Role { ctx ->
            GlassStyle(cornerRadiusPx = dp(ctx, 30f), blurRadiusPx = 24f)
        },
        R.id.glass_role_panel_active to Role { ctx ->
            GlassStyle(cornerRadiusPx = dp(ctx, 26f), blurRadiusPx = 18f, body = 0x12FFFFFF)
        },
        R.id.glass_role_panel to Role { ctx ->
            GlassStyle(
                cornerRadiusPx = dp(ctx, 26f),
                blurRadiusPx = 18f,
                bevelWidthPx = 48f,
                refractionPx = 160f,
                tint = 0x1AFFFFFF,
                fallbackFill = 0x24FFFFFF,
            )
        },
    )

    /**
     * How many panes were installed and how many are being watched, for the log line.
     *
     * Kept as counters rather than a leak of the views: the interesting question is only whether the
     * walk matched anything and whether the sensor has anything to light, and both are numbers.
     */
    @Volatile
    var installedCount: Int = 0
        private set

    @Volatile
    var watchedCount: Int = 0
        private set

    /** Swaps every tagged glass surface in [root]'s subtree for the real material. */
    fun apply(root: View) {
        val context = root.context
        val map = roles(context)
        var installed = 0
        var untagged = 0
        walk(root) { view ->
            val role = view.getTag(R.id.glass_role) ?: view.tag
            if (role !is Int || !map.containsKey(role)) {
                if (view.background != null) untagged++
                return@walk
            }
            val material = map[role]!!.style(context)
            var parent: View? = view
            var sheet = false
            while (parent != null) {
                if (parent.id == R.id.sheet_panel) { sheet = true; break }
                parent = parent.parent as? View
            }
            val style = if (sheet) material.copy(dimAmount = .30f) else material
            // A settings segment changes role in place. Restyle its existing material rather
            // than replacing it or registering another backdrop/sensor/touch listener.
            val current = view.background
            val existing = when (current) {
                is GlassDrawable -> current
                is RippleDrawable -> (0 until current.numberOfLayers)
                    .map { current.getDrawable(it) }.filterIsInstance<GlassDrawable>().firstOrNull()
                else -> null
            }
            if (existing != null) {
                existing.style = style
                if (view.isClickable) Haptics.attachTo(view)
                return@walk
            }
            val glass = GlassDrawable(view, style).apply { radiusPx = style.cornerRadiusPx }
            // A ripple's press state is what the glass drawable reads to run its press animation, so
            // the ripple is kept and the glass becomes its content.
            val background = view.background
            view.background = if (background is RippleDrawable) {
                // RippleDrawable has no getter for its colour, so the one the XML declared is used.
                // An explicit alpha mask keeps RippleDrawable from software-rasterizing the
                // shader-backed content to discover the pressed region.
                val mask = GradientDrawable().apply {
                    cornerRadius = style.cornerRadiusPx
                    setColor(Color.WHITE)
                }
                RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), glass, mask)
            } else {
                glass
            }
            if (view.isClickable) Haptics.attachTo(view)
            watch(view)
            installed++
        }
        installedCount += installed
        Log.i(
            TAG,
            "glass installed on $installed pane(s) in this tree ($installedCount this session), " +
                "$untagged painted view(s) left alone; sensor=${LightSourceController.status()}",
        )
    }

    /** Registers a view with the shared backdrop for as long as it is on screen. */
    fun watch(view: View) {
        if (view.isAttachedToWindow) {
            GlassBackdrop.watch(view)
            LightSourceController.register(view)
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var watching = view.isAttachedToWindow

            override fun onViewAttachedToWindow(v: View) {
                if (!watching) {
                    watching = true
                    GlassBackdrop.watch(v)
                    LightSourceController.register(v)
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (watching) {
                    watching = false
                    GlassBackdrop.unwatch(v)
                    LightSourceController.unregister(v)
                }
            }
        })
    }

    internal fun material(view: View): GlassDrawable? = when (val bg = view.background) {
        is GlassDrawable -> bg
        is RippleDrawable -> (0 until bg.numberOfLayers).map { bg.getDrawable(it) }.filterIsInstance<GlassDrawable>().firstOrNull()
        else -> null
    }
    internal fun isGlass(view: View): Boolean = view is LiquidGlassView || material(view) != null

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    private const val TAG = "GlassInstaller"
}
