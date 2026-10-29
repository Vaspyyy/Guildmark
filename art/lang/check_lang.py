#!/usr/bin/env python3
"""
Finds the language keys the Kotlin names that the merged en_us.json lacks, so a missing line is found by a command and
not by a player seeing a raw key (living-world/build-plan.md section 6; Tales' design 13.6).

    python3 -B art/lang/check_lang.py                       the four designs
    python3 -B art/lang/check_lang.py trade tales           only those (cook, trade, tales, villages)
    python3 -B art/lang/check_lang.py --prefix gui.guildmark.works.   any keys that start so, as a design of its own
    python3 -B art/lang/check_lang.py --all                 also lists the keys that are there

It reads every string in src/main/kotlin (comments left out), keeps those that start with one of a design's PREFIXES and
look like a language key (Component.translatable("gui.guildmark.trade.foo"), a key in a table or a when, a key handed to a
helper), and looks each up in lang/en_us.json. A key with a template in it ("fortune.guildmark.reason.tale.$it") is
looked up as a pattern: it must match at least one key. Exit status 1 if any key is missing, 0 if none is.

What it cannot see: a key put together from pieces (say("hauled") for fortune.guildmark.reason.hauled), or one that
begins with a template. Give those a command or a test step that shows the line. A missing key it finds that is in a
fragment already only wants merge_lang.py run.

Each part adds its own prefixes to PREFIXES, in one line. Python 3 standard library only.
"""

import bisect
import json
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import merge_lang  # noqa: E402  (the fragments' names and places are its)

ROOT = merge_lang.ROOT
KOTLIN = os.path.join(ROOT, "src", "main", "kotlin")

# What each design's language keys start with. Not a bare "tale.": rumours', voice families' and sounds' ids start so
# too, and they are not language keys.
PREFIXES = {
    "cook": (
        "block.guildmark.stewpot", "block.guildmark.hob", "block.guildmark.bake_oven", "block.guildmark.spit",
        "block.guildmark.prep_board", "block.guildmark.plate", "block.guildmark.larder", "block.guildmark.menu_board",
        "item.guildmark.dish_", "item.guildmark.gilded_ladle", "effect.guildmark.",
        "item.guildmark.cooks_", "item.guildmark.turner", "item.guildmark.cook_book", "item.guildmark.herb_sprig",
        "item.guildmark.gruel", "item.guildmark.scorched_mess", "block.guildmark.herb",
        "bonus.guildmark.cook_", "guildmark.configuration.cook", "gui.guildmark.settings.section.cooking",
        "gui.guildmark.cook.", "message.guildmark.cook.", "gui.guildmark.talk.kitchen", "subtitles.guildmark.cook.",
        "say.guildmark.talk.kitchen.", "say.guildmark.talk.detail.kitchen.",
        "say.guildmark.talk.works.raised.cookshop", "say.guildmark.talk.works.also.raised.cookshop",
        "gui.guildmark.works.why.cookshop", "gui.guildmark.works.doing.cookshop", "fortune.guildmark.works.cookshop",
        "gui.guildmark.works.kind.cookshop", "board.guildmark.slip.works.kind.cookshop", "gui.guildmark.works.not_yet.cookshop",
        "gui.guildmark.works.stage_of.cookshop", "gui.guildmark.works.pledges.none_cookshop", "say.guildmark.talk.detail.works.raised.cookshop",
        "message.guildmark.festival.hint.sheafmoot_cook",
    ),
    "trade": (
        "block.guildmark.haul_board", "item.guildmark.tally", "item.guildmark.carriers_warrant",
        "item.guildmark.pack_harness", "item.guildmark.drovers_whistle", "item.guildmark.chapmans_cart",
        "item.guildmark.wagon_bill", "entity.guildmark.player_cart",
        "gui.guildmark.trade.", "gui.guildmark.tally.", "message.guildmark.trade.", "news.guildmark.trade.", "subtitles.guildmark.trade.",
        "skill.guildmark.chapman", "skill.guildmark.factor", "title.guildmark.chapman", "title.guildmark.factor",
        "title.guildmark.trading_house", "guildmark.configuration.trade",
        "say.guildmark.trade_", "say.guildmark.talk.trade.", "say.guildmark.talk.detail.trade.",
        "fortune.guildmark.kind.hauled", "fortune.guildmark.reason.hauled",
        "gui.guildmark.works.why.store", "gui.guildmark.works.why.counting_house", "gui.guildmark.works.not_yet.store", "gui.guildmark.works.not_yet.counting_house", "gui.guildmark.works.doing.store", "gui.guildmark.works.doing.counting_house", "gui.guildmark.works.stage_of.store", "gui.guildmark.works.stage_of.counting_house", "gui.guildmark.works.kind.store", "gui.guildmark.works.kind.counting_house", "gui.guildmark.works.pledges.none_store", "gui.guildmark.works.pledges.none_counting_house", "board.guildmark.slip.works.kind.store", "board.guildmark.slip.works.kind.counting_house", "fortune.guildmark.works.store", "fortune.guildmark.works.counting_house", "say.guildmark.talk.detail.works.raised.store", "say.guildmark.talk.detail.works.raised.counting_house", "block.guildmark.store_", "block.guildmark.strongbox", "block.guildmark.counting_frame", "block.guildmark.house_",
    ),
    "tales": (
        "say.guildmark.talk.tale.", "say.guildmark.talk.detail.tale.", "talk.guildmark.choice.tale.",
        "say.guildmark.crier_tale_", "say.guildmark.crier_news_tale", "subtitles.guildmark.townsfolk.tale.",
        "gui.guildmark.tale.", "message.guildmark.tale.", "news.guildmark.tale.", "board.guildmark.slip.tale",
        "fortune.guildmark.kind.tale", "fortune.guildmark.reason.tale.", "fortune.guildmark.tale.",
        "item.guildmark.keepsake", "item.guildmark.tale_quill", "item.guildmark.tale_prop",
        "block.guildmark.memorial", "atlas.guildmark.memorial", "atlas.guildmark.kind.memorial",
        "gui.guildmark.map.tip.tale", "gui.guildmark.map.tip.memorial", "advancements.guildmark.tale",
        "advancements.guildmark.tales_", "handbook.guildmark.page19", "character.guildmark.pip.verse.tales_three",
        "guildmark.configuration.townTale", "guildmark.configuration.taleBanners",
        "slate.guildmark.sealed", "slate.guildmark.lease", "gui.guildmark.works.why.moot",
        "board.guildmark.slip.word.tale", "title.guildmark.good_neighbour", "say.guildmark.talk.detail.deal.slight.word_broken", "say.guildmark.talk.detail.deal.slight.wronged", "say.guildmark.talk.detail.deal.slight.defaced", "gui.guildmark.works.regard.cool.slight.word_broken", "gui.guildmark.works.regard.cool.slight.wronged", "gui.guildmark.works.regard.cool.slight.defaced",
    ),
    "villages": (
        "say.guildmark.talk.village.", "say.guildmark.talk.detail.village.", "subtitles.guildmark.village.",
        "gui.guildmark.village.", "message.guildmark.village.", "news.guildmark.village.", "block.guildmark.village_",
        "guildmark.configuration.village", "gui.guildmark.settings.section.villages",
        "fortune.guildmark.kind.held", "fortune.guildmark.kind.lost",
        "fortune.guildmark.reason.held", "fortune.guildmark.reason.lost",
        "guildmark.configuration.livingVillages", "guildmark.configuration.maxLiveVillages", "guildmark.configuration.maxVillageFolk",
        "talk.guildmark.choice.wares",
    ),
}

