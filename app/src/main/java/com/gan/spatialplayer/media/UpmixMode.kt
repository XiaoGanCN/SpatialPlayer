package com.gan.spatialplayer.media

/**
 * How two channels are spread across six.
 *
 * The platform refuses to spatialise stereo, so music only reaches the head tracker if it is widened
 * to a surround layout first - but "widened" is not one thing, and which one is right depends on the
 * material. A studio mix with a lot of stereo information survives the derived-difference treatment;
 * a mono-ish or heavily centred track can collapse into the middle, and someone listening for the
 * original stereo image will want the direct mapping instead.
 */
enum class UpmixMode {

    /**
     * Derived surround. Fronts are copied, the centre is the sum at -3 dB relative to one front, the
     * rears are the difference signal delayed by 12 ms, and the subwoofer is both channels low-passed
     * at 120 Hz.
     *
     * The delay is what stops the rears reading as an echo of the front pair - without it, a
     * difference signal sounds like a very wide stereo rather than like a space.
     */
    SURROUND,

    /**
     * Direct mapping: left to front left *and* surround left, right to front right and surround
     * right, with no delay and no difference signal.
     *
     * This is what most people mean when they ask to send the left channel to the surround left. It
     * keeps the original stereo image intact and simply wraps it around the listener, at the cost of
     * some level (the rears are 6 dB down, so the sum is not twice as loud).
     */
    WIDE,

    /**
     * The front pair only, with centre, subwoofer and rears left silent.
     *
     * Nothing is invented. Useful for hearing exactly what the other two modes add, and for material
     * that is already mixed the way the listener wants it.
     */
    FRONT,
}
