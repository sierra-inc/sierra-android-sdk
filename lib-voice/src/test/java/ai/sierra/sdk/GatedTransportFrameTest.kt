// Copyright Sierra

package ai.sierra.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedTransportFrameTest {
    @Test
    fun passingGateForwardsCapturedAudio() {
        val source = byteArrayOf(1, 2, 3, 4)
        val frame = gatedTransportFrame(passesSpeakingGate = true, source = source, length = 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame)
    }

    @Test
    fun closedGateEmitsEqualLengthSilence() {
        // When the echo gate is closed we emit silence of the same length instead of dropping the
        // frame, so the upstream AudioIn timeline keeps its true duration. See CH-633.
        val source = byteArrayOf(1, 2, 3, 4)
        val frame = gatedTransportFrame(passesSpeakingGate = false, source = source, length = 4)
        assertEquals(source.size, frame.size)
        assertTrue(frame.all { it == 0.toByte() })
    }

    @Test
    fun forwardsReadLengthNotFullBufferSize() {
        // The capture buffer is reused and larger than the bytes actually read; both paths must
        // forward exactly `length` bytes.
        val source = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(3, gatedTransportFrame(passesSpeakingGate = true, source = source, length = 3).size)
        assertEquals(3, gatedTransportFrame(passesSpeakingGate = false, source = source, length = 3).size)
    }

    @Test
    fun capturePolicyPrecedence() {
        // System pause suspends the session and the mic is unreliable, so it drops even when the
        // user is also muted. User mute and speaking-mute emit silence to keep the server's
        // byte-counted AudioIn clock advancing so agent audio stays aligned during playback.
        // See CH-633.
        assertEquals(
            CapturePolicy.DROP,
            capturePolicy(systemPaused = true, userMuted = true, speakingMuted = true),
        )
        assertEquals(
            CapturePolicy.SILENCE,
            capturePolicy(systemPaused = false, userMuted = true, speakingMuted = false),
        )
        assertEquals(
            CapturePolicy.SILENCE,
            capturePolicy(systemPaused = false, userMuted = false, speakingMuted = true),
        )
        assertEquals(
            CapturePolicy.CAPTURE,
            capturePolicy(systemPaused = false, userMuted = false, speakingMuted = false),
        )
    }
}
