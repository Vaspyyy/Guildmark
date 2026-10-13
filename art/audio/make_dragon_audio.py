"""The Unbowed, the fire-drake of the high peaks: its sound effects and its battle theme, made from nothing.

usage (from the repo root):
    python3 -B art/audio/make_dragon_audio.py src/main/resources/assets/guildmark [--work DIR] [--wav DIR]
        [--only roar,breath,music] [--sf2 FluidR3_GM.sf2]

Writes the effects as mono 44.1 kHz Ogg Vorbis to <assets>/sounds/dragon/<name><n>.ogg and the theme to
<assets>/sounds/music/dragon.ogg. --only takes effect names (roar, wing_flap, ...) and/or music. --wav also
writes WAV copies; --work keeps the theme's per-stem MIDI and WAV renders (default build/music_work).

The effects are synthesised with sfxkit.py, like make_sfx.py's. The voice is a few rough glottal sources an
octave below a man's, through a vocal tract nearly twice as long, saturated, with a growl's flutter on it, a
deep chest pulse under it and a shriek riding on top; the fire is shaped noise, flicker and crackle; the wings
are low pushes of air and leather snapping taut. The theme is composed in code and played by FluidSynth
through make_music.py's machinery (parts, stems, hall, mix).
"""
import argparse
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403
import make_music as mm  # noqa: E402

RECIPES = {}


def recipe(name, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, rms_db=None):
    """one effect, written as dragon/<name><n>.ogg for n = 1..variants (the same contract as make_sfx.py).
    rms_db: the loudness the finished file should have (its RMS, with its peak at peak_db): the effect is soft-limited
    just enough that every variant, however sharp its attack, lands there; the other creatures' sounds sit at the
    same -3 dBFS peaks"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db, rms_db=rms_db)
        return fn
    return deco


def render_effect(name, v):
    """one variant of one effect, finished (levelled, trimmed, faded, peak at peak_db)"""
    r = RECIPES[name]
    x = r["fn"](v, rng_for("dragon/" + name, v))
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]

    def finish(y):
        return finalize(y, peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])

    y = finish(x)
    if not r["rms_db"] or to_db(rms(y)) >= r["rms_db"]:  # (a sound already denser than asked is left alone)
        return y
    x = x / (np.max(np.abs(x)) + 1e-12)
    lo, hi = 0.1, 1.0
    for _ in range(12):  # the soft limit that gives the asked RMS: lower ceiling, denser sound
        c = float(np.sqrt(lo * hi))
        y = finish(limiter(x, c, 0.002, 0.05))
        if to_db(rms(y)) < r["rms_db"]:
            hi = c
        else:
            lo = c
    return finish(limiter(x, float(np.sqrt(lo * hi)), 0.002, 0.05))


def nrm(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def wander(dur, rng, rate, depth):
    """a slow random drift about 1 (within +-depth): pitch and level that are never quite still"""
    return 1 + depth * (2 * smooth_random(dur, rng, rate) - 1)


def flutter(dur, rng, rate, depth, sharp=1.6, drift=0.18):
    """a growl's flutter: the level pulsing at about `rate` Hz, its speed wandering"""
    n = ns(dur)
    r = rate * wander(dur, rng, 2.5, drift)
    m = (0.5 + 0.5 * np.sin(phase_from_freq(r, n) + rng.uniform(0, 6.28))) ** sharp
    return 1 - depth * m


# =========================================================================== the voice
def throat(dur, rng, f0, va, vb, scale=0.56, curve=None, voices=3, spread=0.013, jitter=0.06, shimmer=0.3,
           subharm=0.45, fry=0.04, breath=0.9, drive_amt=4.0, growl_hz=24.0, growl=0.35, rasp=0.22, lpf=5200.0):
    """the dragon's voice: a few rough glottal sources a hair apart (one huge voice is never one clean
    oscillator) through a vowel tract nearly twice a man's, saturated, with the growl's flutter on it and the
    rasp of air tearing through"""
    n = ns(dur)
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,))
    src = np.zeros(n)
    for k in range(voices):
        det = 1 + spread * (k - (voices - 1) / 2)
        f = f0 * det * wander(dur, rng, 3.0 + 1.7 * k, 0.025)
        src += (1.0 if k == 0 else 0.7) * glottal(dur, f, rng, jitter=jitter, shimmer=shimmer, subharm=subharm,
                                                  breath=breath, fry=fry)
    y = formants(src, va, vb, scale=scale, curve=curve, gains=(1.0, 0.7, 0.3, 0.12))
    y = drive(nrm(y), drive_amt, asym=0.12) * flutter(dur, rng, growl_hz, growl)
    r = bp(white(dur, rng), 700, 3400) * smooth_random(dur, rng, 45, 0.3, 1.0)
    y = nrm(y) + rasp * nrm(r) * np.abs(nrm(y)) ** 0.5
    return lp(y, lpf)


def chest(dur, rng, f0, scale=0.5, growl_hz=13.0, growl=0.3, lpf=420.0, fry=0.03):
    """the growl's core: a slow, deep pulse from a chest the size of a wagon, felt as much as heard"""
    g = glottal(dur, f0 * wander(dur, rng, 2.2, 0.03), rng, jitter=0.05, shimmer=0.25, subharm=0.25, breath=0.3,
                fry=fry)
    y = formants(g, "u", "o", scale=scale, gains=(1.0, 0.5, 0.15, 0.05))
    y = drive(nrm(y), 2.2) * flutter(dur, rng, growl_hz, growl)
    return lp(y, lpf, order=4)


def scream(dur, rng, f0, scale=1.7, curve=None, jitter=0.045, shimmer=0.25, subharm=0.45, drive_amt=3.5, rasp=0.3,
           lpf=7000.0):
    """the cry a throat gives when it is forced up an octave and a half: two rough pulse sources a hair apart at
    f0 (a few hundred Hz), through a vowel tract a third as long as the bellow's, saturated and torn by noise. Its
    pitch is never still, and nothing in it is a clean line."""
    n = ns(dur)
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,))
    src = np.zeros(n)
    for k in range(2):
        f = f0 * (1 + 0.011 * (k - 0.5)) * wander(dur, rng, 4.0 + 1.3 * k, 0.03)
        src += (1.0 if k == 0 else 0.7) * glottal(dur, f, rng, jitter=jitter, shimmer=shimmer, subharm=subharm,
                                                  breath=0.5, fry=0.02, open_q=0.5)
    y = formants(src, "a", "e", scale=scale, curve=curve, gains=(1.0, 0.8, 0.5, 0.25))
    y = drive(nrm(y), drive_amt, asym=0.1)
    r = bp(white(dur, rng), 1200, 5200) * smooth_random(dur, rng, 55, 0.2, 1.0)
    y = nrm(y) + rasp * nrm(r) * np.abs(nrm(y)) ** 0.4
    return lp(y, lpf)


