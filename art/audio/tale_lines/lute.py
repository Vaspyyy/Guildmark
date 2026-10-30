"""The Silent Lute (living-world/stories/design.md section 8.6): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GENTLE, GREETING, GRIEF, MW, TALK, TIRED, WARM

GROUPS = [
    ("The Silent Lute", MW, TALK, {
        # the bard on a bench in the square (S1 The Count, 10:00 to 18:00), to a player who has not said yes yet: the
        # opener; where her lute is follows in grey
        "tale.lute.ask": [
            "I haven't played in days. I haven't the heart.",
            "The square's gone quiet, and that's on me.",
        ],
        # the bard, to anyone passing who has not been asked
        "tale.lute.passing": [
            "No songs today. Forgive me.",
            "Ask me another day.",
        ],
        # anyone who has heard that the bard has pawned her lute and the square has gone quiet (3.2)
        "tale.lute.rumour": [
            "The bard's pawned her lute, you know.",
            "It's been quiet in the square since the bard stopped.",
        ],
        # the keeper, in the inn, when the player asks after the lute (step 1): the loan stands; the sum follows in
        # grey
        "tale.lute.keeper": [
            "The loan stands until it's repaid.",
            "I'm not a charity. I'm an inn.",
        ],
        # the keeper, to a player who says "Half now, half after." (route B); what is paid now and what is owed follow
        # in grey
        "tale.lute.parts": [
            "Half now, half after, and she can play.",
            "Part now, the rest soon. I'll trust you.",
        ],
        # the keeper, to a player the town knows (25 or more) who says "Lend it for one night on my word." (route C)
        "tale.lute.vouch": [
            "Your word's good here. One night, no more.",
            "One night, then it comes back to me.",
        ],
        # the bard, as she has her lute back (routes A to C)
        "tale.lute.thanks": [
            "My lute. My own lute. I could weep.",
            "I'll play for you tonight, and gladly.",
        ],
        # the bard at the concert, in the square at the next dusk (17:00 to 19:30), before she plays
        "tale.lute.intro": [
            "This one's for the whole of the square.",
            "Something old, for something mended.",
        ],
        # the bard, when no way is chosen or nothing is paid (ending W): the lute is sold and the square stays quiet
        "tale.lute.glum": [
            "I'll find another way, in time.",
            "The square will manage without me.",
        ],
        # the bard, when a player who is in asks "How does it stand?"; the sum still owed follows in grey
        "tale.lute.stand": [
            "The lute's the only thing I need.",
            "I'd play again if I could.",
        ],
    }),
    ("The Silent Lute: short lines", MW, GREETING, {
        # the keeper, the morning after a night's loan, as the lute comes back to him (ending C)
        "tale.lute.returned": [
            "Back to me, then. Sleep well.",
            "Thank you for the evening.",
        ],
    }),
    # Villages V12d: the lines Tales T1 typed (art/lang/tales_t1b.json) and nobody had recorded, in T1's words
    ("The Silent Lute: the lines first typed", MW, TALK, {
        # the keeper, to a player the town does not know well enough (under 25) who says "Lend it for one night on my
        # word." (route C); that the keeper lends only on such a word follows in grey
        "tale.lute.refused": [
            "I lend on the word of folk I know.",
            "The town doesn't know you yet. I'm sorry.",
        ],
        # the keeper, to a player who would pay (step 2) and does not carry the Marks; the sum wanted follows in grey
        "tale.lute.short": [
            "That's not enough. Come back when you have it.",
            "Not with that purse. Another day.",
        ],
        # the keeper, as a player pays (step 2): the whole, the half or the rest; what is paid and what is left follow
        # in grey
        "tale.lute.paid": [
            "I'll mark it down. Thank you.",
            "Paid, and noted. That's fair.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.lute.ask": GRIEF,  # no heart to play
    "tale.lute.passing": TIRED,  # turning down a song
    "tale.lute.stand": GENTLE,  # wistful
    "tale.lute.thanks": dict(speed=0.97, level=1.0),  # glad to tears
    "tale.lute.intro": dict(speed=0.97, level=1.5),  # a song announced to the square
    "tale.lute.glum": TIRED,  # giving up on the song
    "tale.lute.returned": WARM,  # the lute handed back
    "tale.lute.refused": dict(speed=0.97, level=-0.5),  # a refusal, not unkind
    "tale.lute.paid": dict(level=0.5),  # business done
}
# Each fix says what Whisper heard without it
SPEED_FOR = {
    ("talk.tale.lute.keeper_1", "woman"): 1.08,  # heard "I'm not a charity I'm an in."
    ("talk.tale.lute.parts_0", "woman"): 1.08,  # heard "Hoff now, Hoff after, and she can play."
    ("talk.tale.lute.keeper_1", "man"): 0.92,  # with its phonemes
}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.lute.keeper_1", "man"): "Im nˌɒtə tʃˈaɹɪti. Im ɐn ˈɪn.",  # heard "I'm not a charity, I'm an N."
    # Villages V12d's: the man says a short vowel a step lower than written ("lend" written "lind"), and the American a
    # of a stressed "and"
    ("talk.tale.lute.refused_0", "man"): "aɪ lˈɪnd ɒnðə wˈɜːd ɒv fˈəʊk aɪ nˈəʊ.",  # heard "I land on the word"
    ("talk.tale.lute.paid_1", "man"): "pˈeɪd, ˈænd nˈəʊtɪd. ðats fˈɛɹ.",  # heard "Paiden noted, that's fair."
}
