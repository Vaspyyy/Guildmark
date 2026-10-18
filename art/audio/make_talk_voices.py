"""The townsfolk's talk (living-world/design-voices.md section 5): what a capital's people say on the talk screen, in
passing and to each other, as families of wordings that fit what's really going on, through the townsfolk voice
pipeline (the same Kokoro voices, timbres, STYLE, process_line and levels as make_townsfolk_voices.py). Also the takes
of the keepers' stall lines that name nothing (economy/StallTalk.kt's say.guildmark.market.*, read from the language
file as written). Run from the repo root:

  tts       <venv>/bin/python -I -B art/audio/make_talk_voices.py tts --model kokoro-v1.0.onnx \\
                --voices voices-v1.0.bin --out <raw-dir> [--timbres man,old] [--keys greet] [--force] [--espeak-data <dir>]
  process   python3 -B art/audio/make_talk_voices.py process --raw <raw-dir> \\
                --assets src/main/resources/assets/guildmark [--timbres ...] [--keys ...] [--wav <dir>]
  phonemes  <venv>/bin/python -I -B art/audio/make_talk_voices.py phonemes --model ... --voices ... [--keys ...]
  sounds    python3 -B art/audio/make_talk_voices.py sounds    (prints the sounds.json entries)
  table     python3 -B art/audio/make_talk_voices.py table [--assets src/main/resources/assets/guildmark]

A family ("greet.morning") is one meaning said two to four ways, so that a player seldom hears the same wording twice:
its takes are talk.<family>_0, _1, ..., each a whole sentence that's true whenever the family is picked. Whatever
changes (a name, a good, a price, a place, a time) is never voiced: it follows the take as text, unvoiced (the
thread's and the sources' details, say.guildmark.talk.detail.*). Where a name belongs, a family has .named texts
(say.guildmark.talk.<family>_<n>.named): the take says "There you are!", the screen and the name tag "There you are,
Ransom!". Each take plays as townsfolk.voice.<timbre>.<key>, subtitled with its own text (say.guildmark.<key>).

Timbres: the man and the woman say everything but the children's lines. The old man has the lines old folk say most
(greetings and farewells, the screen's own, the daily round, market talk, the town, the weather, the lead-ins, the
replies and the household, and three of his own) and borrows the man's voice lower for the rest; a child has their
own 18 and borrows the woman's higher for anything else (TownsfolkClient.speak).

Reading: the voices are American, given espeak's British phonemes as in make_townsfolk_voices.py, but each line is
read with the r's of espeak's American reading sounded (`rhotic`), which the crier's and the festival's lines did a
word at a time; TALK_PHONEMES and PHONEMES put right what Whisper still heard wrong after that.

Lengths: a take (as processed, its breath and the street's tail included) may last 2.5 s for a greeting, a farewell,
the screen's own lines and the replies, 4.0 s for other talk, 1.8 s for a news lead-in and 3.0 s for the news after
it. `process` refuses a take over its limit (it writes nothing for it and says by how much). The keepers' stall lines
are held to 4.0 s.

`table` writes art/audio/talk_sounds.json and talk_lang.json (the sounds.json and en_us.json entries: every take, its
text and .named text, and the five talk-of-the-town lines the crier cries, which make_townsfolk_voices.py records in
CRIER) and generates src/main/kotlin/io/github/vaspyyy/guildmark/townsfolk/talk/TalkLines.kt from the table and the
takes on disk: each family, its number of wordings, each wording's length in ticks (the longest timbre's), whether it
has .named texts and which timbres say it; and the length of every other voiced townsfolk line in sounds.json (the
old lines, the keepers', the festival's, the crier's), so that TalkLines.ticks knows any line a talk can say.
"""
import argparse
import difflib
import json
import math
import os
import re
import struct
import sys
import types

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "art", "audio"))
import make_townsfolk_voices as mtv  # noqa: E402

ASSETS = os.path.join(REPO, "src", "main", "resources", "assets", "guildmark")
LANG = os.path.join(ASSETS, "lang", "en_us.json")
KOTLIN = os.path.join(REPO, "src", "main", "kotlin", "io", "github", "vaspyyy", "guildmark", "townsfolk", "talk",
                      "TalkLines.kt")
HERE = os.path.dirname(os.path.abspath(__file__))

MWO = ("man", "woman", "old")
MW = ("man", "woman")
OLD = ("old",)
CHILD = ("child",)
TIMBRES = ("man", "woman", "old", "child")

# The length limits, in seconds of the processed take
GREETING, TALK, LEAD_IN, NEWS = 2.5, 4.0, 1.8, 3.0

