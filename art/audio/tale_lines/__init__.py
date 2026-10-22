"""The tales' voices (living-world/stories/design.md 13.5): every family of wordings the twelve tales' people say, in the
form of make_talk_voices.GROUPS, one module for each tale and a shared one (shared.py: the lines every tale uses; note.py,
chair.py, wagon.py, child.py, fence.py, lute.py, moot.py, cart.py, lantern.py, informer.py, locket.py, brother.py: one
tale each, in the order of the design's section 8). 189 families, 380 wordings, 774 takes.

A module holds `GROUPS`, a list of (label, timbres, limit, {family: [wording, ...]}) as make_talk_voices.GROUPS does, with
a comment over each family saying when it is said and what follows it in grey; and optionally `DELIVERY`, `SPEED_FOR`,
`SAY_AS` and `PHONEMES` for the lines that need them (make_talk_voices.py has the forms). Each wording is a whole
sentence that is true whenever its family is picked, so it holds no name, no number and no brace: whatever changes follows
as the grey detail (say.guildmark.talk.detail.tale.<id>.<beat>, Part C's), which is never voiced. A family's takes play as
townsfolk.voice.<timbre>.talk.<family>_<n>; its typed text (and the subtitle) is say.guildmark.talk.<family>_<n>.

Tiers. `table` (make_talk_voices.py) is all or nothing: it refuses to run while any take of any family in GROUPS is
missing. So a family enters make_talk_voices.GROUPS (through `groups()`) only when its takes are made, and `RECORDED` names
the tiers that are. The tier is read from the family's beat, so there is no list to keep:

    1  the shared families, and each tale's ask, stand and thanks: what every player hears at every step
    2  each tale's passing and rumour: what the street says
    3  everything else: the lines inside the set pieces and the endings

`RECORDED` is raised in the commit that adds the tier's takes and the TalkLines.kt, talk_sounds.json and talk_lang.json that
`table` regenerates, and in no other. `lang.py` writes the typed text of all three tiers whatever `RECORDED` is, so a line
reads in full before its take exists (design 4.5: a family with no take is read as text, and a family with no text shows
nothing).

    python3 -B art/audio/tale_lines/lang.py          writes art/lang/tales_h.json, then runs check()
    python3 -B art/audio/tale_lines/lang.py --check  changes nothing: says whether the fragment is current and check() passes
"""
import re
import sys

sys.dont_write_bytecode = True

# The timbre sets the talk records (make_talk_voices.MWO, MW, OLD, CHILD, named again here because make_talk_voices
# imports this package) and its two length limits in seconds of a processed take (GREETING: about 7 words, TALK: about 11)
MWO = ("man", "woman", "old")
MW = ("man", "woman")
OLD = ("old",)
CHILD = ("child",)
GREETING, TALK = 2.5, 4.0

# The deliveries the families share, in make_talk_voices.DELIVERY's terms (speed: times the timbre's own; pitch: cents added;
# level: dB over the common speech level), so a family names what its speaker feels and the numbers are set once. The
# existing tables stay within these amounts (speed 0.92 to 1.06, pitch down to -30, level -4 to +2.5): the voices are
# Kokoro's, which takes a small change well and a large one badly.
WORRY = dict(speed=1.04, level=0.5)  # asking in a hurry, afraid of the answer
TIRED = dict(speed=0.95, level=-1.0)  # ashamed, worn out or flat: a little slower and under
GRIEF = dict(speed=0.90, pitch=-25, level=-1.5)  # a held-back sorrow: slower, lower, softer
GENTLE = dict(speed=0.94, level=-1.0)  # comfort and tenderness
WARM = dict(speed=0.95, level=0.5)  # thanks that mean it
CALL = dict(speed=1.05, pitch=15, level=2.5)  # calling out after someone, over a distance
CHEER = dict(speed=1.03, level=1.5)  # a glass raised, a crowd's pleasure
HUSH = dict(level=-3.0)  # said low, in confidence
PLEA = dict(speed=0.96, pitch=20, level=0.5)  # begging
RELIEF = dict(speed=0.97, level=1.5)  # the breath let go

# The tiers whose takes are made, and so whose families are in make_talk_voices.GROUPS (see above)
RECORDED = (1, 2, 3)

from . import shared, note, chair, wagon, child, fence, lute, moot, cart, lantern, informer, locket, brother  # noqa: E402

MODULES = (shared, note, chair, wagon, child, fence, lute, moot, cart, lantern, informer, locket, brother)
ALL_GROUPS = [g for m in MODULES for g in m.GROUPS]

