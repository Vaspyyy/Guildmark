"""Living villages: the village's own sounds, made from nothing (the villages design 17.3, Part V12b).

Seven events and 23 mono 44.1 kHz Ogg files in `sounds/village/` (`guildmark:village.<name>`, in the order of the design's table,
which `village/live/VillageSounds.kt` keeps), each with the files, the lengths and the reach of its row:

    door_bolt    4 files, 0.38-0.50 s, reach 16   an iron bolt shot home along its keeper, the door's clack (BOLT_DOOR at tick 30)
    hush         2 loops, 6.6 and 7.6 s, reach 12  people whispering behind a bolted door (two or more cowering inside)
    cheer        3 files, 1.45-1.9 s, reach 32    a village's cheer, 6 to 12 voices, children among them
    candle_lay   3 files, 0.65-0.85 s, reach 16   a candle set on the memorial stone, a match struck, the wick catching (LAY_CANDLE)
    pedlar_bell  3 files, 1.25-1.8 s, reach 40    the pedlar's brass handbell, smaller and brighter than the works' and the crew's
    baby         4 files, 0.75-1.25 s, reach 12   a baby cooing in the crook of an arm (CRADLE's coo), one of them fretful
    rooster      4 files, 1.6-2.2 s, reach 56     a rooster crowing at first light from a coop, four birds

No sample is used: every sound is noise, a glottal pulse train and resonators, laid out with `sfxkit.py`, the townsfolk's helpers
(`make_townsfolk_sfx.py`: the levelling, knocks, rings, rustles and claps), the homestead's (`make_homestead_audio.py`: the
shouts and claps of a crowd) and the cog's (`make_ship_audio.py`: struck iron and oak, a creak, a loop built in a circle), each
seeded by its event and file, so that a rerun is bit-identical. The whispers of `hush` are the townsfolk's own recorded talk
(`sounds/townsfolk/voice`) with its voice taken out: the spectrum's shape kept and its pitch replaced by breath, cut into
syllables and dealt out between two or three of them, heard through a door, so nothing in it can be made out.

Levelling, as the townsfolk's and the cook's are: a one-shot's loudest 100 ms goes to a K-weighted loudness of its own (from -14 for
the rooster to -23 for a coo), its true peak held under -1 dBFS and re-encoded lower if the encoder overshoots. The loop is built in
a circle (`make_ship_audio.Ring`), set to -30 dBFS RMS with its peaks (true peaks too) under -10, turned to start at a quiet zero
crossing, and read back after the encoder: its length, the step across its join, the highs round the join against the highs
elsewhere. Each one-shot starts on its moment (the bolt's last run into the keeper, the candle's foot on the stone, the bell's
first stroke, the crow's first syllable), so a clip's moment plays it on the frame it happens.

The ambience the village layer plays on the player's side alone (`ambience.village_*`, `ambience.dog_far` ...) is made by
`make_ambience.py`'s module `ambience/village.py`; this script writes their `sounds.json` entries and subtitles into the same
fragments, and checks, plays and lists them with its own.

usage (from the repo root; the full render takes about a minute, `nice -n 10` for it):
    python3 -B art/audio/make_village_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b] [--mp3 FILE] [--audit]

  (no --only)  renders the 23 files and writes `villages_sounds.json` (the sounds.json entries of the seven events and of the
               village ambience) and `villages_lang.json` (their subtitles) beside this script; run
               `python3 -B art/lang/merge_lang.py` after it, which adds them to sounds.json and en_us.json
  --only       events: `rooster,door_bolt` (`village.rooster` also works); no fragments are written
  --wav DIR    also writes WAV copies for listening
  --mp3 FILE   joins the files on disk into one listen-through MP3 (the seven events, then the ambience: each bed for a minute,
               then the far-off one-shots), a chapter mark at each, and writes `village_what_plays_when.txt` beside it
  --audit      renders nothing: reads every file back (mono 44.1 kHz Vorbis or a stereo bed, the row's length, peak and true peak,
               the loudest 100 ms against the target, a loop's RMS and join, a bed's join after the encoder) and checks the events
               against the design's table, the reaches against VillageSounds.kt and the fragments against the files; exits 1 on a
               problem
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np
from scipy import signal
from scipy.ndimage import uniform_filter1d

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from sfxkit import *  # noqa: E402,F401,F403
import make_homestead_audio as hs  # noqa: E402
import make_townsfolk_sfx as tf  # noqa: E402
from make_townsfolk_sfx import level, write_checked, true_peak, loudness_curve, nrm, lead_in, knock, thud, rustle  # noqa: E402
from make_ship_audio import Ring, seam_report, iron_ring, oak, creak_voice  # noqa: E402

SOUND_DIR = "village"
VOICE_DIR = os.path.join("sounds", "townsfolk", "voice")
LOOP_MARGIN_DB = 0.8  # a loop's peaks are held this far under its ceiling, for the encoder's own overshoot
EVENTS = {}  # event (without the "village." prefix) -> recipe, in the order of the design's table and VillageSounds.NAMES
ARGS = None


# =========================================================================== registry and finishing
def effect(event, files, lengths, subtitle, reach, loud=-18.0, fade_out=0.05, tail_db=-70.0, limit=2.0, when=""):
    """registers a one-shot: `files` variants, the n-th exactly lengths[n] seconds long (each inside the design's range), with the
    subtitle the game writes and the reach (blocks) it is registered and heard with. loud is the target for its loudest 100 ms
    (K-weighted LUFS), limit the most dB of peak limiting allowed to get there; when is the moment that plays it (16.1)"""
    def deco(fn):
        assert len(lengths) == files
        EVENTS[event] = dict(kind="effect", fn=fn, files=files, lengths=list(lengths), subtitle=subtitle, reach=reach, loud=loud,
                             fade_out=fade_out, tail_db=tail_db, limit=limit, when=when)
        return fn
    return deco


def loop(event, files, periods, subtitle, reach, rms_db=-30.0, ceiling_db=-10.0, when=""):
    """registers a seamless loop: the recipe is handed a Ring of periods[v] seconds and gives back the three periods it laid out;
    the file is the middle one, levelled by RMS with its peaks held under ceiling_db"""
    def deco(fn):
        EVENTS[event] = dict(kind="loop", fn=fn, files=files, lengths=list(periods), subtitle=subtitle, reach=reach,
                             rms_db=rms_db, ceiling_db=ceiling_db, when=when)
        return fn
    return deco


def sound_path(event, n):
    """'rooster', 1 -> 'village/rooster1'"""
    return f"{SOUND_DIR}/{event}{n}"


def build_effect(event, r, v):
    """one variant of a one-shot, finished: trimmed, faded, exactly as long as its row says, its loudest 100 ms at the target"""
    rng = rng_for("village", event, v)
    x = r["fn"](v, rng)
    length = r["lengths"][v]
    x = finalize(lead_in(x), peak_db=-3.0, fade_out=r["fade_out"], max_len=length, tail_db=r["tail_db"])
    x = fit(x, ns(length))
    return level(x, r["loud"], max_limit_db=r["limit"])


def render_loop(event, r, v):
    """a loop, finished: the middle period of the three the recipe laid out, high-passed, levelled to its RMS with its peaks held
    under the ceiling, and turned to start at a quiet zero crossing (the cook's and the cog's render_loop)"""
    rg = Ring(r["lengths"][v])
    x = hp(r["fn"](v, rg, rng_for("village", event, v)), 25.0)
    ceiling = db(r["ceiling_db"] - LOOP_MARGIN_DB)
    held, limited = ceiling, False
    for _ in range(8):
        y = rg.middle(x)
        x = x * (db(r["rms_db"]) / (rms(y) + 1e-12))
        tp = true_peak(rg.middle(x))
        if tp <= ceiling * 1.001:
            break
        if limited:
            held *= ceiling / tp
        x = limiter(x, ceiling=held, lookahead=0.003, release=0.08)
        limited = True
    return quiet_start(rg.middle(x))


def quiet_start(y):
    """the loop turned round to start where it is quiet, at a zero crossing rising (make_ship_audio.loop_start, with the
    running energy kept off the rounding error under zero that would otherwise make its square root NaN)"""
    env = np.sqrt(np.maximum(uniform_filter1d(y * y, size=ns(0.012)), 0.0))
    lo = lp(y, 500.0, 2)
    cross = np.nonzero((lo[:-1] < 0) & (lo[1:] >= 0))[0] + 1
    if not len(cross):
        return y
    cost = env[cross] / (env.max() + 1e-12) + 6.0 * np.abs(y[cross]) / (env.max() + 1e-12)
    return np.roll(y, -int(cross[int(np.argmin(cost))]))


# =========================================================================== places and distances
def green(x, wet=0.12, seed=5300, rt60=1.2):
    """out on a village green: open ground, a few cottage walls and a barn round it, so an early slap or two and a short soft
    tail (no street's hard walls either side)"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.03, hf_damp=0.7, seed=seed, early_span=0.07)


def cottage(x, wet=0.1, seed=5310, rt60=0.35):
    """inside a cottage: plaster, timber and a flagged floor, a small room with a short tail"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.004, hf_damp=0.55, seed=seed, early_span=0.02)


def afar(x, metres, wall=None, seed=5320):
    """as heard [metres] off across open ground: the air takes the top off it, the ground and the hedges add a soft tail, and a
    barn wall [wall] metres behind the source throws back a slap-back echo"""
    y = lp(x, max(2200.0, 15000.0 * np.exp(-metres / 45.0)), order=2)
    y = reverb(y, wet=min(0.5, 0.1 + metres / 110.0), rt60=1.1 + metres / 90.0, predelay=0.02, hf_damp=0.7, seed=seed,
               early_span=0.09)
    if wall:
        d = ns(2 * wall / 343.0)
        echo = lp(np.concatenate([np.zeros(d), y]), 2600)[:len(y) + d]
        y = np.concatenate([y, np.zeros(d)]) + 0.22 * echo
    return y


def env_shape(points, dur):
    """a smooth envelope through (time, level) points (a cosine between each two)"""
    n = ns(dur)
    t = np.arange(n) / SR
    pts = sorted(points)
    out = np.zeros(n)
    for (t0, v0), (t1, v1) in zip(pts[:-1], pts[1:]):
        m = (t >= t0) & (t < t1)
        u = (t[m] - t0) / max(t1 - t0, 1e-9)
        out[m] = v0 + (v1 - v0) * (0.5 - 0.5 * np.cos(np.pi * u))
    out[t >= pts[-1][0]] = pts[-1][1]
    return out


# =========================================================================== the bolt
IRON_BOLT = (np.array([1830.0, 2870.0, 4120.0, 5650.0, 7340.0]), np.array([0.05, 0.04, 0.03, 0.022, 0.016]),
             np.array([1.0, 0.8, 0.6, 0.4, 0.25]))


def bolt_run(dur, rng, rate0, rate1, amp=1.0):
    """the iron bolt running along its keeper and staples: a fast stick-slip train ringing the bar's modes, a dry rasp with it"""
    n = ns(dur)
    rate = np.linspace(rate0, rate1, n)
    env = np.sin(np.pi * np.clip(np.arange(n) / max(n - 1, 1), 0, 1)) ** 0.5 * amp
    y = creak_voice(dur, rng, rate, env, IRON_BOLT, jitter=0.12, rub=0.35, kick=0.0004, bright=8000.0, unsteady=0.25)
    return hp(y, 900)


def bolt_home(rng, f_bolt, f_door, weight=1.0):
    """the bolt striking home: its end on the keeper (forged iron, a short dull clank, the bar's modes), the door's oak answering
    and the planks' thud under it"""
    L = 0.32
    m = Mix(L)
    clank = iron_ring(L, rng, [f_bolt, f_bolt * 1.47], t60=0.09, gains=[1.0, 0.55])
    tick = hp(white(0.003, rng), 2500) * np.exp(-tvec(0.003) / 0.0005)
    m.add(nrm(clank), 0.0, 0.85)
    m.add(nrm(tick), 0.0, 0.3)
    m.add(nrm(oak(L, rng, f_door, t60=0.07, hard=0.8)), 0.0006, 0.9 * weight)
    m.add(nrm(thud(0.12, rng, f=95.0, decay=0.02, tone=0.4)), 0.001, 0.55 * weight)
    return m.out()


@effect("door_bolt", 4, [0.42, 0.38, 0.50, 0.46], "Bolt shoots home", 16, loud=-17.0, limit=3.0, fade_out=0.08,
        when="BOLT_DOOR's moment `bolt` (tick 30 of 50), as someone sheltering shoots the bolt of the door they pushed to (16.1 "
             "row 21, V10's shelter); a puff of dust from the frame with it")
def door_bolt(v, rng):
    """an iron bolt shot home on a cottage door: the last of its run along the keeper (a dry iron rasp, quicker as it goes),
    its end striking home (a short clank and the door's oak knocking under it). v1 a quick shove, v2 a lighter bolt slid fast,
    v3 a stiff bolt that sticks once, is shoved again and slams, v4 shot home and its handle dropped into its notch"""
    m = Mix(0.6)
    if v == 2:
        m.add(bolt_run(0.035, rng, 180, 240, 0.6), 0.0, 0.3)
        m.add(bolt_run(0.07, rng, 260, 520), 0.07, 0.42)
        home = 0.14
    else:
        run = [0.06, 0.045, 0.0, 0.07][v]
        m.add(bolt_run(run, rng, [300, 380, 0, 260][v], [520, 640, 0, 480][v]), 0.0, [0.4, 0.32, 0, 0.38][v])
        home = run
    f_bolt, f_door, weight = [(1180, 205, 1.0), (1420, 230, 0.75), (1050, 190, 1.15), (1250, 215, 0.95)][v]
    m.add(bolt_home(rng, f_bolt * rng.uniform(0.98, 1.02), f_door * rng.uniform(0.97, 1.03), weight), home, 1.0)
    if v == 3:
        # the handle let go: it drops into its notch with a small iron knock
        m.add(nrm(iron_ring(0.12, rng, [2350.0], t60=0.05)) * 0.3, home + 0.075, 1.0)
        m.add(nrm(knock(0.04, [1600, 2500], [0.01, 0.007], [1, .5], rng, lo=900, hi=8000)), home + 0.074, 0.18)
    return cottage(m.out(), wet=0.12, seed=5311 + v)


# =========================================================================== the whispers behind the door
# everyday talk, as the queue's murmur takes (a child's own lines are its own families, the wary and the scared among them)
WHISPER_FROM = ("talk.home.", "talk.town.", "talk.doing.", "talk.sky.")
WHISPER_FROM_CHILD = ("talk.child.wary_", "talk.child.hungry_", "talk.child.news_", "talk.child.doing_", "talk.tale.child.scared_")


def talk_takes(timbre, rng, k):
    """k of the townsfolk's own recorded talk lines in that voice (named families only, so new lines elsewhere leave the
    whispers as they are), picked by rng in name order"""
    d = os.path.join(ARGS.assets, VOICE_DIR, timbre)
    pre = WHISPER_FROM_CHILD if timbre == "child" else WHISPER_FROM
    names = sorted(f for f in os.listdir(d) if f.startswith(pre) and f.endswith(".ogg"))
    pick = sorted(rng.choice(len(names), size=min(k, len(names)), replace=False))
    return [read_audio(os.path.join(d, names[i]))[0] for i in pick]


def unvoice(x, rng, smooth_hz=260.0):
    """speech made a whisper: the short-time spectrum's shape kept (the vowels and consonants) but its harmonics smoothed away
    and its phase made random, so the voice is replaced by breath; the lows a voice has and a whisper has not taken out"""
    nper, hop = 1024, 256
    f, t, Z = signal.stft(x, fs=SR, nperseg=nper, noverlap=nper - hop)
    mag = np.abs(Z)
    k = max(3, int(round(smooth_hz / (SR / nper))))
    env = uniform_filter1d(mag, size=k, axis=0, mode="nearest")
    ph = np.exp(1j * rng.uniform(0, 2 * np.pi, Z.shape))
    _, y = signal.istft(env * ph, fs=SR, nperseg=nper, noverlap=nper - hop)
    y = hp(hp(y[:len(x)], 650.0, 2), 650.0, 2)
    return eq(y, "peak", 2600.0, 0.9, 6.0)


def syllables(x, rng, lo=0.16, hi=0.5):
    """a take cut at its quiet moments into syllable-long pieces, each faded in and out (as the queue's murmur is)"""
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
        piece = fade(x[i:j].copy(), 0.015, 0.03)
        if rms(piece) > 0.25 * rms(x[on[0]:end]):
            out.append(piece)
        i = j
    return out


def shush(dur, rng):
    """'shh': a long breath through the teeth, the hiss high and narrow, swelling and let go"""
    y = bp(white(dur, rng), 2400, 7500)
    y = eq(y, "peak", rng.uniform(3600, 4400), 2.0, 9.0)
    return nrm(y) * bell(dur, 0.3, 1.2, 1.6)


@loop("hush", 2, [6.6, 7.6], "People whisper", 12, rms_db=-30.0,
      when="at the door of a house with two or more cowering inside it, faded in and out as the queue's murmur is (16.1 row 21, "
           "V10's shelter)")
def hush(v, rg, rng):
    """people sheltering behind a bolted door, heard at it (a loop): two or three of them whispering by turns, low and quick,
    none of it to be made out (the townsfolk's own talk with the voice taken out of it, cut into syllables and dealt out afresh),
    one of them hushing the others, a sleeve on a coat, a board creaking under a shifted foot; all of it through the door's
    planks. v1 a woman and a man and an old man, v2 a woman, a man and a child"""
    period = rg.P
    voices = [[("woman", 1.0, -1.0), ("man", 0.97, -2.0), ("old", 0.96, -4.0)],
              [("woman", 0.99, -1.5), ("man", 0.95, -2.5), ("child", 1.0, -5.0)]][v]
    bed = rg.new()
    pools = []
    for timbre, rate, g in voices:
        pieces = []
        for x in talk_takes(timbre, rng, 5):
            pieces += syllables(unvoice(x, rng), rng)
        pools.append((pieces, rate, g))
    # turns: a burst of whispered syllables from one of them, then a pause, then another answers
    at, turn = rng.uniform(0.1, 0.5), 0
    while at < period - 0.3:
        pieces, rate, g = pools[turn % len(pools)] if turn % 4 != 3 else pools[0]
        burst = rng.uniform(0.9, 2.1)
        end = at + burst
        while at < end and at < period - 0.15:
            p = varispeed(pieces[int(rng.integers(len(pieces)))], rate * rng.uniform(0.97, 1.05))
            p = 0.1 * p / (rms(p) + 1e-9)
            rg.place(bed, p, at, db(g) * rng.uniform(0.75, 1.0))
            at += len(p) / SR + rng.uniform(0.0, 0.07)
        at += rng.uniform(0.35, 1.1)
        turn += 1 + int(rng.integers(2))
    # one of them hushing the rest
    rg.place(bed, shush(rng.uniform(0.55, 0.8), rng), rng.uniform(0.25, 0.55) * period, db(-6.0))
    for at in rng.uniform(0, period, 3):  # a sleeve on a coat, someone shifting closer
        rg.place(bed, rustle(0.3, rng, peak=0.4, density=220, lo=600, hi=3500), at, db(-16.0))
    creak = creak_voice(0.35, rng, np.linspace(40, 70, ns(0.35)), bell(0.35, 0.4, 1.2, 1.5),
                        (np.array([210.0, 380.0, 610.0, 940.0]), np.array([0.05, 0.04, 0.03, 0.02]), np.array([1, .7, .4, .2])),
                        rub=0.05)
    rg.place(bed, creak, rng.uniform(0.6, 0.9) * period, db(-18.0))
    # through the planks of the door, from a room behind it
    y = rg.tile(rg.middle(bed))
    y = hp(lp(y, 3200.0, order=2), 280.0)
    y = cottage(y, wet=0.16, seed=5330 + v)[:rg.N]
    return y


# =========================================================================== the cheer
def village_crowd(dur, rng, words, n, start=0.0, spread=0.14, women=0.4, kids=0.2, gain=(0.6, 1.0), rise=1.35):
    """some of the village shouting the same word together, ragged: men, women and children (higher and with smaller throats)"""
    m = Mix(dur)
    for _ in range(n):
        at = start + rng.uniform(0, spread)
        r = rng.random()
        if r < kids:
            f0, sc = rng.uniform(300, 390), rng.uniform(1.3, 1.42)
        else:
            f0, sc = hs.voice(rng, r < kids + women)
        word = words[int(rng.integers(len(words)))]
        g = rng.uniform(*gain) * (0.8 if r < kids else 1.0)
        for vowels, length, step, gap in word:
            at += gap
            s = hs.shout(length * rng.uniform(0.9, 1.12), rng, f0 * step, vowels, sc, rise=rise)
            m.add(s, at, g * (0.55 if step < 1 else 1.0))
            at += length * 0.8
    return fit(m.out(), ns(dur))


@effect("cheer", 3, [1.6, 1.9, 1.45], "Villagers cheer", 32, loud=-15.0, limit=3.0, fade_out=0.45,
        when="a village's cheer (16.1 rows 14, 17, 23, 25 and 32): the folk thanking the traveller who fed them, a topping out, "
             "the all-clear after a siege held, a rescued neighbour home, a festival's finale")
def cheer(v, rng):
    """the village's folk cheering together, smaller than the works' crowd (5 to 15 voices): men, women and the children's high
    voices among them, ragged, and clapping, out on the green. v1 'hooray!' from eight, v2 'hey!' and 'hurrah!' from twelve and a
    whistle, v3 'hurrah!' from six, a hamlet's"""
    dur = [1.6, 1.9, 1.45][v]
    m = Mix(dur)
    if v == 0:
        m.add(village_crowd(dur, rng, [hs.HOORAY], n=8, spread=0.15, kids=0.25), 0.0, 1.0)
        m.add(hs.claps(dur, rng, 9, 0.4, 0.9), 0.0, 0.4)
    elif v == 1:
        m.add(village_crowd(dur, rng, [hs.HEY, hs.HURRAH], n=12, spread=0.2, kids=0.25, women=0.4), 0.0, 1.0)
        m.add(hs.whistle(0.5, rng, 2400, 1.25), 0.45, 0.13)
        m.add(hs.claps(dur, rng, 14, 0.35, 1.2), 0.0, 0.42)
    else:
        m.add(village_crowd(dur, rng, [hs.HURRAH], n=6, spread=0.12, kids=0.17), 0.0, 1.0)
        m.add(hs.claps(dur, rng, 6, 0.45, 0.7), 0.0, 0.38)
    return green(fit(m.out(), ns(dur)), wet=0.14, seed=5340 + v)


# =========================================================================== the candle
def wax_on_stone(rng, weight=1.0):
    """a candle's foot set down on the stone: a soft, short tick of wax on stone, the stone's own dull tock under it, and the
    grit of it settled"""
    L = 0.08
    m = Mix(L)
    m.add(nrm(knock(0.05, np.array([1850, 2720, 3900]) * rng.uniform(0.93, 1.07), [0.009, 0.006, 0.004], [1, .6, .3], rng,
                    lo=900, hi=9000, ex_len=0.0009)), 0.0, 0.6)
    m.add(nrm(knock(0.06, np.array([690, 1130, 1720]) * rng.uniform(0.95, 1.05), [0.016, 0.011, 0.008], [1, .6, .35], rng,
                    lo=300, hi=4000, ex_len=0.0016)), 0.0, 0.5 * weight)
    m.add(nrm(tf.grains(0.06, rng, 5, 0.004, 0.015, 2500, 8000, t60=(0.002, 0.005))), 0.006, 0.12)
    return m.out()


def strike_match(rng, dur=0.07, rough=1.0):
    """a match drawn across its striker: a rasp of grit, rising"""
    t = tvec(dur)
    rasp = bp(white(dur, rng), 1600, 9000) * (0.4 + 0.6 * t / dur)
    grit = tf.grains(dur, rng, int(18 * rough), 0.0, dur * 0.5, 2500, 10000, t60=(0.001, 0.003))
    return nrm(nrm(rasp) * (0.7 + 0.3 * smooth_random(dur, rng, 180)) + 0.5 * nrm(grit))


def flare(rng, dur=0.24):
    """the match's head catching: a quick fizzing rush that flares and settles, with a few sputters in it"""
    t = tvec(dur)
    env = (1 - np.exp(-t / 0.008)) * np.exp(-t / (dur * 0.32))
    rush = bp(pink(dur, rng), 500, 6500) * env
    fizz = crackle(dur, rng, 900 * np.exp(-t / 0.05) + 40, decay=0.0004, f_lo=2500, f_hi=9000, size_var=0.8)
    return nrm(nrm(rush) + 0.35 * nrm(fizz) * np.exp(-t / 0.08))


def wick_catch(rng, dur=0.3):
    """the wick taking the flame: a small soft puff, low, and a faint flutter as the flame stands up"""
    t = tvec(dur)
    puff = lp(bp(pink(dur, rng), 260, 1800), 1200) * bell(dur, 0.18, 1.0, 2.0)
    flutter = bp(pink(dur, rng), 220, 900) * (0.5 + 0.5 * smooth_random(dur, rng, 14)) * np.exp(-t / 0.18)
    return nrm(nrm(puff) + 0.4 * nrm(flutter))


@effect("candle_lay", 3, [0.75, 0.85, 0.65], "Candle set on stone", 16, loud=-21.0, limit=3.0, fade_out=0.18,
        when="LAY_CANDLE's moment `set` (tick 24 of 60): a neighbour kneels at the memorial stone the dawn after a death, sets "
             "a candle on it and lights it (the task lights the candle block at tick 26) (16.1 row 26, V10)")
def candle_lay(v, rng):
    """a candle set down on the memorial stone and lit: wax on stone, a match struck and flaring, the wick catching. v1 set and
    lit at the first stroke, v2 set, shifted a little, and lit at the second stroke, v3 set and lit quickly"""
    m = Mix(0.9)
    m.add(wax_on_stone(rng), 0.0, 0.55)
    if v == 0:
        m.add(strike_match(rng), 0.06, 0.42)
        m.add(flare(rng), 0.12, 0.6)
        m.add(wick_catch(rng), 0.28, 0.16)
    elif v == 1:
        m.add(wax_on_stone(rng, 0.6), 0.07, 0.25)
        m.add(strike_match(rng, 0.05, 0.6), 0.12, 0.3)
        m.add(strike_match(rng), 0.24, 0.42)
        m.add(flare(rng, 0.26), 0.3, 0.6)
        m.add(wick_catch(rng, 0.34), 0.46, 0.16)
    else:
        m.add(strike_match(rng, 0.055), 0.05, 0.4)
        m.add(flare(rng, 0.2), 0.1, 0.55)
        m.add(wick_catch(rng, 0.26), 0.22, 0.15)
    return hp(green(m.out(), wet=0.08, seed=5350 + v, rt60=0.7), 160.0)


# =========================================================================== the pedlar's bell
PEDLAR_BELL = 1318.5  # E6: smaller and brighter than the crew's (1046.5) and the master builder's (784)


def brass_bell(dur, rng, f0, strength=1.0):
    """one swing of a small brass handbell: the clapper's tick, the bell's hum and its strong upper partials (a thin, bright
    casting), every mode a shimmering pair; a harder swing brings the uppers out more"""
    r = np.array([1.0, 2.02, 2.97, 4.13, 5.38, 6.83, 8.4, 10.1])
    amps = np.array([1.0, 0.24, 0.72, 0.5, 0.4, 0.26, 0.16, 0.09]) * strength ** np.array([0, .4, .6, .8, 1.0, 1.2, 1.4, 1.6])
    t60s = [1.7, 1.1, 0.95, 0.62, 0.46, 0.32, 0.22, 0.15]
    y = tf.ring(dur, f0 * r, t60s, amps, rng, doublet=0.0016, doublet_amp=0.6)
    tick = bp(white(0.003, rng), 3500, 15000) * np.exp(-tvec(0.003) / 0.0004)
    return nrm(nrm(y) + 0.3 * strength * nrm(fit(tick, len(y))))


@effect("pedlar_bell", 3, [1.5, 1.8, 1.25], "Hand bell rings", 40, loud=-16.0, limit=2.5, fade_out=0.45,
        when="the pedlar's RING_BELL as they set up at the green at nine (16.1 row 6, V4's pedlar), and softly each time they call "
             "out their wares")
def pedlar_bell(v, rng):
    """the pedlar's handbell, a small brass one, shaken to call the village to the cart: brighter and quicker than the works'
    handbell. v1 six quick strokes and let ring (the arrival), v2 eight in a swinging rhythm, v3 three lighter strokes (a call)"""
    dur = [1.5, 1.8, 1.25][v]
    plan = [
        [(0.0, 1.0), (0.13, 0.78), (0.26, 0.95), (0.39, 0.74), (0.52, 0.9), (0.65, 0.7)],
        [(0.0, 1.0), (0.1, 0.62), (0.32, 0.92), (0.42, 0.6), (0.64, 0.95), (0.74, 0.6), (0.96, 0.88), (1.06, 0.55)],
        [(0.0, 0.8), (0.17, 0.58), (0.34, 0.72)],
    ][v]
    m = Mix(dur)
    for at, g in plan:
        at = at + (rng.uniform(-0.012, 0.012) if at else 0.0)
        g = g * rng.uniform(0.93, 1.05)
        m.add(brass_bell(dur - at, rng, PEDLAR_BELL * rng.uniform(0.9993, 1.0007), g), at, g)
    return green(fit(m.out(), ns(dur)), wet=0.12, seed=5360 + v)


# =========================================================================== the baby
def coo(dur, rng, f0, vowels, scale=1.72, breath=0.45, jitter=0.012, shimmer=0.07, curve=None):
    """a baby's voice: a soft glottal source (the folds barely closing) on the pitch curve f0, through a tiny throat (formants
    about 1.7 times a grown man's), breathy, never pushed"""
    src = glottal(dur, f0, rng, jitter=jitter, shimmer=shimmer, open_q=0.78, breath=breath)
    a, b = vowels
    y = formants(src, a, b, scale=scale, curve=curve, gains=(1.0, 0.55, 0.25, 0.12))
    return lp(nrm(y), 5200.0)


def smack(rng, f=1800.0):
    """a wet little click of the lips or tongue"""
    return nrm(knock(0.02, [f, f * 1.6], [0.004, 0.003], [1, .5], rng, lo=800, hi=7000, ex_len=0.0006))


@effect("baby", 4, [0.85, 1.1, 0.75, 1.25], "A baby coos", 12, loud=-23.0, limit=2.0, fade_out=0.15,
        when="CRADLE's moments `coo` (ticks 16 and 46 of each 60-tick rock) as a parent holds their newborn in the lane the "
             "morning after a birth, and the birth's scene (16.1 row 27, V13)")
def baby(v, rng):
    """a baby in the crook of an arm, out in the lane. v1 a long 'oooh', rising and falling; v2 'ah-goo'; v3 a breathy little
    laugh into a coo; v4 fretful, two small unhappy 'eh's (a grizzle, not a cry)"""
    dur = [0.85, 1.1, 0.75, 1.25][v]
    m = Mix(dur + 0.2)
    if v == 0:
        d = 0.72
        f = env_shape([(0, 400), (0.25, 520), (0.5, 495), (d, 380)], d) * (1 + 0.01 * np.sin(2 * np.pi * 5.5 * tvec(d)))
        m.add(coo(d, rng, f, ("u", "o")) * bell(d, 0.35, 1.2, 1.4), 0.03, 1.0)
        m.add(smack(rng), 0.0, 0.08)
    elif v == 1:
        d1, d2 = 0.3, 0.5
        m.add(coo(d1, rng, env_shape([(0, 440), (0.15, 490), (d1, 470)], d1), ("a", "a")) * bell(d1, 0.3, 1.0, 1.5), 0.02, 0.9)
        g = lp(white(0.025, rng), 900) * bell(0.025, 0.25, 1.0, 2.0)
        m.add(nrm(g), 0.39, 0.06)
        m.add(coo(d2, rng, env_shape([(0, 520), (0.12, 540), (d2, 410)], d2), ("u", "u")) * bell(d2, 0.25, 1.0, 1.6), 0.41, 1.0)
    elif v == 2:
        at = 0.02
        for i in range(3):
            d = rng.uniform(0.07, 0.1)
            h = bp(white(d, rng), 900, 5000) * bell(d, 0.3, 1, 2)
            m.add(nrm(h), at, 0.25)
            m.add(coo(d, rng, 500 + 30 * i, ("e", "a"), breath=0.7) * bell(d, 0.35, 1, 2), at + 0.01, 0.75)
            at += d + rng.uniform(0.03, 0.05)
        d = 0.36
        m.add(coo(d, rng, env_shape([(0, 470), (0.1, 500), (d, 400)], d), ("o", "u")) * bell(d, 0.3, 1.2, 1.5), at + 0.03, 0.9)
        m.add(smack(rng, 2100), at + 0.01, 0.07)
    else:
        for at, d, f0 in ((0.02, 0.26, 430), (0.48, 0.42, 455)):
            f = env_shape([(0, f0), (d * 0.3, f0 * 1.1), (d, f0 * 0.92)], d) * (1 + 0.025 * np.sin(2 * np.pi * 7 * tvec(d)))
            m.add(coo(d, rng, f, ("e", "uh"), breath=0.32, shimmer=0.12) * bell(d, 0.3, 1.0, 1.4), at, 1.0)
        m.add(smack(rng, 1600), 0.36, 0.06)
    return green(m.out(), wet=0.06, seed=5370 + v, rt60=0.8)


# =========================================================================== the rooster
def crow(dur, rng, notes, scale=1.45, rough=0.22, drive_=2.2, rattle_hz=48.0):
    """a cock crowing. notes are the syllables, (start s, length s, [(time in it, Hz)...], vowel from, vowel to, level): a harsh
    harmonic voice (a glottal train with the period-doubling and shimmer of a stretched syrinx), each syllable rising into the
    next, the last held high with the throat's rattle in it and let fall; through a beak and throat (formants at about 1.45
    times a man's), driven hard"""
    n = ns(dur)
    f0 = np.full(n, 400.0)
    amp = np.zeros(n)
    curve = np.zeros(n)
    segs = []
    for at, length, pts, va, vb, g in notes:
        i0, i1 = ns(at), min(n, ns(at + length))
        if i1 <= i0:
            continue
        f0[i0:i1] = env_shape(pts, length)[:i1 - i0]
        att, rel = min(0.018, length * 0.2), min(0.06, length * 0.3)
        amp[i0:i1] = g * env_shape([(0, 0), (att, 1), (length - rel, 0.92), (length, 0)], length)[:i1 - i0]
        segs.append((i0, i1, va, vb))
    # the pitch between syllables glides on, so the source never jumps
    idx = np.nonzero(amp > 0)[0]
    if len(idx):
        f0 = np.interp(np.arange(n), idx, f0[idx])
    src = glottal(dur, f0, rng, jitter=0.022, shimmer=0.22, subharm=rough, open_q=0.42, breath=0.2)
    y = np.zeros(n)
    for i0, i1, va, vb in segs:
        seg = src[i0:i1]
        y[i0:i1] = formants(seg, va, vb, scale=scale, curve=np.linspace(0, 1, i1 - i0) ** 0.7,
                            gains=(1.0, 0.85, 0.5, 0.3))
    y = drive(nrm(y), drive_) * amp
    # the rattle of the held note: the throat fluttering, faster as the note goes on
    t = np.arange(n) / SR
    rat = 1 - 0.28 * (0.5 + 0.5 * np.sin(2 * np.pi * rattle_hz * t + 2.0 * smooth_random(dur, rng, 6)))
    y = y * rat
    y = eq(y, "peak", 2400.0, 0.8, 4.0)
    return lp(hp(y, 300), 9000)


def crow_notes(shape, k=1.0, rng=None):
    """the syllables of a crow, 'cock-a-doodle-doo' and its kin, at a pitch k times the classic one"""
    if shape == "classic":
        s = [(0.0, 0.11, [(0, 360), (0.05, 520), (0.11, 480)], "a", "o", 0.9),
             (0.15, 0.09, [(0, 470), (0.09, 560)], "uh", "a", 0.85),
             (0.27, 0.15, [(0, 540), (0.08, 650), (0.15, 620)], "o", "u", 0.9),
             (0.46, 0.1, [(0, 600), (0.1, 660)], "uh", "a", 0.9),
             (0.6, 1.1, [(0, 620), (0.15, 730), (0.75, 720), (0.95, 600), (1.1, 430)], "a", "u", 1.0)]
    elif shape == "old":
        s = [(0.0, 0.13, [(0, 330), (0.06, 460), (0.13, 430)], "a", "o", 0.9),
             (0.19, 0.1, [(0, 420), (0.1, 500)], "uh", "a", 0.85),
             (0.34, 0.17, [(0, 480), (0.09, 580), (0.17, 560)], "o", "u", 0.9),
             (0.55, 0.11, [(0, 540), (0.11, 590)], "uh", "a", 0.9),
             (0.71, 1.3, [(0, 560), (0.2, 650), (0.9, 640), (1.05, 690), (1.15, 520), (1.3, 380)], "a", "u", 1.0)]
    elif shape == "young":
        s = [(0.0, 0.09, [(0, 420), (0.04, 600), (0.09, 560)], "a", "o", 0.9),
             (0.13, 0.08, [(0, 560), (0.08, 650)], "uh", "a", 0.85),
             (0.25, 0.13, [(0, 620), (0.07, 760), (0.13, 720)], "o", "u", 0.9),
             (0.42, 0.95, [(0, 700), (0.12, 820), (0.5, 830), (0.55, 980), (0.7, 900), (0.95, 520)], "a", "u", 1.0)]
    else:  # "four": a bird whose crow has four syllables, the second run into the third
        s = [(0.0, 0.12, [(0, 380), (0.05, 540), (0.12, 500)], "a", "o", 0.9),
             (0.17, 0.2, [(0, 500), (0.08, 610), (0.2, 640)], "uh", "u", 0.92),
             (0.41, 0.1, [(0, 610), (0.1, 680)], "uh", "a", 0.9),
             (0.55, 1.25, [(0, 640), (0.18, 740), (0.85, 735), (1.05, 620), (1.25, 450)], "a", "u", 1.0)]
    return [(at, length, [(tt, f * k) for tt, f in pts], va, vb, g) for at, length, pts, va, vb, g in s]


@effect("rooster", 4, [1.9, 2.2, 1.6, 2.05], "A rooster crows", 56, loud=-14.0, limit=3.0, fade_out=0.35,
        when="once at first light (the clock's 5:00 to 5:45, a minute drawn for each village and day) from one of the village's "
             "barns (or its farthest house, or the edge of its ground), while a player is in the village (village/live/"
             "VillageVisits) (16.1 row 1)")
def rooster(v, rng):
    """a cock crowing from a coop across the yard: four birds. v1 the classic 'cock-a-doodle-doo' in five syllables, v2 an old
    bird, lower and slower, his long note cracking at the top, v3 a young cockerel, high, his last note breaking upward, v4 a
    bird whose crow has four syllables, heard a little farther off"""
    shape, k, rough, metres, wall = [("classic", 1.0, 0.22, 14, 9), ("old", 0.93, 0.32, 18, 12), ("young", 1.0, 0.16, 12, 7),
                                     ("four", 0.97, 0.24, 26, 11)][v]
    dur = [1.9, 2.2, 1.6, 2.05][v]
    notes = crow_notes(shape, k * rng.uniform(0.985, 1.015), rng)
    y = crow(dur, rng, notes, scale=rng.uniform(1.4, 1.5), rough=rough)
    return afar(y, metres, wall=wall, seed=5380 + v)


# =========================================================================== build
def build(assets, names, wav_dir=None):
    written = []
    for event in names:
        r = EVENTS[event]
        for v in range(r["files"]):
            t0 = time.time()
            name = sound_path(event, v + 1)
            p = os.path.join(assets, "sounds", name + ".ogg")
            if r["kind"] == "loop":
                x = render_loop(event, r, v)
                x = write_checked(p, x)
                d_len, step, seam = seam_report(p, len(x))
                extra = f"  loop {len(x)} samples (file {d_len:+d}), join {step:4.1f}x a step, {seam:4.2f}x the usual highs"
            else:
                x, over, short = build_effect(event, r, v)
                x = write_checked(p, x)
                extra = (f"  limited {over:.1f} dB" if over > 0.05 else "") + \
                    (f"  {short:.1f} dB under (set by peak)" if short > 0.05 else "")
            if wav_dir:
                write_wav(os.path.join(wav_dir, name + ".wav"), x)
            y = read_audio(p)[0]
            print(f"{name:24s} {probe(p)[0] / SR:6.3f}s  peak {to_db(np.max(np.abs(y))):6.1f}  tp {20 * np.log10(true_peak(y)):6.1f}  "
                  f"rms {to_db(rms(y)):6.1f}  short {float(np.max(loudness_curve(y))):6.1f}  {time.time() - t0:5.2f}s{extra}",
                  flush=True)
            written.append(name)
    return written


def select(only):
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        s = s.removeprefix("village.")
        if s not in EVENTS:
            raise SystemExit(f"unknown: {s} (events: {', '.join(EVENTS)})")
        if s not in names:
            names.append(s)
    return names


# =========================================================================== fragments
# The village ambience (ambience/village.py, made by make_ambience.py), in the order its fragment lists them; their reach and
# subtitle are make_ambience.EVENTS'
AMBIENCE = ["village_day", "village_night", "village_market", "village_birds", "village_crickets", "dog_far", "hammer_far",
            "child_far"]
AMBIENCE_SUBTITLES = {"dog_far": "Dog barks far off", "hammer_far": "Hammering far off", "child_far": "Child calls far off"}


def ambience_entries(assets):
    """the sounds.json entries of the village ambience, built as make_ambience.py --sounds builds them (from the files on disk and
    its EVENTS), so either writes the same"""
    import make_ambience as amb
    out = {}
    for name in AMBIENCE:
        reach, subtitle, *listed = amb.EVENTS[name]
        stems = []
        while os.path.exists(os.path.join(assets, "sounds", "ambience", f"{name}{len(stems) + 1}.ogg")):
            stems.append(f"{name}{len(stems) + 1}")
        files = [f"guildmark:ambience/{stem}" for stem in stems]
        if not files:
            continue
        entry = {"sounds": files if reach is None else [{"name": f, "attenuation_distance": reach} for f in files]}
        if subtitle:
            entry["subtitle"] = f"subtitles.guildmark.ambience.{subtitle}"
        out[f"ambience.{name}"] = entry
    return out


def fragments(assets):
    """(the sounds.json entries of the seven events and of the village ambience, their subtitles). Every one-shot carries other
    than the game's 16 blocks or is said to carry 16, so each has its reach as the sound's attenuation_distance; the loop is not
    streamed (it is short and starts often), as the cook's and the mills' loops are not"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        entries = []
        for v in range(r["files"]):
            e = {"name": f"guildmark:{sound_path(event, v + 1)}"}
            if r["kind"] == "loop":
                e["stream"] = False
            e["attenuation_distance"] = r["reach"]
            entries.append(e)
        sounds[f"village.{event}"] = {"sounds": entries, "subtitle": f"subtitles.guildmark.village.{event}"}
        lang[f"subtitles.guildmark.village.{event}"] = r["subtitle"]
    sounds.update(ambience_entries(assets))
    import make_ambience as amb
    for name, text in AMBIENCE_SUBTITLES.items():
        lang[f"subtitles.guildmark.ambience.{amb.EVENTS[name][1]}"] = text
    return sounds, lang


def write_fragments(assets):
    """villages_sounds.json and villages_lang.json beside this script, for art/lang/merge_lang.py to add to sounds.json and
    en_us.json"""
    sounds, lang = fragments(assets)
    for fname, data in (("villages_sounds.json", sounds), ("villages_lang.json", lang)):
        with open(os.path.join(HERE, fname), "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(os.path.join(HERE, fname), len(data), "entries")


# =========================================================================== the listen-through
LOOP_SHOWN = 14.0  # how long the whisper loop plays in the listen-through (twice round, so its join is heard)
BED_SHOWN = 60.0  # and each ambience bed
GAP = 0.7

# The ambience as the listen-through plays it: (name, files, what each is)
AMBIENCE_SHOWN = [
    ("village_day", 2, "bed"), ("village_birds", 1, "bed"), ("village_market", 1, "bed"), ("village_night", 2, "bed"),
    ("village_crickets", 1, "bed"), ("hammer_far", 3, "far"), ("child_far", 3, "far"), ("dog_far", 3, "far"),
]


def listen_through(assets, path_):
    """one MP3 to listen to on a phone: every file of the seven events (in the table's order; the whisper loop for fourteen
    seconds), then the village ambience (each bed for a minute, then the far-off one-shots), read back from disk so it is what the
    game will play, a gap between and a chapter mark at each. The village sounds are at the levels the game plays them at; the
    beds come at the level the village layer plays them by day at full settings (0.8 to 0.9 of the file), and the far one-shots
    as the layer places them, about 35 blocks off. Returns the chapters [(start, end, title, key)]"""
    pieces, marks, at = [], [], 0.0

    def add(x, title, key):
        nonlocal at
        mono = x.mean(axis=0) if x.ndim == 2 else x
        marks.append((at, at + mono.shape[-1] / SR + GAP, title, key))
        pieces.append(x if x.ndim == 2 else np.stack([x, x]))
        pieces.append(np.zeros((2, ns(GAP))))
        at += x.shape[-1] / SR + GAP

    for event, r in EVENTS.items():
        for v in range(r["files"]):
            p = os.path.join(assets, "sounds", sound_path(event, v + 1) + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {sound_path(event, v + 1)})")
                continue
            x = read_audio(p)[0]
            if r["kind"] == "loop":
                x = fade(np.tile(x, int(np.ceil(LOOP_SHOWN * SR / len(x))))[:ns(LOOP_SHOWN)], 0.3, 0.5)
            title = f"village.{event}" + (f" {v + 1} of {r['files']}" if r["files"] > 1 else "") + \
                (" (loop, twice round)" if r["kind"] == "loop" else "")
            add(x, title, f"village.{event}")
    import make_ambience as amb
    for name, files, kind in AMBIENCE_SHOWN:
        for v in range(files):
            p = os.path.join(assets, "sounds", "ambience", f"{name}{v + 1}.ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: ambience/{name}{v + 1})")
                continue
            x = read_audio(p)
            if kind == "bed":
                reps = int(np.ceil(BED_SHOWN * SR / x.shape[1]))
                x = np.tile(x, (1, reps))[:, :ns(BED_SHOWN)]
                x = np.stack([fade(c, 2.0, 2.0) for c in x]) * 0.85
                title = f"ambience.{name}" + (f" {v + 1} of {files}" if files > 1 else "") + " (bed, a minute)"
            else:
                reach = amb.EVENTS[name][0]
                x = x[0] * max(0.0, 1 - 35.0 / reach) * 0.9
                title = f"ambience.{name} {v + 1} of {files} (35 blocks off)"
            add(x, title, f"ambience.{name}")
    audio = np.concatenate(pieces, axis=1).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w", encoding="utf-8") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark village sounds\nartist=Guildmark\n")
            for a, b, title, _ in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\ntitle={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
        shutil.copyfile(mp3, path_)
    print(f"{path_}  {at:.1f}s, {len(marks)} chapters, {os.path.getsize(path_) / 1e6:.2f} MB")
    return marks


def clock(t):
    return f"{int(t // 60)}:{t % 60:04.1f}"


def made_of(fn):
    """the first paragraph of a recipe's docstring, on one line"""
    import inspect
    doc = inspect.getdoc(fn) or ""
    return " ".join(doc.split("\n\n")[0].split())


# What the village layer (client/ambience/VillageAmbience.kt) does with each of the ambience's sounds
AMBIENCE_WHEN = {
    "village_day": "under everything by day in a village, from first light (five) to dusk (half past eight), crossing over with the "
                   "night bed through the twilights; a little under in rain and storm, and softer indoors",
    "village_night": "under everything by night in a village, through the twilights and the dark; the wind in it rises with a storm",
    "village_market": "on the village's own market day from eight to four (the stalls' hours), as loud as the folk about the player "
                      "are many: nobody near, nothing",
    "village_birds": "over the day bed: hardest in the dawn chorus (from before five to nine), quiet through the heat of the day, up "
                     "again toward evening, done by dark; thin in winter, silent in rain",
    "village_crickets": "on warm nights only (late spring to early autumn when the seasons are on), from dusk until the first birds; "
                        "silent in rain",
    "hammer_far": "now and then (every 20 to 65 seconds) by day from seven to five, on working days (not the rest day), from 26 to 46 "
                  "blocks off; not in rain",
    "child_far": "now and then (every 30 to 85 seconds) by day from eight to seven, from 26 to 48 blocks off; not in rain",
    "dog_far": "now and then (every 25 to 80 seconds) from dusk to first light, from 28 to 52 blocks off",
}


def write_when(marks, path_, assets):
    """the text of what plays when: for each sound, its files, length, reach and subtitle, what it is made of, the moment it
    belongs to, and where it is in the listen-through"""
    import make_ambience as amb
    amb.load_modules(["village"])
    at = {}
    for a, _, _, key in marks:
        at.setdefault(key, []).append(a)
    lines = ["Village sounds: what plays when", "=" * 31, "",
             "village_all.mp3 plays every file of the seven village sounds once, in the order of the design's table (living-world/"
             "villages/design.md 17.3), the whispers twice round so that their join is heard; then the village layer's own "
             "sounds: each bed for a minute (they are loops of 30 or 40 seconds, so the join comes by once or twice), then the "
             "far-off one-shots, as quiet as the layer plays them about 35 blocks off. A phone's player shows a chapter at each; "
             "the times below are where each starts.",
             "",
             "The village sounds are at the game's own levels, and the game's levels are quiet: turn the phone up until the first "
             "one, village.door_bolt, is comfortable, and judge the rest against it. The beds sit well under it on purpose: in the "
             "game they live under everything, quieter and sparser than a capital's (fewer folk, more country).", "",
             "THE VILLAGE'S OWN SOUNDS (played in the world by the server, heard by everyone near)", ""]
    for event, r in EVENTS.items():
        lens = sorted(set(r["lengths"]))
        if r["kind"] == "loop":
            what = f"{r['files']} loops of {' and '.join(f'{x:g}' for x in r['lengths'])} s, each built to join itself"
        else:
            what = f"{r['files']} files, {lens[0]:g} to {lens[-1]:g} s"
        lines.append(f"village.{event}: {what}; carries {r['reach']} blocks")
        lines.append(f"  subtitle: \"{r['subtitle']}\"")
        lines.append("  in village_all.mp3: " + ", ".join(clock(t) for t in at.get(f"village.{event}", [])))
        lines.append(f"  made of: {made_of(r['fn'])}")
        lines.append(f"  plays: {r['when']}")
        lines.append("")
    lines += ["THE VILLAGE LAYER (played on each player's own side, under the Guildmark setting Village Sounds)", ""]
    from ambkit import RECIPES
    for name in AMBIENCE:
        rec = RECIPES.get(name)
        reach, subtitle = amb.EVENTS[name][0], amb.EVENTS[name][1]
        if rec is None:
            continue
        if rec["loop"]:
            what = f"{rec['variants']} stereo loop{'s' if rec['variants'] > 1 else ''} of {rec['loop']:g} s, unpositioned"
        else:
            ml = rec["max_len"] if isinstance(rec["max_len"], (list, tuple)) else [rec["max_len"]]
            what = f"{rec['variants']} files, up to {max(ml):g} s, placed in the world, carrying {reach} blocks"
        lines.append(f"ambience.{name}: {what}")
        if subtitle:
            lines.append(f"  subtitle: \"{AMBIENCE_SUBTITLES.get(name, '')}\"")
        lines.append("  in village_all.mp3: " + ", ".join(clock(t) for t in at.get(f"ambience.{name}", [])))
        lines.append(f"  made of: {made_of(rec['fn'])}")
        lines.append(f"  plays: {AMBIENCE_WHEN[name]}")
        lines.append("")
    lines += ["Also heard in a village, and not made here: the bells (the vanilla bell, rung by the bellman), the watch's horn "
              "(works.horn), the lamps (townsfolk.lamp.*), the crew's and the pedlar's own work, and the city layer's crowd, which "
              "in a village is turned well down, with no criers but on its market day and no rattling carts.", ""]
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


def bed_join(path_, n):
    """a stereo bed's join, read back from the file: its length against the loop's, the step across the join against the
    steps between samples elsewhere (their 99.9th centile: a bed is full of quick sounds, so its usual step says little), and
    the highs in the 10 ms round the join against the highs of the loop's busier moments (the 90th centile of its 10 ms
    windows); both channels, the worse. A click shows as a step past 1 or highs well past the busy moments'"""
    y = read_audio(path_)
    worst = (y.shape[1] - n, 0.0, 0.0)
    for c in y:
        step = abs(c[0] - c[-1]) / (float(np.percentile(np.abs(np.diff(c)), 99.9)) + 1e-12)
        h = hp(c, 1500.0, 2)
        w = ns(0.005)
        seam = np.sqrt(np.mean(np.concatenate([h[-w:], h[:w]]) ** 2))
        els = [np.sqrt(np.mean(h[i:i + 2 * w] ** 2)) for i in range(ns(0.1), len(c) - ns(0.1), 2 * w)]
        worst = (worst[0], max(worst[1], step), max(worst[2], seam / (np.percentile(els, 90) + 1e-12)))
    return worst


# The design's table (17.3) for the ambience: files and lengths (s); the two beds the table does not list (the birds and the
# crickets, split from the day's and the night's so the layer can follow the hour and the season) are checked as made
AMB_TABLE = {"village_day": (2, 40.0, 40.0), "village_night": (2, 40.0, 40.0), "village_market": (1, 30.0, 30.0),
             "dog_far": (3, 1.0, 2.0), "hammer_far": (3, 1.0, 2.0), "child_far": (3, 1.0, 2.0)}

# The design's table (17.3): files and the range of lengths for each event, its reach and whether it loops
TABLE = {"door_bolt": (4, 0.35, 0.55, 16, False), "hush": (2, 6.0, 8.0, 12, True), "cheer": (3, 1.4, 2.0, 32, False),
         "candle_lay": (3, 0.6, 0.9, 16, False), "pedlar_bell": (3, 1.2, 1.8, 40, False), "baby": (4, 0.7, 1.3, 12, False),
         "rooster": (4, 1.4, 2.2, 56, False)}


def audit(assets):
    """every file read back from disk and held to its row of the design's table: there, mono 44.1 kHz Vorbis, exactly its
    length (inside the row's range), its peak and true peak under -1 dBFS (a loop's under -10), a one-shot's loudest 100 ms near
    its target, a loop's RMS and join; the ambience's beds and one-shots likewise (stereo beds, their join after the encoder);
    the reaches against VillageSounds.kt, the fragments against the recipes and the files, sounds.json against both. Prints a line
    for each; returns 1 if any fails"""
    bad, files = [], 0
    for event, r in EVENTS.items():
        want = TABLE.get(event)
        if want is None:
            bad.append(f"village.{event} is not in the design's table")
        elif (r["files"], r["reach"], r["kind"] == "loop") != (want[0], want[3], want[4]) or \
                not all(want[1] <= x <= want[2] for x in r["lengths"]):
            bad.append(f"village.{event}: {r['files']} files of {r['lengths']} s, reach {r['reach']}; the table says {want}")
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
            if frames != ns(r["lengths"][v]):
                notes.append(f"is {frames} samples, the recipe says {ns(r['lengths'][v])}")
            if r["kind"] == "loop":
                d_len, step, seam = seam_report(p, ns(r["lengths"][v]))
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
            print(f"{stem:24s} {frames / SR:6.3f}s  peak {pk:6.1f}  tp {tp:6.1f}  {tail}" + ("   <-- " + "; ".join(notes) if notes else ""))
            if notes:
                bad.append(f"{stem}: " + "; ".join(notes))
    if list(EVENTS) != list(TABLE):
        bad.append("the events are not the design's table, in its order")
    # the ambience
    sys.path.insert(0, HERE)
    from ambkit import RECIPES
    import make_ambience as amb
    amb.load_modules(["village"])
    for name in AMBIENCE:
        rec = RECIPES.get(name)
        if rec is None:
            bad.append(f"ambience.{name}: no recipe in ambience/village.py")
            continue
        if name not in amb.EVENTS:
            bad.append(f"ambience.{name}: not in make_ambience.EVENTS")
        want = AMB_TABLE.get(name)
        made = rec["loop"] or (max(rec["max_len"]) if isinstance(rec["max_len"], (list, tuple)) else rec["max_len"])
        if want and (rec["variants"] != want[0] or not want[1] - 1e-6 <= (rec["loop"] or want[1]) <= want[2] + 1e-6):
            bad.append(f"ambience.{name}: {rec['variants']} files of {made} s; the table says {want}")
        if want is None:
            print(f"(ambience.{name}: beyond the design's table, {rec['variants']} file of {made} s)")
        for v in range(rec["variants"]):
            stem = f"ambience/{name}{v + 1}"
            p = os.path.join(assets, "sounds", stem + ".ogg")
            files += 1
            if not os.path.exists(p):
                bad.append(f"{stem}: missing")
                continue
            frames, ch, rate, codec = probe(p)
            y = read_audio(p)
            pk, tp = to_db(np.max(np.abs(y))), 20 * np.log10(max(true_peak(c) for c in y))
            notes = []
            if rec["loop"]:
                if (ch, rate, codec) != (2, SR, "vorbis"):
                    notes.append(f"is {ch} ch {rate} Hz {codec}, a bed is stereo")
                d_len, step, seam = bed_join(p, ns(rec["loop"]))
                r_db = to_db(np.sqrt(np.mean(np.square(y))))
                if d_len != 0:
                    notes.append(f"decodes {d_len:+d} samples")
                if abs(r_db - rec["rms_db"]) > 0.6:
                    notes.append(f"RMS {r_db:.1f}, wanted {rec['rms_db']}")
                if tp > -1.0:
                    notes.append(f"true peak {tp:.1f}")
                if step > 1.0 or seam > 1.5:
                    notes.append(f"join {step:.2f}x the big steps, {seam:.2f}x the busy highs")
                tail = f"rms {r_db:6.1f}  join {step:4.2f}x the big steps, {seam:4.2f}x the busy highs"
            else:
                if (ch, rate, codec) != (1, SR, "vorbis"):
                    notes.append(f"is {ch} ch {rate} Hz {codec}")
                ml = rec["max_len"][v] if isinstance(rec["max_len"], (list, tuple)) else rec["max_len"]
                lo, hi = (AMB_TABLE.get(name) or (0, 1.0, 2.0))[1:]
                if not lo <= frames / SR <= hi + 1e-6 or (ml and frames > ns(ml)):
                    notes.append(f"is {frames / SR:.3f} s; the table says {lo:g} to {hi:g}")
                if tp > -1.0:
                    notes.append(f"true peak {tp:.1f}")
                tail = f"rms {to_db(np.sqrt(np.mean(np.square(y)))):6.1f}"
            print(f"{stem:24s} {frames / SR:6.3f}s  peak {pk:6.1f}  tp {tp:6.1f}  {tail}" + ("   <-- " + "; ".join(notes) if notes else ""))
            if notes:
                bad.append(f"{stem}: " + "; ".join(notes))
    # the reaches the server registers its events with
    kt = os.path.join(HERE, "..", "..", "src", "main", "kotlin", "io", "github", "vaspyyy", "guildmark", "village", "live",
                      "VillageSounds.kt")
    if os.path.exists(kt):
        found = dict(re.findall(r'sound\("village\.([a-z_]+)", (\d+)f\)', open(kt, encoding="utf-8").read()))
        want = {e: str(r["reach"]) for e, r in EVENTS.items()}
        if found != want:
            bad.append(f"VillageSounds.kt registers {found}, the recipes say {want}")
    else:
        bad.append("village/live/VillageSounds.kt is missing")
    # the pedlar's bell is to be brighter than the works' handbell: their spectral centroids
    def centroid(p):
        y = read_audio(p)[0]
        sp = np.abs(np.fft.rfft(y)) ** 2
        return float(np.sum(sp * np.fft.rfftfreq(len(y), 1 / SR)) / (np.sum(sp) + 1e-12))
    works_bell = os.path.join(assets, "sounds", "works", "handbell1.ogg")
    pedlar = [os.path.join(assets, "sounds", sound_path("pedlar_bell", v + 1) + ".ogg") for v in range(3)]
    if os.path.exists(works_bell) and all(os.path.exists(p) for p in pedlar):
        wc, pc = centroid(works_bell), [centroid(p) for p in pedlar]
        print(f"brightness (spectral centroid): works.handbell1 {wc:.0f} Hz, village.pedlar_bell " +
              ", ".join(f"{c:.0f}" for c in pc) + " Hz")
        if min(pc) <= wc:
            bad.append("village.pedlar_bell is not brighter than works.handbell")
    want_sounds, want_lang = fragments(assets)
    for fname, want in (("villages_sounds.json", want_sounds), ("villages_lang.json", want_lang)):
        fp = os.path.join(HERE, fname)
        if not os.path.exists(fp):
            bad.append(f"{fname} is missing (a full render writes it)")
        elif json.load(open(fp, encoding="utf-8")) != want:
            bad.append(f"{fname} is not what the recipes and the files say (a full render writes it)")
    merged = json.load(open(os.path.join(assets, "sounds.json"), encoding="utf-8"))
    for key, ent in want_sounds.items():
        if merged.get(key) != ent:
            bad.append(f"sounds.json's {key} is not the fragment's (run art/lang/merge_lang.py)")
        for e in ent["sounds"]:
            n = e if isinstance(e, str) else e["name"]
            if not os.path.exists(os.path.join(assets, "sounds", n.split(":", 1)[1] + ".ogg")):
                bad.append(f"{key}: {n} has no file")
    print(f"\n{len(EVENTS)} events and {len(AMBIENCE)} of the ambience, {files} files checked: " +
          ("all as the design says" if not bad else f"{len(bad)} problems"))
    for b in bad:
        print("  PROBLEM:", b)
    return 1 if bad else 0


def main():
    global ARGS
    ap = argparse.ArgumentParser(description="Living villages: the village's own sounds")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated events, e.g. rooster,door_bolt")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of the files on disk, and "
                                                "village_what_plays_when.txt beside it")
    ap.add_argument("--audit", action="store_true", help="render nothing: read every file back and check it against the design")
    ARGS = ap.parse_args()
    if ARGS.audit:
        raise SystemExit(audit(ARGS.assets))
    names = select(ARGS.only) if ARGS.only else list(EVENTS)
    build(ARGS.assets, names, ARGS.wav)
    if not ARGS.only:
        write_fragments(ARGS.assets)
    if ARGS.mp3:
        marks = listen_through(ARGS.assets, ARGS.mp3)
        write_when(marks, os.path.join(os.path.dirname(os.path.abspath(ARGS.mp3)), "village_what_plays_when.txt"), ARGS.assets)


if __name__ == "__main__":
    main()
