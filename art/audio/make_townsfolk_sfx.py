"""Guildmark townsfolk: the sounds one townsperson makes at work and at play, and the bard's four lute tunes.

usage (from the repo root):
    python3 -B art/audio/make_townsfolk_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]
        [--work DIR] [--sf2 FluidR3_GM.sf2]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/townsfolk/: every effect as <group>/<name><n>.ogg
(the event townsfolk.smith.hammer is smith/hammer1..4, townsfolk.clap is clap1..3) and the tunes as
bard/tune1..4.ogg. --only takes event names such as smith.hammer, a group prefix such as tavern., or tunes
(or bard.tune). --wav also writes 16-bit WAV copies for listening; --work is where the tunes' MIDI and
FluidSynth renders go (default build/townsfolk_music_work).

The members of the three factions have work sounds too: a scholar's mage.page, mage.quill and mage.conjure, a knight's
knight.clash, knight.post and knight.whetstone, a thief's thief.shake, thief.dice, thief.coin and thief.lockpick
(make_faction_audio.py makes the factions' own sounds). The town crier has the market board's and their scroll's:
crier.chalk, crier.chalk_tap, crier.pin, crier.unroll and crier.roll_up.

Everything is synthesised (numpy/scipy through sfxkit) or, for the tunes, composed here and played by
FluidSynth's General MIDI nylon guitar. These are close-up sounds timed to the townsfolk's animations (the
event fires on the tick the hammer meets the anvil), so each one is dry apart from a small room. They are not
levelled by peak like make_sfx.py's: each is set to a loudness target (the loudest 100 ms, K-weighted, as in
EBU R128), which keeps a long hiss and a short clap in proportion to each other and to the combat sounds;
peaks are held under -1 dBFS. All randomness is seeded, so a rerun is bit-identical.
"""
import argparse
import os
import sys
import time
from fractions import Fraction

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403

SOUND_DIR = "townsfolk"
SF2_DEFAULT = "/usr/share/sounds/sf2/FluidR3_GM.sf2"

EVENTS = {}  # event (without the "townsfolk." prefix) -> recipe


def effect(event, variants=1, subtitle="", loud=-16.0, max_len=None, fade_out=0.03, tail_db=-62.0,
           attenuation=None, limit=2.0):
    """registers a sound effect. loud: target for the loudest 100 ms (K-weighted LUFS); limit: the most dB of
    peak limiting allowed to get there; attenuation: the distance it carries, in blocks, when more than 16."""
    def deco(fn):
        EVENTS[event] = dict(kind="effect", fn=fn, variants=variants, subtitle=subtitle, loud=loud, max_len=max_len,
                             fade_out=fade_out, tail_db=tail_db, attenuation=attenuation, limit=limit, stream=False)
        return fn
    return deco


def sound_path(event, n):
    """'smith.hammer', 1 -> 'townsfolk/smith/hammer1'"""
    return f"{SOUND_DIR}/{event.replace('.', '/')}{n}"


# ------------------------------------------------------------------ loudness
def kweight(x):
    """BS.1770 K-weighting: a +4 dB shelf above ~1.5 kHz and a 38 Hz high-pass"""
    def biquad(gain_db, q, fc, kind):
        A = 10 ** (gain_db / 40.0)
        w0 = 2 * np.pi * fc / SR
        alpha = np.sin(w0) / (2 * q)
        c = np.cos(w0)
        if kind == "shelf":
            b = [A * ((A + 1) + (A - 1) * c + 2 * np.sqrt(A) * alpha), -2 * A * ((A - 1) + (A + 1) * c),
                 A * ((A + 1) + (A - 1) * c - 2 * np.sqrt(A) * alpha)]
            a = [(A + 1) - (A - 1) * c + 2 * np.sqrt(A) * alpha, 2 * ((A - 1) - (A + 1) * c),
                 (A + 1) - (A - 1) * c - 2 * np.sqrt(A) * alpha]
        else:
            b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
            a = [1 + alpha, -2 * c, 1 - alpha]
        return np.array(b) / a[0], np.array(a) / a[0]
    y = signal.lfilter(*biquad(4.0, 1 / np.sqrt(2), 1500.0, "shelf"), x)
    return signal.lfilter(*biquad(0.0, 0.5, 38.0, "hp"), y)


def loudness_curve(x, win=0.1, hop=0.005):
    """K-weighted loudness (LUFS) in sliding windows; zero-padded so a short sound still fills one window"""
    k = kweight(np.concatenate([x, np.zeros(ns(win))]))
    w, h = ns(win), max(1, ns(hop))
    c = np.concatenate([[0.0], np.cumsum(k * k)])
    starts = np.arange(0, max(1, len(k) - w), h)
    return -0.691 + 10 * np.log10(np.maximum((c[starts + w] - c[starts]) / w, 1e-20))


def integrated_loudness(x):
    """EBU R128 integrated loudness (400 ms blocks, 75% overlap, absolute and relative gates)"""
    m = loudness_curve(x, 0.4, 0.1)
    m = m[m > -70]
    if not len(m):
        return -99.0
    rel = -0.691 + 10 * np.log10(np.mean(10 ** ((m + 0.691) / 10))) - 10
    g = m[m > rel]
    return float(-0.691 + 10 * np.log10(np.mean(10 ** ((g + 0.691) / 10))))


def true_peak(x):
    return float(np.max(np.abs(signal.resample_poly(x, 4, 1)))) + 1e-12


def level(x, target, ceiling_db=-1.0, measure="short", max_limit_db=2.0):
    """scale x so its loudness hits target (LUFS) and hold the true peak under ceiling_db with a lookahead
    limiter, raising the gain again for the loudness the limiter took. A very short, sharp sound (a clap)
    would need more than max_limit_db of limiting to get there; it stops at that and stays under the target.
    Returns (y, dB limited, dB short of target)."""
    def loudness(z):
        return float(np.max(loudness_curve(z))) if measure == "short" else integrated_loudness(z)
    peak = 20 * np.log10(true_peak(x))
    cap = ceiling_db + max_limit_db - peak  # the most gain allowed: the peak may go max_limit_db over the ceiling
    gain = target - loudness(x)
    y, g, now = x, 0.0, loudness(x)
    for _ in range(5):
        g = min(gain, cap)
        y = x * db(g)
        if 20 * np.log10(true_peak(y)) > ceiling_db:
            for _ in range(4):
                y = limiter(y, ceiling=db(ceiling_db - 0.3), lookahead=0.0015, release=0.04)
                if 20 * np.log10(true_peak(y)) <= ceiling_db:
                    break
        now = loudness(y)
        if abs(target - now) < 0.05 or g >= cap:
            break
        gain += target - now
    return y, max(0.0, peak + g - ceiling_db), max(0.0, target - now)


# ------------------------------------------------------------------ shared helpers
def nrm(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def late(x, at, n=None):
    """x delayed by `at` seconds (zero-padded), optionally fitted to n samples"""
    y = np.concatenate([np.zeros(ns(at)), x])
    return fit(y, n) if n else y


def small_room(x, wet=0.1, rt60=0.4, seed=3, predelay=0.005):
    """a small timber-and-stone room: a few close reflections, a short tail"""
    return reverb(x, wet=wet, rt60=rt60, predelay=predelay, hf_damp=0.55, seed=seed, early_span=0.02)


def street(x, wet=0.1, seed=7):
    """an open street between houses: a slap off the walls and very little tail"""
    return reverb(x, wet=wet, rt60=0.55, predelay=0.012, hf_damp=0.6, seed=seed, early_span=0.05)


def ring(dur, freqs, t60s, amps, rng, doublet=0.0016, doublet_amp=0.55, glide=0.0, attack=0.0004):
    """modal ring where every mode is a close pair (as on a real casting), so the ring shimmers as it dies"""
    freqs = np.asarray(freqs, dtype=float)
    y = modal(dur, freqs, t60s, amps, rng, glide=glide, attack=attack)
    if doublet:
        split = 1 + doublet * rng.uniform(0.4, 1.6, len(freqs))
        y = y + doublet_amp * modal(dur, freqs * split, np.asarray(t60s) * 0.92, amps, rng, glide=glide,
                                    attack=attack)
    return y


def knock(dur, freqs, t60s, amps, rng, lo=300, hi=9000, ex_len=0.0012):
    """a struck body: a short noise excitation through a bank of resonators (wood, clay, stone)"""
    ex = bp(white(ex_len, rng), lo, hi) * np.hanning(ns(ex_len) + 2)[1:-1]
    return resonators(fit(ex, ns(dur)), freqs, t60s, amps)


def thud(dur, rng, f=110.0, decay=0.018, tone=0.35):
    """a dull, damped body blow (dough, soil, a sack): mostly low noise, only a hint of a pitched thump"""
    t = tvec(dur)
    nz = lp(white(dur, rng), f * 3.5, order=2) * np.exp(-t / decay) * np.clip(t / 0.0015, 0, 1)
    th = sine_sweep(dur, f * 1.25, f * 0.7, 4.0) * np.exp(-t / (decay * 0.9)) * np.clip(t / 0.001, 0, 1)
    return nrm(nrm(nz) + tone * nrm(th))


def grains(dur, rng, count, start, spread, f_lo, f_hi, t60=(0.01, 0.03), amp_decay=0.3, partials=(1.0, 1.47, 2.3)):
    """a scatter of tiny struck things (grit, pebbles, droplets of scale) after `start`"""
    m = Mix(dur)
    for _ in range(count):
        tt = start + rng.exponential(spread)
        if tt > dur - 0.02:
            continue
        f = np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))
        tl = rng.uniform(*t60)
        p = knock(min(dur - tt, tl * 1.5 + 0.01), [f * r for r in partials], [tl, tl * 0.7, tl * 0.5][:len(partials)],
                  [1, .6, .4][:len(partials)], rng, lo=600, hi=12000, ex_len=0.0006)
        m.add(nrm(p), tt, rng.uniform(0.3, 1.0) * np.exp(-(tt - start) / max(amp_decay, 1e-3)))
    return fit(m.out(), ns(dur))


def coin(dur, rng, f0=3300.0, t60=0.25, bright=1.0, click=0.35):
    """a coin struck: the modes of a thin metal disc (1, 1.73, 2.33, 3.91 and 5.95 times the lowest, the upper ones dying
    first), each a close pair that beats (no coin is quite round), and the tick of the blow. t60 is how long the lowest
    mode rings (a coin set on a stack is damped to a few hundredths of a second, one on stone rings for a quarter) and
    bright the level of the upper modes"""
    r = np.array([1.0, 1.73, 2.33, 3.91, 5.95]) * rng.uniform(0.992, 1.008, 5)
    amps = np.array([1.0, 0.7, 0.5, 0.3, 0.16]) * np.array([1.0, bright, bright, bright, bright])
    y = ring(dur, f0 * r, t60 * np.array([1.0, 0.78, 0.6, 0.4, 0.25]), amps, rng, doublet=0.004, doublet_amp=0.7)
    tt = tvec(0.004)  # the tick comes in over a fifth of a millisecond, so that it starts from zero
    tick = fit(bp(white(0.004, rng), 2500, 14000) * np.exp(-tt / 0.0005) * np.clip(tt / 0.0002, 0, 1), len(y))
    return nrm(nrm(y) + click * nrm(tick))


def lead_in(x, t=0.002):
    """x with a couple of milliseconds of silence in front of it. The file's own 25 Hz zero-phase high-pass pads a sound's
    ends with an odd extension, as if its first few samples had always been there, so a sound that starts on a loud sample
    comes out with a low thump after its start; with silence in front there is no such thump (and finalize() trims the
    silence off again)"""
    return np.concatenate([np.zeros(ns(t)), np.asarray(x, dtype=float)])


# =========================================================================== the smith
def anvil(dur, rng, f0, strength=1.0, hot=1.0, t60=0.8, glide=0.0025):
    """one hammer blow: the hammer squashing hot iron (a dull, heavy 'tchunk'), the anvil ringing under it
    (a bright strike tone with a near-fifth and near-octave above it, every mode a shimmering pair), the
    hammer head's own ping and the weight of the block through the stump"""
    t = tvec(dur)
    ratios = np.array([1.0, 1.497, 2.012, 2.64, 3.18, 3.93, 4.71, 5.62, 6.55])
    amps = np.array([1.0, 0.42, 0.55, 0.3, 0.26, 0.18, 0.12, 0.08, 0.05])
    amps[1:] *= strength ** np.linspace(0.3, 1.6, len(amps) - 1)  # a harder blow lights up the upper modes
    t60s = t60 * np.array([1.0, 0.78, 0.66, 0.46, 0.38, 0.29, 0.22, 0.17, 0.13])
    face = ring(dur, f0 * ratios, t60s, amps, rng, glide=glide)
    body = ring(dur, f0 * np.array([0.287, 0.415, 0.63]), [0.22, 0.16, 0.1], [0.8, 0.6, 0.35], rng, doublet=0.004)
    m = Mix(dur)
    m.add(nrm(face), 0.0, 1.0)
    m.add(nrm(body), 0.0, 0.32 + 0.1 * hot)
    # the contact itself: steel meeting steel through a skin of scale
    m.add(nrm(bp(white(0.006, rng), 1800, 15000) * np.exp(-tvec(0.006) / 0.0007)), 0.0, 0.55 * strength)
    # hot iron squashed under the face: a short, dull mid knock
    if hot:
        chunk = knock(0.12, [430, 760, 1180, 1650], [0.05, 0.04, 0.028, 0.02], [1, .8, .5, .3], rng, lo=200,
                      hi=5000, ex_len=0.0025)
        m.add(nrm(chunk), 0.0003, 0.55 * hot)
    m.add(nrm(thump(0.25, 175, 95, rng, decay=0.025, click=0.05, noise=0.35, nlp=700)), 0.0, 0.6 * (0.6 + 0.4 * hot))
    head = modal(0.12, [5150 * rng.uniform(0.97, 1.03), 7480, 9300], [0.07, 0.05, 0.035], [1, .55, .3], rng)
    m.add(nrm(head), 0.0002, 0.12 * strength)
    y = fit(m.out(), ns(dur))
    return y * np.clip(t / 0.0002, 0, 1)


def scale_sparks(dur, rng, count, start=0.02):
    """flakes of scale and sparks flying off the hot iron: tiny dry pops and ticks"""
    return grains(dur, rng, count, start, 0.09, 2500, 9000, t60=(0.004, 0.012), amp_decay=0.25)


@effect("smith.hammer", variants=4, subtitle="Hammer rings on anvil", loud=-12.0, max_len=0.95, fade_out=0.22,
        attenuation=24)
def smith_hammer(v, rng):
    dur = 0.95
    f0 = [1262.0, 1318.0, 1215.0, 1290.0][v]
    m = Mix(dur)
    m.add(anvil(dur, rng, f0, strength=[1.0, 0.92, 1.08, 0.97][v], hot=[1.0, 0.8, 1.15, 0.9][v],
                t60=[0.82, 0.74, 0.9, 0.78][v]), 0.0, 1.0)
    m.add(nrm(scale_sparks(dur, rng, [7, 5, 10, 6][v])), 0.0, 0.07)
    if v == 1:  # the hammer let bounce on the bare face: a light, bright 'tink'
        m.add(anvil(0.6, rng, f0 * 1.003, strength=1.25, hot=0.0, t60=0.5), 0.17, 0.2)
    if v == 3:
        m.add(anvil(0.5, rng, f0 * 0.998, strength=1.2, hot=0.0, t60=0.45), 0.2, 0.13)
    y = drive(nrm(fit(m.out(), ns(dur))), 1.2)
    return small_room(y, wet=0.09, rt60=0.42, seed=11)


