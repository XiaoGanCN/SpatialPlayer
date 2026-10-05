package com.gan.spatialplayer.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
) {
    enum class Source { MEDIA_STORE, FOLDER, POWERAMP, EXTERNAL }

    /** Extension used for container MIME guessing. */
    val extension: String?
        get() = displayName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
}

/**
 * Finds playable files.
 *
 * Deliberately not a media library: there is no database, no scanning of the whole device and no
 * indexing. It reads what the platform already indexed (MediaStore), what the user explicitly
 * granted (a picked folder), and what another app already organised (Poweramp).
 */
class MediaRepository(private val context: Context) {

    /** Video and audio containers the bundled decoders plus the platform can plausibly open. */
    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "mpg", "mpeg",
        "flv", "wmv", "ogv", "3gp", "divx", "vob", "rmvb", "asf", "m2v", "mp2v", "mxf",
    )

    private val audioExtensions = setOf(
        "mka", "m4a", "flac", "opus", "ogg", "mp3", "aac", "ac3", "eac3", "dts", "thd",
        "wav", "alac", "ape", "wv", "mlp", "truehd",
    )

    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "ttml", "dfxp", "smi")

    fun isSubtitle(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in subtitleExtensions

    fun isPlayable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions || ext in audioExtensions
    }

    /** Items the platform has already indexed. */
    suspend fun fromMediaStore(): List<FileEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileEntry>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.MIME_TYPE,
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                collect(cursor, out, FileEntry.Source.MEDIA_STORE)
            }
        }
        out
    }

    private fun collect(cursor: Cursor, out: MutableList<FileEntry>, source: FileEntry.Source) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameColumn = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
        val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val mimeColumn = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
            if (name.isNullOrBlank()) continue
            val uri = ContentUris.withAppendedId(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                id,
            )
            out += FileEntry(
                uri = uri,
                displayName = name,
                sizeBytes = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L,
                durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L,
                mimeType = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null,
                source = source,
            )
        }
    }

    /**
     * Walks a tree the user granted. Only the top level plus one nested level is scanned, so a
     * mistakenly-granted root folder cannot turn into a multi-minute crawl.
     */
    suspend fun fromTree(treeUri: Uri, maxDepth: Int = 2): List<FileEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileEntry>()
        val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return@withContext out
        walk(root, out, 0, maxDepth)
        out.sortedBy { it.displayName.lowercase() }
    }

    private fun walk(dir: DocumentFile, out: MutableList<FileEntry>, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                walk(child, out, depth + 1, maxDepth)
            } else {
                val name = child.name ?: continue
                if (!isPlayable(name)) continue
                val mime = context.contentResolver.getType(child.uri)
                    ?: mimeForName(name)
                out += FileEntry(
                    uri = child.uri,
                    displayName = name,
                    sizeBytes = child.length(),
                    durationMs = 0L,
                    mimeType = mime,
                    source = FileEntry.Source.FOLDER,
                )
            }
        }
    }

    /** Subtitle sidecars that sit next to [video], matched on the base filename. */
    suspend fun findSidecarSubtitles(video: FileEntry): List<Uri> = withContext(Dispatchers.IO) {
        val base = video.displayName.substringBeforeLast('.', video.displayName).lowercase()
        val out = ArrayList<Uri>()
        // Search the same directory the video lives in, when that is expressible.
        val parent = runCatching {
            DocumentFile.fromSingleUri(context, video.uri)?.parentFile
        }.getOrNull() ?: return@withContext out
        val siblings = runCatching { parent.listFiles() }.getOrNull() ?: return@withContext out
        for (sibling in siblings) {
            val name = sibling.name ?: continue
            if (!isSubtitle(name)) continue
            val siblingBase = name.substringBeforeLast('.', name).lowercase()
            if (siblingBase == base || siblingBase.startsWith("$base.") || base.startsWith("$siblingBase.")) {
                out += sibling.uri
            }
        }
        out
    }

    /** Reads a display name and size for an arbitrary picked URI. */
    suspend fun describe(uri: Uri): FileEntry? = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val resolvedName = name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Unknown"
        FileEntry(
            uri = uri,
            displayName = resolvedName,
            sizeBytes = size,
            durationMs = 0L,
            mimeType = context.contentResolver.getType(uri) ?: mimeForName(resolvedName),
            source = FileEntry.Source.EXTERNAL,
        )
    }

    private fun mimeForName(name: String): String? =
        PlayerEngine.mimeForExtension(name.substringAfterLast('.', ""))

    companion object {
        /** Audio extensions worth offering to the file picker alongside video. */
        val PICKER_MIME_TYPES = arrayOf(
            "video/*",
            "audio/*",
            "application/octet-stream",
        )
    }
}
