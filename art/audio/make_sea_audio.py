"""Guildmark's sea monsters: every sound of the gnashfins (the pike-toothed hunters that run in packs, leap aboard and gnaw at
the hull) and of the Maelgrasp (the thing under the open water: its omens, its arms on and over the ship, its head), and its
battle theme, made from nothing.

usage (from the repo root):
    python3 -B art/audio/make_sea_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,music] [--mp3 FILE]
        [--fragments DIR] [--work DIR] [--sf2 FluidR3_GM.sf2] [--theme-mp3 FILE]

Writes the sounds as mono 44.1 kHz Ogg Vorbis to <assets>/sounds/sea/<event><n>.ogg (the dragon's naming; the cog's has an
underscore before the number), or <event>.ogg when an event has one file: 31 events, 55 files. The theme follows, from
make_sea_music.py, stereo, to <assets>/sounds/music/maelgrasp.ogg, where the other battle themes are. --only takes event
names (gnashfin_snap, maelgrasp_roar, or as the game names them, sea.maelgrasp.roar) and/or music, --wav also writes WAV
copies (the theme's too), --mp3 joins what was rendered into one listen-through file (every file in order, a gap between, a
chapter mark at each), --theme-mp3 writes the theme as an MP3 to listen to, --work keeps the theme's per-stem MIDI and WAV
renders, --fragments writes sea_sounds.json (the sounds.json entries of the events, keyed sea.<creature>.<event>, and
music.maelgrasp, streamed) and sea_subtitles.json (their subtitles) for merging: the real sounds.json and en_us.json are not
touched. The files are listed in sounds.json by hand, as the other effects' are.

The gnashfins (sea.gnashfin.<event>)
  fin          3 files  0.9-1.3 s     a dorsal fin cutting the surface as it circles: the thin hiss of water parted along
                                      the fin's edge, swelling and brightening as it comes close, over a wash of water
                                      thrown aside and a fizz of bubbles, drops falling back (a quick pass, a slower one
                                      that curves away with a second cut, two weaving cuts with a small hollow plip between)
  leap         2 files  0.95-1.05 s   the fish bursting up out of the sea: the tail's smack, the body hauled out in a rush
                                      that streams away, the sheet of water torn off it, spray, the swish of the arc, drops
                                      pattering back (an explosive leap; a flat skimming one with a second kick off a crest)
  dive         2 files  0.82-0.85 s   knifing back in head first: the crack of parted water, the bubbles dragged down behind
                                      it, the hollow closing over it with a bloop, the hiss going dull as it drops away
                                      (clean; steeper after a long leap, with a crown of spray first and a second, deeper
                                      bloop)
  snap         3 files  0.35-0.45 s   the jaws shutting, a bony clack: a spray of needle tips meeting over the bright hollow
                                      knock of the bone behind them, a breath shut out between the teeth (one hard clack; a
                                      clack and a rebound; a clack and the rows of teeth settling)
  bite         2 files  0.55-0.9 s    teeth into flesh: a rush of puncturing points, the meaty thud, the wound squelching
                                      shut round the teeth, gristle crunching, the wet suck of the release (a quick bite and
                                      let go; one that holds while the head worries it, a rhythmic wet tearing)
  gnaw         3 files  1.2-1.6 s     teeth at wet timber at the waterline, half drowned and heard through the planks:
                                      stick-slip rasping on the hull's own modes, a gurgle round the snout (a long drawn
                                      rasp with two tugs; a quick chatter of nibbles; a heavy grind that ends in two hollow
                                      knocks and a slap)
  flop         3 files  1.4-1.5 s     a heavy wet fish slapping on deck boards, the planks knocking back, the deck booming,
                                      water squirted out and pattering (three flops that weaken; a flurry and one big heave;
                                      one huge flop, a long wet slither and two weak thumps)
  hurt         2 files  0.7-0.85 s    a gurgling hiss: air and water forced out of the gills, falling from bright to dull
                                      over a gargle of bubbles (a sharp hiss with a jaw clack; two bursts and a rattle of
                                      gill plates)
  death        1 file   1.89 s        a rattling gasp: a last wet breath through the gills, the rattle of bone and gill
                                      plate over a gargle, its rate dropping as it fails, a long breath going out, the
                                      tail's last two slaps
The Maelgrasp, its omens and the sea (sea.maelgrasp.<event>)
  omen         3 files  4.6-6.8 s     a vast groan from far below, heard through water and hull, deep, distant and wrong:
                                      the voice heaving in two swells with the mouth opening and closing on 'oo' and 'ah', a
                                      crack in it, ancient timber creaking in the lull (two voices a tritone apart, bubbles
                                      climbing between them; the voice coming apart into a slow ratchet of creaks, a shudder
                                      of the sea, a last sagging drop)
  bubbles      2 files  3.4-3.9 s     a great upwelling round the hull as something stirs: bubbles of every size, from fizz
                                      to the slow bloops of a bubble as big as a head, a patch of sea boiling, the hull
                                      knocking and creaking (one long swell, building and dying away; three belches, each
                                      bigger)
  surge        1 file   2.7 s         the sea heaving as something huge comes up under it: the weight of the water lifting
                                      (a pressure in the chest, displaced water streaming off the swelling back of it), the
                                      cog lifted and slapped and creaking in every timber, the crest breaking over and
                                      slumping back
The Maelgrasp's arms
  burst        2 files  1.9-2.3 s     an arm tearing up out of the sea with the water streaming off it, suckers popping as
                                      it flexes (one arm beside the hull; a bigger, slower eruption of two, the column of
                                      water falling back)
  writhe       3 files  1.8-2.3 s     an idle arm: wet slither and the pop of suckers (a slow coil and a long peeling of
                                      suckers; a run of pops over a quiet slither; a heavier thrash, the tip slapping the
                                      boards twice)
  rear         1 file   2.5 s         an arm rearing high over the deck, water pouring off it in a sheet that thins, the air
                                      parted by the swelling mass of it, the long wet squeak and rubbery ratchet of muscle
                                      taking the weight, a held creak at the top, the rain of drops falling back
  slam         2 files  1.8-2.1 s     an arm as thick as a mast slamming the deck: the slap and the boom, a run of timber
                                      cracks, water and splinters thrown up and raining down, the hull groaning, the rigging
                                      ringing (flat across the boards; the planks splitting in a long rip along the grain,
                                      then the wet drag clear)
  sweep        1 file   1.7 s         an arm swept across the deck: the weight of air pushed aside, its wet skin dragging
                                      over the planks, a long scrape of timber as it takes the cleats, the rail and a barrel
                                      or two along
  grab         1 file   1.3 s         suckers clamping down: the heavy wet slap across the rail, a cascade of suckers taking
                                      hold one after another, the flesh squeezing tight round the timber with a rubbery
                                      squeak
  squeeze      1 file   2.0 s         an arm tightening round the ship: the planking taking the strain, a rubbery ratchet of
                                      muscle, long wet squeals of skin on tarred oak, timber fibres giving in small pops,
                                      water squeezed out of the flesh
  hurl         1 file   1.6 s         a whip-crack fling: the suckers letting go in a run of pops, the groan of muscle
                                      gathering, the whoosh climbing in pitch and weight, the crack of the tip, the rush
                                      falling away
  arm_hurt     2 files  1.0-1.2 s     a shudder hissing through the water: the blow landing in flesh, the arm trembling (a
                                      hiss falling from bright to dull, chopped by an uneven quiver, a soft thump of muscle
                                      at every shake), bubbles fizzing out of it, a wet squeak (a long shiver and slump; a
                                      sharp flinch and a whip back)
  sever        2 files  1.4-1.7 s     a wet chop and a gush: the blade through the arm, gristle crunching, the halves
                                      parting, blood and sea water pumping out with the heart, the cut length slapping and
                                      twitching, the stump recoiling with a squeal (one blow; two, the first not enough, the
                                      end rolling into the sea)
  sink         1 file   2.8 s         an arm sliding back under, gurgling: the length of it dragged over the rail, the last
                                      suckers letting go, the water closing over it with a bloop, bubbles that slow and fall
                                      in pitch, the sound going dull as the sea takes it
  crush        3 files  2.3-3.1 s     the cog's timbers groaning under enormous strain, in the cog's own voice a long way
                                      lower and slower (a groan that climbs, ratcheting faster, and a huge crack of the
                                      hull; two surges with a line parting and water jetting through the seams; the keel
                                      drawn out, a plank ripping along the grain, a last shudder)
The Maelgrasp's head
  rise         1 file   4.6 s         the head surfacing: the sea swelling and boiling over it, the hull lifted and slapped,
                                      the break of the surface (a great smack, a wall of water, the hollow punched under
                                      it), then the cascade of a small lake pouring off a head the size of a house, and a
                                      huge, wet breath
  roar         2 files  3.9-4.8 s     the bellow of the thing under the sea, wet and cavernous: a deep voice driven into a
                                      gargle of great bubbles with an octave of chest under it, the beak clacking beneath
                                      it, a gush of water as the mouth opens, the sea throwing it back (one long bellow that
                                      climbs and falls away in a rattle; a short bark and, after a breath, a longer, higher
                                      one that tears)
  blink        1 file   0.7 s         a wet lid closing over an eye the size of a cartwheel: skin gliding over a slick
                                      surface, a small tacky squelch as the edges meet, a soft deep pressure
  bite         1 file   1.4 s         the beak crunching through the rail: hard keratin closing, a deep knock, a run of
                                      cracks and the long tearing rip of the grain, splinters, the wet munch of the mouth,
                                      the rail falling in
  ink          1 file   1.9 s         ink spewing from a siphon: three heavy pulses of thick fluid through a tube, fat
                                      blorps on the water, a bloom of it billowing in the sea, thick bubbles popping slowly
  dive         1 file   3.4 s         the head plunging back under: the huge smack, the wall of water falling back, the
                                      hollow it opens roaring and slamming shut with deep gulps, the swell thrown at the
                                      hull, a vortex gargling away downward, the groan of the creature as it sinks, the
                                      surface settling
  death        1 file   9.6 s         a long dying groan fading into bubbles: the voice sagging from a bellow through the
                                      whole of its range into a rumble under hearing, breaking and stumbling as the throat
                                      fails, the beak clacking slower, a long breath going out, the body slumping into the
                                      sea, the hull working, a stream of bubbles that thins and slows and grows, the last
                                      big bloops, the surface going still

Nothing is sampled. Each event is one `@recipe` built with sfxkit.py and the cog's own parts (make_ship_audio.py), so that
the sea monsters sit in the same sea as the ship. The water is Minnaert bubbles (a bubble of radius R mm rings at about
3260/R Hz) in swarms with power-law radii, smacks, hollow cavity knocks, the slaps of a hull and sheets of pouring water,
let down at the top as the cog's water is (a gentle shelf from 3 kHz and a low-pass); a bubble as big as a head would ring
at 30 Hz, which no small speaker plays, so the big ones here are driven into a bloop with overtones and a body. The oak is
the cog's oak: the gnashfins' gnawing and the Maelgrasp's crushing are the same stick-slip trains of slips ringing the same
kind of hull modes, the gnawing half drowned, the crushing lower, slower and longer. The gnashfin is a pike: its jaws are a
spray of needle tips (tiny bright ticks) over the hollow knock of thin bone plates, its flesh squelches (the wet lips of a
pocket fluttering as it closes, a note that climbs as it dies) and its gills hiss through a gargle of bubbles. The
Maelgrasp's voice comes from a throat as big as a hold: a living mass of harmonics on a pitch down where hearing runs out,
each partial wandering and swelling at its own rate, a second voice a semitone over the first (the interval of its theme)
grinding against it, a rough pulse voice and the breath through the same slow resonances (the mouth sliding from 'oo' to
'ah'), broken up by a roughness and swimming in the water; heard from below, it goes through a long, dark, slow hall. Its
arms are wet muscle and suckers: squelches of a pocket of water closing, the 'tpk' of a cup snapping open, rubbery squeals
of skin dragging on tarred oak, ratchets of muscle taking a load. The heaviest blows are driven before the limiter, so that
their bodies, not only their peaks, carry the loudness.

Levels. The one-shots peak at -3 dBFS (lower where a sound reaches its loudness first: the fins, the hurts, the idle arm,
the eye) and are set, as the cog's are, by the loudest 100 ms (K-weighted LUFS): -10.5 to -11.5 for the blows and the voice
of the boss (the slams, the rise, the roars, the head's bite and dive, the arm bursting), as loud as the dragon's and the
troll's; -12 to -14 for the great water and the arms in motion (the surge, the sweep, the sever, the crush, the hurl, the
death); -14.5 to -17.5 for the gnashfins' leaps, dives, bites and flops and the arms' smaller sounds (the omens, the
bubbles, the rear, the grab, the squeeze, the arm's hurt, the sink, the ink); -17 to -20.7 for the quietest, the often-heard
fin and gnaw of the gnashfin, the writhes, the eye, and the snap, which is too short to be louder. A sharp sound is
soft-limited just enough to get there (never by more than limit_db), a louder one turned down. Every file starts from zero
and fades out, is high-passed at 36 Hz (a little lower for the death, a little higher and steeper for the deepest sounds,
which are felt rather than heard on a small speaker and would only take up the peaks), true peaks stay under -1 dBTP, and
every random number is seeded, so a rerun gives the same files. Every sound carries as far as its `reach` says (its
attenuation_distance in sea_sounds.json): 24 blocks for the gnashfins' small sounds up to 128 for the roar and the death, as
far as the ranges SeaRegistry fixes for the events that have one.

The theme is make_sea_music.py's: F sharp minor in a heaving 6/8, 74 bars, 2:28, three notes for the creature's call, built
exactly as one turn of a loop (the game plays it as a boss track that starts again the moment it ends).
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403
from make_ship_audio import (Bus, at_sea, beats, cavity, creak_voice, drip, grain_cloud, hann_burst,  # noqa: E402
                             hull_modes, hull_slap, knock, lead_in, nrm, oak, path, pop, rise_to, rn, saturate,
                             short_lufs, slosh, smack, splash, spread, squeal, strike, swish, timber_modes,
                             write_checked)
from make_ship_audio import bubble as ship_bubble  # noqa: E402

RECIPES = {}
SEA_DIR = "sea"


# =========================================================================== registry and finishing
def recipe(name, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, loud=None, limit_db=6.0,
           subtitle="", reach=None, hp_hz=36.0, sat=0.0, hp_order=2):
    """one sound event, written as sea/<name>.ogg (one variant) or sea/<name><n>.ogg for n = 1..variants (as the dragon's)."""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db, loud=loud, limit_db=limit_db, subtitle=subtitle, reach=reach, hp_hz=hp_hz, sat=sat, hp_order=hp_order)
        return fn
    return deco


def sound_path(name, v, variants):
    return f"{SEA_DIR}/{name}{v + 1}" if variants > 1 else f"{SEA_DIR}/{name}"


def event_key(name):
    """'gnashfin_fin' -> 'sea.gnashfin.fin', 'maelgrasp_arm_hurt' -> 'sea.maelgrasp.arm_hurt'"""
    a, b = name.split("_", 1)
    return f"sea.{a}.{b}"


def render_effect(name, v):
    r = RECIPES[name]
    x = lead_in(r["fn"](v, rng_for("sea/" + name, v)))
    if r["hp_hz"]:
        x = hp(x, r["hp_hz"], r["hp_order"])  # (nothing under the lowest a small speaker plays: it only takes up the peaks)
    if r["sat"]:
        x = drive(x / (np.max(np.abs(x)) + 1e-12), r["sat"])  # (a blow is driven into the air: fewer peaks over its body)
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
    target = r["loud"][v] if isinstance(r["loud"], (list, tuple)) else r["loud"]

    def finish(y, peak_db=r["peak_db"]):
        y = finalize(y, peak_db=peak_db, fade_out=r["fade_out"], max_len=ml, tail_db=r["tail_db"])
        k = min(len(y), ns(0.001))
        y[:k] *= np.sin(np.linspace(0.0, np.pi / 2, k, endpoint=False)) ** 2
        return y

    y = finish(x)
    if target is None:
        return y
    now = short_lufs(y)
    if now >= target:
        return finish(x, r["peak_db"] - (now - target))
    x = x / (np.max(np.abs(x)) + 1e-12)
    lo, hi = db(-r["limit_db"]), 1.0
    for _ in range(12):
        c = float(np.sqrt(lo * hi))
        y = finish(limiter(x, c, 0.002, 0.05))
        if short_lufs(y) < target:
            hi = c
        else:
            lo = c
    return finish(limiter(x, float(np.sqrt(lo * hi)), 0.002, 0.05))


# =========================================================================== build
def build(assets, names, wav_dir=None):
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            t0 = time.time()
            x = render_effect(name, v)
            out = sound_path(name, v, r["variants"])
            p = os.path.join(assets, "sounds", out + ".ogg")
            x = write_checked(p, x)
            if wav_dir:
                write_wav(os.path.join(wav_dir, out + ".wav"), x)
            print(f"{out:26s} {len(x) / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  s100 {short_lufs(x):6.1f}  "
                  f"{time.time() - t0:5.2f}s", flush=True)


# =========================================================================== small helpers
def sweep_band(x, f_curve, q=1.0):
    """x through a band-pass whose centre follows f_curve (Hz, one per sample)"""
    return tvf(x, "bp", f_curve, q)


def wander(dur, rng, rate, depth):
    return spread(dur, rng, rate, depth)


def lurch(dur, rng, rate, depth, sharp=1.0):
    """a level that is never steady: 1 down to 1 - depth, broken up about `rate` times a second"""
    return 1 - depth * smooth_random(dur, rng, rate) ** sharp


def events(dur, rng, rate, start=0.0, shape=1.0):
    """moments (seconds) of events at about `rate` a second (a number or a curve over the time), a little more even than
    chance (shape 1 is chance, bigger is more regular)"""
    n = ns(dur)
    r = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    out, t = [], start
    while t < dur:
        i = min(n - 1, ns(t))
        out.append(t)
        t += rng.gamma(shape, 1.0 / (shape * max(r[i], 1e-3)))
    return np.array(out)


# =========================================================================== bubbles that can be heard
def bubble(r_mm, rng, rise=None, max_dur=0.35):
    """one air bubble ringing in water, the cog's (Minnaert's) with one change: a big one is given overtones. The note of a
    bubble a hand across is a sine down near the floor of hearing (90 mm sounds at 36 Hz), which no small speaker plays at
    all; the surface it rings in is not a perfect spring, so its note is driven (more the lower it is) into a deep 'bloop' with a
    body to it, and its ring is let go gently, not cut off"""
    b = ship_bubble(r_mm, rng, rise=rise, max_dur=max_dur)
    f0 = 3260.0 / r_mm
    amt = float(np.clip((150.0 - f0) / 100.0, 0.0, 1.0))  # nothing above 150 Hz, all of it under 50
    k = len(b) // 5
    if k > 8:
        b = b.copy()
        b[-k:] *= 0.5 + 0.5 * np.cos(np.linspace(0.0, np.pi, k))
    if amt <= 0.0:
        return b
    pk = float(np.max(np.abs(b))) + 1e-12
    return (1 - amt) * b + amt * drive(b / pk, 1.0 + 2.0 * amt) * pk


def bubbles(dur, rng, rate, r_lo, r_hi, beta=1.8, level=1.0, weight=0.5, rise=None, keep_tail=False):
    """a swarm of bubbles forming and rising, `rate` of them a second (a number or a curve over the time): radii from a power
    law between r_lo and r_hi mm (many small ones, few big ones), louder the bigger they are. The cog's, ringing the bubbles above"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    idx = np.nonzero(rng.random(n) < rate / SR)[0]
    a = 1 - beta
    rs = (r_lo ** a + rng.random(len(idx)) * (r_hi ** a - r_lo ** a)) ** (1 / a)
    out = np.zeros(n + ns(0.4))
    for i, r in zip(idx, rs):
        b = bubble(r, rng, rise=rise) * (r / 3.0) ** weight * np.exp(0.3 * rng.standard_normal())
        out[i:i + len(b)] += b
    return level * (out if keep_tail else out[:n])


import make_ship_audio as _ship  # noqa: E402

_ship.bubble = bubble  # (the cog's splash() and its other big bloops ring through this one too, here)


# =========================================================================== water
def spray(dur, rng, env, lo=1400.0, hi=9000.0, rate=1400.0, decay=0.0005):
    """drops thrown up and falling back: a thin sizzle of the finest, and a shower of little hard ticks as thick as env (0..1,
    a curve over the time) says"""
    n = ns(dur)
    e = np.broadcast_to(np.asarray(env, dtype=float), (n,))
    fine = bp(colored(dur, rng, -1.0), lo, hi) * e ** 1.5
    ticks = grain_cloud(dur, rng, rate * e + 3, decay=decay, f_lo=lo, f_hi=hi, size_var=0.9)
    return rn(fine) * 0.6 + rn(ticks) * 0.8


def pour(dur, rng, flow, lo=450.0, hi=8000.0, drops=1.0, glugs=1.0, size=1.0, mix=(1.0, 0.5, 0.4)):
    """water streaming and pouring off something big: flow (0..1, a curve over the time) is how much of it. The sheet of it
    (a broad rush, ruffled in three bands each by itself), the thousand small drops and bubbles it makes, and the bigger glugs
    of it going back into the sea (size: how big they are)"""
    n = ns(dur)
    fl = np.clip(np.broadcast_to(np.asarray(flow, dtype=float), (n,)), 0.0, 1.5)
    sheet = np.zeros(n)
    for a, b, g in [(lo, lo * 2.3, 0.7), (lo * 2.3, lo * 5.2, 1.0), (lo * 5.2, hi, 0.6)]:
        band = bp(colored(dur, rng, -1.5), a, min(b, hi * 0.98))
        mod = np.exp(0.5 * (2 * smooth_random(dur, rng, 50) - 1) + 0.3 * (2 * smooth_random(dur, rng, 8) - 1))
        sheet += g * rn(band) * mod
    sheet = rn(sheet * fl ** 1.3)
    pings = rn(bubbles(dur, rng, 1500 * drops * fl ** 1.6 + 5, 0.45, 2.6, beta=2.0, level=1.0, weight=0.4))
    glug = rn(bubbles(dur, rng, 30 * glugs * fl ** 1.4 + 0.3, 3.0 * size ** 0.5, 13.0 * size ** 0.6, beta=1.6, level=1.0,
                      weight=0.6, rise=0.3))
    return mix[0] * sheet + mix[1] * pings + mix[2] * glug


def hush(x, shelf=-6.0, cut=7500.0):
    """water is not white: the top of a sound let down the way the cog's own water is (a gentle shelf from 3 kHz and a
    low-pass), so that the sea monsters sit in the same sea as the ship"""
    return lp(eq(x, "highshelf", 3000, 0.7, shelf), cut, 2)


def sea_open(x, wet=0.12, rt60=1.1, seed=1901):
    """out on open water with the ship near: the slap back off the hull and the swell, a darker and longer tail than at_sea()"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.02, hf_damp=0.8, seed=seed, early_span=0.06)


