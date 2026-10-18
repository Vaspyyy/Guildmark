"""The grand festivals' art: everything the decorations, the prizes and the sky lanterns are drawn with.

    python3 -B art/scripts/festival_art.py [assets dir [preview dir]]

With no arguments it writes into the repo's assets (`src/main/resources/assets/guildmark`). It is painted with
`hdkit.py` like the rest of the 32x art, and seeded, so a rerun gives the same files.

- The ornaments' atlas, `textures/entity/festival/ornaments.png`. The renderer (client/festival/
  FestivalOrnamentRenderer.kt) declares its regions on a 256x256 sheet; the sheet is painted and shipped at 512x512,
  so every region is 2x. The pale pieces (pennant cloth, cord, ribbon, bows, the glow, the lanterns' glass and paper)
  are near white, because the renderer tints them with each festival's colours. The rest (wood, iron, gilt, the
  banners, the garlands' flowers and leaves, the sun wheel, the star and the sheaves) is painted in its own colours.
- The Ribbonwake's maypole, `textures/block/festival_pole_birch.png` (32x32): white paint with green and pink
  ribbons wound round it. Each face of the pole's model takes a quarter of the texture, in order round the pole, so
  the stripes run on unbroken from face to face and from one block of the pole to the next. The oak and spruce
  poles use the vanilla logs.
- The four prizes (32x items), the Ribbon Crown as worn (`textures/entity/equipment/humanoid/ribbon_crown.png`:
  the armour layout is declared at 64x32 and painted at 128x64) and the Lanternrise's sky lantern particle (16x16,
  four frames of its flame).
- The JSON that points at them: the trim's blockstate (each look to its model: the poles here, the rest the
  vanilla blocks'); the ornament's blockstate (a model with only a particle texture, because its renderer draws
  it); the poles' models; the prizes' item models and definitions; the crown's equipment asset; and the particle
  definition.

With a preview dir it also writes `festival_art.png`, a contact sheet of all of it, magnified.
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
from hdkit import (PAL, Canvas, grid, vnoise, fbm, rand, bands, disc, ellipse, seg_dist, path_dist, poly, blur, light,
                   outline, glint, tone, mix, grow, edge, paste, sheet)

S = 2          # the atlas is painted at twice the size the renderer declares
N = 32         # items and the pole

ALL = None


def full(n, m=None):
    return np.ones((m or n, n), bool)


def pale(v, warm=(255, 251, 242)):
    """A grey value (0..1) as a pale colour, for what the game tints."""
    v = np.clip(np.asarray(v, float), 0, 1)
    return v[..., None] * np.asarray(warm, float)


def lit_side(xs, ys, cx, cy, r):
    """-1..1 across a round shape: +1 toward the light at the upper left."""
    return np.clip((-(xs - cx) - (ys - cy)) / (1.4 * r), -1, 1)


# ================================================================ small shapes

def leaf_mask(xs, ys, x0, y0, x1, y1, width, spikes=0):
    """A pointed leaf from (x0, y0) to (x1, y1), [width] at its widest; its mask, how far along it each pixel
    is, and which side of the midrib (1 or -1)."""
    d, t = seg_dist(x0, y0, x1, y1, xs.shape[1], xs.shape[0])
    half = width / 2 * np.sin(np.clip(t, 0, 1) * math.pi) ** 0.75
    if spikes:
        half = half * (0.7 + 0.3 * np.abs(np.sin(t * math.pi * spikes)))
    side = np.sign((x1 - x0) * (ys - y0) - (y1 - y0) * (xs - x0))
    return d < half, t, side, d / np.maximum(half, 1e-3)


def paint_leaf(cv, xs, ys, x0, y0, x1, y1, width, pal, base=0.5, spikes=0):
    m, t, side, across = leaf_mask(xs, ys, x0, y0, x1, y1, width, spikes)
    # The side turned to the light (upper left) lighter, a darker midrib, darker toward the tip
    nx, ny = -(y1 - y0), (x1 - x0)
    facing = 1 if (nx * -1 + ny * -1) > 0 else -1
    k = base + 0.18 * (side == facing) - 0.12 * (side != facing) - 0.1 * t + 0.08 * (1 - across)
    k = np.where((across < 0.18) & (t > 0.08) & (t < 0.9), k - 0.22, k)
    cv.put(m, bands(pal, k))
    return m


def paint_flower(cv, xs, ys, cx, cy, r, petals, petal, centre, rot=0.0, cup=0.0):
    """A flower seen from the front: [petals] rounded petals in [petal] tones, a [centre] in the middle."""
    dx, dy = xs - cx, ys - cy
    d = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx) - rot
    wave = np.abs(np.cos(ang * petals / 2))
    m = d < r * (0.6 + 0.4 * wave ** 0.6)
    crease = wave < 0.22
    k = 0.62 + 0.25 * lit_side(xs, ys, cx, cy, r) - 0.18 * crease + 0.12 * (d / r) - cup * (d / r)
    cv.put(m, bands(petal, k))
    c = d < r * 0.32
    cv.put(c, bands(centre, 0.55 + 0.35 * lit_side(xs, ys, cx, cy, r * 0.32)))
    return m | c


def star_points(cx, cy, r_out, r_in, points, rot=-math.pi / 2):
    pts = []
    for i in range(points * 2):
        a = rot + i * math.pi / points
        r = r_out if i % 2 == 0 else r_in
        pts.append((cx + math.cos(a) * r, cy + math.sin(a) * r))
    return pts


# Petal and leaf tones (darkest first), for the garlands, banners and prizes
BLOSSOM = [(120, 60, 86), (186, 104, 140), (226, 150, 182), (246, 196, 216), (255, 232, 240), (255, 248, 250)]
WHITE_PETAL = [(110, 96, 110), (176, 166, 176), (222, 214, 220), (244, 240, 240), (255, 252, 248)]
YELLOW = [(110, 70, 20), (176, 124, 34), (226, 180, 58), (248, 216, 98), (255, 240, 170)]
MARIGOLD = [(110, 40, 12), (176, 76, 18), (226, 120, 30), (246, 164, 46), (255, 204, 96), (255, 232, 160)]
GREEN = [(22, 44, 26), (38, 74, 36), (62, 108, 48), (94, 144, 64), (136, 180, 90), (180, 214, 130)]
DARK_GREEN = [(12, 28, 20), (22, 46, 30), (34, 68, 40), (52, 92, 52), (78, 118, 66)]
MAPLE = [(70, 16, 12), (132, 32, 20), (186, 58, 28), (222, 98, 40), (242, 146, 64), (252, 196, 110)]
BERRY = [(54, 8, 14), (112, 18, 24), (170, 34, 34), (214, 64, 52), (246, 130, 110), (255, 210, 196)]
STRAW = [(72, 46, 20), (120, 82, 34), (168, 124, 52), (206, 164, 76), (232, 198, 112), (248, 228, 162)]
PINE = [(10, 24, 22), (18, 40, 32), (28, 58, 42), (42, 78, 54), (62, 100, 66), (92, 128, 84)]


# ================================================================ the atlas: pale pieces the game tints

def cloth():
    """The pennants' cloth: a fine weave, the hem folded over the cord with its stitching, and the hems down the
    two slanted edges (each pennant is the triangle with the top edge's corners and the bottom middle)."""
    n = 16 * S
    xs, ys = grid(n)
    weave = ((np.floor(xs) + np.floor(ys)) % 2) * 0.035
    v = 0.9 + weave + 0.05 * (fbm(n, n, 5, 302) - 0.5) + 0.02 * (rand(n, n, 301) - 0.5) - 0.08 * (ys / n)
    v = np.where(ys < 4, v - 0.12 + 0.06 * (ys < 1.5), v)
    v = np.where((np.abs(ys - 5.2) < 0.55) & (np.floor(xs) % 4 < 2), v - 0.14, v)
    d1, _ = seg_dist(0, 0, n / 2, n, n)
    d2, _ = seg_dist(n, 0, n / 2, n, n)
    v = np.where(np.minimum(d1, d2) < 1.6, v - 0.08, v)
    cv = Canvas(n)
    cv.put(full(n), pale(v))
    return cv


def rope():
    """Twisted cord: its strands running along it (the renderer runs the texture's width along the cord), round
    across it."""
    n = 16 * S
    xs, ys = grid(n)
    across = ys / n
    round_ = np.sin(across * math.pi)
    twist = 0.5 + 0.5 * np.cos((xs + ys * 0.9) * 2 * math.pi / 7.0)
    v = 0.45 + 0.35 * round_ + 0.18 * twist * round_ + 0.04 * (rand(n, n, 311) - 0.5) - 0.1 * (across > 0.62)
    cv = Canvas(n)
    cv.put(full(n), pale(v, (250, 244, 232)))
    return cv


def ribbon():
    """Satin ribbon: a sheen along it a little above the middle, hemmed edges, faint folds."""
    n = 16 * S
    xs, ys = grid(n)
    t = ys / n
    sheen = np.exp(-((t - 0.34) / 0.13) ** 2)
    v = 0.82 + 0.16 * sheen - 0.1 * np.clip((t - 0.55) / 0.45, 0, 1) + 0.05 * (fbm(n, n, 10, 321) - 0.5)
    v = np.where((t < 0.08) | (t > 0.92), v - 0.12, v)
    cv = Canvas(n)
    cv.put(full(n), pale(v))
    return cv


def halo():
    """A soft round glow, white: the renderer tints it and fades it with the dark."""
    n = 16 * S
    xs, ys = grid(n)
    d = np.hypot(xs - n / 2, ys - n / 2) / (n / 2)
    cv = Canvas(n)
    cv.rgb[:] = 255
    cv.a = np.clip(1 - d, 0, 1) ** 2.0
    return cv


def flame():
    """A candle flame: a white core, gold round it, an orange edge that fades out; pointed at the top."""
    n = 16 * S
    xs, ys = grid(n)
    cx, cy, tip, base, rx = n / 2, 20.0, 2.0, 28.0, 7.0
    above = ys < cy
    w = np.where(above, rx * np.clip((ys - tip) / (cy - tip), 0, 1) ** 0.75, rx)
    hy = np.where(above, cy - tip, base - cy)
    q = np.sqrt(((xs - cx) / np.maximum(w, 0.05)) ** 2 + ((ys - cy) / hy) ** 2)
    core = (255, 252, 236)
    mid = (255, 214, 120)
    rim = (255, 140, 54)
    col = np.where((q < 0.45)[..., None], np.array(core, float),
                   np.where((q < 0.78)[..., None], mix(mid, core, np.clip((0.78 - q) / 0.33, 0, 1) * 0.4), np.array(rim, float)))
    cv = Canvas(n)
    cv.rgb = np.broadcast_to(col, (n, n, 3)).copy()
    cv.a = np.clip((1.12 - q) / 0.3, 0, 1)
    return cv


def tag():
    """The riddle's slip: a card of old paper hung by its top, a few lines of writing on it."""
    n = 16 * S
    xs, ys = grid(n)
    wob = (vnoise(n, n, 3, 331) - 0.5) * 1.6
    card = (xs > 3.5 + wob) & (xs < 28.5 + wob) & (ys > 2.5) & (ys < 29.5 + wob)
    hole = np.hypot(xs - 16, ys - 6.5) < 1.6
    curl = np.clip((ys - 22) / 8, 0, 1)
    v = 0.66 + 0.1 * (fbm(n, n, 6, 332) - 0.5) - 0.15 * curl * (xs > 22) + 0.08 * (xs < 8)
    cv = Canvas(n)
    cv.put(card & ~hole, bands(PAL['parchment'], v))
    ring = (np.hypot(xs - 16, ys - 6.5) < 2.6) & ~hole & card
    cv.put(ring, bands(PAL['parchment'], v - 0.25))
    for i, y0 in enumerate((11.5, 15.5, 19.5, 23.5)):
        wave = 0.8 * np.sin(xs * 1.15 + i * 2.1)
        gaps = np.sin(xs * 0.62 + i * 1.7) > 0.86
        end = 26 - (i * 3) % 7
        ink = card & (np.abs(ys - y0 - wave * 0.5) < 0.6) & (xs > 6.5) & (xs < end) & ~gaps
        cv.put(ink, bands(PAL['ink'], np.full((n, n), 0.6)))
    # The twine through the hole, up over the top edge
    tw, _ = seg_dist(16, 0, 16, 5.5, n)
    cv.put(tw < 0.9, (64, 48, 32))
    return cv


def bow():
    """A ribbon bow, pale (tinted in the game): two loops, a knot and two short notched tails."""
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    v = np.zeros((n, n))
    shape = np.zeros((n, n), bool)
    # Tails first (under the loops)
    for sx in (-1, 1):
        d, t = seg_dist(16, 15, 16 + sx * 8, 29, n)
        tail = d < 2.0 + 0.6 * t
        notch_c = (16 + sx * 8, 30.5)
        notch = np.hypot(xs - notch_c[0], (ys - notch_c[1]) * 1.4) < 2.2
        tail &= ~notch
        shape |= tail
        v = np.where(tail, 0.72 + 0.12 * (sx < 0) - 0.15 * t, v)
    for sx in (-1, 1):
        cx = 16 + sx * 7.5
        m, h = ellipse(cx, 12, 7.2, 6.0, n)
        hole, _ = ellipse(cx + sx * 1.0, 12.4, 2.8, 2.0, n)
        loop = m & ~hole
        shape |= loop
        k = 0.8 + 0.18 * lit_side(xs, ys, cx, 12, 7) - 0.22 * (np.abs(xs - 16) < 4) + 0.1 * h
        v = np.where(loop, k, v)
    knot, kh = ellipse(16, 13, 3.2, 3.8, n)
    shape |= knot
    v = np.where(knot, 0.7 + 0.25 * kh + 0.1 * lit_side(xs, ys, 16, 13, 3), v)
    cv.put(shape, pale(v))
    outline(cv, 0.45)
    return cv


# ================================================================ the atlas: its own colours

def wood():
    """Turned wood for rods, stakes and the wheel's axle: grain running up it."""
    n = 16 * S
    xs, ys = grid(n)
    wav = vnoise(n, n, 8, 341)
    lines = 0.5 + 0.5 * np.sin(xs * 1.9 + 4.0 * wav)
    knot = np.exp(-(((xs - 21) / 2.2) ** 2 + ((ys - 9) / 3.0) ** 2))
    v = 0.58 + 0.2 * lines + 0.08 * (fbm(n, n, 4, 342) - 0.5) - 0.3 * knot
    cv = Canvas(n)
    cv.put(full(n), bands(PAL['wood'], v))
    return cv


def iron():
    n = 16 * S
    xs, ys = grid(n)
    hammer = fbm(n, n, 3, 351)
    scratch = (np.abs(np.sin(xs * 0.9 + ys * 2.3)) > 0.985) * 0.15
    v = 0.42 + 0.25 * (hammer - 0.5) + scratch
    cv = Canvas(n)
    cv.put(full(n), bands(PAL['iron'], v))
    return cv


def gold():
    n = 16 * S
    xs, ys = grid(n)
    gleam = 0.5 + 0.5 * np.sin((xs - ys) * 0.22)
    v = 0.5 + 0.28 * gleam + 0.1 * (fbm(n, n, 4, 361) - 0.5)
    cv = Canvas(n)
    cv.put(full(n), bands(PAL['gold'], v))
    return cv


def spark():
    """A four-point sparkle, white, soft."""
    n = 16 * S
    xs, ys = grid(n)
    dx, dy = np.abs(xs - n / 2), np.abs(ys - n / 2)
    a = np.maximum(np.exp(-dx / 0.9 - (dy / 7.0) ** 2), np.exp(-dy / 0.9 - (dx / 7.0) ** 2))
    a = np.maximum(a, np.exp(-(dx * dx + dy * dy) / 6.0))
    cv = Canvas(n)
    cv.rgb[:] = 255
    cv.a = np.clip(a * 1.1, 0, 1)
    return cv


def gold_ball():
    """The gilt ball on the ribbon pole's top."""
    n = 16 * S
    xs, ys = grid(n)
    m, h = disc(n / 2, n / 2, 13.0, n)
    cv = Canvas(n)
    cv.put(m, bands(PAL['gold'], 0.5 + 0.9 * light(h * 6, 1.0) + 0.12 * h))
    glint(cv, 11, 11, 2)
    return cv


def glass():
    """A lantern's pane: an iron frame with a bar across, warm glass the flame shows through (half seen through:
    the renderer draws the glass with the glow, from both sides)."""
    w, h = 16 * S, 24 * S
    xs, ys = grid(w, h)
    frame = (xs < 3) | (xs > w - 3) | (ys < 3) | (ys > h - 3) | (np.abs(ys - h / 2) < 1.5)
    cv = Canvas(w, h)
    streak = 0.5 + 0.5 * np.sin(xs * 0.7 + 2.0 * vnoise(w, h, 6, 371))
    centre = np.exp(-(((xs - w / 2) / 9) ** 2 + ((ys - h / 2) / 13) ** 2))
    cv.put(~frame, pale(0.86 + 0.08 * streak + 0.06 * centre, (255, 246, 226)), alpha=0.32 + 0.12 * streak + 0.2 * centre)
    cv.put(frame, bands(PAL['iron'], 0.45 + 0.25 * (xs < 3) + 0.2 * (ys < 3) + 0.1 * (fbm(w, h, 3, 372) - 0.5)))
    for cx, cy in ((1.5, 1.5), (w - 1.5, 1.5), (1.5, h - 1.5), (w - 1.5, h - 1.5), (1.5, h / 2), (w - 1.5, h / 2)):
        r, _ = disc(cx, cy, 1.3, w, h)
        cv.put(r, bands(PAL['brass'], np.full((h, w), 0.8)))
    return cv


def paper():
    """A paper lantern's panel: thin paper lit from inside, a seam at each side and the hoops across it."""
    w, h = 16 * S, 24 * S
    xs, ys = grid(w, h)
    fibre = fbm(w, h, 3, 381)
    glow_ = np.exp(-(((xs - w / 2) / 12) ** 2 + ((ys - h / 2) / 18) ** 2))
    v = 0.78 + 0.16 * glow_ + 0.06 * (fibre - 0.5)
    seam = (xs < 1.5) | (xs > w - 1.5)
    hoops = (ys < 2.5) | (ys > h - 2.5) | (np.abs(ys - h / 3) < 0.6) | (np.abs(ys - 2 * h / 3) < 0.6)
    v = np.where(seam, v - 0.2, v)
    v = np.where(hoops, v - 0.28, v)
    # A painted ring in the middle, faint
    d = np.hypot(xs - w / 2, ys - h / 2)
    v = np.where((d > 5.0) & (d < 6.4), v - 0.12, v)
    cv = Canvas(w, h)
    cv.put(full(w, h), pale(v, (255, 248, 234)))
    return cv


# ================================================================ banners

def banner(field, border, emblem, cut, seed):
    """A festival's banner, 32x64 declared: a sleeve over the rod at the top, a field in the festival's colour with
    a border, its emblem, and a [cut] bottom (swallow, point or dags)."""
    w, h = 32 * S, 64 * S
    xs, ys = grid(w, h)
    cx = w / 2
    f = np.abs(xs - cx) / cx
    if cut == 'swallow':
        bottom = h - 26 * (1 - f)
    elif cut == 'point':
        bottom = h - 24 - 0.5 + 24 * (1 - f)
    else:
        tri = np.abs(((xs / w * 3) % 1.0) - 0.5) * 2
        bottom = h - 4 - 18 * tri
    inside = ys < bottom
    weave = ((np.floor(xs) + np.floor(ys)) % 2) * 0.03
    folds = 0.06 * np.sin(xs * 0.42 + 1.5 * vnoise(w, h, 14, seed))
    shade = weave + folds + 0.05 * (fbm(w, h, 6, seed + 1) - 0.5) - 0.08 * (ys / h)
    cv = Canvas(w, h)
    cv.put(inside, tone(field, shade * 2.0))
    # The border, following the cut
    dist_side = np.minimum(xs, w - xs)
    dist_bottom = (bottom - ys) * 0.9
    border_m = inside & ((dist_side < 4.5) | (dist_bottom < 4.5)) & (ys > 10)
    cv.put(border_m, tone(border, shade * 1.6 + 0.05))
    inner = inside & ((np.abs(dist_side - 6.0) < 0.6) | (np.abs(dist_bottom - 6.0) < 0.6)) & (ys > 12) & (dist_side > 5) & (dist_bottom > 5)
    cv.put(inner, tone(border, -0.1))
    # The sleeve over the rod
    sleeve = ys < 10
    cv.put(sleeve, tone(field, -0.28 + 0.1 * (ys < 3) + weave))
    cv.put((np.abs(ys - 9.5) < 0.6) & (np.floor(xs) % 4 < 2), tone(border, 0.1))
    emblem(cv, xs, ys, cx, 54.0)
    return cv


def emblem_blossom(cv, xs, ys, cx, cy):
    for (x0, y0, x1, y1) in ((cx - 2, cy + 10, cx - 14, cy + 22), (cx + 2, cy + 10, cx + 13, cy + 24)):
        paint_leaf(cv, xs, ys, x0, y0, x1, y1, 7, GREEN, 0.6)
    # Ribbons curling out either side
    for sx, col in ((-1, (246, 214, 98)), (1, (142, 201, 240))):
        pts = [(cx + sx * (6 + i * 1.6), cy + 14 + 6 * math.sin(i * 0.7)) for i in range(10)]
        d, _ = path_dist(pts, cv.w, cv.h)
        cv.put(d < 1.5, tone(col, -0.05))
    paint_flower(cv, xs, ys, cx, cy, 14.5, 5, WHITE_PETAL, YELLOW, rot=-math.pi / 2)
    m = np.hypot(xs - cx, ys - cy) < 14.5
    cv.over(m & (np.hypot(xs - cx, ys - cy) > 6), (240, 150, 190), 0.25)


def emblem_sun(cv, xs, ys, cx, cy):
    rays = poly(star_points(cx, cy, 24, 13, 12), cv.w, cv.h)
    k = 0.6 + 0.3 * lit_side(xs, ys, cx, cy, 24)
    cv.put(rays, bands(PAL['gold'], k))
    m, h = disc(cx, cy, 13, cv.w, cv.h)
    cv.put(m, bands(PAL['gold'], 0.55 + 0.8 * light(h * 5, 1.0) + 0.15 * h))
    face = (np.hypot(xs - cx, ys - cy) > 9.5) & (np.hypot(xs - cx, ys - cy) < 10.5)
    cv.put(face & m, bands(PAL['gold'], np.full(xs.shape, 0.3)))
    # A kindly face: eyes and a smile
    for ex in (-4, 4):
        e, _ = disc(cx + ex, cy - 2, 1.4, cv.w, cv.h)
        cv.put(e, (120, 70, 28))
    sm, _ = seg_dist(cx - 4, cy + 4, cx + 4, cy + 4, cv.w, cv.h)
    smile = (np.hypot(xs - cx, ys - (cy - 1)) > 5.4) & (np.hypot(xs - cx, ys - (cy - 1)) < 6.6) & (ys > cy + 2)
    cv.put(smile, (120, 70, 28))


def paint_sheaf(cv, xs, ys, cx, top, waist, bottom, spread_top, spread_waist, spread_bottom, stalks, pal, seed, ear=1.0):
    """A sheaf: [stalks] stalks gathered at [waist], fanning out to the ears at the top and the cut ends below."""
    rng = np.random.default_rng(seed)
    order = sorted(range(stalks), key=lambda i: abs(i - (stalks - 1) / 2), reverse=True)
    for i in order:
        u = (i / (stalks - 1)) * 2 - 1
        jitter = rng.uniform(-0.15, 0.15)
        x_top = cx + (u + jitter) * spread_top
        y_top = top + abs(u) * (waist - top) * 0.25 + rng.uniform(-2, 2) * ear
        x_w = cx + u * spread_waist
        x_b = cx + (u + jitter * 0.5) * spread_bottom
        pts = [(x_b, bottom), (x_w, waist), ((x_w + x_top) / 2, (waist + y_top) / 2 + 2), (x_top, y_top)]
        d, along = path_dist(pts, cv.w, cv.h)
        stalk = d < 1.0 * ear + 0.2
        cv.put(stalk, bands(pal, 0.45 + 0.15 * u + 0.1 * (d < 0.5)))
        # The ear: a plump head of grain along the stalk's last stretch, with awns
        ex, ey = x_top, y_top
        dx, dy = x_top - (x_w + x_top) / 2, y_top - ((waist + y_top) / 2 + 2)
        L = math.hypot(dx, dy) or 1
        ux, uy = dx / L, dy / L
        ex0, ey0 = ex - ux * 9 * ear, ey - uy * 9 * ear
        de, te = seg_dist(ex0, ey0, ex, ey, cv.w, cv.h)
        head = de < (2.3 * ear) * np.sin(np.clip(te, 0, 1) * math.pi) ** 0.5 + 0.3
        grains = 0.5 + 0.5 * np.cos(te * 9 * math.pi)
        cv.put(head, bands(pal, 0.6 + 0.25 * grains + 0.15 * lit_side(xs, ys, (ex + ex0) / 2, (ey + ey0) / 2, 3)))
        for s in (-1, 1):
            ax, ay = ex + ux * 5 * ear + s * uy * 2.5 * ear, ey + uy * 5 * ear - s * ux * 2.5 * ear
            da, _ = seg_dist(ex - ux * 2, ey - uy * 2, ax, ay, cv.w, cv.h)
            cv.put(da < 0.45, bands(pal, np.full(xs.shape, 0.75)))


def emblem_sheaf(cv, xs, ys, cx, cy):
    paint_sheaf(cv, xs, ys, cx, cy - 20, cy + 10, cy + 26, 15, 3.5, 7, 9, PAL['gold'], 391, ear=0.9)
    band, _ = seg_dist(cx - 6, cy + 10, cx + 6, cy + 10, cv.w, cv.h)
    cv.put(band < 1.6, bands(PAL['crimson'], np.full(xs.shape, 0.6)))


def emblem_star(cv, xs, ys, cx, cy):
    # Snow about it
    rng = np.random.default_rng(401)
    for _ in range(14):
        x, y = rng.uniform(8, cv.w - 8), rng.uniform(14, 100)
        if math.hypot(x - cx, y - cy) < 22:
            continue
        m, _ = disc(x, y, rng.uniform(0.8, 1.5), cv.w, cv.h)
        cv.put(m, (236, 240, 248))
    big = poly(star_points(cx, cy, 20, 5.5, 4), cv.w, cv.h)
    small = poly(star_points(cx, cy, 12, 4.5, 4, rot=-math.pi / 4), cv.w, cv.h)
    k = 0.62 + 0.3 * lit_side(xs, ys, cx, cy, 16)
    cv.put(small, bands(PAL['gold'], k - 0.15))
    cv.put(big, bands(PAL['gold'], k + 0.1))
    # A little red lantern hanging below
    ly = cy + 30
    m, h = ellipse(cx, ly, 5, 6, cv.w, cv.h)
    cv.put(m, bands(PAL['crimson'], 0.55 + 0.5 * h))
    for yy in (ly - 6, ly + 6):
        cap = (np.abs(xs - cx) < 3) & (np.abs(ys - yy) < 1.2)
        cv.put(cap, bands(PAL['gold'], np.full(xs.shape, 0.7)))
    line, _ = seg_dist(cx, cy + 20, cx, ly - 6, cv.w, cv.h)
    cv.put(line < 0.6, bands(PAL['gold'], np.full(xs.shape, 0.5)))


BANNER_LOOKS = [
    ((214, 120, 160), (250, 240, 226), emblem_blossom, 'swallow'),
    ((168, 46, 34), (234, 182, 74), emblem_sun, 'point'),
    ((128, 62, 34), (218, 164, 60), emblem_sheaf, 'dags'),
    ((32, 44, 84), (238, 234, 224), emblem_star, 'swallow'),
]


# ================================================================ garland sprites

def sprite_blossom():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    for x0, y0, x1, y1 in ((16, 18, 6, 27), (17, 18, 27, 26), (15, 16, 9, 6)):
        paint_leaf(cv, xs, ys, x0, y0, x1, y1, 5.5, GREEN, 0.55)
    paint_flower(cv, xs, ys, 10.5, 13.5, 6.5, 5, BLOSSOM, YELLOW, rot=0.3)
    paint_flower(cv, xs, ys, 21.5, 11.5, 6.0, 5, WHITE_PETAL, YELLOW, rot=-0.4)
    paint_flower(cv, xs, ys, 16.5, 22.0, 5.5, 5, BLOSSOM, YELLOW, rot=1.0)
    outline(cv, 0.7)
    return cv


def sprite_marigold():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    for x0, y0, x1, y1 in ((15, 17, 4, 24), (17, 17, 28, 25), (16, 18, 16, 30)):
        paint_leaf(cv, xs, ys, x0, y0, x1, y1, 6, GREEN, 0.5)
    for cx, cy, r in ((11.5, 12.5, 7.5), (21.5, 17.5, 6.5)):
        d = np.hypot(xs - cx, ys - cy)
        ang = np.arctan2(ys - cy, xs - cx)
        ruffle = 0.82 + 0.18 * np.abs(np.cos(ang * 6))
        m = d < r * ruffle
        rings = 0.5 + 0.5 * np.cos(d / r * 3 * math.pi)
        k = 0.5 + 0.25 * lit_side(xs, ys, cx, cy, r) + 0.15 * rings - 0.12 * (np.abs(np.cos(ang * 6)) < 0.2)
        cv.put(m, bands(MARIGOLD, k))
        c = d < r * 0.28
        cv.put(c, bands(MARIGOLD, 0.2 + 0.2 * lit_side(xs, ys, cx, cy, 2)))
    outline(cv, 0.7)
    return cv


def sprite_maple():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    cx, cy = 16, 15
    ang = np.arctan2(ys - cy, xs - cx) + math.pi / 2
    d = np.hypot(xs - cx, ys - cy)
    lobes = np.abs(np.cos(ang * 2.5)) ** 2.5
    serr = 0.06 * np.abs(np.sin(ang * 14))
    r = 13 * (0.42 + 0.58 * lobes) * (1 - serr)
    # The bottom lobes shorter, and the gap at the stem
    r = r * np.where(np.cos(ang) < -0.55, 0.62, 1.0)
    m = d < r
    vein = np.zeros_like(m)
    for k in range(5):
        a = -math.pi / 2 + (k - 2) * (2 * math.pi / 5)
        dv, _ = seg_dist(cx, cy, cx + math.cos(a) * 12, cy + math.sin(a) * 12, n)
        vein |= dv < 0.55
    tint = 0.55 + 0.25 * lit_side(xs, ys, cx, cy, 13) - 0.08 * (d / 13) + 0.1 * (vnoise(n, n, 6, 411) - 0.5)
    cv.put(m, bands(MAPLE, tint))
    cv.put(m & vein, bands(MAPLE, tint - 0.25))
    st, _ = seg_dist(cx, cy + 2, cx + 1.5, 30, n)
    cv.put(st < 0.9, bands(MAPLE, np.full((n, n), 0.25)))
    outline(cv, 0.72)
    return cv


def sprite_holly():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    for x0, y0, x1, y1 in ((15, 17, 3, 9), (17, 17, 29, 10), (16, 18, 15, 30)):
        paint_leaf(cv, xs, ys, x0, y0, x1, y1, 8.5, DARK_GREEN, 0.6, spikes=3.5)
    for bx, by in ((14, 15), (18.5, 14), (16.5, 18.5)):
        m, h = disc(bx, by, 2.6, n)
        cv.put(m, bands(BERRY, 0.45 + 0.6 * h * (0.6 + 0.4 * lit_side(xs, ys, bx, by, 2.6))))
        cv.px(int(bx - 1), int(by - 1), (255, 220, 210))
    outline(cv, 0.7)
    return cv


def sprite_leaf():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    stem = [(6, 28), (11, 20), (17, 13), (25, 5)]
    d, _ = path_dist(stem, n)
    cv.put(d < 0.9, bands(PAL['wood'], np.full((n, n), 0.6)))
    for (x0, y0, x1, y1) in ((11, 20, 3, 13), (12, 19, 20, 24), (17, 13, 10, 5), (18, 12, 27, 16), (24, 6, 29, 2)):
        paint_leaf(cv, xs, ys, x0, y0, x1, y1, 6.0, GREEN, 0.62)
    outline(cv, 0.7)
    return cv


def sprite_wheat():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    paint_sheaf(cv, xs, ys, 16, 6, 22, 31, 8, 2.5, 3, 3, STRAW, 421, ear=0.8)
    outline(cv, 0.7)
    return cv


def sprite_berries():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    for x0, y0, x1, y1 in ((16, 20, 5, 27), (17, 20, 28, 26)):
        paint_leaf(cv, xs, ys, x0, y0, x1, y1, 6, GREEN, 0.5)
    rng = np.random.default_rng(431)
    for cx, cy in ((10, 11), (21, 10), (16, 17)):
        st, _ = seg_dist(16, 21, cx, cy, n)
        cv.put(st < 0.6, bands(PAL['wood'], np.full((n, n), 0.5)))
        for _ in range(5):
            bx, by = cx + rng.uniform(-3, 3), cy + rng.uniform(-3, 3)
            m, h = disc(bx, by, 2.0, n)
            cv.put(m, bands(BERRY, 0.42 + 0.55 * h * (0.6 + 0.4 * lit_side(xs, ys, bx, by, 2))))
    outline(cv, 0.7)
    return cv


def sprite_pine():
    n = 16 * S
    xs, ys = grid(n)
    cv = Canvas(n)
    twig = [(4, 28), (12, 20), (20, 11), (28, 3)]
    d, along = path_dist(twig, n)
    rng = np.random.default_rng(441)
    for i in range(26):
        f = i / 25
        px = 4 + 24 * f
        py = 28 - 25 * f
        for s in (-1, 1):
            a = math.atan2(-25, 24) + s * (1.0 + rng.uniform(-0.2, 0.2))
            L = 5.5 * (1 - 0.5 * abs(f - 0.45)) * rng.uniform(0.8, 1.1)
            dn, t = seg_dist(px, py, px + math.cos(a) * L, py + math.sin(a) * L, n)
            m = dn < 0.7
            cv.put(m & (cv.a == 0), bands(PINE, 0.4 + 0.45 * t + 0.15 * (s < 0)))
    cv.put(d < 1.0, bands(PAL['wood'], np.full((n, n), 0.4)))
    outline(cv, 0.7)
    return cv


# ================================================================ the centrepieces

def sun_wheel():
    """The Sunhearth's wheel, 64x64 declared: a rim bound in straw, eight spokes, a gilt hub, an inner ring of
    straw, red ties where the spokes meet the rim. Open between the spokes."""
    n = 64 * S
    xs, ys = grid(n)
    c = n / 2
    dx, dy = xs - c, ys - c
    d = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)
    cv = Canvas(n)
    straw_grain = 0.5 + 0.5 * np.sin(ang * 90 + 3 * vnoise(n, n, 6, 451))
    # The rim: straw wound round, with twine binding it every eighth of the way round
    rim = (d > 52) & (d < 63)
    round_ = np.sin(np.clip((d - 52) / 11, 0, 1) * math.pi)
    binding = np.abs(((ang / (2 * math.pi) * 16) % 1.0) - 0.5) < 0.07
    cv.put(rim, bands(STRAW, 0.35 + 0.45 * round_ + 0.12 * straw_grain + 0.12 * lit_side(xs, ys, c, c, 63)))
    cv.put(rim & binding, bands(PAL['leather'], 0.45 + 0.3 * round_))
    # Spokes
    for k in range(8):
        a = k * math.pi / 4
        ds, t = seg_dist(c + math.cos(a) * 10, c + math.sin(a) * 10, c + math.cos(a) * 54, c + math.sin(a) * 54, n)
        sp = ds < 3.2
        cv.put(sp, bands(PAL['wood'], 0.62 + 0.25 * (1 - ds / 3.2) + 0.08 * (vnoise(n, n, 5, 452) - 0.5)))
        # A red tie where the spoke meets the rim
        tx, ty = c + math.cos(a) * 52, c + math.sin(a) * 52
        tie, th = disc(tx, ty, 3.6, n)
        cv.put(tie, bands(PAL['crimson'], 0.55 + 0.4 * th))
    # The inner ring of straw
    inner = (d > 27) & (d < 33)
    ir = np.sin(np.clip((d - 27) / 6, 0, 1) * math.pi)
    cv.put(inner, bands(STRAW, 0.4 + 0.4 * ir + 0.1 * straw_grain))
    # Little gilt sun rays between the spokes, on the inner ring
    for k in range(8):
        a = k * math.pi / 4 + math.pi / 8
        tri = poly([(c + math.cos(a - 0.12) * 33, c + math.sin(a - 0.12) * 33), (c + math.cos(a + 0.12) * 33, c + math.sin(a + 0.12) * 33),
                    (c + math.cos(a) * 46, c + math.sin(a) * 46)], n)
        cv.put(tri, bands(PAL['gold'], 0.6 + 0.2 * lit_side(xs, ys, c, c, 46)))
    # The hub
    hub, hh = disc(c, c, 11, n)
    cv.put(hub, bands(PAL['gold'], 0.45 + 0.9 * light(hh * 6, 1.0) + 0.2 * hh))
    for k in range(6):
        a = k * math.pi / 3
        r, _ = disc(c + math.cos(a) * 7, c + math.sin(a) * 7, 1.1, n)
        cv.put(r, bands(PAL['gold'], np.full((n, n), 0.2)))
    outline(cv, 0.6)
    return cv


