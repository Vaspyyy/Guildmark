"""The islands' small art: the map icons and compass marks of the seven kinds of island, the gull egg and the seared egg, the gull nest and the
lighthouse lamp (blocks, with their models) and the lighthouse's beam.

    python3 -B art/scripts/island_art.py [assets dir [preview dir]]

With no arguments it writes into the repo's assets (`src/main/resources/assets/guildmark`; a `-` for the assets dir means that too) and nothing else. Everything is written the way the older
art is: the map icons and compass marks are 11x11 and 7x7 designs (rows of letters, as `ui_art.py` draws its own) put through `ui_hd.remaster`, so they
stand exactly beside the older markers at 22x22 and 14x14; the items and the blocks' textures are 32x, painted with `hdkit.py` in the same dark-fantasy
look as `items_hd.py`; the models are the JSON the game reads, written here, so the geometry and the textures painted for it are made together, and
`island_blockview.py` draws them without the game. Imports only; nothing older is changed. Everything is seeded: a rerun gives the same files. With a
preview dir it also writes `island_art.png`, a contact sheet of all of it, magnified, with the blocks drawn from their own model files.

Files written (under the assets dir):
    textures/gui/sprites/map/island.png, island_lighthouse.png, island_wreck.png, island_cove.png, island_shrine.png, island_stacks.png,
        island_haven.png                                            22x22   (the wild isle is the plain `island`)
    textures/gui/sprites/compass/ the same seven                     14x14
    textures/item/gull_egg.png, seared_gull_egg.png                 32x32 (+ models/item/*.json, items/*.json)
    textures/block/gull_nest.png (the twig sheet), gull_nest_top.png (the lining), gull_nest_egg.png (the shell)         32x32
    models/block/gull_nest_0.json .. gull_nest_3.json, blockstates/gull_nest.json (eggs=0..3), items/gull_nest.json
    textures/block/lighthouse_lamp.png (brass), lighthouse_lamp_lens.png (the glass); lit: lighthouse_lamp_core.png (the flame),
        lighthouse_lamp_glow.png and _glow_1/_2/_3.png (the light on the glass, a shimmer that goes round the four faces); out: lighthouse_lamp_wick.png
        (the mantle and the banked ember), lighthouse_lamp_glint.png and _glint_1.png (the sun on the glass); the animated ones with a .mcmeta
    models/block/lighthouse_lamp.json (lit), lighthouse_lamp_unlit.json, blockstates/lighthouse_lamp.json (lit=true, lit=false),
        items/lighthouse_lamp.json
    textures/entity/island/lighthouse_beam.png (+.mcmeta), lighthouse_beam_tile.png (+.mcmeta)
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import PAL, Canvas, grid, vnoise, fbm, rand, bands, poly, seg_dist, ellipse, dist_in, light, cavity, rim, outline
import ui_art
import ui_hd

N = 32


# ================================================================ the map icons and the compass marks

# The map's palette is ui_art's (ink "k", soft ink "s", red ink "r", red "R", gold "g", blue "b", green "e", paper "w", greys "P" "q" "Q") with a
# little more: "y" the pale gold of a lit lamp, "t" a drowned thing's sea-green glow.
MAP_PAL = dict(ui_art.MAP_PAL)
MAP_PAL.update({'y': (236, 206, 124, 255), 't': (46, 140, 132, 255)})
# The compass marks' palette is ui_art's (outline "o", gold "g" "G" "d", bone "w" "W", red "r" "R", blue "b" "B", brown "n" "N", green "e" "E",
# iron "i" "I", greys "P" "q" "Q") with "t" "T" for the sea-green glow.
MARK_PAL = dict(ui_art.MARK_PAL)
MARK_PAL.update({'t': (64, 176, 164, 255), 'T': (150, 232, 214, 255)})

# Seven kinds of island: wild land, a lighthouse on its rock, a ship broken on a reef, a smugglers' cove, a drowned shrine, sea stacks thick with
# gulls and a fishing haven. 11x11 for the map, drawn in ink on parchment; 7x7 for the compass, outlined in black over the world.
MAP_ICONS = {
    # a palm over a green hill and its beach, the sea round the foot
    'island': [
        "...........",
        "...ee.ee...",
        "..eeeeeee..",
        "...eekee...",
        "......k....",
        ".....k.....",
        "..kkkkkkk..",
        ".keeeeeeeek",
        "kgggggggggk",
        ".kkkkkkkkk.",
        "b.bb.bb.bb.",
    ],
    # a banded tower on its rock, the lamp lit
    'lighthouse': [
        "....kkk....",
        "...kyyyk...",
        "...kyyyk...",
        "....kkk....",
        "....kwk....",
        "....kRk....",
        "....kwk....",
        "...kRRRk...",
        "...kwwwk...",
        "..kRRRRRk..",
        ".kQQQQQQQk.",
    ],
    # a hull on a reef, ribs and two broken masts and a rag, the sea over its stern
    'wreck': [
        "...........",
        "....k...R..",
        "....kk.RR..",
        "....k..R...",
        "..k.k..k...",
        ".kk.kk.k.k.",
        "kskskskskk.",
        "ks.ks.ks.k.",
        ".kkssssskk.",
        "bbbkkkkkbbb",
        ".b.b..b.b.b",
    ],
    # the sea cave at the back of a cove, a lantern lit in it
    'cove': [
        "...........",
        "..kkkkkkk..",
        ".kQQQQQQQk.",
        "kQQkkkkkQQk",
        "kQk.....kQk",
        "kQk..k..kQk",
        "kQk.kyk.kQk",
        "kkk.....kkk",
        "b.bb.bb.bb.",
        ".b..b..b..b",
        "...........",
    ],
    # a drowned temple, its lintel lit with a sea-green eye, half under the sea
    'shrine': [
        "...........",
        "...kkkkk...",
        "..kQQtQQk..",
        ".kkkkkkkkk.",
        "..kQk.kQk..",
        "..kQk.kQk..",
        "bbbbbbbbbbb",
        "b.bb.bb.bb.",
        ".bbbbbbbbb.",
        "...........",
        "...........",
    ],
    # two grass-capped stacks standing in the sea, the gulls over them
    'stacks': [
        "......k.k..",
        ".......k...",
        "..kkk......",
        ".keeek..k.k",
        ".kqQsk...k.",
        ".kqQsk.kk..",
        ".kqQsk.kek.",
        ".kQQsk.kQsk",
        "kqQQssk.ssk",
        "bkkkkkkkkkb",
        ".b..b..b..b",
    ],
    # an anchor over the water: the fishing haven
    'haven': [
        "....kkk....",
        "....k.k....",
        "....kkk....",
        "..kkkkkkk..",
        ".....k.....",
        ".....k.....",
        "k....k....k",
        "kk...k...kk",
        ".kk..k..kk.",
        "..kkkkkkk..",
        "bbbbbbbbbbb",
    ],
}

COMPASS_ICONS = {
    'island': [
        "..oEo..",
        ".oEeEo.",
        "oEeoeEo",
        "..oNo..",
        ".oNNNo.",
        "ogggggo",
        ".bobobo",
    ],
    'lighthouse': [
        "..ooo..",
        ".oGGGo.",
        ".ooooo.",
        "..owo..",
        "..oro..",
        ".owwwo.",
        "orrrrro",
    ],
    'wreck': [
        "..o.o.R",
        "..ono.R",
        "..onoRo",
        ".onnnno",
        "onononn",
        "obbbbbo",
        "bbbbbbb",
    ],
    'cove': [
        ".ooooo.",
        "oIIIIIo",
        "oIbbbIo",
        "oIbbbIo",
        "oIbGbIo",
        "ooobooo",
        "bbbbbbb",
    ],
    'shrine': [
        ".ooooo.",
        "oIITIIo",
        "ooooooo",
        "oIIoIIo",
        "oIIoIIo",
        "bbbbbbb",
        ".bbbbb.",
    ],
    'stacks': [
        "....W.W",
        ".oo..W.",
        "oQIo...",
        "oQIoooo",
        "oQIoQIo",
        "oQIoQIo",
        "bbbbbbb",
    ],
    'haven': [
        "..ooo..",
        "..oWo..",
        ".ooWoo.",
        "o.oWo.o",
        "owoWowo",
        ".owWwo.",
        "..ooo..",
    ],
}

KINDS = ['island', 'lighthouse', 'wreck', 'cove', 'shrine', 'stacks', 'haven']


def sprite_name(kind):
    """The file a kind's map icon and compass mark are saved as: the wild isle is the plain `island`."""
    return 'island' if kind == 'island' else f'island_{kind}'


def map_icon(kind):
    rows = MAP_ICONS[kind]
    assert len(rows) == 11 and all(len(r) == 11 for r in rows), kind
    return ui_hd.remaster(ui_art.from_rows(rows, MAP_PAL), seed=40 + KINDS.index(kind), thin=True)


def compass_mark(kind):
    rows = COMPASS_ICONS[kind]
    assert len(rows) == 7 and all(len(r) == 7 for r in rows), kind
    return ui_hd.remaster(ui_art.from_rows(rows, MARK_PAL), seed=60 + KINDS.index(kind), thin=False)


# ================================================================ the gull egg and the seared egg

# A gull's egg: a blue-green-grey shell, dark to light, with the brown of its speckle.
SHELL = [(28, 40, 48), (58, 82, 88), (104, 134, 132), (148, 176, 164), (190, 210, 188), (228, 238, 214)]
SPECK = [(40, 32, 28), (66, 52, 38), (96, 80, 56)]
WHITE = [(58, 48, 50), (120, 108, 104), (178, 168, 152), (218, 212, 192), (244, 240, 222), (255, 252, 240)]
SEAR = [(30, 18, 14), (62, 34, 20), (104, 60, 28), (146, 92, 40), (186, 128, 60), (216, 164, 90)]
YOLK = [(96, 40, 10), (160, 76, 14), (214, 118, 20), (242, 164, 36), (254, 204, 74), (255, 238, 150)]


def egg_field(cx, cy, rx, ry, tilt_deg, skew=0.16):
    """An egg's outline and dome: (inside, height, u across, v along the axis: -1 the sharp end, +1 the blunt end)."""
    xs, ys = grid(N)
    a = math.radians(tilt_deg)
    dx, dy = xs - cx, ys - cy
    u = (dx * math.cos(a) + dy * math.sin(a)) / rx
    v = (-dx * math.sin(a) + dy * math.cos(a)) / ry
    w = 1.0 + skew * v                  # wider toward the blunt end (v > 0)
    uu = u / np.maximum(w, 0.2)
    d2 = uu * uu + v * v
    inside = d2 <= 1
    h = np.sqrt(np.clip(1 - d2, 0, 1))
    return inside, h, u, v, uu


def gull_egg():
    """The gull's egg, lying a little tilted: a pale blue-green shell, soft marks under the glaze, brown speckle thickest at the blunt end."""
    cv = Canvas(N)
    inside, h, u, v, uu = egg_field(15.5, 17.0, 9.0, 12.2, 16.0)
    L = light(h * 6.0, 1.0)
    grain = fbm(N, N, 3, 11)
    t = np.where(inside, 0.56 + 0.85 * L + 0.10 * (grain - 0.5), 0)
    cv.put(inside, bands(SHELL, t, 0.55))
    rng = np.random.default_rng(5)
    under = fbm(N, N, 5, 31)
    cv.over(inside & (under > 0.70), (120, 122, 146), 0.35)
    # speckle: spread so that none touch, thicker toward the blunt end, a few of two texels
    placed = []
    tries = 0
    while len(placed) < 46 and tries < 4000:
        tries += 1
        px, py = rng.uniform(4, 28), rng.uniform(3, 30)
        ix, iy = int(px), int(py)
        if not inside[iy, ix]:
            continue
        keep = 0.22 + 0.78 * np.clip((v[iy, ix] + 1.0) / 2.0, 0, 1) ** 1.4
        if rng.random() > keep:
            continue
        if any((ix - qx) ** 2 + (iy - qy) ** 2 < 5.5 for qx, qy, _ in placed):
            continue
        placed.append((ix, iy, rng.random() < 0.18))
    for ix, iy, big in placed:
        col = np.array(SPECK[0] if rng.random() < 0.55 else SPECK[1], float)
        cells = [(0, 0)] + ([(1, 0)] if big and rng.random() < 0.5 else []) + ([(0, 1)] if big and rng.random() < 0.5 else [])
        for dx, dy in cells:
            x, y = ix + dx, iy + dy
            if 0 <= x < N and 0 <= y < N and inside[y, x]:
                cv.rgb[y, x] = col * (1.0 + 0.55 * max(L[y, x], 0))
    # a few olive-brown blotches near the blunt end
    for _ in range(14):
        ix, iy = int(rng.uniform(7, 25)), int(rng.uniform(17, 29))
        if not inside[iy, ix] or any((ix - qx) ** 2 + (iy - qy) ** 2 < 4.0 for qx, qy, _ in placed):
            continue
        cv.rgb[iy, ix] = np.array(SPECK[2], float) * (1 + 0.5 * max(L[iy, ix], 0))
    cv.a[inside] = 1
    rim(cv, 0.38)
    outline(cv, 0.8)
    # a pale gloss on the upper left, two points and a short streak
    for (gx, gy, c) in ((9, 9, (238, 246, 232)), (10, 8, (222, 234, 218)), (9, 10, (214, 228, 212)), (11, 7, (206, 222, 206))):
        if cv.a[gy, gx] > 0:
            cv.px(gx, gy, c)
    return cv.image()


