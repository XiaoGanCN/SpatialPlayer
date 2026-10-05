package com.gan.spatialplayer.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads tracks out of Poweramp's library so this player can act as its video/audio front-end.
 *
 * Poweramp exposes a REST-style read-only provider at `com.maxmpz.audioplayer.data` rather than a
 * documented SDK, and its schema is quirky in ways that are easy to get wrong:
 *
 *  * `content://…/files` only answers when given a `limit` parameter; without one it returns
 *    nothing.
 *  * `_id` is ambiguous across the provider's joins, so a projection must qualify it as
 *    `folder_files._id`. Projecting a bare `_id` is an SQL error.
 *  * Column names are suffixed (`title_tag`, `album_tag`, `artist_tag`) and `duration` must also be
 *    qualified. There is no `_data` column; the file name lives in `name`, and `path` is the
 *    *folder* path, not the file's.
 *
 * Everything is treated as optional: if Poweramp is missing, or present but has not granted this
 * app access, the caller gets an empty list plus an explanation rather than an exception.
 */
class PowerampReader(private val context: Context) {

    data class Result(
        val entries: List<FileEntry>,
        val status: String,
        val installed: Boolean,
    )

    private val authority = "com.maxmpz.audioplayer.data"

    /**
     * Columns that are known to exist on the `files` view.
     *
     * `_id`, `name` and `duration` all collide with the joined `folders` table, so they must be
     * qualified as `folder_files.*` or the provider raises "ambiguous column name". `title_tag` and
     * `folder_id` happen to be unambiguous, and the folder path is `folders.path`.
     */
    private val projection = arrayOf(
        "folder_files._id",
        "folder_files.name",
        "title_tag",
        "folder_files.duration",
        "folder_id",
        "folders.path",
    )

    /** Poweramp's `files` endpoint refuses queries without a limit. */
    private val pageLimit = 500

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(POWERAMP_PACKAGE, 0)
        true
    }.getOrDefault(false)

    suspend fun readLibrary(): Result = withContext(Dispatchers.IO) {
        if (!isInstalled()) {
            return@withContext Result(emptyList(), "Poweramp is not installed.", false)
        }

        val entries = ArrayList<FileEntry>()
        val attempt = runCatching { queryFiles(entries) }

        if (attempt.isSuccess) {
            return@withContext Result(
                entries = entries.sortedBy { it.displayName.lowercase() },
                status = if (entries.isEmpty()) {
                    "Poweramp returned no entries."
                } else {
                    "${entries.size} entries from Poweramp."
                },
                installed = true,
            )
        }

        Result(
            entries = emptyList(),
            status = buildString {
                append("Poweramp is installed but its library could not be read.")
                attempt.exceptionOrNull()?.message?.let { append(" (").append(it).append(")") }
            },
            installed = true,
        )
    }

    private fun queryFiles(out: MutableList<FileEntry>) {
        val base = Uri.parse("content://$authority/files")
        val uri = base.buildUpon().appendQueryParameter("limit", pageLimit.toString()).build()

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            // The provider reports these under their bare names even when projected qualified.
            val idIndex = cursor.getColumnIndex("_id")
            val nameIndex = cursor.getColumnIndex("name")
            val titleIndex = cursor.getColumnIndex("title_tag")
            val durationIndex = cursor.getColumnIndex("duration")
            val folderPathIndex = cursor.getColumnIndex("path")

            while (cursor.moveToNext()) {
                val name = when {
                    nameIndex >= 0 -> cursor.getString(nameIndex)
                    titleIndex >= 0 -> cursor.getString(titleIndex)
                    else -> null
                } ?: continue

                val id = if (idIndex >= 0) cursor.getLong(idIndex) else -1L

                // The individual entry is addressable, which is what makes it playable.
                val entryUri: Uri = if (id >= 0) {
                    ContentUris.withAppendedId(base, id)
                } else {
                    continue
                }

                out += FileEntry(
                    uri = entryUri,
                    displayName = name,
                    sizeBytes = 0L,
                    durationMs = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) {
                        cursor.getLong(durationIndex)
                    } else {
                        0L
                    },
                    // The provider does not report a MIME type; derive it from the file name so
                    // Media3 can pick the right extractor.
                    mimeType = mimeForName(name),
                    source = FileEntry.Source.POWERAMP,
                    subtitleHint = if (folderPathIndex >= 0) cursor.getString(folderPathIndex) else null,
                )
            }
        }
    }

    /** Best-effort MIME for a Poweramp entry, from its extension. */
    private fun mimeForName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4a-latm"
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "alac" -> "audio/alac"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/x-ape"
            "dsf", "dff" -> "audio/dsd"
            // Video containers, so Poweramp's video entries work too.
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            else -> null
        }
    }

    companion object {
        const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"

        /** Broadcast Poweramp listens for to grant a third-party app read access. */
        const val PERMISSION_REQUEST_ACTION = "com.maxmpz.audioplayer.API_PERMISSION"
    }
}
