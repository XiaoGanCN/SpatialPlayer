package com.gan.spatialplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gan.spatialplayer.ui.InspectorSheet

/**
 * Debug-only. **Not registered outside the debug variant** - see `PlayerActivity.onResume`.
 *
 * Opens every inspector panel on the *running* player and logs what each one rendered, so
 * `tools/verify-panels.sh` can assert it over adb.
 *
 * ## Why this exists
 *
 * Neither of the usual ways to look at this screen works. The player animates continuously, so
 * `uiautomator dump` refuses to settle on it - measured, "ERROR: could not get idle state" - and the
 * sheet's rows are built in code inside a separate dialog window, so a screenshot can only say
 * "something is or is not in the lower two-thirds of the screen".
 *
 * It drives the activity instance that is really on screen rather than building a sheet of its own,
 * because the reported bug was in the *wiring* between the sheet and the activity: a probe with its
 * own callback would test a copy of the code that was wrong.
 *
 * ## Why a broadcast, and why the result is a log line
 *
 * A broadcast can be aimed at a *running* activity, which a probe activity cannot - and an ordered
 * broadcast result has to be set before `onReceive` returns, while the panels can only be measured
 * after the next frame. So the work is started by one broadcast and narrated to the log, the same
 * shape `SmokeChaptersProbeActivity` uses. `PanelProbeActivity` is the debug entry point that fires
 * it, because the release manifest keeps `PlayerActivity` unexported.
 */
class PanelProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        trigger()
    }

    companion object {
        private const val TAG = "PanelProbe"

        const val ACTION = "com.gan.spatialplayer.debug.PANEL_PROBE"

        /**
         * Every panel whose rows the activity supplies, with the label its tab shows.
         *
         * Inspection and Metadata are deliberately absent: they are read-only reports rendered from
         * `onReadoutRequested` rather than from a supplied list, so "has rows" does not apply to them.
         */
        private val PROBED = listOf(
            InspectorSheet.Panel.SPEED to R.string.speed,
            InspectorSheet.Panel.DECODER to R.string.decoder_settings,
            InspectorSheet.Panel.SUBTITLES to R.string.subtitle_settings,
            InspectorSheet.Panel.AUDIO to R.string.audio_settings,
            InspectorSheet.Panel.SCALING to R.string.aspect_settings,
            InspectorSheet.Panel.CHAPTERS to R.string.chapters,
        )

        /**
         * Opens each panel in turn, between frames, and logs what it rendered.
         *
         * The first panel goes through the activity's own entry point - the same call the overflow
         * sheet's row and the long-presses make. Every one after it is a plain `show(panel)`, which is
         * all the tab strip's click listener does. The sheet is left open across the switches on
         * purpose: that is the reported route, "opened from Scaling, then switched to Speed".
         */
        fun trigger() {
            val activity = PlayerActivity.instance
            if (activity == null) {
                report("error=no_activity")
                return
            }
            // Measured inline, not from a `post`. `show` renders synchronously, so the rows are in
            // the container by the time it returns - and a post would run after the whole loop, by
            // which point each panel has already been replaced by the next. Measured exactly that:
            // every panel read back as zero rows with no empty marker, which is a rendered-empty
            // container and not a populated one.
            report(runCatching { probe(activity) }.getOrElse { "error=$it" })
        }

        private fun probe(activity: PlayerActivity): String {
            val lines = ArrayList<String>()
            for ((index, entry) in PROBED.withIndex()) {
                val (panel, titleRes) = entry
                if (index == 0) {
                    activity.showInspectorForTest(panel)
                } else {
                    activity.showPanelForTest(panel)
                }

                // Measured through the sheet rather than by walking the activity's view tree: the
                // rows live in the sheet's own dialog window, which is not a child of the activity.
                val sheet = activity.sheetForTest()
                val ids = sheet.visibleRowIds()
                val empty = ids.contains(sheet.emptyMarkerId)
                // Option rows are the entries with no id of their own; the marker and the section
                // headings are the only things in the container that carry one.
                val rows = ids.count { it == android.view.View.NO_ID }
                val headings = ids.count { it == sheet.sectionHeadingId }
                lines += "$panel rows=$rows headings=$headings " +
                    "empty=${if (empty) "yes" else "no"} title=${activity.getString(titleRes)}"
            }
            // Leave nothing on screen: the sheet is a dialog over the player, and a probe that ended
            // with it open would change what every suite running after it photographs. Reported as
            // well as done, because "is a dialog still up" cannot be read from the window dump on
            // this device - the lock screen's shade holds `mCurrentFocus` more often than not.
            activity.hidePanelForTest()
            val closed = !activity.sheetForTest().isOpen
            return lines.joinToString("; ") + " closed=${if (closed) "yes" else "no"}"
        }

        private fun report(data: String) {
            Log.i(TAG, "panel probe :: $data")
        }
    }
}
