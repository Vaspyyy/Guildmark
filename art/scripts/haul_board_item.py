"""The Haul Board's item: its 32 x 32 icon (the board seen from the front, as the market board's icon is), its item definition and its
model. Imported by haul_board_art.py, which writes them with the board's other files.

The icon moves, as the trade's other items do: the tickets on their pegs stir now and then, and a light crosses the gilt of the crest
and the straw of its sheaf. It rests 98 ticks between movements (the Tally rests 64 and 44, the Warrant 82, the harness 58, the whistle
70, the cart 76 and the bill 90, so a hotbar of them is never in step). The strip's `.mcmeta` plays it.

Painted with trade_items.py's small tools (hdkit's, on the 32 x 32 sprite) in the same house style: a few hard tones to a material,
light from the upper left, a fine dark outline round the whole.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import trade_items as TI
from hdkit import PAL, Canvas, poly
from market_board_art import OAK

N = TI.N
XS, YS = TI.XS, TI.YS
REST = 98                                       # ticks the resting frame is held
STIR = [(1, 0, 0, 0), (0, 0, 1, -1), (-1, 1, 0, 0), (0, -1, -1, 1)]     # how far each ticket's lower rows are off their rest, px (frames 1..4)
INDIGO = TI.INDIGO
STRAW = [(60, 40, 14), (112, 80, 28), (166, 126, 50), (206, 168, 78), (234, 200, 112), (250, 228, 156)]
CHALK = (236, 231, 220)
CHALK_HEAD = (226, 212, 160)
ROAD = {'g': TI.ROAD_GREEN, 'a': TI.ROAD_AMBER, 'r': TI.ROAD_RED}
CHIP_RED = (200, 62, 54)
CHIP_GREEN = (92, 156, 82)


def nb(pal, t, d=0.25):
    return TI.nb(pal, t, d)


def put_rect(cv, x0, y0, x1, y1, pal, t, d=0.0):
    cv.put(TI.R(x0, y0, x1, y1), nb(pal, t, d))


def col(c):
    return np.asarray(c, float)


def posts(cv):
    """The two posts, standing from the header to the ground, each in a stone collar at its foot."""
    for px in (2, 27):
        m = TI.R(px, 11, px + 3, 31)
        t = 0.44 + 0.2 * (XS - px < 1) - 0.18 * (XS - px >= 2) + 0.08 * (TI.vnoise(N, N, 2, px) - 0.5)
        cv.put(m, nb(PAL['darkwood'], t))
        put_rect(cv, px - 1, 29, px + 4, 31, PAL['stone'], 0.5 + 0.2 * (YS < 29.9))
        cv.px(px + 1, 29, col(PAL['stone'][4]))


def roof(cv):
    m = poly([(0, 9.6), (16, 4.6), (32, 9.6), (32, 10.4), (0, 10.4)], N)
    row = np.floor((YS - 4.6) / 1.8)
    shingle = ((XS + row * 1.5) % 3.0) < 0.7
    cv.put(m, nb(PAL['slate'], 0.5 - 0.04 * row - 0.22 * shingle + 0.1 * (XS < 16), 0.3))


def crest(cv):
    """The crest over the ridge: a little cap, an oak frame round an indigo field, a gilt wheel and a straw sheaf in it."""
    cap = poly([(9.0, 2.7), (16, 0.2), (23.0, 2.7), (23.0, 3.4), (9.0, 3.4)], N)
    cv.put(cap, nb(PAL['slate'], 0.52 - 0.18 * (YS > 2.9) + 0.1 * (XS < 16), 0.2))
    put_rect(cv, 10, 3, 22, 7, OAK, 0.5 + 0.18 * (YS < 3.9) - 0.15 * (YS > 5.9))
    put_rect(cv, 11, 4, 21, 7, INDIGO, 0.42, 0.0)
    cv.over(TI.R(11, 4, 21, 5), (80, 100, 160), 0.18)
    TI.pix(cv, ['.G.', 'GkG', '.G.'], 17, 4, {'G': PAL['gold'][4], 'k': PAL['gold'][2]})
    TI.pix(cv, ['.s.', 'sss', '.t.'], 12, 4, {'s': STRAW[4], 't': PAL['gold'][3]})
    cv.px(13, 4, col(STRAW[5]))


def header(cv):
    put_rect(cv, 1, 9, 31, 12, PAL['darkwood'], 0.5 + 0.18 * (YS < 9.9) - 0.2 * (YS > 10.9))
    cv.px(4, 10, col(PAL['gold'][4]))
    cv.px(27, 10, col(STRAW[4]))


def slate(cv):
    """The slate and its chalk: the head line, then a row of dashes for a name, a red chip, a green chip and a road's flag."""
    m = TI.R(6, 12, 18, 23)
    cv.put(m, nb(PAL['slate'], 0.2 + 0.05 * (TI.vnoise(N, N, 4, 7) - 0.5) - 0.1 * ((YS < 13) | (XS < 7)), 0.3))
    cv.over(TI.R(6, 12, 18, 13), PAL['slate'][0], 0.6)
    for i in range(7, 14):
        cv.px(i, 13, col(CHALK_HEAD))
    rows = [(4, 'r', 'g', 'g'), (3, 'r', None, 'a'), (5, 'rr', 'g', 'r'), (4, None, 'g', 'g')]
    for k, (nm, red, green, road) in enumerate(rows):
        y = 15 + 2 * k
        for i in range(nm):
            cv.px(7 + i, y, col(CHALK))
        for j in range(len(red or '')):
            cv.px(12 + j, y, col(CHIP_RED))
        if green:
            cv.px(14, y, col(CHIP_GREEN))
        cv.px(16, y, col(ROAD[road]))
        cv.px(17, y, col(ROAD[road]) * 0.72)


