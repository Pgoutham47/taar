"""
Playback files for training an arc model without an arc rig.

There is no dimmer and lamp at the venue, so the arc class is synthesised and
played through a speaker, and the phone records it. That is weaker than a real
rig and the pitch has to say so. It is still better than training on the
synthesis directly, because every clip then passes through a real speaker, a real
room and the iQOO's own microphone chain -- which is what the model will hear.

The trap this is built around: if only the arcs come out of the speaker, a model
learns "speaker" rather than "arc". So `not_arc.wav` goes through the same speaker,
at the same levels, and most of it is near-misses built from the arc's own
bursts -- off the line rate, or at the line rate but not locked to it. The only
thing separating the two files is the 100 Hz lock, which is the physical claim.

Real venue sound with nothing playing is recorded as not-arc on top of this.

Run:  .venv/bin/python spike/audio/make_playback.py [--minutes 10] [--seed 7]
"""

import argparse
import csv
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, sosfilt

from arcdetect import modulation_index, threshold_from_baselines
from sounds import SR, arc, ballast, make, motor, rustle, speech

OUT = Path(__file__).parent / "playback"

SEGMENT_S = (4.0, 8.0)       # longer than a 3 s capture, so most captures sit in one
FADE_S = 0.05
LEVEL_DBFS = (-32.0, -14.0)  # RMS; loud and quiet arcs, set against speaker volume
PEAK = 0.89

# Envelope lines at k * rep_hz. Rates of 100/k (100, 50, 33.3, 25) would put a line
# at 100 Hz and be an arc by the physical definition, so the near-misses avoid them.
OFF_RATES_HZ = [(57.0, 90.0), (112.0, 190.0), (210.0, 290.0)]


def _arc_shape(rng):
    lo = rng.uniform(0.2, 0.5)
    return dict(skip=rng.uniform(0.0, 0.3),
                width_ms=(lo, lo + rng.uniform(0.4, 1.2)),
                hiss_hz=rng.uniform(2000, 5000),
                buzz=rng.uniform(0.0, 0.3))


def arc_locked(n, rng):
    p = dict(_arc_shape(rng), jitter=rng.uniform(0.02, 0.12))
    return arc(n, rng, **p), p


def off_rate(n, rng):
    lo, hi = OFF_RATES_HZ[rng.integers(len(OFF_RATES_HZ))]
    p = dict(_arc_shape(rng), rep_hz=rng.uniform(lo, hi), jitter=rng.uniform(0.02, 0.12))
    return arc(n, rng, **p), p


def unlocked(n, rng):
    """Sparks at about the arc's rate, with timing too loose to hold a 100 Hz line."""
    p = dict(_arc_shape(rng), jitter=rng.uniform(0.4, 0.8))
    return arc(n, rng, **p), p


def hiss(n, rng):
    """Broadband and steady: the arc's spectrum with no envelope at all."""
    p = dict(hiss_hz=rng.uniform(2000, 5000), buzz=rng.uniform(0.0, 0.3))
    t = np.arange(n) / SR
    band = sosfilt(butter(4, p["hiss_hz"] / (SR / 2), btype="high", output="sos"),
                   rng.normal(0, 1, n))
    return band + p["buzz"] * np.sin(2 * np.pi * 100 * t), p


def _plain(source):
    return lambda n, rng: (source(n, rng), {})


ARC = {"arc": arc_locked}
NOT_ARC = {"off_rate": off_rate, "unlocked": unlocked, "hiss": hiss,
           "ballast": _plain(ballast), "motor": _plain(motor),
           "speech": _plain(speech), "rustle": _plain(rustle)}
# Near-misses carry the weight; the plain confounders are also recorded live.
NOT_ARC_WEIGHTS = {"off_rate": 3, "unlocked": 3, "hiss": 1,
                   "ballast": 1, "motor": 1, "speech": 1, "rustle": 1}


def _level(x, dbfs):
    rms = np.sqrt(np.mean(x ** 2))
    y = x * (10 ** (dbfs / 20) / rms) if rms > 0 else x
    peak = np.abs(y).max()
    return y * (PEAK / peak) if peak > PEAK else y  # scale down rather than clip


def _fade(x):
    k = int(FADE_S * SR)
    ramp = np.linspace(0.0, 1.0, k)
    x[:k] *= ramp
    x[-k:] *= ramp[::-1]
    return x


def playlist(kinds, weights, seconds, rng, label, file):
    names = list(kinds)
    w = np.array([weights.get(k, 1) for k in names], dtype=float)
    parts, rows, t = [], [], 0.0
    while t < seconds:
        kind = names[rng.choice(len(names), p=w / w.sum())]
        n = int(rng.uniform(*SEGMENT_S) * SR)
        audio, params = kinds[kind](n, rng)
        dbfs = rng.uniform(*LEVEL_DBFS)
        audio = _fade(_level(audio, dbfs))
        parts.append(audio)
        rows.append(dict(file=file, start_s=round(t, 3), end_s=round(t + n / SR, 3),
                         label=label, kind=kind, level_dbfs=round(dbfs, 1),
                         params={k: (tuple(round(x, 4) for x in v) if isinstance(v, tuple)
                                     else round(v, 4)) for k, v in params.items()},
                         mod_index=modulation_index(audio[: 3 * SR])))
        t += n / SR
    return np.concatenate(parts), rows


def write(path, x):
    wavfile.write(path, SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=10.0, help="length of each playlist")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = np.random.default_rng(args.seed)
    OUT.mkdir(exist_ok=True)
    seconds = args.minutes * 60

    arc_audio, arc_rows = playlist(ARC, {}, seconds, rng, "arc", "arc.wav")
    not_audio, not_rows = playlist(NOT_ARC, NOT_ARC_WEIGHTS, seconds, rng, "not_arc", "not_arc.wav")
    write(OUT / "arc.wav", arc_audio)
    write(OUT / "not_arc.wav", not_audio)

    # One steady, typical arc for the stage: no level swings mid-demo.
    demo, _ = arc_locked(60 * SR, rng)
    write(OUT / "demo_arc.wav", _fade(_level(demo, -18.0)))

    rows = arc_rows + not_rows
    with open(OUT / "manifest.csv", "w", newline="") as f:
        out = csv.DictWriter(f, fieldnames=list(rows[0]))
        out.writeheader()
        out.writerows(rows)

    # Sanity check against the rule-based detector, before anything goes near a
    # speaker: arcs should clear the quiet-room threshold, near-misses should not.
    thr = threshold_from_baselines(
        np.array([modulation_index(make("quiet", 3.0, rng)) for _ in range(100)]))
    print(f"wrote {OUT}/  arc.wav  not_arc.wav  demo_arc.wav  manifest.csv")
    print(f"{args.minutes:g} min per playlist · {SR} Hz · 16-bit mono\n")
    print(f"modulation index threshold (quiet room, 5% false alarm): {thr:.4f}\n")
    print(f"{'kind':<10} {'segments':>8} {'median MI':>10} {'above thr':>10}")
    for kind in list(ARC) + list(NOT_ARC):
        mi = np.array([r["mod_index"] for r in rows if r["kind"] == kind])
        if mi.size:
            print(f"{kind:<10} {mi.size:>8} {np.median(mi):>10.4f} {np.mean(mi > thr):>10.0%}")


if __name__ == "__main__":
    main()
