"""
Keeps the townsfolk's heads from snapping round: smooths the head track of every baked clip in a folder whose head turns
faster than animkit.HEAD_CAP deg a tick (against its body and in the world both), the same pass animkit's Clip.write
runs on every townsfolk clip it writes. For clips already baked, without running their scripts again. Run from the
repo root:

    python3 -B art/scripts/steady_heads.py src/main/resources/assets/guildmark/animations/townsfolk [--check]

--check only lists what would change (and exits 1 if anything would).
"""
import glob, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from animkit import steady_head, HEAD_CAP


def main(argv):
    folder = argv[1]
    check = '--check' in argv[2:]
    changed = 0
    for path in sorted(glob.glob(os.path.join(folder, '*.json'))):
        with open(path) as f:
            data = json.load(f)
        done = steady_head(data)
        if not done:
            continue
        changed += 1
        print('%s %-28s head %.0f -> %.0f deg a tick at most'
              % ('!!' if done[1] > HEAD_CAP else '  ', os.path.basename(path), *done))
        if not check:
            with open(path, 'w') as f:
                json.dump(data, f, separators=(',', ':'))
    print(('would steady' if check else 'steadied') + f' {changed} clip(s)')
    return 1 if check and changed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
