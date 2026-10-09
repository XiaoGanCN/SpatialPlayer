package com.gan.spatialplayer

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MimeTypes
import com.gan.spatialplayer.media.DecoderPolicy
import com.gan.spatialplayer.media.DecoderProfile
import com.gan.spatialplayer.media.DeviceCapabilities
import com.gan.spatialplayer.media.FfmpegCodecs
import com.gan.spatialplayer.media.PlaybackReport
import com.gan.spatialplayer.media.PlayerEngine

/**
 * Debug-only self-inspection. **Not present in release builds.**
 *
 * Emits the player's own view of its decode capability to logcat under a stable tag, so
 * `tools/smoke-test.sh` can assert on it. This matters because the platform's own decoder list
 * (`dumpsys media.player`) cannot say anything about the bundled FFmpeg build - only the app can
 * report that, and it is exactly the part that makes TrueHD / AC3 / DTS work.
 */
class SmokeProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe()
        finish()
    }

    private fun probe() {
        val identity = DeviceCapabilities.identity()
        line("identity", "${identity.manufacturer} ${identity.model} | ${identity.soc} | Android ${identity.androidRelease} (API ${identity.sdkInt}) | ${identity.abis.joinToString(",")}")

        val hdr = DeviceCapabilities.hdrSnapshot(this)
        line(
            "display",
            "${hdr.widthPx}x${hdr.heightPx} @${hdr.densityDpi}dpi | hdr=[${hdr.supportedHdrTypes.joinToString(",")}] | " +
                "maxLum=${hdr.maxLuminance} avgLum=${hdr.maxAverageLuminance} minLum=${hdr.minLuminance} | " +
                "conversion=${hdr.conversionModeLabel} preferred=${hdr.preferredHdrOutputType} | " +
                "refresh=${hdr.currentRefreshRate}(max ${hdr.peakRefreshRate})",
        )

        val spatial = DeviceCapabilities.spatialSnapshot(this)
        line(
            "spatial",
            "available=${spatial.available} enabled=${spatial.enabled} level=${spatial.immersiveLevelLabel} " +
                "headTracker=${spatial.headTrackerAvailable} outputCanSpatialize=${spatial.outputCanSpatialize} " +
                "device=${spatial.outputDeviceName ?: "-"} type=${spatial.outputDeviceTypeLabel ?: "-"}",
        )

        line("ffmpeg", "version=${FfmpegCodecs.version()} available=${FfmpegCodecs.isAvailable(MimeTypes.AUDIO_AC3)}")

        // The heart of the decoder verification: for every codec this player exists to handle,
        // report how it would actually be decoded on this device, under each policy.
        val codecs = listOf(
            "AC3" to MimeTypes.AUDIO_AC3,
            "EAC3" to MimeTypes.AUDIO_E_AC3,
            "EAC3-JOC" to MimeTypes.AUDIO_E_AC3_JOC,
            "TrueHD" to MimeTypes.AUDIO_TRUEHD,
            "DTS" to MimeTypes.AUDIO_DTS,
            "DTS-HD" to MimeTypes.AUDIO_DTS_HD,
            "AAC" to MimeTypes.AUDIO_AAC,
            "FLAC" to MimeTypes.AUDIO_FLAC,
            "Opus" to MimeTypes.AUDIO_OPUS,
            "Vorbis" to MimeTypes.AUDIO_VORBIS,
            "MP3" to MimeTypes.AUDIO_MPEG,
            "H.264" to MimeTypes.VIDEO_H264,
            "HEVC" to MimeTypes.VIDEO_H265,
            "AV1" to MimeTypes.VIDEO_AV1,
            "VP9" to MimeTypes.VIDEO_VP9,
            "MPEG2" to MimeTypes.VIDEO_MPEG2,
        )

        for ((label, mime) in codecs) {
            val platform = if (DeviceCapabilities.hasHardwareDecoder(mime)) "hardware" else "none"
            val ffmpeg = if (FfmpegCodecs.isAvailable(mime)) {
                "ffmpeg/${FfmpegCodecs.decoderName(mime) ?: "?"}"
            } else {
                "none"
            }
            val auto = DecoderPolicy.describeRoute(mime, DecoderProfile.AUTO)
            val ffonly = DecoderPolicy.describeRoute(mime, DecoderProfile.FFMPEG_ONLY)
            val hwonly = DecoderPolicy.describeRoute(mime, DecoderProfile.HARDWARE_ONLY)
            line(
                "codec",
                "$label|$mime|platform=$platform|ffmpeg=$ffmpeg|auto=$auto|ffmpeg_only=$ffonly|hardware_only=$hwonly",
            )
        }

        // A count summary makes a single logcat grep enough to detect a regression.
        val losslessTargets = listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
        )
        val covered = losslessTargets.count { DecoderPolicy.canDecode(it, DecoderProfile.AUTO) }
        line("summary", "lossless_multichannel_covered=$covered/${losslessTargets.size} ffmpeg=${FfmpegCodecs.version()}")
    }

    private fun line(key: String, value: String) {
        Log.i(TAG, "$key :: $value")
    }

    companion object {
        const val TAG = "SpatialPlayerProbe"
    }
}

