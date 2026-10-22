#!/usr/bin/env python3
"""
Merges the living-world designs' language and sound fragments into the two files the game reads.

    python3 -B art/lang/merge_lang.py            adds what is missing, and says what it did
    python3 -B art/lang/merge_lang.py --check    changes nothing; says what a merge would do (exit 1 if it would do any)

lang/en_us.json and sounds.json are OUTPUT. No part edits either by hand (living-world/build-plan.md section 6, and
Hearthcraft's design 12.5): each part writes its lines into a fragment of its own, and this script adds them. A part
commits its fragment and the two merged files together. If git reports a conflict in either big file, take main's and
run this again: the fragments are the source.

The fragments are found by name, so a new one needs no edit here. A design's fragments start with one of its names
(DESIGNS: the build plan's spelling first, then the spelling its own scripts use), and are, for the language file:

    art/lang/<design>_<part>.json        a part's lines     (cook_0, trade_a0, tales_t1, villages_v1, cook_lines ...)
    art/audio/<design>_lang.json         its sounds' subtitles (and art/audio/<design>_<stream>_lang.json, such as a
                                         voice stream's, trade_voices_lang.json, beside the sounds')

and for sounds.json:

    art/audio/<design>_sounds.json       its sound events (and art/audio/<design>_<stream>_sounds.json)

After the four designs come the voice tables every design's voices add to: art/audio/talk_sounds.json and
talk_lang.json, which `make_talk_voices.py table` writes whole, and townsfolk_sounds.json and townsfolk_lang.json,
which `make_townsfolk_voices.py fragments` writes whole. Their old lines are in the two files already, so only a
design's new talk families and crier lines are added; a part that runs `table` or `fragments` runs this after it.

Each fragment is a JSON object of its own. The designs go in the order the build plan accepts them (Hearthcraft, Trade,
Tales, Villages), a design's fragments by name, and each key a fragment brings is:

  - added at the end of the target, in the fragment's own order, if the target lacks it;
  - skipped, if the target has it with the same value;
  - refused, if the target has it with a different value, unless the key is in OVERRIDES, where it is replaced in place.

A fragment that is not valid JSON, that repeats a key, or that gives a key a different value than another fragment does is
refused; so is one whose values are not text (in the language file) or objects (in sounds.json), and so is a target that is
not in the form this script writes (json.dumps, 2-space indent, UTF-8 as it stands, one newline at the end), which is the
form both are in. When anything is refused nothing at all is written. A key is never removed or moved. A fragment that is
not there yet is skipped, with a note. A second run changes nothing, and does not touch the files.

Python 3 standard library only. Run from anywhere: it finds the repository from its own place.
"""

import glob
import json
import os
import sys

sys.dont_write_bytecode = True

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

RESOURCES = "src/main/resources/assets/guildmark"
LANG = RESOURCES + "/lang/en_us.json"
SOUNDS = RESOURCES + "/sounds.json"

# The designs in the order build-plan.md section 1 accepts them, each with the names its fragments may start with.
DESIGNS = (
    ("Hearthcraft", ("cook",)),
    ("Trade", ("trade",)),
    ("Tales", ("tales", "tale")),
    ("Villages", ("villages", "village")),
    # The shared voice tables, written whole by the voice scripts (see the head of this file)
    ("Voices", ("talk", "townsfolk")),
)

# Groups whose fragments are never reported as "not there yet"
QUIET = {"Voices"}

# What each target is merged from ({d} is each of a design's names), and what each of its values must be.
TARGETS = (
    (LANG, str, ("art/lang/{d}_*.json", "art/audio/{d}_lang.json", "art/audio/{d}_*_lang.json")),
    (SOUNDS, dict, ("art/audio/{d}_sounds.json", "art/audio/{d}_*_sounds.json")),
)

# Patterns a design may have nothing for, never reported as "not there yet"
OPTIONAL = {"art/audio/{d}_*_lang.json", "art/audio/{d}_*_sounds.json"}

# Keys a fragment may give a different value than the target already has; the target's is replaced where it stands.
OVERRIDES = {
    # Hearthcraft Part H: they now serve a cookshop as well as a stall
    "gui.guildmark.works.why.owner",
    "gui.guildmark.works.part.pitch.tip",
}


class Refused(Exception):
    pass


def path_of(rel):
    return os.path.join(ROOT, *rel.split("/"))


