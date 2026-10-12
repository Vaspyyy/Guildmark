"""Guildmark combat + boss sound effects, fully synthesised (no samples).

usage (from the repo root):
    python3 art/audio/make_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/<group>/<name><n>.ogg.
--wav also writes 16-bit WAV copies (for listening / analysis). --only takes recipe
names such as combat/parry or a group prefix such as fallen_warden/.
"""
import argparse
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403

RECIPES = {}


def recipe(path, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0):
    def deco(fn):
        RECIPES[path] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out,
                             max_len=max_len, tail_db=tail_db)
        return fn
    return deco


def nrm(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def small_room(x, wet=0.12, seed=3):
    return reverb(x, wet=wet, rt60=0.35, predelay=0.004, hf_damp=0.55, seed=seed, early_span=0.02)


def mid_room(x, wet=0.15, seed=5):
    return reverb(x, wet=wet, rt60=0.8, predelay=0.012, hf_damp=0.5, seed=seed, early_span=0.035)


def hall(x, wet=0.2, rt60=1.8, seed=9, hf_damp=0.45):
    return reverb(x, wet=wet, rt60=rt60, predelay=0.022, hf_damp=hf_damp, seed=seed, early_span=0.06)


def hollow(x, delay=0.0031, fb=0.38, mix=0.3):
    """helmet / hollow-body resonance: a short metallic comb"""
    return comb(x, delay, fb, mix)


def clank(dur, f0, rng, kind="plate", n=12, t60_lo=0.3, t60_hi=0.05, tilt=0.55, beat=0.003, glide=0.0):
    """inharmonic metal ring with beating pairs; t60 falls from t60_lo (lowest mode) to t60_hi"""
    fr = metal_partials(f0, kind, n=n, rng=rng, spread=0.012)
    k = np.arange(len(fr))
    t60 = t60_lo * (t60_hi / t60_lo) ** (k / max(1, len(fr) - 1))
    amps = 1.0 / (1 + k) ** tilt * rng.uniform(0.6, 1.0, len(fr))
    y = modal(dur, fr, t60, amps, rng, glide=glide)
    if beat:
        y += 0.6 * modal(dur, fr * (1 + beat * rng.uniform(0.5, 1.5, len(fr))), t60, amps, rng, glide=glide)
    return nrm(y)


def burst(dur, rng, lo=2000, hi=12000, decay=0.003):
    t = tvec(dur)
    return nrm(bp(white(dur, rng), lo, min(hi, 20000)) * np.exp(-t / decay))


def squelch(dur, rng, f0=1600, f1=450, decay=0.05, density=3500, lo=350, hi=2600):
    """wet, gristly impact texture: dense decaying grains + a downward resonant sweep"""
    t = tvec(dur)
    dens = density * np.exp(-t / (decay * 1.6))
    g = crackle(dur, rng, dens, decay=0.0012, f_lo=lo, f_hi=hi, size_var=0.8)
    sw = tvf(colored(dur, rng, -2), "bp", f1 + (f0 - f1) * np.exp(-t / (decay * 0.7)), 2.2)
    sw *= np.exp(-t / decay)
    env = np.exp(-t / decay)
    return nrm(nrm(g) * 0.8 * env + 0.9 * nrm(sw))


def rattle(dur, rng, start, span, rate=28.0, f_lo=900, f_hi=3800, decay_tau=0.15, gain_jit=0.5):
    """armour rattling: rapid sequence of small plate clinks"""
    out = Mix(dur)
    tt = start
    while tt < start + span and tt < dur - 0.02:
        f = np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))
        L = min(dur - tt, 0.12)
        c = clank(L, f, rng, "plate", n=6, t60_lo=0.07, t60_hi=0.02, beat=0)
        g = np.exp(-(tt - start) / decay_tau) * rng.uniform(1 - gain_jit, 1.0)
        out.add(c, tt, g)
        tt += rng.uniform(0.6, 1.4) / rate
    return fit(out.out(), ns(dur))


# =========================================================================== combat
@recipe("combat/swing_light", variants=3, max_len=[0.33, 0.30, 0.35], fade_out=0.06)
def swing_light(v, rng):
    dur = [0.30, 0.27, 0.33][v]
    peak = [0.42, 0.37, 0.47][v]
    fhi = [5200, 6400, 4500][v]
    m = whoosh(dur, rng, peak=peak, f_lo=650, f_hi=fhi, q=1.25, power=2.2, rise=1.4, fall=1.15,
               whistle=[0.35, 0.5, 0.3][v], whistle_mult=[1.2, 1.35, 1.1][v], whistle_q=10, body=0.3, body_lp=700,
               body_hp=180, color=-1.0, flutter_hz=[0, 0, 38][v], flutter_depth=[0, 0, 0.22][v])
    edge = whoosh(dur, rng, peak=peak + 0.03, f_lo=2200, f_hi=9500, q=2.2, power=3.0, color=0.0)
    y = nrm(m) + 0.35 * nrm(edge)
    return small_room(y, 0.10)


@recipe("combat/swing_heavy", variants=2, max_len=[0.56, 0.6], fade_out=0.08)
def swing_heavy(v, rng):
    dur = [0.52, 0.58][v]
    peak = [0.55, 0.6][v]
    m = whoosh(dur, rng, peak=peak, f_lo=170, f_hi=[1500, 1250][v], q=0.9, power=2.4, rise=1.8, fall=1.3,
               whistle=0.18, whistle_mult=0.9, whistle_q=8, body=0.8, body_lp=260,
               flutter_hz=[27, 23][v], flutter_depth=0.35, color=-3.0)
    s = bell(dur, peak, 1.8, 1.3)
    t = tvec(dur)
    u = t / dur
    dop = 1 + 0.07 * np.tanh((peak - u) * 8)
    f0 = [92, 80][v] * dop
    hum = sum(a * np.sin(k * phase_from_freq(f0, len(t))) for k, a in ((1, 1.0), (2, 0.5), (3, 0.22)))
    hum *= s ** 3.2
    y = lp(nrm(m), 4500) + 0.28 * nrm(hum)
    return small_room(y, 0.13, seed=4)


@recipe("combat/whirl", variants=1, max_len=0.82, fade_out=0.08)
def whirl(v, rng):
    dur = 0.8
    a = whoosh(0.46, rng, peak=0.5, f_lo=350, f_hi=3000, q=1.0, power=2.3, rise=1.5, fall=1.3,
               whistle=0.25, whistle_mult=1.1, body=0.5, body_lp=300, flutter_hz=18, flutter_depth=0.2, color=-2.0)
    b = whoosh(0.52, rng, peak=0.45, f_lo=420, f_hi=3900, q=1.1, power=2.3, rise=1.4, fall=1.3,
               whistle=0.3, whistle_mult=1.2, body=0.5, body_lp=320, flutter_hz=22, flutter_depth=0.2, color=-1.8)
    m = Mix(dur)
    m.add(nrm(a), 0.0, 0.8)
    m.add(nrm(b), 0.27, 1.0)
    # rotating swirl underneath: band centre circles at ~2.6 rev/s
    t = tvec(dur)
    rot = 0.5 + 0.5 * np.sin(2 * np.pi * 2.6 * t - 1.2)
    sw = tvf(pink(dur, rng), "bp", 500 * (1 + 2.5 * rot), 1.4) * bell(dur, 0.55, 1.2, 1.5) * (0.35 + 0.65 * rot)
    m.add(nrm(sw), 0.0, 0.22)
    return small_room(fit(m.out(), ns(dur)), 0.12)


