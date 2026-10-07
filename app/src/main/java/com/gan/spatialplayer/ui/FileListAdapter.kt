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

            // Only what is actually known. A missing duration used to render as an em dash, which
            // put a bare "-" in the middle of the line between the duration and the source and read
            // as a typo rather than as "unknown" - and the sources that leave it empty are the ones
            // that also supply the artist, so it was the most visible line in the row.
            val duration = if (entry.durationMs > 0) TextSpans.timecode(entry.durationMs) else null
            val size = if (entry.sizeBytes > 0) TextSpans.bytes(entry.sizeBytes) else null
            val source = when (entry.source) {
                FileEntry.Source.MEDIA_STORE -> "mediastore"
                FileEntry.Source.FOLDER -> "folder"
                FileEntry.Source.POWERAMP -> "poweramp"
                FileEntry.Source.EXTERNAL -> "file"
            }
            val metaParts = listOfNotNull(duration, size, source)
            meta.text = mixed(
                metaParts.joinToString("  ·  "),
                *listOfNotNull(duration, size).toTypedArray(),
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

            if (thumbnails != null) {
                icon.setPadding(0, 0, 0, 0)
                // A decoded frame fills the 16:9 slot. Cover art is square, so its *bounds* are made
                // square too: drawing a square inside a 16:9 frame leaves two dead panels of frame
                // either side, which reads as a broken image rather than as a cover.
                //
                // The end margin makes up the difference, so the text still starts at the same x for
                // both kinds of row - a mixed list must not have its titles stepping in and out.
                val density = itemView.resources.displayMetrics.density
                val params = icon.layoutParams
                if (isAudio) {
                    params.width = (AUDIO_ART_DP * density).toInt()
                    params.height = (AUDIO_ART_DP * density).toInt()
                    (params as? ViewGroup.MarginLayoutParams)?.marginEnd =
                        ((VIDEO_SLOT_DP - AUDIO_ART_DP) * density).toInt()
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    params.width = (VIDEO_SLOT_DP * density).toInt()
                    params.height = (VIDEO_SLOT_HEIGHT_DP * density).toInt()
                    (params as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                icon.layoutParams = params
                thumbnails.load(entry.uri, entry.mimeType, isAudio, icon, placeholder)
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
            /**
             * Extensions used only when the source supplies no mime type.
             *
             * The list decides whether a row gets the square cover slot or the 16:9 frame slot, so a
             * missing entry is visible: `.aiff` was absent, so an AIFF with no mime type was laid out
             * as a video and its cover was cropped into a 58x34 rectangle. The audio-only containers
             * are all here now, including the ones that are rare on phones but common in a music
             * library.
             */
            val AUDIO_EXTENSIONS = setOf(
                "flac", "opus", "mp3", "m4a", "m4b", "aac", "ac3", "eac3", "dts", "thd",
                "mka", "aiff", "aif", "aifc", "wav", "wave", "ogg", "oga", "ape", "wv",
                "dsf", "dff", "amr", "aa", "aax", "mpc", "wma", "caf", "au", "snd", "tak",
            )

            /** Width of a video row's 16:9 thumbnail slot, in dp. Matches `item_file.xml`. */
            const val VIDEO_SLOT_DP = 58

            /** Height of a video row's thumbnail slot, in dp. */
            const val VIDEO_SLOT_HEIGHT_DP = 34

            /** Cover art is square; this is also the height of an audio row's art. */
            const val AUDIO_ART_DP = 40
        }
    }
}
