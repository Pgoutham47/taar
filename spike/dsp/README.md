# Taar DSP spike — findings

**Status: reference spike. Not submission code.** This exists to answer the
questions in `HACKATHON.md` §2 before the event, so the 30 hours are not spent
discovering them. See §1 of that file for the rebuild protocol.

```bash
python3 -m venv .venv && .venv/bin/pip install numpy scipy
.venv/bin/python spike/dsp/sweep.py
.venv/bin/python spike/dsp/harmonic_lock.py
```

Simulation only — no hardware was involved. Every number below is a property of
the model in `simulate.py`, not a measurement of an iQOO 15. The point is to know
what to measure on the day and what to do about each answer.

---

## The headline: the risk was misdiagnosed

The plan assumed the danger was a **slow magnetometer** — that below ~100 Hz you
could not see a 50 Hz signal. That is wrong, and the correction is useful.

Sampling at **24 Hz** recovers the 50 Hz amplitude to **0.3%**, less than half the
supposed Nyquist rate. Sampling at **100 Hz** — the common Android default — fails
at **22%** error. Sampling at exactly **50 Hz** fails completely, at **100%**.

The governing quantity is not the rate. It is how many distinct phases of the
waveform the sampling visits per cycle:

```
phases = fs / gcd(fs, 50)        fitting sin + cos needs >= 3
```

| rate | gcd | phases | verdict |
|---:|---:|---:|---|
| 24 | 2 | 12 | fine |
| **25** | 25 | **1** | **degenerate** |
| 26 | 2 | 13 | fine |
| **50** | 50 | **1** | **degenerate** |
| 75 | 25 | 3 | fine |
| 99 | 1 | 99 | fine |
| **100** | 50 | **2** | **marginal** |
| 150 | 50 | 3 | fine |

At 50 Hz every sample lands on the same phase, so the signal reads as a constant
and mean-subtraction deletes it. At 100 Hz the sampling alternates between two
phases, which is ill-conditioned and phase-dependent. At 150 Hz — also an integer
multiple — three phases is enough, and it is fine.

**Design consequence.** `detect.safe_rate()` moves a locked rate to the nearest
unlocked one: 100 → 99, 50 → 49, 25 → 24. One call at sensor registration removes
the entire risk. Real device jitter also breaks the lock, so hardware is safer
than this simulation's uniform case — but relying on jitter that has not been
measured is not a plan.

---

## Sensitivity

Field at 3 cm from a conductor: **1 A produces 9.43 µT peak**, against an Earth
background of 45 µT and assumed sensor noise of 0.5 µT RMS.

Minimum detectable current, 3 s capture, 5% false alarm, 90% probability of
detection:

| condition | Lomb–Scargle | magnitude-only |
|---|---:|---:|
| bench, 100 Hz / 15% jitter | **0.02 A** | 0.05 A |
| hand-held, same stream | **0.05 A** | 0.25 A |

"Hand-held" adds 3 µT of ~10 Hz tremor and 6 µT of drift across the capture —
both of which are larger than the signal from a 0.25 A load.

Any real circuit is far above these floors. A 5 A appliance produces 47 µT, which
is comparable to the Earth's field. **Sensitivity is not the constraint**, which
was worth establishing: it means effort belongs in specificity and calibration,
not in chasing a lower noise floor.

---

## Why Lomb–Scargle earns its place

On a bench the two statistics are within a factor of 2, and a magnitude statistic
is far simpler. The difference appears when the phone is in a hand:

**5× better minimum detectable current at 100 Hz, 12× at 200 Hz.**

Because magnitude cannot tell a 50 Hz field from hand tremor, tremor in the
baseline captures raises its threshold and its sensitivity collapses. A
50 Hz-specific statistic ignores tremor by construction.

That is the argument for implementing it, and it only shows up once the simulation
includes a hand. On clean synthetic data the extra machinery looks unjustified.

---

## What this does not establish

- **No hardware was measured.** Sensor noise (0.5 µT RMS), resolution (0.15 µT)
  and the tremor model are assumptions. All three need checking on the loaner.
- **The tremor model is a guess** — a wandering ~10 Hz tone. Real tremor coupled
  through the Earth's field may be larger, smaller or differently shaped.
- **Nothing here touches arc detection.** The acoustic half of the product is
  unmodelled and is the larger remaining unknown.
- **A single conductor is assumed.** At a real board, adjacent circuits contribute
  and live/neutral pairs largely cancel. Not modelled, and it is the thing most
  likely to make field results worse than these.

---

## What to do on the day

1. Log 10 s of magnetometer timestamps. Record mean rate and inter-sample jitter.
2. Compute `distinct_phases(measured_rate)`. If it is below 3, re-register at
   `safe_rate()`.
3. Measure the real noise floor with the phone still and far from any conductor.
   Substitute it for the 0.5 µT assumption and re-run `sweep.py`.
4. Hold the phone 3 cm from a cable under a known load and confirm a deflection.
   **If there is none, stop** — the premise is wrong and the fallback idea applies.
5. Record all of it in `docs/PREFLIGHT.md` with raw numbers.
