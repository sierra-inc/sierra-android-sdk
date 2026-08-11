// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Number of bars in each row of the voice waveform. */
public const val VOICE_WAVEFORM_BAR_COUNT: Int = 8

/** Default color of the bars driven by the agent's speech. */
@ColorInt
public val DEFAULT_VOICE_WAVEFORM_AGENT_COLOR: Int = Color.rgb(125, 211, 252)

/** Default color of the bars driven by the end user's microphone. */
@ColorInt
public val DEFAULT_VOICE_WAVEFORM_USER_COLOR: Int = Color.rgb(255, 194, 102)

/**
 * Bounds of the waveform size scale, a multiplier of the default dimensions. Hosts can shrink the
 * waveform to nothing or grow it to 3x; anything outside the range is clamped so a bad value can't
 * break the voice screen layout. Matches the Web SDK's `voiceWaveformSize` bounds.
 */
public const val VOICE_WAVEFORM_SCALE_MIN: Float = 0f
public const val VOICE_WAVEFORM_SCALE_MAX: Float = 3f
public const val DEFAULT_VOICE_WAVEFORM_SCALE: Float = 1f

/**
 * Clamps a host-supplied waveform scale into the supported range. Non-finite values fall back to the
 * default rather than collapsing the waveform.
 */
public fun clampVoiceWaveformScale(scale: Float): Float =
    if (scale.isFinite()) scale.coerceIn(VOICE_WAVEFORM_SCALE_MIN, VOICE_WAVEFORM_SCALE_MAX)
    else DEFAULT_VOICE_WAVEFORM_SCALE

/**
 * Renders the voice-call waveform: two overlaid rows of rounded bars centered on a shared horizontal
 * axis, one driven by the agent's speech and one by the end user's microphone. The user's row is
 * reversed and composited in hard-light so overlapping bars stay legible. Mirrors the Web SDK's
 * waveform design.
 */
