"""Silvan Marrow, the Unbound: the renegade mage of the Starwatch, the factions questline's final boss. A gaunt man in a
tattered midnight-blue robe whose silver star embroidery has gone black and cracked, under a deep pointed hood and a
long ragged star-mantle (a cape to the calves, its hem burnt and torn into six strips that hang and sway of their own).
Bare pale hands with ink-stained fingers; the dark staff is a real item, so the model has none. His eyes and a few cracked
star sigils on the mantle glow cold white-violet.

The model is the humanoid at the standard pivots (head, hat, body, right_arm, left_arm, right_leg, left_leg: the game's
clips drive them) plus `hood` (on head: a deep cowl with the face set back in it, stepped up to a point), and `mantle`
(on body, pivoting at the shoulders) with its six `hem_0`..`hem_5` strips. The robe's skirt is two halves that ride on
the legs, so they never let a leg through. The box UV is declared 128x64 and the texture ships at twice that, painted
natively at the higher size: every cube face is painted on its own (the folds of cloth lit from the upper left, black
thread stars with cracks, burnt and torn edges), and the robe's front and the mantle's back are painted once across the
cubes they span so the pattern runs on unbroken. Glow works from the same pixels: the eyes and each sigil's cracks are an
exact light colour in the base texture, and the glow map repeats them with a bloom round them (kept inside its own face).

    python3 -B art/scripts/renegade.py src/main/resources/assets/guildmark [preview-dir] art/models

Writes textures/entity/renegade.png and renegade_glow.png, client/model/RenegadeModel.kt (straight into the source tree:
the animation lives in this script's template), art/models/renegade.bbmodel and, with a preview dir, the preview sheets
and the Kotlin body. Everything is seeded, so a rerun gives the same files.
"""
import json
import math
import os
import re
import sys
import uuid
import zlib

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import hdkit
from hdkit import PAL, BAYER, bands, light, poly, fbm, grow, bevel, edge, seg_dist, path_dist
from modelkit import Cube, Part, Model

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/model/RenegadeModel.kt')

TEX_W, TEX_H = 128, 64     # the declared (box UV) size
S = 2                      # the texture ships at S times that

# ================================================================ palettes (darkest first: cool shadow, warm light)

ROBE = [(8, 9, 22), (14, 18, 40), (22, 28, 60), (33, 41, 84), (48, 58, 108), (72, 84, 138)]
CLOTH = ROBE[1:5]                                   # the four tones the robe's cloth is shaded in
MANTLE = [(5, 5, 14), (10, 9, 28), (17, 15, 46), (27, 24, 68), (40, 36, 92), (58, 52, 120)]
LINING = [(5, 4, 12), (10, 8, 24), (17, 14, 38), (27, 23, 56)]
SKIN = [(34, 30, 44), (66, 59, 80), (104, 96, 112), (140, 131, 142), (172, 162, 168)]
INK = [(5, 5, 16), (12, 12, 34), (24, 24, 58)]
HAIR = [(20, 19, 26), (44, 43, 54), (76, 75, 88), (116, 115, 128), (156, 155, 166)]
THREAD = [(2, 2, 8), (6, 6, 14), (14, 15, 28), (40, 43, 66)]            # the black embroidery, its lit edge last
SILVER = [(46, 48, 62), (84, 88, 106), (128, 132, 150), (176, 180, 196)]  # what is left of the silver
SCORCH = [(6, 5, 9), (15, 10, 13), (29, 19, 19), (50, 33, 28)]
LEATHER = PAL['blackleather']
CORD = [(6, 5, 12), (14, 12, 24), (26, 22, 40), (44, 38, 60)]       # the sash: black cord with a violet cast

# The light: exact marker colours in the base texture (the eyes and the sigil cracks), repeated in the glow map; the
# glow map alone adds a bloom round them, each ring fainter
CORE = (232, 224, 255)
MID = (170, 142, 255)
AURA = [(21, 16, 62), (10, 8, 33), (5, 4, 17)]       # a faint violet spilled on the cloth round a sigil


def seed_for(*keys):
    return zlib.crc32('/'.join(str(k) for k in keys).encode())


# ================================================================ geometry

def C(o, s, name, **kw):
    return Cube(o, s, None, name=name, **kw)


# The mantle's torn hem: six strips on child parts, the fan of their x edges, their lengths, and the angle each is hung
# at about z (the outer ones fan out)
HEM_EDGES = [-8, -5, -2, 0, 2, 5, 8]
HEM_LENS = [8, 10, 6, 9, 7, 9]
HEM_TILT = [14, 8, 2, -2, -8, -14]
HEM_TOP = 10.5             # where the strips hang from, down the mantle


def build_model():
    strips = []
    for i in range(6):
        w = HEM_EDGES[i + 1] - HEM_EDGES[i]
        xc = (HEM_EDGES[i] + HEM_EDGES[i + 1]) / 2
        strips.append(Part(f'hem_{i}', (xc, HEM_TOP, 0.04), (0, 0, HEM_TILT[i]),
                           cubes=[C((-w / 2, 0, 0), (w, HEM_LENS[i], 1), f'hem_{i}')]))
    # The face sits two pixels back in the hood; the hood steps up to a point that leans a little to the back
    head = Part('head', cubes=[C((-4, -8, -3.5), (8, 8, 8), 'face')], children=[
        Part('hat'),
        Part('hood', cubes=[C((-4.5, -9, -5.5), (9, 9, 11), 'hood_shell'),
                            C((-3.5, -11, -3.5), (7, 2, 9), 'hood_peak1'),
                            C((-2.5, -13, -1.7), (5, 2, 7), 'hood_peak2'),
                            C((-1.5, -14, 0.5), (3, 1, 4), 'hood_peak3')]),
    ])
    body = Part('body', cubes=[C((-4, 0, -2), (8, 12, 4), 'torso'), C((-4, 9.5, -2), (8, 2, 4), 'sash', inflate=0.3),
                               C((-6, -1, -3.5), (12, 3, 7), 'yoke')],
                children=[Part('mantle', (0, 0.5, 3.6), (6, 0, 0), cubes=[C((-8, 0, 0), (16, 11, 1), 'mantle')],
                               children=strips)])
    right_arm = Part('right_arm', (-5, 2, 0), cubes=[C((-2.5, -2, -1.5), (3, 12, 3), 'arm_r'),
                                                      C((-3, -2.5, -2), (4, 9, 4), 'sleeve_r')])
    left_arm = Part('left_arm', (5, 2, 0), cubes=[C((-0.5, -2, -1.5), (3, 12, 3), 'arm_l'),
                                                   C((-1, -2.5, -2), (4, 9, 4), 'sleeve_l')])
    # The skirt halves sit 0.1 either side of x=0 like the legs do; the robe reaches half a pixel off the ground
    right_leg = Part('right_leg', (-1.9, 12, 0), cubes=[C((-2.1, 0, -2), (4, 12, 4), 'leg_r'),
                                                         C((-3.1, -0.5, -2.5), (5, 12, 5), 'skirt_r')])
    left_leg = Part('left_leg', (1.9, 12, 0), cubes=[C((-1.9, 0, -2), (4, 12, 4), 'leg_l'),
                                                      C((-1.9, -0.5, -2.5), (5, 12, 5), 'skirt_l')])
    model = Model('renegade', TEX_W, TEX_H, [head, body, right_arm, left_arm, right_leg, left_leg])
    names = [c.name for c in model.all_cubes()]
    if len(set(names)) != len(names):
        raise SystemExit('renegade: cube names must be unique')
    model.pack()
    return model