def seared_gull_egg():
    """The egg seared in a pan: a ragged white gone brown and lacy at the rim, an amber yolk, two bits of speckled shell."""
    xs, ys = grid(N)
    cx, cy = 15.5, 15.5
    ang = np.arctan2(ys - cy, xs - cx)
    rr = np.hypot(xs - cx, ys - cy)
    R = 11.6 + 0.9 * np.sin(2 * ang + 0.6) + 0.8 * np.sin(3 * ang + 2.0) + 0.55 * np.sin(5 * ang + 4.1) + 0.35 * np.sin(8 * ang + 1.3)
    white = rr <= R
    cv = Canvas(N)
    depth = dist_in(white, 12)
    h = np.clip(depth / 5.0, 0, 1) ** 0.7 * 2.2 + 0.3 * fbm(N, N, 3, 17)
    L = light(h, 1.2)
    grain = fbm(N, N, 3, 19)
    t = 0.66 + 0.9 * L + 0.10 * (grain - 0.5)
    cv.put(white, bands(WHITE, np.where(white, t, 0), 0.5))
    # the sear: a lacy brown rim creeping in, ragged, with blisters in the white
    lace = fbm(N, N, 3, 23)
    edge_d = np.where(white, depth, 0)
    sear = white & (edge_d < 1.7 + 2.6 * (lace - 0.42))
    s_t = 0.35 + 0.55 * (1 - np.clip(edge_d / 4.0, 0, 1)) + 0.5 * L + 0.12 * (grain - 0.5)
    cv.put(sear, bands(SEAR, np.where(sear, s_t, 0), 0.5))
    blist = white & ~sear & (fbm(N, N, 2.5, 29) > 0.74)
    cv.put(blist, bands(SEAR, np.where(blist, 0.55 + 0.5 * L, 0), 0.4))
    # the yolk: an amber dome, set a little off centre
    ym, yd = ellipse(14.6, 14.4, 5.2, 4.8, N)
    yl = light(yd * 3.2, 1.0)
    cv.put(ym, bands(YOLK, np.where(ym, 0.52 + 0.85 * yl + 0.15 * yd, 0), 0.4))
    # the shell: two broken pieces at the lower corners, the speckled blue-green outside and the pale torn inside edge
    sh1 = poly([(20.5, 25.0), (27.5, 23.2), (29.8, 26.6), (27.0, 30.0), (22.2, 29.6)], N)
    sh2 = poly([(2.4, 25.4), (8.0, 24.2), (10.4, 27.8), (7.0, 30.4), (3.0, 29.2)], N)
    for shp, ox, oy in ((sh1, 25.4, 27.0), (sh2, 6.6, 27.4)):
        d = dist_in(shp, 6)
        Ls = light(np.clip(d / 3.0, 0, 1) * 2.6, 1.0)
        cv.put(shp, bands(SHELL, np.where(shp, 0.58 + 0.9 * Ls + 0.08 * (grain - 0.5), 0), 0.5))
        cv.put(shp & (d <= 1), WHITE[4])
        rng = np.random.default_rng(int(ox))
        for _ in range(7):
            px, py = int(ox + rng.integers(-3, 4)), int(oy + rng.integers(-3, 4))
            if 0 <= px < N and 0 <= py < N and shp[py, px] and d[py, px] >= 2:
                cv.rgb[py, px] = np.array(SPECK[0], float)
    # a pinch of pepper, and a gloss on the yolk
    for (px, py) in ((7, 11), (21, 9), (20, 21), (9, 20)):
        if cv.a[py, px] > 0 and not ym[py, px]:
            cv.rgb[py, px] = np.array((34, 26, 24), float)
    for (gx, gy, c) in ((11, 12, (255, 250, 214)), (12, 11, (255, 244, 190)), (11, 13, (255, 236, 170)), (13, 11, (255, 240, 184))):
        if ym[gy, gx]:
            cv.px(gx, gy, c)
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ block models: a few helpers

def dump_json(obj, indent=2, _level=0):
    """JSON the way the game's own files read, two spaces to a level, but a list of plain numbers or words on one line (a model of a hundred cubes
    stays a few hundred lines)."""
    pad = ' ' * (indent * (_level + 1))
    end = ' ' * (indent * _level)
    if isinstance(obj, dict):
        if not obj:
            return '{}'
        rows = [f'{pad}{json.dumps(k)}: {dump_json(v, indent, _level + 1)}' for k, v in obj.items()]
        return '{\n' + ',\n'.join(rows) + '\n' + end + '}'
    if isinstance(obj, (list, tuple)):
        if all(not isinstance(v, (dict, list, tuple)) for v in obj):
            return '[' + ', '.join(json.dumps(v) for v in obj) + ']'
        rows = [pad + dump_json(v, indent, _level + 1) for v in obj]
        return '[\n' + ',\n'.join(rows) + '\n' + end + ']'
    return json.dumps(obj)


def r3(v):
    """A coordinate rounded to a thousandth of a pixel, and a whole number when it is one."""
    v = round(float(v), 3)
    return int(v) if v == int(v) else v


def face(texture, uv, cull=None, rot=None):
    f = {'uv': [r3(v) for v in uv], 'texture': texture}
    if cull:
        f['cullface'] = cull
    if rot:
        f['rotation'] = rot
    return f


def cuboid(frm, to, faces, rot=None, origin=(8, 0, 8), **extra):
    """An element: the box from `frm` to `to` (in pixels), its `faces`, turned about `origin` by `rot` = (x, y, z) degrees (the game turns z first,
    then y, then x), and `extra` such as light_emission."""
    e = {'from': [r3(v) for v in frm], 'to': [r3(v) for v in to]}
    e.update(extra)
    if rot is not None and any(abs(a) > 1e-6 for a in rot):
        e['rotation'] = {'origin': [r3(v) for v in origin], 'x': r3(rot[0]), 'y': r3(rot[1]), 'z': r3(rot[2])}
    e['faces'] = faces
    return e


class Pile:
    """Strands laid one over another on a wrapping tile: a height, which palette and a tone offset for every texel. Later strands lie over earlier."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.H = np.zeros((h, w))
        self.pid = np.full((h, w), -1)
        self.off = np.zeros((h, w))
        self.z = 0.0

    def stick(self, x0, y0, x1, y1, r, pal, off=0.0, taper=0.4, lump=0.0, seed=0.0, lift=0.05):
        self.z += lift
        for ox in (-self.w, 0, self.w):
            for oy in (-self.h, 0, self.h):
                ax, ay, bx, by = x0 + ox, y0 + oy, x1 + ox, y1 + oy
                if max(ax, bx) + r < 0 or min(ax, bx) - r > self.w or max(ay, by) + r < 0 or min(ay, by) - r > self.h:
                    continue
                d, t = seg_dist(ax, ay, bx, by, self.w, self.h)
                rr = r * (1.0 - taper * np.abs(t - 0.5) * 2.0)
                if lump:
                    rr = rr * (1.0 + lump * np.sin(t * math.pi * 5 + seed))
                hs = np.sqrt(np.clip(1 - (d / np.maximum(rr, 0.2)) ** 2, 0, 1))
                m = hs > 0
                self.H = np.where(m, self.z + hs * 0.9, self.H)
                self.pid = np.where(m, pal, self.pid)
                self.off = np.where(m, off, self.off)


def light_wrap(H, strength=1.0):
    """hdkit's light() on a height field that wraps, so a tile shades the same across its own seam."""
    p = 3
    return light(np.pad(H, p, mode='wrap'), strength)[p:-p, p:-p]


def cavity_wrap(H, r=2):
    p = 4
    return cavity(np.pad(H, p, mode='wrap'), r)[p:-p, p:-p]


# ================================================================ the gull nest

# Strands for the nest, dark to light: driftwood, two browns, straw, bleached stick, olive weed, brown kelp, a dark root, grey lichen on a twig.
DRIFT = [(36, 30, 32), (72, 62, 58), (112, 98, 84), (150, 132, 108), (188, 170, 138), (218, 202, 168)]
DRIFT_D = [(30, 26, 28), (58, 50, 48), (88, 76, 66), (118, 102, 84), (152, 134, 108), (184, 164, 132)]
BROWN = [(34, 26, 24), (68, 52, 44), (100, 78, 62), (132, 104, 82), (166, 136, 108), (196, 168, 138)]
BROWN_D = [(28, 22, 22), (54, 42, 36), (80, 62, 50), (106, 84, 66), (136, 108, 84), (164, 134, 106)]
STRAW = [(64, 50, 34), (112, 92, 60), (156, 134, 86), (192, 170, 112), (222, 202, 146), (242, 228, 180)]
BLEACH = [(60, 56, 54), (108, 102, 94), (152, 144, 128), (186, 178, 158), (214, 208, 188), (238, 232, 214)]
WEED = [(20, 28, 22), (38, 52, 34), (60, 78, 42), (88, 106, 54), (122, 140, 76)]
KELP = [(26, 22, 18), (52, 44, 28), (80, 70, 38), (110, 96, 50), (144, 126, 66)]
ROOT = [(24, 18, 18), (46, 34, 30), (70, 52, 42), (94, 70, 54), (122, 94, 70)]
LICHEN = [(34, 36, 32), (68, 74, 62), (104, 112, 90), (140, 150, 118), (176, 186, 150)]
DOWN = [(96, 88, 84), (142, 134, 124), (184, 176, 162), (214, 208, 192), (236, 232, 218), (250, 248, 238)]
GRASS = [(58, 46, 32), (98, 80, 52), (140, 116, 72), (178, 152, 96), (208, 186, 126), (230, 212, 156)]
GRASS_D = [(44, 34, 26), (80, 62, 42), (114, 90, 58), (148, 118, 74), (180, 148, 96), (206, 176, 118)]

# The twig sheet: each strand a band of three texels (a lit top, a body, a shadowed underside), 32 long and wrapping, one under another; the last
# a thin band of two. A twig in the model is a cube 1.5 pixels thick whose faces are cut from one band.
TWIG_ROWS = [('drift', DRIFT, 0, 3), ('drift_d', DRIFT_D, 3, 3), ('brown', BROWN, 6, 3), ('brown_d', BROWN_D, 9, 3), ('straw', STRAW, 12, 3),
             ('bleach', BLEACH, 15, 3), ('weed', WEED, 18, 3), ('kelp', KELP, 21, 3), ('root', ROOT, 24, 3), ('lichen', LICHEN, 27, 3),
             ('grass', STRAW, 30, 2)]
TWIG_V = {name: (r0 / 2.0, rows / 2.0) for name, _, r0, rows in TWIG_ROWS}      # uv of the band, and its height in pixels


