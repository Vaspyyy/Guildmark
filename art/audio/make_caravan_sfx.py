"""Guildmark caravans (the Living World's roads): the wagon rolling and creaking, its harness bells, brake and canvas, a
crate set down; a waylaying (the band's whistle and whoop, the wagon going over, crates smashed, sacks ripped, bottles
broken, a wheel snapping); the draught horse (hooves on earth, stone and planks, a stamp, snorts, neighs, a squeal, a
fall, grazing); and the crew's moves, timed to their clips (art/scripts/road_clips.py: LivingMoments plays each on the
tick it happens, from where it happens).

usage (from the repo root):
    python3 -B art/audio/make_caravan_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]
        [--mp3 FILE]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/caravan/ (the event caravan.hoof_stone is hoof_stone1..4),
and art/audio/caravan_sounds.json and caravan_lang.json: their sounds.json entries and subtitles, merged into the real
sounds.json and en_us.json by hand as the other effects' are. --only takes event names; --mp3 joins what was rendered
into one listen-through MP3.

The three rolling sounds (roll, roll_stone, roll_planks) are loops: each file comes round seamlessly on itself, for a
looping sound whose volume (and a little of its pitch) follows the wagon's speed. Everything else is a one-shot.

Everything is synthesised with sfxkit, make_townsfolk_sfx.py's helpers and the Dusk Rider's horse (make_sfx.py's
whinny and hoof, played here by a heavier, plainer animal), levelled the same way as the townsfolk's (the loudest
100 ms to a K-weighted target, peaks under -1 dBFS; the loops by gain alone, so their seams stay whole), and seeded,
so a rerun is bit-identical.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from make_townsfolk_sfx import *  # noqa: E402,F401,F403  (sfxkit, and the townsfolk's own helpers)
from make_townsfolk_sfx import level, write_checked, true_peak, loudness_curve  # noqa: E402
from make_sfx import whinny, hoof, clank, outdoors  # noqa: E402

SOUND_DIR = "caravan"
EVENTS = {}  # event (without the "caravan." prefix) -> recipe


def effect(event, variants=1, subtitle="", loud=-16.0, max_len=None, fade_out=0.03, tail_db=-62.0,
           attenuation=None, limit=2.0, loop=False):
    """registers a sound effect, as make_townsfolk_sfx.effect does (loud: the loudest 100 ms, K-weighted LUFS; limit:
    the most dB of peak limiting allowed to get there; attenuation: how far it carries in blocks, when more than 16;
    loop: the file comes round seamlessly on itself)"""
    def deco(fn):
        EVENTS[event] = dict(fn=fn, variants=variants, subtitle=subtitle, loud=loud, max_len=max_len, fade_out=fade_out,
                             tail_db=tail_db, attenuation=attenuation, limit=limit, loop=loop)
        return fn
    return deco


def sound_path(event, n):
    """'hoof_stone', 1 -> 'caravan/hoof_stone1'"""
    return f"{SOUND_DIR}/{event}{n}"


# =========================================================================== shared pieces
def wander(dur, rng, rate, depth):
    """a slow random drift about 1 (within +-depth)"""
    return 1 + depth * (2 * smooth_random(dur, rng, rate) - 1)


def cloth(dur, rng, peak=0.4, lo=300.0, hi=3600.0, grit=700.0, rub=14.0):
    """cloth or canvas sliding and rushing: band noise swelling with the movement, rippled by the weave catching, a few
    fibres crackling"""
    s = bell(dur, peak, 1.4, 1.6)
    body = bp(colored(dur, rng, -2.0), lo, hi) * (0.5 + 0.5 * smooth_random(dur, rng, rub))
    fibres = crackle(dur, rng, grit * s + 5, decay=0.0007, f_lo=1000, f_hi=4800, size_var=0.8)
    return nrm(body) * s ** 1.2 + 0.25 * nrm(fibres) * s


def skin(dur, rng, f_res=1200.0, t60=0.03, bright=0.8, weight=1.0):
    """a flat hand on skin, hide or a palm: the snap of the palm, the pocket of air under it, the give of the flesh"""
    t = tvec(dur)
    ex = white(dur, rng) * np.exp(-t / 0.004) * np.clip(t / 0.0004, 0, 1)
    pocket = resonators(ex, [f_res, f_res * 1.9], [t60, t60 * 0.6], [1.0, 0.25])
    m = Mix(dur)
    m.add(nrm(pocket), 0.0, 0.85)
    m.add(nrm(bp(ex, 550, 6500)), 0.0, 0.7)
    m.add(nrm(hp(ex, 2600) * np.exp(-t / 0.0015)), 0.0, 0.4 * bright)
    m.add(nrm(thump(min(dur, 0.09), 230, 130, rng, decay=0.012, click=0.0, noise=0.3, nlp=800)), 0.0, 0.35 * weight)
    return drive(nrm(fit(m.out(), ns(dur))), 1.8)


def board(dur, rng, f0, t60=0.05, ex_len=0.002, lo=120, hi=4000):
    """a wooden board or plank struck: a hollow knock"""
    return nrm(knock(dur, f0 * np.array([1.0, 1.58, 2.24, 3.1, 4.3]), np.array([1.0, 0.75, 0.5, 0.33, 0.2]) * t60,
                     [1, .7, .5, .32, .2], rng, lo=lo, hi=hi, ex_len=ex_len))


def grit(dur, rng, rate, lo=300, hi=3000, decay=0.0009, size_var=0.8):
    """grit and gravel crushed or scattered: a dense patter of tiny grains"""
    return crackle(dur, rng, rate, decay=decay, f_lo=lo, f_hi=hi, size_var=size_var)


def splinter(dur, rng, density=14000.0, decay=0.03, lo=500, hi=6000):
    """wood giving way: a burst of fibres cracking, thinning out fast"""
    t = tvec(dur)
    return nrm(crackle(dur, rng, density * np.exp(-t / decay) + 20, decay=0.0006, f_lo=lo, f_hi=hi, size_var=1.1))


def crotal(dur, rng, f0, strike=1.0):
    """a small harness bell (a crotal: a hollow brass ball round a loose pea) struck by its pea"""
    return nrm(ring(dur, f0 * np.array([1.0, 1.52, 2.03, 2.71, 3.6]), np.array([0.3, 0.2, 0.13, 0.08, 0.05]) * strike,
                    [1.0, 0.55, 0.38, 0.22, 0.12], rng, doublet=0.003, doublet_amp=0.6))


def breath(dur, rng, a, b=None, scale=1.0, curve=None, air=0.3):
    """an unvoiced breath shaped by the mouth: noise through the formants of vowel [a] (opening to [b]) and a little
    open air"""
    src = colored(dur, rng, -1.0)
    return nrm(nrm(formants(src, a, b, scale=scale, curve=curve)) + air * nrm(bp(src, 400, 6000)))


def voiced(dur, rng, f0, a, b=None, scale=1.0, curve=None, breath_=0.35, jitter=0.03, shimmer=0.15, subharm=0.0):
    """a voiced sound: the throat's buzz at f0 (a number or a curve) through the formants of [a] opening to [b]"""
    src = glottal(dur, f0, rng, jitter=jitter, shimmer=shimmer, breath=breath_, subharm=subharm)
    return nrm(formants(src, a, b, scale=scale, curve=curve))


def loopable(x, xfade):
    """[x] made to come round on itself: its last [xfade] s laid over its first, equal power (the layers are
    unrelated noise), and cut off"""
    k = ns(xfade)
    body = np.array(x[:len(x) - k], dtype=float)
    w = np.linspace(0, 1, k, endpoint=False)
    body[:k] = body[:k] * np.sin(w * np.pi / 2) + x[len(x) - k:] * np.cos(w * np.pi / 2)
    return body


def circular(x, fn):
    """fn (a filter) run over a loop as if it went on round: three laps through, the middle kept"""
    n = len(x)
    return fn(np.concatenate([x, x, x]))[n:2 * n]


def field(x, wet=0.08, seed=31):
    """the open road: a slap back off the verges and a short tail"""
    return reverb(x, wet=wet, rt60=0.9, predelay=0.03, hf_damp=0.65, seed=seed, early_span=0.06)


# =========================================================================== the wagon rolling (loops)
def rut(dur, rng, f=72.0, weight=1.0, boards=(180, 260, 330), rattle=1.0):
    """a wheel dropping into a rut and out: the whole wagon's thump, the bed's boards knocking, the load shifting"""
    m = Mix(dur)
    m.add(nrm(thump(dur, f * 1.3, f * 0.7, rng, decay=0.07, click=0.05, noise=0.8, nlp=300)), 0.0, 0.9 * weight)
    for i, f0 in enumerate(boards):
        at = 0.004 + rng.uniform(0, 0.05) + 0.03 * i
        m.add(board(min(0.25, dur - at), rng, f0 * rng.uniform(0.95, 1.05), t60=0.04), at, 0.35 * rattle * rng.uniform(0.6, 1.0))
    for _ in range(3):
        at = rng.uniform(0.04, 0.2)
        m.add(nrm(box(0.2, rng, rng.uniform(260, 420), weight=0.2)), at, 0.18 * rattle)
    return fit(m.out(), ns(dur))


