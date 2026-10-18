"""What the market hall's merchant says about a player's own stall (economy/StallDesk.kt: a table let, another week
paid, the stall doing well, and the goods kept after a week ran out) and what the carter who comes for a homestead's
shipping crates says (homestead/Carter.kt), through the townsfolk voice pipeline: the same Kokoro voices, timbres and
processing as make_townsfolk_voices.py, with these lines and their delivery here. Run from the repo root:

  tts       <venv>/bin/python -I -B art/audio/make_stall_voices.py tts --model kokoro-v1.0.onnx \\
                --voices voices-v1.0.bin --out <raw-dir> [--timbres man,old] [--keys carter] [--espeak-data <dir>]
  process   python3 -B art/audio/make_stall_voices.py process --raw <raw-dir> \\
                --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
  phonemes  <venv>/bin/python -I -B art/audio/make_stall_voices.py phonemes --model ... --voices ... [--keys ...]
  check     <venv>/bin/python -I -B art/audio/make_stall_voices.py check --asr <sherpa-onnx-whisper-base.en dir> \\
                --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...]
  preview   python3 -B art/audio/make_stall_voices.py preview --assets src/main/resources/assets/guildmark --mp3 <file>
  sounds    python3 -B art/audio/make_stall_voices.py sounds    (the sounds.json entries, to merge)

Each line plays as townsfolk.voice.<timbre>.<line> in the man's, the woman's and the old man's voices; a child
borrows the woman's, pitched up, as TownsfolkClient does for any line the child has no take of. Each is subtitled
with its own text, say.guildmark.<line>. No line holds a name or a number: those are said in the chat line beside it.
`check` hears the encoded files with Whisper base.en and prints each line's words as heard beside what was written.
"""
import argparse
import json
import os
import re
import sys
import types

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "art", "audio"))
import make_townsfolk_voices as mtv  # noqa: E402

TIMBRES = ("man", "woman", "old")

# The language file's say.guildmark.<line>, word for word
LINES = {
    # the hall's merchant, letting a table and taking another week's rent
    "stall_rent_0": "The table's yours for the week. Set your goods out nicely.",
    "stall_rent_1": "Paid up, then. Mind you keep it stocked.",
    "stall_paid_0": "Another week? Good. The table stays yours.",
    # the merchant, to a renter whose stall is selling well
    "stall_well_0": "Your stall's doing a fine trade, you know.",
    "stall_well_1": "Folks keep asking for your goods. Well done.",
    # the merchant, keeping a stall's goods after its week ran out, and handing them back
    "stall_gone_0": "Your week ran out, so the table's gone back. I kept your goods safe.",
    "stall_gone_1": "Here you are. Everything that was on your table, and the till.",
    # the carter, coming up the road to the shipping crates
    "carter_arrive_0": "Morning! I've come for your crates.",
    "carter_arrive_1": "Carter for the market! Anything going?",
    # heaving the first sack onto the cart
    "carter_load_0": "Up she goes.",
    "carter_load_1": "That's a good weight.",
    # off down the road with the load
    "carter_leave_0": "That's the lot. Off to market with it.",
    "carter_leave_1": "Right, the market's waiting. Good day!",
    # nothing to take, or the market had no room for it
    "carter_empty_0": "Nothing in the crates today? I'll come by tomorrow.",
    "carter_full_0": "The market's got plenty of that. I've left it in the crate.",
    # to a player who talks to them
    "carter_hello_0": "Can't stop long, the market won't wait.",
}


def kind(line):
    if line.startswith("carter_arrive_"):
        return "call"
    if line.startswith("carter_load_"):
        return "heave"
    if line.startswith("carter_hello_"):
        return "busy"
    return "talk"


# Delivery by kind. speed: times the timbre's own. pitch: cents added to the timbre's processing (calling up the road
# sits a little higher, as the festival's calls do). level: dB over the common speech level. wet: the street's reverb.
DELIVERY = {
    "call": dict(speed=1.04, pitch=35, level=2.0, wet=0.08),
    "heave": dict(speed=1.06, pitch=20, level=1.0, wet=0.06),
    "busy": dict(speed=1.08, pitch=20, level=0.5, wet=0.06),
    "talk": dict(speed=1.0, pitch=0, level=0.0, wet=0.06),
}