# =========================================================================== flesh, bone and teeth
def squelch(rng, size=1.0, dur=0.24, up=1.3):
    """wet flesh squeezed and let go: the air forced out of a pocket as it closes, a rough, stuttering flutter of the wet lips
    of it (an irregular pulse through the pocket's resonances, its note climbing as it dies and shaking as it goes), a swept
    band of noise slurring under it, small bubbles spitting in it (size: how big the pocket is; bigger is lower and slower)"""
    d = dur * size ** 0.4
    n = ns(d)
    t = tvec(d)
    f0 = rng.uniform(130, 330) / size ** 0.6
    f = f0 * (1 + up * (1 - np.exp(-t / (0.4 * d))))
    f = f * (1 + 0.06 * (2 * smooth_random(d, rng, 40) - 1))
    env = np.clip(t / 0.004, 0, 1) * np.exp(-t / (0.3 * d))
    src = glottal(d, f, rng, jitter=0.1, shimmer=0.45, subharm=0.35, open_q=0.4, breath=0.7, fry=0.12)
    flap = (tvf(src, "bp", f * 2.4, 1.3) + 0.6 * tvf(src, "bp", f * 4.6, 1.0)) * (0.5 + 0.5 * smooth_random(d, rng, 55))
    tone = np.sin(phase_from_freq(f, n))
    slur = tvf(white(d, rng), "bp", f * 1.7, 3.0) * (1 - 0.5 * smooth_random(d, rng, 70))
    spit = bubbles(d, rng, 260 * np.exp(-t / (0.3 * d)) + 10, 0.8, 4.0 / size ** 0.3, beta=1.9, level=1.0, weight=0.4)
    y = 0.85 * nrm(flap) * env + 0.15 * nrm(tone) * env + 0.8 * nrm(slur) * env + 0.5 * nrm(spit) * env ** 0.5
    return y * np.clip(t / 0.002, 0, 1) * np.clip((d - t) / 0.03, 0, 1)


def sucker_pop(rng, size=1.0, hard=1.0):
    """one sucker letting go: the cup of air snapped open, a 'tpk' (a note that climbs as the pocket fills, a click, a
    breath of wet after it)"""
    f = rng.uniform(260, 760) / size ** 0.7
    L = 0.07 * size ** 0.5
    t = tvec(L)
    fr = f * (1 + 0.55 * (1 - np.exp(-t / 0.006)))
    body = np.sin(phase_from_freq(fr, len(t))) * np.exp(-t / (0.011 * size ** 0.5)) * np.clip(t / 0.0006, 0, 1)
    body += 0.3 * np.sin(phase_from_freq(fr * 2.07, len(t)) + 0.6) * np.exp(-t / (0.006 * size ** 0.5))
    click = hp(white(L, rng), 1500) * np.exp(-t / 0.0012) * np.clip(t / 0.0002, 0, 1)
    tail = tvf(white(L, rng), "bp", fr * 2.2, 2.0) * np.exp(-t / 0.012)
    return (nrm(body) + 0.35 * hard * nrm(click) + 0.25 * nrm(tail)) * np.clip((L - t) / 0.02, 0, 1)


def jaw_clack(rng, size=1.0, hard=1.0, wet=0.25):
    """the jaws of a pike meeting: the needle tips touching (a spray of tiny bright ticks), the bone behind them answering
    with a hollow, bright knock (a few modes of thin bone plates, not wood) that rings a moment in the skull, a flat splat of
    water squeezed out between the teeth, and a wet tick of spit"""
    k = size ** -0.3 * rng.uniform(0.96, 1.04)
    m = Bus(0.25)
    for _ in range(int(rng.integers(6, 11))):
        f = rng.uniform(3000, 8000)
        m.add(strike(0.02, rng, [f, f * 1.93], [0.004, 0.0025], [1, 0.4], lo=2000, hi=11000, ex=0.0004),
              rng.exponential(0.003), rng.uniform(0.1, 0.3) * hard, end_fade=0.002)
    m.add(strike(0.22, rng, np.array([680, 1130, 1790, 2720, 3900]) * k, [0.06, 0.045, 0.03, 0.02, 0.012],
                 [1, 0.85, 0.6, 0.4, 0.25], lo=300, hi=7000, ex=0.0012), 0.0, 1.0)
    m.add(oak(0.12, rng, 360 * k, t60=0.045, hard=1.0, lo=250), 0.0, 0.4 * hard)
    m.add(hann_burst(0.0015, rng, 1500, 8000), 0.0, 0.22 * hard)
    st = tvec(0.05)
    m.add(bp(white(0.05, rng), 350, 2600) * np.exp(-st / 0.012) * np.clip(st / 0.0006, 0, 1), 0.001, 0.45 * wet * 2)
    if wet:
        m.add(nrm(cavity(rng, f=rng.uniform(420, 800), glide=0.3, tau=0.012)), 0.004, 0.6 * wet)
    return m.out()


def body_slap(rng, size=1.0, hard=1.0, dur=0.5):
    """a heavy wet fish landing flat on boards: the slap (a flat burst of wet noise and a soft low pad under it), the planks
    answering, the deck booming a moment over the hollow under it, water squirted out and pattering"""
    t = tvec(dur)
    m = Bus(dur)
    slap = bp(white(dur, rng), 220, 3000) * np.exp(-t / (0.022 * size ** 0.5)) * np.clip(t / 0.0008, 0, 1)
    m.add(slap, 0.0, 0.9 * hard)
    pad = bp(white(dur, rng), 80, 520) * np.exp(-t / (0.06 * size ** 0.5)) * np.clip(t / 0.002, 0, 1)
    m.add(pad, 0.0, 0.7)
    m.add(oak(0.3, rng, rng.uniform(175, 250) / size ** 0.2, t60=0.1 * size ** 0.5, hard=0.8 * hard, lo=170), 0.0, 0.9)
    m.add(knock(rng, rng.uniform(0.95, 1.1), 1.0 * size ** 0.5, 0.4 * hard, 0.45, 0.9), 0.0, 0.5)
    m.add(hann_burst(0.003, rng, 900, 6000), 0.0, 0.15 * hard)
    wd = 0.25 * size ** 0.5
    sq = grain_cloud(wd, rng, 500 * np.exp(-tvec(wd) / 0.07) + 10, decay=0.0006, f_lo=1200, f_hi=7000, size_var=0.9)
    m.add(sq, 0.01, 0.25)
    return fit(m.out(), ns(dur))


def gargle(dur, rng, rate, r_lo, r_hi, drift=1.0, shape=3.0, weight=0.5, max_dur=0.16, swell=None):
    """a throat or a pipe gurgling: `rate` glugs a second (a number or a curve), each a bubble of its own, evenly enough spaced
    to be a gargle and not a boil, their size drifting from r_lo to r_hi mm over the time (drift 1 is all the way, so that the
    pitch falls as it goes) and their level following `swell`"""
    n = ns(dur)
    out = np.zeros(n + ns(max_dur + 0.02))
    for tt in events(dur, rng, rate, shape=shape):
        u = tt / dur
        r = r_lo * (r_hi / r_lo) ** (u * drift) * np.exp(0.12 * rng.standard_normal())
        g = (r / 3.0) ** weight * rng.uniform(0.6, 1.0)
        if swell is not None:
            g *= swell[min(n - 1, ns(tt))]
        b = bubble(r, rng, rise=rng.uniform(0.05, 0.3), max_dur=max_dur)
        i = ns(tt)
        out[i:i + len(b)] += g * b[:max(0, len(out) - i)]
    return out[:n]


def gill_hiss(dur, rng, f_from=3600.0, f_to=900.0, gurgle=0.9, rate=26.0, r_lo=2.5, r_hi=9.0):
    """air and water forced out of the gills of a fish in pain: a hiss that falls from bright to dull, chopped by a gargle of
    bubbles that slows and drops in pitch as the breath fails, and the dull wet body of the throat under the glugs"""
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    fc = f_from * (f_to / f_from) ** (u ** 0.8)
    swl = np.sin(np.pi * np.clip(u, 0, 1)) ** 0.5
    hiss = tvf(white(dur, rng), "bp", fc, 1.1) * lurch(dur, rng, 60, 0.6) * np.clip(t / 0.006, 0, 1)
    gar = gargle(dur, rng, rate * (1 - 0.45 * u), r_lo, r_hi, 1.0, 3.0, 0.5, 0.14)
    body = lp(bp(brown(dur, rng), 90, 500), 500) * (0.4 + 0.6 * np.abs(nrm(lp(np.abs(gar), 45))))
    m = Bus(dur)
    m.add(hiss, 0.0, 0.7 * (1 - 0.5 * gurgle))
    m.add(gar, 0.0, 1.0 * gurgle)
    m.add(body, 0.0, 0.45 * gurgle)
    return fit(m.out(), n) * np.clip((dur - t) / 0.04, 0, 1) * swl


# =========================================================================== the gnashfins
def fin_pass(dur, rng, peak=0.3, lo=700.0, hi=4200.0, size=1.0, doppler=0.1):
    """a dorsal fin slicing the surface as it passes: the thin hiss of water torn along its edge (it swells and brightens as
    the fin comes close, a sharp edge to it as the blade breaks the surface, and dulls and falls in pitch as it goes), the
    water thrown aside burbling under it, a fizz of tiny bubbles and the gurgle of bigger ones in its wake"""
    n = ns(dur)
    u = np.linspace(0, 1, n, endpoint=False)
    s = bell(dur, peak, 1.1, 1.6)
    dop = 1 + doppler * np.tanh((peak - u) * 6)
    fc = lo * (hi / lo) ** (s ** 0.8) * dop
    cut = tvf(colored(dur, rng, -1.5), "bp", fc, 0.8) * s ** 1.2 * lurch(dur, rng, 45, 0.45)
    edge = tvf(white(dur, rng), "bp", fc * 1.7, 3.5) * s ** 2.2 * lurch(dur, rng, 30, 0.5)
    wash = lp(bp(pink(dur, rng), 100, 900), 900) * s * lurch(dur, rng, 9, 0.5)
    fizz = bubbles(dur, rng, 1300 * s ** 1.6 + 8, 0.45, 1.8, beta=2.0, level=1.0, weight=0.4)
    mid = bubbles(dur, rng, 70 * s ** 1.2 + 2, 1.5, 5.0, beta=1.8, level=1.0, weight=0.5, rise=0.25)
    m = Bus(dur)
    m.add(cut, 0.0, 1.0)
    m.add(edge, 0.0, 0.25)
    m.add(wash, 0.0, 0.7 * size)
    m.add(fizz, 0.0, 0.35)
    m.add(mid, 0.0, 0.3)
    return fit(m.out(), n)


@recipe("gnashfin_fin", variants=3, peak_db=-3.0, fade_out=0.2, max_len=[0.9, 1.3, 1.1], tail_db=-60.0, loud=-18.0,
        limit_db=6.0, subtitle="Fin cuts the water", reach=24)
def gnashfin_fin(v, rng):
    """a dorsal fin cutting the surface as the gnashfin circles: 1 a quick clean pass, the hiss swelling and brightening
    and gone; 2 a slower one coming in and curving away, a second thinner cut as the fish turns, laps after it; 3 two
    weaving cuts close together with a small hollow plip between. Each is the bright, thin hiss of water parted along the
    fin's edge over a soft wash of water thrown aside and a fizz of tiny bubbles, a few drops falling back"""
    dur = [1.0, 1.4, 1.2][v]
    m = Bus(dur)
    if v == 0:
        m.add(fin_pass(0.85, rng, 0.28, 800, 4600), 0.0, 1.0)
        m.add(hann_burst(0.003, rng, 1200, 6000), 0.0, 0.12)
    elif v == 1:
        m.add(fin_pass(1.2, rng, 0.4, 600, 3600, 1.2, 0.12), 0.0, 1.0)
        m.add(fin_pass(0.42, rng, 0.35, 900, 4200), 0.82, 0.4)
        m.add(slosh(0.5, rng, 900, 0.3), 0.7, 0.2)
    else:
        m.add(fin_pass(0.5, rng, 0.3, 900, 4800), 0.0, 0.9)
        m.add(nrm(bubble(rng.uniform(7, 11), rng, rise=0.4)), 0.43, 0.25)
        m.add(fin_pass(0.62, rng, 0.35, 1100, 5200), 0.38, 1.0)
    for tt in np.sort(rng.uniform(0.35 * dur, 0.95 * dur, 4)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.08))
    return at_sea(hush(fit(m.out(), ns(dur))), 0.05, seed=1910 + v)


def leap_out(rng, size=1.0, skim=0.0, dur=1.05):
    """a pike's body bursting up out of the water: the tail's drive (a flat smack of water and the hollow punched under it),
    a gush as the body is hauled out (a swelling rush that streams away), the sheet of water torn off it, thrown spray, the
    soft swish of the fish arcing through the air, drops pattering back"""
    t = tvec(dur)
    m = Bus(dur)
    m.add(smack(rng, 1.1 * size, 1.0), 0.0, 0.9)
    m.add(bp(white(0.25, rng), 200, 3600) * np.exp(-tvec(0.25) / 0.045), 0.0, 0.5)
    m.add(saturate(thump(0.3, 160, 72, rng, decay=0.05 * size, click=0.0, noise=0.8, nlp=520), 2.2), 0.0, 0.5)
    gd = 0.45
    gt = tvec(gd)
    gush = tvf(colored(gd, rng, -2.0), "bp", 380 * 3.2 ** np.clip(gt / 0.15, 0, 1) ** 0.7, 0.8) \
        * np.sin(np.pi / 2 * np.clip(gt / 0.06, 0, 1)) ** 2 * np.exp(-gt / (0.14 * size ** 0.5)) * lurch(gd, rng, 30, 0.4)
    m.add(gush, 0.0, 0.75 * (1 - 0.4 * skim))
    shed = np.exp(-t / (0.2 * size ** 0.5)) * np.clip(t / 0.01, 0, 1) * (1 - 0.5 * skim)
    m.add(pour(dur, rng, shed, lo=500, hi=5000, drops=0.9, glugs=0.5, size=0.6, mix=(1.0, 0.5, 0.35)), 0.0, 0.75)
    m.add(spray(dur, rng, shed ** 0.8, 1200, 6000, rate=900), 0.02, 0.25)
    ad = 0.5
    m.add(swish(ad, rng, bell(ad, 0.35, 1.4, 1.7), 350, 2400, 0.4, 35), 0.08, 0.3 + 0.25 * skim)
    for tt in np.sort(rng.uniform(0.3, 0.95, 10)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09))
    return fit(m.out(), ns(dur))


@recipe("gnashfin_leap", variants=2, peak_db=-3.0, fade_out=0.25, max_len=[1.05, 0.95], tail_db=-60.0, loud=-14.5,
        limit_db=7.0, subtitle="Gnashfin leaps", reach=32)
def gnashfin_leap(v, rng):
    """the gnashfin bursting up out of the sea: 1 an explosive leap, the tail's smack and the body torn out of the water in a
    rush that streams away, spray flung up, the swish of the arc and drops falling back; 2 a flatter, skimming leap, less
    water and more air, a second kick off the crest of a wave"""
    if v == 0:
        y = leap_out(rng, 1.0, 0.0, 1.05)
    else:
        m = Bus(0.95)
        m.add(leap_out(rng, 0.8, 0.6, 0.95), 0.0, 1.0)
        m.add(smack(rng, 0.6, 1.2), 0.3, 0.25)
        m.add(hann_burst(0.004, rng, 1200, 6500), 0.3, 0.1)
        y = m.out()
    return at_sea(hush(y), 0.06, seed=1920 + v)


def knife_in(rng, size=1.0, steep=0.0, dur=0.95):
    """a pike going back into the sea head first: a thin, sharp crack of water parted by the point (little splash, a rushing
    bright hiss), the stream of bubbles dragged down behind it, the hollow closing over it with a 'bloop', the hiss going dull as
    it drops away; steep: a bigger crown of spray thrown up first"""
    m = Bus(dur)
    et = tvec(0.08)
    m.add(bp(white(0.08, rng), 1800, 9500) * np.exp(-et / 0.012) * np.clip(et / 0.0005, 0, 1), 0.0, 0.9)
    m.add(smack(rng, 0.6 * size, 1.2), 0.0, 0.4)
    if steep:
        m.add(splash(0.6, rng, size=0.5 * size, hard=0.7), 0.0, 0.5 * steep)
    bd = 0.65
    bt = tvec(bd)
    m.add(bubbles(bd, rng, 800 * np.exp(-bt / 0.14) + 30, 0.4, 3.4, beta=1.9, level=1.0, weight=0.4), 0.01, 0.7)
    m.add(nrm(bubble(rng.uniform(9, 15) * size ** 0.4, rng, rise=0.5, max_dur=0.3)), 0.05 + 0.02 * steep, 0.6)
    sd = 0.55
    st = tvec(sd)
    fc = 4200 * (500 / 4200) ** np.clip(st / 0.4, 0, 1)
    m.add(tvf(colored(sd, rng, -1.0), "bp", fc, 0.9) * np.exp(-st / 0.18) * np.clip(st / 0.004, 0, 1), 0.01, 0.55)
    for tt in np.sort(rng.uniform(0.2, 0.8, 6)):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.1))
    return fit(m.out(), ns(dur))


@recipe("gnashfin_dive", variants=2, peak_db=-3.0, fade_out=0.25, max_len=[0.9, 1.0], tail_db=-60.0, loud=-15.0,
        limit_db=7.0, subtitle="Gnashfin dives", reach=32)
def gnashfin_dive(v, rng):
    """the gnashfin knifing back in: 1 clean, a sharp crack of parted water, a rush of bubbles dragged down and a hollow
    'bloop' closing over it, the hiss dulling as it drops; 2 steeper after a long leap, a crown of spray first, then the
    same rush and a second, deeper bloop"""
    y = knife_in(rng, 1.0 + 0.2 * v, 0.0 if v == 0 else 1.0, [0.95, 1.05][v])
    if v == 1:
        m = Bus(1.05)
        m.add(y, 0.0, 1.0)
        m.add(nrm(bubble(rng.uniform(18, 26), rng, rise=0.4, max_dur=0.4)), 0.2, 0.35)
        y = m.out()
    return at_sea(y, 0.05, seed=1930 + v)


@recipe("gnashfin_snap", variants=3, peak_db=-3.0, fade_out=0.08, max_len=[0.35, 0.4, 0.45], tail_db=-58.0, loud=-19.0,
        limit_db=11.0, subtitle="Gnashfin snaps", reach=24)
def gnashfin_snap(v, rng):
    """jaws snapping shut, a bony clack: a spray of needle tips meeting over the hollow, bright knock of the bone behind
    them, a breath shut out between the teeth. 1 one hard clack; 2 a clack and a softer rebound a hair later, the jaws
    bouncing on each other; 3 a clack and the rows of teeth settling after it, a run of tiny ticks and a hiss of air"""
    m = Bus(0.5)
    sw = tvec(0.03)
    m.add(swish(0.03, rng, np.sin(np.pi / 2 * np.clip(sw / 0.03, 0, 1)) ** 2, 1200, 5000, 0.2, 40), 0.0, 0.12)
    if v == 0:
        m.add(jaw_clack(rng, 1.0, 1.0), 0.028, 1.0)
    elif v == 1:
        m.add(jaw_clack(rng, 1.0, 1.0), 0.028, 1.0)
        m.add(jaw_clack(rng, 1.15, 0.7), 0.092, 0.6)
    else:
        m.add(jaw_clack(rng, 0.9, 1.0), 0.028, 1.0)
        for k in range(5):
            m.add(strike(0.03, rng, [rng.uniform(2200, 6500)], [0.006], [1], lo=1500, hi=10000, ex=0.0004),
                  0.075 + 0.021 * k + rng.uniform(0, 0.006), 0.2 * 0.75 ** k)
        m.add(nrm(bp(white(0.14, rng), 2500, 7000) * np.exp(-tvec(0.14) / 0.04)), 0.07, 0.1)
    return at_sea(fit(m.out(), ns(0.5)), 0.04, seed=1940 + v)


def fang_bite(rng, hard=1.0, shake=0.0, dur=0.7):
    """a pike's teeth going into flesh: the jaws closing (a breath of air), a rush of needle points puncturing (fine ticks all
    over a few milliseconds), the meaty thud of the bite and the squelch of the wound closing round the teeth, gristle and
    sinew crunching, then the jaws letting go with a wet suck (shake: the head worrying it first, a rhythmic wet tearing)"""
    m = Bus(dur)
    m.add(swish(0.04, rng, np.sin(np.pi / 2 * np.clip(tvec(0.04) / 0.04, 0, 1)) ** 2, 800, 4500, 0.2, 40), 0.0, 0.1)
    at = 0.035
    for _ in range(int(rng.integers(7, 13))):
        f = rng.uniform(2200, 7000)
        m.add(strike(0.02, rng, [f, f * 1.9], [0.003, 0.002], [1, 0.4], lo=1500, hi=11000, ex=0.0004),
              at + rng.exponential(0.004), rng.uniform(0.12, 0.35) * hard, end_fade=0.002)
    m.add(saturate(thump(0.18, 210, 110, rng, decay=0.035, click=0.0, noise=0.5, nlp=700), 2.0), at, 0.6 * hard)
    m.add(oak(0.12, rng, 270, t60=0.03, hard=0.5, lo=200), at, 0.3)
    m.add(squelch(rng, 0.8, 0.2, 1.0), at + 0.004, 0.6)
    ct = tvec(0.2)
    m.add(grain_cloud(0.2, rng, 900 * np.exp(-ct / 0.07) + 30, decay=0.0011, f_lo=350, f_hi=3200, size_var=1.0), at + 0.01, 0.5)
    m.add(jaw_clack(rng, 1.0, 0.6, 0.0), at, 0.35)
    end = at + 0.16
    if shake:
        sd = 0.3 * shake
        stt = tvec(sd)
        rate = 13.0
        am = (0.5 + 0.5 * np.sin(2 * np.pi * rate * stt + rng.uniform(0, 6.28))) ** 1.5
        wet = bp(colored(sd, rng, -1.5), 200, 1800) * am * np.sin(np.pi * np.clip(stt / sd, 0, 1)) ** 0.7
        m.add(wet, end - 0.03, 0.55)
        for k in range(int(3 * shake)):
            m.add(squelch(rng, rng.uniform(0.6, 1.0), 0.14, 1.0), end + 0.02 + 0.075 * k, 0.35)
        end += sd
    m.add(squelch(rng, 0.9, 0.18, 1.6), end, 0.5)
    m.add(nrm(sucker_pop(rng, 1.2, 0.6)), end + 0.02, 0.2)
    return fit(m.out(), ns(dur))