/**
 * Debug-only: plays one item and reports the tracks Media3 actually selected.
 *
 * This is what verifies subtitle handling, because "did a subtitle render" is not visible in
 * logcat - what is checkable is which text tracks exist and whether one is selected.
 */
class SmokeTrackProbeActivity : AppCompatActivity() {

    private var engine: PlayerEngine? = null

    /**
     * The decoder profile that was in force before this probe ran.
     *
     * `switchDecoderProfile` PERSISTS its choice to the app's settings, so a probe that leaves
     * `FFMPEG_ONLY` behind changes the app for every later run: that profile bypasses
     * `SpatialRenderersFactory` altogether, so no FFmpeg renderer is registered and nothing decodes.
     * Measured - it broke `verify-multichannel.sh` for two consecutive runs, and the failure looked
     * like a decoder regression rather than a leftover preference.
     */
    private var originalProfile: DecoderProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val directUriArg = intent.getStringExtra(EXTRA_URI)
        if (path.isNullOrBlank() && directUriArg.isNullOrBlank()) {
            Log.w(SmokeProbeActivity.TAG, "tracks :: no path or uri supplied")
            finish()
            return
        }

        val engine = PlayerEngine(this, object : PlayerEngine.Listener {
            override fun onEnginePlaybackState(state: Int) = Unit
            override fun onEngineIsPlaying(playing: Boolean) = Unit
            override fun onEngineTracksChanged(tracks: androidx.media3.common.Tracks) = Unit
            override fun onEngineVideoSize(size: androidx.media3.common.VideoSize) = Unit
            override fun onEngineError(message: String, cause: Throwable?) {
                Log.e(SmokeProbeActivity.TAG, "tracks :: player error: $message", cause)
            }
            override fun onEngineFirstFrame() = Unit
            override fun onEnginePlaybackParameters(speed: Float) = Unit
            override fun onEngineAudioDownmixed(from: Int, to: Int) = Unit
        })
        this.engine = engine

        // Optionally exercise a different decoder policy, to test whether audio track selection
        // depends on which renderer claims the stream. Applied before `build()`, and the previous
        // value is restored in `onDestroy`; setting it here rather than mid-probe also means there is
        // exactly one rebuild, so the selection made later is not racing a second one.
        originalProfile = engine.decoderProfile
        when (intent.getStringExtra(EXTRA_PROFILE)) {
            "ffmpeg" -> engine.switchDecoderProfile(
                com.gan.spatialplayer.media.DecoderProfile.FFMPEG_ONLY,
            )
            "ffmpeg_audio" -> engine.switchDecoderProfile(
                com.gan.spatialplayer.media.DecoderProfile.FFMPEG_AUDIO,
            )
            "hardware" -> engine.switchDecoderProfile(
                com.gan.spatialplayer.media.DecoderProfile.HARDWARE_ONLY,
            )
            else -> Unit
        }
        engine.build()
        val directUri = directUriArg
        if (directUri != null) {
            Log.i(SmokeProbeActivity.TAG, "source :: uri=$directUri")
            engine.setMedia(android.net.Uri.parse(directUri))
        } else {
            val file = java.io.File(path)
            Log.i(
                SmokeProbeActivity.TAG,
                "source :: exists=${file.exists()} readable=${file.canRead()} len=${file.length()}",
            )
            engine.setMedia(android.net.Uri.fromFile(file))
        }
        engine.prepare()