def star():
    """The Lanternrise's star, 32x32 declared, drawn with the glow: a long four-point star over a short one, white
    at its heart, gold at its points, soft at the edge."""
    n = 32 * S
    xs, ys = grid(n)
    c = n / 2
    big = poly(star_points(c, c, 30, 7, 4), n).astype(float)
    small = poly(star_points(c, c, 19, 6.5, 4, rot=-math.pi / 4), n).astype(float)
    shape = np.maximum(big, small)
    soft = blur(shape, 1)
    d = np.hypot(xs - c, ys - c)
    heart = np.exp(-(d / 9) ** 2)
    cv = Canvas(n)
    cv.rgb = mix((255, 206, 110), (255, 255, 244), np.clip(heart * 1.4 + 0.2, 0, 1))
    glow_ = 0.28 * np.exp(-d / 9)
    cv.a = np.clip(np.maximum(soft, glow_), 0, 1)
    return cv


def sheaf():
    """One of the Sheafmoot crown's sheaves, 32x64 declared: stood on its cut ends at the bottom, gathered at the
    waist where the crown's red band goes round, ears fanning out at the top."""
    w, h = 32 * S, 64 * S
    xs, ys = grid(w, h)
    cv = Canvas(w, h)
    paint_sheaf(cv, xs, ys, w / 2, 14, 95, 126, 27, 7, 13, 15, STRAW, 461, ear=1.5)
    tie, _ = seg_dist(w / 2 - 8, 95, w / 2 + 8, 95, w, h)
    cv.put(tie < 2.0, bands(PAL['leather'], np.full((h, w), 0.4)))
    outline(cv, 0.65)
    return cv


