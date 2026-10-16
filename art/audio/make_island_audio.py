"""Guildmark's islands: every sound of the islands in the sea (the surf on the beaches, the rocks and the caves, the wind, the
gulls, the lighthouse, the wreck, the smugglers' cove, the drowned shrine, the fishing haven), made from nothing. The
islands' theme is make_island_music.py's.

usage (from the repo root):
    python3 -B art/audio/make_island_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b] [--mp3 FILE]
        [--variants-mp3 FILE] [--fragments DIR] [--no-render]

Writes the sounds as 44.1 kHz Ogg Vorbis to <assets>/sounds/island/<name>_<n>.ogg (n from 1; <name>.ogg for an event with
one file; the name is the event without island. and with _ for the dot after its group: island.surf.lap -> surf_lap_1.ogg):
28 events, 101 files. Mono, so that the game places them, except the wind bed and the discovery, which are stereo (the game
plays a stereo file unpositioned).

--only takes names, groups or events (surf_lap, gull, island.wreck.bell). --wav also writes WAV copies. --mp3 joins the
sounds into one listen-through file, a chapter for each place (beach, rocks and cave, wind, gulls, lighthouse, wreck, cove,
shrine, haven, discovery), the sounds laid out at about the pace and the loudness a player would meet them, each placed by
its reach (the game's linear fall-off, a pan, the air taking the top off a far one). --variants-mp3 joins every file in
order, a gap between, a chapter at each (a loop plays for twelve seconds). --fragments writes island_sounds_entries.json
(the sounds.json entries of every event, keyed as the game names them, and music.isles, streamed), island_subtitles.json
(their subtitles) and island_marks.json (below) for merging: the real sounds.json and en_us.json are not touched, and the
files are listed in sounds.json by hand, as the other effects' are. --no-render writes only the MP3s and the fragments, from
the files already written (no marks then).

Loudness. A one-shot peaks at -3 dBFS and is then set by the loudest 100 ms in LUFS (K-weighted, `loud` in its recipe: the
townsfolk's, the cog's and the dragon's way): a sound louder than that is turned down to it, a sharp one is soft-limited up
to it. The loops are set by RMS, -27 dBFS (as the cog's rigging), their peaks held under -8 or -9 dBFS. A file that the
encoder pushes over -1 dBTP is turned down and encoded again. Reach is the sound's attenuation_distance in blocks (16, the
game's default, where it is not listed; the two stereo files are unpositioned and have none). All randomness is seeded: a
rerun gives the same files.

Marks. The surf's files have moments the game can sync a particle to, in seconds from the start of the file
(island_marks.json): surf_lap `break` (the wave tumbling over on the sand, where the foam should come up) and `stall` (the
top of the run-up, where the wash stops and starts to drain); surf_crash and surf_cave `impact` (the blow on the rock, where
the spray should fly).

The places (event, files, length, reach in blocks)
  The beach and the rocks
    island.surf.lap           6 files  3.2-4.8 s      reach 24
        a wave rolling up the sand: a soft break, the wash running up and the hiss of it draining down through the
        sand (an ordinary lap; a small quick one; a big one, deep, with a long drain; a spilling wave, more foam than
        thump; a second rill of water coming a moment late; a lazy one, a long creeping wash)
    island.surf.crash         6 files  3.4-5.0 s      reach 32
        a wave on rock: the swell rushing up, the blow, the spray flung up and showering down, the water pouring off
        (an ordinary crash; a small slap on a ledge; a big one you feel; a long fine spray; a hollow gully gulping; a
        blowhole whistling after)
    island.surf.cave          4 files  5.0-5.8 s      reach 32
        the sea booming in a cave: the air pushed ahead of the wave, a hollow thud, a long dark echo, the water
        draining (an ordinary boom; a smaller one, nearer the mouth; a heavy one with a second boom coming back off
        the far wall; a boom and the cave's long breath)
  The wind
    island.wind.bed           1 file  12.000 s loop  unpositioned
        the steady breeze, wide: a broad rush swelling and sinking every few seconds, dune grass ticking in it, low
        air at the ears, a faint whistle in the rocks. A stereo loop, unpositioned, as the wall wind is
    island.wind.gust          6 files  4.0-7.0 s      reach 32
        a gust sweeping over: the rush rising, brightening and sinking away, grass, a low buffeting (an ordinary gust;
        a sharp one; one that sings through the rocks; a long swell of wind roaring low; two surges with a lull; a
        soft sigh of a breeze)
  The gulls (the harbour's gull voice, and the birds' bodies: wings, feet, water)
    island.gull.call          6 files  2.8-4.8 s      reach 64
        the long call: a few low notes, then the run of kyows, quickening, and a falling tail (in full; a laughing
        run; a short one of five; a pair, one answering from far; a high excited run; a far lazy call over the water)
    island.gull.cry           8 files  0.8-1.9 s      reach 48
        one cry (a falling kyow; a long keeeow; a soft mew; a thin kee-kee-kee; a low double ow-ow; a plaintive wail;
        a kyeee with a break in the voice; a hoarse kaah)
    island.gull.alarm         4 files  1.3-1.9 s      reach 48
        harsh and hurried (a run of keks; four sharp yeows; a wavering chatter; a held scream and keks after it)
    island.gull.flap          4 files  1.3-1.8 s      reach 24
        wings (taking off; coming in to land; a few slow strokes passing over; a shake of the feathers)
    island.gull.squabble      3 files  2.2-2.9 s      reach 40
        two gulls over a scrap, harsh calls flung across each other and a clatter of wings (a scrap of fish; a chase;
        a quarrel further off)
    island.gull.splash        3 files  1.4-1.7 s      reach 24
        a gull on the water (coming down and skidding to a stop; a plunge for a fish; bathing)
    island.gull.colony        1 file  12.000 s loop  reach 48
        the colony on a ledge: dozens of birds at every distance calling over one another, a clamour that never
        repeats, the cliff giving it back. A mono loop
  The lighthouse
    island.lighthouse.turn    1 file  8.000 s loop   reach 24
        the clockwork that turns the lamp, one turn of the lens to the loop: the escapement ticking twice a second,
        the gear train, the carriage rolling on its wheels, a latch dropping home once a turn and the lens sweeping
        past. A mono loop
    island.lighthouse.horn    2 files  6.0, 7.2 s     reach 128
        the diaphone over the sea, the sea and the cliffs giving it back in long dull echoes (one long blast; a short
        blast and a long one, lower)
    island.lighthouse.bell    3 files  5.6-8.0 s      reach 64
        the big bronze fog bell struck by the clockwork hammer, the strike note an E over the deep hum and prime,
        ringing long (one blow; a pair; a slow toll of three)
  The wreck
    island.wreck.creak        6 files  2.6-3.6 s      reach 32
        the hulk working in the swell, lower, wetter and slower than a living ship (a long groan as a swell lifts her;
        two moans; timbers ticking as they dry; a rib letting go; a wet rasp of plank on plank; settling, water
        running out of her)
    island.wreck.sail         3 files  3.0-3.3 s      reach 24
        what is left of the sail on the broken yard (lazy flaps; a gust's rattle and snap; a ripple along a torn edge)
    island.wreck.bell         2 files  1.7, 2.7 s     reach 48
        the ship's bell, cracked and half drowned, a dull tunk and a gulp of water (one blow; two uneven blows as she
        rocks)
  The smugglers' cove
    island.cove.drip          6 files  1.7-3.0 s      reach 16 (the default)
        water dripping in the cave, each drop ringing in the stone (a bright drop; two; a heavy drop in a deep pool;
        three thinning out; a trickle and a drop; a tick on dry stone and a drop in the pool)
    island.cove.signal        3 files  1.9-2.4 s      reach 32
        a signal whistled across the cove, the cliffs answering (an owl's hoot; a shrill pipe; a curlew's call)
    island.cove.camp          4 files  1.9-2.5 s      reach 16 (the default)
        the camp, no voices (a crate set down on the shingle; a mooring line creaking; coins dropped on a plank; a
        bottle set down on a cask)
  The drowned shrine
    island.shrine.drone       1 file  12.000 s loop  reach 32
        its voice: a deep slow chord (D, A, D, with an E and an F sharp trading places over the loop), every note a
        pair a hair apart so that it beats and breathes, a veil of glassy overtones, the sea breathing through stone.
        A mono loop
    island.shrine.chime       4 files  4.1-4.8 s      reach 24
        glass and bronze tubes tuned to the chord (D, A, E, F sharp), heard through moving water, a long dark hall
        after them (a high A; F sharp falling to D; D, A, E rising; one deep broad D)
    island.shrine.bubbles     3 files  3.0-3.8 s      reach 24
        bubbles rising out of the shrine, slow and deep (a long uneven stream; a gulp and a train of small ones; three
        slow breaths)
    island.shrine.whisper     3 files  3.2-4.0 s      reach 16 (the default)
        breath through the shapes of vowels, no words, voices rising and sinking with the water (three voices; two,
        one answering; a crowd, all low)
  The fishing haven
    island.haven.boats        4 files  2.4-3.1 s      reach 24
        boats at their moorings (two hulls nudging; a dory on the piles and an oar squealing; a hull rubbing on its
        fender; the swell passing down the line of boats)
    island.haven.rope         3 files  2.0-2.5 s      reach 16 (the default)
        mooring line on the jetty (taut and creaking; a coil dropped on the planks; a line running out through a ring
        and brought up short on a cleat)
  The finding
    island.discover           1 file  7.0 s          unpositioned
        a new island found, a sting played at the player: the surf far off, a harp rolled up an A major arpeggio, a
        low wooden flute rising from E to A and B and settling on A, the harp answering underneath it with an F sharp
        minor and a last open chord. Stereo

Made from the cog's parts (make_ship_audio.py: oak, rope, canvas, bells, bubbles and water), the harbour's gull voice
(ambience/harbour.py) and the kit's noise, modal and room tools (sfxkit.py, ambkit.py).
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
from scipy.ndimage import maximum_filter1d, uniform_filter1d

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403
from sfxkit import _sos  # noqa: E402
from make_ship_audio import (BRONZE, Bus, Ring, bell_blow, bell_modes, bubble, bubbles, cavity, clapper,  # noqa: E402
                             creak_voice, crinkle, drip, flump, grain_cloud, gurgle, hann_burst, hull_slap, iron_ring, knock,
                             late, lead_in, nrm, oak, path, periodic_phase, pop, rn, rope_run, saturate, short_lufs, slosh,
                             spread, squeal, strike, tame, timber_modes, true_peak)
from ambkit import pan, stereo_reverb  # noqa: E402
from ambience.harbour import gull_note  # noqa: E402  (the harbour's gull voice: a harmonic stack on a chevron of pitch)
from make_ship_audio import flap as canvas_flap  # noqa: E402

RECIPES = {}
ISLAND_DIR = "island"
LEAD = 0.002  # seconds of silence put in front of every one-shot (lead_in), so that the zero-phase high-pass pads nothing


# =========================================================================== registry and finishing
def recipe(name, variants=1, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, loud=None, limit_db=6.0,
           loop=None, rms_db=None, ceiling_db=-6.0, stereo=False, subtitle="", reach=None, hp_hz=25.0, onset_db=-40.0):
    """one sound event, written as island/<name>.ogg (one variant) or island/<name>_<n>.ogg for n = 1..variants. The event is
    island.<group>.<what> for a name of group_what (surf_lap -> island.surf.lap), island.<name> for one without a group.
    peak_db: where a one-shot's peak sits. loud: how loud a one-shot is, as the townsfolk's and the cog's effects are set: the
    loudest 100 ms in LUFS (K-weighted; a number, or one for each variant). A variant that is louder than that at its peak is
    turned down to it; a sharp one is soft-limited just enough to reach it, never by more than limit_db. onset_db: how far under
    its peak a one-shot has to be before it counts as started (the quiet run-up of a wave is kept by a low one). A one-shot's
    function returns its samples, or the samples and a dict of marks (moments in seconds, from the start of what it returns);
    the marks come out again, from the start of the finished file.
    loop: the length in seconds of a seamless loop (the recipe is handed a Ring and gives back the three periods it laid out,
    see Ring; stereo: (2, 3 periods)), rms_db its RMS with its peaks held under ceiling_db. subtitle: the caption in the language
    file. reach: how many blocks the sound carries in the game, when not the usual 16"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, variants=variants, peak_db=peak_db, fade_out=fade_out, max_len=max_len,
                             tail_db=tail_db, loud=loud, limit_db=limit_db, loop=loop, rms_db=rms_db, ceiling_db=ceiling_db,
                             stereo=stereo, subtitle=subtitle, reach=reach, hp_hz=hp_hz, onset_db=onset_db)
        return fn
    return deco


def sound_path(name, v, variants):
    """('surf_lap', 2, 6) -> 'island/surf_lap_3'; a sound with one file is 'island/wind_bed'"""
    return f"{ISLAND_DIR}/{name}_{v + 1}" if variants > 1 else f"{ISLAND_DIR}/{name}"


def event_key(name):
    """'surf_lap' -> 'island.surf.lap', 'discover' -> 'island.discover'"""
    return "island." + name.replace("_", ".", 1)


def finalize_at(x, peak_db, fade_out, onset_db, hp_hz, max_len, tail_db, pre_roll=0.0005):
    """sfxkit.finalize, and where it began: (the finished samples, the index of the first sample kept of x), so that a mark in
    the recipe's own time can be moved to the finished file's"""
    x = np.asarray(x, dtype=np.float64)
    if hp_hz:
        x = signal.sosfiltfilt(_sos("hp", hp_hz, 2), x) if len(x) > 30 else x
    a = np.abs(x)
    pk = a.max() + 1e-12
    on = np.nonzero(a > pk * db(onset_db))[0]
    start = max(0, on[0] - ns(pre_roll)) if len(on) else 0
    env = maximum_filter1d(a, size=ns(0.01) + 1)
    alive = np.nonzero(env > pk * db(tail_db))[0]
    end = alive[-1] + 1 if len(alive) else len(x)
    x = x[start:end]
    if max_len:
        x = x[: ns(max_len)]
    x = fade(x, fin=pre_roll if start > 0 else 0.0, fout=min(fade_out, len(x) / SR * 0.5))
    return peak_norm(x, db(peak_db)), start


def render_effect(name, v):
    """one variant of a one-shot, finished: trimmed, faded, its peak at peak_db, and its loudness (the loudest 100 ms) at the
    recipe's target, turned down to it or soft-limited up to it. Returns (samples, marks)"""
    r = RECIPES[name]
    out = r["fn"](v, rng_for("island/" + name, v))
    marks = {}
    if isinstance(out, tuple):
        out, marks = out
    if np.ndim(out) == 2:
        return render_stereo_effect(r, out), marks
    x = lead_in(out, LEAD)
    ml = r["max_len"][v] if isinstance(r["max_len"], (list, tuple)) else r["max_len"]
    target = r["loud"][v] if isinstance(r["loud"], (list, tuple)) else r["loud"]

    def finish(y, peak_db=r["peak_db"]):
        y, start = finalize_at(y, peak_db, r["fade_out"], r["onset_db"], r["hp_hz"], ml, r["tail_db"])
        # (the zero-phase high-pass leaves a faint run-up before a hard onset: the file starts from true zero)
        k = min(len(y), ns(0.001))
        y[:k] *= np.sin(np.linspace(0.0, np.pi / 2, k, endpoint=False)) ** 2
        return y, start

    y, start = finish(x)
    if target is not None:
        now = short_lufs(y)
        if now >= target:  # (already louder than asked: the whole sound is turned down)
            y, start = finish(x, r["peak_db"] - (now - target))
        else:
            xn = x / (np.max(np.abs(x)) + 1e-12)
            lo, hi = db(-r["limit_db"]), 1.0
            for _ in range(12):  # the soft limit that gives the asked loudness: lower ceiling, denser sound
                c = float(np.sqrt(lo * hi))
                y, start = finish(limiter(xn, c, 0.002, 0.05))
                if short_lufs(y) < target:
                    hi = c
                else:
                    lo = c
            y, start = finish(limiter(xn, float(np.sqrt(lo * hi)), 0.002, 0.05))
    shift = LEAD - start / SR
    return y, {k: float(t + shift) for k, t in marks.items()}


def render_stereo_effect(r, out):
    """a stereo one-shot, finished: both channels trimmed alike (from the first moment either is heard until both are gone),
    faded, their peak at peak_db together, and turned down to the recipe's loudness if they are louder (the loudest 100 ms of
    the two added together). No soft limiting: a stereo sting is music, it is made at its level"""
    x = np.stack([lead_in(c, LEAD) for c in np.asarray(out, dtype=float)])
    if r["hp_hz"]:
        x = np.stack([signal.sosfiltfilt(_sos("hp", r["hp_hz"], 2), c) for c in x])
    a = np.max(np.abs(x), axis=0)
    pk = a.max() + 1e-12
    on = np.nonzero(a > pk * db(r["onset_db"]))[0]
    start = max(0, on[0] - ns(0.0005)) if len(on) else 0
    alive = np.nonzero(maximum_filter1d(a, size=ns(0.01) + 1) > pk * db(r["tail_db"]))[0]
    end = alive[-1] + 1 if len(alive) else x.shape[1]
    x = x[:, start:end]
    if r["max_len"]:
        x = x[:, : ns(r["max_len"])]
    x = np.stack([fade(c, fin=0.0005 if start > 0 else 0.0, fout=min(r["fade_out"], x.shape[1] / SR * 0.5)) for c in x])
    x = x * (db(r["peak_db"]) / (np.max(np.abs(x)) + 1e-12))
    if r["loud"] is not None:
        now = short_lufs(x.mean(axis=0))
        if now > r["loud"]:
            x = x * db(r["loud"] - now)
    return x


# =========================================================================== seamless loops
def render_loop(name, v):
    """a loop, finished: the middle period of the three the recipe laid out, high-passed, levelled to its RMS with its peaks
    held under the ceiling, and turned to start at a quiet zero crossing. A stereo loop is levelled and limited as one, and
    turned by the same amount in both channels"""
    r = RECIPES[name]
    ring = Ring(r["loop"])
    x = np.asarray(r["fn"](ring, rng_for("island/" + name, v)), dtype=float)
    st = x.ndim == 2
    chans = x if st else x[None, :]
    chans = np.stack([hp(c, r["hp_hz"], 2) for c in chans])
    for _ in range(5):
        mid = chans[:, ring.n:2 * ring.n]
        chans = chans * (db(r["rms_db"]) / (float(np.sqrt(np.mean(mid ** 2))) + 1e-12))
        mid = chans[:, ring.n:2 * ring.n]
        if np.max(np.abs(mid)) <= db(r["ceiling_db"]) * 1.001:
            break
        link = np.max(np.abs(chans), axis=0)
        lim = limiter(link, ceiling=db(r["ceiling_db"]), lookahead=0.003, release=0.08)
        chans = chans * np.where(link > 1e-9, lim / np.maximum(link, 1e-9), 1.0)
    mid = chans[:, ring.n:2 * ring.n]
    k = loop_start_index(mid.mean(axis=0))
    mid = np.roll(mid, -k, axis=1)
    return mid if st else mid[0]


def loop_start_index(y):
    """where to turn a loop so that it starts at a quiet rising zero crossing (the game starts the loop from silence, so that
    start is not a click; the loop itself is circular, so turning it changes no join): the cog's loop_start, as an index"""
    env = np.sqrt(uniform_filter1d(y * y, size=ns(0.012)))
    lo = lp(y, 500.0, 2)
    cross = np.nonzero((lo[:-1] < 0) & (lo[1:] >= 0))[0] + 1
    if not len(cross):
        return 0
    cost = env[cross] / (env.max() + 1e-12) + 6.0 * np.abs(y[cross]) / (env.max() + 1e-12)
    return int(cross[int(np.argmin(cost))])


# =========================================================================== small helpers
def wob(dur, rng, rate, depth):
    """a wander about 1 (within +-depth), as make_ship_audio.spread"""
    return spread(dur, rng, rate, depth)


def env_at(points, dur, smooth=0.01):
    """a curve through (time, value) points, its corners rounded"""
    return path(points, dur, smooth)


def pad_to(x, n):
    return fit(np.asarray(x, dtype=float), n)


# =========================================================================== water
def wash_noise(dur, rng, centre, lo=150.0, hi=9000.0, bands=6, width=1.2, ruffle=0.4, rate=45.0, slope=-1.0):
    """white water, unit RMS: noise in `bands` bands from lo to hi Hz, every band ruffled by a level of its own that never
    keeps still (the thousand small breakings of a foam) and weighted by how near it lies to `centre` (Hz; a number or a curve
    over the time, width in octaves), so the wash is brighter or darker as the centre moves"""
    n = ns(dur)
    centre = np.broadcast_to(np.asarray(centre, dtype=float), (n,))
    edges = np.geomspace(lo, hi, bands + 1)
    out = np.zeros(n)
    for a, b in zip(edges[:-1], edges[1:]):
        fc = float(np.sqrt(a * b))
        band = rn(bp(colored(dur, rng, slope), a, min(b, SR * 0.45), 2))
        w = np.exp(-0.5 * (np.log2(fc / centre) / width) ** 2)
        mod = np.exp(ruffle * 2.0 * (2 * smooth_random(dur, rng, rate) - 1))
        out += band * w * mod
    return rn(out)


def fizz(dur, rng, rate, r_lo=0.4, r_hi=1.6, level=1.0, beta=2.0):
    """the sizzle of foam: a swarm of tiny bubbles (a bubble 0.4 mm across rings at 8 kHz, one 1.6 mm at 2 kHz), `rate` of them
    a second (a number or a curve over the time)"""
    return bubbles(dur, rng, rate, r_lo, r_hi, beta=beta, level=level, weight=0.4)


def curve(t, pts):
    """a piecewise-linear curve through (time, value) points, read at the times t"""
    xs, ys = zip(*sorted(pts))
    return np.interp(t, xs, ys)


def hush(x, shelf=-5.0, cut=9000.0):
    """water is not white: the top of a sound let down a little, as the cog's water is"""
    return lp(eq(x, "highshelf", 3000, 0.7, shelf), cut, 2)


