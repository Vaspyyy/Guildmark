"""Guildmark's three factions, the Starwatch, the Hearthsworn and the Quiet Hand: their sound effects, made from nothing.

usage (from the repo root):
    python3 -B art/audio/make_faction_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b] [--mp3 FILE]

Writes the effects as mono 44.1 kHz Ogg Vorbis to <assets>/sounds/faction/<name><n>.ogg for n = 1..variants; a name with
a dot is a group (ward_light.hum is faction/ward_light/hum1.ogg, expelled is faction/expelled1.ogg). --only takes names,
or a group such as ward_light (or ward_light.). --wav also writes WAV copies; --mp3 joins what was rendered into one
listen-through file, a chapter mark at each sound; a full run adds the faction members' new work sounds from
make_townsfolk_sfx.py (read back from disk), so the file is every new faction sound in order.

The Starwatch's ward-lights are the cold glassy sorcery of make_magic_audio.py (struck crystal, a hum of glass, frost) and
share its voices. The Hearthsworn are steel, wood, gravel, bells and fire (a horn, a hand of heavy chords, a wardfire
choked); the Quiet Hand are cloth, leather, coin and cord, close and dry, and a brass hand bell when it all goes wrong.
The renegade is stone and cold glass and a cold breath. Nothing is sampled: each sound is one `@recipe` that sets its
variants, length and loudness (as in make_magic_audio.py and make_dragon_audio.py), built from real materials' modes,
grains and envelopes with sfxkit.py, and every random number is seeded, so a rerun gives the same files.
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
# the sorcery's and the prayers' voices (struck crystal, bells, choirs, fire) and the combat sounds' steel, shared
from make_magic_audio import (COLD, MODES, WARM_SCALE, Bus, air_thump, bits, burst, chapel, chimes, choir, cold,  # noqa: E402
                              embers, flame, frost, glass, glasspad, mallet, nrm, note, rise_in, shards, shimmer, snap,
                              sparks, strike, tract, wander, warm_bell)
from make_sfx import clank  # noqa: E402
from make_townsfolk_sfx import coin, creak, grains, knock, lead_in, ring, small_room, thud  # noqa: E402

RECIPES = {}
FACTION_DIR = "faction"


def recipe(name, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, rms_db=None, limit_db=6.0):
    """one effect, written as faction/<name><n>.ogg for n = 1..variants (the same contract as make_magic_audio.py's).
    peak_db: where the file's peak sits: one-shots at -3 dBFS like the combat, dragon and magic sounds, the quiet ones
    (the hum, the lifted purse) lower, the soft swells a little under it.
    rms_db: the loudness a sharp sound should have (its RMS, with its peak at peak_db): it is soft-limited just enough
    to land there, but never by more than limit_db, so its crack stays a crack"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db, rms_db=rms_db, limit_db=limit_db)
        return fn
    return deco


def sound_path(name, n):
    """'ward_light.hum', 1 -> 'faction/ward_light/hum1'; 'expelled', 1 -> 'faction/expelled1'"""
    return f"{FACTION_DIR}/{name.replace('.', '/')}{n}"


def render_effect(name, v):
    """one variant of one effect, finished (levelled, trimmed, faded, peak at peak_db)"""
    r = RECIPES[name]
    x = lead_in(r["fn"](v, rng_for("faction/" + name, v)))  # (starting from zero, so that the file's high-pass adds no thump)
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
def rim(dur, rng, f0, kind="bowl", n=4, rate=1.8, tilt=1.1, beat=2.5):
    """a glass rim sung (a wet finger round a goblet): the bowl's modes held on, each a doublet that beats a few Hz and
    each wavering in level at a rate of its own, so the hum never quite stands still"""
    t = tvec(dur)
    out = np.zeros(len(t))
    for k, r in enumerate(MODES[kind][:n]):
        f = f0 * r * (1 + rng.uniform(-0.002, 0.002))
        wav = smooth_random(dur, rng, rate * rng.uniform(0.7, 1.4), 0.08, 1.0) ** 1.5
        b = beat * rng.uniform(0.6, 1.4) * (1 + 0.2 * k)
        out += (1 + k) ** -tilt * wav * (np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) +
                                         0.5 * np.sin(2 * np.pi * (f + b) * t + rng.uniform(0, 6.28)))
    return nrm(out)


def chime(f, rng, t60=0.5, attack=0.004, **kw):
    """a glass note struck softly: glass() with the front of it rounded off over `attack` seconds, the blow of a soft
    mallet and not a ping"""
    x = glass(f, rng, t60=t60, **kw)
    return x * np.sin(np.pi / 2 * np.clip(np.arange(len(x)) / SR / attack, 0, 1)) ** 2


def soft_chimes(dur, rng, rate, f_lo, f_hi, t60=(0.3, 0.8), scale=None, level=1.0, attack=0.004, beat=3.0):
    """a scatter of glass notes struck softly (chimes() of make_magic_audio.py, but with the front of each rounded off by
    `attack` seconds, so that they ring and do not tick): pings at random times, `rate` per second, at random pitches of
    the sorcery's open fifths"""
    total = ns(dur)
    scale = COLD if scale is None else scale
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (total,))
    lv = np.broadcast_to(np.asarray(level, dtype=float), (total,))
    m = Mix(dur)
    for i in np.nonzero(rng.random(total) < rate / SR)[0]:
        f = snap(float(np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))), scale)
        m.add(chime(f, rng, t60=rng.uniform(*t60) * (f / 3000.0) ** -0.4, attack=attack, kind="bowl", n=3, beat=beat,
                    click=0.0), i / SR, lv[i] * rng.uniform(0.35, 1.0))
    return fit(m.out(), total)


# =========================================================================== the Starwatch's ward-lights
@recipe("ward_light.hum", variants=2, peak_db=-14.0, fade_out=0.8, max_len=2.5, tail_db=-60.0)
def ward_light_hum(v, rng):
    """a ward-light hovering (it plays every few seconds): a soft hum of glass, open fifths of pure partials a few cents
    apart that waver against each other with the slow bob of the light, a sung rim's modes shimmering in and out above
    them and the faintest breath of air, swelling in and fading out so that one hum runs into the next (quiet)"""
    dur = 2.75
    n = ns(dur)
    t = tvec(dur)
    env = rise_in(t, 0.0, 0.8) * np.clip((dur - t) / 1.0, 0, 1) ** 1.4
    pair = [[note("E4"), note("B4")], [note("F#4"), note("C#5")]][v]
    m = Bus(dur)
    m.add(glasspad(dur, rng, pair, nh=4, tilt=2.1, vib=(0.8, 0.0025), detune=0.0035) * env, 0.0, 1.0)
    m.add(rim(dur, rng, pair[0] * 2, n=5, rate=1.6) * env ** 1.3, 0.0, 0.5)
    m.add(shimmer(dur, rng, 1700, 5600, count=7, rate=2.2) * env ** 1.5, 0.0, 0.26)
    m.add(tvf(colored(dur, rng, -1.5), "bp", 3000 * wander(dur, rng, 1.5, 0.15), 1.2) *
          smooth_random(dur, rng, 2.5, 0.3, 1.0) * env ** 2, 0.0, 0.07)
    return cold(fit(m.out(), n), 0.1, 0.9, seed=1011 + v)


@recipe("ward_light.pass", variants=2, fade_out=0.15, max_len=[0.42, 0.4], rms_db=-21.0)
def ward_light_pass(v, rng):
    """a blade or an arrow going through a ward-light and out the other side, harmlessly: a puff of air drawn through
    cold light, a shimmer of glassy partials scattering and settling back (rippling as the light closes over the gap),
    a glint or two"""
    dur = 0.47
    t = tvec(dur)
    s = bell(dur, 0.27, 1.3, 1.5)
    ripple = 1 - 0.45 * (0.5 + 0.5 * np.sin(phase_from_freq(30 * np.exp(-t / 0.3), len(t)) + 1.0))
    m = Bus(dur)
    m.add(whoosh(dur, rng, peak=0.27, f_lo=1500, f_hi=[6800, 7600][v], q=1.1, power=1.5, rise=1.0, fall=1.4, color=-1.0,
                 doppler=0.0), 0.0, 0.45)
    clim = 1 + [0.3, -0.28][v] * np.clip(t / 0.35, 0, 1)
    m.add(shimmer(dur, rng, [2300, 3300][v], [7400, 9000][v], count=10, rate=15.0, rise=clim) * s ** 1.2 * ripple, 0.0, 1.0)
    for i, f in enumerate([[COLD[7], COLD[9]], [COLD[10], COLD[8]]][v]):
        m.add(glass(f, rng, t60=0.2, kind="bowl", n=2, beat=6.0, click=0.03), 0.07 + 0.09 * i, 0.3 * (1 - 0.3 * i))
    return cold(fit(m.out(), ns(dur)), 0.12, 0.6, seed=1013 + v)


