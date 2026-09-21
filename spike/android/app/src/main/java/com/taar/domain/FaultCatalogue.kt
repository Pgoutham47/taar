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
    val labelTe: String,
    /** What to do. Plain, imperative, and safe to follow. */
    val action: String,
    val actionTe: String,
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
            id = "circuit_dead",
            label = "Circuit not live",
            labelTe = "సర్క్యూట్ లో కరెంట్ లేదు",
            action = "No mains detected. If you expected this circuit to be off, " +
                "this confirms it — but confirm with a contact tester before working on it.",
            actionTe = "కరెంట్ కనిపించలేదు. పని మొదలుపెట్టే ముందు టెస్టర్‌తో మళ్ళీ చూడండి.",
            severity = Status.WARNING,
            priority = 90,
            evaluate = { m, _ ->
                listOf(
                    Evidence("no 50 Hz component now (${fmt(m.lineConfidence)})", !m.isLive),
                    Evidence("was live at baseline (${fmt(m.baselineLineConfidence)})", m.wasLive),
                )
            },
        ),
        Fault(
            id = "arcing",
            label = "Arcing / loose connection",
            labelTe = "ఆర్సింగ్ / వదులైన కనెక్షన్",
            action = "A loose connection can start a fire. Do not open the panel yourself " +
                "unless you are qualified. Isolate the circuit and have the terminations checked.",
            actionTe = "వదులైన కనెక్షన్ మంటలకు దారితీయవచ్చు. సర్క్యూట్ ఆఫ్ చేసి, " +
                "అర్హత ఉన్న ఎలక్ట్రీషియన్‌తో టెర్మినల్స్ చెక్ చేయించండి.",
            severity = Status.CRITICAL,
            priority = 100,
            evaluate = { m, t ->
                listOf(
                    Evidence("arc modulation ${fmt(m.arcZ)} MAD above baseline", m.arcZ >= t.warningZ),
                    Evidence("circuit is live", m.isLive),
                )
            },
        ),
        Fault(
            id = "overload",
            label = "Load above breaker rating",
            labelTe = "బ్రేకర్ సామర్థ్యం కంటే ఎక్కువ లోడ్",
            action = "Measured load is at or above the breaker's rating. Move some load " +
                "to another circuit, or have the circuit uprated by a qualified electrician.",
            actionTe = "లోడ్ బ్రేకర్ సామర్థ్యానికి చేరింది. కొంత లోడ్ వేరే సర్క్యూట్‌కు మార్చండి.",
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
            labelTe = "మామూలు కంటే ఎక్కువ లోడ్",
            action = "This circuit is drawing more than its own baseline. Check what has " +
                "been added to it since the reference was recorded.",
            actionTe = "ఈ సర్క్యూట్ మామూలు కంటే ఎక్కువ లాగుతోంది. కొత్తగా ఏమి కలిపారో చూడండి.",
            severity = Status.WARNING,
            priority = 60,
            evaluate = { m, t ->
                listOf(
                    Evidence("load ${fmt(m.loadZ)} MAD above baseline", m.loadZ >= t.warningZ),
                    Evidence("still within breaker rating", (m.loadVsRating ?: 0.0) < 0.9),
                    Evidence("field estimate usable", m.fieldUsable),
                )
            },
        ),
        Fault(
            id = "unexpectedly_live",
            label = "Circuit live when it should not be",
            labelTe = "ఆఫ్ చేసినా కరెంట్ ఉంది",
            action = "Mains is present on a circuit recorded as dead. Stop. There may be a " +
                "back-feed or a mislabelled breaker. Do not work on this circuit.",
            actionTe = "ఆఫ్ అని రికార్డ్ అయిన సర్క్యూట్‌లో కరెంట్ ఉంది. ఆపండి — " +
                "బ్రేకర్ లేబుల్ తప్పు కావచ్చు. ఈ సర్క్యూట్ మీద పని చేయవద్దు.",
            severity = Status.CRITICAL,
            priority = 99,
            evaluate = { m, _ ->
                listOf(
                    Evidence("50 Hz present now (${fmt(m.lineConfidence)})", m.isLive),
                    Evidence("baseline had none (${fmt(m.baselineLineConfidence)})", !m.wasLive),
                )
            },
        ),
        Fault(
            id = "reading_unreliable",
            label = "Reading not usable",
            labelTe = "రీడింగ్ నమ్మదగినది కాదు",
            action = "The field estimate was ill-conditioned — usually the sample rate " +
                "landing on a locked value. Re-run the pre-check and capture again.",
            actionTe = "రీడింగ్ సరిగ్గా రాలేదు. ప్రీ-చెక్ మళ్ళీ చేసి, మళ్ళీ కొలవండి.",
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
