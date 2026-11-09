package com.gan.spatialplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

/** Variant-specific hooks for the device verification harnesses. */
internal object DebugProbes {
    fun registerLibrary(context: Context): BroadcastReceiver = LibraryProbeReceiver().also {
        context.registerReceiver(it, IntentFilter(LibraryProbeReceiver.ACTION).apply { addAction(UiStateProbe.LIBRARY) }, Context.RECEIVER_EXPORTED)
    }

    fun registerPlayer(context: Context): BroadcastReceiver = PanelProbeReceiver().also {
        val filter = IntentFilter().apply {
            addAction(PanelProbeReceiver.ACTION)
            addAction(UiStateProbe.PLAYER)
            addAction(PanelProbeReceiver.ACTION_CHROME_TOUCH)
            addAction(PanelProbeReceiver.ACTION_GEOMETRY)
            addAction(PanelProbeReceiver.ACTION_GLASS_PRESS)
            addAction(PanelProbeReceiver.ACTION_GLASS_CAPTURE)
        }
        context.registerReceiver(it, filter, Context.RECEIVER_EXPORTED)
    }
}
