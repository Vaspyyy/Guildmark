"""Guildmark's lamplighters: the sounds of their round, timed to their clips (LivingMoments.kt plays each on the tick it
happens, from where it happens) and to the street lamps themselves.

usage (from the repo root):
    python3 -B art/audio/make_lamp_sfx.py src/main/resources/assets/guildmark [--wav DIR] [--only a,b,...] [--mp3 FILE]

Writes mono 44.1 kHz Ogg Vorbis files to <assets>/sounds/townsfolk/lamp/ the way make_living_sfx.py does (the event
townsfolk.lamp.clink is lamp/clink1..3), and art/audio/lamp_sounds.json and lamp_lang.json: their sounds.json entries
and subtitles, merged into the real sounds.json and en_us.json by hand as the other effects' are. --only takes event
names such as lamp.clink; --mp3 joins what was rendered into one listen-through MP3, a chapter mark at each sound.

The sounds: the wick at the top of the pole catching as the lamplighter sets out of an evening (a striker's rasp, the
oiled wick taking with a soft 'ffp' and a few crackles, then its small flame fluttering), the pole's brass end tapping
on the lantern's frame as the wick goes in at its door, the snuffer's brass bell clinking on the lantern of a morning,
and the hiss of the flame smothered under it (also heard when a lamp is put out by hand, or goes out by itself). The
lamp catching is make_living_sfx.py's lamp.light.

Everything is synthesised with sfxkit and make_townsfolk_sfx.py's helpers, levelled the same way (the loudest 100 ms
to a K-weighted target, peaks under -1 dBFS), and seeded, so a rerun is bit-identical.
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


# =========================================================================== shared parts
def flutter(dur, rng, lo=180.0, hi=1100.0, rate=13.0):
    """a small flame burning in still air: soft low noise, its level wavering as the flame does"""
    s = bell(dur, 0.18, 1.2, 1.5)
    return nrm(bp(pink(dur, rng), lo, hi) * s * (0.35 + 0.65 * smooth_random(dur, rng, rate)))


def brass_tick(rng, lo=3000.0, hi=13000.0, tau=0.0005):
    """the hard little tick of brass meeting iron"""
    t = tvec(0.004)
    return bp(white(0.004, rng), lo, hi) * np.exp(-t / tau) * np.clip(t / 0.0002, 0, 1)


def frame_ring(dur, rng, f0, t60=0.07, bright=1.0):
    """the lantern's thin iron frame and glass struck lightly: a few close, quickly damped modes"""
    r = np.array([1.0, 1.62, 2.71, 3.93, 5.38]) * rng.uniform(0.99, 1.01, 5)
    amps = np.array([1.0, 0.75, 0.5, 0.3, 0.18]) * np.array([1.0, bright, bright, bright, bright])
    return ring(dur, f0 * r, t60 * np.array([1.0, 0.8, 0.6, 0.45, 0.3]), amps, rng, doublet=0.003, doublet_amp=0.5)


def cup_bell(dur, rng, f0, t60=0.32, bright=1.0):
    """the snuffer: a small cup of thin brass rung by the knock, its partials spread wide as a cup's are (each a close
    pair, so it shimmers as it dies)"""
    r = np.array([1.0, 2.32, 3.86, 5.51, 7.3]) * rng.uniform(0.995, 1.005, 5)
    t60s = t60 * np.array([1.0, 0.7, 0.45, 0.3, 0.2])
    amps = np.array([1.0, 0.55, 0.4, 0.22, 0.12]) * bright ** np.array([0.0, 0.6, 1.0, 1.4, 1.8])
    return ring(dur, f0 * r, t60s, amps, rng, doublet=0.0035, doublet_amp=0.6)


