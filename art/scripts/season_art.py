"""The seasons' art: the compass medallion and the glyphs of the four seasons, the four festivals' emblems, the
Almanac's wheel of the year and its needle, the motes that drift over its pages, the particles of the seasons
(blossom petals, falling leaves, breath on cold air and frost glints) and the Almanac itself.

    python3 -B art/scripts/season_art.py [assets dir [preview dir]]

With no arguments it writes into the repo's assets (`src/main/resources/assets/guildmark`). Everything is drawn
the way the older art is: the small sprites are designs in rows of letters, at 1x, put through `ui_hd.remaster`
so they stand beside the compass and HUD sprites at 2x (each design here is the shape only: its dark outline is
added round it before the remaster); the wheel and the soft glow are painted at 2x outright; the particles are
16x16, painted pale where the game tints them, with `region_particles.py`'s brushes; the Almanac is a 32x item
painted with `hdkit.py`. Everything is seeded: a rerun gives the same files.

Files written (under the assets dir):
    textures/gui/sprites/season/medallion.png                         28x28   (drawn 14x14 under the compass strip)
    textures/gui/sprites/season/spring.png, summer, autumn, winter     24x24   (the glyphs, drawn 12x12)
    textures/gui/sprites/season/festival_<id>.png                      24x24   (ribbonwake, sunhearth, sheafmoot, lanternrise)
    textures/gui/sprites/season/mote_<season>.png                      10x10   (drifting over the Almanac and the banner)
    textures/gui/sprites/season/glint.png                              10x10
    textures/gui/sprites/season/needle.png                             12x104  (the Almanac's needle, tip up)
    textures/gui/sprites/season/glow.png                               32x32   (a soft white glow, tinted in the game)
    textures/gui/sprites/season/wheel.png                              240x240 (the wheel of the year, drawn 120x120)
    textures/particle/season_petal_0..7, season_leaf_0..7             16x16   (two shapes, four tumbling frames each)
    textures/particle/season_breath_0..3, season_glint_0..3           16x16
    particles/season_petal.json, season_leaf.json, season_breath.json, season_glint.json
    textures/item/almanac.png                                         32x32
    models/item/almanac.json, items/almanac.json
With a preview dir it also writes `season_art.png`, a contact sheet of all of it, magnified.
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
from hdkit import PAL, Canvas, grid, vnoise, fbm, rand, bands, disc, seg_dist, light, outline, glint, tone, mix, blur
import ui_art
import ui_hd
import region_particles as rp

CL = (0, 0, 0, 0)
OUT = (10, 9, 12, 255)

# ================================================================ the small sprites, as rows of letters

# ui_art's marker palette, and the seasons' own colours
PAL1 = dict(ui_art.MARK_PAL)
PAL1.update({
    # blossom
    'p': (255, 226, 236, 255), 'k': (240, 160, 192, 255), 'K': (196, 100, 142, 255),
    # leaves and stems
    'e': (82, 168, 92, 255), 'E': (150, 220, 150, 255), 'h': (48, 112, 60, 255), 'n': (122, 84, 52, 255), 'N': (168, 120, 74, 255),
    # sun and gold
    'y': (250, 214, 104, 255), 'Y': (255, 244, 196, 255), 'a': (232, 156, 54, 255), 'A': (184, 104, 38, 255),
    # autumn
    'f': (226, 118, 46, 255), 'F': (250, 178, 84, 255), 'r': (168, 56, 36, 255), 'R': (116, 40, 32, 255),
    # frost
    'c': (170, 210, 240, 255), 'C': (236, 248, 255, 255), 'b': (104, 146, 196, 255),
    # ribbons and paper
    'm': (236, 120, 170, 255), 'M': (186, 76, 124, 255), 'u': (96, 150, 222, 255), 'v': (120, 196, 120, 255),
    'l': (255, 222, 140, 255), 'L': (255, 248, 214, 255), 'x': (214, 76, 52, 255), 'X': (150, 44, 36, 255),
    # the medallion's inside
    'z': (30, 28, 38, 255), 'Z': (44, 42, 54, 255),
})


def outlined(rows, pal=PAL1, diagonal=False):
    """A design (the shape only, '.' see-through) with the dark outline drawn round it: every see-through
    pixel beside the shape (and, with [diagonal], at its corners too) turns to outline."""
    img = ui_art.from_rows(rows, pal)
    w, h = img.size
    a = np.asarray(img)[..., 3] > 0
    out = img.copy()
    for y in range(h):
        for x in range(w):
            if a[y, x]:
                continue
            near = [(1, 0), (-1, 0), (0, 1), (0, -1)] + ([(1, 1), (-1, 1), (1, -1), (-1, -1)] if diagonal else [])
            if any(0 <= x + dx < w and 0 <= y + dy < h and a[y + dy, x + dx] for dx, dy in near):
                out.putpixel((x, y), OUT)
    return out


def hd(rows, seed, diagonal=False, thin=True):
    """A 1x design outlined and remastered at 2x, as the compass and HUD sprites are."""
    return ui_hd.remaster(outlined(rows, diagonal=diagonal), seed=seed, thin=thin)


# The four seasons' glyphs: 12x12 with a pixel of room for the outline all round. Each is drawn round the
# point the HUD turns it about: spring's sprig sways from the foot of its stem, summer's sun turns about its
# middle, autumn's leaf swings from its stalk at the top, winter's flake turns about its middle.
GLYPHS = {
    'spring': [
        "............",
        ".....kk.....",
        "...kkpkkk...",
        "..kpppyppk..",
        "..kppyYypK..",
        "...kpyypK...",
        "...KkppKK...",
        "..ee.nn.....",
        ".eEEhn......",
        "..ehhn..ee..",
        ".....nhhEe..",
        "............",
    ],
    'summer': [
        "............",
        ".....aa.....",
        "..a..yy..a..",
        "...aYYYya...",
        "...YYyyya...",
        ".ayYyyyyyAa.",
        ".ayyyyyyAAa.",
        "...yyyyAA...",
        "...ayAAAa...",
        "..a..AA..a..",
        ".....aa.....",
        "............",
    ],
    'autumn': [
        "............",
        ".....n......",
        "..F..n..f...",
        "..FF.n.ff...",
        ".FFFFnfffr..",
        "..FFfnffr...",
        ".FFffnfffrr.",
        "..Fffnffrr..",
        "...ffnfrr...",
        "....fnrr....",
        ".....rR.....",
        "............",
    ],
    'winter': [
        "............",
        ".C...C...C..",
        "..c..C..c...",
        "...c.c.c....",
        "....cCc.....",
        ".CCcCbCcCC..",
        "....cCc.....",
        "...c.c.c....",
        "..c..C..c...",
        ".C...C...C..",
        "............",
        "............",
    ],
}

# The festivals' emblems, 12x12 like the glyphs
EMBLEMS = {
    # Ribbonwake: the pole with its crown of flowers, ribbons streaming from it
    'ribbonwake': [
        "............",
        "...EkpkE....",
        "...kpppk....",
        "....NnmmM...",
        "....Nnuumm..",
        "....Nnvvuum.",
        "....Nn..vvu.",
        "....Nn....v.",
        "....Nn......",
        "....Nn......",
        "...NNnn.....",
        "............",
    ],
    # Sunhearth: the sun wheel, its spokes gilt and a flame at its hub
    'sunhearth': [
        "............",
        "....yGGy....",
        "..yG.G.Gy...",
        "..G..G..G...",
        ".yG.xlx.Gy..",
        ".GGGlLlGGG..",
        ".yG.xlx.Gy..",
        "..G..G..G...",
        "..yG.G.Gy...",
        "....yGGy....",
        "............",
        "............",
    ],
    # Sheafmoot: a sheaf of wheat bound with a red band
    'sheafmoot': [
        "............",
        "..Y.yY.Y....",
        "..yYyGyYy...",
        "...yGyGy....",
        "...GyGyG....",
        "....GgG.....",
        "....rRr.....",
        "....GgG.....",
        "...GgGgG....",
        "..GgG.GgG...",
        "..g.....g...",
        "............",
    ],
    # Lanternrise: a round paper lantern, lit, a small star over it
    'lanternrise': [
        "............",
        ".....Y......",
        "....YLY.....",
        ".....Y......",
        "...XXXXX....",
        "..xlLLLlx...",
        "..xLLLLlx...",
        "..xlLLllx...",
        "..xlllllx...",
        "...XXXXX....",
        ".....n......",
        "............",
    ],
}

# Motes drifting over the Almanac's pages and round the season's banner: 5x5, no outline, soft
MOTES = {
    'spring': [
        ".kk..",
        "kppk.",
        "kpppk",
        ".kpk.",
        "..k..",
    ],
    'summer': [
        "..a..",
        ".aya.",
        "ayYya",
        ".aya.",
        "..a..",
    ],
    'autumn': [
        "..F..",
        ".FFf.",
        "FFffr",
        ".frr.",
        "..n..",
    ],
    'winter': [
        ".C.C.",
        "..c..",
        "CcCcC",
        "..c..",
        ".C.C.",
    ],
}

GLINT = [
    "..C..",
    "..c..",
    "CcCcC",
    "..c..",
    "..C..",
]


def medallion():
    """The compass's season medallion: a gilt ring lit from the upper left round a dark well, 14x14."""
    n = 14
    rows = []
    for y in range(n):
        row = ''
        for x in range(n):
            dx, dy = x + 0.5 - n / 2, y + 0.5 - n / 2
            d = math.hypot(dx, dy)
            if d > 6.25:
                row += '.'
            elif d > 5.2:
                # The rim: lit toward the upper left, dark toward the lower right
                lit = -(dx + dy) / max(d, 0.01)
                row += 'G' if lit > 0.45 else 'g' if lit > -0.45 else 'd'
            else:
                row += 'Z' if (dx + dy) < -3.5 else 'z'
        rows.append(row)
    return ui_hd.remaster(outlined(rows), seed=900, thin=True)


