"""The capitals' orders and supplier standing, voiced: what a business's keeper says over an order or a supplier's
rise in standing (economy/StallTalk.kt's order_ lines) and what the town crier cries about the orders
(townsfolk/Crier.kt's), through the townsfolk voice pipeline: the same Kokoro voices, timbres and processing as
make_townsfolk_voices.py, with these lines and their delivery here. Run from the repo root:

  tts       <venv>/bin/python -I -B art/audio/make_order_voices.py tts --model kokoro-v1.0.onnx \\
                --voices voices-v1.0.bin --out <raw-dir> [--timbres man,old] [--keys rank] [--espeak-data <dir>]
  process   python3 -B art/audio/make_order_voices.py process --raw <raw-dir> \\
                --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
  phonemes  <venv>/bin/python -I -B art/audio/make_order_voices.py phonemes --model ... --voices ... [--keys ...]
  sounds    python3 -B art/audio/make_order_voices.py sounds    (the sounds.json entries, to merge)

The keeper's lines (KEEPER) are said in the man's, the woman's and the old man's voices, as everyday speech. The
crier's (CRIER) are cried in the man's and the woman's exactly as make_townsfolk_voices.CRIER is (process_crier: read
slower, cut into calls, each call drawn out, projected and set in the square, at -19 LUFS, none over 4.5 s, heard 48
blocks off); an old crier borrows the man's, a little lower, as with every crier line. Each plays as
townsfolk.voice.<timbre>.<line>, subtitled with its own text, say.guildmark.<line>. None of them says a name or a
number: the chat line under a cry says who wants how many, and the market screen what an order pays.
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
KEEPER = {
    # an order of theirs to be had, and taken
    "order_offer_0": "I've an order going, if you've the goods for it.",
    "order_offer_1": "We're short of something. Would you take an order?",
    "order_taken_0": "Good. It's yours, then. Bring it by the rest day.",
    "order_taken_1": "Done. I'll look for you before the week is out.",
    # an order filled, and part of one
    "order_thanks_0": "That's the lot. Thank you kindly!",
    "order_thanks_1": "All of it, and in good time. Here's your pay.",
    "order_part_0": "That'll do for now. Bring the rest when you can.",
    "order_part_1": "A good start. I'll mark it down.",
    # to a supplier who let an order lapse lately, and to one who gives one up
    "order_lapsed_0": "You never brought it. I had to look elsewhere.",
    "order_lapsed_1": "Let me down, you did. Mind it doesn't happen again.",
    "order_given_up": "Pity. I'll ask someone else, then.",
    # goods refused (or sold for no standing) for having come off their own stall
    "order_bought_here": "That came off our own stall this week.",
    # a standing order offered, and turned down
    "order_standing": "Same again every week? I'd pay a little more for it.",
    "order_declined": "Fair enough. Another time, perhaps.",
    # a supplier's rise in standing at the capital, one line per rank
    "order_rank_1": "You're a grower we can count on now.",
    "order_rank_2": "Everyone here knows your goods. A known supplier!",
    "order_rank_3": "There's nobody we'd sooner buy from. You've our trust.",
    "order_rank_4": "Purveyor to the town! We're honoured to deal with you.",
}

CRIER = {
    # a business's order, newly up, at noon: the chat line under it says who wants how many, and about what it pays
    "crier_wanted_wheat": "Wheat wanted! A fair price for good grain!",
    "crier_wanted_carrot": "Carrots wanted! Bring them to market!",
    "crier_wanted_potato": "Potatoes wanted, by the sackful!",
    "crier_wanted_beetroot": "Beetroot wanted! A fair price paid!",
    "crier_wanted_apple": "Apples wanted! Bring in your apples!",
    "crier_wanted_honey": "Honey wanted! A fair price paid!",
    "crier_wanted_cod": "Cod wanted! Bring in your catch!",
    "crier_wanted_salmon": "Salmon wanted! Bring in your catch!",
    "crier_wanted_beef": "Beef wanted! A fair price paid!",
    "crier_wanted_pork": "Pork wanted! Bring it to market!",
    "crier_wanted_mutton": "Mutton wanted! A fair price paid!",
    "crier_wanted_chicken": "Chicken wanted! Bring it to market!",
    "crier_wanted_wool": "Wool is wanted! Bring in your fleeces!",  # "Wool wanted" is heard as "We'll want it"
    "crier_wanted_string": "String wanted! A fair price paid!",
    "crier_wanted_leather": "Leather wanted! Bring in your hides!",
    "crier_wanted_feather": "Feathers wanted! A fair price paid!",
    "crier_wanted_arrow": "Arrows wanted! Bring in your arrows!",
    "crier_wanted_flint": "Flint wanted! A fair price paid!",
    "crier_wanted_candle": "Candles wanted! Bring them to market!",
    "crier_wanted_bottle": "Bottles wanted! A fair price paid!",
    "crier_wanted_iron": "Iron wanted! Bring it to the smithy!",
    "crier_wanted_coal": "Coal wanted! Bring it to the smithy!",
    # the orders filled since the noon before: the chat line names who brought what ("An order filled" was heard as
    # "In order filled", however it was read)
    "crier_order_filled_0": "An order is filled! The town thanks its suppliers!",
    "crier_order_filled_1": "Goods delivered, and the town well supplied!",
    # writing the board up for new orders' tickets when no price has changed
    "crier_board_orders": "New orders are up on the board!",
}

# The crier's lines join make_townsfolk_voices.CRIER for the run (the file is not changed), so that its crier
# machinery (crier_phonemes, process_crier) reads and cries them as it does its own
mtv.CRIER.update(CRIER)

# Level offsets (dB) from the common speech level, as make_townsfolk_voices.LEVEL: a keeper's glad cries carry a
# little, a shrug doesn't
LEVEL = {"order_thanks_0": 1.0, "order_rank_2": 1.0, "order_rank_4": 1.5, "order_given_up": -1.0,
         "order_declined": -1.0}

# Every line is read from phonemes: espeak's British reading of it (a crier line's with
# make_townsfolk_voices.CRIER_PHONEMES already put right) with these put right, in this order, then the line's own in
# one timbre (PHONEMES_FOR). The American voices drop or bend a British vowel that has no r after it, so "order",
# "short", "mark", "more", "here", "sooner", "our", "purveyor", "delivered", "suppliers" and "iron" sound their r, as
# the crier's "dear" and "board" do, and "of" takes the vowel it did in the crier's "little of it". Each comment says
# what Whisper base.en heard without the fix.
PHONEMES = [
    ("ˈɔːdəz", "ˈɔːɹdɚz"),  # "orders", not "otters" or "auditors"
    ("ˈɔːdə", "ˈɔːɹdɚ"),  # "an order", not "an audit" or "an auto"
    ("ɚɹ ɪz fˈɪld", "ɚɹ ˈɪz fˈɪld"),  # "An order is filled", not "In order is filled"
    ("wiə ʃˈɔːt ɒv", "wɪɹ ʃˈɔːɹt ʌv"),  # "We're short of", not "We are shot of" or "We assured of"
    ("mˈɑːk ɪt", "mˈɑːɹk ɪt"),  # "mark it down", not "mock it down"
    ("mˈɔː fɔːɹ", "mˈɔːɹ fɔːɹ"),  # "a little more for it", not "a little off for it"
    ("fˈeəɹ ɪnˈʌf", "fˈɛɹ ɪnˈʌf"),  # "Fair enough", not "Fear enough"
    ("hˈiə nˈəʊz", "hˈɪɹ nˈoʊz"),  # "Everyone here knows", not "Everyone he knows"
    ("ɐ nˈəʊn səplˈaɪə", "ɐ nˈoʊn səplˈaɪɚ"),  # "A known supplier", not "are known supplier"
    ("sˈuːnə bˈaɪ", "sˈuːnɚ bˈaɪ"),  # "sooner buy from", not "soon abide from"
    ("ˌaʊə tɹˈʌst", "ˈaʊɚ tɹˈʌst"),  # "our trust", not "all trust"
    ("pɜːvˈeɪə", "pɜːɹvˈeɪjɚ"),  # "Purveyor", not "Pervaya" (now "Perveyor": a word base.en doesn't know)
    ("wiəɹ ˈɒnəd", "wɪɹ ˈɒnɚd"),  # "We're honoured", not "we are on it"
    ("dɪlˈɪvəd", "dɪlˈɪvɚd"),  # "Goods delivered"
    ("səplˈaɪəz", "səplˈaɪɚz"),  # "thanks its suppliers", not "thanks its supply is"
    ("wˈiːt wˈɒntɪd", "hwˈiːtʰ wˈɒntɪd"),  # "Wheat wanted", not "We wanted", however its t was said
    ("bɹˈɪŋ ɪn jɔː", "bɹˈɪŋ ˈɪn jɔː"),  # "Bring in your", not "bringing your"
    ("ˈaɪən wˈɒntɪd", "ˈaɪɚn wˈɒntɪd"),  # "Iron wanted", not "I am wanted"
    ("kˈɑːd wˈɒntɪd", "kˈɔːd wˈɒntɪd"),  # "Cod wanted", not "Card wanted" (CRIER_PHONEMES' "Cod", right in "Cod is")
]
# The man still runs "Bring in your" together ("bringing your") unless he breathes after "Bring", as he does in the
# crier's "Bring, in the harvest"
BRING_IN = [("bɹˈɪŋ ˈɪn jɔː", "bɹˈɪŋ, ˈɪn jɔː")]
# A line's own fixes in one timbre, after PHONEMES: (line, timbre) -> [(espeak's, Kokoro's)]
PHONEMES_FOR = {
    ("order_offer_0", "man"): [("ɪf juːv ðə", "ɪf jˈuːv ðə")],  # "if you've the goods", not "if you the goods"
    ("order_part_1", "man"): [("ɐ ɡˈʊd stˈɑːt", "ʌ ɡˈʊd stˈɑːt")],  # "A good start", not "Good start"
    ("crier_wanted_mutton", "woman"): [("mˈʌtən wˈɒntɪd", "mˈətən wˈɒntɪd")],  # "Mutton", not "Martin"
    ("crier_wanted_salmon", "woman"): [("sˈamən wˈɒntɪd", "sˈæmən wˈɒntɪd")],  # "Salmon", not "Sam and"
    ("crier_wanted_wheat", "man"): [("hwˈiːtʰ wˈɒntɪd", "hwˈiːtʰ, wˈɒntɪd")],  # "Wheat wanted", not "We'd wanted"
    ("crier_wanted_cod", "man"): BRING_IN,
    ("crier_wanted_salmon", "man"): BRING_IN,
    ("crier_wanted_wool", "man"): BRING_IN,
    ("crier_wanted_leather", "man"): BRING_IN,
    ("crier_wanted_arrow", "man"): BRING_IN,
}


def lines(timbres=None, keys=None):
    """{timbre: {line: text}}: the keeper's lines in every timbre, the crier's in the crier's ([timbres]: only these,
    [keys]: a regex on the line names)"""
    out = {}
    for timbre in TIMBRES:
        if timbres and timbre not in timbres:
            continue
        d = dict(KEEPER, **(CRIER if timbre in mtv.CRIER_TIMBRES else {}))
        out[timbre] = {k: v for k, v in d.items() if not keys or re.search(keys, k)}
    return out


def said(k, line, timbre):
    """the phonemes Kokoro is given for a line (with the Kokoro k): espeak's British reading of it (a crier line's as
    make_townsfolk_voices reads its own, crier_phonemes), with PHONEMES and the line's PHONEMES_FOR put right"""
    if line in CRIER:
        ph = mtv.crier_phonemes(k, line, timbre)
    else:
        ph = k.tokenizer.phonemize(KEEPER[line], mtv.TIMBRES[timbre]["lang"])
    for a, b in PHONEMES + PHONEMES_FOR.get((line, timbre), []):
        ph = ph.replace(a, b)
    return ph


