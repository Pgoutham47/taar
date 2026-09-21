# HACKATHON.md — Taar build plan

iQOO City Battles 2026 × Reskilll — **Hyderabad, 26–27 Sept 2026**, Track 05 (Smart Living).

This file is the plan, the rules position and the checklist. Read section 1 before
writing any code, and section 2 before the clock starts.

---

## 1. The original-work rule — the single largest disqualification risk

The published rules say:

> - Original work only: **code written during the event window. No shipping a pre-built product.**
> - Open-source libraries and frameworks are fine with attribution; **carrying in a completed app is not.**
> - Organisers **may verify a project was built inside the event window.**
> - Repos are **locked before Top 10 pitches.**

Another team's research log quotes a stricter wording than the public Guide page —
including a `spikes/` folder convention and "organizers can check commit history."
That wording is not on `iqoo.reskilll.com/guide`, so **confirm which version binds
at check-in.** Ask an organiser directly; do not infer.

### Position

Anything written before the event is a **reference spike**, kept on a branch named
`spike/pre-event` that is never submitted and never merged.

### Protocol at the event

1. `git checkout --orphan main` → `git rm -rf .` → the first real commit is an
   empty Android project scaffold created live, on the day.
2. Rebuild in the order in section 5, **retyping each file rather than pasting it.**
   Retyping is not ceremony: it is the only version of this that survives someone
   asking you to explain a line.
3. Commit after each coherent chunk. Target **a commit every 20–40 minutes**,
   30–60 commits across the team over the event.
4. Never `git merge`, `git cherry-pick` or `git add` from the spike onto `main`.
   If the temptation is real, keep the spike in a **separate clone on a separate
   machine**, not a branch in the same working tree.
5. Attribute every open-source dependency in `NOTICE.md` as you add it, not at the end.

### What the spike is allowed to contain

Design notes, signal-processing experiments, throwaway probes that answer a
question. It exists to de-risk section 3, and nothing in it ships.

---

## 2. Pre-flight — the measurements that decide the architecture

The pre-event spike (`spike/dsp/`) already answered the question this section
originally asked, and corrected it. **The risk is not a slow magnetometer.** In
simulation, 24 Hz recovers the 50 Hz amplitude to 0.3%, while **100 Hz — the common
Android default — fails at 22%, and 50 Hz fails completely.**

What governs it is how many distinct phases of the waveform the sampling visits:

```
phases = fs / gcd(fs, 50)        fitting sin + cos needs >= 3
```

Locked rates below 150 Hz are **10, 20, 25, 50 and 100 Hz**. Everything else is fine,
including rates far below the supposed Nyquist limit.

### The fix, which is one line

`detect.safe_rate()` moves a locked rate to the nearest unlocked one — 100 → 99,
50 → 49. Call it at sensor registration. Device jitter also breaks the lock, so
real hardware is safer than the uniform case, but do not rely on jitter you have
not measured.

### Still to measure on the loaner, hour 0–1

```
1. Register TYPE_MAGNETIC_FIELD at SENSOR_DELAY_FASTEST, log 10 s of timestamps.
   Report: mean rate, inter-sample jitter (stddev), min/max.
2. distinct_phases(measured_rate) < 3  ->  re-register at safe_rate().
3. Phone still, away from any conductor: measure the real noise floor.
   The spike assumed 0.5 uT RMS. Substitute the measured value and re-run sweep.py.
4. Phone 3 cm from a cable under known load: confirm a deflection above background.
```

**Step 4 is the stop condition.** No deflection means the premise is wrong — switch
to the fallback idea at hour one, not hour twenty.

Write the raw numbers into `docs/PREFLIGHT.md`. That file is evidence for the
technical-depth score and costs twenty minutes.

### What the spike also settled

- **Sensitivity is not the constraint.** Minimum detectable current is ~0.05 A
  hand-held; a 5 A appliance produces 47 µT, comparable to the Earth's field.
  Effort belongs in specificity and calibration, not in chasing a noise floor.
- **Lomb–Scargle earns its place only once a hand is in the loop** — 5× better
  than a magnitude statistic at 100 Hz, 12× at 200 Hz, because magnitude cannot
  separate a 50 Hz field from ~10 Hz tremor. On a bench the two are within a
  factor of 2, so implement it for the hand-held case or not at all.
- **Arc detection is unmodelled.** The acoustic half is now the larger unknown.

## 3. Red Light / Green Light planning

~55% of build time is **Red Light** (phone only, via Office Kit), ~45% **Green**
(both devices). Roughly 10.5 h red and 8.5 h green across 19 h of pure build,
inside a 25 h window from Sat 11:00 to Sun 12:00. The exact block boundaries are
given at the Saturday 10:00 teach-in — **write them into this file then.**

Tag every task before starting:

| Tag | Meaning | Taar examples |
|---|---|---|
| 🟢 GREEN-ONLY | needs the laptop / Android Studio | Gradle setup, first APK, model export and quantisation, crash debugging, Lomb–Scargle prototyping in Python |
| 🟣 EITHER | phone through Office Kit with a keyboard | self-contained Kotlin files, fault-catalogue JSON, UI copy, result-screen layout, manual QA |
| 🔴 RED-OK | phone only | **all capture work** — baselines, load runs, arc captures, calibration, threshold tuning, running the app |

Taar is unusually well suited to this split: the capture and calibration work
*must* happen on the phone anyway, so Red Light blocks are productive rather than
survived. Front-load Gradle, model export and signal-processing prototyping into
Green.

**One person owns Office Kit from hour 1** and develops through it all event, so
the 10% telemetry accrues continuously rather than in a panic on Sunday.

---

