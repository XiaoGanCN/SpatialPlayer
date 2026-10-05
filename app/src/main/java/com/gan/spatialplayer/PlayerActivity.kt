package com.gan.spatialplayer

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import com.gan.spatialplayer.databinding.ActivityPlayerBinding
import com.gan.spatialplayer.media.DecoderProfile
import com.gan.spatialplayer.media.DeviceCapabilities
import com.gan.spatialplayer.media.FfmpegCodecs
import com.gan.spatialplayer.media.PlaybackReport
import com.gan.spatialplayer.media.PlayerEngine
import com.gan.spatialplayer.media.PlayerSample
import com.gan.spatialplayer.ui.AmbientSampler
import com.gan.spatialplayer.ui.InspectorSheet
import com.gan.spatialplayer.ui.PlayerAnimation
import com.gan.spatialplayer.ui.PlayerGestureController
import com.gan.spatialplayer.ui.TextSpans
import com.gan.spatialplayer.ui.VideoRectCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The player.
 *
 * Owns decode policy (through [PlayerEngine]), presentation (scaling, subtitles, ambient glow) and
 * the chrome. Everything that decides *how* a file is played is here or in the engine; the views
 * only report what the user asked for.
 */
class PlayerActivity : AppCompatActivity(), PlayerEngine.Listener, InspectorSheet.Callback,
    PlayerGestureController.Host {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var engine: PlayerEngine
    private lateinit var gestures: PlayerGestureController
    private lateinit var inspectorSheet: InspectorSheet
    private var ambientSampler: AmbientSampler? = null
    private val ambientHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var displayName: String = ""
    private var mimeType: String? = null
    private var sizeBytes: Long = 0L
    private var mediaUri: Uri? = null

    private var controlsVisible = true
    private var lastSample: PlayerSample = PlayerSample.EMPTY
    private var lastTracks: Tracks? = null
    private var videoSize: VideoSize? = null
    private var refreshJob: Job? = null
    private var isScrubbing = false

    /** Playback position when a horizontal scrub began, and the position it currently points at. */
    private var scrubAnchorMs = 0L
    private var scrubTargetMs = 0L

    private var scaleMode = VideoRectCalculator.SCALE_FIT
    private var userZoom = 1f
    private var ambientEnabled = true
    private var locked = false
    private var templateRect = Rect()

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            attachSubtitle(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // A video player that lets the panel sleep mid-film is broken. This is scoped to the
        // player window only, so the library list still follows the normal screen timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?: intent.data?.lastPathSegment?.substringAfterLast('/')
            ?: "Untitled"
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
        sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
        mediaUri = intent.data

        engine = PlayerEngine(this, this)
        engine.build()

        binding.playerView.player = engine.player
        configurePlayerView()

        gestures = PlayerGestureController(this, this)
        binding.gestureLayer.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

        inspectorSheet = InspectorSheet(this).apply { callback = this@PlayerActivity }

        setUpControls()
        setUpBackHandling()
        updateTitle()
        startRefreshLoop()

        mediaUri?.let { uri ->
            engine.setMedia(uri, mimeType ?: PlayerEngine.mimeForExtension(displayName.substringAfterLast('.', "")))
            engine.prepare()
            engine.play()
            discoverSidecarSubtitles(uri)
        }
    }

    // ------------------------------------------------------------------ configuration

    private fun configurePlayerView() {
        binding.playerView.useController = false
        binding.playerView.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        binding.playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
        binding.playerView.setKeepContentOnPlayerReset(true)
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        // Subtitles: readable defaults, no baked-in styling fighting the design.
        binding.playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(false)
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SUBTITLE_TEXT_SP)
            setBottomPaddingFraction(SUBTITLE_BOTTOM_FRACTION)
        }
    }

    private fun setUpControls() {
        binding.buttonBack.setOnClickListener {
            if (inspectorSheet.isOpen) inspectorSheet.hide() else finish()
        }

        binding.buttonPlayPause.setOnClickListener { togglePlayPause() }
        binding.centerPlayPause.setOnClickListener { togglePlayPause() }

        binding.buttonRewind.setOnClickListener { seekBy(-PlayerEngine.SEEK_STEP_MS) }
        binding.buttonForward.setOnClickListener { seekBy(PlayerEngine.SEEK_STEP_MS) }

        binding.buttonSpeed.setOnClickListener { cycleSpeed() }

        binding.buttonAspect.setOnClickListener {
            cycleScaleMode()
        }

        binding.buttonSubtitles.setOnClickListener {
            populateSubtitlePanel()
            inspectorSheet.show(InspectorSheet.Panel.SUBTITLES)
        }

        binding.buttonAudio.setOnClickListener {
            populateAudioPanel()
            inspectorSheet.show(InspectorSheet.Panel.AUDIO)
        }

        // The live readouts. These are the primary way in, so they sit in the chrome rather than
        // hiding behind a long-press.
        binding.buttonInspect.setOnClickListener {
            inspectorSheet.show(InspectorSheet.Panel.INSPECTION)
        }

        binding.buttonMetadata.setOnClickListener {
            inspectorSheet.show(InspectorSheet.Panel.METADATA)
        }

        binding.seekBar.listener = object : com.gan.spatialplayer.ui.SeekBarView.Listener {
            override fun onScrubStart() {
                isScrubbing = true
                showControlsTemporarily()
            }

            override fun onScrubMove(fraction: Float) {
                val duration = lastSample.durationMs
                if (duration > 0) {
                    binding.timeCurrent.text = TextSpans.timecode((duration * fraction).toLong())
                }
            }

            override fun onScrubStop(fraction: Float) {
                val duration = engine.player?.duration ?: 0L
                if (duration > 0) {
                    engine.player?.seekTo((duration * fraction).toLong().coerceAtLeast(0L))
                }
                isScrubbing = false
            }
        }

        binding.buttonDismissError.setOnClickListener {
            PlayerAnimation.fadeOut(binding.errorCard)
        }

        // Long-press the inspect button jumps straight to the decoder panel.
        binding.buttonInspect.setOnLongClickListener {
            populateDecoderPanel()
            inspectorSheet.show(InspectorSheet.Panel.DECODER)
            true
        }

        // Long-press the play button opens the decoder sheet: discoverable but not in the way.
        binding.buttonPlayPause.setOnLongClickListener {
            populateDecoderPanel()
            inspectorSheet.show(InspectorSheet.Panel.DECODER)
            true
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controlsOverlay.setPadding(0, 0, 0, 0)
            insets
        }
    }

    private fun updateTitle() {
        binding.mediaTitle.text = displayName
        binding.mediaSubtitle.text = buildString {
            append(mimeType ?: PlaybackReport.containerFor(displayName, null) ?: "video")
            if (sizeBytes > 0) {
                append("  ·  ").append(TextSpans.bytes(sizeBytes))
            }
        }
    }

    // ------------------------------------------------------------------ transport

    private fun togglePlayPause() {
        val player = engine.player ?: return
        if (player.isPlaying) player.pause() else player.play()
        syncPlayPauseIcon()
        showControlsTemporarily()
    }

    private fun seekBy(deltaMs: Long) {
        val player = engine.player ?: return
        val duration = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        val clamped = if (duration > 0) target.coerceAtMost(duration) else target
        player.seekTo(clamped)
        showSeekFeedback(deltaMs)
        showControlsTemporarily()
    }

    private fun cycleSpeed() {
        val player = engine.player ?: return
        val options = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val current = player.playbackParameters.speed
        val index = options.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val next = options[(index + 1).mod(options.size)]
        player.setPlaybackSpeed(next)
        updateSpeedLabel(next)
    }

    private fun updateSpeedLabel(speed: Float) {
        binding.buttonSpeed.text = String.format(Locale.US, "%.2f×", speed)
    }

    private fun cycleScaleMode() {
        scaleMode = when (scaleMode) {
            VideoRectCalculator.SCALE_FIT -> VideoRectCalculator.SCALE_FILL
            VideoRectCalculator.SCALE_FILL -> VideoRectCalculator.SCALE_ZOOM
            VideoRectCalculator.SCALE_ZOOM -> VideoRectCalculator.SCALE_STRETCH
            else -> VideoRectCalculator.SCALE_FIT
        }
        // A mode change resets any pinch zoom, so the two controls never fight.
        userZoom = 1f
        binding.playerView.resizeMode = when (scaleMode) {
            VideoRectCalculator.SCALE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoRectCalculator.SCALE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            VideoRectCalculator.SCALE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        val label = when (scaleMode) {
            VideoRectCalculator.SCALE_FIT -> getString(R.string.scale_fit)
            VideoRectCalculator.SCALE_FILL -> getString(R.string.scale_fill)
            VideoRectCalculator.SCALE_ZOOM -> getString(R.string.scale_zoom)
            else -> getString(R.string.scale_stretch)
        }
        showFeedback(label)
        updateVideoRect()
    }

    private fun toggleControls() {
        if (locked) return
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        PlayerAnimation.fadeIn(binding.controlsOverlay)
    }

    private fun hideControls() {
        controlsVisible = false
        PlayerAnimation.fadeOut(binding.controlsOverlay)
    }

    private var hideControlsRunnable: Runnable? = null

    private fun showControlsTemporarily(delayMs: Long = 3200L) {
        showControls()
        hideControlsRunnable?.let { ambientHandler.removeCallbacks(it) }
        val runnable = Runnable { hideControls() }
        hideControlsRunnable = runnable
        ambientHandler.postDelayed(runnable, delayMs)
    }

    private fun showSeekFeedback(deltaMs: Long) {
        binding.seekFeedback.text = TextSpans.offset(deltaMs)
        PlayerAnimation.pulse(binding.seekFeedback)
    }

    private fun showFeedback(text: String) {
        binding.seekFeedback.text = text
        PlayerAnimation.pulse(binding.seekFeedback, holdMs = 800L)
    }

    private fun syncPlayPauseIcon() {
        val playing = engine.player?.isPlaying == true
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        binding.buttonPlayPause.setImageResource(icon)
        binding.centerPlayPause.setImageResource(icon)
        binding.buttonPlayPause.contentDescription =
            getString(if (playing) R.string.pause else R.string.play)
    }

    // ------------------------------------------------------------------ gestures

    override fun onSingleTap() {
        if (inspectorSheet.isOpen) return
        toggleControls()
    }

    override fun onDoubleTap(forward: Boolean) {
        seekBy(if (forward) PlayerEngine.SEEK_STEP_MS else -PlayerEngine.SEEK_STEP_MS)
    }

    override fun onScrubStart() {
        isScrubbing = true
        // Remember where the drag began; the gesture controller reports displacement from here.
        scrubAnchorMs = engine.player?.currentPosition ?: lastSample.positionMs
        scrubTargetMs = scrubAnchorMs
        showControls()
    }

    override fun onScrubMove(deltaFraction: Float) {
        val duration = lastSample.durationMs
        if (duration <= 0) return
        // Duration is a Long; promote to Double for the fraction maths, then clamp as a Long.
        val target = (scrubAnchorMs + (duration.toDouble() * deltaFraction.toDouble()).toLong())
            .coerceIn(0L, duration)
        scrubTargetMs = target
        binding.timeCurrent.text = TextSpans.timecode(target)
        binding.seekBar.forceProgress((target.toFloat() / duration).coerceIn(0f, 1f))
    }

    override fun onScrubStop(deltaFraction: Float) {
        val duration = engine.player?.duration ?: 0L
        if (duration > 0) {
            val target = (scrubAnchorMs + (duration.toDouble() * deltaFraction.toDouble()).toLong())
                .coerceIn(0L, duration)
            engine.player?.seekTo(target)
            binding.seekBar.forceProgress((target.toFloat() / duration).coerceIn(0f, 1f))
        }
        isScrubbing = false
        showControlsTemporarily()
    }

    override fun onZoom(scale: Float) {
        val newZoom = (userZoom * scale).coerceIn(1f, 4f)
        if (kotlin.math.abs(newZoom - userZoom) < 0.01f) return
        userZoom = newZoom
        // Pinch only means anything when the picture is allowed to overflow.
        if (scaleMode != VideoRectCalculator.SCALE_ZOOM && scaleMode != VideoRectCalculator.SCALE_FILL) {
            scaleMode = VideoRectCalculator.SCALE_ZOOM
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        updateVideoRect()
        showFeedback(String.format(Locale.US, "%.2f×", userZoom))
    }

    override fun onBrightnessDelta(delta: Float) {
        val attrs = window.attributes
        val next = (attrs.screenBrightness.takeIf { it >= 0f } ?: 0.5f) + delta
        attrs.screenBrightness = next.coerceIn(0.01f, 1f)
        window.attributes = attrs
        showFeedback(String.format(Locale.US, "brightness %.0f%%", attrs.screenBrightness * 100))
    }

    override fun onVolumeDelta(delta: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (current + (delta * max)).toInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        showFeedback("volume ${(next * 100) / max}%")
    }

    // ------------------------------------------------------------------ ambient + geometry

    private fun startAmbientSampling() {
        // Idempotent: onResume and onRenderedFirstFrame both call this, and rebuilding the sampler
        // would discard the picture geometry it has already been told about, which makes the wash
        // sample the wrong part of the frame.
        if (ambientSampler != null) {
            ambientSampler?.start()
            return
        }

        val surface = binding.playerView.videoSurfaceView as? android.view.SurfaceView ?: return
        ambientSampler = AmbientSampler(
            surfaceView = surface,
            onColors = { top, bottom, left, right ->
                binding.ambientGlow.setEdgeColors(top, bottom, left, right)
            },
            onUnavailable = {
                // PixelCopy against a video surface is not guaranteed on every device. If it is
                // refused, stop burning attempts and say so once rather than silently leaving a
                // black screen where a glow was promised.
                binding.ambientGlow.glowEnabled = false
                Log.i(TAG, "ambient glow unavailable on this device; disabled")
            },
        ).also { it.start() }

        // Hand the new sampler the current geometry before it takes its first sample.
        updateVideoRect()
    }

    /**
     * Recomputes where the picture sits, then feeds that both to the ambient wash (so it knows
     * which bands are empty) and to the sampler (so it reads the picture and not the bars).
     */
    private fun updateVideoRect() {
        val container = binding.playerView
        val size = videoSize
        val width = size?.width ?: 0
        val height = size?.height ?: 0

        val rect = if (width > 0 && height > 0) {
            VideoRectCalculator.compute(
                containerWidth = container.width,
                containerHeight = container.height,
                videoWidth = width,
                videoHeight = height,
                pixelWidthHeightRatio = size?.pixelWidthHeightRatio ?: 1f,
                rotationDegrees = 0, // VideoSize reports this as deprecated/alway-zero in Media3 1.8
                mode = scaleMode,
                userZoom = userZoom,
            )
        } else {
            Rect(0, 0, container.width, container.height)
        }

        templateRect = rect
        binding.ambientGlow.setVideoRect(rect)
        ambientSampler?.setVideoRect(rect)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        engine.play()
        startAmbientSampling()
        refreshSpatialState()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            engine.pause()
        }
        ambientSampler?.stop()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        ambientSampler?.release()
        ambientSampler = null
        hideControlsRunnable?.let { ambientHandler.removeCallbacks(it) }
        engine.release()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            engine.pause()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val size = videoSize
        runCatching {
            val builder = PictureInPictureParams.Builder()
            if (size != null && size.width > 0 && size.height > 0) {
                builder.setAspectRatio(Rational(size.width, size.height))
            }
            enterPictureInPictureMode(builder.build())
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.controlsOverlay.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.ambientGlow.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ engine callbacks

    override fun onEnginePlaybackState(state: Int) {
        if (state == Player.STATE_READY) {
            updateDuration()
        }
    }

    override fun onEngineIsPlaying(playing: Boolean) {
        syncPlayPauseIcon()
    }

    override fun onEngineTracksChanged(tracks: Tracks) {
        lastTracks = tracks
        refreshOpenReadoutPanel()
    }

    override fun onEngineVideoSize(size: VideoSize) {
        videoSize = size
        updateVideoRect()
    }

    override fun onEngineError(message: String, cause: Throwable?) {
        binding.errorText.text = message
        PlayerAnimation.fadeIn(binding.errorCard)
    }

    override fun onEngineFirstFrame() {
        updateVideoRect()
        // Only meaningful once the surface actually has a frame in it.
        startAmbientSampling()
    }

    override fun onEnginePlaybackParameters(speed: Float) {
        updateSpeedLabel(speed)
    }

    // ------------------------------------------------------------------ refresh loop

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                updateProgress()
                if (inspectorSheet.isOpen) {
                    refreshOpenReadoutPanel()
                }
                delay(250L)
            }
        }
    }

    private fun updateProgress() {
        val sample = engine.sample()
        lastSample = sample
        if (!isScrubbing && sample.durationMs > 0) {
            val fraction = sample.positionMs.toFloat() / sample.durationMs
            binding.seekBar.setProgress(fraction)
            binding.seekBar.setBuffered(sample.bufferedMs.toFloat() / sample.durationMs)
            binding.timeCurrent.text = TextSpans.timecode(sample.positionMs)
            binding.timeRemaining.text =
                "-" + TextSpans.timecode((sample.durationMs - sample.positionMs).coerceAtLeast(0L))
        }
        updateHdrBadge()
    }

    private fun updateDuration() {
        val duration = engine.player?.duration ?: 0L
        if (duration > 0) {
            gestures.setDuration(duration)
            binding.timeRemaining.text = "-" + TextSpans.timecode(duration)
        }
    }

    /** Surfaces the HDR transfer function in the subtitle line, which is where it belongs. */
    private fun updateHdrBadge() {
        val videoFormat = engine.player?.videoFormat
        val hdr = PlaybackReport.hdrLabel(videoFormat)
        val base = buildString {
            append(mimeType ?: PlaybackReport.containerFor(displayName, null) ?: "video")
            if (sizeBytes > 0) append("  ·  ").append(TextSpans.bytes(sizeBytes))
        }
        binding.mediaSubtitle.text = if (hdr != null) "$base  ·  $hdr" else base
    }

    private fun refreshSpatialState() {
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        // The audio sheet reflects head-tracking availability; keep the button state honest.
        binding.buttonAudio.alpha = if (spatial.active) 1f else 0.7f
    }

    // ------------------------------------------------------------------ sheets

    private fun populateDecoderPanel() {
        val sheet = inspectorSheet
        val videoMime = engine.player?.videoFormat?.sampleMimeType
        val audioMime = engine.player?.audioFormat?.sampleMimeType

        val choices = DecoderProfile.entries.map { profile ->
            InspectorSheet.Choice(
                panel = InspectorSheet.Panel.DECODER,
                value = profile.name,
                title = profile.label,
                subtitle = buildString {
                    append(profile.detail)
                    append("\nvideo: ")
                    append(
                        com.gan.spatialplayer.media.DecoderPolicy.describeRoute(videoMime, profile),
                    )
                    append("   audio: ")
                    append(
                        com.gan.spatialplayer.media.DecoderPolicy.describeRoute(audioMime, profile),
                    )
                },
                selected = profile == engine.decoderProfile,
                enabled = true,
                section = if (profile == DecoderProfile.entries.first()) "Policy" else null,
            )
        }
        sheet.setChoices(InspectorSheet.Panel.DECODER, choices)
    }

    private fun populateAudioPanel() {
        val sheet = inspectorSheet
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        val tracks = lastTracks
        val choices = ArrayList<InspectorSheet.Choice>()

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.AUDIO,
            value = "spatial_on",
            title = getString(R.string.spatial_audio),
            subtitle = buildString {
                append("spatializer ")
                append(if (spatial.available) "available" else "unavailable")
                append(" · enabled ").append(spatial.enabled)
                append(" · level ").append(spatial.immersiveLevelLabel)
                append("\nhead tracker: ").append(spatial.headTrackerAvailable)
                append(" · output spatializes: ").append(spatial.outputCanSpatialize)
                append("\noutput: ").append(spatial.outputDeviceName ?: "—")
                if (!spatial.headTrackerAvailable || !spatial.enabled) {
                    append("\n").append(getString(R.string.head_tracking_how))
                }
            },
            selected = engine.spatialAudioEnabled,
            enabled = true,
            section = "Spatial",
        )

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.AUDIO,
            value = "spatial_off",
            title = "Disable spatialization",
            subtitle = "Send multichannel audio straight to the output",
            selected = !engine.spatialAudioEnabled,
            enabled = true,
        )

        if (tracks != null) {
            val audioLines = PlaybackReport.flatten(tracks).filter { it.type == "audio" }
            for ((index, line) in audioLines.withIndex()) {
                choices += InspectorSheet.Choice(
                    panel = InspectorSheet.Panel.AUDIO,
                    value = "track_$index",
                    title = line.label,
                    subtitle = line.detail,
                    selected = line.selected,
                    enabled = true,
                    section = if (index == 0) "Audio track" else null,
                )
            }
        }

        sheet.setChoices(InspectorSheet.Panel.AUDIO, choices)
    }

    private fun populateSubtitlePanel() {
        val sheet = inspectorSheet
        val tracks = lastTracks
        val choices = ArrayList<InspectorSheet.Choice>()

        val textLines = tracks?.let { PlaybackReport.flatten(it).filter { line -> line.type == "text" } }
            ?: emptyList()

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = SUBTITLE_OFF,
            title = getString(R.string.subtitle_off),
            subtitle = null,
            selected = textLines.none { it.selected },
            enabled = true,
            section = "Tracks",
        )

        for ((index, line) in textLines.withIndex()) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SUBTITLES,
                value = "text_$index",
                title = line.label,
                subtitle = line.detail,
                selected = line.selected,
                enabled = true,
            )
        }

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = "add_external",
            title = getString(R.string.subtitle_external),
            subtitle = "Sidecar files next to the video are picked up automatically",
            selected = false,
            enabled = true,
            section = "External",
        )

        sheet.setChoices(InspectorSheet.Panel.SUBTITLES, choices)
    }

    private fun populateScalingPanel() {
        val sheet = inspectorSheet
        val choices = listOf(
            Triple(VideoRectCalculator.SCALE_FIT, R.string.scale_fit, "Fit the whole picture, bars as needed"),
            Triple(VideoRectCalculator.SCALE_FILL, R.string.scale_fill, "Fill the screen, no cropping"),
            Triple(VideoRectCalculator.SCALE_ZOOM, R.string.scale_zoom, "Fill the screen and crop the overflow"),
            Triple(VideoRectCalculator.SCALE_STRETCH, R.string.scale_stretch, "Ignore aspect ratio (distorts)"),
        ).mapIndexed { index, (mode, titleRes, detail) ->
            InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SCALING,
                value = "scale_$mode",
                title = getString(titleRes),
                subtitle = detail,
                selected = mode == scaleMode,
                enabled = true,
                section = if (index == 0) "Scaling" else null,
            )
        } + InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SCALING,
            value = if (ambientEnabled) "ambient_off" else "ambient_on",
            title = getString(R.string.ambient_glow),
            subtitle = if (ambientEnabled) "On" else "Off",
            selected = ambientEnabled,
            enabled = true,
            section = "Ambient",
        )

        sheet.setChoices(InspectorSheet.Panel.SCALING, choices)
    }

    override fun onSheetClosed() {
        showControlsTemporarily()
    }

    override fun onReadoutRequested(panel: InspectorSheet.Panel): String {
        val hdr = DeviceCapabilities.hdrSnapshot(this)
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        val outDevice = engine.currentOutputDevice()?.let {
            "${it.productName} (${DeviceCapabilities.deviceTypeLabel(it.type)})"
        }
        return when (panel) {
            InspectorSheet.Panel.INSPECTION -> PlaybackReport.inspection(
                sample = engine.sample(),
                tracks = lastTracks?.let { PlaybackReport.flatten(it) } ?: emptyList(),
                decoderProfile = engine.decoderProfile,
                spatial = spatial,
                hdr = hdr,
                ffmpegVersion = FfmpegCodecs.version(),
                outputDevice = outDevice,
                videoSize = videoSize,
            )

            InspectorSheet.Panel.METADATA -> PlaybackReport.metadata(
                displayName = displayName,
                sizeBytes = sizeBytes,
                sample = engine.sample(),
                tracks = lastTracks?.let { PlaybackReport.flatten(it) } ?: emptyList(),
                containerMime = PlaybackReport.containerFor(displayName, mimeType),
            )

            else -> ""
        }
    }

    override fun onChoiceSelected(panel: InspectorSheet.Panel, choice: InspectorSheet.Choice) {
        when (panel) {
            InspectorSheet.Panel.DECODER -> {
                val profile = runCatching { DecoderProfile.valueOf(choice.value) }.getOrNull()
                if (profile != null && profile != engine.decoderProfile) {
                    showFeedback(getString(R.string.decoder_switch))
                    engine.switchDecoderProfile(profile)
                    binding.playerView.player = engine.player
                    populateDecoderPanel()
                }
            }

            InspectorSheet.Panel.AUDIO -> handleAudioChoice(choice)
            InspectorSheet.Panel.SUBTITLES -> handleSubtitleChoice(choice)
            InspectorSheet.Panel.SCALING -> handleScalingChoice(choice)
            else -> Unit
        }
    }

    private fun handleAudioChoice(choice: InspectorSheet.Choice) {
        when {
            choice.value == "spatial_on" -> {
                engine.setSpatialAudioEnabled(true)
                binding.playerView.player = engine.player
                populateAudioPanel()
            }

            choice.value == "spatial_off" -> {
                engine.setSpatialAudioEnabled(false)
                binding.playerView.player = engine.player
                populateAudioPanel()
            }

            choice.value.startsWith("track_") -> selectTrackByIndex(C.TRACK_TYPE_AUDIO, choice.value)
        }
    }

    private fun handleSubtitleChoice(choice: InspectorSheet.Choice) {
        when {
            choice.value == SUBTITLE_OFF -> engine.applyPreferredSubtitleLanguage(null)
            choice.value == "add_external" -> {
                pickSubtitle.launch(arrayOf("application/x-subrip", "text/*", "*/*"))
                return
            }

            choice.value.startsWith("text_") -> selectTrackByIndex(C.TRACK_TYPE_TEXT, choice.value)
        }
        populateSubtitlePanel()
    }

    private fun handleScalingChoice(choice: InspectorSheet.Choice) {
        when {
            choice.value.startsWith("scale_") -> {
                scaleMode = choice.value.removePrefix("scale_").toIntOrNull()
                    ?: VideoRectCalculator.SCALE_FIT
                userZoom = 1f
                binding.playerView.resizeMode = when (scaleMode) {
                    VideoRectCalculator.SCALE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoRectCalculator.SCALE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    VideoRectCalculator.SCALE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                updateVideoRect()
            }

            choice.value == "ambient_off" -> {
                ambientEnabled = false
                binding.ambientGlow.glowEnabled = false
            }

            choice.value == "ambient_on" -> {
                ambientEnabled = true
                binding.ambientGlow.glowEnabled = true
            }
        }
        populateScalingPanel()
    }

    /**
     * Selects the nth track of a type by building a [androidx.media3.common.TrackSelectionOverride]
     * over the matching track group, which is the only override Media3 accepts.
     */
    private fun selectTrackByIndex(trackType: Int, value: String) {
        val index = value.substringAfterLast('_').toIntOrNull() ?: return
        val tracks = lastTracks ?: return
        val player = engine.player ?: return
        val matching = tracks.groups.filter { it.type == trackType }
        val group = matching.getOrNull(index) ?: matching.firstOrNull() ?: return
        val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0)
        val updated = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
        player.trackSelectionParameters = updated
        refreshOpenReadoutPanel()
    }

    private fun attachSubtitle(uri: Uri) {
        val configuration = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeTypeForSubtitle(uri))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        engine.setExternalSubtitles(listOf(configuration))
        showFeedback("subtitle attached")
    }

    private fun mimeTypeForSubtitle(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase() ?: return MimeTypes.APPLICATION_SUBRIP
        return when (name.substringAfterLast('.', "")) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            "sub" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /** Looks for sidecar subtitle files beside the video and attaches them. */
    private fun discoverSidecarSubtitles(videoUri: Uri) {
        lifecycleScope.launch {
            val repository = com.gan.spatialplayer.media.MediaRepository(this@PlayerActivity)
            val entry = repository.describe(videoUri) ?: return@launch
            val sidecars = withContext(Dispatchers.IO) { repository.findSidecarSubtitles(entry) }
            if (sidecars.isEmpty()) return@launch
            val configurations = sidecars.take(4).map { uri ->
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mimeTypeForSubtitle(uri))
                    .setLabel(uri.lastPathSegment?.substringAfterLast('/'))
                    .build()
            }
            engine.setExternalSubtitles(configurations)
        }
    }

    // ------------------------------------------------------------------ misc

    /**
     * Re-renders a read-only panel in place. The sheet pulls its text through
     * [onReadoutRequested], so nudging it with the current choices is enough to refresh.
     */
    private fun refreshOpenReadoutPanel() {
        if (!inspectorSheet.isOpen) return
        val panel = inspectorSheet.shownPanel
        if (panel == InspectorSheet.Panel.INSPECTION || panel == InspectorSheet.Panel.METADATA) {
            inspectorSheet.setChoices(panel, emptyList())
        }
    }

    /**
     * Back closes an open sheet first, and only leaves the player when nothing is open.
     * Registered on the dispatcher rather than overriding the deprecated `onBackPressed`.
     */
    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (inspectorSheet.isOpen) {
                        inspectorSheet.hide()
                    } else {
                        // Disable and re-dispatch so the default behaviour (finish) takes over
                        // without recursing back into this callback.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_SIZE_BYTES = "extra_size_bytes"
        const val SUBTITLE_OFF = "subtitle_off"

        private const val TAG = "PlayerActivity"

        private const val SUBTITLE_TEXT_SP = 17f
        private const val SUBTITLE_BOTTOM_FRACTION = 0.08f
    }
}
