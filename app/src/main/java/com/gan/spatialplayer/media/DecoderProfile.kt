package com.gan.spatialplayer.media

import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Which decoder handles which stream.
 *
 * Media3 decides this per track from a [MediaCodecSelector]: the selector is asked for candidate
 * decoders for a MIME type, best first, and Media3 falls through the list. So decoder policy is
 * entirely expressed by the order and content of those lists - no per-file tuning required.
 *
 * ## Why surround audio is deliberately routed away from the platform decoder
 *
 * Measured on the reference device (Sony XQ-DQ72, Snapdragon 8 Gen 2): the vendor image advertises
 * an `audio/ac3` codec but does **not** actually have an AC-3 decoder. It has an *E-AC3* decoder
 * (`c2.dolby.eac3.decoder`) that is registered for both MIME types, and initialising it against a
 * real AC-3 stream throws `DecoderInitializationException`. Relying on `MediaCodecList` alone
 * therefore produces a hard playback error on the most common movie audio format there is.
 *
 * So the default policy treats the surround formats (AC3, EAC3, TrueHD/MLP, DTS) as FFmpeg's job,
 * and hands everything else - AAC, FLAC, Opus, Vorbis, MP3, and all video - to the platform first
 * so hardware decode is kept where it is both correct and much cheaper.
 */
enum class DecoderProfile(
    val label: String,
    val detail: String,
) {
    /** Hardware first; FFmpeg owns the surround audio formats the platform mishandles. */
    AUTO(
        label = "Auto",
        detail = "Hardware video + FFmpeg for surround audio (AC3/EAC3/TrueHD/DTS)",
    ),

    /** Force hardware; refuse rather than fall back to software. */
    HARDWARE_ONLY(
        label = "Hardware only",
        detail = "Never software-decode; fails instead of falling back",
    ),

    /** Force FFmpeg for every stream. Slow, but predictable and codec-complete. */
    FFMPEG_ONLY(
        label = "FFmpeg only",
        detail = "Software decode everything (CPU heavy, best compatibility)",
    ),

    /** Hardware video, FFmpeg audio. A blunter version of Auto, kept for comparison. */
    FFMPEG_AUDIO(
        label = "FFmpeg audio",
        detail = "Hardware video, software audio for every audio codec",
    ),

    /** FFmpeg video, hardware audio. Useful when a hardware video decoder misbehaves. */
    FFMPEG_VIDEO(
        label = "FFmpeg video",
        detail = "Software video, hardware audio",
    ),
    ;

    companion object {
        val DEFAULT = AUTO
    }
}