def strand_row(pal, seed, rows=3, knots=0.06, bark=0.30, smooth=False):
    """One strand, `rows` texels high and 32 long and wrapping: the tone wanders along it, with knots and nicks where it is rough."""
    rng = np.random.default_rng(seed)
    pal = np.asarray(pal, float)
    out = np.zeros((rows, N, 3))
    wander = vnoise(N, 1, 7, seed)[0]
    wander2 = vnoise(N, 1, 3, seed + 5)[0]
    for x in range(N):
        body = 2 if wander[x] < 0.55 else 3
        if wander2[x] > 0.72:
            body = min(body + 1, len(pal) - 2)
        hi = body + 1 if not (rng.random() < bark and not smooth) else body
        lo = max(body - 1, 0) if rng.random() > 0.2 else max(body - 2, 0)
        if rows == 3:
            out[0, x], out[1, x], out[2, x] = pal[min(hi, len(pal) - 1)], pal[body], pal[lo]
        else:
            out[0, x], out[1, x] = pal[min(hi, len(pal) - 1)], pal[body]
    for _ in range(int(round(N * knots))):
        x = int(rng.integers(0, N))
        if rows == 3:
            out[1, x] = pal[1]
            out[1, (x + 1) % N] = pal[1] if rng.random() < 0.6 else pal[2]
            out[0, x] = pal[min(4, len(pal) - 1)]
            out[2, x] = pal[0]
            out[2, (x + 1) % N] = pal[0]
    return out


def nest_twigs():
    """gull_nest.png: the strands the nest is built of."""
    cv = Canvas(N)
    for i, (name, pal, r0, rows) in enumerate(TWIG_ROWS):
        row = strand_row(pal, 100 + i, rows=rows, knots=0.06 if i < 6 else 0.04, smooth=name in ('straw', 'bleach', 'weed', 'grass'))
        for r in range(rows):
            cv.rgb[r0 + r] = row[r]
            cv.a[r0 + r] = 1
    return cv.image()


def nest_lining():
    """gull_nest_top.png: the floor of the cup, dry grass laid round in arcs with tufts of down in it, darker toward the wall."""
    rng = np.random.default_rng(11)
    xs, ys = grid(N)
    cx, cy = 16.0, 16.0
    P = Pile(N, N)
    for ring in range(7):
        r = 1.0 + ring * 2.35
        a = rng.uniform(0, 6.28)
        total = 0.0
        while total < 6.28:
            span = rng.uniform(1.0, 1.9) * (7.0 / (r + 4.0))
            n = 5
            pts = [(cx + (r + rng.normal(0, 0.12)) * math.cos(a + span * i / (n - 1)), cy + (r + rng.normal(0, 0.12)) * math.sin(a + span * i / (n - 1)))
                   for i in range(n)]
            pal = int(rng.choice([0, 1, 0]))
            off = rng.uniform(-0.12, 0.12)
            rad = rng.uniform(1.05, 1.4)
            P.z += 0.02
            for (ax, ay), (bx, by) in zip(pts, pts[1:]):
                P.stick(ax, ay, bx, by, rad, pal, off, 0.0, lift=0.0)
            a += span * 0.82
            total += span * 0.82
    for k in range(3):                                   # a bit of weed and twig bedded in it
        a = rng.uniform(0, 6.28)
        r = rng.uniform(5, 11)
        x, y = cx + r * math.cos(a), cy + r * math.sin(a)
        t = a + math.pi / 2 + rng.normal(0, 0.3)
        P.stick(x - math.cos(t) * 3.5, y - math.sin(t) * 3.5, x + math.cos(t) * 3.5, y + math.sin(t) * 3.5, 1.15, 4 if k else 3, -0.05, 0.5)
    H, pid, off = P.H, P.pid, P.off
    L = light_wrap(H * 2.0, 1.0)
    cav = cavity_wrap(H, 2)
    out = np.zeros((N, N, 3))
    vign = np.clip((np.hypot(xs - cx, ys - cy) - 7.0) / 9.0, 0, 1)
    for k, p in enumerate([GRASS, GRASS_D, DOWN, WEED, BROWN]):
        m = pid == k
        if m.any():
            t = 0.66 + 0.8 * L + off + 0.06 * (fbm(N, N, 3, 70 + k) - 0.5) - 0.5 * cav - 0.30 * vign
            out[m] = bands(p, np.clip(t, 0, 1), 0.2)[m]
    gap = pid < 0
    out[gap] = bands(GRASS_D, np.clip(0.26 + 0.08 * fbm(N, N, 2, 79) - 0.15 * vign, 0, 1), 0.2)[gap]
    cv = Canvas(N)
    cv.put(np.ones((N, N), bool), out)
    cv.a[:] = 1
    for (px, py, rx, ry) in ((13.0, 12.5, 2.6, 1.7), (19.5, 14.0, 2.2, 1.5), (14.5, 20.0, 2.4, 1.6), (20.5, 20.0, 2.0, 1.4), (16.5, 9.0, 1.8, 1.2),
                             (11.0, 17.0, 1.8, 1.3)):                                    # tufts of down: soft pale domes
        m, d = ellipse(px, py, rx, ry, N)
        t = 0.55 + 0.9 * light(d * 3.0, 1.0) + 0.35 * d
        cv.put(m, bands(DOWN, np.where(m, np.clip(t, 0, 1), 0), 0.3))
    return cv.image()


def nest_shell():
    """gull_nest_egg.png: the shell of the nest's eggs. Rows 0-15 are for the sides of an egg; rows 16-31, a shade deeper, for its upward faces, so the
    game's own top light does not step the egg."""
    rng = np.random.default_rng(7)
    cv = Canvas(N)
    base = fbm(N, N, 6, 91)
    t = 0.60 + 0.12 * (base - 0.5) * 2 + 0.04 * (rand(N, N, 92) - 0.5)
    cv.put(np.ones((N, N), bool), bands(SHELL, np.clip(t, 0, 1), 0.3))
    cv.a[:] = 1
    cv.over(fbm(N, N, 5, 93) > 0.66, (124, 128, 150), 0.30)
    placed = []
    tries = 0
    while len(placed) < 60 and tries < 6000:
        tries += 1
        ix, iy = int(rng.uniform(0, N)), int(rng.uniform(0, N))
        if any((ix - qx) ** 2 + (iy - qy) ** 2 < 4.5 for qx, qy in placed):
            continue
        placed.append((ix, iy))
    for ix, iy in placed:
        col = np.array(SPECK[0] if rng.random() < 0.5 else SPECK[1], float)
        cells = [(0, 0)]
        if rng.random() < 0.2:
            cells.append((1, 0) if rng.random() < 0.5 else (0, 1))
        for dx, dy in cells:
            if 0 <= ix + dx < N and 0 <= iy + dy < N:
                cv.rgb[iy + dy, ix + dx] = col
    cv.rgb[16:] = cv.rgb[16:] * 0.86
    return cv.image()


NEST_TEXTURES = {'particle': 'guildmark:block/gull_nest', 'twigs': 'guildmark:block/gull_nest', 'lining': 'guildmark:block/gull_nest_top',
                 'shell': 'guildmark:block/gull_nest_egg'}
NEST_SEED = 6
def twig(cx, cz, y0, length, yaw, kind, u0, pitch=0.0, thick=1.5, down=True):
    """A twig `length` pixels long and 1.5 thick, its middle at (cx, y0 + thick / 2, cz), turned `yaw` about the vertical and pitched `pitch` along its
    length; its faces are cut from the strand `kind` of the twig sheet, starting `u0` along it."""
    v0, rows = TWIG_V[kind]
    h = rows if rows < thick else thick
    t = '#twigs'
    f = {'north': face(t, [u0, v0, u0 + length, v0 + h]), 'south': face(t, [u0, v0, u0 + length, v0 + h]),
         'up': face(t, [u0, v0, u0 + length, v0 + h]),
         'east': face(t, [u0, v0, u0 + h, v0 + h]), 'west': face(t, [u0, v0, u0 + h, v0 + h])}
    if down:
        f['down'] = face(t, [u0, v0, u0 + length, v0 + h])
    return cuboid((cx - length / 2, y0, cz - h / 2), (cx + length / 2, y0 + h, cz + h / 2), f, rot=(0, yaw, pitch), origin=(cx, y0 + h / 2, cz))


def tangent_yaw(theta):
    """The turn that lays a twig (long side along x) along the circle at angle theta about the middle of the block."""
    return math.degrees(math.atan2(-math.cos(theta), -math.sin(theta)))


def nest_wreath(seed=NEST_SEED):
    """The nest's walls: courses of twigs round the cup, each course wider than the one under it, the bottom darkest and the rim palest."""
    rng = np.random.default_rng(seed)
    els = []
    courses = [
        (0.0, [(4.7, 9), (3.2, 7)], ['root', 'brown_d', 'drift_d', 'kelp', 'brown_d', 'drift_d', 'root'], 3.0),
        (1.45, [(5.9, 10), (4.5, 8)], ['brown_d', 'drift_d', 'brown', 'kelp', 'drift', 'brown', 'drift_d', 'brown_d'], 5.0),
        (2.9, [(6.45, 11), (5.0, 9)], ['drift', 'brown', 'drift', 'brown', 'drift', 'brown_d', 'straw', 'lichen', 'bleach'], 8.0),
    ]
    for j, (y, rings, kinds, pitch_max) in enumerate(courses):
        for r_i, (R, n) in enumerate(rings):
            off = rng.uniform(0, 2 * math.pi)
            bag = []
            for i in range(n):
                th = off + (i + 0.5 * ((j + r_i) % 2)) * 2 * math.pi / n + rng.normal(0, 0.07)
                rad = R + rng.normal(0, 0.18)
                cx, cz = 8 + rad * math.cos(th), 8 + rad * math.sin(th)
                length = 2 * math.pi * R / n * rng.uniform(1.25, 1.6)
                yaw = tangent_yaw(th) + rng.normal(0, 13)
                if not bag:                                      # the kinds come round in a shuffled bag, so no ring is all one stuff
                    bag = [kinds[k] for k in rng.permutation(len(kinds))]
                kind = bag.pop()
                u0 = float(rng.integers(0, int(16 - length)))
                yy = y + rng.normal(0, 0.12) + (0.0 if r_i == 0 else 0.1)
                els.append(twig(cx, cz, yy, length, yaw, kind, u0, pitch=rng.uniform(-pitch_max, pitch_max)))
    # a few long sticks that stand out past the rim
    for th_deg, y, kind in ((20, 2.2, 'brown'), (155, 2.7, 'drift'), (250, 2.4, 'brown')):
        th = math.radians(th_deg)
        cx, cz = 8 + 6.1 * math.cos(th), 8 + 6.1 * math.sin(th)
        els.append(twig(cx, cz, y, 5.2, math.degrees(math.atan2(-math.sin(th), math.cos(th))), kind, float(rng.integers(0, 10)), pitch=rng.uniform(-6, 6)))
    # seaweed hung over the rim and down the outside, as a flat ribbon
    for th_deg, hang in ((100, 3.4), (305, 2.6)):
        th = math.radians(th_deg)
        cx, cz = 8 + 7.5 * math.cos(th), 8 + 7.5 * math.sin(th)
        v0, rows = TWIG_V['weed']
        f = {'north': face('#twigs', [0, v0, hang, v0 + rows], rot=270), 'south': face('#twigs', [0, v0, hang, v0 + rows], rot=90)}
        els.append(cuboid((cx - rows / 2, 4.3 - hang, cz - 0.15), (cx + rows / 2, 4.3, cz + 0.15), f, rot=(0, tangent_yaw(th), 0), origin=(cx, 2.5, cz)))
    return els


# The eggs lie in the cup as a gull lays them: each with its blunt end toward the wall and its pointed end raised and turned in, the three points
# meeting in the middle. An egg is EGG_L long and EGG_W across (flat to flat) at its fullest, built as rings of eight sides along its length; each
# side of each band between two rings is one thin box, tipped to the slope of the shell there, with its outer face cut from the shell sheet.
EGG_L, EGG_W = 4.8, 3.6
EGG_SIDES = 8
EGG_PROFILE = [(0.0, 0.0), (0.03, 0.40), (0.08, 0.66), (0.16, 0.86), (0.26, 0.97), (0.36, 1.0), (0.46, 0.985), (0.56, 0.93), (0.66, 0.84), (0.76, 0.70),
               (0.85, 0.52), (0.93, 0.30), (0.98, 0.12), (1.0, 0.0)]          # how full the shell is, as a share of its breadth, along its length from the blunt end