def fire(dur, rng, s, lo=260.0, hi=1500.0, crackles=200.0):
    """a forge or oven fire breathing with the swell s (0..1 per sample): a low roar, a banded rush, crackle"""
    roar = lp(brown(dur, rng), 200, order=4) * s ** 1.2
    rush = tvf(pink(dur, rng), "bp", lo * (hi / lo) ** s, 0.7) * s ** 1.5 * (0.7 + 0.3 * smooth_random(dur, rng, 11))
    crk = crackle(dur, rng, 25 + crackles * s, decay=0.0009, f_lo=1400, f_hi=8000) * (0.3 + 0.7 * s)
    return nrm(roar), nrm(rush), nrm(crk)


def creak(dur, rng, rate0, rate1, freqs, t60=0.05):
    """wood or leather under strain: stick-slip friction (a speeding train of grains) through the frame's modes"""
    t = tvec(dur)
    fr = crackle(dur, rng, rate0 + (rate1 - rate0) * t / dur, decay=0.0007, f_lo=120, f_hi=3500, size_var=0.35)
    k = np.arange(len(freqs))
    y = resonators(fr, freqs, t60 * 0.8 ** k, 0.75 ** k)
    return y * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.7


@effect("smith.bellows", variants=2, subtitle="Bellows roar the forge", loud=-16.0, max_len=1.7, fade_out=0.35)
def smith_bellows(v, rng):
    """the great leather bellows pumped once: the lever creaks down, air rushes out through the nozzle and the
    coals roar up in answer, crackling; the valve flap drops shut as the bellows fill again"""
    dur = 1.7
    m = Mix(dur)
    dc = [0.3, 0.4][v]
    m.add(nrm(creak(dc, rng, [90, 60][v], [220, 170][v], [255, 410, 600, 840, 1230], 0.05)), 0.0, [0.22, 0.3][v])
    da = 0.95
    s = bell(da, [0.42, 0.36][v], 1.3, 1.7)
    air = tvf(colored(da, rng, -1.5), "bp", 330 * 2 ** (1.7 * s), 1.1) * s ** 1.5
    nozzle = tvf(white(da, rng), "bp", [610, 540][v] * (1 + 0.12 * s), 7.0) * s ** 2
    m.add(nrm(nrm(air) + 0.22 * nrm(nozzle)), 0.07, 0.62)
    fd = 1.45
    sf = bell(fd, [0.3, 0.27][v], 1.5, 1.8)
    roar, rush, crk = fire(fd, rng, sf, crackles=320)
    m.add(roar, 0.2, 0.8)
    m.add(rush, 0.2, 0.55)
    m.add(crk, 0.2, 0.2)
    flap = knock(0.1, [360, 690, 1130], [0.035, 0.025, 0.018], [1, .6, .3], rng, lo=150, hi=4000, ex_len=0.002)
    m.add(nrm(flap), [1.08, 1.0][v], 0.1)
    y = fit(m.out(), ns(dur))
    return small_room(lp(y, 9000), wet=0.08, rt60=0.42, seed=12 + v)


def bubbles(dur, rng, rate, f_lo, f_hi, tau=(0.004, 0.016), rise=0.6, size_var=0.6):
    """bubbles ringing as they form and rise (Minnaert resonance, pitch climbing as each one shrinks).
    rate: bubbles per second, scalar or per-sample curve"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    idx = np.nonzero(rng.random(n) < rate / SR)[0]
    out = np.zeros(n)
    for i in idx:
        f = np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))
        tau_i = np.exp(rng.uniform(np.log(tau[0]), np.log(tau[1]))) * (900.0 / f) ** 0.5
        L = min(n - i, ns(tau_i * 6))
        if L < 16:
            continue
        tb = np.arange(L) / SR
        fb = f * (1 + rise * tb / (tau_i * 3))
        b = np.sin(phase_from_freq(fb, L)) * np.exp(-tb / tau_i) * np.clip(tb / 0.0004, 0, 1)
        out[i:i + L] += b * np.exp(size_var * rng.standard_normal()) * (900.0 / f) ** 0.3
    return out


@effect("smith.quench", variants=2, subtitle="Hot iron hisses in water", loud=-14.0, max_len=1.6, fade_out=0.4)
def smith_quench(v, rng):
    """the hot iron plunged into the slack tub: a splash and a plunk, then a violent seething hiss that boils
    and sputters as the steam bubbles burst, falling away to a sizzle"""
    dur = 1.6
    t = tvec(dur)
    m = Mix(dur)
    sp = tvec(0.07)
    m.add(nrm(bp(white(0.07, rng), 400, 7000) * np.exp(-sp / 0.014)), 0.0, 0.3)
    plunk = sine_sweep(0.11, [230, 260][v], [520, 610][v], 2.0) * np.exp(-tvec(0.11) / 0.028)
    m.add(nrm(plunk), 0.003, 0.22)
    hold = [0.32, 0.4][v]
    env = np.clip(t / 0.012, 0, 1) * (0.22 + 0.78 * np.exp(-np.maximum(0, t - 0.05) / hold)) * np.exp(-t / 1.4)
    sput = (0.5 + 0.5 * smooth_random(dur, rng, [17, 21][v])) ** 1.4
    hiss = tvf(white(dur, rng), "bp", 5600 - 2000 * np.clip(t / 1.3, 0, 1), 0.8) + 0.45 * hp(white(dur, rng), 7500)
    seethe = crackle(dur, rng, 14000 * env + 300, decay=0.00022, f_lo=1500, f_hi=12500, size_var=0.75)
    m.add(nrm(hiss) * env * sput, 0.006, 0.9)
    m.add(nrm(seethe) * env, 0.006, 0.55)
    boil = bubbles(dur, rng, 900 * env * sput + 25, 500, 2400, tau=(0.003, 0.01), rise=0.8)
    m.add(nrm(boil), 0.006, 0.35)
    blub = bubbles(dur, rng, 26 * np.exp(-t / 0.5) + 2, 140, 420, tau=(0.008, 0.02), rise=0.5, size_var=0.4)
    m.add(nrm(lp(blub, 1500)), 0.02, 0.28)
    y = fit(m.out(), ns(dur))
    return small_room(y, wet=0.07, rt60=0.42, seed=14 + v)


# =========================================================================== the baker
def dough(dur, rng, weight=1.0, slap=1.0, squish=0.5, table=1.0, pitch=1.0):
    """a lump of dough thrown down and pressed on a floury wooden table"""
    t = tvec(dur)
    m = Mix(dur)
    # the wet 'thwap' of the dough's skin meeting the board: a soft-edged burst through a broad mid hump
    sl = tvf(white(dur, rng), "bp", 900 * pitch, 0.9) * np.clip(t / 0.0025, 0, 1) * np.exp(-t / 0.014)
    m.add(nrm(sl), 0.0, 0.8 * slap)
    m.add(thud(dur, rng, 105 * pitch, decay=0.022, tone=0.25), 0.0, 0.6 * weight)
    tab = knock(0.3, np.array([168, 286, 452, 688, 1015]) * pitch, [0.06, 0.05, 0.04, 0.03, 0.022],
                [1, .8, .6, .4, .25], rng, lo=100, hi=3000, ex_len=0.004)
    m.add(nrm(tab), 0.001, 0.42 * table)
    if squish:
        sq = crackle(0.16, rng, 2600 * np.exp(-tvec(0.16) / 0.05), decay=0.0006, f_lo=450, f_hi=3200, size_var=0.6)
        m.add(nrm(sq), 0.012, 0.3 * squish)
    puff = bp(pink(0.2, rng), 1500, 7000) * bell(0.2, 0.15, 1.2, 2.0)
    m.add(nrm(puff), 0.003, 0.08)
    return lp(fit(m.out(), ns(dur)), 7000)


@effect("baker.knead", variants=3, subtitle="Dough slaps the table", loud=-17.0, max_len=0.45, fade_out=0.1)
def baker_knead(v, rng):
    """v1 thrown down with a slap, v2 a heavy press, v3 folded and pushed (a slap and a squish)"""
    dur = 0.45
    y = dough(dur, rng, weight=[0.85, 1.1, 0.8][v], slap=[1.1, 0.55, 0.8][v], squish=[0.35, 0.6, 1.0][v],
              table=[1.0, 0.8, 0.9][v], pitch=[1.0, 0.92, 1.06][v])
    if v == 2:  # the heel of the hand pushing the fold away: a soft sticky drag
        dd = 0.16
        drag = bp(colored(dd, rng, -1.0), 300, 2200) * bell(dd, 0.35, 1.2, 1.5)
        drag *= 0.6 + 0.4 * smooth_random(dd, rng, 50)
        y = y + 0.2 * nrm(fit(late(drag, 0.07), len(y))) * np.max(np.abs(y))
    return small_room(y, wet=0.08, rt60=0.38, seed=20 + v)


@effect("baker.oven", variants=2, subtitle="Peel scrapes the oven", loud=-17.0, max_len=1.3, fade_out=0.35)
def baker_oven(v, rng):
    """the wooden peel knocks on the oven's stone lip and scrapes across the hot floor, the fire crackling inside"""
    dur = 1.3
    m = Mix(dur)
    m.add(nrm(knock(0.12, [540, 960, 1500, 2350], [0.035, 0.026, 0.02, 0.012], [1, .7, .5, .3], rng)), 0.0, 0.45)
    m.add(nrm(knock(0.05, [2900, 4100, 5600], [0.01, 0.008, 0.006], [1, .7, .5], rng, lo=1500)), 0.0, 0.25)
    sd = [0.5, 0.4][v]
    s = bell(sd, [0.3, 0.25][v], 1.2, 1.6)
    gr = crackle(sd, rng, 500 + 1100 * s, decay=0.0005, f_lo=300, f_hi=7000, size_var=0.5)
    board = resonators(gr, [640, 1090, 1720, 2600, 3500], [0.02, 0.018, 0.014, 0.011, 0.008], [1, .8, .6, .45, .3])
    rasp = bp(colored(sd, rng, -0.5), 1300, 6500) * (0.55 + 0.45 * smooth_random(sd, rng, 70))
    m.add(nrm(nrm(board) + 0.5 * nrm(rasp)) * s, 0.03, 0.85)
    if v == 1:  # a second, shorter push to settle the loaf
        s2 = bell(0.22, 0.4, 1.2, 1.5)
        g2 = crackle(0.22, rng, 400 + 800 * s2, decay=0.0005, f_lo=300, f_hi=7000, size_var=0.5)
        b2 = resonators(g2, [640, 1090, 1720, 2600], [0.02, 0.018, 0.014, 0.011], [1, .8, .6, .45])
        m.add(nrm(b2) * s2, 0.5, 0.45)
    fd = 1.2
    sf = bell(fd, 0.22, 1.2, 1.4)
    roar, rush, crk = fire(fd, rng, sf, lo=300, hi=1100, crackles=160)
    m.add(roar, 0.05, 0.25)
    m.add(rush, 0.05, 0.25)
    m.add(crk, 0.05, 0.3)
    y = fit(m.out(), ns(dur))
    return small_room(y, wet=0.1, rt60=0.45, seed=24 + v)


# =========================================================================== the merchant
def box(dur, rng, f0, weight=1.0, hollow_=0.0):
    """a small wooden crate or box set down on the counter: the box's knock and the counter under it"""
    m = Mix(dur)
    k = knock(dur, np.array([1.0, 1.62, 2.41, 3.37, 4.6]) * f0, [0.05, 0.04, 0.03, 0.022, 0.015], [1, .8, .55, .35, .2],
              rng, lo=150, hi=5000, ex_len=0.003)
    if hollow_:
        k = comb(k, 1 / (f0 * 1.9), 0.4, hollow_)
    m.add(nrm(k), 0.0, 0.8)
    counter = knock(dur, [148, 236, 371, 560], [0.07, 0.055, 0.04, 0.03], [1, .8, .5, .3], rng, lo=80, hi=2500,
                    ex_len=0.004)
    m.add(nrm(counter), 0.001, 0.6 * weight)
    m.add(nrm(thump(dur, 140, 80, rng, decay=0.025, click=0.0, noise=0.5, nlp=500)), 0.0, 0.45 * weight)
    return fit(m.out(), ns(dur))


def slide(dur, rng, lo=400, hi=2800, rate=300):
    """something pushed along a plank: a muffled rub with a grain of wood fibres in it"""
    s = bell(dur, 0.4, 1.3, 1.5)
    rub = bp(colored(dur, rng, -1.5), lo, hi) * s
    fib = crackle(dur, rng, rate * s + 10, decay=0.0006, f_lo=lo, f_hi=hi * 1.4, size_var=0.5)
    return nrm(lp(nrm(rub) + 0.45 * nrm(fib), 3500))


def clay(dur, rng, f0):
    """a glazed clay jar set down: a short, dull, high ring"""
    return ring(dur, f0 * np.array([1.0, 1.73, 2.61, 3.9]), [0.07, 0.05, 0.035, 0.02], [1, .5, .3, .15], rng,
                doublet=0.003)


@effect("merchant.arrange", variants=2, subtitle="Goods shuffled on a counter", loud=-17.0, max_len=0.8,
        fade_out=0.12, limit=3.0)
def merchant_arrange(v, rng):
    """v1 a little crate slid over and set down, then a box nudged into line;
    v2 a bundle set down and a clay jar put beside it"""
    dur = 0.8
    m = Mix(dur)
    if v == 0:
        m.add(slide(0.16, rng), 0.0, 0.16)
        m.add(box(0.3, rng, 395, 1.0, 0.25), 0.15, 1.0)
        m.add(slide(0.08, rng, 500, 3000), 0.36, 0.1)
        m.add(box(0.25, rng, 520, 0.5), 0.43, 0.55)
    else:
        m.add(box(0.3, rng, 300, 1.1), 0.0, 0.9)
        m.add(nrm(bp(pink(0.12, rng), 400, 2500) * bell(0.12, 0.2, 1.2, 1.6)), 0.0, 0.15)
        m.add(box(0.2, rng, 610, 0.35), 0.24, 0.4)
        m.add(nrm(clay(0.3, rng, 1180)), 0.245, 0.35)
        m.add(slide(0.1, rng, 500, 2600), 0.32, 0.08)
    y = fit(m.out(), ns(dur))
    return small_room(y, wet=0.08, rt60=0.4, seed=30 + v)


# =========================================================================== the farmer
def clods(dur, rng, count, start=0.0, spread=0.2):
    """earth thrown up by the blade, pattering back down"""
    m = Mix(dur)
    for _ in range(count):
        tt = start + rng.exponential(spread)
        if tt > dur - 0.04:
            continue
        f = rng.uniform(250, 1100)
        c = knock(0.06, [f, f * 1.6], [0.025, 0.015], [1, .4], rng, lo=100, hi=3000, ex_len=0.003)
        m.add(nrm(c), tt, rng.uniform(0.2, 0.7) * np.exp(-(tt - start) / (spread * 1.5)))
    return fit(m.out(), ns(dur))