def on_the_shore(x, wet=0.06, seed=2701):
    """open shore: a quick slap back off the sand and the swell, and nothing more"""
    return reverb(x, wet=wet, rt60=1.0, predelay=0.02, hf_damp=0.8, seed=seed, early_span=0.05)


def on_the_rocks(x, wet=0.14, seed=2702):
    """a rocky shore: a hard slap back off the rock faces, a short tail of it"""
    return reverb(x, wet=wet, rt60=1.5, predelay=0.03, hf_damp=0.65, seed=seed, early_span=0.07)


def in_a_cave(x, wet=0.7, rt60=3.6, seed=2703, echoes=((0.19, 0.30), (0.43, 0.2), (0.81, 0.12)), cut=1500.0, pad=2.0):
    """inside a sea cave: a long dark tail (the high end dies quickly on wet rock) and a few echoes slapping back from its
    walls, each duller than the last"""
    y = np.concatenate([np.asarray(x, dtype=float), np.zeros(ns(pad))])
    out = y.copy()
    for k, (d, g) in enumerate(echoes):
        out = out + g * late(lp(y, cut * 0.8 ** k, 2), d, len(y))
    return reverb(out, wet=wet, rt60=rt60, predelay=0.03, hf_damp=0.9, seed=seed, early_span=0.1)


def sheet_of_water(dur, rng, env, centre, fizz_rate=900.0, fizz_level=0.6, lo=150.0, hi=9000.0, ruffle=0.4):
    """a sheet of foam running over sand or rock: a wash of noise that brightens and darkens as `centre` moves and swells and
    dies with `env` (a curve over the time, 0..1), with the fizz of its tiny bubbles on top, as many of them as env says"""
    n = ns(dur)
    env = np.broadcast_to(np.asarray(env, dtype=float), (n,))
    w = wash_noise(dur, rng, centre, lo, hi, ruffle=ruffle) * env
    f = rn(fizz(dur, rng, fizz_rate * env ** 1.5 + 2.0)) * env ** 0.8
    return w + fizz_level * f


# <<surf
# =========================================================================== the surf
def wave_break(rng, size=1.0, soft=1.0, dur=1.0, thump=0.3, crack=0.7):
    """the break of a small wave on a sandy shoal: the lip comes over and falls (a quick burst of white water, brightest in its
    first instants, a soft, deep 'whumf' under it as the air is caught, the big bubbles closing with a bloop or two) and
    the foam boils and settles. size makes it bigger and slower. Starts from zero at 0, the burst beginning at 0.02 s"""
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    atk = 0.03 * size ** 0.5 / soft
    env = np.sin(np.pi / 2 * np.clip((t - 0.02) / atk, 0, 1)) ** 2 * np.exp(-np.maximum(t - 0.02 - atk, 0) / (0.17 * size ** 0.6))
    burst = wash_noise(dur, rng, 1100.0 * np.exp(-t / 0.35) + 450.0, lo=120.0, hi=8000.0, ruffle=0.35) * env
    m.add(burst, 0.0, 0.8 * crack)
    m.add(hann_burst(0.008, rng, 1600, 7500) * 0.7, 0.02, 0.18 * crack / soft)
    body = sine_sweep(0.5, 118 / size ** 0.3, 52 / size ** 0.2, 4.0) * np.exp(-tvec(0.5) / (0.1 * size ** 0.5))
    body = body * np.clip(tvec(0.5) / 0.012, 0, 1)
    m.add(saturate(body, 2.0), 0.02, thump * size ** 0.5)
    for r in rng.uniform(12.0, 34.0, 2) * size ** 0.4:
        m.add(nrm(bubble(r, rng, rise=0.35, max_dur=0.4)), 0.05 + rng.uniform(0.0, 0.14), 0.16)
    boil = fizz(dur, rng, 1500.0 * np.exp(-t / 0.3) * size ** 0.5 + 10.0, 0.45, 2.2) * np.sin(np.pi / 2 * np.clip((t - 0.02) / 0.05, 0, 1))
    m.add(boil, 0.0, 0.45)
    return fit(m.out(), n)


def surf_lap_wave(rng, dur, tb, size=1.0, soft=1.0, run=1.3, wash_level=1.0, back=1.0, fizz_back=1.0, trickle=1.0,
                  thump=0.25, crack=0.55, hiss=1.0, late_rill=0.0):
    """one wave rolling up a sandy beach and running back, from its first rustle to the last of the water draining away (dur
    seconds; the break at tb). The wave coming in (a hush of rushing water that grows), the break, the wash of foam running
    up the sand (a thick, low boil at first, thinning to a hiss as the sheet slows and the sand drinks it), the moment it
    stands still, and the backwash: a long hiss as the water drains back down through the sand, soft, high and sizzling,
    little streams finding their way, a trickle, the last drops. Returns the samples and where the wave stalls"""
    n = ns(dur)
    t = tvec(dur)
    x = np.maximum(t - tb, 0.0)
    m = Bus(dur)
    # the wave coming in: a hush that grows to the break and goes on a moment under it
    pre = np.clip(t / tb, 0, 1) ** 2.2
    approach = wash_noise(dur, rng, 380.0 + 600.0 * pre, lo=100.0, hi=3500.0, ruffle=0.3, rate=18.0)
    m.add(approach * 0.42 * pre * np.exp(-x / 0.3) * size ** 0.3, 0.0, 1.0)
    m.add(lp(brown(dur, rng), 170, 2) * pre ** 1.5 * np.exp(-x / 0.2), 0.0, 0.2 * size)
    # the break
    m.add(wave_break(rng, size, soft, min(1.2, dur - tb), thump, crack), tb - 0.02, 1.0)
    # the wash running up: a thick boil that thins to a hiss as it slows
    ts = tb + run * size ** 0.5
    rise = np.sin(np.pi / 2 * np.clip(x / (0.09 * size ** 0.5), 0, 1)) ** 2
    up = rise * (0.55 * np.exp(-x / (0.3 * size ** 0.5)) + 0.45 * np.exp(-x / (1.05 * size ** 0.5)))
    stall = np.clip((ts + 0.8 - t) / 1.3, 0, 1)
    swash = sheet_of_water(dur, rng, up * stall, 650.0 + 1700.0 * (1 - np.exp(-x / 0.9)), fizz_rate=1100.0, fizz_level=0.65,
                           ruffle=0.5)
    m.add(swash, 0.0, 0.95 * wash_level * size ** 0.3)
    # the backwash: soft, high, hissing down through the sand
    b0 = ts - 0.45
    bu = np.clip((t - b0) / 0.9, 0, 1)
    bd = np.exp(-np.maximum(t - (b0 + 0.9), 0) / (1.1 * size ** 0.3)) * np.clip((dur - t) / 0.5, 0, 1)
    be = np.sin(np.pi / 2 * bu) ** 2 * bd
    sizzle = wash_noise(dur, rng, 3300.0 - 1200.0 * np.clip((t - b0) / 2.5, 0, 1), lo=900.0, hi=9500.0, width=0.9,
                        ruffle=0.45, rate=14.0) * be
    m.add(sizzle, 0.0, 0.5 * back * hiss)
    low = wash_noise(dur, rng, 520.0, lo=120.0, hi=2000.0, width=1.0, ruffle=0.3, rate=9.0) * be
    m.add(low, 0.0, 0.2 * back)
    fz = rn(fizz(dur, rng, (1400.0 * be ** 1.3 + 3.0) * fizz_back, 0.4, 1.4, 1.0, beta=2.2)) * be ** 0.7
    m.add(fz, 0.0, 0.4 * back)
    # little streams finding their way down: a run of plinks and larger bubbles
    tr = rn(bubbles(dur, rng, (40.0 * be ** 1.2 + 0.5) * trickle, 1.4, 3.6, beta=1.7, level=1.0, weight=0.5, rise=0.5)) * be ** 0.5
    m.add(tr, 0.0, 0.2 * trickle)
    for tt in np.sort(rng.uniform(ts + 0.3, dur - 0.4, int(5 * trickle))):
        m.add(drip(rng), tt, rng.uniform(0.04, 0.1) * trickle)
    if late_rill:  # a rill running a moment after the rest, the sand giving the water back from further up
        r0 = ts + late_rill
        rt = np.clip((t - r0) / 0.25, 0, 1) * np.exp(-np.maximum(t - r0 - 0.25, 0) / 0.5)
        rill = wash_noise(dur, rng, 2600.0, lo=700.0, hi=8500.0, width=0.7, ruffle=0.5, rate=20.0) * rt
        m.add(rill, 0.0, 0.3)
    return fit(m.out(), n), ts


@recipe("surf_lap", variants=6, peak_db=-3.0, fade_out=0.4, max_len=[4.0, 3.2, 4.8, 4.2, 4.6, 4.8], tail_db=-62.0, loud=-17.0,
        limit_db=6.0, onset_db=-90.0, subtitle="Wave rolls up the beach", reach=24)
def surf_lap(v, rng):
    """a wave rolling up a sandy beach, six ways, each a soft break, the wash running up the sand and the hiss of it draining
    back down through the sand. 1 an ordinary lap; 2 a small one, a ripple of a break, quick; 3 a bigger wave, a deeper break,
    a long run up and a long slow draining; 4 a spilling wave, more foam than thump, the sizzle thick on the way back; 5 a lap
    whose backwash is met by a second rill of water running a moment late; 6 a lazy one, the break hardly a break, a long creeping
    wash and a long soft hiss"""
    plan = [dict(dur=4.2, tb=0.40, size=1.0),
            dict(dur=3.4, tb=0.34, size=0.55, soft=1.2, thump=0.12, wash_level=0.7),
            dict(dur=5.0, tb=0.46, size=1.5, thump=0.35, crack=0.8, back=1.1),
            dict(dur=4.4, tb=0.38, size=0.9, soft=1.5, thump=0.1, crack=0.6, fizz_back=1.5),
            dict(dur=4.8, tb=0.42, size=1.1, late_rill=0.55, trickle=1.3),
            dict(dur=5.0, tb=0.50, size=0.8, soft=2.0, thump=0.08, crack=0.45, run=1.7, back=1.15)][v]
    x, ts = surf_lap_wave(rng, **plan)
    x = hush(on_the_shore(x, 0.05, 2710 + v), -6.0, 8500.0)
    soft = plan.get("soft", 1.0)
    return x, {"break": plan["tb"] + 0.5 * 0.03 * plan["size"] ** 0.5 / soft, "stall": ts}


def rills(dur, rng, t0, count, level=1.0, fc=(800.0, 3400.0), decay=(0.4, 1.3), spread_t=0.7, glug=1.0, fine=1.0):
    """water pouring off rock in `count` streams, starting from t0 (seconds): each its own little run of white water (a band of
    noise somewhere between fc[0] and fc[1] Hz, ruffled, that swells quickly and drains away at its own rate, decay[0] to
    decay[1] seconds) with the bubbles it throws up (a hundred small ones a second, now and then a big glug)"""
    n = ns(dur)
    t = tvec(dur)
    out = np.zeros(n)
    for k in range(count):
        a = t0 + rng.uniform(0.0, spread_t)
        tau = rng.uniform(*decay)
        env = np.clip((t - a) / 0.07, 0, 1) ** 1.5 * np.exp(-np.maximum(t - a - 0.07, 0) / tau)
        f = float(np.exp(rng.uniform(np.log(fc[0]), np.log(fc[1]))))
        band = rn(bp(colored(dur, rng, -1.0), f / 1.6, f * 1.6, 2))
        mod = np.exp(0.9 * (2 * smooth_random(dur, rng, rng.uniform(12.0, 35.0)) - 1))
        g = rng.uniform(0.5, 1.0)
        out += g * band * mod * env
        pings = bubbles(dur, rng, (90.0 * fine) * env ** 1.5 + 0.2, 1.0, 3.2, beta=1.9, level=1.0, weight=0.5, rise=0.5)
        out += 0.35 * g * rn(pings) * env ** 0.6
        gl = bubbles(dur, rng, 10.0 * glug * env ** 1.3 + 0.1, 4.0, 11.0, beta=1.6, level=1.0, weight=0.6, rise=0.3)
        out += 0.25 * g * rn(gl) * env ** 0.4
    return level * rn(out) * np.sqrt(count) / np.sqrt(max(count, 1))


def spray_burst(dur, rng, at, size=1.0, bright=1.0, rain=1.0):
    """the spray thrown up by a wave breaking on rock: a hiss of the finest drops (a sharp rise, a fall over a third of a
    second), a thick shower of the bigger ones ticking down after, thinning"""
    t = tvec(dur)
    x = np.maximum(t - at, 0.0)
    env = np.sin(np.pi / 2 * np.clip(x / (0.025 * size ** 0.5), 0, 1)) ** 2 * np.exp(-x / (0.26 * size ** 0.5)) * (t >= at)
    fine_ = bp(colored(dur, rng, -1.0), 1800.0 * bright, 10500.0, 2) * env
    shower = grain_cloud(dur, rng, 650.0 * rain * size ** 0.5 * (np.exp(-x / (0.5 * size ** 0.5)) * (t >= at)) + 2.0,
                         decay=0.0005, f_lo=1500.0 * bright, f_hi=9000.0, size_var=0.9)
    shower = shower * np.sin(np.pi / 2 * np.clip(x / 0.05, 0, 1)) ** 2
    return rn(fine_) * 0.8 * env.max() + 0.6 * rn(shower) * (x < 1.5) , env


def rock_slam(rng, dur, t0, size=1.0, hard=1.0, boom=1.0, spray=1.0, roar=1.0, pour=1.0, rumble=0.0, hollow=0.0,
              streams=7, hiss=0.0):
    """a wave breaking against rock, from the first of its rush to the water running off: the swell surging up to the rock
    (a hush that grows), the blow (a deep thump of the water's weight, driven so that its harmonics carry, a broad crash of
    white water, the slap of its front, the ledge and the pool under it sounding a deep low boom), the spray flung up (a hiss of
    the finest drops, a shower of the bigger ones), the white water boiling over and settling, and the water pouring off
    the rock in a dozen streams that thin and drain. hollow: how much of a hollow, gulping gully the rock has; hiss: a blowhole's
    breath of air and spray through a crack. The blow is at t0 seconds"""
    n = ns(dur)
    t = tvec(dur)
    x = np.maximum(t - t0, 0.0)
    m = Bus(dur)
    pre = np.clip(t / t0, 0, 1) ** 2.0
    rush = wash_noise(dur, rng, 350.0 + 900.0 * pre, lo=90.0, hi=3500.0, ruffle=0.3, rate=16.0)
    m.add(rush * np.clip(t / t0, 0, 1) ** 3.0 * np.exp(-x / 0.06), 0.0, 0.2)
    # the blow
    th = thump(0.8, 96 / size ** 0.25, 38.0, rng, decay=0.15 * size ** 0.5, click=0.12, noise=0.55, nlp=380)
    m.add(saturate(th, 2.3), t0, 1.0 * boom)
    cr_env = np.sin(np.pi / 2 * np.clip(x / 0.008, 0, 1)) ** 2 * np.exp(-x / (0.1 * size ** 0.6)) * (t >= t0)
    crash = wash_noise(dur, rng, 1500.0 * np.exp(-x / 0.25) + 450.0, lo=120.0, hi=6500.0, ruffle=0.35) * cr_env
    m.add(crash, 0.0, 0.95 * hard)
    m.add(hann_burst(0.005, rng, 1300, 8500), t0, 0.4 * hard)
    ex = lp(white(0.03, rng), 420) * np.exp(-tvec(0.03) / 0.008)
    low = resonators(fit(ex, ns(1.4)), np.array([57.0, 84.0, 124.0, 181.0]) / size ** 0.1, [0.65, 0.5, 0.36, 0.26],
                     [1.0, 0.75, 0.5, 0.35])
    m.add(nrm(low), t0, 0.5 * boom)
    if hollow:
        for f in (rng.uniform(120, 190), rng.uniform(210, 330)):
            m.add(nrm(cavity(rng, f=f, glide=rng.uniform(0.1, 0.3), tau=rng.uniform(0.04, 0.09))), t0 + rng.uniform(0.02, 0.16),
                  0.45 * hollow)
    # the spray
    sp, sp_env = spray_burst(dur, rng, t0 + 0.01, size, 1.0, spray)
    m.add(sp, 0.0, 0.7 * spray)
    # the white water boiling over and settling
    r_env = np.clip(x / 0.1, 0, 1) ** 1.2 * np.exp(-x / (0.75 * size ** 0.5)) * (t >= t0)
    boil = sheet_of_water(dur, rng, r_env, 900.0 * np.exp(-x / 1.0) + 380.0, fizz_rate=900.0, fizz_level=0.5, lo=100.0,
                          hi=7500.0, ruffle=0.5)
    m.add(boil, 0.0, 0.8 * roar * size ** 0.3)
    if rumble:
        m.add(lp(brown(dur, rng), 150, 4) * np.clip(x / 0.15, 0, 1) * np.exp(-x / 1.0) * (t >= t0), 0.0, 0.5 * rumble)
    # pouring off the rock
    m.add(rills(dur, rng, t0 + 0.28, streams, 1.0, decay=(0.5, 1.5 * size ** 0.4), glug=1.0), 0.0, 0.62 * pour)
    sheet = wash_noise(dur, rng, 1500.0, lo=400.0, hi=7000.0, width=1.1, ruffle=0.6, rate=22.0) \
        * np.clip((x - 0.25) / 0.2, 0, 1) * np.exp(-np.maximum(x - 0.25, 0) / (1.1 * size ** 0.4)) * (t >= t0)
    m.add(sheet, 0.0, 0.4 * pour)
    if hiss:
        d = 0.7
        hs = tvec(d)
        fc = 900.0 + 1600.0 * np.sin(np.pi * np.clip(hs / d, 0, 1)) ** 0.8
        blow = tvf(white(d, rng), "bp", fc, 1.4) * bell(d, 0.3, 1.4, 1.8) * (0.7 + 0.3 * smooth_random(d, rng, 30))
        m.add(blow, t0 + 0.12, 0.6 * hiss)
    tail_n = int(6 * pour)
    for tt in np.sort(t0 + 1.0 + rng.exponential(0.8, tail_n)):
        if tt < dur - 0.3:
            m.add(drip(rng), tt, rng.uniform(0.04, 0.1))
    return fit(m.out(), n)


def sea_cave_boom(rng, dur, t0, size=1.0, drain=1.0, second=0.0, gasp=0.0, far=0.0):
    """a wave booming into a sea cave: the sea pushing the air in front of it in (a hollow, rising rush), the blow, a deep,
    hollow thud (a body of rock and air ringing at its lowest modes, 40 to 130 Hz, driven so that its harmonics carry on a small
    speaker), the cave's long dark echo, and the water draining out of it: runnels, a long sucking slide as the sea withdraws,
    gulps and plinks in the crevices. Dry: the cave's own room is put round it after"""
    n = ns(dur)
    t = tvec(dur)
    x = np.maximum(t - t0, 0.0)
    m = Bus(dur)
    pre = np.clip(t / t0, 0, 1) ** 1.8
    m.add(wash_noise(dur, rng, 200.0 + 380.0 * pre, lo=50.0, hi=2200.0, ruffle=0.3, rate=12.0) * pre ** 1.7 * np.exp(-x / 0.08),
          0.0, 0.22)

    def blow(at, g, sz):
        d = 0.9
        body = sine_sweep(d, 84 / sz ** 0.25, 33.0, 3.0) * np.exp(-tvec(d) / (0.26 * sz ** 0.4)) * np.clip(tvec(d) / 0.014, 0, 1)
        m.add(saturate(body, 2.8), at, 1.0 * g)
        ex = lp(white(0.04, rng), 330) * np.exp(-tvec(0.04) / 0.01)
        modes = resonators(fit(ex, ns(2.0)), np.array([41.0, 58.0, 87.0, 126.0, 171.0]) / sz ** 0.08,
                           [1.5, 1.2, 0.9, 0.65, 0.45], [1.0, 0.85, 0.65, 0.45, 0.3])
        m.add(nrm(modes), at, 0.8 * g)
        thud = strike(0.5, rng, np.array([118.0, 190.0, 276.0, 395.0]) / sz ** 0.1, [0.16, 0.12, 0.09, 0.06],
                      [1.0, 0.7, 0.5, 0.3], lo=70, hi=900, ex=0.006)
        m.add(nrm(thud), at, 0.45 * g)
        d2 = 0.6
        e2 = np.sin(np.pi / 2 * np.clip(tvec(d2) / 0.012, 0, 1)) ** 2 * np.exp(-tvec(d2) / (0.11 * sz ** 0.5))
        m.add(wash_noise(d2, rng, 600.0, lo=70.0, hi=2600.0, ruffle=0.3) * e2, at, 0.5 * g)
        m.add(hann_burst(0.006, rng, 700, 3200), at, 0.1 * g)

    blow(t0, 1.0, size)
    if second:
        blow(t0 + second, 0.55, size * 0.7)
    # the water draining out
    d0 = t0 + 0.5
    de = np.clip((t - d0) / 0.3, 0, 1) ** 1.2 * np.exp(-np.maximum(t - d0 - 0.3, 0) / (1.5 * drain))
    run = rills(dur, rng, d0, int(6 * drain), 1.0, fc=(500.0, 2400.0), decay=(0.8, 2.0 * drain), spread_t=1.2, glug=2.5, fine=0.7)
    m.add(run, 0.0, 0.36 * drain)
    sw = np.clip((t - d0) / 0.6, 0, 1) * np.clip((d0 + 1.9 - t) / 0.7, 0, 1)
    suck = tvf(pink(dur, rng), "bp", 1100.0 * np.exp(-np.clip((t - d0) / 1.8, 0, 1) * 1.4) + 150.0, 1.1) * sw ** 1.3
    m.add(suck, 0.0, 0.22 * drain)
    m.add(rn(bubbles(dur, rng, 28.0 * de ** 1.0 + 0.3, 2.0, 13.0, beta=1.6, level=1.0, weight=0.6, rise=0.4)) * de ** 0.5, 0.0,
          0.3 * drain)
    for tt in np.sort(d0 + 0.6 + rng.exponential(1.0, int(7 * drain))):
        if tt < dur - 0.4:
            m.add(drip(rng), tt, rng.uniform(0.05, 0.14))
    if gasp:  # the breath the cave lets out as the sea falls back: a long soft 'hooo' through the mouth
        d = 1.4
        gt = tvec(d)
        fc = 520.0 - 220.0 * np.clip(gt / d, 0, 1)
        m.add(tvf(pink(d, rng), "bp", fc, 1.8) * bell(d, 0.35, 1.5, 1.6) * (0.75 + 0.25 * smooth_random(d, rng, 5)), t0 + 0.9,
              0.5 * gasp)
    return fit(m.out(), n)


