package com.gan.spatialplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.recyclerview.widget.RecyclerView
import com.gan.spatialplayer.media.PlaybackEngine
import com.gan.spatialplayer.media.StereoUpmixProcessor
import org.json.JSONArray
import org.json.JSONObject

/** Reports actual visible geometry and actual decoder/DSP state for real input-driven tests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object UiStateProbe {
    const val PLAYER = "org.spatialplayer.debug.UI_PLAYER"
    const val LIBRARY = "org.spatialplayer.debug.UI_LIBRARY"
    fun report(activity: Activity, intent: Intent) {
        intent.getStringExtra("restore_upmix")?.let {
            val state = runCatching { JSONObject(it) }.getOrElse { error -> Log.e("UiStateProbe", "Invalid restore payload", error); return }
            SettingsStore(activity).apply {
                upmixAdvanced = state.getBoolean("advanced")
                upmixMode = state.getString("mode")
                upmixMatrix = state.getString("matrix")
            }
            PlaybackEngine.peek()?.reloadUpmixSettings()
        }
        intent.getStringExtra("restore_source")?.let { (activity as? MainActivity)?.restoreSourceForTest(it) }
        if (intent.hasExtra("restore_spatial")) SettingsStore(activity).spatialEnabled = intent.getBooleanExtra("restore_spatial", true)
        val views = JSONObject()
        val names = listOf("buttonOpenFile", "buttonOpenFolder", "buttonPlayPause", "seekFeedback", "timeCurrent", "timeRemaining", "playerChips", "chipStrip", "buttonBack", "buttonInspect", "buttonMetadata", "buttonMore", "buttonSpeed",
            "seekBar", "controlGlass", "playerHeader", "buttonLibrary", "buttonPoweramp", "buttonSettings", "buttonRefresh", "libraryBody", "fileList")
        fun add(name: String, view: View) {
            val pos = IntArray(2)
            view.getLocationOnScreen(pos)
            val rect = Rect()
            val shown = view.getGlobalVisibleRect(rect)
            views.put(name, JSONObject().put("bounds", JSONArray(listOf(pos[0], pos[1], pos[0] + view.width, pos[1] + view.height)))
                .put("visible", shown && view.isShown).put("width", view.width).put("height", view.height)
                .put("text", (view as? android.widget.TextView)?.text?.toString().orEmpty())
                .put("alpha", view.alpha).put("rows", (view as? RecyclerView)?.childCount ?: 0))
        }
        for (name in names) {
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            activity.findViewById<View>(id)?.let { add(name, it) }
        }
        val sheet = (activity as? PlayerActivity)?.sheetForTest()
        if (sheet != null) for (tag in listOf("sheet_close", "speed_readout", "speed_slider", "speed_1.5", "speed_1.0")) {
            sheet.viewTaggedForTest(tag)?.let { add(tag, it) }
        }
        val sample = PlaybackEngine.peek()?.sample()
        val seek = activity.findViewById<com.gan.spatialplayer.ui.SeekBarView>(R.id.seekBar)
        val settings = SettingsStore(activity)
        val pass = StereoUpmixProcessor()
        val native = AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
        val bypass = pass.configure(native) == native && !pass.isActive()
        pass.reset()
        val json = JSONObject().put("source", (activity as? MainActivity)?.sourceForTest)
            .put("spatialSetting", settings.spatialEnabled)
            .put("haptics", com.gan.spatialplayer.ui.Haptics.feedbackCount)
            .put("hapticsEnabled", android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0)
            .put("planarGravity", com.gan.spatialplayer.ui.LightSourceController.planarGravity)
            .put("light", JSONArray(com.gan.spatialplayer.ui.LightSourceController.lightDirection()?.let { listOf(it.first,it.second) } ?: emptyList<Float>()))
            .put("volume", (activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            .put("requestId", intent.getStringExtra("request_id")).put("sheetOpen", sheet?.isOpen ?: false).put("panel", sheet?.shownPanel?.name.orEmpty()).put("views", views).put("width", activity.window.decorView.width).put("height", activity.window.decorView.height)
            .put("media", PlaybackEngine.peek()?.currentMediaUri?.toString()).put("state", sample?.state).put("error", sample?.playerError).put("speed", sample?.playbackSpeed)
            .put("decoder", sample?.videoCodecName).put("rendered", sample?.renderedFrames ?: 0).put("dropped", sample?.droppedFrames ?: 0)
            .put("upmix", PlaybackEngine.peek()?.isStereoUpmixActive ?: false).put("channels", sample?.audioChannelCount ?: 0)
            .put("seekDragging", seek?.isScrubbing ?: false).put("seekDisplayed", seek?.displayedFraction ?: 0f)
            .put("positionMs", sample?.positionMs ?: 0L).put("durationMs", sample?.durationMs ?: 0L)
            .put("glassFrames", com.gan.spatialplayer.ui.GlassLensRenderer.framesRendered)
            .put("surroundBypass", bypass)
            .put("upmixSettings", JSONObject().put("advanced", settings.upmixAdvanced).put("mode", settings.upmixMode).put("matrix", settings.upmixMatrix))
        Log.i("UiStateProbe", json.toString())
    }
}
