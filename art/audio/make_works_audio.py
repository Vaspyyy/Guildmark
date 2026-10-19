"""Guildmark works and streets (M6, influence): the sounds of the town's works going up and of its streets in good
times and hard.

usage (from the repo root):
    python3 -B art/audio/make_works_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]
        [--mp3 FILE]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/works/ and <assets>/sounds/streets/ (the event works.trowel
is works/trowel1..6, streets.ladle is streets/ladle1..4), and art/audio/works_sounds.json and works_lang.json: their
sounds.json entries and subtitles, merged into the real sounds.json and en_us.json by hand as the other effects' are.
--only takes event names (works.trowel,streets.bowl); --mp3 joins what was rendered into one listen-through MP3, each
loop played round twice.

- The building: a trowel's stroke through mortar, a mallet on a chisel, mortar turned in its tub, the hoist's rope
  hauled hand over hand, a beam set down, a stone set on its bed and tapped home, a fence post knocked in.
- The ceremonies and the works at work: the topping-out cheer, the cups at the toast, the cloth off the plaque, the
  master's handbell, the watch's horn, the beacon's flare and its fire (a loop), the granary's heavy doors, Marks
  dropped into the post's box at a pledge.
- The streets: the alms table's ladle, its pot simmering (a loop), a bowl taken with a spoon in it, the bread queue's
  murmur (a loop), an awning's flap, an empty basket shaken out, feet stamped against the cold, pats on the back in an
  embrace, a bundle set on a cart, and a handcart heaped with household goods (a loop).
- Round two, what the moments' art wanted: the market board's medallion creaking round to a new face, shaped to its
  turn (works.medallion), and its catch knocking home (works.medallion_catch); a wax seal dropping out on its cord at
  the works' post and swinging against the board (works.seal); the "To let" board creaking on its iron hooks as the
  wind swings it (streets.to_let).

Every one-shot that goes with a clip starts on its contact (the blade's touch, the blow, the foot coming down), as
the homestead's tools do, so a clip's moment plays it on the frame it happens. The four loops (works.beacon_loop,
streets.pot_loop, streets.queue, streets.handcart_goods) are built round, not cut and faded: their noise is made in
the frequency domain to come round on itself, everything that happens in them is laid on a ring (a tail running off
the end comes in again at the start), their filters run three laps with the middle kept and their reverb's tail is
folded back to the start, so each file is one lap of a sound with no seam in it. The queue's murmur is made from the
townsfolk's own recorded talk (sounds/townsfolk/voice), cut at its pauses into syllables and dealt out among a few
voices, muffled, so nothing in it can be made out.

Everything else is synthesised with sfxkit and the townsfolk's and the homestead's helpers, levelled as theirs are
(the loudest 100 ms to a K-weighted target, peaks under -1 dBFS; the loops by gain alone, so their seams stay whole),
and seeded, so a rerun is bit-identical.
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
from sfxkit import *  # noqa: E402,F401,F403
import make_townsfolk_sfx as tf  # noqa: E402
import make_homestead_audio as hs  # noqa: E402
import make_ship_audio as ship  # noqa: E402
from make_townsfolk_sfx import (level, write_checked, true_peak, loudness_curve, nrm, street, ring,  # noqa: E402
                                knock, thud, grains, clods, rustle, creak, bubbles, late, lead_in)

EVENTS = {}  # event ("works.trowel") -> recipe
ARGS = None


def effect(event, variants=1, subtitle="", loud=-16.0, max_len=None, fade_out=0.03, tail_db=-62.0, reach=None,
           limit=2.0, loop=False):
    """registers a sound: loud is the target for its loudest 100 ms (K-weighted LUFS), limit the most dB of peak
    limiting allowed to get there, reach its attenuation distance in blocks when it isn't the game's 16, and loop
    that the file comes round seamlessly on itself"""
    def deco(fn):
        EVENTS[event] = dict(fn=fn, variants=variants, subtitle=subtitle, loud=loud, max_len=max_len,
                             fade_out=fade_out, tail_db=tail_db, reach=reach, limit=limit, loop=loop)
        return fn
    return deco


def sound_path(event, n):
    """'works.trowel', 1 -> 'works/trowel1'"""
    group, name = event.split(".", 1)
    return f"{group}/{name}{n}"


# =========================================================================== loops, built round
class Ring:
    """a mix that comes round on itself: whatever runs off the end comes in again at the start"""

    def __init__(self, dur):
        self.buf = np.zeros(ns(dur))

    def add(self, x, at=0.0, gain=1.0, end_fade=0.006):
        x = np.asarray(x, dtype=np.float64) * gain
        n = len(self.buf)
        if end_fade and 4 < len(x) < n:  # a whole lap has no end to fade
            k = min(len(x) // 2, ns(end_fade))
            x[-k:] *= np.cos(np.linspace(0, np.pi / 2, k)) ** 2
        i = ns(at) % n
        while len(x):
            k = min(len(x), n - i)
            self.buf[i:i + k] += x[:k]
            x, i = x[k:], 0
        return self

    def out(self):
        return self.buf.copy()


def fold(y, n):
    """y wrapped round into n samples: a loop's tails coming in again at its start"""
    out = np.zeros(n)
    for i in range(0, len(y), n):
        seg = y[i:i + n]
        out[:len(seg)] += seg
    return out


def lap(fn, x, *curves):
    """fn (a filter, given x and any per-sample curves) run over a loop as if it went on round: three laps through,
    the middle kept"""
    n = len(x)
    return fn(np.tile(x, 3), *[np.tile(c, 3) for c in curves])[n:2 * n]


def pnoise(n, rng, slope=0.0, lo=None, hi=None):
    """n samples of noise that come round on themselves, tilted by slope dB an octave and rolled off (4th order)
    under lo and over hi: shaped in the frequency domain, so it has no ends"""
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = rng.standard_normal(len(f)) + 1j * rng.standard_normal(len(f))
    g = (np.maximum(f, 20.0) / 1000.0) ** (slope / 6.0206)
    if lo:
        g = g / np.sqrt(1 + (lo / np.maximum(f, 1e-3)) ** 8)
    if hi:
        g = g / np.sqrt(1 + (f / hi) ** 8)
    spec *= g
    spec[0] = 0
    y = np.fft.irfft(spec, n)
    return y / (np.std(y) + 1e-12)


def pwander(n, rng, rate, lo=0.0, hi=1.0):
    """a slowly wandering curve that comes round on itself (nothing in it much faster than rate Hz), set to
    [lo, hi]"""
    f = np.fft.rfftfreq(n, 1 / SR)
    spec = (rng.standard_normal(len(f)) + 1j * rng.standard_normal(len(f))) * np.exp(-(f / rate) ** 2)
    spec[0] = 0
    y = np.fft.irfft(spec, n)
    y = (y - y.min()) / (np.ptp(y) + 1e-12)
    return lo + (hi - lo) * y


def pcrackle(n, rng, rate, decay=0.002, f_lo=800, f_hi=6000, size_var=0.6):
    """sfxkit's crackle, laid on a ring: grains at rate a second (a number or a curve), each a short noisy decay"""
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    idx = np.nonzero(rng.random(n) < rate / SR)[0]
    imp = np.zeros(n)
    imp[idx] = rng.standard_normal(len(idx)) * np.exp(size_var * rng.standard_normal(len(idx)))
    k = max(8, ns(decay * 6))
    kern = np.exp(-np.arange(k) / max(1, ns(decay))) * rng.standard_normal(k)
    y = fold(signal.fftconvolve(imp, kern), n)
    return lap(lambda z: bp(z, f_lo, f_hi), y)


def street_round(x, wet=0.1, seed=7):
    """make_townsfolk_sfx.street for a loop: the same slap off the walls, its tail folded back to the start"""
    ir = room_ir(rt60=0.55, predelay=0.012, hf_damp=0.6, seed=seed, early_span=0.05)
    src = lap(lambda z: hp(z, 120.0), x)
    return x + wet * fold(signal.fftconvolve(src, ir), len(x))


def level_loop(x, target, ceiling_db=-1.0):
    """a loop set to its loudness target by gain alone (a limiter's release would show at the seam), held under the
    ceiling by its peak if need be (as make_caravan_sfx's). Returns (y, 0, dB short of target)"""
    def loudness(z):
        return float(np.max(loudness_curve(np.concatenate([z, z]))))
    gain = target - loudness(x)
    peak = 20 * np.log10(true_peak(np.concatenate([x, x])))
    g = min(gain, ceiling_db - 0.2 - peak)
    return x * db(g), 0.0, max(0.0, gain - g)


# =========================================================================== shared pieces
def tick(rng, lo=1500.0, hi=12000.0, tau=0.0006, dur=0.006):
    """the click of a hard contact"""
    t = tvec(dur)
    return nrm(bp(white(dur, rng), lo, hi) * np.exp(-t / tau) * np.clip(t / 0.0002, 0, 1))


def burst(dur, rng, lo, hi, tau, slope=0.0):
    """a short shaped breath of noise: a slap, a puff, a cloth's snap"""
    t = tvec(dur)
    src = colored(dur, rng, slope) if slope else white(dur, rng)
    return nrm(bp(src, lo, hi) * np.exp(-t / tau) * np.clip(t / 0.0008, 0, 1))


def stone(dur, rng, f0, t60=0.025, lo=300, hi=9000, ex_len=0.0008):
    """dressed stone struck: a short, dull, high knock (its modes close together and quickly damped)"""
    return nrm(knock(dur, f0 * np.array([1.0, 1.47, 2.09, 2.83, 3.71]), t60 * np.array([1.0, 0.8, 0.6, 0.45, 0.3]),
                     [1, .8, .6, .4, .25], rng, lo=lo, hi=hi, ex_len=ex_len))


def steel(dur, rng, f0, t60=0.12, bright=1.0):
    """a steel tool's blade or shank ringing: a free bar's stretched overtones, each a close pair"""
    return nrm(ring(dur, f0 * np.array([1.0, 2.756, 5.404]), t60 * np.array([1.0, 0.6, 0.35]),
                    [1, .5 * bright, .25 * bright], rng, doublet=0.002))


def timber(dur, rng, f0, t60=0.06, ex_len=0.003, lo=80, hi=4000):
    """a length of timber or a plank knocked: its overtones at a free bar's spacing, the wood damping them fast"""
    return nrm(knock(dur, f0 * np.array([1.0, 2.756, 5.404, 8.933]), t60 * np.array([1.0, 0.6, 0.4, 0.25]),
                     [1, .7, .4, .2], rng, lo=lo, hi=hi, ex_len=ex_len))


def wood_box(dur, rng, f0, t60=0.04, hollow=0.3):
    """a wooden box, tub or bowl knocked: its body's modes and the air in it"""
    k = knock(dur, f0 * np.array([1.0, 1.58, 2.31, 3.24, 4.4]), t60 * np.array([1.0, 0.8, 0.6, 0.45, 0.3]),
              [1, .8, .55, .35, .2], rng, lo=120, hi=6000, ex_len=0.002)
    return nrm(comb(k, 1 / (f0 * 1.9), 0.35, hollow) if hollow else k)


def scuff(dur, rng, lo=500.0, hi=4000.0, grit_=1.0):
    """a sole or a bundle dragged a little way over stone: a soft rub with grit crunching in it"""
    s = bell(dur, 0.35, 1.2, 1.5)
    rub = bp(colored(dur, rng, -1.0), lo, hi) * s
    gr = crackle(dur, rng, 2500 * s * grit_ + 20, decay=0.0006, f_lo=lo * 1.5, f_hi=hi * 1.6, size_var=0.8)
    return nrm(nrm(rub) + 0.6 * grit_ * nrm(gr))


def cloth(dur, rng, rate, lo=250.0, hi=3500.0, weave=0.35):
    """heavy cloth on the move (rate, a 0..1 curve, is how fast): a soft hush, its weave crackling faintly"""
    hush = bp(colored(dur, rng, -2.0), lo, hi) * rate ** 1.3 * smooth_random(dur, rng, 18, 0.5, 1.0)
    wv = crackle(dur, rng, 300 * rate + 10, decay=0.0009, f_lo=lo * 2, f_hi=hi, size_var=0.7) * rate
    return nrm(nrm(hush) + weave * nrm(wv))


def flap(rng, size=1.0, crisp=1.0):
    """canvas or heavy cloth snapping taut: a soft, low thwap, the folds crackling as they straighten"""
    m = Mix(0.25)
    m.add(burst(0.05, rng, 180 / size, 3200, 0.008 * size), 0.0, 0.8)
    m.add(nrm(sine_sweep(0.07, 150 / size, 85 / size, 3.0) * np.exp(-tvec(0.07) / (0.016 * size))), 0.0, 0.45)
    m.add(nrm(crackle(0.06, rng, 1500, decay=0.0005, f_lo=1500, f_hi=7000) * np.exp(-tvec(0.06) / 0.018)), 0.002,
          0.3 * crisp)
    return m.out()


