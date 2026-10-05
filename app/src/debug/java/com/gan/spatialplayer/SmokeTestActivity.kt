package com.gan.spatialplayer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Debug-only entry point. **Not present in release builds.**
 *
 * The release manifest keeps [PlayerActivity] unexported, which is correct security posture but
 * means `adb shell am start` cannot drive it directly. This shim exists so `tools/smoke-test.sh`
 * can exercise real playback over adb without loosening the shipped manifest.
 *
 * It lives in `src/debug`, so it is compiled into debug variants only.
 */
class SmokeTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        startActivity(
            android.content.Intent(this, PlayerActivity::class.java)
                .setAction(android.content.Intent.ACTION_VIEW)
                .setData(android.net.Uri.parse("file://$path"))
                .putExtra(PlayerActivity.EXTRA_DISPLAY_NAME, path.substringAfterLast('/'))
                .putExtra(PlayerActivity.EXTRA_MIME_TYPE, intent.getStringExtra(EXTRA_MIME)),
        )
        finish()
    }

    companion object {
        const val EXTRA_PATH = "smoke_path"
        const val EXTRA_MIME = "smoke_mime"
    }
}
