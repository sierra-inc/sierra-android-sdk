// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import ai.sierra.sdk.chatkit.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

public class VoiceAudioLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    @ColorInt
    public var inputColor: Int = Color.rgb(0, 212, 255)
        set(value) {
            field = value
            invalidate()
        }

    @ColorInt
    public var outputColor: Int = Color.rgb(180, 217, 140)
        set(value) {
            field = value
            invalidate()
        }

    @ColorInt
    public var micColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    public var levelGain: Float = 6f

    private val inputPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var targetInputLevel = 0f
    private var targetOutputLevel = 0f
    private var smoothedInputLevel = 0f
    private var smoothedOutputLevel = 0f
    private var framePosted = false
    private val micDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.sierra_chatkit_ic_mic_24)?.mutate()

    public fun setInputLevel(level: Float) {
        targetInputLevel = clampedLevel(level)
        updateFrameCallback()
    }

    public fun setOutputLevel(level: Float) {
        targetOutputLevel = clampedLevel(level)
        updateFrameCallback()
    }

    public fun resetLevels() {
        targetInputLevel = 0f
        targetOutputLevel = 0f
        smoothedInputLevel = 0f
        smoothedOutputLevel = 0f
        framePosted = false
        removeCallbacks(tickRunnable)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tickRunnable)
        framePosted = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / SVG_WIDTH, height / SVG_HEIGHT)
        if (scale <= 0f) return

        val drawWidth = SVG_WIDTH * scale
        val drawHeight = SVG_HEIGHT * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        if (micDrawable != null) {
            DrawableCompat.setTint(micDrawable, micColor)
            micDrawable.setBounds(left.toInt(), top.toInt(), (left + drawWidth).toInt(), (top + drawHeight).toInt())
            micDrawable.draw(canvas)
        }

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        drawLevelOvals(canvas)
        canvas.restore()
    }

    private fun drawLevelOvals(canvas: Canvas) {
        val baseY = MIC_SLOT_TOP + MIC_SLOT_HEIGHT
        outputPaint.color = outputColor
        inputPaint.color = inputColor

        val outputHeight = MIC_SLOT_HEIGHT * smoothedOutputLevel
        if (outputHeight > 0f) {
            drawSlotFill(canvas, baseY - outputHeight, outputHeight, outputPaint)
        }

        val inputHeight = MIC_SLOT_HEIGHT * smoothedInputLevel
        if (inputHeight > 0f) {
            drawSlotFill(canvas, baseY - inputHeight, inputHeight, inputPaint)
        }
    }

    private fun drawSlotFill(canvas: Canvas, top: Float, height: Float, paint: Paint) {
        val rect = RectF(MIC_SLOT_LEFT, top, MIC_SLOT_LEFT + MIC_SLOT_WIDTH, top + height)
        canvas.drawRoundRect(rect, MIC_SLOT_WIDTH / 2f, MIC_SLOT_WIDTH / 2f, paint)
    }

    private fun clampedLevel(raw: Float): Float = min(1f, max(0f, raw * levelGain))

    private fun updateFrameCallback() {
        if (!isShown || framePosted || !needsSmoothing()) return
        framePosted = true
        postOnAnimation(tickRunnable)
    }

    private val tickRunnable = Runnable {
        framePosted = false
        smoothedInputLevel = blend(smoothedInputLevel, targetInputLevel)
        smoothedOutputLevel = blend(smoothedOutputLevel, targetOutputLevel)
        snapSettledLevels()
        invalidate()
        updateFrameCallback()
    }

    private fun blend(current: Float, target: Float): Float {
        val coefficient = if (target > current) ATTACK_COEF else RELEASE_COEF
        return current + (target - current) * coefficient
    }

    private fun needsSmoothing(): Boolean =
        abs(smoothedInputLevel - targetInputLevel) > LEVEL_EPSILON ||
            abs(smoothedOutputLevel - targetOutputLevel) > LEVEL_EPSILON

    private fun snapSettledLevels() {
        if (abs(smoothedInputLevel - targetInputLevel) <= LEVEL_EPSILON) {
            smoothedInputLevel = targetInputLevel
        }
        if (abs(smoothedOutputLevel - targetOutputLevel) <= LEVEL_EPSILON) {
            smoothedOutputLevel = targetOutputLevel
        }
    }

    private companion object {
        const val SVG_WIDTH = 20f
        const val SVG_HEIGHT = 20f
        const val MIC_SLOT_LEFT = 8f
        const val MIC_SLOT_TOP = 4f
        const val MIC_SLOT_WIDTH = 4f
        const val MIC_SLOT_HEIGHT = 7f
        const val ATTACK_COEF = 0.62f
        const val RELEASE_COEF = 0.26f
        const val LEVEL_EPSILON = 0.001f
    }
}
