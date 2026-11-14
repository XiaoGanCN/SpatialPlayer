package org.spatialplayer.ui

import android.os.Build
import android.view.View
import android.view.Window

/** A UI vote, not a display-mode lock. The system retains thermal/power and HDR mode policy. */
object HighRefreshRate {
    fun apply(window: Window) {
        val display=window.decorView.display ?: window.context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        val current=display.mode
        val rate=display.supportedModes.filter {
            it.physicalWidth==current.physicalWidth && it.physicalHeight==current.physicalHeight
        }.maxOfOrNull {it.refreshRate.coerceAtMost(120f)} ?: 60f
        if(window.attributes.preferredRefreshRate!=rate)window.attributes=window.attributes.apply {preferredRefreshRate=rate}
        if(Build.VERSION.SDK_INT>=35)window.decorView.requestedFrameRate=View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
    }
}
