package org.spatialplayer.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.WindowManager

/** Shared material, geometry and motion for controls, sheets and editors. */
object GlassTheme {
    fun dp(context: Context, value: Float) = (context.resources.displayMetrics.density * value).toInt()
    fun surface(context: Context, radius: Float = 24f, selected: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (selected) intArrayOf(0x36FFFFFF, 0x20FFFFFF) else intArrayOf(0x1FFFFFFF, 0x0DFFFFFF),
    ).apply {
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 0.5f).coerceAtLeast(1), 0x32FFFFFF)
    }
    fun control(context: Context, selected: Boolean = false): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, 22f).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), surface(context, 22f, selected), mask)
    }
    fun configureSheet(dialog: Dialog, height: Int) {
        val window = dialog.window ?: return
        val ctx = dialog.context
        // The shell owns the GPU lens; a second compositor frost makes it opaque and doubles blur.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.setBackgroundBlurRadius(0)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.22f)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        val metrics=ctx.getSystemService(WindowManager::class.java).currentWindowMetrics
        val bars=metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val safeWidth=metrics.bounds.width()-bars.left-bars.right
        val safeHeight=metrics.bounds.height()-bars.top-bars.bottom
        val width=minOf(safeWidth-dp(ctx,24f),dp(ctx,620f)).coerceAtLeast(1)
        val panel=window.decorView.findViewById<GlassSheetLayout>(org.spatialplayer.R.id.sheet_panel)
        val natural=panel?.preferredHeight(width) ?: height
        val cap=minOf(safeHeight-dp(ctx,24f),dp(ctx,860f)).coerceAtLeast(1)
        val bounded=natural.coerceIn(dp(ctx,120f).coerceAtMost(cap),cap)
        window.setLayout(width,bounded)
        window.attributes=window.attributes.apply {y=bars.bottom+dp(ctx,12f)}
        val key=metrics.bounds.width().toLong().shl(32) or metrics.bounds.height().toLong()
        if(window.decorView.getTag(org.spatialplayer.R.id.sheet_resize)==null) {
            window.decorView.setTag(org.spatialplayer.R.id.sheet_resize,key)
            window.decorView.addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_ ->
                val bounds=ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                val next=bounds.width().toLong().shl(32) or bounds.height().toLong()
                if(view.getTag(org.spatialplayer.R.id.sheet_resize)!=next) {
                    view.setTag(org.spatialplayer.R.id.sheet_resize,next)
                    view.post { if(dialog.isShowing)configureSheet(dialog,height) }
                }
            }
        }
        dialog.setCanceledOnTouchOutside(true)
    }
}
