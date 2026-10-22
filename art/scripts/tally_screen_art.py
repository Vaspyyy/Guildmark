"""The Tally's screen art, at the HD scale with the art kit (hdkit.py): the open book the five pages are written in (an indigo leather
cover with brass corners and a notched page-block, two sheets of cream paper creased at the spine) and the small pictures the screen draws
on and round it (the leather tabs and their five icons, the rope and its knots, the knife that cuts a notch, a heading's flourish, the corners
a page is turned by). Build part B of the Trade design (living-world/trader/design.md, Part 1 and 12.5); the item's own art, the Haul Board's
chips and the rest of the trade's pictures are X1's (`trade_items.py`, `haul_board_art.py`) and are not made here.

    python3 -B art/scripts/tally_screen_art.py src/main/resources/assets/guildmark [preview-dir]

Writes

  * textures/gui/trade/tally_book.png: the whole open book, 716 x 452 texels (358 x 226 screen px: the book is 342 x 210 and a soft
    shadow of 8 px lies round it); client/trade/TallyStage.kt lays its pages on it, so its numbers (BOOK_W, BOOK_H, MARGIN, PAGE_W, PAGE_H,
    GAP) are the ones below and the two have to agree;
  * textures/gui/sprites/trade/tally_*.png: the little pictures, each at twice the size it is drawn at.

Everything is seeded: a rerun gives the same files. With a preview dir it also writes sheets: the book, and every sprite large on the
parchment and on the dark of the screen.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import (PAL, Canvas, grid, vnoise, fbm, rand, bands, poly, seg_dist, disc, rect, blur, shift, grow, rim, outline, tone, mix, edge)
from haul_board_icons import Pic

# ================================================================ the book's measures (screen px; a texel is half of one)

K = 2
BOOK_W, BOOK_H = 342, 210                # the cover
SHADOW = 8                               # the soft shadow round it, so the picture is (BOOK_W + 16) x (BOOK_H + 16)
MARGIN = 9                               # from the cover's edge to the paper: leather, then the page block's edge
LEATHER = 6                              # of which the leather's width (the other three are the page block's edge)
PAGE_W, PAGE_H = 158, 192
GAP = 8                                  # the spine between the two pages

assert MARGIN + PAGE_W + GAP + PAGE_W + MARGIN == BOOK_W
assert MARGIN + PAGE_H + MARGIN == BOOK_H

# ================================================================ the colours

INDIGO = [(10, 12, 24), (20, 26, 46), (32, 42, 72), (48, 62, 98), (68, 86, 124), (94, 112, 150)]
SILK = [(30, 6, 14), (66, 12, 24), (112, 22, 34), (158, 38, 46), (198, 66, 62), (232, 112, 94)]
BRASS = PAL['brass']
# Cream paper: a shade lighter and cleaner than the map's parchment (mean 223, 203, 159), so the ink reads on it
PAPER = [(190, 170, 126), (208, 188, 144), (222, 204, 160), (234, 218, 176), (244, 232, 194)]
EDGE = [(150, 132, 96), (190, 172, 130), (214, 198, 156), (236, 224, 186)]      # the page block's leaves
THREAD = [(70, 52, 38), (118, 92, 62), (170, 142, 98), (214, 190, 140)]
INK = (58, 38, 20)

SPRITES = None
WRITTEN = []


def save_texture(img, rel):
    path = os.path.join(ASSETS, 'textures', 'gui', 'trade', rel + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    WRITTEN.append(('texture ' + rel, img))


def save_sprite(img, name):
    path = os.path.join(ASSETS, 'textures', 'gui', 'sprites', 'trade', name + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    WRITTEN.append((name, img))


def smooth(t):
    t = np.clip(t, 0, 1)
    return t * t * (3 - 2 * t)


# ================================================================ the book

def book():
    W, H = (BOOK_W + 2 * SHADOW) * K, (BOOK_H + 2 * SHADOW) * K
    xs, ys = grid(W, H)
    cv = Canvas(W, H)
    ox, oy = SHADOW * K, SHADOW * K                      # the cover's corner, in texels
    cw, ch = BOOK_W * K, BOOK_H * K

    # --- the shadow: the cover's shape, blurred and dropped a little
    sh = rect(ox + 2, oy + 6, ox + cw - 2, oy + ch + 4, W, H).astype(float)
    sh = blur(blur(sh, 4), 4) * 0.62
    cv.rgb[:] = (6, 5, 10)
    cv.a = sh

    # --- the cover: indigo leather, grained, lit from the upper left
    cover = rect(ox, oy, ox + cw, oy + ch, W, H)
    corner = 7
    for cx, cy in ((ox, oy), (ox + cw, oy), (ox, oy + ch), (ox + cw, oy + ch)):
        dx = np.abs(xs - cx)
        dy = np.abs(ys - cy)
        cover &= ~((dx < corner) & (dy < corner) & (np.hypot(corner - dx, corner - dy) > corner))
    grain = fbm(W, H, 30, 11, 3) * 0.5 + rand(W, H, 12) * 0.5
    creases = fbm(W, H, 11, 13, 2)
    t = 0.44 + 0.2 * (grain - 0.5) + 0.12 * (creases - 0.5)
    # a lit edge on the upper left and a shaded one on the lower right, a few texels wide
    ex = np.minimum(xs - ox, ox + cw - xs)
    ey = np.minimum(ys - oy, oy + ch - ys)
    near = np.minimum(ex, ey)
    t += 0.2 * smooth(1 - near / 5.0) * np.where((xs - ox < ox + cw - xs) | (ys - oy < oy + ch - ys), 1, -1) * 0.8
    t -= 0.16 * smooth((xs - ox) / cw * 0.5 + (ys - oy) / ch * 0.5 - 0.35)
    cv.put(cover, bands(INDIGO, t, 0.5))

    # --- blind tooling: a line pressed into the leather, inset from the edge, and the stitching along it
    inset = 5
    ring = cover & ~rect(ox + inset + 1, oy + inset + 1, ox + cw - inset - 1, oy + ch - inset - 1, W, H) & rect(ox + inset, oy + inset, ox + cw - inset, oy + ch - inset, W, H)
    cv.rgb[ring] = tone(cv.rgb[ring], -0.35)
    ring2 = rect(ox + inset + 1, oy + inset + 1, ox + cw - inset - 1, oy + ch - inset - 1, W, H) & ~rect(ox + inset + 2, oy + inset + 2, ox + cw - inset - 2, oy + ch - inset - 2, W, H)
    cv.rgb[ring2] = tone(cv.rgb[ring2], 0.18)

    # --- the page block: its leaves show along the sides, the foot and the head of each page
    pg_l = MARGIN * K
    leaf_w = (MARGIN - LEATHER) * K
    block = rect(ox + LEATHER * K, oy + LEATHER * K, ox + cw - LEATHER * K, oy + ch - LEATHER * K, W, H)
    cv.put(block, (200, 182, 140))
    rng = np.random.default_rng(31)
    # leaves: the distance in from the block's edge decides the tone, striped like the ends of a thick stack of sheets
    bx0, by0 = ox + LEATHER * K, oy + LEATHER * K
    bx1, by1 = ox + cw - LEATHER * K, oy + ch - LEATHER * K
    din = np.minimum(np.minimum(xs - bx0, bx1 - xs), np.minimum(ys - by0, by1 - ys))
    stripes = ((din * 1.0) % 2.0) < 1.0
    lt = 0.62 + 0.18 * stripes + 0.12 * (fbm(W, H, 9, 5, 2) - 0.5)
    lt -= 0.22 * smooth(1 - din / 5.0) * 0.0
    cv.put(block, bands(EDGE, lt, 0.3))
    # shaded where the block meets the leather, lit on its upper and left edges
    inner = rect(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, W, H)
    cv.rgb[block & ~inner] = tone(cv.rgb[block & ~inner], -0.3)

    # --- the paper: two sheets, a spine between them
    p_x0 = ox + MARGIN * K
    p_y0 = oy + MARGIN * K
    pw = PAGE_W * K
    ph = PAGE_H * K
    gap = GAP * K
    left = rect(p_x0, p_y0, p_x0 + pw, p_y0 + ph, W, H)
    right = rect(p_x0 + pw + gap, p_y0, p_x0 + 2 * pw + gap, p_y0 + ph, W, H)
    spine_c = p_x0 + pw + gap / 2.0
    paper = left | right | rect(p_x0 + pw, p_y0, p_x0 + pw + gap, p_y0 + ph, W, H)
    fibres = fbm(W, H, 46, 21, 3)
    fine = rand(W, H, 22)
    streak = vnoise(W, H, 3.2, 23) * 0.5 + vnoise(W, H, 30, 24) * 0.5
    pt = 0.64 + 0.20 * (fibres - 0.5) + 0.06 * (fine - 0.5) + 0.05 * (streak - 0.5)
    # the sheet darkens toward its outer edges and its foot, and into the spine
    dedge = np.minimum(np.minimum(xs - p_x0, p_x0 + 2 * pw + gap - xs), np.minimum(ys - p_y0, p_y0 + ph - ys))
    pt -= 0.2 * smooth(1 - dedge / 22.0)
    pt -= 0.06 * smooth(1 - (p_y0 + ph - ys) / 70.0)
    dspine = np.abs(xs - spine_c)
    pt -= 0.32 * smooth(1 - (dspine - gap / 2.0) / 30.0)
    # foxing: a few faint stains, and one ring where a cup once stood
    for k in range(9):
        fx = p_x0 + rng.random() * (2 * pw + gap)
        fy = p_y0 + rng.random() * ph
        r = 5 + rng.random() * 9
        d = np.hypot(xs - fx, ys - fy) / r
        pt -= 0.09 * smooth(1 - d) * (0.5 + rng.random())
    rx, ry, rr = p_x0 + pw * 1.55, p_y0 + ph * 0.72, 30.0
    dd = np.abs(np.hypot(xs - rx, ys - ry) - rr)
    pt -= 0.06 * smooth(1 - dd / 2.5) * (0.6 + 0.4 * fbm(W, H, 6, 25, 2))
    cv.put(paper, bands(PAPER, pt, 0.45))
    # the crease: a dark valley in the spine, a lit edge on each page where it rises
    valley = rect(int(spine_c - 3), p_y0, int(spine_c + 3), p_y0 + ph, W, H)
    cv.rgb[valley] = tone(cv.rgb[valley], -0.28)
    cv.rgb[rect(int(spine_c - 1), p_y0, int(spine_c + 1), p_y0 + ph, W, H)] = (112, 88, 62)
    # the paper's outline against the page block
    pedge = (paper & ~rect(p_x0 + 1, p_y0 + 1, p_x0 + 2 * pw + gap - 1, p_y0 + ph - 1, W, H))
    cv.rgb[pedge] = tone(cv.rgb[pedge], -0.4)

    # --- the stitching of the binding: four threads across the spine
    for fy in (0.1, 0.37, 0.64, 0.91):
        y = int(p_y0 + ph * fy)
        for dx in range(-int(gap / 2) - 6, int(gap / 2) + 7):
            x = int(spine_c + dx)
            band = 0.5 + 0.2 * math.sin(dx * 0.9)
            for dy in (0, 1):
                cv.px(x, y + dy, (140, 112, 76) if dy == 0 else (84, 62, 42))
        cv.px(int(spine_c - gap / 2 - 7), y, (90, 66, 44))
        cv.px(int(spine_c + gap / 2 + 7), y, (90, 66, 44))

    # --- notches cut into the page block's foot and its outer side: the book is a tally
    for nx in range(bx0 + 40, bx1 - 30, 64):
        for j in range(3):
            w = 3 - j
            cv.rgb[rect(nx - w, by1 - 6 + j, nx + w + 1, by1 - 5 + j, W, H) & block] = (46, 34, 26)
    for ny in range(by0 + 40, by1 - 30, 64):
        for j in range(3):
            w = 3 - j
            cv.rgb[rect(bx1 - 6 + j, ny - w, bx1 - 5 + j, ny + w + 1, W, H) & block] = (46, 34, 26)
            cv.rgb[rect(bx0 + 5 - j, ny - w, bx0 + 6 - j, ny + w + 1, W, H) & block] = (46, 34, 26)

    # --- brass corners
    cap = 30
    for sx, sy, cx, cy in ((1, 1, ox, oy), (-1, 1, ox + cw, oy), (1, -1, ox, oy + ch), (-1, -1, ox + cw, oy + ch)):
        dx = (xs - cx) * sx
        dy = (ys - cy) * sy
        m = (dx >= 0) & (dy >= 0) & (dx + dy < cap + 8) & (dx < cap) & (dy < cap) & cover
        # the cap's own shape: the cover's corner with a pointed inward edge
        h = (dx + dy) / (cap + 8)
        tt = 0.55 + 0.25 * (1 - smooth(h)) - 0.12 * smooth(np.maximum(dx, dy) / cap) + 0.1 * (fbm(W, H, 5, 41, 2) - 0.5)
        cv.put(m, bands(BRASS, tt, 0.4))
        e = m & ~(shift(m, 1, 0) & shift(m, 0, 1) & shift(m, -1, 0) & shift(m, 0, -1))
        cv.rgb[e] = tone(cv.rgb[e], -0.4)
        lit = m & ~shift(m, sx, 0) | m & ~shift(m, 0, sy)
        cv.rgb[lit & ~e] = tone(cv.rgb[lit & ~e], 0.3)
        # a rivet
        rr = disc(cx + sx * 9, cy + sy * 9, 3.0, W, H)[0]
        cv.put(rr, BRASS[4])
        cv.px(int(cx + sx * 9 - 1), int(cy + sy * 9 - 1), BRASS[5])
        cv.rgb[disc(cx + sx * 9 + 1, cy + sy * 9 + 1, 3.0, W, H)[0] & ~rr] = BRASS[1]

    # --- the dark line round all of it
    cv.a = np.maximum(cv.a, 0)
    solid = cv.a > 0.6
    solid_cover = cover | block
    ring_o = grow(solid_cover) & ~solid_cover
    cv.rgb[ring_o] = (8, 8, 14)
    cv.a[ring_o] = 1.0
    cv.a[solid_cover] = 1.0
    return cv.image()


# ================================================================ the tabs

TAB_W, TAB_H = 16, 24


def erode(m, n):
    """The mask [m] with its outer [n] pixels taken off (four-neighbour steps)."""
    m = m.astype(bool)
    for _ in range(n):
        s = np.zeros_like(m)
        s[1:-1, 1:-1] = m[1:-1, 1:-1] & m[:-2, 1:-1] & m[2:, 1:-1] & m[1:-1, :-2] & m[1:-1, 2:]
        m = s
    return m


def tab(lit):
    """A leather tab, 16 x 24 px at 2x, that sticks out of the book's right edge: indigo like the cover, bound round its outer edge in brass with
    a line of stitches inside it, the outer end rounded; the brass brighter and the leather paler when it is the page the book is open at."""
    W, H = TAB_W * K, TAB_H * K
    p = Pic(W, H)
    xs, ys = p.xs, p.ys
    r = 11.0
    m = np.ones((H, W), bool)
    m &= ~((xs > W - r) & (ys < r) & (np.hypot(xs - (W - r), ys - r) > r))
    m &= ~((xs > W - r) & (ys > H - r) & (np.hypot(xs - (W - r), ys - (H - r)) > r))
    grain = fbm(W, H, 7, 61 + int(lit), 3) * 0.5 + rand(W, H, 62) * 0.5
    t = (0.5 if lit else 0.4) + 0.22 * (grain - 0.5) - 0.1 * smooth(xs / W) + 0.1 * (1 - smooth(ys / (H * 0.6)))
    p.cv.put(m, bands(INDIGO, t, 0.45))
    # the brass binding: three texels round the outer edge, lit on top, dark on its inner side
    band = m & ~erode(m, 3)
    bt = 0.7 + 0.22 * (1 - ys / H) - 0.1 * smooth(xs / W) + (0.12 if lit else -0.08)
    p.cv.put(band, bands(BRASS, bt, 0.3))
    inner = erode(m, 3) & ~erode(m, 4)
    p.cv.rgb[inner] = tone(p.cv.rgb[inner], -0.5)
    # stitches a little inside it
    stitch = erode(m, 6) & ~erode(m, 7) & (((xs + ys) % 4.0) < 2.0)
    p.cv.put(stitch, THREAD[3] if lit else THREAD[2])
    e = m & ~erode(m, 1)
    p.cv.rgb[e] = tone(p.cv.rgb[e], -0.55)
    return p.cv.image()


def icon(draw):
    """An icon 10 x 10 px at 2x, cut in brass on a dark ground with a fine dark outline: [draw] paints it on a Pic."""
    p = Pic(20)
    draw(p)
    return p.finish(0.8, 0.2)


def word_icon(p):
    # a town's ring with a dot in it, and a flag on a stick beside: the map of towns
    p.flat(p.D(9.5, 10.5, 7.2) & ~p.D(9.5, 10.5, 5.2), BRASS, 0.9)
    p.flat(p.D(9.5, 10.5, 2.0), BRASS, 0.78)
    p.flat(p.L(15.5, 3.0, 15.5, 10.5, 1.2), BRASS, 0.5)
    p.flat(p.P([(15.5, 3.0), (19.0, 4.4), (15.5, 6.0)]), BRASS, 0.85)


def routes_icon(p):
    # a signpost: a post and two boards pointing opposite ways
    p.flat(p.R(9, 2, 11.4, 19), BRASS, 0.5)
    p.flat(p.P([(3, 4), (13.5, 4), (16.6, 6.6), (13.5, 9.2), (3, 9.2)]), BRASS, 0.9)
    p.flat(p.P([(17, 10.6), (6.5, 10.6), (3.4, 13.2), (6.5, 15.8), (17, 15.8)]), BRASS, 0.7)


def held_icon(p):
    # a hung ticket with an eyelet and a notched foot
    p.flat(p.R(4.5, 2.5, 15.5, 17.5), BRASS, 0.92)
    p.flat(p.D(10, 5.4, 1.5), PAL['darkwood'], 0.1)
    for x in (6.5, 10.0, 13.5):
        p.flat(p.P([(x - 1.4, 17.5), (x + 1.4, 17.5), (x, 14.9)]), PAL['darkwood'], 0.1)
    p.flat(p.R(6.5, 8.0, 13.5, 8.9) | p.R(6.5, 10.6, 12.0, 11.5), PAL['darkwood'], 0.2)


def ledger_icon(p):
    # a tally stick laid slantwise, notched
    p.flat(p.path([(2.5, 17.0), (17.0, 3.0)], 3.6), BRASS, 0.88)
    for k in range(4):
        t = 0.28 + k * 0.18
        x = 2.5 + (17.0 - 2.5) * t
        y = 17.0 - (17.0 - 3.0) * t
        p.flat(p.path([(x - 0.8, y - 2.0), (x + 1.8, y + 0.6)], 1.2), PAL['darkwood'], 0.12)


def standing_icon(p):
    # a knot in a rope: the rope runs down through a round knot, bound in turns
    p.flat(p.path([(10, 0.5), (10, 19.5)], 3.2), BRASS, 0.5)
    p.flat(p.path([(9.2, 0.5), (9.2, 19.5)], 1.0), BRASS, 0.82)
    ball = p.E(10, 10, 6.2, 5.6)
    p.round(ball, BRASS, 8, 8, 6.5, 0.6, 0.36, 0.0)
    for k in (-1, 0, 1):
        turn = p.path([(4.6, 7.4 + k * 3.0), (10, 9.6 + k * 3.0), (15.4, 7.4 + k * 3.0)], 1.1) & ball
        p.flat(turn, BRASS, 0.18)
        p.flat(p.path([(4.8, 6.4 + k * 3.0), (10, 8.6 + k * 3.0), (15.2, 6.4 + k * 3.0)], 0.9) & ball, BRASS, 0.95)


ICONS = {'word': word_icon, 'routes': routes_icon, 'held': held_icon, 'ledger': ledger_icon, 'standing': standing_icon}

# ================================================================ the rope and its knots


HEMP = [(34, 30, 28), (66, 60, 54), (104, 96, 84), (144, 134, 114), (184, 172, 148), (214, 202, 176)]


def twist(xs, ys, w, cx):
    """The tone of a twisted rope `w` texels across with its middle at `cx`, its strands slanting eight texels to a turn (so lengths 8, 16 or 32 tall stack into one rope)."""
    across = (xs - cx) / (w / 2.0 - 0.5)
    phase = ((ys + xs * 0.95) / 8.0) % 1.0
    return 0.16 + 0.5 * np.sin(phase * math.pi) + 0.2 * (1 - across ** 2) - 0.1 * across


def rope():
    """A length of rope, 6 x 16 px at 2x, twisted: its strands run in a slant and the next length begins where this one ends, so a stack of them is one rope."""
    W, H = 12, 32
    p = Pic(W, H)
    body = rect(1, 0, W - 1, H, W, H)
    t = twist(p.xs, p.ys, W - 2, W / 2.0) + 0.06 * (rand(W, H, 71) - 0.5)
    p.cv.put(body, bands(THREAD, t, 0.3))
    side = body & ~shift(body, 1, 0)
    p.cv.rgb[side] = tone(p.cv.rgb[side], 0.2)
    side2 = body & ~shift(body, -1, 0)
    p.cv.rgb[side2] = tone(p.cv.rgb[side2], -0.5)
    return p.cv.image()


def knot(tied):
    """A knot, 14 x 14 px at 2x, on a rope that runs down through it: a ball of cord wound in slanting turns, in gold thread when the rung is held and in grey hemp when it is not."""
    W = 28
    p = Pic(W)
    xs, ys = p.xs, p.ys
    ramp = PAL['gold'] if tied else HEMP
    cx = cy = 14.0
    # the rope going in at the top and out at the foot
    stub = rect(8, 0, 20, W, W, W)
    p.cv.put(stub, bands(ramp, twist(xs, ys, 12, 14.0) - 0.1, 0.3))
    # the knot: a ball, the cord laid round it in turns that curve with it
    rx, ry = 12.5, 11.0
    ball = p.E(cx, cy, rx, ry)
    u = (xs - cx) / rx
    v = (ys - cy) / ry
    dome = np.sqrt(np.clip(1 - u * u - v * v, 0, 1))
    around = np.arctan2(u, np.maximum(dome, 0.05))                 # how far round the ball a point is, seen from the front
    turn = (v * 2.1 + around * 0.62 + 4.0) % 1.0
    lit = np.clip(-(u + v) * 0.5, -1, 1)
    t = 0.34 + 0.4 * np.sin(turn * math.pi) + 0.3 * lit - 0.2 * (1 - dome)
    p.cv.put(ball, bands(ramp, t, 0.2))
    groove = ball & (turn < 0.1)
    p.cv.rgb[groove] = tone(p.cv.rgb[groove], -0.45)
    if tied:
        for (x, y) in ((10, 9), (11, 9), (10, 10)):
            p.px(x, y, (255, 244, 206))
    return p.finish(0.8, 0.2)


# ================================================================ the knife

def knife():
    """A small knife, 16 x 8 px at 2x, its blade to the left: for the cut of a notch."""
    W, H = 32, 16
    p = Pic(W, H)
    blade = p.P([(0.5, 12.0), (3.5, 5.6), (17.0, 4.4), (17.0, 9.6), (5.0, 10.4)])
    p.cel(blade, PAL['steel'], 0.7, 0.28, 0.24)
    p.flat(p.path([(2.5, 11.0), (17.0, 9.3)], 1.0) & blade, PAL['steel'], 0.2)
    p.flat(p.path([(4.0, 6.4), (15.0, 5.5)], 0.9) & blade, PAL['steel'], 0.98)
    p.flat(p.R(17, 3.4, 19.4, 10.8), PAL['brass'], 0.7)
    p.cel(p.P([(19.4, 4.2), (30.6, 4.6), (31.4, 8.0), (30.6, 11.4), (19.4, 10.6)]), PAL['wood'], 0.6, 0.2, 0.22)
    p.flat(p.D(26.0, 7.6, 0.9), PAL['brass'], 0.8)
    return p.finish(0.8, 0.2)


# ================================================================ a heading's flourish and a page's corners

def flourish():
    """A hand-inked flourish, 64 x 6 px at 2x, to lay under a heading: a thin line that swells to a leaf at either end of a small diamond, in the map's ink."""
    W, H = 128, 12
    cv = Canvas(W, H)
    xs, ys = grid(W, H)
    cx = W / 2.0
    line = np.abs(ys - 6.0 + 0.4 * np.sin(xs * 0.11)) < (0.55 + 0.8 * smooth(1 - np.abs(xs - cx) / 60.0))
    line &= np.abs(xs - cx) < 58
    dia = (np.abs(xs - cx) + np.abs(ys - 6.0) * 1.8) < 5.0
    leaf_l = (np.hypot(xs - 20.0, (ys - 6.0) * 1.8) < 6.5) & (xs < cx - 8)
    leaf_r = (np.hypot(xs - (W - 20.0), (ys - 6.0) * 1.8) < 6.5) & (xs > cx + 8)
    m = line | dia | leaf_l | leaf_r
    ink = np.array(INK, float)
    cv.put(m, ink)
    cv.a[m] = 0.88
    cv.a[m & (rand(W, H, 81) > 0.93)] = 0.5
    return cv.image()


