"""Tales' items at 32x, painted with the HD art kit (hdkit.py) in the house style: a few hard tones to a material, light
from the upper left, a fine dark outline round each thing. Part G of the Tales design (living-world/stories/design.md,
13.2.2 to 13.2.4).

    python3 -B art/scripts/tale_items.py src/main/resources/assets/guildmark [preview-dir]

Writes

  * the sixteen keepsakes (`textures/item/keepsake/<look>.png`, `models/item/keepsake/<look>.json`) and the one item
    definition that picks between them by custom model data, `items/keepsake.json` (as `items/heist_prize.json` does);
  * the twelve carried props (`textures/item/tale_prop/<look>.png`, `models/item/tale_prop/`, `items/tale_prop.json`): what
    a person in a street scene holds, drawn by `LivingGoodsLayer` through the item's own model;
  * the quill, the tracker row's icon (`textures/item/tale_quill.png`, its model and `items/tale_quill.json`);
  * the mote, the gold specks at a cast member's shoulder (`textures/particle/tale_mote_0..3.png`, `particles/tale_mote.json`).

The lit lantern and the lit miner's lamp are animated: a strip of frames and a `.mcmeta`, so the flame inside moves.
Everything is seeded, so a rerun gives the same files. With a preview dir it also writes the sheets: every item large on
a dark and on a light ground, then as a hotbar shows them (32, 16 and the 6 or so pixels a carried prop is drawn at).
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw


HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import (PAL, GEMS, BAYER, Canvas, grid, vnoise, fbm, bands, poly, seg_dist, path_dist, disc, ellipse, rect, blur,
                   shift, edge, grow, light, rim, outline, glint, bevel)


N = 32
XS, YS = grid(N)


# ================================================================ the ramps these items add


LINEN = [(52, 46, 40), (96, 88, 76), (142, 132, 112), (184, 174, 150), (216, 208, 184), (238, 232, 210)]   # undyed, a little warm

WOOL = [(26, 32, 44), (44, 54, 70), (68, 80, 96), (96, 110, 124), (128, 142, 152), (164, 176, 182)]        # grey-blue wool

PETAL_WHITE = [(70, 66, 66), (130, 124, 118), (188, 182, 170), (226, 222, 208), (246, 242, 230)]

PETAL_GOLD = [(70, 44, 14), (130, 88, 24), (190, 140, 40), (232, 184, 66), (252, 226, 130)]

PETAL_BLUE = [(18, 26, 62), (36, 56, 120), (66, 96, 170), (110, 142, 210), (180, 204, 240)]

CLAY = PAL['cork']

INK = PAL['ink']

FLAME = [(70, 20, 8), (150, 54, 14), (214, 104, 24), (244, 164, 52), (255, 214, 110), (255, 246, 200)]


SOOT = [(8, 8, 10), (18, 17, 20), (30, 28, 30), (46, 42, 42)]


# ================================================================ small tools


def nb(stops, t, dither=0.3):
    """hdkit's bands on the 32 x 32 sprite, taking a plain number for a flat tone too."""
    return bands(stops, np.broadcast_to(np.asarray(t, float), (N, N)), dither)


class Frame:
    """A turned frame to draw a tilted thing in: u runs along its width, v down its length, from (cx, cy), the frame turned
    [deg] degrees clockwise on the sprite."""

    def __init__(self, cx, cy, deg):
        a = math.radians(deg)
        dx, dy = XS - cx, YS - cy
        self.u = dx * math.cos(a) + dy * math.sin(a)
        self.v = -dx * math.sin(a) + dy * math.cos(a)
        self.cx, self.cy, self.a = cx, cy, a

    def at(self, u, v):
        """The sprite point of the frame's (u, v)."""
        return (self.cx + u * math.cos(self.a) - v * math.sin(self.a), self.cy + u * math.sin(self.a) + v * math.cos(self.a))

    def box(self, u0, v0, u1, v1):
        return (self.u >= u0) & (self.u < u1) & (self.v >= v0) & (self.v < v1)

    def line(self, u0, v0, u1, v1, w):
        """Distance to a segment given in the frame, and how far along it."""
        (x0, y0), (x1, y1) = self.at(u0, v0), self.at(u1, v1)
        return seg_dist(x0, y0, x1, y1, N)

    def poly(self, pts):
        return poly([self.at(u, v) for u, v in pts], N)

    def disc(self, u, v, r):
        x, y = self.at(u, v)
        return disc(x, y, r, N)

    def ellipse(self, u, v, ru, rv):
        d = np.hypot((self.u - u) / ru, (self.v - v) / rv)
        return d <= 1, np.sqrt(np.clip(1 - d * d, 0, 1))


def path(pts, w, frame=None):
    """A thick polyline's mask and the run along it; [pts] in the frame if one is given, else on the sprite."""
    if frame is not None:
        pts = [frame.at(u, v) for u, v in pts]
    d, run = path_dist(pts, N)
    return d < w / 2, run, d


def finish(cv, glints=(), rim_k=0.3, outline_k=0.8, skip_rim=None):
    """The edge darkening, the dark outline round the thing and any glints."""
    before = cv.rgb.copy()
    rim(cv, rim_k)
    if skip_rim is not None:
        cv.rgb[skip_rim] = before[skip_rim]
    outline(cv, outline_k)
    for x, y, size, col in glints:
        glint(cv, x, y, size, col)
    return cv.image()


# ================================================================ the keepsakes (13.2.2)


KEEPSAKES = ('note', 'note_c', 'chair', 'wagon', 'child', 'fence', 'lute', 'moot', 'cart', 'lantern', 'informer',
             'informer_c', 'locket', 'locket_c', 'brother', 'brother_c')


PROPS = ('note_sealed', 'ransom_pouch', 'slate', 'thread_paper', 'locket', 'blanket', 'washing', 'lantern_paper_dark',
         'lantern_paper_lit', 'miners_lamp_dark', 'miners_lamp_lit', 'posy')


PAINTERS = {}


def painter(name):
    def deco(fn):
        PAINTERS[name] = fn
        return fn
    return deco


def R(x0, y0, x1, y1):
    return rect(int(x0), int(y0), int(x1), int(y1), N)


def D(cx, cy, r):
    return disc(cx, cy, r, N)[0]


def E(cx, cy, rx, ry):
    return ellipse(cx, cy, rx, ry, N)[0]


def P(pts):
    return poly(pts, N)


def L(x0, y0, x1, y1, w=1.0):
    """A line's mask, [w] pixels thick."""
    return seg_dist(x0, y0, x1, y1, N)[0] < w / 2


def part(cv, mask, pal, base=0.55, relief=0.0, k=1.0, edge=0.15, noise=0.0, seed=0, dither=0.25, extra=0.0):
    """Paint [mask] in the ramp [pal]: the tone [base] (plus [extra], a number or an array), the lit edges brighter and the
    shaded ones darker by [edge] (hdkit's bevel), the whole rounded over like a pillow if [relief] (a radius in pixels) is
    set, lit from the upper left by [k], and a mottle of [noise]. Returns the tone it painted."""
    t = np.zeros((N, N)) + base + extra
    if relief:
        h = blur(mask.astype(float), max(1, int(round(relief))))
        t = t + k * light(h * 3.0, 1.0)
    if edge:
        t = t + edge * bevel(mask)
    if noise:
        t = t + noise * (fbm(N, N, 5, seed) - 0.5) * 2
    cv.put(mask, nb(pal, t, dither))
    return t


def edge_bands(mask, lit=1, dark=2):
    """Crisp light on a flat shape: +1 on the [lit] pixels along its up-left edges, -1 on the [dark] pixels along its down-right
    ones (where both reach, the shade wins), 0 elsewhere."""
    m = mask.astype(bool)
    lit_m = np.zeros(m.shape, bool)
    dark_m = np.zeros(m.shape, bool)
    for k in range(1, lit + 1):
        lit_m |= (m & ~shift(m, k, 0)) | (m & ~shift(m, 0, k))
    for k in range(1, dark + 1):
        dark_m |= (m & ~shift(m, -k, 0)) | (m & ~shift(m, 0, -k))
    return lit_m * 1.0 - dark_m * 1.0 + (lit_m & dark_m) * 1.0


def cel(cv, mask, pal, base=0.6, hi=0.24, lo=0.26, lw=1, dw=2, extra=0.0, dither=0.0):
    """Paint [mask] flat in [pal] at tone [base] with [lw] pixels of lit edge up-left and [dw] of shade down-right."""
    b = edge_bands(mask, lw, dw)
    cv.put(mask, nb(pal, base + extra + hi * (b > 0) - lo * (b < 0), dither))


def dash_row(cv, mask, y, x0, x1, seed, col, a=0.9, gap=1, lo=2, hi=4, thick=1):
    """A row of small writing: short strokes with gaps, from [x0] to [x1] at row [y]."""
    rng = np.random.default_rng(seed)
    x = x0
    while x < x1:
        w = int(rng.integers(lo, hi + 1))
        for i in range(min(w, x1 - x)):
            for j in range(thick):
                if mask is None or mask[y + j, x + i]:
                    cv.px(x + i, y + j, np.asarray(col, float), a)
        x += w + gap


