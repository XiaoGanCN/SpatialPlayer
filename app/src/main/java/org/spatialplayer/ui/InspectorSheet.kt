package org.spatialplayer.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.spatialplayer.R

/** Player subpanels share the GPU lens shell; native HDR video remains on its own surface. */
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

        /**
         * The sheet is about to display [panel], so its rows should be supplied now.
         *
         * The tab strip lives in here and switches panels without the host ever hearing about it,
         * so a panel whose rows were only ever pushed by the host at the moment one of *its own*
         * entry points opened it had no rows to show when it was reached by tab instead - the sheet
         * rendered its empty marker. Pulling the rows on every show is what makes the tabs work.
         */
        fun onPanelShown(panel: Panel)

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
         *
         * [defaultValue] is what a double tap on the reading restores; without it the reading is
         * only typeable, not resettable.
         */
        val range: ClosedFloatingPointRange<Float>? = null,
        val stepSize: Float = 1f,
        val defaultValue: Float? = null,
    )

    var callback: Callback? = null
    var onNavigateBack: (() -> Unit)? = null

    private var dialog: Dialog? = null
    private var sheet: View? = null
    private var titleView: TextView? = null
    private var contentContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null

    private var currentPanel: Panel = Panel.INSPECTION
    private var choices: Map<Panel, List<Choice>> = emptyMap()
    private var renderedChoices:List<Choice> = emptyList()
    private var renderedPanel:Panel?=null
    private val choiceUpdates=ArrayList<(Choice)->Unit>()
    private var loadingPanel=false

    init {
        visibility = View.GONE
    }

    val isOpen: Boolean get() = dialog?.isShowing == true

    val shownPanel: Panel get() = currentPanel

    /** Supplies the option rows a panel should display. */
    fun setChoices(panel: Panel, list: List<Choice>) {
        choices = choices + (panel to list)
        if (loadingPanel || panel != currentPanel || contentContainer==null) return
        val stable=renderedPanel==panel && renderedChoices.size==list.size &&
            renderedChoices.zip(list).all { (old,next) ->
                old.copy(value=next.value,selected=next.selected,enabled=next.enabled,subtitle=next.subtitle)==next &&
                    (old.subtitle==null)==(next.subtitle==null)
            }
        if(stable) {
            list.zip(choiceUpdates).forEach {(choice,update)->update(choice)}
            if(renderedChoices!=list)sheet?.let {GlassBackdrop.materialChanged(it)}
            renderedChoices=list
        } else render()
    }

    fun show(panel:Panel)=show(panel,null)
    internal fun show(panel: Panel,reuse:SheetWindow?) {
        currentPanel = panel
        // Before anything is drawn, and whatever route reached here - a tab, an overflow row, a
        // long-press - so the rows always describe the panel that is about to appear.
        loadingPanel=true
        try {callback?.onPanelShown(panel)}finally {loadingPanel=false}
        val existing = dialog
        if (existing != null && existing.isShowing) {
            render()
            applyWindowFlags(existing)
            return
        }
        buildDialog(reuse).also { d ->
            dialog = d
            render()
            applyWindowFlags(d)
            if(!d.isShowing)GlassTheme.showSheet(d,sheet as GlassSheetLayout)
            else GlassInstaller.apply(sheet!!)
        }
    }

    internal fun takeWindow():SheetWindow? {
        val window=dialog as? GlassSheetDialog ?: return null
        val pane=sheet as? GlassSheetLayout ?: return null
        window.setOnDismissListener(null);window.setOnCancelListener(null)
        pane.animate().setListener(null).cancel()
        dialog=null;sheet=null;titleView=null;contentContainer=null;scrollView=null
        return SheetWindow(window,pane)
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

    private fun buildDialog(reuse:SheetWindow?):Dialog {
        val d=reuse?.dialog ?: GlassSheetDialog(context)
        val navigate={if(onNavigateBack!=null)onNavigateBack?.invoke() else hide();Unit}
        d.navigateBack=navigate
        val panel=reuse?.panel ?: GlassSheetLayout(context,context.getString(currentPanel.titleRes),::hide)
        panel.configureHeader(context.getString(currentPanel.titleRes),::hide,if(onNavigateBack!=null)navigate else null)
        sheet = panel
        titleView = panel.title
        contentContainer = panel.content
        scrollView = panel.scroll
        if(reuse==null)GlassTheme.setSheetContent(d,panel)
        d.setOnCancelListener { callback?.onSheetClosed() }
        return d
    }

    // ------------------------------------------------------------------ window

    private fun applyWindowFlags(d: Dialog) {
        GlassTheme.configureSheet(d, minOf((resources.displayMetrics.heightPixels * 0.78f).toInt(), dp(if (currentPanel == Panel.SPEED) 390 else 620)))
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        try { renderContent() } finally {
            sheet?.let { GlassInstaller.apply(it) }
            dialog?.takeIf { it.isShowing }?.let { applyWindowFlags(it) }
        }
    }

    private fun renderContent() {
        titleView?.text = context.getString(currentPanel.titleRes)
        val container = contentContainer ?: return
        container.removeAllViews()
        choiceUpdates.clear()
        renderedPanel=currentPanel
        renderedChoices=choices[currentPanel].orEmpty()

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

            Panel.SPEED -> renderSpeed(container)
            else -> {
                val list = choices[currentPanel].orEmpty()
                if (list.isEmpty()) {
                    container.addView(
                        TextView(context).apply {
                            id = R.id.sheet_empty
                            text = "—"
                            typeface = Typeface.MONOSPACE
                            setTextColor(0xD9FFFFFF.toInt())
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
            id = R.id.sheet_section
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
                this.text = text
                typeface = UiTypography.body(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0f
                setTextColor(0xD9FFFFFF.toInt())
            },
        )
        return wrapper
    }

    private fun renderSpeed(container: LinearLayout) {
        var speed = choices[Panel.SPEED].orEmpty().firstOrNull { it.selected }?.value?.toFloatOrNull() ?: 1f
        val readout = TextView(context).apply {
            tag = "speed_readout"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = String.format(java.util.Locale.US, "%.2f×", speed)
        }
        container.addView(readout, LinearLayout.LayoutParams(-1, dp(92)))
        val slider = GlassSlider(context).apply {
            tag = "speed_slider"
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            max = 55
            progress = kotlin.math.round((speed - 0.25f) / 0.05f).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        val presets = LinkedHashMap<Float, TextView>()
        fun markPreset() {
            for ((value, button) in presets) {
                button.setTag(R.id.glass_role, if (kotlin.math.abs(speed-value) < .025f) R.id.glass_role_button_active else R.id.glass_role_button)
                GlassInstaller.apply(button)
            }
        }
        fun apply(value: Float) {
            speed = value
            readout.text = String.format(java.util.Locale.US, "%.2f×", value)
            slider.progress = kotlin.math.round((value - 0.25f) / 0.05f).toInt()
            callback?.onChoiceSelected(Panel.SPEED, Choice(Panel.SPEED, value.toString(), "Playback speed"))
            markPreset()
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) apply(0.25f + progress * 0.05f)
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {}
        })
        container.addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        for (group in listOf(listOf(0.5f, 0.75f, 1f), listOf(1.25f, 1.5f, 2f))) {
            val row = LinearLayout(context)
            for (value in group) row.addView(TextView(context).apply {
                tag = "speed_$value"
                presets[value] = this
                text = "${value}×"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GlassTheme.control(context)
                setTag(R.id.glass_role, R.id.glass_role_button)
                setOnClickListener { apply(value) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
            container.addView(row)
        }
        markPreset()
        SliderReading.attach(readout, { speed }, 0.25f..3f, 0.05f,
            { String.format(java.util.Locale.US, "%.2f", it) }, 1f, ::apply)
    }

    private fun optionRow(choice: Choice): View {
        var current=choice
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GlassTheme.control(context, choice.selected)
            setTag(R.id.glass_role, if (choice.selected) R.id.glass_role_panel_active else R.id.glass_role_panel)
            alpha = if (choice.enabled || choice.value.endsWith("_header")) 1f else 0.42f
            isSelected = choice.selected
            isClickable = choice.enabled
            isFocusable = choice.enabled
            setOnClickListener { if(current.enabled)callback?.onChoiceSelected(currentPanel,current) }
            isClickable=choice.enabled
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = choice.title
                typeface = UiTypography.body(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        container.addView(titleRow)

        val subtitleView=choice.subtitle?.let { subtitle ->
                TextView(context).apply {
                    text = subtitle
                    typeface = UiTypography.body(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(0xD9FFFFFF.toInt())
                    setPadding(0, dp(4), 0, 0)
                }.also {container.addView(it)}
        }
        choiceUpdates.add {next ->
            current=next
            container.isSelected=next.selected
            container.isClickable=next.enabled
            container.isFocusable=next.enabled
            container.alpha=if(next.enabled || next.value.endsWith("_header"))1f else .42f
            subtitleView?.let {if(it.text.toString()!=next.subtitle)it.text=next.subtitle}
            container.setTag(R.id.glass_role,if(next.selected)R.id.glass_role_panel_active else R.id.glass_role_panel)
            GlassInstaller.apply(container)
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
     * the effect of a change can be judged without releasing. It is also *editable*: one tap opens
     * a field so an exact number can be typed, and a double tap restores the row's default - the
     * two things a drag cannot express.
     */
    private fun sliderRow(choice: Choice): View {
        val range = choice.range ?: return optionRow(choice)
        val current = choice.value.toFloatOrNull() ?: range.start

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = GlassTheme.control(context)
            setTag(R.id.glass_role, R.id.glass_role_panel)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = choice.title
                typeface = UiTypography.body(context)
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
            // The reading is a touch target as well as a label: give it a body, or it is a 12 sp
            // strip of digits that has to be hit exactly.
            val pad = dp(7)
            setPadding(pad, dp(3), pad, dp(3))
        }
        header.addView(reading)
        wrapper.addView(header)

        val steps = (((range.endInclusive - range.start) / choice.stepSize).toInt())
            .coerceAtLeast(1)
        val slider = GlassSlider(context).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            max = steps
            progress = (((current - range.start) / choice.stepSize).toInt()).coerceIn(0, steps)
        }

        /**
         * Applies a value that arrived from somewhere other than the bar - typed, or a reset - and
         * keeps the bar and the reading in step with it. `onChoiceSelected` is fired once with the
         * snapped value, which is the same callback a drag uses, so the activity cannot tell the
         * two apart and no second apply path exists.
         */
        fun applyFromElsewhere(value: Float) {
            val snapped = snapToStep(value, range, steps, choice.stepSize)
            slider.progress = ((snapped - range.start) / choice.stepSize).toInt().coerceIn(0, steps)
            reading.text = formatReading(snapped)
            callback?.onChoiceSelected(currentPanel, choice.copy(value = snapped.toString()))
        }

        SliderReading.attach(
            view = reading,
            current = {
                snapToStep(range.start + slider.progress * choice.stepSize, range, steps, choice.stepSize)
            },
            range = range,
            step = choice.stepSize,
            format = ::formatReading,
            default = choice.defaultValue,
        ) { value -> applyFromElsewhere(value) }

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
                    // GlassSlider owns feedback.
                }

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    // GlassSlider owns feedback.
                }
            },
        )
        wrapper.addView(
            slider,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        // A control nobody can see is a control nobody uses: the reading gives no sign that it is
        // tappable, so the hint says it. Shown only while it can be true - a row with no default
        // cannot be reset, and promising that would be a lie.
        if (choice.defaultValue != null) {
            wrapper.addView(
                TextView(context).apply {
                    text = context.getString(R.string.slider_hint)
                    typeface = UiTypography.body(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(0xD9FFFFFF.toInt())
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }

        wrapper.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        choiceUpdates.add {next ->
            val value=next.value.toFloatOrNull() ?: range.start
            slider.progress=kotlin.math.round((value-range.start)/choice.stepSize).toInt().coerceIn(0,steps)
            slider.isEnabled=next.enabled
            reading.text=formatReading(range.start+slider.progress*choice.stepSize)
        }
        return wrapper
    }

    /** Integers print without a decimal point; everything else keeps one. */
    private fun formatReading(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    /**
     * Maps any value onto the bar's own grid, so a typed number can never leave the bar showing a
     * position it cannot take.
     */
    private fun snapToStep(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        stepSize: Float,
    ): Float = range.start + ((value - range.start) / stepSize).toInt().coerceIn(0, steps) * stepSize

    /**
     * Ids of what the content container is currently showing, for the debug panel probe.
     *
     * The rows are built in code in a separate dialog window, so this is the only description of them
     * that exists: `uiautomator dump` cannot settle on the animating player, and a screenshot cannot
     * tell one row from another. Each entry is `View.NO_ID` for an option row (they carry no id,
     * because nothing looks one up), otherwise the child's own id.
     *
     * Returning the ids rather than a count is what lets the caller say *why* a panel looks empty -
     * the `"no rows"` marker is one of these ids, and a section heading is another - instead of
     * inferring it from a number that a heading can inflate.
     */
    internal fun rootForTest(): View? = sheet

    internal fun viewTaggedForTest(tag: String): View? = sheet?.findViewWithTag(tag)

    internal fun visibleRowIds(): List<Int> {
        val container = contentContainer ?: return emptyList()
        return (0 until container.childCount).map { container.getChildAt(it).id }
    }

    /** The `"no rows"` marker's id, so a caller does not have to know which id means "empty". */
    internal val emptyMarkerId: Int get() = R.id.sheet_empty

    /** A section heading's id, which is chrome rather than a row. */
    internal val sectionHeadingId: Int get() = R.id.sheet_section

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
