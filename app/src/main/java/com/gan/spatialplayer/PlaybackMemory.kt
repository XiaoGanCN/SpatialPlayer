package com.gan.spatialplayer

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Where each item was left off, for the lifetime of the process.
 *
 * Deliberately **not** persisted to disk. The requirement is to remember the last played position
 * until the app quits, which is a convenience within a session - a film put down for the evening and
 * picked up again tomorrow should start at the beginning unless the user asks otherwise, and
 * silently resuming something watched days ago is startling.
 *
 * A `ConcurrentHashMap` rather than a plain map because the position is written from the activity's
 * lifecycle callbacks while a `PixelCopy` or refresh coroutine may still be reading.
 */
object PlaybackMemory {

    private val positions = ConcurrentHashMap<String, Long>()

    /** Records a position, ignoring anything at or below zero. */
    fun remember(uri: Uri, positionMs: Long) {
        if (positionMs <= 0L) return
        positions[uri.toString()] = positionMs
    }

    /** The remembered position, or 0 when there is none. */
    fun positionFor(uri: Uri): Long = positions[uri.toString()] ?: 0L

    /** Drops an entry, used when an item is finished rather than abandoned part-way. */
    fun forget(uri: Uri) {
        positions.remove(uri.toString())
    }

    /** Clears everything. Exposed so a caller can be explicit rather than relying on process death. */
    fun clear() {
        positions.clear()
    }

    /** Number of remembered items; used by tests to prove the memory holds anything. */
    val size: Int get() = positions.size
}
