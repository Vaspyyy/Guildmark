"""Guildmark's two schools of magic, sorcery and prayer: their sound effects, made from nothing.

usage (from the repo root):
    python3 -B art/audio/make_magic_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only arc_bolt_cast,flare]
        [--mp3 FILE]

Writes the effects as mono 44.1 kHz Ogg Vorbis to <assets>/sounds/magic/<name><n>.ogg for n = 1..variants. --only
takes effect names. --wav also writes WAV copies; --mp3 joins what was rendered into one listen-through file (every
sound and its variants in turn, a gap between, a chapter mark at each).

Sorcery is cold. Struck crystal (the modes of a glass bowl or a crystal bar, each a doublet that beats a few Hz), sparks
that crackle in clumps, rushing air, hair-fine frost crackle and a hum of glass rising on a breath. Prayer is warm.
Bells and gongs struck soft (modal, with the beating doublets of cast metal), a small choir humming on a vowel (glottal
pulses through formants, each voice its own drift and vibrato) and fire (shaped noise, flutter, embers). Everything is
made with sfxkit.py like make_dragon_audio.py's effects, which this script follows: each sound is one `@recipe` that
sets its variants, length and loudness, and every random number is seeded, so a rerun gives the same files.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403

RECIPES = {}


def recipe(name, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, rms_db=None, limit_db=6.0):
    """one effect, written as magic/<name><n>.ogg for n = 1..variants (the same contract as make_dragon_audio.py's).
    peak_db: where the file's peak sits: one-shots at -3 dBFS like the combat and dragon sounds, the long soft swells
    and the quiet ones below it, so that all of them come out at the loudness of what they sit beside in the game.
    rms_db: the loudness a sharp sound should have (its RMS, with its peak at peak_db): it is soft-limited just enough
    to land there, but never by more than limit_db, so its crack stays a crack"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db, rms_db=rms_db, limit_db=limit_db)
        return fn
    return deco


def render_effect(name, v):
    """one variant of one effect, finished (levelled, trimmed, faded, peak at peak_db)"""
    r = RECIPES[name]
    x = r["fn"](v, rng_for("magic/" + name, v))
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]

    def finish(y):
        return finalize(y, peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])

    y = finish(x)
    if not r["rms_db"] or to_db(rms(y)) >= r["rms_db"]:  # (a sound already denser than asked is left alone)
        return y
    x = x / (np.max(np.abs(x)) + 1e-12)
    lo, hi = db(-r["limit_db"]), 1.0
    for _ in range(12):  # the soft limit that gives the asked RMS: lower ceiling, denser sound
        c = float(np.sqrt(lo * hi))
        y = finish(limiter(x, c, 0.002, 0.05))
        if to_db(rms(y)) < r["rms_db"]:
            hi = c
        else:
            lo = c
    return finish(limiter(x, float(np.sqrt(lo * hi)), 0.002, 0.05))


# =========================================================================== small things
def nrm(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def late(x, at, n=None):
    """x delayed by `at` seconds (zero-padded), optionally fitted to n samples"""
    y = np.concatenate([np.zeros(ns(at)), x])
    return fit(y, n) if n else y


def kweight(x):
    """BS.1770 K-weighting (a +4 dB shelf above ~1.5 kHz and a 38 Hz high-pass): roughly how loud a sound sounds"""
    def biquad(gain_db, q, fc, shelf):
        A = 10 ** (gain_db / 40.0)
        w0 = 2 * np.pi * fc / SR
        alpha = np.sin(w0) / (2 * q)
        c = np.cos(w0)
        if shelf:
            b = [A * ((A + 1) + (A - 1) * c + 2 * np.sqrt(A) * alpha), -2 * A * ((A - 1) + (A + 1) * c),
                 A * ((A + 1) + (A - 1) * c - 2 * np.sqrt(A) * alpha)]
            a = [(A + 1) - (A - 1) * c + 2 * np.sqrt(A) * alpha, 2 * ((A - 1) - (A + 1) * c),
                 (A + 1) - (A - 1) * c - 2 * np.sqrt(A) * alpha]
        else:
            b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
            a = [1 + alpha, -2 * c, 1 - alpha]
        return np.array(b) / a[0], np.array(a) / a[0]
    y = signal.lfilter(*biquad(4.0, 1 / np.sqrt(2), 1500.0, True), x)
    return signal.lfilter(*biquad(0.0, 0.5, 38.0, False), y)


def rn(x, ref=0.1):
    """x levelled by its loudness (K-weighted RMS, over at least 100 ms: the ear adds up what comes within that), not
    by its peak, so that a spray of clicks and a whoosh given the same gain sound as loud as each other"""
    k = kweight(np.asarray(x, dtype=float))
    r = float(np.sqrt(np.sum(k * k) / max(len(k), ns(0.1))))
    return x * (ref / r) if r > 1e-9 else x


def tame(x, crest_db=12.0):
    """soft-clip x so that no peak stands more than crest_db over its RMS: the few biggest clicks of a crackle would
    otherwise be so far above the rest that they set the level of the whole sound"""
    ceil = float(np.sqrt(np.mean(np.square(x)))) * db(crest_db) + 1e-12
    return np.tanh(x / ceil) * ceil


class Bus(Mix):
    """a Mix whose layers come in levelled by loudness (rn): a layer's gain is how loud it is next to the others"""

    def add(self, x, at=0.0, gain=1.0, end_fade=0.006):
        return super().add(rn(x), at, gain, end_fade)


def wander(dur, rng, rate, depth):
    """a slow random drift about 1 (within +-depth): pitch and level that are never quite still"""
    return 1 + depth * (2 * smooth_random(dur, rng, rate) - 1)


_SEMI = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def note(name):
    """'F#4' -> its pitch in Hz (equal temperament, A4 = 440)"""
    semis = _SEMI[name[0]] + name.count("#") - name.count("b")
    octave = int(name.lstrip("ABCDEFG#b"))
    return 440.0 * 2 ** ((12 * (octave + 1) + semis - 69) / 12)


def burst(dur, rng, lo=2000.0, hi=12000.0, decay=0.003):
    """a click: band noise with a sharp front and a fast decay"""
    t = tvec(dur)
    return nrm(bp(white(dur, rng), lo, min(hi, 20000)) * np.exp(-t / decay) * np.clip(t / 0.0002, 0, 1))


def air_thump(dur, rng, f0=50.0, f1=30.0, rise=0.03, decay=0.1, noise=0.6, nlp=240.0):
    """a push of air: a low pressure swell with a soft front, no click"""
    t = tvec(dur)
    a = np.sin(np.pi / 2 * np.clip(t / rise, 0, 1)) ** 2
    env = a * np.exp(-np.maximum(0, t - rise) / decay)
    body = sine_sweep(dur, f0, f1, 3.0) * env
    nz = lp(brown(dur, rng), nlp, order=4) * env
    return nrm(nrm(body) + noise * nrm(nz))


def whistle(dur, rng, fc, q=12.0, passes=2):
    """a thin whistle of air: noise through a narrow resonance that can move (fc is a Hz scalar or curve)"""
    y = white(dur, rng)
    for _ in range(passes):
        y = tvf(y, "bp", fc, q)
    return nrm(y)


def airy(dur, rng, **kw):
    """a whoosh (sfxkit.whoosh), peak-normalised"""
    return nrm(whoosh(dur, rng, **kw))


# =========================================================================== spaces
def cold(x, wet=0.14, rt60=0.8, seed=501, predelay=0.008):
    """sorcery's air: clean and bright, the highs hardly damped, so a tail shimmers instead of darkening"""
    return reverb(x, wet=wet, rt60=rt60, predelay=predelay, hf_damp=0.12, seed=seed, early_span=0.04)


def chapel(x, wet=0.22, rt60=2.0, seed=601, predelay=0.02):
    """prayer's stone: a warm hall, the highs dying first"""
    return reverb(x, wet=wet, rt60=rt60, predelay=predelay, hf_damp=0.62, seed=seed, early_span=0.07)


# =========================================================================== sorcery: crystal, sparks, frost
MODES = {  # what a struck crystal rings with, as ratios of its lowest mode
    "bowl": (1.0, 2.32, 4.25, 6.63, 9.38),            # a glass bowl or goblet
    "bar": (1.0, 2.756, 5.404, 8.933, 13.344),        # a free bar of crystal
    "soft": (1.0, 2.0, 3.01, 4.16, 5.43),             # a small music-box tine: nearly harmonic, warmer
}


def glass(f0, rng, t60=0.4, kind="bowl", n=4, tilt=1.1, beat=3.0, damp=0.8, click=0.12, dur=None):
    """a struck crystal: the modes of a glass bowl or a crystal bar, each a doublet (no crystal is quite round, so every
    mode splits in two a few Hz apart and the ring wavers), the upper ones dying first, and the click of the blow"""
    dur = dur or t60 * 1.1
    t = tvec(dur)
    out = np.zeros(len(t))
    for k, r in enumerate(MODES[kind][:n]):
        f = f0 * r * (1 + rng.uniform(-0.003, 0.003))
        if f > 16500:
            break
        a = (1 + k) ** -tilt * rng.uniform(0.75, 1.0)
        dec = np.exp(-6.9078 * t / (t60 / (1 + damp * k)))
        b = beat * rng.uniform(0.6, 1.5) * (1 + 0.25 * k)
        out += a * dec * (np.sin(2 * np.pi * f * t) + 0.5 * np.sin(2 * np.pi * (f + b) * t + rng.uniform(0, 0.6)))
    out = nrm(out) * np.sin(np.pi / 2 * np.clip(t / 0.0004, 0, 1)) ** 2
    if click:
        k = min(len(t), ns(0.01))
        ck = np.zeros(len(t))
        ck[:k] = burst(k / SR, rng, f0 * 0.7, 15000, 0.0006)
        out = out + click * ck
    return nrm(out)


# the sorcery's chimes sit on an open set of notes (E, F#, B, C#), no thirds: nothing warm in them
COLD = np.array(sorted(note("E5") * 2 ** ((s + 12 * o) / 12) for o in range(4) for s in (0, 2, 7, 9)))


def snap(f, scale):
    """the nearest pitch of the scale (on a log axis)"""
    return float(scale[np.argmin(np.abs(np.log(scale / f)))])


def chimes(dur, rng, rate, f_lo, f_hi, t60=(0.1, 0.25), kind="bowl", n=3, scale=None, level=1.0, beat=4.0, click=0.15):
    """a scatter of small crystals ringing: pings at random times (rate per second, scalar or curve) and random pitches
    (snapped to a scale, when given); the smaller the crystal, the shorter it rings"""
    total = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (total,))
    lv = np.broadcast_to(np.asarray(level, dtype=float), (total,))
    m = Mix(dur)
    for i in np.nonzero(rng.random(total) < rate / SR)[0]:
        f = float(np.exp(rng.uniform(np.log(f_lo), np.log(f_hi))))
        if scale is not None:
            f = snap(f, scale)
        tl = rng.uniform(*t60) * (f / 3000.0) ** -0.4
        m.add(glass(f, rng, t60=tl, kind=kind, n=n, beat=beat, click=click), i / SR, lv[i] * rng.uniform(0.35, 1.0))
    return fit(m.out(), total)


