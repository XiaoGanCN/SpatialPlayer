package com.gan.spatialplayer

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gan.spatialplayer.media.DecoderPolicy
import com.gan.spatialplayer.media.DeviceCapabilities
import com.gan.spatialplayer.media.DecoderProfile
import com.gan.spatialplayer.media.FfmpegCodecs
import com.gan.spatialplayer.ui.Haptics

/**
 * Everything the app remembers, in one place, plus the facts worth knowing about the device.
 *
 * Built in code rather than in XML. It is a column of rows that differ only in their label and their
 * control, and three near-identical XML blocks per row would be more to keep in step than the few
 * lines each one takes here - which is also how `InspectorSheet` is built.
 *
 * ## Why the controls are shaped the way they are
 *
 * A row that *cycles* its value when tapped hides its options: you cannot see what is available, and
 * getting back to where you started means going all the way round. Binary settings are a switch
 * because there are only two answers and tapping is the whole interaction. Anything with three or
 * more answers shows all of them at once with the current one marked, so the choice is visible before
 * it is made.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll.addView(column)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_deep))
            addView(header())
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        buildPlayback(column)
        buildDecoding(column)
        buildGestures(column)
        buildAppearance(column)
        buildDevice(column)
        buildDiagnostics(column)

        setContentView(root)
    }

    // ------------------------------------------------------------------ chrome

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(10), dp(16), dp(10))

        addView(
            ImageButton(this@SettingsActivity).apply {
                setBackgroundResource(R.drawable.bg_glass_button)
                setImageResource(R.drawable.ic_arrow_up)
                rotation = -90f
                contentDescription = getString(R.string.close)
                setColorFilter(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )

        addView(
            TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                setPadding(dp(14), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
    }

    // ------------------------------------------------------------------ sections

    private fun buildPlayback(column: LinearLayout) {
        column.addView(sectionHeader(R.string.settings_playback))

        column.addView(
            toggleRow(
                title = getString(R.string.spatial_audio),
                subtitle = getString(R.string.spatial_audio_hint),
                value = settings.spatialEnabled,
            ) { settings.spatialEnabled = it },
        )

        column.addView(
            toggleRow(
                title = getString(R.string.ambient_glow),
                subtitle = "Tints the letterbox with the colours of the picture",
                value = settings.ambientEnabled,
            ) { settings.ambientEnabled = it },
        )

        column.addView(
            sliderRow(
                title = getString(R.string.settings_jump_length),
                from = SettingsStore.MIN_JUMP_MS / 1000f,
                to = SettingsStore.MAX_JUMP_MS / 1000f,
                step = 0.5f,
                value = settings.doubleTapJumpMs / 1000f,
                reading = { getString(R.string.settings_seconds, it) },
            ) { settings.doubleTapJumpMs = (it * 1000f).toLong() },
        )

        column.addView(
            sliderRow(
                title = getString(R.string.settings_subtitle_tracks),
                from = SettingsStore.MIN_SUBTITLE_TRACK_LIMIT.toFloat(),
                to = SettingsStore.MAX_SUBTITLE_TRACK_LIMIT.toFloat(),
                step = 1f,
                value = settings.subtitleTrackLimit.toFloat(),
                reading = { getString(R.string.settings_tracks, it.toInt()) },
            ) { settings.subtitleTrackLimit = it.toInt() },
        )
    }

    private fun buildDecoding(column: LinearLayout) {
        column.addView(sectionHeader(R.string.settings_decoding))

        column.addView(
            segmentedRow(
                title = getString(R.string.decoder_settings),
                options = DecoderProfile.entries.map { it.label to it.name },
                selected = settings.decoderProfile,
            ) { settings.decoderProfile = it },
        )

        column.addView(
            infoRow(
                getString(R.string.decoder_policy),
                if (FfmpegCodecs.isAvailable(androidx.media3.common.MimeTypes.AUDIO_TRUEHD)) {
                    DecoderProfile.entries.firstOrNull { it.name == settings.decoderProfile }
                        ?.detail
                        ?: DecoderPolicy.describeRoute(null, DecoderProfile.AUTO)
                } else {
                    "FFmpeg extension unavailable; AC3, EAC3, TrueHD and DTS will not decode"
                },
            ),
        )
    }

    private fun buildGestures(column: LinearLayout) {
        column.addView(sectionHeader(R.string.settings_gestures))

        column.addView(
            sliderRow(
                title = getString(R.string.settings_vertical_gain),
                from = SettingsStore.MIN_VERTICAL_GAIN,
                to = SettingsStore.MAX_VERTICAL_GAIN,
                step = 0.01f,
                value = settings.verticalGain,
                reading = { "%.0f%%".format(it * 100f) },
            ) { settings.verticalGain = it },
        )

        column.addView(
            infoRow(
                getString(R.string.double_tap_jump),
                "Left half seeks back, right half forward; the capsule's skip buttons match",
            ),
        )
    }

    private fun buildAppearance(column: LinearLayout) {
        column.addView(sectionHeader(R.string.settings_appearance))

        column.addView(
            segmentedRow(
                title = getString(R.string.settings_glass_material),
                options = listOf(
                    getString(R.string.settings_glass_regular) to SettingsStore.GLASS_REGULAR,
                    getString(R.string.settings_glass_clear) to SettingsStore.GLASS_CLEAR,
                ),
                selected = settings.glassMaterial,
            ) { settings.glassMaterial = it },
        )
    }

    private fun buildDevice(column: LinearLayout) {
        column.addView(sectionHeader(R.string.settings_device))

        val hdr = DeviceCapabilities.hdrSnapshot(this)
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        val metrics = resources.displayMetrics

        column.addView(
            infoRow(
                getString(R.string.settings_device),
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
                    "(API ${Build.VERSION.SDK_INT})",
            ),
        )
        column.addView(
            infoRow(
                "Display",
                "${hdr.widthPx}x${hdr.heightPx} · ${metrics.densityDpi} dpi · " +
                    hdr.supportedHdrTypes.joinToString("+").ifEmpty { "SDR" },
            ),
        )
        column.addView(
            infoRow(
                getString(R.string.spatial_audio),
                buildString {
                    append(if (spatial.available) "spatializer available" else "no spatializer")
                    append(if (spatial.enabled) ", enabled" else ", disabled")
                    append(if (spatial.headTrackerAvailable) ", head tracker present" else ", no head tracker")
                    spatial.outputDeviceName?.let { append(", out: $it") }
                },
            ),
        )
    }

    private fun buildDiagnostics(column: LinearLayout) {
        column.addView(sectionHeader(R.string.settings_debug))

        column.addView(
            actionRow(getString(R.string.settings_copy_report)) {
                copyReport()
            },
        )
    }

    /**
     * Everything a bug report needs, as text.
     *
     * The point is that the user does not have to be asked for six separate facts, and the person
     * reading it does not have to guess which build produced it.
     */
    private fun copyReport() {
        val hdr = DeviceCapabilities.hdrSnapshot(this)
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        val report = buildString {
            appendLine("Spatial Player ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("display: ${hdr.widthPx}x${hdr.heightPx}, ${hdr.supportedHdrTypes.joinToString("+").ifEmpty { "SDR" }}")
            appendLine(
                "spatial: available=${spatial.available} enabled=${spatial.enabled} " +
                    "headTracker=${spatial.headTrackerAvailable} " +
                    "canSpatialize=${spatial.outputCanSpatialize} " +
                    "out=${spatial.outputDeviceName ?: "-"}",
            )
            appendLine("ffmpeg: ${if (FfmpegCodecs.isAvailable(androidx.media3.common.MimeTypes.AUDIO_TRUEHD)) "available" else "missing"}")
            appendLine("profile: ${settings.decoderProfile}")
            appendLine("jump: ${settings.doubleTapJumpMs} ms")
            appendLine("vertical gain: ${settings.verticalGain}")
            appendLine("subtitle cap: ${settings.subtitleTrackLimit}")
            appendLine("glass: ${settings.glassMaterial}")
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatial Player", report))
        android.widget.Toast.makeText(this, getString(R.string.settings_report_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ rows

    private fun sectionHeader(res: Int): View = TextView(this).apply {
        text = getString(res).uppercase()
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.18f
        setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
        setPadding(dp(6), dp(22), 0, dp(8))
    }

    private fun rowShell(title: String, subtitle: String?, trailing: View?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_glass_button)
        }
        row.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(this).apply {
                text = title
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        trailing?.let { titleRow.addView(it) }
        row.addView(titleRow)

        subtitle?.let {
            row.addView(
                TextView(this).apply {
                    text = it
                    typeface = Typeface.SANS_SERIF
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
                    setPadding(0, dp(5), 0, 0)
                },
            )
        }
        return row
    }

    private fun toggleRow(
        title: String,
        subtitle: String?,
        value: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val state = booleanArrayOf(value)
        val pill = valuePill(if (value) "On" else "Off", value)
        val row = rowShell(title, subtitle, pill)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener {
            state[0] = !state[0]
            Haptics.touch(row)
            val next = valuePill(if (state[0]) "On" else "Off", state[0])
            // Replace the trailing pill in place; rebuilding the row would lose the click target.
            (row.getChildAt(0) as LinearLayout).removeViewAt(1)
            (row.getChildAt(0) as LinearLayout).addView(next)
            onChange(state[0])
        }
        Haptics.attachTo(row)
        return row
    }

    private fun segmentedRow(
        title: String,
        options: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit,
    ): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(9), 0, 0)
        }
        // Segments size to their own labels inside a scroller rather than sharing the width equally.
        // Equal widths wrapped "Hardware only" onto three lines, which is a worse outcome than a
        // strip the user can flick.
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            // Same cue as the status chips: a segment dissolving at the edge means "there is more",
            // where a hard cut looks like the layout ran out of room.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(36))
            addView(strip)
        }
        val buttons = HashMap<String, TextView>()

        fun restyle(active: String) {
            for ((value, view) in buttons) {
                view.background = ContextCompat.getDrawable(
                    this@SettingsActivity,
                    if (value == active) R.drawable.bg_glass_button_active
                    else R.drawable.bg_glass_button,
                )
            }
        }

        for ((label, value) in options) {
            val button = TextView(this).apply {
                text = label
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Haptics.touch(this)
                    restyle(value)
                    onSelect(value)
                }
            }
            Haptics.attachTo(button)
            buttons[value] = button
            button.maxLines = 1
            strip.addView(
                button,
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    rightMargin = dp(6)
                },
            )
        }
        restyle(selected)

        return rowShell(title, null, null).apply { addView(scroller) }
    }

    private fun sliderRow(
        title: String,
        from: Float,
        to: Float,
        step: Float,
        value: Float,
        reading: (Float) -> String,
        onChange: (Float) -> Unit,
    ): View {
        val steps = (((to - from) / step).toInt()).coerceAtLeast(1)
        val label = valuePill(reading(value), false)

        val bar = SeekBar(this).apply {
            max = steps
            progress = ((value - from) / step).toInt().coerceIn(0, steps)
            setPadding(0, dp(6), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser && progress == 0) return
                    val current = from + progress * step
                    label.text = reading(current)
                    onChange(current)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    Haptics.tick(seekBar ?: return)
                }
            })
        }

        return rowShell(title, null, label).apply { addView(bar) }
    }

    private fun infoRow(title: String, value: String): View =
        rowShell(title, value, null)

    private fun actionRow(title: String, run: () -> Unit): View {
        val row = rowShell(title, null, null)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener {
            Haptics.touch(row)
            run()
        }
        Haptics.attachTo(row)
        return row
    }

    private fun valuePill(text: String, highlighted: Boolean): TextView = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(
            ContextCompat.getColor(
                this@SettingsActivity,
                if (highlighted) R.color.accent else R.color.text_secondary,
            ),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH_PARENT = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
