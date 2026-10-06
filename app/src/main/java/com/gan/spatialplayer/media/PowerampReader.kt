package com.gan.spatialplayer.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads tracks out of Poweramp's library so this player can act as its front-end.
 *
 * Poweramp exposes a REST-style read-only provider at `com.maxmpz.audioplayer.data` rather than a
 * documented SDK, and three separate things about it are easy to get wrong. All three were found
 * by measurement:
 *
 *  1. **Schema.** `content://…/files` only answers when given a `limit` parameter. `_id`, `name`
 *     and `duration` collide with the joined `folders` table and must be qualified as
 *     `folder_files.*`. The title column is `title_tag`, and there is no `_data` column - the file
 *     name is `name`, while `path` is the *folder* path.
 *  2. **Visibility.** Android 11+ hides undeclared packages, so `getPackageInfo` reported Poweramp
 *     as absent until it was declared in a `<queries>` block.
 *  3. **Playability.** Poweramp's per-file URI cannot be opened - `openInputStream` fails on it,
 *     which surfaced as a "source error" on playback. Nor is a guessed `file://` path readable,
 *     because Android 11+ scoped storage refuses it through FUSE even with READ_MEDIA_AUDIO
 *     granted (measured: `File.exists()` returned false for a path the shell could read).
 *     MediaStore indexes the same files and returns content URIs that do open, so paths are
 *     resolved through it.
 *
 * Everything is optional: if Poweramp is missing, or present but not readable, the caller gets an
 * empty list plus an explanation instead of an exception.
 */
class PowerampReader(private val context: Context) {

    data class Result(
        val entries: List<FileEntry>,
        val status: String,
        val installed: Boolean,
    )

    /** Tracks Poweramp lists that could not be resolved to a playable URI in the last read. */
    var lastUnresolvedCount: Int = 0
        private set

    private val authority = "com.maxmpz.audioplayer.data"

    /**
     * Columns known to exist on the `files` view.
     *
     * `_id`, `name` and `duration` are qualified because they collide with the joined `folders`
     * table; `title_tag` and `folder_id` are unambiguous, and the folder path is `folders.path`.
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
    private val pageLimit = 1000

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

        if (attempt.isFailure) {
            return@withContext Result(
                entries = emptyList(),
                status = buildString {
                    append("Poweramp is installed but its library could not be read.")
                    attempt.exceptionOrNull()?.message?.let { append(" (").append(it).append(")") }
                },
                installed = true,
            )
        }

        val status = buildString {
            append("${entries.size} tracks from Poweramp.")
            if (lastUnresolvedCount > 0) {
                append(" $lastUnresolvedCount could not be matched to a playable file.")
            }
        }
        Result(entries.sortedBy { it.displayName.lowercase() }, status, true)
    }

    private fun queryFiles(out: MutableList<FileEntry>) {
        val base = Uri.parse("content://$authority/files")
        val uri = base.buildUpon().appendQueryParameter("limit", pageLimit.toString()).build()

        // One pass over MediaStore beats one query per track, and supplies the playable URIs.
        val mediaStore = mediaStorePathIndex()
        var unresolved = 0

        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
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
                }
                if (name.isNullOrBlank()) continue

                val folderPath =
                    if (folderPathIndex >= 0) cursor.getString(folderPathIndex) else null
                val playable = resolvePlayableUri(folderPath, name, mediaStore)

                val playableUri: Uri
                if (playable != null) {
                    playableUri = playable
                } else {
                    // Listed but not playable: keep it visible so the gap is obvious rather than
                    // silently dropping the track.
                    unresolved++
                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else -1L
                    if (id < 0) continue
                    playableUri = ContentUris.withAppendedId(base, id)
                }

                val durationMs = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) {
                    cursor.getLong(durationIndex)
                } else {
                    0L
                }

                out += FileEntry(
                    uri = playableUri,
                    displayName = name,
                    sizeBytes = 0L,
                    durationMs = durationMs,
                    // The provider reports no MIME type, so derive it from the name.
                    mimeType = mimeForName(name),
                    source = FileEntry.Source.POWERAMP,
                    subtitleHint = folderPath,
                )
            }
        }

        lastUnresolvedCount = unresolved
    }

    /**
     * Maps Poweramp's `path` + `name` onto a URI MediaStore can serve.
     *
     * Poweramp reports `path` as a storage-relative folder such as `primary/Music/Hi-Res/`, which
     * corresponds to `/storage/emulated/0/Music/Hi-Res/`.
     */
    private fun resolvePlayableUri(
        storageRelativeFolder: String?,
        fileName: String,
        mediaStoreByPath: Map<String, Uri>,
    ): Uri? {
        if (storageRelativeFolder.isNullOrBlank()) return null

        val volume = storageRelativeFolder.substringBefore('/')
        val remainder = storageRelativeFolder.substringAfter('/', "").trim('/')
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        val absolutePath =
            if (remainder.isEmpty()) "$root/$fileName" else "$root/$remainder/$fileName"
        return mediaStoreByPath[absolutePath]
    }

    /** Every audio file MediaStore knows about, keyed by absolute path. */
    private fun mediaStorePathIndex(): Map<String, Uri> {
        val index = HashMap<String, Uri>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
        runCatching {
            context.contentResolver.query(collection, columns, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val dataIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (idIndex < 0 || dataIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIndex) ?: continue
                    index[path] = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                }
            }
        }
        return index
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