@recipe("gnashfin_bite", variants=2, peak_db=-3.0, fade_out=0.12, max_len=[0.55, 0.9], tail_db=-60.0, loud=-13.5,
        limit_db=8.0, subtitle="Gnashfin bites", reach=24)
def gnashfin_bite(v, rng):
    """teeth into flesh: 1 a quick hard bite and release, a rush of needle points puncturing, the meaty thud and the wound
    squelching shut round the teeth, gristle crunching, the jaws letting go with a wet suck; 2 a bite that holds and the head
    worrying it, a wet rhythmic tearing, three squelches, and the release"""
    y = fang_bite(rng, 1.0, 0.0 if v == 0 else 1.0, [0.6, 0.95][v])
    return at_sea(y, 0.04, seed=1950 + v)


def gnaw_rasp(dur, rng, rate, amp, peaks=(190, 420), grit=1.0):
    """needle teeth dragged across wet oak at the waterline: a stick-slip train of tiny slips ringing the hull's own modes (the
    same oak as the cog's creaks), a fine gritty rasp of the teeth over the grain, all of it half drowned, so that the top
    is gone and what is left is the planks' dull rattle"""
    y = creak_voice(dur, rng, rate, amp, hull_modes(120, 2400, peaks=peaks), jitter=0.22, alt=0.1, rub=0.0, kick=0.0006,
                    bright=3400, unsteady=0.5)
    gr = grain_cloud(dur, rng, (400 + 1700 * np.asarray(amp)) * grit, decay=0.0007, f_lo=600, f_hi=3600, size_var=1.0) * amp
    return nrm(y) + 0.5 * grit * nrm(gr)


@recipe("gnashfin_gnaw", variants=3, peak_db=-4.0, fade_out=0.2, max_len=[1.4, 1.2, 1.6], tail_db=-60.0, loud=-17.0,
        limit_db=8.0, subtitle="Teeth gnaw at the hull", reach=24)
def gnashfin_gnaw(v, rng):
    """teeth worrying at wet timber at the waterline, half muffled: 1 a long drawn rasp, two hard tugs that pull the groan
    down and a dull knock of the plank; 2 a quick chatter of nibbles, a dozen small bites at uneven moments, each a small
    tick through the planking; 3 a heavy grinding gnaw that ends in two hollow knocks of the hull and a slap of water as the
    fish thrashes. Under it all the water lapping at the planks, a gurgle of bubbles round the snout, and the dull hollow of
    the hull"""
    dur = [1.5, 1.3, 1.7][v]
    m = Bus(dur)
    if v == 0:
        rate = path([(0, 55), (0.35, 95), (0.62, 62), (0.7, 30), (0.78, 110), (1.1, 80), (1.3, 40), (1.45, 20)], dur, 0.02)
        amp = path([(0, 0.0), (0.06, 0.55), (0.5, 0.7), (0.66, 1.0), (0.72, 0.3), (0.8, 1.0), (1.1, 0.75), (1.4, 0.0)], dur, 0.015)
        m.add(gnaw_rasp(dur, rng, rate, amp, peaks=(190, 440)), 0.0, 1.0)
        for at, g in [(0.64, 0.8), (1.2, 0.5)]:
            m.add(oak(0.2, rng, rng.uniform(210, 260), t60=0.07, hard=0.6, lo=180), at, g)
    elif v == 1:
        times = np.cumsum(rng.uniform(0.06, 0.15, 12)) + 0.02
        for k, tt in enumerate(times):
            if tt > dur - 0.2:
                break
            d = rng.uniform(0.04, 0.09)
            rate_k = path([(0, rng.uniform(70, 130)), (d, rng.uniform(40, 80))], d, 0.005)
            m.add(gnaw_rasp(d, rng, rate_k, np.sin(np.pi * np.linspace(0, 1, ns(d))) ** 0.7, peaks=(220, 520)), tt,
                  rng.uniform(0.5, 1.0))
            m.add(oak(0.1, rng, rng.uniform(250, 340), t60=0.04, hard=0.7, lo=200), tt + d * 0.4, rng.uniform(0.3, 0.7))
    else:
        rate = path([(0, 30), (0.4, 52), (0.9, 70), (1.2, 40), (1.4, 22), (1.6, 12)], dur, 0.02)
        amp = path([(0, 0.0), (0.08, 0.7), (0.5, 0.85), (1.0, 1.0), (1.25, 0.8), (1.5, 0.0)], dur, 0.02)
        m.add(gnaw_rasp(dur, rng, rate, amp, peaks=(150, 330), grit=1.2), 0.0, 1.0)
        for at, g in [(1.26, 1.0), (1.42, 0.7)]:
            m.add(knock(rng, rng.uniform(0.85, 1.0), 1.1, 0.5, 0.45, 1.0), at, g)
        m.add(splash(0.5, rng, size=0.45, hard=0.5), 1.3, 0.5)
    lapd = dur
    m.add(slosh(lapd, rng, 700, 0.4), 0.0, 0.25)
    for _ in range(int(3 + 3 * dur)):
        m.add(hull_slap(rng, rng.uniform(0.25, 0.45), 0.6, cav=0.5), rng.uniform(0.0, dur - 0.5), rng.uniform(0.08, 0.2))
    bubd = dur
    m.add(bubbles(bubd, rng, 60 + 40 * np.sin(np.linspace(0, 3 * np.pi, ns(bubd))) ** 2, 1.5, 7.0, beta=1.8, level=1.0,
                  weight=0.5), 0.0, 0.25)
    y = fit(m.out(), ns(dur))
    y = lp(y, 2300, 2)  # half drowned
    y = eq(y, "peak", 230, 1.0, 4.0)
    return below_hull(y, seed=1960 + v)


def below_hull(x, wet=0.18, rt60=0.35, seed=1970):
    """heard through the planks from outside: the small, close, dull room of oak and tarred plank, a hollow in it"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.003, hf_damp=0.7, seed=seed, early_span=0.012)


@recipe("gnashfin_flop", variants=3, peak_db=-3.0, fade_out=0.25, max_len=[1.4, 1.4, 1.5], tail_db=-60.0, loud=-14.5,
        limit_db=8.0, subtitle="Gnashfin flops on deck", reach=24)
def gnashfin_flop(v, rng):
    """a heavy fish slapping on deck boards: wet, flat slaps (a burst of wet noise and the soft pad under it), the planks
    knocking back dully, the deck booming over the hollow below, water squirted out and pattering, scales scraping.
    1 three flops that slow and weaken; 2 a flurry of small ones then one big heave; 3 one huge flop, a long wet slither
    across the boards and two weak thumps at the end"""
    dur = 1.5
    m = Bus(dur)
    if v == 0:
        for at, size, hard, g in [(0.0, 1.0, 1.0, 1.0), (0.4, 0.85, 0.85, 0.85), (0.8, 0.7, 0.7, 0.65), (1.2, 0.5, 0.5, 0.4)]:
            m.add(body_slap(rng, size, hard), at + rng.uniform(-0.01, 0.01), g)
    elif v == 1:
        for at, size, hard, g in [(0.0, 0.55, 0.7, 0.6), (0.11, 0.5, 0.65, 0.55), (0.23, 0.55, 0.7, 0.6), (0.34, 0.5, 0.6, 0.5),
                                  (0.49, 0.6, 0.75, 0.7), (0.78, 1.15, 1.0, 1.0)]:
            m.add(body_slap(rng, size, hard, 0.4), at + rng.uniform(-0.008, 0.008), g)
        m.add(swish(0.25, rng, bell(0.25, 0.4, 1.3, 1.6), 400, 2600, 0.5, 40), 0.55, 0.2)
    else:
        m.add(body_slap(rng, 1.25, 1.0), 0.0, 1.0)
        sd = 0.5
        st = tvec(sd)
        env = np.sin(np.pi * np.clip(st / sd, 0, 1)) ** 1.2
        sl = bp(colored(sd, rng, -1.5), 250, 2600) * env * lurch(sd, rng, 22, 0.6) \
            + 0.5 * grain_cloud(sd, rng, 300 * env + 10, decay=0.001, f_lo=700, f_hi=3800) * env
        m.add(sl, 0.3, 0.45)
        for k in range(3):
            m.add(nrm(squeal(0.07, rng, rng.uniform(900, 1500), np.sin(np.pi * np.linspace(0, 1, ns(0.07))), harmonics=5, chatter=0.6)),
                  0.38 + 0.13 * k, 0.1)
        m.add(body_slap(rng, 0.55, 0.6), 0.9, 0.4)
        m.add(body_slap(rng, 0.4, 0.45), 1.18, 0.28)
    ft = tvec(dur)
    m.add(grain_cloud(dur, rng, 80 * np.exp(-ft / 0.5) + 5, decay=0.0006, f_lo=900, f_hi=6000), 0.05, 0.15)
    for tt in np.sort(rng.uniform(0.1, 1.3, 8)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.08))
    return at_sea(hush(fit(m.out(), ns(dur)), -4.0, 8000), 0.04, seed=1965 + v)


@recipe("gnashfin_hurt", variants=2, peak_db=-3.0, fade_out=0.12, max_len=[0.7, 0.85], tail_db=-60.0, loud=-15.5, limit_db=7.0,
        subtitle="Gnashfin hisses", reach=24)
def gnashfin_hurt(v, rng):
    """a gurgling hiss: air and water forced out of the gills of a fish in pain, a hiss falling away dull over a gargle of
    bubbles. 1 a sharp hiss with the gargle thickening as it goes, a clack of the jaws at the start; 2 two bursts of it and a
    wet rattle of the gill-plates after"""
    dur = [0.75, 0.95][v]
    m = Bus(dur)
    if v == 0:
        m.add(gill_hiss(0.68, rng, 3800, 1000, 0.85, 26), 0.0, 1.0)
        m.add(jaw_clack(rng, 1.0, 0.7, 0.4), 0.0, 0.35)
        m.add(slosh(0.4, rng, 900, 0.2), 0.0, 0.2)
    else:
        m.add(gill_hiss(0.34, rng, 4200, 1600, 0.7, 32, 2.0, 7.0), 0.0, 0.85)
        m.add(gill_hiss(0.55, rng, 3400, 800, 0.95, 22, 3.0, 11.0), 0.3, 1.0)
        for k in range(5):
            m.add(strike(0.05, rng, rng.uniform(900, 1700) * np.array([1, 1.9]), [0.012, 0.008], [1, 0.5], lo=500, hi=6000,
                         ex=0.0008), 0.62 + 0.045 * k + rng.uniform(0, 0.01), 0.3 * 0.8 ** k)
    return at_sea(hush(fit(m.out(), ns(dur)), -4.0, 7500), 0.04, seed=1980 + v)


@recipe("gnashfin_death", peak_db=-3.0, fade_out=0.35, max_len=1.9, tail_db=-62.0, loud=-15.0, limit_db=7.0,
        subtitle="Gnashfin gasps its last", reach=32)
def gnashfin_death(v, rng):
    """a rattling gasp: the fish drawing a last wet breath (a gulp of air and water through the gills that opens and closes
    like a vowel, bubbles gargling in it), the rattle after it (bone and gill-plate clacking over a wet gargle, the rate
    dropping as it fails), a long breath going out of it in a hiss, and the tail's last two weak slaps on the boards"""
    dur = 2.0
    m = Bus(dur)
    gd = 0.5
    gt = tvec(gd)
    u = gt / gd
    gasp = formants(colored(gd, rng, -1.2), "a", "o", scale=0.85, curve=u, gains=(1.0, 0.8, 0.45, 0.2)) \
        * np.sin(np.pi * np.clip(u, 0, 1)) ** 1.3 * lurch(gd, rng, 28, 0.5)
    m.add(lp(gasp, 3200), 0.0, 0.8)
    m.add(gargle(gd, rng, 30 * (1 - 0.3 * u), 2.5, 8.0, 0.8, 3.0, 0.5, 0.14, np.sin(np.pi * u) ** 0.6), 0.0, 0.7)
    rd = 1.1
    rate = path([(0, 22), (0.4, 17), (0.8, 10), (1.1, 5)], rd, 0.02)
    for tk in beats(rate, rng, 0.25):
        if tk < rd - 0.05:
            amp = np.exp(-tk / 0.7) * rng.uniform(0.5, 1.0)
            m.add(jaw_clack(rng, rng.uniform(0.8, 1.2), 0.6, 0.5), 0.45 + tk, 0.7 * amp)
    m.add(gargle(1.0, rng, path([(0, 24), (0.6, 14), (1.0, 6)], 1.0, 0.02), 3.0, 10.0, 1.0, 2.5, 0.5, 0.16,
                 np.exp(-tvec(1.0) / 0.5)), 0.5, 0.8)
    m.add(gill_hiss(0.95, rng, 2600, 700, 0.5, 12), 0.55, 0.45)
    m.add(body_slap(rng, 0.5, 0.5, 0.35), 1.25, 0.4)
    m.add(body_slap(rng, 0.35, 0.4, 0.3), 1.55, 0.25)
    return at_sea(hush(fit(m.out(), ns(dur)), -4.0, 7500), 0.04, seed=1990)


# =========================================================================== the voice of the deep
def swirl(x, rng, base=0.006, depth=0.002, rate=0.35, mix=0.5):
    """a sound heard through moving water: a delayed copy whose delay swims about (a comb filter that wanders), so that the
    colour of the sound drifts and phases as the water between moves"""
    n = len(x)
    t = np.arange(n) / SR
    d = base + depth * np.sin(2 * np.pi * rate * t + rng.uniform(0, 6.28)) \
        + 0.5 * depth * np.sin(2 * np.pi * rate * 2.7 * t + rng.uniform(0, 6.28))
    y = np.interp(np.arange(n) - d * SR, np.arange(n), x, left=0.0)
    return (x + mix * y) / (1 + 0.5 * mix)


def abyss_voice(dur, rng, f0, formants=((150, 0.55, 4.0), (420, 0.6, 2.5)), slope=1.0, k_max=40, shimmer=0.35,
                drift=0.003, rolloff=1800.0, amp_rate=(0.15, 1.2)):
    """a voice too big for a throat: a stack of harmonics on a pitch down where hearing runs out (f0: Hz, a number or a curve),
    shaped by broad resonances that slide with the shape of the sound (formants: (centre Hz, number or curve; width in
    octaves; gain)), each partial wandering a little in pitch and swelling and fading at a rate of its own, so that it is a
    living mass of tone and not an organ pipe"""
    n = ns(dur)
    t = np.arange(n) / SR
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,))
    ph0 = 2 * np.pi * np.cumsum(f0) / SR
    out = np.zeros(n)
    for k in range(1, k_max + 1):
        fk = k * f0
        if np.min(fk) > SR * 0.45:
            break
        dev = 1 + drift * (2 * smooth_random(dur, rng, rng.uniform(0.3, 2.0)) - 1)
        ph = k * ph0 * 1.0 + 2 * np.pi * np.cumsum(fk * (dev - 1)) / SR + rng.uniform(0, 6.28)
        a = k ** -slope * np.ones(n)
        for c, w, g in formants:
            cc = np.broadcast_to(np.asarray(c, dtype=float), (n,))
            a = a * (1 + g * np.exp(-0.5 * (np.log(np.maximum(fk, 1.0) / cc) / w) ** 2))
        a = a / np.sqrt(1 + (fk / rolloff) ** 4)
        r = rng.uniform(*amp_rate)
        a = a * (1 - shimmer * 0.5 * (1 + np.sin(2 * np.pi * r * t + rng.uniform(0, 6.28))))
        a = np.where(fk < SR * 0.45, a, 0.0)
        out += a * np.sin(ph)
    return out / (np.max(np.abs(out)) + 1e-12)


def deep_voice(dur, rng, f0, opening=None, mass=0.8, twin=0.45, twin_ratio=1.0595, grit=0.7, breath=0.25, rough=17.0,
               depth=0.35, lpf=1500.0, k_max=40, subharm=0.5, tremor=0.0):
    """the Maelgrasp's voice, out of a throat as big as a ship's hold: a living mass of tone on a pitch (f0, Hz, a number or a
    curve) down where hearing runs out, a second voice a semitone over it (the interval of its theme; the omens' answer is a
    tritone) grinding against the first, a rough pulse voice through a vocal tract of a thousand times a man's volume, the
    turbulence of the breath through the same resonances, all of it broken up by a slow roughness and swimming in the water.
    `opening` (0..1, a curve) is how far the mouth is open: the resonances slide with it, 'oo' to 'ah'"""
    n = ns(dur)
    t = tvec(dur)
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,)).copy()
    op = np.full(n, 0.5) if opening is None else np.broadcast_to(np.asarray(opening, dtype=float), (n,))
    c1 = 125 + 110 * op
    c2 = 360 + 200 * op
    out = mass * nrm(abyss_voice(dur, rng, f0, formants=((c1, 0.5, 5.0), (c2, 0.55, 3.0)), k_max=k_max))
    if twin:
        out += twin * nrm(abyss_voice(dur, rng, f0 * twin_ratio, formants=((c1 * 0.9, 0.5, 5.0), (c2 * 0.9, 0.55, 3.0)),
                                      k_max=int(k_max * 0.75)))
    if grit:
        g = glottal(dur, f0 * (1 + 0.02 * np.sin(2 * np.pi * 5.3 * t)) * wander(dur, rng, 2.0, 0.02), rng, jitter=0.08,
                    shimmer=0.4, subharm=subharm, open_q=0.5, breath=0.5, fry=0.12)
        g = formants(g, "o", "a", scale=0.42, curve=np.clip(op, 0, 1), gains=(1.0, 0.7, 0.3, 0.12))
        out += grit * nrm(drive(nrm(g), 3.0, asym=0.1))
    if breath:
        d = tvf(colored(dur, rng, -2.0), "bp", c1 * 1.2, 1.5) + 0.6 * tvf(colored(dur, rng, -2.0), "bp", c2 * 1.2, 2.0)
        out += breath * nrm(d * (0.5 + 0.5 * smooth_random(dur, rng, 8)))
    rate = rough * wander(dur, rng, 1.5, 0.25)
    out = out * (1 - depth * (0.5 + 0.5 * np.sin(phase_from_freq(rate, n) + rng.uniform(0, 6.28))))
    if tremor:
        out = out * (1 - tremor * (0.5 + 0.5 * np.sin(phase_from_freq(5.3 * wander(dur, rng, 0.7, 0.2), n) + rng.uniform(0, 6.28))))
    out = swirl(out, rng, 0.006, 0.002, 0.3, 0.45)
    out = lp(out, lpf, 2)
    return out / (np.max(np.abs(out)) + 1e-12)


def abyss(x, wet=0.3, rt60=3.0, seed=1905, cut=900.0, pad=1.5):
    """heard from far below, through the sea and the hull: the top gone, and round it a long, dark, slow ring"""
    return reverb(lp(x, cut, 2), wet=wet, rt60=rt60, predelay=0.06, hf_damp=0.9, seed=seed, early_span=0.15, pre_hp=40.0,
                  tail_pad=pad)


def deep_creak(dur, rng, rate, amp, f_lo=28.0, f_hi=520.0, peaks=(60.0, 130.0), t60=(0.5, 0.1), vowel=None, kick=0.003,
               bright=700.0, jitter=0.18, alt=0.15, rub=0.0, unsteady=0.4):
    """stick-slip friction on a body far bigger than the cog: the same train of slips, ringing modes an octave and more
    lower and a good deal longer (a hull, a reef, a bed of oak and bone), so that it is not a creak but a groan the size of
    the sea floor"""
    modes = timber_modes(rng, f_lo, f_hi, 24, t60[0], t60[1], peaks=peaks)
    return creak_voice(dur, rng, rate, amp, modes, vowel, jitter=jitter, alt=alt, rub=rub, kick=kick, bright=bright,
                       unsteady=unsteady)


def sea_floor(dur, rng, env, lo=24.0, hi=140.0):
    """the sea itself moving: a swell of the deepest rumble, as much felt as heard, ruffled slowly"""
    y = lp(bp(brown(dur, rng), lo, hi), hi, 2) * (1 - 0.5 * smooth_random(dur, rng, 2.5))
    return y * env


def voice_break(f0, t, spans, drop=0.5, edge=0.02):
    """a voice failing for a moment: over each (start, length) in spans the pitch falls by `drop` (a period dropped, down an
    octave) and comes back"""
    f0 = np.array(f0, dtype=float)
    for a, d in spans:
        w = np.clip(np.minimum(t - a, a + d - t) / edge, 0, 1)
        f0 = f0 * (1 - drop * w)
    return f0


@recipe("maelgrasp_omen", variants=3, peak_db=-3.0, fade_out=0.8, max_len=[4.6, 5.9, 6.8], tail_db=-62.0, loud=-15.5,
        limit_db=10.0, subtitle="Something groans far below", reach=96, hp_hz=38.0, hp_order=3)
