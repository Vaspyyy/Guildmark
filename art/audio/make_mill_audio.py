"""Guildmark's mills: the sounds by a capital's windmill and watermill while they turn (client/mill/MillSounds.kt), made from
nothing: the windmill's sails sweeping round and their timbers creaking, the millstones grinding inside, and the watermill's
wheel splashing through its stream.

usage (from the repo root):
    python3 -B art/audio/make_mill_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only sails,stones] [--mp3 FILE]

Writes three seamless mono loops, 44.1 kHz Ogg Vorbis, to <assets>/sounds/mill/<name>.ogg. The game plays each one at its
mill while the mill turns, louder and a little higher the faster it turns (the sails from pitch 0.85 to 1, the wheel from
0.75 to 1), and fades it out as the mill stops; only the three mills nearest the player are heard. --only takes names,
--wav also writes WAV copies, --mp3 joins what was rendered into one listen-through file (each loop plays for twelve
seconds, so that its join is heard once, a chapter mark at each). They are listed in sounds.json by hand, as the other
effects are: mill.sails, mill.stones and mill.wheel, not streamed, each with its reach as its attenuation_distance.

  sails   loop  8.000 s  reach 28  the four sails sweeping down past the tower, one every two seconds as they go round
                                   once in eight: a deep rush of air through the lattice and the cloth, rising and
                                   falling as each one passes, the cloth's edge fluttering, the air squeezed between the
                                   sail and the tower; the windshaft groaning in its neck bearing, the stocks creaking as
                                   the sails go over the top, the reef lines ticking on the bars
  stones  loop  6.000 s  reach 16  the millstones at work inside the mill, heard through its walls: the runner stone
                                   rumbling round on the bed stone twice a second, the grain crushed between them, the
                                   wooden cogs of the stone nut, and the damsel knocking the shoe four times a second to
                                   shake the grain down
  wheel   loop  8.000 s  reach 20  the watermill's wheel going round once in eight seconds: each of its sixteen paddles
                                   slapping into the stream, the water pouring off the paddles rising behind and dripping
                                   back, the stream churning round the wheel, its timbers creaking and the axle knocking
                                   in its bearing once a turn

Nothing is sampled: the parts are the cog's (make_ship_audio.py), on sfxkit.py. Air is noise through a band that moves with
the sail's speed; timber creaks are stick-slip friction ringing an oak body's modes; knocks are heavy oak's modes; water is
smacks, hollow cavity knocks and Minnaert bubbles; the grain is a cloud of tiny grains of grit.

The loops are built in a circle, as the cog's are (make_ship_audio.Ring): laid out over three periods that repeat each
other, run through their filters and rooms, and the middle period kept, so that the sample after the last is the first.
Each starts at a quiet rising zero crossing, and is read back after the encoder to check its length and its join (the step
across it against the usual step, the highs round it against the highs elsewhere). They are quiet, set by RMS: the sails
and the stones -30 dBFS, the wheel -28, their peaks held under -10; true peaks stay under -1 dBTP. All randomness is seeded:
a rerun gives the same files.
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
from make_ship_audio import (Ring, bubbles, creak_voice, drip, gurgle, hull_slap, knock, loop_start, oak,  # noqa: E402
                             path, periodic_phase, rn, seam_report, spread, strike, timber_modes, write_checked)

RECIPES = {}
MILL_DIR = "mill"

SAIL_TURN = 8.0  # seconds the sails take to go round once at work (MillBlockEntity.SAIL_SPEED)
SAILS = 4
STONE_TURNS = 2.0  # the runner stone's turns a second at work: twelve in the loop
DAMSEL_ARMS = 2  # the damsel knocks the shoe twice a turn of the stone: four times a second
NUT_STAVES = 8  # the stone nut's staves, meshing with the spur wheel's cogs: sixteen a second
WHEEL_TURN = 8.0  # seconds the wheel takes to go round once while the mill grinds (MillBlockEntity.WHEEL_WORK)
PADDLES = 16


# =========================================================================== registry and finishing
def recipe(name, loop, rms_db, ceiling_db=-10.0, reach=None):
    """one of the mills' loops, written as mill/<name>.ogg and played as the event mill.<name>: `loop` seconds long (the
    recipe is handed a Ring and gives back the three periods it laid out), its RMS at rms_db with its peaks held under
    ceiling_db; reach is how many blocks it carries in the game (its attenuation_distance in sounds.json)"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, loop=loop, rms_db=rms_db, ceiling_db=ceiling_db, reach=reach)
        return fn
    return deco


