"""The Fourth Wagon (living-world/stories/design.md section 8.3): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GENTLE, GREETING, MW, TALK, TIRED, WARM, WORRY

GROUPS = [
    ("The Fourth Wagon", MW, TALK, {
        # `kin` at the gate (S1 The Count, 15:00 to 20:00), to a player who has not said yes yet: the opener; who was
        # taken, and by which band, follow in grey
        "tale.wagon.ask": [
            "They took the wagon, and my own went with it.",
            "The wagon came back without the second hand.",
        ],
        # `kin`, to anyone passing who has not been asked
        "tale.wagon.passing": [
            "I can't sleep. I keep watching the gate.",
            "Any word of the road? Any word at all?",
        ],
        # anyone who has heard that the band took someone with the wagon (3.2); the name and the road follow in grey
        "tale.wagon.rumour": [
            "They say the band took someone with the wagon.",
            "Someone from the town went with that wagon.",
        ],
        # the driver, while he sits at the gate (step 1): the band holds the hand and wants a price; the sum and the
        # hour follow in grey
        "tale.wagon.driver_hand": [
            "They took one alive. A rider came about a price.",
            "They kept the second hand. They want paying.",
        ],
        # the captain, to a player the town knows (25 or more) who says "Send the watch." (step 2, route C): the watch
        # rides at the next close
        "tale.wagon.captain_yes": [
            "I'll ride at dawn, on your word.",
            "Show me where, and I'll take the watch.",
        ],
        # `kin` at the gate, as the hand is brought home (S6 The Reunion, endings A to C)
        "tale.wagon.thanks": [
            "You brought them back. I'll not forget it.",
            "Whatever you ask of me, ask.",
        ],
        # `kin` at the cairn (ending W, S5 The Laying at the wreck's place); the hand's name follows in grey
        "tale.wagon.cairn": [
            "At least there's a place to come to.",
            "They'll be remembered here, whatever else.",
        ],
        # `kin`, when a player who is in asks "How does it stand?"; where the hand is held and what is left to do
        # follow in grey
        "tale.wagon.stand": [
            "Still no word of them.",
            "The camp's known. The rest is up to us.",
        ],
    }),
    ("The Fourth Wagon: short lines", MW, GREETING, {
        # the captain, to a player the town does not yet know well enough who says "Send the watch."
        "tale.wagon.captain_no": [
            "Not without a name I trust.",
            "Prove yourself first.",
        ],
        # the go-between, a hooded rider at the parley spot (06:00 to 20:00), as the player comes with the price
        # (route B); the sum follows in grey
        "tale.wagon.parley": [
            "Bring the coin and take your own.",
            "The price is set. Pay, and walk.",
        ],
        # the go-between, once the price is given: he lets the hand go
        "tale.wagon.released": [
            "Take them. We're done with each other.",
            "Walk them out. Don't look back.",
        ],
        # the hand, freed at the camp (route A) or let go by the go-between (route B), as the player first reaches
        # them
        "tale.wagon.found": [
            "You came. I hoped someone would.",
            "My leg. Mind my leg.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.wagon.ask": WORRY,  # the wagon taken, the hand gone with it
    "tale.wagon.passing": WORRY,  # sleepless, watching the gate
    "tale.wagon.stand": TIRED,  # no word yet: worn out with waiting
    "tale.wagon.thanks": WARM,  # the family brought back
    "tale.wagon.captain_yes": dict(level=0.5),  # a captain's word, firm
    "tale.wagon.cairn": GENTLE,  # a place to come to
    "tale.wagon.parley": dict(speed=0.96, pitch=-20),  # the band's price, hard
    "tale.wagon.released": dict(speed=0.96, pitch=-20),  # the band letting go, with no love
    "tale.wagon.found": GENTLE,  # weak and glad
}
# Each fix says what Whisper heard without it
SPEED_FOR = {
    ("talk.tale.wagon.driver_hand_1", "man"): 0.96,  # with its phonemes
    ("talk.tale.wagon.found_1", "man"): 0.865,  # heard "My leg, mine, my leg."
    ("talk.tale.wagon.found_1", "woman"): 0.865,  # heard "My leg, mine my leg."
}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.wagon.ask_0", "man"): "ðˈeɪ tˈʊk ðə wˈaɡən, and maɪ ˈəʊn wˈɛnt wɪð ɪt.",  # "They took", not "He took"
    ("talk.tale.wagon.driver_hand_1", "man"): "ðˈeɪ kˈɛpt ðə sˈɛkənd hˈand. ðˈeɪ wˈɒnt pˈeɪɪŋ.",  # heard "They kept the second hand they want peeing."
    ("talk.tale.wagon.captain_yes_0", "woman"): "aɪl ɹˈaɪd ˈat dˈɔːn, ˌɒn jɔːɹ wˈɜːd.",  # heard "I'll write it dawn on your word."
    ("talk.tale.wagon.parley_1", "woman"): "ðə pɹˈaɪs ˈɪz sˈɛt. pˈeɪ, and wˈɔːk.",  # heard "The prices set pay and walk."
}
