"""Trade's six items at 32x, painted with the HD art kit (hdkit.py) in the house style: a few hard tones to a material, light
from the upper left, a fine dark outline round each thing. Stream X1 of the Trade design (living-world/trader/design.md, section
10 and 12.15): the Tally, the Carrier's Warrant, the Pack Harness, the Drover's Whistle, the Chapman's Cart and the Wagon Bill.

    python3 -B art/scripts/trade_items.py src/main/resources/assets/guildmark [preview-dir]

Writes, for each item id the code registers (`tally`, `carriers_warrant`, `pack_harness`, `drovers_whistle`, `chapmans_cart`,
`wagon_bill`), its item definition `items/<id>.json`, its model `models/item/<id>.json` and its texture `textures/item/<id>.png`
(a strip of 32 x 32 frames and a `.mcmeta` where the item moves). Nothing else: the items' names, recipes and behaviour are the
code parts'.

WHAT EACH ITEM DOES ON ITS OWN, with no code behind it:
  tally             in the inventory, on the ground and in a frame it is the closed book (a ribbon hangs from its pages and sways;
                    now and then a light crosses its brass); in a hand, first or third person, it is the open book (a page turns
                    now and then). One `minecraft:select` on `minecraft:display_context` picks.
  carriers_warrant  a sealed scroll: the ribbon's tail stirs, a light crosses the wax now and then.
  pack_harness      the saddlebags: the brass bell swings.
  drovers_whistle   a whistle on its cord: the cord swings down like a pendulum now and then, and a light crosses its brass. Its
                    item definition also has the branch a held, used whistle takes (`minecraft:using_item`: raised to the lips in
                    the goat horn's pose, the sound leaving it in arcs that grow outward); it shows only if a part gives the
                    whistle a use time, as the goat horn has.
  chapmans_cart     a hand-cart under its canvas: the pennant flutters. The pennant is its own layer painted in greys and tinted
                    by `minecraft:dye` (the `dyed_color` component of the stack, if a part sets one; white if not).
  wagon_bill        a bill of sale: a light crosses the seal, a corner of the sheet stirs.
Every moving item rests a few seconds between its movements and no two rest the same, so a hotbar of them is not in step.

Everything is seeded, so a rerun gives the same files. With a preview dir it also writes the sheets: every item large on a dark
and on a light ground, then as a hotbar shows them (32, 16 and 8 px), beside the game's own items it stands in for, and a moving
picture of each moving one, played as its `.mcmeta` plays it. Before that it checks what it wrote (every id has its files, every
model names a texture that exists, every strip fits its `.mcmeta`, no fully saturated pixel) and stops with the list if there is
a problem.
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
from hdkit import (PAL, GEMS, BAYER, Canvas, grid, vnoise, fbm, rand, bands, poly, seg_dist, path_dist, disc, ellipse, rect,
                   blur, shift, edge, grow, dist_in, light, rim, outline, glint, bevel, tone, mix)

N = 32
XS, YS = grid(N)

# ================================================================ the ramps these items add

# The Tally's leather: a deep ink blue, cool in its shadows (the Almanac is green, the Cook's Book brown)
INDIGO = [(10, 12, 24), (20, 26, 46), (32, 42, 72), (48, 62, 98), (68, 86, 124), (94, 112, 150)]
# Vellum for the Warrant: a shade paler and cooler than the contract's parchment
VELLUM = [(48, 42, 40), (92, 84, 76), (140, 130, 112), (186, 176, 150), (218, 210, 182), (240, 234, 208)]
# Silk for the ribbon: the crimson of the wax, a little brighter in its lights
SILK = [(30, 6, 14), (66, 12, 24), (112, 22, 34), (158, 38, 46), (198, 66, 62), (232, 112, 94)]
# Wicker, canvas, bone, hemp
WICKER = [(36, 24, 14), (74, 52, 28), (116, 84, 44), (158, 120, 66), (198, 160, 94), (228, 196, 130)]
CANVAS = [(46, 44, 42), (86, 84, 78), (128, 124, 112), (168, 164, 148), (204, 200, 180), (230, 226, 206)]
BOX = [(40, 30, 20), (82, 64, 38), (128, 100, 58), (170, 140, 84), (206, 178, 116), (232, 212, 156)]     # boxwood
IVORY = [(56, 44, 34), (108, 90, 66), (160, 140, 106), (206, 188, 150), (232, 218, 180), (250, 240, 206)]     # a whistle's pale horn
WOOL_RED = [(40, 10, 14), (84, 22, 26), (130, 40, 38), (170, 66, 52), (204, 100, 72)]
WOOL_OCHRE = [(52, 36, 14), (104, 72, 26), (156, 112, 40), (198, 154, 62), (226, 190, 98)]
INK = (50, 34, 32)                      # the ink a clerk writes in: a warm near-black
INK_RED = (140, 34, 30)                 # the red ink of a printed rule
LIGHT = np.array((255, 248, 226), float)

# ================================================================ small tools


def nb(stops, t, dither=0.3):
    """hdkit's bands on the 32 x 32 sprite, taking a plain number for a flat tone too."""
    return bands(stops, np.broadcast_to(np.asarray(t, float), (N, N)), dither)


def R(x0, y0, x1, y1):
    return rect(int(math.floor(x0)), int(math.floor(y0)), int(math.floor(x1)), int(math.floor(y1)), N)


def D(cx, cy, r):
    return disc(cx, cy, r, N)[0]


def E(cx, cy, rx, ry):
    return ellipse(cx, cy, rx, ry, N)[0]


def P(pts):
    return poly(pts, N)


def L(x0, y0, x1, y1, w=1.0):
    """A line's mask, [w] pixels thick."""
    return seg_dist(x0, y0, x1, y1, N)[0] < w / 2


def part(cv, mask, pal, base=0.55, relief=0.0, k=1.0, edge_k=0.15, noise=0.0, seed=0, dither=0.25, extra=0.0):
    """Paint [mask] in the ramp [pal]: the tone [base] (plus [extra], a number or an array), the lit edges brighter and the
    shaded ones darker by [edge_k] (hdkit's bevel), the whole rounded over like a pillow if [relief] (a radius in pixels) is
    set, lit from the upper left by [k], and a mottle of [noise]. Returns the tone it painted."""
    t = np.zeros((N, N)) + base + extra
    if relief:
        h = blur(mask.astype(float), max(1, int(round(relief))))
        t = t + k * light(h * 3.0, 1.0)
    if edge_k:
        t = t + edge_k * bevel(mask)
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


def pix(cv, rows, x0, y0, key, mask=None):
    """A little hand-drawn picture: one string per row, each character a colour from [key] ('.' and ' ' leave a pixel alone)."""
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            c = key.get(ch)
            if c is not None and (mask is None or (0 <= y0 + j < N and 0 <= x0 + i < N and mask[y0 + j, x0 + i])):
                cv.px(x0 + i, y0 + j, np.asarray(c, float), 1.0)


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


def seal(cv, cx, cy, r, seed=0, imprint='wheel', pal=None, lift=0.0):
    """A disc of sealing wax: the edge a little wobbly, a raised rim lit from the upper left, and inside it the hall's wheel
    pressed in (a ring, four spokes and a hub in a darker tone). Returns its mask."""
    pal = pal or PAL['wax']
    wob = vnoise(N, N, 2.0, seed)
    m = D(cx, cy, r) | (D(cx, cy, r + 0.6) & (wob > 0.62))
    d = np.hypot(XS - cx, YS - cy)
    g = shade_xy(cx, cy, r)
    rim_zone = m & (d > r - 1.15)
    field = m & ~rim_zone
    flat(cv, rim_zone, pal, 0.60 + lift + 0.30 * g)
    flat(cv, field, pal, 0.52 + lift + 0.10 * g)
    if imprint == 'wheel' and r >= 3.0:
        ri = r - 1.9
        spokes = ((np.abs(XS - cx) < 0.5) | (np.abs(YS - cy) < 0.5)) & (d < ri)
        flat(cv, ((np.abs(d - ri) < 0.55) | spokes | (d < 0.8)) & field, pal, 0.30 + lift * 0.5)
    return m


