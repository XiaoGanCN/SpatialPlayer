package com.gan.spatialplayer.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads tracks out of Poweramp's library so this player can act as its video front-end.
 *
 * Poweramp exposes a read-only `ContentProvider` rather than a documented SDK, and its grant flow
 * is a broadcast the app must be installed to answer. Every step is therefore treated as optional:
 * if Poweramp is absent, or present but has not granted this app access, the caller gets an empty
 * list plus an explanation instead of an exception. This is a convenience door, not a dependency.
 */
class PowerampReader(private val context: Context) {

    data class Result(
        val entries: List<FileEntry>,
        val status: String,
        val installed: Boolean,
    )

    private val authority = "com.maxmpz.audioplayer.data"

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(POWERAMP_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Queries the `files` table. Poweramp's schema puts the real path in `_data` and a stable
     * identifier in `_id`, which together give a content URI this app can play via its own
     * provider grant or via the raw file path.
     */
    suspend fun readLibrary(): Result = withContext(Dispatchers.IO) {
        if (!isInstalled()) {
            return@withContext Result(emptyList(), "Poweramp is not installed.", false)
        }

        val entries = ArrayList<FileEntry>()
        var lastError: String? = null

        for (uri in candidateUris()) {
            val attempt = runCatching { query(uri, entries) }
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
            lastError = attempt.exceptionOrNull()?.message
        }

        Result(
            entries = emptyList(),
            status = buildString {
                append("Poweramp is installed but did not grant access.")
                lastError?.let { append(" (").append(it).append(")") }
            },
            installed = true,
        )
    }

    private fun candidateUris(): List<Uri> = listOf(
        Uri.parse("content://$authority/files"),
        Uri.parse("content://$authority/files/"),
        Uri.parse("content://com.maxmpz.audioplayer/files"),
    )

    private fun query(uri: Uri, out: MutableList<FileEntry>) {
        val projection = arrayOf(
            "_id",
            "_data",
            "title",
            "album",
            "artist",
            "duration",
            "folder",
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex("_id")
            val dataIndex = cursor.getColumnIndex("_data")
            val titleIndex = cursor.getColumnIndex("title")
            val folderIndex = cursor.getColumnIndex("folder")
            val durationIndex = cursor.getColumnIndex("duration")

            while (cursor.moveToNext()) {
                val path = if (dataIndex >= 0) cursor.getString(dataIndex) else null
                val title = if (titleIndex >= 0) cursor.getString(titleIndex) else null
                val folder = if (folderIndex >= 0) cursor.getString(folderIndex) else null

                val name = title
                    ?: path?.substringAfterLast('/')
                    ?: continue

                // Prefer a playable content URI; fall back to the raw path.
                val playUri: Uri = if (idIndex >= 0) {
                    ContentUris.withAppendedId(uri, cursor.getLong(idIndex))
                } else if (path != null) {
                    Uri.parse("file://$path")
                } else {
                    continue
                }

                out += FileEntry(
                    uri = playUri,
                    displayName = name,
                    sizeBytes = 0L,
                    durationMs = if (durationIndex >= 0 && !cursor.isNull(durationIndex)) {
                        cursor.getLong(durationIndex)
                    } else {
                        0L
                    },
                    mimeType = context.contentResolver.getType(playUri),
                    source = FileEntry.Source.POWERAMP,
                    subtitleHint = folder,
                )
            }
        }
    }

    companion object {
        const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"

        /** Broadcast Poweramp listens for to grant a third-party app read access. */
        const val PERMISSION_REQUEST_ACTION = "com.maxmpz.audioplayer.API_PERMISSION"
    }
}
