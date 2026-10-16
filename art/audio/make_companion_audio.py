"""Guildmark's four companions (Bram, Ysolde, Kestrel and Lucan): the six small sounds that go with them, made from nothing.

usage (from the repo root):
    python3 -B art/audio/make_companion_audio.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b] [--mp3 FILE]

Writes the effects as mono 44.1 kHz Ogg Vorbis to <assets>/sounds/companion/<name>.ogg (one file each: they are cues, not
sounds that need variants). --only takes names; --wav also writes WAV copies; --mp3 joins what was rendered into one
listen-through file, a chapter mark at each sound.

  approve      a companion pleased with what you did: two soft bells rising a third, small and quiet
  disapprove   a companion displeased: one low dull note that sags, quiet, nothing like a buzzer
  downed       a body going down on one knee in its kit: the knee, steel and leather, a breath let out
  revive       a breath drawn in, a hand slapped into a hand, a small warm ember swell
  chapter      a short questioning figure of three plucked notes (a companion has something to ask)
  sworn        a warm short swell for the oath: a lute rising to a chord and low strings under it

The bells are the prayers' warm bells of make_magic_audio.py and the strings the Hearthsworn's and the Quiet Hand's of
make_faction_audio.py (a plucked string, a bowed one); the breath, the steel and leather and the ground under a kneeling
knight are the townsfolk's and the combat's. Nothing is sampled: each sound is one `@recipe` that sets its length and
loudness (as in make_faction_audio.py), built from real materials' modes, grains and envelopes with sfxkit.py, and every
random number is seeded, so a rerun gives the same files. Their tunes sit in D major, the Wardfires' own key.
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import *  # noqa: E402,F401,F403
from make_magic_audio import (Bus, air_thump, chapel, embers, flame, mallet, nrm, note, rise_in, tract,  # noqa: E402
                              warm_bell)
from make_faction_audio import bowed, cloth, guitar_body, pluck, slap, yard  # noqa: E402
from make_sfx import clank, rattle  # noqa: E402
from make_townsfolk_sfx import blade, creak, lead_in, small_room, thud  # noqa: E402

RECIPES = {}
COMPANION_DIR = "companion"


def recipe(name, peak_db=-3.0, fade_out=0.03, max_len=None, tail_db=-62.0, rms_db=None, limit_db=6.0):
    """one effect, written as companion/<name>.ogg. peak_db: where the file's peak sits (the cues that come often are
    quiet, the oath is not). rms_db: the loudness a sharp sound should have (its RMS, with its peak at peak_db): it is
    soft-limited just enough to land there, but never by more than limit_db, so that its knock stays a knock"""
    def deco(fn):
        RECIPES[name] = dict(fn=fn, peak_db=peak_db, fade_out=fade_out, max_len=max_len, tail_db=tail_db,
                             rms_db=rms_db, limit_db=limit_db)
        return fn
    return deco


def sound_path(name):
    return f"{COMPANION_DIR}/{name}"


def render_effect(name):
    """one effect, finished (levelled, trimmed, faded, peak at peak_db)"""
    r = RECIPES[name]
    # (starting from zero, so that the file's high-pass adds no thump)
    x = lead_in(r["fn"](0, rng_for("companion/" + name, 0)))

    def finish(y):
        return finalize(y, peak_db=r["peak_db"], fade_out=r["fade_out"], max_len=r["max_len"], tail_db=r["tail_db"])

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


# =========================================================================== breath
def breath(dur, rng, f0, f1, peak, vowel_a="a", vowel_b="uh", front=0.012, level=1.0):
    """a breath that is not a word (cold air or warm air moved through a mouth, and no voice in it, so that it never
    turns into speech): noise through a vocal tract that slides from one vowel to another, with a band of air sweeping
    from f0 to f1 over it. peak is where in the breath (0..1) it is loudest: an inhale swells to the end of it, an exhale
    pushes at the front and tails away"""
    t = tvec(dur)
    u = t / dur
    s = bell(dur, peak, 1.5, 1.7)
    s = s * np.sin(np.pi / 2 * np.clip(t / front, 0, 1)) ** 2
    src = colored(dur, rng, -0.5) * (0.5 + 0.5 * smooth_random(dur, rng, 7.0, 0.0, 1.0))
    vt = tract(src, vowel_a, vowel_b, curve=np.clip(u * 1.2, 0, 1) ** 1.2, scale=1.0, bw=1.8)
    sweep = f0 * (f1 / f0) ** np.clip(u, 0, 1)
    air = tvf(colored(dur, rng, -1.0), "bp", sweep, 0.7)
    y = nrm(vt) * 0.7 + nrm(air) * 0.5
    return level * nrm(y) * s ** 1.15


def sag(dur, f_from, f_to, over, harmonics, tilt=1.0):
    """a note whose pitch falls from f_from to f_to over `over` seconds (a smooth sag, quick at first and then easing):
    a few harmonics of it, each sliding with it"""
    t = tvec(dur)
    u = np.clip(t / over, 0, 1)
    f = f_from * (f_to / f_from) ** (np.sin(np.pi / 2 * u) ** 1.0)
    ph = phase_from_freq(f, len(t))
    y = np.zeros(len(t))
    for k, g in enumerate(harmonics, start=1):
        y += g * np.sin(k * ph)
    return y


def lute(dur, rng, f, t60=1.6, bright=0.35, pos=0.28):
    """a lute string plucked: pluck() of make_faction_audio.py, through the soft box the string is plucked over"""
    return guitar_body(pluck(dur, rng, f, t60=t60, bright=bright, pos=pos))


# =========================================================================== the companions' cues
@recipe("approve", peak_db=-11.0, fade_out=0.3, max_len=1.25, tail_db=-60.0)
def approve(v, rng):
    """a companion pleased with what the player did: two soft bells struck with a felt mallet, D and then F sharp above it
    (a major third, the way a voice rises on 'mm-hm'), the second left to ring a little longer, the soft hum of the
    bell an octave down giving it warmth, a small room round them. Quiet: it plays often"""
    dur = 1.4
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    m.add(warm_bell(note("D5"), rng, t60=0.6, fc=2400, attack=0.007, hum=0.6), 0.0, 0.8)
    m.add(warm_bell(note("F#5"), rng, t60=1.0, fc=2400, attack=0.007, hum=0.45), 0.135, 1.0)
    m.add(mallet(0.04, rng, f=480, decay=0.006, tone=0.3), 0.0, 0.1)  # the felt of the mallet
    m.add(mallet(0.04, rng, f=600, decay=0.006, tone=0.3), 0.135, 0.1)
    glow = (np.sin(2 * np.pi * note("D4") * t) + 0.5 * np.sin(2 * np.pi * note("F#4") * t)) * \
        rise_in(t, 0.1, 0.12) * np.exp(-np.maximum(0, t - 0.2) / 0.38)
    m.add(glow, 0.0, 0.3)
    return reverb(fit(m.out(), n), wet=0.12, rt60=0.7, predelay=0.008, hf_damp=0.6, seed=1601, early_span=0.025)


@recipe("disapprove", peak_db=-12.0, fade_out=0.25, max_len=0.85, tail_db=-60.0)
def disapprove(v, rng):
    """a companion displeased: one low dull note that sags, G down to D in half a second (soft in front, with a felt
    mallet's muffled knock, its few harmonics rolled off at a thousand Hz so that it is dull and not hard), a breath of
    air let out under it like a sigh. A fall that is a sigh and not a buzzer: no edge anywhere in it. Quiet"""
    dur = 0.95
    n = ns(dur)
    t = tvec(dur)
    env = np.sin(np.pi / 2 * np.clip(t / 0.012, 0, 1)) ** 2 * np.exp(-t / 0.3) * np.clip((dur - t) / 0.2, 0, 1)
    body = lp(sag(dur, note("G3"), note("D3"), 0.5, [1.0, 0.42, 0.14]), 1100, order=2) * env
    wob = 1 + 0.06 * np.sin(2 * np.pi * 4.6 * t + 1.0) * np.clip(t / 0.2, 0, 1)  # (a faint waver: a voice, not a machine)
    m = Bus(dur)
    m.add(body * wob, 0.0, 1.0)
    m.add(thud(0.16, rng, f=100, decay=0.035, tone=0.45), 0.0, 0.55)
    m.add(breath(0.55, rng, 1400, 500, 0.18, "uh", "o", level=1.0), 0.03, 0.3)
    return reverb(lp(fit(m.out(), n), 3200), wet=0.1, rt60=0.5, predelay=0.006, hf_damp=0.7, seed=1603, early_span=0.02)


@recipe("downed", peak_db=-4.0, fade_out=0.3, max_len=1.3, tail_db=-62.0, rms_db=-20.0, limit_db=7.0)
def downed(v, rng):
    """a companion going down on one knee in their kit (they never die: they go down and get up again): a rush of cloth
    and a creak of leather as the weight comes off the back foot, the knee meeting the ground (a dull thud in the earth, the
    ring of a plate of steel over the joint, a scrape of grit), the mail and straps rattling as it all settles, a clink of
    the sword's hilt against steel, and a breath let out, tired, and the last small shift of weight after it"""
    dur = 1.5
    n = ns(dur)
    at = 0.12  # the knee meets the ground
    m = Bus(dur)
    m.add(cloth(0.2, rng, peak=0.55, lo=230, hi=2400, grit=500), 0.0, 0.4)
    m.add(creak(0.14, rng, 140, 430, [560, 940, 1500], 0.03), 0.01, 0.16)
    m.add(thud(0.24, rng, f=76, decay=0.05, tone=0.55), at, 1.0)
    m.add(lp(clank(0.34, 430, rng, "plate", n=9, t60_lo=0.2, t60_hi=0.04, tilt=0.5, beat=0.003), 5200), at + 0.001, 0.6)
    m.add(crackle(0.14, rng, 3800 * np.exp(-tvec(0.14) / 0.04) + 100, decay=0.0009, f_lo=300, f_hi=3600, size_var=0.9),
          at, 0.4)
    m.add(jingle(0.5, rng, 15, f_lo=1800, f_hi=6500, t60=(0.02, 0.07), start=0.0, spread=0.18, amp_decay=0.2),
          at + 0.01, 0.45)
    m.add(rattle(0.35, rng, 0.0, 0.25, rate=34.0, f_lo=1100, f_hi=3600, decay_tau=0.12), at + 0.03, 0.55)
    m.add(blade(0.4, rng, 1180, t60=0.26), at + 0.075, 0.5)  # the hilt of the sword on the steel at the hip
    m.add(breath(0.6, rng, 1800, 520, 0.14, "a", "uh", front=0.02), at + 0.17, 1.1)
    m.add(cloth(0.2, rng, peak=0.4, lo=240, hi=2200, grit=350), 0.7, 0.22)  # the weight settling
    m.add(creak(0.12, rng, 120, 340, [520, 880, 1400], 0.03), 0.72, 0.1)
    m.add(lp(clank(0.2, 610, rng, "plate", n=7, t60_lo=0.08, t60_hi=0.025, tilt=0.5, beat=0.0), 4800), 0.74, 0.2)
    return yard(fit(m.out(), n), 0.1, seed=1605)


@recipe("revive", peak_db=-4.0, fade_out=0.4, max_len=1.75, tail_db=-62.0, rms_db=-21.0, limit_db=7.0)
def revive(v, rng):
    """a companion helped to their feet: a breath drawn in, quick and glad, a hand slapped into a hand (a palm on a palm
    with a rustle of sleeve, the grip that pulls them up), and a small warm swell of embers breathing up in the same
    moment, a push of air and a glow of fire under it with a few sparks and, down in it, a soft low bell"""
    dur = 1.9
    n = ns(dur)
    clasp = 0.37
    m = Bus(dur)
    m.add(breath(0.36, rng, 700, 3300, 0.8, "uh", "i", front=0.03, level=1.0), 0.0, 1.3)
    m.add(slap(0.14, rng, f_res=980, t60=0.03, bright=0.8, weight=1.1), clasp, 1.1)
    m.add(cloth(0.22, rng, peak=0.3, lo=260, hi=2600, grit=500), clasp - 0.04, 0.28)
    wd = 1.15
    wt = tvec(wd)
    s = bell(wd, 0.2, 1.4, 2.3)
    s = s * np.sin(np.pi / 2 * np.clip(wt / 0.04, 0, 1)) ** 2
    m.add(air_thump(0.45, rng, 130, 60, rise=0.05, decay=0.14, noise=0.8, nlp=380), clasp + 0.02, 0.55)
    m.add(flame(wd, rng, s, lo=100, hi=2600, core=(300, 900), hiss=0.06, flutter_hz=16.0) * s, clasp + 0.03, 0.8)
    m.add(embers(wd, rng, 260 * s + 12, lo=1500, hi=7000, pops=0.5) * np.sin(np.pi / 2 * np.clip(wt / 0.05, 0, 1)),
          clasp + 0.04, 0.3)
    m.add(warm_bell(note("D4"), rng, t60=1.3, fc=1800, attack=0.06, hum=0.7), clasp + 0.04, 0.5)
    m.add(warm_bell(note("A4"), rng, t60=0.9, fc=1800, attack=0.07), clasp + 0.07, 0.22)
    return small_room(fit(m.out(), n), wet=0.12, rt60=0.7, seed=1607)


@recipe("chapter", peak_db=-7.0, fade_out=0.3, max_len=1.6, tail_db=-60.0, rms_db=-26.0)
def chapter(v, rng):
    """a companion with something to ask: a short figure of three plucked notes on a lute, D, up to A and on to B (the sixth,
    which does not come home: the way a question lifts and is left hanging), each a little louder than the one before, the
    last one longer and brighter, left ringing. Soft fingertips on the string, a small room"""
    dur = 1.9
    n = ns(dur)
    m = Bus(dur)
    for f, at, g, t60, bright in [(note("D4"), 0.0, 0.75, 1.1, 0.3), (note("A4"), 0.2, 0.85, 1.2, 0.35),
                                  (note("B4"), 0.42, 1.0, 1.7, 0.45)]:
        m.add(lute(dur, rng, f * rng.uniform(0.9995, 1.0005), t60=t60, bright=bright), at, g)
        m.add(thud(0.04, rng, f=rng.uniform(220, 300), decay=0.006, tone=0.2), at, 0.08)  # the fingertip
    return small_room(fit(m.out(), n), wet=0.11, rt60=0.6, seed=1609)


@recipe("sworn", peak_db=-4.0, fade_out=0.6, max_len=2.9, tail_db=-64.0, rms_db=-21.0)
def sworn(v, rng):
    """a companion sworn to the player: a warm short swell, more a hand on the shoulder than a hall's fanfare. A lute
    rises in D major (A, D, F sharp, A, and the high D, a breath apart) and comes home in a rolled chord, and under it a
    few low strings (D, A, D and F sharp, two players on each, bowed soft and muted) swell in with the run, hold the chord
    and let it go. The notes are the Hearthsworn's bells' (D major, the Wardfires' key) but plucked and bowed, near and
    dry, and not struck in a stone hall"""
    dur = 3.2
    n = ns(dur)
    t = tvec(dur)
    m = Bus(dur)
    run = [(note("A3"), 0.0, 0.7), (note("D4"), 0.13, 0.75), (note("F#4"), 0.26, 0.8), (note("A4"), 0.39, 0.85),
           (note("D5"), 0.52, 1.0)]
    for f, at, g in run:  # the lute climbing
        m.add(lute(dur, rng, f * rng.uniform(0.9995, 1.0005), t60=1.4, bright=0.4), at, g)
    at = 0.86
    for i, (f, g) in enumerate([(note("D3"), 1.0), (note("A3"), 0.85), (note("D4"), 0.8), (note("F#4"), 0.7),
                                (note("A4"), 0.6)]):  # the chord, rolled from the bass up
        m.add(lute(dur, rng, f * rng.uniform(0.9995, 1.0005), t60=2.2, bright=0.4), at + 0.028 * i, g)
        m.add(thud(0.04, rng, f=rng.uniform(190, 260), decay=0.006, tone=0.2), at + 0.028 * i, 0.05)
    a = np.clip(rise_in(t, 0.15, 0.95) * np.clip((2.5 - t) / 0.9, 0, 1), 0, 1) ** 1.2  # the bow: in with the run, out slowly
    for f, g in [(note("D3"), 1.0), (note("A3"), 0.8), (note("D4"), 0.7), (note("F#4"), 0.5)]:
        for det in (-0.004, 0.004):
            m.add(bowed(dur, rng, f * (1 + det), a, vib=(5.0, 0.004), mute=1900.0, noise=0.05), 0.0, 0.5 * g)
    m.add(np.sin(2 * np.pi * note("D2") * t) * a ** 2, 0.0, 0.18)  # the weight under it
    return chapel(fit(m.out(), n), 0.17, 1.1, seed=1611)


# =========================================================================== main
def build_sfx(assets, names, wav_dir=None):
    for name in names:
        t0 = time.time()
        x = render_effect(name)
        out = sound_path(name)
        write_ogg(os.path.join(assets, "sounds", out + ".ogg"), x)
        if wav_dir:
            write_wav(os.path.join(wav_dir, out + ".wav"), x)
        print(f"{out:24s} {len(x) / SR:6.3f}s  peak {to_db(np.max(np.abs(x))):6.1f}  rms {to_db(rms(x)):6.1f}  "
              f"{time.time() - t0:5.2f}s", flush=True)


GAP = 1.2  # seconds of quiet between one sound and the next in the listen-through


def listen_through(assets, names, path):
    """one MP3 to listen to on a phone: every sound that was just written, in order, a gap between, a chapter mark (the
    sound's name) at each. The files are read back from disk, so it is what the game will play"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        p = os.path.join(assets, "sounds", sound_path(name) + ".ogg")
        if not os.path.exists(p):
            print(f"(skipped, not rendered: {name})")
            continue
        x = read_audio(p)[0]
        marks.append((at, at + len(x) / SR + GAP, name))
        pieces += [x, np.zeros(ns(GAP))]
        at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark companion sounds\nartist=Guildmark\n")
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
    names = []
    for s in (s.strip() for s in only.split(",") if s.strip()):
        if s.startswith(COMPANION_DIR + "/"):
            s = s[len(COMPANION_DIR) + 1:]
        if s not in RECIPES:
            raise SystemExit(f"unknown: {s} (effects: {', '.join(RECIPES)})")
        if s not in names:
            names.append(s)
    return names


def main():
    ap = argparse.ArgumentParser(description="Guildmark's companions: their six small sounds")
    ap.add_argument("assets", help="the assets folder, e.g. src/main/resources/assets/guildmark")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    ap.add_argument("--only", default=None, help="comma-separated effect names")
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    a = ap.parse_args()
    names = select(a.only) if a.only else list(RECIPES)
    build_sfx(a.assets, names, a.wav)
    if a.mp3:
        listen_through(a.assets, names, a.mp3)


if __name__ == "__main__":
    main()
