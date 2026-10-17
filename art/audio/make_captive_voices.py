"""The captives' voice lines (outlaw/Captives.kt) through the townsfolk voice pipeline: the same functions as
make_townsfolk_voices.py (Kokoro TTS, then process_line, then Ogg), with the captive's lines and their delivery here.

  tts      <venv python, -I -B>: raw 24 kHz takes per variant -> raw/<variant>/<timbre>/<line>.wav
  process  <system python3, -B>: raw take -> assets/sounds/townsfolk/voice/<timbre>/<line>.ogg (+ wav copies)

The shipped lines are take d (`tts --variants d`, then `process --variant d --breath -24`), with an inhale 24 dB under
the speech before the freed lines. Their sounds.json entries are townsfolk.voice.<timbre>.captive_<line>.
"""
import argparse
import os
import sys
import types

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "art", "audio"))
import make_townsfolk_voices as mtv  # noqa: E402

TIMBRES = ("man", "woman", "old")

# the language file's say.guildmark.<line>, word for word
CAPTIVE = {
    "captive_help_0": "Help! Over here!",
    "captive_help_1": "Please, cut me loose!",
    "captive_help_2": "Hurry, before they come back!",
    "captive_freed_0": "Oh, thank you! Thank you!",
    "captive_freed_1": "Get me home, please. I'll keep close.",
    "captive_home_0": "Home! I thought I'd never see it again.",
    "captive_home_1": "Bless you, friend. The whole village will hear of this.",
}


def kind(line):
    return line.split("_")[1]            # help | freed | home


# Delivery by kind. speed: times the timbre's own TTS speed. pitch: cents added to the timbre's processing (frightened:
# a little higher and tighter; close to tears: a little lower). tremor: cents of wobble in the voice (added to the timbre's
# own). level: dB over the common speech level (cries carry). wet: the street's reverb (a close, warm line is drier).
DELIVERY = {
    "help": dict(speed=1.12, pitch=110, tremor=0, level=2.0, wet=0.06),
    "freed": dict(speed=1.08, pitch=40, tremor=12, level=1.0, wet=0.06),
    "home": dict(speed=0.90, pitch=-40, tremor=30, level=-1.0, wet=0.04),
}
LEVEL_FOR = {"captive_freed_1": 0.0}

# How a variant changes the take: a speed factor per kind over the delivery's, and the text given to the TTS (the
# punctuation where the line should breathe; the words are the written ones)
VARIANTS = {
    "a": dict(speed=dict(help=1.0, freed=1.0, home=1.0), text={"captive_home_0": "Home... I thought I'd never see it again."}),
    "b": dict(speed=dict(help=1.1, freed=1.1, home=0.93), text={}),
    # tries for the lines the recogniser stumbles on in the man's voice
    "c1": dict(speed=dict(help=1.0, freed=1.0, home=1.0), text={"captive_help_1": "Please! Cut me loose!", "captive_freed_1": "Get me home, please! I'll keep close."}),
    "c2": dict(speed=dict(help=0.9, freed=0.95, home=1.0), text={"captive_help_1": "Please, cut me loose!", "captive_freed_1": "Get me home, please. I'll keep close."}),
    "c3": dict(speed=dict(help=1.0, freed=1.0, home=1.0), text={"captive_help_1": "Please... cut me loose!", "captive_freed_1": "Get, me home, please. I'll keep close."}),
    "c4": dict(speed=dict(help=1.1, freed=1.0, home=1.0), text={"captive_help_1": "Please, cut me, loose!", "captive_freed_1": "Gett me home, please. I'll keep close."}),
    # the take that is used: quick for the cries and the relief, slow for the homecoming; the man's pleas read with a pause
    # after the first word, which his voice needs to say "cut me" clearly
    "d": dict(speed=dict(help=1.1, freed=1.1, home=0.93), text={"captive_home_0": "Home... I thought I'd never see it again."},
              text_for={("man", "captive_help_1"): "Please... cut me loose!", ("man", "captive_freed_1"): "Get, me home, please. I'll keep close."},
              speed_for={("man", "captive_help_1"): 1.0}),
    "c5": dict(speed=dict(help=0.8, freed=0.9, home=1.0), text={"captive_help_1": "Please, cut me loose!", "captive_freed_1": "Get me home, please. I'll keep close."}),
}


def speed_for(variant, timbre, line):
    k = kind(line)
    f = VARIANTS[variant].get("speed_for", {}).get((timbre, line), VARIANTS[variant]["speed"][k])
    return mtv.TIMBRES[timbre]["speed"] * DELIVERY[k]["speed"] * f


def text_for(variant, line, timbre=None):
    v = VARIANTS[variant]
    return v.get("text_for", {}).get((timbre, line)) or v["text"].get(line, CAPTIVE[line])