def rolling(dur, rng, rumble_hz, rumble, grit_rate, grit_band, grit_gain, ruts, creaks, clatter=None, drums=None):
    """the wagon going along: the iron tyres' rumble on the road, grit crushed under them, ruts every so often, the
    axles and the box creaking, and on stone the tyres clattering over the setts, on planks each board drumming"""
    m = Mix(dur)
    t = tvec(dur)
    rb = lp(brown(dur, rng), rumble_hz, order=4) * wander(dur, rng, 3.0, 0.3)
    m.add(nrm(rb), 0.0, rumble)
    gr = grit(dur, rng, grit_rate * wander(dur, rng, 5.0, 0.4), lo=grit_band[0], hi=grit_band[1])
    m.add(nrm(gr), 0.0, grit_gain)
    hush = bp(pink(dur, rng), grit_band[0], grit_band[1] * 0.6) * wander(dur, rng, 4.0, 0.35)
    m.add(nrm(hush), 0.0, grit_gain * 0.35)
    for at, f, w in ruts:
        m.add(rut(0.45, rng, f, w), at, 1.0)
    for at, cd, r0, r1, modes, g in creaks:
        m.add(nrm(creak(cd, rng, r0, r1, modes, 0.05)), at, g)
    if clatter:  # iron on stone: every sett's edge under each tyre
        rate, f_lo, f_hi, g = clatter
        k = 0.0
        while k < dur - 0.05:
            k += rng.exponential(1.0 / rate)
            if k >= dur - 0.05:
                break
            f = np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))
            hit = knock(0.05, [f, f * 1.47, f * 2.31], [0.012, 0.009, 0.006], [1, .6, .35], rng, lo=600, hi=9000, ex_len=0.0007)
            m.add(nrm(hit), k, g * rng.uniform(0.3, 1.0))
            if rng.random() < 0.35:
                m.add(nrm(thump(0.06, 110, 70, rng, decay=0.012, click=0.1, noise=0.6, nlp=500)), k, 0.5 * g)
    if drums:  # planks: each board under each wheel
        rate, f_lo, f_hi, g = drums
        k = 0.0
        while k < dur - 0.05:
            k += rng.uniform(0.6, 1.4) / rate
            if k >= dur - 0.05:
                break
            f0 = rng.uniform(f_lo, f_hi)
            d = comb(board(0.18, rng, f0, t60=0.06, ex_len=0.003), 1.0 / (f0 * 0.5), 0.45, 0.4)
            m.add(nrm(d), k, g * rng.uniform(0.45, 1.0))
            m.add(nrm(thump(0.12, f0 * 0.6, f0 * 0.4, rng, decay=0.03, click=0.0, noise=0.5, nlp=400)), k, 0.5 * g)
    y = fit(m.out(), ns(dur)) * np.clip(t / 0.01, 0, 1)
    return y


@effect("roll", variants=2, subtitle="Wagon wheels rumble", loud=-24.0, attenuation=24, loop=True)
def roll(v, rng):
    """on earth and grass: a soft rumble, grit crunched under the tyres, a rut or two (the bed's boards knock, the
    load shifts), the axles creaking round"""
    dur = [2.7, 3.1][v] + 0.3
    ruts = [[(0.35, 72, 0.9), (1.75, 64, 0.6)], [(0.6, 68, 0.7), (1.5, 76, 0.5), (2.45, 62, 1.0)]][v]
    creaks = [[(0.9, 0.42, 45, 110, [300, 480, 720, 1050, 1500], 0.18), (2.2, 0.3, 60, 140, [520, 800, 1200, 1750], 0.12)],
              [(0.2, 0.35, 50, 120, [280, 450, 690, 1000, 1450], 0.16), (1.9, 0.45, 40, 100, [340, 530, 790, 1150], 0.18)]][v]
    y = rolling(dur, rng, 160, 1.0, 900, (350, 3200), 0.32, ruts, creaks)
    return loopable(y, 0.3)


@effect("roll_stone", variants=2, subtitle="Wagon wheels clatter on stone", loud=-21.0, attenuation=24, loop=True)
def roll_stone(v, rng):
    """on stone, setts and gravel: a harder rumble, the iron tyres clattering over every joint, the box rattling"""
    dur = [2.4, 2.8][v] + 0.3
    ruts = [[(0.5, 80, 0.7), (1.6, 74, 0.5)], [(0.3, 78, 0.6), (1.4, 84, 0.8), (2.3, 72, 0.5)]][v]
    creaks = [[(1.1, 0.3, 70, 160, [420, 650, 980, 1400], 0.12)], [(2.0, 0.35, 60, 150, [380, 600, 900, 1300], 0.12)]][v]
    y = rolling(dur, rng, 230, 1.0, 2200, (500, 5000), 0.4, ruts, creaks, clatter=(26.0, 900, 2600, 0.55))
    return loopable(y, 0.3)


@effect("roll_planks", variants=2, subtitle="Wagon rumbles over planks", loud=-21.0, attenuation=24, loop=True)
def roll_planks(v, rng):
    """over a bridge's planks: each board drumming hollow under the wheels, the deck booming below"""
    dur = [2.4, 2.8][v] + 0.3
    creaks = [[(0.8, 0.4, 40, 100, [260, 420, 640, 950], 0.2)], [(1.7, 0.45, 45, 110, [240, 400, 610, 900], 0.2)]][v]
    y = rolling(dur, rng, 120, 0.8, 500, (300, 2400), 0.2, [(1.2 + 0.4 * v, 70, 0.5)], creaks,
                drums=(11.0, 105, 160, 0.9))
    return loopable(y, 0.3)


# =========================================================================== the wagon's parts
@effect("creak", variants=4, subtitle="Wagon creaks", loud=-21.0, max_len=1.2, fade_out=0.2)
def wagon_creak(v, rng):
    """the loaded wagon's timbers under strain: v1 the box working on its frame, v2 an axle in its hub, v3 the tilt's
    hoops twice, v4 a long groan of the whole wagon"""
    dur = [0.8, 0.6, 0.8, 1.2][v]
    m = Mix(dur)
    if v == 0:
        m.add(nrm(creak(0.55, rng, 40, 120, [220, 360, 540, 800, 1150], 0.06)), 0.0, 1.0)
        m.add(nrm(box(0.2, rng, 300, weight=0.2)), 0.42, 0.25)
    elif v == 1:
        m.add(nrm(creak(0.45, rng, 90, 260, [640, 980, 1500, 2200, 3100], 0.04)), 0.0, 1.0)
    elif v == 2:
        for at, d, f in ((0.0, 0.26, 480), (0.38, 0.3, 520)):
            m.add(nrm(creak(d, rng, 70, 180, [f, f * 1.58, f * 2.4, f * 3.5], 0.04)), at, 1.0 if at == 0 else 0.8)
    else:
        m.add(nrm(creak(1.0, rng, 25, 75, [150, 250, 410, 620, 900], 0.07)), 0.0, 1.0)
        m.add(nrm(creak(0.35, rng, 80, 200, [700, 1080, 1600], 0.03)), 0.55, 0.3)
        m.add(nrm(box(0.25, rng, 260, weight=0.3)), 0.8, 0.22)
    return lead_in(field(fit(m.out(), ns(dur)), 0.06, seed=10 + v))


@effect("bells", variants=4, subtitle="Harness bells jingle", loud=-22.0, max_len=0.9, fade_out=0.25, attenuation=20)
def bells(v, rng):
    """the bells on the hames shaken by a stride: a few small brass bells, each struck two or three times by its pea"""
    dur = 0.9
    m = Mix(dur)
    tones = [(2380, 2950, 3420), (2470, 3080, 3610), (2290, 2840, 3330, 3900), (2520, 3180)][v]
    for i, f0 in enumerate(tones):
        at = rng.uniform(0.0, 0.03) + 0.012 * i
        for k in range(int(rng.integers(2, 4))):
            m.add(crotal(0.6, rng, f0 * rng.uniform(0.997, 1.003), strike=rng.uniform(0.6, 1.0)), at, rng.uniform(0.35, 1.0) * 0.85 ** k)
            at += rng.uniform(0.025, 0.07)
    # the leather and the hames' rings under them
    m.add(nrm(jingle(0.3, rng, 6, 1800, 5000, t60=(0.01, 0.03), start=0.0, spread=0.12)), 0.0, 0.12)
    return lead_in(field(fit(m.out(), ns(dur)), 0.05, seed=20 + v))


