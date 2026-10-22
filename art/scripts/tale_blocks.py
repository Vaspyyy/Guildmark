"""The memorial block's look, painted and modelled with the repo's block kit (works_art.py's `cube`, hdkit.py's ramps). Part G of the
Tales design (living-world/stories/design.md, 13.2.1), made to carry what the villages' design (11.4, 20.4) asks of the same block:
names cut in the stone, and candles.

    python3 -B art/scripts/tale_blocks.py src/main/resources/assets/guildmark [preview-dir]

One block (`guildmark:memorial`), two looks chosen by the block-state property `kind` (`stone`, `cairn`), and `facing`, `candles` (0 to
4), `lit` and `mossy` besides. The blockstate is a multipart: for the way it faces, the base of its kind (turned), the moss over it if
`mossy`, then a candle stub for each candle that stands and, if `lit`, its flame. Writes

  * `blockstates/memorial.json`;
  * `models/block/`: `memorial_stone`, `memorial_cairn` (the bases); `memorial_<kind>_moss` and `_moss_east`, `_moss_south`, `_moss_west`
    (the moss for each way the memorial faces: laid out in the world's frame, so it grows on the world's north side whichever way
    the stone looks); `memorial_<kind>_candle_1..4` and `memorial_<kind>_flame_1..4`;
  * `models/item/memorial_stone.json`, `memorial_cairn.json` (the creative hand's, each with two lit candles) and `items/memorial.json`;
  * the textures `memorial_stone`, `memorial_cairn` (sheets), `memorial_moss`, `memorial_wax` and the two flame strips `memorial_flame_a`
    and `_b`, each animated by its `.mcmeta`.

Authored facing north (the carved face, the name-slab and the candles toward -z); the blockstate turns it. A model element may not
run past -16..32 (the game refuses the whole model), so the stone, two blocks high, cannot have a candle on its top: the candles
stand on a ledge at the front (the stone's foot, the cairn's step), where a mourner kneels to set them. `candle_tip` says where each
flame burns, for the smoke the block adds. Everything is seeded: a rerun gives the same files. With a preview dir it also writes the
sheets (both looks from four sides, every candle state by day and by night, the four facings with their moss, the flames moving, the
creative hand's two models, the textures).
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
from hdkit import PAL, Canvas, grid, fbm, rand, bands, mix, shift, light, blur, path_dist
from works_art import cube, Rx, Ry, Rz, SIX
import island_blockview as BV

NS = 'guildmark:block/'

# ================================================================ the textures

SHEET_W, SHEET_H = 160, 64              # the stone's and the cairn's sheets, in texels (two to a model pixel)

MOSS_RAMP = PAL['moss']
LICHEN = (120, 118, 84)
WAX = [(78, 70, 58), (130, 118, 96), (178, 164, 134), (214, 200, 168), (236, 226, 196), (250, 244, 222)]       # beeswax: warm, creamy
FLAME = [(70, 20, 8), (150, 54, 14), (214, 104, 24), (244, 164, 52), (255, 214, 110), (255, 246, 200)]

# where the faces of the stone's shaft are on its sheet (texels): the shaft is 27 pixels tall, so 54 rows
FRONT_X, BACK_X, WEST_X, EAST_X = 64, 88, 112, 130
FACE_ROWS = 54


def put(cv, rgb, x, y):
    """Lay an RGB array into the canvas at texel (x, y), opaque."""
    h, w = rgb.shape[:2]
    cv.rgb[y:y + h, x:x + w] = rgb
    cv.a[y:y + h, x:x + w] = 1.0


def tone_of(w, h, seed, rough=1.0, lift=0.0, cracks=0):
    """The tone of weathered stone, [w] x [h] texels, lit from the upper left: a height field of chisel facets (the height snapped to steps,
    so the light breaks into planes), finer mottling over it, and [cracks] cracks running down. [rough] scales how broken the planes are."""
    hh = fbm(w, h, 10, seed) * 1.6 + fbm(w, h, 4, seed + 1) * 0.5
    facets = np.round(hh * 5) / 5
    t = 0.62 + lift + 0.6 * rough * light(facets * 3.0, 1.0) + 0.16 * rough * (fbm(w, h, 3, seed + 2) - 0.5)
    rng = np.random.default_rng(seed)
    for _ in range(cracks):
        x, y = int(rng.integers(3, max(4, w - 3))), int(rng.integers(2, max(3, h // 2)))
        for _ in range(int(rng.integers(max(5, h // 5), max(7, h // 2)))):
            if 0 <= x < w and 0 <= y < h:
                t[y, x] -= 0.34
                if x + 1 < w:
                    t[y, x + 1] += 0.1
            x = int(np.clip(x + rng.choice((-1, 0, 0, 1)), 1, w - 2))
            y += 1
    return t


def rgb_of(t, tint=0.0, pal='stone', dither=0.25):
    rgb = bands(PAL[pal], t, dither)
    if tint:
        rgb = mix(rgb, bands(PAL['slate'], t + 0.08, dither), tint)
    return rgb


def lichen_over(rgb, w, h, seed, amount):
    """Pale lichen in blotches over [rgb], more toward the foot."""
    xs, ys = grid(w, h)
    m = (fbm(w, h, 3, seed + 5) > 0.66) & (rand(w, h, seed + 6) < amount) | ((fbm(w, h, 3, seed + 5) > 0.74) & (ys > h * 0.4))
    out = rgb.astype(float).copy()
    out[m] = out[m] * 0.55 + np.array(LICHEN) * 0.45
    return out


def stone_tile(w, h, seed, rough=1.0, tint=0.0, lift=0.0, cracks=1, lichen=0.0):
    t = tone_of(w, h, seed, rough, lift, cracks)
    rgb = rgb_of(t, tint)
    return lichen_over(rgb, w, h, seed, lichen) if lichen else rgb


def carve(t, cut, deep=0.42, lip=0.26):
    """Tone [t] with [cut] (a mask) carved into it: the cut dark, the lip below and right of it (which faces the light) bright."""
    out = t.copy()
    out[cut] -= deep
    out[shift(cut, 1, 1) & ~cut] += lip
    return out


def line_mask(w, h, pts, width=0.9):
    return path_dist(pts, w, h)[0] < width / 2


def edge_light(t, w, h, c0, c1, r0=0, r1=None, k=0.11):
    """A chamfer along a face's edges: the top and left edges catch the light, the bottom and right fall into shade."""
    r1 = h if r1 is None else r1
    t[r0:r0 + 1, c0:c1] += k
    t[r0:r1, c0:c0 + 1] += k
    t[r1 - 1:r1, c0:c1] -= k
    t[r0:r1, c1 - 1:c1] -= k


