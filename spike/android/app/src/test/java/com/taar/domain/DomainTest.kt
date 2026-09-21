package com.taar.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit wrapper over [DomainChecks]; see GoldenTest for why the split exists. */
class DomainTest {
    @Test
    fun `all domain checks pass`() {
        val results = DomainChecks.runAll()
        val failures = results.filterNot { it.passed }
        assertTrue(
            buildString {
                appendLine("${failures.size} of ${results.size} domain checks failed:")
                failures.forEach { appendLine("  ${it.name}: ${it.detail}") }
            },
            failures.isEmpty(),
        )
    }
}
