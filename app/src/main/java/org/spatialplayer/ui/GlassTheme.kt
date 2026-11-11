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
        val blurEnabled = ctx.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        window.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (blurEnabled) intArrayOf(0xA62C3037.toInt(), 0xCE14171B.toInt()) else intArrayOf(0xF22C3037.toInt(), 0xFA14171B.toInt())).apply {
            cornerRadius = dp(ctx, 30f).toFloat()
            setStroke(1, 0x42FFFFFF)
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.22f)
        window.setBackgroundBlurRadius(dp(ctx, 32f))
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        val width = minOf(ctx.resources.displayMetrics.widthPixels - dp(ctx, 24f), dp(ctx, 620f))
        window.setLayout(width, height)
        window.attributes = window.attributes.apply { y = dp(ctx, 12f) }
        dialog.setCanceledOnTouchOutside(true)
    }
}