def tear(dur, rng, fc0=3000.0, fc1=1100.0, q=3.0, am_hz=52.0, am_depth=0.75):
    """a shriek's tearing: noise through a falling resonance (and its octave), the level torn by a rough pulse"""
    n = ns(dur)
    u = np.linspace(0, 1, n, endpoint=False)
    fc = fc0 * (fc1 / fc0) ** (u ** 0.8) * wander(dur, rng, 6.0, 0.04)
    nz = white(dur, rng)
    y = tvf(tvf(nz, "bp", fc, q), "bp", fc * 1.03, q) + 0.5 * tvf(tvf(nz, "bp", fc * 2.0, q), "bp", fc * 2.05, q)
    pulse = (0.5 + 0.5 * np.sin(phase_from_freq(am_hz * wander(dur, rng, 4.0, 0.3), n) + rng.uniform(0, 6.28))) ** 2.0
    return nrm(nrm(y) * (1 - am_depth + am_depth * pulse) * smooth_random(dur, rng, 70, 0.4, 1.0))


def fry_voice(dur, rng, f0, rate_hz=17.0, growl=0.6):
    """the voice breaking down into a low, rattling vocal fry: periods skipped and stumbling"""
    g = glottal(dur, f0, rng, jitter=0.12, shimmer=0.5, subharm=0.6, breath=0.5, fry=0.45)
    y = formants(g, "uh", "o", scale=0.58, gains=(1.0, 0.6, 0.25, 0.1))
    return lp(drive(nrm(y), 3.0) * flutter(dur, rng, rate_hz, growl), 2200)


def bark(dur, rng, lo=250.0, hi=3500.0, decay=0.03):
    """the explosive start of a roar: air and voice let go at once"""
    t = tvec(dur)
    return nrm(bp(white(dur, rng), lo, hi) * np.exp(-t / decay) * np.clip(t / 0.002, 0, 1))


def smoke(dur, rng, lo=1600.0, hi=6500.0, rate=7.0):
    """smoke and hot air hissing out between the teeth and through the nostrils"""
    return bp(colored(dur, rng, -0.5), lo, hi) * smooth_random(dur, rng, rate, 0.35, 1.0)


def embers(dur, rng, rate, lo=900.0, hi=7500.0, pops=0.0):
    """fire crackle: fine sparks, and (pops > 0) the odd bigger snap of something burning through"""
    y = crackle(dur, rng, rate, decay=0.0008, f_lo=lo, f_hi=hi)
    y = nrm(y)
    if pops:
        rate_p = np.asarray(rate, dtype=float) * 0.06
        p = crackle(dur, rng, rate_p, decay=0.0025, f_lo=350, f_hi=3200, size_var=1.0)
        y = y + pops * nrm(p)
    return y


# =========================================================================== space
def outdoors(x, wet=0.12, seed=301):
    """open ground on the high peaks: a slap back off the rocks and little else"""
    return reverb(x, wet=wet, rt60=1.5, predelay=0.045, hf_damp=0.7, seed=seed, early_span=0.08)


def mountain(x, wet=0.22, rt60=2.6, seed=311, echoes=((0.31, 0.16), (0.74, 0.1), (1.22, 0.05))):
    """a voice thrown across a valley among the peaks: a long, dark tail and the cliffs answering it"""
    y = reverb(x, wet=wet, rt60=rt60, predelay=0.05, hf_damp=0.65, seed=seed, early_span=0.12)
    m = Mix(len(y) / SR)
    m.add(y, 0.0, 1.0)
    for i, (d, g) in enumerate(echoes):
        e = lp(hp(x, 90), 2400 / (1 + 0.7 * i))
        e = reverb(e, wet=0.7, rt60=0.9, predelay=0.01, hf_damp=0.7, seed=seed + 1 + i, dry=0.5)
        m.add(e, d, g)
    return m.out()


# =========================================================================== bodies, air, ground
def air_thump(dur, rng, f0=50.0, f1=30.0, rise=0.03, decay=0.1, noise=0.6, nlp=240.0):
    """a great push of air: a low pressure swell with a soft front, no click"""
    t = tvec(dur)
    a = np.sin(np.pi / 2 * np.clip(t / rise, 0, 1)) ** 2
    env = a * np.exp(-np.maximum(0, t - rise) / decay)
    body = sine_sweep(dur, f0, f1, 3.0) * env
    nz = lp(brown(dur, rng), nlp, order=4) * env
    return nrm(nrm(body) + noise * nrm(nz))


def membrane(dur, rng, rate=32.0, lo=110.0, hi=900.0, decay=0.12, snap=0.3):
    """a wing's leather going taut at the bottom of the stroke: a dull snap and a short, slapping flutter"""
    t = tvec(dur)
    ph = phase_from_freq(rate * wander(dur, rng, 5.0, 0.15), len(t)) + rng.uniform(0, 6.28)
    fl = (0.5 + 0.5 * np.sin(ph)) ** 2.5
    env = np.clip(t / 0.005, 0, 1) * np.exp(-t / decay)
    y = bp(colored(dur, rng, -2.0), lo, hi) * fl * env
    sn = bp(white(dur, rng), 250, 2200) * np.exp(-t / 0.007)
    return nrm(nrm(y) + snap * nrm(sn))


def clods(dur, rng, count, start=0.0, spread=0.3, f_lo=220.0, f_hi=1100.0):
    """earth and grit thrown up, pattering back down"""
    m = Mix(dur)
    for _ in range(count):
        tt = start + rng.exponential(spread)
        if tt > dur - 0.04:
            continue
        ex = lp(white(0.004, rng), 3000)
        f = rng.uniform(f_lo, f_hi)
        c = resonators(fit(ex, ns(0.06)), [f, f * 1.6], [0.025, 0.015], [1, .4])
        m.add(nrm(c), tt, rng.uniform(0.2, 0.7) * np.exp(-(tt - start) / (spread * 1.5)))
    return fit(m.out(), ns(dur))


def pebbles(dur, rng, count, start=0.04, spread=0.25, f_lo=1100.0, f_hi=4200.0):
    """stones skittering away"""
    m = Mix(dur)
    for _ in range(count):
        tt = start + rng.exponential(spread)
        if tt > dur - 0.05:
            continue
        f = rng.uniform(f_lo, f_hi)
        p = resonators(fit(bp(white(0.002, rng), 800, 8000), ns(0.05)), [f, f * 1.47, f * 2.3],
                       [0.03, 0.02, 0.015], [1, .6, .4])
        m.add(nrm(p), tt, rng.uniform(0.2, 0.6) * np.exp(-(tt - start) / (spread * 1.8)))
    return fit(m.out(), ns(dur))


