// Copyright Sierra

package ai.sierra.sdk.chatkit.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Pinned to mdpi so one density-independent pixel is one pixel and the measured sizes below are the
// same numbers as the design's dp values, and to SDK 34 (the compileSdk) because the library
// manifest declares no targetSdk, which leaves Robolectric emulating an SDK old enough to predate
// View.onVisibilityAggregated (API 24).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "mdpi", sdk = [34])
class VoiceWaveformViewTest {
    private fun waveform(): VoiceWaveformView = VoiceWaveformView(RuntimeEnvironment.getApplication())

    private fun measure(view: View) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
    }

    @Test
    fun clampsScaleIntoTheSupportedRange() {
        assertEquals(1f, clampVoiceWaveformScale(1f), 0f)
        assertEquals(0.5f, clampVoiceWaveformScale(0.5f), 0f)
        assertEquals(VOICE_WAVEFORM_SCALE_MAX, clampVoiceWaveformScale(10f), 0f)
        assertEquals(VOICE_WAVEFORM_SCALE_MIN, clampVoiceWaveformScale(-1f), 0f)
    }

    @Test
    fun fallsBackToTheDefaultForNonFiniteScales() {
        assertEquals(DEFAULT_VOICE_WAVEFORM_SCALE, clampVoiceWaveformScale(Float.NaN), 0f)
        assertEquals(DEFAULT_VOICE_WAVEFORM_SCALE, clampVoiceWaveformScale(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun waveformSizeScalesBarsAndSpacingTogether() {
        // Eight 4dp bars separated by seven 2dp gaps, 32dp tall, all multiplied by the scale.
        val view = waveform()

        measure(view)
        assertEquals(46, view.measuredWidth)
        assertEquals(32, view.measuredHeight)

        view.scale = 2f
        measure(view)
        assertEquals(92, view.measuredWidth)
        assertEquals(64, view.measuredHeight)

        view.scale = 0.5f
        measure(view)
        assertEquals(23, view.measuredWidth)
        assertEquals(16, view.measuredHeight)
    }

    @Test
    fun waveformSizeIsClampedByTheView() {
        val view = waveform()

        view.scale = 10f
        assertEquals(VOICE_WAVEFORM_SCALE_MAX, view.scale, 0f)
        measure(view)
        assertEquals(138, view.measuredWidth)
        assertEquals(96, view.measuredHeight)

        view.scale = -1f
        assertEquals(VOICE_WAVEFORM_SCALE_MIN, view.scale, 0f)
        measure(view)
        assertEquals(0, view.measuredWidth)
        assertEquals(0, view.measuredHeight)
    }

    @Test
    fun defaultsToTheWebSdkColors() {
        val view = waveform()

        assertEquals(Color.rgb(125, 211, 252), view.agentColor)
        assertEquals(Color.rgb(255, 194, 102), view.userColor)
        assertEquals(DEFAULT_VOICE_WAVEFORM_AGENT_COLOR, view.agentColor)
        assertEquals(DEFAULT_VOICE_WAVEFORM_USER_COLOR, view.userColor)
    }

    /**
     * The session hands over whatever the analyser produced, so the view has to survive arrays that
     * are the wrong length or carry non-finite levels.
     */
    @Test
    fun toleratesShortAndNonFiniteLevelArrays() {
        val view = waveform()
        measure(view)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        view.setAgentLevels(floatArrayOf(1f, Float.NaN, -3f))
        view.setUserLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT + 4) { 1f })

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        view.resetLevels()
        view.draw(Canvas(bitmap))
    }

    @Test
    fun firstSamplesSnapThenUseWebAttackAndRelease() {
        val view = waveform()
        view.onVisibilityAggregated(true)

        view.setAgentLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT) { 1f })
        view.setUserLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT) { 0.5f })

        assertEquals(1f, view.smoothedAgentLevel(0), 0f)
        assertEquals(0.5f, view.smoothedUserLevel(0), 0f)
        assertFalse(view.isSmoothingTickScheduled)

        view.setAgentLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT))
        view.runSmoothingTick()
        assertEquals(0.74f, view.smoothedAgentLevel(0), 0.0001f)

        view.setAgentLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT) { 1f })
        view.runSmoothingTick()
        assertEquals(0.9012f, view.smoothedAgentLevel(0), 0.0001f)

        view.resetLevels()
        view.setAgentLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT) { 0.4f })
        assertEquals(0.4f, view.smoothedAgentLevel(0), 0f)
    }

    /**
     * Band callbacks that arrive while the view is hidden only move the targets, and upstream
     * dispatch dedupes resting frames, so becoming visible again has to restart the smoothing tick
     * without waiting for another callback. Visibility is driven through [View.onVisibilityAggregated]
     * directly because Robolectric does not reproduce the attach/visibility dispatch of a real window.
     */
    @Test
    fun resumesSmoothingWhenShownAgain() {
        val view = waveform()
        view.onVisibilityAggregated(true)

        // The first sample of each row snaps rather than easing, so prime both rows at rest before
        // exercising the tick.
        view.setAgentLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT))
        view.setUserLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT))
        assertFalse(view.isSmoothingTickScheduled)

        view.setAgentLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT) { 1f })
        assertTrue(view.isSmoothingTickScheduled)

        // Hiding cancels the tick, and band targets that keep moving must not reschedule it.
        view.onVisibilityAggregated(false)
        assertFalse(view.isSmoothingTickScheduled)
        view.setUserLevels(FloatArray(VOICE_WAVEFORM_BAR_COUNT) { 1f })
        assertFalse(view.isSmoothingTickScheduled)
        assertEquals(0f, view.smoothedAgentLevel(0), 0f)

        // Becoming visible again restarts the tick on its own, and the ticks drain the bars toward
        // the targets that moved while hidden.
        view.onVisibilityAggregated(true)
        assertTrue(view.isSmoothingTickScheduled)
        repeat(30) { view.runSmoothingTick() }
        assertEquals(1f, view.smoothedAgentLevel(0), 0f)
    }
}
