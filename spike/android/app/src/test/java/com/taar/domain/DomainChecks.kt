package com.taar.domain

import kotlin.math.abs

/**
 * Domain checks, as plain Kotlin so the same implementation runs under JUnit and
 * from tools/verify.sh.
 */
object DomainChecks {

    data class Result(val name: String, val passed: Boolean, val detail: String)

    fun runAll(): List<Result> = listOf(
        medianAndMad(),
        quantileInterpolates(),
        robustZHandlesDegenerateInput(),
        baselineEqualityComparesContents(),
        baselineRequiresEnoughSamples(),
        thresholdsOrderingHolds(),
        thresholdsWidenWithoutFaultyLabels(),
        thresholdsTightenWithFaultyLabels(),
        metricsNeedABaseline(),
        uncalibratedCircuitReportsNoAmperes(),
        healthyReadingRaisesNothing(),
        arcingIsCriticalAndRankedFirst(),
        deadCircuitIsReported(),
        unexpectedlyLiveOutranksDead(),
        overloadAndHighLoadAreMutuallyExclusive(),
        unusableFieldDoesNotProduceALoadDiagnosis(),
        storeRoundTripsAnInstallation(),
        storeAppendsWithoutLosingReadings(),
        storeRejectsAWrongVersion(),
        storeSurvivesTabsAndNewlinesInNames(),
        calibrationUsesLabelledReadings(),
        classifierLearnsAndRecognises(),
        classifierRejectsTheUnfamiliar(),
        classifierRefusesWhenClassesOverlap(),
        classifierScalesFeaturesSoOneCannotDominate(),
        classifierWithNoTrainingReturnsNothing(),
        flatBaselineDoesNotManufactureHugeZScores(),
        aRealChangeStillScoresAgainstAFlatBaseline(),
        ordinaryArcVariationDoesNotWarn(),
        aRealArcSignatureStillWarns(),
        liveThresholdSeparatesTheMeasuredPopulations(),
    )

    private fun check(name: String, block: () -> String?): Result =
        try {
            val f = block()
            Result(name, f == null, f ?: "ok")
        } catch (e: Exception) {
            Result(name, false, "threw ${e::class.simpleName}: ${e.message}")
        }

    private fun near(a: Double, b: Double, tol: Double = 1e-9) = abs(a - b) <= tol

    // ---- stats ----

    fun medianAndMad() = check("median and MAD resist an outlier") {
        val clean = doubleArrayOf(10.0, 11.0, 12.0, 11.5, 10.5)
        val withOutlier = clean + doubleArrayOf(900.0)
        val m1 = Stats.median(clean)
        val m2 = Stats.median(withOutlier)
        when {
            !near(m1, 11.0) -> "median(clean) = $m1, want 11.0"
            abs(m2 - m1) > 1.0 -> "outlier moved median from $m1 to $m2"
            Stats.mad(clean) <= 0.0 -> "MAD should be positive for spread data"
            else -> null
        }
    }

    fun quantileInterpolates() = check("quantile interpolates between samples") {
        val v = doubleArrayOf(0.0, 10.0)
        val q = Stats.quantile(v, 0.5)
        if (!near(q, 5.0)) "quantile(0.5) = $q, want 5.0" else null
    }

    fun robustZHandlesDegenerateInput() = check("robustZ returns 0 for zero spread") {
        val identical = doubleArrayOf(5.0, 5.0, 5.0, 5.0)
        val z = Stats.robustZ(99.0, identical)
        when {
            !z.isFinite() -> "z is not finite: $z"
            !near(z, 0.0) -> "z = $z, want 0.0 (no spread means no information)"
            !near(Stats.robustZ(1.0, doubleArrayOf(1.0)), 0.0) -> "single sample should give 0"
            else -> null
        }
    }

    // ---- model ----

