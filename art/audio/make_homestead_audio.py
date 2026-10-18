"""Guildmark homesteads: the sounds of a claim staked and a house going up.

usage (from the repo root):
    python3 -B art/audio/make_homestead_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]
        [--preview out.mp3] [--work DIR] [--sf2 FluidR3_GM.sf2]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/homestead/<name><n>.ogg, one per variant of each event
in sounds.json's homestead.* block: the claim (the charter unrolled, its seal pressed, the guild's chime), a stake
knocked in with a mallet, the polite two notes of a refusal, a builder's hammer on a nail, a saw stroke, a shovel
biting the ground, the crew's handbell coming up the road, their cheer, the topping-out (hip, hip, hooray), the
fanfare for a finished tier (a fiddle, a whistle, an accordion and a tabor, played by FluidSynth, and the crew
cheering over the last chord), goods going into a crate, the ledger opened at the post, and scaffolding poles.
--preview also writes every sound in a row, with a breath between, as an MP3 to listen to.

Everything is synthesised (numpy/scipy through sfxkit and the townsfolk kit's helpers) apart from the fanfare's
band. The tool sounds are timed to the builders' animations: each starts on the contact (BuilderCrew plays the
hammer on NAIL_HIT, the saw on SAW_STROKE, the shovel on DIG_BITE and the mallet on STAKE_HIT), so each starts
on its blow with no lead-in. Loudness is set as the townsfolk's is (the loudest 100 ms, K-weighted), peaks held
under -1 dBFS. All randomness is seeded, so a rerun is bit-identical.
"""
import argparse
import os
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403
import make_townsfolk_sfx as tf  # noqa: E402
from make_townsfolk_sfx import (level, write_checked, nrm, small_room, street, ring, knock, thud, grains,  # noqa: E402
                                clods, rustle, creak, lead_in)

SOUND_DIR = "homestead"
SF2_DEFAULT = "/usr/share/sounds/sf2/FluidR3_GM.sf2"
EVENTS = {}  # event (without the "homestead." prefix) -> recipe


def effect(event, variants=1, loud=-16.0, max_len=None, fade_out=0.03, tail_db=-62.0, limit=2.0):
    """registers a sound: loud is the target for its loudest 100 ms (K-weighted LUFS), limit the most dB of peak
    limiting allowed to get there"""
    def deco(fn):
        EVENTS[event] = dict(fn=fn, variants=variants, loud=loud, max_len=max_len, fade_out=fade_out, tail_db=tail_db,
                             limit=limit)
        return fn
    return deco


# =========================================================================== shared
def chime(dur, rng, f):
    """a small bright bell, struck once: its partials a little stretched, every one a shimmering pair"""
    y = ring(dur, f * np.array([1.0, 2.01, 2.99, 4.13, 5.4]), [1.7, 0.9, 0.6, 0.35, 0.2], [1, .28, .45, .2, .1], rng,
             doublet=0.0012)
    tick = bp(white(0.003, rng), 3000, 14000) * np.exp(-tvec(0.003) / 0.0004)
    return nrm(nrm(y) + 0.2 * nrm(fit(tick, len(y))))


def handbell(dur, rng, f0, strength=1.0):
    """the crew's brass handbell, one swing of it: the clapper's tick, the bell's hum, its twelfth and the high,
    quickly dying partials that a harder swing brings out"""
    r = np.array([1.0, 2.02, 2.97, 4.13, 5.38, 6.83, 8.4])
    amps = np.array([1.0, 0.18, 0.62, 0.32, 0.22, 0.12, 0.06]) * strength ** np.array([0, .5, .8, 1.0, 1.2, 1.4, 1.6])
    y = ring(dur, f0 * r, [2.4, 1.5, 1.3, 0.8, 0.55, 0.35, 0.22], amps, rng, doublet=0.0015, doublet_amp=0.6)
    tick = bp(white(0.003, rng), 3000, 14000) * np.exp(-tvec(0.003) / 0.0004)
    return nrm(nrm(y) + 0.22 * strength * nrm(fit(tick, len(y))))


