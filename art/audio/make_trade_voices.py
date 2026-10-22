"""The trader's life, voiced (living-world/trader/design.md section 10, "Voice lines", and the X6 row of 12.15): what the
hall's merchant, the keeper at the counter, the crier, the street, a Hunt's outlaws, a hired guard, the Store's clerk, the
Counting House's scribe and carters and the gate guards say, through the townsfolk voice pipeline: the same Kokoro voices,
timbres and processing as make_townsfolk_voices.py, with these lines and their delivery here, as make_order_voices.py does.
The trade's ten talk families (trade_talk.py) are recorded by make_talk_voices.py itself (its tts and process, once its
GROUPS has the block that enters them); this script reads their table for the checks, the loudness report and the listening
copy. Run from the repo root:

  tts        <venv>/bin/python -I -B art/audio/make_trade_voices.py tts --model kokoro-v1.0.onnx \\
                 --voices voices-v1.0.bin --out <raw-dir> [--timbres man,old] [--keys '^trade_board'] [--force] \\
                 [--espeak-data <dir>]
  process    python3 -B art/audio/make_trade_voices.py process --raw <raw-dir> \\
                 --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
  phonemes   <venv>/bin/python -I -B art/audio/make_trade_voices.py phonemes --model ... --voices ... [--keys ...]
  check      <venv with sherpa_onnx>/bin/python -I -B art/audio/make_trade_voices.py check \\
                 --asr <sherpa-onnx-whisper-base.en dir> --assets src/main/resources/assets/guildmark \\
                 [--timbres ...] [--keys ...] [--json <file>]
  loudness   python3 -B art/audio/make_trade_voices.py loudness --assets src/main/resources/assets/guildmark [--keys ...]
  listen     python3 -B art/audio/make_trade_voices.py listen --assets src/main/resources/assets/guildmark \\
                 --out <dir> [--keys ...]       (one MP3 of every take in turn, and a text file of what is said when)
  fragments  python3 -B art/audio/make_trade_voices.py fragments [--assets src/main/resources/assets/guildmark]
                 (writes trade_voices_sounds.json and trade_voices_lang.json beside this script)

The talk families' ten are not written to the fragments: `make_talk_voices.py table` writes talk_sounds.json and
talk_lang.json for them, and art/lang/merge_lang.py merges all of these into sounds.json and en_us.json.

The keeper's, the merchant's and the street's lines are everyday speech in the man's, the woman's and (the keeper's and the
street's) the old man's voices. The crier's (CRIER) are cried in the man's and the woman's exactly as
make_townsfolk_voices.CRIER is (process_crier: read slower, cut into calls, each call drawn out, projected and set in the
square, at -19 LUFS, none over 4.5 s, heard 48 blocks off), and join it for the run (the file is not changed). The outlaws'
are rough (lower, with grit, louder), the guards' steady, the carters' called across a yard. Each plays as
townsfolk.voice.<timbre>.<line>, subtitled with its own text, say.guildmark.<line>. None of them says a name, a number, a
good or a place: the chat line or the grey text beside a line says who wants how many of what.
"""
import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile
import types

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HERE = os.path.join(REPO, "art", "audio")
sys.path.insert(0, HERE)
import make_townsfolk_voices as mtv  # noqa: E402
import trade_talk  # noqa: E402

MW = ("man", "woman")
MWO = ("man", "woman", "old")
TIMBRES = MWO

# A take may last this long (seconds, as processed: its breath and the street's tail included); the crier's, the server's
# 100 ticks. Other talk is held to 4.0 s, and so are these
LIMIT = 4.0
CRIER_LIMIT = mtv.CRIER_MAX_LEN
TALK_LIMIT = 4.0


class Line:
    """one key: its text (the language file's say.guildmark.<key>, word for word), the timbres that say it, who says it
    and when (for the listening copy), its group and its family"""

    def __init__(self, key, family, text, timbres, who, when, group, crier=False):
        self.key, self.family, self.text, self.timbres = key, family, text, timbres
        self.who, self.when, self.group, self.crier = who, when, group, crier


