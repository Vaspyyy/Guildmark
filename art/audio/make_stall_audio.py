"""Guildmark's players' own stalls and homestead shipping: a table in the market hall let, stocked and paying, the
renter's pennant dropping over it, and the carter who takes a homestead's goods to market.

usage (from the repo root):
    python3 -B art/audio/make_stall_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]
        [--preview out.mp3] [--entries out.json]

Writes mono 44.1 kHz Ogg Vorbis files, one per variant of each event: stall/<name><n>.ogg for the stall's (stall.rent,
stall.set_out, stall.till, stall.pennant) and homestead/<name><n>.ogg for shipping's (homestead.carter.wheels as
carter_wheels<n>, homestead.carter.load as carter_load<n>, homestead.purse as purse<n>). --only takes event names,
--preview writes every sound in a row with a breath between as an MP3 to listen to, and --entries their sounds.json
entries (the block to merge, in this order).

Everything is synthesised (numpy/scipy through sfxkit and the townsfolk kit's helpers). Loudness is set as the
townsfolk's is (the loudest 100 ms, K-weighted), peaks held under -1 dBFS. All randomness is seeded, so a rerun is
bit-identical.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403
import make_townsfolk_sfx as tf  # noqa: E402
from make_townsfolk_sfx import level, write_checked, nrm, small_room, street, knock, thud, rustle, creak, lead_in  # noqa: E402

EVENTS = {}  # event -> recipe


def effect(event, file, variants=1, loud=-17.0, max_len=None, fade_out=0.03, tail_db=-62.0, limit=2.0, reach=None):
    """registers a sound: file is its path under sounds/ without the variant's number, loud the target for its loudest
    100 ms (K-weighted LUFS), limit the most dB of peak limiting allowed to get there, reach its attenuation distance in
    blocks when it isn't the game's 16"""
    def deco(fn):
        EVENTS[event] = dict(fn=fn, file=file, variants=variants, loud=loud, max_len=max_len, fade_out=fade_out,
                             tail_db=tail_db, limit=limit, reach=reach)
        return fn
    return deco


# =========================================================================== shared
def counter(dur, rng, f=150.0):
    """the market hall's trestle table under what's set on it: a broad, dull wooden knock"""
    return nrm(knock(dur, f * np.array([1.0, 1.6, 2.5, 3.8]), [0.07, 0.055, 0.04, 0.028], [1, .8, .5, .3], rng, lo=80,
                     hi=2500, ex_len=0.004))


def coin_down(rng, f0, damp=0.12, gain=1.0):
    """a coin set down on wood: its short ring, quickly damped by the board, and the board's own small tick"""
    m = Mix(0.4)
    m.add(tf.coin(0.35, rng, f0=f0, t60=damp, bright=0.7, click=0.45), 0.0, gain)
    m.add(nrm(knock(0.06, [610.0, 1150.0, 1900.0], [0.012, 0.009, 0.006], [1, .5, .25], rng, lo=300, hi=5000,
                    ex_len=0.001)), 0.0, 0.25 * gain)
    return m.out()


def cloth(dur, rng, rate, lo=250.0, hi=3500.0):
    """heavy cloth on the move (rate, a 0..1 curve, is how fast): a soft, papery-less hush, its weave crackling
    faintly, swelling as it folds over"""
    hush = bp(colored(dur, rng, -2.0), lo, hi) * rate ** 1.3 * smooth_random(dur, rng, 18, 0.5, 1.0)
    weave = crackle(dur, rng, 300 * rate + 10, decay=0.0009, f_lo=lo * 2, f_hi=hi, size_var=0.7) * rate
    return nrm(nrm(hush) + 0.35 * nrm(weave))


def boards(dur, rng, f=128.0):
    """the cart's bed of planks, knocked by what lands on it"""
    return nrm(knock(dur, f * np.array([1.0, 1.68, 2.71, 4.05, 5.6]), [0.08, 0.06, 0.045, 0.03, 0.02],
                     [1, .85, .6, .4, .22], rng, lo=70, hi=3500, ex_len=0.005))