def rock(dur, rng, modes=(210, 380, 620, 1040, 1700), t60=(0.09, 0.07, 0.05, 0.04, 0.03)):
    """the knock of stone breaking under a blow"""
    ex = crackle(0.03, rng, 9000, decay=0.001, f_lo=300, f_hi=6000)
    return nrm(resonators(fit(ex, ns(dur)), list(modes), list(t60), [1, .9, .7, .5, .35]))


def burst(dur, rng, lo=2000.0, hi=12000.0, decay=0.003):
    t = tvec(dur)
    return nrm(bp(white(dur, rng), lo, min(hi, 20000)) * np.exp(-t / decay))


# =========================================================================== voice
@recipe("roar", variants=2, fade_out=0.6, max_len=[3.38, 3.2], tail_db=-70, rms_db=-15.0)
def roar(v, rng):
    """the challenge: a bark and a deep chest bellow under a ragged, saturated voice that opens as the jaw drops, a
    cry torn out of the top of it at the peak, the voice breaking into a rattle, and the mountains throwing it back"""
    dur = [3.4, 3.2][v]
    dv = [2.25, 2.12][v]
    t = tvec(dv)
    pts = [[(0, 66), (0.04, 98), (0.16, 80), (0.6, 100), (1.0, 113), (1.55, 108), (1.85, 92), (dv, 54)],
           [(0, 80), (0.04, 124), (0.2, 96), (0.34, 118), (0.7, 128), (1.2, 118), (1.6, 100), (dv, 58)]][v]
    f0 = uniform_filter1d(env_points(pts, dv), ns(0.05)) * wander(dv, rng, 1.5, 0.02)
    f0 = f0 * (1 + 0.012 * np.sin(2 * np.pi * [5.3, 6.1][v] * t))
    for tc, dc_, up in [[(0.62, 0.09, 0.42), (1.32, 0.07, 0.3)], [(0.47, 0.08, 0.4), (1.22, 0.1, 0.34)]][v]:
        f0 = f0 * (1 + up * np.clip(np.minimum(t - tc, tc + dc_ - t) / 0.012, 0, 1))  # a crack: the voice flips up
    jaw = env_points([(0, 0), (0.3, 1.0), (1.5, 0.95), (dv, 0.3)], dv)
    amp = env_points([[(0, 0), (0.035, 0.85), (0.14, 0.6), (0.45, 1.0), (0.85, 0.75), (1.3, 1.0), (1.7, 0.92),
                       (1.95, 0.5), (dv, 0)],
                      [(0, 0), (0.035, 0.9), (0.15, 0.66), (0.36, 1.0), (0.7, 0.8), (1.05, 1.0), (1.6, 0.9),
                       (1.85, 0.5), (dv, 0)]][v], dv) * smooth_random(dv, rng, 2.5, 0.85, 1.0)  # the beast pushing air
    mid = throat(dv, rng, f0, "o", "a", scale=0.62, curve=jaw, subharm=[0.5, 0.55][v], drive_amt=4.5,
                 growl_hz=[25, 28][v], growl=0.5, rasp=0.2, jitter=0.04, shimmer=0.25, breath=0.6)
    core = chest(dv, rng, f0 / 2)
    air_ = tvf(colored(dv, rng, -1.5), "bp", 300 + 400 * jaw, 0.8)
    # the cry on top: a scream-register voice and the tearing of the air round it
    ds = [1.45, 1.2][v]
    ts = tvec(ds)
    fs = env_points([[(0, 250), (0.25, 330), (0.6, 410), (0.8, 380), (ds, 215)],
                     [(0, 300), (0.18, 400), (0.45, 470), (0.7, 420), (ds, 240)]][v], ds)
    fs = uniform_filter1d(fs, ns(0.03)) * wander(ds, rng, 3.0, 0.04)
    cry = (nrm(scream(ds, rng, fs, curve=np.clip(ts / ds, 0, 1))) + 0.5 * nrm(tear(ds, rng))) * \
        bell(ds, [0.38, 0.3][v], 1.7, 1.5) ** 1.3
    # the break: the voice stumbles down into a rattle
    df = 1.2
    rat = nrm(fry_voice(df, rng, [44, 48][v] - 8 * tvec(df) / df)) * np.clip(tvec(df) / 0.15, 0, 1) * \
        np.exp(-tvec(df) / 0.6)
    m = Mix(dv + 0.3)
    m.add(nrm(mid), 0.0, 1.0)
    m.add(nrm(core), 0.0, 0.5)
    m.add(cry, [0.6, 0.42][v], 0.55)
    m.add(nrm(air_), 0.0, 0.35)
    y = fit(nrm(fit(m.out(), ns(dv))) * amp, ns(dv + 0.3))
    m2 = Mix(dv + 0.3)
    m2.add(y, 0.0, 1.0)
    m2.add(rat, [1.55, 1.5][v], 0.35)
    m2.add(bark(0.1, rng), 0.0, 0.3)
    y = lp(drive(nrm(m2.out()), 2.2), 7500)
    y = nrm(limiter(y / np.max(np.abs(y)), 0.55, lookahead=0.004, release=0.15))
    return fit(mountain(fit(y, ns(dur - 0.6)), wet=0.3, rt60=3.2, seed=311 + v,
                        echoes=((0.34, 0.2), (0.8, 0.13), (1.3, 0.07))), ns(dur))