# ================================================================ the painters

PAINTERS = {}                  # key -> painter(frame=0) giving a 32 x 32 image
FRAMES = {}                    # key -> how many frames its strip has (the others are one still)


def painter(name, frames=1):
    def deco(fn):
        PAINTERS[name] = fn
        FRAMES[name] = frames
        return fn
    return deco




def shade_xy(cx, cy, r):
    """-1..1 across a round thing: +1 at its upper left (the light's side), -1 at its lower right."""
    return np.clip(-((XS - cx) + (YS - cy)) / (2.0 * r), -1, 1)


def flat(cv, mask, pal, t):
    """Paint [mask] in [pal] at the tone [t] (a number or an array), with no dither: for thin things."""
    cv.put(mask, nb(pal, t, 0.0))


# ---------------------------------------------------------------- the Tally, closed

TALLY_RIBBON_SWAY = [0.6, 1.3, 1.8, 1.3, 0.4, -0.7, -1.3, -0.6]     # how far the ribbon's tail is off its rest, px (frames 1..8)


def tally_book(cv):
    """The closed book without its ribbon: ink-blue leather, brass corners, a gilt wheel, the notched leaves showing at the
    fore-edge (right) and the tail (bottom)."""
    cover = R(4, 3, 26, 22)
    pages_r = R(26, 5, 29, 24)
    pages_b = R(6, 22, 29, 25)
    pages = pages_r | pages_b
    t = 0.76 + 0.06 * (fbm(N, N, 5, 41) - 0.5)
    t = np.where(pages_r & (np.floor(YS) % 2 == 1), t - 0.07, t)                 # the leaves, line on line
    t = np.where(pages_b & (np.floor(XS) % 2 == 1), t - 0.07, t)
    t = np.where(pages_r & (XS >= 28), t - 0.13, t)                              # the outer leaf in shade
    t = np.where(pages_b & (YS >= 24), t - 0.13, t)
    flat(cv, pages, PAL['parchment'], t)
    for y in (8, 11, 14, 17, 20):                                                # a tally's notches, cut in the fore-edge
        cv.px(28, y, np.asarray(PAL['parchment_dark'][3], float))
        cv.px(27, y, np.asarray(PAL['parchment'][2], float))
    for x in (9, 12, 15, 24):                                                    # and in the tail
        cv.px(x, 24, np.asarray(PAL['parchment_dark'][3], float))
        cv.px(x, 23, np.asarray(PAL['parchment'][2], float))
    flat(cv, R(29, 6, 30, 25) | R(7, 25, 30, 26), INDIGO, 0.14)                 # the back board, a pixel of it
    # the cover: leather with a grain, its edges worn pale
    t = 0.46 + 0.10 * (fbm(N, N, 3, 7) - 0.5) + 0.07 * (vnoise(N, N, 1.4, 9) - 0.5)
    t = t + 0.06 * (1 - (XS - 4) / 22.0) - 0.07 * ((YS - 3) / 19.0)
    eb = edge_bands(cover, 1, 1)
    t = t + 0.14 * (eb > 0) - 0.10 * (eb < 0)
    cv.put(cover, nb(INDIGO, t, 0.35))
    # the spine: rounded over, three raised cords across it, a groove where the board hinges
    spine = R(4, 3, 8, 22)
    flat(cv, spine, INDIGO, 0.30 + 0.12 * (XS < 5.5) - 0.12 * (XS >= 7))
    for y in (7, 12, 17):
        flat(cv, R(4, y, 8, y + 1), INDIGO, 0.64)
        flat(cv, R(4, y + 1, 8, y + 2), INDIGO, 0.14)
    flat(cv, R(8, 3, 9, 22), INDIGO, 0.10)
    flat(cv, R(9, 3, 10, 22), INDIGO, 0.58)
    # a stitched border in a paler thread
    ring_m = R(11, 5, 25, 20) & ~R(12, 6, 24, 19)
    flat(cv, ring_m & (((np.floor(XS) + np.floor(YS)).astype(int) % 2) == 0), INDIGO, 0.72)
    # brass corners, small ones
    corners = []
    for (cx0, cy0, sx, sy) in ((4, 3, 1, 1), (26, 3, -1, 1), (4, 22, 1, -1), (26, 22, -1, -1)):
        m = P([(cx0, cy0), (cx0 + sx * 3.4, cy0), (cx0 + sx * 3.4, cy0 + sy * 1.3), (cx0 + sx * 1.3, cy0 + sy * 3.4), (cx0, cy0 + sy * 3.4)])
        corners.append(m)
        flat(cv, m, PAL['brass'], 0.58 + 0.24 * shade_xy(cx0 + sx * 1.4, cy0 + sy * 1.4, 2.2))
    # the wheel on the cover, gilt: a rim, six spokes and a hub, lit from the upper left, with a shadow on the leather
    cx, cy = 17.5, 12.5
    d = np.hypot(XS - cx, YS - cy)
    ring = np.abs(d - 4.5) < 0.8
    spokes = np.zeros((N, N), bool)
    for a in range(3):
        ang = a * math.pi / 3 + math.pi / 2
        spokes |= seg_dist(cx - math.cos(ang) * 4.5, cy - math.sin(ang) * 4.5, cx + math.cos(ang) * 4.5, cy + math.sin(ang) * 4.5, N)[0] < 0.55
    hub = d < 1.7
    wheel = ring | spokes | hub
    sh = shift(wheel, 1, 1) & ~wheel & cover & (d > 3.6)
    cv.over(sh, np.asarray(INDIGO[0], float), 0.8)
    g = shade_xy(cx, cy, 4.5)
    flat(cv, ring, PAL['gold'], 0.66 + 0.26 * g)
    flat(cv, spokes & ~ring, PAL['gold'], 0.56 + 0.2 * g)
    flat(cv, hub, PAL['gold'], 0.72 + 0.2 * g)
    cv.px(int(cx), int(cy), np.asarray(INDIGO[1], float))                       # the nave: a pin
    return {'cover': cover, 'wheel': wheel, 'corners': corners}


def tally_ribbon(cv, sway):
    """The bookmark ribbon, three pixels of red silk out of the tail of the pages and hanging past the book's foot with a
    swallow's tail cut in it. [sway] is how far its foot is off its rest (px): the rows nearer the pages move less."""
    for y in range(23, 31):
        f = max(0.0, (y - 24) / 6.0)
        x0 = 19 + int(round(sway * f ** 1.4))
        shade = -0.12 if y < 25 else 0.0
        if y == 30:
            cols = (0, 2)
        else:
            cols = (0, 1, 2)
        for i in cols:
            tone_ = (0.78, 0.6, 0.42)[i] + shade + (0.06 if (y % 3 == 0 and i == 1) else 0.0)
            cv.px(x0 + i, y, nb(SILK, tone_, 0)[0, 0])


@painter('tally', frames=1 + len(TALLY_RIBBON_SWAY))
def tally_closed(frame=0):
    """The closed Tally: a squat book in ink-blue leather with brass corners and a gilt wheel on its cover, the fore-edge and
    tail of its leaves notched like a tally stick, a red ribbon hanging from the tail. Frame 0 is the ribbon at rest, the rest
    its sway."""
    cv = Canvas(N)
    tally_book(cv)
    tally_ribbon(cv, 0.0 if frame == 0 else TALLY_RIBBON_SWAY[frame - 1])
    return finish(cv)


# ---------------------------------------------------------------- the Tally, open

ROAD_GREEN = (58, 112, 62)            # a road's peril colours, as the board's pennants and the Tally's map use them
ROAD_AMBER = (206, 132, 34)
ROAD_RED = (172, 42, 40)
ROAD_BLACK = (30, 24, 28)

FACE_W, FACE_H = 12, 16               # a page's face, in pixels
OPEN_FLIP = (30, 60, 90, 120, 150)    # the angles a page is caught at while it turns (0 is flat on the right, 180 on the left)
OPEN_SWAY = [0.6, 1.2, 1.7, 1.2, 0.3, -0.6, -1.0, -0.5]


