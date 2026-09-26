# Taar arc-detection spike — findings

**Status: reference spike. Not submission code.** See `HACKATHON.md` §1.

```bash
.venv/bin/python spike/audio/sweep_arc.py
```

---

## Read this before the results

Unlike the magnetometer spike, where `B = μ₀I/2πr` is textbook and the signal
model is sound, **an arc's acoustic signature is empirical and I invented it.**
Synthesising an arc and then detecting it proves very little by itself.

Two things keep this from being circular, and one thing does not:

- The detector keys on **one physical claim** — that a series arc in AC re-ignites
  twice per cycle, so its noise is amplitude-modulated at 100 Hz. It is not tuned
  to anything else about how `sounds.arc()` shapes a burst.
- The confounders each share **one** of the arc's properties. A ballast is also
  100 Hz periodic. A rustle is also broadband and transient. Speech is also
  amplitude-modulated. A detector that survives all three is keying on the
  conjunction, not on an artefact.
- **What remains unproven is whether a real arc looks like this at all.** That
  needs a recording. See "What to do on the day".

---

## The result

Threshold set from quiet-room baselines at a 5% false-alarm design point.

### Detection — arc, by level

| arc level | modulation @100 Hz | hf band energy |
|---:|---:|---:|
| 0.02 | 85% | 100% |
| 0.05 | **100%** | 100% |
| ≥0.10 | **100%** | 100% |

Both detect an arc. That was never the hard part.

### False alarms — the number that decides whether this ships

| scene | modulation @100 Hz | hf band energy |
|---|---:|---:|
| ballast hum | **4%** | 100% |
| speech | **6%** | 100% |
| sleeve / keys rustle | **10%** | 100% |
| motor | **5%** | 100% |

The modulation index sits at its 5% design point across every confounder. **The
energy detector fires on all of them, every time** — because an energy detector
detects sound, and an electrical room is full of sound.

This is the whole argument for the extra machinery, and it is the same argument
the magnetometer spike produced: do not measure energy, measure energy at the
line-locked frequency. Only one thing in the room is tied to the grid.

### Arc inside a noisy room — arc at 0.2 mixed with a confounder at 0.4

| mixture | detected |
|---|---:|
| arc + ballast | 100% |
| arc + speech | 100% |
| arc + motor | 100% |
| arc + rustle | 92% |

A ballast humming at the same 100 Hz does not mask the arc, because the ballast is
tonal and never reaches the 4–16 kHz band the envelope is taken from.

---

## How it works

```
bandpass 4–16 kHz  →  Hilbert envelope  →  decimate to 2 kHz
                   →  FFT of the envelope
                   →  power in ±3 Hz around 100 Hz, over power in 20–400 Hz
```

Normalising by total envelope power makes the statistic independent of loudness,
so one threshold serves a quiet cupboard and a plant room. Decimating to 2 kHz is
what makes it cheap enough to run on every capture on the phone.

The ±3 Hz window tolerates grid frequency drift.

---

## Two bugs found while building this, both mine

Recorded because they are the kind that survive into a submission:

1. **The arc's mechanical buzz scaled as `level²`** — it was multiplied by `level`
   inside the function and again outside. At high arc levels the extra
   low-frequency energy inflated the denominator of the ratio-based comparator,
   producing a nonsensical "detection gets worse as the arc gets louder" result.
2. **The naive comparator was rigged twice, in opposite directions.** First as a
   *ratio*, which is fragile to whatever occupies the denominator and scored 0%.
   It now uses absolute band energy — its strongest reasonable form. A comparison
   against a straw man tells you nothing.

---

## What this does not establish

- **No real arc was recorded.** This is the central gap.
- **No real room was recorded.** The ambient model is pink noise plus rumble.
- **Phone microphone response is not modelled** — AGC, noise suppression and the
  codec may attenuate exactly the 4–16 kHz band this depends on.
  `AudioSource.UNPROCESSED` matters here and needs checking on the loaner.
- **50 Hz is assumed.** A generator-backed supply may not be at 50.000 Hz; the
  ±3 Hz window covers drift but not a different nominal frequency.

---

## What to do on the day

**Do not create an arcing connection in mains wiring to test this.** A deliberately
loose connection carrying an appliance load is a genuine fire and shock hazard, and
it is not a reasonable thing to do on a hackathon floor. The demo plan in
`HACKATHON.md` §6 originally said to wobble a plug — that was wrong and has been
changed.