@recipe("ward_light.break", variants=3, fade_out=0.3, max_len=[0.95, 0.95, 0.95], rms_db=-19.0)
def ward_light_break(v, rng):
    """a ward-light put out by a spell: the pop of a skin of light bursting (a soft, hollow knock and the click of glass
    giving way), a cascade of splinters, and a chime of glass falling away down the open fifths, each note a little
    softer than the last, the last of the light shimmering down after"""
    dur = 1.05
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    m = Bus(dur)
    m.add(burst(0.03, rng, 1300, 11000, 0.0016), 0.0, 0.45)
    m.add(thump(0.1, [720, 640, 800][v], 300, rng, decay=0.011, click=0.0, noise=0.5, nlp=2200), 0.0, 0.7)
    m.add(shards(0.4, rng, 520 * np.exp(-tvec(0.4) / 0.07) + 25, f_lo=2400, f_hi=9000, t60=(0.015, 0.09)), 0.004, 0.55)
    seq = [[COLD[9], COLD[7], COLD[6], COLD[4], COLD[2]], [COLD[7], COLD[6], COLD[5], COLD[4], COLD[3], COLD[2]],
           [COLD[6], COLD[5], COLD[4], COLD[2], COLD[1]]][v]
    for i, f in enumerate(seq):
        at = 0.045 + 0.095 * i + rng.uniform(0, 0.015)
        m.add(chime(f * rng.uniform(0.998, 1.002), rng, t60=0.55 + 0.07 * i, attack=0.003, kind="bowl", n=3, beat=4.0,
                    click=0.0), at, 0.9 * 0.86 ** i)
    m.add(shimmer(dur, rng, 1800, 6500, count=7, rate=10.0, rise=1 - 0.4 * u) * bell(dur, 0.15, 1.2, 1.4), 0.0, 0.3)
    return cold(fit(m.out(), n), 0.18, 0.9, seed=1021 + v)


@recipe("ward_light.spawn", variants=1, peak_db=-5.0, fade_out=0.3, max_len=0.85, rms_db=-21.0)
def ward_light_spawn(v, rng):
    """a ward-light appearing: a breath drawn in and thinning to a point, a held glass tone swelling out of nothing and
    four soft chimes (a music box's tines) climbing the open fifths, the last left ringing"""
    dur = 0.95
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(tvf(colored(dur, rng, -1.2), "bp", 1200 * (5200 / 1200) ** np.clip(t / 0.45, 0, 1), 1.5) *
          bell(dur, 0.35, 1.4, 1.6) ** 1.2, 0.0, 0.3)
    m.add(glasspad(dur, rng, [note("E5"), note("B5")], nh=3, tilt=2.0, vib=(5.0, 0.002)) * rise_in(t, 0.0, 0.5) *
          np.clip((dur - t) / 0.35, 0, 1), 0.0, 0.7)
    for i, f in enumerate([COLD[0], COLD[2], COLD[4], COLD[6]]):
        m.add(chime(f, rng, t60=0.5 + 0.08 * i, attack=0.005, kind="soft", n=3, beat=3.0, click=0.0), 0.1 + 0.115 * i,
              0.55 + 0.15 * i)
    m.add(shimmer(dur, rng, 2200, 6000, count=6, rate=8.0, rise=1 + 0.35 * np.clip(t / 0.5, 0, 1)) *
          rise_in(t, 0.0, 0.4) * np.clip((dur - t) / 0.4, 0, 1), 0.0, 0.25)
    return cold(fit(m.out(), n), 0.16, 0.8, seed=1031)


@recipe("ward_light.shoot", variants=2, fade_out=0.1, max_len=[0.32, 0.32], rms_db=-19.0)
def ward_light_shoot(v, rng):
    """a summoned ward-light spitting a cold mote: a thin icy hiss of air torn open and falling away, a prickle of frost,
    the glint of glass at the light's edge and a very small push of air, no more than a zip"""
    dur = 0.34
    n = ns(dur)
    t = tvec(dur)
    fc = [8800, 9600][v] * (0.36 ** np.clip(t / 0.2, 0, 1) ** 0.8) * wander(dur, rng, 9.0, 0.03)
    zip_ = tvf(tvf(white(dur, rng), "bp", fc, 5.0), "bp", fc * 1.04, 5.0)
    zip_ = nrm(zip_) * np.sin(np.pi / 2 * np.clip(t / 0.005, 0, 1)) ** 2 * np.exp(-t / 0.09)
    m = Bus(dur)
    m.add(zip_, 0.0, 1.0)
    m.add(whoosh(dur, rng, peak=0.2, f_lo=1800, f_hi=5200, q=1.2, power=1.8, rise=0.9, fall=1.3, color=-1.5,
                 doppler=0.0), 0.0, 0.35)
    m.add(frost(dur, rng, 1500 * np.exp(-t / 0.07) + 60, decay=0.0004, lo=3500, hi=12000), 0.0, 0.5)
    m.add(chime([4900, 5600][v], rng, t60=0.14, attack=0.0015, kind="bar", n=2, beat=8.0, click=0.1), 0.0, 0.6)
    m.add(air_thump(0.1, rng, 150, 80, rise=0.008, decay=0.02, noise=0.8, nlp=500), 0.0, 0.2)
    return cold(fit(m.out(), n), 0.1, 0.4, seed=1033 + v)


# =========================================================================== the Quiet Hand: cloth, leather, coin, cord
def cloth(dur, rng, peak=0.4, lo=300.0, hi=3600.0, grit=700.0, rub=14.0):
    """cloth sliding over cloth or leather: a soft rush of band noise that swells and falls with the hand, rippled by the
    weave catching (rub, in Hz) and with a few fibres crackling in it. Nothing in it is sharp, which is what makes it cloth
    and not paper"""
    s = bell(dur, peak, 1.4, 1.6)
    body = bp(colored(dur, rng, -2.0), lo, hi) * (0.5 + 0.5 * smooth_random(dur, rng, rub))
    fibres = crackle(dur, rng, grit * s + 5, decay=0.0007, f_lo=1000, f_hi=4800, size_var=0.8)
    return nrm(body) * s ** 1.2 + 0.25 * nrm(fibres) * s


def slap(dur, rng, f_res=1200.0, t60=0.03, bright=0.8, weight=1.0):
    """a flat hand coming down on skin or leather: the snap of the palm and the pocket of air under it, the low give of the
    flesh"""
    t = tvec(dur)
    ex = white(dur, rng) * np.exp(-t / 0.004) * np.clip(t / 0.0004, 0, 1)
    pocket = resonators(ex, [f_res, f_res * 1.9], [t60, t60 * 0.6], [1.0, 0.25])
    m = Mix(dur)
    m.add(nrm(pocket), 0.0, 0.85)
    m.add(nrm(bp(ex, 550, 6500)), 0.0, 0.7)
    m.add(nrm(hp(ex, 2600) * np.exp(-t / 0.0015)), 0.0, 0.4 * bright)
    m.add(nrm(thump(0.07, 250, 150, rng, decay=0.01, click=0.0, noise=0.3, nlp=800)), 0.0, 0.35 * weight)
    return drive(nrm(fit(m.out(), ns(dur))), 1.8)