WILD = "\x00"  # a template in a string ("$it", "${...}"): any text
SPECIAL = re.compile(r'["\\$\n]')
CODE = re.compile(r"[/\"']")
CODE_NESTED = re.compile(r"[/\"'{}]")
KEY = re.compile(r"^[A-Za-z0-9_.\x00-]+$")


def scan_code(src, i, out, nested):
    """Scans Kotlin code from [i]; collects (index, text) of each string into [out]. Nested: inside a ${...}, until its closing brace."""
    n = len(src)
    braces = 0
    find = CODE_NESTED if nested else CODE
    while i < n:
        m = find.search(src, i)
        if m is None:
            return n
        i = m.start()
        c = src[i]
        if c == "/":
            nxt = src[i + 1:i + 2]
            if nxt == "/":
                j = src.find("\n", i)
                i = n if j < 0 else j
            elif nxt == "*":
                depth = 1
                i += 2
                while i < n and depth:
                    if src.startswith("/*", i):
                        depth += 1
                        i += 2
                    elif src.startswith("*/", i):
                        depth -= 1
                        i += 2
                    else:
                        i += 1
            else:
                i += 1
        elif c == '"':
            raw = src.startswith('"""', i)
            i = scan_string(src, i + (3 if raw else 1), out, raw, i)
        elif c == "'":
            # A character: 'x', '\n', '\u0041', '"'
            if src[i + 1:i + 2] == "\\":
                i += 8 if src[i + 2:i + 3] == "u" else 4
            else:
                i += 3
        else:  # a brace, in a template's code
            if c == "{":
                braces += 1
            elif braces == 0:
                return i + 1
            else:
                braces -= 1
            i += 1
    return n


