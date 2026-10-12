"""The named characters' voices: Wren, Tobin, Ilsa, Pip and Maren read by Kokoro TTS, each in their own
voice, then given a light touch of processing (a room around them, a little age for Tobin, a chapel for
Ilsa, a ghost's chill for Maren).

The words come straight from the language file, so a voice always says what the dialogue shows. Every
line the dialogue screen speaks is voiced: the introductions, each chapter's request, the "still
waiting" and "all done" lines, the goodbyes when a story ends, the topics, and Maren's scenes.

Stages (run from the repo root):

1) TTS with Kokoro (kokoro-onnx, Apache-2.0) -> raw 24 kHz WAVs, one folder per character
   <venv>/bin/python -I -B art/audio/make_cast_voices.py tts --model kokoro-v1.0.onnx \\
       --voices voices-v1.0.bin --out <raw_dir> [--only wren,pip] [--keys 'topic'] [--espeak-data <dir>]

2) Processing (numpy/scipy + sox) -> mono 32 kHz Ogg Vorbis in the mod's assets
   python3 -B art/audio/make_cast_voices.py process --raw <raw_dir> \\
       --assets src/main/resources/assets/guildmark [--only ...] [--wav <dir>]

3) sounds.json: adds (or refreshes) a voice.<character>.<line> event for every voiced line
   python3 -B art/audio/make_cast_voices.py sounds --assets src/main/resources/assets/guildmark

The dialogue screen plays guildmark:voice.<character>.<line> when it shows that line, if the event
exists; lines without a recording fall back to the typed-text ticks.
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

# Kokoro voice, speed and accent per character
CAST = {
    "wren": dict(voice="af_heart", speed=1.06, lang="en-us"),
    "tobin": dict(voice="bm_george", speed=0.9, lang="en-gb"),
    "ilsa": dict(voice="bf_emma", speed=0.94, lang="en-gb"),
    "pip": dict(voice="bm_fable", speed=1.06, lang="en-gb"),
    "maren": dict(voice="af_bella", speed=0.9, lang="en-us"),
}

# Processing per character: pitch shift in cents (duration kept), room (rt60 s, wet), grit, ghost
STYLE = {
    "wren": dict(pitch=0, rt60=0.45, wet=0.06, grit=0.0, ghost=False),
    "tobin": dict(pitch=-100, rt60=0.6, wet=0.07, grit=0.12, ghost=False),
    "ilsa": dict(pitch=0, rt60=1.6, wet=0.13, grit=0.0, ghost=False),
    "pip": dict(pitch=0, rt60=0.5, wet=0.06, grit=0.0, ghost=False),
    "maren": dict(pitch=-60, rt60=3.2, wet=0.28, grit=0.0, ghost=True),
}

# The lines the dialogue screen speaks (step0..2.note and the like are written, never spoken)
VOICED = re.compile(r"^(intro|step\d|waiting|done|finale|scene\d\.p\d|topic\.[a-z_]+\.\d)$")

# Spelling the TTS reads better than the written text
SAY_AS = {
    "Hmph.": "Humph.",  # otherwise spelled out, H-M-P-H
    "Halvard": "Hal-vard",  # otherwise it comes out as "Harvard"
}

OUT_SR = 32000

LANG = "src/main/resources/assets/guildmark/lang/en_us.json"


def lines(only=None, keys=None):
    """{character: {line key: text}} for every voiced line in the language file ([keys]: a regex to narrow them)."""
    data = json.load(open(LANG, encoding="utf-8"))
    out = {}
    for cid in CAST:
        if only and cid not in only:
            continue
        prefix = f"character.guildmark.{cid}."
        out[cid] = {k[len(prefix):]: v for k, v in data.items()
                    if k.startswith(prefix) and VOICED.match(k[len(prefix):]) and (not keys or re.search(keys, k[len(prefix):]))}
    return out


def file_name(key):
    return key.replace(".", "_")


def spoken(text):
    for a, b in SAY_AS.items():
        text = text.replace(a, b)
    return text


# ------------------------------------------------------------------ stage 1: TTS
def cmd_tts(a):
    import soundfile as sf
    import espeakng_loader
    from kokoro_onnx import Kokoro, EspeakConfig
    cfg = EspeakConfig(lib_path=espeakng_loader.get_library_path(),
                       data_path=a.espeak_data or espeakng_loader.get_data_path())
    k = Kokoro(a.model, a.voices, espeak_config=cfg)
    for cid, keys in lines(a.only, a.keys).items():
        cast = CAST[cid]
        folder = os.path.join(a.out, cid)
        os.makedirs(folder, exist_ok=True)
        for key, text in keys.items():
            audio, sr = k.create(spoken(text), voice=cast["voice"], speed=cast["speed"], lang=cast["lang"])
            fn = os.path.join(folder, file_name(key) + ".wav")
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr)
            print(fn, f"{len(audio) / sr:.2f}s", flush=True)


# ------------------------------------------------------------------ stage 2: processing
def sox(x, sr, *effects, out_sr=None):
    """Runs a mono float signal through a sox effects chain."""
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


def process_line(x, sr_in, st):
    from sfxkit import SR, hp, lp, eq, compress, drive, room_ir, limiter, fade, db, ns
    from scipy import signal
    from scipy.ndimage import uniform_filter1d
    # Trim the TTS silence (keep a breath at each end)
    a = np.abs(x)
    on = np.nonzero(a > a.max() * 10 ** (-40 / 20))[0]
    x = x[max(0, on[0] - int(0.02 * sr_in)): on[-1] + int(0.06 * sr_in)]
    x = 0.7 * x / (np.max(np.abs(x)) + 1e-12)
    effects = ["pitch", st["pitch"]] if st["pitch"] else []
    y = sox(x, sr_in, *effects, out_sr=SR)
    y = hp(y, 70, order=2)
    # Tone: less mud, a little presence and air so the words cut through the game
    y = eq(y, "peak", 320, 1.0, -1.5)
    y = eq(y, "peak", 3000, 0.9, 2.0)
    y = eq(y, "highshelf", 8000, 0.7, 1.0)
    y /= np.max(np.abs(y)) + 1e-12
    y = compress(y, thresh_db=-20, ratio=2.5, attack=0.005, release=0.12)
    if st["grit"]:
        y /= np.max(np.abs(y)) + 1e-12
        y = (1 - st["grit"]) * y + st["grit"] * drive(y, 2.2, asym=0.03)
    if st["ghost"]:
        # A faint octave above, chorused, like the words ringing in cold air (sox wants headroom)
        y = 0.5 * y / (np.max(np.abs(y)) + 1e-12)
        high = sox(y, SR, "pitch", 1200, "highpass", 900, "chorus", 0.6, 0.9, 50, 0.4, 0.25, 2.0, "-t")
        high = fit_len(high, len(y))
        high = lp(high, 7000)
        high *= np.max(np.abs(y)) / (np.max(np.abs(high)) + 1e-12)
        y = y + db(-21.0) * high
        y = 0.5 * y / (np.max(np.abs(y)) + 1e-12)
        y = sox(y, SR, "chorus", 0.8, 0.9, 38, 0.25, 0.2, 1.6, "-s")
    # The room, ducked under the dry voice so the words stay clear
    ir = room_ir(rt60=st["rt60"], predelay=0.012 if st["rt60"] < 1 else 0.03, early=12, early_span=0.05,
                 hf_damp=0.5, seed=17, density_ms=30)
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
    # Every character at the same loudness: speech level to -19 dBFS RMS, peaks held under -1 dBFS
    speech = np.abs(out) > 0.02 * np.max(np.abs(out))
    rms = np.sqrt(np.mean(out[speech] ** 2)) + 1e-12
    out *= db(-19.0) / rms
    out = limiter(out, ceiling=db(-1.0), lookahead=0.003, release=0.08)
    out = fade(out, fin=0.004, fout=min(0.4, tail / SR))
    return out


def cmd_process(a):
    from scipy.io import wavfile
    from sfxkit import SR, write_ogg, write_wav
    for cid, keys in lines(a.only, a.keys).items():
        for key in keys:
            sr, x = wavfile.read(os.path.join(a.raw, cid, file_name(key) + ".wav"))
            if np.issubdtype(x.dtype, np.integer):
                x = x.astype(np.float64) / float(np.iinfo(x.dtype).max + 1)
            x = x.astype(np.float64)
            if x.ndim > 1:
                x = x.mean(axis=1)
            y = process_line(x, sr, STYLE[cid])
            y = sox(y, SR, out_sr=OUT_SR)
            out = os.path.join(a.assets, "sounds", "voice", cid, file_name(key) + ".ogg")
            write_ogg(out, y, sr=OUT_SR, quality=3)
            if a.wav:
                write_wav(os.path.join(a.wav, cid, file_name(key) + ".wav"), y, sr=OUT_SR)
            print(f"{out}  {len(y) / OUT_SR:.2f}s", flush=True)


# ------------------------------------------------------------------ stage 3: sounds.json
def cmd_sounds(a):
    path = os.path.join(a.assets, "sounds.json")
    data = json.load(open(path, encoding="utf-8"))
    for name in [n for n in data if n.startswith("voice.") and n.split(".")[1] in CAST]:
        del data[name]
    count = 0
    for cid, keys in lines().items():
        for key in keys:
            if not os.path.exists(os.path.join(a.assets, "sounds", "voice", cid, file_name(key) + ".ogg")):
                continue
            data[f"voice.{cid}.{key}"] = {
                "sounds": [{"name": f"guildmark:voice/{cid}/{file_name(key)}", "stream": True}],
                "subtitle": f"subtitles.guildmark.voice.{cid}",
            }
            count += 1
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, indent=2) + "\n")
    print(count, "voice events")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("tts")
    t.add_argument("--model", required=True)
    t.add_argument("--voices", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--only", type=lambda s: set(s.split(",")), default=None)
    t.add_argument("--keys", default=None, help="only lines whose key matches this regex")
    t.add_argument("--espeak-data", default=None,
                   help="copy of espeakng_loader's espeak-ng-data at a short path (espeak-ng caps the path at 160 chars)")
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--only", type=lambda s: set(s.split(",")), default=None)
    p.add_argument("--keys", default=None, help="only lines whose key matches this regex")
    p.add_argument("--wav", default=None)
    s = sub.add_parser("sounds")
    s.add_argument("--assets", required=True)
    a = ap.parse_args()
    {"tts": cmd_tts, "process": cmd_process, "sounds": cmd_sounds}[a.cmd](a)


if __name__ == "__main__":
    main()