@recipe("growl", variants=3, fade_out=0.35, max_len=[1.9, 1.75, 2.1], tail_db=-66, rms_db=-17.5)
def growl(v, rng):
    """idle: a deep rumbling growl through a throat full of smoke; a growl with a snort of smoke on the end;
    a slow rattling purr like a crocodile's bellow"""
    dur = [1.9, 1.75, 2.1][v]
    dv = [1.45, 1.05, 1.65][v]
    t = tvec(dv)
    u = t / dv
    m = Mix(dur)
    if v == 2:
        f0 = 58 + 8 * np.sin(np.pi * u) - 6 * u
        y = throat(dv, rng, f0, "u", "o", scale=0.52, subharm=0.65, fry=0.25, breath=0.9, drive_amt=3.0,
                   growl_hz=8.5, growl=0.7, rasp=0.15, lpf=2400)
        core = chest(dv, rng, f0 / 2, growl_hz=8.5, growl=0.6, fry=0.2)
    else:
        f0 = [70, 64][v] + [14, 10][v] * np.sin(np.pi * np.clip(u / 0.8, 0, 1)) ** 1.3 - 8 * u
        y = throat(dv, rng, f0, "o", "uh", scale=0.54, subharm=0.6, fry=0.1, breath=0.95, drive_amt=3.2,
                   growl_hz=[17, 20][v], growl=0.45, rasp=0.2, lpf=2800)
        core = chest(dv, rng, f0 / 2)
    env = np.clip(t / [0.25, 0.12, 0.3][v], 0, 1) ** 1.5 * np.clip((dv - t) / (dv * 0.45), 0, 1) ** 1.3
    m.add((nrm(y) + 0.8 * nrm(core)) * env, 0.0, 1.0)
    hiss = smoke(dv + 0.3, rng) * bell(dv + 0.3, 0.6, 1.5, 1.4)
    m.add(nrm(hiss), 0.0, 0.07)
    if v == 1:  # a snort of smoke through the nostrils, and a few embers in it
        ds = 0.45
        ts = tvec(ds)
        puff = formants(colored(ds, rng, -0.8), "u", "o", scale=0.6) + 0.5 * smoke(ds, rng, 1200, 5000, 12)
        puff = lp(puff, 6000) * np.clip(ts / 0.015, 0, 1) * np.exp(-ts / 0.13)
        m.add(nrm(puff), dv - 0.08, 0.6)
        m.add(nrm(embers(ds, rng, 120 * np.exp(-ts / 0.15), pops=0.5)) * np.exp(-ts / 0.2), dv - 0.05, 0.12)
    return outdoors(fit(m.out(), ns(dur)), 0.12, seed=321 + v)


@recipe("snore", variants=2, fade_out=0.45, max_len=[3.2, 3.3], tail_db=-66, rms_db=-22.5)
def snore(v, rng):
    """asleep: a slow rumbling snore drawn in through a long throat, then a breath out with smoke hissing
    from the nostrils"""
    dur = [3.2, 3.3][v]
    di = [1.35, 1.2][v]
    ti = tvec(di)
    ui = ti / di
    flap = [24, 28][v] * (1 + 0.12 * np.sin(np.pi * ui))
    pulses = (0.5 + 0.5 * np.sin(phase_from_freq(flap, len(ti)))) ** 3
    src = colored(di, rng, -1.5) * pulses + 0.5 * glottal(di, flap * 2, rng, jitter=0.1, shimmer=0.4, breath=0.8,
                                                          fry=0.5)
    inh = formants(src, "u", "o", scale=0.5, curve=np.clip(ui, 0, 1))
    inh = lp(inh, 1800) * np.sin(np.pi * np.clip(ui, 0, 1)) ** 1.1
    rumble = lp(brown(di, rng), 110, order=4) * np.sin(np.pi * ui) ** 1.5
    de = [1.55, 1.75][v]
    te = tvec(de)
    exh_env = np.clip(te / 0.12, 0, 1) * np.exp(-te / 0.42)
    exh = formants(colored(de, rng, -1.2), "o", "uh", scale=0.55) * exh_env
    hiss = smoke(de, rng, 1500, 6500, 6) * np.clip(te / 0.2, 0, 1) * np.exp(-te / 0.6)
    low = lp(brown(de, rng), 100, order=4) * exh_env
    m = Mix(dur)
    m.add(nrm(inh), 0.0, 1.0)
    m.add(nrm(rumble), 0.0, 0.5)
    at = di + [0.12, 0.08][v]
    m.add(nrm(lp(exh, 1600)), at, 0.5)
    m.add(nrm(hiss), at + 0.05, 0.22)
    m.add(nrm(low), at, 0.45)
    if v == 1:  # an ember or two stirring in the smoke
        m.add(nrm(embers(de, rng, 25 * np.exp(-te / 0.5), pops=0.6)) * np.exp(-te / 0.6), at + 0.05, 0.08)
    return outdoors(fit(m.out(), ns(dur)), 0.1, seed=331 + v)


@recipe("hurt", variants=3, fade_out=0.15, max_len=[0.75, 0.7, 0.85], tail_db=-64, rms_db=-17.5)
def hurt(v, rng):
    """a pained snarl: a sharp, torn cry out of the throat, dropping fast, teeth bared"""
    dur = [0.75, 0.7, 0.85][v]
    dv = [0.5, 0.45, 0.6][v]
    t = tvec(dv)
    f0 = [150, 168, 128][v] * np.exp(-t / [0.45, 0.35, 0.6][v]) * (1 + 0.08 * np.clip(t / 0.04, 0, 1))
    y = throat(dv, rng, f0, "a", "uh", scale=0.6, curve=np.clip(t / dv, 0, 1), subharm=0.5, fry=0.06,
               drive_amt=5.0, growl_hz=[30, 34, 26][v], growl=0.35, rasp=0.35, lpf=6000)
    core = chest(dv, rng, f0 / 2, growl_hz=20)
    scr = (nrm(scream(dv, rng, f0 * [2.7, 2.9, 2.5][v], curve=np.clip(t / dv, 0, 1))) + 0.4 * nrm(tear(dv, rng, 2600, 1300))) * \
        np.exp(-t / [0.16, 0.12, 0.1][v])
    env = np.clip(t / 0.008, 0, 1) * np.exp(-np.maximum(0, t - 0.07) / [0.17, 0.15, 0.22][v])
    m = Mix(dur)
    m.add((nrm(y) + 0.55 * nrm(core) + [0.3, 0.35, 0.15][v] * nrm(scr)) * env, 0.0, 1.0)
    hiss = bp(white(0.12, rng), 2500, 7500) * np.exp(-tvec(0.12) / 0.03)  # air through the teeth
    m.add(nrm(hiss), 0.0, 0.12)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.4)
    return fit(outdoors(y, 0.12, seed=341 + v), ns(dur))


