package com.gan.spatialplayer.media

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
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
     * The stock sink, deliberately.
     *
     * A stereo upmix was wired in here through `setAudioProcessors` / `setAudioProcessorChain`, and
     * **it never engaged**. What was established on device, in order, because the answer is not
     * obvious and should not have to be rediscovered:
     *
     *  * The processor is constructed by this factory (`Log` from its `init`) and the sink built here
     *    is the very instance the renderers hold — `System.identityHashCode` matched on both sides of
     *    `buildAudioRenderers`, and a reflective proxy over the sink logged the renderers calling
     *    `setListener`, `setAudioAttributes`, `supportsFormat`, `getFormatSupport` and
     *    `configure(Format(2, ..., audio/raw, ..., [2, 48000]))` on it.
     *  * `AudioProcessingPipeline.configure` provably calls `processor.configure(format)` before
     *    consulting `isActive()`, and has no exception table, so nothing can quietly skip a
     *    processor in the middle of the chain.
     *  * `DefaultAudioSink.DefaultAudioProcessorChain` copies the array it is given without
     *    filtering, and its `getAudioProcessors()` returns it verbatim.
     *  * Yet `configure` is **never** entered on the processor — proven with a log as the method's
     *    first statement *and* with one in every branch that declines a format.
     *
     * The decisive observation is a negative one: `SonicAudioProcessor` lives in the same chain, and
     * speed changes work in this app — so a chain *is* being configured, and it is the stock one.
     * The chain handed to the builder is not the chain the sink ends up with. That is a Media3
     * internal this app cannot see into, and guessing at it further is not worth the risk to a working
     * audio path, so the upmix is not wired in at all until it is understood. `StereoUpmixProcessor`
     * is kept because its DSP is implemented and unit-verified.
     *
     * Disabling offload was also tried (it bypasses the processor chain, and the platform spatialiser
     * with it) and made no difference, so it is not carried either — it was a real cost to battery and
     * to high-resolution audio for no benefit.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = requireNotNull(
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams),
    ) { "default audio sink unavailable" }

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