@recipe("pickpocket.lift", variants=2, peak_db=-12.0, fade_out=0.12, max_len=[0.42, 0.42], tail_db=-60.0)
def pickpocket_lift(v, rng):
    """a purse lifted: the soft rush of cloth as a hand slips into a fold and draws something out, a creak of leather
    under the fingers, closing round it, and two chinks of coin muffled in the purse, no more than a thought (quiet)"""
    dur = 0.46
    n = ns(dur)
    m = Bus(dur)
    m.add(cloth(0.25, rng, peak=0.5, lo=240, hi=2500, grit=450), 0.0, 1.0)
    m.add(cloth(0.17, rng, peak=0.4, lo=280, hi=2100, grit=280), 0.2, 0.5)
    m.add(creak(0.13, rng, 120, 380, [640 * (1 + 0.1 * v), 1090, 1740], 0.03), 0.07, 0.14)
    for at, f, g in [[(0.17, 3350, 0.34), (0.255, 3900, 0.24)], [(0.02, 3000, 0.22), (0.23, 3600, 0.34)]][v]:
        m.add(lp(coin(0.2, rng, f0=f, t60=0.08, bright=0.55, click=0.2), 4800), at, g)
    return small_room(fit(m.out(), n), wet=0.06, rt60=0.3, seed=1101 + v)


@recipe("pickpocket.caught", variants=1, fade_out=0.12, max_len=0.4, rms_db=-17.0)
def pickpocket_caught(v, rng):
    """a hand clamped on the thief's wrist: the sleeve snatched, a sharp rip of cloth, and the flat smack of the palm on
    the wrist, the cuff and a few coins in the dropped purse rattling after"""
    dur = 0.4
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    snatch = tvf(colored(dur, rng, -1.0), "bp", 2300 - 1200 * np.clip(t / 0.08, 0, 1), 0.9)
    snatch *= np.sin(np.pi / 2 * np.clip(t / 0.005, 0, 1)) ** 2 * np.exp(-t / 0.035)
    m.add(snatch, 0.0, 0.5)
    m.add(crackle(0.1, rng, 3200 * np.exp(-tvec(0.1) / 0.03) + 100, decay=0.0006, f_lo=900, f_hi=6500, size_var=0.7), 0.0, 0.4)
    m.add(slap(0.12, rng, f_res=1150, t60=0.028, bright=0.9, weight=1.1), 0.042, 1.7)
    m.add(cloth(0.15, rng, peak=0.2, lo=300, hi=2800, grit=600), 0.05, 0.3)
    m.add(jingle(0.25, rng, 7, f_lo=2400, f_hi=6800, t60=(0.02, 0.07), start=0.0, spread=0.12, amp_decay=0.12), 0.1, 0.22)
    return small_room(fit(m.out(), n), wet=0.07, rt60=0.32, seed=1103)


@recipe("purse.open", variants=2, fade_out=0.15, max_len=[0.82, 0.86], rms_db=-20.0)
def purse_open(v, rng):
    """a stolen purse opened: the drawstring loosened (cord rasping through leather and the mouth of the purse easing
    open), then the coins tipped out into a palm: a slither of them sliding, the first falling on the soft flesh, dull,
    then onto each other, brighter, the last few ringing and settling. v1 coppers, v2 silver and a few more"""
    dur = 0.9
    n = ns(dur)
    m = Bus(dur)
    for at, d, rate, g in [(0.0, 0.14, 800, 0.5), (0.16, 0.08, 450, 0.3)]:  # the cord drawn through, then eased
        s = bell(d, 0.55, 1.3, 1.4)
        m.add(crackle(d, rng, rate * s + 15, decay=0.0004, f_lo=1800, f_hi=6000, size_var=0.6) * s, at, g)
    m.add(cloth(0.26, rng, peak=0.4, lo=240, hi=2200, grit=250), 0.02, 0.5)
    m.add(creak(0.1, rng, 200, 520, [700, 1180, 1900], 0.025), 0.12, 0.1)
    start = 0.28
    m.add(jingle(0.45, rng, [10, 14][v], f_lo=2800, f_hi=8500, t60=(0.01, 0.04), start=0.0, spread=0.18, amp_decay=0.2),
          start, 0.3)
    count = [7, 9][v]
    at = start + 0.03
    for i in range(count):
        f = [rng.uniform(2500, 3500), rng.uniform(3300, 4800)][v]
        bright = 0.25 + 0.75 * i / (count - 1)  # onto the palm first, then onto the coins already there
        t60 = (0.04 + 0.1 * i / (count - 1)) * rng.uniform(0.8, 1.3)
        g = (1.0 - 0.45 * i / count) * rng.uniform(0.6, 1.0)
        m.add(lp(coin(0.3, rng, f0=f, t60=t60, bright=bright, click=0.25), 9000), at, 0.5 * g)
        m.add(thud(0.05, rng, f=rng.uniform(150, 230), decay=0.008, tone=0.2), at, 0.1 * g * (1 - bright))
        at += ([0.028, 0.022][v] + [0.01, 0.008][v] * i) * rng.uniform(0.7, 1.3)  # the coins slowing as the purse empties
    return small_room(fit(m.out(), n), wet=0.07, rt60=0.32, seed=1105 + v)


# =========================================================================== the heist: a bowed string, a brass bell, a chest
def bowed(dur, rng, f, a, vib=(5.2, 0.004), mute=2200.0, hair=0.2, noise=0.06, nh=30):
    """a bowed string, low and muted: the bow drags the string into a sawtooth (harmonic k at 1/k^p, p steeper the softer
    it is played, so the loud passages are the bright ones), the level of each harmonic rocking a little as the hair grips
    and slips; the body gives it a throat (resonances near 270, 460 and 1100 Hz), the mute takes the top off, and the
    bow's own noise rides on it. f: the pitch in Hz and a: the bow's pressure 0..1, each a number or a curve per sample"""
    n = ns(dur)
    t = tvec(dur)
    f = np.broadcast_to(np.asarray(f, dtype=float), (n,))
    a = np.broadcast_to(np.asarray(a, dtype=float), (n,))
    fv = f * (1 + vib[1] * np.clip((t - 0.25) / 0.4, 0, 1) * np.sin(2 * np.pi * vib[0] * t + rng.uniform(0, 6.28))) * \
        wander(dur, rng, 2.0, 0.0015)
    ph = phase_from_freq(fv, n)
    y = np.zeros(n)
    p = 0.6 + 1.1 * (1 - a)
    for k in range(1, nh + 1):
        if float(np.max(fv)) * k > 7000:
            break
        grip = 1 + hair * (2 * smooth_random(dur, rng, 8 + 0.5 * k, 0.0, 1.0) - 1)
        y += np.sin(k * ph + rng.uniform(0, 6.28)) * k ** -p * grip
    y = eq(eq(eq(nrm(y), "peak", 270, 3.0, 6.0), "peak", 460, 3.0, 4.0), "peak", 1100, 2.0, 3.0)
    y = lp(hp(y, 100, order=2), mute, order=2) * a
    bow = bp(white(dur, rng), 900, 4200) * smooth_random(dur, rng, 14, 0.4, 1.0) * a ** 1.5
    return y + noise * nrm(bow) * np.max(np.abs(y))


@recipe("heist.suspicion", variants=1, peak_db=-5.0, fade_out=0.2, max_len=1.0, rms_db=-21.0)
def heist_suspicion(v, rng):
    """a watcher stopping to look round: a low swell of bowed strings, muted, a tritone (B and F) that creeps a little
    sharp as it swells, the bow's hair gripping and the pitch wavering a touch, cut off short at the crest as the head
    turns. No tune: only a held breath"""
    dur = 1.1
    n = ns(dur)
    t = tvec(dur)
    u = np.clip(t / 0.85, 0, 1)
    a = (0.12 + 0.88 * u ** 1.5) * np.clip((dur - t) / 0.2, 0, 1)
    creep = 1 + 0.028 * u ** 1.5
    m = Bus(dur)
    for f, g, at in [(123.47, 1.0, 0.0), (174.61, 0.8, 0.07)]:
        for det in (-0.004, 0.004):
            m.add(bowed(dur, rng, f * (1 + det) * creep, a * rise_in(t, at, 0.3), noise=0.05 + 0.1 * (f > 150)), 0.0, g)
    m.add(np.sin(phase_from_freq(61.7 * creep, n)) * a ** 2, 0.0, 0.05)
    return reverb(fit(m.out(), n), wet=0.16, rt60=0.9, predelay=0.012, hf_damp=0.75, seed=1111, early_span=0.04)


