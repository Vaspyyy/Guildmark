"""The city's voices: market criers, the night watchman and the chatter of the crowds, read by Kokoro TTS.

This script only makes the raw takes; the recipes in ambience/voices.py turn them into the game's sounds
(make_ambience.py --raw <raw_dir>). One take per line, a mono 24 kHz WAV named by a stable stem:

- hawker_01_bread .. hawker_12_chestnuts: twelve criers, each in a voice of their own (six women, six men),
  read a little slower than normal; the recipe raises the pitch and draws the last vowels out into a call.
- watch_ten .. watch_five: the night watchman calling the hours, all in one deep, slow voice.
- crowd_day_01 .. crowd_day_84: market small talk, haggling and greetings, three takes in each of Kokoro's 28
  English voices at speeds from 0.92 to 1.15. The crowd_day recipe cuts them into phrases and overlaps them.
- crowd_light_01 .. crowd_light_24: quieter street talk for crowd_light, eight talkers of three takes each,
  so the two beds share no phrases.

None of the criers or the watchman uses a voice the named characters (or Halvard) speak with.

Stage (run from the repo root):

   <venv>/bin/python -I -B art/audio/make_city_voices.py tts --model kokoro-v1.0.onnx \\
       --voices voices-v1.0.bin --out <raw_dir> [--only hawker_,watch_ten] [--espeak-data <dir>]

--only takes stems or stem prefixes, comma separated. The whole set takes about two minutes on four cores.
"""
import argparse
import os
import sys

import numpy as np

# Market criers: stem, Kokoro voice, speed, accent, line. The voices were picked by ear-proxy: each line came
# back word for word from Whisper. Some lines needed a particular accent: af_sarah's American "hot" was heard
# as "hard", so she reads with British phonemes; am_puck's "Hear ye... the guild" came back as "here, here...
# the gold", am_echo's came back right.
HAWKERS = [
    ("hawker_01_bread", "bf_isabella", 0.9, "en-gb", "Fresh bread! Warm from the oven!"),
    ("hawker_02_apples", "am_eric", 0.92, "en-us", "Apples! Sweet red apples, three for a mark!"),
    ("hawker_03_fish", "bm_daniel", 0.9, "en-gb", "Fish! Fresh fish, caught this morning!"),
    ("hawker_04_pies", "af_sarah", 0.9, "en-gb", "Hot pies! Meat pies, hot and fresh!"),
    ("hawker_05_candles", "am_fenrir", 0.9, "en-us", "Candles and lamp oil! Keep the dark away!"),
    ("hawker_06_honey", "af_aoede", 0.92, "en-us", "Honey cakes! Sweet honey cakes!"),
    ("hawker_07_cloth", "bf_lily", 0.9, "en-gb", "Fine cloth from the south! Feel the weave!"),
    ("hawker_08_knives", "am_michael", 0.92, "en-us", "Knives sharpened! Swords and axes!"),
    ("hawker_09_guild", "am_echo", 0.88, "en-gb", "Hear ye, hear ye! The guild seeks brave hands!"),
    ("hawker_10_flowers", "af_kore", 0.92, "en-us", "Flowers! Flowers for your sweetheart!"),
    ("hawker_11_potions", "bf_alice", 0.9, "en-gb", "Potions and remedies! Cure what ails you!"),
    ("hawker_12_chestnuts", "am_liam", 0.92, "en-us", "Roasted chestnuts! Get them while they're hot!"),
]

# The night watchman: one deep voice, slow, for every hour. He calls in two parts, the hour and then "and
# all's well", so each part is read on its own and the two are joined with WATCH_PAUSE seconds of silence
WATCH_VOICE = dict(voice="am_onyx", speed=0.82, lang="en-gb")
WATCH_PAUSE = 0.35
WATCH = [
    ("watch_ten", "Ten o'clock, and all's well!"),
    ("watch_eleven", "Eleven o'clock, and all's well!"),
    ("watch_midnight", "Midnight, and all's well!"),
    ("watch_one", "One o'clock, and all's well!"),
    ("watch_two", "Two o'clock, and all's well!"),
    ("watch_three", "Three o'clock, and all's well!"),
    ("watch_four", "Four o'clock, and all's well!"),
    ("watch_five", "Five o'clock, and dawn is near!"),
]