# Text the TTS reads better (the subtitles keep the line as written), by line: {written: said}
SAY_AS = {}
# Phonemes written out (espeak's British reading put right), by line or by (line, timbre), as
# make_townsfolk_voices.PHONEMES: the American voices turn the British long vowels of "yours" and "Carter" into
# "yaws" and "Cotta" unless the r is sounded, so it is; the man's "Off" came out as "of", so it takes the American
# vowel, and his British "Folks" as "Though", so it takes the American one too.
PHONEMES = {
    "stall_rent_0": "ðə tˈeɪbəlz jˈɔːɹz fəðə wˈiːk. sˈɛt jɔː ɡˈʊdz ˈaʊt nˈaɪsli.",
    "carter_arrive_1": "kˈɑːɹtɚ fəðə mˈɑːkɪt! ˈɛnɪθˌɪŋ ɡˈəʊɪŋ?",
    ("stall_well_1", "man"): "fˈoʊks kˈiːp ˈaskɪŋ fɔː jɔː ɡˈʊdz. wˈɛl dˈʌn.",
    ("carter_leave_0", "man"): "ðats ðə lˈɒt. ˈɔːf tə mˈɑːkɪt wɪð ɪt.",
}


def lines(timbres=None, keys=None):
    out = {}
    for timbre in TIMBRES:
        if timbres and timbre not in timbres:
            continue
        out[timbre] = {k: v for k, v in LINES.items() if not keys or re.search(keys, k)}
    return out


def said(line, timbre):
    """what Kokoro is given -> (text or phonemes, is_phonemes)"""
    ph = PHONEMES.get((line, timbre)) or PHONEMES.get(line)
    if ph:
        return ph, True
    text = LINES[line]
    for a, b in SAY_AS.get(line, {}).items():
        text = text.replace(a, b)
    return text, False


def ogg(assets, timbre, line):
    return os.path.join(assets, "sounds", mtv.SOUND_DIR, timbre, line + ".ogg")


def cmd_tts(a):
    import soundfile as sf
    k = mtv.kokoro(types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data))
    for timbre, keys in lines(a.timbres, a.keys).items():
        t = mtv.TIMBRES[timbre]
        folder = os.path.join(a.out, timbre)
        os.makedirs(folder, exist_ok=True)
        for line in keys:
            text, is_ph = said(line, timbre)
            sp = t["speed"] * DELIVERY[kind(line)]["speed"]
            audio, sr = k.create(text, voice=t["voice"], speed=sp, lang=t["lang"], is_phonemes=is_ph)
            fn = os.path.join(folder, line + ".wav")
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
            print(fn, f"speed {sp:.3f}  {len(audio) / sr:.2f}s", flush=True)


def cmd_phonemes(a):
    k = mtv.kokoro(types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data))
    for timbre, keys in lines(a.timbres, a.keys).items():
        for line in keys:
            text, is_ph = said(line, timbre)
            ph = text if is_ph else k.tokenizer.phonemize(text, mtv.TIMBRES[timbre]["lang"])
            print(f"{timbre:6s} {line:16s} {ph}")


def cmd_process(a):
    import warnings
    from scipy import signal
    from scipy.io import wavfile
    from sfxkit import SR, read_audio, write_ogg, write_wav
    for timbre, keys in lines(a.timbres, a.keys).items():
        for line in keys:
            with warnings.catch_warnings():  # the float WAV's PEAK chunk, which scipy skips
                warnings.simplefilter("ignore", wavfile.WavFileWarning)
                sr, x = wavfile.read(os.path.join(a.raw, timbre, line + ".wav"))
            x = x.astype(np.float64)
            if x.ndim > 1:
                x = x.mean(axis=1)
            d = DELIVERY[kind(line)]
            st = dict(mtv.STYLE[timbre])
            st["pitch"] = st["pitch"] + d["pitch"]
            st["wet"] = d["wet"]
            y = mtv.process_line(x, sr, st, line, timbre, d["level"])
            y = mtv.sox(y, SR, out_sr=mtv.OUT_SR)
            out = ogg(a.assets, timbre, line)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            for _ in range(3):  # held under -1 dBFS true peak in the encoded file, the encoder's overshoot too
                write_ogg(out, y, sr=mtv.OUT_SR, quality=3)
                dec = read_audio(out, sr=mtv.OUT_SR)[0]
                over = 20 * np.log10(np.max(np.abs(signal.resample_poly(dec, 4, 1))) + 1e-12) + 1.0
                if over <= 0:
                    break
                y = y * 10 ** (-(over + 0.05) / 20)
            if a.wav:
                write_wav(os.path.join(a.wav, timbre, line + ".wav"), y, sr=mtv.OUT_SR)
            print(f"{out}  {len(y) / mtv.OUT_SR:.2f}s", flush=True)


