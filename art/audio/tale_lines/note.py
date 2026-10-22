"""The Marlow Note (living-world/stories/design.md section 8.1): what its cast and the town say, as families of
wordings in the form of make_talk_voices.GROUPS. A family is one meaning said two ways; the comment over it
says when it is said, and what follows it in grey (the detail, never voiced) where one does. The tier of each
family is read from its beat (tale_lines.tier).
"""
from . import GREETING, MW, TALK, TIRED, WARM

GROUPS = [
    ("The Marlow Note", MW, TALK, {
        # the debtor at her stall (S1 The Count, 10:00 to 16:00), to a player who has not said yes yet: the opener;
        # the sum and the lender follow in grey
        "tale.note.ask": [
            "I'm into a man for more than I can pay.",
            "I borrowed against this stall, and now it's due.",
        ],
        # the debtor, to anyone passing who has not been asked: she mentions her trouble unasked (3.1)
        "tale.note.passing": [
            "Hard days, and a man wanting his money.",
            "Lean times. I'm behind, and it shows.",
        ],
        # anyone who has heard of it (3.2), told as the town's talk; who owes whom, and when it falls due, follow in
        # grey
        "tale.note.rumour": [
            "They say a stall keeper owes a lender, and it's due.",
            "Someone on the row is behind with the lender.",
        ],
        # the debtor, when a player who is in asks "How does it stand?"; the sum still owed and the days left follow
        # in grey
        "tale.note.stand": [
            "Still owing, but less than before.",
            "We're nearer, thanks to you.",
        ],
        # the debtor, when the note is paid and the lender hands it to her (endings A and B)
        "tale.note.paid": [
            "It's done. It's truly done. Thank you.",
            "I can breathe again. I'll not forget you.",
        ],
        # the lender, on the due day with twelve or less still owing (ending C): he takes the lease of the stall and
        # she stays on as his keeper
        "tale.note.lease": [
            "I'll keep the lease. She stays on as my keeper.",
            "The stall is mine now. She'll keep it for me.",
        ],
        # the debtor at the seal (The Seal, ending W, 9:00 on the due day), as the lender points at her awning and she
        # lets it down herself
        "tale.note.sealed": [
            "That's that, then. Take it, all of it.",
            "I'll not beg. Shut it up.",
        ],
    }),
    ("The Marlow Note: short lines", MW, GREETING, {
        # the debtor, the moment the player says yes
        "tale.note.thanks": [
            "You'd stand for us? Truly?",
            "I'll not forget this.",
        ],
        # the debtor, at each purchase the player makes at her stall while the note is owing
        "tale.note.progress": [
            "Every coin counts. Thank you.",
            "That helps more than you know.",
        ],
        # the lender, to anyone who asks him "How much is it?" in the square (13:00 to 17:00); the sum and the days
        # follow in grey
        "tale.note.lender_hard": [
            "A debt is a debt.",
            "I lent in good faith.",
        ],
        # the lender, to a player the town knows (25 or more) who says "Give her a few days. I'll answer for it.": the
        # due day moves three days on, and the player is the surety
        "tale.note.lender_grace": [
            "On your word. A few days only.",
            "A few days. You answer for it.",
        ],
        # the lender, to a Starwatch member who says "Show me the sums.": the interest was counted twice, and six
        # Marks come off
        "tale.note.lender_sums": [
            "These sums are wrong. I stand corrected.",
            "I miscounted. I'll mend it.",
        ],
        # the lender, to a Hearthsworn member who asks what the Order's law says of lenders: six Marks come off
        "tale.note.lender_law": [
            "The Order's law. I'll take less.",
            "I'd not cross the Order. Less, then.",
        ],
        # the lender, to a Quiet Hand member who says "A quiet word.": six Marks come off
        "tale.note.lender_word": [
            "Say no more. I'll take less.",
            "Heard. We'll say no more of it.",
        ],
        # the lender, as he hands the paid note over (S4 The Burning, ending A; the quiet paper of ending B)
        "tale.note.quits": [
            "Paid in full. We are quits.",
            "Then it's settled between us.",
        ],
    }),
]

# This tale's delivery, where a family wants one other than the plain reading (see make_talk_voices.py: DELIVERY
# by family or prefix, SPEED_FOR by (key, timbre), SAY_AS by key, PHONEMES by key or (key, timbre))
DELIVERY = {
    "tale.note.ask": dict(level=-0.5),  # a debtor's worry, said low
    "tale.note.passing": TIRED,  # the same trouble, muttered to whoever passes
    "tale.note.thanks": WARM,  # moved that someone would stand for them
    "tale.note.paid": WARM,  # the breath let go: the note is paid
    "tale.note.sealed": TIRED,  # giving up the stall without a fight
    "tale.note.progress": WARM,  # a coin at a time
    "tale.note.lender_hard": dict(speed=0.97, level=0.5),  # the lender, firm
    "tale.note.lender_law": TIRED,  # the lender, giving way to the Order's law
    "tale.note.lender_word": dict(level=-1.5),  # a quiet word, answered quietly
}
SPEED_FOR = {}
SAY_AS = {}
# Each fix says what Whisper heard without it
PHONEMES = {
    "talk.tale.note.ask_1": "aɪ bˈɒɹəʊd ɐɡˈɛnst ðˈɪss stˈɔːl, and nˈaʊ ɪts djˈuː.",  # "this stall", not "the stall"
    ("talk.tale.note.stand_1", "man"): "wɪɹ nˈɪɹɚ, θˈaŋks tə juː.",  # "We're nearer", not "We're near a"
    ("talk.tale.note.lender_hard_1", "man"): "ˈaɪ, lˈɛnt, ˈɪn, ɡˈʊd fˈeɪθ.",  # heard "I'm letting good faith."
    ("talk.tale.note.lender_sums_1", "man"): "ˈaɪ mɪskˈaʊntɪd. aɪl mˈɛnd ɪt.",  # heard "I'm discounted, I'll mend it."
    ("talk.tale.note.quits_0", "man"): "pˈeɪd ɪn fˈʊl. wˈiː ˈɑːɹ kwˈɪts.",  # heard "Paid in full wear quits."
    ("talk.tale.note.quits_1", "man"): "ðˈɛn, ˈɪts, sˈɛtəld bɪtwˈiːn ˌʌs.",  # heard "Then it settled between us."
    ("talk.tale.note.sealed_1", "man"): "ˈaɪl, nˌɒt, bˈɛɡ. ʃˈʌt ɪt ˈʌp.",  # heard "I'm not big, shut it up."
    ("talk.tale.note.lease_1", "woman"): "ðə stˈɔːl ɪz mˈaɪn nˈaʊ. ʃiːl kˈiːp ɪt fˈɔːɹ mˌiː.",  # heard "The stall is mine now, she'll keep it from me."
    ("talk.tale.note.lender_sums_1", "woman"): "ˈaɪ, mɪskˈaʊntɪd. aɪl mˈɛnd ɪt.",  # heard "I'm discounted, I'll mend it."
    ("talk.tale.note.lender_word_1", "man"): "hˈɜːd. wˈiːl sˈA nˈəʊmˌɔːɹ ɒv ɪt.",  # heard "Heard we'll see no more of it."
}