# ------------------------------------------------------------------ the lines
# [(group, who says it, [(family, when, timbres, [wordings])])]; a family of several wordings is keyed <family>_0, _1, ...,
# a family of one that is written with a single string is keyed as its name (crier_hauls_thanks). The words are the
# design's section 10, but for two changes (and three more in trade_talk.py), each said in the hand-back: the outlaws' "Lost
# him!" and "He's gone to ground. Back to the camp." assumed the player is a man, so they are "Lost them!" and "Gone to
# ground. Back to the camp."
TABLE = [
    ("The hall's merchant, in the hall, at the board and at the counter", "the hall's merchant", [
        ("trade_board", "after hanging the new tickets on the Haul Board", MW, [
            "New tickets are up. Fair pay for honest carriers.",
            "Fresh word from the other towns, chalked and stamped.",
            "Mind the dates on the tickets. They don't keep.",
        ]),
        ("trade_board_first", "the first chalk of the day", MW, [
            "The morning's word is up. Come and read it.",
            "Good morning to the carriers. The board is chalked.",
        ]),
        ("trade_tally", "handing over a Tally", MW, [
            "Here. It remembers what you see and what you carry.",
            "Keep it dry. Every carrier needs a Tally.",
        ]),
        ("trade_report", "taking a report", MW, [
            "Your word is good. I'll chalk it up.",
            "Fresher than ours. Thank you.",
        ]),
        ("trade_book", "a ticket booked", MW, [
            "Stamped and sealed. It's yours to fill.",
            "Booked. Mind the day it falls due.",
        ]),
        ("trade_lapsed", "a booking left to lapse", MW, [
            "That ticket has lapsed. It's back on the board.",
            "You never brought the goods. Another will.",
        ]),
        ("trade_sell_harness", "selling a Pack Harness", MW, [
            "A good harness, and a whistle to call them by.",
            "Sound straps. Your beast will thank you.",
            "Fit that snug and she'll follow you anywhere.",
        ]),
        ("trade_sell_donkey", "selling a donkey", MW, [
            "Here's a donkey, tame and willing.",
            "Sure-footed, and she won't complain.",
            "She's yours. Be kind to her and she'll carry all day.",
        ]),
        ("trade_sell_cart", "selling a Chapman's Cart", MW, [
            "A fine cart. Mind the wheels on the stones.",
            "Light enough to pull, big enough to matter.",
            "Pull it with pride. It carries your name now.",
        ]),
        ("trade_sell_wagon", "selling a Wagon Bill", MW, [
            "The seal is on the bill. The wagon is yours.",
            "A wagon of your own at last. Drive it well.",
            "Mind the road, and keep your guards close.",
        ]),
        ("trade_warrant", "selling the Carrier's Warrant", MW, [
            "By the hall's leave, a carrier of good name.",
            "Sealed and signed. Honour it.",
            "The hall trusts you with the big loads.",
        ]),
    ]),
    ("The keeper at the counter", "the keeper", [
        ("trade_delivered", "a haul delivered", MWO, [
            "Just what we were short of.",
            "Counted and sound. Here's your pay.",
            "That'll keep the shelves full. Thank you.",
            "Good carrying. We'd have run dry by the week's end.",
        ]),
        ("trade_relief", "a relief ticket delivered", MWO, [
            "Bless you. We were running out.",
            "You've kept us fed. We won't forget it.",
        ]),
        ("trade_part", "part of a ticket delivered", MWO, [
            "That's a start. Bring the rest by the day.",
            "Some now and more later? That'll do for the moment.",
        ]),
        ("trade_street", "the second line, to the street, at a cheer", MWO, [
            "Bless the road that brought it.",
            "The carriers have done us proud.",
        ]),
        ("trade_firstcall", "offering a carrier first call", MWO, [
            "I'd sooner deal with you again. Take it first, if you'll have it.",
            "Same again, if you can. You'll have first call.",
        ]),
    ]),
    ("The crier (cried)", "the town crier", [
        ("crier_hauls", "the noon cry, when tickets are up", MW, [
            "Carriers are wanted at the Haul Board!",
            "Goods wanted from the road! See the Haul Board!",
            "Carriers wanted! Fair pay at the Haul Board!",
        ]),
        ("crier_hauls_thanks", "for a town fed lately", MW, "Our thanks to those who brought goods in from the road!"),
        ("crier_hauls_bells", "a Hawker has been about", MW, "Bells on the road! The carriers are about!"),
        ("crier_house", "a trading house runs wagons to the town", MW, "A trading house is running wagons to us!"),
    ]),
    ("Townsfolk in the street", "townsfolk", [
        ("trade_passing", "a laden trader passes", MWO, [
            "Mind how you go with that.",
            "Heavy load! Keep to the road, friend.",
        ]),
        ("trade_cartman", "the town's carter brings a cart round", MW, [
            "Here she is, freshly oiled.",
            "Mind the shafts. She's a good one.",
        ]),
        ("trade_cheer", "the street cheers a delivery", MWO, [
            "Hurrah!",
            "Well done!",
            "Bravo!",
        ]),
    ]),
    ("The outlaws of a Hunt", "an outlaw of the band", [
        ("trade_hold", "the leader calls, twelve blocks off", MW, [
            "Hold there! Drop the pack and no one bleeds!",
            "Stand where you are! Leave the load and walk on!",
            "That's far enough! Put the goods down!",
        ]),
        ("trade_wise", "the player gives the goods up", MW, [
            "Wise. We'll take our due.",
            "Sensible. Stand still and it'll be quick.",
        ]),
        ("trade_lesson", "a blow that would have felled the player", MW, [
            "That's the lesson.",
            "Enough! You've had your lesson.",
        ]),
        ("trade_lost", "the player gets away", MW, [
            "Lost them!",  # the design's "Lost him!"
            "Gone to ground. Back to the camp.",  # the design's "He's gone to ground. Back to the camp."
        ]),
        ("trade_flee", "the party breaks", MW, [
            "Off, off! There's too many of them!",
            "Back! Back to the hills!",
        ]),
    ]),
    ("A hired guard", "a hired guard", [
        ("trade_guard_hello", "meeting the player at the gate", MW, [
            "Guard at your service. I'll keep close.",
            "Right behind you. Lead on.",
        ]),
        ("trade_guard_stay", "a Hunt begins", MW, [
            "Stay behind me!",
            "Get back, I'll deal with them!",
        ]),
        ("trade_guard_end", "his days end", MW, [
            "My days are up. Good road to you.",
            "That's my time served. Hire me again if you like.",
        ]),
    ]),
    ("The Store and the Counting House", "the Store's clerk, the scribe, the carters and the gate guards", [
        ("trade_clerk_hello", "the Store's clerk, at the desk", MW, [
            "Welcome to the Store. Your goods are safe with me.",
            "Morning. The bonded goods are all in order.",
            "Anything to leave, or to take away?",
        ]),
        ("trade_clerk_bonded", "goods bonded", MW, [
            "Bonded, and sealed for the month.",
            "Safe in the Store. They keep their name a month.",
        ]),
        ("trade_clerk_unpaid", "the clerk's wage owing", MW, [
            "My wage is owing. I can't bond more until it's paid.",
            "The Store is shut to new goods till I'm paid.",
        ]),
        ("trade_scribe_back", "a wagon back, at the Counting House", MW, [
            "Wagon's back!",
            "A wagon in the yard! I'll count it.",
            "Back safe, and the strongbox heavier.",
        ]),
        ("trade_scribe_books", "the day's sums made", MW, [
            "The books balance.",
            "Every Mark accounted for.",
        ]),
        ("trade_scribe_float", "the purse empty", MW, [
            "We're short of float. The wagons can't load.",
            "The strongbox is nearly empty. Put something in.",
        ]),
        ("trade_carter_hired", "a carter hired", MW, [
            "I'll drive for you, and gladly.",
            "A good wage and a good wagon. Done.",
        ]),
        ("trade_carter_out", "a carter setting out", MW, [
            "Off we go, then!",
            "Back before you know it.",
        ]),
        ("trade_carter_back", "a carter back", MW, [
            "Delivered, and not a scratch.",
            "Safe home. The road was kind.",
        ]),
        ("trade_carter_robbed", "a wagon waylaid", MW, [
            "They stopped us on the road. I'm sorry.",
            "Outlaws took the load. I did what I could.",
        ]),
        ("trade_gate_house", "the gate guards read a House wagon", MW, [
            "That's a House wagon. Let it through.",
            "A trading house on the road. Good to see it.",
        ]),
    ]),
]