def needle():
    """The Almanac's needle, tip up: a gilt diamond at its head, a fine shaft and a ring at its foot round the
    pin, 6x52 at 1x."""
    w, h = 6, 52
    rows = [['.'] * w for _ in range(h)]
    # The diamond head
    head = [1, 1, 2, 2, 2, 2, 1, 1]
    for i, half in enumerate(head):
        y = 1 + i
        for x in range(3 - half, 3 + half):
            rows[y][x] = 'G' if x < 3 else 'g'
    rows[1 + 3][2] = 'W'
    # The shaft
    for y in range(1 + len(head), h - 6):
        rows[y][2] = 'G'
        rows[y][3] = 'd'
    # The ring round the pin
    for y, row in enumerate(["..gg..", ".gddg.", ".gddg.", "..gg.."]):
        for x, ch in enumerate(row):
            if ch != '.':
                rows[h - 6 + y][x] = ch
    return ui_hd.remaster(outlined([''.join(r) for r in rows]), seed=901, thin=True)


# ================================================================ painted at 2x outright

def glow():
    """A soft round glow, white, fading to nothing at its edge: tinted by whatever draws it."""
    n = 32
    xs, ys = grid(n)
    d = np.hypot(xs - n / 2, ys - n / 2) / (n / 2)
    a = np.clip(1 - d, 0, 1) ** 1.8
    cv = Canvas(n)
    cv.rgb[:] = 255
    cv.a = a
    return cv.image()