def new_face(seed):
    """A bare page: parchment with a mottle, as a canvas of its own."""
    f = Canvas(FACE_W, FACE_H)
    t = 0.80 + 0.14 * (fbm(FACE_W, FACE_H, 4, seed) - 0.5)
    f.rgb[:] = bands(PAL['parchment'], t, 0.12)
    f.a[:] = 1.0
    return f


def face_map():
    """The left page of the open book: three towns ringed by how old the news of each is (thick when fresh, thin when old) and
    the roads between them in their peril colours."""
    f = new_face(3)

    def road(x0, y0, x1, y1, col):
        f.put(seg_dist(x0, y0, x1, y1, FACE_W, FACE_H)[0] < 0.5, np.asarray(col, float))

    road(3.5, 3.5, 7.5, 8.5, ROAD_AMBER)
    road(7.5, 8.5, 3.5, 13.5, ROAD_GREEN)
    road(7.5, 8.5, 12.0, 6.0, ROAD_RED)
    ink = np.asarray(INK, float)
    old = np.asarray(PAL['parchment'][1], float)
    # a ring of twelve pixels for news that is fresh, of four for news that is old, the dot a town is in the middle of it
    full = ((-2, 0), (-2, -1), (-2, 1), (2, 0), (2, -1), (2, 1), (0, -2), (-1, -2), (1, -2), (0, 2), (-1, 2), (1, 2))
    mid = ((-2, 0), (-2, 1), (2, 0), (2, -1), (0, -2), (-1, -2), (0, 2), (1, 2))
    thin = ((-2, 0), (2, 0), (0, -2), (0, 2))
    for (cx, cy), ring, col in (((3, 3), full, ink), ((7, 8), thin, old), ((3, 13), mid, ink)):
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if dx * dx + dy * dy <= 5:
                    f.px(cx + dx, cy + dy, np.asarray(PAL['parchment'][4], float))
        for dx, dy in ring:
            f.px(cx + dx, cy + dy, col)
        f.px(cx, cy, col)
    return f


def face_tally():
    """The right page: rows of tally marks, four strokes and a fifth across them."""
    f = new_face(5)
    ink = np.asarray(INK, float)
    gate = ('X.X.X.X', 'XXXXXXX', 'X.X.X.X')                    # four strokes and the fifth struck across them
    stroke = ('X', 'X', 'X')
    pix(f, gate, 1, 1, {'X': ink})
    pix(f, stroke, 9, 1, {'X': ink})
    pix(f, stroke, 11, 1, {'X': ink})
    pix(f, gate, 1, 5, {'X': ink})
    pix(f, stroke, 9, 5, {'X': ink})
    pix(f, ('X.X.X.X', 'X.X.X.X', 'X.X.X.X'), 1, 9, {'X': ink})
    pix(f, stroke, 1, 13, {'X': np.asarray(ROAD_RED, float)})                   # the newest cut, in red
    pix(f, stroke, 3, 13, {'X': np.asarray(ROAD_RED, float)})
    return f


def face_lines():
    """The back of a turned leaf: lines of small writing."""
    f = new_face(8)
    ink = np.asarray(INK, float)
    for k, y in enumerate((2, 5, 8, 11, 14)):
        end = (10, 8, 11, 6, 9)[k]
        x = 1
        rng = np.random.default_rng(20 + k)
        while x < end:
            w = int(rng.integers(2, 4))
            for i in range(min(w, end - x)):
                f.px(x + i, y, ink)
            x += w + 1
    return f


def face_ledger():
    """The leaf under it: a column of little boxes, ticked or not, each with its line of writing."""
    f = new_face(11)
    ink = np.asarray(INK, float)
    for k, y in enumerate((2, 6, 10, 14)):
        for dx in (0, 1):
            for dy in (0, 1):
                f.px(1 + dx, y + dy, ink if (dx == 0 or dy == 0 or k % 2 == 0) else np.asarray(PAL['parchment'][3], float))
        if k % 2 == 0:
            f.px(2, y + 1, np.asarray(ROAD_RED, float))
        x = 5
        rng = np.random.default_rng(30 + k)
        end = (10, 9, 11, 8)[k]
        while x < end:
            w = int(rng.integers(2, 4))
            for i in range(min(w, end - x)):
                f.px(x + i, y, ink)
            x += w + 1
    return f


FACES = {}


def faces():
    if not FACES:
        FACES.update({'map': face_map(), 'tally': face_tally(), 'lines': face_lines(), 'ledger': face_ledger()})
    return FACES


def place_page(cv, face, x0, side):
    """Lay a page's face on the book with the shade of the gutter on the side next to it."""
    rgb = face.rgb.copy()
    for i in range(FACE_W):
        xc = x0 + i + 0.5
        d = (15.0 - xc) if side == 'L' else (xc - 17.0)
        k = 0.30 if d < 1.5 else 0.17 if d < 3.0 else 0.08 if d < 4.5 else 0.0          # the paper dips into the gutter
        if k > 0:
            rgb[:, i] = tone(rgb[:, i], -k)
    cv.rgb[8:24, x0:x0 + FACE_W] = rgb
    cv.a[8:24, x0:x0 + FACE_W] = 1.0


def open_boards(cv):
    """The two boards of the open book, the leaves' edges along its foot, the gutter, the brass at its corners."""
    outer = R(1, 6, 31, 27)
    t = 0.40 + 0.10 * (fbm(N, N, 3, 17) - 0.5)
    eb = edge_bands(outer, 1, 1)
    t = t + 0.16 * (eb > 0) - 0.12 * (eb < 0)
    cv.put(outer, nb(INDIGO, t, 0.35))
    flat(cv, R(15, 8, 17, 26), PAL['parchment_dark'], 0.40)                      # the gutter, deep in shadow
    for x0, x1 in ((3, 15), (17, 29)):                                          # the leaves' edges, heaped toward the gutter
        flat(cv, R(x0, 24, x1, 25), PAL['parchment'], 0.74)
        flat(cv, R(x0, 25, x1, 26), PAL['parchment'], 0.46)
    for x in (5, 8, 11, 20, 23, 26):
        cv.px(x, 25, np.asarray(PAL['parchment_dark'][3], float))
    corners = []
    for (cx0, cy0, sx, sy) in ((1, 6, 1, 1), (31, 6, -1, 1), (1, 27, 1, -1), (31, 27, -1, -1)):
        m = P([(cx0, cy0), (cx0 + sx * 3.2, cy0), (cx0 + sx * 3.2, cy0 + sy * 1.2), (cx0 + sx * 1.2, cy0 + sy * 3.2), (cx0, cy0 + sy * 3.2)])
        corners.append(m)
        flat(cv, m, PAL['brass'], 0.58 + 0.24 * shade_xy(cx0 + sx * 1.3, cy0 + sy * 1.3, 2.2))
    return corners


def open_notches(cv):
    for y in (10, 13, 16, 19, 22):
        cv.px(3, y, np.asarray(PAL['parchment_dark'][3], float))
        cv.px(28, y, np.asarray(PAL['parchment_dark'][3], float))


def open_ribbon(cv, sway, tail_only=False):
    """The ribbon lying down the gutter and hanging out at the foot, swaying there."""
    if not tail_only:
        for y in range(8, 25):
            cv.px(15, y, nb(SILK, 0.7, 0)[0, 0])
            cv.px(16, y, nb(SILK, 0.46, 0)[0, 0])
    for y in range(25, 31):
        f = (y - 25) / 5.0
        x0 = 15 + int(round(sway * f ** 1.4))
        shade = -0.1 if y < 27 else 0.0
        if y == 30:
            cv.px(x0, y, nb(SILK, 0.6 + shade, 0)[0, 0])
        else:
            cv.px(x0, y, nb(SILK, 0.72 + shade, 0)[0, 0])
            cv.px(x0 + 1, y, nb(SILK, 0.46 + shade, 0)[0, 0])


