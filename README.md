# Taar · తార

**Hold the phone near a distribution board. It tells you which circuits are live,
how hard they are working, and whether anything is arcing — without touching a
wire.**

Preparation for **iQOO City Battles 2026 — Hyderabad**, 26–27 September, Track 05
(Smart Living).

---

## ⚠️ This repository is not a competition entry

The rules require original work written inside the 30-hour event window, and
prohibit carrying in a completed app. Everything here is **pre-event reference
material**: research spikes that answer questions which would otherwise consume
event hours, and a skeleton that gets **retyped** on the day.

The protocol is in [`HACKATHON.md`](HACKATHON.md) §1 — orphan branch, empty
scaffold committed live, retype rather than paste, never merge from here.

---

## The idea

Every current-carrying conductor broadcasts its current as a magnetic field, and
every arcing connection broadcasts its fault as sound. A phone already has
instruments for both.

- **Magnetometer → current.** `B = μ₀I/2πr`. At 3 cm from a conductor carrying 5 A
  the field is ~33 µT, against an Earth background of ~45 µT. The principle a clamp
  meter uses, without the clamp.
- **Microphone → fault.** A series arc re-ignites twice per mains cycle, so its
  broadband noise is amplitude-modulated at 100 Hz.

Everything runs on the device. Boards live in basements with no signal, a
building's circuit layout is not a contractor's data to upload, and a measurement
of response time cannot have a network round trip inside it.

---

## What is in here

| | |
|---|---|
| [`HACKATHON.md`](HACKATHON.md) | Rules position, build order, Red/Green tagging, HackTracker checklist, demo fallback tree |
| [`docs/APPLICATION.md`](docs/APPLICATION.md) | The screening application, in pasteable blocks |
| [`docs/PREFLIGHT.md`](docs/PREFLIGHT.md) | The hour-one measurement form, with the hardware results filled in |
| [`spike/dsp/`](spike/dsp/) | Can a phone magnetometer see 50 Hz? |
| [`spike/audio/`](spike/audio/) | Does an arc separate from a ballast, speech, a rustle and a motor? |
| [`spike/android/`](spike/android/) | The app — builds clean, 36/36 logic checks pass |

---

## Measured on hardware

Six captures against a fridge supply cable, phone taped in place:

| state | contrast at 50 Hz |
|---|---|
| no current | 3× · 2× · 2× |
| compressor running | **9× · 16×** |

A factor of three, no overlap. The live threshold is set from that separation rather
than from simulation. Magnetometer measured at 105.3 Hz over 21 distinct phases;
noise floor 0.39–0.40 µT RMS. Full record and caveats in
[`docs/PREFLIGHT.md`](docs/PREFLIGHT.md) §3.

---

## Findings worth reading first

**The sampling risk was misdiagnosed.** The danger is not a slow magnetometer.
24 Hz recovers a 50 Hz amplitude to 0.3%; **100 Hz — the Android default — fails at
22%, and 50 Hz fails completely.** What governs it is how many distinct phases of
the waveform the sampling visits: `phases = fs / gcd(fs, 50)`, and fitting sin+cos
needs at least three. Locked rates below 150 Hz are **10, 20, 25, 50 and 100**.

The fix is one call at sensor registration. See [`spike/dsp/README.md`](spike/dsp/README.md).

**An energy detector cannot find an arc.** Against four confounders, a plain
high-band energy statistic fired **100% of the time on every one of them**. The
100 Hz modulation index held its 5% design point. Same lesson as the magnetometer,
reached independently: do not measure energy, measure energy at the line-locked
frequency. See [`spike/audio/README.md`](spike/audio/README.md).

---

## Running it

```bash
cd spike/android
./gradlew assembleDebug testDebugUnitTest   # full app + unit tests
./tools/verify.sh                            # logic checks only, no Android SDK needed
```

```bash
cd spike/dsp    && python3 -m venv .venv && .venv/bin/pip install numpy scipy
.venv/bin/python sweep.py
.venv/bin/python harmonic_lock.py
```

---

## Scope, stated up front

Taar is a **triage aid**. It does not replace a licensed electrician, a calibrated
clamp meter, an insulation tester or a statutory inspection. It reports change
against a circuit's own reference; it reports amperes only when that circuit has
been calibrated against a known load, and a relative index otherwise.

The acoustic half is **designed and plausible, not validated** — no real arc has
been recorded. The magnetometer half rests on textbook physics and is on firmer
ground. Both still need the measurements in `docs/PREFLIGHT.md`.
