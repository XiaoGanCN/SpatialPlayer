package com.gan.spatialplayer

import android.content.Context
import com.gan.spatialplayer.media.UpmixMatrix
import com.gan.spatialplayer.media.UpmixMode
import android.content.SharedPreferences
import com.gan.spatialplayer.media.DecoderProfile

/**
 * Every persisted preference, in one place.
 *
 * There was no such thing before: each screen owned whichever keys it happened to need, read them
 * from a shared file with its own string literals, and applied a default of its own invention. Two
 * screens that disagreed about a default could not be reconciled, and nothing could list what the
 * app actually remembers - which is what a settings screen has to do.
 *
 * Values are read through typed properties so a default appears exactly once, next to the key it
 * belongs to. Nothing here caches: these are cheap reads from an already-loaded `SharedPreferences`,
 * and the settings screen has to see changes the player makes.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ library

    /** The folder the user granted, if any. A URI, stored as text. */
    var folderUri: String?
        get() = prefs.getString(KEY_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_URI, value).apply()

    /** Which library is folded open on the main screen; see MainActivity.Fold. */
    var foldedLibrary: String
        get() = prefs.getString(KEY_FOLDED, FOLD_NONE) ?: FOLD_NONE
        set(value) = prefs.edit().putString(KEY_FOLDED, value).apply()

    // ------------------------------------------------------------------ playback

    /**
     * Length of a double-tap jump and of the skip buttons, in milliseconds.
     *
     * Clamped on the way in rather than on the way out, so a corrupt value cannot travel any
     * further into the app.
     */
    var doubleTapJumpMs: Long
        get() = prefs.getLong(KEY_JUMP_MS, DEFAULT_JUMP_MS).coerceIn(MIN_JUMP_MS, MAX_JUMP_MS)
        set(value) {
            val clamped = value.coerceIn(MIN_JUMP_MS, MAX_JUMP_MS)
            prefs.edit().putLong(KEY_JUMP_MS, clamped).apply()
        }

    /** Which decoder profile the player builds its renderers from. See `DecoderProfile`. */
    var decoderProfile: String
        get() = prefs.getString(KEY_DECODER_PROFILE, null) ?: DEFAULT_DECODER_PROFILE
        set(value) = prefs.edit().putString(KEY_DECODER_PROFILE, value).apply()

    /** Whether the app asks the platform to spatialise multichannel audio. */
    var spatialEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPATIAL, true)
        set(value) = prefs.edit().putBoolean(KEY_SPATIAL, value).apply()

    /** Whether the ambient wash behind the picture is drawn. */
    var ambientEnabled: Boolean
        get() = prefs.getBoolean(KEY_AMBIENT, true)
        set(value) = prefs.edit().putBoolean(KEY_AMBIENT, value).apply()

    // ------------------------------------------------------------------ gestures

    /**
     * How much of the window a full-height vertical drag covers, as a fraction of the range.
     *
     * Higher means a shorter drag moves further, so the useful band is narrow: below about 0.15 the
     * volume feels stuck, above about 0.6 it jumps several steps at a time.
     */
    var verticalGain: Float
        get() = prefs.getFloat(KEY_VERTICAL_GAIN, DEFAULT_VERTICAL_GAIN)
            .coerceIn(MIN_VERTICAL_GAIN, MAX_VERTICAL_GAIN)
        set(value) {
            val clamped = value.coerceIn(MIN_VERTICAL_GAIN, MAX_VERTICAL_GAIN)
            prefs.edit().putFloat(KEY_VERTICAL_GAIN, clamped).apply()
        }

    // ------------------------------------------------------------------ subtitles

    /** Subtitle text size in sp. */
    var subtitleSizeSp: Float
        get() = prefs.getFloat(KEY_SUBTITLE_SIZE, DEFAULT_SUBTITLE_SIZE)
            .coerceIn(MIN_SUBTITLE_SIZE, MAX_SUBTITLE_SIZE)
        set(value) {
            val clamped = value.coerceIn(MIN_SUBTITLE_SIZE, MAX_SUBTITLE_SIZE)
            prefs.edit().putFloat(KEY_SUBTITLE_SIZE, clamped).apply()
        }

    /** How far up from the bottom of the window subtitles sit, as a fraction of its height. */
    var subtitlePositionFraction: Float
        get() = prefs.getFloat(KEY_SUBTITLE_POSITION, DEFAULT_SUBTITLE_POSITION)
            .coerceIn(0f, MAX_SUBTITLE_POSITION)
        set(value) {
            val clamped = value.coerceIn(0f, MAX_SUBTITLE_POSITION)
            prefs.edit().putFloat(KEY_SUBTITLE_POSITION, clamped).apply()
        }

    /**
     * How many embedded subtitle tracks the picker lists.
     *
     * A remux can carry fifty or more - the reference film has 51 - and building a row for every one
     * of them inflates a panel nobody can read anyway. The cap keeps the common case instant and the
     * panel navigable; the picker offers the rest behind one more tap, and whatever is currently
     * selected is always shown regardless of where it falls in the list.
     */
    var subtitleTrackLimit: Int
        get() = prefs.getInt(KEY_SUBTITLE_TRACK_LIMIT, DEFAULT_SUBTITLE_TRACK_LIMIT)
            .coerceIn(MIN_SUBTITLE_TRACK_LIMIT, MAX_SUBTITLE_TRACK_LIMIT)
        set(value) {
            val clamped = value.coerceIn(MIN_SUBTITLE_TRACK_LIMIT, MAX_SUBTITLE_TRACK_LIMIT)
            prefs.edit().putInt(KEY_SUBTITLE_TRACK_LIMIT, clamped).apply()
        }

    // ------------------------------------------------------------------ glass

    /**
     * Which glass material the chrome is drawn with: `REGULAR` or `CLEAR`.
     *
     * CLEAR dims what is behind the pane and lets more of it through, which is the readable choice
     * over dark video; REGULAR is the brighter, more opaque one.
     */
    /**
     * Whether music keeps playing with the app in the background or the screen off.
     *
     * On by default, which is what a music player is expected to do - and the media notification is
     * how it is controlled once the screen is gone.
     */
    var backgroundAudio: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_AUDIO, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_AUDIO, value).apply()

    /**
     * The same question for anything with a picture.
     *
     * Off by default: leaving a hardware video decoder running behind a black screen is not what
     * anyone wants from a video player, and the picture cannot be seen anyway. Offered because some
     * people listen to concert films or commentary tracks with the screen off.
     */
    var backgroundVideo: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_VIDEO, value).apply()

    /**
     * How the stereo upmix spreads two channels across six; see `UpmixMode`.
     *
     * Stored by name rather than by ordinal so reordering the enum cannot silently change what a
     * stored preference means.
     */
    var upmixMode: String
        get() = prefs.getString(KEY_UPMIX_MODE, UpmixMode.SURROUND.name) ?: UpmixMode.SURROUND.name
        set(value) = prefs.edit().putString(KEY_UPMIX_MODE, value).apply()

    /**
     * Whether the manual matrix is in use instead of the presets.
     *
     * Kept apart from [upmixMode] rather than folded into it, so switching to the advanced editor and
     * back does not lose either choice.
     */
    var upmixAdvanced: Boolean
        get() = prefs.getBoolean(KEY_UPMIX_ADVANCED, false)
        set(value) = prefs.edit().putBoolean(KEY_UPMIX_ADVANCED, value).apply()

    /** The manual mapping; see `UpmixMatrix`. */
    var upmixMatrix: String
        get() = prefs.getString(KEY_UPMIX_MATRIX, UpmixMatrix.DEFAULT.encode())
            ?: UpmixMatrix.DEFAULT.encode()
        set(value) = prefs.edit().putString(KEY_UPMIX_MATRIX, value).apply()

    var glassMaterial: String
        get() = prefs.getString(KEY_GLASS_MATERIAL, null) ?: GLASS_REGULAR
        set(value) = prefs.edit().putString(KEY_GLASS_MATERIAL, value).apply()

    companion object {
        const val FILE = "spatial_player"

        const val KEY_FOLDER_URI = "scoped_folder_uri"
        const val KEY_FOLDED = "folded_library"
        const val KEY_JUMP_MS = "double_tap_jump_ms"
        const val KEY_DECODER_PROFILE = "decoder_profile"
        const val KEY_SPATIAL = "spatial_enabled"
        const val KEY_AMBIENT = "ambient_enabled"
        const val KEY_VERTICAL_GAIN = "vertical_gain"
        const val KEY_SUBTITLE_SIZE = "subtitle_size_sp"
        const val KEY_SUBTITLE_POSITION = "subtitle_position"
        const val KEY_SUBTITLE_TRACK_LIMIT = "subtitle_track_limit"
        const val KEY_UPMIX_MODE = "upmix_mode"

        const val KEY_UPMIX_ADVANCED = "upmix_advanced"

        const val KEY_UPMIX_MATRIX = "upmix_matrix"

        const val KEY_BACKGROUND_AUDIO = "background_audio"

        const val KEY_BACKGROUND_VIDEO = "background_video"

        const val KEY_GLASS_MATERIAL = "glass_material"

        const val FOLD_NONE = "NONE"

        const val GLASS_REGULAR = "REGULAR"
        const val GLASS_CLEAR = "CLEAR"

        const val DEFAULT_JUMP_MS = 10_000L
        const val MIN_JUMP_MS = 1_000L
        const val MAX_JUMP_MS = 60_000L

        const val DEFAULT_VERTICAL_GAIN = 0.30f
        const val MIN_VERTICAL_GAIN = 0.10f
        const val MAX_VERTICAL_GAIN = 0.70f

        const val DEFAULT_SUBTITLE_SIZE = 18f
        const val MIN_SUBTITLE_SIZE = 10f
        const val MAX_SUBTITLE_SIZE = 40f

        const val DEFAULT_SUBTITLE_POSITION = 0.06f
        const val MAX_SUBTITLE_POSITION = 0.45f

        /**
         * Twenty is comfortably more than anyone picks from, and low enough that the panel opens
         * without a visible pause on a phone.
         */
        const val DEFAULT_SUBTITLE_TRACK_LIMIT = 20
        const val MIN_SUBTITLE_TRACK_LIMIT = 5
        const val MAX_SUBTITLE_TRACK_LIMIT = 200

        val DEFAULT_DECODER_PROFILE: String = DecoderProfile.AUTO.name
    }
}