# ================================================================ the atlas

REGIONS = {
    'cloth': (0, 0), 'rope': (16, 0), 'ribbon': (32, 0), 'halo': (48, 0), 'flame': (64, 0), 'tag': (80, 0),
    'bow': (96, 0), 'wood': (112, 0), 'iron': (128, 0), 'gold': (144, 0), 'spark': (160, 0), 'gold_ball': (176, 0),
    'glass': (0, 16), 'paper': (16, 16),
    'banner_0': (32, 16), 'banner_1': (64, 16), 'banner_2': (96, 16), 'banner_3': (128, 16),
    'blossom': (0, 80), 'marigold': (16, 80), 'maple': (32, 80), 'holly': (48, 80), 'leaf': (64, 80), 'wheat': (80, 80),
    'berries': (96, 80), 'pine': (112, 80),
    'wheel': (0, 96), 'star': (64, 96), 'sheaf': (96, 96),
}


def atlas_pieces():
    out = {
        'cloth': cloth(), 'rope': rope(), 'ribbon': ribbon(), 'halo': halo(), 'flame': flame(), 'tag': tag(), 'bow': bow(),
        'wood': wood(), 'iron': iron(), 'gold': gold(), 'spark': spark(), 'gold_ball': gold_ball(),
        'glass': glass(), 'paper': paper(),
        'blossom': sprite_blossom(), 'marigold': sprite_marigold(), 'maple': sprite_maple(), 'holly': sprite_holly(),
        'leaf': sprite_leaf(), 'wheat': sprite_wheat(), 'berries': sprite_berries(), 'pine': sprite_pine(),
        'wheel': sun_wheel(), 'star': star(), 'sheaf': sheaf(),
    }
    for i, (field, border, emblem, cut) in enumerate(BANNER_LOOKS):
        out[f'banner_{i}'] = banner(field, border, emblem, cut, 380 + 10 * i)
    return {k: v.image() for k, v in out.items()}


