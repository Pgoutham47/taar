"""
Synthetic Android magnetometer stream.

The question this spike exists to answer: at the sample rates and timing jitter an
Android magnetometer actually delivers, can a 50 Hz component from a current-carrying
conductor be recovered at all?

The model:

    reading(t) = earth + A * sin(2*pi*50*t + phase) + noise

`earth` is a large static offset (~45 uT in southern India) that swamps the signal
and is removed by subtracting the mean. `A` is the field amplitude from the wire.
`noise` is sensor noise, which for phone magnetometers sits around 0.3-1.0 uT RMS.

Timestamps are irregular on purpose. SensorManager delivers samples on a best-effort
basis and stamps each one; that irregularity is not a nuisance here, it is the thing
that makes 50 Hz recoverable below the uniform-sampling Nyquist limit.
"""

from dataclasses import dataclass
from typing import Optional, Tuple

import numpy as np

MU0_OVER_2PI = 2e-7  # T*m/A
LINE_HZ = 50.0
EARTH_UT = 45.0


def field_amplitude_ut(current_rms_a: float, distance_m: float) -> float:
    """
    Peak field amplitude in microtesla at `distance_m` from a conductor carrying
    `current_rms_a` RMS amperes.

        B = mu0 * I / (2 * pi * r)

    Times sqrt(2) to convert RMS current to the peak of the alternating field.
    """
    tesla = MU0_OVER_2PI * np.sqrt(2.0) * current_rms_a / distance_m
    return tesla * 1e6


def current_for_amplitude(amplitude_ut: float, distance_m: float) -> float:
    """Inverse of `field_amplitude_ut` — the RMS current that produces this amplitude."""
    tesla = amplitude_ut * 1e-6
    return tesla * distance_m / (MU0_OVER_2PI * np.sqrt(2.0))


@dataclass
class StreamSpec:
    rate_hz: float
    """Nominal sample rate the sensor is registered at."""

    jitter: float
    """
    Timing irregularity as a fraction of the nominal interval. 0.0 is perfectly
    uniform; 0.3 means each interval varies by +/-30%. Real Android streams are
    rarely below 0.05 and often well above it.
    """

    duration_s: float = 3.0
    """Capture length. 3 s matches the app's capture window."""

    noise_ut: float = 0.5
    """Sensor noise, RMS microtesla."""

    quantisation_ut: float = 0.15
    """Reported resolution of a typical phone magnetometer."""

    drift_ut: float = 0.0
    """Slow drift over the capture, e.g. from the hand moving. Peak-to-peak."""

    tremor_ut: float = 0.0
    """
    Hand tremor amplitude. Physiological tremor sits around 8-12 Hz and, held at
    arm's length in the Earth's 45 uT field, small angular wobble produces field
    variation far larger than the signal being looked for. This is the dominant
    real-world interference and the reason a magnitude statistic is not enough.
    """

    tremor_hz: float = 10.0


def timestamps(spec: StreamSpec, rng: np.random.Generator) -> np.ndarray:
    """
    Irregular sample times. Intervals are drawn around the nominal one and the
    result is cumulative, which is how a real stream behaves — jitter accumulates
    into phase rather than averaging away.
    """
    n = max(2, int(spec.duration_s * spec.rate_hz))
    nominal = 1.0 / spec.rate_hz

    if spec.jitter <= 0:
        return np.arange(n) * nominal

    intervals = nominal * (1.0 + rng.uniform(-spec.jitter, spec.jitter, size=n))
    intervals = np.clip(intervals, nominal * 0.1, None)
    return np.cumsum(intervals) - intervals[0]


def capture(
    spec: StreamSpec,
    amplitude_ut: float,
    rng: np.random.Generator,
    phase: Optional[float] = None,
) -> Tuple[np.ndarray, np.ndarray]:
    """
    One synthetic capture. Returns (t, values_ut).

    `amplitude_ut` of 0.0 produces a baseline capture with no conductor present —
    used for the null distribution when setting a detection threshold.
    """
    t = timestamps(spec, rng)
    if phase is None:
        phase = rng.uniform(0, 2 * np.pi)

    signal = amplitude_ut * np.sin(2 * np.pi * LINE_HZ * t + phase)
    noise = rng.normal(0.0, spec.noise_ut, size=t.size)

    drift = 0.0
    if spec.drift_ut:
        drift = spec.drift_ut * (t / max(t[-1], 1e-9) - 0.5)

    tremor = 0.0
    if spec.tremor_ut:
        # Not a pure tone: real tremor wanders in frequency and amplitude.
        wander = spec.tremor_hz * (1.0 + 0.25 * np.sin(2 * np.pi * 0.7 * t))
        tremor = spec.tremor_ut * np.sin(2 * np.pi * wander * t + rng.uniform(0, 2 * np.pi))

    values = EARTH_UT + signal + noise + drift + tremor

    if spec.quantisation_ut > 0:
        values = np.round(values / spec.quantisation_ut) * spec.quantisation_ut

    return t, values