        // Give the extractor time to publish its track list before reporting.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            val textIndex = intent.getIntExtra(EXTRA_SELECT_TEXT, -1)
            val wantsText = textIndex >= 0
            if (wantsText) selectTextTrack(engine, textIndex)

            // Switch to a specific audio track, mirroring what the audio panel does. Used to prove
            // multi-stream selection actually takes effect.
            val audioIndex = intent.getIntExtra(EXTRA_SELECT_AUDIO, -1)
            if (audioIndex >= 0) selectAudioTrack(engine, audioIndex)
            // Track selection is applied on the playback thread, so `currentTracks` does not
            // reflect an override until that thread has run. Report after it has settled.
            handler.postDelayed({
                report(engine)
                // An explicit end marker, last line always. A suite that reads the log after a fixed
                // sleep races the rebuild it is measuring, and a half-written log looks exactly like
                // a failed restore - measured, that is what made this check flaky at 1 run in 3.
                Log.i(SmokeProbeActivity.TAG, "probe_done")
                // The engine is NOT released here. It has to outlive this callback so `onDestroy` can
                // put the decoder profile back - see `restoreProfile`. Releasing it here nulled the
                // engine, `onDestroy` found nothing to switch, and FFMPEG_ONLY leaked into the app's
                // settings, where it bypasses `SpatialRenderersFactory` and left every later run with
                // no FFmpeg renderer at all.
                finish()
            }, if (wantsText) SELECTION_SETTLE_MS else 0L)
        }, TRACK_SETTLE_MS)
    }

    /**
     * Selects the nth audio track via the same override path the audio panel uses, addressed by
     * (group, index) rather than a flat list position.
     */
    private fun selectAudioTrack(engine: PlayerEngine, index: Int) {
        val player = engine.player ?: return
        val tracks = com.gan.spatialplayer.media.MediaTracks.from(player.currentTracks)
        val track = tracks.audio.getOrNull(index)
        if (track == null) {
            Log.w(SmokeProbeActivity.TAG, "select_audio :: no audio track at index $index")
            return
        }
        Log.i(
            SmokeProbeActivity.TAG,
            "select_audio :: requesting index=$index label=${track.label} group=${track.group.id}",
        )
        val params = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(track.toOverride())
            .build()
        player.trackSelectionParameters = params

        Log.i(
            SmokeProbeActivity.TAG,
            "select_audio :: applied override group=${track.group.id} index=${track.trackIndex}",
        )
    }

    /**
     * Mirrors what the subtitle panel does when the user picks a track.
     *
     * Selects by *index into the flattened track list* and addresses it by the same stable key the
     * UI uses, so this reproduces the real path - including the case that used to break: many
     * tracks sharing a language, where only the container title separates them. Selecting index 2
     * of four `eng` subtitles must land on the third one, not the first.
     */
    private fun selectTextTrack(engine: PlayerEngine, index: Int) {
        val player = engine.player ?: return
        val tracks = com.gan.spatialplayer.media.MediaTracks.from(player.currentTracks)
        val track = tracks.text.getOrNull(index)
        if (track == null) {
            Log.w(SmokeProbeActivity.TAG, "select_text :: no text track at index $index")
            return
        }
        Log.i(
            SmokeProbeActivity.TAG,
            "select_text :: requesting index=$index id=${track.id} label=${track.label}",
        )
        // Resolve through the keyed model exactly as the panel does.
        val resolved = tracks.resolve(track.id) ?: track
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(resolved.toOverride())
            .build()

        // Then do the two other things the subtitle panel does, which is the whole point of this
        // option: record the language preference (a disc release's four `eng` tracks all share it)
        // and the exact identity, so a rebuilt player has to restore the *specific* track rather than
        // "some track in this language". Restoring by language alone lands on whichever `eng` track
        // comes first, which is the reported "highlight sticks to the first [en] row". The rebuild
        // itself is the `--es track_probe_profile` option: it is applied before the engine is built,
        // so this track list is already the post-rebuild one.
        if (intent.getBooleanExtra(EXTRA_APPLY_LANGUAGE, false)) {
            resolved.language?.let { engine.applyPreferredSubtitleLanguage(it) }
            engine.applyPreferredTextTrack(resolved, engine.currentMediaUri?.toString())
            Log.i(
                SmokeProbeActivity.TAG,
                "select_text :: language=${resolved.language} recorded; this engine was built on a " +
                    "different renderer stack, so its track list is the rebuild",
            )
            // No rebuild here: this option is used together with a decoder profile supplied on the
            // command line, so the engine is already built with the profile the test wants and the
            // track list arriving at `onTracksChanged` is the one that has to carry the selection
            // over. See the profile handling in `onCreate` for why the profile is not switched
            // midway.
        }
    }

    private fun report(engine: PlayerEngine) {
        val player = engine.player
        val tracks = player?.currentTracks
        if (tracks == null) {
            Log.w(SmokeProbeActivity.TAG, "tracks :: none")
            return
        }

        val lines = com.gan.spatialplayer.media.MediaTracks.from(tracks).all
        Log.i(SmokeProbeActivity.TAG, "tracks :: count=${lines.size}")

        // Group layout: Media3 addresses a track as (group id, index within that group), and for
        // some containers several streams share one group.
        tracks.groups.forEachIndexed { gi, g ->
            val members = (0 until g.length).joinToString(",") { i ->
                val f = g.getTrackFormat(i)
                "${i}:${f.sampleMimeType}${if (g.isTrackSelected(i)) "*" else ""}"
            }
            Log.i(
                SmokeProbeActivity.TAG,
                "group :: index=$gi id=${g.mediaTrackGroup.id} type=${g.type} len=${g.length} [$members]",
            )
        }

        for (line in lines) {
            Log.i(
                SmokeProbeActivity.TAG,
                "track :: type=${line.type}|selected=${line.selected}|supported=${line.supported}|" +
                    "label=${line.label}|detail=${line.detail}",
            )
        }

        val textTracks = lines.filter { it.type == "text" }
        val selectedText = textTracks.count { it.selected }
        val audioTracks = lines.filter { it.type == "audio" }
        val videoTracks = lines.filter { it.type == "video" }

        Log.i(
            SmokeProbeActivity.TAG,
            "track_summary :: video=${videoTracks.size} audio=${audioTracks.size} " +
                "text=${textTracks.size} text_selected=$selectedText",
        )

        // The multichannel formats must be selected as multichannel, not as a stereo downmix, or
        // the spatializer has nothing to place.
        val selectedAudio = audioTracks.firstOrNull { it.selected }
        if (selectedAudio != null) {
            Log.i(
                SmokeProbeActivity.TAG,
                "selected_audio :: ${selectedAudio.label} | ${selectedAudio.detail}",
            )
        }
    }

    /**
     * Puts the decoder profile back and releases the engine.
     *
     * The profile matters more than it looks: `switchDecoderProfile` persists its choice, and
     * `FFMPEG_ONLY` skips `SpatialRenderersFactory` entirely, so a probe that exits without restoring
     * leaves the app unable to register an FFmpeg renderer at all. That is not a subtle failure - it
     * broke the multichannel suite's decoder checks for every run after it.
     */
    private fun restoreProfile() {
        val current = engine ?: return
        val original = originalProfile
        if (original != null && current.decoderProfile != original) {
            current.switchDecoderProfile(original)
        }
        current.release()
        engine = null
    }

    override fun onDestroy() {
        restoreProfile()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "track_probe_path"
        const val EXTRA_SELECT_TEXT = "track_probe_select_text_index"
        const val EXTRA_SELECT_AUDIO = "track_probe_select_audio"
        const val EXTRA_PROFILE = "track_probe_profile"
        const val EXTRA_URI = "track_probe_uri"

        /**
         * Also record the language preference and the exact track, then rebuild - the app's path.
         *
         * Off by default so the existing suite's "select this specific track" check keeps testing
         * selection alone.
         */
        const val EXTRA_APPLY_LANGUAGE = "track_probe_apply_language"
        private const val TRACK_SETTLE_MS = 3000L

        /** Time allowed for the playback thread to pick up a track-selection override. */
        private const val SELECTION_SETTLE_MS = 8000L
    }
}