def ring_bell(dur, rng, plan, f0=1046.5):
    """the handbell swung to a rhythm: [(time, strength)]"""
    m = Mix(dur)
    for at, g in plan:
        if at < dur - 0.05:
            m.add(handbell(dur - at, rng, f0 * rng.uniform(0.9993, 1.0007), g), at, g)
    return fit(m.out(), ns(dur))


def shout(dur, rng, f0, vowels, scale=1.0, rise=1.35, breath_h=True, fall=0.3):
    """one person's shout: a breath of h, then the voice thrown up in pitch and falling away, the vowel opening
    from the first to the second"""
    n = ns(dur)
    u = np.linspace(0, 1, n, endpoint=False)
    up = np.sin(np.clip(u / 0.3, 0, 1) * np.pi / 2)
    contour = f0 * (1 + (rise - 1) * up - fall * np.clip((u - 0.45) / 0.55, 0, 1) ** 1.5)
    vib = 1 + 0.015 * np.sin(2 * np.pi * rng.uniform(4.5, 6.0) * u * dur + rng.uniform(0, 6.3))
    src = glottal(dur, contour * vib, rng, jitter=0.03, shimmer=0.16, breath=0.3)
    a, b = vowels
    voiced = formants(src, a, b, scale=scale, curve=np.clip((u - 0.15) / 0.45, 0, 1))
    voiced = drive(nrm(voiced), 1.6)
    y = nrm(voiced) * bell(dur, 0.2, 1.2, 1.8)
    if breath_h:
        hd = 0.05
        hh = bp(white(hd, rng), 700, 4500) * bell(hd, 0.75, 1.0, 1.0)
        y = np.concatenate([0.22 * nrm(hh), y])
    return y


def voice(rng, female):
    """a speaking pitch and the size of the throat for one of the crew"""
    if female:
        return rng.uniform(190, 250), rng.uniform(1.12, 1.2)
    return rng.uniform(100, 150), rng.uniform(0.94, 1.04)


def crowd(dur, rng, words, n=4, start=0.0, spread=0.14, women=0.3, gain=(0.6, 1.0), rise=1.35):
    """a few of the crew shouting the same word together, ragged: words are [(syllables, ...)] where each syllable
    is (vowels, length, pitch step, gap before it)"""
    m = Mix(dur)
    for i in range(n):
        at = start + rng.uniform(0, spread)
        f0, sc = voice(rng, rng.random() < women)
        word = words[rng.integers(len(words))]
        g = rng.uniform(*gain)
        for vowels, length, step, gap in word:
            at += gap
            s = shout(length * rng.uniform(0.9, 1.12), rng, f0 * step, vowels, sc, rise=rise)
            m.add(s, at, g * (0.55 if step < 1 else 1.0))
            at += length * 0.8
    return fit(m.out(), ns(dur))


HEY = [(("e", "i"), 0.36, 1.25, 0.0)]
HOORAY = [(("u", "u"), 0.16, 0.9, 0.0), (("e", "i"), 0.5, 1.3, 0.02)]
HURRAH = [(("uh", "uh"), 0.13, 0.92, 0.0), (("a", "a"), 0.5, 1.28, 0.02)]


def whistle(dur, rng, f0=2300.0, up=1.25):
    """two fingers in the mouth: a pure, piercing note swooping up and falling off, a little breath round it"""
    u = np.linspace(0, 1, ns(dur), endpoint=False)
    f = f0 * (1 + (up - 1) * np.sin(np.clip(u / 0.35, 0, 1) * np.pi / 2) - 0.22 * np.clip((u - 0.6) / 0.4, 0, 1))
    tone = np.sin(phase_from_freq(f * (1 + 0.004 * np.sin(2 * np.pi * 7 * u * dur))))
    air = bp(white(dur, rng), f0 * 0.8, f0 * 1.6)
    return nrm(tone + 0.06 * nrm(air)) * bell(dur, 0.25, 1.0, 1.4)


def claps(dur, rng, count, start, spread):
    """a few of the crew clapping, out of step with each other"""
    m = Mix(dur)
    for i in range(count):
        at = start + rng.uniform(0, spread)
        if at < dur - 0.3:
            m.add(tf.clap(int(rng.integers(3)), rng), at, rng.uniform(0.35, 0.7))
    return fit(m.out(), ns(dur))


