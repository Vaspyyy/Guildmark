"""The grand festivals' townsfolk lines (festival/FestivalSite.kt: the host calling out through the day, the helpers
putting the decorations up, what dancers and the crowd say when a player talks to them, the prize-giving) through the
townsfolk voice pipeline: the same Kokoro voices, timbres and processing as make_townsfolk_voices.py, with the
festival's lines and their delivery here. Run from the repo root:

  tts       <venv>/bin/python -I -B art/audio/make_festival_voices.py tts --model kokoro-v1.0.onnx \\
                --voices voices-v1.0.bin --out <raw-dir> [--timbres man,old] [--keys chat] [--espeak-data <dir>]
  process   python3 -B art/audio/make_festival_voices.py process --raw <raw-dir> \\
                --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
  phonemes  <venv>/bin/python -I -B art/audio/make_festival_voices.py phonemes --model ... --voices ... [--keys ...]
  sounds    python3 -B art/audio/make_festival_voices.py sounds    (the sounds.json entries, to merge)

Each line plays as townsfolk.voice.<timbre>.<line> in the man's, the woman's and the old man's voices; a child
borrows the woman's, pitched up, as TownsfolkClient does for any line the child has no take of. Each is subtitled
with its own text, say.guildmark.<line>.
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
FESTIVAL = {
    # the host, while the decorations go up
    "fest_setup_0": "Up a bit, up a bit... there!",
    "fest_setup_1": "Mind the ladder! Who's got the pennants?",
    "fest_setup_2": "A lantern on every post, mind. Every post!",
    "fest_setup_3": "Not long now. The band's tuning up!",
    # the host calling out the day's contest and the festival through the fair
    "fest_ribbonwake_0": "There are ribbons hidden all about the town! Find them all!",
    "fest_ribbonwake_1": "Take a ribbon and join the ring!",
    "fest_ribbonwake_2": "Spring's come in! Dance it welcome!",
    "fest_sunhearth_0": "To the targets, archers! The best eye takes the medal!",
    "fest_sunhearth_1": "Partner up! The reel's about to start!",
    "fest_sunhearth_2": "The longest day! Make the most of it!",
    "fest_sheafmoot_0": "Bring your best to the show! Pumpkins, pies, the lot!",
    "fest_sheafmoot_1": "A fine harvest! The barns are full!",
    "fest_sheafmoot_2": "Who's grown the finest? We'll soon see!",
    "fest_lanternrise_0": "A riddle on every lantern! Bring me the answers!",
    "fest_lanternrise_1": "Keep your lanterns lit! We let them go at nightfall!",
    "fest_lanternrise_2": "The longest night, and the brightest square!",
    "fest_evening_0": "One more dance before dark!",
    "fest_evening_1": "Look up, look up! Fireworks!",
    "fest_evening_2": "What a day it's been!",
    # the host, to a player who talks to them
    "fest_host_0": "Glad you came! Have you tried the contest?",
    "fest_host_1": "Ask me anything about today's contest.",
    "fest_host_2": "Enjoying the festival?",
    # a dancer or a musician, mid-set
    "fest_busy_0": "Can't stop now, the music's playing!",
    "fest_busy_1": "After this dance, I promise!",
    # anyone else, chatting
    "fest_chat_ribbonwake_0": "Did you see the pole? Every ribbon a different colour!",
    "fest_chat_ribbonwake_1": "First blossom of the year. It always feels like a fresh start.",
    "fest_chat_ribbonwake_2": "I found a ribbon by the well last year!",
    "fest_chat_sunhearth_0": "Hot day for dancing, but who's complaining?",
    "fest_chat_sunhearth_1": "Wait till dusk, when the wheel catches fire!",
    "fest_chat_sunhearth_2": "My cousin hit the middle three times running.",
    "fest_chat_sheafmoot_0": "Smell that? There's a pie in every oven in town.",
    "fest_chat_sheafmoot_1": "The pumpkins are enormous this year.",
    "fest_chat_sheafmoot_2": "Harvest's in. Time to dance!",
    "fest_chat_lanternrise_0": "Cold out, but it's warm in the square.",
    "fest_chat_lanternrise_1": "I've been puzzling over that lantern's riddle all day.",
    "fest_chat_lanternrise_2": "When the lanterns go up, make a wish.",
    # the prize-giving
    "fest_gather": "Gather round, everyone! The prizes!",
    "fest_winner": "Three cheers for the winner!",
}


def kind(line):
    for k in ("chat", "busy", "host"):
        if line.startswith(f"fest_{k}_"):
            return k
    return "call"


# Delivery by kind. speed: times the timbre's own. pitch: cents added to the timbre's processing (calling out over a
# crowd sits a little higher). level: dB over the common speech level (calls carry). wet: the street's reverb (an open
# square: a touch more than a lane between houses for the calls).
DELIVERY = {
    "call": dict(speed=1.04, pitch=35, level=2.0, wet=0.08),
    "host": dict(speed=1.0, pitch=15, level=0.5, wet=0.06),
    "busy": dict(speed=1.1, pitch=25, level=1.0, wet=0.06),
    "chat": dict(speed=1.0, pitch=0, level=0.0, wet=0.06),
}

# Text the TTS reads better (the subtitles keep the line as written), by line: {written: said}
SAY_AS = {}
# Phonemes written out, as make_townsfolk_voices.PHONEMES: the American voices turn the British long "a" of
# "targets", "start", "harvest", "barns" and "dark" into an "o" unless the r is sounded, so it is; "Can't" takes the
# short "a" of "dance" (its long one came out as "cont"), and "music's" is read "musics" rather than "musicz". The
# recogniser heard "Aladdin" for "A lantern" and "arches" for "archers" until their r was sounded too, and "will soon
# see" until "We'll" was stressed, and "ances" for the short-a "answers" (now the American one).
PHONEMES = {
    "fest_setup_1": "mˈaɪnd ðə lˈadɚ! hˌuːz ɡˈɒt ðə pˈɛnənts?",
    "fest_setup_2": "ɐ lˈantɚn ˌɒn ˈɛvɹɪ pˈəʊst, mˈaɪnd. ˈɛvɹɪ pˈəʊst!",
    "fest_sunhearth_0": "tə ðə tˈɑːɹɡɪts, ˈɑːɹtʃɚz! ðə bˈɛst ˈaɪ tˈeɪks ðə mˈɛdəl!",
    "fest_sunhearth_1": "pˈɑːɹtnəɹ ˈʌp! ðə ɹˈiːlz ɐbˌaʊt tə stˈɑːɹt!",
    "fest_sheafmoot_1": "ɐ fˈaɪn hˈɑːɹvɪst! ðə bˈɑːɹnz ɑːɹ fˈʊl!",
    "fest_sheafmoot_2": "hˌuːz ɡɹˈəʊn ðə fˈaɪnɪst? wˈiːl sˈuːn sˈiː!",
    "fest_lanternrise_0": "ɐ ɹˈɪdəl ˌɒn ˈɛvɹɪ lˈantɚn! bɹˈɪŋ mˌiː ðɪ ˈænsɚz!",
    "fest_evening_0": "wˈɒn mˈɔː dˈans bɪfˌɔː dˈɑːɹk!",
    "fest_busy_0": "kˈant stˈɒp nˈaʊ, ðə mjˈuːzɪks plˈeɪɪŋ!",
    "fest_chat_ribbonwake_1": "fˈɜːst blˈɒsəm ɒvðə jˈiə. ɪt ˈɔːlweɪz fˈiːlz lˈaɪk ɐ fɹˈɛʃ stˈɑːɹt.",
    "fest_chat_sheafmoot_2": "hˈɑːɹvɪsts ˈɪn. tˈaɪm tə dˈans!",
}


def lines(timbres=None, keys=None):
    out = {}
    for timbre in TIMBRES:
        if timbres and timbre not in timbres:
            continue
        out[timbre] = {k: v for k, v in FESTIVAL.items() if not keys or re.search(keys, k)}
    return out


def said(line, timbre):
    """what Kokoro is given -> (text or phonemes, is_phonemes)"""
    ph = PHONEMES.get((line, timbre)) or PHONEMES.get(line)
    if ph:
        return ph, True
    text = FESTIVAL[line]
    for a, b in SAY_AS.get(line, {}).items():
        text = text.replace(a, b)
    return text, False


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
            print(f"{timbre:6s} {line:24s} {ph}")


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
            out = os.path.join(a.assets, "sounds", mtv.SOUND_DIR, timbre, line + ".ogg")
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


def cmd_sounds(a):
    entries = {}
    for timbre in TIMBRES:
        for line in FESTIVAL:
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
    sub.add_parser("sounds")
    a = ap.parse_args()
    {"tts": cmd_tts, "phonemes": cmd_phonemes, "process": cmd_process, "sounds": cmd_sounds}[a.cmd](a)


if __name__ == "__main__":
    main()