    fun baselineEqualityComparesContents() = check("Baseline equality compares contents") {
        fun make() = Baseline(1L, doubleArrayOf(1.0, 2.0), doubleArrayOf(0.1, 0.2),
            doubleArrayOf(0.9, 0.9))
        when {
            make() != make() -> "two identical baselines compared unequal"
            make().hashCode() != make().hashCode() -> "hashCodes differ"
            else -> null
        }
    }

    fun baselineRequiresEnoughSamples() = check("Baseline below MIN_SAMPLES is insufficient") {
        val thin = Baseline(0L, doubleArrayOf(1.0), doubleArrayOf(0.1), doubleArrayOf(0.9))
        val ok = Baseline(0L, DoubleArray(3) { 1.0 }, DoubleArray(3) { 0.1 }, DoubleArray(3) { 0.9 })
        when {
            thin.isSufficient -> "1 sample reported sufficient"
            !ok.isSufficient -> "${Baseline.MIN_SAMPLES} samples reported insufficient"
            else -> null
        }
    }

    // ---- thresholds ----

    fun thresholdsOrderingHolds() = check("critical is never below warning") {
        val base = DoubleArray(20) { 10.0 + (it % 3) * 0.1 }
        val healthy = DoubleArray(10) { 10.0 + (it % 3) * 0.1 }
        val t = Thresholds.calibrate(healthy, DoubleArray(0), base)
        if (t.criticalZ < t.warningZ) "critical ${t.criticalZ} < warning ${t.warningZ}" else null
    }

    fun thresholdsWidenWithoutFaultyLabels() = check("no faulty labels gives a wide critical") {
        val base = DoubleArray(20) { 10.0 + (it % 4) * 0.2 }
        val t = Thresholds.calibrate(DoubleArray(10) { 10.0 + (it % 4) * 0.2 }, DoubleArray(0), base)
        when {
            t.criticalZ < t.warningZ * 1.9 -> "critical ${t.criticalZ} not ~2x warning ${t.warningZ}"
            !t.isProvisional && t.basedOn < Thresholds.MIN_LABELLED -> "provisional flag wrong"
            else -> null
        }
    }

    fun thresholdsTightenWithFaultyLabels() = check("faulty labels move critical between populations") {
        val base = DoubleArray(20) { 10.0 + (it % 4) * 0.2 }
        val healthy = DoubleArray(10) { 10.0 + (it % 4) * 0.2 }
        val faulty = DoubleArray(6) { 40.0 + it * 0.5 }
        val withFaulty = Thresholds.calibrate(healthy, faulty, base)
        val without = Thresholds.calibrate(healthy, DoubleArray(0), base)
        when {
            withFaulty.criticalZ <= withFaulty.warningZ -> "ordering broken"
            withFaulty.criticalZ >= Stats.robustZ(faulty[0], base) ->
                "critical ${withFaulty.criticalZ} should sit below the faulty population"
            withFaulty.criticalZ <= without.criticalZ ->
                "faulty labels should raise critical above the no-information default here"
            else -> null
        }
    }

    // ---- metrics ----

    private fun baseline(n: Int = 8, field: Double = 10.0, arc: Double = 0.02, live: Double = 0.95) =
        Baseline(
            0L,
            DoubleArray(n) { field + (it % 3) * 0.1 },
            DoubleArray(n) { arc + (it % 3) * 0.001 },
            DoubleArray(n) { live },
        )

    private fun reading(
        field: Double = 10.0, arc: Double = 0.02, live: Double = 0.95, usable: Boolean = true,
    ) = Reading(
        circuitId = "c1",
        epochMillis = 0L,
        fieldAmplitudeUt = field,
        lineConfidence = live,
        arcModulationIndex = arc,
        fieldEstimateUsable = usable,
    )

    fun metricsNeedABaseline() = check("metrics refuse without a sufficient baseline") {
        val none = Circuit("c1", "Lights")
        val thin = Circuit("c1", "Lights",
            baseline = Baseline(0L, doubleArrayOf(1.0), doubleArrayOf(0.1), doubleArrayOf(0.9)))
        when {
            Metrics.derive(reading(), none) != null -> "derived with no baseline"
            Metrics.derive(reading(), thin) != null -> "derived from a 1-sample baseline"
            else -> null
        }
    }