def build():
    out = {}
    for group, who, families in TABLE:
        for family, when, timbres, ws in families:
            crier = family.startswith("crier_")
            if isinstance(ws, str):
                items = [(family, ws)]
            else:
                items = [(f"{family}_{i}", w) for i, w in enumerate(ws)]
            for key, text in items:
                assert key not in out, f"{key} is in the table twice"
                out[key] = Line(key, family, text, timbres, who, when, group, crier)
    return out


LINES = build()
CRIER = {k: ln.text for k, ln in LINES.items() if ln.crier}

# The crier's lines join make_townsfolk_voices.CRIER for the run (the file is not changed), so that its crier machinery
# (crier_phonemes, process_crier) reads and cries them as it does its own
mtv.CRIER.update(CRIER)

# ------------------------------------------------------------------ how they are said
# Delivery by family. speed: times the timbre's own. pitch: cents added to the timbre's processing. grit: added to the
# timbre's. tremor: cents of wobble added to the timbre's. level: dB over the common speech level. wet: the street's
# reverb (the road is open, the hall is not). The crier's are cried by mtv.process_crier whatever is set here.
ROUGH = dict(pitch=-70, grit=0.2, wet=0.03)  # an outlaw of the band: lower, with grit, out on the open road
DELIVERY = {
    # the hall's merchant: calling the board to the hall; a brisk offer; sorry to say; the Warrant's ceremony
    "trade_board": dict(level=1.0, wet=0.08),
    "trade_board_first": dict(speed=1.03, level=1.5, wet=0.08),
    "trade_book": dict(level=0.5),
    "trade_lapsed": dict(speed=0.97, level=-0.5),
    "trade_sell_harness": dict(level=0.5),
    "trade_sell_donkey": dict(level=0.5),
    "trade_sell_cart": dict(level=0.5),
    "trade_sell_wagon": dict(level=0.5),
    "trade_warrant": dict(speed=0.96, level=0.5),
    # the keeper: glad of it; moved by it; to the street over the till
    "trade_delivered": dict(level=0.5),
    "trade_relief": dict(speed=0.94, pitch=-20),
    "trade_street": dict(speed=0.98, level=1.5, wet=0.08),
    # the street: a word in passing; the carter calling up the yard; the crowd
    "trade_passing": dict(level=0.5),
    "trade_cartman": dict(level=1.0, pitch=20, wet=0.08),
    "trade_cheer": dict(speed=1.05, pitch=60, level=3.0, wet=0.08),
    # the outlaws: the leader's call carries; a menace said low; a blow; a curse after the player; a rout
    "trade_hold": dict(ROUGH, speed=1.04, level=3.0),
    "trade_wise": dict(ROUGH, speed=0.95, pitch=-80, level=0.5),
    "trade_lesson": dict(ROUGH, speed=1.05, pitch=-60, level=2.5),
    "trade_lost": dict(ROUGH, speed=1.05, pitch=-40, level=3.0),
    "trade_flee": dict(ROUGH, speed=1.1, pitch=20, grit=0.15, level=3.0),
    # a hired guard: steady; a warning shouted; a parting
    "trade_guard_hello": dict(pitch=-20, level=0.5, wet=0.05),
    "trade_guard_stay": dict(speed=1.06, pitch=20, level=2.5, wet=0.03),
    "trade_guard_end": dict(wet=0.05),
    # the Store and the House: a clerk's welcome and complaint; the scribe calling across the yard; the carters
    "trade_clerk_hello": dict(level=0.5),
    "trade_clerk_unpaid": dict(speed=0.97, level=-0.5),
    "trade_scribe_back": dict(speed=1.05, pitch=30, level=1.5, wet=0.08),
    "trade_scribe_float": dict(speed=0.97, level=-0.5),
    "trade_carter_hired": dict(level=0.5),
    "trade_carter_out": dict(speed=1.04, pitch=25, level=1.5, wet=0.08),
    "trade_carter_back": dict(level=1.0, wet=0.08),
    "trade_carter_robbed": dict(speed=0.93, pitch=-40, level=-1.0),
    "trade_gate_house": dict(level=1.0, wet=0.08),
}
# A line's own level (dB over the speech level) where its family's does not fit: line -> dB. And (line, timbre) -> dB, a
# take put right where it measured off its family's level (the loudness report still expects the family's)
LEVEL_FOR = {
    ("trade_cheer_0", "man"): 5.2,  # his "hurray" is a short one and measured 2.2 LU under the crowd's
}
# A take's own speed (times the timbre's own), where its timbre's reading misread or rushed at the family's: (line, timbre)
SPEED_FOR = {
    ("trade_wise_0", "man"): 0.88,  # "Why is we'll take our due": the one-word opening runs into the next at the family's pace
    ("trade_wise_1", "man"): 0.9,  # "Sensible stands still": the d of "stand" is lost into "still" at the family's pace
}