def sparks(dur, rng, rate, lo=2000.0, hi=11000.0, decay=0.0004, clump=0.55, kernels=3, size_var=0.55):
    """electric crackle: sparks in clumps. A clump is a few clicks a fraction of a millisecond apart, each weaker than
    the last (the zzt of an arc), and the clumps fall at `rate` per second (scalar or curve). Each click rings a
    short burst of noise, and a few different bursts are used so the clicks do not all sound alike"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    trains = np.zeros((kernels, n))
    for i in np.nonzero(rng.random(n) < rate / SR)[0]:
        k = int(rng.integers(kernels))
        amp = float(np.exp(size_var * rng.standard_normal()))
        pos = int(i)
        while pos < n:
            trains[k, pos] += amp * rng.choice((-1.0, 1.0))
            if rng.random() > clump:
                break
            pos += ns(rng.uniform(0.0002, 0.0016))
            amp *= 0.78
    kl = max(8, ns(decay * 6))
    y = np.zeros(n)
    for k in range(kernels):
        ker = np.exp(-np.arange(kl) / max(1, ns(decay))) * rng.standard_normal(kl)
        y += signal.fftconvolve(trains[k], ker)[:n]
    return tame(bp(y, lo, hi), 12.0)


def shimmer(dur, rng, f_lo, f_hi, count=7, rate=9.0, rise=None):
    """a cluster of glassy high partials, each fading in and out at a rate of its own: the shimmer of light on cut
    crystal. rise is a per-sample multiplier on the pitches (a cluster that climbs)"""
    n = ns(dur)
    out = np.zeros(n)
    for j in range(count):
        f = f_lo * (f_hi / f_lo) ** ((j + rng.uniform(-0.3, 0.3)) / max(1, count - 1))
        fv = f * (rise if rise is not None else 1.0)
        env = smooth_random(dur, rng, rate * rng.uniform(0.7, 1.4), 0.0, 1.0) ** 2
        out += env * np.sin(phase_from_freq(fv, n) + rng.uniform(0, 6.28)) / (1 + 0.15 * j)
    return nrm(out)


def frost(dur, rng, rate, lo=4500.0, hi=13000.0, decay=0.00035):
    """frost crackling: hair-fine grains, far finer than a fire's"""
    return nrm(tame(crackle(dur, rng, rate, decay=decay, f_lo=lo, f_hi=hi, size_var=0.6), 12.0))


def shards(dur, rng, rate, f_lo=1800.0, f_hi=9000.0, t60=(0.015, 0.1), level=1.0):
    """broken glass: a cascade of tiny pings at random times and pitches, no scale, each a crystal splinter"""
    total = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (total,))
    lv = np.broadcast_to(np.asarray(level, dtype=float), (total,))
    m = Mix(dur)
    for i in np.nonzero(rng.random(total) < rate / SR)[0]:
        f = float(np.exp(rng.uniform(np.log(f_lo), np.log(f_hi))))
        tl = rng.uniform(*t60) * (f / 4000.0) ** -0.3
        p = glass(f, rng, t60=tl, kind="bar", n=3, beat=rng.uniform(2, 9), click=0.4)
        m.add(p, i / SR, lv[i] * np.exp(0.7 * rng.standard_normal()) * 0.5)
    return fit(m.out(), total)


# =========================================================================== prayer: bells, gongs, choir, fire
# a soft-struck bell's modes: ratio to its strike note, level, T60 as a fraction of the bell's, doublet beat in Hz. The
# hum an octave under, the prime and the fifth and octave over it carry it; the minor third is kept low and short so that
# it never fights the major chords the choirs sing round the bells
WARM = [(0.5, 0.55, 1.25, 0.5), (1.0, 1.0, 1.0, 0.7), (1.19, 0.12, 0.5, 1.0), (1.5, 0.42, 0.7, 1.2),
        (2.0, 0.55, 0.55, 1.6), (2.5, 0.14, 0.4, 2.0), (3.0, 0.22, 0.4, 2.4), (4.0, 0.12, 0.28, 3.0),
        (5.4, 0.07, 0.2, 3.6), (6.8, 0.04, 0.14, 4.2)]


def warm_bell(f0, rng, t60=2.0, fc=2800.0, dur=None, attack=0.004, detune=0.002, hum=1.0):
    """a bell struck with a soft mallet: the warm partials of WARM, each a doublet that beats slowly (the warble of
    cast metal), the upper ones dying first and the mallet rolling the highs off (fc is where it starts to). hum scales
    the lowest partial, the octave-under hum that outlasts all the rest (a big bell would drown in it)"""
    dur = dur or t60 * 1.1
    t = tvec(dur)
    out = np.zeros(len(t))
    for r, a, tf, bt in WARM:
        f = f0 * r * (1 + rng.normal(0, detune))
        if f > 15000:
            continue
        amp = a * (hum if r < 0.75 else 1.0) / (1 + (f / fc) ** 2)
        tk = t60 * tf
        b = bt * rng.uniform(0.8, 1.25)
        ph = rng.uniform(0, 2 * np.pi)
        out += amp * np.exp(-6.9078 * t / tk) * (np.sin(2 * np.pi * (f - b / 2) * t + ph) +
                                                  0.55 * np.sin(2 * np.pi * (f + b / 2) * t + ph))
    return nrm(out * np.sin(np.pi / 2 * np.clip(t / attack, 0, 1)) ** 2)


def mallet(dur, rng, f=200.0, decay=0.012, tone=0.6):
    """the thud of a soft mallet on metal: a dull knock with a little tone in it"""
    t = tvec(dur)
    kn = lp(white(dur, rng), 3.0 * f + 400) * np.exp(-t / decay)
    kt = np.sin(2 * np.pi * f * t) * np.exp(-t / (decay * 1.6))
    return nrm(nrm(kn) + tone * nrm(kt)) * np.clip(t / 0.0008, 0, 1)


def gong(f0, rng, t60=4.0, dur=None, bloom=0.25, fc=2000.0, n=13):
    """a great gong (a tam-tam's way): dense inharmonic modes that do not begin together. The low ones speak at the
    blow, the high ones swell in over the next tenth of a second or more (the energy runs up the spectrum as the metal
    flexes), and the whole slides a hair flat as it settles, each mode wavering as it beats"""
    dur = dur or t60 * 1.1
    t = tvec(dur)
    out = np.zeros(len(t))
    ratios = np.concatenate([[0.5], metal_partials(1.0, "plate", n=n - 1)])
    for k, r in enumerate(ratios):
        f = f0 * r * (1 + rng.uniform(-0.01, 0.01))
        if f > 12000:
            continue
        a = (1.0 / (1 + 0.45 * k)) / (1 + (f / fc) ** 2) * rng.uniform(0.7, 1.0)
        ta = max(0.0008, bloom * (k / n) ** 1.2 * rng.uniform(0.6, 1.4))
        tk = t60 * (1.0 / (1 + 0.16 * k)) * rng.uniform(0.85, 1.1)
        env = (1 - np.exp(-t / ta)) * np.exp(-6.9078 * t / tk)
        fl = f * (1 - 0.012 * np.exp(-t / 0.35))
        b = rng.uniform(0.4, 3.0)
        ph = rng.uniform(0, 2 * np.pi)
        out += a * env * (np.sin(ph + phase_from_freq(fl, len(t))) + 0.6 * np.sin(ph + phase_from_freq(fl + b, len(t))))
    return nrm(out)


