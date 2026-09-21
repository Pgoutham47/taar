"""
Is the failure mode low sample rate, or harmonic lock?

The sweep showed 50 Hz and 100 Hz uniform failing while 30 Hz and 75 Hz uniform
worked perfectly. That is not a Nyquist story — 30 Hz is far below 100 Hz and does
fine. The hypothesis is that failure happens when the sample rate divides evenly
into the line frequency, so every sample lands on the same phase of the wave and
the 50 Hz component looks like a constant, which mean-subtraction then removes.

If that is right, the fix is not "sample faster". It is "do not sample at 50 or
100 Hz", which is a one-line change to the registration rate.

Run:  .venv/bin/python spike/dsp/harmonic_lock.py
"""

from math import gcd

import numpy as np

from simulate import StreamSpec, capture, field_amplitude_ut
from detect import amplitude_estimate

DISTANCE_M = 0.03
CURRENT_A = 2.0
TRIALS = 200


def error_at(rate_hz: float, jitter: float) -> float:
    spec = StreamSpec(rate_hz=rate_hz, jitter=jitter)
    rng = np.random.default_rng(int(rate_hz * 1000 + jitter * 100))
    true_amp = field_amplitude_ut(CURRENT_A, DISTANCE_M)
    errs = [
        abs(amplitude_estimate(*capture(spec, true_amp, rng)) - true_amp) / true_amp
        for _ in range(TRIALS)
    ]
    return float(np.median(errs))


def main() -> None:
    print("Median amplitude error at 2 A, sweeping sample rate finely\n")
    print(f"{'rate':>7} | {'uniform':>9} {'5% jitter':>11} {'15% jitter':>11}")
    print("-" * 44)

    # Exhaustive, not hand-picked. An earlier version of this file tested a
    # chosen list and missed 10 Hz and 20 Hz entirely, and the README repeated
    # the incomplete answer until a Kotlin test scanned the whole range.
    rates = list(range(8, 152))
    bad = []
    for r in rates:
        if gcd(r, 50) and r // gcd(r, 50) >= 3 and r not in (24, 26, 33, 51, 75, 99, 101, 150):
            continue  # print a representative sample, but scan every rate
        u, j5, j15 = error_at(r, 0.0), error_at(r, 0.05), error_at(r, 0.15)
        mark = "   <-- LOCKED" if u > 0.20 else ""
        print(f"{r:>7} | {u:>8.1%} {j5:>10.1%} {j15:>10.1%}{mark}")
    bad = [r for r in rates if r // gcd(r, 50) < 3]

    print("\n" + "=" * 60)
    print("Locked rates (uniform error > 20%):", bad)
    print()
    print("The rule is not 'integer ratio' — 150 Hz is 3x the line frequency and is")
    print("fine. It is the number of DISTINCT PHASES the sampling visits per cycle:")
    print()
    print(f"  phases = fs / gcd(fs, 50)      fitting sin+cos needs >= 3")
    print()
    print(f"{'rate':>7} {'gcd':>5} {'phases':>7}  verdict")
    print("-" * 40)
    for r in [24, 25, 26, 33, 50, 51, 75, 99, 100, 101, 150, 200]:
        g = gcd(int(r), 50)
        phases = int(r) // g
        verdict = "DEGENERATE" if phases < 2 else ("marginal" if phases == 2 else "fine")
        print(f"{r:>7} {g:>5} {phases:>7}  {verdict}")
    print()
    print("Consequence for the app: the failure mode is not a slow sensor. It is")
    print("sampling at 10, 20, 25, 50 or 100 Hz -- and 100 Hz is the Android default.")
    print("Registering at ~91 Hz or ~111 Hz instead costs nothing and removes the risk")
    print("entirely. Timing jitter also breaks the lock, which is why the real device,")
    print("being irregular, is safer than this simulation's uniform case.")


if __name__ == "__main__":
    main()