@effect("farmer.hoe", variants=3, subtitle="Hoe chops the soil", loud=-15.0, max_len=0.6, fade_out=0.12)
def farmer_hoe(v, rng):
    """the blade driven into the soil: a dull thud, the crunch of earth and roots giving way, clods pattering
    back. v2 nicks a stone, v3 drags the soil back"""
    dur = 0.6
    t = tvec(dur)
    m = Mix(dur)
    m.add(thud(0.3, rng, [100, 92, 108][v], decay=0.024, tone=0.3), 0.0, 0.85)
    ct = tvec(0.18)
    crunch = crackle(0.18, rng, 9000 * np.exp(-ct / 0.035), decay=0.0009, f_lo=250, f_hi=3800, size_var=0.9)
    m.add(nrm(crunch), 0.002, 0.85)
    m.add(nrm(tvf(white(0.08, rng), "bp", 700, 0.8) * np.exp(-tvec(0.08) / 0.012)), 0.0, 0.4)
    chk = bp(white(0.012, rng), 1200, 7000) * np.exp(-tvec(0.012) / 0.0018)
    m.add(nrm(chk), 0.0, 0.3)
    m.add(nrm(modal(0.06, [2950, 4420, 6100], [0.03, 0.02, 0.012], [1, .5, .3], rng)), 0.001, 0.05)
    m.add(nrm(knock(0.2, [205, 372, 610], [0.06, 0.045, 0.03], [1, .6, .3], rng, lo=100, hi=2000)), 0.004, 0.22)
    m.add(clods(dur, rng, [8, 6, 7][v], start=0.04, spread=0.08), 0.0, 0.45)
    if v == 1:  # the edge nicks a stone
        m.add(nrm(ring(0.15, [3150, 4870, 6630], [0.06, 0.04, 0.025], [1, .6, .35], rng, doublet=0.002)), 0.003, 0.2)
    if v == 2:  # dragged back through the furrow
        dd = 0.2
        sd = bell(dd, 0.35, 1.3, 1.4)
        drag = lp(bp(colored(dd, rng, -2.0), 200, 1800), 2000) * sd
        gr = crackle(dd, rng, 2200 * sd + 20, decay=0.0009, f_lo=250, f_hi=2600, size_var=0.8)
        m.add(nrm(nrm(drag) + 0.8 * nrm(gr)), 0.22, 0.45)
    y = fit(m.out(), ns(dur)) * np.clip(t / 0.0005, 0, 1)
    return street(lp(y, 8000), wet=0.08, seed=40 + v)


def rustle(dur, rng, peak=0.4, density=700, lo=900, hi=8000):
    """leaves and stalks brushing past each other: a swell of crisp, separate crinkles over a faint hush,
    coming in little gusts as the hand moves"""
    s = bell(dur, peak, 1.3, 1.6)
    gust = (0.35 + 0.65 * smooth_random(dur, rng, 22)) ** 2
    ticks = crackle(dur, rng, density * s * gust + 10, decay=0.0006, f_lo=lo, f_hi=hi, size_var=1.1)
    body = crackle(dur, rng, 0.5 * density * s * gust + 5, decay=0.0015, f_lo=500, f_hi=2500, size_var=0.9)
    hush = bp(pink(dur, rng), 1800, hi) * s ** 1.5 * gust
    return nrm(nrm(ticks) + 0.5 * nrm(body) + 0.18 * nrm(hush))


@effect("farmer.harvest", variants=2, subtitle="Crops rustle and snap", loud=-18.0, max_len=0.85, fade_out=0.15,
        limit=3.0)
def farmer_harvest(v, rng):
    """a hand in among the leaves, then v1 a stalk snapped off, v2 a root pulled out of the ground"""
    dur = 0.85
    m = Mix(dur)
    m.add(rustle(0.5, rng, peak=0.45), 0.0, 0.75)
    if v == 0:
        snap = knock(0.06, [1850, 2930, 4310, 6200], [0.014, 0.011, 0.008, 0.005], [1, .8, .6, .35], rng, lo=1000,
                     hi=12000, ex_len=0.0005)
        m.add(nrm(snap), 0.31, 1.0)
        m.add(nrm(bp(white(0.004, rng), 3000, 14000) * np.exp(-tvec(0.004) / 0.0006)), 0.31, 0.3)
        m.add(rustle(0.3, rng, peak=0.15, density=500), 0.33, 0.45)
    else:
        tear_t = tvec(0.2)
        tear = crackle(0.2, rng, 300 + 3500 * (tear_t / 0.2) ** 2, decay=0.0008, f_lo=200, f_hi=2600, size_var=0.8)
        m.add(nrm(tear) * (tear_t / 0.2), 0.2, 0.55)
        m.add(nrm(thump(0.2, 185, 92, rng, decay=0.03, click=0.05, noise=0.6, nlp=700)), 0.4, 0.8)
        m.add(clods(0.4, rng, 6, start=0.0, spread=0.07), 0.41, 0.45)
        m.add(rustle(0.25, rng, peak=0.2, density=450), 0.41, 0.35)
    y = fit(m.out(), ns(dur))
    return street(y, wet=0.07, seed=44 + v)


# =========================================================================== the fisher
def plop(dur, rng, f0=750, size=1.0, fizz=10.0):
    """a small thing landing in water: a bubble's rising 'plip', a little splash and a droplet or two"""
    m = Mix(dur)
    bt = tvec(0.06)
    m.add(np.sin(phase_from_freq(f0 * (1 + 0.9 * bt / 0.06), len(bt))) * np.exp(-bt / (0.012 * size)), 0.004, 1.0)
    m.add(nrm(bp(white(0.06, rng), 500, 4000) * np.exp(-bt / (0.012 * size))), 0.0, 0.5)
    if fizz and dur > 0.06:
        m.add(bubbles(dur - 0.04, rng, fizz * np.exp(-tvec(dur - 0.04) / 0.1), 900, 2600, tau=(0.003, 0.008),
                      rise=0.9), 0.04, 0.3)
    return fit(m.out(), ns(dur))


@effect("fisher.cast", variants=2, subtitle="Fishing line cast", loud=-16.0, max_len=1.25, fade_out=0.2)
def fisher_cast(v, rng):
    """the rod whipped forward (a thin whistling swish), the line running out, and out on the water a soft plop"""
    dur = 1.25
    t = tvec(dur)
    m = Mix(dur)
    whip = whoosh(0.32, rng, peak=[0.5, 0.45][v], f_lo=900, f_hi=[5600, 6400][v], q=1.6, power=2.4, rise=1.6, fall=1.1,
                  whistle=0.6, whistle_mult=1.3, whistle_q=14.0, color=0.0, doppler=0.1)
    m.add(nrm(whip), 0.0, 1.0)
    zd = 0.45
    zt = tvec(zd)
    zip_ = tvf(white(zd, rng), "bp", 6500 - 2500 * zt / zd, 4.0) * np.exp(-zt / 0.16) * np.clip(zt / 0.02, 0, 1)
    zip_ *= 1 - 0.4 * (0.5 + 0.5 * np.sin(2 * np.pi * 38 * zt))
    m.add(nrm(zip_), 0.13, 0.18)
    at = [0.78, 0.86][v]
    p = plop(0.4, rng, f0=[720, 820][v])
    p = street(lp(p, 3800), wet=0.18, seed=50 + v)
    m.add(nrm(p), at, 0.32)
    y = fit(m.out(), ns(dur))
    return street(y, wet=0.06, seed=52 + v)


@effect("fisher.reel", variants=2, subtitle="Fishing reel ratchets", loud=-16.0, max_len=1.1, fade_out=0.15)
def fisher_reel(v, rng):
    """the rod yanked up: the line tears out of the water in a splash (droplets raining back) and the reel's
    pawl ratchets as the line comes in"""
    dur = 1.1
    m = Mix(dur)
    st = tvec(0.3)
    tear = tvf(white(0.3, rng), "bp", 2400 - 1400 * np.clip(st / 0.15, 0, 1), 0.7)
    splash = tear * np.exp(-st / 0.06) * np.clip(st / 0.008, 0, 1) * (0.6 + 0.4 * smooth_random(0.3, rng, 60))
    m.add(nrm(splash), 0.0, 0.8)
    m.add(nrm(bubbles(0.3, rng, 700 * np.exp(-tvec(0.3) / 0.08), 450, 2400, tau=(0.003, 0.012), rise=0.9)), 0.004,
          0.5)
    drops = Mix(0.7)
    for _ in range(11):
        tt = 0.1 + rng.exponential(0.13)
        if tt < 0.62:
            drops.add(plop(0.05, rng, f0=rng.uniform(1100, 2400), size=0.35, fizz=0), tt, rng.uniform(0.15, 0.5))
    m.add(nrm(drops.out()), 0.0, 0.35)
    # the ratchet: [start, end, clicks per second at start, at end]
    runs = [[(0.06, 0.82, 26.0, 13.0)], [(0.05, 0.4, 24.0, 20.0), (0.5, 0.88, 22.0, 12.0)]][v]
    for a, b, r0, r1 in runs:
        tt = a
        while tt < b:
            u = (tt - a) / (b - a)
            click = knock(0.04, [2350, 3720, 5150, 940], [0.012, 0.009, 0.006, 0.02], [1, .7, .45, .5], rng, lo=600,
                          hi=12000, ex_len=0.0004)
            m.add(nrm(click), tt, 0.6 * (0.75 + 0.25 * rng.random()) * (1 - 0.35 * u))
            tt += 1.0 / (r0 + (r1 - r0) * u) * rng.uniform(0.9, 1.1)
    y = fit(m.out(), ns(dur))
    return street(y, wet=0.07, seed=56 + v)


# =========================================================================== anyone
@effect("clap", variants=3, subtitle="Someone claps", loud=-14.0, max_len=0.3, fade_out=0.08, limit=3.5)
def clap(v, rng):
    """one clap of the hands: the snap of skin on skin and the pocket of air between the palms (v1 flat and
    bright, v2 cupped and hollow, v3 in between)"""
    dur = 0.3
    t = tvec(dur)
    f_res, t60, bright, exd = [(1720, 0.03, 0.8, 0.0045), (1040, 0.036, 0.5, 0.004), (1360, 0.03, 0.75, 0.004)][v]
    ex = white(dur, rng) * np.exp(-t / exd) * np.clip(t / 0.0004, 0, 1)
    pocket = resonators(ex, [f_res, f_res * 1.9], [t60, t60 * 0.6], [1.0, 0.25])
    broad = bp(ex, 550, 6500)
    snap = hp(ex, 2600) * np.exp(-t / 0.0015)
    palms = thump(0.06, 270, 190, rng, decay=0.007, click=0.0, noise=0.3, nlp=900)
    m = Mix(dur)
    m.add(nrm(pocket), 0.0, 0.85)
    m.add(nrm(broad), 0.0, 0.7)
    m.add(nrm(snap), 0.0, 0.4 * bright)
    m.add(nrm(palms), 0.0, 0.2)
    y = drive(nrm(fit(m.out(), ns(dur))), 2.2)
    return small_room(y, wet=0.14, rt60=0.4, seed=60 + v)


@effect("sweep", variants=3, subtitle="Broom sweeps", loud=-18.0, max_len=0.5, fade_out=0.1)
def sweep(v, rng):
    """one stroke of a twig broom on flagstones: a bright brushing swish, the bristle tips flicking, a little
    grit skittering (v3)"""
    dur = 0.5
    sd = [0.38, 0.28, 0.34][v]
    t = tvec(sd)
    s = bell(sd, [0.35, 0.3, 0.4][v], 1.2, 1.4)
    scratch = (0.3 + 0.7 * smooth_random(sd, rng, 70)) ** 1.5  # bundles of twigs catching on the joints
    fc = 1700 * 2 ** (0.9 * s)
    swish = tvf(colored(sd, rng, -0.5), "bp", fc, 0.9) * s ** 1.3 * scratch
    body = bp(colored(sd, rng, -1.5), 350, 1800) * s ** 1.5
    tips = crackle(sd, rng, 4500 * s * scratch + 50, decay=0.0003, f_lo=1500, f_hi=8000, size_var=0.9)
    stroke = nrm(swish) + 0.35 * nrm(body) + 0.7 * nrm(tips) * s
    m = Mix(dur)
    m.add(nrm(stroke), 0.0, 1.0)
    if v == 2:
        m.add(grains(dur, rng, 9, 0.05, 0.09, 1800, 5500, t60=(0.008, 0.02), amp_decay=0.3), 0.0, 0.3)
    y = lp(fit(m.out(), ns(dur)), 8500)
    return street(y, wet=0.08, seed=64 + v)


# =========================================================================== the tavern
@effect("tavern.sip", variants=2, subtitle="Someone gulps", loud=-18.0, max_len=0.5, fade_out=0.08)
def tavern_sip(v, rng):
    """a mouthful of ale drawn in and swallowed: a little slurp, then the wet click and dull 'glunk' of the gulp
    (v2 two quick gulps)"""
    dur = 0.5
    m = Mix(dur)
    sd = 0.12
    st = tvec(sd)
    slurp = formants(colored(sd, rng, -1.0) * (0.6 + 0.4 * smooth_random(sd, rng, 45)), "u", "o", scale=1.1)
    m.add(nrm(lp(slurp, 3500)) * bell(sd, 0.6, 1.3, 1.2), 0.0, 0.22)
    gulps = [0.13] if v == 0 else [0.1, 0.27]
    for i, at in enumerate(gulps):
        gt = tvec(0.12)
        f0 = [480, 520][v] * (0.94 ** i)
        fg = f0 * (0.42 + 0.58 * np.exp(-gt / 0.03))
        glunk = np.sin(phase_from_freq(fg, len(gt))) * np.exp(-gt / 0.028)
        wet = tvf(white(0.12, rng), "bp", fg * 1.1, 3.0) * np.exp(-gt / 0.022)  # the throat's wet noise on it
        glunk = (nrm(glunk) + 0.55 * nrm(wet)) * np.clip(gt / 0.002, 0, 1)
        m.add(nrm(glunk), at + 0.006, 0.9 * (0.85 ** i))
        m.add(nrm(bp(white(0.006, rng), 900, 4500) * np.exp(-tvec(0.006) / 0.0012)), at, 0.35)
        m.add(nrm(thump(0.1, 165, 90, rng, decay=0.02, click=0.0, noise=0.3, nlp=500)), at + 0.004, 0.45)
    y = lp(fit(m.out(), ns(dur)), 6000)
    return small_room(y, wet=0.08, rt60=0.38, seed=70 + v)


def tankard(dur, rng, kind, f0):
    """one tankard's voice when knocked: pewter rings (soft metal, dull and short), wood knocks hollow"""
    if kind == "pewter":
        r = np.array([1.0, 1.58, 2.27, 3.08, 4.03, 5.2])
        return ring(dur, f0 * r, [0.3, 0.22, 0.15, 0.11, 0.08, 0.05], [1, .7, .5, .32, .2, .12], rng, doublet=0.0025)
    k = knock(dur, f0 * np.array([1.0, 1.66, 2.48, 3.55, 4.8]), [0.13, 0.1, 0.075, 0.055, 0.04],
              [1, .8, .55, .35, .2], rng, lo=150, hi=6000, ex_len=0.002)
    return comb(k, 1 / (f0 * 2.3), 0.35, 0.3)


def slosh(dur, rng, rate=7.0):
    """ale sloshing round inside (a pint pot's slosh mode is near 3.5 Hz, so a slap on each wall about 7 times
    a second): one wave slapping the far wall and a smaller one back, a few bubbles"""
    t = tvec(dur)
    wobble = 1 + 0.25 * (2 * smooth_random(dur, rng, 6) - 1)
    wave = (0.5 + 0.5 * np.sin(phase_from_freq(rate * wobble, len(t)) - 1.2)) ** 2
    env = np.clip(t / 0.03, 0, 1) * np.exp(-t / (dur * 0.3))
    sl = bp(colored(dur, rng, -1.5), 250, 1500) * wave * env * (0.5 + 0.5 * smooth_random(dur, rng, 40))
    b = bubbles(dur, rng, 60 * env, 400, 1500, tau=(0.004, 0.01), rise=0.7)
    return nrm(nrm(sl) + 0.5 * nrm(b))


