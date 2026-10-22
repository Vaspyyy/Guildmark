"""Writes art/lang/tales_h.json, the tales' voice lines as the player reads them, whether or not a take is recorded
(design 13.5.4, 13.6):

    say.guildmark.talk.<family>_<n>   the typed text of every wording of every family of every tier (380), which is also
                                      the subtitle of its take
    say.guildmark.crier_tale_good ... the crier's seven lines (13.5.6), whose text the chat line and the board slip show
                                      whether or not make_townsfolk_voices.py has recorded them

`make_talk_voices.py table` writes the same text into talk_lang.json for the tiers in RECORDED, and make_townsfolk_voices.py
`fragments` writes the crier's into townsfolk_lang.json, so merge_lang.py meets each key with the same value from every
fragment and only adds it once. Run from anywhere:

    python3 -B art/audio/tale_lines/lang.py          writes the fragment, then runs tale_lines.check()
    python3 -B art/audio/tale_lines/lang.py --check  writes nothing: says whether the fragment is current, and runs check()

then `python3 -B art/lang/merge_lang.py`, which adds the lines to lang/en_us.json.
"""
import json
import os
import sys

sys.dont_write_bytecode = True

if __package__ in (None, ""):  # run as a script: the package is in the folder above
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import tale_lines  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
FRAGMENT = os.path.join(ROOT, "art", "lang", "tales_h.json")
REL = "art/lang/tales_h.json"


def entries():
    """{language key: text}: the typed text of every wording, in the order of the table, then the crier's seven"""
    out = {}
    for family, (_, _, words) in tale_lines.families().items():
        for n, text in enumerate(words):
            out[f"say.guildmark.talk.{family}_{n}"] = text
    for key, text in tale_lines.CRIER.items():
        out[f"say.guildmark.{key}"] = text
    return out


def canonical(data):
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def stale():
    """None if the fragment is there and is exactly what entries() gives; else what is wrong with it"""
    try:
        with open(FRAGMENT, encoding="utf-8") as f:
            have = f.read()
    except OSError:
        return f"{REL} is not written: run python3 -B art/audio/tale_lines/lang.py"
    if have != canonical(entries()):
        return f"{REL} is out of date: run python3 -B art/audio/tale_lines/lang.py"
    return None


def main(argv):
    check_only = "--check" in argv
    if any(a != "--check" for a in argv):
        print(__doc__.strip().split("\n\n")[0], file=sys.stderr)
        return 2
    if not check_only:
        text = canonical(entries())
        try:
            with open(FRAGMENT, encoding="utf-8") as f:
                same = f.read() == text
        except OSError:
            same = False
        if same:
            print(f"{REL}: already current")
        else:
            with open(FRAGMENT, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            print(f"{REL}: written, {len(entries())} lines")
    try:
        print(tale_lines.check())
    except ValueError as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