def speed(line, timbre):
    """how quickly Kokoro reads a line: a crier line as the crier cries (make_townsfolk_voices.CRIER_SPEED), a
    keeper's at the timbre's own speed"""
    return mtv.CRIER_SPEED[timbre] if line in CRIER else mtv.TIMBRES[timbre]["speed"]


def kokoro(a):
    return mtv.kokoro(types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data))


def cmd_tts(a):
    import soundfile as sf
    k = kokoro(a)
    for timbre, keys in lines(a.timbres, a.keys).items():
        t = mtv.TIMBRES[timbre]
        folder = os.path.join(a.out, timbre)
        os.makedirs(folder, exist_ok=True)
        for line in keys:
            sp = speed(line, timbre)
            audio, sr = k.create(said(k, line, timbre), voice=t["voice"], speed=sp, lang=t["lang"], is_phonemes=True)
            fn = os.path.join(folder, line + ".wav")
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
            print(fn, f"speed {sp:.3f}  {len(audio) / sr:.2f}s", flush=True)


def cmd_phonemes(a):
    k = kokoro(a)
    for timbre, keys in lines(a.timbres, a.keys).items():
        for line in keys:
            print(f"{timbre:6s} {line:22s} {said(k, line, timbre)}")


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
            if line in CRIER:
                y = mtv.process_crier(x, sr, timbre, line)
            else:
                y = mtv.process_line(x, sr, mtv.STYLE[timbre], line, timbre, LEVEL.get(line, 0.0))
            y = mtv.sox(y, SR, out_sr=mtv.OUT_SR)
            out = os.path.join(a.assets, "sounds", mtv.SOUND_DIR, timbre, line + ".ogg")
            for _ in range(3):  # held under -1 dBFS true peak in the encoded file, the encoder's overshoot too
                write_ogg(out, y, sr=mtv.OUT_SR, quality=3)
                dec = read_audio(out, sr=mtv.OUT_SR)[0]
                over = 20 * np.log10(np.max(np.abs(signal.resample_poly(dec, 4, 1))) + 1e-12) + 1.0
                if over <= 0:
                    break
                y = y * 10 ** (-(over + 0.05) / 20)
            if a.wav:
                write_wav(os.path.join(a.wav, timbre, line + ".wav"), y, sr=mtv.OUT_SR)
            long = line in CRIER and len(y) / mtv.OUT_SR > mtv.CRIER_MAX_LEN
            print(f"{out}  {len(y) / mtv.OUT_SR:.2f}s" + ("  OVER CRIER_MAX_LEN: quicken it in CRIER_SPEED_FOR"
                                                           if long else ""), flush=True)


