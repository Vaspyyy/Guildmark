"""The lines every tale shares (living-world/stories/design.md section 8.0): the asks of 3.5 and 4.7, the
reasons of 5.4, the set pieces of 4.3 and the after-lines of 6.6, recorded once and used by all twelve
tales. All of them are tier 1 (tale_lines.tier), so they are the first takes made.
"""
from . import CALL, CHEER, GENTLE, GREETING, MW, MWO, OLD, RELIEF, TALK, WARM, WORRY

GROUPS = [
    ("Tales: the lines every tale shares: short lines", MW, GREETING, {
        # any cast member, when the player answers an ask with "Not now." (3.5); they ask again after two market days,
        # twice at most
        "tale.ask.later": [
            "Another time, then.",
            "Come back if you change your mind.",
        ],
        # any cast member, to a player who already has two tales on the tracker (4.1/5.1): the third is declined
        "tale.ask.full": [
            "You've your hands full already.",
            "You carry enough already.",
        ],
        # any cast member, to a second player whose choice matches one already made in a CHOOSE step (4.7)
        "tale.ask.decided": [
            "It's been decided.",
            "That's settled, thank you.",
        ],
        # any cast member, when a player in the tale asks "Why?" (5.4): the lead-in to the reasons, newest first,
        # which follow in grey
        "tale.why.lead": [
            "It's like this.",
            "Let me tell it plain.",
        ],
        # a helper who comes up to the caller and asks which way the missing went (S2 The Calling)
        "tale.set.helper": [
            "Which way did they go?",
            "Where was the last place?",
        ],
        # the one who waits and the one come home, as they meet and embrace (S6 The Reunion: the wagon, the child, the
        # brother, the locket; the cart's first words at the door); a child says it in the woman's voice
        "tale.set.home": [
            "You're home. You're home!",
            "Oh, thank goodness. You're safe.",
        ],
        # the one who raises a cup when a tale ends well (S7 The Cheer; the fence's third, with a clay cup at the
        # well)
        "tale.set.toast": [
            "To better days!",
            "To good neighbours!",
        ],
    }),
    ("Tales: the lines every tale shares: the longer lines", MW, TALK, {
        # any cast member, to a second player when another is already seeing the tale through (4.7); who it is and
        # what comes next follow in grey
        "tale.ask.lead": [
            "Someone's already seeing to it. You could help.",
            "A traveller has taken it on. They could use you.",
        ],
        # a passer-by who stops by someone sitting alone with a trouble (S1 The Count, 4.3: the note, the lute, the
        # chair, the wagon; the cart's and the lantern's variants at a doorstep) and says a kind word
        "tale.set.comfort": [
            "Chin up. It'll come right.",
            "These things pass. Hold on.",
        ],
        # a caller at the gate or door, calling the missing by name (S2 The Calling: the child, the brother); the name
        # follows in grey
        "tale.set.call": [
            "Where are you? Answer me!",
            "Come out, it's not funny now!",
        ],
        # a cast member who was helped, to the player who helped, in their first talk after the ending and for 40
        # market days (6.6); what changed follows in grey
        "tale.after.thanks": [
            "I haven't forgotten what you did.",
            "You're always welcome at our door.",
        ],
        # anyone who has heard of a good ending, when asked what they think of it, for 40 market days (6.6); the
        # ending's sentence follows in grey
        "tale.after.glad": [
            "Nice to hear something go right.",
            "Glad that's mended. A good thing.",
        ],
        # anyone who has heard of an ill ending, when asked, for 40 market days (6.6); the ending's sentence follows
        # in grey
        "tale.after.sorry": [
            "A sad business. I wish it had gone better.",
            "Nobody's fault, but it's a pity.",
        ],
    }),
    ("Tales: the lines every tale shares: the old folk walking", MWO, GREETING, {
        # a walker the player leads, when the player gets more than 24 blocks ahead (S8 The Slow Walk: the lantern's
        # old resident, the brother led up from the mine)
        "tale.set.wait": [
            "Don't leave me behind, dear!",
            "Slowly, now. These legs!",
        ],
        # a walker the player leads, resting on a bench or after thirty blocks and catching their breath (S8 The Slow
        # Walk)
        "tale.set.rest": [
            "A moment, dear. These legs.",
            "Let me catch my breath.",
        ],
    }),
    ("Tales: the lines every tale shares: the elders", OLD, TALK, {
        # the elders, from the chronicle and for good (6.6); the chronicle line and its age follow in grey
        "tale.after.old": [
            "I remember that. It was a hard season.",
            "That's a story the town still tells.",
        ],
    }),
    # Villages V12d: the talk's general thanks at the yes, typed in Tales C (art/lang/tales_c.json) and recorded here in the
    # same three wordings
    ("Tales: the lines every tale shares: the yes", MWO, TALK, {
        # the one who asks, the moment a player says yes (TaleScript.GENERAL_YES): every tale's asker but the note's and
        # the informer's, which have their own (the lantern's old asker among them); nothing follows in grey
        "tale.yes": [
            "Thank you. That means a great deal.",
            "I'm glad of it. Truly.",
            "Bless you for it.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.why.lead": dict(speed=0.95),  # a breath taken before the telling
    "tale.set.helper": WORRY,  # asking which way, in a hurry
    "tale.set.home": RELIEF,  # the breath let go as the missing come home
    "tale.set.toast": CHEER,  # a cup raised
    "tale.set.comfort": GENTLE,  # the kind word to someone sitting alone: slower, softer
    "tale.set.call": CALL,  # calling the missing from the gate, over a distance
    "tale.after.thanks": WARM,  # the thanks in the first talk after
    "tale.after.glad": dict(level=0.5),  # pleased to hear it
    "tale.after.sorry": dict(speed=0.92, pitch=-20, level=-1.5),  # sorry to hear it: slower, lower, softer
    "tale.set.wait": dict(speed=1.02, level=1.5),  # an old walker calling after the one who leads them
    "tale.set.rest": dict(speed=0.93, level=-0.5),  # an old walker out of breath
    "tale.after.old": dict(speed=0.96, pitch=-10, level=-0.5),  # an elder remembering
    "tale.yes": WARM,  # glad someone will help
}
SPEED_FOR = {}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    "talk.tale.ask.decided_0": "ɪts bɪn dɪsˈaɪdɪd.",  # "It's been", not "It's being"
    "talk.tale.ask.decided_1": "ðˈats, sˈɛtəld, θˈaŋk juː.",  # "That's settled", not "That settled"
    ("talk.tale.set.rest_0", "man"): "ɐ mˈəʊmənt. dˈɪɹ. ðiːz lˈɛɡz.",  # "A moment, dear", not "A moment to hear"
}