# ------------------------------------------------------------------ the table
# Each group: its timbres, its length limit and its families, {family: [wording, ...]}, a wording's number being its
# place in the list. Braces hold what only the .named text says: "There you are{, %s}!" is voiced "There you are!" and
# shown "There you are, Ransom!". %s is the player's name; name.pleased's .named texts take two, the speaker's own given
# name (%1$s) and then the player's (%2$s). A family either has .named texts for all its wordings or for none. The
# comment over each family says when it's said: every wording must be true whenever that holds.
GROUPS = [
    ("Greetings, farewells and introductions", MWO, GREETING, {
        # strangers and the met, by the hour: from 05:00 to 12:00 (greet_1 "Morning!" is said then too)
        "greet.morning": [
            "Good morning, and well met.",
            "Morning. Up and about, I see.",
            "Good morning. What brings you by?",
        ],
        # from 12:00 to 17:00
        "greet.afternoon": [
            "Good afternoon to you.",
            "Afternoon. Keeping well?",
        ],
        # from 17:00 to 21:00 (night_0 to night_2 greet the night)
        "greet.evening": [
            "Good evening to you.",
            "Evening. Another day nearly done.",
        ],
        # someone they've met, and seen earlier the same day
        "greet.again": [
            "Back again so soon{, %s}?",
            "You again{, %s}! Forgotten something?",
            "Well{, %s}, we meet again.",
        ],
        # someone they've met, not seen for seven days or more
        "greet.long": [
            "It's been a while{, %s}. Thought we'd lost you.",
            "Look who it is! Where've you been{, %s}?",
        ],
        # a friend
        "greet.friend": [
            "There you are{, %s}!",
            "I was hoping you'd come by{, %s}.",
            "Good to see you again{, %s}.",
            "Well{, %s}, how are you keeping?",
        ],
        # someone they've never met but have heard of (a rumour about them, or their standing at the board)
        "greet.heard": [
            "So you're the one they're all talking about.",
            "I've heard about you, you know.",
            "Word of you has got round.",
        ],
        # the same, at Hero standing
        "greet.hero": [
            "It's an honour. Truly.",
            "Is it really you? Well, I never!",
        ],
        # the wary: short, arms folded
        "greet.wary": [
            "What is it now?",
            "Make it quick.",
            "Oh. It's you.",
        ],
        # the shy, to a stranger
        "greet.shy": [
            "Oh! Hello.",
            "Hello... can I help you?",
        ],
        # Goodbye by day, to anyone not a friend or wary (greet_4 and greet_5 are farewells too)
        "bye.plain": [
            "Well, I'll not keep you.",
            "Take care of yourself.",
        ],
        # Goodbye from 21:00 to 05:00
        "bye.night": [
            "Good night to you.",
            "Get some sleep.",
        ],
        # Goodbye, to a friend
        "bye.friend": [
            "Don't be a stranger{, %s}.",
            "Come by again soon{, %s}, won't you?",
            "Safe home{, %s}.",
        ],
        # Goodbye, from the wary
        "bye.wary": [
            "Off you go, then.",
            "Right. We're done here.",
        ],
        # they end the talk themselves: their day needs them (work, the watch, the cry, lessons or the service is
        # due), or it has gone on two minutes
        "bye.off": [
            "I must be off, I'm afraid.",
            "Things to see to. Another time!",
            "Look at the hour! I'd best be going.",
        ],
        # called after a player who walked off mid-talk without a goodbye
        "bye.walked": [
            "Well! Goodbye to you too.",
            "Not so much as a goodbye.",
        ],
        # the player has stood there thirty seconds and said nothing
        "bye.idle": [
            "Well, I'll be getting on.",
            "I'll leave you to your thinking.",
        ],
        # the answer to "I'm Ransom.": %1$s is the speaker's own given name, %2$s the player's
        "name.pleased": [
            "{%1$s. }Pleased to meet you{, %2$s}.",
            "{I'm %1$s. }Glad to know you{, %2$s}.",
            "{%1$s. }A pleasure{, %2$s}. I'll remember the name.",
        ],
    }),
    ("The screen's own", MWO, GREETING, {
        # after the greeting, when they've nothing to go straight on with: the choices follow
        "ask.yes": [
            "What can I do for you?",
            "Yes? What is it?",
            "Was there something you wanted?",
        ],
        # a word over the shoulder from anyone who can't stop: due at work or on the way to it, a carter at the
        # shafts, someone carrying a load, a bard mid-song, the crier mid-cry
        "ask.busy": [
            "Can't stop!",
            "Sorry, no time just now!",
            "Not now, friend. Later!",
        ],
        # a fifth player, while four are talking with them already
        "ask.queue": [
            "One at a time, one at a time!",
        ],
        # someone woken in bed: a mumble on the name tag, and no screen
        "ask.asleep": [
            "Mm... not now...",
            "Five more minutes...",
        ],
        # someone at the temple's service
        "ask.hush": [
            "Hush. After the service.",
            "Quiet, now. We're at prayer.",
        ],
        # before an answer to "Anything else?"
        "ask.think": [
            "Now, what was it...",
            "Let me think...",
        ],
        # "Any news?", and they have none at all
        "ask.dunno": [
            "I've not heard a thing.",
            "Nothing that I know of.",
        ],
        # "Anything else?", and they've told all they'll tell
        "ask.all": [
            "That's all I know.",
            "That's the lot, I'm afraid.",
            "I've told you all I have.",
        ],
        # (the news source's) a rumour the player has heard, of which this teller knows another version: it follows
        "ask.other": [
            "Ah, but I heard different.",
            "That's not how I heard it.",
        ],
        # answering "Trade" as the market screen opens
        "ask.trade": [
            "Have a look, then.",
            "Let's see what you're after.",
        ],
        # the hostile: one cold line, and only Goodbye
        "ask.cold": [
            "I've nothing to say to you.",
            "Leave me be.",
        ],
    }),
    ("The daily round", MWO, TALK, {
        # "What are you up to?" (or in passing) during that stretch of their day. What comes next, and when, is the
        # thread's detail after it ("Then the tavern, at 18:30."), so none of these says it.
        # just up: a wash, a stretch
        "doing.rise": [
            "Only just up. Still rubbing the sleep from my eyes.",
            "Waking up, slowly. Don't rush me.",
        ],
        # breakfast at home
        "doing.breakfast": [
            "Breakfast first. Nothing gets done on an empty stomach.",
        ],
        # off for a bite at midday
        "doing.lunch": [
            "Stopping for a bite. A body has to eat.",
            "Midday, and my stomach knows it.",
        ],
        # off to the stalls for the house
        "doing.market": [
            "Supper won't buy itself.",
            "Off to the stalls, before the best of it's gone.",
            "A bit of shopping. The larder's looking bare.",
        ],
        # fetching water from the well
        "doing.water": [
            "Fetching water. The house never has enough.",
            "To the well and back. My arms will feel it.",
        ],
        # taking something across town to someone's door
        "doing.errand": [
            "Taking something across town. Someone's waiting on it.",
            "Running an errand. Never a moment's peace.",
        ],
        # calling on a neighbour
        "doing.visit": [
            "Calling on a neighbour. There'll be something on the fire.",
            "Paying a neighbour a visit. We'll put the world to rights.",
        ],
        # off to the temple to pray
        "doing.worship": [
            "Off to the temple, for a prayer or two.",
            "Going to say my prayers. It keeps me right.",
        ],
        # a turn about the town, for the pleasure of it
        "doing.stroll": [
            "Just taking the air.",
            "A turn about the town. My legs need it.",
        ],
        # off to the tavern of an evening, or there
        "doing.tavern": [
            "One drink. Two at the most.",
            "The tavern, for good ale and better company.",
            "A mug of ale and a bit of a sit-down.",
        ],
        # supper at home
        "doing.supper": [
            "Supper. I'll not be kept from it.",
            "Home for supper. About time, too.",
        ],
        # an evening at home by the fire
        "doing.evening": [
            "A quiet evening by the fire. That's all I ask.",
        ],
        # off home to bed, late
        "doing.bed": [
            "Off to bed, before I drop where I stand.",
            "It's late. My bed's calling me.",
        ],
        # any free stretch of the seventh day, the rest day, when nobody works
        "doing.rest": [
            "It's the rest day. I'm doing nothing at all, and gladly.",
            "No work today. The seventh day is for resting.",
            "A day off. My hands don't know what to do.",
        ],
    }),
    ("Trades", MW, TALK, {
        # at work, or asked then. The facts of the moment are the thread's detail after it (the cold ovens get "No
        # flour in the bins." and the bread's price; a catch gets how many are in the basket).
        # at the anvil, the forge busy this hour
        "doing.smith": [
            "Hammer and anvil, all day long. I don't hear half of what's said.",
            "There's iron in the fire, so I can't stop long.",
            "Hinges, nails, hoes. There's always something wants making.",
        ],
        # at the smithy, the forge standing idle this hour (no iron, or no call for it)
        "doing.smith_idle": [
            "The forge is idle. Nothing to put on the anvil.",
        ],
        # baking, the ovens going
        "doing.baker": [
            "Up since before dawn, and the ovens still hot.",
            "Kneading and baking. The town eats more than you'd think.",
            "Another batch in the oven. The smell is the best part.",
        ],
        # the ovens standing cold for want of flour
        "doing.baker_cold": [
            "Waiting on flour. Again.",
            "Cold ovens, and folk wanting bread. It's no good.",
        ],
        # keeping their stall
        "doing.merchant": [
            "Minding the stall. Somebody has to.",
            "Selling what I can, while there's folk to buy it.",
            "The stall keeps me on my feet from morning till night.",
        ],
        # on the day watch
        "doing.guard": [
            "On watch. Keeping the peace, mostly.",
            "Standing my watch. The day goes slow on duty.",
        ],
        # on the night watch
        "doing.guard_night": [
            "Night watch. Long and quiet, if we're lucky.",
            "Keeping watch while the town sleeps.",
        ],
        # a farmer in spring, sowing
        "doing.farmer_sow": [
            "Sowing. What goes in now feeds us all come autumn.",
            "Sowing, row after row. My back's had enough already.",
        ],
        # a farmer in summer or winter: weeding, tending, mending
        "doing.farmer_tend": [
            "Tending the fields. There's always something wants doing.",
            "Mending and minding. A farm's never finished.",
        ],
        # a farmer in autumn: the harvest
        "doing.farmer_reap": [
            "Harvest. Every pair of hands is wanted.",
            "Bringing it in before the weather turns.",
        ],
        # at the water, nothing said of the catch
        "doing.fisher": [
            "Line in the water, and patience. That's fishing.",
            "A quiet spot, a good line. Can't ask for more.",
        ],
        # with fish in the basket already (fisher_0 "They're biting today!" is said then too)
        "doing.fisher_catch": [
            "Not a bad catch so far.",
            "The water's been kind. I've got a few.",
        ],
        # at it a while, and the basket still empty (fisher_1 "Nothing yet..." too)
        "doing.fisher_none": [
            "Not so much as a nibble.",
            "The fish are keeping to themselves today.",
        ],
        # behind the tavern's bar
        "doing.inn": [
            "Pouring and wiping, wiping and pouring.",
            "Keeping the mugs full and the peace kept.",
            "Behind the bar, where I belong.",
        ],
        # at the temple
        "doing.priest": [
            "Keeping the temple, and the candles lit.",
            "Prayers for the town, morning and evening.",
            "Anyone may come in and sit. The door's open.",
        ],
        # a noble out seeing the city
        "doing.noble": [
            "Seeing the city. One must, now and then.",
            "Out among the people, where one ought to be.",
            "A little air, a little conversation. Very civilised.",
        ],
        # playing, in the square or the tavern (mid-song they can't stop: ask.busy)
        "doing.bard": [
            "A song or two, for whoever will listen.",
            "Between tunes. My fingers need a rest.",
        ],
        # hauling the town's goods with the cart: grain, flour, produce
        "doing.carter": [
            "Grain to the mill, flour to the ovens. All day, every day.",
            "Hauling for the town. The cart's never empty for long.",
            "The cart and I know every street by now.",
        ],
        # the crier's day: the prices chalked of a morning, the news cried at noon
        "doing.crier": [
            "The news, at noon. Come and hear it.",
            "Prices on the board, news in the square. That's my day.",
        ],
        # grinding at the mill
        "doing.miller": [
            "Grinding. The stones never rest while there's grain.",
            "In goes the grain, out comes the flour. Mind the dust.",
        ],
        # a Starwatch mage at study or practice
        "doing.mage": [
            "Study, always study. The stars keep their secrets well.",
            "Reading, writing, and a little conjuring. A mage's day.",
        ],
        # a Hearthsworn knight at drill or on guard
        "doing.knight": [
            "Drill, then watch, then drill again. That's the oath.",
            "Keeping the hearth and the road, as sworn.",
        ],
        # one of the Quiet Hand, who never admits to anything
        "doing.thief": [
            "Me? Nothing. Nothing at all.",
            "Just passing the time. Honest.",
        ],
        # an ordinary townsperson's work about the house and town
        "doing.citizen": [
            "Sweeping, carrying, fetching. The usual.",
            "A bit of this and a bit of that. It all needs doing.",
            "Keeping the house in order. It never stays that way.",
        ],
        # at a workbench, at their craft
        "doing.craft": [
            "At the bench. Steady hands, steady work.",
            "Making and mending. There's a knack to it.",
        ],
    }),
    ("Market talk", MWO, TALK, {
        # the goods they buy are dear (the detail names them and their prices)
        "market.dear": [
            "Everything's dear these days.",
            "Have you seen the prices? I don't know how folk manage.",
            "My purse gets lighter every time I go to market.",
        ],
        # the goods they buy are cheap
        "market.cheap": [
            "Cheap at the stalls today, for once.",
            "The prices are kind just now. Buy while you can.",
        ],
        # something they want is short, or none at the stalls
        "market.short": [
            "Can't get it for love nor money.",
            "The stalls have run short. Folk are grumbling.",
            "There's none to be had. I've looked everywhere.",
        ],
        # the town is going hungry
        "market.hungry": [
            "The pot's been thin at our house.",
            "There's not enough to go round. Folk are going hungry.",
            "I've tightened my belt, and I'm not the only one.",
        ],
        # no bread to be had
        "market.no_bread": [
            "No bread to be had. Not one loaf.",
            "The bread's all gone, and there's mouths to feed.",
        ],
        # the mill isn't turning (its sails broken, or no grain to grind)
        "market.mill": [
            "The mill's not turning. Flour will be short.",
            "No flour from the mill. The bakers are at their wits' end.",
        ],
        # no fish at the stalls
        "market.no_fish": [
            "Not a fish to be had.",
            "No fish at the stalls. Somebody's going without.",
        ],
        # good news at the market: a trouble over, the stalls full again
        "market.glad": [
            "Good news at the market, for a change.",
            "Things are looking up at the stalls.",
        ],
        # their own shopping, at the stalls or on the way home with it (the detail says what they bought)
        "market.shopping": [
            "Just the usual bits and pieces.",
            "Got what I came for, and a bit more besides.",
            "I always spend more than I mean to.",
        ],
    }),
    ("The town", MWO, TALK, {
        # a festival within a few days (the detail names it and when)
        "town.festival_soon": [
            "There's a festival coming. You can feel it in the streets.",
            "Festival soon. The whole town's getting ready.",
        ],
        # the spring festival soon: the ribbon pole, the weave and the hunt
        "town.ribbonwake": [
            "Ribbonwake soon. I mean to find a ribbon this year.",
            "Ribbonwake's coming. Nothing beats the ribbon pole.",
        ],
        # the summer festival soon: the great fire and the sun wheel, the reel, archery at the butts
        "town.sunhearth": [
            "Sunhearth soon. Wait till you see the great fire.",
            "Sunhearth's coming. I've been practising at the butts.",
        ],
        # the autumn festival soon: the harvest stack, the rings, the produce show
        "town.sheafmoot": [
            "Sheafmoot soon. I've been baking for it all week.",
            "Sheafmoot's coming. The produce show's always a fight.",
        ],
        # the winter festival soon: the lantern tree, the riddles, the lanterns let go at dusk
        "town.lanternrise": [
            "Lanternrise soon. I've a lantern half made.",
            "Lanternrise is coming. Have you got a lantern yet?",
        ],
        # a festival today
        "town.festival_today": [
            "Festival day! The whole town's out.",
            "It's festival day. Enjoy it while it lasts.",
        ],
        # the day or two after a festival
        "town.festival_after": [
            "My head's still aching from the festival.",
            "What a festival that was. I'm still tired.",
        ],
        # a townsperson has died (the detail names them)
        "town.death": [
            "We've lost one of our own. Sad business.",
            "There's been a death in the town. It's hit us all hard.",
        ],
        # a newcomer household
        "town.newcomer": [
            "There's new folk in the town. Have you met them?",
            "New neighbours in the town. We'll see what they're like.",
        ],
        # a monster got into the town
        "town.monster": [
            "There was a monster in the streets. In the streets!",
            "Lock your door at night. There's been a monster about.",
            "A monster, right here in town. I've not slept since.",
        ],
        # after the crier's noon news
        "town.crier": [
            "Did you hear what the crier had to say?",
            "The crier's news has the whole square talking.",
        ],
        # outlaws on the roads
        "town.outlaws": [
            "There's outlaws on the roads again. Travel by day.",
            "Nobody's safe on the roads with those outlaws about.",
        ],
    }),
    ("Weather and season", MWO, TALK, {
        # raining
        "sky.rain": [
            "Raining again. Typical.",
            "It's coming down, and no mistake.",
            "Listen to that rain.",
        ],
        # a thunderstorm
        "sky.storm": [
            "Thunder like that turns the milk.",
            "Hear that? Best get indoors.",
        ],
        # snowing
        "sky.snow": [
            "Snow! Mind your footing.",
            "Look at it snow. Pretty, if you're not out in it.",
        ],
        # cold, and not snowing
        "sky.cold": [
            "There's a bite in the air.",
            "Keep moving, or the cold gets in your bones.",
        ],
        # hot
        "sky.hot": [
            "Hot enough to bake bread on the cobbles.",
        ],
        # the rain just stopped
        "sky.clearing": [
            "Dry at last. I thought it'd never stop.",
            "That's the rain done with, thank goodness.",
        ],
        # fair weather by day (greet_2, greet_7, gossip_4 and noble_0 are said then too)
        "sky.fine": [
            "Fine weather. Make the most of it.",
        ],
        # a clear night
        "sky.night": [
            "A clear night. Look at all those stars.",
            "Lovely night, isn't it? Clear as glass.",
        ],
        # the seasons: in its first days most, now and then later
        "sky.spring": [
            "Spring at last. Everything's coming up green.",
        ],
        "sky.summer": [
            "Summer. Long days, and short tempers.",
        ],
        "sky.autumn": [
            "Autumn already. Where does the year go?",
        ],
        "sky.winter": [
            "Winter. Keep your fire well fed.",
        ],
    }),
    ("Deeds and dealings", MW, TALK, {
        # to the player, about what they did (seen, or heard of: the detail says what and where)
        # a great beast slain, heard of
        "deed.beast": [
            "They say you killed a great beast. Is it true?",
            "Everyone's talking about that beast you brought down.",
        ],
        # a great beast slain, and they saw it
        "deed.beast_seen": [
            "I saw you bring that thing down. Never seen the like.",
            "I watched you fight that beast. My heart was in my mouth.",
        ],
        # a dragon slain
        "deed.dragon": [
            "A dragon! You really killed a dragon?",
            "A dragon-slayer, here in our town. Who'd have thought it?",
        ],
        # a monster's lair cleared
        "deed.lair": [
            "You cleared out a monster's lair, they say. Brave work.",
            "One monster's lair gone, thanks to you.",
        ],
        # a siege held off
        "deed.siege": [
            "They say you held off a siege. That took some nerve.",
        ],
        # an outlaw band broken
        "deed.band": [
            "You broke that band of outlaws! The roads are better for it.",
            "That band won't trouble anyone again, thanks to you.",
        ],
        # the wagon escorted in (M4): the gate guards and the keepers
        "deed.escort": [
            "You brought the wagon in safe. We'd have gone short without it.",
        ],
        # salvage brought back to the wagon (M4)
        "deed.salvage": [
            "You brought back what the wagon lost. That was honest of you.",
        ],
        # their order filled (M3)
        "deed.order": [
            "That order you filled came just in time.",
        ],
        # contracts done for the guild's board
        "deed.contract": [
            "You've been busy on the guild's board, I hear.",
            "Another contract done, they say. You don't sit still, do you?",
        ],
        # a festival's contest won
        "deed.festival": [
            "You took the prize at the festival! Well done.",
            "We're still talking about your win at the festival.",
        ],
        # a new rank at the guild
        "deed.rank": [
            "Risen in the guild, I hear. Congratulations.",
            "A new rank, they say. Good for you.",
        ],
        # cold Wardfires lit again
        "deed.kindle": [
            "They say you've been lighting the old Wardfires again.",
        ],
        # a monster killed in the town
        "deed.monster_town": [
            "You dealt with that monster in town. Thank you.",
            "That creature would have had someone, if not for you.",
        ],
        # the player saved them from a monster
        "deed.saved": [
            "You saved my life. I won't forget it.",
            "If you hadn't come when you did... thank you.",
        ],
        # the player freed them, or brought them home
        "deed.rescued": [
            "You got me out of there. I owe you more than I can say.",
        ],
        # any good deed, heard of
        "deed.praise": [
            "Heard what you did. Good on you.",
            "People speak well of you, you know.",
            "The town's glad to have you, and that's the truth.",
        ],
        # a deed they heard of and doubt (a tall version, or a grumpy teller)
        "deed.doubt": [
            "Can't believe everything you hear, mind.",
            "So they say. I wasn't there to see it.",
        ],
        # the player was caught lifting a purse
        "deed.theft": [
            "I'm watching your hands.",
            "Keep your fingers out of other folk's purses.",
        ],
        # the player struck them, or one of their household
        "deed.hurt": [
            "You raised your hand to us. We've not forgotten.",
            "Keep your fists to yourself, if you know what's good for you.",
        ],
        # the player killed a townsperson
        "deed.murder": [
            "You've a nerve, showing your face here.",
            "I know what you did. Everyone does.",
            "Stay away from me. Murderer.",
        ],
        # the player's homestead going up near the capital
        "deed.homestead": [
            "They say you're building yourself a homestead. Good for you.",
            "That homestead of yours is coming along, I hear.",
        ],
        # the player has joined a faction (the detail says which)
        "deed.faction": [
            "I hear you've taken sides. I hope it's the right one.",
            "So you're one of them now, are you?",
            "Word is you've found yourself some new friends.",
        ],
        # a keeper to a player who trades with them often
        "deal.regular": [
            "Always glad to see a regular{, %s}.",
            "You're one of my best customers{, %s}.",
        ],
        # what the player sold them sold on quickly
        "deal.sold": [
            "What you sold me went in an hour.",
            "Those goods of yours sold in no time.",
        ],
        # thanks for a favour (what the town was short of, an order filled, salvage, a rescue)
        "deal.thanks": [
            "Much obliged to you{, %s}, truly.",
            "I've not forgotten your kindness{, %s}.",
        ],
        # a slight they remember
        "deal.grudge": [
            "I haven't forgotten, you know.",
            "I've a long memory, mind.",
        ],
    }),
    ("News lead-ins", MWO, LEAD_IN, {
        # where they had the news and how sure they are; the news follows 6 ticks after (said in confidence: tall,
        # secret, 3 dB lower)
        # they saw it themselves
        "news.frame.seen": [
            "I saw it myself...",
            "I was there, I tell you...",
        ],
        "news.frame.tavern": [
            "Word in the tavern is...",
        ],
        "news.frame.well": [
            "They were saying at the well...",
        ],
        "news.frame.market": [
            "Word at the market is...",
        ],
        # heard at home, from their household
        "news.frame.home": [
            "At home, they were saying...",
        ],
        # heard at work, from their trade
        "news.frame.work": [
            "Word at work is...",
        ],
        # heard from the crier at noon
        "news.frame.crier": [
            "The crier had it...",
        ],
        # from far off: the guild's news, another capital's, a visitor's
        "news.frame.afar": [
            "Word from afar is...",
            "News from far off...",
        ],
        # heard somewhere or other
        "news.frame.they": [
            "They say...",
            "Everyone's saying...",
        ],
        # a tall tale (the rumour's wildest version)
        "news.frame.tall": [
            "You won't believe this, but...",
        ],
        # something told in confidence
        "news.frame.secret": [
            "Don't say I told you, but...",
        ],
    }),
    ("News", MW, NEWS, {
        # the news itself, after a lead-in: the gist, its facts in the detail ("Ransom, of the guild. Away to the
        # north."), as the version the teller heard tells them
        "news.dragon": [
            "...someone's gone and killed a dragon.",
            "...a dragon's been slain. A real one!",
        ],
        # the Hollow King slain
        "news.king": [
            "...the Hollow King has fallen.",
        ],
        # the sea's great beast slain
        "news.sea": [
            "...the great beast of the sea is dead.",
        ],
        # a great beast slain
        "news.beast": [
            "...someone's killed a great beast.",
            "...a monster's been brought down, a big one.",
        ],
        "news.siege_won": [
            "...a village held off a siege.",
        ],
        "news.siege_lost": [
            "...a village has fallen to a horde.",
        ],
        # an outlaw band about
        "news.band": [
            "...there's a band of outlaws working the roads.",
            "...outlaws are robbing folk on the roads.",
        ],
        "news.band_broken": [
            "...the outlaws on the roads have been scattered.",
        ],
        "news.raid": [
            "...outlaws have raided a village.",
        ],
        # a captive brought home
        "news.rescue": [
            "...a captive's been brought home safe.",
        ],
        # a new rank at the guild
        "news.rank": [
            "...one of the guild folk has risen in rank.",
        ],
        # a guild founded
        "news.guild": [
            "...there's a new guild been founded.",
        ],
        # a faction joined
        "news.faction": [
            "...someone's joined up with a faction.",
        ],
        # a homestead raised near the capital
        "news.homestead": [
            "...someone's raising a homestead nearby.",
        ],
        "news.festival_won": [
            "...somebody carried off the festival prize.",
        ],
        # a townsperson died
        "news.death": [
            "...someone's died. One of ours.",
            "...we've lost a neighbour.",
        ],
        # a townsperson killed by someone
        "news.murder": [
            "...someone was murdered. Here, in our town.",
            "...there's been a killing, and not by any beast.",
        ],
        # a townsperson struck by someone
        "news.assault": [
            "...somebody attacked one of our own.",
        ],
        # a purse lifted, and the thief caught
        "news.theft": [
            "...someone's been caught cutting purses.",
        ],
        "news.newcomer": [
            "...there's newcomers in town.",
        ],
        # a monster got into the town
        "news.monster": [
            "...there was a monster loose in the streets.",
        ],
        # anything else from far away (the detail says what)
        "news.far": [
            "...something's afoot, far off.",
            "...big things are happening, a long way off.",
        ],
        # said on its own: the player the news is about is in sight, within 16 blocks
        "news.there": [
            "That's the one, over there.",
            "Look, there they are now!",
        ],
    }),
    ("Replies", MWO, GREETING, {
        # the listener's answer to news, by its tone (reply_0 to reply_5 are said as wonder too)
        "reply.glad": [
            "Well, that's good news.",
            "Glad to hear it.",
            "That's welcome news, that is.",
        ],
        "reply.sorry": [
            "Poor soul.",
            "That's a sad business.",
            "Oh, that's terrible.",
        ],
        "reply.wonder": [
            "Go on!",
            "Never!",
            "Well, fancy that.",
        ],
        "reply.doubt": [
            "I'll believe it when I see it.",
            "A likely story.",
            "If you say so.",
        ],
    }),
    ("The household", MWO, TALK, {
        # to those they know well: who shares their home and what has befallen it
        # a full household
        "home.busy": [
            "No rest at our house. There's always something.",
            "A full house keeps you on your feet.",
        ],
        # children in the household
        "home.children": [
            "The little ones keep me on my toes.",
            "Children! They'll be the death of me.",
        ],
        # newcomers (the detail says since when)
        "home.new": [
            "We've only just moved in. Still finding our feet.",
            "We're new here. Still learning which street is which.",
        ],
        # a death in the household
        "home.grief": [
            "The house is quiet without them.",
            "We lost someone dear. It's hard.",
            "I keep setting a place for them at the table.",
        ],
        # no bed for them yet
        "home.nobed": [
            "Still no bed for me. The floor's hard, I can tell you.",
        ],
        # gossip about the neighbours, to a friend
        "home.neighbours": [
            "Don't get me started on the neighbours.",
            "The neighbours keep me up half the night.",
            "Between you and me, I've had words with next door.",
        ],
        # they went hungry lately
        "home.hungry": [
            "We've been going without at home.",
            "I went to bed hungry, and I'm not the only one.",
        ],
        # they were robbed lately
        "home.robbed": [
            "Someone had my purse. A week's wages, gone.",
            "I was robbed. I still can't believe it.",
        ],
    }),
    ("The old man's own", OLD, TALK, {
        # an old man, to those he knows well
        "home.old": [
            "My knees tell the weather before the sky does.",
            "I've seen more winters than I care to count.",
            "Nobody listens to the old. We were young once, you know.",
        ],
    }),
    ("The children's greetings and farewells", CHILD, GREETING, {
        "child.greet": [
            "Hello! Hello!",
            "Hello! Are you lost?",
        ],
        "child.bye": [
            "Bye! Bye-bye!",
            "See you later!",
        ],
    }),
    ("The children's talk", CHILD, TALK, {
        # what they're up to: at play, at their letters, or tagging along
        "child.doing": [
            "It's a secret!",
            "Nothing. Grown-ups always ask that.",
            "I'm busy. Very busy.",
        ],
        # telling a rumour (the detail tells it)
        "child.news": [
            "Guess what I heard!",
            "I know something you don't know!",
        ],
        # a good deed of the player's, seen or heard of
        "child.deed": [
            "Did you really do it? Everyone says you did!",
            "When I grow up, I'll be just like you!",
            "Teach me to fight! Please!",
        ],
        # the player is someone to be wary of
        "child.wary": [
            "I'm not allowed to talk to you.",
            "Go away! You're scary!",
        ],
        # any weather worth a word
        "child.weather": [
            "I love this weather!",
            "Look at the sky! Look!",
        ],
        # a festival soon, or today
        "child.festival": [
            "Festivals are the best thing in the whole world!",
            "Will there be dancing? I love the dancing!",
        ],
    }),
]

