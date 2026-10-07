package com.gan.spatialplayer.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gan.spatialplayer.R
import com.gan.spatialplayer.ui.TextSpans.mono

/**
 * The shared sheet behind Inspection, Metadata, Decoder, Subtitles, Audio and Scaling.
 *
 * ## Why this presents in its own window
 *
 * The brief asks for a liquid-glass surface, and a view inside the player's own window cannot
 * deliver one: the picture is drawn by a `SurfaceView`, which is composited by SurfaceFlinger
 * rather than drawn into the window's canvas, so there is nothing behind an in-window panel to
 * blur. The only way to genuinely frost the video is to put the panel in a *separate, translucent
 * window* that declares a background blur radius. On Android 12+ that asks the system compositor to
 * blur whatever is behind the window - which includes the video - and the result is real glass
 * rather than a tinted rectangle.
 *
 * On older platforms the blur is simply unavailable, so the same layout falls back to a translucent
 * gradient surface. Nothing about the panel's behaviour changes.
 */
class InspectorSheet @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Panel(val titleRes: Int) {
        INSPECTION(R.string.inspection),
        METADATA(R.string.metadata),
        DECODER(R.string.decoder_settings),
        SUBTITLES(R.string.subtitle_settings),
        AUDIO(R.string.audio_settings),
        SCALING(R.string.aspect_settings),
        SPEED(R.string.speed),
        CHAPTERS(R.string.chapters),
    }

    interface Callback {
        fun onSheetClosed()

        /** Read-only panels are rendered by pulling the current report text. */
        fun onReadoutRequested(panel: Panel): String

        /** A row in a control panel was chosen. */
        fun onChoiceSelected(panel: Panel, choice: Choice)
    }

    /**
     * One selectable option. [value] is an opaque tag the activity interprets; [enabled] lets a
     * panel show an action that is known to be unusable on this device.
     */
    data class Choice(
        val panel: Panel,
        val value: String,
        val title: String,
        val subtitle: String? = null,
        val selected: Boolean = false,
        val enabled: Boolean = true,
        val section: String? = null,
        /**
         * When set, the row renders as a slider the user can drag. [value] carries the current
         * reading and is echoed back through [Callback.onChoiceSelected] as it moves, so changes
         * apply live rather than on release.
         */
        val range: ClosedFloatingPointRange<Float>? = null,
        val stepSize: Float = 1f,
    )

    var callback: Callback? = null

    private var dialog: Dialog? = null
    private var sheet: View? = null
    private var titleView: TextView? = null
    private var contentContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null

    private val tabViews = LinkedHashMap<Panel, TextView>()
    private var currentPanel: Panel = Panel.INSPECTION
    private var choices: Map<Panel, List<Choice>> = emptyMap()

    init {
        visibility = View.GONE
    }

    val isOpen: Boolean get() = dialog?.isShowing == true

    val shownPanel: Panel get() = currentPanel

    /** Supplies the option rows a panel should display. */
    fun setChoices(panel: Panel, list: List<Choice>) {
        choices = choices + (panel to list)
        if (panel == currentPanel) render()
    }

    fun show(panel: Panel) {
        currentPanel = panel
        val existing = dialog
        if (existing != null && existing.isShowing) {
            render()
            return
        }
        buildDialog()?.also { d ->
            dialog = d
            d.show()
            applyWindowFlags(d)
            render()
            animateIn()
            // `show` must happen before the window exists to configure blur reliably.
            applyWindowBlur(d)
        }
    }

    /** Dismisses without animation, for lifecycle teardown. */
    fun dismissNow() {
        dialog?.dismiss()
        dialog = null
    }

    fun hide() {
        val d = dialog ?: return
        if (!d.isShowing) return
        val sheetView = sheet
        if (sheetView == null) {
            d.dismiss()
            dialog = null
            callback?.onSheetClosed()
            return
        }
        sheetView.animate()
            .translationY(sheetView.height.toFloat())
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                d.dismiss()
                dialog = null
                callback?.onSheetClosed()
            }
            .start()
    }

    // ------------------------------------------------------------------ construction

    private fun buildDialog(): Dialog? {
        val ctx = context
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = FrameLayout(ctx)
        root.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // Tap outside the sheet to dismiss. No dim: the window itself is translucent and relies on
        // the blur, so an extra scrim would defeat the glass.
        val scrim = View(ctx).apply {
            setBackgroundColor(Color.argb(40, 0, 0, 0))
            setOnClickListener { hide() }
        }
        root.addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_glass_sheet_window)
        }
        sheet = panel

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(12), dp(8))
        }
        header.addView(
            View(ctx).apply {
                setBackgroundColor(Color.argb(70, 255, 255, 255))
            },
            LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            },
        )

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(ctx).apply {
            typeface = Typeface.SANS_SERIF
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        titleRow.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(
            ImageButton(ctx).apply {
                setBackgroundResource(R.drawable.bg_glass_button)
                setImageResource(R.drawable.ic_close)
                setColorFilter(ContextCompat.getColor(ctx, R.color.text_primary))
                contentDescription = ctx.getString(R.string.close)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { hide() }
            },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        header.addView(titleRow)

        val tabStrip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            HorizontalScrollViewCompat.wrap(ctx, tabStrip),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) },
        )
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(24))
        }
        scrollView = ScrollView(ctx).apply {
            isFillViewport = false
            addView(contentContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            panel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            },
        )

        d.setContentView(root)
        buildTabs(tabStrip)
        return d
    }

    private fun buildTabs(tabStrip: LinearLayout) {
        tabStrip.removeAllViews()
        tabViews.clear()
        for (panel in Panel.entries) {
            val tab = TextView(context).apply {
                text = context.getString(panel.titleRes)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_button)
                setOnClickListener { show(panel) }
            }
            tabStrip.addView(
                tab,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(7) },
            )
            tabViews[panel] = tab
        }
    }

    // ------------------------------------------------------------------ window

    private fun applyWindowFlags(d: Dialog) {
        val window = d.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // Dimming would hide the very backdrop the glass is meant to reveal.
        window.setDimAmount(0f)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        window.setGravity(Gravity.BOTTOM)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            dimAmount = 0f
        }
        // Keep the sheet clear of the gesture navigation bar.
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this)
        val bottom = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        (sheet?.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(10) + bottom
            sheet?.layoutParams = it
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * Requests the real backdrop blur.
     *
     * This is what makes the surface read as glass: the system compositor blurs everything behind
     * this window - including the video, which an in-window view could never reach - and the
     * translucent fill above it is then read as frosted rather than merely tinted.
     */
    private fun applyWindowBlur(d: Dialog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val window = d.window ?: return
        runCatching {
            window.setBackgroundBlurRadius(dp(BLUR_RADIUS_DP))
        }.onFailure {
            // Some devices ship with background blur disabled; the translucent fill still reads
            // as glass, just without the frost.
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun animateIn() {
        val sheetView = sheet ?: return
        sheetView.post {
            sheetView.translationY = sheetView.height.coerceAtLeast(dp(400)).toFloat()
            sheetView.alpha = 0f
            sheetView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun render() {
        for ((panel, tab) in tabViews) {
            val active = panel == currentPanel
            tab.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.text_primary else R.color.text_secondary,
                ),
            )
            tab.background = ContextCompat.getDrawable(
                context,
                if (active) R.drawable.bg_glass_button_active else R.drawable.bg_glass_button,
            )
        }

        titleView?.text = context.getString(currentPanel.titleRes)
        val container = contentContainer ?: return
        container.removeAllViews()

        when (currentPanel) {
            Panel.INSPECTION, Panel.METADATA -> {
                val readout = callback?.onReadoutRequested(currentPanel).orEmpty()
                container.addView(
                    TextView(context).apply {
                        typeface = Typeface.MONOSPACE
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(ContextCompat.getColor(context, R.color.text_mono))
                        letterSpacing = 0.02f
                        setLineSpacing(dp(4).toFloat(), 1f)
                        text = readout
                        setTextIsSelectable(true)
                    },
                )
            }

            else -> {
                val list = choices[currentPanel].orEmpty()
                if (list.isEmpty()) {
                    container.addView(
                        TextView(context).apply {
                            text = "—"
                            typeface = Typeface.MONOSPACE
                            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        },
                    )
                }
                var lastSection: String? = null
                for (choice in list) {
                    if (choice.section != null && choice.section != lastSection) {
                        lastSection = choice.section
                        container.addView(sectionHeader(choice.section))
                    }
                    container.addView(if (choice.range != null) sliderRow(choice) else optionRow(choice))
                }
            }
        }
    }

    /**
     * A section heading preceded by a hairline rule.
     *
     * The rule is what makes the grouping readable; with headings alone the option rows ran
     * together and the panel looked like one undifferentiated list.
     */
    private fun sectionHeader(text: String): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(2))
        }
        wrapper.addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
                .apply { bottomMargin = dp(10) },
        )
        wrapper.addView(
            TextView(context).apply {
                this.text = text.uppercase()
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.16f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            },
        )
        return wrapper
    }

    private fun optionRow(choice: Choice): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = ContextCompat.getDrawable(
                context,
                if (choice.selected) R.drawable.bg_glass_button_active else R.drawable.bg_glass_button,
            )
            alpha = if (choice.enabled) 1f else 0.42f
            isClickable = choice.enabled
            isFocusable = choice.enabled
            if (choice.enabled) {
                setOnClickListener { callback?.onChoiceSelected(currentPanel, choice) }
            }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = choice.title
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        if (choice.selected) {
            titleRow.addView(
                TextView(context).apply {
                    text = "●"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(context, R.color.accent))
                },
            )
        }
        container.addView(titleRow)

        choice.subtitle?.let { subtitle ->
            container.addView(
                TextView(context).apply {
                    text = mono(subtitle)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }

        container.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        return container
    }

    /**
     * A labelled slider row.
     *
     * The reading is shown in monospace next to the title and updates while the finger is down, so
     * the effect of a change can be judged without releasing.
     */
    private fun sliderRow(choice: Choice): View {
        val range = choice.range ?: return optionRow(choice)
        val current = choice.value.toFloatOrNull() ?: range.start

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_button)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = choice.title
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        val reading = TextView(context).apply {
            text = formatReading(current)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.text_mono))
        }
        header.addView(reading)
        wrapper.addView(header)

        val steps = (((range.endInclusive - range.start) / choice.stepSize).toInt() - 1)
            .coerceAtLeast(1)
        val slider = android.widget.SeekBar(context).apply {
            max = steps
            progress = (((current - range.start) / choice.stepSize).toInt()).coerceIn(0, steps)
        }
        slider.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    val value = range.start + progress * choice.stepSize
                    reading.text = formatReading(value)
                    if (fromUser) {
                        callback?.onChoiceSelected(
                            currentPanel,
                            choice.copy(value = value.toString()),
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                    Haptics.touch(reading)
                }

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    Haptics.release(reading)
                }
            },
        )
        wrapper.addView(
            slider,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        wrapper.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        return wrapper
    }

    /** Integers print without a decimal point; everything else keeps one. */
    private fun formatReading(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Blur strength for the frosted backdrop, in dp. Raises with the display density. */
        const val BLUR_RADIUS_DP = 40
    }
}

/**
 * Small helper so the tab strip can scroll horizontally when there are more tabs than fit.
 */
private object HorizontalScrollViewCompat {
    fun wrap(context: Context, child: View): View =
        android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(child)
        }
}
