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
        if (intent.action == ACTION_CHROME_TOUCH) {
            probeChromeTouch()
            return
        }
        if (intent.action == ACTION_GEOMETRY) {
            probeGeometry(context)
            return
        }
        if (intent.action == ACTION_GLASS_PRESS) {
            probeGlassPress()
            return
        }
        trigger()
    }

    companion object {
        private const val TAG = "PanelProbe"

        const val ACTION = "com.gan.spatialplayer.debug.PANEL_PROBE"

        /**
         * Exercises the chrome's touch path instead of its panels.
         *
         * Separate action because it is a different question: what happens when the chrome has timed
         * out and the user taps where one of its buttons used to be. See [probeChromeTouch].
         */
        const val ACTION_CHROME_TOUCH = "com.gan.spatialplayer.debug.CHROME_TOUCH"

        /**
         * Reports the picture geometry the ambient wash is drawing with. Used by
         * `tools/verify-rotation.sh` after each rotation.
         */
        const val ACTION_GEOMETRY = "com.gan.spatialplayer.debug.GEOMETRY"

        /**
         * Holds the player's capsule in a press for a moment, so `tools/verify-glass-press.sh` can
         * photograph the press and compare it with the same capsule at rest.
         */
        const val ACTION_GLASS_PRESS = "com.gan.spatialplayer.debug.GLASS_PRESS"

        /** How long the press is held. Long enough to screenshot from the host, twice. */
        private const val PRESS_HOLD_MS = 3_000L

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

        /**
         * What happens when the chrome has timed out and the user taps where a button used to be.
         *
         * The bug: the chrome faded to `alpha = 0` but stayed `VISIBLE`, and a transparent view still
         * takes touches. The tap went to the invisible button rather than becoming a gesture, so
         * nothing appeared to happen and the chrome did not come back either - reported as "these
         * buttons are dead".
         *
         * Two things are measured, both properties of the real activity rather than of a synthetic
         * event:
         *
         *  * `routed` - the predicate `dispatchTouchEvent` branches on, asked about the point the
         *    More button occupies. It must be true while the chrome is up and false once it has timed
         *    out, because that is what sends the tap to the gesture layer.
         *  * `parked` - the overlay has actually left the touch path. Alpha alone does not do that.
         *
         * The button's own action is exercised as the control: if `opened` were false with the chrome
         * up, the probe would be measuring nothing.
         */
        private fun probeChromeTouch() {
            val activity = PlayerActivity.instance
            if (activity == null) {
                report("chrome-touch error=no_activity")
                return
            }

            activity.showControlsTemporarilyForTest()
            activity.window.decorView.post {
                val visible = activity.controlsShownForTest()
                val more = activity.controlCentreForTest(moreButtonOf(activity))
                val routedVisible = activity.routesToControlsForTest(
                    more[0].toFloat(),
                    more[1].toFloat(),
                )
                activity.tapMoreForTest()
                val opened = activity.overflowSheetOpenForTest()

                activity.window.decorView.postDelayed(
                    {
                        activity.overflowSheetCloseForTest()
                        activity.hideControlsForTest()
                        activity.window.decorView.postDelayed(
                            {
                                val hidden = !activity.controlsShownForTest()
                                val parked = !activity.controlsTouchableForTest()
                                val routedHidden = activity.routesToControlsForTest(
                                    more[0].toFloat(),
                                    more[1].toFloat(),
                                )
                                report(
                                    "chrome-touch visible=$visible routedVisible=$routedVisible " +
                                        "opened=$opened hidden=$hidden parked=$parked " +
                                        "routedHidden=$routedHidden",
                                )
                                activity.hideControlsForTest()
                            },
                            HIDE_SETTLE_MS,
                        )
                    },
                    SHEET_SETTLE_MS,
                )
            }
        }

        /**
         * The picture geometry, after a settle delay so a pending layout or geometry pass has run.
         *
         * Posted rather than read immediately: a rotation schedules its recompute on the next frame
         * (see `PlayerActivity.scheduleVideoRectRefresh`), so reading at once would measure the
         * intermediate state and report a failure that no user would ever see.
         */
        private fun probeGeometry(context: Context) {
            val activity = PlayerActivity.instance
            if (activity == null) {
                report("geometry error=no_activity")
                return
            }
            activity.window.decorView.postDelayed(
                { report("geometry :: " + activity.ambientGeometryForTest()) },
                GEOMETRY_SETTLE_MS,
            )
        }

        /**
         * Long enough for `PanelProbeActivity`'s `finish()` to complete and the player to be the
         * resumed activity again.
         *
         * This is not padding. The probe shim is a full-screen activity, so while it is finishing the
         * view tree is being re-laid out, and a reading taken inside that window described a capsule
         * 1100 px lower than the one the placement code had just computed - which looked exactly
         * like a layout bug and was not one.
         */
        private const val GEOMETRY_SETTLE_MS = 2_500L

        private fun moreButtonOf(activity: PlayerActivity): android.view.View =
            activity.findViewById(R.id.buttonMore)

        /**
         * Presses the capsule and holds it, then releases and reports the geometry it was pressed at.
         *
         * The press is applied to the real activity's capsule rather than to a view built for the
         * probe, for the same reason the panel probe drives the real sheet: the question is whether
         * the material responds, and a copy would answer a different question.
         */
        private fun probeGlassPress() {
            val activity = PlayerActivity.instance
            if (activity == null) {
                report("glass-press error=no_activity")
                return
            }
            activity.showControlsTemporarilyForTest()
            activity.window.decorView.post {
                val (width, height) = activity.capsuleSizeForTest()
                if (width <= 0 || height <= 0) {
                    report("glass-press error=no_capsule w=$width h=$height")
                    return@post
                }
                // A third of the way along and centred vertically: inside the silhouette, clear of the
                // rounded ends, and away from the buttons that sit at either edge.
                val x = width / 3f
                val y = height / 2f
                activity.pressCapsuleForTest(1f, x, y)
                report("glass-press pressed=true w=$width h=$height x=${"%.0f".format(x)} y=${"%.0f".format(y)}")
                activity.window.decorView.postDelayed(
                    {
                        activity.pressCapsuleForTest(0f, x, y)
                        report("glass-press pressed=false")
                    },
                    PRESS_HOLD_MS,
                )
            }
        }

        private fun report(data: String) {
            Log.i(TAG, "panel probe :: $data")
        }

        private const val SHEET_SETTLE_MS = 600L
        private const val HIDE_SETTLE_MS = 500L
    }
}
