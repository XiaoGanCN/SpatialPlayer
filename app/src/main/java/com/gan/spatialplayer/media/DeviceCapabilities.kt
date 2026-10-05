package com.gan.spatialplayer.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.HdrConversionMode
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.Spatializer
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * Runtime picture of what this specific handset can actually do.
 *
 * Everything here is probed from the platform rather than hard-coded, because the two questions
 * that matter most for this app - "can this track be decoded?" and "can this panel show this
 * transfer function?" - vary per device and per attached output.
 *
 * Note on public API surface: several tempting platform calls are hidden and would fail to
 * compile or throw at runtime (`Display.getHdrConversionMode()`, `Spatializer.getHeadTrackingMode()`,
 * `Spatializer.getSpatializationLevel()`, `AudioManager.getSpatializedChannelMasks()`). Only the
 * stable public surface is used here; the richer detail is read back off `dumpsys` during
 * verification instead.
 */
object DeviceCapabilities {

    // ---------------------------------------------------------------- audio codecs

    /** Audio decoders exposed by the platform (hardware and AOSP software fallbacks). */
    fun platformAudioDecoders(): List<CodecEntry> = codecsWhere { it.startsWith("audio/") }

    /** Video decoders exposed by the platform. */
    fun platformVideoDecoders(): List<CodecEntry> = codecsWhere { it.startsWith("video/") }

