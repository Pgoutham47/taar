"""
Two detectors, matching the two paths in HACKATHON.md section 2.

`lombscargle_power` is the wanted path: it estimates spectral power at exactly
50 Hz from irregularly sampled data, which is why it can work below the uniform
Nyquist rate. `magnitude_index` is the fallback that survives any sample rate at
the cost of saying nothing about amperes.

Both return a statistic that is compared against a threshold learned from baseline
captures — the same structure the app uses, where thresholds come from the
installation's own readings rather than from a constant in the source.
"""

from typing import Tuple

import numpy as np
from scipy.signal import lombscargle

LINE_HZ = 50.0


def _centre(values: np.ndarray) -> np.ndarray:
    """
    Remove the Earth field. It is a large static offset carrying no information
    about current, and leaving it in dominates every spectral estimate.
    """
    return values - values.mean()


def lombscargle_power(t: np.ndarray, values: np.ndarray, freq_hz: float = LINE_HZ) -> float:
    """
    Normalised Lomb-Scargle power at `freq_hz`.

    Scipy's `lombscargle` takes angular frequencies. Normalising by the series
    variance makes the statistic comparable across captures of different amplitude,
    which is what lets one threshold serve every circuit on a board.
    """
    y = _centre(values)
    var = y.var()
    if var <= 0 or t.size < 8:
        return 0.0
    return float(lombscargle(t, y, np.array([2 * np.pi * freq_hz]), normalize=True)[0])


def lombscargle_spectrum(
    t: np.ndarray, values: np.ndarray, lo_hz: float = 1.0, hi_hz: float = 120.0, n: int = 1200
) -> Tuple[np.ndarray, np.ndarray]:
    """Full periodogram, for inspecting whether 50 Hz is the peak or merely present."""
    y = _centre(values)
    freqs = np.linspace(lo_hz, hi_hz, n)
    if y.var() <= 0 or t.size < 8:
        return freqs, np.zeros_like(freqs)
    power = lombscargle(t, y, 2 * np.pi * freqs, normalize=True)
    return freqs, power


def amplitude_estimate(t: np.ndarray, values: np.ndarray, freq_hz: float = LINE_HZ) -> float:
    """
    Least-squares fit of a sinusoid at a known frequency — the amplitude of the
    50 Hz component in microtesla.

    Frequency is known (the grid is 50 Hz), so this is a linear fit against sin and
    cos basis vectors rather than a search. That is both exact and cheap enough to
    run on the phone for every capture.
    """
    y = _centre(values)
    if t.size < 4:
        return 0.0
    w = 2 * np.pi * freq_hz
    design = np.column_stack([np.sin(w * t), np.cos(w * t)])
    coeffs, *_ = np.linalg.lstsq(design, y, rcond=None)
    return float(np.hypot(coeffs[0], coeffs[1]))


def magnitude_index(values: np.ndarray) -> float:
    """
    Fallback statistic: the standard deviation of the centred reading.

    It cannot separate a 50 Hz field from hand tremor or a passing motor, which is
    exactly why a reading built on it is reported as a load index and never as a
    current in amperes.
    """
    return float(_centre(values).std())


def threshold_from_baselines(stats: np.ndarray, false_alarm: float = 0.05) -> float:
    """
    Detection threshold at a chosen false-alarm rate, taken as a quantile of the
    baseline statistics.

    Using the installation's own baselines rather than a fixed constant is the
    property that makes this work across boards with different noise floors.
    """
    return float(np.quantile(stats, 1.0 - false_alarm))


def distinct_phases(rate_hz: int, line_hz: int = 50) -> int:
    """
    How many distinct phases of the line waveform a uniform stream at `rate_hz`
    visits per cycle.

    Fitting a sinusoid of known frequency needs two free parameters, so fewer than
    three distinct phases leaves the fit ill-conditioned and the 50 Hz component
    indistinguishable from a constant — which mean-subtraction then removes.
    """
    from math import gcd

    return rate_hz // gcd(rate_hz, line_hz)


def is_locked(rate_hz: int, line_hz: int = 50) -> bool:
    """True when a uniform stream at this rate cannot see the line frequency."""
    return distinct_phases(rate_hz, line_hz) < 3


def safe_rate(preferred_hz: int, line_hz: int = 50) -> int:
    """
    Nearest rate to `preferred_hz` that is not harmonically locked to the line.

    The Android default of 100 Hz is one of the three locked rates below 150 Hz,
    so this is called once at registration rather than being left to chance.
    """
    if not is_locked(preferred_hz, line_hz):
        return preferred_hz
    for delta in range(1, preferred_hz):
        for candidate in (preferred_hz - delta, preferred_hz + delta):
            if candidate >= 8 and not is_locked(candidate, line_hz):
                return candidate
    return preferred_hz