def flip_page(cv, theta):
    """The leaf caught at [theta] degrees of its turn from the right to the left: a leaf of the book's own paper, squeezed by the
    angle, its free edge lifted and bright, throwing a thin shade on the page it is above."""
    th = math.radians(theta)
    c, s = math.cos(th), math.sin(th)
    xf = 16.0 + 13.0 * c
    if abs(xf - 16.0) < 1.2:
        xf = 16.0 + (1.2 if c >= 0 else -1.2)
    span = abs(xf - 16.0)
    lift = 3.2 * s
    front = faces()['tally']
    back = faces()['lines']
    shade_cols = []
    for cx in range(N):
        xc = cx + 0.5
        frac = abs(xc - 16.0) / span
        if frac >= 1.0 or (xc < 16.0) != (xf < 16.0):
            continue
        dy = lift * frac
        for cy in range(N):
            yc = cy + 0.5
            if not (8.0 - dy <= yc < 24.0 - dy):
                continue
            fy = int(min(max(yc + dy - 8.0, 0), FACE_H - 1))
            if c >= 0:
                fx = int(min(max(frac * 13.0 - 1.0, 0), FACE_W - 1))
                col = front.rgb[fy, fx]
                k = -0.04 - 0.14 * s
            else:
                fx = int(min(max(11.0 - frac * 12.0, 0), FACE_W - 1))
                col = back.rgb[fy, fx]
                k = -0.12 - 0.1 * s
            if frac > 0.84:
                k = 0.14
            cv.rgb[cy, cx] = tone(col, k)
            cv.a[cy, cx] = 1.0
        shade_cols.append(cx)
    # the thin shade on the leaf below, just beyond the free edge
    sx = int(math.floor(xf)) + (1 if c >= 0 else -1)
    for k in (0, 1):
        x = sx + (k if c >= 0 else -k)
        if 3 <= x < 29 and not (15 <= x < 17):
            if abs(x + 0.5 - 16.0) > span:
                cv.rgb[8:24, x] = tone(cv.rgb[8:24, x], -0.28 + 0.1 * k)


def tally_open_frame(state, sway=0.0):
    """state: None (a map on the left, tally marks on the right), or the angle of the turning leaf in degrees; 180 is the leaf
    turned (its back on the left, the next leaf on the right)."""
    cv = Canvas(N)
    fc = faces()
    open_boards(cv)
    if state is None:
        place_page(cv, fc['map'], 3, 'L')
        place_page(cv, fc['tally'], 17, 'R')
    elif state >= 180:
        place_page(cv, fc['lines'], 3, 'L')
        place_page(cv, fc['ledger'], 17, 'R')
    else:
        place_page(cv, fc['map'], 3, 'L')
        place_page(cv, fc['ledger'], 17, 'R')
    open_notches(cv)
    open_ribbon(cv, sway)
    if state is not None and state < 180:
        flip_page(cv, state)
        open_ribbon(cv, sway, tail_only=True)
    return finish(cv)


@painter('tally_open', frames=1 + len(OPEN_FLIP) + 1 + len(OPEN_SWAY))
def tally_open(frame=0):
    """The open Tally, as it is in the hand: the Word's map on its left page, a page of tally marks on its right, the ribbon in
    the gutter. Frames: 0 at rest; 1 to 5 a leaf caught at the angles of its turn (the turn is played forward, the leaf lies a
    while, then it is played back); 6 the leaf lying turned; 7 to 14 the ribbon's tail swaying."""
    if frame == 0:
        return tally_open_frame(None)
    if frame <= len(OPEN_FLIP):
        return tally_open_frame(OPEN_FLIP[frame - 1])
    if frame == len(OPEN_FLIP) + 1:
        return tally_open_frame(180)
    return tally_open_frame(None, OPEN_SWAY[frame - len(OPEN_FLIP) - 2])


# ---------------------------------------------------------------- the Carrier's Warrant

WARRANT_FLUTTER = [(0.5, -0.5), (1.0, -1.0), (1.4, -1.4), (1.0, -1.0), (0.4, -0.4), (-0.4, 0.4), (-0.9, 0.9), (-0.5, 0.5)]
# how far the left and the right streamer of the ribbon are off their rest, px at their feet, one pair for each frame of the flutter
SC_CY, SC_R = 11.0, 5.8                                                       # the roll's middle line and radius
SC_X0, SC_X1 = 6.6, 25.4                                                      # its ends, centre to centre of the caps


def scroll_body(cv):
    """The rolled vellum, lying level: a cylinder lit from above, the spiral of its layers on the end toward us, the sheet's
    loose edge a seam along it. Returns the roll's mask."""
    cyl = (np.abs(YS - SC_CY) <= SC_R) & (XS >= SC_X0) & (XS <= SC_X1)
    cap_l = E(SC_X0, SC_CY, 3.1, SC_R)
    cap_r = E(SC_X1, SC_CY, 3.1, SC_R)
    body = cyl | cap_l | cap_r
    v = np.clip((YS - (SC_CY - SC_R)) / (2 * SC_R), 0, 1)                    # 0 at the top of the roll, 1 at its foot
    t = 0.94 - 0.56 * v ** 1.1 + 0.06 * (np.abs(v - 0.2) < 0.1)
    t += 0.05 * (fbm(N, N, 4, 71) - 0.5) * 2
    t -= 0.14 * (np.abs(v - 0.62) < 0.07)                                    # the loose edge of the sheet, a seam along the roll
    cv.put(body, nb(VELLUM, t, 0.2))
    # the end toward us: the roll's layers, a spiral
    dx, dy = (XS - SC_X0) / 3.1, (YS - SC_CY) / SC_R
    r = np.hypot(dx, dy)
    spiral = (r * 2.0 + np.arctan2(dy, dx) / (2 * math.pi)) % 1.0
    tt = np.where(spiral < 0.26, 0.5, 0.97) - 0.05 * dy
    tt = np.where(r > 0.86, 0.42, tt)
    tt = np.where(r < 0.16, 0.2, tt)
    cv.put(cap_l, nb(VELLUM, tt, 0.0))
    return body


def scroll_ribbon(cv, flutter):
    """The ribbon bound round the middle of the roll, its two streamers hanging from it and fluttering."""
    band = (np.abs(YS - SC_CY) <= SC_R) & (XS >= 13.0) & (XS < 18.0)
    v = np.clip((YS - (SC_CY - SC_R)) / (2 * SC_R), 0, 1)
    t = 0.78 - 0.40 * v ** 1.1 + 0.10 * (XS < 14.0) - 0.12 * (XS >= 17.0)
    flat(cv, band, SILK, t)
    for sx, x_top, x_foot, s in ((-1, 13.4, 11.4, flutter[0]), (1, 17.4, 19.4, flutter[1])):
        for y in range(15, 28):
            f = (y - 15) / 12.0
            xc = x_top + (x_foot - x_top) * f + s * f ** 1.4
            x0 = int(round(xc - 1.0))
            last = y == 27
            for i in range(2):
                if last and i == (0 if sx > 0 else 1):
                    continue
                cv.px(x0 + i, y, nb(SILK, (0.88, 0.58)[i] - (0.12 if y < 18 else 0.0), 0)[0, 0])


@painter('carriers_warrant', frames=1 + len(WARRANT_FLUTTER))
def warrant(frame=0):
    """The Carrier's Warrant: a sheet of vellum rolled and tied round its middle with a red ribbon whose two ends hang and
    flutter, sealed over the ribbon with red wax and the hall's wheel. Frame 0 is at rest, the rest the flutter."""
    cv = Canvas(N)
    scroll_body(cv)
    scroll_ribbon(cv, (0.0, 0.0) if frame == 0 else WARRANT_FLUTTER[frame - 1])
    seal(cv, 15.4, SC_CY, 4.0, seed=5, lift=0.1)
    return finish(cv)
# ---------------------------------------------------------------- the Pack Harness

# The bell's swing: the angles it is caught at (degrees, a positive one swings its foot to the right). It rings down, damped: the
# timeline passes through the rest between them.
BELL_ANGLES = [8, 14, -8, -13, 5, 9, -4, -6]


