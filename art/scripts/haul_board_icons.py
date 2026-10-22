"""The Haul Board's small pictures, painted at the HD scale with the art kit (hdkit.py): a chip for each of the 23 goods in red
(short) and green (glut), a badge for each kind of event, the four road pennants, the hourglass in eight sand levels, the
booker's seal in six colours, the notch row, the dealer's-lot mark, the relief cross, the feast festoon and the struck-through
chalk. haul_board_art.py packs them into the atlas, the sprites and the Kotlin table; this module only paints.

Every picture is made by a function of its own that gives a 'Pic' (an RGBA image) at its texel size: 32 x 32 for a chip or a badge (a good's own picture is 24 x 24 inside it).
Light is from the upper left, a few hard tones to a material, a fine dark outline round each thing.

    python3 -B art/scripts/haul_board_icons.py [preview-dir]

writes contact sheets of everything painted here (at 8x, on a dark and a light ground, and at the sizes the screen and the board
draw them) for a look, and nothing else.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, fbm, rand, bands, poly, seg_dist, disc, ellipse, rect, blur, shift, grow,
                   light, rim, outline, glint, bevel, tone, mix, edge, dist_in)

# ================================================================ the goods, in the market board's order (economy/MarketBoards.ORDER)

GOODS = ['bread', 'wheat', 'carrot', 'potato', 'beetroot', 'apple', 'honey', 'cod', 'salmon', 'beef', 'pork', 'mutton', 'chicken',
         'wool', 'string', 'leather', 'feather', 'arrow', 'flint', 'candle', 'bottle', 'iron', 'coal']

# economy/Market.kt's EventKind ids, in its order
EVENTS = ['blight', 'bumper', 'murrain', 'fowl_pest', 'mill_broken', 'oven_cracked', 'good_catch', 'poor_catch', 'fever', 'waylaid']
GOOD_EVENTS = {'bumper', 'good_catch'}                      # the two that are good news; the rest are bad

# a road's state (caravan stages: open, troubled, perilous, closed) and the pennant that flies for it
ROADS = ['open', 'troubled', 'perilous', 'closed']

# ================================================================ ramps

CRUST = [(58, 32, 14), (108, 62, 22), (158, 98, 36), (204, 142, 60), (238, 186, 98)]
STRAW = [(42, 28, 14), (86, 60, 26), (134, 98, 40), (180, 138, 58), (216, 178, 88), (242, 216, 142)]
CARROT = [(70, 24, 8), (140, 56, 12), (206, 100, 24), (240, 148, 48), (252, 196, 102)]
SPUD = [(50, 34, 22), (96, 68, 40), (146, 108, 64), (188, 148, 92), (220, 184, 124)]
BEET = [(36, 8, 22), (76, 18, 42), (122, 32, 64), (164, 54, 86), (204, 90, 114)]
LEAF = [(18, 40, 20), (34, 70, 30), (58, 108, 40), (92, 148, 58), (140, 190, 92)]
COD = [(30, 34, 30), (62, 70, 58), (104, 112, 86), (150, 156, 116), (196, 198, 152)]
SALMON = [(40, 22, 30), (110, 48, 52), (190, 84, 72), (232, 132, 104), (250, 186, 150)]
SILVER = [(34, 40, 52), (70, 80, 98), (118, 130, 148), (172, 184, 196), (226, 234, 240)]
BEEF = [(40, 8, 14), (88, 18, 26), (140, 32, 40), (186, 58, 60), (222, 98, 92)]
HAM = [(70, 26, 34), (130, 52, 60), (184, 90, 92), (222, 132, 126), (244, 176, 164)]
LAMB = [(46, 18, 16), (98, 40, 30), (148, 66, 46), (190, 100, 68), (224, 142, 102)]
ROAST = [(60, 30, 10), (116, 64, 20), (170, 106, 34), (214, 152, 60), (242, 196, 110)]
FAT = [(120, 100, 86), (170, 150, 130), (214, 198, 176), (238, 228, 210)]
FLEECE = [(60, 56, 60), (112, 108, 112), (166, 162, 160), (212, 208, 200), (244, 240, 232)]
TWINE = [(70, 54, 34), (122, 98, 62), (170, 142, 92), (208, 182, 128), (236, 214, 160)]
PLUME = [(70, 74, 86), (124, 130, 142), (176, 182, 190), (218, 222, 226), (246, 248, 250)]
FLINT = [(18, 22, 30), (40, 48, 60), (72, 82, 98), (110, 122, 138), (160, 170, 182)]
TALLOW = [(110, 96, 70), (170, 154, 116), (212, 198, 160), (240, 230, 200), (252, 246, 226)]
FLAME = [(190, 88, 28), (230, 136, 46), (250, 186, 86), (255, 228, 160)]
GLASS_G = [(10, 30, 24), (20, 62, 44), (40, 104, 70), (80, 152, 104), (150, 210, 160)]
COAL = [(8, 8, 12), (20, 20, 28), (36, 38, 50), (58, 62, 80), (96, 104, 128)]
HONEY = GEMS['amber']
APPLE = GEMS['red']
INDIGO = [(10, 12, 24), (20, 26, 46), (32, 42, 72), (48, 62, 98), (68, 86, 124), (94, 112, 150)]
SILK = [(30, 6, 14), (66, 12, 24), (112, 22, 34), (158, 38, 46), (198, 66, 62), (232, 112, 94)]


# ================================================================ a small painter for one picture

class Pic:
    """A picture to paint: masks (disc, ellipse, polygon, line, rectangle), flat tones off a material's ramp with a lit edge
    up-left and a dark one down-right, and the finish (the dark outline)."""

    def __init__(self, w, h=None):
        self.w, self.h = w, h or w
        self.xs, self.ys = grid(self.w, self.h)
        self.cv = Canvas(self.w, self.h)

    # masks
    def D(self, cx, cy, r):
        return disc(cx, cy, r, self.w, self.h)[0]

    def E(self, cx, cy, rx, ry, rot=0.0):
        """An ellipse, its x axis turned [rot] degrees (clockwise on the screen)."""
        if not rot:
            return ellipse(cx, cy, rx, ry, self.w, self.h)[0]
        a = math.radians(rot)
        dx, dy = self.xs - cx, self.ys - cy
        u = dx * math.cos(a) + dy * math.sin(a)
        v = -dx * math.sin(a) + dy * math.cos(a)
        return (u / rx) ** 2 + (v / ry) ** 2 <= 1.0

    def P(self, pts):
        return poly(pts, self.w, self.h)

    def L(self, x0, y0, x1, y1, w=1.0):
        return seg_dist(x0, y0, x1, y1, self.w, self.h)[0] < w / 2

    def R(self, x0, y0, x1, y1):
        return rect(int(math.floor(x0)), int(math.floor(y0)), int(math.floor(x1)), int(math.floor(y1)), self.w, self.h)

    def path(self, pts, w=1.0):
        """A polyline [w] thick."""
        m = np.zeros((self.h, self.w), bool)
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            m |= self.L(x0, y0, x1, y1, w)
        return m

    # shading
    def sh(self, cx, cy, r):
        """-1..1 across a round thing: +1 at its upper left, -1 at its lower right."""
        return np.clip(-((self.xs - cx) + (self.ys - cy)) / (2.0 * r), -1, 1)

    def flat(self, mask, ramp, t):
        self.cv.put(mask, bands(ramp, np.broadcast_to(np.asarray(t, float), (self.h, self.w)), 0.0))

    def round(self, mask, ramp, cx, cy, r, base=0.5, k=0.3, dither=0.0):
        """A rounded thing: the tone follows the light across [r] round (cx, cy)."""
        t = base + k * self.sh(cx, cy, r)
        self.cv.put(mask, bands(ramp, t, dither))

    def cel(self, mask, ramp, base=0.55, hi=0.22, lo=0.24, lw=1, dw=1, extra=0.0):
        """Flat at [base] with a lit edge [lw] px up-left and a shaded one [dw] px down-right."""
        m = mask.astype(bool)
        lit = np.zeros(m.shape, bool)
        dk = np.zeros(m.shape, bool)
        for k in range(1, lw + 1):
            lit |= (m & ~shift(m, k, 0)) | (m & ~shift(m, 0, k))
        for k in range(1, dw + 1):
            dk |= (m & ~shift(m, -k, 0)) | (m & ~shift(m, 0, -k))
        t = base + extra + hi * lit - lo * (dk & ~lit)
        self.cv.put(m, bands(ramp, t, 0.0))

    def px(self, x, y, col, a=1.0):
        self.cv.px(int(x), int(y), np.asarray(col, float), a)

    def pix(self, rows, x0, y0, key):
        """Hand-placed pixels: one string per row, each character a colour of [key] ('.' leaves a pixel alone)."""
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                c = key.get(ch)
                if c is not None:
                    self.px(x0 + i, y0 + j, c)

    def over_mask(self, mask, col, a):
        self.cv.over(mask, col, a)

    def finish(self, k=0.8, rim_k=0.22):
        before = self.cv.rgb.copy()
        rim(self.cv, rim_k)
        outline(self.cv, k)
        return self.cv.image()


def stops(ramp, i):
    return np.asarray(ramp[i], float)


# ================================================================ pictograms of the goods (each in a 24 x 24 picture, in about 16 x 16)

def g_bread(p):
    """A round loaf, scored three times, flour on its top."""
    body = p.E(12, 13.4, 8.2, 5.8)
    p.round(body, CRUST, 11, 11, 8, 0.52, 0.34, 0.3)
    p.flat(p.E(12, 17.4, 7.0, 1.6) & body, CRUST, 0.16)                 # the underside in shade
    for k in range(3):                                                    # the scores
        x = 8.0 + k * 3.7
        cut = p.path([(x, 9.4), (x + 2.4, 13.2)], 1.5) & body
        p.flat(cut, CRUST, 0.9)
        p.flat(p.path([(x + 0.8, 10.2), (x + 2.8, 13.6)], 1.0) & body, CRUST, 0.22)
    for (x, y) in ((9, 9), (12, 8), (15, 10), (11, 11)):                  # flour
        p.px(x, y, (250, 240, 214))


def ear(p, cx, cy, deg, rx=2.2, ry=4.2, awns=True):
    """An ear of wheat centred (cx, cy), its tip turned [deg] to the right of up: an oval of grain with chevrons of herringbone
    cut into it and the awns bristling from its tip. Painted in place."""
    a = math.radians(deg)
    dx, dy = p.xs - cx, p.ys - cy
    u = dx * math.cos(a) + dy * math.sin(a)                        # across the ear
    v = -(-dx * math.sin(a) + dy * math.cos(a))                    # along it, up positive
    body = (u / rx) ** 2 + (v / ry) ** 2 <= 1.0
    t = 0.66 + 0.3 * p.sh(cx, cy, ry)
    p.cv.put(body, bands(STRAW, t, 0.0))
    for k in range(-2, 3):                                           # the chevrons
        vk = k * 1.9
        chev = body & (np.abs(np.abs(u) * 0.85 - (vk - v)) < 0.42) & (v < vk + 0.2) & (np.abs(u) > 0.3)
        p.flat(chev, STRAW, 0.2)
    p.flat(body & (u < -rx * 0.55), STRAW, 0.95)                    # the lit side
    if awns:
        tx, ty = cx + math.sin(a) * ry, cy - math.cos(a) * ry
        for off in (-22, -7, 7, 22):
            q = a + math.radians(off)
            p.flat(p.path([(tx, ty), (tx + math.sin(q) * 3.8, ty - math.cos(q) * 3.8)], 0.8), STRAW, 0.88)


def g_wheat(p):
    """A little sheaf of three ears, bound with a red cord, its stalks fanning from the knot."""
    for (x0, y0, x1, y1) in ((12, 22, 7.8, 13), (12, 22, 16.2, 13), (12, 22, 12, 12.6)):
        p.flat(p.path([(x0, y0), (x1, y1)], 1.3), LEAF, 0.56)
    p.flat(p.P([(11.4, 21.4), (7.0, 21.6), (5.0, 18.4), (9.4, 19.6)]), LEAF, 0.7)            # a leaf
    ear(p, 7.8, 10.6, -22)
    ear(p, 16.2, 10.6, 22)
    ear(p, 12.0, 9.2, 0)
    p.flat(p.R(9, 17, 15, 19), SILK, 0.58)                                                    # the cord
    p.flat(p.R(9, 17, 15, 18), SILK, 0.84)


def g_carrot(p):
    """A carrot pointing down to the left, its green top at the upper right."""
    body = p.P([(15.4, 7.4), (18.0, 10.0), (6.2, 20.0), (4.6, 19.0)]) | p.E(16.6, 8.8, 2.2, 2.2)
    p.cel(body, CARROT, 0.58, 0.2, 0.24, 1, 2)
    for (x, y) in ((11, 12.6), (13.4, 10.6), (9.0, 15.0)):                 # the rings round it
        p.flat(p.path([(x - 1, y + 1.2), (x + 1.2, y - 0.9)], 0.9) & body, CARROT, 0.28)
    for (ax, ay, bx, by) in ((17.6, 7.4, 16.0, 2.4), (18.0, 8.2, 20.4, 3.6), (16.6, 6.6, 13.6, 3.4)):
        p.flat(p.path([(ax, ay), (bx, by)], 1.7), LEAF, 0.62)
        p.flat(p.path([(ax, ay), (bx, by)], 0.7), LEAF, 0.9)


def g_potato(p):
    """A lumpy potato, three eyes in it."""
    body = p.E(12, 12.6, 8.0, 5.8, rot=-18) | p.E(15.6, 11.2, 3.6, 3.4) | p.E(8.4, 14.4, 3.4, 3.0)
    p.round(body, SPUD, 10.5, 9.6, 8.4, 0.52, 0.34, 0.25)
    p.flat(p.E(15, 17, 6, 1.6) & body, SPUD, 0.14)
    for (x, y) in ((9.4, 12.0), (13.6, 10.4), (14.6, 14.4)):
        p.px(x, y, SPUD[0])
        p.px(x + 1, y, SPUD[1])
        p.px(x - 0.0, y - 1, SPUD[4])
    for (x, y) in ((8, 15), (12, 14), (16, 12)):
        p.px(x, y, SPUD[2])


def g_beetroot(p):
    """A beetroot: a round purple root with a thin tail and red-veined leaves."""
    root = p.E(12, 14.4, 5.8, 5.0)
    tail = p.P([(11.2, 18.6), (12.8, 18.6), (12.2, 22.0)])
    p.round(root, BEET, 10.5, 12.6, 6.0, 0.5, 0.36, 0.3)
    p.cel(tail, BEET, 0.4, 0.1, 0.1, 1, 1)
    p.flat(p.path([(6.6, 14.6), (17.4, 14.6)], 0.8) & root & (np.abs(p.xs - 12) > 2.4), BEET, 0.74)
    for (x0, x1, y1) in ((9.0, 6.6, 3.6), (12.0, 12.0, 2.4), (15.0, 17.6, 3.6)):
        p.flat(p.path([(x0 * 0.3 + 12 * 0.7, 10.0), (x1, y1)], 2.0), LEAF, 0.55)
        p.flat(p.path([(x0 * 0.3 + 12 * 0.7, 10.0), (x1, y1 + 1.4)], 0.9), BEET, 0.8)


def g_apple(p):
    """An apple with a stalk and a leaf."""
    body = p.E(9.6, 13.6, 5.6, 5.8) | p.E(14.4, 13.6, 5.6, 5.8) | p.R(8, 10, 16, 18)
    body &= ~p.D(12, 7.2, 1.8)                                              # the dip at the top
    p.round(body, APPLE, 10.4, 11.4, 8.0, 0.56, 0.36, 0.25)
    p.flat(p.E(9.2, 11.4, 1.5, 2.4, rot=20), APPLE, 0.94)                  # the sheen
    p.flat(p.path([(12, 8.0), (12.6, 4.6)], 1.2), PAL['leather'], 0.5)     # the stalk
    p.flat(p.P([(13.0, 5.4), (17.6, 3.6), (16.6, 7.2)]), LEAF, 0.66)


def g_honey(p):
    """A jar of honey: a wooden lid, a rope of honey running down its side, a label."""
    jar = p.R(6, 9, 18, 21) & ~p.R(6, 9, 7, 10) & ~p.R(17, 9, 18, 10)
    p.flat(jar, HONEY, 0.5)
    p.flat(p.R(7, 10, 9, 20), HONEY, 0.78)                                  # the light down the left
    p.flat(p.R(15, 10, 17, 20), HONEY, 0.26)
    p.flat(p.R(6, 20, 18, 21), HONEY, 0.18)
    p.flat(p.R(5, 6, 19, 10), PAL['wood'], 0.6)
    p.flat(p.R(5, 6, 19, 7), PAL['wood'], 0.84)
    p.flat(p.R(5, 9, 19, 10), PAL['wood'], 0.2)
    p.flat(p.R(8, 13, 16, 18), PAL['parchment'], 0.8)                       # the label with a little comb on it
    for (x, y) in ((11, 14), (13, 14), (10, 16), (12, 16), (14, 16)):
        p.px(x, y, HONEY[2])
        p.px(x + 1, y, HONEY[3])
    p.flat(p.R(16, 8, 17, 12), HONEY, 0.7)                                  # a drip over the lid's lip


def _fish(p, ramp, belly, tail_up=True, slim=1.0, spots=False):
    """A fish seen from the side, head at the left: a body, a forked tail, two fins, an eye and a gill."""
    body = p.E(11.4, 12, 7.8, 4.4 * slim)
    tail = p.P([(17.0, 12), (21.8, 12 - 4.6 * slim), (20.2, 12), (21.8, 12 + 4.6 * slim)])
    fin_t = p.P([(9, 8.4 + (1 - slim) * 2), (12.8, 5.6), (14.4, 8.8)])
    fin_b = p.P([(10, 15.8), (12.8, 18.6), (14, 15.8)])
    p.cel(tail | fin_t | fin_b, ramp, 0.46, 0.14, 0.18, 1, 1)
    t = 0.56 + 0.3 * p.sh(11, 8, 9) - 0.2 * np.clip((p.ys - 12) / 5, 0, 1) * 0
    p.cv.put(body, bands(ramp, t, 0.2))
    p.flat(body & (p.ys > 12.4), belly, 0.8)                                 # the pale belly
    p.flat(body & (np.abs(p.ys - 11.2) < 0.5) & (p.xs > 8), ramp, 0.26)     # the lateral line
    p.flat(p.path([(8.2, 9.6), (7.6, 14.6)], 0.9), ramp, 0.2)                # the gill
    p.px(5.2, 11, (240, 240, 230))
    p.px(5.2, 10, (20, 20, 24))
    p.px(5.2, 11, (24, 22, 26))
    if spots:
        for (x, y) in ((11, 9.6), (13.4, 10.4), (14.8, 9.2), (12.2, 11.6)):
            p.px(x, y, ramp[1])


def g_cod(p):
    _fish(p, COD, [(206, 202, 170), (226, 222, 194), (240, 238, 216)], slim=1.05, spots=True)
    p.flat(p.path([(4.6, 14.4), (3.4, 17.0)], 1.0), COD, 0.5)                # the chin barbel


def g_salmon(p):
    _fish(p, SALMON, [(238, 170, 150), (248, 196, 172), (252, 216, 196)], slim=0.82)
    p.flat(p.E(11.4, 9.6, 6.6, 1.5), SILVER, 0.34)                           # the dark back, blue-grey


def g_beef(p):
    """A T-bone steak: red meat with a white fat edge round it and the bone's T showing."""
    meat = p.E(12, 12.4, 8.8, 6.6, rot=-12) | p.E(15.4, 13.4, 4.6, 5.0)
    p.flat(meat, FAT, 0.8)
    inner = p.E(12.2, 12.6, 7.6, 5.4, rot=-12) | p.E(15.4, 13.4, 3.7, 4.0)
    p.round(inner, BEEF, 10, 9, 8, 0.52, 0.3, 0.3)
    p.flat(p.R(5.5, 11, 18.5, 13), PAL['bone'], 0.78)                         # the T of the bone
    p.flat(p.R(11, 11, 13, 17), PAL['bone'], 0.78)
    p.flat(p.R(5.5, 12, 18.5, 13), PAL['bone'], 0.55)
    p.flat(p.R(12, 11, 13, 17), PAL['bone'], 0.55)
    for (x, y) in ((8, 9), (15, 9), (16, 15), (8, 15)):                     # marbling
        p.flat(p.path([(x, y), (x + 1.6, y + 0.8)], 0.8) & inner, FAT, 0.6)


