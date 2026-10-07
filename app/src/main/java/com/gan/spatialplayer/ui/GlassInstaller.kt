package com.gan.spatialplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.gan.spatialplayer.R

/**
 * Turns the app's existing glass backgrounds into real glass.
 *
 * Every control was styled with `bg_glass_button`, `bg_glass_button_active` or `bg_glass_panel` - XML
 * gradients that stand in for glass and cannot be more than a tint, because a shape drawable has no
 * idea what is behind it. Rather than hand-editing five layouts and two sheet builders and the
 * settings screen, this walks a view tree once and swaps any view wearing one of those backgrounds
 * for a [GlassDrawable] with the matching shape.
 *
 * The ripple is kept: it is re-wrapped *around* the glass rather than replaced, so a button still
 * reacts to a press. Losing that would have been the sort of regression that makes a "revamp" worse
 * than what it replaced.
 */
object GlassInstaller {

    /** Radius and material per background resource, so a button and a panel stay different shapes. */
    private data class Role(val radiusDp: Float, val style: (Context) -> GlassStyle)

    private fun roles(context: Context): Map<Int, Role> = mapOf(
        R.drawable.bg_glass_button to Role(18f) { ctx ->
            GlassStyle(cornerRadiusPx = dp(ctx, 18f), fallbackFill = 0x2BFFFFFF)
        },
        R.drawable.bg_glass_button_active to Role(18f) { ctx ->
            GlassStyle(
                cornerRadiusPx = dp(ctx, 18f),
                tint = 0x598AB4F8.toInt(),
                body = 0x1F8AB4F8,
                specularStrength = 1.15f,
                fallbackFill = 0x4D8AB4F8,
            )
        },
        R.drawable.bg_glass_panel to Role(26f) { ctx ->
            GlassStyle(
                cornerRadiusPx = dp(ctx, 26f),
                blurTexels = 9f,
                bevelWidthPx = dp(ctx, 18f),
                refractionPx = dp(ctx, 8f),
                tint = 0x1AFFFFFF,
                fallbackFill = 0x24FFFFFF,
            )
        },
    )

    /** Swaps every matching background in [root]'s subtree. Safe to call more than once. */
    fun apply(root: View) {
        val context = root.context
        val map = roles(context)
        walk(root) { view ->
            val background = view.background ?: return@walk
            val match = map.entries.firstOrNull { (res, _) ->
                val candidate = runCatching { ContextCompat.getDrawable(context, res) }.getOrNull()
                candidate != null &&
                    (candidate === background || candidate.constantState == background.constantState)
            } ?: return@walk

            val style = match.value.style(context)
            val glass = GlassDrawable(view, style).apply { radiusPx = style.cornerRadiusPx }
            // Ripples keep their press state; the glass becomes their content.
            view.background = if (background is RippleDrawable) {
                // RippleDrawable has no getter for its colour, so the same one the XML declared is
                // used here - the tint is part of the material, not of the ripple.
                RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), glass, null)
            } else {
                glass
            }
            watch(view)
        }
    }

    /** Registers a view with the shared backdrop for as long as it is on screen. */
    fun watch(view: View) {
        if (view.isAttachedToWindow) GlassBackdrop.watch(view)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private var watching = false

            override fun onViewAttachedToWindow(v: View) {
                if (!watching) {
                    watching = true
                    GlassBackdrop.watch(v)
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                if (watching) {
                    watching = false
                    GlassBackdrop.unwatch(v)
                }
            }
        })
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density
}