def wicker(dur, rng, rate, lo=900.0, hi=6000.0):
    """wicker flexing (rate a 0..1 curve): the withies creaking against each other in dry little crackles, the basket's
    body humming under them"""
    cr = crackle(dur, rng, 2500 * rate + 30, decay=0.0006, f_lo=lo, f_hi=hi, size_var=0.9) * (0.4 + 0.6 * rate)
    body = resonators(cr, [260, 430, 690, 1010], [0.03, 0.025, 0.02, 0.015], [1, .8, .5, .3])
    return nrm(nrm(cr) + 0.25 * nrm(body))


def far_field(x, wet=0.26, seed=9):
    """the open country round a watchtower: a long, soft tail off the slopes"""
    return reverb(x, wet=wet, rt60=1.8, predelay=0.05, hf_damp=0.72, seed=seed, early_span=0.1)


# =========================================================================== building
@effect("works.trowel", 6, "Trowel scrapes", loud=-19.0, max_len=0.85, fade_out=0.12)
def trowel(v, rng):
    """one stroke of a trowel, from the blade's touch: steel through wet mortar, the sand in it grating under the
    blade, the lime's wet smear and its little sucking pops, the course under it knocking faintly and the thin blade
    singing a little over the grit. v1 a scoop off the board, v2 a long spread along the course, v3 a short one, v4 a
    heavy stroke that presses the bed down, v5 the edge drawn along the joint to cut it clean (the blade ringing as it
    lifts off the stone), v6 a quick flick"""
    d = [0.4, 0.58, 0.32, 0.5, 0.44, 0.26][v]
    pk = [0.22, 0.42, 0.3, 0.35, 0.55, 0.2][v]
    grit_ = [0.8, 1.0, 0.9, 0.7, 1.3, 1.0][v]
    wet_ = [1.2, 1.0, 0.9, 1.3, 0.5, 0.8][v]
    k = [1.0, 0.96, 1.05, 0.93, 1.02, 1.08][v]
    dur = d + 0.3
    s = env_points([(0.0, 0.55), (pk * d, 1.0), (d * 0.85, 0.35), (d, 0.0)], d)
    m = Mix(dur)
    gr = crackle(d, rng, 500 + 7000 * s * grit_, decay=0.00035, f_lo=1700, f_hi=9000, size_var=0.9) * s
    m.add(nrm(gr), 0.0, 0.5)
    blade = resonators(gr, np.array([2350, 3870, 5600, 7400]) * k, [0.04, 0.03, 0.022, 0.016], [1, .7, .5, .3])
    m.add(nrm(blade), 0.0, 0.16)
    smear = tvf(colored(d, rng, -1.0), "bp", 650 * k * (0.7 + 0.7 * s), 0.9) * s
    smear *= 0.55 + 0.45 * smooth_random(d, rng, 26)
    m.add(nrm(smear), 0.0, 0.75 * wet_)
    m.add(nrm(bubbles(d, rng, 34 * s * wet_ + 2, 600, 2100, tau=(0.0015, 0.004), rise=0.3, size_var=0.5)), 0.0,
          0.22 * wet_)
    course = knock(0.12, np.array([640, 1010, 1530, 2240]) * k, [0.03, 0.024, 0.018, 0.012], [1, .7, .45, .25], rng,
                   lo=250, hi=5000, ex_len=0.003)
    m.add(nrm(course), 0.0, 0.32)  # the blade landing on the bed
    m.add(thud(0.08, rng, f=190 * k, decay=0.01, tone=0.2), 0.0, 0.3 * wet_)
    m.add(tick(rng, 2000, 10000, 0.0004), 0.0, 0.18)
    if v == 3:  # pressed down: the bed squashing under the flat of the blade
        m.add(thud(0.15, rng, f=130, decay=0.022, tone=0.2), 0.12, 0.55)
        m.add(burst(0.06, rng, 400, 2200, 0.012), 0.12, 0.3)
    if v in (1, 4):  # the edge lifting off the stone, ringing
        m.add(steel(0.3, rng, 3100 * k, t60=0.09, bright=0.6), d - 0.02, [0.1, 0.16][v == 4])
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 8500), wet=0.08, seed=10 + v))


@effect("works.chisel", 6, "Chisel chips stone", loud=-16.5, max_len=0.6, fade_out=0.15, reach=24, limit=4.0)
def chisel(v, rng):
    """a mallet on a chisel, from the blow: the beech mallet's knock on the chisel's head, the steel shank ringing
    briefly, its edge biting the stone with a sharp, gritty crack, the block and the bench under it, and the chips
    flying off and pattering down. v1 a firm blow, v2 a light one, v3 a heavy one that breaks a flake off (it drops
    with a clack), v4 a skimming cut that throws fine chips, v5 a small pitching chisel, ringing higher, v6 a dull
    blow, the mallet landing a little off the head"""
    dur = 0.6
    mf = [700, 760, 640, 720, 820, 600][v]
    cf = [3400, 3650, 3200, 3500, 4300, 3300][v]
    ring_g = [0.11, 0.14, 0.1, 0.09, 0.15, 0.05][v]
    crack = [1.0, 0.55, 1.25, 0.8, 0.9, 0.7][v]
    chips = [9, 5, 12, 18, 8, 6][v]
    sf = [920, 980, 860, 1050, 1000, 900][v]
    m = Mix(dur)
    m.add(nrm(knock(0.12, mf * np.array([1.0, 1.62, 2.35, 3.3]), [0.03, 0.022, 0.016, 0.01], [1, .7, .45, .25], rng,
                    lo=300, hi=6000, ex_len=0.0012)), 0.0, 0.7)  # the mallet's head
    m.add(steel(0.25, rng, cf, t60=0.07), 0.0, ring_g)  # the shank
    ct = tvec(0.03)
    cr = crackle(0.03, rng, 22000 * np.exp(-ct / 0.006), decay=0.00025, f_lo=1500, f_hi=10000, size_var=1.0)
    m.add(nrm(cr), 0.0015, 0.65 * crack)  # the edge biting
    m.add(stone(0.1, rng, sf, t60=0.022), 0.001, 0.5 * crack)  # the block
    m.add(nrm(knock(0.2, [150, 240, 380, 560], [0.06, 0.045, 0.035, 0.025], [1, .7, .45, .25], rng, lo=80, hi=2000,
                    ex_len=0.004)), 0.002, 0.35)  # the bench under it
    m.add(grains(dur, rng, chips, 0.03, [0.06, 0.05, 0.07, 0.09, 0.06, 0.05][v], 2200, 7500,
                 t60=(0.004, 0.012), amp_decay=0.12), 0.0, [0.28, 0.2, 0.3, 0.36, 0.26, 0.22][v])
    if v == 2:  # a flake breaks off and drops on the stone
        m.add(nrm(crackle(0.02, rng, 9000, decay=0.0004, f_lo=900, f_hi=7000, size_var=1.0)), 0.006, 0.4)
        m.add(stone(0.12, rng, 1650, t60=0.03, lo=500), 0.2, 0.36)
        m.add(stone(0.08, rng, 1900, t60=0.02, lo=600), 0.27, 0.16)
    if v == 5:  # off the head: more mallet, less bite
        m.add(thud(0.1, rng, f=210, decay=0.014, tone=0.3), 0.0, 0.4)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 11000), wet=0.1, seed=20 + v))


@effect("works.mortar", 4, "Mortar churns", loud=-19.0, max_len=1.15, fade_out=0.2)
def mortar(v, rng):
    """one turn of the hoe through mortar in its tub, from the blade going in: the thick, wet, gritty churn as it's
    pushed through, lumps of lime and sand sucking at it, the blade scraping the tub's boards under it, and the heap
    flopping over. v1 a long push and the fold, v2 pushed and dragged back along the bottom, v3 a short chop into the
    heap and a fold, v4 a push and the hoe knocking the tub's rim as it lifts"""
    d = [0.62, 0.5, 0.3, 0.55][v]
    dur = d + 0.55
    s = env_points([(0.0, 0.7), (d * 0.3, 1.0), (d * 0.8, 0.6), (d, 0.0)], d)
    m = Mix(dur)
    m.add(thud(0.12, rng, f=150, decay=0.016, tone=0.15), 0.0, 0.6)  # the blade going in
    m.add(burst(0.05, rng, 300, 1800, 0.01), 0.0, 0.45)
    churn = tvf(colored(d, rng, -2.0), "bp", 330 * (1 + 0.8 * s), 1.3) * s
    churn *= (0.4 + 0.6 * smooth_random(d, rng, 13)) ** 1.5
    m.add(nrm(churn), 0.0, 0.9)
    m.add(nrm(crackle(d, rng, 2600 * s + 40, decay=0.0005, f_lo=1200, f_hi=6000, size_var=0.8) * s), 0.0, 0.32)
    m.add(nrm(bubbles(d, rng, 14 * s + 1, 170, 620, tau=(0.006, 0.018), rise=0.2, size_var=0.5)), 0.0, 0.5)
    scrape = crackle(d, rng, 350 * s + 10, decay=0.0008, f_lo=350, f_hi=2600, size_var=0.6)
    tub = resonators(scrape, [182, 305, 468, 690], [0.05, 0.04, 0.03, 0.022], [1, .8, .55, .35])
    m.add(nrm(nrm(tub) + 0.5 * nrm(scrape)) * s, 0.02, 0.3)
    if v == 1:  # dragged back along the bottom
        bd = 0.32
        sb = bell(bd, 0.4, 1.2, 1.4)
        back = crackle(bd, rng, 600 * sb + 10, decay=0.0008, f_lo=350, f_hi=2600, size_var=0.6)
        m.add(nrm(resonators(back, [182, 305, 468, 690], [0.05, 0.04, 0.03, 0.022], [1, .8, .55, .35])) * sb, d + 0.06,
              0.35)
        m.add(nrm(tvf(colored(bd, rng, -2.0), "bp", 380, 1.2) * sb), d + 0.06, 0.45)
    else:  # the heap flopping over
        at = d + [0.02, 0, 0.04, 0.0][v]
        m.add(thud(0.22, rng, f=[105, 0, 115, 100][v], decay=0.03, tone=0.2), at, 0.85)
        m.add(burst(0.08, rng, 450, 2400, 0.014), at, 0.4)
        m.add(nrm(bubbles(0.2, rng, 60 * np.exp(-tvec(0.2) / 0.05), 350, 1200, tau=(0.003, 0.01), rise=0.4)), at + 0.01,
              0.3)
    if v == 3:  # the hoe knocks the rim as it comes up
        m.add(wood_box(0.2, rng, 235, t60=0.05, hollow=0.25), d + 0.3, 0.42)
        m.add(tick(rng, 800, 6000, 0.0008), d + 0.3, 0.15)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.0008, 0, 1)
    return lead_in(street(lp(y, 9000), wet=0.08, seed=30 + v))


@effect("works.hoist", 4, "Hoist rope creaks", loud=-19.0, max_len=1.2, fade_out=0.2, reach=24)
def hoist(v, rng):
    """one pull on the hoist's rope, hand over hand, from the grip: the rope rasping through the palms, its strands
    creaking under the load, the wooden sheave turning on its pin in a dry, rising creak, and the hoist beam groaning
    on the scaffold. v1 a steady pull, v2 a long one, the sheave creaking higher, v3 a short heave, v4 a pull that
    swings the load against the scaffold (a knock)"""
    d = [0.62, 0.8, 0.42, 0.6][v]
    dur = d + 0.45
    s = env_points([(0.0, 0.5), (d * 0.35, 1.0), (d * 0.75, 0.7), (d, 0.0)], d)
    m = Mix(dur)
    m.add(thud(0.08, rng, f=230, decay=0.01, tone=0.1), 0.0, 0.35)  # the hands closing on the rope
    fib = crackle(d, rng, 700 * s + 20, decay=0.0007, f_lo=700, f_hi=4500, size_var=0.8)
    hush = bp(colored(d, rng, -2.0), 900, 4000) * s ** 1.3
    m.add(nrm(nrm(fib) * s + 0.25 * nrm(hush)), 0.0, 0.42)
    m.add(nrm(creak(d, rng, 30, 85, [150, 236, 372, 540], t60=0.06)), 0.0, 0.5)  # the rope under load
    sh = [(0.05, d - 0.05, 60, 150), (0.05, d - 0.02, 80, 210), (0.03, d - 0.03, 70, 160), (0.05, d - 0.05, 55, 140)][v]
    sheave = creak(sh[1] - sh[0], rng, sh[2], sh[3], [540, 880, 1320, 1890], t60=0.045)
    m.add(nrm(sheave), sh[0], [0.38, 0.45, 0.32, 0.36][v])
    m.add(nrm(creak(0.3, rng, 20, 45, [120, 196, 300], t60=0.08)), d * 0.3, 0.3)  # the beam on the scaffold
    if v == 3:
        m.add(hs.pole(rng, 380, 0.1), d + 0.12, 0.45)
        m.add(nrm(creak(0.2, rng, 40, 90, [150, 236, 372], t60=0.05)), d + 0.14, 0.2)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.002, 0, 1)
    return lead_in(street(lp(y, 9000), wet=0.1, seed=40 + v))


