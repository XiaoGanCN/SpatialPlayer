package com.gan.spatialplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt

/**
 * A scrub bar that behaves like a video timeline rather than a progress indicator.
 *
 * Three bands are drawn: the played portion, the buffered-but-not-played portion, and the
 * remainder. Dragging is captured immediately on touch-down within a generous vertical slop so a
 * thumb does not have to be precise, and the listener distinguishes [Listener.onScrubStart] /
 * [Listener.onScrubMove] / [Listener.onScrubStop] so the caller can hold the timecode steady while
 * the finger is down and commit a single seek on release.
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /** Finger down; the caller should stop advancing the displayed position. */
        fun onScrubStart()

        /** Fraction of total duration, 0..1. Continuous, for live timecode updates. */
        fun onScrubMove(fraction: Float)

        /** Fraction of total duration to commit as a seek. */
        fun onScrubStop(fraction: Float)
    }

    var listener: Listener? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackRect = RectF()
    private val playedRect = RectF()
    private val bufferedRect = RectF()

    @ColorInt private var trackColor: Int = Color.argb(61, 255, 255, 255)
    @ColorInt private var playedColor: Int = Color.parseColor("#8AB4F8")
    @ColorInt private var bufferedColor: Int = Color.argb(110, 255, 255, 255)
    @ColorInt private var thumbColor: Int = Color.parseColor("#F2F4F7")

    /** 0..1, the value actually drawn. */
    private var progress = 0f

    /** Where the animation is heading; playback may report the next tick before it arrives. */
    private var targetProgress = 0f

    private var progressAnimator: ValueAnimator? = null

    /** 0..1 */
    private var buffered = 0f

    private var scrubbing = false
    private var hoverFraction = 0f

    /** Grows on touch for a tangible handle. */
    private var thumbScale = 1f
    private var thumbAnimator: ValueAnimator? = null

    private var trackHeightPx = 3f
    private var thumbRadiusPx = 6f
    private var scrubThumbRadiusPx = 9f
    private var touchSlopPx = 0f

    init {
        val density = resources.displayMetrics.density
        trackHeightPx = 3f * density
        thumbRadiusPx = 6f * density
        scrubThumbRadiusPx = 9f * density
        touchSlopPx = 24f * density

        trackPaint.color = trackColor
        playedPaint.color = playedColor
        bufferedPaint.color = bufferedColor
        thumbPaint.color = thumbColor

        isClickable = true
        isFocusable = true
    }

    fun setColors(@ColorInt played: Int, @ColorInt track: Int, @ColorInt bufferedBar: Int, @ColorInt thumb: Int) {
        playedColor = played
        trackColor = track
        bufferedColor = bufferedBar
        thumbColor = thumb
        playedPaint.color = played
        trackPaint.color = track
        bufferedPaint.color = bufferedBar
        thumbPaint.color = thumb
        invalidate()
    }

    /**
     * Current playback position as a fraction of duration. Ignored while the user is scrubbing.
     *
     * The value is eased toward rather than snapped to. Playback position arrives on a polling
     * tick (and in coarse jumps on some streams), so assigning it directly makes the bar step
     * visibly; interpolating gives a continuous sweep without ever running ahead of the truth.
     */
    fun setProgress(fraction: Float) {
        if (scrubbing) return
        val clamped = fraction.coerceIn(0f, 1f)

        val distance = kotlin.math.abs(clamped - progress)
        if (distance < MIN_VISIBLE_DELTA) {
            // Below a pixel or so there is nothing to smooth; avoid pointless animation churn.
            progress = clamped
            invalidate()
            return
        }

        targetProgress = clamped

        // A large jump (a seek, or a stream restart) should arrive quickly and directly; the
        // smoothing is for the small increments of ordinary playback.
        if (distance > LARGE_JUMP) {
            progressAnimator?.cancel()
            progress = clamped
            invalidate()
            return
        }

        startProgressAnimation()
    }

    private fun startProgressAnimation() {
        progressAnimator?.cancel()
        val from = progress
        val to = targetProgress
        progressAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = PROGRESS_ANIMATION_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Buffered position as a fraction of duration. */
    fun setBuffered(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == buffered) return
        buffered = clamped
        invalidate()
    }

    /** Bypasses the scrub lock, for the caller to re-sync after a seek completes. */
    fun forceProgress(fraction: Float) {
        progressAnimator?.cancel()
        progress = fraction.coerceIn(0f, 1f)
        targetProgress = progress
        invalidate()
    }

    val isScrubbing: Boolean get() = scrubbing

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (scrubThumbRadiusPx * 2 + 12f * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerY = height / 2f
        val horizontalInset = scrubThumbRadiusPx
        val left = paddingLeft + horizontalInset
        val right = width - paddingRight - horizontalInset
        if (right <= left) return

        val halfTrack = trackHeightPx / 2f
        trackRect.set(left, centerY - halfTrack, right, centerY + halfTrack)

        // Remainder
        canvas.drawRoundRect(trackRect, halfTrack, halfTrack, trackPaint)

        // Buffered
        val bufferedX = left + (right - left) * buffered.coerceAtLeast(progress)
        if (bufferedX > left) {
            bufferedRect.set(left, centerY - halfTrack, bufferedX, centerY + halfTrack)
            canvas.drawRoundRect(bufferedRect, halfTrack, halfTrack, bufferedPaint)
        }

        // Played
        val playedX = left + (right - left) * progress
        if (playedX > left) {
            playedRect.set(left, centerY - halfTrack, playedX, centerY + halfTrack)
            canvas.drawRoundRect(playedRect, halfTrack, halfTrack, playedPaint)
        }

        // Thumb
        val radius = if (scrubbing) scrubThumbRadiusPx else thumbRadiusPx * thumbScale
        canvas.drawCircle(playedX, centerY, radius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val horizontalInset = scrubThumbRadiusPx
        val left = paddingLeft + horizontalInset
        val right = width - paddingRight - horizontalInset
        if (right <= left) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Accept the touch anywhere in the bar's vertical slop, not just on the thumb.
                if (event.y < -touchSlopPx || event.y > height + touchSlopPx) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                hoverFraction = fractionFor(event.x, left, right)
                animateThumb(1.25f)
                listener?.onScrubStart()
                listener?.onScrubMove(hoverFraction)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                hoverFraction = fractionFor(event.x, left, right)
                listener?.onScrubMove(hoverFraction)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!scrubbing) return false
                hoverFraction = fractionFor(event.x, left, right)
                scrubbing = false
                animateThumb(1f)
                listener?.onScrubStop(hoverFraction)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                scrubbing = false
                animateThumb(1f)
                listener?.onScrubStop(hoverFraction)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fractionFor(x: Float, left: Float, right: Float): Float =
        ((x - left) / (right - left)).coerceIn(0f, 1f)

    private fun animateThumb(target: Float) {
        thumbAnimator?.cancel()
        thumbAnimator = ValueAnimator.ofFloat(thumbScale, target).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                thumbScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Fraction currently under the finger, for live timecode display. */
    fun currentScrubFraction(): Float = hoverFraction

    override fun onDetachedFromWindow() {
        thumbAnimator?.cancel()
        thumbAnimator = null
        progressAnimator?.cancel()
        progressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        /** How long the bar takes to ease to a newly reported position. */
        const val PROGRESS_ANIMATION_MS = 260L

        /** Below this fraction there is nothing visible to smooth. */
        const val MIN_VISIBLE_DELTA = 0.0008f

        /** Above this fraction the change is a seek, not playback drift, so jump straight there. */
        const val LARGE_JUMP = 0.05f
    }
}