EGG_SLOTS = (35.0, 155.0, 275.0)        # the bearing, from the middle of the nest, each egg lies toward; the first `eggs` are drawn
EGG_ELEV = 22.0                         # degrees its pointed end is raised
EGG_REACH = 2.4                         # pixels from the middle of the nest to the middle of an egg
EGG_SINK = 0.25                         # how far its underside sinks into the lining
LINING_Y = 2.0                          # the top of the lining, the floor of the cup


def _rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler_xyz(R):
    """(x, y, z) in degrees such that R = Rx(x) Ry(y) Rz(z): the three angles of an element's `rotation`, which the game applies z first, then y, then x."""
    y = math.asin(max(-1.0, min(1.0, R[0, 2])))
    if abs(math.cos(y)) > 1e-6:
        return math.degrees(math.atan2(-R[1, 2], R[2, 2])), math.degrees(y), math.degrees(math.atan2(-R[0, 1], R[0, 0]))
    return math.degrees(math.atan2(R[2, 1], R[1, 1])), math.degrees(y), 0.0


def turn_to(v_from, v_to):
    """The rotation matrix taking the direction `v_from` to `v_to` by the shortest turn."""
    a = np.asarray(v_from, float) / np.linalg.norm(v_from)
    b = np.asarray(v_to, float) / np.linalg.norm(v_to)
    k = np.cross(a, b)
    sn, cs = float(np.linalg.norm(k)), float(a @ b)
    if sn < 1e-9:
        return np.eye(3) if cs > 0 else _rx(math.pi)
    k = k / sn
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + sn * K + (1 - cs) * (K @ K)


def egg_rings(a_max, da=0.32, ds=0.75):
    """The rings an egg is built from, (distance along its axis from its middle, apothem of the eight sides): taken along the shell's profile so that no
    band between two rings changes by more than `da` across or `ds` along."""
    ft = np.array([p[0] for p in EGG_PROFILE])
    ff = np.array([p[1] for p in EGG_PROFILE])
    t = np.linspace(0, 1, 2001)
    S, R = -EGG_L / 2 + t * EGG_L, a_max * np.interp(t, ft, ff)
    rings, last = [(S[0], R[0])], 0
    for i in range(1, len(t)):
        if abs(R[i] - R[last]) >= da or abs(S[i] - S[last]) >= ds:
            rings.append((S[i], R[i]))
            last = i
    if rings[-1][0] < S[-1] - 1e-6:
        rings.append((S[-1], R[-1]))
    return rings


def egg_facets(mid, axis, roll, rng, thick=0.25):
    """One egg with its middle at `mid` and its pointed end toward the unit vector `axis`, turned `roll` degrees about that axis. Every facet is a thin
    box turned to the shell's slope and lying on the shell, with its outer face on the shell sheet (the sides of the sheet's upper half where the facet
    faces up, so the game's top light does not step the egg); the bands at the two tips show their inner faces too, so no gap shows the nest behind."""
    mid = np.asarray(mid, float)
    R_egg = turn_to((0, 1, 0), axis) @ _ry(math.radians(roll))
    rings = egg_rings(EGG_W / 2)
    t22 = math.tan(math.radians(180.0 / EGG_SIDES))
    els = []
    for j in range(len(rings) - 1):
        (s0, a0), (s1, a1) = rings[j], rings[j + 1]
        slope = math.hypot(s1 - s0, a1 - a0)
        eps = math.atan2(-(a1 - a0), s1 - s0)                         # how far the shell's normal here is tipped up from the horizontal
        a_mid, s_mid = (a0 + a1) / 2, (s0 + s1) / 2
        w, h = 2 * max(a0, a1) * t22 * 1.02 + 0.02, slope * 1.04
        tip = j < 2 or j >= len(rings) - 3
        for k in range(EGG_SIDES):
            psi = math.radians(360.0 / EGG_SIDES * (k + 0.5))
            R = R_egg @ _ry(psi) @ _rx(-eps)
            centre = mid + R_egg @ np.array([a_mid * math.sin(psi), s_mid, a_mid * math.cos(psi)])
            n = R @ np.array([0.0, 0.0, 1.0])
            up = n[1] > max(abs(n[0]), abs(n[2]))
            u0 = math.floor(float(rng.uniform(0, 16 - w)) * 2) / 2                      # on the texel grid, and inside the sheet
            v0 = math.floor(float(rng.uniform(0, 8 - h)) * 2) / 2 + (8.0 if up else 0.0)
            f = {'south': face('#shell', [u0, v0, u0 + w, v0 + h])}
            if tip:
                f['north'] = face('#shell', [u0, v0, u0 + w, v0 + h])
            els.append(cuboid(centre + (-w / 2, -h / 2, -thick), centre + (w / 2, h / 2, 0.0), f, rot=euler_xyz(R), origin=tuple(centre)))
    return els


def lowest_of_shell(els):
    """The lowest y of the outer faces (the shell itself) of the facets `els`, as the game turns them."""
    lo = 1e9
    for e in els:
        r = e['rotation']
        R = _rx(math.radians(r['x'])) @ _ry(math.radians(r['y'])) @ _rz(math.radians(r['z']))
        o = np.array(r['origin'], float)
        for x in (e['from'][0], e['to'][0]):
            for y in (e['from'][1], e['to'][1]):
                lo = min(lo, float((R @ (np.array([x, y, e['to'][2]], float) - o))[1] + o[1]))
    return lo


def egg_elements(count, seed=NEST_SEED):
    """The first `count` of the nest's three eggs, each laid so that its underside sinks EGG_SINK into the lining."""
    els = []
    for k in range(count):
        az, el = math.radians(EGG_SLOTS[k]), math.radians(EGG_ELEV)
        axis = np.array([-math.cos(el) * math.cos(az), math.sin(el), -math.cos(el) * math.sin(az)])
        mid = np.array([8 + EGG_REACH * math.cos(az), 3.4, 8 + EGG_REACH * math.sin(az)])
        roll = 47.0 * k
        probe = egg_facets(mid, axis, roll, np.random.default_rng(seed + 100 + k))
        mid[1] += (LINING_Y - EGG_SINK) - lowest_of_shell(probe)
        els += egg_facets(mid, axis, roll, np.random.default_rng(seed + 100 + k))
    return els


def nest_model(eggs):
    """The nest block holding `eggs` eggs (0 to 3): a cup of twigs on a bed of lining, the eggs lying in it."""
    f = {'up': face('#lining', [4, 4, 12, 12])}
    els = [cuboid((4, 0, 4), (12, LINING_Y, 12), f)]
    els += nest_wreath()
    els += egg_elements(eggs)
    return {'parent': 'minecraft:block/block', 'textures': NEST_TEXTURES, 'elements': els}


# ================================================================ the lighthouse lamp

BRASS = PAL['brass']
VERDIGRIS = (62, 108, 94)


def lamp_brass():
    """lighthouse_lamp.png: the lamp's brass, in three pieces, painted tone by tone. A rod four texels wide down the left (x 0-3), for the posts; a bar
    three texels high along the top (x 4-31, y 0-2), for the edges of plates; and a plate 28 texels square (x 4-31, y 4-31), for the tops and
    undersides, bevelled, riveted, with rings turned in it."""
    rng = np.random.default_rng(23)
    pal = np.array(BRASS, float)
    cv = Canvas(N)

    def paint(idx, x0, y0):
        idx = np.clip(idx, 0, len(BRASS) - 1).astype(int)
        h, w = idx.shape
        cv.rgb[y0:y0 + h, x0:x0 + w] = pal[idx]
        cv.a[y0:y0 + h, x0:x0 + w] = 1

    def rivet(idx, x, y):
        """A 2x2 rivet: a bright upper left, a shaded lower right."""
        for (dx, dy, t) in ((0, 0, 5), (1, 0, 3), (0, 1, 3), (1, 1, 1)):
            if 0 <= y + dy < idx.shape[0] and 0 <= x + dx < idx.shape[1]:
                idx[y + dy, x + dx] = t

    # the rod: lit on the left, dark on the right, a clamp band and a rivet at intervals
    rod = np.tile(np.array([4, 3, 2, 1]), (N, 1))
    for y0 in (6, 19):
        rod[y0 - 1, :] = 1
        rod[y0:y0 + 3] = np.array([5, 4, 3, 2])
        rod[y0 + 3, :] = 1
    for y in (2, 13, 26):
        rivet(rod, 1, y)
    paint(rod + (rng.random((N, 4)) < 0.10) * rng.choice([-1, 1], (N, 4)) * (np.arange(4) > 0), 0, 0)
    # the bar: a lit top row, a body, a shaded edge; a rivet at either end and the middle, two seams
    bar = np.tile(np.array([[4], [3], [2]]), (1, 28))
    for x in (2, 13, 24):
        rivet(bar, x, 0)
    for x in (8, 19):
        bar[:, x] = 1
        bar[0, x + 1] = 5
    paint(bar + (rng.random((3, 28)) < 0.08) * rng.choice([-1, 1], (3, 28)), 4, 0)
    # the plate: a bevelled border, the field inside it, three rings turned in it, a rivet in each corner and a boss in the middle
    xs, ys = grid(28)
    plate = np.full((28, 28), 3)
    plate[0:2, :] = 5
    plate[:, 0:2] = 5
    plate[26:28, :] = 2
    plate[:, 26:28] = 2
    plate[0, :] = 5
    plate[0:2, 26:28] = 4
    plate[26:28, 0:2] = 3
    plate[27, :] = 1
    plate[:, 27] = 1
    plate[0, 27] = 3
    plate[27, 0] = 3
    inner = (xs > 2.2) & (xs < 25.8) & (ys > 2.2) & (ys < 25.8)
    plate[inner] = 3 + (rng.random((28, 28)) < 0.12)[inner] * rng.choice([-1, 1], (28, 28))[inner]
    r = np.hypot(xs - 14.0, ys - 14.0)
    for r0 in (5.2, 8.6, 12.0):
        groove = inner & (np.abs(r - r0) < 0.55)
        plate[groove] = 1
        lip = inner & (np.abs(r - (r0 + 1.1)) < 0.5) & ((xs - 14.0) + (ys - 14.0) < 0)
        plate[lip] = 4
    boss = r < 3.1
    plate[boss] = np.where(((xs - 14.0) + (ys - 14.0))[boss] < -1.0, 5, np.where(((xs - 14.0) + (ys - 14.0))[boss] < 1.6, 4, 3))
    plate[(r >= 3.1) & (r < 3.8)] = 1
    for (cx, cy) in ((3, 3), (23, 3), (3, 23), (23, 23)):
        rivet(plate, cx, cy)
    paint(plate, 4, 4)
    # a few specks of verdigris, where the sea has got in
    for _ in range(14):
        x, y = int(rng.integers(0, N)), int(rng.integers(0, N))
        if cv.a[y, x] > 0 and (x > 3 and y > 3 or rng.random() < 0.3):
            cv.rgb[y, x] = np.array(VERDIGRIS, float) * (0.8 + 0.4 * rng.random())
    # the one row no face reads, between the bar and the plate, takes the dark of the grooves: the sheet is whole (the particles of a broken lamp pick
    # their texels from all of it)
    cv.rgb[3, 4:] = pal[1]
    cv.a[3, 4:] = 1
    return cv.image()


# The lens: on each of the lamp's four sides a Fresnel disc, a bull's-eye in the middle and rings of prisms round it, cut off by the posts at the sides
# and the plates above and below. The face is 9 pixels wide and 10 high (uv 3.5-12.5, 3-13), so the glass occupies texels x 7-24 and y 6-25 of its sheet.
LENS_X0, LENS_X1, LENS_Y0, LENS_Y1 = 7, 25, 6, 26
LENS_C = (16.0, 16.0)
GLASS = [(18, 40, 48), (40, 84, 92), (82, 134, 138), (130, 182, 180), (184, 224, 216), (238, 252, 246)]