def maelgrasp_omen(v, rng):
    """a vast groan from far below: deep, distant, wrong. 1 a voice heaving up in two swells with a lull between, the mouth
    opening and closing on its 'oo' and 'ah', a crack in the voice at the top of the second, ancient timber creaking in the
    lull, and a slow sag; 2 two voices taking turns, a low one and an answer a tritone above it that does not belong, bubbles
    climbing between them, both sagging away together; 3 the voice coming apart into a slow ratchet of creaks, a shudder of
    the whole sea, and a last sagging drop. Heard through the water and the hull, long and dark"""
    dur = [3.9, 5.0, 5.8][v]
    t = tvec(dur)
    m = Bus(dur + 1.6)
    heave_ = lambda d, r: 1 + 0.045 * np.sin(2 * np.pi * r * tvec(d) + rng.uniform(0, 6.28))  # the slow heave of the whole beast
    if v == 0:
        f0 = path([(0, 31), (0.7, 34), (1.2, 42), (1.55, 40), (2.0, 50), (2.4, 54), (2.8, 46), (3.3, 36), (dur, 29)], dur, 0.2) \
            * heave_(dur, 0.75) * wander(dur, rng, 0.6, 0.015)
        f0 = voice_break(f0, t, [(2.42, 0.12)])
        op = np.clip(path([(0, 0.1), (1.0, 0.7), (1.6, 0.3), (2.3, 1.0), (3.4, 0.15)], dur, 0.25), 0, 1)
        env = path([(0, 0.0), (0.45, 0.25), (1.1, 0.75), (1.65, 0.16), (2.1, 0.85), (2.5, 1.0), (3.0, 0.7), (3.5, 0.25), (dur, 0.0)], dur,
                   0.1) * lurch(dur, rng, 6, 0.25)
        m.add(deep_voice(dur, rng, f0, op, tremor=0.15) * env, 0.0, 1.0)
        m.add(deep_creak(1.1, rng, path([(0, 5), (0.5, 13), (1.1, 6)], 1.1), bell(1.1, 0.45, 1.5, 1.4),
                         vowel=[(path([(0, 90), (0.6, 170), (1.1, 110)], 1.1), 3.0, 0.9)], jitter=0.4, alt=0.3), 1.2, 0.5)
        m.add(deep_creak(dur, rng, path([(0, 3), (2.4, 20), (3.4, 6), (dur, 3)], dur), bell(dur, 0.6, 1.5, 1.5) * 0.7,
                         vowel=[(path([(0, 90), (2.0, 160), (dur, 100)], dur), 3.0, 0.9)], jitter=0.4, alt=0.3), 0.0, 0.35)
        m.add(bubbles(dur, rng, 3 + 8 * bell(dur, 0.55, 1.5, 1.5), 22.0, 110.0, beta=1.4, level=1.0, weight=0.7, rise=0.1), 0.2, 0.35)
    elif v == 1:
        d1 = 3.0
        f1 = path([(0, 30), (0.5, 33), (1.0, 40), (1.6, 49), (2.2, 41), (d1, 30)], d1, 0.25) * heave_(d1, 0.7) * wander(d1, rng, 0.5, 0.015)
        e1 = path([(0, 0.0), (0.4, 0.3), (1.0, 0.85), (1.5, 1.0), (2.05, 0.15), (d1, 0.0)], d1, 0.1) * lurch(d1, rng, 5, 0.25)
        m.add(deep_voice(d1, rng, f1, np.clip(path([(0, 0.2), (1.5, 0.85), (d1, 0.1)], d1, 0.3), 0, 1), tremor=0.12) * e1, 0.0, 1.0)
        d2 = 3.3
        f2 = path([(0, 62), (0.7, 71), (1.4, 67), (2.2, 56), (d2, 44)], d2, 0.25) * heave_(d2, 0.9) * wander(d2, rng, 0.7, 0.02)
        e2 = path([(0, 0.0), (0.4, 0.5), (1.0, 1.0), (1.8, 0.8), (2.5, 0.35), (d2, 0.0)], d2, 0.12) * lurch(d2, rng, 7, 0.3)
        m.add(deep_voice(d2, rng, voice_break(f2, tvec(d2), [(1.3, 0.1)]), np.clip(path([(0, 0.7), (1.0, 0.9), (d2, 0.2)], d2, 0.3), 0, 1),
                         twin=0.6, twin_ratio=1.4142, grit=0.4, lpf=1100.0) * e2, 1.8, 0.62)
        m.add(bubbles(dur, rng, 3 + 40 * np.clip((t - 1.6) / 2.4, 0, 1) ** 2, 8.0, 70.0, beta=1.5, level=1.0, weight=0.6, rise=0.1), 0.3, 0.3)
        m.add(deep_creak(1.4, rng, path([(0, 4), (0.7, 12), (1.4, 5)], 1.4), bell(1.4, 0.5, 1.4, 1.4), jitter=0.4, alt=0.3,
                         vowel=[(path([(0, 100), (1.4, 140)], 1.4), 3.0, 0.9)]), 1.5, 0.45)
    else:
        f0 = path([(0, 33), (1.0, 40), (1.8, 48), (2.6, 55), (3.1, 52), (3.5, 46), (4.0, 36), (4.8, 24), (dur, 21)], dur, 0.2) \
            * heave_(dur, 0.6) * wander(dur, rng, 0.8, 0.015)
        f0 = voice_break(f0, t, [(3.0, 0.1), (3.55, 0.14)])
        op = np.clip(path([(0, 0.15), (2.6, 0.9), (dur, 0.0)], dur, 0.3), 0, 1)
        env = path([(0, 0.0), (0.7, 0.3), (1.5, 0.55), (2.1, 0.45), (2.7, 1.0), (3.3, 0.8), (4.0, 0.4), (4.5, 0.0), (dur, 0.0)], dur,
                   0.12) * lurch(dur, rng, 5, 0.25)
        vd = 4.4
        m.add(deep_voice(vd, rng, f0[:ns(vd)], op[:ns(vd)], grit=0.9, tremor=0.2) * env[:ns(vd)]
              * np.clip((vd - tvec(vd)) / 0.6, 0, 1), 0.0, 0.9)
        rate = path([(0, 3), (2.0, 7), (3.4, 16), (4.4, 26), (5.2, 14), (dur, 3)], dur)
        m.add(deep_creak(dur, rng, rate, bell(dur, 0.7, 1.5, 1.3), f_lo=30, f_hi=600, peaks=(70, 150),
                         vowel=[(path([(0, 110), (4.0, 210), (dur, 120)], dur), 3.0, 0.8)], bright=900.0, jitter=0.4, alt=0.3), 0.0, 0.7)
        m.add(sea_floor(dur, rng, bell(dur, 0.72, 2.0, 1.4)), 0.0, 0.8)
        m.add(bubbles(1.8, rng, 2 + 60 * np.linspace(0, 1, ns(1.8)) ** 2, 1.5, 12.0, beta=1.8, level=1.0, weight=0.5, rise=0.3),
              3.6, 0.25)
    y = fit(m.out(), ns(dur + 1.6))
    # the cog's own timbers answering the sea
    hm = hull_modes(85, 900, peaks=(170, 430))
    y = y + 0.18 * rn(resonators(lp(y, 420, 2), *hm)) * rms(y)
    return abyss(y, 0.28, 2.8, 1905 + v, 950.0, 1.2)


# =========================================================================== the sea heaved up
def heave(dur, rng, f_pts, env, amount=2.4, wobble=0.04):
    """the weight of the sea moving: a pressure wave too low to hear, wandering in pitch, driven until its harmonics carry
    (f_pts: (time, Hz) points of its pitch, env: its level)"""
    n = ns(dur)
    f = path(f_pts, dur, 0.1) * wander(dur, rng, 1.2, wobble)
    y = np.sin(phase_from_freq(f, n) + rng.uniform(0, 6.28)) + 0.4 * np.sin(phase_from_freq(f * 1.5, n) + rng.uniform(0, 6.28))
    return saturate(y * env, amount) * np.maximum(env, 0) ** 0.3


def boil(dur, rng, env, lo=90.0, hi=1100.0, churn=1.0):
    """a great patch of sea boiling: the broad, low roar of turbulent water, churning slowly (a rush ruffled in three bands
    at their own rates, so that it heaves and does not hiss)"""
    y = np.zeros(ns(dur))
    for a, b, g, r in [(lo, lo * 2.6, 1.0, 1.7), (lo * 2.6, lo * 6.0, 0.8, 3.1), (lo * 6.0, hi, 0.5, 5.3)]:
        band = bp(colored(dur, rng, -2.0), a, min(b, hi))
        y += g * rn(band) * (0.35 + 0.65 * smooth_random(dur, rng, r * churn) ** 1.3)
    return y * env


@recipe("maelgrasp_bubbles", variants=2, peak_db=-3.0, fade_out=0.5, max_len=[3.4, 3.9], tail_db=-62.0, loud=-15.0,
        limit_db=9.0, subtitle="The sea boils with bubbles", reach=48, hp_hz=40.0, hp_order=4)
def maelgrasp_bubbles(v, rng):
    """a great upwelling of bubbles round the hull as something stirs below: a swarm of every size, fine fizz up to the great
    slow 'bloops' of bubbles as big as a head, a patch of sea boiling and heaving under them, the hull knocking and
    creaking as bubbles burst against it and the water under her swells. 1 one long swell, building, boiling and dying away;
    2 three belches of it, each bigger, with the water slumping between"""
    dur = [3.4, 3.9][v]
    n = ns(dur)
    m = Bus(dur)
    if v == 0:
        up = path([(0, 0.0), (0.5, 0.25), (1.3, 0.8), (1.9, 1.0), (2.5, 0.7), (3.0, 0.3), (dur, 0.0)], dur, 0.2)
    else:
        up = path([(0, 0.0), (0.2, 0.35), (0.55, 0.45), (0.85, 0.2), (1.25, 0.7), (1.6, 0.55), (1.95, 0.15), (2.3, 0.9),
                   (2.7, 1.0), (3.1, 0.45), (dur, 0.0)], dur, 0.1)
    up = np.clip(up, 0, 1)
    m.add(boil(dur, rng, up ** 1.2), 0.0, 1.0)
    m.add(bubbles(dur, rng, 2600 * up ** 1.6 + 4, 0.5, 3.5, beta=2.0, level=1.0, weight=0.4), 0.0, 0.45)
    m.add(bubbles(dur, rng, 160 * up ** 1.4 + 2, 3.5, 24.0, beta=1.8, level=1.0, weight=0.5, rise=0.15), 0.0, 0.7)
    m.add(bubbles(dur, rng, 11 * up ** 1.5 + 0.4, 24.0, 120.0, beta=1.5, level=1.0, weight=0.7, rise=0.1), 0.0, 1.0)
    m.add(spray(dur, rng, up ** 1.5, 1500, 6500, rate=700), 0.0, 0.18)
    m.add(heave(dur, rng, [(0, 30), (dur * 0.5, 38), (dur, 27)], up ** 1.3, 2.0), 0.0, 0.45)
    # the hull over it: bubbles bursting on the planks, the hull lifted and creaking
    for tt in events(dur, rng, 5 + 14 * up, shape=1.5):
        m.add(hull_slap(rng, rng.uniform(0.3, 0.9), 0.6, cav=0.9), tt, rng.uniform(0.08, 0.3) * (0.3 + up[min(n - 1, ns(tt))]))
    if v == 1:
        for at in (0.2, 1.2, 2.3):
            m.add(knock(rng, rng.uniform(0.6, 0.9), 1.6, 0.35, 0.6, 1.0), at + 0.04, 0.5 * (0.5 + at / 2.3))
    # the great bubbles that stand out of the swarm, breaking one by one (a hand across: a deep, round 'bloop')
    for at, r, g in ([(0.9, 38, 0.5), (1.45, 55, 0.7), (1.9, 30, 0.5), (2.5, 44, 0.5)] if v == 0 else
                     [(0.25, 30, 0.5), (1.25, 42, 0.6), (2.35, 60, 0.8), (2.75, 36, 0.6), (3.1, 48, 0.6)]):
        m.add(nrm(bubble(r * rng.uniform(0.9, 1.1), rng, rise=0.3, max_dur=0.6)), at + rng.uniform(-0.03, 0.03), g)
    m.add(creak_voice(dur, rng, path([(0, 5), (1.6, 14), (2.6, 30), (dur, 6)], dur), up ** 0.8 * 0.6,
                      hull_modes(70, 1800, peaks=(150, 330)), [(path([(0, 200), (2.0, 340), (dur, 240)], dur), 3.5, 0.8)],
                      jitter=0.16, alt=0.2, kick=0.0014, bright=2200, unsteady=0.4), 0.0, 0.35)
    for tt in np.sort(rng.uniform(0.3 * dur, dur - 0.1, 8)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09) * up[min(n - 1, ns(tt))] ** 0.5)
    y = fit(m.out(), n)
    return at_sea(hush(y, -5.0, 6500), 0.1, seed=2000 + v)


@recipe("maelgrasp_surge", peak_db=-3.0, fade_out=0.5, max_len=2.7, tail_db=-62.0, loud=-12.0, limit_db=9.0,
        subtitle="The sea heaves", reach=64, hp_hz=40.0, hp_order=4)
def maelgrasp_surge(v, rng):
    """the sea heaving as something huge comes up under it: the weight of the water lifting (a pressure wave heard in the
    chest, a great rush of displaced water gathering, streaming off the swelling back of it), the cog lifted and slapped by
    the swell and creaking in every timber, bubbles bursting from below, the crest breaking over and the water slumping
    back with a slosh"""
    dur = 2.9
    n = ns(dur)
    m = Bus(dur)
    lift = np.clip(path([(0, 0.0), (0.4, 0.3), (1.0, 0.85), (1.45, 1.0), (1.9, 0.6), (2.4, 0.25), (dur, 0.0)], dur, 0.15), 0, 1)
    m.add(heave(dur, rng, [(0, 24), (1.0, 34), (1.5, 46), (2.2, 36), (dur, 26)], lift, 2.6), 0.0, 0.8)
    m.add(boil(dur, rng, lift ** 1.1, 80.0, 1400.0, 0.8), 0.0, 0.9)
    rush = tvf(colored(dur, rng, -2.0), "bp", path([(0, 160), (1.2, 650), (1.8, 900), (dur, 380)], dur, 0.2), 0.8) \
        * lift ** 1.4 * lurch(dur, rng, 20, 0.4)
    m.add(rush, 0.0, 0.8)
    flow = np.clip(path([(0, 0.0), (0.9, 0.2), (1.5, 1.0), (2.1, 0.55), (dur, 0.0)], dur, 0.1), 0, 1)
    m.add(pour(dur, rng, flow, lo=300, hi=5500, drops=1.0, glugs=1.2, size=1.8, mix=(1.0, 0.5, 0.5)), 0.0, 0.85)
    m.add(bubbles(dur, rng, 40 + 900 * lift ** 1.5, 0.8, 30.0, beta=1.7, level=1.0, weight=0.5, rise=0.2), 0.0, 0.5)
    m.add(bubbles(dur, rng, 8 * lift ** 1.5 + 0.3, 30.0, 110.0, beta=1.5, level=1.0, weight=0.7, rise=0.1), 0.2, 0.7)
    for at, sz, g in [(0.35, 0.8, 0.35), (0.7, 1.1, 0.5), (1.05, 1.4, 0.75), (1.35, 1.6, 0.9), (1.8, 1.3, 0.6), (2.2, 0.9, 0.4)]:
        m.add(hull_slap(rng, sz, 0.9, cav=1.0), at + rng.uniform(-0.04, 0.04), g)
    for at, g in [(1.0, 0.7), (1.55, 0.9)]:
        m.add(knock(rng, rng.uniform(0.6, 0.85), 2.0, 0.5, 0.7, 1.0), at, g)
    m.add(creak_voice(dur, rng, path([(0, 6), (0.9, 22), (1.5, 48), (2.2, 20), (dur, 5)], dur) * spread(dur, rng, 5, 0.08),
                      lift ** 0.7 * 0.8, hull_modes(60, 2400, peaks=(140, 330)),
                      [(path([(0, 160), (1.5, 330), (dur, 190)], dur), 3.5, 0.9)], jitter=0.2, alt=0.25, rub=0.15,
                      kick=0.0012, bright=3000, unsteady=0.4), 0.0, 0.7)
    for tt in beats(path([(0, 4), (1.2, 22), (2.0, 8)], dur), rng, 0.8):
        if tt < dur - 0.3:
            m.add(pop(rng, rng.uniform(140, 520), rng.uniform(0.5, 1.0), 0.8), tt, rng.uniform(0.06, 0.3) * lift[min(n - 1, ns(tt))])
    m.add(splash(1.2, rng, size=3.2, hard=0.7), 1.5, 0.6)
    m.add(spray(1.2, rng, np.exp(-tvec(1.2) / 0.4), 1200, 6000, rate=900), 1.55, 0.2)
    m.add(slosh(1.0, rng, 700, 0.3), 1.9, 0.4)
    for tt in np.sort(rng.uniform(1.6, 2.8, 14)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.1))
    y = fit(m.out(), n)
    return at_sea(hush(y, -5.0, 7000), 0.12, seed=2010)


# =========================================================================== the arms
def flesh_creak(dur, rng, rate, amp, f_lo=110.0, f_hi=1500.0, peaks=(240.0, 520.0), vowel=None, bright=1500.0, rub=0.25,
                unsteady=0.55, jitter=0.34):
    """a great muscle working under its skin: stick-slip friction again (a train of slips), but ringing the dull, short
    modes of wet flesh and not of oak, so that it is a rubbery groan and a squeak and not a creak"""
    modes = timber_modes(rng, f_lo, f_hi, 18, 0.05, 0.012, peaks=peaks)
    return creak_voice(dur, rng, rate, amp, modes, vowel, jitter=jitter, alt=0.2, rub=rub, kick=0.0016, bright=bright,
                       unsteady=unsteady)


def rubber_squeal(dur, rng, f, amp, harmonics=9, tilt=0.9, chatter=0.6, wobble=0.03, grit=0.25, peak_hz=None, shudder=0.035):
    """a squeak of wet skin on tarred wood: the cog's squeal, but thick and shuddering, not a note: more of its harmonics,
    a harder chatter, a pitch that shakes about thirty times a second as the skin catches and lets go, a rub of grit"""
    n = ns(dur)
    f = np.broadcast_to(np.asarray(f, dtype=float), (n,))
    f = f * (1 + shudder * (2 * smooth_random(dur, rng, 33) - 1))
    return squeal(dur, rng, f, amp, harmonics=harmonics, tilt=tilt, chatter=chatter, wobble=wobble, grit=grit, peak_hz=peak_hz)


def slither(dur, rng, env, lo=170.0, hi=1900.0, wave=2.0, grit=0.4):
    """an arm drawn over wet wood or over water: the long wave of its body passing (a band of wet noise that sweeps up and
    down it, louder as each coil presses and drags), the drag of the skin (a rough rasp), the grain of the boards under it,
    the dull pad of the weight of it"""
    n = ns(dur)
    w = 0.5 + 0.5 * np.sin(phase_from_freq(wave * wander(dur, rng, 1.0, 0.25), n) + rng.uniform(0, 6.28))
    fc = lo * (hi / lo) ** (0.25 + 0.55 * w) * wander(dur, rng, 6, 0.1)
    body = rn(tvf(colored(dur, rng, -1.5), "bp", fc, 0.9)) * (0.35 + 0.65 * w) ** 1.5 * lurch(dur, rng, 16, 0.5)
    drag = rn(bp(colored(dur, rng, -1.0), 500, 3200)) * w ** 2 * lurch(dur, rng, 40, 0.6)
    gr = rn(grain_cloud(dur, rng, (60 + 700 * w ** 2) * grit, decay=0.0007, f_lo=700, f_hi=4200, size_var=0.9)) * w
    low = rn(lp(bp(brown(dur, rng), 70, 420), 420)) * (0.4 + 0.6 * w)
    return (body + 0.5 * drag + 0.4 * grit * gr + 0.4 * low) * env


def wet_moves(m, rng, dur, rate, size=1.0, gain=1.0, pops=0.6, squelches=0.5):
    """the small wet noises of an arm moving, scattered over a Bus: suckers letting go (rate a second, a number or a curve),
    flesh squeezing"""
    n = ns(dur)
    r = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    for tt in events(dur, rng, r, shape=1.3):
        k = r[min(n - 1, ns(tt))] / max(np.max(r), 1e-6)
        if rng.random() < pops:
            m.add(sucker_pop(rng, size * rng.uniform(0.8, 1.6), rng.uniform(0.4, 1.0)), tt, gain * rng.uniform(0.12, 0.4) * (0.4 + k))
        if rng.random() < squelches:
            m.add(squelch(rng, size * rng.uniform(0.7, 1.6), 0.2, rng.uniform(0.6, 1.6)), tt + rng.uniform(0, 0.04),
                  gain * rng.uniform(0.15, 0.45) * (0.4 + k))


def arm_whoosh(dur, rng, peak=0.4, lo=250.0, hi=2200.0, power=2.2, flutter=18.0, body=0.6):
    """a heavy limb swung through the air: a bellying rush of air pushed aside (a band that sweeps up with its speed), the
    dull body of the displaced air under it, flutter as the wet skin and the suckers tear the air"""
    return whoosh(dur, rng, peak=peak, f_lo=lo, f_hi=hi, q=0.9, power=power, rise=1.6, fall=1.4, body=body, body_lp=300.0,
                  flutter_hz=flutter, flutter_depth=0.3)


def surface_burst(dur, rng, size=1.0, shed=0.6, arms=1):
    """an arm tearing up through the surface beside the ship: the sea bulging and breaking (a smack of broken water, a rush of
    it, the hollow punched under the column), the arm swung up through the air (a heavy whoosh), the suckers and wet flesh
    flexing, the water sheeting off it in a long pour that thins as it drains, the big drops falling back, the swell slapping
    the hull"""
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(smack(rng, 1.5 * size, 1.2), 0.0, 0.8)
    et = tvec(0.5)
    m.add(bp(white(0.5, rng), 150, 4200) * np.exp(-et / (0.12 * size ** 0.5)) * np.clip(et / 0.004, 0, 1), 0.0, 0.85)
    m.add(saturate(thump(0.5, 118 / size ** 0.3, 44, rng, decay=0.1 * size ** 0.5, click=0.0, noise=0.6, nlp=380), 2.2), 0.0, 0.8)
    col = np.exp(-t / (0.5 * size ** 0.5)) * np.clip(t / 0.01, 0, 1)
    m.add(boil(dur, rng, col, 90.0, 1200.0, 1.0), 0.0, 0.7)
    for k in range(arms):
        m.add(arm_whoosh(0.5 * size ** 0.3, rng, 0.35, 250, 2400, 2.2, 18, 0.7), 0.05 + 0.2 * k, 0.65)
    wet_moves(m, rng, 0.9, path([(0, 24), (0.3, 18), (0.9, 4)], 0.9), size=size * 1.2, gain=0.8)
    flow = col ** (0.7 / shed)
    m.add(pour(dur, rng, flow, lo=320, hi=5500, drops=1.0, glugs=1.1, size=1.7 * size, mix=(1.0, 0.5, 0.5)), 0.0, 0.95)
    m.add(spray(dur, rng, flow ** 0.8, 1200, 6000, rate=1200), 0.02, 0.28)
    m.add(hull_slap(rng, 1.1 * size, 0.9, cav=1.0), 0.2, 0.6)
    m.add(hull_slap(rng, 0.8 * size, 0.8, cav=1.0), 0.5, 0.4)
    m.add(hull_slap(rng, 0.7 * size, 0.7, cav=0.8), 0.95, 0.25)
    m.add(slosh(0.9, rng, 800, 0.3), 0.3, 0.35)
    m.add(flesh_creak(0.9, rng, path([(0, 14), (0.4, 30), (0.9, 9)], 0.9), bell(0.9, 0.4, 1.4, 1.5), peaks=(200, 440)), 0.1, 0.35)
    for tt in np.sort(rng.uniform(0.25, dur - 0.1, int(14 + 8 * size))):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.12) * np.exp(-tt / 1.2))
    return fit(m.out(), n)


