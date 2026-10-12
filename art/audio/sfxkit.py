"""sfxkit: a small numpy/scipy DSP toolkit for Guildmark's synthesised sound effects.

Everything here is procedural: noise, filters, envelopes, modal (inharmonic) resonators,
a glottal-pulse "creature voice" source, synthetic room impulse responses and a few
mastering helpers. All randomness goes through a seeded numpy Generator so every file
regenerates bit-identically.
"""
import os
import shutil
import subprocess
import tempfile

import numpy as np
from scipy import signal
from scipy.ndimage import maximum_filter1d, minimum_filter1d, uniform_filter1d
from scipy.io import wavfile

SR = 44100


# ---------------------------------------------------------------- basics
def ns(t):
    """seconds -> samples"""
    return max(0, int(round(t * SR)))


def tvec(dur):
    return np.arange(ns(dur)) / SR


def db(x):
    return 10 ** (x / 20.0)


def to_db(x):
    return 20 * np.log10(np.maximum(1e-12, x))


def rng_for(*key):
    """deterministic generator from any hashable key (name, variant...)"""
    h = 0
    for k in key:
        for ch in str(k):
            h = (h * 131 + ord(ch)) % (2 ** 32 - 5)
    return np.random.default_rng(h)


def fit(x, n):
    """pad with zeros or cut to n samples"""
    if len(x) >= n:
        return x[:n]
    return np.concatenate([x, np.zeros(n - len(x))])