# The keepers' lines are held to other talk's limit; the five crier lines are make_townsfolk_voices.py's (CRIER)
STALL_PREFIX = "market."
CRIER_TALK = "crier_talk_"

# Delivery by family (or a stall line's key prefix). speed: times the timbre's own. pitch: cents added to the
# timbre's processing. level: dB over the common speech level (said in confidence, hushed or mumbled is lower; called
# after someone, over a shoulder or over a crowd is higher).
DELIVERY = {
    "news.frame": dict(speed=1.06),  # a quick run-up into the news
    "ask.busy": dict(speed=1.06, level=1.0),
    "ask.queue": dict(speed=1.04, level=1.0),
    "ask.asleep": dict(speed=0.92, pitch=-30, level=-4.0),
    "ask.hush": dict(level=-4.0),
    "news.frame.secret": dict(level=-3.0),
    "news.frame.tall": dict(level=-3.0),
    "bye.walked": dict(level=1.5),
    "market.shut.asleep": dict(speed=0.92, pitch=-30, level=-4.0),
    "market.greet.storm": dict(speed=1.04, level=1.0),
    "market.bye.walked": dict(level=1.5),
    "market.greet.cheerful": dict(level=0.5),
}
# A take's own speed, where its timbre's reading would run over its limit, misread or rush at the usual speed:
# (key, timbre) -> times the timbre's own, instead of its family's
SPEED_FOR = {
    ("talk.news.assault_0", "man"): 0.88,  # rushed at 8 syllables a second, the man's news being 5.5 to 7
}

