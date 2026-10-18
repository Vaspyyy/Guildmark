"""The townsfolk's voices: what the people of the city say as you pass (greetings, trade cries, gossip,
replies, a laugh, a cry for help, a lamplighter's word on their round, what the three factions' members and a
heist's watchmen say, and the road's: a caravan's driver and guards, and the grumbles while the road is troubled),
read by Kokoro TTS in four voices, one per timbre, then given a light touch of processing so they sit in the street
(a little age for the old man, a child's pitch for the child).

The game picks the timbre from the townsperson (old women use "woman") and varies the pitch a little per
person, so one voice per timbre is enough. Line lengths do not matter to the game, except for the town crier's.

The town crier's lines (CRIER: the day's news, cried in the market square at noon) are in the man's and the woman's
voice, cried rather than said: read slower, each call's last stressed vowel drawn out and a breath between calls,
then projected and set in the square (process_crier). The server gives each of them 100 ticks, so none runs over
4.5 s, and they carry 48 blocks.

Stages (run from the repo root):

1) TTS with Kokoro (kokoro-onnx, Apache-2.0) -> raw 24 kHz WAVs, one folder per timbre
   <venv>/bin/python -I -B art/audio/make_townsfolk_voices.py tts --model kokoro-v1.0.onnx \\
       --voices voices-v1.0.bin --out <raw_dir> [--only man,child] [--keys 'greet'] [--espeak-data <dir>]

2) Processing (numpy/scipy + sox) -> mono 32 kHz Ogg Vorbis in the mod's assets, as
   sounds/townsfolk/voice/<timbre>/<line>.ogg
   python3 -B art/audio/make_townsfolk_voices.py process --raw <raw_dir> \\
       --assets src/main/resources/assets/guildmark [--only ...] [--keys ...] [--wav <dir>]

3) Phonemes: prints what Kokoro will say for each line (after SAY_AS, or the line's PHONEMES), to catch misreadings
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

# man and woman only: the lamplighters' (townsfolk/Lamplighters.kt), said now and then on their round to whoever's by
# (dusk: after a lamp is lit; dawn: after one is put out) and to someone who stops them (hello)
LAMPLIGHTER = {
    "lamplighter_dusk_0": "There we are. That'll see you home.",
    "lamplighter_dusk_1": "Evening. The lamps are going up.",
    "lamplighter_dusk_2": "Not a dark street in the city tonight.",
    "lamplighter_dusk_3": "Burn bright, old girl.",
    "lamplighter_dawn_0": "Morning. The sun can take it from here.",
    "lamplighter_dawn_1": "There. That's the night seen off.",
    "lamplighter_dawn_2": "Rest now. I'll be back at dusk.",
    "lamplighter_hello_0": "Can't stop long. I've a round to finish.",
    "lamplighter_hello_1": "Mind the pole, friend.",
    "lamplighter_hello_2": "Someone has to see to the lamps.",
}

# man, woman and old: the faction members' street lines (the Starwatch's mages, the Hearthsworn's knights, the
# Quiet Hand's thieves: their everyday lines, what they say to kin and to the expelled) and what a heist's
# watchmen say (alarm: shouts, suspect: wary, calm: muttered, purse: a victim's cries). The text is the language
# file's say.guildmark.<line>, character for character
FACTION = {
    "mage_0": "The stars were clear last night. Rare, these days.",
    "mage_1": "Mind the lecterns.",
    "mage_2": "Has anyone seen my quill? I had it a moment ago.",
    "mage_3": "Four hundred years of star charts, and no two nights alike.",
    "mage_4": "Patience. The weave rewards it.",
    "mage_5": "Light bends, if you ask it nicely.",
    "mage_kin_0": "Well met, friend of the Starwatch.",
    "mage_kin_1": "Light keep you, colleague.",
    "knight_0": "Keep the fire. Keep the road.",
    "knight_1": "Ring's open, if you fancy a bout.",
    "knight_2": "Watch your feet. Most fights are lost at the feet.",
    "knight_3": "The Hollow walked again last night.",
    "knight_4": "Shield up. Always shield up.",
    "knight_5": "The Commander had us drilling before dawn.",
    "knight_kin_0": "Hearth and road, friend.",
    "knight_kin_1": "Good to have you with us.",
    "knight_cold_0": "We have nothing to say to thieves.",
    "thief_0": "Didn't see me. Nobody ever does.",
    "thief_1": "Nice boots. Shame if they walked off.",
    "thief_2": "Keep your voice down.",
    "thief_3": "Lovely night for it.",
    "thief_4": "I've a cousin who can get you one of those. Cheap.",
    "thief_5": "Mind your purse round here. Not everyone's as honest as me.",
    "thief_kin_0": "Quietly does it.",
    "thief_kin_1": "The Widow speaks well of you. Don't let it go to your head.",
    "thief_cold_0": "Keep walking, knight's dog.",
    "alarm_0": "Thief! Stop, thief!",
    "alarm_1": "Guards! Guards!",
    "alarm_2": "Someone's in here!",
    "suspect_0": "Hm? Who's there?",
    "suspect_1": "Did you hear that?",
    "suspect_2": "I know someone's there.",
    "calm_0": "Must've been the wind.",
    "calm_1": "Rats, I suppose.",
    "purse_0": "My purse! Where's my purse?",
    "purse_1": "Hey! That's mine!",
}

# man, woman and old: the road's (M4, design-road.md section 5). A caravan's driver, a stranger from the far end of the
# road, on the bench (to the horse, to themself, to a passer-by, struck), then at the gate after a waylaying, and to a
# player who escorts the wagon; and what the townsfolk say while their road is troubled, then once it's clear again.
# The text is the language file's say.guildmark.<line>, character for character
ROAD = {
    "driver_walk_on": "Walk on, Daisy.",
    "driver_quiet": "Road's quiet. I don't like it.",
    "driver_greet": "Good day! Mind the wheels, now.",
    "driver_hold": "Whoa, girl! Whoa!",
    "driver_trees": "Look out! Outlaws!",
    "driver_took": "They came out of nowhere and took the lot.",
    "driver_ran": "I ran. I'm not proud of it.",
    "driver_mark": "Their camp's out that way. Here, I'll show you.",
    "driver_next": "The next one won't go without more spears.",
    "driver_escort_meet": "You're our escort? Then let's be off.",
    "driver_escort_done": "Never been so glad to see a wall.",
    "gossip_road_1": "The wagons aren't safe on the road these days.",
    "gossip_road_2": "Everything off the wagons is dear now.",
    "gossip_road_3": "My brother drives a wagon. I worry for him.",
    "gossip_road_4": "Outlaws on the road, and nobody does a thing.",
    "gossip_road_5": "Somebody ought to deal with those outlaws.",
    "gossip_road_6": "They say the last wagon never came in.",
    "gossip_road_7": "Bread's gone up again. It's that road.",
    "gossip_road_8": "I won't set foot outside the walls till it's sorted.",
    "gossip_road_clear_1": "Wagons are coming in again. About time.",
    "gossip_road_clear_2": "They say the outlaws were broken. Good riddance.",
    "gossip_road_clear_3": "Prices are coming down, thank goodness.",
    "gossip_road_clear_4": "The road's safe again, they say.",
}

# man and woman only: a caravan's guards (townsfolk guards from the far end), in a fight on the road and to an escort
ROAD_GUARD = {
    "guard_to_wagon": "To the wagon!",
    "guard_together": "Stand together!",
    "guard_beaten_off": "That's it, run! And stay gone!",
    "guard_cant_hold": "There's too many! Can't hold them!",
    "guard_glad": "Glad of you. Watch the treeline.",
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

# ------------------------------------------------------------------ the town crier
# At noon a townsperson in each capital cries the day's news in the market square and pins it up on the market
# board: what happened to the town's market, which prices moved and why, word from the land. Each line
# here is one piece of that news, in the man's and the woman's voice (an old crier says the man's lines a little
# lower, as old men do with every line of the man's). The text is the language file's say.guildmark.<line>,
# character for character, and how the crier says it is set further down (CRIER_SPEED to CRIER_PHONEMES).
CRIER_TIMBRES = ("man", "woman")
CRIER = {
    # the opening call
    "crier_open_0": "Oyez! Oyez! Oyez!",
    "crier_open_1": "Hear ye, hear ye! News from the market!",
    "crier_open_2": "Gather round, good people, and hear the news!",
    # trouble or luck that has begun
    "crier_blight_wheat": "Blight in the fields! The wheat is failing!",
    "crier_blight_carrot": "Blight in the fields! The carrots are failing!",
    "crier_blight_potato": "Blight in the fields! The potatoes are failing!",
    "crier_blight_beetroot": "Blight in the fields! The beetroot is failing!",
    "crier_bumper_wheat": "A bumper crop of wheat is coming in!",
    "crier_bumper_carrot": "A bumper crop of carrots is coming in!",
    "crier_bumper_potato": "A bumper crop of potatoes is coming in!",
    "crier_bumper_beetroot": "A bumper crop of beetroot is coming in!",
    "crier_murrain": "Murrain among the herds! Meat and hides will be dear!",
    "crier_fowl_pest": "A pest has got among the hens!",
    "crier_mill_broken": "The windmill's sails are broken! Flour will be short!",
    "crier_oven_cracked": "The bakery's oven has cracked! Bread will be short!",
    "crier_good_catch": "The boats are coming in full! Fish is cheap at the quay!",
    "crier_poor_catch": "The nets come up empty! Fish will be dear!",
    "crier_fever": "A fever is going round the town! Keep warm, and rest!",
    # trouble that is over
    "crier_over_blight": "The blight has passed from the fields!",
    "crier_over_murrain": "The herds are healthy again!",
    "crier_over_fowl_pest": "The hens are laying again!",
    "crier_over_mill_broken": "The windmill turns again!",
    "crier_over_oven_cracked": "The bakery's oven is mended!",
    "crier_over_poor_catch": "The fish are back in the nets!",
    "crier_over_fever": "The fever has passed!",
    # a price that moved: crier_dear_<good> and crier_cheap_<good> for each of the market's goods
    "crier_dear_wheat": "Wheat is dear today!",
    "crier_cheap_wheat": "Wheat is cheap today!",
    "crier_dear_bread": "Bread is dear today!",
    "crier_cheap_bread": "Bread is cheap today!",
    "crier_dear_carrot": "Carrots are dear today!",
    "crier_cheap_carrot": "Carrots are cheap today!",
    "crier_dear_potato": "Potatoes are dear today!",
    "crier_cheap_potato": "Potatoes are cheap today!",
    "crier_dear_beetroot": "Beetroot is dear today!",
    "crier_cheap_beetroot": "Beetroot is cheap today!",
    "crier_dear_apple": "Apples are dear today!",
    "crier_cheap_apple": "Apples are cheap today!",
    "crier_dear_cod": "Cod is dear today!",
    "crier_cheap_cod": "Cod is cheap today!",
    "crier_dear_salmon": "Salmon is dear today!",
    "crier_cheap_salmon": "Salmon is cheap today!",
    "crier_dear_beef": "Beef is dear today!",
    "crier_cheap_beef": "Beef is cheap today!",
    "crier_dear_pork": "Pork is dear today!",
    "crier_cheap_pork": "Pork is cheap today!",
    "crier_dear_mutton": "Mutton is dear today!",
    "crier_cheap_mutton": "Mutton is cheap today!",
    "crier_dear_chicken": "Chicken is dear today!",
    "crier_cheap_chicken": "Chicken is cheap today!",
    "crier_dear_honey": "Honey is dear today!",
    "crier_cheap_honey": "Honey is cheap today!",
    "crier_dear_wool": "Wool is dear today!",
    "crier_cheap_wool": "Wool is cheap today!",
    "crier_dear_string": "String and twine are dear today!",
    "crier_cheap_string": "String and twine are cheap today!",
    "crier_dear_leather": "Leather is dear today!",
    "crier_cheap_leather": "Leather is cheap today!",
    "crier_dear_arrow": "Arrows are dear today!",
    "crier_cheap_arrow": "Arrows are cheap today!",
    "crier_dear_feather": "Feathers are dear today!",
    "crier_cheap_feather": "Feathers are cheap today!",
    "crier_dear_flint": "Flint is dear today!",
    "crier_cheap_flint": "Flint is cheap today!",
    "crier_dear_candle": "Candles are dear today!",
    "crier_cheap_candle": "Candles are cheap today!",
    "crier_dear_bottle": "Bottles are dear today!",
    "crier_cheap_bottle": "Bottles are cheap today!",
    "crier_dear_iron": "Iron is dear today!",
    "crier_cheap_iron": "Iron is cheap today!",
    "crier_dear_coal": "Coal is dear today!",
    "crier_cheap_coal": "Coal is cheap today!",
    # why a price moved
    "crier_why_bought_up": "Folk have been buying it up!",
    "crier_why_sold_in": "The stalls are piled high with it!",
    "crier_why_no_flour": "The ovens stood cold for want of flour!",
    "crier_why_no_grain": "The mill stood idle for want of grain!",
    "crier_why_short_handed": "Too few hands to make it!",
    "crier_why_rest_day": "The ovens rest on the seventh day!",
    "crier_why_carts_behind": "The carts are behind with the harvest!",
    "crier_why_worn_tools": "The town's tools are worn out!",
    "crier_why_from_afar": "It must come from afar, by caravan!",
    "crier_why_scarce": "There's little of it about!",
    "crier_why_plenty": "There's plenty of it about!",
    "crier_why_feast": "The whole town is feasting!",
    "crier_why_cold": "Every hearth is burning coal against the cold!",
    "crier_why_warm": "Few fires are lit in the warm days!",
    "crier_why_fields_lean": "Little grows in the fields this time of year!",
    "crier_why_fields_full": "The fields are at their best!",
    "crier_why_orchard_lean": "The orchards won't bear till autumn!",
    "crier_why_orchard_full": "The orchards are heavy with fruit!",
    "crier_why_herds_lean": "Few beasts go to the butcher in the cold!",
    "crier_why_herds_full": "The fattened beasts are going to the butcher!",
    "crier_why_fleece_lean": "The sheep won't be shorn till spring!",
    "crier_why_fleece_full": "It's shearing time! Wool aplenty!",
    "crier_why_coops_lean": "The coops give little in the short days!",
    "crier_why_coops_full": "The coops are doing well in the long days!",
    # the town
    "crier_hungry": "The town went hungry yesterday! Food is short!",
    "crier_quiet": "The market is well stocked, and prices are fair!",
    "crier_board": "The prices are up on the board!",
    # word from the land
    "crier_news_slain": "A great beast has been slain!",
    "crier_news_dragon": "A dragon has been slain!",
    "crier_news_sea": "A terror of the deep has been slain!",
    "crier_news_lair": "A monster's lair has been cleared!",
    "crier_news_rank": "The guild has a new champion!",
    "crier_news_works": "A village has finished its new works!",
    "crier_news_road": "A new road is open to travellers!",
    "crier_news_bandits": "Bandits were driven off!",
    "crier_news_siege_won": "A village has held off a siege!",
    "crier_news_siege_lost": "A village has been overrun by a horde!",
    "crier_news_outlaws": "Outlaws are abroad! Keep to the roads by day!",
    "crier_news_outlaw_slain": "An outlaw captain has been brought down!",
    "crier_news_taken": "Outlaws have carried folk off!",
    "crier_news_rescued": "A captive has been brought home!",
    "crier_news_guild": "A new guild has been founded!",
    "crier_news_banners": "Banners are changing over the villages!",
    "crier_news_king": "The Hollow King is no more!",
    "crier_news_deeds": "Great deeds are afoot in the land!",
    "crier_news_more": "More word from the guild halls!",
    # the year
    "crier_festival_soon": "A festival is coming! Make ready!",
    "crier_festival_today": "The festival is today! Come one, come all!",
    "crier_season_spring": "Spring has come! Time to sow!",
    "crier_season_summer": "Summer is here!",
    "crier_season_autumn": "Autumn is upon us! Bring in the harvest!",
    "crier_season_winter": "Winter has come! Lay in your stores!",
    # the road (M4): a wagon waylaid and the goods it carried (the event's news), the trouble over, the road's stages as
    # it grows worse, the band broken, the relief wagon, and goods brought back from a wreck
    "crier_waylaid": "A wagon was waylaid on the road! Goods will be short!",
    "crier_over_waylaid": "The wagons are getting through again!",
    "crier_road_troubled": "Trouble on the road! Escorts wanted for the wagons!",
    "crier_road_perilous": "The road is perilous! The wagons go together, at noon!",
    "crier_road_closed": "The road is closed! No wagon goes without an escort!",
    "crier_road_clear": "The outlaws are broken! The road is clear again!",
    "crier_relief": "A relief wagon is on its way to us!",
    "crier_salvage_returned": "Goods from the wreck have been brought back! Our thanks!",
    # the closing call
    "crier_close_0": "That is the news! Go about your business!",
    "crier_close_1": "Noon, and all's well!",
    "crier_close_2": "So ends the news! Fair trading to you all!",
    # the talk of the town (townsfolk/talk: the hottest rumour of the day, told straight, its details on the slip)
    "crier_talk_deed": "And the talk of the town: a deed worth the telling!",
    "crier_talk_death": "And the town has lost one of its own.",
    "crier_talk_trouble": "And the talk of the town: trouble close to home!",
    "crier_talk_good": "And the talk of the town: glad tidings!",
    "crier_talk_far": "And the talk of the town: news from afar!",
}

# Spelling the TTS reads better than the written text (the subtitles keep the text as written)
SAY_AS = {
    "Hmph.": "Humph.",  # otherwise spelled out, H-M-P-H
    "Hmm.": "Hmmm.",  # otherwise a weak, swallowed 'h'm'
    "Hm?": "Hmm?",  # otherwise spelled out, H-M
    "Commander": "Commarnder",  # otherwise an American "a"
    # a comma where the line breathes keeps the recogniser (and a listener) on the words
    "The weave rewards it": "The weave, rewards it",  # otherwise "the weaver wads it"
    "lost at the feet": "lost, at the feet",  # otherwise "lost to the feet"
    "Shield up. Always shield up.": "Shield, up. Always, shield up.",  # otherwise "shield app"
    "Where's my purse": "Where is my purse",  # otherwise "was", "with" or "wears" my purse
    "Light keep you, colleague.": "Light, keep you colleague.",  # otherwise "like keep you"
    "knight's dog": "knight's... dog",  # otherwise "night's dog" with the first word lost
}

# Lines no respelling gets right, with their phonemes written out as Kokoro reads them. The American voices turn
# the British long "a" of "stars", "charts", "guards", "Starwatch" and "hearth" into an "o" ("stalls", "star shots",
# "gods", "Hawthorne") unless the r is sounded; "lecterns" came out as "lect turns" (two stresses) and "Hollow" as
# "holler".
PHONEMES = {
    "mage_0": "ðə stˈɑːɹz wɜː klˈiə lˈast nˈaɪt. ɹˈeə, ðiːz dˈeɪz.",
    "mage_1": "mˈaɪnd ðə lˈɛktɚnz.",
    "mage_3": "fˈɔː hˈʌndɹɪd jˈiəz ɒv stˈɑːɹ tʃˈɑːɹts, and nˈəʊ tˈuː nˈaɪts ɐlˈaɪk.",
    "mage_kin_0": "wˈɛl mˈɛt, fɹˈɛnd ɒvðə stˈɑːɹwɒtʃ.",
    "knight_3": "ðə hˈɒloʊ wˈɔːkt ɐɡˈɛn lˈast nˈaɪt.",
    "knight_kin_0": "hˈɑːɹθ ænd ɹˈəʊd, fɹˈɛnd.",
    "alarm_1": "ɡˈɑːɹdz! ɡˈɑːɹdz!",
    "alarm_0": "θˈiːf! stˈɒp, θˈiːf!",  # as the text reads; written out because its LEAD_IN is phonemes
    # the road's: an r sounded where the American voices would drop it ("sorted", "more spears", "here", "their",
    # "escort", "dear", "are", "aren't"), as for the crier
    "driver_mark": "ðɛɹ kˈæmps ˈaʊt ðat wˈeɪ. hˈɪɹ, aɪl ʃˈəʊ juː.",  # "camp's", not "comes"
    "gossip_road_6": "ðeɪ sˈeɪ ðə lˈæst wˈaɡən nˈɛvə kˈeɪm ˈɪn.",  # "last", not "lost"
    "driver_next": "ðə nˈɛkst wˈɒn wəʊnt ɡˌəʊ wɪðˌaʊt mˈɔːɹ spˈɪɹz.",
    "driver_escort_meet": "jɔːɹ ˌaʊəɹ ˈɛskɔːɹt? ðˈɛn lˈɛts biː ˈɒf.",
    "gossip_road_1": "ðə wˈaɡənz ˈɑːɹnt sˈeɪf ɒnðə ɹˈəʊd ðiːz dˈeɪz.",
    "gossip_road_2": "ˈɛvɹɪθˌɪŋ ˈɒf ðə wˈaɡənz ɪz dˈɪɹ nˈaʊ.",
    "gossip_road_8": "aɪ wəʊnt sˈɛt fˈʊt aʊtsˈaɪd ðə wˈɔːlz tˈɪl ɪts sˈɔːɹtɪd.",
    "gossip_road_clear_1": "wˈaɡənz ɑːɹ kˈʌmɪŋ ɪn ɐɡˈɛn. ɐbˌaʊt tˈaɪm.",
    "gossip_road_clear_3": "pɹˈaɪsɪz ɑːɹ kˈʌmɪŋ dˈaʊn, θˈaŋk ɡˈʊdnəs.",
}
# The same for one timbre only. With the plain "f" the woman's and the old man's first "Thief" came out as "Thieve":
# the woman's reads right with an aspirated "f" and a full stop, the old man's with a longer one and a comma.
PHONEMES_FOR = {
    ("alarm_0", "woman"): "θˈiːfʰ. stˈɒp, θˈiːf!",
    ("alarm_0", "old"): "θˈiːff, stˈɒp, θˈiːf!",
    # the road's: "There's too many! Can't" not "This too many caught", "Wagons" not "Woggins"
    ("driver_hold", "old"): "wˈoʊ, ɡˈɜːl! wˈoʊ!",  # "Whoa", not "Well"
    ("driver_trees", "man"): "lˈʊk ˈaʊt! ˈaʊt lˌɔːz!",  # "Outlaws", not "outliers"
    ("guard_cant_hold", "woman"): "ðɛɹz tˈuː mˈɛni! kˈænt hˈəʊld ðˌɛm!",
    ("gossip_road_clear_1", "woman"): "wˈæɡənz ɑːɹ kˈʌmɪŋ ɪn ɐɡˈɛn. ɐbˌaʊt tˈaɪm.",
}

# Kokoro lets the first word of a take go: the "th" of a "Thief" that opens an utterance is almost silent ("Leave!
# Stop, leave!"), though the same word in the middle of a sentence is fine. For such a line a throw-away "oh" is read
# in front of it (as phonemes if the line is, else as text) and cut off again in the `tts` stage (cut_lead_in), where
# its vowel ends, so the raw take starts with the "th".
LEAD_IN = {"alarm_0": "ˈəʊ! "}

# Level offsets (dB) from the common speech level: cries carry, mutters don't
LEVEL = {
    "flee_0": 2.0, "flee_1": 2.0, "flee_2": 2.0, "baker_0": 1.0, "baker_1": 1.0, "merchant_0": 1.0,
    "merchant_1": 1.0, "merchant_2": 1.0, "merchant_3": 1.0, "merchant_4": 1.0, "merchant_5": 1.0,
    "reply_1": -3.0, "fisher_1": -2.5, "night_1": -1.0,
    # a heist's watchmen: shouts must carry, a victim cries out, the wary and the muttering are quieter
    "alarm_0": 3.0, "alarm_1": 3.0, "alarm_2": 3.0, "purse_0": 2.0, "purse_1": 2.0,
    "suspect_0": -1.0, "suspect_1": -1.0, "suspect_2": -1.0, "calm_0": -2.0, "calm_1": -2.0, "thief_2": -3.0,
    # the road: a driver struck and guards in a fight shout, a driver uneasy or ashamed says it low
    "driver_hold": 3.0, "driver_trees": 3.0, "guard_to_wagon": 3.0, "guard_together": 3.0, "guard_beaten_off": 2.0,
    "guard_cant_hold": 3.0, "driver_quiet": -2.0, "driver_ran": -1.5,
}

# ------------------------------------------------------------------ how the crier cries
# The crier speaks in the timbre's everyday voice (the same Kokoro voice and British phonemes), so a crier who greets
# you and then cries the news is one person, projecting: read slower and more deliberately than everyday speech (the
# man reads his everyday lines at 1.0, the woman hers at 1.12), each call's last stressed vowel drawn out a little and a
# breath left between one call and the next, then less chest and more presence, gently compressed and set in the
# square, with a slap off its stone fronts. The server gives each line 100 ticks, so none may run over CRIER_MAX_LEN;
# one that would is read a little quicker (CRIER_SPEED_FOR) rather than cut.
CRIER_SPEED = {"man": 0.88, "woman": 0.97}
CRIER_SPEED_FOR = {  # (line, timbre): a speed of its own
    ("crier_festival_soon", "woman"): 1.03,  # "Make ready!", not "make granny"
    ("crier_blight_carrot", "woman"): 1.03,  # "fields", not "field"
}
CRIER_DRAW = (1.2, 1.32)  # how far a call's last stressed vowel is drawn out: the earlier calls', the last call's
CRIER_GAP = 0.36  # seconds between one call and the next, at least
CRIER_HP = {"man": 95, "woman": 115}  # less chest than the everyday lines (75 and 90 Hz)
CRIER_LUFS = -19.0  # every crier line's integrated loudness, about 2.3 LU over the everyday lines' -21.3 on average
CRIER_REACH = 48  # blocks: the whole square hears the crier (the lines' attenuation_distance)
CRIER_MAX_LEN = 4.5
# The square's stone fronts: each reflection's delay (s), its level (dB under the voice) and the low-pass (Hz) that
# dulls it, the far walls' last
CRIER_SLAP = [(0.034, -13.0, 5500), (0.057, -15.5, 4800), (0.089, -17.5, 4200), (0.141, -18.5, 3600),
              (0.212, -22.0, 3000), (0.283, -25.0, 2600)]

# The crier's lines are read from phonemes: espeak's British reading of the line (after SAY_AS) with these put right,
# in this order. The American voices drop or bend a British vowel that has no r after it, so "dear", "are", "short",
# "board", "more", "carts", "harvest", "pork", "afar", "stores", "bumper", "banners", "feathers" and "iron" sound their
# r. Both voices also say a short vowel a step lower than written ("Bread" came out as "Brad"), and a few words needed
# a vowel, a stress or a space of their own. Each comment says what Whisper heard without the fix.
CRIER_PHONEMES = [
    ("ˈɔɪɛz", "oʊjˈɛz"),  # "Oyez" as oh-YEZ (heard as "oh yes", as criers say it), not OY-ez ("OES")
    ("mˈʌɹeɪn", "mˈʌɹɪn"),  # "Murrain" as MURR-in, not mur-RAIN ("Maureen")
    ("dˈiə", "dˈɪɹ"),  # "dear", not "dee-ah" ("DIA") or "do here"
    ("ɑː ", "ɑːɹ "),  # "are", lost before "dear" ("carrots a deer")
    ("ʃˈɔːt", "ʃˈɔːɹt"),  # "short", not "shot"
    ("bˈɔːd", "bˈɔːɹd"),  # "board", not "baud"
    ("nˈəʊmˌɔː", "nˈoʊ mˈɔːɹ"),  # "no more", not "no maw"
    ("mˈɔː wˈɜːd", "mˈɔːɹ wˈɜːd"),  # "More word"
    ("kˈɑːts", "kˈɑːɹts"),  # "carts", not "cots"
    ("hˈɑːvɪst", "hˈɑːɹvɪst"),  # "harvest"
    ("bˈəʊts", "bˈoʊts"),  # "boats", not "bolts"
    ("bˈiːtɹuːt", "bˈiːt ɹˌuːt"),  # "beetroot", not "be true, it" or "beatrot"
    ("hˈɒləʊ", "hˈɒloʊ"),  # "Hollow", not "holler" (as knight_3)
    ("kˈɒd", "kˈɑːd"),  # "Cod", not "caught"
    ("pˈɔːk", "pˈɔːɹk"),  # "Pork", not "park" or "pock"
    ("ɐfˈɑː", "ɐfˈɑːɹ"),  # "afar", not "a fall"
    ("stˈɔːz", "stˈɔːɹz"),  # "stores", not "stars"
    ("ˈɔːlz wˈɛl", "ˈɔlz wˈɛl"),  # "all's well", not "all as well"
    ("hɐz kɹˈakt", "hˈaz kɹˈakt"),  # "has cracked", not "is cracked"
    ("bɹˈɪŋ ɪnðə", "bɹˈɪŋ ˈɪn ðə"),  # "Bring in the harvest", not "bringing the harvest"
    ("lˈeɪ ɪn", "lˈeɪ ˈɪn"),  # "Lay in", not "late in"
    ("wˈɪndmɪl", "wˈɪndmˌɪl"),  # "windmill", not "windmull"
    ("wˈiːt ɪz", "wˈiːt ˈɪz"),  # "Wheat is", not "weed is" (no flapped t before a stressed vowel)
    ("wˈʊl ɪz", "wˈuːl ɪz"),  # "Wool", not "well" or "wall"
    ("ˈaɪən ɪz", "ˈaɪɚn ɪz"),  # "Iron", not "I am"
    ("hɐz hˈɛld", "hˌaz hˈɛld"),  # "has held", not "is held"
    ("fˈiːvə hɐz", "fˈiːvə hˌaz"),  # "The fever has passed", not "is passed"
    ("lˈɪtəl ɒv ɪt", "lˈɪtəl ʌv ɪt"),  # "little of it", not "a little bit"
    ("ˈʌvən ɪz mˈɛndɪd", "ˈʌvən ˈɪz mˈɛndɪd"),  # "oven is mended", not "of an ismended"
    ("tə sˈəʊ", "tə sˈoʊ"),  # "sow", not "sell"
    ("bˈʌmpə kɹˈɒp", "bˈʌmpəɹ kɹˈɒp"),  # "bumper", not "bumpa"
    ("bˈanəz", "bˈænɚz"),  # "Banners", not "Banas" or "Bonners"
    ("fˈɛðəz ɑːɹ", "fˈeðəɹz ɑːɹ"),  # "Feathers", not "Fathers"
    ("mˈʌtən ɪz", "mˈətən ɪz"),  # "Mutton", not "Martin"
    ("kˈəʊl ɪz", "kˈoʊəl ɪz"),  # "Coal", not "Call"
    ("bɹˈɛd ɪz", "bɹˈɪd ɪz"),  # "Bread", not "Brad": both voices say a short vowel a step lower than written
    ("wˈɒnt ɒv", "wˈɑnt ʌv"),  # "want of", not "one of"
    ("wˈɑnt ʌv ɡɹˈeɪn", "wˈɑntʰ ʌv ɡɹˈeɪn"),  # the man's "want of grain", with its t
    ("pˈɛst hɐz ɡɒt", "pˈɛst hˌaz ɡˈɒt"),  # "has got among the hens", not "...the hands"
    ("ɐmˌʌŋ ðə hˈɛnz", "ɐmˌʌŋ ðə hˈɪnz"),  # "hens", not "hands" (as "Bread")
    ("tʃˈɪkɪn ɪz dˈɪɹ", "tʃˈɪkɪn ˈɪz dˈɪɹ"),  # "Chicken is dear", not "Chuckin is dear"
    ("wˈiːt ˈɪz dˈɪɹ", "wˈiːtʰ ˈɪz dˈɪɹ"),  # the man's "Wheat is dear", not "We dis deer"
    ("wˈiːt ˈɪz tʃˈiːp", "wˈiːtʰ ˈɪz tʃˈiːp"),  # and "Wheat is cheap", not "Weed is cheap"
    ("nˈuːn, and ˈɔlz", "nˈuːn! and ˈɔlz"),  # "Noon! and all's well", called as the hour, not "Noonan as well"
    # the road's (M4): "escort" with its r, "wanted" not "wonted", "clear" as "dear" is, "waylaid" not "whaling" and
    # "Our thanks" not "are thanks"
    ("ˈɛskɔːts", "ˈɛskɔːɹts"),
    ("ˈɛskɔːt", "ˈɛskɔːɹt"),
    ("wˈɒntɪd", "wˈɑntɪd"),
    ("ˌaʊə θˈaŋks", "ˈaʊwɚ θˈaŋks"),
    ("klˈiəɹ", "klˈɪɹ"),
    ("wˈeɪleɪd", "wˈeɪ lˌeɪd"),
]
# A line's own fixes in one timbre, after CRIER_PHONEMES: (line, timbre) -> [(espeak's, Kokoro's)]
CRIER_PHONEMES_FOR = {
    ("crier_season_autumn", "man"): [("bɹˈɪŋ ˈɪn ðə", "bɹˈɪŋ, ˈɪn ðə")],  # "Bring in", not "bringing"
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
            d = dict(COMMON, **(dict(TRADES, **LAMPLIGHTER) if timbre in ("man", "woman") else {}))
            d.update(FACTION)
            d.update(ROAD)
            if timbre in ("man", "woman"):
                d.update(ROAD_GUARD)
            if timbre in CRIER_TIMBRES:
                d.update(CRIER)
        out[timbre] = {k: v for k, v in d.items() if not keys or re.search(keys, k)}
    return out


def spoken(text):
    for a, b in SAY_AS.items():
        text = text.replace(a, b)
    return text


def say(key, text, timbre=None, k=None):
    """what Kokoro is given for a line: its written-out phonemes (the timbre's own, else the line's) if it has any,
    a crier line's phonemes (read with the Kokoro k: crier_phonemes), else its text after SAY_AS, with the line's
    lead-in in front -> (text or phonemes, is_phonemes)"""
    ph = PHONEMES_FOR.get((key, timbre)) or PHONEMES.get(key)
    if not ph and key in CRIER and k is not None:
        ph = crier_phonemes(k, key, timbre)
    said, is_ph = (ph, True) if ph else (spoken(text), False)
    return LEAD_IN.get(key, "") + said, is_ph


def crier_phonemes(k, key, timbre):
    """a crier line's phonemes: espeak's reading of its text (after SAY_AS), with CRIER_PHONEMES put right, then the
    line's own fixes in this timbre (CRIER_PHONEMES_FOR)"""
    ph = k.tokenizer.phonemize(spoken(CRIER[key]), TIMBRES[timbre]["lang"])
    for a, b in CRIER_PHONEMES + CRIER_PHONEMES_FOR.get((key, timbre), []):
        ph = ph.replace(a, b)
    return ph


