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
 * The rule is deliberately boring: the engine is released only when nothing is playing. A screen
 * going away calls [releaseIfIdle], which is a no-op while audio is coming out of the speaker, so
 * ordinary cleanup can be written without having to ask whether background playback is in progress.
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
     * Frees the engine unless audio is still playing.
     *
     * The activity calls this on the way out, and so does the service: whichever happens last, the
     * engine survives exactly as long as it is producing sound.
     */
    @Synchronized
    fun releaseIfIdle() {
        val engine = instance ?: return
        if (engine.player?.isPlaying == true) return
        engine.release()
        instance = null
    }
}