@recipe("maelgrasp_burst", variants=2, peak_db=-3.0, fade_out=0.4, max_len=[1.9, 2.3], tail_db=-62.0, loud=-11.5, limit_db=9.0,
        subtitle="A tentacle bursts from the sea", reach=64)
def maelgrasp_burst(v, rng):
    """an arm bursting out of the sea with the water streaming off it: 1 one arm tearing up beside the hull, the broken water,
    the whoosh of it climbing, suckers popping as it flexes, the sheet of water pouring off and the swell slapping the planks;
    2 a bigger, slower eruption, two arms one after the other, the column of water falling back with a great splash"""
    if v == 0:
        y = surface_burst(1.9, rng, 1.0, 0.6, 1)
    else:
        m = Bus(2.3)
        m.add(surface_burst(2.3, rng, 1.3, 0.8, 2), 0.0, 1.0)
        m.add(splash(1.4, rng, size=3.5, hard=0.8), 0.7, 0.7)
        m.add(hull_slap(rng, 1.4, 1.0, cav=1.0), 0.85, 0.6)
        y = m.out()
    return at_sea(hush(y, -5.0, 7500), 0.1, seed=2020 + v)


@recipe("maelgrasp_writhe", variants=3, peak_db=-3.0, fade_out=0.3, max_len=[1.8, 2.0, 2.3], tail_db=-60.0, loud=-17.0,
        limit_db=8.0, subtitle="A tentacle writhes", reach=32)
def maelgrasp_writhe(v, rng):
    """an idle arm, wet slithering and the pop of suckers: 1 a slow coil drawn over wet boards, a few squelches and a long
    peeling of suckers at the end; 2 a run of suckers letting go one after the other, a pattering of pops, over a quiet
    slither; 3 a heavier thrash, the tip slapping the boards twice and the coil dragging after it, deep squelches"""
    dur = [1.8, 2.0, 2.3][v]
    n = ns(dur)
    m = Bus(dur)
    if v == 0:
        env = bell(dur, 0.45, 1.3, 1.4)
        m.add(slither(dur, rng, env, wave=1.6), 0.0, 1.0)
        wet_moves(m, rng, dur, 5 + 10 * env, size=1.1, gain=1.0)
        for k in range(7):
            m.add(sucker_pop(rng, rng.uniform(0.9, 1.5), 0.6), 1.2 + 0.07 * k * (1 + 0.3 * rng.random()), 0.2 * (1 - k / 9))
    elif v == 1:
        env = bell(dur, 0.55, 1.4, 1.2) * 0.7
        m.add(slither(dur, rng, env, lo=140, hi=1400, wave=1.1, grit=0.2), 0.0, 0.6)
        at, gap = 0.2, 0.17
        while at < dur - 0.3:
            m.add(sucker_pop(rng, rng.uniform(0.9, 1.7), rng.uniform(0.5, 1.0)), at, rng.uniform(0.3, 0.7))
            if rng.random() < 0.4:
                m.add(squelch(rng, rng.uniform(0.7, 1.2), 0.18, 1.2), at + 0.01, 0.25)
            at += gap * rng.uniform(0.6, 1.3)
            gap *= 0.93
    else:
        env = bell(dur, 0.4, 1.2, 1.5)
        m.add(slither(dur, rng, env, lo=130, hi=1700, wave=2.4), 0.0, 0.9)
        for at, s, g in [(0.15, 1.0, 1.0), (0.62, 0.8, 0.7)]:
            m.add(body_slap(rng, s * 1.3, 0.9, 0.5), at, g)
        wet_moves(m, rng, dur, 6 + 14 * env, size=1.5, gain=1.0, squelches=0.7)
        m.add(flesh_creak(dur, rng, path([(0, 8), (1.0, 26), (dur, 6)], dur), env, peaks=(180, 420)), 0.0, 0.4)
    for tt in np.sort(rng.uniform(0.2, dur - 0.1, 6)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.08))
    return at_sea(hush(fit(m.out(), n), -4.0, 7500), 0.06, seed=2030 + v)


@recipe("maelgrasp_rear", peak_db=-3.0, fade_out=0.4, max_len=2.5, tail_db=-62.0, loud=-15.0, limit_db=8.0,
        subtitle="A tentacle rears up", reach=48, sat=1.4)
def maelgrasp_rear(v, rng):
    """an arm rearing high over the deck, the water streaming off it and the muscle creaking: water pouring off in a sheet
    that thins as it climbs, the air parted by the swelling mass of it (a slow, deep whoosh), the long wet squeak and the
    rubbery ratchet of muscle taking the weight, suckers peeling, a held creak at the very top as it stops, and the rain of
    drops falling back on the boards and the sea"""
    dur = 2.5
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    flow = np.clip(path([(0, 0.0), (0.12, 0.95), (0.5, 0.9), (1.2, 0.5), (1.8, 0.2), (dur, 0.05)], dur, 0.1), 0, 1)
    m.add(pour(dur, rng, flow, lo=300, hi=5000, drops=1.0, glugs=1.0, size=1.5, mix=(1.0, 0.5, 0.5)), 0.0, 1.0)
    m.add(smack(rng, 1.2, 1.0), 0.0, 0.5)
    m.add(arm_whoosh(1.5, rng, 0.55, 140, 1100, 2.0, 11, 0.9), 0.15, 0.7)
    up = bell(dur, 0.6, 1.4, 1.3)
    m.add(flesh_creak(dur, rng, path([(0, 7), (0.8, 16), (1.6, 28), (1.85, 34), (dur, 6)], dur) * spread(dur, rng, 5, 0.12),
                      up, vowel=[(path([(0, 190), (1.7, 420), (dur, 260)], dur), 4.0, 0.9)], peaks=(190, 420), rub=0.3), 0.0, 0.9)
    m.add(nrm(squeal(0.9, rng, path([(0, 360), (0.8, 640), (0.9, 520)], 0.9), bell(0.9, 0.6, 1.4, 1.2), harmonics=6, tilt=1.3,
                     chatter=0.5, wobble=0.02, grit=0.1, peak_hz=900)), 0.9, 0.2)
    wet_moves(m, rng, 1.6, path([(0, 20), (0.8, 12), (1.6, 2)], 1.6), size=1.3, gain=0.8, squelches=0.4)
    m.add(hull_slap(rng, 1.0, 0.9, cav=1.0), 0.18, 0.5)
    m.add(slosh(1.2, rng, 800, 0.2), 0.2, 0.3)
    m.add(oak(0.3, rng, 175, t60=0.1, hard=0.5, lo=140), 1.85, 0.25)  # the tick of tension at the top
    rain = np.exp(-np.clip(t - 1.0, 0, None) / 0.7) * np.clip((t - 0.1) / 0.4, 0, 1)
    m.add(grain_cloud(dur, rng, 160 * rain + 4, decay=0.0006, f_lo=1000, f_hi=6000) * rain, 0.0, 0.25)
    for tt in np.sort(rng.uniform(0.3, dur - 0.1, 22)):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.12) * (0.2 + rain[min(n - 1, ns(tt))]))
    for tt in np.sort(rng.uniform(1.9, 2.4, 3)):
        m.add(body_slap(rng, 0.25, 0.4, 0.2), tt, 0.15)
    return at_sea(hush(fit(m.out(), n), -4.0, 7500), 0.1, seed=2040)


def deck_slam(dur, rng, size=1.0, hard=1.0, split=0.0):
    """an arm as thick as a mast coming down across the deck: the wet slap of it (flesh flat on boards), the deck booming
    like a drum over the hold under it, the planking answering, cracks all over as the grain gives (a run of timber pops of
    every size, thinning as the deck settles), a rip of timber tearing along the grain, a spray of water and splinters thrown
    up and raining down, the whole hull groaning after it and the rigging ringing, things on deck knocked loose"""
    n = ns(dur)
    m = Bus(dur)
    # the blow
    m.add(body_slap(rng, 2.6 * size, hard, 0.6), 0.0, 1.2)
    bt = tvec(0.5)
    m.add(bp(white(0.5, rng), 120, 2600) * np.exp(-bt / 0.04) * np.clip(bt / 0.001, 0, 1), 0.0, 1.0)
    m.add(hann_burst(0.006, rng, 400, 6000), 0.0, 0.3 * hard)
    # the deck as a drum over the hold: the boom, the hollow punched in the planking, the planks answering
    boom_t = tvec(1.0)
    m.add(saturate(sine_sweep(1.0, 98 / size ** 0.2, 40, 3.0) * np.exp(-boom_t / (0.3 * size ** 0.4)), 2.2), 0.0, 0.8)
    m.add(saturate(sine_sweep(0.4, 190, 80, 4.0) * np.exp(-tvec(0.4) / 0.07), 2.6), 0.0, 0.6)
    m.add(knock(rng, 0.62 / size ** 0.15, 2.4 * size, 0.7 * hard, 0.9, 1.0), 0.0, 0.9)
    m.add(oak(0.6, rng, 115 / size ** 0.2, t60=0.25 * size ** 0.4, hard=0.9, lo=70), 0.0, 0.9)
    m.add(oak(0.4, rng, rng.uniform(180, 210), t60=0.12, hard=1.0, lo=120), 0.004, 0.5)
    # the grain giving: a run of cracks of every size, thinning
    at = 0.004
    for k in range(int(16 + 8 * split)):
        at += rng.exponential(0.03) * (1 + split)
        m.add(pop(rng, rng.uniform(110, 1100), rng.uniform(0.4, 1.0) * size ** 0.3, 1.0), at,
              rng.uniform(0.15, 0.55) * np.exp(-at / 0.5))
    rd = 0.55 + 0.4 * split
    m.add(creak_voice(rd, rng, path([(0, 130), (0.1, 150), (0.3, 80), (rd, 25)], rd) * spread(rd, rng, 8, 0.1),
                      bell(rd, 0.15, 1.2, 1.6), hull_modes(70, 3600, peaks=(150, 340)),
                      [(path([(0, 500), (rd, 900)], rd), 3.5, 0.7)], jitter=0.35, alt=0.3, rub=0.4, kick=0.0009, bright=5200,
                      unsteady=0.5), 0.01, 0.55 + 0.4 * split)
    st = tvec(0.9)
    m.add(grain_cloud(0.9, rng, 1100 * np.exp(-st / 0.25) + 10, decay=0.0007, f_lo=800, f_hi=5500, size_var=1.0), 0.02, 0.45)
    sd = 1.1
    sh = np.exp(-tvec(sd) / 0.28) * np.clip(tvec(sd) / 0.01, 0, 1)
    m.add(pour(sd, rng, sh, lo=450, hi=6000, drops=1.0, glugs=0.5, size=0.8, mix=(1.0, 0.6, 0.3)), 0.0, 0.5)
    m.add(spray(sd, rng, sh ** 0.8, 1500, 7000, rate=1000), 0.02, 0.25)
    m.add(squelch(rng, 2.6, 0.4, -0.3), 0.03, 0.4)
    # the hull taking the weight: two groans, a mid one and a low one, dying away over the settling deck
    gd = dur - 0.12
    gt = tvec(gd)
    m.add(creak_voice(gd, rng, path([(0, 12), (0.3, 30), (0.9, 18), (gd, 5)], gd) * spread(gd, rng, 6, 0.12),
                      np.exp(-gt / 0.75) * np.clip(gt / 0.02, 0, 1), hull_modes(60, 2400, peaks=(130, 300)),
                      [(path([(0, 150), (gd, 260)], gd), 3.5, 0.9)], jitter=0.4, alt=0.25, kick=0.0014, bright=2400,
                      unsteady=0.6), 0.12, 0.6)
    m.add(deep_creak(gd, rng, path([(0, 7), (0.5, 15), (1.0, 8), (gd, 3)], gd) * spread(gd, rng, 6, 0.15),
                     bell(gd, 0.2, 1.4, 1.2), f_lo=34, f_hi=500, peaks=(70, 150), jitter=0.55, alt=0.3, bright=900.0,
                     unsteady=0.6), 0.12, 0.4)
    # the rigging rings, ropes and blocks thrown about
    for at, f0, g in [(0.02, 520, 0.2), (0.07, 760, 0.17), (0.14, 430, 0.15)]:
        m.add(modal(0.9, f0 * np.array([1, 2.31, 3.87, 5.4]), [0.7, 0.45, 0.3, 0.2], [1, 0.5, 0.3, 0.15], rng, glide=0.06), at, g)
    for at in np.sort(rng.uniform(0.35, dur - 0.2, 6)):
        m.add(knock(rng, rng.uniform(1.0, 1.7), 0.6, 0.5, 0.3, 0.4), at, rng.uniform(0.08, 0.25))
    for at in np.sort(rng.uniform(0.5, dur - 0.2, 8)):
        m.add(pop(rng, rng.uniform(150, 700), rng.uniform(0.3, 0.7), 0.8), at, rng.uniform(0.05, 0.16))
    for tt in np.sort(rng.uniform(0.2, dur - 0.1, 14)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09))
    return fit(m.out(), n)


@recipe("maelgrasp_slam", variants=2, peak_db=-3.0, fade_out=0.4, max_len=[1.8, 2.1], tail_db=-62.0, loud=[-10.8, -10.5],
        limit_db=14.0, subtitle="A tentacle slams the deck", reach=64, sat=1.8)
def maelgrasp_slam(v, rng):
    """an arm as thick as a mast slamming down on the deck, a huge wet impact with the timber cracking: 1 the full weight of
    it flat across the boards, the slap and the boom of the deck, a run of cracks, water and splinters thrown up and raining
    down, the hull groaning and the rigging ringing; 2 it lands and the planks split, a great crack and a long rip of timber
    tearing along the grain, then the wet drag of the arm pulling clear"""
    if v == 0:
        y = deck_slam(1.8, rng, 1.0, 1.0, 0.0)
    else:
        m = Bus(2.1)
        m.add(deck_slam(2.1, rng, 1.15, 1.0, 1.0), 0.0, 1.0)
        m.add(hann_burst(0.01, rng, 500, 8000), 0.03, 0.4)
        m.add(oak(0.6, rng, 118, t60=0.25, hard=1.0, lo=80), 0.03, 0.8)
        pdur = 0.7
        m.add(slither(pdur, rng, bell(pdur, 0.4, 1.3, 1.4), 150, 1600, 1.8, 0.8), 0.9, 0.35)
        m.add(creak_voice(0.5, rng, path([(0, 60), (0.15, 110), (0.5, 20)], 0.5), bell(0.5, 0.3, 1.2, 1.5),
                          hull_modes(80, 4000, peaks=(160, 380)), None, jitter=0.35, alt=0.3, rub=0.5, kick=0.0008,
                          bright=6000, unsteady=0.5), 0.08, 0.5)
        y = m.out()
    return at_sea(hush(y, -4.0, 8500), 0.1, seed=2050 + v)


def sucker_grip(rng, size=1.0, hard=1.0):
    """a sucker clamping down: the rim meets the wood with a soft 'tup', the cup's air is squeezed out and the pitch of it
    falls as the flesh seals"""
    m = Bus(0.2)
    m.add(squelch(rng, size, 0.16, -0.45), 0.0, 1.0)
    et = tvec(0.03)
    m.add(bp(white(0.03, rng), 200, 1800) * np.exp(-et / 0.006) * np.clip(et / 0.0006, 0, 1), 0.0, 0.5 * hard)
    return fit(m.out(), ns(0.2))


@recipe("maelgrasp_grab", peak_db=-3.0, fade_out=0.25, max_len=1.3, tail_db=-60.0, loud=-13.5, limit_db=8.0,
        subtitle="A tentacle grabs", reach=32)
def maelgrasp_grab(v, rng):
    """suckers clamping down: the arm landing across the rail (a heavy wet slap), a cascade of suckers taking hold one
    after another, each a squelch that falls as the cup seals and a soft tup, the flesh squeezing tight round the timber with a
    rubbery squeak and the rail complaining under it, a last long squelch as the grip settles"""
    dur = 1.3
    n = ns(dur)
    m = Bus(dur)
    m.add(body_slap(rng, 1.6, 0.9, 0.5), 0.0, 1.0)
    m.add(knock(rng, 0.9, 1.0, 0.4, 0.4, 0.8), 0.0, 0.5)
    at = 0.045
    for k in range(16):
        m.add(sucker_grip(rng, rng.uniform(0.9, 1.7), 0.8), at, rng.uniform(0.2, 0.55) * (1 - 0.3 * k / 16))
        if k % 3 == 1:
            m.add(sucker_pop(rng, rng.uniform(1.0, 1.6), 0.5), at + 0.012, 0.18)
        at += rng.uniform(0.018, 0.04) * (1 + 0.04 * k)
    gd = 0.8
    m.add(flesh_creak(gd, rng, path([(0, 10), (0.3, 30), (0.8, 12)], gd), bell(gd, 0.4, 1.3, 1.5), peaks=(260, 560)), 0.2, 0.55)
    m.add(nrm(squeal(0.5, rng, path([(0, 520), (0.25, 760), (0.5, 640)], 0.5), bell(0.5, 0.4, 1.4, 1.4), harmonics=5, tilt=1.4,
                     chatter=0.6, wobble=0.02, grit=0.1, peak_hz=1100)), 0.25, 0.22)
    m.add(creak_voice(0.9, rng, path([(0, 6), (0.4, 18), (0.9, 8)], 0.9), bell(0.9, 0.35, 1.3, 1.5), hull_modes(80, 1800, peaks=(160, 360)),
                      [(path([(0, 170), (0.9, 260)], 0.9), 3.5, 0.8)], jitter=0.2, alt=0.2, kick=0.0014, bright=2200, unsteady=0.4),
          0.15, 0.5)
    m.add(squelch(rng, 1.8, 0.4, 0.5), 0.75, 0.55)
    m.add(grain_cloud(0.9, rng, 120 * np.exp(-tvec(0.9) / 0.35) + 8, decay=0.0006, f_lo=900, f_hi=5000), 0.05, 0.12)
    for tt in np.sort(rng.uniform(0.2, 1.2, 6)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.08))
    return at_sea(hush(fit(m.out(), n), -4.0, 7500), 0.05, seed=2060)


@recipe("maelgrasp_sweep", peak_db=-3.0, fade_out=0.3, max_len=1.7, tail_db=-60.0, loud=-13.5, limit_db=8.0,
        subtitle="A tentacle sweeps the deck", reach=48)
def maelgrasp_sweep(v, rng):
    """an arm swept across the deck, a heavy wet whoosh with the timber scraping: the weight of air pushed aside as the arm
    comes round (a deep rush that swells and falls), its wet skin dragging over the planks (a rasp and a slither), a long
    scrape of timber as it takes the cleats, the rail and a barrel or two along, knocks and rattles in its wake, water
    shed along the boards"""
    dur = 1.7
    n = ns(dur)
    m = Bus(dur)
    m.add(arm_whoosh(1.1, rng, 0.45, 120, 1500, 2.0, 12, 1.0), 0.0, 1.0)
    env = bell(dur, 0.42, 1.3, 1.4)
    m.add(slither(dur, rng, env, 200, 2600, 3.0, 0.8), 0.0, 0.7)
    sd = 1.2
    m.add(creak_voice(sd, rng, path([(0, 60), (0.3, 130), (0.55, 170), (0.9, 110), (sd, 30)], sd) * spread(sd, rng, 9, 0.12),
                      bell(sd, 0.38, 1.3, 1.5), hull_modes(90, 3400, peaks=(190, 430)),
                      [(path([(0, 320), (0.6, 620), (sd, 400)], sd), 3.0, 0.7)], jitter=0.3, alt=0.2, rub=0.45, kick=0.0009,
                      bright=4800, unsteady=0.5), 0.15, 0.8)
    m.add(grain_cloud(dur, rng, 700 * env + 10, decay=0.0006, f_lo=700, f_hi=4800, size_var=1.0) * env, 0.0, 0.3)
    for at, f0, g in [(0.35, 1.0, 0.7), (0.52, 1.3, 0.55), (0.66, 0.8, 0.8), (0.9, 1.1, 0.45)]:
        m.add(knock(rng, f0, rng.uniform(0.5, 1.1), 0.7, 0.4, 0.6), at + rng.uniform(-0.02, 0.02), g * 0.7)
        m.add(oak(0.25, rng, rng.uniform(300, 700), t60=0.08, hard=0.9, lo=200), at + 0.005, g * 0.4)
    for at in np.sort(rng.uniform(0.5, 1.5, 7)):
        m.add(strike(0.12, rng, rng.uniform(500, 1500) * np.array([1.0, 1.7]), [0.03, 0.02], [1, 0.5], lo=300, hi=5000, ex=0.001),
              at, rng.uniform(0.06, 0.2))
    m.add(flesh_creak(1.0, rng, path([(0, 12), (0.5, 28), (1.0, 8)], 1.0), bell(1.0, 0.4, 1.3, 1.5), peaks=(200, 440)), 0.1, 0.4)
    m.add(slosh(1.0, rng, 900, 0.3), 0.3, 0.3)
    m.add(grain_cloud(dur, rng, 220 * env + 5, decay=0.0006, f_lo=1200, f_hi=6500), 0.0, 0.15)
    for tt in np.sort(rng.uniform(0.3, dur - 0.1, 12)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09))
    return at_sea(hush(fit(m.out(), n), -4.0, 8000), 0.08, seed=2070)


@recipe("maelgrasp_squeeze", peak_db=-3.0, fade_out=0.35, max_len=2.0, tail_db=-60.0, loud=-15.5, limit_db=8.0,
        subtitle="A tentacle squeezes", reach=32)