public class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    @ColorInt
    public var agentColor: Int = DEFAULT_VOICE_WAVEFORM_AGENT_COLOR
        set(value) {
            field = value
            agentPaint.color = value
            invalidate()
        }

    @ColorInt
    public var userColor: Int = DEFAULT_VOICE_WAVEFORM_USER_COLOR
        set(value) {
            field = value
            userPaint.color = value
            invalidate()
        }

    /**
     * Multiplier of the default waveform dimensions. Values outside
     * [VOICE_WAVEFORM_SCALE_MIN]..[VOICE_WAVEFORM_SCALE_MAX] are clamped.
     */
    public var scale: Float = DEFAULT_VOICE_WAVEFORM_SCALE
        set(value) {
            val clamped = clampVoiceWaveformScale(value)
            if (field == clamped) return
            field = clamped
            requestLayout()
            invalidate()
        }

    private val agentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_VOICE_WAVEFORM_AGENT_COLOR
    }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_VOICE_WAVEFORM_USER_COLOR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blendMode = BlendMode.HARD_LIGHT
        } else {
            // Hard-light landed in API 29. Overlay is its operand-swapped twin and the closest blend
            // the platform offers before that.
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }
    }
    private val barRect = RectF()

    // Level state is touched only from the main thread: the setters below are called from the voice
    // session's main-thread dispatch, and the smoothing tick runs as a view frame callback.
    private val targetAgentLevels = FloatArray(VOICE_WAVEFORM_BAR_COUNT)
    private val targetUserLevels = FloatArray(VOICE_WAVEFORM_BAR_COUNT)
    private val smoothedAgentLevels = FloatArray(VOICE_WAVEFORM_BAR_COUNT)
    private val smoothedUserLevels = FloatArray(VOICE_WAVEFORM_BAR_COUNT)
    private var agentLevelsInitialized = false
    private var userLevelsInitialized = false
    private var framePosted = false

    // Aggregated visibility from onVisibilityAggregated, which unlike isShown also covers window
    // visibility. Gates the smoothing tick so it pauses while the view is covered and resumes when
    // the view is shown again.
    private var visibleForAnimation = false

    /**
     * Sets the agent's per-band levels, each normalized to `0..1`. Levels beyond
     * [VOICE_WAVEFORM_BAR_COUNT] are ignored and missing bands rest at zero.
     */
    public fun setAgentLevels(levels: FloatArray) {
        copyLevels(levels, into = targetAgentLevels)
        if (!agentLevelsInitialized) {
            targetAgentLevels.copyInto(smoothedAgentLevels)
            agentLevelsInitialized = true
            invalidate()
            return
        }
        updateFrameCallback()
    }

    /** Sets the end user's per-band levels, each normalized to `0..1`. */
    public fun setUserLevels(levels: FloatArray) {
        copyLevels(levels, into = targetUserLevels)
        if (!userLevelsInitialized) {
            targetUserLevels.copyInto(smoothedUserLevels)
            userLevelsInitialized = true
            invalidate()
            return
        }
        updateFrameCallback()
    }

    /** Test-only reads and stepping of the smoothing state. */
    internal fun smoothedAgentLevel(index: Int): Float = smoothedAgentLevels[index]
    internal fun smoothedUserLevel(index: Int): Float = smoothedUserLevels[index]
    internal val isSmoothingTickScheduled: Boolean get() = framePosted
    internal fun runSmoothingTick(): Unit = tickRunnable.run()

    /** Drops every bar back to its resting dot immediately, without the release ramp. */
    public fun resetLevels() {
        targetAgentLevels.fill(0f)
        targetUserLevels.fill(0f)
        smoothedAgentLevels.fill(0f)
        smoothedUserLevels.fill(0f)
        agentLevelsInitialized = false
        userLevelsInitialized = false
        framePosted = false
        removeCallbacks(tickRunnable)
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (visibleForAnimation == isVisible) return
        visibleForAnimation = isVisible
        if (isVisible) {
            // Band callbacks that arrived while hidden only moved the targets, and upstream dedupe
            // may never send another frame, so the tick has to restart itself here.
            updateFrameCallback()
        } else {
            removeCallbacks(tickRunnable)
            framePosted = false
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tickRunnable)
        framePosted = false
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val barCount = VOICE_WAVEFORM_BAR_COUNT
        val desiredWidth = (barCount * barWidth() + (barCount - 1) * barGap()).roundToInt()
        val desiredHeight = maxBarHeight().roundToInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = barWidth()
        if (barWidth <= 0f) return

        val barCount = VOICE_WAVEFORM_BAR_COUNT
        val totalWidth = barCount * barWidth + (barCount - 1) * barGap()
        val originX = (width - totalWidth) / 2f
        val centerY = height / 2f

        // The two rows share a layer so the user row's blend has the agent row as its backdrop.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        drawRow(canvas, smoothedAgentLevels, reversed = false, paint = agentPaint, originX = originX, centerY = centerY)
        // The user's row runs in the opposite direction so the two spectra fan out from the middle.
        drawRow(canvas, smoothedUserLevels, reversed = true, paint = userPaint, originX = originX, centerY = centerY)
        canvas.restoreToCount(layer)
    }

    private fun drawRow(
        canvas: Canvas,
        levels: FloatArray,
        reversed: Boolean,
        paint: Paint,
        originX: Float,
        centerY: Float,
    ) {
        val barWidth = barWidth()
        val maxBarHeight = maxBarHeight()
        val stride = barWidth + barGap()
        val radius = barWidth / 2f
        for (index in 0 until VOICE_WAVEFORM_BAR_COUNT) {
            val level = levels[if (reversed) VOICE_WAVEFORM_BAR_COUNT - 1 - index else index]
            // A silent bar rests as a circle the width of the bar.
            val height = max(barWidth, level * maxBarHeight)
            val left = originX + index * stride
            barRect.set(left, centerY - height / 2f, left + barWidth, centerY + height / 2f)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }
    }

    private fun barWidth(): Float = BAR_WIDTH_DP * scale * density
    private fun barGap(): Float = BAR_GAP_DP * scale * density
    private fun maxBarHeight(): Float = MAX_BAR_HEIGHT_DP * scale * density

    private val density: Float get() = resources.displayMetrics.density

    private fun copyLevels(levels: FloatArray, into: FloatArray) {
        for (index in into.indices) {
            val level = levels.getOrElse(index) { 0f }
            into[index] = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        }
    }

    private fun updateFrameCallback() {
        if (!visibleForAnimation || framePosted || !needsSmoothing()) return
        framePosted = true
        postOnAnimation(tickRunnable)
    }

    private val tickRunnable = Runnable {
        framePosted = false
        blend(smoothedAgentLevels, targetAgentLevels)
        blend(smoothedUserLevels, targetUserLevels)
        invalidate()
        updateFrameCallback()
    }

    private fun blend(current: FloatArray, target: FloatArray) {
        for (index in current.indices) {
            val difference = target[index] - current[index]
            if (abs(difference) <= LEVEL_EPSILON) {
                current[index] = target[index]
                continue
            }
            val coefficient = if (difference > 0f) LEVEL_ATTACK else LEVEL_RELEASE
            current[index] += difference * coefficient
        }
    }

    private fun needsSmoothing(): Boolean {
        for (index in 0 until VOICE_WAVEFORM_BAR_COUNT) {
            if (abs(smoothedAgentLevels[index] - targetAgentLevels[index]) > LEVEL_EPSILON) return true
            if (abs(smoothedUserLevels[index] - targetUserLevels[index]) > LEVEL_EPSILON) return true
        }
        return false
    }

    private companion object {
        /** Dimensions at a scale of 1, matching the Web SDK. */
        const val MAX_BAR_HEIGHT_DP = 32f
        const val BAR_WIDTH_DP = 4f
        const val BAR_GAP_DP = 2f

        /**
         * Slight attack/release blend: mostly follows the signal, eases drops a little. Matches the
         * Web SDK's per-frame smoothing.
         */
        const val LEVEL_ATTACK = 0.62f
        const val LEVEL_RELEASE = 0.26f

        const val LEVEL_EPSILON = 0.001f
    }
}
