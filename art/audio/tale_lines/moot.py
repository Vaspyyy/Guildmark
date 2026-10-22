"""The Bell Moot (living-world/stories/design.md section 8.7): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GREETING, MW, TALK

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
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {}
SPEED_FOR = {}
SAY_AS = {}
PHONEMES = {}
