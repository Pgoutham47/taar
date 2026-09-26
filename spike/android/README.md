# Taar — Android app (reference spike)

**Status: reference spike. Not submission code.** See `../../HACKATHON.md` §1 for
the orphan-branch protocol. This is retyped at the event, not merged.

---

## What is verified

A Kotlin toolchain and the Android SDK were installed for this work, so the claims
here are checked rather than hoped for.

| Layer | Compiles | Tested |
|---|---|---|
| `dsp/` | ✅ | ✅ 13 checks against golden vectors from the Python spike |
| `domain/` | ✅ | ✅ 42 checks on rules, thresholds, calibration, stats, store and classifier |
| `sensor/` | ✅ | ❌ needs hardware — first run is on the loaner |
| `ui/` | ✅ | ❌ no instrumentation tests |
| `data/` | ✅ | ❌ one file over `Context` |

```bash
./gradlew clean assembleDebug testDebugUnitTest    # BUILD SUCCESSFUL, 9.1 MB APK
./tools/verify.sh                                   # 55/55, no Android SDK needed
```

The whole app compiles and packages. **55 of 55 logic checks pass.** What remains
unverified is behaviour that only a phone can show: whether the magnetometer
delivers a usable rate, what the real noise floor is, whether the field deflects at
3 cm, and whether `UNPROCESSED` audio survives the device's chain. Those are the
pre-flight items in `../../docs/PREFLIGHT.md`.

Verified here on Gradle 9.7.1 / JDK 27. The wrapper is pinned to 9.7.1 because
Gradle 8.x refuses JDK 27; Android Studio's bundled JDK will also work with it.

### The tests are not vacuous

Perturbing one golden value by 0.001 µT fails the build:

```
GoldenTest > all golden checks pass FAILED
```

Restoring it passes again. Worth re-running that experiment if the suite ever goes
quiet.

## What the tests already caught

**The locked-rate set was wrong in the documentation.** The Python spike tested a
hand-picked list of sample rates and never scanned 10 Hz or 20 Hz, so the README
claimed the locked rates below 150 Hz were 25, 50 and 100. A Kotlin check that
scanned the whole range found **10, 20, 25, 50 and 100**. `harmonic_lock.py` now
scans exhaustively, and every document has been corrected.

That is a golden test catching an error in the reference rather than in the port,
which was not the direction it was designed to work in.

---

## Layout

```
app/src/main/java/com/taar/
  dsp/          pure Kotlin, no Android — compiled and tested here
    LineFrequency.kt   harmonic-lock guard
    SineFit.kt         least-squares amplitude at a known frequency
    LombScargle.kt     normalised power, matching scipy's definition
    Biquad.kt          SOS cascade, coefficients emitted from the spike
    Fft.kt             radix-2, used only on the decimated envelope
    ArcDetector.kt     100 Hz modulation index
    Spectrogram.kt     envelope spectrogram for the result screen
  domain/       pure Kotlin, no Android — compiled and tested here
    Stats.kt           median, MAD, robust z
    Model.kt           Installation, Circuit, Reading, Baseline
    Thresholds.kt      per-circuit calibration from labelled readings
    Metrics.kt         reading + baseline -> evidence
    FaultCatalogue.kt  6 faults, their evidence rules and actions
    Classifier.kt      nearest-centroid, trained on the technician's own labels
    Store.kt           versioned line format, FileSystem interface
  sensor/       MagCapture, AudioCapture, CaptureCoordinator
  ui/           MainActivity, PreCheck, Measure, Result, Boards, Circuits,
                History, SpectrogramView
  data/         AndroidFileSystem

app/src/test/java/com/taar/
  dsp/GoldenChecks.kt     assertions, framework-free
  dsp/GoldenTest.kt       JUnit wrapper
  domain/DomainChecks.kt  assertions, framework-free
tools/
  Verify.kt, verify.sh    the same checks, from the command line
golden/                   CSV fixtures from the Python reference
```

`GoldenChecks` and `DomainChecks` hold the assertions; JUnit and the CLI both call
them. Two copies would eventually disagree, and the copy that disagreed silently
would be the one that mattered.

## Decisions worth knowing about

**The rate guard is not optional.** Every requested rate goes through
`LineFrequency.safeRate()`. Android's magnetometer default is 100 Hz, one of five
rates below 150 Hz that cannot see a 50 Hz signal.

**Per-sample timestamps are kept.** Assuming a fixed interval discards the
irregularity that makes 50 Hz recoverable below the uniform Nyquist limit.

**Field magnitude, not a single axis.** Orientation relative to the conductor is
not controlled.

**Rectify + low-pass, not Hilbert.** Checked against the Hilbert version first —
equivalent separation, one filter pass instead of an FFT over the full clip.

**Filter coefficients are emitted, never retyped.** `golden/filter_impulse.csv`
proves the transcription to 1e-9.

**Median and MAD everywhere, not mean and standard deviation.** Readings are
labelled by a technician on a live site; one capture taken while a lift motor
started should not move a threshold.

**A rules table, not a classifier.** For a tool that tells someone to open a live
panel, being able to say *why* matters more than being flexible. Every fault ends
in an action, because a fault name alone is no use to the person holding the phone.

**Partial evidence is not a weak diagnosis.** `RulesEngine` only returns faults
whose evidence fully holds. Offering maybes trains the technician to discount the
tool.

**Amperes only when calibrated.** `CurrentBasis.UNCALIBRATED` makes the result
screen print a relative index. A fabricated ampere figure is worse than none.

**Refuse rather than guess.** No baseline, or an ill-conditioned fit, produces a
refusal — not a plausible number. An electrician acting on a confident wrong
reading is the worst outcome this project has.

**Writes are atomic and append-only.** A capture is the one thing that cannot be
retaken.

**No INTERNET permission in the manifest.** There is no server and nothing to
upload, and its absence is a claim a judge can check in thirty seconds.

**A nearest-centroid classifier, not a trained head.** Adding a class must cost
nothing: a technician labels a reading on Tuesday and it is recognised on
Wednesday. A trained model's output layer is fixed to the classes it was trained
on. It also rejects — an unfamiliar or ambiguous reading returns nothing rather
than the nearest of several wrong answers — and it sits *behind* the rules, never
in front of them, because a learned label with no explanation is not something to
act on at a live panel.

## Not built

- Instrumentation tests (needs a device or emulator)
- Calibration flow for `utPerAmp` — the UI shows the state but there is no
  guided "measure a known load" step yet
- Phase imbalance, which needs three conductors read together
- Export or sharing of a report

Build order is `HACKATHON.md` §5. The must-have milestone at step 5 is met, and
steps 9-11 are done.
