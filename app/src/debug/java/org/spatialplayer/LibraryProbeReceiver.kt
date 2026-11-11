package org.spatialplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Debug-only. **Not registered outside the debug variant** - see [MainActivity.onResume].
 *
 * Drives the *running* library through the sequence that lost Poweramp's rows: open the Poweramp
 * fold, count what it shows, press refresh, count again. `SmokePowerampProbeActivity` could not
 * have caught that bug, because it reads Poweramp's provider in a fresh process and never sees
 * `MainActivity`'s list at all - the provider read was never what was broken.
 *
 * Both loads are asynchronous (a query, then a merge, then a publish), so the probe polls the
 * list itself rather than sleeping a fixed time: the count is `0` while a load is in flight and
 * the header is empty until it lands, so "the list says it has rows" is the settled state. A
 * fixed sleep raced the load once already in this project's history and reported an empty fold.
 *
 * Everything it reports is one greppable line, narrated to the log, the same shape the other
 * probes use.
 */
class LibraryProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UiStateProbe.LIBRARY) {
            MainActivity.instance?.let { UiStateProbe.report(it, intent) }
            return
        }

        if (intent.action != ACTION) return
        val activity = MainActivity.instance
        if (activity == null) {
            report("error=no_activity")
            return
        }
        Handler(Looper.getMainLooper()).post { probe(activity) }
    }

    private fun probe(activity: MainActivity) {
        // Remembered so the probe leaves the library as it found it: a diagnostic that moves the
        // screen it measured is a bug of its own the next time a human looks at the phone.
        val initialFold = activity.foldForTest
        activity.openPowerampFoldForTest()

        // Wait for the fold's own load: rows > 0, or the loaded flag, or the header describing
        // the music rather than the empty state. The count is what the harness compares, so it is
        // read only once the list has settled.
        waitFor(activity, LOAD_ROUNDS, { settled(activity) }) { ok ->
            val before = activity.powerampRowCountForTest
            val loaded = activity.powerampLoadedForTest
            if (!ok) {
                report("poweramp fold did not settle before refresh rows=$before loaded=$loaded")
                activity.setFoldForTest(initialFold)
                return@waitFor
            }

            activity.refreshForTest()
            // A refresh publishes once the three sources are merged; the scan spinner is the
            // only thing that says "still working", so it is the thing polled.
            waitFor(activity, REFRESH_ROUNDS, { !activity.scanningForTest }) { settled ->
                val after = activity.powerampRowCountForTest
                // The fold must also still be open: a bug that closed it on refresh would report
                // an equal count for the wrong list.
                val stillOpen = activity.powerampRowCountForTest >= 0
                report(
                    "poweramp before=$before after=$after unchanged=${before == after} " +
                        "fold=${if (stillOpen) "open" else "closed"} refreshed=$loaded->" +
                        "${activity.powerampLoadedForTest} header=${activity.headerForTest()}",
                )
                if (!settled) report("poweramp refresh did not settle; counts are as far as it got")
                activity.setFoldForTest(initialFold)
            }
        }
    }

    private fun settled(activity: MainActivity): Boolean =
        activity.powerampRowCountForTest > 0 && !activity.scanningForTest

    /**
     * Polls `ready` every [POLL_MS] on the main thread, up to [maxRounds]; runs `then` on the
     * main thread either way, with whether the condition ever held. Never blocks `onReceive`,
     * which has to return before any of this can run.
     */
    private fun waitFor(
        activity: MainActivity,
        maxRounds: Int,
        ready: () -> Boolean,
        then: (Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        var rounds = 0
        val step = object : Runnable {
            override fun run() {
                if (ready()) {
                    then(true)
                    return
                }
                rounds++
                if (rounds >= maxRounds) {
                    then(false)
                    return
                }
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.post(step)
    }

    private fun report(data: String) {
        Log.i(TAG, "library probe :: $data")
    }

    companion object {
        private const val TAG = "LibraryProbe"

        const val ACTION = "org.spatialplayer.debug.LIBRARY_PROBE"

        private const val POLL_MS = 400L

        /** The fold's load is one provider query; eight seconds is a generous ceiling. */
        private const val LOAD_ROUNDS = 20

        /** A refresh re-reads MediaStore, the folder and Poweramp; give it the same ceiling. */
        private const val REFRESH_ROUNDS = 20
    }
}