# Text the TTS reads better (the subtitles keep the line as written): {written: said}, for every line, then a line's own
SAY_AS_ALL = {
    "Hmph.": "Humph.",  # otherwise spelled out, H-M-P-H (make_townsfolk_voices.SAY_AS)
}
SAY_AS = {}

# The voices are American and read espeak's British phonemes, as make_townsfolk_voices.py has them: but a British
# vowel whose r goes unsounded comes out bent or lost ("Zappa" for "Supper", "shot" for "short", "farge" for "forge",
# "cots" for "carts", "Dia" for "dear", "mock it" for "market"; the crier's and the festival's lines put such words
# right one at a time). So each line is read as espeak's British reading with the r's of its American reading sounded
# (rhotic): "ʃˈɔːt" and "ʃˈɔːɹt" give "ʃˈɔːɹt", "sˈʌpə" and "sˈʌpɚ" give "sˈʌpɚ", "dˈiə" and "dˈɪɹ" give "dˈɪɹ"; the
# British vowels stay, and with them the voice the townsfolk already have.
CENTRING = ("iə", "ɪə", "eə", "ɛə", "ʊə")


def rhotic(gb, us):
    """[gb] (espeak's British reading) with the r's of [us] (its American reading of the same text) sounded, word by
    word where the two readings have as many words (else over the whole line)"""
    gw, uw = gb.split(" "), us.split(" ")
    if len(gw) == len(uw):
        return " ".join(rhotic_word(g, u) for g, u in zip(gw, uw))
    return rhotic_word(gb, us)