def g_pork(p):
    """A ham: a pink round with a rind-edged cut and the knuckle's bone."""
    body = p.E(10.6, 12.6, 7.6, 6.4)
    p.flat(p.P([(15.6, 10.4), (20.4, 14.8), (18.8, 17.6), (14.6, 14.6)]), PAL['bone'], 0.52)     # the knuckle
    p.flat(p.E(19.8, 16.2, 2.0, 2.0), PAL['bone'], 0.7)
    p.round(body, HAM, 9, 10, 8, 0.58, 0.34, 0.25)
    p.flat(p.E(10.6, 12.6, 7.6, 6.4) & ~p.E(10.0, 12.0, 6.0, 4.9), FAT, 0.74)   # the fat and the rind
    p.flat(p.E(7.8, 9.4, 1.8, 1.4, rot=-30), HAM, 0.96)
    p.flat(p.path([(9, 14), (14, 11.4)], 0.8), HAM, 0.24)


def g_mutton(p):
    """A lamb chop: a kidney of meat with a pale fat edge and a bone left bare at its upper right, knobbed."""
    p.flat(p.path([(13.4, 12.2), (19.2, 5.4)], 2.0), PAL['bone'], 0.66)
    p.flat(p.path([(13.0, 11.6), (18.8, 5.0)], 0.8), PAL['bone'], 0.94)
    p.flat(p.D(19.8, 4.4, 2.0), PAL['bone'], 0.7)
    chop = p.E(10.4, 15.6, 7.6, 5.8, rot=34) & ~p.D(15.6, 19.2, 2.4)
    p.flat(chop, FAT, 0.78)
    inner = p.E(10.2, 15.4, 6.4, 4.7, rot=34) & ~p.D(15.4, 19.0, 2.4)
    p.round(inner, LAMB, 8, 12, 8, 0.52, 0.3, 0.3)
    p.flat(p.E(8.8, 13.6, 2.4, 1.2, rot=34), LAMB, 0.9)
    p.flat(p.path([(6, 17), (11, 20)], 0.8) & inner, LAMB, 0.2)


def g_chicken(p):
    """A roast drumstick: a golden knob of meat on a bone with a knuckle."""
    meat = p.E(9.6, 10.2, 6.2, 5.2, rot=40)
    p.round(meat, ROAST, 8, 8, 7.6, 0.54, 0.34, 0.25)
    p.flat(p.path([(12.6, 13.4), (18.4, 18.8)], 1.8), PAL['bone'], 0.6)
    p.flat(p.path([(12.4, 13.0), (18.0, 18.0)], 0.7), PAL['bone'], 0.92)
    p.flat(p.D(19.6, 19.4, 1.7) | p.D(17.8, 20.4, 1.6), PAL['bone'], 0.66)
    p.flat(p.E(7.4, 7.8, 1.8, 1.2, rot=40), ROAST, 0.96)
    p.flat(p.path([(9, 13.4), (13, 10.8)], 0.8) & meat, ROAST, 0.22)


FLEECE_W = [(96, 88, 84), (150, 142, 134), (200, 192, 182), (232, 226, 216), (250, 246, 238)]


