"""Silvan Marrow, the Unbound: a renegade Starwatch mage, gaunt and bitter, brilliant and half mad from the forbidden
star chart. Not a monster (Halvard is the monster): a man's voice, made cold and a little uncanny, standing out in
the open in a ring of standing stones on high ground.

Two stages (run from the repo root), modelled on make_voice.py:

1) TTS with Kokoro (kokoro-onnx, Apache-2.0) -> raw 24 kHz WAVs
   <venv>/bin/python -I -B art/audio/make_renegade_voice.py tts --model kokoro-v1.0.onnx --voices voices-v1.0.bin \\
       --out <raw_dir> [--voice "am_liam*0.4+am_fenrir*0.3+am_santa*0.3"] [--espeak-data <dir>]
   (--voice accepts a blend such as "bm_george*0.7+bm_lewis*0.3": name*weight terms, summed)

2) Processing (numpy/scipy + sox) -> mono 44.1 kHz Ogg Vorbis in the mod's assets, as sounds/voice/renegade_<line>.ogg
   python3 -B art/audio/make_renegade_voice.py process --raw <raw_dir> --assets src/main/resources/assets/guildmark \\
       [--wav <dir>]

The processing chain: trim -> sox pitch (a little per line) -> EQ (thin and cold: less chest, a hard edge, some
air) -> gentle compression -> a touch of grit -> a faint whispered double (the voice's own band envelopes driving
noise, formants a little higher, a hair late) -> on the phase-two line a thin glassy shimmer (the voice's high
band ringing through a few glass-like resonances) -> a stone slap (a handful of hard, bright echoes off the
standing stones and a short scattered tail, no hall) -> wind across the hill (gusting, with a faint whistle
through the stones), ducked under the words -> RMS level -> limiter.
"""
import argparse
import os
import subprocess
import sys
import tempfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

LINES = {
    "renegade_intro": "Idris sent you? Of course he did. He never could bear to look at the dark himself.",
    "renegade_phase2": "Look up! Watch them go out, one by one!",
    "renegade_death": "Tell him... the chart was true...",
}

# Silvan's own voice: three American men blended, no one else's in the game (make_voice.py, make_cast_voices.py,
# make_townsfolk_voices.py, make_city_voices.py). American phonemes: read from British ones the voices turned
# "dark" into "dock" and "chart" into "shot".
DEFAULT_VOICE = "am_liam*0.4+am_fenrir*0.3+am_santa*0.3"
LANG = "en-us"
# Kokoro speed per line: the intro measured and dry, the cry quicker, the last words slow
SPEED = {"renegade_intro": 0.95, "renegade_phase2": 1.0, "renegade_death": 0.9}

# Spelling the TTS reads better than the written text (the subtitles keep the text as written)
SAY_AS = {
    "Idris": "Ydris",  # otherwise "eye-dris"
}

OUT_SR = 44100
SPEECH_DB = -13.0  # speech RMS: the bosses speak up (as Halvard does), the dialogue is -19
SOUND_DIR = "voice"

