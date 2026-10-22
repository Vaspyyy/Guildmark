"""The trader's sounds: every sound of the Living World's trade, made from nothing (trader design section 10, Sounds, and
12.15, stream X5).

37 events in `sounds/trade/*.ogg` (mono 44.1 kHz Ogg Vorbis), the design's 37 names in the order of `trade/TradeSounds.NAMES`
(the code plays them by those names), `trade.bell_step` and `trade.hunt_shout` in three variants as the design says, and
more variants where a sound is heard over and over (a tally stick knocked once for every ten units, a step, a clink, a bead).
The game names them `guildmark:trade.<name>`; a file is `trade/<name><n>.ogg`. No sample is used: every sound is noise and
resonators, laid out with `sfxkit.py`, the townsfolk's helpers (`make_townsfolk_sfx.py`: the levelling, the knocks and rings,
the paper), the cog's (`make_ship_audio.py`: stick-slip creaking, squeals, a bronze bell's modes, a loop built in a circle),
the crew's (`make_homestead_audio.py`: a crowd's hurrah) and the works' (`make_works_audio.py`: an animal horn), each seeded
by its event and file, so that a rerun is bit-identical. `trade.hoist_creak` is the granary's own `works.hoist` (the design:
"the granary's own"): its entry in `sounds.json` points at that event (`"type": "event"`), there is no copy of it here.

    the board and the Tally   board_stamp  seal_press  ticket_peel  hourglass  tally_open  tally_page  tally_notch  report_file
    a haul delivered          knock  deliver
    carrying                  bell_step  bell_trot  harness_buckle  whistle_come  whistle_stay  pack_clink  cart_creak (loop)
                              shafts_lift  shafts_drop  canvas_flap  tailboard  rein_slack  wagon_brake
    the Hunt                  hunt_horn  hunt_shout (a bark, a laugh, a snarl)  hunt_win  sack_lift  guard_step
    the ladder                warrant_seal  store_key  scale_settle  hoist_creak (works.hoist)  strongbox  beads  house_bell
                              carter_hail
    the town answers          cheer

Each recipe says in its docstring what it is made of, and what each of its variants does that the others do not; the moment
each belongs to is in WHEN (the design's Parts 1 to 6 and the call sites on the code that exists), which `--mp3` writes out
beside the listen-through.

Levelling, as the works' and the townsfolk's are: a one-shot's loudest 100 ms goes to a K-weighted loudness set per sound
(-13 to -16 for what carries across a street or a valley, -17 to -20 for what is done at a board, a counter or a cart (the
keeper's five, which the code plays at 0.7, are 3 dB up on that), -21 to -26 for the small close things: a page, a notch, a sand
glass), its true peak held under -1 dBFS and re-encoded lower if the
encoder overshoots. Where the code that exists plays a sound below full volume the file is levelled for that (the bell on a
pack animal is played at 0.55 and 0.7, the clink of a laden back at 0.16 to 0.42, and the keeper's stamp, seal, tally-stick knocks,
crate and peeling ticket, the stalls' cues, at 0.7: the files are louder by about that much, so that what is heard is the works'
level). The loop (`cart_creak`) is built in a circle (`make_ship_audio.Ring`: periodic noise,
creaks laid at the same moment of every lap, filters and rooms run three laps, the middle one kept), so that the sample
after the last is the first with no seam, then set to -25 dBFS RMS with its peaks (true peaks too) held under -9, a margin for
the encoder, and turned to start at a quiet zero crossing. After the encoder it is read back: its length, the step across the
join against the usual step between samples, the highs round the join against the highs elsewhere. It is built to the cart's
own wheel: the Chapman's Cart has the handcart's wheels (radius 10/16 of a block, `Handcart.AXLE_UP`), a pulled cart walks
3.67 blocks a second (the walk of 4.317, slowed by 15%), so a wheel turns once in 1.07 s, and the loop is eight turns (8.56 s)
at pitch 1.0: play it at pitch = speed / 3.67 and each wheel's creak comes once a turn at every speed.

usage (from the repo root; the full render takes a few minutes, `nice -n 10` for it):
    python3 -B art/audio/make_trade_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...] [--mp3 FILE] [--audit]

  (no --only)  renders every file and writes `trade_sounds.json` (their sounds.json entries: the loop with `"stream": false`,
               every sound's reach as `attenuation_distance` when it is not the game's 16, `trade.hoist_creak` as an event) and
               `trade_lang.json` (the 37 subtitles `subtitles.guildmark.trade.<name>`) beside this script; run
               `python3 -B art/lang/merge_lang.py` after it, which adds them to sounds.json and en_us.json
  --only       events: `knock,bell_step` (`trade.knock` also works); no fragments are written
  --wav DIR    also writes WAV copies for listening
  --mp3 FILE   joins what was rendered into one listen-through MP3 (a chapter mark at each file; the loop plays for twelve
               seconds so that its join is heard once; each sound at the volume the code plays it at), and writes
               `trade_what_plays_when.txt` beside it: each sound's files, length, reach, subtitle, what it is made of, the
               moment it belongs to and where it is in the MP3
  --audit      renders nothing: reads every file back (mono 44.1 kHz Vorbis; peak and true peak; the loudest 100 ms against
               the target; the loop's length, RMS and join) and checks the 37 events against TradeSounds.NAMES and its ranges,
               and the fragments against the files; exits 1 on a problem
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

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from sfxkit import *  # noqa: E402,F401,F403
import make_townsfolk_sfx as tf  # noqa: E402
import make_ship_audio as ship  # noqa: E402
import make_homestead_audio as hs  # noqa: E402
import make_works_audio as works  # noqa: E402
from make_townsfolk_sfx import level, write_checked, true_peak, loudness_curve, nrm, lead_in  # noqa: E402
from make_ship_audio import Ring, Bus, loop_start, seam_report, rn  # noqa: E402

SOUND_DIR = "trade"
LOOP_MARGIN_DB = 0.8  # a loop's peaks are held this far under its ceiling, for the encoder's own overshoot
FADE_IN = 0.0006  # a one-shot comes in over the first 0.6 ms (a hard contact still is: no step from silence to a blow's first sample)
EVENTS = {}  # event (without the "trade." prefix) -> recipe, in the order of TradeSounds.NAMES


# =========================================================================== registry and finishing
def effect(event, files, subtitle, reach=16, loud=-18.0, max_len=2.0, fade_out=0.08, tail_db=-66.0, limit=3.0,
           peak_db=-3.0, vol=1.0, soft=0.0, when=""):
    """registers a one-shot: `files` variants, each at most `max_len` seconds (a number, or one for each file), with the
    subtitle the game writes. loud is the target for its loudest 100 ms (K-weighted LUFS), limit the most dB of peak
    limiting allowed to get there, reach how many blocks it carries (its attenuation_distance, and the range its event is
    registered with in TradeSounds.kt, when it is not the game's 16), vol the volume the code that exists plays it at (the
    file is levelled for that; the listen-through plays it so), soft the dB of crest a sharp blow is softly clipped down by
    before it is levelled (so that its body, not a limiter's ducking, carries it to the target), when the moment it belongs to"""
    def deco(fn):
        EVENTS[event] = dict(kind="effect", fn=fn, files=files, length=max_len, subtitle=subtitle, reach=reach, loud=loud,
                             fade_out=fade_out, tail_db=tail_db, limit=limit, peak_db=peak_db, vol=vol, soft=soft, when=when)
        return fn
    return deco


def loop(event, period, reach, subtitle, rms_db=-25.0, ceiling_db=-9.0, vol=1.0, when=""):
    """registers a seamless loop of `period` seconds: the recipe is handed a Ring and gives back the three periods it laid
    out; the file is the middle one, levelled by RMS with its peaks held under ceiling_db"""
    def deco(fn):
        EVENTS[event] = dict(kind="loop", fn=fn, files=1, length=period, subtitle=subtitle, reach=reach, rms_db=rms_db,
                             ceiling_db=ceiling_db, vol=vol, when=when)
        return fn
    return deco


def alias(event, of, subtitle, reach=None, when=""):
    """registers an event that is another event: its sounds.json entry points at `of` (`"type": "event"`), it has no file; reach
    is how far that event carries, for the range its own event is registered with in TradeSounds.kt (it has no attenuation_distance
    of its own: the other event's entry has it)"""
    EVENTS[event] = dict(kind="alias", fn=None, of=of, files=0, length=0.0, subtitle=subtitle, reach=reach, vol=1.0, when=when)


def sound_path(event, n):
    """'knock', 1 -> 'trade/knock1'"""
    return f"{SOUND_DIR}/{event}{n}"


def length_of(r, v):
    ml = r["length"]
    return ml[v] if isinstance(ml, (list, tuple)) else ml


def soft_clip(x, amount_db):
    """x with its biggest peaks pressed in by a smooth curve (tanh), amount_db of crest: the peak stays where it is and the
    rest comes up by about that much, with none of a limiter's ducking of what follows a blow"""
    g = db(amount_db)
    p = float(np.max(np.abs(x))) + 1e-12
    return np.tanh(g * x / p) / np.tanh(g) * p


def build_effect(event, r, v):
    """one variant of a one-shot, finished: trimmed, faded, cut at its length, its loudest 100 ms at the target"""
    rng = rng_for("trade", event, v)
    x = r["fn"](v, rng)
    x = finalize(lead_in(x), peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=length_of(r, v), tail_db=r["tail_db"])
    x = fade(x, FADE_IN)
    if r["soft"]:
        x = soft_clip(x, r["soft"])
    return level(x, r["loud"], max_limit_db=r["limit"])


def render_loop(event, r):
    """a loop, finished: the middle period of the three the recipe laid out, high-passed, levelled to its RMS with its peaks
    held under the ceiling, and turned to start at a quiet zero crossing (the cog's render_loop)"""
    rg = Ring(r["length"])
    x = hp(r["fn"](rg, rng_for("trade", event, 0)), 25.0)
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


# =========================================================================== the rooms
def hall(x, wet=0.08, seed=8100):
    """the market hall's counter and board: timber over stone, a small hard room with a quick, fairly bright tail"""
    return tf.small_room(x, wet=wet, rt60=0.42, seed=seed)


def square(x, wet=0.08, seed=8200):
    """the square and the street: a slap off the house walls and very little tail"""
    return tf.street(x, wet=wet, seed=seed)


def road(x, wet=0.07, seed=8300):
    """the open road: a slap back off the verges and a short tail (the caravans' field)"""
    return reverb(x, wet=wet, rt60=0.9, predelay=0.03, hf_damp=0.65, seed=seed, early_span=0.06)


def valley(x, wet=0.3, seed=8400):
    """a valley: a long soft tail off the slopes, and the far hillside giving the call back, twice, each time duller and
    fainter (the echoes are taps of the sound itself, delayed, the second one a little later than the first)"""
    y = reverb(x, wet=wet, rt60=2.4, predelay=0.07, hf_damp=0.8, seed=seed, early_span=0.12)
    out = np.concatenate([y, np.zeros(ns(1.7))])
    for delay, g, cut in ((0.62, 0.30, 1800.0), (1.37, 0.15, 1100.0)):
        e = lp(lp(np.concatenate([np.zeros(ns(delay)), hp(x, 60.0)]), cut, 2), cut * 1.4, 2)
        out[:len(e)] += g * e[:len(out)]
    return out


# =========================================================================== small parts of my own
def ping(dur, freqs, t60s, amps, rng, ex_len=0.0004, hi=12000.0):
    """a small hard ring (a latch, a link, a lid, a clay pot): the knock bank with the skirt taken off under its lowest mode,
    so that it is a tick and a ring and no thump"""
    y = tf.knock(dur, freqs, t60s, amps, rng, lo=max(500.0, 0.6 * min(freqs)), hi=hi, ex_len=ex_len)
    return hp(y, 0.55 * min(freqs), 2)


def tick(rng, lo=1500.0, hi=12000.0, tau=0.0006, dur=0.006):
    """the click of a hard contact"""
    return works.tick(rng, lo, hi, tau, dur)


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


def wrap(rg, at):
    """a moment folded into the period"""
    return float(at) % rg.P


def jitter(rng, x, amount):
    """x moved by a random fraction of itself, +- amount"""
    return x * (1.0 + amount * (2.0 * rng.random() - 1.0))


# =========================================================================== the board and the Tally
@effect("board_stamp", 3, "Stamp thumps on paper", reach=16, loud=-14.0, max_len=0.5, fade_out=0.1, limit=4.5, vol=0.7, soft=4.0,
        when=("Parts 1 and 2 (A, `Stalls.booked`): a haul ticket booked: the keeper hands over its slip and, six ticks later, the "
              "stamp comes down, played at the keeper (chest height) at volume 0.7 and pitch 0.95 to 1.05; the file is levelled for "
              "that. Part 1 (C, the Haul Board): the hall's merchant stamps each ticket she hangs on the rail."))
def board_stamp(v, rng):
    """a clerk's boxwood stamp brought down on a paper ticket pinned to the board: the stamp's body meeting the paper (a short,
    dull 'tok', damped by the paper and the pad of ink), the oak board and its frame answering underneath (a hollow thump), the
    paper crushed flat, and the stamp lifted again with a faint stick of ink. v1 an ordinary stamp, v2 a lighter and higher one,
    v3 a hard one, brought down with the weight of the arm"""
    dur = 0.5
    firm = [1.0, 0.8, 1.2][v]
    f = [440.0, 490.0, 400.0][v] * rng.uniform(0.985, 1.015)
    m = Mix(dur)
    body = tf.knock(0.14, f * np.array([1.0, 1.85, 3.0, 4.4]), [0.02, 0.015, 0.01, 0.007], [1, .7, .45, .25], rng, lo=250,
                    hi=3500, ex_len=0.0025)
    m.add(nrm(body), 0.0, 0.85 * firm)
    m.add(works.burst(0.035, rng, 600, 4200, 0.008), 0.0, 0.45 * firm)
    m.add(ship.oak(0.3, rng, 170.0 * rng.uniform(0.95, 1.05), t60=0.11, hard=0.5, lo=120.0), 0.0, 0.8 * firm)
    m.add(tf.thud(0.2, rng, f=105, decay=0.04, tone=0.5), 0.0, 0.7 * firm)
    lift = 0.085 + 0.02 * v
    m.add(nrm(crackle(0.04, rng, 1800, decay=0.0005, f_lo=1500, f_hi=6000) * bell(0.04, 0.3, 1.0, 1.6)), lift, 0.2)
    m.add(works.burst(0.01, rng, 1000, 5000, 0.003), lift, 0.15)
    return hall(lp(fit(m.out(), ns(dur)), 6500), 0.07, 8101 + v)


@effect("seal_press", 2, "Wax seal pressed", reach=12, loud=-16.0, max_len=0.7, fade_out=0.15, limit=4.0, vol=0.7,
        when=("Parts 1 and 2 (A, `Stalls.booked`): fourteen ticks after the keeper hands over a booked ticket's slip (eight after "
              "the stamp) the seal in the player's colour goes on it, the wax squashing and settling; played at the keeper at "
              "volume 0.7 and pitch 0.95 to 1.05, the file levelled for that. Also the seal on a ticket the hall's merchant hangs "
              "for the town (C)."))
def seal_press(v, rng):
    """a brass seal pressed into a blob of warm wax on a ticket: the seal meeting the wax (a tick of brass in its wooden handle,
    damped at once by the wax under it), the wax squashing out sideways with a low, wet push as the disc spreads, a short settle,
    and the seal lifted off with a small sticky pop. v1 pressed and lifted, v2 pressed harder and held a moment before it lifts"""
    dur = 0.75
    hold = [0.0, 0.07][v]
    m = Mix(dur)
    m.add(nrm(ping(0.05, [2900.0, 4400.0, 6300.0], [0.008, 0.006, 0.004], [1, .6, .35], rng)), 0.0, 0.22)
    m.add(nrm(tf.knock(0.1, [330.0, 640.0, 1050.0], [0.025, 0.02, 0.012], [1, .7, .4], rng, lo=150, hi=3000, ex_len=0.003)),
          0.0, 0.5)
    sd = 0.16 + hold
    t = tvec(sd)
    push = tvf(colored(sd, rng, -2.0), "bp", 900 * np.exp(-t / 0.06) + 220, 1.6) * np.clip(t / 0.006, 0, 1) * np.exp(-t / (0.09 + hold))
    m.add(nrm(push), 0.004, 0.55)
    m.add(tf.thud(0.12, rng, f=170, decay=0.02, tone=0.3), 0.0, 0.5)
    lift = 0.3 + hold
    pop_t = tvec(0.012)
    m.add(nrm(sine_sweep(0.012, 500, 1500, 2.0) * np.exp(-pop_t / 0.004)), lift, 0.3)
    m.add(nrm(crackle(0.05, rng, 1600, decay=0.0006, f_lo=1200, f_hi=5500) * bell(0.05, 0.6, 1.2, 1.2)), lift - 0.03, 0.18)
    return hall(lp(fit(m.out(), ns(dur)), 7000), 0.06, 8111 + v)


@effect("ticket_peel", 2, "Ticket peels off", reach=12, loud=-18.0, max_len=1.5, fade_out=0.2, limit=4.0, vol=0.7,
        when=("Part 2 (A, `Stalls.hauled`): a haul ticket that the delivery fills peels off the board as the Marks are counted out "
              "(ninety ticks after the delivery starts), played at the keeper at volume 0.7 and pitch 0.95 to 1.05, the file "
              "levelled for that. Also (C) a ticket withdrawn from the board: it peels off its hook, curls and drops to the ground."))
def ticket_peel(v, rng):
    """a paper ticket pulling free of its wax seal and hook and falling: the fibres of the paper tearing off the wax (a dry,
    quickening crackle), the iron hook swinging free, the sheet curling in on itself (a papery rustle), fluttering down and
    landing with a faint pat. v1 comes away in one pull, v2 sticks halfway and then lets go"""
    dur = 1.5
    pd = [0.32, 0.46][v]
    m = Mix(dur)
    pts = [[(0.0, 0.2), (pd * 0.5, 0.6), (pd, 1.0)], [(0.0, 0.2), (pd * 0.45, 0.7), (pd * 0.6, 0.15), (pd, 1.0)]][v]
    s = env_points(pts, pd)
    tear = crackle(pd, rng, 250 + 1400 * s, decay=0.0006, f_lo=1500, f_hi=7500, size_var=0.9) * (0.4 + 0.6 * s)
    m.add(nrm(tear), 0.0, 0.5)
    m.add(nrm(ping(0.16, [1900.0, 3100.0, 4700.0], [0.05, 0.03, 0.02], [1, .5, .3], rng)), pd - 0.04, 0.2)
    cd = 0.4
    rate = env_points([(0.0, 0.0), (0.12, 0.8), (0.28, 0.5), (cd, 0.0)], cd)
    m.add(tf.parchment(cd, rng, rate, crisp=0.6, turns=3.0), pd - 0.05, 0.6)
    m.add(tf.paper_flap(0.14, rng, size=0.9), pd + 0.18, 0.28)
    land = pd + cd + 0.12
    m.add(works.burst(0.03, rng, 300, 3000, 0.006), land, 0.3)
    m.add(tf.thud(0.08, rng, f=210, decay=0.01, tone=0.1), land, 0.25)
    return square(lp(fit(m.out(), ns(dur)), 9000), 0.06, 8121 + v)


@effect("hourglass", 2, "Sand runs in a glass", reach=8, loud=-27.0, max_len=1.1, fade_out=0.25, limit=4.0,
        when=("Parts 1 and 6 (C): a row's hourglass on the Haul Board tipping and beginning to run when the merchant chalks the "
              "row up with fresher news (the sand has run down as far as the news is old)."))
def hourglass(v, rng):
    """the little glass at the head of a row tipped on its pivot and set running: the wooden frame turning on its pin (a faint
    knock), the glass settling on its stop with a thin ring, and the sand beginning to run through the neck, a fine hiss of
    falling grains that tick where they land on the heap and thin out as the top bulb empties. v1 and v2 differ in how fine
    the sand is"""
    dur = 1.0
    m = Mix(dur)
    m.add(nrm(tf.knock(0.08, [430.0, 880.0, 1500.0], [0.02, 0.014, 0.01], [1, .6, .35], rng, lo=200, hi=4000, ex_len=0.002)),
          0.0, 0.5)
    g0 = [3.0e3, 4.2e3][v]
    glass = ping(0.25, [3300.0 * rng.uniform(0.97, 1.03), 5200.0, 7400.0], [0.1, 0.06, 0.035], [1, .5, .25], rng, ex_len=0.0006)
    m.add(nrm(glass), 0.07, 0.22)
    sd = 0.85
    t = tvec(sd)
    rate = g0 * np.clip(t / 0.05, 0, 1) * (1 - 0.55 * t / sd)
    grains = ship.grain_cloud(sd, rng, rate, decay=0.0004, f_lo=2500, f_hi=9500, size_var=0.6)
    hiss = bp(white(sd, rng), 3000, 9000) * np.clip(t / 0.05, 0, 1) * (1 - 0.6 * t / sd) * (0.6 + 0.4 * smooth_random(sd, rng, 25))
    m.add(nrm(grains), 0.1, 0.7)
    m.add(nrm(hiss), 0.1, 0.35)
    return hall(fit(m.out(), ns(dur)), 0.05, 8131 + v)


@effect("tally_open", 2, "Book opens", reach=8, loud=-21.0, max_len=0.95, fade_out=0.15,
        when="Part 1 (B): right-clicking the Tally, anywhere: the small leather book opens to the page its ribbon marks.")
def tally_open(v, rng):
    """the Tally's cover lifted and laid back on its spine: the stiff leather creaking as it bends (a short, dry, rising creak),
    the cover flopping over and the leaves falling apart to the page where the ribbon lies (a rustle that settles), and a thumb
    run down the notched edge (a quick run of small ticks). v1 opened slowly, v2 flipped open"""
    dur = 0.95
    sl = [1.0, 0.65][v]
    m = Mix(dur)
    m.add(nrm(tf.creak(0.2 * sl, rng, 40, 130, [900.0, 1500.0, 2300.0], t60=0.03)), 0.0, 0.4)
    at = 0.2 * sl
    m.add(tf.thud(0.14, rng, f=190, decay=0.018, tone=0.2), at, 0.55)
    m.add(nrm(works.burst(0.04, rng, 400, 3500, 0.007)), at, 0.4)
    m.add(tf.paper_flap(0.16, rng, size=1.0), at + 0.02, 0.35)
    rd = 0.3
    m.add(nrm(crackle(rd, rng, 800 * bell(rd, 0.2, 1.0, 1.4) + 20, decay=0.0007, f_lo=1200, f_hi=6500, size_var=1.0)), at + 0.05, 0.3)
    nt = at + 0.38
    for k in range(9):
        tt = nt + 0.011 * k * (1.0 - 0.02 * k) + rng.uniform(-0.002, 0.002)
        f = 2300.0 * (1.0 + 0.04 * rng.standard_normal())
        m.add(nrm(ping(0.02, [f, f * 1.9], [0.004, 0.003], [1, .5], rng, ex_len=0.0003)), tt, 0.16 * rng.uniform(0.6, 1.0))
    return hall(lp(fit(m.out(), ns(dur)), 9000), 0.06, 8141 + v)


@effect("tally_page", 3, "Page turns", reach=8, loud=-23.0, max_len=0.5, fade_out=0.08,
        when="Part 1 (B): turning a page of the Tally's five (Word, Routes, Held, Ledger, Standing).")
def tally_page(v, rng):
    """one leaf of the Tally turned: the corner caught up between finger and thumb, the paper swung across (a thin rush of air
    with a crackle as it flexes) and laid down with a soft pat. v1 an ordinary leaf, v2 a quick one, v3 a stiff, slower one"""
    dur = 0.5
    sd = [0.13, 0.095, 0.18][v]
    m = Mix(dur)
    m.add(nrm(crackle(0.05, rng, 1500, decay=0.0006, f_lo=1800, f_hi=7500, size_var=0.8) * bell(0.05, 0.45, 1.0, 1.5)), 0.0, 0.25)
    s = bell(sd, 0.4, 1.4, 1.4)
    air = bp(colored(sd, rng, -1.5), 700, 5500) * s ** 1.3
    flex = crackle(sd, rng, 600 * s + 10, decay=0.0007, f_lo=1500, f_hi=7500, size_var=0.8) * s
    m.add(nrm(nrm(air) + 0.8 * nrm(flex)), 0.03, 0.8)
    at = 0.03 + sd - 0.015
    m.add(tf.thud(0.1, rng, f=[250, 285, 215][v], decay=0.01, tone=0.1), at, 0.4)
    m.add(works.burst(0.025, rng, 500, 4000, 0.005), at, 0.35)
    return hall(lp(fit(m.out(), ns(dur)), 9500), 0.05, 8151 + v)


@effect("tally_notch", 3, "Notch is cut", reach=8, loud=-23.0, max_len=0.35, fade_out=0.06,
        when=("Parts 1 and 2 (B, C): the knife cutting a notch in the Tally: reading a Haul Board (it marks what was copied), "
              "a delivery ('the Tally cuts a notch') and the Ledger's notches counting down."))
def tally_notch(v, rng):
    """a knife's point cutting a notch in a hazel tally stick: the blade drawn across and bitten in (a dry, raspy shave of
    fibres), the wedge coming free with a short, hard 'tk', the stick ringing faintly in the hand and the chip falling. v1 one
    cut, v2 a deeper cut with a second stroke, v3 a quick nick"""
    dur = 0.35
    m = Mix(dur)
    pl = [[(0.0, 0.09)], [(0.0, 0.07), (0.1, 0.07)], [(0.0, 0.05)]][v]
    end = 0.0
    for at, d in pl:
        s = bell(d, 0.7, 1.2, 1.0)
        shave = bp(colored(d, rng, -1.0), 1800, 7500) * s
        fib = crackle(d, rng, 1800 * s + 30, decay=0.0004, f_lo=2000, f_hi=8000, size_var=0.6) * s
        m.add(nrm(nrm(shave) + 0.7 * nrm(fib)), at, 0.6)
        end = at + d
    m.add(nrm(ping(0.05, [1450.0, 2700.0, 4300.0], [0.018, 0.012, 0.008], [1, .6, .3], rng, ex_len=0.0004)), end, 0.5)
    m.add(works.burst(0.006, rng, 1500, 9000, 0.0015), end, 0.4)
    m.add(nrm(tf.knock(0.18, [640.0, 1380.0, 2300.0], [0.06, 0.04, 0.025], [1, .6, .3], rng, lo=250, hi=5000, ex_len=0.0008)),
          end, 0.18)
    m.add(nrm(ping(0.03, [1900.0, 3300.0], [0.008, 0.005], [1, .5], rng, ex_len=0.0003)), end + 0.09 + 0.02 * v, 0.1)
    return hall(fit(m.out(), ns(dur)), 0.04, 8161 + v)


@effect("report_file", 2, "Slip is filed", reach=12, loud=-20.0, max_len=1.05, fade_out=0.15,
        when=("Part 1 (B, C): the File report button: the merchant at the board takes your slip of paper and puts it away "
              "before she re-chalks the row."))
def report_file(v, rng):
    """the clerk taking your slip of paper and filing it: the slip plucked from the hand (a crisp flick), creased over once,
    slid into the satchel (a long, soft rustle) and the flap buckled down (a pat of leather and the buckle's tick). v1 and v2
    differ in how many times the slip is creased and how long the rustle goes on"""
    dur = 1.05
    m = Mix(dur)
    m.add(tf.paper_flap(0.07, rng, size=0.7), 0.0, 0.55)
    ck = [0.16, 0.2][v]
    m.add(nrm(crackle(0.06, rng, 1700, decay=0.0006, f_lo=1500, f_hi=7000, size_var=0.8) * bell(0.06, 0.4, 1.0, 1.5)), ck, 0.4)
    if v == 1:
        m.add(nrm(crackle(0.05, rng, 1500, decay=0.0006, f_lo=1500, f_hi=7000, size_var=0.8) * bell(0.05, 0.4, 1.0, 1.5)), ck + 0.13, 0.3)
    rd = [0.34, 0.42][v]
    sl = bp(colored(rd, rng, -1.8), 500, 4200) * bell(rd, 0.35, 1.4, 1.4) * smooth_random(rd, rng, 22, 0.5, 1.0)
    m.add(nrm(sl), ck + 0.1, 0.4)
    m.add(nrm(crackle(rd, rng, 500 * bell(rd, 0.35, 1.0, 1.5) + 10, decay=0.0007, f_lo=1200, f_hi=6000, size_var=1.0)), ck + 0.1, 0.3)
    bk = ck + 0.1 + rd + 0.05
    m.add(tf.thud(0.1, rng, f=170, decay=0.012, tone=0.1), bk, 0.4)
    m.add(works.burst(0.03, rng, 300, 3000, 0.006), bk, 0.3)
    m.add(nrm(ping(0.04, [2600.0, 4100.0], [0.01, 0.006], [1, .5], rng, ex_len=0.0003)), bk + 0.05, 0.25)
    return hall(lp(fit(m.out(), ns(dur)), 9000), 0.06, 8171 + v)


# =========================================================================== a haul delivered
@effect("knock", 4, "Tally stick knocks", reach=16, loud=-16.5, max_len=0.3, fade_out=0.06, limit=6.0, vol=0.7, soft=4.0,
        when=("Part 2 (A, `Stalls.hauled`): the keeper counts the goods off on a tally stick as the delivery starts, one knock for "
              "each ten units (at least one, at most eight: a delivery of 120 knocks eight times), the first four ticks in and "
              "then every five ticks (a quarter of a second apart), each played at the keeper at volume 0.7 and pitch 0.95 to "
              "1.05; the file is levelled for that, and each knock sounds on while the next comes."))
def tally_knock(v, rng):
    """a keeper's hazel tally stick rapped on the counter, one knock for each ten units: the stick's end (a thin, hard 'tk',
    quickly dying), the oak counter under it ringing hollow and short, and the stick itself ringing faintly in the hand. The
    four differ in the weight of the blow and where on the counter it lands (a higher, drier note near the edge, a deeper one
    over the drawer), so that twelve knocks in a row are not one knock twelve times"""
    f = [260.0, 310.0, 235.0, 285.0][v] * rng.uniform(0.99, 1.01)
    firm = [1.0, 0.85, 1.1, 0.9][v]
    m = Mix(0.3)
    m.add(nrm(ping(0.05, [2300.0 * rng.uniform(0.96, 1.04), 4100.0, 6200.0], [0.01, 0.007, 0.004], [1, .6, .3], rng)), 0.0, 0.45 * firm)
    m.add(nrm(ship.oak(0.25, rng, f, t60=0.07, hard=0.8, lo=200.0)), 0.0, 0.9 * firm)
    m.add(tf.thud(0.12, rng, f=f * 0.5, decay=0.02, tone=0.3), 0.0, 0.7 * firm)
    m.add(nrm(tf.knock(0.18, [720.0, 1550.0, 2500.0], [0.07, 0.045, 0.03], [1, .6, .3], rng, lo=250, hi=5000, ex_len=0.001)), 0.0, 0.2)
    return hall(fit(m.out(), ns(0.3)), 0.05, 8201 + v)


@effect("deliver", 3, "Crate set down", reach=16, loud=-14.0, max_len=0.75, fade_out=0.15, limit=5.0, vol=0.7, soft=4.0,
        when=("Part 2 (A, `Stalls.hauled`): a haul delivered at the shop's counter: the keeper takes the goods across and, thirty "
              "ticks in (as the handing ends), sets the crate down, before the Marks are counted out; played at the keeper at "
              "volume 0.7 and pitch 0.95 to 1.05, the file levelled for that."))
def deliver(v, rng):
    """a crate of goods set down on the counter's boards: the crate's own knock, the floor under it and what is inside
    settling. v1 jars (clay knocking in the straw), v2 sacks (a dull thump and grain shifting), v3 ingots (iron clanking on iron
    inside)"""
    dur = 0.75
    f = [235.0, 205.0, 255.0][v] * rng.uniform(0.99, 1.01)
    m = Mix(dur)
    m.add(nrm(tf.knock(0.35, f * np.array([1.0, 1.62, 2.41, 3.37, 4.6]), [0.06, 0.05, 0.035, 0.025, 0.017], [1, .8, .55, .35, .2],
                       rng, lo=120, hi=4500, ex_len=0.003)), 0.0, 0.8)
    m.add(tf.thud(0.3, rng, [92.0, 80.0, 100.0][v], decay=0.03, tone=0.4), 0.0, 0.8)
    if v == 0:
        for at, f0, g in ((0.015, 1350.0, 1.0), (0.06, 1720.0, 0.7), (0.115, 1180.0, 0.45)):
            m.add(nrm(tf.clay(0.2, rng, f0 * rng.uniform(0.97, 1.03))), at, 0.35 * g)
    elif v == 1:
        m.add(tf.thud(0.2, rng, f=120.0, decay=0.03, tone=0.2), 0.02, 0.5)
        m.add(tf.rustle(0.35, rng, peak=0.3, density=500, lo=600, hi=5000), 0.03, 0.3)
    else:
        m.add(nrm(ship.iron_ring(0.4, rng, [900.0 * rng.uniform(0.95, 1.05), 1400.0], t60=0.12, gains=[1.0, 0.6])), 0.01, 0.35)
        m.add(nrm(ping(0.06, [2800.0, 4300.0], [0.012, 0.008], [1, .5], rng)), 0.012, 0.2)
        m.add(nrm(ship.iron_ring(0.3, rng, [1100.0, 1700.0], t60=0.08, gains=[1.0, 0.5])), 0.075, 0.2)
    return hall(fit(m.out(), ns(dur)), 0.07, 8211 + v)


# =========================================================================== carrying: the bell, the buckle, the whistle, the clink
# a small brass bell with its tongue, hung on a strap: (ratio to the nominal, level in dB, T60 in s, beat in Hz) for each mode
# (the cog's bronze bell, `make_ship_audio.BRONZE`, cut down to a small brass one: it rings for half a second, not for four)
SMALL_BRASS = [
    (0.5, -9.0, 0.75, 1.0), (1.0, 0.0, 0.62, 1.6), (1.19, -9.0, 0.46, 2.0), (1.5, -7.0, 0.4, 2.4), (2.0, -4.0, 0.3, 3.0),
    (2.51, -10.0, 0.22, 3.5), (3.0, -9.0, 0.16, 4.0), (4.17, -14.0, 0.1, 5.0), (5.4, -18.0, 0.07, 6.0),
]
DONKEY_BELL = 1450.0  # the nominal of the one bell the pack animal wears (its strike note is the octave below the nominal's pair)


def donkey_bell_modes():
    """the pack animal's bell: always the same bell, whichever file and whichever stride (its modes are fixed, so that a stride
    struck on a bell still ringing from the last adds to it the way a second blow does)"""
    return ship.bell_modes(DONKEY_BELL, rng_for("trade", "donkey_bell"), table=SMALL_BRASS, detune=0.002)


def bell_hit(dur, modes, rng, hard=1.0):
    """one blow of the tongue on the bell: its modes from rest, and the little bright tick of brass on brass"""
    y = nrm(ship.bell_blow(dur, modes, hard=hard, fc=6500.0))
    tik, _ = ship.clapper(rng, hard)
    return y + 0.12 * hard * fit(tik, len(y))


@effect("bell_step", 3, "Bell tinkles", reach=24, loud=-18.0, max_len=0.85, fade_out=0.2, limit=4.0, vol=0.55,
        when=("Part 3 (D1, PackFollow.bell): for every stride a harnessed pack animal walks (every 1.6 blocks) its brass bell "
              "rings once, played at 0.55 volume and pitch 0.9 to 1.1; the bell is hushed while the animal is spooked or "
              "told to stay."))
def bell_step(v, rng):
    """the brass bell on the pack animal's breast strap knocked by one stride: the pendulum thrown against the bell's side
    (a bright, short strike, the bell's hum and prime and the tierce above them ringing out and dying at their own rates, the
    upper ones first), the bell's body knocking the animal's chest on its leather strap (a dull 'tok' and a rub of hide).
    It is always the same bell. v1 an ordinary stride, v2 a soft one with the pendulum bouncing a second time off the side,
    v3 a long stride, struck harder"""
    dur = 0.85
    modes = donkey_bell_modes()
    hard = [0.8, 0.5, 1.0][v]
    m = Mix(dur)
    m.add(bell_hit(dur, modes, rng, hard), 0.0, 1.0)
    if v == 1:
        m.add(bell_hit(dur - 0.05, modes, rng, 0.3), 0.052, 0.45)
    m.add(tf.thud(0.08, rng, f=145.0, decay=0.012, tone=0.1), 0.0, 0.14 * hard)
    m.add(nrm(works.burst(0.05, rng, 300, 2200, 0.012)), 0.0, 0.07 * hard)
    return road(lp(fit(m.out(), ns(dur)), 12000), 0.05, 8221 + v)


@effect("bell_trot", 3, "Bell jingles", reach=24, loud=-18.0, max_len=0.9, fade_out=0.2, limit=4.0, vol=0.7,
        when=("Part 3 (D1, PackFollow.bell): the same bell for every stride of a trot (every 2.4 blocks), played at 0.7 volume and "
              "pitch 0.9 to 1.1: the bell rings faster as the animal trots."))
def bell_trot(v, rng):
    """the same bell flung about by a trot: the pendulum striking one side and then the other, three or four blows close
    together, each landing on the ringing of the last (so the bell chatters, then rings on), and the bell body knocking the
    chest harder. v1 three blows, v2 four, quicker, v3 two, then a third, late and soft"""
    dur = 0.9
    modes = donkey_bell_modes()
    plan = [[(0.0, 0.9), (0.075, 0.7), (0.15, 0.85)], [(0.0, 1.0), (0.06, 0.65), (0.12, 0.8), (0.19, 0.55)],
            [(0.0, 0.85), (0.09, 0.9), (0.24, 0.4)]][v]
    m = Mix(dur)
    for at, hard in plan:
        at = at + rng.uniform(-0.006, 0.006) if at else 0.0
        m.add(bell_hit(dur - at, modes, rng, hard), at, 0.8 * hard + 0.2)
        m.add(tf.thud(0.07, rng, f=150.0, decay=0.01, tone=0.1), at, 0.12 * hard)
    return road(lp(fit(m.out(), ns(dur)), 12000), 0.05, 8231 + v)


@effect("harness_buckle", 2, "Harness is buckled", reach=16, loud=-19.0, max_len=0.8, fade_out=0.15, limit=4.0,
        when=("Part 3 (D1): the Pack Harness fitted to a donkey, mule or llama (volume 1.0), taken off again (0.8, pitch 0.8, "
              "so lower and slower: unbuckling), and the merchant laying the Drover's Whistle across the harness on the counter "
              "(0.8): 'a click and a jingle'."))
def harness_buckle(v, rng):
    """the straps of the harness buckled on the animal: the leather strap drawn through its keeper (a short, dry slide), the
    buckle's tongue found and dropped into its hole (a small, bright 'tk' of iron and the strap slapping flat), the brass rings
    of the hames and the bell on the breast strap jingling as it is settled, and the blanket's soft thump. v1 one buckle and
    the jingle after it, v2 two straps, one on each side, quicker, with less jingle"""
    dur = 0.8
    m = Mix(dur)
    straps = [[0.0], [0.0, 0.2]][v]
    for k, at in enumerate(straps):
        sd = 0.11
        slide = bp(colored(sd, rng, -1.5), 600, 3500) * bell(sd, 0.5, 1.2, 1.4)
        grain = crackle(sd, rng, 900 * bell(sd, 0.5, 1.0, 1.0) + 20, decay=0.0004, f_lo=1500, f_hi=6000, size_var=0.7)
        m.add(nrm(nrm(slide) + 0.4 * nrm(grain)), at, 0.4)
        m.add(nrm(tf.creak(0.09, rng, 60, 160, [1200.0, 1900.0, 2800.0], t60=0.02)), at, 0.22)
        bt = at + sd
        f = 2150.0 * rng.uniform(0.96, 1.04)
        m.add(nrm(ship.iron_ring(0.12, rng, [f], t60=0.05, gains=[1.0])), bt, 0.55)
        m.add(works.tick(rng, 2500.0, 11000.0, 0.0005, 0.004), bt, 0.5)
        m.add(tf.thud(0.1, rng, f=185.0, decay=0.012, tone=0.1), bt + 0.03, 0.4)
        m.add(works.burst(0.025, rng, 300, 3000, 0.006), bt + 0.03, 0.25)
    jt = straps[-1] + 0.11 + 0.03
    jg = [1.0, 0.5][v]
    m.add(nrm(jingle(0.35, rng, 7, 2000, 6000, t60=(0.03, 0.09), start=0.0, spread=0.12)), jt, 0.3 * jg)
    m.add(bell_hit(0.5, donkey_bell_modes(), rng, 0.35), jt + 0.02, 0.4 * jg)
    m.add(works.cloth(0.3, rng, env_points([(0.0, 0.0), (0.1, 0.8), (0.3, 0.0)], 0.3), lo=200, hi=2500), jt, 0.15)
    return road(lp(fit(m.out(), ns(dur)), 11000), 0.05, 8241 + v)


def whistle_note(dur, rng, f, f_end=None, blow=1.0, attack=0.03, release=0.07, warble=0.004):
    """one note on a drover's whistle (a small wooden pipe on a cord: a jet of breath splitting on an edge): a nearly pure tone
    (the fundamental, a faint second and third harmonic), the pitch a few per cent sharp for the first instants of a hard blow
    and settling, a wavering where the breath is not quite steady, a chiff of breath at the start and the jet's hiss riding on
    the tone. f slides to f_end where the note falls or climbs"""
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    base = f * ((f_end / f) ** u if f_end else 1.0)
    fr = base * (1 + 0.03 * blow * np.exp(-t / 0.05)) * (1 + warble * np.sin(2 * np.pi * rng.uniform(5.0, 6.5) * t + rng.uniform(0, 6.3))
                                                      + 0.003 * (2 * smooth_random(dur, rng, 6) - 1))
    ph = phase_from_freq(fr, n)
    tone = np.sin(ph) + 0.10 * np.sin(2 * ph + rng.uniform(0, 6.3)) + 0.035 * np.sin(3 * ph + rng.uniform(0, 6.3))
    env = np.clip(t / attack, 0, 1) ** 1.5 * np.clip((dur - t) / release, 0, 1) ** 1.2 * (0.92 + 0.08 * smooth_random(dur, rng, 7))
    jet = tvf(white(dur, rng), "bp", fr * 1.02, 6.0, block=64)
    chiff = bp(white(dur, rng), f * 0.8, f * 2.2) * np.exp(-t / 0.018) * np.clip(t / 0.003, 0, 1)
    y = tone * env + 0.1 * nrm(jet) * env + 0.18 * nrm(chiff)
    return nrm(y)


@effect("whistle_come", 2, "Whistle blows", reach=32, loud=-14.0, max_len=1.1, fade_out=0.2, limit=4.0,
        when=("Part 3 (D1, PackFollow.whistle): using the Drover's Whistle (two notes, rising): every harnessed animal of yours is "
              "called to follow; played from the player (source PLAYERS, volume 1.0); also the merchant's horn in the donkey "
              "scene at the counter."))
def whistle_come(v, rng):
    """two notes on the drover's whistle, the second a major third over the first: the first short, to catch the ear, the
    second longer, with a small slide up into it. v1 and v2 are two blows of the same whistle, the second blown a little
    harder and a little higher"""
    dur = 1.0
    f1 = [1680.0, 1740.0][v] * rng.uniform(0.99, 1.01)
    f2 = f1 * 1.26
    m = Mix(dur)
    m.add(whistle_note(0.26, rng, f1, blow=1.0), 0.0, 0.9)
    m.add(whistle_note(0.5, rng, f2 * 0.95, f_end=f2, blow=0.8, warble=0.006), 0.31, 1.0)
    return road(fit(m.out(), ns(dur)), 0.08, 8251 + v)


@effect("whistle_stay", 2, "Whistle sounds low", reach=32, loud=-14.0, max_len=1.0, fade_out=0.2, limit=4.0,
        when=("Part 3 (D1, PackFollow.whistle): sneaking and using the Drover's Whistle (one note, falling): every harnessed animal "
              "of yours within range is told to stay where it stands; played from the player (PLAYERS, 1.0)."))
def whistle_stay(v, rng):
    """one long note on the drover's whistle, blown firm and let fall as the breath slackens: the pitch sags a fifth over
    the note and the tone thins as it goes. v1 and v2 differ in how far it falls"""
    dur = 0.9
    f = [2080.0, 2010.0][v] * rng.uniform(0.99, 1.01)
    y = whistle_note(0.7, rng, f, f_end=f * [0.74, 0.80][v], blow=1.1, release=0.18, warble=0.007)
    return road(fit(y, ns(dur)), 0.08, 8261 + v)


def pot(dur, rng, f0, t60=0.12, glaze=1.0):
    """a glazed clay pot knocked: a bowl's modes (1, 2.32, 3.9, 5.7 and 7.9 times the lowest), each a close pair that beats, dying
    quickly, the upper ones first; glaze lifts the upper ones"""
    r = np.array([1.0, 2.32, 3.9, 5.7, 7.9]) * rng.uniform(0.996, 1.004, 5)
    return tf.ring(dur, f0 * r, t60 * np.array([1.0, 0.7, 0.45, 0.3, 0.2]),
                   np.array([1.0, 0.75, 0.5, 0.32, 0.18]) * np.array([1, 1, glaze, glaze ** 1.5, glaze ** 2]), rng, doublet=0.004,
                   doublet_amp=0.5)


@effect("pack_clink", 4, "Pots clink", reach=16, loud=-16.5, max_len=0.4, fade_out=0.1, limit=5.0, vol=0.3, soft=4.0,
        when=("Part 3 (D1, client LadenLook): a trader laden with 250 Marks or more of goods wears the tall frame, and its pots "
              "clink with their steps (the tall frame on every step; the lantern on the middle frame on a sprint), at volume "
              "0.16 to 0.42 and pitch 1.22 down to 0.80 for the heavier load."))
def pack_clink(v, rng):
    """the pots and cups tied on a trader's tall pack frame knocking together as they walk: two glazed clay pots (a short,
    bright, dying ring), a tin cup against one (a thin tink over a duller one), a lone glaze click, a soft jostle of three. The
    game plays it at 0.16 to 0.42 and lowers it for a heavier load, so it is made at the middle pitch of that"""
    dur = 0.4
    m = Mix(dur)
    if v == 0:
        m.add(nrm(pot(0.25, rng, 1350.0, 0.1)), 0.0, 1.0)
        m.add(nrm(pot(0.2, rng, 1640.0, 0.08)), 0.03, 0.6)
    elif v == 1:
        m.add(nrm(tf.ring(0.3, 2400.0 * np.array([1.0, 2.76, 5.4]), [0.16, 0.08, 0.04], [1, .5, .25], rng, doublet=0.003)), 0.0, 0.6)
        m.add(nrm(pot(0.2, rng, 1100.0, 0.07)), 0.0, 0.8)
        m.add(works.tick(rng, 2500.0, 12000.0, 0.0005, 0.004), 0.0, 0.35)
    elif v == 2:
        m.add(nrm(pot(0.22, rng, 2100.0, 0.06, glaze=1.3)), 0.0, 0.8)
        m.add(works.tick(rng, 3000.0, 12000.0, 0.0004, 0.003), 0.0, 0.3)
    else:
        for at, f0, g in ((0.0, 1500.0, 0.8), (0.055, 1900.0, 0.6), (0.11, 1250.0, 0.45)):
            m.add(nrm(pot(0.2, rng, f0 * rng.uniform(0.98, 1.02), 0.05)), at, g)
    return square(fit(m.out(), ns(dur)), 0.04, 8271 + v)


# =========================================================================== carrying: the cart and the wagon
REV = 1.07  # seconds a wheel of the Chapman's Cart takes to turn once at the pace it is pulled (radius 10/16 of a block: 3.93 blocks
#            a turn; 3.67 blocks a second: the walk of 4.317 slowed by 15%), so the loop is eight turns of it at pitch 1.0
REVS = 8


@loop("cart_creak", REV * REVS, 24, "Cart creaks", rms_db=-25.0, ceiling_db=-9.0,
      when=("Part 3 (D2, client): a loop under the Chapman's Cart while it is pulled, following it, its volume and pitch following "
            "the cart's speed (pitch = speed / 3.67 blocks a second, so that each wheel creaks once a turn at every speed); the "
            "wheels' roll over the ground is the caravans' own (`caravan.roll`, `roll_stone`, `roll_planks` by `CaravanSounds.Ground`)."))
def cart_creak(rg, rng):
    """a loaded handcart's axles under their weight, going round: each of the two wooden wheels creaks once a turn as the load's
    weight comes round onto a worn place in its hub (a dry squeak that climbs a little and sags, caught and let go forty times
    a second, over the low, woody groan of the axle box), the two half a turn apart and never quite, one a little higher and
    louder than the other, every turn a little different; the bed's boards working now and then (a longer, lower groan); a soft
    knock of the felloe joint under each wheel once a turn; and loose things on the bed rattling as the wheels ride over what
    they ride over (a pot lid, a tool, a few coins' worth of small clinks), more of them at some turns than at others"""
    P = rg.P
    sq, gr, bed, tok, rat = rg.new(), rg.new(), rg.new(), rg.new(), rg.new()
    wander_ = rg.curve(rng, 1, 3, 1.0)

    def at_level(t):
        return float(np.exp(0.32 * wander_[rg.n + int(t * SR) % rg.n]))

    for k in range(REVS):
        for w in range(2):
            at = k * REV + w * 0.5 * REV + 0.02 * rng.standard_normal() + 0.05
            g = [1.0, 0.72][w] * rng.uniform(0.7, 1.0) * at_level(at)
            sd = rng.uniform(0.3, 0.46)
            f = [610.0, 735.0][w] * rng.uniform(0.94, 1.06)
            sqk = ship.squeal(sd, rng, ship.path([(0, f * 0.88), (sd * 0.35, f), (sd * 0.75, f * 1.06), (sd, f * 0.96)], sd),
                              bell(sd, 0.4, 1.4, 1.6), harmonics=7, tilt=1.2, chatter=0.55, wobble=0.02, peak_hz=1600.0)
            rg.place(sq, sqk, wrap(rg, at), g)
            gd = sd * 1.3
            grk = ship.creak_voice(gd, rng, ship.path([(0, 45), (gd * 0.5, 95), (gd, 55)], gd), bell(gd, 0.45, 1.3, 1.5),
                                   ship.timber_modes(rng, 140.0, 1400.0, 14, 0.07, 0.02, peaks=(380.0, 760.0)),
                                   vowel=[(ship.path([(0, 420), (gd, 620)], gd), 4.0, 0.6)], jitter=0.1)
            rg.place(gr, grk, wrap(rg, at - 0.04), g * 0.8)
        kn = ship.oak(0.15, rng, 280.0 * rng.uniform(0.95, 1.05), t60=0.05, hard=0.6)
        rg.place(tok, nrm(kn), wrap(rg, k * REV + 0.42 + 0.01 * rng.standard_normal()), rng.uniform(0.5, 1.0))
        rg.place(tok, nrm(kn), wrap(rg, k * REV + 0.95 + 0.01 * rng.standard_normal()), rng.uniform(0.3, 0.7))
    for k in range(3):  # the bed's boards, now and then
        at = (k * REVS / 3.0 + rng.uniform(0.1, 0.9)) * REV
        bd = rng.uniform(0.5, 0.85)
        bedk = ship.creak_voice(bd, rng, ship.path([(0, 28), (bd * 0.5, 70), (bd, 40)], bd), bell(bd, 0.5, 1.3, 1.5),
                                ship.timber_modes(rng, 90.0, 900.0, 14, 0.09, 0.03, peaks=(230.0, 520.0)),
                                vowel=[(ship.path([(0, 260), (bd, 400)], bd), 3.5, 0.7)], jitter=0.12)
        rg.place(bed, bedk, wrap(rg, at), rng.uniform(0.5, 0.9))
    for _ in range(int(rng.integers(9, 13))):  # loose things on the bed
        at = rng.uniform(0.0, P)
        kind = rng.integers(0, 3)
        if kind == 0:
            ev = ping(0.12, [f0 := rng.uniform(1500.0, 2600.0), f0 * 2.3, f0 * 4.1], [0.05, 0.03, 0.02], [1, .5, .25], rng)
        elif kind == 1:
            ev = ship.oak(0.12, rng, rng.uniform(350.0, 700.0), t60=0.03, hard=0.9)
        else:
            ev = chain_links(rng, count=4, span=0.1, size=0.6)
        rg.place(rat, nrm(ev), wrap(rg, at), rng.uniform(0.3, 1.0) * at_level(at))
    rub = bp(rg.noise(rng, -1.5), 500.0, 2500.0, 2) * rg.bumps([k * REV / 2 + 0.1 for k in range(2 * REVS)],
                                                               rng.uniform(0.3, 0.8, 2 * REVS), 0.05, 0.14)
    x = 1.0 * rn(sq) + 0.55 * rn(gr) + 0.3 * rn(bed) + 0.22 * rn(tok) + 0.2 * rn(rat) + 0.1 * rn(rub)
    return lp(x, 9500.0, 2)


@effect("shafts_lift", 2, "Cart shafts lift", reach=16, loud=-19.0, max_len=1.0, fade_out=0.2, limit=4.0,
        when=("Part 3 (D2): right-clicking the shafts (or the cart) with an empty hand to take them up: the shafts lift, a wooden "
              "knock, and the cart rolls after you."))
def shafts_lift(v, rng):
    """the shafts of the Chapman's Cart taken up from where it stands tipped forward on them: a hand closing on the wood (a soft
    thud and a rub), the shaft tips lifted off the ground (a scrape, a little grit falling off them), the axle taking the weight
    with a low groan in its box, the bed rocking back (boards knocking, what is in it shifting) and the shafts coming level with
    a wooden knock against the cart's front board. v1 and v2 differ in how stiffly it comes up"""
    dur = 1.0
    m = Mix(dur)
    m.add(tf.thud(0.07, rng, f=240, decay=0.01, tone=0.1), 0.0, 0.3)
    m.add(works.scuff(0.07, rng, 400, 3000), 0.0, 0.2)
    gt = tvec(0.18)
    m.add(nrm(ship.grain_cloud(0.18, rng, 2500 * np.exp(-gt / 0.07) + 30, decay=0.0007, f_lo=500, f_hi=4500)), 0.07, 0.25)
    gd = [0.38, 0.3][v]
    m.add(nrm(ship.creak_voice(gd, rng, ship.path([(0, 40), (gd * 0.6, 85), (gd, 60)], gd), bell(gd, 0.5, 1.3, 1.4),
                               ship.timber_modes(rng, 120.0, 1300.0, 14, 0.08, 0.02, peaks=(300.0, 640.0)),
                               vowel=[(ship.path([(0, 350), (gd, 520)], gd), 4.0, 0.6)])), 0.1, 0.6)
    for at, f, g in ((0.2, 210.0, 0.5), (0.3, 255.0, 0.35), (0.39, 190.0, 0.3)):
        m.add(nrm(ship.oak(0.18, rng, f * rng.uniform(0.95, 1.05), t60=0.05, hard=0.5)), at, g)
    kn = 0.5 + 0.06 * v
    m.add(nrm(tf.knock(0.2, [320.0, 700.0, 1180.0, 1800.0], [0.06, 0.045, 0.03, 0.02], [1, .7, .45, .25], rng, lo=150, hi=4000,
                       ex_len=0.002)), kn, 0.9)
    m.add(tf.thud(0.12, rng, f=150, decay=0.02, tone=0.3), kn, 0.5)
    m.add(nrm(chain_links(rng, count=4, span=0.1, size=0.6)), kn + 0.02, 0.12)
    return road(lp(fit(m.out(), ns(dur)), 9500), 0.05, 8301 + v)


@effect("shafts_drop", 2, "Cart shafts clatter down", reach=16, loud=-16.0, max_len=1.1, fade_out=0.25, limit=4.0,
        when=("Part 3 (D2): letting go of the shafts (the cart tips forward onto them where you stand), and when the cart has "
              "caught on something and the shafts 'drop out of your hands by themselves with a clatter and a puff of dust'."))
def shafts_drop(v, rng):
    """the shafts falling out of the puller's hands: the wood meeting the ground (the tips strike and the whole length slaps down
    after them, a heavy double thud), a smaller rebound, the cart rocking forward onto its shafts with a clatter of boards and
    loose things, a puff of dust (a low breath of noise) and the axle's creak as the weight settles. v1 a drop onto earth, v2
    onto stones, with a harder clack and a longer clatter"""
    dur = 1.1
    hard = [0.7, 1.0][v]
    m = Mix(dur)
    m.add(tf.thud(0.25, rng, f=95, decay=0.05, tone=0.5), 0.0, 0.9)
    m.add(nrm(ship.oak(0.3, rng, 150.0, t60=0.12, hard=hard)), 0.0, 0.8)
    m.add(nrm(ship.grain_cloud(0.12, rng, 4000 * np.exp(-tvec(0.12) / 0.03) + 40, decay=0.0007, f_lo=500, f_hi=4000)), 0.0, 0.3 + 0.2 * v)
    m.add(tf.thud(0.2, rng, f=120, decay=0.03, tone=0.4), 0.045, 0.7)
    m.add(nrm(ship.oak(0.25, rng, 230.0, t60=0.08, hard=hard)), 0.045, 0.6)
    m.add(tf.thud(0.12, rng, f=105, decay=0.02, tone=0.3), 0.14, 0.4)
    m.add(nrm(ship.oak(0.15, rng, 190.0, t60=0.05, hard=0.6)), 0.14, 0.3)
    if v == 1:
        m.add(nrm(ping(0.1, [1900.0, 3000.0, 4800.0], [0.04, 0.025, 0.015], [1, .5, .3], rng)), 0.0, 0.25)
    t = 0.09
    for _ in range(int(rng.integers(6, 9)) + 2 * v):
        t += rng.uniform(0.015, 0.05)
        if rng.random() < 0.55:
            m.add(nrm(ship.oak(0.1, rng, rng.uniform(260.0, 520.0), t60=0.03, hard=0.8)), t, 0.35 * np.exp(-(t - 0.09) / 0.2))
        else:
            m.add(nrm(ping(0.1, [f0 := rng.uniform(1400.0, 2800.0), f0 * 2.3, f0 * 3.9], [0.04, 0.025, 0.015], [1, .5, .3], rng)), t,
                  0.3 * np.exp(-(t - 0.09) / 0.2))
    dd = 0.4
    dust = bp(colored(dd, rng, -2.0), 200, 1800) * bell(dd, 0.25, 1.2, 1.6)
    m.add(nrm(dust), 0.02, 0.22)
    gd = 0.3
    m.add(nrm(ship.creak_voice(gd, rng, ship.path([(0, 35), (gd * 0.5, 75), (gd, 45)], gd), bell(gd, 0.4, 1.3, 1.5),
                               ship.timber_modes(rng, 120.0, 1200.0, 12, 0.08, 0.02, peaks=(280.0, 600.0)))), 0.22, 0.3)
    return road(lp(fit(m.out(), ns(dur)), 10000), 0.05, 8311 + v)


@effect("canvas_flap", 2, "Canvas flaps", reach=16, loud=-19.0, max_len=1.4, fade_out=0.25, limit=4.0,
        when=("Part 3 (D2): sneaking and using an empty hand on the cart's canvas: it is pulled over the load or folded back "
              "('a flap, a cord pulled tight')."))
def canvas_flap(v, rng):
    """the Chapman's Cart's canvas worked over its load. v1 drawn over: the heavy cloth dragged across the heap (a soft rush
    with the weave crackling), thrown the last of the way so that it flaps and settles (a low thwap and a few smaller ones),
    and the cord hauled tight through its eyelets (the rope rasping, a creak of strain and the toggle knocking home). v2 folded
    back: the cord let go (a short rustle of rope), the canvas lifted and rolled up on itself (cloth sliding and creasing, two soft
    pats) and its flap as it comes to rest"""
    dur = 1.2
    m = Mix(dur)
    if v == 0:
        cd = 0.4
        m.add(works.cloth(cd, rng, env_points([(0.0, 0.1), (0.15, 0.9), (0.3, 0.7), (cd, 0.0)], cd), lo=200, hi=3200), 0.0, 0.6)
        m.add(works.flap(rng, size=1.4), 0.3, 0.9)
        m.add(works.flap(rng, size=0.8), 0.46, 0.35)
        m.add(works.flap(rng, size=0.6), 0.55, 0.2)
        rd = 0.35
        sp = env_points([(0.0, 0.0), (0.05, 0.8), (0.28, 1.0), (rd, 0.0)], rd)
        m.add(tame_(ship.rope_run(rd, rng, sp, lay=30.0, lo=600, hi=4800)), 0.62, 0.4)
        m.add(nrm(ship.creak_voice(0.2, rng, ship.path([(0, 55), (0.2, 110)], 0.2), bell(0.2, 0.7, 1.2, 1.2),
                                   ship.timber_modes(rng, 400.0, 2600.0, 12, 0.03, 0.01, peaks=(900.0,)))), 0.8, 0.25)
        m.add(nrm(tf.knock(0.1, [1300.0, 2300.0, 3700.0], [0.02, 0.014, 0.009], [1, .6, .3], rng, lo=500, hi=6000, ex_len=0.0008)),
              0.97, 0.45)
    else:
        rd = 0.22
        sp = env_points([(0.0, 0.0), (0.05, 0.6), (rd, 0.0)], rd)
        m.add(tame_(ship.rope_run(rd, rng, sp, lay=24.0, lo=500, hi=4500)), 0.0, 0.3)
        cd = 0.55
        m.add(works.cloth(cd, rng, env_points([(0.0, 0.0), (0.12, 0.7), (0.3, 0.9), (cd, 0.0)], cd), lo=200, hi=3000), 0.2, 0.7)
        m.add(nrm(crackle(cd, rng, 500 * bell(cd, 0.4, 1.0, 1.5) + 10, decay=0.0008, f_lo=1000, f_hi=6000, size_var=1.0)), 0.2, 0.25)
        for at, f in ((0.5, 150.0), (0.68, 175.0)):
            m.add(tf.thud(0.12, rng, f=f, decay=0.015, tone=0.1), at, 0.45)
            m.add(works.burst(0.04, rng, 250, 2800, 0.008), at, 0.35)
        m.add(works.flap(rng, size=0.9), 0.8, 0.5)
    return road(lp(fit(m.out(), ns(dur)), 9500), 0.05, 8321 + v)


def tame_(x, crest_db=10.0):
    """ship.tame, a texture's few biggest clicks held down to a crest of crest_db over its RMS"""
    return ship.tame(x, crest_db)


@effect("tailboard", 2, "Tailboard falls open", reach=16, loud=-17.0, max_len=1.2, fade_out=0.3, limit=4.0,
        when=("Part 3 (D3): right-clicking the wagon's tailboard: it falls open and the crates inside are seen (54 slots)."))
def tailboard(v, rng):
    """the tailboard of the carrier's wagon let down: the hasp lifted off its staple (a tick, a clack of iron), the board falling
    on its chains (the long plank swinging down, the links running out and rattling), its end fetching up against the stops with
    a deep, hollow boom (the board and the wagon's bed ringing under it) and the chains settling. v1 let down, v2 let go and
    allowed to drop"""
    dur = 1.2
    fall = [0.2, 0.12][v]
    m = Mix(dur)
    m.add(nrm(ship.iron_ring(0.15, rng, [1850.0 * rng.uniform(0.96, 1.04)], t60=0.06, gains=[1.0])), 0.0, 0.5)
    m.add(works.tick(rng, 2000.0, 10000.0, 0.0006, 0.005), 0.0, 0.5)
    m.add(nrm(tf.knock(0.08, [520.0, 980.0, 1600.0], [0.02, 0.014, 0.009], [1, .6, .3], rng, lo=250, hi=5000, ex_len=0.001)), 0.03, 0.35)
    sd = fall
    sw = bp(colored(sd, rng, -2.0), 150, 1800) * bell(sd, 0.7, 1.4, 1.0)
    m.add(nrm(sw), 0.06, 0.3)
    m.add(nrm(chain_links(rng, count=12, f_lo=1200.0, f_hi=3400.0, span=fall + 0.12, decay=0.9, size=1.0)), 0.07, 0.5)
    b = 0.06 + fall
    m.add(nrm(tf.knock(0.5, [150.0, 255.0, 410.0, 690.0, 1100.0], [0.16, 0.12, 0.08, 0.05, 0.03], [1, .8, .55, .35, .2], rng, lo=80, hi=3000,
                       ex_len=0.004)), b, 0.9)
    m.add(nrm(ship.oak(0.45, rng, 175.0, t60=0.18, hard=0.7, lo=120.0)), b, 0.8)
    m.add(ship.saturate(tf.thud(0.4, rng, f=85, decay=0.09, tone=0.6), 2.0), b, 0.7)
    m.add(nrm(chain_links(rng, count=9, f_lo=1000.0, f_hi=3000.0, span=0.3, decay=0.6, size=1.2)), b + 0.01, 0.45)
    t = b + 0.06
    for _ in range(4):
        t += rng.uniform(0.04, 0.1)
        m.add(nrm(ship.oak(0.12, rng, rng.uniform(260.0, 520.0), t60=0.03, hard=0.8)), t, 0.18)
    return road(lp(fit(m.out(), ns(dur)), 9500), 0.06, 8331 + v)


@effect("rein_slack", 2, "Rein goes slack", reach=16, loud=-22.0, max_len=0.9, fade_out=0.2, limit=4.0,
        when=("Part 3 (D3): when you get twenty blocks ahead of the wagon the rein goes slack, the horse stops and the wagon waits "
              "('The wagon is waiting.')."))
def rein_slack(v, rng):
    """the rein going slack: the leather that was drawn taut easing (a long, thin squeak that sags as the strain lets go), the
    strap sliding over the rail and falling in loops (a slither of leather and its rings), the iron ring at the horse's end
    jingling once and the slack rein slapping against the horse's flank. v1 and v2 differ in how long the strain lasts"""
    dur = 0.9
    sd = [0.32, 0.42][v]
    m = Mix(dur)
    sq = ship.squeal(sd, rng, ship.path([(0, 980.0), (sd, 560.0)], sd), bell(sd, 0.25, 1.1, 1.8), harmonics=5, tilt=1.6, chatter=0.6,
                     wobble=0.03)
    m.add(nrm(sq), 0.0, 0.35)
    m.add(nrm(ship.creak_voice(sd, rng, ship.path([(0, 60), (sd, 25)], sd), bell(sd, 0.3, 1.2, 1.6),
                               ship.timber_modes(rng, 300.0, 2800.0, 12, 0.03, 0.01, peaks=(1000.0,)))), 0.0, 0.25)
    sl = 0.3
    slide = bp(colored(sl, rng, -1.5), 500, 3500) * bell(sl, 0.4, 1.3, 1.4) * smooth_random(sl, rng, 18, 0.5, 1.0)
    m.add(nrm(slide), sd * 0.6, 0.3)
    m.add(nrm(crackle(sl, rng, 600 * bell(sl, 0.4, 1.0, 1.4) + 10, decay=0.0005, f_lo=1500, f_hi=6000, size_var=0.7)), sd * 0.6, 0.15)
    for k, at in enumerate((sd * 0.75, sd * 0.75 + 0.07, sd * 0.75 + 0.12)):
        f = 2400.0 * rng.uniform(0.9, 1.15)
        m.add(nrm(ship.iron_ring(0.12, rng, [f], t60=0.05, gains=[1.0])), at, 0.25 * 0.75 ** k)
    sp = sd + 0.15
    m.add(nrm(works.burst(0.04, rng, 300, 3200, 0.008)), sp, 0.35)
    m.add(tf.thud(0.1, rng, f=170, decay=0.012, tone=0.1), sp, 0.4)
    return road(lp(fit(m.out(), ns(dur)), 9500), 0.05, 8341 + v)


@effect("wagon_brake", 2, "Wagon brake squeals", reach=24, loud=-17.0, max_len=1.6, fade_out=0.3, limit=4.0,
        when=("Part 3 (D3): the carrier's wagon (the led wagon, and a House carter's) braking to a stop: the lever hauled and the "
              "shoe on the tyre."))
def wagon_brake(v, rng):
    """the carrier's wagon braked as it comes to a stop: the lever hauled back (its pawl clacking over three teeth), the wooden
    shoe pressed against the iron tyre (a scrape of grit, then a squeal that wavers and drops as the wheel slows), the wagon rocking
    on its axles with a groan and a last knock of its boards. Lower, shorter and plainer than the caravans' own (`caravan.brake`):
    a wagon at a walk. v1 a firm stop, v2 a slow one with a longer squeal"""
    dur = 1.2
    sd = [0.45, 0.6][v]
    m = Mix(dur)
    for i in range(3):
        at = 0.07 * i
        m.add(nrm(ship.iron_ring(0.1, rng, [rng.uniform(1500.0, 1800.0)], t60=0.04, gains=[1.0])), at, 0.25)
        m.add(nrm(ship.oak(0.1, rng, 420.0, t60=0.03, hard=0.7)), at, 0.18)
        m.add(works.tick(rng, 2000.0, 9000.0, 0.0005, 0.004), at, 0.25)
    s0 = 0.24
    t = tvec(sd)
    u = t / sd
    f0 = [800.0, 720.0][v] * ship.spread(sd, rng, 6.0, 0.03) * (1 - 0.1 * u)
    chatter = 0.55 + 0.45 * np.sin(phase_from_freq(23.0 * ship.spread(sd, rng, 3.0, 0.2), len(t))) ** 2
    sq = ship.squeal(sd, rng, f0, np.clip(t / 0.08, 0, 1) * (1 - 0.75 * u) ** 1.3 * chatter, harmonics=5, tilt=1.1, chatter=0.4,
                     wobble=0.015, grit=0.08, peak_hz=1600.0)
    m.add(nrm(sq), s0 + 0.03, 0.55)
    scrape = bp(colored(sd, rng, -1.0), 500, 4500) * np.clip(t / 0.02, 0, 1) * np.exp(-t / 0.3)
    m.add(nrm(scrape + 0.5 * ship.grain_cloud(sd, rng, 2200 * np.exp(-t / 0.28) + 40, decay=0.0007, f_lo=700, f_hi=4500)), s0, 0.4)
    gd = 0.55
    m.add(nrm(ship.creak_voice(gd, rng, ship.path([(0, 30), (gd * 0.5, 70), (gd, 35)], gd), bell(gd, 0.4, 1.3, 1.5),
                               ship.timber_modes(rng, 100.0, 1000.0, 14, 0.09, 0.03, peaks=(250.0, 560.0)))), s0 + 0.25, 0.4)
    kn = s0 + sd + 0.12
    m.add(nrm(ship.oak(0.3, rng, 210.0, t60=0.08, hard=0.7)), kn, 0.45)
    m.add(tf.thud(0.2, rng, f=88, decay=0.04, tone=0.4), kn, 0.45)
    return road(lp(fit(m.out(), ns(dur)), 9500), 0.06, 8351 + v)


# =========================================================================== the Hunt
def war_spectrum(f):
    """what each harmonic of a great horn (an ox horn bound with a long wooden bore, a metre and a half of it) radiates, in dB: its
    bore lets little of the lowest harmonics out (the fundamental least of all: the ear hears the low pitch from the ones above
    it), its bell sings round 750 Hz with a rough lift near 1.8 kHz, and little escapes above 4 kHz"""
    f = np.maximum(f, 1.0)
    lf = np.log2(f / 750.0)
    e = -2.6 * lf ** 2 + 3.0 * np.exp(-(np.log2(f / 1800.0) / 0.3) ** 2)
    e -= 8.0 * np.clip((190.0 - f) / 120.0, 0, 1)
    e -= 12.0 * np.clip(np.log2(f / 3500.0), 0, None)
    return e


def war_horn(dur, rng, f_curve, pres, rasp=0.14, wob=1.0):
    """one call on a great horn: the lips buzzing into it at f_curve (Hz per sample), the player's breath pressure `pres` (0 to 1
    per sample) sets how loud and how bright it is (each harmonic comes up faster than the one under it as it is blown harder),
    the pitch wavering a little as a long breath does and the lips' own rasp in it, a breath of air round it, driven a little for
    the blare of a horn blown for all it is worth"""
    n = ns(dur)
    t = tvec(dur)
    wv = 1 + wob * (0.004 * (2 * smooth_random(dur, rng, 4) - 1) + 0.0025 * np.sin(2 * np.pi * rng.uniform(4.8, 5.8) * t + rng.uniform(0, 6.3)))
    f = f_curve * wv
    ph = phase_from_freq(f, n)
    y = np.zeros(n)
    for k in range(1, 80):
        fk = k * f
        if float(np.min(fk)) > 7000.0:
            break
        a = db(war_spectrum(fk)) * pres ** (1.0 + 0.2 * (k - 1)) * np.clip((8000.0 - fk) / 1500.0, 0, 1)
        y += a * np.sin(k * ph + rng.uniform(0, 2 * np.pi))
    y = nrm(y)
    buzz = bp(white(dur, rng), 300, 3000) * (0.5 + 0.5 * np.cos(ph)) ** 6 * pres ** 2
    air = bp(white(dur, rng), 1200, 6000) * pres
    out = y + rasp * nrm(buzz) + 0.03 * nrm(air)
    return drive(nrm(out), 1.8) * np.clip(pres * 4.0, 0, 1)


def horn_notes(dur, plan, rng, attack=0.1):
    """a line of notes on the horn from `plan`, [(start s, length s, pitch Hz, how hard 0..1)]: the pitch kept in each and
    slurred from one to the next over a tenth of a second (a lip slur between two of the horn's partials), the breath swelling
    and easing in each, and the pitch sagging a little as it runs out"""
    n = ns(dur)
    t = tvec(dur)
    f = np.full(n, plan[0][2], dtype=float)
    pres = np.zeros(n)
    for i, (at, ln, pitch, hard) in enumerate(plan):
        if i:
            glide = 0.5 - 0.5 * np.cos(np.pi * np.clip((t - at) / 0.11, 0, 1))
            f = f * (1 - glide) + pitch * glide
        u = np.clip((t - at) / ln, 0, 1)
        on = np.clip((t - at) / attack, 0, 1) ** 1.3 * (t >= at)
        off = np.clip((at + ln - t) / 0.12, 0, 1)
        sw = 0.82 + 0.18 * np.sin(np.pi * np.clip(u * 1.2, 0, 1))
        pres = np.maximum(pres, hard * on * off * sw * ((t >= at) & (t < at + ln)))
        sag = np.clip((t - (at + ln - 0.25)) / 0.25, 0, 1) * ((t >= at) & (t < at + ln))
        f = f * (1 - 0.04 * sag ** 2)
    return war_horn(dur, rng, f, np.clip(pres, 0, 1))


@effect("hunt_horn", 2, "Horn sounds on the hill", reach=80, loud=-13.0, max_len=5.3, fade_out=1.0, tail_db=-70.0, limit=4.0,
        when=("Part 4 (E, the Hunt): you are on the road with a pack worth 25 Marks or more within 160 blocks of a camp whose band is "
              "free to strike, and their lookout has seen you: the horn winds from the camp's side of the hills, six to ten seconds "
              "before the party comes into being. Positional, heard to 80 blocks."))
def hunt_horn(v, rng):
    """the outlaws' lookout winding a great horn from the hillside: a low, rising call that reads as a threat from far off. A long
    low note, blown hard and rough, then slurred up a fifth and held, swelling, and up another fourth to the last, the breath
    running out and the pitch sagging at the end; then the valley giving it back, the far slope's echo coming a little duller and
    fainter, twice, and a long tail over the hills. v1 three notes (G, D, G, an octave up in all), v2 a shorter call that climbs a
    fifth and ends in a crack up to the octave, as a hurried blower's does"""
    if v == 0:
        dur = 3.7
        plan = [(0.0, 1.0, 98.0, 0.85), (1.1, 1.0, 146.8, 0.95), (2.2, 1.45, 196.0, 1.0)]
    else:
        dur = 3.1
        plan = [(0.0, 0.7, 110.0, 0.8), (0.82, 1.55, 164.8, 0.95), (2.45, 0.6, 220.0, 1.0)]
    y = horn_notes(dur, plan, rng)
    return valley(hp(y, 40.0), wet=0.28, seed=8401 + v)


def vox(dur, rng, f0, vowels, scale=1.0, curve=None, breath=0.3, jitter=0.03, shimmer=0.15, subharm=0.0, fry=0.0, drive_=1.6):
    """a man's voice, unspoken: the throat's buzz at f0 (a number or a curve) through the formants of the vowels, opening from the
    first to the second along `curve`, driven for roughness"""
    src = glottal(dur, f0, rng, jitter=jitter, shimmer=shimmer, subharm=subharm, breath=breath, fry=fry)
    y = formants(src, vowels[0], vowels[1], scale=scale, curve=curve)
    return drive(nrm(y), drive_)


def aspirate(rng, dur=0.05, lo=700.0, hi=4500.0):
    """the breath of an 'h' before a vowel"""
    return nrm(bp(white(dur, rng), lo, hi) * bell(dur, 0.75, 1.0, 1.0))


@effect("hunt_shout", 3, "Outlaw shouts", reach=32, loud=-15.0, max_len=[0.7, 1.4, 1.3], fade_out=0.3, limit=4.0,
        when=("Part 4 (E): a band's party shouting as it comes down the hill toward a laden traveller, and in the fight; the "
              "voiced lines ('Hold there!') are the voice stream's, these are the wordless calls between them. The code picks "
              "one of the three."))
def hunt_shout(v, rng):
    """an outlaw's wordless shout, a man's: v1 a bark, 'hah!', the breath thrown out hard and the voice cracking down from high to
    low, with a short rough 'uh' after it; v2 a laugh, four or five short 'heh's, each a little lower and weaker than the last,
    gruff and catching; v3 a snarl, a growl down in the chest that swells and rasps, the lips drawn back (a hiss of breath through the
    teeth) and breaking off"""
    if v == 0:
        dur = 0.7
        t = tvec(dur)
        f0 = 112.0 * rng.uniform(0.97, 1.03)
        fc = f0 * (1 + 0.36 * np.exp(-t / 0.1))
        ba = 0.34
        bt = tvec(ba)
        bark = vox(ba, rng, fc[:ns(ba)], ("a", "uh"), scale=1.0, curve=np.clip(bt / 0.25, 0, 1), breath=0.4, jitter=0.04,
                   shimmer=0.2, subharm=0.15, drive_=2.2)
        bark = bark * np.clip(bt / 0.008, 0, 1) * np.exp(-np.maximum(0, bt - 0.07) / 0.13) * np.clip((ba - bt) / 0.05, 0, 1)
        m = Mix(dur)
        m.add(aspirate(rng, 0.045, 600.0, 4500.0), 0.0, 0.3)
        m.add(nrm(bark), 0.04, 1.0)
        ud = 0.16
        uh = vox(ud, rng, f0 * 0.85 * (1 - 0.2 * tvec(ud) / ud), ("uh", "uh"), breath=0.3, subharm=0.2, drive_=1.8)
        m.add(nrm(uh) * bell(ud, 0.3, 1.2, 1.5), 0.3, 0.3)
        y = fit(m.out(), ns(dur))
    elif v == 1:
        dur = 1.4
        m = Mix(dur)
        f0 = 138.0 * rng.uniform(0.97, 1.03)
        k = 5
        at = 0.0
        for i in range(k):
            d = 0.12 + 0.01 * i
            g = 0.9 ** i
            ff = f0 * (0.93 ** i)
            pd = tvec(d)
            seg = vox(d, rng, ff * (1 + 0.12 * np.exp(-pd / 0.04)), ("e", "uh"), scale=1.0, curve=np.clip(pd / d, 0, 1), breath=0.4,
                      jitter=0.05, shimmer=0.25, subharm=0.2, fry=0.1, drive_=1.8)
            seg = nrm(seg) * np.clip(pd / 0.01, 0, 1) * np.exp(-pd / 0.07)
            m.add(aspirate(rng, 0.04, 800.0, 4500.0), at, 0.28 * g)
            m.add(seg, at + 0.035, g)
            at += 0.19 + rng.uniform(-0.015, 0.02) + 0.012 * i
        y = fit(m.out(), ns(dur))
    else:
        dur = 1.3
        t = tvec(dur)
        u = t / dur
        f0 = (90.0 - 18.0 * u) * (1 + 0.04 * (2 * smooth_random(dur, rng, 5) - 1))
        gr = vox(dur, rng, f0, ("a", "o"), scale=0.96, curve=np.clip(u * 1.3, 0, 1), breath=0.2, jitter=0.07, shimmer=0.3, subharm=0.55,
                 fry=0.3, drive_=2.4)
        rough = 1 - 0.45 * (0.5 + 0.5 * np.sin(phase_from_freq(np.full(len(t), 31.0), len(t)) + rng.uniform(0, 6.3)))
        env = np.clip(t / 0.22, 0, 1) ** 1.3 * np.clip((dur - t) / 0.18, 0, 1) * (0.85 + 0.15 * smooth_random(dur, rng, 6))
        teeth = bp(white(dur, rng), 2000, 6500) * env * (0.4 + 0.6 * smooth_random(dur, rng, 14)) * np.clip((u - 0.15) / 0.2, 0, 1)
        y = nrm(gr) * rough * env + 0.1 * nrm(teeth) + 0.15 * nrm(lp(brown(dur, rng), 160.0, 4)) * env
    return road(lp(y, 9000), 0.07, 8411 + v)


def brass_note(dur, rng, f, bright=1.0, attack=0.035, release=0.12, swell=0.0, vib=0.0):
    """one note on a natural trumpet: the lips buzzing into the bore at f, the harmonics of a brass tone coming up faster than the
    one under them as it is blown harder (each one's level goes with the breath to a higher power), a flutter of lip at the
    start (a chiff), the breath swelling over a long note, no vibrato to speak of"""
    n = ns(dur)
    t = tvec(dur)
    u = t / dur
    fr = f * (1 + 0.012 * np.exp(-t / 0.03)) * (1 + vib * np.sin(2 * np.pi * 5.5 * t) + 0.002 * (2 * smooth_random(dur, rng, 5) - 1))
    ph = phase_from_freq(fr, n)
    pres = np.clip(t / attack, 0, 1) ** 1.2 * np.clip((dur - t) / release, 0, 1) * (1 + swell * np.sin(np.pi * np.clip(u, 0, 1)) - swell * 0.5)
    pres = np.clip(pres, 0, 1)
    y = np.zeros(n)
    for k in range(1, 16):
        if k * f > 9000.0:
            break
        a = k ** -0.85 * pres ** (0.4 + 0.45 * bright * (k - 1)) * (1.0 + 0.6 * np.exp(-((np.log2(k * f / 1500.0)) / 0.5) ** 2))
        y += a * np.sin(k * ph + rng.uniform(0, 2 * np.pi))
    chiff = bp(white(dur, rng), 900, 5000) * np.exp(-t / 0.018) * np.clip(t / 0.002, 0, 1)
    return nrm(nrm(y) + 0.12 * nrm(chiff)) * np.clip(pres * 3.0, 0, 1)


@effect("hunt_win", 2, "Fanfare sounds", reach=32, loud=-15.0, max_len=2.6, fade_out=0.5, limit=4.0,
        when=("Part 4 (E): the party beaten (every member down, or the leader down and the rest fled): the bar clears and the town's "
              "gate guards hear of it. Played to the player and those about them; the Raids' VILLAGER_CELEBRATE is the fallback "
              "where this has no file."))
def hunt_win(v, rng):
    """a short rising fanfare for the road won back, on two natural trumpets a third apart: three quick notes climbing the horn's
    own partials and a fourth, long and swelling, held to the end; the second trumpet a little behind the first. v1 climbs G, C, E, G,
    v2 starts a step lower with a turn, C, E, G, C"""
    dur = 2.3
    base = [392.0, 349.2][v]
    steps = [(0.0, 0.17, 1.0), (0.2, 0.17, 4 / 3.0), (0.4, 0.19, 5 / 3.0), (0.62, 1.2, 2.0)] if v == 0 else \
        [(0.0, 0.15, 1.0), (0.17, 0.15, 9 / 8.0), (0.36, 0.17, 1.0), (0.55, 0.19, 4 / 3.0), (0.78, 1.15, 5 / 3.0)]
    m = Mix(dur)
    for at, ln, r in steps:
        f = base * r * rng.uniform(0.998, 1.002)
        long = ln > 0.5
        m.add(brass_note(ln + 0.05, rng, f, bright=1.0, swell=0.3 if long else 0.0, vib=0.003 if long else 0.0), at, 1.0)
        m.add(brass_note(ln + 0.05, rng, f * 0.8 * rng.uniform(0.999, 1.001), bright=0.8, swell=0.3 if long else 0.0, vib=0.003 if long else 0.0),
              at + 0.012, 0.6)
    y = fit(m.out(), ns(dur))
    return reverb(y, wet=0.16, rt60=1.1, predelay=0.02, hf_damp=0.6, seed=8421 + v, early_span=0.05)


@effect("sack_lift", 2, "Sack is heaved up", reach=16, loud=-20.0, max_len=1.0, fade_out=0.2, limit=4.0,
        when=("Part 4 (E): the outlaws taking their share (a sack for each outlaw, carried in the off hand) and the sacks of what "
              "spilled being picked up again; a sack heaved up and swung to the shoulder."))
def sack_lift(v, rng):
    """a full sack heaved off the ground and swung up: the coarse sacking gripped and dragged free of what it lay on (a rough
    rasp of hemp and grit), the weight shifting inside as it swings (v1 grain running with a hush, v2 hard goods, iron and
    pots, clinking), a dull thud as it lands on the shoulder and the sacking's rustle as it settles"""
    dur = 1.0
    m = Mix(dur)
    gd = 0.22
    m.add(works.cloth(gd, rng, env_points([(0.0, 0.2), (0.08, 1.0), (gd, 0.0)], gd), lo=300, hi=3800, weave=0.8), 0.0, 0.5)
    m.add(works.scuff(0.14, rng, 400, 3200, 1.2), 0.0, 0.35)
    sw = 0.3
    t = tvec(sw)
    if v == 0:
        gr = ship.grain_cloud(sw, rng, 1500 * bell(sw, 0.4, 1.2, 1.5) + 20, decay=0.0005, f_lo=1500, f_hi=6500, size_var=0.5)
        m.add(nrm(gr), 0.14, 0.4)
        m.add(nrm(bp(colored(sw, rng, -2.0), 300, 2500) * bell(sw, 0.4, 1.2, 1.5)), 0.14, 0.3)
    else:
        for at, f0 in ((0.17, 1700.0), (0.24, 2300.0), (0.31, 1300.0)):
            m.add(nrm(ping(0.12, [f0 * rng.uniform(0.95, 1.05), f0 * 2.4, f0 * 4.1], [0.05, 0.03, 0.02], [1, .5, .3], rng)), at, 0.25)
        m.add(nrm(ship.iron_ring(0.3, rng, [820.0], t60=0.1, gains=[1.0])), 0.18, 0.15)
        m.add(nrm(bp(colored(sw, rng, -2.0), 300, 2500) * bell(sw, 0.4, 1.2, 1.5)), 0.14, 0.2)
    sh = 0.42
    m.add(tf.thud(0.2, rng, f=105, decay=0.035, tone=0.3), sh, 0.9)
    m.add(works.burst(0.05, rng, 250, 2600, 0.012), sh, 0.4)
    m.add(tf.rustle(0.3, rng, peak=0.25, density=450, lo=600, hi=5000), sh + 0.02, 0.3)
    return square(lp(fit(m.out(), ns(dur)), 9500), 0.05, 8431 + v)


@effect("guard_step", 3, "Spear butt strikes stone", reach=16, loud=-21.0, max_len=0.6, fade_out=0.12, limit=5.0,
        when=("Part 4 (E, and the hired guard): a guard's step as he walks with his spear: the butt planted on the stone as the "
              "boot comes down; the town's gate guards on post, and a hired guard on the road's paving."))
def guard_step(v, rng):
    """a guard's spear planted on the paving as he steps: the iron shoe of the butt meeting stone (a hard, dry 'tak' with a tick
    of iron), the long ash shaft taking the knock and ringing down its length (a hollow, dying 'tonk' near 200 Hz), and the boot
    coming down with it (a dull scuff and thud). v1 a plain step, v2 a heavy one with the spearhead ringing faintly far above, v3 a
    lighter one on grit"""
    dur = 0.6
    m = Mix(dur)
    st = [1.0, 1.15, 0.8][v]
    m.add(works.stone(0.05, rng, [1900.0, 1700.0, 2100.0][v], t60=0.012), 0.0, 0.7 * st)
    m.add(nrm(ping(0.07, [2700.0 * rng.uniform(0.96, 1.04), 4300.0, 6600.0], [0.015, 0.01, 0.006], [1, .5, .3], rng)), 0.0, 0.35 * st)
    m.add(hs.pole(rng, [205.0, 190.0, 225.0][v] * rng.uniform(0.98, 1.02), t60=0.11), 0.0, 0.6 * st)
    m.add(tf.thud(0.12, rng, f=125, decay=0.02, tone=0.15), 0.008, 0.55 * st)
    m.add(works.scuff(0.08, rng, 300, 2600, [0.6, 0.8, 1.4][v]), 0.0, 0.2)
    if v == 1:
        m.add(nrm(ship.iron_ring(0.5, rng, [3100.0], t60=0.25, gains=[1.0])), 0.002, 0.08)
    return square(fit(m.out(), ns(dur)), 0.07, 8441 + v)


# =========================================================================== the ladder
@effect("warrant_seal", 2, "Warrant is sealed", reach=16, loud=-16.0, max_len=1.1, fade_out=0.25, limit=4.0,
        when=("Part 5 (the Warrant scene, WarrantScene): the hall's merchant warms red wax at the lamp, drips it on the scroll and "
              "presses the hall's seal: a thump, a puff, the wax squashing and settling; then the ribbon is tied and the "
              "Warrant held out. Played where the merchant stands, at the moment the seal comes down."))
def warrant_seal(v, rng):
    """the hall's great seal pressed into a pool of red wax on a scroll lying on the oak counter, with both hands: the brass
    face meeting the wax (a tick of brass in its boxwood grip, killed at once), the weight coming down on the counter (a hollow
    thump), the air squeezed out from under the paper (a soft puff), the wax squashing out sideways, a long wet push that
    sinks to a stop, the seal lifted with a tacky peel and a small pop, and the scroll rustling as the merchant slides it
    straight. v1 one firm press, v2 rocked down (the heel of the seal first, then all of it), a second thump a moment after"""
    dur = 1.1
    presses = [[(0.0, 1.0)], [(0.0, 0.7), (0.085, 1.0)]][v]
    m = Mix(dur)
    for at, g in presses:
        m.add(nrm(ping(0.06, [2500.0 * rng.uniform(0.97, 1.03), 3900.0, 5900.0], [0.01, 0.007, 0.005], [1, .6, .35], rng)), at, 0.22 * g)
        m.add(nrm(ship.oak(0.3, rng, 150.0 * rng.uniform(0.95, 1.05), t60=0.12, hard=0.6, lo=100.0)), at, 0.7 * g)
        m.add(tf.thud(0.2, rng, f=95, decay=0.04, tone=0.4), at, 0.95 * g)
    last = presses[-1][0]
    pf = 0.18
    pt = tvec(pf)
    m.add(nrm(lp(white(pf, rng), 2800) * np.clip(pt / 0.005, 0, 1) * np.exp(-pt / 0.05)), last + 0.004, 0.32)
    sd = 0.36
    t = tvec(sd)
    push = tvf(colored(sd, rng, -2.0), "bp", 720 * np.exp(-t / 0.11) + 170, 1.5) * np.clip(t / 0.01, 0, 1) * np.exp(-t / 0.15)
    m.add(nrm(push), last + 0.01, 0.5)
    m.add(nrm(crackle(0.2, rng, 28 * bell(0.2, 0.3, 1.0, 1.4) + 4, decay=0.0006, f_lo=800, f_hi=3500, size_var=0.8)), last + 0.14, 0.12)
    lift = last + 0.5
    pop_t = tvec(0.012)
    m.add(nrm(sine_sweep(0.012, 420, 1300, 2.0) * np.exp(-pop_t / 0.004)), lift, 0.3)
    m.add(nrm(crackle(0.09, rng, 520 * bell(0.09, 0.5, 1.2, 1.2) + 20, decay=0.0006, f_lo=1000, f_hi=5000)), lift - 0.07, 0.22)
    m.add(tf.paper_flap(0.12, rng, size=0.8), lift + 0.07, 0.22)
    m.add(tf.rustle(0.3, rng, peak=0.35, density=300, lo=500, hi=4500), lift + 0.1, 0.22)
    return hall(lp(fit(m.out(), ns(dur)), 7000), 0.07, 8501 + v)


@effect("store_key", 2, "Key turns in a lock", reach=24, loud=-18.5, max_len=[1.35, 1.8], fade_out=0.3, limit=5.0,
        when=("Part 5 (F2, the Carriers' Store's first use): when the last block is laid the town's clerk takes the key from the "
              "master builder and unlocks the door: a heavy key sound, then the hoist lifts the first crate in. Played at the "
              "Store's door."))
def store_key(v, rng):
    """a big iron key put in the Store's door lock and turned: the key sliding into the keyway (a scrape of iron, its tip
    finding the wards with a faint tick), the wards lifting one after the other as it turns (a run of small, dry clicks), and
    the bolt drawn back (a heavy clunk of iron on iron, ringing a little, the oak of the door and its lock-case knocking under
    it), the key drawn out again with a jingle of the ring it hangs on. v1 a single bolt, the key out; v2 a lock of two bolts,
    two turns, each with its clicks, and the ring of other keys on the clerk's belt swinging against the door"""
    dur = [1.35, 1.8][v]
    m = Mix(dur)
    m.add(works.scuff(0.12, rng, 500, 4200, 0.9), 0.0, 0.3)
    m.add(nrm(ping(0.05, [2200.0 * rng.uniform(0.96, 1.04), 3600.0, 5600.0], [0.012, 0.008, 0.005], [1, .6, .3], rng)), 0.1, 0.22)
    turns = [0.24] if v == 0 else [0.24, 0.84]
    for k, at in enumerate(turns):
        t = at
        for _ in range(6):
            t += 0.026 * rng.uniform(0.8, 1.3)
            f = float(np.exp(rng.uniform(np.log(1900.0), np.log(3400.0))))
            m.add(nrm(ping(0.03, [f, f * 1.7, f * 2.6], [0.006, 0.004, 0.003], [1, .5, .25], rng)), t, rng.uniform(0.2, 0.4))
        bolt = t + 0.1
        m.add(nrm(ship.iron_ring(0.5, rng, [560.0 * rng.uniform(0.96, 1.04), 860.0], t60=0.16, gains=[1.0, 0.6])), bolt, 0.65)
        m.add(works.burst(0.04, rng, 400, 3500, 0.01), bolt, 0.4)
        m.add(tf.thud(0.2, rng, f=140, decay=0.025, tone=0.25), bolt, 0.65)
        m.add(works.wood_box(0.3, rng, 220 * rng.uniform(0.96, 1.04), t60=0.07, hollow=0.2), bolt, 0.5)
        m.add(nrm(ping(0.04, [2700.0, 4300.0], [0.01, 0.006], [1, .5], rng)), bolt + 0.04, 0.15)
    out = turns[-1] + 0.4
    m.add(works.scuff(0.1, rng, 500, 3800, 0.8), out, 0.22)
    m.add(chain_links(rng, count=[4, 8][v], f_lo=1800.0, f_hi=3600.0, span=0.2, decay=0.5, size=0.9), out + 0.04, 0.25)
    return square(lp(fit(m.out(), ns(dur)), 9000), 0.09, 8511 + v)


@effect("scale_settle", 2, "Scale beam settles", reach=12, loud=-22.0, max_len=[1.5, 1.9], fade_out=0.35, limit=4.0,
        when=("Part 5 (F2, the Store's scale, StoreScale): the clerk weighs a sack now and then (WEIGH): the beam tips, settles "
              "and the pans sway to rest (a damped swing of 40 ticks). Played at the scale when the beam comes to rest."))
def scale_settle(v, rng):
    """the Store's balance: the beam turning on its pin as the sack goes on the pan (a soft, low creak of wood on iron), the heavier
    end coming down against its stop (a quiet wooden 'tock' with the chains of the pans shivering), and the beam rocking back
    and forth about its level, each swing less than the last and the pans' chains ticking at its ends, till it hangs still. v1 a heavy
    sack, one deep swing and two small ones; v2 a light one, a fine balance, rocking about level five times"""
    dur = [1.5, 1.9][v]
    m = Mix(dur)
    td = 0.3
    cr = tf.creak(td, rng, 20, 60, [650.0, 1050.0, 1700.0], t60=0.03) * bell(td, 0.5, 1.2, 1.5)
    m.add(nrm(cr), 0.0, 0.3 if v == 0 else 0.2)
    hits = [(0.28, 1.0), (0.66, 0.42), (0.96, 0.2)] if v == 0 else [(0.3, 0.5), (0.66, 0.38), (1.02, 0.28), (1.36, 0.17), (1.66, 0.09)]
    for k, (at, a) in enumerate(hits):
        at = at + rng.uniform(-0.01, 0.01)
        m.add(nrm(ship.oak(0.15, rng, 380.0 * rng.uniform(0.96, 1.04), t60=0.04, hard=0.5 * a + 0.1, lo=200.0)), at, 0.7 * a + 0.1)
        m.add(tf.thud(0.1, rng, f=170, decay=0.015, tone=0.2), at, 0.5 * a)
        m.add(chain_links(rng, count=int(3 + 6 * a), f_lo=1800.0, f_hi=3800.0, span=0.12, decay=0.5, size=0.8), at, 0.5 * a + 0.1)
        m.add(nrm(ping(0.35, [1900.0 * rng.uniform(0.97, 1.03), 3300.0, 5100.0], [0.14, 0.08, 0.05], [1, .5, .3], rng)), at, 0.12 * a)
        if k and v == 1:
            m.add(nrm(tf.creak(0.1, rng, 18, 40, [700.0, 1200.0], t60=0.025)), at - 0.14, 0.12 * a + 0.06)
    return hall(lp(fit(m.out(), ns(dur)), 9500), 0.06, 8521 + v)


alias("hoist_creak", "works.hoist", "Hoist rope creaks", reach=24,
      when=("Part 5 (F2, the Store): the hoist beam over the Store's door lifts and lowers a crate when anyone deposits or takes "
            "goods; the granary's own hoist (`works.hoist`) plays, drawn by the same WorksHoistRenderer. No file of its own: its "
            "sounds.json entry is an event that points at works.hoist."))


def coin_in_chest(rng, f0, gain=1.0, on_coins=True):
    """a Mark falling into the strongbox: its ring, cut short where it lands on the coins already there, and the iron-bound
    box's own hollow knock, lower than the works' post box"""
    m = Mix(0.4)
    m.add(tf.coin(0.35, rng, f0=f0, t60=0.12 if on_coins else 0.06, bright=0.7, click=0.4), 0.0, gain)
    if on_coins:
        m.add(tf.coin(0.2, rng, f0=f0 * rng.uniform(0.88, 1.12), t60=0.05, bright=0.4, click=0.2), 0.007, 0.35 * gain)
    m.add(works.wood_box(0.18, rng, 330 * rng.uniform(0.97, 1.03), t60=0.04, hollow=0.5), 0.0, 0.3 * gain)
    return m.out()


@effect("strongbox", 2, "Strongbox lid and coins", reach=16, loud=-19.0, max_len=[1.9, 2.2], fade_out=0.35, limit=4.0,
        when=("Part 6 (G, the Counting House's yard, YardShow): when a wagon is back the bell rings, the strongbox lid lifts as the "
              "scribe pays in (coins into it, a clink) and the beads fly. Played at the box: the lid up, the coins, the lid shut."))
def strongbox(v, rng):
    """the Counting House's iron-bound strongbox opened, paid into and shut: the hasp flicked up (a small clink of iron), the lid
    going up on its hinges (a short, high groan of iron on iron) and meeting its stay (a hollow knock), the Marks dropped in
    (each a bright ring, dying where it lands on the ones already there, the box knocking hollow under them), and the lid let fall
    (a deep, short thump of oak, the hasp rattling in its staple). v1 five Marks counted in one at a time, v2 a purseful poured
    in, with a jingle of the purse's mouth"""
    dur = [1.9, 2.2][v]
    m = Mix(dur)
    m.add(nrm(ping(0.1, [1700.0, 2750.0 * rng.uniform(0.97, 1.03), 4200.0], [0.05, 0.03, 0.02], [1, .5, .3], rng)), 0.0, 0.25)
    m.add(works.hinge_groan(0.32, rng, 45, 95, 1.9), 0.05, 0.35)
    m.add(works.wood_box(0.2, rng, 300.0, t60=0.04, hollow=0.3), 0.4, 0.35)
    if v == 0:
        times = [0.55, 0.78, 0.96, 1.19, 1.33]
    else:
        times = list(0.55 + np.cumsum([0.0, 0.035, 0.03, 0.04, 0.025, 0.03, 0.035, 0.03, 0.04, 0.05, 0.045, 0.05]))
    for i, at in enumerate(times):
        m.add(coin_in_chest(rng, rng.uniform(2800.0, 3900.0), gain=rng.uniform(0.55, 1.0), on_coins=i > 0 or v != 0),
              float(at), 1.0 if i == 0 else 0.8)
    if v == 1:
        m.add(jingle(0.45, rng, 10, 3000, 8000, t60=(0.01, 0.04), start=0.04, amp_decay=0.15), 0.55, 0.2)
    shut = times[-1] + 0.3
    m.add(works.door_boom(rng, 2.0, 0.55), shut, 0.8)
    m.add(nrm(ping(0.15, [1700.0, 2750.0, 4200.0], [0.06, 0.04, 0.025], [1, .5, .3], rng)), shut + 0.03, 0.2)
    m.add(chain_links(rng, count=4, f_lo=1700.0, f_hi=3200.0, span=0.12, decay=0.5, size=0.9), shut + 0.04, 0.2)
    return hall(lp(fit(m.out(), ns(dur)), 9000), 0.07, 8541 + v)


def bead(rng, g=1.0):
    """one wooden bead knocking the next on its wire: a hard, hollow, short 'tk' of boxwood, the wire's thin ring behind it"""
    f = float(np.exp(rng.uniform(np.log(1700.0), np.log(2800.0))))
    k = tf.knock(0.03, [f, f * 1.9, f * 3.1], [0.006, 0.004, 0.003], [1, .6, .3], rng, lo=800, hi=9000, ex_len=0.0004)
    w = ping(0.08, [rng.uniform(4200.0, 5400.0), rng.uniform(6800.0, 7600.0)], [0.03, 0.02], [1, .5], rng)
    return nrm(k) + 0.12 * fit(nrm(w), len(k))


def bead_row(m, rng, at, count, gain=1.0, pace=0.02):
    """a row of beads pushed along its wire with one finger: they knock one against the next at a quickening beat, the last
    against the frame"""
    t = at
    for i in range(count):
        t += pace * rng.uniform(0.7, 1.3) * (1.1 - 0.5 * i / count)
        m.add(bead(rng), t, gain * rng.uniform(0.4, 1.0))
    m.add(works.wood_box(0.12, rng, 560.0 * rng.uniform(0.95, 1.05), t60=0.025, hollow=0.3), t + 0.012, 0.4 * gain)
    return t


@effect("beads", 3, "Counting beads slide", reach=12, loud=-22.0, max_len=[0.55, 0.85, 0.75], fade_out=0.15, limit=5.0,
        when=("Part 6 (G, the Counting House's scribe, YardShow): the beads of the counting frame slide as the day's sums are made, "
              "a row at a time, when a wagon is back and while the scribe counts; the code picks one of the three."))
def beads(v, rng):
    """the scribe's counting frame: the beads shoved along their wires, the knocking of one boxwood bead on the next in a
    quickening run and the last of the row against the frame (a deeper, hollow knock). v1 one row pushed across, v2 two rows,
    one back and one forward, a breath apart, v3 three rows flicked across, almost together (the beads fly)"""
    dur = [0.55, 0.85, 0.75][v]
    m = Mix(dur)
    if v == 0:
        bead_row(m, rng, 0.0, 9)
    elif v == 1:
        e = bead_row(m, rng, 0.0, 7, 1.0, 0.018)
        bead_row(m, rng, e + 0.12, 8, 0.85, 0.016)
    else:
        bead_row(m, rng, 0.0, 6, 1.0, 0.015)
        bead_row(m, rng, 0.07, 8, 0.85, 0.014)
        bead_row(m, rng, 0.15, 5, 0.75, 0.016)
        bead_row(m, rng, 0.3, 7, 0.6, 0.015)
    return hall(lp(fit(m.out(), ns(dur)), 10000), 0.05, 8551 + v)


@effect("house_bell", 2, "Bell rings over the door", reach=32, loud=-15.0, max_len=3.4, fade_out=0.9, tail_db=-62.0, limit=4.0,
        when=("Part 6 (G, the Counting House's yard, YardShow): the bell over the door rings when one of your wagons is back. Heard "
              "across the yard and the street (32 blocks)."))
def house_bell(v, rng):
    """the bronze bell on its bracket over the Counting House's door, rung by its rope when a wagon is back: a bigger, lower bell
    than the hand's (strike note a D), each pull of the rope throwing the clapper against one side (an iron 'tik' and the bell's
    hum and prime ringing out, the high modes dying first), the bell's bracket knocking its wall, the strokes landing on a bell
    still ringing from the last. v1 three slow pulls, v2 five quicker ones, the last two weaker as the rope is let go"""
    dur = 3.4
    modes = ship.bell_modes(587.3, rng_for("trade", "house_bell"))
    plan = [[(0.0, 1.0), (0.62, 0.9), (1.22, 0.95)], [(0.0, 1.0), (0.42, 0.85), (0.82, 0.95), (1.2, 0.6), (1.6, 0.45)]][v]
    m = Mix(dur)
    for at, hard in plan:
        at = at + rng.uniform(-0.012, 0.012) if at else 0.0
        tik, knock = ship.clapper(rng, hard)
        m.add(ship.bell_blow(dur - at, modes, hard=hard), at, 1.0)
        m.add(tik, at, 0.1 * hard)
        m.add(knock, at, 0.05 * hard)
        m.add(tf.thud(0.1, rng, f=190, decay=0.012, tone=0.1), at, 0.08 * hard)
    return square(lp(fit(m.out(), ns(dur)), 11000), 0.12, 8561 + v)


@effect("carter_hail", 2, "Carter calls out", reach=40, loud=-16.0, max_len=[1.1, 1.0], fade_out=0.3, limit=4.0,
        when=("Part 6 (G, the Counting House's yard, YardShow): a carter calling across the yard as the wagon is loaded and as it "
              "rolls out of the gate, or hailing a wagon coming in; the voiced lines of the House ('Wagon's back!') are the voice "
              "stream's, this is the wordless call that goes with the arm raised."))
def carter_hail(v, rng):
    """a carter's wordless call across a yard, a man's, thrown out the way one calls to a team or a mate at the far gate:
    v1 'ho-oy!', a short low 'ho' and then a long open call going up and falling off, v2 'hey-up!', a high 'hey' held and a short,
    low 'up' cutting it off. The yard's walls give it back a little"""
    dur = 1.15
    f0 = float(rng.uniform(118.0, 132.0))
    m = Mix(dur)
    if v == 0:
        m.add(hs.shout(0.2, rng, f0 * 0.9, ("o", "o"), 1.0, rise=1.15, fall=0.12), 0.0, 0.6)
        m.add(hs.shout(0.62, rng, f0 * 1.25, ("o", "i"), 1.0, rise=1.3, fall=0.4), 0.16, 1.0)
    else:
        m.add(hs.shout(0.34, rng, f0 * 1.3, ("e", "i"), 1.0, rise=1.2, fall=0.12), 0.0, 1.0)
        m.add(hs.shout(0.16, rng, f0 * 0.85, ("uh", "uh"), 1.0, rise=1.0, fall=0.2, breath_h=False), 0.36, 0.7)
    return square(lp(fit(m.out(), ns(dur)), 9000), 0.12, 8571 + v)


@effect("cheer", 2, "Townsfolk cheer", reach=40, loud=-16.0, max_len=2.0, fade_out=0.4, limit=4.0,
        when=("Part 7 (H, ReliefCheer, `trade cheer`): a relief ticket filled or a big delivery made: up to six townsfolk within 24 "
              "blocks turn, walk to within five blocks and cheer, with the villager celebration under it; at most once a game hour a "
              "town, and only with a player within 48 blocks (the command always plays it)."))
def cheer(v, rng):
    """the street cheering the carrier in: a swell of voices more than a shout, one or two starting it and the rest joining
    over half a second (a few more men than women, a child's high voice over them), ragged, the clapping coming in behind, the
    swell broken off a little raggedly as it falls. v1 'hurrah!' from a dozen, v2 'hey!' and 'hurrah!' mixed, with a whistle"""
    dur = 2.0
    m = Mix(dur)
    word = [hs.HURRAH] if v == 0 else [hs.HEY, hs.HURRAH]
    m.add(hs.crowd(dur, rng, word, n=3, start=0.0, spread=0.12, women=0.3), 0.0, 0.9)
    m.add(hs.crowd(dur, rng, word, n=4, start=0.2, spread=0.22, women=0.4), 0.0, 1.0)
    m.add(hs.crowd(dur, rng, word, n=4, start=0.42, spread=0.2, women=0.4, rise=1.4), 0.0, 0.85)
    m.add(hs.crowd(dur, rng, word, n=3, start=0.7, spread=0.25, women=0.4, gain=(0.4, 0.8)), 0.0, 0.6)
    m.add(hs.shout(0.5, rng, float(rng.uniform(380.0, 440.0)), ("a", "a"), 1.3, rise=1.3, fall=0.3), 0.55, 0.35)
    m.add(hs.claps(dur, rng, [12, 14][v], 0.4, 1.4), 0.0, 0.4)
    if v == 1:
        m.add(hs.whistle(0.5, rng, 2250.0, 1.25), 0.85, 0.14)
    return square(lp(fit(m.out(), ns(dur)), 10500), 0.14, 8601 + v)



# =========================================================================== the order of the sounds
# the order of TradeSounds.NAMES (the design's section 10 table): the order of the fragments and of the listen-through
ORDER = ["board_stamp", "seal_press", "ticket_peel", "hourglass", "tally_open", "tally_page", "tally_notch", "report_file",
         "knock", "deliver",
         "bell_step", "bell_trot", "harness_buckle", "whistle_come", "whistle_stay", "pack_clink", "cart_creak", "shafts_lift",
         "shafts_drop", "canvas_flap", "tailboard", "rein_slack", "wagon_brake",
         "hunt_horn", "hunt_shout", "hunt_win", "sack_lift", "guard_step",
         "warrant_seal", "store_key", "scale_settle", "hoist_creak", "strongbox", "beads", "house_bell", "carter_hail",
         "cheer"]

assert sorted(ORDER) == sorted(EVENTS), "every sound is in ORDER, once: " + str(set(ORDER) ^ set(EVENTS))
for _name in ORDER:
    EVENTS[_name] = EVENTS.pop(_name)


# =========================================================================== main
def build(assets, names, wav_dir=None):
    written = []
    for event in names:
        r = EVENTS[event]
        if r["kind"] == "alias":
            print(f"trade.{event:20s} is {r['of']} (no file)")
            continue
        for v in range(r["files"]):
            t0 = time.time()
            name = sound_path(event, v + 1)
            p = os.path.join(assets, "sounds", name + ".ogg")
            if r["kind"] == "loop":
                x = render_loop(event, r)
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
            print(f"{name:26s} {len(y) / SR:6.3f}s  peak {to_db(np.max(np.abs(y))):6.1f}  tp {20 * np.log10(true_peak(y)):6.1f}  "
                  f"rms {to_db(rms(y)):6.1f}  short {float(np.max(loudness_curve(y))):6.1f}  {time.time() - t0:5.2f}s{extra}",
                  flush=True)
            written.append(name)
    return written


def select(only):
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        s = s.removeprefix("trade.")
        hits = [e for e in EVENTS if e == s or (s == "loops" and EVENTS[e]["kind"] == "loop")]
        if not hits:
            raise SystemExit(f"unknown: {s} (events: {', '.join(EVENTS)}, loops)")
        names += [e for e in hits if e not in names]
    return names


# =========================================================================== fragments
def fragments():
    """(the sounds.json entries of the 37 events, their subtitles). A sound that carries other than the game's 16 blocks has its
    reach as the file's attenuation_distance (and the event's range in TradeSounds.kt says the same: the server only sends a sound
    that far); a loop is not streamed and has its reach so; `trade.hoist_creak` is the granary's own event, `works.hoist`"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        entries = []
        if r["kind"] == "alias":
            entries.append({"name": f"guildmark:{r['of']}", "type": "event"})
        for v in range(r["files"]):
            name = f"guildmark:{sound_path(event, v + 1)}"
            e = {"name": name}
            if r["kind"] == "loop":
                e["stream"] = False
            if r["reach"] and (r["reach"] != 16 or r["kind"] == "loop"):
                e["attenuation_distance"] = r["reach"]
            entries.append(e if len(e) > 1 else name)
        sounds[f"trade.{event}"] = {"sounds": entries, "subtitle": f"subtitles.guildmark.trade.{event}"}
        lang[f"subtitles.guildmark.trade.{event}"] = r["subtitle"]
    return sounds, lang


def write_fragments():
    """trade_sounds.json and trade_lang.json beside this script, for art/lang/merge_lang.py to add to sounds.json and en_us.json"""
    sounds, lang = fragments()
    for fname, data in (("trade_sounds.json", sounds), ("trade_lang.json", lang)):
        with open(os.path.join(HERE, fname), "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(os.path.join(HERE, fname), len(data), "entries")


# =========================================================================== the listen-through
LOOP_SHOWN = 12.0  # how long the loop plays in the listen-through (so that its join is heard once)
GAP = 0.7


def chapter_title(event, v, r):
    title = f"trade.{event}" + (f" {v + 1} of {r['files']}" if r["files"] > 1 else "")
    if r["kind"] == "loop":
        title += " (loop, 12 s)"
    if r.get("vol", 1.0) != 1.0:
        title += f" (played at {r['vol']:g})"
    return title


def listen_through(assets, names, path_):
    """one MP3 to listen to on a phone: every file that was just written, in the game's order, read back from disk (so it is what
    the game will play), each at the volume the code plays it at, a gap between and a chapter mark at each; the loop plays for
    twelve seconds, so that its join is heard once; `trade.hoist_creak` is the granary's first hoist file, as it is heard there.
    Returns the chapter list [(start, end, title, event, v)], for write_when"""
    pieces, marks, at = [], [], 0.0
    for event in names:
        r = EVENTS[event]
        if r["kind"] == "alias":
            stems = [(f"works/hoist{k}", f"trade.{event} = {r['of']}, file {k} of 4") for k in (1, 2)]
        else:
            stems = [(sound_path(event, v + 1), chapter_title(event, v, r)) for v in range(r["files"])]
        for v, (stem, title) in enumerate(stems):
            p = os.path.join(assets, "sounds", stem + ".ogg")
            if not os.path.exists(p):
                print(f"(skipped, not rendered: {stem})")
                continue
            x = read_audio(p)[0] * r["vol"]
            if r["kind"] == "loop":
                x = fade(np.tile(x, int(np.ceil(LOOP_SHOWN * SR / len(x))))[:ns(LOOP_SHOWN)], 0.0, 0.3)
            marks.append((at, at + len(x) / SR + GAP, title, event, v))
            pieces += [x, np.zeros(ns(GAP))]
            at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w", encoding="utf-8") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark trade sounds\nartist=Guildmark\n")
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


def write_when(marks, path_, mp3_name):
    """the text of what plays when: for each sound, its files, length, reach and subtitle, what it is made of, the moment it
    belongs to, and where it is in the listen-through"""
    at = {}
    for a, _, _, event, v in marks:
        at.setdefault(event, []).append(a)
    # (a paragraph is one line, for a phone's reader to wrap)
    lines = ["Trade sounds: what plays when", "=" * 29, "",
             f"{mp3_name} plays every file once, in the order of the game's list (TradeSounds.NAMES), with a chapter mark at each "
             "(a phone's player shows them). The cart's loop plays for 12 seconds so that its join is heard once. The times are "
             "where each file starts in it. A sound's moment is as the design names it (living-world/trader/design.md section 10 and "
             "Parts 1 to 7) and where the code that exists plays it; which part of the game plays it, and whether it does yet, is "
             "for the game test.",
             "",
             "The sounds are at the volumes the code plays them at (the bell on the pack animal at 0.55 and 0.7, the keeper's stamp, "
             "seal, knocks, crate and peeling ticket at 0.7, the clink of a laden back at 0.3, a middle of the 0.16 to 0.42 it plays, "
             "the rest at 1), so they keep their balance with each other, and the "
             "game's levels are quiet: turn the phone up until the first one, trade.board_stamp, is comfortable and judge the rest "
             "against it. The cart's loop is the quietest of all, by design: it lives under the wheels' own sound and the road's. "
             "None of this was listened to by the person who made it: it was made, measured and read off spectrograms, and the "
             "sounds are for the ears of whoever plays the game to judge.", ""]
    for event, r in EVENTS.items():
        files = r["files"]
        if r["kind"] == "alias":
            what = f"no file of its own: an event that points at {r['of']} (the granary's own hoist, four files)"
        elif r["kind"] == "loop":
            what = f"one loop of {r['length']:g} s, built to join itself"
        elif files > 1:
            what = f"{files} files, up to {max(length_of(r, v) for v in range(files)):g} s each"
        else:
            what = f"1 file, up to {r['length']:g} s"
        reach = f"carries {r['reach']} blocks" if r["reach"] else "carries 16 blocks"
        lines.append(f"trade.{event}: {what}; {reach}")
        lines.append(f"  subtitle: \"{r['subtitle']}\"")
        lines.append(f"  in {mp3_name}: " + ", ".join(clock(t) for t in at.get(event, [])))
        if r["kind"] != "alias":
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


def kotlin_block(src, opener):
    """the text of the `opener ... \\n    )` block of a Kotlin source (a list or map the audit compares to the recipes)"""
    m = re.search(re.escape(opener) + r"(.*?)\n    \)", src, re.S)
    return m.group(1) if m else None


def audit(assets):
    """every file of every event read back from disk and held to its row of the design: it is there, mono 44.1 kHz Vorbis, as
    many files as the recipe says (three for bell_step and hunt_shout), a one-shot no longer than its length, starting and ending
    on a quiet sample, its peak and true peak under -1 dBFS (the loop's under its ceiling), the loudest 100 ms near its target,
    the loop's length, RMS and join; then the 37 events against TradeSounds.NAMES and the ranges its table gives them, the
    fragments against the recipes, and every file against the fragments. Prints a line for each; returns 1 if any fails"""
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
            if r["kind"] == "loop":
                d_len, step, seam = seam_report(p, ns(r["length"]))
                lim = r["ceiling_db"]
                if frames != ns(r["length"]):
                    notes.append(f"is {frames} samples, the recipe says {ns(r['length'])}")
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
                if frames > ns(length_of(r, v)) + 64 or frames < ns(0.1):
                    notes.append(f"is {frames / SR:.3f} s, the recipe says up to {length_of(r, v):g}")
                if tp > -1.0 + 1e-3:
                    notes.append(f"true peak {tp:.1f}")
                if abs(short - r["loud"]) > 1.6:
                    notes.append(f"loudest 100 ms {short:.1f}, wanted {r['loud']}")
                if abs(y[0]) > 0.02 or abs(y[-1]) > 0.005:
                    notes.append(f"starts at {y[0]:.3f}, ends at {y[-1]:.4f}")
                tail = f"short {short:6.1f}  (target {r['loud']})"
            print(f"{stem:26s} {frames / SR:6.3f}s  peak {pk:6.1f}  tp {tp:6.1f}  {tail}" +
                  ("   <-- " + "; ".join(notes) if notes else ""))
            if notes:
                bad.append(f"{stem}: " + "; ".join(notes))
    for event in ("bell_step", "hunt_shout"):
        if EVENTS[event]["files"] != 3:
            bad.append(f"{event} has {EVENTS[event]['files']} files, the design says three")
    if len(EVENTS) != 37:
        bad.append(f"{len(EVENTS)} events, the design says 37")
    want_sounds, want_lang = fragments()
    for fname, want in (("trade_sounds.json", want_sounds), ("trade_lang.json", want_lang)):
        fp = os.path.join(HERE, fname)
        if not os.path.exists(fp):
            bad.append(f"{fname} is missing (a full render writes it)")
        elif json.load(open(fp, encoding="utf-8")) != want:
            bad.append(f"{fname} is not what the recipes say (a full render writes it)")
    listed = set()
    for key, ent in want_sounds.items():
        for e in ent["sounds"]:
            n = e if isinstance(e, str) else e["name"]
            if isinstance(e, dict) and e.get("type") == "event":
                if not os.path.exists(os.path.join(assets, "sounds", "works", "hoist1.ogg")):
                    bad.append(f"{key}: {n} has no file behind it")
                continue
            listed.add(n.split(":", 1)[1] + ".ogg")
            if not os.path.exists(os.path.join(assets, "sounds", n.split(":", 1)[1] + ".ogg")):
                bad.append(f"{key}: {n} has no file")
    tdir = os.path.join(assets, "sounds", SOUND_DIR)
    for f in sorted(os.listdir(tdir)) if os.path.isdir(tdir) else []:
        if f"{SOUND_DIR}/{f}" not in listed:
            bad.append(f"{SOUND_DIR}/{f} is on disk and in no fragment")
    sounds_json = os.path.join(assets, "sounds.json")
    if os.path.exists(sounds_json):
        have = json.load(open(sounds_json, encoding="utf-8"))
        for key, ent in want_sounds.items():
            if have.get(key) != ent:
                bad.append(f"sounds.json: {key} is not the fragment's (run art/lang/merge_lang.py)")
    kt_dir = os.path.join(HERE, "..", "..", "src", "main", "kotlin", "io", "github", "vaspyyy", "guildmark", "trade")
    kt = os.path.join(kt_dir, "TradeSounds.kt")
    if os.path.exists(kt):
        src = open(kt, encoding="utf-8").read()
        names = kotlin_block(src, "val NAMES: List<String> = listOf(")
        if names is not None and re.findall(r'"trade\.([a-z_]+)"', names) != list(EVENTS):
            bad.append("the events are not TradeSounds.NAMES, in its order")
        table = kotlin_block(src, "val RANGES: Map<String, Float> = mapOf(")
        if table is None:
            bad.append("TradeSounds.kt has no RANGES table")
        else:
            have = {k: float(x) for k, x in re.findall(r'"trade\.([a-z_]+)"\s+to\s+([0-9.]+)f', table)}
            want = {e: float(r["reach"]) for e, r in EVENTS.items() if r["reach"] and r["reach"] != 16}
            if have != want:
                bad.append(f"TradeSounds.RANGES is not the recipes' reaches: only in the table {sorted(set(have.items()) - set(want.items()))}, "
                           f"only in the recipes {sorted(set(want.items()) - set(have.items()))}")
    print(f"\n{len(EVENTS)} events, {files} files checked: " + ("all as the design says" if not bad else f"{len(bad)} problems"))
    for b in bad:
        print("  PROBLEM:", b)
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description="The trader's sounds: the sounds of the Living World's trade")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated events, e.g. knock,bell_step,loops")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered, and "
                                                "trade_what_plays_when.txt beside it")
    ap.add_argument("--audit", action="store_true", help="render nothing: read every file back and check it against the design")
    a = ap.parse_args()
    if a.audit:
        raise SystemExit(audit(a.assets))
    names = select(a.only) if a.only else list(EVENTS)
    if a.wav:
        os.makedirs(os.path.join(a.wav, SOUND_DIR), exist_ok=True)
    build(a.assets, names, a.wav)
    if not a.only:
        write_fragments()
    if a.mp3:
        marks = listen_through(a.assets, names, a.mp3)
        write_when(marks, os.path.join(os.path.dirname(os.path.abspath(a.mp3)), "trade_what_plays_when.txt"),
                   os.path.basename(a.mp3))


if __name__ == "__main__":
    main()
