"""Halvard, the Fallen Warden: TTS voice lines processed into a hollow undead knight.

Two stages (run from the repo root):

1) TTS with Kokoro (kokoro-onnx, Apache-2.0) -> raw 24 kHz WAVs
   python3 -I art/audio/make_voice.py tts --model kokoro-v1.0.onnx --voices voices-v1.0.bin \
       --out <raw_dir> [--voice bm_george] [--speed 1.0] [--espeak-data <dir>]
   (--voice accepts a blend such as "bm_george*0.7+bm_lewis*0.3")

2) Processing (numpy/scipy + sox) -> mono 44.1 kHz Ogg Vorbis in the mod's assets
   python3 art/audio/make_voice.py process --raw <raw_dir> --assets src/main/resources/assets/guildmark \
       [--wav <dir>]

The processing chain: trim -> resample -> sox `speed` (tape-style slow-down, about -3.4 semitones and
~18% slower) -> EQ -> gentle compression -> helmet comb -> quiet chorused octave-down double ->
gentle saturation -> large stone-hall convolution reverb (ducked under the dry voice) -> limiter.
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

LINES = {
    "halvard_intro": "Another traveller... on my road. The oath still holds me here. Turn back, or be buried beside me.",
    "halvard_phase2": "The crown calls... and I cannot rest!",
    "halvard_death": "The road... is the only home... we keep.",
}

# chosen settings (see README): voice, Kokoro speed, and the sox speed factor
DEFAULT_VOICE = "bm_lewis"
DEFAULT_SPEED = 1.0
SPEED_FACTOR = 0.82  # sox speed: pitch * 0.82 (= -3.4 semitones), duration / 0.82

# per-line shading: phase two is harsher (he is transforming), the death line softer and wetter
STYLE = {
    "halvard_intro": dict(octave_db=-13.0, drive=2.2, drive_mix=0.45, wet=0.36),
    "halvard_phase2": dict(octave_db=-10.5, drive=3.0, drive_mix=0.6, wet=0.34),
    "halvard_death": dict(octave_db=-15.0, drive=1.8, drive_mix=0.3, wet=0.42),
}


# ------------------------------------------------------------------ stage 1: TTS
def parse_voice(k, spec):
    if "*" not in spec and "+" not in spec:
        return spec
    style = None
    for part in spec.split("+"):
        name, _, w = part.partition("*")
        s = k.get_voice_style(name.strip()) * float(w or 1.0)
        style = s if style is None else style + s
    return style


def cmd_tts(a):
    import soundfile as sf
    import espeakng_loader
    from kokoro_onnx import Kokoro, EspeakConfig
    cfg = EspeakConfig(lib_path=espeakng_loader.get_library_path(),
                       data_path=a.espeak_data or espeakng_loader.get_data_path())
    k = Kokoro(a.model, a.voices, espeak_config=cfg)
    os.makedirs(a.out, exist_ok=True)
    voice = parse_voice(k, a.voice)
    lang = "en-gb" if a.voice.strip().startswith("b") else "en-us"
    for key, text in LINES.items():
        audio, sr = k.create(text, voice=voice, speed=a.speed, lang=lang)
        fn = os.path.join(a.out, key + ".wav")
        sf.write(fn, np.asarray(audio, dtype=np.float32), sr)
        print(fn, sr, f"{len(audio) / sr:.2f}s", flush=True)


# ------------------------------------------------------------------ stage 2: processing
def sox(x, sr, *effects, out_sr=None):
    """run a mono float signal through a sox effects chain"""
    from scipy.io import wavfile
    with tempfile.TemporaryDirectory() as td:
        i, o = os.path.join(td, "i.wav"), os.path.join(td, "o.wav")
        wavfile.write(i, sr, np.asarray(x, dtype=np.float32))
        cmd = ["sox", "-q", i, "-e", "floating-point", "-b", "32", o] + [str(e) for e in effects]
        if out_sr:
            cmd += ["rate", "-v", str(out_sr)]
        subprocess.run(cmd, check=True)
        r, y = wavfile.read(o)
    return y.astype(np.float64)


def process_line(x, sr_in, k):
    from sfxkit import (SR, hp, lp, eq, compress, comb, drive, room_ir, limiter, fade, db, ns)
    from scipy import signal
    from scipy.ndimage import uniform_filter1d
    # trim TTS silence (keep 15 ms)
    a = np.abs(x)
    on = np.nonzero(a > a.max() * 10 ** (-42 / 20))[0]
    x = x[max(0, on[0] - int(0.015 * sr_in)): on[-1] + int(0.05 * sr_in)]
    x = 0.7 * x / (np.max(np.abs(x)) + 1e-12)
    # tape-style slow-down = pitch down + slower, then resample to 44.1 kHz
    y = sox(x, sr_in, "speed", SPEED_FACTOR, out_sr=SR)
    y = hp(y, 65, order=2)
    # tone: a little chest, less boxiness, presence for intelligibility
    y = eq(y, "lowshelf", 160, 0.7, 2.0)
    y = eq(y, "peak", 420, 1.0, -2.5)
    y = eq(y, "peak", 2900, 0.9, 3.5)
    y = eq(y, "highshelf", 7000, 0.7, 1.5)
    y /= np.max(np.abs(y)) + 1e-12
    y = compress(y, thresh_db=-20, ratio=3.0, attack=0.004, release=0.12)
    # hollow helmet resonance (subtle)
    y = comb(y, 0.0029, 0.32, 0.18)
    # quiet chorused octave-down double (WSOLA pitch shift keeps it aligned)
    low = sox(y, SR, "pitch", -1200, "lowpass", 1800, "chorus", 0.6, 0.85, 45, 0.4, 0.3, 2.0, "-t",
              55, 0.3, 0.25, 1.4, "-s")
    low = fit_len(low, len(y))
    low = lp(low, 1600)
    low *= np.max(np.abs(y)) / (np.max(np.abs(low)) + 1e-12)
    st = STYLE[k]
    y = y + db(st["octave_db"]) * low
    # gentle saturation, in parallel
    y /= np.max(np.abs(y)) + 1e-12
    y = (1 - st["drive_mix"]) * y + st["drive_mix"] * drive(y, st["drive"], asym=0.04)
    # large stone hall, ducked under the dry voice so words stay clear
    ir = room_ir(rt60=2.9, predelay=0.045, early=18, early_span=0.09, hf_damp=0.55, seed=41, density_ms=40)
    send = hp(lp(y, 6500), 220)
    wet = signal.fftconvolve(send, ir)
    tail = ns(1.25)
    out = np.concatenate([y, np.zeros(tail)])
    wet = fit_len(wet, len(out))
    env = uniform_filter1d(np.abs(np.concatenate([y, np.zeros(tail)])), size=ns(0.05))
    env /= env.max() + 1e-12
    duck = 1.0 - 0.45 * np.clip(env * 2.5, 0, 1)
    duck = uniform_filter1d(duck, size=ns(0.08))
    out = out + st["wet"] * wet * duck
    out = hp(out, 50, order=2)
    out /= np.max(np.abs(out)) + 1e-12
    out = limiter(out * db(3.0), ceiling=db(-1.0), lookahead=0.003, release=0.08)
    out = fade(out, fin=0.004, fout=0.6)
    return out


def fit_len(x, n):
    return x[:n] if len(x) >= n else np.concatenate([x, np.zeros(n - len(x))])


def cmd_process(a):
    from scipy.io import wavfile
    from sfxkit import SR, write_ogg, write_wav
    for key in LINES:
        sr, x = wavfile.read(os.path.join(a.raw, key + ".wav"))
        if np.issubdtype(x.dtype, np.integer):
            x = x.astype(np.float64) / float(np.iinfo(x.dtype).max + 1)
        x = x.astype(np.float64)
        if x.ndim > 1:
            x = x.mean(axis=1)
        y = process_line(x, sr, key)
        out = os.path.join(a.assets, "sounds", "voice", key + ".ogg")
        write_ogg(out, y, quality=6)
        if a.wav:
            write_wav(os.path.join(a.wav, key + ".wav"), y)
        print(f"{out}  {len(y) / SR:.3f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("tts")
    t.add_argument("--model", required=True)
    t.add_argument("--voices", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--voice", default=DEFAULT_VOICE)
    t.add_argument("--speed", type=float, default=DEFAULT_SPEED)
    t.add_argument("--espeak-data", default=None,
                   help="copy of espeakng_loader's espeak-ng-data at a short path (espeak-ng caps the path at 160 chars)")
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--wav", default=None)
    a = ap.parse_args()
    {"tts": cmd_tts, "process": cmd_process}[a.cmd](a)


if __name__ == "__main__":
    main()
