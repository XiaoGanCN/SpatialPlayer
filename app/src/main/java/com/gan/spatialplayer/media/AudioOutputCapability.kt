package com.gan.spatialplayer.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * How many channels the current output can actually accept.
 *
 * This exists because of a real failure: a 7.1 Dolby Atmos remux produced
 *
 * ```
 * audio sink: AudioTrack init failed 0 Config(48000, 6396, 4, 9216000)
 *   Format(2, Dolby Atmos 7.1, audio/raw, [8, 48000])
 * ```
 *
 * The decoder was fine. The *sink* refused to open eight channels of raw PCM, and no amount of
 * decoder switching can fix that - the guard has to happen before track selection, by capping the
 * channel count so the selector picks a layout the output can carry (or lets Media3 downmix).
 *
 * The failure is easy to hit in practice: over Bluetooth A2DP the device advertises only
 * `channel masks: 0x0001, 0x0003` - mono and stereo - so any 7.1 source fails until it is capped.
 * Media3 cannot infer this: `AudioCapabilities.getMaxChannelCount()` is a hidden API and is not part
 * of the public surface, so the capabilities are read from the platform directly here.
 */
object AudioOutputCapability {

    /** Channels assumed when the platform will not tell us; stereo is the safe answer. */
    const val SAFE_CHANNELS = 2

    /** Highest channel count Android will decode to. 8 admits 7.1. */
    const val MAX_CHANNELS = 8

    /**
     * Channel count to allow for the output in use right now.
     *
     * Bluetooth is special-cased to stereo rather than trusting the reported profile, because the
     * negotiated codec can change after the device is queried - and a 7.1 sink attempt over A2DP is
     * exactly the failure this prevents.
     */
    fun maxChannels(context: Context): Int {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return SAFE_CHANNELS

        val outputs: List<AudioDeviceInfo> =
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }
                .getOrNull()
                ?: emptyList()
        if (outputs.isEmpty()) return SAFE_CHANNELS

        // Prefer a device that is actually in use.
        val active = outputs.filter { isWiredOrWireless(it.type) }
        val candidates = active.ifEmpty { outputs }

        var best = 0
        var sawBluetooth = false
        for (device in candidates) {
            if (isBluetooth(device.type)) {
                sawBluetooth = true
                // A2DP cannot carry more than stereo once it is encoded, whatever it reports.
                continue
            }
            val counts: IntArray = runCatching { device.channelCounts }.getOrNull() ?: IntArray(0)
            for (count in counts) {
                if (count > best && count <= MAX_CHANNELS) best = count
            }
        }

        if (best > 0) return best
        // Only Bluetooth is attached, or nothing usable was reported.
        return if (sawBluetooth) SAFE_CHANNELS else SAFE_CHANNELS
    }

    /**
     * Whether a 7.1 (8 channel) track can be sent to the sink as-is.
     *
     * Exposed so the UI can explain why a multichannel track is being downmixed instead of leaving
     * the user to wonder why their Atmos disc sounds like stereo.
     */
    fun canCarry7Point1(context: Context): Boolean = maxChannels(context) >= 8

    /**
     * Builds an [AudioTrack] to prove a channel count works, then releases it immediately.
     *
     * Used when the platform reports the capability but the sink still refuses, which is what
     * `AudioTrack init failed` means. Cheap: constructing and releasing a track does not start audio.
     */
    fun canOpenTrack(channelCount: Int, sampleRate: Int = 48_000): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val mask = channelMaskFor(channelCount) ?: return false
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                mask,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return false
            val track = AudioTrack.Builder()
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val state = track.state
            track.release()
            state == AudioTrack.STATE_INITIALIZED
        } catch (_: Exception) {
            // Includes UnsupportedOperationException on devices that cap channel counts.
            false
        }
    }

    /**
     * Channel count the sink will actually accept, verified by opening a track.
     *
     * Falls back through 8, 6 and 2 so a caller always gets something usable.
     */
    fun verifiedMaxChannels(context: Context, sampleRate: Int = 48_000): Int {
        val advertised = maxChannels(context)
        // Try the advertised value first, then step down.
        for (candidate in intArrayOf(advertised, 6, 2)) {
            if (candidate == 2) return 2
            if (canOpenTrack(candidate, sampleRate)) return candidate
        }
        return SAFE_CHANNELS
    }

    /** Platform channel mask for a discrete channel count, or null when there is no exact match. */
    private fun channelMaskFor(channelCount: Int): Int? = when (channelCount) {
        1 -> android.media.AudioFormat.CHANNEL_OUT_MONO
        2 -> android.media.AudioFormat.CHANNEL_OUT_STEREO
        6 -> android.media.AudioFormat.CHANNEL_OUT_5POINT1
        8 -> android.media.AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> null
    }

    private fun isBluetooth(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> true
        else -> false
    }

    private fun isWiredOrWireless(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> true
        else -> false
    }

    /** One-line description for the UI, e.g. "stereo over Bluetooth". */
    fun describe(context: Context): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputs: List<AudioDeviceInfo> =
            runCatching { manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }
                .getOrNull()
                ?: emptyList()
        val name = outputs.firstOrNull { isWiredOrWireless(it.type) }?.productName?.toString()
        val channels = maxChannels(context)
        return buildString {
            append(
                when {
                    channels >= 8 -> "7.1 capable"
                    channels >= 6 -> "5.1 capable"
                    else -> "stereo only"
                },
            )
            if (name != null) append(" · $name")
        }
    }
}
