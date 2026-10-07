package com.gan.spatialplayer.media

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.DefaultRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.ArrayList

/**
 * A renderers factory that puts the FFmpeg renderers where this app needs them.
 *
 * ## Why this exists rather than using `NextRenderersFactory` directly
 *
 * Media3 picks the first renderer that reports it can handle a track, and `NextRenderersFactory`
 * appends its `FfmpegAudioRenderer` *after* the platform `MediaCodecAudioRenderer`. On the
 * reference device that ordering is fatal: the vendor image registers `c2.dolby.eac3.decoder` for
 * `audio/ac3` even though it cannot decode AC-3, so the MediaCodec renderer claims the track,
 * fails during `configure`, and the whole playback errors out. `setEnableDecoderFallback(true)`
 * does not help, because fallback re-tries *codecs* inside one renderer - it never hands the track
 * to a different renderer.
 *
 * Text renderer ordering matters too, for the same "first match wins" reason: nextlib's
 * `NextTextRenderer` applies a user-configurable subtitle offset, and it has to be reached before
 * the stock `TextRenderer` claims the track.
 *
 * So this class subclasses `NextRenderersFactory` purely to reorder what it already built. It adds
 * no new renderer types and no new decoding behaviour - only priority.
 */
class SpatialRenderersFactory(
    context: Context,
    private val profile: DecoderProfile,
    /** Whether the user wants the platform to spatialise; also gates the stereo upmix. */
    private val spatialEnabled: Boolean = false,
    /** Which mapping the upmix uses; see [UpmixMode]. */
    private val upmixMode: UpmixMode = UpmixMode.SURROUND,
) : NextRenderersFactory(context) {

    init {
        // CRITICAL: nextlib's factory returns early - before registering any FFmpeg renderer -
        // when extensionRendererMode is EXTENSION_RENDERER_MODE_OFF, which is Media3's default.
        // Without this line no FFmpeg renderer is ever created, and every surround format the
        // platform cannot really decode becomes a hard playback failure.
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Fallback stays on: within one renderer it is what lets Media3 step from a broken codec to
        // the next candidate instead of failing the track outright.
        setEnableDecoderFallback(true)
        setMediaCodecSelector(DecoderPolicy.selectorFor(profile))
        setEnableAudioFloatOutput(true)
        setEnableAudioTrackPlaybackParams(true)
    }

    /**
     * Builds the sink, inserting the stereo upmix when spatial audio is wanted.
     *
     * ## Why float output has to be off for the upmix to exist at all
     *
     * This cost most of a session to find, so it is written down precisely: **Media3 1.8
     * `DefaultAudioSink.configure` skips the custom `AudioProcessorChain` whenever float output is
     * used.** From `javap` on the 1.8.0 artifact:
     *
     * ```
     * 70: ifeq 86        // shouldUseFloatOutput(pcmEncoding) == false -> the int path
     * 73: add(toFloatPcmAudioProcessor)
     * 83: goto 111       // <-- jumps past the chain entirely
     * 86: add(toInt16PcmAudioProcessor)
     * 99: add(audioProcessorChain.getAudioProcessors())   // only reached on the int path
     * ```
     *
     * `shouldUseFloatOutput` is `enableFloatOutput && isEncodingHighResolutionPcm(pcmEncoding)`, and
     * nextlib's FFmpeg audio renderer decodes to **float** - which is exactly why this app enabled
     * float output in the first place. So with float output on, every custom processor is silently
     * bypassed: the sink is configured, no exception is raised, the chain's `getAudioProcessors` is
     * simply never called and `configure` is never invoked on the processor.
     *
     * The upmix is therefore only installed with float output off. That costs a float-to-16-bit
     * conversion on the decoder's output, which is a real trade and a deliberate one: the case this
     * feature exists for is a Bluetooth headset, whose link is lossy (LDAC) long before 16 bits
     * matter, and turning spatial audio off in Settings restores the bit-perfect float path for
     * music that does not want to be spatialised.
     *
     * Speed changes are unaffected either way: `setEnableAudioTrackPlaybackParams(true)` hands
     * `PlaybackParameters` to `AudioTrack` itself rather than to `SonicAudioProcessor`.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        if (!spatialEnabled) {
            return requireNotNull(
                super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams),
            ) { "default audio sink unavailable" }
        }

        Log.i(TAG, "building audio sink with the stereo upmix in the chain")
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    StereoUpmixProcessor(
                        mode = upmixMode,
                        canRenderSixChannels = { sampleRate ->
                            AudioOutputCapability.canOpenTrack(6, sampleRate)
                        },
                    ),
                ),
            )
            // Off, for the reason above - not a preference.
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )

        Log.i(TAG, "audio renderers as built: ${out.map { it.javaClass.simpleName }}")

        if (!profile.prefersFfmpegAudioFirst()) return

        // Move the FFmpeg audio renderer to the front so it gets first refusal on the tracks it
        // handles. If the build did not include one, the list is left exactly as it was.
        val index = out.indexOfFirst { it is FfmpegAudioRenderer }
        if (index > 0) {
            val ffmpegRenderer = out.removeAt(index)
            out.add(0, ffmpegRenderer)
        } else if (index < 0) {
            // This is the failure that makes surround audio unplayable: with no FFmpeg audio
            // renderer registered, Media3 has nothing to fall back to when the vendor decoder
            // rejects a format it falsely advertises.
            Log.w(TAG, "no FfmpegAudioRenderer registered (native library unavailable?)")
        }
        Log.i(TAG, "audio renderers after reorder: ${out.map { it.javaClass.simpleName }}")
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )

        if (!profile.prefersFfmpegVideoFirst()) return

        val index = out.indexOfFirst { it is FfmpegVideoRenderer }
        if (index > 0) {
            val ffmpegRenderer = out.removeAt(index)
            out.add(0, ffmpegRenderer)
        }
    }

    private fun DecoderProfile.prefersFfmpegAudioFirst(): Boolean = when (this) {
        // Auto deliberately prefers FFmpeg only for the surround formats the platform mishandles;
        // the selector already biases candidate order per MIME type, and this puts the renderer
        // that owns those codecs in front for the whole renderer list.
        DecoderProfile.AUTO -> true
        DecoderProfile.FFMPEG_AUDIO -> true
        DecoderProfile.FFMPEG_ONLY -> true
        else -> false
    }

    private fun DecoderProfile.prefersFfmpegVideoFirst(): Boolean = when (this) {
        DecoderProfile.FFMPEG_VIDEO, DecoderProfile.FFMPEG_ONLY -> true
        else -> false
    }

    private companion object {
        const val TAG = "SpatialRenderers"
    }
}
