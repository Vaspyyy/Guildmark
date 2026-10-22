"""Where the Little One Went (living-world/stories/design.md section 8.4): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import CHILD, GREETING, MW, PLEA, TALK, TIRED

GROUPS = [
    ("Where the Little One Went", MW, TALK, {
        # the parent at the gate (S2 The Calling, 15:30 to 19:00; waiting there after 19:00), to a player who has not
        # said yes yet: the opener; the child and the gate follow in grey
        "tale.child.ask": [
            "My little one's not in the house or the yard.",
            "My child's gone. Out of the gate, they say.",
        ],
        # the parent, to anyone passing who has not been asked
        "tale.child.passing": [
            "Have you seen a small one with a bundle?",
            "They've run off, and I'm sick with worry.",
        ],
        # anyone who has heard that a child has gone off down the road (3.2); the child and the gate follow in grey
        "tale.child.rumour": [
            "The new family's little one has gone missing.",
            "They say a child's gone off down the road.",
        ],
        # the guard at the gate (step 1), when the player asks: a small one went out toward the old road at three;
        # which gate and which road follow in grey
        "tale.child.guard": [
            "A small one went out with a bundle, down the road.",
            "I saw one go out. I thought a parent was behind.",
        ],
        # the parent, to the player at the reunion (endings A to C)
        "tale.child.thanks": [
            "You found my little one. I'll never forget it.",
            "We came here for a better life. Now I believe it.",
        ],
        # the parent at the gate, as the household goes back down the road (ending W)
        "tale.child.leave": [
            "This town was a mistake. We're going back.",
            "We'll try the old road. It was kinder.",
        ],
        # the parent, when a player who is in asks "How does it stand?"; when the child went and by which gate follow
        # in grey
        "tale.child.stand": [
            "Please, please find them.",
            "Out on the road, in the dark.",
        ],
    }),
    ("Where the Little One Went: the child's own", CHILD, GREETING, {
        # the child at the edge of the clearing (step 2), when the player comes within six blocks: why they went; each
        # wording is a feeling, true whichever is picked
        "tale.child.found": [
            "I want to go home.",
            "Nobody here knows my name.",
            "I miss my old house.",
        ],
        # the child, when the player says "Your family is calling you."
        "tale.child.agree": [
            "Will they be cross?",
            "Is it very far back?",
            "Can I hold your hand?",
        ],
        # the child on the walk home in the dark, when a hostile mob comes within eight blocks (at most once in twenty
        # seconds)
        "tale.child.scared": [
            "Something's out there!",
            "Please go faster!",
        ],
        # the child at the reunion in the last light (S6 The Reunion, endings A and B), holding out the wooden horse
        "tale.child.gift": [
            "Here. It's for luck.",
            "You can keep my horse.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.child.ask": dict(speed=1.06, level=1.0),  # a parent in distress, the words tumbling
    "tale.child.passing": dict(speed=1.06, level=1.0),  # the same, asking of whoever passes
    "tale.child.stand": PLEA,  # begging
    "tale.child.thanks": dict(speed=0.93, level=0.5),  # overcome
    "tale.child.leave": TIRED,  # giving the town up
    "tale.child.found": dict(speed=0.95, level=-0.5),  # a small voice, homesick
    "tale.child.agree": dict(speed=0.95, level=-1.0),  # timid
    "tale.child.scared": dict(speed=1.06, level=1.5),  # frightened, hurrying
}
# Each fix says what Whisper heard without it
SPEED_FOR = {
    ("talk.tale.child.guard_1", "woman"): 0.92,  # heard "I saw when go out, I thought a parent was behind."
}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.child.rumour_1", "man"): "ðeɪ sˈA ɐ tʃˈaɪldz ɡɒn ˈɒf dˌaʊn ðə ɹˈəʊd.",  # heard "They see a child's gone off down the road."
}