# The delivery tables of make_talk_voices.py (DELIVERY, SPEED_FOR, SAY_AS, PHONEMES), all the modules' entries together
DELIVERY, SPEED_FOR, SAY_AS, PHONEMES = {}, {}, {}, {}
for _m in MODULES:
    DELIVERY.update(_m.DELIVERY)
    SPEED_FOR.update(_m.SPEED_FOR)
    SAY_AS.update(_m.SAY_AS)
    PHONEMES.update(_m.PHONEMES)

# The crier's seven lines (design 6.2, 8.7, 8.9, 13.5.6): one wording each, recorded by make_townsfolk_voices.py (CRIER)
# in the man's and the woman's voices, not through the talk table. Their typed text is the same as the CRIER entries'.
CRIER = {
    "crier_tale_good": "A trouble in the town has been mended!",
    "crier_tale_ill": "A sad turn in the town today!",
    "crier_tale_home": "Someone long awaited is home again!",
    "crier_tale_lost": "We have lost one of our own!",
    "crier_news_tale": "A great turn has been done in a town!",
    "crier_tale_moot": "The moot is called! Come, and say your piece!",
    "crier_tale_custom": "A custom of the town is kept tonight!",
}

SHARED_IDS = ("set", "ask", "why", "after")
SET_NAMES = {MW: "MW", MWO: "MWO", OLD: "OLD", CHILD: "CHILD"}
TIER_NOTES = {1: "the shared families and each tale's ask, stand and thanks",
              2: "each tale's passing and rumour",
              3: "the lines inside the set pieces and the endings"}


def families():
    """{family: (timbres, limit, [wording, ...])} for every family of every tier, in the order of the modules"""
    out = {}
    for _, timbres, limit, fams in ALL_GROUPS:
        for family, words in fams.items():
            assert family not in out, f"{family} is in the tales' table twice"
            out[family] = (timbres, limit, words)
    return out


def tier(family):
    """1, 2 or 3: read from the beat in the family's name (tale.<id>.<beat>; the shared families are tale.set.*,
    tale.ask.*, tale.why.* and tale.after.*, all tier 1)"""
    _, tale, beat = family.split(".")
    if tale in SHARED_IDS or beat in ("ask", "stand", "thanks"):
        return 1
    return 2 if beat in ("passing", "rumour") else 3


def groups(tiers=None):
    """The groups of make_talk_voices.GROUPS' form that hold the families of [tiers] (RECORDED when none is given), each
    group with only those families, and left out if it has none: what make_talk_voices adds to its own table"""
    tiers = RECORDED if tiers is None else tiers
    out = []
    for label, timbres, limit, fams in ALL_GROUPS:
        kept = {f: w for f, w in fams.items() if tier(f) in tiers}
        if kept:
            out.append((label, timbres, limit, kept))
    return out


def keys(tiers=(1, 2, 3)):
    """[(timbre, key, text, limit)] for every take of the families of [tiers], key being talk.<family>_<n>"""
    out = []
    for family, (timbres, limit, words) in families().items():
        if tier(family) in tiers:
            for n, text in enumerate(words):
                for timbre in timbres:
                    out.append((timbre, f"talk.{family}_{n}", text, limit))
    return out


def words_of(text):
    """the words a wording counts as: the pieces between spaces (a contraction is one)"""
    return len(text.split())


