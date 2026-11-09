package com.gan.spatialplayer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.gan.spatialplayer.media.PlayerEngine

/**
 * Debug-only: what the *player* thinks is happening. **Not present in release builds.**
 *
 * The decode probe answers "can the decoder decode this file" - and for the reference film it can.
 * This answers the next question, which only Media3 can: was the video track selected at all, what
 * does the renderer say it is doing, and what surface is it drawing into.
 *
 * ```
 * adb shell am start -n <pkg>/com.gan.spatialplayer.TrackProbeActivity -d <content-uri>
 * ```
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackProbeActivity : AppCompatActivity() {

    private lateinit var engine: PlayerEngine
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            line("result", "no uri")
            finish()
            return
        }

        engine = PlayerEngine(this, null)
        engine.build()
        if (intent.getBooleanExtra("track_probe_mute", false)) engine.player?.volume = 0f

        // A real SurfaceView, because a video renderer given no output does not report a failure -
        // it simply never enables, and the probe would see nothing and conclude nothing.
        val surface = android.view.SurfaceView(this)
        setContentView(surface)

        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                line("videoSize", "${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio}")
            }

            override fun onTracksChanged(tracks: Tracks) {
                line("tracks", "changed")
                tracks.groups.forEachIndexed { gi, group ->
                    line(
                        "group",
                        "#$gi type=${typeName(group.type)} selected=${group.isSelected} " +
                            "supported=${group.isSupported}",
                    )
                    for (ti in 0 until group.length) {
                        if (!group.isTrackSupported(ti)) continue
                        line("track", "#$gi.$ti ${describe(group.getTrackFormat(ti))}")
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                line("state", stateName(state))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = line("isPlaying", "$isPlaying")

            override fun onPlayerError(error: PlaybackException) {
                line("error", "${error.errorCodeName}: ${error.message}")
                if (error.cause != null) line("errorCause", "${error.cause}")
            }

            override fun onRenderedFirstFrame() = line("renderedFirstFrame", "true")

            private fun typeName(type: Int) = when (type) {
                C.TRACK_TYPE_VIDEO -> "video"
                C.TRACK_TYPE_AUDIO -> "audio"
                C.TRACK_TYPE_TEXT -> "text"
                else -> "other($type)"
            }
        }

        engine.playerForProbe()?.apply {
            addListener(listener)
            setVideoSurfaceView(surface)
        }

        engine.setMedia(uri, intent.type)
        engine.prepare()
        engine.play()
        line("setup", "prepared")
        line("profile", engine.decoderProfile.name)

        val seekMs = intent.getLongExtra("track_probe_seek_ms", -1L)
        if (seekMs >= 0L) schedule(3_500) {
            engine.player?.seekTo(seekMs)
            line("seekRequested", seekMs.toString())
        }
        schedule(2_000) { snapshot("t+2s") }
        schedule(6_000) { snapshot("t+6s") }
        schedule(7_000) {
            line("result", "done")
            finish()
        }
    }

    private fun snapshot(whenLabel: String) {
        val player = engine.playerForProbe() ?: return
        line(
            "snapshot",
            "$whenLabel state=${stateName(player.playbackState)} " +
                "playing=${player.isPlaying} position=${player.currentPosition} " +
                "buffered=${player.bufferedPosition} duration=${player.duration} " +
                "videoSize=${player.videoSize.width}x${player.videoSize.height}",
        )
        line(
            "media",
            "item=${player.currentMediaItem?.localConfiguration?.uri} " +
                "mediaItemCount=${player.mediaItemCount} " +
                "commands=${player.availableCommands.size()} " +
                "canPrepare=${player.isCommandAvailable(Player.COMMAND_PREPARE)} " +
                "canPlay=${player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)} " +
                "playWhenReady=${player.playWhenReady} " +
                "sameAsEngine=${player === engine.playerForProbe()}",
        )
        line(
            "trackParams",
            "disabledVideo=" +
                player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) +
                " disabledAudio=" +
                player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO),
        )
    }

    private fun describe(format: Format): String = buildString {
        append(format.sampleMimeType)
        append(" codecs=").append(format.codecs)
        append(" ").append(format.width).append("x").append(format.height)
        append(" bitrate=").append(format.bitrate)
        append(" frameRate=").append(format.frameRate)
        append(" colorInfo=").append(format.colorInfo)
        append(" hdr=").append(format.colorInfo?.colorTransfer)
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        handler.postDelayed(block, delayMs)
    }

    private fun line(key: String, value: String) {
        Log.i(TAG, "track probe :: $key :: $value")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::engine.isInitialized) engine.release()
        line("released", "true")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TrackProbe"
    }
}