def render_loop(name):
    """a loop, finished: the middle period of the three the recipe laid out, high-passed, levelled to its RMS with its peaks
    held under the ceiling, and turned to start at a quiet zero crossing (the cog's render_loop)"""
    r = RECIPES[name]
    ring = Ring(r["loop"])
    x = hp(r["fn"](ring, rng_for(MILL_DIR + "/" + name)), 25.0)
    for _ in range(5):
        y = ring.middle(x)
        x = x * (db(r["rms_db"]) / (rms(y) + 1e-12))
        y = ring.middle(x)
        if np.max(np.abs(y)) <= db(r["ceiling_db"]) * 1.001:
            break
        x = limiter(x, ceiling=db(r["ceiling_db"]), lookahead=0.003, release=0.08)
    return loop_start(ring.middle(x))


# =========================================================================== small helpers
def wrap(ring, at):
    """a moment folded into the period"""
    return float(at) % ring.P


def ring_grains(ring, rng, rate, decay=0.0006, f_lo=700.0, f_hi=5000.0, size_var=0.7, kernels=5):
    """the cog's grain_cloud laid round the ring: tiny dry grains (grit, crushed grain), about `rate` a second (a number, or
    a curve over the three periods, of which the middle one is read), the same grains in every period, so that the cloud
    joins itself"""
    n = ring.n
    r = np.broadcast_to(np.asarray(rate, dtype=float), (ring.N,))[n:2 * n]
    idx = np.nonzero(rng.random(n) < r / SR)[0]
    sel = rng.integers(0, kernels, len(idx))
    mag = rng.standard_normal(len(idx)) * np.exp(size_var * rng.standard_normal(len(idx)))
    out = ring.new()
    k = max(8, ns(decay * 6))
    for j in range(kernels):
        kern = np.exp(-np.arange(k) / max(1, ns(decay))) * rng.standard_normal(k)
        imp = np.zeros(n)
        m = sel == j
        imp[idx[m]] = mag[m]
        out += signal.fftconvolve(ring.tile(imp), kern)[:ring.N]
    return bp(out, f_lo, min(f_hi, SR * 0.45))


def in_the_fields(x, wet=0.07, seed=3101):
    """out in the fields by a mill: a slap back off its wall and a short open tail"""
    return reverb(x, wet=wet, rt60=0.7, predelay=0.01, hf_damp=0.7, seed=seed, early_span=0.03)


def through_the_walls(x, wet=0.3, seed=3201, cut=1700.0):
    """the millstones heard from outside: the mill's stone floor and timbers giving them a short, dull room, and its walls
    taking the top off"""
    y = reverb(x, wet=wet, rt60=0.75, predelay=0.006, hf_damp=0.6, seed=seed, early_span=0.02)
    return lp(lp(y, cut, 2), cut * 1.7, 2)


# <<sails
# =========================================================================== the windmill's sails
def shaft_modes():
    """the windshaft's own voice, the same in every groan: one great oak shaft turning in its neck bearing"""
    return timber_modes(rng_for("mill", "shaft"), 65.0, 1800.0, 28, 0.15, 0.025, peaks=(140, 390))


