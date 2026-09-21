"""
Arc detector.

One physical claim drives the whole design: a series arc in an AC circuit
re-ignites twice per mains cycle, so its broadband noise is amplitude-modulated
at 100 Hz. Everything else follows.

    band-limit to the high frequencies where arc noise lives
      -> take the envelope
        -> ask how much of the envelope's power sits at exactly 100 Hz

That last step is the same move the magnetometer side makes: do not measure
energy, measure energy at the line-locked frequency. A ballast hums at 100 Hz but
is tonal, not broadband, so it never reaches the high band. A rustle is broadband
but aperiodic, so its envelope has no 100 Hz line. Speech is modulated but at
syllable rates. The conjunction is what identifies an arc.

`hf_energy` is the naive alternative kept for comparison -- it asks only whether
there is more high-frequency energy than the room's baseline, which is what a
detector looks like before you think about it. It is given its strongest form
here (absolute band energy, not a ratio) so the comparison is not rigged: a ratio
is fragile to whatever else occupies the denominator and fails for reasons that
have nothing to do with the idea being tested.
"""

from typing import Tuple

import numpy as np
from scipy.signal import butter, sosfilt, hilbert

SR = 44_100
ARC_REP_HZ = 100.0

# Arc noise is strongest well above where speech and machinery live.
HF_LO, HF_HI = 4_000.0, 16_000.0

# Envelope band to search. 20 Hz excludes drift; 400 Hz spans the first harmonics.
ENV_LO, ENV_HI = 20.0, 400.0


def _bandpass(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    nyq = SR / 2
    hi = min(hi, nyq * 0.99)
    sos = butter(4, [lo / nyq, hi / nyq], btype="band", output="sos")
    return sosfilt(sos, x)


def envelope(x: np.ndarray, decimate_to: int = 2_000) -> Tuple[np.ndarray, float]:
    """
    Amplitude envelope of the high band, decimated to a rate that still resolves
    a few hundred Hz. Decimation is what makes this cheap enough to run on a phone
    for every capture.
    """
    band = _bandpass(x, HF_LO, HF_HI)
    env = np.abs(hilbert(band))
    step = max(1, int(SR / decimate_to))
    env = env[::step]
    return env - env.mean(), SR / step


def modulation_index(x: np.ndarray, freq_hz: float = ARC_REP_HZ) -> float:
    """
    Fraction of the envelope's power in a narrow band at `freq_hz`, relative to
    the whole envelope band.

    Normalising by total envelope power is what makes the statistic independent of
    how loud the arc is, so one threshold serves a quiet cupboard and a noisy plant
    room alike.
    """
    env, env_sr = envelope(x)
    if env.size < 64 or env.std() == 0:
        return 0.0

    spec = np.abs(np.fft.rfft(env * np.hanning(env.size))) ** 2
    freqs = np.fft.rfftfreq(env.size, 1.0 / env_sr)

    band = (freqs >= ENV_LO) & (freqs <= ENV_HI)
    total = spec[band].sum()
    if total <= 0:
        return 0.0

    # +/- 3 Hz around the line-locked frequency, to tolerate grid drift.
    peak = (freqs >= freq_hz - 3) & (freqs <= freq_hz + 3)
    return float(spec[peak].sum() / total)


def hf_energy(x: np.ndarray) -> float:
    """
    Naive comparison detector: RMS energy in the high band, in absolute terms.

    Compared against a threshold learned from the room's own quiet baselines, this
    is a reasonable first attempt and not a straw man -- it is what most people
    would write first.
    """
    band = _bandpass(x, HF_LO, HF_HI)
    return float(np.sqrt(np.mean(band.astype(np.float64) ** 2)))


def threshold_from_baselines(stats: np.ndarray, false_alarm: float = 0.05) -> float:
    """Threshold at a chosen false-alarm rate, from this room's own baselines."""
    return float(np.quantile(stats, 1.0 - false_alarm))
