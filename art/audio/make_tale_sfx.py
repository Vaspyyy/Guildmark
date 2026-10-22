"""Guildmark's tales: the seven sounds a tale makes (living-world/stories/design.md 13.4). Four are the banner's stings,
played to the one who has the tale; three happen in the street, played where the thing is done.

usage (from the repo root):
    python3 -B art/audio/make_tale_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...] [--mp3 FILE]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/townsfolk/tale/ the way make_lamp_sfx.py does (the event
townsfolk.tale.begin is tale/begin1..2), and art/audio/tales_sounds.json and tales_lang.json: their sounds.json entries and
subtitles, which art/lang/merge_lang.py merges into the real sounds.json and en_us.json. --only takes event names such as
tale.begin or tale.end; --mp3 joins what was rendered into one listen-through MP3, a chapter mark at each sound.

The stings share one key (D) and one hall, so that they sound like one tale told four ways:
  tale.begin        a quill scratching a few strokes, then two soft bell notes a fifth apart, rising, with a breath of
                    strings under the second (2 files: D5 to A5, B4 to F#5)
  tale.step         one soft bell note and a page turned (2: D5, A4)
  tale.end_good     three bell notes rising to the home note (D5), a warm chord of strings held under it, and a seal
                    pressed into wax (2: F#4 A4 D5, B4 C#5 D5)
  tale.end_ill      one low bell, muffled with a cloth, a second note a minor third under it, and a hush; no seal
                    (2: D4 to B3, A3 to F#3)
and three in the street, dry apart from a small room, as the townsfolk's are:
  tale.note_burn    a paper catching: a short dry rasp, a soft whump, a thin crackle of the sheet curling, a last
                    settling to ash (3 files)
  tale.candle_light a match struck, the wick taking with a soft 'fp', a warm low hum under it (2)
  tale.cart_mend    a mended wheel dropped onto its axle: the hub creaking along it, a wooden thunk, the cart settling on
                    its springs (2)

The bells are a bell's own partials (a hum, a prime, the minor-third tierce, the quint, the nominal, then the stretched
upper ones), each a close pair that beats, the upper ones dying first, and a mallet's or a muffled clapper's hardness setting
how many of them are heard; the strings are several voices of a bowed note a few cents apart, each with its vibrato, with
the scrape of the bow. Everything is synthesised with sfxkit and make_townsfolk_sfx.py's helpers, levelled the same way (the
loudest 100 ms to a K-weighted target, peaks under -1 dBFS), and seeded, so a rerun is bit-identical.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from make_townsfolk_sfx import *  # noqa: E402,F401,F403  (sfxkit, and the townsfolk's own helpers)
from make_townsfolk_sfx import build_effect, sound_path, write_checked  # noqa: E402

EVENTS = {}  # event (without the "townsfolk." prefix) -> recipe


def effect(event, variants=1, subtitle="", loud=-16.0, max_len=None, fade_out=0.03, tail_db=-62.0,
           attenuation=None, limit=2.0):
    """registers a sound effect, as make_townsfolk_sfx.effect does (loud: the loudest 100 ms, K-weighted LUFS; limit:
    the most dB of peak limiting allowed to get there; attenuation: how far it carries in blocks, when more than 16)"""
    def deco(fn):
        EVENTS[event] = dict(kind="effect", fn=fn, variants=variants, subtitle=subtitle, loud=loud, max_len=max_len,
                             fade_out=fade_out, tail_db=tail_db, attenuation=attenuation, limit=limit, stream=False)
        return fn
    return deco


# =========================================================================== notes, the hall
NOTE_PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def hz(name):
    """'F#4' -> 369.99 (equal temperament, A4 = 440)"""
    pc = NOTE_PC[name[0]] + (1 if "#" in name[1:-1] else -1 if "b" in name[1:-1] else 0)
    return 440.0 * 2.0 ** ((12 * (int(name[-1]) + 1) + pc - 69) / 12.0)


# One hall for the four stings: a stone room with a long, dark tail
HALL = dict(rt60=2.0, predelay=0.022, hf_damp=0.55, early_span=0.06, seed=4801)


def hall(x, wet=0.4):
    return reverb(x, wet=wet, **HALL)


def together(*parts):
    """the parts laid over each other from their start, the longest deciding the length (a reverb's tail is longer than
    what went in)"""
    n = max(len(p) for p in parts)
    return sum(fit(p, n) for p in parts)


# =========================================================================== bells and strings
BELL = np.array(metal_partials(1.0, "bell", n=12))  # hum, prime, tierce, quint, nominal, then the upper partials
BELL_AMP = np.array([0.30, 0.75, 0.50, 0.42, 1.00, 0.45, 0.40, 0.28, 0.18, 0.10, 0.06, 0.035])


def bell_note(dur, rng, f0, t60=1.6, hard=0.2, mute=0.0):
    """a bell struck and heard at f0 (its nominal; the prime is an octave under it and the hum two). Every partial is a
    close pair that beats as it dies, the upper ones dying first; hard (0..1) is the strike: a felt mallet (0.1) lets only
    the low partials sound, a brass clapper (0.8) rings them all. mute (0..1) is a cloth or a hand on the bell, which
    chokes the upper partials and shortens all of them"""
    prime = f0 / 2.0
    freqs = prime * BELL * rng.uniform(0.9985, 1.0015, len(BELL))
    amps = BELL_AMP * np.exp(-freqs / (1300.0 + 5200.0 * hard)) * np.exp(-0.5 * mute * BELL)
    t60s = t60 * np.clip((prime / freqs) ** 0.55, 0.14, 1.6) / (1.0 + 1.5 * mute * (BELL - 0.5))
    y = ring(dur, freqs, t60s, amps, rng, doublet=0.003, doublet_amp=0.6, glide=0.003 * (1.0 - mute))
    t = tvec(dur)
    y = y * (1.0 - np.exp(-t / ((0.0008 + 0.006 * (1.0 - hard)) / 3.0)))  # a soft strike takes a few ms to speak
    cd = 0.03
    ct = tvec(cd)
    click = bp(white(cd, rng), 700, 2500 + 6000 * hard) * np.exp(-ct / (0.0015 + 0.002 * (1.0 - hard)))
    click = click * np.clip(ct / 0.0003, 0, 1)
    felt = lp(white(cd, rng), 500) * np.exp(-ct / 0.005) * np.clip(ct / 0.0006, 0, 1)
    out = nrm(y)
    out[:len(click)] += (0.05 + 0.3 * hard) * nrm(click) + (0.12 * (1.0 - hard) + 0.1 * mute) * nrm(felt)
    return out


def strings(dur, rng, notes, attack=0.4, release=0.6, voices=3):
    """a breath of bowed strings: each note is a few voices a few cents apart, each a bowed (sawtooth-like) tone with its
    own slow vibrato that comes in after the bow has started, the scrape of the bow in the air above them, swelling in and
    dying away; warmed with a lowpass and a body's two broad resonances"""
    n = ns(dur)
    t = tvec(dur)
    out = np.zeros(n)
    vib_in = np.clip((t - attack * 0.8) / 0.7, 0.0, 1.0)
    for f in notes:
        top = int(min(30, 5200.0 // f))
        for _ in range(voices):
            fi = f * 2.0 ** (rng.uniform(-8.0, 8.0) / 1200.0) * \
                (1.0 + rng.uniform(0.0025, 0.005) * vib_in * np.sin(2 * np.pi * rng.uniform(4.7, 5.5) * t +
                                                                    rng.uniform(0, 2 * np.pi)))
            ph = np.cumsum(2 * np.pi * fi / SR)
            for k in range(1, top + 1):
                out += np.sin(k * ph + rng.uniform(0, 2 * np.pi)) / k ** 1.15
    out = lp(out, 2600.0, order=2)
    out = eq(out, "peak", 300.0, 1.0, 2.0)
    out = eq(out, "peak", 2800.0, 1.0, 1.2)
    bow = bp(white(dur, rng), 1800.0, 6000.0) * smooth_random(dur, rng, 9.0, 0.4, 1.0)
    out = nrm(out) + 0.03 * nrm(bow)
    swell = np.sin(np.clip(t / attack, 0, 1) * np.pi / 2) ** 2
    away = np.cos(np.clip((t - (dur - release)) / release, 0, 1) * np.pi / 2) ** 2
    return hp(out, 80.0) * swell * away * (0.85 + 0.15 * smooth_random(dur, rng, 1.5))


# =========================================================================== paper, wax and the rest of the small things
def page_turn(rng, v=0):
    """a leaf of a bound book turned (dry): the corner caught up between finger and thumb (a dry crinkle), the leaf swung
    over (the air it pushes aside, the skin flexing, a fine flutter along its edge) and laid down with a soft flop and a
    settling rustle. v1 an ordinary leaf, v2 a quicker one"""
    dur = 0.5
    m = Mix(dur)
    sd = [0.2, 0.15][v]
    cd = 0.08
    m.add(nrm(crackle(cd, rng, 1500, decay=0.0006, f_lo=1800, f_hi=7500, size_var=0.8) * bell(cd, 0.45, 1.0, 1.5)), 0.0, 0.3)
    s = bell(sd, 0.4, 1.4, 1.4)
    air = bp(colored(sd, rng, -1.5), 500, 4800) * s ** 1.3
    flex = crackle(sd, rng, 650 * s + 10, decay=0.0007, f_lo=1500, f_hi=7000, size_var=0.8) * s
    flutter_ = bp(white(sd, rng), 2500, 7000) * s ** 1.6 * smooth_random(sd, rng, 70, 0.2, 1.0)
    m.add(nrm(nrm(air) + 0.7 * nrm(flex) + 0.3 * nrm(flutter_)), 0.04, 0.8)
    at = 0.04 + sd - 0.02  # the leaf comes down as the swing ends
    m.add(thud(0.14, rng, f=[190, 215][v], decay=0.014, tone=0.15), at, 0.45)
    m.add(nrm(bp(white(0.03, rng), 500, 3500) * np.exp(-tvec(0.03) / 0.005)), at, 0.4)
    rd = 0.14
    m.add(nrm(crackle(rd, rng, 500 * bell(rd, 0.2, 1.0, 1.6) + 10, decay=0.0008, f_lo=1200, f_hi=6000, size_var=1.0)),
          at + 0.01, 0.3)
    return fit(m.out(), ns(dur))


def seal_press(rng, v=0):
    """a seal pressed into a pool of warm wax on a page (dry): the stamp coming down on the soft wax (a dull, cushioned
    knock, the desk answering low under the paper), the wax squeezed out round its edge in a short wet squeeze with a
    pop or two in it, held a moment, then lifted, the wax pulling away from the metal with a tiny sticky tick and pop"""
    dur = 0.6
    m = Mix(dur)
    m.add(nrm(thud(0.2, rng, f=[115, 128][v], decay=0.035, tone=0.3)), 0.0, 0.9)
    m.add(nrm(knock(0.25, [165, 270, 430, 640], [0.06, 0.05, 0.035, 0.025], [1, .7, .45, .25], rng, lo=100, hi=1800,
                    ex_len=0.004)), 0.0, 0.45)
    sd = 0.2
    st = tvec(sd)
    squeeze = tvf(colored(sd, rng, -2.0), "bp", 950 * np.exp(-st / 0.07) + 230, 1.2) * np.clip(st / 0.012, 0, 1) * \
        np.exp(-st / 0.07)
    m.add(nrm(squeeze), 0.014, 0.5)
    for at, f in ((0.055, 360 + 40 * v), (0.115, 470 - 30 * v)):
        m.add(nrm(plop(0.05, rng, f0=f, size=0.45, fizz=0)), at, 0.1)
    lift = [0.3, 0.34][v]
    m.add(nrm(bp(white(0.014, rng), 700, 3200) * np.exp(-tvec(0.014) / 0.003)), lift, 0.3)
    m.add(nrm(plop(0.06, rng, f0=540, size=0.35, fizz=0)), lift + 0.005, 0.14)
    return lp(fit(m.out(), ns(dur)), 5500)


def flame_flutter(dur, rng, lo=180.0, hi=1100.0, rate=13.0):
    """a small flame burning in still air: soft low noise, its level wavering as the flame does"""
    s = bell(dur, 0.18, 1.2, 1.5)
    return nrm(bp(pink(dur, rng), lo, hi) * s * (0.35 + 0.65 * smooth_random(dur, rng, rate)))


# =========================================================================== the four stings
@effect("tale.begin", variants=2, subtitle="A tale begins", loud=-19.0, max_len=1.8, fade_out=0.5)
def tale_begin(v, rng):
    """a tale taken up: a quill scratching a few strokes at a page, then two soft bell notes a fifth apart, rising, with a
    breath of strings under the second, left to ring in a long room. v1 D5 to A5, v2 B4 to F#5"""
    dur = 2.4
    quill = Mix(0.4)
    strokes = [[(0.0, 0.07, 1.0), (0.10, 0.05, 0.8), (0.19, 0.09, 1.1)],
               [(0.02, 0.09, 1.0), (0.15, 0.06, 0.85), (0.25, 0.08, 1.1)]][v]
    for at, d, speed in strokes:
        quill.add(quill_stroke(d, rng, speed), at, rng.uniform(0.8, 1.0))
    m = Mix(dur)
    first, second = [("D5", "A5"), ("B4", "F#5")][v]
    t1 = [0.40, 0.44][v]
    t2 = t1 + [0.36, 0.40][v]
    m.add(bell_note(1.6, rng, hz(first), t60=1.1, hard=0.18), t1, 0.62)
    m.add(bell_note(1.9, rng, hz(second), t60=1.4, hard=0.15), t2, 0.9)
    pad = [("D3", "A3"), ("B2", "F#3")][v]
    m.add(strings(1.8, rng, [hz(n) for n in pad], attack=0.45, release=0.7), t2 - 0.08, 0.2)
    tonal = hall(lp(fit(m.out(), ns(dur)), 10000), wet=0.45)
    return lead_in(together(tonal, 0.5 * small_room(fit(quill.out(), ns(dur)), wet=0.08, rt60=0.4, seed=4811 + v)))


@effect("tale.step", variants=2, subtitle="A tale turns", loud=-19.0, max_len=1.0, fade_out=0.3)
def tale_step(v, rng):
    """a tale turning a leaf: one soft bell note, a page turned under it. v1 D5, v2 A4"""
    dur = 1.4
    m = Mix(dur)
    m.add(bell_note(1.2, rng, hz(["D5", "A4"][v]), t60=0.85, hard=0.2), 0.0, 0.85)
    tonal = hall(lp(fit(m.out(), ns(dur)), 10000), wet=0.4)
    page = Mix(dur)
    page.add(page_turn(rng, v), [0.14, 0.18][v], 0.4)
    return lead_in(together(tonal, small_room(fit(page.out(), ns(dur)), wet=0.07, rt60=0.38, seed=4821 + v)))


@effect("tale.end_good", variants=2, subtitle="A tale ends well", loud=-19.0, max_len=2.8, fade_out=0.7)
def tale_end_good(v, rng):
    """a tale ended well: three bell notes rising to the home note (D5), a warm chord of strings coming up under the last
    and held, and, when the bells have bloomed, a seal pressed into wax. v1 F#4 A4 D5, v2 B4 C#5 D5"""
    dur = 3.4
    m = Mix(dur)
    notes = [("F#4", "A4", "D5"), ("B4", "C#5", "D5")][v]
    starts = [(0.0, 0.21, 0.45), (0.0, 0.19, 0.42)][v]
    for k, (name, at) in enumerate(zip(notes, starts)):
        last = k == 2
        m.add(bell_note(2.6 if last else 1.4, rng, hz(name), t60=2.0 if last else 1.0, hard=0.18),
              at, 1.0 if last else 0.68 + 0.08 * k)
    m.add(strings(2.7, rng, [hz(n) for n in ("D3", "A3", "D4", "F#4", "A4")], attack=0.55, release=0.9), 0.4, 0.3)
    tonal = hall(lp(fit(m.out(), ns(dur)), 10000), wet=0.42)
    wax = Mix(dur)
    wax.add(seal_press(rng, v), [1.5, 1.55][v], 0.55)
    return lead_in(together(tonal, small_room(fit(wax.out(), ns(dur)), wet=0.08, rt60=0.4, seed=4831 + v)))


@effect("tale.end_ill", variants=2, subtitle="A tale ends in sorrow", loud=-21.0, max_len=2.6, fade_out=0.8)
def tale_end_ill(v, rng):
    """a tale ended in sorrow: one low bell, muffled with a cloth, a second note falling a minor third under it, and a
    hush; no seal. v1 D4 to B3, v2 A3 to F#3"""
    dur = 3.2
    first, second = [("D4", "B3"), ("A3", "F#3")][v]
    m = Mix(dur)
    m.add(bell_note(2.4, rng, hz(first), t60=1.5, hard=0.05, mute=0.55), 0.0, 0.95)
    m.add(bell_note(2.2, rng, hz(second), t60=1.7, hard=0.03, mute=0.5), [0.74, 0.8][v], 0.8)
    hd = 1.7
    hush = lp(bp(colored(hd, rng, -3.0), 220, 1600), 1400) * bell(hd, 0.4, 1.8, 1.6) ** 1.5
    m.add(nrm(hush), 0.95, 0.16)
    return lead_in(hall(lp(fit(m.out(), ns(dur)), 9000), wet=0.5))


# =========================================================================== the three in the street
@effect("tale.note_burn", variants=3, subtitle="A paper burns", loud=-22.0, max_len=2.4, fade_out=0.55, limit=3.0)
def tale_note_burn(v, rng):
    """a note fed to a fire and catching: the paper's edge taking the flame with a short dry rasp, the soft whump of the
    whole sheet alight at once, a thin crackle of it curling and blackening (fine at first, sparser as it goes), and the
    last of it settling to ash. v1 catches at once, v2 smoulders a moment first, v3 catches in one corner, then the rest"""
    dur = 2.7
    m = Mix(dur)
    rd = [0.15, 0.3, 0.17][v]
    rt = tvec(rd)
    edge = np.clip(rt / 0.012, 0, 1) * np.exp(-np.maximum(rt - rd * 0.45, 0) / (rd * 0.22))
    rasp = tvf(white(rd, rng), "bp", 2200 + 3800 * rt / rd, 1.0) * edge * (0.6 + 0.4 * smooth_random(rd, rng, 300))
    sizzle = crackle(rd, rng, 1400, decay=0.0004, f_lo=2500, f_hi=10000, size_var=0.7) * edge
    m.add(nrm(nrm(rasp) + 0.6 * nrm(sizzle)), 0.0, 0.5)
    for k, (at, size) in enumerate([[(0.1, 1.0)], [(0.27, 0.8)], [(0.09, 0.45), (0.44, 1.0)]][v]):
        wd = 0.5
        wt = tvec(wd)
        body = sine_sweep(wd, 150, 55, 4.0) * np.exp(-wt / 0.09) * (1.0 - np.exp(-wt / 0.012))
        air = lp(brown(wd, rng), 330, order=2) * bell(wd, 0.22, 1.6, 1.8)
        rush = tvf(pink(wd, rng), "bp", 300 * 2.0 ** (2.0 * bell(wd, 0.25, 1.4, 1.6)), 0.8) * bell(wd, 0.25, 1.4, 1.6) ** 1.5
        m.add(nrm(nrm(body) + 0.9 * nrm(air)), at, 0.7 * size)
        m.add(nrm(rush), at, 0.33 * size)
    cd = 1.9
    ct = tvec(cd)
    rate = [170, 120, 210][v] * np.exp(-ct / 0.55) + 8
    crk = crackle(cd, rng, rate, decay=0.0009, f_lo=1500, f_hi=9000, size_var=1.0) * (0.5 + 0.5 * np.exp(-ct / 0.8))
    m.add(nrm(crk), 0.2, 0.5)
    m.add(rustle(1.3, rng, peak=0.2, density=240, lo=1200, hi=6500) * np.exp(-tvec(1.3) / 0.6), 0.25, 0.22)
    sd = 0.3
    st = tvec(sd)
    ash = lp(pink(sd, rng), 1000) * np.clip(st / 0.02, 0, 1) * np.exp(-st / 0.09)
    m.add(nrm(ash), 1.95, 0.22)
    for at in (2.05, 2.28):
        m.add(nrm(crackle(0.05, rng, 700, decay=0.0006, f_lo=1500, f_hi=7000, size_var=0.5)), at, 0.12)
    return lead_in(small_room(lp(fit(m.out(), ns(dur)), 10500), wet=0.08, rt60=0.42, seed=4841 + v))


@effect("tale.candle_light", variants=2, subtitle="A candle is lit", loud=-24.0, max_len=1.3, fade_out=0.4)
def tale_candle_light(v, rng):
    """a candle lit from a match: the head drawn across the striker (a dry, gritty scrape that quickens), the flare of the
    sulphur catching and the little flame fluttering on the stick, the wick taking from it with a soft 'fp', and a warm low
    hum coming up under the new flame and dying away. v1 strikes at once, v2 on the second draw"""
    dur = 1.5
    m = Mix(dur)
    draws = [[(0.0, 0.11)], [(0.0, 0.05), (0.13, 0.11)]][v]
    for at, sd in draws:
        st = tvec(sd)
        crescendo = (st / sd) ** 1.3 * np.clip(st / 0.004, 0, 1)
        grit = tvf(white(sd, rng), "bp", 1500 + 4200 * st / sd, 1.1) * crescendo * (0.6 + 0.4 * smooth_random(sd, rng, 400))
        scratches = crackle(sd, rng, 650, decay=0.0004, f_lo=2000, f_hi=9000, size_var=0.8) * crescendo
        m.add(nrm(nrm(grit) + 0.5 * nrm(scratches)), at, 0.5 if len(draws) == 1 or at else 0.3)
    lit = draws[-1][0] + draws[-1][1]
    fd = 0.2
    ft = tvec(fd)
    flare = tvf(white(fd, rng), "bp", 5200 * np.exp(-ft / 0.06) + 1800, 0.8) * np.clip(ft / 0.003, 0, 1) * np.exp(-ft / 0.05)
    m.add(nrm(flare), lit, 0.5)
    m.add(nrm(lp(white(0.05, rng), 900) * np.exp(-tvec(0.05) / 0.01)), lit, 0.35)
    m.add(flame_flutter(0.5, rng, lo=300.0, hi=2200.0, rate=17.0), lit + 0.03, 0.17)
    wick = lit + 0.36
    wd = 0.2
    wt = tvec(wd)
    fp = tvf(colored(wd, rng, -1.5), "lp", 500 + 3200 * np.exp(-wt / 0.03), 0.9) * np.clip(wt / 0.01, 0, 1) * np.exp(-wt / 0.06)
    m.add(nrm(fp), wick, 0.7)
    m.add(nrm(crackle(0.25, rng, 90 * np.exp(-tvec(0.25) / 0.1) + 8, decay=0.0005, f_lo=1800, f_hi=9000, size_var=0.8)),
          wick + 0.01, 0.25)
    m.add(flame_flutter(0.6, rng), wick + 0.05, 0.14)
    hd = 0.8
    ht = tvec(hd)
    hum = (np.sin(2 * np.pi * hz("A2") * ht) + 0.55 * np.sin(2 * np.pi * hz("D3") * ht + 0.7) +
           0.25 * np.sin(2 * np.pi * hz("A3") * ht + 1.9)) * (0.85 + 0.15 * np.sin(2 * np.pi * 4.1 * ht))
    hum = hum * bell(hd, 0.2, 1.3, 1.6) ** 1.2
    m.add(nrm(hum), wick + 0.02, 0.2)
    return lead_in(small_room(lp(fit(m.out(), ns(dur)), 10500), wet=0.07, rt60=0.4, seed=4851 + v))


@effect("tale.cart_mend", variants=2, subtitle="A cart wheel is set", loud=-20.0, max_len=1.6, fade_out=0.35, limit=3.0)
def tale_cart_mend(v, rng):
    """a mended wheel dropped onto its axle: the hub grinding and creaking along the axle as it is pushed home, a wooden
    thunk as it seats (oak on oak, the tick of the iron cap and the linchpin dropping in), then the cart's weight coming onto
    it, the bed settling with two slow creaks and a last knock on its springs. v1 and v2 differ in the wood and the weight"""
    dur = 2.1
    m = Mix(dur)
    cd = [0.34, 0.3][v]
    m.add(nrm(creak(cd, rng, 35, 125, [240, 410, 640, 960, 1400], 0.06)), 0.0, 0.5)
    m.add(slide(cd, rng, lo=300, hi=1800, rate=180), 0.0, 0.3)
    seat = cd + 0.01
    f = [1.0, 1.07][v]
    m.add(nrm(knock(0.3, np.array([128, 205, 330, 520, 760]) * f, [0.12, 0.09, 0.06, 0.04, 0.03], [1, .8, .5, .3, .2],
                    rng, lo=80, hi=2500, ex_len=0.004)), seat, 0.9)
    m.add(nrm(thud(0.15, rng, f=100 * f, decay=0.03, tone=0.3)), seat, 0.7)
    m.add(nrm(knock(0.06, [2900, 4300], [0.02, 0.012], [1, .5], rng, lo=1500, hi=9000, ex_len=0.0006)), seat + 0.004, 0.22)
    pin = seat + [0.17, 0.2][v]
    m.add(nrm(knock(0.09, [1900, 2800, 4200], [0.03, 0.02, 0.012], [1, .6, .35], rng, lo=1000, hi=9000, ex_len=0.0008)),
          pin, 0.25)
    sd = 1.1
    st = tvec(sd)
    rock = 1.0 + 0.8 * np.exp(-st / 0.45) * np.sin(2 * np.pi * [2.3, 2.6][v] * st)
    settle = creak(sd, rng, 20, 60, [160, 300, 480, 720], 0.09) * rock * bell(sd, 0.3, 1.2, 1.6)
    m.add(nrm(settle), seat + 0.12, 0.42)
    for at, g in ((seat + 0.30, 0.35), (seat + 0.62, 0.2), (seat + 0.96, 0.1)):
        m.add(nrm(knock(0.12, np.array([180, 290, 450]) * f, [0.05, 0.04, 0.03], [1, .6, .35], rng, lo=100, hi=2000,
                        ex_len=0.003)), at, g)
    m.add(nrm(jingle(0.3, rng, 4, f_lo=1800, f_hi=4500, t60=(0.01, 0.03))), seat + 0.31, 0.08)
    return lead_in(street(lp(fit(m.out(), ns(dur)), 9500), wet=0.07, seed=4861 + v))


# =========================================================================== main
def write_fragments():
    """tales_sounds.json (the sounds.json entries) and tales_lang.json (their subtitles), which merge_lang.py merges"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        names = [f"guildmark:{sound_path(event, v + 1)}" for v in range(r["variants"])]
        entry = [{"name": n, "attenuation_distance": r["attenuation"]} for n in names] if r["attenuation"] else names
        sounds[f"townsfolk.{event}"] = {"sounds": entry, "subtitle": f"subtitles.guildmark.townsfolk.{event}"}
        lang[f"subtitles.guildmark.townsfolk.{event}"] = r["subtitle"]
    for name, data in (("tales_sounds.json", sounds), ("tales_lang.json", lang)):
        with open(os.path.join(HERE, name), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


GAP = 0.6


def listen_through(assets, names, path_):
    """one MP3 to listen to: every file just written, read back as the game will play it, a chapter mark at each"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        x = read_audio(os.path.join(assets, "sounds", name + ".ogg"))[0]
        marks.append((at, at + len(x) / SR + GAP, name))
        pieces += [x, np.zeros(ns(GAP))]
        at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark tales\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
        shutil.copyfile(mp3, path_)
    print(f"{path_}  {at:.1f}s, {len(marks)} sounds")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    a = ap.parse_args()
    sel = a.only.split(",") if a.only else None
    written = []
    for event, r in EVENTS.items():
        if sel and not any(event == s or event.startswith(s) for s in sel):
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            name = sound_path(event, v + 1)
            x, over, short = build_effect(event, r, v)
            x = write_checked(os.path.join(a.assets, "sounds", name + ".ogg"), x)
            if a.wav:
                write_wav(os.path.join(a.wav, name + ".wav"), x)
            note = (f"  limited {over:.1f} dB" if over > 0.05 else "") + (f"  {short:.1f} dB under (set by peak)"
                                                                          if short > 0.05 else "")
            print(f"{name:34s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s{note}", flush=True)
            written.append(name)
    write_fragments()
    if a.mp3:
        listen_through(a.assets, written, a.mp3)


if __name__ == "__main__":
    main()