def strike(f0, rng, dur=0.5, t60=0.3, n=8, top=7500.0):
    """the splash on top of a struck bell or gong: a cloud of high inharmonic partials that rings out within a fraction of
    a second, the metal of the blow that a big bell's low notes would otherwise bury (f0 is the bell's strike note)"""
    ratios = (4.2, 5.4, 6.8, 8.3, 10.1, 12.4, 15.0, 18.2)[:n]
    fs = [f0 * r * rng.uniform(0.985, 1.015) for r in ratios if f0 * r < top]
    t60s = [t60 / (1 + 0.25 * k) for k in range(len(fs))]
    amps = [1.0 / (1 + 0.45 * k) for k in range(len(fs))]
    return nrm(modal(dur, fs, t60s, amps, rng))


def tract(x, va, vb=None, curve=None, scale=1.0, bw=1.0, gains=(1.0, 0.6, 0.35, 0.2)):
    """a vocal tract on x: the four formants of vowel va (opening towards vb along curve, 0..1 per sample), scale > 1
    for a shorter tract, bw widening the resonances (a choir's are broader than one voice's)"""
    fa, ba = VOWELS[va]
    fb, bb = VOWELS[vb or va]
    still = vb is None and curve is None
    out = np.zeros(len(x))
    for k in range(4):
        if still:
            out += gains[k] * eq(x, "bp", fa[k] * scale, fa[k] / max(ba[k] * bw, 20))
            continue
        c = np.linspace(0, 1, len(x)) if curve is None else curve
        f = (fa[k] + (fb[k] - fa[k]) * c) * scale
        w = (ba[k] + (bb[k] - ba[k]) * c) * bw
        out += gains[k] * tvf(x, "bp", f, f / np.maximum(w, 20))
    return out


def choir(dur, rng, notes, va="o", vb=None, curve=None, voices=3, scale=1.0, vib_hz=5.3, vib_depth=0.005,
          vib_delay=0.25, breath=0.08, spread=7.0, bw=1.4, open_q=0.72, glide=None, lpf=6500.0):
    """a small choir singing a chord on one vowel, no words. Each note is `voices` singers, each a glottal pulse train
    with a detune (spread cents), a slow drift and a vibrato of its own that settles in after the first moments; the sum
    goes through the vowel's tract, opening from va to vb along curve. glide: a per-sample multiplier on every pitch"""
    n = ns(dur)
    t = tvec(dur)
    src = np.zeros(n)
    for f in notes:
        for _ in range(voices):
            fv = np.broadcast_to(np.asarray(f, dtype=float), (n,)) * (glide if glide is not None else 1.0)
            fv = fv * 2 ** (rng.uniform(-spread, spread) / 1200) * wander(dur, rng, 1.2, 0.002)
            vd = vib_depth * rng.uniform(0.7, 1.2) * np.clip((t - vib_delay) / 0.35, 0, 1)
            fv = fv * (1 + vd * np.sin(2 * np.pi * vib_hz * rng.uniform(0.88, 1.12) * t + rng.uniform(0, 6.28)))
            src += glottal(dur, fv, rng, jitter=0.004, shimmer=0.04, breath=breath, open_q=open_q)
    return lp(nrm(tract(nrm(src), va, vb, curve, scale, bw)), lpf)


def glasspad(dur, rng, notes, glide=None, nh=5, tilt=1.7, vib=(5.0, 0.003), detune=0.004):
    """a held glass tone (a glass harmonica's): pure partials, three copies a few cents apart, a slow vibrato"""
    n = ns(dur)
    t = tvec(dur)
    out = np.zeros(n)
    for f in notes:
        for det in (-detune, 0.4 * detune, detune):
            fv = f * (1 + det) * (glide if glide is not None else 1.0)
            fv = fv * (1 + vib[1] * np.sin(2 * np.pi * vib[0] * rng.uniform(0.85, 1.15) * t + rng.uniform(0, 6.28)))
            ph = phase_from_freq(fv, n)
            for k in range(1, nh + 1):
                out += np.sin(k * ph + rng.uniform(0, 6.28)) / k ** tilt
    return nrm(out)


def flame(dur, rng, s, lo=120.0, hi=3200.0, core=(450.0, 1350.0), hiss=0.16, flutter_hz=22.0, flutter_depth=0.35):
    """a stream of fire: a broad roar, a mid hump that opens with the swell s (0..1 per sample), a hiss on top, ragged
    flicker and the flame front's own flutter"""
    n = ns(dur)
    body = hp(lp(colored(dur, rng, -1.5), hi), lo)
    c = tvf(pink(dur, rng), "bp", core[0] + (core[1] - core[0]) * s, 0.7)
    h = bp(white(dur, rng), 2200, 8000)
    flick = smooth_random(dur, rng, 18, 0.5, 1.0) * smooth_random(dur, rng, 5, 0.7, 1.0)
    ph = phase_from_freq(flutter_hz * wander(dur, rng, 2.0, 0.25), n) + rng.uniform(0, 6.28)
    flut = 1 - flutter_depth * (0.5 + 0.5 * np.sin(ph))
    return (nrm(body) + 0.75 * nrm(c) + hiss * nrm(h) * s) * flick * flut


def embers(dur, rng, rate, lo=900.0, hi=7500.0, pops=0.0):
    """fire crackle: fine sparks, and (pops > 0) the odd bigger snap of something burning through"""
    y = nrm(crackle(dur, rng, rate, decay=0.0008, f_lo=lo, f_hi=hi))
    if pops:
        p = crackle(dur, rng, np.asarray(rate, dtype=float) * 0.06, decay=0.0025, f_lo=350, f_hi=3200, size_var=1.0)
        y = y + pops * nrm(p)
    return y


def paper(dur, rng, peak=0.4, lo=900.0, hi=6000.0, grain=900.0):
    """a sheet sliding: soft band noise with a crackle of fibres riding the motion"""
    s = bell(dur, peak, 1.6, 1.8)
    slide = bp(colored(dur, rng, -1.0), lo, hi) * s ** 1.5
    fibres = crackle(dur, rng, grain, decay=0.0012, f_lo=1500, f_hi=7000, amp_curve=s)
    return nrm(slide) + 0.35 * nrm(fibres)


def rock(dur, rng, modes, t60):
    """the knock of stone or crystal breaking under a blow"""
    ex = crackle(0.03, rng, 9000, decay=0.001, f_lo=300, f_hi=6000)
    return nrm(resonators(fit(ex, ns(dur)), list(modes), list(t60), [1, .9, .7, .5, .35][:len(modes)]))


def bits(dur, rng, count, start=0.04, spread=0.25, f_lo=1100.0, f_hi=4200.0):
    """bits of stone and crystal skittering and pattering down"""
    m = Mix(dur)
    for _ in range(count):
        tt = start + rng.exponential(spread)
        if tt > dur - 0.05:
            continue
        f = rng.uniform(f_lo, f_hi)
        p = resonators(fit(bp(white(0.002, rng), 800, 9000), ns(0.05)), [f, f * 1.47, f * 2.3], [0.03, 0.02, 0.015],
                       [1, .6, .4])
        m.add(nrm(p), tt, rng.uniform(0.2, 0.6) * np.exp(-(tt - start) / (spread * 1.8)))
    return fit(m.out(), ns(dur))


# =========================================================================== sorcery
@recipe("arcane_charge", variants=1, peak_db=-5.0, fade_out=0.22, max_len=1.2, rms_db=-19.0)
def arcane_charge(v, rng):
    """a sorcery gathering (the charge hold begins): a hum of glass rising an octave on a swelling breath, crystal
    partials above it shimmering and climbing, the air drawn thin and tight, and chimes pinging faster until the charge
    is full"""
    dur = 1.2
    n = ns(dur)
    t = tvec(dur)
    u = np.clip(t / 0.98, 0, 1)
    rise = u ** 1.4  # how far up the charge has climbed
    end = np.clip((dur - t) / 0.2, 0, 1)
    f = 98.0 * 2 ** rise  # G2 up to G3
    hum = np.zeros(n)
    for det in (-0.004, 0.0, 0.0045):
        fv = f * (1 + det) * (1 + 0.0025 * np.sin(2 * np.pi * 5.2 * t + rng.uniform(0, 6.28)))
        ph = phase_from_freq(fv, n)
        for k in range(1, 19):
            hum += np.sin(k * ph + rng.uniform(0, 6.28)) / k ** 1.5
    hum = eq(nrm(hum), "peak", 1800, 1.2, 6.0)
    swell = (0.05 + 0.95 * u ** 1.7) * end
    m = Bus(dur)
    m.add(hum * swell, 0.0, 1.0)
    m.add(np.sin(phase_from_freq(f / 2, n)) * swell, 0.0, 0.3)
    m.add(shimmer(dur, rng, 1900, 5200, count=7, rate=9.0, rise=1 + 0.4 * rise) * (0.15 + 0.85 * u ** 2) * end, 0.0, 0.3)
    breath = tvf(colored(dur, rng, -1.0), "bp", 600 * (5200 / 600) ** rise, 1.6) * smooth_random(dur, rng, 12, 0.6, 1.0)
    m.add(breath * rise ** 1.6 * end, 0.0, 0.35)
    m.add(chimes(dur, rng, 2 + 34 * u ** 2, 2400, 7800, t60=(0.1, 0.22), scale=COLD, level=0.25 + 0.75 * u), 0.0, 0.45)
    return cold(fit(m.out(), n), 0.12, 0.7, seed=511)