def beam_end(rng, f1, ground=1.0, ring_=1.0, crunch=1.0):
    """one end of a heavy beam coming down: the ground's thump, the timber's woody tonk, grit crushed under it"""
    m = Mix(0.8)
    m.add(nrm(thump(0.45, 95, 48, rng, decay=0.06, click=0.03, noise=0.7, nlp=320)), 0.0, 1.0 * ground)
    m.add(timber(0.75, rng, f1, t60=0.22 * ring_, ex_len=0.004, lo=60, hi=3000), 0.001, 0.55 * ring_)
    ct = tvec(0.14)
    m.add(nrm(crackle(0.14, rng, 7000 * np.exp(-ct / 0.03), decay=0.0009, f_lo=250, f_hi=3200, size_var=0.9)), 0.002,
          0.38 * crunch)
    return m.out()


@effect("works.beam_down", 3, "Beam thuds down", loud=-15.0, max_len=1.3, fade_out=0.3, reach=32, limit=3.0)
def beam_down(v, rng):
    """a beam set down off two shoulders, from the first end landing: a heavy thump and the timber's hollow tonk, then
    the other end a moment after; v1 the two ends close together, v2 the second carrier slower, the beam rocking once,
    v3 onto the yard's stack, the timbers knocking together and a little dust of bark"""
    dur = 1.3
    gap = [0.13, 0.3, 0.17][v]
    f1 = [108, 100, 115][v]
    m = Mix(dur)
    if v < 2:
        m.add(beam_end(rng, f1), 0.0, 1.0)
        m.add(beam_end(rng, f1 * 1.03, 0.85, 0.9, 0.8), gap, 0.85)
        if v == 1:
            m.add(timber(0.4, rng, f1 * 1.5, t60=0.08, ex_len=0.003), gap + 0.21, 0.22)
            m.add(nrm(thump(0.2, 90, 60, rng, decay=0.03, click=0.0, noise=0.6, nlp=300)), gap + 0.21, 0.25)
    else:
        m.add(beam_end(rng, f1, 0.6, 1.5, 0.3), 0.0, 1.0)
        m.add(timber(0.6, rng, f1 * 1.31, t60=0.18, ex_len=0.003), 0.004, 0.45)  # the timber under it
        m.add(beam_end(rng, f1 * 0.97, 0.5, 1.4, 0.3), gap, 0.85)
        m.add(timber(0.5, rng, f1 * 1.72, t60=0.12, ex_len=0.003), gap + 0.006, 0.35)
        m.add(grains(dur, rng, 7, 0.03, 0.08, 900, 4000, t60=(0.006, 0.02), amp_decay=0.15), 0.0, 0.16)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 8000), wet=0.12, seed=50 + v))


@effect("works.stone_set", 4, "Stone is set", loud=-17.0, max_len=0.9, fade_out=0.18, reach=20, limit=3.0)
def stone_set(v, rng):
    """a dressed stone set onto its bed, from its landing: the dull, wet thup of it squashing the mortar, then worked
    into place. v1 set and tapped home twice with the trowel's handle, v2 set and shifted on the bed with a grind, v3
    shifted and tapped once, v4 a big stone, set heavily and knocked down with the mallet"""
    dur = 0.9
    f0 = [930, 870, 990, 760][v]
    m = Mix(dur)
    m.add(thud(0.25, rng, f=[130, 125, 140, 105][v], decay=0.026, tone=0.25), 0.0, 0.9)
    m.add(burst(0.05, rng, 300, 2000, 0.01), 0.0, 0.4)  # the bed squashing
    m.add(nrm(bubbles(0.15, rng, 70 * np.exp(-tvec(0.15) / 0.04), 400, 1400, tau=(0.002, 0.006), rise=0.3)), 0.005,
          0.22)
    m.add(stone(0.12, rng, f0, t60=0.02), 0.0, 0.32)
    if v in (1, 2):  # shifted on the bed
        gd = 0.22
        sg = bell(gd, 0.4, 1.2, 1.4)
        gr = crackle(gd, rng, 4500 * sg + 30, decay=0.0005, f_lo=800, f_hi=6500, size_var=0.8)
        m.add(nrm(nrm(gr) + 0.5 * nrm(resonators(gr, [f0, f0 * 1.47, f0 * 2.09], [0.02, 0.016, 0.012]))) * sg, 0.16,
              0.4)
        m.add(nrm(bp(colored(gd, rng, -1.5), 150, 900) * sg), 0.16, 0.3)
    taps = [[0.34, 0.5], [], [0.48], [0.3]][v]
    for i, at in enumerate(taps):
        g = 1.0 if i == 0 else 0.8
        if v == 3:  # the mallet
            m.add(nrm(knock(0.12, [560, 900, 1310], [0.03, 0.022, 0.015], [1, .6, .35], rng, lo=250, hi=5000)), at,
                  0.6)
            m.add(stone(0.1, rng, f0, t60=0.025), at + 0.001, 0.5)
            m.add(thud(0.15, rng, f=110, decay=0.02, tone=0.3), at, 0.45)
        else:  # the trowel's wooden handle
            m.add(nrm(knock(0.06, [1450, 2300, 3300], [0.012, 0.009, 0.006], [1, .6, .3], rng, lo=600, hi=9000,
                            ex_len=0.0006)), at, 0.38 * g)
            m.add(stone(0.08, rng, f0 * 1.04, t60=0.018), at + 0.0005, 0.42 * g)
            m.add(steel(0.15, rng, 2900, t60=0.05, bright=0.4), at, 0.05 * g)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 10000), wet=0.1, seed=60 + v))


@effect("works.stake", 4, "Post knocked in", loud=-15.0, max_len=0.65, fade_out=0.14, reach=24, limit=3.0)
def stake(v, rng):
    """a fence post or the works' post knocked in with a heavy maul, from the blow: a deeper, heavier knock than a
    homestead's stake, the post driven into packed earth with a dull bite and humming a moment after; v2 a blow a
    little off the top, brighter, v4 throwing up a few clods"""
    dur = 0.65
    f0 = [300, 278, 318, 292][v]
    m = Mix(dur)
    m.add(hs.wood_knock(0.3, rng, f0, t60=0.07, ex_len=0.0025, bright=[0.7, 0.6, 1.0, 0.7][v]), 0.0, 1.0)
    m.add(nrm(knock(0.1, [520, 830, 1240], [0.025, 0.018, 0.012], [1, .5, .25], rng, lo=200, hi=4000)), 0.0, 0.3)
    m.add(tick(rng, 1200, 8000, 0.0008), 0.0, [0.2, 0.18, 0.34, 0.2][v])
    m.add(thud(0.35, rng, [80, 76, 86, 82][v], decay=0.04, tone=0.4), 0.001, [0.85, 0.9, 0.6, 0.85][v])
    ct = tvec(0.14)
    m.add(nrm(crackle(0.14, rng, 5500 * np.exp(-ct / 0.03), decay=0.001, f_lo=220, f_hi=2800, size_var=0.9)), 0.003,
          0.38)
    fb = [158, 150, 170, 162][v]  # the post's sway, standing out of the ground
    m.add(nrm(modal(0.45, [fb, fb * 2.76], [0.28, 0.12], [1, .3], rng, attack=0.004)), 0.004, 0.14)
    if v == 3:
        m.add(clods(dur, rng, 5, start=0.05, spread=0.07), 0.0, 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=70 + v))


# =========================================================================== the ceremonies
@effect("works.cheer", 3, "Crowd cheers", loud=-14.0, max_len=3.2, fade_out=0.6, reach=48, limit=3.0)
def cheer(v, rng):
    """the topping-out, the master's call answered: the crew and the townsfolk who have come over to watch cheering
    together, ragged, and clapping. v1 'hurrah!', v2 'hurrah! hurrah!', v3 'hey!' and 'hurrah!' at once and a
    whistle"""
    dur = 3.2
    m = Mix(dur)
    if v == 0:
        m.add(hs.crowd(dur, rng, [hs.HURRAH], n=8, start=0.0, spread=0.16, women=0.4), 0.0, 1.0)
        m.add(hs.claps(dur, rng, 18, 0.45, 1.5), 0.0, 0.42)
    elif v == 1:
        m.add(hs.crowd(dur, rng, [hs.HURRAH], n=7, start=0.0, spread=0.12, women=0.4), 0.0, 1.0)
        m.add(hs.crowd(dur, rng, [hs.HURRAH], n=8, start=0.78, spread=0.14, women=0.4, rise=1.42), 0.0, 0.95)
        m.add(hs.claps(dur, rng, 16, 1.25, 1.3), 0.0, 0.42)
    else:
        m.add(hs.crowd(dur, rng, [hs.HEY, hs.HURRAH], n=9, start=0.0, spread=0.18, women=0.45), 0.0, 1.0)
        m.add(hs.whistle(0.6, rng, 2300, 1.27), 0.55, 0.16)
        m.add(hs.claps(dur, rng, 20, 0.4, 1.6), 0.0, 0.45)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.15, seed=80 + v))


def slop(dur, rng, rate=6.5):
    """ale slopping in a cup after it's knocked: the wave slapping the walls a few times, dying away (a softer
    make_townsfolk_sfx.slosh, with no bubbles in it)"""
    t = tvec(dur)
    wave = (0.5 + 0.5 * np.sin(phase_from_freq(rate * (1 + 0.2 * (2 * smooth_random(dur, rng, 6) - 1)), len(t)) - 1.2)) ** 2
    env = np.clip(t / 0.03, 0, 1) * np.exp(-t / (dur * 0.28))
    return nrm(lp(bp(colored(dur, rng, -1.5), 250, 1400) * wave * env, 1600))


def cup(dur, rng, kind, f0):
    """a drinking cup knocked: a turned wooden one knocks hollow, a pewter one rings dull and short"""
    return nrm(tf.tankard(dur, rng, kind, f0))


@effect("works.toast", 4, "Cups clink", loud=-16.0, max_len=0.9, fade_out=0.2, reach=20, limit=3.0)
def toast(v, rng):
    """the toast, from the first cups meeting: the crew's cups knocked together over the master's, a cluster of
    clinks, and the ale slopping in them. v1 three wooden cups, v2 wood and pewter, v3 five cups in a ragged cluster,
    v4 two cups knocked firmly"""
    dur = 0.9
    plans = [
        [(0.0, "wood", 470, "wood", 520), (0.05, "wood", 430, "wood", 505), (0.12, "wood", 455, "wood", 540)],
        [(0.0, "pewter", 1240, "wood", 480), (0.04, "wood", 445, "wood", 500), (0.1, "pewter", 1320, "pewter", 1180),
         (0.17, "wood", 470, "pewter", 1260)],
        [(0.0, "wood", 460, "wood", 515), (0.03, "pewter", 1290, "wood", 470), (0.08, "wood", 440, "wood", 495),
         (0.13, "pewter", 1210, "pewter", 1350), (0.19, "wood", 480, "wood", 530), (0.27, "wood", 450, "pewter", 1270)],
        [(0.0, "wood", 455, "wood", 500)],
    ][v]
    m = Mix(dur)
    for i, (at, ka, fa, kb, fb) in enumerate(plans):
        g = rng.uniform(0.75, 1.0) * (1.0 if i == 0 else 0.85)
        m.add(cup(0.5, rng, ka, fa * rng.uniform(0.98, 1.02)), at, 0.8 * g)
        m.add(cup(0.5, rng, kb, fb * rng.uniform(0.98, 1.02)), at + 0.0004, 0.7 * g)
        lo, hi = (1400, 12000) if "pewter" in (ka, kb) else (700, 6000)
        m.add(nrm(bp(white(0.004, rng), lo, hi) * np.exp(-tvec(0.004) / 0.0006)), at, 0.25 * g)
        m.add(nrm(thump(0.1, 230, 150, rng, decay=0.012, click=0.0, noise=0.4, nlp=900)), at, 0.25 * g)
    m.add(slop(0.45, rng, rate=6.5), 0.04, [0.12, 0.11, 0.14, 0.16][v])
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=90 + v))


def whisk(dur, rng, peak=0.5, lo=350.0, hi=4200.0, flutter=22.0):
    """cloth swept through the air: a swish rising and falling with its speed, the cloth fluttering faster as it
    flies, its weave crackling"""
    s = bell(dur, peak, 1.4, 1.5)
    fc = lo * (hi / lo) ** (0.25 + 0.6 * s)
    sw = tvf(colored(dur, rng, -2.0), "bp", fc, 0.8) * s ** 1.4
    fl = 1 - 0.55 * 0.5 * (1 + np.sin(phase_from_freq(flutter * (0.5 + 0.8 * s), ns(dur))))
    wv = crackle(dur, rng, 700 * s + 20, decay=0.0006, f_lo=900, f_hi=6000, size_var=0.8) * s
    return nrm(nrm(sw * fl) + 0.35 * nrm(wv))


