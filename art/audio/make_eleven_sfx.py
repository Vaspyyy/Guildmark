"""The creature and combat sound effects recorded with ElevenLabs (dragon, Hill Troll, Dusk Rider, Halvard the
Fallen Warden, the Hollow Soldier, and the swings, hits, blocks, parries and rolls of combat/): trims, levels and
encodes the raw takes into the game's sound files.

usage (from the repo root):
    python3 -B art/audio/make_eleven_sfx.py src/main/resources/assets/guildmark <takes dir> [--only dragon/,hill_troll/roar1]
        [--wav DIR]

<takes dir> holds one MP3 per sound, named <group>__<name>_a.mp3 (dragon__roar1_a.mp3), as the download jobs leave
them. art/audio/eleven_sfx.json lists every sound with the prompt and generation it came from, its longest allowed
length (max_len, seconds) and its loudness (rms_db: the RMS of its loud part, measured on the synthesised sound it
replaced, so the mix stays where it was). Each take is made mono 44.1 kHz, high-passed at 25 Hz, trimmed of leading
silence and of an inaudible tail, cut at max_len with a fade-out if it runs longer, brought to rms_db and held under
a -3 dBFS peak like the other creature sounds, then written as Ogg Vorbis to <assets>/sounds/<group>/<name>.ogg.
Sounds marked one_hit (steps, hooves, bites, swipes, swings, wing beats, blade hits, blocks) keep only the loudest of the separate hits a
take holds, since a take asked for one footstep sometimes comes back as several.

These files replace what make_sfx.py (fallen_warden/, hill_troll/, dusk_rider/, hollow_soldier/, and combat/ but for
lock_on) and make_dragon_audio.py (dragon/) synthesise: re-running those scripts for these groups would put the
synthesised sounds back.
"""
import argparse
import json
import os
import sys

import numpy as np
from scipy import signal
from scipy.ndimage import maximum_filter1d, uniform_filter1d

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sfxkit import SR, _sos, db, fade, limiter, ns, read_audio, write_ogg, write_wav  # noqa: E402

PEAK_DB = -3.0        # the creature sounds' shared peak
MAX_SQUASH_DB = 12.0  # never limit more than this to reach rms_db (only sharp hits get near it); land quieter instead
ONSET_DB = -40.0      # leading silence: everything before the first sample this far under the peak
TAIL_DB = -60.0       # inaudible tail: everything after the last 10 ms window this far under the peak
HIT_DB = -30.0        # one_hit: a hit is louder than this under the peak...
HIT_GAP = 0.06        # ...and two hits are apart when quieter than that for this long (seconds)


def active_rms(x):
    """RMS of the loud part: the 50 ms windows within 20 dB of the loudest one"""
    p = uniform_filter1d(x * x, size=ns(0.05))
    sel = p > p.max() * db(-20.0)
    return float(np.sqrt(np.mean(x[sel] ** 2)))


def loudest_hit(x):
    """The loudest separate hit: runs of 10 ms windows within HIT_DB of the peak, apart when quieter than that for
    HIT_GAP; the kept hit runs on to just before the next one starts, so its decay stays"""
    env = maximum_filter1d(np.abs(x), size=ns(0.01) + 1)
    loud = np.nonzero(env > env.max() * db(HIT_DB))[0]
    if not len(loud):
        return x
    br = np.nonzero(np.diff(loud) > ns(HIT_GAP))[0]
    starts, ends = np.r_[loud[0], loud[br + 1]], np.r_[loud[br], loud[-1]]
    i = int(np.argmax([env[s:e + 1].max() for s, e in zip(starts, ends)]))
    stop = starts[i + 1] - ns(0.02) if i + 1 < len(starts) else len(x)
    return x[max(0, starts[i] - ns(0.005)):stop]


def process(x, max_len, rms_db, one_hit=False):
    x = signal.sosfiltfilt(_sos("hp", 25.0, 2), x)
    if one_hit:
        x = loudest_hit(x)
    a = np.abs(x)
    pk = a.max() + 1e-12
    on = np.nonzero(a > pk * db(ONSET_DB))[0]
    start = max(0, on[0] - ns(0.005)) if len(on) else 0
    alive = np.nonzero(maximum_filter1d(a, size=ns(0.01) + 1) > pk * db(TAIL_DB))[0]
    end = alive[-1] + 1 if len(alive) else len(x)
    x = x[start:end]
    cut = len(x) > ns(max_len)
    if cut:
        x = x[: ns(max_len)]
    x = fade(x, fin=0.005 if start > 0 else 0.0, fout=min(0.4, 0.3 * max_len) if cut else min(0.03, len(x) / SR / 2))
    # the limiter's release also pulls down what follows a sharp hit, so step the gain up until the limited sound
    # reaches rms_db, within MAX_SQUASH_DB of limiting
    g_max = db(PEAK_DB + MAX_SQUASH_DB) / (np.abs(x).max() + 1e-12)
    g = min(db(rms_db) / max(active_rms(x), 1e-9), g_max)
    for _ in range(8):
        y = limiter(x * g, ceiling=db(PEAK_DB))
        short = rms_db - 20 * np.log10(active_rms(y) + 1e-12)
        if short < 0.2 or g >= g_max:
            break
        g = min(g * db(short), g_max)
    over = np.abs(y).max() / db(PEAK_DB)
    return (y / over if over > 1 else y), cut


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("takes")
    ap.add_argument("--only", default="", help="comma list of group/ or group/name")
    ap.add_argument("--wav", default=None, help="also write WAV copies here")
    a = ap.parse_args()
    spec = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "eleven_sfx.json"), encoding="utf-8"))
    only = [o for o in a.only.split(",") if o]
    done = 0
    for key, s in spec["sounds"].items():
        if only and not any(key == o or (o.endswith("/") and key.startswith(o)) for o in only):
            continue
        take = os.path.join(a.takes, key.replace("/", "__") + "_a.mp3")
        if not os.path.exists(take):
            print("missing take", key)
            continue
        x = read_audio(take, sr=SR).mean(axis=0)
        y, cut = process(x, s["max_len"], s["rms_db"], s.get("one_hit", False))
        write_ogg(os.path.join(a.assets, "sounds", key + ".ogg"), y)
        if a.wav:
            write_wav(os.path.join(a.wav, key + ".wav"), y)
        done += 1
        print(f"{key:28s} take {len(x) / SR:5.2f} s -> {len(y) / SR:5.2f} s{' (cut)' if cut else ''}"
              f"  rms {20 * np.log10(active_rms(y)):6.1f} dB (want {s['rms_db']})")
    print(done, "written")


if __name__ == "__main__":
    main()