@recipe("arc_bolt_cast", variants=3, fade_out=0.1, max_len=[0.42, 0.45, 0.4], rms_db=-19.0)
def arc_bolt_cast(v, rng):
    """the bolt leaving the staff: a snap and a spray of clumped sparks, a ragged hiss of air torn open and falling as
    it goes, a glint of violet crystal and a short low push behind it"""
    dur = [0.46, 0.5, 0.44][v]
    t = tvec(dur)
    m = Bus(dur)
    rate = [2600, 3200, 2300][v] * np.exp(-t / 0.05) + 140 * np.exp(-t / 0.18)
    m.add(sparks(dur, rng, rate, lo=1500, hi=8200, decay=0.0004, clump=0.65), 0.0, 1.0)
    m.add(burst(0.03, rng, 1500, 9500, 0.0012), 0.0, 0.4)
    m.add(whoosh(dur, rng, peak=0.17, f_lo=[900, 1000, 800][v], f_hi=[4800, 4200, 5200][v], q=1.2, power=2.0, rise=0.9,
                 fall=1.4, color=-2.0, whistle=0.06, doppler=0.0), 0.004, 0.9)
    m.add(thump(0.12, [210, 190, 230][v], 95, rng, decay=0.03, click=0.0, noise=0.55, nlp=500), 0.0, 0.4)
    g0 = [2350, 2850, 3300][v]
    m.add(glass(g0, rng, t60=0.30, kind="bar", n=3, beat=4.5, click=0.0), 0.003, 0.7)
    m.add(glass(g0 * 1.5, rng, t60=0.18, kind="bowl", n=2, beat=6.5), 0.012, 0.35)
    m.add(bp(white(dur, rng), 5000, 9500) * np.exp(-t / 0.06) * np.clip(t / 0.002, 0, 1), 0.0, 0.2)
    return cold(fit(m.out(), ns(dur)), 0.1, 0.5, seed=521 + v)


@recipe("arc_bolt_impact", variants=2, fade_out=0.15, max_len=[0.52, 0.5], rms_db=-17.5)
def arc_bolt_impact(v, rng):
    """the bolt bursting: a pop (a hard bright crack over a short low thud), a crackle of sparks dying away, a puff of
    air thrown outward and a glassy bowl-ring that hangs on after it"""
    dur = 0.6
    t = tvec(dur)
    m = Bus(dur)
    m.add(burst(0.04, rng, 700, 9500, 0.004), 0.0, 0.5)
    m.add(thump(0.15, [190, 170][v], 70, rng, decay=0.04, click=0.2, noise=0.5, nlp=700), 0.0, 0.5)
    m.add(sparks(dur, rng, [3200, 2700][v] * np.exp(-t / 0.07) + 60 * np.exp(-t / 0.3), lo=1500, hi=8200, decay=0.0005,
                 clump=0.6), 0.004, 0.9)
    m.add(whoosh(0.25, rng, peak=0.12, f_lo=900, f_hi=4200, q=0.9, power=1.8, rise=1.0, fall=1.5, color=-2.0,
                 doppler=0.0), 0.0, 0.5)
    f = [1550, 1850][v]
    m.add(glass(f, rng, t60=0.9, kind="bowl", n=4, beat=4.0, click=0.0), 0.002, 0.6)
    m.add(glass(f * 1.34, rng, t60=0.6, kind="bowl", n=3, beat=6.0, click=0.0), 0.006, 0.3)
    return cold(fit(m.out(), ns(dur)), 0.14, 0.6, seed=531 + v)


@recipe("splinter_cast", variants=2, fade_out=0.12, max_len=[0.52, 0.5], rms_db=-19.5)
def splinter_cast(v, rng):
    """a fan of crystals flung: a bright swish of air spreading and falling away, five splinters leaving one after
    another, each its own little ring, and a few grains of crystal dust"""
    dur = 0.62
    t = tvec(dur)
    m = Bus(dur)
    m.add(whoosh(dur, rng, peak=0.2, f_lo=1500, f_hi=4800, q=1.6, power=1.9, rise=1.0, fall=1.4, color=-2.0,
                 doppler=0.0), 0.0, 0.8)
    base = [[5200, 4400, 3700, 3100, 2600], [2700, 3200, 3800, 4500, 5300]][v]
    for i, f in enumerate(base):
        at = 0.035 + 0.032 * i + rng.uniform(-0.006, 0.006)
        m.add(glass(f * rng.uniform(0.98, 1.02), rng, t60=rng.uniform(0.16, 0.3), kind="bar", n=3, beat=5.0,
                    click=0.2), at, 0.7 * (1 - 0.06 * i))
    m.add(crackle(dur, rng, 260 * np.exp(-t / 0.2), decay=0.0006, f_lo=3000, f_hi=9000), 0.02, 0.25)
    m.add(thump(0.1, 220, 110, rng, decay=0.025, click=0.0, noise=0.5, nlp=600), 0.0, 0.35)
    m.add(bp(white(0.25, rng), 5000, 9000) * bell(0.25, 0.3, 1.5, 1.6), 0.02, 0.12)
    return cold(fit(m.out(), ns(dur)), 0.12, 0.5, seed=541 + v)


@recipe("splinter_impact", variants=3, fade_out=0.1, max_len=[0.32, 0.3, 0.34], rms_db=-19.0)
def splinter_impact(v, rng):
    """a crystal shard striking: a hard little crack, a bright tink that rings a moment, a few grains where it
    splinters and a small knock of the thing it hit"""
    dur = 0.4
    f = [2700, 3300, 3800][v]
    m = Bus(dur)
    m.add(glass(f, rng, t60=0.25, kind="bar", n=4, beat=5.5, click=0.25), 0.0, 1.0)
    m.add(glass(f * 1.41, rng, t60=0.15, kind="bowl", n=2, beat=7.0, click=0.0), 0.0, 0.5)
    m.add(burst(0.012, rng, 1500, 10000, 0.0012), 0.0, 0.35)
    m.add(crackle(0.06, rng, 1100 * np.exp(-tvec(0.06) / 0.02), decay=0.0005, f_lo=2500, f_hi=10000), 0.002, 0.4)
    knock = resonators(fit(burst(0.002, rng, 400, 3000, 0.0008), ns(0.05)), [820 * (1 + 0.1 * v), 1380], [0.015, 0.01],
                       [1, .5])
    m.add(knock, 0.0, 0.4)
    return cold(fit(m.out(), ns(dur)), 0.1, 0.4, seed=551 + v)


@recipe("rime_cast", variants=2, fade_out=0.15, max_len=[0.62, 0.6], rms_db=-19.0)
def rime_cast(v, rng):
    """an ice lance thrown: an icy hiss of air going through cold, a whoosh under it, a thin whistle of the lance's edge
    falling away, a prickle of frost crackle and a few glints"""
    dur = 0.72
    t = tvec(dur)
    u = t / dur
    m = Bus(dur)
    m.add(whoosh(dur, rng, peak=0.16, f_lo=3200, f_hi=8800, q=1.3, power=1.6, rise=0.9, fall=1.3, color=-1.0,
                 doppler=0.05), 0.0, 0.6)
    m.add(whoosh(dur, rng, peak=0.2, f_lo=700, f_hi=3000, q=1.4, power=2.0, rise=1.1, fall=1.3, color=-2.5,
                 doppler=0.0), 0.0, 1.0)
    edge = bell(dur, 0.2, 1.2, 1.2) ** 1.5
    fw = [6200, 5600][v] * (0.58 ** (u ** 0.8)) * wander(dur, rng, 6.0, 0.02)
    m.add(whistle(dur, rng, fw, q=14.0) * edge, 0.0, 0.3)
    m.add(frost(dur, rng, 700 * bell(dur, 0.3, 1.0, 1.5) + 60, decay=0.0004, lo=3500, hi=12000) * edge ** 0.5, 0.0, 0.8)
    m.add(air_thump(0.15, rng, 110, 60, rise=0.012, decay=0.04, noise=0.8, nlp=400), 0.0, 0.4)
    for i in range(3):
        m.add(glass([6800, 7600][v] * (1 - 0.13 * i), rng, t60=0.14, kind="bar", n=2, beat=8.0, click=0.1),
              0.03 + 0.06 * i + rng.uniform(0, 0.02), 0.9)
    return cold(fit(m.out(), ns(dur)), 0.1, 0.5, seed=561 + v)