class Mix:
    """accumulates layers at time offsets"""

    def __init__(self, dur=0.0):
        self.buf = np.zeros(ns(dur))

    def add(self, x, at=0.0, gain=1.0, end_fade=0.006):
        """mix x in at time `at`; the layer's last end_fade seconds are faded so a layer
        that is cut before it has fully decayed can never click"""
        x = np.asarray(x, dtype=np.float64)
        if end_fade and len(x) > 4:
            k = min(len(x) // 2, ns(end_fade))
            if k > 0:
                x = x.copy()
                x[-k:] *= np.cos(np.linspace(0, np.pi / 2, k)) ** 2
        i = ns(at)
        end = i + len(x)
        if end > len(self.buf):
            self.buf = np.concatenate([self.buf, np.zeros(end - len(self.buf))])
        self.buf[i:end] += gain * x
        return self

    def out(self):
        return self.buf.copy()


def peak_norm(x, peak=1.0):
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 0 else x


def rms(x):
    return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


# ---------------------------------------------------------------- noise
def white(dur, rng):
    return rng.standard_normal(ns(dur))


def colored(dur, rng, slope_db_per_oct=-3.0, fmin=20.0):
    """noise with a spectral slope (pink = -3, brown = -6, blue = +3 dB/oct)"""
    n = ns(dur)
    if n == 0:
        return np.zeros(0)
    m = int(2 ** np.ceil(np.log2(max(n, 16))))
    spec = np.fft.rfft(rng.standard_normal(m))
    f = np.fft.rfftfreq(m, 1 / SR)
    f[0] = f[1]
    spec *= (np.maximum(f, fmin) / 1000.0) ** (slope_db_per_oct / 6.0206)
    y = np.fft.irfft(spec, m)[:n]
    return y / (np.std(y) + 1e-12)


def pink(dur, rng):
    return colored(dur, rng, -3.0)


def brown(dur, rng):
    return colored(dur, rng, -6.0)


def smooth_random(dur, rng, rate_hz, lo=0.0, hi=1.0):
    """slowly wandering random curve (band-limited noise) mapped to [lo, hi]"""
    n = ns(dur)
    k = max(4, int(dur * rate_hz) + 4)
    pts = rng.uniform(0, 1, k)
    xs = np.linspace(0, n, k)
    y = np.interp(np.arange(n), xs, pts)
    y = uniform_filter1d(y, size=max(1, int(SR / rate_hz / 2)))
    y = (y - y.min()) / (np.ptp(y) + 1e-12)
    return lo + (hi - lo) * y


# ---------------------------------------------------------------- envelopes
def env_points(points, dur, shape=1.0):
    """piecewise envelope through (t, v) points; shape>1 makes segments ease-in"""
    n = ns(dur)
    t = np.arange(n) / SR
    pts = sorted(points)
    out = np.zeros(n)
    for (t0, v0), (t1, v1) in zip(pts[:-1], pts[1:]):
        m = (t >= t0) & (t < t1)
        if t1 > t0:
            u = ((t[m] - t0) / (t1 - t0)) ** shape
            out[m] = v0 + (v1 - v0) * u
    out[t >= pts[-1][0]] = pts[-1][1]
    out[t < pts[0][0]] = pts[0][1]
    return out


def env_exp(dur, decay, attack=0.001, hold=0.0):
    """fast attack, optional hold, exponential decay (decay = time constant in s)"""
    t = tvec(dur)
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    a = np.sin(a * np.pi / 2) ** 2
    d = np.exp(-np.maximum(0, t - attack - hold) / max(decay, 1e-6))
    return a * d


def env_t60(dur, t60, attack=0.001):
    return env_exp(dur, t60 / 6.9078, attack)


def bell(dur, peak, rise=2.0, fall=2.0):
    """asymmetric bell 0..1..0 peaking at fraction `peak` of dur"""
    u = np.linspace(0, 1, ns(dur), endpoint=False)
    a = np.where(u < peak, np.sin(np.clip(u / peak, 0, 1) * np.pi / 2) ** rise,
                 np.cos(np.clip((u - peak) / (1 - peak), 0, 1) * np.pi / 2) ** fall)
    return a


def fade(x, fin=0.0, fout=0.0):
    x = x.copy()
    a, b = ns(fin), ns(fout)
    if a > 0:
        x[:a] *= np.sin(np.linspace(0, np.pi / 2, a)) ** 2
    if b > 0:
        b = min(b, len(x))
        x[-b:] *= np.cos(np.linspace(0, np.pi / 2, b)) ** 2
    return x


# ---------------------------------------------------------------- filters
def _sos(kind, f, order=2):
    nyq = SR / 2
    if kind in ("bp", "bandpass", "bs"):
        lo, hi = f
        lo = max(5.0, lo)
        hi = min(hi, nyq * 0.98)
        return signal.butter(order, [lo / nyq, hi / nyq], btype="bandpass" if kind != "bs" else "bandstop", output="sos")
    f = min(max(5.0, f), nyq * 0.98)
    return signal.butter(order, f / nyq, btype={"lp": "lowpass", "hp": "highpass"}[kind], output="sos")


def lp(x, f, order=2):
    return signal.sosfilt(_sos("lp", f, order), x)


def hp(x, f, order=2):
    return signal.sosfilt(_sos("hp", f, order), x)


def bp(x, lo, hi, order=2):
    return signal.sosfilt(_sos("bp", (lo, hi), order), x)


def _rbj(kind, f, q, gain_db=0.0):
    f = min(max(10.0, f), SR * 0.49)
    w = 2 * np.pi * f / SR
    cw, sw = np.cos(w), np.sin(w)
    alpha = sw / (2 * q)
    A = 10 ** (gain_db / 40)
    if kind == "lp":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
        a = [1 + alpha, -2 * cw, 1 - alpha]
    elif kind == "hp":
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
        a = [1 + alpha, -2 * cw, 1 - alpha]
    elif kind == "bp":  # constant 0 dB peak gain
        b = [alpha, 0, -alpha]
        a = [1 + alpha, -2 * cw, 1 - alpha]
    elif kind == "peak":
        b = [1 + alpha * A, -2 * cw, 1 - alpha * A]
        a = [1 + alpha / A, -2 * cw, 1 - alpha / A]
    elif kind == "lowshelf":
        sq = 2 * np.sqrt(A) * alpha
        b = [A * ((A + 1) - (A - 1) * cw + sq), 2 * A * ((A - 1) - (A + 1) * cw), A * ((A + 1) - (A - 1) * cw - sq)]
        a = [(A + 1) + (A - 1) * cw + sq, -2 * ((A - 1) + (A + 1) * cw), (A + 1) + (A - 1) * cw - sq]
    elif kind == "highshelf":
        sq = 2 * np.sqrt(A) * alpha
        b = [A * ((A + 1) + (A - 1) * cw + sq), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - sq)]
        a = [(A + 1) - (A - 1) * cw + sq, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - sq]
    else:
        raise ValueError(kind)
    b = np.array(b) / a[0]
    a = np.array(a) / a[0]
    return b, a


