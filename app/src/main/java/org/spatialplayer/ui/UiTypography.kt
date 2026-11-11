package org.spatialplayer.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import org.spatialplayer.R

/** Stable typography across screens and device theme/font overrides. Inter is bundled under OFL. */
object UiTypography {
    private var cached: Typeface? = null
    fun body(context: Context): Typeface = cached ?: (ResourcesCompat.getFont(context, R.font.inter)
        ?: Typeface.SANS_SERIF).also { cached = it }
}
