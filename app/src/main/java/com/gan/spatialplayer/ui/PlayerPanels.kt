package com.gan.spatialplayer.ui

import android.content.Context
import android.util.Log
import com.gan.spatialplayer.R
import com.gan.spatialplayer.SettingsStore
import com.gan.spatialplayer.media.DecoderProfile
import com.gan.spatialplayer.media.DeviceCapabilities
import com.gan.spatialplayer.media.MediaTracks
import com.gan.spatialplayer.media.PlayerEngine
import com.gan.spatialplayer.media.UpmixMode
import com.gan.spatialplayer.ui.PlayerChoiceValues.SUBTITLE_OFF
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_AUDIO_HEADER
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_AUDIO_PREFIX
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_TEXT_HEADER
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_TEXT_PREFIX
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_TEXT_SHOW_ALL
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_UPMIX_ADVANCED
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_UPMIX_HEADER
import com.gan.spatialplayer.ui.PlayerChoiceValues.VALUE_UPMIX_PREFIX
import java.util.Locale

/** Builds inspector rows from current playback state whenever a panel is shown. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerPanels(
    private val context: Context,
    private val inspectorSheet: InspectorSheet,
    private val settings: SettingsStore,
) {
    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun populateSpeedPanel(current: Float) {
        val choices = SPEED_OPTIONS.map { speed ->
            InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SPEED,
                value = speed.toString(),
                title = String.format(Locale.US, "%.2f×", speed),
                subtitle = speedHint(speed),
                selected = kotlin.math.abs(speed - current) < 0.01f,
                enabled = true,
                section = if (speed == SPEED_OPTIONS.first()) "Playback speed" else null,
            )
        }
        inspectorSheet.setChoices(InspectorSheet.Panel.SPEED, choices)
    }

    fun populateDecoderPanel(engine: PlayerEngine) {
        val sheet = inspectorSheet
        val videoMime = engine.player?.videoFormat?.sampleMimeType
        val audioMime = engine.player?.audioFormat?.sampleMimeType

        val choices = DecoderProfile.entries.map { profile ->
            InspectorSheet.Choice(
                panel = InspectorSheet.Panel.DECODER,
                value = profile.name,
                title = profile.label,
                subtitle = buildString {
                    append(profile.detail)
                    append("\nvideo: ")
                    append(
                        com.gan.spatialplayer.media.DecoderPolicy.describeRoute(videoMime, profile),
                    )
                    append("   audio: ")
                    append(
                        com.gan.spatialplayer.media.DecoderPolicy.describeRoute(audioMime, profile),
                    )
                },
                selected = profile == engine.decoderProfile,
                enabled = true,
                section = if (profile == DecoderProfile.entries.first()) "Policy" else null,
            )
        }
        sheet.setChoices(InspectorSheet.Panel.DECODER, choices)
    }

    fun populateAudioPanel(engine: PlayerEngine, mediaTracks: MediaTracks) {
        val sheet = inspectorSheet
        val spatial = DeviceCapabilities.spatialSnapshot(context)
        val choices = ArrayList<InspectorSheet.Choice>()

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.AUDIO,
            value = "spatial_on",
            title = getString(R.string.spatial_audio),
            subtitle = buildString {
                append("spatializer ")
                append(if (spatial.available) "available" else "unavailable")
                append(" · enabled ").append(spatial.enabled)
                append(" · level ").append(spatial.immersiveLevelLabel)
                append("\nhead tracker: ").append(spatial.headTrackerAvailable)
                append(" · output spatializes: ").append(spatial.outputCanSpatialize)
                append("\noutput: ").append(spatial.outputDeviceName ?: "—")
                if (!spatial.headTrackerAvailable || !spatial.enabled) {
                    append("\n").append(getString(R.string.head_tracking_how))
                }
            },
            selected = engine.spatialAudioEnabled,
            enabled = true,
            section = "Spatial",
        )

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.AUDIO,
            value = "spatial_off",
            title = "Ask the system not to spatialise",
            // Deliberately worded as a request: the platform has the final say, and on the
            // reference device it keeps spatialising even when asked not to.
            subtitle = "The platform and connected headset make the final decision",
            selected = !engine.spatialAudioEnabled,
            enabled = true,
        )

        // The upmix, where the user already is. Having to leave the player, find the library, open
        // Settings and come back made a control that changes how the current song sounds into one
        // nobody would reach mid-playback.
        val currentUpmix = when {
            engine.upmixAdvanced -> "advanced"
            engine.upmixMode == UpmixMode.WIDE -> getString(R.string.upmix_wide)
            engine.upmixMode == UpmixMode.FRONT -> getString(R.string.upmix_front)
            else -> getString(R.string.upmix_surround)
        }
        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.AUDIO,
            value = VALUE_UPMIX_HEADER,
            title = getString(R.string.settings_upmix),
            subtitle = "How stereo is spread across 5.1, for music. Now: $currentUpmix",
            selected = false,
            enabled = false,
            section = "Upmix",
        )
        if (!engine.hasVideo) {
            for (mode in listOf(UpmixMode.SURROUND, UpmixMode.WIDE, UpmixMode.FRONT)) {
                val label = when (mode) {
                    UpmixMode.WIDE -> getString(R.string.upmix_wide)
                    UpmixMode.FRONT -> getString(R.string.upmix_front)
                    else -> getString(R.string.upmix_surround)
                }
                choices += InspectorSheet.Choice(
                    panel = InspectorSheet.Panel.AUDIO,
                    value = "$VALUE_UPMIX_PREFIX${mode.name}",
                    title = label,
                    subtitle = null,
                    selected = !engine.upmixAdvanced && engine.upmixMode == mode,
                    enabled = true,
                )
            }
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.AUDIO,
                value = VALUE_UPMIX_ADVANCED,
                title = "Advanced matrix",
                subtitle = "Per-channel mapping; opens Settings",
                selected = engine.upmixAdvanced,
                enabled = true,
            )
        }

        // One row per audio stream. A file with several dubs has to be switchable mid-playback.
        val audio = mediaTracks.audio
        if (audio.size > 1) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.AUDIO,
                value = VALUE_AUDIO_HEADER,
                title = "${audio.size} audio tracks",
                subtitle = "Selecting one keeps playing and switches the stream",
                selected = false,
                enabled = false,
                section = "Audio track",
            )
        }
        for ((index, track) in audio.withIndex()) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.AUDIO,
                value = "$VALUE_AUDIO_PREFIX${track.id}",
                title = track.label,
                subtitle = track.detail,
                selected = track.selected,
                enabled = track.supported,
                section = if (audio.size == 1 && index == 0) "Audio track" else null,
            )
        }

        sheet.setChoices(InspectorSheet.Panel.AUDIO, choices)
    }

    fun populateSubtitlePanel(mediaTracks: MediaTracks, subtitleLimitOverridden: Boolean, subtitleSizeSp: Float, subtitlePositionFraction: Float) {
        val sheet = inspectorSheet
        val choices = ArrayList<InspectorSheet.Choice>()

        val textTracks = mediaTracks.text

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = SUBTITLE_OFF,
            title = getString(R.string.subtitle_off),
            subtitle = "Hide subtitles for this item",
            selected = textTracks.none { it.selected },
            enabled = true,
            section = "Tracks",
        )

        // A remux can carry fifty or more subtitle tracks and building a row for each one makes the
        // panel slow to open and impossible to scan. The cap keeps the common case instant; whatever
        // is selected is always listed, wherever it falls, and the rest are one tap away.
        val limit = if (subtitleLimitOverridden) Int.MAX_VALUE else settings.subtitleTrackLimit
        val selectedTracks = textTracks.filter { it.selected }
        val listed = if (textTracks.size <= limit) {
            textTracks
        } else {
            // Always keep whatever is selected, then fill up to the cap in file order. The selected
            // tracks are collected first and the list is filtered back into its original order
            // afterwards, so the panel still reads top-to-bottom like the container does rather than
            // putting the current track first - and it is exactly `limit` rows when there are enough,
            // which a "take(limit - selected)" formulation gets wrong whenever a selected track is
            // already inside the first block.
            val keep = LinkedHashSet(selectedTracks.map { it.id })
            for (track in textTracks) {
                if (keep.size >= limit) break
                keep += track.id
            }
            textTracks.filter { it.id in keep }
        }

        // Logged as well as shown: the panel cannot be introspected (the player never settles for
        // uiautomator), so this is how the cap is asserted on a file with more tracks than the cap.
        if (textTracks.size > listed.size) {
            Log.i(TAG, "subtitle list capped: showing ${listed.size} of ${textTracks.size} tracks")
        }

        for (track in listed) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SUBTITLES,
                value = "$VALUE_TEXT_PREFIX${track.id}",
                title = track.label,
                subtitle = track.detail,
                selected = track.selected,
                enabled = track.supported,
            )
        }

        if (listed.size < textTracks.size) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SUBTITLES,
                value = VALUE_TEXT_SHOW_ALL,
                title = getString(R.string.subtitle_tracks_show_all, textTracks.size),
                subtitle = getString(
                    R.string.subtitle_tracks_limited,
                    listed.size,
                    textTracks.size,
                ),
                selected = false,
                enabled = true,
            )
        }

        if (textTracks.isEmpty()) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SUBTITLES,
                value = VALUE_TEXT_HEADER,
                title = "No embedded subtitle tracks",
                subtitle = "Add a sidecar file, or drop one next to the video",
                selected = false,
                enabled = false,
            )
        }

        // Styling sliders. Fixed subtitle geometry cannot suit portrait, a landscape window and a
        // TV-style layout at once.
        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = subtitleSizeSp.toString(),
            title = getString(R.string.subtitle_size),
            range = SettingsStore.MIN_SUBTITLE_SIZE..SettingsStore.MAX_SUBTITLE_SIZE,
            stepSize = 1f,
            defaultValue = SettingsStore.DEFAULT_SUBTITLE_SIZE,
            section = "Styling",
        )
        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = (subtitlePositionFraction * 100f).toString(),
            title = getString(R.string.subtitle_bottom_padding),
            range = 0f..45f,
            stepSize = 1f,
            defaultValue = SettingsStore.DEFAULT_SUBTITLE_POSITION * 100f,
            section = null,
        )

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = "add_external",
            title = getString(R.string.subtitle_external),
            subtitle = "Sidecar files next to the video are picked up automatically",
            selected = false,
            enabled = true,
            section = "External",
        )

        sheet.setChoices(InspectorSheet.Panel.SUBTITLES, choices)
    }

    fun populateScalingPanel(scaleMode: Int, ambientEnabled: Boolean, doubleTapJumpMs: Long) {
        val sheet = inspectorSheet
        val choices = listOf(
            Triple(VideoRectCalculator.SCALE_FIT, R.string.scale_fit, "Fit the whole picture, bars as needed"),
            Triple(VideoRectCalculator.SCALE_FILL, R.string.scale_fill, "Fill the screen, no cropping"),
            Triple(VideoRectCalculator.SCALE_ZOOM, R.string.scale_zoom, "Fill the screen and crop the overflow"),
            Triple(VideoRectCalculator.SCALE_STRETCH, R.string.scale_stretch, "Ignore aspect ratio (distorts)"),
        ).mapIndexed { index, (mode, titleRes, detail) ->
            InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SCALING,
                value = "scale_$mode",
                title = getString(titleRes),
                subtitle = detail,
                selected = mode == scaleMode,
                enabled = true,
                section = if (index == 0) "Scaling" else null,
            )
        } + InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SCALING,
            value = if (ambientEnabled) "ambient_off" else "ambient_on",
            title = getString(R.string.ambient_glow),
            subtitle = if (ambientEnabled) "On" else "Off",
            selected = ambientEnabled,
            enabled = true,
            section = "Ambient",
        ) + InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SCALING,
            // Seconds, like the range: the setting itself is milliseconds, and feeding ms to a bar
            // whose range is seconds pinned it at the top and printed "10000" as its reading.
            value = (doubleTapJumpMs / 1000f).toString(),
            title = getString(R.string.double_tap_jump),
            subtitle = "Double tap the left half to go back, the right half to go forward",
            // A slider over the four lengths people actually ask for; 10s is the default.
            range = 1f..10f,
            stepSize = 0.5f,
            defaultValue = SettingsStore.DEFAULT_JUMP_MS / 1000f,
            section = "Gestures",
        )

        sheet.setChoices(InspectorSheet.Panel.SCALING, choices)
    }

    /** A word for the rates worth naming, so the list is not five bare numbers. */
    private fun speedHint(speed: Float): String? = when (speed) {
        0.5f -> "Half speed"
        1f -> "Normal"
        2f -> "Double speed"
        else -> null
    }

    private companion object {
        const val TAG = "PlayerActivity"
        val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
