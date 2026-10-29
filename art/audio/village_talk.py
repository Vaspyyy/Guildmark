"""The villages' talk families (living-world/villages/design.md section 17.4, "Talk families", and 8.3): what a village's
folk say on the talk screen and in passing of the wagon, the pedlar, the bell, the watch, a siege, the dead, the new ones,
the city's word, the builders and the player, as families of wordings for make_talk_voices.py's table. A module of its own,
as the build plan (living-world/build-plan.md section 6) asks of every design's voices: it imports nothing from the script,
takes the script's timbre sets and length limit as arguments, and make_talk_voices.py enters it by one block after the
tales' (`GROUPS += village_talk.groups(...)`, and its four tables updated from DELIVERY, SPEED_FOR, SAY_AS and PHONEMES
below), and only once the takes exist: `table` refuses a family with no take.

A family is one meaning said one or two ways, each a whole sentence that is true whenever the family is picked (the
comment over each family says when that is: the topics are drawn from the village's records, design 8.3). Whatever changes
(the capital's name, the hour, what the wagon brought, the pedlar's or the bell's holder's name, who died, the goods) is
never voiced: it follows the take as grey text, which the village's own talk source writes. So no wording names a person,
a town, a good, a day or an hour, and none has a .named text. The takes are talk.<family>_0, _1, ...; the man, the woman
and the old man say every family (a child borrows the woman's, higher, as TownsfolkClient.speak does).

Wordings changed from the design's 17.4 table, each so that it is true whenever its family is picked (the comment beside
it gives the design's words and why): the wagon's hour and day ("in the morning", "at nine", "this morning", "in a week"),
the pedlar's day ("It's a pedlar day" of tomorrow's) and the guess at why a visit was lost ("The roads must be bad": a band,
a killing or a command loses one too), the watch's tower and night, the wall a siege came over or broke (a village with
only lamp posts can be besieged), the candle by the bell (it burns down in a week and the family lasts ten days), the
"good people" of one death, the wall the builders are on and mended (they build a hall, a tower, a market, lamps), and the
bread the player brought (it may be iron).

make_townsfolk_voices.py's village groups (BELLMAN, PEDLAR, SHELTER, VILLAGE) are the lines said aloud outside the talk.
"""

# (group, the group's timbre set, [(family, who says it and when, [wordings])]), in the order the design lists them.
# The timbre sets are "MWO" (man, woman and old) and "CHILD", as make_talk_voices.py names them.
TABLE = [
    ("The village: the wagon and the pedlar", "MWO", [
        # the wagon is due today or tomorrow (grey: the capital's name and the hour)
        ("village.cart.due", "anyone, the wagon is due today or tomorrow (grey: the capital's name, the hour)", [
            "The wagon's due before long.",  # the design's "due in the morning": it may be due at noon or three
            "There'll be a wagon along soon.",  # the design's "at nine": the hour follows in grey, and it may be noon or three
        ]),
        # a wagon called at the village today (grey: what it brought)
        ("village.cart.in", "anyone, a wagon called today (grey: what it brought)", [
            "The wagon came in today.",  # the design's "this morning": a wagon may come in at noon or three
            "The wagon's in. Have you seen what it brought?",
        ]),
        # more than three days since a wagon called, and the road is troubled
        ("village.cart.late", "anyone, more than three days since a wagon called and the road troubled", [
            "No wagon for days now.",
            "It's been a long while since the last wagon.",  # the design's "We've not seen a wagon in a week": it may be four days
        ]),
        # a wagon of this village was waylaid in the last five days
        ("village.cart.taken", "anyone, a wagon of the village waylaid in the last five days", [
            "They took the wagon on the road.",
            "The wagon never came back.",
        ]),
        # the pedlar's day is today or tomorrow (grey: the pedlar's name)
        ("village.pedlar.due", "anyone, the pedlar's day is today or tomorrow (grey: the pedlar's name)", [
            "The pedlar's due on the green.",
            "The pedlar will be along soon.",  # the design's "It's a pedlar day.": the day may be tomorrow
        ]),
        # the pedlar's last visit was lost (the village market's cart record has it missed)
        ("village.pedlar.missed", "anyone, the pedlar's last visit was lost", [
            # the design's "No pedlar this week. The roads must be bad.": a visit is lost to a shut road, a band camped near, a
            # pedlar killed or a command, and the lost visit may be more than a week back; nobody calls the pedlar he or she
            "No pedlar this time. Something kept them away.",
        ]),
    ]),
    ("The village: the bell and the watch", "MWO", [
        # said by the resident who holds the village's bell this week
        ("village.bell.mine", "the bell's holder, of themself", [
            "It's my week for the bell.",
            "I've the bell this week.",
        ]),
        # said of the holder by anyone else (grey: the holder's name)
        ("village.bell.other", "anyone, of the bell's holder (grey: their name)", [
            "They've the bell this week.",
        ]),
        # the village has a watch
        ("village.watch", "anyone, the village has a watch", [
            "We've a watch of our own.",  # the design's "There's a guard up on the tower tonight.": not every watch has a tower, nor is it always night
            "The watch keeps us safe.",
        ]),
    ]),
    ("The village: sieges and the dead", "MWO", [
        # a siege of the village was held in the last ten days
        ("village.siege.held", "anyone, a siege held in the last ten days", [
            "They came at us, and we held.",  # the design's "came over the wall": a village with only lamp posts can be besieged
            "We held them off. Thank the ones who stood.",  # the design's "We held the wall. Thank the guard.": likewise, and the player may have held it
        ]),
        # a siege of the village was lost in the last ten days
        ("village.siege.lost", "anyone, a siege lost in the last ten days", [
            "They broke through. We've not got over it.",  # the design's "The wall fell. We're mending.": there may be no wall, and the mending may be done
        ]),
        # someone of the village died in the last ten days (grey: the name)
        ("village.dead", "anyone, someone of the village died in the last ten days (grey: the name)", [
            "We lit a candle for them.",  # the design's "There's a candle by the bell for them.": a candle burns down in a week
            "We lost one of our own.",  # the design's "We lost good people.": one may have died
        ]),
    ]),
    ("The village: births, newcomers and the city's word", "MWO", [
        # a birth in the village in the last three days
        ("village.born", "anyone, a birth in the last three days", [
            "A baby in the village! Have you heard?",
        ]),
        # newcomers arrived in the last five days
        ("village.newcomers", "anyone, newcomers in the last five days", [
            "New folk have come. We're glad of them.",
        ]),
        # the wagon carried word from the capital (grey: the word)
        ("village.capital", "anyone, the wagon brought word from the city (grey: the word)", [
            "The wagon brought word from the city.",
        ]),
    ]),
    ("The village: building and mending", "MWO", [
        # a crew is at work in the village
        ("village.build.going", "anyone, a crew at work", [
            "There's building going on.",  # the design's "They're putting up the wall.": they may be building a hall, a tower, a market or lamps
            "The builders are hard at it.",  # the design's "at it again": the first build is not again
        ]),
        # a project was finished in the last seven days
        ("village.build.done", "anyone, a project finished in the last seven days", [
            "The work's done at last.",  # the design's "The wall's built at last.": likewise
            "That's a weight off.",
        ]),
        # a mend finished in the last three days
        ("village.mended", "anyone, a mend finished in the last three days", [
            "They mended the damage. Good as new.",  # the design's "the wall": a mend puts back posts and lamps
        ]),
    ]),
    ("The village: the player, and the days after the trouble", "MWO", [
        # to a player who supplied the village in the last five days (grey: the goods)
        ("village.fed", "to a player who supplied the village in the last five days (grey: the goods)", [
            "You brought what we needed. We'll not forget it.",  # the design's "brought the bread": it may have been iron
            "They say you kept the whole village going. Bless you.",  # the design's "fed the whole village": likewise
        ]),
        # a siege or a raid in the last three days
        ("village.shelter.after", "anyone, a siege or a raid in the last three days", [
            "I don't want to go through that again.",
        ]),
    ]),
]


