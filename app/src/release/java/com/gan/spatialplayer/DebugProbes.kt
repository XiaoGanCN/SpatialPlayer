package com.gan.spatialplayer

import android.content.BroadcastReceiver
import android.content.Context

/** Variant-specific hooks for the device verification harnesses. */
internal object DebugProbes {
    fun registerLibrary(context: Context): BroadcastReceiver? = null
    fun registerPlayer(context: Context): BroadcastReceiver? = null
}