def hand_bell(dur, rng, f0, strikes, t60=1.0, brass=1.0):
    """a brass hand bell, rung: strikes is a list of (time, strength, side). Every blow of the clapper is a short burst of
    noise that sets the bell's modes ringing (the partials of a small bell, 0.5, 1, 1.18, 1.5, 2, 2.5 ... times the strike
    tone, each a pair a few Hz apart, the upper ones dying first), the clapper hitting alternately one side of the lip and
    the other, which lights the modes in different proportions, and its own tick and the clank of the brass itself on top"""
    n = ns(dur)
    ratios = np.array([0.5, 1.0, 1.183, 1.506, 2.0, 2.514, 2.662, 3.011, 4.166, 5.433, 6.796, 8.215])
    amps = np.array([0.5, 1.0, 0.45, 0.5, 0.55, 0.35, 0.3, 0.3, 0.2, 0.14, 0.09, 0.06])
    decays = t60 / (1 + 0.45 * np.arange(len(ratios)))
    freqs = f0 * ratios * (1 + rng.uniform(-0.003, 0.003, len(ratios)))
    sides = [amps * rng.uniform(0.6, 1.4, len(ratios)) for _ in range(2)]
    exc = [np.zeros(n), np.zeros(n)]
    click = np.zeros(n)
    for tt, s, side in strikes:
        i = ns(tt)
        e = bp(white(0.0009, rng), 500, 9000) * np.hanning(ns(0.0009) + 2)[1:-1]
        exc[side][i:i + len(e)] += s * e[:n - i]
        c = bp(white(0.006, rng), 1800 + 3000 * brass, 11000) * np.exp(-tvec(0.006) / 0.0012)
        click[i:i + len(c)] += s * c[:n - i]
    y = np.zeros(n)
    for side in range(2):
        y += resonators(exc[side], freqs, decays, sides[side])
        y += 0.6 * resonators(exc[side], freqs * 1.0035, decays * 0.9, sides[side])
    return nrm(y) + 0.1 * nrm(click)


@recipe("heist.alarm", variants=1, fade_out=0.4, max_len=2.6, tail_db=-60.0, rms_db=-13.0)
def heist_alarm(v, rng):
    """the alarm raised: a brass hand bell rung hard and fast, the clapper hammering both sides of the lip some ten times a
    second in uneven strokes, the ringer's arm getting up to speed, the strokes crowding into one hard clangour, then easing
    off as the arm tires and the bell ringing out. Carries (a hall's slap round it)"""
    dur = 2.9
    strikes = []
    tt, i = 0.0, 0
    while tt < 2.0:
        slow = 1.0 + 1.6 * np.clip((tt - 1.6) / 0.4, 0, 1)  # the last strokes come further apart as the arm tires
        up = min(1.0, 0.45 + tt / 0.3)  # the first strokes lighter, the ringer's arm getting up to speed
        strikes.append((tt, up * rng.uniform(0.55, 1.0) * (1.25 if i % 4 == 0 else 1.0), i % 2))
        if rng.random() < 0.25:  # the clapper bouncing off the lip and striking again
            strikes.append((tt + rng.uniform(0.018, 0.03), 0.4 * up * rng.uniform(0.6, 1.0), (i + 1) % 2))
        tt += 0.082 * slow * (1 + 0.14 * (1 if i % 2 == 0 else -1)) * (1 + 0.16 * rng.standard_normal())
        i += 1
    y = hand_bell(dur, rng, 1180.0, strikes, t60=1.0)
    y = drive(y, 1.5)
    return reverb(y, wet=0.16, rt60=1.0, predelay=0.012, hf_damp=0.4, seed=1113, early_span=0.04)


@recipe("heist.loot", variants=1, peak_db=-4.0, fade_out=0.35, max_len=1.0, rms_db=-21.0)
def heist_loot(v, rng):
    """the prize taken from the chest: the soft rustle of a hand closing on it, a muffled clink as it comes up (small metal
    in cloth: its high end gone), a soft low bell blooming a moment after, and one more small clink as it is tucked away"""
    dur = 1.15
    n = ns(dur)
    m = Bus(dur)
    m.add(cloth(0.16, rng, peak=0.5, lo=260, hi=2800, grit=300), 0.0, 0.5)
    m.add(lp(coin(0.25, rng, f0=2100, t60=0.09, bright=0.5, click=0.2), 3200), 0.1, 0.7)
    m.add(lp(coin(0.2, rng, f0=2500, t60=0.06, bright=0.4, click=0.2), 3000), 0.27, 0.35)
    m.add(warm_bell(note("D4"), rng, t60=1.1, fc=2200, attack=0.007, hum=0.5), 0.13, 1.0)
    m.add(warm_bell(note("A4"), rng, t60=0.7, fc=2000, attack=0.008), 0.15, 0.22)
    return chapel(fit(m.out(), n), 0.14, 1.1, seed=1115)


# =========================================================================== the Hearthsworn: steel, gravel, brass, a wardfire
def yard(x, wet=0.13, seed=1201):
    """a walled training yard: stone on three sides and open sky, so a quick slap back and a short bright tail"""
    return reverb(x, wet=wet, rt60=0.8, predelay=0.016, hf_damp=0.4, seed=seed, early_span=0.05)


def night(x, wet=0.22, rt60=2.2, seed=1301, echoes=((0.46, 0.12), (1.02, 0.06))):
    """open country at night: a long dark tail and a hill or two giving the sound back, late and soft"""
    y = reverb(x, wet=wet, rt60=rt60, predelay=0.04, hf_damp=0.65, seed=seed, early_span=0.1)
    m = Mix(len(y) / SR)
    m.add(y, 0.0, 1.0)
    for i, (d, g) in enumerate(echoes):
        e = reverb(lp(hp(x, 90), 1900 / (1 + 0.5 * i)), wet=0.6, rt60=0.9, predelay=0.01, hf_damp=0.7, seed=seed + 1 + i,
                   dry=0.5)
        m.add(e, d, g)
    return m.out()


@recipe("spar.salute", variants=1, fade_out=0.2, max_len=0.65, rms_db=-19.0)
def spar_salute(v, rng):
    """two blades tapped together before a bout: the flats meeting (a small, hard tick and the tonk of steel on steel), each
    blade ringing out on its own note a little apart from the other's so that the pair shimmer, a second tiny touch as they
    bounce apart, the yard giving it a short bright tail"""
    dur = 0.78
    n = ns(dur)
    m = Bus(dur)
    for f, g in [(1245.0, 1.0), (1325.0, 0.8)]:
        m.add(clank(0.7, f, rng, "blade", n=12, t60_lo=0.55, t60_hi=0.1, tilt=0.5, beat=0.003), 0.0005, g)
    m.add(burst(0.012, rng, 2200, 14000, 0.0012), 0.0, 0.35)
    m.add(knock(0.06, [880, 1520, 2300], [0.012, 0.009, 0.006], [1, .6, .3], rng, lo=500, hi=6000, ex_len=0.0008), 0.0, 0.3)
    m.add(clank(0.4, 1252.0, rng, "blade", n=10, t60_lo=0.3, t60_hi=0.06, tilt=0.6, beat=0.003), 0.042, 0.3)
    m.add(burst(0.008, rng, 2500, 14000, 0.001), 0.042, 0.15)
    return yard(fit(m.out(), n), 0.14, seed=1201)


@recipe("spar.yield", variants=1, fade_out=0.3, max_len=1.05, rms_db=-19.0)
def spar_yield(v, rng):
    """a bout won: the loser sets the point of his sword down into the ground, a dull thunk in the earth and the crunch of
    gravel parting, the blade left standing and shivering (a clamped bar's modes, the slow wobble of it on the sound), and
    then he goes down on one knee, a soft thud and gravel scuffing under it, the leather creaking"""
    dur = 1.2
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(thud(0.18, rng, f=85, decay=0.03, tone=0.45), 0.0, 1.0)
    m.add(crackle(0.16, rng, 4500 * np.exp(-tvec(0.16) / 0.04) + 100, decay=0.0009, f_lo=300, f_hi=3800, size_var=0.9),
          0.0, 0.6)
    f1 = 25.0  # the blade's lowest mode, too low to hear but it makes the rest waver
    modes = f1 * np.array([6.267, 17.55, 34.39, 56.84, 84.9])
    shiver = knock(1.0, modes, [0.9, 0.7, 0.5, 0.35, 0.25], [1, .8, .55, .35, .2], rng, lo=150, hi=5000, ex_len=0.003)
    shiver = fit(shiver, n) * (1 - 0.4 * np.exp(-t / 0.45) * (0.5 + 0.5 * np.sin(2 * np.pi * f1 * t + 1.0)))
    m.add(shiver, 0.002, 0.8)
    m.add(thud(0.14, rng, f=120, decay=0.025, tone=0.3), 0.3, 0.5)
    sc = bell(0.34, 0.35, 1.3, 1.6)
    m.add(crackle(0.34, rng, 650 * sc + 15, decay=0.0014, f_lo=400, f_hi=3600, size_var=1.1) * sc, 0.29, 0.55)
    m.add(grains(0.4, rng, 16, 0.0, 0.1, 700, 3800, t60=(0.01, 0.03), amp_decay=0.25), 0.29, 0.35)
    m.add(cloth(0.22, rng, peak=0.4, lo=300, hi=2600, grit=350), 0.3, 0.3)
    m.add(creak(0.14, rng, 100, 300, [540, 900, 1500], 0.03), 0.31, 0.12)
    return yard(fit(m.out(), n), 0.12, seed=1203)


