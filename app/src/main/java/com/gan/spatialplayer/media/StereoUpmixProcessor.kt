package com.gan.spatialplayer.media

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Upmixes stereo to 5.1 so the platform spatialiser can see it at all.
 *
 * The problem this solves is narrow and measured, not theoretical. On the reference device
 * `Spatializer.canBeSpatialized()` answers *false* for stereo PCM and *true* for 5.1 PCM, so a
 * stereo music file never enters the head-tracked spatial pipeline no matter what the app declares
 * about its audio attributes. Multichannel film audio already takes that path; stereo has no route
 * into it. Media3 has no processor that changes the channel count on this branch either - its
 * `ChannelMixingAudioProcessor` builds a linear matrix, which can spread stereo across six channels
 * but cannot low-pass a subwoofer feed or decorrelate the rears - so the upmix has to exist here.
 *
 * The upmix is a plain, musical one, deliberately short of a licensed decoder:
 *
 * - **front left/right** carry the input *bit-exact*, with no gain applied at all. Anything else
 *   would colour the part of the signal the listener already knows, and this is the one pair of
 *   channels in the whole processor where a gain mistake is obvious.
 * - **front centre** carries the correlated, mono part `(L + R) / 2` at -3 dB *relative to each
 *   front channel*, which is what pulls vocals and dialogue forward without hollowing out the
 *   stereo image: an anti-phase pair cancels in the centre, so the sides stay where they are.
 * - **LFE** carries a sum of both channels through a second-order Butterworth low-pass at 120 Hz,
 *   scaled by -6 dB. Sending the sub the full-range sum is the single most common way to make an
 *   upmix sound muddy, so the filter is not optional.
 * - **back left/right** carry the *difference* `(L - R) / 2` and its inverse at -6 dB, delayed by
 *   ~12 ms. The rear therefore carries out-of-phase, decorrelated content - the ambience that a
 *   stereo mix already contains - rather than a delayed copy of what the front is playing. The
 *   inverse polarity is intentional: it is what widens the rear image instead of collapsing it
 *   into a phantom centre.
 *
 * ## Gain structure
 *
 * Front left/right unity, centre -9.0 dB on the sum (that is `(L + R) / 2` at -3 dB), LFE -6.0 dB
 * behind the low-pass, rears -6.0 dB, plus a -0.915 dB (x0.9) trim on everything except the fronts.
 * The trim is not there to rescue a hot mix: nothing here compresses or limits. It keeps the
 * upmixed channels from being an overall level *boost* over the source, and it buys enough headroom
 * that ordinary music, whose channels differ, never reaches the clamp.
 *
 * A mono-presented source at 0 dBFS is the case where the clamp is genuinely reachable: front left
 * and right are bit-exact copies of a -0.0 dBFS input, so no linear upmix can keep their sum plus a
 * centre inside full scale. That is a deliberate trade - holding the fronts unmodified matters more
 * than never clipping a hard-panned or mono master at its very loudest transients.
 *
 * ## What this processor does not do
 *
 * It does not decide whether the upmix is wanted or whether the sink can carry six channels: it
 * activates on any stereo 16-bit input, and the caller owns that policy. If the output cannot take
 * 5.1, upmixing is worse than staying stereo, so check the sink first. It also assumes the Media3
 * `audio/raw` byte order, which is little-endian on every Android ABI this app ships to, and it is
 * inactive for anything that is not exactly stereo 16-bit PCM - float, 24-bit, mono and already
 * multichannel input is left alone.
 *
 * No static mutable state: one instance per audio sink, all filter and delay state owned by it, and
 * that state is cleared on `flush()` and `reset()` so a seek cannot click.
 */
