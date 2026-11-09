package com.gan.spatialplayer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Debug-only: ask the decoder itself what it thinks of a file. **Not present in release builds.**
 *
 * Playback failing with "nothing on the screen" can mean three very different things - the container
 * was never parsed, no decoder was selected, or a decoder was created and produced no frames - and
 * the player's own logcat does not distinguish them, because a renderer that never enables says
 * nothing at all. This probe stops at each of those boundaries and writes down which one was crossed:
 *
 * 1. every track the extractor can see, with the exact codec string it would hand a decoder;
 * 2. whether a decoder for the video track can be *created* and *configured*;
 * 3. how many frames it actually emits for the first samples, and the first error code if it stops.
 *
 * Run it against a file the player also plays, and a black screen becomes a specific failure.
 *
 * ```
 * adb shell am start -n <pkg>/com.gan.spatialplayer.DecodeProbeActivity \
 *   -d content://media/external/video/media/1234
 * ```
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DecodeProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            line("result", "no uri")
            finish()
            return
        }
        Thread {
            runCatching { probe(uri) }
                .onFailure { line("result", "threw ${it.javaClass.simpleName}: ${it.message}") }
            line("probe_done", "true")
        }.start()
    }

    private fun probe(uri: Uri) {
        line("input", "$uri")
        // Also ask Media3's own selector, because "the decoder can do it" and "Media3 will hand the
        // track to a decoder" are different questions - the second one is what decides whether a
        // video group is `supported`, and an empty candidate list means the track is dropped with no
        // error at all.
        for (mime in listOf("video/hevc", "video/dolby-vision", "video/av01", "video/avc")) {
            val plain = runCatching {
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mime, false, false)
                    .map { "${it.name} hw=${it.hardwareAccelerated}" }
            }
            line("selector", "$mime -> ${plain.getOrElse { "threw $it" }}")
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
        } catch (t: Throwable) {
            line("result", "extractor refused the source: ${t.javaClass.simpleName}: ${t.message}")
            extractor.release()
            return
        }

        var videoTrack = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
            line("track", "#$i $mime")
            if (mime.startsWith("video/")) {
                // MediaFormat has no key enumeration below API 29 (`keySet`), and enumerating is the
                // whole point of this probe - the interesting Dolby Vision keys are not in the
                // public API at all. So ask for the ones that matter instead.
                for (key in PROBED_KEYS) {
                    if (!format.containsKey(key)) continue
                    val value = format.readable(key) ?: continue
                    line("param", "#$i $key=$value")
                }
                val width = format.getIntOrNull(MediaFormat.KEY_WIDTH)
                val height = format.getIntOrNull(MediaFormat.KEY_HEIGHT)
                line("video", "#$i $mime ${width}x$height")
                if (videoTrack < 0) {
                    videoTrack = i
                    videoFormat = format
                }
            }
        }

        val format = videoFormat
        if (format == null) {
            line("result", "no video track in the container")
            extractor.release()
            return
        }
        decode(extractor, videoTrack, format)
        extractor.release()
    }

    /**
     * Creates a decoder for [format] and pumps samples into it, counting what comes back out.
     *
     * Output buffers are released immediately: nothing is rendered, this only answers whether frames
     * are produced at all. Twenty samples is enough - a decoder that cannot handle a stream fails on
     * configure or on the very first buffer, and this keeps the probe under a second on any file.
     */
    private fun decode(extractor: MediaExtractor, track: Int, format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        extractor.selectTrack(track)

        val decoder = try {
            MediaCodec.createDecoderByType(mime).also { line("decoder", "created for $mime") }
        } catch (t: Throwable) {
            line("result", "createDecoderByType failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            line("decoder", "configured and started")
        } catch (t: Throwable) {
            line("result", "configure failed: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { decoder.release() }
            return
        }

        val info = decoder.codecInfo
        line(
            "decoder",
            "name=${info?.name} hw=${info?.isHardwareAccelerated} " +
                "caps=${info?.let { summarize(it) }}",
        )

        val bufferInfo = MediaCodec.BufferInfo()
        var fed = 0
        var frames = 0
        var inputDone = false
        val deadline = System.currentTimeMillis() + TIMEOUT_MS

        while (frames == 0 && System.currentTimeMillis() < deadline) {
            if (!inputDone && fed < MAX_SAMPLES) {
                val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = decoder.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        line("input", "end of stream after $fed samples")
                    } else {
                        decoder.queueInputBuffer(
                            index,
                            0,
                            size,
                            extractor.sampleTime,
                            extractor.sampleFlags,
                        )
                        fed++
                        extractor.advance()
                    }
                }
            }

            when (val out = decoder.dequeueOutputBuffer(bufferInfo, INPUT_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = decoder.outputFormat
                    line(
                        "output",
                        "format changed: ${outFormat.getString(MediaFormat.KEY_MIME)} " +
                            "${outFormat.getIntOrNull(MediaFormat.KEY_WIDTH)}x" +
                            outFormat.getIntOrNull(MediaFormat.KEY_HEIGHT) + " " +
                            PROBED_KEYS.filter { outFormat.containsKey(it) }
                                .joinToString(",") { "$it=${outFormat.readable(it)}" },
                    )
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (out >= 0) {
                        frames++
                        line(
                            "frame",
                            "#$frames size=${bufferInfo.size} flags=${bufferInfo.flags} " +
                                "pts=${bufferInfo.presentationTimeUs}",
                        )
                        decoder.releaseOutputBuffer(out, false)
                    }
                }
            }
        }

        line(
            "result",
            if (frames > 0) {
                "DECODED $frames frame(s) after feeding $fed samples"
            } else {
                "NO FRAMES after feeding $fed samples"
            },
        )
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
    }

    private fun summarize(info: MediaCodecInfo): String = info.supportedTypes.joinToString("|") { type ->
        val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
        val profile = caps?.profileLevels?.joinToString("+") { "${it.profile}/${it.level}" } ?: "-"
        val size = caps?.videoCapabilities?.let { "${it.supportedWidths}x${it.supportedHeights}" } ?: "-"
        "$type profiles=$profile sizes=$size"
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** The value of [key] as this probe wants to print it: a size for CSD, a short string otherwise. */
    private fun MediaFormat.readable(key: String): String? {
        val bytes = runCatching { getByteBuffer(key) }.getOrNull()
        if (bytes != null) return "${bytes.remaining()} bytes"
        return runCatching { getString(key) }.getOrNull()
            ?: runCatching { getInteger(key) }.getOrNull()?.toString()
            ?: runCatching { getLong(key) }.getOrNull()?.toString()
            ?: runCatching { getFloat(key) }.getOrNull()?.toString()
    }

    private fun line(key: String, value: String) {
        Log.i(TAG, "decode probe :: $key :: $value")
    }

    private companion object {
        const val TAG = "DecodeProbe"
        const val MAX_SAMPLES = 20
        const val INPUT_TIMEOUT_US = 10_000L
        const val TIMEOUT_MS = 8_000L

        /**
         * Asked for by name because `MediaFormat` cannot be enumerated on API 34.
         *
         * `dolby-vision-config` is the one that matters here: when a stream carries Dolby Vision, the
         * extractor hands the decoder a codec-specific blob as well as the ordinary CSD, and a
         * decoder that does not want it can reject the whole format.
         */
        val PROBED_KEYS = listOf(
            "csd-0",
            "csd-1",
            "csd-2",
            "dolby-vision-config",
            "hdr-static-info",
            "hdr-to-sdr-tonemap-info",
            MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_TRANSFER,
            MediaFormat.KEY_COLOR_RANGE,
            MediaFormat.KEY_FRAME_RATE,
            MediaFormat.KEY_PROFILE,
            MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_MAX_INPUT_SIZE,
            MediaFormat.KEY_OPERATING_RATE,
            MediaFormat.KEY_PRIORITY,
            "rotation-degrees",
            "sar-width",
            "sar-height",
        )
    }
}