def rhotic_word(gb, us):
    blocks = []  # (same?, British, American), each run of differences as one block
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=gb, b=us, autojunk=False).get_opcodes():
        if op != "equal" and blocks and not blocks[-1][0]:
            blocks[-1] = (False, blocks[-1][1] + gb[i1:i2], blocks[-1][2] + us[j1:j2])
        else:
            blocks.append((op == "equal", gb[i1:i2], us[j1:j2]))
    out = []
    for i, (same, a, b) in enumerate(blocks):
        before = "".join(out)
        after = blocks[i + 1][1] if i + 1 < len(blocks) else ""
        whole = before + a
        near_r = after.startswith("ɹ") or before.endswith("ɹ")
        if same or not re.search("[ɹɚ]", b) or re.search("[ɹɚ]", a):
            out.append(a)  # the same, or no r to sound here, or one sounded already
        elif not a:
            out.append("" if near_r else "ɹ" if "ɹ" in b else "ɚ")  # an r the British reading leaves out
        elif any(whole.endswith(c) and not whole[:-len(c)].endswith(tuple("aeoɔə")) for c in CENTRING):
            # a centring diphthong ("dˈiə", "ðeə", "wɪə"; not the "aɪə" of "fire"): its American vowel and r ("dˈɪɹ",
            # "ðɛɹ", "wɪɹ"), what came before it being the same in both
            out.append(re.sub("ɹ+$", "", b) + ("" if after.startswith("ɹ") else "ɹ") if b.endswith("ɹ") else b)
        elif "ɚ" in b and "ə" in a:
            k = a.rindex("ə")  # the schwa takes the r: "sˈʌpə" -> "sˈʌpɚ", "bˈɛtə" (American "bˈɛɾɚ") -> "bˈɛtɚ"
            out.append(a[:k] + "ɚ" + a[k + 1:])
        elif near_r:
            out.append(a)
        elif b.startswith("ɹ"):
            out.append("ɹ" + a)  # the r before what follows the vowel: "stˈɑːtɪd" (American "stˈɑːɹɾᵻd") -> "stˈɑːɹtɪd"
        else:
            out.append(a + "ɹ")  # a long vowel and its r: "fˈɔːdʒ" (American "fˈoːɹdʒ") -> "fˈɔːɹdʒ"
    return "".join(out)