def cmd_sounds(a):
    """the sounds.json entries: the keeper's lines, then the crier's (heard across the square)"""
    entries = {}
    for set_of, timbres in ((KEEPER, TIMBRES), (CRIER, mtv.CRIER_TIMBRES)):
        for timbre in timbres:
            for line in set_of:
                sound = f"guildmark:{mtv.SOUND_DIR}/{timbre}/{line}"
                if set_of is CRIER:
                    sound = {"name": sound, "attenuation_distance": mtv.CRIER_REACH}
                entries[f"townsfolk.voice.{timbre}.{line}"] = {"sounds": [sound], "subtitle": f"say.guildmark.{line}"}
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
        t.add_argument("--espeak-data", default=None,
                       help="a copy of espeakng_loader's espeak-ng-data at a short path (espeak-ng caps the path at "
                            "160 chars)")
        t.add_argument("--keys", default=None, help="only lines whose name matches this regex")
        t.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--wav", default=None)
    p.add_argument("--keys", default=None, help="only lines whose name matches this regex")
    p.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    sub.add_parser("sounds")
    a = ap.parse_args()
    {"tts": cmd_tts, "phonemes": cmd_phonemes, "process": cmd_process, "sounds": cmd_sounds}[a.cmd](a)


if __name__ == "__main__":
    main()
