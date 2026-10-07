package com.gan.spatialplayer.media

/**
 * A manual six-channel matrix, per output channel and per input channel.
 *
 * This is the "more freedom" version of the presets: every output channel states how much of the left
 * input and how much of the right input it receives, as a percentage from -100 to +100. Negative is
 * not a curiosity - it is polarity, and it is what makes a surround pair sound like a space instead of
 * like one wide source behind the listener, so the presets use opposite signs on their two rears.
 *
 * ## Channel identity
 *
 * The six entries are in the platform's own order, which is the order Media3 writes and the audio HAL
 * reads: **FL, FR, FC, LFE, BL, BR** - `CHANNEL_OUT_5POINT1`. The settings screen labels each row with
 * that identifier, because "FC" is what every other audio tool calls it and "centre" alone does not
 * say which of the six slots it is.
 *
 * Levels are the channel's own afterwards - the centre, the rears and the subwoofer keep their trims -
 * because those are what make six channels sound like a mix rather than like six copies of the same
 * signal.
 */
data class UpmixMatrix(
    /** (from left, from right) as percentages for FL, FR, FC, LFE, BL, BR. */
    val gains: List<Pair<Int, Int>> = DEFAULT_GAINS,
    val subwooferLowPass: Boolean = true,
) {

    fun withChannel(index: Int, left: Int, right: Int): UpmixMatrix {
        if (index !in gains.indices) return this
        val next = gains.toMutableList()
        next[index] = left.coerceIn(MIN_GAIN, MAX_GAIN) to right.coerceIn(MIN_GAIN, MAX_GAIN)
        return copy(gains = next)
    }

    /** `100,0|0,100|...|1` - compact enough for a preference and readable in a bug report. */
    fun encode(): String =
        gains.joinToString("|") { "${it.first},${it.second}" } + "|" + if (subwooferLowPass) "1" else "0"

    companion object {
        /** FL, FR, FC, LFE, BL, BR. Mirrors the SURROUND preset, minus its delay. */
        val DEFAULT_GAINS: List<Pair<Int, Int>> = listOf(
            100 to 0,      // FL
            0 to 100,      // FR
            50 to 50,      // FC, half of each
            50 to 50,      // LFE
            100 to -100,   // BL, the difference
            -100 to 100,   // BR, the same difference inverted
        )

        const val MIN_GAIN = -100
        const val MAX_GAIN = 100

        /** Names in the platform's channel order; the settings screen shows these as the IDs. */
        val CHANNEL_IDS = listOf("FL", "FR", "FC", "LFE", "BL", "BR")

        val DEFAULT = UpmixMatrix()

        fun decode(value: String?): UpmixMatrix {
            if (value.isNullOrBlank()) return DEFAULT
            val parts = value.split("|")
            if (parts.size < 7) return DEFAULT
            val gains = parts.take(6).map { pair ->
                val halves = pair.split(",")
                val l = halves.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val r = halves.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                l.coerceIn(MIN_GAIN, MAX_GAIN) to r.coerceIn(MIN_GAIN, MAX_GAIN)
            }
            return UpmixMatrix(
                gains = gains,
                subwooferLowPass = parts[6].trim() != "0",
            )
        }
    }
}