@recipe("combat/charge", variants=1, fade_out=0.04, max_len=0.95)
def charge(v, rng):
    dur = 0.9
    t = tvec(dur)
    u = t / dur
    f0 = 92 * 2 ** (1.0 * u ** 1.3)  # rises an octave
    trem_rate = 6 + 14 * u ** 1.5
    trem = 1 - 0.35 * (0.5 + 0.5 * np.sin(phase_from_freq(trem_rate, len(t))))
    ratios = [1, 2.01, 2.98, 4.13, 5.31, 6.92, 8.6]
    hum = np.zeros(len(t))
    for k, r in enumerate(ratios):
        for det in (0.0, 0.004 + 0.012 * u):  # beating that speeds up with tension
            hum += (0.8 ** k) * np.sin(phase_from_freq(f0 * r * (1 + det), len(t)) + rng.uniform(0, 6.28))
    hum = nrm(hum) * (0.25 + 0.75 * u ** 1.4) * trem
    shim_f = 2400 * 2 ** (0.9 * u)
    shim = sum(np.sin(phase_from_freq(shim_f * r, len(t)) + rng.uniform(0, 6)) * a
               for r, a in ((1, 1), (1.34, .6), (1.51, .5), (2.02, .35)))
    shim = nrm(shim) * u ** 2.2 * trem
    energy = tvf(white(dur, rng), "bp", 450 * 2 ** (3.3 * u), 2.0) * u ** 1.8
    grind = crackle(dur, rng, 200 + 2800 * u ** 2, decay=0.0008, f_lo=2500, f_hi=9000) * u
    shing = clank(0.25, 2900, rng, "blade", n=10, t60_lo=0.2, t60_hi=0.06) * np.exp(-tvec(0.25) / 0.05)
    shing = fit(shing, len(t))
    scrape = fit(burst(0.05, rng, 3000, 12000, 0.012), len(t))
    y = 1.0 * hum + 0.35 * shim + 0.3 * nrm(energy) + 0.12 * nrm(grind) + 0.35 * shing + 0.25 * scrape
    y = fade(drive(nrm(y), 1.6), fout=0.03)
    return small_room(y, 0.12)


@recipe("combat/hit_flesh", variants=3, max_len=0.27, fade_out=0.08)
def hit_flesh(v, rng):
    dur = 0.27
    t = tvec(dur)
    crack = burst(dur, rng, 2500, 14000, [0.004, 0.003, 0.005][v])
    th = thump(dur, [165, 140, 185][v], [58, 52, 64][v], rng, decay=0.05, click=0.15, noise=0.7, nlp=650)
    sq = squelch(dur, rng, f0=[1900, 1600, 2200][v], f1=[480, 420, 560][v], decay=[0.05, 0.06, 0.045][v],
                 density=[3500, 4200, 3000][v], hi=3200)
    sl_f = 1400 + 4200 * np.exp(-t / 0.018)
    slice_ = tvf(white(dur, rng), "bp", sl_f, 1.6) * np.exp(-t / 0.022)
    # the "slab of meat" smack: heavily damped low-mid body modes
    ex = lp(white(0.008, rng), 2500) * np.exp(-tvec(0.008) / 0.002)
    k = [1.0, 0.9, 1.12][v]
    meat = resonators(fit(ex, ns(dur)), np.array([175, 300, 480, 770, 1180]) * k,
                      [0.07, 0.055, 0.045, 0.035, 0.025], [1, .9, .7, .5, .3])
    m = Mix(dur)
    m.add(crack, 0, 0.45)
    m.add(nrm(th), 0.002, 0.8)
    m.add(nrm(meat), 0.001, 0.75)
    m.add(sq, [0.004, 0.008, 0.003][v], 0.85)
    m.add(nrm(slice_), 0, 0.42)
    if v == 1:  # a second, smaller gristle pop
        m.add(squelch(0.1, rng, 1200, 600, 0.02, 2500), 0.045, 0.35)
    y = drive(nrm(fit(m.out(), ns(dur))), 2.4, asym=0.08)
    return small_room(y, 0.10)


@recipe("combat/hit_armor", variants=3, max_len=0.32, fade_out=0.08)
def hit_armor(v, rng):
    dur = 0.32
    t = tvec(dur)
    f0 = [720, 880, 600][v]
    ring = clank(dur, f0, rng, "plate", n=13, t60_lo=[0.28, 0.24, 0.12][v], t60_hi=0.05, tilt=0.45, glide=0.004)
    tr = burst(dur, rng, 3000, 16000, 0.0018)
    body = thump(dur, [320, 360, 260][v], [190, 220, 150][v], rng, decay=0.03, click=0.2, noise=0.5, nlp=1200)
    scr_env = np.exp(-t / 0.025) * smooth_random(dur, rng, 160, 0.3, 1.0)
    scrape = tvf(white(dur, rng), "bp", 4200 + 1500 * np.exp(-t / 0.02), 3.0) * scr_env
    m = Mix(dur)
    m.add(tr, 0, 0.5)
    m.add(ring, 0.001, [0.85, 0.9, 0.5][v])
    m.add(nrm(body), 0, 0.7)
    m.add(nrm(scrape), 0.004, 0.3)
    if v == 2:  # bone: dry woody crack under a duller ring
        ex = crackle(0.03, rng, 9000, decay=0.0007, f_lo=500, f_hi=6000)
        bone = resonators(fit(ex, ns(dur)), [1050, 1680, 2550, 3400], [0.045, 0.035, 0.03, 0.02], [1, .8, .6, .4])
        m.add(nrm(bone), 0.002, 0.9)
        m.add(nrm(thump(dur, 210, 120, rng, decay=0.04, click=0.3, noise=0.6, nlp=900)), 0, 0.5)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.8)
    return small_room(y, 0.11)


@recipe("combat/critical", variants=2, fade_out=0.15, max_len=0.62)
def critical(v, rng):
    dur = 0.62
    t = tvec(dur)
    stab_f = 1800 + 5200 * np.exp(-t / 0.02)
    stab = tvf(white(dur, rng), "bp", stab_f, 1.4) * np.exp(-t / 0.03) * np.clip(t / 0.004, 0, 1)
    blade = clank(0.25, [2500, 2150][v], rng, "blade", n=10, t60_lo=0.12, t60_hi=0.04)
    th = thump(0.45, [125, 110][v], [45, 40][v], rng, decay=0.09, click=0.3, noise=0.8, nlp=900)
    sq = squelch(0.45, rng, f0=1800, f1=380, decay=0.1, density=5000, lo=300, hi=3200)
    ex = lp(white(0.01, rng), 2500) * np.exp(-tvec(0.01) / 0.003)
    meat = resonators(fit(ex, ns(0.3)), [160, 270, 450, 720, 1100], [0.1, 0.08, 0.06, 0.045, 0.03],
                      [1, .9, .7, .5, .3])
    boom = drive(sine_sweep(0.6, [90, 80][v], [38, 34][v], 3.0), 2.5) * env_exp(0.6, 0.13, attack=0.004)
    rumble = lp(brown(0.6, rng), 160, order=4) * env_exp(0.6, 0.15, attack=0.01)
    low = lp(nrm(boom) + 0.45 * nrm(rumble), 420, order=2)
    bone_ex = crackle(0.025, rng, 9000, decay=0.0008, f_lo=600, f_hi=7000)
    bone = resonators(fit(bone_ex, ns(0.12)), [980, 1510, 2420], [0.04, 0.03, 0.025], [1, .7, .5])
    m = Mix(dur)
    m.add(nrm(stab), 0, 0.6)
    m.add(blade, 0.002, 0.25)
    m.add(nrm(th), 0.012, 0.8)
    m.add(sq, 0.014, 0.95)
    m.add(nrm(meat), 0.012, 0.6)
    m.add(nrm(low), 0.008, 0.8)
    m.add(nrm(bone), [0.04, 0.055][v], 0.6)
    if v == 1:
        m.add(squelch(0.15, rng, 1100, 500, 0.035, 3000), 0.12, 0.4)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.6)
    return mid_room(y, 0.14)