def hung(px, py, deg, k=1.0):
    """Each pixel's place in a frame hung from (px, py), swung [deg] to the right and [k] times the size: across it (lx) and down
    it (ly), in the frame's own pixels."""
    a = math.radians(deg)
    dx, dy = (XS - px) / k, (YS - py) / k
    return dx * math.cos(a) - dy * math.sin(a), dx * math.sin(a) + dy * math.cos(a)


def brass_bell(cv, px, py, deg, k=1.25):
    """A bell hung from (px, py) on a short loop of leather, swung [deg]: a crown, a flared skirt with a thick lip, a clapper that
    trails the swing a little."""
    lx, ly = hung(px, py, deg, k)
    cord = (np.abs(lx) <= 0.5) & (ly >= -0.6) & (ly <= 1.4)
    flat(cv, cord, PAL['leather'], 0.36)
    r = np.interp(ly, [1.0, 1.6, 2.6, 4.6, 6.0, 7.2], [0.9, 1.9, 2.4, 2.8, 3.25, 3.8])
    body = (ly >= 1.0) & (ly <= 7.2) & (np.abs(lx) <= r)
    u = np.clip(lx / np.maximum(r, 0.1), -1, 1)
    tt = 0.68 - 0.34 * u + 0.22 * (np.abs(u + 0.45) < 0.2)
    tt = np.where(ly >= 6.2, tt - 0.22 + 0.12 * (ly >= 6.9), tt)               # the lip, a band of its own
    cl = np.hypot(lx - 0.5 * math.sin(math.radians(deg * 0.8)), ly - 8.1) < 0.85
    cv.put(grow(body | cl, 1) & ~(body | cl), np.asarray(PAL['gold'][0], float))     # a dark edge, so it stands off the wicker
    flat(cv, body, PAL['gold'], tt)
    flat(cv, cl & ~body, PAL['gold'], 0.5)
    return body


def pannier(cv, x0, x1, ytop, ybot, shrink, seed):
    """A wicker basket seen from the front: wider at its rim than at its foot, woven in rows two pixels deep of stitches four long,
    each row half a stitch along from the one above, a willow rim round the top. Returns its mask."""
    f = np.clip((YS - ytop) / (ybot - ytop), 0, 1)
    left = x0 + shrink * f
    right = x1 - shrink * f
    body = (YS >= ytop) & (YS < ybot) & (XS >= left) & (XS < right)
    band = np.floor((YS - ytop) / 2.0).astype(int)
    within = (np.floor(YS - ytop) % 2).astype(int)
    along = np.floor(XS - x0).astype(int) + (band % 2) * 2
    gap = (along % 4) == 3
    stitch = along // 4
    t = 0.56 + 0.05 * (((stitch * 5 + band * 3) % 3) - 1) + 0.04 * (fbm(N, N, 3, seed) - 0.5) * 2
    t = np.where(within == 0, t + 0.12, t - 0.06)
    t = np.where(gap, t - 0.24, t)
    t = t + 0.12 * np.clip(1.0 - (XS - x0) / (x1 - x0) * 1.7, -0.4, 1) - 0.05     # light from the left
    t = np.where(body & (XS >= right - 1.0), t - 0.2, t)
    t = np.where(body & (YS >= ybot - 1.0), t - 0.14, t)
    flat(cv, body, WICKER, t)
    rim = body & (YS < ytop + 2.0)
    flat(cv, rim, WICKER, 0.84 + 0.1 * (YS < ytop + 1.0) - 0.16 * (XS >= right - 1.5) - 0.1 * (YS >= ytop + 1.0))
    return body


def blanket_roll(cv):
    """The blanket rolled and lying across the top of the harness: striped wool on a round roll, the layers showing at its ends."""
    cy, r = 6.6, 3.7
    body = ((np.abs(YS - cy) <= r) & (XS >= 7.0) & (XS < 25.0)) | E(7.0, cy, 1.9, r) | E(25.0, cy, 1.9, r)
    v = np.clip((YS - (cy - r)) / (2 * r), 0, 1)
    xi = np.floor(XS).astype(int)
    och = np.isin(xi, (11, 12, 19, 20))
    drk = np.isin(xi, (10, 13, 18, 21))
    base = 0.82 - 0.50 * v ** 1.1
    red = nb(WOOL_RED, base + 0.03 * (fbm(N, N, 3, 91) - 0.5) * 2, 0.15)
    ochre = nb(WOOL_OCHRE, base - 0.04, 0.15)
    dark = nb(WOOL_RED, base - 0.4, 0)
    cv.put(body, np.where(och[..., None], ochre, np.where(drk[..., None], dark, red)))
    # the layers at each end
    for cx, sgn in ((7.0, -1), (25.0, 1)):
        d = np.hypot((XS - cx) / 1.9, (YS - cy) / r)
        cap = d <= 1.0
        ring = (d * 3.0 % 1.0) < 0.5
        cv.put(cap, np.where(ring[..., None], nb(WOOL_RED, 0.52 + 0.1 * (sgn < 0), 0), nb(WOOL_OCHRE, 0.5 + 0.1 * (sgn < 0), 0)))
    return body


def harness_body(cv, bell_deg):
    """The harness as it hangs on a peg: the blanket rolled across its top, a wicker basket each side with a leather strap and a
    brass buckle over it, a breast strap between them and on it a brass ring and a bell."""
    pannier(cv, 2.0, 11.8, 11.0, 26.0, 2.2, 7)
    pannier(cv, 20.2, 30.0, 11.0, 26.0, 2.2, 8)
    # the breast strap across the gap, and the roll over everything
    flat(cv, R(11, 10, 21, 12), PAL['leather'], 0.5 + 0.12 * (YS < 10.9) - 0.1 * (YS >= 11.1))
    blanket_roll(cv)
    # the straps over the roll and down the baskets, each with its buckle
    for x0 in (6, 24):
        flat(cv, R(x0, 3, x0 + 2, 26), PAL['leather'], 0.46 + 0.14 * (XS < x0 + 1))
        flat(cv, R(x0 - 1, 17, x0 + 3, 21), PAL['brass'], 0.58 + 0.24 * shade_xy(x0 + 1, 19, 2.2))
        flat(cv, R(x0, 18, x0 + 2, 20), PAL['leather'], 0.3)
    # the ring on the breast strap and the bell
    flat(cv, np.abs(np.hypot(XS - 16.0, YS - 12.8) - 1.25) < 0.62, PAL['brass'], 0.72 + 0.2 * shade_xy(16, 12.8, 1.4))
    brass_bell(cv, 16.0, 13.4, bell_deg)


@painter('pack_harness', frames=1 + len(BELL_ANGLES))
def pack_harness(frame=0):
    """The Pack Harness: a striped blanket rolled across the top, a wicker basket each side with a strap and a buckle over it, a
    brass bell on a ring at the breast strap between them. Frame 0 is at rest; frames 1 to 8 the bell caught at the angles of its
    swing."""
    cv = Canvas(N)
    harness_body(cv, 0.0 if frame == 0 else BELL_ANGLES[frame - 1])
    return finish(cv)
# ---------------------------------------------------------------- the Drover's Whistle

WHISTLE_ANGLES = [6, 11, -6, -10, 4, -3]                 # the cord's swing: where it is caught (degrees, positive to the right)
BLOW_FRAMES = 3                                         # the sound that leaves a whistle in use, in three steps of growing arcs
ARC_LIGHT = np.array((255, 244, 214), float)


def seg_local(lx, ly, ax, ay, bx, by):
    """Distance to a segment in a frame's own coordinates, how far along it the nearest point is (0 to 1) and which side of it the
    pixel is (positive to the left of its travel, seen from the front)."""
    dx, dy = bx - ax, by - ay
    ln = math.hypot(dx, dy)
    t = np.clip(((lx - ax) * dx + (ly - ay) * dy) / (ln * ln), 0, 1)
    d = np.hypot(lx - (ax + t * dx), ly - (ay + t * dy))
    side = ((lx - ax) * -dy + (ly - ay) * dx) / ln
    return d, t, side


