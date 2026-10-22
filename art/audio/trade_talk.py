"""The trade's talk families (living-world/trader/design.md section 10, "Talk families"): what townsfolk say of the
roads, the board and the carriers, as families of wordings for make_talk_voices.py's table. A module of its own, as the
build plan (living-world/build-plan.md section 6) asks of every design's voices: it imports nothing from the script, takes
the script's timbre sets and length limit as arguments, and make_talk_voices.py enters it by one block after PHONEMES
(`GROUPS += trade_talk.groups(...)`, and its four tables updated from DELIVERY, SPEED_FOR, SAY_AS and PHONEMES below), and
only once the takes exist: `table` refuses a family with no take.

A family is one meaning said two to four ways, each a whole sentence that is true whenever the family is picked. Whatever
changes (a name, a good, a number, a town, a time) is never voiced: it follows the take as grey text, which the trade's own
talk sources write (say.guildmark.talk.detail.trade.*). So no wording names a town, a good or a day, and none has a .named
text. The takes are talk.<family>_0, _1, ...; the man, the woman and the old man say every family but trade.child, which is
the child's own.

make_trade_voices.py reads this table too, for its listen-through, its recogniser check and its loudness report.
"""

# (group, the group's timbre set, [(family, who says it and when, [wordings])]), in the order the design lists them.
# The timbre sets are "MWO" (man, woman and old) and "CHILD", as make_talk_voices.py names them.
TABLE = [
    ("The trade: what is heard of the other towns", "MWO", [
        # whoever has heard that a town is short of something (the town and the good follow as grey text)
        ("trade.short", "anyone who has heard a town is short", [
            "I hear they're short of something over yonder.",
            "Word is, there's a shortage in the next town.",
            "Folk in the next town are going without, they say.",
        ]),
        # whoever has heard that a town has too much of something
        ("trade.glut", "anyone who has heard a town has too much", [
            "They've more than they need, over in the next town.",
            "There's a glut of something, I heard, and the price is down.",
            "A town over yonder can't give it away, so I'm told.",
        ]),
        # word of a big delivery made elsewhere (by a trader, to a town)
        ("trade.hauled", "anyone who has heard of a big delivery elsewhere", [
            "A trader brought a whole cartload to the next town, they say.",
            "Heard a carrier saved a town's shelves, not long ago.",  # the design says "last week": the word may be a day old
            "Good carrying, I hear, somewhere down the road.",
        ]),
    ]),
    ("The trade: guards and merchants", "MWO", [
        # a guard, of the roads
        ("trade.road", "a guard, of the roads", [
            "Take company on that road if you're laden.",
            "I wouldn't carry much that way without a guard.",
            "Keep to the day, friend. The roads are worse after dark.",
        ]),
        # a merchant, of the Haul Board
        ("trade.board", "a merchant, of the Haul Board", [
            "The board says what's wanted. Read it before you buy.",
            "News from the other towns is on the board. A day or two old, mind.",
            "The board is the place for word of the other towns.",
        ]),
    ]),
    ("The trade: what the town makes of a carrier", "MWO", [
        # to a player who delivered lately
        ("trade.thanks", "to a player who delivered lately", [
            "Thanks to the carriers, we're well stocked.",
            "We'd have run short without a good carrier.",
            "Bless the ones who bring it in from the road.",
            "That was good carrying. We noticed.",  # the design says "last week": the delivery may be yesterday's
        ]),
        # to a trader of repute
        ("trade.fame", "to a trader of repute", [
            "Ah, the carrier! You've done the town a service.",
            "They talk of your carrying, round here.",
            "You're the one who kept us supplied, aren't you?",
        ]),
        # a keeper, to a player who was robbed on the road
        ("trade.robbed", "a keeper, to a player robbed on the road", [
            "Heard you were robbed on the road. Bad business.",
            "They say you were stopped on the road. I'm sorry for it.",
            "Robbed on the road, I hear. It's a hard trade.",
        ]),
        # townsfolk, of a trader robbed on the road
        ("trade.hunted", "townsfolk, of a trader robbed on the road", [
            "They say a trader was held up on the road.",
            "Someone was robbed out on the road, I heard.",
            "The outlaws have been at the carriers again.",
        ]),
    ]),
    ("The trade: the children's", "CHILD", [
        # a child, after a delivery in the square
        ("trade.child", "a child, after a delivery", [
            "The cart came!",  # the design says "The cart man came!": the trader may be a woman, so nobody is called a man
            "Look at all the sacks!",
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


# A family's delivery, as make_talk_voices.DELIVERY: nothing here is said other than plainly. The guard's warning and
# the merchant's tip are said as they come
DELIVERY = {}

# A take's own speed where its timbre's reading would run over the limit, misread or rush (key, timbre) -> times the
# timbre's own, instead of its family's
SPEED_FOR = {}

# Text the TTS reads better (the subtitles keep the line as written): {key: {written: said}}
SAY_AS = {}

# A take's phonemes written out in full (its r's sounded), as make_talk_voices.PHONEMES: (key, timbre) or key -> phonemes,
# for the lines the recogniser heard wrong as espeak reads them. Each comment says what Whisper base.en heard without it
PHONEMES = {}