def eq(x, kind, f, q=0.707, gain_db=0.0):
    b, a = _rbj(kind, f, q, gain_db)
    return signal.lfilter(b, a, x)


def tvf(x, kind, freq, q=0.707, block=32):
    """time-varying RBJ biquad. freq is a scalar or per-sample array (Hz); q scalar or array."""
    x = np.asarray(x, dtype=np.float64)
    n = len(x)
    freq = np.broadcast_to(np.asarray(freq, dtype=np.float64), (n,))
    qa = np.broadcast_to(np.asarray(q, dtype=np.float64), (n,))
    y = np.empty(n)
    zi = np.zeros(2)
    for s in range(0, n, block):
        e = min(n, s + block)
        m = (s + e) // 2
        b, a = _rbj(kind, freq[m], qa[m])
        y[s:e], zi = signal.lfilter(b, a, x[s:e], zi=zi)
    return y


def resonators(x, freqs, t60s, gains=None):
    """bank of 2-pole resonators (modal synthesis driven by an excitation x)"""
    out = np.zeros(len(x))
    if gains is None:
        gains = np.ones(len(freqs))
    for f, t60, g in zip(freqs, t60s, gains):
        if f >= SR * 0.47:
            continue
        r = 10 ** (-3.0 / (max(t60, 1e-3) * SR))
        w = 2 * np.pi * f / SR
        a = [1.0, -2 * r * np.cos(w), r * r]
        b = [(1 - r * r) * np.sin(w)]
        out += g * signal.lfilter(b, a, x)
    return out


def comb(x, delay_s, feedback=0.5, mix=0.5):
    d = max(1, ns(delay_s))
    a = np.zeros(d + 1)
    a[0] = 1.0
    a[d] = -feedback
    y = signal.lfilter([1.0], a, x)
    return (1 - mix) * x + mix * y * (1 - abs(feedback))


# ---------------------------------------------------------------- oscillators
def phase_from_freq(freq, n=None):
    f = np.broadcast_to(np.asarray(freq, dtype=np.float64), (n,) if n else np.shape(freq))
    return 2 * np.pi * np.cumsum(f) / SR


def sine_sweep(dur, f0, f1, curve=3.0, phase=0.0):
    """sine gliding exponentially-ish from f0 to f1 (curve>1 = fast early drop)"""
    u = np.linspace(0, 1, ns(dur), endpoint=False)
    f = f1 + (f0 - f1) * np.exp(-curve * u) if curve > 0 else f0 + (f1 - f0) * u
    if curve > 0:
        # normalise so the sweep actually reaches ~f1 at the end
        f = f1 + (f0 - f1) * (np.exp(-curve * u) - np.exp(-curve)) / (1 - np.exp(-curve))
    return np.sin(phase + phase_from_freq(f))


def modal(dur, freqs, t60s, amps, rng=None, jitter_phase=True, glide=0.0, attack=0.0005):
    """sum of exponentially decaying sinusoids (direct modal synthesis).
    glide: fractional downward pitch drift over the first 100 ms (impact pitch drop)."""
    t = tvec(dur)
    out = np.zeros(len(t))
    gl = 1.0 + glide * np.exp(-t / 0.03) if glide else 1.0
    for i, (f, t60, a) in enumerate(zip(freqs, t60s, amps)):
        if f >= SR * 0.47:
            continue
        ph = rng.uniform(0, 2 * np.pi) if (rng is not None and jitter_phase) else 0.0
        if glide:
            sig = np.sin(ph + phase_from_freq(f * gl, len(t)))
        else:
            sig = np.sin(ph + 2 * np.pi * f * t)
        out += a * sig * np.exp(-t * 6.9078 / max(t60, 1e-3))
    if attack > 0:
        out *= np.clip(t / attack, 0, 1)
    return out