SEASON_RGB = {
    'spring': (168, 218, 122),
    'summer': (240, 196, 84),
    'autumn': (224, 132, 58),
    'winter': (180, 210, 234),
}


def wheel():
    """The wheel of the year, 240x240 (drawn 120x120 in the Almanac): a gilt rim round a dark band where the game
    marks the days, and inside it four fields, one per season, each washed with its colour, spring at the top and
    the year going round clockwise, gilt spokes at the seasons' turns, a ring engraved where the glyphs sit and a
    boss at the middle for the needle's pin. All measured in the Almanac's own pixels (x2 here)."""
    K = 2
    n = 120 * K
    xs, ys = grid(n)
    cx = cy = n / 2
    dx, dy = xs - cx, ys - cy
    r = np.hypot(dx, dy) / K
    # Angle from the top, clockwise, in turns 0..1
    turn = (np.arctan2(dx, -dy) / (2 * math.pi)) % 1.0
    cv = Canvas(n)
    grain = fbm(n, n, 10, 61)
    fine = rand(n, n, 62)
    lit = -(dx + dy) / np.maximum(np.hypot(dx, dy), 1e-6)

    # The four fields
    field = r < 46.0
    season = np.minimum((turn * 4).astype(int), 3)
    base = np.zeros((n, n, 3))
    for i, key in enumerate(('spring', 'summer', 'autumn', 'winter')):
        base[season == i] = SEASON_RGB[key]
    dark = np.array([26.0, 24.0, 32.0])
    # Washed into the dark: stronger toward the rim, faint at the middle, with a little parchment grain
    wash = 0.16 + 0.22 * np.clip((r - 8) / 38, 0, 1)
    col = dark + (base - dark) * wash[..., None]
    col = col * (0.9 + 0.2 * grain[..., None]) * (0.97 + 0.06 * fine[..., None])
    cv.put(field, col)
    # A soft shade toward the lower right inside the rim, as if the dish were sunk
    shade = np.clip((dx + dy) / (K * 46.0), -1, 1)
    cv.rgb[field] = cv.rgb[field] * (1 - 0.12 * shade[field, None])

    # The engraved ring where the glyphs sit, and a finer one inside it
    for rr, k in ((32.0 + 8.6, 0.55), (32.0 - 8.6, 0.4)):
        ring = field & (np.abs(r - rr) < 0.45)
        cv.rgb[ring] = cv.rgb[ring] * (1 - k * 0.6)
        under = field & (np.abs(r - (rr + 0.7)) < 0.35)
        cv.rgb[under] = np.clip(cv.rgb[under] * 1.18 + 6, 0, 255)

    # The spokes at the seasons' turns, from the boss to the band
    for i in range(4):
        a = i * math.pi / 2
        ux, uy = math.sin(a), -math.cos(a)
        across = np.abs(dx * uy - dy * ux) / K
        along = (dx * ux + dy * uy) / K
        spoke = field & (across < 0.9) & (along > 5.0)
        t = 0.62 + 0.35 * lit
        cv.put(spoke, bands(PAL['gold'], np.where(across < 0.45, t + 0.1, t - 0.2)))
        edge = field & (across >= 0.9) & (across < 1.4) & (along > 5.0)
        cv.rgb[edge] = cv.rgb[edge] * 0.55

    # The band the days are marked on: dark, finely grained
    band = (r >= 46.0) & (r < 55.0)
    bt = 0.26 + 0.1 * grain + 0.06 * (fine - 0.5) - 0.06 * np.clip((r - 50.5) / 4.5, -1, 1) ** 2
    cv.put(band, bands(PAL['blacksteel'], bt + 0.08))
    inner_lip = (r >= 45.4) & (r < 46.6)
    cv.put(inner_lip, bands(PAL['gold'], 0.42 - 0.3 * lit))

    # The gilt rim
    rim_m = (r >= 55.0) & (r < 59.2)
    across = (r - 55.0) / 4.2
    hgt = np.sin(np.clip(across, 0, 1) * math.pi)
    t = 0.46 + 0.42 * lit * hgt + 0.18 * hgt + 0.08 * (grain - 0.5)
    cv.put(rim_m, bands(PAL['gold'], t))
    # Little studs on the rim at the seasons' turns and halfway between
    for i in range(8):
        a = i * math.pi / 4
        sx, sy = cx + math.sin(a) * 57.1 * K, cy - math.cos(a) * 57.1 * K
        sm, sd = disc(sx, sy, 1.55 * K, n)
        cv.put(sm, bands(PAL['gold'], 0.5 + 0.9 * light(sd * 3.0, 1.0) + 0.25 * sd))
    # A dark line outside it all
    edge = (r >= 59.2) & (r < 59.9)
    cv.put(edge, np.array([14.0, 12.0, 18.0]))

    # The boss at the middle, for the needle's pin
    bm, bd = disc(cx, cy, 4.6 * K, n)
    cv.put(bm, bands(PAL['gold'], 0.45 + 1.1 * light(bd * 4.0, 1.0) + 0.2 * bd))
    pin, _ = disc(cx, cy, 1.4 * K, n)
    cv.put(pin, np.array([30.0, 22.0, 20.0]))
    return cv.image()


