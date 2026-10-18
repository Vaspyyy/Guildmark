"""Guildmark townsfolk, the Living World: the sounds of its everyday work, timed to its clips (LivingMoments.kt plays
each on the tick it happens, from where it happens).

usage (from the repo root):
    python3 -B art/audio/make_living_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/townsfolk/ the way make_townsfolk_sfx.py does (the event
townsfolk.well.rope is well/rope1..3, townsfolk.knock is knock1..3), and art/audio/living_sounds.json and
living_lang.json: their sounds.json entries and subtitles, merged into the real sounds.json and en_us.json by hand as
the other effects' are. --only takes event names such as knock or a group prefix such as well.

The new sounds: a seat creaking as someone sits or gets up, knuckles on a door, an axe splitting a billet, the well's
rope hauled over its curb, its bucket knocking the shaft and scraping over the stone, water sloshing in a bucket,
water scooped up in the hands, splashed on a face and shaken off them, grain poured into a hopper, a millstone
grinding, the crier's handbell, a street lamp catching, a scythe through standing wheat. The rest of the Living World's
moments use vanilla sounds (a ladder's creak, eating, a crop broken or planted, a bundle) or the townsfolk's own
(coins, goods set down, a crop pulled up).

Everything is synthesised with sfxkit and make_townsfolk_sfx.py's helpers, levelled the same way (the loudest 100 ms
to a K-weighted target, peaks under -1 dBFS), and seeded, so a rerun is bit-identical.
"""
import argparse
import json
import os
import sys
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


# =========================================================================== sitting down, getting up
@effect("seat", variants=3, subtitle="A seat creaks", loud=-21.0, max_len=0.8, fade_out=0.15)
def seat(v, rng):
    """a wooden seat taking someone's weight: the planks thump softly as they settle, the joints creak (stick-slip in
    the frame's tenons), clothes settle. v1 sitting down, v2 a heavier, longer groan, v3 lighter (getting up)"""
    dur = 0.8
    m = Mix(dur)
    m.add(thud(0.25, rng, f=[95, 88, 120][v], decay=0.03, tone=0.4), 0.0, [0.55, 0.6, 0.2][v])
    cd = [0.32, 0.5, 0.26][v]
    m.add(nrm(creak(cd, rng, [70, 45, 90][v], [190, 140, 230][v], [310, 470, 690, 980, 1400], 0.06)), [0.02, 0.03, 0.0][v],
          [0.6, 0.75, 0.55][v])
    rd = 0.3
    m.add(nrm(bp(pink(rd, rng), 800, 5000) * bell(rd, 0.25, 1.3, 2.0)), 0.0, 0.12)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 7000), wet=0.1, rt60=0.4, seed=200 + v))


# =========================================================================== at the door
@effect("knock", variants=3, subtitle="Someone knocks at a door", loud=-14.0, max_len=0.4, fade_out=0.1, limit=3.0,
        attenuation=20)
def knock_door(v, rng):
    """one rap of the knuckles on a plank door: the panel's low modes (a big, loosely held board), hollow with the
    room behind it, a dry tick where the knuckle lands, the latch rattling a little on v2"""
    dur = 0.4
    m = Mix(dur)
    f0 = [172, 158, 186][v]
    panel = knock(dur, f0 * np.array([1.0, 1.71, 2.53, 3.62, 5.1, 7.3]), [0.11, 0.085, 0.06, 0.045, 0.03, 0.02],
                  [1, .85, .6, .4, .25, .15], rng, lo=120, hi=5000, ex_len=0.002)
    m.add(nrm(comb(panel, 1 / (f0 * 2.2), 0.3, 0.25)), 0.0, 1.0)
    m.add(thud(0.15, rng, f=f0 * 0.6, decay=0.02, tone=0.5), 0.0, 0.5)
    m.add(nrm(bp(white(0.004, rng), 900, 7000) * np.exp(-tvec(0.004) / 0.0007)), 0.0, 0.35)
    if v == 1:
        m.add(nrm(jingle(0.12, rng, 3, f_lo=1800, f_hi=4200, t60=(0.01, 0.03))), 0.004, 0.08)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 9000), wet=0.1, seed=210 + v))