def maelgrasp_squeeze(v, rng):
    """an arm tightening round the ship, flesh creaking: the strain taking up in the planking (a groan that climbs as the
    load comes on), the muscle working (a rubbery ratchet of stick-slip), long wet squeals of skin dragging on tarred oak,
    timber fibres giving in small pops, water squeezed out of the flesh in bubbles and squirts, a tremor of the whole at
    the height of it"""
    dur = 2.0
    n = ns(dur)
    m = Bus(dur)
    load = np.clip(path([(0, 0.1), (0.5, 0.35), (1.2, 0.8), (1.7, 1.0), (dur, 0.7)], dur, 0.1), 0, 1)
    m.add(creak_voice(dur, rng, path([(0, 7), (0.6, 13), (1.2, 22), (1.7, 32), (dur, 18)], dur) * spread(dur, rng, 5, 0.12),
                      load ** 0.8, hull_modes(60, 2200, peaks=(120, 280)),
                      [(path([(0, 130), (1.2, 260), (1.8, 340), (dur, 240)], dur), 3.5, 0.9)], jitter=0.34, alt=0.3, rub=0.2,
                      kick=0.0014, bright=2400, unsteady=0.55), 0.0, 1.0)
    m.add(flesh_creak(dur, rng, path([(0, 8), (0.8, 18), (1.7, 34), (dur, 14)], dur) * spread(dur, rng, 4, 0.12), load,
                      vowel=[(path([(0, 200), (1.7, 520), (dur, 330)], dur), 4.0, 0.9)], peaks=(220, 480), rub=0.3), 0.0, 0.8)
    for at, f1, f2, d in [(0.25, 230, 360, 0.7), (0.95, 280, 520, 0.8), (1.5, 340, 640, 0.5)]:
        m.add(nrm(squeal(d, rng, path([(0, f1), (d, f2)], d), bell(d, 0.55, 1.4, 1.4) * load[min(n - 1, ns(at))], harmonics=7,
                         tilt=1.2, chatter=0.55, wobble=0.025, grit=0.1, peak_hz=f2 * 2.2)), at, 0.3)
    for tt in beats(path([(0, 2), (1.0, 6), (1.8, 18), (dur, 6)], dur), rng, 0.8):
        if tt < dur - 0.1:
            m.add(pop(rng, rng.uniform(150, 650), rng.uniform(0.4, 0.9), 0.6), tt, rng.uniform(0.05, 0.3) * (0.3 + load[min(n - 1, ns(tt))]))
    m.add(bubbles(dur, rng, 5 + 140 * load ** 2, 1.5, 9.0, beta=1.8, level=1.0, weight=0.5, rise=0.3) * load, 0.0, 0.3)
    wet_moves(m, rng, dur, 3 + 8 * load, size=1.4, gain=0.6, pops=0.2, squelches=0.9)
    m.add(knock(rng, 0.75, 1.6, 0.4, 0.5, 1.0), 1.65, 0.45)
    td = 0.5
    ttt = tvec(td)
    m.add(bp(colored(td, rng, -2.0), 80, 700) * (0.5 + 0.5 * np.sin(2 * np.pi * 9 * ttt)) * bell(td, 0.5, 1.2, 1.2), 1.5, 0.4)
    return at_sea(hush(fit(m.out(), n), -4.0, 7000), 0.08, seed=2080)


def whip_crack(rng, hard=1.0):
    """the crack of a limb thrown faster than sound: a hair-thin, hard burst across the whole band (the little shock wave of
    the tip), a ring in the air after it, the dull slap of the body behind"""
    m = Bus(0.3)
    m.add(hann_burst(0.0012, rng, 1800, 9500), 0.0, 1.0 * hard)
    m.add(bp(white(0.06, rng), 900, 4800) * np.exp(-tvec(0.06) / 0.012) * np.clip(tvec(0.06) / 0.0005, 0, 1), 0.0004, 0.5)
    m.add(strike(0.2, rng, [1500, 2700, 4300], [0.03, 0.02, 0.012], [1, 0.6, 0.3], lo=800, hi=8000, ex=0.0006), 0.0, 0.3)
    m.add(saturate(thump(0.15, 220, 90, rng, decay=0.03, click=0.0, noise=0.6, nlp=600), 2.5), 0.0, 0.45)
    return fit(m.out(), ns(0.3))


@recipe("maelgrasp_hurl", peak_db=-3.0, fade_out=0.3, max_len=1.6, tail_db=-60.0, loud=-13.0, limit_db=9.0,
        subtitle="A tentacle hurls", reach=40)
def maelgrasp_hurl(v, rng):
    """a whip-crack fling: the suckers letting go with a run of pops, the arm gathering itself (a slow groan of muscle) and
    thrown, a whoosh that climbs fast in pitch and weight as the speed comes on, the crack of the tip at the top of it, and
    the rush falling away as the thing flung goes, water shaken off the arm"""
    dur = 1.6
    n = ns(dur)
    m = Bus(dur)
    at = 0.0
    for k in range(11):
        m.add(sucker_pop(rng, rng.uniform(0.9, 2.0), rng.uniform(0.5, 1.0)), at, rng.uniform(0.15, 0.55) * (1 - k / 16))
        at += rng.exponential(0.028) + 0.008
    m.add(squelch(rng, 1.5, 0.3, 1.2), 0.04, 0.5)
    m.add(flesh_creak(0.5, rng, path([(0, 12), (0.25, 38), (0.5, 70)], 0.5), np.linspace(0.3, 1.0, ns(0.5)), peaks=(240, 520)), 0.0, 0.45)
    wd = 0.62
    wt = tvec(wd)
    u = wt / wd
    m.add(tvf(colored(wd, rng, -1.5), "bp", 220 * (3600 / 220) ** u ** 1.6, 0.9) * u ** 2.2 * lurch(wd, rng, 40, 0.3)
          + 0.6 * lp(bp(brown(wd, rng), 80, 500), 500) * u ** 1.5, 0.12, 0.9)
    crack = 0.72
    m.add(whip_crack(rng, 1.0), crack, 1.0)
    fd = 0.8
    ft = tvec(fd)
    uf = ft / fd
    m.add(tvf(colored(fd, rng, -1.5), "bp", 3000 * (260 / 3000) ** uf ** 0.7, 0.9) * (1 - uf) ** 2.0 * np.clip(ft / 0.01, 0, 1)
          * lurch(fd, rng, 30, 0.4), crack + 0.02, 0.55)
    m.add(pour(0.9, rng, np.exp(-tvec(0.9) / 0.25) * np.clip(tvec(0.9) / 0.01, 0, 1), lo=500, hi=5000, drops=1.0, glugs=0.3,
               size=0.7, mix=(0.8, 0.6, 0.3)), crack, 0.3)
    m.add(slosh(0.8, rng, 800, 0.2), 0.8, 0.2)
    for tt in np.sort(rng.uniform(0.75, 1.5, 9)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09))
    return at_sea(hush(fit(m.out(), n), -3.0, 9000), 0.08, seed=2090)


@recipe("maelgrasp_arm_hurt", variants=2, peak_db=-3.0, fade_out=0.25, max_len=[1.0, 1.2], tail_db=-60.0, loud=-14.5, limit_db=9.0,
        subtitle="A tentacle shudders", reach=32)
def maelgrasp_arm_hurt(v, rng):
    """a shudder hissing through the water: the blow landing in flesh (a wet slap and a squelch), the hurt arm trembling (a
    hiss that falls from bright to dull and is chopped by an uneven quiver that slows as it goes, a soft low thump of the
    muscle at every shake, the thumps running together into a flutter), bubbles hissing out of it and fizzing, a wet rubbery
    squeak. 1 a long shiver and a slump; 2 a sharp flinch, a quick whip back and the shiver after it"""
    dur = [1.0, 1.2][v]
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    m = Bus(dur)
    lead = 0.0 if v == 0 else 0.12
    m.add(body_slap(rng, 1.3, 0.8, 0.3), lead, 0.8)
    m.add(squelch(rng, 1.5, 0.3, 0.8), lead + 0.008, 0.6)
    qr = (21.0 - 11.0 * u) * wander(dur, rng, 2.5, 0.25)
    quiver = (0.5 + 0.5 * np.sin(phase_from_freq(qr, n) + rng.uniform(0, 6.28))) ** 1.6
    quiver = 0.3 + 0.7 * quiver * (0.6 + 0.4 * smooth_random(dur, rng, 14))
    env = np.clip((t - lead) / 0.025, 0, 1) * np.exp(-np.clip(t - lead, 0, None) / ((0.55 if v == 0 else 0.5) * dur))
    fc = 3400 * (800 / 3400) ** (u ** 0.8)
    m.add(tvf(colored(dur, rng, -1.0), "bp", fc, 1.0) * quiver * env, 0.0, 1.0)
    # a thump of the muscle at every shake, each a different pitch and weight
    for i in np.nonzero(np.diff(np.floor(np.cumsum(qr) / SR)) > 0)[0]:
        tt = (i + 1) / SR
        if tt < lead + 0.02 or tt > dur - 0.12:
            continue
        g = float(env[i]) * rng.uniform(0.4, 1.0)
        m.add(saturate(thump(0.12, rng.uniform(78, 125), rng.uniform(46, 64), rng, decay=0.03, click=0.0, noise=0.6, nlp=320), 1.6),
              tt, 0.55 * g)
    m.add(bubbles(dur, rng, 700 * env + 6, 0.5, 4.0, beta=1.9, level=1.0, weight=0.4) * (0.4 + 0.6 * quiver), 0.0, 0.5)
    m.add(gargle(dur, rng, 24 * (1 - 0.4 * u), 3.0, 10.0, 0.8, 3.0, 0.5, 0.14, env), 0.0, 0.5)
    m.add(flesh_creak(dur, rng, path([(0, 30), (dur * 0.5, 18), (dur, 8)], dur), env, peaks=(260, 600)), 0.0, 0.45)
    m.add(nrm(rubber_squeal(0.35, rng, path([(0, 700), (0.35, 420)], 0.35), bell(0.35, 0.3, 1.4, 1.4), harmonics=7, tilt=1.2,
                            peak_hz=1300)), 0.03 + lead, 0.18)
    if v == 1:
        m.add(arm_whoosh(0.25, rng, 0.4, 250, 2600, 2.0, 25, 0.4), 0.0, 0.6)
        m.add(body_slap(rng, 0.9, 0.7, 0.3), 0.3, 0.35)
    for tt in np.sort(rng.uniform(0.15, dur - 0.1, 6)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.08))
    return at_sea(hush(fit(m.out(), n), -4.0, 7500), 0.05, seed=2100 + v)


def chop(rng, size=1.0, hard=1.0):
    """a heavy blade going through a limb: the edge parting skin (a hair of a tick), the meaty thunk of the blow, the wet slap
    of the flesh opening, the gristle and cartilage crunching (a rattle of little cracks and a short bony snap), a squelch as
    the two halves part, the blade on the timber under it"""
    m = Bus(0.5)
    m.add(hann_burst(0.002, rng, 1500, 6500), 0.0, 0.2 * hard)
    m.add(saturate(thump(0.25, 170 / size ** 0.3, 64, rng, decay=0.06 * size ** 0.5, click=0.0, noise=0.5, nlp=650), 2.2), 0.003, 0.9)
    et = tvec(0.1)
    m.add(bp(white(0.1, rng), 150, 2600) * np.exp(-et / 0.03) * np.clip(et / 0.001, 0, 1), 0.003, 0.8)
    ct = tvec(0.14)
    m.add(grain_cloud(0.14, rng, 900 * np.exp(-ct / 0.05) + 40, decay=0.001, f_lo=450, f_hi=3300, size_var=1.0), 0.01, 0.55)
    m.add(strike(0.12, rng, np.array([760, 1350, 2300]) * rng.uniform(0.9, 1.1), [0.02, 0.014, 0.009], [1, 0.6, 0.35], lo=500,
                 hi=6000, ex=0.0008), 0.012, 0.35 * hard)
    m.add(squelch(rng, 1.2 * size, 0.25, 0.9), 0.02, 0.55)
    m.add(oak(0.25, rng, rng.uniform(220, 300), t60=0.07, hard=0.9, lo=180), 0.05, 0.4)
    return fit(m.out(), ns(0.5))


def gush(dur, rng, flow, pulse=3.4, depth=0.5, size=1.0):
    """a great vessel pumping out: a pour whose flow beats with the heart (pulse Hz, depth), glugging and spattering"""
    n = ns(dur)
    t = tvec(dur)
    ph = rng.uniform(0, 6.28)
    beat = (1 - depth) + depth * (0.5 + 0.5 * np.sin(phase_from_freq(pulse * (1 - 0.3 * t / dur), n) + ph)) ** 1.5
    return pour(dur, rng, np.asarray(flow) * beat, lo=300, hi=5000, drops=1.1, glugs=1.5, size=1.5 * size, mix=(1.0, 0.5, 0.6))


@recipe("maelgrasp_sever", variants=2, peak_db=-3.0, fade_out=0.3, max_len=[1.4, 1.7], tail_db=-60.0, loud=-13.0, limit_db=9.0,
        subtitle="A tentacle is severed", reach=48)
def maelgrasp_sever(v, rng):
    """a wet chop and a gush: the blade through the arm (thunk, gristle crunching, the halves parting), blood and sea water
    pumping out in a gush that beats with the heart and thins, the cut-off length slapping the boards and twitching (suckers
    popping, a slither) while the stump recoils with a rubbery squeal. 1 one clean blow; 2 two blows (the first not enough),
    a heavier gush, and the cut end rolling off into the sea"""
    dur = [1.4, 1.7][v]
    n = ns(dur)
    m = Bus(dur)
    blows = [0.0] if v == 0 else [0.0, 0.3]
    for k, at in enumerate(blows):
        last = k == len(blows) - 1
        m.add(chop(rng, 1.0 + 0.2 * k, 1.0), at, 1.0 if last else 0.8)
        m.add(whoosh(0.18, rng, 0.7, 250, 2600, 1.0, 2.0) * 1.0, max(0.0, at - 0.12), 0.25)
    t0 = blows[-1]
    gd = dur - t0 - 0.04
    fl = np.exp(-tvec(gd) / (0.5 + 0.2 * v)) * np.clip(tvec(gd) / 0.03, 0, 1)
    m.add(gush(gd, rng, fl, 3.6, 0.55, 1.0 + 0.3 * v), t0 + 0.02, 1.0)
    m.add(body_slap(rng, 1.6, 0.9, 0.5), t0 + 0.12, 0.8)
    wet_moves(m, rng, 0.8, path([(0, 18), (0.4, 10), (0.8, 3)], 0.8), size=1.3, gain=0.8, squelches=0.5)
    m.add(slither(0.7, rng, bell(0.7, 0.3, 1.3, 1.5), 180, 2000, 3.2, 0.6), t0 + 0.18, 0.4)
    m.add(body_slap(rng, 0.9, 0.5, 0.3), t0 + 0.48, 0.35)
    m.add(flesh_creak(0.8, rng, path([(0, 40), (0.3, 22), (0.8, 8)], 0.8), bell(0.8, 0.2, 1.2, 1.5), peaks=(260, 600)), t0, 0.5)
    m.add(nrm(squeal(0.35, rng, path([(0, 820), (0.35, 480)], 0.35), bell(0.35, 0.25, 1.4, 1.4), harmonics=5, tilt=1.4, chatter=0.6,
                     wobble=0.03, grit=0.1, peak_hz=1400)), t0 + 0.05, 0.14)
    if v == 1:
        m.add(splash(0.9, rng, size=1.6, hard=0.8), t0 + 0.72, 0.8)
        m.add(hull_slap(rng, 1.0, 0.9), t0 + 0.8, 0.4)
    m.add(hull_slap(rng, 0.9, 0.8), t0 + 0.2, 0.35)
    for tt in np.sort(rng.uniform(t0 + 0.1, dur - 0.1, 10)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.1))
    return at_sea(hush(fit(m.out(), n), -4.0, 8000), 0.08, seed=2110 + v)


@recipe("maelgrasp_sink", peak_db=-3.0, fade_out=0.6, max_len=2.8, tail_db=-62.0, loud=-16.0, limit_db=8.0,
        subtitle="A tentacle slides under", reach=32, hp_hz=38.0, hp_order=3)
def maelgrasp_sink(v, rng):
    """an arm sliding back under the sea, gurgling: the length of it dragged over the rail (a slither that thins out, water
    pouring off, the last suckers letting go one by one), the end of it slipping in (a soft suck and the water closing over
    it with a bloop), gargling bubbles that slow and fall in pitch as it goes down, the sound going muffled and dull as the
    sea takes it, a last great bubble and the swirl settling"""
    dur = 2.8
    n = ns(dur)
    m = Bus(dur)
    sl = np.clip(path([(0, 0.0), (0.15, 1.0), (0.9, 0.7), (1.4, 0.0)], dur, 0.1), 0, 1)
    m.add(slither(dur, rng, sl, 200, 2200, 1.5, 0.6), 0.0, 1.0)
    m.add(flesh_creak(dur, rng, path([(0, 20), (0.8, 12), (1.4, 5), (dur, 3)], dur), sl, peaks=(200, 460)), 0.0, 0.5)
    for k in range(8):
        m.add(sucker_pop(rng, rng.uniform(1.0, 1.6), 0.5), 0.1 + 0.11 * k * (1 + 0.5 * rng.random()) ** 1.0, rng.uniform(0.15, 0.4) * (1 - k / 11))
    flow = np.clip(path([(0, 0.0), (0.1, 0.8), (1.0, 0.5), (1.6, 0.1), (dur, 0.0)], dur, 0.1), 0, 1)
    m.add(pour(dur, rng, flow, lo=300, hi=4500, drops=0.8, glugs=1.2, size=1.4, mix=(1.0, 0.45, 0.5)), 0.0, 0.7)
    m.add(hull_slap(rng, 0.9, 0.9, cav=1.0), 0.05, 0.35)
    m.add(squelch(rng, 1.8, 0.4, -0.3), 1.25, 0.5)
    m.add(nrm(cavity(rng, f=190, glide=0.3, tau=0.05)), 1.4, 0.5)
    m.add(splash(0.8, rng, size=1.2, hard=0.4), 1.3, 0.45)
    gd = 1.5
    m.add(gargle(gd, rng, path([(0, 24), (0.5, 14), (1.0, 7), (gd, 2.5)], gd), 8.0, 70.0, 1.0, 2.5, 0.6, 0.3,
                 np.exp(-tvec(gd) / 0.8)), 1.3, 1.0)
    m.add(bubbles(dur - 1.2, rng, 400 * np.exp(-tvec(dur - 1.2) / 0.5) + 6, 0.6, 5.0, beta=1.9, level=1.0, weight=0.4), 1.2, 0.35)
    m.add(nrm(bubble(95.0, rng, rise=0.1, max_dur=0.6)), 2.05, 0.9)
    m.add(nrm(bubble(60.0, rng, rise=0.1, max_dur=0.5)), 2.3, 0.5)
    m.add(slosh(1.4, rng, 500, 0.3), 1.3, 0.4)
    y = fit(m.out(), n)
    y = tvf(y, "lp", path([(0, 9000), (1.2, 7000), (1.45, 2200), (2.0, 700), (dur, 450)], dur, 0.05), 0.7)  # the sea takes it
    return at_sea(hush(y, -4.0, 8000), 0.1, seed=2120)


def twang(rng, f0, dur=0.7, t60=0.4, g=1.0):
    """a taut line parting or letting go: a ringing whose pitch falls as the tension goes out of it"""
    return g * modal(dur, f0 * np.array([1, 2.01, 3.03, 4.06, 5.1]), [t60, t60 * 0.6, t60 * 0.4, t60 * 0.28, t60 * 0.2],
                     [1, 0.6, 0.4, 0.25, 0.15], rng, glide=0.12)


def seam_jets(dur, rng, env, rate=14.0):
    """water forced through the opened seams of a hull: sharp, spitting jets (a hiss that stops and starts)"""
    jet = tvf(colored(dur, rng, -1.0), "bp", 2400 * wander(dur, rng, 4, 0.2), 1.4)
    gate = smooth_random(dur, rng, rate) ** 2.0
    return jet * gate * env


@recipe("maelgrasp_crush", variants=3, peak_db=-3.0, fade_out=0.5, max_len=[2.3, 2.7, 3.1], tail_db=-62.0, loud=-13.0, limit_db=9.0,
        subtitle="The ship's timbers groan", reach=48, sat=1.3)