@recipe("death", variants=1, fade_out=0.6, max_len=4.2, tail_db=-72, rms_db=-19.5)
def death(v, rng):
    """the last roar: a great cry that cracks and breaks, sinks into a groan, and goes out of it in a long,
    rattling breath with the last of the smoke"""
    dur = 4.2
    m = Mix(dur)
    # the cry, and where it breaks: the pitch falls away a fourth and the voice splits into rough halves
    dc = 1.55
    t = tvec(dc)
    brk = 0.78
    f0 = env_points([(0, 92), (0.4, 118), (brk, 112), (brk + 0.06, 80), (dc, 62)], dc)
    f0 = uniform_filter1d(f0, ns(0.03)) * wander(dc, rng, 2.0, 0.025)
    jaw = env_points([(0, 0), (0.3, 1.0), (1.0, 0.85), (dc, 0.4)], dc)
    clean = throat(dc, rng, f0, "o", "a", scale=0.56, curve=jaw, subharm=0.4, fry=0.04, drive_amt=4.5,
                   growl_hz=24, growl=0.3)
    broken = throat(dc, rng, f0, "o", "a", scale=0.55, curve=jaw, subharm=0.8, fry=0.3, jitter=0.12,
                    shimmer=0.45, drive_amt=5.0, growl_hz=15, growl=0.55, rasp=0.4)
    x = np.clip((t - brk) / 0.06, 0, 1)
    voice = nrm(clean) * (1 - x) + nrm(broken) * x
    core = chest(dc, rng, f0 / 2, growl_hz=11, growl=0.4)
    scr_f = f0 * 5.3 * (1 + 0.25 * np.exp(-((t - brk) / 0.05) ** 2))  # the shriek cracks upward, then dies
    scr = (nrm(scream(dc, rng, scr_f * 0.6, jitter=0.06, curve=np.clip(t / dc, 0, 1))) + 0.45 * nrm(tear(dc, rng, 2800, 1000))) * \
        bell(dc, 0.3, 1.5, 1.2) * np.clip((brk + 0.25 - t) / 0.25, 0, 1)
    amp = env_points([(0, 0), (0.08, 0.6), (0.22, 1.0), (brk, 0.95), (brk + 0.1, 0.8), (dc, 0.0)], dc)
    m.add((voice + 0.7 * nrm(core) + 0.28 * nrm(scr)) * amp, 0.0, 1.0)
    # the groan it sinks into
    dg = 1.3
    tg = tvec(dg)
    fg = 64 * np.exp(-tg / 1.1) - 6 * tg / dg
    groan = throat(dg, rng, fg, "a", "u", scale=0.54, curve=np.clip(tg / dg, 0, 1), subharm=0.6, fry=0.35,
                   jitter=0.1, drive_amt=3.0, growl_hz=12, growl=0.5, rasp=0.2, lpf=2500)
    groan += 0.6 * nrm(chest(dg, rng, fg / 2, growl_hz=9, growl=0.5, fry=0.2))
    m.add(nrm(groan) * np.clip(tg / 0.15, 0, 1) * np.clip((dg - tg) / 0.7, 0, 1) ** 1.3, dc - 0.2, 0.62)
    # the rattling breath going out of it, smoke in the last of it
    dr = 1.7
    tr = tvec(dr)
    rate = 19 - 8 * tr / dr
    rat = (0.5 + 0.5 * np.sin(phase_from_freq(rate, len(tr)) + rng.uniform(0, 6))) ** 2.2
    br = formants(colored(dr, rng, -1.0), "uh", "o", scale=0.6) * (0.35 + 0.65 * rat)
    br += 0.4 * nrm(glottal(dr, rate * 2, rng, jitter=0.15, shimmer=0.5, breath=0.9, fry=0.5)) * rat
    benv = np.clip(tr / 0.2, 0, 1) * np.exp(-tr / 0.7)
    m.add(nrm(lp(br, 2600)) * benv, dc + 0.85, 0.75)
    m.add(nrm(smoke(dr, rng, 1500, 6000, 5)) * benv * np.clip(tr / 0.5, 0, 1), dc + 0.95, 0.16)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.3)
    return fit(mountain(y, wet=0.22, seed=351), ns(dur))


# =========================================================================== body
def wingbeat(dur, rng, at=0.07, size=1.0, rate=32.0, leather=0.5, swoosh=0.5, thump=0.55):
    """one downbeat of a wing as wide as a house: the stroke's dark whoosh, the air shoved down in a low thump
    at `at`, the leather cracking taut and the membrane slapping and fluttering"""
    m = Mix(dur)
    ws = at + 0.34 * size
    sw = whoosh(ws, rng, peak=min(0.6, at / ws + 0.05), f_lo=90, f_hi=700, q=0.7, power=2.0, rise=1.3, fall=1.6,
                body=0.9, body_lp=220, color=-3.0, doppler=0.0)
    m.add(nrm(sw), 0.0, swoosh)
    m.add(air_thump(dur, rng, 64 / size, 36 / size, rise=0.025 * size, decay=0.09 * size, noise=0.9, nlp=420),
          max(0, at - 0.03), thump)
    m.add(membrane(0.45, rng, rate=rate, lo=170, hi=1500, decay=0.11 * size, snap=0.35), at + 0.004, leather)
    crack = bp(white(0.04, rng), 700, 3800) * np.exp(-tvec(0.04) / 0.008) * np.clip(tvec(0.04) / 0.001, 0, 1)
    m.add(nrm(crack), at - 0.004, 0.32)  # the leather going taut
    t = tvec(dur)
    gust = lp(brown(dur, rng), 260, order=4) * np.clip((t - at + 0.02) / 0.05, 0, 1) * \
        np.exp(-np.maximum(0, t - at) / 0.2)
    m.add(nrm(gust), 0.0, 0.5)
    return fit(m.out(), ns(dur))


@recipe("wing_flap", variants=3, fade_out=0.12, max_len=[0.65, 0.75, 0.6], tail_db=-64, rms_db=-17.5)
def wing_flap(v, rng):
    """a massive leathery downbeat: a low push of air you feel in the chest, the membrane slapping taut"""
    dur = [0.65, 0.75, 0.6][v]
    y = wingbeat(dur, rng, at=[0.07, 0.085, 0.06][v], size=[1.0, 1.2, 0.92][v], rate=[32, 27, 38][v],
                 leather=[0.55, 0.5, 0.65][v])
    return fit(outdoors(lp(y, 4200), 0.08, seed=361 + v), ns(dur))