@recipe("surf_crash", variants=6, peak_db=-3.0, fade_out=0.5, max_len=[4.6, 3.4, 5.0, 4.2, 4.8, 5.0], tail_db=-62.0,
        loud=[-14.0, -15.5, -12.5, -14.0, -14.0, -14.0], limit_db=8.0, onset_db=-90.0, subtitle="Wave crashes on rocks", reach=32)
def surf_crash(v, rng):
    """a wave crashing on rock, six ways: the swell rushing up, the blow (a deep thump, a crash of white water), the spray
    flung up and showering down, the water pouring off the rock. 1 an ordinary crash; 2 a small one slapping a ledge, quick;
    3 a big one, a deep boom you feel, a long pouring; 4 a spray-thrower, a long fine hiss and shower; 5 a hollow gully, the water
    gulping in it; 6 a blowhole, the air and spray whistling out through a crack after the blow"""
    plan = [dict(dur=4.6, t0=0.80),
            dict(dur=3.4, t0=0.60, size=0.6, boom=0.8, streams=5),
            dict(dur=5.0, t0=0.95, size=1.6, boom=1.15, rumble=1.0, streams=9),
            dict(dur=4.2, t0=0.75, size=0.9, spray=1.6, hard=1.1),
            dict(dur=4.8, t0=0.80, size=1.1, hollow=1.0, streams=8),
            dict(dur=5.0, t0=0.85, size=1.2, hiss=1.0, boom=0.9)][v]
    x = rock_slam(rng, **plan)
    x = hush(on_the_rocks(x, 0.12, 2720 + v), -5.0, 9000.0)
    return x, {"impact": plan["t0"]}


@recipe("surf_cave", variants=4, peak_db=-3.0, fade_out=1.0, max_len=[5.6, 5.0, 5.8, 5.4], tail_db=-62.0, loud=-14.0,
        limit_db=8.0, onset_db=-90.0, subtitle="Sea booms in a cave", reach=32)
def surf_cave(v, rng):
    """the sea booming in a cave, four ways: the air pushed in ahead of the wave, a hollow thud, a long dark echo, the water
    draining out down the rock. 1 an ordinary boom; 2 a smaller one, nearer the mouth; 3 a heavy one with a second, smaller
    boom coming back off the far wall; 4 a boom followed by the long breath of the cave letting its air out"""
    plan = [dict(dur=3.8, t0=0.70),
            dict(dur=3.4, t0=0.60, size=0.7, drain=0.8),
            dict(dur=4.0, t0=0.80, size=1.4, drain=1.2, second=1.15),
            dict(dur=3.8, t0=0.70, size=1.0, gasp=1.0)][v]
    x = sea_cave_boom(rng, **plan)
    x = in_a_cave(x, wet=0.75, rt60=3.8, seed=2730 + v)
    return x, {"impact": plan["t0"]}


# surf>>

# <<wind
# =========================================================================== the wind
def wind_weights(f, g, c0=190.0, c_mul=3.8, tilt0=-8.0, tilt1=-4.0, floor=0.08, power=1.7, top=7000.0):
    """the spectrum of open wind rushing by (as the walls' rush_mag, set for the open sea): a broad hump that climbs from c0 Hz
    to c0 * c_mul Hz as the gust strengthens (g, 0..1) on a slope that falls tilt dB an octave above it, little under 55 Hz or
    over `top`; louder as the gust gets up"""
    f = np.maximum(f, 1.0)
    cen = c0 * c_mul ** g
    o = np.log2(f / cen)
    tilt = tilt0 + (tilt1 - tilt0) * g
    hump = np.exp(-0.5 * (o / 1.5) ** 2)
    shelf = np.where(o > 0, 10 ** (o * tilt / 20.0), np.exp(-0.5 * (o / 1.9) ** 2))
    edge = 1 / np.sqrt(1 + (55.0 / f) ** 4) / np.sqrt(1 + (f / top) ** 8)
    return (floor + (1 - floor) * g) ** power * (0.55 * hump + 0.45 * shelf) * edge


def wind_rush(noise, g, lo=60.0, hi=8000.0, per_oct=1.4, ruffle=0.25, rate=30.0, rng=None, ring=None, **kw):
    """wind rushing past, unit RMS: noise (a function that gives a fresh white noise) split into bands a third of an octave
    apart or so, each one weighted by wind_weights at its own centre (so the whole spectrum swells, brightens and darkens with
    the gust g, a curve over the time) and ruffled by a level of its own that never keeps still (the thousand eddies of a
    wind). On a Ring (ring=) the ruffles are curves of whole cycles of the period"""
    nb = int(round(per_oct * np.log2(hi / lo)))
    edges = np.geomspace(lo, hi, nb + 1)
    out = 0.0
    for a, b in zip(edges[:-1], edges[1:]):
        fc = float(np.sqrt(a * b))
        band = rn(bp(noise(), a, min(b, SR * 0.45), 2))
        w = wind_weights(fc, g, **kw) * np.sqrt(b - a)
        if ring is not None:
            sm = lp(ring.noise(rng, 0.0), rate * 0.5, 2)
            mod = np.exp(ruffle * 1.4 * sm / (np.std(sm) + 1e-12))
        else:
            mod = np.exp(ruffle * 2.0 * (2 * smooth_random(len(g) / SR, rng, rate) - 1))
        out = out + band * w * mod
    return rn(out)


def edge_tone(n, rng, freq, width):
    """a narrow band of noise about `width` Hz wide round freq (Hz, a number or a curve over n samples): a wavering whistle, as
    the wind makes over an edge of rock or a post"""
    a = ns(0.05)
    z = lp(white((n + a) / SR, rng), width / 2, order=2) + 1j * lp(white((n + a) / SR, rng), width / 2, order=2)
    z = z[a:a + n]
    z /= np.sqrt(np.mean(np.abs(z) ** 2)) + 1e-12
    return np.real(z * np.exp(1j * phase_from_freq(np.broadcast_to(freq, (n,)), n)))


def air_buffet(low, g, puffs):
    """the air buffeting at the ears: low (a brown noise under 120 Hz) swelling in puffs, stronger as the wind gets up"""
    return nrm(low) * puffs * g


def dune_grass(dur, rng, g, rate=1.0, lo=2400.0, hi=8200.0):
    """marram and sea-oats hissing and ticking in the wind: a swarm of fine ticks (a thousand a second at the height of a gust)
    in the top of the spectrum, shimmering as the stems whip, with the soft hiss of the blades between them"""
    shimmer = 0.55 + 0.45 * smooth_random(dur, rng, 28)
    ticks = tame(grain_cloud(dur, rng, (60.0 + 900.0 * g ** 2) * rate, decay=0.0011, f_lo=lo, f_hi=hi, size_var=0.5), 9.0) * shimmer
    hiss = bp(white(dur, rng), lo * 0.8, hi, 2) * shimmer ** 1.5
    return rn(ticks) * 0.7 + rn(hiss) * 0.45


def the_open(x, wet=0.07, seed=2740):
    """open sea air: the faintest slap back off the water, nothing more"""
    return reverb(x, wet=wet, rt60=1.3, predelay=0.04, hf_damp=0.75, seed=seed, early_span=0.07)


@recipe("wind_gust", variants=6, peak_db=-3.0, fade_out=0.8, max_len=[5.0, 4.0, 6.2, 7.0, 6.0, 4.6], tail_db=-62.0, loud=-19.0,
        limit_db=4.0, onset_db=-60.0, subtitle="Wind gusts", reach=32)
def wind_gust(v, rng):
    """a gust of sea wind sweeping over the island, six ways: the rush rising and brightening and sinking away, the hiss and
    tick of dune grass in it, a low buffeting at the height. 1 an ordinary gust; 2 a sharp one, up in a breath and a long fall;
    3 a gust that sings a thin whistle through the rocks at its top; 4 a long swell of wind that builds for a long time and
    lets go, roaring low; 5 two surges with a lull between; 6 a soft sigh of a breeze, mostly grass"""
    plan = [dict(dur=5.0, pts=[(0, 0.04), (0.8, 0.2), (2.0, 1.0), (3.2, 0.55), (4.4, 0.12), (5.0, 0.03)]),
            dict(dur=4.0, pts=[(0, 0.03), (0.4, 0.2), (1.0, 1.0), (1.8, 0.6), (3.0, 0.2), (4.0, 0.03)], grass=1.4),
            dict(dur=6.2, pts=[(0, 0.03), (1.2, 0.25), (2.8, 0.85), (3.6, 0.95), (4.6, 0.4), (5.6, 0.1), (6.2, 0.03)],
                 whistle=(780.0, 1140.0)),
            dict(dur=7.0, pts=[(0, 0.03), (2.0, 0.3), (4.0, 1.0), (5.2, 0.6), (6.2, 0.15), (7.0, 0.03)], buffet=1.4, low=1.3),
            dict(dur=6.0, pts=[(0, 0.03), (0.9, 0.35), (1.6, 0.85), (2.5, 0.25), (3.4, 0.4), (4.1, 1.0), (5.0, 0.3), (6.0, 0.03)],
                 grass=1.2),
            dict(dur=4.6, pts=[(0, 0.04), (1.4, 0.35), (2.2, 0.5), (3.4, 0.2), (4.6, 0.03)], grass=1.5, soft=1.0, sigh=1.0)][v]
    dur = plan["dur"]
    n = ns(dur)
    t = tvec(dur)
    g = env_at(plan["pts"], dur, 0.25)
    g = np.clip(g * (1 + 0.08 * (2 * smooth_random(dur, rng, 6) - 1)), 0.0, 1.0)
    soft = plan.get("soft", 0.0)
    y = wind_rush(lambda: white(dur, rng), g, rng=rng, c0=190.0 * (1.3 if soft else 1.0))
    m = Bus(dur)
    m.add(y, 0.0, 1.0)
    puffs = (0.15 + 0.85 * smooth_random(dur, rng, 2.6)) ** 2.5
    low = lp(brown(dur, rng), 120.0, 4)
    m.add(air_buffet(low, g ** 1.4, puffs), 0.0, 0.16 * plan.get("buffet", 1.0) * (0.4 if soft else 1.0))
    if plan.get("low"):  # the roar of a long swell, low in the chest
        m.add(eq(lp(brown(dur, rng), 260.0, 2), "peak", 110.0, 1.0, 4.0) * g ** 1.6, 0.0, 0.18 * plan["low"])
    m.add(dune_grass(dur, rng, g, 1.0)  * g ** 0.9, 0.0, 0.2 * plan.get("grass", 1.0))
    if plan.get("whistle"):
        f0, f1 = plan["whistle"]
        f = (f0 + (f1 - f0) * g) * (1 + 0.006 * (2 * smooth_random(dur, rng, 2) - 1))
        w = edge_tone(n, rng, f, 11.0) + 0.3 * edge_tone(n, rng, 2.01 * f, 16.0)
        m.add(w * np.clip((g - 0.45) / 0.55, 0, 1) ** 1.4, 0.0, 0.3)
    if plan.get("sigh"):  # a soft breathy band sinking with the breeze
        gg = np.clip((t - 0.35 * dur) / (0.65 * dur), 0, 1)
        sg = tvf(pink(dur, rng), "bp", 900.0 * (330.0 / 900.0) ** gg, 2.0) * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 1.2
        m.add(sg, 0.0, 0.2)
    x = the_open(fit(m.out(), n), 0.06, 2740 + v)
    return lp(x, 9500.0, 2)


@recipe("wind_bed", loop=12.0, stereo=True, rms_db=-27.0, ceiling_db=-8.0, reach=None)
def wind_bed(ring, rng):
    """the steady breeze on the island, wide: a broad rush that swells and sinks every few seconds, low air at the ears, the
    hiss and tick of dune grass in the swells, and now and then a faint whistle in the rocks. Stereo (the game plays a stereo file
    unpositioned, as the wall wind)"""
    P = ring.P
    out = []
    swell0 = ring.curve(rng, 1, 3, 1.1)
    for c in range(2):
        lag = int((0.35 if c else -0.35) * SR)
        sw = np.roll(swell0, lag)
        g = np.clip(0.5 + 0.25 * np.tanh(1.1 * (0.8 * sw + 0.4 * ring.curve(rng, 4, 14, 1.0))), 0.0, 1.0)
        y = wind_rush(lambda: ring.noise(rng, 0.0), g, rng=rng, ring=ring, c0=210.0, c_mul=3.0, power=1.2, floor=0.4)
        puffs = (0.5 + 0.5 * np.tanh(1.4 * ring.curve(rng, 2, 8, 0.9))) ** 2.2
        b = air_buffet(lp(ring.noise(rng, -6.0), 120.0, 4), g ** 1.2, puffs)
        gr = np.zeros(ring.N)
        gm = ring.middle(g)
        gr = ring.circ(dune_grass(P, rng, gm, 0.8)) * g ** 1.0
        x = 1.0 * rn(y) + 0.14 * rn(b) + 0.17 * rn(gr)
        out.append(x)
    # the whistles: two gaps in the rock, singing faintly in the swells, one in each ear's side
    for f0, side, lagt in ((690.0, 0, 3.0), (1030.0, 1, 8.0)):
        sw = np.roll(swell0, int(lagt * SR))
        gu = np.clip(0.5 + 0.5 * np.tanh(1.5 * sw), 0, 1)
        f = f0 * (1 + 0.08 * (gu - 0.5)) * (1 + 0.01 * ring.curve(rng, 4, 20, 1.0))
        ph = periodic_phase(ring, f)
        amp = np.clip(gu - 0.62, 0, 1) ** 1.5 * 3.0 * (0.6 + 0.4 * (0.5 + 0.5 * np.tanh(ring.curve(rng, 4, 18, 1.0))))
        tone = np.sin(ph + rng.uniform(0, 6.28)) + 0.2 * np.sin(2 * ph + rng.uniform(0, 6.28))
        breath = bp(ring.noise(rng, 0.0), f0 * 0.93, f0 * 1.07, 2)
        w = amp * (rn(tone) + 0.6 * rn(breath))
        out[side] = out[side] + 0.16 * w * rms(out[side]) + 0.04 * w * rms(out[1 - side])
    st = np.stack([lp(o, 9500.0, 2) for o in out])
    return st
# wind>>


# <<gulls
# =========================================================================== the gulls
def jit(rng, x, s=0.03):
    """x with a little chance in it (a gull never says a thing twice the same)"""
    return x * np.exp(s * rng.standard_normal())


def gull_phrase(notes, dur):
    """notes: (start, level, samples); mixed into one buffer dur long, each note levelled by its peak"""
    m = Mix(dur)
    for at, g, x in notes:
        m.add(nrm(x), at, g)
    return fit(m.out(), ns(dur))


def kyow(rng, f, dur=0.34, rough=0.35, tp=0.2, rise=0.74, fall=0.58, beak=(1700.0, 2900.0), onset="k", **kw):
    """one 'kyow' of a gull: the voice leaps up to its peak f (Hz) from rise * f in the first fifth of the note and falls long
    to fall * f, through a nasal formant that opens and closes with the beak"""
    kw.setdefault("sag", 0.4)
    kw.setdefault("fall_shape", 1.6)
    return gull_note(dur, rng, rise * f, f, fall * f, tp=tp, rough=rough, beak=beak, onset=onset, **kw)


def run_of(rng, n, f0, f1, d0, d1, g0, g1, gap0, gap1, at=0.0, vary=1.0, **kw):
    """n notes, one after another: the peak pitch moving from f0 to f1 (Hz), the note's length from d0 to d1 (s), its level from
    g0 to g1 and the gap between one note's start and the next's from gap0 to gap1, every number a little unsteady. Returns the
    notes as (start, level, samples) and the time the next one would start"""
    out = []
    for i in range(n):
        u = i / max(n - 1, 1)
        f = jit(rng, f0 + (f1 - f0) * u, 0.025)
        d = jit(rng, d0 + (d1 - d0) * u, 0.07)
        g = (g0 + (g1 - g0) * u) * jit(rng, 1.0, 0.08)
        k = dict(kw)
        for key, spread_ in (("tp", 0.04), ("rise", 0.05), ("fall", 0.05), ("rough", 0.07)):  # (no two notes are the same shape)
            if key in k:
                k[key] = k[key] + spread_ * vary * rng.standard_normal()
        out.append((at, g, kyow(rng, f, d, **k)))
        at += jit(rng, gap0 + (gap1 - gap0) * u, 0.07)
    return out, at


def over_the_water(x, wet=0.09, seed=2750, cut=9500.0):
    """a gull out over open water: the air and one faint slap back off the sea"""
    return reverb(lp(x, cut, 2), wet=wet, rt60=1.3, predelay=0.035, hf_damp=0.75, seed=seed, early_span=0.07)


def from_afar(x, dist, seed=2760):
    """a gull further off: the air takes its top end (more the further it is) and the sea gives back more of it"""
    y = lp(x, max(1900.0, 9000.0 * np.exp(-dist / 60.0)), 2)
    return reverb(y, wet=min(0.5, 0.1 + dist / 150.0), rt60=1.5, predelay=0.02 + dist / 5000.0, hf_damp=0.7, seed=seed,
                  early_span=0.08)


@recipe("gull_call", variants=6, peak_db=-3.0, fade_out=0.25, max_len=[4.8, 3.4, 3.2, 4.2, 4.0, 4.4], tail_db=-60.0, loud=-15.0,
        limit_db=5.0, onset_db=-40.0, subtitle="Gull calls", reach=64)
def gull_call(v, rng):
    """a gull's long call, six ways: a few low, soft notes, then the run of 'kyow's, louder and higher, quicker and quicker, and a
    falling tail. 1 the long call of a herring gull, in full; 2 a laughing run (ha-ha-ha-ha-hah-hah, quickening, sinking);
    3 a short call of five; 4 a pair (one near, one far answering); 5 a high, excited run with a squeal at the top; 6 a far,
    lazy call over the water"""
    if v == 0:
        a, t = run_of(rng, 2, 640, 700, 0.14, 0.15, 0.35, 0.45, 0.2, 0.2, 0.05, onset="h", rough=0.15, beak=(1300, 2100))
        b, t = run_of(rng, 8, 1050, 1420, 0.40, 0.31, 0.7, 1.0, 0.42, 0.33, t + 0.1)
        c, t = run_of(rng, 4, 1250, 900, 0.3, 0.38, 0.9, 0.45, 0.36, 0.5, t + 0.06, rough=0.3)
        return over_the_water(gull_phrase(a + b + c, t + 0.6), 0.1, 2751)
    if v == 1:
        a, t = run_of(rng, 10, 1180, 880, 0.15, 0.2, 0.8, 0.95, 0.27, 0.15, 0.0, tp=0.35, rough=0.3, onset="h", rise=0.8, fall=0.7)
        b, t = run_of(rng, 3, 900, 780, 0.2, 0.28, 0.9, 0.5, 0.28, 0.4, t + 0.02, tp=0.3, rough=0.3, onset="h", rise=0.8, fall=0.65)
        return over_the_water(gull_phrase(a + b, t + 0.5), 0.09, 2752)
    if v == 2:
        a, t = run_of(rng, 5, 1100, 1380, 0.3, 0.26, 0.75, 1.0, 0.36, 0.29, 0.0, rough=0.4)
        b, t = run_of(rng, 1, 1000, 1000, 0.5, 0.5, 0.65, 0.65, 0.2, 0.2, t + 0.05, tp=0.15, rough=0.3, fall=0.5, sag=0.5)
        return over_the_water(gull_phrase(a + b, t + 0.4), 0.09, 2753)
    if v == 3:
        a, ta = run_of(rng, 4, 1000, 1260, 0.38, 0.32, 0.85, 1.0, 0.42, 0.34, 0.0, rough=0.35)
        b, tb = run_of(rng, 4, 1450, 1650, 0.3, 0.26, 0.7, 0.9, 0.36, 0.3, 1.3, rough=0.3, beak=(1900, 3200))
        near = gull_phrase(a, 3.8)
        far_ = from_afar(gull_phrase(b, 3.8), 45, 2754)[: ns(3.8)]
        return over_the_water(near + 0.5 * nrm(far_) * np.max(np.abs(near)), 0.08, 2755)
    if v == 4:
        a, t = run_of(rng, 6, 1350, 1750, 0.28, 0.2, 0.7, 1.0, 0.31, 0.22, 0.0, rough=0.4, tp=0.15, beak=(1900, 3300))
        sq = kyow(rng, 2300.0, 0.5, rough=0.45, tp=0.5, rise=0.7, fall=0.75, beak=(2200, 3600), sag=0.2)
        c, t2 = run_of(rng, 3, 1250, 960, 0.28, 0.34, 0.7, 0.4, 0.34, 0.45, t + 0.55, rough=0.3)
        return over_the_water(gull_phrase(a + [(t + 0.05, 0.95, sq)] + c, t2 + 0.5), 0.1, 2756)
    a, ta = run_of(rng, 7, 960, 1100, 0.4, 0.34, 0.65, 0.85, 0.5, 0.42, 0.0, rough=0.2, beak=(1500, 2400), rise=0.8, fall=0.62)
    return from_afar(gull_phrase(a, ta + 0.5), 55, 2757)