def g_wool(p):
    """A fleece: a cloud of curled locks, white, the underside in a warm shade."""
    blobs = [(7.6, 12.4, 4.4), (12.0, 9.4, 4.8), (16.4, 12.4, 4.4), (9.4, 16.0, 4.2), (14.8, 16.0, 4.2), (12, 13.4, 5.0)]
    m = np.zeros((p.h, p.w), bool)
    for (x, y, r) in blobs:
        m |= p.D(x, y, r)
    t = 0.8 + 0.2 * p.sh(10, 8, 9) - 0.2 * np.clip((p.ys - 15.0) / 5.0, 0, 1)
    p.cv.put(m, bands(FLEECE_W, t, 0.2))
    for (x, y, r) in blobs[:5]:                                              # the curl in each lock
        ring = (np.abs(np.hypot(p.xs - x, p.ys - y) - r * 0.55) < 0.55) & (p.xs < x + 1.2) & (p.ys < y + 2.2)
        p.flat(ring & m, FLEECE_W, 0.5)
    for (x, y) in ((7, 11), (12, 8), (16, 11), (12, 12)):
        p.px(x, y, FLEECE_W[4])


TWINE_W = [(70, 70, 76), (118, 116, 118), (168, 164, 160), (210, 206, 196), (240, 236, 224)]


TWINE_W = [(70, 66, 62), (118, 112, 104), (168, 160, 146), (210, 202, 184), (240, 232, 212)]


def g_string(p):
    """A spool of twine: two wooden flanges and the thread wound between them in slanting turns, a loose end trailing down."""
    barrel = p.R(7, 7, 17, 17)
    p.cv.put(barrel, bands(TWINE_W, 0.6 + 0.3 * p.sh(10, 10, 9) * 0 + 0.3 * ((17 - p.xs) < 0) * 0 + 0.3 * np.clip((12 - p.xs) / 6, -1, 1) * 0.6, 0.0))
    turn = ((p.xs + p.ys * 1.0).astype(int) % 3) == 0
    p.flat(barrel & turn, TWINE_W, 0.28)
    p.flat(barrel & ~turn, TWINE_W, 0.74)
    p.flat(barrel & (p.xs < 9), TWINE_W, 0.92)
    p.flat(barrel & (p.xs >= 15), TWINE_W, 0.3)
    for y0 in (4.2, 17.0):                                                    # the flanges
        fl = p.R(5, y0, 19, y0 + 2.8)
        p.cel(fl, PAL['wood'], 0.7, 0.24, 0.3, 1, 1)
        p.flat(p.R(5, y0 + 1, 19, y0 + 2), PAL['wood'], 0.58)
    p.flat(p.path([(15.8, 17.2), (17.8, 20.6), (16.6, 22.4), (19.0, 22.8)], 1.0), TWINE_W, 0.8)


def _rot(dx, dy, deg, sx, sy):
    """Coordinates scaled so that an ellipse of semi-axes (r*sx, r*sy) turned [deg] becomes the unit circle."""
    a = math.radians(deg)
    u = dx * math.cos(a) + dy * math.sin(a)
    v = -dx * math.sin(a) + dy * math.cos(a)
    return u / (7.6 * sx), v / (7.6 * sy)


def g_leather(p):
    """A hide: an irregular piece with a leg at each corner, a stitched edge, the flesh side's mottle."""
    hide = p.P([(5, 6), (9, 8), (15, 8), (19, 6), (20, 10), (18, 12), (20, 15), (19, 19), (15, 16.6), (9, 16.6), (5, 19), (4, 15),
                (6, 12), (4, 10)])
    t = 0.66 + 0.16 * (fbm(p.w, p.h, 3, 7) - 0.5) * 2 + 0.22 * p.sh(10, 8, 9)
    p.cv.put(hide, bands(PAL['leather'], t, 0.3))
    stitch = ((dist_in(hide, 3) == 2) & (((p.xs.astype(int) + p.ys.astype(int)) % 2) == 0))
    p.flat(stitch, PAL['leather'], 0.96)
    p.flat(p.E(12, 12, 3.4, 2.4), PAL['leather'], 0.46)


def g_feather(p):
    """A plume: a curved shaft with a barbed vane, white with a grey shadow side."""
    shaft = [(5.0, 20.0), (9.0, 14.0), (13.6, 8.0), (19.0, 3.4)]
    vane_l = p.P([(7.4, 17.6), (7.0, 11.6), (10.6, 7.0), (16.4, 4.0), (19.0, 3.4), (13.6, 8.0)])
    vane_r = p.P([(8.6, 17.4), (13.0, 15.6), (17.6, 11.0), (19.6, 5.6), (19.0, 3.4), (13.6, 8.0)])
    p.cel(vane_l, PLUME, 0.84, 0.1, 0.16, 1, 1)
    p.cel(vane_r, PLUME, 0.66, 0.12, 0.2, 1, 1)
    for k in range(1, 7):                                                     # barbs
        t = k / 7.0
        x = 5.0 + 14.0 * t
        y = 20.0 - 16.6 * t
        p.flat(p.path([(x, y), (x - 3.0 * (1 - t) - 1.2, y + 1.6)], 0.7) & vane_l, PLUME, 0.5)
        p.flat(p.path([(x, y), (x + 3.0 * (1 - t) + 1.2, y + 1.8)], 0.7) & vane_r, PLUME, 0.44)
    p.flat(p.path(shaft, 1.0), PAL['bone'], 0.86)
    p.flat(p.path([(5.0, 20.0), (3.4, 22.0)], 1.0), PAL['bone'], 0.6)


def g_arrow(p):
    """An arrow flying up to the right: a steel head, a wooden shaft, three fletchings in red and white."""
    p.flat(p.path([(5.4, 18.6), (17.0, 7.0)], 1.4), PAL['wood'], 0.7)
    p.flat(p.path([(5.0, 18.2), (16.6, 6.6)], 0.6), PAL['wood'], 0.9)
    head = p.P([(15.0, 5.6), (21.6, 2.4), (18.4, 9.0), (16.6, 8.4)])
    p.cel(head, PAL['steel'], 0.62, 0.22, 0.24, 1, 1)
    for k, col in enumerate(((200, 52, 44), (236, 234, 224), (200, 52, 44))):  # the fletching
        t = k * 2.0
        bx, by = 5.4 + t * 0.9, 18.6 - t * 0.9
        fl = p.P([(bx, by), (bx - 3.6, by - 0.4), (bx - 4.4, by + 3.4), (bx - 0.6, by + 1.6)])
        p.flat(fl, [tuple(int(c * 0.3) for c in col), tuple(int(c * 0.6) for c in col), col, col], 0.7 + 0.2 * (k == 1))
    p.flat(p.D(5.4, 18.6, 1.1), PAL['wood'], 0.4)


def g_flint(p):
    """A knapped flint: a grey-blue stone cut into facets, a pale chipped edge and a spark."""
    stone = p.P([(5, 14), (8, 7), (14, 5), (19, 9), (18, 16), (13, 20), (7, 19)])
    p.cv.put(stone, bands(FLINT, 0.5 + 0.2 * p.sh(10, 8, 9), 0.0))
    facets = [p.P([(8, 7), (14, 5), (13, 10), (9, 12)]), p.P([(14, 5), (19, 9), (15, 12), (13, 10)]),
              p.P([(5, 14), (9, 12), (11, 17), (7, 19)])]
    for k, f in enumerate(facets):
        p.flat(f, FLINT, (0.84, 0.66, 0.3)[k])
    p.flat(p.path([(8, 7), (14, 5), (19, 9)], 0.9), FLINT, 0.97)           # the chipped edge, pale
    for (x, y) in ((10, 14), (14, 15), (12, 12)):
        p.px(x, y, FLINT[4])
    p.px(20.5, 3.5, (255, 240, 170))
    p.px(20.5, 2.5, (255, 214, 120))
    p.px(19.5, 3.5, (255, 214, 120))
    p.px(21.5, 3.5, (255, 214, 120))
    p.px(20.5, 4.5, (255, 214, 120))


def g_candle(p):
    """A tallow candle: a pale stick with a drip down one side, a black wick and a flame."""
    p.flat(p.R(8, 9, 16, 21), TALLOW, 0.6)
    p.flat(p.R(9, 9, 11, 21), TALLOW, 0.9)
    p.flat(p.R(14, 9, 16, 21), TALLOW, 0.36)
    p.flat(p.R(8, 20, 16, 21), TALLOW, 0.2)
    p.flat(p.E(12, 9.2, 4.0, 1.4), TALLOW, 0.96)                             # the melted top
    p.flat(p.P([(8, 9), (10, 9), (10, 14), (9, 15.4), (8, 14)]), TALLOW, 0.98)    # a drip
    p.flat(p.R(11.4, 5.6, 12.6, 9.4), PAL['ink'], 0.6)                       # the wick
    flame = p.E(12, 4.4, 2.2, 3.4) | p.P([(12, 0.2), (14.0, 3.6), (10.0, 3.6)])
    p.flat(flame, FLAME, 0.5)
    p.flat(p.E(12, 5.0, 1.2, 2.2), FLAME, 0.98)


def g_bottle(p):
    """A green glass bottle with a cork and the sheen of the glass down one side."""
    body = p.R(7, 11, 17, 21) | p.P([(7, 11), (10, 7.8), (14, 7.8), (17, 11)]) | p.R(10, 4, 14, 8)
    p.flat(body, GLASS_G, 0.5)
    p.flat(p.R(8, 12, 10, 20), GLASS_G, 0.78)
    p.flat(p.R(15, 12, 17, 20), GLASS_G, 0.24)
    p.flat(p.R(7, 20, 17, 21), GLASS_G, 0.16)
    p.flat(p.R(10, 4, 14, 5), GLASS_G, 0.7)
    p.flat(p.R(10.5, 8, 11.5, 11), GLASS_G, 0.94)
    p.flat(p.R(9.6, 13, 14.4, 18), PAL['parchment'], 0.76)                   # a label
    p.flat(p.R(10.6, 14.4, 13.6, 15.2), PAL['ink'], 0.8)
    p.flat(p.R(10, 1.6, 14, 5), PAL['cork'], 0.62)                           # the cork
    p.flat(p.R(10, 1.6, 12, 2.6), PAL['cork'], 0.9)


def g_iron(p):
    """An iron ingot: a long bar with sloping ends and a smith's mark struck into its top."""
    top = p.P([(8, 8.4), (18, 8.4), (21, 12.6), (5, 12.6)])
    front = p.P([(5, 12.6), (21, 12.6), (19, 18), (7, 18)])
    p.flat(top, PAL['steel'], 0.9)
    p.flat(front, PAL['steel'], 0.6)
    p.flat(p.R(7, 17, 19, 18), PAL['steel'], 0.2)
    p.flat(p.P([(5, 12.6), (7, 12.6), (7.6, 18), (7, 18)]), PAL['steel'], 0.78)
    p.flat(p.path([(8, 12.8), (7.4, 17.6)], 1.0), PAL['steel'], 0.94)
    p.flat(p.R(14, 9.4, 17, 10.4), PAL['steel'], 0.4)                          # the mark
    p.flat(p.R(15, 9.4, 16, 11.8), PAL['steel'], 0.4)
    p.flat(p.R(11, 10.2, 12, 11.2), PAL['steel'], 0.36)


def g_coal(p):
    """Lumps of coal: black, angular, with a blue shine on the broken faces."""
    a = p.P([(4, 16), (6, 10), (11, 9), (14, 13), (13, 19), (7, 20)])
    b = p.P([(11, 12), (14, 6), (19, 7), (21, 13), (18, 19), (13, 19)])
    p.cv.put(a, bands(COAL, 0.44 + 0.2 * p.sh(8, 12, 7), 0.0))
    p.cv.put(b, bands(COAL, 0.4 + 0.2 * p.sh(15, 10, 7), 0.0))
    for f, lvl in ((p.P([(6, 10), (11, 9), (9, 14)]), 0.8), (p.P([(14, 6), (19, 7), (16, 11)]), 0.84), (p.P([(4, 16), (6, 10), (8, 15)]), 0.3)):
        p.flat(f, COAL, lvl)
    p.px(7, 11, COAL[4])
    p.px(15, 8, COAL[4])
    p.px(17, 8, COAL[3])
    p.px(10, 10, COAL[3])


