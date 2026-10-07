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
import com.gan.spatialplayer.media.PlaybackEngine
import com.gan.spatialplayer.media.UpmixMatrix
import com.gan.spatialplayer.ui.GlassInstaller
import com.gan.spatialplayer.media.UpmixMode
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
        GlassInstaller.apply(root)
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
            toggleRow(
                title = getString(R.string.settings_background_audio),
                subtitle = getString(R.string.settings_background_audio_hint),
                value = settings.backgroundAudio,
            ) { settings.backgroundAudio = it },
        )

        column.addView(
            toggleRow(
                title = getString(R.string.settings_background_video),
                subtitle = getString(R.string.settings_background_video_hint),
                value = settings.backgroundVideo,
            ) { settings.backgroundVideo = it },
        )

        // Simple or advanced. Switching rebuilds the page rather than showing and hiding rows in
        // place: the two editors share nothing, and a page that is rebuilt cannot end up with a row
        // left over from the other one.
        column.addView(
            segmentedRow(
                title = getString(R.string.settings_upmix_mode),
                options = listOf(
                    getString(R.string.settings_upmix_simple) to "simple",
                    getString(R.string.settings_upmix_advanced) to "advanced",
                ),
                selected = if (settings.upmixAdvanced) "advanced" else "simple",
            ) {
                settings.upmixAdvanced = it == "advanced"
                applyUpmixNow()
                recreate()
            },
        )

        if (settings.upmixAdvanced) {
            buildAdvancedUpmix(column)
        } else {
            // The options and their explanation are one card: the explanation says what the selected
            // option *does*, so splitting it into a separate row below made it read as a caption for
            // the section rather than for the choice - and it went stale the moment a different
            // option was tapped, because only the segments were rebuilt.
            column.addView(
                segmentedRow(
                    title = getString(R.string.settings_upmix),
                    options = listOf(
                        getString(R.string.upmix_surround) to UpmixMode.SURROUND.name,
                        getString(R.string.upmix_wide) to UpmixMode.WIDE.name,
                        getString(R.string.upmix_front) to UpmixMode.FRONT.name,
                    ),
                    selected = settings.upmixMode,
                    hint = ::upmixHint,
                ) {
                    settings.upmixMode = it
                    applyUpmixNow()
                },
            )
        }

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

    /** What the selected preset actually does; shown inside the same card as the options. */
    private fun upmixHint(mode: String): CharSequence = when (mode) {
        UpmixMode.WIDE.name -> getString(R.string.upmix_wide_hint)
        UpmixMode.FRONT.name -> getString(R.string.upmix_front_hint)
        else -> getString(R.string.upmix_surround_hint)
    }

    /**
     * The manual matrix: one card per output channel, with a slider for each input.
     *
     * Two sliders rather than a list of named sources, because the interesting mappings are not
     * "left or right" - they are "mostly left with a little of the right, inverted". A source list
     * cannot express that, and the point of the advanced editor is the freedom the presets do not
     * have.
     */
    private fun buildAdvancedUpmix(column: LinearLayout) {
        val labels = listOf(
            R.string.upmix_channel_fl,
            R.string.upmix_channel_fr,
            R.string.upmix_channel_c,
            R.string.upmix_channel_lfe,
            R.string.upmix_channel_bl,
            R.string.upmix_channel_br,
        )

        var matrix = UpmixMatrix.decode(settings.upmixMatrix)

        for ((index, labelRes) in labels.withIndex()) {
            val gains = matrix.gains[index]
            column.addView(
                matrixRow(
                    title = "${getString(labelRes)}  ·  ${UpmixMatrix.CHANNEL_IDS[index]}",
                    fromLeft = gains.first,
                    fromRight = gains.second,
                ) { left, right ->
                    matrix = matrix.withChannel(index, left, right)
                    settings.upmixMatrix = matrix.encode()
                    applyUpmixNow()
                },
            )
        }

        column.addView(
            toggleRow(
                title = getString(R.string.settings_sub_lowpass),
                subtitle = getString(R.string.settings_sub_lowpass_hint),
                value = matrix.subwooferLowPass,
            ) {
                matrix = matrix.copy(subwooferLowPass = it)
                settings.upmixMatrix = matrix.encode()
                applyUpmixNow()
            },
        )

        column.addView(
            infoRow(getString(R.string.settings_upmix), getString(R.string.upmix_advanced_hint)),
        )
    }

    /**
     * One output channel's two contributions, as percentages from -100 to +100.
     *
     * Negative is polarity. It is offered rather than hidden because a surround pair carrying the same
     * signal in phase reads as one wide source behind the listener instead of as a space - which is
     * why the presets fill their two rears with opposite signs.
     */
    private fun matrixRow(
        title: String,
        fromLeft: Int,
        fromRight: Int,
        onChange: (Int, Int) -> Unit,
    ): View {
        var left = fromLeft
        var right = fromRight

        val leftReading = valuePill(percent(fromLeft), false)
        val rightReading = valuePill(percent(fromRight), false)

        fun bar(initial: Int, reading: TextView, onValue: (Int) -> Unit): SeekBar =
            SeekBar(this).apply {
                max = UpmixMatrix.MAX_GAIN - UpmixMatrix.MIN_GAIN
                progress = initial - UpmixMatrix.MIN_GAIN
                setPadding(0, dp(4), 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val current = progress + UpmixMatrix.MIN_GAIN
                        reading.text = percent(current)
                        onValue(current)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        Haptics.tick(seekBar ?: return)
                    }
                })
            }

        val leftBar = bar(fromLeft, leftReading) { value ->
            left = value
            onChange(left, right)
        }
        val rightBar = bar(fromRight, rightReading) { value ->
            right = value
            onChange(left, right)
        }

        fun labelled(text: String, view: View, pill: View): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply {
                    this.text = text
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                },
                LinearLayout.LayoutParams(dp(28), WRAP_CONTENT),
            )
            row.addView(view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(pill, LinearLayout.LayoutParams(dp(52), WRAP_CONTENT))
            return row
        }

        return rowShell(title, null, null).apply {
            addView(labelled("L", leftBar, leftReading))
            addView(labelled("R", rightBar, rightReading))
        }
    }

    private fun percent(value: Int): String = if (value > 0) "+$value%" else "$value%"

    /** Pushes the mapping at the running player, so a change is audible before leaving this screen. */
    private fun applyUpmixNow() {
        PlaybackEngine.peek()?.reloadUpmixSettings()
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
        hint: ((String) -> CharSequence)? = null,
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

        // Lives in the same card as the options and is rewritten in place, so the explanation always
        // describes the option that is actually selected.
        val hintView = hint?.let { provider ->
            TextView(this).apply {
                text = provider(selected)
                typeface = Typeface.SANS_SERIF
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
                setPadding(0, dp(9), 0, 0)
            }
        }

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
                    hintView?.text = hint?.invoke(value)
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

        return rowShell(title, null, null).apply {
            addView(scroller)
            hintView?.let { addView(it) }
        }
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