def wood_knock(dur, rng, f0, t60=0.06, ex_len=0.0018, bright=1.0):
    """wood struck by wood: the mallet's head and the stake's top, a hollow knock"""
    return nrm(knock(dur, f0 * np.array([1.0, 1.58, 2.24, 3.1, 4.3]), np.array([1.0, 0.75, 0.5, 0.33, 0.2]) * t60,
                     [1, .75, .5 * bright, .3 * bright, .15 * bright], rng, lo=200, hi=6000, ex_len=ex_len))


def pole(rng, f, t60=0.12):
    """a scaffold pole knocked: a long round bar of wood, its overtones at the free bar's spacing"""
    return nrm(knock(t60 * 2.2 + 0.05, f * np.array([1.0, 2.756, 5.404, 8.933]), [t60, t60 * 0.6, t60 * 0.4, t60 * 0.25],
                     [1, .7, .4, .2], rng, lo=150, hi=7000, ex_len=0.0015))


# =========================================================================== the claim
@effect("claim", 1, loud=-15.0, max_len=2.9, fade_out=0.7)
def claim(v, rng):
    """the charter unrolled with a crisp sweep of parchment, its seal pressed down onto it (a soft heavy squash of
    wax and the table under it), and the guild's chime: three bell notes rising, a fifth and an octave over the
    first, ringing on together"""
    dur = 2.9
    m = Mix(dur)
    ud = 0.36
    s = bell(ud, 0.55, 1.2, 1.3)
    paper = bp(colored(ud, rng, -1.0), 900, 7000) * s ** 1.4
    crk = crackle(ud, rng, 900 * s + 20, decay=0.0006, f_lo=1500, f_hi=7500, size_var=0.9) * s
    m.add(nrm(nrm(paper) + 0.8 * nrm(crk)), 0.0, 0.35)
    m.add(thud(0.22, rng, f=150, decay=0.02, tone=0.4), 0.42, 0.75)
    m.add(nrm(bp(white(0.09, rng), 300, 2400) * bell(0.09, 0.2, 1.0, 2.0)), 0.425, 0.22)
    for f, at, g in ((783.99, 0.62, 0.8), (1174.66, 0.84, 0.66), (1567.98, 1.06, 0.72)):
        m.add(chime(dur - at, rng, f), at, g)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.16, seed=11))


@effect("stake", 3, loud=-15.0, max_len=0.55, fade_out=0.12, limit=3.0)
def stake(v, rng):
    """a stake knocked in with a mallet: wood on wood, the stake shoved into the ground with a dull bite, the
    earth giving round it (v3 throws up a few clods)"""
    dur = 0.55
    m = Mix(dur)
    m.add(wood_knock(0.25, rng, [420, 380, 455][v]), 0.0, 1.0)
    m.add(nrm(bp(white(0.008, rng), 1500, 9000) * np.exp(-tvec(0.008) / 0.001)), 0.0, 0.3)
    m.add(thud(0.3, rng, [95, 88, 102][v], decay=0.03, tone=0.35), 0.001, 0.75)
    ct = tvec(0.12)
    m.add(nrm(crackle(0.12, rng, 5000 * np.exp(-ct / 0.03), decay=0.0009, f_lo=250, f_hi=3000, size_var=0.9)), 0.003, 0.4)
    if v == 2:
        m.add(clods(dur, rng, 5, start=0.05, spread=0.06), 0.0, 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=20 + v))


def bar_tone(dur, rng, f):
    """a soft mallet on a wooden bar: the note, the bar's tuned overtone two octaves up, a whisper of the third"""
    return nrm(modal(dur, [f, f * 3.93, f * 9.2], [0.32, 0.11, 0.05], [1, .22, .05], rng, attack=0.0025))


@effect("refuse", 2, loud=-19.0, max_len=0.75, fade_out=0.18)
def refuse(v, rng):
    """the polite little shake of the head: two soft wooden notes stepping down, a fourth (v1) or a fifth (v2)"""
    dur = 0.75
    a, b = [(587.33, 440.0), (587.33, 392.0)][v]
    m = Mix(dur)
    m.add(bar_tone(0.5, rng, a), 0.0, 0.8)
    m.add(bar_tone(0.6, rng, b), 0.16, 0.95)
    m.add(nrm(knock(0.06, [900, 1500], [0.012, 0.008], [1, .5], rng, lo=300, hi=5000, ex_len=0.002)), 0.0, 0.08)
    y = lp(fit(m.out(), ns(dur)), 6000)
    return lead_in(small_room(y, wet=0.12, rt60=0.4, seed=30 + v))