@effect("brake", variants=2, subtitle="Wagon brake squeals", loud=-17.0, max_len=1.6, fade_out=0.3, attenuation=24)
def brake(v, rng):
    """the brake hauled on: the lever's pawl clacking over its teeth, the block biting the iron tyre with a scrape and
    then a wavering squeal that chatters as the wheel slows, the wagon groaning to a stop"""
    dur = [1.5, 1.3][v]
    m = Mix(dur)
    for i in range([3, 2][v]):
        at = 0.06 * i
        m.add(clank(0.08, rng.uniform(1500, 1800), rng, "plate", n=6, t60_lo=0.05, t60_hi=0.01, beat=0), at, 0.25)
        m.add(nrm(board(0.06, rng, 420, t60=0.015)), at, 0.15)
    s0 = 0.16
    sd = dur - s0 - 0.15
    t = tvec(sd)
    u = t / sd
    f0 = [1080, 1240][v] * wander(sd, rng, 6.0, 0.025) * (1 - 0.06 * u)
    chatter = 0.55 + 0.45 * np.sin(phase_from_freq([27.0, 33.0][v] * wander(sd, rng, 3.0, 0.2), len(t))) ** 2
    ph = phase_from_freq(f0, len(t))
    sq = sum(a * np.sin(k * ph + rng.uniform(0, 6)) for k, a in ((1, 1.0), (2, 0.55), (3, 0.38), (4, 0.2), (5, 0.12)))
    env = np.clip(t / 0.08, 0, 1) * (1 - 0.7 * u) ** 1.3 * chatter
    m.add(nrm(bp(sq, 700, 7000) * env), s0 + 0.03, 0.8)
    scrape = bp(colored(sd, rng, -1.0), 600, 5000) * np.clip(t / 0.02, 0, 1) * np.exp(-t / 0.35)
    m.add(nrm(scrape + 0.5 * grit(sd, rng, 2500 * np.exp(-t / 0.3) + 50, lo=800, hi=5000)), s0, 0.45)
    m.add(nrm(creak(0.7, rng, 30, 80, [180, 300, 460, 700], 0.06)), s0 + 0.3, 0.35)
    m.add(nrm(rut(0.4, rng, 70, 0.5, boards=(200, 290), rattle=0.6)), dur - 0.42, 0.45)
    return lead_in(field(fit(m.out(), ns(dur)), 0.08, seed=30 + v))


@effect("tilt", variants=3, subtitle="Canvas flaps", loud=-21.0, max_len=1.6, fade_out=0.3)
def tilt(v, rng):
    """the canvas tilt in the wind: a rush as it fills, then snaps as it slaps back against its hoops; v3 luffs, rattling
    fast, before one snap"""
    dur = [1.3, 1.1, 1.6][v]
    m = Mix(dur)
    m.add(cloth(dur, rng, peak=0.35, lo=200, hi=2600, grit=300, rub=9.0), 0.0, 0.5)
    snaps = [[0.18, 0.62], [0.1, 0.32, 0.5], [1.05]][v]
    for at in snaps:
        sd = 0.25
        t = tvec(sd)
        crack = bp(white(sd, rng), 250, 3200) * np.exp(-t / 0.022) * np.clip(t / 0.003, 0, 1)
        m.add(nrm(crack), at, rng.uniform(0.7, 1.0))
        m.add(nrm(thump(0.15, 140, 90, rng, decay=0.03, click=0.0, noise=0.8, nlp=350)), at, 0.4)
        m.add(cloth(0.3, rng, peak=0.2, lo=300, hi=3000, grit=200), at + 0.01, 0.35)
    if v == 2:  # luffing
        ld = 0.9
        t = tvec(ld)
        flap = (0.5 + 0.5 * np.sin(phase_from_freq(9.0 * wander(ld, rng, 2.0, 0.15), len(t)))) ** 3
        lf = bp(colored(ld, rng, -1.5), 220, 2200) * flap * bell(ld, 0.5, 1.2, 1.5)
        m.add(nrm(lf), 0.1, 0.7)
    return lead_in(field(fit(m.out(), ns(dur)), 0.07, seed=40 + v))


@effect("crate_down", variants=3, subtitle="Crate set down", loud=-17.0, max_len=0.7, fade_out=0.15)
def crate_down(v, rng):
    """a crate of goods set down: its boards' knock and the ground under it, what's inside settling; v3 on the wagon's
    bed, hollow"""
    dur = 0.7
    m = Mix(dur)
    f0 = [240, 210, 260][v]
    k = knock(0.3, np.array([1.0, 1.62, 2.41, 3.37, 4.6]) * f0, [0.05, 0.04, 0.03, 0.022, 0.015], [1, .8, .55, .35, .2],
              rng, lo=150, hi=5000, ex_len=0.003)
    m.add(nrm(k), 0.0, 0.8)
    if v == 2:
        m.add(comb(board(0.35, rng, 150, t60=0.07, ex_len=0.004), 1 / 140.0, 0.4, 0.45), 0.001, 0.7)
    else:
        m.add(nrm(thud(0.3, rng, [95, 88][v], decay=0.03, tone=0.4)), 0.0, 0.8)
        m.add(nrm(grit(0.2, rng, 3000 * np.exp(-tvec(0.2) / 0.04) + 10, lo=400, hi=3500)), 0.002, 0.25)
    for _ in range(3):  # inside, settling
        at = rng.uniform(0.03, 0.12)
        m.add(nrm(box(0.15, rng, rng.uniform(400, 700), weight=0.1)), at, 0.15)
    return lead_in(field(fit(m.out(), ns(dur)), 0.05, seed=50 + v))


# =========================================================================== a waylaying
@effect("overturn", variants=2, subtitle="Wagon crashes over", loud=-11.0, max_len=3.2, fade_out=0.6, attenuation=40,
        limit=3.0)
def overturn(v, rng):
    """the wagon going over: its timbers groaning as it tips and the load sliding across, a hoop or stake cracking,
    the bed slamming down on its side, crates tumbling and one breaking, a wheel left spinning in the air"""
    dur = [3.2, 2.9][v]
    m = Mix(dur)
    tip = [0.75, 0.6][v]
    m.add(nrm(creak(tip + 0.1, rng, 25, 120, [140, 240, 390, 600, 880], 0.08)), 0.0, 0.6)
    m.add(nrm(slide(tip, rng, 250, 2200, 260)), 0.15, 0.45)
    for _ in range(4):
        at = rng.uniform(0.2, tip)
        m.add(nrm(box(0.2, rng, rng.uniform(220, 380), weight=0.4)), at, rng.uniform(0.2, 0.4))
    m.add(nrm(jingle(tip, rng, 10, 1500, 5000, t60=(0.01, 0.05), start=0.1, spread=tip)), 0.0, 0.15)
    m.add(splinter(0.3, rng, 9000, 0.03, 600, 6000), tip - 0.12, 0.45)
    m.add(board(0.25, rng, 320, t60=0.03), tip - 0.12, 0.35)
    # down on its side
    hit = tip + 0.05
    m.add(nrm(thump(0.8, 62, 34, rng, decay=0.16, click=0.2, noise=1.0, nlp=260)), hit, 1.0)
    for i in range(7):
        at = hit + 0.004 + rng.exponential(0.025)
        m.add(board(0.3, rng, rng.uniform(130, 330), t60=rng.uniform(0.03, 0.06)), at, rng.uniform(0.35, 0.7))
    m.add(clank(1.2, [210, 240][v], rng, "plate", n=10, t60_lo=0.7, t60_hi=0.08), hit + 0.01, 0.18)
    m.add(nrm(grit(0.6, rng, 5000 * np.exp(-tvec(0.6) / 0.12) + 20, lo=300, hi=4000)), hit, 0.35)
    # what was in it, tumbling
    for _ in range(5):
        at = hit + rng.uniform(0.08, 0.7)
        m.add(nrm(box(0.25, rng, rng.uniform(200, 420), weight=0.6)), at, rng.uniform(0.25, 0.55))
    m.add(splinter(0.4, rng, 12000, 0.04, 500, 5500), hit + [0.32, 0.25][v], 0.5)
    m.add(nrm(thud(0.3, rng, 90, decay=0.03, tone=0.3)), hit + 0.45, 0.4)
    # a wheel in the air, running down
    k = hit + 0.5
    period = 0.11
    while k < dur - 0.2:
        m.add(nrm(board(0.06, rng, 520 + rng.uniform(-40, 40), t60=0.012)), k, 0.12 * (1 - (k - hit) / dur))
        k += period
        period *= 1.12
    return lead_in(field(fit(m.out(), ns(dur)), 0.12, seed=60 + v))


@effect("smash", variants=3, subtitle="Crate smashed", loud=-13.0, max_len=1.0, fade_out=0.25, attenuation=24, limit=3.0)
def smash(v, rng):
    """a crate stove in: the blow, its boards splitting with a crack and a spray of splinters, the pieces falling"""
    dur = 1.0
    m = Mix(dur)
    m.add(nrm(thump(0.3, [130, 120, 145][v], 70, rng, decay=0.04, click=0.3, noise=0.8, nlp=600)), 0.0, 0.8)
    m.add(splinter(0.35, rng, [16000, 12000, 20000][v], 0.035, 500, 7000), 0.002, 0.8)
    m.add(board(0.25, rng, [280, 240, 320][v], t60=0.035, ex_len=0.0015), 0.0, 0.6)
    for i in range([4, 5, 3][v]):
        at = 0.08 + rng.exponential(0.12)
        if at > dur - 0.15:
            continue
        m.add(board(0.2, rng, rng.uniform(250, 600), t60=0.025), at, rng.uniform(0.2, 0.45))
    if v == 2:  # what was in it, spilling out
        m.add(grains(0.6, rng, 40, 0.06, 0.12, 900, 3000), 0.05, 0.3)
    return lead_in(field(fit(m.out(), ns(dur)), 0.08, seed=70 + v))