# Text the TTS reads better (the subtitles keep the line as written): {line: {written: said}}
SAY_AS = {}

# Every non-crier line is read from phonemes: espeak's British reading of its text with the r's of its American reading
# sounded (make_talk_voices.rhotic, as the talk is read), then make_talk_voices' TALK_PHONEMES, then these put right, in
# this order, then the line's own in one timbre (PHONEMES_FOR). Each comment says what Whisper base.en heard without it.
PHONEMES = [
    ("nˈəʊwˈɒn", "nˈoʊ wˈʌn"),  # "no one", run together as "no-won" and then read "No wine"
]
# A line's own fixes in one timbre, after PHONEMES: (line, timbre) -> [(espeak's, Kokoro's)]
PHONEMES_FOR = {
    ("trade_report_1", "man"): [("fɹˈɛʃɚ ðɐn", "fɹˈɛʃɚː ðɐn")],  # "Fresh than ours": the -er was swallowed
    ("trade_book_1", "woman"): [("bˈʊkt.", "bˈʊkd.")],  # "Book to mind the day": the t was heard as a word
    ("trade_lapsed_0", "man"): [("hɐz lˈapst", "hˈaz lˈapst")],  # "That ticket is lapsed"
    ("trade_sell_harness_2", "man"): [("fˈɪt ðat", "fˈɪtʰ ðˈat")],  # "With that snug and she'll follow you"
    ("trade_sell_donkey_1", "woman"): [("ʃˈʊɹfˈʊtɪd", "ʃˈʊʊɹ fˈʊtɪd")],  # "Shore footed"
    ("trade_delivered_1", "man"): [("kˈaʊntɪd", "kˈaʊntɛd")],  # "Count it and sound"
    ("trade_part_1", "man"): [("sˌʌm nˈaʊ", "sˈʌm nˈaʊ")],  # "So now and more later"
    ("trade_street_0", "man"): [("ɹˈəʊd ðat", "ɹˈəʊd, ðat")],  # "Bless the road the product"
    ("trade_cartman_0", "man"): [("fɹˈɛʃli", "fɹˈɛʃli,")],  # "freshly ailed"
    ("trade_wise_1", "man"): [("stˈand stˈɪl", "stˈænd stˈɪl")],  # "Sensible stands still"
    ("trade_wise_1", "woman"): [("stˈand stˈɪl", "stˈandʰ stˈɪl")],  # likewise (the man's fix is not hers)
    ("trade_lost_1", "man"): [("ɡɒn tə", "ɡˈɒn tə")],  # "But underground back to the camp"
    ("trade_flee_0", "man"): [("ˈɒf, ˈɒf!", "ˈɔːf, ˈɔːf!")],  # "a f off"
    ("trade_clerk_unpaid_1", "man"): [("tˈɪl aɪm", "tˈɪl, aɪm")],  # "till time paid"
    ("trade_scribe_back_0", "man"): [("wˈaɡənz bˈak", "wˈæɡənz bˈæk")],  # "Waggons back"
    ("trade_scribe_back_0", "woman"): [("wˈaɡənz bˈak", "wˈæɡənz bˈæk")],  # "Woggins back"
    ("trade_scribe_back_1", "man"): [("ɐ wˈaɡən", "ʌ wˈæɡən")],  # "wagging in the yard": the A was lost
    ("trade_scribe_back_2", "man"): [("sˈeɪf, and ðə", "sˈeɪf, ˈand ðə"),  # "Back safe in the strong box"
                                     ("stɹˈɒŋbɒks", "stɹˈɒŋbˌɒks")],
    ("trade_carter_hired_0", "man"): [("aɪl dɹˈaɪv", "ˈaɪl dɹˈaɪv")],  # "I'll try for you"
    ("trade_gate_house_0", "man"): [("lˈɛt ɪt", "lˈɛtʰ ɪt")],  # "lit it through"
    ("trade_scribe_float_0", "man"): [("ɒv flˈəʊt", "ɒv. flˈəʊt")],  # "We're sure to float": a breath after "of" parts them
}
# A take's phonemes written out in full: (line, timbre) or line -> phonemes
FULL = {
    # "Hurrah" is said "hooray": Kokoro reads "Hurrah" as "her eye" or "Hara" (the builders' cry, make_townsfolk_voices)
    "trade_cheer_0": "hʊɹˈeɪ!",
    ("trade_cheer_0", "man"): "hɐɹˈeɪ!",  # his "hooray" was heard as "Ray"
}
# The crier's, after make_townsfolk_voices.CRIER_PHONEMES; and a line's own in one timbre
CRIER_FIXES = []
CRIER_FIXES_FOR = {
    ("crier_hauls_2", "man"): [("kˈaɹiəz wˈɑntɪd", "kˈaɹiɚz wˈɑntɪd")],  # "Carrie is wanted"
}
# The throw-away word read in front of a line whose opening the voice swallows, and cut off again (mtv.LEAD_IN)
LEAD_IN = {}
# How far a line carries (blocks, its sounds.json attenuation_distance) where it is shouted across a distance and the
# default sixteen would leave it quiet (the volume falls away in a line to nothing at that distance, so at twelve blocks a
# sixteen leaves a quarter of it): the outlaws' call "at twelve blocks", a curse after the player getting away, a rout
REACH = {key: blocks for family, blocks in (("trade_hold", 32), ("trade_lost", 32), ("trade_flee", 24))
         for key, ln in LINES.items() if ln.family == family}


