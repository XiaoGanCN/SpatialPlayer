package com.gan.spatialplayer.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import java.util.Locale

/**
 * Turns the live engine state into the two monospace readouts.
 *
 * Kept apart from the UI so the formatting rules - which is exactly the part that has to stay
 * legible and honest - can be read and adjusted without touching view code.
 */
object PlaybackReport {

    /** The "is it actually working" panel. */
    fun inspection(
        sample: PlayerSample,
        tracks: List<TrackLine>,
        decoderProfile: DecoderProfile,
        spatial: DeviceCapabilities.SpatialSnapshot,
        hdr: DeviceCapabilities.HdrSnapshot,
        ffmpegVersion: String,
        outputDevice: String?,
        videoSize: VideoSize?,
    ): String = buildString {
        section("PLAYBACK")
        row("state", stateLabel(sample.state))
        row("position", timecode(sample.positionMs))
        row("duration", if (sample.durationMs > 0) timecode(sample.durationMs) else "unknown")
        row("buffered", timecode(sample.bufferedMs))
        val bufferedAhead = (sample.bufferedMs - sample.positionMs).coerceAtLeast(0L)
        row("buffer ahead", "${bufferedAhead / 1000}s")
        row("speed", String.format(Locale.US, "%.2fx", sample.playbackSpeed))

        section("VIDEO")
        row("mime", sample.videoMime)
        row("route", sample.videoCodecName)
        row(
            "resolution",
            if (sample.videoWidth > 0) "${sample.videoWidth}x${sample.videoHeight}" else "—",
        )
        row(
            "frame rate",
            if (sample.videoFrameRate > 0f) String.format(Locale.US, "%.3f fps", sample.videoFrameRate) else "—",
        )
        row("rendered", "${sample.renderedFrames} frames")
        row("dropped", "${sample.droppedFrames} frames")
        videoSize?.let { row("output size", "${it.width}x${it.height}") }

        section("AUDIO")
        row("mime", sample.audioMime)
        row("route", sample.audioCodecName)
        row(
            "channels",
            if (sample.audioChannelCount > 0) {
                "${sample.audioChannelCount} (${channelLayout(sample.audioChannelCount)})"
            } else {
                "—"
            },
        )
        row("sample rate", if (sample.audioSampleRate > 0) "${sample.audioSampleRate} Hz" else "—")
        row("underruns", "${sample.audioUnderruns}")
        row("output", outputDevice ?: "—")

        section("SPATIAL")
        row("spatializer", if (spatial.available) "available" else "unavailable")
        row("enabled", spatial.enabled.toString())
        row("immersive level", spatial.immersiveLevelLabel)
        row("output spatializes", spatial.outputCanSpatialize.toString())
        row("head tracker", spatial.headTrackerAvailable.toString())
        if (!spatial.enabled || !spatial.headTrackerAvailable) {
            note("Head tracking is switched on by the system, in Settings > Sound & vibration.")
        }

        section("DISPLAY")
        row("hdr types", hdr.supportedHdrTypes.joinToString(", ").ifEmpty { "none" })
        row("hdr conversion", hdr.conversionModeLabel)
        row("preferred output", hdr.preferredHdrOutputType)
        row("peak luminance", String.format(Locale.US, "%.0f nits", hdr.maxLuminance))
        row("avg luminance", String.format(Locale.US, "%.0f nits", hdr.maxAverageLuminance))
        row(
            "refresh",
            String.format(Locale.US, "%.2f Hz (max %.2f)", hdr.currentRefreshRate, hdr.peakRefreshRate),
        )

        section("DECODER")
        row("policy", decoderProfile.label)
        row("policy detail", decoderProfile.detail)
        row("ffmpeg", ffmpegVersion)
        row("decoder inits", "${sample.decoderInitCount}")

        if (tracks.isNotEmpty()) {
            section("TRACKS")
            for (track in tracks) {
                val marker = if (track.selected) "*" else " "
                val support = if (track.supported) "" else "  [unsupported]"
                append("$marker ")
                append(track.type.padEnd(6))
                append(track.label)
                append(support)
                if (track.detail.isNotEmpty()) {
                    append("\n    ")
                    append(track.detail)
                }
                append('\n')
            }
        }

        sample.playerError?.let {
            section("ERROR")
            append(it).append('\n')
        }
    }