def wing_beat(rng, size=1.0, hard=1.0, dur=0.2):
    """one stroke of a gull's wing: the downstroke pushing air (a low, soft puff), the swish of the long primaries through it
    (a band of noise that sweeps up and down), a fine rattle of feather tips, and a thin crack where the tips meet at the top.
    Over in dur seconds (the puff and the swish die away well inside it)"""
    n = ns(dur)
    t = tvec(dur)
    m = Mix(dur)
    puff = lp(white(dur, rng), 230.0 / size ** 0.3, 2) * bell(dur, 0.3, 1.3, 2.4)
    m.add(nrm(puff), 0.0, 0.6 * hard)
    fc = (700.0 + 1900.0 * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.9) * (1 + 0.1 * (2 * smooth_random(dur, rng, 20) - 1))
    sw = tvf(white(dur, rng), "bp", fc, 0.9) * bell(dur, 0.38, 1.4, 2.6) * (0.75 + 0.25 * smooth_random(dur, rng, 40))
    m.add(nrm(lp(sw, 6500.0, 2)), 0.0, 1.0)
    rat = grain_cloud(dur, rng, 700.0 * bell(dur, 0.45, 1.3, 2.2) + 5, decay=0.0009, f_lo=2000.0, f_hi=6500.0, size_var=0.5)
    m.add(nrm(rat), 0.0, 0.16 * hard)
    m.add(nrm(hann_burst(0.004, rng, 1800, 6000)), dur * 0.55, 0.07 * hard)
    return fit(m.out(), n) * np.clip(t / 0.008, 0, 1)


def wing_run(rng, beats, gaps, dur, g0=1.0, g1=1.0, size=1.0, at=0.0):
    """a run of wing strokes, one every gaps[i] seconds, growing or sinking in level from g0 to g1. Returns the strokes in one
    buffer dur long, and when the next would have come"""
    m = Mix(dur)
    t = at
    for i in range(beats):
        u = i / max(beats - 1, 1)
        gap = gaps[min(i, len(gaps) - 1)]
        m.add(wing_beat(rng, size * jit(rng, 1.0, 0.08), 1.0, dur=min(0.2, 0.92 * gap)), t, (g0 + (g1 - g0) * u) * jit(rng, 1.0, 0.1))
        t += gap * jit(rng, 1.0, 0.07)
    return fit(m.out(), ns(dur)), t


def ruffle(rng, dur, level=1.0):
    """a gull shaking its feathers out: a quick shiver of fine ticks and swishes, loud at the start and dying over the dur"""
    t = tvec(dur)
    e = (1 - np.exp(-t / 0.02)) * np.exp(-t / (0.35 * dur)) * (0.7 + 0.3 * smooth_random(dur, rng, 24))
    ticks = grain_cloud(dur, rng, 1100.0 * e + 10, decay=0.0009, f_lo=1800.0, f_hi=7500.0, size_var=0.6) * e
    sw = bp(white(dur, rng), 600.0, 4800.0, 2) * e * (0.5 + 0.5 * smooth_random(dur, rng, 30))
    return level * (nrm(ticks) * 0.45 + nrm(sw) * 0.7)


@recipe("gull_cry", variants=8, peak_db=-3.0, fade_out=0.12, max_len=[1.0, 1.45, 0.8, 1.1, 1.1, 1.9, 0.9, 0.9], tail_db=-60.0,
        loud=[-15.5, -15.5, -19.0, -17.5, -16.5, -17.0, -15.5, -16.0], limit_db=5.0, onset_db=-40.0,
        subtitle="Gull cries", reach=48)
def gull_cry(v, rng):
    """a gull's single cry, eight ways. 1 a falling kyow; 2 a long keeeow that climbs and falls; 3 a soft, nasal mew; 4 a high
    thin kee-kee-kee; 5 a low double ow-ow; 6 a plaintive wail that climbs slowly; 7 a bright kyeee with a break in the voice;
    8 a hoarse kaah"""
    if v == 0:
        n = kyow(rng, 1280, 0.72, rough=0.35, tp=0.12, rise=0.7, fall=0.46, sag=0.45, fall_shape=1.8, release=0.05, breath=0.07)
        return over_the_water(gull_phrase([(0.0, 1.0, n)], 1.0), 0.09, 2761)
    if v == 1:
        n = kyow(rng, 1560, 1.15, rough=0.25, tp=0.34, rise=0.66, fall=0.5, onset="h", sag=0.35, attack=0.03, release=0.08,
                 beak=(1500, 2900), breath=0.08, fall_shape=1.4)
        return over_the_water(gull_phrase([(0.0, 1.0, n)], 1.45), 0.1, 2762)
    if v == 2:
        n = gull_note(0.55, rng, 1100, 1220, 760, tp=0.25, rough=0.06, rasp=0.1, breath=0.05, onset="h", attack=0.04,
                      beak=(1250, 2100), fall_shape=1.3, release=0.1, thin=0.5, sag=0.3)
        return over_the_water(gull_phrase([(0.0, 1.0, n)], 0.8), 0.08, 2763)
    if v == 3:
        notes = [(i * 0.215, 1.0 - 0.1 * i, kyow(rng, jit(rng, 1900 - 70 * i, 0.02), 0.15, rough=0.2, tp=0.3, rise=0.82, fall=0.7,
                                                 beak=(2100, 3400), onset="h", attack=0.006, sag=0.3)) for i in range(3)]
        return over_the_water(gull_phrase(notes, 1.1), 0.09, 2764)
    if v == 4:
        notes = [(0.0, 1.0, kyow(rng, 880, 0.34, rough=0.45, tp=0.22, rise=0.78, fall=0.6, beak=(1300, 2300), sag=0.4)),
                 (0.5, 0.85, kyow(rng, 840, 0.4, rough=0.45, tp=0.2, rise=0.78, fall=0.55, beak=(1300, 2300), sag=0.45))]
        return over_the_water(gull_phrase(notes, 1.1), 0.1, 2765)
    if v == 5:
        n = gull_note(1.6, rng, 880, 1560, 1180, tp=0.7, rough=0.12, rasp=0.15, breath=0.07, onset="h", attack=0.05,
                      beak=(1400, 2700), fall_shape=1.2, release=0.15, thin=0.6, sag=0.3, follow=0.8)
        return over_the_water(gull_phrase([(0.0, 1.0, n)], 1.9), 0.1, 2766)
    if v == 6:
        a = kyow(rng, 1750, 0.2, rough=0.2, tp=0.5, rise=0.8, fall=1.0, beak=(2000, 3400), onset="h", sag=0.1)
        b = kyow(rng, 2250, 0.4, rough=0.3, tp=0.15, rise=0.85, fall=0.62, beak=(2200, 3800), onset="", sag=0.35, attack=0.004)
        return over_the_water(gull_phrase([(0.0, 0.8, a), (0.17, 1.0, b)], 0.9), 0.09, 2767)
    n = gull_note(0.65, rng, 700, 860, 560, tp=0.15, rough=0.6, rasp=0.5, breath=0.16, onset="h", attack=0.01, beak=(1100, 1900),
                  fall_shape=1.5, release=0.1, thin=1.0, sag=0.4)
    return over_the_water(gull_phrase([(0.0, 1.0, n)], 0.9), 0.1, 2768)


@recipe("gull_alarm", variants=4, peak_db=-3.0, fade_out=0.12, max_len=[1.3, 1.5, 1.4, 1.9], tail_db=-60.0, loud=-14.5,
        limit_db=6.0, onset_db=-40.0, subtitle="Gull screams", reach=48)
def gull_alarm(v, rng):
    """a gull sounding the alarm: harsh, hurried and loud. 1 a run of short kek-kek-keks, quickening; 2 four sharp yeows;
    3 a rapid chatter that wavers in pitch; 4 a scream, held, and a run of keks after it"""
    if v == 0:
        a, t = run_of(rng, 7, 1500, 1750, 0.085, 0.07, 0.7, 1.0, 0.15, 0.1, 0.0, rough=0.55, tp=0.3, rise=0.8, fall=0.7,
                      beak=(1800, 3100), sag=0.25, attack=0.003)
        return over_the_water(gull_phrase(a, t + 0.15), 0.1, 2771)
    if v == 1:
        a, t = run_of(rng, 4, 1900, 2150, 0.22, 0.2, 0.8, 1.0, 0.27, 0.23, 0.0, rough=0.4, tp=0.2, rise=0.78, fall=0.6,
                      beak=(2000, 3500), sag=0.35)
        return over_the_water(gull_phrase(a, t + 0.4), 0.1, 2772)
    if v == 2:
        notes, at = [], 0.0
        for i in range(10):
            f = jit(rng, 1700 + 280 * np.sin(i * 1.7), 0.03)
            notes.append((at, 0.65 + 0.35 * np.sin(np.pi * (i + 0.5) / 10), kyow(rng, f, 0.065, rough=0.55, tp=0.3, rise=0.8, fall=0.72,
                                                                                    beak=(1800, 3200), sag=0.25, attack=0.003)))
            at += jit(rng, 0.1 + 0.004 * i, 0.12)
        return over_the_water(gull_phrase(notes, at + 0.2), 0.1, 2773)
    sq = kyow(rng, 2050, 0.65, rough=0.55, tp=0.35, rise=0.72, fall=0.7, beak=(2100, 3600), sag=0.15, attack=0.02, release=0.08)
    a, t = run_of(rng, 4, 1700, 1500, 0.08, 0.07, 0.85, 0.5, 0.14, 0.17, 0.78, rough=0.5, tp=0.3, rise=0.8, fall=0.7,
                  beak=(1800, 3100), sag=0.25, attack=0.003)
    return over_the_water(gull_phrase([(0.0, 1.0, sq)] + a, t + 0.3), 0.11, 2774)


@recipe("gull_flap", variants=4, peak_db=-3.0, fade_out=0.15, max_len=[1.8, 1.5, 1.5, 1.3], tail_db=-60.0, loud=[-20.0, -21.0, -20.0, -22.0],
        limit_db=6.0, onset_db=-40.0, subtitle="Wings flap", reach=24)
def gull_flap(v, rng):
    """a gull's wings, four ways. 1 taking off, the strokes coming quicker and harder; 2 coming in to land, the strokes slowing
    to a flutter; 3 a few slow strokes passing over; 4 a shake of the feathers and two soft beats"""
    if v == 0:
        x, t = wing_run(rng, 8, [0.27, 0.24, 0.22, 0.2, 0.19, 0.18, 0.18], 1.8, 0.55, 1.0, 1.0, 0.0)
    elif v == 1:
        x, t = wing_run(rng, 6, [0.15, 0.17, 0.2, 0.25, 0.3], 1.5, 1.0, 0.5, 0.9, 0.0)
        x = x + 0.5 * fit(late(ruffle(rng, 0.5, 0.6), t - 0.05), ns(1.5))
    elif v == 2:
        x, t = wing_run(rng, 4, [0.36, 0.38, 0.42], 1.5, 0.85, 1.0, 1.2, 0.0)
        pas = tvf(pink(1.5, rng), "bp", 450.0 + 650.0 * np.sin(np.pi * tvec(1.5) / 1.5), 0.7) * bell(1.5, 0.45, 1.5, 1.5)
        x = x + 0.22 * nrm(pas) * np.max(np.abs(x))
    else:
        x = fit(ruffle(rng, 0.8, 1.0), ns(1.3))
        y, _ = wing_run(rng, 2, [0.3], 1.3, 0.5, 0.7, 0.9, 0.0)
        x = x + 0.7 * late(y, 0.6, ns(1.3))
    return over_the_water(x * np.clip(tvec(len(x) / SR) / 0.01, 0, 1), 0.06, 2780 + v, cut=6200.0)


def bird_splash(dur, rng, size=1.0, hard=1.0):
    """something small dropped on the sea (a gull's breast, a foot, a wing): the slap of the water torn apart, a quick low plop
    of the cavity it opens, a puff of tiny bubbles and a bloop or two of bigger ones, a few drops thrown up and falling back.
    size 1 is a gull coming down on its breast"""
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(bp(white(dur, rng), 380.0, 8500.0, 2) * np.exp(-t / (0.02 * size ** 0.5)) * np.clip(t / 0.0012, 0, 1), 0.0, 0.8 * hard)
    m.add(hann_burst(0.007, rng, 2500, 9500), 0.0, 0.2 * hard)
    m.add(thump(0.3, 210 / size ** 0.3, 90 / size ** 0.2, rng, decay=0.04 * size ** 0.5, click=0.0, noise=0.55, nlp=700.0),
          0.0, 0.5)
    sw = bubbles(dur, rng, 520.0 * size * np.exp(-t / (0.11 * size ** 0.5)) + 8.0, 0.8, 5.0, beta=1.9, level=1.0, weight=0.5, rise=0.3)
    m.add(rn(sw), 0.012, 0.5)
    for r in rng.uniform(4.0, 10.0, 2) * size ** 0.4:
        m.add(nrm(bubble(r, rng, rise=0.3, max_dur=0.16)), rng.uniform(0.005, 0.06), 0.4)
    sp = dur - 0.05
    ts = tvec(sp)
    senv = (1 - np.exp(-ts / 0.05)) * np.exp(-ts / (0.2 * size ** 0.5))
    senv /= senv.max() + 1e-12
    m.add(grain_cloud(sp, rng, 220.0 * size * senv + 4, decay=0.0006, f_lo=1500, f_hi=8500, size_var=0.7) * senv, 0.04, 0.3)
    for tt in np.sort(rng.uniform(0.05, dur * 0.7, int(5 * size ** 0.7) + 1)):
        m.add(drip(rng), tt, rng.uniform(0.05, 0.15))
    return fit(m.out(), n)


@recipe("gull_splash", variants=3, peak_db=-3.0, fade_out=0.3, max_len=[1.6, 1.4, 1.7], tail_db=-60.0, loud=-18.5, limit_db=6.0,
        onset_db=-40.0, subtitle="Gull splashes down", reach=24)
def gull_splash(v, rng):
    """a gull on the water. 1 coming down, the strokes slowing, a pat of feet and the breast skidding to a stop in a spray; 2 a
    plunge for a fish, a rush of folded wings, a sharp splash, bubbles, and the wings clawing it back up; 3 a gull bathing,
    ducking and shaking the spray off its wings"""
    dur = [1.6, 1.4, 1.7][v]
    m = Bus(dur)
    if v == 0:
        w, t = wing_run(rng, 4, [0.15, 0.18, 0.22], dur, 1.0, 0.5, 0.9, 0.0)
        m.add(w, 0.0, 0.8)
        at = 0.55
        for k in range(2):
            m.add(bird_splash(0.5, rng, 0.25, 0.5), at - 0.1 + 0.07 * k, 0.4)
        m.add(bird_splash(1.0, rng, 1.0, 0.9), at, 1.0)
        sk = wash_noise(0.6, rng, 1700.0 - 900.0 * tvec(0.6) / 0.6, lo=300.0, hi=8000.0, width=1.0, ruffle=0.4, rate=30.0) * bell(0.6, 0.15, 1.2, 1.8)
        m.add(sk, at, 0.45)
        m.add(ruffle(rng, 0.5, 0.6), at + 0.35, 0.25)
    elif v == 1:
        dv = tvec(0.45)
        fall = tvf(white(0.45, rng), "bp", 2800.0 - 1700.0 * dv / 0.45, 0.8) * bell(0.45, 0.8, 1.6, 1.0)
        m.add(fall, 0.0, 0.35)
        at = 0.4
        m.add(bird_splash(1.0, rng, 0.7, 1.0), at, 1.0)
        m.add(rn(bubbles(0.8, rng, 120.0 * np.exp(-tvec(0.8) / 0.22) + 6.0, 0.9, 6.0, beta=1.8, level=1.0, weight=0.5, rise=0.4)), at + 0.06, 0.45)
        w, _ = wing_run(rng, 5, [0.17, 0.15, 0.15, 0.15], 0.9, 0.6, 1.0, 1.0, 0.0)
        m.add(w, at + 0.42, 0.6)
    else:
        at = 0.0
        for i in range(5):
            m.add(bird_splash(0.6, rng, rng.uniform(0.2, 0.4), 0.5), at, 0.7 * jit(rng, 1.0, 0.15))
            at += rng.uniform(0.17, 0.3)
        m.add(ruffle(rng, 0.8, 0.9), at, 0.7)
        w, _ = wing_run(rng, 3, [0.24, 0.24], 0.7, 0.8, 0.6, 0.8, 0.0)
        m.add(w, at + 0.1, 0.45)
    x = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.005, 0, 1)
    return over_the_water(x, 0.08, 2795 + v)


@recipe("gull_squabble", variants=3, peak_db=-3.0, fade_out=0.2, max_len=[2.7, 2.2, 2.9], tail_db=-60.0, loud=[-15.5, -15.5, -17.5],
        limit_db=5.0, onset_db=-40.0, subtitle="Gulls squabble", reach=40)
def gull_squabble(v, rng):
    """two gulls fighting over a scrap: harsh calls flung back and forth, cut across each other, and a clatter of wings.
    1 over a scrap of fish, 2 a chase, one shrieking, 3 a quarrel further off"""
    dur = [2.7, 2.2, 2.9][v]
    fa, fb = (1050.0, 1500.0) if v != 1 else (1250.0, 1900.0)
    notes_a, notes_b = [], []
    t = 0.0
    k = 0
    while t < dur - 0.5:
        who = notes_a if k % 2 == 0 else notes_b
        f = (fa if k % 2 == 0 else fb) * jit(rng, 1.0, 0.05)
        d = rng.uniform(0.07, 0.2) if rng.random() < 0.7 else rng.uniform(0.25, 0.4)
        who.append((t, rng.uniform(0.6, 1.0), kyow(rng, f, d, rough=rng.uniform(0.4, 0.65), tp=rng.uniform(0.15, 0.4),
                                                    rise=rng.uniform(0.72, 0.85), fall=rng.uniform(0.55, 0.75),
                                                    beak=(1600, 2900) if k % 2 == 0 else (1900, 3300), sag=0.3,
                                                    onset="k" if rng.random() < 0.7 else "h")))
        t += rng.uniform(0.07, 0.24) if rng.random() < 0.75 else rng.uniform(0.28, 0.45)
        k += 1
    x = gull_phrase(notes_a, dur + 0.3) + 0.9 * gull_phrase(notes_b, dur + 0.3)
    if v == 1:
        sq = kyow(rng, 2300, 0.55, rough=0.5, tp=0.4, rise=0.72, fall=0.7, beak=(2200, 3800), sag=0.15, attack=0.02)
        x = x + 0.9 * np.max(np.abs(x)) * fit(late(nrm(sq), 1.15), len(x))
    wingsx, _ = wing_run(rng, 7, [0.14, 0.12, 0.2, 0.13, 0.16, 0.12], dur + 0.3, 0.5, 0.8, 1.0, 0.35)
    x = x + 0.25 * np.max(np.abs(x)) * nrm(wingsx)
    if v == 2:
        return from_afar(x, 28.0, 2791)
    return over_the_water(x, 0.1, 2790 + v)



def colony_call(rng, kind):
    """one call of the colony, as one bird says it: kinds 0 kyow, 1 a pair of kyows, 2 a mew, 3 a run of keks, 4 a laugh, 5 a
    wail, 6 a chick's thin peeping"""
    f = jit(rng, 1250.0, 0.18)
    if kind == 0:
        return nrm(kyow(rng, f, rng.uniform(0.35, 0.7), rough=rng.uniform(0.25, 0.45), tp=rng.uniform(0.12, 0.3), rise=rng.uniform(0.68, 0.8),
                        fall=rng.uniform(0.45, 0.62), sag=0.4))
    if kind == 1:
        a, t = run_of(rng, 2, f, f * 0.93, 0.34, 0.4, 0.9, 0.8, 0.42, 0.4, 0.0, rough=0.35)
        return gull_phrase(a, t + 0.3)
    if kind == 2:
        return nrm(gull_note(rng.uniform(0.4, 0.6), rng, f * 0.85, f * 0.95, f * 0.6, tp=0.25, rough=0.06, rasp=0.1, breath=0.05,
                             onset="h", attack=0.04, beak=(1250, 2100), fall_shape=1.3, release=0.1, thin=0.5, sag=0.3))
    if kind == 3:
        a, t = run_of(rng, int(rng.integers(3, 6)), f * 1.25, f * 1.3, 0.08, 0.07, 0.8, 1.0, 0.13, 0.11, 0.0, rough=0.55, tp=0.3,
                      rise=0.8, fall=0.7, beak=(1800, 3100), sag=0.25, attack=0.003)
        return gull_phrase(a, t + 0.15)
    if kind == 4:
        a, t = run_of(rng, int(rng.integers(4, 8)), f * 0.95, f * 0.72, 0.14, 0.19, 0.8, 0.9, 0.26, 0.16, 0.0, tp=0.35, rough=0.3,
                      onset="h", rise=0.8, fall=0.7)
        return gull_phrase(a, t + 0.3)
    if kind == 5:
        return nrm(gull_note(rng.uniform(0.9, 1.3), rng, f * 0.7, f * 1.2, f * 0.95, tp=0.65, rough=0.12, rasp=0.15, breath=0.07,
                             onset="h", attack=0.05, beak=(1400, 2700), fall_shape=1.2, release=0.15, thin=0.6, sag=0.3, follow=0.8))
    notes = [(i * rng.uniform(0.09, 0.12), 1.0 - 0.15 * i, gull_note(0.07, rng, 3300, 3900, 3000, tp=0.3, rough=0.05, rasp=0.1,
                                                                        breath=0.02, onset="", attack=0.005, beak=(3500, 5200),
                                                                        thin=0.3, sag=0.2)) for i in range(int(rng.integers(2, 5)))]
    return gull_phrase(notes, len(notes) * 0.12 + 0.2)