@recipe("combat/roll", variants=2, max_len=0.47, fade_out=0.1)
def roll(v, rng):
    dur = 0.45
    t = tvec(dur)
    rust = smooth_random(dur, rng, 45, 0.15, 1.0) ** 2
    cloth = bp(colored(dur, rng, -1.5), 700, 6500) * rust * bell(dur, [0.3, 0.36][v], 0.8, 1.3)
    cloth *= np.clip(t / 0.012, 0, 1)
    jing = jingle(dur, rng, [14, 18][v], 2600, 7600, t60=(0.02, 0.07), start=0.01, spread=0.33, amp_decay=0.4)
    tt = [0.19, 0.23][v]
    th = thump(0.2, [115, 100][v], [52, 46][v], rng, decay=0.05, click=0.06, noise=0.9, nlp=380)
    grit = crackle(0.12, rng, 2200, decay=0.0009, f_lo=900, f_hi=5500) * np.exp(-tvec(0.12) / 0.035)
    plates = rattle(dur, rng, 0.02, 0.3, rate=20, f_lo=1100, f_hi=3200, decay_tau=0.25)
    m = Mix(dur)
    m.add(nrm(cloth), 0, 0.85)
    m.add(nrm(jing), 0, 0.25)
    m.add(nrm(plates), 0, 0.22)
    m.add(nrm(th), tt, 0.9)
    m.add(nrm(grit), tt, 0.3)
    return small_room(fit(m.out(), ns(dur)), 0.10)


@recipe("combat/backstep", variants=1, max_len=0.26, fade_out=0.07)
def backstep(v, rng):
    dur = 0.25
    t = tvec(dur)
    foot = thump(0.14, 125, 62, rng, decay=0.025, click=0.12, noise=0.6, nlp=500)
    sc_env = np.clip(t / 0.006, 0, 1) * np.exp(-t / 0.06)
    scuff = tvf(pink(dur, rng), "bp", 1300 + 1500 * np.exp(-t / 0.05), 0.8) * sc_env
    grit = crackle(dur, rng, 2600 * np.exp(-t / 0.06), decay=0.0008, f_lo=1200, f_hi=7000)
    swish = whoosh(0.2, rng, peak=0.3, f_lo=700, f_hi=3200, q=1.0, power=2.0)
    m = Mix(dur)
    m.add(nrm(foot), 0, 0.75)
    m.add(nrm(scuff), 0.0, 0.8)
    m.add(nrm(grit), 0.004, 0.3)
    m.add(nrm(swish), 0.0, 0.25)
    return small_room(fit(m.out(), ns(dur)), 0.09)


@recipe("combat/parry", variants=2, fade_out=0.25, max_len=0.82)
def parry(v, rng):
    dur = 0.8
    t = tvec(dur)
    base = [1318.5, 1174.7][v]  # E6 / D6
    clang = clank(dur, base * 0.62, rng, "blade", n=14, t60_lo=0.9, t60_hi=0.18, tilt=0.35, beat=0.0025)
    # consonant "ting" (root, fifth, octave, + a bell-ish minor seventh partial), gently chorused
    ting = np.zeros(len(t))
    vib = 1 + 0.0025 * np.sin(2 * np.pi * 5.3 * t)
    for r, a, t60 in ((1.0, 1.0, 0.75), (1.4983, 0.55, 0.6), (2.0, 0.42, 0.5), (2.9966, 0.18, 0.35), (4.0, 0.1, 0.25)):
        for det in (-0.0024, 0.0, 0.0028):
            ting += a * np.sin(phase_from_freq(base * r * (1 + det) * vib, len(t)) + rng.uniform(0, 6.28)) \
                * np.exp(-t * 6.9 / t60)
    ting = nrm(ting) * np.clip(t / 0.002, 0, 1)
    tr = burst(dur, rng, 2500, 16000, 0.0022)
    body = thump(0.15, 240, 150, rng, decay=0.03, click=0.25, noise=0.4, nlp=1500)
    slide = tvf(white(dur, rng), "bp", 5200 - 1500 * np.clip(t / 0.08, 0, 1), 4.0) * \
        np.clip((t - 0.012) / 0.01, 0, 1) * np.exp(-np.maximum(0, t - 0.012) / 0.03)
    glitter = jingle(dur, rng, 16, 5500, 11000, t60=(0.03, 0.12), start=0.03, spread=0.35, amp_decay=0.25)
    m = Mix(dur)
    m.add(tr, 0, 0.55)
    m.add(clang, 0.0005, 0.75)
    m.add(ting, 0.001, 0.62)
    m.add(nrm(body), 0, 0.5)
    m.add(nrm(slide), 0, 0.18)
    m.add(nrm(glitter), 0, 0.12)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.25)
    return reverb(y, wet=0.26, rt60=1.1, predelay=0.012, hf_damp=0.25, seed=11, early_span=0.04)


@recipe("combat/guard", variants=3, max_len=0.31, fade_out=0.08)
def guard(v, rng):
    dur = 0.3
    t = tvec(dur)
    th = thump(dur, [150, 172, 136][v], [88, 100, 78][v], rng, decay=0.05, click=0.1, noise=0.8, nlp=900)
    ex = bp(white(0.006, rng), 200, 4000)
    k = [1.0, 1.1, 0.92][v]
    wood = resonators(fit(ex, ns(dur)), np.array([300, 465, 690, 1015, 1460]) * k,
                      [0.09, 0.07, 0.06, 0.045, 0.03], [1, .8, .6, .4, .25])
    rim = clank(dur, [520, 575, 480][v], rng, "shield", n=7, t60_lo=0.18, t60_hi=0.06)
    slap = bp(white(dur, rng), 900, 4200) * np.exp(-t / 0.007)
    m = Mix(dur)
    m.add(nrm(th), 0, 1.0)
    m.add(nrm(wood), 0.001, 0.6)
    m.add(rim, 0.001, 0.09)
    m.add(nrm(slap), 0, 0.25)
    y = lp(drive(nrm(fit(m.out(), ns(dur))), 1.6), 6500)
    return small_room(y, 0.11)


@recipe("combat/guard_break", variants=1, fade_out=0.15, max_len=0.72)
def guard_break(v, rng):
    dur = 0.7
    t = tvec(dur)
    imp = thump(0.45, 140, 45, rng, decay=0.08, click=0.45, noise=0.9, nlp=900)
    boom = drive(sine_sweep(0.6, 66, 34, 3.0), 2.0) * env_exp(0.6, 0.13, attack=0.003)
    tr = burst(0.05, rng, 1500, 16000, 0.003)
    spl_t = tvec(0.25)
    splinter = crackle(0.25, rng, 5000 * np.exp(-spl_t / 0.05), decay=0.0009, f_lo=700, f_hi=7000, size_var=0.9)
    crack_res = resonators(fit(crackle(0.04, rng, 9000, decay=0.0007, f_lo=500, f_hi=8000), ns(0.25)),
                           [880, 1390, 2260, 3100], [0.05, 0.04, 0.03, 0.02], [1, .8, .6, .4])
    m = Mix(dur)
    m.add(tr, 0, 0.7)
    m.add(nrm(imp), 0, 0.85)
    m.add(nrm(lp(boom, 400)), 0.004, 0.55)
    m.add(nrm(splinter), 0.002, 0.8)
    m.add(nrm(crack_res), 0.001, 0.75)
    times = [0.07, 0.185, 0.28, 0.355, 0.415, 0.46]
    for i, tt in enumerate(times):
        f = [1210, 930, 1480, 1050, 1320, 1600][i]
        c = clank(0.3, f, rng, "plate", n=9, t60_lo=0.22, t60_hi=0.05, beat=0.002)
        m.add(c, tt, 0.8 * 0.82 ** i)
    m.add(jingle(0.6, rng, 18, 2800, 8000, t60=(0.02, 0.06), start=0.05, spread=0.4), 0, 0.22)
    y = drive(nrm(fit(m.out(), ns(dur))), 2.0)
    return mid_room(y, 0.15)