def tear_cloth(dur, rng, rate=55.0, lo=900, hi=5200):
    """sacking torn: the weave giving thread by thread, a rough buzz of popping fibres"""
    t = tvec(dur)
    s = bell(dur, 0.35, 1.0, 1.4)
    pulse = (0.5 + 0.5 * np.sin(phase_from_freq(rate * wander(dur, rng, 6.0, 0.35), len(t)))) ** 2
    nz = bp(white(dur, rng), lo, hi) * (0.4 + 0.6 * pulse)
    pops = crackle(dur, rng, 2600 * s + 30, decay=0.0005, f_lo=lo, f_hi=hi * 1.3, size_var=1.0)
    return nrm(nrm(nz) * s + 0.8 * nrm(pops) * s)


@effect("rip", variants=3, subtitle="Sack rips", loud=-16.0, max_len=1.1, fade_out=0.3, attenuation=20)
def rip(v, rng):
    """a sack ripped open: the burlap tearing and what's in it pouring out (grain, then v2 a quick double tear, v3 a long
    one and potatoes thudding out)"""
    dur = 1.1
    m = Mix(dur)
    tears = [[(0.0, 0.4)], [(0.0, 0.18), (0.24, 0.22)], [(0.0, 0.55)]][v]
    for at, d in tears:
        m.add(tear_cloth(d, rng, rate=rng.uniform(45, 70)), at, 1.0)
    end = tears[-1][0] + tears[-1][1]
    if v < 2:
        pd = dur - end + 0.1
        t = tvec(pd)
        pour = bp(white(pd, rng), 2500, 9000) * np.clip(t / 0.05, 0, 1) * np.exp(-t / 0.35)
        m.add(nrm(pour), end - 0.15, 0.3)
        m.add(grains(pd, rng, 60, 0.0, 0.15, 1500, 5000, t60=(0.005, 0.015)), end - 0.12, 0.35)
    else:
        for i in range(4):
            at = end - 0.1 + i * rng.uniform(0.07, 0.12)
            m.add(nrm(thud(0.15, rng, rng.uniform(140, 190), decay=0.012, tone=0.5)), at, rng.uniform(0.35, 0.6))
    return lead_in(field(fit(m.out(), ns(dur)), 0.05, seed=80 + v))


def shatter(dur, rng, f, size=1.0):
    """one bottle breaking: its glass ringing as it's struck, the crack and burst of it, shards skittering away"""
    m = Mix(dur)
    fr = f * np.array([1.0, 2.17, 3.57, 5.2]) * rng.uniform(0.98, 1.02, 4)
    m.add(nrm(modal(min(dur, 0.4), fr, np.array([0.25, 0.14, 0.09, 0.06]) * size, [1.0, 0.55, 0.3, 0.16], rng)), 0.0, 0.5)
    t = tvec(0.12)
    m.add(nrm(crackle(0.12, rng, 30000 * np.exp(-t / 0.02) + 50, decay=0.0003, f_lo=2500, f_hi=14000, size_var=1.0)), 0.0, 0.9)
    m.add(grains(dur, rng, 24, 0.02, 0.15, 3000, 9500, t60=(0.01, 0.04), partials=(1.0, 2.1, 3.4)), 0.0, 0.45)
    return fit(m.out(), ns(dur))


@effect("bottles", variants=3, subtitle="Bottles break", loud=-14.0, max_len=1.4, fade_out=0.3, attenuation=24, limit=3.0)
def bottles(v, rng):
    """the bottle crate broken: bottles shattering one on another, shards skittering, what was in them splashing out"""
    dur = [1.2, 1.0, 1.4][v]
    m = Mix(dur)
    m.add(nrm(box(0.25, rng, 230, weight=0.6)), 0.0, 0.5)
    hits = [[0.0, 0.07, 0.19], [0.0, 0.11], [0.0, 0.05, 0.16, 0.31]][v]
    for at in hits:
        m.add(shatter(dur - at, rng, rng.uniform(1300, 2400), size=rng.uniform(0.7, 1.2)), at + 0.005, rng.uniform(0.6, 1.0))
    sd = 0.6
    t = tvec(sd)
    splash = bp(colored(sd, rng, -1.0), 300, 2400) * (0.5 + 0.5 * smooth_random(sd, rng, 30)) * np.clip(t / 0.02, 0, 1) * np.exp(-t / 0.15)
    m.add(nrm(splash), 0.04, 0.4)
    return lead_in(field(fit(m.out(), ns(dur)), 0.07, seed=90 + v))


@effect("wheel_snap", variants=2, subtitle="Wheel snaps", loud=-13.0, max_len=2.4, fade_out=0.4, attenuation=32, limit=3.0)
def wheel_snap(v, rng):
    """a wheel giving way: spokes cracking out of the nave one after another, the iron tyre springing off ringing, the
    wheel falling flat and rocking to rest"""
    dur = [2.4, 2.1][v]
    m = Mix(dur)
    for i in range([3, 2][v]):
        at = i * rng.uniform(0.03, 0.06)
        m.add(splinter(0.25, rng, 18000, 0.025, 700, 7000), at, 0.8 - 0.15 * i)
        m.add(board(0.2, rng, rng.uniform(350, 520), t60=0.02, ex_len=0.001), at, 0.6)
    m.add(nrm(thump(0.5, 90, 45, rng, decay=0.1, click=0.3, noise=0.9, nlp=400)), 0.0, 0.8)
    m.add(clank(1.6, [190, 230][v], rng, "bar", n=7, t60_lo=1.2, t60_hi=0.1, beat=0.004), 0.08, 0.35)   # a ring bends as a bar does
    # down on its face, rocking faster and faster to rest
    k, period, g = 0.55, 0.26, 0.6
    while period > 0.025 and k < dur - 0.1:
        m.add(board(0.12, rng, 210 + rng.uniform(-15, 15), t60=0.03), k, g)
        m.add(clank(0.15, 640, rng, "bar", n=6, t60_lo=0.1, t60_hi=0.02, beat=0), k, 0.08 * g)
        k += period
        period *= 0.78
        g *= 0.88
    return lead_in(field(fit(m.out(), ns(dur)), 0.1, seed=100 + v))


def finger_whistle(dur, rng, f0, glide_up=1.12, fall=0.18, rise_t=0.3):
    """two fingers in the mouth: a pure, piercing note swooping up onto its pitch and falling off at the end, a little
    breath round it"""
    u = np.linspace(0, 1, ns(dur), endpoint=False)
    f = f0 * (1 + (glide_up - 1) * np.sin(np.clip(u / rise_t, 0, 1) * np.pi / 2) - fall * np.clip((u - 0.7) / 0.3, 0, 1) ** 1.5)
    f = f / glide_up
    tone = np.sin(phase_from_freq(f * (1 + 0.004 * np.sin(2 * np.pi * 6.5 * u * dur))))
    tone += 0.06 * np.sin(2 * phase_from_freq(f))
    air = bp(white(dur, rng), f0 * 0.7, f0 * 1.6)
    return nrm(tone + 0.07 * nrm(air)) * bell(dur, 0.2, 1.0, 1.3)


@effect("whistle", variants=3, subtitle="Shrill whistle", loud=-13.0, max_len=1.6, fade_out=0.4, attenuation=64)
def whistle(v, rng):
    """the band's signal from off the road: two shrill notes through the fingers, the second dropping, out in the open"""
    dur = [1.4, 1.2, 1.5][v]
    m = Mix(dur)
    hi_, lo_ = [(2750, 2060), (2900, 2320), (2600, 1950)][v]
    n1, gap, n2 = [(0.36, 0.08, 0.5), (0.28, 0.06, 0.42), (0.42, 0.1, 0.55)][v]
    m.add(finger_whistle(n1, rng, hi_, glide_up=1.1, fall=0.05), 0.0, 1.0)
    m.add(finger_whistle(n2, rng, lo_, glide_up=1.06, fall=0.22, rise_t=0.15), n1 + gap, 0.9)
    return lead_in(outdoors(fit(m.out(), ns(dur)), 0.22, seed=110 + v))


def yell(dur, rng, f0, rise=1.7, vowels=("u", "o"), rough=0.0, scale=1.0, fall=0.22):
    """one rough voice whooping: thrown up from low in the chest to a high hoot, the mouth opening, cracking off"""
    n = ns(dur)
    u = np.linspace(0, 1, n, endpoint=False)
    up = np.sin(np.clip(u / 0.55, 0, 1) * np.pi / 2) ** 1.6
    contour = f0 * (1 + (rise - 1) * up - fall * np.clip((u - 0.78) / 0.22, 0, 1))
    vib = 1 + 0.02 * np.sin(2 * np.pi * rng.uniform(5.0, 7.0) * u * dur + rng.uniform(0, 6))
    src = glottal(dur, contour * vib, rng, jitter=0.045, shimmer=0.22, breath=0.35, subharm=rough)
    y = formants(src, vowels[0], vowels[1], scale=scale, curve=np.clip((u - 0.1) / 0.6, 0, 1))
    y = drive(nrm(y), 2.2)
    env = np.clip(u * dur / 0.05, 0, 1) * np.clip((1 - u) * dur / 0.1, 0, 1)
    hd = 0.05
    hh = bp(white(hd, rng), 700, 4500) * bell(hd, 0.75, 1.0, 1.0)
    return np.concatenate([0.25 * nrm(hh), nrm(y) * env])