@recipe("step", variants=3, fade_out=0.1, max_len=[0.5, 0.55, 0.45], tail_db=-64, rms_db=-15.5)
def step(v, rng):
    """a foot the size of a cart coming down on rock: a thud with a body to it (the ground ringing under the
    weight), gravel crunching, claws scraping as it settles"""
    dur = [0.5, 0.55, 0.45][v]
    t = tvec(dur)
    k = [1.0, 0.88, 1.12][v]  # the pitch of this foot
    # the blow: the foot and the ground ringing low, the pitch dropping as the stone gives
    body = modal(dur, [66 * k, 104 * k, 158 * k, 240 * k, 380 * k, 560 * k], [0.24, 0.2, 0.15, 0.11, 0.08, 0.06],
                 [0.55, 0.85, 1.0, 0.85, 0.6, 0.4], rng, glide=0.15)
    slap = lp(white(dur, rng), 1300) * np.exp(-t / 0.022) * np.clip(t / 0.0007, 0, 1)
    weight = sine_sweep(dur, 46 * k, 30, 2.0) * np.clip(t / 0.01, 0, 1) * np.exp(-t / 0.12)
    crunch = crackle(0.2, rng, 2600 * np.exp(-tvec(0.2) / 0.05), decay=0.0012, f_lo=250, f_hi=3200, size_var=1.0)
    m = Mix(dur)
    m.add(nrm(drive(nrm(body), 1.6)), 0.0, 1.0)
    m.add(nrm(slap), 0.0, 0.8)
    m.add(nrm(weight), 0.0, 0.14)
    m.add(nrm(crunch), 0.003, [0.36, 0.24, 0.38][v])
    m.add(clods(dur, rng, 8, start=0.02, spread=0.09, f_lo=200, f_hi=1200), 0.0, 0.3)
    m.add(pebbles(dur, rng, 5, start=0.03, spread=0.1, f_lo=1200, f_hi=3200), 0.0, 0.1)
    # the claws scraping as the foot settles: stick-slip grit ringing a few hard modes
    ds = [0.16, 0.2, 0.13][v]
    ts = tvec(ds)
    grit = crackle(ds, rng, 900, decay=0.0006, f_lo=600, f_hi=7000, size_var=0.5)
    kk = [1.0, 0.9, 1.12][v]
    ring = resonators(grit, [1750 * kk, 2620 * kk, 3900 * kk], [0.03, 0.025, 0.02], [1, .7, .5])
    scrape = (nrm(ring) + 0.6 * nrm(bp(white(ds, rng), 1200, 5500))) * np.sin(np.pi * ts / ds) ** 1.5
    m.add(nrm(scrape), [0.05, 0.07, 0.04][v], [0.14, 0.11, 0.12][v])
    y = fade(drive(nrm(fit(m.out(), ns(dur))), 2.6), fin=0.002)  # a soft front: a foot that size does not click
    return fit(outdoors(lp(y, 6500), 0.1, seed=371 + v), ns(dur))


@recipe("bite", variants=2, fade_out=0.08, max_len=[0.4, 0.42], tail_db=-62, rms_db=-17.5)
def bite(v, rng):
    """huge jaws snapping shut: a hollow clack of bone on bone (teeth meeting a hair apart), wet"""
    dur = [0.4, 0.42][v]
    m = Mix(dur)
    lead = 0.03
    snap_air = whoosh(lead + 0.02, rng, peak=0.85, f_lo=300, f_hi=2200, q=0.8, power=2.0, rise=1.2, fall=0.6,
                      color=-1.5, doppler=0.0)
    m.add(nrm(snap_air), 0.0, 0.18)
    k = [1.0, 0.86][v]
    modes = np.array([680, 1180, 1830, 2700, 3850]) * k
    for i, (dt, g) in enumerate(((0.0, 1.0), (0.0045, 0.7), (0.0095, 0.5))):
        ex = burst(0.004, rng, 1500, 14000, 0.0007)
        tooth = resonators(fit(ex, ns(0.15)), modes * (1 + 0.03 * i), [0.06, 0.05, 0.04, 0.03, 0.02], [1, .8, .6, .45, .3])
        m.add(nrm(tooth), lead + dt, 0.85 * g)
        m.add(burst(0.02, rng, 2500, 15000, 0.0012), lead + dt, 0.35 * g)
    jaw = thump(0.25, [150, 132][v], [70, 62][v], rng, decay=0.045, click=0.15, noise=0.6, nlp=900)
    m.add(nrm(jaw), lead, 0.75)
    # wet: a slap of spit and gum and a gristly tail
    dw = 0.22
    tw = tvec(dw)
    g = crackle(dw, rng, 4200 * np.exp(-tw / 0.05), decay=0.0012, f_lo=350, f_hi=2800, size_var=0.8)
    sw = tvf(colored(dw, rng, -2), "bp", 480 + 1400 * np.exp(-tw / 0.03), 2.2) * np.exp(-tw / 0.05)
    m.add(nrm(nrm(g) + 0.9 * nrm(sw)), lead + 0.004, 0.55)
    m.add(nrm(crackle(0.25, rng, 300 * np.exp(-tvec(0.25) / 0.08), decay=0.001, f_lo=500, f_hi=3000)), lead + 0.05,
          0.12)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.8, asym=0.05)
    return fit(outdoors(y, 0.09, seed=381 + v), ns(dur))


@recipe("swipe", variants=2, fade_out=0.12, max_len=[0.62, 0.68], tail_db=-64, rms_db=-18.0)
def swipe(v, rng):
    """a claw (or a tail) the weight of a tree trunk through the air: slow, deep and dark"""
    dur = [0.62, 0.68][v]
    peak = [0.52, 0.56][v]
    w = whoosh(dur, rng, peak=peak, f_lo=[65, 58][v], f_hi=[820, 700][v], q=0.75, power=2.4, rise=1.8, fall=1.3,
               whistle=[0.12, 0.0][v], whistle_mult=2.4, whistle_q=10, body=1.3, body_lp=170,
               flutter_hz=[12, 9][v], flutter_depth=0.45, color=-3.5, doppler=0.08)
    s = bell(dur, peak, 1.8, 1.3)
    air = lp(brown(dur, rng), 85, order=4) * s ** 2
    y = nrm(w) + 0.45 * nrm(air)
    return fit(outdoors(y, 0.1, seed=391 + v), ns(dur))


@recipe("slam", variants=2, fade_out=0.3, max_len=[1.25, 1.35], tail_db=-66, rms_db=-14.5)
def slam(v, rng):
    """the whole weight coming down on rock: a deep boom you feel and a ringing body you hear, stone cracking,
    rubble raining back"""
    dur = [1.25, 1.35][v]
    t = tvec(dur)
    k = [1.0, 0.9][v]
    imp = thump(1.1, [88, 80][v], [36, 32][v], rng, decay=[0.22, 0.25][v], click=0.35, noise=1.0, nlp=520,
                sweep_curve=3.0)
    imp = lp(drive(nrm(imp), 3.0), 1700)
    sub = sine_sweep(dur, 42, 28, 2.0) * np.clip(t / 0.012, 0, 1) * np.exp(-t / 0.3)
    # the ground and the foot ringing: a body to the boom that small speakers can still give
    body = modal(dur, [58 * k, 92 * k, 140 * k, 212 * k, 330 * k, 520 * k, 790 * k],
                 [0.55, 0.45, 0.34, 0.26, 0.18, 0.12, 0.08], [0.6, 0.9, 1.0, 0.9, 0.7, 0.5, 0.3], rng, glide=0.12)
    shatter = crackle(0.35, rng, 6500 * np.exp(-tvec(0.35) / 0.045), decay=0.001, f_lo=400, f_hi=8000, size_var=0.9)
    rubble = crackle(dur, rng, 700 * np.exp(-t / 0.2) + 50 * np.exp(-t / 0.6), decay=0.0028, f_lo=180, f_hi=3000,
                     size_var=1.0)
    rumble = lp(brown(dur, rng), 140, order=4) * np.clip(t / 0.03, 0, 1) * np.exp(-t / 0.5)
    dust = bp(pink(dur, rng), 300, 2500) * np.exp(-t / 0.45) * np.clip(t / 0.05, 0, 1)
    m = Mix(dur)
    m.add(burst(0.05, rng, 900, 12000, 0.004), 0.0, 0.5)
    m.add(nrm(imp), 0.001, 0.8)
    m.add(nrm(sub), 0.0, 0.22)
    m.add(nrm(drive(nrm(body), 1.8)), 0.0, 0.9)
    m.add(rock(0.35, rng, [[210, 380, 620, 1040, 1700], [190, 350, 580, 960, 1580]][v]), 0.001, 0.55)
    m.add(nrm(shatter), 0.002, 0.5)
    m.add(nrm(rubble), 0.015, 0.55)
    m.add(nrm(hp(rumble, 40)), 0.0, 0.3)
    m.add(pebbles(dur, rng, 16, start=0.05, spread=0.25), 0.0, 0.4)
    m.add(clods(dur, rng, 14, start=0.06, spread=0.25), 0.0, 0.35)
    m.add(nrm(dust), 0.0, 0.25)
    y = fade(drive(nrm(fit(m.out(), ns(dur))), 1.5), fin=0.002)
    return fit(reverb(y, wet=0.16, rt60=1.8, predelay=0.05, hf_damp=0.65, seed=401 + v, early_span=0.1), ns(dur))