def shaft_face(w, h, seed, rough=0.45, lift=0.04):
    """A smoothed face of the shaft: planes broken only lightly, dark streaks running down from the top where the rain runs, a few
    slanting tool marks, a crack."""
    t = tone_of(w, h, seed, rough, lift, 1)
    streak = np.repeat(fbm(w // 3 + 1, h, 5, seed + 3, 2), 3, axis=1)[:, :w]
    t = t - 0.12 * np.clip((streak - 0.5) * 2.4, 0, 1) * np.clip(1 - np.arange(h)[:, None] / (h * 0.7), 0, 1)
    xs, ys = grid(w, h)
    marks = (np.abs(np.sin((xs + ys * 0.8) * 0.9 + 3 * fbm(w, h, 6, seed + 2))) > 0.95) & (rand(w, h, seed + 4) > 0.45)
    return np.where(marks, t - 0.07, t)


def front_face(seed=11, w=24, h=FACE_ROWS):
    """The stone's carved front, the shaft's whole height (rows 0 to 53 are the 27 pixels from the top): a groove inset round the edge, a
    wreath tied with a bow, and below it rows of cut marks where the names are; no readable lettering (the names are read by right-click)."""
    t = shaft_face(w, h, seed)
    panel = np.zeros((h, w), bool)
    panel[7:h - 5, 5:w - 5] = True                                                                                        # a polished panel inside the groove
    t = np.where(panel, tone_of(w, h, seed + 7, 0.2, 0.13), t)
    cut = np.zeros((h, w), bool)
    cut |= line_mask(w, h, [(4.5, 6.5), (w - 4.5, 6.5), (w - 4.5, h - 4.5), (4.5, h - 4.5), (4.5, 6.5)], 1.4)             # the groove
    cx, cy, R_ = w / 2, 17.5, 5.6
    for i in range(14):                                                                                                   # the wreath
        a = i * math.tau / 14
        px_, py_ = cx + R_ * math.cos(a), cy + R_ * math.sin(a)
        tx, ty = -math.sin(a), math.cos(a)
        cut |= line_mask(w, h, [(px_ - tx * 1.3, py_ - ty * 1.3), (px_ + tx * 1.3, py_ + ty * 1.3)], 1.5)
    cut |= line_mask(w, h, [(cx, 23.4), (cx - 3.0, 26.8)], 1.5) | line_mask(w, h, [(cx, 23.4), (cx + 3.0, 26.8)], 1.5)  # the bow's two ends
    cut |= line_mask(w, h, [(cx, 22.8), (cx, 24.2)], 1.8)
    rows = ((31, ((6, 10), (12, 18))), (36, ((6, 18),)), (41, ((6, 9), (11, 18))), (46, ((8, 16),)))                      # the names
    for y, segs in rows:
        for a, b in segs:
            x = a
            while x < b:
                n = 2 if ((x + y) % 3) else 3
                cut[y:y + 2, x:min(x + n, b)] = True
                x += n + 1
    t = carve(t, cut)
    for c0, c1, r0, r1 in tier_spans('x'):                                                                              # a chamfer on each tier
        edge_light(t, w, h, c0, c1, r0, r1)
    return rgb_of(t)


def side_face(w, h, seed, tint=0.0):
    t = shaft_face(w, h, seed)
    for c0, c1, r0, r1 in tier_spans('z'):
        edge_light(t, w, h, c0, c1, r0, r1)
    return rgb_of(t, tint)


def back_face(w, h, seed):
    t = shaft_face(w, h, seed)
    for c0, c1, r0, r1 in tier_spans('x'):
        edge_light(t, w, h, c0, c1, r0, r1)
    return rgb_of(t)


def slab_face(seed=5, w=16, h=20):
    """The cairn's name-slab: a slab of dark slate set up at its front, a ring cut at the top and rows of cut marks below."""
    xs, ys = grid(w, h)
    t = tone_of(w, h, seed, 0.5, 0.02, 1)
    cut = np.zeros((h, w), bool)
    cut |= (np.abs(np.hypot(xs - 8, ys - 5.2) - 2.6) < 0.5)
    cut |= line_mask(w, h, [(8, 4.2), (8, 6.2)], 0.9) | line_mask(w, h, [(7, 5.2), (9, 5.2)], 0.9)
    for y, a, b in ((11, 3, 13), (14, 3, 10), (17, 4, 12)):
        x = a
        while x < b:
            n = 2 if ((x + y) % 3) else 3
            cut[y:y + 1 + ((x * 5 + y) % 3 == 0), x:min(x + n, b)] = True
            x += n + 1
    t = carve(t, cut, 0.26, 0.15)
    edge_light(t, w, h, 0, w)
    return bands(PAL['slate'], t + 0.04, 0.25)


def tiles_of(seed, rough, tints):
    """The four 32 x 32 tiles on the left of a sheet, as float RGB: warm, cool, dark, pale."""
    return [stone_tile(32, 32, seed, rough, 0.0, 0.0, 1, 0.3),
            stone_tile(32, 32, seed + 13, rough, tints[0], -0.03, 1, 0.2),
            stone_tile(32, 32, seed + 26, rough * 1.1, tints[1], -0.1, 2, 0.1),
            stone_tile(32, 32, seed + 39, rough * 0.9, tints[2], 0.1, 1, 0.2)]


def stone_sheet():
    """memorial_stone: four weathered tiles (warm, cool, dark, and a pale smooth one for the cap's top) and the shaft's four faces."""
    cv = Canvas(SHEET_W, SHEET_H)
    t = tiles_of(100, 0.9, (0.55, 0.12, 0.1))
    for (x, y), rgb in zip(((0, 0), (32, 0), (0, 32), (32, 32)), t):
        put(cv, rgb, x, y)
    put(cv, stone_tile(32, 32, 160, 0.4, 0.12, 0.1, 0), 32, 32)
    put(cv, front_face(11), FRONT_X, 0)
    put(cv, back_face(24, FACE_ROWS, 21), BACK_X, 0)
    put(cv, side_face(18, FACE_ROWS, 31, 0.2), WEST_X, 0)
    put(cv, side_face(18, FACE_ROWS, 41, 0.55), EAST_X, 0)
    return cv


def cairn_sheet():
    """memorial_cairn: four fieldstone tones (warm, cool, dark, pale), the name-slab's face, a flagstone for the step and the capstone,
    and the slab's slate for its edges."""
    cv = Canvas(SHEET_W, SHEET_H)
    for (x, y), rgb in zip(((0, 0), (32, 0), (0, 32), (32, 32)), tiles_of(200, 1.25, (0.5, 0.18, 0.1))):
        put(cv, rgb, x, y)
    put(cv, slab_face(5), 64, 0)
    put(cv, stone_tile(32, 32, 220, 0.5, 0.05, 0.1, 0), 96, 0)
    put(cv, rgb_of(tone_of(32, 32, 230, 0.5, 0.0, 1), 0.0, 'slate'), 96, 32)
    return cv


MOSS_W = MOSS_H = 64                    # the moss sheet: a thick tile at (0,0), a long fade at (32,0) and a short fade at (0,32), 32 x 32 each


def moss_tex(seed=71):
    """memorial_moss (64 x 64): moss in clumps with ragged edges and nothing at all between them (a cutout), the `moss` ramp lit from the
    upper left. The first tile is thick (a foot, the tops of stones); the second thins upward over 22 rows from its foot (the stone's
    north side) and the third over 14 (its other sides): each thickest at the bottom."""
    w, h = MOSS_W, MOSS_H
    f = fbm(w, h, 5, seed, 3)
    g = fbm(w, h, 2.5, seed + 3, 2)
    cover = f + 0.35 * (g - 0.5)
    ys = np.arange(h)[:, None] * np.ones((1, w))
    xs = np.arange(w)[None, :] * np.ones((h, 1))
    thr = np.full((h, w), 0.37)
    for tx, ty, span in ((32, 0, 22), (0, 32, 14)):
        inside = (ys >= ty) & (ys < ty + 32) & (xs >= tx) & (xs < tx + 32)
        d = np.clip((ys - ty - (32 - span)) / span, 0, 1)
        thr = np.where(inside, 0.74 - 0.40 * d, thr)
    thr = np.where((ys >= 32) & (xs >= 32), 9.0, thr)                          # the fourth tile is empty
    depth = cover - thr
    on = depth > 0
    height = np.clip(depth * 5.0, 0, 1)
    t = 0.38 + 0.7 * height + 0.8 * light(blur(height, 1), 5.0) + 0.1 * (rand(w, h, seed + 5) - 0.5)
    cv = Canvas(w, h)
    cv.put(on, bands(MOSS_RAMP, t, 0.15))
    return cv


def wax_tex():
    """memorial_wax (16 x 16): a stub's two sides at (0,0) and (4,0), 4 x 12 texels each; its top at (8,0); the wick at (12,0); the pool
    the stub stands in, its side at (0,12) and its top at (8,12)."""
    cv = Canvas(16, 16)
    for x0, seed, dx, dl in ((0, 3, 2.6, 7.0), (4, 5, 1.2, 9.0)):
        xs, ys = grid(4, 12)
        t = 0.80 - 0.30 * (xs / 4) + 0.05 * (rand(4, 12, seed) - 0.5)
        t = np.where((np.abs(xs - dx) < 0.6) & (ys < dl), t - 0.2, t)               # a run of wax down the side
        t[0:1, :] += 0.1
        put(cv, bands(WAX, t, 0.1), x0, 0)
    xs, ys = grid(4, 4)
    topt = np.where((xs > 1) & (xs < 3) & (ys > 1) & (ys < 3), 0.52, 0.84)         # the dimple round the wick
    put(cv, bands(WAX, topt, 0), 8, 0)
    put(cv, np.tile(np.array(PAL['ink'][1], float), (2, 2, 1)), 12, 0)
    put(cv, bands(WAX, 0.7 - 0.2 * (xs / 4), 0), 0, 12)
    put(cv, bands(WAX, np.full((4, 4), 0.86), 0), 8, 12)
    return cv


FLAME_FRAMES = 6


def flame_frames(phase):
    """The candle flame, 8 x 8 texels a frame: a teardrop with a near-white core, a yellow body and an orange edge, leaning and
    growing and shrinking from frame to frame; [phase] changes how each frame leans, so two candles never flicker together."""
    frames = []
    for i in range(FLAME_FRAMES):
        k = (i + phase * 2) % FLAME_FRAMES
        lean = (0.0, 0.6, -0.5, 0.3, -0.7, 0.5)[k]
        top = (1.4, 0.8, 1.6, 1.0, 1.8, 1.2)[k]
        cv = Canvas(8, 8)
        xs, ys = grid(8, 8)
        wy = np.clip((ys - top) / (7.6 - top), 0, 1)
        half = 1.85 * np.sin(wy * math.pi * 0.5) ** 0.75
        fx = 3.9 + lean * (1 - wy)
        fl = (ys >= top) & (ys <= 7.6) & (np.abs(xs - fx) <= half)
        t = 0.95 - 0.6 * np.abs(xs - fx) / np.maximum(half, 0.4) - 0.2 * (1 - wy)
        cv.put(fl, bands(FLAME, t, 0))
        cv.put(fl & (wy > 0.5) & (np.abs(xs - fx) < 0.7), (255, 248, 222))
        frames.append(cv)
    out = Canvas(8, 8 * FLAME_FRAMES)
    for i, f in enumerate(frames):
        out.rgb[i * 8:(i + 1) * 8] = f.rgb
        out.a[i * 8:(i + 1) * 8] = f.a
    return out


# ================================================================ the geometry

FACINGS = ('north', 'east', 'south', 'west')
TILE_AT = ((0, 0), (32, 0), (0, 32), (32, 32))      # the four 32 x 32 texel tiles on the left of each sheet

# Where the candles stand: on the front ledge (the stone's foot, the cairn's step), each kind's own four places, (x, z) with the front
# at -z, the first candle first; and the height of the ledge they stand on.
LEDGE = {'stone': 5.5, 'cairn': 8.6}
SLOTS = {'stone': ((8.6, 2.1), (5.6, 1.9), (11.6, 1.9), (3.6, 2.2)),
         'cairn': ((8.6, -10.8), (5.6, -11.4), (11.6, -11.4), (3.6, -10.8))}
CANDLE_H = (4.5, 3.5, 5.0, 3.0)         # the stubs' heights, no two alike: one is burnt lower, one is new


def uv_px(w, h, x0, y0, x1, y1):
    """The uv (sixteenths of the whole texture) of the texel box (x0, y0, x1, y1) of a w x h texture."""
    return [x0 * 16.0 / w, y0 * 16.0 / h, x1 * 16.0 / w, y1 * 16.0 / h]


def sheet_uv(x0, y0, x1, y1):
    return uv_px(SHEET_W, SHEET_H, x0, y0, x1, y1)


def patch(tile, w_px, h_px, rng):
    """A [w_px] x [h_px] model-pixel patch of a sheet's tile [tile] (two texels to the pixel) at a place [rng] picks, as a uv; a face
    bigger than the tile shows the whole tile stretched over it."""
    tx, ty = TILE_AT[tile]
    tw, th = min(32, round(w_px * 2)), min(32, round(h_px * 2))
    ox, oy = int(rng.integers(0, 32 - tw + 1)), int(rng.integers(0, 32 - th + 1))
    return sheet_uv(tx + ox, ty + oy, tx + ox + tw, ty + oy + th)


def turn_pt(x, z, k):
    """A point turned [k] quarter turns clockwise (seen from above) about the cell's middle, as the blockstate's `y` turns a model."""
    for _ in range(k % 4):
        x, z = 16.0 - z, x
    return x, z


def turn_box(frm, to, k):
    (x0, z0), (x1, z1) = turn_pt(frm[0], frm[2], k), turn_pt(to[0], to[2], k)
    return (min(x0, x1), frm[1], min(z0, z1)), (max(x0, x1), to[1], max(z0, z1))


def grown(frm, to, e):
    return tuple(v - e for v in frm), tuple(v + e for v in to)


# ---------------------------------------------------------------- the stone

# A plinth with a ledge in front, then a shaft that narrows in five tiers (a hand wider at the foot), under a cap with a flat top for
# the posy. A tier is (y0, y1, x0, x1, z0, z1) in pixels, every width a whole number of texels.
PLINTH = ((0.75, 0.0, 0.5), (15.25, 5.5, 13.5))
Y_EDGES = (5.5, 10.5, 15.5, 20.5, 25.5, 30.0)
SEGS = tuple((Y_EDGES[i], Y_EDGES[i + 1], 2.0 + 0.25 * i, 14.0 - 0.25 * i, 3.5 + 0.25 * i, 12.5 - 0.25 * i) for i in range(5))
CAP = ((2.5, 30.0, 4.0), (13.5, 32.0, 12.0))


def seg_columns(seg):
    """A tier's width and depth in texels, the first column of the shaft's faces it shows (the front and back are 24 texels wide, the
    sides 18, and the tiers narrow toward the top), and the rows (the faces' 54 rows run from the top, y = 32, down to y = 5)."""
    y0, y1, x0, x1, z0, z1 = seg
    w, d = round((x1 - x0) * 2), round((z1 - z0) * 2)
    return w, d, (24 - w) // 2, (18 - d) // 2, round((32 - y1) * 2), round((32 - y0) * 2)


def tier_spans(axis):
    """For each tier, (first column, past-last column, first row, past-last row) on the front and back ('x') or the sides ('z')."""
    out = []
    for seg in SEGS:
        w, d, cw, cd, r0, r1 = seg_columns(seg)
        out.append((cw, cw + w, r0, r1) if axis == 'x' else (cd, cd + d, r0, r1))
    return out


def stone_elements():
    """The standing stone, facing north: a plinth, a shaft in three courses with the carved face toward -z, a cap, one block wide and two
    high, the top at 32 for the posy."""
    rng = np.random.default_rng(31)
    S = 'sheet'
    els = [cube(*PLINTH, S, ('north', 'south', 'west', 'east', 'up'),
                uv={'north': patch(0, 14.5, 5.5, rng), 'south': patch(0, 14.5, 5.5, rng), 'west': patch(1, 13, 5.5, rng),
                    'east': patch(1, 13, 5.5, rng), 'up': patch(2, 14.5, 13, rng)})]
    for seg in SEGS:
        y0, y1, x0, x1, z0, z1 = seg
        w, d, cw, cd, r0, r1 = seg_columns(seg)
        els.append(cube((x0, y0, z0), (x1, y1, z1), S, ('north', 'south', 'west', 'east', 'up'),
                        uv={'north': sheet_uv(FRONT_X + cw, r0, FRONT_X + cw + w, r1), 'south': sheet_uv(BACK_X + cw, r0, BACK_X + cw + w, r1),
                            'west': sheet_uv(WEST_X + cd, r0, WEST_X + cd + d, r1), 'east': sheet_uv(EAST_X + cd, r0, EAST_X + cd + d, r1),
                            'up': patch(2, w / 2, d / 2, rng)}))
    els.append(cube(*CAP, S, SIX,
                    uv={'north': patch(0, 11, 2, rng), 'south': patch(0, 11, 2, rng), 'west': patch(1, 8, 2, rng), 'east': patch(1, 8, 2, rng),
                        'up': sheet_uv(37, 40, 59, 56), 'down': patch(1, 11, 8, rng)}))
    return els


def moss_uv(rng, tile, ncols, row0, row1):
    """The moss sheet's tile [tile] (1 the long fade, 2 the short), [ncols] texels wide about its middle, rows [row0] to [row1]
    counted up from the tile's foot."""
    tx, ty = ((0, 0), (32, 0), (0, 32))[tile]
    c0 = tx + (32 - ncols) // 2
    return uv_px(MOSS_W, MOSS_H, c0, ty + 32 - row1, c0 + ncols, ty + 32 - row0)


def dense_uv(rng, w_px, h_px):
    """A patch of the thick tile, [w_px] x [h_px] model pixels, at a place [rng] picks."""
    tw, th = min(32, round(w_px * 2)), min(32, round(h_px * 2))
    ox, oy = int(rng.integers(0, 32 - tw + 1)), int(rng.integers(0, 32 - th + 1))
    return uv_px(MOSS_W, MOSS_H, ox, oy, ox + tw, oy + th)


def stone_moss_elements(k):
    """The moss on the stone for a memorial facing [k] quarter turns round: the plinth's four sides and its top, and up the shaft, high on
    the world's north face and a hand's breadth on the others. Laid out in the world's own frame (the blockstate does not turn it), so
    the green is always on the north, whichever way the stone looks."""
    rng = np.random.default_rng(71 + k)
    M = 'moss'
    e = 0.04
    f0, f1 = grown(*turn_box(*PLINTH, k), e)
    wx, wz = f1[0] - f0[0] - 2 * e, f1[2] - f0[2] - 2 * e
    els = [cube(f0, f1, M, ('north', 'south', 'west', 'east', 'up'),
                uv={'north': dense_uv(rng, wx, 5.5), 'south': dense_uv(rng, wx, 5.5), 'west': dense_uv(rng, wz, 5.5),
                    'east': dense_uv(rng, wz, 5.5), 'up': dense_uv(rng, wx, wz)})]
    for (y0, y1, x0, x1, z0, z1) in SEGS:
        t0, t1 = turn_box((x0, y0, z0), (x1, y1, z1), k)
        wx, wz = round((t1[0] - t0[0]) * 2), round((t1[2] - t0[2]) * 2)
        up0, up1 = (y0 - SEGS[0][0]) * 2, (y1 - SEGS[0][0]) * 2           # the tier's rows, counted up from the shaft's foot
        for d, n in (('north', wx), ('south', wx), ('west', wz), ('east', wz)):
            tile, reach = (1, 22) if d == 'north' else (2, 17)             # the world's north: thick for a hand's breadth, thinning for a hand more
            row0, row1 = up0, min(up1, reach)
            if row1 - row0 < 1:
                continue
            g0, g1 = grown((t0[0], y0, t0[2]), (t1[0], y0 + (row1 - row0) / 2, t1[2]), e)
            els.append(cube(g0, g1, M, (d,), uv={d: moss_uv(rng, tile, n, int(row0), int(row1))}))
    return els


# ---------------------------------------------------------------- the cairn

CAIRN_SLAB = (64, 0, 80, 20)            # the name-slab's face on the cairn sheet, in texels
CAIRN_FLAG = (96, 0, 128, 32)           # the flagstone for the step and the capstone
CAIRN_STEP = ((2.0, -2.0, -13.0), (14.0, 8.6, -5.5))        # the flagstone step in front, where the candles stand
CAIRN_CAP = ((1.5, 29.2, 2.5), (14.5, 32.0, 13.5))          # the capstone: flat, at 32, wider than the crown under it

# the pile's courses, set back as they rise and thinner toward the top (laid up like a stack of fieldstone): ring count, ring radius,
# the foot's height, the rocks' heights and widths, the filling rock's width, where the ring starts (degrees)
COURSES = ((9, 16.5, -3.0, (10.0, 12.0), (9.5, 12.0), 22.0, 18.0),
           (7, 10.5, 6.5, (5.5, 7.0), (8.0, 10.5), 15.0, 30.0),
           (6, 7.6, 12.2, (3.6, 4.6), (8.0, 10.0), 12.0, 10.0),
           (5, 5.6, 16.4, (3.2, 4.2), (7.0, 9.0), 9.5, 54.0),
           (4, 4.0, 20.0, (3.2, 4.2), (6.5, 8.0), 7.5, 20.0),
           (4, 3.6, 23.6, (3.0, 4.0), (6.5, 8.0), 7.5, 0.0),
           (3, 3.4, 26.0, (3.0, 3.6), (5.5, 6.5), 6.5, 40.0))


def cairn_rocks():
    """The fieldstones of the pile, as plain numbers (so the base and the moss turn the same pile): each course a rock in the middle and a
    ring of them, the footing running to -3 (below the ground) so a slope shows no gap, the whole inside the -16..32 a model element may
    span. The front is left to the step and the name-slab."""
    rng = np.random.default_rng(7)
    rocks = []

    def rock(cx, cz, y0, h, s, course):
        rocks.append({'cx': cx, 'cz': cz, 'y0': y0, 'h': h, 's': s, 'course': course, 'ang': float(rng.uniform(0, 90)),
                      'tilt': (float(rng.uniform(-7, 7)), float(rng.uniform(-7, 7))), 'tile': int(rng.integers(0, 4))})

    for c, (n, r, y0, hr, sr, fill, start) in enumerate(COURSES):
        rock(8.0, 8.0, y0, (hr[0] + hr[1]) / 2, fill, c)
        for i in range(n):
            a = math.radians(start) + math.tau * (i + rng.uniform(-0.1, 0.1)) / n
            rr = r + rng.uniform(-0.9, 0.9)
            rock(8 + rr * math.cos(a), 8 + rr * math.sin(a), y0 + rng.uniform(-0.5, 0.4), rng.uniform(*hr), rng.uniform(*sr), c)
    for i in range(9):                                                                                                    # a few strays round the foot
        a = math.tau * (i + rng.uniform(-0.2, 0.2)) / 9
        rr = rng.uniform(19.0, 20.5)
        rock(8 + rr * math.cos(a), 8 + rr * math.sin(a), -2.0, rng.uniform(4.5, 6.0), rng.uniform(3.0, 4.5), 0)
    rocks = [r for r in rocks if not (r['course'] <= 1 and r['cz'] < 1.0 and abs(r['cx'] - 8) < 6.5 and r['s'] < 20)]       # the front is the step's
    rock(8.0, -1.0, 5.0, 10.5, 8.0, 1)                                                                                     # what the slab leans on
    return rocks


def rock_elements(r, rng, k=0, moss=False):
    """One fieldstone as two boxes, the second turned 45 degrees (an eight-sided stone), the whole leaning a little, its pile turned [k]
    quarter turns. [moss] gives the overlay that goes on it (the tops and the north side) instead of the stone."""
    cx, cz = turn_pt(r['cx'], r['cz'], k)
    out = []
    if moss and (r['course'] > 2 or float(rng.random()) < 0.35):
        return out
    ox = int(rng.integers(0, 16)) if moss else 0
    for j, (scale, yaw, drop) in enumerate(((1.0, 0.0, 0.0), (0.88, 45.0, 0.03))):
        s = r['s'] * scale
        e = 0.05 if moss else 0.0
        frm = (cx - s / 2 - e, r['y0'], cz - s / 2 - e)
        to = (cx + s / 2 + e, r['y0'] + r['h'] - drop + e, cz + s / 2 + e)
        R = Ry(r['ang'] + yaw - 90 * k) @ Rx(r['tilt'][0]) @ Rz(r['tilt'][1])
        origin = (cx, r['y0'] + r['h'] / 2, cz)
        if moss:
            out.append(cube(frm, to, 'moss', ('up', 'north') if j == 0 else ('up',),
                            uv={'up': uv_px(MOSS_W, MOSS_H, ox, 0, ox + 16, 16), 'north': uv_px(MOSS_W, MOSS_H, ox, 16, ox + 16, 32)}, rot=R, origin=origin))
            continue
        tile = (r['tile'] + j) % 4
        h = r['h']
        out.append(cube(frm, to, 'sheet', ('north', 'south', 'west', 'east', 'up'), rot=R, origin=origin,
                        uv={'north': patch(tile, s, h, rng), 'south': patch(tile, s, h, rng), 'west': patch(tile, s, h, rng),
                            'east': patch(tile, s, h, rng), 'up': patch(tile, s, s, rng)}))
    return out


def cairn_front_elements(k=0):
    """The front of the pile: a flagstone step to stand the candles on (its top at LEDGE['cairn']) and, leaning on the pile behind it,
    the name-slab, a flat of dark slate with the ring and the cut marks on its face."""
    y = LEDGE['cairn']
    cx, cz = turn_pt(8.0, -7.2, k)
    R = Ry(-90 * k) @ Rx(11.0)
    rng = np.random.default_rng(13)
    sf, st = turn_box(*CAIRN_STEP, k)
    return [
        cube(sf, st, 'sheet', ('north', 'south', 'west', 'east', 'up'),
             uv={'north': patch(0, 12, 10.6, rng), 'south': patch(0, 12, 10.6, rng), 'west': patch(0, 7.5, 10.6, rng), 'east': patch(0, 7.5, 10.6, rng),
                 'up': sheet_uv(98, 4, 122, 19)}),
        cube((cx - 4.0, y, cz - 1.0), (cx + 4.0, y + 10.0, cz + 1.0), 'sheet', ('north', 'south', 'west', 'east', 'up'), rot=R, origin=(cx, y, cz),
             uv={'north': sheet_uv(*CAIRN_SLAB), 'south': sheet_uv(100, 36, 116, 56), 'west': sheet_uv(100, 36, 104, 56),
                 'east': sheet_uv(108, 36, 112, 56), 'up': sheet_uv(100, 36, 116, 40)}),
    ]


def cairn_elements():
    """The cairn, facing north (the step and the slab toward -z): three blocks across at the foot, two high, a flat capstone at 32."""
    rng = np.random.default_rng(23)
    els = []
    for r in cairn_rocks():
        els += rock_elements(r, rng)
    els += [cube(*CAIRN_CAP, 'sheet', ('north', 'south', 'west', 'east', 'up', 'down'), rot=Ry(3.0), origin=(8.0, 29.2, 8.0),
                 uv={'north': patch(1, 13, 2.8, rng), 'south': patch(1, 13, 2.8, rng), 'west': patch(1, 11, 2.8, rng), 'east': patch(1, 11, 2.8, rng),
                     'up': sheet_uv(98, 4, 124, 26), 'down': sheet_uv(*CAIRN_FLAG)})]
    return els + cairn_front_elements()


def cairn_moss_elements(k):
    """The cairn's moss for a memorial facing [k] quarter turns round: patches on the lower courses' tops and north sides."""
    rng = np.random.default_rng(91 + k)
    els = []
    for r in cairn_rocks():
        els += rock_elements(r, rng, k, moss=True)
    return els


# ---------------------------------------------------------------- the candles and their flames

def candle_elements(kind, i):
    """Candle [i] (0 to 3) of a [kind]: a pool of wax it stands in, a stub two pixels square, a wick."""
    cx, cz = SLOTS[kind][i]
    y = LEDGE[kind]
    h = CANDLE_H[i]
    faces = ('north', 'south', 'west', 'east', 'up')
    pool = cube((cx - 1.5, y, cz - 1.5), (cx + 1.5, y + 0.45, cz + 1.5), 'wax', faces, uv={'*': [0, 12, 4, 16], 'up': [8, 12, 12, 16]})
    stub = cube((cx - 1.0, y + 0.4, cz - 1.0), (cx + 1.0, y + h, cz + 1.0), 'wax', faces,
                uv={'north': [0, 0, 4, 2 * h], 'south': [4, 0, 8, 2 * h], 'west': [0, 0, 4, 2 * h], 'east': [4, 0, 8, 2 * h], 'up': [8, 0, 12, 4]})
    wick = cube((cx - 0.25, y + h, cz - 0.25), (cx + 0.25, y + h + 0.9, cz + 0.25), 'wax', faces, uv={'*': [12, 0, 14, 2]})
    return [pool, stub, wick]


def flame_elements(kind, i):
    """Candle [i]'s flame: two planes crossed over the wick, full bright, in the animated flame strip (two strips, so the candles do
    not flicker together). The planes are 4 pixels square, the flame in them about two wide and three tall."""
    cx, cz = SLOTS[kind][i]
    y0 = LEDGE[kind] + CANDLE_H[i] + 0.6
    w = 4.0
    var = 'flame_a' if i % 2 == 0 else 'flame_b'
    full = [0, 0, 16, 16]
    return [cube((cx - w / 2, y0, cz), (cx + w / 2, y0 + w, cz), var, ('north', 'south'), uv=full, light_emission=15, shade=False),
            cube((cx, y0, cz - w / 2), (cx, y0 + w, cz + w / 2), var, ('west', 'east'), uv=full, light_emission=15, shade=False)]


def candle_tip(kind, i):
    """Where candle [i]'s flame burns, (x, y, z) in 16ths of the cell, authored facing north (the code that adds smoke turns it by facing)."""
    cx, cz = SLOTS[kind][i]
    return (cx, LEDGE[kind] + CANDLE_H[i] + 1.6, cz)


# ================================================================ the files

TEXTURE_OF = {'moss': NS + 'memorial_moss', 'wax': NS + 'memorial_wax', 'flame_a': NS + 'memorial_flame_a', 'flame_b': NS + 'memorial_flame_b'}


def textures_for(elements, kind):
    """The model's `textures`: the variables its elements name, and a particle."""
    names = []
    for e in elements:
        for f in e['faces'].values():
            t = f['texture'][1:]
            if t not in names:
                names.append(t)
    sheet = NS + 'memorial_' + kind
    tex = {'particle': sheet if 'sheet' in names else TEXTURE_OF[names[0]]}
    for n in names:
        tex[n] = sheet if n == 'sheet' else TEXTURE_OF[n]
    return tex


def model_json(elements, kind='stone', extra=None):
    m = {'parent': 'minecraft:block/block', 'ambientocclusion': False}
    m.update(extra or {})
    m['textures'] = textures_for(elements, kind)
    m['elements'] = elements
    return m


def models():
    """Every block model, by file name: the two bases, their moss (one for each way the memorial can face), the candles and the flames."""
    out = {'memorial_stone': model_json(stone_elements(), 'stone'), 'memorial_cairn': model_json(cairn_elements(), 'cairn')}
    for k, f in enumerate(FACINGS):
        suffix = '' if k == 0 else '_' + f
        out['memorial_stone_moss' + suffix] = model_json(stone_moss_elements(k), 'stone')
        out['memorial_cairn_moss' + suffix] = model_json(cairn_moss_elements(k), 'cairn')
    for kind in ('stone', 'cairn'):
        for i in range(4):
            out[f'memorial_{kind}_candle_{i + 1}'] = model_json(candle_elements(kind, i), kind)
            out[f'memorial_{kind}_flame_{i + 1}'] = model_json(flame_elements(kind, i), kind)
    return out


def blockstate():
    """The multipart: for each way it faces, the base of its kind (turned), the moss if `mossy` (laid out for that facing, not turned),
    and, for each candle that stands, its stub (turned) and, if `lit`, its flame."""
    parts = []
    for k, f in enumerate(FACINGS):
        y = {'y': 90 * k} if k else {}
        suffix = '' if k == 0 else '_' + f
        for kind in ('stone', 'cairn'):
            parts.append({'when': {'kind': kind, 'facing': f}, 'apply': {'model': f'{NS}memorial_{kind}', **y}})
        for kind in ('stone', 'cairn'):
            parts.append({'when': {'kind': kind, 'facing': f, 'mossy': 'true'}, 'apply': {'model': f'{NS}memorial_{kind}_moss{suffix}'}})
    for k, f in enumerate(FACINGS):
        y = {'y': 90 * k} if k else {}
        for kind in ('stone', 'cairn'):
            for n in range(1, 5):
                stands = '|'.join(str(c) for c in range(n, 5))
                parts.append({'when': {'kind': kind, 'facing': f, 'candles': stands}, 'apply': {'model': f'{NS}memorial_{kind}_candle_{n}', **y}})
                parts.append({'when': {'kind': kind, 'facing': f, 'candles': stands, 'lit': 'true'},
                              'apply': {'model': f'{NS}memorial_{kind}_flame_{n}', **y}})
    return {'multipart': parts}


def item_models():
    """The creative hand's two models: the stone and the cairn, each with two lit candles on it (so the icon moves), set to fit the slot
    and the hands by the extent of what is in them."""
    out = {}
    for kind in ('stone', 'cairn'):
        els = ((stone_elements() if kind == 'stone' else cairn_elements()) + candle_elements(kind, 0) + candle_elements(kind, 1)
               + flame_elements(kind, 0) + flame_elements(kind, 1))
        out['memorial_' + kind] = model_json(els, kind, {'display': display_for(els)})
    return out


def extent(elements):
    lo, hi = np.full(3, 1e9), np.full(3, -1e9)
    for e in elements:
        lo, hi = np.minimum(lo, e['from']), np.maximum(hi, e['to'])
    return lo, hi


def gui_fit(lo, hi, rot=(30.0, 225.0, 0.0), fill=0.92):
    """The scale and translation that put a model with corners [lo], [hi] in the middle of the slot at the game's inventory angle (the
    transform is translate . rotate . scale about the model's middle, in blocks)."""
    corners = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]) / 16.0 - 0.5
    p = corners @ (Rx(rot[0]) @ Ry(rot[1]) @ Rz(rot[2])).T
    (x0, y0), (x1, y1) = p[:, :2].min(0), p[:, :2].max(0)
    s = fill / max(x1 - x0, y1 - y0)
    return s, (-(x0 + x1) / 2 * s * 16.0, -(y0 + y1) / 2 * s * 16.0)