    /** The "what is this file" panel. */
    fun metadata(
        displayName: String,
        sizeBytes: Long,
        sample: PlayerSample,
        tracks: List<TrackLine>,
        containerMime: String?,
    ): String = buildString {
        section("FILE")
        row("name", displayName)
        row("size", if (sizeBytes > 0) humanBytes(sizeBytes) else "unknown")
        row("container", containerMime ?: "—")
        row(
            "duration",
            if (sample.durationMs > 0) "${timecode(sample.durationMs)} (${sample.durationMs} ms)" else "unknown",
        )

        val video = tracks.firstOrNull { it.type == "video" }
        val audio = tracks.firstOrNull { it.type == "audio" }

        section("VIDEO STREAM")
        if (video != null) {
            row("codec", video.detail.ifEmpty { video.label })
        } else {
            row("codec", "none")
        }

        section("AUDIO STREAM")
        if (audio != null) {
            row("codec", audio.detail.ifEmpty { audio.label })
        } else {
            row("codec", "none")
        }

        val textTracks = tracks.filter { it.type == "text" }
        section("SUBTITLES")
        if (textTracks.isEmpty()) {
            row("tracks", "none")
        } else {
            for (track in textTracks) {
                row(track.label, if (track.selected) "selected" else "available")
            }
        }

        section("ALL TRACKS")
        if (tracks.isEmpty()) {
            append("no tracks reported yet\n")
        } else {
            for ((index, track) in tracks.withIndex()) {
                append("[")
                append((index + 1).toString().padStart(2, '0'))
                append("] ")
                append(track.type.padEnd(6))
                append(track.label)
                append('\n')
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Flat description of one track, for both panels. */
    data class TrackLine(
        val type: String,
        val label: String,
        val detail: String,
        val selected: Boolean,
        val supported: Boolean,
    )

    /** Flattens Media3's [Tracks] into the lines both panels consume. */
    fun flatten(tracks: Tracks): List<TrackLine> {
        val out = ArrayList<TrackLine>()
        for (group in tracks.groups) {
            val trackGroup = group.mediaTrackGroup
            val typeLabel = when (group.type) {
                C.TRACK_TYPE_VIDEO -> "video"
                C.TRACK_TYPE_AUDIO -> "audio"
                C.TRACK_TYPE_TEXT -> "text"
                else -> "other"
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val supported = group.isTrackSupported(i)
                out += TrackLine(
                    type = typeLabel,
                    label = describeLabel(format, i),
                    detail = describeDetail(format),
                    selected = group.isTrackSelected(i),
                    supported = supported,
                )
            }
        }
        return out
    }

    private fun describeLabel(format: Format, index: Int): String {
        val parts = ArrayList<String>()
        format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { parts += "[$it]" }
        format.codecs?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (parts.isEmpty()) parts += "track ${index + 1}"
        return parts.joinToString(" ")
    }

    private fun describeDetail(format: Format): String {
        val parts = ArrayList<String>()
        format.sampleMimeType?.let { parts += it }
        format.codecs?.takeIf { it.isNotBlank() }?.let { if (parts.none { p -> p.contains(it) }) parts += it }
        if (format.width > 0 && format.height > 0) parts += "${format.width}x${format.height}"
        if (format.frameRate > 0f) parts += String.format(Locale.US, "%.3f fps", format.frameRate)
        if (format.channelCount > 0) {
            parts += "${format.channelCount}ch ${channelLayout(format.channelCount)}"
        }
        if (format.sampleRate > 0) parts += "${format.sampleRate} Hz"
        if (format.bitrate > 0) parts += "${format.bitrate / 1000} kbps"
        format.colorInfo?.let { color ->
            parts += colorTransferLabel(color.colorTransfer)
            if (color.colorSpace == C.COLOR_SPACE_BT2020) parts += "bt2020"
            if (color.hdrStaticInfo != null) parts += "static-hdr-metadata"
        }
        if (format.rotationDegrees != 0) parts += "rot ${format.rotationDegrees}"
        return parts.joinToString(" · ")
    }

    fun colorTransferLabel(transfer: Int): String = when (transfer) {
        C.COLOR_TRANSFER_LINEAR -> "linear"
        C.COLOR_TRANSFER_SDR -> "sdr"
        C.COLOR_TRANSFER_SRGB -> "srgb"
        C.COLOR_TRANSFER_GAMMA_2_2 -> "gamma2.2"
        C.COLOR_TRANSFER_ST2084 -> "PQ (HDR10)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "transfer-$transfer"
    }

    /** Whether a format carries an HDR transfer function, for the HDR badge. */
    fun hdrLabel(format: Format?): String? {
        val color = format?.colorInfo ?: return null
        return when (color.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    fun channelLayout(count: Int): String = when (count) {
        1 -> "mono"
        2 -> "stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${count}.0"
    }

    private fun stateLabel(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "state-$state"
    }

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append('\n')
        append("── ")
        append(title)
        append(" ──")
        append('\n')
    }

    private fun StringBuilder.row(key: String, value: String) {
        append(key.padEnd(20))
        append(value)
        append('\n')
    }

    private fun StringBuilder.note(text: String) {
        append("  · ").append(text).append('\n')
    }

    fun timecode(ms: Long): String = com.gan.spatialplayer.ui.TextSpans.timecode(ms)

    fun humanBytes(size: Long): String = com.gan.spatialplayer.ui.TextSpans.bytes(size)

    /** MIME type for a container, for the metadata panel. */
    fun containerFor(name: String, reported: String?): String? {
        reported?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { return it }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts", "m2ts", "mts" -> MimeTypes.VIDEO_MP2T
            "avi" -> MimeTypes.VIDEO_AVI
            "mov" -> "video/quicktime"
            "mpg", "mpeg" -> MimeTypes.VIDEO_MPEG
            "flac" -> MimeTypes.AUDIO_FLAC
            "mka" -> MimeTypes.AUDIO_MATROSKA
            "ac3" -> MimeTypes.AUDIO_AC3
            else -> null
        }
    }
}
