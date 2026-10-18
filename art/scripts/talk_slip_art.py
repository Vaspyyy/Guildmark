"""The talk of the town's paper for the market board (living world M5, memory and rumours): a slip of soft blue-grey
rag paper, its fibres showing, with two heads inked in profile in the bottom right corner, leaning together over a
whisper, and a few spatters where the pen was shaken.

market_board_art.py paints every slip's paper; this gives it the talk's. To take it in there:
  import talk_slip_art as TALK
  SLIP_KINDS = [..., 'year', 'talk']        (last, so the other papers keep their places in the atlas)
  SLIP_KEEP['talk'] = TALK.KEEP
  PAPERS['talk'] = TALK.PALETTE
  in paper(), with the other kinds' decorations:   elif kind == 'talk': TALK.decorate(cv, m, pal, seed)
then run market_board_art.py again (it writes the slips atlas and MarketBoardArt.kt) and add TALK("talk") to
MarketBoardBlockEntity.Slip.Kind. Until then a talk slip is drawn on the word paper.

    python3 -B art/scripts/talk_slip_art.py <preview.png>

paints the paper on its own (market_board_art.py's paper with this decoration, as the atlas would have it) and writes
it eight times over for a look.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import PAL, grow, rand, seg_dist

W, H = 64, 32

# Blue-grey rag, dark to light
PALETTE = [(46, 52, 62), (86, 96, 110), (128, 138, 152), (166, 175, 186), (196, 203, 212), (222, 226, 233)]

# The corner the heads take (texels of the 64x32 paper; the slip's words keep clear of it)
KEEP = (44, 11, 64, 32)

INK = PAL['ink']

# One gossip in profile, facing right: the back of the head round, the brow, the nose, the lips and chin, the neck and
# the shoulders. The head (the first eight rows) leans one texel toward the other, off the neck.
FIGURE = [
    "..####..",
    ".######.",
    "#######.",
    "#######.",
    "########",
    "######..",
    "#######.",
    ".#####..",
    "..###...",
    "..###...",
    ".######.",
    "########",
]
HEAD_ROWS = 8

# Where each figure's box sits (its left column and top row), which way it faces, and its lean
FIGURES = [(45, 18, 1), (56, 18, -1)]

# The whisper rising from between their faces (texel, strength), and the spatter about the corner
WHISPER = [((54, 16), 0.85), ((55, 14), 0.6), ((54, 12), 0.38)]
SPATTER = [(48, 13), (47, 15), (61, 13), (62, 15)]


def figures():
    """The two figures' ink, as a mask, and the texels at the back of their heads (where the ink is laid thinner)."""
    ink = np.zeros((H, W), bool)
    back = np.zeros((H, W), bool)
    for (x0, y0, face) in FIGURES:
        for row, line in enumerate(FIGURE):
            lean = face if row < HEAD_ROWS else 0
            cells = line if face > 0 else line[::-1]
            first = cells.find('#') if face > 0 else cells.rfind('#')
            for col, c in enumerate(cells):
                if c != '#':
                    continue
                x, y = x0 + col + lean, y0 + row
                if 0 <= x < W and 0 <= y < H:
                    ink[y, x] = True
                    if row < HEAD_ROWS and col == first:
                        back[y, x] = True
    return ink, back


def decorate(cv, m, pal, seed):
    """The talk's paper on [cv] (its paper mask [m], its palette [pal]): the rag's fibres, the two heads and the spatter."""
    rng = np.random.default_rng(seed + 7)
    # Rag: short fibres caught in the paper, most a little darker, some lighter
    for k in range(18):
        x0, y0 = rng.uniform(2, W - 2), rng.uniform(2, H - 3)
        a = rng.uniform(0, np.pi)
        n = rng.uniform(1.5, 3.5)
        d, _ = seg_dist(x0, y0, x0 + np.cos(a) * n, y0 + np.sin(a) * n, W, H)
        cv.over((d < 0.45) & m, pal[2] if k % 3 else pal[5], 0.3)
    ink, back = figures()
    ink &= m
    spatter = np.zeros((H, W), bool)
    for (x, y) in SPATTER:
        spatter[y, x] = True
    spatter &= m
    # Ink bleeds a little into rag: a faint grey edge round the figures
    cv.over(grow(ink, 1) & ~ink & m, pal[2], 0.25)
    cv.put(ink, INK[0])
    # The backs of their heads laid thinner, and a little unevenness over the rest
    cv.over(back & ink, INK[2], 0.6)
    cv.over(ink & ~back & (rand(W, H, seed + 9) > 0.86), INK[1], 0.6)
    cv.over(spatter, INK[1], 0.55)
    for (x, y), strength in WHISPER:
        if m[y, x]:
            cv.over(_at(x, y), INK[1], strength)


def _at(x, y):
    """A mask of the one texel ([x], [y])."""
    a = np.zeros((H, W), bool)
    a[y, x] = True
    return a


def main():
    out = sys.argv[1]
    import market_board_art as MB
    MB.PAPERS['talk'] = PALETTE
    seed = 300 + (MB.SLIP_KINDS.index('talk') if 'talk' in MB.SLIP_KINDS else len(MB.SLIP_KINDS))
    cv = MB.paper('talk', seed)
    m = cv.a > 0.02
    decorate(cv, m, PALETTE, seed)
    cv.image().resize((W * 8, H * 8), Image.NEAREST).save(out)
    print('wrote', out)


if __name__ == '__main__':
    main()