# =========================================================================== the stall
@effect("stall.rent", "stall/rent", 1, loud=-17.0, max_len=1.3, fade_out=0.25)
def rent(v, rng):
    """a week's rent paid at the table: three coins set down on the counter one after another, then the hall's rent
    book stamped (the wooden stamp thumped down on the page) and shut"""
    dur = 1.3
    m = Mix(dur)
    for at, f0, g in ((0.0, 3150.0, 0.8), (0.15, 3420.0, 0.7), (0.27, 3060.0, 0.85)):
        m.add(coin_down(rng, f0 * rng.uniform(0.98, 1.02)), at, g)
    m.add(thud(0.18, rng, f=170, decay=0.014, tone=0.3), 0.55, 0.8)
    m.add(nrm(knock(0.15, [420.0, 760.0, 1250.0, 1900.0], [0.035, 0.028, 0.02, 0.012], [1, .7, .45, .25], rng, lo=200,
                    hi=5000, ex_len=0.002)), 0.551, 0.5)
    m.add(counter(0.25, rng, 140), 0.552, 0.45)
    m.add(nrm(crackle(0.05, rng, 1500, decay=0.0005, f_lo=1800, f_hi=8000) * bell(0.05, 0.2, 1.0, 2.0)), 0.55, 0.18)
    # The book shut: its cover swung over with a puff of air, then a soft clap
    m.add(nrm(bp(colored(0.14, rng, -2.0), 200, 2000) * bell(0.14, 0.6, 1.4, 1.2)), 0.8, 0.25)
    m.add(thud(0.14, rng, f=140, decay=0.012, tone=0.2), 0.93, 0.55)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(y, wet=0.12, rt60=0.6, seed=210))


@effect("stall.set_out", "stall/set_out", 3, loud=-17.0, max_len=0.8, fade_out=0.15, limit=3.0)
def set_out(v, rng):
    """goods set out on the table: v1 a little crate set down and slid into line, v2 produce tipped out of a sack and
    rolling to a stop (apples, roots), v3 a sack set down and patted into shape"""
    dur = 0.8
    m = Mix(dur)
    if v == 0:
        m.add(tf.box(0.3, rng, 380, 1.0, 0.25), 0.0, 1.0)
        m.add(tf.slide(0.16, rng, 400, 2600), 0.2, 0.22)
        m.add(tf.box(0.2, rng, 470, 0.4), 0.37, 0.35)
    elif v == 1:
        m.add(cloth(0.18, rng, bell(0.18, 0.3, 1.0, 1.5)), 0.0, 0.3)
        at = 0.08
        for i in range(8):
            at += rng.exponential(0.035) + 0.012
            if at > dur - 0.12:
                break
            f = rng.uniform(260, 520)
            m.add(nrm(knock(0.12, [f, f * 1.9, f * 3.1], [0.03, 0.02, 0.012], [1, .5, .2], rng, lo=150, hi=4000,
                            ex_len=0.003)), at, rng.uniform(0.35, 0.8) * np.exp(-(at - 0.08) / 0.35))
        m.add(counter(0.3, rng, 150), 0.1, 0.35)
        m.add(tf.slide(0.3, rng, 300, 1800, rate=120), 0.25, 0.12)
    else:
        m.add(thud(0.3, rng, f=110, decay=0.03, tone=0.25), 0.0, 1.0)
        m.add(counter(0.3, rng, 135), 0.002, 0.5)
        m.add(tf.grains(0.4, rng, 14, 0.02, 0.06, 1500, 6000, t60=(0.004, 0.01), amp_decay=0.15), 0.0, 0.18)
        for at, g in ((0.36, 0.5), (0.52, 0.35)):
            m.add(thud(0.1, rng, f=200, decay=0.008, tone=0.1), at, g)
            m.add(cloth(0.06, rng, bell(0.06, 0.2, 1.0, 2.0)), at, 0.12)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(y, wet=0.1, rt60=0.6, seed=212 + v))