def no_repeats(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError("repeats the key " + json.dumps(key))
        out[key] = value
    return out


def no_constants(name):
    raise ValueError("has " + name + ", which is not JSON")


def read_json(rel):
    """The JSON object in [rel] (a path from the repository's root), and its text; Refused if it can't be read as one."""
    try:
        # Read with the newlines of whatever checkout this is (a Windows one may have turned the LFs to CRLFs); a file
        # that merging changes is written with LFs, which git takes as it takes any
        with open(path_of(rel), encoding="utf-8") as f:
            text = f.read()
    except (OSError, UnicodeDecodeError) as e:
        raise Refused("%s: can't be read: %s" % (rel, e))
    try:
        data = json.loads(text, object_pairs_hook=no_repeats, parse_constant=no_constants)
    except ValueError as e:
        raise Refused("%s: %s" % (rel, e))
    if not isinstance(data, dict):
        raise Refused("%s: is not a JSON object" % rel)
    return data, text


def canonical(data):
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def short(value, most=70):
    text = json.dumps(value, ensure_ascii=False)
    return text if len(text) <= most else text[:most - 3] + "..."


def fragment_files(patterns):
    """-> (files, pending): the fragments there are, in the order they merge, and the patterns nothing is there for yet."""
    files, pending = [], []
    for group, names in DESIGNS:
        for pattern in patterns:
            hits = []
            for name in names:
                p = pattern.format(d=name)
                if any(c in p for c in "*?["):
                    hits += sorted(os.path.relpath(x, ROOT).replace(os.sep, "/") for x in glob.glob(path_of(p)))
                elif os.path.isfile(path_of(p)):
                    hits.append(p)
            if hits:
                files += hits
            elif group not in QUIET and pattern not in OPTIONAL:
                pending.append(pattern.format(d=names[0]))
    return files, pending


def plan(target, kind, patterns, errors):
    """What merging into [target] does: -> (new text, or None if it stays as it is; added [(file, count)]; reworded; same; pending)."""
    try:
        data, raw = read_json(target)
    except Refused as e:
        errors.append(str(e))
        return None, [], [], 0, []
    if canonical(data) != raw:
        want, have = canonical(data).split("\n"), raw.split("\n")
        line = next((i for i in range(min(len(want), len(have))) if want[i] != have[i]), min(len(want), len(have)))
        errors.append(
            "%s: is not in the form this script writes (json.dumps, 2-space indent, UTF-8, one newline at the end); "
            "first difference at line %d. Take main's file and run this again" % (target, line + 1)
        )
        return None, [], [], 0, []
    files, pending = fragment_files(patterns)
    wanted = {}  # key -> (value, the fragment that first brought it), in the order brought
    for rel in files:
        try:
            fragment, _ = read_json(rel)
        except Refused as e:
            errors.append(str(e))
            continue
        for key, value in fragment.items():
            if not key:
                errors.append("%s: has an empty key" % rel)
            elif not isinstance(value, kind):
                errors.append("%s: %s is %s, not %s" % (rel, json.dumps(key), short(value, 40), "text" if kind is str else "an object"))
            elif key in wanted:
                if wanted[key][0] != value:
                    errors.append(
                        "%s: %s is %s, but %s says %s" % (rel, json.dumps(key), short(value), wanted[key][1], short(wanted[key][0]))
                    )
            else:
                wanted[key] = (value, rel)
    merged = dict(data)
    added, reworded, same = {}, [], 0
    for key, (value, rel) in wanted.items():
        if key not in data:
            merged[key] = value
            added[rel] = added.get(rel, 0) + 1
        elif data[key] == value:
            same += 1
        elif key in OVERRIDES:
            merged[key] = value
            reworded.append((rel, key))
        else:
            errors.append(
                "%s: %s is %s in %s, and the fragment says %s. A fragment may not change a line that is already there "
                "(a deliberate reword goes in OVERRIDES at the head of merge_lang.py)"
                % (rel, json.dumps(key), short(data[key]), target, short(value))
            )
    text = canonical(merged)
    return (None if text == raw else text), sorted(added.items(), key=lambda kv: files.index(kv[0])), reworded, same, pending


def main(argv):
    check = "--check" in argv
    if any(a != "--check" for a in argv):
        print(__doc__.split("\n\n")[0].strip(), file=sys.stderr)
        print("usage: merge_lang.py [--check]", file=sys.stderr)
        return 2
    errors, results = [], []
    for target, kind, patterns in TARGETS:
        results.append((target,) + plan(target, kind, patterns, errors))
    if errors:
        print("Refused; nothing was written:", file=sys.stderr)
        for e in errors:
            print("  " + e, file=sys.stderr)
        return 1
    changed = False
    for target, text, added, reworded, same, pending in results:
        total = sum(n for _, n in added)
        if text is None:
            print("%s: nothing to change (%d line%s from fragments already there)" % (target, same, "" if same == 1 else "s"))
        else:
            changed = True
            print("%s: %s %d line%s%s" % (
                target, "would add" if check else "added", total, "" if total == 1 else "s",
                (", %s %d" % ("would reword" if check else "reworded", len(reworded))) if reworded else "",
            ))
            for rel, n in added:
                print("    %s: %d" % (rel, n))
            for rel, key in reworded:
                print("    %s: reworded %s" % (rel, key))
            if not check:
                temp = path_of(target) + ".merging"
                with open(temp, "w", encoding="utf-8", newline="") as f:
                    f.write(text)
                os.replace(temp, path_of(target))
        if pending:
            print("    not there yet, skipped: " + ", ".join(pending))
    return 1 if check and changed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