def atlas(pieces):
    img = Image.new('RGBA', (256 * S, 256 * S), (0, 0, 0, 0))
    for k, (x, y) in REGIONS.items():
        img.paste(pieces[k], (x * S, y * S))
    return img


# ================================================================ the maypole

def pole_birch():
    """White paint over birch, with a green and a pink ribbon wound round. The stripes run along (u + v): the
    pole's four faces take a quarter of the width each, in order round it, so the stripes go on unbroken round
    the corners and up into the next block."""
    xs, ys = grid(N)
    s = (np.floor(xs) + np.floor(ys)) % N
    grain = 0.5 + 0.5 * np.sin(xs * 2.1 + 3 * vnoise(N, N, 8, 471))
    paint = 0.7 + 0.08 * grain + 0.05 * (fbm(N, N, 4, 472) - 0.5)
    cv = Canvas(N)
    cv.put(full(N), bands(WHITE_PETAL, paint + 0.05))
    green = (s >= 0) & (s < 7)
    pink = (s >= 16) & (s < 23)
    edge_g = (s == 0) | (s == 6)
    edge_p = (s == 16) | (s == 22)
    cv.put(green, bands(GREEN, 0.62 + 0.1 * grain - 0.25 * edge_g))
    cv.put(pink, bands(BLOSSOM, 0.5 + 0.1 * grain - 0.25 * edge_p))
    gilt = (s == 9) | (s == 25)
    cv.put(gilt, bands(PAL['gold'], 0.7 + 0.1 * grain))
    return cv.image()


