"""The background beds recorded with ElevenLabs, the capitals' (the market crowd, the quieter street, the harbour
water, the wind on the walls) and the villages' (day, night, birdsong, crickets, market): turns the raw takes into
seamless stereo loops.

usage (from the repo root):
    python3 -B art/audio/make_eleven_ambience.py src/main/resources/assets/guildmark <takes dir> [--only crowd_day,village_]
        [--wav DIR] [--png DIR]

<takes dir> holds one MP3 per file, named ambience__<file>_a.mp3 (ambience__crowd_day1_a.mp3, ambience__village_day2_a.mp3).
art/audio/eleven_ambience.json lists each file with the prompt and generation it came from and its loudness (rms_db,
the level its bed's synthesised recipe in ambience/ was made at, so the mix stays where it was). Each
take loses its first `skip` seconds, then goes through ambkit's loop finishing like the synthesised beds: high-passed
at 25 Hz, levelled to rms_db, limited at -1 dBFS on both channels together, and cut to a loop as long as the take
allows with the last XFADE seconds crossfaded (equal power) over the head, so the file runs on into itself with no
seam. Written as Ogg Vorbis (quality 5, like the other beds) to <assets>/sounds/ambience/<file>.ogg.

make_ambience.py leaves these beds alone unless it is given --synth-beds.
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ambkit import SR, describe, finish, read_audio, spectrogram_png, write_ogg, write_wav  # noqa: E402

XFADE = 3.0  # seconds of the take's end crossfaded over its head to close the loop


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("takes")
    ap.add_argument("--only", default="", help="comma list of file names or prefixes")
    ap.add_argument("--wav", default=None)
    ap.add_argument("--png", default=None)
    a = ap.parse_args()
    spec = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "eleven_ambience.json"),
                          encoding="utf-8"))
    only = [o for o in a.only.split(",") if o]
    done = 0
    for f, s in spec["files"].items():
        if only and not any(f.startswith(o) for o in only):
            continue
        take = os.path.join(a.takes, f"ambience__{f}_a.mp3")
        if not os.path.exists(take):
            print("missing take", f)
            continue
        x = np.atleast_2d(read_audio(take, sr=SR))
        if x.shape[0] == 1:
            x = np.vstack([x, x])
        x = x[:2, int(s.get("skip", 0.0) * SR):]
        loop = np.floor((x.shape[1] / SR - XFADE - 0.05) * 10) / 10
        y = finish(x, dict(stereo=True, loop=loop, xfade=XFADE, rms_db=s["rms_db"], ceiling_db=-1.0), 0)
        name = f"ambience/{f}"
        write_ogg(os.path.join(a.assets, "sounds", name + ".ogg"), y, quality=5)
        if a.wav:
            write_wav(os.path.join(a.wav, name + ".wav"), y)
        if a.png:
            spectrogram_png(os.path.join(a.png, name + ".png"), y, name)
        done += 1
        print(f"{name:24s} {describe(y, True)}")
    print(done, "written")


if __name__ == "__main__":
    main()