@recipe("combat/stance_break", variants=2, fade_out=0.15, max_len=0.62)
def stance_break(v, rng):
    dur = 0.6
    t = tvec(dur)
    whump = drive(sine_sweep(0.6, [96, 86][v], [38, 34][v], 2.5), 2.0) * env_exp(0.6, 0.12, attack=0.008)
    air = bp(brown(0.45, rng), 90, 1200) * env_exp(0.45, 0.08, attack=0.01)
    hit = thump(0.3, [150, 135][v], 60, rng, decay=0.06, click=0.2, noise=0.8, nlp=700)
    gong = clank(0.6, [142, 128][v], rng, "bell", n=8, t60_lo=0.7, t60_hi=0.2, beat=0.004)
    rat = rattle(dur, rng, 0.03, 0.4, rate=[30, 24][v], f_lo=900, f_hi=3600, decay_tau=0.16)
    jing = jingle(dur, rng, 16, 2800, 8500, t60=(0.02, 0.06), start=0.02, spread=0.3)
    m = Mix(dur)
    m.add(nrm(lp(whump, 500)), 0, 0.62)
    m.add(nrm(air), 0, 0.7)
    m.add(nrm(hit), 0, 0.8)
    m.add(gong, 0.003, 0.26)
    m.add(nrm(rat), 0.0, 0.9)
    m.add(nrm(jing), 0, 0.32)
    y = drive(nrm(fit(m.out(), ns(dur))), 2.0)
    return mid_room(y, 0.14)


@recipe("combat/stagger", variants=1, max_len=0.41, fade_out=0.1)
def stagger(v, rng):
    dur = 0.4
    t = tvec(dur)
    hit = thump(0.35, 132, 54, rng, decay=0.07, click=0.25, noise=0.75, nlp=700)
    slap = bp(white(dur, rng), 700, 3600) * np.exp(-t / 0.012)
    rat = rattle(dur, rng, 0.015, 0.22, rate=26, f_lo=1000, f_hi=3400, decay_tau=0.1)
    jing = jingle(dur, rng, 9, 3000, 8000, t60=(0.02, 0.05), start=0.01, spread=0.2)
    sw = whoosh(0.25, rng, peak=0.35, f_lo=500, f_hi=2200, q=1.0, power=2.0)
    m = Mix(dur)
    m.add(nrm(hit), 0, 1.0)
    m.add(nrm(slap), 0, 0.4)
    m.add(nrm(rat), 0.0, 0.32)
    m.add(nrm(jing), 0, 0.15)
    m.add(nrm(sw), 0.06, 0.2)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.8)
    return small_room(y, 0.11)


@recipe("combat/lock_on", variants=1, fade_out=0.03, max_len=0.1)
def lock_on(v, rng):
    dur = 0.1
    t = tvec(dur)
    ex = bp(white(0.0015, rng), 500, 9000)
    wood = resonators(fit(ex, ns(dur)), [1240, 2490, 3710], [0.05, 0.03, 0.02], [1, .5, .25])
    tone = np.sin(2 * np.pi * 1661.2 * t) * np.exp(-t / 0.022) + 0.4 * np.sin(2 * np.pi * 2489 * t) * np.exp(-t / 0.014)
    y = 0.6 * nrm(wood) + 0.55 * nrm(tone) * np.clip(t / 0.0015, 0, 1)
    y = lp(y, 7000)
    return reverb(y, wet=0.08, rt60=0.25, predelay=0.003, hf_damp=0.5, seed=2)


@recipe("combat/out_of_stamina", variants=1, fade_out=0.08, max_len=0.41)
def out_of_stamina(v, rng):
    dur = 0.4
    t = tvec(dur)
    src = colored(dur, rng, -1.0)
    curve = np.clip(t / dur, 0, 1)
    br = formants(src, "uh", "o", scale=1.0, curve=curve, gains=(1.0, 0.75, 0.45, 0.25))
    br = hp(br, 250) + 0.25 * hp(lp(src, 5000), 1500)
    env = np.clip(t / 0.035, 0, 1) ** 1.5 * np.exp(-np.maximum(0, t - 0.06) / 0.11)
    flutter = 1 - 0.18 * smooth_random(dur, rng, 22)
    huff = lp(bp(brown(dur, rng), 150, 700), 700) * np.exp(-t / 0.05) * np.clip(t / 0.01, 0, 1)
    y = nrm(br) * env * flutter + 0.3 * nrm(huff)
    return small_room(y, 0.10)


# =========================================================================== hollow soldier
def creature_voice(dur, rng, f0, vowel_a, vowel_b, scale=0.9, jitter=0.04, shimmer=0.2, subharm=0.0,
                   breath=0.4, fry=0.0, curve=None, drive_amt=1.5, wobble=0.03, double=0.0):
    """glottal pulses -> vowel formants. wobble = slow irregular pitch drift (fraction);
    double = detune of a second, independent voice layered on top (ghostly doubling)"""
    n = ns(dur)
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,))
    f0w = f0 * (1 + wobble * (2 * smooth_random(dur, rng, 4.5) - 1))
    src = glottal(dur, f0w, rng, jitter=jitter, shimmer=shimmer, subharm=subharm, breath=breath, fry=fry)
    if double:
        f0d = f0 * (1 + double) * (1 + wobble * (2 * smooth_random(dur, rng, 3.7) - 1))
        src = src + 0.7 * glottal(dur, f0d, rng, jitter=jitter, shimmer=shimmer, subharm=subharm,
                                  breath=breath, fry=fry)
    y = formants(src, vowel_a, vowel_b, scale=scale, curve=curve, gains=(1.0, 0.6, 0.28, 0.1))
    return drive(nrm(y), drive_amt)


@recipe("hollow_soldier/ambient", variants=3, fade_out=0.25, max_len=[1.35, 1.45, 1.35])
def hs_ambient(v, rng):
    if v == 0:  # raspy in-out breath
        dur = 1.35
        m = Mix(dur)
        di = 0.55
        ti = tvec(di)
        inh = formants(colored(di, rng, -1.0), "i", "e", scale=0.95)
        inh = lp(hp(inh, 400), 6000)
        env_i = np.sin(np.pi / 2 * np.clip(ti / (di * 0.72), 0, 1)) ** 2 * \
            np.cos(np.pi / 2 * np.clip((ti - di * 0.72) / (di * 0.28), 0, 1)) ** 2
        inh *= env_i * (1 - 0.35 * smooth_random(di, rng, 30))
        m.add(nrm(inh), 0, 0.35)
        de = 0.8
        te = tvec(de)
        ex_src = colored(de, rng, -1.2) * 0.8 + 0.6 * glottal(de, 52 - 10 * te / de, rng, jitter=0.08, shimmer=0.4,
                                                             breath=0.6, fry=0.35)
        exh = formants(ex_src, "a", "o", scale=0.92)
        env = np.clip(te / 0.09, 0, 1) ** 1.5 * np.exp(-te / 0.3)
        m.add(nrm(exh) * env, 0.47, 1.0)
        y = m.out()
    elif v == 1:  # low moan
        dur = 1.4
        t = tvec(dur)
        f0 = 96 - 22 * (t / dur) ** 1.2 + 3 * np.sin(2 * np.pi * 3.1 * t)
        y = creature_voice(dur, rng, f0, "o", "u", scale=0.92, jitter=0.06, shimmer=0.3, breath=0.85, fry=0.06,
                           drive_amt=1.4, wobble=0.05, double=0.018)
        rasp = 1 - 0.3 * smooth_random(dur, rng, 35)
        y = y * rasp * np.clip(t / 0.12, 0, 1) * np.clip((dur - t) / 0.35, 0, 1) ** 1.3
    else:  # wheezy, creaky groan with a rattling inhale at the end
        dur = 1.25
        t = tvec(0.85)
        f0 = 112 - 25 * np.sin(np.pi * t / 0.85) ** 2
        g = creature_voice(0.85, rng, f0, "uh", "a", scale=0.95, jitter=0.07, shimmer=0.3, fry=0.28, breath=0.65,
                           drive_amt=1.8, wobble=0.04, double=0.014)
        g *= np.clip(t / 0.05, 0, 1) * np.clip((0.85 - t) / 0.3, 0, 1)
        tw = tvec(0.4)
        wh = tvf(white(0.4, rng), "bp", 1900 + 400 * tw / 0.4, 9) + 0.5 * hp(white(0.4, rng), 2500)
        wh *= np.sin(np.pi * tw / 0.4) ** 1.5 * (1 - 0.5 * smooth_random(0.4, rng, 40))
        m = Mix(dur)
        m.add(g, 0, 1.0)
        m.add(nrm(wh), 0.82, 0.3)
        y = m.out()
    y = hollow(nrm(y), delay=[0.0031, 0.0036, 0.0028][v], fb=0.35, mix=0.28)
    return mid_room(y, 0.12, seed=21)