def coffer(dur, rng, f=520.0):
    """the till itself, a small coffer of planks: a light, hollow box knock"""
    return nrm(knock(dur, f * np.array([1.0, 1.55, 2.3, 3.4]), [0.04, 0.03, 0.022, 0.015], [1, .7, .45, .25], rng, lo=200,
                     hi=6000, ex_len=0.002))


@effect("stall.till", "stall/till", 3, loud=-17.0, max_len=0.65, fade_out=0.12, limit=3.0)
def till(v, rng):
    """the till: v1 a handful of coins dropped into it, ringing on each other, v2 the coins scooped out of it in a
    sliding, clinking rush, v3 a single coin dropped in, bouncing once"""
    dur = 0.65
    m = Mix(dur)
    if v == 0:
        for i in range(7):
            at = rng.uniform(0.0, 0.11) + (0.0 if i < 3 else 0.03)
            m.add(tf.coin(0.4, rng, f0=rng.uniform(2900, 4100), t60=rng.uniform(0.05, 0.16), bright=0.7, click=0.4), at,
                  rng.uniform(0.35, 0.8))
        m.add(coffer(0.25, rng, 520), 0.005, 0.5)
        m.add(jingle(0.3, rng, 6, 3000, 8000, t60=(0.01, 0.04), start=0.06, amp_decay=0.12), 0.0, 0.22)
    elif v == 1:
        sd = 0.3
        m.add(nrm(bp(white(sd, rng), 2500, 9000) * bell(sd, 0.45, 1.2, 1.4) * (0.5 + 0.5 * smooth_random(sd, rng, 40))),
              0.0, 0.3)
        m.add(jingle(0.45, rng, 14, 2800, 8500, t60=(0.01, 0.05), spread=0.25, start=0.02, amp_decay=0.3), 0.0, 0.7)
        for at in (0.05, 0.16, 0.24):
            m.add(tf.coin(0.3, rng, f0=rng.uniform(3000, 4000), t60=0.06, bright=0.6, click=0.3), at, 0.45)
        m.add(coffer(0.2, rng, 560), 0.0, 0.2)
    else:
        f0 = 3500.0 * rng.uniform(0.97, 1.03)
        m.add(tf.coin(0.5, rng, f0=f0, t60=0.22, bright=0.8, click=0.45), 0.0, 1.0)
        m.add(coffer(0.2, rng, 540), 0.0, 0.45)
        m.add(tf.coin(0.3, rng, f0=f0 * 1.01, t60=0.09, bright=0.5, click=0.3), 0.11, 0.35)
        m.add(tf.coin(0.2, rng, f0=f0 * 1.01, t60=0.04, bright=0.4, click=0.25), 0.17, 0.15)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(y, wet=0.1, rt60=0.55, seed=216 + v))


@effect("stall.pennant", "stall/pennant", 2, loud=-19.0, max_len=1.1, fade_out=0.25, reach=24)
def pennant(v, rng):
    """the renter's pennant let down over the table (played a little higher as one rolls up): the cloth unrolling off
    its roll in a quickening flutter, the cords creaking as its weight comes onto them, and a soft snap as it falls
    taut; v2 a heavier cloth, more slowly"""
    dur = 1.1
    m = Mix(dur)
    od = [0.72, 0.8][v]
    rate = env_points([(0.0, 0.2), (od * 0.4, 0.6), (od * 0.85, 1.0), (od, 0.3)], od)
    flutter = cloth(od, rng, rate, 300, 4200) * (0.6 + 0.4 * np.sin(2 * np.pi * np.cumsum(9 + 14 * rate) / SR) ** 2)
    m.add(nrm(flutter), 0.0, 0.7)
    m.add(nrm(creak(0.25, rng, 50, 140, [520.0, 930.0, 1500.0], t60=0.04)), od * 0.55, 0.18)
    at = od
    m.add(nrm(bp(white(0.035, rng), 250, 3500) * np.exp(-tvec(0.035) / 0.007)), at, 0.85)
    m.add(nrm(sine_sweep(0.06, 150, 90, 3.0) * np.exp(-tvec(0.06) / 0.014)), at, 0.4)
    m.add(nrm(knock(0.08, [610.0, 1180.0], [0.02, 0.012], [1, .5], rng, lo=300, hi=4000)), at + 0.004, 0.15)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 9000), wet=0.1, rt60=0.6, seed=220 + v))