def horn(dur, rng, f, a, vib=(5.0, 0.0035), nh=28):
    """a low brass horn: the lips buzz a pulse train and the louder the player blows the more the upper harmonics grow
    (harmonic k stands at a^(1 + 0.16 (k - 1)): dark at a murmur, brazen at full blast; the lowest is the weak one, as in a
    horn), the flare of the bell takes the top off above two or three kHz, the bore gives it a throat near 650 Hz, and the buzz
    of the lips rides on it. f is the pitch in Hz and a the player's breath, 0..1, each a number or a curve per sample"""
    n = ns(dur)
    t = tvec(dur)
    f = np.broadcast_to(np.asarray(f, dtype=float), (n,))
    a = np.clip(np.broadcast_to(np.asarray(a, dtype=float), (n,)), 0.0, 1.0)
    fv = f * (1 + vib[1] * np.clip((t - 0.9) / 0.6, 0, 1) * np.sin(2 * np.pi * vib[0] * t + rng.uniform(0, 6.28))) * \
        wander(dur, rng, 3.0, 0.0025)
    ph = phase_from_freq(fv, n)
    y = np.zeros(n)
    for k in range(1, nh + 1):
        if float(np.max(fv)) * k > 9000:
            break
        lip = 1 + 0.12 * (2 * smooth_random(dur, rng, 11.0 + k, 0.0, 1.0) - 1) if k > 4 else 1.0
        y += (0.5 if k == 1 else 1.0) * k ** -0.6 * a ** (1.0 + 0.16 * (k - 1)) * lip * np.sin(k * ph + rng.uniform(0, 6.28))
    y = eq(eq(eq(nrm(y), "peak", 650, 1.0, 5.0), "peak", 1500, 1.2, 3.0), "highshelf", 2800, 0.7, -9.0)
    buzz = bp(white(dur, rng), 500, 3500) * a ** 2 * smooth_random(dur, rng, 40, 0.3, 1.0)
    return y + 0.04 * nrm(buzz) * np.max(np.abs(y))


@recipe("hold.horn", variants=1, peak_db=-3.0, fade_out=0.6, max_len=3.1, tail_db=-66.0, rms_db=-17.0)
def hold_horn(v, rng):
    """the Hearthsworn's horn calling a wave out of the dark: one low note, taken soft and swelling (the lips scooping up
    into the pitch), then up a fifth with a slur and brighter, held and wavering at the top of its breath and let go, a
    brass that carries over open ground at night, the hills giving it back"""
    dur = 3.3
    n = ns(dur)
    t = tvec(dur)
    w = np.clip((t - 1.25) / 0.12, 0, 1)
    w = w * w * (3 - 2 * w)  # the slur from the first note to the second
    scoop = 1 - 0.035 * np.exp(-t / 0.06) - 0.03 * np.exp(-np.maximum(0, t - 1.25) / 0.05) * (t > 1.25)
    f = 110.0 * (164.81 / 110.0) ** w * scoop * (1 - 0.012 * np.clip((t - 2.3) / 0.3, 0, 1))
    pts = [(0, 0.0), (0.04, 0.2), (0.4, 0.42), (1.15, 0.62), (1.27, 0.55), (1.45, 0.82), (1.95, 1.0), (2.35, 0.7),
           (2.75, 0.0), (dur, 0.0)]
    a = np.clip(uniform_filter1d(env_points(pts, dur), size=ns(0.03)), 0, 1)
    m = Bus(dur)
    for det, g in [(0.0, 1.0), (0.0035, 0.5)]:
        m.add(horn(dur, rng, f * (1 + det), a), 0.0, g)
    m.add(bp(white(0.15, rng), 600, 3000) * np.exp(-tvec(0.15) / 0.05), 0.0, 0.08)  # the first breath into the horn
    return night(fit(m.out(), n), 0.22, 2.2, seed=1301)


@recipe("hold.drain", variants=2, fade_out=0.2, max_len=[0.72, 0.74], rms_db=-17.0)
def hold_drain(v, rng):
    """Hollows clawing the Wardfire down: a hollow whoomph (a low push of air in a chest that is empty, the flame flaring
    as it is beaten and then choked, its roar closing down to nothing in a quarter of a second), the hiss of it going
    out under their hands, claws raking embers, a few hot coals snapping"""
    dur = 0.85
    n = ns(dur)
    t = tvec(dur)
    s = np.clip(t / 0.012, 0, 1) * np.exp(-np.maximum(0, t - 0.02) / [0.075, 0.09][v])
    fc = 3200 * np.exp(-t / 0.1) + 220  # the flame's roar closing down, brighter highs gone first
    m = Bus(dur)
    m.add(eq(air_thump(0.4, rng, [96, 88][v], 40, rise=0.01, decay=0.09, noise=0.8, nlp=300), "peak", 170, 3.0, 8.0), 0.0, 1.0)
    roar = flame(dur, rng, s, lo=110, hi=3000, core=(350, 900), hiss=0.1, flutter_hz=[20, 24][v]) * s
    m.add(tvf(roar, "lp", fc, 0.7), 0.0, 0.9)
    m.add(bp(white(dur, rng), 2500, 9000) * np.clip((t - 0.03) / 0.06, 0, 1) * np.exp(-t / 0.2), 0.0, 0.4)
    for at, d, g in [[(0.01, 0.07, 0.5), (0.13, 0.08, 0.4), (0.26, 0.06, 0.3)], [(0.02, 0.06, 0.5), (0.1, 0.06, 0.35),
                                                                                  (0.19, 0.07, 0.4), (0.3, 0.05, 0.25)]][v]:
        sc = bell(d, 0.4, 1.2, 1.5)
        m.add(crackle(d, rng, 1800 * sc + 30, decay=0.0006, f_lo=500, f_hi=3800, size_var=0.8) * sc, at, g)
    m.add(embers(dur, rng, 500 * np.exp(-t / 0.2) + 30, lo=1500, hi=7500, pops=0.8) * np.clip(t / 0.03, 0, 1), 0.0, 0.5)
    return reverb(fit(m.out(), n), wet=0.1, rt60=0.7, predelay=0.01, hf_damp=0.5, seed=1305 + v, early_span=0.03)