def ledge_and_rail(cv):
    put_rect(cv, 6, 23, 27, 24, PAL['wood'], 0.5 + 0.2 * (YS < 23.9))
    cv.put(TI.R(9, 22, 11, 23), col(CHALK))
    put_rect(cv, 5, 24, 28, 26, PAL['darkwood'], 0.45 + 0.2 * (YS < 24.9) - 0.15 * (YS > 25.1))


def panel(cv):
    put_rect(cv, 18, 12, 19, 23, PAL['darkwood'], 0.5)
    k3 = np.floor(XS - 19) % 3
    put_rect(cv, 19, 12, 27, 23, PAL['darkwood'], 0.46 + 0.1 * (k3 == 0) - 0.2 * (k3 == 2))
    put_rect(cv, 19, 17, 27, 18, PAL['darkwood'], 0.34)


def tickets(cv, stir=(0, 0, 0, 0)):
    """Four tickets on their pegs, two tiers of two, each three pixels wide: a peg head over the eyelet, a chip, a line of writing and
    a notched foot. [stir] moves the lower two rows of each a pixel to one side (a ticket stirring on its peg)."""
    paper = PAL['parchment']
    spots = [(20, 13, 0, 'plain'), (24, 13, 1, 'seal'), (20, 18, 2, 'plain'), (24, 18, 3, 'plain')]
    for (x, y, k, kind) in spots:
        s = stir[k]
        for j in range(4):
            dx = s if j >= 2 else 0
            for i in range(3):
                if j == 3 and i == 1:
                    continue                          # the notch in the foot
                t = 0.84 - 0.1 * (j == 3) - 0.12 * (i == 2) + 0.06 * (i == 0)
                cv.px(x + i + dx, y + j, nb(paper, t, 0)[0, 0])
        cv.px(x + 1, y, col(PAL['steel'][4]))                                       # the peg's head in front of the eyelet
        cv.px(x, y + 1, col(CHIP_RED if kind != 'seal' else CHIP_GREEN))
        cv.px(x + 2, y + 1, nb(paper, 0.5, 0)[0, 0])
        cv.px(x + s, y + 2, col(TI.INK))                                            # a line of writing: it stirs with the foot
        cv.px(x + 1 + s, y + 2, col(TI.INK) * 1.3 + 30)