@recipe("rime_impact", variants=2, fade_out=0.2, max_len=[0.72, 0.7], rms_db=-17.5)
def rime_impact(v, rng):
    """ice shattering on a foe: a hard crack and a low crunch, a cascade of glass splinters ringing away in every
    pitch, then chips of ice tinkling down, a breath of frost mist over all of it"""
    dur = 0.85
    t = tvec(dur)
    m = Bus(dur)
    m.add(burst(0.04, rng, 900, 11000, 0.005), 0.0, 0.5)
    m.add(thump(0.15, [140, 125][v], 60, rng, decay=0.035, click=0.15, noise=0.7, nlp=650), 0.0, 0.5)
    m.add(rock(0.1, rng, [520 * (1 + 0.15 * v), 870, 1450], [0.02, 0.015, 0.01]), 0.0, 0.4)
    m.add(shards(0.5, rng, 900 * np.exp(-tvec(0.5) / 0.1) + 20, f_lo=1800, f_hi=8500, t60=(0.02, 0.12)), 0.002, 1.0)
    m.add(shards(dur - 0.15, rng, 38 * np.exp(-tvec(dur - 0.15) / 0.28), f_lo=2200 * (1 + 0.1 * v), f_hi=7800,
                 t60=(0.08, 0.3)), 0.15, 0.7)
    m.add(bp(white(dur, rng), 3000, 10000) * np.exp(-t / 0.1) * np.clip(t / 0.004, 0, 1), 0.0, 0.25)
    m.add(frost(dur, rng, 600 * np.exp(-t / 0.2) + 20, lo=3500, hi=12000), 0.0, 0.3)
    return cold(fit(m.out(), ns(dur)), 0.16, 0.7, seed=571 + v)


@recipe("undertow_open", variants=1, fade_out=0.2, max_len=0.85, rms_db=-16.0)
def undertow_open(v, rng):
    """a vortex tearing open: a rip (dense clicks like cloth parting), then the air dragged in towards the hole in a
    deep, swelling suck that climbs as it spins up and breaks off as the hole opens, a low whump, and the air still
    rushing round the opened mouth over the dark hum that is left turning"""
    dur = 0.92
    n = ns(dur)
    t = tvec(dur)
    turn = 0.85 + 0.15 * np.sin(phase_from_freq(5.0 * np.ones(n), n) + 1.0)
    m = Bus(dur)
    m.add(sparks(0.22, rng, 1300 * np.exp(-tvec(0.22) / 0.08) + 120, lo=900, hi=6500, decay=0.0007, clump=0.4), 0.0, 0.5)
    m.add(whoosh(0.4, rng, peak=0.85, f_lo=110, f_hi=1500, q=0.9, power=1.5, rise=1.6, fall=3.0, color=-2.0,
                 doppler=0.0, body=0.6, body_lp=220), 0.0, 1.0)
    m.add(whoosh(0.4, rng, peak=0.88, f_lo=500, f_hi=4800, q=1.2, power=2.2, rise=1.8, fall=3.0, color=-1.2,
                 doppler=0.0), 0.0, 0.35)
    m.add(thump(0.6, 74, 32, rng, decay=0.22, click=0.08, noise=0.8, nlp=300), 0.3, 0.7)
    wash = whoosh(0.6, rng, peak=0.08, f_lo=200, f_hi=800, q=1.0, power=1.4, rise=1.0, fall=1.2, color=-2.0, doppler=0.0)
    m.add(wash * turn[:len(wash)], 0.32, 0.5)
    hum = sum(np.sin(2 * np.pi * 71 * k * t + rng.uniform(0, 6.28)) / k ** 1.2 for k in range(1, 7))
    m.add(hum * np.clip((t - 0.3) / 0.3, 0, 1) * turn, 0.0, 0.4)
    return fit(m.out(), n)


@recipe("undertow_loop", variants=1, peak_db=-6.0, fade_out=0.05, max_len=3.0)
def undertow_loop(v, rng):
    """the vortex turning, once through, as long as it lasts: a low hum that sways, airy rushes that sweep round at
    each turn and fall away as it slows, dust ticking in it, then it fades closed"""
    dur = 3.05
    n = ns(dur)
    t = tvec(dur)
    turn = 1.55 * (1 - 0.2 * np.clip((t - 1.8) / 1.25, 0, 1))  # turns a second, slowing as it begins to close
    ph = phase_from_freq(turn, n) + 0.3
    near = 0.5 + 0.5 * np.cos(ph - 0.6)  # 1 as the rush sweeps past
    f = 71.0 * (1 + 0.012 * np.sin(ph)) * wander(dur, rng, 0.8, 0.01)
    hum = np.zeros(n)
    for det in (0.0, 0.0127):
        p = phase_from_freq(f * (1 + det), n)
        for k in range(1, 10):
            hum += np.sin(k * p + rng.uniform(0, 6.28)) / k ** 1.1
    hum = lp(nrm(hum), 900) * (0.78 + 0.22 * np.cos(ph))
    m = Bus(dur)
    m.add(hum, 0.0, 1.0)
    m.add(tvf(colored(dur, rng, -1.2), "bp", 500 * 3.8 ** near, 1.3) * (0.25 + 0.75 * near ** 2), 0.0, 0.8)
    m.add(tvf(white(dur, rng), "bp", 3600 + 2200 * near, 1.4) * near ** 4, 0.0, 0.2)
    m.add(lp(brown(dur, rng), 90, order=4), 0.0, 0.5)
    m.add(crackle(dur, rng, 14 + 22 * near, decay=0.002, f_lo=300, f_hi=2200), 0.0, 0.12)
    m.add(shimmer(dur, rng, 700, 1800, count=5, rate=3.0) * (0.4 + 0.6 * near), 0.0, 0.15)
    return fit(m.out(), n) * np.sin(np.pi / 2 * np.clip(t / 0.3, 0, 1)) ** 2 * np.clip((dur - t) / 1.0, 0, 1) ** 1.6


@recipe("flicker", variants=2, fade_out=0.12, max_len=[0.4, 0.4], rms_db=-19.0)
def flicker(v, rng):
    """a blink: 'fw', the air closing in round you, 'ip', a quick bright flick of it going, a small push where you
    were and a short shimmer of glass left hanging"""
    dur = 0.46
    m = Bus(dur)
    dfw = 0.1
    tf_ = tvec(dfw)
    fc = 350 * (4200 / 350) ** (np.clip(tf_ / 0.07, 0, 1) ** 0.8)
    fw = tvf(colored(dfw, rng, -1.2), "bp", fc, 1.4) * np.sin(np.pi / 2 * np.clip(tf_ / 0.03, 0, 1)) ** 2 * \
        np.clip((dfw - tf_) / 0.04, 0, 1)
    m.add(fw, 0.0, 1.0)
    dip = 0.09
    ti = tvec(dip)
    fi = (2400 + 3600 * ti / dip) if v == 0 else (6000 - 3000 * ti / dip)
    m.add(tvf(white(dip, rng), "bp", fi, 3.0) * np.exp(-ti / 0.025) * np.clip(ti / 0.003, 0, 1), 0.05, 0.8)
    m.add(air_thump(0.12, rng, 120, 60, rise=0.01, decay=0.025, noise=0.9, nlp=400), 0.03, 0.35)
    pitches = [[4100, 5200, 6200, 4700], [6000, 4800, 5600, 4300]][v]
    for i, f in enumerate(pitches):
        m.add(glass(f, rng, t60=rng.uniform(0.16, 0.26), kind="bowl", n=3, beat=5.0, click=0.05),
              0.07 + 0.028 * i + rng.uniform(0, 0.008), 0.5 * (1 - 0.15 * i))
    return cold(fit(m.out(), ns(dur)), 0.12, 0.5, seed=581 + v)


@recipe("star_call", variants=1, peak_db=-5.0, fade_out=0.3, max_len=1.5, rms_db=-19.0)
def star_call(v, rng):
    """the star called down: a held glass chord of open fifths climbing a whole tone, a twinkling of crystal grains
    that thickens, a run of chimes quickening up through the scale, the air drawn up thin and a low dread swelling under"""
    dur = 1.65
    n = ns(dur)
    t = tvec(dur)
    u = np.clip(t / 1.35, 0, 1)
    end = np.clip((dur - t) / 0.3, 0, 1)
    swell = u ** 1.5 * end
    climb = 1 + 0.122 * u ** 1.2
    m = Bus(dur)
    m.add(glasspad(dur, rng, [note("E4"), note("B4"), note("F#5"), note("C#6")], glide=climb) * swell, 0.0, 1.0)
    m.add(chimes(dur, rng, 6 + 60 * u ** 2, 1900, 8200, t60=(0.08, 0.25), kind="bowl", n=3, scale=COLD,
                 level=0.2 + 0.8 * u), 0.0, 0.5)
    run = Mix(dur)
    for i in range(12):
        x = i / 11
        run.add(glass(COLD[min(len(COLD) - 1, 3 + i)], rng, t60=0.7, kind="bowl", n=3, beat=3.5, click=0.1),
                0.3 + 1.0 * x ** 0.75, 0.16 + 0.34 * x)
    m.add(fit(run.out(), n), 0.0, 0.6)
    m.add(tvf(colored(dur, rng, -1.0), "bp", 900 * (7000 / 900) ** u, 1.3) * u ** 2 * end, 0.0, 0.4)
    low = np.sin(phase_from_freq(52 + 30 * u, n)) + 0.5 * np.sin(phase_from_freq(104 + 60 * u, n))
    m.add(low * u ** 3 * end, 0.0, 0.5)
    return cold(fit(m.out(), n), 0.2, 1.4, seed=591)


