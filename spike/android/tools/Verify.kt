package com.taar.tools

import com.taar.domain.DomainChecks
import com.taar.dsp.GoldenChecks
import java.io.File

/**
 * Command-line runner for the golden and domain checks, for a machine with no
 * Android SDK. See tools/verify.sh.
 *
 * Note: Kotlin block comments nest, so a shell glob containing a slash-star
 * sequence cannot appear inside one. The compile line lives in the script.
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "golden")

    val sections = listOf(
        "DSP — golden vectors from the Python reference" to GoldenChecks.runAll(dir)
            .map { Triple(it.name, it.passed, it.detail) },
        "Domain — behaviour of the rules, thresholds and model" to DomainChecks.runAll()
            .map { Triple(it.name, it.passed, it.detail) },
    )

    var passed = 0
    var total = 0
    for ((title, results) in sections) {
        println("\n$title")
        println("-".repeat(title.length))
        for ((name, ok, detail) in results) {
            println("  ${if (ok) "PASS" else "FAIL"}  $name")
            if (!ok) println("        $detail")
            if (ok) passed++
            total++
        }
    }

    println("\n$passed/$total passed")
    if (passed != total) kotlin.system.exitProcess(1)
}
