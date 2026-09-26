"""
Features for the arc model, written to be reproduced exactly in Kotlin.

A model trained on one set of numbers and fed slightly different ones on the phone
fails without saying so. So nothing here is new DSP: every step is one the app
already runs, with the same coefficients and the same sizes.

    envelope   Biquad 4-16 kHz -> |x| -> Biquad 500 Hz -> keep every 22nd sample
               (ArcDetector.modulationIndex, verbatim)
    ENV        its power spectrum, Hann, zero-padded to a power of two (Fft.kt),
               summed into 5 Hz bins over 20-400 Hz, as fractions of that band
    SHAPE      the raw audio's mean power spectrum over 1024-sample Hann frames,
               summed into 12 log-spaced bands over 100 Hz-20 kHz, as fractions
    MI         the rule's own statistic: power within 3 Hz of 100 Hz over 20-400 Hz

Every feature is a fraction, so none of them carries loudness -- the speaker's
volume and the phone's distance cannot become the thing the model learns. All are
log10(fraction + 1e-6).

One call is one 3 s capture at 44.1 kHz, which is what the app records.
"""

import csv
from pathlib import Path

import numpy as np
from scipy.signal import sosfilt

SR = 44_100
CAPTURE_S = 3.0

ENVELOPE_RATE_HZ = 2_000.0
ENV_LO_HZ, ENV_HI_HZ, ENV_BIN_HZ = 20.0, 400.0, 5.0
ARC_REP_HZ, PEAK_HALF_WIDTH_HZ = 100.0, 3.0

SHAPE_FRAME = 1024
SHAPE_EDGES_HZ = np.geomspace(100.0, 20_000.0, 13)  # 12 bands

EPS = 1e-6

_GOLDEN = Path(__file__).resolve().parents[1] / "android" / "golden" / "filter_sos.csv"


def _sos(name):
    with open(_GOLDEN) as f:
        rows = [r for r in csv.DictReader(f) if r["filter"] == name]
    rows.sort(key=lambda r: int(r["section"]))
    return np.array([[float(r[k]) for k in ("b0", "b1", "b2", "a0", "a1", "a2")] for r in rows])


HF_SOS = _sos("hf_bandpass_4k_16k")
ENV_SOS = _sos("env_lowpass_500")

N_ENV = int(round((ENV_HI_HZ - ENV_LO_HZ) / ENV_BIN_HZ))  # 76
N_SHAPE = len(SHAPE_EDGES_HZ) - 1                         # 12
N_FEATURES = N_ENV + N_SHAPE + 1                          # 89


def _next_pow2(n):
    return 1 << (n - 1).bit_length()


def _hann(n):
    # Fft.hann: 0.5 - 0.5 cos(2 pi i / (n - 1)), the symmetric window.
    return np.hanning(n)


def _power(x):
    n = _next_pow2(len(x))
    spec = np.fft.rfft(x, n)
    return spec.real ** 2 + spec.imag ** 2, n


def envelope(audio, sr=SR):
    smoothed = sosfilt(ENV_SOS, np.abs(sosfilt(HF_SOS, audio)))
    step = max(1, int(sr / ENVELOPE_RATE_HZ))
    return smoothed[::step][: len(smoothed) // step], sr / step


def features(audio, sr=SR):
    """89 floats for one capture. Order: ENV (76), SHAPE (12), MI (1)."""
    if sr != SR:
        raise ValueError(f"expected {SR} Hz, got {sr}")
    audio = np.asarray(audio, dtype=np.float64)

    env, env_rate = envelope(audio, sr)
    x = (env - env.mean()) * _hann(len(env))
    power, n = _power(x)
    freqs = np.arange(len(power)) * env_rate / n

    in_band = (freqs >= ENV_LO_HZ) & (freqs <= ENV_HI_HZ)
    total = power[in_band].sum()
    env_bins = np.zeros(N_ENV)
    for b in range(N_ENV):
        lo = ENV_LO_HZ + b * ENV_BIN_HZ
        env_bins[b] = power[(freqs >= lo) & (freqs < lo + ENV_BIN_HZ)].sum()
    peak = power[in_band & (np.abs(freqs - ARC_REP_HZ) <= PEAK_HALF_WIDTH_HZ)].sum()
    env_frac = env_bins / total if total > 0 else env_bins
    mi = peak / total if total > 0 else 0.0

    frames = len(audio) // SHAPE_FRAME
    window = _hann(SHAPE_FRAME)
    mean_power = np.zeros(SHAPE_FRAME // 2 + 1)
    for i in range(frames):
        p, _ = _power(audio[i * SHAPE_FRAME:(i + 1) * SHAPE_FRAME] * window)
        mean_power += p
    mean_power /= max(frames, 1)
    sfreqs = np.arange(len(mean_power)) * sr / SHAPE_FRAME
    shape = np.array([mean_power[(sfreqs >= lo) & (sfreqs < hi)].sum()
                      for lo, hi in zip(SHAPE_EDGES_HZ[:-1], SHAPE_EDGES_HZ[1:])])
    shape_total = shape.sum()
    shape_frac = shape / shape_total if shape_total > 0 else shape

    return np.log10(np.concatenate([env_frac, shape_frac, [mi]]) + EPS).astype(np.float32)