# Phonemes put right in espeak's British reading (before the r's are sounded), as
# make_townsfolk_voices.CRIER_PHONEMES: (espeak's, Kokoro's), each matched as whole words. The American voices say
# "can't" with the long a as "cont" (make_festival_voices.py), and run a weak "We'll" or "for a" into "will" and
# "for". Each was heard wrong by the recogniser, and right once put so.
TALK_PHONEMES = [
    ("kˈɑːnt", "kˈant"),  # "Can't", not "cont"
    ("hˈɒləʊ", "hˈɒloʊ"),  # "Hollow", not "holler" (as knight_3)
    ("sˈʌnhɑːθz", "sˈʌnhɑːɹθs"),  # "Sunhearth's", not "Sun Hoth's"
    ("wiːl", "wˈiːl"),  # "We'll", not "will" (as the festival's)
    ("fəɹə", "fɔːɹ ɐ"),  # "for a bite", not "for bite"
    ("juː nˈəʊ", "juː nˈoʊ"),  # "you know", not "you now"
    ("wˈɒn", "wˈʌn"),  # "one" ("That's one mark" was heard "That's Walmart")
    ("wˌɒn", "wˌʌn"),
]
# A take's phonemes written out in full, its r's sounded: (key, timbre) or key -> phonemes. espeak spells out "Mm" and
# "Mmh" letter by letter ("em em aitch"), so the sleepy mumbles are a hum, and they fall at the end (a trailing "..."
# lifts it, as if more were coming); "home" takes the American o (the recogniser lost "Safe home" without it). The rest
# were heard wrong as espeak joins or weakens them, and right as written here (a stressed "at", "as", "has" or "and",
# a "been" said "bin", a pause the comma didn't give, an American vowel or a sounded t where the British one was taken
# for another word). A timbre's own entry is for a line the other timbres were heard saying right as espeak reads it.
PHONEMES = {
    "talk.ask.asleep_0": "mmm, nˌɒt nˈaʊ.",
    "talk.ask.asleep_1": "fˈaɪv mˈɔːɹ mˈɪnɪts.",
    "talk.bye.friend_2": "sˈeɪf hˈoʊm.",
    "market.shut.asleep": "mmm, wɪɹ ʃˈʌt... kˈʌm bˈak ɪnðə mˈɔːɹnɪŋ.",
    "talk.ask.hush_1": "kwˈaɪət, nˈaʊ. wˈɪɹ ˈat pɹˈɛɹ.",  # "at prayer", not "a prayer"
    "talk.ask.all_1": "ðats ðiː lˈɒt, aɪm ɐfɹˈeɪd.",  # "the lot", not "a lot"
    "talk.bye.walked_1": "nˌɒt sˈəʊ mˌʌtʃ ˈaz ɐ ɡʊdbˈaɪ.",  # "as a goodbye", not "is a goodbye"
    "talk.news.frame.well_0": "ðeɪ wɜː sˈeɪɪŋ ˈat ðə wˈɛl...",  # "at the well", not "it the well"
    "talk.town.monster_0": "ðɛɹ wˌɒz ɐ mˈɒnstɚɹ ɪn ðə stɹˈiːts. ɪn ðə stɹˈiːts!",  # "streets", not "street"
    "talk.home.robbed_0": "sˈʌmwʌn had maɪ pˈɜːs. ɐ wˈiːks wˈeɪdʒɪz... ɡˈɒn.",  # not "a week's wage is gone"
    "talk.reply.wonder_0": "ɡˌoʊ ˈɒn!",  # "Go on!", not "Go ons"
    "talk.town.festival_today_0": "fˈɛstɪvəl dˈeɪ! ðə hˈoʊl tˈaʊnz ˈaʊt.",  # its "out" was lost
    "talk.doing.knight_1": "kˈiːpɪŋ ðə hˈɑːɹθ, ˈænd ðə ɹˈəʊd, ˈaz swˈɔːɹn.",  # not "the hearth in the road, is sworn"
    "talk.market.cheap_1": "ðə pɹˈaɪsɪz ɑːɹ kˈaɪnd dʒˈʌst nˈaʊ. bˈaɪ wˈaɪl juː kˈan.",  # "while", not "well"
    "talk.sky.cold_1": "kˈiːp mˈuːvɪŋ, ɔːɹ ðə kˈəʊld ɡˈɛts ɪn jɔːɹ bˈoʊnz.",  # "bones", not "bounds"
    "talk.news.king_0": "ðə hˈɒloʊ kˈɪŋ hˈaz fˈɔːlən.",  # "has fallen", not "is fallen"
    "talk.news.band_1": "ˈaʊtlɔːz ɑːɹ ɹˈɑbɪŋ fˈəʊk ɒnðə ɹˈəʊdz.",  # "robbing", not "rubbing"
    "talk.news.rescue_0": "ɐ kˈaptɪvz bɪn bɹˈɔːt hˈəʊm sˈeɪf.",  # "been", not "is being"
    "talk.news.guild_0": "ðɛɹz ɐ njˈuː ɡˈɪld bɪn fˈaʊndɪd.",  # "been founded", not "being founded"
    "market.shut.done.bakery": "ðə bɹˈɛdz ˈaʊt fɚðə dˈeɪ. kˈʌm ˈat fˈɜːst lˈaɪt.",  # "at first light"
    "market.greet.rain.0": "kˈʌm ˈʌndɚ ðɪ ˈɔːnɪŋ, ˌaʊtəv ðə wˈɛt.",  # "under", not "into"
    "market.greet.rain.indoors": "wˈɛtʰ ˈaʊt ðˈɛɹ? kʌm ˈɪn... ænd dɹˈaɪ ˈɒf.",  # not "What out there? Coming..."
    "market.greet.smithy": "ˈaɪɚn ænd kˈoʊl... ɡˈʊd ænd dɹˈaɪ.",  # "coal", not "cold"
    "market.poor.one": "ðats wˈʌn mˈɑːɹk, fɹˈɛnd, ˈænd jɔːɹ ʃˈɔːɹt.",  # not "That's Walmart, friend, in your..."
    "market.dear.again": "dˈɪɹ, aɪ nˈəʊ. kˈæntʰ biː hˈɛlpt.",  # "Can't be helped", not "Can be helped"
    "market.dear.carts_behind": "dˈɪɹ tədˈeɪ: ðə kˈɑːɹtʰs hˈavənt bɹˈɔːt ɪt ɪn jˈɛt.",  # "carts", not "cards"
    "market.dear.event.fowl_pest": "dˈɪɹ tədˈeɪ: ɐ pˈests ɡɒt ɐmˌʌŋ ðə hˈinz.",  # not "a past's got among the hands"
    "market.dear.event.murrain": "dˈɪɹ tədˈeɪ: ðɛɹz mˈʌɹɪn ɐmˌʌŋ ðə hˈɜːdz.",  # "murrain" as said, not "Maureen"
    "market.dear.event.poor_catch": "dˈɪɹ tədˈeɪ: ðə bˈəʊts kˈeɪm ɪn nˈɪɹ ˈɛmpti.",  # "near empty", not "your empty"
    "market.dear.no_flour": "dˈɪɹ tədˈeɪ: ðɪ ˈʌvənz stˈʊd kˈəʊld, fɔːɹ wˈɒntʰ ɒv flˈaʊɚ.",  # "want of", not "one of"
    "market.dear.orchard_lean": "dˈɪɹ tˈɪl ˈɔːtəm, aɪm ɐfɹˈeɪd: ðɪ ˈɔːɹtʃɚdz ˌɑːɹnt bˈɛɹɪŋ.",  # not "Tillatum"
    "market.dear.scarce": "dˈɪɹ tədˈeɪ: ðˌɛɹz lˈɪtəl ˈɒv ɪt ɐbˈaʊt.",  # "there's little", not "there's a little"
    "talk.deal.sold_0": "wˌɒt juː sˈəʊld mˌiː, wˈɛnt ɪn ɐn ˈaʊɚ.",  # not "Would you sold me when in an hour?"
    "talk.deed.doubt_0": "kˈant bɪlˈiːv ˈɛvɹɪθˌɪŋ juː hˈɪɹ... mˈaɪnd.",  # not "...you hear in mind"
    "talk.deed.dragon_1": "ɐ dɹˈaɡən slˈeɪjɚ, hˈɪɹ ɪn ˌaʊɚ tˈaʊn. hˌuːd hav θˈɔːt ɪt?",  # not "A dragon's lair"
    "talk.deed.rank_1": "ɐ nˈuː ɹˈaŋk, ðeɪ sˈeɪ. ɡˈʊd fɔːɹ juː.",  # "A new rank", not "And you rank"
    "talk.doing.fisher_1": "ɐ kwˈaɪət spˈɒt, ɐ ɡˈʊd lˈaɪn. kˈæntʰ ˈæsk fɔːɹ mˈɔːɹ.",  # "Can't ask", not "can ask"
    "talk.doing.smith_2": "hˈɪndʒɪz, nˈeɪlz, hˈəʊz. ðɛɹz ˈɔːlweɪz sˈʌmθɪŋ wˈɑnts mˈeɪkɪŋ.",  # "wants", not "once"
    # the man's own, where the old man's was heard right as espeak reads it
    ("talk.ask.hush_1", "man"): "kwˈaɪət, nˈaʊ. wɪɹ ˈæt pɹˈɛɹ.",  # "at prayer", not "a prayer"
    ("talk.greet.hero_1", "man"): "ɪz ɪt ɹˈiəlɪ juː? wˈɛl... aɪ nˈɛvɚ!",  # "Well", not "while"
    ("talk.home.neighbours_1", "man"): "ðə nˈeɪbɚz kˈiːp mˌiː ˌʌp hˈaf ðə nˈaɪt.",  # "half", not "off"
    ("talk.market.dear_0", "man"): "ˈɛvɹɪθˌɪŋz dˈiəɹ ðiːz dˈeɪz.",  # "dear", not "due"
    ("talk.market.hungry_0", "man"): "ðə pˈɒts bɪn θˈɪn at ˌaʊɚ hˈaʊs.",  # "been", not "being"
    ("talk.market.shopping_1", "man"): "ɡˈɒt wɒt aɪ kˈeɪm fɔːɹ, and ɐ bˈɪt mˈɔːɹ bɪsˈaɪdz.",  # "Got", not "Go"
    ("talk.town.newcomer_1", "man"): "nˈuː nˈeɪbɚz ɪnðə tˈaʊn. wˈiːl sˈiː wɒt ðeɪɚ lˈaɪk.",  # "New", not "You"
    ("talk.sky.clearing_0", "man"): "dɹˈaɪ ˈat lˈast. aɪ θˈɔːt ɪt wʊd nˈɛvɚ stˈɒp.",  # not "it had never stopped"
    # the old man's own, where the man's was heard right
    ("talk.ask.dunno_0", "old"): "aɪv nˈɒt hˈɜːɹd ɐ θˈɪŋ.",  # not "I have, I have"
    # the woman's own
    ("market.greet.smithy", "woman"): "ˈaɪɚn ænd kˈoʊl, ɡˈʊd ænd dɹˈaɪ.",  # "coal", not "cold"
    ("talk.doing.farmer_sow_1", "woman"): "sˈəʊɪŋ, ɹˈəʊ ˈaftɚ ɹˈəʊ. maɪ bˈaks hˈad ɪnˈʌf ɔːlɹˈɛdi.",  # not "back's at"
    ("talk.greet.wary_1", "woman"): "mˈeɪk ɪt kwˈɪk!",  # "quick", not "quicker"
    ("talk.home.neighbours_1", "woman"): "ðə nˈeɪbɚz kˈiːp mˌiː ˌʌp hˈaf ðə nˈaɪt.",  # "half", not "off"
    ("talk.news.far_0", "woman"): "sˈʌmθɪŋz ɐfˈʊt... fˈɑːɹ ˈɒf.",  # not "Some things are foot"
    ("talk.sky.snow_0", "woman"): "snˈoʊ! mˈaɪnd jɔːɹ fˈʊtɪŋ.",  # "Snow!", not "Now"
    ("talk.town.sheafmoot_0", "woman"): "ʃˈiːf mˌuːt sˈuːn. aɪv bˌiːn bˈeɪkɪŋ fɔːɹ ɪt ˈɔːl wˈiːk.",  # not "She've moved"
    ("talk.town.sheafmoot_1", "woman"): "ʃˈiːf mˌuːts kˈʌmɪŋ. ðə pɹˈoʊdus ʃˈəʊz ˈɔːlweɪz ɐ fˈaɪt.",  # not "the Pradhu"
    # a child's
    "talk.child.weather_1": "lˈʊk at ðə skˈaɪ! lˈʊkʰ!",  # "Look!", not "Loki"
}


