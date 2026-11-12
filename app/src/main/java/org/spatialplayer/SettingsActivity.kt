package org.spatialplayer

import kotlin.math.roundToInt
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
import org.spatialplayer.media.DecoderPolicy
import org.spatialplayer.media.DecoderProfile
import org.spatialplayer.media.DeviceCapabilities
import org.spatialplayer.media.FfmpegCodecs
import org.spatialplayer.media.PlaybackEngine
import org.spatialplayer.media.UpmixMatrix
import org.spatialplayer.media.UpmixMode
import org.spatialplayer.ui.GlassInstaller
import org.spatialplayer.ui.Haptics
import org.spatialplayer.ui.SliderReading

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

    private var probe: android.content.BroadcastReceiver? = null
    internal var activeSheet: View? = null
        private set
    private lateinit var settings: SettingsStore
    private val upmixHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val applyMatrix = Runnable { applyUpmixNow() }

    override fun onDestroy() {
        probe?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onStop() {
        upmixHandler.removeCallbacks(applyMatrix)
        if (::settings.isInitialized) applyUpmixNow()
        super.onStop()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = SettingsStore(this)
        probe = DebugProbes.registerSettings(this)

        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; tag = "settings_scroll" }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll.addView(column)

        val bar = header()
        val fade = org.spatialplayer.ui.TopFadeBlurView(this).apply { sourceView = scroll }
        val root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.bg_deep))
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fade, FrameLayout.LayoutParams(MATCH_PARENT, dp(84)))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        scroll.clipToPadding = false
        scroll.setPadding(0,dp(62),0,0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(bars.left, 0, bars.right, 0)
            bar.setPadding(dp(8), bars.top + dp(10), dp(16), dp(10))
            scroll.setPadding(0, bars.top + dp(62), 0, bars.bottom)
            fade.layoutParams = (fade.layoutParams as FrameLayout.LayoutParams).apply { height = bars.top + dp(84) }
            insets
        }

        buildPlayback(column)
        buildDecoding(column)
        buildGestures(column)
        buildAppearance(column)
        column.addView(infoRow("Playback diagnostics", "Device, decoder and output details").apply {
            setOnClickListener {
                val dialog = android.app.Dialog(this@SettingsActivity, R.style.Theme_SpatialPlayer_Glass)
                val panel = org.spatialplayer.ui.GlassSheetLayout(this@SettingsActivity, "Playback diagnostics", { dialog.dismiss() })
                buildDevice(panel.content)
                buildDiagnostics(panel.content)
                dialog.setContentView(panel)
                dialog.show()
                org.spatialplayer.ui.GlassTheme.configureSheet(dialog, (resources.displayMetrics.heightPixels * .75f).toInt())
                GlassInstaller.apply(panel)
            }
        })

        column.addView(infoRow("Glass playground", "Try Backdrop alongside our current controls").apply {
            setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, GlassPlaygroundActivity::class.java)) }
        })
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
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
                tag = R.id.glass_role_button
                setImageResource(R.drawable.ic_chevron_left)
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
                typeface = org.spatialplayer.ui.UiTypography.body(this@SettingsActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
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
                subtitle = "Head tracking with a compatible headset",
                value = settings.spatialEnabled,
            ) { settings.spatialEnabled = it },
        )

        column.addView(
            toggleRow(
                title = getString(R.string.settings_background_audio),
                subtitle = null,
                value = settings.backgroundAudio,
            ) { settings.backgroundAudio = it },
        )

        column.addView(
            toggleRow(
                title = getString(R.string.settings_background_video),
                subtitle = null,
                value = settings.backgroundVideo,
            ) { settings.backgroundVideo = it },
        )

        column.addView(infoRow("Stereo stage", "Presets and a six-channel matrix · stereo only").apply {
            setOnClickListener { showUpmixEditor() }
        })

        column.addView(
            sliderRow(
                title = getString(R.string.settings_jump_length),
                from = SettingsStore.MIN_JUMP_MS / 1000f,
                to = SettingsStore.MAX_JUMP_MS / 1000f,
                step = 0.5f,
                value = settings.doubleTapJumpMs / 1000f,
                reading = { getString(R.string.settings_seconds, it) },
                default = SettingsStore.DEFAULT_JUMP_MS / 1000f,
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
                default = SettingsStore.DEFAULT_SUBTITLE_TRACK_LIMIT.toFloat(),
            ) { settings.subtitleTrackLimit = it.toInt() },
        )
    }

    private fun showUpmixEditor() {
        val dialog = android.app.Dialog(this, R.style.Theme_SpatialPlayer_Glass)
        val panel = org.spatialplayer.ui.GlassSheetLayout(this, "Stereo stage", { dialog.dismiss() })
        val column = panel.content
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderEditor() {
            content.removeAllViews()
            if (settings.upmixAdvanced) buildAdvancedUpmix(content)
            else content.addView(segmentedRow("Sound field", listOf(
                "Surround" to UpmixMode.SURROUND.name, "Wide" to UpmixMode.WIDE.name,
                "Front" to UpmixMode.FRONT.name), settings.upmixMode, ::upmixHint) {
                settings.upmixMode = it; applyUpmixNow()
            })
            GlassInstaller.apply(content)
        }
        column.addView(segmentedRow("", listOf("Presets" to "simple", "Matrix" to "advanced"),
            if (settings.upmixAdvanced) "advanced" else "simple") {
            settings.upmixAdvanced = it == "advanced"
            applyUpmixNow()
            renderEditor()
        })
        column.addView(content)
        renderEditor()
        activeSheet = panel
        dialog.setOnDismissListener { activeSheet = null }
        dialog.setContentView(panel)
        dialog.show()
        org.spatialplayer.ui.GlassTheme.configureSheet(dialog, minOf(dp(680), (resources.displayMetrics.heightPixels * .82f).toInt()))
        GlassInstaller.apply(panel)
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
        var matrix = UpmixMatrix.decode(settings.upmixMatrix)
        var selected = 0
        val editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val channelButtons = ArrayList<TextView>()
        fun save() {
            settings.upmixMatrix = matrix.encode()
            upmixHandler.removeCallbacks(applyMatrix)
            upmixHandler.postDelayed(applyMatrix, 250L)
        }
        fun render() {
            editor.removeAllViews()
            channelButtons.forEachIndexed { index, view ->
                view.setTag(R.id.glass_role, if (index == selected) R.id.glass_role_button_active else R.id.glass_role_button)
                view.isSelected = index == selected
                GlassInstaller.apply(view)
            }
            val sources = listOf("Left" to (100 to 0), "Right" to (0 to 100),
                "Both" to (50 to 50), "Difference" to (100 to -100), "Mute" to (0 to 0))
            val currentGains = matrix.gains[selected]
            val source = sources.firstOrNull { it.second == currentGains }?.first.orEmpty()
            editor.addView(segmentedRow("Source", sources.map { it.first to it.first }, source) { label ->
                val gains = sources.first { it.first == label }.second
                matrix = matrix.withChannel(selected, gains.first, gains.second)
                save()
                render()
            })
            val gains = matrix.gains[selected]
            editor.addView(matrixRow(
                title = "Input mix",
                fromLeft = gains.first, fromRight = gains.second,
                defaults = UpmixMatrix.DEFAULT_GAINS[selected],
            ) { left, right -> matrix = matrix.withChannel(selected, left, right); save() })
            GlassInstaller.apply(editor)
        }

        val names = listOf("Front L", "Front R", "Centre", "Sub", "Rear L", "Rear R")
        for (group in listOf(0..2, 3..5)) {
            val row = LinearLayout(this)
            for (index in group) {
                val button = TextView(this).apply {
                    setTag(R.id.glass_role, if (index == selected) R.id.glass_role_button_active else R.id.glass_role_button)
                    text = names[index]
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    background = org.spatialplayer.ui.GlassTheme.control(this@SettingsActivity, index == selected)
                    setOnClickListener { selected = index; render() }
                }
                channelButtons += button
                row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            column.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        column.addView(editor)
        render()
        column.addView(toggleRow("Subwoofer low-pass", "Keep bass below 120 Hz", matrix.subwooferLowPass) {
            matrix = matrix.copy(subwooferLowPass = it); save()
        })
        column.addView(infoRow("Reset matrix", "Restore the original six-channel mapping").apply {
            setOnClickListener { matrix = UpmixMatrix.DEFAULT.copy(subwooferLowPass = matrix.subwooferLowPass); save(); render() }
        })
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
        defaults: Pair<Int, Int>,
        onChange: (Int, Int) -> Unit,
    ): View {
        var left = fromLeft
        var right = fromRight

        val leftReading = valuePill(percent(fromLeft), false)
        val rightReading = valuePill(percent(fromRight), false)

        fun bar(initial: Int, reading: TextView, onValue: (Int) -> Unit): SeekBar =
            org.spatialplayer.ui.GlassSlider(this).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                max = UpmixMatrix.MAX_GAIN - UpmixMatrix.MIN_GAIN
                progress = initial - UpmixMatrix.MIN_GAIN
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val current = progress + UpmixMatrix.MIN_GAIN
                        reading.text = percent(current)
                        onValue(current)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        // GlassSlider owns its press, step and release feedback.
                    }
                })
            }

        /**
         * The pill between a matrix bar's label and the bar: type a percentage into it, or
         * double-tap to put that channel back to the SURROUND preset's value for it - the
         * starting point this editor was reached from, so "reset" means something specific.
         */
        fun editable(bar: SeekBar, reading: TextView, default: Int, onValue: (Int) -> Unit) {
            SliderReading.attach(
                view = reading,
                current = { (bar.progress + UpmixMatrix.MIN_GAIN).toFloat() },
                range = UpmixMatrix.MIN_GAIN.toFloat()..UpmixMatrix.MAX_GAIN.toFloat(),
                step = 1f,
                format = { percent(it.toInt()) },
                default = default.toFloat(),
            ) { applied ->
                val snapped = applied.toInt().coerceIn(UpmixMatrix.MIN_GAIN, UpmixMatrix.MAX_GAIN)
                bar.progress = snapped - UpmixMatrix.MIN_GAIN
                reading.text = percent(snapped)
                onValue(snapped)
            }
        }

        val leftDefault = defaults.first
        val rightDefault = defaults.second
        val leftBar = bar(fromLeft, leftReading) { value ->
            left = value
            onChange(left, right)
        }
        val rightBar = bar(fromRight, rightReading) { value ->
            right = value
            onChange(left, right)
        }
        editable(leftBar, leftReading, leftDefault) { value ->
            left = value
            onChange(left, right)
        }
        editable(rightBar, rightReading, rightDefault) { value ->
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
            row.addView(pill, LinearLayout.LayoutParams(dp(68), WRAP_CONTENT).apply { marginStart = dp(8) })
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
                default = SettingsStore.DEFAULT_VERTICAL_GAIN,
                inputScale = 100f,
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
        text = getString(res)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.03f
        setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
        setPadding(dp(6), dp(22), 0, dp(8))
    }

    private fun rowShell(title: String, subtitle: String?, trailing: View?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_glass_button)
            tag = "settings_row:$title"
            setTag(R.id.glass_role, R.id.glass_role_panel)
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
                typeface = org.spatialplayer.ui.UiTypography.body(this@SettingsActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        trailing?.let { titleRow.addView(it) }
        if (title.isNotBlank() || trailing != null) row.addView(titleRow)

        subtitle?.let {
            row.addView(
                TextView(this).apply {
                    text = it
                    typeface = org.spatialplayer.ui.UiTypography.body(this@SettingsActivity)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
                    setPadding(0, dp(5), 0, 0)
                },
            )
        }
        return row
    }

    private fun toggleRow(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = org.spatialplayer.ui.GlassSwitch(this).apply { isChecked = value }
        val row = rowShell(title, subtitle, toggle)
        row.setOnClickListener { toggle.toggle() }
        toggle.setOnCheckedChangeListener { _, checked -> onChange(checked) }
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
            overScrollMode = View.OVER_SCROLL_NEVER
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
                typeface = org.spatialplayer.ui.UiTypography.body(this@SettingsActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_tertiary))
                setPadding(0, dp(9), 0, 0)
            }
        }

        fun restyle(active: String) {
            for ((value, view) in buttons) {
                view.isSelected = value == active
                view.setTextColor(if (value == active) 0xFFFFFFFF.toInt() else 0xC7FFFFFF.toInt())
                view.tag = if (value == active) R.id.glass_role_button_active else R.id.glass_role_button
                GlassInstaller.apply(view)
            }
        }

        for ((label, value) in options) {
            val button = TextView(this).apply {
                setBackgroundResource(R.drawable.bg_glass_button)
                text = label
                typeface = org.spatialplayer.ui.UiTypography.body(this@SettingsActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    restyle(value)
                    hintView?.text = hint?.invoke(value)
                    onSelect(value)
                }
            }
            Haptics.attachTo(button)
            buttons[value] = button
            button.maxLines = 1
            button.minHeight = dp(44)
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
        default: Float? = null,
        inputScale: Float = 1f,
        onChange: (Float) -> Unit,
    ): View {
        val steps = (((to - from) / step).roundToInt()).coerceAtLeast(1)
        val label = valuePill(reading(value), false)

        val bar = org.spatialplayer.ui.GlassSlider(this).apply {
                progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            max = steps
            progress = ((value - from) / step).roundToInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val current = from + progress * step
                    label.text = reading(current)
                    onChange(current)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    // GlassSlider owns its press, step and release feedback.
                }
            })
        }

        // The pill is the value as well as its label: tap it to type an exact number, double-tap
        // to put the setting back where it shipped.
        SliderReading.attach(
            view = label,
            current = { (from + bar.progress * step) * inputScale },
            range = (from * inputScale)..(to * inputScale),
            step = step * inputScale,
            format = { reading(it / inputScale) },
            default = default?.times(inputScale),
        ) { applied ->
            val snapped = (from + ((applied / inputScale - from) / step).roundToInt().coerceIn(0, steps) * step)
            bar.progress = ((snapped - from) / step).roundToInt().coerceIn(0, steps)
            label.text = reading(snapped)
            onChange(snapped)
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