def display_for(elements):
    lo, hi = extent(elements)
    s, (tx, ty) = gui_fit(lo, hi)
    q = min(1.0, 22.0 / float(max(hi - lo)))                     # the model against a vanilla block's 16 pixels
    r3_ = lambda v: round(float(v), 3)
    ground = 0.25 * q
    return {
        'gui': {'rotation': [30, 225, 0], 'translation': [r3_(tx), r3_(ty), 0], 'scale': [r3_(s)] * 3},
        'ground': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [r3_(ground)] * 3},
        'fixed': {'rotation': [0, 0, 0], 'translation': [0, -2, 0], 'scale': [r3_(0.5 * q)] * 3},
        'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [r3_(0.375 * q)] * 3},
        'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [r3_(0.375 * q)] * 3},
        'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [r3_(0.4 * q)] * 3},
        'firstperson_lefthand': {'rotation': [0, 225, 0], 'translation': [0, 0, 0], 'scale': [r3_(0.4 * q)] * 3},
    }


def item_definition():
    """items/memorial.json: the model by the block-state `kind` the stack carries (a block item given with `block_state={kind:cairn}`),
    else by a custom_model_data string `cairn`, else the stone."""
    stone = {'type': 'minecraft:model', 'model': 'guildmark:item/memorial_stone'}
    cairn = {'type': 'minecraft:model', 'model': 'guildmark:item/memorial_cairn'}
    return {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:block_state', 'block_state_property': 'kind',
        'cases': [{'when': 'stone', 'model': stone}, {'when': 'cairn', 'model': cairn}],
        'fallback': {'type': 'minecraft:select', 'property': 'minecraft:custom_model_data',
                     'cases': [{'when': 'cairn', 'model': cairn}], 'fallback': stone}}}