@recipe("gull_colony", loop=12.0, rms_db=-27.0, ceiling_db=-8.0, reach=48)
def gull_colony(ring, rng):
    """a colony of gulls on a ledge of rock: dozens of birds at every distance calling over one another, a steady clamour that
    never repeats (kyows, mews, runs of keks, laughs, now and then a long wail, a chick's thin peeping, a flutter of wings),
    with the cliff giving it back"""
    buf = ring.new()
    kinds = np.array([0, 1, 2, 3, 4, 5, 6, 7])
    weights = np.array([0.25, 0.1, 0.16, 0.1, 0.08, 0.05, 0.14, 0.05])
    for at in ring.times(rng, 4.6, shape=1.3):
        k = int(rng.choice(kinds, p=weights / weights.sum()))
        if k == 7:
            ev, _ = wing_run(rng, int(rng.integers(2, 5)), [0.2, 0.22, 0.26], 1.2, 0.9, 0.6, 1.0, 0.0)
        else:
            ev = colony_call(rng, k)
        dist = float(np.exp(rng.uniform(np.log(6.0), np.log(90.0))))
        ev = lp(ev, max(2400.0, 10000.0 * np.exp(-dist / 55.0)), 2) * (1.0 / (1.0 + dist / 10.0)) * (0.25 if k == 7 else 1.0)
        ring.place(buf, ev, at, 1.0)
    # a clamour has no long lull (a loop would bring the quietest stretch back every twelve seconds, the one place where it
    # could be told from the colony itself): where the ring is quietest, a bird not far off calls
    w = ns(0.7)
    for _ in range(6):
        p = ring.middle(buf) ** 2
        cs = np.concatenate([[0.0], np.cumsum(np.concatenate([p, p[:w]]))])
        m = (cs[w:w + ring.n] - cs[:ring.n]) / w  # the mean power of the stretch that starts at each sample
        i = int(np.argmin(m))
        if m[i] > 0.3 * np.median(m):
            break
        ev = colony_call(rng, int(rng.choice([0, 0, 1, 2, 3, 4])))
        dist = float(rng.uniform(9.0, 26.0))
        ev = lp(ev, max(2400.0, 10000.0 * np.exp(-dist / 55.0)), 2) * (1.0 / (1.0 + dist / 10.0))
        ring.place(buf, ev, (i / SR + 0.35 - 0.25 * len(ev) / SR) % ring.P, 1.0)
    x = reverb(buf, wet=0.3, rt60=2.3, predelay=0.03, hf_damp=0.65, seed=2801, early_span=0.1)
    return lp(x, 9000.0, 2)
# gulls>>


# <<lighthouse
# =========================================================================== the lighthouse
def tower_room(x, wet=0.16, rt60=1.1, seed=2810):
    """the lamp room and the stair of a round stone tower: close, hard slaps and a short stony tail"""
    return reverb(x, wet=wet, rt60=rt60, predelay=0.006, hf_damp=0.55, seed=seed, early_span=0.025)


@recipe("lighthouse_turn", loop=8.0, rms_db=-27.0, ceiling_db=-9.0, reach=24)
def lighthouse_turn(ring, rng):
    """the clockwork that turns the lamp, one turn of the lens in the loop: the escapement ticking and tocking twice a second
    (a small brass wheel and its pallet, one tick high and one low), the purr of the gear train behind it, the lens carriage
    rolling heavy on its wheels (a low rumble that swells and sinks), a chain link clinking now and then, and once a turn a
    latch dropping home (a heavy clunk) and the great lens sweeping past (a breath of air and the glass ringing faintly)"""
    ticks = ring.new()
    for k in range(16):
        hi = k % 2 == 0
        f = (2250.0 if hi else 1720.0) * rng.uniform(0.985, 1.015)
        ev = strike(0.15, rng, f * np.array([1.0, 2.76, 5.4]), [0.035, 0.02, 0.012], [1.0, 0.45, 0.2], lo=1200, hi=9000, ex=0.0007)
        ev = nrm(ev) + 0.45 * nrm(fit(oak(0.12, rng, 400.0 if hi else 330.0, t60=0.04, hard=1.0), len(ev)))
        ring.place(ticks, ev, k * 0.5 + 0.0015 * rng.standard_normal(), (1.0 if hi else 0.78) * jit(rng, 1.0, 0.05))
    # the gear train: a purr of teeth (33 turns of a toothed wheel a second, a whole number in the loop)
    ph = periodic_phase(ring, np.full(ring.N, 33.0))
    purr = bp(ring.noise(rng, -1.0), 450.0, 2600.0, 2) * (0.35 + 0.65 * (0.5 + 0.5 * np.sin(ph)) ** 1.6)
    purr = purr * (0.6 + 0.4 * (0.5 + 0.5 * np.tanh(ring.curve(rng, 3, 12, 1.0))))
    # the lens carriage on its wheels: a low rolling rumble, a slow thrum as the wheels cross the joints of the track
    ph2 = periodic_phase(ring, np.full(ring.N, 5.0))
    roll = lp(ring.noise(rng, -6.0), 200.0, 4) * (0.55 + 0.45 * (0.5 + 0.5 * np.sin(ph2)) ** 2) \
        * (0.7 + 0.3 * (0.5 + 0.5 * np.tanh(ring.curve(rng, 1, 4, 1.0))))
    # a chain clinking as the weight runs down
    chain = ring.new()
    for at in (1.9, 4.35, 6.6):
        for j in range(int(rng.integers(2, 4))):
            ev = iron_ring(0.25, rng, [rng.uniform(1700, 2600)], t60=0.09)
            ring.place(chain, ev, at + j * rng.uniform(0.045, 0.09), rng.uniform(0.35, 0.8))
    # once a turn: the latch dropping home
    clunk = ring.new()
    ev = Mix(0.9)
    ev.add(nrm(oak(0.4, rng, 150.0, t60=0.12, hard=1.1)), 0.0, 1.0)
    ev.add(saturate(thump(0.35, 120, 58, rng, decay=0.06, click=0.0, noise=0.4, nlp=420), 2.2), 0.0, 0.5)
    ev.add(nrm(iron_ring(0.6, rng, [310.0, 470.0], t60=0.3)), 0.0, 0.45)
    ev.add(nrm(hann_burst(0.003, rng, 1500, 7000)), 0.0, 0.3)
    ring.place(clunk, ev.out(), 0.95, 1.0)
    # the lens sweeping by: a soft breath of air, and the glass ringing faintly
    lens = ring.new()
    env = bell(1.6, 0.5, 1.6, 1.6)
    sw = tvf(pink(1.6, rng), "bp", 330.0 + 900.0 * np.sin(np.pi * tvec(1.6) / 1.6), 0.8) * env
    ring.place(lens, sw, 4.9, 1.0)
    ring_ = modal(1.2, np.array([2130.0, 3290.0, 5710.0]) * rng.uniform(0.99, 1.01), [0.7, 0.45, 0.3], [1.0, 0.55, 0.3], rng) \
        * np.clip(tvec(1.2) / 0.05, 0, 1)
    glass = ring.new()
    ring.place(glass, ring_, 5.4, 1.0)
    x = 1.0 * rn(ticks) + 0.2 * rn(purr) + 0.3 * rn(roll) + 0.28 * rn(chain) + 0.55 * rn(clunk) + 0.22 * rn(lens) + 0.07 * rn(glass)
    return lp(tower_room(x, 0.14, 1.0, 2811), 9500.0, 2)


def diaphone(dur, rng, f0, grunt_at, grunt_len=0.55, attack=0.3, drop=0.74, level=1.0):
    """a fog horn of the diaphone kind: a piston chopping compressed air at the pitch f0, a pulse train rich in harmonics, the
    pitch rising a little as the piston gets up to speed, a slow, swelling attack, a steady blast, and at its end the 'grunt':
    the air falls away and the pitch sags to drop * f0 as the level dies. The horn's bell gathers the tone about three
    resonances (330, 660 and 1050 Hz), the top rolls off by two kHz; a chuff of air at the start, a rasp of leakage in it"""
    n = ns(dur)
    t = tvec(dur)
    up = 1.0 - 0.17 * np.exp(-t / 0.13)
    gu = np.clip((t - grunt_at) / grunt_len, 0, 1)
    sag = 1.0 - (1.0 - drop) * np.sin(np.pi / 2 * gu) ** 1.5
    f = f0 * up * sag * (1 + 0.004 * (2 * smooth_random(dur, rng, 3) - 1))
    ph = phase_from_freq(f, n)
    y = np.zeros(n)
    for k in range(1, 42):
        fk = k * f
        if np.max(fk) > 4200:
            break
        h = 1.0 / np.sqrt(1.0 + (fk / 1900.0) ** 4)
        h = h * (0.45 + 1.3 * np.exp(-(np.log2(np.maximum(fk, 1) / 330.0) / 0.55) ** 2)
                 + 0.9 * np.exp(-(np.log2(np.maximum(fk, 1) / 660.0) / 0.42) ** 2) + 0.6 * np.exp(-(np.log2(np.maximum(fk, 1) / 1050.0) / 0.42) ** 2))
        y += (k ** -0.85) * h * np.sin(k * ph + rng.uniform(0, 2 * np.pi))
    # the piston's chop: the pulses are a hair uneven, a rasp of air leaking in the gaps
    leak = tvf(white(dur, rng), "bp", 500.0 + 300.0 * np.sin(2 * np.pi * 0.3 * t), 1.3) * (0.5 + 0.5 * np.sin(ph)) ** 2
    y = rn(y) + 0.1 * rn(leak)
    env = np.sin(np.pi / 2 * np.clip(t / attack, 0, 1)) ** 2 * (1.0 - np.sin(np.pi / 2 * gu) ** 1.2)
    env = env * (1 + 0.05 * (2 * smooth_random(dur, rng, 1.5) - 1))
    y = y * env
    chuff = bp(white(0.3, rng), 150.0, 900.0, 2) * bell(0.3, 0.35, 1.4, 1.8)
    return level * (y + 0.18 * np.max(np.abs(y)) * fit(nrm(chuff), n))


def sea_echo(x, taps, cut=700.0):
    """the sea and the cliffs giving a sound back: taps are (delay s, level), each echo duller than the last"""
    n = len(x)
    out = np.asarray(x, dtype=float).copy()
    for k, (d, g) in enumerate(taps):
        out = out + g * late(lp(x, cut * 0.75 ** k, 2), d, n)
    return out


@recipe("lighthouse_horn", variants=2, peak_db=-3.0, fade_out=0.9, max_len=[6.0, 7.2], tail_db=-62.0, loud=-12.5, limit_db=6.0,
        onset_db=-50.0, subtitle="Foghorn blares", reach=128)
def lighthouse_horn(v, rng):
    """the lighthouse's fog horn, a diaphone blowing over the sea, two ways. 1 one long blast, the pitch swelling up and the grunt
    at the end; 2 a short blast and a long one, lower. The sea and the cliffs give it back in long, dull echoes"""
    if v == 0:
        dur = 4.4
        x = diaphone(dur, rng, 118.0, grunt_at=2.8, grunt_len=0.6, attack=0.35)
        taps = [(1.25, 0.3), (2.6, 0.16)]
    else:
        dur = 5.8
        a = diaphone(0.95, rng, 104.0, grunt_at=0.6, grunt_len=0.35, attack=0.2, level=0.9)
        b = diaphone(3.3, rng, 104.0, grunt_at=2.65, grunt_len=0.6, attack=0.3)
        x = np.concatenate([a, np.zeros(ns(0.25)), b])
        x = fit(x, ns(dur))
        taps = [(1.4, 0.28), (2.9, 0.15)]
    x = fit(x, ns(dur + 3.0))
    x = sea_echo(x, taps)
    y = reverb(x, wet=0.25, rt60=3.0, predelay=0.05, hf_damp=0.9, seed=2820 + v, early_span=0.12)
    return lp(y, 3200.0, 2)


@recipe("lighthouse_bell", variants=3, peak_db=-3.0, fade_out=1.4, max_len=[5.6, 6.4, 8.0], tail_db=-62.0, loud=-13.0, limit_db=4.0,
        onset_db=-50.0, subtitle="Fog bell tolls", reach=64)
def lighthouse_bell(v, rng):
    """the lighthouse's fog bell, a big bronze bell struck by the clockwork hammer, three ways. The strike note an E (330 Hz) over
    the deep hum and prime of a great bell, ringing a long time, the beating of the doublets slow. 1 one blow; 2 a pair, the second
    softer, a breath after the first has gone down; 3 a slow toll of three, each a little softer"""
    dur = 9.0
    table = [(r, a, t * 1.6, b * 0.6) for (r, a, t, b) in BRONZE]
    modes = bell_modes(330.0, rng_for("island", "fogbell"), table=table)
    plan = [[(0.0, 1.0, 1.0)],
            [(0.0, 1.0, 1.0), (1.35, 0.82, 0.95)],
            [(0.0, 1.0, 1.0), (1.7, 0.85, 0.95), (3.4, 0.7, 0.9)]][v]
    m = Mix(dur)
    for at, g, hard in plan:
        at = at + rng.uniform(-0.01, 0.01)
        tik, knock_ = clapper(rng, hard)
        m.add(bell_blow(dur - at, modes, hard=g * hard, fc=3200.0), at, 1.0)
        m.add(tik, at, 0.08 * g)
        m.add(knock_, at, 0.05 * g)
    y = lp(m.out(), 9500.0)
    y = sea_echo(y, [(1.15, 0.1), (2.5, 0.05)], cut=1500.0)
    return reverb(y, wet=0.13, rt60=1.6, predelay=0.03, hf_damp=0.7, seed=2830 + v, early_span=0.08)
# lighthouse>>


# <<wreck
# =========================================================================== the wreck
def hulk_modes(f_lo=55.0, f_hi=1800.0, peaks=(95, 210, 430), count=32):
    """the voice of the wreck's timbers: a great waterlogged hull has more low modes than a living ship's, and they ring longer
    (the same modes in every sound, so that it is one wreck)"""
    return timber_modes(rng_for("island", "hulk"), f_lo, f_hi, count, 0.17, 0.03, peaks=peaks)


def hulk_air(x, wet=0.15, seed=2840, cut=4800.0):
    """a half-sunk hull in a quiet sea: the hollow of the timbers answering, a dull wet tail"""
    return reverb(lp(x, cut, 2), wet=wet, rt60=1.2, predelay=0.012, hf_damp=0.7, seed=seed, early_span=0.04)


@recipe("wreck_creak", variants=6, peak_db=-3.0, fade_out=0.35, max_len=[3.6, 2.6, 2.7, 3.2, 3.2, 3.0], tail_db=-60.0, loud=-18.0,
        limit_db=9.0, onset_db=-45.0, subtitle="Wreck groans", reach=32)
def wreck_creak(v, rng):
    """the old wreck working in the swell, six ways, lower, wetter and slower than a living ship: 1 the hulk groaning long and low
    as a swell lifts it, an 'oo' sliding to 'ah'; 2 two plaintive moans, a plank squealing in each, and a dull knock; 3 timbers
    ticking and popping as they dry and shift; 4 a rib letting go (a crack, a thud) and a long groan sinking after it; 5 a wet,
    rasping drag of one hull plank on another; 6 the wreck settling, three low pops and a creaking sigh, water running out of
    it. Under all of them the slosh and gurgle of the sea in her"""
    modes = hulk_modes
    if v == 0:
        dur = 3.6
        t = tvec(dur)
        rate = path([(0, 4), (0.5, 10), (1.1, 26), (1.8, 42), (2.4, 28), (3.0, 10), (3.5, 4)], dur) * spread(dur, rng, 4, 0.06)
        amp = bell(dur, 0.5, 1.3, 1.6) * 0.85 + 0.15 * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.3
        vowel = [(path([(0, 130), (1.5, 240), (3.6, 160)], dur), 3.5, 1.0), (path([(0, 420), (1.5, 590), (3.6, 450)], dur), 4.0, 0.5)]
        y = creak_voice(dur, rng, rate, amp, modes(55, 1500, peaks=(95, 215)), vowel, jitter=0.12, alt=0.2)
        low = creak_voice(dur, rng, rate * 0.5 * spread(dur, rng, 3, 0.05), amp ** 1.4, modes(40, 400, peaks=(70, 130)), jitter=0.2)
        sl = slosh(dur, rng, 900.0, 0.5)
        y = nrm(y) + 0.4 * nrm(low) + 0.14 * nrm(sl)
    elif v == 1:
        dur = 2.7
        m = Mix(dur)
        for at, d, pts, g in [(0.02, 0.95, [(0, 300), (0.35, 430), (0.95, 350)], 1.0), (1.2, 0.8, [(0, 340), (0.3, 500), (0.8, 380)], 0.85)]:
            a = bell(d, 0.45, 1.3, 1.5)
            m.add(squeal(d, rng, path(pts, d), a, harmonics=10, tilt=1.0, chatter=0.55, wobble=0.025, peak_hz=1100), at, g)
            rate = path([(0, 40), (d * 0.5, 80), (d, 50)], d)
            m.add(creak_voice(d, rng, rate, a * 0.8, modes(70, 1500, peaks=(130, 300)), jitter=0.12), at, 0.7 * g)
        m.add(knock(rng, 0.8, 1.5, 0.5), 2.05, 0.5)
        y = fit(m.out(), ns(dur)) + 0.12 * fit(nrm(slosh(dur, rng, 800.0, 0.4)), ns(dur))
    elif v == 2:
        dur = 2.7
        m = Mix(dur)
        for at, f0, g, size in [(0.1, 180, 0.8, 1.2), (0.52, 240, 0.5, 1.0), (0.83, 150, 0.7, 1.3), (1.35, 330, 0.45, 0.8),
                                (1.62, 210, 0.6, 1.0), (2.15, 270, 0.4, 0.8)]:
            m.add(pop(rng, f0 * rng.uniform(0.95, 1.05), size, rng.uniform(0.5, 0.9)), at + rng.uniform(-0.02, 0.02), g)
            if rng.random() < 0.7:
                d = rng.uniform(0.25, 0.4)
                m.add(creak_voice(d, rng, path([(0, 8), (d * 0.5, 30), (d, 6)], d), bell(d, 0.4, 1.3, 1.6) * 0.5,
                                  modes(70, 1500, peaks=(150, 330)), jitter=0.2), at + 0.05, 0.4)
        y = fit(m.out(), ns(dur))
    elif v == 3:
        dur = 3.2
        m = Mix(dur)
        m.add(pop(rng, 125, 1.7, 1.0), 0.05, 1.0)
        m.add(saturate(thump(0.4, 100, 46, rng, decay=0.08, click=0.0, noise=0.4, nlp=300), 2.2), 0.08, 0.6)
        d = 2.6
        rate = path([(0, 70), (0.3, 36), (1.0, 18), (1.8, 9), (2.6, 4)], d) * spread(d, rng, 4, 0.08)
        m.add(creak_voice(d, rng, rate, bell(d, 0.12, 1.1, 1.3) * 0.9, modes(55, 1500, peaks=(95, 200, 420)),
                          [(path([(0, 260), (2.6, 140)], d), 4.0, 1.0)], jitter=0.12, alt=0.15), 0.4, 0.9)
        y = fit(m.out(), ns(dur)) + 0.1 * fit(nrm(slosh(dur, rng, 800.0, 0.3)), ns(dur))
    elif v == 4:
        dur = 3.2
        rate = path([(0, 70), (0.5, 100), (1.2, 130), (2.0, 110), (2.8, 70), (3.1, 40)], dur) * spread(dur, rng, 8, 0.1)
        amp = bell(dur, 0.45, 1.3, 1.6) * path([(0, 0.7), (1.0, 1.0), (2.0, 0.6), (3.2, 0.5)], dur)
        y = creak_voice(dur, rng, rate, amp, modes(90, 2400, peaks=(180, 420, 800)),
                        [(path([(0, 300), (1.5, 520), (3.2, 360)], dur), 6.0, 1.0)], jitter=0.2, alt=0.3, rub=0.35, unsteady=0.5)
        y = nrm(y)
        gl = Mix(dur)
        for at in rng.uniform(0.3, 2.6, 4):
            gl.add(nrm(gurgle(rng, 1.0)), at, rng.uniform(0.12, 0.25))
        y = y + fit(gl.out(), len(y))
    else:
        dur = 3.0
        m = Mix(dur)
        for at, f0, g, size in [(0.05, 150, 0.8, 1.2), (0.37, 200, 0.55, 1.0), (0.6, 130, 0.65, 1.3)]:
            m.add(pop(rng, f0 * rng.uniform(0.95, 1.05), size, 0.7), at, g)
        d = 1.5
        rate = path([(0, 8), (0.3, 30), (0.7, 48), (1.1, 22), (1.5, 6)], d)
        m.add(creak_voice(d, rng, rate, bell(d, 0.4, 1.3, 1.6) * 0.8, modes(60, 1600, peaks=(110, 240)),
                          [(path([(0, 250), (1.5, 170)], d), 4.0, 0.8)], jitter=0.12), 0.9, 1.0)
        for at in np.sort(rng.uniform(1.0, 2.7, 6)):
            m.add(drip(rng), at, rng.uniform(0.08, 0.2))
        m.add(nrm(slosh(1.8, rng, 800.0, 0.3)), 1.0, 0.18)
        y = fit(m.out(), ns(dur))
    return hulk_air(y, 0.15, 2845 + v)


def tatter(dur, rng, rate, level, lo=300.0, hi=3600.0):
    """a ragged edge of canvas fluttering: a band of rustle chopped by the flutter (rate, Hz, a number or a curve, with the odd
    flap missing), and the dry crinkle of the rotten cloth in it"""
    n = ns(dur)
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    ph = phase_from_freq(rate * (1 + 0.35 * (2 * smooth_random(dur, rng, 4) - 1)), n)
    cyc = np.floor(ph / (2 * np.pi)).astype(int)  # (every flap its own strength, some hardly there)
    gains = np.exp(0.5 * rng.standard_normal(int(cyc.max()) + 2)) * np.where(rng.random(int(cyc.max()) + 2) < 0.2, 0.2, 1.0)
    fl = (0.5 + 0.5 * np.sin(ph)) ** 1.6 * gains[cyc] * (0.6 + 0.4 * smooth_random(dur, rng, 12))
    fl = fl / (np.percentile(fl, 95) + 1e-12)
    lvl = np.broadcast_to(np.asarray(level, dtype=float), (n,))
    band = bp(colored(dur, rng, -1.5), lo, hi) * fl
    cr = grain_cloud(dur, rng, (60.0 + 700.0 * lvl) * fl, decay=0.0008, f_lo=900.0, f_hi=5500.0, size_var=0.9)
    return (rn(band) + 0.45 * rn(tame(cr, 9.0))) * lvl


@recipe("wreck_sail", variants=3, peak_db=-3.0, fade_out=0.4, max_len=[3.4, 3.0, 3.2], tail_db=-60.0, loud=-19.0, limit_db=6.0,
        onset_db=-45.0, subtitle="Torn sail flaps", reach=24)