@recipe("star_fall", variants=1, fade_out=0.008, max_len=0.7, rms_db=-16.0)
def star_fall(v, rng):
    """the star falling in: a thin, breathy whistle sliding down through a fourth and a half while a roar swells under
    it, cold and turbulent, with a low pressure building and a spray of crystal sparks trailing"""
    dur = 0.7
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    fc = 4800 * (1250 / 4800) ** (u ** 0.85) * wander(dur, rng, 8.0, 0.02)
    grow = 0.25 + 0.75 * u ** 1.3
    m = Bus(dur)
    m.add(whistle(dur, rng, fc, q=10.0) * grow, 0.0, 1.3)
    m.add(whistle(dur, rng, fc * 1.5, q=14.0) * grow, 0.0, 0.5)
    mid = tvf(colored(dur, rng, -2.0), "bp", fc / 2.6 + 150, 0.9) * u ** 1.2
    mid *= 1 - 0.35 * (0.5 + 0.5 * np.sin(phase_from_freq(31 * wander(dur, rng, 3.0, 0.2), n)))
    m.add(mid, 0.0, 1.0)
    m.add(lp(brown(dur, rng), 240, order=4) * u ** 2, 0.0, 0.6)
    m.add(np.sin(phase_from_freq(40 + 26 * u, n)) * u ** 2.5, 0.0, 0.35)
    m.add(chimes(dur, rng, 10 + 70 * u, 3000, 9000, t60=(0.04, 0.12), kind="bar", n=2, level=0.2 + 0.8 * u), 0.0, 0.5)
    return fit(m.out(), n)


@recipe("star_impact", variants=2, fade_out=0.5, max_len=[2.0, 2.0], tail_db=-70, rms_db=-14.5)
def star_impact(v, rng):
    """the star landing: a deep boom with a body that rings, a crack of crystal splitting and a rattle of fracture, a
    wave of air thrown out, shards and dust pattering down, and a long crystalline shimmer: chimes ringing out of the
    blast, a few clear glass tones singing on and a cold, bright tail that goes on"""
    dur = 2.5
    n = ns(dur)
    t = tvec(dur)
    k = [1.0, 0.9][v]
    m = Bus(dur)
    imp = thump(1.2, [82, 74][v], [30, 27][v], rng, decay=[0.26, 0.3][v], click=0.3, noise=1.0, nlp=520, sweep_curve=3.0)
    m.add(lp(drive(nrm(imp), 2.6), 1500), 0.0, 1.0)
    m.add(sine_sweep(dur, 44, 26, 2.0) * np.clip(t / 0.012, 0, 1) * np.exp(-t / 0.45), 0.0, 0.3)
    body = modal(dur, [91 * k, 140 * k, 215 * k, 335 * k, 520 * k, 800 * k], [0.7, 0.55, 0.4, 0.3, 0.2, 0.14],
                 [0.9, 1.0, 0.9, 0.7, 0.5, 0.3], rng, glide=0.1)
    m.add(drive(nrm(body), 1.8), 0.0, 0.5)
    m.add(burst(0.06, rng, 800, 12000, 0.006), 0.0, 0.5)
    m.add(crackle(0.4, rng, 6000 * np.exp(-tvec(0.4) / 0.05), decay=0.0008, f_lo=1200, f_hi=10000, size_var=0.9), 0.002, 0.5)
    m.add(rock(0.35, rng, [210 * k, 380 * k, 620 * k, 1040 * k, 1700 * k], [0.09, 0.07, 0.05, 0.04, 0.03]), 0.001, 0.4)
    m.add(whoosh(0.8, rng, peak=0.12, f_lo=250, f_hi=4200, q=0.7, power=1.6, rise=1.0, fall=1.5, color=-2.0, body=1.0,
                 body_lp=300, doppler=0.0), 0.0, 0.6)
    m.add(bits(dur, rng, 30, 0.04, 0.35, 900, 5200), 0.0, 0.3)
    td = dur - 0.1
    m.add(chimes(td, rng, 30 * np.exp(-tvec(td) / 0.7) + 2, 1300, 8200, t60=(0.5, 1.5), kind="bowl", n=4, scale=COLD,
                 level=np.exp(-tvec(td) / 1.0), beat=3.0), 0.1, 0.5)
    sing = Mix(dur)
    for i, f in enumerate([COLD[7], COLD[10], COLD[12], COLD[14], COLD[11], COLD[9]]):
        sing.add(glass(f * (1 + 0.04 * v), rng, t60=rng.uniform(1.4, 2.0), kind="bowl", n=3, beat=3.0, click=0.0,
                       dur=dur - 0.15 - 0.05 * i), 0.05 + 0.07 * i, 0.2 * (1 - 0.08 * i))
    m.add(fit(sing.out(), n), 0.0, 0.4)
    m.add(shimmer(dur, rng, 2500, 7500, count=8, rate=6.0) * np.exp(-t / 1.1) * np.clip(t / 0.15, 0, 1), 0.0, 0.2)
    return cold(fit(m.out(), n), 0.3, 2.3, seed=601 + v)


# =========================================================================== prayer
# the prayers' chimes sit on D major (the Wardfires' own key: their pads and bells are D and A)
WARM_SCALE = np.array(sorted(note("D5") * 2 ** ((s + 12 * o) / 12) for o in range(3) for s in (0, 2, 4, 7, 9)))


def rise_in(t, start, span):
    """0 before start, easing up to 1 over span seconds (a voice or a bell coming in)"""
    return np.sin(np.pi / 2 * np.clip((t - start) / span, 0, 1)) ** 2


@recipe("prayer_charge", variants=1, peak_db=-6.0, fade_out=0.25, max_len=1.2, rms_db=-19.0)
def prayer_charge(v, rng):
    """a prayer gathering: a small choir swelling out of nothing on one breath, 'oo' opening to 'ah' as it grows,
    the voices coming in one after another on a D major chord and lifting a little in pitch, a low warmth under them
    and the breath through it"""
    dur = 1.2
    n = ns(dur)
    t = tvec(dur)
    u = np.clip(t / 1.0, 0, 1)
    end = np.clip((dur - t) / 0.25, 0, 1)
    climb = 1 + 0.012 * u ** 1.5
    m = Bus(dur)
    for f, at in [(note("D3"), 0.0), (note("A3"), 0.1), (note("D4"), 0.2), (note("F#4"), 0.32)]:
        c = choir(dur, rng, [f], va="u", vb="a", curve=u ** 1.2, glide=climb, vib_delay=0.3, breath=0.1, lpf=5000.0)
        m.add(c * rise_in(t, at, 0.5) * u ** 1.1 * end, 0.0, 1.0)
    m.add(np.sin(2 * np.pi * note("D2") * t) * u ** 1.5 * end, 0.0, 0.35)
    m.add(tvf(colored(dur, rng, -2.0), "bp", 1400, 0.9) * u ** 1.6 * end * smooth_random(dur, rng, 10, 0.6, 1.0), 0.0, 0.12)
    return chapel(fit(m.out(), n), 0.2, 1.6, seed=611)


@recipe("hearthlight", variants=2, peak_db=-4.0, fade_out=0.45, max_len=[1.55, 1.5], rms_db=-20.0)
def hearthlight(v, rng):
    """healing light: a few soft bells spilling down (or up) a D major chord, warm and slow, a scatter of small bright
    pings round them, over a gentle pad of voices that swell and climb: the low ones first, the high ones coming in
    above until the chord stands open"""
    dur = 1.75
    n = ns(dur)
    t = tvec(dur)
    u = np.clip(t / 1.3, 0, 1)
    end = np.clip((dur - t) / 0.5, 0, 1)
    m = Bus(dur)
    voices = [(note("D3"), 0.0), (note("A3"), 0.05), (note("D4"), 0.25), (note("F#4"), 0.4), (note("A4"), 0.55)]
    pad = Mix(dur)
    for f, at in voices:
        c = choir(dur, rng, [f], va="o", vb="a", curve=u, glide=1 + 0.008 * u, vib_delay=0.3, breath=0.1, lpf=5500.0)
        pad.add(c * rise_in(t, at, 0.6), 0.0, 1.0)
    m.add(fit(pad.out(), n) * u ** 0.8 * end, 0.0, 1.0)
    seq = [[note("A5"), note("F#5"), note("D5"), note("A4")], [note("D5"), note("F#5"), note("A5"), note("D6")]][v]
    for i, f in enumerate(seq):
        m.add(warm_bell(f, rng, t60=1.5 + 0.1 * i, fc=2600, attack=0.004), 0.05 + 0.08 * i, 0.85 * (1 - 0.1 * i))
    m.add(chimes(dur - 0.15, rng, 14 * np.exp(-tvec(dur - 0.15) / 0.8) + 2, 1300, 4200, t60=(0.3, 0.7), kind="soft",
                 n=3, scale=WARM_SCALE, level=np.exp(-tvec(dur - 0.15) / 0.8), beat=3.0, click=0.02), 0.15, 0.5)
    m.add(shimmer(dur, rng, 2200, 5000, count=5, rate=4.0) * np.exp(-t / 0.9) * np.clip(t / 0.1, 0, 1), 0.0, 0.15)
    return chapel(fit(m.out(), n), 0.24, 1.8, seed=621 + v)


