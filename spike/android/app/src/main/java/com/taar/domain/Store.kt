package com.taar.domain

/**
 * Persistence.
 *
 * A line-oriented text format rather than JSON. Three reasons, in order: it can be
 * round-trip tested without a serialisation dependency, it can be read with `cat`
 * when a reading looks wrong on site, and a truncated file loses the last record
 * instead of the whole document.
 *
 * Every file starts with a version line. A format that changes mid-event without
 * one leaves unreadable readings, and readings are the thing that cannot be retaken.
 *
 * [FileSystem] is an interface so the whole store is testable off-device; the
 * Android implementation is a dozen lines over the app's files directory.
 */

interface FileSystem {
    fun read(path: String): String?
    fun write(path: String, contents: String)
    fun list(prefix: String): List<String>
    fun delete(path: String)
}

/** In-memory implementation, used by the tests and by UI previews. */
class MemoryFileSystem : FileSystem {
    private val files = linkedMapOf<String, String>()
    override fun read(path: String): String? = files[path]
    override fun write(path: String, contents: String) { files[path] = contents }
    override fun list(prefix: String): List<String> = files.keys.filter { it.startsWith(prefix) }
    override fun delete(path: String) { files.remove(path) }
}

class Store(private val fs: FileSystem) {

    companion object {
        const val VERSION = "taar/2"   // v2 adds lineContrast to each reading
        private const val SEP = "\t"
        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t")
            .replace("\n", "\\n")
        private fun unesc(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> { out.append('\\'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        'n' -> { out.append('\n'); i += 2 }
                        else -> { out.append(c); i++ }
                    }
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }

        private fun arr(v: DoubleArray) = v.joinToString(" ")
        private fun parseArr(s: String) =
            if (s.isBlank()) DoubleArray(0)
            else s.trim().split(" ").map { it.toDouble() }.toDoubleArray()
    }

    // ---- installations ----

    private fun installationPath(id: String) = "installation/$id.tsv"

    fun saveInstallation(inst: Installation) {
        val sb = StringBuilder()
        sb.appendLine(VERSION)
        sb.appendLine(listOf("installation", esc(inst.id), esc(inst.name), inst.isBenchRig)
            .joinToString(SEP))
        for (c in inst.circuits) {
            sb.appendLine(listOf(
                "circuit", esc(c.id), esc(c.label),
                c.breakerRatingA?.toString() ?: "",
                c.utPerAmp?.toString() ?: "",
            ).joinToString(SEP))
            c.baseline?.let { b ->
                sb.appendLine(listOf(
                    "baseline", esc(c.id), b.recordedAtMillis.toString(),
                    arr(b.fieldAmplitudesUt), arr(b.arcModulationIndices), arr(b.lineConfidences),
                ).joinToString(SEP))
            }
        }
        fs.write(installationPath(inst.id), sb.toString())
    }

    fun loadInstallation(id: String): Installation? {
        val text = fs.read(installationPath(id)) ?: return null
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.firstOrNull() != VERSION) return null

        var name = ""
        var bench = false
        val circuits = linkedMapOf<String, Circuit>()
        val baselines = mutableMapOf<String, Baseline>()

        for (line in lines.drop(1)) {
            val f = line.split(SEP)
            when (f[0]) {
                "installation" -> { name = unesc(f[2]); bench = f[3].toBoolean() }
                "circuit" -> {
                    val cid = unesc(f[1])
                    circuits[cid] = Circuit(
                        id = cid,
                        label = unesc(f[2]),
                        breakerRatingA = f[3].takeIf { it.isNotEmpty() }?.toDouble(),
                        utPerAmp = f[4].takeIf { it.isNotEmpty() }?.toDouble(),
                    )
                }
                "baseline" -> baselines[unesc(f[1])] = Baseline(
                    recordedAtMillis = f[2].toLong(),
                    fieldAmplitudesUt = parseArr(f[3]),
                    arcModulationIndices = parseArr(f[4]),
                    lineConfidences = parseArr(f[5]),
                )
            }
        }

        return Installation(
            id = id,
            name = name,
            isBenchRig = bench,
            circuits = circuits.values.map { it.copy(baseline = baselines[it.id]) },
        )
    }

    fun listInstallationIds(): List<String> =
        fs.list("installation/").map { it.removePrefix("installation/").removeSuffix(".tsv") }

    // ---- readings ----

    private fun readingsPath(installationId: String) = "readings/$installationId.tsv"

    /**
     * Appends rather than rewriting. A capture is the one thing in this app that
     * cannot be retaken, so the write path must not be able to lose earlier ones.
     */
    fun appendReading(installationId: String, r: Reading, label: Status? = null) {
        val existing = fs.read(readingsPath(installationId))
        val sb = StringBuilder()
        if (existing == null) sb.appendLine(VERSION) else sb.append(existing)
        sb.appendLine(listOf(
            esc(r.circuitId), r.epochMillis.toString(), r.fieldAmplitudeUt.toString(),
            r.lineConfidence.toString(), r.arcModulationIndex.toString(),
            r.fieldEstimateUsable.toString(), label?.name ?: "",
            r.lineContrast.toString(),
        ).joinToString(SEP))
        fs.write(readingsPath(installationId), sb.toString())
    }

    data class LabelledReading(val reading: Reading, val label: Status?)

    fun loadReadings(installationId: String): List<LabelledReading> {
        val text = fs.read(readingsPath(installationId)) ?: return emptyList()
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.firstOrNull() != VERSION) return emptyList()

        return lines.drop(1).mapNotNull { line ->
            val f = line.split(SEP)
            if (f.size < 7) return@mapNotNull null
            LabelledReading(
                Reading(
                    circuitId = unesc(f[0]),
                    epochMillis = f[1].toLong(),
                    fieldAmplitudeUt = f[2].toDouble(),
                    lineConfidence = f[3].toDouble(),
                    arcModulationIndex = f[4].toDouble(),
                    fieldEstimateUsable = f[5].toBoolean(),
                    lineContrast = f.getOrNull(7)?.toDoubleOrNull() ?: 0.0,
                ),
                label = f[6].takeIf { it.isNotEmpty() }?.let { runCatching { Status.valueOf(it) }.getOrNull() },
            )
        }
    }

    /**
     * Recalibrates a circuit's thresholds from its labelled readings.
     *
     * Bench installations are excluded by the caller, not here, so the exclusion is
     * visible at the call site rather than buried in a helper.
     */
    fun calibrate(installationId: String, circuitId: String, baseline: Baseline): Thresholds {
        val readings = loadReadings(installationId).filter { it.reading.circuitId == circuitId }
        val healthy = readings.filter { it.label == Status.HEALTHY }
            .map { it.reading.fieldAmplitudeUt }.toDoubleArray()
        val faulty = readings.filter { it.label == Status.CRITICAL || it.label == Status.WARNING }
            .map { it.reading.fieldAmplitudeUt }.toDoubleArray()
        return Thresholds.calibrate(healthy, faulty, baseline.fieldAmplitudesUt)
    }
}