def delivery(line, timbre=None):
    d = dict(speed=1.0, pitch=0, grit=0.0, tremor=0, level=0.0, wet=None)
    d.update(DELIVERY.get(line.family, {}))
    if (line.key, timbre) in LEVEL_FOR:
        d["level"] = LEVEL_FOR[(line.key, timbre)]
    elif line.key in LEVEL_FOR:
        d["level"] = LEVEL_FOR[line.key]
    return d


def speed(line, timbre):
    """how quickly Kokoro reads a line: a crier line as the crier cries (CRIER_SPEED), else the timbre's own times its
    delivery's and the line's own"""
    if line.crier:
        return mtv.CRIER_SPEED_FOR.get((line.key, timbre), mtv.CRIER_SPEED[timbre])
    return mtv.TIMBRES[timbre]["speed"] * delivery(line)["speed"] * SPEED_FOR.get((line.key, timbre), 1.0)


def lines(timbres=None, keys=None):
    """{timbre: [Line, ...]}: the lines each timbre says ([timbres]: only these, [keys]: a regex on the key)"""
    out = {}
    for timbre in TIMBRES:
        if timbres and timbre not in timbres:
            continue
        out[timbre] = [ln for ln in LINES.values() if timbre in ln.timbres and (not keys or re.search(keys, ln.key))]
    return out


def spoken(line):
    text = line.text
    for a, b in SAY_AS.get(line.key, {}).items():
        text = text.replace(a, b)
    return text


def said(k, line, timbre):
    """the phonemes Kokoro is given for [line] (with the Kokoro k), with the line's lead-in in front"""
    full = FULL.get((line.key, timbre)) or FULL.get(line.key)
    if full:
        return LEAD_IN.get(line.key, "") + full
    if line.crier:
        ph = mtv.crier_phonemes(k, line.key, timbre)
        fixes = CRIER_FIXES + CRIER_FIXES_FOR.get((line.key, timbre), [])
    else:
        import make_talk_voices as talk
        text = spoken(line)
        ph = talk.rhotic(talk.fix_phonemes(k.tokenizer.phonemize(text, mtv.TIMBRES[timbre]["lang"])),
                         k.tokenizer.phonemize(text, "en-us"))
        fixes = PHONEMES + PHONEMES_FOR.get((line.key, timbre), [])
    for a, b in fixes:
        ph = ph.replace(a, b)
    return LEAD_IN.get(line.key, "") + ph


def kokoro(a):
    return mtv.kokoro(types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data))


def ogg(assets, timbre, key):
    return os.path.join(assets, "sounds", mtv.SOUND_DIR, timbre, key + ".ogg")


# ------------------------------------------------------------------ stage 1: TTS
def cmd_tts(a):
    import soundfile as sf
    k = kokoro(a)
    for timbre, todo in lines(a.timbres, a.keys).items():
        t = mtv.TIMBRES[timbre]
        folder = os.path.join(a.out, timbre)
        os.makedirs(folder, exist_ok=True)
        for line in todo:
            fn = os.path.join(folder, line.key + ".wav")
            if os.path.exists(fn) and not a.force:
                continue
            sp = speed(line, timbre)
            audio, sr = k.create(said(k, line, timbre), voice=t["voice"], speed=sp, lang=t["lang"], is_phonemes=True)
            if line.key in LEAD_IN:
                audio = mtv.cut_lead_in(audio, sr)
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
            print(fn, f"speed {sp:.3f}  {len(audio) / sr:.2f}s", flush=True)


def cmd_phonemes(a):
    k = kokoro(a)
    for timbre, todo in lines(a.timbres, a.keys).items():
        for line in todo:
            print(f"{timbre:6s} {line.key:24s} {said(k, line, timbre)}")


# ------------------------------------------------------------------ stage 2: processing
def render(x, sr, line, timbre):
    """a raw take (mono, at its own rate [sr]) as the game plays it: processed as its kind of line is (a crier line cried,
    an everyday one in its delivery), at the pipeline's output rate -> (signal, the longest it may last in seconds)"""
    from sfxkit import SR
    if line.crier:
        y = mtv.process_crier(x, sr, timbre, line.key)
        limit = CRIER_LIMIT
    else:
        d = delivery(line, timbre)
        st = dict(mtv.STYLE[timbre])
        st["pitch"] += d["pitch"]
        st["grit"] = min(0.5, st["grit"] + d["grit"])
        st["tremor"] += d["tremor"]
        if d["wet"] is not None:
            st["wet"] = d["wet"]
        y = mtv.process_line(x, sr, st, line.key, timbre, d["level"])
        limit = LIMIT
    return mtv.sox(y, SR, out_sr=mtv.OUT_SR), limit


