package com.gan.spatialplayer.media

import com.gan.spatialplayer.SettingsStore

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
    listener: Listener?,
) {

    /**
     * Where state changes are reported.
     *
     * Settable, and nullable, because this engine can outlive the screen that created it: music is
     * allowed to keep playing with the app in the background, and the activity that owns the views
     * must not be held alive by a callback list while that happens. The activity attaches itself in
     * `onStart` and detaches in `onDestroy`.
     */
    var listener: Listener? = listener

    interface Listener {
        fun onEnginePlaybackState(state: Int)
        fun onEngineIsPlaying(playing: Boolean)
        fun onEngineTracksChanged(tracks: Tracks)
        fun onEngineVideoSize(size: VideoSize)
        fun onEngineError(message: String, cause: Throwable?)
        fun onEngineFirstFrame()
        fun onEnginePlaybackParameters(speed: Float)

        /**
         * The output refused [from] channels and the player is retrying with [to].
         *
         * Its own callback rather than an error: nothing went wrong, the sink is simply narrower than
         * its advertised capability, and the user is entitled to know why their 7.1 film is coming out
         * as 5.1 instead of having to guess.
         */
        fun onEngineAudioDownmixed(from: Int, to: Int)
    }

    private val trackSelector = DefaultTrackSelector(context)

    var player: ExoPlayer? = null
        private set

    private val settings = SettingsStore(context)

    /** Which renderer ordering to build. Read from settings so a choice survives the app. */
    var decoderProfile: DecoderProfile = runCatching {
        DecoderProfile.valueOf(settings.decoderProfile)
    }.getOrDefault(DecoderProfile.DEFAULT)
        private set

    /** Set false to let the platform leave audio exactly where the app put it. */
    var spatialAudioEnabled: Boolean = settings.spatialEnabled
        private set

    /** Whether the manual matrix is in use instead of the presets. */
    var upmixAdvanced: Boolean = settings.upmixAdvanced
        private set

    /** The manual mapping; only consulted when [upmixAdvanced] is set. */
    var upmixMatrix: UpmixMatrix = UpmixMatrix.decode(settings.upmixMatrix)
        private set

    /** Which preset the stereo upmix uses when the manual matrix is not in use. */
    var upmixMode: UpmixMode = runCatching {
        UpmixMode.valueOf(settings.upmixMode)
    }.getOrDefault(UpmixMode.SURROUND)
        private set

    /** What the sink is actually built with. */
    private val effectiveUpmixMode: UpmixMode
        get() = if (upmixAdvanced) UpmixMode.ADVANCED else upmixMode

    private var currentMedia: MediaItem? = null

    /** What is loaded right now, so a screen reopening from the notification can avoid a reload. */
    val currentMediaUri: android.net.Uri?
        get() = currentMedia?.localConfiguration?.uri

    val currentMediaMime: String?
        get() = currentMedia?.localConfiguration?.mimeType

    /**
     * Whether a decoder failure should automatically retry with a different renderer stack.
     *
     * Off means failures surface immediately; the user can still change the profile by hand.
     */
    var autoFallbackEnabled: Boolean = true

    /** Human-readable detail for the most recent failure, shown on the error card and in reports. */
    var lastErrorDetail: String? = null
        private set

    /**
     * Whether the most recent [Listener.onEngineError] is a final failure.
     *
     * A retry also reports an error, but the user should not be shown a failure card for something
     * the player is already recovering from. The host reads this to decide between a transient
     * notice and the card.
     */
    var lastErrorIsTerminal: Boolean = true
        private set

    /** The profile that was tried when the current failure occurred. */
    private var fallbackTried: MutableSet<DecoderProfile> = mutableSetOf()

    /**
     * Channel cap forced after the sink refused a higher one.
     *
     * The platform advertises more channels than the active output will actually open, so a refusal
     * has to be remembered - otherwise rebuilding would ask for the same rejected layout again.
     */
    private var forcedChannelCap: Int? = null

    private var subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList()

    /** Preferred audio language tag, "und" meaning "no preference". */
    var preferredAudioLanguageCode: String = "und"

    /** Preferred subtitle language tag; null means do not auto-select subtitles. */
    var preferredSubtitleLanguageCode: String? = null

    /**
     * Highest channel count the track selector may choose.
     *
     * 8 admits 7.1. If a sink cannot take that many channels the failure does not appear as a track
     * selection problem, so this is only lowered deliberately (or by the 7.1 retry below).
     */
    var maxAudioChannelCount: Int = AudioOutputCapability.MAX_CHANNELS
        private set

    /**
     * Re-reads the output capability and applies it.
     *
     * Called at build time and whenever the output device changes. The channel cap has to be right
     * *before* the sink is created, because a sink that refuses a channel count fails the whole
     * playback - the 7.1-over-Bluetooth case.
     */
    fun refreshOutputCapability() {
        val channels = AudioOutputCapability.verifiedMaxChannels(context)
        if (channels == maxAudioChannelCount) return
        maxAudioChannelCount = channels
        // The selector reads this at prepare time, so a running player needs its parameters updated.
        player?.trackSelectionParameters = buildTrackSelectionParameters()
    }

    /** Invoked after [build] replaces the player, so a media session can re-point at the new one. */
    var onPlayerChanged: (() -> Unit)? = null

    fun build() {
        release()

        // Must be decided before the sink exists: a refused channel count fails playback outright
        // rather than degrading, which is what happened with a 7.1 Atmos remux over Bluetooth.
        maxAudioChannelCount = forcedChannelCap
            ?: AudioOutputCapability.verifiedMaxChannels(context)

        val renderersFactory = DecoderPolicy.renderersFactory(
            context,
            decoderProfile,
            spatialAudioEnabled,
            effectiveUpmixMode,
            upmixMatrix,
        )
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
        onPlayerChanged?.invoke()
    }

    /**
     * Whether the current item carries a picture, or `null` while that is not yet known.
     *
     * Read from the tracks rather than from the mime type, because a Matroska file may hold either and
     * the container tells you nothing.
     */
    val hasVideoTrack: Boolean?
        get() {
            val tracks = player?.currentTracks ?: return null
            if (tracks.groups.isEmpty()) return null
            return tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        }

    /**
     * Whether the tracks say there is a picture. Unknown is `false`, not "assume video": a caller that
     * needs to be sure must look at [hasVideoTrack] and decide for itself, because the two ways of
     * being wrong are not equally bad. Assuming video pauses music; assuming audio lets a film run
     * behind a blank screen for the fraction of a second before the tracks arrive - and the caller can
     * simply look again when they do.
     */
    val hasVideo: Boolean
        get() = hasVideoTrack ?: hasVideoFormat

    /**
     * Whether the video renderer is presenting a picture.
     *
     * The fallback for [hasVideoTrack], and the more reliable of the two on the reference device:
     * `currentTracks` was measured **empty** for a plain MP4 that was visibly playing, while the video
     * format was populated - which is why the chips showed a resolution for a file whose tracks
     * appeared to be unknown.
     */
    val hasVideoFormat: Boolean
        get() = player?.videoFormat != null

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
            // 8 admits 7.1 (TrueHD Atmos on a UHD remux is 8 discrete channels). The selector refuses
            // to pick a track above this, so capping it lower would silently force a downmix.
            .setMaxAudioChannelCount(maxAudioChannelCount)

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

    /**
     * Loads an item.
     *
     * [title], [artist] and [album] become the item's `MediaMetadata`, which is what the media
     * notification, the lock screen and the media centre display. Without them the system has nothing
     * to label the session with, and the notification was left showing whichever item it last had a
     * description for rather than the one playing.
     */
    fun setMedia(
        uri: Uri,
        mimeType: String? = null,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ) {
        val builder = MediaItem.Builder().setUri(uri)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (title != null || artist != null || album != null) {
            val metadata = MediaMetadata.Builder()
            title?.let { metadata.setTitle(it) }
            artist?.let { metadata.setArtist(it) }
            album?.let { metadata.setAlbumTitle(it) }
            builder.setMediaMetadata(metadata.build())
        }
        if (subtitleConfigurations.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigurations)
        }
        val item = builder.build()
        currentMedia = item
        resetFallbackState()
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

    /**
     * Starts or resumes.
     *
     * A finished item is rewound first: `play()` on a player sitting at the end of its timeline sets
     * playWhenReady and then does nothing at all, which is what made the play button appear dead once
     * a track had run out.
     */
    fun play() {
        if (player?.playbackState == Player.STATE_ENDED) {
            player?.seekTo(0)
        }
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
        settings.decoderProfile = profile.name
        rebuildPreservingState()
    }

    /**
     * Asks the platform to spatialise, or not to.
     *
     * This sets `SPATIALIZATION_BEHAVIOR_AUTO` / `SPATIALIZATION_BEHAVIOR_NEVER` on the audio
     * attributes, which requires rebuilding the player because Media3 fixes the attribute set at
     * construction.
     *
     * **It is a request, not a command.** Measured on the reference device: with `NEVER` set and the
     * player rebuilt, `dumpsys audio` still reports `isSpatialized=true` for the 5.1 track. That is
     * expected - Android exposes no way for an app to turn platform spatialisation off. `Spatializer`
     * has only read-only members (`isEnabled`, `isAvailable`, `canBeSpatialized`, listeners); the
     * decision belongs to the system and the connected headset. The toggle therefore changes what
     * the app asks for, and the live platform state is shown next to it so the two are never
     * confused.
     */
    /**
     * Changes the stereo mapping.
     *
     * The mode is read when the sink is built, so this rebuilds - the same cost as the spatial
     * toggle, and for the same reason.
     */
    fun switchUpmixMode(mode: UpmixMode) {
        if (mode == upmixMode && !upmixAdvanced && player != null) return
        upmixMode = mode
        upmixAdvanced = false
        settings.upmixMode = mode.name
        settings.upmixAdvanced = false
        rebuildPreservingState()
    }

    /**
     * Re-reads the mapping from settings and applies it.
     *
     * Called by the settings screen itself, not only when the player resumes: the engine is one
     * object shared by both screens, so a change can be heard while the settings are still open
     * rather than after backing out and reopening the item.
     */
    fun reloadUpmixSettings() {
        val mode = runCatching { UpmixMode.valueOf(settings.upmixMode) }
            .getOrDefault(UpmixMode.SURROUND)
        switchUpmix(settings.upmixAdvanced, mode, UpmixMatrix.decode(settings.upmixMatrix))
    }

    /**
     * Switches between the presets and the manual matrix.
     *
     * One entry point for both, because they are one choice: the mapping the sink is built with is
     * either a preset or the matrix, never a mixture, and two setters would let a caller produce a
     * state that neither screen can represent.
     */
    fun switchUpmix(advanced: Boolean, mode: UpmixMode, matrix: UpmixMatrix) {
        if (advanced == upmixAdvanced && mode == upmixMode && matrix == upmixMatrix && player != null) {
            return
        }
        upmixAdvanced = advanced
        upmixMode = mode
        upmixMatrix = matrix
        settings.upmixAdvanced = advanced
        settings.upmixMode = mode.name
        settings.upmixMatrix = matrix.encode()
        rebuildPreservingState()
    }

    fun setSpatialAudioEnabled(enabled: Boolean) {
        if (enabled == spatialAudioEnabled) return
        spatialAudioEnabled = enabled
        settings.spatialEnabled = enabled
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

    /**
     * Pushes the spatial attributes back onto the player after the audio sink was rebuilt.
     *
     * `setAudioAttributes(..., handleAudioFocus = false)` deliberately does not re-request focus:
     * the seek did not change who owns audio, and asking again can make the player duck or pause.
     */
    private fun reassertSpatialAttributes() {
        val exo = player ?: return

        // Nothing to carry across if no audio is playing.
        if (exo.audioFormat == null) return

        // Failures here are deliberately swallowed.
        //
        // This is a best-effort repair for an intermittent platform behaviour, and reporting a
        // failure turned it into a user-visible error for a problem that did not otherwise exist -
        // the previous version raised "could not re-apply spatial audio" through the error path,
        // which surfaced as an error on exactly the renderer stacks where the sink is mid-rebuild
        // when a seek lands. A repair that cannot be observed to fail should not be able to create a
        // failure of its own.
        runCatching { exo.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus= */ false) }
    }

    /**
     * True when the error is the output sink refusing the channel layout.
     *
     * Matched on the message because Media3 reports it as an audio sink error with the native
     * `AudioTrack init failed` text; the error code alone (`ERROR_CODE_AUDIO_TRACK_INIT_FAILED`) is
     * also accepted when the platform supplies it.
     */
    private fun isAudioSinkChannelFailure(error: androidx.media3.common.PlaybackException): Boolean {
        val message = (error.message ?: "") + " " + (error.cause?.message ?: "")
        val mentionsInit = message.contains("AudioTrack init failed", ignoreCase = true) ||
            message.contains("init failed", ignoreCase = true)
        val codeMatches = error.errorCode == androidx.media3.common.PlaybackException
            .ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        return codeMatches || mentionsInit
    }

    /**
     * Rebuilds with a lower channel cap after the sink refused the current one.
     *
     * Steps 8 -> 6 -> 2, which covers 7.1 refused outright, 7.1 accepted but 5.1 preferred, and an
     * output that can only take stereo. Each step is attempted once.
     *
     * @return true when a retry was started.
     */
    private fun downgradeChannelsForSink(): Boolean {
        val refused = maxAudioChannelCount
        val next = when {
            maxAudioChannelCount > 6 -> 6
            maxAudioChannelCount > 2 -> 2
            else -> return false
        }
        // Remember the refusal so build() does not immediately raise the cap back to its advertised
        // value - the platform claims a capability the sink then rejects.
        forcedChannelCap = next

        val position = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying == true
        val media = currentMedia ?: return false

        build()
        player?.setMediaItem(media, position)
        player?.prepare()
        if (wasPlaying) player?.play()

        lastErrorIsTerminal = false
        listener?.onEngineAudioDownmixed(from = refused, to = next)
        return true
    }

    /**
     * Moves to the next decoder tier after a failure, restoring position and play state.
     *
     * The order is chosen so the cheapest change happens first: keep the user's own preference,
     * then fall back to software video (which rescues an unsupported profile or an over-level
     * bitstream), and finally to audio only, which at least keeps the soundtrack playing rather than
     * failing outright. Each tier is attempted once, so a file that cannot be decoded at all reaches
     * the error card quickly instead of looping.
     *
     * @return true when a retry was started, false when the chain is exhausted.
     */
    private fun advanceFallbackTier(): Boolean {
        lastErrorIsTerminal = false
        fallbackTried += decoderProfile
        val next = fallbackOrder.firstOrNull { it !in fallbackTried } ?: return false

        val position = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying == true
        val media = currentMedia ?: return false

        decoderProfile = next
        build()
        player?.setMediaItem(media, position)
        player?.prepare()
        if (wasPlaying) player?.play()

        // Built outside the string template: Kotlin cannot contain a nested quoted literal inside
        // an interpolation, and the profile name needs its underscores turned into spaces.
        val tierName = next.name.lowercase().replace('_', ' ')
        val detail = lastErrorDetail?.let { " ($it)" }.orEmpty()
        listener?.onEngineError("decoder failed, retrying with $tierName$detail", null)
        return true
    }

    /**
     * The fallback ladder.
     *
     * Ordered by how likely the change is to help without costing anything: software video is the
     * common rescue, audio-only is the last resort that still plays something.
     */
    private val fallbackOrder: List<DecoderProfile> = listOf(
        DecoderProfile.FFMPEG_VIDEO,
        DecoderProfile.FFMPEG_AUDIO,
        DecoderProfile.HARDWARE_ONLY,
        DecoderProfile.FFMPEG_ONLY,
    )

    /**
     * Turns a [PlaybackException] into something worth showing.
     *
     * The default message is often just "Source error", which tells the user nothing and makes the
     * report useless. The error code name, the numeric code and - most usefully - the format that
     * could not be handled are all included, because those are what identify the actual cause.
     */
    private fun describeError(error: androidx.media3.common.PlaybackException): String {
        val parts = ArrayList<String>()
        parts += error.errorCodeName
        parts += "(code ${error.errorCode})"

        val failing = failingFormat()
        if (failing != null) {
            val bits = ArrayList<String>()
            failing.sampleMimeType?.let { bits += it }
            failing.codecs?.let { bits += it }
            if (failing.width > 0 && failing.height > 0) bits += "${failing.width}x${failing.height}"
            if (failing.channelCount > 0) bits += "${failing.channelCount}ch"
            if (failing.frameRate > 0) bits += "${failing.frameRate}fps"
            if (bits.isNotEmpty()) parts += "[" + bits.joinToString(" ") + "]"
        }

        val cause = error.cause?.message?.takeIf { it.isNotBlank() }
        if (cause != null) parts += "- $cause"

        return parts.joinToString(" ")
    }

    /**
     * Best guess at the format that failed.
     *
     * Media3 does not attach the offending track to the exception, so the most recently reported
     * audio and video formats are the closest available evidence. A decoder failure is far more often
     * video than audio, so video is reported when both exist.
     */
    private fun failingFormat(): androidx.media3.common.Format? =
        player?.videoFormat ?: player?.audioFormat

    /** Clears the failure memo so a fresh item is not judged by the previous one's history. */
    private fun resetFallbackState() {
        fallbackTried.clear()
        lastErrorDetail = null
        lastErrorIsTerminal = true
        // A new item should be judged on the output as it is now, not on an earlier refusal.
        forcedChannelCap = null
    }

    // ------------------------------------------------------------------ listeners

    private val playerListener = object : Player.Listener {
        /**
         * Re-asserts the spatial audio configuration whenever the playhead jumps.
         *
         * A seek tears down and rebuilds the audio sink. The sink is what carries the spatialisation
         * attributes to the platform, and the rebuild does not always carry them across - reported as
         * "head tracking sometimes stops after repositioning the playhead", which matches an
         * intermittent rebuild. Re-applying the attributes is cheap and idempotent, so doing it on
         * every seek is a safe way to close a race that is not reliably reproducible.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            reassertSpatialAttributes()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            listener?.onEnginePlaybackState(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onEngineIsPlaying(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            listener?.onEngineTracksChanged(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            listener?.onEngineVideoSize(videoSize)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            lastErrorDetail = describeError(error)

            // An audio sink that cannot open its channel count is not a decoder problem, so the
            // decoder fallback ladder below cannot help. Capping the channels and rebuilding does,
            // and it is what turns a failed 7.1 film into a playing one over Bluetooth.
            if (isAudioSinkChannelFailure(error) && downgradeChannelsForSink()) return

            // Try the next decoder tier before giving up. A remux with a Dolby Vision profile 7 base
            // layer, or a bitstream above what the hardware decoder advertises, fails in the decoder
            // rather than in the container - and the same file often plays once the renderer stack
            // changes. Only fall back when the player is already prepared, so a genuine "file is
            // unreadable" error is not retried forever.
            if (autoFallbackEnabled && advanceFallbackTier()) return

            lastErrorIsTerminal = true
            listener?.onEngineError(lastErrorDetail ?: error.errorCodeName, error)
        }

        override fun onRenderedFirstFrame() {
            listener?.onEngineFirstFrame()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            listener?.onEnginePlaybackParameters(playbackParameters.speed)
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
            listener?.onEngineError("audio sink: ${audioSinkError.message}", audioSinkError)
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            listener?.onEngineError("video codec: ${videoCodecError.message}", videoCodecError)
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            listener?.onEngineError("audio codec: ${audioCodecError.message}", audioCodecError)
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
        /**
         * Container mime type for a file name.
         *
         * Audio is here as well as video, and that is not cosmetic: this is the type handed to the
         * player for anything opened from a folder, and it is the only thing that decides whether a
         * row in the library gets the square cover slot or the 16:9 frame slot when the source
         * supplies no type of its own. A missing entry is therefore visible - `.aiff` was absent, so
         * an AIFF was laid out as video and its art was cropped into a letterbox.
         *
         * Media3 1.8 has no constant for QuickTime or AIFF; those strings are the ones its own
         * extractors are registered under.
         */
        fun mimeForExtension(extension: String?): String? = when (extension?.lowercase()) {
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts", "m2ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> MimeTypes.VIDEO_AVI
            "mov" -> "video/quicktime"
            "mpg", "mpeg" -> MimeTypes.VIDEO_MPEG

            "mka" -> MimeTypes.AUDIO_MATROSKA
            "flac" -> MimeTypes.AUDIO_FLAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "m4b" -> MimeTypes.AUDIO_MP4
            "aac" -> MimeTypes.AUDIO_AAC
            "opus" -> MimeTypes.AUDIO_OPUS
            "ogg", "oga" -> MimeTypes.AUDIO_OGG
            "wav", "wave" -> MimeTypes.AUDIO_WAV
            "aiff", "aif", "aifc" -> "audio/x-aiff"
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "dts" -> MimeTypes.AUDIO_DTS
            "thd" -> MimeTypes.AUDIO_TRUEHD
            "amr" -> MimeTypes.AUDIO_AMR
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