def lens_mask():
    m = np.zeros((N, N), bool)
    m[LENS_Y0:LENS_Y1, LENS_X0:LENS_X1] = True
    return m


RISERS = [2.7, 5.2, 7.7, 10.2, 12.7, 15.2]          # the radii, in texels from the middle, of the thin dark lines between the prisms
EYE_R = 2.7


def lens_rings():
    """The lens as pixel art, not a height field (it is only sixteen texels across): the radius of every texel, which prism it is in (0 the eye), how far
    through the prism, and on which half of the disc (+1 toward the light at the upper left, -1 away)."""
    xs, ys = grid(N)
    dx, dy = xs - LENS_C[0], ys - LENS_C[1]
    r = np.hypot(dx, dy)
    ring = np.zeros((N, N), int)
    for k, rr in enumerate(RISERS):
        ring[r >= rr] = k + 1
    side = np.sign(-(dx + dy) / np.sqrt(2))
    return r, ring, side


def lamp_lens():
    """lighthouse_lamp_lens.png: the glass, half clear. A glinting dome of an eye in the middle (nearly clear), then prisms round it, each pale on the
    side toward the light and dark on the other, a thin dark line between them; deeper glass at the edges. Half-clear, so what is inside shows through."""
    r, ring, side = lens_rings()
    m = lens_mask()
    tone_idx = np.zeros((N, N), int)
    alpha = np.zeros((N, N))
    for k in range(0, len(RISERS) + 1):
        sel = ring == k
        if k == 0:
            base, a = 4, 0.42                                   # nearly clear, so the flame shows through the eye
        else:
            base, a = (3 if k % 2 == 1 else 2), (0.56 if k % 2 == 1 else 0.42)
        tone_idx[sel] = base
        alpha[sel] = a
    # a prism is lit on the side toward the light and dark on the other, more the nearer its edge
    pos = np.zeros((N, N))
    for k in range(1, len(RISERS) + 1):
        lo = RISERS[k - 1]
        hi = RISERS[k] if k < len(RISERS) else lo + 2.5
        sel = ring == k
        pos[sel] = ((r - lo) / (hi - lo))[sel]
    tone_idx = tone_idx + np.where((ring > 0) & (pos < 0.5), side, -side * (pos > 0.5) * 0.0)
    tone_idx = np.where((ring > 0) & (pos >= 0.5) & (side < 0), tone_idx - 1, tone_idx)
    tone_idx = np.clip(tone_idx, 0, len(GLASS) - 1).astype(int)
    cv = Canvas(N)
    cv.rgb = np.array(GLASS, float)[tone_idx]
    cv.a = alpha.copy()
    # the thin dark line between prisms
    riser = np.zeros((N, N), bool)
    for rr in RISERS:
        riser |= (r >= rr) & (r < rr + 0.8)
    cv.rgb[riser & m] = np.array(GLASS[1], float)
    cv.a[riser & m] = 0.78
    # the eye: a dome, bright at its upper left, and a glint
    eye = r < EYE_R
    eh = np.sqrt(np.clip(1 - (r / EYE_R) ** 2, 0, 1))
    cv.rgb[eye] = bands(GLASS, np.where(eye, np.clip(0.55 + 0.55 * eh + 0.18 * (np.where(eye, -(0), 0)), 0, 1), 0), 0.2)[eye]
    for (gx, gy, c, a) in ((14, 14, (255, 255, 250), 0.96), (15, 14, (246, 252, 246), 0.9), (14, 15, (240, 250, 244), 0.88)):
        cv.rgb[gy, gx] = c
        cv.a[gy, gx] = a
    cv.a[~m] = 0
    cv.rgb[~m] = 0
    return cv.image()


GOLD = (255, 198, 100)
HOT = (255, 247, 218)
AMBER_GLOW = (255, 184, 84)
GLOW_FRAMES = 40          # the glow's cycle is 80 ticks: 40 frames of 2 ticks, so that the flame's own 16 ticks go five times into it
GLOW_FRAMETIME = 2


def _smooth(x):
    x = np.clip(x, 0, 1)
    return x * x * (3 - 2 * x)


def lamp_glow(face):
    """lighthouse_lamp_glow.png (face 0) and _glow_1, _2, _3: the light of the burning lamp on its glass, an animated strip of GLOW_FRAMES frames laid over
    the lens and drawn at full strength. A steady amber-gold, whitest round the eye and falling away through the prisms to the rim (the risers between
    the prisms are left dark, so the rings of the lens show), the lit half of each prism a little brighter. It breathes with the flame (a flicker of the
    flame's own 16 ticks and a smaller, quicker one over it), and twice a cycle a ring of brighter light runs out from the eye through the prisms to the
    rim, on each face a quarter of a run behind the one before, so the light seems to turn inside the lens. (The sweep of the beam itself is the
    renderer's.) Where nothing glows the strip is clear."""
    r, ring, side = lens_rings()
    m = lens_mask()
    prism = (ring > 0) & m
    riser = np.zeros((N, N), bool)
    for rr in RISERS:
        riser |= (r >= rr) & (r < rr + 0.8)
    w_ring = np.where(side > 0, 1.0, 0.64)                     # the lit half of a prism glows more
    w_ring = np.where(ring % 2 == 1, w_ring, w_ring * 0.80)
    eye = np.exp(-(r / 4.0) ** 2)
    radial = 1.0 + 1.3 * (1.0 - np.clip(r / 15.0, 0, 1)) ** 1.3
    steady = 0.36 * radial * w_ring * prism * (~riser) + 0.95 * eye
    strip = Image.new('RGBA', (N, N * GLOW_FRAMES), (0, 0, 0, 0))
    for f in range(GLOW_FRAMES):
        t = f / GLOW_FRAMES
        flick = 1 + 0.05 * math.sin(2 * math.pi * 5 * t + 0.5) + 0.03 * math.sin(2 * math.pi * 8 * t + 2.0)
        s = (2 * t + face / 4.0) % 1.0                          # how far this face's ring of light has run
        env = _smooth(s / 0.12) * (1 - 0.6 * s)
        wave = np.exp(-((r - (1.8 + 15.0 * s)) / 1.9) ** 2) * env
        E = flick * steady + 0.34 * wave * prism * (~riser) * (0.6 + 0.4 * w_ring)
        E = np.where(m, E, 0.0)
        A = np.clip(E * 0.95, 0, 0.96)
        hot = np.clip((E - 0.45) / 0.55, 0, 1) ** 1.2
        rgb = np.array(AMBER_GLOW, float) * (1 - hot[..., None]) + np.array(HOT, float) * hot[..., None]
        out = np.dstack([rgb, A * 255]).round().astype(np.uint8)
        out[out[..., 3] < 3] = (*AMBER_GLOW, 0)               # clear, but amber: the game blends frame into frame, colour and all, so a clear texel must not be black
        strip.paste(Image.fromarray(out, 'RGBA'), (0, f * N))
    return strip


GLINT_FRAMES = 48         # the glint's cycle is 96 ticks of 2
GLINT_FRAMETIME = 2
GLINT_RGB = (255, 250, 236)


def lamp_glint(phase):
    """lighthouse_lamp_glint.png (phase 0) and _glint_1 (half a cycle later): the sun on the glass of the lamp when it is out, an animated strip laid over
    the lens, white and not glowing. Once a cycle a soft double streak sweeps the lens from the upper left to the lower right, brighter on every other
    prism, and the glint in the eye flares into a four-pointed star as it passes. Clear (but white) where nothing shows."""
    r, ring, side = lens_rings()
    m = lens_mask()
    xs, ys = grid(N)
    q = ((xs - LENS_C[0]) + (ys - LENS_C[1])) / math.sqrt(2)           # along the diagonal: negative toward the upper left
    alt = np.where(ring % 2 == 1, 1.0, 0.7)
    strip = Image.new('RGBA', (N, N * GLINT_FRAMES), (0, 0, 0, 0))
    for f in range(GLINT_FRAMES):
        t = (f / GLINT_FRAMES + phase) % 1.0
        s = t / 0.38
        g = np.zeros((N, N))
        if s < 1.0:
            c = -15.5 + 31.0 * s
            g = (np.exp(-((q - c) / 1.3) ** 2) + 0.5 * np.exp(-((q - (c - 4.2)) / 0.8) ** 2)) * math.sin(math.pi * s) ** 0.6
        g = g * alt * m * (ring > 0)
        star = np.zeros((N, N))
        tw = math.exp(-((t - 0.17) / 0.03) ** 2)
        if tw > 0.02:
            for (dx, dy, k) in ((0, 0, 1.0), (1, 0, 0.85), (-1, 0, 0.85), (0, 1, 0.85), (0, -1, 0.85), (2, 0, 0.55), (-2, 0, 0.55), (0, 2, 0.55),
                                (0, -2, 0.55), (3, 0, 0.25), (-3, 0, 0.25), (0, 3, 0.25), (0, -3, 0.25)):
                star[14 + dy, 14 + dx] = k * tw
        out = np.zeros((N, N, 4), np.uint8)
        out[..., :3] = GLINT_RGB
        out[..., 3] = (np.clip(g * 0.85 + star, 0, 1) * 255).round().astype(np.uint8)
        out[out[..., 3] < 3, 3] = 0
        strip.paste(Image.fromarray(out, 'RGBA'), (0, f * N))
    return strip


CORE_FRAMES = 8
CORE_FRAMETIME = 2
AMBER = (240, 150, 54)
EMBER = (214, 96, 36)


def flame_colour(e):
    """The core's colour by how far in from its edge a texel is (0 the rim, 1 the heart): ember, amber, gold, white."""
    e = np.asarray(e, float)
    out = np.empty(e.shape + (3,))
    out[:] = EMBER
    for lo, hi, c in ((0.14, 0.34, AMBER), (0.34, 0.62, GOLD), (0.62, 99.0, HOT)):
        out[(e >= lo) & (e < hi)] = c
    return out


def lamp_core():
    """lighthouse_lamp_core.png: the burning core at the lamp's heart, an animated strip of CORE_FRAMES frames. The sides of the core sit at x 0-6, y 0-13 of
    each frame and its ends at x 0-6, y 14-20: a tongue of fire, white at the heart and gold, amber and ember toward the rim, that sways and swells a little
    from frame to frame and is the same at the frame after the last as at the first."""
    strip = Image.new('RGBA', (N, N * CORE_FRAMES), (0, 0, 0, 0))
    ys7, xs7 = np.mgrid[0:14, 0:7].astype(float)
    xs7, ys7 = xs7 + 0.5, ys7 + 0.5
    ysq, xsq = np.mgrid[0:7, 0:7].astype(float)
    xsq, ysq = xsq + 0.5, ysq + 0.5
    for f in range(CORE_FRAMES):
        ph = 2 * math.pi * f / CORE_FRAMES
        frame = np.zeros((N, N, 4), np.uint8)
        frame[..., :3] = EMBER                                          # clear, but ember (see lamp_glow)
        yy = ys7 - 7.4
        up = np.clip(-yy / 7.4, 0, 1)                                    # 0 at the belly, 1 at the tip
        rad = 3.25 * np.sqrt(np.clip(1 - (yy / 7.0) ** 2, 0, 1)) * (1 - 0.30 * up) * (1 + 0.07 * np.sin(2 * ph + ys7 * 0.4))
        sway = 0.95 * up ** 1.5 * np.sin(ph + ys7 * 0.33)
        dist = np.abs(xs7 - 3.5 - sway)
        e = 1 - dist / np.maximum(rad, 0.05)
        inside = (e > 0) & (rad > 0.3)
        blk = np.zeros((14, 7, 4))
        blk[..., :3] = flame_colour(e)
        blk[..., 3] = np.where(inside, 255, 0)
        frame[0:14, 0:7] = blk.round().astype(np.uint8)
        dq = np.hypot(xsq - 3.5, ysq - 3.5) / (3.35 + 0.18 * math.sin(ph))
        eq = 1 - dq
        blkq = np.zeros((7, 7, 4))
        blkq[..., :3] = flame_colour(eq * 1.15)
        blkq[..., 3] = np.where(eq > 0, 255, 0)
        frame[14:21, 0:7] = blkq.round().astype(np.uint8)
        strip.paste(Image.fromarray(frame, 'RGBA'), (0, f * N))
    return strip


