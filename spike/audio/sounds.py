"""
Synthetic audio for the arc-detection question.

A caution that governs how much weight these results can carry: unlike the
magnetometer case, where B = mu0*I/(2*pi*r) is textbook and the signal model is
sound, an arc's acoustic signature is empirical. Inventing an arc and then
detecting it proves very little on its own.

Two things are done to keep this from being circular:

  1. The detector in `arcdetect.py` is not tuned to this synthesis. It keys on one
     physical claim -- that a series arc in AC re-ignites twice per cycle, so its
     broadband noise is amplitude-modulated at 100 Hz -- and nothing else about
     how the burst is shaped here.
  2. The confounders are chosen to share the arc's properties one at a time. A
     ballast hum is also 100 Hz periodic. A rustle is also broadband and transient.
     Speech is also amplitude-modulated. A detector that survives all three is
     keying on the conjunction rather than on any single artefact of the synthesis.

What this cannot establish is whether a real arc looks like `arc()`. That needs a
recording, and §"What to do on the day" in the README says how to get one safely.
"""

from dataclasses import dataclass

import numpy as np
from scipy.signal import butter, sosfilt

SR = 44_100
LINE_HZ = 50.0
ARC_REP_HZ = 2 * LINE_HZ  # re-ignition twice per cycle


def _pink(n: int, rng: np.random.Generator) -> np.ndarray:
    """Pink-ish noise via a one-pole filter on white. Good enough for a room floor."""
    white = rng.normal(0, 1, n)
    sos = butter(1, 500 / (SR / 2), btype="low", output="sos")
    low = sosfilt(sos, white)
    return 0.7 * low + 0.3 * white


def room(n: int, rng: np.random.Generator, level: float = 0.02) -> np.ndarray:
    """Ambient floor: HVAC rumble plus a broadband hiss."""
    rumble = sosfilt(butter(2, 120 / (SR / 2), btype="low", output="sos"),
                     rng.normal(0, 1, n))
    return level * (2.5 * rumble + 0.6 * _pink(n, rng))


def arc(n: int, rng: np.random.Generator, level: float = 1.0) -> np.ndarray:
    """
    A series arc: short broadband bursts locked to 100 Hz, with the irregularity
    a real re-ignition has -- jittered timing, varying amplitude, occasional
    skipped half-cycles.
    """
    t = np.arange(n) / SR
    env = np.zeros(n)
    period = SR / ARC_REP_HZ

    k = 0
    while True:
        centre = k * period + rng.normal(0, period * 0.06)
        k += 1
        if centre >= n:
            break
        if rng.random() < 0.12:  # a half-cycle that does not strike
            continue
        width = int(SR * rng.uniform(0.00035, 0.0011))
        start = max(0, int(centre))
        stop = min(n, start + width)
        if stop <= start:
            continue
        burst = np.exp(-np.linspace(0, 4, stop - start))
        env[start:stop] += burst * rng.uniform(0.5, 1.0)

    hiss = sosfilt(butter(4, 3000 / (SR / 2), btype="high", output="sos"),
                   rng.normal(0, 1, n))
    buzz = 0.15 * np.sin(2 * np.pi * 100 * t)  # mechanical hum at 2x line
    return level * (env * hiss + buzz)


def ballast(n: int, rng: np.random.Generator, level: float = 1.0) -> np.ndarray:
    """
    The hard confounder: a magnetic ballast or transformer hum. Also periodic at
    100 Hz, also present in an electrical room. Tonal rather than broadband, which
    is the only thing separating it from an arc.
    """
    t = np.arange(n) / SR
    tone = sum(a * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28))
               for f, a in [(100, 1.0), (200, 0.45), (300, 0.22), (400, 0.1)])
    return level * (0.35 * tone + 0.04 * _pink(n, rng))


def speech(n: int, rng: np.random.Generator, level: float = 1.0) -> np.ndarray:
    """Amplitude-modulated broadband, but at syllable rates of 2-6 Hz."""
    t = np.arange(n) / SR
    env = np.clip(0.5 + 0.5 * np.sin(2 * np.pi * rng.uniform(2.5, 5.5) * t
                                     + rng.uniform(0, 6.28)), 0, None)
    band = sosfilt(butter(4, [300 / (SR / 2), 4000 / (SR / 2)], btype="band", output="sos"),
                   rng.normal(0, 1, n))
    return level * 0.5 * env * band


def rustle(n: int, rng: np.random.Generator, level: float = 1.0) -> np.ndarray:
    """Keys, paper, a sleeve brushing the phone: broadband transients, aperiodic."""
    env = np.zeros(n)
    for _ in range(rng.integers(6, 18)):
        start = rng.integers(0, max(1, n - 2000))
        width = int(SR * rng.uniform(0.002, 0.03))
        stop = min(n, start + width)
        env[start:stop] += np.exp(-np.linspace(0, 3, stop - start)) * rng.uniform(0.4, 1.0)
    hiss = sosfilt(butter(4, 2000 / (SR / 2), btype="high", output="sos"),
                   rng.normal(0, 1, n))
    return level * 0.8 * env * hiss


def motor(n: int, rng: np.random.Generator, level: float = 1.0) -> np.ndarray:
    """A fan or pump: tonal at its rotation rate and harmonics, plus flow noise."""
    t = np.arange(n) / SR
    f0 = rng.uniform(23, 47)
    tone = sum((1.0 / (i + 1)) * np.sin(2 * np.pi * f0 * (i + 1) * t + rng.uniform(0, 6.28))
               for i in range(6))
    return level * (0.25 * tone + 0.08 * _pink(n, rng))


@dataclass
class Clip:
    name: str
    audio: np.ndarray


def make(kind: str, seconds: float, rng: np.random.Generator,
         level: float = 1.0, room_level: float = 0.02) -> np.ndarray:
    """A clip of `kind` mixed onto the ambient room floor."""
    n = int(seconds * SR)
    base = room(n, rng, room_level)
    if kind == "quiet":
        return base
    source = {"arc": arc, "ballast": ballast, "speech": speech,
              "rustle": rustle, "motor": motor}[kind]
    return base + source(n, rng, level)