@recipe("hollow_soldier/hurt", variants=2, max_len=0.42, fade_out=0.1)
def hs_hurt(v, rng):
    dur = 0.4
    t = tvec(0.3)
    f0 = [150, 128][v] * np.exp(-t / 0.4) + 0 * t
    g = creature_voice(0.3, rng, f0, "a", "uh", scale=0.95, jitter=0.06, shimmer=0.25, subharm=0.3, breath=0.7,
                       fry=0.08, drive_amt=2.2, wobble=0.04, double=0.015)
    g *= np.clip(t / 0.008, 0, 1) * np.exp(-np.maximum(0, t - 0.06) / 0.09)
    rasp = bp(white(0.3, rng), 900, 3200) * np.abs(g) ** 0.6
    jing = jingle(dur, rng, [22, 26][v], 3000, 9000, t60=(0.015, 0.06), start=0.0, spread=0.25, amp_decay=0.22)
    plate = clank(0.2, [1350, 1150][v], rng, "plate", n=8, t60_lo=0.12, t60_hi=0.03)
    m = Mix(dur)
    m.add(hollow(nrm(g) + 0.3 * nrm(rasp), 0.003, 0.3, 0.25), 0, 1.0)
    m.add(nrm(jing), 0.0, 0.45)
    m.add(plate, 0.004, 0.2)
    return small_room(fit(m.out(), ns(dur)), 0.10)


@recipe("hollow_soldier/death", variants=1, fade_out=0.2, max_len=1.25)
def hs_death(v, rng):
    dur = 1.2
    tg = tvec(0.95)
    f0 = 58 + 64 * np.exp(-tg / 0.45)
    curve = np.clip(tg / 0.95, 0, 1)
    g = creature_voice(0.95, rng, f0, "a", "u", scale=0.93, jitter=0.07, shimmer=0.28, breath=0.7, fry=0.08,
                       curve=curve, drive_amt=1.6, wobble=0.05, double=0.016)
    g = g * np.clip(tg / 0.03, 0, 1) * np.clip((0.95 - tg) / 0.45, 0, 1) ** 1.2
    m = Mix(dur)
    m.add(hollow(g, 0.0032, 0.35, 0.28), 0, 0.85)
    m.add(nrm(thump(0.25, 125, 60, rng, decay=0.06, click=0.25, noise=0.8, nlp=700)), 0.38, 0.6)
    m.add(clank(0.4, 420, rng, "plate", n=10, t60_lo=0.18, t60_hi=0.05), 0.385, 0.35)
    m.add(nrm(thump(0.4, 105, 40, rng, decay=0.1, click=0.3, noise=0.9, nlp=600)), 0.63, 0.9)
    for tt, f, g_ in ((0.635, 640, 0.45), (0.71, 910, 0.35), (0.80, 520, 0.3), (0.94, 1180, 0.2), (1.04, 1500, 0.12)):
        m.add(clank(min(0.35, dur - tt), f, rng, "plate", n=9, t60_lo=0.2, t60_hi=0.05), tt, g_)
    m.add(jingle(0.55, rng, 30, 2800, 8500, t60=(0.02, 0.07), start=0.0, spread=0.3), 0.62, 0.3)
    return mid_room(fit(m.out(), ns(dur)), 0.14, seed=23)


@recipe("hollow_soldier/attack", variants=2, max_len=0.42, fade_out=0.1)
def hs_attack(v, rng):
    dur = 0.4
    t = tvec(dur)
    if v == 0:
        f0 = 88 + 45 * np.clip(t / 0.12, 0, 1) - 12 * np.clip((t - 0.2) / 0.2, 0, 1)
    else:
        f0 = 100 + 30 * np.sin(np.pi * np.clip(t / 0.3, 0, 1)) ** 0.7
    curve = np.clip(t / 0.15, 0, 1)
    g = creature_voice(dur, rng, f0, "uh", "a", scale=[0.9, 0.86][v], jitter=0.06, shimmer=0.3,
                       subharm=[0.5, 0.6][v], breath=0.8, fry=0.04, curve=curve, drive_amt=3.5, wobble=0.04,
                       double=0.02)
    growl = 1 - 0.42 * (0.5 + 0.5 * np.sin(2 * np.pi * [32, 27][v] * t + rng.uniform(0, 6)))
    rasp = bp(white(dur, rng), 1000, 4200) * smooth_random(dur, rng, 60, 0.3, 1.0)
    env = np.clip(t / 0.015, 0, 1) * np.clip((dur - t) / 0.12, 0, 1)
    y = (nrm(g) * growl + 0.25 * nrm(rasp)) * env
    y = lp(y, 5500)
    return small_room(hollow(y, 0.0029, 0.3, 0.22), 0.10, seed=24)


# =========================================================================== fallen warden
@recipe("fallen_warden/ambient", variants=2, fade_out=0.3, max_len=1.55)
def fw_ambient(v, rng):
    dur = 1.5
    m = Mix(dur)
    di = [0.6, 0.55][v]
    ti = tvec(di)
    inh = formants(colored(di, rng, -2.0), "o", "o", scale=0.75) + 0.12 * bp(white(di, rng), 700, 1300)
    inh = lp(inh, 2600)
    env_i = np.sin(np.pi / 2 * np.clip(ti / (di * 0.75), 0, 1)) ** 2 * \
        np.cos(np.pi / 2 * np.clip((ti - di * 0.75) / (di * 0.25), 0, 1)) ** 2
    inh *= env_i
    whistle = tvf(white(di, rng), "bp", 1050 + 120 * ti / di, 14) * env_i ** 2
    m.add(nrm(inh) + 0.1 * nrm(whistle), 0.0, 0.42)
    de = 0.85
    te = tvec(de)
    ex = colored(de, rng, -2.5) * 0.7 + glottal(de, 44 - 6 * te / de, rng, jitter=0.1, shimmer=0.4, breath=0.8,
                                               fry=0.4)
    ex = formants(ex, "uh", "o", scale=0.72)
    ex *= np.clip(te / 0.12, 0, 1) ** 1.5 * np.exp(-te / 0.33)
    m.add(nrm(drive(nrm(ex), 1.5)), di - 0.06, 1.0)
    if v == 0:
        m.add(clank(0.4, 215, rng, "plate", n=8, t60_lo=0.25, t60_hi=0.06), di + 0.12, 0.12)
    else:  # leather + metal creak (stick-slip friction through plate modes)
        dc = 0.35
        tc = tvec(dc)
        rate = 70 + 60 * tc / dc
        fr = crackle(dc, rng, rate, decay=0.0006, f_lo=100, f_hi=4000, size_var=0.3)
        creak = resonators(fr, [310, 455, 610, 870], [0.08, 0.06, 0.05, 0.04], [1, .8, .6, .4])
        creak *= np.sin(np.pi * tc / dc)
        m.add(nrm(creak), 0.08, 0.22)
    y = hollow(fit(m.out(), ns(dur)), delay=0.0024, fb=0.45, mix=0.32)
    y = lp(y, 4500)
    return hall(y, 0.16, rt60=1.2, seed=31)


