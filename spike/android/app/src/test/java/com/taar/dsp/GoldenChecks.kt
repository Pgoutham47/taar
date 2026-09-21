package com.taar.dsp

import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * The golden checks, as plain Kotlin returning results.
 *
 * Kept free of any test framework so the same implementation can be driven from
 * JUnit inside Android Studio and from a command-line harness on a machine with no
 * Android SDK. Duplicating assertions between the two would eventually let them
 * disagree, which defeats the purpose of having them.
 */
object GoldenChecks {

    data class Result(val name: String, val passed: Boolean, val detail: String)

    fun runAll(goldenDir: File): List<Result> = listOf(
        rateGuardTable(goldenDir),
        lockedRatesAreExactlyThree(),
        androidDefaultIsMovedOffTheLock(),
        lombScargleAndSineFit(goldenDir),
        loadedSeparatesFromIdle(goldenDir),
        filterImpulseResponses(goldenDir),
        modulationSeparatesGatedFromSteady(),
        fftFindsAKnownTone(),
        spectrogramShowsTheModulationBand(),
        spectrogramRefusesShortInput(),
        magnitudeReductionDestroysAPerpendicularAcField(),
    )

    // ---- fixtures ----

    private fun rows(dir: File, name: String): List<List<String>> =
        File(dir, name).readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }

    private fun capture(dir: File, case: String): Pair<DoubleArray, DoubleArray> {
        val r = rows(dir, "mag_$case.csv")
        return DoubleArray(r.size) { r[it][0].toDouble() } to
            DoubleArray(r.size) { r[it][1].toDouble() }
    }

    private fun check(name: String, block: () -> String?): Result =
        try {
            val failure = block()
            Result(name, failure == null, failure ?: "ok")
        } catch (e: Exception) {
            Result(name, false, "threw ${e::class.simpleName}: ${e.message}")
        }

    // ---- checks ----

    fun rateGuardTable(dir: File) = check("rate guard matches reference table") {
        val table = rows(dir, "rate_guard.csv")
        if (table.isEmpty()) return@check "no fixtures at ${dir.absolutePath}"
        for (r in table) {
            val rate = r[0].toInt()
            if (LineFrequency.distinctPhases(rate) != r[1].toInt())
                return@check "distinctPhases($rate) = ${LineFrequency.distinctPhases(rate)}, want ${r[1]}"
            if (LineFrequency.isLocked(rate) != r[2].toBoolean())
                return@check "isLocked($rate) = ${LineFrequency.isLocked(rate)}, want ${r[2]}"
            if (LineFrequency.safeRate(rate) != r[3].toInt())
                return@check "safeRate($rate) = ${LineFrequency.safeRate(rate)}, want ${r[3]}"
        }
        null
    }

    fun lockedRatesAreExactlyThree() = check("locked rates below 150 Hz are 10, 20, 25, 50, 100") {
        val locked = (8..149).filter { LineFrequency.isLocked(it) }
        if (locked != listOf(10, 20, 25, 50, 100)) "got $locked" else null
    }

    fun androidDefaultIsMovedOffTheLock() = check("Android default 100 Hz is moved to 99") {
        when {
            !LineFrequency.isLocked(100) -> "100 Hz should be locked"
            LineFrequency.safeRate(100) != 99 -> "safeRate(100) = ${LineFrequency.safeRate(100)}"
            LineFrequency.isLocked(99) -> "99 Hz should not be locked"
            else -> null
        }
    }

    fun lombScargleAndSineFit(dir: File) = check("Lomb-Scargle and sine fit match Python") {
        for (exp in rows(dir, "mag_expected.csv")) {
            val (t, v) = capture(dir, exp[0])
            val power = LombScargle.power(t, v, 50.0)
            val amp = SineFit.fit(t, v, 50.0).amplitudeUt
            if (abs(power - exp[3].toDouble()) > 1e-6)
                return@check "${exp[0]}: power $power, want ${exp[3]}"
            if (abs(amp - exp[4].toDouble()) > 1e-6)
                return@check "${exp[0]}: amplitude $amp, want ${exp[4]}"
        }
        null
    }

    fun loadedSeparatesFromIdle(dir: File) = check("loaded circuit separates from idle") {
        val (ti, vi) = capture(dir, "idle")
        val (tl, vl) = capture(dir, "loaded_0p5A")
        val idle = LombScargle.power(ti, vi, 50.0)
        val loaded = LombScargle.power(tl, vl, 50.0)
        when {
            idle >= 0.01 -> "idle power $idle, want < 0.01"
            loaded <= 0.9 -> "loaded power $loaded, want > 0.9"
            else -> null
        }
    }

    fun filterImpulseResponses(dir: File) = check("filter impulse responses match Python") {
        val filters = mapOf(
            "hf_bandpass_4k_16k" to { Biquad.hfBandpass4kTo16k() },
            "env_lowpass_500" to { Biquad.envLowpass500() },
        )
        for ((name, expected) in rows(dir, "filter_impulse.csv").groupBy { it[0] }) {
            val impulse = DoubleArray(256).also { it[0] = 1.0 }
            val response = filters.getValue(name)().filter(impulse)
            for (row in expected) {
                val i = row[1].toInt()
                if (abs(response[i] - row[2].toDouble()) > 1e-9)
                    return@check "$name[$i] = ${response[i]}, want ${row[2]}"
            }
        }
        null
    }

    fun modulationSeparatesGatedFromSteady() =
        check("modulation index separates gated from steady carrier") {
            val sr = 44_100.0
            val n = (sr * 2).toInt()
            val gated = DoubleArray(n) {
                val t = it / sr
                val on = if ((t * 100.0) % 1.0 < 0.06) 1.0 else 0.0
                on * sin(2.0 * Math.PI * 10_000.0 * t)
            }
            val steady = DoubleArray(n) { sin(2.0 * Math.PI * 10_000.0 * (it / sr)) }
            val mod = ArcDetector.modulationIndex(gated, sr)
            val flat = ArcDetector.modulationIndex(steady, sr)
            when {
                mod <= 0.10 -> "gated scored $mod, want > 0.10"
                flat >= 0.05 -> "steady scored $flat, want < 0.05"
                mod <= flat * 3 -> "margin too small: $mod vs $flat"
                else -> null
            }
        }

    fun spectrogramShowsTheModulationBand() =
        check("spectrogram puts a gated carrier's energy near 100 Hz") {
            val sr = 44_100.0
            val n = (sr * 2).toInt()
            val gated = DoubleArray(n) {
                val t = it / sr
                val on = if ((t * 100.0) % 1.0 < 0.06) 1.0 else 0.0
                on * sin(2.0 * Math.PI * 10_000.0 * t)
            }
            val s = Spectrogram.ofEnvelope(gated, sr) ?: return@check "returned null"

            // Which bin covers 100 Hz, given bins span 0..maxFrequencyHz.
            val binWidth = s.maxFrequencyHz / s.binCount
            val target = (100.0 / binWidth).toInt()

            // Average each bin across frames, then check the 100 Hz bin leads.
            val perBin = DoubleArray(s.binCount) { b ->
                (0 until s.frameCount).sumOf { s.cells[it][b] } / s.frameCount
            }
            val peak = perBin.indices.maxByOrNull { perBin[it] }!!
            when {
                s.cells.size != s.frameCount -> "frame count mismatch"
                s.cells[0].size != s.binCount -> "bin count mismatch"
                perBin.any { it < 0.0 || it > 1.0 } -> "cells outside 0..1"
                kotlin.math.abs(peak - target) > 1 -> "peak bin $peak, want about $target"
                else -> null
            }
        }

    /**
     * Documents the reduction bug found on hardware.
     *
     * With the Earth's 45 uT on one axis and a 1 uT alternating field on another,
     * the magnitude |B| barely moves -- the change is second order. Fitting the
     * axes separately recovers the full amplitude. This is why MagCapture keeps
     * x, y and z rather than reducing to magnitude.
     */
    fun magnitudeReductionDestroysAPerpendicularAcField() =
        check("per-axis fit recovers an AC field that magnitude loses") {
            val n = 300
            val t = DoubleArray(n) { it * (1.0 / 99.0) }
            val earthX = 45.0
            val acY = 1.0

            val magnitude = DoubleArray(n) {
                val y = acY * sin(2.0 * Math.PI * 50.0 * t[it])
                kotlin.math.sqrt(earthX * earthX + y * y)
            }
            val axisY = DoubleArray(n) { acY * sin(2.0 * Math.PI * 50.0 * t[it]) }

            val fromMagnitude = SineFit.fit(t, magnitude, 50.0).amplitudeUt
            val fromAxis = SineFit.fit(t, axisY, 50.0).amplitudeUt

            when {
                fromAxis < 0.95 * acY -> "per-axis fit recovered only $fromAxis of $acY"
                fromMagnitude > 0.05 * acY ->
                    "magnitude unexpectedly retained $fromMagnitude -- has the bug been reintroduced?"
                else -> null
            }
        }

    fun spectrogramRefusesShortInput() = check("spectrogram refuses input too short to frame") {
        if (Spectrogram.ofEnvelope(DoubleArray(512), 44_100.0) != null)
            "produced a spectrogram from 512 samples" else null
    }

    fun fftFindsAKnownTone() = check("FFT finds a known tone") {
        val sr = 2_000.0
        val n = 4_096
        val tone = DoubleArray(n) { sin(2.0 * Math.PI * 100.0 * it / sr) }
        val power = Fft.powerSpectrum(tone)
        val freqs = Fft.frequencies(n, sr)
        val peak = power.indices.maxByOrNull { power[it] }!!
        if (abs(freqs[peak] - 100.0) >= 1.0) "peak at ${freqs[peak]} Hz" else null
    }
}