# =========================================================================== the builders at work
@effect("hammer", 4, loud=-14.0, max_len=0.5, fade_out=0.12, limit=4.0)
def hammer(v, rng):
    """a builder's hammer on a nail: steel meeting steel, the nail ringing while it still stands proud (less with
    every blow as the wood grips it), the plank humming under it, and the hammer head's own dull clank. v1..v4 are
    one nail from the first tap to the blow that sets it"""
    dur = 0.5
    proud = [1.0, 0.8, 0.55, 0.2][v]
    m = Mix(dur)
    m.add(nrm(bp(white(0.005, rng), 2500, 15000) * np.exp(-tvec(0.005) / 0.0006)), 0.0, 0.3)
    nf = [3150, 3480, 3900, 4400][v]
    nail = ring(0.3, nf * np.array([1.0, 2.76, 5.4]), np.array([0.12, 0.06, 0.03]) * proud + 0.008, [1, .4, .15], rng,
                doublet=0.002)
    m.add(nrm(nail), 0.0, 0.32 * proud + 0.04)
    pf = [205, 230, 190, 215][v]
    board = knock(0.3, pf * np.array([1.0, 1.72, 2.65, 3.8, 5.1, 6.9]), [0.09, 0.07, 0.05, 0.035, 0.025, 0.015],
                  [1, .8, .6, .4, .25, .15], rng, lo=120, hi=6000, ex_len=0.0012)
    m.add(nrm(board), 0.0005, 0.9)
    m.add(nrm(modal(0.08, [1850, 2720, 4150], [0.025, 0.02, 0.012], [1, .6, .3], rng)), 0.0, 0.22)
    m.add(nrm(thump(0.12, 160, 90, rng, decay=0.015, click=0.0, noise=0.4, nlp=600)), 0.0, 0.35 + 0.35 * (1 - proud))
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.12, seed=40 + v))


def saw_stroke(dur, rng, push=True, speed=1.0):
    """one stroke of a hand saw through a plank: each tooth tearing its fibre (a tick, a hundred or so a second at
    the stroke's quickest), the board resonating under the blade, sawdust hissing off the sides and the blade
    itself singing faintly; the push cuts and is heavier, the pull only drags"""
    t = tvec(dur)
    s = bell(dur, 0.45 if push else 0.4, 1.3, 1.5)
    rate = (150 + 250 * s) * speed * (0.88 + 0.24 * smooth_random(dur, rng, 30))
    ph = np.cumsum(rate) / SR
    ticks = np.diff(np.floor(ph), prepend=0.0) > 0
    imp = ticks * (0.55 + 0.45 * rng.random(len(t))) * s ** 1.2
    grain = bp(white(0.003, rng), 900, 9000) * np.exp(-tvec(0.003) / 0.0007)
    rasp = signal.fftconvolve(imp, grain)[:len(t)]
    plank = resonators(rasp, [520, 940, 1480, 2230, 3350], [0.03, 0.026, 0.02, 0.016, 0.012], [1, .8, .6, .45, .3])
    hiss = bp(colored(dur, rng, -0.5), 2500, 9000) * s ** 1.5
    whine = modal(dur, [2640 * rng.uniform(0.98, 1.02), 4210], [0.3, 0.2], [1, .5], rng) * s
    y = nrm(rasp) * (1.0 if push else 0.55) + 0.7 * nrm(plank) + 0.25 * nrm(hiss) + 0.05 * nrm(whine)
    return nrm(y) * (1.0 if push else 0.6)