# =========================================================================== the carter
@effect("homestead.carter.wheels", "homestead/carter_wheels", 3, loud=-21.0, max_len=1.0, fade_out=0.25, reach=24)
def wheels(v, rng):
    """the carter's handcart rolling up or down the road (played every few steps while it moves, so each overlaps the
    next): its iron-shod wheels crunching over grit, a knock each time a wheel's joint comes round, the axle creaking
    in its bearing and the load shifting on the bed"""
    dur = 1.0
    m = Mix(dur)
    sw = (0.55 + 0.45 * smooth_random(dur, rng, 3.5)) * bell(dur, 0.5, 1.1, 1.1) ** 0.4
    m.add(nrm(lp(brown(dur, rng), 260) * sw), 0.0, 0.7)
    m.add(nrm(crackle(dur, rng, 240 * sw + 30, decay=0.0007, f_lo=900, f_hi=5500, size_var=1.0)), 0.0, 0.45)
    for at in [(0.12, 0.62), (0.3, 0.84), (0.05, 0.55)][v]:
        f = rng.uniform(150, 190)
        m.add(nrm(knock(0.2, f * np.array([1.0, 1.7, 2.75, 4.1]), [0.05, 0.04, 0.03, 0.02], [1, .7, .45, .25], rng,
                        lo=90, hi=3000, ex_len=0.003)), at, 0.55)
    m.add(nrm(creak(0.35, rng, 25, 60, [410.0, 760.0, 1240.0], t60=0.05)), [0.4, 0.15, 0.55][v], 0.28)
    m.add(boards(0.3, rng, 120 * rng.uniform(0.95, 1.05)), [0.62, 0.55, 0.3][v], 0.2)
    y = fit(m.out(), ns(dur))
    y = fade(y, fin=0.06)
    return lead_in(street(lp(y, 7000), wet=0.1, seed=230 + v))


@effect("homestead.carter.load", "homestead/carter_load", 3, loud=-16.0, max_len=0.75, fade_out=0.15, limit=3.0)
def load(v, rng):
    """a piece of the load heaped onto the carter's cart: v1 a full sack dropped onto the bed, the grain in it settling,
    v2 a crate set down with a hollow knock, v3 a basket, its wicker creaking and what's in it shifting; the cart rocks
    on its wheels under each"""
    dur = 0.75
    m = Mix(dur)
    if v == 0:
        m.add(thud(0.32, rng, f=95, decay=0.034, tone=0.3), 0.0, 1.0)
        m.add(boards(0.35, rng, 118), 0.003, 0.55)
        m.add(tf.grains(0.5, rng, 20, 0.02, 0.07, 1200, 5500, t60=(0.004, 0.012), amp_decay=0.18), 0.0, 0.2)
    elif v == 1:
        m.add(tf.box(0.35, rng, 300, 1.2, 0.3), 0.0, 1.0)
        m.add(boards(0.35, rng, 125), 0.002, 0.5)
    else:
        m.add(nrm(crackle(0.2, rng, 900 * bell(0.2, 0.25, 1.0, 1.5) + 40, decay=0.0012, f_lo=500, f_hi=4500,
                          size_var=0.9)), 0.0, 0.6)
        m.add(thud(0.25, rng, f=150, decay=0.02, tone=0.2), 0.01, 0.6)
        m.add(boards(0.3, rng, 130), 0.012, 0.4)
        for at in (0.09, 0.15, 0.2):
            f = rng.uniform(300, 520)
            m.add(nrm(knock(0.1, [f, f * 2.1], [0.025, 0.015], [1, .4], rng, lo=150, hi=3500)), at, 0.25)
    m.add(nrm(creak(0.3, rng, 30, 80, [380.0, 690.0, 1150.0], t60=0.05)), 0.08, 0.22)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 8000), wet=0.1, seed=236 + v))


