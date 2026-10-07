package com.gan.spatialplayer.media

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
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

/** Builds the renderers factory and codec selector for a [DecoderProfile]. */
object DecoderPolicy {

    /**
     * Audio formats where the platform decoder is either absent or unreliable, and the bundled
     * FFmpeg build is the correct first choice. See the class comment for the measurement behind
     * this list.
     */
    private val FFMPEG_FIRST_AUDIO: Set<String> = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        "audio/mlp",
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
    )

    /** True when Media3 will prefer the FFmpeg audio renderer for [mime]. */
    fun prefersFfmpegAudio(mime: String): Boolean = mime in FFMPEG_FIRST_AUDIO

    /**
     * Wraps [MediaCodecSelector.DEFAULT] to reorder candidates per MIME type.
     *
     * Media3 exposes one selector for every renderer, and the renderer asks it for candidates by
     * MIME type, so the MIME type is the only signal available to express "FFmpeg should win for
     * this codec". Reordering (rather than filtering) is safe: [MediaCodecSelector.DEFAULT]'s full
     * list is preserved behind the preferred entries, so a track can never become unplayable just
     * because of policy.
     */
    private fun reordering(preferHardware: (String) -> Boolean): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val all: List<MediaCodecInfo> = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (preferHardware(mimeType)) {
                all.filter { it.hardwareAccelerated } + all.filterNot { it.hardwareAccelerated }
            } else {
                // FFmpeg-preferred: park software decoders first so the FFmpeg renderer claims the
                // track. The platform decoders stay in the list as a fallback.
                all.filterNot { it.hardwareAccelerated } + all.filter { it.hardwareAccelerated }
            }
        }

    /** Nothing is reordered; the platform's own preference is used. */
    fun selectorFor(profile: DecoderProfile): MediaCodecSelector = when (profile) {
        // Hardware for everything except the surround audio formats.
        DecoderProfile.AUTO -> reordering { mime -> !prefersFfmpegAudio(mime) }

        DecoderProfile.HARDWARE_ONLY -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(mime, secure, tunneling)
                .filter { it.hardwareAccelerated }
        }

        DecoderProfile.FFMPEG_ONLY -> MediaCodecSelector.DEFAULT

        // Every audio codec goes to FFmpeg; video stays on hardware.
        DecoderProfile.FFMPEG_AUDIO -> reordering { mime -> !mime.startsWith("audio/") }

        // Video goes to FFmpeg; audio stays on hardware.
        DecoderProfile.FFMPEG_VIDEO -> reordering { mime -> !mime.startsWith("video/") }
    }

    /**
     * Renderers factory for [profile].
     *
     * Every profile except [DecoderProfile.FFMPEG_ONLY] goes through [SpatialRenderersFactory],
     * which registers both the platform and the FFmpeg renderer families and reorders them
     * according to policy. `FFMPEG_ONLY` registers nothing but FFmpeg renderers.
     */
    fun renderersFactory(
        context: Context,
        profile: DecoderProfile,
        spatialEnabled: Boolean = false,
    ): DefaultRenderersFactory =
        when (profile) {
            DecoderProfile.FFMPEG_ONLY ->
                io.github.anilbeesetti.nextlib.media3ext.ffdecoder
                    .FFmpegOnlyRenderersFactory(context)
                    .apply {
                        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        setEnableDecoderFallback(true)
                        setEnableAudioFloatOutput(true)
                        setEnableAudioTrackPlaybackParams(true)
                    }

            else -> SpatialRenderersFactory(context, profile, spatialEnabled)
        }

    /**
     * Whether this profile can decode [mime] at all, combining the platform's codec list with the
     * bundled FFmpeg build. Used for pre-flight checks and for annotating the track list.
     */
    fun canDecode(mime: String?, profile: DecoderProfile): Boolean {
        if (mime == null) return false
        val platform = DeviceCapabilities.hasHardwareDecoder(mime)
        val ffmpeg = FfmpegCodecs.isAvailable(mime)
        return when (profile) {
            DecoderProfile.FFMPEG_ONLY -> ffmpeg
            DecoderProfile.HARDWARE_ONLY -> platform
            else -> platform || ffmpeg
        }
    }

    /** Short human label for how [mime] will actually be handled under [profile]. */
    fun describeRoute(mime: String?, profile: DecoderProfile): String {
        if (mime == null) return "unknown"
        val platform = DeviceCapabilities.hasHardwareDecoder(mime)
        val ffmpeg = FfmpegCodecs.isAvailable(mime)
        val ffName = FfmpegCodecs.decoderName(mime)

        fun hw() = if (platform) "hardware" else null
        fun ff() = if (ffmpeg) "ffmpeg/${ffName ?: "?"}" else null

        val ordered: List<String?> = when (profile) {
            DecoderProfile.FFMPEG_ONLY -> listOf(ff(), hw())
            DecoderProfile.HARDWARE_ONLY -> listOf(hw())
            DecoderProfile.AUTO ->
                if (prefersFfmpegAudio(mime)) listOf(ff(), hw()) else listOf(hw(), ff())
            DecoderProfile.FFMPEG_AUDIO ->
                if (mime.startsWith("audio/")) listOf(ff(), hw()) else listOf(hw(), ff())
            DecoderProfile.FFMPEG_VIDEO ->
                if (mime.startsWith("video/")) listOf(ff(), hw()) else listOf(hw(), ff())
        }
        return ordered.filterNotNull().firstOrNull() ?: "no decoder"
    }
}