@effect("saw", 3, loud=-17.0, max_len=1.0, fade_out=0.12)
def saw(v, rng):
    """a stroke there and back, the length of one swing of the builder's arm (v2 quicker; v3 cuts through, the
    offcut cracking off and dropping)"""
    dur = 1.0
    m = Mix(dur)
    if v == 0:
        m.add(saw_stroke(0.52, rng, True), 0.0, 1.0)
        m.add(saw_stroke(0.34, rng, False), 0.55, 1.0)
    elif v == 1:
        m.add(saw_stroke(0.44, rng, True, 1.15), 0.0, 1.0)
        m.add(saw_stroke(0.3, rng, False, 1.1), 0.47, 1.0)
    else:
        m.add(saw_stroke(0.5, rng, True, 0.95), 0.0, 1.0)
        m.add(nrm(crackle(0.06, rng, 2500, decay=0.0008, f_lo=600, f_hi=6000, size_var=1.0)), 0.47, 0.5)
        m.add(nrm(knock(0.2, [610, 1050, 1720, 2600], [0.05, 0.04, 0.03, 0.02], [1, .7, .5, .3], rng, lo=200, hi=6000,
                        ex_len=0.002)), 0.62, 0.55)
        m.add(nrm(knock(0.15, [700, 1200, 1900], [0.04, 0.03, 0.02], [1, .6, .3], rng, lo=200, hi=6000)), 0.74, 0.25)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 9500), wet=0.1, seed=44 + v))


@effect("dig", 3, loud=-16.0, max_len=0.75, fade_out=0.15)
def dig(v, rng):
    """a shovel biting the ground: the blade scraping in over grit (its steel ringing a little), the soil giving with
    a crunch and a heavy, dull bite, then the spadeful lifted and thrown, clods pattering down. v2 grates on a stone,
    v3 tears through roots"""
    dur = 0.75
    m = Mix(dur)
    sd = 0.12
    ss = bell(sd, 0.3, 1.0, 1.5)
    m.add(nrm(crackle(sd, rng, 6000 * ss + 50, decay=0.0005, f_lo=1500, f_hi=7000, size_var=0.8)), 0.0, 0.42)
    blade = ring(0.25, np.array([1250 * rng.uniform(0.97, 1.03), 2710, 4300]), [0.1, 0.07, 0.04], [1, .6, .3], rng,
                 doublet=0.003)
    m.add(nrm(blade), 0.0, 0.1)
    m.add(thud(0.3, rng, [92, 86, 100][v], decay=0.026, tone=0.3), 0.05, 0.8)
    ct = tvec(0.2)
    m.add(nrm(crackle(0.2, rng, 8000 * np.exp(-ct / 0.04), decay=0.0009, f_lo=220, f_hi=3500, size_var=0.9)), 0.05, 0.7)
    m.add(nrm(bp(colored(0.18, rng, -1.5), 300, 2500) * bell(0.18, 0.4, 1.2, 1.5)), 0.32, 0.25)
    m.add(clods(dur, rng, [9, 7, 8][v], start=0.42, spread=0.07), 0.0, 0.45)
    if v == 1:
        grate = ring(0.18, [2350, 3610, 5200], [0.05, 0.035, 0.02], [1, .6, .35], rng, doublet=0.002)
        m.add(nrm(grate), 0.03, 0.22)
    if v == 2:
        rd = 0.16
        m.add(nrm(crackle(rd, rng, 1800 * bell(rd, 0.3, 1.0, 1.5) + 20, decay=0.0015, f_lo=300, f_hi=4000,
                          size_var=1.0)), 0.1, 0.45)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 8500), wet=0.08, seed=50 + v))


# =========================================================================== the crew
@effect("arrive", 1, loud=-16.0, max_len=3.4, fade_out=0.9)
def arrive(v, rng):
    """the crew's handbell as they come up the road in the morning: swung in a walking rhythm, ding, ding,
    ding-ding-ding, ringing on"""
    dur = 3.4
    y = ring_bell(dur, rng, [(0.0, 1.0), (0.4, 0.72), (0.8, 0.95), (1.06, 0.62), (1.32, 0.86), (1.72, 0.66)])
    return lead_in(street(y, wet=0.18, seed=60))