# ================================================================ the particles

# Blossom: near white, a blush toward the base of the petal (the game tints it white, blush or pink)
PETAL = [(200, 150, 170), (226, 186, 202), (244, 214, 226), (252, 232, 240), (255, 244, 248), (255, 252, 252)]

PETAL_SHAPES = [
    # a single round petal, notched at its tip
    ([(8.0, 13.6), (5.4, 12.0), (3.8, 9.4), (3.6, 6.4), (4.8, 3.8), (6.6, 2.6), (8.0, 4.2), (9.4, 2.6),
      (11.2, 3.8), (12.4, 6.4), (12.2, 9.4), (10.6, 12.0)],
     [(8.0, 13.0), (8.0, 6.0)]),
    # a narrower petal, curled at one side
    ([(7.4, 13.8), (5.6, 11.4), (5.0, 8.0), (5.8, 4.6), (7.6, 2.4), (9.4, 3.0), (10.8, 5.4), (11.0, 8.8),
      (9.8, 11.6)],
     [(7.6, 13.0), (8.4, 5.0)]),
]

# Leaves: pale and neutral, so the game tints each with the colour of the leaves it fell from
LEAF = rp.LEAF

LEAF_SHAPES = [
    # an oak's leaf, its lobes in pairs down the midrib, a stalk at its foot
    ([(8.0, 15.4), (7.4, 13.4), (5.0, 13.0), (5.6, 11.4), (3.4, 10.6), (4.4, 9.0), (2.8, 7.6), (4.4, 6.6),
      (3.8, 4.6), (5.8, 4.4), (6.2, 2.4), (8.0, 1.0), (9.8, 2.4), (10.2, 4.4), (12.2, 4.6), (11.6, 6.6),
      (13.2, 7.6), (11.6, 9.0), (12.6, 10.6), (10.4, 11.4), (11.0, 13.0), (8.6, 13.4)],
     [(8.0, 15.2), (8.0, 2.4)]),
    # a long pointed leaf, broad at the middle, the stalk curved
    ([(6.8, 15.2), (7.2, 13.2), (5.0, 11.6), (3.8, 8.8), (4.2, 5.6), (6.0, 3.0), (9.2, 0.8), (11.4, 3.6),
      (12.0, 6.8), (11.0, 10.0), (8.8, 12.6), (7.8, 13.4), (7.6, 15.4)],
     [(7.2, 15.0), (7.8, 10.0), (9.0, 2.0)]),
]