def write_take(out, y):
    """encodes [y] to the Ogg at [out], held under -1 dBFS true peak in the encoded file, the encoder's overshoot too ->
    the signal as written"""
    from scipy import signal
    from sfxkit import read_audio, write_ogg
    for _ in range(3):
        write_ogg(out, y, sr=mtv.OUT_SR, quality=3)
        dec = read_audio(out, sr=mtv.OUT_SR)[0]
        peak = 20 * np.log10(np.max(np.abs(signal.resample_poly(dec, 4, 1))) + 1e-12) + 1.0
        if peak <= 0:
            break
        y = y * 10 ** (-(peak + 0.05) / 20)
    return y


def cmd_process(a):
    import warnings
    from scipy.io import wavfile
    from sfxkit import write_wav
    over = []
    for timbre, todo in lines(a.timbres, a.keys).items():
        for line in todo:
            with warnings.catch_warnings():  # the float WAV's PEAK chunk, which scipy skips
                warnings.simplefilter("ignore", wavfile.WavFileWarning)
                sr, x = wavfile.read(os.path.join(a.raw, timbre, line.key + ".wav"))
            x = x.astype(np.float64)
            if x.ndim > 1:
                x = x.mean(axis=1)
            y, limit = render(x, sr, line, timbre)
            seconds = len(y) / mtv.OUT_SR
            if seconds > limit:
                over.append(f"{timbre} {line.key} {seconds:.2f}s, {seconds - limit:.2f}s over {limit}")
                print(f"REFUSED {timbre}/{line.key}: {seconds:.2f}s is over its {limit}s: shorten the wording or "
                      "quicken it in SPEED_FOR", flush=True)
                continue
            out = ogg(a.assets, timbre, line.key)
            y = write_take(out, y)
            if a.wav:
                write_wav(os.path.join(a.wav, timbre, line.key + ".wav"), y, sr=mtv.OUT_SR)
            print(f"{out}  {seconds:.2f}s", flush=True)
    if over:
        print(f"{len(over)} takes refused, over their limits:\n  " + "\n  ".join(over))
        sys.exit(1)


# ------------------------------------------------------------------ every take, the talk families' too
class Take:
    def __init__(self, timbre, key, text, limit, group, when, who):
        self.timbre, self.key, self.text, self.limit, self.group, self.when, self.who = \
            timbre, key, text, limit, group, when, who


def takes(timbres=None, keys=None, talk=True):
    """[Take, ...] in the order the design lists them: each line in each of its timbres, then the talk families"""
    out = []
    for group, who, families in TABLE:
        for family, when, ts, ws in families:
            for key in [k for k, ln in LINES.items() if ln.family == family]:
                ln = LINES[key]
                for timbre in ts:
                    if (not timbres or timbre in timbres) and (not keys or re.search(keys, key)):
                        out.append(Take(timbre, key, ln.text, CRIER_LIMIT if ln.crier else LIMIT, group, when, who))
    if talk:
        sets = {"MWO": MWO, "CHILD": ("child",)}
        for family, (who, said_when, ws) in trade_talk.families().items():
            for i, w in enumerate(ws):
                key = f"talk.{family}_{i}"
                for timbre in sets[who]:
                    if (not timbres or timbre in timbres) and (not keys or re.search(keys, key)):
                        out.append(Take(timbre, key, w, TALK_LIMIT, "Talk: " + family, said_when, "townsfolk"))
    return out


# ------------------------------------------------------------------ the recogniser
def words(s):
    return re.sub(r"[^a-z' ]+", " ", s.lower().replace("’", "'")).split()


# What the recogniser can write without having heard a different word, so that `check` can tell a take heard word for word
# from one heard right but for these: its notes of a sound in brackets ("(buzzing)", "[BLANK_AUDIO]", or one cut off at
# "(buzz" as the room tail dies away), a possessive's or a contraction's apostrophe, a British or American spelling, a
# compound written as two words, and words it can only pick between because they sound the same (mind and mined are one
# sound; so are chalked and chocked to Kokoro's American voices, and "Wagon's" and "waggons")
HOMOPHONES = {"haul": "hall", "hours": "ours", "hooray": "hurrah", "hoorah": "hurrah", "honor": "honour",
              "mined": "mind", "chocked": "chalked", "waggons": "wagons"}


def heard_words(heard):
    return words(re.sub(r"[(\[][^)\]]*[)\]]?", " ", heard))


def loosely(ws):
    s = " ".join(ws).replace("'", "").replace("strong box", "strongbox")
    return [HOMOPHONES.get(w, w) for w in s.split()]


def looped(heard, want):
    """the recogniser's decoder looping on a word: the same word four times running, or far more words than were written"""
    h = heard_words(heard)
    return len(h) > 2 * len(want) + 3 or any(h[i] == h[i + 1] == h[i + 2] == h[i + 3] for i in range(len(h) - 3))


def listen_to(rec, path, want):
    """what the recogniser hears in the encoded take at [path], retried once with a breath of silence either side if its
    decoder loops on a word"""
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", "16000", "-"],
                         check=True, capture_output=True).stdout
    x = np.frombuffer(pcm, dtype=np.float32)
    heard = ""
    for pad in (0.0, 0.4):
        y = np.concatenate([np.zeros(int(16000 * pad), np.float32), x, np.zeros(int(16000 * pad), np.float32)])
        st = rec.create_stream()
        st.accept_waveform(16000, y)
        rec.decode_stream(st)
        heard = st.result.text.strip()
        if not looped(heard, want):
            break
    return heard


