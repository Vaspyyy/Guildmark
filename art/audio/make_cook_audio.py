"""Hearthcraft's kitchen: every sound of the cook's stations, made from nothing (the cook design's 11.2, Part M2).

45 events and 91 mono 44.1 kHz Ogg files in `sounds/cook/` (the design's 44 events and 88 files, and `eat.clink` after them, which
the lead added: a diner's single spoon clink; a dot in an event's name is a folder: `cook.plop.soft` is
`cook/plop/soft1.ogg` ...), each with the number of files, the length and the reach of its row in the design. The game names them
`guildmark:cook.<name>` (`cooking/CookSounds.kt`, whose order ORDER keeps). No sample is used: every sound is noise and
resonators, laid out with `sfxkit.py`, the townsfolk's helpers (`make_townsfolk_sfx.py`: the levelling, the knocks and rings) and
the cog's (`make_ship_audio.py`: bubbles, splashes, creaks, a loop built in a circle), each seeded by its event and file, so that a
rerun is bit-identical. The stone kitchen they are heard in is a small hard room (`kitchen`), the oven's fire is heard through
brick (`through_brick`). The sounds the design says are reused as they are (the game's campfire crackle, bucket and flint and
steel, the market's coin sounds, the festival fanfare) are not remade here.

    the pot and its water    place  pour  plop.soft  plop.meat  plop.grain  stir  taste  serve  skim  ease
    fire, fat and the pan    fuel  ignite  crackle  flare  drip  flip  boil_over  burnt
    chimes                   call  ready  ripe  tier.plain  tier.sound  tier.fine  tier.hallmark  buff
    the oven, spit, crank    oven_open  oven_shut  crank
    board, counter, larder   chop  slice  carve  loaf_down  plate  chalk  larder
    the table                eat.spoon  eat.bite  drink  eat.clink (a diner's sip: one clink, the last of the order)
    five loops              simmer (8 s)  sizzle (6 s)  oven_hum (8 s)  spit_creak (6 s)  chill (10 s)

Each recipe says in its docstring what it is made of, and what each of its variants does that the others do not; the moment each
belongs to is in WHEN (the design's 1.4 to 1.6, 3.3, 4.3 and 7.3 to 7.5), which `--mp3` writes out beside the listen-through.

Levelling, as the lamps' and the townsfolk's are: a one-shot's loudest 100 ms goes to a K-weighted loudness set per sound (the
stations' a little under the lamps': from -22 for a log landing and an oven door to -29 for a spit's crank and a drop of fat, the
tier motifs quiet at -26 to -28, the eater's buff -19.5 because the game plays it at 0.6), its true peak held under -1 dBFS and
re-encoded lower if the encoder overshoots; a sound too sharp to reach its target within a few dB of limiting stops short of it
and says so. The five loops are built in a circle
(`make_ship_audio.Ring`: periodic noise, events laid at the same moment of every lap, filters and rooms run three laps, the middle
one kept), so that the sample after the last is the first with no seam, then set to -27 dBFS RMS with their peaks (true peaks
too) held under -10, a margin for the encoder, and turned to start at a quiet zero crossing. After the encoder each loop is read
back: its length, the step across the join against the usual step between samples, the highs round the join against the highs
elsewhere.

usage (from the repo root; the full render takes about two minutes, `nice -n 10` for it):
    python3 -B art/audio/make_cook_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...] [--mp3 FILE] [--audit]

  (no --only)  renders all 91 files and writes `cook_sounds.json` (their sounds.json entries: a loop with `"stream": false` and
               its reach as `attenuation_distance`, as the mills'; a one-shot with its reach when it is not the game's 16) and
               `cook_lang.json` (the 45 subtitles `subtitles.guildmark.cook.<name>`) beside this script; run
               `python3 -B art/lang/merge_lang.py` after it, which adds them to sounds.json and en_us.json
  --only       events or groups: `stir,plop,eat,tier,loops` (`cook.stir` also works); no fragments are written
  --wav DIR    also writes WAV copies for listening
  --mp3 FILE   joins what was rendered into one listen-through MP3, a chapter mark at each file (a loop plays for twelve seconds,
               so that its join is heard once), and writes `cook_what_plays_when.txt` beside it: each sound's files, length,
               reach, subtitle, what it is made of, the moment it belongs to and where it is in the MP3
  --audit      renders nothing: reads every file back (what it declares, mono 44.1 kHz Vorbis, exactly the row's length; peak and
               true peak; the loudest 100 ms against the target; a loop's RMS and join) and checks the 45 events and 91 files
               against the design (11.2's 44 and 88, and eat.clink's three), the order against CookSounds.NAMES and the fragments
               against the files; exits 1 on a problem
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
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from sfxkit import *  # noqa: E402,F401,F403
import make_townsfolk_sfx as tf  # noqa: E402
import make_ship_audio as ship  # noqa: E402
from make_townsfolk_sfx import level, write_checked, true_peak, loudness_curve, nrm, lead_in, knock  # noqa: E402
from make_ship_audio import (Ring, Bus, bubble, grain_cloud, hann_burst, loop_start, oak, iron_ring, path,
                             periodic_phase, rn, seam_report, squeal, timber_modes, creak_voice, tame, bell_modes,
                             bell_blow)  # noqa: E402

SOUND_DIR = "cook"
LOOP_MARGIN_DB = 0.8  # a loop's peaks are held this far under its ceiling, for the encoder's own overshoot
EVENTS = {}  # event (without the "cook." prefix) -> recipe, in the order of CookSounds.NAMES


# =========================================================================== registry and finishing
def effect(event, files, length, subtitle, reach=16, loud=-25.0, fade_out=0.05, tail_db=-70.0, limit=2.0, peak_db=-3.0,
           when=""):
    """registers a one-shot: `files` variants, each `length` seconds long exactly (as the design's table says), with the
    subtitle the game writes. loud is the target for its loudest 100 ms (K-weighted LUFS), limit the most dB of peak
    limiting allowed to get there, reach how many blocks it carries (its attenuation_distance when it isn't the game's 16),
    when the moment it belongs to (the design's 1.4 to 1.6, 3.3, 4.3, 7.3 to 7.5), for the text of what plays when"""
    def deco(fn):
        EVENTS[event] = dict(kind="effect", fn=fn, files=files, length=length, subtitle=subtitle, reach=reach, loud=loud,
                             fade_out=fade_out, tail_db=tail_db, limit=limit, peak_db=peak_db, when=when)
        return fn
    return deco


def loop(event, period, reach, subtitle, rms_db=-27.0, ceiling_db=-10.0, when=""):
    """registers a seamless loop of `period` seconds: the recipe is handed a Ring and gives back the three periods it laid
    out; the file is the middle one, levelled by RMS with its peaks held under ceiling_db"""
    def deco(fn):
        EVENTS[event] = dict(kind="loop", fn=fn, files=1, length=period, subtitle=subtitle, reach=reach, rms_db=rms_db,
                             ceiling_db=ceiling_db, when=when)
        return fn
    return deco


def sound_path(event, n):
    """'plop.soft', 1 -> 'cook/plop/soft1'"""
    return f"{SOUND_DIR}/{event.replace('.', '/')}{n}"


def build_effect(event, r, v):
    """one variant of a one-shot, finished: trimmed, faded, exactly as long as its row, its loudest 100 ms at the target"""
    rng = rng_for("cook", event, v)
    x = r["fn"](v, rng)
    x = finalize(lead_in(x), peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=r["length"], tail_db=r["tail_db"])
    x = fit(x, ns(r["length"]))
    return level(x, r["loud"], max_limit_db=r["limit"])


def render_loop(event, r):
    """a loop, finished: the middle period of the three the recipe laid out, high-passed, levelled to its RMS with its peaks
    held under the ceiling, and turned to start at a quiet zero crossing (the cog's render_loop)"""
    rg = Ring(r["length"])
    x = hp(r["fn"](rg, rng_for("cook", event, 0)), 25.0)
    ceiling = db(r["ceiling_db"] - LOOP_MARGIN_DB)
    held, limited = ceiling, False
    for _ in range(8):
        y = rg.middle(x)
        x = x * (db(r["rms_db"]) / (rms(y) + 1e-12))
        tp = true_peak(rg.middle(x))
        if tp <= ceiling * 1.001:
            break
        if limited:
            held *= ceiling / tp  # the limiter holds the samples; the true peak between them ran a little over
        x = limiter(x, ceiling=held, lookahead=0.003, release=0.08)
        limited = True
    return loop_start(rg.middle(x))


# =========================================================================== the kitchen's own parts
def kitchen(x, wet=0.1, seed=5100):
    """a stone kitchen: flagged floor, plastered walls, a vault overhead; a hard small room, so a quick slap and a short,
    fairly bright tail"""
    return reverb(x, wet=wet, rt60=0.5, predelay=0.006, hf_damp=0.45, seed=seed, early_span=0.03)


def through_brick(x, wet=0.3, seed=5200, cut=1800.0):
    """a fire heard from outside its oven: the brick vault gives it a short dull room and takes the top off"""
    y = reverb(x, wet=wet, rt60=0.7, predelay=0.005, hf_damp=0.6, seed=seed, early_span=0.02)
    return lp(lp(y, cut, 2), cut * 1.6, 2)


def ring_grains(rg, rng, rate, decay=0.0006, f_lo=700.0, f_hi=5000.0, size_var=0.7, kernels=5):
    """tiny dry grains laid round the ring, about `rate` a second (a number, or a curve over the three periods of which the
    middle is read), the same in every period so that the cloud joins itself"""
    n = rg.n
    r = np.broadcast_to(np.asarray(rate, dtype=float), (rg.N,))[n:2 * n]
    idx = np.nonzero(rng.random(n) < r / SR)[0]
    sel = rng.integers(0, kernels, len(idx))
    mag = rng.standard_normal(len(idx)) * np.exp(size_var * rng.standard_normal(len(idx)))
    out = rg.new()
    k = max(8, ns(decay * 6))
    for j in range(kernels):
        kern = np.exp(-np.arange(k) / max(1, ns(decay))) * rng.standard_normal(k)
        imp = np.zeros(n)
        m = sel == j
        imp[idx[m]] = mag[m]
        out += signal.fftconvolve(rg.tile(imp), kern)[:rg.N]
    return bp(out, f_lo, min(f_hi, SR * 0.45))


def wrap(rg, at):
    """a moment folded into the period"""
    return float(at) % rg.P


def ping(dur, freqs, t60s, amps, rng, ex_len=0.0004, hi=12000.0):
    """a small hard ring (a latch, a link, a lid, a spoon): the knock bank with the skirt taken off under its lowest mode, so
    that it is a tick and a ring and no thump (the resonators have a flat low skirt, about 11 dB down at the bottom of the
    range, which a tiny knock would otherwise carry as a thump under it)"""
    y = knock(dur, freqs, t60s, amps, rng, lo=max(500.0, 0.6 * min(freqs)), hi=hi, ex_len=ex_len)
    return hp(y, 0.55 * min(freqs), 2)


# <<loops
# =========================================================================== the five loops
def blup(rng, f0, tau, rising=True, pop=0.25):
    """a bubble of thick stew breaking at the surface: the film's little tick and the rounded 'blup' of the pocket it leaves, a
    low note that bends as the pocket closes. Thick stew is nothing like clean water, so the note is half breath (noise through
    a moving band, as wet and uneven as the stew) and half a tone with a ripple in its pitch and its level"""
    dur = tau * 6
    n = ns(dur)
    t = np.arange(n) / SR
    bend = (1.0 + 0.45 * np.exp(-t / (tau * 0.8))) if not rising else (1.35 - 0.35 * np.exp(-t / (tau * 1.5)))
    fc = f0 * bend * (1.0 + 0.04 * (smooth_random(dur, rng, 35) - 0.5))
    env = np.exp(-t / tau) * np.clip(t / 0.004, 0, 1)
    ph = phase_from_freq(fc, n)
    tone = np.sin(ph) + 0.3 * np.sin(2.07 * ph + 1.3) + 0.12 * np.sin(3.1 * ph + 0.4)
    breath = tvf(white(dur, rng), "bp", fc * 1.2, 3.0)
    y = (0.55 * tone / (np.std(tone) + 1e-12) + 0.85 * breath / (np.std(breath) + 1e-12)) * env
    y = y * (0.75 + 0.25 * smooth_random(dur, rng, 60))
    out = y / (np.max(np.abs(y)) + 1e-12)
    tick = bp(white(0.004, rng), 1000, 4500) * np.exp(-tvec(0.004) / 0.0008)
    out[:len(tick)] += pop * tick / (np.max(np.abs(tick)) + 1e-12)
    return out


@loop("simmer", 8.0, 24, "A pot simmers",
        when=("Loop. A pot that is heating or cooking simmers (moment 7), and the inn's house pot from 10:00 to 24:00 "
              "(7.3). At most three loops are heard at once (9.5)."))
def simmer(rg, rng):
    """a pot at a gentle simmer, low: the whole pot rolling over slowly (the boil swelling and slackening about once a
    second, a heave of water in iron), big slow blups of thick stew breaking at the surface, and under them the fine
    fizz of small bubbles rising; the iron gives it a hollow note about the pitch of its mouth"""
    P = rg.P
    roll = 0.5 + 0.5 * np.tanh(1.1 * rg.curve(rng, 5, 11, 0.8))
    body = lp(rg.noise(rng, -6.0), 300.0, 2) * (0.45 + 0.75 * roll) * np.exp(0.2 * rg.curve(rng, 6, 30, 1.0))
    wash = bp(rg.noise(rng, -3.0), 350.0, 1800.0, 2) * (0.2 + 0.8 * roll) ** 1.5 * np.exp(0.3 * rg.curve(rng, 12, 60, 0.9))
    blups = rg.new()
    for at in rg.times(rng, 2.6 * (0.4 + 1.1 * roll), shape=1.5):
        f0 = float(np.exp(rng.uniform(np.log(95.0), np.log(340.0))))
        ev = blup(rng, f0, rng.uniform(0.016, 0.06), rising=bool(rng.random() < 0.6), pop=rng.uniform(0.1, 0.4))
        rg.place(blups, ev, at, np.exp(0.5 * rng.standard_normal()) * (0.5 + 0.5 * roll[rg.n + int(at * SR)]))
    fizz = rg.circ(ship.bubbles(P, rng, 70.0 * (0.4 + 1.2 * roll[rg.n:2 * rg.n]), 0.7, 1.8, beta=2.0, level=1.0, weight=0.4,
                                keep_tail=True))
    sparkle = ring_grains(rg, rng, 90.0 * (0.4 + 1.2 * roll), decay=0.0004, f_lo=1500.0, f_hi=4200.0)
    x = 1.0 * rn(body) + 0.28 * rn(wash) + 0.75 * rn(blups) + 0.22 * rn(fizz) + 0.1 * rn(sparkle)
    x = eq(x, "peak", 280.0, 1.3, 4.0)
    return lp(x, 4800.0, 2)


@loop("sizzle", 6.0, 20, "Food sizzles",
        when=("Loop. Food in a hot pan: the Hob while it cooks, and the STICK sign at a pan (1.4, 1.6)."))
def sizzle(rg, rng):
    """fat and water on a hot iron pan: thousands of tiny steam bubbles bursting a second (a bright bed of dry grains that
    comes in clusters), small pops among them, the odd spit of fat with the pan answering faintly, a thin hiss under it all"""
    busy = 0.5 + 0.5 * np.tanh(1.0 * rg.curve(rng, 2, 5, 0.8))
    flurry = np.exp(0.45 * rg.curve(rng, 10, 50, 0.7))
    fine = ring_grains(rg, rng, 4200.0 * (0.5 + 0.7 * busy) * flurry, decay=0.00035, f_lo=3200.0, f_hi=10500.0, size_var=0.6)
    mid = ring_grains(rg, rng, 420.0 * (0.4 + 0.9 * busy) * flurry, decay=0.0009, f_lo=1800.0, f_hi=6500.0, size_var=0.8)
    hiss = hp(rg.noise(rng, -1.0), 4500.0, 2) * (0.3 + 0.6 * busy) * np.exp(0.25 * rg.curve(rng, 20, 90, 0.8))
    spits = rg.new()
    for at in rg.times(rng, 3.0, shape=1.6):
        f = rng.uniform(2200.0, 4200.0)
        ev = grain_cloud(0.03, rng, 1500.0, decay=0.0012, f_lo=1200.0, f_hi=7000.0, size_var=0.5) * np.exp(-tvec(0.03) / 0.008)
        ev = ev + 0.4 * resonators(ev, [f, f * 1.7, f * 2.4], [0.02, 0.014, 0.01], [1, .6, .35])
        rg.place(spits, ev, at, np.exp(0.5 * rng.standard_normal()))
    pan = rg.new()
    for at in rg.times(rng, 2.2, shape=1.5):
        ev = iron_ring(0.12, rng, [rng.uniform(1900.0, 3100.0)], t60=0.05, gains=[1.0])
        rg.place(pan, ev, at, rng.uniform(0.3, 1.0))
    x = 1.0 * rn(fine) + 0.7 * rn(mid) + 0.3 * rn(hiss) + 0.5 * rn(spits) + 0.12 * rn(pan)
    return tame(lp(x, 11000.0, 2), 13.0)


@loop("oven_hum", 8.0, 20, "An oven roars",
        when=("Loop. A lit Bake oven's fire heard through its brick (heating and cooking), and the baker's morning oven "
              "(7.5)."))
def oven_hum(rg, rng):
    """a fire's low roar inside a closed brick oven, heard through its walls: a deep steady roar that breathes as the draught
    does, the flames flickering in it a few times a second, the vault ringing faintly on its own low notes, and now and then
    a log shifting or a knot popping"""
    draw = 0.5 + 0.5 * np.tanh(1.2 * rg.curve(rng, 2, 6, 1.0))
    flick = np.exp(0.35 * rg.curve(rng, 8, 40, 0.9))
    roar = lp(rg.noise(rng, -5.0), 420.0, 4) * (0.55 + 0.6 * draw) * flick
    rush = bp(rg.noise(rng, -2.0), 300.0, 1500.0, 2) * (0.25 + 0.8 * draw) ** 1.5 * np.exp(0.3 * rg.curve(rng, 20, 90, 0.8))
    vault = resonators(bp(rg.noise(rng, -3.0), 60.0, 400.0), [96.0, 148.0, 211.0, 297.0], [0.25, 0.2, 0.16, 0.12],
                       [1.0, 0.8, 0.55, 0.35])
    pops = rg.new()
    for at in rg.times(rng, 1.4, shape=1.3):
        ev = oak(0.12, rng, rng.uniform(160.0, 260.0), t60=0.04, hard=0.5)
        ev = ev + 0.5 * fit(grain_cloud(0.05, rng, 1200.0, decay=0.0008, f_lo=900.0, f_hi=4500.0) * np.exp(-tvec(0.05) / 0.012),
                            len(ev))
        rg.place(pops, ev, at, np.exp(0.5 * rng.standard_normal()))
    crack = ring_grains(rg, rng, 60.0 * (0.4 + 1.0 * draw), decay=0.0008, f_lo=800.0, f_hi=4000.0)
    x = 1.0 * rn(roar) + 0.45 * rn(rush) + 0.25 * rn(vault) + 0.3 * rn(pops) + 0.2 * rn(crack)
    return through_brick(x, 0.3, 5201)


@loop("spit_creak", 6.0, 20, "A spit turns",
        when=("Loop. The Spit's rod turning in its cradle while it is cranked (1.6), and the innkeeper's rest-day roast "
              "(7.5)."))
def spit_creak(rg, rng):
    """an iron spit turning in dry notches: the rod lifted a notch at a time (a dry tick of iron on iron and the low knock of
    its weight settling), most notches with a short dry squeak of the rod in its cradle, and after each the faint rub of iron on
    iron as the rod swings over; the notches come about a second apart but never quite evenly"""
    P = rg.P
    k = 6
    gaps = np.maximum(0.5, 1.0 + 0.16 * rng.standard_normal(k))
    gaps = gaps / gaps.sum() * P
    at_list = np.cumsum(gaps) - gaps + 0.25 * gaps[0]
    rod = rg.new()
    squeaks = rg.new()
    for i, at in enumerate(at_list):
        g = rng.uniform(0.6, 1.0)
        ev = Mix(0.3)
        ev.add(nrm(hann_burst(0.002, rng, 2500.0, 9000.0)), 0.0, 0.3)
        ev.add(nrm(iron_ring(0.12, rng, [rng.uniform(1500.0, 1900.0), 2480.0], t60=0.05, gains=[1.0, 0.6])), 0.003, 0.7)
        ev.add(nrm(oak(0.2, rng, rng.uniform(250.0, 320.0), t60=0.07, hard=0.8, lo=200.0)), 0.003, 0.6)
        rg.place(rod, ev.out(), wrap(rg, at), g)
        if rng.random() < 0.75:
            sd = rng.uniform(0.08, 0.26)
            f = rng.uniform(950.0, 1700.0)
            sq = squeal(sd, rng, path([(0, f * 0.9), (sd * 0.4, f), (sd, f * 1.12 * rng.uniform(0.95, 1.05))], sd),
                        bell(sd, 0.4, 1.2, 1.6), harmonics=6, tilt=1.1, chatter=0.5, wobble=0.02, peak_hz=2100.0)
            rg.place(squeaks, sq, wrap(rg, at + 0.05), rng.uniform(0.3, 0.9))
    rub = bp(rg.noise(rng, -1.5), 600.0, 2600.0, 2) * rg.bumps(at_list + 0.1, rng.uniform(0.6, 1.0, k), 0.04, 0.12) * \
        (0.6 + 0.4 * np.exp(0.5 * rg.curve(rng, 6, 30, 1.0)))
    x = 1.0 * rn(rod) + 0.5 * rn(squeaks) + 0.2 * rn(rub)
    return kitchen(x, 0.12, 5202)


@loop("chill", 10.0, None, "The cold bites",
        when=("Loop. Winter Chill: the wind under the frost, heard only by the chilled player, at half of the ambience "
              "slider (4.3, Part F)."))
def chill(rg, rng):
    """a thin cold wind with a faint chatter: air slipping by in gusts, thin and high (a band that climbs in the gusts), a few
    faint whistles round corners that rise and sink with them, and three short spells of teeth chattering, very faint"""
    gust = 0.5 + 0.5 * np.tanh(1.3 * rg.curve(rng, 1, 4, 1.1))
    wind = bp(rg.noise(rng, -3.0), 450.0, 3800.0, 2) * (0.3 + 0.7 * gust) ** 1.4 * np.exp(0.3 * rg.curve(rng, 8, 40, 0.9))
    air = hp(rg.noise(rng, -1.0), 3000.0, 2) * gust ** 2.0 * np.exp(0.3 * rg.curve(rng, 20, 90, 0.8))
    whistles = np.zeros(rg.N)
    for f0, g, lag in [(880.0, 0.8, 0.0), (1240.0, 0.6, 1.4), (1730.0, 0.4, 2.7)]:
        gu = 0.5 + 0.5 * np.tanh(1.3 * np.roll(rg.curve(rng, 1, 4, 1.1), int(lag * SR)))
        f = f0 * (1 + 0.09 * (gu - 0.5) + 0.012 * rg.curve(rng, 5, 25, 1.0) + 0.006 * rg.curve(rng, 30, 70, 0.6))
        phs = periodic_phase(rg, f)
        amp = np.clip((gu - 0.55) / 0.45, 0, 1) ** 1.5 * (0.7 + 0.3 * np.exp(0.5 * rg.curve(rng, 10, 60, 0.8)))
        breath = tvf(rg.noise(rng, 0.0), "bp", f, 14.0, block=256)
        whistles += g * amp * (np.sin(phs + rng.uniform(0, 6.28)) + 0.2 * np.sin(2 * phs + rng.uniform(0, 6.28))
                               + 1.2 * breath / (np.std(breath) + 1e-12))
    chatter = rg.new()
    for at, count in ((1.4, 9), (4.9, 7), (8.0, 8)):
        tt = 0.0
        for j in range(count):
            ev = ping(0.03, [2300.0 * rng.uniform(0.9, 1.1), 3900.0, 5600.0], [0.006, 0.005, 0.004], [1.0, 0.6, 0.4], rng,
                      ex_len=0.0005, hi=9000.0)
            rg.place(chatter, ev, wrap(rg, at + tt), rng.uniform(0.5, 1.0))
            tt += rng.uniform(0.07, 0.1)
    x = 1.0 * rn(wind) + 0.25 * rn(air) + 0.45 * rn(whistles) + 0.12 * rn(chatter)
    return lp(x, 9500.0, 2)
# loops>>


# <<pot
# =========================================================================== iron, water and the pot
def pot_ring(dur, rng, f0, t60=0.3, wet=0.0):
    """a cast-iron pot struck: a thick bowl of iron rings dull and short (modes 1, 2.32, 3.9, 5.7 and 7.9 times the lowest, the
    upper ones dying first, each a close pair that beats); `wet` is how full it is, for what is in it takes the ring away"""
    r = np.array([1.0, 2.32, 3.9, 5.7, 7.9]) * rng.uniform(0.995, 1.005, 5)
    t60s = t60 * (1.0 - 0.75 * wet) * np.array([1.0, 0.7, 0.45, 0.3, 0.2])
    return tf.ring(dur, f0 * r, t60s, [1.0, 0.75, 0.5, 0.32, 0.18], rng, doublet=0.003, doublet_amp=0.6)


def chain_links(rng, count=8, f_lo=1500.0, f_hi=3800.0, span=0.22, decay=0.5, size=1.0):
    """the links of an iron chain shifting against one another as a weight settles: a quick scatter of small clinks, more of
    them at first, each link a short ring of its own"""
    m = Mix(span + 0.1)
    for _ in range(count):
        tt = span * rng.random() ** 1.6
        f = float(np.exp(rng.uniform(np.log(f_lo), np.log(f_hi)))) / size ** 0.3
        t60 = rng.uniform(0.02, 0.06) * size
        link = ping(0.08, [f, f * 2.76, f * 5.4], [t60, t60 * 0.5, t60 * 0.3], [1.0, 0.5, 0.25], rng, ex_len=0.0004)
        m.add(nrm(link), tt, rng.uniform(0.25, 1.0) * np.exp(-tt / (span * decay)))
    return m.out()


def spatter(dur, rng, rate0, tau, lo=500.0, hi=5000.0, gain_lo=0.2):
    """droplets landing in water, a patter that thins out: each a tiny bubble's plip with the tick of its fall"""
    n = ns(dur)
    t = tvec(dur)
    out = np.zeros(n + ns(0.1))
    idx = np.nonzero(rng.random(n) < rate0 * np.exp(-t / tau) / SR)[0]
    for i in idx:
        r = rng.uniform(0.7, 2.6)
        b = bubble(r, rng, rise=rng.uniform(0.3, 0.8), max_dur=0.08) * rng.uniform(gain_lo, 1.0)
        out[i:i + len(b)] += b
    return bp(out[:n], lo, hi)


def swirl(dur, rng, f_lo, f_hi, peak=0.5, q=0.8, lumps=0.0):
    """a ladle drawn through thick stew: a low rush of the liquid heaving round it (its band climbing and sinking with the
    speed of the stroke), lumps knocking against the bowl of it"""
    s = bell(dur, peak, 1.5, 1.6)
    wob = 1.0 + 0.12 * np.sin(2 * np.pi * rng.uniform(1.0, 1.6) * tvec(dur) + rng.uniform(0, 6.28))
    y = tvf(colored(dur, rng, -2.0), "bp", (f_lo + (f_hi - f_lo) * s) * wob, q) * s ** 1.2
    y = y * (0.7 + 0.3 * smooth_random(dur, rng, 14))
    if lumps:
        for _ in range(int(rng.integers(2, 5))):
            at = rng.uniform(0.1, 0.8) * dur
            i = ns(at)
            b = blup(rng, rng.uniform(140.0, 260.0), rng.uniform(0.018, 0.035), rising=bool(rng.random() < 0.5), pop=0.1)
            m = min(len(b), len(y) - i)
            if m > 0:
                y[i:i + m] += lumps * np.max(np.abs(y)) * b[:m] * rng.uniform(0.3, 1.0)
    return y


@effect("place", 2, 0.5, "A cooking station is set down", loud=-22.0, fade_out=0.12, limit=3.0,
        when=("A cook sets a station down (the pot's first moment, 1.5): the tripod meets the ground and the pot swings on "
              "its chain."))
def place(v, rng):
    """the Stewpot set down: its wooden tripod meeting the ground with a low thud, the iron pot swinging on its chain and
    knocking the legs with a dull 'tonk', and the chain's links rattling as it settles (one rattle). v2 a little lighter and
    higher, the rattle a beat later"""
    dur = 0.5
    m = Bus(dur)
    f = [1.0, 1.08][v]
    m.add(thump(0.3, 135 * f, 72 * f, rng, decay=0.07, click=0.12, noise=0.45, nlp=520), 0.0, 1.0)
    m.add(oak(0.25, rng, 175 * f, t60=0.11, hard=0.7, ex=0.003), 0.0, 0.7)
    m.add(hann_burst(0.003, rng, 400.0, 3500.0), 0.0, 0.25)
    m.add(pot_ring(0.4, rng, 330 * f, t60=0.28, wet=0.0), [0.035, 0.05][v], 0.42)
    m.add(hann_burst(0.002, rng, 1500.0, 6000.0), [0.035, 0.05][v], 0.12)
    m.add(chain_links(rng, count=[9, 8][v], f_lo=1400.0, f_hi=3600.0, span=0.2, size=1.0), [0.075, 0.1][v], 0.42)
    return kitchen(fit(m.out(), ns(dur)), 0.1, 5301 + v)


@effect("pour", 2, 1.0, "Water pours into a pot", reach=12, loud=-25.0, fade_out=0.15,
        when=("Water is poured into the empty pot from a bucket (moment 2)."))
def pour(v, rng):
    """water poured into the empty iron pot: the first of it landing on the iron bottom (a splash and a short 'tink' of the
    pot), then a stream falling into what is already there, its note climbing as the pot fills (the pot's air column, a pipe
    that gets shorter), a babble of small bubbles and the glug of the bucket tipping, a last trickle and two drops. v2
    pours in two heaves"""
    dur = 1.0
    pd = [0.84, 0.8][v]
    t = tvec(pd)
    u = t / pd
    m = Bus(dur)
    m.add(bp(white(0.05, rng), 700.0, 5500.0) * np.exp(-tvec(0.05) / 0.012), 0.0, 0.4)
    m.add(pot_ring(0.3, rng, 300.0, t60=0.2, wet=0.1), 0.0, 0.25)
    env = np.clip(t / 0.05, 0, 1) * np.clip((pd - t) / 0.1, 0, 1) ** 1.5 * (0.6 + 0.4 * np.clip(t / 0.3, 0, 1))
    if v == 1:
        env = env * (0.55 + 0.45 * (0.5 + 0.5 * np.cos(2 * np.pi * t / 0.45 + 0.5)) ** 0.8)
    glug = 1.0 - 0.35 * (0.5 + 0.5 * np.sin(phase_from_freq(7.0 * (1 + 0.1 * smooth_random(pd, rng, 3)), len(t))))
    babble = ship.bubbles(pd, rng, 650.0 * env + 20.0, 0.7, 3.0, beta=1.8, level=1.0, weight=0.4, rise=0.5)
    src = colored(pd, rng, -1.0) + 0.9 * nrm(babble)
    stream = tvf(src, "bp", 380.0 * (1.0 + 2.6 * u ** 1.2), 4.0)
    wash = bp(colored(pd, rng, -1.5), 600.0, 4500.0) * (0.6 + 0.4 * smooth_random(pd, rng, 25))
    m.add((nrm(stream) + 0.25 * nrm(wash)) * env * glug, 0.02, 1.0)
    m.add(grain_cloud(pd, rng, 500.0 * env + 5.0, decay=0.0005, f_lo=2500.0, f_hi=8000.0, size_var=0.6) * env, 0.02, 0.12)
    # the iron shimmering under the stream: what falls on it excites the pot's own notes, which the water in it keeps short
    shim = resonators(nrm(bp(wash, 500.0, 3000.0)) * env, [300.0 * 2.32, 300.0 * 3.9, 300.0 * 5.7], [0.1, 0.07, 0.05], [1.0, 0.8, 0.5])
    m.add(shim, 0.02, 0.14)
    for i, at in enumerate([pd + 0.05, pd + 0.13]):
        m.add(ship.drip(rng), at, 0.4 * 0.7 ** i)
    return kitchen(lp(fit(m.out(), ns(dur)), 8500.0), 0.08, 5303 + v)


@effect("plop.soft", 3, 0.4, "Something drops into a pot", reach=12, loud=-25.0, fade_out=0.1,
        when=("An ingredient goes into the pot (moment 3), chosen by what it is: a soft thing (a leaf, a root, an onion, a "
              "mushroom). Played for a friend watching, too."))
def plop_soft(v, rng):
    """a soft thing (a leaf, a root, a slice of onion) dropped into the pot: a small splash, a bubble's rising 'plip' with the
    pot's hollow round it, the droplets falling back; three sizes of bubble for three kinds of thing"""
    dur = 0.4
    r_mm = [2.9, 2.2, 3.7][v]
    m = Bus(dur)
    m.add(bp(white(0.04, rng), 500.0, 5000.0) * np.exp(-tvec(0.04) / 0.009), 0.0, 0.4)
    m.add(bubble(r_mm, rng, rise=0.8, max_dur=0.2), 0.003, 1.0)
    m.add(resonators(bp(white(0.012, rng), 300.0, 2500.0) * np.hanning(ns(0.012)), [420.0, 780.0, 1250.0], [0.06, 0.045, 0.03],
                     [1.0, 0.5, 0.3]), 0.0, 0.25)
    m.add(spatter(0.25, rng, 50.0, 0.055), 0.05, 0.25)
    return kitchen(lp(fit(m.out(), ns(dur)), 7500.0), 0.08, 5305 + v)


@effect("plop.meat", 3, 0.4, "Something drops into a pot", reach=12, loud=-23.0, fade_out=0.1,
        when=("An ingredient goes into the pot (moment 3): a heavy, wet thing (meat, fish, bone)."))
def plop_meat(v, rng):
    """a heavy, wet thing (a joint, a chunk of mutton, a fish) let fall into the pot: the slap of it on the water, a deep 'bloop'
    as the water closes over it and the bubbles it lets go, a dull knock of it against the iron; three weights of it"""
    dur = 0.4
    size = [0.1, 0.07, 0.14][v]
    m = Bus(dur)
    m.add(ship.splash(0.4, rng, size=size, hard=0.8, crown=False), 0.0, 1.0)
    m.add(thump(0.15, 150.0, 90.0, rng, decay=0.03, click=0.0, noise=0.6, nlp=500), 0.0, 0.5)
    m.add(pot_ring(0.25, rng, 300.0 * [1.0, 1.1, 0.9][v], t60=0.2, wet=0.6), 0.004, 0.18)
    m.add(grain_cloud(0.15, rng, 500.0, decay=0.0007, f_lo=500.0, f_hi=3000.0) * np.exp(-tvec(0.15) / 0.05), 0.02, 0.3)
    return kitchen(lp(fit(m.out(), ns(dur)), 6000.0), 0.08, 5308 + v)


@effect("plop.grain", 3, 0.4, "Something drops into a pot", reach=12, loud=-26.0, fade_out=0.1,
        when=("An ingredient goes into the pot (moment 3): a handful of grain, pease or oats."))
def plop_grain(v, rng):
    """a handful of grain (barley, oats, pease) let run into the pot: a hiss of it falling, a patter of tiny splashes as the
    grains reach the water, thinning out, one or two grains ticking on the iron"""
    dur = 0.4
    m = Bus(dur)
    gd = [0.26, 0.2, 0.3][v]
    gt = tvec(gd)
    env = np.clip(gt / 0.015, 0, 1) * np.exp(-gt / (gd * 0.5))
    m.add(bp(white(gd, rng), 2500.0, 8000.0) * env, 0.0, 0.5)
    m.add(spatter(0.35, rng, [380.0, 300.0, 460.0][v], 0.09, lo=900.0, hi=7000.0), 0.0, 1.0)
    for i in range(int(rng.integers(2, 5))):
        m.add(ping(0.04, [2300.0 * rng.uniform(0.9, 1.1), 3800.0], [0.008, 0.005], [1.0, 0.5], rng, ex_len=0.0004, hi=9000.0),
              rng.uniform(0.0, 0.15), rng.uniform(0.2, 0.5))
    return kitchen(lp(fit(m.out(), ns(dur)), 9500.0), 0.07, 5311 + v)


@effect("stir", 3, 0.8, "A pot is stirred", loud=-26.0, fade_out=0.15,
        when=("The ladle on the pot (moments 6 and 9, and every stir after). The innkeeper's stir scene plays it every 20 "
              "ticks while she stirs the house pot (7.3)."))
def stir(v, rng):
    """a wooden ladle drawn round in a heavy iron pot of stew: a slow swish of the thick liquid round the bowl of it and the odd
    blup of a lump, one knock of the ladle's wood on the iron rim as it goes by, the pot's low hum answering. v1 knocks as the
    ladle goes in, v2 grazes the rim half way round, v3 knocks at the end and scrapes the bottom a moment first"""
    dur = 0.8
    m = Bus(dur)
    sd = [0.62, 0.7, 0.66][v]
    m.add(swirl(sd, rng, 280.0, [720.0, 640.0, 680.0][v], peak=[0.5, 0.45, 0.55][v], q=1.6, lumps=0.5), [0.02, 0.0, 0.0][v], 1.0)
    at = [0.02, 0.4, 0.66][v]
    hard = [0.8, 0.6, 0.9][v]
    m.add(oak(0.1, rng, [980.0, 850.0, 1050.0][v], t60=0.025, hard=hard, ex=0.0015, lo=500.0), at, 0.5)
    m.add(pot_ring(0.4, rng, [470.0, 440.0, 500.0][v], t60=0.3, wet=0.45), at + 0.001, 0.55)
    m.add(hann_burst(0.002, rng, 1500.0, 6000.0), at, 0.12)
    hum = resonators(fit(hann_burst(0.01, rng, 60.0, 400.0), ns(0.7)), [96.0, 190.0], [0.5, 0.3], [1.0, 0.5])
    m.add(hum, at, 0.16)
    if v == 2:
        rub = bp(colored(0.18, rng, -1.0), 800.0, 3200.0) * bell(0.18, 0.4, 1.2, 1.5) * (0.5 + 0.5 * smooth_random(0.18, rng, 45))
        m.add(rub, 0.28, 0.28)
    return kitchen(lp(fit(m.out(), ns(dur)), 7500.0), 0.08, 5314 + v)
# pot>>


# <<fire
# =========================================================================== fire, fat and steam
def hiss(dur, rng, lo, hi, attack=0.02, tau=0.2, color=-0.5, order=2):
    """steam or air let out: band-limited noise that comes up over `attack` seconds and dies away with time constant `tau`"""
    t = tvec(dur)
    return bp(colored(dur, rng, color), lo, hi, order) * np.clip(t / max(attack, 1e-4), 0, 1) ** 1.5 * np.exp(-t / tau)


def spit_pop(rng, size=1.0, sizzle=1.0):
    """a drop of fat bursting on hot iron: the film's sharp little crack, the small hard 'pok' of the pan answering it, and the
    sizzle of what is left boiling off"""
    d = 0.18 * size
    m = Mix(d)
    m.add(nrm(bp(white(0.003, rng), 1500.0, 9000.0) * np.exp(-tvec(0.003) / 0.0005)), 0.0, 0.6)
    f = rng.uniform(900.0, 2200.0)
    m.add(nrm(ping(0.04, [f, f * 1.8, f * 2.9], [0.008, 0.006, 0.004], [1.0, 0.5, 0.3], rng, ex_len=0.0008, hi=6000.0)),
          0.0, 0.5)
    t = tvec(d)
    m.add(nrm(hp(white(d, rng), 3500.0) * np.exp(-t / (0.035 * size)) * np.clip(t / 0.002, 0, 1)), 0.001, 0.4 * sizzle)
    return m.out()


def flutter(dur, rng, lo=180.0, hi=1100.0, rate=13.0, peak=0.2):
    """a flame burning, its body of soft low noise wavering as flames do"""
    s = bell(dur, peak, 1.2, 1.5)
    return nrm(bp(pink(dur, rng), lo, hi) * s * (0.35 + 0.65 * smooth_random(dur, rng, rate)))


def ember_fall(dur, rng, rate0, tau, f_lo=1500.0, f_hi=6000.0, clinks=3):
    """embers and charcoal shifting under a log: a crackle that thins out as they settle, and the small hard clinks of
    pieces of coal against each other"""
    t = tvec(dur)
    y = grain_cloud(dur, rng, rate0 * np.exp(-t / tau) + 6.0, decay=0.0007, f_lo=f_lo, f_hi=f_hi, size_var=0.8)
    m = Mix(dur)
    m.add(nrm(y), 0.0, 1.0)
    for _ in range(clinks):
        f = rng.uniform(2300.0, 4200.0)
        m.add(nrm(ping(0.05, [f, f * 1.9], [0.02, 0.012], [1.0, 0.5], rng, ex_len=0.0004, hi=9000.0)),
              rng.exponential(tau * 0.7) % (dur * 0.9), rng.uniform(0.08, 0.25))
    return m.out()


@effect("fuel", 2, 0.6, "Fuel is thrown on a fire", loud=-22.0, fade_out=0.15, limit=3.0,
        when=("Logs or coal are thrown at the fire region of a station (moment 4)."))
def fuel(v, rng):
    """logs thrown onto embers: the heavy one landing with a low wooden thud and the bark scuffing, a smaller one clattering
    against it, the embers breaking and crackling under them in a shower of small sparks with the clink of coal on coal, the
    draught of the fire answering with a soft rush. v2 is two small logs"""
    dur = 0.6
    m = Bus(dur)
    if v == 0:
        logs = [(0.0, 150.0, 0.7, 1.0), (0.1, 270.0, 0.8, 0.6)]
    else:
        logs = [(0.0, 215.0, 0.8, 0.8), (0.07, 290.0, 0.9, 0.7)]
    for at, f0, hard, g in logs:
        m.add(oak(0.3, rng, f0, t60=0.12, hard=hard, ex=0.003, lo=150.0), at, g)
        m.add(thump(0.2, f0 * 0.8, f0 * 0.45, rng, decay=0.05, click=0.0, noise=0.5, nlp=f0 * 3.0), at, 0.5 * g)
        m.add(grain_cloud(0.06, rng, 1500.0, decay=0.001, f_lo=600.0, f_hi=3500.0) * np.exp(-tvec(0.06) / 0.015), at, 0.25 * g)
    m.add(ember_fall(0.5, rng, [300.0, 240.0][v], 0.12), 0.012, 0.6)
    rush = bp(colored(0.4, rng, -1.5), 350.0, 2200.0) * bell(0.4, 0.2, 1.3, 1.6)
    m.add(rush, 0.03, 0.25)
    return kitchen(lp(fit(m.out(), ns(dur)), 9000.0), 0.08, 5321 + v)


@effect("ignite", 2, 0.9, "A cooking fire catches", loud=-23.0, fade_out=0.2,
        when=("Flint and steel or a fire charge lights the station's fire (moment 4), over the game's own flint-and-steel "
              "sound."))
def ignite(v, rng):
    """a cooking fire catching: the striker's quick rasp over the flint and a few sparks, the tinder taking with a soft 'ffp',
    then the whoomf of the kindling lighting (a low rush that swells in a tenth of a second and settles), crackling as the
    sticks catch and the little flames fluttering after. v2 is slower to take, with more crackle"""
    dur = 0.9
    m = Bus(dur)
    rd = [0.13, 0.17][v]
    rt = tvec(rd)
    rasp = tvf(white(rd, rng), "bp", 2600.0 + 4600.0 * rt / rd, 1.2) * np.clip(rt / 0.004, 0, 1) * np.exp(-np.maximum(rt - rd * 0.5, 0) / (rd * 0.2))
    rasp = rasp * (0.6 + 0.4 * smooth_random(rd, rng, 350))
    m.add(rasp, 0.0, 0.28)
    m.add(grain_cloud(rd + 0.04, rng, 800.0, decay=0.0003, f_lo=2500.0, f_hi=11000.0, size_var=0.9), 0.0, 0.18)
    at = [0.12, 0.17][v]
    ft = tvec(0.2)
    m.add(tvf(colored(0.2, rng, -1.5), "lp", 500.0 + 3000.0 * np.exp(-ft / 0.03), 0.9) * np.clip(ft / 0.01, 0, 1) * np.exp(-ft / 0.06), at, 0.5)
    wd = 0.62
    wt = tvec(wd)
    wf = [0.07, 0.1][v]
    s = np.clip(wt / wf, 0, 1) ** 1.6 * np.exp(-np.maximum(wt - wf, 0) / [0.2, 0.26][v])
    whoomf = tvf(colored(wd, rng, -3.0), "lp", 300.0 + 900.0 * s, 0.8) * s
    m.add(whoomf, at + 0.01, 1.0, end_fade=0.2)
    m.add(sine_sweep(0.2, 90.0, 55.0, 2.0) * np.exp(-tvec(0.2) / 0.07) * np.clip(tvec(0.2) / 0.01, 0, 1), at + 0.01, 0.25)
    cd = 0.55
    ct = tvec(cd)
    m.add(grain_cloud(cd, rng, ([160.0, 260.0][v]) * np.sin(np.pi * np.clip(ct / cd, 0, 1)) ** 0.8 + 10.0, decay=0.0008,
                      f_lo=1200.0, f_hi=6500.0, size_var=0.8), at + 0.04, 0.45)
    m.add(flutter(0.55, rng, peak=0.15), at + 0.3, 0.3)
    return kitchen(lp(fit(m.out(), ns(dur)), 10000.0), 0.08, 5323 + v)


@effect("crackle", 2, 2.0, "Food crackles", reach=20, loud=-25.0, fade_out=0.4, limit=3.0,
        when=("A missed call gets worse (moment 14): a pot catching, fat spitting; overdone food (moment 19)."))
def crackle_sound(v, rng):
    """fat spitting in a hot pan and a pot catching: a ragged run of pops and spits (each a crack, a hard 'pok' of the iron and a
    little sizzle behind it) that comes in bursts and slackens, scorching dry crackle between them. v1 spits, wet and sharp;
    v2 is the dry crackle of a pot that has caught, finer and closer together"""
    dur = 2.0
    m = Bus(dur)
    t = tvec(dur)
    if v == 0:
        env = np.clip(t / 0.05, 0, 1) * (0.55 + 0.45 * smooth_random(dur, rng, 2.2)) * np.exp(-np.maximum(t - 0.9, 0) / 0.7)
        at = 0.0
        while at < dur - 0.2:
            m.add(spit_pop(rng, size=rng.uniform(0.6, 1.6), sizzle=rng.uniform(0.5, 1.2)), at, 0.55 * env[min(ns(at), len(env) - 1)] ** 0.8 + 0.05)
            at += rng.gamma(1.4, 0.075 / max(env[min(ns(at), len(env) - 1)], 0.15))
        m.add(grain_cloud(dur, rng, 700.0 * env + 20.0, decay=0.0005, f_lo=2500.0, f_hi=9000.0, size_var=0.8) * env, 0.0, 0.3)
        m.add(hiss(dur, rng, 4000.0, 9500.0, attack=0.03, tau=0.7, color=-0.5) * env, 0.0, 0.12)
    else:
        env = np.clip(t / 0.08, 0, 1) * (0.6 + 0.4 * smooth_random(dur, rng, 3.0)) * np.exp(-np.maximum(t - 1.0, 0) / 0.6)
        m.add(grain_cloud(dur, rng, 1500.0 * env + 30.0, decay=0.0006, f_lo=1500.0, f_hi=7500.0, size_var=0.9) * env, 0.0, 1.0)
        m.add(grain_cloud(dur, rng, 5200.0 * env + 100.0, decay=0.0003, f_lo=3500.0, f_hi=11000.0, size_var=0.7) * env, 0.0, 0.5)
        at = 0.1
        while at < dur - 0.3:
            m.add(spit_pop(rng, size=rng.uniform(0.5, 1.0), sizzle=0.5), at, 0.4 * env[min(ns(at), len(env) - 1)])
            at += rng.gamma(1.5, 0.2)
        m.add(hiss(dur, rng, 3500.0, 9000.0, attack=0.05, tau=0.9, color=-0.5) * env, 0.0, 0.2)
    return kitchen(tame(lp(fit(m.out(), ns(dur)), 11000.0), 13.0), 0.07, 5325 + v)


@effect("flare", 2, 0.8, "Fat flares in the fire", loud=-25.0, fade_out=0.25,
        when=("A FLARE call's sign at the hob, the oven or the spit (1.4): the fat catches, a lick of flame."))
def flare(v, rng):
    """fat catching in the fire: a lick of flame, a soft 'whup' as the vapour lights, a quick roar that climbs and falls away
    in a tenth of a second over a flutter of flame, crackling, a hiss of fat. v2 is a smaller flame that gutters"""
    dur = 0.8
    m = Bus(dur)
    fd = [0.6, 0.55][v]
    ft = tvec(fd)
    s = np.clip(ft / 0.05, 0, 1) ** 1.4 * np.exp(-np.maximum(ft - 0.05, 0) / [0.2, 0.15][v])
    roar = tvf(colored(fd, rng, -2.0), "bp", 450.0 + 2200.0 * s, 0.9) * s * (0.6 + 0.4 * smooth_random(fd, rng, 18))
    m.add(roar, 0.0, 1.0)
    m.add(sine_sweep(0.12, 160.0, 90.0, 2.0) * np.exp(-tvec(0.12) / 0.04) * np.clip(tvec(0.12) / 0.008, 0, 1), 0.0, 0.3)
    cd = 0.5
    ct = tvec(cd)
    m.add(grain_cloud(cd, rng, [260.0, 180.0][v] * s[:ns(cd)] + 10.0, decay=0.0007, f_lo=1500.0, f_hi=6000.0, size_var=0.8) *
          np.clip((cd - ct) / 0.2, 0, 1), 0.02, 0.4)
    m.add(hiss(0.5, rng, 3000.0, 6500.0, attack=0.02, tau=0.16), 0.05, 0.14)
    m.add(flutter(0.5, rng, lo=250.0, hi=1500.0, rate=17.0, peak=0.25) * np.clip((cd - ct) / 0.2, 0, 1), 0.1, 0.25)
    return kitchen(lp(fit(m.out(), ns(dur)), 7500.0), 0.07, 5327 + v)


@effect("drip", 2, 0.4, "Fat drips on the coals", reach=12, loud=-29.0, fade_out=0.12, limit=4.0,
        when=("Fat dripping on the coals under the spit (1.6)."))
def drip(v, rng):
    """a drop of fat falling on hot coals: the tick of it landing, a bright small 'tsst' as it boils off and a faint pop of its
    steam. v2 has a second, smaller drop close behind"""
    dur = 0.4
    m = Bus(dur)
    drops = [(0.0, 1.0)] if v == 0 else [(0.0, 1.0), (0.17, 0.5)]
    for at, g in drops:
        m.add(spit_pop(rng, size=rng.uniform(0.7, 1.0), sizzle=1.6), at, g)
        m.add(nrm(bubble(rng.uniform(1.0, 1.8), rng, rise=0.6, max_dur=0.06)), at + 0.002, 0.18 * g)
    return kitchen(lp(fit(m.out(), ns(dur)), 10000.0), 0.06, 5329 + v)


@effect("flip", 3, 0.6, "Food is turned", reach=12, loud=-26.0, fade_out=0.15,
        when=("TURN answered (1.4, 1.6): the turner under food on the pan or a tray; the cookshop keeper's flip (7.5)."))
def flip(v, rng):
    """a turner slid under food on a hot pan and flicked over: the iron blade scraping under it (a thin metallic rasp over the
    pan), the little push of the flick, the food landing back with a soft slap and the sizzle of its fresh side on the iron
    (v3 turns two pieces, one after the other)"""
    dur = 0.6
    m = Bus(dur)
    sd = [0.14, 0.12, 0.15][v]
    st = tvec(sd)
    scrape = tvf(colored(sd, rng, -0.5), "bp", 2200.0 + 1200.0 * st / sd, 1.3) * bell(sd, 0.4, 1.2, 1.5) * (0.5 + 0.5 * smooth_random(sd, rng, 90))
    m.add(scrape, 0.0, 0.5)
    m.add(resonators(scrape, [2100.0, 3300.0, 5200.0], [0.02, 0.015, 0.01], [1.0, 0.6, 0.3]), 0.0, 0.25)
    m.add(ping(0.05, [1800.0, 3100.0], [0.012, 0.008], [1.0, 0.5], rng, ex_len=0.0005, hi=8000.0), sd, 0.3)
    lands = [[sd + 0.1]] if v < 2 else [[sd + 0.1], [sd + 0.2]]
    for k, (at,) in enumerate(lands):
        g = 1.0 if k == 0 else 0.75
        m.add(tf.thud(0.08, rng, f=210.0, decay=0.014, tone=0.2), at, 0.5 * g)
        m.add(bp(white(0.03, rng), 500.0, 4000.0) * np.exp(-tvec(0.03) / 0.006), at, 0.35 * g)
        sz = tvec(0.3)
        m.add(hp(colored(0.3, rng, -0.5), 3000.0) * np.clip(sz / 0.004, 0, 1) * np.exp(-sz / 0.11), at, 0.55 * g, end_fade=0.12)
        m.add(grain_cloud(0.3, rng, 1300.0 * np.exp(-sz / 0.1) + 10.0, decay=0.0004, f_lo=2500.0, f_hi=9500.0), at, 0.3 * g,
              end_fade=0.12)
    return kitchen(lp(fit(m.out(), ns(dur)), 10500.0), 0.07, 5331 + v)


@effect("boil_over", 2, 1.2, "A pot boils over", loud=-23.0, fade_out=0.3,
        when=("The BOIL_OVER call's sign (moment 10): foam spilling and the lid rattling; the burst if the call is missed."))
def boil_over(v, rng):
    """a pot boiling over onto the hot iron and the fire: the foam heaving up and spilling with a heavy burble, a rush of steam
    hissing as it hits and spitting, and the lid chattering on the pot's rim with the steam under it. v2 is a softer spill and
    a longer rattle"""
    dur = 1.2
    m = Bus(dur)
    t = tvec(dur)
    s = np.clip(t / 0.09, 0, 1) ** 1.3 * np.exp(-np.maximum(t - 0.12, 0) / [0.55, 0.42][v])
    m.add(tvf(colored(dur, rng, -0.8), "bp", 4200.0 + 1200.0 * s, 0.6) * s * (0.7 + 0.3 * smooth_random(dur, rng, 30)), 0.0, 1.0)
    m.add(grain_cloud(dur, rng, 1100.0 * s + 15.0, decay=0.0006, f_lo=1500.0, f_hi=7500.0, size_var=0.8) * s, 0.0, 0.45)
    m.add(ship.bubbles(0.7, rng, 55.0 * np.exp(-tvec(0.7) / 0.35) + 4.0, 3.0, 9.0, beta=1.6, level=1.0, weight=0.5, rise=0.3), 0.0, 0.35)
    m.add(lp(brown(0.5, rng), 300.0, 2) * bell(0.5, 0.25, 1.3, 1.6), 0.0, 0.3)
    n_clink = [9, 12][v]
    at = 0.18
    for i in range(n_clink):
        f = rng.uniform(780.0, 1150.0)
        g = (0.55 + 0.45 * rng.random()) * np.exp(-max(0.0, at - 0.5) / 0.5)
        m.add(nrm(iron_ring(0.1, rng, [f], t60=0.045, gains=[1.0])), at, 0.5 * g)
        m.add(nrm(hann_burst(0.002, rng, 1500.0, 7000.0)), at, 0.12 * g)
        at += rng.uniform(0.055, 0.1) * (1.0 + 0.5 * i / n_clink)
    return kitchen(lp(fit(m.out(), ns(dur)), 10500.0), 0.08, 5333 + v)


@effect("burnt", 2, 1.2, "A pot burns", loud=-23.0, fade_out=0.3,
        when=("The pot burns (moment 20): a hiss, a crackle and a sour glop."))
def burnt(v, rng):
    """a pot gone to a burn: a hiss of steam and scorched fat, a dry crackle of the crust catching and blackening, then a sour,
    thick 'glop' as the last of the liquid collapses on the iron (v2 glops twice)"""
    dur = 1.2
    m = Bus(dur)
    m.add(hiss(0.9, rng, 2500.0, 8500.0, attack=0.02, tau=0.3, color=-0.8), 0.0, 1.0, end_fade=0.3)
    cr = 0.95
    ct = tvec(cr)
    m.add(grain_cloud(cr, rng, 420.0 * np.exp(-ct / 0.4) + 25.0, decay=0.0007, f_lo=1000.0, f_hi=6000.0, size_var=0.9), 0.0, 0.6,
          end_fade=0.35)
    for i in range(int(rng.integers(3, 6))):
        m.add(spit_pop(rng, size=rng.uniform(0.5, 1.0), sizzle=0.3), rng.uniform(0.05, 0.6), rng.uniform(0.2, 0.4))
    glops = [(0.55, 125.0, 1.0)] if v == 0 else [(0.52, 140.0, 0.9), (0.75, 105.0, 1.0)]
    for at, f0, g in glops:
        m.add(blup(rng, f0, 0.07, rising=False, pop=0.3), at, 0.9 * g)
        m.add(lp(brown(0.25, rng), 250.0, 2) * bell(0.25, 0.3, 1.2, 1.6), at, 0.25 * g)
    return kitchen(lp(fit(m.out(), ns(dur)), 9500.0), 0.08, 5335 + v)
# fire>>


# <<chimes
# =========================================================================== the chimes
D5, FS5, A5, D6 = 587.33, 739.99, 880.0, 1174.66  # the tier motif: a D major arpeggio


def bar_note(dur, rng, f, t60=0.6, bright=1.0):
    """one note of a wooden hand-chime: a tuned bar of rosewood struck with a soft mallet (partials at 1, 3.93 and 9.2 times the
    pitch, the upper ones gone in a moment), the mallet's soft tick, and the hollow knock of the bar on its cord"""
    r = np.array([1.0, 3.93, 9.2]) * rng.uniform(0.998, 1.002, 3)
    y = tf.ring(dur, f * r, t60 * np.array([1.0, 0.3, 0.1]), [1.0, 0.4 * bright, 0.14 * bright], rng, doublet=0.0012,
                doublet_amp=0.5, attack=0.0008)
    t = tvec(0.014)
    mallet = lp(white(0.014, rng), 3500.0, 2) * np.exp(-t / 0.002) * np.clip(t / 0.0008, 0, 1)
    m = Mix(dur)
    m.add(nrm(y), 0.0, 1.0)
    m.add(nrm(mallet), 0.0, 0.12 * bright)
    m.add(nrm(oak(0.06, rng, f * 0.5, t60=0.02, hard=0.4, ex=0.002, lo=150.0)), 0.0, 0.08)
    return m.out()


def shimmer(dur, rng, f, depth=1.0):
    """the glimmer on a last note: two faint upper partials a few cycles a second apart from each other, so that they swell and
    fade against each other as the note dies, and a thread of high sparkle above them"""
    t = tvec(dur)
    env = np.clip(t / 0.03, 0, 1) * np.exp(-t / 0.55)
    y = np.zeros(len(t))
    for fr, g, beat in ((f * 2.0, 0.5, 4.6), (f * 3.0, 0.35, 6.1), (f * 4.0, 0.25, 7.3)):
        ph = rng.uniform(0, 6.28)
        y += g * (np.sin(2 * np.pi * fr * t + ph) + np.sin(2 * np.pi * (fr + beat) * t + 2 * ph))
    return y * env * depth


MOTIFS = {"plain": [(0.0, D5)], "sound": [(0.0, D5), (0.24, A5)], "fine": [(0.0, D5), (0.2, FS5), (0.4, A5)],
          "hallmark": [(0.0, D5), (0.19, FS5), (0.38, A5), (0.58, D6)]}


def motif(tier, dur, rng, t60=0.55):
    m = Mix(dur)
    notes = MOTIFS[tier]
    for i, (at, f) in enumerate(notes):
        last = i == len(notes) - 1
        g = [0.8, 0.85, 0.95, 1.0][min(i, 3)] if len(notes) > 1 else 1.0
        m.add(bar_note(dur - at, rng, f * rng.uniform(0.999, 1.001), t60=t60 * (1.6 if last else 1.0)), at, g)
        if last and tier == "hallmark":
            m.add(shimmer(dur - at, rng, f), at, 0.16)
    return m.out()


@effect("tier.plain", 1, 0.6, "A plain dish", reach=12, loud=-28.0, fade_out=0.2, limit=3.0,
        when=("The first bowl of a batch is served (moment 18): a Plain dish, one note."))
def tier_plain(v, rng):
    """a plain dish: one note on a wooden hand-chime (D), quiet"""
    return kitchen(motif("plain", 0.6, rng, t60=0.45), 0.06, 5341)


@effect("tier.sound", 1, 0.9, "A sound dish", reach=12, loud=-27.0, fade_out=0.25, limit=3.0,
        when=("The first bowl of a batch is served (moment 18): a Sound dish, two notes."))
def tier_sound(v, rng):
    """a sound dish: two notes (D, A), quiet"""
    return kitchen(motif("sound", 0.9, rng, t60=0.5), 0.06, 5342)


@effect("tier.fine", 1, 1.3, "A fine dish", reach=12, loud=-27.0, fade_out=0.35, limit=3.0,
        when=("The first bowl of a batch is served (moment 18): a Fine dish, three notes."))
def tier_fine(v, rng):
    """a fine dish: three notes (D, F sharp, A), quiet"""
    return kitchen(motif("fine", 1.3, rng, t60=0.55), 0.06, 5343)


@effect("tier.hallmark", 1, 2.0, "A hallmarked dish", reach=12, loud=-26.0, fade_out=0.6, limit=3.0,
        when=("The first bowl of a batch is served (moment 18): a Hallmarked dish, four notes, the last with a shimmer."))
def tier_hallmark(v, rng):
    """a hallmarked dish: four notes (D, F sharp, A, D above), the last ringing on in a shimmer, quiet"""
    return kitchen(motif("hallmark", 2.0, rng, t60=0.6), 0.07, 5344)


SOFT_BELL = [  # (multiple of the strike note, level dB, T60 s, beat Hz): a small bronze bell, struck softly, mostly its low modes
    (0.5, -9.0, 1.3, 0.7), (1.0, 0.0, 1.2, 1.1), (1.19, -7.0, 1.0, 1.5), (1.5, -9.0, 0.8, 1.5), (2.0, -11.0, 0.6, 1.9),
    (2.62, -17.0, 0.35, 2.4), (3.0, -19.0, 0.3, 2.7), (4.0, -23.0, 0.2, 3.2),
]


@effect("ready", 1, 1.2, "A dish is ready", loud=-24.0, fade_out=0.35, limit=3.0,
        when=("The dish becomes ready (moment 15): a soft low chime at the start of its window."))
def ready(v, rng):
    """a dish ready: one soft, low chime, a small bronze bell touched rather than struck (a gentle G with its hum an octave below
    and the minor third that makes a bell a bell), ringing on and beating softly as it dies"""
    dur = 1.2
    modes = bell_modes(392.0, rng_for("cook", "ready", "bell"), table=SOFT_BELL)
    m = Mix(dur)
    m.add(bell_blow(dur, modes, hard=0.5, fc=1800.0, build=0.004), 0.0, 1.0)
    m.add(nrm(oak(0.05, rng, 700.0, t60=0.015, hard=0.3, ex=0.002, lo=200.0)), 0.0, 0.08)
    return kitchen(lp(fit(m.out(), ns(dur)), 7000.0), 0.08, 5345)


@effect("ripe", 1, 0.5, "A dish is at its best", loud=-27.0, fade_out=0.2, limit=3.0,
        when=("The dish is at its best (moment 15): one small bright ting at the centre of the window."))
def ripe(v, rng):
    """a dish at its best: one small bright ting (a little steel bell, A above the staff, its upper partials a shimmering pair)"""
    dur = 0.5
    f = 1760.0
    y = tf.ring(dur, f * np.array([1.0, 2.32, 4.12, 6.3]), [0.4, 0.2, 0.12, 0.07], [1.0, 0.4, 0.22, 0.1], rng, doublet=0.002,
                doublet_amp=0.6, attack=0.0004)
    t = tvec(0.004)
    tick = bp(white(0.004, rng), 3000.0, 12000.0) * np.exp(-t / 0.0005) * np.clip(t / 0.0002, 0, 1)
    m = Mix(dur)
    m.add(nrm(y), 0.0, 1.0)
    m.add(nrm(tick), 0.0, 0.15)
    return kitchen(fit(m.out(), ns(dur)), 0.07, 5346)


@effect("buff", 1, 1.0, "A meal takes hold", loud=-19.5, fade_out=0.3, limit=3.0,
        when=("A meal takes hold (moment 23): the chime the eater alone hears, played by Part F at 0.6 of its level and at "
              "a pitch from 0.9 (a plain dish) to 1.2 (a hallmarked one)."))
def buff(v, rng):
    """a meal taking hold: a soft, warm chime, played to the eater alone (the game plays it at 0.6 of its level and from pitch 0.9
    to 1.2 by the dish's tier). A round bell-like note (D, partials at 1, 2, 3 and 4.2 times it, the low ones the strongest) that
    blooms in a hundredth of a second and fades slowly, a fifth (A) coming in under it a moment later, glowing and beating as
    both fade"""
    dur = 1.0
    m = Mix(dur)
    for at, f, g in ((0.0, D5, 1.0), (0.09, A5, 0.5)):
        y = tf.ring(dur - at, f * np.array([1.0, 2.0, 3.0, 4.2]), [0.95, 0.6, 0.35, 0.2], [1.0, 0.45, 0.2, 0.08], rng, doublet=0.0016,
                    doublet_amp=0.7, attack=0.006)
        m.add(nrm(y), at, g)
    t = tvec(0.01)
    m.add(nrm(lp(white(0.01, rng), 2500.0, 2) * np.exp(-t / 0.002)), 0.0, 0.05)
    return kitchen(fit(m.out(), ns(dur)), 0.06, 5347)
# chimes>>


# <<iron
# =========================================================================== the oven, the spit and the crank
def door_modes(rng):
    """the iron door of a bake oven: the same plate every time it swings, ringing at a few low notes"""
    return timber_modes(rng_for("cook", "door"), 160.0, 2600.0, 22, 0.14, 0.03, peaks=(260, 620))


@effect("oven_open", 2, 0.9, "An oven door opens", loud=-23.0, fade_out=0.3,
        when=("The oven door swings open (1.6): a player looks at it with food in hand, or the baker's peel scene (7.5)."))
def oven_open(v, rng):
    """the iron door of a brick oven opened: the latch lifted off its keeper (a tick and a small clink), the heavy door groaning
    on its pivot with a squeal of dry iron, swinging back to rest against the oven's face with a dull knock, and the oven's
    breath coming out over it, a warm rush with the low roar of the fire behind. v2 is a quicker door that squeals twice"""
    dur = 0.9
    m = Bus(dur)
    m.add(nrm(hann_burst(0.002, rng, 2000.0, 8000.0)), 0.0, 0.25)
    m.add(nrm(ping(0.1, [1900.0 * rng.uniform(0.97, 1.03), 3100.0], [0.04, 0.025], [1.0, 0.5], rng)), 0.002, 0.45)
    m.add(nrm(oak(0.12, rng, 380.0, t60=0.03, hard=0.5, ex=0.002, lo=200.0)), 0.004, 0.3)
    sd = [0.42, 0.34][v]
    sq = squeal(sd, rng, path([(0, 420.0), (0.3 * sd, 520.0), (sd, 700.0)], sd), bell(sd, 0.5, 1.2, 1.5), harmonics=8, tilt=1.0,
                chatter=0.5, wobble=0.02, peak_hz=1100.0)
    m.add(sq, 0.06, 0.55)
    gd = sd + 0.08
    rate = path([(0, 14.0), (0.3 * gd, 55.0), (0.7 * gd, 38.0), (gd, 12.0)], gd)
    m.add(creak_voice(gd, rng, rate, bell(gd, 0.45, 1.2, 1.5), door_modes(rng), vowel=[(path([(0, 250.0), (gd, 380.0)], gd), 4.0, 0.6)],
                      jitter=0.1), 0.05, 0.6)
    if v == 1:
        m.add(squeal(0.12, rng, path([(0, 560.0), (0.12, 640.0)], 0.12), bell(0.12, 0.4, 1.2, 1.5), harmonics=6, chatter=0.5,
                     wobble=0.02, peak_hz=1100.0), 0.06 + sd + 0.02, 0.25)
    at = 0.06 + sd + 0.06
    m.add(pot_ring(0.35, rng, 245.0, t60=0.3, wet=0.0), at, 0.45)
    m.add(thump(0.15, 110.0, 70.0, rng, decay=0.035, click=0.05, noise=0.5, nlp=450.0), at, 0.4)
    breath = lp(colored(0.62, rng, -2.0), 1500.0, 2) * bell(0.62, 0.3, 1.4, 1.8) * (0.8 + 0.2 * smooth_random(0.62, rng, 9))
    m.add(breath, 0.12, 0.55)
    m.add(lp(brown(0.6, rng), 260.0, 2) * bell(0.6, 0.45, 1.5, 1.6), 0.15, 0.4)
    return through_brick(lp(fit(m.out(), ns(dur)), 9000.0), 0.12, 5351 + v, cut=5000.0)


@effect("oven_shut", 2, 0.5, "An oven door shuts", loud=-22.0, fade_out=0.2, limit=3.0,
        when=("The oven door shuts (1.6): the player looks away, or 40 ticks go by."))
def oven_shut(v, rng):
    """the iron door of a brick oven slammed to: the plate of it meeting its frame with a deep clang (a plate's own inharmonic
    notes, a low one the loudest, ringing on a third of a second), the brick giving a dull thud under it, the latch dropping
    into its keeper with a clink and the handle rattling. v2 is a lighter shove"""
    dur = 0.5
    m = Bus(dur)
    g = [1.0, 0.75][v]
    f0 = [265.0, 285.0][v]
    plate = modal(0.5, metal_partials(f0, "plate", n=11, rng=rng, spread=0.01), [0.34, 0.3, 0.26, 0.22, 0.18, 0.15, 0.13, 0.11, 0.09, 0.08, 0.07],
                  [1.0, 0.8, 0.7, 0.55, 0.5, 0.42, 0.35, 0.3, 0.25, 0.2, 0.15], rng)
    m.add(plate, 0.0, 1.0 * g)
    m.add(hann_burst(0.003, rng, 800.0, 7000.0), 0.0, 0.35 * g)
    m.add(thump(0.25, 100.0, 60.0, rng, decay=0.06, click=0.0, noise=0.4, nlp=400.0), 0.0, 0.6 * g)
    m.add(nrm(ping(0.12, [2100.0 * rng.uniform(0.97, 1.03), 3300.0], [0.05, 0.03], [1.0, 0.5], rng)), 0.07, 0.4)
    for k in range(3):
        m.add(nrm(ping(0.08, [rng.uniform(1100.0, 1700.0)], [0.035], [1.0], rng)), 0.11 + 0.035 * k + rng.uniform(0, 0.01),
              0.18 * 0.7 ** k)
    return through_brick(fit(m.out(), ns(dur)), 0.1, 5353 + v, cut=6000.0)


def pawl_tick(rng, f=1.0, load=1.0):
    """a ratchet's pawl dropping over a tooth: a tick of iron on iron, its small bright ring and the dull little knock of the
    wooden handle it is cranked by"""
    m = Mix(0.14)
    m.add(nrm(hann_burst(0.002, rng, 2500.0, 9000.0)), 0.0, 0.3)
    m.add(nrm(ping(0.1, [1650.0 * f * rng.uniform(0.96, 1.04), 2480.0 * f], [0.04, 0.03], [1.0, 0.6], rng)), 0.0015, 0.8)
    m.add(nrm(oak(0.12, rng, 330.0 * f, t60=0.04, hard=0.7, ex=0.002, lo=200.0)), 0.002, 0.6 * load)
    return m.out()


@effect("crank", 3, 0.4, "A crank turns", reach=12, loud=-29.0, fade_out=0.1, limit=4.0,
        when=("One click of the Spit's crank, a quarter turn at a time (1.4, 1.6: TURN answered with an empty hand)."))
def crank(v, rng):
    """a spit's crank turned a quarter: the ratchet's pawl clicking over its teeth (two clicks, three quick ones, one with the
    handle squeaking in the hand), the wooden handle knocking at each"""
    dur = 0.4
    m = Bus(dur)
    plan = [[(0.0, 1.0, 1.0), (0.085, 1.04, 0.8)], [(0.0, 0.97, 1.0), (0.06, 1.0, 0.8), (0.125, 1.03, 0.75)],
            [(0.0, 1.02, 1.0)]][v]
    for at, f, g in plan:
        m.add(pawl_tick(rng, f, g), at, g)
    if v == 2:
        m.add(squeal(0.16, rng, path([(0, 980.0), (0.16, 1250.0)], 0.16), bell(0.16, 0.4, 1.2, 1.5), harmonics=5, chatter=0.5, wobble=0.02,
                     peak_hz=1900.0), 0.03, 0.25)
    return kitchen(fit(m.out(), ns(dur)), 0.05, 5355 + v)
# iron>>


# <<board
# =========================================================================== the board, the counter and the larder
def crunch(dur, rng, rate, lo=900.0, hi=6500.0, decay=0.0006, size_var=1.0):
    """something crisp breaking: many little fractures at once, each a short click of its own (the crust, the root, the rind),
    their number following `rate` (a number or a curve), with a few bigger cracks among them"""
    n = ns(dur)
    y = grain_cloud(dur, rng, rate, decay=decay, f_lo=lo, f_hi=hi, size_var=size_var, kernels=6)
    big = np.zeros(n)
    r = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    for i in np.nonzero(rng.random(n) < r * 0.012 / SR)[0]:
        e = ping(0.012, [rng.uniform(1100.0, 2600.0)], [0.004], [1.0], rng, ex_len=0.0005, hi=8000.0)
        m = min(len(e), n - i)
        big[i:i + m] += e[:m] * rng.uniform(0.5, 1.0)
    return y / (np.max(np.abs(y)) + 1e-12) + 1.2 * big / (np.max(np.abs(big)) + 1e-12)


@effect("chop", 3, 0.3, "Something is chopped", reach=10, loud=-25.0, fade_out=0.08, limit=3.0,
        when=("A knife chopping on the Prep board."))
def chop(v, rng):
    """a knife chopping on a board: the edge meeting the wood with a short hard 'tock' (the board's own low knock, the blade's
    brief thin ring), the food parting under it in a small crisp crunch. v1 a carrot, crisp and bright; v2 an onion, softer
    and wetter; v3 a herb bunch, light, with the blade settling"""
    dur = 0.3
    m = Bus(dur)
    f0 = [250.0, 215.0, 280.0][v]
    hard = [1.0, 0.7, 0.8][v]
    m.add(oak(0.2, rng, f0, t60=0.05, hard=hard, ex=0.002, lo=200.0), 0.0, 1.0)
    m.add(hann_burst(0.002, rng, 1500.0, 7000.0), 0.0, 0.3 * hard)
    m.add(nrm(ping(0.06, [3600.0 * rng.uniform(0.95, 1.05), 6900.0], [0.012, 0.006], [1.0, 0.5], rng)), 0.0, 0.12 * hard)
    cd = [0.07, 0.09, 0.06][v]
    ct = tvec(cd)
    env = np.clip(ct / 0.004, 0, 1) * np.exp(-ct / (cd * 0.4))
    m.add(crunch(cd, rng, [1700.0, 700.0, 900.0][v] * env + 10.0, lo=[1500.0, 700.0, 1800.0][v], hi=[7000.0, 4000.0, 8000.0][v]) * env, 0.001,
          [0.7, 0.5, 0.4][v])
    if v == 2:
        m.add(nrm(ping(0.05, [3300.0, 6300.0], [0.01, 0.005], [1.0, 0.5], rng)), 0.09, 0.1)
        m.add(nrm(oak(0.1, rng, 300.0, t60=0.02, hard=0.4, ex=0.002, lo=200.0)), 0.09, 0.15)
    return kitchen(tame(lp(fit(m.out(), ns(dur)), 10000.0), 15.0), 0.06, 5361 + v)


@effect("slice", 3, 0.5, "A pie is sliced", reach=10, loud=-26.0, fade_out=0.15,
        when=("The Knife on a pie or tart (1.6): the baker's and the cookshop keeper's slice scene (7.5)."))
def slice_sound(v, rng):
    """a knife slicing a pie: the point breaking the crust (a fine, dense crackle of the pastry that comes up and goes as the
    blade draws through it), a soft drag through the filling, the edge reaching the tin with a small tick and a ring. v2 the
    slice slid out from the rest with a short scrape of pastry on tin; v3 a heavier cut"""
    dur = 0.5
    m = Bus(dur)
    sd = [0.2, 0.18, 0.24][v]
    env = bell(sd, 0.35, 1.2, 1.6)
    m.add(crunch(sd, rng, [1700.0, 1500.0, 1900.0][v] * env + 20.0, lo=1500.0, hi=7500.0, decay=0.0004, size_var=0.8) * env, 0.0, 1.0)
    m.add(lp(colored(sd, rng, -2.0), 1400.0, 2) * env ** 1.3 * (0.6 + 0.4 * smooth_random(sd, rng, 30)), 0.0, 0.35)
    at = sd - 0.02
    m.add(nrm(ping(0.15, [1250.0 * rng.uniform(0.96, 1.04), 2150.0, 3400.0], [0.06, 0.04, 0.025], [1.0, 0.5, 0.25], rng)), at, 0.4)
    m.add(nrm(oak(0.1, rng, 240.0, t60=0.025, hard=0.4, ex=0.002, lo=150.0)), at, 0.25)
    if v == 1:
        sc = bp(colored(0.12, rng, -1.0), 1000.0, 4500.0) * bell(0.12, 0.4, 1.2, 1.5) * (0.5 + 0.5 * smooth_random(0.12, rng, 70))
        m.add(sc, at + 0.06, 0.25)
    return kitchen(lp(fit(m.out(), ns(dur)), 10000.0), 0.06, 5364 + v)


@effect("carve", 3, 0.6, "A roast is carved", reach=10, loud=-26.0, fade_out=0.15,
        when=("The Knife on the spit's joint (1.6): the innkeeper's rest-day carving (7.5)."))
def carve(v, rng):
    """a knife carving a roast: the point through the crackling of the crust, then the long, wet, soft stroke of the blade drawn
    through the meat (a low, moist rasp with a few small squelches in it), a last tick of the edge on the plate under it.
    v1 two strokes, v2 one long, v3 three short"""
    dur = 0.6
    m = Bus(dur)
    strokes = [[(0.0, 0.24), (0.27, 0.2)], [(0.0, 0.42)], [(0.0, 0.14), (0.16, 0.14), (0.32, 0.14)]][v]
    for k, (at, sd) in enumerate(strokes):
        env = bell(sd, 0.4, 1.2, 1.5)
        if k == 0:
            m.add(crunch(0.09, rng, 1000.0 * np.exp(-tvec(0.09) / 0.04) + 40.0, lo=1200.0, hi=6000.0, decay=0.0005) * np.exp(-tvec(0.09) / 0.04),
                  at, 0.55)
        rasp = tvf(colored(sd, rng, -2.0), "bp", 500.0 + 900.0 * env, 0.9) * env ** 1.2 * (0.6 + 0.4 * smooth_random(sd, rng, 40))
        m.add(rasp, at, 0.6)
        m.add(ship.bubbles(sd, rng, 40.0 * env, 0.8, 2.5, beta=1.8, level=1.0, weight=0.4, rise=0.4), at, 0.25)
    end = strokes[-1][0] + strokes[-1][1]
    m.add(nrm(ping(0.15, [1400.0 * rng.uniform(0.96, 1.04), 2300.0, 3600.0], [0.07, 0.045, 0.03], [1.0, 0.5, 0.25], rng)), end, 0.3)
    m.add(nrm(oak(0.1, rng, 260.0, t60=0.03, hard=0.4, ex=0.002, lo=150.0)), end, 0.2)
    return kitchen(lp(fit(m.out(), ns(dur)), 9000.0), 0.06, 5367 + v)


@effect("loaf_down", 2, 0.5, "Loaves are set down", reach=12, loud=-25.0, fade_out=0.15,
        when=("The baker sets the loaves on the counter after the peel (7.5)."))
def loaf_down(v, rng):
    """loaves set down on a counter: each a soft, dull thud of a heavy crust on a scrubbed plank (the plank's own low knock, a
    crinkle of crust and a rustle of flour); two loaves, one a beat behind the other, v2 three"""
    dur = 0.5
    m = Bus(dur)
    plan = [[(0.0, 1.0, 1.0), (0.13, 0.85, 1.1)], [(0.0, 1.0, 1.0), (0.1, 0.8, 0.9), (0.21, 0.9, 1.15)]][v]
    for at, g, f in plan:
        m.add(tf.thud(0.12, rng, f=150.0 * f, decay=0.02, tone=0.25), at, 0.8 * g)
        m.add(oak(0.2, rng, 175.0 * f, t60=0.07, hard=0.5, ex=0.004, lo=120.0), at, 0.7 * g)
        m.add(crunch(0.06, rng, 600.0 * np.exp(-tvec(0.06) / 0.02) + 20.0, lo=1500.0, hi=6000.0, decay=0.0007, size_var=0.8) * np.exp(-tvec(0.06) / 0.02),
              at + 0.003, 0.25 * g)
        m.add(bp(white(0.08, rng), 2500.0, 7000.0) * bell(0.08, 0.3, 1.2, 1.5), at + 0.01, 0.12 * g)
    return kitchen(lp(fit(m.out(), ns(dur)), 8000.0), 0.07, 5370 + v)


@effect("plate", 2, 0.4, "A plate is set down", reach=8, loud=-27.0, fade_out=0.12, limit=3.0,
        when=("A plate set down on a table (the Plate station, 3.5)."))
def plate(v, rng):
    """a plate set down on a table: the foot of it meeting the wood with a short knock, the glaze ringing a moment in its own
    thin notes, and the plate settling, rattling twice as it comes to rest (v1 a glazed plate, v2 a pewter one, duller)"""
    dur = 0.4
    m = Bus(dur)
    f0 = [1450.0, 1050.0][v]
    t60 = [0.1, 0.16][v]
    ratios = np.array([1.0, 1.65, 2.9, 4.6, 6.4]) * rng.uniform(0.99, 1.01, 5)
    m.add(tf.ring(0.4, f0 * ratios, t60 * np.array([1.0, 0.75, 0.5, 0.35, 0.25]), [1.0, 0.7, 0.45, 0.3, 0.2], rng, doublet=0.003, doublet_amp=0.6), 0.0, 0.6)
    m.add(oak(0.15, rng, 210.0, t60=0.04, hard=0.6, ex=0.003, lo=150.0), 0.0, 0.8)
    m.add(hann_burst(0.002, rng, 1200.0, 6000.0), 0.0, 0.25)
    for k in range(2):
        at = 0.07 + 0.045 * k + rng.uniform(0, 0.01)
        m.add(nrm(ping(0.06, [f0 * 1.1, f0 * 1.9], [0.03, 0.02], [1.0, 0.5], rng)), at, 0.22 * 0.6 ** k)
        m.add(nrm(oak(0.06, rng, 230.0, t60=0.015, hard=0.4, ex=0.002, lo=150.0)), at, 0.18 * 0.6 ** k)
    return kitchen(lp(fit(m.out(), ns(dur)), 10000.0), 0.06, 5373 + v)


@effect("chalk", 2, 1.0, "Chalk scratches a slate", reach=8, loud=-27.0, fade_out=0.25,
        when=("The Menu board's chalked text going up (1.1, 5.4)."))
def chalk(v, rng):
    """a line chalked on the Menu board's slate: the chalk set down with a tick, a dry gritty scratch of a first short stroke and
    a quicker second (the chalk catching and slipping on the stone, the slate's own faint ring under it), a squeak where it bites,
    and a tap for the full stop. v2 a longer first line and an underlining stroke"""
    dur = 1.0
    m = Bus(dur)
    plan = [[(0.0, 0.3, 1.0), (0.4, 0.26, 1.2)], [(0.0, 0.42, 0.9), (0.52, 0.3, 1.25)]][v]
    for at, sd, sp in plan:
        m.add(nrm(tf.chalk_tick(rng, 0.5)), at, 0.3)
        m.add(tf.chalk_stroke(sd, rng, sp), at + 0.004, 1.0)
    sq_at = plan[0][0] + plan[0][1] * 0.55
    m.add(nrm(tf.chalk_squeak(0.06, rng, [2500.0, 2300.0][v])), sq_at, 0.2)
    last = plan[-1][0] + plan[-1][1] + 0.1
    m.add(nrm(tf.chalk_tick(rng, 1.0)), last, 0.5)
    return kitchen(lp(hp(lead_in(fit(m.out(), ns(dur))), 300.0), 10500.0), 0.05, 5375 + v)


@effect("larder", 2, 0.5, "A larder opens", reach=8, loud=-27.0, fade_out=0.15,
        when=("A Larder opened (1.1)."))
def larder(v, rng):
    """a slatted larder door opened: the thumb-latch lifted (a tick and a small clink), the dry wood of the door rubbing on its
    frame with a short soft creak, the slats rattling in it, a draught of cool air and the faint clink of crockery on the shelves
    inside. v2 a heavier door, slower"""
    dur = 0.5
    m = Bus(dur)
    m.add(nrm(hann_burst(0.002, rng, 1800.0, 7000.0)), 0.0, 0.25)
    m.add(nrm(ping(0.08, [2300.0 * rng.uniform(0.96, 1.04), 3700.0], [0.03, 0.02], [1.0, 0.5], rng)), 0.002, 0.45)
    cd = [0.2, 0.3][v]
    rate = path([(0, 20.0), (0.4 * cd, 60.0), (cd, 18.0)], cd)
    modes = timber_modes(rng_for("cook", "larder", "door"), 180.0, 2400.0, 18, 0.07, 0.02, peaks=(420, 900))
    m.add(creak_voice(cd, rng, rate, bell(cd, 0.45, 1.2, 1.5), modes, jitter=0.1, rub=0.15), 0.03, 0.55)
    for k in range(5 + 2 * v):
        m.add(nrm(oak(0.05, rng, rng.uniform(650.0, 1250.0), t60=0.014, hard=0.8, ex=0.001, lo=300.0)), 0.04 + rng.uniform(0, cd) * 0.9, rng.uniform(0.1, 0.3))
    m.add(lp(colored(0.3, rng, -2.0), 1200.0, 2) * bell(0.3, 0.35, 1.3, 1.7), 0.08, 0.18)
    for k in range(int(rng.integers(1, 3))):
        m.add(nrm(ping(0.07, [rng.uniform(2300.0, 3300.0), 5100.0], [0.03, 0.015], [1.0, 0.4], rng)), 0.15 + 0.1 * k + rng.uniform(0, 0.04), 0.1)
    return kitchen(lp(fit(m.out(), ns(dur)), 9000.0), 0.07, 5377 + v)
# board>>


# <<serving
# =========================================================================== calls, the ladle and the bowl
@effect("call", 2, 0.3, "The pot calls for attention", reach=12, loud=-21.0, fade_out=0.08, limit=3.0,
        when=("The cue of every call (moment 8, 1.4): two knocks near the vessel before its sign shows."))
def call(v, rng):
    """the pot calling for you: two short knocks on wood (knuckles on the pot's wooden handle and its hanging post, hollow and
    clear, the second a little higher and softer)"""
    dur = 0.3
    m = Bus(dur)
    plan = [[(0.0, 410.0, 1.0), (0.135, 460.0, 0.8)], [(0.0, 370.0, 1.0), (0.125, 400.0, 0.85)]][v]
    for at, f, g in plan:
        f = f * rng.uniform(0.99, 1.01)
        m.add(knock(0.18, [f, f * 1.8, f * 2.9], [0.07, 0.05, 0.03], [1.0, 0.6, 0.3], rng, lo=200, hi=4500, ex_len=0.0015), at, 1.0 * g)
        m.add(oak(0.1, rng, f * 1.9, t60=0.03, hard=0.9, ex=0.0015, lo=400.0), at, 0.35 * g)
        m.add(hann_burst(0.002, rng, 800.0, 5000.0), at, 0.2 * g)
    return kitchen(tame(lp(fit(m.out(), ns(dur)), 9000.0), 14.0), 0.06, 5381 + v)


@effect("skim", 2, 0.6, "A pot is skimmed", reach=8, loud=-27.0, fade_out=0.15,
        when=("The ladle skims the grey film off the surface (moment 13)."))
def skim(v, rng):
    """a slotted ladle skimming a pot: a soft sweep across the surface (the water slipping through its slots as it goes), the
    droplets falling back from it into the pot, a flick of it over the rim with a small tick of iron"""
    dur = 0.6
    m = Bus(dur)
    sd = [0.3, 0.26][v]
    env = bell(sd, 0.4, 1.3, 1.5)
    m.add(tvf(colored(sd, rng, -1.5), "bp", 500.0 + 1400.0 * env, 0.8) * env ** 1.2 * (0.7 + 0.3 * smooth_random(sd, rng, 20)), 0.0, 0.7)
    m.add(spatter(0.4, rng, 130.0, 0.14, lo=800.0, hi=5500.0), 0.1, 0.7)
    m.add(ship.bubbles(sd, rng, 30.0 * env, 1.0, 3.0, beta=1.8, level=1.0, weight=0.4, rise=0.6), 0.0, 0.25)
    at = sd + 0.04
    m.add(bp(white(0.03, rng), 1500.0, 6000.0) * np.exp(-tvec(0.03) / 0.007), at, 0.25)
    m.add(nrm(ping(0.1, [1750.0 * rng.uniform(0.96, 1.04), 2900.0, 4600.0], [0.05, 0.03, 0.02], [1.0, 0.5, 0.25], rng)), at, 0.4)
    return kitchen(lp(fit(m.out(), ns(dur)), 9500.0), 0.07, 5383 + v)


@effect("serve", 3, 1.0, "A dish is served", reach=12, loud=-25.0, fade_out=0.2,
        when=("A ladleful poured into a bowl (moment 17); the innkeeper's ladling step plays it too (7.3)."))
def serve(v, rng):
    """a ladleful of stew poured into a bowl: the ladle's lip touching the bowl with a clink, the stew running in (thick, so a
    low burble with lumps knocking in it, its note rising as the bowl fills), the last of it dripping off the ladle and a tap of
    the ladle on the bowl's rim. v2 a smaller ladleful, v3 a heavier one with a lump or two"""
    dur = 1.0
    m = Bus(dur)
    pd = [0.58, 0.46, 0.66][v]
    t = tvec(pd)
    u = t / pd
    m.add(nrm(ping(0.12, [2350.0 * rng.uniform(0.97, 1.03), 3900.0, 5600.0], [0.06, 0.04, 0.025], [1.0, 0.5, 0.25], rng)), 0.0, 0.5)
    m.add(tf.ring(0.2, 1150.0 * np.array([1.0, 2.2, 3.6]), [0.09, 0.06, 0.04], [1.0, 0.5, 0.25], rng, doublet=0.003), 0.0, 0.25)
    env = np.clip(t / 0.05, 0, 1) * np.clip((pd - t) / 0.12, 0, 1) ** 1.4 * (0.7 + 0.3 * np.clip(t / 0.2, 0, 1))
    babble = ship.bubbles(pd, rng, 220.0 * env + 8.0, 1.2, 6.0, beta=1.7, level=1.0, weight=0.5, rise=0.4)
    stream = tvf(colored(pd, rng, -1.5) + 0.8 * nrm(babble), "bp", 420.0 * (1.0 + 1.4 * u), 2.2) * env
    m.add(nrm(stream), 0.04, 1.0)
    for k in range([1, 0, 2][v]):
        f0 = float(np.exp(rng.uniform(np.log(120.0), np.log(250.0))))
        m.add(blup(rng, f0, rng.uniform(0.025, 0.04), rising=True, pop=0.15), 0.1 + rng.uniform(0, pd * 0.7), 0.5)
    m.add(lp(colored(pd, rng, -2.5), 1100.0, 2) * env, 0.04, 0.3)
    end = 0.04 + pd
    for i, at in enumerate([end + 0.02, end + 0.09]):
        m.add(ship.drip(rng), at, 0.4 * 0.7 ** i)
    tap = end + [0.13, 0.12, 0.16][v]
    m.add(nrm(ping(0.12, [2300.0 * rng.uniform(0.97, 1.03), 3800.0], [0.05, 0.03], [1.0, 0.5], rng)), tap, 0.25)
    return kitchen(lp(fit(m.out(), ns(dur)), 8500.0), 0.08, 5385 + v)


@effect("ease", 2, 0.6, "A heat is eased", reach=12, loud=-24.0, fade_out=0.15, limit=3.0,
        when=("The heat eased a notch with an empty hand on the heat region (moment 11): the chain hauled, the damper slid."))
def ease(v, rng):
    """the heat eased: the pot's chain hauled hand over hand through its hook (links clanking over the iron, three pulls, each
    a heavier clank) and the damper's iron plate slid across in its guides with a dry rasp and a dull seating knock. v2 two
    pulls and a longer slide"""
    dur = 0.6
    m = Bus(dur)
    pulls = [(0.0, 1.0), (0.13, 0.85), (0.27, 1.0)] if v == 0 else [(0.0, 1.0), (0.17, 0.9)]
    for at, g in pulls:
        f = 820.0 * rng.uniform(0.95, 1.05)
        m.add(nrm(iron_ring(0.2, rng, [f], t60=0.12, gains=[1.0])), at, 0.55 * g)
        m.add(chain_links(rng, count=6, f_lo=1200.0, f_hi=3000.0, span=0.07, decay=0.6, size=1.3), at, 0.6 * g)
        m.add(nrm(hann_burst(0.002, rng, 1500.0, 7000.0)), at, 0.12 * g)
    sd = [0.15, 0.22][v]
    at = pulls[-1][0] + 0.07
    rate = path([(0, 40.0), (0.5 * sd, 120.0), (sd, 60.0)], sd)
    modes = timber_modes(rng_for("cook", "damper"), 400.0, 4200.0, 16, 0.05, 0.015, peaks=(900,))
    m.add(creak_voice(sd, rng, rate, bell(sd, 0.5, 1.2, 1.4), modes, jitter=0.15, rub=0.25, bright=5000.0, unsteady=0.2), at, 0.4)
    seat = at + sd
    m.add(pot_ring(0.3, rng, 230.0, t60=0.2, wet=0.0), seat, 0.3)
    m.add(thump(0.12, 120.0, 80.0, rng, decay=0.03, click=0.05, noise=0.5, nlp=450.0), seat, 0.3)
    return kitchen(lp(fit(m.out(), ns(dur)), 10000.0), 0.07, 5387 + v)
# serving>>


# <<mouth
# =========================================================================== the table: mouths and spoons
def gulp(rng, f0=480.0, size=1.0):
    """a swallow: the throat's wet 'glunk' (a dull note that drops as the gullet closes over it, with the wet of it) and the small
    thud of the muscles"""
    gt = tvec(0.14)
    fg = f0 * (0.42 + 0.58 * np.exp(-gt / 0.03)) * (1.0 + 0.06 * (smooth_random(0.14, rng, 60) - 0.5))
    glunk = np.sin(phase_from_freq(fg, len(gt))) * np.exp(-gt / (0.028 * size))
    wet = tvf(white(0.14, rng), "bp", fg * 1.1, 2.2) * np.exp(-gt / 0.026)
    y = (0.6 * nrm(glunk) + nrm(wet)) * np.clip(gt / 0.002, 0, 1)
    m = Mix(0.14)
    m.add(nrm(y), 0.006, 0.9)
    m.add(nrm(bp(white(0.006, rng), 900.0, 4500.0) * np.exp(-tvec(0.006) / 0.0012)), 0.0, 0.3)
    m.add(nrm(thump(0.1, 165.0, 90.0, rng, decay=0.02, click=0.0, noise=0.3, nlp=500.0)), 0.004, 0.4)
    return m.out()


def slurp(dur, rng, f_start=1700.0, f_end=3000.0, strength=1.0):
    """liquid drawn in through the lips: a thin turbulent rush of air through the narrow opening (a band that climbs as the lips
    close on the rim), small wet bubbles in it, the mouth's vowel shifting and, at the end, the lips parting with a soft smack"""
    t = tvec(dur)
    u = t / dur
    env = np.clip(t / 0.06, 0, 1) ** 1.2 * np.clip((dur - t) / 0.04, 0, 1) * (0.6 + 0.4 * smooth_random(dur, rng, 12))
    air = tvf(colored(dur, rng, -1.0), "bp", f_start + (f_end - f_start) * u ** 1.3, 1.8) * env
    bub = ship.bubbles(dur, rng, 260.0 * env + 10.0, 0.7, 2.2, beta=1.8, level=1.0, weight=0.5, rise=0.5)
    low = lp(formants(colored(dur, rng, -2.0) * env, "u", "i", scale=1.1), 2500.0)
    return strength * (nrm(air) + 0.6 * nrm(bub) + 0.4 * nrm(low))


def chew(count, rng, rate=4.5, moist=1.0, size=1.0):
    """chewing with the mouth shut: a soft wet squelch for each closing of the jaw (low, a little vowel moving in it) with small
    wet pops in it and, now and then, a faint tick of teeth"""
    per = 1.0 / rate
    m = Mix(count * per + 0.12)
    for k in range(count):
        at = k * per * rng.uniform(0.92, 1.08)
        d = 0.09 * size
        sq = lp(formants(colored(d, rng, -1.5), "u", "o", scale=0.95 + 0.1 * rng.random()), 1800.0) * bell(d, 0.4, 1.2, 1.4)
        m.add(nrm(sq), at, 0.8 * rng.uniform(0.7, 1.0))
        m.add(ship.bubbles(d, rng, 260.0 * moist, 0.6, 2.0, beta=1.8, level=1.0, weight=0.4, rise=0.3), at, 0.3 * moist)
        if rng.random() < 0.4:
            m.add(nrm(ping(0.02, [2600.0], [0.004], [1.0], rng, ex_len=0.0003)), at + d * 0.8, 0.05)
    return m.out()


@effect("taste", 2, 0.9, "Someone tastes the pot", reach=8, loud=-27.0, fade_out=0.2,
        when=("Sneak-click the pot with the ladle (moments 5 and 16). The innkeeper's taste scene plays it (7.3; the clip's "
              "ticks 24 and 30 are asked for, which would overlap it: once is enough)."))
def taste(v, rng):
    """tasting the pot from a ladle: a blow across the hot stew (a soft, breathy 'fooo' with the surface rippling under it), then
    the slurp of a sip (a thin rush of air and liquid over the lips with small bubbles in it) and the soft smack of the lips
    after it"""
    dur = 0.9
    m = Bus(dur)
    bd = [0.3, 0.26][v]
    blow = formants(white(bd, rng), "u", "o", scale=1.25) * bell(bd, 0.4, 1.3, 1.5) * (0.7 + 0.3 * smooth_random(bd, rng, 18))
    m.add(lp(blow, 5000.0), 0.0, 0.6)
    m.add(ship.bubbles(bd, rng, 35.0 * bell(bd, 0.5, 1.2, 1.5), 1.5, 4.0, beta=1.8, level=1.0, weight=0.4, rise=0.4), 0.05, 0.12)
    sa = [0.4, 0.34][v]
    sd = [0.3, 0.28][v]
    m.add(slurp(sd, rng, strength=1.0), sa, 1.0)
    m.add(nrm(bp(white(0.03, rng), 200.0, 1200.0) * np.exp(-tvec(0.03) / 0.008)), sa + sd, 0.25)
    m.add(nrm(bp(white(0.005, rng), 1000.0, 5000.0) * np.exp(-tvec(0.005) / 0.001)), sa + sd - 0.005, 0.15)
    return kitchen(lp(fit(m.out(), ns(dur)), 8500.0), 0.06, 5391 + v)


@effect("eat.spoon", 3, 1.6, "Someone eats", reach=8, loud=-28.0, fade_out=0.3, limit=3.0,
        when=("Eating a pot dish from a bowl (3.3): three spoonfuls and a swallow. Also the tavern diners' spoon clinks "
              "(7.4)."))
def eat_spoon(v, rng):
    """eating stew from a bowl with a spoon: three spoonfuls, each the spoon's clink on the glazed bowl and a scrape of its
    bowl-end, a faint slurp as it is taken, and after the last a swallow. v2 slower, ending on a pleased breath out; v3 quicker"""
    dur = 1.6
    m = Bus(dur)
    gap = [0.5, 0.52, 0.44][v]
    first = [0.05, 0.08, 0.04][v]
    at = first
    for k in range(3):
        at += 0.0 if k == 0 else gap * rng.uniform(0.9, 1.1)
        f = [2450.0, 2300.0, 2600.0][v] * rng.uniform(0.96, 1.04)
        m.add(nrm(ping(0.14, [f, f * 2.7, f * 4.9], [0.05, 0.03, 0.02], [1.0, 0.5, 0.25], rng)), at, 0.6)
        m.add(tf.ring(0.2, 1100.0 * np.array([1.0, 2.2, 3.6]), [0.08, 0.05, 0.03], [1.0, 0.5, 0.25], rng, doublet=0.003), at, 0.25)
        sc = bp(colored(0.07, rng, -1.0), 1500.0, 6000.0) * bell(0.07, 0.4, 1.2, 1.5) * (0.5 + 0.5 * smooth_random(0.07, rng, 80))
        m.add(sc, at + 0.015, 0.18)
        m.add(slurp(0.12, rng, 1800.0, 2800.0, strength=0.6), at + 0.2, 0.22)
    sw = at + 0.3
    m.add(gulp(rng, f0=[470.0, 440.0, 500.0][v]), sw, 0.9)
    if v == 1:
        ex = formants(white(0.28, rng), "uh", "a", scale=0.95) * bell(0.28, 0.35, 1.3, 1.6)
        m.add(lp(ex, 3500.0), sw + 0.18, 0.2)
    return kitchen(lp(fit(m.out(), ns(dur)), 8500.0), 0.06, 5393 + v)


@effect("eat.bite", 3, 1.2, "Someone eats", reach=8, loud=-26.0, fade_out=0.25,
        when=("Eating a solid dish (pie slice, tart, cakes, fish, roast, loaf, strips, roots; 3.3): a crunch and a chew."))
def eat_bite(v, rng):
    """biting into something and eating it: the teeth going through the crust with a crunch (a quick spray of little fractures
    and a dull knock of the jaw), a few wet chews, a second, smaller bite, more chewing and a small swallow. v1 a crusty loaf, v2
    a crisp pie crust, v3 something soft and moist, meat or fish"""
    dur = 1.2
    m = Bus(dur)
    crisp = [1.0, 1.4, 0.35][v]
    moist = [0.7, 0.6, 1.5][v]
    for at, g in ((0.0, 1.0), (0.63, 0.7)):
        cd = 0.11
        ct = tvec(cd)
        env = np.clip(ct / 0.005, 0, 1) * np.exp(-ct / 0.04)
        m.add(crunch(cd, rng, 2200.0 * crisp * env + 20.0, lo=700.0 if v < 2 else 400.0, hi=[6000.0, 7500.0, 3500.0][v], decay=0.0007) * env,
              at, 0.9 * g * min(1.0, 0.4 + crisp * 0.6))
        m.add(nrm(lp(white(0.06, rng), 400.0, 2) * np.exp(-tvec(0.06) / 0.015)), at, 0.5 * g)
        if v == 2:
            m.add(ship.bubbles(0.1, rng, 500.0, 0.6, 2.0, beta=1.8, level=1.0, weight=0.4, rise=0.3), at, 0.3 * g)
    m.add(chew(3, rng, rate=4.4, moist=moist), 0.15, 0.7)
    m.add(chew(3, rng, rate=4.6, moist=moist), 0.78, 0.6)
    m.add(gulp(rng, f0=420.0, size=0.8), 1.08, 0.35)
    return kitchen(lp(fit(m.out(), ns(dur)), 8000.0), 0.06, 5396 + v)


@effect("drink", 2, 1.4, "Someone drinks broth", reach=8, loud=-27.0, fade_out=0.25,
        when=("Drinking broth (3.3): a long sip and a gulp."))
def drink(v, rng):
    """broth drunk from a bowl: one long sip (the rim at the lips, a slow slurp of liquid and air with small bubbles in it, rising
    as the bowl tips), then the gulp that follows it and a soft breath out after. v2 two gulps"""
    dur = 1.4
    m = Bus(dur)
    sd = [0.66, 0.56][v]
    m.add(nrm(ping(0.08, [1700.0, 2800.0], [0.03, 0.02], [1.0, 0.5], rng)), 0.0, 0.18)
    m.add(slurp(sd, rng, 1500.0, 3300.0, strength=1.0), 0.03, 1.0)
    g1 = 0.03 + sd + 0.1
    m.add(gulp(rng, f0=[470.0, 450.0][v]), g1, 0.9)
    if v == 1:
        m.add(gulp(rng, f0=420.0, size=0.9), g1 + 0.24, 0.7)
    ex_at = g1 + (0.28 if v == 1 else 0.17) + 0.1
    ex = formants(white(0.34, rng), "a", "uh", scale=0.95) * bell(0.34, 0.3, 1.3, 1.6)
    m.add(lp(ex, 3500.0), ex_at, 0.22)
    return kitchen(lp(fit(m.out(), ns(dur)), 8500.0), 0.06, 5399 + v)


@effect("eat.clink", 3, 0.3, "A spoon clinks on a bowl", reach=8, loud=-29.0, fade_out=0.1, limit=3.0,
        when=("A seated tavern diner's sip (7.4, Part I3): one clink of the spoon on the bowl, where cook.eat.spoon would play "
              "three spoonfuls and a swallow. Added after the design's 44, at the lead's word."))
def eat_clink(v, rng):
    """a spoon clinking on a bowl, for a seated diner's sip: the bowl of the spoon touching the glaze with a small hard 'tink' (the
    spoonful of eat.spoon with nothing after it), the bowl ringing a moment under it, a faint scrape of the spoon on the wall of the
    bowl. v1 a glazed bowl; v2 a deeper, duller one, the spoon dragged to its wall first; v3 a lighter touch that rebounds once"""
    dur = 0.3
    m = Bus(dur)
    f = [2450.0, 2200.0, 2750.0][v] * rng.uniform(0.97, 1.03)
    fb = [1100.0, 950.0, 1250.0][v]
    at = [0.0, 0.07, 0.0][v]
    m.add(nrm(ping(0.14, [f, f * 2.7, f * 4.9], [0.05, 0.03, 0.02], [1.0, 0.5, 0.25], rng)), at, 0.6)
    m.add(tf.ring(0.2, fb * np.array([1.0, 2.2, 3.6]), [0.08, 0.05, 0.03], [1.0, 0.5, 0.25], rng, doublet=0.003), at, 0.25)
    sc = bp(colored(0.07, rng, -1.0), 1500.0, 6000.0) * bell(0.07, 0.4, 1.2, 1.5) * (0.5 + 0.5 * smooth_random(0.07, rng, 80))
    m.add(sc, 0.0 if v == 1 else at + 0.015, 0.18)
    if v == 2:
        m.add(nrm(ping(0.08, [f * 1.03, f * 2.7], [0.03, 0.02], [1.0, 0.5], rng)), 0.085, 0.22)
    return kitchen(lp(fit(m.out(), ns(dur)), 8500.0), 0.06, 5401 + v)
# mouth>>


# =========================================================================== the order of the sounds
# the order of CookSounds.NAMES (the design's Appendix C.5): the order of the fragments and of the listen-through
ORDER = ["place", "pour", "plop.soft", "plop.meat", "plop.grain", "fuel", "ignite", "stir", "taste", "simmer", "call",
         "crackle", "sizzle", "ease", "boil_over", "skim", "ready", "ripe", "serve", "tier.plain", "tier.sound", "tier.fine",
         "tier.hallmark", "burnt", "eat.spoon", "eat.bite", "drink", "buff", "oven_hum", "oven_open", "oven_shut",
         "spit_creak", "crank", "drip", "flare", "flip", "chop", "slice", "carve", "loaf_down", "plate", "chalk", "larder",
         "chill", "eat.clink"]  # (eat.clink was added after the design's 44, and so comes after them)

assert sorted(ORDER) == sorted(EVENTS), "every sound is in ORDER, once: " + str(set(ORDER) ^ set(EVENTS))
for _name in ORDER:
    EVENTS[_name] = EVENTS.pop(_name)


# =========================================================================== main
def build(assets, names, wav_dir=None):
    written = []
    for event in names:
        r = EVENTS[event]
        for v in range(r["files"]):
            t0 = time.time()
            name = sound_path(event, v + 1)
            p = os.path.join(assets, "sounds", name + ".ogg")
            if r["kind"] == "loop":
                x = render_loop(event, r)
                x = write_checked(p, x)
                d_len, step, seam = seam_report(p, len(x))
                extra = (f"  loop {len(x)} samples (file {d_len:+d}), join {step:4.1f}x a step, {seam:4.2f}x the usual highs")
            else:
                x, over, short = build_effect(event, r, v)
                x = write_checked(p, x)
                extra = (f"  limited {over:.1f} dB" if over > 0.05 else "") + \
                    (f"  {short:.1f} dB under (set by peak)" if short > 0.05 else "")
            if wav_dir:
                write_wav(os.path.join(wav_dir, name + ".wav"), x)
            y = read_audio(p)[0]
            print(f"{name:26s} {len(y) / SR:6.3f}s  peak {to_db(np.max(np.abs(y))):6.1f}  tp {20 * np.log10(true_peak(y)):6.1f}  "
                  f"rms {to_db(rms(y)):6.1f}  short {float(np.max(loudness_curve(y))):6.1f}  {time.time() - t0:5.2f}s{extra}",
                  flush=True)
            written.append(name)
    return written


def select(only):
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        s = s.removeprefix("cook.")
        hits = [e for e in EVENTS if e == s or e.startswith(s + ".") or (s == "loops" and EVENTS[e]["kind"] == "loop")]
        if not hits:
            raise SystemExit(f"unknown: {s} (events: {', '.join(EVENTS)}, loops)")
        names += [e for e in hits if e not in names]
    return names


# =========================================================================== fragments
def fragments():
    """(the sounds.json entries of the 45 events, their subtitles). A one-shot that carries other than the game's 16 blocks has its
    reach as the sound's attenuation_distance; a loop is not streamed and has its reach so, as the mills' have; the wind under the
    frost (reach None) is heard by the player alone, unpositioned, and has neither"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        entries = []
        for v in range(r["files"]):
            name = f"guildmark:{sound_path(event, v + 1)}"
            e = {"name": name}
            if r["kind"] == "loop":
                e["stream"] = False
            if r["reach"] and (r["reach"] != 16 or r["kind"] == "loop"):
                e["attenuation_distance"] = r["reach"]
            entries.append(e if len(e) > 1 else name)
        sounds[f"cook.{event}"] = {"sounds": entries, "subtitle": f"subtitles.guildmark.cook.{event}"}
        lang[f"subtitles.guildmark.cook.{event}"] = r["subtitle"]
    return sounds, lang


def write_fragments():
    """cook_sounds.json and cook_lang.json beside this script, for art/lang/merge_lang.py to add to sounds.json and en_us.json"""
    sounds, lang = fragments()
    for fname, data in (("cook_sounds.json", sounds), ("cook_lang.json", lang)):
        with open(os.path.join(HERE, fname), "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(os.path.join(HERE, fname), len(data), "entries")


# =========================================================================== the listen-through
LOOP_SHOWN = 12.0  # how long each loop plays in the listen-through (so that its join is heard once)
GAP = 0.65


def chapter_title(event, v, r):
    return f"cook.{event}" + (f" {v + 1} of {r['files']}" if r["files"] > 1 else "") + (" (loop, 12 s)" if r["kind"] == "loop" else "")


def listen_through(assets, names, path_):
    """one MP3 to listen to on a phone: every file that was just written, in the game's order, read back from disk (so it is what
    the game will play), a gap between and a chapter mark at each; a loop plays for twelve seconds, so that its join is heard
    once. Returns the chapter list [(start, end, title, event, v)], for write_when"""
    pieces, marks, at = [], [], 0.0
    for event in names:
        r = EVENTS[event]
        for v in range(r["files"]):
            stem = sound_path(event, v + 1)
            p = os.path.join(assets, "sounds", stem + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {stem})")
                continue
            x = read_audio(p)[0]
            if r["kind"] == "loop":
                x = fade(np.tile(x, int(np.ceil(LOOP_SHOWN * SR / len(x))))[:ns(LOOP_SHOWN)], 0.0, 0.3)
            marks.append((at, at + len(x) / SR + GAP, chapter_title(event, v, r), event, v))
            pieces += [x, np.zeros(ns(GAP))]
            at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w", encoding="utf-8") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark Hearthcraft sounds\nartist=Guildmark\n")
            for a, b, title, _, _ in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
        shutil.copyfile(mp3, path_)  # (encoded aside, then copied: ffmpeg seeks back in a file to finish its header)
    print(f"{path_}  {at:.1f}s, {len(marks)} files, {os.path.getsize(path_) / 1e6:.2f} MB")
    return marks


def clock(t):
    return f"{int(t // 60)}:{t % 60:04.1f}"


def made_of(fn):
    """the first paragraph of a recipe's docstring, on one line"""
    import inspect
    doc = inspect.getdoc(fn) or ""
    return " ".join(doc.split("\n\n")[0].split())


def write_when(marks, path_):
    """the text of what plays when: for each sound, its files, length, reach and subtitle, what it is made of, the moment it
    belongs to, and where it is in the listen-through"""
    at = {}
    for a, _, _, event, v in marks:
        at.setdefault(event, []).append(a)
    # (a paragraph is one line, for a phone's reader to wrap)
    lines = ["Hearthcraft sounds: what plays when", "=" * 36, "",
             "cook_all.mp3 plays every file once, in the order of the game's list (CookSounds.NAMES), with a chapter mark at each "
             "(a phone's player shows them). Each loop plays for 12 seconds so that its join is heard once. The times are where "
             "each file starts in it. A sound's moment is as the design names it (living-world/cook/design.md 1.4 to 1.6, 3.3, "
             "4.3, 7.3 to 7.5); which part of the game plays it, and whether it does yet, is for the game test (Parts C, E, F and "
             "I3 play them, and some are wired already). The last, cook.eat.clink, was added after the design's 44 sounds.",
             "",
             "The sounds are at the game's own levels, so they keep their balance with each other, and the game's levels are "
             "quiet: turn the phone up until the first one, cook.place, is comfortable and judge the rest against it. The five "
             "loops are the quietest of all, by design: they live under the town's own sounds.", ""]
    for event, r in EVENTS.items():
        files = r["files"]
        if r["kind"] == "loop":
            what = f"one loop of {r['length']:g} s, built to join itself"
        elif files > 1:
            what = f"{files} files, {r['length']:g} s each"
        else:
            what = f"1 file, {r['length']:g} s"
        reach = "the player alone (unpositioned)" if r["reach"] is None else f"carries {r['reach']} blocks"
        if event == "buff":
            reach = "the eater alone"
        lines.append(f"cook.{event}: {what}; {reach}")
        lines.append(f"  subtitle: \"{r['subtitle']}\"")
        lines.append("  in cook_all.mp3: " + ", ".join(clock(t) for t in at.get(event, [])))
        lines.append(f"  made of: {made_of(r['fn'])}")
        lines.append(f"  plays: {r['when']}")
        lines.append("")
    os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
    with open(path_, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(path_, len(lines), "lines")


# =========================================================================== audit
def probe(path_):
    """(samples, channels, rate, codec) the file declares, by ffprobe"""
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                          "stream=duration_ts,channels,sample_rate,codec_name", "-of", "json", path_],
                         capture_output=True, text=True, check=True).stdout
    st = json.loads(out)["streams"][0]
    return int(st["duration_ts"]), int(st["channels"]), int(st["sample_rate"]), st["codec_name"]


