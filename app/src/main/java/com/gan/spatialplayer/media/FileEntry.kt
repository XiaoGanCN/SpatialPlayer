package com.gan.spatialplayer.media

import android.net.Uri

/**
 * One playable item, from MediaStore, from a folder the user granted, or from Poweramp.
 */
data class FileEntry(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val mimeType: String?,
    val source: Source,
    val subtitleHint: String? = null,
    /** Optional tags, used to describe a track in the list. */
    val artist: String? = null,
    val album: String? = null,
) {
    enum class Source { MEDIA_STORE, FOLDER, POWERAMP, EXTERNAL }

    /** Extension used for container MIME guessing. */
    val extension: String?
        get() = displayName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
}
