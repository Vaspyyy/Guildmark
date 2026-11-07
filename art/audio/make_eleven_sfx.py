"""The sound effects recorded with ElevenLabs: trims, levels and encodes the raw takes into the game's sound files.
First the creatures and combat (dragon, Hill Troll, Dusk Rider, Halvard the Fallen Warden, the Hollow Soldier, and
the swings, hits, blocks, parries and rolls of combat/), then nearly everything else that was synthesised: caravans,
the kitchen, trade, works, islands, ships and sea monsters, magic, factions, companions, homesteads, streets, stalls,
villages, the city's one-shots (bells, smithy, harbour), wardfires, dungeons and the menu sounds.

usage (from the repo root):
    python3 -B art/audio/make_eleven_sfx.py src/main/resources/assets/guildmark <takes dir> [--only dragon/,hill_troll/roar1]
        [--wav DIR]

<takes dir> holds one MP3 per sound, named after its path with each / as __ (dragon__roar1_a.mp3,
cook__plop__soft1_a.mp3), as the download jobs leave them. art/audio/eleven_sfx.json lists every sound with the prompt and generation it came from, its longest allowed
length (max_len, seconds) and its loudness (rms_db: the RMS of its loud part, measured on the synthesised sound it
replaced, so the mix stays where it was). Each take is made mono 44.1 kHz, high-passed at 25 Hz, trimmed of leading
silence and of an inaudible tail, cut at max_len with a fade-out if it runs longer, brought to rms_db and held under
a peak (peak_db, -3 dBFS for the creatures and combat, -1 for the rest, as their synthesised sounds had), then
written as Ogg Vorbis to <assets>/sounds/<path>.ogg.
Sounds marked one_hit (steps, hooves, bites, swipes, swings, wing beats, blade hits, blocks) keep only the loudest of the separate hits a
take holds, since a take asked for one footstep sometimes comes back as several.

Sounds marked loop (a wagon rolling, a pot simmering, a cog's wake, a beacon burning, the island beds...) are played
round and round by the game: their takes were asked for as loops, and each is high-passed, levelled by its RMS over
the whole file (rms_db, the synthesised loop's), limited under -1 dBFS and cut to as long a loop as the take allows,
its last XFADE seconds crossfaded over its head, as make_eleven_ambience.py does the city beds. Mono, so that the game
places them, except those marked stereo.

These files replace what the synthesising scripts made (make_sfx.py, make_dragon_audio.py, make_caravan_sfx.py,
make_cook_audio.py and the rest). Those scripts now leave every file listed in eleven_sfx.json alone (sfxkit.write_ogg
skips them unless GUILDMARK_SYNTH_ALL=1 is set), so re-running one only rebuilds what is still synthesised.
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
from ambkit import finish  # noqa: E402

PEAK_DB = -3.0        # the creature sounds' shared peak
MAX_SQUASH_DB = 12.0  # never limit more than this to reach rms_db (only sharp hits get near it); land quieter instead
ONSET_DB = -40.0      # leading silence: everything before the first sample this far under the peak
TAIL_DB = -60.0       # inaudible tail: everything after the last 10 ms window this far under the peak
HIT_DB = -30.0        # one_hit: a hit is louder than this under the peak...
HIT_GAP = 0.06        # ...and two hits are apart when quieter than that for this long (seconds)
XFADE = 3.0           # loops: seconds of the take's end crossfaded over its head (less for short takes)


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


def process(x, max_len, rms_db, one_hit=False, peak_db=PEAK_DB):
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
    g_max = db(peak_db + MAX_SQUASH_DB) / (np.abs(x).max() + 1e-12)
    g = min(db(rms_db) / max(active_rms(x), 1e-9), g_max)
    for _ in range(8):
        y = limiter(x * g, ceiling=db(peak_db))
        short = rms_db - 20 * np.log10(active_rms(y) + 1e-12)
        if short < 0.2 or g >= g_max:
            break
        g = min(g * db(short), g_max)
    over = np.abs(y).max() / db(peak_db)
    return (y / over if over > 1 else y), cut


def process_loop(x, rms_db, stereo=False):
    """a take asked for as a loop, made a seamless one as long as the take allows (x is (channels, n))"""
    x = np.vstack([x, x])[:2] if stereo and x.shape[0] == 1 else (x[:2] if stereo else x.mean(axis=0))
    secs = x.shape[-1] / SR
    xfade = min(XFADE, round(0.15 * secs, 1))
    loop = np.floor((secs - xfade - 0.05) * 10) / 10
    return finish(x, dict(stereo=stereo, loop=loop, xfade=xfade, rms_db=rms_db, ceiling_db=-1.0), 0)


def rms(y):
    return float(np.sqrt(np.mean(np.asarray(y) ** 2)))


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
        x = read_audio(take, sr=SR)
        if s.get("loop"):
            y = process_loop(x, s["rms_db"], s.get("stereo", False))
            got, cut = 20 * np.log10(rms(y)), False
        else:
            x = x.mean(axis=0)
            y, cut = process(x, s["max_len"], s["rms_db"], s.get("one_hit", False), s.get("peak_db", PEAK_DB))
            got = 20 * np.log10(active_rms(y))
        write_ogg(os.path.join(a.assets, "sounds", key + ".ogg"), y, quality=5 if s.get("loop") else 6, force=True)
        if a.wav:
            write_wav(os.path.join(a.wav, key + ".wav"), y)
        done += 1
        print(f"{key:28s} take {x.shape[-1] / SR:5.2f} s -> {y.shape[-1] / SR:5.2f} s{' (cut)' if cut else ''}"
              f"{' loop' if s.get('loop') else ''}  rms {got:6.1f} dB (want {s['rms_db']})")
    print(done, "written")


if __name__ == "__main__":
    main()
