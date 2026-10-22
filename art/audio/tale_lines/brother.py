"""The Brother Under the Hill (living-world/stories/design.md section 8.12): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GENTLE, GREETING, GRIEF, MW, RELIEF, TALK, TIRED, WORRY

GROUPS = [
    ("The Brother Under the Hill", MW, TALK, {
        # the smith at the forge by day (S1) or calling at the gate at dusk (S2 The Calling), to a player who has not
        # said yes yet: the opener; who went down the old mine and when follow in grey
        "tale.brother.ask": [
            "My own went down for iron. Two nights now.",
            "They went to the old mine. No word since.",
        ],
        # the smith, to anyone passing who has not been asked
        "tale.brother.passing": [
            "The forge feels empty. No word at all.",
            "I keep listening for the door.",
        ],
        # anyone who has heard that a delver is missing in the old workings (3.2); who and where the mine lies follow
        # in grey
        "tale.brother.rumour": [
            "Someone went down the old mine. No sign since.",
            "They say a delver's missing in the workings.",
        ],
        # the captain at the gate (step 1), when the player asks: they were seen going out at dusk toward the old
        # workings; the mine going on the map follows in grey
        "tale.brother.captain": [
            "They went out at dusk with a lantern and sack.",
            "I saw them go, toward the old workings.",
        ],
        # the smith (step 2), once the player has heard the captain: the three ways to bring the brother back
        "tale.brother.choose": [
            "I can't leave the forge. What would you do?",
            "Go down, send others, or light the way.",
        ],
        # the delver at the tavern's door (route B), to a player who asks for a party; the sum follows in grey
        "tale.brother.hire": [
            "Three of us, with torches and rope. For a fee.",
            "We go down at the close. We don't come cheap.",
        ],
        # the smith, when the player says "I'll light the old fire." (route C)
        "tale.brother.light": [
            "Light the old fire by the mine. They'd see it.",
            "If they live, they'll climb toward the light.",
        ],
        # the smith, to the player after the reunion (endings A to C)
        "tale.brother.thanks": [
            "You brought them home. I owe you everything.",
            "I can never repay this. Never.",
        ],
        # the smith at the stone (S5 The Laying, ending W), as the cairn is set; the brother's name follows in grey
        "tale.brother.cairn": [
            "Rest easy. I'll keep a lamp lit for you.",
            "At least there is a place to come and speak.",
        ],
        # the smith, when nothing was in motion by the close of the fifth day (ending W)
        "tale.brother.late": [
            "Too many nights. No one lives so long below.",
            "I knew, and I hoped anyway.",
        ],
        # the smith, when a player who is in asks "How does it stand?"; the days left follow in grey
        "tale.brother.stand": [
            "Any word? Anything at all?",
            "Every hour down there is a bad one.",
        ],
    }),
    ("The Brother Under the Hill: short lines", MW, GREETING, {
        # the delver, once the sum is whole (route B): the party goes at the close
        "tale.brother.paid": [
            "Done. We go at the close.",
            "Settled. Leave it to us.",
        ],
        # the brother in the first hall of the mine (route A), as the player comes within six blocks
        "tale.brother.found": [
            "You came. I thought no one would.",
            "My leg. Stay close, they're still about.",
        ],
        # the brother, when the player says "Can you walk? Come with me." and he sets out behind them (S8 The Slow
        # Walk)
        "tale.brother.follow": [
            "Lead on. I'll keep close.",
            "Slowly, now. And watch the dark.",
        ],
        # the brother on the way up, when a hostile mob comes within eight blocks
        "tale.brother.scared": [
            "Something's coming! Hurry!",
            "Behind you! Go, go!",
        ],
        # the brother at the mine's fire (route C), as he comes out toward the light
        "tale.brother.rise": [
            "The light. I saw the light.",
            "Your fire. I'd almost given up.",
        ],
        # the brother, when the mine is cleared by others while he is below
        "tale.brother.freed": [
            "It's quiet. Is it over?",
            "The guards are gone. Who did it?",
        ],
        # the delver in the street at first light (The Return, route B), as the party brings the brother up
        "tale.brother.delver_back": [
            "We found them. Hurt, but breathing.",
            "Alive. Hurt, but they'll mend.",
        ],
        # the brother at the forge door (S6 The Reunion), home at last
        "tale.brother.home": [
            "I'm home. I'm really home.",
            "Never again. I swear it.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.brother.ask": WORRY,  # two nights, and no word
    "tale.brother.passing": TIRED,  # a forge gone quiet, listening for the door
    "tale.brother.stand": WORRY,  # asking again for any word at all
    "tale.brother.thanks": dict(speed=0.93, level=0.5),  # owing everything
    "tale.brother.light": dict(speed=0.97),  # hope, said carefully
    "tale.brother.cairn": GENTLE,  # a lamp kept for the dead
    "tale.brother.late": GRIEF,  # too many nights
    "tale.brother.found": dict(speed=0.95, level=-0.5),  # weak, and glad
    "tale.brother.follow": dict(level=-1.5),  # kept low in the dark
    "tale.brother.scared": dict(speed=1.08, level=2.5),  # shouted: something is coming
    "tale.brother.rise": RELIEF,  # the light seen
    "tale.brother.freed": dict(level=-0.5),  # hushed wonder
    "tale.brother.delver_back": dict(speed=0.97, level=0.5),  # found alive
    "tale.brother.home": dict(speed=0.95, level=0.5),  # home at last
}
SPEED_FOR = {
    ("talk.tale.brother.ask_1", "man"): 0.9,  # slower, so the "n" of "mine" is heard
    ("talk.tale.brother.delver_back_1", "man"): 0.892,  # heard "A life hurt but they'll mend."
    ("talk.tale.brother.rumour_1", "man"): 0.92,  # heard "They say a Dalvar is missing in the workings."
    ("talk.tale.brother.passing_0", "man"): 0.874,  # with its phonemes
}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    ("talk.tale.brother.ask_1", "man"): "ðeɪ wɛnt tə ðɪ ˈəʊld mˈaɪn. nˈəʊ wˈɜːd sˈɪns.",  # "old mine", not "old mind"
    ("talk.tale.brother.paid_0", "man"): "dˈʌn. wiː ɡˌəʊ, ˈat, ðə klˈəʊs.",  # heard "Don, we go with the close."
    ("talk.tale.brother.rise_1", "man"): "jˈɔːɹ fˈaɪɚ. ˈaɪd ˈɔːlməʊst ɡˈɪvən ˈʌp.",  # heard "You're fire at almost given up."
    ("talk.tale.brother.passing_0", "man"): "ðə fˈɔːɹdʒ fˈiːlz ˈɛmpti. nˈQ wˈɜːd at ˈɔːl.",  # heard "The forage feels empty, no word at all."
    ("talk.tale.brother.rumour_0", "woman"): "sˈʌmwɒn wɛnt dˌWn ðɪ ˈQld mˈIn. nˈQ sˈIn. . sˈɪns.",  # heard "Someone went down the old mine, no signsense."
}