def cmd_check(a):
    """Whisper base.en (sherpa-onnx) hears each encoded take; prints the words as heard beside any take not heard word for
    word ("~" where the words are right but for what HOMOPHONES and its neighbours allow, "!" where one is wrong), and the
    share of words heard right"""
    import sherpa_onnx
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=os.path.join(a.asr, "base.en-encoder.int8.onnx"), decoder=os.path.join(a.asr, "base.en-decoder.int8.onnx"),
        tokens=os.path.join(a.asr, "base.en-tokens.txt"), num_threads=4, language="en", task="transcribe")
    right = total = exact = loose = count = 0
    results = []
    for t in takes(a.timbres, a.keys):
        path = ogg(a.assets, t.timbre, t.key)
        if not os.path.exists(path):
            print(f"-  {t.timbre:6s} {t.key:34s} NOT RECORDED", flush=True)
            continue
        want = words(t.text)
        heard = listen_to(rec, path, want)
        got = heard_words(heard)
        hits = sum(b.size for b in difflib.SequenceMatcher(a=want, b=got).get_matching_blocks())
        right += hits
        total += len(want)
        count += 1
        same = want == got
        near = not same and loosely(want) == loosely(got)
        exact += same
        loose += near
        results.append(dict(timbre=t.timbre, key=t.key, want=t.text, heard=heard, exact=same, near=near))
        print(f"{'  ' if same else '~ ' if near else '! '}{t.timbre:6s} {t.key:34s} {heard}", flush=True)
    print(f"{exact} of {count} takes word for word, {loose} more right but for a homophone, a spelling, an apostrophe or a "
          f"note of a sound, {count - exact - loose} heard wrong; {100.0 * right / max(1, total):.1f}% of words")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=1, ensure_ascii=False)


# ------------------------------------------------------------------ the loudness report
def true_peak_db(y):
    from scipy import signal
    return 20 * np.log10(np.max(np.abs(signal.resample_poly(y, 4, 1))) + 1e-12)


def spikes(y, sr):
    """the largest jump of the signal's second difference over a take's own speech, the measure of a click: how many
    times the median speech frame's"""
    d = np.abs(np.diff(y, 2))
    n = max(1, int(0.002 * sr))
    frames = np.sqrt(np.convolve(d * d, np.ones(n) / n, mode="valid"))
    speech = frames[frames > 0.1 * frames.max()]
    return float(frames.max() / (np.median(speech) + 1e-12)) if len(speech) else 0.0


def cmd_loudness(a):
    from make_townsfolk_sfx import integrated_loudness
    from sfxkit import SR, read_audio
    reference = []
    base = os.path.join(a.assets, "sounds", mtv.SOUND_DIR)
    for timbre in MW:
        names = sorted(f for f in os.listdir(os.path.join(base, timbre))
                       if f.endswith(".ogg") and f.startswith(("order_", "stall_", "carter_", "homestead_", "greet_",
                                                               "gossip_", "farmer_", "fisher_")))
        reference += [os.path.join(base, timbre, f) for f in names[::2]]
    ref = [integrated_loudness(read_audio(p, sr=SR)[0]) for p in reference]
    ref_spikes = [spikes(read_audio(p, sr=SR)[0], SR) for p in reference]
    mean_ref = float(np.mean(ref))
    print(f"reference: {len(ref)} everyday takes (the keepers' and builders' and greetings'), {mean_ref:.1f} LUFS on average "
          f"({min(ref):.1f} to {max(ref):.1f}), largest click measure {max(ref_spikes):.0f}")
    groups = {}
    rows = []
    for t in takes(None, a.keys):
        path = ogg(a.assets, t.timbre, t.key)
        if not os.path.exists(path):
            continue
        y = read_audio(path, sr=SR)[0]
        lufs = integrated_loudness(y)
        line = LINES.get(t.key)
        if line is not None and line.crier:
            expected = mtv.CRIER_LUFS
        elif line is not None:
            expected = mean_ref + delivery(line)["level"]
        else:
            expected = mean_ref
        row = dict(t=t, seconds=len(y) / SR, lufs=lufs, peak=true_peak_db(y), spike=spikes(y, SR), expected=expected)
        rows.append(row)
        groups.setdefault(t.group, []).append(row)
    flagged = []
    for group, rs in groups.items():
        l = [r["lufs"] for r in rs]
        d = [r["lufs"] - r["expected"] for r in rs]
        print(f"{group[:62]:62s} {len(rs):3d} takes  {np.mean(l):6.1f} LUFS ({min(l):.1f} to {max(l):.1f})  "
              f"vs. expected {np.mean(d):+.1f} (worst {max(d, key=abs):+.1f})  longest {max(r['seconds'] for r in rs):.2f}s")
    for r in rows:
        t = r["t"]
        notes = []
        if abs(r["lufs"] - r["expected"]) > 2.0:
            notes.append(f"{r['lufs'] - r['expected']:+.1f} LU from its level")
        if r["peak"] > -1.0:
            notes.append(f"true peak {r['peak']:.2f} dBTP")
        if r["spike"] > max(ref_spikes) * 1.5:
            notes.append(f"click measure {r['spike']:.0f}")
        if r["seconds"] > t.limit:
            notes.append(f"{r['seconds']:.2f}s over {t.limit}")
        if notes:
            flagged.append(f"  {t.timbre:6s} {t.key:34s} {r['lufs']:.1f} LUFS  " + "; ".join(notes))
    allp = [r["peak"] for r in rows]
    print(f"{len(rows)} takes; true peaks {min(allp):.2f} to {max(allp):.2f} dBTP; {len(flagged)} flagged")
    for f in flagged:
        print(f)


# ------------------------------------------------------------------ the listening copy
def stamp(seconds):
    return f"{int(seconds // 60):02d}:{int(seconds % 60):02d}"