def metal_partials(f0, kind="plate", n=14, rng=None, spread=0.0):
    """inharmonic partial ratios for metal objects"""
    if kind == "bar":  # free-free bar
        r = [1.0, 2.756, 5.404, 8.933, 13.344, 18.64, 24.81]
    elif kind == "blade":  # thin blade: dense, slightly stretched
        r = [1.0, 1.594, 2.136, 2.296, 2.653, 3.156, 3.501, 3.928, 4.477, 5.134, 5.672, 6.231, 7.012, 7.880]
    elif kind == "bell":
        r = [0.5, 1.0, 1.183, 1.506, 2.0, 2.514, 2.662, 3.011, 4.166, 5.433, 6.796, 8.215]
    elif kind == "plate":  # roughly a clamped plate / armour plate
        r = [1.0, 1.403, 1.892, 2.152, 2.565, 2.936, 3.398, 3.718, 4.221, 4.746, 5.287, 5.912, 6.618, 7.403]
    elif kind == "shield":
        r = [1.0, 1.594, 2.136, 2.653, 2.918, 3.5, 4.06]
    else:
        raise ValueError(kind)
    r = np.array(r[:n], dtype=float)
    if rng is not None and spread:
        r *= 1 + rng.uniform(-spread, spread, len(r))
    return f0 * r


def glottal(dur, f0, rng, jitter=0.02, shimmer=0.1, subharm=0.0, open_q=0.6, breath=0.15, fry=0.0):
    """pulse-train vocal source with per-period jitter, shimmer, optional period doubling
    (subharm, growl) and vocal fry (fry = probability of a skipped/irregular period).
    f0 may be a scalar or per-sample curve. Returns a source with ~-12 dB/oct tilt."""
    n = ns(dur)
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,))
    out = np.zeros(n)
    pos = 0.0
    k = 0
    while True:
        i = int(pos)
        if i >= n:
            break
        f = f0[i] * (1 + jitter * rng.standard_normal())
        if fry and rng.random() < fry:
            f *= rng.uniform(0.35, 0.6)
        period = SR / max(f, 20.0)
        amp = 1 + shimmer * rng.standard_normal()
        if subharm and k % 2 == 1:
            amp *= (1 - subharm)
        # LF-ish pulse: smooth opening (raised cosine) and abrupt closure
        L = max(4, int(period * open_q))
        tt = np.arange(L) / L
        pulse = (0.5 - 0.5 * np.cos(np.pi * tt * 1.3)) * (1 - tt ** 6)
        d = np.diff(np.concatenate([[0], pulse]))  # flow derivative: strong negative closure spike
        e = min(n, i + L)
        out[i:e] += amp * d[: e - i]
        pos += period
        k += 1
    out = signal.lfilter([1.0], [1.0, -0.97], out)  # integrate partially -> -6 dB/oct tilt overall
    out = hp(out, 40)
    out /= np.max(np.abs(out)) + 1e-12
    if breath:
        nz = hp(white(dur, rng), 400) * 0.35
        # pitch-synchronous-ish aspiration: modulate by the source envelope
        envs = uniform_filter1d(np.abs(out), size=max(1, int(SR / 300)))
        out = out + breath * nz * (0.4 + envs / (envs.max() + 1e-12))
    return out


VOWELS = {  # F1..F4 (Hz) and bandwidths, adult male-ish, used darkened for creatures
    "a": ([730, 1090, 2440, 3400], [90, 120, 260, 420]),
    "o": ([570, 840, 2410, 3300], [80, 110, 260, 420]),
    "u": ([300, 870, 2240, 3200], [70, 110, 250, 420]),
    "e": ([530, 1840, 2480, 3500], [80, 130, 260, 420]),
    "uh": ([520, 1190, 2390, 3300], [90, 130, 260, 420]),
    "i": ([270, 2290, 3010, 3600], [60, 130, 260, 420]),
}


def formants(x, vowel_a, vowel_b=None, scale=1.0, curve=None, gains=(1.0, 0.6, 0.35, 0.2)):
    """parallel formant filter bank; morphs vowel_a -> vowel_b along curve (0..1 per sample)"""
    n = len(x)
    fa, ba = VOWELS[vowel_a]
    fb, bb = VOWELS[vowel_b or vowel_a]
    if curve is None:
        curve = np.linspace(0, 1, n)
    out = np.zeros(n)
    for k in range(4):
        f = (fa[k] + (fb[k] - fa[k]) * curve) * scale
        bw = ba[k] + (bb[k] - ba[k]) * curve
        q = f / np.maximum(bw, 20)
        out += gains[k] * tvf(x, "bp", f, q)
    return out