@effect("works.unveil", 3, "Cloth whisked away", loud=-16.0, max_len=1.1, fade_out=0.25, reach=24)
def unveil(v, rng):
    """the cloth pulled off the patron's plaque with a flourish, from the first tug: v1 one sweeping pull, the cloth
    flying off and snapping taut in the hand, v2 a tug, the cloth sliding off the bronze, then swept away, v3 swept off
    and shaken out, flapping twice"""
    dur = 1.1
    m = Mix(dur)
    m.add(burst(0.03, rng, 300, 3000, 0.006), 0.0, 0.3)  # the hand closing on it
    if v == 0:
        m.add(whisk(0.55, rng, 0.45), 0.0, 1.0)
        m.add(flap(rng, 1.0), 0.52, 0.75)
    elif v == 1:
        rd = 0.2
        rate = bell(rd, 0.5, 1.2, 1.2)
        m.add(cloth(rd, rng, rate, 400, 4500, 0.6), 0.0, 0.55)  # sliding off the bronze
        m.add(nrm(resonators(crackle(rd, rng, 200 * rate + 5, decay=0.0005, f_lo=1500, f_hi=6000),
                             [1870, 3150, 4720], [0.08, 0.06, 0.04])) * rate, 0.0, 0.06)  # the plate humming faintly
        m.add(whisk(0.5, rng, 0.4, flutter=25.0), 0.16, 1.0)
        m.add(flap(rng, 0.9, 0.8), 0.62, 0.6)
    else:
        m.add(whisk(0.45, rng, 0.4), 0.0, 1.0)
        m.add(flap(rng, 1.1), 0.42, 0.7)
        m.add(whisk(0.22, rng, 0.5, 500, 3500, 30.0), 0.55, 0.45)
        m.add(flap(rng, 1.0, 0.7), 0.74, 0.5)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 7500), wet=0.1, seed=100 + v))


MASTER_BELL = 784.0  # the master builder's handbell: bigger and lower than the homestead crew's


@effect("works.handbell", 3, "Handbell rings", loud=-15.0, max_len=3.2, fade_out=0.9, reach=48)
def handbell(v, rng):
    """the master builder's handbell, rung to open the works: a bigger, lower bell than the homestead crew's, swung
    briskly back and forth so the clapper strikes on both sides, then let ring. v1 eight quick strokes, v2 six and
    a longer ring, v3 two bursts of four"""
    dur = 3.2
    plan = [
        [(0.0, 1.0), (0.2, 0.74), (0.4, 0.96), (0.6, 0.72), (0.8, 0.92), (1.0, 0.7), (1.2, 0.88), (1.4, 0.64)],
        [(0.0, 1.0), (0.25, 0.78), (0.5, 0.95), (0.75, 0.76), (1.0, 0.9), (1.25, 0.7)],
        [(0.0, 0.95), (0.18, 0.7), (0.36, 0.9), (0.54, 0.62), (0.92, 1.0), (1.1, 0.72), (1.28, 0.9), (1.46, 0.6)],
    ][v]
    plan = [(at + (rng.uniform(-0.018, 0.018) if at else 0.0), g * rng.uniform(0.92, 1.05)) for at, g in plan]
    y = hs.ring_bell(dur, rng, plan, f0=MASTER_BELL * rng.uniform(0.998, 1.002))
    return lead_in(street(y, wet=0.18, seed=110 + v))


def horn_spectrum(f):
    """the watch's horn (a cow's horn, end-blown): what each harmonic radiates, in dB. Its small bell lets little of
    the fundamental out, the bore sings round a kilohertz, there's a nasal lift near 2.6 kHz and it falls away
    above"""
    lf = np.log2(np.maximum(f, 1.0) / 1000.0)
    e = -6.0 * lf ** 2 + 4.0 * np.exp(-((np.log2(np.maximum(f, 1.0) / 2600.0)) / 0.25) ** 2)
    e -= 8.0 * np.clip((300.0 - f) / 150.0, 0, 1)
    e -= 14.0 * np.clip(np.log2(np.maximum(f, 1.0) / 3800.0), 0, None)
    return e


def animal_horn(dur, rng, f0, attack=0.08, release=0.28, scoop=0.07, crack=None):
    """one call on an animal horn: the lips buzzing into it, the note found from a little under, wavering with the
    breath, brighter the harder it's blown (each harmonic comes up faster than the one under it), a rasp of the lips
    in it and breath round it, and the pitch sagging as the breath runs out. crack: (time, length) the lips jump to
    the overblown note and back"""
    n = ns(dur)
    t = tvec(dur)
    sag = np.clip((t - (dur - release * 1.4)) / (release * 1.4), 0, 1) ** 2
    wav = 1 + 0.004 * (2 * smooth_random(dur, rng, 4) - 1) + 0.0025 * np.sin(2 * np.pi * 5.3 * t + rng.uniform(0, 6.3))
    f = f0 * (1 - scoop * np.exp(-t / 0.045)) * wav * (1 - 0.05 * sag)
    pres = env_points([(0.0, 0.0), (attack, 0.82), (attack + 0.2, 1.0), (dur - release, 0.92), (dur, 0.0)], dur)
    pres = pres * (0.94 + 0.06 * smooth_random(dur, rng, 7))
    over = np.zeros(n)
    if crack:
        c0, cl = crack
        over = np.clip(np.minimum((t - c0) / 0.02, (c0 + cl - t) / 0.03), 0, 1)
    ph = phase_from_freq(f)
    y = np.zeros(n)
    for k in range(1, 40):
        fk = k * f0
        if fk > 8500:
            break
        a = db(horn_spectrum(fk)) * pres ** (1.0 + 0.22 * (k - 1))
        if crack:  # the overblown note: the even harmonics of the octave up take over a moment
            a = a * (1 - 0.85 * over) + (db(horn_spectrum(fk)) * 1.2 * over if k % 2 == 0 else 0)
        y += a * np.sin(k * ph + rng.uniform(0, 2 * np.pi))
    y = nrm(y)
    rasp = bp(white(dur, rng), 700, 3500) * (0.5 + 0.5 * np.cos(ph)) ** 6 * pres ** 2
    breath = bp(white(dur, rng), 1500, 6000) * pres
    tongue = burst(0.035, rng, 800, 4500, 0.008)
    m = Mix(dur)
    m.add(y, 0.0, 1.0)
    m.add(nrm(rasp), 0.0, 0.1)
    m.add(nrm(breath), 0.0, 0.035)
    m.add(tongue, 0.0, 0.22)
    return drive(nrm(fit(m.out(), n)), 1.5)


HORN_F0 = 226.0


@effect("works.horn", 3, "Watch horn sounds", loud=-14.0, max_len=4.4, fade_out=1.0, reach=128, limit=3.0)
def horn(v, rng):
    """the watch's horn from the tower when riders are seen on the road: a rough, reedy animal horn, not the
    factions' brass. v1 one long call, v2 a short call and a long one, v3 a long call whose top cracks up to the
    overblown note and back, as a hurried blower's does"""
    dur = 4.4
    f0 = HORN_F0 * rng.uniform(0.995, 1.005)
    m = Mix(dur)
    if v == 0:
        m.add(animal_horn(2.3, rng, f0), 0.0, 1.0)
    elif v == 1:
        m.add(animal_horn(0.55, rng, f0, attack=0.06, release=0.16), 0.0, 0.9)
        m.add(animal_horn(1.7, rng, f0, scoop=0.05), 0.75, 1.0)
    else:
        m.add(animal_horn(2.2, rng, f0, scoop=0.09, crack=(0.95, 0.16)), 0.0, 1.0)
    y = fit(m.out(), ns(dur))
    return lead_in(far_field(hp(y, 120), wet=0.3, seed=120 + v))


def flame(dur, rng, s, lo=180.0, hi=1400.0, crackles=260.0):
    """an open fire breathing with the swell s (0..1 per sample): make_townsfolk_sfx.fire's roar, rush and crackle,
    mixed for a brazier in the open"""
    roar, rush, crk = tf.fire(dur, rng, s, lo=lo, hi=hi, crackles=crackles)
    return nrm(0.85 * roar + 0.55 * rush + 0.3 * crk)


@effect("works.beacon_flare", 3, "Beacon flares", loud=-14.0, max_len=2.6, fade_out=0.8, reach=48, limit=3.0)
def beacon_flare(v, rng):
    """the beacon catching, from the torch going in: a soft, deep whoomph as the pitch-soaked kindling takes all at
    once, the flames roaring up and leaning in a gust, then settling to a crackling burn. v1 a clean flare, v2 a bigger
    one spitting sparks and snapping, v3 the torch's resin hissing as it goes in, then the whoomph"""
    dur = 2.6
    t0 = 0.05 if v == 2 else 0.0
    m = Mix(dur)
    if v == 2:
        hd = 0.12
        m.add(nrm(bp(white(hd, rng), 2500, 9000) * bell(hd, 0.4, 1.0, 1.2)), 0.0, 0.25)
    wd = 0.5
    wt = tvec(wd)
    whoomph = lp(brown(wd, rng), 240, order=4) * np.clip(wt / 0.035, 0, 1) ** 1.5 * np.exp(-wt / 0.12)
    m.add(nrm(whoomph), t0, 1.0)
    m.add(nrm(sine_sweep(wd, 85, 42, 3.0) * np.clip(wt / 0.03, 0, 1) * np.exp(-wt / 0.1)), t0, 0.5)
    m.add(whoosh(0.7, rng, peak=0.22, f_lo=220, f_hi=1700, q=0.9, power=2.0, color=-2.0, doppler=0.0), t0, 0.55)
    fd = dur - t0
    sf = env_points([(0.0, 0.0), (0.12, 1.0), (0.5, 0.75), (1.3, 0.5), (fd, 0.38)], fd) * (
        0.85 + 0.15 * smooth_random(fd, rng, 6))
    m.add(flame(fd, rng, sf, crackles=[260, 420, 300][v]), t0, 0.75)
    if v == 1:  # the sparks spitting, a knot snapping
        for at in sorted(rng.uniform(0.25, 1.6, 7)):
            m.add(nrm(crackle(0.03, rng, 6000, decay=0.0006, f_lo=1200, f_hi=9000, size_var=1.0) *
                      np.exp(-tvec(0.03) / 0.006)), t0 + at, rng.uniform(0.25, 0.55))
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 10000), wet=0.12, seed=130 + v))


@effect("works.beacon_loop", 2, "Beacon burns", loud=-25.0, reach=24, loop=True)
def beacon_loop(v, rng):
    """the beacon burning in its brazier on the tower (a loop): a steady low roar breathing as the flames lean in the
    wind, their rush, wood crackling and spitting, now and then a knot snapping, sap hissing or the coals settling
    with a chink"""
    dur = [6.4, 7.3][v]
    n = ns(dur)
    s = pwander(n, rng, 2.2, 0.55, 1.0) * (0.8 + 0.2 * pwander(n, rng, 9.0))
    m = Ring(dur)
    roar = pnoise(n, rng, -6.0, lo=30, hi=200) * s ** 1.2
    m.add(nrm(roar), 0.0, 0.85)
    rush = lap(lambda z, fc: tvf(z, "bp", fc, 0.7), pnoise(n, rng, -3.0), 180 * (1400 / 180) ** (s - 0.3)) * s ** 1.5
    m.add(nrm(rush), 0.0, 0.45)
    crk = pcrackle(n, rng, 40 + 260 * s, decay=0.0009, f_lo=1400, f_hi=8000) * (0.3 + 0.7 * s)
    m.add(nrm(crk), 0.0, 0.28)
    for at in rng.uniform(0, dur, [5, 6][v]):  # a knot snapping
        m.add(nrm(crackle(0.03, rng, 7000, decay=0.0006, f_lo=1000, f_hi=8000, size_var=1.0) *
                  np.exp(-tvec(0.03) / 0.006)), at, rng.uniform(0.3, 0.6))
    for at in rng.uniform(0, dur, 2):  # sap hissing out of a log
        hd = rng.uniform(0.25, 0.45)
        m.add(nrm(bp(white(hd, rng), 3000, 8000) * bell(hd, 0.3, 1.2, 1.4) * smooth_random(hd, rng, 30, 0.4, 1.0)),
              at, 0.12)
    for at in rng.uniform(0, dur, 2):  # the coals settling
        m.add(grains(0.3, rng, 4, 0.0, 0.05, 1800, 4200, t60=(0.01, 0.03), amp_decay=0.08), at, 0.22)
        m.add(nrm(thump(0.12, 110, 70, rng, decay=0.02, click=0.0, noise=0.6, nlp=400)), at, 0.18)
    y = lap(lambda z: lp(z, 9000), m.out())
    return street_round(y, wet=0.1, seed=140 + v)


