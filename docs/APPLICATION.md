# Taar — screening application

**Project title:** Taar · తార
**Track:** Smart Living (05) — runs at city battles *and* the Grand Finale
**City:** Hyderabad, 26–27 September 2026
**Tagline:** *Hold the phone near a cable. It tells you whether current is flowing —
with no probe, no contact and no network.*

Blocks below are modular, to be pasted into whatever fields the form asks for.
Approximate lengths are noted; cut from the bottom of each block.

---

## One-line description *(~200 chars)*

Taar reads mains current from the phone's magnetometer and fault signatures from its
microphone, scoring both against a circuit's own reference. It runs entirely on the
device. The detection has been measured on hardware, not assumed.

---

## The problem *(~150 words)*

Electrical faults are among the most common causes of building fires in India, and
the specific faults that cause them — a loose connection quietly arcing behind a
cover plate, a circuit run permanently over its rating — are invisible until they
are not.

The people who would catch them are small electricians and facility technicians,
diagnosing by feel. The instruments that would make it visible are a clamp meter, a
thermal camera and an arc-fault analyser: a serious capital outlay for a two-person
firm. So the tools stay with large contractors and the buildings that most need
checking never get checked.

Underneath that is a safety problem. Before working on a circuit, an electrician
needs to know it is genuinely dead. The common method is to probe it, or to trust a
label on a board that may be a decade out of date.

---

## The solution *(~140 words)*

Every current-carrying conductor broadcasts its current as a magnetic field, and
every arcing connection broadcasts its fault as sound. The phone already has
instruments for both.

Hold the phone against a cable. A three-second capture samples the magnetometer and
microphone together, and the app answers three questions:

- **Is current flowing?** A 50 Hz component in the magnetic field, measured against
  neighbouring frequencies.
- **How much, relative to this circuit's own normal?** Amplitude scored against a
  recorded reference.
- **Is anything arcing?** Broadband noise modulated at 100 Hz — twice the mains
  frequency, which is how often a series arc re-ignites.

Nothing is touched, nothing is probed, and nothing leaves the device.

---

## What we measured *(~160 words)*

This is the part that separates Taar from an idea. The app is built, runs on a
phone, and the central claim has been tested.

**Six captures against a fridge supply cable, phone taped in place and not moved
between them:**

| state | contrast at 50 Hz |
|---|---|
| no current | 3× · 2× · 2× |
| compressor running | **9× · 16×** |

A factor of three between the populations, no overlap. The detection threshold was
then set from that separation — 6×, midway — replacing a value chosen from
simulation that sat close enough to the idle readings to call a dead circuit live.

**Also measured on the device:** magnetometer at 105.3 Hz visiting 21 distinct
phases of the waveform; noise floor 0.39–0.40 µT RMS across two sessions.

**Honest scale:** six captures, one phone, one appliance, roughly 1–1.5 A through a
twin cable whose conductors largely cancel. Enough to establish the premise. Not
enough to quote a sensitivity figure, and we do not.

---

## What hardware taught us that simulation could not *(~140 words)*

Four defects surfaced only once the app ran on a phone. Each is now covered by a
regression test.

1. **Two Android clocks.** `SensorEvent.timestamp` includes deep sleep;
   `System.nanoTime()` does not. Comparing them made every capture return nothing.
2. **Vector magnitude destroyed the signal.** Reducing the three axes to
   `sqrt(x² + y² + z²)` hid the AC component behind Earth's static 45 µT — a 1 µT
   field moved the magnitude by 0.01 µT. Each axis is now fitted separately.
3. **Detection normalised by total variance.** A hand-held capture drifts several
   µT; that drift buried the 50 Hz term and reported a real signal as zero
   confidence. Now measured against neighbouring frequencies instead.
4. **Thresholds guessed rather than measured.** The arc statistic's floor was five
   times too small, and reported a domestic fridge as an arcing fault.

A simulation that had predicted any of these would have been a better simulation.
It didn't.

---

## Architecture *(~110 words)*