def maelgrasp_crush(v, rng):
    """the cog's timbers groaning under enormous strain, deep creaks and cracks: the hull as one great beam taking a load it
    cannot, every plank and frame ringing in the ship's own voice but a long way lower and slower. 1 a strain that builds,
    the groan climbing, ratcheting faster and faster, and a huge crack of the hull giving; 2 two surges of it with cracking
    between, a line parting with a twang and water jetting through the seams; 3 a long drawn groan of the keel, a plank
    splitting along the grain with a long rip, the mast working very low, a last shudder and rattle"""
    dur = [2.3, 2.7, 3.1][v]
    n = ns(dur)
    m = Bus(dur)
    hm = lambda lo, hi, pk: hull_modes(lo, hi, peaks=pk)
    if v == 0:
        load = np.clip(path([(0, 0.15), (1.0, 0.5), (1.7, 1.0), (1.82, 0.0), (dur, 0.0)], dur, 0.02), 0, 1)
        rate = path([(0, 5), (0.7, 10), (1.2, 22), (1.7, 52), (1.82, 0.0), (dur, 0.0)], dur, 0.02) * spread(dur, rng, 5, 0.08)
        m.add(creak_voice(dur, rng, rate, load ** 0.9, hm(40, 2200, (100, 230)),
                          [(path([(0, 110), (1.7, 250), (dur, 150)], dur), 3.5, 1.0)], jitter=0.2, alt=0.25, rub=0.2, kick=0.002,
                          bright=1800, unsteady=0.4), 0.0, 1.0)
        m.add(creak_voice(dur, rng, rate * 1.9 + 4, load, hm(120, 3200, (320, 700)),
                          [(path([(0, 300), (1.7, 650)], dur), 4.0, 0.8)], jitter=0.3, alt=0.3, rub=0.3, kick=0.0011, bright=3600,
                          unsteady=0.5), 0.0, 0.55)
        cr = 1.78
        m.add(hann_burst(0.01, rng, 400, 8000), cr, 0.4)
        m.add(oak(0.8, rng, 96, t60=0.32, hard=1.0, lo=60), cr, 1.2)
        m.add(saturate(sine_sweep(0.7, 90, 38, 4.0) * np.exp(-tvec(0.7) / 0.14), 2.6), cr, 0.7)
        m.add(knock(rng, 0.65, 2.2, 0.9, 0.9, 1.0), cr, 0.8)
        for k in range(8):
            m.add(pop(rng, rng.uniform(110, 800), rng.uniform(0.4, 1.0), 1.0), cr + 0.01 + 0.03 * k + rng.uniform(0, 0.02),
                  0.5 * 0.82 ** k)
        m.add(creak_voice(0.5, rng, path([(0, 90), (0.2, 50), (0.5, 8)], 0.5), bell(0.5, 0.15, 1.2, 1.5), hm(70, 3000, (160, 380)),
                          None, jitter=0.35, alt=0.3, rub=0.4, kick=0.0009, bright=5000, unsteady=0.5), cr, 0.6)
        m.add(creak_voice(0.7, rng, path([(0, 18), (0.3, 8), (0.7, 3)], 0.7), bell(0.7, 0.2, 1.3, 1.5), hm(60, 1600, (130, 300)),
                          None, jitter=0.2, alt=0.2, kick=0.002, bright=1500), cr + 0.15, 0.4)
        m.add(twang(rng, 256, 0.9, 0.5), cr + 0.03, 0.3)
        m.add(seam_jets(0.5, rng, np.exp(-tvec(0.5) / 0.15), 16), cr + 0.05, 0.25)
    elif v == 1:
        for k, (a, b) in enumerate([(0.0, 1.0), (1.05, 2.1)]):
            d = b - a
            r = path([(0, 6), (0.4 * d, 16), (0.8 * d, 36), (d, 8)], d) * spread(d, rng, 5, 0.1)
            ld = bell(d, 0.65, 1.4, 1.2) * (0.8 + 0.2 * k)
            m.add(creak_voice(d, rng, r, ld, hm(40, 2300, (110, 260)), [(path([(0, 130), (0.8 * d, 290), (d, 180)], d), 3.5, 1.0)],
                              jitter=0.2, alt=0.25, rub=0.2, kick=0.002, bright=1900, unsteady=0.4), a, 1.0)
            m.add(creak_voice(d, rng, r * 1.7 + 3, ld, hm(150, 3200, (360, 760)), [(path([(0, 330), (d, 700)], d), 4.0, 0.8)],
                              jitter=0.3, alt=0.3, rub=0.3, kick=0.0011, bright=3600, unsteady=0.5), a, 0.5)
            at = a + 0.78 * d
            m.add(hann_burst(0.008, rng, 400, 7500), at, 0.3)
            m.add(oak(0.6, rng, 105 - 12 * k, t60=0.25, hard=1.0, lo=70), at, 0.9)
            m.add(knock(rng, 0.7 - 0.05 * k, 2.0, 0.8, 0.8, 1.0), at, 0.6)
            for j in range(5):
                m.add(pop(rng, rng.uniform(120, 700), rng.uniform(0.4, 1.0), 1.0), at + 0.01 + 0.035 * j + rng.uniform(0, 0.02), 0.4 * 0.8 ** j)
        m.add(twang(rng, 214, 1.0, 0.55), 1.78, 0.5)
        m.add(twang(rng, 301, 0.8, 0.4), 1.86, 0.35)
        m.add(seam_jets(0.9, rng, bell(0.9, 0.3, 1.3, 1.3), 18), 1.7, 0.35)
        m.add(slosh(1.0, rng, 900, 0.3), 1.8, 0.3)
    else:
        load = bell(dur, 0.55, 1.5, 1.3)
        m.add(creak_voice(dur, rng, path([(0, 4), (0.8, 8), (1.6, 14), (2.3, 9), (dur, 3)], dur) * spread(dur, rng, 4, 0.08), load ** 0.8,
                          hm(36, 1800, (82, 190)), [(path([(0, 90), (1.4, 210), (dur, 100)], dur), 3.0, 1.0)], jitter=0.15, alt=0.2,
                          rub=0.1, kick=0.0026, bright=1400, unsteady=0.4), 0.0, 1.0)
        m.add(creak_voice(dur, rng, path([(0, 6), (1.2, 22), (1.9, 34), (dur, 7)], dur), load, hm(100, 3000, (300, 640)),
                          [(path([(0, 260), (1.9, 560)], dur), 4.0, 0.8)], jitter=0.3, alt=0.3, rub=0.3, kick=0.0011, bright=3400,
                          unsteady=0.5), 0.15, 0.5)
        rp = 1.3
        at = 1.45
        m.add(creak_voice(rp, rng, path([(0, 120), (0.15, 160), (0.5, 90), (0.9, 45), (rp, 12)], rp) * spread(rp, rng, 8, 0.12),
                          bell(rp, 0.15, 1.2, 1.6), hm(70, 3600, (150, 340)), [(path([(0, 520), (rp, 900)], rp), 3.5, 0.7)],
                          jitter=0.3, alt=0.3, rub=0.45, kick=0.0009, bright=5200, unsteady=0.5), at, 0.9)
        m.add(oak(0.7, rng, 130, t60=0.28, hard=1.0, lo=80), at, 0.9)
        m.add(hann_burst(0.01, rng, 500, 7500), at, 0.3)
        m.add(saturate(sine_sweep(0.6, 95, 42, 4.0) * np.exp(-tvec(0.6) / 0.12), 2.6), at, 0.5)
        for k in range(10):
            m.add(pop(rng, rng.uniform(150, 900), rng.uniform(0.4, 1.0), 1.0), at + 0.03 * k * (1 + 0.3 * rng.random()), 0.45 * 0.85 ** k)
        m.add(creak_voice(1.0, rng, path([(0, 5), (0.5, 16), (1.0, 4)], 1.0), bell(1.0, 0.4, 1.3, 1.3), hm(30, 600, (62, 130)),
                          [(path([(0, 70), (1.0, 120)], 1.0), 3.0, 1.0)], jitter=0.15, alt=0.2, kick=0.003, bright=900), 0.9, 0.9)
        for a2, g in [(2.55, 0.6), (2.72, 0.45), (2.88, 0.3)]:
            m.add(knock(rng, rng.uniform(0.9, 1.3), 0.9, 0.6, 0.4, 0.8), a2, g)
            m.add(strike(0.15, rng, rng.uniform(500, 1400) * np.array([1.0, 1.8]), [0.03, 0.02], [1, 0.5], lo=300, hi=5000, ex=0.001), a2, g * 0.2)
        m.add(twang(rng, 190, 0.9, 0.5), 2.0, 0.25)
    # the weight of the sea and the arms on the planking, felt
    m.add(boil(dur, rng, bell(dur, 0.6, 1.6, 1.3) * 0.6, 60.0, 500.0, 0.6), 0.0, 0.4)
    for tt in np.sort(rng.uniform(0.3, dur - 0.1, 8)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09))
    return at_sea(hush(fit(m.out(), n), -3.0, 8500), 0.08, seed=2130 + v)


# =========================================================================== the head
def cavern(x, wet=0.28, rt60=2.6, seed=1930, echoes=((0.21, 0.22), (0.47, 0.14), (0.83, 0.08)), pad=1.2, cut=1400.0):
    """a voice in a cave under the sea: a long, dark tail, and a few dull echoes thrown back off the walls"""
    y = reverb(x, wet=wet, rt60=rt60, predelay=0.03, hf_damp=0.8, seed=seed, early_span=0.12, pre_hp=60.0, tail_pad=pad)
    e = lp(x, cut, 2)
    for d, g in echoes:
        i = ns(d)
        k = max(0, min(len(e), len(y) - i))
        y[i:i + k] += g * e[:k]
    return y


def beak_click(rng, size=2.5, hard=0.8, wet=0.5):
    """the beak of a creature as big as a barn closing: the hard click of keratin (a few bright modes), the bone of the
    skull answering, a splat of water squeezed out between the plates"""
    return jaw_clack(rng, size, hard, wet)


def beak_train(dur, rng, rate, env, size=2.5, hard=0.8, shape=2.0):
    """a rattle of beak clicks, `rate` a second (a curve), each its own size, level following env"""
    n = ns(dur)
    out = np.zeros(n + ns(0.4))
    for tt in events(dur, rng, rate, shape=shape):
        c = beak_click(rng, size * rng.uniform(0.8, 1.3), hard * rng.uniform(0.6, 1.0), 0.4)
        i = ns(tt)
        g = env[min(n - 1, i)] * rng.uniform(0.5, 1.0)
        out[i:i + len(c)] += g * c[:max(0, len(out) - i)]
    return out[:n]


def bellow(dur, rng, f0, opening, env, gargle_rate=30.0, grit=1.0, rough=13.0, depth=0.45, lpf=2600.0, chest=0.6, breath=0.4):
    """a bellow out of a throat full of water: the deep voice with its pulse source driven, a chest voice an octave below,
    all of it chopped by water in the throat (a gargle of the level at `gargle_rate`) and bubbling through"""
    v1 = deep_voice(dur, rng, f0, opening, mass=0.7, twin=0.35, grit=grit, breath=breath, rough=rough, depth=depth, lpf=lpf,
                    subharm=0.6)
    v2 = deep_voice(dur, rng, f0 * 0.5, opening, mass=1.0, twin=0.0, grit=0.3, breath=0.1, rough=rough * 0.6, depth=0.3, lpf=700.0)
    y = nrm(v1) + chest * nrm(v2)
    g = lurch(dur, rng, gargle_rate, 0.5, 1.2)
    return y * g * env


@recipe("maelgrasp_roar", variants=2, peak_db=-3.0, fade_out=0.8, max_len=[3.9, 4.8], tail_db=-62.0, loud=-10.5, limit_db=10.0,
        subtitle="Maelgrasp roars", reach=128)
def maelgrasp_roar(v, rng):
    """the bellow of the thing under the sea, wet and cavernous: a throat the size of a hold bellowing through a mouth full of
    water (the deep voice, driven, and an octave of chest under it, chopped up by a gargle, great bubbles bursting in it),
    the beak clacking under the voice in a rattle that quickens and slows, a gush of water thrown out as the mouth opens, the
    cave of the sea throwing it back in dark echoes. 1 one long bellow that climbs and falls away in a rattle; 2 a short
    bark of it and, after a breath, a longer, higher one that tears at its peak"""
    n_tail = 1.2
    if v == 0:
        dur = 3.6
        vd = 2.7
        t = tvec(vd)
        f0 = path([(0, 40), (0.1, 72), (0.5, 86), (1.1, 96), (1.7, 90), (2.2, 68), (vd, 46)], vd, 0.04) * wander(vd, rng, 1.0, 0.02) \
            * (1 + 0.012 * np.sin(2 * np.pi * 5.6 * t))
        op = np.clip(path([(0, 0.1), (0.2, 0.95), (1.9, 0.85), (vd, 0.1)], vd, 0.05), 0, 1)
        env = path([(0, 0.0), (0.05, 0.85), (0.3, 0.7), (0.9, 1.0), (1.8, 0.9), (2.3, 0.45), (vd, 0.0)], vd, 0.03)
        voice = bellow(vd, rng, f0, op, env, 24.0)
        m = Bus(dur + n_tail)
        m.add(voice, 0.0, 1.0)
        m.add(gargle(vd, rng, path([(0, 36), (1.4, 28), (vd, 16)], vd), 16.0, 60.0, 0.6, 3.0, 0.6, 0.12, env), 0.0, 0.6)
        m.add(beak_train(vd, rng, path([(0, 8), (0.8, 14), (1.5, 22), (2.1, 14), (vd, 5)], vd), env ** 0.7, 2.5, 0.8), 0.0, 0.55)
        gt = tvec(1.0)
        m.add(pour(1.0, rng, np.exp(-gt / 0.3) * np.clip(gt / 0.01, 0, 1), lo=300, hi=5000, drops=1.0, glugs=1.2, size=1.8,
                   mix=(1.0, 0.5, 0.6)), 0.0, 0.6)
        m.add(smack(rng, 1.6, 1.0), 0.0, 0.4)
        m.add(bubbles(vd, rng, 30 + 220 * env, 1.0, 14.0, beta=1.8, level=1.0, weight=0.5), 0.0, 0.3)
        m.add(creak_voice(1.6, rng, path([(0, 20), (0.8, 40), (1.6, 10)], 1.6), bell(1.6, 0.4, 1.3, 1.5), hull_modes(100, 2400, peaks=(200, 460)),
                          None, jitter=0.2, alt=0.2, kick=0.0014, bright=2200), 0.2, 0.3)
    else:
        dur = 4.5
        m = Bus(dur + n_tail)
        d1 = 0.8
        f1 = path([(0, 44), (0.06, 78), (0.3, 92), (0.6, 70), (d1, 50)], d1, 0.03) * wander(d1, rng, 1.0, 0.02)
        e1 = path([(0, 0.0), (0.04, 1.0), (0.3, 0.8), (0.6, 0.5), (d1, 0.0)], d1, 0.02)
        m.add(bellow(d1, rng, f1, np.clip(path([(0, 0.2), (0.15, 1.0), (d1, 0.1)], d1, 0.05), 0, 1), e1, 30.0), 0.0, 0.9)
        m.add(beak_train(d1, rng, path([(0, 14), (d1, 8)], d1), e1, 2.5, 0.9), 0.0, 0.5)
        m.add(pour(0.7, rng, np.exp(-tvec(0.7) / 0.2) * np.clip(tvec(0.7) / 0.01, 0, 1), lo=300, hi=5000, drops=1.0, glugs=1.0,
                   size=1.5, mix=(1.0, 0.5, 0.6)), 0.0, 0.5)
        m.add(gargle(0.5, rng, 26.0, 10.0, 40.0, 0.6, 3.0, 0.6, 0.12, np.sin(np.pi * np.linspace(0, 1, ns(0.5))) ** 0.7), 0.7, 0.4)
        m.add(bp(colored(0.4, rng, -1.5), 120, 900) * bell(0.4, 0.5, 1.4, 1.4), 0.75, 0.3)  # the breath drawn in
        d2 = 3.2
        t2 = tvec(d2)
        f2 = path([(0, 52), (0.08, 80), (0.5, 98), (1.2, 112), (1.8, 132), (2.2, 126), (2.7, 84), (d2, 50)], d2, 0.05) \
            * wander(d2, rng, 1.0, 0.022) * (1 + 0.015 * np.sin(2 * np.pi * 6.1 * t2))
        o2 = np.clip(path([(0, 0.2), (0.3, 0.9), (1.8, 1.0), (d2, 0.1)], d2, 0.05), 0, 1)
        e2 = path([(0, 0.0), (0.08, 0.8), (0.5, 0.8), (1.2, 1.0), (1.9, 1.0), (2.4, 0.5), (d2, 0.0)], d2, 0.03)
        m.add(bellow(d2, rng, f2, o2, e2, 27.0, 1.0, 14.0, 0.5, 3000.0), 1.1, 1.0)
        tear_ = tvf(colored(d2, rng, -1.5), "bp", 700 + 900 * np.clip(path([(0, 0), (1.6, 1.0), (d2, 0.2)], d2, 0.1), 0, 1), 1.2) \
            * np.clip(path([(0, 0), (1.2, 0.0), (1.9, 1.0), (2.4, 0.6), (d2, 0)], d2, 0.1), 0, 1) * lurch(d2, rng, 55, 0.7)
        m.add(tear_, 1.1, 0.35)
        m.add(gargle(d2, rng, path([(0, 36), (1.6, 30), (d2, 14)], d2), 14.0, 55.0, 0.6, 3.0, 0.6, 0.12, e2), 1.1, 0.5)
        m.add(beak_train(d2, rng, path([(0, 6), (0.8, 12), (1.8, 26), (2.4, 14), (d2, 4)], d2), e2 ** 0.7, 2.6, 0.9), 1.1, 0.55)
        gt = tvec(1.0)
        m.add(pour(1.0, rng, np.exp(-gt / 0.35) * np.clip(gt / 0.01, 0, 1), lo=300, hi=5000, drops=1.0, glugs=1.2, size=1.9,
                   mix=(1.0, 0.5, 0.6)), 1.1, 0.5)
        m.add(bubbles(d2, rng, 30 + 200 * e2, 1.0, 14.0, beta=1.8, level=1.0, weight=0.5), 1.1, 0.3)
        m.add(smack(rng, 1.8, 1.0), 1.1, 0.35)
    y = fit(m.out(), ns(dur + n_tail))
    y = cavern(y, 0.3, 2.6, 1930 + v, ((0.21, 0.2), (0.47, 0.13), (0.83, 0.07)), 0.8)
    return hush(y, -4.0, 8500)


@recipe("maelgrasp_rise", peak_db=-3.0, fade_out=0.8, max_len=4.6, tail_db=-62.0, loud=-10.5, limit_db=8.0,
        subtitle="Maelgrasp rises", reach=96, hp_hz=40.0, hp_order=4, sat=1.5)
def maelgrasp_rise(v, rng):
    """the head surfacing, an enormous heave of cascading water: the sea swelling and boiling over the thing coming up
    (a pressure in the chest, a rush building, the hull lifted and slapped, timbers groaning), the break of the surface (a
    great smack, a wall of water thrown up and the hollow punched under it), then the cascade, the water of a small lake
    pouring off a head the size of a house in sheets, glugging, drops falling in a long rain, the swell rolling away under the
    hull, and under it all the deep, wet breath of something huge hauling itself up into the air"""
    dur = 4.6
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    brk = 1.25
    lift = np.clip(path([(0, 0.0), (0.4, 0.25), (0.9, 0.7), (brk, 1.0), (1.7, 0.7), (2.6, 0.35), (dur, 0.0)], dur, 0.12), 0, 1)
    lvl = 0.45 + 0.55 * rise_to(t, brk - 0.05, 0.1)  # the sea heaving under the thing is half what it is once it is through
    pre = np.clip(lift * (t < brk + 0.3), 0, 1)
    m.add(heave(dur, rng, [(0, 24), (0.8, 32), (brk, 44), (2.2, 34), (dur, 24)], lift * lvl, 2.6), 0.0, 0.7)
    m.add(boil(dur, rng, (lift * lvl) ** 1.1, 80.0, 1500.0, 0.8), 0.0, 0.9)
    m.add(bubbles(dur, rng, 30 + 800 * pre ** 1.5, 0.8, 30.0, beta=1.7, level=1.0, weight=0.5, rise=0.2) * lvl, 0.0, 0.5)
    m.add(bubbles(dur, rng, 9 * np.clip(lift * (t < brk + 0.4), 0, 1) ** 1.5 + 0.3, 30.0, 120.0, beta=1.5, level=1.0, weight=0.7,
                  rise=0.1) * lvl, 0.0, 0.7)
    rush = tvf(colored(dur, rng, -2.0), "bp", path([(0, 160), (brk, 800), (2.0, 600), (dur, 300)], dur, 0.2), 0.8) \
        * np.clip(lift * (t < brk + 0.2), 0, 1) ** 1.4 * lurch(dur, rng, 20, 0.4) * lvl
    m.add(rush, 0.0, 0.7)
    for at, sz, g in [(0.3, 0.9, 0.3), (0.6, 1.2, 0.4), (0.9, 1.5, 0.6), (1.15, 1.7, 0.8), (1.6, 1.5, 0.8), (2.0, 1.3, 0.6),
                      (2.5, 1.0, 0.45), (3.1, 0.8, 0.3)]:
        m.add(hull_slap(rng, sz, 0.9, cav=1.0), at + rng.uniform(-0.04, 0.04), g)
    for at, g in [(0.95, 0.55), (1.5, 0.9), (2.3, 0.5)]:
        m.add(knock(rng, rng.uniform(0.55, 0.8), 2.2, 0.6, 0.8, 1.0), at, g)
    m.add(creak_voice(dur, rng, path([(0, 6), (0.8, 18), (brk, 34), (2.0, 20), (3.2, 10), (dur, 4)], dur) * spread(dur, rng, 5, 0.12),
                      np.clip(lift ** 0.7 + 0.1, 0, 1) * lvl, hull_modes(50, 2400, peaks=(130, 310)),
                      [(path([(0, 150), (brk, 340), (dur, 190)], dur), 3.5, 0.9)], jitter=0.34, alt=0.25, rub=0.15, kick=0.0013,
                      bright=3000, unsteady=0.5), 0.0, 0.7)
    # the break of the surface
    m.add(smack(rng, 2.6, 1.2), brk, 1.2)
    et = tvec(0.9)
    m.add(bp(white(0.9, rng), 120, 4500) * np.exp(-et / 0.2) * np.clip(et / 0.006, 0, 1), brk, 1.0)
    m.add(saturate(thump(0.8, 95, 36, rng, decay=0.16, click=0.0, noise=0.6, nlp=420), 2.4), brk, 1.0)
    m.add(splash(2.2, rng, size=4.5, hard=0.8, crown=True), brk, 0.9)
    # the cascade
    cd = dur - brk
    ct = tvec(cd)
    flow = np.exp(-ct / 1.0) * np.clip(ct / 0.04, 0, 1)
    m.add(pour(cd, rng, flow, lo=280, hi=5500, drops=1.2, glugs=1.6, size=2.4, mix=(1.0, 0.5, 0.7)), brk, 1.1)
    m.add(spray(cd, rng, flow ** 0.8, 1200, 6500, rate=1400), brk, 0.3)
    # the breath of the head
    bd = 2.2
    m.add(deep_voice(bd, rng, path([(0, 38), (0.8, 48), (1.5, 42), (bd, 30)], bd, 0.2), np.clip(path([(0, 0.3), (0.8, 0.9), (bd, 0.2)], bd, 0.2), 0, 1),
                     tremor=0.1, lpf=900.0) * bell(bd, 0.35, 1.5, 1.4), brk + 0.35, 0.55)
    m.add(gargle(1.6, rng, 14.0, 20.0, 70.0, 0.5, 3.0, 0.6, 0.18, bell(1.6, 0.4, 1.4, 1.4)), brk + 0.5, 0.45)
    m.add(creak_voice(1.5, rng, path([(0, 5), (0.7, 12), (1.5, 4)], 1.5), bell(1.5, 0.4, 1.3, 1.4), hull_modes(40, 800, peaks=(90, 200)),
                      None, jitter=0.2, alt=0.2, kick=0.0026, bright=1100), brk + 0.4, 0.35)
    m.add(slosh(1.6, rng, 700, 0.3), brk + 0.6, 0.45)
    for tt in np.sort(rng.uniform(brk + 0.2, dur - 0.1, 40)):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.12) * np.exp(-(tt - brk) / 1.8))
    return at_sea(hush(fit(m.out(), n), -4.0, 8000), 0.12, seed=2140)


@recipe("maelgrasp_blink", peak_db=-3.0, fade_out=0.15, max_len=0.7, tail_db=-60.0, loud=-18.5, limit_db=8.0,
        subtitle="Maelgrasp's eye closes", reach=24)
def maelgrasp_blink(v, rng):
    """a wet lid closing over an eye the size of a cartwheel: the skin gliding over a slick surface (a slow, soft slither that
    swells and fades), a small tacky squelch as the edges meet, a soft deep pressure under it all, a drop or two"""
    dur = 0.65
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    m = Bus(dur)
    slick = tvf(colored(dur, rng, -1.5), "bp", 450 * (1800 / 450) ** np.sin(np.pi * u) ** 0.8, 1.6) * np.sin(np.pi * np.clip(u, 0, 1)) ** 1.3 \
        * lurch(dur, rng, 40, 0.5)
    m.add(slick, 0.0, 1.0)
    m.add(squelch(rng, 1.3, 0.2, 0.8), 0.0, 0.5)
    m.add(squelch(rng, 0.7, 0.14, -0.5), 0.4, 0.45)
    m.add(sucker_pop(rng, 1.1, 0.4), 0.46, 0.35)
    m.add(saturate(thump(0.4, 90, 48, rng, decay=0.07, click=0.0, noise=0.5, nlp=300), 1.8), 0.4, 0.5)
    m.add(gargle(0.4, rng, 22.0, 3.0, 9.0, 0.5, 3.0, 0.5, 0.1, np.sin(np.pi * np.linspace(0, 1, ns(0.4)))), 0.15, 0.2)
    for tt in np.sort(rng.uniform(0.3, 0.6, 3)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.08))
    return at_sea(hush(fit(m.out(), n), -4.0, 7000), 0.05, seed=2150)