def check():
    """Raises ValueError listing everything wrong with the table; else returns a one-line summary. What it holds to is
    the design's: one to four wordings a family (two, but three in the child's found and agree), 7 words in a 2.5 s
    family and 11 in a 4.0 s one, no brace, no percent sign, no name, unique names, the four recorded timbre sets, and
    the totals of 13.5.1 (189 families, 380 wordings, 774 takes; 50, 21 and 118 families in the three tiers)."""
    bad = []
    fams = families()
    limits = {GREETING: 7, TALK: 11}
    seen = {}
    for family, (timbres, limit, words) in fams.items():
        if not re.fullmatch(r"tale\.[a-z]+\.[a-z_]+", family):
            bad.append(f"{family}: not tale.<id>.<beat>")
            continue
        _, tale, beat = family.split(".")
        if timbres not in SET_NAMES:
            bad.append(f"{family}: timbres {timbres} are not one of the four recorded sets")
        if limit not in limits:
            bad.append(f"{family}: limit {limit} is neither {GREETING} nor {TALK}")
            continue
        if not 1 <= len(words) <= 4:
            bad.append(f"{family}: {len(words)} wordings, not one to four")
        elif len(words) != (3 if family in ("tale.child.found", "tale.child.agree") else 2):
            bad.append(f"{family}: {len(words)} wordings; the design has two (three in the child's found and agree)")
        for n, w in enumerate(words):
            where = f"{family}_{n}"
            if not isinstance(w, str) or not w:
                bad.append(f"{where}: not a text")
                continue
            if w != w.strip() or "  " in w:
                bad.append(f"{where}: stray spaces: {w!r}")
            if not w.isascii() or any(c in w for c in '{}%"\\'):
                bad.append(f"{where}: has a brace, percent sign, quote mark or a non-ASCII letter: {w!r}")
            if not (w[0].isupper() and w[-1] in ".!?"):
                bad.append(f"{where}: a wording starts with a capital and ends with . ! or ?: {w!r}")
            n_words = words_of(w)
            if n_words > limits[limit]:
                bad.append(f"{where}: {n_words} words, over the {limits[limit]} of a {limit} s line: {w!r}")
            # no name in a take: a capital inside a sentence is only ever I, the Order or Lanternrise
            for m in re.finditer(r"\b[A-Z][A-Za-z']*", w):
                if m.start() == 0 or re.search(r"[.!?]\s+$", w[:m.start()]):
                    continue
                if m.group(0).split("'")[0] not in ("I", "Order", "Lanternrise"):
                    bad.append(f"{where}: {m.group(0)!r} looks like a name, and no take holds one: {w!r}")
            if w in seen:
                bad.append(f"{where}: the same wording as {seen[w]}")
            seen[w] = where
        if len(set(words)) != len(words):
            bad.append(f"{family}: a wording twice")
    def known(k):
        """whether a delivery table's key names a family (a take's key, talk.<family>_<n>) or a prefix of some"""
        k = k[0] if isinstance(k, tuple) else k
        if k.startswith("talk."):
            return k[len("talk."):].rsplit("_", 1)[0] in fams
        return any(f == k or f.startswith(f"{k}.") or f.startswith(f"{k}_") for f in fams)

    for m in MODULES:
        for table, name in ((m.DELIVERY, "DELIVERY"), (m.SPEED_FOR, "SPEED_FOR"), (m.SAY_AS, "SAY_AS"),
                            (m.PHONEMES, "PHONEMES")):
            for k in table:
                if not known(k):
                    bad.append(f"{m.__name__}.{name}: {k!r} names no family of the tales")
    for k, v in DELIVERY.items():
        if not set(v) <= {"speed", "pitch", "level"}:
            bad.append(f"DELIVERY[{k!r}]: {v} has something other than speed, pitch and level")
    if set(RECORDED) - {1, 2, 3}:
        bad.append(f"RECORDED {RECORDED} names a tier there is not")
    # the totals of 13.5.1 and 13.5.4
    wordings = sum(len(w) for _, _, w in fams.values())
    takes = sum(len(w) * len(t) for t, _, w in fams.values())
    per_tier = {n: (sum(1 for f in fams if tier(f) == n),
                    sum(len(w) * len(t) for f, (t, _, w) in fams.items() if tier(f) == n)) for n in (1, 2, 3)}
    by_timbre = {t: sum(len(w) for ts, _, w in fams.values() if t in ts) for t in ("man", "woman", "old", "child")}
    want = {"families": 189, "wordings": 380, "takes": 774, "tiers": {1: (50, 208), 2: (21, 86), 3: (118, 480)},
            "timbres": {"man": 368, "woman": 368, "old": 28, "child": 10}}
    have = {"families": len(fams), "wordings": wordings, "takes": takes, "tiers": per_tier, "timbres": by_timbre}
    if have != want:
        bad.append(f"the totals are not the design's (13.5.1): {have}, not {want}")
    # the typed text, once written, is current
    from . import lang
    stale = lang.stale()
    if stale:
        bad.append(stale)
    # the same constants as make_talk_voices has (it imports this package, so it is looked at only if it can be loaded)
    try:
        import make_talk_voices as talk
    except ImportError:
        talk = None
    if talk is not None:
        if (talk.MWO, talk.MW, talk.OLD, talk.CHILD, talk.GREETING, talk.TALK) != (MWO, MW, OLD, CHILD, GREETING, TALK):
            bad.append("the timbre sets or limits differ from make_talk_voices.py's")
        try:
            talk.families()
        except AssertionError as e:
            bad.append(f"make_talk_voices.families() refuses the table: {e}")
    if bad:
        raise ValueError(f"{len(bad)} problem{'s' if len(bad) != 1 else ''} in art/audio/tale_lines:\n  " + "\n  ".join(bad))
    return (f"ok: {len(fams)} families, {wordings} wordings, {takes} takes; tiers "
            + ", ".join(f"{n}: {f} families / {t} takes" for n, (f, t) in per_tier.items())
            + f"; RECORDED {RECORDED}")