# per-line shading: pitch (cents, duration kept), tremor (cents of pitch wobble), grit, the whispered double's
# level (dB under the voice), the glassy shimmer (dB, None = none), the stone slap (wet) and the wind (dB under
# the voice's speech level)
STYLE = {
    "renegade_intro": dict(pitch=-30, tremor=0, grit=0.06, whisper_db=-23.0, glass_db=None, wet=0.22, wind_db=-23.0),
    "renegade_phase2": dict(pitch=60, tremor=7, grit=0.12, whisper_db=-21.0, glass_db=-26.0, wet=0.25, wind_db=-21.0),
    "renegade_death": dict(pitch=-200, tremor=9, grit=0.05, whisper_db=-20.0, glass_db=None, wet=0.25, wind_db=-21.0),
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


def spoken(text):
    for a, b in SAY_AS.items():
        text = text.replace(a, b)
    return text


def cmd_tts(a):
    import soundfile as sf
    import espeakng_loader
    from kokoro_onnx import Kokoro, EspeakConfig
    cfg = EspeakConfig(lib_path=espeakng_loader.get_library_path(),
                       data_path=a.espeak_data or espeakng_loader.get_data_path())
    k = Kokoro(a.model, a.voices, espeak_config=cfg)
    os.makedirs(a.out, exist_ok=True)
    voice = parse_voice(k, a.voice)
    for key, text in LINES.items():
        audio, sr = k.create(spoken(text), voice=voice, speed=SPEED[key] * a.speed, lang=LANG)
        fn = os.path.join(a.out, key + ".wav")
        sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
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
        _, y = wavfile.read(o)
    return y.astype(np.float64)


def fit_len(x, n):
    return x[:n] if len(x) >= n else np.concatenate([x, np.zeros(n - len(x))])


def tremor(y, cents, rng, rate=6.1):
    """a nervous unsteadiness: the pitch wobbles a few cents (the read-out speed swings around 1, so the length
    is kept) and the level with it"""
    from sfxkit import SR, smooth_random
    n = len(y)
    t = np.arange(n) / SR
    depth = (2 ** (cents / 1200.0) - 1) * (0.6 + 0.4 * smooth_random(n / SR, rng, 1.5))
    wob = np.sin(2 * np.pi * rate * t + rng.uniform(0, 2 * np.pi) + 0.9 * smooth_random(n / SR, rng, 2.5))
    pos = np.clip(np.cumsum(1.0 + depth * wob) - 1.0, 0, n - 1)
    return np.interp(pos, np.arange(n), y) * (1.0 + 0.05 * wob)


def whisper_double(y, rng, shift=1.13, bands=24, lo=200.0, hi=7400.0, delay=0.022):
    """a whispered double: the voice's own band envelopes drive noise in bands shifted up by `shift` (a smaller,
    higher mouth), so it says the same words breathily and a little above the voice, a hair late"""
    from sfxkit import SR, bp, hp, lp, white, ns
    n = len(y)
    edges = np.geomspace(lo, hi, bands + 1)
    noise = white(n / SR, rng)
    out = np.zeros(n)
    for i in range(bands):
        a, b = edges[i], edges[i + 1]
        env = lp(np.abs(bp(y, a, b, order=2)), 60.0, order=2)
        car = bp(noise, min(a * shift, SR * 0.45), min(b * shift, SR * 0.46), order=2)
        out += np.clip(env, 0, None) * car / (np.sqrt(np.mean(car ** 2)) + 1e-12)
    out = hp(out, 900.0, order=2)
    d = ns(delay)
    return np.concatenate([np.zeros(d), out])[:n]


def glass(y, rng):
    """a thin glassy shimmer: the voice's high band rings a few high, glass-like resonances (a little out of
    tune with each other), swaying slowly"""
    from sfxkit import SR, hp, lp, resonators, smooth_random
    n = len(y)
    ex = hp(y, 1900.0, order=2)
    freqs = np.array([2310.0, 3470.0, 5130.0, 6940.0]) * rng.uniform(0.985, 1.015, 4)
    ring = resonators(ex, freqs, t60s=[0.5, 0.42, 0.34, 0.26], gains=[1.0, 0.8, 0.6, 0.45])
    sway = 0.65 + 0.35 * np.sin(2 * np.pi * 6.3 * np.arange(n) / SR + 2 * np.pi * smooth_random(n / SR, rng, 1.0))
    return lp(ring * sway, 9000.0, order=2)


def stone_ir(rng):
    """the ring of standing stones on a hilltop: a handful of hard, bright echoes off the nearest stones (a few
    metres each, so 30 to 150 ms) and a short scattered tail, nothing like a hall"""
    from sfxkit import SR, bp, hp, lp, ns, room_ir
    n = ns(0.75)
    ir = np.zeros(n)
    taps = [(0.034, 0.55), (0.058, 0.40), (0.091, 0.30), (0.127, 0.21), (0.171, 0.14)]
    for t, g in taps:
        t *= rng.uniform(0.97, 1.03)
        burst = bp(rng.standard_normal(ns(0.012)), 900.0, 6500.0, order=2) * np.exp(-np.arange(ns(0.012)) / (SR * 0.0028))
        i = ns(t)
        ir[i:i + len(burst)] += g * burst / (np.max(np.abs(burst)) + 1e-12) * rng.choice([-1.0, 1.0])
    tail = room_ir(rt60=0.5, predelay=0.03, early=0, early_span=0.05, hf_damp=0.7, seed=61, density_ms=45, length=0.7)
    tail = hp(lp(tail, 5200.0), 260.0)
    tail /= np.sqrt(np.sum(tail ** 2)) + 1e-12
    ir += 0.28 * fit_len(tail, n) / (np.max(np.abs(tail)) + 1e-12) * np.max(np.abs(ir))
    return ir / (np.sqrt(np.sum(ir ** 2)) + 1e-12)  # unit energy, so `wet` is the slap's level against the voice


def wind(dur, rng):
    """wind across the hill: gusting, band-passed noise whose centre wanders, and a faint whistle through the stones"""
    from sfxkit import colored, smooth_random, tvf, white, fade
    gust = smooth_random(dur, rng, 0.55, 0.12, 1.0) ** 1.6
    fc = 260.0 + 620.0 * smooth_random(dur, rng, 0.8)
    w = tvf(colored(dur, rng, -4.5), "bp", fc, 0.7) * gust
    w /= np.sqrt(np.mean(w ** 2)) + 1e-12
    whistle = tvf(white(dur, rng), "bp", 1180.0 + 260.0 * smooth_random(dur, rng, 0.35), 28.0)
    whistle *= (smooth_random(dur, rng, 0.5, 0.0, 1.0) ** 3) * gust
    whistle /= np.sqrt(np.mean(whistle ** 2)) + 1e-12
    return w + 0.12 * whistle


def process_line(x, sr_in, key):
    from sfxkit import SR, hp, lp, eq, compress, drive, limiter, fade, db, ns, rng_for
    from scipy import signal
    from scipy.ndimage import uniform_filter1d
    st = STYLE[key]
    rng = rng_for("renegade", "voice", key)
    # trim the TTS silence (keep a breath at each end), normalise
    a = np.abs(x)
    on = np.nonzero(a > a.max() * 10 ** (-42 / 20))[0]
    x = x[max(0, on[0] - int(0.015 * sr_in)): on[-1] + int(0.05 * sr_in)]
    # Kokoro's vocoder leaves thin steady whistles at 4.8 and 9.6 kHz: notch them out
    for f0 in (4800.0, 9600.0):
        if f0 < 0.45 * sr_in:
            x = signal.filtfilt(*signal.iirnotch(f0, 30.0, fs=sr_in), x)
    x = 0.7 * x / (np.max(np.abs(x)) + 1e-12)
    y = sox(x, sr_in, *(["pitch", st["pitch"]] if st["pitch"] else []), out_sr=SR)
    if st["tremor"]:
        y = tremor(y, st["tremor"], rng)
    y = hp(y, 85, order=2)
    # tone: thin and cold (less chest and body, a hard edge for the consonants, some air)
    y = eq(y, "lowshelf", 170, 0.7, -2.5)
    y = eq(y, "peak", 380, 1.0, -2.0)
    y = eq(y, "peak", 2900, 0.9, 3.0)
    y = eq(y, "highshelf", 6500, 0.7, 2.0)
    y /= np.max(np.abs(y)) + 1e-12
    y = compress(y, thresh_db=-20, ratio=2.5, attack=0.004, release=0.12)
    if st["grit"]:
        y /= np.max(np.abs(y)) + 1e-12
        y = (1 - st["grit"]) * y + st["grit"] * drive(y, 2.4, asym=0.04)
    speech = np.abs(y) > 0.02 * np.max(np.abs(y))
    voice_rms = np.sqrt(np.mean(y[speech] ** 2)) + 1e-12
    # the whispered double and, on the cry, the glassy shimmer
    w = whisper_double(y, rng)
    y = y + db(st["whisper_db"]) * voice_rms * w / (np.sqrt(np.mean(w[speech] ** 2)) + 1e-12)
    if st["glass_db"] is not None:
        g = glass(y, rng)
        y = y + db(st["glass_db"]) * voice_rms * g / (np.sqrt(np.mean(g[speech] ** 2)) + 1e-12)
    # the stone slap, ducked a little under the words so they stay clear
    ir = stone_ir(rng)
    send = hp(lp(y, 6800), 300)
    tail = ns(0.9)
    out = np.concatenate([y, np.zeros(tail)])
    wet = fit_len(signal.fftconvolve(send, ir), len(out))
    env = uniform_filter1d(np.abs(out), size=ns(0.05))
    env /= env.max() + 1e-12
    duck = uniform_filter1d(1.0 - 0.35 * np.clip(env * 2.5, 0, 1), size=ns(0.08))
    out = out + st["wet"] * wet * duck
    # wind across the hill: rises as he speaks, ducked under the words, blows out after
    wnd = wind(len(out) / SR, rng)
    swell = uniform_filter1d(np.clip(env * 3.0, 0, 1), size=ns(0.2))
    wgate = 0.45 + 0.55 * swell
    wgate *= np.minimum(1.0, np.arange(len(out)) / ns(0.12))
    wduck = uniform_filter1d(1.0 - 0.5 * np.clip(env * 2.5, 0, 1), size=ns(0.06))
    out = out + db(st["wind_db"]) * voice_rms * wnd * wgate * wduck
    out = hp(out, 55, order=2)
    # the bosses speak up: one speech level, peaks held under -1 dBFS
    sp = np.abs(out) > 0.02 * np.max(np.abs(out))
    rms = np.sqrt(np.mean(out[sp] ** 2)) + 1e-12
    out *= db(SPEECH_DB) / rms
    out = limiter(out, ceiling=db(-1.0), lookahead=0.003, release=0.08)
    out = fade(out, fin=0.004, fout=0.6)
    return out


def cmd_process(a):
    import warnings
    from scipy import signal
    from scipy.io import wavfile
    from sfxkit import SR, read_audio, write_ogg, write_wav
    for key in LINES:
        with warnings.catch_warnings():  # the float WAV's PEAK chunk, which scipy skips
            warnings.simplefilter("ignore", wavfile.WavFileWarning)
            sr, x = wavfile.read(os.path.join(a.raw, key + ".wav"))
        if np.issubdtype(x.dtype, np.integer):
            x = x.astype(np.float64) / float(np.iinfo(x.dtype).max + 1)
        x = x.astype(np.float64)
        if x.ndim > 1:
            x = x.mean(axis=1)
        y = process_line(x, sr, key)
        out = os.path.join(a.assets, "sounds", SOUND_DIR, key + ".ogg")
        for _ in range(3):  # held under -1 dBFS true peak in the encoded file, the encoder's overshoot too
            write_ogg(out, y, sr=OUT_SR, quality=6)
            d = read_audio(out, sr=OUT_SR)[0]
            over = 20 * np.log10(np.max(np.abs(signal.resample_poly(d, 4, 1))) + 1e-12) + 1.0
            if over <= 0:
                break
            y = y * 10 ** (-(over + 0.05) / 20)
        if a.wav:
            write_wav(os.path.join(a.wav, key + ".wav"), y, sr=OUT_SR)
        print(f"{out}  {len(y) / SR:.3f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("tts")
    t.add_argument("--model", required=True)
    t.add_argument("--voices", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--voice", default=DEFAULT_VOICE)
    t.add_argument("--speed", type=float, default=1.0, help="multiplies the lines' own speeds")
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