# Market chatter for crowd_day: three takes in each English voice (stem crowd_day_NN, in this order)
CROWD_DAY_TAKES = [
    ("af_alloy", 1.0, "Good morning! How's the family keeping these days?"),
    ("af_alloy", 1.1, "Three marks? For that? I'll give you two, and not a copper more."),
    ("af_alloy", 0.95, "Oh, I couldn't possibly. Well, maybe just a small one."),
    ("af_aoede", 1.05, "Have you seen the price of salt this week? It's robbery, I tell you."),
    ("af_aoede", 0.92, "These onions are lovely. I'll take a dozen, if you please."),
    ("af_aoede", 1.12, "Wait for me by the fountain, I won't be long."),
    ("af_bella", 1.0, "No, no, the blue one. The one at the back, there."),
    ("af_bella", 1.08, "Is it fresh? It looks like it's been sitting out since dawn."),
    ("af_bella", 0.94, "Tell your mother I was asking after her, won't you?"),
    ("af_heart", 1.1, "Well, I never! Fancy seeing you here, of all places."),
    ("af_heart", 0.97, "We're going to need more flour before the festival."),
    ("af_heart", 1.03, "Do you have any of those little almond biscuits left?"),
    ("af_jessica", 1.06, "Did you hear about the miller's boy? Joined the guild, he did."),
    ("af_jessica", 0.93, "Lovely weather for it, isn't it? Not a cloud in the sky."),
    ("af_jessica", 1.0, "Put it in the basket with the eggs. Gently, now."),
    ("af_kore", 0.98, "Keep an eye on your purse, there are cutpurses about."),
    ("af_kore", 1.12, "Can you hold this for me a moment? I've lost my glove."),
    ("af_kore", 1.04, "My sister makes it with cream and a little nutmeg."),
    ("af_nicole", 1.0, "That's the last of the plums, I'm afraid. Come back tomorrow."),
    ("af_nicole", 0.95, "Oh, it's beautiful work. Did you make this yourself?"),
    ("af_nicole", 1.08, "I'll think about it. Let me have a look around first."),
    ("af_nova", 1.15, "Half a mark? You must be joking."),
    ("af_nova", 1.0, "Where's the stall with the spices? I can never find it."),
    ("af_nova", 0.92, "She's been waiting since sunrise for that cart to come in."),
    ("af_river", 0.96, "My feet are aching. Shall we sit down for a bit?"),
    ("af_river", 1.06, "It's the best honey this side of the river, ask anyone."),
    ("af_river", 1.12, "Hello again! Same as last week?"),
    ("af_sarah", 1.02, "Oh, don't start with that again. Not today."),
    ("af_sarah", 0.94, "I'd like a loaf of rye and two of the sweet rolls."),
    ("af_sarah", 1.1, "You look well! Married life suits you."),
    ("af_sky", 1.05, "What a lovely shawl! Where did you find it?"),
    ("af_sky", 0.98, "Fresh eggs? Do you have any brown ones left?"),
    ("af_sky", 1.13, "Come on, the children will be wanting their supper."),
    ("am_adam", 1.0, "Morning! Busy as ever, I see."),
    ("am_adam", 0.95, "That's a fine knife. What would you want for it?"),
    ("am_adam", 1.08, "Put it on my account, I'll settle up next week."),
    ("am_echo", 1.04, "I told him, you'll not find better leather in the whole city."),
    ("am_echo", 0.93, "Right then, wrap it up and I'll be on my way."),
    ("am_echo", 1.1, "The guard has doubled the toll at the east gate, did you know?"),
    ("am_eric", 1.0, "Could you break a silver? I've nothing smaller, sorry."),
    ("am_eric", 1.12, "We'll split it, then. Half each, fair and square."),
    ("am_eric", 0.94, "Not bad, not bad at all. Could be worse."),
    ("am_fenrir", 0.97, "Is it true they found another ruin up in the hills?"),
    ("am_fenrir", 1.06, "That one's mine, thank you very much."),
    ("am_fenrir", 1.0, "Those boots won't last you the winter, mark my words."),
    ("am_liam", 1.08, "Ah, there you are! I've been looking everywhere for you."),
    ("am_liam", 0.95, "The fishmonger's got eels again, if you fancy some."),
    ("am_liam", 1.02, "If it rains again, the whole harvest will rot."),
    ("am_michael", 0.98, "Four, five, six. There you go, and keep the change."),
    ("am_michael", 1.14, "Excuse me, sorry, coming through."),
    ("am_michael", 1.0, "My cousin says the roads are safer since the knights came back."),
    ("am_onyx", 1.03, "Don't be daft, it's far too big for you."),
    ("am_onyx", 0.92, "We should be getting back before the bells ring."),
    ("am_onyx", 1.1, "Oh, go on then. Just the one."),
    ("am_puck", 1.06, "I swear that rooster gets louder every morning."),
    ("am_puck", 0.96, "No thank you, I've got plenty at home."),
    ("am_puck", 1.12, "He's always late, that one. Every single time."),
    ("am_santa", 1.0, "Shall we meet at the tavern after the market closes?"),
    ("am_santa", 0.93, "Careful with that basket, those are the good plates."),
    ("am_santa", 1.07, "That comes to two marks and a half, all told."),
    ("bf_alice", 1.04, "I've not seen him since the spring fair."),
    ("bf_alice", 0.97, "Look at the size of those pumpkins!"),
    ("bf_alice", 1.12, "It's my last coin, so it had better be worth it."),
    ("bf_emma", 1.0, "Can I try a bit first? Just a little taste."),
    ("bf_emma", 1.08, "Honestly, the queue at the well this morning!"),
    ("bf_emma", 0.94, "Go and fetch your father, will you? Quick, now."),
    ("bf_isabella", 0.98, "Oh, it smells wonderful. What's in it?"),
    ("bf_isabella", 1.1, "Six apples and a bunch of carrots, thanks."),
    ("bf_isabella", 1.02, "Is this the queue for the baker, or the butcher?"),
    ("bf_lily", 1.05, "The smith's apprentice burned his hand again."),
    ("bf_lily", 0.95, "Mind your step, the cobbles are slick by the fountain."),
    ("bf_lily", 1.1, "He said he'd pay me on market day, and here we are again."),
    ("bm_daniel", 1.0, "Three for a mark, or five if you're buying for the inn."),
    ("bm_daniel", 1.12, "You'll have to speak up, it's ever so noisy in here."),
    ("bm_daniel", 0.93, "How's business? Keeping you on your toes?"),
    ("bm_fable", 1.06, "Now that's what I call a proper cheese."),
    ("bm_fable", 0.97, "I'll come back later, when it's quieter."),
    ("bm_fable", 1.1, "Who's minding the stall while you're over here?"),
    ("bm_george", 0.95, "Lovely bit of mutton, that. Fed the whole family for a week."),
    ("bm_george", 1.04, "Good day to you, madam. What can I get you?"),
    ("bm_george", 1.12, "Ten copper, and I'll throw in the string."),
    ("bm_lewis", 1.0, "Hold on, hold on, let me count it again."),
    ("bm_lewis", 0.92, "The weather's turning. We'll have snow by the new moon."),
    ("bm_lewis", 1.08, "Aye, that's right. Same price as always."),
]