def textures():
    """Every texture of the block, by file name."""
    return {'memorial_stone': stone_sheet().image(), 'memorial_cairn': cairn_sheet().image(), 'memorial_moss': moss_tex().image(),
            'memorial_wax': wax_tex().image(), 'memorial_flame_a': flame_frames(0).image(), 'memorial_flame_b': flame_frames(2).image()}


MCMETA = {'memorial_flame_a': {'animation': {'frametime': 2, 'frames': [0, 1, 2, 3, 4, 5, 3, 1]}},
          'memorial_flame_b': {'animation': {'frametime': 3, 'frames': [0, 1, 2, 3, 4, 5, 3, 1]}}}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as fh:
        json.dump(obj, fh, indent=2)
        fh.write('\n')


def sweep(folder, keep, suffixes):
    """Remove the memorial's files in [folder] that this run did not write (a model renamed or dropped leaves nothing stale behind)."""
    if not os.path.isdir(folder):
        return
    for fn in os.listdir(folder):
        if fn.startswith('memorial_') and fn not in keep and fn.endswith(suffixes):
            os.remove(os.path.join(folder, fn))


def write_all(assets):
    block_tex = os.path.join(assets, 'textures', 'block')
    kept = set()
    for name, im in textures().items():
        path = os.path.join(block_tex, name + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        im.save(path)
        kept.add(name + '.png')
        if name in MCMETA:
            write_json(path + '.mcmeta', MCMETA[name])
            kept.add(name + '.png.mcmeta')
    sweep(block_tex, kept, ('.png', '.mcmeta'))
    for sub, group in (('block', models()), ('item', item_models())):
        for name, m in group.items():
            write_json(os.path.join(assets, 'models', sub, name + '.json'), m)
        sweep(os.path.join(assets, 'models', sub), {n + '.json' for n in group}, ('.json',))
    write_json(os.path.join(assets, 'blockstates', 'memorial.json'), blockstate())
    write_json(os.path.join(assets, 'items', 'memorial.json'), item_definition())


def verify(assets):
    """Every model sound: elements inside -16..32, uvs inside the texture and over painted texels, every named texture there; the
    blockstate names only models that exist; no fully saturated pixel anywhere."""
    bad = []
    imgs = {}

    def img(ref):
        if ref not in imgs:
            imgs[ref] = np.asarray(Image.open(os.path.join(assets, 'textures', 'block', ref.split('/')[-1] + '.png')).convert('RGBA'))
        return imgs[ref]

    files = {}
    for sub, names in (('block', models()), ('item', item_models())):
        for name in names:
            files[(sub, name)] = json.load(open(os.path.join(assets, 'models', sub, name + '.json')))
    for (sub, name), m in files.items():
        for e in m['elements']:
            for v in list(e['from']) + list(e['to']):
                if not -16 <= v <= 32:
                    bad.append(f'{name}: element past -16..32: {e["from"]} {e["to"]}')
                    break
            for d, f in e['faces'].items():
                var = f['texture'][1:]
                if var not in m['textures']:
                    bad.append(f'{name}: texture {var} not in the model')
                    continue
                u0, v0, u1, v1 = f['uv']
                if min(u0, v0, u1, v1) < -1e-6 or max(u0, v0, u1, v1) > 16 + 1e-6:
                    bad.append(f'{name}: uv outside the texture {f["uv"]}')
                    continue
                t = img(m['textures'][var])
                th, tw = t.shape[:2]
                x0, x1 = int(math.floor(u0 / 16 * tw + 1e-6)), int(math.ceil(u1 / 16 * tw - 1e-6))
                y0, y1 = int(math.floor(v0 / 16 * th + 1e-6)), int(math.ceil(v1 / 16 * th - 1e-6))
                if var.startswith('flame'):
                    continue
                region = t[y0:y1, x0:x1]
                if region.size == 0:
                    bad.append(f'{name}: empty uv region {f["uv"]}')
                elif var != 'moss' and (region[..., 3] < 255).any():
                    bad.append(f'{name}: uv {f["uv"]} of {var} reaches clear texels')
    for fn in os.listdir(os.path.join(assets, 'textures', 'block')):
        if fn.startswith('memorial_') and fn.endswith('.png'):
            t = np.asarray(Image.open(os.path.join(assets, 'textures', 'block', fn)).convert('RGBA')).astype(int)
            solid = t[..., 3] > 0
            hot = solid & (t[..., :3].max(-1) >= 200) & (t[..., :3].min(-1) <= 4)
            if hot.any():
                bad.append(f'{fn}: {int(hot.sum())} fully saturated pixels')
    bs = json.load(open(os.path.join(assets, 'blockstates', 'memorial.json')))
    for part in bs['multipart']:
        mod = part['apply']['model'].split(':')[1].split('/')[-1]
        if ('block', mod) not in files:
            bad.append(f'blockstate names {mod}')
    return bad


# ================================================================ the previews

_orig_faces = BV.faces_of


def turn_arr(c, k):
    c = np.array(c, float)
    for _ in range(k % 4):
        x, z = c[:, 0].copy(), c[:, 2].copy()
        c[:, 0], c[:, 2] = 16.0 - z, x
    return c


def _turned_faces(model):
    """island_blockview's faces with each element turned by its `_turn` quarter turns, as a blockstate's `y` turns a model."""
    faces = _orig_faces(model)
    out, i = [], 0
    for el in model.get('elements', []):
        n = len(el.get('faces', {}))
        k = el.get('_turn', 0)
        for (c, vuv, ref, sh, emit, nrm) in faces[i:i + n]:
            if k:
                c = turn_arr(c, k)
                for _ in range(k % 4):
                    nrm = np.array([-nrm[2], nrm[1], nrm[0]])
                if sh != 1.0:
                    sh = BV.SHADE[BV.nearest_direction(nrm)]
            out.append((c, vuv, ref, sh, emit, nrm))
        i += n
    return out


def compose(kind, k=0, candles=0, lit=False, mossy=False):
    """A preview model of the block in a state: what the multipart would apply, the elements of each marked with how they are turned."""
    base = stone_elements() if kind == 'stone' else cairn_elements()
    els = [dict(e, _turn=k) for e in base]
    if mossy:
        els += [dict(e, _turn=0) for e in (stone_moss_elements(k) if kind == 'stone' else cairn_moss_elements(k))]
    for i in range(candles):
        els += [dict(e, _turn=k) for e in candle_elements(kind, i)]
        if lit:
            els += [dict(e, _turn=k) for e in flame_elements(kind, i)]
    return {'textures': textures_for(els, kind), 'elements': els}


def view(model, assets, yaw=145.0, elev=26.0, scale=5.0, size=(230, 300), center=(8, 17, 8), **kw):
    BV.faces_of = _turned_faces
    try:
        return BV.render_model(model, assets, yaw=yaw, elev=elev, scale=scale, size=size, center=center, **kw)
    finally:
        BV.faces_of = _orig_faces


DAY = (86, 90, 100)
NIGHT = (14, 14, 24)


def panel(im, label, w=None):
    out = Image.fromarray(im)
    d = ImageDraw.Draw(out)
    d.text((6, 4), label, fill=(236, 230, 214))
    return out


def strip(images, pad=6, bg=(30, 30, 38)):
    h = max(i.height for i in images)
    w = sum(i.width for i in images) + pad * (len(images) + 1)
    out = Image.new('RGB', (w, h + 2 * pad), bg)
    x = pad
    for i in images:
        out.paste(i, (x, pad))
        x += i.width + pad
    return out


def grid_of(rows, pad=6, bg=(30, 30, 38)):
    strips = [strip(r, pad, bg) for r in rows]
    w = max(s.width for s in strips)
    out = Image.new('RGB', (w, sum(s.height for s in strips)), bg)
    y = 0
    for s in strips:
        out.paste(s, (0, y))
        y += s.height
    return out


def previews(assets, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    A = assets

    def stone(**kw):
        return compose('stone', **kw)

    def cairn(**kw):
        return compose('cairn', **kw)

    cairn_view = dict(scale=3.4, size=(250, 280), center=(8, 14, 8))
    # the two looks, from four sides
    rows = [[panel(view(stone(candles=2, lit=True), A, yaw=y, bg=DAY), f'stone yaw {y}') for y in (150, 180, 210, 20)],
            [panel(view(cairn(candles=2, lit=True), A, yaw=y, bg=DAY, **cairn_view), f'cairn yaw {y}') for y in (150, 180, 210, 20)]]
    grid_of(rows).save(os.path.join(out_dir, 'tales_memorial_looks.png'))
    # the candle states, by day and by night
    rows = [[panel(view(stone(candles=n), A, bg=DAY, size=(190, 300)), f'candles {n}') for n in range(5)],
            [panel(view(stone(candles=n, lit=True), A, bg=DAY, size=(190, 300)), f'lit {n}') for n in range(5)],
            [panel(view(stone(candles=n, lit=True), A, bg=NIGHT, ambient=0.28, size=(190, 300)), f'night {n}') for n in range(5)]]
    grid_of(rows).save(os.path.join(out_dir, 'tales_memorial_candles.png'))
    # the four facings, each with the moss it would carry (the moss stays on the world's north)
    rows = [[panel(view(stone(k=k, candles=2, lit=True, mossy=True), A, yaw=135, bg=DAY, size=(220, 300)), f'stone facing {FACINGS[k]}') for k in range(4)],
            [panel(view(cairn(k=k, candles=2, lit=True, mossy=True), A, yaw=135, bg=DAY, **cairn_view), f'cairn facing {FACINGS[k]}') for k in range(4)]]
    grid_of(rows).save(os.path.join(out_dir, 'tales_memorial_facings.png'))
    # mossy and not, close
    rows = [[panel(view(stone(mossy=m), A, yaw=y, bg=DAY, scale=8, size=(260, 340), center=(8, 14, 8)), f'stone moss {m} yaw {y}') for m in (False, True) for y in (150, 30)],
            [panel(view(cairn(mossy=m), A, yaw=y, bg=DAY, scale=4.2, size=(260, 300), center=(8, 12, 8)), f'cairn moss {m} yaw {y}') for m in (False, True) for y in (150, 30)]]
    grid_of(rows).save(os.path.join(out_dir, 'tales_memorial_moss.png'))
    # the flames moving
    frames = []
    for t in range(0, 36, 2):
        m = stone(candles=4, lit=True)
        a = view(m, A, yaw=170, elev=20, bg=NIGHT, ambient=0.3, scale=9, size=(250, 200), center=(8, 11, 4), tick=t)
        b = view(compose('cairn', candles=4, lit=True), A, yaw=170, elev=20, bg=NIGHT, ambient=0.3, scale=6, size=(250, 200), center=(8, 11, -4), tick=t)
        frames.append(Image.fromarray(np.concatenate([a, b], axis=1)))
    frames[0].save(os.path.join(out_dir, 'tales_memorial_flames.gif'), save_all=True, append_images=frames[1:], duration=100, loop=0)
    # the creative hand: the two item models as the inventory slot shows them, large
    tiles = []
    for kind in ('stone', 'cairn'):
        m = json.load(open(os.path.join(assets, 'models', 'item', f'memorial_{kind}.json')))
        lo, hi = extent(m['elements'])
        s, _ = gui_fit(lo, hi)
        mid = (lo + hi) / 2
        tiles.append(panel(BV.render_model(m, assets, yaw=135, elev=30, scale=s * 16, size=(256, 256), center=tuple(mid), bg=(60, 60, 68), ss=2), f'{kind} in a slot'))
        tiles.append(Image.fromarray(BV.render_model(m, assets, yaw=135, elev=30, scale=s * 4, size=(64, 64), center=tuple(mid), bg=(60, 60, 68), ss=4)).resize((256, 256), Image.NEAREST))
    strip(tiles).save(os.path.join(out_dir, 'tales_memorial_items.png'))
    # the textures
    sheets = []
    for name in ('memorial_stone', 'memorial_cairn'):
        sheets.append(Image.open(os.path.join(assets, 'textures', 'block', name + '.png')).convert('RGBA').resize((512, 256), Image.NEAREST))
    small = []
    for name in ('memorial_moss', 'memorial_wax'):
        im = Image.open(os.path.join(assets, 'textures', 'block', name + '.png')).convert('RGBA')
        small.append(im.resize((im.width * 6, im.height * 6), Image.NEAREST))
    for name in ('memorial_flame_a', 'memorial_flame_b'):
        im = Image.open(os.path.join(assets, 'textures', 'block', name + '.png')).convert('RGBA')
        row = Image.new('RGBA', (im.width * 6 * 6 + 5 * 6, im.width * 6), (0, 0, 0, 0))
        for i in range(im.height // im.width):
            row.alpha_composite(im.crop((0, i * im.width, im.width, (i + 1) * im.width)).resize((im.width * 6, im.width * 6), Image.NEAREST), (i * (im.width * 6 + 6), 0))
        small.append(row)
    W = max(s.width for s in sheets + small) + 20
    H = sum(s.height for s in sheets + small) + 12 * (len(sheets) + len(small) + 1)
    out = Image.new('RGBA', (W, H), (46, 44, 54, 255))
    y = 12
    for s in sheets + small:
        out.alpha_composite(s, (10, y))
        y += s.height + 12
    out.convert('RGB').save(os.path.join(out_dir, 'tales_memorial_textures.png'))


def main(assets, previews_dir=None):
    write_all(assets)
    problems = verify(assets)
    for p in problems:
        print('PROBLEM', p)
    print('memorial: %d block models, 2 item models, 6 textures%s' % (len(models()), '' if not problems else ', %d problems' % len(problems)))
    if previews_dir:
        previews(assets, previews_dir)
    return problems


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    sys.exit(1 if main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None) else 0)
