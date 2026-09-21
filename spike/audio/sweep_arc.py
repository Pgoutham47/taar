"""
Does the 100 Hz modulation index separate an arc from the things that share its
properties, and at what level?

The number that decides whether this ships is not the detection rate. It is the
false-alarm rate against a ballast hum, because a ballast is in the same cupboard
as the board and a tool that cries arc at a transformer is worse than no tool.

Run:  .venv/bin/python spike/audio/sweep_arc.py
"""

import numpy as np

from sounds import make
from arcdetect import modulation_index, hf_energy, threshold_from_baselines

TRIALS = 200
FALSE_ALARM = 0.05
CONFOUNDERS = ["ballast", "speech", "rustle", "motor"]
LEVELS = [0.02, 0.05, 0.1, 0.2, 0.4, 0.8]


def baseline_threshold(stat, rng, false_alarm=FALSE_ALARM):
    """Threshold from quiet-room captures — what the app records as its baseline."""
    vals = [stat(make("quiet", 3.0, rng)) for _ in range(TRIALS)]
    return threshold_from_baselines(np.array(vals), false_alarm)


def rate(stat, kind, level, thr, rng, n=TRIALS):
    """Fraction of `kind` clips at `level` exceeding the threshold."""
    hits = sum(stat(make(kind, 3.0, rng, level=level)) > thr for _ in range(n))
    return hits / n


def main() -> None:
    rng = np.random.default_rng(4242)

    print("Taar arc-detection spike")
    print(f"{TRIALS} trials · 3 s clips · 44.1 kHz · threshold from quiet-room "
          f"baselines at {FALSE_ALARM:.0%} false alarm\n")

    thr_mod = baseline_threshold(modulation_index, rng)
    thr_hf = baseline_threshold(hf_energy, rng)
    print(f"thresholds:  modulation index {thr_mod:.4f}   hf band energy {thr_hf:.4f}\n")

    print("=" * 66)
    print("DETECTION — arc, by level")
    print("=" * 66)
    print(f"\n{'arc level':>10} | {'mod@100Hz':>10} {'hf energy':>9}")
    print("-" * 34)
    for lv in LEVELS:
        pd_mod = rate(modulation_index, "arc", lv, thr_mod, rng)
        pd_hf = rate(hf_energy, "arc", lv, thr_hf, rng)
        print(f"{lv:>10.2f} | {pd_mod:>9.0%} {pd_hf:>8.0%}")

    print("\n" + "=" * 66)
    print("FALSE ALARMS — the number that decides whether this ships")
    print("  every confounder at level 0.4, well above the arc's detection floor")
    print("=" * 66)
    print(f"\n{'scene':>10} | {'mod@100Hz':>10} {'hf energy':>9}")
    print("-" * 34)
    false_alarms = {}
    for kind in CONFOUNDERS:
        fa_mod = rate(modulation_index, kind, 0.4, thr_mod, rng)
        fa_hf = rate(hf_energy, kind, 0.4, thr_hf, rng)
        false_alarms[kind] = (fa_mod, fa_hf)
        print(f"{kind:>10} | {fa_mod:>9.0%} {fa_hf:>8.0%}")

    print("\n" + "=" * 66)
    print("ARC INSIDE A NOISY ELECTRICAL ROOM")
    print("  arc at 0.2 mixed with a confounder at 0.4 — the realistic case")
    print("=" * 66)
    print(f"\n{'arc + ':>14} | {'mod@100Hz':>10}")
    print("-" * 28)
    for kind in CONFOUNDERS:
        hits = 0
        for _ in range(TRIALS):
            mixed = (make("arc", 3.0, rng, level=0.2)
                     + make(kind, 3.0, rng, level=0.4, room_level=0.0))
            hits += modulation_index(mixed) > thr_mod
        print(f"{('arc + ' + kind):>14} | {hits / TRIALS:>9.0%}")

    print("\n" + "=" * 66)
    print("READING THIS")
    print("=" * 66)
    fa_rustle_mod, fa_rustle_hf = false_alarms["rustle"]
    print(f"\nA sleeve brushing the phone fires the naive high-frequency detector")
    print(f"{fa_rustle_hf:.0%} of the time, against {fa_rustle_mod:.0%} for the modulation index.")
    print("Energy alone cannot tell an arc from a rustle. Energy modulated at twice")
    print("the line frequency can, because only one of them is tied to the grid.")


if __name__ == "__main__":
    main()