def audit(assets):
    """every file of every event read back from disk and held to its row of the design: it is there, mono 44.1 kHz Vorbis, as
    many files as the row says, each exactly as long as the row says (what the file declares; a decoder may add or drop a few
    samples of a one-shot, which no game hears), its peak and true peak under -1 dBFS (a loop's under -10), a one-shot's loudest
    100 ms near its target, a loop's RMS at -27 dBFS with its join checked. Prints a line for each; returns 1 if any fails"""
    import re
    bad, files = [], 0
    for event, r in EVENTS.items():
        for v in range(r["files"]):
            stem = sound_path(event, v + 1)
            p = os.path.join(assets, "sounds", stem + ".ogg")
            files += 1
            if not os.path.exists(p):
                bad.append(f"{stem}: missing")
                continue
            frames, ch, rate, codec = probe(p)
            y = read_audio(p)[0]
            pk, tp = to_db(np.max(np.abs(y))), 20 * np.log10(true_peak(y))
            short = float(np.max(loudness_curve(y)))
            notes = []
            if (ch, rate, codec) != (1, SR, "vorbis"):
                notes.append(f"is {ch} ch {rate} Hz {codec}")
            if frames != ns(r["length"]):
                notes.append(f"is {frames} samples, the row says {ns(r['length'])}")
            if r["kind"] == "loop":
                d_len, step, seam = seam_report(p, ns(r["length"]))
                lim = r["ceiling_db"]
                if d_len != 0:
                    notes.append(f"decodes {d_len:+d} samples")
                if abs(to_db(rms(y)) - r["rms_db"]) > 0.4:
                    notes.append(f"RMS {to_db(rms(y)):.1f}, wanted {r['rms_db']}")
                if tp > lim or pk > lim:
                    notes.append(f"peak {pk:.1f} / true peak {tp:.1f} over {lim}")
                if step > 3.0 or seam > 1.5:
                    notes.append(f"join {step:.1f}x a step, {seam:.2f}x the highs")
                tail = f"rms {to_db(rms(y)):6.1f}  join {step:4.1f}x a step, {seam:4.2f}x the highs"
            else:
                if tp > -1.0:
                    notes.append(f"true peak {tp:.1f}")
                if abs(short - r["loud"]) > 1.6:
                    notes.append(f"loudest 100 ms {short:.1f}, wanted {r['loud']}")
                tail = f"short {short:6.1f}  (target {r['loud']})"
            print(f"{stem:26s} {frames / SR:6.3f}s  peak {pk:6.1f}  tp {tp:6.1f}  {tail}" +
                  ("   <-- " + "; ".join(notes) if notes else ""))
            if notes:
                bad.append(f"{stem}: " + "; ".join(notes))
    total = sum(r["files"] for r in EVENTS.values())
    if len(EVENTS) != 45 or total != 91:
        bad.append(f"{len(EVENTS)} events and {total} files, the design says 44 and 88 and eat.clink adds 1 and 3")
    want_sounds, want_lang = fragments()
    for fname, want in (("cook_sounds.json", want_sounds), ("cook_lang.json", want_lang)):
        fp = os.path.join(HERE, fname)
        if not os.path.exists(fp):
            bad.append(f"{fname} is missing (a full render writes it)")
        elif json.load(open(fp, encoding="utf-8")) != want:
            bad.append(f"{fname} is not what the recipes say (a full render writes it)")
    for key, ent in want_sounds.items():
        for e in ent["sounds"]:
            n = e if isinstance(e, str) else e["name"]
            if not os.path.exists(os.path.join(assets, "sounds", n.split(":", 1)[1] + ".ogg")):
                bad.append(f"{key}: {n} has no file")
    names_kt = os.path.join(HERE, "..", "..", "src", "main", "kotlin", "io", "github", "vaspyyy", "guildmark", "cooking",
                            "CookSounds.kt")
    if os.path.exists(names_kt):
        kt = re.search(r"val NAMES: List<String> = listOf\((.*?)\n    \)", open(names_kt, encoding="utf-8").read(), re.S)
        if kt and re.findall(r'"cook\.([a-z_.]+)"', kt.group(1)) != list(EVENTS):
            bad.append("the events are not CookSounds.NAMES, in its order")
    print(f"\n{len(EVENTS)} events, {files} files checked: " + ("all as the design says" if not bad else f"{len(bad)} problems"))
    for b in bad:
        print("  PROBLEM:", b)
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description="Hearthcraft's kitchen: the sounds of the cook's stations")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated events or groups, e.g. stir,plop,eat,loops")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered, and "
                                                "cook_what_plays_when.txt beside it")
    ap.add_argument("--audit", action="store_true", help="render nothing: read every file back and check it against the design")
    a = ap.parse_args()
    if a.audit:
        raise SystemExit(audit(a.assets))
    names = select(a.only) if a.only else list(EVENTS)
    build(a.assets, names, a.wav)
    if not a.only:
        write_fragments()
    if a.mp3:
        marks = listen_through(a.assets, names, a.mp3)
        write_when(marks, os.path.join(os.path.dirname(os.path.abspath(a.mp3)), "cook_what_plays_when.txt"))


if __name__ == "__main__":
    main()