def wreck_sail(v, rng):
    """what is left of the sail on the broken yard, in the wind, three ways. 1 three lazy flaps as the rag fills and spills, then a
    ragged flutter dying away; 2 a gust, the flutter rising to a rattle and a snap, the loose end of a line tapping the mast;
    3 a long soft ripple along a torn edge, and a rope end knocking at the end"""
    dur = [3.4, 3.0, 3.2][v]
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    if v == 0:
        for at, size, hard, g in [(0.15, 1.5, 0.7, 1.0), (0.85, 1.3, 0.6, 0.8), (1.55, 1.1, 0.5, 0.6)]:
            m.add(canvas_flap(rng, size, hard), at + rng.uniform(-0.02, 0.02), g)
            m.add(flump(rng, size * 0.9), at + 0.25, 0.4 * g)
        lvl = path([(0, 0.0), (1.9, 0.0), (2.2, 0.6), (2.7, 0.35), (3.4, 0.0)], dur)
        m.add(tatter(dur, rng, path([(0, 9), (2.0, 10), (3.4, 6)], dur), lvl), 0.0, 0.5)
    elif v == 1:
        lvl = bell(dur, 0.55, 1.5, 1.4) * 0.9
        m.add(tatter(dur, rng, path([(0, 6), (1.6, 15), (3.0, 8)], dur), lvl), 0.0, 1.0)
        m.add(canvas_flap(rng, 1.4, 1.0), 1.75, 1.0)
        m.add(canvas_flap(rng, 0.8, 0.7), 1.95, 0.5)
        for at in (0.9, 1.1, 1.38, 2.3, 2.45):
            m.add(nrm(strike(0.05, rng, [rng.uniform(900, 1300), rng.uniform(2300, 3100)], [0.014, 0.01], [1, 0.5], lo=600, hi=6000, ex=0.0007)),
                  at, rng.uniform(0.15, 0.3))
        m.add(rn(lp(white(dur, rng), 900.0)) * lvl ** 1.2, 0.0, 0.15)
    else:
        lvl = path([(0, 0.0), (0.6, 0.5), (1.6, 0.6), (2.6, 0.2), (3.2, 0.0)], dur)
        m.add(tatter(dur, rng, path([(0, 5), (1.5, 9), (3.2, 4)], dur), lvl, 250.0, 3000.0), 0.0, 1.0)
        for at, g in [(2.55, 0.5), (2.78, 0.35)]:
            m.add(nrm(oak(0.3, rng, rng.uniform(420, 520), t60=0.07)), at, g)
        m.add(nrm(flump(rng, 1.2)), 0.5, 0.35)
    y = fit(m.out(), n) * np.clip(t / 0.01, 0, 1)
    return reverb(lp(y, 6000.0, 2), wet=0.08, rt60=1.0, predelay=0.02, hf_damp=0.7, seed=2850 + v, early_span=0.05)


@recipe("wreck_bell", variants=2, peak_db=-3.0, fade_out=0.6, max_len=[3.4, 4.2], tail_db=-60.0, loud=-17.0, limit_db=6.0,
        onset_db=-50.0, subtitle="Drowned bell clunks", reach=48)
def wreck_bell(v, rng):
    """the ship's bell on the wreck, cracked and half-drowned, rocked by the swell: a dull 'tunk' (the bronze lower in pitch and
    short in ring, the cracked rim rattling the upper modes at a ragged buzz, the water taking the top) and a gulp of water.
    1 one blow, 2 two uneven blows as the wreck rocks"""
    dur = 5.0
    table = [(r, a, t * 0.5, b * 2.2) for (r, a, t, b) in BRONZE]
    modes = bell_modes(760.0, rng_for("island", "wreckbell"), table=table, detune=0.012)
    plan = [[(0.0, 1.0, 0.8)], [(0.0, 1.0, 0.8), (1.05, 0.7, 0.7)]][v]
    m = Mix(dur)
    for at, g, hard in plan:
        at = at + rng.uniform(-0.01, 0.01)
        tik, kn = clapper(rng, hard)
        blow = bell_blow(dur - at, modes, hard=g * hard, fc=1800.0)
        t = tvec(len(blow) / SR)
        upper = hp(blow, 900.0, 2)
        buzz = 1.0 + 0.7 * np.sin(2 * np.pi * rng.uniform(17, 26) * t + rng.uniform(0, 6.28)) * np.exp(-t / 0.5)
        blow = (blow - upper) + upper * buzz
        m.add(blow, at, 1.0)
        m.add(tik, at, 0.04 * g)
        m.add(kn, at, 0.12 * g)
        m.add(nrm(gurgle(rng, 1.0)), at + 0.12, 0.25 * g)
        m.add(nrm(slosh(0.9, rng, 800.0, 0.3)), at, 0.12 * g)
    y = lp(m.out(), 2600.0, 2)
    return hulk_air(y, 0.14, 2855 + v, cut=3000.0)
# wreck>>


# <<cove
# =========================================================================== the smugglers' cove
def cove_air(x, wet=0.2, seed=2860):
    """a cove between cliffs: hard walls close by, a tail that rings a moment among them"""
    return reverb(x, wet=wet, rt60=1.5, predelay=0.025, hf_damp=0.6, seed=seed, early_span=0.09)


def whistle_tone(dur, rng, f, level=1.0, vib=0.012, vib_rate=5.5, breath=0.12, harm=0.1, attack=0.03, release=0.06):
    """a person whistling: a nearly pure tone on f (Hz, a number or a curve), a shade of vibrato that grows as the note goes on,
    breath through the same band, the octave faint above it"""
    n = ns(dur)
    t = tvec(dur)
    f = np.broadcast_to(np.asarray(f, dtype=float), (n,))
    vr = vib * np.clip(t / (0.3 * dur + 1e-6), 0, 1)
    fv = f * (1 + vr * np.sin(2 * np.pi * vib_rate * t + rng.uniform(0, 6.28)) + 0.003 * (2 * smooth_random(dur, rng, 9) - 1))
    ph = phase_from_freq(fv, n)
    y = rn(np.sin(ph) + harm * np.sin(2 * ph))
    y = y + breath * rn(tvf(white(dur, rng), "bp", fv, 9.0))
    env = np.sin(np.pi / 2 * np.clip(t / attack, 0, 1)) ** 2 * np.cos(np.pi / 2 * np.clip((t - (dur - release)) / release, 0, 1)) ** 2
    return level * y * env * (1 + 0.06 * (2 * smooth_random(dur, rng, 14) - 1))


@recipe("cove_signal", variants=3, peak_db=-3.0, fade_out=0.3, max_len=[2.4, 1.9, 2.1], tail_db=-60.0, loud=-17.0, limit_db=4.0,
        onset_db=-45.0, subtitle="Smuggler whistles", reach=32)
def cove_signal(v, rng):
    """a smuggler whistling the signal across the cove, three ways. 1 a low owl's hoot, two short and one long, down at the end;
    2 a shrill pipe, two short and a long note that climbs and trills; 3 a curlew's call, a long rising note, a trill, and a
    sinking 'oo'. Whistled over water between cliffs, with the cliffs answering"""
    dur = [2.4, 1.9, 2.1][v]
    m = Mix(dur)
    if v == 0:
        m.add(whistle_tone(0.22, rng, path([(0, 470), (0.22, 450)], 0.22), 0.9, vib=0.0, breath=0.2, harm=0.18), 0.0)
        m.add(whistle_tone(0.22, rng, path([(0, 475), (0.22, 440)], 0.22), 0.95, vib=0.0, breath=0.2, harm=0.18), 0.36)
        d = 0.85
        m.add(whistle_tone(d, rng, path([(0, 420), (0.12, 455), (0.5, 440), (d, 380)], d), 1.0, vib=0.01, breath=0.22, harm=0.2,
                           attack=0.06, release=0.15), 0.85)
    elif v == 1:
        m.add(whistle_tone(0.11, rng, 2350.0, 0.8, vib=0.0, breath=0.06, harm=0.04, attack=0.01, release=0.025), 0.0)
        m.add(whistle_tone(0.11, rng, 2380.0, 0.85, vib=0.0, breath=0.06, harm=0.04, attack=0.01, release=0.025), 0.19)
        d = 0.85
        m.add(whistle_tone(d, rng, path([(0, 2150), (0.25, 2500), (d, 3000)], d), 1.0, vib=0.028, vib_rate=11.0, breath=0.08,
                           harm=0.05, attack=0.04, release=0.08), 0.5)
    else:
        m.add(whistle_tone(0.36, rng, path([(0, 940), (0.14, 1250), (0.36, 1750)], 0.36), 1.0, vib=0.0, breath=0.1, harm=0.08,
                           attack=0.025, release=0.05), 0.0)
        for i in range(6):
            m.add(whistle_tone(0.075, rng, jit(rng, 1500 - 55 * i, 0.01), 0.75 - 0.05 * i, vib=0.0, breath=0.08, harm=0.06,
                               attack=0.012, release=0.02), 0.45 + 0.092 * i)
        d = 0.5
        m.add(whistle_tone(d, rng, path([(0, 1100), (d, 930)], d), 0.7, vib=0.012, breath=0.14, harm=0.1, attack=0.05, release=0.12), 1.1)
    x = fit(m.out(), ns(dur))
    return cove_air(x, 0.2, 2861 + v)


@recipe("cove_drip", variants=6, peak_db=-3.0, fade_out=0.5, max_len=[2.4, 2.8, 2.8, 2.4, 3.0, 2.6], tail_db=-60.0, loud=-23.0,
        limit_db=6.0, onset_db=-50.0, subtitle="Water drips", reach=None)
def cove_drip(v, rng):
    """water dripping in a cove cave, six ways, each ringing in the stone: 1 a single bright drop; 2 two drops, the second
    lower; 3 a heavy drop falling into a deep pool, a hollow bloop; 4 three quick drops thinning out; 5 a thin trickle and a
    drop; 6 a drop on dry stone (a tick) and one in the pool after it"""
    dur = [2.4, 2.8, 2.8, 2.4, 3.0, 2.6][v]
    m = Mix(dur)

    def drop(at, r, g=1.0, deep=False):
        if deep:
            bb = nrm(bubble(r, rng, rise=0.25, max_dur=0.3))
            y = bb + 0.6 * nrm(fit(thump(0.2, 140, 70, rng, decay=0.03, click=0.0, noise=0.5, nlp=500), len(bb)))
            m.add(y, at, g)
        else:
            m.add(drip(rng) if r is None else nrm(bubble(r, rng, rise=rng.uniform(0.3, 0.6))), at, g)
            m.add(nrm(hp(white(0.004, rng), 1800) * np.exp(-tvec(0.004) / 0.0007)), at, 0.12 * g)

    if v == 0:
        drop(0.05, 1.5, 1.0)
    elif v == 1:
        drop(0.05, 1.7, 1.0)
        drop(0.85, 2.6, 0.8)
    elif v == 2:
        drop(0.05, 7.5, 1.0, deep=True)
        drop(0.1, 2.0, 0.25)
    elif v == 3:
        for at, r, g in [(0.05, 1.6, 1.0), (0.3, 1.9, 0.85), (0.62, 1.7, 0.65)]:
            drop(at, r, g)
    elif v == 4:
        tr = rn(bubbles(1.8, rng, 18.0 * np.exp(-tvec(1.8) / 1.0) + 2.0, 1.2, 3.4, beta=1.7, level=1.0, weight=0.5, rise=0.5))
        m.add(tr * bell(1.8, 0.25, 1.3, 1.5), 0.0, 0.45)
        drop(1.55, 1.6, 0.9)
    else:
        m.add(nrm(strike(0.12, rng, np.array([2400.0, 3900.0, 6100.0]), [0.04, 0.025, 0.015], [1.0, 0.5, 0.25], lo=1500, hi=9000, ex=0.0006)),
              0.05, 0.8)
        drop(0.95, 2.2, 0.9)
    x = fit(m.out(), ns(dur))
    return in_a_cave(x, wet=0.6, rt60=2.8, seed=2870 + v, echoes=((0.31, 0.22), (0.66, 0.12)), cut=3500.0, pad=1.5)[: ns(dur + 1.0)]


def coin_hit(rng, f0=None, hard=1.0):
    """a coin on wood or stone: a bright, short ring (the modes of a thin disc, a few of them a close pair), a tick at the
    instant"""
    f0 = f0 or rng.uniform(2300, 3900)
    fr = metal_partials(f0, "plate", 6, rng, 0.01)
    y = modal(0.35, fr, np.array([0.22, 0.16, 0.12, 0.09, 0.07, 0.05]) * rng.uniform(0.7, 1.2), [1.0, 0.7, 0.5, 0.4, 0.3, 0.2], rng)
    y = y + 0.4 * modal(0.35, fr * 1.004, np.array([0.2, 0.14, 0.1, 0.08, 0.06, 0.04]), [1.0, 0.7, 0.5, 0.4, 0.3, 0.2], rng)
    return nrm(y) + 0.25 * nrm(fit(hann_burst(0.002, rng, 2000, 9000), len(y)))


def glass_ring(rng, f=1320.0, size=1.0, dur=0.7):
    """a glass bottle struck: the ring of its walls (a few modes, the high ones dying first), the dull knock of the body"""
    fr = f * np.array([1.0, 2.17, 3.57, 5.2]) * rng.uniform(0.98, 1.02, 4)
    y = modal(dur, fr, np.array([0.45, 0.25, 0.16, 0.1]) * size, [1.0, 0.55, 0.3, 0.16], rng)
    return nrm(y)


@recipe("cove_camp", variants=4, peak_db=-3.0, fade_out=0.25, max_len=[2.1, 2.5, 1.9, 2.1], tail_db=-60.0, loud=-21.0, limit_db=8.0,
        onset_db=-40.0, subtitle="Camp stirs", reach=None)
def cove_camp(v, rng):
    """the smugglers' camp, no voices, four ways. 1 a crate set down heavy on the shingle, its contents shifting, the lid
    creaking up and dropping back; 2 a mooring line creaking as the boat tugs at its post; 3 a handful of coins dropped on a
    plank, rattling and spinning to rest; 4 a bottle set down on a cask, a clink against another and a cork squeaking"""
    dur = [2.1, 2.5, 1.9, 2.1][v]
    m = Bus(dur)
    if v == 0:
        m.add(knock(rng, 1.25, 0.6, 0.8, 0.45, 0.8), 0.0, 1.0)
        m.add(nrm(oak(0.2, rng, 520.0, t60=0.05, hard=1.0)), 0.02, 0.4)
        for at in (0.17, 0.26, 0.4):
            m.add(glass_ring(rng, rng.uniform(1700, 2600), 0.35, 0.3), at, rng.uniform(0.1, 0.22))
        m.add(coin_hit(rng), 0.31, 0.15)
        d = 0.7
        m.add(creak_voice(d, rng, path([(0, 18), (0.3, 55), (0.7, 12)], d), bell(d, 0.45, 1.3, 1.5) * 0.8, timber_modes(rng_for("island", "crate"), 220, 3200, 18, 0.06, 0.015, (480, 1100)), jitter=0.12), 0.65, 0.7)
        m.add(knock(rng, 1.5, 0.45, 0.7, 0.35, 0.5), 1.5, 0.6)
        m.add(nrm(oak(0.15, rng, 640.0, t60=0.04, hard=1.0)), 1.52, 0.3)
    elif v == 1:
        sp = np.clip(path([(0, 0.0), (0.4, 0.15), (1.0, 0.55), (1.6, 0.35), (2.0, 0.12), (2.4, 0.0)], dur), 0.0, 1.2)
        m.add(tame(rope_run(dur, rng, sp, lay=14.0, lo=350, hi=2800), 9.0), 0.0, 0.45)
        m.add(squeal(dur, rng, path([(0, 420), (1.0, 560), (2.4, 440)], dur), sp ** 1.6 * 0.5, harmonics=7, tilt=1.2, chatter=0.6,
                     wobble=0.025, peak_hz=1400), 0.0, 0.5)
        rate = path([(0, 6), (0.9, 30), (1.7, 18), (2.4, 4)], dur)
        m.add(creak_voice(dur, rng, rate, bell(dur, 0.45, 1.3, 1.5) * 0.7, timber_modes(rng_for("island", "post"), 120, 2200, 22, 0.1, 0.02, (240, 520)),
                          jitter=0.18), 0.0, 0.7)
        m.add(nrm(oak(0.2, rng, 300.0, t60=0.08)), 1.95, 0.35)
    elif v == 2:
        at = 0.0
        gap = 0.075
        k = 0
        while at < 1.0:
            m.add(coin_hit(rng, hard=1.0), at, (0.4 + 0.6 * rng.random()) * 0.85 ** k)
            at += gap * (0.6 + 0.8 * rng.random())
            gap *= 1.1
            k += 1
        # one coin spinning to rest: quicker and quicker ticks, then a shiver
        sp0 = 1.15
        f0 = rng.uniform(2800, 3400)
        gap = 0.16
        for j in range(14):
            m.add(coin_hit(rng, f0 * (1 + 0.005 * j)), sp0, 0.5 * (0.5 + 0.5 * j / 14.0) * 0.7)
            sp0 += gap
            gap *= 0.78
            if gap < 0.012:
                break
        m.add(oak(0.2, rng, 330.0, t60=0.05), 0.0, 0.25)
    else:
        m.add(nrm(oak(0.2, rng, 260.0, t60=0.05, hard=0.8)), 0.0, 0.7)
        m.add(glass_ring(rng, 1280.0, 0.5, 0.5), 0.0, 0.55)
        m.add(glass_ring(rng, 1850.0, 0.8, 0.7), 0.85, 0.7)
        m.add(glass_ring(rng, 1320.0, 0.6, 0.6), 0.855, 0.45)
        m.add(nrm(hann_burst(0.003, rng, 1500, 7000)), 0.85, 0.15)
        d = 0.32
        m.add(squeal(d, rng, path([(0, 2700), (0.1, 3200), (d, 2900)], d), bell(d, 0.4, 1.2, 1.5), harmonics=5, chatter=0.5, wobble=0.02,
                     peak_hz=3600), 1.35, 0.3)
        m.add(nrm(oak(0.12, rng, 700.0, t60=0.03)), 1.72, 0.2)
    x = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.004, 0, 1)
    return cove_air(lp(x, 9000.0, 2), 0.12, 2880 + v)
# cove>>


# <<shrine
# =========================================================================== the drowned shrine
def drowned(x, wet=0.45, rt60=3.2, seed=2890, cut=5200.0):
    """a hall of stone under the sea: a long, soft, dark tail"""
    return reverb(lp(x, cut, 2), wet=wet, rt60=rt60, predelay=0.04, hf_damp=0.8, seed=seed, early_span=0.12)


def water_wobble(x, rng, rate=2.6, depth_ms=0.45):
    """a sound heard through moving water: the delay it takes is never still, so its pitch wavers a hair and its partials beat
    (a slow sinusoidal delay, wandering, of depth_ms milliseconds)"""
    n = len(x)
    t = np.arange(n) / SR
    d = depth_ms * 1e-3 * SR * (np.sin(2 * np.pi * rate * t + rng.uniform(0, 6.28)) + 0.5 * np.sin(2 * np.pi * rate * 1.7 * t + rng.uniform(0, 6.28)))
    return np.interp(np.arange(n) - d, np.arange(n), x, left=0.0, right=0.0)


@recipe("shrine_drone", loop=12.0, rms_db=-27.0, ceiling_db=-8.0, reach=32)
def shrine_drone(ring, rng):
    """the voice of the drowned shrine: a deep, slow chord (D, A, D, with an E and an F sharp trading places over the loop, so
    that it leans from open to bright and back), every note a pair a hair apart so that it beats and breathes, a veil of glassy
    overtones that come and go, the sea breathing through stone under it. Every note has a whole number of cycles in the loop,
    so it joins itself exactly"""
    P = ring.P
    t = ring.t

    def grid(f):
        return round(f * P) / P

    def breathe(k_lo=1, k_hi=3, depth=0.35):
        return 1.0 - depth + depth * np.tanh(1.2 * ring.curve(rng, k_lo, k_hi, 1.0))

    out = np.zeros(ring.N)
    notes = [(73.4167, 1.0, 0.25), (110.0, 0.62, 0.2), (146.8333, 0.55, 0.3), (220.0, 0.34, 0.4), (293.6667, 0.2, 0.5)]
    for f, g, beat in notes:
        for sgn, w in ((-1, 0.64), (1, 0.36)):  # (the pair unequal, so that the beat swells and sinks and never cancels)
            f1 = grid(f) + sgn * round(beat * P / 2) / P
            ph = rng.uniform(0, 6.28)
            out += g * w * breathe() * np.sin(2 * np.pi * f1 * t + ph)
    # the E and the F sharp, trading places
    for f, centre in ((165.0, 3.0), (183.5, 9.0)):
        env = ring.bumps([centre], [1.0], 1.8, 2.4)
        for sgn in (-1, 1):
            f1 = grid(f) + sgn * 1.0 / P
            out += 0.3 * 0.5 * env * np.sin(2 * np.pi * f1 * t + rng.uniform(0, 6.28))
    out = saturate(out / (np.max(np.abs(out)) + 1e-9), 1.6) * 0.8 + 0.2 * out / (np.max(np.abs(out)) + 1e-9)
    # glassy overtones, high and soft, each swelling in its own time
    glass = np.zeros(ring.N)
    for f, centre, g in ((587.3333, 2.0, 0.5), (880.0, 6.0, 0.4), (734.0, 10.0, 0.35), (1174.6667, 4.5, 0.25)):
        env = ring.bumps([centre], [1.0], 1.6, 2.2) + 0.1
        shim = 1.0 + 0.2 * np.sin(2 * np.pi * round(5.5 * P) / P * t + rng.uniform(0, 6.28))
        glass += g * env * shim * np.sin(2 * np.pi * grid(f) * t + rng.uniform(0, 6.28))
    # the sea breathing through the stone
    sea = bp(ring.noise(rng, -3.0), 160.0, 900.0, 2) * breathe(1, 3, 0.4)
    x = 1.0 * rn(out) + 0.16 * rn(glass) + 0.2 * rn(sea)
    return drowned(x, 0.5, 4.2, 2891, 6500.0)