# ---------------------------------------------------------------- time / pitch
def varispeed(x, rate):
    """resample with a time-varying playback rate (scalar or per-output-sample array).
    rate>1 = faster/higher. Output length follows the rate curve until x runs out."""
    if np.isscalar(rate):
        n_out = int(len(x) / rate)
        pos = np.arange(n_out) * rate
    else:
        rate = np.asarray(rate, dtype=float)
        pos = np.concatenate([[0], np.cumsum(rate)[:-1]])
        pos = pos[pos < len(x) - 1]
    return np.interp(pos, np.arange(len(x)), x)


# ---------------------------------------------------------------- non-linear
def drive(x, amount=2.0, asym=0.0):
    """tanh saturation, amount = pre-gain. asym adds even harmonics."""
    if amount <= 0:
        return x
    y = np.tanh(amount * (x + asym)) - np.tanh(amount * asym)
    return y / (np.tanh(amount) + 1e-12)


def crush(x, bits=8):
    q = 2 ** (bits - 1)
    return np.round(x * q) / q


# ---------------------------------------------------------------- dynamics
def limiter(x, ceiling=0.9, lookahead=0.002, release=0.06):
    """lookahead peak limiter (min-filtered gain + smoothing), vectorised"""
    a = np.abs(x)
    w = max(1, ns(lookahead))
    target = np.minimum(1.0, ceiling / np.maximum(maximum_filter1d(a, size=2 * w + 1), 1e-12))
    g = minimum_filter1d(target, size=2 * w + 1)
    # release smoothing: one-pole on the gain, but never above the instantaneous target
    r = np.exp(-1.0 / max(1, ns(release)))
    g2 = signal.lfilter([1 - r], [1, -r], g - 1.0) + 1.0
    g = np.minimum(g, g2)
    g = uniform_filter1d(g, size=w)
    return x * g


def compress(x, thresh_db=-18.0, ratio=3.0, attack=0.003, release=0.08, makeup=True):
    """feed-forward RMS-ish compressor (python loop envelope; fine for short SFX)"""
    a = np.abs(x).tolist()
    at = np.exp(-1.0 / max(1, ns(attack)))
    rl = np.exp(-1.0 / max(1, ns(release)))
    env = np.empty(len(a))
    e = 0.0
    for i, v in enumerate(a):
        c = at if v > e else rl
        e = c * e + (1 - c) * v
        env[i] = e
    lev = to_db(env)
    over = np.maximum(0, lev - thresh_db)
    gain_db = -over * (1 - 1 / ratio)
    y = x * db(gain_db)
    if makeup:
        y *= np.max(np.abs(x)) / (np.max(np.abs(y)) + 1e-12)
    return y


# ---------------------------------------------------------------- reverb
def room_ir(rt60=0.6, length=None, predelay=0.008, early=12, early_span=0.04, hf_damp=0.5,
            density_ms=25.0, seed=7, stereo=False, low_rt_mult=1.15):
    """synthetic room impulse response: sparse early reflections + a diffuse exponentially
    decaying noise tail whose high bands die faster (hf_damp 0..1). Unit energy."""
    rng = np.random.default_rng(seed)
    length = length or min(6.0, rt60 * 1.4 + predelay + 0.05)
    n = ns(length)
    chans = 2 if stereo else 1
    t = np.arange(n) / SR
    out = np.zeros((chans, n))
    bands = [(20, 250), (250, 1000), (1000, 4000), (4000, 10000), (10000, 20000)]
    for c in range(chans):
        tail = np.zeros(n)
        nz = rng.standard_normal(n)
        for k, (lo, hi) in enumerate(bands):
            # band RT: low bands slightly longer, high bands shorter by hf_damp
            mult = [low_rt_mult, 1.0, 1.0 - 0.35 * hf_damp, 1.0 - 0.6 * hf_damp, 1.0 - 0.8 * hf_damp][k]
            rt = max(0.03, rt60 * mult)
            bnz = bp(nz, lo, min(hi, SR * 0.48), order=2)
            tail += bnz * np.exp(-6.9078 * np.maximum(0, t - predelay) / rt)
        # diffuse build-up
        build = np.clip((t - predelay) / (density_ms / 1000.0), 0, 1) ** 1.5
        tail *= build
        tail[t < predelay] = 0
        er = np.zeros(n)
        for _ in range(early):
            d = predelay * 0.4 + rng.uniform(0.0, early_span)
            i = ns(d)
            if i < n:
                er[i] += rng.uniform(0.3, 1.0) * (1 if rng.random() < 0.5 else -1) * (0.02 / (0.02 + d))
        er = lp(er, 7000 if hf_damp > 0.3 else 11000)
        ir = tail / (np.sqrt(np.sum(tail ** 2)) + 1e-12) + 0.6 * er / (np.sqrt(np.sum(er ** 2)) + 1e-12)
        out[c] = ir / (np.sqrt(np.sum(ir ** 2)) + 1e-12)
    return out if stereo else out[0]


