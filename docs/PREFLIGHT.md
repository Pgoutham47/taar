# PREFLIGHT — Taar, hour 0–1

**Template. Fill this in on the loaner, before any feature work.**

Every spike result rests on an assumption about hardware nobody has measured. This
hour replaces the assumptions with numbers, and each number has a decision attached
to it. Two of them can end the project, which is the point of doing this first
rather than at hour twenty.

Budget: **60 minutes.** If a step is taking longer, record what you have, note it
as incomplete, and move on.

```
Date / time     ______________________
Device          ______________________   (model, Android version, OriginOS version)
Phone serial    ______________________   (loaner tag, so readings are traceable)
Filled in by    ______________________
Venue / room    ______________________   (noise floor depends on it)
```

---

## 0 · Golden tests — do this before touching a sensor

The Kotlin in `spike/android/` has never been compiled. Run the transcription
tests before trusting any reading that comes out of it.

```bash
./gradlew test --tests '*GoldenTest*'
```

```
Tests passed          ____ / 7
First failure         ______________________________________________
Fixed by              ______________________________________________
```

☐ All passing before continuing. If a DSP test fails, the Python under
`spike/dsp/` is the reference — the Kotlin is wrong, not the fixture.

---

## 1 · Magnetometer rate and jitter

Register `TYPE_MAGNETIC_FIELD` at `SENSOR_DELAY_FASTEST`, log 10 s of timestamps.

```
Requested rate            ________ Hz
Mean measured rate        ________ Hz
Inter-sample jitter       ________ %   (stddev / mean interval)
Min / max interval        ________ / ________ ms
Samples in 3 s            ________
distinctPhases(measured)  ________
```

**Decision:**

| measured rate | do this |
|---|---|
| `distinctPhases ≥ 3` | nothing — `safeRate()` already handled it |
| `distinctPhases < 3` | confirm `MagCapture` is calling `LineFrequency.safeRate()`. If it is and you still land on 10/20/25/50/100 Hz, the sensor is clamping — record the clamped rate and fall back to magnitude-only |
| jitter > 1% | good. Irregular sampling breaks harmonic lock |
| jitter < 0.1% | unusually uniform — double-check the rate is not 10, 20, 25, 50 or 100 |

```
Path chosen:   ☐ Lomb-Scargle + amplitude    ☐ magnitude-only index
Why: ________________________________________________________________
```

---

## 2 · Noise floor

Phone still, flat on a table, **away from any conductor, laptop or charger**.
Capture 3 s, ten times.

```
Field magnitude, mean     ________ uT     (Earth field — expect roughly 40-48 in Hyderabad)
Centred RMS noise         ________ uT     SPIKE ASSUMED 0.5
Reported resolution       ________ uT     SPIKE ASSUMED 0.15
Lomb-Scargle @50 Hz       ________        (should be near zero with nothing nearby)
```

**Decision:** substitute the measured RMS into `spike/dsp/simulate.py` (`noise_ut`)
and re-run `sweep.py`. It takes two minutes and tells you the real detection floor.

```
Re-run min detectable current, hand-held:  ________ A   (spike said 0.05)
```

⚠️ If Lomb-Scargle at 50 Hz is **not** near zero with nothing nearby, something in
the room is radiating at mains frequency. Find it before baselining anything.

---

## 3 · Field deflection — ⛔ STOP CONDITION

Power strip, an appliance drawing a known load, phone held **3 cm** from the cable.

```
Appliance / rated load    ______________________  ( ________ A expected )

Field magnitude, load OFF   ________ uT
Field magnitude, load ON    ________ uT
Difference                  ________ uT     PREDICTED: 9.43 uT per amp at 3 cm
Lomb-Scargle @50 Hz, ON     ________
Fitted amplitude, ON        ________ uT
Implied current             ________ A      (fitted / 9.43)
Error vs rated              ________ %
```

**Decision:**

- ☐ **Clear deflection, implied current within ~30% of rated** → the premise holds.
  Proceed. Record the 3 cm distance assumption; you will need it for calibration.
- ☐ **Deflection present but implied current far off** → geometry. The magnetometer
  sits in one corner of the phone, and live/neutral in a twin cable partially
  cancel. Find the corner, mark it, and calibrate against a known load rather than
  reporting absolute amperes.
- ☐ **⛔ No deflection above the noise floor** → **STOP.** The premise is wrong.
  Switch to the fallback idea now, at hour one. Do not spend the weekend on it.

