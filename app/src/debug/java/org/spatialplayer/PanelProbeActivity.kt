package org.spatialplayer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Debug-only entry point for the inspector-sheet probes. **Not present in release builds.**
 *
 * The release manifest keeps [PlayerActivity] unexported, so `adb shell am start` cannot drive it
 * directly - the same reason [SmokeTestActivity] exists. This shim fires a probe at the player that
 * is already on screen and then gets out of the way, so a suite never has to put anything in front of
 * what it is measuring.
 *
 * Which probe is chosen with `--es probe <name>`:
 *
 * | name | action | what it reports |
 * |---|---|---|
 * | `panel` | [PanelProbeReceiver.ACTION] | the rows each inspector panel renders |
 * | `chrome` | [PanelProbeReceiver.ACTION_CHROME_TOUCH] | the chrome's touch routing, shown and timed out |
 * | `geometry` | [PanelProbeReceiver.ACTION_GEOMETRY] | the picture rect the wash is drawing with |
 * | `press` | [PanelProbeReceiver.ACTION_GLASS_PRESS] | holds the capsule in a press for 3 s |
 * | `library` | [LibraryProbeReceiver.ACTION] | the running library's Poweramp fold across a refresh |
 *
 * The choice matters: all of them report through the same log tag, so firing more than one and then
 * grepping for "the last line" reads whichever happened to land last - which is how `verify-panels.sh`
 * began failing against a geometry line that had nothing to do with panels. Omitting the extra fires
 * all three, which is only useful when looking at the device by hand. `library` reports on the
 * `LibraryProbe` tag instead, but the same discipline applies.
 */
class PanelProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = intent.getStringExtra(EXTRA_PROBE)
        if (wanted == "settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        if (wanted.isNullOrBlank()) {
            fire(PanelProbeReceiver.ACTION)
            fire(PanelProbeReceiver.ACTION_CHROME_TOUCH)
            fire(PanelProbeReceiver.ACTION_GEOMETRY)
        } else {
            fire(
                when (wanted) {
                    PROBE_CHROME -> PanelProbeReceiver.ACTION_CHROME_TOUCH
                    PROBE_GEOMETRY -> PanelProbeReceiver.ACTION_GEOMETRY
                    PROBE_PRESS -> PanelProbeReceiver.ACTION_GLASS_PRESS
                    PROBE_LIBRARY -> LibraryProbeReceiver.ACTION
                    else -> PanelProbeReceiver.ACTION
                },
            )
        }
        finish()
    }

    private fun fire(action: String) {
        // The receiver is registered dynamically, for the actions it names, so this is the one place
        // that has to be kept in step with that filter rather than two.
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    companion object {
        const val EXTRA_PROBE = "probe"
        const val PROBE_PANEL = "panel"
        const val PROBE_CHROME = "chrome"
        const val PROBE_GEOMETRY = "geometry"
        const val PROBE_PRESS = "press"
        const val PROBE_LIBRARY = "library"
    }
}