@recipe("flare", variants=2, fade_out=0.25, max_len=[0.72, 0.7], rms_db=-17.0)
def flare(v, rng):
    """a gout of holy fire: a soft whump as the censer breathes out, a bright roar of flame that swells fast and gutters
    over a third of a second, a hiss of sparks along its edge, embers crackling and the faint ring of a bell struck
    by the heat"""
    dur = 0.82
    n = ns(dur)
    t = tvec(dur)
    s = np.clip(t / 0.03, 0, 1) ** 0.7 * np.exp(-np.maximum(0, t - 0.14) / [0.2, 0.24][v])
    m = Bus(dur)
    m.add(air_thump(0.35, rng, 105, 48, rise=0.012, decay=0.07, noise=0.7, nlp=380), 0.0, 0.5)
    m.add(flame(dur, rng, s, lo=140, hi=4200, core=(650, 1700), flutter_hz=[24, 27][v]) * s, 0.0, 1.0)
    m.add(bp(white(dur, rng), 3500, 10000) * np.exp(-t / 0.12) * np.clip(t / 0.004, 0, 1), 0.0, 0.35)
    m.add(embers(dur, rng, 900 * np.exp(-t / 0.25) + 60, lo=2500, hi=9000, pops=0.4) * s ** 0.5, 0.0, 0.4)
    m.add(warm_bell(note("D6") * (1 + 0.12 * v), rng, t60=0.6, fc=5000, attack=0.002), 0.012, 0.25)
    return reverb(fit(m.out(), n), wet=0.1, rt60=0.7, predelay=0.01, hf_damp=0.5, seed=631 + v, early_span=0.03)


@recipe("vigil", variants=1, peak_db=-3.0, fade_out=0.45, max_len=1.6, rms_db=-19.0)
def vigil(v, rng):
    """a vow of protection: a low bell struck with a soft mallet, a gong's bloom opening in it and the metal of the blow
    on top, a warm hummed chord swelling in behind the stroke and holding after it, and the boom of the strike in the
    floor"""
    dur = 2.0
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(warm_bell(note("D3"), rng, t60=2.6, fc=2600, attack=0.005, hum=0.35), 0.0, 1.0)
    m.add(gong(note("A3"), rng, t60=2.2, bloom=0.14, fc=2600), 0.0, 0.6)
    m.add(strike(note("D3"), rng, dur=0.6, t60=0.4), 0.0, 0.6)
    m.add(mallet(0.1, rng, f=160, decay=0.015, tone=0.5), 0.0, 0.5)
    m.add(sine_sweep(dur, 74, 55, 1.5) * np.exp(-t / 0.5) * np.clip(t / 0.006, 0, 1), 0.0, 0.25)
    hum = choir(dur, rng, [note("D3"), note("A3"), note("D4")], va="u", vb="o", curve=np.clip(t / 1.2, 0, 1), voices=3,
                vib_delay=0.4, breath=0.06, lpf=3000.0, glide=1.0, bw=1.2)
    m.add(hum * rise_in(t, 0.04, 0.6) * np.clip((dur - t) / 0.7, 0, 1), 0.0, 1.0)
    return chapel(fit(m.out(), n), 0.24, 2.2, seed=641)


@recipe("wayflame_cast", variants=2, fade_out=0.15, max_len=[0.52, 0.5], rms_db=-19.0)
def wayflame_cast(v, rng):
    """an ember sent off: a soft breath of fire, warm and low, drifting away with a flutter in it, a few sparks
    pattering after"""
    dur = 0.62
    n = ns(dur)
    t = tvec(dur)
    s = bell(dur, 0.3, 1.4, 1.5)
    m = Bus(dur)
    m.add(flame(dur, rng, s, lo=100, hi=2400, core=(380, 950), hiss=0.1, flutter_hz=[17, 20][v], flutter_depth=0.4) * s ** 1.2,
          0.0, 1.0)
    m.add(air_thump(0.25, rng, 90, 50, rise=0.02, decay=0.06, noise=0.7, nlp=300), 0.0, 0.35)
    m.add(embers(dur, rng, 220 * np.exp(-t / 0.2) + 30, lo=1800, hi=7000, pops=0.3) * np.clip(t / 0.04, 0, 1), 0.0, 0.5)
    m.add(bp(white(dur, rng), 3000, 7000) * bell(dur, 0.25, 1.4, 1.6) ** 1.5, 0.0, 0.12)
    return reverb(fit(m.out(), n), wet=0.1, rt60=0.6, predelay=0.008, hf_damp=0.55, seed=651 + v, early_span=0.03)


@recipe("wayflame_impact", variants=2, fade_out=0.2, max_len=[0.62, 0.6], rms_db=-18.0)
def wayflame_impact(v, rng):
    """the ember bursting into flame: a soft whump, a pop and a crackle of burning, a small bloom of flame rising and
    settling, sparks"""
    dur = 0.72
    n = ns(dur)
    t = tvec(dur)
    s = np.clip(t / 0.03, 0, 1) * np.exp(-t / [0.2, 0.24][v])
    m = Bus(dur)
    m.add(air_thump(0.35, rng, [115, 100][v], 50, rise=0.008, decay=0.07, noise=0.9, nlp=420), 0.0, 0.7)
    m.add(burst(0.03, rng, 700, 6500, 0.003), 0.0, 0.35)
    m.add(embers(dur, rng, 1800 * np.exp(-t / 0.12) + 120 * np.exp(-t / 0.4), lo=1500, hi=8000, pops=0.7), 0.004, 0.9)
    m.add(flame(dur, rng, s, lo=120, hi=2600, core=(450, 1100), hiss=0.12, flutter_hz=[20, 23][v]) * s, 0.0, 0.7)
    m.add(bp(white(dur, rng), 3000, 8000) * np.exp(-t / 0.15) * smooth_random(dur, rng, 30, 0.3, 1.0), 0.01, 0.15)
    return reverb(fit(m.out(), n), wet=0.1, rt60=0.7, predelay=0.01, hf_damp=0.5, seed=661 + v, early_span=0.03)


@recipe("hallowed_ground", variants=1, fade_out=0.5, max_len=2.0, rms_db=-18.0)
def hallowed_ground(v, rng):
    """ground hallowed: a bell struck and ringing clear, a second answering an octave up as the ring spreads, the
    boom of the blow in the earth and under it a low resonant hum that swells, wavers and settles"""
    dur = 2.4
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(warm_bell(note("D4"), rng, t60=2.2, fc=3600, hum=0.5), 0.0, 1.0)
    m.add(strike(note("D4"), rng, dur=0.5, t60=0.3), 0.0, 0.5)
    m.add(mallet(0.1, rng, f=320, decay=0.012), 0.0, 0.4)
    m.add(warm_bell(note("D5"), rng, t60=1.6, fc=3500), 0.5, 0.25)
    m.add(thump(0.8, 78, 38, rng, decay=0.25, click=0.05, noise=0.5, nlp=250), 0.0, 0.55)
    hum = np.zeros(n)
    for f, g in [(note("D2"), 1.0), (note("D2") * 1.012, 0.8)]:
        p = phase_from_freq(f * wander(dur, rng, 0.7, 0.004), n)
        for k in range(1, 9):
            hum += g * np.sin(k * p + rng.uniform(0, 6.28)) / k ** 1.0
    hum = eq(lp(nrm(hum), 1000), "peak", 300, 1.4, 5.0)
    m.add(hum * rise_in(t, 0.05, 0.6) * np.clip((dur - t) / 0.8, 0, 1) * (0.85 + 0.15 * np.sin(2 * np.pi * 1.7 * t)), 0.0, 0.75)
    m.add(lp(brown(dur, rng), 80, order=4) * rise_in(t, 0.0, 0.4) * np.exp(-t / 1.0), 0.0, 0.25)
    return chapel(fit(m.out(), n), 0.26, 2.2, seed=671)


