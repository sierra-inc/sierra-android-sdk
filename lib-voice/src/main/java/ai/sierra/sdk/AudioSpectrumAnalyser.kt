// Copyright Sierra

package ai.sierra.sdk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns linear16 PCM audio into the per-band levels the voice waveform draws.
 *
 * Reproduces what the Web SDK gets from a Web Audio `AnalyserNode` with `fftSize = 64`, so the
 * native waveform behaves like the web one: a Blackman-windowed 64-point FFT, magnitudes smoothed
 * over time, converted to decibels across a -100..-30 dB window, then sampled at the same eight bins
 * the web waveform keeps. Levels come back normalized to `0..1`.
 *
 * Bin index (not absolute frequency) is what is held in common with web, because a browser's
 * `AudioContext` sample rate is itself device-dependent. At the SDK's 24 kHz voice sample rate the
 * eight bands span roughly 750 Hz to 9 kHz.
 *
 * Not thread-safe: each audio direction owns an instance and only ever touches it from that
 * direction's audio thread.
 */
internal class AudioSpectrumAnalyser {
    private val samples = FloatArray(FFT_SIZE)
    private val real = FloatArray(FFT_SIZE)
    private val imaginary = FloatArray(FFT_SIZE)
    private val smoothedMagnitudes = FloatArray(KEPT_BINS.size)

    /**
     * Per-band levels for the most recent [FFT_SIZE] samples ending at this buffer, each `0..1`.
     * [bytes] holds little-endian signed 16-bit mono samples, of which the first [length] are valid.
     */
    fun analyse(bytes: ByteArray, length: Int): FloatArray {
        if (length < 2) {
            return restingLevels()
        }
        appendSamples(bytes, length)

        for (index in 0 until FFT_SIZE) {
            real[index] = samples[index] * WINDOW[index]
            imaginary[index] = 0f
        }
        forwardTransform()

        val levels = FloatArray(KEPT_BINS.size)
        for (position in KEPT_BINS.indices) {
            val bin = KEPT_BINS[position]
            val magnitude =
                sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) / FFT_SIZE
            val smoothed = SMOOTHING_TIME_CONSTANT * smoothedMagnitudes[position] +
                (1f - SMOOTHING_TIME_CONSTANT) * magnitude
            smoothedMagnitudes[position] = smoothed
            levels[position] = normalizedLevel(smoothed)
        }
        return levels
    }

    /** Advances analyser smoothing through one silent frame, as Web Audio does between utterances. */
    fun analyseSilence(): FloatArray = analyse(SILENCE_FRAME, SILENCE_FRAME.size)

    /**
     * Clears the smoothing state and sample window so a resumed stream doesn't decay out of stale
     * levels.
     */
    fun reset() {
        samples.fill(0f)
        smoothedMagnitudes.fill(0f)
    }

    /**
     * Keeps [samples] holding the most recent [FFT_SIZE] samples of the stream, so a read shorter
     * than the window still transforms a full window.
     */
    private fun appendSamples(bytes: ByteArray, length: Int) {
        val available = length / 2
        val taken = min(available, FFT_SIZE)
        val retained = FFT_SIZE - taken
        for (index in 0 until retained) {
            samples[index] = samples[index + taken]
        }
        var byteOffset = (available - taken) * 2
        for (index in 0 until taken) {
            val low = bytes[byteOffset].toInt() and 0xFF
            val high = bytes[byteOffset + 1].toInt()
            samples[retained + index] = ((high shl 8) or low) / 32768f
            byteOffset += 2
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT over [real]/[imaginary]. */
    private fun forwardTransform() {
        var reversed = 0
        for (index in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed or bit
            if (index < reversed) {
                real.swap(index, reversed)
                imaginary.swap(index, reversed)
            }
        }

        var length = 2
        while (length <= FFT_SIZE) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()
            val half = length / 2
            var start = 0
            while (start < FFT_SIZE) {
                var twiddleReal = 1f
                var twiddleImaginary = 0f
                for (offset in 0 until half) {
                    val low = start + offset
                    val high = low + half
                    val productReal = real[high] * twiddleReal - imaginary[high] * twiddleImaginary
                    val productImaginary = real[high] * twiddleImaginary + imaginary[high] * twiddleReal
                    real[high] = real[low] - productReal
                    imaginary[high] = imaginary[low] - productImaginary
                    real[low] += productReal
                    imaginary[low] += productImaginary
                    val nextTwiddleReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
                start += length
            }
            length = length shl 1
        }
    }

    internal companion object {
        /** Matches the Web SDK's `AnalyserNode.fftSize`, which yields 32 usable bins. */
        private const val FFT_SIZE = 64
        private val KEPT_BINS = intArrayOf(2, 5, 8, 11, 14, 17, 20, 24)
        private val SILENCE_FRAME = ByteArray(FFT_SIZE * 2)

        /** `AnalyserNode` defaults, which set how magnitudes are smoothed and mapped onto `0..255`. */
        private const val SMOOTHING_TIME_CONSTANT = 0.8f
        private const val MIN_DECIBELS = -100f
        private const val MAX_DECIBELS = -30f

        /** Blackman window coefficients from the Web Audio specification. */
        private val WINDOW = FloatArray(FFT_SIZE) { index ->
            val ratio = 2.0 * PI * index / FFT_SIZE
            (0.42 - 0.5 * cos(ratio) + 0.08 * cos(2 * ratio)).toFloat()
        }

        /**
         * Levels for silence. Callable from any thread, so suppression paths can emit it without
         * touching an analyser's audio-thread-confined state.
         */
        fun restingLevels(): FloatArray = FloatArray(KEPT_BINS.size)

        /** Maps a magnitude onto the same 256 steps returned by `getByteFrequencyData`. */
        private fun normalizedLevel(magnitude: Float): Float {
            if (magnitude <= 0f) {
                return 0f
            }
            val decibels = 20f * log10(magnitude)
            val level = ((decibels - MIN_DECIBELS) / (MAX_DECIBELS - MIN_DECIBELS)).coerceIn(0f, 1f)
            return (level * BYTE_LEVEL_MAX).toInt() / BYTE_LEVEL_MAX
        }

        private const val BYTE_LEVEL_MAX = 255f

        private fun FloatArray.swap(first: Int, second: Int) {
            val value = this[first]
            this[first] = this[second]
            this[second] = value
        }
    }
}