def weave(mask, step=2, phase=0):
    """A cloth's cross-hatch: 0 or 1 on a checkerboard of [step]-pixel cells, over [mask]."""
    return (((XS // step).astype(int) + (YS // step).astype(int) + phase) % 2 == 0) & mask


PEN_DARK = (44, 28, 26)


def pix(cv, rows, x0, y0, key):
    """A little hand-drawn picture: one string per row, each character a colour from [key] ('.' and ' ' leave a pixel alone)."""
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            c = key.get(ch)
            if c is not None:
                cv.px(x0 + i, y0 + j, np.asarray(c, float), 1.0)


def seal(cv, cx, cy, r, seed=0, crack=False, imprint=True):
    """A disc of sealing wax: the edge a little wobbly, the dome lit from the upper left, a ring and a small cross pressed in
    it; [crack] splits it down the middle and lets the right half drop a pixel."""
    wob = vnoise(N, N, 2.0, seed)
    m = D(cx, cy, r) | (D(cx, cy, r + 0.6) & (wob > 0.6))
    d = np.hypot(XS - cx, YS - cy)
    dome = np.sqrt(np.clip(1 - (d / (r + 0.2)) ** 2, 0, 1))
    h = dome * 0.8 + 0.25 * (np.abs(d - r * 0.68) < 0.5)
    t = 0.68 + 0.8 * light(h, 2.2) + 0.12 * dome
    sc = Canvas(N)
    sc.put(m, nb(PAL['wax'], t, 0))
    if imprint:
        cross = ((np.abs(XS - cx) < 0.6) & (np.abs(YS - cy) < r * 0.4)) | ((np.abs(YS - cy) < 0.6) & (np.abs(XS - cx) < r * 0.4))
        sc.put(cross & m, nb(PAL['wax'], t - 0.34, 0))
    if not crack:
        cv.put(m, sc.rgb)
        return m
    right = m & ((XS - cx) * 0.95 - (YS - cy) * 0.3 >= 0.0)
    left = m & ~right
    cv.put(left, sc.rgb)
    moved = shift(right, 1, 1)
    cv.put(right & ~moved, (22, 5, 10))                       # the gap the break leaves, in shadow
    cv.put(moved, shift(sc.rgb, 1, 1))
    return m | moved


@painter('keep:note')
def keep_note():
    """The cleared note: a paper once folded in thirds, flat now, the corner turned down, a clean diagonal stroke of ink
    through the sum, the red seal low on it, cracked."""
    cv = Canvas(N)
    paper = R(5, 3, 26, 28) & ~P([(19, 3), (26, 3), (26, 10)])
    t = 0.8 + 0.2 * bevel(paper) + 0.04 * (fbm(N, N, 6, 11) - 0.5)
    for y in (11, 19):                                  # the folds it was kept in: a dark line with a light one under
        t = np.where(paper & (YS > y) & (YS < y + 1), t - 0.3, t)
        t = np.where(paper & (YS > y + 1) & (YS < y + 2), t + 0.1, t)
    cv.put(paper, nb(PAL['parchment'], t, 0.2))
    flap = P([(19, 3), (26, 10), (19, 10)])             # the corner turned down, its back showing
    cv.put(flap, nb(PAL['parchment'], 0.42 + 0.14 * bevel(flap), 0))
    cv.put(L(19, 3.5, 26, 10.5, 1.2) & paper, nb(PAL['parchment'], 1.0, 0))
    ink = (66, 44, 36)
    for y, x1, sd in ((5, 17, 1), (7, 15, 2), (9, 17, 3)):
        dash_row(cv, paper, y, 8, x1, sd, ink, 0.85)
    dash_row(cv, paper, 13, 8, 22, 4, PEN_DARK, 1.0, gap=1, lo=2, hi=3, thick=2)       # the sum
    dash_row(cv, paper, 16, 8, 20, 5, PEN_DARK, 1.0, gap=1, lo=2, hi=3, thick=1)
    stroke = L(8.5, 6.5, 23.5, 19.5, 2.1) & paper
    cv.put(stroke, INK[0])
    cv.put(L(8.5, 6.0, 23.5, 19.0, 0.8) & stroke, INK[1])
    seal(cv, 20.4, 22.6, 4.8, 7, crack=True)
    return finish(cv, [(18, 20, 1, (255, 214, 190))])


BOW = ['..dddd.dddd..',
       '.dmmmmdmmmmd.',
       'dmlmmmkmmmlmd',
       'dmmmmmkmmmmmd',
       '.ddmmmkmmmdd.',
       '...ddmkmdd...',
       '....dmkmd....',
       '...dmd.dmd...',
       '..dmd...dmd..',
       '..ddd...ddd..']


@painter('keep:note_c')
def keep_note_c():
    """The lease: a paper folded in thirds and tied across the middle with a plain leather cord, a thumbprint in ink on it."""
    cv = Canvas(N)
    paper = R(6, 4, 26, 28)
    t = 0.8 + 0.2 * bevel(paper) + 0.04 * (fbm(N, N, 6, 21) - 0.5)
    for y in (12, 20):
        t = np.where(paper & (YS > y) & (YS < y + 1), t - 0.28, t)
        t = np.where(paper & (YS > y + 1) & (YS < y + 2), t + 0.1, t)
    cv.put(paper, nb(PAL['parchment'], t, 0.2))
    ink = (66, 44, 36)
    for y, x1, sd in ((6, 22, 1), (8, 20, 2), (10, 23, 3), (23, 15, 4), (25, 14, 5)):
        dash_row(cv, paper, y, 8, x1, sd, ink, 0.8)
    # the thumbprint, low on the right: an inked oval with the ridges left pale in it
    pr = np.hypot((XS - 21.4) / 3.7, (YS - 23.6) / 4.4)
    cv.put((pr < 1) & paper, (74, 44, 38))
    cv.put(((np.abs(pr - 0.32) < 0.1) | (np.abs(pr - 0.66) < 0.1)) & (pr < 1) & paper & (vnoise(N, N, 2, 5) > 0.22), nb(PAL['parchment'], 0.7, 0))
    # the cord, round the middle, and its bow
    band = R(6, 14, 26, 16)
    cv.put(band, nb(PAL['leather'], 0.62, 0))
    cv.put(R(6, 15, 26, 16), nb(PAL['leather'], 0.36, 0))
    key = {'d': PAL['leather'][1], 'm': PAL['leather'][3], 'l': PAL['leather'][5], 'k': PAL['leather'][2]}
    pix(cv, BOW, 10, 11, key)
    return finish(cv, [(8, 5, 1, (255, 232, 200))])


def blossom(cv, cx, cy, r, pal, heart, petals=5, turn=0.0):
    """A flower seen from the front: [petals] rounded petals round a [heart]."""
    for i in range(petals):
        a = turn + i * math.tau / petals
        x, y = cx + math.cos(a) * r * 0.62, cy + math.sin(a) * r * 0.62
        lit = 0.62 + 0.22 * (-(math.cos(a) * 0.6 + math.sin(a) * 0.8))
        part(cv, D(x, y, r * 0.55), pal, lit, edge=0.2, dither=0)
    cv.put(D(cx, cy, r * 0.32), heart)


@painter('keep:chair')
def keep_chair():
    """A pressed posy: three dried flowers between two small squares of paper, tied with thread."""
    cv = Canvas(N)
    back = R(3, 3, 22, 22)
    front = R(9, 8, 28, 28)
    part(cv, back, PAL['parchment'], 0.5, edge=0.2, dither=0)
    part(cv, front, PAL['parchment'], 0.86, edge=0.2, dither=0, noise=0.03, seed=3)
    cv.put(R(10, 9, 27, 10) & front, nb(PAL['parchment'], 1.0, 0))
    # the stems, converging on the thread
    for x0, y0 in ((12.0, 15.0), (18.0, 12.0), (24.0, 15.0)):
        d, _ = seg_dist(x0, y0, 18.0, 22.0, N)
        cv.put((d < 0.55) & front, nb(PAL['moss'], 0.5, 0))
    for cx, cy, a in ((14.4, 19.0, -0.5), (21.8, 19.0, 0.5)):             # two leaves
        lf = np.hypot(((XS - cx) * math.cos(a) + (YS - cy) * math.sin(a)) / 2.7, (-(XS - cx) * math.sin(a) + (YS - cy) * math.cos(a)) / 1.3) <= 1
        part(cv, lf & front, PAL['moss'], 0.7, edge=0.2, dither=0)
    blossom(cv, 12.0, 15.0, 2.9, PAL['crimson'], PAL['gold'][3], 5, 0.4)
    blossom(cv, 18.0, 11.8, 3.5, PETAL_WHITE, PAL['gold'][3], 6, 0.2)
    blossom(cv, 24.0, 15.0, 2.7, PAL['crimson'], PAL['gold'][3], 5, 1.0)
    # the tissue over them dulls what is under: a veil of the paper's own colour
    cv.over(front & (YS < 21) & ~R(9, 8, 28, 10), PAL['parchment'][4], 0.16)
    # the thread, round both squares, and a bow low on the front
    cv.put(R(3, 21, 28, 23) & (back | front), nb(PAL['cork'], 0.72, 0))
    cv.put(R(3, 22, 28, 23) & (back | front), nb(PAL['cork'], 0.4, 0))
    pix(cv, ['.dd.dd.',
             'dmmkmmd',
             '.dmkmd.',
             '..dkd..',
             '.dm.md.',
             '.dd.dd.'], 15, 20, {'d': PAL['cork'][1], 'm': PAL['cork'][3], 'k': PAL['cork'][2]})
    return finish(cv, [(4, 4, 1, (255, 240, 214))])


@painter('keep:wagon')
def keep_wagon():
    """The hand's wagon tag: a small brass plate stamped with a covered wagon, on a loop of leather cord."""
    cv = Canvas(N)
    # the cord: a loop through the hole, behind the plate
    ring = E(16, 7.2, 4.6, 6.2) & ~E(16, 7.4, 2.8, 4.4)
    part(cv, ring, PAL['leather'], 0.55, edge=0.25, dither=0)
    cv.put(ring & (((XS + YS) % 3) < 1) & (YS > 3), nb(PAL['leather'], 0.34, 0))           # the twist of the cord
    plate = R(4, 10, 28, 30) & ~R(4, 10, 6, 12) & ~R(26, 10, 28, 12) & ~R(4, 28, 6, 30) & ~R(26, 28, 28, 30)
    part(cv, plate, PAL['brass'], 0.64, edge=0.3, dither=0)
    field = R(6, 12, 26, 28) & plate
    cv.put(field, nb(PAL['brass'], 0.5 - 0.14 * bevel(field), 0))                             # the field sunk inside the raised rim
    # the wagon, stamped in: a bowed cover over the bed, two wheels, a drawbar
    cover = E(16.5, 20.4, 7.4, 4.6) & (YS < 20.4)
    bed = R(9, 20, 24, 24)
    bar = L(9.0, 22.6, 6.6, 25.4, 1.3) & field
    wheels = D(12.4, 24.6, 3.0) | D(20.6, 24.6, 3.0)
    motif = cover | bed | bar | wheels
    cv.put(grow(motif) & ~motif & field, nb(PAL['brass'], 0.16, 0))
    cv.put(motif, nb(PAL['brass'], 0.86 + 0.22 * bevel(motif) - 0.08 * (YS > 22.5), 0))
    cv.put(bed & (YS >= 22) & ~wheels, nb(PAL['brass'], 0.58, 0))                              # the bed's lower board
    cv.put(cover & ((np.abs(XS - 12.5) < 0.5) | (np.abs(XS - 16.5) < 0.5) | (np.abs(XS - 20.5) < 0.5)), nb(PAL['brass'], 0.5, 0))
    for cx in (12.4, 20.6):                                                                    # spokes and hub
        wm = D(cx, 24.6, 3.0)
        cv.put(wm & ~D(cx, 24.6, 2.1), nb(PAL['brass'], 0.5, 0))
        cv.put(D(cx, 24.6, 2.1) & ((np.abs(XS - cx) < 0.55) | (np.abs(YS - 24.6) < 0.55)), nb(PAL['brass'], 0.22, 0))
        cv.put(D(cx, 24.6, 0.8), nb(PAL['brass'], 0.1, 0))
    hole = D(16, 12.4, 1.5)                                                                    # the hole, the cord through it
    cv.put(hole, nb(PAL['brass'], 0.02, 0))
    cv.put(hole & (YS < 12.6), nb(PAL['leather'], 0.6, 0))
    return finish(cv, [(6, 11, 1, (255, 240, 200))], rim_k=0.25)


@painter('keep:child')
def keep_child():
    """A wooden horse, carved, one ear chipped, the paint worn."""
    cv = Canvas(N)
    W = PAL['wood']
    # far legs, a tone back
    for pts in ([(11.6, 18.0), (14.2, 18.0), (14.0, 27.0), (11.4, 27.0)], [(20.6, 18.0), (23.2, 18.0), (22.8, 27.0), (20.2, 27.0)]):
        m = P(pts)
        part(cv, m, W, 0.34, edge=0.2, dither=0)
        cv.put(m & (YS > 25.2), nb(PAL['darkwood'], 0.4, 0))
    body = E(16.0, 16.2, 8.7, 4.7)
    head = P([(4.0, 12.4), (3.6, 9.8), (5.4, 7.2), (8.0, 4.6), (10.4, 3.4), (13.0, 3.8), (15.0, 6.5), (16.2, 11.0), (16.0, 13.5),
              (10.0, 15.8), (8.2, 14.8), (8.4, 12.6), (6.6, 13.0)])
    ear_a = P([(9.6, 4.4), (10.0, 1.0), (12.2, 4.2)])
    ear_b = P([(12.2, 4.2), (12.6, 1.6), (13.6, 2.6), (14.2, 1.9), (14.7, 3.2), (14.3, 5.2)])
    legs = [P([(8.4, 18.0), (11.4, 18.0), (11.0, 27.0), (7.8, 27.0)]), P([(19.6, 17.8), (23.4, 17.8), (24.2, 22.6), (23.2, 27.0), (20.4, 27.0), (21.0, 22.6)])]
    horse = body | head | ear_a | ear_b | legs[0] | legs[1]
    part(cv, horse, W, 0.6, relief=2, k=0.9, edge=0.3, dither=0.2, noise=0.03, seed=9)
    for m in legs:
        cv.put(m & (YS > 25.0), nb(PAL['darkwood'], 0.5 + 0.2 * bevel(m), 0))                  # hooves
    # mane and tail in the dark wood
    mane = P([(10.6, 3.4), (13.2, 3.6), (15.4, 6.6), (16.6, 11.2), (16.8, 13.8), (14.8, 12.8), (14.8, 8.8), (12.4, 6.2)])
    tail = P([(24.0, 12.8), (26.6, 12.6), (27.8, 15.6), (27.6, 20.8), (25.8, 22.4), (26.0, 17.4), (24.2, 14.8)])
    for m in (mane, tail):
        part(cv, m, PAL['darkwood'], 0.7, relief=1, k=0.8, edge=0.3, dither=0)
    cv.put(mane & ((XS * 2 + YS) % 5 < 1), nb(PAL['darkwood'], 0.34, 0))
    # the paint, worn: a red saddle-cloth with a blue trim, chipped through to the wood, and a red bridle
    cloth = P([(13.4, 11.6), (21.0, 11.4), (21.6, 16.8), (19.2, 17.6), (17.4, 16.0), (13.2, 15.8)])
    worn = vnoise(N, N, 2.2, 31) > 0.66
    cv.put(cloth & ~worn, nb(PAL['crimson'], 0.64 + 0.3 * bevel(cloth), 0))
    trim = cloth & (L(13.2, 15.6, 17.4, 15.9, 1.2) | L(17.4, 15.9, 21.5, 17.1, 1.2)) & ~worn
    cv.put(trim, nb(GEMS['blue'], 0.62, 0))
    bridle = (L(4.2, 10.4, 6.8, 13.2, 1.1) | L(6.6, 8.4, 9.4, 10.8, 1.0)) & horse
    cv.put(bridle & (vnoise(N, N, 1.6, 12) > 0.3), nb(PAL['crimson'], 0.6, 0))
    # eye, nostril and a bright glint on the poll where hands wore the paint away
    cv.px(8, 7, INK[0])
    cv.px(8, 6, nb(PAL['bone'], 0.9, 0)[0, 0])
    cv.px(4, 10, INK[0])
    return finish(cv, [(9, 2, 1, (255, 232, 190))])


def clay_cup(cv, cx, ytop, rt, rb, ht, seed, handle):
    """One clay cup, a little from above: a body drawn in toward the foot, the mouth an ellipse with a pale lip, a slip band,
    a ring handle on the side [handle] (-1 left, 1 right)."""
    ry = rt * 0.38
    ybot = ytop + ht
    frac = np.clip((YS - ytop) / ht, 0, 1)
    hw = rt + (rb - rt) * frac
    body = (np.abs(XS - cx) <= hw) & (YS >= ytop) & (YS <= ybot)
    foot = E(cx, ybot, rb, rb * 0.38) & (YS > ybot)
    mouth = E(cx, ytop, rt, ry)
    # the handle behind the body: a ring
    hx = cx + handle * (rt + 0.6)
    hr = E(hx, ytop + 5.6, 2.9, 3.6) & ~E(hx, ytop + 5.6, 1.4, 2.0)
    part(cv, hr & ~(body | foot | mouth), CLAY, 0.62, edge=0.3, dither=0)
    solid = body | foot | mouth
    u = (XS - cx) / np.maximum(hw, 1.0)
    t = 0.74 - 0.4 * u + 0.1 * (fbm(N, N, 5, seed) - 0.5) - 0.12 * frac
    cv.put(solid, nb(CLAY, t, 0.2))
    cv.put(solid & (u > 0.8), nb(CLAY, 0.42, 0))                                    # reflected light low on the dark side
    cv.put(body & (np.abs(u + 0.5) < 0.09) & (frac > 0.12) & (frac < 0.8), nb(CLAY, 1.0, 0))      # the glaze's glint
    band = body & (YS > ytop + 3.0) & (YS < ytop + 4.8)
    cv.put(band, nb(CLAY, t - 0.36, 0.1))
    cv.put(body & (YS >= ytop + 4.8) & (YS < ytop + 5.8), nb(CLAY, t + 0.1, 0.1))
    inner = E(cx, ytop + 0.3, rt - 1.4, ry - 1.0)
    lip = mouth & ~inner
    cv.put(lip, nb(PAL['bone'], 0.78 + 0.25 * bevel(lip) - 0.1 * (XS > cx + rt * 0.5), 0))
    cv.put(inner, nb(CLAY, 0.16 + 0.2 * ((YS - ytop) / max(ry, 1)), 0))
    cv.put(inner & (YS < ytop - 0.2), nb(CLAY, 0.32, 0))


@painter('keep:fence')
def keep_fence():
    """Two clay cups side by side."""
    cv = Canvas(N)
    clay_cup(cv, 10.0, 10.5, 6.0, 4.8, 11.0, 3, -1)
    clay_cup(cv, 22.0, 14.0, 6.0, 4.8, 11.0, 8, 1)
    return finish(cv, [(5, 9, 1, (255, 244, 220))])


def pick_shape(angle, cx, cy, big=9.2, tip=16.0):
    """A plectrum's outline: a round shoulder drawn down to a point, turned [angle] degrees."""
    phi = math.acos(big / tip)
    pts = [(big * math.sin(t), big * math.cos(t)) for t in np.linspace(phi, 2 * math.pi - phi, 48)] + [(0.0, tip)]
    a = math.radians(angle)
    return P([(cx + x * math.cos(a) - y * math.sin(a), cy + x * math.sin(a) + y * math.cos(a)) for x, y in pts])


def tortoise(cv, m, seed, cell=5.0):
    """Tortoise-shell in [m]: amber with deeper brown patches and a few cream ones, the thin edge lit, a gloss along the shoulder."""
    f = fbm(N, N, cell, seed, octaves=2)
    t = np.where(f < 0.38, 0.28, np.where(f < 0.64, 0.58, 0.8))
    cv.put(m, nb(GEMS['amber'], t + 0.1 * bevel(m), 0))
    cv.put(m & (f >= 0.86), nb(GEMS['amber'], 1.0, 0))
    inner = m & shift(m, 2, 2) & ~shift(m, 3, 3)
    cv.put(inner & (XS + YS < 34), nb(GEMS['amber'], 0.98, 0))                           # the gloss
    cv.put(m & ~shift(m, 1, 1), nb(GEMS['amber'], 1.0, 0))


@painter('keep:lute')
def keep_lute():
    """A lute pick: a thin tortoise-shell teardrop."""
    cv = Canvas(N)
    m = pick_shape(-24, 15.0, 8.8, 8.4, 16.5)
    tortoise(cv, m, 23, 3.4)
    return finish(cv, [(10, 8, 2, (255, 248, 220))])


def _shift(m, dx, dy):
    return np.roll(np.roll(m, dy, 0), dx, 1)


@painter('keep:moot')
def keep_moot():
    """The moot token: a slice of dark wood with a small iron bell burned into its face."""
    cv = Canvas(N)
    disc_m = D(16, 16, 12.8)
    face = D(16, 16, 10.6)
    rim_m = disc_m & ~face
    lit = -(((XS - 16) + (YS - 16)) / (12.8 * 1.4))
    cv.put(rim_m, nb(PAL['darkwood'], 0.62 + 0.5 * lit, 0))
    rings = np.mod(np.hypot(XS - 14.0, YS - 18.5) * 0.85, 1.8) < 0.55
    t = 0.56 + 0.1 * (fbm(N, N, 5, 4) - 0.5) + 0.1 * lit
    cv.put(face, nb(PAL['darkwood'], t - 0.1 * rings, 0.1))
    cv.put(face & ~_shift(face, 1, 1), nb(PAL['darkwood'], 0.28, 0))                  # the face sunk a hair below the rim
    # the bell: a crown, a dome, a flared skirt with a lip, a clapper; scorched round its edge
    dome = E(16, 15.2, 4.4, 4.8) & (YS < 16.2)
    skirt = P([(11.6, 15.6), (20.4, 15.6), (22.4, 21.2), (9.6, 21.2)])
    lip = R(9, 21, 23, 23)
    ring = (np.hypot(XS - 16, YS - 9.8) < 2.0) & (np.hypot(XS - 16, YS - 9.8) > 0.9)
    bell = dome | skirt | lip | D(16, 11.2, 1.6)
    clapper = D(16, 24.4, 1.6)
    scorch = grow(bell | clapper | ring, 1) & ~(bell | clapper | ring) & face
    cv.put(scorch, nb(PAL['darkwood'], 0.16, 0))
    u = (XS - 16) / 6.0
    cv.put(bell, nb(PAL['iron'], 0.74 - 0.5 * u + 0.18 * bevel(bell), 0.0))
    cv.put(lip, nb(PAL['iron'], 0.6 - 0.3 * u, 0))
    cv.put(lip & (YS >= 22), nb(PAL['iron'], 0.32, 0))
    cv.put(ring, nb(PAL['iron'], 0.6, 0))
    cv.put(clapper, nb(PAL['iron'], 0.62 + 0.3 * bevel(clapper), 0))
    cv.put(bell & (np.abs(XS - 13.2) < 0.5) & (YS > 12) & (YS < 20), nb(PAL['iron'], 1.0, 0))    # its highlight
    return finish(cv, [(7, 7, 2, (255, 226, 180))])


@painter('keep:cart')
def keep_cart():
    """A wheel spoke: a turned oak spoke broken at one end, a nail still driven through it."""
    cv = Canvas(N)
    F = Frame(16.2, 15.8, 50)
    v, u = F.v, F.u
    hw = np.interp(v, [-13.5, 4.0, 6.3, 6.6, 14.0], [2.2, 3.4, 3.4, 1.6, 1.6])
    inside = (np.abs(u) <= hw) & (v >= -13.5) & (v <= 14.0)
    teeth = 2.4 * np.abs(np.mod((u + 3.0) * 0.7, 2.0) - 1.0) + 0.5 * np.sin(u * 4.0)
    broken = (v < -10.2 + teeth) & (v < -8.2)
    spoke = inside & ~broken
    splinters = (F.poly([(-1.8, -9.2), (-0.1, -9.2), (-1.1, -16.2)]) | F.poly([(0.4, -9.4), (2.4, -9.4), (2.0, -14.6)])) & ~spoke
    un = u / np.maximum(hw, 0.5)
    t = np.where(un < -0.35, 0.9, np.where(un < 0.4, 0.7, 0.44))                                    # a turned rod: three flat bands
    cv.put(spoke, nb(PAL['wood'], t, 0))
    cv.put(spoke & ((np.abs(u + 1.2) < 0.4) | (np.abs(u - 1.0) < 0.4)) & (np.mod(v + 3.0, 9.0) < 5.5) & (v < 5.0), nb(PAL['wood'], 0.3, 0))      # grain
    cv.put(spoke & (v < -7.2) & (np.abs(u) < hw - 0.4), nb(PAL['wood'], 1.0 - 0.18 * (un > 0.2), 0))       # the pale wood where it broke
    cv.put(splinters, nb(PAL['wood'], 1.0, 0))
    cv.put(spoke & (v > 6.0) & (v < 7.0), nb(PAL['wood'], 0.2, 0))                                    # the shoulder where the tenon starts
    cv.put(spoke & (v > 13.0), nb(PAL['wood'], 1.0, 0))                                               # the squared end of the tenon
    # the nail: a flat head on the near side, the point through the far side and bent down
    head = F.box(-5.8, -2.9, -3.0, -0.1)
    tip = path([F.at(2.6, -1.5), F.at(5.8, -1.5), F.at(6.6, 1.6)], 1.3)[0] & ~spoke
    cv.put(grow(head | tip) & ~(head | tip) & ~spoke, nb(PAL['iron'], 0.08, 0))
    cv.put(head, nb(PAL['iron'], 0.84, 0))
    cv.put(head & ~shift(head, 0, -1), nb(PAL['iron'], 1.0, 0))
    cv.put(tip, nb(PAL['iron'], 0.6, 0))
    return finish(cv, [], rim_k=0.25)


@painter('keep:lantern')
def keep_lantern():
    """A small paper lantern, dark and folded flat: five pleats of dull paper between two gold caps, bound with gold thread."""
    cv = Canvas(N)
    hb = PAL['parchment_dark']
    loop = E(16, 5.0, 3.4, 3.8) & ~E(16, 5.4, 1.9, 2.3)
    cv.put(loop, nb(PAL['gold'], 0.66 + 0.2 * bevel(loop), 0))
    for yc, hw in zip((9.4, 12.6, 15.8, 19.0, 22.2), (6.2, 8.4, 9.6, 8.4, 6.2)):
        m = E(16, yc, hw, 2.7)
        u = (XS - 16) / hw
        cv.put(m, nb(hb, np.where(u < -0.5, 0.92, np.where(u < 0.35, 0.74, 0.5)), 0))
        top = m & ~shift(m, 0, 1)                                                      # each pleat's top edge: a dark crease, a light lip under it
        cv.put(top, nb(hb, 0.12, 0))
        cv.put(m & shift(top, 0, 1) & ~top, nb(hb, 1.0 - 0.3 * (u > 0.35), 0))
    for cap in (R(10, 5, 22, 8), R(10, 24, 22, 27)):
        cel(cv, cap, PAL['gold'], 0.62, 0.3, 0.3, 1, 1)
        cv.put(cap & (np.mod(XS, 3) < 1) & (YS == cap.nonzero()[0].min() + 1), nb(PAL['gold'], 0.4, 0))
    for x, ln in ((14.5, 3), (16.0, 4), (17.5, 3)):                                      # a short tassel of thread
        cv.put(L(x, 27.0, x - 0.3 + (x - 16) * 0.3, 27.0 + ln, 0.9), nb(PAL['gold'], 0.74, 0))
    return finish(cv, [(11, 11, 1, (255, 228, 170))], rim_k=0.2)


@painter('keep:informer')
def keep_informer():
    """The notched tally stick: half a hazel rod, bark on one side, a row of cuts in the other, a hole for a thong."""
    cv = Canvas(N)
    F = Frame(15.4, 17.0, 45)
    hw = 3.3
    stick = (np.abs(F.u) <= hw) & (F.v >= -12.5) & (F.v <= 12.5)
    stick &= ~F.poly([(-hw - 1, -13.5), (hw + 1, -13.5), (hw + 1, -11.0), (-hw - 1, -12.6)])
    stick &= ~F.poly([(-hw - 1, 13.5), (hw + 1, 13.5), (hw + 1, 11.4), (-hw - 1, 12.2)])
    bark = stick & (F.u > 1.7)
    face = stick & ~bark
    cv.put(face, nb(PAL['wood'], np.where(F.u < -1.6, 0.86, 0.72), 0))
    cv.put(bark, nb(PAL['darkwood'], 0.8 + 0.18 * np.sin(F.v * 1.4), 0))
    cv.put(stick & (np.abs(F.u - 1.7) < 0.5), nb(PAL['wood'], 0.3, 0))
    # the notches, hand-cut so none are quite even, the fifth long: bone-pale across the face, a shadow beside each
    for k, vv in enumerate((-8.6, -5.7, -3.0, -0.4, 2.5, 6.0, 8.8)):
        u1 = 3.4 if k == 4 else 1.4
        cut = F.box(-2.9, vv - 0.45, u1, vv + 0.45)
        cv.put(cut & stick, nb(PAL['bone'], 1.0, 0))
        cv.put(F.box(-2.9, vv + 0.45, u1, vv + 1.2) & face & ~cut, nb(PAL['wood'], 0.22, 0))
    hx, hy = F.at(0.0, -9.9)                                          # the thong's hole, and the loop of leather through it
    cv.put(D(hx, hy, 0.9), nb(PAL['wood'], 0.02, 0))
    lx, ly = F.at(0.0, -14.4)
    lp = (E(lx, ly, 2.5, 2.5) & ~E(lx, ly, 1.2, 1.2)) | path([F.at(0, -11.5), F.at(0, -13.5)], 1.5)[0]
    cel(cv, lp & ~stick, PAL['leather'], 0.6, 0.25, 0.25, 1, 1)
    gx, gy = F.at(-2.2, 9.6)
    return finish(cv, [(int(gx), int(gy), 1, (255, 232, 190))])


@painter('keep:informer_c')
def keep_informer_c():
    """A chalk stub, snapped and worn down, a streak of its dust trailing from the worn tip."""
    cv = Canvas(N)
    F = Frame(18.0, 13.6, 40)
    stub = F.box(-4.2, -6.4, 4.2, 6.4)
    stub &= ~F.poly([(-5.0, -7.4), (5.0, -7.4), (5.0, -4.9), (2.4, -6.1), (0.2, -4.4), (-2.0, -6.4), (-5.0, -5.0)])      # the snapped end, ragged
    stub &= ~F.poly([(2.6, 6.8), (5.0, 6.8), (5.0, 4.6)]) & ~F.poly([(-2.6, 6.8), (-5.0, 6.8), (-5.0, 4.6)])             # the worn tip, rounded
    un = F.u / 4.2
    cv.put(stub, nb(PAL['bone'], np.where(un < -0.35, 1.0, np.where(un < 0.45, 0.82, 0.56)), 0))
    cv.put(stub & (F.v > 5.2), nb(PAL['bone'], 1.0, 0))                                        # the worn, rounded tip, bright
    cv.put(stub & (F.v < -4.0) & (F.v > -5.8) & (vnoise(N, N, 1.6, 4) > 0.45), nb(PAL['bone'], 0.5, 0))
    img = finish(cv, [], rim_k=0.15)
    # the streak it left: chalk dragged beyond the tip, fading out, and some dust; no outline, so laid under the stub
    dust = Canvas(N)
    tx, ty = F.at(0.4, 7.0)
    for i in range(1, 15):
        f = i / 14.0
        px_, py_ = tx - 0.9 * i, ty + 0.52 * i
        w = 2.2 * (1 - 0.6 * f)
        for dy in (-1, 0, 1):
            x, y = int(round(px_)), int(round(py_)) + dy
            if abs(dy) <= w / 2 + 0.3 and 0 <= x < N and 0 <= y < N and BAYER[y % 4, x % 4] + 0.5 < (1 - f) * 1.05:
                dust.px(x, y, np.asarray(PAL['bone'][5], float), 1.0)
    for x, y in ((24, 21), (27, 17), (22, 25), (6, 6), (4, 11), (10, 4)):
        dust.px(x, y, np.asarray(PAL['bone'][4], float), 0.85)
    base = dust.image()
    base.alpha_composite(img)
    return base


@painter('keep:locket')
def keep_locket():
    """A blue hair ribbon, tied in a bow, its edges worn grey."""
    cv = Canvas(N)
    B = GEMS['blue']
    # two tails, swallow-tailed
    for sgn in (-1, 1):
        tail = P([(16 + sgn * 0.8, 14.5), (16 + sgn * 4.2, 14.5), (16 + sgn * 8.0, 26.5), (16 + sgn * 5.6, 24.4), (16 + sgn * 3.4, 27.6)])
        part(cv, tail, B, 0.46, edge=0.3, dither=0)
        cv.put(tail & (L(16 + sgn * 2.4, 15, 16 + sgn * 5.4, 25.5, 0.9)), nb(B, 0.72, 0))        # the satin's sheen
    # two loops, hollow, the inside of the ribbon darker
    for sgn in (-1, 1):
        cx = 16 + sgn * 6.6
        outer = E(cx, 11.6, 6.8, 5.4)
        part(cv, outer, B, 0.64, relief=1, k=0.7, edge=0.3, dither=0)
        inner = E(cx - sgn * 0.4, 11.8, 3.8, 2.6)
        cv.put(inner, nb(B, 0.16 - 0.1 * sgn * ((XS - cx) / 3.8), 0))
        cv.put(outer & (YS < 9.2) & (np.abs(XS - cx) < 4.5), nb(B, 0.86, 0))
    knot = R(13, 8.6, 19, 15.4) & ~P([(13, 8.6), (13.6, 8.6), (13, 9.4)])
    part(cv, knot, B, 0.54, relief=1, k=0.6, edge=0.35, dither=0)
    cv.put(R(15, 9, 16, 15) & knot, nb(B, 0.8, 0))
    cv.put(R(18, 9, 19, 15) & knot, nb(B, 0.2, 0))
    # the worn edge: slate grey where hands have rubbed it
    solid = cv.solid
    worn = edge(solid) & (vnoise(N, N, 2.0, 61) > 0.5) & (YS > 12)
    cv.put(worn, nb(PAL['slate'], 0.6, 0))
    return finish(cv, [(8, 8, 1, (220, 236, 255))], rim_k=0.2)


@painter('keep:locket_c')
def keep_locket_c():
    """A plain ribbon: undyed linen, looped once and crossed over itself, the ends frayed."""
    cv = Canvas(N)
    down = [(9.5, 28.0), (13.5, 21.5), (16.4, 16.4), (20.6, 10.4), (21.0, 5.4), (16.4, 3.6)]                  # up the right side of the loop
    over = [(16.4, 3.6), (11.4, 5.8), (10.8, 11.0), (14.2, 16.6), (16.6, 21.6), (22.6, 28.0)]                  # down the left, across, and out
    for k, pts in enumerate((down, over)):
        d, run = path_dist(pts, N)
        m = d < 2.4
        if k == 1:
            cv.put(grow(m) & ~m & cv.solid, nb(LINEN, 0.04, 0))                               # the strand on top, a dark line where it crosses
        t = np.where(d < 0.9, 0.82, 0.68) + 0.08 * weave(m, 1) - 0.0
        cel(cv, m, LINEN, 0.7, 0.28, 0.26, 1, 1, extra=0.06 * weave(m, 1))
        cv.put(m & (np.abs(d - 1.4) < 0.36) & (np.mod(run, 2.4) < 1.4) & (d > 0.9), nb(LINEN, 0.46, 0))    # a stitched line along it
    hole = D(16.4, 10.2, 2.4)
    cv.a[hole & ~cv.solid] = 0
    for x0, y0, x1, y1 in ((9.5, 28.0, 8.6, 30.6), (10.8, 28.0, 11.0, 30.8), (22.6, 28.0, 23.6, 30.6), (21.4, 28.0, 21.2, 30.8)):
        dd, _ = seg_dist(x0, y0, x1, y1, N)
        cv.put(dd < 0.5, nb(LINEN, 0.6, 0))
    return finish(cv, [(14, 7, 1, (255, 250, 232))], rim_k=0.2)


@painter('keep:brother')
def keep_brother():
    return miners_lamp(lit=False, wear=True)


@painter('keep:brother_c')
def keep_brother_c():
    """A candle stub in a puddle of its own wax: beeswax, a thread of ink for a wick, the drips run down one side."""
    cv = Canvas(N)
    B = PAL['bone']
    W = PAL['parchment']
    puddle = E(16, 26.0, 8.6, 2.5) | E(10.4, 25.6, 3.6, 2.0) | E(22.4, 26.4, 3.8, 2.1) | E(13.5, 27.6, 4.6, 1.5) | E(19.5, 27.8, 3.8, 1.3)
    cel(cv, puddle, W, 0.78, 0.2, 0.3, 1, 1)
    cv.put(E(14, 25.8, 4.2, 0.9) & puddle, nb(W, 1.0, 0))                                   # the wet gleam on the flat of it
    stub = R(13, 11, 20, 26)
    drips = P([(19.0, 12), (21.6, 14), (21.6, 21.2), (20.4, 23.0), (19.0, 20.5)]) | P([(13.0, 17), (11.6, 19.0), (11.6, 23.2), (13.0, 24.6)])
    body = stub | drips
    u = (XS - 16.4) / 3.8
    cv.put(body, nb(B, np.where(u < -0.45, 1.0, np.where(u < 0.5, 0.82, 0.56)), 0))
    cv.put(body & (np.abs(u + 0.62) < 0.17) & (YS > 13) & (YS < 24), nb(B, 1.0, 0))
    cv.put(grow(drips) & ~drips & stub & (XS > 18), nb(B, 0.4, 0))                              # the drip's edge against the candle
    cv.put(R(13, 25, 20, 26), nb(B, 0.3, 0))                                                    # where it meets the puddle
    mouth = E(16.4, 11.6, 3.9, 1.8)
    cv.put(mouth, nb(B, 0.96, 0))
    cv.put(E(16.8, 11.9, 2.6, 1.1), nb(B, 0.36, 0))                                            # the melted pool in the top
    cv.put(R(16, 6, 17, 12), INK[0])                                                          # the wick, bent at its tip
    cv.px(17, 6, INK[0])
    cv.px(17, 5, INK[1])
    return finish(cv, [(14, 14, 1, (255, 248, 226))], rim_k=0.2)


def miners_lamp(lit, wear, frame=0):
    """The small miner's lamp: an iron font with a brass collar, a glass chimney in a cage of iron wire, a cap and a brass hook.
    Dark, or lit: a flame in the chimney (its tip and lean change with [frame]), the glass glowing round it, a halo outside."""
    cv = Canvas(N)
    hook, _, _ = path([(16, 10.5), (16, 6.0), (17.2, 3.4), (19.8, 2.6), (21.6, 4.4), (20.8, 6.8)], 1.9)
    part(cv, hook, PAL['brass'], 0.66, edge=0.3, dither=0)
    cap = P([(11, 9.8), (21, 9.8), (23, 12.6), (9, 12.6)])
    part(cv, cap, PAL['iron'], 0.6, edge=0.3, dither=0)
    chimney = R(11, 12, 21, 22)
    u = (XS - 16) / 5.0
    if lit:
        tip = (15.4, 16.2, 15.0, 15.8, 14.8, 16.0)[frame % 6]
        sway = (0.0, 0.5, -0.4, 0.3, -0.5, 0.2)[frame % 6]
        glow = np.clip(1 - np.hypot((XS - 16) / 6.4, (YS - 19.0) / 7.6), 0, 1)
        cv.put(chimney, nb(PAL['draught'], 0.2 + 0.85 * glow, 0.2))
        wy = np.clip((YS - tip) / (21.6 - tip), 0, 1)
        half = 2.4 * np.sin(wy * math.pi * 0.5) ** 0.8
        fx = 16 + sway * (1 - wy)
        fl = (YS >= tip) & (YS <= 21.6) & (np.abs(XS - fx) <= half) & chimney
        ft = 0.96 - 0.6 * (np.abs(XS - fx) / np.maximum(half, 0.4)) - 0.2 * (1 - wy)
        cv.put(fl, nb(FLAME, ft, 0))
        cv.put(fl & (wy > 0.45) & (np.abs(XS - fx) < 0.8), (255, 248, 222))
        cv.put(chimney & (np.abs(XS - 13.0) < 0.55) & (YS > 13) & (YS < 21), nb(PAL['draught'], 1.0, 0))
        cv.put(R(15, 21, 17, 22), INK[0])
    else:
        cv.put(chimney, nb(PAL['glass'], 0.4 - 0.2 * u, 0.2))
        cv.put(chimney & (np.abs(XS - 13.0) < 0.55) & (YS > 13) & (YS < 21), nb(PAL['glass'], 1.0, 0))           # the glint on the glass
        cv.put(chimney & (np.abs(XS - 19.4) < 0.4) & (YS > 14) & (YS < 20), nb(PAL['glass'], 0.62, 0))
        cv.put(chimney & (YS < 15.0), nb(PAL['glass'], 0.1 + 0.05 * (YS - 12), 0))                              # soot along the top
        cv.put(R(15, 18, 16, 22), INK[0])                                                                       # the cold wick
    for x in (11, 20):                                                                                           # the cage: two posts, two rings
        cv.put(R(x, 12, x + 1, 22), nb(PAL['iron'], 0.62 - 0.2 * (x > 16), 0))
    for y in (15, 19):
        cv.put(R(11, y, 21, y + 1), nb(PAL['iron'], 0.58, 0))
    base = P([(9.2, 22), (22.8, 22), (24.4, 25), (24.0, 28.6), (8.0, 28.6), (7.6, 25)])
    part(cv, base, PAL['iron'], 0.58, relief=1, k=0.7, edge=0.3, dither=0.1)
    cv.put(R(9, 22, 23, 24) & base, nb(PAL['brass'], 0.66 + 0.2 * bevel(R(9, 22, 23, 24)), 0))                 # the brass collar
    cv.put(D(16, 26.2, 1.7), nb(PAL['brass'], 0.7, 0))
    cv.put(D(16, 26.2, 0.7), nb(PAL['brass'], 0.3, 0))
    if wear:
        for x, y in ((10, 25), (12, 27), (20, 27), (22, 25)):
            cv.px(x, y, PAL['iron'][4])
        cv.put(R(9, 26, 11, 27), nb(PAL['iron'], 0.9, 0))                                                        # a scrape
        cv.put(R(21, 25, 22, 28), nb(PAL['iron'], 0.18, 0))                                                      # a dent's shadow
    img = finish(cv, [] if lit else [(8, 24, 1, (255, 240, 210))], rim_k=0.25)
    if not lit:
        return img
    halo = Canvas(N)
    dist = np.hypot((XS - 16) / 13.0, (YS - 17.5) / 14.0)
    ha = np.clip((1 - dist) * 1.1, 0, 1) ** 1.5 * (0.46 + 0.07 * (frame % 3))
    halo.put(dist < 1, (255, 190, 100), ha)
    under = halo.image()
    under.alpha_composite(img)
    return under


# ================================================================ the carried props (13.2.3)


ANIM = {}                      # painter key -> how many frames its strip has (the others are one still)


def animated(key, frames):
    def deco(fn):
        ANIM[key] = frames
        return fn
    return deco


@painter('prop:note_sealed')
def prop_note_sealed():
    """A letter folded shut: a cream paper, its flap turned down to a point and sealed with a whole red seal."""
    cv = Canvas(N)
    P_ = PAL['parchment']
    body = R(4, 7, 28, 27)
    cel(cv, body, P_, 0.76, 0.2, 0.3, 1, 2)
    flap = P([(4, 7), (28, 7), (16, 20.5)])
    cv.put(flap, nb(P_, 0.94, 0))
    cv.put(flap & ~shift(flap, 0, 1), nb(P_, 1.0, 0))                                     # the top edge catches the light
    left = L(4, 7, 16, 20.5, 1.2) & body
    right = L(28, 7, 16, 20.5, 1.2) & body
    cv.put((left | right) & ~R(0, 0, 32, 7), nb(P_, 0.34, 0))                              # the flap's two lower edges
    under = shift(left | right, 0, 1) & body & ~flap
    cv.put(under, nb(P_, 0.5, 0))
    seal(cv, 16, 20.0, 4.6, 5, crack=False)
    return finish(cv, [(14, 18, 1, (255, 214, 190))], rim_k=0.2)


@painter('prop:ransom_pouch')
def prop_ransom_pouch():
    """A small leather pouch, heavy at the bottom, its neck gathered and tied with a cord and a bow."""
    cv = Canvas(N)
    Lh = PAL['leather']
    body = E(16, 20.2, 10.8, 8.6)
    neck = P([(11.4, 12.0), (20.6, 12.0), (22.6, 16.5), (9.4, 16.5)])
    frill = P([(9.4, 8.4), (11.0, 6.2), (13.0, 7.6), (14.8, 5.6), (16.6, 7.4), (18.4, 5.6), (20.2, 7.6), (21.8, 6.2), (22.8, 8.4), (21.0, 12.4), (11.0, 12.4)])
    whole = body | neck | frill
    part(cv, whole, Lh, 0.62, relief=2, k=0.9, edge=0.3, dither=0)
    cv.put(frill & ((np.abs(XS - 12.8) < 0.5) | (np.abs(XS - 16.0) < 0.5) | (np.abs(XS - 19.2) < 0.5)) & (YS > 8), nb(Lh, 0.3, 0))    # the gathers
    cv.put(body & (np.abs(XS - 16) < 0.5) & (YS > 22), nb(Lh, 0.34, 0))                                                               # the seam
    cord = R(10, 12, 22, 14)
    cel(cv, cord, PAL['cork'], 0.86, 0.2, 0.3, 1, 1)
    pix(cv, ['.dd...dd.',
             'dmmdkdmmd',
             '.dmmkmmd.',
             '..ddkdd..',
             '..dm.md..',
             '.dmd.dmd.',
             '.dd...dd.'], 11, 11, {'d': PAL['cork'][1], 'm': PAL['cork'][4], 'k': PAL['cork'][2]})
    return finish(cv, [(11, 17, 1, (255, 226, 190))], rim_k=0.2)


@painter('prop:slate')
def prop_slate():
    """The sergeant's slate: a small wooden frame round a dark slate, a chalk line ruled across it and a few tallies."""
    cv = Canvas(N)
    fr = R(3, 5, 29, 28) & ~R(3, 5, 4, 6) & ~R(28, 5, 29, 6) & ~R(3, 27, 4, 28) & ~R(28, 27, 29, 28)
    cel(cv, fr, PAL['wood'], 0.7, 0.28, 0.3, 1, 2)
    inner = R(6, 8, 26, 25)
    cv.put(inner, nb(PAL['slate'], 0.5 - 0.14 * bevel(inner), 0))
    cv.put(inner & ((XS - 6) + (YS - 8) < 10) & ((XS - 6) + (YS - 8) > 5), nb(PAL['slate'], 0.66, 0))        # the glare across a corner
    chalk = PAL['bone'][5]
    cv.put(R(8, 13, 24, 14) & (np.mod(XS, 7) != 3), chalk)                                                       # the ruled line, a break or two
    for x in (9, 12, 15, 18):
        cv.put(R(x, 16, x + 1, 21), chalk)                                                                       # four tallies
    cv.put(L(8.0, 21.0, 19.6, 16.0, 1.1) & inner, chalk)                                                         # and the stroke through
    cv.put(R(22, 17, 24, 18) | R(22, 19, 24, 20) | R(22, 21, 24, 22), PAL['bone'][3])                           # a column of figures, faint
    cv.over(inner & (vnoise(N, N, 2.4, 6) > 0.74) & (YS > 20), nb(PAL['slate'], 0.7, 0), 0.5)                  # chalk dust smeared on the slate
    cv.put(R(28, 10, 29, 14), nb(PAL['wood'], 0.2, 0))
    return finish(cv, [(5, 6, 1, (255, 232, 190))], rim_k=0.2)


@painter('prop:thread_paper')
def prop_thread_paper():
    """A folded paper, tied across both ways with a black thread and knotted in the middle."""
    cv = Canvas(N)
    P_ = PAL['parchment']
    paper = R(5, 5, 27, 27) & ~R(5, 5, 6, 6) & ~R(26, 5, 27, 6) & ~R(5, 26, 6, 27) & ~R(26, 26, 27, 27)
    cel(cv, paper, P_, 0.86, 0.2, 0.3, 1, 2)
    cv.put(paper & (np.abs(XS - 16) < 0.5) & (np.abs(YS - 16) > 0.5), nb(P_, 0.5, 0))                           # the folds in quarters
    cv.put(paper & (np.abs(YS - 16) < 0.5), nb(P_, 0.5, 0))
    th = (np.abs(XS - 16) < 1.0) | (np.abs(YS - 16) < 1.0)
    cv.put(paper & th, nb(SOOT, 0.3, 0))
    cv.put(paper & th & (np.abs(XS - 15.4) < 0.6) & (YS < 16), nb(SOOT, 0.7, 0))
    cv.put(R(14, 14, 19, 19), nb(SOOT, 0.4, 0))                                                                # the knot
    cv.put(R(14, 14, 16, 16), nb(SOOT, 0.9, 0))
    for pts in (((17, 18), (21, 22), (23, 24.5)), ((18, 17), (22, 19), (24.5, 19.5))):                         # the two ends
        cv.put(path(list(pts), 1.1)[0], nb(SOOT, 0.4, 0))
    return finish(cv, [(8, 8, 1, (255, 240, 214))], rim_k=0.2)


@painter('prop:locket')
def prop_locket():
    """A small oval gold locket, an engraved flower on its lid, on a short chain."""
    cv = Canvas(N)
    G = PAL['gold']
    chain = E(16, 9.0, 8.2, 7.4) & ~E(16, 9.0, 6.6, 5.8)
    links = chain & (np.mod(np.floor(np.arctan2(YS - 9.0, XS - 16) * 4.0), 2) == 0)
    cv.put(chain, nb(G, 0.46, 0))
    cv.put(links, nb(G, 0.86, 0))
    locket = E(16, 20.0, 8.0, 9.6)
    h = np.sqrt(np.clip(1 - ((XS - 16) / 8.4) ** 2 - ((YS - 20) / 10.0) ** 2, 0, 1))
    cv.put(grow(locket) & ~locket & chain, nb(G, 0.08, 0))
    cv.put(locket, nb(G, 0.62 + 0.8 * light(h * 2.4, 1.0) + 0.1 * h, 0))
    face = E(16, 20.0, 5.6, 7.4)
    cv.put(face & ~shift(face, 1, 1), nb(G, 0.34, 0))                                           # the lid's rim, a step down
    cv.put(face & ~shift(face, -1, -1) & ~(face & ~shift(face, 1, 1)), nb(G, 0.9, 0))
    flower = ['..a.a..',
              '.aabaa.',
              'aabbbaa',
              '.aabaa.',
              '..a.a..']
    pix(cv, flower, 13, 17, {'a': G[2], 'b': G[1]})
    cv.put(R(15, 13, 17, 15), nb(G, 0.4, 0))                                                    # the bail the chain runs through
    cv.put(R(15, 13, 17, 14), nb(G, 0.9, 0))
    cv.put(R(15, 28, 17, 29), nb(G, 0.2, 0))
    return finish(cv, [(12, 14, 2, (255, 248, 220))], rim_k=0.2)


@painter('prop:blanket')
def prop_blanket():
    """A grey-blue wool blanket folded in three, a cream stripe near the end, a fringe."""
    cv = Canvas(N)
    slabs = ((20, 27, 5, 26, 0.5), (14, 21, 4, 27, 0.64), (8, 15, 5, 26, 0.8))
    for k, (y0, y1, x0, x1, base) in enumerate(slabs):
        slab = R(x0, y0, x1, y1) | E(x1, (y0 + y1) / 2.0, 2.8, (y1 - y0) / 2.0) | E(x0, (y0 + y1) / 2.0, 1.2, (y1 - y0) / 2.0)
        cel(cv, slab, WOOL, base, 0.22, 0.3, 1, 1)
        cv.put(slab & (YS % 2 == 0) & (YS > y0 + 1) & (YS < y1 - 1), nb(WOOL, base - 0.07, 0))       # the weave's rows
        cv.put(slab & ~shift(slab, 0, 1) & (XS > x0 + 1), nb(WOOL, base + 0.28, 0))               # the lit fold along the top
    top = R(5, 8, 26, 15)
    cv.put(R(9, 9, 11, 15) & top, nb(WOOL, 0.18, 0))
    cv.put(R(11, 9, 14, 15) & top, nb(LINEN, 0.9, 0))
    cv.put(R(14, 9, 16, 15) & top, nb(WOOL, 0.18, 0))
    for y in (9, 11, 13):                                                  # the fringe at the near end
        cv.put(R(2, y, 5, y + 1), nb(LINEN, 0.78, 0))
    return finish(cv, [(8, 9, 1, (230, 240, 250))], rim_k=0.2)


@painter('prop:washing')
def prop_washing():
    """A bundle of linen washing, knotted up in its own cloth: a soft lump, the cloth gathered into a knot with two ears."""
    cv = Canvas(N)
    lump = E(16, 19.6, 11.6, 8.4) | E(16, 15.0, 8.0, 5.6)
    ears = P([(15.2, 11.0), (8.4, 5.6), (7.0, 9.4), (12.6, 12.6)]) | P([(16.8, 11.0), (23.6, 4.8), (25.0, 8.8), (19.4, 12.6)])
    whole = lump | ears
    part(cv, whole, LINEN, 0.76, relief=2, k=0.9, edge=0.3, dither=0)
    for pts in (((14.6, 13.0), (12.0, 16.0), (10.6, 19.0)), ((17.4, 13.0), (20.0, 16.0), (21.6, 19.0)), ((8.0, 24.0), (12.0, 25.4), (15.0, 25.6)), ((17.0, 25.6), (21.0, 25.0), (24.6, 23.4))):
        cv.put(path(list(pts), 1.0)[0] & lump, nb(LINEN, 0.4, 0))                            # the cloth drawn in toward the knot, creased below
    cv.put(ears & ((XS < 14.4) | (XS > 17.6)) & (np.abs(YS - 8.5) < 0.5), nb(LINEN, 0.4, 0))
    knot = E(16, 11.6, 3.6, 2.6)
    part(cv, knot, LINEN, 0.66, relief=1, k=1.0, edge=0.3, dither=0)
    cv.put(knot & (np.abs(XS - 16) < 0.5), nb(LINEN, 0.32, 0))
    cv.put(E(11.4, 18.4, 3.6, 2.0) & ~shift(E(11.4, 18.4, 3.6, 2.0), 1, 1), nb(LINEN, 1.0, 0))    # the lit rise of the lump
    return finish(cv, [(10, 15, 1, (255, 250, 236))], rim_k=0.2)


@painter('prop:posy')
def prop_posy():
    """A small bunch of flowers, stems bound with thread."""
    cv = Canvas(N)
    for x0, y0 in ((9.5, 12.0), (16.0, 7.0), (22.5, 12.0), (12.5, 16.0), (19.5, 16.0)):
        d, _ = seg_dist(x0, y0, 16.0, 24.0, N)
        cv.put(d < 0.6, nb(PAL['moss'], 0.5, 0))
    cv.put(P([(14.0, 24.0), (18.0, 24.0), (18.6, 29.6), (13.4, 29.6)]), nb(PAL['moss'], 0.44, 0))       # the stems, cut square below the tie
    for cx, cy, a in ((11.0, 20.0, -0.6), (21.0, 20.0, 0.6)):                                             # two leaves
        lf = np.hypot(((XS - cx) * math.cos(a) + (YS - cy) * math.sin(a)) / 3.2, (-(XS - cx) * math.sin(a) + (YS - cy) * math.cos(a)) / 1.4) <= 1
        part(cv, lf, PAL['moss'], 0.74, edge=0.25, dither=0)
    blossom(cv, 16.0, 6.8, 3.8, PETAL_GOLD, PAL['moss'][3], 6, 0.3)
    blossom(cv, 9.2, 12.2, 3.6, PETAL_WHITE, PAL['gold'][3], 5, 0.5)
    blossom(cv, 22.8, 12.2, 3.6, PETAL_BLUE, PAL['gold'][4], 5, 0.1)
    blossom(cv, 12.4, 16.8, 3.1, PAL['crimson'], PAL['gold'][3], 5, 0.9)
    blossom(cv, 19.8, 16.8, 3.1, PETAL_WHITE, PAL['crimson'][3], 5, 0.2)
    tie = R(13, 23, 19, 25)
    cel(cv, tie, PAL['cork'], 0.8, 0.2, 0.3, 1, 1)
    pix(cv, ['.dd.dd.', 'dmmkmmd', '.dmkmd.', '..dkd..', '.dm.md.'], 13, 22, {'d': PAL['cork'][1], 'm': PAL['cork'][4], 'k': PAL['cork'][2]})
    return finish(cv, [(7, 10, 1, (255, 250, 236))], rim_k=0.2)


def lantern_prop(lit, frame=0):
    """A round paper lantern with a wooden cap at each end and a wire handle; dark, or lit from inside by a candle whose
    glow breathes frame by frame."""
    cv = Canvas(N)
    cx, cy, rx, ry = 16.0, 17.4, 11.0, 9.2
    body = E(cx, cy, rx, ry)
    u = (XS - cx) / rx
    hwy = rx * np.sqrt(np.clip(1 - ((YS - cy) / ry) ** 2, 0, 1))
    un = (XS - cx) / np.maximum(hwy, 0.5)
    if not lit:
        hb = PAL['parchment_dark']
        cv.put(body, nb(hb, np.where(un < -0.45, 0.9, np.where(un < 0.35, 0.68, 0.44)), 0))
        rib_t = 0.14
    else:
        b = (1.0, 0.92, 1.06, 0.88, 1.0, 0.96)[frame % 6]
        sway = (0.0, 0.4, -0.3, 0.3, -0.4, 0.1)[frame % 6]
        r = np.hypot((XS - cx - sway) / rx, (YS - cy - 0.8) / ry)
        glow = np.clip(1 - r, 0, 1)
        t = 0.34 + 0.9 * glow ** 0.8 * b
        core = np.hypot((XS - cx - sway) / 3.0, (YS - cy - 0.6) / (3.4 * (0.96 + 0.08 * b)))
        cv.put(body, nb(PAL['draught'], t, 0.2))
        cv.put(body & (core < 1.0), nb(PAL['draught'], 1.0, 0))
        cv.put(body & (core < 0.55), (255, 246, 214))
        rib_t = 0.2
    # the ribs: five curves from cap to cap
    for s in (-0.78, -0.4, 0.0, 0.4, 0.78):
        rib = body & (np.abs(XS - (cx + s * hwy)) < 0.55)
        cv.put(rib, nb(PAL['draught'] if lit else PAL['parchment_dark'], rib_t, 0))
    for cap in (R(11, 6, 21, 9), R(11, 26, 21, 29)):
        cel(cv, cap, PAL['darkwood'], 0.78, 0.3, 0.3, 1, 1)
    handle = E(cx, 4.4, 5.0, 3.0) & ~E(cx, 4.8, 3.6, 1.7) & (YS < 7)
    cv.put(handle, nb(PAL['iron'], 0.7, 0))
    img = finish(cv, [] if lit else [(9, 12, 1, (240, 230, 214))], rim_k=0.2)
    if not lit:
        return img
    # a halo of warm light round it, laid under the lantern so it takes no outline
    halo = Canvas(N)
    dist = np.hypot((XS - cx) / (rx + 5.0), (YS - cy) / (ry + 5.0))
    ha = np.clip((1 - dist) * 1.1, 0, 1) ** 1.4 * (0.5 + 0.1 * (frame % 3))
    halo.put(dist < 1, (255, 190, 100), ha)
    base = halo.image()
    base.alpha_composite(img)
    return base


@painter('prop:lantern_paper_dark')
def prop_lantern_dark():
    return lantern_prop(False)


@painter('prop:lantern_paper_lit')
@animated('prop:lantern_paper_lit', 6)
def prop_lantern_lit(frame=0):
    return lantern_prop(True, frame)


@painter('prop:miners_lamp_dark')
def prop_miners_lamp_dark():
    return miners_lamp(lit=False, wear=False)


@painter('prop:miners_lamp_lit')
@animated('prop:miners_lamp_lit', 6)
def prop_miners_lamp_lit(frame=0):
    return miners_lamp(lit=True, wear=False, frame=frame)


# ================================================================ the quill (13.2.4)


@painter('quill:tale_quill')
def tale_quill():
    """The quill: a long feather, its vane cream turning to gold at the tip, a bare barrel and a dark nib with a bead of ink."""
    cv = Canvas(N)
    F = Frame(16.0, 16.0, 45)
    u, v = F.u, F.v
    bend = 0.012 * (v + 4.0) ** 2 * (v < 4.0)                     # the feather bows a little outward toward its tip
    uc = u + bend - 0.6
    wy = np.clip((v + 17.5) / 21.5, 0, 1)
    prof = np.sin(np.clip(wy ** 0.8, 0, 1) * math.pi) ** 0.7
    half_l = 5.4 * prof
    half_r = 4.5 * prof
    half = np.where(uc < 0, half_l, half_r)
    vane = (v >= -17.5) & (v <= 4.0) & (np.abs(uc) <= half) & (np.abs(uc) > 0.55)
    vane &= ~F.poly([(3.6, -9.6), (0.6, -8.6), (3.8, -6.6)])                                              # the splits a real feather has
    vane &= ~F.poly([(-5.4, -4.2), (-1.8, -3.2), (-5.4, -1.4)])
    barb = np.mod(v * 1.0 + np.abs(uc) * 1.6, 2.7) < 1.1                                                  # the barbs, swept back from the shaft
    tip_gold = np.clip((-v - 11.5) / 4.0, 0, 1)
    base_t = np.where(uc < 0, 0.9, 0.7) - 0.14 * barb
    cv.put(vane, nb(PAL['parchment'], base_t, 0))
    edge_gold = (np.abs(uc) > half - 0.9) & (np.abs(uc) > 1.5) & (v < 1.0)
    gold = vane & ((tip_gold > 0.3) | edge_gold)
    cv.put(gold, nb(PAL['gold'], np.where(uc < 0, 0.92, 0.7) - 0.16 * barb + 0.1 * tip_gold, 0))
    shaft = (np.abs(uc) <= 0.6) & (v >= -18.0) & (v <= 11.0)
    cv.put(shaft, nb(PAL['parchment'], np.where(uc < 0, 1.0, 0.62), 0))                                     # the rachis, then the bare barrel
    barrel = (np.abs(u) <= 1.1) & (v > 4.0) & (v <= 11.0)
    cv.put(barrel, nb(PAL['gold'], np.where(u < 0, 0.96, 0.6), 0))
    cv.put(F.box(-1.7, 10.6, 1.7, 11.8), nb(PAL['gold'], 0.5, 0))                                         # the collar
    nib = F.poly([(-1.4, 11.8), (1.4, 11.8), (0.6, 15.4), (0.0, 16.6), (-0.6, 15.4)])
    cv.put(nib, nb(PAL['iron'], np.where(u < 0, 0.5, 0.26), 0))
    cv.put(nib & (np.abs(u) < 0.3) & (v > 13), nb(PAL['iron'], 0.04, 0))                                    # the slit in the nib
    bx, by = F.at(0.0, 17.8)
    cv.put(D(bx, by, 1.1), nb(INK, 0.1, 0))
    gx, gy = F.at(-2.4, -10.0)
    return finish(cv, [(int(gx), int(gy), 1, (255, 250, 232))], rim_k=0.25)


# ================================================================ the mote (13.2.4)


# per frame: the solid core's radius, the halo's, the halo's strength, how far the four-point twinkle reaches
MOTE = ((1.5, 3.6, 0.55, 3.6), (1.3, 3.2, 0.45, 2.4), (1.0, 2.6, 0.35, 0.0), (0.7, 1.9, 0.25, 0.0))


def mote_frame(core, halo, strength, arm):
    """One 8 x 8 frame of the mote: near white with a soft warm halo, and in the first frames a faint four-point twinkle. The
    particle tints it gold and fades it, as the forge spark's is."""
    img = Image.new('RGBA', (8, 8), (0, 0, 0, 0))
    px_ = img.load()
    c = 3.5
    for y in range(8):
        for x in range(8):
            dx, dy = x - c, y - c
            d = math.hypot(dx, dy)
            if d <= core:
                a = 1.0
            elif d <= halo:
                a = strength * (1.0 - (d - core) / (halo - core)) ** 2
            else:
                a = 0.0
            if arm and (abs(dx) < 0.8 or abs(dy) < 0.8):
                reach = max(abs(dx), abs(dy))
                if reach <= arm:
                    a = max(a, 0.6 * (1.0 - reach / (arm + 0.6)))
            if a <= 0.01:
                continue
            warm = 0 if d <= core else int(40 * min(1.0, (d - core) / max(halo - core, 0.1)))
            px_[x, y] = (255, 255 - warm // 2, 255 - warm, int(round(255 * min(a, 1.0))))
    return img


MOTES = [mote_frame(*m) for m in MOTE]


# ================================================================ the files


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def plain_model(texture):
    return {'parent': 'minecraft:item/generated', 'textures': {'layer0': texture}}


def one_model(name):
    return {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}}


def select_definition(cases, fallback):
    """An item definition that picks its model by the stack's custom model data string, as `items/heist_prize.json` does."""
    return {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:custom_model_data',
        'cases': [{'when': look, 'model': {'type': 'minecraft:model', 'model': model}} for look, model in cases],
        'fallback': {'type': 'minecraft:model', 'model': fallback}}}


# What each animated strip does: the frames it plays, in order, and how long each is held, in ticks
MCMETA = {
    'prop:lantern_paper_lit': {'animation': {'frametime': 3, 'frames': [0, 1, 2, 3, 4, 5, 3, 1]}},
    'prop:miners_lamp_lit': {'animation': {'frametime': 2, 'frames': [0, 1, 2, 3, 4, 5, 3, 1]}},
}


def frames_of(key):
    """The painter's frames: one still, or the whole run of an animated one."""
    if key in ANIM:
        return [PAINTERS[key](frame=i) for i in range(ANIM[key])]
    return [PAINTERS[key]()]


def strip_of(frames):
    out = Image.new('RGBA', (N, N * len(frames)), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        out.paste(f, (0, i * N))
    return out


def write_items(assets):
    """Paint every item and write its texture, model and item definition; returns {name: [frames]} in the order listed."""
    made = {}
    models = os.path.join(assets, 'models', 'item')
    defs = os.path.join(assets, 'items')

    def texture(key, img):
        path = os.path.join(assets, 'textures', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path, optimize=True)

    for look in KEEPSAKES:
        made['keepsake/' + look] = frames = frames_of('keep:' + look)
        texture('item/keepsake/' + look, strip_of(frames) if len(frames) > 1 else frames[0])
        write_json(os.path.join(models, 'keepsake', look + '.json'), plain_model('guildmark:item/keepsake/' + look))
    write_json(os.path.join(defs, 'keepsake.json'), select_definition(
        [(look, 'guildmark:item/keepsake/' + look) for look in KEEPSAKES], 'guildmark:item/contract'))
    for look in PROPS:
        key = 'prop:' + look
        made['tale_prop/' + look] = frames = frames_of(key)
        texture('item/tale_prop/' + look, strip_of(frames) if len(frames) > 1 else frames[0])
        if key in MCMETA:
            write_json(os.path.join(assets, 'textures', 'item', 'tale_prop', look + '.png.mcmeta'), MCMETA[key])
        write_json(os.path.join(models, 'tale_prop', look + '.json'), plain_model('guildmark:item/tale_prop/' + look))
    write_json(os.path.join(defs, 'tale_prop.json'), select_definition(
        [(look, 'guildmark:item/tale_prop/' + look) for look in PROPS], 'guildmark:item/tale_prop/' + PROPS[0]))
    made['tale_quill'] = [PAINTERS['quill:tale_quill']()]
    texture('item/tale_quill', made['tale_quill'][0])
    write_json(os.path.join(models, 'tale_quill.json'), plain_model('guildmark:item/tale_quill'))
    write_json(os.path.join(defs, 'tale_quill.json'), one_model('tale_quill'))
    names = []
    for i, m in enumerate(MOTES):
        path = os.path.join(assets, 'textures', 'particle', 'tale_mote_%d.png' % i)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        m.save(path, optimize=True)
        names.append('guildmark:tale_mote_%d' % i)
    write_json(os.path.join(assets, 'particles', 'tale_mote.json'), {'textures': names})
    return made


def verify(assets, made):
    """Every case of the three item definitions has a model, every model its texture, and no texture has a fully saturated
    pixel. Returns a list of what is wrong (empty when all is well)."""
    problems = []

    def exists(rel):
        return os.path.exists(os.path.join(assets, rel))

    def check_model(ref):
        ns, _, path = ref.partition(':')
        mp = 'models/' + path + '.json'
        if not exists(mp):
            problems.append('missing model ' + ref)
            return
        with open(os.path.join(assets, mp)) as f:
            tex = json.load(f)['textures']['layer0']
        tp = 'textures/' + tex.partition(':')[2] + '.png'
        if not exists(tp):
            problems.append('missing texture ' + tex)

    for name in ('keepsake', 'tale_prop', 'tale_quill'):
        with open(os.path.join(assets, 'items', name + '.json')) as f:
            node = json.load(f)['model']
        if node['type'] == 'minecraft:model':
            check_model(node['model'])
            continue
        looks = [c['when'] for c in node['cases']]
        if len(set(looks)) != len(looks):
            problems.append(name + ': a look is listed twice')
        for c in node['cases']:
            check_model(c['model']['model'])
        if name == 'keepsake' and set(looks) != set(KEEPSAKES):
            problems.append('keepsake cases differ from the sixteen looks')
        if name == 'tale_prop' and set(looks) != set(PROPS):
            problems.append('tale_prop cases differ from the twelve looks')
    for i in range(4):
        if not exists('textures/particle/tale_mote_%d.png' % i):
            problems.append('missing tale_mote_%d' % i)
    for name, frames in made.items():
        for k, im in enumerate(frames):
            a = np.asarray(im.convert('RGBA'), int)
            solid = a[..., 3] > 0
            mx, mn = a[..., :3].max(-1), a[..., :3].min(-1)
            bad = solid & (mx >= 200) & (mn <= 4)
            if bad.any():
                problems.append('%s frame %d: %d fully saturated pixels' % (name, k, int(bad.sum())))
    for i, m in enumerate(MOTES):
        a = np.asarray(m, int)
        if ((a[..., 3] > 0) & (a[..., :3].max(-1) >= 200) & (a[..., :3].min(-1) <= 4)).any():
            problems.append('mote %d has a fully saturated pixel' % i)
    return problems


# ================================================================ previews


BG_DARK = (58, 64, 80)

BG_LIGHT = (206, 204, 198)

BACKDROPS = (('dark', BG_DARK), ('light', BG_LIGHT))


def tiles(images, labels, scale, cols, bg, pad=10):
    """Nearest-neighbour tiles on a backdrop, each with its name beneath."""
    cw = max(i.width for i in images) * scale
    ch = max(i.height for i in images) * scale
    rows = (len(images) + cols - 1) // cols
    out = Image.new('RGBA', (cols * (cw + pad) + pad, rows * (ch + pad + 14) + pad), (*bg, 255))
    d = ImageDraw.Draw(out)
    ink = (226, 220, 206, 255) if sum(bg) < 400 else (40, 38, 46, 255)
    for i, (im, name) in enumerate(zip(images, labels)):
        x, y = pad + (i % cols) * (cw + pad), pad + (i // cols) * (ch + pad + 14)
        out.alpha_composite(im.resize((im.width * scale, im.height * scale), Image.NEAREST), (x, y))
        d.text((x, y + ch + 1), name, fill=ink)
    return out


def small_row(images, bg, size, zoom=3, per_row=None):
    """The images as they read small: each squeezed to [size] pixels (a box filter, as the game's mipmaps do), in a slot, then
    shown [zoom] times larger so it can be seen."""
    per_row = per_row or len(images)
    slot = size + 4
    rows = (len(images) + per_row - 1) // per_row
    out = Image.new('RGBA', (per_row * slot + 4, rows * slot + 4), (*bg, 255))
    d = ImageDraw.Draw(out)
    slot_bg = tuple(int(c * 0.8) for c in bg)
    for i, im in enumerate(images):
        x, y = 2 + (i % per_row) * slot, 2 + (i // per_row) * slot
        d.rectangle([x, y, x + slot - 2, y + slot - 2], fill=(*slot_bg, 255))
        out.alpha_composite(im.resize((size, size), Image.BOX) if size != im.width else im, (x + 1, y + 1))
    return out.resize((out.width * zoom, out.height * zoom), Image.NEAREST)


def gif(frames, path, bg, scale=8, ms=100):
    """A looping preview of an animation, each frame on the backdrop."""
    out = []
    for f in frames:
        base = Image.new('RGBA', (f.width * scale, f.height * scale), (*bg, 255))
        base.alpha_composite(f.resize((f.width * scale, f.height * scale), Image.NEAREST))
        out.append(base.convert('P', palette=Image.ADAPTIVE, colors=128))
    out[0].save(path, save_all=True, append_images=out[1:], duration=ms, loop=0, disposal=2)


def previews(made, outdir):
    """The sheets: every item large on the dark and on the light ground, then small as the game shows them. Also the lit lamps
    as moving pictures and the mote's four frames."""
    os.makedirs(outdir, exist_ok=True)
    names = list(made)
    stills = [made[n][0] for n in names]
    keep = [n for n in names if n.startswith('keepsake/')]
    props = [n for n in names if n.startswith('tale_prop/')]
    for key, bg in BACKDROPS:
        tiles([made[n][0] for n in keep], [n.split('/')[1] for n in keep], 6, 8, bg).save(os.path.join(outdir, 'tales_keepsakes_%s.png' % key))
        tiles([made[n][0] for n in props] + [made['tale_quill'][0]], [n.split('/')[1] for n in props] + ['tale_quill'], 6, 7, bg).save(
            os.path.join(outdir, 'tales_props_%s.png' % key))
    # as in a hotbar: 32, 16 and the 8 or so pixels a carried prop is drawn at
    rows = []
    for key, bg in BACKDROPS:
        for size, zoom in ((32, 2), (16, 4), (8, 6)):
            rows.append(small_row(stills, bg, size, zoom, per_row=15))
    width = max(r.width for r in rows)
    sheet_ = Image.new('RGBA', (width, sum(r.height + 6 for r in rows)), (*BG_DARK, 255))
    y = 0
    for r in rows:
        sheet_.alpha_composite(r, (0, y))
        y += r.height + 6
    sheet_.save(os.path.join(outdir, 'tales_items_small.png'))
    for n in names:
        if len(made[n]) > 1:
            gif(made[n], os.path.join(outdir, 'tales_%s.gif' % n.split('/')[1]), BG_DARK, 10, 100)
    motes = [m.resize((8 * 12, 8 * 12), Image.NEAREST) for m in MOTES]
    mt = Image.new('RGBA', (len(motes) * 8 * 12 + 40, 8 * 12 + 20), (*BG_DARK, 255))
    for i, m in enumerate(motes):
        mt.alpha_composite(m, (10 + i * (8 * 12 + 6), 10))
    mt.save(os.path.join(outdir, 'tales_mote.png'))


# ================================================================ main


def main(assets, previews_dir=None):
    made = write_items(assets)
    problems = verify(assets, made)
    if previews_dir:
        previews(made, previews_dir)
    print('tale_items: %d keepsakes, %d props, the quill and %d mote frames' % (len(KEEPSAKES), len(PROPS), len(MOTES)))
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  ' + p)
        sys.exit(1)
    print('  every case has its model and texture; no fully saturated pixel')


if __name__ == '__main__':
    args = sys.argv[1:]
    main(args[0] if args else 'src/main/resources/assets/guildmark', args[1] if len(args) > 1 else None)