@recipe("dawnburst", variants=1, fade_out=0.5, max_len=2.0, rms_db=-15.0)
def dawnburst(v, rng):
    """a burst of dawn light: a quick drawing-in, then a crack and a boom as the light breaks, a big radiant rush of air
    sweeping up and out, a bright cluster of bells on D major, voices opening on a high 'ah', and a shimmer of sparks
    and small bells fading over the rest"""
    dur = 2.5
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    at = 0.07  # where the light breaks
    m.add(whoosh(0.1, rng, peak=0.95, f_lo=300, f_hi=3000, q=1.0, power=2.0, rise=1.2, fall=0.5, color=-1.5,
                 doppler=0.0), 0.0, 0.4)
    m.add(burst(0.06, rng, 900, 12000, 0.005), at, 0.5)
    m.add(embers(0.4, rng, 3500 * np.exp(-tvec(0.4) / 0.06) + 100, lo=2000, hi=11000, pops=0.5), at, 0.6)
    m.add(thump(1.0, 85, 32, rng, decay=0.28, click=0.15, noise=0.8, nlp=450), at, 0.9)
    m.add(whoosh(1.2, rng, peak=0.12, f_lo=250, f_hi=7500, q=0.7, power=1.7, rise=1.0, fall=1.1, color=-1.5, body=1.0,
                 body_lp=400, doppler=0.0), at, 1.0)
    for i, (nm, g, t60) in enumerate([("D5", 1.0, 1.9), ("A5", 0.85, 1.7), ("D6", 0.8, 1.5), ("F#6", 0.6, 1.3),
                                      ("A6", 0.5, 1.1), ("D4", 0.6, 2.0)]):
        m.add(warm_bell(note(nm), rng, t60=t60, fc=6500, attack=0.002), at + 0.012 * i + rng.uniform(0, 0.01), g * 0.7)
    ch = Mix(dur)
    for f, a_ in [(note("D3"), 0.0), (note("A3"), 0.02), (note("D4"), 0.05), (note("F#4"), 0.08), (note("A4"), 0.1)]:
        c = choir(1.6, rng, [f], va="a", vb="e", curve=np.clip(tvec(1.6) / 0.8, 0, 1), voices=3, lpf=7000.0)
        ch.add(c * rise_in(tvec(1.6), 0.0, 0.25) * np.exp(-np.maximum(0, tvec(1.6) - 0.35) / 0.5), a_, 1.0)
    m.add(fit(ch.out(), n), at, 0.6)
    td = dur - 0.3
    m.add(chimes(td, rng, 36 * np.exp(-tvec(td) / 0.7) + 3, 1500, 7500, t60=(0.4, 1.1), kind="soft", n=3,
                 scale=WARM_SCALE, level=np.exp(-tvec(td) / 0.9), beat=3.0, click=0.02), 0.3, 0.6)
    m.add(shimmer(dur, rng, 2600, 7800, count=8, rate=6.0) * np.exp(-t / 1.0) * np.clip(t / 0.1, 0, 1), 0.0, 0.2)
    return chapel(fit(m.out(), n), 0.3, 2.4, seed=681)


@recipe("learn", variants=1, peak_db=-4.0, fade_out=0.3, max_len=1.0, rms_db=-21.0)
def learn(v, rng):
    """a spell learned from a scroll: the stiff rustle of parchment unrolling in two pulls and settling, then a soft
    chime climbing the chord, three small bells and a held fourth, the last ringing on"""
    dur = 1.4
    n = ns(dur)
    m = Bus(dur)
    for at, d, pk in [(0.0, 0.24, 0.4), (0.2, 0.22, 0.5), (0.38, 0.16, 0.3)]:
        m.add(paper(d, rng, peak=pk, lo=500, hi=4500, grain=1500), at, 1.5)
    for i, (nm, at) in enumerate([("D5", 0.4), ("F#5", 0.5), ("A5", 0.6), ("D6", 0.72)]):
        m.add(warm_bell(note(nm), rng, t60=0.8 + 0.15 * i, fc=3800, attack=0.003), at, 0.8 * (1 + 0.1 * i))
    return reverb(fit(m.out(), n), wet=0.18, rt60=1.2, predelay=0.012, hf_damp=0.55, seed=691, early_span=0.04)


@recipe("attune", variants=1, peak_db=-4.0, fade_out=0.3, max_len=1.0, rms_db=-21.0)
def attune(v, rng):
    """spells attuned at a Wardfire: a soft low bell, a shimmer of two high tones that start a little apart, beating,
    and settle into one pure note, a few embers snapping at the edges"""
    dur = 1.4
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(warm_bell(note("A3"), rng, t60=1.6, fc=1800, attack=0.005), 0.0, 1.0)
    m.add(mallet(0.08, rng, f=180, decay=0.012, tone=0.5), 0.0, 0.3)
    f = note("A5")
    for det, g in [(0.0, 1.0), (1.0, 1.0)]:
        cents = 28.0 * (1 - rise_in(t, 0.1, 0.7)) * det
        ph = phase_from_freq(f * 2 ** (cents / 1200), n)
        m.add(np.sin(ph) * np.exp(-t / 0.45) * rise_in(t, 0.0, 0.02), 0.12, 0.5 * g)
    m.add(embers(dur, rng, 40 * np.exp(-t / 0.4), lo=2000, hi=7000, pops=0.0), 0.05, 0.12)
    m.add(chimes(dur - 0.2, rng, 8 * np.exp(-tvec(dur - 0.2) / 0.4) + 1, 2000, 5000, t60=(0.3, 0.6), kind="soft", n=2,
                 scale=WARM_SCALE, level=np.exp(-tvec(dur - 0.2) / 0.5), click=0.02), 0.2, 0.3)
    return chapel(fit(m.out(), n), 0.2, 1.6, seed=701)


@recipe("cycle", variants=2, peak_db=-12.0, fade_out=0.06, max_len=[0.2, 0.21])
def cycle(v, rng):
    """choosing the next spell: a light dry tick with a hint of wood in it, a tiny glassy shimmer ringing on after it
    (quiet)"""
    dur = 0.32
    n = ns(dur)
    m = Bus(dur)
    f = [2300, 2550][v]
    ex = bp(white(0.001, rng), 1500, 9000)
    m.add(resonators(fit(ex, n), [f, f * 1.7, f * 2.6], [0.018, 0.012, 0.008], [1, .5, .3]), 0.0, 1.0)
    m.add(resonators(fit(lp(white(0.002, rng), 1500), n), [[780, 880][v], [1250, 1400][v]], [0.012, 0.008], [1, .5]), 0.0, 0.4)
    g = [4700, 5300][v]
    m.add(glass(g, rng, t60=0.3, kind="bowl", n=3, beat=7.0, click=0.0, dur=dur), 0.005, 1.5)
    m.add(glass(g * 1.5, rng, t60=0.22, kind="bowl", n=2, beat=9.0, click=0.0, dur=dur), 0.012, 0.8)
    return fit(m.out(), n)


@recipe("fizzle", variants=1, peak_db=-10.0, fade_out=0.12, max_len=0.4, rms_db=None)
def fizzle(v, rng):
    """a cast that fails: a dull puff of smoke, soft at the front, lowpassed and falling as it spends itself, a couple
    of faint snaps in it (quiet)"""
    dur = 0.5
    n = ns(dur)
    t = tvec(dur)
    fc = 1500 * np.exp(-t / 0.18) + 260
    puff = tvf(colored(dur, rng, -2.5), "lp", fc, 0.7) * np.sin(np.pi / 2 * np.clip(t / 0.015, 0, 1)) ** 2 * np.exp(-t / 0.11)
    m = Bus(dur)
    m.add(puff, 0.0, 1.0)
    m.add(bp(white(dur, rng), 1800, 4200) * np.exp(-t / 0.05) * np.clip(t / 0.008, 0, 1), 0.0, 0.2)
    m.add(crackle(dur, rng, 40 * np.exp(-t / 0.15), decay=0.0015, f_lo=500, f_hi=2500), 0.03, 0.1)
    return fit(m.out(), n)


# =========================================================================== main
def build_sfx(assets, names, wav_dir=None):
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            t0 = time.time()
            x = render_effect(name, v)
            out = f"magic/{name}{v + 1}"
            write_ogg(os.path.join(assets, "sounds", out + ".ogg"), x)
            if wav_dir:
                write_wav(os.path.join(wav_dir, out + ".wav"), x)
            print(f"{out:30s} {len(x) / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  rms {to_db(rms(x)):6.1f}  "
                  f"{time.time() - t0:5.2f}s", flush=True)


GAP_VARIANT, GAP_SOUND = 0.6, 1.7  # seconds of quiet between a sound's variants, and between one sound and the next


def listen_through(assets, names, path):
    """one MP3 to listen to on a phone: every sound that was just written, in order, each variant in turn, a gap
    between, and a chapter mark (the sound's name) at each. The files are read back from disk, so it is what the game
    will play"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        n_var = RECIPES[name]["variants"]
        for v in range(n_var):
            x = read_audio(os.path.join(assets, "sounds", f"magic/{name}{v + 1}.ogg"))[0]
            gap = GAP_VARIANT if v < n_var - 1 else GAP_SOUND
            marks.append((at, at + len(x) / SR + gap, name if n_var == 1 else f"{name} {v + 1} of {n_var}"))
            pieces += [x, np.zeros(ns(gap))]
            at += len(x) / SR + gap
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark magic sounds\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\ntitle={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)  # (encoded aside, then copied: ffmpeg seeks back in a file to finish its header)
    print(f"{path}  {at:.1f}s, {len(marks)} sounds")


def main():
    ap = argparse.ArgumentParser(description="Guildmark's magic: the sorcery and prayer sound effects")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated effect names")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    a = ap.parse_args()
    names = list(RECIPES)
    if a.only:
        names = [s.strip()[len("magic/"):] if s.strip().startswith("magic/") else s.strip()
                 for s in a.only.split(",") if s.strip()]
        bad = [s for s in names if s not in RECIPES]
        if bad:
            ap.error(f"unknown: {', '.join(bad)} (effects: {', '.join(RECIPES)})")
    build_sfx(a.assets, names, a.wav)
    if a.mp3:
        listen_through(a.assets, names, a.mp3)


if __name__ == "__main__":
    main()
