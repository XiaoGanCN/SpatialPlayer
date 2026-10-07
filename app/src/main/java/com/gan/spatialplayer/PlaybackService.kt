package com.gan.spatialplayer

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.gan.spatialplayer.media.PlaybackEngine

/**
 * Publishes the player to the system, which is what puts it in the media centre and in the
 * notification shade.
 *
 * ## Why this is a service at all
 *
 * A media session on its own is enough to be *listed*; it is not enough to keep playing. Android
 * treats a backgrounded app as killable, and an app that wants to keep producing sound has to say so
 * with a foreground service - which is exactly what [MediaSessionService] is. It also brings the
 * notification, the transport controls on a lock screen or headset, and the wake lock that keeps
 * audio running once the screen is off.
 *
 * The service does **not** own the player. `PlaybackEngine` holds it, because the activity also
 * drives it directly for chapters, track selection and the spatial controls, and routing all of that
 * through a `MediaController` would be a much larger change for no behavioural gain in a
 * single-process app. This service's job is the session and the notification.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // No notification for a player that is merely sitting there paused. It appears when there is
        // sound, which is what makes it useful rather than permanent clutter.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        attach()
    }

    /**
     * Points the session at the engine's current player.
     *
     * Called again whenever the engine rebuilds, because a rebuild creates a *new* `ExoPlayer` - the
     * spatial toggle and any decoder-profile change go through it - and a session still holding the
     * old one would show transport controls wired to a dead player.
     */
    private fun attach() {
        val engine = PlaybackEngine.acquire(this)
        val player = engine.player ?: return
        // One session for the service's lifetime, re-pointed at each new player rather than replaced.
        // Replacing it released a session and added another, and the notification from the released
        // one could outlive it - which is how the media centre came to show a track that had finished
        // while a different one was playing.
        session?.let { existing ->
            val moved = runCatching { existing.setPlayer(player) }
            if (moved.isSuccess) {
                engine.onPlayerChanged = { attach() }
                return
            }
            Log.w(TAG, "could not re-point the session; rebuilding it", moved.exceptionOrNull())
            runCatching { removeSession(existing) }
            existing.release()
            session = null
        }

        val built = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer())
            // A finished item ignores play(): the position is already at the end, so the button does
            // nothing at all. Rewinding first is what makes play work again on the notification and
            // the lock screen, and mirrors what PlayerEngine.play() does for the in-app button.
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    if (playerCommand == Player.COMMAND_PLAY_PAUSE) {
                        val current = session.player
                        if (current.playbackState == Player.STATE_ENDED) {
                            current.seekTo(0)
                        }
                    }
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }
            })
            .build()
        // A session that is only returned from onGetSession is not necessarily *added* to the
        // service, and `MediaNotificationManager.shouldShowNotification` asks `isSessionAdded` before
        // it will post anything - as well as requiring a connected controller whose timeline is not
        // empty. Without this the session worked (the media centre listed it) while no notification
        // was ever created.
        runCatching { addSession(built) }
            .onFailure { Log.w(TAG, "could not add the session", it) }
        session = built
        engine.onPlayerChanged = { attach() }
    }

    /** Tapping the notification returns to the player, not merely to the app. */
    private fun openPlayer(): PendingIntent {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val engine = PlaybackEngine.peek()
            val uri = engine?.currentMediaUri
            if (uri != null) {
                setDataAndType(uri, engine.currentMediaMime)
            }
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the app off the recents list stops audio, as it does in every other player; leaving it
     * alone while something is playing would be the surprising behaviour.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.isPlaying != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    private companion object {
        const val TAG = "PlaybackService"
    }

    override fun onDestroy() {
        PlaybackEngine.peek()?.onPlayerChanged = null
        session?.release()
        session = null
        // Frees the engine unless it is still producing sound for someone else.
        PlaybackEngine.releaseIfIdle()
        super.onDestroy()
    }
}