@effect("tavern.clink", variants=3, subtitle="Tankards clunk together", loud=-15.0, max_len=0.6, fade_out=0.15,
        limit=3.0)
def tavern_clink(v, rng):
    """two tankards knocked together in a toast: v1 pewter on pewter, v2 wood on wood, v3 wood on pewter; the
    ale sloshes in them after"""
    dur = 0.6
    m = Mix(dur)
    pair = [("pewter", 1010, "pewter", 1138), ("wood", 330, "wood", 371), ("wood", 352, "pewter", 1065)][v]
    m.add(nrm(tankard(dur, rng, pair[0], pair[1])), 0.0, 0.85)
    m.add(nrm(tankard(dur, rng, pair[2], pair[3])), 0.0004, 0.75)
    lo, hi = [(1500, 12000), (700, 6000), (1000, 9000)][v]  # the contact: bright on metal, duller on wood
    m.add(nrm(bp(white(0.004, rng), lo, hi) * np.exp(-tvec(0.004) / 0.0006)), 0.0, [0.4, 0.2, 0.3][v])
    m.add(nrm(thump(0.12, 210, 140, rng, decay=0.015, click=0.0, noise=0.4, nlp=900)), 0.0, 0.3)
    m.add(slosh(0.4, rng, rate=[7.0, 6.0, 6.5][v]), 0.04, [0.16, 0.2, 0.18][v])
    y = fit(m.out(), ns(dur))
    return small_room(y, wet=0.1, rt60=0.42, seed=74 + v)


@effect("tavern.pour", variants=2, subtitle="Ale pours", loud=-17.0, max_len=1.5, fade_out=0.15)
def tavern_pour(v, rng):
    """ale poured from a jug into a mug: the first splash on the bottom, then the stream filling it, its note
    climbing as the mug fills, the jug glugging as air gets back in, a head of foam fizzing up; a last drip"""
    dur = 1.5
    pd = [1.15, 1.0][v]
    t = tvec(pd)
    u = t / pd
    m = Mix(dur)
    m.add(nrm(knock(0.15, [420, 760, 1240], [0.04, 0.03, 0.02], [1, .6, .35], rng, lo=200, hi=4000)), 0.0, 0.3)
    m.add(nrm(bp(white(0.05, rng), 600, 6000) * np.exp(-tvec(0.05) / 0.012)), 0.0, 0.4)
    stream_env = np.clip(t / 0.03, 0, 1) * np.clip((pd - t) / 0.12, 0, 1) ** 1.5
    glug_hz = [7.5, 8.5][v] * (1 + 0.1 * smooth_random(pd, rng, 3))
    glug = 1 - 0.35 * (0.5 + 0.5 * np.sin(phase_from_freq(glug_hz, len(t))))
    src = colored(pd, rng, -0.8) + 0.8 * nrm(bubbles(pd, rng, 1600, 600, 3000, tau=(0.002, 0.006), rise=1.0))
    # the air column above the ale is a quarter-wave pipe: about 450 Hz in the empty mug, a fourth of its
    # length (and nearly four times the pitch) by the time it is full
    fill = tvf(src, "bp", 450 * (1 + 2.5 * u ** 1.3), 6.0)
    wash = bp(colored(pd, rng, -1.5), 500, 3500) * (0.6 + 0.4 * smooth_random(pd, rng, 30))
    m.add(nrm(nrm(fill) + 0.15 * nrm(wash)) * stream_env * glug, 0.01, 1.0)
    foam = crackle(pd, rng, 3000 * u ** 1.5 + 50, decay=0.0002, f_lo=3000, f_hi=9000, size_var=0.6)
    m.add(nrm(foam) * np.clip(u * 1.5, 0, 1), 0.01, 0.1)
    for i, at in enumerate([pd + 0.12, pd + 0.31][: 2 - v]):
        m.add(nrm(plop(0.08, rng, f0=1500 + 300 * i, size=0.3)), at, 0.22 * 0.7 ** i)
    y = lp(fit(m.out(), ns(dur)), 6500)
    return small_room(y, wet=0.08, rt60=0.4, seed=78 + v)


@effect("hiccup", variants=3, subtitle="A drunk hiccups", loud=-15.0, max_len=0.5, fade_out=0.08)
def hiccup(v, rng):
    """the diaphragm jerks: a sharp catch of breath, a squeak of voice drawn in through the throat, slammed shut
    by the glottis ('hic!'). v3 mumbles a contented 'mm' after it"""
    dur = 0.5
    m = Mix(dur)
    ad = 0.03
    asp = formants(white(ad, rng), "i", "i", scale=1.05) * np.clip(tvec(ad) / ad, 0, 1) ** 2
    m.add(nrm(asp), 0.0, 0.35)
    vd = [0.075, 0.06, 0.07][v]
    vt = tvec(vd)
    f_a, f_b = [(270, 360), (300, 420), (240, 330)][v]
    f0 = f_a + (f_b - f_a) * np.sin(np.pi / 2 * np.clip(vt / vd, 0, 1))
    src = glottal(vd, f0, rng, jitter=0.05, shimmer=0.25, breath=0.55, open_q=0.5)
    squeak = formants(src, "i", ["e", "uh", "e"][v], scale=1.08, gains=(1.0, 0.8, 0.45, 0.2))
    squeak *= np.clip(vt / 0.008, 0, 1)
    m.add(nrm(squeak), 0.018, 1.0)
    end = 0.018 + vd
    m.add(nrm(bp(white(0.003, rng), 800, 6000) * np.exp(-tvec(0.003) / 0.0005)), end, 0.45)
    if v == 2:
        md = 0.2
        mt = tvec(md)
        mm = formants(glottal(md, 118 - 12 * mt / md, rng, jitter=0.03, shimmer=0.15, breath=0.3), "u", "u", scale=0.95)
        m.add(nrm(lp(mm, 1500)) * bell(md, 0.3, 1.3, 1.5), end + 0.13, 0.4)
    y = fit(m.out(), ns(dur))
    # the squeak ends dead at the click (its source is only vd long); the high-pass keeps out breath rumble
    return small_room(hp(y, 120), wet=0.08, rt60=0.38, seed=82 + v)


# =========================================================================== the mage
@effect("mage.page", variants=3, subtitle="Pages turn", loud=-18.0, max_len=0.55, fade_out=0.12)
def mage_page(v, rng):
    """a page of a big book turned: the corner caught up between finger and thumb (a dry crinkle), the heavy vellum leaf
    swung over (the air it pushes aside, the skin flexing with a crackle, a fine flutter along its edge) and laid down on
    the other side with a soft flop and a settling rustle. v1 an ordinary leaf, v2 a quick one, v3 a thick, slow one that
    lands heavily"""
    dur = 0.56
    m = Mix(dur)
    sd = [0.2, 0.15, 0.25][v]
    cd = 0.08
    crinkle = crackle(cd, rng, 1500, decay=0.0006, f_lo=1800, f_hi=7500, size_var=0.8) * bell(cd, 0.45, 1.0, 1.5)
    m.add(nrm(crinkle), 0.0, 0.3)
    s = bell(sd, 0.4, 1.4, 1.4)
    air = bp(colored(sd, rng, -1.5), 500, 4800) * s ** 1.3
    flex = crackle(sd, rng, 650 * s + 10, decay=0.0007, f_lo=1500, f_hi=7000, size_var=0.8) * s
    flutter = bp(white(sd, rng), 2500, 7000) * s ** 1.6 * smooth_random(sd, rng, 70, 0.2, 1.0)
    m.add(nrm(nrm(air) + 0.7 * nrm(flex) + 0.3 * nrm(flutter)), 0.04, 0.8)
    at = 0.04 + sd - 0.02  # the leaf comes down as the swing ends
    m.add(thud(0.14, rng, f=[190, 215, 160][v], decay=0.014, tone=0.15), at, 0.45)
    m.add(nrm(bp(white(0.03, rng), 500, 3500) * np.exp(-tvec(0.03) / 0.005)), at, 0.4)
    rd = 0.14
    m.add(nrm(crackle(rd, rng, 500 * bell(rd, 0.2, 1.0, 1.6) + 10, decay=0.0008, f_lo=1200, f_hi=6000, size_var=1.0)),
          at + 0.01, 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 9000), wet=0.08, rt60=0.38, seed=100 + v))


def quill_stroke(dur, rng, speed=1.0):
    """one stroke of a nib over parchment: the point catching and slipping on the grain of the skin (a fine, dry scratch,
    broken up by the stick and slip at a hundred or so times a second, with a little hard grit in it) and the faint hollow
    of the desk under it; the quicker the stroke, the louder and brighter"""
    s = bell(dur, 0.35, 1.0, 1.7)
    slip = 0.3 + 0.7 * smooth_random(dur, rng, 110 * speed, 0.0, 1.0) ** 1.5
    scratch = bp(colored(dur, rng, -1.0), 1600, 6500) * slip * s
    grit = crackle(dur, rng, 1300 * speed * s + 30, decay=0.00035, f_lo=2200, f_hi=7500, size_var=0.5) * s
    desk = hp(resonators(colored(dur, rng, -2.0) * s, [430, 760, 1250], [0.012, 0.01, 0.008], [1, .7, .4]), 300)
    return nrm(nrm(scratch) + 0.6 * nrm(grit) + 0.18 * nrm(desk)) * speed ** 0.6


@effect("mage.quill", variants=3, subtitle="Quill scratches", loud=-19.0, max_len=0.75, fade_out=0.1)
def mage_quill(v, rng):
    """a quill writing: the nib dragged over parchment in a few short strokes, each a dry, fine scratch that swells and
    tails off with the hand, with the hand's small pauses between them"""
    dur = 0.78
    plan = [[(0.0, 0.07, 1.0), (0.1, 0.05, 0.8), (0.19, 0.09, 1.1), (0.34, 0.06, 0.9), (0.45, 0.11, 1.2)],
            [(0.02, 0.1, 1.0), (0.17, 0.06, 0.9), (0.29, 0.08, 1.0), (0.43, 0.13, 1.1)],
            [(0.0, 0.05, 0.9), (0.08, 0.07, 1.0), (0.18, 0.05, 0.9), (0.27, 0.08, 1.1), (0.4, 0.07, 1.0),
             (0.5, 0.09, 1.2)]][v]
    m = Mix(dur)
    for at, d, speed in plan:
        m.add(quill_stroke(d, rng, speed), at, rng.uniform(0.8, 1.0))
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(hp(y, 100), wet=0.07, rt60=0.35, seed=104 + v))


def mote(rng, f, t60=0.3):
    """one tiny glass bead struck: a few bar modes (1, 2.76, 5.4 times the lowest) with a soft attack, ringing for a
    moment"""
    return nrm(ring(min(0.6, t60 * 2.2), f * np.array([1.0, 2.76, 5.4]), t60 * np.array([1.0, 0.5, 0.25]),
                    [1.0, 0.4, 0.15], rng, doublet=0.003, attack=0.004))


def glint_cloud(dur, rng, f_lo, f_hi, count=7, rate=9.0, rise=None):
    """a cluster of glassy high partials, each fading in and out at a rate of its own: light shifting in cut crystal"""
    n = ns(dur)
    out = np.zeros(n)
    for j in range(count):
        f = f_lo * (f_hi / f_lo) ** ((j + rng.uniform(-0.3, 0.3)) / max(1, count - 1))
        env = smooth_random(dur, rng, rate * rng.uniform(0.7, 1.4), 0.0, 1.0) ** 2
        fv = f * (rise if rise is not None else 1.0)
        out += env * np.sin(phase_from_freq(fv, n) + rng.uniform(0, 6.28)) / (1 + 0.15 * j)
    return nrm(out)


@effect("mage.conjure", variants=2, subtitle="Motes shimmer", loud=-20.0, max_len=1.3, fade_out=0.4)
def mage_conjure(v, rng):
    """glowing motes rising from a mage's palm: a soft glassy shimmer, a scatter of tiny glass pings (more of them as the
    motes lift, each a little higher than the last on the whole, none on a note: a handful of glass beads, not a tune), a
    faint cluster of glass partials holding it together and a breath of air under it, all much gentler than a spell's"""
    dur = 1.3
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    swell = np.sin(np.pi / 2 * np.clip(t / 0.3, 0, 1)) ** 2 * np.clip((dur - t) / 0.55, 0, 1) ** 1.2
    m = Mix(dur)
    rate = 10.0 + 22.0 * np.sin(np.pi * np.clip(u / 0.95, 0, 1)) ** 1.3  # pings a second
    for i in np.nonzero(rng.random(n) < rate / SR)[0]:
        f = rng.uniform(2300, 4600) * (1.0 + 0.45 * u[i]) * [1.0, 1.12][v]
        m.add(mote(rng, f, rng.uniform(0.12, 0.35)), i / SR, swell[i] * rng.uniform(0.25, 0.7))
    cloud = glint_cloud(dur, rng, [1900, 2300][v], [6200, 7200][v], count=8, rate=6.0,
                        rise=1 + 0.25 * np.clip(t / 0.9, 0, 1))
    m.add(cloud * swell, 0.0, 0.3)
    air = bp(colored(dur, rng, -1.0), 1800, 6500, order=3) * swell ** 1.5 * smooth_random(dur, rng, 14, 0.3, 1.0)
    m.add(air, 0.0, 0.09)
    y = fit(m.out(), n)
    return lead_in(small_room(y, wet=0.1, rt60=0.5, seed=108 + v))


# =========================================================================== the knight
def blade(dur, rng, f0, t60=0.3, gain=1.0):
    """a steel blade ringing after a blow: the modes of a free-free bar (1, 2.76, 5.40, 8.93, 13.3 and 18.6 times the
    lowest, the upper ones dying first), each a close pair, the weight in the middle ones so that it clanks and does not
    sing a note"""
    r = np.array([1.0, 2.756, 5.404, 8.933, 13.344, 18.64]) * rng.uniform(0.99, 1.01, 6)
    return nrm(ring(dur, f0 * r, t60 * np.array([0.9, 1.0, 0.8, 0.6, 0.4, 0.25]),
                    gain * np.array([0.35, 0.8, 1.0, 0.8, 0.5, 0.3]), rng, doublet=0.002))


@effect("knight.clash", variants=4, subtitle="Sword rings on shield", loud=-14.0, max_len=0.5, fade_out=0.15, limit=3.0,
        attenuation=24)
def knight_clash(v, rng):
    """a practice sword meeting a shield in a spar: the blow on the shield's wood (a short, dense 'thock' with the board's
    modes in it and the whole shield answering low behind it), the blunted steel ringing above it for a moment. v1 on the
    face, v2 on the rim (more ring), v3 a hard cut, v4 glancing off with a scrape"""
    dur = 0.5
    m = Mix(dur)
    k = [1.0, 1.1, 0.93, 1.05][v]
    wood = knock(dur, np.array([330, 520, 790, 1150, 1700, 2500]) * k, [0.05, 0.045, 0.04, 0.03, 0.025, 0.02],
                 [1, .85, .65, .45, .3, .2], rng, lo=150, hi=6000, ex_len=0.002)
    m.add(wood, 0.0, [1.0, 0.55, 1.0, 0.8][v])
    m.add(thud(0.14, rng, f=[150, 175, 135, 160][v], decay=0.03, tone=0.4), 0.0, [0.35, 0.2, 0.5, 0.3][v])
    m.add(blade(dur, rng, [440, 520, 400, 480][v], t60=[0.45, 0.6, 0.4, 0.5][v]), 0.0005, [0.7, 0.9, 0.7, 0.6][v])
    m.add(nrm(bp(white(0.004, rng), 2000, 10000) * np.hanning(ns(0.004) + 2)[1:-1]), 0.0, 0.22)
    if v == 3:  # the blade skating off the face: a short rasp of steel on wood
        sd = 0.13
        sl = bell(sd, 0.3, 1.2, 1.5)
        m.add(nrm(crackle(sd, rng, 1500 * sl + 30, decay=0.0006, f_lo=500, f_hi=6000, size_var=0.6) * sl), 0.012, 0.35)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 12000), wet=0.1, rt60=0.45, seed=112 + v))