class StereoUpmixProcessor(
    /**
     * Whether the output can actually take six channels at a given sample rate.
     *
     * Asked here rather than decided by the caller because the sample rate is only known once the
     * stream is configured, and upmixing onto a sink that then refuses the layout is worse than
     * staying stereo: it turns a track that plays into one that does not. Injected as a lambda so
     * the DSP stays free of Android APIs.
     */
    private val canRenderSixChannels: (sampleRate: Int) -> Boolean = { true },
) : AudioProcessor {

    private var active = false

    /**
     * Bytes carried over from the previous call: fewer than one frame, a source that delivered a
     * partial frame at the end of a buffer. Kept and prepended to the next call rather than
     * dropped, because dropping it would desynchronise every later sample from its channel.
     */
    private var remainder = 0

    /** [remainder] plus the incoming bytes, so a frame split across calls is reassembled. */
    private var inputBytes: ByteArray = ByteArray(INITIAL_INPUT_BYTES)

    /** Written into on every call; grown only while the input grows, never per buffer. */
    private var outputBytes: ByteArray = ByteArray(0)

    /**
     * Reused view over [outputBytes], so handing output out costs two field writes rather than a
     * `ByteBuffer.wrap` per call. The sink reads `[0, limit)`, and the read position is the sink's
     * own: this processor never advances it afterwards.
     */
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    /** Set while [queueInput] is assembling [outputBytes]; cleared once [getOutput] hands it out. */
    private var outputPending = false
    private var inputEnded = false

    // Centre and rear gains held as integer multipliers so the two hot channels never touch a
    // float. Scaling is `(sample * gain) / Q`, which is exact enough at 16-bit and allocation-free.
    private var centreGain = 0
    private var rearGain = 0

    // Low-pass coefficients and state for the LFE, one biquad state pair per input channel.
    private val lowPassCoefficients = FloatArray(5)
    private var lowPassLeft = 0f
    private var lowPassLeftPrev = 0f
    private var lowPassRight = 0f
    private var lowPassRightPrev = 0f

    /**
     * Delay rings for the rears, masked to a power of two so the read index needs no modulo. Sized
     * for the sample rate in [configure]; the delay itself only exists so the rears are not
     * time-aligned with the fronts, which is what makes them read as space rather than as an echo
     * of the front pair.
     */
    private var delayMask = 0
    private var delaySamples = 0
    private var rearLeftRing = IntArray(0)
    private var rearRightRing = IntArray(0)
    private var ringIndex = 0

    /**
     * Accepts stereo 16-bit PCM at the rates the audio sink uses, and declares 5.1 PCM out.
     *
     * Anything else **throws** [AudioProcessor.UnhandledAudioFormatException], which is how Media3
     * is told to leave this processor out of the pipeline - the audio then passes through untouched
     * rather than mangled. The return type is not nullable, so throwing is the only way to decline;
     * returning the input instead would claim the format was accepted while emitting nothing, and
     * the sink would then be fed silence.
     *
     * Coefficients and the delay rings are rebuilt here from the actual sample rate, never
     * hardcoded, so a 44.1 kHz file gets a 120 Hz corner at 44.1 kHz and not a scaled one.
     */
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            active = false
            Log.i(TAG, "passing through: not stereo 16-bit PCM ($inputAudioFormat)")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.sampleRate != SR_44_1 && inputAudioFormat.sampleRate != SR_48) {
            active = false
            Log.i(TAG, "passing through: ${inputAudioFormat.sampleRate} Hz is not a rate we filter for")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // One line each way. Whether this engaged is otherwise invisible: the chips report the
        // decoder's layout, not the sink's, so a stereo track that was upmixed and one that was left
        // alone look identical everywhere in the app.
        Log.i(TAG, "upmixing ${inputAudioFormat.sampleRate} Hz stereo to 5.1")

        if (!canRenderSixChannels(inputAudioFormat.sampleRate)) {
            // The output cannot take 5.1 at this rate, so leave the stereo alone. The platform cannot
            // spatialise stereo, so this track simply does not get head tracking - which is honest,
            // and better than a track that refuses to play.
            active = false
            Log.i(TAG, "passing through: output cannot take 5.1 at ${inputAudioFormat.sampleRate} Hz")
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        val upmixed = AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            OUTPUT_CHANNELS,
            C.ENCODING_PCM_16BIT,
        )
        active = true

        // Centre and rear gains become integer multipliers here, once, so the sample loop only ever
        // does long arithmetic on them.
        centreGain = Math.round(CENTRE_LINEAR * GAIN_SCALE)
        rearGain = Math.round(REAR_LINEAR * GAIN_SCALE)
        designLowPass(inputAudioFormat.sampleRate)
        allocateDelay(inputAudioFormat.sampleRate)
        clearState()
        return upmixed
    }

    override fun isActive(): Boolean = active

    /**
     * Upmixes every whole frame in [input] into the reused output buffer.
     *
     * Runs on the audio thread, so once the two scratch buffers have reached their working size
     * this allocates nothing: the delay rings, the byte arrays and the output [ByteBuffer] are all
     * reused, and they only grow when the input itself grows. A trailing partial frame is copied to
     * the front of the carry buffer for the next call rather than dropped.
     */
    override fun queueInput(input: ByteBuffer) {
        check(!inputEnded) { "queueInput() after queueEndOfStream()" }
        check(active) { "queueInput() without a successful configure()" }

        // The input is read as little-endian regardless of what byte order the caller left on the
        // buffer, because that is what the sink delivers and what readSample() decodes.
        input.order(ByteOrder.LITTLE_ENDIAN)
        val incoming = input.remaining()
        if (incoming <= 0) return

        val total = remainder + incoming
        if (inputBytes.size < total) inputBytes = ByteArray(total)
        val array = inputBytes
        input.get(array, remainder, incoming)

        val completeFrames = total / INPUT_FRAME_BYTES
        val completeBytes = completeFrames * INPUT_FRAME_BYTES
        remainder = total - completeBytes

        // Six channels out of two means three times the bytes; the frame count bounds both buffers,
        // so an input that is not a whole number of frames is truncated rather than mis-framed.
        val outSize = completeBytes * (OUTPUT_CHANNELS / 2)
        if (outputBytes.size < outSize) {
            outputBytes = ByteArray(outSize)
            output = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        val out = outputBytes

        var sourceIndex = 0
        var destination = 0
        repeat(completeFrames) {
            val left = readSample(array, sourceIndex)
            val right = readSample(array, sourceIndex + 2)
            val centre = ((left + right).toLong() * centreGain / GAIN_SCALE).toInt() * TRIM_NUM / TRIM_DEN
            val rearSource = ((left - right).toLong() * rearGain / GAIN_SCALE).toInt()

            // Advance the delay line, then read the sample from REAR_DELAY_MS ago and overwrite it.
            ringIndex = (ringIndex + 1) and delayMask
            val readIndex = (ringIndex - delaySamples) and delayMask
            val delayedLeft = rearLeftRing[readIndex]
            val delayedRight = rearRightRing[readIndex]
            rearLeftRing[readIndex] = rearSource
            rearRightRing[readIndex] = -rearSource

            // Subwoofer feed: both channels through their own biquad, then summed.
            val lfe = processLowPass(left, right) * LFE_LINEAR * TRIM_LINEAR
            val lfeSample = if (lfe >= Short.MAX_VALUE) Short.MAX_VALUE.toInt() else lfe.toInt()

            // Media3's 5.1 order is the platform's CHANNEL_OUT_5POINT1 order: front left, front
            // right, front centre, LFE, back left, back right, interleaved. The platform reads the
            // six channels positionally, so front left/right are copied with no gain at all.
            destination = writeSample(out, destination, left)
            destination = writeSample(out, destination, right)
            destination = writeSample(out, destination, clamp(centre))
            destination = writeSample(out, destination, clamp(lfeSample))
            destination = writeSample(out, destination, clamp(delayedLeft))
            destination = writeSample(out, destination, clamp(delayedRight))
            sourceIndex += INPUT_FRAME_BYTES
        }

        // The carry bytes sit at the front of the assembly buffer and `remainder` says how many, so
        // `total` bytes are meaningful here. Everything past the whole frames is the tail and belongs
        // at the front for the next call. The shift runs whenever anything is left over, including
        // when this call completed no frame at all: otherwise the next call would write over the
        // carry instead of behind it. Copying one byte at a time keeps it correct even when a
        // misaligned payload is shorter than the carry already held.
        if (remainder > 0) {
            for (i in 0 until remainder) array[i] = array[completeBytes + i]
        }

        output.position(0)
        output.limit(outSize)
        outputPending = true
    }

    /** No tail to emit: the low-pass and delay lines are held, not flushed, because a click is worse. */
    override fun queueEndOfStream() {
        inputEnded = true
    }

    /**
     * Output for the last [queueInput] call, or an empty buffer once the sink has consumed it.
     *
     * The returned buffer is absolute and independent of its position, so the sink can read it in
     * one go or in pieces without this processor tracking a position.
     */
    override fun getOutput(): ByteBuffer {
        if (!outputPending) return AudioProcessor.EMPTY_BUFFER
        outputPending = false
        return output
    }

    override fun isEnded(): Boolean = inputEnded && !outputPending

    /**
     * Clears everything a seek must not carry across: filter state, delay line, carry remainder.
     *
     * Once the renderer flushes, the samples after the seek point have no continuous relationship
     * with the ones before it, and reusing either the biquad state or the delay ring would splice
     * the two together as an audible click.
     */
    override fun flush() {
        clearState()
        inputEnded = false
    }

    override fun reset() {
        flush()
        active = false
    }

    /**
     * Empties the carry remainder, the filter state, the delay rings and the pending output.
     *
     * The byte arrays and the output view are deliberately kept: their size is a working set, not
     * stream state, and re-allocating them on every flush would put allocations back on the audio
     * thread for no gain.
     */
    private fun clearState() {
        remainder = 0
        outputPending = false
        output.position(0)
        output.limit(0)
        lowPassLeft = 0f
        lowPassLeftPrev = 0f
        lowPassRight = 0f
        lowPassRightPrev = 0f
        rearLeftRing.fill(0)
        rearRightRing.fill(0)
        ringIndex = 0
    }

    /**
     * Design for a second-order Butterworth low-pass, from the Audio EQ Cookbook biquad form.
     *
     * Coefficients come from [sampleRate] every time, because the corner has to stay at 120 Hz
     * rather than at "120 Hz at 48 kHz"; at 44.1 kHz a hardcoded 48 kHz set would land ~9% high.
     * The Butterworth quality factor Q = 1/sqrt(2) gives the maximally flat passband, so the sub
     * carries bass and nothing above it. Stored normalised by a0 in a preallocated array so the
     * audio thread only reads.
     */
    private fun designLowPass(sampleRate: Int) {
        val omega = 2.0 * PI * LFE_CORNER_HZ / sampleRate
        val alpha = sin(omega) / (2.0 * BUTTERWORTH_Q)
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cos(omega)
        val a2 = 1.0 - alpha
        lowPassCoefficients[0] = ((1.0 - cos(omega)) / 2.0 / a0).toFloat()
        lowPassCoefficients[1] = ((1.0 - cos(omega)) / a0).toFloat()
        lowPassCoefficients[2] = lowPassCoefficients[0]
        lowPassCoefficients[3] = (a1 / a0).toFloat()
        lowPassCoefficients[4] = (a2 / a0).toFloat()
    }

    /**
     * Sizes the rear delay rings for [sampleRate] and masks them to a power of two.
     *
     * Only allocates when the required size changes, so a reconfigure at the same sample rate keeps
     * the arrays it already has; [clearState] zeroes them in place. The ring is sized at twice the
     * delay so the masked read index is always behind the write index without wrapping onto it.
     */
    private fun allocateDelay(sampleRate: Int) {
        delaySamples = Math.round(sampleRate * REAR_DELAY_MS / 1000f)
        var size = 1
        while (size < delaySamples * 2) size = size shl 1
        delayMask = size - 1
        if (rearLeftRing.size != size) {
            rearLeftRing = IntArray(size)
            rearRightRing = IntArray(size)
        }
    }

    /**
     * One pass of the LFE low-pass over both channels, summed on the way out.
     *
     * Transposed direct form II, which needs one state variable per channel instead of two and does
     * not push the coefficient quantisation noise of direct form I's delay line into the audio.
     */
    private fun processLowPass(left: Int, right: Int): Float {
        val c = lowPassCoefficients
        val leftOut = c[0] * left + lowPassLeft
        lowPassLeft = c[1] * left - c[3] * leftOut + lowPassLeftPrev
        lowPassLeftPrev = c[2] * left - c[4] * leftOut
        val rightOut = c[0] * right + lowPassRight
        lowPassRight = c[1] * right - c[3] * rightOut + lowPassRightPrev
        lowPassRightPrev = c[2] * right - c[4] * rightOut
        return leftOut + rightOut
    }

    /** Little-endian 16-bit read; explicit so the loop never depends on the buffer's byte order. */
    private fun readSample(bytes: ByteArray, index: Int): Int {
        val raw = (bytes[index].toInt() and 0xFF) or (bytes[index + 1].toInt() shl 8)
        return raw.toShort().toInt()
    }

    /** Little-endian 16-bit write, clamped first because `Short` trims silently. */
    private fun writeSample(bytes: ByteArray, index: Int, sample: Int): Int {
        val clamped = clamp(sample)
        bytes[index] = (clamped and 0xFF).toByte()
        bytes[index + 1] = ((clamped shr 8) and 0xFF).toByte()
        return index + 2
    }

    /** Keeps a summed sample inside 16-bit range; the clipping this prevents is the last resort. */
    private fun clamp(sample: Int): Int = when {
        sample > Short.MAX_VALUE -> Short.MAX_VALUE.toInt()
        sample < Short.MIN_VALUE -> Short.MIN_VALUE.toInt()
        else -> sample
    }

    private companion object {
        const val TAG = "StereoUpmix"

        /** Only the two rates the audio sink actually runs at; 96 kHz is left as pass-through. */
        const val SR_44_1 = 44_100

        /** 48 kHz: the sink's preferred rate and what the spatialiser is configured for. */
        const val SR_48 = 48_000

        /** 5.1: front left, front right, front centre, LFE, back left, back right. */
        const val OUTPUT_CHANNELS = 6

        /** Stereo 16-bit: two channels x two bytes. Every frame must be a multiple of this. */
        const val INPUT_FRAME_BYTES = 4

        /** Initial carry/assembly buffer, about 23 ms of stereo at 48 kHz. */
        const val INITIAL_INPUT_BYTES = 4096

        /** Fixed-point denominator for the centre and rear gains: 2^16. */
        const val GAIN_SCALE = 1 shl 16

        /**
         * -9.03 dB: `(L + R) / 2` at -3 dB, in one multiplier. Halving the sum is what keeps the
         * centre the same distance below each front channel that a real 5.1 mix would place it.
         */
        const val CENTRE_LINEAR = 0.35355f

        /** -6.0 dB: the difference signal, quiet enough to read as ambience rather than as a copy. */
        const val REAR_LINEAR = 0.50119f

        /** -6.0 dB: subwoofer feed behind the low-pass, conservative on purpose - subs overshoot easily. */
        const val LFE_LINEAR = 0.50119f

        /** -0.915 dB (x0.9): a small overall trim so summed centre/rear content stays in range. */
        const val TRIM_LINEAR = 0.9f

        /** The same trim as an exact integer ratio, so the centre channel needs no float at all. */
        const val TRIM_NUM = 9

        /** Denominator of [TRIM_NUM]; 9/10 is exactly the 0.9f above. */
        const val TRIM_DEN = 10

        /** 120 Hz: the standard subwoofer crossover, and where the LFE low-pass corner sits. */
        const val LFE_CORNER_HZ = 120.0

        /** Q = 1/sqrt(2): the Butterworth value, giving the flattest possible passband. */
        const val BUTTERWORTH_Q = 0.70710678

        /** ~12 ms: enough decorrelation for the rears to read as space, cheap enough to be free. */
        const val REAR_DELAY_MS = 12f
    }
}