def straps(cv):
    for (x0, x1) in ((1, 6), (26, 31)):
        put_rect(cv, x0, 11, x1, 12, PAL['iron'], 0.5)
        cv.px(x0 + 2, 11, col(PAL['iron'][5]))


def icon_frame(stir=(0, 0, 0, 0)):
    """The icon: the board from the front, its slate chalked up, four tickets and the crest over its cap.
    [stir] is the offset of the lower rows of the four tickets (0 at rest)."""
    cv = Canvas(N)
    posts(cv)
    ledge_and_rail(cv)
    panel(cv)
    slate(cv)
    header(cv)
    roof(cv)
    crest(cv)
    straps(cv)
    tickets(cv, stir)
    return TI.finish(cv)


# ================================================================ the strip and its files

SHINE = [PAL['gold'], STRAW, PAL['steel']]
TIMELINE = [(0, REST), 1, 2, 3, 4, (0, 20)] + list(range(5, 5 + TI.SWEEP))      # rest, the tickets stir, rest, a light crosses


def frames():
    rest = icon_frame()
    stir = [icon_frame(s) for s in STIR]
    return [rest] + stir + TI.sweep_frames(rest, SHINE)


def definition():
    return {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/haul_board'}}


def model_json():
    return {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/haul_board'}}


def write(assets):
    """Writes textures/item/haul_board.png (+ .mcmeta), models/item/haul_board.json and items/haul_board.json; gives the frames."""
    fr = frames()
    path = os.path.join(assets, 'textures', 'item', 'haul_board.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    TI.strip_image(fr).save(path, optimize=True)
    TI.write_json(path + '.mcmeta', TI.mcmeta(TIMELINE))
    TI.write_json(os.path.join(assets, 'models', 'item', 'haul_board.json'), model_json())
    TI.write_json(os.path.join(assets, 'items', 'haul_board.json'), definition())
    return fr


def verify(assets, frames):
    """What is wrong with the icon's files (empty when all is well): the strip fits its .mcmeta, starts on its resting frame and holds
    it longest, shows every frame, rests unlike any other trade item, has no fully saturated pixel and nothing bright at its edge."""
    problems = []
    path = os.path.join(assets, 'textures', 'item', 'haul_board.png')
    im = Image.open(path)
    if im.size != (N, N * len(frames)):
        problems.append('haul_board icon: the strip is %d x %d, not %d x %d' % (im.width, im.height, N, N * len(frames)))
    with open(path + '.mcmeta') as f:
        plays = TI.playlist([e if isinstance(e, int) else (e['index'], e['time']) for e in json.load(f)['animation']['frames']])
    if any(not 0 <= i < len(frames) or t < 1 for i, t in plays):
        problems.append('haul_board icon: the .mcmeta plays a frame that is not there')
    if plays[0][0] != 0 or max(t for _, t in plays) != plays[0][1]:
        problems.append('haul_board icon: the loop does not start on its resting frame, held the longest')
    if len({i for i, _ in plays}) != len(frames):
        problems.append('haul_board icon: a frame is never shown')
    if plays[0][1] in {TI.playlist(tl)[0][1] for name, (_, tl) in TI.STRIPS.items() if name not in TI.LOOPING}:
        problems.append('haul_board icon: it rests as long as another trade item (%d ticks)' % plays[0][1])
    for k, fr in enumerate(frames):
        a = np.asarray(fr.convert('RGBA'), int)
        solid = a[..., 3] > 0
        if (solid & (a[..., :3].max(-1) >= 200) & (a[..., :3].min(-1) <= 4)).any():
            problems.append('haul_board icon frame %d: a fully saturated pixel' % k)
        border = np.zeros_like(solid)
        border[0], border[-1], border[:, 0], border[:, -1] = True, True, True, True
        if (solid & border & (a[..., :3].max(-1) > 70)).any():
            problems.append('haul_board icon frame %d: something bright touches the edge of the frame' % k)
        if not solid.any():
            problems.append('haul_board icon frame %d is empty' % k)
    for rel in ('items/haul_board.json', 'models/item/haul_board.json'):
        if not os.path.exists(os.path.join(assets, rel)):
            problems.append('haul_board icon: %s was not written' % rel)
    return problems