# Street talk for crowd_light: eight talkers, three takes each (stem crowd_light_NN, in this order)
CROWD_LIGHT_TAKES = [
    ("af_alloy", 1.0, "I was just on my way to see your brother, actually."),
    ("af_alloy", 0.95, "No, it was the week before. You're thinking of the wedding."),
    ("af_alloy", 1.06, "I keep telling him to fix that shutter, but does he listen?"),
    ("af_river", 1.04, "Oh, was it? I'd forgotten all about that."),
    ("af_river", 0.97, "So I said to her, if you want it done properly, do it yourself."),
    ("af_river", 1.1, "Well, you know what he's like."),
    ("am_echo", 0.98, "We could take the long way round, past the old mill."),
    ("am_echo", 1.05, "Is that the guild hall? I thought it was further along."),
    ("am_echo", 0.93, "Slow down a little, my knees aren't what they used to be."),
    ("bm_daniel", 1.08, "Hold on, I think I dropped something back there."),
    ("bm_daniel", 1.0, "It's quiet today. Everyone must be down at the docks."),
    ("bm_daniel", 0.95, "I'll walk you as far as the bridge, then I'd best get home."),
    ("am_santa", 0.94, "Have you eaten yet? There's stew left over, if you want it."),
    ("am_santa", 1.02, "My grandmother used to live just up this lane."),
    ("am_santa", 1.08, "Mind the puddle! Too late. Never mind."),
    ("af_jessica", 1.0, "The new family on the corner seem nice enough."),
    ("af_jessica", 1.1, "Who told you that? It's not true, not a word of it."),
    ("af_jessica", 0.96, "We'll need to be up early tomorrow, remember."),
    ("af_nova", 1.05, "Hello there! Off to the market?"),
    ("af_nova", 0.98, "Have you got the key? I gave it to you, I'm sure of it."),
    ("af_nova", 1.12, "Smells like someone's baking bread."),
    ("am_adam", 1.0, "Good day to you, sir."),
    ("am_adam", 1.1, "Come along, keep up, we're late as it is."),
    ("am_adam", 0.95, "Was that thunder? I hope it holds off till we're home."),
]