# ================================================================ layers: one cube face at the texture's size

class Layer:
    """One face's pixels: colour, opacity and the glow's light, all at S times the declared size."""

    def __init__(self, W, H):
        self.W, self.H = W, H
        self.rgb = np.zeros((H, W, 3))
        self.a = np.ones((H, W))
        self.gl = np.zeros((H, W, 3))

    def put(self, mask, col):
        self.rgb = np.where(np.asarray(mask, bool)[..., None], np.asarray(col, float), self.rgb)

    def cut(self, mask):
        self.a[mask] = 0
        self.rgb[mask] = 0
        self.gl[mask] = 0

    def crop(self, x0, y0, w, h):
        out = Layer(w, h)
        out.rgb = self.rgb[y0:y0 + h, x0:x0 + w].copy()
        out.a = self.a[y0:y0 + h, x0:x0 + w].copy()
        out.gl = self.gl[y0:y0 + h, x0:x0 + w].copy()
        return out

    def flipx(self):
        out = Layer(self.W, self.H)
        out.rgb, out.a, out.gl = self.rgb[:, ::-1].copy(), self.a[:, ::-1].copy(), self.gl[:, ::-1].copy()
        return out


def XY(W, H):
    return hdkit.grid(W, H)


def flat(W, H, col):
    lay = Layer(W, H)
    lay.rgb[...] = col
    return lay


def noise2(W, H, cx, cy, seed):
    """Smooth value noise in 0..1 with cells cx wide and cy tall: long thin streaks (threads, folds) when they differ."""
    rng = np.random.default_rng(seed)
    gw, gh = int(W / cx) + 3, int(H / cy) + 3
    g = rng.random((gh, gw))
    fx, fy = np.arange(W) / cx, np.arange(H) / cy
    x0, y0 = fx.astype(int), fy.astype(int)
    tx, ty = fx - x0, fy - y0
    tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a = g[y0][:, x0] * (1 - tx) + g[y0][:, x0 + 1] * tx
    b = g[y0 + 1][:, x0] * (1 - tx) + g[y0 + 1][:, x0 + 1] * tx
    return a * (1 - ty[:, None]) + b * ty[:, None]


def cloth_t(W, H, seed, base=0.5, period=8.0, amp=0.35, lean=0.0, warp=1.5, thread=0.03, wear=0.05, fall=0.12, top=0.06):
    """The tone (0..1 along a ramp) of hanging cloth: soft pleats lit from the upper left that swell and fade as they
    run down, a few loose threads, faded and sooty patches, a little lighter at the top and darker toward the hem.
    [lean] tilts the pleats (px across per px down); [warp] bends them."""
    xs, ys = XY(W, H)
    rng = np.random.default_rng(seed)
    wx = warp * (noise2(W, H, 9, 9, seed + 1) - 0.5) * 2
    u = (xs + lean * ys + wx) / period
    h = (np.sin(2 * math.pi * u + rng.random() * 6.283) + 0.45 * np.sin(2 * math.pi * u * 2.3 + rng.random() * 6.283)) / 1.45
    swell = 0.55 + 0.9 * noise2(W, H, period * 2.2, 14, seed + 4)
    t = base + 1.6 * light(h * amp * swell, 1.0)
    t += thread * (noise2(W, H, 1.1, 6, seed + 2) - 0.5) * 2
    t += wear * (fbm(W, H, 8, seed + 3) - 0.5) * 2
    t += top * (1 - ys / H) - fall * (ys / H) ** 1.6
    return t


def cloth(W, H, ramp, seed, dither=0.22, **kw):
    lay = Layer(W, H)
    lay.rgb = bands(ramp, cloth_t(W, H, seed, **kw), dither).astype(float)
    return lay


def tidy(rgb):
    return np.clip(np.round(rgb), 0, 255)


# ---------------------------------------------------------------- torn and burnt edges

def hem_profile(seed, n, reach, knot=(2, 5), closed=False):
    """How far up from the full length each of n columns is torn away: random knots joined by straight runs, so the
    edge is a row of tongues and notches rather than noise. [closed] makes the last column meet the first."""
    rng = np.random.default_rng(seed)
    first = rng.integers(0, reach + 1)
    xs, vals, x = [0], [first], 0
    while x < n - 1:
        x = min(n - 1, x + int(rng.integers(knot[0], knot[1] + 1)))
        xs.append(x)
        vals.append(first if (closed and x == n - 1) else rng.integers(0, reach + 1))
    return np.interp(np.arange(n), xs, vals)


def tear(lay, cut_y, burn=4.0):
    """Cut the layer away from [cut_y] (a pixel row per column) down, and scorch the cloth above the cut: black at the
    edge, singed brown, fading into the cloth through a dither."""
    W, H = lay.W, lay.H
    xs, ys = XY(W, H)
    d = np.asarray(cut_y, float)[None, :] - ys               # how far above the cut each pixel is
    gone = d <= 0
    w = np.clip(1 - d / burn, 0, 1)
    thr = np.tile(BAYER, (H // 4 + 1, W // 4 + 1))[:H, :W] + 0.5
    scorch = (w * 1.15 > thr) & ~gone
    idx = np.clip(np.floor(w * 3.2), 0, 3).astype(int)
    lay.rgb = np.where(scorch[..., None], np.array(SCORCH, float)[3 - idx], lay.rgb)
    lay.cut(gone)
    return lay


def holes(lay, n, seed):
    """A few burn holes: one to three pixels cut clean through, ringed with scorch."""
    rng = np.random.default_rng(seed)
    W, H = lay.W, lay.H
    for _ in range(n):
        x, y = int(rng.integers(2, W - 2)), int(rng.integers(2, H - 4))
        pts = [(x, y)]
        if rng.random() < 0.6:
            pts.append((x + int(rng.integers(-1, 2)), y + int(rng.integers(0, 2))))
        if rng.random() < 0.3:
            pts.append((x + int(rng.integers(-1, 2)), y + 1))
        ring = {(px + dx, py + dy) for px, py in pts for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))}
        for px, py in ring - set(pts):
            if 0 <= px < W and 0 <= py < H and lay.a[py, px] > 0 and not lay.gl[py, px].any():
                lay.rgb[py, px] = SCORCH[1]
        for px, py in pts:
            if 0 <= px < W and 0 <= py < H and not lay.gl[py, px].any():
                lay.a[py, px] = 0
                lay.rgb[py, px] = 0


# ---------------------------------------------------------------- black thread: stars, stitches, cracks