@recipe("hold.dawn", variants=1, peak_db=-4.0, fade_out=0.7, max_len=3.1, tail_db=-66.0, rms_db=-19.0)
def hold_dawn(v, rng):
    """the night held: a warm swell rising out of the dark, a small choir opening from 'oo' to 'ah' note by note up a D
    major chord, a low organ-like warmth under it and the glow of a fire taking, and at the top of it one bright bell
    stroke, ringing clear over the swell as it settles, a few small bells falling like the first light"""
    dur = 3.5
    n = ns(dur)
    t = tvec(dur)
    u = np.clip(t / 1.85, 0, 1)
    swell = rise_in(t, 0.0, 1.85) ** 1.3 * np.clip((dur - t) / 1.2, 0, 1)
    m = Bus(dur)
    for f, at in [(note("D3"), 0.0), (note("A3"), 0.25), (note("D4"), 0.5), (note("F#4"), 0.8), (note("A4"), 1.1)]:
        c = choir(dur, rng, [f], va="u", vb="a", curve=u, glide=1 + 0.006 * u, vib_delay=0.5, breath=0.1, lpf=5500.0)
        m.add(c * rise_in(t, at, 0.9) * swell, 0.0, 1.0)
    m.add((np.sin(2 * np.pi * note("D2") * t) + 0.5 * np.sin(2 * np.pi * note("D3") * t)) * swell, 0.0, 0.4)
    g = rise_in(t, 0.2, 1.6) * np.clip((dur - t) / 1.2, 0, 1)
    m.add(flame(dur, rng, g, lo=100, hi=2000, core=(300, 800), hiss=0.05) * g, 0.0, 0.25)
    m.add(embers(dur, rng, 90 * g + 5, lo=1500, hi=7000, pops=0.3), 0.0, 0.15)
    at = 1.85  # the bell stroke
    m.add(warm_bell(note("D6"), rng, t60=2.0, fc=6500, attack=0.002), at, 1.0)
    m.add(strike(note("D5"), rng, dur=0.5, t60=0.3), at, 0.4)
    m.add(mallet(0.1, rng, f=360, decay=0.012), at, 0.3)
    td = dur - at - 0.3
    m.add(chimes(td, rng, 9 * np.exp(-tvec(td) / 0.9) + 1, 1500, 5200, t60=(0.4, 1.0), kind="soft", n=3, scale=WARM_SCALE,
                 level=np.exp(-tvec(td) / 1.0), beat=3.0, click=0.02), at + 0.25, 0.35)
    return chapel(fit(m.out(), n), 0.3, 2.4, seed=1307)


# =========================================================================== ranks and the cast out: chimes, bells, a string, a door
def pluck(dur, rng, f, t60=1.6, bright=0.5, pos=0.28):
    """a plucked string (Karplus-Strong): a burst of noise, softened by `bright` (a fingertip is soft, a nail bright), set
    circulating in a loop one period long, each trip through a two-point average that takes the top off (so the high
    harmonics die first, as on a real string) and a loss that sets how long it rings (t60). pos is where along the string it
    was plucked: the burst is combed so that the harmonics with a node there are missing"""
    N = int(round(SR / f - 0.5))
    ex = lp(white(N / SR, rng), 700 + 6500 * bright)[:N] * np.sin(np.pi / 2 * np.clip(np.arange(N) / ns(0.003), 0, 1)) ** 2
    k = max(1, int(pos * N))
    ex = ex - np.concatenate([np.zeros(k), ex[:-k]])
    x = np.zeros(ns(dur))
    x[:N] = ex / (np.max(np.abs(ex)) + 1e-12)
    c = 10 ** (-3 * (N / SR) / t60)
    a = np.zeros(N + 2)
    a[0], a[N], a[N + 1] = 1.0, -c / 2, -c / 2
    return nrm(signal.lfilter([1.0], a, x))


def guitar_body(x):
    """the box a string is plucked over: the air in it and its top plate answering near 110 and 210 Hz, the highs rolled
    off as by soft fingers"""
    return lp(eq(eq(x, "peak", 110, 1.8, 4.0), "peak", 210, 2.2, 3.0), 4200)


@recipe("rank.starwatch", variants=1, peak_db=-4.0, fade_out=0.5, max_len=2.0, rms_db=-19.0)
def rank_starwatch(v, rng):
    """joined or rose in the Starwatch: a rising crystalline chime chord, six glass notes climbing the open fifths in a
    breath (E, B, F sharp, C sharp, E, B) each left to ring so that they stack into one chord, a held glass tone swelling
    under it, a cold shimmer climbing and a few last chimes scattering as it fades"""
    dur = 2.5
    n = ns(dur)
    t = tvec(dur)
    run = [note("E4"), note("B4"), note("F#5"), note("C#6"), note("E6"), note("B6")]
    m = Bus(dur)
    for i, f in enumerate(run):
        m.add(chime(f, rng, t60=1.9 - 0.2 * i, attack=0.003, kind="bowl", n=4, beat=3.0 + 0.5 * i, click=0.04),
              0.02 + 0.115 * i, 0.8 + 0.08 * i)
    m.add(glasspad(dur, rng, run[:4], nh=3, tilt=1.9, vib=(4.8, 0.002)) * rise_in(t, 0.3, 0.7) *
          np.exp(-np.maximum(0, t - 0.9) / 0.9), 0.0, 0.6)
    m.add(shimmer(dur, rng, 2500, 7500, count=8, rate=7.0, rise=1 + 0.3 * np.clip(t / 0.8, 0, 1)) *
          rise_in(t, 0.1, 0.5) * np.exp(-np.maximum(0, t - 0.8) / 0.8), 0.0, 0.25)
    td = dur - 0.9
    m.add(chimes(td, rng, 14 * np.exp(-tvec(td) / 0.6) + 1, 1300, 6500, t60=(0.4, 1.2), kind="bowl", n=3, scale=COLD,
                 level=np.exp(-tvec(td) / 0.9), beat=3.0), 0.8, 0.4)
    return cold(fit(m.out(), n), 0.24, 1.6, seed=1401)


@recipe("rank.hearthsworn", variants=1, peak_db=-4.0, fade_out=0.5, max_len=2.0, rms_db=-19.0)
def rank_hearthsworn(v, rng):
    """joined or rose in the Hearthsworn: a warm bell chord, D major struck low to high in a breath (a deep bell a
    mallet's blow below it), ringing over a fire's crackle and the low breath of its flame, the sparks thinning as the bells
    die away"""
    dur = 2.3
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    for i, (nm, g, t60) in enumerate([("D3", 0.9, 2.2), ("D4", 1.0, 1.9), ("A4", 0.85, 1.7), ("D5", 0.8, 1.5),
                                      ("F#5", 0.6, 1.3), ("A5", 0.5, 1.1)]):
        m.add(warm_bell(note(nm), rng, t60=t60, fc=3400, attack=0.004, hum=0.6 if i == 0 else 1.0),
              0.022 * i + rng.uniform(0, 0.006), 1.5 * g)
    m.add(mallet(0.1, rng, f=170, decay=0.015, tone=0.5), 0.0, 0.5)
    s = np.exp(-t / 1.3) * np.clip(t / 0.05, 0, 1)
    m.add(flame(dur, rng, s, lo=90, hi=1900, core=(300, 700), hiss=0.06, flutter_hz=18.0) * s, 0.0, 0.16)
    m.add(embers(dur, rng, 230 * np.exp(-t / 0.9) + 25, lo=1400, hi=8000, pops=0.5), 0.0, 0.28)
    return chapel(fit(m.out(), n), 0.24, 1.8, seed=1309)


@recipe("rank.quiet_hand", variants=1, peak_db=-4.0, fade_out=0.4, max_len=2.0, rms_db=-21.0)
def rank_quiet_hand(v, rng):
    """joined or rose in the Quiet Hand: a soft low plucked figure, A minor (A, C, E climbing, then a high A left
    to ring), strung with fingertips on a low string and a little woody, a pause, and a coin flicked up off a thumb, ringing
    as it turns in the air (its pair of modes beating) and caught in the palm, the ring cut dead"""
    dur = 2.3
    n = ns(dur)
    m = Bus(dur)
    for f, at, g, t60 in [(note("A2"), 0.0, 1.0, 1.5), (note("C3"), 0.17, 0.85, 1.3), (note("E3"), 0.31, 0.8, 1.3),
                          (note("A3"), 0.62, 0.9, 1.7)]:
        p = guitar_body(pluck(dur, rng, f * rng.uniform(0.999, 1.001), t60=t60, bright=0.3))
        m.add(p, at, g)
        m.add(thud(0.04, rng, f=rng.uniform(190, 260), decay=0.006, tone=0.2), at, 0.1 * g)  # the fingertip
    at = 1.0
    flick = coin(1.2, rng, f0=3750.0, t60=0.9, bright=0.9, click=0.5)
    catch = 1.62
    flick = flick[:ns(catch - at + 0.02)] * np.clip((catch - at - np.arange(ns(catch - at + 0.02)) / SR) / 0.012, 0, 1)
    m.add(flick, at, 0.45)
    m.add(slap(0.07, rng, f_res=900, t60=0.015, bright=0.4, weight=0.5), catch, 0.35)
    m.add(cloth(0.08, rng, peak=0.4, lo=300, hi=2400, grit=200), catch, 0.12)
    return small_room(fit(m.out(), n), wet=0.1, rt60=0.6, seed=1403)


