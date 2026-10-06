package com.gan.spatialplayer

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.gan.spatialplayer.media.AudioOutputCapability

/**
 * Reports what the connected output will actually accept.
 *
 * This is the check that guards the failing case: a 7.1 Atmos remux opened a raw 8-channel PCM
 * `AudioTrack` and the sink refused it ("AudioTrack init failed"), which failed the whole film over
 * Bluetooth. The guard is only as good as its capability reading, so that reading is measured here.
 */
class SmokeAudioOutputProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val advertised = AudioOutputCapability.maxChannels(this)
        val verified = AudioOutputCapability.verifiedMaxChannels(this)
        val can8 = AudioOutputCapability.canOpenTrack(8)
        val can6 = AudioOutputCapability.canOpenTrack(6)
        val can2 = AudioOutputCapability.canOpenTrack(2)

        Log.i(TAG, "describe :: ${AudioOutputCapability.describe(this)}")
        Log.i(TAG, "advertisedMaxChannels :: $advertised")
        Log.i(TAG, "verifiedMaxChannels :: $verified")
        Log.i(TAG, "canOpen 8ch=$can8 6ch=$can6 2ch=$can2")
        Log.i(
            TAG,
            "verdict :: " + when {
                verified >= 8 -> "output can take 7.1"
                verified >= 6 -> "output takes 5.1; 7.1 must be downmixed"
                else -> "output is stereo only; everything must be downmixed"
            },
        )
        finish()
    }

    companion object {
        const val TAG = "AudioOutputProbe"
    }
}