def hinge_groan(dur, rng, rate0, rate1, f=1.0):
    """a heavy door's strap hinges turning on their pintles: iron dragging on iron in a stick-slip groan that wavers
    with the door's swing, sounding through the door's boards"""
    t = tvec(dur)
    rate = (rate0 + (rate1 - rate0) * np.sin(np.pi * t / dur * 0.8)) * (0.85 + 0.3 * smooth_random(dur, rng, 5))
    fr = crackle(dur, rng, rate, decay=0.0007, f_lo=120, f_hi=3500, size_var=0.3)
    modes = np.array([150, 248, 395, 610, 930]) * f
    y = resonators(fr, modes, 0.08 * 0.8 ** np.arange(5), 0.75 ** np.arange(5))
    iron = resonators(fr, [1180 * f, 1910 * f], [0.03, 0.02], [1, .5])
    return nrm(nrm(y) + 0.25 * nrm(iron)) * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.6


def door_boom(rng, f=1.0, weight=1.0):
    """a heavy plank door coming against its frame or its stop: a deep boom, the boards knocking and the iron ring on
    it rattling"""
    m = Mix(0.9)
    m.add(nrm(thump(0.5, 88 * f, 50 * f, rng, decay=0.09, click=0.04, noise=0.7, nlp=320)), 0.0, 1.0 * weight)
    m.add(nrm(knock(0.6, np.array([118, 206, 330, 470, 650]) * f, [0.14, 0.1, 0.07, 0.05, 0.035], [1, .8, .55, .35, .2],
                    rng, lo=70, hi=3000, ex_len=0.004)), 0.001, 0.7)
    for at, g in ((0.015, 0.2), (0.09, 0.12)):
        m.add(nrm(ring(0.2, [1720, 2830, 4110], [0.12, 0.08, 0.05], [1, .6, .35], rng, doublet=0.002)), at, g)
    return m.out()


@effect("works.granary_doors", 3, "Granary doors creak", loud=-16.0, max_len=2.3, fade_out=0.5, reach=32, limit=3.0)
def granary_doors(v, rng):
    """the granary's heavy double doors swung open or shut (one sound for both): v1 one leaf, its strap hinges
    groaning, then the boom against its frame or its stop; v2 both leaves, one a moment behind the other; v3 the
    bar lifted out of its irons first with a scrape and a clunk"""
    dur = 2.3
    m = Mix(dur)
    if v == 0:
        m.add(hinge_groan(1.0, rng, 70, 150), 0.0, 0.6)
        m.add(door_boom(rng), 1.0, 1.0)
    elif v == 1:
        m.add(hinge_groan(0.95, rng, 75, 160), 0.0, 0.55)
        m.add(hinge_groan(0.9, rng, 60, 135, 0.92), 0.22, 0.5)
        m.add(door_boom(rng, 1.0, 0.9), 0.95, 0.9)
        m.add(door_boom(rng, 0.94, 0.85), 1.14, 0.85)
    else:
        bd = 0.26
        m.add(scuff(bd, rng, 300, 2600, 0.7), 0.0, 0.45)  # the bar sliding in its irons
        m.add(nrm(ring(0.2, [1500, 2440], [0.08, 0.05], [1, .5], rng)), 0.02, 0.08)
        m.add(wood_box(0.3, rng, 190, t60=0.06, hollow=0.0), bd, 0.7)  # set down against the wall
        m.add(thud(0.2, rng, f=120, decay=0.02, tone=0.3), bd, 0.5)
        m.add(hinge_groan(0.9, rng, 72, 155, 1.05), 0.45, 0.55)
        m.add(door_boom(rng, 1.03), 1.35, 1.0)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 8000), wet=0.14, seed=150 + v))


def coin_in_box(rng, f0, gain=1.0, on_coins=True):
    """a Mark falling into the post's box: its ring, cut short where it lands on the coins already there, and the
    box's own hollow knock"""
    m = Mix(0.4)
    m.add(tf.coin(0.35, rng, f0=f0, t60=0.1 if on_coins else 0.05, bright=0.65, click=0.4), 0.0, gain)
    if on_coins:
        m.add(tf.coin(0.2, rng, f0=f0 * rng.uniform(0.88, 1.12), t60=0.05, bright=0.4, click=0.2), 0.008, 0.35 * gain)
    m.add(wood_box(0.15, rng, 470 * rng.uniform(0.97, 1.03), t60=0.03, hollow=0.4), 0.0, 0.35 * gain)
    return m.out()


@effect("works.pledge", 4, "Marks drop in the box", loud=-17.0, max_len=1.0, fade_out=0.2, reach=12, limit=3.0)
def pledge(v, rng):
    """a pledge dropped into the box on the works' post, from the first coin: the Marks slipping through the slot
    and landing on the ones inside, the box knocking hollow under them. v1 three coins, v2 a little handful in a rush,
    v3 one coin and then two, v4 a purseful poured in"""
    dur = 1.0
    plans = [[0.0, 0.1, 0.21], list(np.cumsum([0.0, 0.03, 0.025, 0.04, 0.02, 0.035])), [0.0, 0.32, 0.4],
             list(np.cumsum([0.0, 0.03, 0.02, 0.025, 0.02, 0.03, 0.02, 0.035, 0.03, 0.04, 0.05]))][v]
    m = Mix(dur)
    m.add(nrm(bp(white(0.025, rng), 3000, 9000) * bell(0.025, 0.3, 1.0, 1.5)), 0.0, 0.12)  # through the slot
    for i, at in enumerate(plans):
        m.add(coin_in_box(rng, rng.uniform(2900, 4000), gain=rng.uniform(0.6, 1.0), on_coins=i > 0 or v != 2), at,
              1.0 if i == 0 else 0.8)
    if v in (1, 3):
        m.add(jingle(0.4, rng, [6, 10][v == 3], 3000, 8000, t60=(0.01, 0.04), start=0.04, amp_decay=0.15), 0.0, 0.2)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.06, seed=160 + v))


# =========================================================================== the streets: the alms table
def pot_rim(dur, rng, f=1.0, damp=1.0):
    """the iron pot's rim knocked: a dull, short iron ring (the soup in it damps it)"""
    return nrm(ring(dur, np.array([640, 1130, 1720, 2490]) * f, np.array([0.18, 0.12, 0.08, 0.05]) / damp,
                    [1, .7, .45, .25], rng, doublet=0.003))


def pour(dur, rng, fill_from=520.0, fill_to=820.0):
    """soup poured from the ladle into a wooden bowl: a thick little stream, gurgling, its pitch rising as the bowl
    fills"""
    t = tvec(dur)
    s = bell(dur, 0.2, 1.2, 1.3)
    fc = fill_from * (fill_to / fill_from) ** (t / dur)
    stream = tvf(colored(dur, rng, -1.0), "bp", fc, 2.5) * s * (0.5 + 0.5 * smooth_random(dur, rng, 35))
    gl = bubbles(dur, rng, 90 * s + 5, 380, 1100, tau=(0.003, 0.009), rise=0.5, size_var=0.5)
    return nrm(nrm(stream) + 0.7 * nrm(gl))


@effect("streets.ladle", 4, "Soup ladled", loud=-18.0, max_len=1.3, fade_out=0.25)
def ladle(v, rng):
    """a ladleful served at the alms table, from the dip: the ladle going into the thick soup with a deep glug, lifted
    out dripping and poured into the held bowl. v1 dip, lift and pour, v2 knocked on the pot's rim before the pour, v3
    scraped along the pot's bottom for the last of it, v4 poured and tapped on the bowl's rim to shake it"""
    dur = 1.3
    m = Mix(dur)
    m.add(tf.plop(0.25, rng, f0=[330, 360, 300, 340][v], size=2.0, fizz=6.0), 0.0, 1.0)
    m.add(nrm(tvf(colored(0.2, rng, -1.5), "bp", 420, 1.2) * bell(0.2, 0.2, 1.0, 1.5)), 0.0, 0.5)
    at = 0.28
    if v == 2:  # the bottom of the pot
        sd = 0.28
        ss = bell(sd, 0.4, 1.2, 1.4)
        gr = crackle(sd, rng, 1600 * ss + 20, decay=0.0005, f_lo=600, f_hi=4000, size_var=0.6)
        m.add(nrm(resonators(gr, [640, 1130, 1720], [0.05, 0.035, 0.025])) * ss, 0.12, 0.3)
        at = 0.45
    for k in range(3):  # drips off the ladle
        m.add(tf.plop(0.08, rng, f0=rng.uniform(1100, 1800), size=0.5, fizz=0.0), at + 0.03 + 0.06 * k, 0.12)
    if v == 1:
        m.add(pot_rim(0.3, rng, damp=1.5), at, 0.3)
        m.add(tick(rng, 1500, 9000, 0.0005), at, 0.12)
        at += 0.16
    pd = [0.34, 0.3, 0.32, 0.36][v]
    m.add(pour(pd, rng), at + 0.08, 0.75)
    bowl = knock(0.2, [540, 905, 1350], [0.04, 0.03, 0.02], [1, .6, .35], rng, lo=200, hi=5000, ex_len=0.004)
    m.add(nrm(bowl), at + 0.09, 0.2)
    if v == 3:  # tapped on the bowl's rim
        m.add(nrm(knock(0.12, [620, 1040, 1560], [0.03, 0.022, 0.015], [1, .6, .3], rng, lo=300, hi=6000)),
              at + pd + 0.16, 0.4)
        m.add(pot_rim(0.15, rng, f=1.6, damp=2.0), at + pd + 0.16, 0.12)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 9000), wet=0.08, seed=170 + v))


def bubble(rng, f, tau, rise=0.3, skin=0.0):
    """one bubble in a thick liquid: its ring as it forms (a damped tone whose pitch climbs as it shrinks), and with
    skin the soft noisy pop of it breaking at the top"""
    L = tau * 7 + 0.01
    bt = tvec(L)
    y = np.sin(phase_from_freq(f * (1 + rise * bt / (tau * 4)), len(bt))) * np.exp(-bt / tau) * np.clip(bt / 0.0015, 0, 1)
    if skin:
        pd = 0.025
        pop = lp(bp(white(pd, rng), 300, 2400) * np.exp(-tvec(pd) / 0.005) * np.clip(tvec(pd) / 0.001, 0, 1), 2000)
        y = y + skin * fit(late(nrm(pop), tau * 2), len(y))
    return y


@effect("streets.pot_loop", 2, "Pot bubbles", loud=-28.0, reach=12, loop=True)
def pot_loop(v, rng):
    """the alms table's pot of soup kept on the simmer (a loop): a steady low seethe, bubbles ticking up through it,
    every so often a slow, fat blup heaving up through the thick of it and breaking, the simmer coming in gentle waves,
    and a breath of steam"""
    dur = [5.9, 6.7][v]
    n = ns(dur)
    m = Ring(dur)
    pulse = pwander(n, rng, 0.7, 0.55, 1.0)  # the simmer coming in slow waves

    def busy(at):
        return pulse[ns(at) % n]
    seethe = pcrackle(n, rng, 110 * pulse, decay=0.0005, f_lo=1100, f_hi=4500, size_var=0.5) * pulse
    m.add(nrm(seethe), 0.0, 0.16)
    k = 0.0
    while True:  # bubbles ticking up
        k += rng.exponential(1.0 / 13.0)
        if k >= dur:
            break
        if rng.random() < busy(k):
            f = np.exp(rng.uniform(np.log(380), np.log(950)))
            m.add(bubble(rng, f, rng.uniform(0.003, 0.007), 0.4), k, rng.uniform(0.12, 0.3))
    k = rng.uniform(0, 0.3)
    while True:  # the fat ones
        k += rng.uniform(0.25, 0.75) / busy(k)
        if k >= dur:
            break
        f = np.exp(rng.uniform(np.log(140), np.log(290)))
        g = rng.uniform(0.45, 0.9)
        m.add(lp(bubble(rng, f, rng.uniform(0.011, 0.02), 0.25, skin=0.5), 1400), k, g)
        m.add(nrm(knock(0.3, [312, 528, 805], [0.12, 0.08, 0.05], [1, .5, .25], rng, lo=150, hi=2000,
                        ex_len=0.004)), k, 0.025 * g)  # the pot humming under it
    m.add(nrm(pnoise(n, rng, -1.0, lo=2500, hi=8000) * pwander(n, rng, 1.2, 0.3, 1.0)), 0.0, 0.025)  # steam
    return street_round(lap(lambda z: lp(z, 7500), m.out()), wet=0.06, seed=180 + v)