def petals():
    """Two blossom petals, four frames each as it tumbles: sprite 4 * shape + frame."""
    out = []
    for s, (shape, rib) in enumerate(PETAL_SHAPES):
        for f, (k, shade) in enumerate(rp.TUMBLE):
            out.append(rp.fleck(shape, k, shade * 0.6 + 0.08 + (0.06 if f == 3 else 0.0), PETAL, 300 + s * 10 + f,
                                rib=rib if f != 3 else None))
    return out


def leaves():
    """Two whole leaves, four frames each as it tumbles: sprite 4 * shape + frame."""
    out = []
    for s, (shape, rib) in enumerate(LEAF_SHAPES):
        for f, (k, shade) in enumerate(rp.TUMBLE):
            out.append(rp.fleck(shape, k, shade + (0.06 if f == 3 else 0.0), LEAF, 400 + s * 10 + f,
                                rib=rib if f != 3 else None))
    return out


def breaths():
    """Four frames of a breath on cold air: a dense little puff that loosens into a wisp as it spreads (the
    game grows it and fades it out over these)."""
    out = []
    white = [(255, 255, 255), (240, 246, 252), (220, 230, 242), (200, 214, 232)]
    for i in range(4):
        img = rp.blank()
        rng = np.random.default_rng(500 + i)
        loose = i / 3.0
        # A few soft lobes, further apart and fainter as the puff spreads
        for j in range(4 + i):
            ang = rng.random() * math.tau
            dist = (0.6 + 2.2 * loose) * rng.random()
            px = 7.5 + math.cos(ang) * dist
            py = 7.8 + math.sin(ang) * dist * 0.8
            rp.soft_dot(img, px, py, 3.4 + 1.6 * loose + rng.random() * 0.8, white, falloff=1.6,
                        peak=int(150 - 40 * loose))
        out.append(img)
    return out


FROST = [(255, 255, 255), (230, 244, 255), (176, 214, 246), (120, 170, 226)]


def frost_glints():
    """Four sizes of a frost glint, a cold four-point sparkle (the game shows them growing and shrinking)."""
    return [rp.glint(FROST, size) for size in range(4)]