# =========================================================================== the wick
@effect("lamp.wick", variants=2, subtitle="A lamplighter's wick catches", loud=-22.0, max_len=1.0, fade_out=0.3)
def lamp_wick(v, rng):
    """the wick at the top of the pole lit as the lamplighter sets out: the striker drawn across (a short rasp), the
    oiled wick taking with a soft 'ffp', a few crackles as it settles, then its small flame fluttering. v1 catches on the
    first stroke; v2 needs a second, the first only sparking"""
    dur = 1.0
    m = Mix(dur)
    strokes = [(0.0, 0.045, 1.0)] if v == 0 else [(0.0, 0.035, 0.6), (0.14, 0.05, 1.0)]
    for at, sd, g in strokes:
        st = tvec(sd)
        s = np.clip(st / 0.004, 0, 1) * np.exp(-np.maximum(st - sd * 0.5, 0) / (sd * 0.25))
        rasp = tvf(white(sd, rng), "bp", 2600 + 4200 * st / sd, 1.2) * s * (0.6 + 0.4 * smooth_random(sd, rng, 400))
        m.add(nrm(rasp), at, 0.55 * g)
        m.add(nrm(crackle(sd + 0.03, rng, 900, decay=0.0003, f_lo=2500, f_hi=11000, size_var=0.9)), at, 0.3 * g)
    lit = strokes[-1][0] + strokes[-1][1] * 0.6
    wd = 0.22
    wt = tvec(wd)
    ffp = tvf(colored(wd, rng, -1.5), "lp", 500 + 3200 * np.exp(-wt / 0.03), 0.9) * np.clip(wt / 0.01, 0, 1) * \
        np.exp(-wt / 0.06)
    m.add(nrm(ffp), lit, 0.75)
    cd = 0.35
    m.add(nrm(crackle(cd, rng, 90 * np.exp(-tvec(cd) / 0.12) + 8, decay=0.0005, f_lo=1800, f_hi=9000, size_var=0.8)),
          lit + 0.01, 0.3)
    fl = dur - lit - 0.05
    m.add(flutter(fl, rng), lit + 0.04, 0.22)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 11000), wet=0.07, seed=400 + v))


# =========================================================================== the pole at the lantern's door
@effect("lamp.tap", variants=3, subtitle="Pole taps a lantern", loud=-23.0, max_len=0.45, fade_out=0.12)
def lamp_tap(v, rng):
    """the pole's brass end knocking on the lantern's frame as the wick goes in at its little door: the iron frame and
    glass ring briefly, the ash pole knocks under it, the door's catch rattling a little on v2"""
    dur = 0.45
    m = Mix(dur)
    f0 = [1720.0, 1580.0, 1890.0][v]
    m.add(nrm(frame_ring(0.3, rng, f0 * rng.uniform(0.995, 1.005), t60=[0.07, 0.06, 0.08][v],
                         bright=[0.9, 0.75, 1.0][v])), 0.0, 0.8)
    m.add(nrm(brass_tick(rng)), 0.0, 0.45)
    m.add(nrm(knock(0.12, [310, 640, 1050], [0.03, 0.02, 0.012], [1, .6, .3], rng, lo=200, hi=4000, ex_len=0.002)),
          0.0, 0.35)
    if v == 1:
        m.add(nrm(jingle(0.12, rng, 3, f_lo=2400, f_hi=5200, t60=(0.01, 0.025))), 0.012, 0.18)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 12000), wet=0.08, seed=410 + v))


# =========================================================================== the snuffer
@effect("lamp.clink", variants=3, subtitle="Snuffer clinks on a lantern", loud=-20.0, max_len=0.7, fade_out=0.25)
def lamp_clink(v, rng):
    """the snuffer's little brass bell set down over the flame: brass on the lantern's iron, a bright clink and the cup
    ringing on a moment, the frame answering low; v2 touches twice (set down, settled), v3 softer"""
    dur = 0.7
    m = Mix(dur)
    f0 = [2780.0, 2640.0, 2900.0][v] * rng.uniform(0.997, 1.003)
    touches = [[(0.0, 1.0)], [(0.0, 0.8), (0.07, 0.55)], [(0.0, 0.6)]][v]
    for at, g in touches:
        m.add(nrm(cup_bell(dur - at, rng, f0, t60=[0.32, 0.28, 0.26][v], bright=g)), at, g)
        m.add(nrm(brass_tick(rng, lo=3500.0)), at, 0.35 * g)
        m.add(nrm(frame_ring(0.18, rng, 1150.0 * rng.uniform(0.98, 1.02), t60=0.05, bright=0.6)), at, 0.3 * g)
    y = fit(m.out(), ns(dur))
    return lead_in(street(y, wet=0.1, seed=420 + v))