def curl(side):
    """A page's lower corner turned up (a dog-ear), 12 x 12 px at 2x: the corner folded over along a diagonal with its paler back showing, the sheet under
    the missing corner shadowed along the fold, and the flap's own shadow on the page ([side] 0 the left page's lower left corner, 1 the right page's lower right)."""
    W = 24
    p = Pic(W)
    xs, ys = p.xs, p.ys
    s = 15.0                                         # the leg of the corner that is turned, in texels
    c = 2.0 * W - s                                  # the fold lies along x + y = c
    d = xs + ys
    flap = (xs >= W - s) & (ys >= W - s) & (d <= c)
    under = d > c
    # the page's own shadow past the flap's two free edges, soft, a few texels wide
    near = np.hypot(np.maximum(0.0, (W - s) - xs), np.maximum(0.0, (W - s) - ys))
    cast = (~flap) & (~under) & (near < 5.0) & (xs > W - s - 5) & (ys > W - s - 5)
    p.cv.rgb[cast] = (36, 24, 14)
    p.cv.a[cast] = 0.42 * smooth(1 - near[cast] / 5.0)
    # the sheet beneath: paper in shade, darkest at the fold
    u = np.clip((d - c) / s, 0, 1)
    p.cv.put(under, bands(PAPER, 0.3 - 0.12 * (1 - u), 0.3))
    # the flap: the back of the leaf, pale, a little darker at the fold, lighter toward the tip
    v = np.clip((c - d) / s, 0, 1)
    p.cv.put(flap, bands(PAPER, 0.58 + 0.34 * v, 0.3))
    e = flap & ~erode(flap, 1)
    p.cv.rgb[e] = (66, 48, 32)
    foldline = flap & (d > c - 2.4)
    p.cv.rgb[foldline] = tone(p.cv.rgb[foldline], -0.35)
    tip = flap & (d < c - s + 3.0) & (xs > W - s + 1) & (ys > W - s + 1)
    p.cv.rgb[tip] = tone(p.cv.rgb[tip], 0.25)
    img = p.cv.image()
    if side == 0:
        img = img.transpose(Image.FLIP_LEFT_RIGHT)
    return img