@effect("streets.bowl", 4, "Spoon clacks in a bowl", loud=-20.0, max_len=0.75, fade_out=0.15, reach=12)
def bowl(v, rng):
    """a wooden bowl with a horn spoon in it, from the first touch: v1 taken up off the table, the spoon rattling in
    it and the soup slopping, v2 the spoon stirred round the bowl and knocked on its side, v3 a spoonful lifted, the
    spoon clacking on the rim, v4 the bowl held in both hands, the spoon knocking twice as it's settled"""
    dur = 0.75
    m = Mix(dur)
    bf = [560, 590, 540, 575][v]
    hit = lambda at, g, f=None: m.add(nrm(knock(0.1, (f or rng.uniform(1150, 1700)) * np.array([1.0, 1.7, 2.6]),
                                               [0.016, 0.011, 0.007], [1, .5, .25], rng, lo=500, hi=8000,
                                               ex_len=0.0006)), at, g)
    if v == 0:
        m.add(wood_box(0.25, rng, bf, t60=0.045, hollow=0.3), 0.0, 0.7)
        m.add(nrm(knock(0.2, [180, 290, 450], [0.05, 0.04, 0.03], [1, .7, .4], rng, lo=80, hi=2000)), 0.0, 0.3)
        for at in (0.035, 0.08, 0.13):
            hit(at, rng.uniform(0.35, 0.6))
        m.add(tf.slosh(0.35, rng, rate=5.5), 0.02, 0.18)
    elif v == 1:
        sd = 0.32
        ss = bell(sd, 0.5, 1.2, 1.2)
        rub = crackle(sd, rng, 700 * ss + 10, decay=0.0006, f_lo=600, f_hi=4500, size_var=0.6)
        m.add(nrm(resonators(rub, [bf, bf * 1.58, bf * 2.31], [0.03, 0.022, 0.015]) + 0.6 * nrm(rub)) * ss, 0.0, 0.5)
        m.add(tf.slosh(0.4, rng, rate=6.0), 0.0, 0.15)
        hit(0.36, 0.6)
        m.add(wood_box(0.2, rng, bf, t60=0.04), 0.36, 0.3)
    elif v == 2:
        m.add(tf.plop(0.1, rng, f0=900, size=0.7, fizz=0.0), 0.0, 0.3)
        hit(0.1, 0.7)
        m.add(wood_box(0.2, rng, bf, t60=0.04), 0.1, 0.35)
        m.add(tf.plop(0.06, rng, f0=1500, size=0.4, fizz=0.0), 0.2, 0.1)
    else:
        m.add(nrm(rustle(0.15, rng, peak=0.3, density=300, lo=600, hi=4000)), 0.0, 0.2)
        for at, g in ((0.0, 0.7), (0.12, 0.5)):
            hit(at, g)
            m.add(wood_box(0.2, rng, bf, t60=0.035, hollow=0.3), at, 0.4 * g)
        m.add(tf.slosh(0.3, rng, rate=5.0), 0.03, 0.14)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.0005, 0, 1)
    return lead_in(street(lp(y, 10000), wet=0.07, seed=190 + v))


# =========================================================================== the streets: the bread queue
VOICE_DIR = os.path.join("sounds", "townsfolk", "voice")
MURMUR_FROM = ("talk.market.", "talk.home.", "talk.town.", "talk.sky.", "talk.doing.")  # everyday talk, not M6's own


def talk_takes(timbre, rng, k):
    """k of the townsfolk's own recorded talk lines in that voice (from the everyday families in MURMUR_FROM, so new
    lines elsewhere leave the murmur as it is), picked by rng in name order"""
    d = os.path.join(ARGS.assets, VOICE_DIR, timbre)
    names = sorted(f for f in os.listdir(d) if f.startswith(MURMUR_FROM) and f.endswith(".ogg"))
    pick = sorted(rng.choice(len(names), size=k, replace=False))
    return [read_audio(os.path.join(d, names[i]))[0] for i in pick]


def syllables(x, rng, lo=0.18, hi=0.55):
    """a take cut at its quiet moments into syllable-long pieces, each faded in and out"""
    e = uniform_filter1d(x ** 2, ns(0.02))
    a = np.abs(x)
    on = np.nonzero(a > np.max(a) * db(-30))[0]
    if not len(on):
        return []
    i, end = on[0], on[-1]
    out = []
    while i < end - ns(lo):
        j = i + ns(rng.uniform(lo, hi))
        w0, w1 = max(i + ns(lo * 0.6), j - ns(0.07)), min(end, j + ns(0.07))
        if w1 <= w0:
            break
        j = w0 + int(np.argmin(e[w0:w1]))
        piece = fade(x[i:j].copy(), 0.012, 0.025)
        if rms(piece) > 0.25 * rms(x[on[0]:end]):
            out.append(piece)
        i = j
    return out


def shuffle_feet(dur, rng):
    """someone in the line shifting their weight: a sole scuffed on the cobbles, a heel set down"""
    m = Mix(dur)
    m.add(scuff(dur * 0.7, rng, 450, 3500, 0.8), 0.0, 0.8)
    m.add(thud(0.1, rng, f=140, decay=0.012, tone=0.2), dur * 0.55, 0.45)
    return m.out()


@effect("streets.queue", 2, "Queue grumbles", loud=-27.0, reach=24, loop=True)
def queue(v, rng):
    """the bread queue waiting (a loop): a few of them muttering to each other, low and fed up, none of it to be made
    out (the townsfolk's own talk, cut into syllables and dealt out afresh, muffled by the crowd), feet shifting on
    the cobbles, a sleeve brushing a coat"""
    dur = [9.2, 10.1][v]
    m = Ring(dur)
    streams = [("man", 0.94, 1400, -2.5), ("woman", 0.98, 1650, -4.0), ("old", 0.95, 1300, -3.0),
               ("man", 0.97, 1500, -6.0), ("woman", 1.0, 1800, -7.0)]
    if v == 1:
        streams = [("woman", 0.97, 1500, -3.5), ("man", 0.93, 1300, -3.0), ("old", 0.94, 1400, -4.5),
                   ("woman", 1.01, 1700, -6.0), ("man", 0.96, 1500, -7.0)]
    for si, (timbre, rate, lpf, g) in enumerate(streams):
        pieces = []
        for x in talk_takes(timbre, rng, 6):
            pieces += syllables(x, rng)
        order = rng.permutation(len(pieces))
        at = rng.uniform(0, 1.0)
        start = at
        for idx in order:
            if at - start > dur - 0.4:
                break
            p = varispeed(pieces[idx], rate)
            p = lp(hp(p, 160), lpf)
            m.add(0.12 * p / (rms(p) + 1e-9), at, db(g) * rng.uniform(0.7, 1.0))
            at += len(p) / SR + (rng.uniform(0.5, 1.4) if rng.random() < 0.22 else rng.uniform(0.02, 0.16))
    for at in rng.uniform(0, dur, [6, 7][v]):  # feet shifting
        m.add(shuffle_feet(rng.uniform(0.18, 0.3), rng), at, 0.25 * rng.uniform(0.6, 1.0))
    for at in rng.uniform(0, dur, 3):  # a sleeve on a coat
        m.add(rustle(0.25, rng, peak=0.4, density=250, lo=600, hi=3500), at, 0.08)
    y = lap(lambda z: lp(z, 6000), m.out())
    return street_round(y, wet=0.14, seed=200 + v)


# =========================================================================== the streets: stalls, the cold, comings and goings
@effect("streets.awning", 3, "Awning flaps", loud=-17.0, max_len=1.3, fade_out=0.25, reach=24)
def awning(v, rng):
    """a stall's canvas awning, from the pull: v1 pulled down by its flap, the canvas sliding over the frame and
    dropping with a flap, its batten knocking the post; v2 drawn up, the cord creaking through its cleat and the canvas
    gathered in with a soft flap; v3 let down and shaken straight, flapping twice"""
    dur = 1.3
    m = Mix(dur)
    m.add(burst(0.03, rng, 300, 3000, 0.006), 0.0, 0.35)  # the hand on the canvas
    if v == 0:
        rd = 0.4
        rate = env_points([(0.0, 0.5), (0.25, 1.0), (rd, 0.4)], rd)
        m.add(cloth(rd, rng, rate, 220, 3200, 0.5), 0.0, 0.7)
        m.add(flap(rng, 1.3), rd, 1.0)
        m.add(timber(0.25, rng, 330, t60=0.05, ex_len=0.002, lo=150), rd + 0.03, 0.4)
    elif v == 1:
        cd = 0.45
        m.add(nrm(creak(cd, rng, 45, 120, [480, 820, 1290], t60=0.04)), 0.0, 0.35)
        m.add(cloth(cd + 0.1, rng, bell(cd + 0.1, 0.5, 1.2, 1.3), 220, 3000, 0.5), 0.02, 0.65)
        m.add(flap(rng, 1.1, 0.7), cd + 0.08, 0.6)
        m.add(nrm(creak(0.12, rng, 60, 140, [480, 820], t60=0.03)), cd + 0.25, 0.2)  # tied off
    else:
        m.add(cloth(0.3, rng, bell(0.3, 0.6, 1.2, 1.3), 220, 3200, 0.5), 0.0, 0.6)
        m.add(flap(rng, 1.3), 0.3, 0.95)
        m.add(cloth(0.2, rng, bell(0.2, 0.5, 1.2, 1.3), 250, 3500, 0.5), 0.42, 0.4)
        m.add(flap(rng, 1.2, 0.8), 0.6, 0.7)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 9000), wet=0.1, seed=210 + v))


@effect("streets.basket", 3, "Basket shaken out", loud=-19.0, max_len=1.1, fade_out=0.2, reach=12)
def basket(v, rng):
    """an empty basket turned over and shaken, from the turn: its wicker creaking, knocking hollow against the hands at
    each shake and nothing falling out but a few crumbs of chaff. v1 two shakes, v2 three, v3 one shake and a slap on
    its bottom"""
    dur = 1.1
    m = Mix(dur)
    td = 0.25
    m.add(wicker(td, rng, bell(td, 0.4, 1.2, 1.4)), 0.0, 0.6)  # turned over
    shakes = [[0.26, 0.5], [0.24, 0.43, 0.62], [0.27]][v]
    for i, at in enumerate(shakes):
        g = 1.0 - 0.15 * i
        m.add(wicker(0.14, rng, bell(0.14, 0.3, 1.0, 1.4)), at, 0.75 * g)
        m.add(thud(0.1, rng, f=200, decay=0.012, tone=0.15), at + 0.01, 0.4 * g)
        m.add(nrm(knock(0.12, [255, 425, 680], [0.03, 0.025, 0.02], [1, .7, .4], rng, lo=120, hi=3000)), at + 0.01,
              0.3 * g)
        if i < 2:
            m.add(grains(0.3, rng, 5, 0.04, 0.06, 2500, 7000, t60=(0.003, 0.008), amp_decay=0.1), at, 0.12 * g)
    if v == 2:  # a slap on its bottom
        at = 0.58
        m.add(burst(0.04, rng, 700, 5500, 0.006), at, 0.65)
        m.add(wicker(0.12, rng, np.exp(-tvec(0.12) / 0.03)), at, 0.6)
        m.add(nrm(knock(0.15, [240, 400, 640], [0.035, 0.028, 0.02], [1, .7, .4], rng, lo=120, hi=3000)), at, 0.4)
        m.add(grains(0.3, rng, 4, 0.03, 0.06, 2500, 7000, t60=(0.003, 0.008), amp_decay=0.1), at, 0.1)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.002, 0, 1)
    return lead_in(street(lp(y, 10000), wet=0.07, seed=220 + v))


@effect("streets.stamp", 6, "Feet stamp", loud=-18.0, max_len=0.45, fade_out=0.1, reach=12)
def stamp(v, rng):
    """one foot stamped down against the cold, from the heel's landing: the boot's thump on the cobbles, its leather
    slapping, the stone's short knock and grit crushed under the sole; v2 and v5 scuffing a little, v4 and v6 on a skin
    of frost"""
    dur = 0.45
    k = [1.0, 0.94, 1.06, 0.97, 1.03, 0.92][v]
    m = Mix(dur)
    m.add(nrm(thump(0.16, 125 * k, 72 * k, rng, decay=0.02, click=0.04, noise=0.55, nlp=600)), 0.0, 1.0)
    m.add(burst(0.03, rng, 380, 3000, 0.0055), 0.0, 0.45)  # the leather
    m.add(stone(0.08, rng, 430 * k, t60=0.02, lo=200, hi=5000, ex_len=0.0015), 0.0005, 0.35)
    ct = tvec(0.05)
    m.add(nrm(crackle(0.05, rng, 6000 * np.exp(-ct / 0.012), decay=0.0005, f_lo=1200, f_hi=6000, size_var=0.9)),
          0.001, 0.25)
    if v in (1, 4):
        m.add(scuff(0.09, rng, 500, 4000, 0.8), 0.05, 0.3)
    if v in (3, 5):  # frost crunching
        m.add(nrm(crackle(0.07, rng, 9000 * np.exp(-tvec(0.07) / 0.02), decay=0.0004, f_lo=1800, f_hi=8000,
                          size_var=1.0)), 0.001, 0.32)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 9000), wet=0.07, seed=230 + v))


@effect("streets.pat", 4, "Back patted", loud=-19.0, max_len=0.4, fade_out=0.1, reach=12)
def pat(v, rng):
    """one pat on the back in an embrace, from the palm landing: a muffled thump on a body through a wool coat, the
    cloth's soft slap and a little rustle; v1 firm, v2 lighter, v3 on a thick coat, duller, v4 on linen, crisper"""
    dur = 0.4
    f = [190, 215, 170, 225][v]
    m = Mix(dur)
    m.add(thud(0.12, rng, f=f, decay=[0.013, 0.01, 0.016, 0.01][v], tone=0.22), 0.0, 1.0)
    m.add(burst(0.025, rng, [300, 320, 250, 420][v], [2600, 3000, 2000, 4200][v], 0.004), 0.0, [0.4, 0.35, 0.3, 0.55][v])
    m.add(nrm(sine_sweep(0.05, 130, 95, 3.0) * np.exp(-tvec(0.05) / 0.012)), 0.0, 0.2)  # the chest under it
    m.add(rustle(0.2, rng, peak=0.2, density=220, lo=500, hi=[3000, 3500, 2500, 5000][v]), 0.01, 0.12)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 7000), wet=0.05, seed=240 + v))