@effect("lamp.hiss", variants=2, subtitle="A street lamp is snuffed", loud=-22.0, max_len=0.8, fade_out=0.25)
def lamp_hiss(v, rng):
    """the flame smothered: a short soft hiss as the air is cut off, the wick sputtering a little as it dies, and the
    last breath of hot air let out ('pff'); v2 sputters once more before it goes"""
    dur = 0.8
    m = Mix(dur)
    hd = 0.34
    ht = tvec(hd)
    s = np.clip(ht / 0.02, 0, 1) * np.exp(-ht / [0.1, 0.12][v])
    hiss = tvf(white(hd, rng), "bp", 5200 - 2600 * ht / hd, 0.9) * s * (0.75 + 0.25 * smooth_random(hd, rng, 60))
    m.add(nrm(hiss), 0.0, 0.7)
    cd = 0.3
    m.add(nrm(crackle(cd, rng, 140 * np.exp(-tvec(cd) / 0.09) + 4, decay=0.0004, f_lo=1500, f_hi=8000, size_var=0.9)),
          0.02, 0.35)
    if v == 1:
        m.add(nrm(plop(0.05, rng, f0=1400, size=0.25, fizz=20.0)), 0.16, 0.2)
    pd = 0.2
    pt = tvec(pd)
    pff = lp(colored(pd, rng, -2.0), 900) * np.clip(pt / 0.015, 0, 1) * np.exp(-pt / 0.05)
    m.add(nrm(pff), [0.2, 0.26][v], 0.4)
    y = fit(m.out(), ns(dur))
    return lead_in(street(lp(y, 10000), wet=0.06, seed=430 + v))


# =========================================================================== main
def write_fragments():
    """lamp_sounds.json (the sounds.json entries) and lamp_lang.json (their subtitles), for merging by hand"""
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        names = [f"guildmark:{sound_path(event, v + 1)}" for v in range(r["variants"])]
        entry = [{"name": n, "attenuation_distance": r["attenuation"]} for n in names] if r["attenuation"] else names
        sounds[f"townsfolk.{event}"] = {"sounds": entry, "subtitle": f"subtitles.guildmark.townsfolk.{event}"}
        lang[f"subtitles.guildmark.townsfolk.{event}"] = r["subtitle"]
    for name, data in (("lamp_sounds.json", sounds), ("lamp_lang.json", lang)):
        with open(os.path.join(HERE, name), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


GAP = 0.6


def listen_through(assets, names, path_):
    """one MP3 to listen to: every file just written, read back as the game will play it, a chapter mark at each"""
    pieces, marks, at = [], [], 0.0
    for name in names:
        x = read_audio(os.path.join(assets, "sounds", name + ".ogg"))[0]
        marks.append((at, at + len(x) / SR + GAP, name))
        pieces += [x, np.zeros(ns(GAP))]
        at += len(x) / SR + GAP
    audio = np.concatenate(pieces).astype(np.float32)
    with tempfile.TemporaryDirectory() as td:
        wav, meta, mp3 = os.path.join(td, "all.wav"), os.path.join(td, "chapters.txt"), os.path.join(td, "all.mp3")
        write_wav(wav, audio)
        with open(meta, "w") as f:
            f.write(";FFMETADATA1\ntitle=Guildmark lamplighters\nartist=Guildmark\n")
            for a, b, title in marks:
                f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={int(round(a * 1000))}\nEND={int(round(b * 1000))}\n"
                        f"title={title}\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-i", meta, "-map", "0:a", "-map_metadata", "1",
                        "-map_chapters", "1", "-c:a", "libmp3lame", "-q:a", "2", "-ac", "1", "-id3v2_version", "3",
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", mp3], check=True)
        os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
        shutil.copyfile(mp3, path_)
    print(f"{path_}  {at:.1f}s, {len(marks)} sounds")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--mp3", default=None, help="also write one listen-through MP3 of what was rendered")
    a = ap.parse_args()
    sel = a.only.split(",") if a.only else None
    written = []
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
            written.append(name)
    write_fragments()
    if a.mp3:
        listen_through(a.assets, written, a.mp3)


if __name__ == "__main__":
    main()
