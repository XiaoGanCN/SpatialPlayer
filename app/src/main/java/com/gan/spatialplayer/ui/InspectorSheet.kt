package com.gan.spatialplayer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * The shared bottom sheet behind Inspection, Metadata, Decoder, Subtitles, Audio and Scaling.
 *
 * Built in code rather than XML because the six panels share one structure - a scrolling stack of
 * [Row]s - and expressing that as six near-identical layouts would be worse to read and worse to
 * keep consistent.
 *
 * Typography follows the app's rule: monospace for anything technical (the two readout panels),
 * the system face for the human-facing option rows.
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
    )

    private val backdrop: View = View(context).apply {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        alpha = 0f
        setOnClickListener { hide() }
    }

    private val sheet: GlassPanelView = GlassPanelView(context).apply {
        orientation = LinearLayout.VERTICAL
        setCornerRadiusDp(28f)
        fillColor = Color.argb(242, 16, 18, 22)
        edgeColor = Color.argb(40, 255, 255, 255)
        setPadding(0, 0, 0, 0)
    }

    private val titleView: TextView = TextView(context).apply {
        typeface = Typeface.SANS_SERIF
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        letterSpacing = 0.01f
    }

    private val tabStrip: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private val contentContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), dp(20))
    }

    private val scrollView: ScrollView = ScrollView(context).apply {
        isFillViewport = false
        addView(
            contentContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    var callback: Callback? = null

    private var currentPanel: Panel = Panel.INSPECTION
    private var choices: Map<Panel, List<Choice>> = emptyMap()

    /** Rows currently rendered per panel, so [setChoices] can be re-applied cheaply. */
    private val tabViews = LinkedHashMap<Panel, TextView>()

    init {
        visibility = View.GONE
        isClickable = true

        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Sheet header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(12), dp(8))
        }

        val handle = View(context).apply {
            setBackgroundColor(Color.argb(60, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }
        header.addView(handle)

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            titleView,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        val closeButton = ImageButton(context).apply {
            setBackgroundResource(R.drawable.bg_glass_button)
            setImageResource(R.drawable.ic_close)
            setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
            contentDescription = context.getString(R.string.close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { hide() }
        }
        titleRow.addView(closeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(titleRow)
        header.addView(
            tabStrip,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        sheet.addView(
            header,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        sheet.addView(
            scrollView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val sheetParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(8)
            rightMargin = dp(8)
            bottomMargin = dp(8)
        }
        addView(sheet, sheetParams)

        buildTabs()
    }

    private fun buildTabs() {
        tabStrip.removeAllViews()
        tabViews.clear()
        for (panel in Panel.entries) {
            val tab = TextView(context).apply {
                text = context.getString(panel.titleRes)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setOnClickListener { show(panel) }
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_button)
            }
            val params = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(7) }
            tabStrip.addView(tab, params)
            tabViews[panel] = tab
        }
    }

    /** Supply the option rows a panel should display. */
    fun setChoices(panel: Panel, list: List<Choice>) {
        choices = choices + (panel to list)
        if (panel == currentPanel) render()
    }

    fun show(panel: Panel) {
        currentPanel = panel
        render()
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            backdrop.animate().alpha(1f).setDuration(180L).start()
            sheet.translationY = sheet.height.coerceAtLeast(dp(400)).toFloat()
            sheet.alpha = 0f
            sheet.post {
                sheet.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(260L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        backdrop.animate().alpha(0f).setDuration(160L).start()
        sheet.animate()
            .translationY(sheet.height.toFloat())
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                visibility = View.GONE
                callback?.onSheetClosed()
            }
            .start()
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

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

        titleView.text = context.getString(currentPanel.titleRes)
        contentContainer.removeAllViews()

        when (currentPanel) {
            Panel.INSPECTION, Panel.METADATA -> {
                val readout = callback?.onReadoutRequested(currentPanel).orEmpty()
                contentContainer.addView(
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
                    contentContainer.addView(
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
                        contentContainer.addView(sectionHeader(choice.section))
                    }
                    contentContainer.addView(optionRow(choice))
                }
            }
        }
    }

    private fun sectionHeader(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        typeface = Typeface.SANS_SERIF
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.16f
        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        setPadding(0, dp(16), 0, dp(6))
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
                setOnClickListener {
                    callback?.onChoiceSelected(currentPanel, choice)
                }
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

        val params = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        container.layoutParams = params
        return container
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** The panel currently displayed, for the activity's refresh loop. */
    val shownPanel: Panel get() = currentPanel

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Keep the sheet clear of the gesture navigation bar.
        val rootInsets = androidx.core.view.ViewCompat.getRootWindowInsets(this)
        val bottom = rootInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        (sheet.layoutParams as? LayoutParams)?.let {
            it.bottomMargin = dp(8) + bottom
            sheet.layoutParams = it
        }
        (sheet.getChildAt(0) as? ViewGroup)?.let { header ->
            header.setPadding(dp(20), dp(10) + (rootInsets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.top ?: 0), dp(12), dp(8))
        }
    }
}