def scan_string(src, i, out, raw, start):
    """Scans a string whose text begins at [i]; adds (start, text) to [out]; returns the index after its end."""
    n = len(src)
    parts = []
    while i < n:
        m = SPECIAL.search(src, i)
        if m is None:
            parts.append(src[i:])
            i = n
            break
        parts.append(src[i:m.start()])
        i = m.start()
        c = src[i]
        if c == '"':
            if not raw:
                i += 1
                break
            if src.startswith('"""', i):
                i += 3
                break
            parts.append(c)
            i += 1
        elif c == "\\":
            if raw:
                parts.append(c)
                i += 1
            else:
                parts.append("\\" + src[i + 1:i + 2])  # an escape: not a key's
                i += 2
        elif c == "\n":
            if not raw:
                break  # not closed on its line: give up on it
            parts.append(c)
            i += 1
        else:  # $
            nxt = src[i + 1:i + 2]
            if nxt == "{":
                parts.append(WILD)
                i = scan_code(src, i + 2, out, True)
            elif nxt.isalpha() or nxt == "_":
                j = i + 1
                while j < n and (src[j].isalnum() or src[j] == "_"):
                    j += 1
                parts.append(WILD)
                i = j
            else:
                parts.append("$")
                i += 1
    out.append((start, "".join(parts)))
    return i


def named_keys(prefixes):
    """-> {design: {key text: [(file, line), ...]}} for the strings under src/main/kotlin that start with [prefixes] (a dict of design -> prefixes)."""
    every = [p for ps in prefixes.values() for p in ps]
    found = {design: {} for design in prefixes}
    for folder, _, names in os.walk(KOTLIN):
        for name in sorted(names):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(folder, name)
            with open(path, encoding="utf-8") as f:
                src = f.read()
            if not any(p in src for p in every):
                continue
            strings = []
            scan_code(src, 0, strings, False)
            newlines = [m.start() for m in re.finditer("\n", src)]
            rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
            for at, text in strings:
                if not KEY.match(text):
                    continue
                for design, ps in prefixes.items():
                    if any(text.startswith(p) for p in ps):
                        found[design].setdefault(text, []).append((rel, bisect.bisect_left(newlines, at) + 1))
    return found


def fragment_lines():
    """The lines the language fragments hold, key -> fragment (those that can be read; merge_lang.py says what is wrong with the rest)."""
    out = {}
    for rel in merge_lang.fragment_files(merge_lang.TARGETS[0][2])[0]:
        try:
            for key in merge_lang.read_json(rel)[0]:
                out.setdefault(key, rel)
        except merge_lang.Refused:
            pass
    return out


def main(argv):
    designs, extra, listing = [], [], False
    args = list(argv)
    while args:
        a = args.pop(0)
        if a == "--all":
            listing = True
        elif a == "--prefix" and args:
            extra.append(args.pop(0))
        elif a in PREFIXES:
            designs.append(a)
        else:
            print(__doc__.strip().split("\n\n")[1], file=sys.stderr)
            return 2
    prefixes = {d: PREFIXES[d] for d in (designs or ([] if extra else list(PREFIXES)))}
    for p in extra:
        prefixes[p + "*"] = (p,)
    try:
        with open(os.path.join(ROOT, *merge_lang.LANG.split("/")), encoding="utf-8") as f:
            merged = json.load(f)
    except (OSError, ValueError) as e:
        print("%s: can't be read: %s" % (merge_lang.LANG, e), file=sys.stderr)
        return 2
    in_fragments = fragment_lines()
    found = named_keys(prefixes)
    missing_any = False
    for design, keys in found.items():
        missing, there = [], []
        for text, where in keys.items():
            if WILD in text:
                rx = re.compile(".+".join(re.escape(part) for part in text.split(WILD)))
                ok = any(rx.fullmatch(k) for k in merged)
                shown = text.replace(WILD, "*")
            else:
                ok = text in merged
                shown = text
            (there if ok else missing).append((shown, where))
        n = len(keys)
        print("%s: %d key%s named in the Kotlin, %s" % (design, n, "" if n == 1 else "s", "%d missing" % len(missing) if missing else "none missing"))
        for shown, where in sorted(missing):
            more = " (+%d more)" % (len(where) - 1) if len(where) > 1 else ""
            note = "   [in %s: run merge_lang.py]" % in_fragments[shown] if shown in in_fragments else ""
            print("  MISSING %-60s %s:%d%s%s" % (shown, where[0][0], where[0][1], more, note))
        if listing:
            for shown, where in sorted(there):
                print("  there   %-60s %s:%d" % (shown, where[0][0], where[0][1]))
        missing_any = missing_any or bool(missing)
    return 1 if missing_any else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
