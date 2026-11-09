package com.gan.spatialplayer.media

import android.content.Context

/**
 * The one player this process has.
 *
 * Background audio needs the player to outlive the screen that started it: the activity can be
 * stopped, and even destroyed, while the music keeps going and the notification stays up. That means
 * something other than the activity has to hold it, and since both the activity and the media
 * session live in this process, a holder here is enough - no `MediaController` indirection, and the
 * activity keeps talking to the engine directly for chapters, tracks and the spatial controls.
 *
 * ## Lifetime
 *
 * Release requires the same engine that requested cleanup, no screen listener, and no active or
 * buffering playback. Old activity/service teardown cannot release a newly opened player.
 */
object PlaybackEngine {

    @Volatile
    private var instance: PlayerEngine? = null

    /**
     * The engine, created on first use.
     *
     * [listener] is applied **every** time, not only when the engine is created. A screen that
     * reopens onto an engine which is already playing - the notification case, or simply reopening
     * the app while music runs - must get its callbacks back, and it would otherwise attach to an
     * engine whose listener had been cleared by the screen that came before it, leaving the UI frozen
     * on stale state with no errors anywhere.
     */
    @Synchronized
    fun acquire(context: Context, listener: PlayerEngine.Listener? = null): PlayerEngine {
        val engine = instance ?: PlayerEngine(context.applicationContext, listener).also {
            instance = it
        }
        if (listener != null) engine.listener = listener
        return engine
    }

    /** The engine if one exists, without creating it. */
    fun peek(): PlayerEngine? = instance

    /**
     * Frees [expected] only if it is still current and has no screen or pending playback.
     */
    @Synchronized
    fun releaseIfIdle(expected: PlayerEngine? = instance) {
        val engine = instance ?: return
        if (engine !== expected) return
        // An old service can stop after another screen has acquired a fresh player. Paused or
        // buffering is not idle while a screen owns it, and playWhenReady covers startup buffering.
        val player = engine.player
        val awaitingPlayback = player?.playWhenReady == true &&
            (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING ||
                player.playbackState == androidx.media3.common.Player.STATE_READY)
        if (engine.listener != null || player?.isPlaying == true || awaitingPlayback) return
        engine.release()
        instance = null
    }
}