| Layer | Component |
|---|---|
| Current | Per-axis sine fit at 50 Hz, detrended; amplitude is the vector sum |
| Detection | Lomb–Scargle contrast against 30–48 and 52–70 Hz |
| Arc | 4–16 kHz band → envelope → power at 100 Hz, normalised |
| Scoring | Median and MAD against the circuit's own reference |
| Explanation | Rules table over six faults, each with evidence and an action |
| Learning | Nearest-centroid classifier, trained on-device from the technician's own labels |

**Built:** native Android, Kotlin, Compose. **44 of 44 logic checks pass**; the app
builds to a 9.3 MB APK. No `INTERNET` permission in the manifest — a claim
reviewable in thirty seconds.

**What is not built:** a local language model for plain-language advice. It is
planned for the build window; nothing in the app calls one today, and we would
rather say so than have it found.

---

## Why this must run on the device *(~90 words)*

**Boards live where there is no signal** — basements, plant rooms, riser cupboards.

**The reference is inherently local.** A baseline for *this* board means nothing
anywhere else. There is nothing a server would usefully hold.

**The output is a building's electrical map.** Circuit layout and load profile of a
hospital or office is a security document, not something a contractor should post to
a third-party API to get a reading back.

---

## Scope — what Taar does not do *(~110 words)*

- **It is a triage aid**, not a certification. It does not replace a licensed
  electrician, a calibrated clamp meter, an insulation tester or a statutory
  inspection. The app says so on the result screen.
- **It reports a load index, not amperes**, unless the circuit has been calibrated
  against a known load. A fabricated ampere figure is worse than none.
- **The acoustic half is designed and plausible, not validated.** No real arc has
  been recorded. The magnetometer half rests on textbook physics and now on
  measurement.
- **`UNPROCESSED` audio was refused** by our test device, so the arc band may be
  attenuated on some phones. Recorded rather than hidden.
- **It refuses rather than guesses.** No reference, or an ill-conditioned fit,
  produces a refusal.

---

## What we build in the 30 hours *(~90 words)*

The entry is written inside the event window. Our pre-event work is a research spike
on a separate branch, rebuilt from an empty scaffold on the day and never merged —
the protocol is written down in `HACKATHON.md` and we will confirm it with an
organiser at check-in.

Priority order: capture and pre-check, per-circuit reference and scoring, the fault
catalogue, the result screen. Then calibration against a known load, which is what
turns the index into amperes. If those are solid, a small local language model on
the NPU for plain-language advice — as a stretch, and only if it earns its place.

We would rather demo one board that works than five features that half-work.

---

## The demo *(~80 words)*

Ninety seconds on the iQOO phone, airplane mode visibly on.

Phone taped to an appliance cable. Appliance off: **Measure** — no mains detected.
Appliance on: **Measure** — contrast jumps, load index climbs, and the screen says
which circuit is live and by how much.

The audience sees a phone detect electricity through insulation, without touching
anything. Yesterday that was a claim. Today there are six captures behind it.

---

## Why this team

We have shipped production AI across health, education and voice — a deployed
caregiver platform running Indian-language voice agents, an AI career-readiness
platform with voice-graded assessment and proctored interviews, and a
classroom-intervention tool for government school teachers. We have also delivered
hard latency engineering: a 6–8 second load path cut to **243 ms measured**.

More relevant to this build: we wrote the signal processing, validated it in
simulation, put it on a phone, found four defects that simulation had missed, fixed
each with a regression test, and set every threshold from measured data rather than
from a guess. That loop is what a 30-hour build needs.

---

## Notes for whoever submits this

- **Team size** is capped at 3. Confirm who is in.
- **Bucket**: all students or all working professionals. No mixing.
- Two sections to keep whatever the character limits allow: **What we measured** and
  **What hardware taught us**. Most entries are an idea; this one is an idea with
  six measurements and a bug list behind it.
- Every number here is traceable: the captures are in `docs/PREFLIGHT.md` §3, the
  checks in `spike/android/tools/verify.sh`, the findings in `spike/dsp/README.md`
  and `spike/audio/README.md`.
