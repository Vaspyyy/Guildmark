"""The Locket (living-world/stories/design.md section 8.11): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GRIEF, MW, TALK, TIRED, WARM

GROUPS = [
    ("The Locket", MW, TALK, {
        # the owner on her doorstep (S1 The Count, 10:00 to 18:00), to a player who has not said yes yet: the opener;
        # when the locket was taken and the three who were about follow in grey
        "tale.locket.ask": [
            "Something small and dear is gone from my sill.",
            "My locket's gone, and I can't stop looking.",
        ],
        # the owner, to anyone passing who has not been asked
        "tale.locket.passing": [
            "I keep reaching for my throat.",
            "It held a curl of hair. Nothing more.",
        ],
        # anyone who has heard that something was taken from a windowsill (3.2); whose and from which lane follow in
        # grey
        "tale.locket.rumour": [
            "They say something was taken from a windowsill.",
            "Light fingers on the lane, they say.",
        ],
        # the first witness, a neighbour (step 1): which way someone slipped off at dusk; the side follows in grey
        # (the first of the three facts)
        "tale.locket.sight_side": [
            "I saw someone slip away down the lane at dusk.",
            "Someone hurried off at dusk. I saw which way.",
        ],
        # the second witness, a neighbour (step 1): what the stranger wore; the piece follows in grey (the second
        # fact)
        "tale.locket.sight_piece": [
            "I never saw a face, only what they wore.",
            "I couldn't tell you who. But I marked their dress.",
        ],
        # the third witness, the baker if the town has one (step 1): a child waiting at the corner (the third fact, in
        # grey)
        "tale.locket.sight_child": [
            "A little one waited at the corner, watching.",
            "There was a child by the corner, waiting.",
        ],
        # each of the three who were about, when the player asks "Where were you at dusk?" (step 2): the same family
        # for all three, so it tells nothing; their own three facts follow in grey
        "tale.locket.alibi": [
            "I was about. Is there harm in that?",
            "Ask anyone. I was on the lane, that's all.",
        ],
        # a suspect who is not the culprit, when the player asks for the locket back (the player has wronged them)
        "tale.locket.wrongly": [
            "How dare you! I've taken nothing from anyone.",
            "You have the wrong door. Shame on you.",
        ],
        # the owner, to a Starwatch member who says "Let me look at the sill."; the three facts follow in grey
        "tale.locket.read": [
            "You can see that, just from the sill?",
            "The stars told you? Truly?",
        ],
        # the culprit, when the player holding two words or more asks them to give back what they took
        "tale.locket.confess": [
            "It was me. There was nothing to eat.",
            "I took it. We were so hungry.",
        ],
        # the culprit, to a Hearthsworn member who says "In the Order's name, give it back."
        "tale.locket.law": [
            "The Order's law. I can't stand against it.",
            "Take it. I'll not stand against the Order.",
        ],
        # the culprit, to a Quiet Hand member who says "A quiet word."
        "tale.locket.quiet": [
            "Take it. Say nothing, I beg you.",
            "Give it back for me. Don't name me.",
        ],
        # the culprit, when the player gives three loaves and tells him to take it back himself (the way A)
        "tale.locket.bread": [
            "You'd feed me after that? I'll put it right.",
            "Bless you. I'll take it back myself.",
        ],
        # the owner, as the locket is given back to her (The Return, The Clasp, The Naming: endings A to C)
        "tale.locket.returned": [
            "My locket. Oh, you're home again.",
            "I thought it gone for good. Thank you.",
        ],
        # the owner, to the culprit at her doorstep (The Return, ending A), as she embraces him
        "tale.locket.forgive": [
            "Take a loaf too. We have all been hungry.",
            "You only had to ask. Come, sit.",
        ],
        # the owner, when she is told it was found on the lane (ending B) or finds it on her sill in the morning
        # (ending C)
        "tale.locket.found": [
            "Found on the lane? Then someone was kind.",
            "However it came back, I thank you.",
        ],
        # the owner at the well (The Naming, ending C), as the culprit holds the locket out before the watchers
        "tale.locket.shame": [
            "I never wanted it done in the street.",
            "It's back, but I'm sorry for the shame.",
        ],
        # the owner, when no thief is found in time (ending W): she has stopped looking
        "tale.locket.lost": [
            "It's gone, and I've stopped looking now.",
            "Some things don't come back. I know.",
        ],
        # the owner, when a player who is in asks "How does it stand?"; the words heard so far follow in grey
        "tale.locket.stand": [
            "Ask the three who saw something. Please.",
            "I only want it back. No one hurt.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.locket.ask": TIRED,  # something small and dear gone, and no peace
    "tale.locket.passing": TIRED,  # a hand reaching for what is not there
    "tale.locket.stand": dict(speed=0.97),  # earnest: only wanting it back
    "tale.locket.alibi": dict(speed=1.03, level=0.5),  # defensive
    "tale.locket.wrongly": dict(speed=1.05, level=2.0),  # indignant
    "tale.locket.read": dict(level=0.5),  # amazed
    "tale.locket.confess": dict(speed=0.93, pitch=-15, level=-1.5),  # ashamed, and hungry
    "tale.locket.law": TIRED,  # giving way to the Order
    "tale.locket.quiet": dict(speed=0.96, level=-2.0),  # begging not to be named
    "tale.locket.bread": WARM,  # fed instead of punished
    "tale.locket.returned": dict(speed=0.94, level=0.5),  # the locket home
    "tale.locket.forgive": WARM,  # a loaf for the thief
    "tale.locket.found": WARM,  # thanks, however it came back
    "tale.locket.shame": TIRED,  # sorry for the shame
    "tale.locket.lost": GRIEF,  # given up for lost
}
SPEED_FOR = {
    ("talk.tale.locket.stand_1", "man"): 1.1,  # "No one hurt": slower, the last "t" came out as "hurts"
    ("talk.tale.locket.returned_0", "woman"): 0.865,  # with its phonemes
}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    "talk.tale.locket.ask_0": "sˈʌmθɪŋ smˈɔːl and dˈiəɹ ɪz ɡɒn fɹˈɒm maɪ sˈɪl.",  # "from my sill", not "for my sill"
    ("talk.tale.locket.ask_1", "man"): "maɪ lˈɑːkɪt ɪz ɡˈɒn, and aɪ kˈant stˈɒp lˈʊkɪŋ.",  # "locket", not "luck"
    ("talk.tale.locket.ask_1", "woman"): "maɪ lˈɒkɪt ɪz ɡˈɒn, and aɪ kˈant stˈɒp lˈʊkɪŋ.",  # "locket", not "lock it's"
    ("talk.tale.locket.stand_1", "man"): "aɪ ˈəʊnli wˈɒnt ɪt bˈak. nˈəʊ wˈʌn hˈɜːt.",
    ("talk.tale.locket.sight_side_1", "man"): "sˈʌmwɒn hˈʌɹid ˈɒf ˈat dˈʌsk. aɪ sˈɔː wˌɪtʃ wˈeɪ.",  # heard "Someone hurried off a desk, I saw which way."
    ("talk.tale.locket.quiet_0", "man"): "tˈeɪk ɪt. sˈA nˈʌθɪŋ, aɪ bˈɛɡ juː.",  # heard "Take it, see nothing I beg you."
    ("talk.tale.locket.returned_0", "woman"): "mI lˈɒkɪt. ˈQ, jɔːɹ hˈQm ɐɡˈɛn.",  # heard "My luck at \"oh, you're home again.\""
}