**Use a triac lamp dimmer instead.** A cheap dimmer on an incandescent or halogen
lamp chops the AC waveform once per half-cycle, producing a broadband buzz locked
to 100 Hz — the same line-locked modulation signature, from a device designed to
be handled. It is safe, repeatable, adjustable by turning the knob, and it makes a
better demo than a fault nobody can reliably reproduce.

Then:

1. Record the dimmer at several settings with `AudioSource.UNPROCESSED`. Confirm
   the 4–16 kHz band survives the phone's audio chain.
2. Record the same room with the dimmer off, for baselines.
3. Compute the modulation index on both. If the separation is nothing like the
   table above, the acoustic half does not ship — say so and lead with the
   magnetometer, which is on much firmer ground.
4. Record a ballast or transformer if the venue has one. It is the confounder that
   matters and the only one you cannot simulate honestly.

---

## Without a dimmer: speaker playback

No dimmer and lamp could be sourced at the venue, and mains wiring is not assembled
on a hackathon floor. The fallback is to synthesise the arc, play it through a
speaker and record it on the phone, so the model at least hears the real speaker,
room and microphone chain.

```bash
.venv/bin/python spike/audio/make_playback.py        # ~5 s, writes spike/audio/playback/
```

| file | label | what is in it |
|---|---|---|
| `arc.wav` | arc | 10 min of varied arcs: level, jitter, skipped strikes, burst width, hiss band |
| `not_arc.wav` | not_arc | 10 min, mostly **near-misses built from the same bursts** — off the line rate, or at it but unlocked — plus ballast, motor, speech, rustle, steady hiss |
| `demo_arc.wav` | — | 60 s of one steady arc, for the stage |
| `manifest.csv` | — | every segment's kind, level and parameters |

`not_arc.wav` exists because of one trap: if only arcs come out of the speaker, a
model learns "speaker", not "arc". Both files go through the same speaker at the
same volume, and the only thing separating them is the 100 Hz lock.

The script checks its own output against `arcdetect.modulation_index` before
anything is played (seed 7, threshold from quiet-room baselines at 5% false alarm):

| kind | segments | above threshold |
|---|---:|---:|
| arc | 96 | **100%** |
| off-rate bursts | 29 | 0% |
| unlocked bursts | 25 | 4% |
| steady hiss | 6 | 0% |
| ballast · motor · speech · rustle | 38 | 0–9% |

### Recording protocol

1. **One speaker, fixed volume** for both playlists. Note which speaker.
2. Play `arc.wav`; take 3 s captures labelled **arc** at 10, 30 and 60 cm.
   Play `not_arc.wav`; the same, labelled **not_arc**.
3. With nothing playing, capture real venue sound as **not_arc**: fans, AC,
   talking, laptop chargers, tube lights, rustling, a quiet corner.
4. Spread this over **at least four sessions** in different places. Write the
   session on every capture.
5. **Split train and test by session, never by clip.** Clips from one session
   share a room and a speaker position; a random split leaks both and reports
   accuracy the demo will not reproduce.

### What this does not establish

That a real arc sounds like `arc()`. A model trained on this learns the synthesis,
heard through a real phone. The pitch says **simulated arc, real phone, real
not-arc** — and that the same pipeline retrains on a dimmer rig's recordings
unchanged.

---

## The arc model

```bash
python3 -m venv .venv-train && .venv-train/bin/pip install tensorflow scipy
.venv-train/bin/python spike/audio/train_arc.py --data ~/Downloads/taar_recordings
```

`arcfeatures.py` turns one 3 s capture into 89 numbers using only DSP the app
already runs — the same filter coefficients (read from `golden/filter_sos.csv`),
the same decimation and the same FFT sizes — so the Kotlin port is a
transcription, not a re-derivation. Every feature is a fraction, so loudness and
distance cannot be what the model learns. `train_arc.py` fits a 89→32→16→1 network,
scores it on a session it never heard beside the app's rule, retrains on
everything and writes `model/taar_arc.tflite` (16.6 KB), `model_card.json` and
`golden_features.csv` for the Kotlin test.

First run, iQOO, 26 Sept — session 1 (one table, three distances) and a short
session 2 (another spot, 30 cm):

| held-out session | | model | rule |
|---|---|---:|---:|
| s1 (trained on s2 only) | detection | 94.8% | 100% |
| | **false alarm** | **0.6%** | 10.2% |
| s2 (trained on s1) | detection | 100% | 100% |
| | false alarm | 0% | 0% |

The model trades about 5% of detections for a false-alarm rate a sixteenth of
the rule's, on the session it was not trained on. Two sessions is thin, s2 is
small and easy, and the arc is still synthetic through a speaker.
