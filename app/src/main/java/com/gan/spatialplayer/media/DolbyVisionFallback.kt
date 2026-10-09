package com.gan.spatialplayer.media

import android.media.MediaCodecList
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor

/**
 * Plays the HDR10 base layer of a Dolby Vision film on a device that has no Dolby Vision decoder.
 *
 * ## The bug this exists for
 *
 * A Dolby Vision Profile 7 UHD remux carries its picture as an ordinary HEVC Main10 stream plus a
 * second, enhancement layer and a Dolby Vision configuration record. The base layer is a valid
 * HDR10 stream on its own - that is the whole point of the format, and it is what a non-Dolby
 * display is supposed to show.
 *
 * Media3's Matroska extractor does not do that. On finding the configuration record it relabels the
 * track `video/dolby-vision`:
 *
 * ```java
 * if (dolbyVisionConfigBytes != null) {
 *   DolbyVisionConfig dolbyVisionConfig = DolbyVisionConfig.parse(...);
 *   if (dolbyVisionConfig != null) {
 *     codecs = dolbyVisionConfig.codecs;
 *     mimeType = MimeTypes.VIDEO_DOLBY_VISION;
 *   }
 * }
 * ```
 *
 * On the reference device (Sony XQ-DQ72, Snapdragon 8 Gen 2) **no decoder is registered for
 * `video/dolby-vision`** - the panel advertises HDR10 and HLG only - so the candidate list for that
 * type is empty, `MediaCodecVideoRenderer.supportsFormatInternal` returns
 * `FORMAT_UNSUPPORTED_SUBTYPE`, and the video track group ends up `supported=false`. Media3 then
 * drops the track **without an error**: the player reports READY, the clock runs, the audio plays,
 * and the picture is black. Measured on a clip cut from the user's film:
 *
 * ```
 * group :: #1 type=video selected=false supported=false
 * state :: READY      isPlaying :: true      position advancing
 * ffmpeg/video: decoded 1 frame      ← the decoder is fine
 * selector :: video/hevc        -> [c2.qti.hevc.decoder hw=true, ...]
 * selector :: video/dolby-vision -> []                    ← nothing claims it
 * ```
 *
 * Relabelling the *same* track as `video/hevc` decodes it immediately, which is what this does - but
 * only when the platform really cannot decode Dolby Vision, so a device that can still shows the
 * full Dolby Vision presentation.
 *
 * ## How it is installed
 *
 * The relabelling has to happen on the `Format` Media3 builds, which no public API exposes, so this
 * wraps the extractor instead: the Matroska extractor is left untouched and its `Format` is
 * rewritten on the way past. Everything else about the file - all 51 subtitle tracks, the TrueHD
 * track, the chapter atoms - is still the stock extractor's work.
 *
 * Not a preference and not a profile: this is a correctness fallback, and it does nothing at all on
 * a device with a Dolby Vision decoder.
 */
object DolbyVisionFallback {

    /** `video/dolby-vision` decoder names, if the platform has any. Cheap enough to ask once. */
    private val deviceHasDolbyVisionDecoder: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.contains(MimeTypes.VIDEO_DOLBY_VISION)
            }
        }.getOrDefault(false)
    }

    fun isNeeded(): Boolean = !deviceHasDolbyVisionDecoder

    /**
     * The extractors to hand the player.
     *
     * When the device can play Dolby Vision, the stock factory is returned untouched - there is
     * nothing to fix and the original path is the one to keep.
     */
    fun extractorsFactory(): ExtractorsFactory {
        if (!isNeeded()) return DefaultExtractorsFactory()
        return ExtractorsFactory {
            DefaultExtractorsFactory().createExtractors().map { extractor ->
                if (extractor is MatroskaExtractor) RewritingExtractor(extractor) else extractor
            }.toTypedArray()
        }
    }

    /**
     * A `Format` as the platform can actually decode it.
     *
     * The codec string is dropped rather than translated: it is a Dolby Vision string (`dvhe.07.06`)
     * and no HEVC decoder will match it, but the format's `initializationData` still carries the
     * genuine HEVC codec-specific data, and Media3 derives the codec string from that when the field
     * is absent. Rewriting it by hand would mean re-implementing the HEVC configuration parser for
     * no gain; leaving it out is both simpler and correct.
     */
    private fun Format.asBaseLayer(): Format {
        val builder = buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265)
        runCatching { builder.setCodecs(null) }
        return runCatching { builder.build() }.getOrDefault(this)
    }

    private class RewritingExtractor(private val delegate: Extractor) : Extractor {

        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

        override fun init(output: ExtractorOutput) = delegate.init(RewritingOutput(output))

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            delegate.read(input, seekPosition)

        override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

        override fun release() = delegate.release()

        override fun getUnderlyingImplementation(): Extractor = delegate
    }

    private class RewritingOutput(private val delegate: ExtractorOutput) : ExtractorOutput {

        override fun track(id: Int, type: Int): TrackOutput =
            RewritingTrackOutput(delegate.track(id, type))

        override fun endTracks() = delegate.endTracks()

        override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) = delegate.seekMap(seekMap)
    }

    private class RewritingTrackOutput(private val delegate: TrackOutput) : TrackOutput {

        override fun format(format: Format) {
            delegate.format(
                if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                    format.asBaseLayer()
                } else {
                    format
                },
            )
        }

        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun sampleData(input: androidx.media3.common.DataReader, length: Int, allowEndOfInput: Boolean): Int =
            delegate.sampleData(input, length, allowEndOfInput)

        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: androidx.media3.common.util.ParsableByteArray, length: Int) =
            delegate.sampleData(data, length)

        override fun sampleData(
            data: androidx.media3.common.util.ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) = delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            encryptionData: TrackOutput.CryptoData?,
        ) = delegate.sampleMetadata(timeUs, flags, size, offset, encryptionData)
    }
}