# ================================================================ the prizes

def prize_crown():
    """The Ribbon Crown: a wreath of leaves and blossom, seen a little from above, ribbons streaming down from it."""
    xs, ys = grid(N)
    cv = Canvas(N)
    cx, cy, rx, ry = 16, 11.5, 11.5, 6.0

    def ring(front):
        d = np.hypot((xs - cx) / rx, (ys - cy) / ry)
        band = (np.abs(d - 1) < 0.33) & ((ys >= cy) if front else (ys < cy))
        leafy = 0.5 + 0.5 * np.sin(np.arctan2(ys - cy, xs - cx) * 9)
        k = 0.45 + 0.2 * leafy + 0.15 * lit_side(xs, ys, cx, cy - 4, 12) - 0.15 * (not front)
        cv.put(band, bands(GREEN, k))

    ring(False)
    for x0, col in ((12.0, BLOSSOM), (16.0, YELLOW), (20.0, [(30, 50, 90), (60, 100, 160), (110, 160, 214), (160, 200, 240), (210, 232, 252)])):
        pts = [(x0 + 1.6 * math.sin(y * 0.55 + x0), y) for y in np.linspace(14, 30, 12)]
        d, along = path_dist(pts, N)
        rib = d < 1.4
        notch = (along > 15.2) & (np.abs(xs - pts[-1][0]) < 0.7)
        cv.put(rib & ~notch, bands(col, 0.55 + 0.2 * np.sin(along * 0.8)))
    ring(True)
    flowers = [(0.0, BLOSSOM), (0.8, WHITE_PETAL), (1.6, YELLOW), (2.4, BLOSSOM), (3.2, WHITE_PETAL), (4.0, BLOSSOM),
               (4.8, YELLOW), (5.6, WHITE_PETAL)]
    for a, col in sorted(flowers, key=lambda f: math.sin(f[0])):
        fx, fy = cx + math.cos(a) * rx, cy + math.sin(a) * ry
        r = 2.2 + 0.7 * (math.sin(a) + 1) / 2
        paint_flower(cv, xs, ys, fx, fy, r, 5, col, YELLOW if col is not YELLOW else MARIGOLD, rot=a)
    outline(cv, 0.8)
    glint(cv, 9, 7, 1)
    return cv.image()