def cord_along(lx, ly, pts, r):
    """A cord through [pts] (a list of points in the frame's own coordinates), [r] pixels either side of its middle: its mask
    and how far along it each pixel lies (in pixels, for the twist)."""
    best = np.full(lx.shape, 9.0)
    along = np.zeros(lx.shape)
    run = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        d, t, _ = seg_local(lx, ly, x0, y0, x1, y1)
        ln = math.hypot(x1 - x0, y1 - y0)
        closer = d < best
        best = np.where(closer, d, best)
        along = np.where(closer, run + t * ln, along)
        run += ln
    return best < r, along, best


def twisted(cv, mask, along, across, ramp):
    """A twisted cord's strands: short slants along it, light then dark, the lit side brighter."""
    slant = np.floor((along + across * 0.9) * 0.75).astype(int) % 2
    t = 0.50 + 0.20 * slant - 0.14 * (across > 0.25) + 0.08 * (across < -0.25)
    flat(cv, mask, ramp, t)


def cord_loop(cv, lx, ly, top, foot, width):
    """The loop of cord: a teardrop from the knot at [top] down to the ring at [foot], [width] across at its widest, twisted."""
    pts_l, pts_r = [], []
    for k in range(25):
        t = k / 24.0
        w = width * math.sin(math.pi * t ** 0.72) ** 0.9
        y = top + (foot - top) * t
        pts_l.append((-w, y))
        pts_r.append((w, y))
    ml, al, dl = cord_along(lx, ly, pts_l, 0.78)
    mr, ar, dr = cord_along(lx, ly, pts_r, 0.78)
    side_l = lx - np.interp(ly, [p[1] for p in pts_l], [p[0] for p in pts_l])
    side_r = lx - np.interp(ly, [p[1] for p in pts_r], [p[0] for p in pts_r])
    m = ml | mr
    twisted(cv, ml & ~mr, al, side_l, SILK)
    twisted(cv, mr, ar, side_r, SILK)
    return m


def whistle_body(cv, lx, ly, top, bottom, r=2.7):
    """A fipple whistle of pale horn lying from its ring end [top] to its mouth [bottom] along ly, its axis at lx = 0: a domed
    brass cap at the ring end, a brass band, a window with the blade of the labium under it, the mouth end cut on a slant.
    Returns its mask."""
    f = (ly - top) / (bottom - top)
    rad = r - 0.55 * np.clip((f - 0.62) / 0.38, 0, 1)
    dome = top + 1.4 * (lx / r) ** 2
    cut = bottom - 0.32 * lx
    m = (np.abs(lx) <= rad) & (ly >= dome) & (ly <= cut)
    u = np.clip(lx / np.maximum(rad, 0.5), -1, 1)                                  # -1 the lit side, +1 the shaded
    t = 0.80 - 0.32 * (u + 1) / 2 + 0.1 * (np.abs(u + 0.5) < 0.22)
    flat(cv, m, IVORY, t + 0.06)
    brass_t = 0.60 + 0.30 * (-u) * 0.8 + 0.1 * (np.abs(u + 0.45) < 0.2)
    flat(cv, m & (ly < top + 2.8), PAL['brass'], brass_t)                          # the cap
    flat(cv, m & (ly >= top + 7.0) & (ly < top + 8.4), PAL['brass'], brass_t)      # a band
    window = m & (ly >= top + 11.4) & (ly < top + 13.4) & (np.abs(lx) < 1.15)
    flat(cv, window, IVORY, 0.04)
    flat(cv, m & (ly >= top + 13.4) & (ly < top + 14.5) & (np.abs(lx) < 1.5), IVORY, 0.97)    # the blade the wind splits on
    flat(cv, m & (ly >= cut - 0.9), IVORY, 0.30)                             # the mouth's end, in shade
    return m


def whistle_ring(cv, lx, ly, cy):
    ring = np.abs(np.hypot(lx, ly - cy) - 1.5) < 0.62
    flat(cv, ring, PAL['brass'], 0.66 + 0.28 * np.clip(-(lx + (ly - cy)) / 3.0, -1, 1))
    return ring


def whistle_hung(cv, deg):
    """The whistle hung by its cord from a knot at the top, swung [deg]."""
    lx, ly = hung(16.0, 2.2, deg)
    cord_loop(cv, lx, ly, 0.2, 9.4, 3.7)
    flat(cv, np.hypot(lx, ly - 0.2) < 1.5, SILK, 0.46 + 0.34 * shade_xy(16, 2.2, 1.6))              # the knot
    whistle_ring(cv, lx, ly, 10.0)
    whistle_body(cv, lx, ly, 11.4, 27.2)


@painter('drovers_whistle', frames=1 + len(WHISTLE_ANGLES))
def drovers_whistle(frame=0):
    """The Drover's Whistle on its cord: a pale horn whistle with brass at its ring end hung from a crimson twisted cord in a loop,
    the knot at the top. Frame 0 is at rest; frames 1 to 6 are the angles the cord is caught at as it swings."""
    cv = Canvas(N)
    whistle_hung(cv, 0.0 if frame == 0 else WHISTLE_ANGLES[frame - 1])
    return finish(cv)


def sound_arcs(img, cx, cy, toward, step, radii):
    """Arcs of sound leaving (cx, cy) in the direction [toward] (degrees, 0 to the right, up positive), [step] of [BLOW_FRAMES]
    grown: the arcs of [radii], the outer ones fainter, each with a dark edge so it reads on a light ground. Painted under the
    finished picture."""
    cv = Canvas(N)
    for k, r0 in enumerate(radii):
        r = r0 + 1.0 * step
        a = np.clip(1.0 - 0.3 * k - (0.3 if step == BLOW_FRAMES - 1 else 0.0), 0.3, 1.0)
        col = ARC_LIGHT * a + np.array((150, 120, 90), float) * (1 - a)
        for deg in np.arange(toward - 26, toward + 26.01, 0.5):                 # a line one pixel thick, walked along the arc
            x = cx + r * math.cos(math.radians(deg))
            y = cy - r * math.sin(math.radians(deg))
            if 0 <= x < N and 0 <= y < N:
                cv.px(int(x), int(y), col)
    outline(cv, 0.8)
    arcs = cv.image()
    arcs.alpha_composite(img)
    return arcs


@painter('drovers_whistle_blow', frames=BLOW_FRAMES)
def drovers_whistle_blow(frame=0):
    """The whistle in use, raised: the whistle slanting at 45 degrees with its mouth to the upper left and its cord hanging in a
    swag from the ring, the sound leaving the mouth in two arcs. Three frames: the arcs grow outward."""
    cv = Canvas(N)
    mx, my = 12.5, 12.5                                                      # the mouth end
    ax = ay = math.sqrt(0.5)                                                 # the axis, from the mouth down to the ring
    dx, dy = XS - mx, YS - my
    lx = dx * ay - dy * ax
    ly = 17.4 - (dx * ax + dy * ay)
    rx, ry = mx + 16.0 * ax, my + 16.0 * ay                                  # the ring's end
    pts = [(rx + 1.0, ry + 1.2), (rx + 2.4, ry + 3.0), (rx + 0.8, ry + 5.0), (rx - 2.2, ry + 5.4), (rx - 4.8, ry + 4.0), (rx - 6.2, ry + 1.6)]
    m, al, _ = cord_along(XS, YS, pts, 0.78)
    twisted(cv, m, al, np.zeros(m.shape), SILK)
    flat(cv, np.hypot(XS - pts[-1][0], YS - pts[-1][1]) < 1.4, SILK, 0.46 + 0.34 * shade_xy(pts[-1][0], pts[-1][1], 1.6))
    whistle_body(cv, lx, ly, 1.4, 17.4)
    whistle_ring(cv, lx, ly, 0.2)
    img = finish(cv)
    return sound_arcs(img, mx - 1.2, my - 1.2, 135, frame, (4.6, 8.8))


# ================================================================ the light that crosses what is shiny

