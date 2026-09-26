package com.taar.domain

/**
 * The fault catalogue and the rules that rank it.
 *
 * Deliberately a table rather than a model. A technician can be told why a reading
 * was flagged, and a rule can be corrected on site by editing one row. A classifier
 * would be more flexible and would not be able to explain itself, which for a tool
 * that tells someone to open a live panel is the wrong trade.
 *
 * Every entry ends in an action, because a fault name on its own is not useful to
 * the person holding the phone.
 */

data class Evidence(val description: String, val holds: Boolean)

data class Fault(
    val id: String,
    val label: String,
    /** What to do. Plain, imperative, and safe to follow. */
    val action: String,
    val severity: Status,
    /** Weight when several faults fit; higher wins ties. */
    val priority: Int,
    val evaluate: (Metrics, Thresholds) -> List<Evidence>,
)

data class RankedFault(
    val fault: Fault,
    val evidence: List<Evidence>,
    val score: Double,
) {
    val supported: Int get() = evidence.count { it.holds }
    val total: Int get() = evidence.size
}

object FaultCatalogue {

    val faults: List<Fault> = listOf(
        Fault(
            id = "arcing",
            label = "Arcing / loose connection",
            action = "A loose connection can start a fire. Do not open the panel yourself " +
                "unless you are qualified. Isolate the circuit and have the terminations checked.",
            severity = Status.CRITICAL,
            priority = 100,
            evaluate = { m, t ->
                listOf(
                    Evidence("arc modulation ${fmt(m.arcZ)} MAD above baseline", m.arcZ >= t.warningZ),
                    Evidence("current flowing", m.isLive),
                )
            },
        ),
        Fault(
            id = "overload",
            label = "Load above breaker rating",
            action = "Measured load is at or above the breaker's rating. Move some load " +
                "to another circuit, or have the circuit uprated by a qualified electrician.",
            severity = Status.CRITICAL,
            priority = 95,
            evaluate = { m, _ ->
                listOf(
                    Evidence("current at ${pct(m.loadVsRating)} of rating",
                        (m.loadVsRating ?: 0.0) >= 0.9),
                    Evidence("field estimate usable", m.fieldUsable),
                )
            },
        ),
        Fault(
            id = "high_load",
            label = "Load higher than usual",
            action = "This circuit is drawing more current than when its reference was " +
                "recorded. If you have just switched something on, that is expected. If not, " +
                "check what has been added to it.",
            severity = Status.WARNING,
            priority = 60,
            evaluate = { m, t ->
                val now = m.currentNowA
                val ref = m.referenceCurrentA
                listOf(
                    Evidence(
                        if (now != null && ref != null)
                            "current ${String.format("%.1f", now)} A, was ${String.format("%.1f", ref)} A at reference"
                        else "load ${fmt(m.loadZ)} MAD above baseline",
                        m.loadAboveReference(t),
                    ),
                    Evidence("still within breaker rating", (m.loadVsRating ?: 0.0) < 0.9),
                    Evidence("field estimate usable", m.fieldUsable),
                )
            },
        ),
        // Both isolation rules hinge on what the technician says, not on the
        // reference. The earlier version fired whenever the reference had no current
        // and the reading did -- which is also what switching on a kettle looks like,
        // so every ordinary load was reported as a back-feed. A "circuit not live"
        // rule went for the same reason: a kettle switching off looked like a lost
        // supply. The phone senses current, not voltage; only the person at the
        // board knows the breaker is off.
        Fault(
            id = "unexpectedly_live",
            label = "Current on a circuit you switched off",
            action = "You said this circuit's supply is off, but current is flowing in the " +
                "cable. Stop. The breaker may be mislabelled or there may be a back-feed. " +
                "Do not work on this circuit.",
            severity = Status.CRITICAL,
            priority = 99,
            evaluate = { m, _ ->
                listOf(
                    Evidence("current flowing now (${fmt(m.lineConfidence)})", m.isLive),
                    Evidence("you said the supply is off", m.supplyIsolated),
                )
            },
        ),
        Fault(
            id = "isolation_unclear",
            label = "Can't confirm the switched-off circuit",
            action = "You said this circuit's supply is off, but the signal is above room " +
                "noise. Keep the phone still and measure again. Do not assume it is off.",
            severity = Status.WARNING,
            priority = 80,
            evaluate = { m, _ ->
                listOf(
                    Evidence("signal unclear (${fmt(m.lineConfidence)})",
                        m.lineState == LineState.UNCLEAR),
                    Evidence("you said the supply is off", m.supplyIsolated),
                )
            },
        ),
        Fault(
            id = "reading_unreliable",
            label = "Reading not usable",
            action = "The field estimate was ill-conditioned — usually the sample rate " +
                "landing on a locked value. Re-run the pre-check and capture again.",
            severity = Status.UNKNOWN,
            priority = 10,
            evaluate = { m, _ -> listOf(Evidence("sine fit ill-conditioned", !m.fieldUsable)) },
        ),
    )

    private fun fmt(v: Double) = String.format("%.2f", v)
    private fun pct(v: Double?) = if (v == null) "unknown" else String.format("%.0f%%", v * 100)
}

object RulesEngine {

    /**
     * Ranks the catalogue against one reading's evidence.
     *
     * Only faults whose evidence *fully* holds are returned. A partial match is not
     * a weak diagnosis, it is a different situation, and offering it would train the
     * technician to discount what the tool says.
     */
    fun rank(metrics: Metrics, thresholds: Thresholds): List<RankedFault> =
        FaultCatalogue.faults
            .map { fault ->
                val evidence = fault.evaluate(metrics, thresholds)
                RankedFault(
                    fault = fault,
                    evidence = evidence,
                    score = if (evidence.all { it.holds }) {
                        fault.priority + metrics.worstZ
                    } else 0.0,
                )
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }

    /** Overall status: the severity of the highest-ranked fault, or healthy. */
    fun status(ranked: List<RankedFault>): Status =
        ranked.firstOrNull()?.fault?.severity ?: Status.HEALTHY
}
