package com.gan.spatialplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Spatializer
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Debug-only control experiment for spatial audio. **Not in release builds.**
 *
 * Plays raw 5.1 PCM through a bare [AudioTrack] carrying the same spatialisation attributes the
 * player uses. This separates two possibilities that are otherwise indistinguishable from the
 * outside:
 *
 *  * the player's audio path is wrong, or
 *  * the device does not spatialise an app-supplied 5.1 track on this route at all.
 *
 * If a bare AudioTrack with these attributes is also reported `isSpatialized=false`, the app is
 * not the problem and the comparison should move to the platform or the output device.
 */
class SmokeSpatialProbeActivity : AppCompatActivity() {

    private var track: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeSpatializer()
        startRawTrack()
    }

    private fun probeSpatializer() {
        val am = getSystemService(AudioManager::class.java)
        val sp: Spatializer? = runCatching { am?.spatializer }.getOrNull()
        if (sp == null) {
            Log.i(TAG, "spatializer :: unavailable")
            return
        }
        Log.i(
            TAG,
            "spatializer :: available=${sp.isAvailable} enabled=${sp.isEnabled} " +
                "headTracker=${sp.isHeadTrackerAvailable} immersive=${sp.immersiveAudioLevel}",
        )

        // Ask the same question the player's content implies, for several layouts.
        for ((label, mask) in listOf(
            "stereo" to AudioFormat.CHANNEL_OUT_STEREO,
            "5.1" to AudioFormat.CHANNEL_OUT_5POINT1,
        )) {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(mask)
                .build()
            val attributes = buildAttributes()
            val can = runCatching { sp.canBeSpatialized(attributes, format) }.getOrDefault(false)
            Log.i(TAG, "canBeSpatialized :: $label=$can")
        }
    }

    /** Exactly the spatialisation attributes the player sets on its ExoPlayer instance. */
    private fun buildAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setSpatializationBehavior(
                        AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO,
                    )
                }
            }
            .build()

    private fun startRawTrack() {
        val stereo = intent.getBooleanExtra(EXTRA_STEREO, false)
        startTrack(
            if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_5POINT1,
            if (stereo) 2 else 6,
            if (stereo) "stereo" else "5.1",
        )
    }

    private fun startTrack(channelMask: Int, channels: Int, label: String) {
        val sampleRate = 48_000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "rawtrack :: getMinBufferSize failed for $label ($minBuffer)")
            return
        }

        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(buildAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            Log.e(TAG, "rawtrack :: construction failed: ${it.message}")
            return
        }

        track = created
        created.play()

        // Feed a second of silence; enough for the platform to latch a format for the track.
        val frames = sampleRate
        val buffer = ShortArray(frames * channels)
        var written = 0
        while (written < buffer.size) {
            val n = created.write(buffer, written, buffer.size - written)
            if (n <= 0) break
            written += n
        }
        Log.i(TAG, "rawtrack :: $label session=${created.audioSessionId} state=${created.state} written=$written")
    }

    override fun onDestroy() {
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "SpatialPlayerSpatial"
        const val EXTRA_STEREO = "spatial_probe_stereo"
    }
}
