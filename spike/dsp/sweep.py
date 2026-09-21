"""
The experiment.

Two questions, in order of how much they matter:

  Q1  Can the 50 Hz amplitude be *estimated* at the rates Android delivers, or
      only detected? Estimation is what separates "3.1 A" from "loaded".
  Q2  In a hand-held capture — where tremor and drift dwarf the signal — does a
      50 Hz-specific statistic still work where a magnitude statistic does not?

Q2 is the one that decides whether Lomb-Scargle is worth implementing. On a clean
bench both statistics work; the product is not used on a bench.

Run:  .venv/bin/python spike/dsp/sweep.py
"""

from typing import Optional, Tuple

import numpy as np

from simulate import StreamSpec, capture, field_amplitude_ut
from detect import (
    lombscargle_power,
    amplitude_estimate,
    magnitude_index,
    threshold_from_baselines,
)

TRIALS = 500
FALSE_ALARM = 0.05
TARGET_PD = 0.90
DISTANCE_M = 0.03

CURRENTS_A = [0.02, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 16.0]
RATES = [30.0, 50.0, 75.0, 100.0, 200.0]
JITTERS = [0.0, 0.05, 0.15, 0.30]

# A hand-held phone, not a phone on a bench.
TREMOR_UT = 3.0
DRIFT_UT = 6.0


def min_detectable(spec: StreamSpec, seed: int) -> Tuple[Optional[float], Optional[float]]:
    """Smallest current in CURRENTS_A reaching TARGET_PD, for each statistic."""
    rng = np.random.default_rng(seed)

    null_ls, null_mag = [], []
    for _ in range(TRIALS):
        t, v = capture(spec, 0.0, rng)
        null_ls.append(lombscargle_power(t, v))
        null_mag.append(magnitude_index(v))
    thr_ls = threshold_from_baselines(np.array(null_ls), FALSE_ALARM)
    thr_mag = threshold_from_baselines(np.array(null_mag), FALSE_ALARM)

    min_ls = min_mag = None
    for cur in CURRENTS_A:
        amp = field_amplitude_ut(cur, DISTANCE_M)
        hit_ls = hit_mag = 0
        for _ in range(TRIALS):
            t, v = capture(spec, amp, rng)
            hit_ls += lombscargle_power(t, v) > thr_ls
            hit_mag += magnitude_index(v) > thr_mag
        if min_ls is None and hit_ls / TRIALS >= TARGET_PD:
            min_ls = cur
        if min_mag is None and hit_mag / TRIALS >= TARGET_PD:
            min_mag = cur
        if min_ls is not None and min_mag is not None:
            break
    return min_ls, min_mag


def amplitude_error(spec: StreamSpec, current_a: float, seed: int) -> float:
    rng = np.random.default_rng(seed + 7919)
    true_amp = field_amplitude_ut(current_a, DISTANCE_M)
    errs = [
        abs(amplitude_estimate(*capture(spec, true_amp, rng)) - true_amp) / true_amp
        for _ in range(300)
    ]
    return float(np.median(errs))


def fmt(x: Optional[float]) -> str:
    return f"{x:g}" if x is not None else ">16"


def main() -> None:
    print("Taar DSP spike")
    print(f"{TRIALS} trials · {FALSE_ALARM:.0%} false alarm · 90% Pd · 3 s capture · phone 3 cm away")
    print(f"Field at 3 cm: 1 A -> {field_amplitude_ut(1.0, DISTANCE_M):.2f} uT peak")
    print(f"Earth background 45 uT · sensor noise 0.5 uT RMS\n")

    print("=" * 62)
    print("Q1  AMPLITUDE ESTIMATION — median relative error at 2 A")
    print("=" * 62)
    print(f"\n{'rate':>6} | " + " ".join(f"{j:>7.0%}" for j in JITTERS) + "   <- timing jitter")
    print("-" * 46)
    for rate in RATES:
        row = []
        for jit in JITTERS:
            spec = StreamSpec(rate_hz=rate, jitter=jit)
            row.append(f"{amplitude_error(spec, 2.0, int(rate*100+jit*1000)):>6.1%}")
        print(f"{rate:>6.0f} | " + " ".join(f"{c:>7}" for c in row))

    print("\n" + "=" * 62)
    print("Q2  HAND-HELD DETECTION — min detectable current (A)")
    print(f"    tremor {TREMOR_UT} uT @ ~10 Hz, drift {DRIFT_UT} uT over the capture")
    print("=" * 62)
    print(f"\n{'rate':>6} {'jitter':>7} | {'Lomb-Scargle':>13} {'magnitude':>11}")
    print("-" * 42)
    held = {}
    for rate in RATES:
        for jit in [0.05, 0.15, 0.30]:
            spec = StreamSpec(rate_hz=rate, jitter=jit,
                              tremor_ut=TREMOR_UT, drift_ut=DRIFT_UT)
            ls, mag = min_detectable(spec, int(rate * 100 + jit * 1000) + 3)
            held[(rate, jit)] = (ls, mag)
            print(f"{rate:>6.0f} {jit:>7.0%} | {fmt(ls):>13} {fmt(mag):>11}")

    print("\n" + "=" * 62)
    print("FINDINGS")
    print("=" * 62)
    clean = StreamSpec(rate_hz=100.0, jitter=0.15)
    bench_ls, bench_mag = min_detectable(clean, 11)
    print(f"\nBench, 100 Hz / 15% jitter:  LS {fmt(bench_ls)} A   magnitude {fmt(bench_mag)} A")
    h = held[(100.0, 0.15)]
    print(f"Hand-held, same stream:      LS {fmt(h[0])} A   magnitude {fmt(h[1])} A")


if __name__ == "__main__":
    main()
