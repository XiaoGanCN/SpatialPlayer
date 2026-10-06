package com.gan.spatialplayer.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Owns the ExoPlayer instance and everything that has to agree with it: decoder policy, buffer
 * policy, audio attributes (which is what opts this app into Android's spatializer), track
 * preferences and the subtitle configuration.
 *
 * The class is deliberately the single place that touches ExoPlayer, so that switching decoder
 * policy - which needs a whole new renderer stack - is an atomic rebuild here rather than a
 * scattered mutation across the UI.
 */
class PlayerEngine(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onEnginePlaybackState(state: Int)
        fun onEngineIsPlaying(playing: Boolean)
        fun onEngineTracksChanged(tracks: Tracks)
        fun onEngineVideoSize(size: VideoSize)
        fun onEngineError(message: String, cause: Throwable?)
        fun onEngineFirstFrame()
        fun onEnginePlaybackParameters(speed: Float)
    }

    private val trackSelector = DefaultTrackSelector(context)

    var player: ExoPlayer? = null
        private set

    var decoderProfile: DecoderProfile = DecoderProfile.DEFAULT
        private set

    /** Set false to let the platform leave audio exactly where the app put it. */
    var spatialAudioEnabled: Boolean = true
        private set

    private var currentMedia: MediaItem? = null
    private var subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()

    /** Preferred audio language tag, "und" meaning "no preference". */
    var preferredAudioLanguageCode: String = "und"

    /** Preferred subtitle language tag; null means do not auto-select subtitles. */
    var preferredSubtitleLanguageCode: String? = null

    fun build() {
        release()

        val renderersFactory = DecoderPolicy.renderersFactory(context, decoderProfile)
        configureRenderers(renderersFactory)

        val audioAttributes = buildAudioAttributes()
        val loadControl = buildLoadControl()

        val exo = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .setUseLazyPreparation(true)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()

        exo.addListener(playerListener)
        exo.addAnalyticsListener(analyticsListener)
        exo.setTrackSelectionParameters(buildTrackSelectionParameters())
        player = exo
    }

    private fun configureRenderers(factory: DefaultRenderersFactory) {
        // FFmpeg's audio decoders emit float PCM and can exceed the default 2-channel assumption.
        factory.setEnableAudioFloatOutput(true)
        factory.setEnableAudioTrackPlaybackParams(true)
    }

    /**
     * Audio attributes double as the spatial-audio opt-in.
     *
     * `SPATIALIZATION_BEHAVIOR_AUTO` means "spatialise this if you can, and if the user wants it".
     * An app cannot force it on and cannot force head tracking on - both stay under system control,
     * which is why the player only reports what the platform decided.
     *
     * `isContentSpatialized` must stay **false**. It means "this content is already binaural", i.e.
     * a pre-rendered spatial mix that the platform should pass through untouched. Setting it true
     * on ordinary 5.1 makes the system skip spatialisation entirely - measured on the reference
     * device, that alone was the difference between `isSpatialized=false` (no head tracking) and
     * `isSpatialized=true`. A bare `AudioTrack` carrying only the behaviour flag spatialised 5.1
     * correctly, which is what isolated the cause.
     */
    private fun buildAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .setSpatializationBehavior(
                if (spatialAudioEnabled) C.SPATIALIZATION_BEHAVIOR_AUTO
                else C.SPATIALIZATION_BEHAVIOR_NEVER,
            )
            .setIsContentSpatialized(false)
            .build()

    /**
     * Buffer sizing. A local movie file wants a deep buffer so a decoder hiccup (a DTS-HD burst, a
     * scene change) never reaches the speakers, but not so deep that seeking feels sluggish.
     */
    private fun buildLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 20_000,
                /* maxBufferMs= */ 90_000,
                /* bufferForPlaybackMs= */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 2_000,
            )
            .setTargetBufferBytes(96 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(/* backBufferDurationMs= */ 15_000, /* retainBackBufferFromKeyframe= */ true)
            .build()

    /**
     * Track preferences. The one that matters for this app is leaving channel count unconstrained
     * so a 5.1 TrueHD track is chosen over its 2.0 compatibility mix - the spatializer needs the
     * multichannel signal to have anything to place.
     */
    private fun buildTrackSelectionParameters(): TrackSelectionParameters {
        val builder = TrackSelectionParameters.Builder()
            .setPreferredAudioLanguage(preferredAudioLanguageCode)
            .setMaxAudioChannelCount(8)

        val subtitleLanguage = preferredSubtitleLanguageCode
        if (subtitleLanguage != null) {
            // Ask for this language, and also accept subtitles whose language is undeclared -
            // external sidecars frequently have no language tag at all.
            builder.setPreferredTextLanguage(subtitleLanguage)
            builder.setSelectUndeterminedTextLanguage(true)
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ playback

    fun setMedia(uri: Uri, mimeType: String? = null) {
        val builder = MediaItem.Builder().setUri(uri)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subtitleConfigurations.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigurations)
        }
        val item = builder.build()
        currentMedia = item
        player?.setMediaItem(item)
    }

    /** External subtitle sidecar files to attach to the current media item. */
    fun setExternalSubtitles(configs: List<MediaItem.SubtitleConfiguration>) {
        subtitleConfigurations = configs
        currentMedia?.let { existing ->
            val rebuilt = existing.buildUpon()
                .setSubtitleConfigurations(configs)
                .build()
            currentMedia = rebuilt
            val position = player?.currentPosition ?: 0L
            val playing = player?.isPlaying == true
            player?.setMediaItem(rebuilt, position)
            player?.prepare()
            if (playing) player?.play()
        }
    }

    fun prepare() {
        player?.prepare()
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        player?.let {
            it.removeListener(playerListener)
            it.removeAnalyticsListener(analyticsListener)
            it.release()
        }
        player = null
    }

    /**
     * Switches decoder policy. Media3 fixes its renderer stack at construction time, so this
     * rebuilds the player and restores position, play state and the media item.
     */
    fun switchDecoderProfile(profile: DecoderProfile) {
        if (profile == decoderProfile && player != null) return
        decoderProfile = profile
        rebuildPreservingState()
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        if (enabled == spatialAudioEnabled) return
        spatialAudioEnabled = enabled
        rebuildPreservingState()
    }

    private fun rebuildPreservingState() {
        val position = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying == true
        val media = currentMedia

        build()
        if (media != null) {
            player?.setMediaItem(media, position)
            player?.prepare()
            if (wasPlaying) player?.play()
        }
    }

    fun applyPreferredAudioLanguage(language: String) {
        preferredAudioLanguageCode = language
        player?.setTrackSelectionParameters(buildTrackSelectionParameters())
    }

    fun applyPreferredSubtitleLanguage(language: String?) {
        preferredSubtitleLanguageCode = language
        player?.setTrackSelectionParameters(buildTrackSelectionParameters())
    }

    // ------------------------------------------------------------------ state readout

    /** Snapshot for the inspection screen. */
    fun sample(): PlayerSample {
        val exo = player ?: return PlayerSample.EMPTY
        val videoFormat = exo.videoFormat
        val audioFormat = exo.audioFormat
        val audioCounters = exo.audioDecoderCounters
        val videoCounters = exo.videoDecoderCounters

        return PlayerSample(
            state = exo.playbackState,
            positionMs = exo.currentPosition,
            durationMs = exo.duration,
            bufferedMs = exo.bufferedPosition,
            isPlaying = exo.isPlaying,
            playbackSpeed = exo.playbackParameters.speed,
            videoCodecName = videoFormat?.sampleMimeType?.let { DecoderPolicy.describeRoute(it, decoderProfile) } ?: "—",
            audioCodecName = audioFormat?.sampleMimeType?.let { DecoderPolicy.describeRoute(it, decoderProfile) } ?: "—",
            videoMime = videoFormat?.sampleMimeType ?: "—",
            audioMime = audioFormat?.sampleMimeType ?: "—",
            audioChannelCount = audioFormat?.channelCount ?: 0,
            audioSampleRate = audioFormat?.sampleRate ?: 0,
            videoWidth = videoFormat?.width ?: 0,
            videoHeight = videoFormat?.height ?: 0,
            videoFrameRate = videoFormat?.frameRate ?: 0f,
            droppedFrames = videoCounters?.droppedBufferCount ?: 0,
            renderedFrames = videoCounters?.renderedOutputBufferCount ?: 0,
            decoderInitCount = (videoCounters?.decoderInitCount ?: 0) + (audioCounters?.decoderInitCount ?: 0),
            audioUnderruns = 0,
            playerError = exo.playerError?.message,
        )
    }

    // ------------------------------------------------------------------ listeners

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            listener.onEnginePlaybackState(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener.onEngineIsPlaying(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            listener.onEngineTracksChanged(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            listener.onEngineVideoSize(videoSize)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            listener.onEngineError(error.message ?: error.errorCodeName, error)
        }

        override fun onRenderedFirstFrame() {
            listener.onEngineFirstFrame()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            listener.onEnginePlaybackParameters(playbackParameters.speed)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            audioUnderrunCount++
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            listener.onEngineError("audio sink: ${audioSinkError.message}", audioSinkError)
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            listener.onEngineError("video codec: ${videoCodecError.message}", videoCodecError)
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            listener.onEngineError("audio codec: ${audioCodecError.message}", audioCodecError)
        }
    }

    private var audioUnderrunCount: Int = 0

    /** Number of audio underruns observed since construction - a real stutter indicator. */
    fun audioUnderruns(): Int = audioUnderrunCount

    // ------------------------------------------------------------------ helpers

    /** Whether audio is currently leaving through a headset rather than the speaker. */
    fun currentOutputDevice(): AudioDeviceInfo? = DeviceCapabilities.currentOutputDevice(context)

    companion object {
        const val SEEK_STEP_MS = 10_000L

        /** MIME hints for containers the picker cannot describe. */
        fun mimeForExtension(extension: String?): String? = when (extension?.lowercase()) {
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts", "m2ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> MimeTypes.VIDEO_AVI
            // Media3 1.8 has no VIDEO_QUICK_TIME constant; the string is stable.
            "mov" -> "video/quicktime"
            "mpg", "mpeg" -> MimeTypes.VIDEO_MPEG
            else -> null
        }
    }
}

/** Immutable readout of engine state for the inspection overlay. */
data class PlayerSample(
    val state: Int,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val videoCodecName: String,
    val audioCodecName: String,
    val videoMime: String,
    val audioMime: String,
    val audioChannelCount: Int,
    val audioSampleRate: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val videoFrameRate: Float,
    val droppedFrames: Int,
    val renderedFrames: Int,
    val decoderInitCount: Int,
    val audioUnderruns: Int,
    val playerError: String?,
) {
    companion object {
        val EMPTY = PlayerSample(
            state = Player.STATE_IDLE,
            positionMs = 0L,
            durationMs = 0L,
            bufferedMs = 0L,
            isPlaying = false,
            playbackSpeed = 1f,
            videoCodecName = "—",
            audioCodecName = "—",
            videoMime = "—",
            audioMime = "—",
            audioChannelCount = 0,
            audioSampleRate = 0,
            videoWidth = 0,
            videoHeight = 0,
            videoFrameRate = 0f,
            droppedFrames = 0,
            renderedFrames = 0,
            decoderInitCount = 0,
            audioUnderruns = 0,
            playerError = null,
        )
    }
}