## 4. HackTracker checklist — 25% auto-measured, cannot be talked up in the pitch

Creative phone use (15%) + Office Kit usage (10%).

- [ ] Ship a **real TFLite model in the first installed build**, and run it on every
      capture. A rules-only build does not register as on-device AI to telemetry —
      wire the classifier from the start even when its output is hidden for a
      healthy reading.
- [ ] Do **20+ real captures spread across the event**, not a burst at the end, so
      magnetometer, microphone and on-device inference all show sustained use.
- [ ] Use the **camera** for more than one thing: photograph each distribution
      board and each circuit label, one asset per photo, re-shot per session.
- [ ] Develop **and** demo through **Office Kit** on an external monitor from hour 1.
- [ ] Set `android:resizeableActivity="true"` and provide a landscape layout for the
      result screen — Office Kit on a desktop should not letterbox a portrait strip.
- [ ] Keep a terminal visible with `adb logcat -s TAAR:*` during the pitch, showing
      the sensor rate line, the estimator chosen, and the NPU backend.

---

## 5. Build order

Each step is committed before the next begins. The milestone marked **must-have**
is the point at which there is a demo; everything after it is upside.

1. 🟢 Gradle scaffold, manifest, dark theme, permissions
2. 🔴 `sensor/` — `MagCapture`, `AudioCapture`, `CaptureCoordinator` (shared clock)
3. 🟢 `ui/PreCheckScreen` — measured sample rates shown to the user, as Jugaad does
4. 🟢 `dsp/` — `LombScargle`, `FieldMagnitude`, `LogMel`, `BandEnergy`
5. 🔴 **`analysis/` — `Baseline`, `AnomalyScore`, `Thresholds`  ← must-have milestone**
6. 🟣 `data/` — JSON store, installation + circuit models, repositories
7. 🟣 `ui/` — InstallationList → Create → Baseline → Capture → Result
8. 🟣 `catalogue/faults.json` — fault types, evidence rules, actions
9. 🟢 `viz/` — spectrogram + field-trace rendering
10. 🟢 TFLite classifier head + on-device training (only if 1–9 are solid)
11. 🟢 Qwen3-1.7B advice wrapper (only if 10 is solid)

If the clock runs out, it runs out at 9 or 10, and the demo is unaffected.

---

## 6. Demo de-risking

- [ ] **Calibrate on the actual props**, not on defaults. Capture ~8 baseline clips
      with the strip idle and ~5 with the hair dryer running. Set thresholds from
      those readings via the in-app calibration flow.
- [ ] **Use a triac lamp dimmer, not a loose plug.** Deliberately arcing a mains
      connection under load is a fire and shock hazard and does not belong on a
      hackathon floor. A dimmer on an incandescent lamp chops the waveform once
      per half-cycle and produces the same 100 Hz-locked broadband signature —
      safe, repeatable, and adjustable by turning the knob. See
      `spike/audio/README.md`.
- [ ] **Time the full segment.** Baseline → load → arc is probably 60–90 s, not
      "instant". Measure it and narrate to the real number.
- [ ] **Pre-grant** microphone and camera permissions before presenting.
- [ ] **Charge and thermals**: 20 captures back to back will warm the phone. Check
      the sensor rate does not degrade when warm.
- [ ] **Safety optics.** Use a power strip and a domestic appliance. Do not open a
      real distribution board on a demo floor, and say why — it reinforces that the
      product is non-contact by design.

### Fallback tree — confirm each on the device, not in principle

| Component fails | Falls back to |
|---|---|
| Lomb–Scargle unusable at the measured rate | Field-magnitude index vs baseline |
| TFLite head fails to load | Rules engine over the fault catalogue |
| LLM advice unavailable | Templated advice strings from the catalogue |
| No baseline recorded | Hard block with an explicit message — never score without a reference |
| Office Kit drops | Run and present on the phone screen |
| Arc refuses to reproduce live | Pre-recorded capture, **labelled on screen as a recording** |

That last row matters. A recording shown honestly costs nothing; a recording
presented as live and spotted costs everything.

---

## 7. Pitch order — 3 to 5 minutes, demo on the phone

1. **Impact first.** Electrical faults cause building fires; the instruments that
   would catch them cost more than a small electrician's monthly revenue.
2. **The insight in one sentence.** Every live wire broadcasts its current as a
   magnetic field, and every arc broadcasts its fault as sound. The phone already
   has both instruments.
3. **Live:** baseline → switch on the load → introduce the fault → flagged, with
   the spectrogram.
4. **Offline proof:** airplane mode was on for all of it. State the manifest
   position honestly — if `INTERNET` is declared for any reason, say why rather
   than claiming it is absent.
5. **On-device AI:** show the backend line in logcat, and the measured sample rate
   the estimator was chosen from.
6. **Scope, said out loud:** a triage aid, not a certification; relative load index
   unless calibrated; leakage detection attempted and reported honestly.
7. **Roadmap:** multi-board estates, trend history over months, more fault classes.

Point 6 is not a weakness to bury. Judges have seen a day of projects that claim
more than they do; a team that draws its own boundary is the one they remember.

---

## 8. Team

Maximum **3** people, solo or up to three. Teams cannot mix buckets — all students
or all working professionals, judged separately. Decide before registering.

Suggested split:

| Role | Owns |
|---|---|
| **Signal** | capture, DSP, baseline, thresholds, the preflight measurement |
| **App** | Gradle, UI, data layer, catalogue, result screen |
| **Evidence** | Office Kit from hour 1, captures and calibration, demo rehearsal, pitch, `docs/` |

The third role is not overhead. 25% of the score is telemetry and 10% is the pitch —
a third of the total sits with whoever owns that work.
