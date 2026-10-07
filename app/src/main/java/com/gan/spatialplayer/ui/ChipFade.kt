package com.gan.spatialplayer.ui

import android.view.View
import android.widget.HorizontalScrollView

/**
 * Shows a chip strip's scroll cue only while there is something left to scroll to.
 *
 * The cue is our own view rather than `requiresFadingEdge`, which drew a hard-edged band about a chip
 * and a half wide instead of a gradient - it looked like a rectangle laid over the last chip, which is
 * precisely how it was reported. Being a view it can also be *hidden* at the end of the strip, which
 * a fading edge cannot: Android's own fade is a property of the scroller and knows nothing about
 * whether the content is exhausted.
 */
object ChipFade {

    fun attach(scroll: HorizontalScrollView, fade: View) {
        val update = {
            fade.visibility = if (scroll.canScrollHorizontally(1)) View.VISIBLE else View.GONE
        }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> update() }
        // The chips are replaced on a timer, so the content width changes without anyone scrolling;
        // without this the cue would stay hidden after a shorter set replaced a longer one.
        (scroll.getChildAt(0))?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        scroll.post(update)
    }
}