def stock_modes():
    """the stocks' voice: the long oak arms the sails are hung on, lighter and higher than the shaft"""
    return timber_modes(rng_for("mill", "stock"), 140.0, 3200.0, 24, 0.09, 0.02, peaks=(310, 740))


def sail_pass(rng, size=1.0, dur=1.8):
    """one sail sweeping down past the tower: the lattice and the cloth pushing through the air (a deep, dull rush whose band
    climbs as the sail comes on and sinks as it goes, a little higher coming than going: a sail goes by at no more than a
    brisk walk, so it is a 'whum', not a hiss), the cloth's edge fluttering at its fastest, and the low push of the air
    squeezed between the sail and the tower as it passes"""
    rush = whoosh(dur, rng, peak=0.52, f_lo=90.0, f_hi=520.0, q=0.8, power=2.0, rise=1.6, fall=1.3, color=-2.5,
                  flutter_hz=9.0, flutter_depth=0.22, doppler=0.05)
    rush = lp(rush, 1100.0, 2)
    s = bell(dur, 0.52, 1.6, 1.3)
    edge = tvf(colored(dur, rng, -1.5), "bp", 650.0 + 900.0 * s, 1.5) * s ** 3.5 \
        * (0.7 + 0.3 * smooth_random(dur, rng, 26))
    whum = lp(brown(dur, rng), 180.0, 4) * bell(dur, 0.58, 3.0, 2.4) ** 2
    m = Mix(dur)
    m.add(rn(rush), 0.0, 1.0)
    m.add(rn(edge), 0.0, 0.1)
    m.add(rn(whum), 0.0, 0.5)
    return m.out() * size


def shaft_groan(rng, dur, top=70.0):
    """the windshaft groaning in its neck bearing as the weight of the sails comes round: a slow stick-slip that gathers
    into a rough groan sliding from 'oo' toward 'ah' and lets go"""
    rate = path([(0, 7), (0.25 * dur, top * 0.4), (0.55 * dur, top), (0.8 * dur, top * 0.6), (dur, 9)], dur) \
        * spread(dur, rng, 4, 0.08)
    amp = bell(dur, 0.5, 1.4, 1.6)
    vowel = [(path([(0, 250), (0.5 * dur, 410), (dur, 300)], dur), 4.0, 0.7)]
    return creak_voice(dur, rng, rate, amp, shaft_modes(), vowel=vowel, rub=0.1, unsteady=0.4)


def stock_creak(rng, dur):
    """a stock creaking as its sail goes over the top and the weight comes onto its other side: short, higher, drier"""
    rate = path([(0, 15), (0.3 * dur, 95), (0.65 * dur, 60), (dur, 12)], dur) * spread(dur, rng, 7, 0.1)
    amp = bell(dur, 0.35, 1.2, 1.8)
    return creak_voice(dur, rng, rate, amp, stock_modes(), rub=0.08, unsteady=0.3, bright=4200.0)


