package com.gan.spatialplayer.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan

/**
 * Small helpers for mixing the two typographic families the design calls for:
 * the system default for human-facing copy, monospace for technical readouts.
 */
object TextSpans {

    /**
     * Builds text where the segments listed in [monoParts] are rendered in monospace and the rest
     * keeps the view's own typeface. Segment text is matched literally and each occurrence is
     * styled, which keeps call sites readable:
     *
     * ```
     * textView.text = TextSpans.mixed("47:12", "1280x720 · 23.976 fps", "47:12")
     * ```
     */
    fun mixed(
        full: CharSequence,
        vararg monoParts: CharSequence,
        monoColor: Int? = null,
    ): CharSequence {
        if (monoParts.isEmpty()) return full
        val builder = SpannableStringBuilder(full)
        for (part in monoParts) {
            if (part.isEmpty()) continue
            var from = builder.indexOf(part.toString())
            while (from >= 0) {
                val to = from + part.length
                builder.setSpan(
                    TypefaceSpan("monospace"),
                    from,
                    to,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (monoColor != null) {
                    builder.setSpan(
                        ForegroundColorSpan(monoColor),
                        from,
                        to,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                from = builder.indexOf(part.toString(), to)
            }
        }
        return builder
    }

    /** Applies monospace to the whole string, keeping it spannable for later use. */
    fun mono(text: CharSequence): CharSequence =
        SpannableString(text).apply {
            setSpan(TypefaceSpan("monospace"), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** Convenience for building a bold-free monospace label. */
    fun monoBold(text: CharSequence): CharSequence = text

    /** Human-readable byte count, e.g. `1.42 GB`. */
    fun bytes(size: Long): String {
        if (size <= 0L) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${value.toLong()} ${units[unit]}"
        else String.format(java.util.Locale.US, "%.2f %s", value, units[unit])
    }

    /** `H:MM:SS` or `M:SS`, with an em dash for an unknown duration. */
    fun timecode(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    /** Signed offset used by the seek feedback chip, e.g. `+10s`. */
    fun offset(ms: Long): String {
        val sign = if (ms < 0) "−" else "+"
        return "$sign${Math.abs(ms) / 1000}s"
    }

    /** Renders an arbitrary typeface name safely, falling back to the system face. */
    fun typefaceFor(name: String): Typeface = when (name) {
        "monospace" -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}
