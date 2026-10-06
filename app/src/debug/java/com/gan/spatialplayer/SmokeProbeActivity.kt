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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            Log.w(SmokeProbeActivity.TAG, "tracks :: no path supplied")
            finish()
            return
        }

        val engine = PlayerEngine(this, object : PlayerEngine.Listener {
            override fun onEnginePlaybackState(state: Int) = Unit
            override fun onEngineIsPlaying(playing: Boolean) = Unit
            override fun onEngineTracksChanged(tracks: androidx.media3.common.Tracks) = Unit
            override fun onEngineVideoSize(size: androidx.media3.common.VideoSize) = Unit
            override fun onEngineError(message: String, cause: Throwable?) {
                Log.e(SmokeProbeActivity.TAG, "tracks :: player error: $message")
            }
            override fun onEngineFirstFrame() = Unit
            override fun onEnginePlaybackParameters(speed: Float) = Unit
        })
        this.engine = engine

        // Optionally exercise a different decoder policy, to test whether audio track selection
        // depends on which renderer claims the stream.
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
        engine.setMedia(android.net.Uri.parse("file://$path"))
        engine.prepare()

        // Give the extractor time to publish its track list before reporting.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            val wantsText = intent.getBooleanExtra(EXTRA_SELECT_TEXT, false)
            if (wantsText) selectFirstTextTrack(engine)

            // Switch to a specific audio track, mirroring what the audio panel does. Used to prove
            // multi-stream selection actually takes effect.
            val audioIndex = intent.getIntExtra(EXTRA_SELECT_AUDIO, -1)
            if (audioIndex >= 0) selectAudioTrack(engine, audioIndex)
            // Track selection is applied on the playback thread, so `currentTracks` does not
            // reflect an override until that thread has run. Report after it has settled.
            handler.postDelayed({
                report(engine)
                engine.release()
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
     * Mirrors what the subtitle panel does when the user picks a track: build a
     * `TrackSelectionOverride` for the matching track group. Exercising it here proves the
     * subtitle selector works without needing to drive the UI by hand.
     */
    private fun selectFirstTextTrack(engine: PlayerEngine) {
        val player = engine.player ?: return
        val group = player.currentTracks.groups
            .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        if (group == null) {
            Log.w(SmokeProbeActivity.TAG, "select_text :: no text group available")
            return
        }
        val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
        Log.i(SmokeProbeActivity.TAG, "select_text :: override applied to text group")
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

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "track_probe_path"
        const val EXTRA_SELECT_TEXT = "track_probe_select_text"
        const val EXTRA_SELECT_AUDIO = "track_probe_select_audio"
        const val EXTRA_PROFILE = "track_probe_profile"
        private const val TRACK_SETTLE_MS = 3000L

        /** Time allowed for the playback thread to pick up a track-selection override. */
        private const val SELECTION_SETTLE_MS = 5000L
    }
}
