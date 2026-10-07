package com.gan.spatialplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Debug-only entry point for the panel probe. **Not present in release builds.**
 *
 * The release manifest keeps [PlayerActivity] unexported, so `adb shell am start` cannot drive it
 * directly - the same reason [SmokeTestActivity] exists. This shim fires
 * [PanelProbeReceiver.ACTION] at the player that is already on screen and then gets out of the way,
 * so `tools/verify-panels.sh` never has to put anything in front of the sheet it is measuring.
 *
 * Start it *after* the player is up:
 * `am start -n com.gan.spatialplayer.debug/com.gan.spatialplayer.PanelProbeActivity`
 */
class PanelProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent(PanelProbeReceiver.ACTION).setPackage(packageName))
        finish()
    }
}