def cart_bed(dur, rng, f=124.0):
    """the handcart's bed of planks, knocked by what's set on it"""
    return nrm(knock(dur, f * np.array([1.0, 1.68, 2.71, 4.05, 5.6]), [0.08, 0.06, 0.045, 0.03, 0.02],
                     [1, .85, .6, .4, .22], rng, lo=70, hi=3500, ex_len=0.005))


@effect("streets.bundle", 4, "Bundle set on a cart", loud=-17.0, max_len=0.8, fade_out=0.15, reach=16, limit=3.0)
def bundle(v, rng):
    """a household's bundle set on their handcart, from its landing: a soft, heavy thump of cloth-wrapped goods on the
    planks, settling, the cart creaking as it takes the weight. v1 a bundle of bedding, v2 one with a pot in it that
    clonks, v3 a heavy one, the cart rocking on its wheels, v4 pushed into place along the boards"""
    dur = 0.8
    m = Mix(dur)
    m.add(thud(0.3, rng, f=[120, 130, 100, 125][v], decay=[0.03, 0.028, 0.036, 0.026][v], tone=0.2), 0.0, 1.0)
    m.add(cart_bed(0.35, rng, [124, 130, 116, 128][v]), 0.003, [0.45, 0.5, 0.6, 0.45][v])
    m.add(rustle(0.3, rng, peak=0.15, density=350, lo=500, hi=4000), 0.01, 0.25)
    if v == 1:  # the pot inside
        m.add(lp(pot_rim(0.25, rng, f=0.8, damp=2.5), 1800), 0.012, 0.28)
    if v == 2:
        m.add(nrm(creak(0.32, rng, 30, 75, [380, 690, 1150], t60=0.05)), 0.06, 0.32)
        m.add(cart_bed(0.25, rng, 118), 0.22, 0.18)
    else:
        m.add(nrm(creak(0.22, rng, 35, 80, [380, 690, 1150], t60=0.05)), 0.07, 0.18)
    if v == 3:  # pushed along the boards
        m.add(scuff(0.25, rng, 300, 2500, 0.5), 0.2, 0.45)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 8000), wet=0.09, seed=250 + v))


@effect("streets.handcart_goods", 2, "Handcart rattles", loud=-23.0, reach=24, loop=True)
def handcart_goods(v, rng):
    """a handcart heaped with a household's goods trundling along (a loop of two turns of its wheels): the iron-shod
    wheels rumbling and crunching grit, a knock as each felloe's joint comes round, the axle creaking once a turn, and
    on every bump the load knocking: a chair's legs, a chest's lid, a pot clanking on its hook, the cage's wires"""
    dur = [3.6, 4.1][v]  # two turns of the wheels at a walk
    n = ns(dur)
    m = Ring(dur)
    sw = pwander(n, rng, 3.0, 0.6, 1.0)
    m.add(nrm(pnoise(n, rng, -6.0, lo=30, hi=260) * sw), 0.0, 0.7)
    m.add(nrm(pcrackle(n, rng, 150 * sw + 20, decay=0.0007, f_lo=800, f_hi=4500, size_var=0.9)), 0.0, 0.25)
    turn = dur / 2
    bumps = []
    for w, off in ((0, 0.0), (1, rng.uniform(0.2, 0.5))):  # each wheel's six joints
        for j in range(6):
            bumps.append((off + j * turn / 6 + rng.uniform(-0.02, 0.02), w))
    bumps += [(at, 2) for at in rng.uniform(0, dur, 3)]  # a stone in the road
    for at, w in bumps:
        g = 1.0 if w == 2 else rng.uniform(0.45, 0.8)
        f = rng.uniform(150, 190)
        m.add(nrm(knock(0.2, f * np.array([1.0, 1.7, 2.75, 4.1]), [0.05, 0.04, 0.03, 0.02], [1, .7, .45, .25], rng,
                        lo=90, hi=3000, ex_len=0.003)), at, 0.45 * g)
        m.add(nrm(thump(0.12, 120, 75, rng, decay=0.02, click=0.0, noise=0.5, nlp=400)), at, 0.3 * g)
        if rng.random() < 0.55 * g + 0.1:  # the load answering the bump
            which = rng.integers(4)
            d = at + rng.uniform(0.012, 0.04)
            if which == 0:  # a chair's leg on the bed
                m.add(timber(0.2, rng, rng.uniform(330, 420), t60=0.04, ex_len=0.0015, lo=200), d, 0.35 * g)
            elif which == 1:  # the chest's lid
                m.add(wood_box(0.2, rng, rng.uniform(210, 260), t60=0.04, hollow=0.3), d, 0.35 * g)
            elif which == 2:  # the pot on its hook
                m.add(pot_rim(0.3, rng, f=rng.uniform(0.85, 1.0), damp=1.0), d, 0.16 * g)
            else:  # the cage's wires
                m.add(jingle(0.15, rng, 3, 2200, 5200, t60=(0.02, 0.05)), d, 0.14 * g)
    for i in range(2):  # the axle, once a turn
        at = 0.35 + i * turn + rng.uniform(-0.04, 0.04)
        m.add(nrm(creak(0.4, rng, 25, 60, [410, 760, 1240], t60=0.05)), at, 0.22)
    y = lap(lambda z: lp(z, 7000), m.out())
    return street_round(y, wet=0.1, seed=260 + v)


# =========================================================================== round two: the medallion, the seals, "To let"
def loud_as(x, lufs):
    """x scaled so that its loudest 100 ms is lufs (K-weighted): layers laid by how loud they sound, not by their
    peaks, so a click and a creak given the same figure sound as loud as each other"""
    x = np.asarray(x, dtype=float)
    return x * db(lufs - float(np.max(loudness_curve(x))))


MED_ZETA, MED_OMEGA = 0.62, 0.24  # MarketBoardArt: the medallion's spring, its damping and (per tick) its pace for a face
MED_DAMPED = MED_OMEGA * np.sqrt(1 - MED_ZETA ** 2)
MED_PEAK = np.pi / MED_DAMPED / 20.0  # s from a one-face turn's start to its swing past onto the catch (0.834)


def med_speed(dur):
    """how fast the medallion turns through a turn of one face, from its start, as a share of its fastest
    (MarketBoardArt.medallionTurn's slope, negative as it swings back): up to speed in a quarter of a second, easing
    off onto its catch at MED_PEAK, back at a twelfth of that, and still"""
    t = tvec(dur) * 20.0
    v = np.exp(-MED_ZETA * MED_OMEGA * t) * np.sin(MED_DAMPED * t)
    return v / np.max(np.abs(v))


def med_body(scale=1.0):
    """the medallion's oak drum and the two posts it turns between: the same modes in every take, as it's one drum,
    dense low down and gathered round the drum's hollow and the posts' tone"""
    f, t60, g = ship.timber_modes(rng_for("works", "medallion", "body"), 85.0, 2400.0, count=28, t60_lo=0.11,
                                  t60_hi=0.02, peaks=(160, 420))
    return f * scale, t60, g


@effect("works.medallion", 6, "Medallion turns", loud=-18.0, max_len=1.75, fade_out=0.35, reach=24, limit=3.0)
def medallion(v, rng):
    """the market board's medallion rolling to its new face, from the moment it's let go: the catch lifting off it
    with a small knock, then the heavy oak drum creaking round on its iron axle in the posts' bearings, as loud and as
    fast as it turns (up to speed in a quarter of a second, easing off as it swings onto its catch at 0.83 s, where
    works.medallion_catch knocks), and a faint creak as it settles back. Made for a turn of one face; the renderer
    plays a turn of n faces, which takes sqrt(n) times as long, at a pitch of 1/sqrt(n), so the creak lasts the turn
    and eases off onto its catch. v1 a rough groan, v2 the axle squeaking dry over it, v3 a creak that sticks and
    skips, v4 the posts groaning under the drum's weight, v5 stuck a moment and breaking free with a crack, v6 drier,
    the iron ticking round in its bearing"""
    dur = 1.75
    t = tvec(dur)
    s = np.abs(med_speed(dur))
    body = med_body()
    r1 = [70, 60, 46, 54, 64, 36][v]
    rate = 2.5 + r1 * s ** 0.8 * rng.uniform(0.95, 1.05)
    amp = s ** 0.75
    vowel = [((240 + 320 * s) * [1.0, 1.06, 0.95, 0.9, 1.0, 1.12][v], 3.5, 0.7)]
    groan = ship.creak_voice(dur, rng, rate, amp, body, vowel=vowel,
                             jitter=[0.08, 0.07, 0.14, 0.1, 0.08, 0.12][v], alt=[0.0, 0.0, 0.55, 0.15, 0.0, 0.25][v],
                             rub=[0.12, 0.1, 0.1, 0.16, 0.12, 0.2][v],
                             kick=[0.0011, 0.001, 0.0012, 0.0014, 0.0011, 0.0008][v],
                             bright=[3000, 3400, 3000, 2400, 3200, 4200][v],
                             unsteady=[0.35, 0.3, 0.45, 0.3, 0.35, 0.4][v])
    m = Mix(dur)
    m.add(loud_as(groan, -20.0), 0.0, 1.0)
    if v == 1:  # the axle dry in its bearing, squeaking as the drum comes up to speed
        sq = ship.squeal(dur, rng, 360 + 190 * s, s ** 2.2 * (t < MED_PEAK), harmonics=6, tilt=1.5, chatter=0.4,
                         peak_hz=900)
        m.add(loud_as(lp(sq, 4000), -28.0), 0.0, 1.0)
    if v == 3:  # the posts groaning under the drum's weight, lower and slower
        posts = ship.creak_voice(dur, rng, 1.5 + 22 * s ** 1.2, s ** 0.9, med_body(0.8), jitter=0.15, alt=0.3,
                                 rub=0.05, kick=0.0016, bright=1800, unsteady=0.5)
        m.add(loud_as(posts, -24.0), 0.02, 1.0)
    if v == 5:  # the iron axle ticking round in its bearing
        ticks = resonators(crackle(dur, rng, 4 + 38 * s, decay=0.0003, f_lo=1500, f_hi=7000, size_var=0.5),
                           [1900, 2650, 3700], [0.04, 0.03, 0.02], [1, .6, .35]) * (t < MED_PEAK + 0.05)
        m.add(loud_as(ticks, -29.0), 0.0, 1.0)
    if v == 4:  # stuck to the bearing a moment: it breaks free with a crack of the wood
        m.add(loud_as(ship.pop(rng, 270, size=0.8, hard=0.8), -22.0), 0.0, 1.0)
    else:  # the catch lifted off the face
        lift = Mix(0.15)
        lift.add(nrm(ship.oak(0.15, rng, [720, 680, 760, 640, 0, 700][v], t60=0.035, hard=0.7)), 0.0, 1.0)
        lift.add(nrm(ship.hann_burst(0.002, rng, 900, 6000)), 0.0, 0.35)
        m.add(loud_as(lift.out(), -26.0), 0.0, 1.0)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.0006, 0, 1)
    return lead_in(street(lp(y, 8000), wet=0.1, seed=270 + v))


@effect("works.medallion_catch", 5, "Medallion's catch knocks", loud=-17.5, max_len=0.7, fade_out=0.2, reach=24,
        limit=4.0)
