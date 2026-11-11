package org.spatialplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import org.spatialplayer.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, best-effort library previews. Video uses provider-cached thumbnails so browsing does not
 * reserve the playback decoder. Embedded album art is downsampled and its retriever released.
 */
class ThumbnailLoader(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var paused = false
    private val generation = AtomicInteger(0)
    fun pause() {
        paused = true
        generation.incrementAndGet()
        inFlight.values.forEach { it.cancel(true) }
        inFlight.clear()
    }
    fun resume() { paused = false }

    /**
     * Decodes run on a small fixed pool. More threads would contend for the single hardware video
     * decoder that the player itself may already be using.
     */
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "thumbnail-loader").apply { isDaemon = true }
    }

    /** In-flight decodes, so a fast scroll does not queue the same work repeatedly. */
    private val inFlight = ConcurrentHashMap<String, Future<*>>()

    /** Bound decoded preview memory by bytes. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Requests outstanding per ImageView, so a rebind can abandon the previous one. */
    private val pendingTag = AtomicInteger(0)

    /**
     * Loads a preview for [uri] into [target], or installs [fallbackRes] when there is none.
     *
     * [target] is tagged with the request so a result arriving after the row has been recycled for a
     * different file is discarded rather than shown against the wrong title.
     *
     * [isAudio] selects what "a preview" means: a decoded frame for a video, album art for a
     * recording. It is passed in rather than inferred from [mimeType] because the list also knows
     * audio by extension, and a file whose type the provider did not report still deserves its cover.
     */
    fun load(
        uri: Uri,
        mimeType: String?,
        isAudio: Boolean,
        target: ImageView,
        fallbackRes: Int,
    ) {
        if (paused) return
        val epoch = generation.get()
        val key = uri.toString()
        val token = pendingTag.incrementAndGet()
        target.tag = token

        val cached = cache.get(key)
        if (cached != null) {
            target.setImageBitmap(cached)
            return
        }

        target.setImageResource(fallbackRes)

        // Only pictures and recordings have anything worth showing.
        val decode: ((Uri) -> Bitmap?)? = when {
            isAudio -> ::decodeAlbumArt
            mimeType?.startsWith("video/") == true -> ::decodeFrame
            else -> null
        }
        if (decode == null) return
        if (inFlight.containsKey(key)) return

        val task = executor.submit {
            if (paused || generation.get() != epoch) return@submit
            val bitmap = runCatching { decode(uri)?.let {
                if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
            } }.getOrNull()
            mainHandler.post {
                if (paused || generation.get() != epoch) return@post
                inFlight.remove(key)
                if (bitmap == null) return@post
                cache.put(key, bitmap)
                // Only apply if this view still wants this exact request.
                if (target.tag == token) target.setImageBitmap(bitmap)
            }
        }
        inFlight[key] = task
    }

    /** Provider-cached previews avoid competing with foreground video decode. */
    private fun decodeFrame(uri: Uri): Bitmap? = runCatching {
        // Let the provider use its cached thumbnail. App-owned retrievers can reserve the same
        // hardware HEVC decoder the foreground HDR player needs, even after a Future is cancelled.
        appContext.contentResolver.loadThumbnail(uri, Size(THUMB_WIDTH, THUMB_HEIGHT), null)
    }.getOrNull()

    private fun decodeAlbumArt(uri: Uri): Bitmap? {
        runCatching {
            appContext.contentResolver.loadThumbnail(uri, Size(ART_SIZE, ART_SIZE), null)
        }.getOrNull()?.let { return it }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val embedded = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(embedded, 0, embedded.size, bounds)
            val options = BitmapFactory.Options()
            while (bounds.outWidth / options.inSampleSize > ART_SIZE * 2 ||
                bounds.outHeight / options.inSampleSize > ART_SIZE * 2) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(embedded, 0, embedded.size, options)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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

        /** Square album art stays sharp at the library row size. */
        private const val ART_SIZE = 256

        /** Placeholder while nothing is loaded. */
        val PLACEHOLDER_VIDEO = R.drawable.ic_aspect
        val PLACEHOLDER_AUDIO = R.drawable.ic_audio
    }
}