@recipe("sails", loop=SAIL_TURN, rms_db=-30.0, reach=28)
def sails(ring, rng):
    """the sails at work, one turn of them in the loop: four passes of a sail down past the tower (one a little heavier,
    its cloth set a touch slacker), the wind in the lattice under them, stirred up by each pass, the windshaft groaning
    once a turn and again more softly, three of the stocks creaking as their sails go over the top, and the reef lines
    ticking on the bars as a sail takes the wind"""
    P = ring.P
    every = P / SAILS
    starts = [wrap(ring, 0.15 + k * every + 0.04 * rng.standard_normal()) for k in range(SAILS)]
    passes = ring.new()
    for at, size in zip(starts, (1.0, 0.86, 1.12, 0.93)):
        ring.place(passes, sail_pass(rng, size), at)
    # each sail's pass peaks about a second after it starts: the stocks creak just after a sail has gone over the top, as
    # the one opposite it passes the tower
    stocks = ring.new()
    for k, g in ((0, 1.0), (1, 0.7), (3, 0.85)):
        dur = rng.uniform(0.45, 0.7)
        ring.place(stocks, stock_creak(rng, dur), wrap(ring, starts[k] + 1.25 + 0.05 * rng.standard_normal()), g)
    groans = ring.new()
    ring.place(groans, shaft_groan(rng, 1.7, 72.0), 2.9, 1.0)
    ring.place(groans, shaft_groan(rng, 1.1, 48.0), 6.6, 0.55)
    # the wind in the lattice, under it all, swelling as each sail passes (a pass peaks at 1.09 s and every two after)
    beat = periodic_phase(ring, np.full(ring.N, SAILS / P))
    wind = bp(ring.noise(rng, -3.0), 110.0, 750.0, 2) * (0.6 + 0.4 * (0.5 + 0.5 * np.cos(beat - np.pi * 1.09))) \
        * np.exp(0.25 * ring.curve(rng, 1, 6, 1.0) + 0.15 * ring.curve(rng, 8, 40, 1.0))
    ticks = ring.new()
    for at in starts:
        for _ in range(int(rng.integers(1, 3))):
            ring.place(ticks, oak(0.1, rng, rng.uniform(480.0, 720.0), t60=0.035, hard=0.5),
                       wrap(ring, at + rng.uniform(0.5, 1.4)), rng.uniform(0.4, 1.0))
    x = 1.0 * rn(passes) + 0.45 * rn(wind) + 0.42 * rn(groans) + 0.22 * rn(stocks) + 0.06 * rn(ticks)
    return lp(in_the_fields(x), 9000.0, 2)
# sails>>


# <<stones
# =========================================================================== the millstones
def damsel_knock(rng, arm):
    """the damsel's arm knocking the shoe: a hollow wooden tok of the little trough on its spring, the hopper's boards
    answering, and the grain shaken down off its lip in a short hiss"""
    f = (430.0 if arm == 0 else 385.0) * rng.uniform(0.98, 1.02)
    m = Mix(0.3)
    m.add(rn(oak(0.16, rng, f, t60=0.05, hard=0.9)), 0.0, 1.0)
    m.add(rn(strike(0.22, rng, [255.0, 590.0, 1130.0], [0.08, 0.05, 0.03], [1.0, 0.5, 0.25], lo=150, hi=2500, ex=0.003)),
          0.0, 0.5)
    pour = bp(white(0.14, rng), 1200.0, 6000.0) * bell(0.14, 0.25, 1.2, 1.6) * (0.5 + 0.5 * smooth_random(0.14, rng, 120))
    m.add(rn(pour), 0.012, 0.22)
    return m.out()


