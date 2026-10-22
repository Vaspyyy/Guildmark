"""The Empty Chair (living-world/stories/design.md section 8.2): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GENTLE, GREETING, GRIEF, MW, TALK

GROUPS = [
    ("The Empty Chair", MW, TALK, {
        # the mourner on her doorstep (S1 The Count, seated, 17:00 to 20:00), to a player who has not said yes yet:
        # the opener; who died and when follow in grey
        "tale.chair.ask": [
            "There's an empty chair at my table.",
            "I keep setting two bowls.",
        ],
        # the mourner, to anyone passing who has not been asked
        "tale.chair.passing": [
            "It's very quiet in the house.",
            "The days are long, now.",
        ],
        # anyone who has heard that the town has not marked a death yet (3.2); who died and when follow in grey
        "tale.chair.rumour": [
            "The town hasn't marked it yet, you know.",
            "They say nobody's laid a stone.",
        ],
        # the priest, to a player who asks what a laying needs: what is proper; choosing the place with the mourner
        # follows in grey
        "tale.chair.priest": [
            "A stone, three candles, and three who remember.",
            "What's proper is a stone and a few kind words.",
        ],
        # a friend of the dead, in their own day (step 1), the first of the three words: a kindness remembered; who
        # they were and how the friend knew them follow in grey
        "tale.chair.word_a": [
            "Always a kind word for everyone.",
            "You never saw them without a smile.",
        ],
        # a friend of the dead, in their own day (step 1), the second word: a life's work and giving; the friend's tie
        # follows in grey
        "tale.chair.word_b": [
            "Never a day without work done well.",
            "They gave more than they ever took.",
        ],
        # a friend of the dead, in their own day (step 1), the third word: the laughter they will be missed for; the
        # friend's tie follows in grey
        "tale.chair.word_c": [
            "Could make us laugh at the worst of times.",
            "They'll be missed at every table.",
        ],
        # the priest in the scene (S5 The Laying, at dusk), as the stone is laid; the dead's name follows in grey
        "tale.chair.laying": [
            "We lay one of our own to rest.",
            "Light and peace to one who is gone.",
        ],
        # the mourner, to the player after the laying (endings A to C)
        "tale.chair.thanks": [
            "The chair isn't so empty now.",
            "Thank you for remembering them with me.",
        ],
        # the mourner at the gate, as the household leaves the town (ending W)
        "tale.chair.leave": [
            "There's nothing here for us now.",
            "We'll go where it doesn't hurt.",
        ],
        # the mourner, when a player who is in asks "How does it stand?"; the words, candles and place so far follow
        # in grey
        "tale.chair.stand": [
            "Three words, three candles, and a place.",
            "I only want it done well.",
        ],
    }),
    ("The Empty Chair: short lines", MW, GREETING, {
        # the mourner, as the player puts candles into her hands (step 2); the count so far follows in grey
        "tale.chair.candles": [
            "A candle for each who will come.",
            "Three lights, then. That's right.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.chair.ask": GRIEF,  # the empty chair: a held-back sorrow
    "tale.chair.passing": GRIEF,  # the same, said to the air
    "tale.chair.rumour": GENTLE,  # the town's talk of it, lowered
    "tale.chair.stand": GENTLE,  # solemn, wanting it done well
    "tale.chair.thanks": GENTLE,  # a quiet thanks for remembering
    "tale.chair.priest": dict(speed=0.95),  # what is proper, said evenly
    "tale.chair.word_a": GENTLE,  # a kind word for the dead
    "tale.chair.word_b": GENTLE,
    "tale.chair.word_c": GENTLE,
    "tale.chair.laying": dict(speed=0.9, pitch=-15, level=-0.5),  # the stone laid: solemn
    "tale.chair.leave": GRIEF,  # no heart to stay
    "tale.chair.candles": GENTLE,  # three lights
}
SPEED_FOR = {}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.chair.ask_1", "man"): "aɪ kˈiːp sˈɛtɪŋ tˈuː bˈoʊʊlz.",  # "bowls", not "balls"
    ("talk.tale.chair.ask_1", "woman"): "aɪ kˈiːp sˈɛtɪŋ tˈuː bˈoʊlz.",  # "bowls", not "balls"
    ("talk.tale.chair.stand_0", "man"): "θɹˈiː wˈɜːdz, θɹˈiː kˈandəlz, ˈand ɐ plˈeɪs.",  # "and a place", not "in a place"
}