WICK_FRAMES = 8
WICK_FRAMETIME = 5                                   # the ember's swell and dimming: 40 ticks
MANTLE_TONES = [(66, 60, 56), (112, 104, 94), (158, 148, 132), (200, 190, 170), (232, 224, 204)]


def lamp_wick():
    """lighthouse_lamp_wick.png: what is inside the lamp when it is out, an animated strip of WICK_FRAMES frames. The mantle, a sack of ash-pale woven
    fibre hanging from the cap, tied in at the collar, frayed and sooty at the bottom (6 x 12 texels at x 0-5, y 0-11; the same in every frame), and the
    ember banked at the burner, a nugget of coal whose glow swells and dims (its sides 4 x 3 texels at x 8-11, y 0-2, its top 4 x 4 at x 8-11, y 3-6)."""
    strip = Image.new('RGBA', (N, N * WICK_FRAMES), (0, 0, 0, 0))
    mw, mh = 6, 12
    widths = [4, 4, 5, 6, 6, 6, 6, 6, 6, 5, 4, 4]
    mant = np.zeros((mh, mw, 4), np.uint8)
    for y in range(mh):
        w = widths[y]
        x0 = (mw - w) // 2
        for x in range(x0, x0 + w):
            if y == mh - 1 and x % 2 == 0:
                continue                                                   # the frayed fringe
            # lit from the upper left; a diagonal of darker stitches across the weave
            shade = 3.4 - (x - x0) / max(1, w - 1) * 1.7 - (y / mh) * 1.1
            if (x + y) % 3 == 0:
                shade -= 0.9
            if y < 2:
                shade = 0.6 + 0.8 * ((x + y) % 2)                           # the collar, tied in
            tone = int(np.clip(round(shade), 0, 4))
            if y >= mh - 2:
                tone = min(tone, 1)                                          # sooty
            mant[y, x] = (*MANTLE_TONES[tone], 255)
    for f in range(WICK_FRAMES):
        frame = np.zeros((N, N, 4), np.uint8)
        frame[..., :3] = EMBER                                              # clear, but ember (see lamp_glow)
        frame[0:mh, 0:mw] = np.where(mant[..., 3:4] > 0, mant, frame[0:mh, 0:mw])
        p = 0.5 + 0.5 * math.sin(2 * math.pi * f / WICK_FRAMES)             # how bright the ember is
        body = np.array((150 + 60 * p, 50 + 40 * p, 22 + 10 * p))
        core = np.array((220 + 35 * p, 100 + 70 * p, 38 + 40 * p))
        spot = np.array((255, 170 + 70 * p, 80 + 90 * p))
        rim_ = np.array((96, 30, 20))
        side = np.zeros((3, 4, 3))
        side[:] = body
        side[0, :] = rim_ * 0.8 + body * 0.2
        side[2, :] = rim_
        side[1, 1:3] = core
        if p > 0.55:
            side[1, 1] = spot
        top = np.zeros((4, 4, 3))
        top[:] = body
        top[0, :] = rim_ * 0.6 + body * 0.4
        top[1:3, 1:3] = core
        top[1 + f % 2, 1 + (f // 2) % 2] = spot
        for blk, (x0, y0) in ((side, (8, 0)), (top, (8, 3))):
            h, w = blk.shape[:2]
            frame[y0:y0 + h, x0:x0 + w, :3] = np.clip(blk, 0, 255).round().astype(np.uint8)
            frame[y0:y0 + h, x0:x0 + w, 3] = 255
        strip.paste(Image.fromarray(frame, 'RGBA'), (0, f * N))
    return strip


LAMP_TEXTURES = {'particle': 'guildmark:block/lighthouse_lamp', 'brass': 'guildmark:block/lighthouse_lamp', 'lens': 'guildmark:block/lighthouse_lamp_lens'}
LIT_TEXTURES = {'core': 'guildmark:block/lighthouse_lamp_core', 'glow0': 'guildmark:block/lighthouse_lamp_glow',
                'glow1': 'guildmark:block/lighthouse_lamp_glow_1', 'glow2': 'guildmark:block/lighthouse_lamp_glow_2',
                'glow3': 'guildmark:block/lighthouse_lamp_glow_3'}
UNLIT_TEXTURES = {'wick': 'guildmark:block/lighthouse_lamp_wick', 'glint0': 'guildmark:block/lighthouse_lamp_glint',
                  'glint1': 'guildmark:block/lighthouse_lamp_glint_1'}


def brass_box(frm, to, rod=False, rows=1.5, down=True, up=True, tex='#brass'):
    """A box of the lamp's brass: its sides cut from the bar of the brass sheet (`rows` pixels high), its top and bottom from the plate."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    wx, wz = x1 - x0, z1 - z0
    side = lambda w: [2, 0, 2 + w, rows]
    f = {'north': face(tex, side(wx)), 'south': face(tex, side(wx)), 'east': face(tex, side(wz)), 'west': face(tex, side(wz))}
    if up:
        f['up'] = face(tex, [2, 2, 2 + wx, 2 + wz])
    if down:
        f['down'] = face(tex, [2, 2, 2 + wx, 2 + wz])
    return cuboid(frm, to, f)


def lamp_model(lit=True):
    """The lighthouse lamp, a whole block: a riveted brass base, four brass posts, a cap of three steps, and between them the great lens, a drum of glass
    with a Fresnel disc on each of its four sides.
    Lit (`lit=true`, from dusk to dawn and in rain): the glass lit from within (it glows at full light), the lamp's burning core inside it, and over the
    glass the light, a shimmering glow on each side (glowing).
    Out (`lit=false`): the glass dull, in it the mantle hanging from the cap and the ember banked at the burner (a faint glow), and over the glass the sun,
    a glint that sweeps it now and then."""
    els = []
    els.append(brass_box((1, 0, 1), (15, 1.5, 15)))
    els.append(brass_box((1.75, 1.5, 1.75), (14.25, 3, 14.25), up=True))
    els.append(brass_box((6, 3, 6), (10, 4.5, 10)))                                  # the burner the core stands on
    # the four posts, cut from the rod of the brass sheet
    for (x0, z0, v0) in ((2, 2, 0), (12, 2, 5), (2, 12, 3), (12, 12, 1.5)):
        f = {d: face('#brass', [0, v0, 2, v0 + 10]) for d in ('north', 'south', 'east', 'west')}
        f['up'] = face('#brass', [2, 2, 4, 4])
        els.append(cuboid((x0, 3, z0), (x0 + 2, 13, z0 + 2), f))
    sides = ('north', 'south', 'east', 'west')
    g = {d: face('#lens', [3.5, 3, 12.5, 13]) for d in sides}
    els.append(cuboid((3.5, 3, 3.5), (12.5, 13, 12.5), g, **(dict(light_emission=15, shade_direction_override='up') if lit else {})))
    if lit:
        c = {d: face('#core', [0, 0, 3.5, 7]) for d in sides}
        c['up'] = face('#core', [0, 7, 3.5, 10.5])
        c['down'] = face('#core', [0, 7, 3.5, 10.5])
        els.append(cuboid((6.25, 4.5, 6.25), (9.75, 11.5, 9.75), c, light_emission=15, shade_direction_override='up'))
    else:
        els.append(cuboid((6.5, 5.5, 6.5), (9.5, 11.5, 9.5), {d: face('#wick', [0, 0, 3, 6]) for d in sides}))        # the mantle
        e = {d: face('#wick', [4, 0, 6, 1.5]) for d in sides}
        e['up'] = face('#wick', [4, 1.5, 6, 3.5])
        els.append(cuboid((7, 4.5, 7), (9, 6, 9), e, light_emission=6, shade_direction_override='up'))              # the ember
    els.append(brass_box((6.5, 11.5, 6.5), (9.5, 13, 9.5), up=False))                # what the core hangs from
    if lit:
        o = {'north': face('#glow0', [3.5, 3, 12.5, 13]), 'east': face('#glow1', [3.5, 3, 12.5, 13]),
             'south': face('#glow2', [3.5, 3, 12.5, 13]), 'west': face('#glow3', [3.5, 3, 12.5, 13])}
        els.append(cuboid((3.46, 3, 3.46), (12.54, 13, 12.54), o, light_emission=15, shade_direction_override='up'))
    else:
        o = {d: face('#glint0' if d in ('north', 'south') else '#glint1', [3.5, 3, 12.5, 13]) for d in sides}
        els.append(cuboid((3.46, 3, 3.46), (12.54, 13, 12.54), o))
    # the cap, three steps
    els.append(brass_box((1.75, 13, 1.75), (14.25, 14, 14.25), rows=1.0))
    els.append(brass_box((3.5, 14, 3.5), (12.5, 15, 12.5), rows=1.0, down=False))
    els.append(brass_box((5.5, 15, 5.5), (10.5, 16, 10.5), rows=1.0, down=False))
    return {'parent': 'minecraft:block/block', 'textures': {**LAMP_TEXTURES, **(LIT_TEXTURES if lit else UNLIT_TEXTURES)}, 'elements': els}


# ================================================================ the lighthouse beam

BEAM_W, BEAM_H = 32, 256             # lighthouse_beam.png: across the beam, and along it from the lamp (row 0) to its far end
TILE_W, TILE_H = 32, 64              # lighthouse_beam_tile.png: across, and one repeat along


def _beam_colour(p):
    """The beam's colour by how far toward its axis a texel is (0 at the soft edge, 1 on the axis): amber at the edges, white-gold on the axis."""
    p = np.clip(p, 0, 1)[..., None]
    return np.array((255, 206, 118), float) * (1 - p) + np.array((255, 247, 224), float) * p


def lighthouse_beam():
    """lighthouse_beam.png, 32 x 256, for the beam drawn once along its length: u across (soft to nothing at both edges), v along from the lamp (v = 0, the
    top row, the brightest) to the far end (v = 1, quite clear). An edge-to-edge soft glow, a brighter axis, and a faint streaking of the air."""
    xs = (np.arange(BEAM_W) + 0.5) / BEAM_W
    vs = (np.arange(BEAM_H) + 0.5) / BEAM_H
    u, v = np.meshgrid(xs, vs)
    s = np.abs(2 * u - 1)                                           # 0 on the axis, 1 at an edge
    across = np.clip(1 - s ** 2, 0, 1) ** 1.7
    along = np.clip(1 - v, 0, 1) ** 1.45
    axis = np.exp(-(s / (0.20 + 0.45 * v)) ** 2) * np.clip(1 - v, 0, 1) ** 2.2
    # the air is never perfectly even: soft streaks along the beam, a little stronger near the edges
    streak = fbm(BEAM_W, BEAM_H, 5, 51) * 0.6 + vnoise(BEAM_W, BEAM_H, 3, 52) * 0.4
    mod = 1.0 + 0.16 * (streak - 0.5) * (0.5 + s)
    a = np.clip((0.82 * across * along + 0.30 * axis) * mod, 0, 1)
    rgb = _beam_colour(np.clip(1 - s ** 1.3, 0, 1) * (0.55 + 0.45 * (1 - v)[..., None][..., 0]))
    out = np.dstack([rgb, a * 255]).round().astype(np.uint8)
    return Image.fromarray(out, 'RGBA')


def lighthouse_beam_tile():
    """lighthouse_beam_tile.png, 32 x 64, a strip that joins itself along its length (and so can be repeated and scrolled): the same soft edges across, the
    shafts and swells of lit air along it, and a few motes of dust. The far end's fade is not in it; fade it by vertex alpha."""
    xs = (np.arange(TILE_W) + 0.5) / TILE_W
    vs = (np.arange(TILE_H) + 0.5) / TILE_H
    u, v = np.meshgrid(xs, vs)
    s = np.abs(2 * u - 1)
    across = np.clip(1 - s ** 2, 0, 1) ** 1.5
    swell = 0.62 + 0.38 * vnoise(TILE_W, TILE_H, 22, 61)                      # slow brightening and dimming along the beam (wraps at the ends)
    shafts = vnoise(TILE_W, TILE_H, 6, 62)                                    # soft streaks across
    mod = swell * (0.78 + 0.34 * shafts)
    a = np.clip(0.62 * across * mod, 0, 1)
    # motes of dust: soft dots, which wrap round the ends
    rng = np.random.default_rng(63)
    for _ in range(7):
        cx, cy = rng.uniform(0.18, 0.82) * TILE_W, rng.uniform(0, TILE_H)
        rr = rng.uniform(1.2, 2.0)
        for oy in (-TILE_H, 0, TILE_H):
            d = np.hypot(u * TILE_W - cx, v * TILE_H - (cy + oy))
            a = np.clip(a + 0.55 * np.exp(-(d / rr) ** 2) * across ** 0.5, 0, 1)
    rgb = _beam_colour(np.clip(1 - s ** 1.3, 0, 1) * (0.6 + 0.4 * swell))
    out = np.dstack([rgb, a * 255]).round().astype(np.uint8)
    return Image.fromarray(out, 'RGBA')


# ================================================================ every file

ASSETS = os.path.normpath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark'))
NEST_ITEM_EGGS = 3                       # the nest's item shows the nest with all three eggs in it


def text_json(obj):
    return dump_json(obj) + '\n'


def anim_meta(frametime, interpolate=True):
    a = {'frametime': frametime}
    if interpolate:
        a['interpolate'] = True
    return text_json({'animation': a})


def sampler_meta(clamp):
    """The .mcmeta of an entity texture that is drawn smooth (the game's default is nearest-texel) and either clamped at its edges or repeating."""
    return text_json({'texture': {'blur': True, 'clamp': clamp}})


def item_definition(model):
    return text_json({'model': {'type': 'minecraft:model', 'model': model}})


def flat_item_model(texture):
    return text_json({'parent': 'minecraft:item/generated', 'textures': {'layer0': texture}})


def nest_blockstate():
    """One of four quarter turns by position for each count of eggs, so a rookery of nests is not a row of copies."""
    variants = {}
    for e in range(4):
        m = f'guildmark:block/gull_nest_{e}'
        variants[f'eggs={e}'] = [{'model': m}] + [{'model': m, 'y': y} for y in (90, 180, 270)]
    return text_json({'variants': variants})


def build():
    """Every file of the art as {path under the assets dir: a PIL image (written as a PNG) or the text of a file}, in the order they are written."""
    files = {}
    for k in KINDS:
        files[f'textures/gui/sprites/map/{sprite_name(k)}.png'] = map_icon(k)
        files[f'textures/gui/sprites/compass/{sprite_name(k)}.png'] = compass_mark(k)
    for name, art in (('gull_egg', gull_egg()), ('seared_gull_egg', seared_gull_egg())):
        files[f'textures/item/{name}.png'] = art
        files[f'models/item/{name}.json'] = flat_item_model(f'guildmark:item/{name}')
        files[f'items/{name}.json'] = item_definition(f'guildmark:item/{name}')
    files['textures/block/gull_nest.png'] = nest_twigs()
    files['textures/block/gull_nest_top.png'] = nest_lining()
    files['textures/block/gull_nest_egg.png'] = nest_shell()
    for e in range(4):
        files[f'models/block/gull_nest_{e}.json'] = text_json(nest_model(e))
    files['blockstates/gull_nest.json'] = nest_blockstate()
    files['items/gull_nest.json'] = item_definition(f'guildmark:block/gull_nest_{NEST_ITEM_EGGS}')
    files['textures/block/lighthouse_lamp.png'] = lamp_brass()
    files['textures/block/lighthouse_lamp_lens.png'] = lamp_lens()
    files['textures/block/lighthouse_lamp_core.png'] = lamp_core()
    files['textures/block/lighthouse_lamp_core.png.mcmeta'] = anim_meta(CORE_FRAMETIME)
    for f in range(4):
        stem = 'lighthouse_lamp_glow' + ('' if f == 0 else f'_{f}')
        files[f'textures/block/{stem}.png'] = lamp_glow(f)
        files[f'textures/block/{stem}.png.mcmeta'] = anim_meta(GLOW_FRAMETIME)
    for k in range(2):
        stem = 'lighthouse_lamp_glint' + ('' if k == 0 else f'_{k}')
        files[f'textures/block/{stem}.png'] = lamp_glint(0.5 * k)
        files[f'textures/block/{stem}.png.mcmeta'] = anim_meta(GLINT_FRAMETIME)
    files['textures/block/lighthouse_lamp_wick.png'] = lamp_wick()
    files['textures/block/lighthouse_lamp_wick.png.mcmeta'] = anim_meta(WICK_FRAMETIME)
    files['models/block/lighthouse_lamp.json'] = text_json(lamp_model(True))
    files['models/block/lighthouse_lamp_unlit.json'] = text_json(lamp_model(False))
    files['blockstates/lighthouse_lamp.json'] = text_json({'variants': {'lit=false': {'model': 'guildmark:block/lighthouse_lamp_unlit'},
                                                                         'lit=true': {'model': 'guildmark:block/lighthouse_lamp'}}})
    files['items/lighthouse_lamp.json'] = item_definition('guildmark:block/lighthouse_lamp')
    files['textures/entity/island/lighthouse_beam.png'] = lighthouse_beam()
    files['textures/entity/island/lighthouse_beam.png.mcmeta'] = sampler_meta(True)
    files['textures/entity/island/lighthouse_beam_tile.png'] = lighthouse_beam_tile()
    files['textures/entity/island/lighthouse_beam_tile.png.mcmeta'] = sampler_meta(False)
    return files


def write_files(root, files):
    for rel, payload in files.items():
        path = os.path.join(root, *rel.split('/'))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if isinstance(payload, Image.Image):
            payload.save(path)
        else:
            with open(path, 'w', newline='\n') as fh:
                fh.write(payload)


def check(files):
    """The files agree with each other: every texture a model names is among them, every face names a texture the model has, every uv lies inside the
    texture (0 to 16) and every cube inside the game's -16 to 32, every model a blockstate or an item names is there, an animated strip is whole
    frames with a .mcmeta, and the icons are the older icons' sizes. Returns a list of what is wrong."""
    bad = []
    have = set(files)

    def tex_ok(ref):
        ns, _, p = ref.partition(':')
        return ns == 'guildmark' and f'textures/{p}.png' in have

    for rel, payload in files.items():
        if rel.endswith('.png'):
            w, h = payload.size
            if rel.startswith('textures/gui/sprites/map/') and (w, h) != (22, 22):
                bad.append(f'{rel}: {w}x{h}, not 22x22')
            if rel.startswith('textures/gui/sprites/compass/') and (w, h) != (14, 14):
                bad.append(f'{rel}: {w}x{h}, not 14x14')
            if h != w and (h % w or rel + '.mcmeta' not in have):
                bad.append(f'{rel}: {w}x{h} is not a whole strip with a .mcmeta')
        elif rel.startswith('models/block/'):
            m = json.loads(payload)
            for k, ref in m['textures'].items():
                if not tex_ok(ref):
                    bad.append(f'{rel}: texture {k} -> {ref} is not made')
            for i, e in enumerate(m['elements']):
                for v in e['from'] + e['to']:
                    if not -16 <= v <= 32:
                        bad.append(f'{rel}: element {i} is outside -16..32')
                for d, f in e['faces'].items():
                    if f['texture'].lstrip('#') not in m['textures']:
                        bad.append(f'{rel}: element {i} {d} names {f["texture"]}')
                    if not all(0 <= v <= 16 for v in f['uv']):
                        bad.append(f'{rel}: element {i} {d} has uv {f["uv"]}')
        elif rel.startswith('blockstates/'):
            for key, v in json.loads(payload)['variants'].items():
                for var in (v if isinstance(v, list) else [v]):
                    if f'models/block/{var["model"].split("/")[-1]}.json' not in have:
                        bad.append(f'{rel}: {key} names {var["model"]}')
        elif rel.startswith('items/'):
            ref = json.loads(payload)['model']['model']
            kind, name = ref.split(':')[1].split('/')
            if f'models/{kind}/{name}.json' not in have:
                bad.append(f'{rel}: names {ref}')
        elif rel.startswith('models/item/'):
            for ref in json.loads(payload)['textures'].values():
                if not tex_ok(ref):
                    bad.append(f'{rel}: {ref} is not made')
    return bad


# ================================================================ the contact sheet

SHEET_BG = (27, 27, 33)
PARCHMENT = (206, 184, 140)
SEA = (60, 90, 110)


def _font(size, bold=False):
    from PIL import ImageFont
    for path in (('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',) if bold else ()) + ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
                                                                                             '/usr/share/fonts/truetype/freefont/FreeSans.ttf'):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return ImageFont.load_default(size=size)


def _magnified(im, mag, bg, checker=False):
    """An image magnified with hard edges over a flat colour, or over a grey checker to show what is clear."""
    w, h = im.width * mag, im.height * mag
    t = Image.new('RGBA', (w, h), tuple(bg) + (255,))
    if checker:
        c = np.indices((h, w)).sum(0) // max(4, mag * 2) % 2
        t = Image.fromarray(np.where(c[..., None] == 0, (58, 58, 66, 255), (44, 44, 52, 255)).astype(np.uint8), 'RGBA')
    t.alpha_composite(im.convert('RGBA').resize((w, h), Image.NEAREST))
    return t


def _bilinear(tex, u, v):
    """Sample an RGBA float image at (u, v) in 0..1 with clamped edges."""
    h, w = tex.shape[:2]
    x = np.clip(u * w - 0.5, 0, w - 1)
    y = np.clip(v * h - 0.5, 0, h - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = (x - x0)[..., None], (y - y0)[..., None]
    return (tex[y0, x0] * (1 - fx) * (1 - fy) + tex[y0, x1] * fx * (1 - fy) + tex[y1, x0] * (1 - fx) * fy + tex[y1, x1] * fx * fy)


def beam_scene(assets, size=(1200, 330)):
    """The beam drawn as the game will: a soft cone from the lamp over a night sea, the whole beam texture stretched once along it."""
    W, H = size
    rng = np.random.default_rng(7)
    ys = np.linspace(0, 1, H)[:, None]
    sky = np.array((7, 10, 24), float) * (1 - ys) + np.array((26, 40, 70), float) * ys
    img = np.broadcast_to(sky[:, None, :], (H, W, 3)).copy()
    for _ in range(90):
        sx, sy = int(rng.integers(0, W)), int(rng.integers(0, int(H * 0.62)))
        img[sy, sx] = np.maximum(img[sy, sx], 90 + 120 * rng.random())
    sea_y = int(H * 0.80)
    gy = np.arange(H)[:, None]
    swell = 0.5 + 0.5 * np.sin(np.arange(W)[None, :] * 0.11 + gy * 0.9)
    sea = np.array((10, 20, 38), float) * (0.8 + 0.4 * swell[..., None])
    img[sea_y:] = sea[sea_y:]
    tex = np.asarray(Image.open(os.path.join(assets, 'textures', 'entity', 'island', 'lighthouse_beam.png')).convert('RGBA'), np.float32)
    x0, cy, length = 96, int(H * 0.42), W - 120
    gx, gyy = np.meshgrid(np.arange(W), np.arange(H))
    t = (gx - x0) / length
    half = 7.0 + 120.0 * np.clip(t, 0, None)
    u = 0.5 + (gyy - cy) / (2 * half)
    inside = (t >= 0) & (t <= 1) & (u >= 0) & (u <= 1)
    s = _bilinear(tex, np.clip(u, 0, 1), np.clip(t, 0, 1))
    a = s[..., 3:4] / 255.0 * inside[..., None]
    img = img * (1 - a) + s[..., :3] * a
    # the lamp's own bloom at the root
    r = np.hypot(gx - x0, gyy - cy)
    img = img + np.exp(-(r / 14.0) ** 2)[..., None] * np.array((255, 232, 170)) * 0.9
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')
    from PIL import ImageDraw
    d = ImageDraw.Draw(out)
    d.rectangle([x0 - 15, cy + 12, x0 + 15, sea_y], fill=(18, 20, 28))                       # the tower, as a silhouette
    d.polygon([(x0 - 18, cy + 12), (x0 + 18, cy + 12), (x0 + 11, cy + 5), (x0 - 11, cy + 5)], fill=(18, 20, 28))
    d.rectangle([x0 - 8, cy - 4, x0 + 8, cy + 6], fill=(255, 226, 150))
    return out


def contact_sheet(assets, path):
    """island_art.png: every bit of this art, magnified, the blocks drawn from their own model files, laid out for looking at."""
    from PIL import ImageDraw
    import island_blockview as BV
    W, M = 1480, 28
    sheet = Image.new('RGBA', (W, 6000), SHEET_BG + (255,))
    d = ImageDraw.Draw(sheet)
    F, FB, FS = _font(13), _font(17, True), _font(11)
    ink, dim = (226, 222, 210), (150, 148, 142)
    y = M

    def heading(text, note=''):
        nonlocal y
        d.line([(M, y), (W - M, y)], fill=(70, 68, 76), width=1)
        d.text((M, y + 8), text, font=FB, fill=ink)
        if note:
            d.text((M + 14 + d.textlength(text, font=FB), y + 11), note, font=F, fill=dim)
        y += 40

    def put(im, x, yy, caption=None, cap_w=None):
        sheet.alpha_composite(im, (x, yy))
        if caption:
            tw = d.textlength(caption, font=FS)
            d.text((x + (im.width - tw) / 2 if cap_w is None else x + (cap_w - tw) / 2, yy + im.height + 5), caption, font=FS, fill=dim)

    def have(rel):
        return os.path.exists(os.path.join(assets, *rel.split('/')))

    def tex(rel):
        return Image.open(os.path.join(assets, *rel.split('/'))).convert('RGBA')

    d.text((M, y), 'Guildmark islands: the small art', font=_font(24, True), fill=ink)
    y += 36
    d.text((M, y), 'island_art.py.  Every pixel and every model comes from the script; the blocks below are drawn from their written model files by '
           'island_blockview.py (no game, no ambient occlusion, no light).', font=F, fill=dim)
    y += 34

    # ---- icons
    heading('Map icons', '22 x 22, shown 6x on parchment; the compass marks 14 x 14, shown 8x over a sea-blue world')
    cell = (W - 2 * M) // len(KINDS)
    for i, k in enumerate(KINDS):
        x = M + i * cell
        put(_magnified(tex(f'textures/gui/sprites/map/{sprite_name(k)}.png'), 6, PARCHMENT), x, y)
        put(_magnified(tex(f'textures/gui/sprites/compass/{sprite_name(k)}.png'), 8, SEA), x + 10, y + 142, sprite_name(k), 112)
    y += 142 + 112 + 30
    older = [n for n in ('ship', 'town', 'village', 'lair', 'vault', 'wardfire') if have(f'textures/gui/sprites/map/{n}.png')]
    if older:
        d.text((M, y), 'beside the older markers, at 4x', font=F, fill=dim)
        y += 22
        x = M
        for n in older:
            put(_magnified(tex(f'textures/gui/sprites/map/{n}.png'), 4, PARCHMENT), x, y)
            x += 22 * 4 + 12
        x += 24
        for k in KINDS:
            put(_magnified(tex(f'textures/gui/sprites/map/{sprite_name(k)}.png'), 4, PARCHMENT), x, y)
            x += 22 * 4 + 12
        y += 22 * 4 + 14
        x = M
        for n in older:
            if have(f'textures/gui/sprites/compass/{n}.png'):
                put(_magnified(tex(f'textures/gui/sprites/compass/{n}.png'), 6, SEA), x, y)
            x += 22 * 4 + 12
        x += 24
        for k in KINDS:
            put(_magnified(tex(f'textures/gui/sprites/compass/{sprite_name(k)}.png'), 6, SEA), x, y)
            x += 22 * 4 + 12
        y += 14 * 6 + 24

    # ---- items, and the block items as the inventory shows them
    heading('Items', '32 x 32, shown 6x;  then the block items in a slot, as the inventory draws them (from the north-east, 30 degrees up), shown 10x')
    x = M
    for n in ('gull_egg', 'seared_gull_egg'):
        put(_magnified(tex(f'textures/item/{n}.png'), 6, (44, 44, 52)), x, y, n)
        x += 192 + 18
    x += 14
    for name, label in (('gull_nest_0', 'gull_nest eggs=0'), ('gull_nest_1', 'eggs=1'), ('gull_nest_2', 'eggs=2'), ('gull_nest_3', 'eggs=3 (the item)'),
                        ('lighthouse_lamp', 'lighthouse_lamp')):
        img = BV.render_model(BV.load_model(assets, name), assets, yaw=135, elev=30, scale=0.625 * 10, size=(160, 160), bg=(44, 44, 52), tick=6, ss=4)
        put(Image.fromarray(img, 'RGB').convert('RGBA'), x, y + 16, label)
        x += 160 + 18
    y += 192 + 34

    # ---- block textures
    heading('Block textures', '32 x 32, shown 6x; below them the animated strips, a frame to a picture (the lamp core 8 frames of 2 ticks, the glow 40 of 2, '
            'the glint 48 of 2, the wick 8 of 5)')
    x = M
    for rel, label in (('gull_nest', 'gull_nest: twig sheet'), ('gull_nest_top', 'gull_nest_top: lining'), ('gull_nest_egg', 'gull_nest_egg: shell'),
                       ('lighthouse_lamp', 'lighthouse_lamp: brass'), ('lighthouse_lamp_lens', 'lighthouse_lamp_lens')):
        put(_magnified(tex(f'textures/block/{rel}.png'), 6, (44, 44, 52), checker=(rel == 'lighthouse_lamp_lens')), x, y, label, 192)
        x += 192 + 16
    y += 192 + 30

    def strip_row(rel, caption, frames, mag, crop=None, gap=8):
        """A row of frames of an animated strip, `frames` of its frame numbers, each magnified `mag`, over a checker; wraps at the sheet's edge."""
        nonlocal y
        strip = tex(f'textures/block/{rel}.png')
        d.text((M, y), caption, font=F, fill=dim)
        y += 20
        x = M
        row_h = 0
        for f in frames:
            fr = strip.crop((0, f * strip.width, strip.width, (f + 1) * strip.width))
            if crop:
                fr = fr.crop(crop)
            im = _magnified(fr, mag, (14, 14, 20), checker=True)
            if x + im.width > W - M:
                x = M
                y += row_h + 22
            put(im, x, y, str(f), im.width)
            row_h = im.height
            x += im.width + gap
        y += row_h + 26

    strip_row('lighthouse_lamp_core', 'lighthouse_lamp_core: the flame as the sides of the core show it (above) and as its two ends do (below, round)',
              range(8), 4)
    strip_row('lighthouse_lamp_glow', 'lighthouse_lamp_glow (the north face; _1, _2, _3 are the same a quarter, a half and three quarters of a run later): '
              'every fourth of its 40 frames', range(0, 40, 4), 2, gap=27)
    strip_row('lighthouse_lamp_glint', 'lighthouse_lamp_glint (_1 is half a cycle later): the sweep and the twinkle, in the first half of its 48 frames, every second',
              range(0, 24, 2), 2, gap=27)
    strip_row('lighthouse_lamp_wick', 'lighthouse_lamp_wick: the mantle (left) and the ember\'s side and top (right); the top left 16 x 14 texels of each frame',
              range(8), 5, crop=(0, 0, 16, 14), gap=14)
    y += 8

    # ---- the nest, drawn from its model files
    heading('Gull nest', 'models/block/gull_nest_0 .. _3, from the south-east and from the north-west; a block is 16 pixels, drawn 14x')
    x = M
    for e in range(4):
        m = BV.load_model(assets, f'gull_nest_{e}')
        for j, (yaw, elev) in enumerate(((45, 32), (225, 58))):
            img = BV.render_model(m, assets, yaw=yaw, elev=elev, scale=18.0, size=(272, 272), bg=(74, 78, 90), center=(8, 3, 8))
            put(Image.fromarray(img, 'RGB').convert('RGBA'), x, y + j * 280, f'eggs={e}' if j == 0 else None)
        x += 272 + 14
    y += 2 * 280 + 24

    # ---- the lamp
    heading('Lighthouse lamp', 'blockstates/lighthouse_lamp.json: lit=true is models/block/lighthouse_lamp, lit=false is lighthouse_lamp_unlit; the renderer adds the beams')
    NIGHT, DAY = (16, 20, 32), (132, 170, 210)
    lit, out = BV.load_model(assets, 'lighthouse_lamp'), BV.load_model(assets, 'lighthouse_lamp_unlit')

    def lamp_views(model, views, bg, ambient, bloom):
        nonlocal y
        x = M
        for yaw, elev, tick, label in views:
            img = BV.render_model(model, assets, yaw=yaw, elev=elev, scale=19.0, size=(340, 340), bg=bg, tick=tick, center=(8, 8, 8), bloom=bloom, ambient=ambient)
            put(Image.fromarray(img, 'RGB').convert('RGBA'), x, y, label)
            x += 340 + 14
        y += 340 + 30

    d.text((M, y), 'lit, at night (a soft bloom drawn round what glows; the game has none): the glow shimmers, a ring of light running out through the prisms', font=F, fill=dim)
    y += 22
    lamp_views(lit, [(45, 22, t, f'tick {t}') for t in (0, 20, 40, 60)], NIGHT, 0.9, 0.5)
    d.text((M, y), 'lit, each side seen from its own side at one moment: the rings are at a different place on each', font=F, fill=dim)
    y += 22
    lamp_views(lit, [(yaw, 8, 24, label) for yaw, label in ((0, 'south'), (90, 'east'), (180, 'north'), (270, 'west'))], NIGHT, 0.9, 0.5)
    d.text((M, y), 'out, by day: the sun sweeps the glass (north and south first, east and west half a cycle later), the ember banked at the burner', font=F, fill=dim)
    y += 22
    lamp_views(out, [(45, 22, 8, 'tick 8'), (45, 22, 16, 'tick 16: south glint twinkles'), (45, 22, 56, 'tick 56'), (45, 22, 64, 'tick 64: east glint twinkles')],
               DAY, 1.0, 0.0)
    lamp_views(out, [(0, 6, 16, 'out, face-on from the south, tick 16'), (0, 6, 40, 'tick 40: nothing glints'), (270, 6, 64, 'the west face, tick 64'),
                     (45, 60, 40, 'from above')], DAY, 1.0, 0.0)

    # ---- the beam
    heading('Lighthouse beam', 'textures/entity/island/lighthouse_beam.png, 32 x 256, stretched once along a cone;  and the 32 x 64 tile, repeated along')
    scene = beam_scene(assets, (W - 2 * M, 330))
    put(scene, M, y)
    y += 330 + 14
    tile = tex('textures/entity/island/lighthouse_beam_tile.png')
    strip = Image.new('RGBA', (tile.height * 6, tile.width), (8, 12, 26, 255))
    for i in range(6):
        strip.alpha_composite(tile.transpose(Image.Transpose.ROTATE_90), (i * tile.height, 0))
    strip = strip.resize((strip.width * 3, strip.height * 3), Image.BILINEAR)
    put(strip, M, y)
    for i in range(1, 6):
        d.line([(M + i * tile.height * 3, y), (M + i * tile.height * 3, y + 4)], fill=(120, 120, 130), width=1)
    d.text((M + strip.width + 16, y + 30), 'the tile, six repeats, a mark at each join', font=F, fill=dim)
    y += strip.height + 20
    out = sheet.crop((0, 0, W, y + M // 2)).convert('RGB')
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    out.save(path)
    return out.size


# ================================================================ the command line

def main(assets=None, prev=None):
    assets = os.path.abspath(assets) if assets and assets != '-' else ASSETS
    files = build()
    bad = check(files)
    if bad:
        raise SystemExit('island_art: the files disagree:\n  ' + '\n  '.join(bad))
    write_files(assets, files)
    print(f'island_art: {len(files)} files written under {assets}')
    if prev:
        size = contact_sheet(assets, os.path.join(prev, 'island_art.png'))
        print(f'island_art: contact sheet {size[0]}x{size[1]} at {os.path.join(prev, "island_art.png")}')


if __name__ == '__main__':
    main(*(sys.argv[1:3]))
