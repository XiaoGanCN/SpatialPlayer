package com.gan.spatialplayer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.gan.spatialplayer.media.MatroskaChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only. **Not present in release builds.**
 *
 * Reads the chapters out of a file and logs them, so `tools/verify-chapters.sh` can assert on the
 * parse without a screen reader. The player is animating while a sheet is open, which means
 * `uiautomator dump` refuses to settle - "could not get idle state" - so the UI cannot be
 * introspected reliably, and the alternative would be OCR of a screenshot.
 *
 * It logs one line per chapter in a deliberately flat, greppable shape, and a final summary, then
 * finishes. Nothing here touches playback.
 */
class SmokeChaptersProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val uriText = intent.getStringExtra(EXTRA_URI)
        if (path.isNullOrBlank() && uriText.isNullOrBlank()) {
            finish()
            return
        }

        val app = applicationContext
        Thread {
            val chapters = runCatching {
                if (!path.isNullOrBlank()) {
                    MatroskaChapters.read(java.io.File(path))
                } else {
                    app.contentResolver.openInputStream(Uri.parse(uriText))
                        ?.use { MatroskaChapters.read(it) }
                        ?: emptyList()
                }
            }.getOrElse {
                Log.w(TAG, "chapter read failed", it)
                emptyList()
            }

            for ((index, chapter) in chapters.withIndex()) {
                Log.i(
                    TAG,
                    "chapter :: $index | ${chapter.startMs} | ${chapter.endMs ?: -1} | " +
                        "${chapter.depth} | ${chapter.language ?: "-"} | ${chapter.title ?: "-"}",
                )
            }
            Log.i(TAG, "chapters total=${chapters.size}")
            runOnUiThread { finish() }
        }.start()
    }

    companion object {
        private const val TAG = "ChaptersProbe"
        const val EXTRA_PATH = "chapters_probe_path"
        const val EXTRA_URI = "chapters_probe_uri"
    }
}
