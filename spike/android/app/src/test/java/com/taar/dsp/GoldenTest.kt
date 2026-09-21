package com.taar.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JUnit wrapper over [GoldenChecks].
 *
 * The assertions live in GoldenChecks so that the same implementation runs here,
 * inside Android Studio, and from tools/verify.sh on a machine with no Android
 * SDK. Two copies would eventually disagree, and the one that disagreed silently
 * would be the one that mattered.
 *
 * Run these before anything else on day one. The Kotlin they exercise was written
 * without a compiler available; the algorithms were validated in Python, the
 * transcription was not.
 */
class GoldenTest {

    private val goldenDir: File =
        sequenceOf(File("../golden"), File("golden"), File("spike/android/golden"))
            .firstOrNull { it.isDirectory }
            ?: File("../golden")

    @Test
    fun `all golden checks pass`() {
        val results = GoldenChecks.runAll(goldenDir)
        val failures = results.filterNot { it.passed }
        assertTrue(
            buildString {
                appendLine("${failures.size} of ${results.size} golden checks failed:")
                failures.forEach { appendLine("  ${it.name}: ${it.detail}") }
                appendLine("fixtures: ${goldenDir.absolutePath}")
            },
            failures.isEmpty(),
        )
    }
}