_IR_CACHE = {}


def reverb(x, wet=0.2, rt60=0.6, predelay=0.008, hf_damp=0.5, seed=7, early_span=0.04, dry=1.0,
           tail_pad=None, pre_hp=120.0):
    """mono convolution reverb. Returns dry*x + wet*reverb (wet scaled relative to x's RMS-ish level)."""
    key = (round(rt60, 3), round(predelay, 4), round(hf_damp, 3), seed, round(early_span, 3))
    if key not in _IR_CACHE:
        _IR_CACHE[key] = room_ir(rt60=rt60, predelay=predelay, hf_damp=hf_damp, seed=seed, early_span=early_span)
    ir = _IR_CACHE[key]
    src = hp(x, pre_hp) if pre_hp else x
    w = signal.fftconvolve(src, ir)
    pad = len(w) - len(x) if tail_pad is None else ns(tail_pad)
    y = np.concatenate([dry * x, np.zeros(max(0, pad))])
    w = w[: len(y)]
    y[: len(w)] += wet * w
    return y


# ---------------------------------------------------------------- texture generators
def crackle(dur, rng, rate, decay=0.002, f_lo=800, f_hi=6000, amp_curve=None, size_var=0.6):
    """random impulsive grains (debris, fire crackle, grit). rate = grains/s (scalar or curve)."""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    p = rate / SR
    hits = rng.random(n) < p
    idx = np.nonzero(hits)[0]
    imp = np.zeros(n)
    imp[idx] = rng.standard_normal(len(idx)) * np.exp(size_var * rng.standard_normal(len(idx)))
    if amp_curve is not None:
        imp *= amp_curve
    k = max(8, ns(decay * 6))
    kern = np.exp(-np.arange(k) / max(1, ns(decay))) * rng.standard_normal(k)
    y = signal.fftconvolve(imp, kern)[:n]
    return bp(y, f_lo, f_hi)


def jingle(dur, rng, count, f_lo=2500, f_hi=8000, t60=(0.02, 0.08), spread=None, start=0.0, amp_decay=0.25):
    """chainmail / small metal pieces: many tiny inharmonic pings at random times"""
    n = ns(dur)
    out = np.zeros(n)
    span = spread if spread is not None else dur - start
    times = np.sort(start + rng.exponential(span / 3.0, count))
    times = times[times < dur - 0.01]
    for tt in times:
        f = np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))
        fr = metal_partials(f, "plate", n=5, rng=rng, spread=0.03)
        tl = rng.uniform(*t60)
        L = ns(min(dur - tt, tl * 1.2 + 0.005))
        if L < 8:
            continue
        g = np.exp(-(tt - start) / max(1e-3, amp_decay)) * rng.uniform(0.3, 1.0)
        s = modal(L / SR, fr, [tl, tl * 0.7, tl * 0.6, tl * 0.5, tl * 0.4], [1, .7, .5, .35, .25], rng)
        i = ns(tt)
        out[i:i + len(s)] += g * s[: n - i]
    return out


def thump(dur, f0, f1, rng, decay=0.12, click=0.3, noise=0.4, sweep_curve=4.0, nlp=400):
    """low body impact: pitch-dropping sine + low noise + click"""
    t = tvec(dur)
    body = sine_sweep(dur, f0, f1, sweep_curve) * np.exp(-t / decay)
    nz = lp(white(dur, rng), nlp, order=2) * np.exp(-t / (decay * 0.5))
    nz /= np.max(np.abs(nz)) + 1e-12
    ck = hp(white(dur, rng), 1500) * np.exp(-t / 0.0025)
    ck /= np.max(np.abs(ck)) + 1e-12
    y = body + noise * nz + click * ck
    return y * np.clip(t / 0.0007, 0, 1)