# =========================================================================== fire
def torch(dur, rng, s, flutter_hz=22.0, flutter_depth=0.35):
    """a stream of fire: jet roar (broad, with a mid hump that opens with the swell s), a hiss on top,
    ragged flicker and the flame front's own flutter"""
    n = ns(dur)
    body = hp(lp(colored(dur, rng, -1.5), 2800), 110)
    core = tvf(pink(dur, rng), "bp", 450 + 900 * s, 0.7)
    hiss = bp(white(dur, rng), 2200, 7500)
    flick = smooth_random(dur, rng, 18, 0.5, 1.0) * smooth_random(dur, rng, 5, 0.7, 1.0)
    ph = phase_from_freq(flutter_hz * wander(dur, rng, 2.0, 0.25), n) + rng.uniform(0, 6.28)
    flut = 1 - flutter_depth * (0.5 + 0.5 * np.sin(ph))
    return (nrm(body) + 0.75 * nrm(core) + 0.16 * nrm(hiss) * s) * flick * flut


@recipe("inhale", variants=1, fade_out=0.04, max_len=1.1, tail_db=-64, rms_db=-17.5)
def inhale(v, rng):
    """the breath before the fire (22 ticks): air dragged down a throat like a cave, a rumble rising under it
    and the fire waking in the chest, crackling"""
    dur = 1.1
    t = tvec(dur)
    u = t / dur
    s = u ** 1.4
    stop = np.clip((1.06 - t) / 0.03, 0, 1)  # the breath stops dead, held
    suck = formants(colored(dur, rng, -1.2), "o", "a", scale=0.6, curve=u) * (0.15 + 0.85 * s ** 1.2)
    whistle = tvf(white(dur, rng), "bp", 520 + 460 * u, 9.0) * s ** 2
    rumble = lp(brown(dur, rng), 120, order=4) * s ** 1.3
    pulse = lp(glottal(dur, 22 + 14 * u, rng, jitter=0.12, shimmer=0.4, breath=0.4, fry=0.35), 320) * s ** 1.5
    glow = tvf(pink(dur, rng), "bp", 220 * 2 ** (2.0 * s), 0.7) * s ** 2 * smooth_random(dur, rng, 12, 0.5, 1.0)
    crk = embers(dur, rng, 30 + 700 * s ** 2, pops=0.6) * s
    m = Mix(dur)
    m.add(nrm(lp(suck, 5000)), 0.0, 0.75)
    m.add(nrm(whistle), 0.0, 0.1)
    m.add(nrm(rumble), 0.0, 0.6)
    m.add(nrm(pulse), 0.0, 0.4)
    m.add(nrm(glow), 0.0, 0.35)
    m.add(nrm(crk), 0.0, 0.2)
    y = drive(nrm(fit(m.out(), ns(dur)) * stop), 1.4)
    return fit(outdoors(y, 0.1, seed=411), ns(dur))


@recipe("breath", variants=2, fade_out=0.2, max_len=[1.9, 1.85], tail_db=-66, rms_db=-16.5)
def breath(v, rng):
    """the fire stream: a whoomph as it catches, then a roaring blowtorch over a low rumble, crackling and
    fluttering, the dragon's own growl behind it, guttering out at the end"""
    dur = [1.9, 1.85][v]
    t = tvec(dur)
    end = [1.25, 1.2][v]
    s = np.clip(t / 0.09, 0, 1) ** 0.8 * np.clip(1 - (t - end) / (dur - 0.08 - end), 0, 1) ** 1.3
    gusts = 1 - 0.55 * np.clip((t - end) / 0.2, 0, 1) * smooth_random(dur, rng, 9, 0.0, 1.0)  # sputtering
    m = Mix(dur)
    m.add(air_thump(0.5, rng, 92, 36, rise=0.012, decay=0.12, noise=0.9, nlp=500), 0.0, 0.75)
    m.add(burst(0.12, rng, 300, 6000, 0.03), 0.0, 0.35)
    st = torch(dur, rng, s, flutter_hz=[22, 26][v], flutter_depth=0.35) * s * gusts
    m.add(nrm(st), 0.0, 1.0)
    m.add(nrm(lp(brown(dur, rng), 110, order=4) * s), 0.0, 0.55)
    m.add(nrm(embers(dur, rng, 250 + 450 * s, pops=0.7) * s), 0.0, 0.2)
    dv = end + 0.3
    tv = tvec(dv)
    g = throat(dv, rng, [64, 70][v] * (1 + 0.1 * np.sin(np.pi * tv / dv)), "a", "o", scale=0.56, subharm=0.5,
               drive_amt=3.0, growl_hz=18, growl=0.4, lpf=1500)
    m.add(nrm(g) * bell(dv, 0.4, 1.4, 1.6), 0.03, 0.22)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.3)
    return fit(outdoors(lp(y, 9000), 0.14, seed=421 + v), ns(dur))