def prize_medal():
    """The Sunhearth Medal: a gold medal with the sun struck on it, on a red ribbon with a gold stripe."""
    from hdkit import bevel
    xs, ys = grid(N)
    cv = Canvas(N)
    for sx in (-1, 1):
        strap = poly([(16 + sx * 9, 0), (16 + sx * 3.5, 0), (16 + sx * 0.5, 12), (16 + sx * 5, 12)], N)
        cv.put(strap, bands(PAL['crimson'], 0.5 + 0.2 * (sx < 0) + 0.1 * (ys < 2)))
        stripe = poly([(16 + sx * 6.8, 0), (16 + sx * 5.6, 0), (16 + sx * 2.6, 12), (16 + sx * 3.6, 12)], N)
        cv.put(stripe, bands(PAL['gold'], np.full((N, N), 0.65)))
    loop = (np.hypot(xs - 16, ys - 11.5) < 2.2) & (np.hypot(xs - 16, ys - 11.5) > 1.0)
    cv.put(loop, bands(PAL['gold'], 0.5 + 0.3 * lit_side(xs, ys, 16, 11.5, 2)))
    cx, cy, r = 16, 21, 9.5
    d = np.hypot(xs - cx, ys - cy)
    side = lit_side(xs, ys, cx, cy, r)
    disc_m = d < r
    rim = disc_m & (d > r - 1.7)
    field = disc_m & ~rim
    cv.put(field, bands(PAL['gold'], 0.5 + 0.12 * side))
    cv.put(rim, bands(PAL['gold'], 0.6 + 0.3 * side))
    rays = poly(star_points(cx, cy, 6.9, 3.9, 12), N) & (d > 3.6)
    boss = d < 3.3
    sun = rays | boss
    k = 0.72 + 0.12 * side + 0.22 * bevel(sun)
    cv.put(sun, bands(PAL['gold'], k))
    cv.put(field & ~sun & (d < 3.9), bands(PAL['gold'], np.full((N, N), 0.25)))
    outline(cv, 0.8)
    glint(cv, 14, 19, 2)
    glint(cv, 10, 16, 1)
    return cv.image()


