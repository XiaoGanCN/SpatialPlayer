package com.gan.spatialplayer.media

import androidx.media3.common.MimeTypes
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

/**
 * Dictionary of what the bundled FFmpeg build can decode.
 *
 * The FFmpeg shared libraries ship inside `nextlib-media3ext` and are compiled with a deliberately
 * narrow decoder set (FFmpeg 6.0 in the 1.9.x line). The authoritative check is always
 * [FfmpegLibrary.supportsFormat], which calls into `avcodec_find_decoder`; the maps below exist so
 * the inspection screen can name the codec and so callers can reason about coverage without a
 * native call.
 *
 * The set that matters most for this player: ac3, eac3, dca (DTS), mlp, truehd. AV1 is absent from
 * this FFmpeg build, so AV1 playback relies on the platform hardware decoder instead.
 */
object FfmpegCodecs {

    /** Media3 MIME -> FFmpeg decoder name, for audio. */
    val AUDIO_DECODERS: Map<String, String> = mapOf(
        MimeTypes.AUDIO_AC3 to "ac3",
        MimeTypes.AUDIO_E_AC3 to "eac3",
        MimeTypes.AUDIO_E_AC3_JOC to "eac3",
        MimeTypes.AUDIO_TRUEHD to "truehd",
        // TrueHD rides inside an MLP stream; the companion MLP decoder covers the core.
        "audio/mlp" to "mlp",
        MimeTypes.AUDIO_DTS to "dca",
        MimeTypes.AUDIO_DTS_HD to "dca",
        MimeTypes.AUDIO_DTS_EXPRESS to "dca",
        MimeTypes.AUDIO_AAC to "aac",
        MimeTypes.AUDIO_VORBIS to "vorbis",
        MimeTypes.AUDIO_OPUS to "opus",
        MimeTypes.AUDIO_FLAC to "flac",
        MimeTypes.AUDIO_ALAC to "alac",
        MimeTypes.AUDIO_MPEG to "mp3",
        MimeTypes.AUDIO_AMR_NB to "amrnb",
        MimeTypes.AUDIO_AMR_WB to "amrwb",
        MimeTypes.AUDIO_ALAW to "pcm_alaw",
        MimeTypes.AUDIO_MLAW to "pcm_mulaw",
    )

    /** Media3 MIME -> FFmpeg decoder name, for video. */
    val VIDEO_DECODERS: Map<String, String> = mapOf(
        MimeTypes.VIDEO_H264 to "h264",
        MimeTypes.VIDEO_H265 to "hevc",
        MimeTypes.VIDEO_MPEG2 to "mpeg2video",
        MimeTypes.VIDEO_MPEG to "mpegvideo",
        MimeTypes.VIDEO_VP8 to "libvpx_vp8",
        MimeTypes.VIDEO_VP9 to "libvpx_vp9",
    )

    val AUDIO: List<String> get() = AUDIO_DECODERS.keys.toList()
    val VIDEO: List<String> get() = VIDEO_DECODERS.keys.toList()

    /** True when the bundled FFmpeg build actually contains a decoder for [mime]. */
    fun isAvailable(mime: String): Boolean = runCatching {
        FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime)
    }.getOrDefault(false)

    /** FFmpeg decoder name for [mime], or null when FFmpeg does not handle it. */
    fun decoderName(mime: String?): String? {
        if (mime == null) return null
        return AUDIO_DECODERS[mime] ?: VIDEO_DECODERS[mime]
    }

    /** Decoder names present in the bundled build, for display. */
    fun bundledDecoderNames(): Set<String> =
        (AUDIO_DECODERS.values + VIDEO_DECODERS.values).toSet()

    /** Version string reported by the bundled FFmpeg, or "unavailable". */
    fun version(): String {
        val reported: String? = runCatching {
            if (FfmpegLibrary.isAvailable()) FfmpegLibrary.getVersion() else null
        }.getOrNull()
        return reported ?: "unavailable"
    }
}
