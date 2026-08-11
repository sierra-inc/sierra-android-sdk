// Copyright Sierra

package ai.sierra.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Sample rate the SDK's voice pipeline runs at, and so the rate these bands are analysed at. */
private const val VOICE_SAMPLE_RATE = 24000

/**
 * Little-endian linear16 bytes for a unit-amplitude sine whose period is an exact number of samples,
 * so it lands on the center of FFT bin [bin] for a 64-point transform.
 */
private fun sineAtBin(bin: Int, sampleCount: Int = 64, amplitude: Double = 1.0): ByteArray {
    val bytes = ByteArray(sampleCount * 2)
    for (index in 0 until sampleCount) {
        val sample = (sin(2.0 * PI * bin * index / 64) * amplitude * 32767).roundToInt()
        bytes[index * 2] = (sample and 0xFF).toByte()
        bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    return bytes
}

private fun indexOfMaximum(levels: FloatArray): Int {
    var best = 0
    for (index in levels.indices) {
        if (levels[index] > levels[best]) best = index
    }
    return best
}

class AudioSpectrumAnalyserTest {
    @Test
    fun silenceRestsEveryBandAtZero() {
        val analyser = AudioSpectrumAnalyser()

        val levels = analyser.analyse(ByteArray(128), 128)

        assertEquals(8, levels.size)
        assertArrayEquals(AudioSpectrumAnalyser.restingLevels(), levels)
    }

    @Test
    fun aReadTooShortToHoldASampleRests() {
        val analyser = AudioSpectrumAnalyser()

        assertArrayEquals(AudioSpectrumAnalyser.restingLevels(), analyser.analyse(ByteArray(2), 1))
    }

    /**
     * A tone at a kept bin's center frequency must light that band, not a neighbor -- this is what
     * makes the eight bars respond independently rather than moving as one.
     */
    @Test
    fun aToneLightsTheBandItBelongsTo() {
        // Bin 8 is the third band the web waveform keeps; at 24 kHz that is 3 kHz.
        assertEquals(3000, 8 * VOICE_SAMPLE_RATE / 64)
        val expectedBand = 2

        val analyser = AudioSpectrumAnalyser()
        val tone = sineAtBin(8)
        var levels = FloatArray(0)
        // The analyser smooths across calls the way the Web Audio node does, so let it settle.
        repeat(20) {
            levels = analyser.analyse(tone, tone.size)
        }

        assertEquals(expectedBand, indexOfMaximum(levels))
        assertTrue("expected a strong band, got ${levels.toList()}", levels[expectedBand] > 0.9f)
        // A band six bins away from the tone stays quiet; the Blackman window keeps leakage low.
        assertTrue("expected band 0 to stay quiet, got ${levels.toList()}", levels[0] < 0.5f)
    }

    @Test
    fun resetClearsSmoothedLevels() {
        val analyser = AudioSpectrumAnalyser()
        val tone = sineAtBin(8)
        repeat(20) { analyser.analyse(tone, tone.size) }

        analyser.reset()

        assertArrayEquals(AudioSpectrumAnalyser.restingLevels(), analyser.analyse(ByteArray(128), 128))
    }

    @Test
    fun levelsUseWebAudioByteQuantization() {
        val levels = AudioSpectrumAnalyser().analyse(sineAtBin(8, amplitude = 0.02), 128)

        for (level in levels) {
            val byteLevel = level * 255f
            assertEquals(byteLevel.roundToInt().toFloat(), byteLevel, 0.0001f)
        }
    }

    @Test
    fun analyserSmoothingBuildsThenReleasesThroughSilence() {
        val analyser = AudioSpectrumAnalyser()
        val tone = sineAtBin(8, amplitude = 0.02)

        val first = analyser.analyse(tone, tone.size)[2]
        val second = analyser.analyse(tone, tone.size)[2]
        val releasing = analyser.analyseSilence()[2]

        assertTrue("expected smoothing to build from $first", second > first)
        assertTrue("expected release to remain above rest", releasing > 0f)
        assertTrue("expected release to fall from $second", releasing < second)

        var released = analyser.analyseSilence()[2]
        repeat(100) {
            released = analyser.analyseSilence()[2]
        }
        assertEquals(0f, released, 0f)
    }

    /**
     * A read shorter than the 64-sample window still transforms a full window, by carrying over the
     * tail of the previous read.
     */
    @Test
    fun shortReadsStillFillTheWindow() {
        val analyser = AudioSpectrumAnalyser()
        val tone = sineAtBin(8, sampleCount = 256)
        var levels = FloatArray(0)
        var offset = 0
        val chunkBytes = 32
        while (offset < tone.size) {
            levels = analyser.analyse(tone.copyOfRange(offset, offset + chunkBytes), chunkBytes)
            offset += chunkBytes
        }

        assertEquals(2, indexOfMaximum(levels))
    }

    private fun assertArrayEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
