"""The townsfolk's voices: what the people of the city say as you pass (greetings, trade cries, gossip,
replies, a laugh, a cry for help), read by Kokoro TTS in four voices, one per timbre, then given a light
touch of processing so they sit in the street (a little age for the old man, a child's pitch for the child).

The game picks the timbre from the townsperson (old women use "woman") and varies the pitch a little per
person, so one voice per timbre is enough. Line lengths do not matter to the game.

Stages (run from the repo root):

1) TTS with Kokoro (kokoro-onnx, Apache-2.0) -> raw 24 kHz WAVs, one folder per timbre
   <venv>/bin/python -I -B art/audio/make_townsfolk_voices.py tts --model kokoro-v1.0.onnx \\
       --voices voices-v1.0.bin --out <raw_dir> [--only man,child] [--keys 'greet'] [--espeak-data <dir>]

2) Processing (numpy/scipy + sox) -> mono 32 kHz Ogg Vorbis in the mod's assets, as
   sounds/townsfolk/voice/<timbre>/<line>.ogg
   python3 -B art/audio/make_townsfolk_voices.py process --raw <raw_dir> \\
       --assets src/main/resources/assets/guildmark [--only ...] [--keys ...] [--wav <dir>]

3) Phonemes: prints what Kokoro will say for each line (after SAY_AS), to catch misreadings
   <venv>/bin/python -I -B art/audio/make_townsfolk_voices.py phonemes [--only ...] [--keys ...] [--espeak-data <dir>]

4) Fragments: writes art/audio/townsfolk_sounds.json (the sounds.json entries for every townsfolk effect, the
   bard's tunes and every voice line) and art/audio/townsfolk_lang.json (their subtitles and every line's text)
   python3 -B art/audio/make_townsfolk_voices.py fragments [--assets src/main/resources/assets/guildmark]

Each line plays as the event townsfolk.voice.<timbre>.<line>, subtitled with the line's own text
(say.guildmark.<line>).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Kokoro voice, speed and accent per timbre. None of these is a named character's voice, a city crier's or the
# night watch's (make_cast_voices.py, make_city_voices.py). The American voices read British phonemes, as the
# city's criers do.
TIMBRES = {
    "man": dict(voice="am_puck", speed=1.0, lang="en-gb"),
    "woman": dict(voice="af_nova", speed=1.12, lang="en-gb"),  # af_nova reads slowly at 1.0
    "old": dict(voice="am_adam", speed=0.86, lang="en-gb"),  # older, slower, lower
    "child": dict(voice="af_jessica", speed=1.1, lang="en-gb"),  # a light woman's voice, quickened, pitched up
}

# Processing per timbre: pitch shift in cents (duration kept), tremor (cents of pitch wobble), grit, the street
STYLE = {
    "man": dict(pitch=0, tremor=0, grit=0.0, hp=75, rt60=0.5, wet=0.06),
    "woman": dict(pitch=0, tremor=0, grit=0.0, hp=90, rt60=0.5, wet=0.06),
    "old": dict(pitch=-120, tremor=13, grit=0.1, hp=70, rt60=0.5, wet=0.06),
    "child": dict(pitch=300, tremor=0, grit=0.0, hp=120, rt60=0.5, wet=0.06),
}

# man, woman and old
COMMON = {
    "greet_0": "Good day to you.",
    "greet_1": "Morning!",
    "greet_2": "Fair weather, isn't it?",
    "greet_3": "Welcome to the city.",
    "greet_4": "Safe travels, adventurer.",
    "greet_5": "Mind how you go.",
    "greet_6": "Ah, one of the guild folk.",
    "greet_7": "Lovely day for it.",
    "night_0": "Late to be about.",
    "night_1": "Evening.",
    "night_2": "Keep to the lit streets.",
    "farmer_0": "Good soil this year.",
    "farmer_1": "Rain's coming. I can feel it.",
    "farmer_2": "Crows, the lot of them.",
    "fisher_0": "They're biting today!",
    "fisher_1": "Nothing yet...",
    "fisher_2": "You should've seen the one that got away.",
    "gossip_0": "Did you hear? A troll in the hills.",
    "gossip_1": "Prices are up again.",
    "gossip_2": "The guild's hiring, they say.",
    "gossip_3": "Hollow soldiers on the north road.",
    "gossip_4": "Lovely weather we're having.",
    "gossip_5": "Have you seen my cat?",
    "gossip_6": "Market's busy today.",
    "gossip_7": "My cousin joined the guild.",
    "gossip_8": "They say the old king's crown was broken.",
    "gossip_9": "Best bread in the city, that bakery.",
    "gossip_10": "A rider in black, at night. I saw it.",
    "gossip_11": "The roads are safer since the guild came.",
    "reply_0": "Is that so?",
    "reply_1": "Hmm.",
    "reply_2": "Really?",
    "reply_3": "Aye.",
    "reply_4": "You don't say.",
    "reply_5": "Ha!",
    "laugh_0": "Ha ha ha!",
    "flee_0": "Help!",
    "flee_1": "Guards! Guards!",
    "flee_2": "Monsters!",
}

# man and woman only
TRADES = {
    "smith_0": "Need a blade sharpened?",
    "smith_1": "Mind the sparks!",
    "smith_2": "Good steel takes time.",
    "baker_0": "Fresh bread! Still warm!",
    "baker_1": "Pies! Hot pies!",
    "baker_2": "Flour everywhere, as always.",
    "merchant_0": "Fine wares, fair prices!",
    "merchant_1": "Come, have a look!",
    "merchant_2": "Best in the city, I swear it!",
    "merchant_3": "Silks and spices from the south!",
    "merchant_4": "Apples! Sweet apples!",
    "merchant_5": "Fresh fish, caught this morning!",
    "guard_0": "Move along.",
    "guard_1": "Keep the peace, now.",
    "guard_2": "All quiet on the walls.",
    "guard_3": "Stay out of trouble.",
    "guard_4": "Watch yourself out there.",
    "noble_0": "Splendid weather.",
    "noble_1": "Have you seen the cathedral? Magnificent.",
    "noble_2": "Hmph. Adventurers.",
    "noble_3": "Charming, simply charming.",
    "inn_0": "What'll it be?",
    "inn_1": "Ale's fresh, stew's hot!",
    "inn_2": "Mind the stairs, love.",
    "inn_3": "Last orders soon!",
    "bard_0": "A song for the road!",
    "bard_1": "Thank you, thank you!",
    "priest_0": "Blessings upon you.",
    "priest_1": "May the light keep you.",
    "priest_2": "Peace be with you.",
    "tavern_0": "Cheers!",
    "tavern_1": "Another round!",
    "tavern_2": "To the guild!",
    "tavern_3": "Ha! Good one.",
    "tavern_4": "My round next time.",
}

# the child only
CHILD = {
    "child_0": "You're it!",
    "child_1": "Can't catch me!",
    "child_2": "Are you a real adventurer?",
    "child_3": "Again! Again!",
    "child_4": "Hee hee hee!",
    "child_5": "Race you to the fountain!",
}

# Spelling the TTS reads better than the written text (the subtitles keep the text as written)
SAY_AS = {
    "Hmph.": "Humph.",  # otherwise spelled out, H-M-P-H
    "Hmm.": "Hmmm.",  # otherwise a weak, swallowed 'h'm'
}

# Level offsets (dB) from the common speech level: cries carry, mutters don't
LEVEL = {
    "flee_0": 2.0, "flee_1": 2.0, "flee_2": 2.0, "baker_0": 1.0, "baker_1": 1.0, "merchant_0": 1.0,
    "merchant_1": 1.0, "merchant_2": 1.0, "merchant_3": 1.0, "merchant_4": 1.0, "merchant_5": 1.0,
    "reply_1": -3.0, "fisher_1": -2.5, "night_1": -1.0,
}

OUT_SR = 32000
SPEECH_DB = -19.0  # speech RMS, as the named characters' voices
SOUND_DIR = "townsfolk/voice"
HERE = os.path.dirname(os.path.abspath(__file__))


def lines(only=None, keys=None):
    """{timbre: {line: text}} ([only]: timbres, [keys]: a regex on the line names)"""
    out = {}
    for timbre in TIMBRES:
        if only and timbre not in only:
            continue
        if timbre == "child":
            d = dict(CHILD)
        else:
            d = dict(COMMON, **(TRADES if timbre in ("man", "woman") else {}))
        out[timbre] = {k: v for k, v in d.items() if not keys or re.search(keys, k)}
    return out


def spoken(text):
    for a, b in SAY_AS.items():
        text = text.replace(a, b)
    return text


def kokoro(a):
    import espeakng_loader
    from kokoro_onnx import Kokoro, EspeakConfig
    cfg = EspeakConfig(lib_path=espeakng_loader.get_library_path(),
                       data_path=a.espeak_data or espeakng_loader.get_data_path())
    return Kokoro(a.model, a.voices, espeak_config=cfg)


# ------------------------------------------------------------------ stage 1: TTS
def cmd_tts(a):
    import soundfile as sf
    k = kokoro(a)
    for timbre, keys in lines(a.only, a.keys).items():
        t = TIMBRES[timbre]
        folder = os.path.join(a.out, timbre)
        os.makedirs(folder, exist_ok=True)
        for key, text in keys.items():
            audio, sr = k.create(spoken(text), voice=t["voice"], speed=t["speed"], lang=t["lang"])
            fn = os.path.join(folder, key + ".wav")
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
            print(fn, f"{len(audio) / sr:.2f}s", flush=True)


def cmd_phonemes(a):
    k = kokoro(a)
    for timbre, keys in lines(a.only, a.keys).items():
        for key, text in keys.items():
            ph = k.tokenizer.phonemize(spoken(text), TIMBRES[timbre]["lang"])
            print(f"{timbre:6s} {key:12s} {text:45s} {ph}")


# ------------------------------------------------------------------ stage 2: processing
def sox(x, sr, *effects, out_sr=None):
    """runs a mono float signal through a sox effects chain"""
    from scipy.io import wavfile
    with tempfile.TemporaryDirectory() as td:
        i, o = os.path.join(td, "i.wav"), os.path.join(td, "o.wav")
        wavfile.write(i, sr, np.asarray(x, dtype=np.float32))
        cmd = ["sox", "-q", i, "-e", "floating-point", "-b", "32", o] + [str(e) for e in effects]
        if out_sr:
            cmd += ["rate", "-v", str(out_sr)]
        subprocess.run(cmd, check=True)
        _, y = wavfile.read(o)
    return y.astype(np.float64)


def fit_len(x, n):
    return x[:n] if len(x) >= n else np.concatenate([x, np.zeros(n - len(x))])


def tremor(y, cents, rng, rate=5.3):
    """an old voice's unsteadiness: the pitch wobbles a few cents (the read-out speed swings around 1, so the
    length is kept) and the level with it"""
    from sfxkit import SR, smooth_random
    n = len(y)
    t = np.arange(n) / SR
    depth = (2 ** (cents / 1200.0) - 1) * (0.6 + 0.4 * smooth_random(n / SR, rng, 1.2))
    wob = np.sin(2 * np.pi * rate * t + rng.uniform(0, 2 * np.pi) + 0.8 * smooth_random(n / SR, rng, 2.0))
    pos = np.clip(np.cumsum(1.0 + depth * wob) - 1.0, 0, n - 1)
    return np.interp(pos, np.arange(n), y) * (1.0 + 0.06 * wob)


def process_line(x, sr_in, st, key, timbre, offset_db=0.0):
    from sfxkit import SR, hp, lp, eq, compress, drive, room_ir, limiter, fade, db, ns, rng_for
    from scipy import signal
    from scipy.ndimage import uniform_filter1d
    # Trim the TTS silence (keep a breath at each end)
    a = np.abs(x)
    on = np.nonzero(a > a.max() * 10 ** (-40 / 20))[0]
    x = x[max(0, on[0] - int(0.02 * sr_in)): on[-1] + int(0.06 * sr_in)]
    # Kokoro's vocoder leaves thin steady whistles at 4.8 and 9.6 kHz: notch them out
    for f0 in (4800.0, 9600.0):
        if f0 < 0.45 * sr_in:
            x = signal.filtfilt(*signal.iirnotch(f0, 30.0, fs=sr_in), x)
    x = 0.7 * x / (np.max(np.abs(x)) + 1e-12)
    effects = ["pitch", st["pitch"]] if st["pitch"] else []
    y = sox(x, sr_in, *effects, out_sr=SR)
    if st["tremor"]:
        y = tremor(y, st["tremor"], rng_for("townsfolk", "voice", timbre, key))
    y = hp(y, st["hp"], order=2)
    # Tone: less mud, a little presence and air so the words cut through the game
    y = eq(y, "peak", 320, 1.0, -1.5)
    y = eq(y, "peak", 3000, 0.9, 2.0)
    y = eq(y, "highshelf", 8000, 0.7, 1.0)
    y /= np.max(np.abs(y)) + 1e-12
    y = compress(y, thresh_db=-20, ratio=2.5, attack=0.005, release=0.12)
    if st["grit"]:
        y /= np.max(np.abs(y)) + 1e-12
        y = (1 - st["grit"]) * y + st["grit"] * drive(y, 2.2, asym=0.03)
    # A street between houses: a slap off the walls and a short tail, ducked under the words
    ir = room_ir(rt60=st["rt60"], predelay=0.012, early=12, early_span=0.05, hf_damp=0.55, seed=23,
                 density_ms=30)
    send = hp(lp(y, 7000), 200)
    wet = signal.fftconvolve(send, ir)
    tail = ns(min(1.2, st["rt60"] * 0.5))
    out = np.concatenate([y, np.zeros(tail)])
    wet = fit_len(wet, len(out))
    env = uniform_filter1d(np.abs(out), size=ns(0.05))
    env /= env.max() + 1e-12
    duck = uniform_filter1d(1.0 - 0.4 * np.clip(env * 2.5, 0, 1), size=ns(0.08))
    out = out + st["wet"] * wet * duck
    out = hp(out, 60, order=2)
    # Everyone at the same loudness (cries a little louder, mutters softer), peaks held under -1 dBFS
    speech = np.abs(out) > 0.02 * np.max(np.abs(out))
    rms = np.sqrt(np.mean(out[speech] ** 2)) + 1e-12
    out *= db(SPEECH_DB + offset_db) / rms
    out = limiter(out, ceiling=db(-1.0), lookahead=0.003, release=0.08)
    out = fade(out, fin=0.004, fout=min(0.4, tail / SR))
    return out


def cmd_process(a):
    import warnings
    from scipy import signal
    from scipy.io import wavfile
    from sfxkit import SR, read_audio, write_ogg, write_wav
    for timbre, keys in lines(a.only, a.keys).items():
        for key in keys:
            with warnings.catch_warnings():  # the float WAV's PEAK chunk, which scipy skips
                warnings.simplefilter("ignore", wavfile.WavFileWarning)
                sr, x = wavfile.read(os.path.join(a.raw, timbre, key + ".wav"))
            if np.issubdtype(x.dtype, np.integer):
                x = x.astype(np.float64) / float(np.iinfo(x.dtype).max + 1)
            x = x.astype(np.float64)
            if x.ndim > 1:
                x = x.mean(axis=1)
            y = process_line(x, sr, STYLE[timbre], key, timbre, LEVEL.get(key, 0.0))
            y = sox(y, SR, out_sr=OUT_SR)
            out = os.path.join(a.assets, "sounds", SOUND_DIR, timbre, key + ".ogg")
            for _ in range(3):  # held under -1 dBFS true peak in the encoded file, the encoder's overshoot too
                write_ogg(out, y, sr=OUT_SR, quality=3)
                d = read_audio(out, sr=OUT_SR)[0]
                over = 20 * np.log10(np.max(np.abs(signal.resample_poly(d, 4, 1))) + 1e-12) + 1.0
                if over <= 0:
                    break
                y = y * 10 ** (-(over + 0.05) / 20)
            if a.wav:
                write_wav(os.path.join(a.wav, timbre, key + ".wav"), y, sr=OUT_SR)
            print(f"{out}  {len(y) / OUT_SR:.2f}s", flush=True)


# ------------------------------------------------------------------ stage 4: the fragments the lead merges
def cmd_fragments(a):
    """townsfolk_sounds.json (sounds.json entries, keys without the namespace) and townsfolk_lang.json"""
    from make_townsfolk_sfx import EVENTS, sound_path
    sounds, lang = {}, {}
    for event, r in EVENTS.items():
        entries = []
        for v in range(r["variants"]):
            s = {"name": "guildmark:" + sound_path(event, v + 1)}
            if r["stream"]:
                s["stream"] = True
            if r["attenuation"]:
                s["attenuation_distance"] = r["attenuation"]
            entries.append(s if len(s) > 1 else s["name"])
        key = f"townsfolk.{event}"
        sounds[key] = {"sounds": entries, "subtitle": f"subtitles.guildmark.{key}"}
        lang[f"subtitles.guildmark.{key}"] = r["subtitle"]
    missing = []
    for timbre, keys in lines().items():
        for key, text in keys.items():
            name = f"{SOUND_DIR}/{timbre}/{key}"
            if a.assets and not os.path.exists(os.path.join(a.assets, "sounds", name + ".ogg")):
                missing.append(name)
            sounds[f"townsfolk.voice.{timbre}.{key}"] = {"sounds": [f"guildmark:{name}"],
                                                         "subtitle": f"say.guildmark.{key}"}
            lang[f"say.guildmark.{key}"] = text
    for path, data in ((os.path.join(HERE, "townsfolk_sounds.json"), sounds),
                       (os.path.join(HERE, "townsfolk_lang.json"), lang)):
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(path, len(data), "entries")
    if missing:
        print(len(missing), "voice files not rendered yet, e.g.", missing[:3])


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("tts", "phonemes"):
        t = sub.add_parser(name)
        t.add_argument("--model", required=True)
        t.add_argument("--voices", required=True)
        if name == "tts":
            t.add_argument("--out", required=True)
        t.add_argument("--only", type=lambda s: set(s.split(",")), default=None)
        t.add_argument("--keys", default=None, help="only lines whose name matches this regex")
        t.add_argument("--espeak-data", default=None,
                       help="copy of espeakng_loader's espeak-ng-data at a short path (espeak-ng caps the path at "
                            "160 chars)")
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--only", type=lambda s: set(s.split(",")), default=None)
    p.add_argument("--keys", default=None, help="only lines whose name matches this regex")
    p.add_argument("--wav", default=None)
    f = sub.add_parser("fragments")
    f.add_argument("--assets", default="src/main/resources/assets/guildmark",
                   help="checks that every voice line has been rendered there")
    a = ap.parse_args()
    {"tts": cmd_tts, "phonemes": cmd_phonemes, "process": cmd_process, "fragments": cmd_fragments}[a.cmd](a)


if __name__ == "__main__":
    main()