@recipe("fireball", variants=2, fade_out=0.15, max_len=[0.75, 0.82], tail_db=-64, rms_db=-16.5)
def fireball(v, rng):
    """spitting a bolt of fire: a whumph from deep in the throat, and the bolt roaring away"""
    dur = [0.75, 0.82][v]
    t = tvec(dur)
    m = Mix(dur)
    m.add(air_thump(0.4, rng, [86, 78][v], 34, rise=0.01, decay=0.08, noise=0.9, nlp=450), 0.0, 0.85)
    dc = 0.14
    cough = formants(colored(dc, rng, -1.0), "a", "a", scale=0.6) * np.clip(tvec(dc) / 0.006, 0, 1) * \
        np.exp(-tvec(dc) / 0.04)
    m.add(nrm(cough), 0.0, 0.45)
    m.add(burst(0.08, rng, 300, 5000, 0.02), 0.005, 0.3)
    # the bolt leaving: fire texture riding a whoosh whose band falls as it goes away
    away = np.clip((t - 0.02) / (dur - 0.02), 0, 1)
    s = np.clip((t - 0.01) / 0.05, 0, 1) * np.exp(-away / [0.38, 0.45][v])
    fc = 1500 * (380 / 1500) ** away
    rush = tvf(colored(dur, rng, -1.5), "bp", fc, 0.8) * s
    fire = torch(dur, rng, s, flutter_hz=[20, 24][v], flutter_depth=0.3) * s
    m.add(nrm(rush), 0.0, 0.65)
    m.add(nrm(tvf(fire, "lp", 1000 + 5000 * s, 0.7)), 0.0, 0.55)
    m.add(nrm(embers(dur, rng, 600 * s, pops=0.6) * s), 0.0, 0.16)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.4)
    return fit(outdoors(y, 0.12, seed=431 + v), ns(dur))


@recipe("fire_burst", variants=2, fade_out=0.3, max_len=[1.05, 1.15], tail_db=-66, rms_db=-18.0)
def fire_burst(v, rng):
    """a fire bolt bursting on the ground: a soft, swelling fwoom (no bang), the flames roaring up and
    settling, crackle and sizzle"""
    dur = [1.05, 1.15][v]
    t = tvec(dur)
    m = Mix(dur)
    a = np.clip(t / 0.018, 0, 1) ** 1.5
    cut = 180 + 3300 * np.clip(t / 0.06, 0, 1) * np.exp(-np.maximum(0, t - 0.06) / 0.18)
    fwoom = tvf(colored(dur, rng, -1.0), "lp", cut, 0.9) * a * np.exp(-t / 0.22)
    m.add(nrm(fwoom), 0.0, 1.0)
    m.add(air_thump(0.6, rng, [72, 64][v], 30, rise=0.02, decay=0.15, noise=0.8, nlp=300), 0.0, 0.85)
    s = np.clip(t / 0.04, 0, 1) * np.exp(-t / [0.35, 0.42][v])
    m.add(nrm(torch(dur, rng, s, flutter_hz=[18, 21][v], flutter_depth=0.3) * s), 0.0, 0.6)
    crk = embers(dur, rng, 2600 * np.exp(-t / 0.12) + 140 * np.exp(-t / 0.5), pops=0.8)
    m.add(nrm(crk) * np.clip(t / 0.01, 0, 1), 0.004, 0.24)
    sizzle = bp(white(dur, rng), 3000, 9000) * np.exp(-t / 0.3) * smooth_random(dur, rng, 30, 0.3, 1.0)
    m.add(nrm(sizzle), 0.01, 0.06)
    m.add(clods(dur, rng, 6, start=0.03, spread=0.12), 0.0, 0.12)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.4)
    return fit(outdoors(y, 0.15, seed=441 + v), ns(dur))


# =========================================================================== flight
@recipe("flyby", variants=2, fade_out=0.25, max_len=[1.6, 1.4], tail_db=-64, rms_db=-17.5)
def flyby(v, rng):
    """something huge passing close overhead: the air tearing round it, rising and falling as it goes by,
    and one great wingbeat as it passes"""
    dur = [1.6, 1.4][v]
    t = tvec(dur)
    pas = [0.55, 0.5][v]  # where it passes, as a fraction
    near = bell(dur, pas, 1.6, 1.4)
    rush = whoosh(dur, rng, peak=pas, f_lo=80, f_hi=1500, q=0.7, power=2.2, rise=1.6, fall=1.4, body=1.2,
                  body_lp=200, color=-2.5, doppler=0.16)
    low = lp(brown(dur, rng), 120, order=4) * near ** 2
    flap_d = 0.6
    wb = wingbeat(flap_d, rng, at=0.07, size=1.1, rate=30, leather=0.5, swoosh=0.3)
    m = Mix(dur)
    m.add(nrm(rush), 0.0, 1.0)
    m.add(nrm(low), 0.0, 0.6)
    t_flap = pas * dur - 0.12
    m.add(varispeed(wb, 1.08), t_flap - 0.07, 0.75)  # the beat on the way in: a touch high
    if v == 0:
        m.add(varispeed(wingbeat(flap_d, rng, at=0.07, size=1.0, rate=34, leather=0.4, swoosh=0.3), 0.9),
              pas * dur + 0.33, 0.35)
    y = fit(m.out(), ns(dur))
    flut = (0.5 + 0.5 * np.sin(phase_from_freq(31 * (1 + 0.1 * np.tanh((pas - t / dur) * 8)), len(t)))) ** 2
    y = y + 0.25 * nrm(bp(colored(dur, rng, -2), 150, 800) * flut * near ** 3)
    y = tvf(y, "lp", 900 + 7000 * near ** 1.5, 0.7)  # air takes the edge off it far away
    return fit(outdoors(y, 0.12, seed=451 + v), ns(dur))


# =========================================================================== main
def build_sfx(assets, names, wav_dir=None):
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            t0 = time.time()
            x = render_effect(name, v)
            out = f"dragon/{name}{v + 1}"
            write_ogg(os.path.join(assets, "sounds", out + ".ogg"), x)
            if wav_dir:
                write_wav(os.path.join(wav_dir, out + ".wav"), x)
            print(f"{out:34s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--sf2", default=mm.SF2_DEFAULT)
    ap.add_argument("--work", default="build/music_work")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    names, music = list(RECIPES), True
    if a.only:
        sel = [s.strip() for s in a.only.split(",") if s.strip()]
        music = any(s in ("music", "music/dragon") for s in sel)
        names = [s[len("dragon/"):] if s.startswith("dragon/") else s for s in sel if s not in ("music", "music/dragon")]
        bad = [s for s in names if s not in RECIPES]
        if bad:
            ap.error(f"unknown: {', '.join(bad)} (effects: {', '.join(RECIPES)}; or music)")
    build_sfx(a.assets, names, a.wav)
    if music:
        import make_dragon_music  # the theme is composed there: art/audio/make_dragon_music.py
        make_dragon_music.build(a.assets, a.sf2, a.work, a.wav)


if __name__ == "__main__":
    main()
