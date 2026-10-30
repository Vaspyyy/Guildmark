"""The Bell Moot (living-world/stories/design.md section 8.7): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GREETING, MW, TALK, WARM

GROUPS = [
    ("The Bell Moot", MW, TALK, {
        # the crier, who has rung the bell and put the question to the square (S3 crowd variant, from 11:00), to a
        # player who has not said yes yet: the opener; the works and its days follow in grey
        "tale.moot.ask": [
            "The town can't agree, and it will cost us all.",
            "The bell is rung. Say what you will.",
        ],
        # `for`, in the half ring in the square (step 1): why the town should build; whose reason it is follows in
        # grey
        "tale.moot.for": [
            "We need it. Every day we wait costs us.",
            "Put it up, and be glad of it.",
        ],
        # `against`, in the half ring (step 1): why the town cannot spend what it has not got; whose reason it is
        # follows in grey
        "tale.moot.against": [
            "We can't spend what we haven't got.",
            "Who pays for it? Not me.",
        ],
        # the crier, when the word that carried the room was spoken (ending A): the town gives; the gift follows in
        # grey
        "tale.moot.good": [
            "The moot has heard. The town will give.",
            "It is decided. The town will help build it.",
        ],
        # the crier, when another good word was spoken (ending B): the town gives a part; the gift follows in grey
        "tale.moot.half": [
            "Half, then. And may it be enough.",
            "The town gives a part. The rest is for willing hands.",
        ],
        # the crier, when the word spoken did not bear on the works (ending C): the question stays
        "tale.moot.deferred": [
            "No one carries the room. We'll meet again.",
            "Not today. The question stays.",
        ],
        # the crier, when nobody spoke by the close of the second day (ending W): the moot ends in a shouting match
        "tale.moot.broke": [
            "Enough! The moot is ended, and badly.",
            "This helps no one. Go home.",
        ],
        # the crier, when a player who is in asks "How does it stand?"; when the moot meets follows in grey
        "tale.moot.stand": [
            "The moot meets soon. Be ready.",
            "Think what is true, and say it.",
        ],
    }),
    ("The Bell Moot: short lines", MW, GREETING, {
        # the crier at noon on the second day, after the news (step 2), calling for someone to speak
        "tale.moot.call": [
            "Does no one speak?",
            "The room waits on someone.",
        ],
        # the crier, to the player who spoke or pledged, once the verdict is given
        "tale.moot.thanks": [
            "Well said. The town is grateful.",
            "That was needed. Thank you.",
        ],
    }),
    # Villages V12d: the answer Tales T4 gave the stand's words, with a line of its own
    ("The Bell Moot: the pledge", MW, TALK, {
        # the crier, when a player says "I'll put in <the pledge> first." (step 2) and does not carry the Marks; the sum
        # follows in grey
        "tale.moot.purse": [
            "Bring the coin, and pledge it then.",
            "A pledge wants the coin in hand.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.moot.ask": dict(level=0.5),  # a town's trouble, said to be heard
    "tale.moot.thanks": WARM,  # the town's thanks
    "tale.moot.for": dict(level=1.5),  # a speaker, projecting to the room
    "tale.moot.against": dict(level=1.5),
    "tale.moot.good": dict(speed=0.97, level=1.0),  # the chair's decision, formal
    "tale.moot.half": dict(speed=0.97, level=1.0),
    "tale.moot.deferred": dict(speed=0.97, level=0.5),  # the chair, tired of it
    "tale.moot.broke": dict(speed=1.04, level=2.0),  # the chair, angry
    "tale.moot.call": dict(level=1.5),  # the chair, asking the room
    "tale.moot.purse": dict(speed=0.97),  # a rule kindly told
}
SPEED_FOR = {
    ("talk.tale.moot.ask_1", "man"): 0.95,  # with its phonemes, below
    ("talk.tale.moot.for_0", "man"): 0.92,  # with its phonemes
    ("talk.tale.moot.good_0", "man"): 0.892,  # heard "The mood has hurt the town will give."
}
SAY_AS = {}
# Each fix says what Whisper heard without it. The man's "rung" is still heard as "wrong", by every reading tried.
PHONEMES = {
    "talk.tale.moot.ask_0": "ðə tˈaʊn kˈant, ɐɡɹˈiː, and ɪt wɪl kˈɒst ˌʌs ˈɔːl.",  # "can't agree", not "can agree"
    ("talk.tale.moot.ask_1", "man"): "ðə bˈɛl ɪz ɹˈʌŋ. sˈA wɒt juː wˈɪl.",  # "Say", not "See": the one-token A of eɪ
    ("talk.tale.moot.ask_1", "woman"): "ðə bˈɛl ɪz ɹˈʌŋɡ. sˈeɪ wɒt juː wˈɪl.",  # "rung", not "wrong"
    ("talk.tale.moot.stand_0", "woman"): "ðə mˈuːt, mˈiːts sˈuːn. bˈiː ɹˈɛdi.",  # "meets", not "mates"
    ("talk.tale.moot.broke_0", "woman"): "ɪnˈʌf! ðə mˈuːt ˈɪz ˈɛndɪd, and bˈadli.",  # heard "Enough the mood has ended and badly."
    ("talk.tale.moot.broke_0", "man"): "ɪnˈʌf! ðə mˈuːt ˈɪz ˈɛndɪd, and bˈadli.",  # heard "Enough the mood has ended and badly."
    ("talk.tale.moot.for_0", "man"): "wiː nˈiːd ɪt. ˈɛvɹɪ dˈA wiː wˈAt kˈɒsts ˌʌs.",  # heard "We need it every day we wait cost us."
    ("talk.tale.moot.half_0", "man"): "hˈæf, ðˈɛn. and mˈA ˈɪt biː ɪnˈʌf.",  # heard "Hosten and may be enough."
}