    fun uncalibratedCircuitReportsNoAmperes() = check("uncalibrated circuit reports no amperes") {
        val c = Circuit("c1", "Lights", breakerRatingA = 16.0, baseline = baseline())
        val m = Metrics.derive(reading(field = 40.0), c) ?: return@check "derive returned null"
        when {
            m.impliedCurrentA != null -> "reported ${m.impliedCurrentA} A without calibration"
            m.loadVsRating != null -> "reported a rating fraction without a current"
            m.loadZ <= 0 -> "load should read high, got ${m.loadZ}"
            else -> null
        }
    }

    // ---- rules ----

    private fun diagnose(
        circuit: Circuit, r: Reading, t: Thresholds = Thresholds.DEFAULT,
    ): Pair<List<RankedFault>, Status> {
        val m = Metrics.derive(r, circuit) ?: return emptyList<RankedFault>() to Status.UNKNOWN
        val ranked = RulesEngine.rank(m, t)
        return ranked to RulesEngine.status(ranked)
    }

    fun healthyReadingRaisesNothing() = check("a healthy reading raises no fault") {
        val c = Circuit("c1", "Lights", baseline = baseline())
        val (ranked, status) = diagnose(c, reading())
        when {
            ranked.isNotEmpty() -> "raised ${ranked.map { it.fault.id }}"
            status != Status.HEALTHY -> "status $status"
            else -> null
        }
    }

    fun arcingIsCriticalAndRankedFirst() = check("arcing is critical and ranked first") {
        val c = Circuit("c1", "Lights", baseline = baseline())
        val (ranked, status) = diagnose(c, reading(arc = 0.5))
        when {
            ranked.isEmpty() -> "no fault raised for a large arc signature"
            ranked.first().fault.id != "arcing" -> "first was ${ranked.first().fault.id}"
            status != Status.CRITICAL -> "status $status"
            else -> null
        }
    }

    fun deadCircuitIsReported() = check("a circuit that lost mains is reported") {
        val c = Circuit("c1", "Lights", baseline = baseline())
        val (ranked, _) = diagnose(c, reading(live = 0.001, field = 0.01))
        if (ranked.none { it.fault.id == "circuit_dead" })
            "raised ${ranked.map { it.fault.id }}" else null
    }

    fun unexpectedlyLiveOutranksDead() = check("unexpectedly live is critical") {
        val c = Circuit("c1", "Spare", baseline = baseline(live = 0.002))
        val (ranked, status) = diagnose(c, reading(live = 0.98))
        when {
            ranked.none { it.fault.id == "unexpectedly_live" } ->
                "raised ${ranked.map { it.fault.id }}"
            status != Status.CRITICAL -> "status $status"
            else -> null
        }
    }

    fun overloadAndHighLoadAreMutuallyExclusive() =
        check("overload and high load never fire together") {
            val c = Circuit("c1", "Sockets", breakerRatingA = 16.0,
                baseline = baseline(), utPerAmp = UT_PER_AMP_AT_3CM)
            // 20 A through a 16 A breaker.
            val over = diagnose(c, reading(field = 20 * UT_PER_AMP_AT_3CM)).first.map { it.fault.id }
            // 8 A: above baseline, comfortably inside the rating.
            val high = diagnose(c, reading(field = 8 * UT_PER_AMP_AT_3CM)).first.map { it.fault.id }
            when {
                "overload" in over && "high_load" in over -> "both fired at 20 A: $over"
                "overload" !in over -> "overload missing at 20 A on a 16 A breaker: $over"
                "high_load" !in high -> "high_load missing at 8 A: $high"
                "overload" in high -> "overload fired at 8 A: $high"
                else -> null
            }
        }

    // ---- store ----