# =========================================================================== the woodpile
@effect("chop", variants=3, subtitle="Axe splits wood", loud=-13.0, max_len=0.7, fade_out=0.12, limit=3.0,
        attenuation=24)
def chop(v, rng):
    """the axe driven down through a billet on the block: a hard 'chock' (the bit's edge, the fibres crunching under
    it, the block thumping), the billet cracking apart, and its halves knocking onto the block and the ground"""
    dur = 0.7
    m = Mix(dur)
    m.add(thud(0.22, rng, f=[120, 110, 130][v], decay=0.03, tone=0.45), 0.0, 0.8)
    m.add(nrm(knock(0.3, [240, 410, 650, 1020, 1580], [0.08, 0.06, 0.045, 0.03, 0.02], [1, .8, .6, .4, .25], rng, lo=100,
                    hi=4000, ex_len=0.003)), 0.0, 0.9)
    ct = tvec(0.12)
    m.add(nrm(crackle(0.12, rng, 8000 * np.exp(-ct / 0.025), decay=0.0008, f_lo=400, f_hi=5000, size_var=0.8)), 0.001, 0.9)
    sd = 0.06
    m.add(nrm(crackle(sd, rng, 20000 * np.exp(-tvec(sd) / 0.012), decay=0.0004, f_lo=1500, f_hi=9000, size_var=0.9)),
          [0.012, 0.02, 0.008][v], 0.6)
    m.add(nrm(bp(white(0.01, rng), 1500, 8000) * np.exp(-tvec(0.01) / 0.0015)), 0.0, 0.35)
    for i, at in enumerate([(0.16, 0.24), (0.2, 0.3), (0.14, 0.27)][v]):
        f = rng.uniform(380, 620)
        m.add(nrm(knock(0.12, [f, f * 1.63, f * 2.5], [0.05, 0.035, 0.02], [1, .6, .3], rng, lo=150, hi=4000,
                        ex_len=0.002)), at, 0.4 * 0.8 ** i)
        m.add(thud(0.08, rng, f=rng.uniform(150, 250), decay=0.012, tone=0.3), at, 0.25)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 10000), wet=0.08, seed=220 + v))


# =========================================================================== the well
@effect("well.rope", variants=3, subtitle="Well rope creaks", loud=-19.0, max_len=0.75, fade_out=0.15)
def well_rope(v, rng):
    """one pull on the well's rope: its fibres dragged over the stone curb (a rough hiss), the strain creaking in the
    rope, and water dripping off it back down the shaft"""
    dur = 0.75
    m = Mix(dur)
    pd = [0.45, 0.5, 0.4][v]
    s = bell(pd, 0.35, 1.3, 1.6)
    rub = tvf(colored(pd, rng, -1.0), "bp", 900 + 1400 * s, 0.6) * s ** 1.3 * (0.5 + 0.5 * smooth_random(pd, rng, 50))
    fib = crackle(pd, rng, 2500 * s + 30, decay=0.0006, f_lo=500, f_hi=5000, size_var=0.8) * s
    m.add(nrm(nrm(rub) + 0.7 * nrm(fib)), 0.0, 0.7)
    m.add(nrm(creak([0.3, 0.38, 0.28][v], rng, [60, 50, 80][v], [210, 160, 240][v], [420, 640, 910, 1300], 0.05)), 0.03,
          0.55)
    drips = Mix(dur)
    for _ in range(3):
        drips.add(plop(0.06, rng, f0=rng.uniform(1300, 2400), size=0.3, fizz=0), 0.2 + rng.uniform(0, 0.45),
                  rng.uniform(0.4, 1.0))
    m.add(street(fit(drips.out(), ns(dur)), wet=0.35, seed=231 + v), 0.0, 0.1)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=230 + v))