@recipe("maelgrasp_bite", peak_db=-3.0, fade_out=0.4, max_len=1.4, tail_db=-62.0, loud=-11.5, limit_db=9.0,
        subtitle="Maelgrasp bites the rail", reach=48, sat=1.4)
def maelgrasp_bite(v, rng):
    """a beak the size of a cart crunching through the rail: the plates of the beak closing (hard keratin, a hollow knock), the
    oak taking it and giving (a deep knock, then a run of cracks and the long tearing rip of the grain), splinters
    scattering and hitting the boards, the wet munch of the mouth round it (squelching, water squeezed out), the broken rail
    falling into the sea with a splash"""
    dur = 1.45
    n = ns(dur)
    m = Bus(dur)
    m.add(beak_click(rng, 3.0, 1.0, 0.3), 0.0, 1.0)
    m.add(jaw_clack(rng, 2.0, 0.9, 0.0), 0.003, 0.6)
    m.add(saturate(thump(0.3, 170, 62, rng, decay=0.06, click=0.0, noise=0.5, nlp=600), 2.2), 0.0, 0.7)
    m.add(oak(0.7, rng, 135, t60=0.22, hard=1.0, lo=80), 0.004, 1.0)
    m.add(knock(rng, 0.75, 2.0, 0.8, 0.8, 1.0), 0.004, 0.7)
    m.add(hann_burst(0.008, rng, 400, 7000), 0.004, 0.35)
    for k in range(10):
        at = 0.02 + 0.045 * k * (1 + 0.3 * rng.random())
        m.add(pop(rng, rng.uniform(120, 850), rng.uniform(0.4, 1.0), 1.0), at, 0.6 * 0.88 ** k)
    rd = 0.7
    m.add(creak_voice(rd, rng, path([(0, 140), (0.1, 170), (0.35, 100), (rd, 24)], rd) * spread(rd, rng, 8, 0.12), bell(rd, 0.15, 1.2, 1.5),
                      hull_modes(70, 3600, peaks=(150, 340)), [(path([(0, 500), (rd, 900)], rd), 3.5, 0.7)], jitter=0.3, alt=0.3,
                      rub=0.5, kick=0.0009, bright=5400, unsteady=0.5), 0.02, 0.9)
    m.add(grain_cloud(1.0, rng, 700 * np.exp(-tvec(1.0) / 0.25) + 10, decay=0.0007, f_lo=800, f_hi=5500, size_var=1.0), 0.02, 0.4)
    for k in range(4):
        m.add(squelch(rng, rng.uniform(1.2, 2.0), 0.26, rng.uniform(0.5, 1.4)), 0.12 + 0.11 * k, 0.45)
        m.add(beak_click(rng, rng.uniform(2.5, 3.5), 0.5, 0.5), 0.2 + 0.13 * k, 0.3)
    m.add(pour(0.8, rng, np.exp(-tvec(0.8) / 0.25) * np.clip(tvec(0.8) / 0.01, 0, 1), lo=300, hi=5000, drops=1.0, glugs=1.0, size=1.2,
               mix=(1.0, 0.5, 0.5)), 0.05, 0.5)
    m.add(splash(0.8, rng, size=1.4, hard=0.7), 0.75, 0.55)
    for at in (0.85, 0.98, 1.1):
        m.add(knock(rng, rng.uniform(1.0, 1.5), 0.6, 0.5, 0.3, 0.5), at, rng.uniform(0.1, 0.3))
    for tt in np.sort(rng.uniform(0.2, 1.3, 10)):
        m.add(drip(rng), tt, rng.uniform(0.03, 0.09))
    return at_sea(hush(fit(m.out(), n), -3.0, 8500), 0.09, seed=2160)


@recipe("maelgrasp_ink", peak_db=-3.0, fade_out=0.45, max_len=1.9, tail_db=-62.0, loud=-16.0, limit_db=8.0,
        subtitle="Maelgrasp spews ink", reach=48)
def maelgrasp_ink(v, rng):
    """ink spewing from a siphon: three heavy pulses of thick fluid forced through a tube (a deep, gluey jet, a hiss on its
    front edge and a gloop at its close), slapping the water in fat blorps, a bloom of it billowing in the sea (a low, slow
    rumble that swells), thick bubbles popping slowly, a soft hiss as the cloud settles"""
    dur = 1.9
    n = ns(dur)
    m = Bus(dur)
    pulses = [(0.0, 0.5, 1.0), (0.42, 0.5, 0.85), (0.86, 0.55, 0.7)]
    for at, pd, g in pulses:
        pt = tvec(pd)
        env = np.sin(np.pi / 2 * np.clip(pt / 0.05, 0, 1)) ** 2 * np.exp(-pt / (0.25 * pd / 0.5)) * np.clip((pd - pt) / 0.03, 0, 1)
        jet = lp(tvf(colored(pd, rng, -2.5), "bp", 320 + 700 * np.exp(-pt / 0.12), 0.8), 1800) * env * lurch(pd, rng, 30, 0.4)
        m.add(jet, at, g)
        m.add(bp(white(0.08, rng), 700, 3500) * np.exp(-tvec(0.08) / 0.02) * np.clip(tvec(0.08) / 0.002, 0, 1), at, 0.3 * g)
        m.add(squelch(rng, 2.4, 0.3, -0.4), at + 0.02, 0.5 * g)
        m.add(nrm(bubble(rng.uniform(22, 34), rng, rise=0.2, max_dur=0.35)), at + pd * 0.8, 0.5 * g)
        m.add(gargle(pd, rng, 20.0, 4.0, 14.0, 0.5, 3.0, 0.5, 0.14, env), at, 0.4 * g)
    bl = np.clip(path([(0, 0.0), (0.3, 0.4), (1.0, 1.0), (1.5, 0.7), (dur, 0.0)], dur, 0.1), 0, 1)
    m.add(lp(bp(brown(dur, rng), 50, 380), 380, 2) * bl * (0.6 + 0.4 * smooth_random(dur, rng, 4)), 0.0, 0.8)
    m.add(bubbles(dur, rng, 14 * bl + 0.5, 6.0, 50.0, beta=1.6, level=1.0, weight=0.6, rise=0.15), 0.3, 0.5)
    m.add(bubbles(dur, rng, 220 * bl + 5, 0.7, 5.0, beta=1.9, level=1.0, weight=0.4), 0.2, 0.2)
    m.add(slosh(1.2, rng, 600, 0.3), 0.5, 0.3)
    for at, sz, g in [(0.1, 0.8, 0.4), (0.55, 0.7, 0.3), (1.0, 0.6, 0.25)]:
        m.add(hull_slap(rng, sz, 0.9, cav=1.0), at, g)
    return at_sea(hush(fit(m.out(), n), -6.0, 6000), 0.1, seed=2170)


@recipe("maelgrasp_dive", peak_db=-3.0, fade_out=0.7, max_len=3.4, tail_db=-62.0, loud=-11.0, limit_db=10.0,
        subtitle="Maelgrasp dives", reach=64)
def maelgrasp_dive(v, rng):
    """the head plunging back under: the great body going over and down into the sea (a huge smack, the water thrown aside
    in a wall and falling back, the hollow it opens roaring and slamming shut with deep gulps), the swell thrown against the
    hull, the draining of the water off it in sheets that thin, the cog rocking, a vortex gargling away downward (bubbles of
    growing size and falling pitch), a deep groan of the creature as it sinks, dulling as the sea closes over it, and the
    surface settling"""
    dur = 3.4
    n = ns(dur)
    m = Bus(dur)
    at = 0.25
    m.add(arm_whoosh(0.3, rng, 0.8, 120, 1400, 2.0, 12, 0.7), 0.0, 0.3)
    m.add(smack(rng, 3.0, 1.2), at, 1.0)
    et = tvec(1.0)
    m.add(bp(white(1.0, rng), 110, 4000) * np.exp(-et / 0.22) * np.clip(et / 0.005, 0, 1), at, 0.9)
    m.add(saturate(thump(0.9, 90, 32, rng, decay=0.2, click=0.0, noise=0.6, nlp=380), 2.4), at, 1.0)
    m.add(splash(2.6, rng, size=6.0, hard=1.0, crown=True), at, 1.0)
    for k, (a, f, d) in enumerate([(0.06, 38, 0.5), (0.32, 30, 0.6), (0.7, 26, 0.7)]):
        m.add(nrm(bubble(rng.uniform(60, 105), rng, rise=0.1, max_dur=0.7)), at + a + 0.05, 0.6 - 0.15 * k)
    flow = np.exp(-tvec(dur - at) / 0.8) * np.clip(tvec(dur - at) / 0.03, 0, 1)
    m.add(pour(dur - at, rng, flow, lo=300, hi=5000, drops=1.1, glugs=1.5, size=2.2, mix=(1.0, 0.5, 0.6)), at, 0.9)
    m.add(boil(dur - at, rng, np.exp(-tvec(dur - at) / 0.9), 80.0, 1400.0, 0.9), at, 0.7)
    for a, sz, g in [(0.15, 1.2, 0.5), (0.3, 1.7, 0.9), (0.55, 1.8, 1.0), (0.85, 1.5, 0.8), (1.25, 1.2, 0.6), (1.75, 1.0, 0.45),
                     (2.3, 0.8, 0.3)]:
        m.add(hull_slap(rng, sz, 0.9, cav=1.0), at + a + rng.uniform(-0.03, 0.03), g)
    for a, g in [(0.2, 0.8), (0.65, 0.7), (1.2, 0.45)]:
        m.add(knock(rng, rng.uniform(0.55, 0.8), 2.2, 0.6, 0.8, 1.0), at + a, g)
    vd = 2.4
    f0 = path([(0, 54), (0.4, 46), (1.2, 36), (vd, 24)], vd, 0.15) * wander(vd, rng, 0.8, 0.02)
    voice = deep_voice(vd, rng, f0, np.clip(path([(0, 0.8), (1.0, 0.4), (vd, 0.0)], vd, 0.2), 0, 1), tremor=0.15, lpf=1400.0)
    voice = tvf(voice * bell(vd, 0.12, 1.2, 1.4), "lp", path([(0, 2400), (0.6, 1500), (1.6, 500), (vd, 250)], vd, 0.1), 0.7)
    m.add(voice, at + 0.1, 0.8)
    gd = 2.2
    m.add(gargle(gd, rng, path([(0, 30), (0.8, 16), (1.6, 8), (gd, 3)], gd), 6.0, 85.0, 1.0, 2.5, 0.6, 0.35, np.exp(-tvec(gd) / 1.0)), at + 0.5, 0.9)
    m.add(bubbles(dur - 0.8, rng, 500 * np.exp(-tvec(dur - 0.8) / 0.7) + 5, 0.6, 6.0, beta=1.9, level=1.0, weight=0.4), 0.8, 0.3)
    m.add(creak_voice(2.0, rng, path([(0, 24), (0.5, 12), (1.2, 7), (2.0, 3)], 2.0), bell(2.0, 0.2, 1.3, 1.4), hull_modes(50, 2200, peaks=(110, 260)),
                      None, jitter=0.2, alt=0.2, kick=0.0016, bright=2200), 0.4, 0.5)
    m.add(slosh(1.8, rng, 600, 0.25), 1.2, 0.45)
    m.add(spray(1.4, rng, np.exp(-tvec(1.4) / 0.4), 1200, 6500, rate=1200), at, 0.3)
    for tt in np.sort(rng.uniform(0.6, dur - 0.1, 28)):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.12) * np.exp(-(tt - at) / 1.6))
    return at_sea(hush(fit(m.out(), n), -4.0, 8000), 0.12, seed=2180)


def last_bloops(m, rng, times, sizes, gain=0.6):
    """the last big bubbles of a dying thing coming up, a slower and bigger one each time"""
    for tt, r in zip(times, sizes):
        m.add(nrm(bubble(r, rng, rise=0.12, max_dur=1.0)), tt, gain)


@recipe("maelgrasp_death", peak_db=-3.0, fade_out=1.0, max_len=9.6, tail_db=-62.0, loud=-14.0, limit_db=9.0,
        subtitle="Maelgrasp dies", reach=128, hp_hz=32.0)
def maelgrasp_death(v, rng):
    """a long dying groan fading into bubbles: the cry going out of it (a deep voice that sags from a bellow down through the
    whole of its range into a rumble below hearing, breaking and stumbling as the throat fails, the sea in its throat, the
    top of it closing off), the beak clacking slower and slower, a long breath going out, the body slumping into the sea
    (waves slapping the hull, the hull groaning), its timbers working, a stream of bubbles that thins and slows and grows,
    the last big bloops coming up one by one and the surface going still"""
    dur = 9.6
    m = Bus(dur + 1.0)
    vd = 7.0
    vt = tvec(vd)
    f0 = path([(0, 56), (0.4, 66), (1.2, 56), (2.5, 46), (4.0, 38), (5.5, 29), (vd, 21)], vd, 0.3) * wander(vd, rng, 0.8, 0.02)
    for a, d in [(1.9, 0.14), (3.1, 0.18), (4.3, 0.2), (5.2, 0.25)]:  # the voice breaks: a period dropped, down an octave
        w = np.clip(np.minimum(vt - a, a + d - vt) / 0.02, 0, 1)
        f0 = f0 * (1 - 0.5 * w)
    op = np.clip(path([(0, 0.7), (0.5, 1.0), (2.5, 0.5), (vd, 0.0)], vd, 0.3), 0, 1)
    env = path([(0, 0.0), (0.1, 0.9), (0.5, 1.0), (2.0, 0.85), (3.5, 0.6), (5.0, 0.36), (6.2, 0.16), (vd, 0.0)], vd, 0.1) \
        * lurch(vd, rng, 6, 0.35)
    voice = deep_voice(vd, rng, f0, op, mass=0.75, twin=0.4, grit=0.9, breath=0.35, rough=15.0, depth=0.4, lpf=2400.0, tremor=0.3)
    voice = tvf(voice, "lp", path([(0, 2600), (2.0, 1800), (4.5, 900), (vd, 500)], vd, 0.2), 0.7) * env
    m.add(voice, 0.0, 1.0)
    m.add(gargle(vd, rng, path([(0, 30), (2.0, 20), (4.0, 12), (vd, 5)], vd), 12.0, 70.0, 1.0, 3.0, 0.6, 0.2, env ** 0.8), 0.3, 0.55)
    kt = 6.0
    krate = path([(0, 22), (1.0, 16), (2.5, 9), (4.0, 4), (5.5, 1.8), (kt, 0.8)], kt, 0.1)
    m.add(beak_train(kt, rng, krate, np.exp(-tvec(kt) / 3.0), 2.5, 0.8), 0.6, 0.5)
    ed = 3.6
    et = tvec(ed)
    m.add(tvf(colored(ed, rng, -1.5), "bp", 700 * (180 / 700) ** (et / ed), 1.0) * bell(ed, 0.2, 1.4, 1.5) * lurch(ed, rng, 30, 0.5), 0.5, 0.45)
    m.add(deep_creak(6.0, rng, path([(0, 8), (1.5, 6), (3.5, 4), (6.0, 2)], 6.0), bell(6.0, 0.25, 1.4, 1.2), peaks=(60, 130),
                     vowel=[(path([(0, 110), (6.0, 70)], 6.0), 3.0, 0.8)], bright=700.0), 0.3, 0.45)
    # the body slumps into the sea
    m.add(smack(rng, 2.4, 1.0), 0.15, 0.6)
    m.add(splash(1.8, rng, size=3.8, hard=0.6), 0.25, 0.7)
    for a, sz, g in [(0.3, 1.5, 0.8), (0.7, 1.6, 0.8), (1.15, 1.4, 0.65), (1.7, 1.2, 0.5), (2.4, 1.0, 0.4), (3.2, 0.8, 0.3), (4.2, 0.6, 0.2)]:
        m.add(hull_slap(rng, sz, 0.9, cav=1.0), a + rng.uniform(-0.05, 0.05), g)
    m.add(knock(rng, 0.65, 2.0, 0.5, 0.8, 1.0), 0.35, 0.5)
    m.add(creak_voice(3.0, rng, path([(0, 6), (0.8, 18), (1.6, 9), (3.0, 3)], 3.0), bell(3.0, 0.3, 1.4, 1.2), hull_modes(50, 2000, peaks=(120, 280)),
                      [(path([(0, 150), (3.0, 220)], 3.0), 3.5, 0.8)], jitter=0.2, alt=0.2, kick=0.0014, bright=2200), 0.5, 0.4)
    m.add(boil(3.5, rng, np.exp(-tvec(3.5) / 1.2), 70.0, 1100.0, 0.7), 0.3, 0.5)
    m.add(slosh(3.0, rng, 600, 0.2), 0.8, 0.4)
    # bubbles: a stream that thins, slows and grows
    bd = 8.6
    bt = tvec(bd)
    m.add(bubbles(bd, rng, 700 * np.exp(-bt / 2.0) * np.clip((bt - 1.5) / 1.5, 0, 1) + 3, 0.5, 4.0, beta=1.9, level=1.0, weight=0.4), 0.0, 0.3)
    m.add(bubbles(bd, rng, 32 * np.exp(-bt / 3.0) * np.clip((bt - 1.2) / 1.5, 0, 1) + 0.3, 4.0, 40.0, beta=1.6, level=1.0, weight=0.6, rise=0.15), 0.0, 0.6)
    last_bloops(m, rng, [5.7, 6.7, 7.6, 8.3, 8.85], [38, 52, 70, 90, 110], 0.55)
    return abyss(at_sea(hush(fit(m.out(), ns(dur + 1.0)), -4.0, 6500), 0.1, seed=2190), 0.18, 2.4, 1915, 2800.0, 0.8)


# =========================================================================== the order of the sounds
# the order of the listen-through and of sea_sounds.json: the gnashfins (what they do in the water, in the air, on the ship, on
# the deck, and their pain), then the Maelgrasp: the omens and the sea heaving, the arms (up, idle, down on the ship, its
# grip, its hurt), the head (up, its voice, its mouth, down, dead)
ORDER = ["gnashfin_fin", "gnashfin_leap", "gnashfin_dive", "gnashfin_snap", "gnashfin_bite", "gnashfin_gnaw", "gnashfin_flop",
         "gnashfin_hurt", "gnashfin_death",
         "maelgrasp_omen", "maelgrasp_bubbles", "maelgrasp_surge", "maelgrasp_burst", "maelgrasp_writhe", "maelgrasp_rear",
         "maelgrasp_slam", "maelgrasp_sweep", "maelgrasp_grab", "maelgrasp_squeeze", "maelgrasp_hurl", "maelgrasp_arm_hurt",
         "maelgrasp_sever", "maelgrasp_sink", "maelgrasp_crush",
         "maelgrasp_rise", "maelgrasp_roar", "maelgrasp_blink", "maelgrasp_bite", "maelgrasp_ink", "maelgrasp_dive",
         "maelgrasp_death"]
assert sorted(ORDER) == sorted(RECIPES), "every sound is in ORDER, once"
for _name in ORDER:
    RECIPES[_name] = RECIPES.pop(_name)


# =========================================================================== listen-through, fragments, command line
GAP = 0.7  # seconds of quiet between one sound and the next in the listen-through


def listen_through(assets, names, path):
    """one MP3 to listen to on a phone: every file that was just written, in order, a gap between, a chapter mark (the
    file's name) at each. The files are read back from disk, so it is what the game will play"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            stem = sound_path(name, v, r["variants"])
            p = os.path.join(assets, "sounds", stem + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {stem})")
                continue
            x = read_audio(p)[0]
            marks.append((at, at + len(x) / SR + GAP, stem[len(SEA_DIR) + 1:]))
            pieces += [x, np.zeros(ns(GAP))]
            at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark sea monsters\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)  # (encoded aside, then copied: ffmpeg seeks back in a file to finish its header)
    print(f"{path}  {at:.1f}s, {len(marks)} files, {os.path.getsize(path) / 1e6:.2f} MB")


def fragments(directory):
    """sea_sounds.json (the sounds.json entries of every event, keys as the game names them, and the theme) and
    sea_subtitles.json (their subtitles), for the lead to merge; the real sounds.json and en_us.json are not touched"""
    sounds, lang = {}, {}
    for name, r in RECIPES.items():
        entries = []
        for v in range(r["variants"]):
            s = {"name": "guildmark:" + sound_path(name, v, r["variants"])}
            if r["reach"]:
                s["attenuation_distance"] = r["reach"]
            entries.append(s)
        key = event_key(name)
        sounds[key] = {"sounds": entries, "subtitle": f"subtitles.guildmark.{key}"}
        lang[f"subtitles.guildmark.{key}"] = r["subtitle"]
    sounds["music.maelgrasp"] = {"sounds": [{"name": "guildmark:music/maelgrasp", "stream": True}]}
    os.makedirs(directory, exist_ok=True)
    for fname, data in (("sea_sounds.json", sounds), ("sea_subtitles.json", lang)):
        p = os.path.join(directory, fname)
        with open(p, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(p, len(data), "entries")


def select(only):
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        for pre in ("sea.", SEA_DIR + "/"):
            if s.startswith(pre):
                s = s[len(pre):]
        s = s.replace(".", "_")
        if s not in RECIPES:
            raise SystemExit(f"unknown: {s} (sounds: {', '.join(RECIPES)}, music)")
        if s not in names:
            names.append(s)
    return names


def main():
    ap = argparse.ArgumentParser(description="Guildmark's sea monsters: the gnashfins and the Maelgrasp, their sounds and battle theme")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated sound names, e.g. gnashfin_snap,maelgrasp_roar,music")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of the sounds that were rendered")
    ap.add_argument("--fragments", default=None, help="also write sea_sounds.json and sea_subtitles.json here")
    ap.add_argument("--work", default=None, help="where the theme keeps its per-stem MIDI and WAV renders")
    ap.add_argument("--sf2", default=None, help="the General MIDI soundfont for the theme (default: found as make_music.py does)")
    ap.add_argument("--theme-mp3", default=None, help="also write the theme as an MP3 to listen to")
    a = ap.parse_args()
    only = [s.strip() for s in a.only.split(",") if s.strip()] if a.only else None
    with_music = only is None or "music" in only
    names = select(",".join(s for s in only if s != "music")) if only else list(RECIPES)
    build(a.assets, names, a.wav)
    if a.mp3 and names:
        listen_through(a.assets, names, a.mp3)
    if a.fragments:
        fragments(a.fragments)
    if with_music:
        import make_sea_music
        make_sea_music.build(a.assets, a.work, a.sf2, a.wav, a.theme_mp3)


if __name__ == "__main__":
    main()
