package com.gan.spatialplayer.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.HashMap

/**
 * A playable track, decoupled from Media3's own structures.
 *
 * ## Identity
 *
 * Three things look like an identity here and only one is safe:
 *
 *  * **`position`** — the index in the flattened list. Convenient, but it shifts whenever the track
 *    set changes, so it must never be the thing a UI holds on to.
 *  * **`indexOf(track)`** — what the panels used to do. `MediaTrack` is a data class, so equality is
 *    structural: two tracks that differ only in a field the UI does not show (or that genuinely
 *    share every field) compare equal, and lookup silently returns the *first* match. On a disc
 *    with 51 `eng` subtitle tracks that differ only by title, every row resolved to row one.
 *  * **`id`** — `"${group.id}#$trackIndex"`. Derived from what Media3 actually addresses a track by,
 *    so it is stable across rebuilds of the flattened list and unique per track. This is what
 *    choices carry.
 *
 * [group] and [trackIndex] remain the real addressing for a [TrackSelectionOverride].
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
    /** Index in the flattened list; for display only, never for identity. */
    val position: Int,
) {
    /** Stable, unique key. Safe to round-trip through UI state. */
    val id: String get() = "${group.id}#$trackIndex"

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

    /**
     * Resolves a UI choice back to a track.
     *
     * Prefers the stable [MediaTrack.id]; falls back to a positional match only for ids that
     * predate the keyed scheme, so an in-flight UI state cannot crash.
     */
    fun resolve(key: String): MediaTrack? {
        all.firstOrNull { it.id == key }?.let { return it }
        val position = key.substringAfterLast('#', "").toIntOrNull() ?: return null
        return all.firstOrNull { it.position == position }
    }

    companion object {
        const val TYPE_VIDEO = "video"
        const val TYPE_AUDIO = "audio"
        const val TYPE_TEXT = "text"
        const val TYPE_OTHER = "other"

        val EMPTY = MediaTracks(emptyList())

        /** Flattens Media3's [Tracks] into a list addressable for selection. */
        fun from(tracks: Tracks): MediaTracks {
            val out = ArrayList<MediaTrack>()

            // How many tracks of each type the file has, so an untitled track can still be numbered
            // against the whole set. Numbering within a group is not enough: a remux with fifty
            // subtitle tracks and no titles or languages puts each one in its own group of one, and
            // every row then read "Track 1 [application/x-subrip]" - fifty identical rows, with no
            // way to tell which was the thirty-seventh.
            val totals = HashMap<Int, Int>()
            for (group in tracks.groups) totals[group.type] = (totals[group.type] ?: 0) + group.mediaTrackGroup.length
            val seen = HashMap<Int, Int>()

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
                    val ordinal = (seen[group.type] ?: 0) + 1
                    seen[group.type] = ordinal
                    out += MediaTrack(
                        type = typeLabel,
                        label = labelFor(format, ordinal, totals[group.type] ?: trackCount),
                        detail = detailFor(format),
                        selected = group.isTrackSelected(i),
                        supported = group.isTrackSupported(i),
                        language = format.language?.takeIf { it.isNotBlank() && it != "und" },
                        group = mediaGroup,
                        trackIndex = i,
                        position = out.size,
                    )
                }
            }
            return MediaTracks(out)
        }

        /**
         * Human label for a track.
         *
         * The container's own title is the only thing that distinguishes the many tracks sharing a
         * language on a disc release ("American", "American / SDH", "British", "British / SDH"), so
         * it leads, and the language and codec are appended in brackets rather than replacing it.
         * Without this every `eng` subtitle read as an identical "eng" row.
         */
        private fun labelFor(format: Format, ordinal: Int, typeTotal: Int): String {
            val title = format.label?.takeIf { it.isNotBlank() }
            val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            val codec = format.codecs?.takeIf { it.isNotBlank() }

            val qualifiers = ArrayList<String>()
            language?.let { qualifiers += it }
            codec?.let { qualifiers += it }

            val head = title
                ?: if (typeTotal > 1) "Track $ordinal of $typeTotal" else "Track $ordinal"

            return if (qualifiers.isEmpty()) head else "$head [${qualifiers.joinToString(" · ")}]"
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
