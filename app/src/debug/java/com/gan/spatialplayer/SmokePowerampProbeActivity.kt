package com.gan.spatialplayer

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gan.spatialplayer.media.PowerampReader
import kotlinx.coroutines.launch

/**
 * Debug-only: exercises the Poweramp reader and reports the outcome. **Not in release builds.**
 *
 * The library screen shows the result in a toast, which is not machine-checkable, so this exists to
 * make the integration assertable from `tools/verify-poweramp.sh`.
 */
class SmokePowerampProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reader = PowerampReader(this)
        Log.i(TAG, "installed :: ${reader.isInstalled()}")

        lifecycleScope.launch {
            val result = reader.readLibrary()
            Log.i(TAG, "status :: ${result.status}")
            Log.i(TAG, "count :: ${result.entries.size}")
            result.entries.take(5).forEach { entry ->
                Log.i(
                    TAG,
                    "entry :: ${entry.displayName} | ${entry.mimeType} | " +
                        "${entry.durationMs}ms | ${entry.uri}",
                )
            }
            val withDuration = result.entries.count { it.durationMs > 0 }
            Log.i(TAG, "summary :: total=${result.entries.size} with_duration=$withDuration")
            finish()
        }
    }

    companion object {
        const val TAG = "SpatialPlayerPoweramp"
    }
}