    fun storeRoundTripsAnInstallation() = check("store round-trips an installation") {
        val store = Store(MemoryFileSystem())
        val original = Installation(
            id = "board-a", name = "Main DB, basement",
            circuits = listOf(
                Circuit("c1", "Lights", breakerRatingA = 6.0, baseline = baseline()),
                Circuit("c2", "Sockets", breakerRatingA = 16.0,
                    utPerAmp = UT_PER_AMP_AT_3CM, baseline = baseline(field = 30.0)),
                Circuit("c3", "Spare"),
            ),
        )
        store.saveInstallation(original)
        val back = store.loadInstallation("board-a") ?: return@check "load returned null"
        when {
            back.name != original.name -> "name: '${'$'}{back.name}'"
            back.circuits.size != 3 -> "circuit count ${'$'}{back.circuits.size}"
            back.circuits[1].utPerAmp != UT_PER_AMP_AT_3CM -> "utPerAmp lost"
            back.circuits[1].breakerRatingA != 16.0 -> "rating lost"
            back.circuits[2].baseline != null -> "invented a baseline for a circuit without one"
            back.circuits[0].baseline != original.circuits[0].baseline -> "baseline differs"
            back.circuits[0].basis != CurrentBasis.UNCALIBRATED -> "basis wrong for c1"
            back.circuits[1].basis != CurrentBasis.CALIBRATED -> "basis wrong for c2"
            else -> null
        }
    }

    fun storeAppendsWithoutLosingReadings() = check("appending never drops an earlier reading") {
        val store = Store(MemoryFileSystem())
        repeat(25) { i ->
            store.appendReading("board-a", reading(field = 10.0 + i).copy(epochMillis = i.toLong()))
        }
        val back = store.loadReadings("board-a")
        when {
            back.size != 25 -> "got ${'$'}{back.size} readings, want 25"
            back.first().reading.epochMillis != 0L -> "first reading is not the oldest"
            back.last().reading.epochMillis != 24L -> "last reading is not the newest"
            else -> null
        }
    }

    fun storeRejectsAWrongVersion() = check("a wrong version header is refused, not misread") {
        val fs = MemoryFileSystem()
        fs.write("installation/x.tsv", "taar/999\ninstallation\tx\tName\tfalse\n")
        val loaded = Store(fs).loadInstallation("x")
        if (loaded != null) "loaded an unknown format instead of refusing" else null
    }

    fun storeSurvivesTabsAndNewlinesInNames() = check("separators inside a name are escaped") {
        val store = Store(MemoryFileSystem())
        val awkward = "Main\tDB\nbasement\\rear"
        store.saveInstallation(Installation("b", awkward,
            listOf(Circuit("c1", "Lights\there"))))
        val back = store.loadInstallation("b") ?: return@check "load returned null"
        when {
            back.name != awkward -> "name came back as '${'$'}{back.name}'"
            back.circuits.size != 1 -> "circuit count ${'$'}{back.circuits.size}"
            back.circuits[0].label != "Lights\there" -> "label '${'$'}{back.circuits[0].label}'"
            else -> null
        }
    }

    fun calibrationUsesLabelledReadings() = check("calibration uses labels and ignores unlabelled") {
        val store = Store(MemoryFileSystem())
        val b = baseline(n = 12)
        repeat(8) { store.appendReading("i", reading(field = 10.0 + it * 0.05), Status.HEALTHY) }
        repeat(4) { store.appendReading("i", reading(field = 45.0 + it), Status.CRITICAL) }
        repeat(6) { store.appendReading("i", reading(field = 999.0), null) }
        val t = store.calibrate("i", "c1", b)
        when {
            t.basedOn != 12 -> "basedOn ${'$'}{t.basedOn}, want 12 (unlabelled must be ignored)"
            t.criticalZ <= t.warningZ -> "ordering broken"
            t.isProvisional -> "12 labels should not be provisional"
            else -> null
        }
    }

