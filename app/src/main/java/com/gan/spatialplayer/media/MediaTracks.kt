package com.gan.spatialplayer.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * A playable track, decoupled from Media3's own structures.
 *
 * [group] and [trackIndex] are the identity: Media3 needs a [TrackSelectionOverride] built from a
 * specific [TrackGroup] plus the index *within that group*, so a flat list index is not enough to
 * address a track. (Using a flat index was a real bug: it sent every selection to the first group.)
 */
data class MediaTrack(
    val type: String,
    val label: String,
    val detail: String,
    val selected: Boolean,
    val supported: Boolean,
    val language: String?,
    val group: TrackGroup,
    val trackIndex: Int,
) {
    /** Media3 override that selects exactly this track. */
    fun toOverride(): TrackSelectionOverride = TrackSelectionOverride(group, trackIndex)
}

/**
 * Immutable snapshot of what a media item contains, and how to change what is playing.
 *
 * Built once per [Tracks] change and handed to the UI, so the panels never have to reason about
 * Media3's group/index structure themselves.
 */
data class MediaTracks(
    val all: List<MediaTrack>,
) {
    val audio: List<MediaTrack> get() = all.filter { it.type == TYPE_AUDIO }
    val video: List<MediaTrack> get() = all.filter { it.type == TYPE_VIDEO }
    val text: List<MediaTrack> get() = all.filter { it.type == TYPE_TEXT }

    val selectedAudio: MediaTrack? get() = audio.firstOrNull { it.selected }
    val selectedText: MediaTrack? get() = text.firstOrNull { it.selected }

    /** How many of each kind, for the summary chips. */
    val counts: Map<String, Int>
        get() = all.groupingBy { it.type }.eachCount()

    fun byId(id: Int): MediaTrack? = all.getOrNull(id)

    companion object {
        const val TYPE_VIDEO = "video"
        const val TYPE_AUDIO = "audio"
        const val TYPE_TEXT = "text"
        const val TYPE_OTHER = "other"

        val EMPTY = MediaTracks(emptyList())

        /** Flattens Media3's [Tracks] into a list addressable for selection. */
        fun from(tracks: Tracks): MediaTracks {
            val out = ArrayList<MediaTrack>()
            for (group in tracks.groups) {
                val typeLabel = when (group.type) {
                    C.TRACK_TYPE_VIDEO -> TYPE_VIDEO
                    C.TRACK_TYPE_AUDIO -> TYPE_AUDIO
                    C.TRACK_TYPE_TEXT -> TYPE_TEXT
                    else -> TYPE_OTHER
                }
                val mediaGroup = group.mediaTrackGroup

                // Some containers (notably adaptive ones) expose several formats per group. Use the
                // group's own length rather than assuming one.
                val trackCount = mediaGroup.length
                for (i in 0 until trackCount) {
                    val format = group.getTrackFormat(i)
                    out += MediaTrack(
                        type = typeLabel,
                        label = labelFor(format, i, mediaGroup.length),
                        detail = detailFor(format),
                        selected = group.isTrackSelected(i),
                        supported = group.isTrackSupported(i),
                        language = format.language?.takeIf { it.isNotBlank() && it != "und" },
                        group = mediaGroup,
                        trackIndex = i,
                    )
                }
            }
            return MediaTracks(out)
        }

        private fun labelFor(format: Format, index: Int, groupSize: Int): String {
            val parts = ArrayList<String>()
            format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
            format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { parts += "[$it]" }

            val codec = format.codecs?.takeIf { it.isNotBlank() }
            if (codec != null && parts.isEmpty()) parts += codec

            if (parts.isEmpty()) {
                parts += if (groupSize > 1) "Track ${index + 1} of $groupSize" else "Track ${index + 1}"
            }
            return parts.joinToString(" ")
        }

        private fun detailFor(format: Format): String {
            val parts = ArrayList<String>()
            format.sampleMimeType?.let { parts += it }
            if (format.width > 0 && format.height > 0) parts += "${format.width}x${format.height}"
            if (format.frameRate > 0f) parts += String.format(java.util.Locale.US, "%.3f fps", format.frameRate)
            if (format.channelCount > 0) {
                parts += "${format.channelCount}ch ${PlaybackReport.channelLayout(format.channelCount)}"
            }
            if (format.sampleRate > 0) parts += "${format.sampleRate} Hz"
            if (format.bitrate > 0) parts += "${format.bitrate / 1000} kbps"
            format.colorInfo?.let { color ->
                parts += PlaybackReport.colorTransferLabel(color.colorTransfer)
                if (color.colorSpace == C.COLOR_SPACE_BT2020) parts += "bt2020"
                if (color.hdrStaticInfo != null) parts += "hdr-metadata"
            }
            format.codecs?.takeIf { it.isNotBlank() }?.let { if (parts.none { p -> p.contains(it) }) parts += it }
            return parts.joinToString(" · ")
        }

        /** Image or subtitle sidecar MIME, used to pick a parser for an external file. */
        fun subtitleMimeFor(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "srt", "sub" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
        }
    }
}