@effect("homestead.purse", "homestead/purse", 1, loud=-17.0, max_len=1.4, fade_out=0.25, reach=12)
def purse(v, rng):
    """the Charter Post's purse emptied into its owner's hand: the leather purse's strings loosened (the hide
    creaking and rustling), then the Marks counted out of it, one coin after another clinking onto the ones already
    in the palm, the last few in a quick run"""
    dur = 1.4
    m = Mix(dur)
    m.add(nrm(creak(0.18, rng, 60, 180, [640.0, 1100.0, 1750.0], t60=0.03)), 0.0, 0.3)
    m.add(rustle(0.25, rng, peak=0.3, density=400, lo=500, hi=4500), 0.02, 0.3)
    at = 0.3
    for i, gap in enumerate((0.0, 0.13, 0.12, 0.11, 0.1, 0.07, 0.06, 0.06)):
        at += gap
        f0 = rng.uniform(3000, 3900)
        m.add(tf.coin(0.3, rng, f0=f0, t60=0.05 + 0.02 * rng.random(), bright=0.6, click=0.35), at, 0.5 + 0.06 * i)
        if i > 0:  # clinking against the coins already in the hand
            m.add(tf.coin(0.15, rng, f0=f0 * rng.uniform(0.9, 1.1), t60=0.03, bright=0.4, click=0.2), at + 0.012, 0.25)
    m.add(jingle(0.3, rng, 6, 3200, 8000, t60=(0.01, 0.03), start=0.02, amp_decay=0.1), at + 0.05, 0.25)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.07, seed=240))


# =========================================================================== main
def build(event, r, v):
    rng = rng_for("stall", event, v)
    x = r["fn"](v, rng)
    x = finalize(x, peak_db=-3.0, fade_out=r["fade_out"], max_len=r["max_len"], tail_db=r["tail_db"])
    return level(x, r["loud"], max_limit_db=r["limit"])


def entries():
    """the sounds.json entries, subtitled subtitles.guildmark.<event>"""
    out = {}
    for event, r in EVENTS.items():
        files = [f"guildmark:{r['file']}{n + 1}" for n in range(r["variants"])]
        sounds = [dict(name=f, attenuation_distance=r["reach"]) for f in files] if r["reach"] else files
        out[event] = {"sounds": sounds, "subtitle": f"subtitles.guildmark.{event}"}
    return out


def write_preview(path, sounds, gap=0.7):
    """every sound in a row with a breath between, as an MP3 (ffmpeg, libmp3lame)"""
    parts = []
    for x in sounds:
        parts += [x, np.zeros(ns(gap))]
    with tempfile.TemporaryDirectory() as td:
        w = os.path.join(td, "preview.wav")
        write_wav(w, np.concatenate(parts))
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", w, "-c:a", "libmp3lame", "-q:a", "3", path], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--preview", default=None)
    ap.add_argument("--entries", default=None)
    a = ap.parse_args()
    sel = a.only.split(",") if a.only else None
    made = []
    for event, r in EVENTS.items():
        if sel and event not in sel:
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            name = f"{r['file']}{v + 1}"
            x, over, short = build(event, r, v)
            x = write_checked(os.path.join(a.assets, "sounds", name + ".ogg"), x)
            if a.wav:
                write_wav(os.path.join(a.wav, name + ".wav"), x)
            made.append(x)
            note = (f"  limited {over:.1f} dB" if over > 0.05 else "") + (f"  {short:.1f} dB under (set by peak)"
                                                                          if short > 0.05 else "")
            print(f"{name:28s} {len(x) / SR:6.3f}s  {time.time() - t0:5.2f}s{note}", flush=True)
    if a.preview:
        write_preview(a.preview, made)
        print("preview", a.preview)
    if a.entries:
        with open(a.entries, "w") as f:
            json.dump(entries(), f, indent=2)
            f.write("\n")
        print("entries", a.entries)


if __name__ == "__main__":
    main()
