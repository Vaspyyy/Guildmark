"""A Lantern for Nell (living-world/stories/design.md section 8.9): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GENTLE, GREETING, GRIEF, MW, MWO, RELIEF, TALK, TIRED, WARM

GROUPS = [
    ("A Lantern for Nell: the old resident", MWO, TALK, {
        # the old resident on the doorstep with an unlit paper lantern (S1 The Count, 10:00 to 17:00 before
        # Lanternrise), to a player who has not said yes yet: the opener; for whom the lantern is and the days to go
        # follow in grey
        "tale.lantern.ask": [
            "Every Lanternrise I let one go. My legs won't carry me.",
            "A lantern for the one I miss. The square's too far.",
        ],
        # the old resident, to anyone passing who has not been asked
        "tale.lantern.passing": [
            "I'll watch the lanterns from my window, I suppose.",
            "These legs don't go to the square anymore.",
        ],
        # the old resident on Lanternrise, 12:00 to 17:30, when the player speaks to them (step 2): they set out for
        # the square
        "tale.lantern.shall": [
            "Shall we go, then? Slowly, mind.",
            "It's a long way. Will you walk with me?",
        ],
        # the old resident, when the player speaks after 17:30 on Lanternrise: too late for the square
        "tale.lantern.late": [
            "Too late for the square now. I'll sit with it.",
            "The lanterns will go without me. It's all right.",
        ],
        # the old resident at the square (step 3), as the lantern is let go with the town's (endings A and B)
        "tale.lantern.release": [
            "Go up now. Go up and be seen.",
            "Rise, little light. Tell them I remember.",
        ],
        # the old resident, to the player after the release (endings A and B)
        "tale.lantern.thanks": [
            "I'd not have managed it. Thank you, dear.",
            "You've a kind heart. They'd have liked you.",
        ],
        # the old resident, when the candle was given but the walk was not done (ending C): the lantern goes up from
        # the door
        "tale.lantern.window": [
            "I'll let it go from the door. It'll find its way.",
            "Thank you for the light. It's enough.",
        ],
        # the old resident, when nothing was done by the close of Lanternrise (ending W): the lantern stays dark
        "tale.lantern.dark": [
            "Not this year. Maybe next, if I'm spared.",
            "I'll just sit with it a while.",
        ],
        # the old resident, when a player who is in asks "How does it stand?"; the days to Lanternrise follow in grey
        "tale.lantern.stand": [
            "Lanternrise is nearly here. Will you walk with me?",
            "The lantern's ready, if you've a candle to spare.",
        ],
    }),
    ("A Lantern for Nell", MW, TALK, {
        # anyone who has heard that someone cannot reach the square for Lanternrise (3.2); who and which street follow
        # in grey
        "tale.lantern.rumour": [
            "Someone can't reach the square for Lanternrise.",
            "Someone on the lane has a lantern and no way there.",
        ],
    }),
    ("A Lantern for Nell: the old resident, short lines", MWO, GREETING, {
        # the old resident, as the player gives a candle (step 1) and it is set in the lantern
        "tale.lantern.candle": [
            "A candle! That's all it wanted.",
            "Bless you. Now it will glow.",
        ],
        # the old resident, as they reach the square on the walk (step 2)
        "tale.lantern.arrive": [
            "Here. Here, at last.",
            "The square! I feared I'd miss it.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.lantern.ask": GENTLE,  # an old tenderness: one lantern a year for the one they miss
    "tale.lantern.passing": GENTLE,  # watching from a window
    "tale.lantern.stand": dict(speed=0.97),  # asking someone to walk with them
    "tale.lantern.thanks": WARM,  # thanks, dear
    "tale.lantern.shall": GENTLE,  # setting out together, slowly
    "tale.lantern.late": TIRED,  # too late for the square
    "tale.lantern.release": dict(speed=0.93),  # the lantern let go: tender
    "tale.lantern.window": GENTLE,  # letting it go from the door
    "tale.lantern.dark": GRIEF,  # no lantern this year
    "tale.lantern.candle": dict(level=1.0),  # a candle, at last
    "tale.lantern.arrive": RELIEF,  # the square at last
}
SPEED_FOR = {}
SAY_AS = {}
PHONEMES = {}