def medallion_catch(v, rng):
    """the medallion's catch dropping in as a face comes round square, from the knock: the oak pawl falling into
    the notch at the drum's end with a hard, woody knock, the drum's weight coming onto it with a dull thump, its body
    booming under it and its iron boss chinking on the axle. The renderer plays it as the turn swings onto its catch,
    with the dust, and more lightly at each face a turn of several rolls past. v1 a clean knock, v2 the pawl bouncing
    once, v3 duller, the drum's weight in it, v4 brighter, the boss ringing, v5 knocked, and the drum creaking back
    off it"""
    dur = 0.7
    f0 = [560, 600, 520, 640, 580][v] * rng.uniform(0.98, 1.02)
    body = med_body()
    low = body[0] < 900
    m = Mix(dur)
    m.add(loud_as(ship.oak(0.35, rng, f0, t60=0.07, hard=[1.1, 1.0, 0.6, 1.3, 1.0][v], ex=0.0018, lo=250), -18.0),
          0.0, 1.0)  # the pawl into its notch
    m.add(loud_as(ship.hann_burst(0.0015, rng, 1500, 9000), -31.0), 0.0, 1.0)
    weight = thump(0.25, [150, 145, 130, 160, 150][v], 85, rng, decay=0.035, click=0.0, noise=0.45, nlp=420)
    m.add(loud_as(ship.saturate(weight, 1.8), [-21.0, -21.0, -19.0, -22.0, -21.0][v]), 0.001, 1.0)
    shake = fit(ship.hann_burst(0.004, rng, 80, 1200), ns(0.5))
    drum = resonators(shake, body[0][low], body[1][low] * 1.6, body[2][low])
    m.add(loud_as(drum, [-21.0, -21.5, -19.0, -22.0, -21.0][v]), 0.001, 1.0)  # the drum's body under it
    boss = ship.iron_ring(0.35, rng, [1380 * rng.uniform(0.98, 1.02), 2050], t60=0.15, gains=[1.0, 0.5])
    m.add(loud_as(boss, [-31.0, -31.0, -34.0, -26.0, -31.0][v]), 0.002, 1.0)  # its iron boss on the axle
    if v == 1:  # the pawl bouncing once in its notch
        m.add(loud_as(ship.oak(0.25, rng, f0 * 1.03, t60=0.05, hard=0.8, ex=0.0015, lo=300), -25.0), 0.045, 1.0)
    if v == 4:  # and the drum creaking back off the catch
        cd = 0.3
        rate = 3 + 15 * bell(cd, 0.35, 1.2, 1.6)
        back = ship.creak_voice(cd, rng, rate, bell(cd, 0.4, 1.3, 1.5), body, jitter=0.1, rub=0.1, kick=0.0012,
                                bright=2800)
        m.add(loud_as(back, -28.0), 0.09, 1.0)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 9000), wet=0.1, seed=280 + v))


def to_let_board():
    """the "To let" board and its two iron hooks: a thin plank board's modes and, damped and quiet, the hooks'
    iron, the same in every take"""
    f, t60, g = ship.timber_modes(rng_for("works", "to_let", "board"), 260.0, 2600.0, count=22, t60_lo=0.05,
                                  t60_hi=0.012, peaks=(480, 1050))
    return (np.concatenate([f, [1650.0, 2380.0, 3150.0]]), np.concatenate([t60, [0.03, 0.025, 0.02]]),
            np.concatenate([g, 0.12 * np.max(g) * np.array([1.0, 0.7, 0.45])]))


@effect("streets.to_let", 8, "Sign creaks on its hooks", loud=-23.0, max_len=1.3, fade_out=0.3, reach=12)
def to_let(v, rng):
    """the "To let" board creaking on its two iron hooks as the wind swings it, at one end of its swing: the eyes on
    the board's top turning on the hooks, rusty iron on iron, slow and soft, the board's thin planks sounding it.
    Kept low and mellow (nothing shrill in it) and every take a different creak, so that it never grates however often
    it comes round. v1 a slow, soft creak, v2 both hooks, one after the other, v3 a short, rusty squeak, v4 a long one
    that wavers, v5 a creak and the hook shifting in its eye with a soft clink, v6 a rough, low one, v7 a little
    double creak, v8 barely there"""
    board = to_let_board()
    d = [0.62, 0.5, 0.42, 0.95, 0.5, 0.7, 0.55, 0.45][v]
    pk = [0.45, 0.4, 0.5, 0.35, 0.55, 0.45, 0.3, 0.5][v]
    r1 = [85, 95, 100, 75, 90, 42, 105, 16][v]
    dur = d + 0.35
    m = Mix(dur)

    def hook(d, pk, r1, f=1.0, lufs=-24.0, wobble=0.0):
        """one hook's creak: the slips ticking slowly as the board comes to the end of its swing and sets off back,
        running together into a soft, rusty groan in the middle"""
        s = bell(d, pk, 1.4, 1.6)
        if wobble:
            s = s * (0.75 + 0.25 * np.sin(2 * np.pi * wobble * tvec(d) + rng.uniform(0, 6.3)))
        rate = 4.0 + r1 * s ** 1.5 * rng.uniform(0.9, 1.1)
        vowel = [((560 + 260 * s) * f, 4.0, 0.4)]
        y = ship.creak_voice(d, rng, rate, s ** 0.8, (board[0] * f, board[1], board[2]), vowel=vowel, jitter=0.1,
                             alt=rng.uniform(0.0, 0.3), rub=0.06, kick=0.0013, bright=2200, unsteady=0.4)
        return loud_as(lp(y, 4000), lufs)
    if v == 3:  # a long creak that wavers as the board rocks on its hooks
        m.add(hook(d, pk, r1, 1.0, -24.0, wobble=2.6), 0.0, 1.0)
    elif v == 6:  # a little double creak: the board catching and letting go twice
        m.add(hook(0.26, 0.45, r1, 1.04), 0.0, 1.0)
        m.add(hook(0.3, 0.5, r1 * 0.9, 1.0, -25.5), 0.25, 1.0)
    elif v == 7:  # barely there: a few slow ticks of the iron
        m.add(hook(d, pk, r1, 0.97, -27.0), 0.0, 1.0)
    else:
        m.add(hook(d, pk, r1, [1.0, 1.05, 1.0, 0, 0.95, 0.86][v], [-24.0, -24.0, -25.0, 0, -24.0, -23.5][v]), 0.0,
              1.0)
    if v == 1:  # the other hook a moment after
        m.add(hook(0.42, 0.5, 24, 0.93, -27.0), 0.2, 1.0)
    if v == 2:  # the rusty squeak of an eye dry on its hook
        sd = 0.3
        sq = ship.squeal(sd, rng, 540 + 70 * bell(sd, 0.4, 1.2, 1.4), bell(sd, 0.45, 1.3, 1.5), harmonics=5, tilt=1.7,
                         chatter=0.45, wobble=0.02, grit=0.08, peak_hz=1200)
        m.add(loud_as(lp(sq, 3500), -27.0), 0.08, 1.0)
    if v == 4:  # the hook shifting in its eye: a soft clink
        m.add(loud_as(ship.iron_ring(0.25, rng, [2550 * rng.uniform(0.97, 1.03)], t60=0.08), -30.0), d * 0.8, 1.0)
        m.add(loud_as(ship.hann_burst(0.0015, rng, 1500, 6000), -33.0), d * 0.8, 1.0)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.002, 0, 1)
    return lead_in(street(lp(y, 5000), wet=0.08, seed=290 + v))


SEAL_KICK = 0.55 * 20.0  # WorksArt.sealDrop: the seal's swing after the jolt, radians a second
SEAL_FADE = 10.0 / 20.0  # and the time it takes to die to a third (s)


def wax_knock(rng, f=1.0):
    """a wax seal knocking the oak board it hangs against: the wax too soft to click, a dull tap, the board's planks
    ringing a moment behind it"""
    m = Mix(0.3)
    m.add(nrm(knock(0.25, np.array([310, 520, 760, 1090, 1480, 2050]) * f, [0.05, 0.04, 0.03, 0.025, 0.02, 0.015],
                    [1, .8, .6, .45, .3, .2], rng, lo=150, hi=3200, ex_len=0.0025)), 0.0, 1.0)
    m.add(thud(0.08, rng, f=240 * f, decay=0.006, tone=0.2), 0.0, 0.6)
    return m.out()


@effect("works.seal", 6, "Seal drops on its cord", loud=-20.0, max_len=1.2, fade_out=0.3, reach=12, limit=3.0)
def seal(v, rng):
    """a wax seal dropping out on its cord at the foot of the works' post's parchment as a tenth of its price comes
    in, from the jolt as the cord comes taut (WorksArt.sealDrop's end, SEAL_DROP ticks after the seal's tenth came
    in): the cord snapping tight, the seal knocking the board under the parchment with a dull, waxy tap and the
    parchment crackling as the cord tugs it, then the seal swinging to and fro across the board (sealDrop's kick,
    half a swing every 0.29 s, dying away over a second), brushing the oak and tapping it lightly. v1 a clean drop,
    v2 it knocks the seal beside it, v3 the long-corded seal, swinging wider, v4 it bounces off the board and back,
    v5 the parchment tugged harder, crackling, v6 a heavier seal, a lower knock"""
    dur = 1.2
    m = Mix(dur)
    m.add(loud_as(burst(0.01, rng, 800, 5000, 0.0015), -28.0), 0.0, 1.0)  # the cord snapping taut
    m.add(loud_as(thud(0.04, rng, f=320, decay=0.004, tone=0.1), -30.0), 0.0, 1.0)
    f = [1.0, 1.05, 0.97, 1.02, 1.0, 0.85][v]
    m.add(loud_as(wax_knock(rng, f), [-21.0, -21.0, -21.5, -21.0, -22.0, -20.0][v]), 0.002, 1.0)
    ct = tvec(0.07)
    crk = crackle(0.07, rng, 5000 * np.exp(-ct / 0.014), decay=0.0004, f_lo=1500, f_hi=7000, size_var=0.8)
    m.add(loud_as(crk, -27.0 if v == 4 else -30.0), 0.003, 1.0)  # the parchment tugged
    if v == 3:  # it bounces off the board and comes back against it
        m.add(loud_as(wax_knock(rng, f * 1.04), -26.0), 0.065, 1.0)
    if v == 1:  # it knocks the seal beside it: wax on wax
        m.add(loud_as(knock(0.06, [1850, 2600, 3500], [0.012, 0.009, 0.006], [1, .6, .3], rng, lo=800, hi=7000,
                            ex_len=0.0008), -28.0), 0.15, 1.0)
    wide = 1.25 if v == 2 else 1.0
    half = np.pi / SEAL_KICK  # a half-swing, 0.286 s
    for k in range(5):  # the wax brushing the board as it swings across it, fastest through the middle of each swing
        bd = 0.16
        at = k * half + rng.uniform(-0.015, 0.015) if k else bd / 2  # (from the jolt, it's off at its fastest)
        g = -29.0 + 20 * np.log10(wide * np.exp(-at / SEAL_FADE))
        sb = bell(bd, 0.5 if k else 0.25, 1.4, 1.4)
        rub = bp(colored(bd, rng, -1.5), 500, 3500) * (0.6 + 0.4 * smooth_random(bd, rng, 40))
        grain = crackle(bd, rng, 900 * sb + 20, decay=0.0004, f_lo=900, f_hi=4500, size_var=0.7)
        m.add(loud_as((nrm(rub) + 0.5 * nrm(grain)) * sb, g), at - bd / 2, 1.0)
    for k in rng.choice([1, 3, 5], size=[1, 1, 2, 1, 1, 2][v], replace=False):  # its edge tapping the board at a turn
        at = k * half / 2 + rng.uniform(-0.01, 0.01)
        m.add(loud_as(wax_knock(rng, f * rng.uniform(1.05, 1.15)), -24.0 + 20 * np.log10(np.exp(-at / SEAL_FADE))),
              at, 1.0)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.0005, 0, 1)
    return lead_in(street(lp(y, 9000), wet=0.07, seed=300 + v))


# =========================================================================== main
def build(event, r, v):
    rng = rng_for("works", event, v)
    x = r["fn"](v, rng)
    if r["loop"]:
        x = lap(lambda z: signal.sosfiltfilt(signal.butter(2, 25.0, "hp", fs=SR, output="sos"), z),
                np.asarray(x, dtype=float))
        x = x / (np.max(np.abs(x)) + 1e-12) * db(-3.0)
        return level_loop(x, r["loud"])
    x = finalize(x, peak_db=-3.0, fade_out=r["fade_out"], max_len=r["max_len"], tail_db=r["tail_db"])
    return level(x, r["loud"], max_limit_db=r["limit"])


def write_fragments():
    """works_sounds.json (the sounds.json entries) and works_lang.json (their subtitles), for merging by hand"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        names = [f"guildmark:{sound_path(event, v + 1)}" for v in range(r["variants"])]
        entry = [{"name": nm, "attenuation_distance": r["reach"]} for nm in names] if r["reach"] else names
        sounds[event] = {"sounds": entry, "subtitle": f"subtitles.guildmark.{event}"}
        lang[f"subtitles.guildmark.{event}"] = r["subtitle"]
    for name, data in (("works_sounds.json", sounds), ("works_lang.json", lang)):
        with open(os.path.join(HERE, name), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


def write_mp3(path, sounds, gap=0.7, loop_laps=2):
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
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--mp3", default=None)
    ARGS = ap.parse_args()
    sel = ARGS.only.split(",") if ARGS.only else None
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
            x, over, short = build(event, r, v)
            x = write_checked(os.path.join(ARGS.assets, "sounds", name + ".ogg"), x)
            if ARGS.wav:
                write_wav(os.path.join(ARGS.wav, name + ".wav"), x)
            made.append((x, r["loop"]))
            note = (f"  limited {over:.1f} dB" if over > 0.05 else "") + (f"  {short:.1f} dB under (set by peak)"
                                                                          if short > 0.05 else "")
            print(f"{name:28s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s{note}", flush=True)
    write_fragments()
    if ARGS.mp3:
        write_mp3(ARGS.mp3, made)
        print("wrote", ARGS.mp3)


if __name__ == "__main__":
    main()