@effect("whoop", variants=3, subtitle="Waylayers whoop", loud=-14.0, max_len=2.0, fade_out=0.4, attenuation=48)
def whoop(v, rng):
    """the band breaking cover: a few rough voices whooping, ragged, one after another (v2 'whoo-hoo', two hoots each)"""
    dur = [1.6, 1.9, 1.5][v]
    m = Mix(dur)
    voices = [3, 4, 3][v]
    for i in range(voices):
        f0 = rng.uniform(105, 150)
        sc = rng.uniform(0.92, 1.04)
        at = rng.uniform(0.0, 0.25) + 0.08 * i
        g = rng.uniform(0.55, 1.0)
        if v == 1:
            m.add(yell(0.22, rng, f0 * 1.05, rise=1.25, vowels=("u", "u"), scale=sc, fall=0.05), at, g * 0.7)
            m.add(yell(rng.uniform(0.45, 0.6), rng, f0 * 1.3, rise=1.55, vowels=("u", "o"), rough=0.15, scale=sc), at + 0.27, g)
        else:
            m.add(yell(rng.uniform(0.55, 0.8), rng, f0, rise=rng.uniform(1.6, 1.9), vowels=("u", ["o", "a", "o"][i % 3]),
                       rough=rng.uniform(0.0, 0.25), scale=sc), at, g)
    return lead_in(outdoors(fit(m.out(), ns(dur)), 0.2, seed=120 + v))


# =========================================================================== the draught horse
@effect("hoof", variants=4, subtitle="Hooves thud", loud=-19.0, max_len=0.35, fade_out=0.08, attenuation=20)
def hoof_earth(v, rng):
    """one great feathered hoof on earth or grass: the horse's weight in a dull thud, the shoe's knock muffled, earth
    spat out from under it"""
    dur = 0.35
    fb, fk = [(86, 380), (80, 360), (92, 410), (84, 395)][v]
    y = hoof(dur, rng, fb, fk, hard=[0.25, 0.2, 0.32, 0.28][v], dirt=[0.75, 0.85, 0.65, 0.8][v])
    y = lp(y, 4500) + clods(dur, rng, 3, start=0.02, spread=0.05) * 0.15
    return lead_in(field(fit(y, ns(dur)), 0.05, seed=130 + v))


@effect("hoof_stone", variants=4, subtitle="Hooves clop on stone", loud=-17.0, max_len=0.35, fade_out=0.08, attenuation=24)
def hoof_stone(v, rng):
    """one shod hoof on stone: a hard clop, the iron shoe clinking on the setts, a scrape of grit"""
    dur = 0.35
    fb, fk = [(104, 640), (98, 600), (110, 700), (101, 660)][v]
    m = Mix(dur)
    m.add(hoof(dur, rng, fb, fk, hard=[0.9, 0.85, 1.0, 0.95][v], dirt=0.1), 0.0, 1.0)
    m.add(clank(0.14, [2100, 1950, 2350, 2200][v], rng, "plate", n=8, t60_lo=0.07, t60_hi=0.015, beat=0), 0.001, 0.22)
    m.add(nrm(grit(0.08, rng, 4000 * np.exp(-tvec(0.08) / 0.02) + 10, lo=1200, hi=6000)), 0.004, 0.12)
    return lead_in(field(fit(m.out(), ns(dur)), 0.06, seed=140 + v))


@effect("hoof_wood", variants=3, subtitle="Hooves knock on planks", loud=-17.0, max_len=0.4, fade_out=0.1, attenuation=24)
def hoof_wood(v, rng):
    """one hoof on a bridge's planks: a hollow knock booming under the deck"""
    dur = 0.4
    m = Mix(dur)
    m.add(hoof(dur, rng, [96, 90, 102][v], [430, 400, 460][v], hard=0.5, dirt=0.05), 0.0, 0.7)
    f0 = [128, 118, 140][v]
    m.add(comb(board(0.35, rng, f0, t60=0.08, ex_len=0.004), 1.0 / (f0 * 0.5), 0.5, 0.45), 0.0, 0.9)
    return lead_in(field(fit(m.out(), ns(dur)), 0.06, seed=150 + v))


@effect("horse_stamp", variants=3, subtitle="Horse stamps", loud=-17.0, max_len=0.6, fade_out=0.12)
def horse_stamp(v, rng):
    """an impatient stamp: a hoof lifted and slammed down, scraped back over the ground, the harness shaking"""
    dur = 0.6
    m = Mix(dur)
    m.add(hoof(0.35, rng, [78, 84, 74][v], [370, 400, 350][v], hard=0.4, dirt=0.9), 0.0, 1.0)
    sd = 0.16
    scr = bp(colored(sd, rng, -1.0), 300, 2500) * bell(sd, 0.3, 1.0, 1.4)
    m.add(nrm(nrm(scr) + 0.7 * nrm(grit(sd, rng, 3000 * bell(sd, 0.3, 1.0, 1.4) + 20, lo=400, hi=3500))), [0.12, 0.1, 0.15][v], 0.35)
    m.add(nrm(jingle(0.35, rng, 7, 1500, 4500, t60=(0.01, 0.04), start=0.0, spread=0.1)), 0.01, 0.12)
    if v == 2:
        m.add(crotal(0.4, rng, 2600), 0.02, 0.1)
    return lead_in(field(fit(m.out(), ns(dur)), 0.05, seed=160 + v))


@effect("horse_snort", variants=3, subtitle="Horse snorts", loud=-18.0, max_len=1.4, fade_out=0.25, attenuation=20)
def horse_snort(v, rng):
    """breath blown out hard through big nostrils: a snort, a double snort, a long blow through fluttering lips"""
    dur = [0.8, 0.8, 1.3][v]
    m = Mix(dur)
    shots = [[(0.0, 0.34, 1.0)], [(0.0, 0.16, 0.8), (0.21, 0.32, 1.0)], [(0.0, 0.85, 1.0)]][v]
    for at, d, g in shots:
        t = tvec(d)
        rate = [52, 58, 28][v] * wander(d, rng, 12.0, 0.2)
        fl = (0.5 + 0.5 * np.sin(phase_from_freq(rate, len(t)))) ** [2, 2, 3][v]
        src = colored(d, rng, -0.8) * (0.35 + 0.65 * fl)
        y = formants(src, "u", "uh", scale=0.7, curve=np.clip(t / d, 0, 1)) + 0.4 * hp(src, 1400)
        env = np.clip(t / 0.012, 0, 1) * np.exp(-t / (d * 0.42))
        m.add(nrm(lp(y, 5000)) * env, at, g)
    if v == 2:  # the lips loose at the end of the blow
        tl = tvec(0.25)
        lips = glottal(0.25, 26 + 6 * tl, rng, jitter=0.15, shimmer=0.4, breath=0.9, fry=0.3)
        m.add(nrm(lp(lips, 800)) * np.exp(-tl / 0.09), 0.65, 0.45)
    return lead_in(field(fit(m.out(), ns(dur)), 0.06, seed=170 + v))


@effect("horse_neigh", variants=3, subtitle="Horse neighs", loud=-13.0, max_len=2.2, fade_out=0.5, attenuation=40)
def horse_neigh(v, rng):
    """a big horse's neigh: lower and thicker than a riding horse's, rising to a shrill held note, a long shuddering
    fall and a 'huh-huh' through the nose"""
    dur = [2.0, 1.7, 2.2][v]
    dv = [1.5, 1.2, 1.7][v]
    peak, end = [(590, 240), (640, 280), (540, 220)][v]
    y = whinny(dv, rng, peak, end, hold=[0.3, 0.2, 0.34][v], trill_hz=[(9.5, 7.0), (10.5, 8.0), (8.5, 6.5)][v],
               trill_fm=[(0.05, 0.09), (0.045, 0.08), (0.06, 0.1)][v], g0=[2.35, 2.25, 2.45][v],
               g0_gain=[0.24, 0.3, 0.2][v], nicker=[3, 2, 3][v], rough=[0.12, 0.2, 0.15][v])
    return lead_in(outdoors(fit(lp(y, 6500), ns(dur)), 0.14, seed=180 + v))


@effect("horse_hurt", variants=3, subtitle="Horse squeals", loud=-13.0, max_len=0.8, fade_out=0.15, attenuation=32)
def horse_hurt(v, rng):
    """struck: a shriek cut short, the breath driven out of it"""
    dur = 0.8
    m = Mix(dur)
    dv = [0.38, 0.32, 0.44][v]
    y = whinny(dv, rng, [860, 940, 800][v], [560, 620, 500][v], hold=0.02, rise=0.02, trill_hz=(15.0, 12.0),
               trill_fm=(0.03, 0.05), trill_am=0.4, g0=[2.27, 2.38, 2.2][v], g0_gain=0.32, nicker=0, rough=0.35)
    m.add(y, 0.0, 1.0)
    bd = 0.3
    t = tvec(bd)
    blow = formants(colored(bd, rng, -0.8), "uh", "u", scale=0.7) * np.exp(-t / 0.1) * np.clip(t / 0.01, 0, 1)
    m.add(nrm(lp(blow, 4000)), dv - 0.02, 0.4)
    return lead_in(outdoors(fit(m.out(), ns(dur)), 0.1, seed=190 + v))