# ------------------------------------------------------------------ the lines
class Line:
    """one key: its text (what the take says), its .named text or None, the timbres that say it, its limit"""

    def __init__(self, key, family, text, named, timbres, limit):
        self.key, self.family, self.text, self.named, self.timbres, self.limit = key, family, text, named, timbres, limit


def families():
    """{family: (timbres, limit, [wording, ...])}, in the table's order"""
    out = {}
    for _, timbres, limit, fams in GROUPS:
        for family, ws in fams.items():
            assert family not in out, f"{family} is in the table twice"
            assert 1 <= len(ws) <= 4, f"{family} has {len(ws)} wordings"
            braced = ["{" in w for w in ws]
            assert all(braced) or not any(braced), f"{family}: .named texts for some wordings but not all"
            out[family] = (timbres, limit, ws)
    return out


def talk_lines():
    """{key: Line} for every talk take"""
    out = {}
    for family, (timbres, limit, ws) in families().items():
        for i, w in enumerate(ws):
            text = re.sub(r"\{[^}]*\}", "", w)
            named = w.replace("{", "").replace("}", "") if "{" in w else None
            assert "%" not in text and "{" not in text and "}" not in text, f"{family}_{i}: {w}"
            out[f"talk.{family}_{i}"] = Line(f"talk.{family}_{i}", family, text, named, timbres, limit)
    return out


def stall_lines(lang_path=LANG):
    """{key: Line} for the keepers' lines that name nothing, as the language file has them"""
    with open(lang_path, encoding="utf-8") as f:
        lang = json.load(f)
    out = {}
    for k, v in lang.items():
        if k.startswith("say.guildmark." + STALL_PREFIX) and "%" not in v:
            key = k[len("say.guildmark."):]
            out[key] = Line(key, None, v, None, MW, TALK)
    return out


def all_lines(timbres=None, keys=None):
    """{timbre: [Line, ...]}: the talk takes and the keepers' ([timbres], [keys]: a regex on the key)"""
    lines = dict(talk_lines(), **stall_lines())
    out = {}
    for timbre in TIMBRES:
        if timbres and timbre not in timbres:
            continue
        out[timbre] = [ln for ln in lines.values() if timbre in ln.timbres and (not keys or re.search(keys, ln.key))]
    return out


def delivery(line):
    """the delivery for [line]: its family's, else the longest key prefix that has one"""
    d = dict(speed=1.0, pitch=0, level=0.0)
    name = line.family or line.key
    for k in sorted(DELIVERY, key=len):
        if name == k or name.startswith(k + ".") or name.startswith(k + "_"):
            d.update(DELIVERY[k])
    return d


def speed(line, timbre):
    return mtv.TIMBRES[timbre]["speed"] * SPEED_FOR.get((line.key, timbre), delivery(line)["speed"])


def spoken(line):
    """what the TTS reads for [line] (before phonemes): its text after SAY_AS, a news line's leading dots dropped"""
    text = line.text
    for a, b in list(SAY_AS_ALL.items()) + list(SAY_AS.get(line.key, {}).items()):
        text = text.replace(a, b)
    return text.lstrip(". ")


def fix_phonemes(ph):
    """espeak's reading with TALK_PHONEMES put right, each matched as whole words"""
    for a, b in TALK_PHONEMES:
        ph = re.sub(r"(?<![^\s,.!?;:…—])" + re.escape(a) + r"(?![^\s,.!?;:…—])", b, ph)
    return ph


def said(k, line, timbre):
    """the phonemes Kokoro is given for [line] in [timbre]"""
    ph = PHONEMES.get((line.key, timbre)) or PHONEMES.get(line.key)
    if ph:
        return ph
    text = spoken(line)
    return rhotic(fix_phonemes(k.tokenizer.phonemize(text, mtv.TIMBRES[timbre]["lang"])),
                  k.tokenizer.phonemize(text, "en-us"))


# ------------------------------------------------------------------ stage 1: TTS
def cmd_tts(a):
    import soundfile as sf
    k = mtv.kokoro(types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data))
    for timbre, lines in all_lines(a.timbres, a.keys).items():
        t = mtv.TIMBRES[timbre]
        folder = os.path.join(a.out, timbre)
        os.makedirs(folder, exist_ok=True)
        for line in lines:
            fn = os.path.join(folder, line.key + ".wav")
            if os.path.exists(fn) and not a.force:
                continue
            sp = speed(line, timbre)
            audio, sr = k.create(said(k, line, timbre), voice=t["voice"], speed=sp, lang=t["lang"], is_phonemes=True)
            sf.write(fn, np.asarray(audio, dtype=np.float32), sr, subtype="FLOAT")  # Kokoro can peak over 1.0
            print(fn, f"speed {sp:.3f}  {len(audio) / sr:.2f}s", flush=True)


def cmd_phonemes(a):
    k = mtv.kokoro(types.SimpleNamespace(model=a.model, voices=a.voices, espeak_data=a.espeak_data))
    for timbre, lines in all_lines(a.timbres, a.keys).items():
        for line in lines:
            print(f"{timbre:6s} {line.key:30s} {said(k, line, timbre)}")


# ------------------------------------------------------------------ stage 2: processing
def cmd_process(a):
    import warnings
    from scipy import signal
    from scipy.io import wavfile
    from sfxkit import SR, read_audio, write_ogg, write_wav
    over = []
    for timbre, lines in all_lines(a.timbres, a.keys).items():
        for line in lines:
            with warnings.catch_warnings():  # the float WAV's PEAK chunk, which scipy skips
                warnings.simplefilter("ignore", wavfile.WavFileWarning)
                sr, x = wavfile.read(os.path.join(a.raw, timbre, line.key + ".wav"))
            x = x.astype(np.float64)
            if x.ndim > 1:
                x = x.mean(axis=1)
            d = delivery(line)
            st = dict(mtv.STYLE[timbre])
            st["pitch"] = st["pitch"] + d["pitch"]
            y = mtv.process_line(x, sr, st, line.key, timbre, d["level"])
            y = mtv.sox(y, SR, out_sr=mtv.OUT_SR)
            seconds = len(y) / mtv.OUT_SR
            if seconds > line.limit:
                over.append(f"{timbre} {line.key} {seconds:.2f}s, {seconds - line.limit:.2f}s over {line.limit}")
                print(f"REFUSED {timbre}/{line.key}: {seconds:.2f}s is over its {line.limit}s: shorten the wording "
                      "or quicken it in SPEED_FOR", flush=True)
                continue
            out = os.path.join(a.assets, "sounds", mtv.SOUND_DIR, timbre, line.key + ".ogg")
            os.makedirs(os.path.dirname(out), exist_ok=True)
            for _ in range(3):  # held under -1 dBFS true peak in the encoded file, the encoder's overshoot too
                write_ogg(out, y, sr=mtv.OUT_SR, quality=3)
                dec = read_audio(out, sr=mtv.OUT_SR)[0]
                peak = 20 * np.log10(np.max(np.abs(signal.resample_poly(dec, 4, 1))) + 1e-12) + 1.0
                if peak <= 0:
                    break
                y = y * 10 ** (-(peak + 0.05) / 20)
            if a.wav:
                write_wav(os.path.join(a.wav, timbre, line.key + ".wav"), y, sr=mtv.OUT_SR)
            print(f"{out}  {seconds:.2f}s", flush=True)
    if over:
        print(f"{len(over)} takes refused, over their limits:\n  " + "\n  ".join(over))
        sys.exit(1)