```
Outcome: ____________________________________________________________
Sensor corner located at: ____________________________________________
```

---

## 4 · Audio path

The arc signature lives at 4–16 kHz. A voice audio path is designed to remove
exactly that.

```
AudioSource.UNPROCESSED granted?   ☐ yes   ☐ no, fell back to ________
Sample rate achieved               ________ Hz
Logcat line confirming source      ______________________________________
```

Record 3 s of a 10 kHz tone played from another device and check it survives:

```
High-band (4-16 kHz) RMS, tone playing    ________
High-band RMS, silence                    ________
Ratio                                     ________
```

**Decision:** if the ratio is small, the audio chain is band-limiting and the arc
half is in trouble. Note it, and lead the pitch with the magnetometer.

---

## 5 · Arc proxy — triac lamp dimmer

⚠️ **Do not create an arcing connection in mains wiring.** A deliberately loose
connection under load is a fire and shock hazard. Use a dimmer on an incandescent
or halogen lamp: it chops the waveform once per half-cycle and produces the same
100 Hz-locked broadband signature, safely and repeatably.

```
Dimmer + lamp available?     ☐ yes   ☐ no — source one, or the demo has no fault to show

Modulation index, dimmer OFF      ________     (baseline)
Modulation index, dimmer 25%      ________
Modulation index, dimmer 50%      ________
Modulation index, dimmer 75%      ________
Threshold from 20 baselines (5% FA)  ________

Separation achieved?   ☐ clean   ☐ marginal   ☐ none
```

Also record, if the venue has one:

```
Ballast / transformer hum present?   ☐ yes   ☐ no
Modulation index of that hum         ________     (spike predicted ~4% false alarm)
```

**Decision:** the ballast number is the one that matters. A tool that cries arc at a
transformer is worse than no tool. If it false-alarms, the acoustic half does not
ship — say so in the pitch and lead with the magnetometer.

---

## 6 · Sustained use

20 captures back to back — the HackTracker checklist wants that many anyway.

```
Sample rate, first capture     ________ Hz
Sample rate, 20th capture      ________ Hz
Phone warm to touch?           ☐ yes  ☐ no
Battery drop over 20           ________ %
Any capture failed?            ________ / 20
```

**Decision:** if the rate degrades when warm, the app must re-measure per capture
rather than trusting the rate it saw at registration.

---

## 7 · Go / no-go

```
☐ GO        — §3 passed, path chosen in §1, proceed to build order §5
☐ GO, REDUCED — magnetometer only; acoustic half not demonstrable
☐ NO-GO     — §3 failed; switching to ____________________________

Decided at  ________  by  ______________________
```

---

## 8 · What changed from the spike's assumptions

Fill this in even if everything passed. It is the honest-scoping material for the
pitch, and it is the difference between "we measured it" and "we assumed it".

| Quantity | Spike assumed | Measured | Consequence |
|---|---|---|---|
| Magnetometer rate | 100 Hz nominal | **105.3 Hz** (dev phone, 21 Sep) | 21 distinct phases — safe |
| Timing jitter | 15% | **~0%** | irrelevant at 21 phases |
| Sensor noise | 0.5 uT RMS | **0.39 uT** | better than assumed |
| Resolution | 0.15 uT | ________ | |
| Field at 3 cm | 9.43 uT/A | ________ | **the open question** |
| Min detectable, hand-held | 0.05 A | **0.02 A** (re-run with 0.39) | 2.5x better |
| Audio source | UNPROCESSED | **refused, fell back to MIC** | check the arc band survives |
| Ballast false alarm | 4% | ________ | |

*Rows filled from a developer phone on 21 Sept. Repeat on the loaner — the numbers
above are not the iQOO's.*

```
Anything the spike got wrong:
_____________________________________________________________________
_____________________________________________________________________

Anything the spike did not anticipate:
_____________________________________________________________________
_____________________________________________________________________
```

---

## Also confirm with an organiser, while you are at check-in

Not measurements, but same hour, and two of them can cost the entry.

```
☐ Which original-work wording binds — the Guide page, or the stricter version
  quoting a spikes/ folder and commit-history checks?        Answer: __________
☐ Is a pre-event spike in a separate, unmerged branch acceptable?   ☐ yes ☐ no
☐ Team bucket confirmed: ☐ all students  ☐ all working professionals
☐ Team size ≤ 3 confirmed
☐ Office Kit paired, and one person owning it from now
☐ Red/Green block times written into HACKATHON.md §3
```
