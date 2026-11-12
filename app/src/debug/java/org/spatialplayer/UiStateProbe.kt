package org.spatialplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.recyclerview.widget.RecyclerView
import org.spatialplayer.media.PlaybackEngine
import org.spatialplayer.media.StereoUpmixProcessor
import org.json.JSONArray
import org.json.JSONObject

/** Reports actual visible geometry and actual decoder/DSP state for real input-driven tests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object UiStateProbe {
    const val SETTINGS = "org.spatialplayer.debug.UI_SETTINGS"
    const val PLAYER = "org.spatialplayer.debug.UI_PLAYER"
    const val LIBRARY = "org.spatialplayer.debug.UI_LIBRARY"
    fun report(activity: Activity, intent: Intent) {
        if (intent.getBooleanExtra("mutate_backdrop",false)) (activity as? TopFadeProbeActivity)?.changePattern()
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
        val names = listOf("buttonOpenFile", "headerTitle", "collectionSummary", "listTopFade", "scanProgress", "timelineRow", "transportRow", "buttonPlayPause", "buttonRewind", "buttonForward", "seekFeedback", "timeCurrent", "timeRemaining", "playerChips", "chipStrip", "buttonBack", "buttonInspect", "buttonMetadata", "buttonMore", "buttonSpeed",
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
        val overflow = (activity as? PlayerActivity)?.overflowForTest()
        if (sheet != null) for (tag in listOf("sheet_close", "sheet_back", "speed_readout", "speed_slider", "speed_1.5", "speed_1.0")) {
            sheet.viewTaggedForTest(tag)?.let { add(tag, it) }
        }
        if (overflow?.isOpen == true) for (tag in listOf("sheet_close", "overflow_action_0", "overflow_action_1", "overflow_action_2", "overflow_action_3", "overflow_action_4")) {
            overflow.viewTaggedForTest(tag)?.let { add(tag, it) }
        }
        intent.getStringExtra("restore_gain")?.toFloatOrNull()?.let { SettingsStore(activity).verticalGain = it }
        intent.getStringExtra("restore_jump")?.toLongOrNull()?.let { SettingsStore(activity).doubleTapJumpMs = it }
        val nodes = JSONArray()
        fun scan(view: View, path: String) {
            val glass = org.spatialplayer.ui.GlassInstaller.material(view)
            if (intent.getBooleanExtra("refresh_glass", false) && glass != null) view.invalidate()
            if ((intent.getBooleanExtra("include_text",false) && view is android.widget.TextView) || view is org.spatialplayer.ui.TopFadeBlurView || glass != null || view is org.spatialplayer.ui.GlassSlider || view is org.spatialplayer.ui.GlassSwitch || view is android.widget.ScrollView) {
                val position = IntArray(2); view.getLocationOnScreen(position)
                nodes.put(JSONObject().put("path", path).put("kind", view.javaClass.simpleName)
                    .put("bounds", JSONArray(listOf(position[0],position[1],position[0]+view.width,position[1]+view.height)))
                    .put("text",(view as? android.widget.TextView)?.text?.toString())
                    .put("selected", view.isSelected).put("tint",glass?.style?.body ?: 0)
                    .put("tag", view.tag?.toString()).put("scrollY", view.scrollY).put("height", view.height).put("width", view.width)
                    .put("translationY", view.translationY).put("scaleY", view.scaleY)
                    .put("frames", glass?.renderedFrames ?: (view as? org.spatialplayer.ui.TopFadeBlurView)?.renderedFrames ?: 0).put("pressed", view.isPressed)
                    .put("engaged", (view as? org.spatialplayer.ui.GlassSlider)?.glassEngaged ?: false)
                    .put("max", (view as? android.widget.SeekBar)?.max ?: 0).put("progress", (view as? android.widget.SeekBar)?.progress ?: -1)
                    .put("radius", glass?.let { minOf(it.style.cornerRadiusPx,view.width/2f,view.height/2f) } ?: 0f))
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) scan(view.getChildAt(i), "$path/$i")
        }
        scan(activity.window.decorView, "root")
        (activity as? SettingsActivity)?.activeSheet?.let { scan(it, "sheet") }
        org.spatialplayer.ui.SliderReading.activeEditor?.let { scan(it, "editor") }
        if (sheet?.isOpen == true) sheet.rootForTest()?.let { scan(it, "sheet") }
        if (overflow?.isOpen == true) overflow.rootForTest()?.let { scan(it, "sheet") }
        val sample = PlaybackEngine.peek()?.sample()
        val seek = activity.findViewById<org.spatialplayer.ui.SeekBarView>(R.id.seekBar)
        val settings = SettingsStore(activity)
        val pass = StereoUpmixProcessor()
        val native = AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
        val bypass = pass.configure(native) == native && !pass.isActive()
        pass.reset()
        val json = JSONObject().put("source", (activity as? MainActivity)?.sourceForTest)
            .put("verticalGain", settings.verticalGain)
            .put("spatialSetting", settings.spatialEnabled).put("skipMs", settings.doubleTapJumpMs).put("overflowOpen", overflow?.isOpen ?: false)
            .put("nodes", nodes).put("lastHaptic", org.spatialplayer.ui.Haptics.lastKind)
            .put("haptics", org.spatialplayer.ui.Haptics.feedbackCount)
            .put("hapticsEnabled", android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0)
            .put("planarGravity", org.spatialplayer.ui.LightSourceController.planarGravity)
            .put("light", JSONArray(org.spatialplayer.ui.LightSourceController.lightDirection()?.let { listOf(it.first,it.second) } ?: emptyList<Float>()))
            .put("volume", (activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            .put("requestId", intent.getStringExtra("request_id")).put("sheetOpen", sheet?.isOpen ?: false).put("panel", sheet?.shownPanel?.name.orEmpty()).put("views", views).put("width", activity.window.decorView.width).put("height", activity.window.decorView.height)
            .put("media", PlaybackEngine.peek()?.currentMediaUri?.toString()).put("state", sample?.state).put("error", sample?.playerError).put("speed", sample?.playbackSpeed)
            .put("decoder", sample?.videoCodecName).put("rendered", sample?.renderedFrames ?: 0).put("dropped", sample?.droppedFrames ?: 0)
            .put("upmix", PlaybackEngine.peek()?.isStereoUpmixActive ?: false).put("channels", sample?.audioChannelCount ?: 0)
            .put("seekDragging", seek?.isScrubbing ?: false).put("seekDisplayed", seek?.displayedFraction ?: 0f)
            .put("positionMs", sample?.positionMs ?: 0L).put("durationMs", sample?.durationMs ?: 0L)
            .put("videoBackdropState", org.spatialplayer.ui.GlassBackdrop.videoDebug(activity.window.decorView))
            .put("playerPlaying", PlaybackEngine.peek()?.player?.isPlaying).put("playWhenReady",PlaybackEngine.peek()?.player?.playWhenReady)
            .put("videoSize",PlaybackEngine.peek()?.player?.videoSize?.let { "${it.width}x${it.height}" })
            .put("videoBackdropReady", org.spatialplayer.ui.GlassBackdrop.videoReady(activity.window.decorView))
            .put("videoPreviewFrames", org.spatialplayer.ui.GlassBackdrop.videoPreviewFrames)
            .put("glassFrames", org.spatialplayer.ui.GlassLensRenderer.framesRendered)
            .put("surroundBypass", bypass)
            .put("upmixSettings", JSONObject().put("advanced", settings.upmixAdvanced).put("mode", settings.upmixMode).put("matrix", settings.upmixMatrix))
        val bytes = java.io.ByteArrayOutputStream().also { buffer ->
            java.util.zip.GZIPOutputStream(buffer).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(3000)
        val request = intent.getStringExtra("request_id") ?: "manual"
        chunks.forEachIndexed { index, chunk -> Log.i("UiStateProbe", "UI_STATE $request $index/${chunks.size} $chunk") }
    }
}