# ================================================================ the Almanac

N = 32


def almanac():
    """The Almanac, 32x: a stout book in green leather, gilt at its corners and bands, the wheel of the year
    tooled on its cover in the four seasons' colours, its pages' edge showing and a red ribbon hanging out."""
    xs, ys = grid(N)
    cv = Canvas(N)
    grain = fbm(N, N, 4, 71)
    fine = rand(N, N, 72)
    # The page block, showing past the cover on the right and at the foot
    pages = (xs > 8) & (xs < 27.5) & (ys > 4.5) & (ys < 29.5)
    lines = (np.mod(ys, 2.0) < 1.0)
    pt = 0.62 + 0.12 * (grain - 0.5) - 0.12 * lines - 0.25 * (xs > 26.5) - 0.2 * (ys > 28.5)
    cv.put(pages, bands(PAL['parchment'], pt))
    # The back cover's edge under the pages
    back = (xs > 7.5) & (xs < 28.5) & (ys > 28.5) & (ys < 30.5)
    cv.put(back, bands(PAL['moss'], 0.25 + 0.1 * grain))
    # The front cover
    cover = (xs > 6.5) & (xs < 26.5) & (ys > 2.5) & (ys < 28.0)
    hgt = np.clip(np.minimum.reduce([xs - 6.5, 26.5 - xs, ys - 2.5, 28.0 - ys]) / 2.0, 0, 1)
    ct = 0.55 + 0.7 * light(hgt * 2.0, 1.0) + 0.12 * (grain - 0.5) + 0.05 * (fine - 0.5)
    cv.put(cover, bands(PAL['moss'], ct))
    # The spine, rounded, with gilt bands across it
    spine = (xs > 3.5) & (xs < 8.5) & (ys > 2.0) & (ys < 28.5)
    round_ = np.sin(np.clip((xs - 3.5) / 5.0, 0, 1) * math.pi)
    st = 0.3 + 0.55 * round_ - 0.25 * (xs > 7.0) + 0.08 * (grain - 0.5)
    cv.put(spine, bands(PAL['moss'], st))
    for by in (6.0, 24.5):
        band = spine & (np.abs(ys - by) < 1.0)
        cv.put(band, bands(PAL['gold'], 0.35 + 0.5 * round_ + 0.15 * (ys < by)))
    # Gilt corners on the cover
    for (x0, y0, sx, sy) in ((26.0, 3.0, -1, 1), (26.0, 27.5, -1, -1)):
        u = (xs - x0) * sx
        v = (ys - y0) * sy
        corner = cover & (u >= 0) & (v >= 0) & (u + v < 4.2)
        cv.put(corner, bands(PAL['gold'], 0.5 + 0.35 * (u + v < 2.2) - 0.1 * (sx > 0)))
    # The wheel of the year tooled on the cover: a gilt ring round four quarters
    wx, wy = 16.8, 15.0
    dd = np.hypot(xs - wx, ys - wy)
    ring = (dd >= 4.6) & (dd < 6.0)
    cv.put(ring, bands(PAL['gold'], 0.5 + 0.3 * (-(xs - wx) - (ys - wy)) / 6.0))
    inside = dd < 4.6
    turn = (np.arctan2(xs - wx, -(ys - wy)) / (2 * math.pi)) % 1.0
    q = np.minimum((turn * 4).astype(int), 3)
    lit_in = (-(xs - wx) - (ys - wy)) / 6.0
    for i, key in enumerate(('spring', 'summer', 'autumn', 'winter')):
        c = np.array(SEASON_RGB[key], float)
        m = inside & (q == i)
        # Enamel, a little lighter toward the upper left
        cv.put(m, np.clip(c[None, None, :] * (0.78 + 0.16 * lit_in)[..., None], 0, 255))
    hub = dd < 1.3
    cv.put(hub, bands(PAL['gold'], np.full((N, N), 0.8)))
    # Spokes between the quarters
    for a in range(4):
        ang = a * math.pi / 2
        ux, uy = math.sin(ang), -math.cos(ang)
        across = np.abs((xs - wx) * uy - (ys - wy) * ux)
        along = (xs - wx) * ux + (ys - wy) * uy
        sp = inside & (across < 0.5) & (along > 0)
        cv.put(sp, bands(PAL['gold'], np.full((N, N), 0.45)))
    # The ribbon, hanging out at the foot
    rib = (np.abs(xs - (20.0 + (ys - 28.0) * 0.25)) < 1.0) & (ys > 27.5) & (ys < 31.8)
    cv.put(rib, bands(PAL['crimson'], 0.55 + 0.3 * (xs < 20.0 + (ys - 28.0) * 0.25)))
    notch = (np.abs(xs - (20.0 + (31.0 - 28.0) * 0.25)) < 0.5) & (ys > 30.8)
    cv.a[notch] = 0
    outline(cv, 0.8)
    glint(cv, 9, 4, 1, (255, 236, 200))
    glint(cv, 13, 10, 1, (255, 236, 200))
    return cv.image()


