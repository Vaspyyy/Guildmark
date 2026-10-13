"""Guildmark's city ambience, fully synthesised (no samples): bells, crowds and criers, workshops,
the harbour and the wind on the walls.

usage (from the repo root):
    python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark [--only a,b] [--wav DIR] [--png DIR]
        [--raw DIR]

Each module in art/audio/ambience/ registers its sounds with @recipe (see ambkit.py). Files go to
<assets>/sounds/ambience/<name><n>.ogg. --only takes recipe names or prefixes (bell_, gull). --wav also
writes WAV copies, --png a waveform and spectrogram picture of each file. --raw is where
make_city_voices.py tts left the raw speech takes; without it the recipes built on speech are skipped.
--modules loads only the named modules from ambience/ (bells,harbour).

    python3 -B art/audio/make_ambience.py src/main/resources/assets/guildmark --sounds

rewrites the ambience.* entries of sounds.json from the files on disk (EVENTS below says how far each
carries and what its subtitle is), leaving every other entry as it is.
"""
import argparse
import glob
import importlib
import json
import os
import sys
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ambkit  # noqa: E402
from ambkit import RECIPES, SR, Skip, describe, finish, rng_for, spectrogram_png, write_ogg, write_wav  # noqa: E402


# Each event's sounds.json entry: how far it carries in blocks (None for the beds, which play unpositioned),
# its subtitle (subtitles.guildmark.ambience.<key>; the beds have none) and, where an event doesn't simply take
# every <name><n>.ogg, the files it plays. Each bell's two strikes are their own events, so the game can keep to
# one of them through a run of strokes: two strikes a little apart in pitch beat against each other as they
# overlap, like two different bells.
EVENTS = {
    "crowd_day": (None, None),
    "crowd_light": (None, None),
    "harbour_water": (None, None),
    "wind_walls": (None, None),
    "bell_great": (160, "bell", ["bell_great1"]),
    "bell_great_soft": (160, "bell", ["bell_great2"]),
    "bell_chime": (160, "chime"),
    "bell_town": (96, "bell", ["bell_town1"]),
    "bell_town_soft": (96, "bell", ["bell_town2"]),
    "ship_bell": (48, "ship_bell"),
    "anvil_work": (24, "anvil"),
    "bellows": (20, "bellows"),
    "quench": (20, "quench"),
    "grindstone": (20, "grindstone"),
    "saw": (20, "saw"),
    "wood_knock": (24, "hammer"),
    "hawker": (32, "crier"),
    "coins": (16, "coins"),
    "cart": (32, "cart"),
    "splash": (24, "splash"),
    "gull": (64, "gull"),
    "creak": (24, "creak"),
    "gust": (32, "gust"),
    "flag": (20, "flag"),
    "watch_ten": (64, "watch"),
    "watch_eleven": (64, "watch"),
    "watch_midnight": (64, "watch"),
    "watch_one": (64, "watch"),
    "watch_two": (64, "watch"),
    "watch_three": (64, "watch"),
    "watch_four": (64, "watch"),
    "watch_five": (64, "watch"),
}


def write_sounds_json(assets):
    """put an ambience.<name> entry in sounds.json for every event in EVENTS that has files"""
    path = os.path.join(assets, "sounds.json")
    with open(path, encoding="utf-8") as f:
        sounds = json.load(f)

    def exists(stem):
        return os.path.exists(os.path.join(assets, "sounds", "ambience", f"{stem}.ogg"))

    for name, (reach, subtitle, *listed) in EVENTS.items():
        if listed:
            stems = [stem for stem in listed[0] if exists(stem)]
        else:
            stems = []
            while exists(f"{name}{len(stems) + 1}"):
                stems.append(f"{name}{len(stems) + 1}")
        files = [f"guildmark:ambience/{stem}" for stem in stems]
        key = f"ambience.{name}"
        if not files:
            print(f"{key}: no files, entry left out")
            sounds.pop(key, None)
            continue
        entry = {"sounds": files if reach is None else [{"name": f, "attenuation_distance": reach} for f in files]}
        if subtitle:
            entry["subtitle"] = f"subtitles.guildmark.ambience.{subtitle}"
        sounds[key] = entry
        print(f"{key}: {len(files)} file(s)")
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(sounds, indent=2, ensure_ascii=False) + "\n")


def load_modules(only=None):
    for path in sorted(glob.glob(os.path.join(HERE, "ambience", "*.py"))):
        name = os.path.splitext(os.path.basename(path))[0]
        if name.startswith("_") or (only and name not in only):
            continue
        try:
            importlib.import_module(f"ambience.{name}")
        except Exception:
            # one broken module shouldn't stop the others from being made
            print(f"ambience/{name}.py failed to load:", flush=True)
            traceback.print_exc()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("assets")
    ap.add_argument("--only", default=None)
    ap.add_argument("--wav", default=None)
    ap.add_argument("--png", default=None)
    ap.add_argument("--raw", default=None)
    ap.add_argument("--modules", default=None)
    ap.add_argument("--sounds", action="store_true")
    a = ap.parse_args()
    if a.sounds:
        write_sounds_json(a.assets)
        return
    ambkit.RAW_DIR = a.raw
    load_modules(a.modules.split(",") if a.modules else None)
    sel = a.only.split(",") if a.only else None
    for path, r in RECIPES.items():
        if sel and not any(path == s or path.startswith(s) for s in sel):
            continue
        for v in range(r["variants"]):
            t0 = time.time()
            name = f"ambience/{path}{v + 1}"
            try:
                x = r["fn"](v, rng_for("ambience", path, v))
            except Skip as e:
                print(f"{name:34s} skipped: {e}", flush=True)
                break
            y = finish(x, r, v)
            write_ogg(os.path.join(a.assets, "sounds", name + ".ogg"), y, quality=5 if r["loop"] else 6)
            if a.wav:
                write_wav(os.path.join(a.wav, name + ".wav"), y)
            if a.png:
                spectrogram_png(os.path.join(a.png, name + ".png"), y, name)
            print(f"{name:34s} {describe(y, bool(r['loop']))}  {time.time() - t0:5.1f}s", flush=True)
        else:
            # A recipe cut down to fewer variants leaves no old files behind for --sounds to count
            n = r["variants"] + 1
            while os.path.exists(stale := os.path.join(a.assets, "sounds", "ambience", f"{path}{n}.ogg")):
                os.remove(stale)
                print(f"ambience/{path}{n:<{33 - len(path)}} removed (no longer made)", flush=True)
                n += 1


if __name__ == "__main__":
    main()