@effect("knight.post", variants=3, subtitle="Sword bites the post", loud=-15.0, max_len=0.38, fade_out=0.1, limit=3.0,
        attenuation=20)
def knight_post(v, rng):
    """a sword biting a wooden practice post: the edge driven into oak (a hard, dull 'chock', fibres crunching and tearing
    under it), the post's long body answering low, a few chips dropping. v1 a clean cut, v2 a harder blow that jars the
    blade (a short ring), v3 a glancing cut that skips off with a scrape"""
    dur = 0.38
    m = Mix(dur)
    m.add(thud(0.2, rng, f=[135, 120, 145][v], decay=0.028, tone=0.45), 0.0, 0.7)
    m.add(nrm(knock(0.25, [220, 380, 610, 950, 1500], [0.07, 0.05, 0.04, 0.03, 0.02], [1, .8, .6, .4, .25], rng, lo=100,
                    hi=3800, ex_len=0.003)), 0.0, 0.9)
    ct = tvec(0.1)
    m.add(nrm(crackle(0.1, rng, 6000 * np.exp(-ct / 0.02), decay=0.0009, f_lo=300, f_hi=4200, size_var=0.7)), 0.001, 0.9)
    m.add(nrm(bp(white(0.012, rng), 1200, 7000) * np.exp(-tvec(0.012) / 0.002)), 0.0, 0.3)
    for _ in range([4, 6, 3][v]):  # chips dropping on the dirt
        m.add(thud(0.04, rng, f=rng.uniform(300, 800), decay=0.005, tone=0.1), 0.06 + rng.exponential(0.07),
              rng.uniform(0.15, 0.4))
    if v == 1:
        m.add(blade(0.3, rng, 400, t60=0.18), 0.0, 0.3)
    if v == 2:
        sd = 0.12
        sl = bell(sd, 0.3, 1.2, 1.5)
        m.add(nrm(crackle(sd, rng, 1200 * sl + 30, decay=0.0007, f_lo=400, f_hi=5000, size_var=0.6) * sl), 0.05, 0.35)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 10000), wet=0.08, rt60=0.36, seed=116 + v))


@effect("knight.whetstone", variants=2, subtitle="Whetstone rasps on steel", loud=-17.0, max_len=0.75, fade_out=0.12)
def knight_whetstone(v, rng):
    """a whetstone drawn along a blade: a long rasp of grit on steel, a gritty hiss with a coarse crackle in it and the
    scrape of the stone's weight lower down, the blade singing faintly on its own notes as the stone drags over it,
    loudest near the start of the stroke and tailing off with a tiny ring. v1 one long stroke, v2 a shorter one and a
    quick second"""
    dur = 0.78
    m = Mix(dur)
    for at, sd in [[(0.02, 0.56)], [(0.02, 0.32), (0.4, 0.26)]][v]:
        s = bell(sd, 0.38, 1.2, 1.6)
        hiss = tvf(colored(sd, rng, -1.0), "bp", 2300 + 1800 * s, 0.5) * s ** 1.2 * \
            (0.4 + 0.6 * smooth_random(sd, rng, 90))
        rough = crackle(sd, rng, 1800 * s + 40, decay=0.0004, f_lo=1500, f_hi=7500, size_var=0.9) * s
        sing = resonators(colored(sd, rng, -0.5), [2420, 3860, 5390, 6910], [0.12, 0.09, 0.06, 0.04],
                          [1, 0.8, 0.5, 0.3]) * s ** 1.3
        body = bp(colored(sd, rng, -2.0), 250, 1400) * s ** 1.5 * (0.5 + 0.5 * smooth_random(sd, rng, 40))
        m.add(nrm(nrm(hiss) + 0.8 * nrm(rough) + 0.4 * nrm(sing) + 0.5 * nrm(body)), at, 1.0)
        tail = ring(0.2, [2420, 3860, 5390], [0.1, 0.07, 0.05], [1, .6, .3], rng, doublet=0.002)
        m.add(nrm(tail), at + sd - 0.03, 0.06)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(y, wet=0.07, rt60=0.36, seed=120 + v))


# =========================================================================== the thief
def die(dur, rng, f=2300.0):
    """one die knocking on something hard: bone or hardwood is dense and stiff, so the knock is a short, dry tick with a
    little ring in it (a few modes, each gone in a few thousandths of a second)"""
    return knock(dur, [f, f * 1.62, f * 2.45, f * 3.4], [0.007, 0.005, 0.0035, 0.0025], [1, .7, .45, .25], rng, lo=800,
                 hi=9500, ex_len=0.0007)


@effect("thief.shake", variants=2, subtitle="Dice rattle in a cup", loud=-18.0, max_len=0.85, fade_out=0.12, limit=3.0)
def thief_shake(v, rng):
    """dice rattled in a leather cup: three or four small cubes flung back and forth inside it by the hand, a cluster of
    dry clacks at each end of every shake (the dull give of the leather in each, the hollow of the cup under them) and the
    rub of the cup in the palm; v2 shakes fewer times, harder"""
    dur = 0.88
    m = Mix(dur)
    strokes = [[(0.05, 1.0), (0.24, 1.1), (0.43, 1.0), (0.62, 0.6)], [(0.04, 1.2), (0.22, 1.3), (0.41, 0.8)]][v]
    for t0, st in strokes:
        for half, g in ((0.0, 1.0), (0.095, 0.75)):
            for _ in range(int(rng.integers(4, 8))):
                at = t0 + half + rng.uniform(0.0, 0.045)
                f = rng.uniform(1700, 2700)
                body = knock(0.05, [420 * rng.uniform(0.9, 1.1), 780, 1250], [0.02, 0.012, 0.008], [1, .6, .3], rng,
                             lo=200, hi=3000, ex_len=0.001)
                click = 0.8 * nrm(die(0.05, rng, f)) + 0.5 * nrm(body)
                m.add(click, at, st * g * rng.uniform(0.45, 1.0))
    rd = 0.8
    rub = bp(colored(rd, rng, -1.5), 200, 1800) * smooth_random(rd, rng, 9, 0.2, 1.0)
    m.add(nrm(rub), 0.04, 0.16)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(hp(y, 100), 9000), wet=0.07, rt60=0.36, seed=124 + v))


def table(dur, rng, size=1.0):
    """a heavy wooden tabletop under a blow: its boards' low modes, a dull knock"""
    return knock(dur, np.array([210, 340, 560, 900, 1500]) * size, [0.05, 0.04, 0.03, 0.02, 0.012],
                 [1, .8, .6, .4, .25], rng, lo=100, hi=3000, ex_len=0.002)


@effect("thief.dice", variants=3, subtitle="Dice clatter on a table", loud=-16.0, max_len=0.65, fade_out=0.12, limit=3.0)
def thief_dice(v, rng):
    """the dice thrown clattering on a table: each cube lands with a knock of the wood and a dry tick of its own and
    bounces on, each bounce shorter and softer than the one before, then tumbles over its corners and settles. v1 three
    dice, v2 two with one skittering off further, v3 four, heavily thrown"""
    dur = 0.65
    m = Mix(dur)
    count = [3, 2, 4][v]
    for i in range(count):
        t0 = rng.uniform(0.0, 0.05) + (0.07 if (v == 1 and i == 1) else 0.0)
        d = rng.uniform(0.07, 0.1) * (1.25 if (v == 1 and i == 1) else 1.0)
        r = rng.uniform(0.6, 0.72)
        f = rng.uniform(2000, 2900)
        a = rng.uniform(0.8, 1.0) * [1.0, 0.9, 1.15][v]
        at = t0
        for k in range(6):
            if at > 0.52:
                break
            m.add(nrm(die(0.05, rng, f * rng.uniform(0.96, 1.04))), at, 0.7 * a)
            m.add(nrm(table(0.12, rng, rng.uniform(0.9, 1.1))), at, 0.6 * a)
            a *= 0.55
            at += d
            d *= r
        for _ in range(int(rng.integers(2, 5))):  # tumbling over its corners
            tt = at + rng.exponential(0.05)
            if tt < 0.6:
                m.add(nrm(die(0.04, rng, f * rng.uniform(0.9, 1.1))), tt, 0.22 * rng.uniform(0.4, 1.0))
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(hp(y, 80), wet=0.08, rt60=0.38, seed=128 + v))


@effect("thief.coin", variants=3, subtitle="Coin clinks on a stack", loud=-17.0, max_len=0.27, fade_out=0.08, limit=3.0)
def thief_coin(v, rng):
    """a coin set down on a stack: the coin's short, bright ring (damped by the stack it lands on) and the tick of the
    blow, the coins under it shifting in a faint second and third clink as they settle against each other"""
    dur = 0.3
    f0 = [3050, 3450, 3850][v]
    m = Mix(dur)
    m.add(coin(0.28, rng, f0=f0, t60=0.16, bright=0.8, click=0.4), 0.0, 1.0)
    m.add(coin(0.15, rng, f0=f0 * rng.uniform(0.88, 1.12), t60=0.06, bright=0.5, click=0.3), 0.018 + 0.008 * v, 0.35)
    m.add(coin(0.12, rng, f0=f0 * rng.uniform(0.88, 1.12), t60=0.05, bright=0.4, click=0.3), 0.046 + 0.01 * v, 0.2)
    stack = knock(0.03, [900, 1500, 2300], [0.006, 0.004, 0.003], [1, .6, .3], rng, lo=500, hi=4000, ex_len=0.001)
    m.add(nrm(stack), 0.0, 0.15)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(hp(y, 150), 12000), wet=0.07, rt60=0.34, seed=132 + v))


def pin_click(rng, f=2600.0):
    """a tiny click of brass on brass: a pin setting in the cylinder, a high dry tick with the lock's small ring behind
    it"""
    hi_ = knock(0.04, [f, f * 1.7, f * 2.6, f * 3.9], [0.006, 0.005, 0.0035, 0.0025], [1, .7, .45, .3], rng, lo=1200,
                hi=10500, ex_len=0.0007)
    lo_ = knock(0.04, [820 * rng.uniform(0.9, 1.1), 1350, 2100], [0.012, 0.009, 0.006], [1, .6, .3], rng, lo=400, hi=4000,
                ex_len=0.0008)
    return nrm(hi_) + 0.4 * nrm(lo_)


def pick_rake(dur, rng, rate=70.0, lo=1800.0, hi=4800.0):
    """the pick raked over the pins: a run of tiny ticks of metal on metal, one for each pin it flicks (quicker in the
    middle of the stroke, each a little different in pitch), a faint scrape of steel joining them"""
    n = ns(dur)
    s = bell(dur, 0.5, 1.2, 1.3)
    m = Mix(dur)
    for i in np.nonzero(rng.random(n) < rate * s / SR)[0]:
        f = float(np.exp(rng.uniform(np.log(lo), np.log(hi))))
        m.add(nrm(knock(0.012, [f, f * 1.7, f * 2.5], [0.0025, 0.002, 0.0015], [1, .5, .25], rng, lo=1200, hi=9500,
                        ex_len=0.0006)), i / SR, rng.uniform(0.3, 1.0) * s[i] ** 0.5)
    scr = bp(colored(dur, rng, -1.0), 2000, 7000) * s ** 1.5 * smooth_random(dur, rng, 70, 0.3, 1.0)
    m.add(nrm(scr), 0.0, 0.2)
    return fit(m.out(), n)


@effect("thief.lockpick", variants=3, subtitle="Lockpick clicks", loud=-20.0, max_len=0.85, fade_out=0.12, limit=3.0)
def thief_lockpick(v, rng):
    """a lock picked: tiny metal clicks and scrapes, the pick raked over the pins in a run of tiny ticks and a pin set now
    and then with a small brass click. v1 a rake, a click, a rake and two clicks, v2 clicks first and a long rake, v3 slow
    and careful"""
    dur = 0.85
    m = Mix(dur)
    plan = [[("s", 0.02, 0.14), ("c", 0.19, 1.0), ("s", 0.3, 0.11), ("c", 0.46, 0.8), ("c", 0.58, 0.6)],
            [("c", 0.02, 0.7), ("c", 0.12, 1.0), ("s", 0.22, 0.28), ("c", 0.55, 0.9)],
            [("s", 0.02, 0.22), ("c", 0.28, 1.0), ("s", 0.4, 0.2), ("c", 0.66, 0.8)]][v]
    for kind, at, x in plan:
        if kind == "s":
            m.add(pick_rake(x, rng, rate=rng.uniform(60, 110)), at, 0.6)
        else:
            m.add(pin_click(rng, f=rng.uniform(2300, 3300)), at, 0.6 * x)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(hp(y, 120), 9500), wet=0.06, rt60=0.34, seed=136 + v))


# =========================================================================== the bard's tunes
# Four little pieces for a lute, composed here and all original: a jig, a reel, a slow air and a hornpipe, in
# the tavern-folk manner. Each is written out bar by bar as a lutenist plays it, the tune over a thumb bass,
# then made human on the way to MIDI: the lilt of a jig and the swing of a hornpipe, metrical accents, the
# thumb a hair ahead of the fingers, cuts and rolls the second time through a part, a small seeded jitter.
# FluidSynth's General MIDI nylon guitar plays it, with a second string a few cents sharp and a few
# milliseconds late (a lute's strings come in pairs; the bass courses have an octave string), and an EQ that
# thins the guitar's body and brings up the pluck. A small frame drum (synthesised here) joins the dances
# from their second time through. Every tune starts on its first note and ends on a held chord that dies away
# to silence, so they can play back to back.
TUNE_EVENT = "bard.tune"
TUNE_LOUDNESS = -18.0  # LUFS integrated: a busker's level, under the hawkers' cries and well under the boss music
TUNE_QUALITY = 5
LUTE = 24  # General MIDI nylon guitar
SCALES = {"D": "D E F# G A B C#", "G": "G A B C D E F#", "Am": "A B C D E F G", "A": "A B C# D E F# G#"}
NOTE_PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def pitch_class(name):
    return (NOTE_PC[name[0]] + name[1:].count("#") - name[1:].count("b")) % 12


def chord_tones(sym):
    """'D' -> (root pc, intervals): 'Em' minor, 'E7' with the seventh"""
    root = sym[:2] if len(sym) > 1 and sym[1] in "#b" else sym[:1]
    rest = sym[len(root):]
    iv = [0, 3 if rest.startswith("m") else 4, 7] + ([10] if rest.endswith("7") else [])
    return pitch_class(root), iv


def bass_root(sym):
    """the chord's root on the thumb's courses, E2..D#3"""
    return 40 + (chord_tones(sym)[0] - 4) % 12


def bass_fifth(sym):
    r = bass_root(sym)
    return r + 7 if r + 7 <= 52 else r - 5