@recipe("fallen_warden/hurt", variants=2, max_len=0.55, fade_out=0.12)
def fw_hurt(v, rng):
    dur = 0.5
    f0 = [340, 290][v]
    ring = clank(dur, f0, rng, "plate", n=12, t60_lo=0.35, t60_hi=0.07, tilt=0.4, glide=0.006)
    tr = burst(dur, rng, 2000, 14000, 0.002)
    th = thump(0.35, 150, 58, rng, decay=0.06, click=0.25, noise=0.8, nlp=800)
    tg = tvec(0.42)
    f = [76, 70][v] * np.exp(-tg / 0.9) - 6 * tg
    gr = creature_voice(0.42, rng, f, "uh", "o", scale=0.74, jitter=0.05, shimmer=0.25, subharm=0.35, breath=0.4,
                        drive_amt=2.5)
    gr *= np.clip(tg / 0.02, 0, 1) * np.exp(-np.maximum(0, tg - 0.08) / 0.12)
    m = Mix(dur)
    m.add(tr, 0, 0.5)
    m.add(ring, 0.001, 0.75)
    m.add(nrm(th), 0, 0.9)
    m.add(hollow(nrm(gr), 0.0024, 0.4, 0.3), 0.025, 0.85)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.6)
    return mid_room(y, 0.14, seed=32)


def spirit_pad(dur, rng, notes_hz, vowel="o", vib=4.8):
    """soft additive 'ethereal choir' pad: harmonics through a vowel, slow vibrato, 3-voice chorus"""
    t = tvec(dur)
    out = np.zeros(len(t))
    for f in notes_hz:
        for det in (-0.006, 0.0, 0.0065):
            fv = f * (1 + det) * (1 + 0.004 * np.sin(2 * np.pi * (vib + rng.uniform(-.4, .4)) * t + rng.uniform(0, 6)))
            ph = phase_from_freq(fv, len(t)) + rng.uniform(0, 6.28)
            for k in range(1, 10):
                if f * k > 9000:
                    break
                out += np.sin(k * ph) / k ** 1.3
    out = formants(out, vowel, vowel, scale=1.0)
    return nrm(out)


@recipe("fallen_warden/death", variants=1, fade_out=0.6, tail_db=-70)
def fw_death(v, rng):
    dur = 3.0
    m = Mix(dur)
    # knees hit the ground
    m.add(nrm(thump(0.45, 120, 44, rng, decay=0.1, click=0.35, noise=0.85, nlp=650)), 0.0, 0.85)
    m.add(clank(0.6, 255, rng, "plate", n=12, t60_lo=0.5, t60_hi=0.08, glide=0.006), 0.002, 0.45)
    m.add(jingle(0.35, rng, 18, 2500, 8000, t60=(0.02, 0.06), start=0.0, spread=0.2), 0.0, 0.15)
    # last breath / groan
    tg = tvec(0.9)
    gr = creature_voice(0.9, rng, 66 * np.exp(-tg / 1.6) - 8 * tg, "a", "u", scale=0.72, jitter=0.07, shimmer=0.3,
                        breath=0.6, fry=0.25, curve=np.clip(tg / 0.9, 0, 1), drive_amt=1.8)
    gr *= np.clip(tg / 0.05, 0, 1) * np.clip((0.9 - tg) / 0.5, 0, 1) ** 1.3
    m.add(hollow(gr, 0.0024, 0.42, 0.3), 0.08, 0.55)
    # full collapse: body crash + armour clatter
    m.add(nrm(thump(0.6, 82, 31, rng, decay=0.22, click=0.4, noise=1.0, nlp=500)), 0.58, 1.0)
    for tt, f, g in ((0.585, 410, 0.5), (0.66, 690, 0.4), (0.74, 330, 0.38), (0.86, 980, 0.3), (0.99, 560, 0.24),
                     (1.15, 1250, 0.16)):
        m.add(clank(0.5, f, rng, "plate", n=10, t60_lo=0.3, t60_hi=0.06, beat=0.002), tt, g)
    m.add(jingle(0.8, rng, 40, 2600, 8500, t60=(0.02, 0.08), start=0.0, spread=0.5), 0.58, 0.22)
    # the greatsword falls and rings out
    sw = clank(1.8, 305, rng, "blade", n=12, t60_lo=1.6, t60_hi=0.3, tilt=0.4, beat=0.0015)
    m.add(sw, 0.9, 0.42)
    # spirit leaves: ethereal open fifth D-A-D, rising shimmer
    dp = 2.15
    tp = tvec(dp)
    pad = spirit_pad(dp, rng, [146.83, 220.0, 293.66], "o")
    pad *= np.clip(tp / 0.6, 0, 1) ** 1.5 * np.clip((dp - tp) / 1.1, 0, 1) ** 1.6
    rise = 2 ** (0.58 * np.clip((tp - 0.6) / 1.5, 0, 1))
    shim = sum(np.sin(phase_from_freq(f * rise, len(tp)) + rng.uniform(0, 6)) for f in (1760.0, 2637.0, 3520.0))
    shim *= (0.5 + 0.5 * np.sin(2 * np.pi * 3.3 * tp)) * np.clip(tp / 0.8, 0, 1) * np.clip((dp - tp) / 0.9, 0, 1)
    m.add(nrm(pad), 0.85, 0.42)
    m.add(nrm(shim), 0.85, 0.07)
    y = fit(m.out(), ns(dur))
    y = drive(nrm(y), 1.3)
    return fit(hall(y, 0.22, rt60=2.2, seed=33), ns(dur))


@recipe("fallen_warden/roar", variants=1, fade_out=0.4, tail_db=-70)
def fw_roar(v, rng):
    dur = 2.5
    dr = 1.7
    t = tvec(dr)
    f0 = 52 + 30 * np.sin(np.pi * np.clip(t / 1.3, 0, 1)) ** 0.8 - 10 * np.clip((t - 1.1) / 0.6, 0, 1)
    curve = np.clip(t / 0.5, 0, 1)
    src = glottal(dr, f0, rng, jitter=0.07, shimmer=0.3, subharm=0.5, breath=0.9, fry=0.03)
    sub = glottal(dr, f0 / 2, rng, jitter=0.05, shimmer=0.2, breath=0.3)
    v1 = formants(src, "uh", "a", scale=0.7, curve=curve)
    v2 = formants(sub, "o", "a", scale=0.62, curve=curve)
    roar = drive(nrm(v1) + 0.6 * nrm(v2), 4.0)
    noise_roar = tvf(colored(dr, rng, -2), "bp", 380 + 500 * curve, 0.9)
    env = np.clip(t / 0.18, 0, 1) ** 1.3 * np.clip((dr - t) / 0.7, 0, 1) ** 1.2
    roar = (nrm(roar) + 0.35 * nrm(noise_roar)) * env * (1 - 0.25 * smooth_random(dr, rng, 30))
    roar = hollow(roar, 0.0024, 0.4, 0.25)
    # rushing flame swelling out of it
    df = 1.65
    tf = tvec(df)
    s = bell(df, 0.42, 1.6, 1.1)
    fire = tvf(pink(df, rng), "bp", 300 * 2 ** (3.0 * s), 0.75) * s ** 1.5
    rumble = lp(brown(df, rng), 140, order=4) * s ** 1.2
    crk = crackle(df, rng, 80 + 380 * s, decay=0.001, f_lo=1200, f_hi=7000) * s
    flick = 0.65 + 0.35 * smooth_random(df, rng, 14)
    ghost = sum(tvf(white(df, rng), "bp", f * (1 + 0.25 * s), 22) for f in (1250, 1880, 2510))
    ghost *= s ** 2
    flame = (nrm(fire) * flick + 0.5 * nrm(rumble) + 0.22 * nrm(crk) + 0.13 * nrm(ghost))
    m = Mix(dur)
    m.add(nrm(roar), 0.0, 1.0)
    m.add(nrm(flame), 0.85, 0.85)
    y = fit(m.out(), ns(dur))
    return fit(hall(y, 0.2, rt60=1.6, seed=34), ns(dur))