def tubular(dur, rng, f, t60=3.0, hard=1.0, inharm=1.0):
    """a struck tube of glass or bronze: the modes of a free bar (1, 2.76, 5.4, 8.9 times the lowest) ringing a long time, the
    upper ones dying first, every one a close pair that beats, a bright tick at the blow"""
    r = np.array([1.0, 2.756, 5.404, 8.933]) ** inharm
    fr = f * r * rng.uniform(0.998, 1.002, 4)
    t60s = t60 * np.array([1.0, 0.55, 0.3, 0.18])
    amps = np.array([1.0, 0.5, 0.28, 0.14]) * np.array([1, 1, hard, hard ** 1.5])
    y = modal(dur, fr, t60s, amps, rng) + 0.55 * modal(dur, fr * (1 + rng.uniform(0.0012, 0.003)), t60s * 0.9, amps, rng)
    return nrm(y) + 0.12 * nrm(fit(hann_burst(0.002, rng, 2500, 9000), len(y)))


@recipe("shrine_chime", variants=4, peak_db=-3.0, fade_out=1.0, max_len=[5.0, 5.2, 5.4, 6.0], tail_db=-62.0, loud=-17.5, limit_db=4.0,
        onset_db=-50.0, subtitle="Chimes ring", reach=24)
def shrine_chime(v, rng):
    """chimes ringing under the sea, four ways, glass and bronze tubes tuned to the shrine's chord (D, A, E, F sharp): 1 one high
    A; 2 F sharp falling to D; 3 a rising arpeggio, D, A, E; 4 one deep, broad D, swelling. Heard through moving water: the
    pitch wavers, and a long dark hall rings after them"""
    dur = 7.0
    m = Mix(dur)
    if v == 0:
        m.add(tubular(dur, rng, 880.0, 3.6), 0.0, 1.0)
    elif v == 1:
        m.add(tubular(dur, rng, 740.0, 3.0), 0.0, 0.9)
        m.add(tubular(dur, rng, 587.33, 3.6), 0.52, 1.0)
    elif v == 2:
        for at, f, g in [(0.0, 587.33, 0.8), (0.34, 880.0, 0.9), (0.68, 1318.5, 0.75)]:
            m.add(tubular(dur, rng, f, 3.0), at + rng.uniform(-0.01, 0.01), g)
    else:
        m.add(tubular(dur, rng, 293.67, 5.0, 0.8, 1.0), 0.0, 1.0)
        m.add(tubular(dur, rng, 440.0, 4.0, 0.7, 1.0), 0.08, 0.35)
    y = water_wobble(m.out(), rng, 2.4 + 0.3 * v, 0.5)
    return drowned(y, 0.5, 3.4, 2892 + v, 6000.0)


@recipe("shrine_bubbles", variants=3, peak_db=-3.0, fade_out=0.5, max_len=[3.4, 3.0, 3.8], tail_db=-60.0, loud=-21.0, limit_db=6.0,
        onset_db=-50.0, subtitle="Bubbles rise", reach=24)
def shrine_bubbles(v, rng):
    """bubbles rising out of the drowned shrine, slow and deep, three ways: 1 a long, uneven stream with a glug in the middle; 2
    one big gulp of air and a train of small ones after it; 3 three slow breaths of bubbles, each a handful. Heard through the
    water, with a hush of it round them"""
    dur = [3.4, 3.0, 3.8][v]
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    if v == 0:
        rate = 9.0 * bell(dur, 0.45, 1.3, 1.4) + 0.5
        m.add(bubbles(dur, rng, rate, 2.5, 9.0, beta=1.6, level=1.0, weight=0.6, rise=0.5), 0.0, 1.0)
        m.add(nrm(bubble(21.0, rng, rise=0.3, max_dur=0.5)), 1.45, 0.55)
        m.add(nrm(bubble(13.0, rng, rise=0.3, max_dur=0.4)), 1.62, 0.35)
    elif v == 1:
        m.add(nrm(bubble(24.0, rng, rise=0.3, max_dur=0.6)), 0.15, 0.7)
        m.add(nrm(thump(0.3, 150, 60, rng, decay=0.05, click=0.0, noise=0.4, nlp=400)), 0.15, 0.2)
        rate = 40.0 * np.exp(-t / 0.7) + 1.0
        m.add(bubbles(dur, rng, rate, 1.6, 7.0, beta=1.7, level=1.0, weight=0.6, rise=0.5), 0.3, 0.9)
    else:
        for at in (0.1, 1.2, 2.3):
            dd = 1.0
            rate = (14.0 + rng.uniform(-3, 3)) * bell(dd, 0.3, 1.3, 1.6) + 0.3
            m.add(bubbles(dd, rng, rate, 2.5, 10.0, beta=1.6, level=1.0, weight=0.6, rise=0.5), at, rng.uniform(0.6, 1.0))
    hush_ = lp(pink(dur, rng), 600.0) * bell(dur, 0.5, 1.5, 1.5)
    m.add(hush_, 0.0, 0.12)
    y = fit(m.out(), n)
    y = water_wobble(lp(y, 2600.0, 2), rng, 3.0, 0.25)
    return drowned(y, 0.3, 2.2, 2895 + v, 4200.0)


def vowel_track(dur, rng, seq, smooth=0.05):
    """formant tracks (three of them) gliding through the vowels in `seq` (a list of (time, vowel)) over dur seconds"""
    table = {"u": (310, 870, 2250), "o": (450, 830, 2400), "a": (720, 1180, 2500), "e": (500, 1800, 2500), "i": (290, 2250, 3000),
             "ə": (520, 1350, 2500)}
    tr = []
    for k in range(3):
        pts = [(tt, float(table[v][k])) for tt, v in seq]
        tr.append(path(pts, dur, smooth))
    return tr


def whisper_voice(dur, rng, level=1.0, rate=1.0):
    """one whispered voice: breath through the formants of a run of vowels, in words of one to three syllables with a breath
    between them (a syllable is a seventh of a second or so, a word a few of them), a quick hiss in front of some: speech with
    no words in it. The voice drifts in and out over the dur"""
    n = ns(dur)
    t = tvec(dur)
    seq = []
    amp = np.zeros(n)
    hiss = np.zeros(n)
    at = rng.uniform(0.05, 0.35)
    while at < dur - 0.45:
        for k in range(int(rng.integers(1, 4))):
            d = rng.uniform(0.11, 0.2) / rate
            c = at + d / 2
            seq.append((c, rng.choice(list("uoaeiə"))))
            amp += rng.uniform(0.55, 1.0) * np.exp(-0.5 * ((t - c) / (d * 0.33)) ** 2)
            if rng.random() < 0.5:
                hiss += rng.uniform(0.5, 1.0) * np.exp(-0.5 * ((t - (at - 0.025)) / 0.02) ** 2)
            at += d * rng.uniform(0.9, 1.15)
        at += rng.uniform(0.1, 0.32) / rate
    if len(seq) < 2:
        seq = [(0.2, "o"), (dur - 0.3, "a")]
    f1, f2, f3 = vowel_track(dur, rng, [(0.0, seq[0][1])] + seq + [(dur, seq[-1][1])], 0.035)
    src = hp(white(dur, rng), 200.0, 2)
    y = rn(tvf(src, "bp", f1, 9.0)) + 0.8 * rn(tvf(src, "bp", f2, 10.0)) + 0.4 * rn(tvf(src, "bp", f3, 10.0))
    y = y * np.clip(amp, 0, 1.4)
    y = y + 0.5 * np.std(y) * rn(bp(white(dur, rng), 3300.0, 7200.0, 2)) * np.clip(hiss, 0, 1.2)
    drift = 0.35 + 0.65 * smooth_random(dur, rng, 0.9)
    return level * y * drift * np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.6


@recipe("shrine_whisper", variants=3, peak_db=-3.0, fade_out=0.8, max_len=[3.6, 3.2, 4.0], tail_db=-60.0, loud=-22.0, limit_db=6.0,
        onset_db=-50.0, subtitle="Whispers murmur", reach=None)
def shrine_whisper(v, rng):
    """whispers from the drowned shrine, no words in them: breath through the shapes of vowels, three voices at once, each saying
    a different run of syllables, rising and sinking as the water carries them. 1 three voices, an even murmur; 2 two voices, a
    slower and longer one answering; 3 a crowd, many voices, all low"""
    dur = [3.6, 3.2, 4.0][v]
    m = Bus(dur)
    plan = [[(3.4, 1.0, 0.0, 1.0), (3.2, 0.9, 0.2, 0.8), (3.0, 1.1, 0.4, 0.7)],
            [(2.3, 0.7, 0.0, 1.0), (2.4, 0.8, 0.65, 0.9)],
            [(3.6, 1.0, 0.0, 0.6), (3.5, 1.1, 0.1, 0.6), (3.4, 0.9, 0.25, 0.6), (3.2, 1.2, 0.35, 0.5), (3.0, 1.0, 0.5, 0.5)]][v]
    for d, rate, at, g in plan:
        w = whisper_voice(d, rng, 1.0, rate)
        w = water_wobble(w, rng, rng.uniform(1.8, 3.2), 0.6)
        m.add(w, at, g)
    y = fit(m.out(), ns(dur))
    return drowned(y, 0.5, 3.0, 2898 + v, 7000.0)
# shrine>>


# <<haven
# =========================================================================== the fishing haven
def jetty_air(x, wet=0.1, seed=2910):
    """a harbour with a few small boats and a wooden jetty: open water and the planks, a short slap of both"""
    return reverb(lp(x, 9000.0, 2), wet=wet, rt60=0.9, predelay=0.012, hf_damp=0.7, seed=seed, early_span=0.04)


@recipe("haven_boats", variants=4, peak_db=-3.0, fade_out=0.35, max_len=[3.2, 2.8, 3.4, 3.8], tail_db=-60.0, loud=-19.0, limit_db=9.0,
        onset_db=-40.0, subtitle="Boats creak and knock", reach=24)
def haven_boats(v, rng):
    """small fishing boats moored in the haven, four ways. 1 two hulls nudging, three knocks, softer each time, a creak between
    them; 2 a dory knocking on the jetty piles, an oar squealing in its rowlock; 3 a hull riding up and down on its fender,
    a long squeaking rub and the lap of the water; 4 the swell passing through the haven: several boats creaking and bumping one
    after another, each at its own pitch"""
    dur = [3.2, 2.8, 3.4, 3.8][v]
    m = Bus(dur)
    small = lambda: timber_modes(rng_for("island", "dory"), 130, 2400, 24, 0.09, 0.02, (260, 560))
    if v == 0:
        for at, g, size in [(0.1, 1.0, 0.7), (1.05, 0.7, 0.65), (2.0, 0.45, 0.6)]:
            m.add(knock(rng, 1.15, size, 0.7 * g + 0.2, 0.5, 0.9), at + rng.uniform(-0.03, 0.03), g)
        m.add(creak_voice(0.9, rng, path([(0, 14), (0.4, 40), (0.9, 10)], 0.9), bell(0.9, 0.45, 1.3, 1.5) * 0.7, small(), jitter=0.15), 0.35, 0.5)
        m.add(creak_voice(0.7, rng, path([(0, 12), (0.3, 30), (0.7, 8)], 0.7), bell(0.7, 0.4, 1.3, 1.5) * 0.6, small(), jitter=0.15), 1.5, 0.4)
    elif v == 1:
        for at, g, f in [(0.12, 1.0, 1.35), (0.7, 0.75, 1.3), (0.95, 0.6, 1.4), (1.9, 0.8, 1.35)]:
            m.add(knock(rng, f, 0.45, 0.8, 0.35, 0.6), at, g)
            m.add(nrm(oak(0.15, rng, rng.uniform(540, 660), t60=0.04)), at + 0.005, 0.25 * g)
        d = 0.6
        m.add(squeal(d, rng, path([(0, 1650), (0.2, 1920), (d, 1750)], d), bell(d, 0.4, 1.3, 1.5), harmonics=7, tilt=1.2, chatter=0.55,
                     wobble=0.02, peak_hz=2800), 0.35, 0.35)
        d2 = 0.4
        m.add(squeal(d2, rng, path([(0, 1580), (0.14, 1800), (d2, 1620)], d2), bell(d2, 0.4, 1.3, 1.5), harmonics=7, tilt=1.2, chatter=0.55,
                     wobble=0.02, peak_hz=2800), 1.5, 0.28)
    elif v == 2:
        d = 2.3
        sp = np.clip(path([(0, 0.0), (0.25, 0.4), (0.8, 0.6), (1.2, 0.2), (1.35, 0.1), (1.6, 0.55), (2.1, 0.5), (2.3, 0.0)], d), 0.0, 1.2)
        m.add(squeal(d, rng, 520.0 + 380.0 * sp, sp ** 1.4 * 0.7, harmonics=8, tilt=1.1, chatter=0.6, wobble=0.03, peak_hz=1500), 0.2, 0.7)
        m.add(creak_voice(d, rng, 12 + 70 * sp, sp ** 1.2, small(), jitter=0.14, unsteady=0.5), 0.2, 0.55)
        for at, size in [(0.05, 0.6), (1.4, 0.5), (2.6, 0.45)]:
            m.add(nrm(hull_slap(rng, size, 1.0)), at, 0.5)
        m.add(nrm(slosh(1.2, rng, 900.0, 0.3)), 1.9, 0.2)
    else:
        for k in range(3):  # (the swell, three boats, each answering a little later)
            at = 0.25 + 0.85 * k
            f = [1.1, 1.3, 1.5][k]
            m.add(creak_voice(0.7, rng, path([(0, 10), (0.35, 36), (0.7, 8)], 0.7), bell(0.7, 0.45, 1.3, 1.5) * 0.8,
                              timber_modes(rng_for("island", "dory", k), 110 * f, 2400, 22, 0.09, 0.02, (260 * f, 620 * f)), jitter=0.16), at, 0.7)
            m.add(knock(rng, f, 0.55 + 0.1 * k, 0.7, 0.4, 0.7), at + 0.52, 0.9)
        for at in (0.95, 1.8, 2.7):
            m.add(nrm(hull_slap(rng, rng.uniform(0.5, 0.9), 1.0)), at, 0.35)
        m.add(nrm(slosh(3.2, rng, 900.0, 0.5)), 0.2, 0.25)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.004, 0, 1)
    return jetty_air(y, 0.1, 2911 + v)


@recipe("haven_rope", variants=3, peak_db=-3.0, fade_out=0.25, max_len=[2.6, 2.0, 2.2], tail_db=-60.0, loud=-19.5, limit_db=9.0,
        onset_db=-40.0, subtitle="Rope creaks", reach=None)
def haven_rope(v, rng):
    """mooring line on the jetty, three ways. 1 a line pulled taut by a boat tugging, creaking and ticking as the fibres bite, the
    post knocking; 2 a coil of rope dropped on the planks, a thump and the rope settling and slithering; 3 a line running out
    through a ring and brought up short on a cleat"""
    dur = [2.6, 2.0, 2.2][v]
    m = Bus(dur)
    if v == 0:
        sp = np.clip(path([(0, 0.0), (0.4, 0.2), (1.1, 0.6), (1.7, 0.35), (2.1, 0.1), (2.5, 0.0)], dur), 0.0, 1.2)
        m.add(tame(rope_run(dur, rng, sp, lay=15.0, lo=350, hi=3000), 9.0), 0.0, 0.5)
        m.add(squeal(dur, rng, path([(0, 700), (1.1, 880), (2.5, 720)], dur), sp ** 1.6 * 0.5, harmonics=7, tilt=1.2, chatter=0.6,
                     wobble=0.03, peak_hz=1900), 0.0, 0.45)
        m.add(creak_voice(dur, rng, 5 + 40 * sp, sp ** 1.2, timber_modes(rng_for("island", "bollard"), 140, 2600, 20, 0.07, 0.015, (320, 760)),
                          jitter=0.18), 0.0, 0.5)
        fib = grain_cloud(dur, rng, 40 + 520 * sp, decay=0.0006, f_lo=1200, f_hi=5500, size_var=0.9) * sp
        m.add(nrm(fib), 0.0, 0.2)
        m.add(nrm(oak(0.2, rng, 330.0, t60=0.07)), 2.05, 0.35)
    elif v == 1:
        m.add(nrm(saturate(thump(0.35, 110, 55, rng, decay=0.05, click=0.0, noise=0.5, nlp=380), 2.0)), 0.0, 0.7)
        m.add(nrm(oak(0.2, rng, 240.0, t60=0.06, hard=0.7)), 0.0, 0.5)
        sp = np.clip(path([(0.05, 0.0), (0.15, 0.6), (0.5, 0.3), (0.9, 0.12), (1.3, 0.0)], dur), 0.0, 1.2)
        m.add(tame(rope_run(dur, rng, sp, lay=40.0, lo=450, hi=3600, fibres=1.4), 9.0), 0.0, 0.5)
        for at in (0.35, 0.62, 0.95):
            m.add(nrm(oak(0.1, rng, rng.uniform(300, 420), t60=0.035)), at, rng.uniform(0.12, 0.25))
        m.add(nrm(crinkle(dur, rng, 100 * np.exp(-tvec(dur) / 0.25) + 5, 600, 3600)), 0.05, 0.12)
    else:
        sp = np.clip(path([(0, 0.0), (0.08, 0.9), (0.9, 0.95), (1.25, 0.8), (1.34, 0.0)], dur), 0.0, 1.2)
        m.add(tame(rope_run(dur, rng, sp, lay=30.0, lo=400, hi=4200), 9.0), 0.0, 0.6)
        m.add(squeal(dur, rng, 1100.0 + 500.0 * sp, sp ** 1.8 * 0.5, harmonics=6, tilt=1.3, chatter=0.55, wobble=0.02, peak_hz=2400), 0.0, 0.3)
        m.add(nrm(oak(0.25, rng, 300.0, t60=0.09)), 1.34, 0.9)
        m.add(nrm(hann_burst(0.004, rng, 600, 4500)), 1.34, 0.25)
        m.add(creak_voice(0.5, rng, path([(0, 70), (0.2, 40), (0.5, 8)], 0.5), bell(0.5, 0.2, 1.1, 1.5) * 0.7,
                          timber_modes(rng_for("island", "bollard"), 140, 2600, 20, 0.07, 0.015, (320, 760)), jitter=0.14), 1.36, 0.4)
    y = fit(m.out(), ns(dur)) * np.clip(tvec(dur) / 0.004, 0, 1)
    return jetty_air(y, 0.08, 2915 + v)
# haven>>


# <<discover
# =========================================================================== the discovery
def harp_pluck(rng, f, dur=4.0, t60=None, hard=1.0, pos=None):
    """a harp string plucked with the finger: the partials of a stiff string (a hair sharp, rising), the place of the pluck
    notching some of them out, the high ones dying first and the low ones ringing on; the string's twin beating faintly with it,
    the soft tick of the nail and the knock of the soundboard"""
    pos = pos or rng.uniform(0.13, 0.22)
    nh = int(min(26, 0.44 * SR / f))
    k = np.arange(1, nh + 1)
    fr = f * k * np.sqrt(1 + 5e-5 * k ** 2)
    t60 = t60 or 7.0 * (110.0 / f) ** 0.45
    t60s = t60 / (1 + 0.5 * (k - 1)) ** 0.85
    amps = k ** -0.85 * (0.3 + np.abs(np.sin(np.pi * k * pos))) * hard ** (0.12 * (k - 1))
    y = modal(dur, fr, t60s, amps, rng, attack=0.0008) + 0.3 * modal(dur, fr * 1.0007, t60s * 0.9, amps, rng, attack=0.0008)
    y = nrm(y)
    m = Mix(dur)
    m.add(y, 0.0, 1.0)
    m.add(nrm(hann_burst(0.004, rng, 1200, 6500)), 0.0, 0.05 * hard)
    m.add(nrm(lp(white(0.2, rng), 240.0, 2) * np.exp(-tvec(0.2) / 0.03)), 0.0, 0.1 * hard)
    return fit(m.out(), ns(dur))


def flute_line(dur, rng, notes, glide=0.05, vib=0.004, breath=0.05, bright=1.0):
    """a low wooden flute playing a legato line: notes are (start, end, Hz), the pitch gliding from one to the next in `glide`
    seconds, every note swelling in and sinking back; harmonics falling off quickly (dark, round, a low register), vibrato
    that comes in after the note has begun, breath through the tone and a breath of chiff at the start of each note"""
    n = ns(dur)
    t = tvec(dur)
    pts = []
    for a, b, f in notes:
        pts += [(a, f), (b, f)]
    fcurve = path(sorted(pts), dur, glide)
    env = np.zeros(n)
    for a, b, f in notes:
        d = b - a
        e = np.sin(np.pi / 2 * np.clip((t - a) / 0.1, 0, 1)) ** 2 * np.cos(np.pi / 2 * np.clip((t - (b - 0.12)) / 0.2, 0, 1)) ** 2 * ((t >= a) & (t < b + 0.2))
        e = e * (0.78 + 0.22 * np.sin(np.pi * np.clip((t - a) / d, 0, 1)))
        env = np.maximum(env, e)
    vr = vib * np.clip((t - 0.25) / 0.6, 0, 1) * (1 + 0.5 * smooth_random(dur, rng, 0.8))
    fv = fcurve * (1 + vr * np.sin(2 * np.pi * 5.0 * t + rng.uniform(0, 6.28)) + 0.0015 * (2 * smooth_random(dur, rng, 6) - 1))
    ph = phase_from_freq(fv, n)
    y = np.sin(ph) + 0.3 * bright * np.sin(2 * ph + 0.5) + 0.09 * bright * np.sin(3 * ph + 1.0) + 0.035 * bright * np.sin(4 * ph)
    y = rn(y)
    onset = np.zeros(n)
    for a, b, f in notes:
        onset += np.exp(-0.5 * ((t - a - 0.02) / 0.05) ** 2)
    nz = rn(tvf(white(dur, rng), "bp", 2.2 * fv, 1.0)) * (0.55 + 0.45 * smooth_random(dur, rng, 9))
    y = (y + breath * nz * (1.0 + 2.5 * np.clip(onset, 0, 1))) * env
    return y


@recipe("discover", variants=1, peak_db=-3.0, fade_out=1.1, max_len=7.0, tail_db=-60.0, loud=-16.0, limit_db=0.0, onset_db=-50.0,
        stereo=True, subtitle="Island discovered", reach=None)