@effect("horse_thud", variants=3, subtitle="Horse falls", loud=-13.0, max_len=1.2, fade_out=0.3, attenuation=24, limit=3.0)
def horse_thud(v, rng):
    """a horse's weight hitting the ground: v1 both forehooves coming down off a rear, v2 the knees, v3 the body, the
    harness clattering and the breath knocked out of it"""
    dur = [0.7, 0.8, 1.2][v]
    m = Mix(dur)
    if v == 0:
        for at, fb in ((0.0, 78), (0.09, 72)):
            m.add(hoof(0.4, rng, fb, 350, hard=0.35, dirt=0.9), at, 1.0)
        m.add(nrm(thump(0.4, 70, 40, rng, decay=0.06, click=0.0, noise=0.8, nlp=250)), 0.0, 0.5)
    elif v == 1:
        for at in (0.0, 0.07):
            m.add(nrm(thud(0.35, rng, 70, decay=0.04, tone=0.5)), at, 0.9)
        m.add(nrm(grit(0.3, rng, 3000 * np.exp(-tvec(0.3) / 0.06) + 10, lo=300, hi=3000)), 0.0, 0.3)
    else:
        m.add(nrm(thump(1.0, 58, 30, rng, decay=0.18, click=0.1, noise=1.0, nlp=220)), 0.0, 1.0)
        m.add(nrm(thud(0.4, rng, 75, decay=0.05, tone=0.4)), 0.0, 0.7)
        m.add(nrm(grit(0.5, rng, 4000 * np.exp(-tvec(0.5) / 0.08) + 10, lo=250, hi=3000)), 0.0, 0.35)
        bd = 0.45
        t = tvec(bd)
        blow = formants(colored(bd, rng, -0.8), "uh", "u", scale=0.65) * np.exp(-t / 0.15) * np.clip(t / 0.02, 0, 1)
        m.add(nrm(lp(blow, 3000)), 0.08, 0.35)
    m.add(nrm(jingle(0.5, rng, 12, 1300, 4500, t60=(0.01, 0.05), start=0.0, spread=0.15)), 0.01, 0.18)
    m.add(nrm(creak(0.2, rng, 120, 300, [700, 1100, 1700], 0.03)), 0.02, 0.15)
    return lead_in(field(fit(m.out(), ns(dur)), 0.08, seed=200 + v))


@effect("horse_tear", variants=3, subtitle="Horse grazes", loud=-21.0, max_len=1.0, fade_out=0.2)
def horse_tear(v, rng):
    """a mouthful of grass torn up and chewed: the stalks ripping from the turf, then big teeth grinding"""
    dur = [1.0, 0.8, 0.9][v]
    m = Mix(dur)
    td = [0.14, 0.11, 0.17][v]
    m.add(nrm(crackle(td, rng, 9000 * bell(td, 0.4, 1.0, 1.3) + 50, decay=0.0007, f_lo=600, f_hi=5000, size_var=1.0)), 0.0, 0.9)
    m.add(nrm(thud(0.12, rng, 160, decay=0.012, tone=0.3)), td * 0.6, 0.3)
    at = td + 0.12
    for k in range([4, 3, 3][v]):
        cd = 0.09
        c = crackle(cd, rng, 4000 * bell(cd, 0.3, 1.0, 1.5) + 20, decay=0.0012, f_lo=300, f_hi=2600, size_var=0.9)
        m.add(nrm(c), at, 0.45 * rng.uniform(0.7, 1.0))
        m.add(nrm(thud(0.08, rng, 120, decay=0.01, tone=0.2)), at, 0.15)
        at += rng.uniform(0.17, 0.22)
    return lead_in(field(fit(m.out(), ns(dur)), 0.04, seed=210 + v))


# =========================================================================== the crew's moves
def tongue_click(rng, f=2200.0):
    """the tongue snapped down off the roof of the mouth: a pop ringing in the mouth's hollow"""
    d = 0.05
    t = tvec(d)
    ex = white(d, rng) * np.exp(-t / 0.0012) * np.clip(t / 0.0002, 0, 1)
    y = resonators(ex, [f, f * 0.42, f * 1.7], [0.012, 0.01, 0.006], [1.0, 0.5, 0.3])
    return nrm(y + 0.3 * nrm(bp(ex, 1500, 8000)))


@effect("click", variants=3, subtitle="Driver clicks their tongue", loud=-20.0, max_len=0.5, fade_out=0.08)
def click(v, rng):
    """the driver geeing the horse on: two clicks of the tongue (v3 three, quicker)"""
    dur = 0.5
    m = Mix(dur)
    times = [[0.0, 0.13], [0.0, 0.15], [0.0, 0.09, 0.18]][v]
    for i, at in enumerate(times):
        m.add(tongue_click(rng, [2200, 2050, 2350][v] * rng.uniform(0.96, 1.04)), at, 1.0 - 0.12 * i)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=220 + v))


@effect("reins_flick", variants=3, subtitle="Reins snap", loud=-18.0, max_len=0.6, fade_out=0.12)
def reins_flick(v, rng):
    """the reins flicked down the horse's back: a short whip of leather through the air, its slap on the hide, the
    buckles and the bit's rings shaken"""
    dur = 0.6
    m = Mix(dur)
    m.add(whoosh(0.12, rng, peak=0.7, f_lo=500, f_hi=2600, q=1.2, power=2.0), 0.0, 0.25)
    m.add(skin(0.15, rng, f_res=[760, 820, 700][v], t60=0.025, bright=0.6, weight=0.9), 0.11, 1.0)
    m.add(skin(0.12, rng, f_res=[900, 980, 860][v], t60=0.02, bright=0.5, weight=0.5), 0.125, 0.5)
    m.add(nrm(jingle(0.35, rng, 6, 1800, 5200, t60=(0.01, 0.04), start=0.0, spread=0.08)), 0.12, 0.2)
    return lead_in(field(fit(m.out(), ns(dur)), 0.04, seed=230 + v))


@effect("reins_haul", variants=2, subtitle="Reins creak", loud=-21.0, max_len=0.8, fade_out=0.15)
def reins_haul(v, rng):
    """the reins hauled back: the leather creaking in the fists, the bit's rings clinking"""
    dur = 0.8
    m = Mix(dur)
    m.add(nrm(creak(0.5, rng, 120, 420, [900, 1350, 2000, 2900], 0.03)), 0.0, 1.0)
    m.add(nrm(creak(0.3, rng, 200, 500, [1100, 1650, 2400], 0.02)), 0.3 + 0.1 * v, 0.5)
    m.add(nrm(jingle(0.4, rng, 5, 1600, 4500, t60=(0.01, 0.04), start=0.05, spread=0.15)), 0.0, 0.25)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=240 + v))


@effect("land", variants=2, subtitle="Someone lands", loud=-18.0, max_len=0.6, fade_out=0.12)
def land(v, rng):
    """two feet landing on the road from the wagon: the thud of boots and knees taking it, grit crunched, clothes
    settling, a breath let out"""
    dur = 0.6
    m = Mix(dur)
    for at, f in ((0.0, [92, 88][v]), ([0.035, 0.05][v], [86, 94][v])):
        m.add(nrm(thud(0.25, rng, f, decay=0.028, tone=0.4)), at, 0.9)
    m.add(nrm(grit(0.2, rng, 5000 * np.exp(-tvec(0.2) / 0.04) + 10, lo=350, hi=3500)), 0.003, 0.35)
    m.add(cloth(0.3, rng, peak=0.15, lo=300, hi=3000, grit=300), 0.0, 0.3)
    bd = 0.2
    m.add(breath(bd, rng, "uh", "uh", scale=1.0) * bell(bd, 0.15, 1.0, 1.6), 0.06, 0.12)
    return lead_in(field(fit(m.out(), ns(dur)), 0.04, seed=250 + v))


@effect("grab", variants=3, subtitle="Hand grips wood", loud=-20.0, max_len=0.35, fade_out=0.08)
def grab(v, rng):
    """a hand slapped onto the wagon's wood and gripping it: the palm's slap and the board's knock"""
    dur = 0.35
    m = Mix(dur)
    m.add(skin(0.12, rng, f_res=[1300, 1150, 1450][v], t60=0.02, bright=0.5, weight=0.4), 0.0, 0.7)
    m.add(board(0.2, rng, [330, 290, 370][v], t60=0.04, ex_len=0.003), 0.001, 0.7)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=260 + v))


@effect("knife_saw", variants=4, subtitle="Knife saws leather", loud=-19.0, max_len=0.5, fade_out=0.08)
def knife_saw(v, rng):
    """one stroke of a knife sawing through a thick leather trace: the edge catching and slipping through the hide's
    fibres (v4 the last, deeper, stroke)"""
    dur = [0.38, 0.34, 0.4, 0.45][v]
    sd = dur - 0.05
    s = bell(sd, [0.4, 0.45, 0.35, 0.5][v], 1.2, 1.4)
    rate = [1100, 1300, 1000, 1500][v] * s * wander(sd, rng, 10.0, 0.3) + 30
    fr = crackle(sd, rng, rate, decay=0.0006, f_lo=500, f_hi=5000, size_var=0.6)
    body = resonators(fr, [620, 1080, 1850, 2900], [0.02, 0.015, 0.01, 0.007], [1.0, 0.7, 0.45, 0.25])
    rasp = bp(colored(sd, rng, -0.5), 2000, 7000) * s ** 1.5
    y = nrm(body) + 0.35 * nrm(rasp)
    m = Mix(dur)
    m.add(nrm(y) * s, 0.0, 1.0)
    m.add(nrm(creak(sd * 0.8, rng, 150, 400, [800, 1300, 2100], 0.02)), 0.02, 0.25)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=270 + v))