@recipe("fallen_warden/slam", variants=2, fade_out=0.2, max_len=1.0)
def fw_slam(v, rng):
    dur = 1.0
    t = tvec(dur)
    imp = thump(0.85, [112, 96][v], [33, 30][v], rng, decay=0.21, click=0.5, noise=1.0, nlp=600, sweep_curve=3.5)
    imp = lp(drive(nrm(imp), 3.0), 2500)
    crack = burst(0.06, rng, 1200, 16000, 0.004)
    cr = crackle(0.1, rng, 7000 * np.exp(-tvec(0.1) / 0.02), decay=0.0008, f_lo=500, f_hi=9000, size_var=0.9)
    blade = clank(0.9, [240, 205][v], rng, "blade", n=12, t60_lo=0.6, t60_hi=0.1, tilt=0.45, glide=0.008)
    rub_rate = 900 * np.exp(-t / 0.16) + 70 * np.exp(-t / 0.5)
    rubble = crackle(dur, rng, rub_rate, decay=0.0025, f_lo=250, f_hi=5000, size_var=1.0)
    pebbles = Mix(dur)
    for _ in range(12):
        tt = 0.05 + rng.exponential(0.22)
        if tt > dur - 0.05:
            continue
        ex = bp(white(0.002, rng), 800, 8000)
        f = rng.uniform(1300, 4200)
        p = resonators(fit(ex, ns(0.05)), [f, f * 1.47, f * 2.3], [0.03, 0.02, 0.015], [1, .6, .4])
        pebbles.add(nrm(p), tt, rng.uniform(0.2, 0.6) * np.exp(-tt / 0.4))
    dust = bp(pink(dur, rng), 500, 4000) * np.exp(-t / 0.35) * np.clip(t / 0.03, 0, 1)
    m = Mix(dur)
    stone = resonators(fit(crackle(0.03, rng, 9000, decay=0.001, f_lo=300, f_hi=6000), ns(0.3)),
                       [210, 380, 620, 1040, 1700], [0.09, 0.07, 0.05, 0.04, 0.03], [1, .9, .7, .5, .35])
    m.add(crack, 0, 0.8)
    m.add(nrm(imp), 0.001, 0.75)
    m.add(nrm(stone), 0.001, 0.75)
    m.add(nrm(cr), 0.001, 0.7)
    m.add(blade, 0.002, 0.4)
    m.add(nrm(rubble), 0.01, 0.85)
    m.add(fit(pebbles.out(), ns(dur)), 0, 0.5)
    m.add(nrm(dust), 0.0, 0.3)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.4)
    return fit(hall(y, 0.17, rt60=1.2, seed=35), ns(dur))


@recipe("fallen_warden/swing", variants=2, fade_out=0.12, max_len=0.74)
def fw_swing(v, rng):
    dur = 0.7
    peak = [0.55, 0.6][v]
    m = whoosh(dur, rng, peak=peak, f_lo=120, f_hi=[950, 1150][v], q=0.8, power=2.6, rise=1.8, fall=1.4,
               whistle=0.15, whistle_mult=0.8, whistle_q=7, body=1.0, body_lp=200,
               flutter_hz=[21, 24][v], flutter_depth=0.45, color=-3.5)
    s = bell(dur, peak, 1.8, 1.4)
    t = tvec(dur)
    dop = 1 + 0.08 * np.tanh((peak - t / dur) * 8)
    f0 = [68, 62][v] * dop
    hum = sum(a * np.sin(k * phase_from_freq(f0, len(t))) for k, a in ((1, 1.0), (2, 0.6), (3, 0.3), (4, 0.15)))
    hum *= s ** 3.0
    air = lp(brown(dur, rng), 95, order=4) * s ** 2
    y = nrm(m) + 0.3 * nrm(hum) + 0.35 * nrm(air)
    return mid_room(y, 0.13, seed=36)


@recipe("fallen_warden/flame", variants=1, fade_out=0.2)
def fw_flame(v, rng):
    dur = 1.2
    t = tvec(dur)
    s = bell(dur, 0.38, 1.3, 1.2)
    fc = 380 * 2 ** (2.8 * s) * (1 + 0.12 * np.tanh((0.38 - t / dur) * 6))
    fire = tvf(pink(dur, rng), "bp", fc, 0.7) * s ** 1.4 * (0.65 + 0.35 * smooth_random(dur, rng, 16))
    roar = lp(brown(dur, rng), 190, order=4) * s ** 1.2
    crk = crackle(dur, rng, 150 + 500 * s, decay=0.001, f_lo=1000, f_hi=7500) * s
    tear_mod = 0.4 + 0.6 * smooth_random(dur, rng, 15)
    tear = crackle(dur, rng, 2500 * s, decay=0.0012, f_lo=350, f_hi=3200, size_var=0.9) * tear_mod
    ghost = sum(tvf(white(dur, rng), "bp", f * (1 + 0.18 * s), 24) for f in (1480, 2220, 2960))
    ghost *= s ** 1.8
    whisper = formants(white(dur, rng), "u", "o", scale=1.0) * s ** 2
    attack = burst(0.08, rng, 800, 9000, 0.015)
    m = Mix(dur)
    m.add(attack, 0, 0.25)
    m.add(nrm(fire), 0, 1.0)
    m.add(nrm(roar), 0, 0.5)
    m.add(nrm(crk), 0, 0.25)
    m.add(nrm(tear), 0, 0.35)
    m.add(nrm(ghost), 0, 0.16)
    m.add(nrm(whisper), 0, 0.12)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.3)
    return fit(hall(y, 0.18, rt60=1.4, seed=37), ns(dur))


@recipe("fallen_warden/step", variants=2, max_len=0.32, fade_out=0.08)
def fw_step(v, rng):
    dur = 0.3
    th = thump(dur, [92, 80][v], [45, 40][v], rng, decay=0.07, click=0.25, noise=0.9, nlp=520)
    sab = clank(0.25, [520, 465][v], rng, "plate", n=10, t60_lo=0.11, t60_hi=0.03)
    jing = jingle(dur, rng, 10, 2800, 8000, t60=(0.02, 0.05), start=0.008, spread=0.12)
    gt = tvec(0.07)
    grit = crackle(0.07, rng, 3000, decay=0.0008, f_lo=800, f_hi=5000) * np.exp(-gt / 0.02)
    mid = thump(0.2, [230, 205][v], [140, 125][v], rng, decay=0.035, click=0.2, noise=0.6, nlp=1400)
    m = Mix(dur)
    m.add(nrm(th), 0, 0.8)
    m.add(nrm(mid), 0, 0.7)
    m.add(sab, 0.003, 0.6)
    m.add(nrm(jing), 0.005, 0.26)
    m.add(nrm(grit), 0.001, 0.32)
    y = drive(nrm(fit(m.out(), ns(dur))), 2.0)
    return reverb(y, wet=0.13, rt60=0.6, predelay=0.01, hf_damp=0.5, seed=38)


# =========================================================================== screens
def paper(dur, rng, peak=0.4, lo=900, hi=6000, grain=900):
    """a sheet of paper sliding: soft band noise with a crackle of fibres riding the motion"""
    s = bell(dur, peak, 1.6, 1.8)
    slide = bp(colored(dur, rng, -1.0), lo, hi) * s ** 1.5
    fibres = crackle(dur, rng, grain, decay=0.0012, f_lo=1500, f_hi=7000, amp_curve=s)
    return nrm(slide) + 0.35 * nrm(fibres)