def discover(v, rng):
    """a new island found: out over the water, the surf a long way off, a harp rolled up an A major arpeggio (A, E, A, C sharp,
    E, A, C sharp, left to right across the sound), then a low wooden flute rising from E to A and B and settling on A, the harp
    answering underneath it with an F sharp minor and a last open chord. About seven seconds, stereo (a sting, played at the
    player)"""
    dur = 8.2
    n = ns(dur)
    harp = np.zeros((2, n))

    def put(f, at, az, g, hard=1.0, t60=None):
        y = harp_pluck(rng, f, dur - at - 0.05, t60, hard)
        st = pan(y, az)
        i = ns(at)
        harp[:, i:i + st.shape[1]] += g * st[:, : n - i]

    put(110.0, 0.12, -0.3, 0.55, 0.8, 7.0)
    arp = [(220.0, 0.20, -0.6, 0.62, 0.8), (329.63, 0.44, -0.45, 0.6, 0.85), (440.0, 0.68, -0.3, 0.6, 0.9), (554.37, 0.92, -0.12, 0.58, 0.95),
           (659.26, 1.16, 0.05, 0.56, 0.95), (880.0, 1.40, 0.22, 0.52, 1.0), (1108.7, 1.64, 0.4, 0.46, 1.0), (1318.5, 1.88, 0.55, 0.4, 1.0)]
    for f, at, az, g, hard in arp:
        put(f * rng.uniform(0.9995, 1.0005), at + rng.uniform(-0.008, 0.008), az, g, hard)
    # F sharp minor under the flute's climb: a slow roll
    for k, (f, g) in enumerate([(185.0, 0.5), (277.18, 0.42), (369.99, 0.4), (440.0, 0.36), (554.37, 0.3)]):
        put(f, 3.05 + 0.09 * k, -0.25 + 0.15 * k, g, 0.8)
    # a last open chord, quiet, an A with its ninth
    put(110.0, 4.7, -0.2, 0.4, 0.7, 6.0)
    for k, (f, g) in enumerate([(220.0, 0.42), (329.63, 0.38), (493.88, 0.34), (554.37, 0.32), (659.26, 0.26)]):
        put(f, 4.75 + 0.08 * k, 0.3 - 0.15 * k, g, 0.75)
    flute = flute_line(dur, rng, [(1.95, 3.0, 329.63), (3.0, 3.85, 440.0), (3.85, 4.3, 493.88), (4.3, 6.5, 440.0)], glide=0.06, vib=0.0045,
                       breath=0.05, bright=0.9)
    # the sea, a long way off: two long waves coming in, one at either end
    sea = np.zeros((2, n))
    for c in range(2):
        w = wash_noise(dur, rng, 700.0 + 500.0 * smooth_random(dur, rng, 0.4), lo=100.0, hi=4200.0, ruffle=0.45, rate=7.0)
        swell = bell(dur, 0.16, 1.4, 1.2) * 0.8 + bell(dur, 0.7, 1.5, 1.3) * 0.55
        sea[c] = lp(w * swell, 3200.0, 2)
    st = np.stack([harp[0] * 0.9, harp[1] * 0.9])
    st = st / (np.max(np.abs(st)) + 1e-9)
    fl = pan(flute / (np.max(np.abs(flute)) + 1e-9), 0.12)
    sea = sea / (np.max(np.abs(sea)) + 1e-9)
    mix = 1.0 * st + 0.5 * fl + 0.13 * sea
    # an arc: the island coming up out of the haze (the first notes soft), full as the flute climbs, the last chord a little
    # under it and the whole of it easing away with the flute
    mix = mix * db(env_at([(0.0, -8.0), (1.2, -4.5), (2.9, 0.0), (4.5, -1.0), (5.3, -3.0), (6.8, -8.0)], dur, 0.2))
    mix = np.stack([lp(c, 9500.0, 2) for c in mix])
    return stereo_reverb(mix, wet=0.3, rt60=2.8, predelay=0.03, hf_damp=0.55, seeds=(2921, 2922), early_span=0.08)[:, : ns(dur + 1.5)]
# discover>>


# =========================================================================== the order of the sounds
# the order of the listen-through and of island_sounds_entries.json: the surf (the beach, the rocks, the cave), the wind, the
# gulls, the lighthouse, the wreck, the smugglers' cove, the drowned shrine, the fishing haven, the discovery
ORDER = ["surf_lap", "surf_crash", "surf_cave", "wind_bed", "wind_gust", "gull_call", "gull_cry", "gull_alarm", "gull_flap",
         "gull_squabble", "gull_splash", "gull_colony", "lighthouse_turn", "lighthouse_horn", "lighthouse_bell", "wreck_creak",
         "wreck_sail", "wreck_bell", "cove_drip", "cove_signal", "cove_camp", "shrine_drone", "shrine_chime", "shrine_bubbles",
         "shrine_whisper", "haven_boats", "haven_rope", "discover"]
assert sorted(ORDER) == sorted(RECIPES), "every sound is in ORDER, once"
for _name in ORDER:
    RECIPES[_name] = RECIPES.pop(_name)


# =========================================================================== build
def write_checked(path, x, ceiling_db=-1.0, quality=6):
    """encode, decode and measure: if the encoder's overshoot lifts the true peak over the ceiling, turn the sound down by that
    much and encode it again. Returns the samples as they were finally written"""
    for _ in range(3):
        write_ogg(path, x, quality=quality)
        dec = read_audio(path)
        over = max(20 * np.log10(true_peak(c)) for c in dec) - ceiling_db
        if over <= 0:
            break
        x = x * db(-(over + 0.05))
    return x


def seam_report(path, n):
    """what a loop sounds like at its join, read back from the file that was written: the file's length against the loop's,
    the step across the join against the typical step between samples, and the high-frequency energy in the 10 ms round the
    join against that of the loop elsewhere (a click or a dropout would show as a jump). The worst of the channels"""
    worst = (0, 0.0, 0.0)
    for y in read_audio(path):
        step = abs(y[0] - y[-1])
        typ = float(np.median(np.abs(np.diff(y)))) + 1e-12
        h = hp(y, 1500.0, 2)
        w = ns(0.005)
        seam = np.sqrt(np.mean(np.concatenate([h[-w:], h[:w]]) ** 2))
        els = [np.sqrt(np.mean(h[i:i + 2 * w] ** 2)) for i in range(ns(0.1), len(y) - ns(0.1), 2 * w)]
        worst = (len(y) - n, max(worst[1], step / typ), max(worst[2], seam / (np.median(els) + 1e-12)))
    return worst


MARKS = {}  # (name, variant) -> the marks of the file as it was written, for the report


def build(assets, names, wav_dir=None):
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            t0 = time.time()
            if r["loop"]:
                x, marks = render_loop(name, v), {}
            else:
                x, marks = render_effect(name, v)
            out = sound_path(name, v, r["variants"])
            p = os.path.join(assets, "sounds", out + ".ogg")
            x = write_checked(p, x)
            if wav_dir:
                write_wav(os.path.join(wav_dir, out + ".wav"), x)
            extra = ""
            if r["loop"]:
                d_len, step, seam = seam_report(p, x.shape[-1])
                extra = f"  loop {x.shape[-1]} samples (file {d_len:+d}), join {step:4.1f}x a step, {seam:4.2f}x the usual highs"
            if marks:
                MARKS[(name, v)] = marks
                extra += "  marks " + ", ".join(f"{k} {t:.3f}" for k, t in marks.items())
            print(f"{out:26s} {x.shape[-1] / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  rms {to_db(rms(x)):6.1f}  "
                  f"s100 {short_lufs(x if x.ndim == 1 else x.mean(axis=0)):6.1f}  {time.time() - t0:5.2f}s{extra}", flush=True)


# =========================================================================== listen-through
GAP = 0.7  # seconds of quiet between one sound and the next in the file of every variant
LOOP_SHOWN = 12.0  # how long each loop plays in that file (so that its join is heard once)


class Scene:
    """a stretch of the islands as a player would hear it, laid out from the files as they were written: sounds placed in time
    and in space (the game's linear fall-off from the listener to the sound's reach, a pan, the air taking the top off a distant
    one), loops running under them, a stereo file (the wind bed, the discovery) played at the listener as the game plays it"""

    def __init__(self, assets, dur, seed):
        self.assets, self.n, self.rng = assets, ns(dur), np.random.default_rng(seed)
        self.buf = np.zeros((2, self.n))
        self.cache = {}
        self.last = {}

    def sound(self, name, v):
        if (name, v) not in self.cache:
            stem = sound_path(name, v, RECIPES[name]["variants"])
            self.cache[(name, v)] = read_audio(os.path.join(self.assets, "sounds", stem + ".ogg"))
        return self.cache[(name, v)]

    def _spatial(self, name, x, dist, az, gain):
        reach = RECIPES[name]["reach"] or 16.0
        if x.shape[0] == 2:
            return x * gain
        d = dist if dist is not None else self.rng.uniform(0.2, 0.75) * reach
        g = max(0.02, 1.0 - d / reach)
        y = x[0] if d < 14.0 else lp(x[0], max(2800.0, 16000.0 * np.exp(-(d - 14.0) / 70.0)), 2)
        return pan(y, az if az is not None else self.rng.uniform(-0.9, 0.9)) * (g * gain)

    def put(self, name, v, at, dist=None, az=None, gain=1.0):
        st = self._spatial(name, self.sound(name, v), dist, az, gain)
        i = ns(at)
        m = min(st.shape[1], self.n - i)
        if m > 0:
            self.buf[:, i:i + m] += st[:, :m]

    def loop(self, name, gain=1.0, t0=0.0, t1=None, dist=None, az=0.0, fade_s=1.0):
        t1 = self.n / SR if t1 is None else t1
        x = self.sound(name, 0)
        reps = int(np.ceil((t1 - t0) * SR / x.shape[1])) + 1
        x = np.tile(x, (1, reps))[:, :ns(t1 - t0)]
        k = ns(fade_s)
        env = np.ones(x.shape[1])
        env[:k] = np.sin(np.linspace(0, np.pi / 2, k)) ** 2
        env[-k:] *= np.cos(np.linspace(0, np.pi / 2, k)) ** 2
        st = self._spatial(name, x * env, dist, az, gain)
        i = ns(t0)
        m = min(st.shape[1], self.n - i)
        self.buf[:, i:i + m] += st[:, :m]

    def shots(self, name, every, t0=0.0, t1=None, jitter=0.35, dist=None, variants=None, gain=1.0, count=None):
        """the sound every `every` seconds or so (a little unevenly) from t0, a different variant each time (not the one before)"""
        t1 = self.n / SR if t1 is None else t1
        nv = RECIPES[name]["variants"]
        at, k = t0, 0
        while at < t1 - 0.5 and (count is None or k < count):
            if variants:
                v = variants[k % len(variants)]
            else:
                v = int(self.rng.integers(nv))
                if nv > 1 and v == self.last.get(name):
                    v = (v + 1) % nv
            self.last[name] = v
            d = self.rng.uniform(*dist) if dist else None
            self.put(name, v, at, d, None, gain)
            at += every * (1.0 + jitter * self.rng.uniform(-1, 1))
            k += 1

    def out(self):
        return self.buf


def scenes(assets):
    """the places of the islands, each a scene with its sounds at about the pace they come in the game: (title, scene)"""
    out = []
    sc = Scene(assets, 40.0, 11)
    sc.loop("wind_bed", 0.5)
    sc.shots("surf_lap", 5.5, 0.5, dist=(5, 16))
    sc.put("gull_cry", 0, 13.0, 24.0)
    sc.put("gull_cry", 3, 29.0, 30.0)
    sc.put("wind_gust", 2, 21.0, 10.0)
    out.append(("Beach: the surf running up the sand, wind, a gull", sc))
    sc = Scene(assets, 40.0, 12)
    sc.loop("wind_bed", 0.4)
    sc.shots("surf_crash", 7.0, 1.0, dist=(8, 24), count=5)
    for at in (9.0, 22.0, 33.0):
        sc.put("surf_cave", int(sc.rng.integers(4)), at, float(sc.rng.uniform(6, 16)))
    out.append(("Rocks and cave: waves on the rock, the cave booming", sc))
    sc = Scene(assets, 48.0, 13)
    sc.loop("wind_bed", 0.8)
    sc.shots("wind_gust", 6.5, 5.0, dist=(4, 20))
    out.append(("Wind: the breeze, and gusts over it", sc))
    sc = Scene(assets, 46.0, 14)
    sc.loop("gull_colony", 0.8, dist=18.0)
    sc.loop("wind_bed", 0.3)
    sc.put("gull_call", 0, 3.0, 22.0)
    sc.put("gull_call", 3, 21.0, 30.0)
    sc.put("gull_call", 1, 38.0, 20.0)
    sc.shots("gull_cry", 5.0, 1.5, dist=(10, 36))
    sc.put("gull_alarm", 0, 12.0, 18.0)
    sc.put("gull_alarm", 3, 31.0, 22.0)
    sc.put("gull_squabble", 0, 9.0, 12.0)
    sc.put("gull_squabble", 1, 27.0, 14.0)
    for at, v in ((6.0, 0), (24.5, 2), (40.0, 1)):
        sc.put("gull_flap", v, at, 8.0)
    for at, v in ((17.0, 0), (35.0, 1), (43.0, 2)):
        sc.put("gull_splash", v, at, 10.0)
    out.append(("Gulls: the colony, calls, cries, alarms, squabbles, wings", sc))
    sc = Scene(assets, 48.0, 15)
    sc.loop("lighthouse_turn", 0.9, dist=6.0)
    sc.loop("wind_bed", 0.4)
    sc.shots("surf_lap", 8.0, 1.0, dist=(14, 22), gain=0.7)
    sc.put("lighthouse_horn", 0, 6.0, 40.0)
    sc.put("lighthouse_horn", 1, 28.0, 46.0)
    for at, v in ((17.0, 0), (35.0, 1), (43.0, 2)):
        sc.put("lighthouse_bell", v, at, 20.0)
    out.append(("Lighthouse: the clockwork, the fog horn, the fog bell", sc))
    sc = Scene(assets, 48.0, 16)
    sc.loop("wind_bed", 0.3)
    sc.shots("surf_lap", 7.0, 1.0, dist=(10, 20), gain=0.7)
    sc.shots("wreck_creak", 5.5, 2.0, dist=(6, 18), variants=[0, 1, 2, 3, 4, 5])
    sc.shots("wreck_sail", 15.0, 4.0, dist=(8, 14), variants=[0, 1, 2])
    sc.put("wreck_bell", 0, 20.0, 22.0)
    sc.put("wreck_bell", 1, 38.0, 24.0)
    out.append(("Wreck: groaning timbers, the torn sail, the drowned bell", sc))
    sc = Scene(assets, 48.0, 17)
    sc.loop("wind_bed", 0.25)
    sc.shots("surf_lap", 8.0, 1.0, dist=(16, 22), gain=0.6)
    sc.shots("cove_drip", 3.6, 1.0, dist=(4, 10), variants=[0, 1, 2, 3, 4, 5])
    for at, v in ((8.0, 0), (22.0, 1), (36.0, 2)):
        sc.put("cove_signal", v, at, 18.0)
    for at, v in ((5.0, 0), (17.0, 1), (29.0, 2), (41.0, 3)):
        sc.put("cove_camp", v, at, 6.0)
    out.append(("Cove: dripping water, whistled signals, the camp stirring", sc))
    sc = Scene(assets, 52.0, 18)
    sc.loop("shrine_drone", 0.9, dist=8.0)
    sc.shots("shrine_chime", 9.0, 2.0, dist=(5, 14), variants=[0, 1, 2, 3])
    sc.shots("shrine_bubbles", 14.0, 6.0, dist=(4, 12), variants=[0, 1, 2])
    sc.shots("shrine_whisper", 15.0, 10.0, dist=(4, 10), variants=[0, 1, 2])
    out.append(("Shrine: the drone, chimes, bubbles, whispers", sc))
    sc = Scene(assets, 40.0, 19)
    sc.loop("wind_bed", 0.35)
    sc.shots("surf_lap", 8.0, 1.0, dist=(12, 22), gain=0.6)
    sc.shots("haven_boats", 7.0, 2.0, dist=(5, 14), variants=[0, 1, 2, 3])
    sc.shots("haven_rope", 11.0, 6.0, dist=(4, 9), variants=[0, 1, 2])
    sc.put("gull_cry", 4, 12.0, 26.0)
    sc.put("gull_cry", 1, 31.0, 30.0)
    out.append(("Haven: boats knocking and creaking, rope", sc))
    sc = Scene(assets, 12.0, 20)
    sc.loop("wind_bed", 0.3)
    sc.put("surf_lap", 0, 0.5, 14.0)
    sc.put("discover", 0, 1.2)
    out.append(("Discovery: the sting", sc))
    return out


def write_mp3(audio, marks, title, path):
    """audio (2, n) to a stereo MP3 with chapter marks (start s, end s, title)"""
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio.astype(np.float32))
        with open(meta, "w") as f:
            f.write(f";FFMETADATA1\ntitle={title}\nartist=Guildmark\n")
            for a, b, t in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\ntitle={t}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1", "-map_chapters", "1",
                        "-c:a", "libmp3lame", "-q:a", "2", "-ac", "2", "-id3v2_version", "3", "-fflags", "+bitexact", "-flags:a",
                        "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        shutil.copyfile(mp3, path)  # (encoded aside, then copied: ffmpeg seeks back in a file to finish its header)
    print(f"{path}  {audio.shape[1] / SR:.1f}s, {len(marks)} chapters, {os.path.getsize(path) / 1e6:.2f} MB")


def listen_through(assets, path):
    """one MP3 to listen to on a phone: the islands place by place (beach, rocks and cave, wind, gulls, lighthouse, wreck, cove,
    shrine, haven, discovery), each its sounds laid out at about the pace and the loudness a player would hear them (the files as
    they were written, a chapter mark at each place)"""
    pieces, marks, at = [], [], 0.0
    for title, sc in scenes(assets):
        x = sc.out()
        pieces += [x, np.zeros((2, ns(1.0)))]
        marks.append((at, at + x.shape[1] / SR + 1.0, title))
        at += x.shape[1] / SR + 1.0
    audio = np.concatenate(pieces, axis=1)
    audio = audio * (db(-1.5) / (np.max(np.abs(audio)) + 1e-12))  # (one gain for all: the places keep their levels to each other)
    write_mp3(audio, marks, "Guildmark islands: the sounds, place by place", path)


def variants_through(assets, names, path):
    """one MP3 of every file that was written, in order, a gap between, a chapter mark (the file's name) at each; a loop plays
    for twelve seconds, round its join"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        r = RECIPES[name]
        for v in range(r["variants"]):
            stem = sound_path(name, v, r["variants"])
            p = os.path.join(assets, "sounds", stem + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {stem})")
                continue
            x = read_audio(p)
            x = np.vstack([x, x]) if x.shape[0] == 1 else x
            if r["loop"]:
                x = np.tile(x, (1, int(np.ceil(LOOP_SHOWN * SR / x.shape[1]))))[:, :ns(LOOP_SHOWN)]
                x = np.stack([fade(c, 0.0, 0.3) for c in x])
            marks.append((at, at + x.shape[1] / SR + GAP, stem[len(ISLAND_DIR) + 1:]))
            pieces += [x, np.zeros((2, ns(GAP)))]
            at += x.shape[1] / SR + GAP
    write_mp3(np.concatenate(pieces, axis=1), marks, "Guildmark island sounds, every file", path)


# =========================================================================== fragments
def fragments(directory):
    """island_sounds_entries.json (the sounds.json entries of every event, keyed as the game names them, with music.isles),
    island_subtitles.json (their subtitles) and island_marks.json (the moments in the files that the game can sync to), for the
    lead to merge: the real sounds.json and en_us.json are not touched"""
    sounds, lang, marks = {}, {}, {}
    for name, r in RECIPES.items():
        entries = []
        for v in range(r["variants"]):
            e = {"name": "guildmark:" + sound_path(name, v, r["variants"])}
            if r["loop"]:
                e["stream"] = False
            if r["reach"] and r["reach"] > 16:
                e["attenuation_distance"] = r["reach"]
            entries.append(e)
        key = event_key(name)
        sounds[key] = {"sounds": entries}
        if r["subtitle"]:
            sounds[key]["subtitle"] = f"subtitles.guildmark.{key}"
            lang[f"subtitles.guildmark.{key}"] = r["subtitle"]
        for v in range(r["variants"]):
            if (name, v) in MARKS:
                marks[sound_path(name, v, r["variants"])] = {k: round(t, 3) for k, t in MARKS[(name, v)].items()}
    sounds["music.isles"] = {"sounds": [{"name": "guildmark:music/isles", "stream": True}]}
    os.makedirs(directory, exist_ok=True)
    for fname, data in (("island_sounds_entries.json", sounds), ("island_subtitles.json", lang), ("island_marks.json", marks)):
        p = os.path.join(directory, fname)
        with open(p, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(p, len(data), "entries")


# =========================================================================== command line
def select(only):
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        for pre in ("island.", ISLAND_DIR + "/"):
            if s.startswith(pre):
                s = s[len(pre):]
        s = s.replace(".", "_")
        hits = [k for k in RECIPES if k == s or k.startswith(s + "_") or (s.endswith("_") and k.startswith(s))]
        if not hits:
            raise SystemExit(f"unknown: {s} (sounds: {', '.join(RECIPES)})")
        for h in hits:
            if h not in names:
                names.append(h)
    return names


def main():
    ap = argparse.ArgumentParser(description="Guildmark's islands: the sounds of the surf, the wind, the gulls and the places")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated sound names or groups, e.g. surf_lap,gull,wind_bed")
    ap.add_argument("--mp3", default=None, help="also write the listen-through MP3, place by place (needs every sound written)")
    ap.add_argument("--variants-mp3", default=None, help="also write an MP3 of every file that was rendered, in order")
    ap.add_argument("--fragments", default=None, help="also write island_sounds_entries.json, island_subtitles.json and island_marks.json here")
    ap.add_argument("--no-render", action="store_true", help="do not render: only the MP3s and fragments, from the files already written (no marks)")
    a = ap.parse_args()
    names = select(a.only) if a.only else list(RECIPES)
    if not a.no_render:
        build(a.assets, names, a.wav)
    if a.mp3:
        listen_through(a.assets, a.mp3)
    if a.variants_mp3:
        variants_through(a.assets, names, a.variants_mp3)
    if a.fragments:
        fragments(a.fragments)


if __name__ == "__main__":
    main()
