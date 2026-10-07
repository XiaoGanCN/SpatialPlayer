package com.gan.spatialplayer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.net.Uri
import com.gan.spatialplayer.media.PlayerEngine
import java.io.File

/**
 * Exercises the spatial on/off switch and reports what the platform did with it.
 *
 * The audio panel's toggle only calls [com.gan.spatialplayer.media.PlayerEngine.setSpatialAudioEnabled],
 * so driving that method directly answers whether the switch has any effect - without needing to
 * screenshot a dialog window, which cannot be captured because it is blurred by the window manager.
 */
class SmokeSpatialToggleProbeActivity : Activity(), PlayerEngine.Listener {

    private lateinit var engine: PlayerEngine
    private val handler = Handler(Looper.getMainLooper())
    private var samples = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = PlayerEngine(this, this)
        engine.build()

        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "missing :: $path")
            return finish()
        }
        engine.setMedia(Uri.fromFile(file), "video/x-matroska")
        engine.prepare()
        engine.play()

        // Spatialization is decided by the audio sink once it is running, so measure after playback
        // has actually started rather than immediately.
        handler.postDelayed({ report("on") }, SETTLE_MS)
        handler.postDelayed({
            Log.i(TAG, "toggling spatial OFF")
            engine.setSpatialAudioEnabled(false)
        }, SETTLE_MS * 2)
        handler.postDelayed({ report("off"); finish() }, SETTLE_MS * 3)
    }

    private fun report(label: String) {
        val p = engine.player ?: return
        Log.i(
            TAG,
            "state[$label] :: playing=${p.isPlaying} spatialEnabled=" +
                "${engine.spatialAudioEnabled} audio=${p.audioFormat?.sampleMimeType} " +
                "channels=${p.audioFormat?.channelCount}",
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.release()
        super.onDestroy()
    }

    override fun onEnginePlaybackState(state: Int) = Unit
    override fun onEngineIsPlaying(playing: Boolean) = Unit
    override fun onEngineTracksChanged(tracks: androidx.media3.common.Tracks) = Unit
    override fun onEngineVideoSize(size: androidx.media3.common.VideoSize) = Unit
    override fun onEngineError(message: String, cause: Throwable?) {
        Log.w(TAG, "engine error :: $message")
    }
    override fun onEngineFirstFrame() = Unit
    override fun onEnginePlaybackParameters(speed: Float) = Unit
    override fun onEngineAudioDownmixed(from: Int, to: Int) = Unit

    companion object {
        const val TAG = "SpatialToggleProbe"
        const val EXTRA_PATH = "toggle_probe_path"
        private const val SETTLE_MS = 6000L
    }
}