@effect("cheer", 2, loud=-15.0, max_len=2.0, fade_out=0.35, limit=3.0)
def cheer(v, rng):
    """the crew's cheer when a stage is done: four or five of them together, ragged, and a few claps. v1 'hey!',
    v2 'hurrah!' and a whistle"""
    dur = 2.0
    m = Mix(dur)
    if v == 0:
        m.add(crowd(dur, rng, [HEY], n=5, start=0.0, spread=0.12), 0.0, 1.0)
        m.add(claps(dur, rng, 7, 0.35, 0.8), 0.0, 0.45)
    else:
        m.add(crowd(dur, rng, [HURRAH], n=5, start=0.0, spread=0.12), 0.0, 1.0)
        m.add(whistle(0.55, rng, 2350), 0.5, 0.18)
        m.add(claps(dur, rng, 6, 0.55, 0.7), 0.0, 0.4)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.14, seed=64 + v))


@effect("topping", 1, loud=-14.0, max_len=3.8, fade_out=0.8, limit=3.0)
def topping(v, rng):
    """the topping-out, the bough tied on the ridge: one of them calls 'hip, hip!', all of them answer 'hooray!',
    and again, then clapping, a whistle and the handbell (the rockets go up with their own sound)"""
    dur = 3.8
    m = Mix(dur)
    f0, sc = voice(rng, False)
    for at in (0.0, 0.3):
        m.add(shout(0.17, rng, f0 * 1.15, ("i", "i"), sc, rise=1.25), at, 0.8)
    m.add(crowd(dur, rng, [HOORAY], n=5, start=0.62, spread=0.1), 0.0, 1.0)
    m.add(crowd(dur, rng, [HOORAY], n=5, start=1.45, spread=0.1, rise=1.42), 0.0, 1.0)
    m.add(claps(dur, rng, 12, 2.05, 1.2), 0.0, 0.5)
    m.add(whistle(0.6, rng, 2250, 1.3), 2.2, 0.16)
    m.add(ring_bell(dur - 2.1, rng, [(0.0, 0.8), (0.3, 0.6), (0.6, 0.75), (0.82, 0.5)]), 2.1, 0.32)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.16, seed=70))


def band(work, sf2):
    """the crew's tune for a finished tier: a fiddle and a tin whistle in a bright little flourish in D, an
    accordion under them, a tabor driving it, a cymbal on the last chord. Played by FluidSynth (General MIDI)"""
    import warnings
    from make_music import Song, export_stem, render as fluid_render, roll
    s = Song("homestead_finished", bpm=150, bars=3, bpb=3)
    fid = s.part("fiddle", 110, "band", pan=48, volume=104, hum=0.004, vjit=4, expr0=118)
    whi = s.part("whistle", 78, "band", pan=80, volume=86, hum=0.004, vjit=3, expr0=112)
    acc = s.part("accordion", 21, "band", pan=64, volume=78, hum=0.006, vjit=3, expr0=100)
    kit = s.part("kit", 0, "band", drum=True, pan=64, volume=100, hum=0.003, vjit=4)
    # A pickup, two bars running up and turning, and the arrival held
    tune = "A4:0.5 B4:0.5 C#5:0.5 D5:1 F#5:0.5 A5:0.5 F#5:0.5 D5:0.5 E5:0.5 F#5:0.5 G5:0.5 E5:0.5 C#5:0.5 A4:0.5 D5:3"
    end = fid.line(0, tune, 92, leg=0.94, accent=8)
    whi.line(0, tune.replace("A4:0.5 B4:0.5 C#5:0.5", "R:1.5"), 76, tr=12, leg=0.9)
    for beat, dur, ch in ((1.5, 3.0, ["D4", "F#4", "A4"]), (4.5, 1.5, ["A3", "E4", "G4"]), (6.0, 1.5, ["A3", "C#4", "E4"]),
                          (7.5, 3.0, ["D4", "F#4", "A4", "D5"])):
        acc.chord(beat, dur * 0.96, ch, 70 if beat < 7 else 84)
    acc.expr([(0, 90), (7.4, 104), (7.5, 120), (10.5, 70)])
    for b in np.arange(1.5, 7.5, 1.5):
        kit.n(b, 0.4, 36, 84)
        kit.n(b + 0.75, 0.3, 38, 62)
        kit.n(b + 1.0, 0.3, 54, 48)
    roll(kit, s, 6.25, 1.25, 38, 40, 92, rate=0.125)
    kit.n(7.5, 2.0, 49, 96)
    kit.n(7.5, 1.0, 36, 104)
    s.end_beat = end
    os.makedirs(work, exist_ok=True)
    mid, wav = os.path.join(work, "homestead_finished.mid"), os.path.join(work, "homestead_finished.wav")
    export_stem(s, "band", mid, tail_beats=6)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        y = fluid_render(mid, wav, sf2)
    x = np.mean(y, axis=0)
    a = np.abs(x)
    i0 = max(0, int(np.argmax(a > np.max(a) * db(-50))) - ns(0.002))
    return x[i0:], s.spb