def star_pts(cx, cy, R, n=8, short=0.7, inner=0.42, rot=-math.pi / 2):
    """An n-pointed compass star: the cardinal points reach R, the ones between them reach R*short."""
    pts = []
    for i in range(2 * n):
        a = rot + math.pi * i / n
        if i % 2 == 0:
            r = R if (i // 2) % 2 == 0 else R * short
        else:
            r = R * inner
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def sparkle_pts(cx, cy, R, thin=0.34, rot=-math.pi / 2):
    """A four-pointed sparkle."""
    pts = []
    for i in range(8):
        a = rot + math.pi * i / 4
        r = R if i % 2 == 0 else R * thin
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def star_mask(W, H, cx, cy, R):
    """A compass star built from rays: eight tapering rays out of a round heart, the cardinal ones R long and the
    diagonal ones two thirds of that. Put the middle on a pixel corner (whole cx, cy) and each ray is two pixels wide,
    which a star this small needs to stay a star."""
    xs, ys = XY(W, H)
    mask = np.hypot(xs - cx, ys - cy) < max(1.4, R * 0.28)
    for k in range(8):
        a = -math.pi / 2 + k * math.pi / 4
        L = R if k % 2 == 0 else R * 0.66
        w0 = 0.75 + 0.04 * R if k % 2 == 0 else 0.7 + 0.03 * R
        d, t = seg_dist(cx, cy, cx + L * math.cos(a), cy + L * math.sin(a), W, H)
        mask |= d < w0 + (0.52 - w0) * t
    return mask


def thread_over(lay, mask, seed, silver=0.4):
    """Lay black embroidery thread over the masked pixels: a raised stitch, lit on its upper-left edge, shaded on its
    lower-right, with patches of tarnished silver where the black has worn through along the edge."""
    bev = bevel(mask)
    col = np.where((bev > 0)[..., None], THREAD[3], np.where((bev < 0)[..., None], THREAD[0], THREAD[1])).astype(float)
    lay.put(mask, col)
    rng = np.random.default_rng(seed)
    patch = edge(mask) & (noise2(lay.W, lay.H, 2.2, 2.2, seed) > 1 - silver)
    lay.put(patch & (bev >= 0), np.array(SILVER, float)[rng.integers(1, 4, mask.shape)])
    lay.put(patch & (bev < 0), SILVER[0])


def sparkle(lay, cx, cy, R, seed, silver=0.4):
    thread_over(lay, poly(sparkle_pts(cx, cy, R), lay.W, lay.H), seed, silver)


def stitch(lay, y, x0=0, x1=None):
    """A line of black stitching across the face, a paler thread dashed just above it."""
    x1 = lay.W if x1 is None else x1
    lay.rgb[y, x0:x1] = THREAD[1]
    if y > 0:
        for x in range(x0, x1, 2):
            lay.rgb[y - 1, x] = THREAD[2]


def zigzag(lay, y, x0=0, x1=None):
    """A band of black zigzag stitching three rows tall, a lit stitch on each crest."""
    x1 = lay.W if x1 is None else x1
    for x in range(x0, x1):
        k = x % 4
        lay.rgb[y + (0, 1, 2, 1)[k], x] = THREAD[3] if k == 0 else THREAD[1]


def bloom(lay, lit, rings, clip=None):
    """The glow map's light round the lit pixels: each ring a step fainter. [clip] keeps it to a mask."""
    for k, col in enumerate(rings):
        ring = grow(lit, k + 1, True) & ~grow(lit, k, True) & (lay.a > 0)
        if clip is not None:
            ring &= clip
        lay.gl = np.where(ring[..., None] & ~lit[..., None], np.array(col, float), lay.gl)


def lit_rays(W, H, cx, cy, R, gaps):
    """The light along a star's rays: a one-pixel line down the lit side of each, broken at [gaps] (pairs of a ray, 0 up
    and on round clockwise, and how far along it, 0 to 1), the heart all light. Gives the lit pixels and how far each
    is from the middle, in radii."""
    xs, ys = XY(W, H)
    far = np.hypot(xs - cx, ys - cy) / R
    lit = far < max(1.0, R * 0.2) / R
    for k in range(8):
        a = -math.pi / 2 + k * math.pi / 4
        L = R if k % 2 == 0 else R * 0.66
        d, _ = seg_dist(cx - 0.25, cy - 0.25, cx - 0.25 + L * math.cos(a), cy - 0.25 + L * math.sin(a), W, H)
        lit |= d < 0.5
    for k, f in gaps:
        a = -math.pi / 2 + k * math.pi / 4
        L = R if k % 2 == 0 else R * 0.66
        lit &= np.hypot(xs - (cx + f * L * math.cos(a)), ys - (cy + f * L * math.sin(a))) > 1.0
    return lit, far


def star_sigil(lay, cx, cy, R, seed, cracks=(), cloth_col=ROBE[2], gaps=None, ring=False, silver=0.4):
    """A star sigil: a compass star in black thread inside an optional broken ring. Without [gaps] it is dead: split
    along [cracks] (polylines in units of R from the star's middle) where the cloth shows through. With them it
    glows: a line of light down each ray, broken at the gaps, a little light spilled onto the cloth round it."""
    W, H = lay.W, lay.H
    shape = star_mask(W, H, cx, cy, R)
    if ring:                                    # a broken ring: a long arc over the star and two short ones low down
        xs, ys = XY(W, H)
        ang = np.degrees(np.arctan2(ys - cy, xs - cx))
        arc = np.abs(np.hypot(xs - cx, ys - cy) - R * 1.38) < 0.55
        shape |= arc & (((ang > -165) & (ang < -15)) | ((ang > 108) & (ang < 148)) | ((ang > 32) & (ang < 72)))
    thread_over(lay, shape, seed, silver=0.2 if gaps is not None else silver)
    if gaps is not None:
        lit, far = lit_rays(W, H, cx, cy, R, gaps)
        lit &= shape
        lay.put(lit, MID)
        lay.put(lit & (far < 0.55), CORE)
        lay.gl = np.where(lit[..., None], lay.rgb, lay.gl)
        bloom(lay, shape, AURA)
        return shape
    crack = np.zeros((H, W), bool)
    for line in cracks:
        d, _ = path_dist([(cx + x * R, cy + y * R) for x, y in line], W, H)
        crack |= d < 0.62
    lay.put(crack & shape, np.array(cloth_col, float) * 0.85)
    return shape


def lit_sparkle(lay, cx, cy, R):
    """A small star that is all light: a four-pointed sparkle in the light colours, a bloom round it."""
    m = poly(sparkle_pts(cx, cy, R, 0.3), lay.W, lay.H)
    xs, ys = XY(lay.W, lay.H)
    lay.put(m, MID)
    lay.put(m & (np.hypot(xs - cx, ys - cy) < R * 0.62), CORE)
    lay.gl = np.where(m[..., None], lay.rgb, lay.gl)
    bloom(lay, m, AURA)


def dust(lay, seed, amount=0.05, col=(104, 108, 138)):
    """A little ash on the top of a piece: sparse pale flecks."""
    rng = np.random.default_rng(seed)
    m = (rng.random((lay.H, lay.W)) < amount) & (lay.a > 0)
    lay.put(m, np.array(lay.rgb) * 0.5 + np.array(col, float) * 0.5)


# ================================================================ the face: drawn by hand at the texture's size

# Under the hood the brow and eye sockets are in deep shadow and the lower face catches what light there is: a long
# narrow face with hollow cheeks, a thin nose and mouth, a patchy grey beard, lank hair at either side, and two cold lights.
FACE_KEY = {
    'D': (15, 13, 23), 'a': (30, 27, 40), 'b': (54, 49, 66), 'c': (88, 80, 98), 'd': (122, 113, 126),
    'k': HAIR[0], 'l': HAIR[1], 'm': HAIR[2], 'n': HAIR[3],
    'E': CORE, 'e': MID, 'h': (22, 17, 44),
    'p': (38, 27, 42), 'N': (10, 8, 16),
}
FACE_BLOOM = [(34, 25, 88), (16, 12, 44)]


def face_front_rows():
    """The face's 16 rows of 16 pixels, set down feature by feature (x from the left, y from the top; `both` sets a
    feature and its mirror image), so no row is ever counted by eye."""
    g = [['D'] * 16 for _ in range(16)]

    def put(y, x0, chars):
        for i, ch in enumerate(chars):
            if ch != '.':
                g[y][x0 + i] = ch

    def both(y, x0, chars):
        put(y, x0, chars)
        put(y, 16 - x0 - len(chars), chars[::-1])
    rng = np.random.default_rng(seed_for('face', 'hair'))
    # lank hair down both sides: three columns each, dark with strands of grey, a few falling over the cheeks
    for y in range(16):
        for x in (0, 1, 2, 13, 14, 15):
            r = rng.random()
            g[y][x] = 'k' if r < 0.55 else ('l' if r < 0.92 else 'm')
    for y in range(9, 16):
        for x in (3, 12):
            if rng.random() < 0.4:
                g[y][x] = 'l'
    # the brow ridge catches a little light over the dark sockets
    both(6, 3, 'aaa')
    both(7, 2, 'h')
    both(8, 3, 'hh')
    both(9, 4, 'hhh')
    # the eyes: two cold slits slanting down toward the nose, apart by the bridge of it
    both(7, 3, 'eEE')
    both(8, 5, 'Ee')
    # the nose: long and thin, lit on its left; hollow cheeks under high bones
    put(8, 7, 'aD')
    put(9, 7, 'ca')
    put(10, 7, 'cb')
    put(11, 7, 'ca')
    put(12, 6, 'NbaN')
    both(10, 3, 'bb')
    put(11, 4, 'aD')
    put(11, 10, 'Da')
    put(12, 4, 'aD')
    put(12, 10, 'Da')
    # a thin bloodless mouth, turned down at the corners, and a hard chin
    put(13, 5, 'pppppp')
    put(14, 5, 'p')
    put(14, 10, 'p')
    put(14, 6, 'aaaa')
    put(15, 5, 'abbbba')
    # a patchy beard along the jaw
    for y in range(13, 16):
        for x in range(4, 12):
            if g[y][x] in 'abcD' and rng.random() < 0.3 and not (y == 13 and 5 <= x <= 10):
                g[y][x] = 'l' if rng.random() < 0.6 else 'm'
    rows = [''.join(r) for r in g]
    assert all(len(r) == 16 for r in rows) and len(rows) == 16
    return rows


def from_rows(rows, key):
    lay = Layer(len(rows[0]), len(rows))
    for j, r in enumerate(rows):
        for i, ch in enumerate(r):
            lay.rgb[j, i] = key[ch]
    return lay


def paint_face(face, W, H):
    """The head cube. Only the front shows through the hood: the rest is hair."""
    if face == 'front':
        lay = from_rows(face_front_rows(), FACE_KEY)
        lit_px = np.all(lay.rgb == np.array(CORE, float), axis=-1) | np.all(lay.rgb == np.array(MID, float), axis=-1)
        lay.gl = np.where(lit_px[..., None], lay.rgb, 0)
        bloom(lay, lit_px, FACE_BLOOM)
        return lay
    # hair, a little lighter toward the crown, with strands
    lay = Layer(W, H)
    t = 0.3 + 0.35 * noise2(W, H, 1.2, 5, seed_for('hair', face)) + 0.1 * (1 - XY(W, H)[1] / H)
    lay.rgb = bands(HAIR, t, 0.4).astype(float)
    if face in ('right', 'left'):
        # the temple: skin in front of the ear (the front edge is the right edge on the right face, the left on the left)
        fx = slice(W - 4, W) if face == 'right' else slice(0, 4)
        sk = bands(SKIN, 0.18 + 0.18 * noise2(W, H, 3, 3, 5), 0.3).astype(float)
        lay.rgb[3:, fx] = sk[3:, fx]
    if face == 'bottom':
        lay.rgb[...] = SKIN[0]
    return lay


# ================================================================ painters, by cube name and face

CACHE = {}
SK_PROF = {}


# ---------------------------------------------------------------- the hood

def hood_front(W, H):
    """18x18: the cowl's front, an opening for the face from column 3 to 14 and open at the bottom, a black lip inside it
    and a stitched edging of black sparkles round it."""
    lay = cloth(W, H, CLOTH, seed_for('hood', 'front'), base=0.42, period=9.0, amp=0.4, fall=0.04, top=0.1)
    xs, ys = XY(W, H)
    hole = np.zeros((H, W), bool)
    for r in range(6, H):                                              # a pointed arch, wider as it goes down
        inset = (3, 2, 1, 1)[r - 6] if r < 10 else 0
        hole[r, 3 + inset:15 - inset] = True
    lay.put(grow(hole, 1) & ~hole, ROBE[0])
    rim2 = grow(hole, 2) & ~grow(hole, 1)
    lay.put(rim2 & (xs < 9), ROBE[4])                                 # the lit upper-left edge of the lip
    lay.put(rim2 & (xs >= 9), ROBE[1])
    # black trim: a dashed stitch down each side of the opening and a small star over its point
    for x in (1, 16):
        for y in range(8, H - 1, 2):
            lay.rgb[y:y + 1, x] = THREAD[1]
            lay.rgb[y + 1:y + 2, x] = THREAD[3] if x == 1 else THREAD[0]
    thread_over(lay, star_mask(W, H, 9, 4, 3.4), seed_for('hood', 'brow'), 0.6)
    lay.cut(hole)
    return lay


def hood_side(face, W, H):
    """22 deep by 18: pleats sweeping up and back from the opening, a hem of black stars. The front edge is the right
    edge on the right face, the left on the left."""
    lean = -0.4 if face == 'right' else 0.4
    lay = cloth(W, H, CLOTH, seed_for('hood', 'side', face), base=0.42, period=9.0, amp=0.4, lean=lean, fall=0.1, top=0.1)
    xs, ys = XY(W, H)
    fx = (W - xs) if face == 'right' else xs
    lay.put(fx < 2.0, ROBE[1])
    zigzag(lay, H - 7)
    return lay


def hood_back(W, H):
    lay = cloth(W, H, CLOTH, seed_for('hood', 'back'), base=0.38, period=8.0, amp=0.4, fall=0.1, top=0.08)
    lay.rgb[:, W // 2 - 1:W // 2 + 1] = ROBE[0]                       # the seam
    star_sigil(lay, 9, 6, 4.8, seed_for('hood', 'sigil'), [[(0.1, -0.9), (-0.1, 0.0), (0.2, 0.9)]], silver=0.6)
    zigzag(lay, H - 7)
    return lay


def hood_top(W, H):
    lay = cloth(W, H, CLOTH, seed_for('hood', 'top'), base=0.5, period=7.0, amp=0.4, fall=0.0, top=0.0)
    lay.rgb[:, W // 2 - 1:W // 2 + 1] = ROBE[1]
    dust(lay, 23, 0.05)
    return lay


def paint_hood_shell(face, W, H):
    if face == 'front':
        return hood_front(W, H)
    if face in ('right', 'left'):
        return hood_side(face, W, H)
    if face == 'back':
        return hood_back(W, H)
    if face == 'top':
        return hood_top(W, H)
    return flat(W, H, ROBE[0])


def paint_peak(n, face, W, H):
    lay = cloth(W, H, CLOTH, seed_for('peak', n, face), base=0.5, period=5.0, amp=0.4, fall=0.0, top=0.0)
    if face in ('front', 'right', 'left', 'back'):
        lay.rgb[H - 1] = np.array(lay.rgb[H - 1]) * 0.62              # shadow where each step sits on the one below
    if face == 'top':
        dust(lay, 24 + n, 0.06)
    return lay


# ---------------------------------------------------------------- the robe: the front and back painted across torso and skirt

def skirt_profiles():
    """The torn hem of each skirt half, as one 30-pixel loop of its three outward faces: the right half runs back, outer
    side, front; the left half front, outer side, back (10 pixels each), so the edge runs round the corners unbroken, and
    the two halves' hems meet at the middle of the front and the back."""
    if 'r' not in SK_PROF:
        r = hem_profile(seed_for('skirt', 'r'), 30, 5)
        l = hem_profile(seed_for('skirt', 'l'), 30, 5)
        l[0], r[0] = r[29], l[29]
        SK_PROF['r'], SK_PROF['l'] = r, l
    return SK_PROF


# Cracks across a star, in units of its radius from the middle: one through the heart and out along the side rays, and
# a break in the top ray and in the bottom one
CRACKS = [[(-0.9, -0.25), (-0.45, 0.05), (-0.1, -0.1), (0.3, 0.2), (0.9, 0.0)],
          [(0.05, -0.95), (-0.1, -0.55), (0.06, -0.2)],
          [(0.1, 0.5), (-0.1, 0.75), (0.05, 0.95)]]


def robe_front():
    """Torso front rows 0..24 (columns 2..18) and the two skirt halves' fronts (rows 23..47, 10 columns each): one
    canvas, 20 wide and 48 tall, x from -5 to 5 and y from 0 to 24."""
    if 'robe_front' in CACHE:
        return CACHE['robe_front']
    W, H = 20, 48
    xs, ys = XY(W, H)
    t = cloth_t(W, H, seed_for('robe', 'front'), base=0.5, period=8.0, amp=0.3, fall=0.16, top=0.08)
    ts = cloth_t(W, H, seed_for('robe', 'skirt'), base=0.46, period=6.5, amp=0.42, fall=0.22, top=0.0)
    # the cloth is a little faded round the chest, where the star was sewn, so the black thread shows on it
    t = np.where(ys > 22, ts, t + 0.09 * np.exp(-((xs - 10) ** 2 + (ys - 12) ** 2) / (2 * 5.5 ** 2)))
    lay = Layer(W, H)
    lay.rgb = bands(CLOTH, t, 0.22).astype(float)
    # the placket down the skirt: a darker panel piped in lighter thread
    lay.put((xs > 9) & (xs < 11) & (ys > 22), ROBE[1])
    for sx in (8.5, 11.5):
        lay.put((np.abs(xs - sx) < 0.5) & (ys > 22), ROBE[4])
    # the chest: the Starwatch star, gone black and cracked
    star_sigil(lay, 10, 12, 7.0, seed_for('robe', 'chest'), CRACKS, silver=0.75)
    for y in (26, 30, 34, 38):                                         # buttons down the placket
        lay.rgb[y:y + 2, 9:11] = THREAD[1]
        lay.rgb[y, 9] = THREAD[3]
    stitch(lay, 40)                                                    # the hem band
    zigzag(lay, 42, 0, 20)
    dust(lay, 25, 0.02)
    lay.rgb = tidy(lay.rgb)
    CACHE['robe_front'] = lay
    return lay


def robe_back():
    if 'robe_back' in CACHE:
        return CACHE['robe_back']
    W, H = 20, 48
    lay = cloth(W, H, CLOTH, seed_for('robe', 'back'), base=0.44, period=7.0, amp=0.36, fall=0.2, top=0.04)
    lay.rgb[:, 9:11] = ROBE[0]                                         # the back seam
    stitch(lay, 40)
    zigzag(lay, 42, 0, 20)
    lay.rgb = tidy(lay.rgb)
    CACHE['robe_back'] = lay
    return lay


def paint_torso(face, W, H):
    if face == 'front':
        return robe_front().crop(2, 0, 16, 24)
    if face == 'back':
        return robe_back().crop(2, 0, 16, 24)
    if face in ('right', 'left'):
        return cloth(W, H, CLOTH, seed_for('torso', face), base=0.4, period=6.0, amp=0.3, fall=0.2, top=0.05)
    return flat(W, H, ROBE[1])


def silver_star(lay, cx, cy, R):
    """A tarnished silver star clasp."""
    clasp = poly(star_pts(cx, cy, R, 4, 0.9, 0.5), lay.W, lay.H)
    bev = bevel(clasp)
    lay.put(clasp, SILVER[1])
    lay.put(clasp & (bev > 0), SILVER[3])
    lay.put(clasp & (bev < 0), SILVER[0])
    lay.rgb[int(cy), int(cx)] = THREAD[0]


def paint_yoke(face, W, H):
    """The cowl over the shoulders: the hood's cloth falling to a collar, a hem of black stars and a silver star clasp."""
    if face in ('top', 'bottom'):
        lay = cloth(W, H, CLOTH, seed_for('yoke', face), base=0.52 if face == 'top' else 0.2, period=7.0, amp=0.35, fall=0.0, top=0.0)
        if face == 'top':
            dust(lay, 27, 0.06)
        return lay
    lay = cloth(W, H, CLOTH, seed_for('yoke', face), base=0.46, period=6.0, amp=0.38, fall=0.1, top=0.08)
    zigzag(lay, H - 4)
    if face == 'front':
        silver_star(lay, W / 2, 2.4, 2.6)
    return lay


def paint_sash(face, W, H):
    """A black cord sash: braided, knotted at the front with a tarnished star buckle."""
    lay = Layer(W, H)
    xs, ys = XY(W, H)
    t = 0.42 + 0.22 * (((xs + ys * 0.8) % 3) < 1.3) - 0.2 * (ys > H - 1.2) + 0.1 * (ys < 1.2)
    t += 0.08 * (noise2(W, H, 1.5, 1.5, seed_for('sash', face)) - 0.5)
    lay.rgb = bands(CORD, t, 0.3).astype(float)
    if face == 'front':
        silver_star(lay, W / 2, H / 2, min(H / 2 + 0.5, 3.2))
    return lay


# ---------------------------------------------------------------- the mantle

MANTLE_CANVAS = (40, 44)      # 20 wide (x from -10 to 10) by 22 tall at 2 px, in back view: column c is x = 10 - c / 2


def strip_range(i):
    """The back-view canvas columns and rows a strip's cube takes (its back face), before its own tilt."""
    w = HEM_EDGES[i + 1] - HEM_EDGES[i]
    xc = (HEM_EDGES[i] + HEM_EDGES[i + 1]) / 2
    c0 = int(round((10 - (xc + w / 2)) * 2))                    # the back view runs from +x to -x
    return c0, int(round(HEM_TOP * 2)), w * 2, HEM_LENS[i] * 2


# centre, radius, gaps in the light along the rays, ring: the sigils on the panel; the small lit stars hang on two strips
SIGILS = [
    ((20, 10), 7.0, [(0, 0.66), (1, 0.8), (2, 0.55), (2, 0.88), (3, 0.7), (4, 0.62), (5, 0.45), (6, 0.74), (7, 0.6)], True),
    ((10, 17), 3.8, [(2, 0.7), (6, 0.8), (4, 0.75)], False),
    ((32, 15), 4.0, [(0, 0.78), (4, 0.6), (6, 0.82)], False),
]
LIT_STARS = [(7.0, 27.0, 2.8), (33.0, 25.0, 2.6)]


def mantle_back():
    """The whole mantle's outer face in back view: the broad panel from column 4 to 36 and rows 0..22, then six strips,
    each torn to its own length. The sigils glow."""
    if 'mantle_back' in CACHE:
        return CACHE['mantle_back']
    W, H = MANTLE_CANVAS
    lay = cloth(W, H, MANTLE[1:5], seed_for('mantle', 'back'), base=0.42, period=9.0, amp=0.4, fall=0.2, top=0.1, wear=0.08)
    rng = np.random.default_rng(seed_for('mantle', 'stars'))
    # the old star-field: little black sparkles and single dots over the whole cloth
    for k in range(24):
        x, y = rng.uniform(5, 35), rng.uniform(1, 40)
        if rng.random() < 0.55:
            sparkle(lay, x, y, 1.5 + rng.random() * 0.6, seed_for('ms', k), 0.4)
        else:
            lay.rgb[int(y), int(x)] = THREAD[1]
    for k, ((cx, cy), R, gaps, ring) in enumerate(SIGILS):
        star_sigil(lay, cx, cy, R, seed_for('mantle', 'sigil', k), gaps=gaps, ring=ring)
    for cx, cy, R in LIT_STARS:
        lit_sparkle(lay, cx, cy, R)
    holes(lay, 6, seed_for('mantle', 'holes'))
    # everything that is not the panel or a strip is air; each strip is torn to a point or a ragged end
    keep = np.zeros((H, W), bool)
    keep[0:22, 4:36] = True
    cut = np.full(W, float(H + 10))
    for i in range(6):
        c0, r0, w, h = strip_range(i)
        keep[r0:r0 + h, c0:c0 + w] = True
        prof = hem_profile(seed_for('strip', i), w, 3)
        pt = np.abs(np.arange(w) - (w - 1) / 2) / max(1, w / 2)          # 0 in the middle, 1 at the sides
        cut[c0:c0 + w] = r0 + h - prof - 2.4 * pt * (1 + (i % 2))
    tear(lay, cut, burn=4.5)
    lay.cut(~keep)
    lay.rgb = tidy(lay.rgb)
    CACHE['mantle_back'] = lay
    return lay


def mantle_front():
    """The mantle's inside, the face toward the body: a dark lining, a few old stars, the same torn edges. In front view
    (x increasing to the right)."""
    if 'mantle_front' in CACHE:
        return CACHE['mantle_front']
    out = mantle_back().flipx()
    W, H = out.W, out.H
    lay = cloth(W, H, LINING, seed_for('mantle', 'in'), base=0.5, period=8.0, amp=0.4, fall=0.15, top=0.1)
    rng = np.random.default_rng(seed_for('mantle', 'in', 'stars'))
    for k in range(16):
        x, y = rng.uniform(5, 35), rng.uniform(1, 40)
        lay.rgb[int(y), int(x)] = LINING[3] if rng.random() < 0.7 else SILVER[0]
    lay.a = out.a.copy()
    lay.rgb = np.where((out.a > 0)[..., None], lay.rgb, 0)
    scorch = np.zeros((H, W), bool)
    for col in SCORCH:
        scorch |= np.all(np.abs(out.rgb - np.array(col, float)) < 1, axis=-1)
    lay.rgb = np.where(scorch[..., None], out.rgb, lay.rgb)
    lay.rgb = tidy(lay.rgb)
    CACHE['mantle_front'] = lay
    return lay


def paint_mantle(face, W, H):
    if face == 'back':
        return mantle_back().crop(4, 0, 32, 22)
    if face == 'front':
        return mantle_front().crop(4, 0, 32, 22)
    return flat(W, H, MANTLE[1])


def paint_hem(i, face, W, H):
    c0, r0, w, h = strip_range(i)
    if face == 'back':
        return mantle_back().crop(c0, r0, w, h)
    if face == 'front':
        return mantle_front().crop(int(round((HEM_EDGES[i] + 10) * 2)), r0, w, h)
    lay = flat(W, H, MANTLE[1])
    if face in ('right', 'left'):
        lay.rgb[H - 4:, :] = SCORCH[1]
        lay.a[H - 2:, :] = 0
    return lay


# ---------------------------------------------------------------- arms: the sleeves and the bare hands

# Where each face of a four-sided piece starts on the loop round it: the texture runs right, front, left, back
LOOP = {'right': 0, 'front': 1, 'left': 2, 'back': 3}


def paint_sleeve(side, face, W, H):
    """Sleeve faces are 8 across and 18 down; the cuff is a band of stitching and sparkles above a torn, singed edge that
    runs continuously round the four faces."""
    if face == 'top':
        lay = cloth(W, H, CLOTH, seed_for('sleeve', side, 'top'), base=0.52, period=5.0, amp=0.3, fall=0.0, top=0.0)
        dust(lay, 30, 0.06)
        return lay
    if face == 'bottom':
        return flat(W, H, LINING[1])
    lay = cloth(W, H, CLOTH, seed_for('sleeve', side, face), base=0.44, period=6.0, amp=0.4, fall=0.16, top=0.1)
    stitch(lay, H - 7)
    for k in range(2):
        sparkle(lay, 2.2 + k * 4.0, H - 4.6, 1.5, seed_for('sc', side, face, k))
    if 'sleeve' not in SK_PROF:
        SK_PROF['sleeve'] = hem_profile(seed_for('sleeve'), 32, 4, closed=True)
    tear(lay, H - SK_PROF['sleeve'][(LOOP[face] * 8 + np.arange(W)) % 32], burn=4.0)
    return lay


def paint_arm(side, face, W, H):
    """The bare arm under the sleeve: rows 0..16 are covered, 17..23 are wrist and hand: pale skin, knuckles, long
    fingers, and ink on the fingertips and the side of the hand."""
    lay = Layer(W, H)
    lay.rgb = bands(CLOTH, cloth_t(W, H, seed_for('arm', side, face), base=0.4), 0.3).astype(float)
    if face == 'top':
        return lay
    if face == 'bottom':
        lay.rgb[...] = SKIN[1]
        lay.rgb[2:4, 2:4] = INK[1]
        return lay
    sk = H - 11                                                 # skin from row 13, where the torn cuff lets it show
    xs, ys = XY(W, H)
    t = 0.4 + 0.1 * (noise2(W, H, 2, 3, seed_for('hand', side, face)) - 0.5)
    t -= 0.3 * np.clip((sk + 5.0 - ys) / 5.0, 0, 1)             # in the sleeve's shadow at the wrist
    t += 0.12 * (xs < 2.5) - 0.12 * (xs > W - 2)                # lit on the left, shaded on the right
    lay.put(ys > sk, bands(SKIN, t, 0.3))
    lay.rgb[sk + 4:sk + 8, 3] = (50, 54, 92)                    # a vein
    # the hand: knuckles, then three fingers two pixels wide whose ends are stained with ink, a smear on its back
    for x in range(W):
        finger = x // 2
        lay.rgb[H - 4, x] = SKIN[3] if x % 2 == 0 else SKIN[1]
        lay.rgb[H - 3, x] = (SKIN[3], SKIN[2], SKIN[3])[finger]
        lay.rgb[H - 2, x] = INK[2] if finger != 1 else SKIN[2]
        lay.rgb[H - 1, x] = INK[1]
    for x, y in ((1, H - 8), (2, H - 7), (2, H - 8), (3, H - 6)):
        lay.rgb[y, x] = INK[1]
    return lay


# ---------------------------------------------------------------- legs, boots and the skirt

def paint_leg(side, face, W, H):
    if face == 'top':
        return flat(W, H, ROBE[1])
    if face == 'bottom':
        return flat(W, H, LEATHER[0])
    lay = Layer(W, H)
    xs, ys = XY(W, H)
    lay.rgb = bands(CLOTH, 0.3 + 0.08 * (noise2(W, H, 1.2, 4, seed_for('leg', side, face)) - 0.5), 0.3).astype(float)
    # boots from the knee: black leather, a strap and a tarnished buckle, a toe cap, a sole
    bt = 0.4 + 0.18 * (ys < 17) - 0.2 * (ys > H - 3) + 0.1 * (noise2(W, H, 2, 2, 9) - 0.5)
    lay.put(ys >= 15, bands(LEATHER, bt, 0.3))
    lay.rgb[H - 2:H] = LEATHER[0]
    lay.rgb[18, :] = LEATHER[3]
    if face == 'front':
        lay.rgb[18:20, W // 2 - 1:W // 2 + 1] = SILVER[1]
        lay.rgb[H - 5:H - 2, 1:W - 1] = LEATHER[3]
    return lay


def paint_skirt(side, face, W, H):
    """Each half of the robe's skirt: the front and back from the painted robe, the sides their own cloth, the hem torn
    round the three outward faces."""
    if face in ('top', 'bottom'):
        return flat(W, H, ROBE[1] if face == 'top' else LINING[1])
    prof = skirt_profiles()[side]
    cols = np.arange(10)
    # where each outward face sits on the half's loop of 30 hem pixels
    start = {'back': 0, 'right': 10, 'front': 20} if side == 'r' else {'front': 0, 'left': 10, 'back': 20}
    if face in ('front', 'back'):
        full = robe_front() if face == 'front' else robe_back()
        lay = full.crop(0 if side == 'r' else 10, 23, 10, 24)
    else:
        lay = cloth(W, H, CLOTH, seed_for('skirt', side, face), base=0.42, period=6.5, amp=0.42, fall=0.22, top=0.0)
        stitch(lay, 16)
        for k in range(2):
            sparkle(lay, 2.6 + k * 4.8, 19.0, 1.7, seed_for('ssh', side, face, k))
    p = prof[start[face] + cols] if face in start else np.zeros(10)       # the inner side is hidden between the legs
    tear(lay, H - p, burn=4.5)
    return lay


# ---------------------------------------------------------------- the registry

def painter_for(name):
    if name == 'face':
        return paint_face
    if name == 'hood_shell':
        return paint_hood_shell
    if name.startswith('hood_peak'):
        return lambda face, W, H, _n=int(name[-1]): paint_peak(_n, face, W, H)
    if name == 'torso':
        return paint_torso
    if name == 'yoke':
        return paint_yoke
    if name == 'sash':
        return paint_sash
    if name == 'mantle':
        return paint_mantle
    if name.startswith('hem_'):
        return lambda face, W, H, _i=int(name[-1]): paint_hem(_i, face, W, H)
    if name.startswith('sleeve_'):
        return lambda face, W, H, _s=name[-1]: paint_sleeve(_s, face, W, H)
    if name.startswith('arm_'):
        return lambda face, W, H, _s=name[-1]: paint_arm(_s, face, W, H)
    if name.startswith('leg_'):
        return lambda face, W, H, _s=name[-1]: paint_leg(_s, face, W, H)
    if name.startswith('skirt_'):
        return lambda face, W, H, _s=name[-1]: paint_skirt(_s, face, W, H)
    raise SystemExit(f'renegade: no painter for {name}')


def paint_textures(model):
    """The base texture and the glow map, both TEX_W*S by TEX_H*S."""
    CACHE.clear()
    SK_PROF.clear()
    W, H = TEX_W * S, TEX_H * S
    tex = np.zeros((H, W, 4), np.uint8)
    glow = np.zeros((H, W, 4), np.uint8)
    for c in model.all_cubes():
        painter = painter_for(c.name)
        for face, (x, y, w, h) in c.rects().items():
            lay = painter(face, w * S, h * S)
            if (lay.W, lay.H) != (w * S, h * S):
                raise SystemExit(f'renegade: {c.name}.{face} painted {lay.W}x{lay.H}, wants {w * S}x{h * S}')
            X, Y = x * S, y * S
            a = lay.a > 0.5
            rgb = np.clip(np.round(lay.rgb), 0, 255).astype(np.uint8)
            tex[Y:Y + h * S, X:X + w * S, :3] = np.where(a[..., None], rgb, 0)
            tex[Y:Y + h * S, X:X + w * S, 3] = np.where(a, 255, 0)
            g = np.clip(np.round(lay.gl), 0, 255).astype(np.uint8)
            on = np.any(g > 0, axis=-1) & a
            glow[Y:Y + h * S, X:X + w * S, :3] = np.where(on[..., None], g, 0)
            glow[Y:Y + h * S, X:X + w * S, 3] = np.where(on, 255, 0)
    return Image.fromarray(tex, 'RGBA'), Image.fromarray(glow, 'RGBA')


# ================================================================ Kotlin

def kotlin_file(model):
    layer = '\n'.join('    ' + l for l in model.kotlin().split('\n'))
    return f'''// generated by art/scripts/renegade.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.model

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.client.combat.CombatAnimator
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import kotlin.math.abs
import kotlin.math.max

/**
 * Silvan Marrow, the Unbound: a gaunt renegade of the Starwatch in a tattered midnight-blue robe, a deep pointed hood and
 * a long ragged star-mantle whose hem is torn into six strips. His staff is a real item, drawn in his hand. Geometry
 * comes from art/scripts/renegade.py; edit the model there.
 */
class RenegadeModel(root: ModelPart) : HumanoidModel<EnemyRenderState>(root) {{
    private val mantle: ModelPart = body.getChild("mantle")
    private val hems: List<ModelPart> = (0 until HEMS).map {{ mantle.getChild("hem_$it") }}

    override fun setupAnim(state: EnemyRenderState) {{
        super.setupAnim(state)
        CombatAnimator.pose(this, state, state.frame)
        // The mantle hangs from his shoulders whichever way he bends, kicks out behind with each stride, and stirs as he
        // stands; the strips of its hem lag a little further and sway each at its own pace
        val stride = max(abs(rightLeg.xRot), abs(leftLeg.xRot))
        mantle.xRot = max(REST + stride * 0.4f + state.walkAnimationSpeed * 0.2f, -body.xRot + 0.06f) +
            Mth.sin(state.ageInTicks * 0.05) * 0.035f
        mantle.zRot = Mth.sin(state.ageInTicks * 0.035 + 1.3) * 0.02f
        for ((i, hem) in hems.withIndex()) {{
            hem.xRot = 0.03f + stride * 0.3f + state.walkAnimationSpeed * 0.15f + Mth.sin(state.ageInTicks * 0.085 + i * 1.9) * 0.06f
            hem.zRot += Mth.sin(state.ageInTicks * 0.06 + i * 2.7) * 0.025f
        }}
    }}

    companion object {{
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "renegade"), "main")
        private const val HEMS = 6
        /** The mantle's hang at rest, a little off his back. */
        private const val REST = 0.105f

        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {{
{layer}
        }}
    }}
}}
'''


# ================================================================ Blockbench

def bbmodel_json(model, tex):
    """The Blockbench model with the HD texture (its pixel size is S times the UV size). UUIDs are derived, so a rebuild is
    byte-identical."""
    bb = model.bbmodel(tex, 'renegade')
    t = bb['textures'][0]
    t['width'], t['height'] = tex.width, tex.height
    text = json.dumps(bb)
    uid = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
    ids = {}
    for m in re.finditer(uid, text):
        ids.setdefault(m.group(0), str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/renegade/{len(ids)}')))
    return re.sub(uid, lambda m: ids[m.group(0)], text)


# ================================================================ previews

BG_DARK = (58, 64, 80)
BG_LIGHT = (206, 204, 198)


def lit(tex, glow):
    """The eyes render type adds the glow onto the lit texture; approximate that for the preview."""
    a = np.array(tex).astype(int)
    b = np.array(glow).astype(int)
    on = b[..., 3] > 0
    out = a.copy()
    out[on, :3] = np.clip(a[on, :3] + b[on, :3], 0, 255)
    out[on, 3] = 255
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


def rot_x(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def rot_z(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]])


def render(model, tex, glow, yaw, pitch=-12, scale=9, size=(260, 330), pose=None, y_shift=0.0, bg=None):
    faces = [(A, B, D, (u * S, v * S, w * S, h * S), face, n) for A, B, D, (u, v, w, h), face, n in model.world_faces(pose)]
    img = model.render(tex, yaw=yaw, pitch=pitch, scale=scale, size=size, glow=lit(tex, glow), faces=faces, y_shift=y_shift)
    if bg is not None:
        a = np.array(img)
        mask = np.all(a[..., :3] == np.array(BG_DARK), axis=-1)
        a[mask, :3] = bg
        img = Image.fromarray(a, 'RGBA')
    return img


def walk_pose(swing=38, mantle=14):
    """A mid-stride pose for the preview: legs and arms swinging, the mantle kicked out behind, the hem lagging."""
    pose = {'right_leg': (rot_x(swing), None), 'left_leg': (rot_x(-swing), None),
            'right_arm': (rot_x(-swing * 0.7), None), 'left_arm': (rot_x(swing * 0.7), None),
            'mantle': (rot_x(6 + mantle), None)}
    for i in range(6):
        pose[f'hem_{i}'] = (rot_z(HEM_TILT[i]) @ rot_x(8 + mantle * 0.6), None)
    return pose


def previews(model, tex, glow, outdir):
    os.makedirs(outdir, exist_ok=True)
    w, h = 260, 330
    row = [render(model, tex, glow, yaw, size=(w, h), y_shift=-1.5) for yaw in (22, 160, 200, -70)]
    walk = walk_pose()
    row2 = [render(model, tex, glow, yaw, size=(w, h), pose=walk, y_shift=-1.5) for yaw in (22, 70, 200)]
    sheet = Image.new('RGBA', (w * 4, h * 2), (*BG_DARK, 255))
    for i, im in enumerate(row):
        sheet.paste(im, (i * w, 0))
    for i, im in enumerate(row2):
        sheet.paste(im, (i * w, h))
    sheet.paste(render(model, tex, glow, 22, size=(w, h), y_shift=-1.5, bg=BG_LIGHT), (3 * w, h))
    sheet.save(os.path.join(outdir, 'renegade_views.png'))
    # the head close up, from the front and a three-quarter turn, with the glow
    close = [render(model, tex, glow, yaw, pitch=-6, scale=26, size=(340, 380), y_shift=1.6) for yaw in (0, 28, 62)]
    cs = Image.new('RGBA', (340 * 3, 380))
    for i, im in enumerate(close):
        cs.paste(im, (i * 340, 0))
    cs.save(os.path.join(outdir, 'renegade_head.png'))
    # the texture, nearest-neighbour, with the glow map under it
    big = tex.resize((tex.width * 3, tex.height * 3), Image.NEAREST)
    gbig = glow.resize((glow.width * 3, glow.height * 3), Image.NEAREST)
    sheet = Image.new('RGBA', (big.width, big.height * 2 + 10), (*BG_DARK, 255))
    sheet.alpha_composite(big, (0, 0))
    sheet.alpha_composite(gbig, (0, big.height + 10))
    sheet.save(os.path.join(outdir, 'renegade_tex.png'))


# ================================================================ main

def main(assets, previews_dir, models):
    model = build_model()
    tex, glow = paint_textures(model)
    tdir = os.path.join(assets, 'textures', 'entity')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, 'renegade.png'), optimize=True)
    glow.save(os.path.join(tdir, 'renegade_glow.png'), optimize=True)
    os.makedirs(os.path.dirname(KOTLIN), exist_ok=True)
    with open(KOTLIN, 'w') as f:
        f.write(kotlin_file(model))
    os.makedirs(models, exist_ok=True)
    with open(os.path.join(models, 'renegade.bbmodel'), 'w') as f:
        f.write(bbmodel_json(model, tex))
    if previews_dir:
        os.makedirs(previews_dir, exist_ok=True)
        with open(os.path.join(previews_dir, 'renegade.kt.txt'), 'w') as f:
            f.write(model.kotlin())
        previews(model, tex, glow, previews_dir)
    used = max(c.uv[1] + c.footprint()[1] for c in model.all_cubes())
    print(f'renegade: {len(list(model.all_cubes()))} cubes, texture {TEX_W}x{TEX_H} (PNG {TEX_W * S}x{TEX_H * S}), {used} rows packed')


if __name__ == '__main__':
    args = sys.argv[1:]
    if not 1 <= len(args) <= 3:
        raise SystemExit('usage: python3 -B art/scripts/renegade.py <assets dir> [preview dir] <models dir>')
    default_models = os.path.join(ROOT, 'art', 'models')
    if len(args) == 1:
        main(args[0], None, default_models)
    elif len(args) == 2:
        main(args[0], None, args[1])
    else:
        main(*args)