@recipe("expelled", variants=1, fade_out=0.5, max_len=1.55, tail_db=-64.0, rms_db=-20.0, limit_db=9.0)
def expelled(v, rng):
    """thrown out of a faction: a heavy door slammed in a stone hall, the boom of the oak in its frame, the panels'
    knock and the iron of the latch and hinges, then, left behind, a low dull tone (a bell with the top taken off, swelling in
    after the blow) that hangs and sinks"""
    dur = 1.7
    n = ns(dur)
    m = Bus(dur)
    m.add(thump(0.5, 90, 44, rng, decay=0.1, click=0.2, noise=0.9, nlp=420), 0.0, 0.3)
    m.add(knock(0.4, [110, 180, 290, 450, 700, 1100, 1700], [0.18, 0.14, 0.11, 0.09, 0.06, 0.04, 0.03],
                [0.4, 0.8, 1, .9, .7, .5, .3], rng, lo=80, hi=3000, ex_len=0.004), 0.0, 1.7)
    m.add(nrm(bp(white(0.02, rng), 300, 2800) * np.exp(-tvec(0.02) / 0.004)), 0.0, 0.8)
    m.add(ring(0.25, [1480, 2380, 3300, 4700], [0.1, 0.06, 0.04, 0.025], [1, .7, .5, .3], rng, doublet=0.003), 0.012, 0.6)
    m.add(nrm(bp(white(0.008, rng), 1500, 9000) * np.exp(-tvec(0.008) / 0.0015)), 0.012, 0.4)
    m.add(jingle(0.3, rng, 6, f_lo=1800, f_hi=5000, t60=(0.03, 0.09), start=0.02, spread=0.1, amp_decay=0.12), 0.02, 0.25)
    m.add(crackle(0.2, rng, 600 * np.exp(-tvec(0.2) / 0.06) + 20, decay=0.002, f_lo=200, f_hi=1500, size_var=0.9), 0.0, 0.3)
    m.add(warm_bell(note("G3"), rng, t60=1.3, fc=750, attack=0.03, hum=0.15), 0.06, 0.5)
    return reverb(fit(m.out(), n), wet=0.2, rt60=1.5, predelay=0.02, hf_damp=0.6, seed=1311, early_span=0.06)


# =========================================================================== the renegade: stones, a cold breath, a failing ward
def stone_voice(dur, rng, f, drive_amt=1.6):
    """one standing stone sounding: a slab of rock a few metres tall rings low and for a long while. Its note (f) with the
    harmonics that carry on a small speaker, a little grit in the wave, and the ring of the stone's own modes about it,
    kept going by a rumble of brown noise so that the amplitude of each wanders slowly (stone is never quite still)"""
    n = ns(dur)
    ph = phase_from_freq(f * wander(dur, rng, 0.6, 0.002), n)
    y = np.zeros(n)
    for k, g in enumerate([1.0, 0.9, 0.7, 0.5, 0.32, 0.2], start=1):
        y += g * np.sin(k * ph + rng.uniform(0, 6.28))
    y = drive(nrm(y), drive_amt)
    modes = resonators(brown(dur, rng), [f * 2.0, f * 3.1, f * 4.4, f * 6.0], [1.6, 1.1, 0.8, 0.5], [1.0, 0.8, 0.5, 0.3])
    return nrm(y) + 0.5 * nrm(modes)


@recipe("renegade.stones", variants=1, peak_db=-4.0, fade_out=0.7, max_len=3.1, tail_db=-66.0, rms_db=-19.0)
def renegade_stones(v, rng):
    """the standing stones waking as the player steps into the ring: the first stone speaks, a deep boom in the ground, and
    one after another the rest take it up on their own low notes (a cluster, G, A, B, D, E: nothing that rests), each
    swelling in and wavering against the others, a grind of rock on rock under them and a cold shimmer of glass climbing over
    the swell as the whole ring stands awake"""
    dur = 3.5
    n = ns(dur)
    t = tvec(dur)
    swell = rise_in(t, 0.0, 2.2) ** 1.3 * np.clip((dur - t) / 1.1, 0, 1)
    m = Bus(dur)
    m.add(thump(1.2, 64, 33, rng, decay=0.35, click=0.05, noise=0.7, nlp=260), 0.0, 0.6)
    for f, at in [(49.0, 0.0), (55.0, 0.35), (61.7, 0.7), (73.4, 1.05), (82.4, 1.4)]:
        m.add(stone_voice(dur, rng, f) * rise_in(t, at, 1.2) * swell, 0.0, 0.55)
    gr = crackle(dur, rng, 14 + 36 * swell, decay=0.012, f_lo=70, f_hi=800, size_var=1.0) * swell
    m.add(gr, 0.0, 0.5)
    m.add(bits(dur, rng, 18, 0.3, 0.9, 700, 3600), 0.0, 0.2)
    m.add(shimmer(dur, rng, 1500, 6500, count=8, rate=2.4, rise=1 + 0.18 * np.clip(t / 2.4, 0, 1)) * swell ** 1.6, 0.0, 0.35)
    m.add(glasspad(dur, rng, [note("E5"), note("B5")], nh=3, tilt=2.0, vib=(0.7, 0.003)) * swell ** 2, 0.0, 0.25)
    td = dur - 0.6
    m.add(soft_chimes(td, rng, 1.2 + 4.5 * np.clip(tvec(td) / 2.4, 0, 1), 1500, 6000, t60=(0.5, 1.2),
                      level=0.15 + 0.85 * np.clip(tvec(td) / 2.4, 0, 1)), 0.6, 0.3)
    return cold(fit(m.out(), n), 0.26, 2.2, seed=1501)


@recipe("renegade.ambient", variants=2, peak_db=-6.0, fade_out=0.5, max_len=[2.0, 2.1], tail_db=-62.0, rms_db=-22.0)
def renegade_ambient(v, rng):
    """the renegade's presence: a breath that is not a word, cold air whispered through teeth (noise through a vocal tract
    that slides slowly from one vowel to another, and no voice in it, so that it never turns into speech), a cold hiss on
    top of it drawn in and let go, a faint glassy ring hanging in the air with it, two tones a few Hz apart"""
    dur = 2.4
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    s = bell(dur, [0.42, 0.52][v], 1.5, 1.7)
    src = colored(dur, rng, -0.5) * (0.5 + 0.5 * smooth_random(dur, rng, 6.0, 0.0, 1.0))
    whisper = tract(src, ["uh", "o"][v], ["i", "e"][v], curve=np.clip(u * 1.2, 0, 1) ** 1.2, scale=1.0, bw=1.8)
    m = Bus(dur)
    m.add(whisper * s ** 1.2, 0.0, 1.0)
    m.add(bp(white(dur, rng), 4200, 9500) * s ** 1.6 * smooth_random(dur, rng, 3.0, 0.35, 1.0), 0.0, 0.5)
    f = [2870.0, 3320.0][v]
    m.add(rim(dur, rng, f, n=2, rate=1.2, beat=4.0) * s ** 2.2, 0.0, 0.3)
    return cold(fit(m.out(), n), 0.18, 1.2, seed=1511 + v)


@recipe("renegade.hurt", variants=3, fade_out=0.1, max_len=[0.36, 0.34, 0.38], rms_db=-18.0)
def renegade_hurt(v, rng):
    """the renegade hit: his ward cracking, a hard crack of glass and a crackle of splintering running through it, a few
    splinters ringing and a short breath of air let out (noise and no voice), a cold ring left hanging"""
    dur = 0.44
    t = tvec(dur)
    m = Bus(dur)
    m.add(glass([2300, 2750, 3200][v], rng, t60=0.25, kind="bar", n=4, beat=5.0, click=0.3), 0.0, 1.0)
    m.add(burst(0.02, rng, 1200, 11000, 0.002), 0.0, 0.5)
    m.add(sparks(dur, rng, [1700, 2100, 1500][v] * np.exp(-t / 0.07) + 60, lo=1500, hi=8500, decay=0.0005, clump=0.6), 0.0, 0.7)
    m.add(shards(0.3, rng, 420 * np.exp(-tvec(0.3) / 0.06) + 15, f_lo=2000, f_hi=8000, t60=(0.015, 0.1)), 0.01, 0.5)
    breath = tvf(colored(dur, rng, -1.2), "bp", [1500, 1250, 1700][v], 0.8) * \
        np.sin(np.pi / 2 * np.clip(t / 0.012, 0, 1)) ** 2 * np.exp(-t / 0.07)
    m.add(breath, 0.0, 0.55)
    return cold(fit(m.out(), ns(dur)), 0.12, 0.5, seed=1521 + v)