@effect("well.bucket", variants=2, subtitle="Bucket knocks on the well", loud=-16.0, max_len=0.6, fade_out=0.15)
def well_bucket(v, rng):
    """the full bucket swinging into the stone of the shaft: its staves knock hollow, an iron hoop rings, the stone
    clicks, the water in it sloshes"""
    dur = 0.6
    m = Mix(dur)
    f0 = [290, 320][v]
    k = knock(0.35, f0 * np.array([1.0, 1.58, 2.35, 3.3, 4.6]), [0.07, 0.05, 0.04, 0.03, 0.02], [1, .8, .55, .35, .2], rng,
              lo=150, hi=5000, ex_len=0.002)
    m.add(nrm(comb(k, 1 / (f0 * 2.1), 0.4, 0.35)), 0.0, 0.9)
    m.add(nrm(ring(0.25, [1480, 2390, 3610], [0.06, 0.04, 0.03], [1, .6, .3], rng, doublet=0.003)), 0.001, 0.12)
    m.add(nrm(knock(0.2, [700, 1150, 1900], [0.03, 0.02, 0.015], [1, .5, .3], rng, lo=300, hi=8000, ex_len=0.001)), 0.0,
          0.3)
    m.add(slosh(0.45, rng, rate=[5.5, 6.5][v]), 0.03, 0.35)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.18, seed=240 + v))


@effect("well.scrape", variants=2, subtitle="Bucket scrapes over stone", loud=-18.0, max_len=0.6, fade_out=0.15)
def well_scrape(v, rng):
    """the bucket dragged up over the curb: wood scraping on stone with grit in it, the staves chattering, the water
    stirring"""
    dur = 0.6
    m = Mix(dur)
    sd = [0.42, 0.36][v]
    s = bell(sd, 0.3, 1.2, 1.5)
    scr = tvf(colored(sd, rng, -0.5), "bp", 1200 + 1500 * s, 0.9) * s ** 1.2 * (0.4 + 0.6 * smooth_random(sd, rng, 70))
    grit = crackle(sd, rng, 3000 * s + 40, decay=0.0005, f_lo=900, f_hi=7000, size_var=0.9) * s
    stave = resonators(crackle(sd, rng, 400 * s + 10, decay=0.0008, f_lo=150, f_hi=2000), [300, 480, 760],
                       [0.05, 0.04, 0.03], [1, .7, .4])
    m.add(nrm(nrm(scr) + 0.9 * nrm(grit) + 0.6 * nrm(stave)), 0.0, 1.0)
    m.add(slosh(0.4, rng, rate=6.0), 0.1, 0.2)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=250 + v))


@effect("slosh", variants=3, subtitle="Water sloshes in a bucket", loud=-19.0, max_len=0.7, fade_out=0.2)
def bucket_slosh(v, rng):
    """water slopping about in a carried bucket (a bigger, slower wave than a mug's), slapping the staves, a few drops
    spilling over the rim"""
    dur = 0.7
    m = Mix(dur)
    m.add(slosh(dur, rng, rate=[4.0, 3.4, 4.6][v]), 0.0, 1.0)
    m.add(nrm(bp(white(0.05, rng), 300, 3000) * np.exp(-tvec(0.05) / 0.015)), [0.05, 0.12, 0.08][v], 0.3)
    for _ in range([2, 3, 1][v]):
        m.add(plop(0.08, rng, f0=rng.uniform(900, 1800), size=0.4, fizz=0), 0.25 + rng.uniform(0, 0.35),
              rng.uniform(0.1, 0.2))
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 7000), wet=0.07, seed=260 + v))


