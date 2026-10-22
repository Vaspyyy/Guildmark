"""The Fence Between (living-world/stories/design.md section 8.5): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import CALL, GENTLE, GREETING, MW, TALK, TIRED, WARM

GROUPS = [
    ("The Fence Between: short lines", MW, GREETING, {
        # `a` in the lane (S3 The Quarrel, 11:00 to 15:00), shouting at `b`; the thing they quarrel about follows in
        # grey
        "tale.fence.shout_a": [
            "You know very well what you did!",
            "I'll not be told by you!",
        ],
        # `b` in the lane (S3 The Quarrel), answering `a`
        "tale.fence.shout_b": [
            "I did nothing of the sort!",
            "Look to your own door first!",
        ],
        # the third neighbour in the lane (S3 The Quarrel), hushing the pair
        "tale.fence.third": [
            "Enough, the pair of you!",
            "Not in the lane, please.",
        ],
        # `a` or `b`, having heard the player out (step 1): they would sit with the other if the other came first
        "tale.fence.willing": [
            "I'd sit with them if they came.",
            "Perhaps. If it's not me who asks.",
        ],
        # either, when the player says "At the well, at dusk. They'll be there." (step 1): the side agrees
        "tale.fence.arranged": [
            "At the well at dusk. I'll come.",
            "Dusk, then. I'll bring my own cup.",
        ],
        # `a` at the well at dusk (The Mending, step 3), handing over the loaf
        "tale.fence.sorry": [
            "I was wrong. I'm sorry.",
            "Let's not speak of it again.",
        ],
        # `b` at the well (The Mending), answering
        "tale.fence.same": [
            "And I. Sit, the bread's getting cold.",
            "No harm done. Eat.",
        ],
        # the one left of the pair, once the other has gone or died (the tale ends C)
        "tale.fence.glum": [
            "Some things aren't worth mending.",
            "We'll manage without each other.",
        ],
    }),
    ("The Fence Between", MW, TALK, {
        # `a`, who lingers after the quarrel and speaks to the player: the opener; both names and the thing follow in
        # grey
        "tale.fence.ask": [
            "My neighbour and I are not speaking. It's foolish.",
            "I've said things I can't unsay.",
        ],
        # anyone who has heard that two neighbours on the lane have stopped speaking (3.2); who they are follows in
        # grey
        "tale.fence.rumour": [
            "Two neighbours on the lane have stopped speaking.",
            "There's a quarrel on the lane. A small thing.",
        ],
        # `a`, in the player's talk (step 1): why they quarrel, from their side; what they say of the thing follows in
        # grey
        "tale.fence.side_a": [
            "It was a small thing at first. Then it wasn't.",
            "They know what they did. I'll not say it twice.",
        ],
        # `b`, in the player's talk (step 1): why they quarrel, from the other side; what they say of the thing
        # follows in grey
        "tale.fence.side_b": [
            "I'm not the one who started this.",
            "Ask them what they said first.",
        ],
        # `a`, when a player who is in asks "How does it stand?"; the days without a word follow in grey
        "tale.fence.stand": [
            "Not a word since.",
            "Maybe if someone asked.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.fence.ask": TIRED,  # ashamed of a quarrel
    "tale.fence.stand": TIRED,  # a few flat words about the silence
    "tale.fence.shout_a": CALL,  # the quarrel aloud
    "tale.fence.shout_b": CALL,
    "tale.fence.third": dict(level=1.5),  # a third party stepping between them
    "tale.fence.willing": TIRED,  # not quite ready
    "tale.fence.sorry": GENTLE,  # an apology
    "tale.fence.same": WARM,  # and the same back
    "tale.fence.glum": TIRED,  # giving the friendship up
}
# Each fix says what Whisper heard without it
SPEED_FOR = {
    ("talk.tale.fence.arranged_0", "man"): 0.92,  # heard "At the welled desk, I'll come."
}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.fence.ask_1", "woman"): "aɪv sˈɛd θˈɪŋz aɪ kˈant ˌʌnsˈeɪ.",  # "can't unsay", not "can't say"
    ("talk.tale.fence.shout_a_1", "man"): "ˈaɪl nˌɒt biː tˈəʊld baɪ juː!",  # heard "I'm not be told by you."
    ("talk.tale.fence.third_1", "man"): "nˈɒt ɪnðə lˈeɪn, plˈiːz.",  # heard "None in the lane please."
}