PICTOGRAMS = {
    'bread': g_bread, 'wheat': g_wheat, 'carrot': g_carrot, 'potato': g_potato, 'beetroot': g_beetroot, 'apple': g_apple,
    'honey': g_honey, 'cod': g_cod, 'salmon': g_salmon, 'beef': g_beef, 'pork': g_pork, 'mutton': g_mutton, 'chicken': g_chicken,
    'wool': g_wool, 'string': g_string, 'leather': g_leather, 'feather': g_feather, 'arrow': g_arrow, 'flint': g_flint,
    'candle': g_candle, 'bottle': g_bottle, 'iron': g_iron, 'coal': g_coal,
}


def good_icon(name):
    """The good's picture alone, 24 x 24, outlined, on nothing."""
    p = Pic(24)
    PICTOGRAMS[name](p)
    return p.finish()


# ================================================================ the chips: a pictogram on a pad in a ring that says short (red, dashed) or glut (green, solid)

RING = {
    'red': GEMS['red'],
    'green': GEMS['green'],
}
PAD = [(86, 70, 52), (126, 106, 78), (170, 148, 108), (204, 184, 142), (226, 212, 172)]


CHIP = 32                    # a chip's and a badge's texels; the screens draw them at 16 px


def token(kind):
    """The chip's token alone, CHIP x CHIP: a pale pad in a ring, red and dashed for a short, green and solid for a glut."""
    p = Pic(CHIP)
    c = CHIP / 2.0
    d = np.hypot(p.xs - c, p.ys - c)
    ang = np.arctan2(p.ys - c, p.xs - c)
    outer = d <= c - 0.1
    ring = (d > 12.6) & (d <= 14.8)
    pad = d <= 12.4
    p.flat(outer, PAL['ink'], 0.0)                                            # the dark backing: shows in a dashed ring's gaps
    seg = np.floor((ang + math.pi) / (2 * math.pi) * 14.0)
    frac = (ang + math.pi) / (2 * math.pi) * 14.0 - seg
    drawn = ring & (frac < 0.7) if kind == 'red' else ring
    t = 0.64 + 0.26 * p.sh(c, c, 14.0)
    p.cv.put(drawn, bands(RING[kind], t, 0.0))
    p.flat(pad, PAL['ink'], 0.0)
    inner = d <= 11.6
    p.cv.put(inner, bands(PAD, 0.66 + 0.3 * p.sh(c, c, 11.6), 0.15))
    p.flat(inner & (d > 10.4) & (p.sh(c, c, 11.6) < 0.1), PAD, 0.18)
    p.flat(inner & (d > 10.4) & (p.sh(c, c, 11.6) >= 0.1), PAD, 0.46)
    return p


