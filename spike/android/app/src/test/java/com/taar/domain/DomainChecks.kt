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
        geigerLevelIsBoundedAndRises(),
        geigerIsSilentWhenQuiet(),
        geigerQuietHereStaysWithinLimits(),
        geigerSmootherRisesFasterThanItFalls(),
        geigerClicksAtTheRequestedRate(),
        geigerHearsACableInAShortWindow(),
        boardMapRoundTrips(),
        boardMapSkipsABadRow(),
        boardMapShowsTheMeasuredResult(),
        boardMapPrefersANewerReading(),
        boardMapUnmeasuredAndSummary(),
        boardMapNumbersInTapOrder(),
        spokenResultNeverCallsAWireSafe(),
        spokenResultLeadsWithWarnings(),
        spokenResultSaysAmpsOnlyWhenFlowing(),
        energyCostOfTheKettle(),
        energyCostRefusesWithoutAmps(),
        energyCostRoundsToWhatTheReadingCanSupport(),
        spokenResultSaysTheCostInWords(),
        voiceUnderstandsTheEverydayCommands(),
        voiceFindsCircuitsByName(),
        voiceReadsHoursAndNumbers(),
        voiceLeavesTheUnknownToTheModel(),
        voiceAnswersWhichWiresHaveAProblem(),
        voiceModelCanOnlyChooseFromTheList(),
        voiceHandlesWhatTheRecogniserActuallyHeard(),
        voiceVocabularyCoversTheBoard(),
        voiceUnderstandsTheNewCommands(),
        voiceNeverActsOnARepliesWord(),
        voiceIgnoresChatterButNotCommands(),
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

    fun geigerLevelIsBoundedAndRises() = check("geiger: level is 0 at quiet, 1 at full, and rises between") {
        val s = Geiger.Scale()
        val levels = listOf(1.0, s.quiet, 8.0, 12.0, 20.0, s.full, 500.0).map { s.level(it) }
        when {
            levels[0] != 0.0 || levels[1] != 0.0 -> "below or at quiet gave ${levels.take(2)}"
            !near(levels[5], 1.0) || levels[6] != 1.0 -> "full gave ${levels[5]}, far above gave ${levels[6]}"
            levels.zipWithNext().any { (a, b) -> b < a } -> "not monotonic: $levels"
            else -> null
        }
    }

    fun geigerIsSilentWhenQuiet() = check("geiger: no clicks when quiet, fastest at full strength") {
        when {
            Geiger.clicksPerSecond(0.0) != 0.0 -> "quiet clicks at ${Geiger.clicksPerSecond(0.0)}/s"
            !near(Geiger.clicksPerSecond(1.0), Geiger.MAX_CLICKS_PER_S) -> "full gave ${Geiger.clicksPerSecond(1.0)}/s"
            Geiger.clicksPerSecond(0.01) < Geiger.MIN_CLICKS_PER_S -> "just above quiet is too slow to hear"
            Geiger.Zone.of(0.0) != Geiger.Zone.QUIET -> "level 0 is not quiet"
            Geiger.Zone.of(1.0) != Geiger.Zone.CLOSE -> "level 1 is not close"
            else -> null
        }
    }

    fun geigerQuietHereStaysWithinLimits() = check("geiger: 'set quiet here' lifts the floor, within limits") {
        val low = Geiger.Scale().quietAt(1.0)
        val mid = Geiger.Scale().quietAt(8.0)
        val high = Geiger.Scale().quietAt(1000.0)
        when {
            low.quiet != Geiger.DEFAULT_QUIET -> "a quiet room lowered the floor to ${low.quiet}"
            !near(mid.quiet, 12.0) -> "background 8x gave floor ${mid.quiet}, expected 12"
            mid.level(8.0) != 0.0 -> "the background itself still clicks"
            high.quiet != Geiger.MAX_QUIET -> "floor not capped: ${high.quiet}"
            high.full < high.quiet * Geiger.MIN_SPAN -> "scale collapsed: ${high.quiet}..${high.full}"
            else -> null
        }
    }

    fun geigerSmootherRisesFasterThanItFalls() = check("geiger: smoothing rises fast and falls slowly") {
        val up = Geiger.Smoother().apply { update(2.0) }.update(40.0)
        val down = Geiger.Smoother().apply { update(40.0) }.update(2.0)
        // Equal steps in log terms: rising should cover more of the way than falling.
        val rose = kotlin.math.ln(up / 2.0) / kotlin.math.ln(20.0)
        val fell = kotlin.math.ln(40.0 / down) / kotlin.math.ln(20.0)
        if (rose > fell) null else "rose $rose of the way, fell $fell"
    }

    fun geigerClicksAtTheRequestedRate() = check("geiger: random clicks average the requested rate") {
        val rnd = java.util.Random(3)
        val tick = 0.010
        val seconds = 200.0
        var n = 0
        repeat((seconds / tick).toInt()) { if (Geiger.clickInTick(10.0, tick, rnd.nextDouble())) n++ }
        val rate = n / seconds
        if (abs(rate - 10.0) < 0.6) null else "asked 10/s, got $rate/s"
    }

    /**
     * A simulated 1.2 s window at the iQOO's measured rate and noise floor: a weak
     * 50 Hz field must click, and noise alone must not.
     */
    fun geigerHearsACableInAShortWindow() = check("geiger: a weak 50 Hz field clicks in 1.2 s, noise alone does not") {
        fun window(seed: Long, amplitudeUt: Double): Double {
            val rnd = java.util.Random(seed)
            val t = ArrayList<Double>()
            var now = 0.0
            while (now < Geiger.WINDOW_SECONDS) { t += now; now += (1 / 105.3) * (1 + 0.05 * rnd.nextGaussian()) }
            val ts = t.toDoubleArray()
            // Earth's static field, a slow hand drift, sensor noise at the measured 0.4 uT.
            fun axis(static: Double, signal: Double) = DoubleArray(ts.size) {
                static + 1.5 * kotlin.math.sin(2 * Math.PI * 0.7 * ts[it]) + 0.4 * rnd.nextGaussian() +
                    signal * kotlin.math.sin(2 * Math.PI * 50.0 * ts[it] + 0.3)
            }
            return Geiger.contrast(ts, axis(20.0, amplitudeUt), axis(-12.0, 0.0), axis(38.0, 0.0))
        }
        val noise = (1L..40L).map { window(it, 0.0) }.sorted()
        val cable = (1L..40L).map { window(100 + it, 0.35) }.sorted()
        val s = Geiger.Scale()
        val noiseClicks = noise.count { s.level(it) > 0 }
        when {
            noise[20] >= Geiger.DEFAULT_QUIET -> "median noise ${noise[20]} is above quiet"
            noiseClicks > 4 -> "noise alone clicked in $noiseClicks of 40 windows (median ${noise[20]})"
            cable[4] <= Geiger.DEFAULT_QUIET * 2 -> "0.35 uT field too weak: 10th percentile ${cable[4]}"
            else -> null
        }
    }

    fun boardMapRoundTrips() = check("board map: pins and results survive a save and load") {
        val store = BoardMapStore(MemoryFileSystem())
        store.placePin("b1", "c1", BoardMap.Pin(0.25, 0.5))
        store.placePin("b1", "c2", BoardMap.Pin(0.75, 0.1))
        store.placePin("b1", "c1", BoardMap.Pin(0.3, 0.6))
        store.removePin("b1", "c2")
        store.recordResult("b1", "c1", BoardMap.LastResult(42L, Fusion.Outcome.POSSIBLE_ARCING, LineState.FLOWING))
        val m = store.load("b1")
        when {
            m.pins != mapOf("c1" to BoardMap.Pin(0.3, 0.6)) -> "pins read ${m.pins}"
            m.results["c1"]?.outcome != Fusion.Outcome.POSSIBLE_ARCING -> "result read ${m.results}"
            store.load("other").pins.isNotEmpty() -> "another board shares pins"
            else -> null
        }
    }

    fun boardMapSkipsABadRow() = check("board map: one unreadable row does not lose the rest") {
        val fs = MemoryFileSystem()
        fs.write("boardmap/b1.tsv", "taar-boardmap/1\npin\tc1\t0.5\t0.5\npin\tc2\tnot-a-number\t1\n" +
            "result\tc1\t5\tNO_SUCH_OUTCOME\tFLOWING\n")
        val m = BoardMapStore(fs).load("b1")
        when {
            m.pins.keys != setOf("c1") -> "pins ${m.pins.keys}"
            m.results.isNotEmpty() -> "bad result kept"
            else -> null
        }
    }

    fun boardMapShowsTheMeasuredResult() = check("board map: a dot shows the result the measurement reached") {
        fun dot(o: Fusion.Outcome, line: LineState) = BoardMap.dotOf(BoardMap.LastResult(10L, o, line), null).mark
        when {
            dot(Fusion.Outcome.POSSIBLE_ARCING, LineState.FLOWING) != BoardMap.Mark.PROBLEM -> "arcing not a problem"
            dot(Fusion.Outcome.CURRENT_ABNORMAL, LineState.FLOWING) != BoardMap.Mark.CHECK -> "abnormal not check"
            dot(Fusion.Outcome.UNRELIABLE, LineState.FLOWING) != BoardMap.Mark.UNCLEAR -> "unreliable not unclear"
            dot(Fusion.Outcome.NO_ANOMALY, LineState.FLOWING) != BoardMap.Mark.LIVE -> "normal live not live"
            dot(Fusion.Outcome.NO_ANOMALY, LineState.NONE) != BoardMap.Mark.OFF -> "normal idle not off"
            dot(Fusion.Outcome.NO_CURRENT_ISOLATED, LineState.NONE) != BoardMap.Mark.OFF -> "isolated not off"
            else -> null
        }
    }

    fun boardMapPrefersANewerReading() = check("board map: a newer reading replaces an older result") {
        val old = BoardMap.LastResult(10L, Fusion.Outcome.POSSIBLE_ARCING, LineState.FLOWING)
        val newer = Reading("c1", 20L, fieldAmplitudeUt = 0.1, lineConfidence = 0.1, arcModulationIndex = 0.0)
        val same = newer.copy(epochMillis = 10L)
        when {
            BoardMap.dotOf(old, newer).mark != BoardMap.Mark.OFF -> "newer idle reading ignored"
            BoardMap.dotOf(old, same).mark != BoardMap.Mark.PROBLEM -> "the result's own reading replaced it"
            else -> null
        }
    }

    fun boardMapUnmeasuredAndSummary() = check("board map: unmeasured circuits say so; summary is most urgent first") {
        val none = BoardMap.dotOf(null, null)
        val dots = listOf(
            none, BoardMap.Dot(BoardMap.Mark.LIVE, 1L, null), BoardMap.Dot(BoardMap.Mark.LIVE, 2L, null),
            BoardMap.Dot(BoardMap.Mark.PROBLEM, 3L, "x"),
        )
        val s = BoardMap.summary(dots)
        when {
            none.mark != BoardMap.Mark.NOT_MEASURED -> "unmeasured gave ${none.mark}"
            s != "1 problem · 2 current flowing · 1 not measured" -> "summary '$s'"
            else -> null
        }
    }

    fun boardMapNumbersInTapOrder() = check("board map: the first switch tapped is dot 1, whatever the board held") {
        val pin = BoardMap.Pin(0.5, 0.5)
        val board = listOf("c1", "c2", "c3", "new")
        val pins = linkedMapOf("new" to pin, "c2" to pin, "gone" to pin)
        val order = BoardMap.order(board, pins)
        val moved = BoardMap.order(board, pins + ("new" to BoardMap.Pin(0.1, 0.1)))
        when {
            order != listOf("new", "c2", "c1", "c3") -> "order $order"
            moved != order -> "moving a dot changed the numbers: $moved"
            else -> null
        }
    }

    fun spokenResultNeverCallsAWireSafe() = check("spoken result: no sentence ever calls a wire safe") {
        val all = Fusion.Outcome.entries.flatMap { o ->
            LineState.entries.map { l -> SpokenResult.of(o, l, "Kitchen", 3.0) }
        }
        all.firstOrNull { Regex("\\bsafe\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { "said: $it" }
    }

    fun spokenResultLeadsWithWarnings() = check("spoken result: critical results start with Warning, after the name") {
        val critical = Fusion.Outcome.entries.filter { it.tone == Fusion.Tone.CRITICAL }
        val bad = critical.map { SpokenResult.of(it, LineState.FLOWING, "Geyser", null) }
            .firstOrNull { !it.startsWith("Geyser. Warning.") }
        val isolated = SpokenResult.of(Fusion.Outcome.NO_CURRENT_ISOLATED, LineState.NONE, null, null)
        when {
            bad != null -> "said: $bad"
            "voltage tester" !in isolated -> "a switched-off circuit is not reminded about voltage"
            else -> null
        }
    }

    fun spokenResultSaysAmpsOnlyWhenFlowing() = check("spoken result: amps only when calibrated and flowing") {
        val flowing = SpokenResult.of(Fusion.Outcome.NO_ANOMALY, LineState.FLOWING, null, 5.24)
        val uncalibrated = SpokenResult.of(Fusion.Outcome.NO_ANOMALY, LineState.FLOWING, null, null)
        val idle = SpokenResult.of(Fusion.Outcome.NO_ANOMALY, LineState.NONE, null, 0.02)
        when {
            "About 5.2 amps." !in flowing -> "flowing said: $flowing"
            "amps" in uncalibrated -> "uncalibrated said amps: $uncalibrated"
            "amps" in idle -> "idle said amps: $idle"
            else -> null
        }
    }

    fun energyCostOfTheKettle() = check("cost: the 1200 W kettle, 1 h a day at ₹7.70, is about ₹280 a month") {
        // 5.2 A x 230 V = 1.196 kW; x 1 h x 30 days = 35.9 units; x 7.70 = ₹276.
        val e = EnergyCost.estimate(5.2, 1.0, EnergyCost.DEFAULT_RATE)
        when {
            e == null -> "no estimate"
            !near(e.kilowatts, 1.196, 1e-3) -> "kW ${e.kilowatts}"
            !near(e.unitsPerMonth, 35.88, 1e-2) -> "units ${e.unitsPerMonth}"
            EnergyCost.rupees(e.rupeesPerMonth) != "₹280" -> "shown as ${EnergyCost.rupees(e.rupeesPerMonth)}"
            else -> null
        }
    }

    fun energyCostRefusesWithoutAmps() = check("cost: nothing is costed without calibrated amps or with nonsense inputs") {
        when {
            EnergyCost.estimate(null, 4.0, 7.7) != null -> "uncalibrated got a cost"
            EnergyCost.estimate(0.0, 4.0, 7.7) != null -> "no current got a cost"
            EnergyCost.estimate(5.0, 25.0, 7.7) != null -> "25 hours a day accepted"
            EnergyCost.estimate(5.0, 4.0, 0.0) != null -> "a zero rate accepted"
            else -> null
        }
    }

    fun energyCostRoundsToWhatTheReadingCanSupport() = check("cost: two significant figures, Indian grouping") {
        val shown = listOf(7.4, 37.2, 276.3, 2127.0, 123456.0, 12345678.0).map { EnergyCost.rupees(it) }
        if (shown == listOf("₹7", "₹37", "₹280", "₹2,100", "₹1,20,000", "₹1,20,00,000")) null else "shown as $shown"
    }

    fun spokenResultSaysTheCostInWords() = check("spoken result: the monthly cost is said in rupees, only with amps") {
        val cost = EnergyCost.estimate(6.2, 8.0, EnergyCost.DEFAULT_RATE)
        val with = SpokenResult.of(Fusion.Outcome.NO_ANOMALY, LineState.FLOWING, "AC", 6.2, cost)
        val idle = SpokenResult.of(Fusion.Outcome.NO_ANOMALY, LineState.NONE, "AC", null, cost)
        when {
            // 6.2 A x 230 V = 1.426 kW; x 8 h x 30 days = 342 units; x 7.70 = ₹2,635.
            "about 2,600 rupees a month at 8 hours a day" !in with -> "said: $with"
            "₹" in with -> "the symbol would be read out oddly: $with"
            "rupees" in idle -> "idle wire given a cost: $idle"
            else -> null
        }
    }

    private val voiceCircuits = listOf(
        VoiceCommand.Name("k", "Kitchen"), VoiceCommand.Name("ac", "AC"), VoiceCommand.Name("g", "Geyser"),
        VoiceCommand.Name("lp", "Left black plug"), VoiceCommand.Name("rp", "Right black plug"),
        VoiceCommand.Name("c1", "Circuit 1"),
    )

    private fun heard(s: String) = VoiceCommand.parse(s, voiceCircuits)

    fun voiceUnderstandsTheEverydayCommands() = check("voice: everyday phrasing maps to the right action") {
        val cases = listOf<Pair<String, VoiceCommand.Command>>(
            "measure the kitchen wire" to VoiceCommand.Command.Measure("k"),
            "check the geyser" to VoiceCommand.Command.Measure("g"),
            "measure" to VoiceCommand.Command.Measure(null),
            "record normal for the a c" to VoiceCommand.Command.RecordNormal("ac"),
            "open board map" to VoiceCommand.Command.Open(VoiceCommand.Place.BOARD_MAP),
            "start geiger mode" to VoiceCommand.Command.Open(VoiceCommand.Place.GEIGER),
            "go home" to VoiceCommand.Command.Open(VoiceCommand.Place.HOME),
            "show history" to VoiceCommand.Command.Open(VoiceCommand.Place.HISTORY),
            "start the phone check" to VoiceCommand.Command.Open(VoiceCommand.Place.PHONE_CHECK),
            "calibrate amps" to VoiceCommand.Command.Open(VoiceCommand.Place.CALIBRATE),
            "which wires have a problem" to VoiceCommand.Command.Problems,
            "say that again" to VoiceCommand.Command.Repeat,
            "how much does the ac cost" to VoiceCommand.Command.Cost("ac"),
            "ask what does grey mean" to VoiceCommand.Command.Ask("what does grey mean"),
            "kitchen" to VoiceCommand.Command.Select("k"),
        )
        cases.firstOrNull { (said, want) -> heard(said) != want }?.let { (said, want) -> "'$said' gave ${heard(said)}, want $want" }
    }

    fun voiceFindsCircuitsByName() = check("voice: circuits found by most of their name; a tie is no guess") {
        when {
            heard("measure left plug") != VoiceCommand.Command.Measure("lp") -> "left plug gave ${heard("measure left plug")}"
            heard("measure the black plug") != VoiceCommand.Command.Measure(null) -> "left and right both match 'black plug', must not guess"
            heard("measure circuit one") != VoiceCommand.Command.Measure("c1") -> "circuit one gave ${heard("measure circuit one")}"
            heard("measure the bathroom") != VoiceCommand.Command.Measure(null) -> "an unknown name picked a circuit"
            else -> null
        }
    }

    fun voiceReadsHoursAndNumbers() = check("voice: hours for the cost, in words or digits, per circuit") {
        when {
            heard("the geyser runs two hours a day") != VoiceCommand.Command.SetHours(2.0, "g") -> "gave ${heard("the geyser runs two hours a day")}"
            heard("ac runs twenty four hours") != VoiceCommand.Command.SetHours(24.0, "ac") -> "gave ${heard("ac runs twenty four hours")}"
            heard("kitchen is on all day") != VoiceCommand.Command.SetHours(24.0, "k") -> "gave ${heard("kitchen is on all day")}"
            heard("8 hours") != VoiceCommand.Command.SetHours(8.0, null) -> "gave ${heard("8 hours")}"
            VoiceCommand.normalise("Twenty-four, A.C.!") != listOf("24", "ac") -> "normalise gave ${VoiceCommand.normalise("Twenty-four, A.C.!")}"
            else -> null
        }
    }

    fun voiceLeavesTheUnknownToTheModel() = check("voice: what the rules do not know is left for the model, not guessed") {
        val unknown = listOf("what does grey mean", "tell me a joke", "", "   ")
        unknown.firstOrNull { heard(it) != null }?.let { "'$it' gave ${heard(it)}" }
    }

    fun voiceAnswersWhichWiresHaveAProblem() = check("voice: 'which wires have a problem' names them, problems first") {
        fun dot(m: BoardMap.Mark) = BoardMap.Dot(m, 1L, null)
        val mixed = VoiceCommand.problemsAnswer(listOf(
            "Kitchen" to dot(BoardMap.Mark.LIVE), "Geyser" to dot(BoardMap.Mark.PROBLEM),
            "AC" to dot(BoardMap.Mark.CHECK), "Lights" to dot(BoardMap.Mark.NOT_MEASURED),
        ))
        val calm = VoiceCommand.problemsAnswer(listOf("Kitchen" to dot(BoardMap.Mark.LIVE)))
        when {
            mixed != "Geyser has a problem. AC needs a check. Lights is not measured yet." -> "said: $mixed"
            calm != "No problems in the latest readings." -> "said: $calm"
            Regex("\\bsafe\\b").containsMatchIn(mixed + calm) -> "called something safe"
            else -> null
        }
    }

    fun voiceModelCanOnlyChooseFromTheList() = check("voice: the model's reply is parsed by the same rules") {
        val p = VoiceCommand.modelPrompt("could you have a look at the geyser for me", listOf("Kitchen", "Geyser"))
        val reply = VoiceCommand.firstLine("- measure geyser\nThis will measure the geyser.")
        when {
            "Circuits: Kitchen, Geyser." !in p -> "circuits missing from the prompt"
            "could you have a look at the geyser for me" !in p -> "request missing from the prompt"
            reply != "measure geyser" -> "first line was '$reply'"
            heard(reply) != VoiceCommand.Command.Measure("g") -> "rephrasing parsed as ${heard(reply)}"
            VoiceCommand.parse(VoiceCommand.firstLine("delete everything"), voiceCircuits) != null -> "an unlisted action was accepted"
            else -> null
        }
    }

    /** Transcripts the limited recogniser produced from spoken test commands, as they came out. */
    fun voiceHandlesWhatTheRecogniserActuallyHeard() = check("voice: real recogniser output still finds the right action") {
        val cases = listOf<Pair<String, VoiceCommand.Command>>(
            "measure kitchen wire" to VoiceCommand.Command.Measure("k"),
            "how much does c cost" to VoiceCommand.Command.Cost("ac"),
            "how much does day c cost" to VoiceCommand.Command.Cost("ac"),
            "record normal for day c" to VoiceCommand.Command.RecordNormal("ac"),
            "the geyser runs two hours day" to VoiceCommand.Command.SetHours(2.0, "g"),
            "which wires has problem" to VoiceCommand.Command.Problems,
            "that again" to VoiceCommand.Command.Repeat,
            "check the geyser" to VoiceCommand.Command.Measure("g"),
            "home" to VoiceCommand.Command.Open(VoiceCommand.Place.HOME),
        )
        cases.firstOrNull { (said, want) -> heard(said) != want }?.let { (said, want) -> "'$said' gave ${heard(said)}, want $want" }
    }

    fun voiceVocabularyCoversTheBoard() = check("voice: the recogniser's words cover commands, numbers and this board") {
        val v = VoiceCommand.vocabulary(listOf("Kitchen", "AC", "Left black plug"))
        val missing = listOf("measure", "geyser", "kitchen", "a", "c", "left", "black", "plug", "twenty", "four", "hours")
            .filter { it !in v && it != "geyser" }
        when {
            missing.isNotEmpty() -> "missing $missing"
            heard("geyser runs to hours a day") != VoiceCommand.Command.SetHours(2.0, "g") -> "'to hours' not read as two"
            heard("geyser runs do hours") != VoiceCommand.Command.SetHours(2.0, "g") -> "'do hours' not read as two"
            "geiger" !in v -> "command word missing"
            else -> null
        }
    }

    fun voiceUnderstandsTheNewCommands() = check("voice: next, why, advice, list, toggles, rate, add, scans, end") {
        val C = listOf<Pair<String, VoiceCommand.Command>>(
            "next circuit" to VoiceCommand.Command.Next,
            "go to the next one" to VoiceCommand.Command.Next,
            "previous" to VoiceCommand.Command.Previous,
            "why" to VoiceCommand.Command.Why,
            "explain that" to VoiceCommand.Command.Why,
            "what should i do" to VoiceCommand.Command.WhatToDo,
            "list the circuits" to VoiceCommand.Command.ListCircuits,
            "which circuit is selected" to VoiceCommand.Command.WhichCircuit,
            "turn off geiger mode" to VoiceCommand.Command.Toggle(VoiceCommand.Mode.GEIGER, false),
            "enable board map" to VoiceCommand.Command.Toggle(VoiceCommand.Mode.BOARD_MAP, true),
            "the rate is nine rupees" to VoiceCommand.Command.SetRate(9.0),
            "tariff seven point seven" to VoiceCommand.Command.SetRate(7.7),
            "add a circuit called fridge" to VoiceCommand.Command.AddCircuit("Fridge"),
            "new circuit" to VoiceCommand.Command.Open(VoiceCommand.Place.CIRCUITS),
            "start a cable scan" to VoiceCommand.Command.StartScan(live = false),
            "open the cable scan" to VoiceCommand.Command.Open(VoiceCommand.Place.CABLE_SCAN),
            "is there current in the kitchen" to VoiceCommand.Command.Measure("k"),
            "take me to history" to VoiceCommand.Command.Open(VoiceCommand.Place.HISTORY),
            "i want to drill here" to VoiceCommand.Command.Open(VoiceCommand.Place.GEIGER),
            "read the result" to VoiceCommand.Command.Repeat,
            "stop listening" to VoiceCommand.Command.End,
            "thank you" to VoiceCommand.Command.End,
        )
        C.firstOrNull { (said, want) -> heard(said) != want }?.let { (said, want) -> "'$said' gave ${heard(said)}, want $want" }
    }

    fun voiceNeverActsOnARepliesWord() = check("voice: 'okay', 'fine', 'is it normal' never start or overwrite anything") {
        listOf("okay", "ok", "fine", "yes", "is it normal").firstOrNull { said ->
            when (heard(said)) {
                null, is VoiceCommand.Command.Ask -> false
                else -> true
            }
        }?.let { "'$it' gave ${heard(it)}" }
    }

    /** Real transcripts from the test recordings: (limited recogniser, free recogniser). */
    fun voiceIgnoresChatterButNotCommands() = check("voice: nearby chatter and noise ignored, real commands kept") {
        val v = VoiceCommand.vocabulary(listOf("Kitchen", "AC", "Geyser"))
        val chatter = listOf(
            "you c the map history day close" to "did you see the match yesterday it was really close",
            "stop main [unk]" to "i think the laptop needs charging before the demo",
            "[unk] runs to day" to "where are we going for lunch today",
            "okay next live show hour" to "okay so the next slide shows the architecture",
            "" to "boom",
            "" to "some",
            "the" to "some",
            "named" to "some",
            "a" to "hmm",
        )
        val commands = listOf(
            "measure kitchen wire" to "major the kitchen while",
            "record normal forty c" to "break record normal for the air sea",
            "how much does day c cost" to "how much does d air e cost",
            "the geyser runs do hour" to "the guys are runs to are very rare",
            "how much does the c cost" to "how much does the air he coughed",
            "go home" to "go home",
            "home" to "though home",
            "again" to "say that again",
        )
        val names = listOf(VoiceCommand.Name("k", "Kitchen"), VoiceCommand.Name("ac", "AC"), VoiceCommand.Name("g", "Geyser"))
        chatter.firstOrNull { (c, f) -> !VoiceCommand.looksLikeChatter(c, f, v, names) }?.let { "chatter kept: ${it.second}" }
            ?: commands.firstOrNull { (c, f) -> VoiceCommand.looksLikeChatter(c, f, v, names) }?.let { "command dropped: ${it.second}" }
    }
}