def prize_sheaf():
    """The Gilded Sheaf: a sheaf of wheat dipped in gold, tied with a russet bow."""
    xs, ys = grid(N)
    cv = Canvas(N)
    paint_sheaf(cv, xs, ys, 16, 4.5, 19.5, 30.5, 11, 2.2, 5, 9, PAL['gold'], 481, ear=0.6)
    band, _ = seg_dist(12.5, 19.5, 19.5, 19.5, N)
    cv.put(band < 1.2, bands(PAL['crimson'], np.full((N, N), 0.6)))
    for sx in (-1, 1):
        m, hh = ellipse(16 + sx * 3.2, 18.6, 2.6, 1.7, N)
        hole, _ = ellipse(16 + sx * 3.2, 18.7, 1.0, 0.6, N)
        cv.put(m & ~hole, bands(PAL['crimson'], 0.45 + 0.4 * hh))
        tail, t = seg_dist(16, 20, 16 + sx * 3.5, 25, N)
        cv.put(tail < 0.9, bands(PAL['crimson'], 0.5 - 0.2 * t))
    knot, kh = disc(16, 19.5, 1.4, N)
    cv.put(knot, bands(PAL['crimson'], 0.5 + 0.4 * kh))
    outline(cv, 0.8)
    glint(cv, 11, 6, 1)
    glint(cv, 20, 9, 1)
    return cv.image()


def prize_lantern():
    """The Riddler's Lantern: a round paper lantern of red and gold, lit from inside, a gold riddle-mark on it."""
    xs, ys = grid(N)
    cv = Canvas(N)
    arc = (np.hypot(xs - 16, (ys - 6.5) * 1.3) > 4.0) & (np.hypot(xs - 16, (ys - 6.5) * 1.3) < 5.2) & (ys < 6.5)
    cv.put(arc, bands(PAL['gold'], 0.5 + 0.3 * lit_side(xs, ys, 16, 3, 5)))
    cx, cy, rx, ry = 16, 16, 10, 8
    m, h = ellipse(cx, cy, rx, ry, N)
    inner = np.exp(-(((xs - cx) / 6) ** 2 + ((ys - cy) / 5) ** 2))
    k = 0.42 + 0.35 * inner + 0.15 * lit_side(xs, ys, cx, cy, 10) + 0.1 * h
    cv.put(m, bands(PAL['crimson'], k))
    for f in (-0.66, -0.33, 0.0, 0.33, 0.66):
        rib_x = cx + f * rx * np.sqrt(np.clip(1 - ((ys - cy) / ry) ** 2, 0, 1))
        rib = m & (np.abs(xs - rib_x) < 0.5)
        cv.put(rib, bands(PAL['crimson'], k - 0.2))
    for y0, half in ((7.5, 5.5), (24.5, 4.5)):
        cap = (np.abs(ys - y0) < 1.3) & (np.abs(xs - 16) < half)
        cv.put(cap, bands(PAL['gold'], 0.55 + 0.3 * (ys < y0)))
    # A gold question mark on the paper
    hook = (np.hypot(xs - 16, ys - 13.5) > 1.6) & (np.hypot(xs - 16, ys - 13.5) < 2.8) & ~((xs < 16) & (ys > 13.5))
    stem = (np.abs(xs - 16.4) < 0.7) & (ys > 15.5) & (ys < 18)
    dot, _ = disc(16.4, 19.6, 0.9, N)
    cv.put(hook | stem | dot, bands(PAL['gold'], np.full((N, N), 0.85)))
    # The tassel
    knot, kh = disc(16, 26.6, 1.3, N)
    cv.put(knot, bands(PAL['gold'], 0.6 + 0.3 * kh))
    for dx in (-1.2, 0.0, 1.2):
        t, _ = seg_dist(16, 27.5, 16 + dx * 1.4, 31.2, N)
        cv.put(t < 0.6, bands(PAL['crimson'], np.full((N, N), 0.55)))
    outline(cv, 0.8)
    glint(cv, 11, 12, 1)
    return cv.image()


# ================================================================ the crown worn

def crown_worn():
    """The Ribbon Crown on the head: the vanilla armour layout (64x32 declared, painted at 128x64). A band of
    leaves and flowers round the top of the helmet's sides, flowers round the edge of its top, and ribbons hanging
    down the back."""
    s = 2
    w, h = 64 * s, 32 * s
    cv = Canvas(w, h)
    xs, ys = grid(w, h)
    rng = np.random.default_rng(491)
    # The head cube's faces: right (0,8), front (8,8), left (16,8), back (24,8), each 8x8; top (8,0)
    sides = [(0, 8), (8, 8), (16, 8), (24, 8)]
    flower_cols = [BLOSSOM, WHITE_PETAL, YELLOW, BLOSSOM]
    for i, (fx, fy) in enumerate(sides):
        x0, y0 = fx * s, fy * s
        leaves = (xs >= x0) & (xs < x0 + 8 * s) & (ys >= y0) & (ys < y0 + 3.2 * s)
        k = 0.45 + 0.25 * (0.5 + 0.5 * np.sin(xs * 1.3 + ys * 0.7)) - 0.1 * ((ys - y0) / (3.2 * s))
        cv.put(leaves, bands(GREEN, k))
        for j in range(3):
            cxf = x0 + (1.6 + j * 2.6 + rng.uniform(-0.3, 0.3)) * s
            cyf = y0 + (1.4 + rng.uniform(-0.2, 0.3)) * s
            paint_flower(cv, xs, ys, cxf, cyf, 1.35 * s, 5, flower_cols[(i + j) % 4], YELLOW if flower_cols[(i + j) % 4] is not YELLOW else MARIGOLD, rot=j)
    # Ribbons down the back face, from the band to its foot
    bx, by = 24 * s, 8 * s
    for j, col in enumerate((BLOSSOM, YELLOW, [(30, 50, 90), (60, 100, 160), (110, 160, 214), (160, 200, 240), (210, 232, 252)])):
        x_mid = bx + (2.0 + j * 2.0) * s
        rib = (np.abs(xs - x_mid - 0.6 * np.sin(ys * 0.5 + j)) < 0.75 * s) & (ys >= by + 3 * s) & (ys < by + 8 * s)
        cv.put(rib, bands(col, 0.55 + 0.2 * np.sin(ys * 0.6 + j)))
    # Flowers round the edge of the top
    tx, ty = 8 * s, 0
    top_ring = (xs >= tx) & (xs < tx + 8 * s) & (ys >= ty) & (ys < ty + 8 * s) & (
        (xs < tx + 1.5 * s) | (xs >= tx + 6.5 * s) | (ys < ty + 1.5 * s) | (ys >= ty + 6.5 * s))
    cv.put(top_ring, bands(GREEN, 0.5 + 0.2 * (0.5 + 0.5 * np.sin(xs * 1.7 + ys))))
    for (u, v) in ((1, 1), (4, 0.8), (7, 1), (7.2, 4), (7, 7), (4, 7.2), (1, 7), (0.8, 4)):
        paint_flower(cv, xs, ys, tx + u * s, ty + v * s, 1.1 * s, 5, BLOSSOM if (u + v) % 2 else WHITE_PETAL, YELLOW)
    return cv.image()


# ================================================================ the sky lantern

def sky_lanterns():
    """The Lanternrise's sky lantern, 16x16, four frames of its flame: a paper bag of a lantern, a little wider
    at the top, lit from the flame at its open foot. Pale: the game tints the paper warm."""
    frames = []
    n = 16
    xs, ys = grid(n)
    for f in range(4):
        cv = Canvas(n)
        top, bottom = 2.5, 13.5
        t = np.clip((ys - top) / (bottom - top), 0, 1)
        half = 5.2 - 1.6 * t
        body = (np.abs(xs - 8) < half) & (ys > top) & (ys < bottom)
        # Rounded shoulders
        body &= ~((ys < top + 1.2) & (np.abs(xs - 8) > half - 1.0))
        flick = (0.85, 1.0, 0.92, 0.78)[f]
        heat = np.exp(-(((xs - 8) / 4.5) ** 2 + ((ys - 13) / (5.5 * flick)) ** 2))
        v = 0.74 + 0.3 * heat * flick + 0.03 * (np.floor(xs) % 3 == 0)
        ribs = (np.abs(xs - 8) > half - 0.9) | (np.abs(ys - 7.5) < 0.5)
        v = np.where(ribs, v - 0.14, v)
        cv.put(body, pale(v, (255, 250, 240)))
        cv.a[body] = 1.0
        fl = (np.abs(xs - 8) < 1.2 * flick + 0.3) & (ys > 12.5 - flick) & (ys < 14.5)
        cv.put(fl, (255, 255, 236))
        # A faint glow round it
        d = np.hypot((xs - 8) / 1.0, (ys - 9) / 1.25)
        halo_a = np.clip(0.3 - d / 26, 0, 1) * flick
        out = ~body & ~fl
        cv.rgb[out] = 255
        cv.a[out] = np.maximum(cv.a[out], halo_a[out])
        frames.append(cv.image())
    return frames