@recipe("renegade.shove", variants=2, fade_out=0.15, max_len=[0.52, 0.5], rms_db=-16.0)
def renegade_shove(v, rng):
    """a palm blast that throws the player back: a hard slap of air as the palm comes down (a pressure front with a brief
    edge), the low thump of the air behind it that pushes, a cold rush tearing past and falling away, a glint of glass in it"""
    dur = 0.6
    n = ns(dur)
    m = Bus(dur)
    m.add(burst(0.04, rng, 300, 6000, 0.006), 0.0, 0.5)
    m.add(air_thump(0.35, rng, [82, 74][v], 36, rise=0.006, decay=0.09, noise=0.9, nlp=420), 0.0, 1.0)
    m.add(whoosh(dur, rng, peak=0.16, f_lo=250, f_hi=[3600, 3000][v], q=0.9, power=1.6, rise=0.8, fall=1.6, color=-2.0,
                 body=0.8, body_lp=260, doppler=0.0), 0.0, 0.9)
    m.add(whoosh(0.3, rng, peak=0.2, f_lo=2200, f_hi=7000, q=1.3, power=1.8, rise=1.0, fall=1.4, color=-1.0, doppler=0.0),
          0.0, 0.3)
    m.add(chime([3400, 4100][v], rng, t60=0.25, attack=0.002, kind="bar", n=2, beat=6.0, click=0.1), 0.02, 0.2)
    return cold(fit(m.out(), n), 0.12, 0.5, seed=1531 + v)


@recipe("renegade.death", variants=1, fade_out=0.6, max_len=2.7, tail_db=-66.0, rms_db=-17.0)
def renegade_death(v, rng):
    """the renegade falling: his wards coming apart, a crack and a crackle of splintering as the first goes and then the
    others, a long shimmer of glass sinking in pitch and thinning as the pieces fall away (chimes stepping down, each lower
    and fewer), a cold breath let out and fading, and at the end, a dull thud as the body meets the ground, his robes
    settling and a last scatter of glass pattering after"""
    dur = 3.0
    n = ns(dur)
    m = Bus(dur)
    m.add(glass(2400, rng, t60=0.35, kind="bar", n=4, beat=5.0, click=0.3), 0.0, 0.7)
    m.add(sparks(1.2, rng, 900 * np.exp(-tvec(1.2) / 0.5) + 25, lo=1500, hi=8500, decay=0.0005, clump=0.6), 0.0, 0.6)
    m.add(shards(2.0, rng, 140 * np.exp(-tvec(2.0) / 0.6) + 5, f_lo=1800, f_hi=8000, t60=(0.02, 0.15)), 0.02, 0.5)
    sd = 2.2
    st = tvec(sd)
    m.add(shimmer(sd, rng, 1500, 7000, count=9, rate=9.0, rise=0.42 ** (np.clip(st / 2.0, 0, 1) ** 0.9)) *
          rise_in(st, 0.0, 0.15) * np.clip((sd - st) / 0.7, 0, 1), 0.05, 0.35)
    for a0, a1, rate, lo, hi in [(0.0, 0.5, 20, 3000, 8000), (0.5, 1.0, 13, 2200, 5200), (1.0, 1.5, 7, 1500, 3500),
                                 (1.5, 2.0, 3, 900, 2200)]:
        sg = a1 - a0
        m.add(chimes(sg + 0.3, rng, rate, lo, hi, t60=(0.3, 0.9), kind="bowl", n=3, scale=COLD,
                     level=1.0 - 0.45 * a0 / 1.5, beat=3.0), a0 + 0.05, 0.5)
    breath = tvf(colored(1.6, rng, -1.5), "bp", 2600 * (330 / 2600) ** (tvec(1.6) / 1.6), 0.9) * bell(1.6, 0.25, 1.4, 1.8)
    m.add(breath, 0.0, 0.35)
    at = 2.05  # the body meets the ground
    m.add(thump(0.5, 78, 36, rng, decay=0.1, click=0.05, noise=0.7, nlp=300), at, 1.0)
    m.add(knock(0.2, [150, 260, 420], [0.06, 0.045, 0.03], [1, .6, .3], rng, lo=80, hi=1500, ex_len=0.004), at, 0.3)
    m.add(cloth(0.35, rng, peak=0.25, lo=200, hi=2400, grit=300), at - 0.07, 0.3)
    m.add(shards(0.5, rng, 60 * np.exp(-tvec(0.5) / 0.15) + 5, f_lo=2000, f_hi=6000, t60=(0.01, 0.06)), at + 0.03, 0.25)
    return cold(fit(m.out(), n), 0.24, 1.8, seed=1541)


# =========================================================================== main
def build_sfx(assets, names, wav_dir=None):
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            t0 = time.time()
            x = render_effect(name, v)
            out = sound_path(name, v + 1)
            write_ogg(os.path.join(assets, "sounds", out + ".ogg"), x)
            if wav_dir:
                write_wav(os.path.join(wav_dir, out + ".wav"), x)
            print(f"{out:34s} {len(x) / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  rms {to_db(rms(x)):6.1f}  "
                  f"{time.time() - t0:5.2f}s", flush=True)


GAP_VARIANT, GAP_SOUND = 0.6, 1.5  # seconds of quiet between a sound's variants, and between one sound and the next
WORK_GROUPS = ("mage", "knight", "thief")  # the groups of make_townsfolk_sfx.py's events that are the factions' work


def listen_through(assets, names, path, with_work=True):
    """one MP3 to listen to on a phone: every sound that was just written, in order, each variant in turn, a gap between,
    and a chapter mark (the sound's name) at each. With with_work, the faction members' work sounds of
    make_townsfolk_sfx.py (the events of WORK_GROUPS) follow, read from where that script wrote them. The files are read
    back from disk, so it is what the game will play"""
    items = [(name, [sound_path(name, v + 1) for v in range(RECIPES[name]["variants"])]) for name in names]
    if with_work:
        from make_townsfolk_sfx import EVENTS, sound_path as work_path
        items += [("townsfolk " + event, [work_path(event, v + 1) for v in range(r["variants"])])
                  for event, r in EVENTS.items() if event.split(".")[0] in WORK_GROUPS]
    pieces, marks, at = [], [], 0.0
    for title, files in items:
        for v, f in enumerate(files):
            p = os.path.join(assets, "sounds", f + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {f})")
                continue
            x = read_audio(p)[0]
            gap = GAP_VARIANT if v < len(files) - 1 else GAP_SOUND
            marks.append((at, at + len(x) / SR + gap, title if len(files) == 1 else f"{title} {v + 1} of {len(files)}"))
            pieces += [x, np.zeros(ns(gap))]
            at += len(x) / SR + gap
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark faction sounds\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)  # (encoded aside, then copied: ffmpeg seeks back in a file to finish its header)
    print(f"{path}  {at:.1f}s, {len(marks)} sounds")


def select(only):
    """the recipes --only names: a full name, or a group (ward_light or ward_light.)"""
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        if s.startswith(FACTION_DIR + "/"):
            s = s[len(FACTION_DIR) + 1:]
        hit = [n for n in RECIPES if n == s or n.startswith(s.rstrip(".") + ".")]
        if not hit:
            raise SystemExit(f"unknown: {s} (effects: {', '.join(RECIPES)})")
        names += [n for n in hit if n not in names]
    return names


def main():
    ap = argparse.ArgumentParser(description="Guildmark's factions: the Starwatch, Hearthsworn and Quiet Hand sound effects")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated effect names or groups (ward_light, heist)")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    a = ap.parse_args()
    names = select(a.only) if a.only else list(RECIPES)
    build_sfx(a.assets, names, a.wav)
    if a.mp3:
        listen_through(a.assets, names, a.mp3, with_work=not a.only)


if __name__ == "__main__":
    main()