def groups(mwo, child, limit):
    """[(group name, timbres, limit in seconds, {family: [wordings]})] in make_talk_voices.py's own form: [mwo] and
    [child] are its timbre sets, [limit] its length limit for other talk"""
    sets = {"MWO": mwo, "CHILD": child}
    return [(name, sets[who], limit, {family: list(ws) for family, _, ws in families})
            for name, who, families in TABLE]


def families():
    """{family: (timbre set's name, who says it and when, [wordings])}, in the table's order"""
    return {family: (who, said, list(ws)) for _, who, fams in TABLE for family, said, ws in fams}


# A family's delivery, as make_talk_voices.DELIVERY (speed: times the timbre's own; pitch: cents; level: dB over the speech
# level): the lost wagon, a lost siege, the dead and the night after said lower and slower, a birth brighter, the thanks
# warm. Every other family is said plainly
DELIVERY = {
    "village.cart.taken": dict(speed=0.96, pitch=-20, level=-0.5),
    "village.siege.lost": dict(speed=0.95, pitch=-20, level=-1.0),
    "village.dead": dict(speed=0.93, pitch=-30, level=-1.5),
    "village.shelter.after": dict(speed=0.95, pitch=-20, level=-1.0),
    "village.born": dict(speed=1.03, pitch=20, level=0.5),
    "village.siege.held": dict(level=0.5),
    "village.fed": dict(speed=0.97, level=0.5),
}

# A take's own speed where its timbre's reading would run over the limit, misread or rush: (key, timbre) -> times the
# timbre's own, instead of its family's
SPEED_FOR = {}

# Text the TTS reads better (the subtitles keep the line as written): {key: {written: said}}
SAY_AS = {}

# A take's phonemes written out in full (its r's sounded), as make_talk_voices.PHONEMES: (key, timbre) or key -> phonemes,
# for the lines the recogniser heard wrong as espeak reads them. Each comment says what Whisper base.en heard without it
PHONEMES = {
    # "wagon" with the American a, as the trade's wagons (else "wagging" or "waggen"), the old man's with a full last
    # vowel and the woman's with a breath after it
    ("talk.village.cart.in_0", "man"): "ðə wˈæɡən kˈeɪm ɪn tədˈeɪ.",
    ("talk.village.cart.late_0", "woman"): "nˈəʊ wˈæɡən, fɔːɹ dˈeɪz nˈaʊ.",
    ("talk.village.cart.late_0", "old"): "nˈoʊ wˈæɡʌn fɔːɹ dˈeɪz nˈaʊ.",
    ("talk.village.pedlar.due_0", "man"): "ðə pˈɛdlɚz djˈuː ˈɑːn ðə ɡɹˈiːn.",  # "pedlar's", not "paddlers"
    ("talk.village.newcomers_0", "man"): "nˈjuː fˈoʊk hav kˈʌm. wɪɹ ɡlˈad ɒv ðˌɛm.",  # "New folk", not "You folk"
    ("talk.village.build.done_1", "man"): "ðats ɐ wˈeɪtʰ ˈɒf.",  # "weight", not "weed", its t sounded
    ("talk.village.build.done_1", "old"): "ðats ɐ wˈeɪtʰ ˈɒf.",  # and not "wait"
    ("talk.village.mended_0", "man"): "ðˈeɪ mˈɛndɪd ðə dˈæmɪdʒ. ɡˈʊd az njˈuː.",  # "They mended", not "The amount of"
}