def chime(dur, f0, rng, t60=0.9, bright=0.5):
    """a small struck brass bell: bell partials, the upper ones dying first"""
    ratios = metal_partials(1.0, "bell", n=8)
    freqs = [f0 * r for r in ratios[1:7]]
    t60s = [t60 * (1.0 - 0.11 * i) for i in range(len(freqs))]
    amps = [1.0, 0.55, 0.4 * bright + 0.1, 0.3 * bright, 0.2 * bright, 0.12 * bright]
    return modal(dur, freqs, t60s, amps, rng)


@recipe("ui/open", variants=1, fade_out=0.08, max_len=0.6)
def ui_open(v, rng):
    dur = 0.6
    t = tvec(dur)
    sheet = paper(0.32, rng, peak=0.45)
    tone = chime(dur, 523.25, rng, t60=0.7, bright=0.35) * np.clip((t - 0.05) / 0.004, 0, 1)
    y = 0.55 * fit(sheet, ns(dur)) + 0.4 * nrm(tone)
    return reverb(lp(y, 9000), wet=0.12, rt60=0.5, predelay=0.006, hf_damp=0.55, seed=41)


@recipe("ui/close", variants=1, fade_out=0.06, max_len=0.35)
def ui_close(v, rng):
    dur = 0.35
    sheet = paper(0.22, rng, peak=0.3, lo=600, hi=4000)
    knock = thump(dur, 190, 120, rng, decay=0.045, click=0.15, noise=0.2, nlp=900)
    y = 0.5 * fit(sheet, ns(dur)) + 0.5 * nrm(knock)
    return reverb(lp(y, 7000), wet=0.1, rt60=0.35, predelay=0.004, seed=42)


@recipe("ui/hover", variants=1, fade_out=0.02, max_len=0.07)
def ui_hover(v, rng):
    dur = 0.07
    ex = bp(white(0.001, rng), 800, 9000)
    wood = resonators(fit(ex, ns(dur)), [1820, 3150, 4900], [0.03, 0.02, 0.012], [1, .5, .25])
    return lp(nrm(wood), 8000)


@recipe("ui/select", variants=1, fade_out=0.1, max_len=0.9)
def ui_select(v, rng):
    dur = 0.9
    t = tvec(dur)
    first = chime(dur, 659.25, rng, t60=0.6, bright=0.5)
    second = chime(dur, 987.77, rng, t60=0.8, bright=0.45) * np.clip((t - 0.07) / 0.003, 0, 1)
    second = np.concatenate([np.zeros(ns(0.07)), second[: len(t) - ns(0.07)]])
    y = 0.55 * nrm(first) + 0.5 * nrm(second)
    return reverb(lp(y, 10000), wet=0.16, rt60=0.7, predelay=0.008, hf_damp=0.5, seed=43)


@recipe("ui/page", variants=2, fade_out=0.06, max_len=0.42)
def ui_page(v, rng):
    dur = 0.42
    y = paper(dur, rng, peak=0.35 + 0.1 * v, lo=700 + 200 * v, hi=7000, grain=1300)
    flick = bp(white(dur, rng), 2500, 9000) * np.exp(-tvec(dur) / 0.01)
    return small_room(fit(y, ns(dur)) + 0.2 * nrm(flick), 0.08)


@recipe("ui/text", variants=3, peak_db=-12.0, fade_out=0.01, max_len=0.04)
def ui_text(v, rng):
    """a quill nib's tick as words appear: very short and quiet, a little different each time"""
    dur = 0.04
    ex = bp(white(0.0008, rng), 1500, 10000)
    f = 2400 + 260 * v
    nib = resonators(fit(ex, ns(dur)), [f, f * 1.73], [0.012, 0.008], [1, .4])
    return lp(nrm(nib), 9000)


@recipe("ui/death_toll", variants=1, fade_out=1.2, max_len=5.0)
def ui_death_toll(v, rng):
    """a great bell tolls once, far off, over a low swell: the ledger closes on another name"""
    dur = 5.0
    t = tvec(dur)
    ratios = metal_partials(1.0, "bell", n=12)
    f0 = 98.0
    freqs = [f0 * r * 2 for r in ratios]
    t60s = [3.8 * (1.0 - 0.07 * i) for i in range(len(freqs))]
    amps = [0.9, 1.0, 0.6, 0.45, 0.5, 0.3, 0.25, 0.2, 0.12, 0.08, 0.05, 0.04]
    toll = modal(dur, freqs, t60s, amps, rng) * np.clip(t / 0.003, 0, 1)
    strike = thump(dur, 140, 70, rng, decay=0.12, click=0.25, noise=0.5, nlp=600)
    swell = lp(colored(dur, rng, -4.0), 220) * bell(dur, 0.35, 2.0, 2.2)
    hum = (np.sin(2 * np.pi * 49 * t) + 0.5 * np.sin(2 * np.pi * 73.5 * t)) * bell(dur, 0.3, 1.5, 2.5)
    y = nrm(toll) + 0.35 * nrm(strike) + 0.25 * nrm(swell) + 0.22 * nrm(hum)
    return reverb(y, wet=0.45, rt60=3.2, predelay=0.03, hf_damp=0.6, seed=44)


@recipe("ui/seal", variants=1, fade_out=0.08, max_len=0.5)
def ui_seal(v, rng):
    """the wax seal pressed onto the page: a soft heavy thud and a sticky lift"""
    dur = 0.5
    t = tvec(dur)
    press = thump(dur, 120, 60, rng, decay=0.07, click=0.1, noise=0.6, nlp=500)
    lift = bp(white(dur, rng), 400, 2500) * np.exp(-np.maximum(0, t - 0.18) / 0.02) * (t > 0.18)
    y = nrm(press) + 0.18 * nrm(lift)
    return small_room(lp(y, 6000), 0.1)


@recipe("ui/discover", variants=1, fade_out=0.9, max_len=3.4)
def ui_discover(v, rng):
    """a new place found: a soft drum, three bells climbing a fifth and an octave over a held chord"""
    dur = 3.4
    t = tvec(dur)

    def late(x, start):
        n = ns(start)
        return np.concatenate([np.zeros(n), x[: len(t) - n]])

    drum = thump(dur, 92, 54, rng, decay=0.2, click=0.06, noise=0.3, nlp=380)
    first = chime(dur, 392.0, rng, t60=1.3, bright=0.4)
    second = late(chime(dur, 587.33, rng, t60=1.7, bright=0.42), 0.17)
    third = late(chime(dur, 783.99, rng, t60=2.1, bright=0.34), 0.34)
    pad = spirit_pad(dur, rng, [196.0, 293.66, 392.0], vowel="o") * bell(dur, 0.42, 1.6, 2.4)
    y = 0.5 * nrm(drum) + 0.45 * nrm(first) + 0.42 * nrm(second) + 0.36 * nrm(third) + 0.26 * nrm(pad)
    return reverb(lp(y, 9000), wet=0.3, rt60=2.4, predelay=0.02, hf_damp=0.55, seed=45)


# =========================================================================== main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    sel = a.only.split(",") if a.only else None
    for path, r in RECIPES.items():
        if sel and not any(path == s or path.startswith(s) for s in sel):
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            rng = rng_for(path, v)
            x = r["fn"](v, rng)
            ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
            x = finalize(x, peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])
            name = f"{path}{v + 1}"
            write_ogg(os.path.join(a.assets, "sounds", name + ".ogg"), x)
            if a.wav:
                write_wav(os.path.join(a.wav, name + ".wav"), x)
            print(f"{name:34s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s", flush=True)


if __name__ == "__main__":
    main()
