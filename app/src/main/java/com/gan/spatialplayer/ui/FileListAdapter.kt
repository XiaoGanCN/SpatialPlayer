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
 * Rows stay deliberately sparse - a name and one monospace metadata line, which is where the
 * duration, size and source live. No thumbnails: decoding them would mean holding a second video
 * pipeline open just to browse.
 */
class FileListAdapter(
    private val onClick: (FileEntry) -> Unit,
) : RecyclerView.Adapter<FileListAdapter.Holder>() {

    private val items = ArrayList<FileEntry>()

    fun submit(list: List<FileEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.rowIcon)
        private val title: TextView = itemView.findViewById(R.id.rowTitle)
        private val meta: TextView = itemView.findViewById(R.id.rowMeta)

        fun bind(entry: FileEntry, onClick: (FileEntry) -> Unit) {
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
                entry.extension in setOf("flac", "opus", "mp3", "m4a", "ac3", "eac3", "dts", "thd", "mka")
            icon.setImageResource(if (isAudio) R.drawable.ic_audio else R.drawable.ic_aspect)

            itemView.setOnClickListener { onClick(entry) }
        }
    }
}