# =========================================================================== washing
@effect("wash.scoop", variants=2, subtitle="Hands scoop water", loud=-18.0, max_len=0.6, fade_out=0.15)
def wash_scoop(v, rng):
    """cupped hands going into a basin (a soft 'gloop' of the air caught under them, bubbles) and lifting out full,
    water running off them"""
    dur = 0.6
    m = Mix(dur)
    m.add(nrm(plop(0.25, rng, f0=[380, 430][v], size=2.2, fizz=25)), 0.0, 0.8)
    m.add(nrm(bubbles(0.35, rng, 400 * np.exp(-tvec(0.35) / 0.08), 300, 1400, tau=(0.004, 0.012), rise=0.6)), 0.02, 0.45)
    rd = 0.3
    run_ = bp(colored(rd, rng, -1.0), 700, 5000) * bell(rd, 0.25, 1.2, 1.8) * (0.5 + 0.5 * smooth_random(rd, rng, 40))
    m.add(nrm(run_), 0.22, 0.4)
    m.add(nrm(bubbles(0.3, rng, 200 * bell(0.3, 0.3, 1.2, 1.6) + 10, 800, 2600, tau=(0.002, 0.006), rise=0.9)), 0.24, 0.35)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 8000), wet=0.06, rt60=0.35, seed=270 + v))


@effect("wash.splash", variants=2, subtitle="Water splashes a face", loud=-16.0, max_len=0.8, fade_out=0.2)
def wash_splash(v, rng):
    """a double handful of water thrown on the face: a wet slap, then drops and a trickle falling back into the basin"""
    dur = 0.8
    m = Mix(dur)
    st = tvec(0.25)
    slap = tvf(white(0.25, rng), "bp", 2200 - 1200 * np.clip(st / 0.1, 0, 1), 0.7) * np.exp(-st / 0.035) * \
        np.clip(st / 0.004, 0, 1)
    m.add(nrm(slap), 0.0, 0.9)
    m.add(nrm(thump(0.1, 160, 90, rng, decay=0.015, click=0.0, noise=0.5, nlp=800)), 0.0, 0.25)
    drops = Mix(0.7)
    for _ in range(14):
        tt = 0.08 + rng.exponential(0.14)
        if tt < 0.65:
            drops.add(plop(0.05, rng, f0=rng.uniform(900, 2600), size=0.35, fizz=0), tt, rng.uniform(0.15, 0.6))
    m.add(nrm(drops.out()), [0.0, 0.02][v], 0.5)
    tr = 0.45
    m.add(nrm(bp(colored(tr, rng, -1.0), 900, 5000) * bell(tr, 0.2, 1.2, 2.0)), 0.06, 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 9000), wet=0.06, rt60=0.35, seed=280 + v))


@effect("wash.shake", variants=2, subtitle="Wet hands shaken dry", loud=-20.0, max_len=0.7, fade_out=0.15)
def wash_shake(v, rng):
    """wet hands flicked dry: two or three quick flicks, each spattering drops on the ground a moment later"""
    dur = 0.7
    m = Mix(dur)
    for i, at in enumerate([(0.0, 0.17), (0.0, 0.14, 0.27)][v]):
        m.add(nrm(whoosh(0.12, rng, peak=0.45, f_lo=700, f_hi=3500, q=1.0, power=2.0, color=-0.5)), at, 0.5 * 0.85 ** i)
        sp = Mix(0.3)
        for _ in range(7):
            sp.add(nrm(bp(white(0.006, rng), 1500, 9000) * np.exp(-tvec(0.006) / 0.001)), rng.uniform(0.05, 0.2),
                   rng.uniform(0.2, 0.7))
        m.add(nrm(sp.out()), at + 0.02, 0.4)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.06, seed=290 + v))


# =========================================================================== the mill
@effect("miller.pour", variants=2, subtitle="Grain pours into a hopper", loud=-17.0, max_len=1.4, fade_out=0.3)
def miller_pour(v, rng):
    """a sack of grain tipped into the hopper: thousands of grains pattering onto the heap (a dense, dry hiss), the
    stream drumming on the hopper's boards, the sack's cloth flapping as it empties"""
    dur = 1.4
    m = Mix(dur)
    pd = [1.15, 0.95][v]
    t = tvec(pd)
    s = np.clip(t / 0.08, 0, 1) * np.clip((pd - t) / 0.35, 0, 1) ** 1.5 * (0.8 + 0.2 * smooth_random(pd, rng, 4))
    rain = crackle(pd, rng, 16000 * s + 50, decay=0.00025, f_lo=1800, f_hi=11000, size_var=0.7)
    hush = bp(colored(pd, rng, 0.0), 2500, 9000) * s * (0.6 + 0.4 * smooth_random(pd, rng, 25))
    body = resonators(crackle(pd, rng, 3000 * s + 20, decay=0.0006, f_lo=150, f_hi=2500), [210, 345, 520, 790],
                      [0.06, 0.05, 0.04, 0.03], [1, .7, .5, .3])
    m.add(nrm(nrm(rain) + 0.5 * nrm(hush) + 0.45 * nrm(body)), 0.0, 1.0)
    m.add(nrm(bp(pink(0.2, rng), 400, 3000) * bell(0.2, 0.3, 1.2, 1.8)), pd * 0.6, 0.12)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(lp(y, 12000), wet=0.08, rt60=0.42, seed=300 + v))