class Lute:
    """one lutenist's part in eighth-note units: the tune, the thumb's bass, chords, the drum's strokes, and
    the shaping (groove, tempo, dynamics) that turns it into a performance"""
    ROLES = {  # base velocity, lag behind the beat (s), timing jitter (s)
        "mel": (76, 0.007, 0.004), "grace": (76, 0.007, 0.0), "bass": (62, 0.0, 0.003),
        "arp": (46, 0.004, 0.005), "chord": (56, 0.0, 0.002)}

    def __init__(self, name, bpm, bar, key, rng, groove=(1, [0.0]), accents=(0,), roles=None):
        self.name, self.bpm, self.bar, self.rng = name, bpm, bar, rng
        self.spb = 60.0 / bpm  # seconds per unit (an eighth note)
        self.scale = {pitch_class(n) for n in SCALES[key].split()}
        self.group, self.lilt = groove  # beat group length and the delay of each unit in it (units)
        self.accents = accents  # velocity offset for each unit of the bar
        self.roles = dict(self.ROLES, **(roles or {}))
        self.notes, self.hits = [], []
        self.tempo = [(0.0, 1.0)]  # (unit, tempo factor) knots: rubato and the last ritardando
        self.dyn = [(0.0, 0.0)]  # (unit, velocity offset) knots: the shape of the whole piece
        self.final = None  # the unit of the closing chord
        self.phrase0 = 0.0  # where the four-bar phrases start (after an intro bar, say)

    def at(self, bar, unit=0.0):
        return (bar - 1) * self.bar + unit

    def neighbour(self, p, up=True):
        q = p + (1 if up else -1)
        while q % 12 not in self.scale:
            q += 1 if up else -1
        return q

    def add(self, at, dur, p, vel, role, shift=0.0, dur_s=None):
        self.notes.append(dict(at=float(at), dur=float(dur), p=int(p), vel=float(vel), role=role, shift=shift,
                               dur_s=dur_s))

    def line(self, bar, spec, role="mel", vel=0.0, leg=1.12):
        """'D5:2 A4:1 R:1 C#5:2/3' in units; a prefix ' is a cut (the note above flicked in just before),
        ~ a roll (the note struck again after a cut, then after a tap below), % a mordent (the note, the
        note below and back, quick as a slur)"""
        u = self.at(bar)
        for tok in spec.split():
            orn = tok[0] if tok[0] in "'~%" else ""
            name, d = tok[len(orn):].rsplit(":", 1)
            d = float(Fraction(d))
            if name != "R":
                p = pitch(name)
                if orn == "'":
                    self.add(u, 0, self.neighbour(p), vel - 18, "grace", shift=-0.032, dur_s=0.03)
                    self.add(u, d * leg, p, vel, role)
                elif orn == "~" and d >= 2:
                    k = int(min(d, 3))
                    for j in range(k):
                        if j:
                            self.add(u + j, 0, self.neighbour(p, j == 1), vel - 20, "grace", shift=-0.03, dur_s=0.028)
                        last = j == k - 1
                        self.add(u + j, (d - j) * leg if last else 1.0, p, vel - (8 if j else 0), role)
                elif orn == "%":
                    self.add(u, 0, p, vel, role, dur_s=0.06)
                    self.add(u, 0, self.neighbour(p, False), vel - 14, "grace", shift=0.06, dur_s=0.06)
                    self.add(u, d * leg, p, vel - 6, role, shift=0.12)
                else:
                    self.add(u, d * leg, p, vel, role)
            u += d
        return u

    def bass(self, bar, chords, vel=0.0, ring=1.0, pattern="rf"):
        """the thumb: a bass note per beat group, root and fifth in turn, a new chord always on its root"""
        span = self.bar / len(chords)
        for i, ch in enumerate(chords):
            fifth = pattern[i % len(pattern)] == "f" and not (i and ch != chords[i - 1])
            self.add(self.at(bar, i * span), span * ring, bass_fifth(ch) if fifth else bass_root(ch), vel, "bass")

    def arpeggio(self, bar, chords, vel=0.0, ring=3.0):
        """broken chords in eighths, as a lute accompanies a song: bass, fifth, octave, tenth, octave, fifth
        (a chord that changes mid-bar starts the pattern again on its own bass)"""
        per = self.bar // len(chords)
        k = 0
        for i in range(self.bar):
            ch = chords[i // per]
            if i and ch != chords[(i - 1) // per]:
                k = 0
            iv = chord_tones(ch)[1]
            p = bass_root(ch) + [0, 7, 12, 12 + iv[1], 12, 7][k]
            if k == 0:
                j = i + 1
                while j < self.bar and chords[j // per] == ch:
                    j += 1
                self.add(self.at(bar, i), j - i, p, vel + 10, "bass")
            else:
                self.add(self.at(bar, i), ring, p, vel, "arp")
            k += 1

    def chord(self, at, pitches, vel=0.0, spread=0.024, dur=4.0, dur_s=None):
        """a chord rolled from the bass up, as a thumb sweeps across the courses"""
        for i, p in enumerate(sorted(pitches)):
            self.add(at, dur, p, vel - 2 * i, "chord", shift=i * spread, dur_s=dur_s)

    def drum(self, bar, pattern, vel=1.0):
        """one character per unit: D an open stroke, d a lighter one, t a tap at the rim, . nothing"""
        for i, c in enumerate(pattern):
            if c != ".":
                self.hits.append((self.at(bar, i), c, vel))

    # ---------------------------------------------------------------- performance
    def _maps(self):
        """unit -> seconds: the groove (only notes on the unit grid are delayed, so triplets stay even), then
        the tempo map; ends of notes follow the groove smoothly"""
        last = max(n["at"] + n["dur"] for n in self.notes) + 4 * self.bar
        step = 0.01
        grid = np.arange(0.0, last + step, step)
        kn = np.array(self.tempo)
        warped = np.concatenate([[0.0], np.cumsum(step / np.interp(grid[:-1] + step / 2, kn[:, 0], kn[:, 1]))])
        ints = np.arange(0, int(last) + 2)
        sw = ints + np.array(self.lilt)[ints % self.group]

        def onset(u):
            i = int(round(u))
            g = u + self.lilt[i % self.group] if abs(u - i) < 1e-6 else u
            return float(np.interp(g, grid, warped)) * self.spb

        def offset(u):
            return float(np.interp(np.interp(u, ints, sw), grid, warped)) * self.spb
        return onset, offset

    def perform(self, lead=0.25):
        """-> [(onset s, end s, midi, velocity)], drum strokes [(s, stroke, level)], end of the piece (s)"""
        onset, offset = self._maps()
        rng = self.rng
        kn = np.array(self.dyn)
        jit = {}
        out = []
        for n in self.notes:
            base, lag, _ = self.roles[n["role"]]
            fam = "bass" if n["role"] in ("bass", "chord") else "mel"
            key = (fam, round(n["at"], 4))
            if key not in jit:
                jit[key] = rng.normal(0, self.roles["mel" if fam == "mel" else "bass"][2])
            t0 = lead + onset(n["at"]) + lag + n["shift"] + jit[key]
            t1 = t0 + n["dur_s"] if n["dur_s"] else lead + offset(n["at"] + n["dur"]) + lag + jit[key]
            pos = n["at"] - self.bar * np.floor(n["at"] / self.bar + 1e-9)
            acc = self.accents[int(round(pos)) % self.bar] if abs(pos - round(pos)) < 1e-6 and n["role"] != "grace" \
                else 0.0
            phrase = 2.5 * np.sin(np.pi * (((n["at"] - self.phrase0) / (4 * self.bar)) % 1.0))
            v = base + n["vel"] + acc + phrase + np.interp(n["at"], kn[:, 0], kn[:, 1]) + rng.normal(0, 2.5)
            out.append((t0, max(t1, t0 + 0.02), n["p"], int(np.clip(round(v), 1, 127))))
        hits = [(lead + onset(u) + rng.normal(0, 0.003), c, lv) for u, c, lv in self.hits]
        end = lead + onset(self.final)
        return out, hits, end


def pitch(name):
    """'C#4' -> 61 (C4 = 60)"""
    i = 1
    while i < len(name) and name[i] in "#b":
        i += 1
    return 12 * (int(name[i:]) + 1) + pitch_class(name[:i])


def inner(sym, top, lo=55, n=2):
    """the chord's middle voices (G3 and up), under the tune's note"""
    root, iv = chord_tones(sym)
    pcs = [(root + i) % 12 for i in iv]
    return [p for p in range(top - 3, lo - 1, -1) if p % 12 in pcs][:n]


def dance(lute, parts, drum, drum_from=2, last_bar=None, no_bass=()):
    """AABB: each part twice, the second time ornamented (seconds[i] replaces bar i), the drum from the given
    pass on. parts: [(bars, seconds, chords, last bar of the first pass)]. Returns the closing bar, which has
    only its tune: the caller ends the piece."""
    bar, passes = 1, 0
    for k, (bars, seconds, chords, first_end) in enumerate(parts):
        for rep in range(2):
            passes += 1
            for i, spec in enumerate(bars):
                if rep == 0 and i == len(bars) - 1 and first_end:
                    spec = first_end
                if rep == 1:
                    spec = seconds.get(i, spec)
                final = k == len(parts) - 1 and rep == 1 and i == len(bars) - 1
                if final and last_bar:
                    spec = last_bar
                lute.line(bar, spec)
                if final:
                    break
                if passes >= drum_from:
                    lute.drum(bar, drum[1] if i == len(bars) - 1 else drum[0])
                if i not in no_bass:
                    lute.bass(bar, chords[i])
                if i == len(bars) - 1:  # the part closes on a fuller chord
                    top = pitch(spec.split()[0].lstrip("'~%").split(":")[0])
                    lute.chord(lute.at(bar), inner(chords[i][0], top), vel=-4, dur=3)
                bar += 1
    return bar


def jig():
    """tune 1, 'The Copper Kettle': a double jig in D, 6/8, AABB. The A part rocks down from the tonic and
    climbs back, asks a question on A and answers it; the B part starts high and runs down in thirds"""
    L = Lute("tune1", 330, 6, "D", rng_for("townsfolk", "tune", 1), groove=(3, [0.0, 0.08, 0.04]),
             accents=(9, -5, -1, 5, -5, -1))
    A = ["D5:2 A4:1 F#4:1 A4:1 D5:1", "B4:1 G4:1 B4:1 D5:1 B4:1 G4:1", "F#4:1 A4:1 D5:1 F#5:2 D5:1",
         "E5:1 C#5:1 A4:1 B4:1 C#5:1 E5:1", "D5:2 A4:1 F#4:1 A4:1 D5:1", "G5:1 F#5:1 E5:1 F#5:1 D5:1 A4:1",
         "B4:1 E5:1 D5:1 C#5:1 A4:1 C#5:1", "D5:3 D5:2 E5:1"]
    A2 = {0: "'D5:2 A4:1 F#4:1 A4:1 D5:1", 4: "'D5:2 A4:1 F#4:1 A4:1 D5:1", 7: "~D5:3 D5:2 E5:1"}
    Ach = [("D", "D"), ("G", "G"), ("D", "D"), ("A", "A"), ("D", "D"), ("G", "D"), ("Em", "A"), ("D", "D")]
    B = ["F#5:1 A5:1 F#5:1 D5:1 F#5:1 A5:1", "G5:1 B4:1 D5:1 G5:1 F#5:1 E5:1", "F#5:1 A4:1 D5:1 F#5:1 E5:1 D5:1",
         "E5:1 A4:1 C#5:1 E5:1 F#5:1 G5:1", "F#5:1 A5:1 F#5:1 D5:1 F#5:1 A5:1", "G5:1 F#5:1 E5:1 D5:1 B4:1 G4:1",
         "B4:1 E5:1 D5:1 C#5:1 A4:1 C#5:1", "D5:3 D5:2 E5:1"]
    B2 = {0: "'F#5:1 A5:1 F#5:1 D5:1 F#5:1 A5:1", 4: "'F#5:1 A5:1 F#5:1 D5:1 F#5:1 A5:1"}
    Bch = [("D", "D"), ("G", "G"), ("D", "D"), ("A7", "A7"), ("D", "D"), ("G", "G"), ("Em", "A"), ("D", "D")]
    L.dyn = [(0, 0), (48, 2), (96, 5), (144, 6), (192, 4)]
    L.tempo = [(0, 1.0), (180, 1.0), (186, 0.93), (192, 0.85)]
    last = dance(L, [(A, A2, Ach, "D5:3 D5:2 A4:1"), (B, B2, Bch, None)], ("D.td.t", "D.tD.."), last_bar="D5:6")
    L.final = L.at(last)
    L.chord(L.final, [pitch(p) for p in ("D3", "A3", "D4", "F#4", "A4")], vel=4, dur_s=3.6)
    L.drum(last, "D.....")
    return L


def reel():
    """tune 2, 'Over the Market Square': a reel in G, cut time, AABB. The A part climbs the G chord and
    answers itself through C and D; the B part rocks on C and G (e, c-c, e) before running home"""
    L = Lute("tune2", 392, 8, "G", rng_for("townsfolk", "tune", 2), groove=(2, [0.0, 0.07]),
             accents=(8, -5, 1, -5, 5, -5, 1, -5))
    A = ["G4:1 B4:1 D5:1 B4:1 G5:1 B4:1 D5:1 B4:1", "C5:1 E5:1 G5:1 E5:1 D5:1 F#5:1 A5:1 F#5:1",
         "G5:1 F#5:1 G5:1 D5:1 E5:1 D5:1 B4:1 G4:1", "A4:1 B4:1 C5:1 E5:1 D5:1 B4:1 A4:1 F#4:1",
         "G4:1 B4:1 D5:1 B4:1 G5:1 B4:1 D5:1 B4:1", "C5:1 E5:1 G5:1 E5:1 F#5:1 D5:1 A5:1 F#5:1",
         "E5:1 G5:1 C5:1 E5:1 D5:1 C5:1 A4:1 F#4:1", "G4:2 B4:1 G4:1 G4:2 C5:1 D5:1"]
    A2 = {0: "~G4:2 D5:1 B4:1 G5:1 B4:1 D5:1 B4:1", 2: "'G5:1 F#5:1 G5:1 D5:1 E5:1 D5:1 B4:1 G4:1",
          4: "~G4:2 D5:1 B4:1 G5:1 B4:1 D5:1 B4:1"}
    Ach = [("G", "G"), ("C", "D"), ("G", "G"), ("Am", "D"), ("G", "G"), ("C", "D"), ("C", "D"), ("G", "G")]
    B = ["E5:1 C5:1 C5:1 E5:1 G5:1 E5:1 C5:1 E5:1", "D5:1 B4:1 B4:1 D5:1 G5:1 D5:1 B4:1 D5:1",
         "E5:1 C5:1 C5:1 E5:1 G5:1 E5:1 A5:1 G5:1", "F#5:1 D5:1 A4:1 D5:1 F#5:1 A5:1 G5:1 F#5:1",
         "E5:1 C5:1 C5:1 E5:1 G5:1 E5:1 C5:1 E5:1", "D5:1 B4:1 B4:1 D5:1 G5:1 B4:1 D5:1 G5:1",
         "E5:1 G5:1 C5:1 E5:1 D5:1 C5:1 A4:1 F#4:1", "G4:2 B4:1 G4:1 G4:2 C5:1 D5:1"]
    B2 = {0: "E5:1 C5:1 'C5:1 E5:1 G5:1 E5:1 C5:1 E5:1", 1: "D5:1 B4:1 'B4:1 D5:1 G5:1 D5:1 B4:1 D5:1",
          4: "E5:1 C5:1 'C5:1 E5:1 G5:1 E5:1 C5:1 E5:1"}
    Bch = [("C", "C"), ("G", "G"), ("C", "C"), ("D", "D"), ("C", "C"), ("G", "G"), ("C", "D"), ("G", "G")]
    L.dyn = [(0, 0), (64, 2), (128, 5), (192, 6), (256, 4)]
    L.tempo = [(0, 1.0), (240, 1.0), (248, 0.92), (256, 0.85)]
    last = dance(L, [(A, A2, Ach, "G4:2 B4:1 G4:1 G4:2 D5:1 B4:1"), (B, B2, Bch, None)],
                 ("D.t.d.t.", "D.t.D..."), last_bar="G4:2 B4:1 D5:1 G5:4")
    L.final = L.at(last, 4)
    L.add(L.at(last), 4, pitch("G2"), 0, "bass")
    L.chord(L.final, [pitch(p) for p in ("G2", "D3", "G3", "B3", "D4", "G4")], vel=4, dur_s=3.6)
    L.drum(last, "D...D...")
    return L


def air():
    """tune 3, 'The Lantern by the Water': a slow air in A minor, 3/4. One bar of the lute alone, then
    sixteen bars in four phrases: the first two rise from A and fall back (a half close on E, then home),
    the third climbs into C major and comes down to E, the fourth closes, on a chord of A major"""
    L = Lute("tune3", 164, 6, "Am", rng_for("townsfolk", "tune", 3), groove=(2, [0.0, 0.0]),
             accents=(5, -3, 1, -3, 1, -3), roles={"mel": (72, 0.012, 0.006)})
    bars = [("R:4 E4:2", "Am Am Am"), ("A4:4 B4:2", "Am Am Am"), ("C5:3 B4:1 A4:2", "F F F"),
            ("B4:4 G4:2", "G G G"), ("A4:3 G#4:1 E4:2", "Dm E E"),
            ("%A4:4 B4:2", "Am Am Am"), ("C5:4 D5:2", "C C C"), ("E5:3 D5:1 B4:2", "E7 E7 E7"),
            ("A4:4 E5:2", "Am Am Am"),
            ("'G5:4 E5:2", "C C C"), ("D5:3 C5:1 B4:2", "G G G"), ("C5:2 A4:2 C5:2", "F F F"),
            ("B4:4 E4:2", "E E E"),
            ("A4:4 B4:2", "Am Am Am"), ("C5:3 D5:1 E5:2", "C C C"), ("D5:2 C5:1 B4:1 G#4:2", "Dm E E")]
    for i, (mel, ch) in enumerate(bars):
        L.line(i + 1, mel, leg=1.25)
        L.arpeggio(i + 1, ch.split())
    last = len(bars) + 1
    L.line(last, "A4:6", vel=-2)
    L.final = L.at(last)
    L.chord(L.final, [pitch(p) for p in ("A2", "E3", "A3", "C#4", "E4")], vel=2, spread=0.04, dur_s=4.0)
    L.phrase0 = 6
    L.dyn = [(0, -4), (24, 0), (54, 2), (66, 5), (78, 1), (96, -2)]
    L.tempo = [(0, 0.94), (4, 1.0), (26, 1.0), (30, 0.86), (30.5, 1.0), (50, 1.0), (54, 0.86), (54.5, 1.0),
               (74, 1.0), (78, 0.86), (78.5, 1.0), (90, 1.0), (96, 0.76)]
    return L


def hornpipe():
    """tune 4, 'The Guildhall Hornpipe': a hornpipe in A, dotted, AABB. Broken chords and triplet turns,
    and each part ends on the hornpipe's three stamps"""
    L = Lute("tune4", 368, 8, "A", rng_for("townsfolk", "tune", 4), groove=(2, [0.0, 0.26]),
             accents=(8, -6, 2, -6, 5, -6, 2, -6))
    A = ["E4:1 A4:1 C#5:1 A4:1 E5:1 C#5:1 A4:1 C#5:1", "D5:1 F#5:1 A5:1 F#5:1 D5:1 F#5:1 E5:1 D5:1",
         "C#5:1 E5:1 A5:1 E5:1 C#5:2/3 D5:2/3 C#5:2/3 B4:1 A4:1", "G#4:1 B4:1 E5:1 B4:1 G#4:1 E4:1 F#4:1 G#4:1",
         "A4:1 C#5:1 E5:1 A5:1 G#5:1 A5:1 E5:1 C#5:1", "D5:1 F#5:1 A5:1 F#5:1 E5:2/3 F#5:2/3 E5:2/3 D5:1 B4:1",
         "B4:1 E5:1 G#5:1 E5:1 D5:1 B4:1 G#4:1 B4:1", "A4:2 E4:2 A4:4"]
    A2 = {0: "E4:1 A4:1 C#5:1 A4:1 'E5:1 C#5:1 A4:1 C#5:1", 4: "A4:1 C#5:1 E5:1 'A5:1 G#5:1 A5:1 E5:1 C#5:1"}
    Ach = [("A", "A"), ("D", "D"), ("A", "A"), ("E", "E"), ("A", "A"), ("D", "D"), ("E7", "E7"), ("A", "A")]
    B = ["C#5:1 E5:1 A5:1 E5:1 F#5:1 A5:1 F#5:1 D5:1", "C#5:1 E5:1 A5:1 E5:1 G#5:2/3 A5:2/3 G#5:2/3 F#5:1 E5:1",
         "C#5:1 E5:1 A5:1 E5:1 F#5:1 A5:1 F#5:1 D5:1", "E5:1 D5:1 B4:1 G#4:1 E4:1 G#4:1 B4:1 D5:1",
         "C#5:1 E5:1 A5:1 E5:1 C#5:1 A4:1 E4:1 A4:1", "D5:1 F#5:1 A5:1 F#5:1 D5:1 A4:1 F#4:1 A4:1",
         "B4:1 E5:1 G#5:1 E5:1 D5:1 B4:1 G#4:1 B4:1", "A4:2 E4:2 A4:4"]
    B2 = {0: "'C#5:1 E5:1 A5:1 E5:1 F#5:1 A5:1 F#5:1 D5:1", 2: "'C#5:1 E5:1 A5:1 E5:1 F#5:1 A5:1 F#5:1 D5:1"}
    Bch = [("A", "D"), ("A", "E"), ("A", "D"), ("E7", "E7"), ("A", "A"), ("D", "D"), ("E7", "E7"), ("A", "A")]
    L.dyn = [(0, 0), (64, 2), (128, 5), (192, 6), (256, 4)]
    L.tempo = [(0, 1.0), (244, 1.0), (252, 0.9), (256, 0.86)]
    last = dance(L, [(A, A2, Ach, None), (B, B2, Bch, None)], ("D.t.d.t.", "D.D.D..."), last_bar="A4:2 E4:2 A4:4",
                 no_bass=(7,))
    # the thumb doubles the stamps, and lets the last one ring
    for b in (8, 16, 24, 32):
        for j, p in enumerate(("A2", "E2", "A2")):
            if not (b == last and j == 2):
                L.add(L.at(b, 2 * j), 4 if j == 2 else 2, pitch(p), 4, "bass")
    L.final = L.at(last, 4)
    L.chord(L.final, [pitch(p) for p in ("A2", "E3", "A3", "C#4", "E4")], vel=6, dur_s=3.6)
    L.drum(last, "D.D.D...")
    return L


TUNES = [jig, reel, air, hornpipe]
EVENTS[TUNE_EVENT] = dict(kind="tune", variants=len(TUNES), subtitle="Bard plays the lute", loud=TUNE_LOUDNESS,
                          attenuation=24, stream=True)


def frame_drum(rng, stroke):
    """a small frame drum with a beater: D an open stroke in the middle of the skin (a round, low 'doom' that
    sags in pitch as the skin settles), d the same, lighter, t a tap near the rim with the hand on the skin"""
    if stroke in "Dd":
        dur = 0.5
        t = tvec(dur)
        f0 = 92.0 * rng.uniform(0.985, 1.015)
        ex = lp(white(0.004, rng), 3000) * np.hanning(ns(0.004) + 2)[1:-1]
        r = np.array([1.0, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156, 3.5])
        skin = resonators(fit(ex, ns(dur)), f0 * r, [0.34, 0.2, 0.15, 0.13, 0.1, 0.085, 0.07, 0.06],
                          [1, .7, .5, .35, .28, .2, .14, .1])
        sag = sine_sweep(dur, f0 * 1.35, f0, 5.0) * np.exp(-t / 0.09) * np.clip(t / 0.001, 0, 1)
        beater = bp(white(0.01, rng), 300, 4000) * np.exp(-tvec(0.01) / 0.0018)
        y = nrm(skin) + 0.6 * nrm(sag) + 0.25 * nrm(fit(beater, ns(dur)))
        return nrm(y) * (1.0 if stroke == "D" else 0.62)
    dur = 0.18
    tap = knock(dur, [330, 610, 980, 1460, 2240], [0.07, 0.045, 0.03, 0.022, 0.016], [1, .8, .55, .4, .25], rng,
                lo=200, hi=6000, ex_len=0.0012)
    frame = knock(dur, [1850, 2900, 4300], [0.02, 0.015, 0.01], [1, .6, .4], rng, lo=1000, hi=9000, ex_len=0.0005)
    return nrm(nrm(tap) + 0.35 * nrm(frame)) * 0.42


def lute_tone(x):
    """the nylon guitar towards a lute: less body and boom, more pluck and nasal bite"""
    y = hp(x, 95, order=2)
    y = eq(y, "peak", 230, q=0.9, gain_db=-3.5)
    y = eq(y, "peak", 1150, q=1.4, gain_db=1.5)
    y = eq(y, "peak", 2700, q=0.9, gain_db=3.0)
    return eq(y, "highshelf", 6500, q=0.7, gain_db=1.5)


def perform_tune(n, work, sf2, detune=8.0):
    """compose tune n (1..4), render it with FluidSynth and mix it: returns (mono signal, info)"""
    import warnings
    from make_music import Song, export_stem, render as fluid_render
    L = TUNES[n - 1]()
    notes, hits, end = L.perform()
    spb = L.spb
    song = Song(f"townsfolk_{L.name}", L.bpm, 1, bpb=L.bar)
    main = song.part("lute", LUTE, "lute", hum=0.0, vjit=0, volume=100, expr0=127)
    course = song.part("course", LUTE, "lute", hum=0.0015, vjit=3, lag=0.0035, volume=78, expr0=127)
    val = 8192 + int(round(detune / 100 * 8192))  # RPN 1, channel fine tuning
    course.ccs += [(0.0, 101, 0), (0.0, 100, 1), (0.0, 6, val >> 7), (0.0, 38, val & 127), (0.0, 101, 127),
                   (0.0, 100, 127)]
    for t0, t1, p, v in notes:
        main.n(t0 / spb, (t1 - t0) / spb, p, v)
        if p < 53:  # a bass course: its octave string
            course.n(t0 / spb, (t1 - t0) / spb, p + 12, v - 16)
        elif p < 72:  # the paired courses (the top course is single)
            course.n(t0 / spb, (t1 - t0) / spb, p, v - 6)
    song.end_beat = max(t1 for _, t1, _, _ in notes) / spb
    os.makedirs(work, exist_ok=True)
    mid, wav = os.path.join(work, f"{L.name}.mid"), os.path.join(work, f"{L.name}.wav")
    export_stem(song, "lute", mid, tail_beats=int(1.0 / spb) + 1)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        y = fluid_render(mid, wav, sf2)[0]
    x = lute_tone(y)
    drum = np.zeros(len(x))
    rng = rng_for("townsfolk", "tune", n, "drum")
    for t, c, lv in hits:
        h = frame_drum(rng, c) * lv
        i = ns(t)
        if i < len(drum):
            drum[i:i + len(h)] += h[:len(drum) - i]
    if np.any(drum):  # the drum sits about 12 LU under the lute
        g = integrated_loudness(x) - 12.0 - integrated_loudness(drum)
        x = x + drum * db(g)
    x = reverb(x, wet=0.13, rt60=0.62, predelay=0.009, hf_damp=0.55, seed=90 + n, early_span=0.025)
    # a clean start on the first note, the closing chord left to ring and die away
    a = np.abs(x)
    i0 = max(0, int(np.argmax(a > np.max(a) * 10 ** (-60 / 20))) - ns(0.002))
    i1 = min(len(x), ns(end + 3.2))
    x = x[i0:i1].copy()
    x[:ns(0.004)] *= np.linspace(0, 1, ns(0.004))
    k = ns(1.4)
    x[-k:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, k))
    x, over, short = level(x, TUNE_LOUDNESS, measure="integrated")
    return x, dict(title=TUNES[n - 1].__doc__.split("'")[1], over=over, short=short, notes=len(notes),
                   hits=len(hits))


# =========================================================================== the town crier and the market board
# At noon a townsperson cries the day's news in a capital's market square, reading from a parchment scroll (their
# words are make_townsfolk_voices.py's CRIER lines), and pins it up on the market board, whose slate has the prices
# chalked on it. These are the sounds of their hands: a line of chalk on the slate, the chalk tapped for a full stop,
# a notice tacked to the board, the scroll opened and rolled shut. Like the voices they have no registry entries: the
# game plays them by id (townsfolk.crier.chalk and so on). They happen out in the square, so each has the street's
# slap off the house fronts instead of a room.
SLATE = [640.0, 1170.0, 1890.0, 2760.0, 3910.0, 5300.0]  # the lowest modes of a writing slate in its frame (Hz)
SLATE_T60 = [0.034, 0.028, 0.022, 0.017, 0.012, 0.009]
SLATE_AMPS = [1.0, 0.8, 0.6, 0.45, 0.3, 0.2]
ROLLER = [470.0, 1020.0, 1760.0]  # the scroll's wooden roller knocking in the hand


def chalk_stroke(dur, rng, speed=1.0):
    """one line of chalk drawn over slate: the stick dragged over the stone in a dry, gritty scratch (the chalk
    catching and slipping on the slate a couple of hundred times a second, which buzzes, and crumbs breaking off it),
    the slate ringing faintly under it; louder and brighter where the hand moves fastest"""
    n = ns(dur)
    s = bell(dur, rng.uniform(0.3, 0.42), 1.2, 1.6)
    slip = 0.25 + 0.75 * smooth_random(dur, rng, 130 * speed, 0.0, 1.0) ** 2
    scratch = bp(colored(dur, rng, -1.0), 900, 6500) * slip * s
    grit = crackle(dur, rng, 1100 * speed * s + 40, decay=0.0003, f_lo=1800, f_hi=8500, size_var=0.7) * s
    # the stick and slip: a jittering train of tiny catches, quicker as the hand speeds up, through the slate
    chatter = np.zeros(n)
    i = 0.0
    while i < n:
        k = int(i)
        chatter[k] = s[k] * rng.uniform(0.5, 1.0)
        i += SR / (190.0 * speed * (0.75 + 0.5 * s[k])) * rng.uniform(0.8, 1.2)
    chatter = resonators(chatter, SLATE, SLATE_T60, SLATE_AMPS)
    board = resonators(0.5 * nrm(scratch) + nrm(grit), SLATE, SLATE_T60, SLATE_AMPS)
    y = nrm(scratch) + 0.5 * nrm(grit) + 0.35 * nrm(chatter) + 0.25 * nrm(board)
    return nrm(y) * speed ** 0.5


def chalk_squeak(dur, rng, f0):
    """the chalk biting for a moment and squealing: stick and slip so quick it sings, a thin high note with a few
    harmonics that wavers with the pressure of the hand, its loudness flickering"""
    t = tvec(dur)
    f = f0 * (1.0 + 0.025 * smooth_random(dur, rng, 30, -1.0, 1.0) + 0.02 * np.linspace(1.0, -1.0, len(t)))
    ph = 2 * np.pi * np.cumsum(f) / SR
    y = np.sin(ph) + 0.45 * np.sin(2 * ph + 0.7) + 0.18 * np.sin(3 * ph + 1.9) + 0.07 * np.sin(4 * ph + 0.3)
    return y * bell(dur, 0.25, 1.0, 1.4) * (0.65 + 0.35 * smooth_random(dur, rng, 70, 0.0, 1.0))


@effect("crier.chalk", variants=4, subtitle="Chalk scratches on slate", loud=-18.0, max_len=0.66, fade_out=0.08)
def crier_chalk(v, rng):
    """a price chalked on the market board's slate: one quick line, the chalk's tip touching down with a tick, then a
    dry, gritty scratch and a little squeak where the chalk bites. v1 a line that squeaks as it ends, v2 a quick
    stroke that squeaks as it starts, v3 a long one with a squeak in its middle, v4 a short, soft one"""
    stroke, speed, sq_at, sq_f, sq_len, sq_lev = [(0.36, 1.0, 0.74, 2650.0, 0.07, 0.24),
                                                  (0.24, 1.25, 0.04, 2900.0, 0.05, 0.24),
                                                  (0.46, 0.9, 0.45, 2350.0, 0.06, 0.2),
                                                  (0.3, 0.8, 0.8, 2100.0, 0.05, 0.14)][v]
    dur = stroke + 0.2
    m = Mix(dur)
    tick = knock(0.03, [f * 1.4 for f in SLATE[2:]], [0.004, 0.003, 0.0025, 0.002], [1, .7, .5, .3], rng, lo=1500,
                 hi=11000, ex_len=0.0004)
    m.add(nrm(tick), 0.0, 0.3)
    m.add(chalk_stroke(stroke, rng, speed), 0.003, 1.0)
    m.add(nrm(chalk_squeak(sq_len, rng, sq_f)), 0.003 + sq_at * (stroke - sq_len), sq_lev)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(hp(y, 300), 10500), wet=0.06, seed=150 + v))