@effect("trace_cut", variants=2, subtitle="Harness cut", loud=-16.0, max_len=0.8, fade_out=0.15)
def trace_cut(v, rng):
    """the trace parting under the knife: the leather's last fibres snapping, the loose end whipping away, its hook and
    chain rattling as it drops to the road"""
    dur = 0.8
    m = Mix(dur)
    sd = 0.05
    t = tvec(sd)
    m.add(nrm(bp(white(sd, rng), 400, 6000) * np.exp(-t / 0.006) * np.clip(t / 0.0005, 0, 1)), 0.0, 1.0)
    m.add(nrm(thump(0.12, 220, 130, rng, decay=0.015, click=0.2, noise=0.5, nlp=900)), 0.0, 0.5)
    m.add(whoosh(0.18, rng, peak=0.3, f_lo=400, f_hi=2200, q=1.0, power=2.0), 0.01, 0.35)
    m.add(nrm(jingle(0.5, rng, 10, 900, 3600, t60=(0.02, 0.06), start=0.0, spread=0.2)), 0.02, 0.35)
    fall = [0.26, 0.3][v]
    m.add(nrm(thud(0.2, rng, 140, decay=0.015, tone=0.3)), fall, 0.4)
    m.add(nrm(jingle(0.3, rng, 8, 900, 3000, t60=(0.02, 0.05), start=0.0, spread=0.08)), fall, 0.3)
    return lead_in(field(fit(m.out(), ns(dur)), 0.05, seed=280 + v))


@effect("sheathe", variants=2, subtitle="Knife sheathed", loud=-21.0, max_len=0.45, fade_out=0.08)
def sheathe(v, rng):
    """the knife slid home in its leather sheath: the blade rubbing in, a soft knock as the hilt meets the mouth"""
    dur = 0.45
    m = Mix(dur)
    sd = [0.22, 0.18][v]
    rub = bp(colored(sd, rng, -1.0), 700, 4500) * bell(sd, 0.6, 1.2, 1.0)
    m.add(nrm(rub), 0.0, 0.6)
    m.add(nrm(creak(sd, rng, 200, 500, [1100, 1700, 2600], 0.015)), 0.0, 0.2)
    m.add(nrm(knock(0.08, [900, 1500, 2300], [0.015, 0.01, 0.007], [1, .6, .3], rng, lo=300, hi=6000)), sd - 0.01, 0.5)
    m.add(nrm(thud(0.08, rng, 200, decay=0.008, tone=0.3)), sd - 0.01, 0.3)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=290 + v))


@effect("slap", variants=3, subtitle="Slap on a horse's rump", loud=-15.0, max_len=0.5, fade_out=0.1)
def slap(v, rng):
    """a flat hand brought down hard on the horse's haunch: a big hollow smack on hide"""
    dur = 0.5
    m = Mix(dur)
    m.add(skin(0.2, rng, f_res=[560, 620, 520][v], t60=0.04, bright=0.7, weight=1.6), 0.0, 1.0)
    m.add(nrm(thump(0.2, 140, 80, rng, decay=0.03, click=0.0, noise=0.5, nlp=500)), 0.0, 0.4)
    return lead_in(field(fit(m.out(), ns(dur)), 0.06, seed=300 + v))


@effect("crate_lift", variants=2, subtitle="Crate lifted", loud=-19.0, max_len=0.8, fade_out=0.15)
def crate_lift(v, rng):
    """a crate taken up off the load: dragged a little over the boards, its joints creaking, what's in it shifting"""
    dur = 0.8
    m = Mix(dur)
    m.add(slide(0.3, rng, 350, 2600, 280), 0.0, 0.6)
    m.add(nrm(creak(0.35, rng, 80, 220, [520, 820, 1250, 1850], 0.04)), 0.12, 0.45)
    for _ in range(3):
        at = rng.uniform(0.2, 0.45)
        m.add(nrm(box(0.15, rng, rng.uniform(380, 650), weight=0.1)), at, 0.18)
    bd = 0.25
    m.add(breath(bd, rng, "uh", "uh") * bell(bd, 0.2, 1.0, 1.5), 0.3, [0.08, 0.14][v])
    return lead_in(field(fit(m.out(), ns(dur)), 0.04, seed=310 + v))


@effect("handoff", variants=2, subtitle="Crate handed down", loud=-19.0, max_len=0.6, fade_out=0.12)
def handoff(v, rng):
    """the crate's weight taken into another's arms: a dull knock against the chest, hands slapping round it, what's in
    it settling"""
    dur = 0.6
    m = Mix(dur)
    m.add(nrm(knock(0.25, np.array([1.0, 1.62, 2.41]) * [230, 250][v], [0.03, 0.022, 0.015], [1, .6, .35], rng, lo=150, hi=3000,
                    ex_len=0.004)), 0.0, 0.6)
    m.add(nrm(thud(0.2, rng, 120, decay=0.02, tone=0.3)), 0.0, 0.5)
    m.add(skin(0.1, rng, f_res=1100, t60=0.015, bright=0.3, weight=0.3), 0.02, 0.35)
    m.add(cloth(0.25, rng, peak=0.2, lo=300, hi=2800, grit=250), 0.0, 0.3)
    for _ in range(2):
        m.add(nrm(box(0.12, rng, rng.uniform(420, 700), weight=0.1)), rng.uniform(0.05, 0.15), 0.12)
    return lead_in(field(fit(m.out(), ns(dur)), 0.04, seed=320 + v))


@effect("gasp", variants=3, subtitle="Someone gasps", loud=-19.0, max_len=0.5, fade_out=0.06)
def gasp(v, rng):
    """a frightened gasp: breath snatched in through the open mouth and caught short in the throat (v3 with a little
    cry in it)"""
    dur = 0.5
    m = Mix(dur)
    gd = [0.26, 0.22, 0.3][v]
    t = tvec(gd)
    u = t / gd
    g = breath(gd, rng, "a", "o", scale=[1.0, 1.08, 0.95][v], curve=u) * np.clip(t / 0.02, 0, 1) ** 0.7
    g *= np.clip((gd - t) / 0.008, 0, 1)  # caught: the throat shuts on it
    m.add(nrm(hp(g, 300)), 0.0, 1.0)
    if v == 2:
        vd = 0.12
        m.add(voiced(vd, rng, 260 + 60 * tvec(vd) / vd, "a", "uh", breath_=0.6) * bell(vd, 0.2, 1.0, 1.5), 0.02, 0.35)
    m.add(nrm(bp(white(0.004, rng), 800, 5000) * np.exp(-tvec(0.004) / 0.0008)), gd, 0.15)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=330 + v))


@effect("pant", variants=3, subtitle="Someone pants", loud=-20.0, max_len=1.0, fade_out=0.12)
def pant(v, rng):
    """a heaving breath after a long run: dragged in through the mouth and pushed out again with a 'huh' in it"""
    dur = [0.9, 0.8, 1.0][v]
    m = Mix(dur)
    ind = [0.32, 0.28, 0.36][v]
    t = tvec(ind)
    inhale = breath(ind, rng, "uh", "a", scale=1.0, curve=t / ind) * bell(ind, 0.6, 1.2, 1.0)
    m.add(nrm(hp(inhale, 350)), 0.0, 0.8)
    od = [0.42, 0.38, 0.5][v]
    t = tvec(od)
    exhale = breath(od, rng, "a", "uh", scale=0.98, curve=t / od) * bell(od, 0.15, 0.8, 1.6)
    m.add(nrm(hp(exhale, 250)), ind + 0.04, 1.0)
    vd = 0.1
    m.add(voiced(vd, rng, [135, 150, 125][v] * (1 - 0.15 * tvec(vd) / vd), "uh", "uh", breath_=0.8) * bell(vd, 0.2, 1.0, 1.5),
          ind + 0.05, 0.25)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=340 + v))


def cough_burst(rng, f0, d=0.18):
    """one cough: the glottis bursting open on a held breath, a rough voiced bark falling away"""
    t = tvec(d)
    m = Mix(d)
    m.add(nrm(bp(white(0.03, rng), 600, 5000) * np.exp(-tvec(0.03) / 0.008)), 0.0, 0.9)
    v = voiced(d, rng, f0 * (1 - 0.25 * t / d), "a", "uh", breath_=0.9, jitter=0.08, shimmer=0.35, subharm=0.3)
    m.add(nrm(drive(v, 2.0)) * np.exp(-t / 0.06) * np.clip(t / 0.005, 0, 1), 0.004, 0.8)
    m.add(breath(d, rng, "a", "uh") * np.exp(-t / 0.05), 0.0, 0.5)
    return fit(m.out(), ns(d))