SWEEP = 8                      # the frames of a sweep of light, each held for SWEEP_TICKS
SWEEP_TICKS = 2
SWEEP_WIDTH = 3.2              # half the band's width, in pixels along the diagonal
SWEEP_LIGHT = np.array((255, 248, 226), float)


def sweep_frames(still, ramps):
    """A band of light crossing the still from its upper left to its lower right, over SWEEP frames: wherever a pixel is one of
    the tones of the shiny [ramps] it is lightened toward warm white by how near the band is. The outline and the dull parts do not
    change, so the shape and the alpha are the still's in every frame."""
    a = np.asarray(still.convert('RGBA'), float)
    shiny = np.zeros((N, N), bool)
    for ramp in ramps:
        for stop in ramp:
            shiny |= (a[..., :3] == np.round(np.asarray(stop, float))).all(-1)
    shiny &= a[..., 3] > 0
    along = XS + YS
    out = []
    for k in range(SWEEP):
        centre = -4.0 + (2 * N + 8) * (k + 0.5) / SWEEP
        near = np.clip(1.0 - np.abs(along - centre) / SWEEP_WIDTH, 0, 1) * shiny * 0.7
        b = a.copy()
        b[..., :3] = a[..., :3] * (1 - near[..., None]) + SWEEP_LIGHT * near[..., None]
        out.append(Image.fromarray(np.clip(b, 0, 255).round().astype(np.uint8), 'RGBA'))
    return out


# ================================================================ what each texture is: its frames and how they play

# Each texture is a strip of 32 x 32 frames. Its `timeline` is what its .mcmeta plays: a frame number, or (frame, ticks) for a frame
# held longer than the usual two. Every strip starts on its resting frame, which it holds the longest, and no two items rest alike.
SHINE = {
    'tally': [PAL['brass'], PAL['gold']],
    'carriers_warrant': [PAL['wax']],
    'pack_harness': [PAL['gold'], PAL['brass']],
    'drovers_whistle': [PAL['brass']],
}


def seq(a, b):
    return list(range(a, b + 1))


def strip_tally():
    rest = PAINTERS['tally'](0)
    return [rest] + [PAINTERS['tally'](i) for i in range(1, 1 + len(TALLY_RIBBON_SWAY))] + sweep_frames(rest, SHINE['tally'])


def strip_tally_open():
    return [PAINTERS['tally_open'](i) for i in range(FRAMES['tally_open'])]


def strip_warrant():
    rest = PAINTERS['carriers_warrant'](0)
    return [rest] + [PAINTERS['carriers_warrant'](i) for i in range(1, 1 + len(WARRANT_FLUTTER))] + sweep_frames(rest, SHINE['carriers_warrant'])


def strip_harness():
    rest = PAINTERS['pack_harness'](0)
    return [rest] + [PAINTERS['pack_harness'](i) for i in range(1, 1 + len(BELL_ANGLES))] + sweep_frames(rest, SHINE['pack_harness'])


def strip_whistle():
    rest = PAINTERS['drovers_whistle'](0)
    return [rest] + [PAINTERS['drovers_whistle'](i) for i in range(1, 1 + len(WHISTLE_ANGLES))] + sweep_frames(rest, SHINE['drovers_whistle'])


def strip_whistle_blow():
    return [PAINTERS['drovers_whistle_blow'](i) for i in range(BLOW_FRAMES)]


# name -> (the strip's frames, the timeline). The names are the texture names: `textures/item/<name>.png`.
STRIPS = {
    'tally': (strip_tally, [(0, 64)] + seq(1, 8) + [(0, 16)] + seq(9, 16)),
    'tally_open': (strip_tally_open, [(0, 44)] + seq(7, 14) + [(0, 30)] + seq(1, 5) + [(6, 36)] + seq(1, 5)[::-1]),
    'carriers_warrant': (strip_warrant, [(0, 82)] + seq(1, 8) + [(0, 24)] + seq(9, 16)),
    # the bell rings down: out to one side and back through the rest, out to the other and back, each swing shorter
    'pack_harness': (strip_harness, [(0, 58), 1, (2, 3), 1, 0, 3, (4, 3), 3, 0, 5, (6, 3), 5, 0, 7, (8, 3), 7, (0, 20)] + seq(9, 16)),
    # the cord swings down like a pendulum, held a moment at the end of each swing, then a light crosses its brass
    'drovers_whistle': (strip_whistle, [(0, 70), 1, (2, 3), 1, 0, 3, (4, 3), 3, 0, 5, 0, 6, (0, 12)] + seq(7, 14)),
    # raised to the lips: the sound's arcs grow outward, over and over (no rest: it plays only while the whistle is blown)
    'drovers_whistle_blow': (strip_whistle_blow, [(0, 3), (1, 3), (2, 3)]),
}

LOOPING = {'drovers_whistle_blow'}          # strips with no rest: they play only while something is held to the lips


def mcmeta(timeline):
    return {'animation': {'frametime': 2, 'frames': [e if isinstance(e, int) else {'index': e[0], 'time': e[1]} for e in timeline]}}


def playlist(timeline):
    """What a timeline plays: [(frame, ticks)] in order."""
    return [(e, 2) if isinstance(e, int) else e for e in timeline]


# ================================================================ the files

def leaf(name):
    return {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}