# ------------------------------------------------------------------ stages 4 and 5: the entries and TalkLines.kt
def ogg_seconds(path):
    """an Ogg Vorbis file's length: its last page's granule position over the identification header's rate"""
    with open(path, "rb") as f:
        data = f.read()
    rate = struct.unpack_from("<I", data, data.index(b"\x01vorbis") + 12)[0]
    last = data.rindex(b"OggS")
    return struct.unpack_from("<q", data, last + 6)[0] / rate


def ticks_of(seconds):
    return int(math.ceil(seconds * 20 - 1e-6))


def crier_lines():
    """{key: text}: the crier's talk of the town (make_townsfolk_voices.py's CRIER)"""
    return {k: v for k, v in mtv.CRIER.items() if k.startswith(CRIER_TALK)}


def entries():
    """(sounds.json entries, en_us.json entries) for the takes, the keepers' lines and the crier's talk of the town"""
    sounds, lang = {}, {}
    talk, stall = talk_lines(), stall_lines()
    for timbre in TIMBRES:
        for line in list(talk.values()) + list(stall.values()):
            if timbre in line.timbres:
                sounds[f"townsfolk.voice.{timbre}.{line.key}"] = {
                    "sounds": [f"guildmark:{mtv.SOUND_DIR}/{timbre}/{line.key}"],
                    "subtitle": f"say.guildmark.{line.key}",
                }
        if timbre in mtv.CRIER_TIMBRES:
            for key in crier_lines():
                sounds[f"townsfolk.voice.{timbre}.{key}"] = {
                    "sounds": [{"name": f"guildmark:{mtv.SOUND_DIR}/{timbre}/{key}",
                                "attenuation_distance": mtv.CRIER_REACH}],
                    "subtitle": f"say.guildmark.{key}",
                }
    for line in talk.values():
        lang[f"say.guildmark.{line.key}"] = line.text
        if line.named:
            lang[f"say.guildmark.{line.key}.named"] = line.named
    for key, text in crier_lines().items():
        lang[f"say.guildmark.{key}"] = text
    return sounds, lang


def cmd_sounds(a):
    print(json.dumps(entries()[0], indent=2, ensure_ascii=False))


def voiced(sounds, assets):
    """{key: {timbre: seconds}} for every townsfolk.voice.<timbre>.<key> event in [sounds] whose file is there"""
    out = {}
    for event, entry in sounds.items():
        m = re.fullmatch(r"townsfolk\.voice\.(man|woman|old|child)\.(.+)", event)
        if not m:
            continue
        s = entry["sounds"][0]
        name = (s["name"] if isinstance(s, dict) else s).split(":", 1)[1]
        path = os.path.join(assets, "sounds", name + ".ogg")
        if os.path.exists(path):
            out.setdefault(m.group(2), {})[m.group(1)] = ogg_seconds(path)
    return out


def kotlin(fams, lengths, others):
    """TalkLines.kt: [fams] {family: (count, ticks, named, timbres)}, [others] {key: ticks}"""
    sets = {MWO: "MWO", MW: "MW", OLD: "OLD", CHILD: "CHILD"}
    rows = []
    for family, (count, ticks, named, timbres) in fams.items():
        rows.append(f'        "{family}" to Family({count}, intArrayOf({", ".join(map(str, ticks))}), {str(named).lower()}, '
                    f'{sets[timbres]}),')
    other_rows = [f'        "{k}" to {v},' for k, v in sorted(others.items())]
    longest = max(t for _, (_, ticks, _, _) in fams.items() for t in ticks)
    return f'''package io.github.vaspyyy.guildmark.townsfolk.talk

import net.minecraft.util.RandomSource

/**
 * The townsfolk's talk lines that have takes recorded, generated by art/audio/make_talk_voices.py (`table`) from its
 * table and the takes on disk: don't edit by hand. Each family ("greet.morning") is one meaning said in [Family.count]
 * wordings, keys talk.<family>_0 to _<count - 1>; [Family.ticks] is each wording's length in ticks (the longest
 * timbre's take, {lengths} in all, the longest {longest} ticks); [Family.named] says whether it has .named texts
 * (say.guildmark.talk.<family>_<n>.named, with the player's name as their one argument, "There you are, %s!"; but
 * name.pleased's take two, the speaker's own given name and then the player's: "%1$s. Pleased to meet you, %2$s.");
 * [Family.timbres] are the timbres that have takes of it ("old" borrows the man's lower and "child" the woman's
 * higher for what they lack, as TownsfolkClient.speak does).
 *
 * Every wording of a family is a whole sentence true whenever its family is: the comments in make_talk_voices.py say
 * when that is. A take plays at the speaker's pitch, so it lasts [ticks] divided by the pitch the client gives it.
 */
object TalkLines {{
    class Family(val count: Int, val ticks: IntArray, val named: Boolean, val timbres: Set<String>)

    private val MWO = setOf("man", "woman", "old")
    private val MW = setOf("man", "woman")
    private val OLD = setOf("old")
    private val CHILD = setOf("child")

    val FAMILIES: Map<String, Family> = mapOf(
{chr(10).join(rows)}
    )

    /**
     * How long each of the other voiced townsfolk lines lasts, in ticks (the longest timbre's): the old lines, the
     * keepers' (market.*), the festival's and the crier's, so that a talk can say any of them in step.
     */
    val OTHERS: Map<String, Int> = mapOf(
{chr(10).join(other_rows)}
    )

    /** A wording of [family] not in [avoid] if there is one (else any), as its key; null if none is recorded. */
    fun pick(family: String, avoid: Set<String>, random: RandomSource): String? {{
        val f = FAMILIES[family]?.takeIf {{ it.count > 0 }} ?: return null
        val keys = (0 until f.count).map {{ "talk.${{family}}_$it" }}
        val fresh = keys.filter {{ it !in avoid }}
        val from = fresh.ifEmpty {{ keys }}
        return from[random.nextInt(from.size)]
    }}

    /** How long take [key] lasts, in ticks; 0 if it isn't recorded. */
    fun ticks(key: String): Int {{
        OTHERS[key]?.let {{ return it }}
        val name = key.removePrefix("talk.")
        val cut = name.lastIndexOf('_')
        if (cut < 0) return 0
        val f = FAMILIES[name.substring(0, cut)] ?: return 0
        return name.substring(cut + 1).toIntOrNull()?.let {{ f.ticks.getOrNull(it) }} ?: 0
    }}
}}
'''


def cmd_table(a):
    sounds, lang = entries()
    talk = talk_lines()
    missing = [f"{t}/{ln.key}" for ln in talk.values() for t in ln.timbres
               if not os.path.exists(os.path.join(a.assets, "sounds", mtv.SOUND_DIR, t, ln.key + ".ogg"))]
    if missing:
        sys.exit(f"{len(missing)} takes not processed yet, e.g. {missing[:5]}")
    with open(os.path.join(a.assets, "sounds.json"), encoding="utf-8") as f:
        game = json.load(f)
    lengths = voiced(dict(game, **sounds), a.assets)
    fams = {}
    for family, (timbres, limit, ws) in families().items():
        ticks = []
        for i in range(len(ws)):
            took = lengths[f"talk.{family}_{i}"]
            assert set(took) == set(timbres), f"{family}_{i}: takes in {sorted(took)}, wanted {timbres}"
            ticks.append(ticks_of(max(took.values())))
        fams[family] = (len(ws), ticks, "{" in ws[0], timbres)
    others = {k: ticks_of(max(v.values())) for k, v in lengths.items() if not k.startswith("talk.")}
    for path, data in ((os.path.join(HERE, "talk_sounds.json"), sounds), (os.path.join(HERE, "talk_lang.json"), lang)):
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        print(path, len(data), "entries")
    takes = sum(len(ln.timbres) for ln in talk.values())
    with open(KOTLIN, "w", encoding="utf-8") as f:
        f.write(kotlin(fams, takes, others))
    print(KOTLIN, len(fams), "families,", len(talk), "keys,", takes, "takes,", len(others), "other lines")


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
        t.add_argument("--espeak-data", default=None)
        t.add_argument("--keys", default=None, help="only lines whose key matches this regex")
        t.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    p = sub.add_parser("process")
    p.add_argument("--raw", required=True)
    p.add_argument("--assets", required=True)
    p.add_argument("--wav", default=None)
    p.add_argument("--keys", default=None)
    p.add_argument("--timbres", default=None, type=lambda s: s.split(","))
    sub.add_parser("sounds")
    t = sub.add_parser("table")
    t.add_argument("--assets", default=ASSETS)
    a = ap.parse_args()
    {"tts": cmd_tts, "phonemes": cmd_phonemes, "process": cmd_process, "sounds": cmd_sounds, "table": cmd_table}[a.cmd](a)


if __name__ == "__main__":
    main()