def whoosh(dur, rng, peak=0.5, f_lo=300.0, f_hi=3000.0, q=1.1, power=2.5, rise=1.6, fall=1.4,
           whistle=0.0, whistle_mult=1.0, whistle_q=9.0, body=0.0, body_lp=300.0, body_hp=0.0,
           flutter_hz=0.0, flutter_depth=0.0, color=-1.5, doppler=0.06):
    """physically-flavoured swing: a blade's speed profile s(t) drives loudness (~s^power,
    aeroacoustic) and the turbulence band (Strouhal: f ~ s). A slight doppler skew raises the
    pitch on approach and drops it as the blade passes."""
    n = ns(dur)
    s = bell(dur, peak, rise, fall)
    u = np.linspace(0, 1, n, endpoint=False)
    dop = 1 + doppler * np.tanh((peak - u) * 8)
    fc = f_lo * (f_hi / f_lo) ** s * dop
    amp = s ** power
    src = colored(dur, rng, color)
    y = tvf(src, "bp", fc, q) * amp
    if whistle:
        ws = tvf(white(dur, rng), "bp", fc * whistle_mult, whistle_q)
        ws = tvf(ws, "bp", fc * whistle_mult, whistle_q)
        y += whistle * ws / (np.max(np.abs(ws)) + 1e-12) * np.max(np.abs(y)) * amp
    if body:
        b = lp(brown(dur, rng), body_lp, order=4)
        if body_hp:
            b = hp(b, body_hp, order=2)
        b = b * s ** (power * 0.8)
        y += body * b / (np.max(np.abs(b)) + 1e-12) * np.max(np.abs(y))
    if flutter_hz:
        ph = phase_from_freq(flutter_hz * (0.6 + 0.4 * s), n)
        y *= 1 - flutter_depth * 0.5 * (1 + np.sin(ph))
    return y


# ---------------------------------------------------------------- finishing + IO
def finalize(x, peak_db=-3.0, fade_out=0.03, onset_db=-40.0, pre_roll=0.0005, hp_hz=25.0, max_len=None,
             tail_db=-62.0):
    """DC/rumble high-pass, trim leading silence (first sample above onset_db re peak),
    trim the inaudible tail, short fade-out, peak normalise."""
    x = np.asarray(x, dtype=np.float64)
    if hp_hz:
        x = signal.sosfiltfilt(_sos("hp", hp_hz, 2), x) if len(x) > 30 else x
    a = np.abs(x)
    pk = a.max() + 1e-12
    on = np.nonzero(a > pk * db(onset_db))[0]
    start = max(0, on[0] - ns(pre_roll)) if len(on) else 0
    env = maximum_filter1d(a, size=ns(0.01) + 1)
    alive = np.nonzero(env > pk * db(tail_db))[0]
    end = alive[-1] + 1 if len(alive) else len(x)
    x = x[start:end]
    if max_len:
        x = x[: ns(max_len)]
    x = fade(x, fin=pre_roll if start > 0 else 0.0, fout=min(fade_out, len(x) / SR * 0.5))
    return peak_norm(x, db(peak_db))


def write_wav(path, x, sr=SR):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    x = np.asarray(x)
    data = np.clip(x, -1, 1)
    wavfile.write(path, sr, (data * 32767).astype(np.int16) if data.ndim == 1 else (data.T * 32767).astype(np.int16))


def write_ogg(path, x, sr=SR, quality=6):
    """encode to Ogg Vorbis with ffmpeg (libvorbis); x is mono (n,) or stereo (2, n)"""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        w = os.path.join(td, "tmp.wav")
        x = np.asarray(x, dtype=np.float32)
        data = x if x.ndim == 1 else x.T
        wavfile.write(w, sr, data)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", w, "-c:a", "libvorbis", "-q:a", str(quality),
                        "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", path], check=True)


def read_audio(path, sr=SR):
    """decode anything ffmpeg reads to float (channels, n) at sr"""
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ar", str(sr), "-"],
                         capture_output=True, check=True).stdout
    probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=channels", "-of", "csv=p=0", path],
                           capture_output=True, text=True, check=True).stdout.strip().splitlines()[0]
    ch = int(probe)
    y = np.frombuffer(out, dtype=np.float32).astype(np.float64)
    return y.reshape(-1, ch).T