def cmd_listen(a):
    """every take in turn, each line in each of its timbres, as one MP3 and a text file of what is said when"""
    from sfxkit import read_audio
    rate = 32000
    parts, cues = [], []
    clock = 0.0
    last_group = last_family = None
    shown = takes(a.timbres, a.keys)
    for i, t in enumerate(shown):
        path = ogg(a.assets, t.timbre, t.key)
        if not os.path.exists(path):
            sys.exit(f"{t.timbre}/{t.key} is not recorded yet")
        y = read_audio(path, sr=rate)[0].astype(np.float32)
        family = t.key.rsplit("_", 1)[0] if re.search(r"_\d+$", t.key) else t.key
        if t.group != last_group:
            cues.append(("group", t.group, t.who))
            last_group = t.group
        if family != last_family:
            cues.append(("family", family, t.when))
            last_family = family
        cues.append(("take", clock, t))
        parts.append(y)
        clock += len(y) / rate
        nxt = shown[i + 1] if i + 1 < len(shown) else None
        gap = 0.55 if nxt and nxt.key == t.key else 1.05
        if nxt and nxt.group != t.group:
            gap = 2.0
        parts.append(np.zeros(int(rate * gap), np.float32))
        clock += gap
    audio = np.concatenate(parts)
    os.makedirs(a.out, exist_ok=True)
    mp3 = os.path.join(a.out, "trade-voices.mp3")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(rate), "-ac", "1", "-i", "-", "-codec:a",
                    "libmp3lame", "-q:a", "3", mp3], input=audio.tobytes(), check=True)
    text = [
        "Guildmark: the trader's life, voiced (Trade X6). Every new line in turn, each in the voices that say it.",
        "",
        f"{len(shown)} takes, {stamp(clock)} in all: {os.path.basename(mp3)}. A take is followed by the same line in the next",
        "voice (man, woman, old man; the child's lines are alone), then the next line; a short silence between each. Levels are",
        "the game's own: the crier is cried louder than the street, the outlaws and the cheer louder than the counter. The words",
        "on the left are what the game shows beneath the voice as its subtitle; the key is what the code says.",
        "",
    ]
    for kind, *rest in cues:
        if kind == "group":
            text += ["", "=" * 100, rest[0].upper() + f"   [{rest[1]}]", "=" * 100]
        elif kind == "family":
            text += ["", f"{rest[0]}   when: {rest[1]}"]
        else:
            at, t = rest
            text.append(f"  {stamp(at)}  {t.timbre:6s} {t.key:32s} {t.text}")
    with open(os.path.join(a.out, "trade-voices.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(text) + "\n")
    print(mp3, f"{os.path.getsize(mp3) / 1e6:.1f} MB,", stamp(clock), "and", os.path.join(a.out, "trade-voices.txt"))


# ------------------------------------------------------------------ stage 4: the fragments the lead merges
def entries(assets=None):
    """(sounds.json entries, en_us.json entries) for the lines: the crier's heard across the square, a shout across a
    distance as far as REACH says (keys without the namespace, in the files' own form)"""
    sounds, lang = {}, {}
    missing = []
    for timbre in TIMBRES:
        for line in LINES.values():
            if timbre not in line.timbres:
                continue
            name = f"{mtv.SOUND_DIR}/{timbre}/{line.key}"
            if assets and not os.path.exists(os.path.join(assets, "sounds", name + ".ogg")):
                missing.append(name)
            sound = f"guildmark:{name}"
            if line.crier:
                sound = {"name": sound, "attenuation_distance": mtv.CRIER_REACH}
            elif line.key in REACH:
                sound = {"name": sound, "attenuation_distance": REACH[line.key]}
            sounds[f"townsfolk.voice.{timbre}.{line.key}"] = {"sounds": [sound], "subtitle": f"say.guildmark.{line.key}"}
    for line in LINES.values():
        lang[f"say.guildmark.{line.key}"] = line.text
    return sounds, lang, missing


def cmd_fragments(a):
    sounds, lang, missing = entries(a.assets)
    if missing:
        sys.exit(f"{len(missing)} takes not processed yet, e.g. {missing[:5]}")
    for name, data in (("trade_voices_sounds.json", sounds), ("trade_voices_lang.json", lang)):
        path = os.path.join(HERE, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(path, len(data), "entries")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("tts", "phonemes"):
        t = sub.add_parser(name)
        t.add_argument("--model", required=True)
        t.add_argument("--voices", required=True)
        if name == "tts":
            t.add_argument("--out", required=True)
            t.add_argument("--force", action="store_true", help="read again takes that are already there")
        t.add_argument("--espeak-data", default=None,
                       help="a copy of espeakng_loader's espeak-ng-data at a short path (espeak-ng caps the path at "
                            "160 chars)")
        t.add_argument("--keys", default=None, help="only lines whose name matches this regex")
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
    c.add_argument("--json", default=None, help="also write the results here")
    ld = sub.add_parser("loudness")
    ld.add_argument("--assets", required=True)
    ld.add_argument("--keys", default=None)
    ls = sub.add_parser("listen")
    ls.add_argument("--assets", required=True)
    ls.add_argument("--out", required=True)
    ls.add_argument("--keys", default=None)
    ls.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    f = sub.add_parser("fragments")
    f.add_argument("--assets", default=os.path.join(REPO, "src", "main", "resources", "assets", "guildmark"),
                   help="checks that every voice line has been rendered there")
    a = ap.parse_args()
    {"tts": cmd_tts, "phonemes": cmd_phonemes, "process": cmd_process, "check": cmd_check, "loudness": cmd_loudness,
     "listen": cmd_listen, "fragments": cmd_fragments}[a.cmd](a)


if __name__ == "__main__":
    main()