@effect("finished", 1, loud=-14.0, max_len=5.6, fade_out=1.0, limit=3.0)
def finished(v, rng):
    """a tier finished: the crew's tune, and the crew cheering over its last chord, clapping and whistling"""
    x, spb = band(ARGS.work, ARGS.sf2)
    dur = 5.6
    m = Mix(dur)
    x = reverb(x, wet=0.12, rt60=0.7, predelay=0.012, hf_damp=0.55, seed=81, early_span=0.04)
    m.add(nrm(x), 0.0, 1.0)
    arrival = 7.5 * spb
    m.add(crowd(dur, rng, [HURRAH, HOORAY], n=6, start=arrival + 0.05, spread=0.12), 0.0, 0.55)
    m.add(claps(dur, rng, 10, arrival + 0.5, 1.2), 0.0, 0.3)
    m.add(whistle(0.6, rng, 2400, 1.28), arrival + 0.35, 0.1)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.08, seed=82))


# =========================================================================== the crates, the ledger, the scaffold
def crate_body(rng):
    """the crate itself, knocked by what goes in: a boxy wooden thump"""
    return nrm(knock(0.3, [172, 288, 455, 690], [0.07, 0.055, 0.04, 0.03], [1, .8, .5, .3], rng, lo=80, hi=3000,
                     ex_len=0.004))


@effect("deliver", 3, loud=-17.0, max_len=0.6, fade_out=0.12, limit=3.0)
def deliver(v, rng):
    """goods going into a crate: v1 lengths of timber clunking in, v2 stone set down with a clack and a little grit,
    v3 a soft bundle (wool, a sack of goods) flopping in and settling"""
    dur = 0.6
    m = Mix(dur)
    if v == 0:
        for at, f, g in ((0.0, 310, 1.0), (0.09, 405, 0.6), (0.2, 360, 0.35)):
            m.add(nrm(knock(0.25, f * np.array([1, 2.76, 5.4]), [0.08, 0.05, 0.03], [1, .5, .25], rng, lo=150, hi=6000,
                            ex_len=0.002)), at, g)
        m.add(crate_body(rng), 0.0, 0.7)
    elif v == 1:
        for at, f, g in ((0.0, 1450, 1.0), (0.11, 1780, 0.55)):
            m.add(nrm(knock(0.12, f * np.array([1, 1.51, 2.3, 3.2]), [0.03, 0.022, 0.015, 0.01], [1, .7, .45, .3], rng,
                            lo=400, hi=9000, ex_len=0.0008)), at, g)
        m.add(crate_body(rng), 0.0, 0.8)
        m.add(nrm(thump(0.2, 140, 80, rng, decay=0.03, click=0.1, noise=0.4, nlp=600)), 0.0, 0.6)
        m.add(grains(dur, rng, 8, 0.02, 0.06, 2500, 7000, t60=(0.006, 0.015), amp_decay=0.15), 0.0, 0.2)
    else:
        m.add(thud(0.3, rng, 120, decay=0.03, tone=0.2), 0.0, 1.0)
        m.add(crate_body(rng), 0.003, 0.4)
        m.add(rustle(0.3, rng, peak=0.2, density=500, lo=700, hi=6000), 0.02, 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(y, wet=0.08, rt60=0.35, seed=90 + v))