def words(s):
    return re.sub(r"[^a-z' ]+", " ", s.lower().replace("’", "'")).split()


def cmd_check(a):
    """Whisper base.en (sherpa-onnx) hears each encoded line; prints the words as heard, marking any line not heard
    word for word, and the share of words heard right"""
    import difflib
    import subprocess
    import sherpa_onnx
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=os.path.join(a.asr, "base.en-encoder.int8.onnx"), decoder=os.path.join(a.asr, "base.en-decoder.int8.onnx"),
        tokens=os.path.join(a.asr, "base.en-tokens.txt"), num_threads=4, language="en", task="transcribe")
    right = total = exact = count = 0
    for timbre, keys in lines(a.timbres, a.keys).items():
        for line in keys:
            pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", ogg(a.assets, timbre, line), "-f", "f32le", "-ac", "1",
                                  "-ar", "16000", "-"], check=True, capture_output=True).stdout
            st = rec.create_stream()
            st.accept_waveform(16000, np.frombuffer(pcm, dtype=np.float32))
            rec.decode_stream(st)
            heard = st.result.text.strip()
            want, got = words(LINES[line]), words(heard)
            hits = sum(b.size for b in difflib.SequenceMatcher(a=want, b=got).get_matching_blocks())
            right += hits
            total += len(want)
            count += 1
            exact += want == got
            print(f"{'  ' if want == got else '! '}{timbre:6s} {line:16s} {heard}", flush=True)
    print(f"{exact} of {count} lines word for word, {100.0 * right / max(1, total):.1f}% of words")


def cmd_preview(a):
    """every line in the three voices, one after another with a breath between, as one MP3"""
    import subprocess
    import tempfile
    parts = []
    for line in LINES:
        for timbre in TIMBRES:
            parts.append(ogg(a.assets, timbre, line))
    with tempfile.TemporaryDirectory() as td:
        gap = os.path.join(td, "gap.wav")
        subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=32000:cl=mono", "-t", "0.6", gap],
                       check=True)
        listing = os.path.join(td, "list.txt")
        with open(listing, "w") as f:
            for p in parts:
                f.write(f"file '{os.path.abspath(p)}'\nfile '{gap}'\n")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", listing, "-ar", "32000", "-ac",
                        "1", "-c:a", "libmp3lame", "-q:a", "3", a.mp3], check=True)
    print("preview", a.mp3)


def cmd_sounds(a):
    entries = {}
    for timbre in TIMBRES:
        for line in LINES:
            entries[f"townsfolk.voice.{timbre}.{line}"] = {
                "sounds": [f"guildmark:{mtv.SOUND_DIR}/{timbre}/{line}"],
                "subtitle": f"say.guildmark.{line}",
            }
    print(json.dumps(entries, indent=2, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("tts", "phonemes"):
        t = sub.add_parser(name)
        t.add_argument("--model", required=True)
        t.add_argument("--voices", required=True)
        if name == "tts":
            t.add_argument("--out", required=True)
        t.add_argument("--espeak-data", default=None)
        t.add_argument("--keys", default=None)
        t.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--wav", default=None)
    p.add_argument("--keys", default=None)
    p.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    c = sub.add_parser("check")
    c.add_argument("--asr", required=True)
    c.add_argument("--assets", required=True)
    c.add_argument("--keys", default=None)
    c.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    v = sub.add_parser("preview")
    v.add_argument("--assets", required=True)
    v.add_argument("--mp3", required=True)
    sub.add_parser("sounds")
    a = ap.parse_args()
    {"tts": cmd_tts, "phonemes": cmd_phonemes, "process": cmd_process, "check": cmd_check, "preview": cmd_preview,
     "sounds": cmd_sounds}[a.cmd](a)


if __name__ == "__main__":
    main()