# ================================================================ writing it all

def sheet(images, scale, bg, cols, pad=8):
    cw = max(i.width for i in images) * scale
    ch = max(i.height for i in images) * scale
    rows = (len(images) + cols - 1) // cols
    out = Image.new('RGBA', (cols * (cw + pad) + pad, rows * (ch + pad) + pad), bg)
    for n, im in enumerate(images):
        x = pad + (n % cols) * (cw + pad)
        y = pad + (n // cols) * (ch + pad)
        out.alpha_composite(im.resize((im.width * scale, im.height * scale), Image.NEAREST), (x, y))
    return out


def main(assets, preview=None):
    global ASSETS
    ASSETS = assets
    b = book()
    save_texture(b, 'tally_book')
    for lit in (False, True):
        save_sprite(tab(lit), 'tally_tab_on' if lit else 'tally_tab')
    for name, fn in ICONS.items():
        save_sprite(icon(fn), 'tally_ico_' + name)
    save_sprite(rope(), 'tally_rope')
    save_sprite(knot(True), 'tally_knot')
    save_sprite(knot(False), 'tally_knot_off')
    save_sprite(knife(), 'tally_knife')
    save_sprite(flourish(), 'tally_flourish')
    for side in (0, 1):
        save_sprite(curl(side), 'tally_curl_%d' % side)
    if preview:
        os.makedirs(preview, exist_ok=True)
        dark = Image.new('RGBA', b.size, (14, 13, 18, 255))
        dark.alpha_composite(b)
        dark.save(os.path.join(preview, 'tally_book.png'))
        parchment = Image.open(os.path.join(assets, 'textures', 'gui', 'map', 'parchment.png')).convert('RGBA')
        smalls = [im for name, im in WRITTEN if not name.startswith('texture')]
        for label, bg in (('on_parchment', None), ('on_dark', (24, 22, 28, 255))):
            s = sheet(smalls, 5, bg or (0, 0, 0, 0), 8)
            if bg is None:
                tile = Image.new('RGBA', s.size)
                for y in range(0, s.size[1], 256):
                    for x in range(0, s.size[0], 256):
                        tile.paste(parchment, (x, y))
                tile.alpha_composite(s)
                s = tile
            s.save(os.path.join(preview, 'tally_sprites_%s.png' % label))
    print('wrote', ', '.join(n for n, _ in WRITTEN))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