@effect("miller.grind", variants=2, subtitle="Millstone grinds", loud=-18.0, max_len=1.1, fade_out=0.3)
def miller_grind(v, rng):
    """one turn of the grindstone: stone grinding on grain and stone (a low, gritty rumble that swells and fades as
    the crank comes round), the axle creaking in its wooden bearing"""
    dur = 1.1
    m = Mix(dur)
    s = bell(dur, 0.4, 1.0, 1.4)
    rumble = lp(brown(dur, rng), 180, order=4) * s
    grind = tvf(colored(dur, rng, -1.0), "bp", 500 + 400 * s, 0.7) * s * (0.5 + 0.5 * smooth_random(dur, rng, 30))
    crunch = crackle(dur, rng, 1800 * s + 20, decay=0.0007, f_lo=500, f_hi=5000, size_var=0.9) * s
    m.add(nrm(nrm(rumble) + 0.8 * nrm(grind) + 0.6 * nrm(crunch)), 0.0, 1.0)
    m.add(nrm(creak(0.35, rng, [40, 55][v], [110, 130][v], [520, 780, 1150], 0.05)), [0.35, 0.5][v], 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(small_room(y, wet=0.08, rt60=0.42, seed=310 + v))


# =========================================================================== the crier
def handbell_tone(dur, rng, f, bright=1.0):
    """one strike of a brass handbell's clapper: a bell's partials (hum, prime, tierce, quint, nominal and the upper
    ones, each a close pair that beats), and the clapper's tick; a softer strike ([bright] under 1) wakes the upper
    partials less"""
    r = np.array([0.5, 1.0, 1.183, 1.506, 2.0, 2.514, 2.662, 3.011, 4.166])
    t60 = np.array([1.8, 1.4, 1.1, 0.9, 0.8, 0.5, 0.45, 0.35, 0.22])
    amps = np.array([0.35, 1.0, 0.7, 0.45, 0.6, 0.35, 0.25, 0.2, 0.12]) * bright ** (0.8 * np.maximum(0.0, r - 1.0))
    y = ring(dur, f * r, t60, amps, rng, doublet=0.003, doublet_amp=0.6)
    tick = fit(bp(white(0.003, rng), 2000, 12000) * np.exp(-tvec(0.003) / 0.0005), len(y))
    return nrm(nrm(y) + 0.3 * bright * nrm(tick))


# the crier's handbell at the ends of its swings: how the clapper strikes it each time, (when, how hard) — a clean
# stroke, one that bounces back for a second, a hard one that rattles three times, a glancing one and its echo
BELL_STRIKES = [[(0.0, 1.0), (0.13, 0.55)], [(0.0, 1.0)], [(0.0, 0.95), (0.085, 0.6), (0.19, 0.35)], [(0.0, 0.7), (0.055, 0.45)]]


@effect("crier.handbell", variants=4, subtitle="Handbell rings", loud=-13.0, max_len=1.8, fade_out=0.5, attenuation=48)
def crier_handbell(v, rng):
    """the town crier's brass handbell at one end of a swing (ring_bell rings it at every end of the shake, four times
    a loop): the clapper strikes once, or bounces to strike again, harder or softer; the bell, still swinging, sounds
    louder and duller as its mouth turns toward and away. One bell, so one pitch: the variants differ in the stroke."""
    dur = 1.8
    m = Mix(dur)
    for at, g in BELL_STRIKES[v]:
        m.add(handbell_tone(dur - at, rng, 660.0 * rng.uniform(0.9995, 1.0005), bright=g), at, g)
    y = fit(m.out(), ns(dur))
    t = tvec(dur)
    y = y * (1 + 0.18 * np.sin(2 * np.pi * [2.6, 2.4, 2.8, 2.5][v] * t + rng.uniform(0, 2 * np.pi)) * np.exp(-t / 0.8))
    return lead_in(street(y, wet=0.15, seed=320 + v))


# =========================================================================== the lamplighter
@effect("lamp.light", variants=2, subtitle="A street lamp is lit", loud=-19.0, max_len=0.9, fade_out=0.3)
def lamp_light(v, rng):
    """the lamplighter's flame put to the wick: a soft 'fwoomp' as it takes, a few crackles, then the new flame
    fluttering in its glass"""
    dur = 0.9
    m = Mix(dur)
    wd = 0.3
    wt = tvec(wd)
    wf = tvf(colored(wd, rng, -2.0), "lp", 300 + 1500 * np.exp(-wt / 0.05), 0.8) * np.clip(wt / 0.015, 0, 1) * \
        np.exp(-wt / [0.08, 0.1][v])
    m.add(nrm(wf), 0.0, 0.9)
    m.add(nrm(crackle(0.4, rng, 120 * np.exp(-tvec(0.4) / 0.15) + 10, decay=0.0006, f_lo=1500, f_hi=8000, size_var=0.8)),
          0.01, 0.35)
    fl = 0.7
    flutter = bp(pink(fl, rng), 200, 1200) * bell(fl, 0.15, 1.2, 1.6) * (0.4 + 0.6 * smooth_random(fl, rng, 14))
    m.add(nrm(flutter), 0.05, 0.3)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.08, seed=330 + v))