@recipe("stones", loop=6.0, rms_db=-30.0, reach=16)
def stones(ring, rng):
    """the millstones at work, heard from outside the mill: the runner stone rumbling round on the bed stone (a deep rumble
    that throbs with each of its turns, a stone never quite in balance), the grain crushed between them, the staves of the
    stone nut clicking through the spur wheel's cogs, and the damsel knocking the shoe, a heavy knock and a lighter one"""
    P = ring.P
    turn = periodic_phase(ring, np.full(ring.N, STONE_TURNS))
    throb = (0.72 + 0.28 * np.cos(turn)) * (0.88 + 0.12 * np.cos(2 * turn + 1.1))
    # the stone on the stone and the floor under it: a rough rumble with the body of the stone case and the boards, driven
    # a little so that it is heard on a small speaker too
    body = resonators(bp(ring.noise(rng, -4.0), 50.0, 500.0, 2), [72.0, 104.0, 151.0, 223.0, 318.0],
                      [0.22, 0.18, 0.14, 0.1, 0.07], [1.0, 0.8, 0.6, 0.4, 0.25])
    rumble = drive(0.4 * rn(body + 0.6 * rn(bp(ring.noise(rng, -3.0), 90.0, 420.0, 2))), 1.6) * throb \
        * np.exp(0.12 * ring.curve(rng, 2, 12, 1.0))
    grit = ring_grains(ring, rng, 2600.0 * (0.8 + 0.2 * np.cos(turn + 0.6)), decay=0.0005, f_lo=500.0, f_hi=4500.0)
    grind = bp(ring.noise(rng, -2.0), 250.0, 1500.0, 2) * (0.75 + 0.25 * np.cos(turn + 0.6))
    cogs = ring.new()
    per = 1.0 / (STONE_TURNS * NUT_STAVES)
    staves = rng.uniform(0.7, 1.0, NUT_STAVES)  # a hand-made nut: each stave meets its cog a little differently
    for i in range(int(round(P / per))):
        ev = oak(0.07, rng, 560.0 * rng.uniform(0.96, 1.04), t60=0.03, hard=0.6)
        ring.place(cogs, ev, wrap(ring, i * per + 0.0012 * rng.standard_normal()), staves[i % NUT_STAVES])
    damsel = ring.new()
    per = 1.0 / (STONE_TURNS * DAMSEL_ARMS)
    for i in range(int(round(P / per))):
        arm = i % DAMSEL_ARMS
        ring.place(damsel, damsel_knock(rng, arm), wrap(ring, 0.02 + i * per + 0.003 * rng.standard_normal()),
                   (1.0 if arm == 0 else 0.72) * rng.uniform(0.9, 1.05))
    x = 1.0 * rn(rumble) + 0.35 * rn(grind) + 0.3 * rn(grit) + 0.25 * rn(cogs) + 0.6 * rn(damsel)
    return through_the_walls(x)
# stones>>


# <<wheel
# =========================================================================== the watermill's wheel
def wheel_modes():
    """the wheel's own voice: its oak spokes and shrouds working on the axle"""
    return timber_modes(rng_for("mill", "wheel"), 90.0, 2400.0, 26, 0.11, 0.02, peaks=(190, 480))


def paddle_pour(rng, dur=0.9):
    """the water pouring off a paddle as it rises out of the stream behind the wheel: a sheet of it falling back, hissing and
    fizzing as it lands, thinning to a trickle"""
    t = tvec(dur)
    env = (1 - np.exp(-t / 0.03)) * np.exp(-t / 0.28)
    sheet = tvf(pink(dur, rng), "bp", 900.0 + 1400.0 * np.exp(-t / 0.2), 0.7) * env
    fizz = bubbles(dur, rng, 900.0 * env + 10.0, 0.5, 2.5, beta=2.0, level=1.0, weight=0.4)
    m = Mix(dur)
    m.add(rn(sheet), 0.0, 1.0)
    m.add(rn(fizz), 0.0, 0.5)
    return m.out()