def chalk_tick(rng, hard=1.0):
    """the chalk's tip knocked on the slate: a hard little click with the slate's short ring behind it, the dull knock
    of the board's wooden frame, and a crumb or two of chalk"""
    tip = knock(0.04, [3100.0, 4700.0, 6900.0], [0.003, 0.0025, 0.002], [1, .7, .5], rng, lo=1500, hi=12000,
                ex_len=0.0003)
    ring = knock(0.15, SLATE, [t * 1.2 for t in SLATE_T60], SLATE_AMPS, rng, lo=500, hi=9000, ex_len=0.0006)
    frame = knock(0.12, [185.0, 330.0, 590.0], [0.035, 0.028, 0.02], [1, .55, .3], rng, lo=90, hi=1800, ex_len=0.0012)
    crumbs = grains(0.1, rng, 3 + int(3 * hard), 0.006, 0.02, 2500, 8000, t60=(0.002, 0.006))
    m = Mix(0.2)
    m.add(nrm(tip), 0.0, 0.5 * hard)
    m.add(nrm(ring), 0.0, 0.55)
    m.add(nrm(frame), 0.0005, 0.35)
    m.add(nrm(crumbs), 0.004, 0.08)
    return fit(m.out(), ns(0.2))


@effect("crier.chalk_tap", variants=2, subtitle="Chalk taps the slate", loud=-22.0, max_len=0.3, fade_out=0.06,
        limit=3.0)