    // ---- classifier ----

    private fun samples(label: String, n: Int, base: DoubleArray, jitter: Double = 0.1) =
        (0 until n).map { i ->
            LabelledSample(label, DoubleArray(base.size) { base[it] + (i % 3 - 1) * jitter })
        }

    fun classifierLearnsAndRecognises() = check("classifier learns classes and recognises them") {
        val c = CentroidClassifier()
        c.train(
            samples("healthy", 8, doubleArrayOf(0.0, 0.0, 0.95, 0.2)) +
                samples("arcing", 8, doubleArrayOf(0.5, 9.0, 0.95, 0.2)),
        )
        val p = c.classify(doubleArrayOf(0.4, 8.8, 0.95, 0.2))
        when {
            c.classCount != 2 -> "learned ${'$'}{c.classCount} classes, want 2"
            p == null -> "rejected a sample sitting on the arcing centroid"
            p.label != "arcing" -> "predicted ${'$'}{p.label}"
            else -> null
        }
    }

    fun classifierRejectsTheUnfamiliar() = check("classifier rejects an unfamiliar reading") {
        val c = CentroidClassifier()
        c.train(
            samples("healthy", 8, doubleArrayOf(0.0, 0.0, 0.95, 0.2)) +
                samples("arcing", 8, doubleArrayOf(0.5, 9.0, 0.95, 0.2)),
        )
        val far = c.classify(doubleArrayOf(400.0, -300.0, 0.0, 50.0))
        if (far != null) "labelled a wildly out-of-range reading as '${'$'}{far.label}'" else null
    }

    fun classifierRefusesWhenClassesOverlap() = check("classifier refuses an ambiguous reading") {
        val c = CentroidClassifier()
        // Two classes close together; a point midway belongs to neither.
        c.train(
            samples("a", 8, doubleArrayOf(0.0, 1.0, 0.9, 0.1), jitter = 0.02) +
                samples("b", 8, doubleArrayOf(0.0, 1.4, 0.9, 0.1), jitter = 0.02),
        )
        val midway = c.classify(doubleArrayOf(0.0, 1.2, 0.9, 0.1))
        if (midway != null) "answered '${'$'}{midway.label}' for a point midway between two classes" else null
    }

    fun classifierScalesFeaturesSoOneCannotDominate() =
        check("a wide-ranging feature does not dominate the distance") {
            val c = CentroidClassifier()
            // Feature 0 ranges over thousands; feature 1 is the one that separates.
            val a = (0 until 8).map {
                LabelledSample("a", doubleArrayOf(it * 1000.0, 0.0, 0.9, 0.1))
            }
            val b = (0 until 8).map {
                LabelledSample("b", doubleArrayOf(it * 1000.0, 10.0, 0.9, 0.1))
            }
            c.train(a + b)
            val p = c.classify(doubleArrayOf(3500.0, 9.8, 0.9, 0.1))
            when {
                p == null -> "rejected a sample clearly in class b"
                p.label != "b" -> "predicted '${'$'}{p.label}'; feature 0 dominated"
                else -> null
            }
        }

    fun classifierWithNoTrainingReturnsNothing() = check("an untrained classifier answers nothing") {
        val c = CentroidClassifier()
        when {
            c.classCount != 0 -> "classCount ${'$'}{c.classCount}"
            c.classify(doubleArrayOf(1.0, 2.0, 3.0, 4.0)) != null -> "answered without training"
            else -> null
        }
    }

    // ---- regressions from hardware ----

    fun flatBaselineDoesNotManufactureHugeZScores() =
        check("a baseline flatter than the sensor cannot produce a huge z") {
            // Three captures of a quiet circuit, all identical to the resolution
            // the sensor reports. Observed on a real phone.
            val flat = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            val z = Stats.robustZ(0.21, flat, Metrics.MIN_FIELD_SPREAD_UT)
            when {
                !z.isFinite() -> "z is not finite: $z"
                z > 3.0 -> "0.21 uT against a flat baseline scored $z MAD"
                else -> null
            }
        }

