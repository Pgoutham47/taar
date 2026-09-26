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
        switchingALoadOffIsNotAFault(),
        switchingALoadOnIsNotAFault(),
        currentOnASwitchedOffCircuitIsCritical(),
        unclearSignalOnASwitchedOffCircuitWarns(),
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
        idleReadingReportsNoAmperes(),
        calibrationFromTheKettleReadsBack(),
        calibrationRefusesWhenOffAlreadyFlowing(),
        calibrationRefusesWhenOnShowsNothing(),
        storeRoundTripsSupplyIsolated(),
        storeRoundTripsSettings(),
        calibratedKettleRaisesHigherLoad(),
        calibratedSmallRiseIsNotAHigherLoad(),
        calibratedIdleIsNotAHigherLoad(),
        motionLevelsAreOrdered(),
        motionRmsCombinesAxes(),
        steadyHandIsNotMoved(),
        fusionHealthyIsNoAnomaly(),
        fusionArcWithAiAgreementIsArcing(),
        fusionArcPlusHighCurrentIsElectricalAnomaly(),
        fusionAudioOnlyIsAcousticAnomaly(),
        fusionCurrentOnlyReportsNoArcPattern(),
        fusionMovedIsUnreliableButKeepsCriticalGuidance(),
        fusionCurrentOnIsolatedSurvivesMovement(),
        fusionArcSoundWithoutCurrentIsNotFromCable(),
        fusionNeverClaimsSafety(),
        scanFindsAConsistentZone(),
        scanIgnoresASingleSpike(),
        scanIgnoresDiscardedPoints(),
        scanReportsUniformSignal(),
        scanNeedsEnoughPoints(),
        scanDownweightsFairPoints(),
        scanStrengthIsBounded(),
        scanStoreRoundTrips(),
        assistantPromptCarriesEvidenceAndRules(),
        assistantPromptStaysInsideTheWindow(),
        assistantFlagsCertaintyAboutSafety(),
        knowledgeFindsTheRightNote(),
        knowledgeFallsBackToTheOverview(),
        knowledgeNotesMakeNoSafetyClaims(),
        generalPromptStaysInsideTheWindow(),
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
        isolated: Boolean = false,
    ) = Reading(
        circuitId = "c1",
        epochMillis = 0L,
        fieldAmplitudeUt = field,
        lineConfidence = live,
        arcModulationIndex = arc,
        fieldEstimateUsable = usable,
        supplyIsolated = isolated,
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

    fun switchingALoadOffIsNotAFault() = check("switching a load off is not a fault") {
        // Reference taken with the kettle running, reading taken after it stopped.
        val c = Circuit("c1", "Kettle", baseline = baseline(live = 0.83))
        val (ranked, status) = diagnose(c, reading(live = 0.04))
        when {
            ranked.isNotEmpty() -> "raised ${ranked.map { it.fault.id }}"
            status != Status.HEALTHY -> "status $status"
            else -> null
        }
    }

    /**
     * The kettle regression. Reference with the kettle off, reading with it on:
     * the old rules called this a back-feed and marked it CRITICAL.
     */
    fun switchingALoadOnIsNotAFault() = check("switching a load on is not a fault") {
        val c = Circuit("c1", "Kettle", baseline = baseline(live = 0.20))
        val (ranked, status) = diagnose(c, reading(live = 0.83))
        when {
            ranked.isNotEmpty() -> "raised ${ranked.map { it.fault.id }}"
            status != Status.HEALTHY -> "status $status"
            else -> null
        }
    }

    fun currentOnASwitchedOffCircuitIsCritical() =
        check("current on a circuit the technician switched off is critical") {
            val c = Circuit("c1", "Spare", baseline = baseline(live = 0.95))
            val (ranked, status) = diagnose(c, reading(live = 0.83, isolated = true))
            when {
                ranked.firstOrNull()?.fault?.id != "unexpectedly_live" ->
                    "raised ${ranked.map { it.fault.id }}"
                status != Status.CRITICAL -> "status $status"
                else -> null
            }
        }

    fun unclearSignalOnASwitchedOffCircuitWarns() =
        check("an unclear signal on a switched-off circuit warns, and silence does not") {
            val c = Circuit("c1", "Spare", baseline = baseline())
            // 10x contrast is 10/19 = 0.53, inside the unclear band.
            val unclear = diagnose(c, reading(live = 0.53, isolated = true)).first.map { it.fault.id }
            val quiet = diagnose(c, reading(live = 0.04, isolated = true)).first.map { it.fault.id }
            when {
                "isolation_unclear" !in unclear -> "unclear signal raised $unclear"
                quiet.isNotEmpty() -> "a quiet isolated circuit raised $quiet"
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

    // ---- amp calibration ----

    fun idleReadingReportsNoAmperes() = check("an idle reading reports no amperes") {
        val c = Circuit("c1", "Kettle", baseline = baseline(field = 0.05), utPerAmp = 0.045)
        val m = Metrics.derive(reading(field = 0.06, live = 0.25), c)
            ?: return@check "derive returned null"
        if (m.impliedCurrentA != null) "reported ${m.impliedCurrentA} A from room noise" else null
    }

    fun calibrationFromTheKettleReadsBack() = check("calibration from the kettle reads back") {
        val amps = AmpCalibration.ampsFromWatts(1200.0)
        val r = AmpCalibration.calibrate(
            offFields = listOf(0.04, 0.04, 0.08),
            offConfidences = listOf(0.25, 0.09, 0.39),
            onFields = listOf(0.21, 0.27, 0.25, 0.18, 0.26),
            onConfidences = listOf(0.76, 0.81, 0.83, 0.87, 0.87),
            amps = amps,
        )
        if (r !is AmpCalibration.Result.Ok) return@check "failed: $r"
        val c = Circuit("c1", "Kettle", baseline = baseline(field = 0.05), utPerAmp = r.utPerAmp)
        val back = Metrics.derive(reading(field = r.onMedianUt, live = 0.83), c)?.impliedCurrentA
        when {
            !near(amps, 5.217, 0.001) -> "1200 W gave $amps A"
            !near(r.utPerAmp, 0.25 / amps, 1e-9) -> "uT/A ${r.utPerAmp}, want median-on / amps"
            back == null -> "no current read back from a calibrated, flowing circuit"
            !near(back, amps, 1e-9) -> "read back $back A, want $amps"
            else -> null
        }
    }

    fun calibrationRefusesWhenOffAlreadyFlowing() =
        check("calibration refuses when current flows with the appliance off") {
            val r = AmpCalibration.calibrate(
                offFields = listOf(0.2, 0.2, 0.2), offConfidences = listOf(0.8, 0.8, 0.8),
                onFields = listOf(0.4, 0.4, 0.4), onConfidences = listOf(0.9, 0.9, 0.9),
                amps = 5.0,
            )
            if (r !is AmpCalibration.Result.Failed) "accepted: $r" else null
        }

    fun calibrationRefusesWhenOnShowsNothing() =
        check("calibration refuses when the appliance shows no current") {
            val r = AmpCalibration.calibrate(
                offFields = listOf(0.05, 0.05), offConfidences = listOf(0.2, 0.2),
                onFields = listOf(0.06, 0.06), onConfidences = listOf(0.3, 0.3),
                amps = 5.0,
            )
            if (r !is AmpCalibration.Result.Failed) "accepted: $r" else null
        }

    fun storeRoundTripsSupplyIsolated() = check("store round-trips the supply-off flag") {
        val store = Store(MemoryFileSystem())
        store.appendReading("b", reading(isolated = true))
        store.appendReading("b", reading(isolated = false))
        val back = store.loadReadings("b").map { it.reading.supplyIsolated }
        if (back != listOf(true, false)) "read back $back" else null
    }

    fun storeRoundTripsSettings() = check("store round-trips settings") {
        val store = Store(MemoryFileSystem())
        store.saveSetting("circuit", "c\ttab")
        store.saveSetting("board", "Home")
        store.saveSetting("board", "Shop")
        when {
            store.loadSetting("circuit") != "c\ttab" -> "circuit read ${store.loadSetting("circuit")}"
            store.loadSetting("board") != "Shop" -> "board read ${store.loadSetting("board")}"
            store.loadSetting("missing") != null -> "missing key returned a value"
            else -> null
        }
    }

    // ---- load in amperes ----

    /** Three reference captures of a calibrated kettle cord, all at [field] and [live]. */
    private fun kettle(field: Double, live: Double) = Circuit(
        "c1", "Kettle", breakerRatingA = 16.0, utPerAmp = 0.038,
        baseline = Baseline(0L, DoubleArray(3) { field }, DoubleArray(3) { 0.02 }, DoubleArray(3) { live }),
    )

    /**
     * The case that motivated amperes. Reference with the kettle off, reading with it
     * boiling: 0.05 to 0.19 uT is about 1 MAD of field and never warned, but it is
     * 0 A to 5 A.
     */
    fun calibratedKettleRaisesHigherLoad() = check("a calibrated kettle switching on is a higher load") {
        val (ranked, status) = diagnose(kettle(field = 0.05, live = 0.20), reading(field = 0.19, live = 0.83))
        val ids = ranked.map { it.fault.id }
        when {
            "high_load" !in ids -> "raised $ids"
            "unexpectedly_live" in ids -> "called a kettle a back-feed: $ids"
            status != Status.WARNING -> "status $status"
            else -> null
        }
    }

    fun calibratedSmallRiseIsNotAHigherLoad() =
        check("a rise inside the calibration scatter is not a higher load") {
            // 0.20 to 0.23 uT is 5.3 A to 6.1 A, inside the kettle's own +/-1 A scatter.
            val (ranked, _) = diagnose(kettle(field = 0.20, live = 0.83), reading(field = 0.23, live = 0.83))
            if (ranked.any { it.fault.id == "high_load" }) "raised ${ranked.map { it.fault.id }}" else null
        }

    fun calibratedIdleIsNotAHigherLoad() = check("a calibrated idle reading is not a higher load") {
        val (ranked, _) = diagnose(kettle(field = 0.05, live = 0.20), reading(field = 0.08, live = 0.30))
        if (ranked.isNotEmpty()) "raised ${ranked.map { it.fault.id }}" else null
    }

    fun motionLevelsAreOrdered() = check("motion: resting is still, turning is moved") {
        when {
            MotionCheck.level(0.005) != MotionCheck.Level.STILL -> "0.005 rad/s not still"
            MotionCheck.level(0.5) != MotionCheck.Level.MOVED -> "0.5 rad/s not moved"
            MotionCheck.STILL_BELOW >= MotionCheck.MOVED_ABOVE -> "thresholds out of order"
            else -> null
        }
    }

    fun motionRmsCombinesAxes() = check("motion RMS combines all three gyroscope axes") {
        // 0.3 rad/s about one axis is the same turn as 0.3 split across all three.
        val one = MotionCheck.rmsRadPerS(DoubleArray(50) { 0.3 }, DoubleArray(50), DoubleArray(50))
        val c = 0.3 / kotlin.math.sqrt(3.0)
        val three = MotionCheck.rmsRadPerS(DoubleArray(50) { c }, DoubleArray(50) { c }, DoubleArray(50) { c })
        when {
            kotlin.math.abs(one - 0.3) > 1e-12 -> "single axis gave $one"
            kotlin.math.abs(three - 0.3) > 1e-12 -> "three axes gave $three"
            MotionCheck.rmsRadPerS(DoubleArray(0), DoubleArray(0), DoubleArray(0)) != 0.0 -> "empty not 0"
            else -> null
        }
    }

    fun steadyHandIsNotMoved() = check("a steady hand is not reported as moved") {
        // Tremor of a hand pressed on a cable: small, fast, and centred on zero.
        val n = 150
        val x = DoubleArray(n) { 0.06 * kotlin.math.sin(it * 0.9) }
        val y = DoubleArray(n) { 0.04 * kotlin.math.cos(it * 1.3) }
        val level = MotionCheck.level(MotionCheck.rmsRadPerS(x, y, DoubleArray(n)))
        if (level == MotionCheck.Level.MOVED) "tremor reported as moved" else null
    }

    // ---- fusion ----

    private fun fuse(
        r: Reading, ai: Float? = 0.02f, motion: Double? = 0.01, circuit: Circuit? = null,
        history: List<Reading> = emptyList(),
    ): Fusion.Analysis {
        val c = circuit ?: Circuit("c1", "Sockets", breakerRatingA = 16.0, baseline = baseline())
        val m = Metrics.derive(r, c)!!
        val t = Thresholds.DEFAULT
        return Fusion.analyse(
            Fusion.Input(
                metrics = m, thresholds = t, faults = RulesEngine.rank(m, t),
                aiArcProbability = ai, aiThreshold = 0.5f, motionRadPerS = motion,
                audioCaptured = true, unprocessedAudio = true, breakerRatingA = c.breakerRatingA,
                history = history.mapNotNull { Metrics.derive(it, c) },
            ),
        )
    }

    private fun expect(a: Fusion.Analysis, want: Fusion.Outcome): String? =
        if (a.outcome != want) "got ${a.outcome}, want $want (why: ${a.why})" else null

    fun fusionHealthyIsNoAnomaly() = check("fusion: a healthy reading is 'no anomaly observed'") {
        val a = fuse(reading())
        expect(a, Fusion.Outcome.NO_ANOMALY)
            ?: if (a.quality != Fusion.Quality.GOOD) "quality ${a.quality}" else null
    }

    fun fusionArcWithAiAgreementIsArcing() = check("fusion: rule and AI agreeing on an arc is possible arcing") {
        val a = fuse(reading(arc = 0.5), ai = 0.95f)
        expect(a, Fusion.Outcome.POSSIBLE_ARCING)
            ?: if (a.strength == Fusion.Strength.LOW) "two agreeing signals rated LOW" else null
    }

    fun fusionArcPlusHighCurrentIsElectricalAnomaly() =
        check("fusion: arc pattern plus abnormal current is a possible electrical anomaly") {
            val a = fuse(reading(field = 20.0, arc = 0.5), ai = 0.9f)
            expect(a, Fusion.Outcome.ELECTRICAL_ANOMALY)
                ?: if (a.strength != Fusion.Strength.HIGH) "three agreeing signals rated ${a.strength}" else null
        }

    fun fusionAudioOnlyIsAcousticAnomaly() =
        check("fusion: normal current + AI-only arc sound is an acoustic anomaly, fault not confirmed") {
            val a = fuse(reading(), ai = 0.9f)
            expect(a, Fusion.Outcome.ACOUSTIC_ONLY)
                ?: if (a.conflicts.isEmpty()) "rule disagreeing with the AI not reported as a conflict" else null
        }

    fun fusionCurrentOnlyReportsNoArcPattern() =
        check("fusion: abnormal current + normal sound says no arc pattern detected") {
            expect(fuse(reading(field = 20.0)), Fusion.Outcome.CURRENT_ABNORMAL)
        }

    fun fusionMovedIsUnreliableButKeepsCriticalGuidance() =
        check("fusion: a moved phone is unreliable, and a critical rule warning is not dropped") {
            val a = fuse(reading(arc = 0.5), ai = 0.9f, motion = 0.6)
            expect(a, Fusion.Outcome.UNRELIABLE)
                ?: when {
                    a.strength != Fusion.Strength.LOW -> "unreliable rated ${a.strength}"
                    a.conflicts.none { "Arcing" in it } -> "arcing warning not mentioned: ${a.conflicts}"
                    a.whatToDo.none { "loose connection" in it.text } -> "arcing guidance dropped"
                    else -> null
                }
        }

    fun fusionCurrentOnIsolatedSurvivesMovement() =
        check("fusion: current on a switched-off circuit is never downgraded") {
            expect(fuse(reading(isolated = true), motion = 0.6), Fusion.Outcome.CURRENT_ON_ISOLATED)
        }

    fun fusionArcSoundWithoutCurrentIsNotFromCable() =
        check("fusion: arc-like sound with no current is not attributed to the cable") {
            val c = Circuit("c1", "Spare", baseline = baseline(live = 0.04))
            expect(fuse(reading(live = 0.04), ai = 0.99f, circuit = c), Fusion.Outcome.SOUND_NOT_FROM_CABLE)
        }

    fun fusionNeverClaimsSafety() = check("fusion: no result claims a wire is safe or proven") {
        val banned = listOf("is safe", "is unsafe", "proven", "guaranteed", "no fault")
        Fusion.Outcome.values().firstNotNullOfOrNull { o ->
            val text = (o.title + " " + o.summary).lowercase()
            banned.firstOrNull { it in text }?.let { "${o.name} says \"$it\"" }
        }
    }

    // ---- cable scan ----

    private fun pt(
        i: Int, strength: Double, quality: Fusion.Quality = Fusion.Quality.GOOD, audio: Boolean = true,
    ) = CableScan.Point(
        index = i, epochMillis = i * 1000L, lineConfidence = 0.95, loadZ = 0.0, arcZ = strength * 8,
        aiProbability = strength.toFloat(), outcome = Fusion.Outcome.NO_ANOMALY, quality = quality,
        motionRadPerS = 0.01, audioCaptured = audio, strength = strength,
    )

    private fun scan(vararg s: Double) = s.mapIndexed { i, v -> pt(i, v) }

    fun scanFindsAConsistentZone() = check("scan: adjacent elevated points form the strongest zone") {
        val r = CableScan.analyse(scan(0.1, 0.1, 0.15, 0.5, 0.8, 0.6, 0.15, 0.1, 0.1))
        when {
            r.verdict != CableScan.Verdict.ZONE_FOUND -> "verdict ${r.verdict}"
            r.peak != 5 -> "peak at point ${r.peak}, want 5"
            r.zoneFirst != 4 || r.zoneLast != 6 -> "zone ${r.zoneFirst}-${r.zoneLast}, want 4-6"
            else -> null
        }
    }

    fun scanIgnoresASingleSpike() = check("scan: one noisy point is never marked as a zone") {
        val r = CableScan.analyse(scan(0.1, 0.1, 0.1, 0.95, 0.1, 0.1, 0.1))
        val edge = CableScan.analyse(scan(0.95, 0.1, 0.1, 0.1, 0.1))
        when {
            r.verdict != CableScan.Verdict.ISOLATED_SPIKE -> "middle spike gave ${r.verdict}"
            r.points.any { it.state == CableScan.State.STRONGEST } -> "a point was marked strongest"
            edge.verdict == CableScan.Verdict.ZONE_FOUND -> "edge spike became a zone"
            else -> null
        }
    }

    fun scanIgnoresDiscardedPoints() = check("scan: points that fail quality checks take no part") {
        val pts = scan(0.1, 0.1, 0.1, 0.1, 0.1, 0.1).toMutableList()
        pts[2] = pt(2, 0.99, quality = Fusion.Quality.POOR)
        pts[3] = pt(3, 0.99, audio = false)
        val r = CableScan.analyse(pts)
        when {
            r.discarded != 2 -> "discarded ${r.discarded}, want 2"
            r.verdict == CableScan.Verdict.ZONE_FOUND -> "discarded points formed a zone"
            r.points[2].state != CableScan.State.DISCARDED -> "moved point not marked discarded"
            else -> null
        }
    }

    fun scanReportsUniformSignal() = check("scan: an evenly high signal is not called a zone") {
        val r = CableScan.analyse(scan(0.6, 0.62, 0.61, 0.6, 0.63, 0.6))
        if (r.verdict != CableScan.Verdict.UNIFORM) "verdict ${r.verdict}" else null
    }

    fun scanNeedsEnoughPoints() = check("scan: too few usable points gives no zone") {
        val r = CableScan.analyse(scan(0.1, 0.9, 0.9))
        if (r.verdict != CableScan.Verdict.TOO_FEW_POINTS || r.peak != null) "verdict ${r.verdict}" else null
    }

    fun scanDownweightsFairPoints() = check("scan: fair-quality points count less than good ones") {
        val good = CableScan.analyse(scan(0.1, 0.1, 0.4, 0.4, 0.1, 0.1))
        val fairPts = scan(0.1, 0.1, 0.4, 0.4, 0.1, 0.1).map {
            if (it.index in 2..3) it.copy(quality = Fusion.Quality.FAIR) else it
        }
        val fair = CableScan.analyse(fairPts)
        when {
            good.verdict != CableScan.Verdict.ZONE_FOUND -> "good-quality bump gave ${good.verdict}"
            fair.verdict == CableScan.Verdict.ZONE_FOUND -> "the same bump at fair quality still made a zone"
            else -> null
        }
    }

    fun scanStrengthIsBounded() = check("scan: point strength is 0 at normal and 1 at critical") {
        val t = Thresholds.DEFAULT
        when {
            !near(CableScan.strengthOf(0.0, 0f, t), 0.0) -> "normal gave ${CableScan.strengthOf(0.0, 0f, t)}"
            !near(CableScan.strengthOf(t.criticalZ * 3, 1f, t), 1.0) -> "critical not capped at 1"
            !near(CableScan.strengthOf(-5.0, null, t), 0.0) -> "below-normal sound gave a positive strength"
            else -> null
        }
    }

    fun scanStoreRoundTrips() = check("scan store round-trips a session") {
        val store = ScanStore(MemoryFileSystem())
        val pts = scan(0.1, 0.5).toMutableList().also { it[1] = it[1].copy(aiProbability = null, motionRadPerS = null) }
        val s = ScanStore.Session("c1", "Kitchen\tcircuit", 42L, pts)
        store.save("b1", s)
        val back = store.list("b1", "c1").singleOrNull()
        when {
            back == null -> "nothing loaded"
            back.points != pts -> "points differ: ${back.points}"
            back.epochMillis != 42L -> "time ${back.epochMillis}"
            else -> null
        }
    }

    // ---- assistant prompt ----

    fun assistantPromptCarriesEvidenceAndRules() =
        check("assistant: the prompt carries the result, its evidence and the safety rules") {
            val a = fuse(reading(arc = 0.5), ai = 0.95f)
            val p = AssistantPrompt.build(a, "Kitchen", 16.0, "What should I do next?")
            when {
                a.outcome.title !in p -> "result title missing"
                "Arc signal (rule)" !in p -> "evidence missing"
                "Never say a wire or circuit is safe or unsafe" !in p -> "safety rule missing"
                "Question: What should I do next?" !in p -> "question missing"
                !p.startsWith("<|im_start|>system") || !p.endsWith("<|im_start|>assistant\n") -> "chat format wrong"
                "Kitchen, breaker 16 A" !in p -> "circuit missing"
                else -> null
            }
        }

    fun assistantPromptStaysInsideTheWindow() = check("assistant: the facts stay within their budget") {
        val a = fuse(reading(field = 20.0, arc = 0.5), ai = 0.9f, motion = 0.6)
        val long = a.copy(why = List(40) { "a very long reason ".repeat(20) })
        val facts = AssistantPrompt.facts(long, "x".repeat(500), 16.0)
        if (facts.length > AssistantPrompt.MAX_FACTS_CHARS) "facts ${facts.length} chars" else null
    }

    fun assistantFlagsCertaintyAboutSafety() = check("assistant: certain safety claims are flagged, hedged ones are not") {
        val bad = listOf("This wire is safe to touch.", "It's completely safe.", "There is no danger here.",
            "This is definitely a loose connection.")
        val fine = listOf("Taar cannot say whether it is safe.", "The phone can not prove the wire is safe.",
            "Possible arcing was observed. Have the connections checked.")
        bad.firstOrNull { AssistantPrompt.concerns(it).isEmpty() }?.let { "missed: $it" }
            ?: fine.firstOrNull { AssistantPrompt.concerns(it).isNotEmpty() }?.let { "false alarm: $it" }
    }

    // ---- product knowledge ----

    fun knowledgeFindsTheRightNote() = check("knowledge: each question finds its note first") {
        val cases = mapOf(
            "How do I record a reference?" to "Recording a reference",
            "What does the phone check do?" to "Phone check",
            "Is my data uploaded to the internet?" to "Privacy and offline",
            "How does it detect sparking?" to "How sparking is detected",
            "What is cable scan?" to "Cable Scan",
            "Can it tell me if the wire is safe to touch?" to "Limits and safety",
            "How do I see amps?" to "Amps calibration",
            "What does red mean?" to "Colours",
            "Why did my reading say unreliable?" to "Measurement quality",
        )
        cases.entries.firstNotNullOfOrNull { (q, want) ->
            val got = ProductKnowledge.lookup(q).firstOrNull()?.title
            if (got != want) "\"$q\" found \"$got\", want \"$want\"" else null
        }
    }

    fun knowledgeFallsBackToTheOverview() = check("knowledge: an unrelated question gets the overview only") {
        val got = ProductKnowledge.lookup("xyzzy plugh").map { it.title }
        if (got != listOf("What Taar is")) "got $got" else null
    }

    fun knowledgeNotesMakeNoSafetyClaims() = check("knowledge: no note sounds certain about safety") {
        ProductKnowledge.notes.firstOrNull { AssistantPrompt.concerns(it.text).isNotEmpty() }?.let { "\"${it.title}\"" }
    }

    fun generalPromptStaysInsideTheWindow() = check("knowledge: the general prompt keeps its notes within budget") {
        val p = AssistantPrompt.general("tell me everything", ProductKnowledge.notes)
        val notes = p.substringAfter("Notes about Taar:\n").substringBefore("\n\nQuestion:")
        when {
            notes.length > AssistantPrompt.MAX_NOTES_CHARS -> "notes ${notes.length} chars"
            "Question: tell me everything" !in p -> "question missing"
            "Never say a wire or circuit is safe or unsafe" !in p -> "safety rule missing"
            else -> null
        }
    }
}