@recipe("wheel", loop=WHEEL_TURN, rms_db=-28.0, reach=20)
def wheel(ring, rng):
    """the wheel at work, one turn of it in the loop: each of the sixteen paddles slapping into the stream (the wet smack of
    the board, the hollow knock of the air it traps, bubbles, the board's own tone), the water pouring off the paddles rising
    behind and dripping from the ones going over the top, the stream churning round the wheel and glugging under it, the
    wheel's timbers creaking twice a turn and the axle knocking in its bearing once"""
    P = ring.P
    every = P / PADDLES
    slaps = ring.new()
    pours = ring.new()
    for k in range(PADDLES):
        at = k * every + 0.012 * rng.standard_normal()
        size = rng.uniform(0.5, 0.75)
        # (how much air a paddle traps as it goes in, and so how hollow its slap, is never the same twice)
        ring.place(slaps, hull_slap(rng, size, 1.0, cav=rng.uniform(0.15, 0.6), plank=0.7), wrap(ring, at),
                   rng.uniform(0.7, 1.0))
        ring.place(pours, paddle_pour(rng, rng.uniform(0.7, 1.0)), wrap(ring, at + 0.31 * P), rng.uniform(0.6, 1.0))
    beat = periodic_phase(ring, np.full(ring.N, PADDLES / P))
    churn = bp(ring.noise(rng, -3.0), 180.0, 2400.0, 2) * (0.8 + 0.2 * np.cos(beat)) * np.exp(0.25 * ring.curve(rng, 2, 16, 1.0))
    drips = ring.new()
    for at in ring.times(rng, 5.0, shape=1.5):
        ring.place(drips, drip(rng), at, rng.uniform(0.25, 1.0))
    glugs = ring.new()
    for at in ring.times(rng, 0.9, shape=2.0):
        ring.place(glugs, gurgle(rng, rng.uniform(0.6, 1.0)), at, rng.uniform(0.4, 1.0))
    timbers = ring.new()
    for at, dur, g in ((1.7, 0.8, 1.0), (5.9, 0.6, 0.6)):
        rate = path([(0, 12), (0.35 * dur, 70), (0.7 * dur, 40), (dur, 10)], dur) * spread(dur, rng, 6, 0.1)
        ring.place(timbers, creak_voice(dur, rng, rate, bell(dur, 0.4, 1.3, 1.6), wheel_modes(), rub=0.1), at, g)
    axle = ring.new()
    ring.place(axle, knock(rng, 1.25, 0.7, 0.7, 0.5, 0.55), 4.35, 1.0)
    x = 1.0 * rn(slaps) + 0.5 * rn(pours) + 0.45 * rn(churn) + 0.22 * rn(drips) + 0.2 * rn(glugs) + 0.22 * rn(timbers) \
        + 0.2 * rn(axle)
    return lp(in_the_fields(x, 0.1, 3301), 9500.0, 2)
# wheel>>


# =========================================================================== main
def build(assets, names, wav_dir=None):
    for name in names:
        t0 = time.time()
        x = render_loop(name)
        out = f"{MILL_DIR}/{name}"
        p = os.path.join(assets, "sounds", out + ".ogg")
        x = write_checked(p, x)
        if wav_dir:
            write_wav(os.path.join(wav_dir, out + ".wav"), x)
        d_len, step, seam = seam_report(p, len(x))
        print(f"{out:14s} {len(x) / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  rms {to_db(rms(x)):6.1f}  "
              f"{time.time() - t0:5.2f}s  loop {len(x)} samples (file {d_len:+d}), join {step:4.1f}x a step, "
              f"{seam:4.2f}x the usual highs", flush=True)


LOOP_SHOWN = 12.0  # how long each loop plays in the listen-through (so that its join is heard once)
GAP = 0.7


def listen_through(assets, names, path_):
    """one MP3 to listen to: each loop that was just written, played for twelve seconds round its join, read back from the
    files, so it is what the game will play; a chapter mark at each"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        x = read_audio(os.path.join(assets, "sounds", MILL_DIR, name + ".ogg"))[0]
        x = fade(np.tile(x, int(np.ceil(LOOP_SHOWN * SR / len(x))))[:ns(LOOP_SHOWN)], 0.0, 0.3)
        marks.append((at, at + len(x) / SR + GAP, name))
        pieces += [x, np.zeros(ns(GAP))]
        at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark mill sounds\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
        shutil.copyfile(mp3, path_)
    print(f"{path_}  {at:.1f}s, {len(marks)} loops")


def main():
    ap = argparse.ArgumentParser(description="Guildmark's mills: the sounds of the sails, the millstones and the wheel")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated names: sails, stones, wheel")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    a = ap.parse_args()
    names = list(RECIPES)
    if a.only:
        names = [s.strip().removeprefix("mill.") for s in a.only.split(",") if s.strip()]
        for s in names:
            if s not in RECIPES:
                raise SystemExit(f"unknown: {s} (sounds: {', '.join(RECIPES)})")
    build(a.assets, names, a.wav)
    if a.mp3:
        listen_through(a.assets, names, a.mp3)


if __name__ == "__main__":
    main()
