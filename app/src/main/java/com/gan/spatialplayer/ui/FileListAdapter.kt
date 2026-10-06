package com.gan.spatialplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gan.spatialplayer.R
import com.gan.spatialplayer.media.FileEntry
import com.gan.spatialplayer.ui.TextSpans.mixed

/**
 * Flat list of playable files.
 *
 * Rows show a preview frame where one can be decoded, the file name, a monospace line of technical
 * facts (duration, size, source) and, when the source provides them, the artist and album.
 */
class FileListAdapter(
    private val onClick: (FileEntry) -> Unit,
    private val thumbnails: ThumbnailLoader? = null,
) : RecyclerView.Adapter<FileListAdapter.Holder>() {

    private val items = ArrayList<FileEntry>()

    fun submit(list: List<FileEntry>) {
        val previous = items.size
        items.clear()
        items.addAll(list)
        // The list is rebuilt wholesale on refresh, so a diff would cost more than it saves; but
        // notifyDataSetChanged on every refresh also throws away scroll position, so only do it when
        // the shape actually changed.
        if (previous != items.size) notifyDataSetChanged() else notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onClick, thumbnails)
    }

    override fun getItemCount(): Int = items.size

    /**
     * Releases queued thumbnail work once the list is detached.
     *
     * Without this the loader's pool and cache outlive the screen, and a fast back-and-forth would
     * leave several pools decoding at once.
     */
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        thumbnails?.shutdown()
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.rowIcon)
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        private val meta: TextView = itemView.findViewById(R.id.rowMeta)
        private val tags: TextView = itemView.findViewById(R.id.rowTags)

        fun bind(entry: FileEntry, onClick: (FileEntry) -> Unit, thumbnails: ThumbnailLoader?) {
            title.text = entry.displayName

            // Monospace for the technical half of the row: duration and size.
            val duration = if (entry.durationMs > 0) TextSpans.timecode(entry.durationMs) else "—"
            val size = if (entry.sizeBytes > 0) TextSpans.bytes(entry.sizeBytes) else "—"
            val source = when (entry.source) {
                FileEntry.Source.MEDIA_STORE -> "mediastore"
                FileEntry.Source.FOLDER -> "folder"
                FileEntry.Source.POWERAMP -> "poweramp"
                FileEntry.Source.EXTERNAL -> "file"
            }
            meta.text = mixed(
                "$duration  ·  $size  ·  $source",
                duration,
                size,
            )

            val isAudio = entry.mimeType?.startsWith("audio/") == true ||
                entry.extension in AUDIO_EXTENSIONS

            // Artist and album only when the source actually supplied them; an empty row of
            // separators would be worse than nothing.
            val tagLine = listOfNotNull(
                entry.artist?.takeIf { it.isNotBlank() },
                entry.album?.takeIf { it.isNotBlank() },
            )
            if (tagLine.isEmpty()) {
                tags.visibility = View.GONE
            } else {
                tags.visibility = View.VISIBLE
                tags.text = tagLine.joinToString("  ·  ")
            }

            val placeholder = if (isAudio) {
                ThumbnailLoader.PLACEHOLDER_AUDIO
            } else {
                ThumbnailLoader.PLACEHOLDER_VIDEO
            }

            if (thumbnails != null && !isAudio) {
                // Reset padding/scale because the placeholder path below sets them for the icon.
                icon.setPadding(0, 0, 0, 0)
                icon.scaleType = ImageView.ScaleType.CENTER_CROP
                thumbnails.load(entry.uri, entry.mimeType, icon, placeholder)
            } else {
                // An icon needs its own inset and must not be cropped.
                val inset = (7 * itemView.resources.displayMetrics.density).toInt()
                icon.setPadding(inset, inset, inset, inset)
                icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
                icon.tag = null
                icon.setImageResource(placeholder)
            }

            itemView.setOnClickListener { onClick(entry) }
        }

        private companion object {
            val AUDIO_EXTENSIONS = setOf(
                "flac", "opus", "mp3", "m4a", "ac3", "eac3", "dts", "thd", "mka",
            )
        }
    }
}