@effect("cough", variants=2, subtitle="Someone coughs", loud=-17.0, max_len=0.9, fade_out=0.12)
def cough(v, rng):
    """dust and a run in the lungs: two rough coughs (v2 three, the last weaker)"""
    dur = [0.7, 0.9][v]
    m = Mix(dur)
    times = [[(0.0, 1.0), (0.24, 0.8)], [(0.0, 1.0), (0.2, 0.85), (0.43, 0.6)]][v]
    for at, g in times:
        m.add(cough_burst(rng, rng.uniform(150, 190)), at, g)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=350 + v))


@effect("smack", variants=3, subtitle="Fist smacks palm", loud=-17.0, max_len=0.35, fade_out=0.06)
def smack(v, rng):
    """a fist driven into an open palm: a meaty smack"""
    dur = 0.35
    m = Mix(dur)
    m.add(skin(0.15, rng, f_res=[950, 1050, 880][v], t60=0.025, bright=0.6, weight=1.3), 0.0, 1.0)
    m.add(nrm(thump(0.1, 180, 110, rng, decay=0.015, click=0.0, noise=0.4, nlp=700)), 0.0, 0.3)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=360 + v))


@effect("swish", variants=3, subtitle="Arm swishes", loud=-23.0, max_len=0.5, fade_out=0.1)
def swish(v, rng):
    """an arm flung out wide: the sleeve's rush through the air and its cloth flapping"""
    dur = 0.5
    m = Mix(dur)
    sd = [0.32, 0.28, 0.38][v]
    m.add(whoosh(sd, rng, peak=0.45, f_lo=250, f_hi=1800, q=1.0, power=2.2), 0.0, 0.8)
    m.add(cloth(sd, rng, peak=0.5, lo=300, hi=3200, grit=500), 0.0, 0.5)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=370 + v))


@effect("stomp", variants=2, subtitle="Foot stamps", loud=-18.0, max_len=0.45, fade_out=0.08)
def stomp(v, rng):
    """a boot stamped down on the road: a thud and a scuff of grit"""
    dur = 0.45
    m = Mix(dur)
    m.add(nrm(thud(0.3, rng, [96, 104][v], decay=0.03, tone=0.45)), 0.0, 1.0)
    sd = 0.1
    m.add(nrm(grit(sd, rng, 6000 * np.exp(-tvec(sd) / 0.03) + 10, lo=400, hi=4000)), 0.002, 0.4)
    m.add(nrm(bp(colored(sd, rng, -1.0), 300, 2500) * bell(sd, 0.2, 1.0, 1.4)), 0.02, 0.25)
    return lead_in(field(fit(m.out(), ns(dur)), 0.04, seed=380 + v))


@effect("wince", variants=3, subtitle="Someone winces", loud=-21.0, max_len=0.7, fade_out=0.1)
def wince(v, rng):
    """the hurt side jarred: breath sucked in hissing through the teeth, a strained grunt held in the throat (v3 a sharp
    'ah' bitten off)"""
    dur = 0.7
    m = Mix(dur)
    if v < 2:
        hd = [0.3, 0.24][v]
        hiss = bp(white(hd, rng), 3500, 9000) * bell(hd, 0.25, 0.8, 1.2) * wander(hd, rng, 14.0, 0.3)
        m.add(nrm(hiss), 0.0, 0.7)
        gd = 0.22
        gt = tvec(gd)
        grunt = voiced(gd, rng, [118, 132][v] * (1 + 0.08 * np.sin(np.pi * gt / gd)), "u", "uh", scale=0.95, breath_=0.5,
                       jitter=0.05, shimmer=0.3, subharm=0.2)
        m.add(nrm(lp(grunt, 1400)) * bell(gd, 0.3, 1.0, 1.4), hd - 0.04, 0.6)
    else:
        vd = 0.16
        vt = tvec(vd)
        cry = voiced(vd, rng, 180 + 50 * np.sin(np.pi * vt / vd), "a", "a", breath_=0.6, jitter=0.05, shimmer=0.25)
        cry *= np.clip(vt / 0.01, 0, 1) * np.clip((vd - vt) / 0.01, 0, 1)
        m.add(nrm(cry), 0.0, 0.8)
        bd = 0.3
        m.add(breath(bd, rng, "uh", "uh") * bell(bd, 0.2, 1.0, 1.4), vd + 0.04, 0.35)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=390 + v))


@effect("huff", variants=2, subtitle="Someone huffs", loud=-20.0, max_len=0.6, fade_out=0.1)
def huff(v, rng):
    """fed up: a 'hmph' through the nose (v2 breath blown out through slack lips)"""
    dur = 0.6
    m = Mix(dur)
    if v == 0:
        vd = 0.16
        vt = tvec(vd)
        hum = voiced(vd, rng, 125 * (1 - 0.12 * vt / vd), "u", "u", scale=0.9, breath_=0.4)
        m.add(nrm(lp(hum, 700)) * np.clip(vt / 0.01, 0, 1) * np.exp(-vt / 0.08), 0.0, 0.7)
        nd = 0.25
        nt = tvec(nd)
        nose = bp(colored(nd, rng, -0.8), 400, 3500) * np.clip(nt / 0.01, 0, 1) * np.exp(-nt / 0.07)
        m.add(nrm(nose), 0.01, 0.8)
    else:
        bd = 0.4
        bt = tvec(bd)
        fl = (0.5 + 0.5 * np.sin(phase_from_freq(32.0 * wander(bd, rng, 6.0, 0.2), len(bt)))) ** 2
        puff = breath(bd, rng, "u", "uh", scale=1.0) * (0.45 + 0.55 * fl) * bell(bd, 0.15, 1.0, 1.4)
        m.add(nrm(hp(puff, 200)), 0.0, 1.0)
    return lead_in(field(fit(m.out(), ns(dur)), 0.03, seed=400 + v))


# =========================================================================== main
def level_loop(x, target, ceiling_db=-1.0):
    """a loop set to its loudness target by gain alone (a limiter's release would show at the seam), held under the
    ceiling by its peak if need be. Returns (y, 0, dB short of target)"""
    def loudness(z):
        return float(np.max(loudness_curve(np.concatenate([z, z]))))
    gain = target - loudness(x)
    peak = 20 * np.log10(true_peak(np.concatenate([x, x])))
    g = min(gain, ceiling_db - 0.2 - peak)
    return x * db(g), 0.0, max(0.0, gain - g)


def build_effect(event, r, v):
    rng = rng_for("caravan", event, v)
    x = r["fn"](v, rng)
    if r["loop"]:
        x = circular(np.asarray(x, dtype=float), lambda z: signal.sosfiltfilt(signal.butter(2, 25.0, "hp", fs=SR, output="sos"), z))
        x = x / (np.max(np.abs(x)) + 1e-12) * db(-3.0)
        return level_loop(x, r["loud"])
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
    x = finalize(x, peak_db=-3.0, fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])
    return level(x, r["loud"], max_limit_db=r["limit"])


def write_fragments():
    """caravan_sounds.json (the sounds.json entries) and caravan_lang.json (their subtitles), for merging by hand"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        names = [f"guildmark:{sound_path(event, v + 1)}" for v in range(r["variants"])]
        entry = [{"name": n, "attenuation_distance": r["attenuation"]} for n in names] if r["attenuation"] else names
        sounds[f"caravan.{event}"] = {"sounds": entry, "subtitle": f"subtitles.guildmark.caravan.{event}"}
        lang[f"subtitles.guildmark.caravan.{event}"] = r["subtitle"]
    for name, data in (("caravan_sounds.json", sounds), ("caravan_lang.json", lang)):
        with open(os.path.join(HERE, name), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


def write_mp3(path, sounds, gap=0.6, loop_laps=2):
    """every sound in a row with a breath between (a loop played round [loop_laps] times), as an MP3"""
    parts = []
    for x, loop in sounds:
        parts += [np.tile(x, loop_laps) if loop else x, np.zeros(ns(gap))]
    y = np.concatenate(parts)
    with tempfile.TemporaryDirectory() as td:
        w = os.path.join(td, "all.wav")
        write_wav(w, y)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", w, "-c:a", "libmp3lame", "-q:a", "3", path], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--mp3", default=None)
    a = ap.parse_args()
    sel = a.only.split(",") if a.only else None
    unknown = [s for s in sel or [] if s not in EVENTS]
    if unknown:
        raise SystemExit(f"no such event: {unknown}")
    made = []
    for event, r in EVENTS.items():
        if sel and event not in sel:
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            name = sound_path(event, v + 1)
            x, over, short = build_effect(event, r, v)
            x = write_checked(os.path.join(a.assets, "sounds", name + ".ogg"), x)
            if a.wav:
                os.makedirs(os.path.join(a.wav, SOUND_DIR), exist_ok=True)
                write_wav(os.path.join(a.wav, name + ".wav"), x)
            made.append((x, r["loop"]))
            note = (f"  limited {over:.1f} dB" if over > 0.05 else "") + (f"  {short:.1f} dB under (set by peak)"
                                                                          if short > 0.05 else "")
            print(f"{name:28s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s{note}", flush=True)
    write_fragments()
    if a.mp3:
        write_mp3(a.mp3, made)
        print("wrote", a.mp3)


if __name__ == "__main__":
    main()