def definitions():
    """The item definitions, `items/<id>.json`, by id."""
    return {
        # closed in the inventory, on the ground, in a frame and on a shelf; open in a hand
        'tally': {'model': {'type': 'minecraft:select', 'property': 'minecraft:display_context',
                            'cases': [{'when': ['gui', 'ground', 'fixed', 'on_shelf'], 'model': leaf('tally')}],
                            'fallback': leaf('tally_open')}},
        'carriers_warrant': {'model': leaf('carriers_warrant')},
        'pack_harness': {'model': leaf('pack_harness')},
        # in the hand, raised to the lips, the sound leaving it; for a part that gives the whistle a use time, as the goat horn has
        'drovers_whistle': {'model': {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
                                      'on_true': leaf('drovers_whistle_blow'), 'on_false': leaf('drovers_whistle')}},
    }


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def strip_image(frames):
    out = Image.new('RGBA', (N, N * len(frames)), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        out.paste(f, (0, i * N))
    return out


# the whistle in use is held up to the mouth, in the pose the game's own tooting goat horn takes (its numbers, for the same pose)
MODEL_EXTRA = {
    'drovers_whistle_blow': {'display': {
        'thirdperson_righthand': {'rotation': [0, -125, 0], 'translation': [-1, 2, 2], 'scale': [0.5, 0.5, 0.5]},
        'thirdperson_lefthand': {'rotation': [0, 55, 0], 'translation': [-1, 2, 2], 'scale': [0.5, 0.5, 0.5]},
        'firstperson_righthand': {'rotation': [0, -55, -5], 'translation': [-1, -2.5, -7.5]},
        'firstperson_lefthand': {'rotation': [0, 115, 5], 'translation': [0, -2.5, -7.5]},
    }},
}


def write_items(assets):
    """Paint every texture and write it with its .mcmeta, its model and the item definitions; returns {texture name: [frames]}."""
    made = {}
    for name, (build, timeline) in STRIPS.items():
        made[name] = frames = build()
        path = os.path.join(assets, 'textures', 'item', name + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        (strip_image(frames) if len(frames) > 1 else frames[0]).save(path, optimize=True)
        if len(frames) > 1:
            write_json(path + '.mcmeta', mcmeta(timeline))
        write_json(os.path.join(assets, 'models', 'item', name + '.json'),
                   {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/' + name}, **MODEL_EXTRA.get(name, {})})
    for item, definition in definitions().items():
        write_json(os.path.join(assets, 'items', item + '.json'), definition)
    return made


def model_refs(node):
    """Every model an item definition's tree names."""
    if isinstance(node, dict):
        out = [node['model']] if node.get('type') == 'minecraft:model' else []
        for v in node.values():
            out += model_refs(v)
        return out
    if isinstance(node, list):
        return [r for v in node for r in model_refs(v)]
    return []


def verify(assets, made):
    """Every item definition's models exist and each model's texture, every strip fits its .mcmeta, every strip starts at rest,
    no two items rest the same number of ticks, no fully saturated pixel. Returns what is wrong (empty when all is well)."""
    problems = []

    def exists(rel):
        return os.path.exists(os.path.join(assets, rel))

    for item in definitions():
        if not exists('items/%s.json' % item):
            problems.append('missing item definition ' + item)
            continue
        with open(os.path.join(assets, 'items', item + '.json')) as f:
            tree = json.load(f)
        for ref in model_refs(tree):
            ns, _, path = ref.partition(':')
            if ns != 'guildmark' or not exists('models/%s.json' % path):
                problems.append('%s: missing model %s' % (item, ref))
                continue
            with open(os.path.join(assets, 'models', path + '.json')) as f:
                tex = json.load(f)['textures']['layer0']
            if not exists('textures/%s.png' % tex.partition(':')[2]):
                problems.append('%s: missing texture %s' % (item, tex))
    rests = {}
    for name, frames in made.items():
        path = os.path.join(assets, 'textures', 'item', name + '.png')
        im = Image.open(path)
        if im.size != (N, N * len(frames)):
            problems.append('%s: the strip is %d x %d, not %d x %d' % (name, im.width, im.height, N, N * len(frames)))
        meta_path = path + '.mcmeta'
        if len(frames) > 1:
            if not os.path.exists(meta_path):
                problems.append(name + ': an animated strip with no .mcmeta')
            else:
                with open(meta_path) as f:
                    plays = playlist([e if isinstance(e, int) else (e['index'], e['time']) for e in json.load(f)['animation']['frames']])
                if any(not 0 <= i < len(frames) or t < 1 for i, t in plays):
                    problems.append(name + ': the .mcmeta plays a frame that is not there')
                if plays[0][0] != 0:
                    problems.append(name + ': the loop does not start on the resting frame')
                if max(t for _, t in plays) != plays[0][1] and name not in LOOPING:
                    problems.append(name + ': the resting frame is not the longest hold')
                if len({i for i, _ in plays}) != len(frames):
                    problems.append(name + ': a frame is never shown')
                if name not in LOOPING:
                    rests.setdefault(plays[0][1], []).append(name)
        elif os.path.exists(meta_path):
            problems.append(name + ': a .mcmeta beside a single frame')
        for k, fr in enumerate(frames):
            a = np.asarray(fr.convert('RGBA'), int)
            solid = a[..., 3] > 0
            bad = solid & (a[..., :3].max(-1) >= 200) & (a[..., :3].min(-1) <= 4)
            if bad.any():
                problems.append('%s frame %d: %d fully saturated pixels' % (name, k, int(bad.sum())))
            border = np.zeros_like(solid)
            border[0], border[-1], border[:, 0], border[:, -1] = True, True, True, True
            if (solid & border & (a[..., :3].max(-1) > 70)).any():
                problems.append('%s frame %d: something bright touches the edge of its frame (only the outline may)' % (name, k))
            if not solid.any():
                problems.append('%s frame %d is empty' % (name, k))
    for t, names in rests.items():
        if len(names) > 1:
            problems.append('rest alike (%d ticks): %s' % (t, ', '.join(names)))
    return problems


# ================================================================ previews

BG_DARK = (58, 64, 80)
BG_LIGHT = (206, 204, 198)
BACKDROPS = (('dark', BG_DARK), ('light', BG_LIGHT))
VANILLA = os.environ.get('TRADE_VANILLA', '')       # a folder holding the game's own item textures, for the side-by-side sheet

# the game's own item each of ours stands in for until it is used
STAND_IN = {'tally': 'book', 'tally_open': 'writable_book', 'carriers_warrant': 'paper', 'pack_harness': 'saddle',
            'drovers_whistle': 'lead', 'drovers_whistle_blow': 'goat_horn'}


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


def small_row(images, bg, size, zoom=3):
    """The images as they read small: each squeezed to [size] pixels (a box filter, as the game's mipmaps do), in a slot, then
    shown [zoom] times larger so it can be seen."""
    slot = size + 4
    out = Image.new('RGBA', (len(images) * slot + 4, slot + 4), (*bg, 255))
    d = ImageDraw.Draw(out)
    slot_bg = tuple(int(c * 0.8) for c in bg)
    for i, im in enumerate(images):
        x, y = 2 + i * slot, 2
        d.rectangle([x, y, x + slot - 2, y + slot - 2], fill=(*slot_bg, 255))
        out.alpha_composite(im.resize((size, size), Image.BOX) if size != im.width else im, (x + 1, y + 1))
    return out.resize((out.width * zoom, out.height * zoom), Image.NEAREST)


def gif(frames, path, bg, plays, scale=8):
    """A looping preview of an animation, each frame on the backdrop, played as the .mcmeta plays it ([plays] is [(frame, ticks)])."""
    rgb = []
    for i, _ in plays:
        f = frames[i]
        base = Image.new('RGBA', (f.width * scale, f.height * scale), (*bg, 255))
        base.alpha_composite(f.resize((f.width * scale, f.height * scale), Image.NEAREST))
        rgb.append(base.convert('RGB'))
    # One palette for every frame, and no palette optimising on save: frames that each carried their own palette came out in false
    # colours, as the saver kept one frame's palette for the others' indices
    sheet = Image.new('RGB', (rgb[0].width, rgb[0].height * len(rgb)))
    for k, im in enumerate(rgb):
        sheet.paste(im, (0, k * im.height))
    pal = sheet.quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    out = [im.quantize(palette=pal, dither=Image.Dither.NONE) for im in rgb]
    out[0].save(path, save_all=True, append_images=out[1:], duration=[50 * t for _, t in plays], loop=0, disposal=2, optimize=False)


def previews(made, outdir):
    """The sheets: every item large on the dark and on the light ground, then small as the game shows them, beside the game's own
    item where we have it, and a moving picture of each animated texture, played as its .mcmeta plays it."""
    os.makedirs(outdir, exist_ok=True)
    names = list(made)
    stills = [made[n][0] for n in names]
    for key, bg in BACKDROPS:
        tiles(stills, names, 8, 4, bg).save(os.path.join(outdir, 'trade_items_%s.png' % key))
    rows = []
    for key, bg in BACKDROPS:
        for size, zoom in ((32, 2), (16, 4), (8, 6)):
            rows.append(small_row(stills, bg, size, zoom))
    width = max(r.width for r in rows)
    sheet = Image.new('RGBA', (width, sum(r.height + 6 for r in rows)), (*BG_DARK, 255))
    y = 0
    for r in rows:
        sheet.alpha_composite(r, (0, y))
        y += r.height + 6
    sheet.save(os.path.join(outdir, 'trade_items_small.png'))
    if VANILLA:
        pairs, labels = [], []
        for n in names:
            v = STAND_IN.get(n)
            if v and os.path.exists(os.path.join(VANILLA, v + '.png')):
                vim = Image.open(os.path.join(VANILLA, v + '.png')).convert('RGBA')
                pairs += [vim.resize((32, 32), Image.NEAREST), made[n][0]]       # drawn at the same size on the screen
                labels += ['vanilla ' + v, n]
        if pairs:
            tiles(pairs, labels, 6, 4, BG_DARK).save(os.path.join(outdir, 'trade_items_beside_vanilla.png'))
    for n in names:
        if len(made[n]) > 1:
            gif(made[n], os.path.join(outdir, 'trade_item_%s.gif' % n), BG_DARK, playlist(STRIPS[n][1]), 10)


# ================================================================ main

def main(assets, previews_dir=None):
    made = write_items(assets)
    problems = verify(assets, made)
    if previews_dir:
        previews(made, previews_dir)
    print('trade_items: %d textures, %d item definitions' % (len(made), len(definitions())))
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  ' + p)
        sys.exit(1)
    print('  every definition has its models and textures; every strip fits its .mcmeta; no fully saturated pixel')


if __name__ == '__main__':
    args = sys.argv[1:]
    main(args[0] if args else 'src/main/resources/assets/guildmark', args[1] if len(args) > 1 else None)