def chip(name, kind):
    """A chip, CHIP x CHIP: the good on a pale pad in a ring that says short (red, dashed) or glut (green, solid). The ring is
    the colour; the dashes are a second sign for anyone who cannot tell red from green."""
    p = token(kind)
    out = p.cv.image()
    icon = Image.new('RGBA', (CHIP, CHIP), (0, 0, 0, 0))
    icon.alpha_composite(good_icon(name), ((CHIP - 24) // 2, (CHIP - 24) // 2))
    out.alpha_composite(icon)
    return out
# ================================================================ the events: a badge for each, news of what is behind a short or a glut

BLIGHT = [(30, 24, 18), (74, 58, 34), (122, 98, 54), (168, 140, 76), (204, 178, 104)]
MOULD = [(18, 30, 20), (36, 58, 34), (64, 92, 48), (104, 132, 70)]
HEN = [(30, 32, 28), (64, 70, 56), (104, 112, 82), (148, 154, 112), (190, 194, 150)]
WATER = [(14, 30, 52), (28, 60, 98), (52, 100, 148), (96, 150, 190), (170, 206, 226)]
SPARK = (255, 244, 200)
INK_PX = (24, 20, 22)


def spark(p, x, y, r=1):
    """A four-point sparkle of light, [r] long in each direction: the sign of good news."""
    p.px(x, y, SPARK)
    for i in range(1, r + 1):
        for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
            p.px(x + dx, y + dy, tone(SPARK, -0.12 * i))


def e_blight(p):
    """A blighted crop: a dry stalk bent over with its ear hanging black, a limp grey-green leaf, spots of mould."""
    p.flat(p.path([(8, 22), (8.6, 15), (10.4, 9.6), (14.0, 6.2), (16.8, 7.2)], 1.7), BLIGHT, 0.66)
    p.flat(p.path([(8, 22), (8.5, 15.5), (10.2, 10.0)], 0.6), BLIGHT, 0.94)                  # the light on the stalk
    p.flat(p.P([(8.6, 17), (3.4, 18.4), (3.0, 22.2), (7.8, 20.6)]), MOULD, 0.6)               # the limp leaf
    p.flat(p.path([(8.4, 17.4), (4.0, 20.8)], 0.7), MOULD, 0.9)
    cx, cy = 16.9, 13.4
    body = p.E(cx, cy, 2.8, 5.2)
    p.cv.put(body, bands(BLIGHT, 0.1 + 0.22 * p.sh(cx, cy, 5.2), 0.0))
    for k in range(-2, 3):                                                                    # the grains' chevrons, pointing down
        vk = cy + k * 2.0
        chev = body & (np.abs(np.abs(p.xs - cx) * 0.85 - (vk - p.ys)) < 0.5) & (p.ys < vk + 0.2) & (np.abs(p.xs - cx) > 0.3)
        p.flat(chev, BLIGHT, 0.34)
    for (x, y) in ((16.2, 10.6), (18.0, 12.6), (15.8, 14.8), (17.6, 16.2), (16.6, 18.0)):     # mould blooming on it
        p.px(x, y, MOULD[3])
        p.px(x + 1, y, MOULD[2])
    p.flat(p.path([(16.8, 18.6), (17.0, 21.6)], 0.8), BLIGHT, 0.3)
    for (x, y) in ((12.6, 18.4), (20.4, 9.2), (13.4, 3.4), (5.6, 14.2), (20.8, 19.8)):        # spores drifting
        p.px(x, y, MOULD[2])


def e_bumper(p):
    """A bumper crop: the sheaf of the wheat, with sparkles round it."""
    g_wheat(p)
    spark(p, 3, 5, 2)
    spark(p, 20, 3, 2)
    spark(p, 21, 15, 1)
    spark(p, 3, 15, 1)


def e_murrain(p):
    """Sickness among the cattle: a horned skull."""
    for sgn in (-1, 1):
        horn = [(12 + sgn * 5.2, 9.4), (12 + sgn * 8.4, 8.2), (12 + sgn * 9.8, 5.0), (12 + sgn * 9.4, 2.6)]
        p.flat(p.path(horn, 2.0), PAL['bone'], 0.6)
        p.flat(p.path([(x - 0.4 * sgn, y + 0.3) for x, y in horn], 0.8), PAL['bone'], 0.88)
    skull = p.P([(6.4, 8.4), (17.6, 8.4), (18.6, 12.4), (15.4, 15.6), (14.8, 21.4), (9.2, 21.4), (8.6, 15.6), (5.4, 12.4)])
    p.cv.put(skull, bands(PAL['bone'], 0.62 + 0.3 * p.sh(10, 10, 8), 0.0))
    p.flat(skull & (p.xs > 15.6), PAL['bone'], 0.3)
    p.flat(p.E(9.2, 12.8, 1.9, 2.2), PAL['bone'], 0.0)
    p.flat(p.E(14.8, 12.8, 1.9, 2.2), PAL['bone'], 0.0)
    p.flat(p.E(10.6, 19.0, 0.8, 1.2) | p.E(13.4, 19.0, 0.8, 1.2), PAL['bone'], 0.0)
    p.flat(p.path([(12, 8.6), (11.4, 11.4), (12.6, 13.4)], 0.7), PAL['bone'], 0.28)


def e_fowl_pest(p):
    """Sickness in the coops: a hen, grey-green, her eye crossed out."""
    tail = p.P([(16.4, 12), (21.4, 6.6), (21.8, 11.4), (20.6, 15.6), (17.4, 16)])
    p.cel(tail, HEN, 0.4, 0.14, 0.18, 1, 1)
    p.flat(p.path([(17.4, 12.6), (20.8, 8.6)], 0.7), HEN, 0.62)
    body = p.E(11.6, 14.8, 6.4, 5.0, rot=-8)
    p.round(body, HEN, 9, 11, 8, 0.55, 0.3, 0.2)
    p.flat(p.E(12.6, 15.0, 3.6, 2.4, rot=-8), HEN, 0.38)                       # the wing
    p.flat(p.E(12.4, 14.4, 2.4, 1.2, rot=-8), HEN, 0.52)
    p.flat(p.D(7.2, 8.8, 2.8), HEN, 0.64)                                        # the head
    p.flat(p.D(6.6, 8.2, 1.2), HEN, 0.86)
    for (x, y) in ((5.4, 5.6), (7.0, 4.8), (8.8, 5.6)):                          # the comb
        p.flat(p.D(x, y, 1.15), SILK, 0.7)
    p.flat(p.P([(4.6, 8.4), (1.8, 9.6), (4.6, 10.6)]), STRAW, 0.7)               # the beak
    p.flat(p.D(5.6, 11.8, 1.1), SILK, 0.6)                                       # the wattle
    for (x, y) in ((6, 8), (7, 9), (7, 8), (6, 9)):                              # the eye, crossed out
        p.px(x, y, INK_PX)
    for (x, y) in ((10, 12), (14, 16), (12, 17.4), (9, 15)):                     # the mottle
        p.px(x, y, HEN[1])
    for lx in (10.4, 13.6):                                                      # legs
        p.flat(p.path([(lx, 19), (lx - 0.4, 22.0)], 0.9), STRAW, 0.6)
        p.flat(p.path([(lx - 1.6, 22.6), (lx + 0.6, 22.4)], 0.9), STRAW, 0.6)


def e_mill_broken(p):
    """A mill with a sail snapped off: the tower, its cap and three sails, the fourth a stump with its splinters falling."""
    p.cel(p.P([(8.0, 22.4), (16.0, 22.4), (14.2, 11.4), (9.8, 11.4)]), PAL['stone'], 0.78, 0.14, 0.26, 1, 1)
    p.flat(p.R(11, 18, 13, 22), PAL['stone'], 0.04)                                           # the door
    p.flat(p.R(11, 14, 13, 16), PAL['stone'], 0.1)                                            # a window
    for y in (13, 17):                                                                        # courses of stone
        p.flat(p.path([(8.6 + (y - 13) * -0.2, y), (15.4 + (y - 13) * 0.2, y)], 0.5) & p.P([(8.0, 22.4), (16.0, 22.4), (14.2, 11.4), (9.8, 11.4)]), PAL['stone'], 0.56)
    p.flat(p.P([(8.8, 11.8), (15.2, 11.8), (12, 7.6)]), PAL['wood'], 0.6)
    hx, hy = 12.0, 9.2
    for deg in (38, 128, 218, 308):
        a = math.radians(deg)
        ux, uy = math.sin(a), -math.cos(a)
        ln = 9.4 if deg != 128 else 3.2                                         # the broken one is a stump
        p.flat(p.path([(hx, hy), (hx + ux * ln, hy + uy * ln)], 1.6), PAL['wood'], 0.82)
        if deg != 128:
            for j in range(1, 5):                                                # the lattice's bars
                bx, by = hx + ux * j * 2.0, hy + uy * j * 2.0
                p.flat(p.path([(bx - uy * 1.9, by + ux * 1.9), (bx + uy * 1.9, by - ux * 1.9)], 0.9), PAL['wood'], 0.5)
    p.flat(p.path([(hx + 3.4, hy + 3.0), (hx + 5.0, hy + 4.6)], 1.5), PAL['wood'], 0.6)    # the splinters, falling
    p.flat(p.path([(20.0, 16.0), (22.0, 19.0)], 1.5), PAL['wood'], 0.7)
    p.flat(p.path([(19.0, 12.8), (20.4, 14.6)], 1.1), PAL['wood'], 0.8)
    p.flat(p.D(hx, hy, 1.5), PAL['brass'], 0.7)


def e_oven_cracked(p):
    """The bakehouse oven with a crack through its dome, the fire showing through it: a brick dome, its arched mouth aglow, a chimney."""
    brick = [(52, 22, 18), (104, 44, 32), (156, 78, 52), (196, 112, 76), (226, 156, 112)]
    p.cel(p.R(15.4, 3.6, 19, 11.4), brick, 0.5, 0.2, 0.24, 1, 1)
    dome = (p.E(12, 16.4, 8.8, 7.6) & (p.ys <= 16.4)) | p.R(3.2, 16.4, 20.8, 21.8)
    p.round(dome, brick, 8, 11, 10, 0.58, 0.3, 0.0)
    joints = ((p.ys.astype(int) % 3) == 0) | (((p.xs.astype(int) + 2 * ((p.ys.astype(int) // 3) % 2)) % 4) == 0)
    p.flat(dome & joints & ~p.R(8.2, 14.4, 15.8, 22), brick, 0.12)                           # the courses and joints
    p.flat(p.R(15.4, 4.6, 19, 5.6) | p.R(15.4, 8.6, 19, 9.6), brick, 0.2)
    mouth = p.R(8.6, 16.6, 15.4, 21.8) | p.E(12, 16.6, 3.4, 3.2)
    p.flat(mouth, brick, 0.0)
    p.flat(mouth & (p.ys > 17.4), PAL['ember'], 0.46)
    p.flat(mouth & (p.ys > 18.8) & (np.abs(p.xs - 12) < 2.6), PAL['ember'], 0.74)
    p.flat(mouth & (p.ys > 20.0) & (np.abs(p.xs - 12) < 1.6), PAL['ember'], 0.96)
    crack = [(11.2, 8.6), (9.6, 11.2), (11.6, 13.2), (10.2, 15.6)]
    p.flat(p.path(crack, 1.6), brick, 0.0)
    p.flat(p.path(crack, 0.7), PAL['ember'], 0.72)                                                 # the fire shining through
    p.flat(p.D(11.0, 8.4, 1.0), PAL['ember'], 0.9)


def e_good_catch(p):
    """The shoals are in: a big silver fish with sparkles round it, a wave under."""
    _fish(p, SILVER, [(206, 214, 222), (226, 232, 238), (244, 248, 250)], slim=1.0)
    p.flat(p.path([(x, 21.2 + 1.0 * math.sin(x * 0.9)) for x in np.arange(2.0, 22.1, 1.0)], 1.5), WATER, 0.62)
    spark(p, 20, 4, 2)
    spark(p, 3, 19, 1)
    spark(p, 21, 18, 1)


def e_poor_catch(p):
    """The nets come up empty: only the bones of a fish, over a wave."""
    bone = PAL['bone']
    p.flat(p.path([(7.0, 12.2), (18.4, 12.2)], 1.3), bone, 0.86)                              # the spine
    for k in range(5):                                                                        # the ribs, sloping back
        x = 9.2 + 1.9 * k
        p.flat(p.path([(x, 12.2), (x + 1.6, 8.0)], 1.0), bone, 0.84)
        p.flat(p.path([(x, 12.2), (x + 1.6, 16.4)], 1.0), bone, 0.76)
    p.flat(p.path([(18.4, 12.2), (21.8, 8.2)], 1.1) | p.path([(18.4, 12.2), (21.8, 16.2)], 1.1), bone, 0.82)     # the tail's fork
    p.flat(p.path([(21.8, 8.2), (21.8, 16.2)], 0.8), bone, 0.7)
    skull = p.E(5.2, 12.2, 3.8, 3.2)
    p.cv.put(skull, bands(bone, 0.66 + 0.3 * p.sh(5.2, 12.2, 3.8), 0.0))
    p.flat(p.P([(2.4, 13.4), (6.8, 13.4), (5.0, 15.8), (2.0, 15.0)]), bone, 0.5)               # the jaw
    p.flat(p.D(4.4, 11.0, 1.1), PAL['bone'], 0.0)                                              # the eye socket
    p.flat(p.path([(2.0, 18.6), (6.0, 19.8), (10.0, 18.6), (14.0, 19.8), (18.0, 18.6), (22.0, 19.8)], 1.5), WATER, 0.6)    # the wave
    p.flat(p.path([(2.0, 18.2), (6.0, 19.4), (10.0, 18.2), (14.0, 19.4), (18.0, 18.2), (22.0, 19.4)], 0.6), WATER, 0.9)


def e_fever(p):
    """A fever going round: a thermometer with its mercury high, drops of sweat and waves of heat."""
    tube = p.R(10.4, 3.6, 13.6, 16.6) | p.E(12, 3.8, 1.6, 1.4)
    p.flat(tube, PAL['glass'], 0.72)
    p.flat(p.R(10.4, 4.2, 11.6, 16), PAL['glass'], 0.96)
    p.flat(p.R(12.8, 4.2, 13.6, 16), PAL['glass'], 0.4)
    p.cv.put(p.D(12, 18.6, 3.5), bands(GEMS['red'], 0.58 + 0.3 * p.sh(12, 18.6, 3.5), 0.0))
    p.flat(p.R(11.2, 6.6, 12.8, 17.2), GEMS['red'], 0.66)
    p.flat(p.R(11.2, 6.6, 11.8, 17.2), GEMS['red'], 0.92)
    for k, y in enumerate((5.4, 7.4, 9.4, 11.4, 13.4)):                          # the scale
        p.flat(p.R(14.2, y, 14.2 + (2.4 if k % 2 == 0 else 1.4), y + 0.8), PAL['ink'], 0.7)
    for (x, y) in ((6.2, 9.4), (4.6, 15.0), (18.6, 15.4)):                       # sweat
        p.flat(p.D(x, y + 0.8, 1.3) | p.P([(x, y - 1.8), (x + 1.2, y + 0.4), (x - 1.2, y + 0.4)]), WATER, 0.66)
        p.px(x - 0.5, y + 0.6, (220, 240, 250))
    for x0 in (17.6, 19.6):                                                      # waves of heat
        p.flat(p.path([(x0, 3.6), (x0 + 1.4, 5.6), (x0, 7.6), (x0 + 1.4, 9.6)], 0.9), GEMS['amber'], 0.62)


def e_waylaid(p):
    """A wagon waylaid: a wheel with an arrow standing in its rim, the fletching still shaking."""
    cx, cy = 10.2, 13.6
    d = np.hypot(p.xs - cx, p.ys - cy)
    p.cv.put((d > 7.0) & (d <= 8.8), bands(PAL['steel'], 0.62 + 0.3 * p.sh(cx, cy, 8.8), 0.0))
    p.cv.put((d > 5.0) & (d <= 7.0), bands(PAL['wood'], 0.8 + 0.2 * p.sh(cx, cy, 7.0), 0.0))
    inside = d <= 5.0
    p.cv.a[inside] = 0
    for k in range(6):
        a = math.radians(k * 60 + 12)
        p.flat(p.path([(cx, cy), (cx + math.cos(a) * 5.4, cy + math.sin(a) * 5.4)], 1.6), PAL['wood'], 0.84 - 0.2 * math.sin(a + 0.8))
    p.cv.put(d <= 2.3, bands(PAL['brass'], 0.66 + 0.3 * p.sh(cx, cy, 2.3), 0.0))
    p.px(cx - 1, cy - 1, PAL['brass'][5])
    sx, sy = 21.4, 2.6                                                                         # the arrow, in from the upper right
    p.flat(p.path([(sx, sy), (cx + 5.4, cy - 5.2)], 1.7), PAL['wood'], 0.9)
    p.flat(p.path([(sx - 0.2, sy - 0.2), (cx + 5.6, cy - 5.4)], 0.6), PAL['wood'], 1.0)
    p.cel(p.P([(cx + 6.6, cy - 6.6), (cx + 3.4, cy - 4.6), (cx + 4.8, cy - 3.2)]), PAL['steel'], 0.7, 0.2, 0.24, 1, 1)    # the head, in the rim
    red = [(60, 16, 12), (120, 30, 26), (200, 52, 44), (226, 84, 70)]
    white = [(90, 88, 84), (150, 148, 142), (236, 232, 218), (252, 250, 240)]
    for (fx, fy, ramp) in ((sx - 0.2, sy + 0.4, red), (sx - 1.8, sy + 1.8, white)):
        p.flat(p.P([(fx, fy), (fx - 2.8, fy - 0.4), (fx - 3.6, fy + 3.0), (fx - 0.4, fy + 2.0)]), ramp, 0.72)


EVENT_PICTOGRAMS = {
    'blight': e_blight, 'bumper': e_bumper, 'murrain': e_murrain, 'fowl_pest': e_fowl_pest, 'mill_broken': e_mill_broken,
    'oven_cracked': e_oven_cracked, 'good_catch': e_good_catch, 'poor_catch': e_poor_catch, 'fever': e_fever, 'waylaid': e_waylaid,
}


def event_icon(name):
    """The event's picture alone, 24 x 24, outlined, on nothing."""
    p = Pic(24)
    EVENT_PICTOGRAMS[name](p)
    return p.finish()


SHIELD = [(3, 1.5), (29, 1.5), (30.6, 3.2), (30.6, 16), (27.8, 23.4), (16, 30.6), (4.2, 23.4), (1.4, 16), (1.4, 3.2)]


def badge(name):
    """An event's badge, CHIP x CHIP: its picture on the same pale pad as a chip's, in a shield (not a ring, so no one takes it for
    a good) with a gilt rim and a band inside it, indigo for bad news and green for good."""
    p = Pic(CHIP)
    shape = p.P(SHIELD)
    d = dist_in(shape, 6)
    band = GEMS['green'] if name in GOOD_EVENTS else INDIGO
    p.cv.put(shape & (d <= 2), bands(PAL['gold'], 0.62 + 0.3 * p.sh(14, 12, 16), 0.0))
    p.cv.put(shape & (d >= 3) & (d <= 4), bands(band, 0.5 + 0.3 * p.sh(14, 12, 16), 0.1))
    inner = shape & (d >= 5)
    p.cv.put(inner, bands(PAD, 0.66 + 0.3 * p.sh(14, 12, 14), 0.15))
    p.flat(inner & (d == 5) & (p.sh(14, 12, 14) < 0.2), PAD, 0.2)
    out = p.finish(0.8, 0.2)
    out.alpha_composite(event_icon(name), (4, 2))
    return out


# ================================================================ the road pennants: green flies on an open road, black on a closed one

CLOTH_W, CLOTH_H = 20, 14
POLE_W, POLE_H = 4, 32
PENNANT_RAMPS = {'open': GEMS['green'], 'troubled': GEMS['amber'], 'perilous': GEMS['red'], 'closed': PAL['blacksteel']}
MARK = (236, 228, 200)


def pennant_cloth(kind):
    """The cloth of a swallow-tailed pennant, CLOTH_W x CLOTH_H, its hoist (the edge the pole holds) at the left. The mark across it
    says the road as well as the colour does: nothing for open, one band for troubled, two for perilous, a cross for closed. It is
    painted flat, with its folds running up and down, so a renderer can cut it into columns and let each ride on a wave."""
    p = Pic(CLOTH_W, CLOTH_H)
    ramp = PENNANT_RAMPS[kind]
    flag = p.P([(0, 0.4), (CLOTH_W, 2.0), (CLOTH_W - 5.4, CLOTH_H / 2.0), (CLOTH_W, CLOTH_H - 2.0), (0, CLOTH_H - 0.4)])
    fold = 0.5 + 0.16 * np.sin(p.xs * 0.62 + 0.5)
    p.cv.put(flag, bands(ramp, fold + 0.1 * (1 - p.ys / CLOTH_H), 0.2))
    p.flat(flag & (p.ys < 1.8 + p.xs * 0.07), ramp, 0.84)                       # the lit top hem
    p.flat(flag & (p.ys > CLOTH_H - 1.8 - p.xs * 0.07), ramp, 0.24)             # the shaded lower hem
    p.flat(flag & (p.xs < 1.6), ramp, 0.34)                                      # the hoist's seam
    mid = CLOTH_H / 2.0
    if kind == 'troubled':
        p.cv.put(flag & (np.abs(p.ys - mid) < 1.1) & (p.xs > 4) & (p.xs < 14), np.asarray(MARK, float))
    elif kind == 'perilous':
        for y in (mid - 2.6, mid + 2.6):
            p.cv.put(flag & (np.abs(p.ys - y) < 1.0) & (p.xs > 4) & (p.xs < 13), np.asarray(MARK, float))
    elif kind == 'closed':
        p.cv.put(flag & (p.path([(6, mid - 3.2), (12, mid + 3.2)], 1.8) | p.path([(6, mid + 3.2), (12, mid - 3.2)], 1.8)), np.asarray(MARK, float))
    return p.finish(0.8, 0.2)


def pennant_pole():
    """The pole the cloth flies from, POLE_W x POLE_H: wood with a brass ball on top and an iron shoe at the foot. The cloth's
    hoist meets it from y = 3."""
    p = Pic(POLE_W, POLE_H)
    p.cel(p.R(1, 3, 3, POLE_H - 3), PAL['wood'], 0.64, 0.26, 0.28, 1, 1)
    p.cv.put(p.D(2, 2.0, 1.8), bands(PAL['brass'], 0.66 + 0.3 * p.sh(2, 2, 1.8), 0.0))
    p.px(1, 1, PAL['brass'][5])
    p.flat(p.R(1, POLE_H - 4, 3, POLE_H - 1), PAL['iron'], 0.6)
    return p.finish(0.8, 0.2)


# ================================================================ the hourglass: eight sand levels, and its parts for a sand that runs smoothly

HG_W, HG_H = 16, 28
HG_CX = HG_W / 2.0
HG_NECK = HG_H // 2                      # the row the neck is centred on (its top row of the lower bulb)
HG_LEVELS = 8
HG_STREAM_FRAMES = 4
HG_STREAM_W, HG_STREAM_H = 2, 12         # a stream's frame: 2 wide, 12 tall, drawn at x = 7 from the neck down
SAND = [(70, 44, 18), (130, 88, 34), (190, 140, 58), (226, 184, 92), (250, 224, 150)]


def hg_glass(p):
    """The glass's outer mask: two round bulbs and a neck between the plates, and its inside (a texel in)."""
    glass = (p.E(HG_CX, 9.4, 5.0, 5.6) | p.E(HG_CX, 18.6, 5.0, 5.6) | p.R(6, 12, 10, 16)) & (p.ys >= 4) & (p.ys < 24)
    inner = glass & (dist_in(glass, 3) >= 2)
    return glass, inner


def hg_frame():
    """The empty glass in its frame: HG_W x HG_H. The glass's inside is left clear so that the sand shows through it."""
    p = Pic(HG_W, HG_H)
    glass, inner = hg_glass(p)
    wall = glass & ~inner
    p.cv.put(wall, bands(PAL['glass'], 0.5 + 0.34 * p.sh(HG_CX, 14, 9), 0.0))
    p.flat(wall & (p.xs < HG_CX - 2.0) & (p.ys > 5) & (p.ys < 12), PAL['glass'], 0.98)           # the sheen on the left
    p.flat(inner, PAL['glass'], 0.1)                                                           # placeholder, cleared below
    for y0 in (1, 24):
        plate = p.R(1, y0, HG_W - 1, y0 + 3)
        p.cel(plate, PAL['darkwood'], 0.6, 0.3, 0.3, 1, 1)
        p.flat(p.R(2, y0 + 1, HG_W - 2, y0 + 2), PAL['brass'], 0.7 if y0 == 1 else 0.5)
    for x0 in (1, HG_W - 2):
        p.cel(p.R(x0, 4, x0 + 1, 24), PAL['wood'], 0.58, 0.24, 0.26, 1, 1)
    img = p.finish(0.8, 0.2)
    arr = np.asarray(img).copy()
    arr[inner] = 0
    return Image.fromarray(arr, 'RGBA')


def hg_sand_part(top):
    """All the sand of one bulb, HG_W x HG_H, laid in the glass's inside: the upper bulb's [top] or the lower's. A renderer shows
    as much of it as it likes by cutting its rows."""
    p = Pic(HG_W, HG_H)
    _, inner = hg_glass(p)
    zone = inner & ((p.ys < HG_NECK) if top else (p.ys >= HG_NECK))
    t = 0.62 + 0.26 * np.clip(-(p.xs - HG_CX + 0.4 * (p.ys - 14)) / 5.0, -1, 1) - 0.1 * (p.ys > 20) + 0.0
    p.cv.put(zone, bands(SAND, t, 0.25))
    grains = zone & (rand(HG_W, HG_H, 31 if top else 37) > 0.86)
    p.flat(grains, SAND, 0.92)
    return p.cv.image()


def hg_stream(frame):
    """The falling sand, HG_STREAM texels: a thread two wide with the grains running down it, [frame] of HG_STREAM_FRAMES."""
    w, h = HG_STREAM_W, HG_STREAM_H
    p = Pic(w, h)
    for y in range(h):
        phase = (y + frame) % 4
        p.cv.put(p.R(0, y, w, y + 1), np.asarray(SAND[4] if phase < 2 else SAND[3], float))
    return p.cv.image()


def hg_rows(inner_zone):
    """The pixel count of each row of a zone, as a list over the rows."""
    return [int(inner_zone[y].sum()) for y in range(inner_zone.shape[0])]


def hourglass(level):
    """The hourglass at [level] of HG_LEVELS (0 new, the sand all above; 7 spent, all below), whole: the frame with the sand
    in it, the surface of each heap shaped, and the thread of sand between while any runs."""
    base = Image.new('RGBA', (HG_W, HG_H), (0, 0, 0, 0))
    p = Pic(HG_W, HG_H)
    _, inner = hg_glass(p)
    top_zone = inner & (p.ys < HG_NECK)
    bot_zone = inner & (p.ys >= HG_NECK)
    rows_t = hg_rows(top_zone)
    rows_b = hg_rows(bot_zone)
    area_t = sum(rows_t)
    frac = level / float(HG_LEVELS - 1)
    left_t = int(round(area_t * 0.94 * (1 - frac)))                      # the sand left above (a little stays on the glass)
    left_b = int(round(area_t * 0.94 * frac))
    keep_t = np.zeros(inner.shape, bool)
    got = 0
    for y in range(HG_NECK - 1, -1, -1):                                  # from the neck upward
        if got >= left_t:
            break
        keep_t[y] = top_zone[y]
        got += rows_t[y]
    surface_t = min((y for y in range(HG_NECK) if keep_t[y].any()), default=HG_NECK)
    if 0 < level < HG_LEVELS - 1:                                         # the surface dishes toward the middle as it drains
        dish = (np.abs(p.xs - HG_CX) < 1.6) & (p.ys == surface_t)
        keep_t &= ~dish
    keep_b = np.zeros(inner.shape, bool)
    got = 0
    for y in range(HG_H - 1, HG_NECK - 1, -1):                            # from the bottom plate upward
        if got >= left_b:
            break
        keep_b[y] = bot_zone[y]
        got += rows_b[y]
    surface_b = min((y for y in range(HG_NECK, HG_H) if keep_b[y].any()), default=HG_H)
    if 0 < level < HG_LEVELS - 1:                                         # a little cone builds under the thread
        keep_b |= (np.abs(p.xs - HG_CX) < 1.6) & (p.ys == surface_b - 1) & bot_zone
        surface_b -= 1
    top_img = Image.alpha_composite(base, hg_sand_part(True))
    bot_img = Image.alpha_composite(base, hg_sand_part(False))
    ta = np.asarray(top_img).copy()
    ba = np.asarray(bot_img).copy()
    ta[~keep_t] = 0
    ba[~keep_b] = 0
    out = Image.alpha_composite(Image.alpha_composite(hg_frame(), Image.fromarray(ta, 'RGBA')), Image.fromarray(ba, 'RGBA'))
    if 0 < level < HG_LEVELS - 1:
        y0 = max(HG_NECK - 3, surface_t)
        stream = hg_stream(0).crop((0, 0, 2, max(1, surface_b - y0)))
        out.alpha_composite(stream, (HG_W // 2 - 1, y0))
    return out


# ================================================================ the booker's seal: wax in one of six colours, a mark pressed in each

SEAL = 24
SEAL_COLOURS = [
    ('red', PAL['wax'], 'wheel'),
    ('blue', GEMS['blue'], 'sheaf'),
    ('green', GEMS['green'], 'bell'),
    ('amber', GEMS['amber'], 'key'),
    ('violet', GEMS['violet'], 'scales'),
    ('teal', [(8, 30, 34), (16, 62, 68), (28, 104, 108), (60, 152, 148), (150, 214, 206)], 'star'),
]
WAX_GREY = [(40, 40, 44), (80, 80, 86), (128, 128, 134), (176, 176, 180), (226, 226, 226)]


def seal_mark(p, kind, c):
    """The mark of a seal as a mask: a wheel, a sheaf, a bell, a key, a pair of scales or a star, about 11 texels across."""
    d = np.hypot(p.xs - c, p.ys - c)
    if kind == 'wheel':
        m = (np.abs(d - 5.2) < 0.85) | (d < 1.6)
        for k in range(6):
            a = math.radians(k * 60 + 15)
            m |= p.path([(c, c), (c + math.cos(a) * 5.0, c + math.sin(a) * 5.0)], 1.1)
        return m
    if kind == 'sheaf':
        bundle = p.P([(c - 1.4, c + 6.4), (c + 1.4, c + 6.4), (c + 4.6, c - 1.2), (c - 4.6, c - 1.2)])
        m = bundle & ~p.R(c - 6, c + 2.0, c + 6, c + 3.0)                                 # the band is a groove across it
        for sx, deg in ((-4.2, -24), (0.0, 0), (4.2, 24)):
            m |= p.E(c + sx, c - 3.6 - (0.8 if sx == 0 else 0), 1.35, 2.7, rot=deg)       # the ears
        m |= p.path([(c - 3.0, c - 1.2), (c - 4.0, c - 3.0)], 0.9) | p.path([(c + 3.0, c - 1.2), (c + 4.0, c - 3.0)], 0.9)
        return m
    if kind == 'bell':
        return (p.P([(c - 2.6, c - 3.4), (c + 2.6, c - 3.4), (c + 4.2, c + 1.4), (c + 5.4, c + 3.2), (c - 5.4, c + 3.2), (c - 4.2, c + 1.4)])
                | p.R(c - 1, c - 5.6, c + 1, c - 3.4) | p.D(c, c + 5.8, 1.3))
    if kind == 'key':
        ring = np.abs(np.hypot(p.xs - c, p.ys - (c - 3.6)) - 2.6) < 0.95
        return ring | p.R(c - 0.6, c - 1.0, c + 0.6, c + 6.2) | p.R(c, c + 3.4, c + 2.8, c + 4.4) | p.R(c, c + 5.2, c + 2.2, c + 6.2)
    if kind == 'scales':
        m = p.R(c - 5.6, c - 3.6, c + 5.6, c - 2.6) | p.R(c - 0.6, c - 5.0, c + 0.6, c + 5.4) | p.R(c - 2.6, c + 5.2, c + 2.6, c + 6.2)
        for sx in (-4.6, 4.6):
            m |= p.path([(c + sx, c - 2.6), (c + sx - 2.0, c + 1.4)], 0.8) | p.path([(c + sx, c - 2.6), (c + sx + 2.0, c + 1.4)], 0.8)
            m |= p.E(c + sx, c + 2.0, 2.6, 1.0)
        return m
    a = p.P([(c, c - 6.4), (c + 5.6, c + 3.2), (c - 5.6, c + 3.2)])
    b = p.P([(c, c + 6.4), (c + 5.6, c - 3.2), (c - 5.6, c - 3.2)])
    return (a | b) & ~p.D(c, c, 1.6)


def wax_seal(ramp, kind, seed):
    """A wax seal, SEAL x SEAL: an uneven disc with a raised rim, its mark standing out of the sunken field in a lighter tone and
    shadowed on the lower right, as a die leaves it."""
    p = Pic(SEAL)
    c = SEAL / 2.0
    wob = vnoise(SEAL, SEAL, 5.0, seed)
    d = np.hypot(p.xs - c, p.ys - c)
    disc_m = d <= 10.2 + 2.6 * (wob - 0.5)
    g = p.sh(c, c, 10.0)
    rim_m = disc_m & (d > 7.4)
    field = disc_m & ~rim_m
    p.cv.put(rim_m, bands(ramp, 0.66 + 0.3 * g, 0.0))
    p.cv.put(field, bands(ramp, 0.3 - 0.1 * g, 0.0))
    p.flat(field & (d > 6.4) & (g < -0.1), ramp, 0.62)                                # the rim's inner wall catching light
    mk = seal_mark(p, kind, c) & field
    shadow = shift(mk, 1, 1) & ~mk & field
    p.flat(shadow, ramp, 0.02)
    p.flat(mk, ramp, 0.9 + 0.1 * (g > 0.2))
    return p.finish(0.8, 0.18)


def seal(index):
    """The wax seal of SEAL_COLOURS[index], or (index -1) the grey one a renderer tints to any colour it likes."""
    if index < 0:
        return wax_seal(WAX_GREY, 'wheel', 5)
    _, ramp, kind = SEAL_COLOURS[index]
    return wax_seal(ramp, kind, 11 + index)


# ================================================================ the notch row: a day left is a notch cut in the paper's edge

NOTCH_W, NOTCH_H = 8, 10


def notch(spent):
    """A notch on a ticket's lower edge, NOTCH_W x NOTCH_H, to lay over the paper at the edge: for a day still to run, a V bitten up
    out of the paper, the dark of the board showing through it; for a day gone, the V filled in with paper and struck across with ink."""
    p = Pic(NOTCH_W, NOTCH_H)
    v = p.P([(0.4, NOTCH_H), (NOTCH_W - 0.4, NOTCH_H), (NOTCH_W / 2.0, 1.2)])
    if not spent:
        p.flat(v, PAL['darkwood'], 0.25)
        p.flat(v & (p.xs < NOTCH_W / 2.0 - 0.6) & (p.ys < NOTCH_H - 1), PAL['darkwood'], 0.05)       # shadow under the cut's upper left
        p.flat(p.path([(0.0, NOTCH_H), (NOTCH_W / 2.0 - 0.4, 0.8)], 1.0) & ~v, PAL['parchment'], 0.9)    # the paper's lit edge
    else:
        p.flat(v, PAL['parchment'], 0.56)
        p.flat(p.path([(1.4, NOTCH_H - 1.4), (NOTCH_W - 1.4, 3.6)], 1.0) | p.path([(1.4, 3.6), (NOTCH_W - 1.4, NOTCH_H - 1.4)], 1.0), PAL['parchment_dark'], 0.42)
    return p.cv.image()


# ================================================================ the chalk strike, the report's quill, the dealer's lot wheel, the relief cross, the feast festoon

STRUCK_W, STRUCK_H = 64, 8
CHALK = [(150, 148, 142), (190, 188, 180), (222, 220, 210), (244, 242, 234)]


def struck():
    """A stroke of chalk through a row, STRUCK_W x STRUCK_H, for news gone stale: a hand-drawn line a little off level, thin at its
    ends and thick in the middle, the chalk skipping on the slate's grain, a dust of crumbs beside it. The middle 40 texels can be
    stretched to any length (the ends are 12 each)."""
    p = Pic(STRUCK_W, STRUCK_H)
    cy = 4.4 - (p.xs - 32.0) * 0.022 + 0.5 * np.sin(p.xs * 0.21)
    taper = np.clip(np.minimum(p.xs, STRUCK_W - 1 - p.xs) / 9.0, 0.35, 1.0)
    thick = (1.25 + 0.3 * (vnoise(STRUCK_W, STRUCK_H, 7.0, 3) - 0.5) * 2) * taper
    core = np.abs(p.ys - cy) < thick
    skip = rand(STRUCK_W, STRUCK_H, 9) > 0.9                                   # the slate's grain makes the chalk skip
    drawn = core & ~(skip & (np.abs(p.ys - cy) > 0.6))
    p.cv.put(drawn, bands(CHALK, 0.74 + 0.24 * (rand(STRUCK_W, STRUCK_H, 4) - 0.5) * 2 + 0.1 * (p.ys < cy), 0.0))
    crumbs = (rand(STRUCK_W, STRUCK_H, 12) > 0.955) & (np.abs(p.ys - cy) < 3.4) & ~core
    p.flat(crumbs, CHALK, 0.32)
    return p.cv.image()


def quill():
    """A quill laid across, 16 x 16: its plume to the upper right, the brass nib at the lower left."""
    p = Pic(16)
    a, b = (2.4, 13.6), (14.2, 1.8)
    ux, uy = (b[0] - a[0]), (b[1] - a[1])
    ln = math.hypot(ux, uy)
    ux, uy = ux / ln, uy / ln
    nx, ny = -uy, ux                                                           # the normal, pointing down-right
    left, right = [], []
    for k in range(15):
        t = 0.3 + 0.7 * k / 14.0
        x, y = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        w = 3.0 * math.sin(math.pi * min(1.0, (t - 0.22) / 0.84)) ** 0.7
        left.append((x - nx * w, y - ny * w))
        right.append((x + nx * w * 0.9, y + ny * w * 0.9))
    tip = (b[0] + ux * 0.4, b[1] + uy * 0.4)
    root = (a[0] + (b[0] - a[0]) * 0.3, a[1] + (b[1] - a[1]) * 0.3)
    vane_l = p.P(left + [tip, root])
    vane_r = p.P(right + [tip, root])
    p.cel(vane_l, PLUME, 0.9, 0.08, 0.14, 1, 1)
    p.cel(vane_r, PLUME, 0.66, 0.12, 0.2, 1, 1)
    for t in (0.5, 0.64, 0.78):                                                # the feather's splits
        x, y = a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t
        p.cv.a[p.D(x - nx * 3.2, y - ny * 3.2, 0.9)] = 0
        p.cv.a[p.D(x + nx * 3.0, y + ny * 3.0, 0.9)] = 0
    p.flat(p.path([a, b], 1.1), PAL['bone'], 0.92)
    p.flat(p.P([(0.6, 15.4), (3.0, 12.0), (4.6, 13.8)]), PAL['brass'], 0.76)                     # the nib
    p.px(1, 15, INK_PX)
    return p.finish(0.8, 0.2)


DEALER_LOT = 18


def dealer_lot():
    """The dealer's lot's mark, 18 x 18 texels (9 x 9 px): a small wagon wheel, a brass tyre on pale wood, six spokes, a dark hub
    with a brass cap. Light from the upper left."""
    p = Pic(DEALER_LOT)
    c = DEALER_LOT / 2.0
    d = np.hypot(p.xs - c, p.ys - c)
    p.cv.put((d > 6.8) & (d <= 8.6), bands(PAL['brass'], 0.66 + 0.34 * p.sh(c, c, 8.6), 0.0))
    p.cv.put((d > 5.2) & (d <= 6.8), bands(PAL['wood'], 0.9 + 0.2 * p.sh(c, c, 6.8), 0.0))
    p.cv.a[d <= 5.2] = 0                                                         # the gaps between the spokes show what is behind
    for k in range(6):
        a = math.radians(k * 60 + 15)
        sp = p.path([(c, c), (c + math.cos(a) * 5.6, c + math.sin(a) * 5.6)], 1.5) & (d <= 5.9)
        p.flat(sp, PAL['wood'], 0.96 - 0.3 * max(0.0, math.sin(a) + math.cos(a)) * 0.5)
    p.cv.put(d <= 2.4, bands(PAL['brass'], 0.7 + 0.3 * p.sh(c, c, 2.4), 0.0))
    p.flat(d <= 0.9, PAL['wood'], 0.2)
    p.px(c - 1, c - 1, PAL['brass'][5])
    return p.finish(0.85, 0.2)


CROSS = 24


def relief_cross():
    """The relief ticket's red cross, 24 x 24: a Greek cross with flared arms on a cream roundel, in a ring of red."""
    p = Pic(CROSS)
    c = CROSS / 2.0
    d = np.hypot(p.xs - c, p.ys - c)
    disc_m = d <= 11.2
    p.cv.put(disc_m, bands(PAD, 0.78 + 0.22 * p.sh(c, c, 11.2), 0.1))
    p.cv.put(disc_m & (d > 9.4), bands(GEMS['red'], 0.6 + 0.3 * p.sh(c, c, 11.2), 0.0))
    arm = 2.6
    cross = p.R(c - arm, c - 7.4, c + arm, c + 7.4) | p.R(c - 7.4, c - arm, c + 7.4, c + arm)
    flare = p.P([(c - arm, c - 7.4), (c + arm, c - 7.4), (c + 3.8, c - 8.4), (c - 3.8, c - 8.4)])
    flare |= p.P([(c - arm, c + 7.4), (c + arm, c + 7.4), (c + 3.8, c + 8.4), (c - 3.8, c + 8.4)])
    flare |= p.P([(c - 7.4, c - arm), (c - 7.4, c + arm), (c - 8.4, c + 3.8), (c - 8.4, c - 3.8)])
    flare |= p.P([(c + 7.4, c - arm), (c + 7.4, c + arm), (c + 8.4, c + 3.8), (c + 8.4, c - 3.8)])
    both = (cross | flare) & (d <= 9.0)
    p.cv.put(both, bands(GEMS['red'], 0.56 + 0.3 * p.sh(c, c, 9.0), 0.0))
    p.flat(both & ((p.xs < c - arm + 1.0) | (p.ys < c - arm + 1.0)) & (p.sh(c, c, 9.0) > 0.1), GEMS['red'], 0.82)
    return p.finish(0.8, 0.2)


def festoon(w=48):
    """The feast ticket's festoon, [w] x 0.29 w: a garland of leaves and red berries hung in a swag between two gold bows. Drawn
    at 48 wide for a ticket's paper and at 96 for the screen."""
    s = w / 48.0
    h = int(round(14 * s))
    p = Pic(w, h)
    rng = np.random.default_rng(23)
    xl, xr = 4.0 * s, w - 4.0 * s
    top_y, sag = 2.4 * s, 7.4 * s

    def cy(x):
        u = (x - xl) / (xr - xl)
        return top_y + sag * (1 - (2 * u - 1) ** 2)

    xs = np.linspace(xl, xr, 60)
    pts = [(x, cy(x)) for x in xs]
    p.flat(p.path(pts, 1.2 * s), PAL['wood'], 0.5)
    n = 17
    leaves = []
    for i in range(n):
        u = (i + 0.5) / n
        x = xl + (xr - xl) * u
        y = cy(x)
        ang = math.degrees(math.atan2(cy(x + 0.5) - cy(x - 0.5), 1.0))
        off = (1 if i % 2 == 0 else -1) * rng.uniform(0.3, 1.1) * s
        leaves.append((x, y + off, ang + rng.uniform(-38, 38), rng.choice([0.36, 0.52, 0.7])))
    for (x, y, ang, t) in leaves:
        m = p.E(x, y, 2.5 * s, 1.35 * s, rot=ang)
        p.round(m, LEAF, x, y, 2.5 * s, t, 0.22, 0.0)
        if s >= 1.5:
            vein = p.path([(x - 1.9 * s * math.cos(math.radians(ang)), y - 1.9 * s * math.sin(math.radians(ang))),
                           (x + 1.9 * s * math.cos(math.radians(ang)), y + 1.9 * s * math.sin(math.radians(ang)))], 0.8) & m
            p.flat(vein, LEAF, t + 0.26)
    for i in range(2, n - 1, 4):                                                 # berries
        u = (i + 0.5) / n
        x = xl + (xr - xl) * u
        y = cy(x) + 0.5 * s
        p.cv.put(p.D(x, y, 1.15 * s), bands(GEMS['red'], 0.62 + 0.3 * p.sh(x, y, 1.15 * s), 0.0))
        p.px(int(x - 0.6 * s), int(y - 0.6 * s), GEMS['red'][4])
    for bx in (xl, xr):                                                          # the bows
        by = top_y
        for sgn in (-1, 1):
            loop = p.E(bx + sgn * 2.3 * s, by - 0.2 * s, 2.3 * s, 1.5 * s, rot=-sgn * 24)
            p.round(loop, GEMS['amber'], bx + sgn * 2.3 * s, by, 2.4 * s, 0.7, 0.3, 0.0)
            p.flat(loop & (np.hypot(p.xs - (bx + sgn * 2.5 * s), p.ys - by) < 0.8 * s), GEMS['amber'], 0.2)
            tail = p.P([(bx + sgn * 0.5 * s, by + 0.6 * s), (bx + sgn * 2.6 * s, by + 4.6 * s), (bx + sgn * 1.2 * s, by + 4.0 * s)])
            p.flat(tail, GEMS['amber'], 0.5)
        p.flat(p.D(bx, by, 1.2 * s), GEMS['amber'], 0.86)
    return p.finish(0.8, 0.2)


# ================================================================ the ticket: stamped paper on a hook, and the ticket row of the market screen

TICKET_W, TICKET_H = 64, 72              # the paper's texels; it hangs 0.25 block wide on the board
TICKET_EYELET = (32, 7)                  # where the hook goes through
TICKET_STRIP = 11                        # the strip along the lower edge the notches are cut in (its top row is the rule above it)


def ticket_paper(back=False):
    """A ticket's paper, TICKET_W x TICKET_H: parchment with a deckled edge and a printed double rule round its face, an eyelet at
    the top for the hook, and along the foot a strip (cut off by a rule) where the days left are notched. [back] is the plain
    side a ticket turns to the board when it is flipped."""
    p = Pic(TICKET_W, TICKET_H)
    body = p.R(1, 1, TICKET_W - 1, TICKET_H - 1)
    deck = dist_in(body, 3)
    body = body & ~((deck == 1) & (rand(TICKET_W, TICKET_H, 17) < 0.1))
    for (x, y) in ((1, 1), (TICKET_W - 2, 1), (1, TICKET_H - 2), (TICKET_W - 2, TICKET_H - 2), (2, TICKET_H - 2), (TICKET_W - 3, TICKET_H - 2)):
        body[y, x] = False
    fib = fbm(TICKET_W, TICKET_H, 7, 3 if not back else 8)
    t = (0.74 if not back else 0.64) + 0.07 * (fib - 0.5) * 2
    p.cv.put(body, bands(PAL['parchment'], t, 0.12))
    flecks = body & (rand(TICKET_W, TICKET_H, 21) > 0.994)
    p.flat(flecks, PAL['parchment'], 0.5)
    flecks = body & (rand(TICKET_W, TICKET_H, 22) > 0.994)
    p.flat(flecks, PAL['parchment'], 0.94)
    lit = body & (~shift(body, 1, 0) | ~shift(body, 0, 1))
    dk = body & (~shift(body, -1, 0) | ~shift(body, 0, -1))
    p.flat(lit, PAL['parchment'], 0.92)
    p.flat(dk & ~lit, PAL['parchment'], 0.46)
    if not back:
        foot = TICKET_H - TICKET_STRIP - 1

        def box(k, bottom):
            return p.R(k, k, TICKET_W - k, bottom) & ~p.R(k + 1, k + 1, TICKET_W - k - 1, bottom - 1)

        rules = (box(4, foot) | box(6, foot)) & body
        p.flat(rules, PAL['parchment'], 0.4)
        p.flat(p.R(4, foot, TICKET_W - 4, foot + 1) & body, PAL['parchment'], 0.4)             # the rule above the notch strip
        for (x, y) in ((4, 4), (TICKET_W - 6, 4), (4, foot - 2), (TICKET_W - 6, foot - 2)):       # the little squares at the corners
            p.flat(p.R(x, y, x + 2, y + 2), PAL['parchment'], 0.3)
        ex, ey = TICKET_EYELET
        p.cv.put(p.D(ex, ey, 3.6), bands(PAL['brass'], 0.62 + 0.3 * p.sh(ex, ey, 3.6), 0.0))
        p.flat(p.D(ex, ey, 1.9), PAL['darkwood'], 0.1)
        p.flat(p.D(ex, ey, 1.9) & (p.xs > ex) & (p.ys > ey), PAL['darkwood'], 0.34)
    else:
        mid = TICKET_H // 2
        p.flat(p.R(2, mid, TICKET_W - 2, mid + 1) & body, PAL['parchment'], 0.4)               # the fold
        p.flat(p.R(2, mid + 1, TICKET_W - 2, mid + 2) & body, PAL['parchment'], 0.86)
    return p.finish(0.8, 0.18)


ROW_W, ROW_H = 64, 40                    # the ticket row's texels: nine-slice, nominal 32 x 20, border 5
ROW_KINDS = {'plain': (PAL['parchment'], 0.36), 'relief': (GEMS['red'], 0.52), 'feast': (GEMS['green'], 0.5)}


def ticket_row(kind):
    """The strip a ticket of the market screen's Hauls section is drawn on, ROW_W x ROW_H, to stretch as a nine-slice: parchment,
    deckled at the corners, a ruled line inside its edge in brown, red (relief) or green and gold (feast)."""
    p = Pic(ROW_W, ROW_H)
    ramp, lvl = ROW_KINDS[kind]
    body = p.R(0, 0, ROW_W, ROW_H)
    for (x, y) in ((0, 0), (ROW_W - 1, 0), (0, ROW_H - 1), (ROW_W - 1, ROW_H - 1)):
        body[y, x] = False
    body[1, 0] = body[0, 1] = body[1, ROW_W - 1] = body[0, ROW_W - 2] = False
    body[ROW_H - 2, 0] = body[ROW_H - 1, 1] = body[ROW_H - 2, ROW_W - 1] = body[ROW_H - 1, ROW_W - 2] = False
    p.cv.put(body, bands(PAL['parchment'], 0.72 + 0.1 * (1 - p.ys / ROW_H) - 0.04 * (p.ys > ROW_H - 4), 0.0))
    p.flat(body & ((p.xs == 0) | (p.ys == 0) | ~shift(body, 1, 0) | ~shift(body, 0, 1)), PAL['parchment'], 0.9)
    p.flat(body & (~shift(body, -1, 0) | ~shift(body, 0, -1)) & ~((p.xs == 0) | (p.ys == 0)), PAL['parchment'], 0.44)
    rule = (p.R(5, 5, ROW_W - 5, ROW_H - 5) & ~p.R(6, 6, ROW_W - 6, ROW_H - 6))
    p.flat(rule, ramp, lvl)
    if kind != 'plain':
        rule2 = (p.R(7, 7, ROW_W - 7, ROW_H - 7) & ~p.R(8, 8, ROW_W - 8, ROW_H - 8))
        p.flat(rule2, GEMS['red'] if kind == 'relief' else PAL['gold'], 0.58 if kind == 'relief' else 0.66)
    for (x, y) in ((5, 5), (ROW_W - 7, 5), (5, ROW_H - 7), (ROW_W - 7, ROW_H - 7)):
        p.flat(p.R(x, y, x + 2, y + 2), ramp if kind == 'plain' else PAL['gold'], lvl if kind == 'plain' else 0.7)
    return p.finish(0.8, 0.18)


# ================================================================ a contact sheet

def sheet(images, labels, scale, cols, bg, pad=6, maxw=1500):
    """A contact sheet: each picture at [scale] on [bg] with its label under it, laid left to right and wrapped at [maxw] pixels (so
    pictures of different sizes sit together)."""
    d0 = ImageDraw.Draw(Image.new('RGB', (4, 4)))
    cells = []
    for im, lab in zip(images, labels):
        w = max(im.width * scale, int(d0.textlength(lab)) + 2)
        cells.append((im, lab, w, im.height * scale))
    rows, row, x = [], [], pad
    for c in cells:
        if row and x + c[2] + pad > maxw:
            rows.append(row)
            row, x = [], pad
        row.append(c)
        x += c[2] + pad
    rows.append(row)
    height = pad + sum(max(c[3] for c in r) + 14 + pad for r in rows)
    width = max(pad + sum(c[2] + pad for c in r) for r in rows)
    out = Image.new('RGBA', (width, height), (*bg, 255))
    d = ImageDraw.Draw(out)
    ink = (226, 220, 206, 255) if sum(bg) < 400 else (40, 38, 46, 255)
    y = pad
    for r in rows:
        x = pad
        rh = max(c[3] for c in r)
        for im, lab, w, h in r:
            out.alpha_composite(im.resize((im.width * scale, im.height * scale), Image.NEAREST), (x, y))
            d.text((x, y + rh + 1), lab, fill=ink)
            x += w + pad
        y += rh + 14 + pad
    return out


def all_pieces():
    """Everything this module paints, as (group, name, image) in the order it is listed: haul_board_art.py packs these."""
    out = []
    for g in GOODS:
        for k in ('red', 'green'):
            out.append(('chip', g + '_' + k, chip(g, k)))
    for e in EVENTS:
        out.append(('event', e, badge(e)))
    for r in ROADS:
        out.append(('pennant', r, pennant_cloth(r)))
    out.append(('pennant', 'pole', pennant_pole()))
    out.append(('hourglass', 'frame', hg_frame()))
    out.append(('hourglass', 'sand_top', hg_sand_part(True)))
    out.append(('hourglass', 'sand_bottom', hg_sand_part(False)))
    for f in range(HG_STREAM_FRAMES):
        out.append(('hourglass', 'stream_%d' % f, hg_stream(f)))
    for lv in range(HG_LEVELS):
        out.append(('hourglass', 'level_%d' % lv, hourglass(lv)))
    for i in range(len(SEAL_COLOURS)):
        out.append(('seal', str(i), seal(i)))
    out.append(('seal', 'tint', seal(-1)))
    out.append(('notch', 'open', notch(False)))
    out.append(('notch', 'spent', notch(True)))
    out.append(('mark', 'struck', struck()))
    out.append(('mark', 'quill', quill()))
    out.append(('mark', 'dealer_lot', dealer_lot()))
    out.append(('mark', 'relief_cross', relief_cross()))
    out.append(('mark', 'festoon', festoon(64)))
    out.append(('mark', 'festoon_wide', festoon(96)))
    out.append(('ticket', 'paper', ticket_paper(False)))
    out.append(('ticket', 'back', ticket_paper(True)))
    for k in ROW_KINDS:
        out.append(('ticket', 'row_' + k, ticket_row(k)))
    return out


def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    icons = [good_icon(g) for g in GOODS]
    sheet(icons, GOODS, 8, 8, (58, 64, 80)).save(os.path.join(outdir, 'haul_icons_goods.png'))
    chips = [chip(g, k) for g in GOODS for k in ('red', 'green')]
    labels = [g + ' ' + k[0] for g in GOODS for k in ('red', 'green')]
    sheet(chips, labels, 5, 8, (40, 44, 56)).save(os.path.join(outdir, 'haul_icons_chips.png'))
    pieces = all_pieces()
    for group, scale, cols in (('event', 6, 5), ('pennant', 8, 5), ('hourglass', 8, 8), ('seal', 8, 7), ('notch', 10, 2)):
        sel = [(n, im) for g, n, im in pieces if g == group]
        sheet([im for _, im in sel], [n for n, _ in sel], scale, cols, (58, 64, 80)).save(os.path.join(outdir, 'haul_icons_%s.png' % group))
    sel = [(n, im) for g, n, im in pieces if g in ('mark', 'ticket')]
    sheet([im for _, im in sel], [n for n, _ in sel], 4, 4, (58, 64, 80)).save(os.path.join(outdir, 'haul_icons_marks.png'))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '.')