    private fun codecsWhere(predicate: (String) -> Boolean): List<CodecEntry> {
        val out = ArrayList<CodecEntry>()
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!predicate(type)) continue
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                out += CodecEntry(
                    name = info.name,
                    mime = type,
                    hardware = runCatching { info.isHardwareAccelerated }.getOrDefault(false),
                    vendor = runCatching { info.isVendor }.getOrDefault(isVendorByName(info.name)),
                    alias = info.isAlias,
                )
            }
        }
        return out.sortedWith(compareBy({ it.mime }, { !it.hardware }, { it.name }))
    }

    /** `c2.android.*` / `OMX.google.*` are AOSP software codecs; anything else is vendor-provided. */
    private fun isVendorByName(name: String): Boolean {
        val n = name.lowercase()
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
    }

    fun hasHardwareDecoder(mime: String): Boolean =
        platformAudioDecoders().any { it.mime == mime && it.hardware } ||
            platformVideoDecoders().any { it.mime == mime && it.hardware }

    // ---------------------------------------------------------------- spatial audio

    /**
     * Snapshot of Android's spatial-audio state.
     *
     * The asymmetry that trips most players up: an app cannot switch Android's spatializer on or
     * off, and cannot force the head tracker on. The platform owns both and only routes spatial
     * audio to an output that advertises support. What an app *can* do is declare its content
     * spatializable, which is what engages the pipeline - see
     * [com.gan.spatialplayer.media.PlayerEngine].
     */
    data class SpatialSnapshot(
        val available: Boolean,
        val enabled: Boolean,
        val headTrackerAvailable: Boolean,
        /** Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_* - "none", "multichannel" or "other". */
        val immersiveLevel: Int,
        val immersiveLevelLabel: String,
        /** Whether the *currently routed* output reports that it can spatialize this content. */
        val outputCanSpatialize: Boolean,
        val outputDeviceName: String?,
        val outputDeviceTypeLabel: String?,
    ) {
        /** True when the platform is actually spatializing right now. */
        val active: Boolean get() = available && enabled
    }

    fun spatialSnapshot(context: Context): SpatialSnapshot {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val sp: Spatializer? = runCatching { am?.spatializer }.getOrNull()

        val available = runCatching { sp?.isAvailable == true }.getOrDefault(false)
        val enabled = runCatching { sp?.isEnabled == true }.getOrDefault(false)
        val headTracker = runCatching { sp?.isHeadTrackerAvailable == true }.getOrDefault(false)
        val level = runCatching {
            sp?.immersiveAudioLevel ?: Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
        }.getOrDefault(Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE)

        // canBeSpatialized asks about a specific audio format on a specific route. Querying the
        // format we actually care about - 5.1 PCM at 48 kHz, the layout movie audio downmixes to -
        // answers "will the system spatialize my multichannel film audio on this output?".
        val probeFormat = android.media.AudioFormat.Builder()
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_5POINT1)
            .build()
        val probeAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val canSpatialize = runCatching {
            sp?.canBeSpatialized(probeAttributes, probeFormat) == true
        }.getOrDefault(false)

        val device = currentOutputDevice(context)

        return SpatialSnapshot(
            available = available,
            enabled = enabled,
            headTrackerAvailable = headTracker,
            immersiveLevel = level,
            immersiveLevelLabel = immersiveLevelLabel(level),
            outputCanSpatialize = canSpatialize,
            outputDeviceName = device?.productName?.toString()?.takeIf { it.isNotBlank() },
            outputDeviceTypeLabel = device?.let { deviceTypeLabel(it.type) },
        )
    }

    private fun immersiveLevelLabel(level: Int): String = when (level) {
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "none"
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "multichannel"
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_OTHER -> "other"
        else -> "level-$level"
    }

    /** Best guess at the device audio is currently routed to. */
    fun currentOutputDevice(context: Context): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()
            ?: return null
        // Prefer an actively-worn headset, then anything that is not the built-in speaker.
        val preferredTypes = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        for (t in preferredTypes) devices.firstOrNull { it.type == t }?.let { return it }
        return devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: devices.firstOrNull()
    }

    fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        else -> "type-0x${Integer.toHexString(type)}"
    }

    // ---------------------------------------------------------------- display / HDR

    data class HdrSnapshot(
        val supportedHdrTypes: List<String>,
        val maxLuminance: Float,
        val maxAverageLuminance: Float,
        val minLuminance: Float,
        /**
         * Android's current HDR conversion strategy. `passthrough` means the panel receives the
         * original transfer function; `system` means the platform is tone mapping for us.
         */
        val conversionModeLabel: String,
        val preferredHdrOutputType: String,
        val currentRefreshRate: Float,
        val peakRefreshRate: Float,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
    ) {
        val hasHdr10: Boolean get() = supportedHdrTypes.any { it.equals("HDR10", true) }
        val hasHlg: Boolean get() = supportedHdrTypes.any { it.contains("HLG", true) }
        val hasDolbyVision: Boolean get() = supportedHdrTypes.any { it.contains("Dolby", true) }
    }

    fun hdrSnapshot(context: Context): HdrSnapshot {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display: Display? = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay

        // Luminance still comes from HdrCapabilities, but the *supported transfer functions* now
        // live on the active Display.Mode - HdrCapabilities.getSupportedHdrTypes() is deprecated.
        val caps = display?.hdrCapabilities
        val activeMode = display?.mode
        val types = activeMode?.supportedHdrTypes?.map { hdrTypeLabel(it) } ?: emptyList()

        // android.hardware.display.HdrConversionMode is the public replacement for the hidden
        // Display.getHdrConversionMode().
        val conversion = runCatching { dm?.hdrConversionMode }.getOrNull()
        val modeLabel = when (conversion?.conversionMode) {
            HdrConversionMode.HDR_CONVERSION_PASSTHROUGH -> "passthrough"
            HdrConversionMode.HDR_CONVERSION_SYSTEM -> "system"
            HdrConversionMode.HDR_CONVERSION_FORCE -> "force"
            else -> "unknown"
        }
        val preferred = hdrOutputTypeLabel(conversion?.preferredHdrOutputType)

        // Panel geometry comes from the active mode; these are the real physical pixels.
        val widthPx = activeMode?.physicalWidth ?: 0
        val heightPx = activeMode?.physicalHeight ?: 0

        val peak = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f

        return HdrSnapshot(
            supportedHdrTypes = types,
            maxLuminance = caps?.desiredMaxLuminance ?: 0f,
            maxAverageLuminance = caps?.desiredMaxAverageLuminance ?: 0f,
            minLuminance = caps?.desiredMinLuminance ?: 0f,
            conversionModeLabel = modeLabel,
            preferredHdrOutputType = preferred,
            currentRefreshRate = display?.refreshRate ?: 0f,
            peakRefreshRate = peak,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = context.resources.configuration.densityDpi,
        )
    }

    /** `Display.HdrCapabilities.HDR_TYPE_*` values. */
    private fun hdrTypeLabel(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "type-$type"
    }

    private fun hdrOutputTypeLabel(type: Int?): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        null, Display.HdrCapabilities.HDR_TYPE_INVALID -> "invalid"
        else -> "type-$type"
    }

    // ---------------------------------------------------------------- container probing

    /** MIME types the bundled FFmpeg build can decode, restricted to audio. */
    fun ffmpegAudioMimes(): List<String> = FfmpegCodecs.AUDIO.filter { FfmpegCodecs.isAvailable(it) }

    fun ffmpegVideoMimes(): List<String> = FfmpegCodecs.VIDEO.filter { FfmpegCodecs.isAvailable(it) }

    // ---------------------------------------------------------------- device identity

    data class Identity(
        val manufacturer: String,
        val model: String,
        val device: String,
        val soc: String,
        val socManufacturer: String,
        val androidRelease: String,
        val sdkInt: Int,
        val abis: List<String>,
        val buildFingerprint: String,
    )

    fun identity(): Identity = Identity(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        soc = runCatching { Build.SOC_MODEL }.getOrDefault(Build.HARDWARE),
        socManufacturer = runCatching { Build.SOC_MANUFACTURER }.getOrDefault("unknown"),
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        buildFingerprint = Build.FINGERPRINT,
    )

    data class CodecEntry(
        val name: String,
        val mime: String,
        val hardware: Boolean,
        val vendor: Boolean,
        val alias: Boolean,
    )
}
