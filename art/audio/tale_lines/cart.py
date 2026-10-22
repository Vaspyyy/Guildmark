"""The Handcart by the Wardfire (living-world/stories/design.md section 8.8): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import CHEER, GENTLE, GREETING, MW, TALK, TIRED, WARM

GROUPS = [
    ("The Handcart by the Wardfire", MW, TALK, {
        # the neighbour on her step under the "To let" board (S1 The Count, 10:00 to 18:00), to a player who has not
        # said yes yet: the opener; who left, by which gate and how long ago follow in grey
        "tale.cart.ask": [
            "My neighbours left in lean days. Their cart's stuck out there.",
            "That empty house had a good family. Someone saw their cart.",
        ],
        # the neighbour, to anyone passing who has not been asked
        "tale.cart.passing": [
            "I still look for their lamp each evening.",
            "Nobody has lit that window since they went.",
        ],
        # anyone who has heard that a family is camped by the road with a broken wheel (3.2); who and which road
        # follow in grey
        "tale.cart.rumour": [
            "They say a family's camped by the road, stuck.",
            "A cart with a broken wheel, out past the farms.",
        ],
        # the parent at the camp (step 1), as the player first comes up to them: afraid, and not sure who is there
        "tale.cart.found": [
            "Who's there? Are you from the town?",
            "Keep back! We've nothing left to take.",
        ],
        # the parent (step 1): the wheel is gone and they cannot leave the cart; again if the player says "Come home
        # with me."; the timber needed follows in grey
        "tale.cart.wheel": [
            "The wheel's gone, and all we own is on it.",
            "We can't leave it, and we can't move it.",
        ],
        # the parent, once the cart is mended (step 3): the road is bad after dark; what would make it safe follows in
        # grey
        "tale.cart.night": [
            "The road's bad after dark. We'll go at first light.",
            "Not tonight. We'll walk when the road is safe.",
        ],
        # the guard at the gate, when the player asks the watch to ride with the family (route 4b); the fee follows in
        # grey
        "tale.cart.guard_yes": [
            "Two of us can ride at first light, for a fee.",
            "We'll walk them in. It won't be free.",
        ],
        # the parent, once the road is safe: the kills counted (route 4a) or the watch at the camp (4b)
        "tale.cart.safe": [
            "The road's quiet. We'll set off at first light.",
            "Safe at last. We can go now.",
        ],
        # the parent at first light (step 5), as the camp packs: they send the player ahead to tell the street
        "tale.cart.go_ahead": [
            "Go on ahead, and tell them we're coming.",
            "We'll follow. Tell the street we're coming home.",
        ],
        # the parent at the gate, as the family comes home (endings A to C)
        "tale.cart.thanks": [
            "You brought us home. We'll never forget it.",
            "We were lost, and you found us.",
        ],
        # the parent at the camp (ending W): the wheel was not mended in time, and they go on to the next town
        "tale.cart.moved": [
            "We can't wait. We'll try the next town.",
            "There's nothing here for us. We're going on.",
        ],
        # the neighbour, when a player who is in asks "How does it stand?"; where the camp is and what the cart needs
        # follow in grey
        "tale.cart.stand": [
            "Still no sign of them coming back.",
            "Someone ought to go and see them.",
        ],
    }),
    ("The Handcart by the Wardfire: short lines", MW, GREETING, {
        # the parent, as the player gives the timber (step 2) and they set to work
        "tale.cart.thanks_wood": [
            "Good timber. Give me a moment.",
            "That will do it. Thank you!",
        ],
        # the parent, as the wheel goes on and the cart settles (step 2)
        "tale.cart.mended": [
            "It rolls again! Look at that!",
            "Sound as new. We're in your debt.",
        ],
        # the parent, when the player says "I'll keep the road for you tonight." (route 4a)
        "tale.cart.keep": [
            "Stay near. We'll be glad of it.",
            "Bless you. We'll keep close.",
        ],
        # the guard, once the fee is paid (route 4b)
        "tale.cart.guard_set": [
            "Done. We ride at first light.",
            "Understood. Two of us will go.",
        ],
        # the neighbour at the door, handing the parent a loaf (S4 The Handing, ending A)
        "tale.cart.loaf": [
            "I baked this, in case you came.",
            "Welcome home. You must be starving.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.cart.passing": GENTLE,  # missing the neighbours' lamp in the evening
    "tale.cart.stand": TIRED,  # no sign of them yet
    "tale.cart.thanks": WARM,  # the lost family home
    "tale.cart.found": dict(speed=1.05, level=1.0),  # afraid of whoever comes down the road
    "tale.cart.wheel": TIRED,  # stuck, with all they own
    "tale.cart.safe": WARM,  # safe at last
    "tale.cart.go_ahead": WARM,  # coming home
    "tale.cart.moved": TIRED,  # giving up on the town
    "tale.cart.thanks_wood": dict(level=0.5),  # good timber
    "tale.cart.mended": CHEER,  # it rolls again
    "tale.cart.keep": WARM,  # glad of the company
    "tale.cart.loaf": WARM,  # a loaf baked in case they came
}
SPEED_FOR = {}
SAY_AS = {}
PHONEMES = {}