@effect("ledger", 1, loud=-19.0, max_len=1.3, fade_out=0.25)
def ledger(v, rng):
    """the ledger opened at the post: the box's lid lifted on its hinge (a small dry creak and the knock on its
    stop), the heavy book's cover swung open and two leaves turned to the last page written"""
    dur = 1.3
    m = Mix(dur)
    m.add(nrm(creak(0.2, rng, 60, 160, [720, 1240, 1980], t60=0.05)), 0.0, 0.3)
    m.add(nrm(knock(0.15, [520, 890, 1400], [0.04, 0.03, 0.02], [1, .6, .35], rng, lo=200, hi=5000, ex_len=0.002)), 0.2, 0.4)
    cd = 0.22
    cs = bell(cd, 0.45, 1.3, 1.4)
    m.add(nrm(bp(colored(cd, rng, -2.0), 200, 2200) * cs ** 1.3), 0.32, 0.4)
    m.add(thud(0.15, rng, f=140, decay=0.016, tone=0.2), 0.5, 0.45)
    m.add(tf.mage_page(1, rng), 0.62, 0.6)
    m.add(tf.mage_page(0, rng), 0.86, 0.5)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.08, seed=96))


@effect("scaffold", 3, loud=-17.0, max_len=0.8, fade_out=0.15, limit=3.0)
def scaffold(v, rng):
    """scaffolding going up or coming down: v1 poles knocking against each other as they're stood up, v2 a lashing
    pulled tight (the rope creaking, a pole nudged), v3 a board slapped down across the ledgers"""
    dur = 0.8
    m = Mix(dur)
    if v == 0:
        for at, f, g in ((0.0, 410, 1.0), (0.07, 530, 0.6), (0.19, 470, 0.45), (0.34, 600, 0.25)):
            m.add(pole(rng, f), at, g)
    elif v == 1:
        m.add(nrm(creak(0.32, rng, 40, 120, [380, 690, 1150], t60=0.06)), 0.0, 0.5)
        m.add(pole(rng, 360, 0.1), 0.3, 0.6)
        m.add(nrm(creak(0.16, rng, 70, 150, [400, 720], t60=0.05)), 0.42, 0.3)
    else:
        board = knock(0.35, [158, 266, 410, 620, 905], [0.07, 0.06, 0.045, 0.03, 0.02], [1, .85, .6, .4, .25], rng,
                      lo=90, hi=5000, ex_len=0.0035)
        m.add(nrm(board), 0.0, 1.0)
        m.add(nrm(bp(white(0.02, rng), 400, 4000) * np.exp(-tvec(0.02) / 0.004)), 0.0, 0.5)
        m.add(pole(rng, 450, 0.08), 0.012, 0.3)
        m.add(pole(rng, 520, 0.06), 0.11, 0.18)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=100 + v))


# =========================================================================== main
ARGS = None


def build(event, r, v):
    rng = rng_for("homestead", event, v)
    x = r["fn"](v, rng)
    x = finalize(x, peak_db=-3.0, fade_out=r["fade_out"], max_len=r["max_len"], tail_db=r["tail_db"])
    return level(x, r["loud"], max_limit_db=r["limit"])


def write_preview(path, sounds, gap=0.7):
    """every sound in a row with a breath between, as an MP3 (ffmpeg, libmp3lame)"""
    parts = []
    for x in sounds:
        parts += [x, np.zeros(ns(gap))]
    y = np.concatenate(parts)
    with tempfile.TemporaryDirectory() as td:
        w = os.path.join(td, "preview.wav")
        write_wav(w, y)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", w, "-c:a", "libmp3lame", "-q:a", "3", path], check=True)


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--preview", default=None)
    ap.add_argument("--work", default="build/homestead_music_work")
    ap.add_argument("--sf2", default=SF2_DEFAULT)
    ARGS = ap.parse_args()
    sel = ARGS.only.split(",") if ARGS.only else None
    made = []
    for event, r in EVENTS.items():
        if sel and event not in sel:
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            name = f"{SOUND_DIR}/{event}{v + 1}"
            x, over, short = build(event, r, v)
            x = write_checked(os.path.join(ARGS.assets, "sounds", name + ".ogg"), x)
            if ARGS.wav:
                write_wav(os.path.join(ARGS.wav, name + ".wav"), x)
            made.append(x)
            note = (f"  limited {over:.1f} dB" if over > 0.05 else "") + (f"  {short:.1f} dB under (set by peak)"
                                                                          if short > 0.05 else "")
            print(f"{name:26s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s{note}", flush=True)
    if ARGS.preview:
        write_preview(ARGS.preview, made)
        print("preview", ARGS.preview)


if __name__ == "__main__":
    main()
