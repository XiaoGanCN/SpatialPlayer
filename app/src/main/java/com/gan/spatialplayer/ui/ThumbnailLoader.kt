package com.gan.spatialplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.gan.spatialplayer.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads small frame previews for the library list.
 *
 * ## Why not decode at row size
 *
 * `getFrameAtTime()` returns a full-resolution frame - 4K for a modern film, which is roughly 35 MB
 * as an `ARGB_8888` bitmap. Sixteen of those in a row cache would exhaust the heap, and the decode
 * itself would stutter the list. [MediaMetadataRetriever.getScaledFrameAtTime] does the scaling
 * inside the decoder, so only the thumbnail is ever allocated.
 *
 * ## Why the retriever is not cached
 *
 * A `MediaMetadataRetriever` holds an open file descriptor and native decoder state. Keeping one per
 * row leaks descriptors as the user scrolls and can trip the "too many open files" limit. One is
 * created per decode and released in a `finally`, which is slower but bounded.
 *
 * Everything is best-effort: a format without a decodable video track, an unreadable URI, or a
 * failure to allocate simply leaves the caller's placeholder in place. Browsing must never fail
 * because a preview could not be produced.
 */
class ThumbnailLoader(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Decodes run on a small fixed pool. More threads would contend for the single hardware video
     * decoder that the player itself may already be using.
     */
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "thumbnail-loader").apply { isDaemon = true }
    }

    /** In-flight decodes, so a fast scroll does not queue the same work repeatedly. */
    private val inFlight = ConcurrentHashMap<String, Future<*>>()

    /** Approximate byte budget; the entry count is what matters, but size guards 4K frames. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Requests outstanding per ImageView, so a rebind can abandon the previous one. */
    private val pendingTag = AtomicInteger(0)

    /**
     * Loads a preview for [entry] into [target], or installs [fallbackRes] when there is none.
     *
     * [target] is tagged with the request so a result arriving after the row has been recycled for a
     * different file is discarded rather than shown against the wrong title.
     */
    fun load(uri: Uri, mimeType: String?, target: ImageView, fallbackRes: Int) {
        val key = uri.toString()
        val token = pendingTag.incrementAndGet()
        target.tag = token

        val cached = cache.get(key)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }

        target.setImageResource(fallbackRes)

        // Only video containers have a frame worth showing.
        if (mimeType?.startsWith("video/") != true) return
        if (inFlight.containsKey(key)) return

        val task = executor.submit {
            val bitmap = decode(uri)
            mainHandler.post {
                inFlight.remove(key)
                if (bitmap == null) return@post
                cache.put(key, bitmap)
                // Only apply if this view still wants this exact request.
                if (target.tag == token) target.setImageBitmap(bitmap)
            }
        }
        inFlight[key] = task
    }

    /**
     * Grabs one representative frame.
     *
     * The very first frame is a poor choice: plenty of encodes open on black, a slate or a fade, so
     * a thumbnail taken at zero reads as "this file is broken". A few timestamps are tried in order
     * and the first one that yields a frame which is not almost entirely black wins. The darkness
     * test is what stops a black lead-in from being accepted just because a frame exists.
     */
    private fun decode(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)

            var best: Bitmap? = null
            for (offsetUs in FRAME_CANDIDATES_US) {
                val frame = retriever.getScaledFrameAtTime(
                    offsetUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_WIDTH,
                    THUMB_HEIGHT,
                ) ?: continue
                if (!isMostlyBlack(frame)) return scaleDown(frame)

                // Keep the first frame as a fallback in case every candidate is dark, so a genuinely
                // dark film still gets a preview rather than the placeholder icon.
                if (best == null) best = frame else frame.recycle()
            }
            best?.let { scaleDown(it) }
        } catch (_: Exception) {
            // Includes RuntimeException from failed setDataSource and allocation failures; a missing
            // preview is never worth surfacing to the user.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * True when a frame carries almost no information.
     *
     * Samples a sparse grid rather than every pixel: at thumbnail scale the difference never
     * matters and this runs on the decode thread for every list row.
     */
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 9).coerceAtLeast(1)
        var lit = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luma = ((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3
                if (luma > BLACK_LUMA) lit++
                total++
                x += stepX
            }
            y += stepY
        }
        return total == 0 || lit * 100 / total < LIT_PERCENT
    }

    /** Scales a decoder result down when it ignored the requested size. */
    private fun scaleDown(frame: Bitmap): Bitmap {
        if (frame.width <= THUMB_WIDTH * 2 && frame.height <= THUMB_HEIGHT * 2) return frame
        val scaled = Bitmap.createScaledBitmap(frame, THUMB_WIDTH, THUMB_HEIGHT, true)
        if (scaled != frame) frame.recycle()
        return scaled
    }

    /** Drops queued work. Call when the screen goes away. */
    fun shutdown() {
        inFlight.values.forEach { it.cancel(true) }
        inFlight.clear()
        executor.shutdownNow()
        cache.evictAll()
    }

    companion object {
        /** ~12 MB of previews, which is a few hundred 256x144 frames. */
        private const val CACHE_BYTES = 12 * 1024 * 1024

        private const val THUMB_WIDTH = 256
        private const val THUMB_HEIGHT = 144

        /**
         * Timestamps tried in order, in microseconds.
         *
         * Early ones keep the thumbnail representative of the opening; later ones rescue files whose
         * first few seconds are black or are a studio slate.
         */
        private val FRAME_CANDIDATES_US = longArrayOf(
            1_000_000L,
            3_000_000L,
            8_000_000L,
            20_000_000L,
            45_000_000L,
        )

        /** Luma below which a sampled pixel counts as unlit. */
        private const val BLACK_LUMA = 24

        /** A frame with less than this share of lit samples is rejected as a lead-in. */
        private const val LIT_PERCENT = 4

        /** Placeholder while nothing is loaded. */
        val PLACEHOLDER_VIDEO = R.drawable.ic_aspect
        val PLACEHOLDER_AUDIO = R.drawable.ic_audio
    }
}
