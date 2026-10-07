package com.gan.spatialplayer.media

/**
 * What feeds one output channel.
 *
 * Five options rather than a continuous mix, because the useful matrices are few and a list of names
 * can be read at a glance where a pair of coefficients per channel cannot.
 */
enum class Source {
    /** The left input channel. */
    LEFT,

    /** The right input channel. */
    RIGHT,

    /** Both, summed. */
    SUM,

    /** The difference, which is the out-of-phase part of the stereo image. */
    DIFFERENCE,

    /**
     * The difference the other way round.
     *
     * Not a nicety: a surround pair carrying the *same* difference signal is in phase, which reads
     * as a single wide source behind the listener rather than as a space. The presets fill the two
     * rears with opposite polarity for exactly this reason, so the manual matrix needs to be able to
     * say the same thing.
     */
    DIFFERENCE_INVERTED,

    /** Nothing. */
    MUTE,
    ;

    companion object {
        fun parse(token: String?): Source =
            entries.firstOrNull { it.name == token } ?: MUTE
    }
}

/**
 * A manual six-channel mapping.
 *
 * The simple modes are opinions about how stereo should be spread; this is the answer for someone
 * who has their own. Every channel names its own source, so "put the left channel in the surround
 * left" is expressed directly rather than inferred.
 *
 * ## What the mapping does and does not control
 *
 * It chooses the *source*. The per-channel level is still the channel's own - the centre and the
 * rears sit at their usual trims, the subwoofer keeps its own gain - because those are what make the
 * result sound like a surround mix rather than like six copies of the same thing. The subwoofer's
 * 120 Hz low-pass is a separate switch, since a full-range signal in the LFE channel is a good way
 * to waste headroom and a bad way to drive a subwoofer.
 *
 * There is deliberately **no delay** in this mode. The simple modes delay the difference signal so
 * the rears read as space rather than as an echo, but here the user is stating exactly what they
 * want where, and a hidden delay would make the result not match what they asked for.
 */
data class UpmixMatrix(
    val frontLeft: Source = Source.LEFT,
    val frontRight: Source = Source.RIGHT,
    val centre: Source = Source.SUM,
    val lfe: Source = Source.SUM,
    val backLeft: Source = Source.DIFFERENCE,
    val backRight: Source = Source.DIFFERENCE_INVERTED,
    val subwooferLowPass: Boolean = true,
) {

    /** Per-channel sources in the order the settings screen lists them. */
    val channels: List<Source>
        get() = listOf(frontLeft, frontRight, centre, lfe, backLeft, backRight)

    /** Compact form for preferences: six source names, then the low-pass flag. */
    fun encode(): String = channels.joinToString(",") { it.name } + ";" + subwooferLowPass

    companion object {
        /** The mapping the simple SURROUND mode is closest to, minus its delay. */
        val DEFAULT = UpmixMatrix()

        fun decode(value: String?): UpmixMatrix {
            if (value.isNullOrBlank()) return DEFAULT
            val parts = value.split(";")
            val tokens = parts.firstOrNull()?.split(",").orEmpty()
            if (tokens.size < 6) return DEFAULT
            return UpmixMatrix(
                frontLeft = Source.parse(tokens[0]),
                frontRight = Source.parse(tokens[1]),
                centre = Source.parse(tokens[2]),
                lfe = Source.parse(tokens[3]),
                backLeft = Source.parse(tokens[4]),
                backRight = Source.parse(tokens[5]),
                subwooferLowPass = parts.getOrNull(1)?.toBooleanStrictOrNull() ?: true,
            )
        }

        /** A copy with one channel replaced, for the settings screen. */
        fun withChannel(matrix: UpmixMatrix, index: Int, source: Source): UpmixMatrix =
            when (index) {
                0 -> matrix.copy(frontLeft = source)
                1 -> matrix.copy(frontRight = source)
                2 -> matrix.copy(centre = source)
                3 -> matrix.copy(lfe = source)
                4 -> matrix.copy(backLeft = source)
                else -> matrix.copy(backRight = source)
            }
    }
}