# =========================================================================== the field
@effect("farmer.scythe", variants=3, subtitle="Scythe swishes through the crop", loud=-16.0, max_len=0.7, fade_out=0.15)
def farmer_scythe(v, rng):
    """the long blade swung low through standing wheat: its swish, the crisp shearing of many stalks cut at once,
    the cut stalks rustling as they fall"""
    dur = 0.7
    m = Mix(dur)
    sw = whoosh(0.45, rng, peak=[0.45, 0.5, 0.42][v], f_lo=500, f_hi=[4200, 3800, 4600][v], q=1.3, power=2.2, rise=1.5,
                fall=1.3, whistle=0.3, whistle_mult=1.4, whistle_q=10.0, color=-0.5, doppler=0.08)
    m.add(nrm(sw), 0.0, 0.8)
    cd = 0.22
    cs = bell(cd, 0.3, 1.2, 1.6)
    cut = crackle(cd, rng, 9000 * cs + 50, decay=0.0003, f_lo=2500, f_hi=11000, size_var=0.8) * cs
    m.add(nrm(cut), 0.13, 0.75)
    m.add(rustle(0.4, rng, peak=0.3, density=900), 0.15, 0.5)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.06, seed=340 + v))


# =========================================================================== main
def write_fragments():
    """living_sounds.json (the sounds.json entries) and living_lang.json (their subtitles), for merging by hand"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        names = [f"guildmark:{sound_path(event, v + 1)}" for v in range(r["variants"])]
        entry = [{"name": n, "attenuation_distance": r["attenuation"]} for n in names] if r["attenuation"] else names
        sounds[f"townsfolk.{event}"] = {"sounds": entry, "subtitle": f"subtitles.guildmark.townsfolk.{event}"}
        lang[f"subtitles.guildmark.townsfolk.{event}"] = r["subtitle"]
    for name, data in (("living_sounds.json", sounds), ("living_lang.json", lang)):
        with open(os.path.join(HERE, name), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    sel = a.only.split(",") if a.only else None
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
    write_fragments()


if __name__ == "__main__":
    main()