# ================================================================ writing it all

def gui_sprites():
    out = {'medallion': medallion(), 'glow': glow(), 'wheel': wheel(), 'needle': needle()}
    for i, (k, rows) in enumerate(GLYPHS.items()):
        out[k] = hd(rows, seed=910 + i)
    for i, (k, rows) in enumerate(EMBLEMS.items()):
        out['festival_' + k] = hd(rows, seed=920 + i)
    for i, (k, rows) in enumerate(MOTES.items()):
        out['mote_' + k] = ui_hd.remaster(ui_art.from_rows(rows, PAL1), seed=930 + i, bevel=False, grain=0.02)
    out['glint'] = ui_hd.remaster(ui_art.from_rows(GLINT, PAL1), seed=940, bevel=False, grain=0.0)
    return out


def particles():
    return {
        'season_petal': petals(),
        'season_leaf': leaves(),
        'season_breath': breaths(),
        'season_glint': frost_glints(),
    }


def write(assets, prev=None):
    gui = gui_sprites()
    folder = os.path.join(assets, 'textures/gui/sprites/season')
    os.makedirs(folder, exist_ok=True)
    for k, img in gui.items():
        img.save(os.path.join(folder, k + '.png'))
    parts = particles()
    for pid, frames in parts.items():
        for i, img in enumerate(frames):
            img.save(os.path.join(assets, 'textures/particle', f'{pid}_{i}.png'))
        with open(os.path.join(assets, 'particles', pid + '.json'), 'w') as f:
            json.dump({'textures': [f'guildmark:{pid}_{i}' for i in range(len(frames))]}, f, indent=2)
            f.write('\n')
    book = almanac()
    book.save(os.path.join(assets, 'textures/item/almanac.png'))
    with open(os.path.join(assets, 'models/item/almanac.json'), 'w') as f:
        json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/almanac'}}, f, indent=2)
        f.write('\n')
    with open(os.path.join(assets, 'items/almanac.json'), 'w') as f:
        json.dump({'model': {'type': 'minecraft:model', 'model': 'guildmark:item/almanac'}}, f, indent=2)
        f.write('\n')
    if prev:
        os.makedirs(prev, exist_ok=True)
        preview(gui, parts, book, os.path.join(prev, 'season_art.png'))


def preview(gui, parts, book, path):
    """A contact sheet: the sprites magnified on the HUD's dark, the particles on a night and a day sky, the
    Almanac large and at its size in a slot."""
    from hdkit import sheet
    small = [gui[k] for k in gui if k not in ('wheel', 'needle', 'glow')]
    labels = [k for k in gui if k not in ('wheel', 'needle', 'glow')]
    a = sheet(small, 6, 7, labels=labels)
    b = sheet([gui['wheel'], gui['needle'], gui['glow']], 1, 3, labels=['wheel', 'needle', 'glow'])
    frames = [f for fs in parts.values() for f in fs]
    c = sheet(frames, 6, 8, bg=(26, 30, 44))
    tinted = [rp.tinted(f, (220, 120, 50)) for f in parts['season_leaf']] + [rp.tinted(f, (255, 200, 220)) for f in parts['season_petal']]
    d = sheet(tinted, 6, 8, bg=(120, 150, 190))
    e = sheet([book], 8, 1)
    w = max(i.width for i in (a, b, c, d, e))
    out = Image.new('RGBA', (w, a.height + b.height + c.height + d.height + e.height), (40, 38, 46, 255))
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