    fun aRealChangeStillScoresAgainstAFlatBaseline() =
        check("the floor does not mask a genuinely large change") {
            val flat = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            // 47 uT is a 5 A load at 3 cm. It must still read as far out of range.
            val z = Stats.robustZ(47.0, flat, Metrics.MIN_FIELD_SPREAD_UT)
            if (z < 50.0) "a 47 uT change scored only $z MAD" else null
        }

    /**
     * Nine captures on hardware put the arc modulation index between 0.0062 and
     * 0.0525. None was next to an arc; the spread is what the statistic does on an
     * ordinary appliance. It must not warn.
     */
    fun ordinaryArcVariationDoesNotWarn() = check("ordinary arc variation does not warn") {
        val observed = doubleArrayOf(
            0.0229, 0.0234, 0.0137, 0.0164, 0.0264, 0.0062, 0.0304, 0.0245,
        )
        val worst = 0.0525
        val z = Stats.robustZ(worst, observed, Metrics.MIN_ARC_SPREAD)
        if (z >= Thresholds.DEFAULT.warningZ)
            "the highest ordinary reading scored $z MAD, at or above the warn threshold"
        else null
    }

    fun aRealArcSignatureStillWarns() = check("a real arc signature still warns") {
        val observed = doubleArrayOf(
            0.0229, 0.0234, 0.0137, 0.0164, 0.0264, 0.0062, 0.0304, 0.0245,
        )
        // The reference measured a gated carrier -- the arc stand-in -- above 0.10.
        val z = Stats.robustZ(0.13, observed, Metrics.MIN_ARC_SPREAD)
        if (z < Thresholds.DEFAULT.warningZ)
            "an arc-level reading scored only $z MAD and would not warn" else null
    }

    /**
     * Holds the live threshold against the readings it was derived from. If either
     * population moves across it, this fails and the number needs choosing again
     * rather than nudging.
     */
    fun liveThresholdSeparatesTheMeasuredPopulations() =
        check("live threshold sits between the measured populations") {
            fun confidence(contrast: Double) = contrast / (contrast + 9.0)

            // Every capture on real hardware so far. See Metrics.LIVE_CONFIDENCE.
            val idle = listOf(3.0, 2.0, 2.0, 6.0, 3.0, 1.0, 6.0, 3.0, 0.0, 4.0, 5.0, 7.0)
            val kettleOn = listOf(28.0, 37.0, 43.0, 58.0, 62.0)
            val fridgeOn = listOf(9.0, 16.0)

            val idleStates = idle.map { LineState.of(confidence(it)) }
            val kettleStates = kettleOn.map { LineState.of(confidence(it)) }
            val fridgeStates = fridgeOn.map { LineState.of(confidence(it)) }

            when {
                idleStates.any { it != LineState.NONE } ->
                    "an idle capture read as ${idleStates.first { it != LineState.NONE }}"
                kettleStates.any { it != LineState.FLOWING } ->
                    "a kettle capture read as ${kettleStates.first { it != LineState.FLOWING }}"
                // A small load near room noise may be unclear, but never "no current".
                fridgeStates.any { it == LineState.NONE } -> "a running fridge read as no current"
                else -> null
            }
        }

    fun unusableFieldDoesNotProduceALoadDiagnosis() =
        check("an unusable field estimate raises no load fault") {
            val c = Circuit("c1", "Sockets", breakerRatingA = 16.0,
                baseline = baseline(), utPerAmp = UT_PER_AMP_AT_3CM)
            val (ranked, _) = diagnose(c, reading(field = 500.0, usable = false))
            val ids = ranked.map { it.fault.id }
            when {
                "overload" in ids || "high_load" in ids -> "load fault from unusable field: $ids"
                "reading_unreliable" !in ids -> "did not flag the reading as unreliable: $ids"
                else -> null
            }
        }
}