# Spelling the TTS reads better than the written text (none needed so far: every line comes back right
# from Whisper as written)
SAY_AS = {}


def lang_of(voice):
    return "en-gb" if voice.startswith("b") else "en-us"


def takes():
    """[(stem, voice, speed, accent, text)] for every take, in a stable order"""
    out = list(HAWKERS)
    out += [(stem, WATCH_VOICE["voice"], WATCH_VOICE["speed"], WATCH_VOICE["lang"], text) for stem, text in WATCH]
    out += [(f"crowd_day_{i + 1:02d}", v, s, lang_of(v), t) for i, (v, s, t) in enumerate(CROWD_DAY_TAKES)]
    out += [(f"crowd_light_{i + 1:02d}", v, s, lang_of(v), t) for i, (v, s, t) in enumerate(CROWD_LIGHT_TAKES)]
    return out


def spoken(text):
    for a, b in SAY_AS.items():
        text = text.replace(a, b)
    return text


# ------------------------------------------------------------------ stage: TTS
def cmd_tts(a):
    import time
    import soundfile as sf
    import espeakng_loader
    from kokoro_onnx import Kokoro, EspeakConfig
    cfg = EspeakConfig(lib_path=espeakng_loader.get_library_path(),
                       data_path=a.espeak_data or espeakng_loader.get_data_path())
    k = Kokoro(a.model, a.voices, espeak_config=cfg)
    os.makedirs(a.out, exist_ok=True)
    t0, total, count = time.time(), 0.0, 0
    for stem, voice, speed, lang, text in takes():
        if a.only and not any(stem == o or stem.startswith(o) for o in a.only):
            continue
        # the watch calls are read in two parts with a pause between; everything else in one go
        parts = spoken(text).split(", ") if stem.startswith("watch_") else [spoken(text)]
        chunks = []
        for i, part in enumerate(parts):
            audio, sr = k.create(part + ("," if i < len(parts) - 1 else ""), voice=voice, speed=speed, lang=lang)
            if i:
                chunks.append(np.zeros(int(WATCH_PAUSE * sr), dtype=np.float32))
            chunks.append(np.asarray(audio, dtype=np.float32))
        audio = np.concatenate(chunks)
        fn = os.path.join(a.out, stem + ".wav")
        sf.write(fn, np.asarray(audio, dtype=np.float32), sr)
        total += len(audio) / sr
        count += 1
        print(f"{fn}  {voice} x{speed}  {len(audio) / sr:.2f}s", flush=True)
    print(f"{count} takes, {total:.1f}s of speech in {time.time() - t0:.1f}s", flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("tts")
    t.add_argument("--model", required=True)
    t.add_argument("--voices", required=True)
    t.add_argument("--out", required=True)
    t.add_argument("--only", type=lambda s: [x for x in s.split(",") if x], default=None,
                   help="stems or stem prefixes, comma separated (hawker_,watch_ten)")
    t.add_argument("--espeak-data", default=None,
                   help="copy of espeakng_loader's espeak-ng-data at a short path (espeak-ng caps the path at 160 chars)")
    a = ap.parse_args()
    {"tts": cmd_tts}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