def cmd_tts(a):
    import soundfile as sf
    ns = types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data)
    k = mtv.kokoro(ns)
    for variant in a.variants:
        for timbre in TIMBRES:
            t = mtv.TIMBRES[timbre]
            folder = os.path.join(a.out, variant, timbre)
            os.makedirs(folder, exist_ok=True)
            for line in CAPTIVE:
                if a.keys and a.keys not in line:
                    continue
                if a.timbres and timbre not in a.timbres:
                    continue
                said = text_for(variant, line, timbre)
                sp = speed_for(variant, timbre, line)
                audio, sr = k.create(said, voice=t["voice"], speed=sp, lang=t["lang"], is_phonemes=False)
                fn = os.path.join(folder, line + ".wav")
                sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")
                print(fn, f"speed {sp:.3f}  {len(audio) / sr:.2f}s  {said!r}", flush=True)


def inhale(sr, level_db, dur=0.2, seed=5):
    """A short breath in: noise shaped to the throat and mouth (a band of about 400 Hz to 4.5 kHz, steep at the top so it
    does not reach past the voice), swelling quickly and falling away, [level_db] under the speech's own RMS."""
    from scipy import signal
    rng = np.random.default_rng(seed)
    n = int(dur * sr)
    x = rng.standard_normal(n)
    x = signal.sosfilt(signal.butter(4, [400.0, 4500.0], btype="band", fs=sr, output="sos"), x)
    u = np.arange(n) / n
    env = np.where(u < 0.35, (u / 0.35) ** 1.5, np.exp(-(u - 0.35) * 4.0)) * (1.0 - u) ** 0.5
    x = x * env
    rms = np.sqrt(np.mean(x ** 2)) + 1e-12
    return x / rms * 10 ** ((mtv.SPEECH_DB + level_db) / 20)


def render(raw_wav, timbre, line, variant, breath=None):
    """-> mono float signal at OUT_SR, as cmd_process makes it, with this line's delivery."""
    from scipy.io import wavfile
    import warnings
    from sfxkit import SR
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", wavfile.WavFileWarning)
        sr, x = wavfile.read(raw_wav)
    if np.issubdtype(x.dtype, np.integer):
        x = x.astype(np.float64) / float(np.iinfo(x.dtype).max + 1)
    x = x.astype(np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    d = DELIVERY[kind(line)]
    st = dict(mtv.STYLE[timbre])
    st["pitch"] = st["pitch"] + d["pitch"]
    st["tremor"] = st["tremor"] + d["tremor"]
    st["wet"] = d["wet"]
    y = mtv.process_line(x, sr, st, line, timbre, LEVEL_FOR.get(line, d["level"]))
    if breath is not None:
        b = inhale(SR, breath)
        gap = np.zeros(int(0.07 * SR))
        y = np.concatenate([b, gap, y])
    return mtv.sox(y, SR, out_sr=mtv.OUT_SR)


def write_line(y, out):
    from scipy import signal
    from sfxkit import read_audio, write_ogg
    for _ in range(3):  # held under -1 dBFS true peak in the encoded file, the encoder's overshoot too
        write_ogg(out, y, sr=mtv.OUT_SR, quality=3)
        d = read_audio(out, sr=mtv.OUT_SR)[0]
        over = 20 * np.log10(np.max(np.abs(signal.resample_poly(d, 4, 1))) + 1e-12) + 1.0
        if over <= 0:
            break
        y = y * 10 ** (-(over + 0.05) / 20)
    return y


def cmd_process(a):
    from sfxkit import write_wav
    choice = {}
    for item in (a.choose or []):
        key, variant = item.split("=")
        choice[key] = variant
    for timbre in TIMBRES:
        for line in CAPTIVE:
            if a.keys and a.keys not in line:
                continue
            if a.timbres and timbre not in a.timbres:
                continue
            variant = choice.get(f"{timbre}.{line}") or choice.get(line) or a.variant
            raw = os.path.join(a.raw, variant, timbre, line + ".wav")
            breath = a.breath if (a.breath is not None and kind(line) == "freed") else None
            y = render(raw, timbre, line, variant, breath)
            out = os.path.join(a.assets, "sounds", mtv.SOUND_DIR, timbre, line + ".ogg")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            y = write_line(y, out)
            if a.wav:
                write_wav(os.path.join(a.wav, timbre, line + ".wav"), y, sr=mtv.OUT_SR)
            print(f"{out}  variant {variant}  {len(y) / mtv.OUT_SR:.2f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("tts")
    t.add_argument("--model", required=True)
    t.add_argument("--voices", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--espeak-data", default=None)
    t.add_argument("--variants", default="a,b", type=lambda s: s.split(","))
    t.add_argument("--keys", default=None)
    t.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--variant", default="a")
    p.add_argument("--choose", nargs="*", help="[timbre.]line=variant")
    p.add_argument("--breath", type=float, default=None, help="dB under speech for an inhale before the freed lines")
    p.add_argument("--wav", default=None)
    p.add_argument("--keys", default=None)
    p.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    a = ap.parse_args()
    {"tts": cmd_tts, "process": cmd_process}[a.cmd](a)


if __name__ == "__main__":
    main()