def speed_of(key, timbre):
    """how quickly Kokoro reads a line: the timbre's speed, or a crier line's (CRIER_SPEED, CRIER_SPEED_FOR)"""
    if key in CRIER:
        return CRIER_SPEED_FOR.get((key, timbre), CRIER_SPEED[timbre])
    return TIMBRES[timbre]["speed"]


def cut_lead_in(x, sr, frame=0.005, floor_db=-22.0, min_run=0.04, fade=0.008):
    """cuts a lead-in (the first voiced stretch of the take: its low band, 80 to 800 Hz, within floor_db of its
    loudest) off the front, with a short fade in so that nothing clicks"""
    x = np.asarray(x, dtype=np.float64)
    f = np.fft.rfftfreq(len(x), 1.0 / sr)
    band = np.exp(-(np.maximum(0.0, 80.0 - f) / 40.0) ** 2) * np.exp(-(np.maximum(0.0, f - 800.0) / 200.0) ** 2)
    low = np.fft.irfft(np.fft.rfft(x) * band, len(x))
    n = int(frame * sr)
    rms = np.sqrt(np.mean(low[:len(low) // n * n].reshape(-1, n) ** 2, axis=1))
    voiced = 20 * np.log10(rms / (rms.max() + 1e-12) + 1e-9) > floor_db
    i = int(np.argmax(voiced))
    while True:
        j = i
        while j < len(voiced) and voiced[j]:
            j += 1
        if (j - i) * frame >= min_run or not voiced[j:].any():
            break
        i = j + int(np.argmax(voiced[j:]))  # a click, not the lead-in's vowel: on to the next stretch
    y = x[j * n:].copy()
    nf = min(len(y), int(fade * sr))
    y[:nf] *= 0.5 - 0.5 * np.cos(np.pi * np.arange(nf) / max(1, nf))
    return y


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
            said, is_ph = say(key, text, timbre, k)
            audio, sr = k.create(said, voice=t["voice"], speed=speed_of(key, timbre), lang=t["lang"],
                                 is_phonemes=is_ph)
            if key in LEAD_IN:
                audio = cut_lead_in(audio, sr)
            fn = os.path.join(folder, key + ".wav")
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
            print(fn, f"{len(audio) / sr:.2f}s", flush=True)


def cmd_phonemes(a):
    k = kokoro(a)
    for timbre, keys in lines(a.only, a.keys).items():
        for key, text in keys.items():
            said, is_ph = say(key, text, timbre, k)
            ph = said if is_ph else k.tokenizer.phonemize(said, TIMBRES[timbre]["lang"])
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


# ------------------------------------------------------------------ the crier's delivery
def city_voices():
    """the city's criers (ambience/voices.py), for their tools: a take's level, the last stressed vowel of a phrase
    and the stretch (waveform-similarity overlap-add) that draws a vowel out without changing its pitch"""
    path = os.path.join(HERE, "ambience")
    if path not in sys.path:
        sys.path.insert(0, path)
    import voices
    return voices


def calls_of(text):
    """a crier line's calls: its sentences, split after each !, ? or . that has more words after it"""
    return [c for c in re.split(r"(?<=[!?.])\s+", text.strip()) if c]


def call_breaks(y, text, cv, floor_db=-26.0, reach=0.12, min_run=0.02):
    """(start, end) samples of where the take falls quiet between each call and the next: the longest stretch
    floor_db under its loudest moment (min_run long at least) near where the text's letters put the break, the nearer
    of two alike. None where Kokoro ran two calls together: the take is never cut through a word"""
    from sfxkit import ns
    calls = calls_of(text)
    letters = [len(re.sub(r"[^a-z]", "", c.lower())) for c in calls]
    env = cv.envelope_db(y, win=0.012)
    out = []
    for i in range(1, len(calls)):
        t = int(sum(letters[:i]) / sum(letters) * len(y))
        r = max(ns(0.15), int(reach * len(y)))
        a, b = max(ns(0.1), t - r), min(len(y) - ns(0.1), t + r)
        e = np.diff(np.concatenate([[0], (env[a:b] < floor_db).astype(np.int8), [0]]))
        runs = [(a + s, a + q) for s, q in zip(np.nonzero(e == 1)[0], np.nonzero(e == -1)[0]) if q - s >= ns(min_run)]
        out.append(max(runs, key=lambda rq: rq[1] - rq[0] - 0.2 * abs((rq[0] + rq[1]) / 2 - t)) if runs else None)
    return out


def as_calls(y, text):
    """a crier line's take (at sfxkit's SR) as calls: split where it falls quiet between its exclamations, each
    call's last stressed vowel drawn out (CRIER_DRAW) and at least CRIER_GAP seconds between one call and the next"""
    from sfxkit import ns, fade
    cv = city_voices()
    breaks = [b for b in call_breaks(y, text, cv) if b]
    parts = np.split(y, [(a + b) // 2 for a, b in breaks])
    out = []
    for i, p in enumerate(parts):
        last = i == len(parts) - 1
        v = cv.final_vowel(p)
        if v:
            draw = CRIER_DRAW[1] if last else CRIER_DRAW[0]
            p = cv.wsola(p, 1.0 + (draw - 1.0) * cv.ramp_window(len(p), v[0], v[1]))
        out.append(fade(p, 0.006 if i else 0.0, 0.0 if last else 0.012))
        if not last:
            a, b = breaks[i]
            out.append(np.zeros(max(0, ns(CRIER_GAP) - (b - a))))
    return np.concatenate(out)


def square_slap(y, rng):
    """the square's stone fronts (CRIER_SLAP): each reflection smeared over a few milliseconds, duller than the
    last"""
    from scipy import signal
    from sfxkit import hp, lp, ns, db
    out = np.zeros(len(y) + ns(0.35))
    src = hp(y, 180)
    for delay, level_db, cutoff in CRIER_SLAP:
        smear = rng.standard_normal(ns(0.004)) * np.hanning(ns(0.004))
        r = signal.fftconvolve(lp(src, cutoff), smear / (np.sqrt(np.sum(smear ** 2)) + 1e-12))[:len(src)]
        i = ns(delay)
        out[i:i + len(r)] += db(level_db) * r[:len(out) - i]
    return out


def process_crier(x, sr_in, timbre, key):
    """a crier line: the take as calls (as_calls), projected (less chest, a lift in the presence band, gently
    compressed) and set in the square (the slap off its stone fronts and a short open tail ducked under the words),
    at CRIER_LUFS with the peaks under -1.5 dBFS"""
    from sfxkit import SR, hp, lp, eq, compress, room_ir, limiter, fade, db, ns, rng_for
    from scipy import signal
    from scipy.ndimage import uniform_filter1d
    from make_townsfolk_sfx import integrated_loudness
    # Trim the TTS silence (keep a breath at each end) and notch out the vocoder's whistles, as for every line
    a = np.abs(x)
    on = np.nonzero(a > a.max() * 10 ** (-40 / 20))[0]
    x = x[max(0, on[0] - int(0.02 * sr_in)): on[-1] + int(0.06 * sr_in)]
    for f0 in (4800.0, 9600.0):
        if f0 < 0.45 * sr_in:
            x = signal.filtfilt(*signal.iirnotch(f0, 30.0, fs=sr_in), x)
    x = 0.7 * x / (np.max(np.abs(x)) + 1e-12)
    y = as_calls(sox(x, sr_in, out_sr=SR), CRIER[key])
    # Projected: less chest and mud, a presence lift and a little air, then a gentle compressor
    y = hp(y, CRIER_HP[timbre], order=2)
    y = eq(y, "peak", 300, 1.0, -2.0)
    y = eq(y, "peak", 2600, 0.8, 3.5)
    y = eq(y, "highshelf", 7500, 0.7, 1.5)
    y /= np.max(np.abs(y)) + 1e-12
    y = compress(y, thresh_db=-24, ratio=2.5, attack=0.006, release=0.14)
    # The square: its walls' slap and a short open tail, the tail ducked under the words
    slap = square_slap(y, rng_for("townsfolk", "crier", timbre, key))
    ir = room_ir(rt60=0.9, predelay=0.03, early=4, early_span=0.06, hf_damp=0.6, seed=31, density_ms=40)
    tail = signal.fftconvolve(hp(lp(y, 6000), 220), ir)
    n = len(y) + ns(0.5)
    out = fit_len(y, n)
    env = uniform_filter1d(np.abs(out), size=ns(0.05))
    env /= env.max() + 1e-12
    duck = uniform_filter1d(1.0 - 0.45 * np.clip(env * 2.5, 0, 1), size=ns(0.08))
    out = out + fit_len(slap, n) + 0.05 * fit_len(tail, n) * duck
    out = hp(out, 60, order=2)
    for _ in range(3):  # every crier line at the same loudness
        out *= db(CRIER_LUFS - integrated_loudness(out))
        out = limiter(out, ceiling=db(-1.5), lookahead=0.003, release=0.08)
    out = fade(out, fin=0.004, fout=0.35)
    e = uniform_filter1d(np.abs(out), size=ns(0.02))
    alive = np.nonzero(e > np.max(e) * db(-55))[0]
    return fade(out[:alive[-1] + 1], fout=0.05)


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
            if key in CRIER:
                y = process_crier(x, sr, timbre, key)
            else:
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
            long = key in CRIER and len(y) / OUT_SR > CRIER_MAX_LEN
            print(f"{out}  {len(y) / OUT_SR:.2f}s" + ("  OVER CRIER_MAX_LEN: quicken it in CRIER_SPEED_FOR"
                                                       if long else ""), flush=True)


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
            entry = f"guildmark:{name}"
            if key in CRIER:  # the whole square hears the crier
                entry = {"name": entry, "attenuation_distance": CRIER_REACH}
            sounds[f"townsfolk.voice.{timbre}.{key}"] = {"sounds": [entry], "subtitle": f"say.guildmark.{key}"}
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