# ================================================================ JSON

TRIM_LOOKS = ['pole_birch', 'pole_oak', 'pole_spruce', 'hay', 'pumpkin', 'carved', 'jack', 'melon', 'target', 'brazier', 'candles', 'blossom']
FACINGS = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
# The campfire's logs: its south is the model's own way round
CAMPFIRE_Y = {'south': 0, 'west': 90, 'north': 180, 'east': 270}
VANILLA = {
    'hay': 'minecraft:block/hay_block', 'pumpkin': 'minecraft:block/pumpkin', 'carved': 'minecraft:block/carved_pumpkin',
    'jack': 'minecraft:block/jack_o_lantern', 'melon': 'minecraft:block/melon', 'target': 'minecraft:block/target',
    'brazier': 'minecraft:block/campfire', 'candles': 'minecraft:block/white_candle_three_candles_lit',
    'blossom': 'minecraft:block/flowering_azalea',
}
POLE_TEXTURES = {
    'pole_birch': ('guildmark:block/festival_pole_birch', 'minecraft:block/stripped_birch_log_top', True),
    'pole_oak': ('minecraft:block/stripped_oak_log', 'minecraft:block/stripped_oak_log_top', False),
    'pole_spruce': ('minecraft:block/spruce_log', 'minecraft:block/spruce_log_top', False),
}
PRIZES = ['ribbon_crown', 'sunhearth_medal', 'gilded_sheaf', 'riddlers_lantern']


def trim_blockstate():
    variants = {}
    for facing in ('east', 'north', 'south', 'west'):
        for look in TRIM_LOOKS:
            key = f'facing={facing},look={look}'
            if look.startswith('pole_'):
                variants[key] = {'model': f'guildmark:block/festival_{look}'}
            elif look in ('carved', 'jack'):
                v = {'model': VANILLA[look]}
                if FACINGS[facing]:
                    v['y'] = FACINGS[facing]
                variants[key] = v
            elif look == 'brazier':
                v = {'model': VANILLA[look]}
                if CAMPFIRE_Y[facing]:
                    v['y'] = CAMPFIRE_Y[facing]
                variants[key] = v
            else:
                variants[key] = {'model': VANILLA[look]}
    return {'variants': variants}


def ornament_blockstate():
    variants = {}
    for lit in range(16):
        for usable in ('false', 'true'):
            variants[f'light={lit},usable={usable}'] = {'model': 'guildmark:block/festival_ornament'}
    return {'variants': variants}


def pole_model(look):
    side, end, painted = POLE_TEXTURES[look]
    # The painted pole's faces each take their quarter of the texture, in order round it; the logs keep their own
    if painted:
        uv = {'north': [0, 0, 4, 16], 'west': [4, 0, 8, 16], 'south': [8, 0, 12, 16], 'east': [12, 0, 16, 16]}
    else:
        uv = {k: [5, 0, 11, 16] for k in ('north', 'west', 'south', 'east')}
    faces = {k: {'uv': v, 'texture': '#side'} for k, v in uv.items()}
    faces['up'] = {'uv': [5, 5, 11, 11], 'texture': '#end', 'cullface': 'up'}
    faces['down'] = {'uv': [5, 5, 11, 11], 'texture': '#end', 'cullface': 'down'}
    return {
        'parent': 'minecraft:block/block',
        'textures': {'side': side, 'end': end, 'particle': side},
        'elements': [{'from': [5, 0, 5], 'to': [11, 16, 11], 'faces': faces}],
    }


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def dump(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def write(assets, prev=None):
    pieces = atlas_pieces()
    sheet_img = atlas(pieces)
    save(sheet_img, os.path.join(assets, 'textures/entity/festival/ornaments.png'))
    pole = pole_birch()
    save(pole, os.path.join(assets, 'textures/block/festival_pole_birch.png'))
    prizes = {'ribbon_crown': prize_crown(), 'sunhearth_medal': prize_medal(), 'gilded_sheaf': prize_sheaf(), 'riddlers_lantern': prize_lantern()}
    for k, img in prizes.items():
        save(img, os.path.join(assets, 'textures/item', k + '.png'))
        dump(os.path.join(assets, 'models/item', k + '.json'), {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/' + k}})
        dump(os.path.join(assets, 'items', k + '.json'), {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + k}})
    worn = crown_worn()
    save(worn, os.path.join(assets, 'textures/entity/equipment/humanoid/ribbon_crown.png'))
    dump(os.path.join(assets, 'equipment/ribbon_crown.json'), {'layers': {'humanoid': [{'texture': 'guildmark:ribbon_crown'}]}})
    lanterns = sky_lanterns()
    for i, img in enumerate(lanterns):
        save(img, os.path.join(assets, 'textures/particle', f'sky_lantern_{i}.png'))
    dump(os.path.join(assets, 'particles/sky_lantern.json'), {'textures': [f'guildmark:sky_lantern_{i}' for i in range(len(lanterns))]})
    dump(os.path.join(assets, 'blockstates/festival_trim.json'), trim_blockstate())
    dump(os.path.join(assets, 'blockstates/festival_ornament.json'), ornament_blockstate())
    for look in POLE_TEXTURES:
        dump(os.path.join(assets, 'models/block', f'festival_{look}.json'), pole_model(look))
    dump(os.path.join(assets, 'models/block/festival_ornament.json'), {'textures': {'particle': 'minecraft:block/white_wool'}})
    if prev:
        os.makedirs(prev, exist_ok=True)
        preview(pieces, sheet_img, pole, prizes, worn, lanterns, os.path.join(prev, 'festival_art.png'))


def tint(img, rgb):
    a = np.asarray(img.convert('RGBA'), float)
    a[..., :3] *= np.asarray(rgb, float) / 255
    return Image.fromarray(a.round().astype(np.uint8), 'RGBA')


def preview(pieces, sheet_img, pole, prizes, worn, lanterns, path):
    """A contact sheet: the atlas on a dusk sky, the pale pieces tinted as the game tints them, the prizes large,
    the maypole tiled, the crown worn and the sky lanterns."""
    bg = (38, 40, 58)
    a = Image.new('RGBA', sheet_img.size, bg + (255,))
    a.alpha_composite(sheet_img)
    a = a.resize((a.width * 2, a.height * 2), Image.NEAREST)
    pale_keys = ['cloth', 'rope', 'ribbon', 'bow', 'glass', 'paper', 'halo', 'flame']
    colours = [(242, 166, 200), (245, 179, 66), (181, 83, 42), (244, 241, 234)]
    tinted = [tint(pieces[k], c) for c in colours for k in pale_keys]
    b = sheet(tinted, 4, len(pale_keys), bg=bg)
    c = sheet(list(prizes.values()), 8, 4, bg=bg, labels=list(prizes))
    tiled = Image.new('RGBA', (N * 4, N * 3))
    for x in range(4):
        for y in range(3):
            tiled.paste(pole, (x * N, y * N))
    d = sheet([tiled, worn], 4, 2, bg=bg, labels=['maypole, its four faces side by side', 'ribbon crown worn'])
    e = sheet([tint(f, (255, 214, 160)) for f in lanterns], 12, 4, bg=(16, 18, 34))
    w = max(i.width for i in (a, b, c, d, e))
    out = Image.new('RGBA', (w, a.height + b.height + c.height + d.height + e.height), (30, 30, 36, 255))
    y = 0
    for im in (a, b, c, d, e):
        out.alpha_composite(im, (0, y))
        y += im.height
    out.save(path)


def main():
    args = sys.argv[1:]
    assets = args[0] if args and args[0] != '-' else os.path.join(HERE, '..', '..', 'src/main/resources/assets/guildmark')
    prev = args[1] if len(args) > 1 else None
    write(assets, prev)


if __name__ == '__main__':
    main()
