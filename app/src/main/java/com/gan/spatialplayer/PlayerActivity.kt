package com.gan.spatialplayer

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
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
import com.gan.spatialplayer.media.MediaTrack
import com.gan.spatialplayer.media.MediaTracks
import com.gan.spatialplayer.media.PlaybackReport
import com.gan.spatialplayer.media.PlayerEngine
import com.gan.spatialplayer.media.PlayerSample
import com.gan.spatialplayer.media.DecoderPolicy
import com.gan.spatialplayer.ui.ChipStrip
import com.gan.spatialplayer.ui.AmbientSampler
import com.gan.spatialplayer.ui.Haptics
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
    private val prefs by lazy { getSharedPreferences("spatial_player", MODE_PRIVATE) }

    private lateinit var gestures: PlayerGestureController

    /** Touch dispatch must not reach the controller before it and the player exist. */
    private var gesturesReady = false
    private lateinit var inspectorSheet: InspectorSheet
    private var ambientSampler: AmbientSampler? = null
    private val ambientHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var displayName: String = ""
    private var mimeType: String? = null
    private var sizeBytes: Long = 0L
    private var mediaUri: Uri? = null

    private var lastSample: PlayerSample = PlayerSample.EMPTY
    private var mediaTracks: MediaTracks = MediaTracks.EMPTY
    private var videoSize: VideoSize? = null
    private var refreshJob: Job? = null
    private var isScrubbing = false

    /** Playback position when a horizontal scrub began, and the position it currently points at. */
    /** Fractional volume position while dragging; Int.MIN_VALUE means "resync from the system". */
    private var volumeAccumulator: Float = Float.MIN_VALUE

    private var scrubAnchorMs = 0L
    private var scrubTargetMs = 0L

    /**
     * Double-tap seek length, in ms.
     *
     * Owned by the activity rather than the player: Media3 exposes the seek increments only as
     * getters, and the only way to change them is to rebuild the player, which is far too heavy to do
     * while a slider is being dragged. The gesture controller and the repeatable skip buttons read
     * this value directly, so a double tap and a skip button move by the same amount. A headset's own
     * skip button stays at its own fixed length, which is the platform's business.
     *
     * Persisted because a preferred jump length is a habit, not a per-session choice.
     */
    private var doubleTapJumpMs: Long = DEFAULT_DOUBLE_TAP_JUMP_MS
        set(value) {
            val clamped = value.coerceIn(MIN_DOUBLE_TAP_JUMP_MS, MAX_DOUBLE_TAP_JUMP_MS)
            field = clamped
            if (::gestures.isInitialized) gestures.doubleTapJumpMs = clamped
            prefs.edit().putLong(KEY_DOUBLE_TAP_JUMP_MS, clamped).apply()
        }

    /** Subtitle tuning, adjustable from the subtitle panel while playing. */
    private var subtitleSizeSp: Float = DEFAULT_SUBTITLE_SP
    private var subtitlePositionFraction: Float = DEFAULT_SUBTITLE_POSITION

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
        attachGlassBackdrop()

        gestures = PlayerGestureController(this, this)
        gestures.verticalGain = DEFAULT_VERTICAL_GAIN
        // Restores the saved jump length, and through the setter also configures the controller and
        // the player's seek increments.
        doubleTapJumpMs = prefs.getLong(KEY_DOUBLE_TAP_JUMP_MS, DEFAULT_DOUBLE_TAP_JUMP_MS)
        // The controller scales drags by the viewport, so it must be told the real size. It was
        // never being given it, which left viewWidth/viewHeight at 1: every vertical delta then
        // saturated its per-event cap and a short drag moved the volume by many steps at once.
        binding.controlsOverlay.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            gestures.setViewport(r - l, b - t)
        }
        binding.controlsOverlay.post {
            gestures.setViewport(binding.controlsOverlay.width, binding.controlsOverlay.height)
        }
        gesturesReady = true

        inspectorSheet = InspectorSheet(this).apply { callback = this@PlayerActivity }

        setUpControls()
        setUpBackHandling()
        updateTitle()
        startRefreshLoop()

        mediaUri?.let { uri ->
            engine.setMedia(uri, mimeType ?: PlayerEngine.mimeForExtension(displayName.substringAfterLast('.', "")))
            engine.prepare()

            // Pick up where this item was left off, if it was played earlier in this session.
            val remembered = PlaybackMemory.positionFor(uri)
            if (remembered > RESUME_MIN_MS) {
                engine.player?.seekTo(remembered)
                showFeedback(getString(R.string.resuming_at, TextSpans.timecode(remembered)))
            }

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
        // The glass capsule refracts the video behind it. The backdrop has to be the surface that
        // actually holds the picture, since a SurfaceView is composited outside the window.
        val density = resources.displayMetrics.density
        binding.controlGlass.cornerRadiusPx = density * CONTROL_GLASS_RADIUS_DP
        // A wide bevel on a control-height capsule: the bend has to happen over most of the pane's
        // short side or the rim reads as a thin outline rather than as thickness.
        binding.controlGlass.bevelWidthPx = density * CONTROL_GLASS_BEVEL_DP
        binding.controlGlass.refractionPx = density * CONTROL_GLASS_REFRACT_DP
        binding.controlGlass.refractionFalloff = 1.6f
        binding.controlGlass.dispersionStrength = 0.10f
        binding.controlGlass.specularStrength = 1.0f
        binding.controlGlass.saturation = 1.06f

        applySubtitleStyle()
    }

    private fun setUpControls() {
        binding.buttonBack.setOnClickListener {
            if (inspectorSheet.isOpen) inspectorSheet.hide() else finish()
        }

        binding.buttonPlayPause.setOnClickListener { togglePlayPause() }
        binding.centerPlayPause.setOnClickListener { togglePlayPause() }

        // The skip buttons follow the same configurable length as a double tap, so the two never
        // disagree about what "jump" means.
        binding.buttonRewind.setOnClickListener { seekBy(-doubleTapJumpMs) }
        binding.buttonForward.setOnClickListener { seekBy(doubleTapJumpMs) }

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
                Haptics.touch(binding.seekBar)
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
                Haptics.release(binding.seekBar)
            }
        }

        binding.buttonDismissError.setOnClickListener {
            PlayerAnimation.hide(binding.errorCard)
        }

        // The precise error text is the whole point of the card, so make it easy to hand over.
        binding.buttonCopyError.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("SpatialPlayer error", binding.errorText.text),
            )
            Haptics.release(binding.buttonCopyError)
            showFeedback(getString(R.string.copied))
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

    /**
     * Applies the current subtitle size and vertical position.
     *
     * Both are adjustable because a single fixed choice cannot suit a phone held in one hand, a
     * landscape window, and a TV-style layout at once - and squashed or clipped subtitles are worse
     * than none.
     */
    private fun applySubtitleStyle() {
        binding.playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(false)
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp)
            setBottomPaddingFraction(subtitlePositionFraction)
        }
    }

    private fun updateTitle() {
        binding.mediaTitle.text = displayName
        updateStreamChips()
    }

    // ------------------------------------------------------------------ transport

    private fun togglePlayPause() {
        val player = engine.player ?: return
        if (player.isPlaying) player.pause() else player.play()
        Haptics.release(binding.buttonPlayPause)
        syncPlayPauseIcon()
        showControlsTemporarily()
    }

    private fun seekBy(deltaMs: Long) {
        val player = engine.player ?: return
        // A double-tap seek is a deliberate jump, so it gets the firm confirmation rather than a
        // passing tick.
        Haptics.release(binding.gestureLayer)
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
        Haptics.release(binding.buttonAspect)
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
        // The view is the source of truth, not a mirror boolean: keeping a second copy is what let
        // the two disagree and made the first tap appear to do nothing.
        if (PlayerAnimation.isShown(binding.controlsOverlay)) hideControls() else showControls()
    }

    private fun showControls() {
        PlayerAnimation.show(binding.controlsOverlay)
    }

    private fun hideControls() {
        PlayerAnimation.hide(binding.controlsOverlay)
    }

    private var hideControlsRunnable: Runnable? = null

    /** Shows the chrome and (re)starts the idle countdown. Every interaction calls this. */
    private fun showControlsTemporarily(delayMs: Long = CONTROLS_TIMEOUT_MS) {
        showControls()
        hideControlsRunnable?.let { ambientHandler.removeCallbacks(it) }
        // While a sheet is up the chrome must stay put; the sheet's own dismissal restarts the
        // countdown, so an unattended timer here would hide it out from under the user.
        if (inspectorSheet.isOpen) {
            hideControlsRunnable = null
            return
        }
        val runnable = Runnable { hideControls() }
        hideControlsRunnable = runnable
        ambientHandler.postDelayed(runnable, delayMs)
    }

    /** Cancels the idle countdown, e.g. while an error card needs the controls to stay reachable. */
    private fun cancelControlsTimeout() {
        hideControlsRunnable?.let { ambientHandler.removeCallbacks(it) }
        hideControlsRunnable = null
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

    /**
     * Routes touches: anything inside the control chrome (or an open sheet) goes to the view that
     * owns it, and everything else - the picture, the letterbox, the empty parts of the overlay -
     * becomes a player gesture.
     *
     * This is done here rather than with an `OnTouchListener` on the gesture layer because the
     * controls overlay is a full-size sibling drawn above that layer and swallows its touches, so
     * gestures stopped working entirely whenever the chrome was visible.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!gesturesReady) return super.dispatchTouchEvent(ev)

        // A sheet is a separate window, so anything arriving here while it is open belongs to it.
        if (inspectorSheet.isOpen) return super.dispatchTouchEvent(ev)

        val inside = isInsideControls(ev.rawX, ev.rawY)
        if (!inside) {
            if (gestures.onTouchEvent(ev)) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True when the point falls on the control capsule, so its buttons keep priority. */
    private fun isInsideControls(rawX: Float, rawY: Float): Boolean {
        if (!PlayerAnimation.isShown(binding.controlsOverlay)) return false
        val capsule = binding.controlGlass
        if (capsule.width == 0 || capsule.height == 0) return false
        val loc = IntArray(2)
        capsule.getLocationOnScreen(loc)
        // A small bleed keeps the touch target comfortable at the edges of the pill.
        val bleed = 6f * resources.displayMetrics.density
        return rawX >= loc[0] - bleed && rawX <= loc[0] + capsule.width + bleed &&
            rawY >= loc[1] - bleed && rawY <= loc[1] + capsule.height + bleed
    }

    override fun onSingleTap() {
        if (inspectorSheet.isOpen) return
        // Revealing the chrome arms the idle countdown; tapping it away does not need one. Without
        // this the chrome stayed up forever after a tap, because only the playback callbacks ever
        // started the timer.
        if (PlayerAnimation.isShown(binding.controlsOverlay)) {
            hideControls()
        } else {
            showControlsTemporarily()
        }
    }

    override fun onDoubleTap(forward: Boolean) {
        // Length is user-configurable (1/3/5/10s); the controller does not own the value.
        val step = gestures.doubleTapJumpMs
        seekBy(if (forward) step else -step)
    }

    override fun onScrubStart() {
        volumeAccumulator = Float.MIN_VALUE
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
        val applied = next.coerceIn(0.01f, 1f)
        attrs.screenBrightness = applied
        window.attributes = attrs
        showFeedback(String.format(Locale.US, "BRI %.0f%%", applied * 100))
    }

    /**
     * Volume drag.
     *
     * The accumulated fraction is kept rather than rounding each event, because a single small
     * movement is usually less than one step of the stream volume scale - rounding per event made
     * slow drags do nothing at all and then jump.
     */
    override fun onVolumeDelta(delta: Float) {
        val manager = audioManager ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return

        if (volumeAccumulator == Float.MIN_VALUE) {
            volumeAccumulator = manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        }

        val exact = volumeAccumulator + delta * max
        val next = exact.toInt().coerceIn(0, max)
        if (next != manager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            Haptics.tick(binding.gestureLayer)
        }
        volumeAccumulator = exact.coerceIn(0f, max.toFloat())
        Log.d(TAG, "volDelta delta=$delta exact=$exact next=$next max=$max")
        showFeedback("VOL ${(next * 100) / max}%")
    }

    // ------------------------------------------------------------------ ambient + geometry

    /**
     * Gives the glass capsule something to refract.
     *
     * The source must be the SurfaceView holding the picture; anything else would sample the window
     * canvas, which does not contain the video.
     */
    private fun attachGlassBackdrop() {
        val surface = binding.playerView.videoSurfaceView as? android.view.SurfaceView ?: return
        binding.controlGlass.backdropSource = surface
        binding.controlGlass.start()
    }

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

    /**
     * Recomputes the picture geometry after a configuration change.
     *
     * The manifest handles orientation changes in-process (`configChanges="orientation|screenSize|…"`)
     * so playback is not interrupted by a rotation - which means the activity is *not* recreated, and
     * nothing afterwards recomputes where the picture sits. The ambient wash and the sampler kept
     * using the portrait geometry in landscape: reported as "ambient glow out of bounds when
     * switching between portrait and landscape".
     *
     * The geometry is recomputed here and again on the next layout pass, because at this point the
     * player view has not been measured at its new size yet.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateVideoRect()
        binding.controlsOverlay.post { updateVideoRect() }
        binding.playerView.post { updateVideoRect() }
    }

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
        rememberPlaybackPosition()
        ambientSampler?.stop()
    }

    /**
     * Records where this item is up to.
     *
     * Kept in memory rather than in preferences: the request is to remember the position until the
     * app quits, so persisting it across launches would be the opposite of what was asked.
     * A position at the very start or the very end is not worth remembering - the first would
     * resume where it already starts, and the second would make reopening a finished film look
     * like it did nothing.
     */
    private fun rememberPlaybackPosition() {
        val uri = mediaUri ?: return
        val player = engine.player ?: return
        val position = player.currentPosition
        val duration = player.duration
        if (position < RESUME_MIN_MS) return
        if (duration > 0 && position > duration - RESUME_END_GUARD_MS) {
            PlaybackMemory.forget(uri)
            return
        }
        PlaybackMemory.remember(uri, position)
    }

    override fun onDestroy() {
        rememberPlaybackPosition()
        refreshJob?.cancel()
        gestures.release()
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
        mediaTracks = MediaTracks.from(tracks)
        // A track change can flip which selection is active, so refresh any open control panel.
        when (inspectorSheet.shownPanel) {
            InspectorSheet.Panel.AUDIO -> populateAudioPanel()
            InspectorSheet.Panel.SUBTITLES -> populateSubtitlePanel()
            else -> Unit
        }
        refreshOpenReadoutPanel()
    }

    override fun onEngineVideoSize(size: VideoSize) {
        videoSize = size
        updateVideoRect()
    }

    override fun onEngineError(message: String, cause: Throwable?) {
        // A decoder failure that the engine is already retrying is not a failure yet: show it as a
        // transient notice so the user knows why playback paused, but do not raise the error card
        // for something that is about to recover.
        if (!engine.lastErrorIsTerminal) {
            showFeedback(message)
            return
        }

        binding.errorText.text = message
        // Surface the controls and stop the idle countdown: while an error is on screen the user
        // needs the dismiss affordance and the way out, not a chrome that fades away underneath it.
        showControls()
        cancelControlsTimeout()
        PlayerAnimation.show(binding.errorCard)
        Haptics.error(binding.root)
    }

    override fun onEngineFirstFrame() {
        updateVideoRect()
        attachGlassBackdrop()
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
        updateStreamChips()
    }

    private fun updateDuration() {
        val duration = engine.player?.duration ?: 0L
        if (duration > 0) {
            gestures.setDuration(duration)
            binding.timeRemaining.text = "-" + TextSpans.timecode(duration)
        }
    }

    /**
     * Stream facts as chips: container, size, HDR transfer function, channel layout and the decoder
     * actually in use.
     *
     * Rendering these as pills rather than a sentence keeps technical detail present without
     * turning the player chrome into a wall of text, and the accent on the HDR chip makes a
     * correctly tone-mapped stream obvious at a glance.
     */
    private fun updateStreamChips() {
        val player = engine.player
        val videoFormat = player?.videoFormat
        val audioFormat = player?.audioFormat
        val hdr = PlaybackReport.hdrLabel(videoFormat)

        val chips = ArrayList<ChipStrip.Chip>()

        chips += ChipStrip.Chip(
            mimeType ?: PlaybackReport.containerFor(displayName, null) ?: "video",
            ChipStrip.Tone.NEUTRAL,
        )

        if (sizeBytes > 0) {
            chips += ChipStrip.Chip(TextSpans.bytes(sizeBytes), ChipStrip.Tone.NEUTRAL)
        }

        if (hdr != null) {
            chips += ChipStrip.Chip(hdr, ChipStrip.Tone.ACTIVE)
        }

        videoFormat?.let { format ->
            if (format.width > 0 && format.height > 0) {
                chips += ChipStrip.Chip("${format.width}x${format.height}", ChipStrip.Tone.NEUTRAL)
            }
        }

        audioFormat?.let { format ->
            if (format.channelCount > 0) {
                val layout = PlaybackReport.channelLayout(format.channelCount)
                chips += ChipStrip.Chip(
                    layout.uppercase(),
                    // Multichannel is the case worth noticing: it is what the spatialiser needs.
                    if (format.channelCount > 2) ChipStrip.Tone.ACTIVE else ChipStrip.Tone.NEUTRAL,
                )
            }
        }

        // The decoder route is the single most useful diagnostic when something will not play.
        videoFormat?.sampleMimeType?.let { mime ->
            chips += ChipStrip.Chip(
                DecoderPolicy.describeRoute(mime, engine.decoderProfile).uppercase(),
                ChipStrip.Tone.NEUTRAL,
            )
        }

        binding.playerChips.setChips(chips)
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
            title = "Ask the system not to spatialise",
            // Deliberately worded as a request: the platform has the final say, and on the
            // reference device it keeps spatialising even when asked not to.
            subtitle = "The platform and connected headset make the final decision",
            selected = !engine.spatialAudioEnabled,
            enabled = true,
        )

        // One row per audio stream. A file with several dubs has to be switchable mid-playback.
        val audio = mediaTracks.audio
        if (audio.size > 1) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.AUDIO,
                value = VALUE_AUDIO_HEADER,
                title = "${audio.size} audio tracks",
                subtitle = "Selecting one keeps playing and switches the stream",
                selected = false,
                enabled = false,
                section = "Audio track",
            )
        }
        for ((index, track) in audio.withIndex()) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.AUDIO,
                value = "$VALUE_AUDIO_PREFIX${track.id}",
                title = track.label,
                subtitle = track.detail,
                selected = track.selected,
                enabled = track.supported,
                section = if (audio.size == 1 && index == 0) "Audio track" else null,
            )
        }

        sheet.setChoices(InspectorSheet.Panel.AUDIO, choices)
    }

    private fun populateSubtitlePanel() {
        val sheet = inspectorSheet
        val choices = ArrayList<InspectorSheet.Choice>()

        val textTracks = mediaTracks.text

        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = SUBTITLE_OFF,
            title = getString(R.string.subtitle_off),
            subtitle = "Hide subtitles for this item",
            selected = textTracks.none { it.selected },
            enabled = true,
            section = "Tracks",
        )

        for (track in textTracks) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SUBTITLES,
                value = "$VALUE_TEXT_PREFIX${track.id}",
                title = track.label,
                subtitle = track.detail,
                selected = track.selected,
                enabled = track.supported,
            )
        }

        if (textTracks.isEmpty()) {
            choices += InspectorSheet.Choice(
                panel = InspectorSheet.Panel.SUBTITLES,
                value = VALUE_TEXT_HEADER,
                title = "No embedded subtitle tracks",
                subtitle = "Add a sidecar file, or drop one next to the video",
                selected = false,
                enabled = false,
            )
        }

        // Styling sliders. Fixed subtitle geometry cannot suit portrait, a landscape window and a
        // TV-style layout at once.
        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = subtitleSizeSp.toString(),
            title = getString(R.string.subtitle_size),
            range = MIN_SUBTITLE_SP..MAX_SUBTITLE_SP,
            stepSize = 1f,
            section = "Styling",
        )
        choices += InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SUBTITLES,
            value = (subtitlePositionFraction * 100f).toString(),
            title = getString(R.string.subtitle_bottom_padding),
            range = 0f..45f,
            stepSize = 1f,
            section = null,
        )

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
        ) + InspectorSheet.Choice(
            panel = InspectorSheet.Panel.SCALING,
            value = doubleTapJumpMs.toString(),
            title = getString(R.string.double_tap_jump),
            subtitle = "Double tap the left half to go back, the right half to go forward",
            // A slider over the four lengths people actually ask for; 10s is the default.
            range = 1f..10f,
            stepSize = 0.5f,
            section = "Gestures",
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
                tracks = mediaTracks.all,
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
                tracks = mediaTracks.all,
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

            choice.value.startsWith(VALUE_AUDIO_PREFIX) -> {
                val key = choice.value.removePrefix(VALUE_AUDIO_PREFIX)
                mediaTracks.resolve(key)?.let { applyTrackChoice(it) }
                populateAudioPanel()
            }
        }
    }

    private fun handleSubtitleChoice(choice: InspectorSheet.Choice) {
        // Slider rows carry a numeric reading rather than one of the fixed action values, so they
        // are matched first. A slider is identified by having a range.
        if (choice.range != null) {
            val value = choice.value.toFloatOrNull() ?: return
            when (choice.title) {
                getString(R.string.subtitle_size) -> subtitleSizeSp = value
                getString(R.string.subtitle_bottom_padding) ->
                    subtitlePositionFraction = (value / 100f).coerceIn(0f, 0.45f)
            }
            applySubtitleStyle()
            return
        }

        when {
            choice.value == SUBTITLE_OFF -> {
                val player = engine.player
                if (player != null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                }
                engine.applyPreferredSubtitleLanguage(null)
                Haptics.touch(binding.gestureLayer)
            }
            choice.value == "add_external" -> {
                pickSubtitle.launch(arrayOf("application/x-subrip", "text/*", "*/*"))
                return
            }

            choice.value.startsWith(VALUE_TEXT_PREFIX) -> {
                val key = choice.value.removePrefix(VALUE_TEXT_PREFIX)
                mediaTracks.resolve(key)?.let { applyTrackChoice(it) }
            }
        }
        populateSubtitlePanel()
    }

    private fun handleScalingChoice(choice: InspectorSheet.Choice) {
        // Slider rows report a numeric reading rather than an action token, so they are matched on
        // their title before the value-based cases below.
        if (choice.range != null && choice.title == getString(R.string.double_tap_jump)) {
            val ms = (choice.value.toFloatOrNull() ?: 10f) * 1000f
            doubleTapJumpMs = ms.toLong()
            return
        }

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
     * Applies a chosen track.
     *
     * Selection is expressed as a [TrackSelectionOverride] over the *specific* track group the
     * track belongs to, with its index inside that group. A flat list index would be wrong as soon
     * as an item has more than one group (which is exactly the multi-stream case), so the track
     * carries its own identity.
     *
     * The choice is also remembered as a language preference, so it survives the decoder rebuild
     * that a policy change triggers.
     */
    private fun applyTrackChoice(track: MediaTrack) {
        val player = engine.player ?: return

        // MediaCodec/Media3 needs the group to still be current; a stale reference after a rebuild
        // is dropped rather than applied to the wrong stream.
        val current = mediaTracks.all.firstOrNull {
            it.group.id == track.group.id && it.trackIndex == track.trackIndex
        } ?: track

        val updated = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(current.toOverride())
            .build()
        player.trackSelectionParameters = updated

        Haptics.touch(binding.gestureLayer)

        when (track.type) {
            MediaTracks.TYPE_AUDIO -> track.language?.let { engine.applyPreferredAudioLanguage(it) }
            MediaTracks.TYPE_TEXT -> track.language?.let { engine.applyPreferredSubtitleLanguage(it) }
            else -> Unit
        }
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

        private const val VALUE_AUDIO_PREFIX = "audiotrack_"
        private const val VALUE_TEXT_PREFIX = "texttrack_"
        private const val VALUE_AUDIO_HEADER = "audio_header"
        private const val VALUE_TEXT_HEADER = "text_header"

        private const val TAG = "PlayerActivity"

        /** Corner radius of the floating control capsule. */
        private const val CONTROL_GLASS_RADIUS_DP = 30f

        /** Refracting bevel and bend distance for the control capsule, in dp. */
        private const val CONTROL_GLASS_BEVEL_DP = 16f
        private const val CONTROL_GLASS_REFRACT_DP = 12f

        /** Vertical drag sensitivity handed to the gesture controller. */
        private const val DEFAULT_VERTICAL_GAIN = 0.30f

        /** Double-tap seek range and default, in ms. */
        private const val DEFAULT_DOUBLE_TAP_JUMP_MS = 10_000L
        private const val MIN_DOUBLE_TAP_JUMP_MS = 1_000L
        private const val MAX_DOUBLE_TAP_JUMP_MS = 30_000L
        private const val KEY_DOUBLE_TAP_JUMP_MS = "double_tap_jump_ms"

        /** Below this the position is not worth restoring. */
        private const val RESUME_MIN_MS = 15_000L

        /** Within this of the end, treat the item as finished rather than resuming it. */
        private const val RESUME_END_GUARD_MS = 20_000L

        /** How long the chrome stays up after an interaction. */
        private const val CONTROLS_TIMEOUT_MS = 6_000L

        /** Subtitle defaults and the range the sliders expose. */
        private const val DEFAULT_SUBTITLE_SP = 18f
        private const val MIN_SUBTITLE_SP = 10f
        private const val MAX_SUBTITLE_SP = 40f
        private const val DEFAULT_SUBTITLE_POSITION = 0.08f
    }
}
