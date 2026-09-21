package com.taar.dsp

/**
 * Iterative radix-2 FFT, real input.
 *
 * Only used on the decimated envelope — about 6000 samples padded to 8192 — so a
 * textbook implementation is fast enough and avoids a dependency. The raw 44.1 kHz
 * audio is never transformed.
 */
object Fft {

    /**
     * Power spectrum of a real signal, length [n]/2 + 1.
     *
     * @param x input; zero-padded to the next power of two
     */
    fun powerSpectrum(x: DoubleArray): DoubleArray {
        val n = nextPowerOfTwo(x.size)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        x.copyInto(re, endIndex = x.size)

        transform(re, im)

        val bins = n / 2 + 1
        val power = DoubleArray(bins)
        for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k]
        return power
    }

    /** Bin centre frequencies matching [powerSpectrum], given the sample rate. */
    fun frequencies(inputSize: Int, sampleRateHz: Double): DoubleArray {
        val n = nextPowerOfTwo(inputSize)
        val bins = n / 2 + 1
        return DoubleArray(bins) { it * sampleRateHz / n }
    }

    fun hann(n: Int): DoubleArray =
        DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (n - 1).coerceAtLeast(1)) }

    private fun nextPowerOfTwo(v: Int): Int {
        var n = 1
        while (n < v) n = n shl 1
        return n
    }

    private fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        if (n <= 1) return

        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(angle)
            val wIm = kotlin.math.sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