def crier_chalk_tap(v, rng):
    """the chalk tapped on the slate for a full stop: v1 one firm tap, v2 a lighter one that bounces once"""
    m = Mix(0.3)
    m.add(chalk_tick(rng, [1.0, 0.8][v]), 0.0, 1.0)
    if v == 1:
        m.add(chalk_tick(rng, 0.4), 0.032, 0.22)
    return lead_in(street(hp(fit(m.out(), ns(0.3)), 80), wet=0.06, seed=152 + v))


def paper_flap(dur, rng, size=1.0):
    """a slip of paper flapped flat against the board: a quick papery flutter (its edge shivering in the air it
    pushes aside), a crisp crackle as it bends, and a soft slap as it meets the wood"""
    s = bell(dur, 0.6, 1.2, 0.8)
    flutter = bp(white(dur, rng), 900, 7000) * s * (0.55 + 0.45 * np.sin(2 * np.pi * rng.uniform(18, 26) / size *
                                                                         tvec(dur) + rng.uniform(0, 6.3)) ** 2)
    crack = crackle(dur, rng, 450 * s + 20, decay=0.0005, f_lo=1500, f_hi=8000, size_var=0.8)
    slap_ = bp(white(0.04, rng), 300, 4000) * np.exp(-tvec(0.04) / 0.006)
    wood = resonators(slap_, [210.0, 470.0, 830.0, 1350.0], [0.03, 0.025, 0.018, 0.012], [1, .7, .45, .25])
    m = Mix(dur + 0.12)
    m.add(nrm(flutter), 0.0, 0.55)
    m.add(nrm(crack), 0.0, 0.3)
    m.add(nrm(slap_), dur, 0.8 * size)
    m.add(nrm(wood), dur, 0.35 * size)
    return m.out()


def tack(rng, push=1.0):
    """a tack pushed through the paper into the board with a thumb: the point piercing the paper (a small, dry tick
    and a tear), biting into the wood with a short creak of the grain, then the head pushed home with a dull knock
    and a faint tick of iron"""
    pierce = knock(0.03, [3600.0, 5200.0, 7400.0], [0.004, 0.003, 0.002], [1, .6, .4], rng, lo=2000, hi=12000,
                   ex_len=0.0004)
    tear = crackle(0.025, rng, 2500, decay=0.0003, f_lo=2000, f_hi=9000, size_var=0.5) * bell(0.025, 0.2, 1.0, 2.0)
    n = ns(0.045)
    grain = np.zeros(n)
    i = 0.0
    while i < n:  # the point catching and slipping in the wood's grain
        grain[int(i)] = rng.uniform(0.5, 1.0)
        i += SR / rng.uniform(330.0, 430.0)
    creak = resonators(grain, [520.0, 980.0, 1650.0, 2400.0], [0.012, 0.01, 0.008, 0.006], [1, .7, .5, .3])
    creak *= bell(0.045, 0.4, 1.0, 1.5)
    home = knock(0.12, [230.0, 520.0, 900.0, 1450.0], [0.035, 0.028, 0.02, 0.014], [1, .7, .45, .25], rng, lo=120,
                 hi=3500, ex_len=0.002)
    head = modal(0.06, [4300.0, 6100.0], [0.03, 0.02], [1, .5], rng)
    m = Mix(0.25)
    m.add(nrm(pierce), 0.0, 0.35)
    m.add(nrm(tear), 0.002, 0.25)
    m.add(nrm(creak), 0.012, 0.3 * push)
    m.add(nrm(home), 0.05, 0.8 * push)
    m.add(thud(0.08, rng, f=240, decay=0.01, tone=0.2), 0.05, 0.4 * push)
    m.add(nrm(head), 0.0505, 0.07)
    return m.out()


@effect("crier.pin", variants=3, subtitle="Notice pinned to the board", loud=-17.0, max_len=0.7, fade_out=0.12)
def crier_pin(v, rng):
    """the day's news put up on the market board: the slip of paper flapped flat against the boards, then a tack
    pushed through it with a thumb. v1 a quick slap and one firm push, v2 a slower flap and the thumb pressing twice,
    v3 a smaller slip, tacked lightly"""
    flap_d = [0.12, 0.17, 0.1][v]
    m = Mix(0.7)
    m.add(paper_flap(flap_d, rng, size=[1.0, 1.15, 0.8][v]), 0.0, 1.0)
    at = flap_d + [0.07, 0.09, 0.06][v]
    m.add(tack(rng, [1.0, 0.9, 0.75][v]), at, [0.9, 0.85, 0.7][v])
    if v == 1:  # pressed home again, harder: only the knock of the head and the thumb
        again = knock(0.12, [235.0, 525.0, 910.0, 1460.0], [0.035, 0.028, 0.02, 0.014], [1, .7, .45, .25], rng,
                      lo=120, hi=3500, ex_len=0.002)
        m.add(nrm(again), at + 0.19, 0.5)
        m.add(thud(0.08, rng, f=230, decay=0.01, tone=0.2), at + 0.19, 0.3)
    return lead_in(street(fit(m.out(), ns(0.7)), wet=0.07, seed=154 + v))


def parchment(dur, rng, rate, crisp=1.0, turns=7.0):
    """parchment on the move: a dry rustle of the stiff skin (rate, a 0..1 curve, is how fast it moves), the crackle
    of it bending (fine and dense where it moves fast; crisp sets how much), a few bigger crinkles, and a swell for
    each turn of the roll as it comes off or goes on"""
    n = ns(dur)
    spin = 2 * np.pi * np.cumsum(turns * (0.5 + 0.5 * rate)) / SR + rng.uniform(0, 2 * np.pi)
    turn = 0.35 + 0.65 * np.sin(spin) ** 2
    rustle = bp(colored(dur, rng, -1.5), 450, 6500) * rate ** 1.2 * turn * smooth_random(dur, rng, 40, 0.6, 1.0)
    fine = crackle(dur, rng, 900 * rate * crisp + 20, decay=0.0005, f_lo=1500, f_hi=8000, size_var=0.9) * rate
    big = Mix(dur)
    for i in np.nonzero(rng.random(n) < 16.0 * crisp * rate / SR)[0]:
        f = float(np.exp(rng.uniform(np.log(1200.0), np.log(3500.0))))
        k = knock(0.03, [f, f * 1.6, f * 2.3], [0.007, 0.005, 0.004], [1, .6, .35], rng, lo=800, hi=9000,
                  ex_len=0.0015)
        big.add(nrm(k), i / SR, rng.uniform(0.4, 1.0) * rate[i])
    return nrm(0.6 * nrm(rustle) + nrm(fine) + 0.7 * nrm(fit(big.out(), n)))


@effect("crier.unroll", variants=2, subtitle="Scroll unrolls", loud=-18.0, max_len=1.25, fade_out=0.15)
def crier_unroll(v, rng):
    """the crier's parchment scroll opened to read from: the roll sprung loose with a crackle and its wooden roller
    knocking in the hand, the stiff skin unrolled between the hands in a dry, crisp rustle that quickens as it goes (a
    swell for each turn that comes off the roll), then pulled taut with a soft snap. v1 opened in one smooth pull, v2
    in two, with a catch between"""
    dur = 1.2
    m = Mix(dur)
    m.add(nrm(crackle(0.06, rng, 1800, decay=0.0006, f_lo=1500, f_hi=8000) * bell(0.06, 0.3, 1.0, 1.5)), 0.0, 0.55)
    m.add(nrm(knock(0.08, ROLLER, [0.025, 0.018, 0.012], [1, .6, .35], rng, lo=200, hi=4000)), 0.005, 0.3)
    od = [0.8, 0.86][v]
    pts = [[(0.0, 0.15), (0.15, 0.5), (0.55, 1.0), (0.72, 0.8), (od, 0.0)],
           [(0.0, 0.2), (0.2, 0.75), (0.34, 0.25), (0.42, 0.3), (0.64, 1.0), (0.78, 0.8), (od, 0.0)]][v]
    m.add(parchment(od, rng, env_points(pts, od), crisp=1.0, turns=7.0), 0.04, 0.85)
    at = 0.04 + od - 0.03  # pulled taut: the sheet snapping flat between the hands, a soft, low thwap with a crackle
    m.add(nrm(bp(white(0.03, rng), 250, 3000) * np.exp(-tvec(0.03) / 0.006)), at, 0.75)
    m.add(nrm(sine_sweep(0.05, 170, 95, 3.0) * np.exp(-tvec(0.05) / 0.012)), at, 0.35)
    m.add(nrm(crackle(0.05, rng, 1200, decay=0.0005, f_lo=2000, f_hi=8000) * np.exp(-tvec(0.05) / 0.015)), at + 0.003,
          0.35)
    return lead_in(street(lp(fit(m.out(), ns(dur)), 10000), wet=0.07, seed=158 + v))


@effect("crier.roll_up", variants=2, subtitle="Scroll rolls shut", loud=-18.0, max_len=1.1, fade_out=0.15)
def crier_roll_up(v, rng):
    """the scroll rolled shut again: the skin curled over on itself and wound up between the palms in a softer,
    sliding rustle (fewer crackles, its turns slipping over one another), then the roll squared off with a pat of the
    hand and its roller knocking in the palm. v1 rolled briskly, v2 more slowly, patted twice"""
    od = [0.72, 0.82][v]
    dur = od + 0.3
    m = Mix(dur)
    pts = [[(0.0, 0.0), (0.08, 0.7), (0.4, 0.9), (0.62, 0.6), (od, 0.0)],
           [(0.0, 0.0), (0.1, 0.55), (0.45, 0.75), (0.7, 0.5), (od, 0.0)]][v]
    rate = env_points(pts, od)
    m.add(parchment(od, rng, rate, crisp=0.55, turns=[5.0, 4.0][v]), 0.0, 0.85)
    m.add(nrm(bp(colored(od, rng, -2.5), 300, 3000) * rate ** 1.5), 0.0, 0.3)
    pats = [[od + 0.04], [od + 0.03, od + 0.16]][v]
    for k, at in enumerate(pats):
        g = 1.0 if k == 0 else 0.6
        m.add(thud(0.12, rng, f=150, decay=0.014, tone=0.15), at, 0.6 * g)
        m.add(nrm(bp(white(0.025, rng), 400, 4000) * np.exp(-tvec(0.025) / 0.005)), at, 0.3 * g)
        m.add(nrm(knock(0.08, ROLLER, [0.025, 0.018, 0.012], [1, .6, .35], rng, lo=200, hi=4000)), at + 0.008,
              0.35 * g)
    return lead_in(street(lp(fit(m.out(), ns(dur)), 10000), wet=0.07, seed=160 + v))


# =========================================================================== main
def write_checked(path, x, quality=6, ceiling_db=-1.0):
    """encode, decode and measure: if the encoder's overshoot lifts the true peak over the ceiling, turn the
    sound down by that much and encode it again"""
    for _ in range(3):
        write_ogg(path, x, quality=quality)
        over = 20 * np.log10(true_peak(read_audio(path)[0])) - ceiling_db
        if over <= 0:
            break
        x = x * db(-(over + 0.05))
    return x


def build_effect(event, r, v):
    rng = rng_for("townsfolk", event, v)
    x = r["fn"](v, rng)
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
    x = finalize(x, peak_db=-3.0, fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])
    return level(x, r["loud"], max_limit_db=r["limit"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--work", default="build/townsfolk_music_work")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    a = ap.parse_args()
    sel = [("bard.tune" if s == "tunes" else s) for s in a.only.split(",")] if a.only else None
    for event, r in EVENTS.items():
        if sel and not any(event == s or event.startswith(s) for s in sel):
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            name = sound_path(event, v + 1)
            if r["kind"] == "tune":
                x, info = perform_tune(v + 1, a.work, a.sf2)
                over, short = info["over"], info["short"]
                x = write_checked(os.path.join(a.assets, "sounds", name + ".ogg"), x, quality=TUNE_QUALITY)
                extra = f"  \"{info['title']}\" {integrated_loudness(x):.1f} LUFS"
            else:
                x, over, short = build_effect(event, r, v)
                x = write_checked(os.path.join(a.assets, "sounds", name + ".ogg"), x)
                extra = ""
            if a.wav:
                write_wav(os.path.join(a.wav, name + ".wav"), x)
            note = (f"  limited {over:.1f} dB" if over > 0.05 else "") + (f"  {short:.1f} dB under (set by peak)"
                                                                          if short > 0.05 else "")
            print(f"{name:34s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s{note}{extra}", flush=True)


if __name__ == "__main__":
    main()
