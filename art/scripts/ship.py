"""The cog: a single-masted medieval trading ship, the first of Guildmark's vessels. A clinker-built hull of old dark oak
(each strake lapped over the one below, a tarred black wale, a dull green-black weedy band under the waterline), a raised
stern castle with the master's cabin under it and the ship's wheel on top, a foredeck with a cathead, a stem post and a
bowsprit, one tall log mast with a fighting top, and one great square sail of oxblood canvas sewn in cloths with the Guild
Mark crest across its middle in faded gold. Hemp rigging, iron fittings, a few brass accents and a hold under a grating.

Built with modelkit like the dragon: parts nest (the yard carries the furled sail and a sail of five rows, each row a centre
panel and two side panels; the pennant is three chained segments), so the game poses it from the render state
(client/ship/CogModel.kt, whose setupAnim this script mirrors line by line in `pose()` for the previews).
Minecraft model space: pixels, y down, the bow faces -z, the starboard side is -x, the origin is the waterline in the middle
of the ship. The game draws it at scale 1 (16 units to a block).

The texture is HD: the model declares 512x512 and the PNG is painted at twice that (the UVs are normalised). Nothing is
painted from character grids: each texel of each face is turned back into its place on the ship (the rest pose's world space)
and the paint is a function of that place, so planks, grain, seams and weed run on over the edges of a box, the windows and the
crest run across several boxes, and the sail's cloths, border, stains and tears run across all fifteen of its panels. Flat
things (the decks, the hold's floor, the sail, the pennant, the hatch grating) are single thin boxes cut to shape in the
texture's alpha, so the model has to be drawn with a cull-free cutout render type. The glow map holds only what burns:
the lantern panes, the cabin's windows and door light, and the binnacle lamp.

The bow carries a pair of bitts (two posts with timber heads, a cross-beam and the bowsprit's heel lashed between them): the bow
lookout's clip (ship_anims.py) grips with its right hand at (-7, -43, -64), which is the starboard bitt's head, so they have
something to hold; everything is kept clear of the spots the riders stand and sit on (see `spot_report`).

Writes the texture and its glow map, the Kotlin body (CogModel.kt, straight into the source tree: the geometry is generated
here, the posing in setupAnim is written to match `pose()` below), the part list, a .bbmodel, the item (a 32x little cog in
profile) and previews: a hero still by dusk and by day, the bow with a stand-in lookout on the bitts, a turntable and two
animations (the sail and the wind, driven by the game's own sailing rules; the helm, anchor, bell and lanterns by night), the
texture sheet, and a report of how far the model reaches and what the riders' spots stand on:

    python3 -B art/scripts/ship.py src/main/resources/assets/guildmark <preview-dir> art/models [kotlin-dir] [--no-previews]
"""
import sys, os, json, math, zlib, re, itertools, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np
from PIL import Image, ImageDraw
from modelkit import Cube, Part, Model

TEX_W, TEX_H = 512, 512      # what the model declares
HD = 2                       # the PNG is painted at twice that

# ================================================================== rig math


def krad(deg):
    """Degrees as the Kotlin body prints them (radians to 3 places), so the game and these scripts agree."""
    return math.degrees(round(math.radians(deg), 3))


def krot(rot):
    return tuple(krad(a) for a in rot)


def kpos(p):
    return tuple(round(float(v), 3) for v in p)


def half(v):
    """A size rounded to a whole HD texel (half a declared one), never below one."""
    return max(0.5, round(float(v) * HD) / HD)


def rotm(rot):
    """A part's rotation (degrees about x, then y, then z) as a matrix: Rz Ry Rx, as the game builds it."""
    rx, ry, rz = [math.radians(a) for a in rot]
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X


def rotm_rad(rx, ry, rz):
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X


def euler_of(m):
    """Degrees (x, y, z) with m = Rz(z) Ry(y) Rx(x)."""
    y = math.asin(max(-1.0, min(1.0, -m[2, 0])))
    if abs(math.cos(y)) < 1e-6:
        return (math.degrees(math.atan2(-m[1, 2], m[1, 1])), math.degrees(y), 0.0)
    x = math.atan2(m[2, 1], m[2, 2])
    z = math.atan2(m[1, 0], m[0, 0])
    return tuple(math.degrees(a) for a in (x, y, z))


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def frame(ex, ey):
    """The rotation whose columns are the local x, y, z axes in the parent: x along [ex], y as near [ey] as is square to it."""
    ex = unit(ex)
    ey = np.asarray(ey, float)
    ey = unit(ey - ex * float(ex @ ey))
    ez = np.cross(ex, ey)
    return np.stack([ex, ey, ez], axis=1)


# ================================================================== colour and noise toolkit


def ramp(stops, t):
    """Colours along [stops] (dark to light, evenly spaced) at [t] in 0..1: an array of any shape in, (..., 3) out."""
    s = np.asarray(stops, float)
    n = len(s) - 1
    x = np.clip(t, 0.0, 1.0) * n
    i = np.minimum(np.floor(x).astype(np.int64), n - 1)
    f = (x - i)[..., None]
    return s[i] * (1.0 - f) + s[i + 1] * f


def sstep(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def hash01(ix, iy=0, iz=0, seed=0):
    """A repeatable pseudo-random number in [0, 1) for each integer lattice point."""
    a = np.asarray(ix, np.int64).astype(np.uint64)
    b = np.asarray(iy, np.int64).astype(np.uint64)
    c = np.asarray(iz, np.int64).astype(np.uint64)
    m32 = np.uint64(0xFFFFFFFF)
    with np.errstate(over='ignore'):
        h = (a * np.uint64(73856093)) ^ (b * np.uint64(19349663)) ^ (c * np.uint64(83492791)) ^ np.uint64((int(seed) * 2654435761) & 0xFFFFFFFF)
        h = h & m32
        h = h ^ (h >> np.uint64(15))
        h = (h * np.uint64(2246822519)) & m32
        h = h ^ (h >> np.uint64(13))
        h = (h * np.uint64(3266489917)) & m32
        h = h ^ (h >> np.uint64(16))
    return h.astype(np.float64) / 4294967296.0


def vnoise(x, y, z, seed=0):
    """Smooth value noise in 0..1 at lattice coordinates (one random value per integer point, eased between them)."""
    x = np.asarray(x, float); y = np.asarray(y, float); z = np.asarray(z, float)
    x0 = np.floor(x); y0 = np.floor(y); z0 = np.floor(z)
    fx = sstep(0, 1, x - x0); fy = sstep(0, 1, y - y0); fz = sstep(0, 1, z - z0)
    x0 = x0.astype(np.int64); y0 = y0.astype(np.int64); z0 = z0.astype(np.int64)
    out = 0.0
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                w = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + w * hash01(x0 + dx, y0 + dy, z0 + dz, seed)
    return out


def fbm(P, cell, seed=0, octaves=3):
    """Fractal noise in 0..1 over model-space points [P] (..., 3): features about [cell] model px across."""
    tot = 0.0; amp = 1.0; norm = 0.0; k = 1.0 / cell
    for o in range(octaves):
        tot = tot + amp * vnoise(P[..., 0] * k + 17.3 * o, P[..., 1] * k + 5.1 * o, P[..., 2] * k + 11.7 * o, seed + 31 * o)
        norm += amp; amp *= 0.5; k *= 2.0
    return tot / norm


def fbm2(a, b, cell_a, cell_b, seed=0, octaves=3):
    """Fractal noise over two coordinates with different feature sizes (stretched grain: long along [a], fine across [b])."""
    tot = 0.0; amp = 1.0; norm = 0.0; ka = 1.0 / cell_a; kb = 1.0 / cell_b
    z = np.zeros_like(np.asarray(a, float))
    for o in range(octaves):
        tot = tot + amp * vnoise(a * ka + 17.3 * o, b * kb + 5.1 * o, z + 3.7 * o, seed + 31 * o)
        norm += amp; amp *= 0.5; ka *= 2.0; kb *= 2.0
    return tot / norm


def seg_dist(px, pz, a, b):
    """Distance from points (px, pz) to the segment a-b (2D), and how far along it they are (0..1)."""
    ab = np.asarray(b, float) - np.asarray(a, float)
    L2 = float(ab @ ab) + 1e-12
    t = np.clip(((px - a[0]) * ab[0] + (pz - a[1]) * ab[1]) / L2, 0.0, 1.0)
    return np.hypot(px - (a[0] + t * ab[0]), pz - (a[1] + t * ab[1])), t


def mixc(a, b, t):
    t = np.asarray(t, float)
    if t.ndim:
        t = t[..., None]
    return a * (1 - t) + b * t


def seed_of(*keys):
    return zlib.crc32('/'.join(str(k) for k in keys).encode())


# Colour ramps, dark to light. The tone t (0..1) of a material picks along its ramp.
RAMP = {
    'oak': [(12, 8, 7), (28, 19, 14), (46, 32, 24), (66, 47, 34), (88, 64, 46), (114, 86, 62), (142, 110, 80), (172, 138, 102)],
    'oakdry': [(20, 16, 14), (40, 32, 27), (64, 52, 43), (90, 74, 60), (116, 98, 80), (142, 122, 100), (168, 146, 120), (194, 172, 144)],
    'deck': [(30, 23, 18), (58, 45, 34), (92, 72, 52), (126, 102, 74), (154, 128, 94), (178, 152, 114), (202, 178, 138), (224, 204, 164)],
    'pine': [(26, 20, 16), (52, 41, 32), (82, 66, 50), (112, 92, 70), (142, 120, 92), (170, 148, 116), (196, 174, 140), (220, 202, 168)],
    'barrel': [(18, 11, 8), (40, 25, 17), (68, 44, 29), (98, 68, 44), (130, 94, 62), (160, 120, 82), (188, 148, 104), (214, 178, 130)],
    'tar': [(4, 4, 6), (9, 9, 12), (16, 16, 20), (26, 26, 32), (40, 40, 48), (60, 60, 70), (86, 86, 98), (118, 118, 132)],
    'weed': [(6, 10, 9), (13, 22, 18), (22, 38, 28), (34, 56, 38), (50, 76, 48), (72, 98, 58), (100, 124, 74), (136, 156, 100)],
    'iron': [(5, 5, 8), (12, 12, 17), (24, 25, 32), (40, 42, 52), (62, 64, 76), (92, 94, 108), (130, 132, 146), (178, 180, 194)],
    'rust': [(26, 12, 7), (50, 22, 12), (82, 38, 18), (116, 56, 26), (150, 78, 34), (180, 104, 48), (206, 134, 66), (226, 166, 96)],
    'brass': [(24, 16, 7), (56, 36, 14), (100, 68, 26), (148, 108, 40), (194, 152, 64), (228, 190, 98), (248, 220, 140), (255, 246, 206)],
    'hemp': [(34, 27, 18), (70, 57, 38), (108, 90, 62), (142, 122, 88), (172, 150, 110), (196, 174, 132), (218, 198, 156), (238, 222, 184)],
    'madder': [(20, 5, 9), (42, 8, 14), (70, 12, 20), (100, 19, 28), (130, 28, 36), (160, 42, 46), (190, 66, 62), (216, 98, 84)],
    'gold': [(58, 40, 18), (100, 72, 32), (146, 112, 54), (184, 148, 82), (210, 178, 112), (230, 204, 144), (246, 226, 178), (255, 244, 214)],
    'lamp': [(60, 20, 6), (130, 56, 12), (200, 100, 24), (240, 152, 48), (255, 200, 100), (255, 232, 160)],
    'glassdark': [(6, 9, 12), (12, 18, 24), (20, 30, 38), (32, 46, 56), (50, 68, 80), (80, 102, 116)],
    'stone': [(20, 20, 24), (40, 40, 46), (62, 62, 68), (88, 88, 92), (116, 114, 114), (146, 142, 136)],
}
RAMP = {k: np.array(v, float) for k, v in RAMP.items()}

# ================================================================== the hull's lines

KEEL_Y, RAIL_Y, DECK_Y = 10.0, -22.0, -12.0
BEAM = 28.0
Z_BEAM0, Z_BEAM1 = 2.0, 22.0          # the stations between which the hull is at its fullest


def zfront(y):
    """The stem's outer line: z at height y (raking forward as it rises, curving back a touch at the head)."""
    y = np.asarray(y, float)
    return np.where(y <= RAIL_Y, -72.0 - 0.2 * (RAIL_Y - y), np.where(y <= 0, -64.0 + 0.3636 * y, -64.0 + 0.45 * y))


def zaft(y):
    """The transom's outer face: z at height y (a little overhang under the castle)."""
    y = np.asarray(y, float)
    return np.where(y < -12.0, 72.0 + 0.2222 * (-12.0 - y), 72.0 - 0.09 * (y + 12.0))


def section(y):
    """How full the hull is at height y, 0.36 at the keel (a flat bottom) to 1 at the rail: a round bilge."""
    h = np.clip((KEEL_Y - np.asarray(y, float)) / (KEEL_Y - RAIL_Y), 0.0, 1.0)
    return 0.36 + 0.64 * (1 - (1 - h) ** 4)


def hb(z, y):
    """The hull's outer half-beam at station z and height y."""
    z = np.asarray(z, float); y = np.asarray(y, float)
    zf, za = zfront(y), zaft(y)
    t = np.clip((z - zf) / (Z_BEAM0 - zf), 0, 1)
    fb = 1 - (1 - t) ** 2.4
    t2 = np.clip((z - Z_BEAM1) / (za - Z_BEAM1), 0, 1)
    fs = 1 - 0.357 * t2 ** 2
    F = np.where(z < Z_BEAM0, fb, np.where(z <= Z_BEAM1, 1.0, fs))
    e = np.clip((np.abs(z - 12.0) - 12.0) / 60.0, 0, 1) ** 1.3
    return BEAM * section(y) ** (1 + 0.9 * e) * F


def sheer(z):
    """How far the bulwark's top is lifted toward the bow (a graceful sheer): 0 amidships, 5.5 at the stem."""
    t = np.clip((-46.0 - np.asarray(z, float)) / 26.0, 0, 1)
    return 5.5 * t ** 1.7


def wsheer(y):
    """How much of the sheer a line at height y follows: all of it at the rail, none at the waterline."""
    return np.clip(-np.asarray(y, float) / 22.0, 0, 1) ** 1.4


# ================================================================== building parts

NAMES = set()


class Cu(Cube):
    """A cube that knows what it is made of: [mat] picks its painting (see MATS), [kw] its parameters."""

    def __init__(self, origin, size, mat, name, **kw):
        super().__init__(tuple(float(v) for v in origin), tuple(half(v) if v else 0.0 for v in size), None, name=name)
        self.mat, self.kw = mat, kw


def unique(name):
    assert name not in NAMES, f'duplicate part name {name}'
    NAMES.add(name)
    return name


def mkpart(name, pivot, rot=(0, 0, 0), cubes=(), children=()):
    return Part(unique(name), kpos(pivot), krot(rot), list(cubes), list(children))


def box(name, o, s, mat, **kw):
    """An axis-aligned box from its min corner [o] and size [s], as a part of its own at the origin."""
    c = Cu(o, s, mat, name, **kw)
    return mkpart(name, (0, 0, 0), cubes=[c])


def cbox(name, c, s, mat, rot=(0, 0, 0), **kw):
    """A box centred on [c] and turned by [rot], pivoting on its own centre."""
    s = [half(v) for v in s]
    return mkpart(name, c, rot, [Cu((-s[0] / 2, -s[1] / 2, -s[2] / 2), s, mat, name, **kw)])


def rod(name, p0, p1, t, mat, t2=None, **kw):
    """A square-section rod from [p0] to [p1] of thickness [t] (a line, a spar, a stay): pivoting on p0, its length along its own y
    (so its box-UV layout is one tall strip, and a long stay does not eat the atlas)."""
    p0 = np.asarray(p0, float); p1 = np.asarray(p1, float)
    d = p1 - p0
    L = float(np.linalg.norm(d))
    d = d / L
    ex = np.cross(d, (0.0, 0.0, 1.0)) if abs(d[2]) < 0.9 else np.cross(d, (1.0, 0.0, 0.0))
    R = frame(ex, d)                      # x along ex, y as near d as is square to it: exactly d
    t2 = t if t2 is None else t2
    return mkpart(name, p0, euler_of(R), [Cu((-t / 2, 0, -t2 / 2), (t, L, t2), mat, name, **kw)])


def rod_frame(p0, p1):
    """The rotation (degrees) a rod from p0 to p1 gets, for the code that poses rods in the game."""
    d = unit(np.asarray(p1, float) - np.asarray(p0, float))
    ex = np.cross(d, (0.0, 0.0, 1.0)) if abs(d[2]) < 0.9 else np.cross(d, (1.0, 0.0, 0.0))
    return euler_of(frame(ex, d))


STATIC = []
OCT_DROP = 0.1        # px: the turned copy of an octagon's square sits this much lower, so their tops and bottoms are never coplanar (they would shimmer)


def add(part):
    STATIC.append(part)
    return part


def sm(a, b, t):
    return a + (b - a) * t


# ---- the clinker hull: strakes of rotated planks round the lines above

# name, top, bottom, thickness, lap, outward shift, tone (the painting reads these)
ARCS = {}      # (strake index, side) -> (z at the vertices, arc length there): the painting reads the arc length along a strake from this
STRAKES = [
    ('s0', -20.5, -16.25, 2.0, 1.0, 0.0),
    ('s1', -16.25, -12.0, 2.0, 1.0, 0.0),
    ('s2', -12.0, -6.0, 3.0, 0.0, 0.7),         # the wale, tarred
    ('s3', -6.0, -0.5, 2.5, 1.1, 0.0),
    ('s4', -0.5, 4.5, 2.5, 1.1, 0.0),
    ('s5', 4.5, 8.7, 2.5, 1.1, 0.0),
]
CASTLE_Z = 36.0
SEG = 9.5
PAD = 0.7


def polyline(y_mid, sigma, z0, z1, seg=SEG):
    """Vertices (z and arc length) from z0 to z1 along the hull at height y_mid, about [seg] apart, on side [sigma]."""
    zs = np.linspace(z0, z1, 800)
    xs = sigma * hb(zs, y_mid)
    d = np.hypot(np.diff(xs), np.diff(zs))
    cum = np.concatenate([[0.0], np.cumsum(d)])
    n = max(1, int(round(cum[-1] / seg)))
    targets = np.linspace(0, cum[-1], n + 1)
    return np.interp(targets, cum, zs), targets


def outer_pt(z, y, sigma, shift=0.0):
    """A point on the hull's outer skin at station z, height y (y negative above the water), pushed out by [shift]."""
    yy = float(y)
    return np.array([sigma * (float(hb(z, max(yy, RAIL_Y))) + shift), yy, float(z)])


def plank(name, p_top0, p_bot0, p_top1, p_bot1, sigma, t, mat, s0, **kw):
    """A plank cube lying on the skin between four outer points (top and bottom edges at its two ends): its outer face at the
    pivot, its length along local x (pointing forward on the starboard side and aft on the port side, so the outer face is
    always the front), its height down the slope along local y, its thickness inboard along +z."""
    ex = (p_top1 + p_bot1) / 2 - (p_top0 + p_bot0) / 2
    L0 = float(np.linalg.norm(ex))
    if sigma < 0:
        ex = -ex
    ey = (p_bot0 + p_bot1) / 2 - (p_top0 + p_top1) / 2
    h0 = float(np.linalg.norm(ey))
    R = frame(ex, ey)
    C = (p_top0 + p_top1 + p_bot0 + p_bot1) / 4
    out = -R[:, 2]
    assert out[0] * sigma > 0 or abs(out[0]) < 0.2, (name, out)
    L = half(L0 + PAD)
    h = half(h0 + 0.7)
    C = C + R[:, 1] * 0.35
    c = Cu((-L / 2, -h / 2, 0.0), (L, h, t), mat, name, sigma=sigma, s0=s0, L=L, h=h, **kw)
    return mkpart(name, C, euler_of(R), [c])


def hull_skin():
    """Every strake, both sides, bow to stern, plus the castle's two upper rows."""
    parts = []
    for k, (sname, yt, yb, t, lap, shift) in enumerate(STRAKES):
        ym = (yt + yb) / 2
        for sigma in (-1, 1):
            side = 's' if sigma < 0 else 'p'
            zv, arc = polyline(ym, sigma, float(zfront(ym)) - 0.4, float(zaft(ym)))
            ARCS[(k, sigma)] = (zv, arc)
            for j in range(len(zv) - 1):
                z0, z1 = zv[j], zv[j + 1]
                pts = []
                for z in (z0, z1):
                    yt_ = yt - float(sheer(z)) * float(wsheer(yt))
                    yb_ = yb - float(sheer(z)) * float(wsheer(yb))
                    pts.append((outer_pt(z, yt_, sigma, shift - lap / 2), outer_pt(z, yb_, sigma, shift + lap / 2)))
                (pt0, pb0), (pt1, pb1) = pts
                name = f'h{side}{k}_{j:02d}'
                parts.append(plank(name, pt0, pb0, pt1, pb1, sigma, t, 'hull', arc[j] - PAD / 2, strake=k, kind='wale' if k == 2 else 'plank',
                                   segs=len(zv) - 1, j=j))
    # the castle's upper rows: straight up the sides from the front wall aft
    for k, (yt, yb) in enumerate(((-25.5, -20.5), (-28.5, -25.5))):
        for sigma in (-1, 1):
            side = 's' if sigma < 0 else 'p'
            zv, arc = polyline(-22.0, sigma, CASTLE_Z - 0.5, float(zaft(yt + 2)), seg=9.0)
            ARCS[(6 + k, sigma)] = (zv, arc)
            for j in range(len(zv) - 1):
                pts = []
                for z in (zv[j], zv[j + 1]):
                    pts.append((outer_pt(z, yt, sigma, -0.5), outer_pt(z, yb, sigma, 0.5)))
                (pt0, pb0), (pt1, pb1) = pts
                parts.append(plank(f'hc{side}{k}_{j:02d}', pt0, pb0, pt1, pb1, sigma, 2.2, 'hull', arc[j] - PAD / 2, strake=6 + k, kind='plank',
                                   segs=len(zv) - 1, j=j))
    return parts


# ================================================================== the scene: faces, packing, poses


def face_rects(c):
    """Each face's rectangle in the texture (declared texels, floats: the game maps fractional sizes as they are)."""
    u, v = c.uv
    w, h, d = c.size
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h),
            'front': (u + d, v + d, w, h), 'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}


def face_corners(c):
    """(A, B, D, outward normal) per face in the cube's part frame: A at the rect's top-left texel corner, B along u, D along v,
    the way ModelPart.Cube maps them (the bottom face's first row is the cube's back edge)."""
    x0, y0, z0 = c.origin
    x1, y1, z1 = [o + s for o, s in zip(c.origin, c.size)]
    P = lambda x, y, z: np.array([x, y, z], float)
    return {
        'top': (P(x0, y0, z1), P(x1, y0, z1), P(x0, y0, z0), (0, -1, 0)),
        'bottom': (P(x0, y1, z1), P(x1, y1, z1), P(x0, y1, z0), (0, 1, 0)),
        'right': (P(x0, y0, z1), P(x0, y0, z0), P(x0, y1, z1), (-1, 0, 0)),
        'front': (P(x0, y0, z0), P(x1, y0, z0), P(x0, y1, z0), (0, 0, -1)),
        'left': (P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z0), (1, 0, 0)),
        'back': (P(x1, y0, z1), P(x0, y0, z1), P(x1, y1, z1), (0, 0, 1)),
    }


def pack_atlas(m):
    """Place every cube's box-UV layout in the atlas: big ones first, each at the first spot (top to bottom, left to right on the
    declared grid) where the two strips it really occupies (the cross's arms, at HD, from the true fractional sizes) are free,
    so small cubes drop into the voids at the corners of big ones. Raises if the atlas is too small."""
    W, H = m.tex_w, m.tex_h
    occ = np.zeros((H * HD, W * HD), bool)
    area = lambda c: (2 * c.size[2] + 2 * c.size[0]) * (c.size[2] + c.size[1])
    cubes = sorted(m.all_cubes(), key=lambda c: (-area(c), c.name))
    hd = lambda v: int(round(v * HD))
    first_free = 0
    for c in cubes:
        w, h, d = c.size
        for v in (w, h, d):
            assert abs(v * HD - round(v * HD)) < 1e-6, f'{c.name}: size {v} is not a whole number of HD texels'
        pieces = [(hd(d), 0, hd(2 * w), hd(d)), (0, hd(d), hd(2 * d + 2 * w), hd(h))]
        pieces = [q for q in pieces if q[2] > 0 and q[3] > 0]
        tw, th = math.ceil(2 * d + 2 * w), math.ceil(d + h)
        S = np.zeros((H * HD + 1, W * HD + 1), np.int32)
        S[1:, 1:] = occ.cumsum(0).cumsum(1)
        nv, nu = H - th + 1, W - tw + 1
        if nv <= 0 or nu <= 0:
            raise SystemExit(f'{m.name}: {c.name} is bigger than the texture')
        ok = np.ones((nv, nu), bool)
        for dx, dy, pw, ph in pieces:
            ys = HD * np.arange(nv)[:, None] + dy
            xs = HD * np.arange(nu)[None, :] + dx
            win = S[ys + ph, xs + pw] - S[ys, xs + pw] - S[ys + ph, xs] + S[ys, xs]
            ok &= win == 0
        pos = np.argwhere(ok)
        if not len(pos):
            raise SystemExit(f'{m.name}: texture too small for {c.name}')
        y, x = pos[0]
        for dx, dy, pw, ph in pieces:
            occ[HD * y + dy:HD * y + dy + ph, HD * x + dx:HD * x + dx + pw] = True
        c.uv = (int(x), int(y))
    return int(np.nonzero(occ.any(axis=1))[0].max()) // HD + 1, float(occ.mean())


class Scene:
    """The model flattened for posing and drawing: every part with its parent and rest pose, every face of every cube."""

    def __init__(self, model):
        self.model = model
        self.parts = list(model.all_parts())
        self.index = {p.name: i for i, p in enumerate(self.parts)}
        self.parent = np.full(len(self.parts), -1)
        for p in self.parts:
            for ch in p.children:
                self.parent[self.index[ch.name]] = self.index[p.name]
        self.rest_pos = np.array([p.pivot for p in self.parts], float)
        self.rest_rot = np.array([[math.radians(a) for a in p.rot] for p in self.parts], float)
        fp, fa, fb, fd, fn, fr, fc, ff = [], [], [], [], [], [], [], []
        self.cubes = []
        for pi, p in enumerate(self.parts):
            for c in p.cubes:
                ci = len(self.cubes)
                self.cubes.append((pi, c))
                rects = face_rects(c)
                for face, (A, B, D, n) in face_corners(c).items():
                    x, y, w, h = rects[face]
                    if w <= 0 or h <= 0:
                        continue
                    fp.append(pi); fa.append(A); fb.append(B); fd.append(D); fn.append(n); fc.append(ci); ff.append(face)
                    fr.append((int(round(x * HD)), int(round(y * HD)), int(round(w * HD)), int(round(h * HD))))
        self.FP = np.array(fp); self.FA = np.array(fa); self.FB = np.array(fb); self.FD = np.array(fd)
        self.FN = np.array(fn, float); self.FR = np.array(fr); self.FC = np.array(fc); self.FF = ff
        # Which faces are seen from both sides (cull-free cards: the sail, the pennant, the grating)
        self.double = np.array([self.cubes[ci][1].mat in DOUBLE for ci in fc])

    def mats(self, pose=None, root=None):
        """World matrices (P, 4, 4) of every part for [pose]: {name: dict(pos=, rot=, scale=, visible=)} over the rest pose; and
        which parts are shown (a hidden part hides what hangs from it). [root] is a 4x4 the whole ship is moved by."""
        n = len(self.parts)
        pos = self.rest_pos.copy(); rot = self.rest_rot.copy(); scl = np.ones((n, 3)); vis = np.ones(n, bool)
        for name, d in (pose or {}).items():
            i = self.index[name]
            if 'pos' in d: pos[i] = d['pos']
            if 'rot' in d: rot[i] = d['rot']
            if 'scale' in d: scl[i] = d['scale']
            if 'visible' in d: vis[i] = d['visible']
        M = np.zeros((n, 4, 4))
        for i in range(n):
            R = rotm_rad(*rot[i])
            L = np.eye(4)
            L[:3, :3] = R * scl[i][None, :]
            L[:3, 3] = pos[i]
            par = self.parent[i]
            if par >= 0:
                M[i] = M[par] @ L
                vis[i] = vis[i] and vis[par]
            else:
                M[i] = L if root is None else root @ L
        return M, vis

    def quads(self, pose=None, root=None):
        """Every shown face in model space: corners A, B, D, outward normal, rect, face index."""
        M, vis = self.mats(pose, root)
        keep = vis[self.FP]
        idx = np.nonzero(keep)[0]
        Mp = M[self.FP[idx]]
        R, T = Mp[:, :3, :3], Mp[:, :3, 3]
        A = np.einsum('nij,nj->ni', R, self.FA[idx]) + T
        B = np.einsum('nij,nj->ni', R, self.FB[idx]) + T
        D = np.einsum('nij,nj->ni', R, self.FD[idx]) + T
        n = -np.cross(B - A, D - A)
        ln = np.linalg.norm(n, axis=1)
        n = n / np.maximum(ln, 1e-12)[:, None]
        return idx, A, B, D, n, ln > 1e-9


DOUBLE = {'canvas', 'furl_dummy', 'pennant', 'grate', 'deck'}


# ================================================================== drawing (orthographic, z-buffered, for the previews)


def rotm_y(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


class Camera:
    """An orthographic camera looking down at the model from [yaw] degrees round it (0 from ahead of the bow, 180 from astern) and
    [elev] degrees above it, [scale] pixels to a model unit, [center] the model point in the middle of the picture."""

    def __init__(self, yaw, elev, scale, size, center):
        self.yaw, self.elev, self.scale, self.size, self.center = yaw, elev, scale, size, np.asarray(center, float)
        self.cy, self.sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        self.ce, self.se = math.cos(math.radians(elev)), math.sin(math.radians(elev))
        self.view = np.array([self.sy * self.ce, self.se, self.cy * self.ce])      # the way the camera looks, in model space

    def project(self, p):
        x, y, z = p[..., 0] - self.center[0], p[..., 1] - self.center[1], p[..., 2] - self.center[2]
        x2 = x * self.cy - z * self.sy
        z2 = x * self.sy + z * self.cy
        y3 = y * self.ce - z2 * self.se
        z3 = z2 * self.ce + y * self.se
        W, H = self.size
        return np.stack([W / 2 + x2 * self.scale, H / 2 + y3 * self.scale, z3], -1)

    def plane_y(self, yw, W, H):
        """For every pixel: where a ray from it meets the horizontal plane at height [yw]: model x, z and the depth there."""
        xs, ys = np.meshgrid(np.arange(W) + 0.5, np.arange(H) + 0.5)
        x2 = (xs - W / 2) / self.scale
        y3 = (ys - H / 2) / self.scale
        y = yw - self.center[1]
        z2 = (y * self.ce - y3) / self.se
        z3 = z2 * self.ce + y * self.se
        x = x2 * self.cy + z2 * self.sy + self.center[0]
        z = -x2 * self.sy + z2 * self.cy + self.center[2]
        return x, z, z3


def raster(scene, tex, glow, pose, cam, light, glow_gain=1.0, bg=None, size=None, only=None, alpha_cut=60, root=None):
    """Draw the posed model: returns (colour (H, W, 3) floats, depth, world y of what was hit, hit mask)."""
    W, H = cam.size
    img = np.zeros((H, W, 3)); depth = np.full((H, W), 1e9); ybuf = np.full((H, W), -1e9); hit = np.zeros((H, W), bool)
    gbuf = np.zeros((H, W, 3))
    T = np.asarray(tex, np.float32)
    G = None if glow is None else np.asarray(glow, np.float32)
    idx, A, B, D, N, okq = scene.quads(pose, root)
    P = cam.project(np.stack([A, B, D], 1))             # (n, 3, 3)
    facing = N @ cam.view
    dbl = scene.double[idx]
    show = okq & ((facing < 1e-4) | dbl)
    if only is not None:
        show &= np.isin(scene.FC[idx], only)
    ys, xs = np.mgrid[0:H, 0:W]
    shade = light(N)                                   # (n, 3) colour multipliers per face
    for k in np.nonzero(show)[0]:
        a, b, d = P[k]
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-7:
            continue
        x0 = int(max(0, math.floor(min(a[0], b[0], d[0], b[0] + e2[0]))))
        x1 = int(min(W, math.ceil(max(a[0], b[0], d[0], b[0] + e2[0])) + 1))
        y0 = int(max(0, math.floor(min(a[1], b[1], d[1], b[1] + e2[1]))))
        y1 = int(min(H, math.ceil(max(a[1], b[1], d[1], b[1] + e2[1])) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]; py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if not inside.any():
            continue
        dep = a[2] + s * e1[2] + t * e2[2]
        u0, v0, w, h = scene.FR[idx[k]]
        tu = np.clip(u0 + np.floor(s * w).astype(int), u0, u0 + w - 1)
        tv = np.clip(v0 + np.floor(t * h).astype(int), v0, v0 + h - 1)
        texel = T[tv, tu]
        zb = depth[y0:y1, x0:x1]
        ok = inside & (texel[..., 3] > alpha_cut) & (dep < zb)
        if not ok.any():
            continue
        col = texel[..., :3] * shade[k]
        gl = np.zeros_like(col)
        if G is not None:
            g = G[tv, tu]
            gl = g[..., :3] * (g[..., 3:4] / 255.0) * glow_gain
            col = col + gl
        gbuf[y0:y1, x0:x1][ok] = gl[ok]
        img[y0:y1, x0:x1][ok] = col[ok]
        zb[ok] = dep[ok]
        hit[y0:y1, x0:x1][ok] = True
        Aw, Bw, Dw = A[k], B[k], D[k]
        yw = Aw[1] + s * (Bw[1] - Aw[1]) + t * (Dw[1] - Aw[1])
        ybuf[y0:y1, x0:x1][ok] = yw[ok]
    return img, depth, ybuf, hit, gbuf


def frame_down(ex, down=(0.0, 1.0, 0.0)):
    """The rotation whose local x runs along [ex], z points as near [down] as is square to it, y completing a right-handed frame."""
    ex = unit(ex)
    ez = np.asarray(down, float)
    ez = unit(ez - ex * float(ex @ ez))
    ey = np.cross(ez, ex)
    return np.stack([ex, ey, ez], axis=1)


def flat_bar(name, q0, q1, width, thick, mat, down=(0.0, 1.0, 0.0), **kw):
    """A bar lying from [q0] to [q1] (the middle of its top face at each end): [width] across it (horizontal, square to it),
    [thick] deep below that face. Used for caps and rails: local x along the bar, -z up."""
    q0 = np.asarray(q0, float); q1 = np.asarray(q1, float)
    R = frame_down(q1 - q0, down)
    L = half(float(np.linalg.norm(q1 - q0)) + 0.5)
    w, t = half(width), half(thick)
    return mkpart(name, (q0 + q1) / 2, euler_of(R), [Cu((-L / 2, -w / 2, 0.0), (L, w, t), mat, name, L=L, **kw)])


def hull_extras():
    """The stem, keel and sternpost, the transom, the flat bottom, the rail caps, the ribs inside the bulwarks."""
    parts = []
    # --- the stem: a post along the raking line, its front face on it, a touch narrower at the head
    ys = [9.6, 4.5, -2.0, -9.0, -16.0, -22.0, -26.0, -31.0]
    for i in range(len(ys) - 1):
        ya, yb = ys[i + 1], ys[i]                      # upper end, lower end
        za, zb = float(zfront(ya)), float(zfront(yb))
        ang = math.degrees(math.atan2(zb - za, yb - ya))
        ym = (ya + yb) / 2
        L = half(math.hypot(zb - za, yb - ya) + 0.8)
        w = half(sm(5.0, 3.5, i / (len(ys) - 2)))
        d = half(sm(4.5, 3.5, i / (len(ys) - 2)))
        nm = f'stem{i}'
        parts.append(mkpart(nm, (0, ym, float(zfront(ym))), (ang, 0, 0), [Cu((-w / 2, -L / 2, 0), (w, L, d), 'timber', nm, axis='y', tone=-0.1, wear=1.0)]))
    # --- the keel, a false keel under the flat bottom, the sternpost
    parts.append(mkpart('keel', (0.0, 9.35, -59.0), (90, 0, 0), [Cu((-2.5, 0.0, -0.75), (5, 131, 1.5), 'timber', 'keel', axis='z', tone=-0.2, wear=1.2)]))
    parts.append(box('sternpost', (-2.0, -12.0, 72.0), (4, 22.0, 4), 'timber', axis='y', tone=-0.15, wear=1.0))
    parts.append(box('sternpost_heel', (-2.25, 7.0, 68.0), (4.5, 3.5, 8), 'timber', axis='z', tone=-0.2, wear=1.0))
    # --- the transom: planks across the stern, one for each strake, raked
    rows = [(s[1], s[2]) for s in STRAKES] + [(-25.5, -20.5), (-28.5, -25.5)]
    for k, (yt, yb) in enumerate(rows):
        ym = (yt + yb) / 2
        za = float(zaft(ym))
        slope = (float(zaft(ym + 0.5)) - float(zaft(ym - 0.5)))
        ang = math.degrees(math.atan(slope))
        W = half(2 * (float(hb(za, min(max(ym, -22.0), 9.0))) - 1.0))
        h = half(abs(yb - yt) + 0.7)
        nm = f'tr{k}'
        parts.append(mkpart(nm, (0, ym, za), (ang, 0, 0), [Cu((-W / 2, -h / 2, -2.0), (W, h, 2.0), 'hull', nm, kind='transom', strake=k if k < 6 else 6 + (k - 6), sigma=0, s0=0.0, L=W, h=h)]))
    # --- the flat bottom, cut to the hull in its alpha
    parts.append(box('bottom', (-14.0, 7.0, -58.0), (28, 1.6, 129), 'deck', zone='bottom'))
    # --- rail caps on the bulwark and on the castle's walls
    for sigma in (-1, 1):
        side = 's' if sigma < 0 else 'p'
        z0 = -70.0
        zv = np.linspace(z0, CASTLE_Z, int(round((CASTLE_Z - z0) / 9.0)) + 1)
        pts = [np.array([sigma * (float(hb(z, -21.0)) - 1.5), -22.0 - float(sheer(z)), z]) for z in zv]
        for j in range(len(pts) - 1):
            parts.append(flat_bar(f'cap{side}{j:02d}', pts[j], pts[j + 1], 4.0, 1.5, 'timber', axis='x', tone=0.08, wear=1.4, rail=True))
        zv = np.linspace(CASTLE_Z - 0.5, float(zaft(-29.0)) - 1.0, 5)
        pts = [np.array([sigma * (float(hb(z, -22.0)) - 1.5), -30.2, z]) for z in zv]
        for j in range(len(pts) - 1):
            parts.append(flat_bar(f'ccap{side}{j:02d}', pts[j], pts[j + 1], 4.0, 1.5, 'timber', axis='x', tone=0.08, wear=1.4, rail=True))
    return parts


def hin(z, y):
    """The hull's inner outline at station z, height y: the decks reach into the planking, so nothing shows between."""
    return float(hb(z, y)) - 1.3


def decks():
    """The decks (flat slabs cut to the hull in their alpha), the hold's floor and hatch, the foredeck's break and steps, the cabin's
    front wall with its door frame, the steps up to the castle."""
    parts = []
    parts.append(box('deck_main', (-28.0, -12.0, -51.0), (56, 1.5, 87.5), 'deck', zone='main'))
    parts.append(box('deck_fore', (-28.0, -20.0, -73.0), (56, 1.5, 23.5), 'deck', zone='fore'))
    parts.append(box('deck_castle', (-28.0, -30.0, 35.5), (56, 1.5, 41.5), 'deck', zone='castle'))
    parts.append(box('hold_floor', (-20.0, 3.0, -4.0), (40, 1.2, 48), 'deck', zone='hold'))
    # the hatch: a low coaming round the opening, the iron-banded grating over it
    parts.append(box('coaming_f', (-13.0, -13.6, 9.0), (26, 1.6, 1.5), 'timber', axis='x', tone=-0.1, wear=1.2))
    parts.append(box('coaming_b', (-13.0, -13.6, 29.5), (26, 1.6, 1.5), 'timber', axis='x', tone=-0.1, wear=1.2))
    parts.append(box('coaming_l', (11.5, -13.6, 10.5), (1.5, 1.6, 19), 'timber', axis='z', tone=-0.1, wear=1.2))
    parts.append(box('coaming_r', (-13.0, -13.6, 10.5), (1.5, 1.6, 19), 'timber', axis='z', tone=-0.1, wear=1.2))
    parts.append(box('grating', (-11.5, -12.9, 10.5), (23, 1.0, 19), 'grate'))           # its top is 0.9 over the deck's: walked on, it is flush
    # the foredeck's break and the steps down from it
    wb = hin(-50.0, -16.0)
    parts.append(box('fore_break', (-wb, -20.2, -51.5), (2 * wb, 8.5, 2.5), 'hull', kind='wall', strake=7, sigma=0, s0=0.0, L=2 * wb, h=8.5))
    parts.append(box('fore_step1', (-6.5, -16.0, -49.5), (13, 4.0, 3), 'timber', axis='x', tone=0.0, wear=1.3, steps=True))
    parts.append(box('fore_step2', (-6.5, -14.0, -46.5), (13, 2.0, 3), 'timber', axis='x', tone=0.0, wear=1.3, steps=True))
    # the castle's front wall with the master's door, and its frame
    wc = hin(CASTLE_Z, -22.0)
    parts.append(box('cabin_front', (-wc, -28.5, 36.0), (2 * wc, 16.5, 3), 'hull', kind='wall', strake=7, sigma=0, s0=0.0, L=2 * wc, h=16.5))
    parts.append(box('door_lintel', (-5.6, -29.0, 34.8), (11.2, 1.5, 1.5), 'timber', axis='x', tone=-0.1, wear=1.2))
    parts.append(box('door_jamb_l', (4.2, -27.6, 34.8), (1.5, 15.6, 1.5), 'timber', axis='y', tone=-0.1, wear=1.2))
    parts.append(box('door_jamb_r', (-5.7, -27.6, 34.8), (1.5, 15.6, 1.5), 'timber', axis='y', tone=-0.1, wear=1.2))
    # the steps up to the castle on the port side, with stringers
    for i in range(4):
        top = -12.0 - 4.55 * (i + 1)                      # the last is a hair over the castle deck, and each a hair wider than the one before: no two faces coplanar
        z0 = 24.0 + 3.0 * i
        parts.append(box(f'step{i}', (13.0 - 0.15 * i, top, z0), (10.5, -12.0 - top, 36.0 - z0), 'timber', axis='x', tone=0.04, wear=1.5, steps=True))
    for nm, x in (('stringer_a', 12.0), ('stringer_b', 23.0)):
        parts.append(rod(nm, (x, -11.5, 22.5), (x, -30.0, 36.0), 1.5, 'timber', axis='z', tone=-0.1, wear=1.2))
    return parts


def castle_rails():
    """The castle's rail: posts and a cap rail round the sides, the stern and the front, and a mid rail under it."""
    parts = []
    top_y = -44.0
    post_h = 12.5

    def post(nm, x, z):
        parts.append(box(nm, (x - 0.8, top_y + 1.5, z - 0.8), (1.6, post_h, 1.6), 'timber', axis='y', tone=0.0, wear=1.3, rail=True))
    for sigma in (-1, 1):
        side = 's' if sigma < 0 else 'p'
        zs = [38.5, 44.0, 50.0, 56.0, 62.0, 68.0, 74.0]
        pts = [np.array([sigma * (float(hb(z, -22.0)) - 2.0), top_y, z]) for z in zs]
        for i, p in enumerate(pts):
            post(f'rp{side}{i}', p[0], p[2])
        for i in range(len(pts) - 1):
            parts.append(flat_bar(f'rc{side}{i}', pts[i], pts[i + 1], 2.4, 1.5, 'timber', axis='x', tone=0.08, wear=1.4, rail=True))
            q0 = pts[i] + np.array([0, 6.0, 0]); q1 = pts[i + 1] + np.array([0, 6.0, 0])
            parts.append(flat_bar(f'rm{side}{i}', q0, q1, 1.2, 1.2, 'timber', axis='x', tone=0.0, wear=1.3, rail=True))
    # across the stern
    zst = 74.4
    xs = [-18.0, -12.0, -6.0, 0.0, 6.0, 12.0, 18.0]
    xs = [x for x in xs]
    for i, x in enumerate(xs):
        post(f'rs{i}', x, zst)
    for i in range(len(xs) - 1):
        parts.append(flat_bar(f'rsc{i}', np.array([xs[i], top_y, zst]), np.array([xs[i + 1], top_y, zst]), 2.4, 1.5, 'timber', axis='x', tone=0.08, wear=1.4, rail=True))
        parts.append(flat_bar(f'rsm{i}', np.array([xs[i], top_y + 6.0, zst]), np.array([xs[i + 1], top_y + 6.0, zst]), 1.2, 1.2, 'timber', axis='x', tone=0.0, wear=1.3, rail=True))
    # across the front: from the starboard side to the bell frame, and a stub by the steps
    zf = 37.0
    xs = [-25.0, -17.0, -9.0, -1.0, 6.0]
    for i, x in enumerate(xs):
        post(f'rf{i}', x, zf)
    for i in range(len(xs) - 1):
        parts.append(flat_bar(f'rfc{i}', np.array([xs[i], top_y, zf]), np.array([xs[i + 1], top_y, zf]), 2.4, 1.5, 'timber', axis='x', tone=0.08, wear=1.4, rail=True))
        parts.append(flat_bar(f'rfm{i}', np.array([xs[i], top_y + 6.0, zf]), np.array([xs[i + 1], top_y + 6.0, zf]), 1.2, 1.2, 'timber', axis='x', tone=0.0, wear=1.3, rail=True))
    post('rf_stair_b', 24.5, zf)
    return parts


# ================================================================== the mast, the yard, the sail

MAST_X, MAST_Z = 0.0, -8.0
MAST_TOP = -186.0
YARD_AT = (0.0, -152.0, -11.0)
SAIL_TOP, SAIL_FOOT = -150.0, -62.0
SAIL_ROWS = 5
ROW_H = [18.0, 18.0, 17.5, 17.5, 17.0]               # 88 in all
ROW_REST_Z = -2.0                                    # the sail hangs 2 in front of the yard's pivot
SAIL_HALF = [50.0, 49.5, 49.0, 48.0, 47.0]            # the half-width of each row, narrower toward the foot
CENTRE_HALF = 20.0                                   # the centre panel's half-width; the sides go from there out
TOP_Y = -160.0


def mast_width(y):
    """The mast's width at height y: 5 at the deck to 2.6 at the head."""
    return float(np.interp(y, [-186.0, -12.0], [2.6, 5.0]))


def mast_parts():
    """The mast: octagonal sections (a square and the same turned an eighth) stacked and tapering, iron hoops, the fighting top, the
    masthead and its pole."""
    parts = []
    edges = [-12.0 - 16.0 * i for i in range(11)] + [MAST_TOP]
    for i in range(len(edges) - 1):
        yl, yu = edges[i], edges[i + 1]                  # lower, upper end of the section (y is down: yu < yl)
        w = half(mast_width((yl + yu) / 2) + 0.1)
        h = half(yl - yu + 0.5)
        ym = (yl + yu) / 2
        parts.append(mkpart(f'mast{i}', (MAST_X, ym, MAST_Z), (0, 0, 0), [Cu((-w / 2, -h / 2, -w / 2), (w, h, w), 'mast', f'mast{i}', axis='y', sect=i)]))
        parts.append(mkpart(f'mast{i}b', (MAST_X, ym + OCT_DROP, MAST_Z), (0, 45, 0), [Cu((-w / 2, -h / 2, -w / 2), (w, h, w), 'mast', f'mast{i}b', axis='y', sect=i)]))
    for i, y in enumerate((-17.0, -34.0, -62.0, -92.0, -122.0, -153.0, -176.0)):
        w = half(mast_width(y) + 1.5)
        parts.append(mkpart(f'hoop{i}', (MAST_X, y, MAST_Z), (0, 0, 0), [Cu((-w / 2, -0.75, -w / 2), (w, 1.5, w), 'iron', f'hoop{i}', wear=1.0, band=True)]))
        parts.append(mkpart(f'hoop{i}b', (MAST_X, y + OCT_DROP, MAST_Z), (0, 45, 0), [Cu((-w / 2, -0.75, -w / 2), (w, 1.5, w), 'iron', f'hoop{i}b', wear=1.0, band=True)]))
    # the fighting top: a round platform, trestletrees under it, an octagon of rail posts and caps
    parts.append(mkpart('top_floor', (MAST_X, TOP_Y, MAST_Z), cubes=[Cu((-9.5, -0.75, -9.5), (19, 1.5, 19), 'deck', 'top_floor', zone='top')]))
    parts.append(box('top_tree_a', (-1.2, TOP_Y + 0.75, MAST_Z - 8.5), (2.5, 2.5, 17), 'timber', axis='z', tone=-0.1, wear=1.2))
    parts.append(box('top_tree_b', (-8.5, TOP_Y + 0.75, MAST_Z - 1.2), (17, 2.5, 2.5), 'timber', axis='x', tone=-0.1, wear=1.2))
    R = 8.6
    for k in range(8):
        a = math.radians(k * 45 + 22.5)
        px, pz = R * math.sin(a), R * math.cos(a)
        parts.append(box(f'top_post{k}', (MAST_X + px - 0.6, TOP_Y - 6.5, MAST_Z + pz - 0.6), (1.2, 6.5, 1.2), 'timber', axis='y', tone=0.0, wear=1.3))
    for k in range(8):
        a0, a1 = math.radians(k * 45 + 22.5), math.radians((k + 1) * 45 + 22.5)
        q0 = np.array([MAST_X + R * math.sin(a0), TOP_Y - 6.5, MAST_Z + R * math.cos(a0)])
        q1 = np.array([MAST_X + R * math.sin(a1), TOP_Y - 6.5, MAST_Z + R * math.cos(a1)])
        parts.append(flat_bar(f'top_rail{k}', q0, q1, 1.5, 1.2, 'timber', axis='x', tone=0.08, wear=1.4))
        parts.append(flat_bar(f'top_mid{k}', q0 + np.array([0, 3.2, 0]), q1 + np.array([0, 3.2, 0]), 1.0, 1.0, 'timber', axis='x', tone=0.0, wear=1.3))
    return parts


def masthead_part():
    """The masthead: a cap and a pole, the part the pennant hangs from (pennant1 is its child)."""
    pennant = pennant_chain()
    cap = [Cu((-2.0, -1.5, -2.0), (4, 1.5, 4), 'iron', 'mh_cap', wear=1.0, band=True),
           Cu((-0.5, -6.0, -0.5), (1, 4.5, 1), 'timber', 'mh_pole', axis='y', tone=-0.1, wear=1.0),
           Cu((-1.0, -7.0, -1.0), (2, 1.0, 2), 'brass', 'mh_ball', wear=1.0)]
    return mkpart('masthead', (MAST_X, MAST_TOP, MAST_Z), cubes=cap, children=[pennant])


PENNANT_LEN = [13.0, 13.0, 14.0]
PENNANT_H = [8.0, 6.5, 5.0]


def pennant_chain():
    """Three segments, each along its own +z, a long two-tailed streamer: deep red with a gold band, cut to shape in its alpha."""
    segs = []
    tail = None
    for i in (2, 1, 0):
        L, h = PENNANT_LEN[i], PENNANT_H[i]
        z0 = 0.0
        cubes = [Cu((-0.25, -h / 2, 0.0), (0.5, h, L), 'pennant', f'pennant{i + 1}', seg=i, L=L, h=h)]
        piv = (0.0, 0.0, 0.0) if i == 0 else (0.0, 0.0, PENNANT_LEN[i - 1])
        tail = mkpart(f'pennant{i + 1}', (0, -3.0, 1.0) if i == 0 else piv, cubes=cubes, children=[tail] if tail else [])
    return tail


def yard_part():
    """The yard: a spar across the mast, thick in the middle and drawn to the arms, iron caps at its ends; the sail's top row and the
    furled bundle hang from it."""
    cubes, kids = [], []
    xs = [-54.0, -44.0, -32.0, -18.0, 18.0, 32.0, 44.0, 54.0]
    th = [2.2, 2.8, 3.4, 4.4, 3.4, 2.8, 2.2]
    for i in range(len(xs) - 1):
        x0, x1 = xs[i], xs[i + 1]
        t = half(th[i])
        cubes.append(Cu((x0, -t / 2, -t / 2), (x1 - x0, t, t), 'mast', f'yard{i}', axis='x', sect=20 + i))
        kids.append(mkpart(f'yard{i}b', (0, 0, 0), (45, 0, 0), [Cu((x0, -t / 2, -t / 2), (x1 - x0, t, t), 'mast', f'yard{i}b', axis='x', sect=20 + i)]))
    for nm, x in (('yard_cap_s', -55.5), ('yard_cap_p', 54.0)):
        cubes.append(Cu((x, -2.0, -2.0), (1.5, 4.0, 4.0), 'iron', nm, wear=1.2, band=True))
    for nm, x in (('yard_band_s', -50.5), ('yard_band_p', 49.0), ('yard_band_cs', -9.0), ('yard_band_cp', 7.5)):
        cubes.append(Cu((x, -2.5, -2.5), (1.5, 5.0, 5.0), 'iron', nm, wear=1.0, band=True))
    # a jackstay along the front of the yard and the sling at its middle
    cubes.append(Cu((-50.0, -1.0, -2.6), (100, 1.0, 1.0), 'rope', 'yard_jack', axis='x'))
    cubes.append(Cu((-3.0, -3.0, -1.4), (6, 6, 2.8), 'hemp_block', 'yard_sling'))
    sail1 = sail_rows()
    furl = furl_part()
    return mkpart('yard', YARD_AT, cubes=cubes, children=kids + [sail1, furl])


def furl_part():
    """The sail furled: a lashed bundle of canvas along the yard (the animation scales it up as the sail is hauled up)."""
    cubes = [Cu((-50.0, -2.0, -2.25), (100, 4.0, 4.5), 'furl', 'furl_a', axis='x', half=0),
             Cu((-34.0, -3.0, -3.0), (68, 6.0, 6.0), 'furl', 'furl_b', axis='x', half=1)]
    return mkpart('furl', (0, 1.5, -3.5), cubes=cubes)


def sail_rows():
    """Five rows, each a centre panel with a side panel either side, each row a child of the one above, pivoted at its own top edge."""
    chain = None
    for r in range(SAIL_ROWS - 1, -1, -1):
        H = ROW_H[r]
        side_w = SAIL_HALF[r] - CENTRE_HALF
        y_world = SAIL_TOP + sum(ROW_H[:r])
        nm = f'sail{r + 1}'
        centre = [Cu((-CENTRE_HALF, 0.0, -0.25), (2 * CENTRE_HALF, H, 0.5), 'canvas', nm, row=r, panel=0)]
        left = mkpart(f'{nm}l', (CENTRE_HALF, 0, 0), cubes=[Cu((0.0, 0.0, -0.25), (side_w, H, 0.5), 'canvas', f'{nm}l', row=r, panel=1)])
        right = mkpart(f'{nm}r', (-CENTRE_HALF, 0, 0), cubes=[Cu((-side_w, 0.0, -0.25), (side_w, H, 0.5), 'canvas', f'{nm}r', row=r, panel=-1)])
        kids = [left, right] + ([chain] if chain else [])
        piv = (0.0, 2.0 + ROW_REST_Z * 0, ROW_REST_Z) if r == 0 else (0.0, ROW_H[r - 1], 0.0)
        chain = mkpart(nm, piv, cubes=centre, children=kids)
    return chain


# ================================================================== rigging, bowsprit and the bow

STAY_TOP = np.array([0.0, -184.0, MAST_Z - 2.2])
SPRIT_TIP = np.array([0.0, -36.0, -102.0])


def rigging():
    """Hemp and iron: the forestay to the bowsprit, two backstays to the castle, three shrouds a side with ratlines and chain plates,
    the halyard, the bobstay and the bowsprit shrouds."""
    parts = []
    # --- stays
    parts.append(rod('forestay', STAY_TOP, SPRIT_TIP + np.array([0, -1.0, 0.5]), 1.2, 'rope', axis='z', thick=1.2))
    for sigma, side in ((-1, 's'), (1, 'p')):
        parts.append(rod(f'backstay_{side}', np.array([sigma * 0.8, -182.0, MAST_Z + 1.2]), np.array([sigma * 19.0, -44.5, 70.0]), 1.2, 'rope', axis='z', thick=1.2))
    parts.append(rod('bobstay', SPRIT_TIP + np.array([0, 1.4, 1.0]), np.array([0.0, 1.0, -65.0]), 1.0, 'rope', axis='z', thick=1.0))
    for sigma, side in ((-1, 's'), (1, 'p')):
        parts.append(rod(f'sprit_shroud_{side}', SPRIT_TIP + np.array([sigma * 0.8, 1.0, 1.0]), np.array([sigma * 11.0, -24.0, -66.0]), 0.9, 'rope', axis='z', thick=0.9))
    # --- the halyard: a block at the masthead, the tye down to the yard, the fall down the mast to the deck
    parts.append(box('halyard_block', (-1.6, -184.5, MAST_Z - 5.0), (3.2, 4.0, 2.6), 'iron', wear=1.0, block=True))
    parts.append(rod('halyard_tye', np.array([0.0, -180.5, MAST_Z - 3.8]), np.array([0.0, -153.5, YARD_AT[2] - 0.3]), 1.0, 'rope', axis='z', thick=1.0))
    parts.append(rod('halyard_fall', np.array([0.0, -181.0, MAST_Z + 2.9]), np.array([0.0, -15.0, MAST_Z + 3.6]), 0.9, 'rope', axis='z', thick=0.9))
    # --- shrouds, ratlines, chain plates and deadeyes
    zs = (-14.0, -6.0, 2.0)
    for sigma, side in ((-1, 's'), (1, 'p')):
        lows, ups = [], []
        for i, z in enumerate(zs):
            lo = np.array([sigma * (float(hb(z, -22.0)) + 0.9), -20.0, z])
            up = np.array([sigma * 8.2, TOP_Y + 1.0, MAST_Z + (z + 8.0) * 0.55])
            lows.append(lo); ups.append(up)
            parts.append(rod(f'shroud_{side}{i}', lo + np.array([0, -1.0, 0]), up, 1.1, 'rope', axis='z', thick=1.1, tar=True))
            parts.append(box(f'chain_{side}{i}', (sigma * (float(hb(z, -22.0)) + 0.4) - 0.5 + (0 if sigma > 0 else -0.0), -22.0, z - 1.6), (1.0, 9.0, 3.2), 'iron', wear=1.2, plate=True))
            parts.append(box(f'deadeye_{side}{i}', (lo[0] - 1.3, -22.0, z - 1.3), (2.6, 2.5, 2.6), 'timber', axis='y', tone=-0.2, wear=1.0, deadeye=True))
        for k in range(9):
            y = -34.0 - 13.0 * k
            pts = []
            for lo, up in zip(lows, ups):
                u = (y - lo[1]) / (up[1] - lo[1])
                pts.append(lo + (up - lo) * u)
            for i in range(2):
                parts.append(rod(f'rat_{side}{k}_{i}', pts[i], pts[i + 1], 0.8, 'rope', axis='z', thick=0.8))
    return parts


def bowsprit():
    """The bowsprit, angled up about 15 degrees from the stem head to its tip, with lashings and an iron cap; the cathead and
    the bow lantern's bracket."""
    parts = []
    p0 = np.array([0.0, -24.8, -61.0])
    d = SPRIT_TIP - p0
    n = 7
    for i in range(n):
        a = p0 + d * (i / n); b = p0 + d * ((i + 1) / n)
        w = sm(4.5, 2.8, i / (n - 1))
        parts.append(rod(f'sprit{i}', a, b + (b - a) * 0.08, w, 'mast', t2=w, axis='z', sect=30 + i))
    for k, (u, w) in enumerate(((0.36, 5.8), (0.42, 5.4), (0.9, 3.6))):
        c = p0 + d * u
        parts.append(rod(f'sprit_lash{k}', c, c + unit(d) * 2.0, w, 'rope', axis='z', thick=w, lash=True))
    parts.append(rod('sprit_cap', SPRIT_TIP - unit(d) * 1.0, SPRIT_TIP + unit(d) * 1.8, 3.4, 'iron', wear=1.2, band=True))
    # the iron bracket the bow lantern hangs from, under the bowsprit's root
    parts.append(rod('bow_arm_a', np.array([0.0, -27.0, -73.3]), np.array([0.0, -27.0, -78.2]), 1.2, 'iron', wear=1.2, arm=True))
    parts.append(rod('bow_arm_b', np.array([0.0, -26.8, -70.5]), np.array([0.0, -27.0, -75.0]), 1.4, 'iron', wear=1.2, arm=True))
    return parts


BITT_X, BITT_Z = 6.2, -64.0          # the bitts either side of the bowsprit's heel (on the bulwarks' caps)
LOOKOUT_HAND = (-7.0, -43.0, -64.0)  # where the bow lookout's right hand grips (ship_anims.py's clip): the starboard bitt's head


def bow_bitts():
    """The bow's bitts: two stout timber posts either side of the bowsprit's heel standing on the bulwarks, an iron strap and a collar
    on each, and a beam across over the sprit to hold it down. The starboard bitt's head (3.6 square, its top just under the fist) is
    what the bow lookout grips: the lookout clip puts the right hand at LOOKOUT_HAND, shoulder high and 8 ahead of the feet at
    (0, -20, -56), well clear of the body (a torso 8 wide and 4 deep over the feet). Static."""
    parts = []
    for sigma, side in ((-1, 's'), (1, 'p')):
        x = sigma * BITT_X
        parts.append(box(f'bitt_{side}', (x - 1.2, -41.0, BITT_Z - 1.2), (2.4, 17.0, 2.4), 'timber', axis='y', tone=-0.12, wear=1.3))
        parts.append(box(f'bitt_strap_{side}', (x - 1.5, -31.0, BITT_Z - 1.5), (3.0, 1.0, 3.0), 'iron', wear=1.2, band=True))
        parts.append(box(f'bitt_collar_{side}', (x - 1.5, -41.7, BITT_Z - 1.5), (3.0, 1.0, 3.0), 'iron', wear=1.2, band=True))
        parts.append(box(f'bitt_head_{side}', (x - 1.8, -44.5, BITT_Z - 1.8), (3.6, 3.0, 3.6), 'timber', axis='y', tone=0.0, wear=1.5))
        parts.append(box(f'bitt_lash_{side}', (x - 1.8, -33.0, BITT_Z - 1.8), (3.6, 5.0, 3.6), 'rope', lash=True, axis='y'))
    parts.append(box('bitt_beam', (-7.4, -32.0, BITT_Z - 1.0), (14.8, 3.2, 2.0), 'timber', axis='x', tone=-0.06, wear=1.3))
    parts.append(box('bitt_sprit_lash', (-2.7, -29.8, BITT_Z - 1.6), (5.4, 6.4, 3.2), 'rope', lash=True, axis='y'))
    return parts


# ================================================================== fittings: wheel, bell, lanterns, rudder, anchor, capstan

WHEEL_AT = (0.0, -45.0, 47.0)
RUDDER_AT = (0.0, -26.0, 74.0)
ANCHOR_AT = (-20.0, -18.0, -56.0)
LANTERN_STERN_AT = (0.0, -52.0, 77.0)
LANTERN_BOW_AT = (0.0, -27.0, -76.0)
BELL_AT = (10.0, -44.0, 38.0)
CAPSTAN_AT = (0.0, -12.0, -34.0)
ANCHOR_DROP = 56.0
ROPE_SEGS, ROPE_SEG = 7, 8.0


def wheel_part():
    """The ship's wheel: a rim of sixteen blocks, four bars through a brass hub whose ends are the eight handles, brass caps on them.
    It turns about its own z (the axle runs fore and aft)."""
    cubes = [Cu((-2.8, -2.8, -1.6), (5.6, 5.6, 3.2), 'timber', 'wheel_hub', axis='x', tone=-0.1, wear=1.0, hub=True),
             Cu((-2.2, -2.2, -2.6), (4.4, 4.4, 1.0), 'brass', 'wheel_boss', wear=1.0),
             Cu((-1.2, -1.2, 1.6), (2.4, 2.4, 0.8), 'brass', 'wheel_boss_b', wear=1.0)]
    kids = []
    R = 9.1
    for k in range(16):
        kids.append(mkpart(f'wheel_r{k}', (0, 0, 0), (0, 0, k * 22.5),
                           [Cu((-2.1, -(R + 0.9), -1.2), (4.2, 1.8, 2.4), 'timber', f'wheel_r{k}', axis='x', tone=0.04, wear=1.2, rim=True)]))
    for k in range(4):
        kids.append(mkpart(f'wheel_s{k}', (0, 0, 0), (0, 0, k * 45.0),
                           [Cu((-0.8, -13.0, -1.0), (1.6, 26.0, 2.0), 'timber', f'wheel_s{k}', axis='y', tone=0.02, wear=1.2),
                            Cu((-1.3, -15.2, -1.3), (2.6, 2.4, 2.6), 'brass', f'wheel_c{k}a', wear=1.1, cap=True),
                            Cu((-1.3, 12.8, -1.3), (2.6, 2.4, 2.6), 'brass', f'wheel_c{k}b', wear=1.1, cap=True)]))
    return mkpart('wheel', WHEEL_AT, cubes=cubes, children=kids)


def lantern_cubes(prefix):
    """An iron-framed lantern hanging from its ring: a roof, four corner posts round amber panes, a brass base."""
    c = [Cu((-0.75, -2.0, -0.75), (1.5, 2.0, 1.5), 'iron', f'{prefix}_ring', wear=1.0, band=True),
         Cu((-1.75, 0.0, -1.75), (3.5, 1.0, 3.5), 'brass', f'{prefix}_cap', wear=1.0),
         Cu((-3.0, 1.0, -3.0), (6.0, 1.0, 6.0), 'iron', f'{prefix}_roof', wear=1.0, band=True),
         Cu((-2.25, 2.0, -2.25), (4.5, 7.0, 4.5), 'lamp', f'{prefix}_glass'),
         Cu((-3.0, 9.0, -3.0), (6.0, 1.0, 6.0), 'brass', f'{prefix}_base', wear=1.0),
         Cu((-1.0, 10.0, -1.0), (2.0, 1.0, 2.0), 'iron', f'{prefix}_foot', wear=1.0, band=True)]
    for i, (x, z) in enumerate(((-2.6, -2.6), (2.1, -2.6), (-2.6, 2.1), (2.1, 2.1))):
        c.append(Cu((x - 0.25, 2.0, z - 0.25), (1.0, 7.0, 1.0), 'iron', f'{prefix}_post{i}', wear=1.0, band=True))
    return c


def fittings():
    parts = []
    # --- the wheel and its pedestal, the binnacle beside it
    parts.append(wheel_part())
    parts.append(box('wheel_post_f', (-2.2, -48.0, 42.4), (4.4, 18.0, 2.2), 'timber', axis='y', tone=-0.1, wear=1.2, pedestal=True))
    parts.append(box('wheel_post_b', (-2.2, -48.0, 49.4), (4.4, 18.0, 2.2), 'timber', axis='y', tone=-0.1, wear=1.2, pedestal=True))
    parts.append(box('wheel_axle', (-1.0, -46.0, 42.4), (2.0, 2.0, 9.2), 'iron', wear=1.0, band=True))
    parts.append(box('wheel_post_cap_f', (-2.8, -49.2, 42.0), (5.6, 1.2, 3.0), 'brass', wear=1.0))
    parts.append(box('wheel_post_cap_b', (-2.8, -49.2, 49.0), (5.6, 1.2, 3.0), 'brass', wear=1.0))
    parts.append(box('wheel_foot', (-3.6, -31.4, 41.4), (7.2, 1.4, 10.4), 'iron', wear=1.2, band=True))
    parts.append(box('binnacle', (10.5, -40.0, 47.0), (6, 10.0, 6), 'timber', axis='y', tone=-0.05, wear=1.2, binnacle=True))
    parts.append(box('binnacle_lamp', (11.25, -43.5, 47.75), (4.5, 3.5, 4.5), 'lamp'))
    parts.append(box('binnacle_cap', (10.5, -44.5, 47.0), (6, 1.0, 6), 'brass', wear=1.0))
    parts.append(box('binnacle_foot', (10.0, -31.0, 46.5), (7, 1.0, 7), 'iron', wear=1.2, band=True))
    # --- the bell, in its frame at the castle's forward rail
    parts.append(box('bell_post_a', (6.1, -46.0, 37.1), (1.8, 16.0, 1.8), 'timber', axis='y', tone=0.0, wear=1.3, rail=True))
    parts.append(box('bell_post_b', (12.1, -46.0, 37.1), (1.8, 16.0, 1.8), 'timber', axis='y', tone=0.0, wear=1.3, rail=True))
    parts.append(box('bell_beam', (5.5, -46.4, 36.9), (9, 2.0, 2.2), 'timber', axis='x', tone=0.04, wear=1.3, rail=True))
    bell = [Cu((-0.8, 0.0, -0.8), (1.6, 1.5, 1.6), 'iron', 'bell_crown', wear=1.0, band=True),
            Cu((-1.8, 1.5, -1.8), (3.6, 1.5, 3.6), 'brass', 'bell_shoulder', wear=1.4, bell=True),
            Cu((-2.5, 3.0, -2.5), (5.0, 2.5, 5.0), 'brass', 'bell_waist', wear=1.4, bell=True),
            Cu((-3.3, 5.5, -3.3), (6.5, 2.0, 6.5), 'brass', 'bell_lip', wear=1.4, bell=True)]
    clap = mkpart('bell_clapper', (0.0, 2.0, 0.0), cubes=[Cu((-0.5, 0.0, -0.5), (1.0, 6.0, 1.0), 'iron', 'bell_rod', wear=1.0, band=True),
                                                          Cu((-1.0, 5.6, -1.0), (2.0, 2.0, 2.0), 'iron', 'bell_ball', wear=1.0, band=True)])
    parts.append(mkpart('bell', BELL_AT, cubes=bell, children=[clap]))
    # --- the lanterns
    parts.append(mkpart('lantern_stern', LANTERN_STERN_AT, cubes=lantern_cubes('ls')))
    parts.append(mkpart('lantern_bow', LANTERN_BOW_AT, cubes=lantern_cubes('lb')))
    parts.append(rod('stern_lamp_post', np.array([0.0, -44.0, 74.4]), np.array([0.0, -54.4, 74.4]), 1.4, 'iron', wear=1.2, arm=True))
    parts.append(rod('stern_lamp_arm', np.array([0.0, -54.0, 73.7]), np.array([0.0, -54.0, 77.9]), 1.4, 'iron', wear=1.2, arm=True))
    parts.append(rod('stern_lamp_brace', np.array([0.0, -46.0, 74.9]), np.array([0.0, -53.4, 77.2]), 1.0, 'iron', wear=1.2, arm=True))
    # --- the rudder
    rud = [Cu((-1.3, 0.0, -0.5), (2.6, 14.0, 5.0), 'rudder', 'rudder_stock', part='stock'),
           Cu((-1.5, 14.0, -0.5), (3.0, 22.0, 11.0), 'rudder', 'rudder_blade', part='blade'),
           Cu((-1.8, -0.8, -0.9), (3.6, 1.6, 6.0), 'iron', 'rudder_head', wear=1.2, band=True)]
    for i, y in enumerate((17.0, 25.5, 33.0)):
        rud.append(Cu((-1.95, y, -1.3), (3.9, 1.6, 8.0), 'iron', f'rudder_strap{i}', wear=1.3, band=True))
    parts.append(mkpart('rudder', RUDDER_AT, cubes=rud))
    # --- the anchor: ring, shank, stock, crown, two arms with flukes (20 long, hanging plumb from the cathead's hook)
    anc = [Cu((-1.75, 0.0, -0.5), (3.5, 0.7, 1.0), 'iron', 'anc_ring_t', wear=1.3, band=True),
           Cu((-1.75, 2.8, -0.5), (3.5, 0.7, 1.0), 'iron', 'anc_ring_b', wear=1.3, band=True),
           Cu((-1.75, 0.7, -0.5), (0.7, 2.1, 1.0), 'iron', 'anc_ring_l', wear=1.3, band=True),
           Cu((1.05, 0.7, -0.5), (0.7, 2.1, 1.0), 'iron', 'anc_ring_r', wear=1.3, band=True),
           Cu((-0.75, 3.5, -0.75), (1.5, 15.0, 1.5), 'iron', 'anc_shank', wear=1.5, band=True),
           Cu((-0.6, 5.5, -5.5), (1.2, 1.2, 11.0), 'iron', 'anc_stock', wear=1.5, band=True),
           Cu((-1.75, 17.0, -1.25), (3.5, 2.5, 2.5), 'iron', 'anc_crown', wear=1.5, band=True)]
    arms = []
    for nm, sgn in (('anchor_arm_p', 1), ('anchor_arm_s', -1)):
        arms.append(mkpart(nm, (sgn * 1.2, 18.6, 0.0), (0, 0, sgn * 52.0),
                           [Cu((-0.7, -8.0, -0.7), (1.4, 8.0, 1.4), 'iron', nm, wear=1.5, band=True),
                            Cu((-0.4, -10.5, -2.0), (0.8, 3.5, 4.0), 'iron', nm + '_fluke', wear=1.5, fluke=True)]))
    parts.append(mkpart('anchor', ANCHOR_AT, cubes=anc, children=arms))
    # its rope: seven lengths, each the child of the one above, shown or hidden as the anchor pays out
    chain = None
    for i in range(ROPE_SEGS - 1, -1, -1):
        nm = 'anchor_rope' if i == 0 else f'anchor_rope{i + 1}'
        piv = ANCHOR_AT if i == 0 else (0.0, ROPE_SEG, 0.0)
        chain = mkpart(nm, piv, cubes=[Cu((-0.6, 0.0, -0.6), (1.2, ROPE_SEG, 1.2), 'rope', nm, axis='y', thick=1.2, seg=i)], children=[chain] if chain else [])
    parts.append(chain)
    # --- the cathead on the starboard bow, with its brace, sheave and hook
    parts.append(box('cathead', (-20.5, -26.0, -57.5), (12.5, 3.0, 3.0), 'timber', axis='x', tone=-0.15, wear=1.3))
    parts.append(box('cathead_sheave', (-22.5, -26.5, -58.0), (2.5, 4.0, 4.0), 'iron', wear=1.3, band=True))
    parts.append(box('cathead_hook', (-20.7, -23.0, -56.7), (1.4, 5.0, 1.4), 'iron', wear=1.3, band=True))
    parts.append(rod('cathead_brace', np.array([-11.0, -17.0, -56.0]), np.array([-19.2, -23.0, -56.0]), 2.0, 'timber', axis='z', tone=-0.15, wear=1.3))
    # --- the capstan on the main deck forward of the mast
    cap = [Cu((-4.0, -7.0, -4.0), (8, 7.0, 8), 'timber', 'cap_drum', axis='y', tone=-0.05, wear=1.3, capstan=True),
           Cu((-5.0, -9.0, -5.0), (10, 2.0, 10), 'timber', 'cap_head', axis='y', tone=0.0, wear=1.3, capstan=True),
           Cu((-12.0, -8.6, -0.9), (24, 1.8, 1.8), 'timber', 'cap_bar_a', axis='x', tone=0.04, wear=1.4),
           Cu((-0.9, -8.6, -12.0), (1.8, 1.8, 24), 'timber', 'cap_bar_b', axis='z', tone=0.04, wear=1.4),
           Cu((-1.5, -10.0, -1.5), (3, 1.0, 3), 'brass', 'cap_cap', wear=1.0),
           Cu((-4.5, -4.5, -4.5), (9, 1.0, 9), 'iron', 'cap_ring', wear=1.2, band=True)]
    cap_kids = [mkpart('capstan_b', (0, OCT_DROP, 0), (0, 45, 0), [Cu((-4.0, -7.0, -4.0), (8, 7.0, 8), 'timber', 'cap_drumb', axis='y', tone=-0.05, wear=1.3, capstan=True),
                                                             Cu((-5.0, -9.0, -5.0), (10, 2.0, 10), 'timber', 'cap_headb', axis='y', tone=0.0, wear=1.3, capstan=True)])]
    parts.append(mkpart('capstan', CAPSTAN_AT, cubes=cap, children=cap_kids))
    return parts


# ================================================================== cargo, the pin rails, the lines that follow the yard

def barrel(name, x, y, z, rot=0.0, size=1.0, tone=0.0, kind='barrel'):
    """An upright barrel standing on (x, y, z): three octagonal sections (each a square and the same turned an eighth) for its
    bilge, hoops and staves painted on by place."""
    s1, s2 = half(7.0 * size), half(8.6 * size)
    h1, h2 = half(3.0 * size), half(5.0 * size)
    kw = dict(cx=x, cy=y, cz=z, bh=2 * h1 + h2, kind=kind, tone=tone, rot=rot)

    def sect(sfx):
        return [Cu((-s1 / 2, -h1, -s1 / 2), (s1, h1, s1), 'barrel', f'{name}_lo{sfx}', **kw),
                Cu((-s2 / 2, -h1 - h2, -s2 / 2), (s2, h2, s2), 'barrel', f'{name}_mid{sfx}', **kw),
                Cu((-s1 / 2, -2 * h1 - h2, -s1 / 2), (s1, h1, s1), 'barrel', f'{name}_hi{sfx}', **kw)]
    kid = mkpart(f'{name}_b', (0, OCT_DROP, 0), (0, 45, 0), sect('b'))
    return mkpart(name, (x, y, z), (0, rot, 0), sect(''), [kid])


def crate(name, x, y, z, w, h, d, rot=0.0, tone=0.0):
    """A slatted crate standing on (x, y, z), turned by [rot]."""
    w, h, d = half(w), half(h), half(d)
    return mkpart(name, (x, y, z), (0, rot, 0), [Cu((-w / 2, -h, -d / 2), (w, h, d), 'crate', name, tone=tone, cx=x, cy=y, cz=z, rot=rot)])


def sack(name, x, y, z, rot=0.0, w=6.0, h=5.0, d=9.0):
    w, h, d = half(w), half(h), half(d)
    return mkpart(name, (x, y, z), (0, rot, 0), [Cu((-w / 2, -h, -d / 2), (w, h, d), 'sack', name, cx=x, cy=y, cz=z, rot=rot)])


def coil(name, x, y, z, r=3.3, h=2.2, rot=0.0):
    """A flaked coil of rope lying flat on (x, y, z): a square and the same turned an eighth."""
    s = half(2 * r)
    h = half(h)
    kw = dict(cx=x, cy=y, cz=z, r=r)
    kid = mkpart(f'{name}_b', (0, OCT_DROP, 0), (0, 45, 0), [Cu((-s / 2, -h, -s / 2), (s, h, s), 'coil', f'{name}_b', **kw)])
    return mkpart(name, (x, y, z), (0, rot, 0), [Cu((-s / 2, -h, -s / 2), (s, h, s), 'coil', name, **kw)], [kid])


def chest(name, x0, x1, y_base, z0, z1, h_body, h_lid, tone=0.0, brass=True):
    """A sea chest with an iron-bound lid; the lid's top is the seat."""
    parts = [box(name, (x0, y_base - h_body, z0), (x1 - x0, h_body, z1 - z0), 'chest', tone=tone, body=True, face='front'),
             box(f'{name}_lid', (x0 - 0.25, y_base - h_body - h_lid, z0 - 0.25), (x1 - x0 + 0.5, h_lid, z1 - z0 + 0.5), 'chest', tone=tone, body=False, lock=brass)]
    return parts


def cargo():
    parts = []
    # --- the sea chests either side of the hatch (their lids are the seats at y -19)
    parts += chest('chest_s', -22.0, -6.0, -12.0, 2.0, 9.0, 5.0, 2.0)
    parts += chest('chest_p', 6.0, 22.0, -12.0, 2.0, 9.0, 5.0, 2.0, tone=0.04)
    # --- the stern locker, a seat on the castle deck (top y -36)
    parts += chest('locker', -24.0, -4.0, -30.0, 59.5, 68.5, 4.5, 1.5, tone=-0.04)
    # --- barrels along the starboard rail, a water cask by the mast, crates and sacks to port, lashed rope coils
    parts.append(barrel('barrel_a', -21.0, -12.0, 15.5, 20.0))
    parts.append(barrel('barrel_b', -21.2, -12.0, 25.0, 0.0, tone=-0.05))
    parts.append(barrel('cask', -12.5, -12.0, -17.0, 8.0, size=1.15, tone=0.05, kind='cask'))
    parts.append(crate('crate_a', 20.5, -12.0, -27.0, 10.0, 8.5, 10.0, 12.0))
    parts.append(crate('crate_b', 20.0, -20.5, -27.5, 8.0, 7.0, 8.0, -9.0, tone=0.06))
    parts.append(crate('crate_c', 21.0, -12.0, -12.5, 8.0, 6.0, 9.0, -4.0, tone=-0.05))
    parts.append(sack('sack_a', 14.0, -12.0, -33.0, 25.0))
    parts.append(sack('sack_b', 23.0, -12.0, -38.0, -15.0, w=5.5, h=4.5, d=8.0))
    parts.append(coil('coil_mast', 0.0, -12.0, -2.2))
    parts.append(coil('coil_fore', -9.5, -20.0, -59.0, r=3.0))                     # by the starboard bitt, clear of the lookout's boots
    parts.append(coil('coil_castle', 15.5, -30.0, 65.0, r=3.4))
    # --- under the grating: the hold's cargo
    parts.append(barrel('hold_a', -6.5, 3.0, 14.5, 10.0))
    parts.append(barrel('hold_b', 5.5, 3.0, 14.0, 40.0, tone=-0.04))
    parts.append(barrel('hold_c', -3.0, 3.0, 24.0, 0.0, size=1.1))
    parts.append(barrel('hold_d', 7.5, 3.0, 25.5, 25.0, tone=0.03))
    parts.append(crate('hold_e', -15.0, 3.0, 20.0, 9.0, 9.0, 11.0, 5.0))
    parts.append(crate('hold_f', 15.0, 3.0, 18.0, 9.0, 8.0, 10.0, -8.0, tone=0.05))
    parts.append(crate('hold_g', 15.0, -5.0, 18.0, 7.5, 5.0, 8.0, 12.0))
    # --- the pin rails either side of the mast, with their belaying pins, and the collar at the mast's foot
    for sigma, side in ((-1, 's'), (1, 'p')):
        x = sigma * 8.5
        for i, z in enumerate((-13.5, -2.5)):
            parts.append(box(f'pinpost_{side}{i}', (x - 0.9, -22.0, z - 0.9), (1.8, 10.0, 1.8), 'timber', axis='y', tone=-0.05, wear=1.3))
        parts.append(box(f'pinrail_{side}', (x - 1.0, -23.4, -14.4), (2.0, 1.6, 13.8), 'timber', axis='z', tone=0.06, wear=1.4, rail=True))
        for i, z in enumerate((-12.5, -9.5, -6.5, -3.5)):
            parts.append(box(f'pin_{side}{i}', (x - 0.4, -25.4, z - 0.4), (0.8, 5.5, 0.8), 'iron', wear=1.2, band=True))
    parts.append(box('collar', (-4.0, -14.4, MAST_Z - 4.0), (8, 2.4, 8), 'timber', axis='y', tone=-0.1, wear=1.3, collar=True))
    parts.append(mkpart('collar_b', (MAST_X, -13.2 + OCT_DROP, MAST_Z), (0, 45, 0), [Cu((-4.0, -1.2, -4.0), (8, 2.4, 8), 'timber', 'collar_b', axis='y', tone=-0.1, wear=1.3, collar=True)]))
    # --- the foredeck's short rail across its aft edge, either side of the steps
    for sigma, side in ((-1, 's'), (1, 'p')):
        xs = [sigma * 8.0, sigma * 13.0]
        for i, x in enumerate(xs):
            parts.append(box(f'frp_{side}{i}', (x - 0.8, -26.5 + 1.5, -51.8), (1.6, 6.0, 1.6), 'timber', axis='y', tone=0.0, wear=1.3, rail=True))
        parts.append(flat_bar(f'frc_{side}', np.array([xs[0], -26.5, -51.0]), np.array([xs[1], -26.5, -51.0]), 2.4, 1.5, 'timber', axis='x', tone=0.08, wear=1.4, rail=True))
        parts.append(flat_bar(f'frm_{side}', np.array([xs[0], -23.0, -51.0]), np.array([xs[1], -23.0, -51.0]), 1.2, 1.2, 'timber', axis='x', tone=0.0, wear=1.3, rail=True))
    return parts


BRACE_FIX = {-1: np.array([-23.1, -44.0, 50.0]), 1: np.array([23.1, -44.0, 50.0])}
LIFT_FIX = {-1: np.array([-1.6, -183.0, MAST_Z - 1.0]), 1: np.array([1.6, -183.0, MAST_Z - 1.0])}
YARD_TIP, YARD_LIFT = 54.0, 50.0
LIFT_DY, LIFT_DZ = -1.2, -0.6        # where on the yard's arm (up, aft) the lift is made fast


def follow_lines():
    """Four lines the yard drags with it: a brace from each yard arm down to the castle's rail, a lift from each up to the masthead.
    Each is a rod pivoting on its fixed end and long enough for its longest reach; setupAnim turns it to the arm and stretches it."""
    parts = []
    for sigma, side in ((-1, 's'), (1, 'p')):
        tip = np.array([sigma * YARD_TIP, YARD_AT[1], YARD_AT[2]])
        parts.append(rod(f'brace_{side}', BRACE_FIX[sigma], tip, 1.0, 'rope', axis='z', thick=1.0))
        tip = np.array([sigma * YARD_LIFT, YARD_AT[1] + LIFT_DY, YARD_AT[2] + LIFT_DZ])
        parts.append(rod(f'lift_{side}', LIFT_FIX[sigma], tip, 0.9, 'rope', axis='z', thick=0.9))
    return parts


# ================================================================== the whole ship

GROUPS = []          # (label, top-level parts): the Kotlin body is cut into one helper method (or more) for each


def build():
    NAMES.clear()
    ARCS.clear()
    GROUPS.clear()
    parts = []

    def group(label, ps):
        GROUPS.append((label, list(ps)))
        parts.extend(ps)
    group('hull', hull_skin())
    group('stem', hull_extras())
    group('decks', decks() + castle_rails())
    group('mast', mast_parts() + [masthead_part()])
    group('yard', [yard_part()])
    group('rigging', rigging() + bowsprit() + bow_bitts())
    group('fittings', fittings())
    group('cargo', cargo())
    group('lines', follow_lines())
    return Model('cog', TEX_W, TEX_H, parts)


# ================================================================== posing: what CogModel.setupAnim does (the previews run this)

D2R = math.pi / 180.0
ROPE_NAMES = ['anchor_rope'] + [f'anchor_rope{i}' for i in range(2, ROPE_SEGS + 1)]
SAIL_H = float(sum(ROW_H))

# The numbers CogModel.kt carries too
BELLY_FWD, BELLY_AFT = 9.0, 2.4          # px: how far the sail's middle bellies forward when full, and back to the mast when taken aback
SIDE_BELLY = 17.0 * D2R                  # how far the side panels fold at the seams, full
RIPPLE_X, RIPPLE_Y = 0.24, 0.34          # radians: the canvas flogging, head to wind (rows nod, side panels flap)
SHIVER_X, SHIVER_Y = 0.035, 0.05         # radians: the canvas always stirring in the wind
TILT_HANG = 0.9                          # how nearly plumb a lantern, the bell and the anchor hang against the ship's tilt


class CogState:
    """What the renderer gives the model (see CogRenderState.kt), with the rest values."""

    def __init__(self, **kw):
        self.sail = 1.0; self.fill = 0.0; self.luff = 0.0; self.brace = 0.0; self.windYaw = 0.0; self.wind = 0.3
        self.wheel = 0.0; self.rudder = 0.0; self.anchor = 0.0; self.lanterns = 0.0; self.bell = -1.0
        self.time = 0.0; self.speed = 0.0; self.pitch = 0.0; self.heel = 0.0; self.bob = 0.0; self.hurtTime = 0.0; self.hurtDir = 1
        for k, v in kw.items():
            if not hasattr(self, k):
                raise AttributeError(k)
            setattr(self, k, v)


def clampf(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def yard_point(brace_deg, x, y, z):
    """A point of the yard's own frame (x along it, y down, z fore and aft) in the ship, with the yard braced [brace_deg]."""
    th = brace_deg * D2R
    c, s = math.cos(th), math.sin(th)
    return (YARD_AT[0] + x * c + z * s, YARD_AT[1] + y, YARD_AT[2] - x * s + z * c)


def pose(st, lens):
    """{part: {rot (radians), pos, scale, visible}} for state [st]; [lens] gives the lengths the lines were built to (name -> L)."""
    P = {}

    def rot(n, x=0.0, y=0.0, z=0.0):
        P.setdefault(n, {})['rot'] = (x, y, z)

    def pos(n, x, y, z):
        P.setdefault(n, {})['pos'] = (x, y, z)

    def scl(n, x=1.0, y=1.0, z=1.0):
        P.setdefault(n, {})['scale'] = (x, y, z)

    def vis(n, v):
        P.setdefault(n, {})['visible'] = bool(v)

    t = st.time
    wind = clampf(st.wind, 0.0, 1.0)
    shake = clampf(st.hurtTime / 10.0, 0.0, 1.0)
    # ---- the yard turns on the mast; the braces and the lifts follow its arms
    rot('yard', 0.0, st.brace * D2R, 0.0)
    for sigma, side in ((-1, 's'), (1, 'p')):
        for nm, fix, local in ((f'brace_{side}', BRACE_FIX[sigma], (sigma * YARD_TIP, 0.0, 0.0)),
                               (f'lift_{side}', LIFT_FIX[sigma], (sigma * YARD_LIFT, LIFT_DY, LIFT_DZ))):
            tip = yard_point(st.brace, *local)
            dx, dy, dz = tip[0] - fix[0], tip[1] - fix[1], tip[2] - fix[2]
            L = math.sqrt(dx * dx + dy * dy + dz * dz)
            rot(nm, math.asin(dz / L), 0.0, math.atan2(-dx, dy))
            scl(nm, 1.0, L / lens[nm], 1.0)
    # ---- the sail: its rows come down one after the other (or are hauled up into the furl)
    n = clampf(st.sail, 0.0, 1.0) * 5.0
    hv = [ROW_H[k] * clampf(n - k, 0.0, 1.0) for k in range(5)]
    Hv = sum(hv)
    fr = 1.0 - n / 5.0
    vis('furl', fr > 0.02)
    scl('furl', 1.0, max(fr, 0.12), max(fr, 0.12))
    f = clampf(st.fill, -1.0, 1.0)
    luff = clampf(max(st.luff, shake * 0.5), 0.0, 1.0)
    ext = Hv / SAIL_H
    deep = (-BELLY_FWD * f if f >= 0.0 else -BELLY_AFT * f) * ext        # px aft at the deepest: negative bellies forward
    hinge = [0.0] * 6
    acc = 0.0
    for k in range(6):
        s = acc / Hv if Hv > 0.0 else 0.0
        hinge[k] = deep * math.sin(math.pi * s ** 0.85)
        if k < 5:
            acc += hv[k]
    wt = t * (0.42 + 0.5 * wind)
    prev = 0.0
    acc = 0.0
    for k in range(5):
        row, left, right = f'sail{k + 1}', f'sail{k + 1}l', f'sail{k + 1}r'
        if hv[k] <= 0.3:
            vis(row, False)
            continue
        vis(row, True)
        scl(row, 1.0, hv[k] / ROW_H[k], 1.0)
        tilt = math.asin(clampf((hinge[k + 1] - hinge[k]) / hv[k], -0.7, 0.7))
        free = 0.55 + 0.45 * k / 4.0                                   # the foot flogs most
        tilt += luff * RIPPLE_X * free * math.sin(wt * 1.6 - 0.9 * k) + wind * SHIVER_X * free * math.sin(wt * 0.9 - 1.3 * k + 1.0)
        rot(row, tilt - prev, luff * 0.07 * math.sin(wt * 1.1 + 1.1 * k), 0.0)
        prev = tilt
        s_mid = (acc + hv[k] / 2.0) / Hv
        acc += hv[k]
        fold = SIDE_BELLY * f * (0.3 + 0.7 * math.sin(math.pi * s_mid))
        rot(left, 0.0, -(fold + luff * RIPPLE_Y * free * math.sin(wt * 1.9 + 1.7 * k + 0.6) + wind * SHIVER_Y * free * math.sin(wt * 1.3 + 2.1 * k)), 0.0)
        rot(right, 0.0, fold + luff * RIPPLE_Y * free * math.sin(wt * 1.9 + 1.7 * k + 2.4) + wind * SHIVER_Y * free * math.sin(wt * 1.3 + 2.1 * k + 1.7), 0.0)
    # ---- the pennant streams the way the wind blows
    ft = t * (0.55 + 0.55 * wind + 0.3 * st.speed)
    droop = (1.0 - wind) ** 1.6
    for i in range(3):
        rot(f'pennant{i + 1}', -droop * (0.18 + 0.12 * i) + 0.07 * wind * math.sin(ft * 1.3 - 1.3 * i + 0.5),
            ((st.windYaw + 180.0) * D2R if i == 0 else 0.0) + (0.10 + 0.20 * wind) * (1.0 + 0.5 * i) * math.sin(ft - 1.15 * i),
            0.10 * (0.4 + wind) * math.sin(ft * 0.8 - i))
    # ---- the helm
    rot('wheel', 0.0, 0.0, -st.wheel * D2R)
    rot('rudder', 0.0, -st.rudder * D2R, 0.0)
    # ---- what hangs: it stays plumb as she leans, and sways
    sway = 0.02 + 0.025 * wind + 0.03 * st.speed + 0.05 * shake
    kick = shake * st.hurtDir
    px, pz = st.pitch * D2R * TILT_HANG, -st.heel * D2R * TILT_HANG
    rot('lantern_stern', px + sway * math.sin(t * 0.19 + 0.4) + 0.1 * kick * math.sin(t * 1.1), 0.0, pz + 0.8 * sway * math.sin(t * 0.15 + 2.0))
    rot('lantern_bow', px + sway * math.sin(t * 0.21 + 2.5), 0.0, pz + 0.8 * sway * math.sin(t * 0.17 + 4.1) + 0.1 * kick * math.sin(t * 1.3))
    b = st.bell
    swing = 0.0
    clap = 0.0
    if b >= 0.0:
        swing = 0.5 * math.exp(-b / 20.0) * math.sin(b * 0.45)
        clap = -1.3 * 0.5 * math.exp(-b / 20.0) * math.sin(b * 0.45 - 0.8)
    rot('bell', swing + px + 0.3 * sway * math.sin(t * 0.23), 0.0, pz * 0.5)
    rot('bell_clapper', clap, 0.0, 0.0)
    # ---- the anchor and its rope, hanging from the cathead's hook, and the capstan turning as it pays out
    d = clampf(st.anchor, 0.0, 1.0) * ANCHOR_DROP
    ax = px + 0.7 * sway * math.sin(t * 0.13 + 1.0)
    az = pz + 0.7 * sway * math.sin(t * 0.11 + 3.0)
    hook = ANCHOR_AT
    pos('anchor', hook[0] - d * math.cos(ax) * math.sin(az), hook[1] + d * math.cos(ax) * math.cos(az), hook[2] + d * math.sin(ax))
    rot('anchor', ax, 0.0, az)
    rot('anchor_rope', ax, 0.0, az)
    for i, nm in enumerate(ROPE_NAMES):
        out = d - ROPE_SEG * i
        vis(nm, out > 0.01)
        scl(nm, 1.0, clampf(out / ROPE_SEG, 0.0, 1.0), 1.0)
    rot('capstan', 0.0, d / 4.0, 0.0)
    return P


# ================================================================== painting: one face at a time, by where each texel lies on the ship

class Face:
    """One cube face as it sits in the texture. Every texel is turned back into its place on the ship: [P] where its centre lies in the
    cube's part frame, [W] in the ship (the rest pose), [n] and [nw] the face's outward normal in both. [kw] are the cube's own
    parameters (see Cu), [x0 y0 w h] the face's rectangle at HD."""

    def __init__(self, scene, M, fi):
        ci, pi = int(scene.FC[fi]), int(scene.FP[fi])
        self.fi, self.part, self.cube = fi, scene.parts[pi].name, scene.cubes[ci][1]
        self.name, self.mat, self.kw, self.face = self.cube.name, self.cube.mat, self.cube.kw, scene.FF[fi]
        self.x0, self.y0, self.w, self.h = [int(v) for v in scene.FR[fi]]
        A, B, D = scene.FA[fi], scene.FB[fi], scene.FD[fi]
        s = (np.arange(self.w) + 0.5) / self.w
        t = (np.arange(self.h) + 0.5) / self.h
        self.P = A + s[None, :, None] * (B - A) + t[:, None, None] * (D - A)
        R, T = M[pi][:3, :3], M[pi][:3, 3]
        self.R, self.T = R, T
        self.W = self.P @ R.T + T
        self.n = np.asarray(scene.FN[fi], float)
        self.nw = R @ self.n
        self.o = np.array(self.cube.origin, float)
        self.sz = np.array(self.cube.size, float)
        self.rng = np.random.default_rng(seed_of(self.name, self.face))

    @property
    def X(self): return self.W[..., 0]

    @property
    def Y(self): return self.W[..., 1]

    @property
    def Z(self): return self.W[..., 2]

    def seed(self, *k):
        return seed_of(self.name, *k)


class Out:
    """What a painter hands back: colour (h, w, 3) 0..255, alpha (h, w) 0..1, glow layers {name: intensity (h, w)}."""

    def __init__(self, rgb, alpha=None, glow=None):
        self.rgb = np.asarray(rgb, float)
        self.alpha = np.ones(self.rgb.shape[:2]) if alpha is None else alpha
        self.glow = glow or {}


def long_axis(f):
    """The local axis (0, 1, 2) a cube is longest along."""
    return int(np.argmax(f.sz))


def unroll(f, ax):
    """Where a texel lies on a box's skin round its long axis [ax]: (a, c, cap): [a] along the axis (px from the cube's low end), [c] round
    the perimeter in px from the middle of the face that looks at the first cross axis' minimum (signed, running on over the edges),
    [cap] whether the face is an end of the box."""
    cross = {0: (2, 1), 1: (0, 2), 2: (0, 1)}[ax]
    pi, qi = cross
    o, sz, P, n = f.o, f.sz, f.P, f.n
    a = P[..., ax] - o[ax]
    hp = sz[pi] / 2
    pp = P[..., pi] - (o[pi] + hp)
    qq = P[..., qi] - o[qi]
    Lp = 4 * hp + 2 * sz[qi]
    if abs(n[ax]) > 0.5:
        return qq, pp, True
    if abs(n[qi]) > 0.5:
        c = pp if n[qi] < 0 else np.where(pp >= 0, 1.0, -1.0) * (Lp / 2 - np.abs(pp))
    else:
        c = hp + qq if n[pi] > 0 else -(hp + qq)
    return a, c, False


def edge_dist(f):
    """Distance (model px) from each texel to the nearest edge of its own face."""
    ex = np.minimum(np.arange(f.w) + 0.5, f.w - np.arange(f.w) - 0.5)[None, :] / HD
    ey = np.minimum(np.arange(f.h) + 0.5, f.h - np.arange(f.h) - 0.5)[:, None] / HD
    return np.minimum(ex, ey)


def shade_rgb(rgb, k):
    """Multiply colour (..., 3) by k (broadcast over the last axis)."""
    return rgb * np.asarray(k, float)[..., None]


def over(base, col, a):
    """Lay colour [col] (3,) or (..., 3) over [base] (..., 3) by coverage [a] (...)."""
    a = np.asarray(a, float)[..., None]
    return base * (1 - a) + np.asarray(col, float) * a


def tone_rgb(name, t):
    return ramp(RAMP[name], t)


def worley(x, z, cell, seed):
    """Distance to the nearest of a jittered grid of points (cell units), and a random number for that point."""
    ix, iz = np.floor(x / cell), np.floor(z / cell)
    best = np.full(np.shape(x), 9.0)
    val = np.zeros(np.shape(x))
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            jx, jz = ix + dx, iz + dz
            px = jx + hash01(jx, jz, 0, seed)
            pz = jz + hash01(jx, jz, 1, seed + 7)
            d = np.hypot(x / cell - px, z / cell - pz)
            take = d < best
            best = np.where(take, d, best)
            val = np.where(take, hash01(jx, jz, 2, seed + 13), val)
    return best, val


def stripes(v, period, width, soft=0.15):
    """1 inside periodic stripes of [width] (fraction of [period]) with soft edges."""
    ph = (v / period) % 1.0
    d = np.abs(ph - 0.5) * 2.0
    return 1.0 - sstep(width - soft, width + soft, d)


# ---- windows, the door and what is painted on the hull (all in the ship's own coordinates)

STERN_WIN = [(-10.5, -22.0, 9.0, 8.0), (10.5, -22.0, 9.0, 8.0)]         # (centre x, centre y, width, height) on the transom
SIDE_WIN = [(47.5, -25.0, 6.0, 4.6), (60.5, -25.0, 6.0, 4.6)]           # (centre z, centre y, width, height) on the castle's sides
FRONT_WIN = [(-11.0, -21.5, 6.0, 6.0), (-21.0, -21.5, 6.0, 6.0)]        # (centre x, centre y, width, height) on the castle's front wall
DOOR = dict(cx=0.0, top=-27.5, bottom=-12.0, half=4.2)
HAWSE = [(-61.5, -17.6, 1.7)]                                           # (z, y, radius), both sides
STRAKE_TONE = [0.36, 0.33, 0.20, 0.37, 0.34, 0.31, 0.37, 0.34]


def window_fx(u, v, w, h):
    """A leaded window seen from outside: [u] across it, [v] down it, measured from its middle (px). The timber frame and sill, the
    lead cames between diamond panes and the glass, as masks (0..1), with an id for each pane."""
    inside = (np.abs(u) < w / 2) & (np.abs(v) < h / 2)
    frame = (np.abs(u) < w / 2 + 0.9) & (v > -h / 2 - 0.9) & (v < h / 2 + 1.5) & ~inside
    a = 2.3
    d1 = np.abs(((u + v) / a + 0.5) % 1.0 - 0.5) * a
    d2 = np.abs(((u - v) / a + 0.5) % 1.0 - 0.5) * a
    came = (np.minimum(d1, d2) < 0.2) & inside
    mid = (np.abs(u) < 0.25) | (np.abs(v) < 0.25)
    came = came | (mid & inside)
    pane = hash01(np.floor((u + v) / a), np.floor((u - v) / a), 3, 91)
    return frame, came, inside, pane


def window_hits(f, rects, axis):
    """For the texels of [f]: (frame, came, glass, pane) summed over the windows [rects] drawn on the plane that [axis] ('stern', 'side', 'front') names."""
    X, Y, Z = f.X, f.Y, f.Z
    fr = np.zeros(X.shape, bool); ca = np.zeros(X.shape, bool); gl = np.zeros(X.shape, bool); pn = np.zeros(X.shape)
    for cx, cy, w, h in rects:
        if axis == 'side':
            u = Z - cx
        else:
            u = X - cx
        a, b, c, d = window_fx(u, Y - cy, w, h)
        fr |= a; ca |= b; gl |= c; pn = np.where(c, d, pn)
    return fr, ca, gl, pn


def seams_for(k, sigma, span=260.0):
    """Where the butt joints between the planks of a strake fall along it (arc length, px)."""
    rng = np.random.default_rng(seed_of('seams', k, sigma))
    pos = -float(rng.uniform(0, 30))
    out = []
    while pos < span:
        pos += float(rng.uniform(26, 46))
        out.append(pos)
    return np.array(out)


def oak(s, v, seed, t0, pid, grain=0.34, cell=13.0):
    """Old oak tone (0..1) at (along the grain s, across it v)."""
    g = fbm2(s + pid * 50.0, v, cell, 0.9, seed, 3)
    f = fbm2(s, v * 5.0, 3.0, 0.35, seed + 5, 2)
    return t0 + (g - 0.5) * grain + (f - 0.5) * 0.12


def knots(s, v, seed, cell=20.0):
    """A darker disc with growth rings at sparse knots: returns (darkening 0..1, ring 0..1)."""
    d, val = worley(s, v, cell, seed)
    r = d * cell
    on = (val > 0.45)
    core = np.exp(-(r / 0.85) ** 2) * on
    ring = (0.5 + 0.5 * np.sin(r * 7.0)) * (r < 2.1) * on
    return core, ring * 0.5


def nails(s, v, s_prev, rows, spacing=4.6, size=0.42):
    """A mask of nail heads on [rows] (px from the plank's top) every [spacing] along it from the plank's start."""
    ph = ((s - s_prev) % spacing) - spacing / 2
    m = np.zeros(np.shape(s), bool)
    for r in rows:
        m |= (np.abs(ph) < size) & (np.abs(v - r) < size)
    return m


def hull_colour(f, k, sigma, kind, s, v, seed, Hh):
    """The colour of a planked skin at (arc/along s, down v): returns (rgb, extra) where extra holds masks the caller overlays."""
    X, Y, Z = f.X, f.Y, f.Z
    seams = seams_for(k, sigma)
    idx = np.searchsorted(seams, s)
    pid = hash01(idx, k + 3, sigma + 2, 5)
    s_prev = np.where(idx > 0, seams[np.maximum(idx - 1, 0)], s - 20.0)
    s_next = np.where(idx < len(seams), seams[np.minimum(idx, len(seams) - 1)], s + 20.0)
    dseam = np.minimum(s - s_prev, s_next - s)
    wale = kind == 'wale'
    if wale:
        t = 0.13 + (fbm2(s + pid * 40, v, 16.0, 0.8, seed, 3) - 0.5) * 0.16 + (fbm2(s, v * 4.0, 2.5, 0.4, seed + 3, 2) - 0.5) * 0.06
        rgb = tone_rgb('tar', t)
        # tar rubbed off by fenders and ropes: bare oak showing through in scrapes
        sc = fbm2(s, v, 22.0, 0.4, seed + 11, 3)
        bare = sstep(0.70, 0.80, sc)
        rgb = over(rgb, tone_rgb('oak', 0.24 + 0.1 * sc), bare * 0.55)
        # a sheen along the lip, and the black running together at the joints
        rgb = rgb * (1 + 0.18 * sstep(Hh - 1.2, Hh, v))[..., None]
    else:
        t = oak(s, v, seed, STRAKE_TONE[min(k, 7)] + (pid - 0.5) * 0.12, pid)
        core, ring = knots(s, v + 37.0 * k, seed + 17)
        t = t - 0.22 * core + 0.05 * ring
        rgb = tone_rgb('oak', t)
        # sun-bleached and salt-grey toward the rail, wet and dark toward the water
        bleach = sstep(-6.0, -20.0, Y) * (0.45 + 0.55 * fbm(f.W, 8.0, seed + 23, 2))
        rgb = over(rgb, (108, 94, 80), 0.12 * bleach)
        rgb = rgb * (1 + 0.22 * sstep(Hh - 1.0, Hh, v) - 0.45 * (1 - sstep(0.0, 1.1, v)))[..., None]
    # the butt joints: a dark gap with the planks' ends a little lighter either side
    gap = 1.0 - sstep(0.18, 0.55, dseam)
    rgb = rgb * (1 - 0.7 * gap)[..., None]
    # nails along the lap and either side of each joint
    nl = nails(s, v, s_prev, (1.15,), 4.6) | ((dseam < 1.4) & (np.abs(((v - 0.85) % 1.65) - 0.8) < 0.38) & (dseam > 0.8)) & (Hh > 2.5)
    rgb = over(rgb, (14, 14, 20), nl * 0.85)
    rust = ((s - s_prev) % 4.6 > 2.0) & ((s - s_prev) % 4.6 < 2.6) & (v > 1.5) & (v < 3.6) & ~nl
    rgb = rgb * (1 - 0.15 * rust * fbm(f.W, 2.0, seed + 29, 1) * 1.5)[..., None]
    return rgb, dict(pid=pid, dseam=dseam)


def hull_water(f, rgb, s, v, seed, sigma):
    """The waterline: wet dark wood above it with a crust of salt, weed and slime below, barnacles on the weed."""
    Y = f.Y
    wet = sstep(-4.5, -0.2, Y)
    rgb = rgb * (1 - 0.30 * wet)[..., None]
    salt = np.exp(-((Y + 3.6) / 0.55) ** 2) * (0.35 + 0.65 * fbm2(s, Y, 6.0, 0.7, seed + 41, 2))
    rgb = over(rgb, (150, 148, 138), 0.28 * salt)
    # the weed: thickest just under the water, thinning to black slime toward the keel
    nz = fbm2(s, Y, 9.0, 1.6, seed + 43, 3)
    hang = fbm2(s * 1.0, Y, 1.4, 8.0, seed + 47, 2)                         # weed hangs in strands: noise stretched down
    cover = sstep(-0.35, 0.9, Y) * (0.55 + 0.45 * nz) * (1.0 - 0.35 * sstep(5.0, 9.0, Y))
    wt = 0.10 + 0.30 * hang + 0.16 * (nz - 0.5)
    weed = tone_rgb('weed', wt)
    slime = tone_rgb('weed', 0.04 + 0.1 * nz)
    weed = mixc(weed, slime, sstep(3.0, 8.0, Y))
    rgb = over(rgb, weed, np.clip(cover * 1.25, 0, 1) * (Y > -0.4))
    # barnacles on the weedy planking
    bd, bv = worley(s, Y, 1.6, seed + 53)
    bar = (bd * 1.6 < 0.27) & (bv > 0.62) & (Y > 0.9)
    rgb = over(rgb, (104, 102, 92), bar * 0.7)
    rgb = over(rgb, (20, 22, 22), ((bd * 1.6 > 0.3) & (bd * 1.6 < 0.42) & (bv > 0.5) & (Y > 0.9)) * 0.6)
    return rgb


def hull_details(f, rgb, kind, glow):
    """Painted on whatever the hull is: hawse holes, scuppers, the castle's windows, the door."""
    X, Y, Z = f.X, f.Y, f.Z
    nz, nx = f.nw[2], f.nw[0]
    on_side = abs(nx) > 0.5
    for hz, hy, hr in HAWSE:
        if on_side:
            d = np.hypot(Z - hz, Y - hy)
            rgb = over(rgb, (6, 6, 9), sstep(hr, hr - 0.4, d))
            rgb = over(rgb, (46, 48, 58), np.exp(-((d - hr - 0.35) / 0.35) ** 2) * 0.9)
    if on_side:
        # scuppers at the foot of the bulwark, with salt-white runs down the wale below them
        for sz_ in (-32.0, -2.0, 26.0):
            sc = (np.abs(Z - sz_) < 1.6) & (Y > -13.4) & (Y < -12.1)
            rgb = over(rgb, (5, 5, 8), sc * 0.9)
            run = (np.abs(Z - sz_) < 0.9) & (Y >= -12.1) & (Y < -12.1 + 4.5 * (0.4 + 0.6 * hash01(int(sz_), 1, 1, 3)))
            rgb = over(rgb, (122, 118, 106), run * 0.22)
    if kind in ('transom',) or (nz > 0.5 and f.P.shape and (Z > 60).any()):
        fr, ca, gl, pn = window_hits(f, STERN_WIN, 'stern')
        fr_ = fr & (nz > 0.5)
        gl_ = gl & (nz > 0.5)
        ca_ = ca & (nz > 0.5)
        rgb = over(rgb, tone_rgb('oak', 0.36 + 0.1 * fbm(f.W, 2.0, 5, 1)), fr_)
        sky = 0.3 + 0.25 * sstep(-0.3, 0.9, (X + Y * 0.8) % 7.0 / 7.0) * 0.0
        glass = tone_rgb('glassdark', 0.3 + 0.1 * pn + 0.3 * sstep(0.2, 0.9, 0.5 + 0.5 * np.sin((X - Y) * 0.7)) * 0.3)
        rgb = over(rgb, glass, gl_)
        rgb = over(rgb, (10, 10, 14), ca_)
        glow['win'] = np.maximum(glow.get('win', 0), (gl_ & ~ca_) * (0.75 + 0.25 * pn))
    if on_side and (Z > 40).any():
        fr, ca, gl, pn = window_hits(f, SIDE_WIN, 'side')
        ok = (Y < -20) & (Z > 40) & (Z < 70)
        fr, ca, gl = fr & ok, ca & ok, gl & ok
        rgb = over(rgb, tone_rgb('oak', 0.36), fr)
        rgb = over(rgb, tone_rgb('glassdark', 0.32 + 0.1 * pn), gl)
        rgb = over(rgb, (10, 10, 14), ca)
        glow['win'] = np.maximum(glow.get('win', 0), (gl & ~ca) * (0.7 + 0.3 * pn))
    if nz < -0.5 and (np.abs(Z - 37.5) < 3).all():
        fr, ca, gl, pn = window_hits(f, FRONT_WIN, 'front')
        rgb = over(rgb, tone_rgb('oak', 0.36), fr)
        rgb = over(rgb, tone_rgb('glassdark', 0.32 + 0.1 * pn), gl)
        rgb = over(rgb, (10, 10, 14), ca)
        glow['win'] = np.maximum(glow.get('win', 0), (gl & ~ca) * (0.7 + 0.3 * pn))
    return rgb


def door_paint(f, rgb, glow):
    """The master's door in the castle's front wall: vertical planks with strap hinges, a brass ring and light round its edges."""
    X, Y = f.X, f.Y
    d = DOOR
    inside = (np.abs(X - d['cx']) < d['half']) & (Y > d['top']) & (Y < d['bottom'])
    if not inside.any():
        return rgb
    u = X - d['cx']
    pl = (u + d['half']) / 2.1
    pid = np.floor(pl)
    local = (pl - pid) * 2.1
    seed = 777
    t = 0.24 + 0.1 * hash01(pid, 1, 1, seed) + (fbm2(Y, u + pid * 9, 9.0, 0.7, seed, 3) - 0.5) * 0.3
    door = tone_rgb('oak', t)
    gap = (local < 0.22) | (local > 2.1 - 0.12)
    door = over(door, (4, 4, 6), gap * 0.9)
    # strap hinges from the left jamb and iron studs
    for yc in (-24.0, -15.0):
        strap = (np.abs(Y - yc) < 0.9) & (u < 2.6) & (u > -d['half'])
        door = over(door, tone_rgb('iron', 0.2 + 0.1 * fbm(f.W, 1.5, 3, 1)), strap)
        door = over(door, (22, 22, 30), strap & (np.abs(((u + 8.4) % 1.5) - 0.75) < 0.2) * (np.abs(Y - yc) < 0.35))
    ring = np.abs(np.hypot(u - 2.6, (Y + 19.5) * 1.0) - 1.15)
    door = over(door, tone_rgb('brass', 0.5), (ring < 0.3))
    door = over(door, tone_rgb('brass', 0.6), (np.hypot(u - 2.6, Y + 21.0) < 0.6))
    rgb = np.where(inside[..., None], door, rgb)
    crack = inside & (gap | (Y > d['bottom'] - 0.7))
    glow['win'] = np.maximum(glow.get('win', 0), crack * np.where(Y > d['bottom'] - 0.7, 0.8, 0.28))
    return rgb


def paint_hull(f):
    kw = f.kw
    kind = kw['kind']
    face = f.face
    glow = {}
    sigma = int(kw.get('sigma', 0))
    k = int(kw.get('strake', 0))
    if kind in ('plank', 'wale'):
        main, inner = face == 'front', face == 'back'
        s = np.interp(f.Z, *ARCS[(k, sigma)])
        v = f.P[..., 1] - f.o[1]
        Hh = kw['h']
    elif kind == 'transom':
        main, inner = face == 'back', face == 'front'
        s = f.X + 45.0 + 0.0 * f.Z
        v = f.P[..., 1] - f.o[1]
        Hh = kw['h']
    else:                                                    # a wall: both big faces are seen
        main, inner = face in ('front', 'back'), False
        s = f.X + 55.0 + 11.0 * f.Z
        v = np.mod(f.Y + 40.0, 3.2) * 1.0
        k = 7
        Hh = 3.2
        sigma = 0
    seed = seed_of('hull', k, sigma)
    if main:
        rgb, ex = hull_colour(f, k, sigma, kind, s, v, seed, Hh)
        rgb = hull_water(f, rgb, s, v, seed, sigma)
        rgb = hull_details(f, rgb, kind, glow)
        if kind == 'wall' and abs(f.nw[2]) > 0.5 and f.Z.min() > 30:
            rgb = door_paint(f, rgb, glow)
        # a little grime in the large
        g = fbm(f.W, 14.0, seed_of('grime'), 2)
        rgb = rgb * (0.82 + 0.36 * g)[..., None]
        if kind == 'wall':
            # the wall seen from the deck: shaded under the castle deck's edge
            rgb = rgb * (1 - 0.35 * sstep(-26.0, -29.0, f.Y))[..., None]
    elif inner:
        # the inside of the planking: darker, plainer, with the frames the planks are fastened to
        sv = np.interp(f.Z, *ARCS[(k, sigma)]) if kind != 'transom' else f.X + 45.0
        t = oak(sv, v, seed + 3, 0.27, 0.5, grain=0.3)
        rgb = tone_rgb('oak', t)
        rib = stripes(f.Z + 3.1, 9.2, 0.26, 0.05) if kind != 'transom' else stripes(f.X + 3.1, 9.2, 0.26, 0.05)
        rgb = over(rgb, tone_rgb('oak', 0.36 + 0.1 * fbm(f.W, 2.0, 8, 1)), rib * 0.8)
        low = sstep(-13.0, -11.0, f.Y)
        rgb = rgb * (1 - 0.55 * low - 0.1 * (1 - sstep(0.0, 1.2, v)))[..., None]
        rgb = rgb * (1 - 0.35 * (1 - sstep(0.0, 0.7, v)))[..., None] * (0.85 + 0.3 * fbm(f.W, 12.0, 2, 2))[..., None]
    else:
        # edges and ends of planks
        t = oak(f.P[..., 0] + f.P[..., 2], f.P[..., 1], seed + 5, 0.20, 0.3, grain=0.2)
        rgb = tone_rgb('oak', t)
        under = 0.62 if face == 'bottom' else 0.5
        rgb = rgb * under
        if face in ('left', 'right'):
            rgb = rgb * (0.9 + 0.3 * stripes(f.P[..., 1] * 1.0, 0.8, 0.4, 0.2))[..., None]
        if (face == 'bottom') and kind == 'plank':
            rgb = hull_water(f, rgb, np.interp(f.Z, *ARCS[(k, sigma)]), v, seed, sigma)
    return Out(rgb, None, glow)


# ================================================================== the whole texture

GLOW_LAYERS = {'win': ((255, 176, 74), 1.0), 'lamp': ((255, 190, 92), 1.0), 'binn': ((255, 168, 70), 0.9)}
MATS = {}                                   # material -> painter(Face) -> Out; filled in below
PLAIN = {'hull': ('oak', 0.3), 'timber': ('oak', 0.35), 'deck': ('deck', 0.45), 'mast': ('pine', 0.4), 'iron': ('iron', 0.25), 'brass': ('brass', 0.5),
         'rope': ('hemp', 0.55), 'canvas': ('madder', 0.5), 'furl': ('madder', 0.55), 'pennant': ('madder', 0.4), 'barrel': ('barrel', 0.4),
         'crate': ('pine', 0.4), 'chest': ('oak', 0.3), 'lamp': ('lamp', 0.7), 'rudder': ('oak', 0.3), 'grate': ('oak', 0.2), 'coil': ('hemp', 0.5),
         'sack': ('hemp', 0.6), 'hemp_block': ('hemp', 0.4)}


def paint_plain(f):
    name, t = PLAIN.get(f.mat, ('oak', 0.4))
    n = fbm(f.W, 3.0, seed_of(f.name), 2)
    return Out(np.broadcast_to(tone_rgb(name, t + (n - 0.5) * 0.2)[..., :], f.W.shape).copy())


def paint_model(scene, only_mats=None):
    """The whole texture (H, W, 4) uint8 and the glow layers {name: (H, W) 0..1}, face by face."""
    H, W = TEX_H * HD, TEX_W * HD
    tex = np.zeros((H, W, 4), np.float64)
    layers = {k: np.zeros((H, W)) for k in GLOW_LAYERS}
    M, _ = scene.mats(None)
    for fi in range(len(scene.FP)):
        f = Face(scene, M, fi)
        if only_mats is not None and f.mat not in only_mats:
            out = paint_plain(f)
        else:
            out = MATS.get(f.mat, paint_plain)(f)
        rgb = np.clip(out.rgb, 0, 255)
        a = np.clip(out.alpha, 0, 1)
        solid = a > 0.5
        if solid.any() and not solid.all():
            rgb = np.where(solid[..., None], rgb, rgb[solid].mean(axis=0))          # what a mip level blends in at a cut edge
        y, x, h, w = f.y0, f.x0, f.h, f.w
        tex[y:y + h, x:x + w, :3] = rgb
        tex[y:y + h, x:x + w, 3] = np.where(solid, 255.0, 0.0)
        for k, v in out.glow.items():
            layers[k][y:y + h, x:x + w] = np.maximum(layers[k][y:y + h, x:x + w], np.where(solid, np.clip(v, 0, 1), 0.0))
    return np.round(tex).astype(np.uint8), layers


def glow_map(layers):
    """The glow map: each layer lit in its own colour, additive where they overlap; clear elsewhere."""
    H, W = next(iter(layers.values())).shape
    acc = np.zeros((H, W, 3))
    on = np.zeros((H, W), bool)
    for name, (col, gain) in GLOW_LAYERS.items():
        v = np.clip(layers[name] * gain, 0, 1)
        acc += v[..., None] * np.array(col, float)
        on |= v > 0.01
    out = np.zeros((H, W, 4), np.uint8)
    out[..., :3] = np.where(on[..., None], np.clip(acc, 0, 255), 0).astype(np.uint8)
    out[..., 3] = np.where(on, 255, 0)
    return out


# ---- the decks and the grating

DECK_Y = {'main': -12.0, 'fore': -20.0, 'castle': -30.0, 'hold': 3.0, 'bottom': 7.8}


def hin_a(z, y):
    return hb(z, y) - 1.3


def deck_mask(zone, X, Z):
    """Where the slab really is (the rest is cut away in its alpha): the hull's planform at its height, less the hatch."""
    if zone == 'main':
        return (np.abs(X) < hin_a(Z, -12.0)) & (Z > -51.0) & ~((np.abs(X) < 11.5) & (Z > 10.5) & (Z < 29.5))
    if zone == 'fore':
        return (np.abs(X) < hin_a(Z, -20.0)) & (Z > zfront(-20.0) + 0.9)
    if zone == 'castle':
        return (np.abs(X) < hin_a(Z, -30.0)) & (Z < 75.6) & (Z > 35.5)
    if zone == 'hold':
        return (np.abs(X) < hin_a(Z, 3.0) - 0.4)
    if zone == 'bottom':
        return (np.abs(X) < hb(Z, 8.0) - 0.4) & (Z > zfront(8.0) + 0.6) & (Z < zaft(8.0) - 0.4)
    return np.hypot(X, Z - MAST_Z) < 9.4


def paint_deck(f):
    zone = f.kw['zone']
    X, Y, Z = f.X, f.Y, f.Z
    seed = seed_of('deck', zone)
    alive = deck_mask(zone, X, Z)
    face = f.face
    if face not in ('top', 'bottom'):                               # a slab's rim is nothing: the hull's planking hides where it ends
        return Out(tone_rgb('oak', 0.2) * np.ones(X.shape + (1,)), np.zeros(X.shape))
    if zone == 'bottom':
        nz = fbm2(X, Z, 3.0, 9.0, seed + 2, 3)
        if face == 'top':                                           # the bilge: black, wet and slimy
            rgb = tone_rgb('weed', 0.03 + 0.06 * nz) * 0.7
            rgb = over(rgb, (6, 8, 10), sstep(0.5, 0.8, fbm(f.W, 6.0, seed + 5, 2)) * 0.5)
        else:                                                       # the hull's underside: weed and slime and barnacles
            rgb = tone_rgb('weed', 0.05 + 0.2 * nz)
            bd, bv = worley(X, Z, 1.7, seed + 9)
            rgb = over(rgb, (120, 118, 108), ((bd * 1.7 < 0.3) & (bv > 0.5)) * 0.7)
        return Out(rgb, alive.astype(float))
    # the planking runs fore and aft, sprung in toward the bow and the stern as the hull is
    hbc = np.maximum(hb(Z, DECK_Y.get(zone, -12.0)), 6.0)
    xs = X * (28.0 / hbc) if zone != 'top' else X
    if zone == 'top':
        ang = np.arctan2(X, Z - MAST_Z)
        xs = ang * 9.4 * 0.9
        along = np.hypot(X, Z - MAST_Z)
    else:
        along = Z
    w = 3.4
    pi = np.floor((xs + 60.0) / w)
    lx = (xs + 60.0) - pi * w
    pid = hash01(pi, 3, 3, seed)
    jnt = (along + 100.0 + pid * 31.0) % 31.0
    jid = np.floor((along + 100.0 + pid * 31.0) / 31.0)
    t = 0.48 + (pid - 0.5) * 0.10 + (hash01(pi, jid, 7, seed) - 0.5) * 0.08
    t = t + (fbm2(along, xs + pi * 5.0, 20.0, 0.8, seed + 3, 3) - 0.5) * 0.26 + (fbm2(along, xs, 3.0, 0.3, seed + 4, 2) - 0.5) * 0.10
    core, ring = knots(along, xs + 31.0 * pi, seed + 6, cell=18.0)
    t = t - 0.18 * core + 0.04 * ring
    # walked bare down the middle and round the hatch; dirt and damp in the corners against the rail
    walk = np.exp(-(X / 11.0) ** 2) * 0.07
    edge = np.clip((hin_a(Z, DECK_Y.get(zone, -12.0)) - np.abs(X)), 0.0, 40.0)
    t = t + walk - 0.14 * np.exp(-edge / 2.6)
    if zone == 'hold':
        t = t - 0.22
    rgb = tone_rgb('deck', t)
    # the caulked seams and the butt joints, the treenails in their rows over the beams
    seam = np.where((lx < 0.34) | (lx > w - 0.08), 1.0, 0.0)
    rgb = rgb * (1 - 0.72 * seam)[..., None]
    rgb = over(rgb, rgb * 1.18, np.where((lx >= 0.34) & (lx < 0.7), 0.35, 0.0))
    butt = (jnt < 0.4)
    rgb = rgb * (1 - 0.6 * butt)[..., None]
    beam = ((along + 3.0) % 9.3)
    plug = (np.abs(beam - 4.65) < 0.34) & (np.abs(lx - w / 2) < 0.34)
    rgb = over(rgb, (26, 20, 15), plug * 0.8)
    nl = ((jnt > 0.5) & (jnt < 1.2) | (jnt > 30.0)) & ((np.abs(lx - 0.9) < 0.3) | (np.abs(lx - 2.5) < 0.3))
    rgb = over(rgb, (18, 18, 24), nl * 0.8)
    # tar spills, wet patches, a few old stains
    tar = sstep(0.73, 0.80, fbm(f.W, 8.0, seed + 11, 3))
    rgb = over(rgb, (18, 14, 12), tar * 0.55)
    wet = sstep(0.55, 0.78, fbm(f.W, 14.0, seed + 13, 2))
    rgb = rgb * (1 - 0.22 * wet)[..., None]
    if face == 'bottom':
        rgb = rgb * 0.45
    if zone == 'hold':
        rgb = rgb * 0.7
    return Out(rgb, alive.astype(float))


def paint_grate(f):
    """The hatch grating: slats along the ship with iron-banded battens across, cut open between them in the alpha."""
    X, Y, Z = f.X, f.Y, f.Z
    seed = seed_of('grate')
    u = X + 11.5
    v = Z - 10.5
    border = (u < 1.5) | (u > 21.5) | (v < 1.5) | (v > 17.5)
    slat = ((u - 1.5) % 2.6) < 1.5
    batten = (np.abs(v - 5.0) < 0.9) | (np.abs(v - 9.5) < 0.9) | (np.abs(v - 14.0) < 0.9)
    solid = border | slat | batten
    t = 0.2 + (fbm2(X, Z, 1.0, 14.0, seed, 3) - 0.5) * 0.3 + 0.06 * sstep(0.0, 1.0, 1 - np.abs(((u - 1.5) % 2.6) / 1.5 - 0.5) * 2)
    rgb = tone_rgb('oak', t)
    iron = border & ((v < 1.5) | (v > 17.5) | (u < 1.5) | (u > 21.5))
    rgb = over(rgb, tone_rgb('iron', 0.24 + 0.08 * fbm(f.W, 1.5, seed + 2, 1)), (batten | iron) * 0.88)
    rivet = (batten | iron) & (np.abs(((u + v) % 3.0) - 1.5) < 0.3) & ((np.abs(v - 5.0) < 0.35) | (np.abs(v - 9.5) < 0.35) | (np.abs(v - 14.0) < 0.35) | (v < 0.75) | (v > 18.25))
    rgb = over(rgb, (10, 10, 14), rivet * 0.9)
    rgb = rgb * (0.9 + 0.2 * fbm(f.W, 3.0, seed + 5, 2))[..., None]
    if f.face in ('left', 'right', 'front', 'back'):
        return Out(tone_rgb('oak', 0.16) * 0.9, np.ones(X.shape))
    return Out(rgb, solid.astype(float))


# ---- timber, mast, rope, iron, brass, glass

def across(f, ax):
    """-1..1 across a long box's face (0 in the middle of it): for shading its edges away so a square section reads round."""
    cross = {0: (2, 1), 1: (0, 2), 2: (0, 1)}[ax]
    pi, qi = cross
    n = f.n
    if abs(n[ax]) > 0.5:
        return np.zeros(f.X.shape)
    use = qi if abs(n[pi]) > 0.5 else pi
    half_ = max(f.sz[use] / 2, 1e-6)
    return (f.P[..., use] - (f.o[use] + half_)) / half_


def paint_timber(f):
    kw = f.kw
    ax = long_axis(f)
    a, c, cap = unroll(f, ax)
    seed = f.seed()
    tone = kw.get('tone', 0.0)
    wear = kw.get('wear', 1.0)
    ed = edge_dist(f)
    t = 0.36 + tone + (fbm2(a, c, 15.0, 1.0, seed, 3) - 0.5) * 0.30 + (fbm2(a, c, 4.0, 0.35, seed + 3, 2) - 0.5) * 0.10
    if cap:
        rr = np.hypot(c, a - np.mean(a))
        t = t - 0.10 + 0.05 * np.sin(rr * 3.2 + fbm(f.P, 4.0, seed, 1) * 3)
    up = np.clip(-f.nw[1], 0.0, 1.0)
    t = t + 0.07 * up * wear - 0.08 * np.clip(f.nw[1], 0.0, 1.0)
    core, ring = knots(a, c + 20.0, seed + 7, cell=16.0)
    t = t - 0.2 * core + 0.04 * ring
    # rubbed pale at the edges, damp and dark low down
    t = t + 0.07 * wear * np.exp(-ed / 0.45) - 0.12 * sstep(-4.0, 6.0, f.Y)
    rgb = tone_rgb('oak', t)
    if kw.get('rail'):
        rgb = over(rgb, (118, 110, 98), 0.16 * up)
    if kw.get('steps') and f.face == 'top':
        rgb = rgb * (1 - 0.5 * stripes(f.Z, 2.2, 0.08, 0.04))[..., None]
    if kw.get('hub') or kw.get('rim'):
        rgb = rgb * 1.12
    sh = 1 - 0.20 * across(f, ax) ** 2
    rgb = rgb * sh[..., None] * (0.86 + 0.28 * fbm(f.W, 10.0, seed + 11, 2))[..., None]
    return Out(rgb)


def paint_mast(f):
    kw = f.kw
    ax = long_axis(f)
    a, c, cap = unroll(f, ax)
    seed = seed_of('mast', int(kw.get('sect', 0)) // 20)
    sect = int(kw.get('sect', 0))
    Y = f.Y
    t = 0.40 + (fbm2(a + sect * 11.0, c, 28.0, 0.9, seed, 3) - 0.5) * 0.36 + (fbm2(a, c, 5.0, 0.4, seed + 3, 2) - 0.5) * 0.12
    # silvered and dry toward the head, dark and tarred toward the deck
    if sect < 20:
        tarred = sstep(-36.0, -6.0, Y)
        t = t - 0.18 * tarred + 0.08 * sstep(-90.0, -180.0, Y)
    core, ring = knots(a, c + 30.0, seed + 5, cell=24.0)
    t = t - 0.2 * core + 0.04 * ring
    if cap:
        rr = np.hypot(c, a - np.mean(a))
        t = t - 0.08 + 0.05 * np.sin(rr * 2.6)
    # cracks in the grain
    cr = sstep(0.82, 0.88, fbm2(a, c, 30.0, 0.5, seed + 9, 2))
    t = t - 0.16 * cr
    rgb = tone_rgb('pine', t)
    rgb = over(rgb, (20, 14, 10), 0.35 * sstep(-30.0, -4.0, Y) * (fbm2(a, c, 6.0, 0.6, seed + 13, 2) > 0.5))
    sh = 1 - 0.28 * across(f, ax) ** 2
    rgb = rgb * sh[..., None] * (0.88 + 0.24 * fbm(f.W, 9.0, seed + 15, 2))[..., None]
    return Out(rgb)


def paint_rope(f):
    kw = f.kw
    ax = long_axis(f)
    a, c, cap = unroll(f, ax)
    seed = f.seed()
    perim = max(4 * f.sz[{0: 2, 1: 0, 2: 0}[ax]], 1e-6)
    tarred = bool(kw.get('tar'))
    lash = bool(kw.get('lash'))
    if lash:
        ph = a / 0.75 + (c / perim) * 0.0
        twist = 0.5 + 0.5 * np.sin(ph * 2 * math.pi)
        t = 0.40 + 0.22 * (twist - 0.5) + (fbm2(a, c, 2.0, 0.8, seed, 2) - 0.5) * 0.12
        rgb = tone_rgb('hemp', t)
        return Out(rgb * (0.92 + 0.1 * np.sin(ph * 2 * math.pi))[..., None])
    ph = a / 2.1 + (c / (perim * 2.0)) * 1.0
    twist = 0.5 + 0.5 * np.sin(ph * 2 * math.pi)
    fibre = hash01(np.floor(a * HD * 1.0), np.floor(c * HD * 1.0), 1, seed)
    if tarred:
        t = 0.10 + 0.07 * twist + (fibre - 0.5) * 0.05
        rgb = tone_rgb('oak', t) * 0.7
        rgb = over(rgb, (60, 56, 50), 0.12 * (fibre > 0.85))
    else:
        t = 0.43 + 0.17 * (twist - 0.5) + (fibre - 0.5) * 0.10 + (fbm(f.W, 20.0, seed + 3, 2) - 0.5) * 0.18
        rgb = tone_rgb('hemp', t)
        rgb = over(rgb, (214, 196, 156), 0.35 * (fibre > 0.9))
    if kw.get('seg') is not None:
        # the anchor's cable: stiff and wet, darker toward the sea
        rgb = rgb * 0.78
        rgb = over(rgb, (40, 52, 40), 0.35 * (fibre > 0.6) * sstep(-8.0, 12.0, f.Y))
    return Out(rgb)


def paint_hemp_block(f):
    seed = f.seed()
    t = 0.42 + (fbm(f.W, 2.0, seed, 2) - 0.5) * 0.2
    rgb = tone_rgb('hemp', t) * (0.7 + 0.5 * stripes(f.X + f.Y, 1.1, 0.5, 0.2))[..., None]
    return Out(rgb)


def paint_iron(f):
    kw = f.kw
    seed = f.seed()
    wear = kw.get('wear', 1.0)
    ed = edge_dist(f)
    t = 0.20 + (fbm(f.W, 3.5, seed, 3) - 0.5) * 0.22 + 0.10 * np.exp(-ed / 0.4)
    up = np.clip(-f.nw[1], 0, 1)
    t = t + 0.07 * up
    rgb = tone_rgb('iron', t)
    # a patina of rust where it's wet, the hammer's dimples
    rust = sstep(0.60, 0.74, fbm(f.W, 4.0, seed + 5, 3)) * wear
    rgb = over(rgb, tone_rgb('rust', 0.25 + 0.3 * fbm(f.W, 1.5, seed + 7, 1)), np.clip(rust, 0, 0.7))
    bd, bv = worley(f.X + f.Z, f.Y, 2.2, seed + 9)
    rgb = rgb * (1 - 0.18 * (bd * 2.2 < 0.5))[..., None]
    if kw.get('band'):
        rv = f.P[..., 0] + f.P[..., 2]
        rgb = over(rgb, (6, 6, 9), ((np.abs(((rv + f.P[..., 1]) % 3.2) - 1.6) < 0.3) & (ed > 0.5) & (ed < 1.6) & (min(f.sz) > 0.9)) * 0.7)
    if kw.get('fluke') or kw.get('plate'):
        rgb = rgb * 0.92
    return Out(rgb)


def paint_brass(f):
    kw = f.kw
    seed = f.seed()
    ed = edge_dist(f)
    t = 0.46 + (fbm(f.W, 2.5, seed, 3) - 0.5) * 0.30 + 0.16 * np.exp(-ed / 0.45) - 0.0 * f.Y
    t = t + 0.08 * np.clip(-f.nw[1], 0, 1)
    rgb = tone_rgb('brass', t)
    # worn dull in the grime and green where the damp sits
    dull = sstep(0.62, 0.76, fbm(f.W, 3.0, seed + 3, 3))
    rgb = over(rgb, tone_rgb('brass', 0.22), dull * 0.5)
    rgb = over(rgb, (58, 92, 76), sstep(0.7, 0.85, fbm(f.W, 2.2, seed + 5, 2)) * 0.45 * (ed < 0.8))
    if kw.get('bell'):
        # the bell's cast bands and its lip
        rgb = rgb * (1 - 0.3 * stripes(f.Y, 1.6, 0.12, 0.06) - 0.12 * np.exp(-(((f.Y + 36.5) * 2.0) ** 2) * 0.01))[..., None]
    return Out(rgb)


def flame_mask(u, v, w, h):
    """A lamp flame: a teardrop in the middle of a pane ([u] across, [v] down from the pane's top, both in px)."""
    x = (u - w / 2) / (w * 0.22)
    y = (v - h * 0.55) / (h * 0.30)
    r = np.sqrt(x * x + np.where(y < 0, (y / 1.5) ** 2, y * y))
    return np.clip(1 - r, 0, 1)


def paint_lamp(f):
    """Lantern glass and the binnacle's lamp: amber panes with a flame in each; what burns goes to the glow layer too."""
    seed = f.seed()
    face = f.face
    layer = 'binn' if f.name == 'binnacle_lamp' else 'lamp'
    if face in ('top', 'bottom'):
        rgb = np.broadcast_to(np.array([46, 28, 14.0]), f.W.shape).copy()
        return Out(rgb, None, {layer: np.full(f.X.shape, 0.5)})
    w = f.sz[0] if face in ('front', 'back') else f.sz[2]
    h = f.sz[1]
    u = (np.arange(f.w) + 0.5)[None, :] / HD * np.ones((f.h, 1))
    v = (np.arange(f.h) + 0.5)[:, None] / HD * np.ones((1, f.w))
    fl = flame_mask(u, v, w, h)
    pane = 0.5 + 0.5 * np.clip(1 - np.hypot((u - w / 2) / (w * 0.7), (v - h * 0.55) / (h * 0.7)), 0, 1)
    rgb = tone_rgb('lamp', 0.30 + 0.38 * pane + 0.30 * fl)
    ed = edge_dist(f)
    rgb = rgb * (0.55 + 0.45 * sstep(0.0, 0.8, ed))[..., None]
    g = np.clip(0.35 * pane + 1.0 * fl, 0, 1) * sstep(0.0, 0.5, ed)
    return Out(rgb, None, {layer: g})


def paint_rudder(f):
    X, Y, Z = f.X, f.Y, f.Z
    seed = f.seed()
    part = f.kw.get('part')
    side = f.face in ('left', 'right')
    if side:
        # vertical planks
        pl = np.floor((Z + 40.0) / 2.6)
        lx = (Z + 40.0) - pl * 2.6
        t = 0.30 + (hash01(pl, 1, 1, seed) - 0.5) * 0.1 + (fbm2(Y, Z, 14.0, 0.8, seed, 3) - 0.5) * 0.3
        rgb = tone_rgb('oak', t)
        rgb = rgb * (1 - 0.6 * (lx < 0.3))[..., None]
    else:
        t = 0.24 + (fbm2(X + Z, Y, 2.0, 12.0, seed, 3) - 0.5) * 0.3
        rgb = tone_rgb('oak', t) * 0.8
    rgb = hull_water_simple(f, rgb, seed)
    sh = 1 - 0.12 * np.exp(-edge_dist(f) / 0.5)
    return Out(rgb * sh[..., None])


def hull_water_simple(f, rgb, seed):
    """The waterline for anything hung in the sea (the rudder, the anchor's cable): damp above it, weed below."""
    Y = f.Y
    rgb = rgb * (1 - 0.28 * sstep(-4.5, -0.2, Y))[..., None]
    nz = fbm2(f.X + f.Z, Y, 6.0, 1.6, seed + 43, 3)
    cover = sstep(-0.35, 0.9, Y) * (0.5 + 0.5 * nz)
    weed = tone_rgb('weed', 0.12 + 0.35 * fbm2(f.X + f.Z, Y, 1.2, 7.0, seed + 47, 2))
    rgb = over(rgb, weed, np.clip(cover * 1.2, 0, 1) * (Y > -0.4))
    return rgb


# ---- cargo: barrels, crates, chests, coils, sacks

def paint_barrel(f):
    kw = f.kw
    X, Y, Z = f.X, f.Y, f.Z
    cx, cy, cz, bh = kw['cx'], kw['cy'], kw['cz'], kw['bh']
    seed = seed_of('barrel', f.name.split('_')[0], f.name.split('_')[1] if '_' in f.name else '')
    dx, dz = X - cx, Z - cz
    r = np.hypot(dx, dz)
    th = np.arctan2(dx, dz) + math.radians(kw.get('rot', 0.0))
    hh = cy - Y                                        # height up from its foot
    n_st = 14
    u = th / (2 * math.pi) * n_st
    sid = np.floor(u)
    lu = u - sid
    cask = kw.get('kind') == 'cask'
    t = 0.40 + kw.get('tone', 0.0) + (hash01(sid % n_st, 5, 5, seed) - 0.5) * 0.12 + (fbm2(hh, u, 7.0, 1.4, seed, 3) - 0.5) * 0.24
    if cask:
        t += 0.08
    core, ring = knots(hh * 1.0, u * 2.0 + sid, seed + 3, cell=9.0)
    t = t - 0.15 * core
    rgb = tone_rgb('barrel', t)
    gap = (lu < 0.07) | (lu > 0.93)
    rgb = rgb * (1 - 0.55 * gap)[..., None]
    # the hoops: iron, near the ends and either side of the bilge, with a dribble of rust
    bands = (0.10, 0.30, 0.70, 0.90)
    hoop = np.zeros(X.shape, bool)
    for b in bands:
        hoop |= np.abs(hh - b * bh) < 0.55
    hoop_rgb = tone_rgb('iron', 0.18 + 0.08 * fbm(f.W, 2.0, seed + 5, 2))
    hoop_rgb = over(hoop_rgb, tone_rgb('rust', 0.3), 0.35 * sstep(0.55, 0.75, fbm(f.W, 3.0, seed + 6, 2)))
    rgb = np.where(hoop[..., None], hoop_rgb, rgb)
    streak = hash01(sid, 3, 3, seed + 7) > 0.6
    rgb = rgb * (1 - 0.18 * streak * (hh < 0.65 * bh) * ((hh % 11.0) > 0.0))[..., None]
    # shaded toward the foot, and where it stands in the hold
    rgb = rgb * (0.78 + 0.22 * sstep(0.0, 0.3 * bh, hh))[..., None]
    alive = np.ones(X.shape)
    if f.face == 'top':
        lid = hh > bh - 0.3
        R = 3.55 if r.max() < 5.2 and (f.sz[0] < 8.0) else 4.5
        ux = dx * math.cos(math.radians(kw.get('rot', 0.0))) - dz * math.sin(math.radians(kw.get('rot', 0.0)))
        if lid.any():
            pl = np.floor((ux + 10.0) / 2.3)
            lx = (ux + 10.0) - pl * 2.3
            tl = 0.44 + (hash01(pl, 4, 4, seed) - 0.5) * 0.1 + (fbm2(Z, X, 8.0, 1.0, seed + 9, 2) - 0.5) * 0.2
            lidc = tone_rgb('barrel', tl) * (1 - 0.55 * (lx < 0.25))[..., None]
            lidc = over(lidc, tone_rgb('iron', 0.2), (r > 3.0) * 0.6 * (r < 3.55))
            bung = np.hypot(dx - 1.4, dz + 1.0) < 0.55
            lidc = over(lidc, (14, 10, 8), bung * 0.9)
            rgb = np.where(lid[..., None], lidc, rgb)
        alive = (r < R).astype(float)
    if f.face == 'bottom':
        rgb = rgb * 0.5
    if cy > 0.0:
        rgb = rgb * 0.55
    return Out(rgb, alive)


def paint_crate(f):
    kw = f.kw
    seed = seed_of('crate', f.name)
    face = f.face
    w, h = f.w / HD, f.h / HD
    u = (np.arange(f.w) + 0.5)[None, :] / HD * np.ones((f.h, 1))
    v = (np.arange(f.h) + 0.5)[:, None] / HD * np.ones((1, f.w))
    tone = kw.get('tone', 0.0)
    if face in ('top', 'bottom'):
        bd = 2.0
        pl = np.floor(v / bd)
        ly = v - pl * bd
        t = 0.40 + tone + (hash01(pl, 2, 2, seed) - 0.5) * 0.10 + (fbm2(u, v, 9.0, 1.2, seed, 3) - 0.5) * 0.22
        rgb = tone_rgb('pine', t) * (1 - 0.6 * (ly < 0.25))[..., None]
        edge = (u < 1.2) | (u > w - 1.2)
        rgb = rgb * (1 + 0.1 * edge)[..., None]
        if face == 'bottom':
            rgb = rgb * 0.6
        rgb = over(rgb, (20, 20, 26), (((u < 1.2) | (u > w - 1.2)) & (np.abs(ly - 1.0) < 0.3) & (np.abs(((u % 1.2) - 0.6)) < 0.3)) * 0.8)
        return Out(rgb * (0.55 if kw.get('cy', 0) > 0 else 1.0))
    bd = 2.0
    pl = np.floor(v / bd)
    ly = v - pl * bd
    t = 0.38 + tone + (hash01(pl, int(w), 2, seed) - 0.5) * 0.12 + (fbm2(u, v, 11.0, 1.2, seed + 1, 3) - 0.5) * 0.24
    core, ring = knots(u, v + 30.0, seed + 2, cell=10.0)
    t = t - 0.16 * core
    rgb = tone_rgb('pine', t)
    rgb = rgb * (1 - 0.6 * (ly < 0.28))[..., None]
    # corner posts and the battens top and bottom
    post = (u < 1.3) | (u > w - 1.3)
    batten = (v < 1.3) | (v > h - 1.3)
    rgb = over(rgb, tone_rgb('pine', t + 0.05) * 0.85, (post | batten) * 0.85)
    rgb = rgb * (1 - 0.5 * ((np.abs(u - 1.3) < 0.18) & (v > 1.2) & (v < h - 1.2)) - 0.5 * ((np.abs(u - (w - 1.3)) < 0.18) & (v > 1.2) & (v < h - 1.2)))[..., None]
    # the diagonal brace
    d = np.abs((u - 1.3) / max(w - 2.6, 1.0) - (1 - (v - 1.3) / max(h - 2.6, 1.0))) * max(w, h)
    brace = (d < 0.95) & (u > 1.0) & (u < w - 1.0) & (v > 1.0) & (v < h - 1.0)
    rgb = over(rgb, tone_rgb('pine', t + 0.04) * 0.9, brace * 0.9)
    rgb = rgb * (1 - 0.5 * ((np.abs(d - 0.95) < 0.15) & (u > 1.0) & (u < w - 1.0) & (v > 1.0) & (v < h - 1.0)))[..., None]
    # nails, a stencilled mark
    nl = (((u < 0.9) | (u > w - 0.9) | ((np.abs(d) < 0.3) & (np.abs(((u + v) % 3.0) - 1.5) < 0.3))) & (np.abs(((v - 0.65) % 2.0) - 1.0) < 0.28)) & (u % 1.0 < 0.9)
    rgb = over(rgb, (16, 16, 22), nl * 0.45)
    if hash01(int(seed) % 97, 1, 1, 3) > 0.3 and face == 'front':
        mk = (np.abs(u - w / 2) / 2.2 + np.abs(v - h / 2) / 2.2) < 1.0
        mk2 = (np.abs(u - w / 2) / 1.2 + np.abs(v - h / 2) / 1.2) < 1.0
        rgb = over(rgb, (176, 150, 100), (mk & ~mk2) * 0.45)
    if kw.get('cy', 0) > 0:
        rgb = rgb * 0.55
    return Out(rgb)


def paint_chest(f):
    kw = f.kw
    seed = seed_of('chest', f.name)
    face = f.face
    body = kw.get('body', True)
    u = (np.arange(f.w) + 0.5)[None, :] / HD * np.ones((f.h, 1))
    v = (np.arange(f.h) + 0.5)[:, None] / HD * np.ones((1, f.w))
    W, Hh = f.w / HD, f.h / HD
    tone = kw.get('tone', 0.0)
    if face in ('front', 'back', 'top', 'bottom'):
        xl = f.P[..., 0] - f.o[0]                         # along the chest, from its left end
        L = f.sz[0]
        along, down = xl, v
        t = 0.30 + tone + (fbm2(xl, v + f.P[..., 2], 13.0, 1.0, seed, 3) - 0.5) * 0.28
        plank = 2.2 if face in ('front', 'back') else 2.0
        coord = v if face in ('front', 'back') else (f.P[..., 2] - f.o[2])
        pl = np.floor(coord / plank)
        ly = coord - pl * plank
        t = t + (hash01(pl, 9, 9, seed) - 0.5) * 0.08
        rgb = tone_rgb('oak', t) * (1 - 0.55 * (ly < 0.25))[..., None]
        if face == 'top':
            rgb = rgb * (1 + 0.20 * np.exp(-0.0 * v))[..., None]
            wear = fbm(f.W, 5.0, seed + 3, 2)
            rgb = over(rgb, tone_rgb('oak', 0.5), sstep(0.5, 0.8, wear) * 0.35)
        straps = [1.7, L / 2, L - 1.7]
        sm = np.zeros(xl.shape, bool)
        for sx in straps:
            sm |= np.abs(xl - sx) < 0.85
        if face != 'bottom':
            rgb = over(rgb, tone_rgb('iron', 0.2 + 0.08 * fbm(f.W, 1.6, seed + 5, 1)), sm * 0.9)
            rv = (np.abs(((v + f.P[..., 2]) % 2.4) - 1.2) < 0.25) & sm
            rgb = over(rgb, (10, 10, 14), rv * 0.7)
        if face == 'front' and not body:
            hasp = (np.abs(xl - L / 2) < 0.7)
            rgb = over(rgb, tone_rgb('iron', 0.26), hasp * 0.9)
        if face == 'front' and body:
            plate = (np.abs(xl - L / 2) < 1.4) & (v < 3.4)
            rgb = over(rgb, tone_rgb('brass', 0.5 + 0.1 * fbm(f.W, 1.5, seed + 7, 1)), plate * 0.95)
            rgb = over(rgb, (14, 10, 6), ((np.abs(xl - L / 2) < 0.28) & (v > 1.2) & (v < 2.5)) * 0.95)
    else:
        t = 0.24 + tone + (fbm2(f.P[..., 2], v, 6.0, 1.0, seed + 2, 2) - 0.5) * 0.2
        rgb = tone_rgb('oak', t)
        rgb = over(rgb, tone_rgb('iron', 0.22), (np.abs(f.P[..., 2] - (f.o[2] + f.sz[2] / 2)) < 0.9) * 0.9)
    sh = 1 - 0.3 * np.exp(-edge_dist(f) / 0.4)
    rgb = rgb * sh[..., None] * (0.85 + 0.3 * fbm(f.W, 9.0, seed + 11, 2))[..., None]
    return Out(rgb)


def paint_coil(f):
    kw = f.kw
    seed = seed_of('coil', f.name)
    X, Y, Z = f.X, f.Y, f.Z
    r = np.hypot(X - kw['cx'], Z - kw['cz'])
    R = kw['r']
    if f.face == 'top':
        rings = 0.5 + 0.5 * np.sin(r * 2 * math.pi / 0.95 + fbm(f.W, 2.0, seed, 1) * 1.5)
        t = 0.46 + 0.20 * (rings - 0.5) + (fbm(f.W, 1.2, seed + 1, 2) - 0.5) * 0.16
        rgb = tone_rgb('hemp', t)
        rgb = over(rgb, (10, 8, 6), (r < 0.55) * 0.9)
        rgb = over(rgb, (210, 192, 150), 0.25 * (hash01(np.floor(X * HD), np.floor(Z * HD), 1, seed) > 0.88))
        return Out(rgb, (r < R + 0.25).astype(float))
    turns = 0.5 + 0.5 * np.sin((Y * 2 * math.pi / 0.9) + (X + Z) * 0.7)
    t = 0.44 + 0.2 * (turns - 0.5) + (fbm(f.W, 1.5, seed + 3, 2) - 0.5) * 0.14
    rgb = tone_rgb('hemp', t) * (0.8 if f.face == 'bottom' else 1.0)
    return Out(rgb)


def paint_sack(f):
    seed = seed_of('sack', f.name)
    X, Y, Z = f.X, f.Y, f.Z
    top = f.kw['cy'] - f.sz[1]
    t = 0.58 + (fbm(f.W, 2.2, seed, 3) - 0.5) * 0.30
    fold = 0.5 + 0.5 * np.sin((X * 0.9 + Z * 0.5) * 2.0 + fbm(f.W, 3.0, seed + 2, 1) * 4)
    t = t + 0.10 * (fold - 0.5)
    rgb = tone_rgb('hemp', t) * np.array([0.86, 0.80, 0.70])
    chk = (np.floor(X * HD) + np.floor(Y * HD) + np.floor(Z * HD)) % 2
    rgb = rgb * (1 - 0.035 * chk)[..., None]
    rgb = over(rgb, tone_rgb('hemp', 0.3), (np.abs(Y - (top + 1.1)) < 0.45) * 0.9)         # the neck tied with cord
    rgb = rgb * (0.78 + 0.22 * sstep(0.0, 2.0, f.kw['cy'] - Y))[..., None]                   # darker toward the foot
    rgb = rgb * (1 - 0.3 * sstep(0.9, 0.0, Y - top))[..., None]                              # pinched at the neck
    sh = 1 - 0.25 * np.exp(-edge_dist(f) / 0.5)
    return Out(rgb * sh[..., None])


# ---- the sail, the furled sail and the pennant

SAIL_CREST = (0.0, -102.0)                                   # where the Guild Mark crest sits on the sail (model px)
SAIL_PATCHES = [(-32.0, -122.0, 7.0, 5.0, 0.07), (24.0, -90.0, 6.0, 6.0, -0.05), (-12.0, -73.0, 8.0, 5.0, 0.04), (35.0, -140.0, 6.0, 4.0, 0.05)]
SAIL_TEARS = [(-33.0, -89.0, -29.0, -79.0, 0.8), (27.0, -128.0, 30.5, -121.5, 0.6), (-7.0, -70.0, -3.0, -62.0, 1.1), (38.0, -73.0, 44.5, -65.5, 1.3),
              (-41.0, -104.0, -39.0, -99.0, 0.5), (14.0, -112.0, 16.0, -109.0, 0.4)]
REEF_BANDS = [-140.0, -127.0]
CLOTH = 12.5


def leech(Y):
    """The sail's side edge at height Y: each row's own half-width, drawn in a touch toward its foot."""
    out = np.full(np.shape(Y), SAIL_HALF[-1])
    y0 = SAIL_TOP
    for r in range(SAIL_ROWS):
        y1 = y0 + ROW_H[r]
        frac = np.clip((Y - y0) / ROW_H[r], 0.0, 1.0)
        out = np.where((Y >= y0) & (Y < y1 + 1e-6), SAIL_HALF[r] - 0.5 * frac, out)
        y0 = y1
    return out


def crest_fx(u, v):
    """The Guild Mark crest, drawn from nothing but its idea: a heater shield with a double gold rim, a deep field, and in it a lozenge
    with a pearl at its heart. [u], [v] are px from its middle (v down). Returns (gold coverage, field coverage, pearl, facets)."""
    hw = np.where(v <= 2.0, 15.0, 15.0 * np.cos(np.clip((v - 2.0) / 17.0, 0.0, 1.0) * math.pi / 2) ** 0.8)
    inside = (np.abs(u) < hw) & (v > -17.0) & (v < 19.0)
    slope = 1.0 / np.sqrt(1.0 + np.where(v > 2.0, (15.0 * 0.8 * np.sin(np.clip((v - 2.0) / 17.0, 0, 1) * math.pi / 2) * (math.pi / 2 / 17.0)) ** 2, 0.0))
    d_edge = np.minimum((hw - np.abs(u)) * slope, v + 17.0)
    rim = inside & (d_edge < 1.7)
    line = inside & (d_edge > 2.5) & (d_edge < 3.0)
    dm = np.abs(u) / 9.0 + np.abs(v + 1.0) / 12.0
    lozenge = inside & (dm < 1.0)
    facets = lozenge & ((np.abs(np.abs(u) / 9.0 - np.abs(v + 1.0) / 12.0) < 0.05) | (dm > 0.9))
    pearl = np.hypot(u, (v + 1.0) * 0.8) < 1.8
    inner = lozenge & (dm < 0.55)
    corner = inside & (np.abs(u) > 8.6) & (v < -11.0) & ((np.abs(u) - 8.6) + (-11.0 - v) < 3.2) & (d_edge > 3.2)
    gold = rim | line | (lozenge & ~inner) | corner
    return gold.astype(float), (inside & ~gold).astype(float), pearl.astype(float), facets.astype(float), inner.astype(float)


def paint_canvas(f):
    kw = f.kw
    X, Y = f.X, f.Y
    seed = seed_of('sail')
    face = f.face
    row = int(kw['row'])
    if face not in ('front', 'back'):
        return Out(tone_rgb('hemp', 0.36 + 0.1 * fbm(f.W, 1.5, seed + 31, 1)) * np.ones(X.shape + (1,)), np.ones(X.shape))
    lx = leech(Y) - np.abs(X)                       # in from the leech
    ty = Y - SAIL_TOP                               # down from the head
    by = SAIL_TOP + SAIL_H - Y                      # up from the foot
    alive = np.ones(X.shape)
    # --- the cloth: eight widths sewn together, each a little its own colour, woven
    cu = (X + 50.0) / CLOTH
    ci = np.floor(cu)
    cl = (cu - ci) * CLOTH
    t = 0.50 + (hash01(ci, 1, 1, seed) - 0.5) * 0.07 + (fbm(f.W, 20.0, seed + 1, 3) - 0.5) * 0.10
    slub = fbm2(X, Y, 0.7, 7.0, seed + 2, 2) - 0.5
    cross = fbm2(X, Y, 9.0, 0.6, seed + 3, 2) - 0.5
    t = t + slub * 0.07 + cross * 0.05
    ix = np.floor(X * HD).astype(int)
    iy = np.floor(Y * HD).astype(int)
    t = t + 0.022 * (((ix + iy) % 2) - 0.5) * 2
    rgb = tone_rgb('madder', t)
    # --- the seams between the cloths: a dark line, the overlap's ridge and two rows of stitches
    seam_line = np.minimum(cl, CLOTH - cl)
    rgb = rgb * (1 - 0.45 * (seam_line < 0.22))[..., None]
    rgb = rgb * (1 + 0.07 * ((cl > 0.22) & (cl < 1.1)))[..., None]
    st = (np.abs(cl - 0.65) < 0.14) | (np.abs(cl - 1.35) < 0.14)
    dash = (np.abs(((Y * 1.0) % 1.7) - 0.85) < 0.6)
    rgb = rgb * (1 - 0.28 * (st & dash))[..., None]
    # --- the hem, its stitching, the bolt-rope round the edge
    hem = (lx < 2.5) | (ty < 2.9) | (by < 2.9)
    rgb = rgb * (1 + 0.06 * hem)[..., None]
    edge_in = np.minimum(np.minimum(lx, ty), by)
    rgb = rgb * (1 - 0.30 * ((np.abs(edge_in - 2.5) < 0.2) & (np.abs(((X + Y) % 1.5) - 0.75) < 0.5)))[..., None]
    rope = edge_in < 0.95
    run = np.where(np.minimum(ty, by) < 0.95, X, Y)
    tw = 0.5 + 0.5 * np.sin(run / 1.25 * 2 * math.pi)
    rope_rgb = tone_rgb('hemp', 0.36 + 0.14 * tw + (fbm(f.W, 2.0, seed + 5, 1) - 0.5) * 0.12)
    rgb = np.where(rope[..., None], rope_rgb, rgb)
    rgb = rgb * (1 - 0.3 * ((edge_in > 0.95) & (edge_in < 1.4)))[..., None]
    # the roband ties along the head where it's lashed to the yard
    if row == 0:
        tie = (np.abs(((X + 2.0) % 4.4) - 2.2) < 0.35) & (ty > 0.0) & (ty < 4.2)
        rgb = over(rgb, tone_rgb('hemp', 0.38), tie * 0.8)
        rgb = over(rgb, (20, 14, 10), ((np.abs(((X + 2.0) % 4.4) - 2.2) < 0.55) & (np.abs(ty - 1.6) < 0.5)) * 0.35 * (1 - tie))
    # --- reef bands and their points
    for yb in REEF_BANDS:
        band = np.abs(Y - yb) < 1.45
        inb = lx > 0.95
        rgb = rgb * (1 + 0.05 * (band & inb))[..., None]
        rgb = rgb * (1 - 0.32 * ((np.abs(np.abs(Y - yb) - 1.45) < 0.17) & inb))[..., None]
        pj = hash01(np.floor((X + 1.0) / 4.5), 9, 9, seed)                         # a few points missing, the rest of uneven length
        pt = (np.abs(((X + 1.0) % 4.5) - 2.25) < 0.20) & (Y > yb + 1.2) & (Y < yb + 3.2 + 1.2 * pj) & inb & (pj > 0.12)
        rgb = over(rgb, tone_rgb('hemp', 0.36 + 0.1 * pj), pt * 0.85)
        rgb = over(rgb, (30, 22, 14), ((np.abs(((X + 1.0) % 4.5) - 2.25) < 0.5) & (np.abs(Y - (yb + 4.4)) < 0.5) & inb) * 0.8)
    # --- weather: rain streaks from the yard, mildew, damp and a pale tide-mark of salt at the foot
    streak = sstep(0.60, 0.85, fbm2(X, Y, 0.9, 38.0, seed + 7, 3))
    rgb = rgb * (1 - 0.28 * streak * sstep(-70.0, -150.0, Y) * 1.0)[..., None]
    mil = sstep(0.60, 0.78, fbm(f.W, 5.0, seed + 8, 3)) * sstep(-120.0, -70.0, Y)
    rgb = over(rgb, (52, 54, 40), 0.30 * mil)
    stain = sstep(0.55, 0.75, fbm(f.W, 14.0, seed + 9, 3))
    rgb = rgb * (1 - 0.22 * stain)[..., None]
    wave = 0.8 * np.sin(X * 0.21) + 0.6 * np.sin(X * 0.57 + 1.0)
    salt = sstep(-77.0 + wave, -73.0 + wave, Y) * (0.5 + 0.5 * fbm2(X, Y, 3.0, 1.2, seed + 10, 2))
    rgb = over(rgb, (176, 150, 128), 0.20 * salt)
    # --- patches, sewn on
    for (px, py, pw, ph, pt_) in SAIL_PATCHES:
        inside = (X > px) & (X < px + pw) & (Y > py) & (Y < py + ph)
        pc = tone_rgb('madder', 0.50 + pt_ + (hash01(int(px), int(py), 3, 4) - 0.5) * 0.04 + 0.0 * X)
        pc = pc * (1 + 0.04 * (((ix + iy) % 2) - 0.5) * 2)[..., None]
        rgb = np.where(inside[..., None], pc, rgb)
        pe = np.minimum(np.minimum(X - px, px + pw - X), np.minimum(Y - py, py + ph - Y))
        rgb = rgb * (1 - 0.35 * (inside & (np.abs(pe - 0.55) < 0.15) & (np.abs(((X + Y) % 1.2) - 0.6) < 0.4)) - 0.25 * (inside & (pe < 0.2)))[..., None]
    # --- the crest, in faded gold, flaking, with the seam showing through it
    cx, cy = SAIL_CREST
    wob = (fbm(f.W, 3.0, seed + 12, 2) - 0.5) * 0.7
    gold, field, pearl, facets, inner = crest_fx(X - cx + wob, Y - cy + wob)
    flake = sstep(0.52, 0.72, fbm(f.W, 2.0, seed + 13, 3))
    fade = 1.0 - 0.30 * flake - 0.12 * stain
    if face == 'back':
        fade = fade * 0.62
    gcol = ramp(RAMP['gold'], 0.40 + 0.20 * (fbm(f.W, 2.5, seed + 14, 2)) + 0.10 * pearl)
    gcol = gcol * (1 - 0.35 * facets * (1 - pearl))[..., None] * (1 - 0.22 * inner)[..., None]
    gold = np.clip(gold, 0, 1)
    rgb = over(rgb, tone_rgb('madder', 0.30 + 0.06 * fbm(f.W, 4.0, seed + 15, 2)), field * 0.55 * (0.55 if face == 'back' else 1.0))
    rgb = over(rgb, gcol * np.where(seam_line < 0.22, 0.85, 1.0)[..., None], gold * fade)
    rgb = over(rgb, (236, 222, 186), pearl * 0.9 * fade)
    # --- tears and the ragged foot, cut through the canvas
    for (x0, y0, x1, y1, tw_) in SAIL_TEARS:
        d, tt = seg_dist(X, Y, (x0, y0), (x1, y1))
        wd = tw_ * np.sin(np.pi * tt) ** 0.7 + 0.02
        hole = d < wd
        rim = (d >= wd) & (d < wd + 0.6)
        alive = np.where(hole, 0.0, alive)
        rgb = over(rgb, (74, 56, 40), rim * 0.8 * (hash01(ix, iy, 5, seed) > 0.35))
        rgb = over(rgb, (190, 166, 128), rim * 0.3 * (hash01(ix, iy, 6, seed) > 0.8))
    foot = by < (fbm2(X, Y, 2.2, 1.0, seed + 16, 2) - 0.35) * 1.1
    alive = np.where(foot & (row == SAIL_ROWS - 1), 0.0, alive)
    if face == 'back':
        rgb = rgb * 0.9
    return Out(rgb * (0.9 + 0.2 * fbm(f.W, 25.0, seed + 17, 2))[..., None], alive)


def paint_furl(f):
    seed = seed_of('furl', f.name)
    a, c, cap = unroll(f, 0)
    X = f.X
    t = 0.40 + (fbm2(a, c, 16.0, 1.2, seed, 3) - 0.5) * 0.18 + 0.08 * (0.5 + 0.5 * np.sin(c * 1.9 + fbm(f.W, 5.0, seed + 1, 2) * 3.0)) - 0.04
    rgb = tone_rgb('madder', t)
    rgb = over(rgb, (70, 60, 44), 0.25 * sstep(0.6, 0.8, fbm(f.W, 5.0, seed + 2, 3)))
    # the gaskets: hemp lashings round the bundle
    gk = np.abs(((X + 47.0) % 11.5) - 5.75) > 5.2
    rgb = over(rgb, tone_rgb('hemp', 0.45 + 0.1 * np.sin(c * 4.0)), gk * 0.95)
    sh = 1 - 0.25 * across(f, 0) ** 2
    return Out(rgb * sh[..., None])


def paint_pennant(f):
    kw = f.kw
    seg = int(kw['seg'])
    L = kw['L']
    seed = seed_of('pennant')
    z0 = sum(PENNANT_LEN[:seg])
    u = f.P[..., 2] - f.o[2]                               # along this segment
    zt = z0 + u                                            # along the whole streamer
    y = f.P[..., 1] - (f.o[1] + f.sz[1] / 2)
    if f.face not in ('left', 'right'):
        return Out(tone_rgb('madder', 0.2) * 0.8, np.ones(zt.shape))
    hh = 4.0 - 0.0577 * zt
    alive = np.abs(y) < hh
    total = sum(PENNANT_LEN)
    notch = np.maximum(0.0, zt - (total - 11.0)) * 0.30
    alive = alive & ~((np.abs(y) < notch) & (zt > total - 11.0))
    t = 0.38 + (fbm2(zt, y, 7.0, 2.5, seed, 3) - 0.5) * 0.14
    rgb = tone_rgb('madder', t)
    # the gold band along it, a gold hoist, the stitched hem
    band = (np.abs(y + 0.2 * np.sin(zt * 0.0)) < 0.65) & (zt > 2.2)
    rgb = over(rgb, ramp(RAMP['gold'], 0.55 + 0.1 * fbm(f.W, 2.0, seed + 1, 2)), band * 0.95)
    hoist = (zt < 2.0) & (zt > 0.8)
    rgb = over(rgb, ramp(RAMP['gold'], 0.5), hoist * 0.9)
    rgb = rgb * (1 - 0.3 * ((hh - np.abs(y) < 0.55) & (hh - np.abs(y) > 0.3)))[..., None]
    ix = np.floor(f.X * HD)
    rgb = rgb * (1 + 0.02 * (((ix + np.floor(f.Z * HD)) % 2) - 0.5) * 2)[..., None]
    return Out(rgb, alive.astype(float))


MATS.update({
    'hull': paint_hull, 'deck': paint_deck, 'grate': paint_grate, 'timber': paint_timber, 'mast': paint_mast, 'rope': paint_rope,
    'hemp_block': paint_hemp_block, 'iron': paint_iron, 'brass': paint_brass, 'lamp': paint_lamp, 'rudder': paint_rudder,
    'barrel': paint_barrel, 'crate': paint_crate, 'chest': paint_chest, 'coil': paint_coil, 'sack': paint_sack,
    'canvas': paint_canvas, 'furl': paint_furl, 'pennant': paint_pennant,
})


# ================================================================== previews: the sea, the sky, the light, frames and GIFs

SKIES = {
    'day': dict(toL=(-0.50, -0.78, -0.28), key=(0.68, 0.64, 0.56), amb=(0.45, 0.47, 0.53), top=(92, 140, 196), hor=(204, 222, 238),
                sea=(24, 58, 84), sea2=(44, 96, 120), fog=(176, 204, 222), stars=False),
    'dusk': dict(toL=(-0.62, -0.30, -0.70), key=(0.82, 0.54, 0.37), amb=(0.31, 0.34, 0.50), top=(36, 48, 92), hor=(236, 142, 96),
                 sea=(20, 32, 58), sea2=(54, 66, 98), fog=(170, 110, 100), stars=False),
    'night': dict(toL=(-0.45, -0.72, 0.40), key=(0.52, 0.64, 0.98), amb=(0.23, 0.29, 0.45), top=(5, 8, 22), hor=(26, 36, 66),
                  sea=(6, 11, 24), sea2=(16, 28, 50), fog=(20, 30, 54), stars=True),
}


def make_light(sky, turn=0.0):
    """The light of [sky] as a function of face normals; [turn] degrees swings it round the vertical (a turntable's light goes round with its camera)."""
    toL = np.array(sky['toL'], float)
    if turn:
        a = math.radians(turn)
        toL = np.array([toL[0] * math.cos(a) - toL[2] * math.sin(a), toL[1], toL[0] * math.sin(a) + toL[2] * math.cos(a)])
    toL = toL / np.linalg.norm(toL)
    key, amb = np.array(sky['key']), np.array(sky['amb'])

    def light(N):
        d = np.clip(N @ toL, 0.0, 1.0)
        return amb[None, :] + key[None, :] * d[:, None]
    return light


def sea_sky(cam, sky, t, W, H, speed=0.0, plain=False):
    """The sea (and the sky above it) as the camera sees it: (rgb (H, W, 3), foam-free). Returns also the sea plane's x, z for the foam.
    [plain] leaves the swell and its crests out (a smooth sea, for a turntable whose camera goes round: a GIF stays small)."""
    x, z, d = cam.plane_y(0.0, W, H)
    Dmax = 640.0
    if plain:
        base = mixc(np.array(sky['sea'], float), np.array(sky['sea2'], float), 0.5 * np.ones(x.shape))
    else:
        wv = (np.sin(x * 0.21 + z * 0.13 + t * 0.045) + 0.8 * np.sin(x * 0.09 - z * 0.17 - t * 0.03) + 0.3 * np.sin(x * 0.5 + z * 0.3 + t * 0.08)) / 2.1
        rip = fbm2(x + t * 0.12, z, 7.0, 9.0, 11, 3)
        base = mixc(np.array(sky['sea'], float), np.array(sky['sea2'], float), np.clip(0.5 + 0.5 * wv * 0.45 + (rip - 0.5) * 0.55, 0, 1))
        # short pale crests (dashes across the line of sight, as the sea looks from a ship's height), more of them far off
        crest = sstep(0.70, 0.84, fbm2(x + t * 0.25, z, 2.8, 1.1, 17, 2)) * (0.35 + 0.65 * sstep(40.0, 260.0, d))
        base = mixc(base, np.array(sky['hor'], float) * 0.55 + np.array(sky['sea2'], float) * 0.45, crest * 0.20)
        # light caught on the swell, more toward the light
        glint = np.clip(0.5 + 0.5 * wv, 0, 1) ** 8 * np.clip(rip * 1.3 - 0.45, 0, 1)
        base = base + glint[..., None] * np.array(sky['key'], float) * 14.0
    far = sstep(120.0, Dmax, d)
    sea = mixc(base, np.array(sky['fog'], float), far * 0.75)
    # the sky above the horizon
    row_h = H / 2 + (cam.center[1] * 0 + (0.0 - cam.center[1]) * cam.ce - Dmax * cam.se) * cam.scale
    ys = np.arange(H)[:, None] + 0.5
    u = np.clip((row_h - ys) / max(row_h, 1.0), 0.0, 1.0)
    sky_col = mixc(np.array(sky['hor'], float), np.array(sky['top'], float), (u ** 0.7) * np.ones((1, W)))
    if sky['stars']:
        rng = np.random.default_rng(5)
        n = int(W * H / 2500)
        sx, sy = rng.integers(0, W, n), rng.integers(0, max(int(row_h) - 2, 1), n)
        br = rng.random(n) ** 3 * 190 + 50
        for a, b, c in zip(sx, sy, br):
            sky_col[b, a] = np.clip(sky_col[b, a] + c, 0, 255)
    horizon = (d > Dmax) | (np.arange(H)[:, None] + 0.5 < row_h)
    rgb = np.where(horizon[..., None], sky_col, sea)
    # a soft band of haze along the horizon
    haze = np.exp(-((ys - row_h) / (H * 0.03)) ** 2)
    rgb = mixc(rgb, np.array(sky['fog'], float), haze * 0.5 * np.ones((1, W)))
    return rgb, x, z, ~horizon


def foam_on_sea(x, z, t, speed, sea_mask):
    """Foam hugging the hull at the waterline, the bow wave and the wake: 0..1 over the sea plane."""
    dz = np.maximum(np.maximum(float(zfront(0.0)) - z, z - float(zaft(0.0))), 0.0)
    hbw = hb(np.clip(z, float(zfront(0.0)) + 0.5, float(zaft(0.0)) - 0.5), 0.0)
    dx = np.maximum(np.abs(x) - hbw, 0.0)
    dist = np.hypot(dx, dz)
    noise = fbm2(x + t * 0.3, z, 2.5, 2.5, 21, 2)
    ring = np.exp(-dist / 1.6) * (0.35 + 0.65 * noise)
    ring = ring * (0.45 + 4.0 * speed)
    bow = np.exp(-np.maximum(float(zfront(0.0)) + 6.0 - z, 0.0) / 9.0) * np.exp(-(np.abs(x) / 9.0) ** 2) * (z < float(zfront(0.0)) + 14)
    bow = bow * (0.2 + 6.0 * speed) * (0.3 + 0.7 * noise)
    az = np.maximum(z - 70.0, 0.0)
    wake = (np.exp(-((np.abs(x) - (0.20 * az + 2.5)) / 1.3) ** 2) + 0.6 * np.exp(-(x / (3.0 + 0.07 * az)) ** 2)) * np.exp(-az / 70.0) * (z > 62.0)
    wake = wake * np.clip(speed * 9.0, 0, 1) * (0.3 + 0.7 * noise)
    return np.clip(ring + bow + wake, 0.0, 1.0) * sea_mask


def tilt_root(pitch, heel, bob):
    """The renderer's lean of the whole ship (nose up [pitch] degrees, heeled to port [heel] degrees, lifted [bob] blocks) as a matrix on
    model space. The renderer turns the pose stack about x by the pitch and then about z by the heel and then flips x and y, which in
    the model's own frame is the heel first and the pitch (the other way round) after it: so a part given x = pitch and z = -heel
    undoes the lean exactly, and that is how the lanterns hang plumb."""
    R = rotm_rad(-pitch * D2R, 0.0, 0.0) @ rotm_rad(0.0, 0.0, heel * D2R)
    M = np.eye(4)
    M[:3, :3] = R
    M[1, 3] = -bob * 16.0
    return M


FIGURE_FEET = (0.0, -20.0, -56.0)     # the bow lookout's feet (ship_anims.py's lookout clip) and the point its right hand grips: LOOKOUT_HAND


_FIGURE = []


def figure_scene():
    """A blocky stand-in for the bow lookout in flat colours (a 4/8/12 px body over the clip's feet, the right arm out to the hand on the
    bitt's head), so a preview can show the grip and the clearance. Only for the previews: it is not part of the model. Returns (scene, tex)."""
    if _FIGURE:
        return _FIGURE[0]
    NAMES.discard('f_arm_s')
    fx, fy, fz = FIGURE_FEET
    skin, tunic, cloth, boot, hair = (196, 150, 120), (118, 44, 50), (74, 58, 46), (46, 36, 30), (58, 40, 30)
    parts = []

    def blk(nm, x0, y0, z0, w, h, d, rgb):
        parts.append(Part(nm, (0, 0, 0), (0, 0, 0), [Cu((fx + x0, fy + y0, fz + z0), (w, h, d), 'flat', nm, rgb=rgb)]))
    blk('f_leg_s', -4, -12, -2, 4, 12, 4, cloth)
    blk('f_leg_p', 0, -12, -2, 4, 12, 4, cloth)
    blk('f_body', -4, -24, -2, 8, 12, 4, tunic)
    blk('f_head', -4, -32, -4, 8, 8, 8, skin)
    blk('f_hair', -4.25, -32.25, -4.25, 8.5, 2.5, 8.5, hair)
    blk('f_arm_p', 4, -24, -2, 4, 12, 4, tunic)
    hand = np.array(LOOKOUT_HAND, float)
    shoulder = np.array([fx - 6.0, fy - 22.0, fz - 1.0])
    p1 = hand + (hand - shoulder) / np.linalg.norm(hand - shoulder) * 2.0
    arm = rod('f_arm_s', shoulder, p1, 4.0, 'flat', rgb=tunic)
    fig = Model('figure', 64, 64, parts + [arm])
    pack_atlas(fig)
    sc = Scene(fig)
    tex = np.zeros((fig.tex_h * HD, fig.tex_w * HD, 4), np.uint8)
    for k in range(len(sc.FC)):
        c = sc.cubes[int(sc.FC[k])][1]
        x, y, w, h = [int(v) for v in sc.FR[k]]
        tex[y:y + h, x:x + w] = (*c.kw['rgb'], 255)
    _FIGURE.append((sc, tex))
    return sc, tex


class Preview:
    """The painted ship in its scene: sprites, the pose and the picture."""

    def __init__(self, scene, tex, glow, lens):
        self.sc, self.tex, self.glow, self.lens = scene, tex, glow, lens
        self.seas = {}

    def frame(self, st, yaw, elev, scale, size, center, sky='dusk', ss=2, glow_gain=None, bloom=1.0, extras=(), sea_t=None, sea_speed=0.0, plain=False, mask=False, light_follow=False):
        """One picture. [sea_t] freezes the sea at that time and [sea_speed] its foam (an animation's sea stays still, so a GIF holds
        only what moves); without it the sea keeps the state's time. [plain] is a smooth sea. [light_follow] keeps the light where it is
        for the camera (the hero's) as the camera goes round. [mask] also returns where the ship is (0..1)."""
        sk = SKIES[sky]
        W, H = size
        cam = Camera(yaw, elev, scale * ss, (W * ss, H * ss), center)
        P = pose(st, self.lens)
        root = tilt_root(st.pitch, st.heel, st.bob)
        gg = st.lanterns if glow_gain is None else glow_gain
        light = make_light(sk, (38.0 - yaw) if light_follow else 0.0)
        img, depth, ybuf, hit, gbuf = raster(self.sc, self.tex, self.glow, P, cam, light, glow_gain=gg, root=root)
        for sc2, tex2 in extras:                           # things standing aboard that are not part of the model (the lookout stand-in)
            i2, d2, y2, h2, g2 = raster(sc2, tex2, None, None, cam, light, root=root)
            take = h2 & (d2 < depth)
            img = np.where(take[..., None], i2, img)
            depth = np.where(take, d2, depth)
            ybuf = np.where(take, y2, ybuf)
            hit = hit | take
        key = (yaw, elev, scale, tuple(size), tuple(center), sky, ss, sea_t, sea_speed, plain)
        bg = self.seas.get(key) if sea_t is not None else None
        if bg is None:
            t_sea = st.time if sea_t is None else sea_t
            bg, x, z, seamask = sea_sky(cam, sk, t_sea, W * ss, H * ss, plain=plain)
            foam = foam_on_sea(x, z, t_sea, st.speed if sea_t is None else sea_speed, seamask)
            bg = mixc(bg, np.array([206, 214, 220], float) * (0.55 + 0.45 * np.array(sk['amb']).mean() * 1.5), np.clip(foam, 0, 1) * 0.7)
            if sea_t is not None:
                self.seas[key] = bg
        out = np.where(hit[..., None], img, bg)
        under = hit & (ybuf > 0.0)
        depth_k = np.clip(ybuf / 36.0, 0.0, 1.0)
        tint = np.array([0.62, 0.88, 0.92])
        sea_c = np.array(sk['sea2'], float)
        under_c = out * (tint[None, None, :] * (0.66 - 0.36 * depth_k[..., None])) + sea_c[None, None, :] * (0.16 + 0.32 * depth_k[..., None])
        out = np.where(under[..., None], under_c, out)
        # a thin line of foam where the hull meets the water
        wl = hit & (np.abs(ybuf) < 0.8)
        out = mixc(out, np.array([200, 208, 214], float) * 0.7, wl * 0.28)
        im = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).resize(size, Image.LANCZOS)
        if gg > 0.02 and bloom > 0:
            from PIL import ImageFilter
            g = Image.fromarray(np.clip(gbuf, 0, 255).astype(np.uint8)).resize(size, Image.LANCZOS)
            halo = np.asarray(g.filter(ImageFilter.GaussianBlur(radius=max(3.0, scale * 3.0))), float) * (1.6 * bloom)
            halo2 = np.asarray(g.filter(ImageFilter.GaussianBlur(radius=max(8.0, scale * 9.0))), float) * (1.4 * bloom)
            im = Image.fromarray(np.clip(np.asarray(im, float) + halo + halo2, 0, 255).astype(np.uint8))
        if mask:
            m = np.asarray(Image.fromarray((hit * 255).astype(np.uint8)).resize(size, Image.BOX), np.float32) / 255.0
            return im, m
        return im


def smooth(a, b, f):
    t = clampf((f - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ================================================================== the Kotlin body: CogModel.kt (geometry generated here, setupAnim written to match pose())

KT_KEYWORDS = {'as', 'break', 'class', 'continue', 'do', 'else', 'false', 'for', 'fun', 'if', 'in', 'interface', 'is', 'null', 'object',
               'package', 'return', 'super', 'this', 'throw', 'true', 'try', 'typealias', 'typeof', 'val', 'var', 'when', 'while'}
KT_BUDGET = 13000                # estimated bytecode of one helper method (about twice what it really weighs; the JVM allows 64 KB a method, and compiles to machine code only the ones under 8 KB)


def kt_float(v):
    s = ('%.3f' % v).rstrip('0').rstrip('.')
    return (s if s not in ('-0', '') else '0') + 'f'


def kt_cost(p):
    """About what a part's addOrReplaceChild call weighs in bytecode."""
    return 70 + 34 * len(p.cubes) + sum(kt_cost(c) for c in p.children)


def kt_emit(part, parent, out):
    f = kt_float
    assert part.name.isidentifier() and part.name not in KT_KEYWORDS, part.name
    var = part.name if part.children else None
    b = 'CubeListBuilder.create()'
    for c in part.cubes:
        x, y, z = c.origin
        w, h, d = c.size
        b += f'.texOffs({c.uv[0]}, {c.uv[1]}).addBox({f(x)}, {f(y)}, {f(z)}, {f(w)}, {f(h)}, {f(d)})'
    px, py, pz = part.pivot
    rx, ry, rz = [math.radians(a) for a in part.rot]
    pose = (f'PartPose.offsetAndRotation({f(px)}, {f(py)}, {f(pz)}, {f(rx)}, {f(ry)}, {f(rz)})' if any(part.rot)
            else f'PartPose.offset({f(px)}, {f(py)}, {f(pz)})')
    call = f'{parent}.addOrReplaceChild("{part.name}", {b}, {pose})'
    out.append('            ' + (f'val {var} = {call}' if var else call))
    for ch in part.children:
        kt_emit(ch, var, out)


def kt_chunks(groups):
    """[(method name, body lines)]: each group of top-level parts, cut into methods that stay far under the JVM's size limit."""
    chunks = []
    for label, parts in groups:
        n = max(1, math.ceil(sum(kt_cost(p) for p in parts) / KT_BUDGET))
        share = sum(kt_cost(p) for p in parts) / n
        cuts, cur, cost = [], [], 0
        for p in parts:
            if cur and cost >= share and len(cuts) < n - 1:
                cuts.append(cur)
                cur, cost = [], 0
            cur.append(p)
            cost += kt_cost(p)
        if cur:
            cuts.append(cur)
        for i, ps in enumerate(cuts):
            lines = []
            for p in ps:
                kt_emit(p, 'root', lines)
            chunks.append(('build' + label.capitalize() + (str(i + 1) if len(cuts) > 1 else ''), lines))
    return chunks


def kt_list(vals):
    return 'floatArrayOf(' + ', '.join(kt_float(v) for v in vals) + ')'


KT_HEAD = '''package io.github.vaspyyy.guildmark.client.ship

import io.github.vaspyyy.guildmark.Guildmark
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The cog: a single-masted trading ship, drawn bow toward -z in pixels (16 to a block) from the waterline amidships, y down, the
 * starboard side at -x (the renderer flips the pose stack by scale(-1, -1, 1), as for any entity model). Every part of her moves
 * that can: the yard braces round on the mast with its braces and lifts following (rods turned and stretched between their fixed
 * ends and the yard's arms), the sail comes down one row at a time (or is hauled up into the furled bundle) and each row bellies
 * with the wind, ripples when she luffs and shivers in a breeze, the pennant (three chained pieces) streams the way the wind
 * blows, the wheel turns with the rudder, the lanterns and the bell and the anchor hang plumb as she pitches and heels (and
 * sway), the bell swings and its clapper lags it, the anchor pays out its rope a length at a time and the capstan turns with it.
 *
 * The geometry comes from art/scripts/ship.py (edit it there: it writes this file's body and the texture, and its `pose()` is the
 * same animation in Python, run for the previews, so the two agree). The texture is HD: this model declares 512x512 and the PNGs
 * are painted at twice that. Her decks, sail, pennant and hatch grating are thin slabs cut to shape in the texture's alpha, so
 * she is drawn with a cutout render type that does not cull back faces ([RenderTypes.entityCutout]); the glow layer is the same
 * model drawn again with [RenderTypes.eyes] over cog_glow.png, tinted by the lanterns' brightness (it holds only what burns: the
 * lanterns' panes, the cabin's windows and door, the binnacle lamp).
 */
class CogModel(root: ModelPart) : EntityModel<CogRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The yard turns on the mast; the braces (yard arm to the castle's rail) and the lifts (yard arm to the masthead) follow its arms
    private val yard = part("yard")
    private val braces = arrayOf(part("brace_s"), part("brace_p"))
    private val lifts = arrayOf(part("lift_s"), part("lift_p"))

    // The sail: the furled bundle on the yard, and five rows, each hung from the one above with a side panel to either hand of it
    private val furl = part("furl")
    private val rows = Array(5) { part("sail${it + 1}") }
    private val lefts = Array(5) { part("sail${it + 1}l") }
    private val rights = Array(5) { part("sail${it + 1}r") }

    // The pennant, three pieces each hung from the one before
    private val pennants = Array(3) { part("pennant${it + 1}") }

    // The helm, and what hangs: the lanterns, the bell and its clapper, the anchor and the seven lengths of its rope, the capstan
    private val wheel = part("wheel")
    private val rudder = part("rudder")
    private val sternLantern = part("lantern_stern")
    private val bowLantern = part("lantern_bow")
    private val bell = part("bell")
    private val clapper = part("bell_clapper")
    private val anchor = part("anchor")
    private val ropes = Array(ROPE_SEGS) { part(if (it == 0) "anchor_rope" else "anchor_rope${it + 1}") }
    private val capstan = part("capstan")

    // Scratch for the sail (the part of each row let down, and how far aft each seam between rows lies): nothing allocated per frame
    private val down = FloatArray(5)
    private val seam = FloatArray(6)

    override fun setupAnim(state: CogRenderState) {
        super.setupAnim(state)
        val t = state.time
        val wind = state.wind.coerceIn(0f, 1f)
        // A blow against her hull shudders what hangs and flogs the canvas
        val shake = (state.hurtTime / 10f).coerceIn(0f, 1f)
        turnYard(state.brace)
        setSail(state, t, wind, shake)
        streamPennant(state, t, wind)
        wheel.zRot = -state.wheel * DEG
        rudder.yRot = -state.rudder * DEG
        hang(state, t, wind, shake)
    }

    /** The yard turns on the mast (above 0 its starboard arm swings aft); each brace and lift is turned and stretched to its arm. */
    private fun turnYard(brace: Float) {
        val theta = brace * DEG
        yard.yRot = theta
        val c = cos(theta)
        val s = sin(theta)
        for (side in 0..1) {
            val sigma = if (side == 0) -1f else 1f
            follow(braces[side], BRACE_FIX, side * 3, sigma * YARD_TIP, 0f, 0f, BRACE_LEN, c, s)
            follow(lifts[side], LIFT_FIX, side * 3, sigma * YARD_LIFT, LIFT_DY, LIFT_DZ, LIFT_LEN, c, s)
        }
    }

    /**
     * Turns [line], a rod pivoting on its fixed end at [fix] ([at] picks which end's three numbers) and running along its own y, to the
     * point ([x], [y], [z]) of the yard's own frame (the yard turned by the sine [s] and cosine [c] of its brace) and stretches it to reach,
     * [rest] being the length the rod was built to.
     */
    private fun follow(line: ModelPart, fix: FloatArray, at: Int, x: Float, y: Float, z: Float, rest: Float, c: Float, s: Float) {
        val dx = YARD_X + x * c + z * s - fix[at]
        val dy = YARD_Y + y - fix[at + 1]
        val dz = YARD_Z - x * s + z * c - fix[at + 2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        line.xRot = asin(dz / length)
        line.yRot = 0f
        line.zRot = atan2(-dx, dy)
        line.yScale = length / rest
    }

    /**
     * The sail. [CogRenderState.sail] lets the rows down one after the other (the last one to come down is drawn shorter until it is
     * all the way); the furled bundle shrinks away as they come. A full sail ([CogRenderState.fill] above 0) bellies forward, most
     * in the middle of what is let down, its side panels folding at the seams; taken aback (below 0) it is pressed back against the
     * mast. Luffing, each row nods and its side panels flap, the foot most; and in any wind the canvas shivers a little.
     */
    private fun setSail(state: CogRenderState, t: Float, wind: Float, shake: Float) {
        val n = state.sail.coerceIn(0f, 1f) * 5f
        var total = 0f
        for (k in 0..4) {
            down[k] = ROW_H[k] * (n - k).coerceIn(0f, 1f)
            total += down[k]
        }
        val furled = 1f - n / 5f
        furl.visible = furled > 0.02f
        val bundle = max(furled, 0.12f)
        furl.yScale = bundle
        furl.zScale = bundle
        val fill = state.fill.coerceIn(-1f, 1f)
        val luff = max(state.luff, shake * 0.5f).coerceIn(0f, 1f)
        // How far aft the seams between the rows lie: negative bellies forward
        val deep = (if (fill >= 0f) -BELLY_FWD * fill else -BELLY_AFT * fill) * (total / SAIL_H)
        var run = 0f
        for (k in 0..5) {
            val s = if (total > 0f) run / total else 0f
            seam[k] = deep * sin(PI_F * s.pow(0.85f))
            if (k < 5) run += down[k]
        }
        val wt = t * (0.42f + 0.5f * wind)
        var before = 0f
        run = 0f
        for (k in 0..4) {
            val row = rows[k]
            if (down[k] <= 0.3f) {
                row.visible = false
                continue
            }
            row.visible = true
            row.yScale = down[k] / ROW_H[k]
            // Each row is a child of the one above: it turns by the difference of their slopes
            var tilt = asin(((seam[k + 1] - seam[k]) / down[k]).coerceIn(-0.7f, 0.7f))
            val free = 0.55f + 0.45f * k / 4f
            tilt += luff * RIPPLE_X * free * sin(wt * 1.6f - 0.9f * k) + wind * SHIVER_X * free * sin(wt * 0.9f - 1.3f * k + 1f)
            row.xRot = tilt - before
            row.yRot = luff * 0.07f * sin(wt * 1.1f + 1.1f * k)
            before = tilt
            val middle = (run + down[k] / 2f) / total
            run += down[k]
            val fold = SIDE_BELLY * fill * (0.3f + 0.7f * sin(PI_F * middle))
            lefts[k].yRot = -(fold + luff * RIPPLE_Y * free * sin(wt * 1.9f + 1.7f * k + 0.6f) + wind * SHIVER_Y * free * sin(wt * 1.3f + 2.1f * k))
            rights[k].yRot = fold + luff * RIPPLE_Y * free * sin(wt * 1.9f + 1.7f * k + 2.4f) + wind * SHIVER_Y * free * sin(wt * 1.3f + 2.1f * k + 1.7f)
        }
    }

    /** The pennant streams the way the wind blows ([CogRenderState.windYaw]), drooping in a calm and rippling along its length. */
    private fun streamPennant(state: CogRenderState, t: Float, wind: Float) {
        val ft = t * (0.55f + 0.55f * wind + 0.3f * state.speed)
        val droop = (1f - wind).pow(1.6f)
        for (i in 0..2) {
            val piece = pennants[i]
            piece.xRot = -droop * (0.18f + 0.12f * i) + 0.07f * wind * sin(ft * 1.3f - 1.3f * i + 0.5f)
            piece.yRot = (if (i == 0) (state.windYaw + 180f) * DEG else 0f) + (0.10f + 0.20f * wind) * (1f + 0.5f * i) * sin(ft - 1.15f * i)
            piece.zRot = 0.10f * (0.4f + wind) * sin(ft * 0.8f - i)
        }
    }

    /**
     * What hangs stays nearly plumb as she pitches and heels (the renderer leans the whole of her about x by the pitch and about z by
     * the heel and flips x and y, so a part turned by +pitch about x and -heel about z undoes it) and sways in the swell: the two
     * lanterns, the bell (and its clapper, lagging it), the anchor on its cable and the capstan that turns as the cable pays out.
     */
    private fun hang(state: CogRenderState, t: Float, wind: Float, shake: Float) {
        val sway = 0.02f + 0.025f * wind + 0.03f * state.speed + 0.05f * shake
        val kick = shake * state.hurtDir
        val px = state.pitch * DEG * TILT_HANG
        val pz = -state.heel * DEG * TILT_HANG
        sternLantern.xRot = px + sway * sin(t * 0.19f + 0.4f) + 0.1f * kick * sin(t * 1.1f)
        sternLantern.zRot = pz + 0.8f * sway * sin(t * 0.15f + 2f)
        bowLantern.xRot = px + sway * sin(t * 0.21f + 2.5f)
        bowLantern.zRot = pz + 0.8f * sway * sin(t * 0.17f + 4.1f) + 0.1f * kick * sin(t * 1.3f)
        // The bell, struck: it swings fore and aft and settles, the clapper a beat behind it
        var swing = 0f
        var clap = 0f
        val b = state.bell
        if (b >= 0f) {
            val ring = 0.5f * exp(-b / 20f)
            swing = ring * sin(b * 0.45f)
            clap = -1.3f * ring * sin(b * 0.45f - 0.8f)
        }
        bell.xRot = swing + px + 0.3f * sway * sin(t * 0.23f)
        bell.zRot = pz * 0.5f
        clapper.xRot = clap
        // The anchor hangs from the cathead's hook on its cable, d paid out; each length of the rope is shown as far as it is out
        val d = state.anchor.coerceIn(0f, 1f) * ANCHOR_DROP
        val ax = px + 0.7f * sway * sin(t * 0.13f + 1f)
        val az = pz + 0.7f * sway * sin(t * 0.11f + 3f)
        anchor.x = ANCHOR_X - d * cos(ax) * sin(az)
        anchor.y = ANCHOR_Y + d * cos(ax) * cos(az)
        anchor.z = ANCHOR_Z + d * sin(ax)
        anchor.xRot = ax
        anchor.zRot = az
        ropes[0].xRot = ax
        ropes[0].zRot = az
        for (i in 0 until ROPE_SEGS) {
            val out = d - ROPE_SEG * i
            ropes[i].visible = out > 0.01f
            ropes[i].yScale = (out / ROPE_SEG).coerceIn(0f, 1f)
        }
        capstan.yRot = d / 4f
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "cog"), "main")

@@CONSTANTS@@
        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
@@CALLS@@
            return LayerDefinition.create(mesh, @@W@@, @@H@@)
        }
@@CHUNKS@@    }
}
'''


def kt_constants():
    f = kt_float
    ints = lambda n: f'{n}'
    L = [
        '        private const val DEG = 0.017453292f',
        '        private const val PI_F = 3.1415927f',
        '',
        '        // The yard\'s pivot on the mast, the sail\'s rows (px down) and all of it',
        f'        private const val YARD_X = {f(YARD_AT[0])}',
        f'        private const val YARD_Y = {f(YARD_AT[1])}',
        f'        private const val YARD_Z = {f(YARD_AT[2])}',
        f'        private val ROW_H = {kt_list(ROW_H)}',
        f'        private const val SAIL_H = {f(SAIL_H)}',
        '',
        '        // How the sail bellies and flogs: px forward / back at the deepest, radians the side panels fold, the rows and panels flogging, the canvas shivering',
        f'        private const val BELLY_FWD = {f(BELLY_FWD)}',
        f'        private const val BELLY_AFT = {f(BELLY_AFT)}',
        f'        private const val SIDE_BELLY = {f(SIDE_BELLY)}',
        f'        private const val RIPPLE_X = {f(RIPPLE_X)}',
        f'        private const val RIPPLE_Y = {f(RIPPLE_Y)}',
        f'        private const val SHIVER_X = {f(SHIVER_X)}',
        f'        private const val SHIVER_Y = {f(SHIVER_Y)}',
        '',
        '        // The braces and the lifts: the fixed end of the starboard one then the port one (x, y, z), how far along the yard each is made fast, the lift\'s offset up and aft, how long each rod was built',
        f'        private val BRACE_FIX = {kt_list(list(BRACE_FIX[-1]) + list(BRACE_FIX[1]))}',
        f'        private val LIFT_FIX = {kt_list(list(LIFT_FIX[-1]) + list(LIFT_FIX[1]))}',
        f'        private const val YARD_TIP = {f(YARD_TIP)}',
        f'        private const val YARD_LIFT = {f(YARD_LIFT)}',
        f'        private const val LIFT_DY = {f(LIFT_DY)}',
        f'        private const val LIFT_DZ = {f(LIFT_DZ)}',
        f'        private const val BRACE_LEN = {f(LENS["brace_s"])}',
        f'        private const val LIFT_LEN = {f(LENS["lift_s"])}',
        '',
        '        // What hangs: how nearly plumb against her lean; the anchor\'s hook, how far it drops, the lengths of its rope',
        f'        private const val TILT_HANG = {f(TILT_HANG)}',
        f'        private const val ANCHOR_X = {f(ANCHOR_AT[0])}',
        f'        private const val ANCHOR_Y = {f(ANCHOR_AT[1])}',
        f'        private const val ANCHOR_Z = {f(ANCHOR_AT[2])}',
        f'        private const val ANCHOR_DROP = {f(ANCHOR_DROP)}',
        f'        private const val ROPE_SEG = {f(ROPE_SEG)}',
        f'        private const val ROPE_SEGS = {ROPE_SEGS}',
        '',
    ]
    return '\n'.join(L)


LENS = {}


def kotlin_source(model):
    """CogModel.kt as a string: the hand-written class (see KT_HEAD) with the constants and the mesh generated from the built [model]."""
    LENS.update({p.name: p.cubes[0].size[1] for p in model.all_parts() if p.name.startswith(('brace_', 'lift_'))})
    chunks = kt_chunks(GROUPS)
    calls = '\n'.join(f'            {name}(root)' for name, _ in chunks)
    body = ''
    for name, lines in chunks:
        body += '\n        @Suppress("LocalVariableName", "UNUSED_VARIABLE")\n'
        body += f'        private fun {name}(root: PartDefinition) {{\n' + '\n'.join(lines) + '\n        }\n'
    out = (KT_HEAD.replace('@@CONSTANTS@@', kt_constants()).replace('@@CALLS@@', calls).replace('@@CHUNKS@@', body)
           .replace('@@W@@', str(model.tex_w)).replace('@@H@@', str(model.tex_h)))
    names = {p.name for p in model.all_parts()}
    for m_ in re.finditer(r'part\("([a-z0-9_]+)"\)', out):
        assert m_.group(1) in names, m_.group(1)
    for need in ['sail%d%s' % (k, sfx) for k in range(1, 6) for sfx in ('', 'l', 'r')] + [f'pennant{k}' for k in (1, 2, 3)] + ROPE_NAMES:
        assert need in names, need
    return out


# ================================================================== the files and the previews

DEFAULT_KOTLIN_DIR = os.path.normpath(os.path.join(HERE, '..', '..', 'src', 'main', 'kotlin', 'io', 'github', 'vaspyyy', 'guildmark', 'client', 'ship'))


def painted():
    """Builds the model, packs its atlas and paints it: (model, scene, texture (1024x1024 RGBA), glow map (the same))."""
    model = build()
    pack_atlas(model)
    scene = Scene(model)
    tex, layers = paint_model(scene)
    glow = glow_map(layers)
    return model, scene, tex, glow


def check_atlas(scene):
    """Every face's rectangle must lie inside the texture and none may overlap another. Returns (problems, texels used)."""
    H, W = TEX_H * HD, TEX_W * HD
    occ = np.zeros((H, W), np.int32)
    problems = []
    for k in range(len(scene.FC)):
        x, y, w, h = [int(v) for v in scene.FR[k]]
        if x < 0 or y < 0 or x + w > W or y + h > H:
            problems.append(f'{scene.cubes[int(scene.FC[k])][1].name} {scene.FF[k]}: outside the texture')
            continue
        occ[y:y + h, x:x + w] += 1
    over = int((occ > 1).sum())
    if over:
        problems.append(f'{over} texels belong to more than one face')
    return problems, int((occ > 0).sum())


def deterministic_uuids(text):
    import uuid
    seen = {}

    def sub(m):
        u = m.group(0)
        if u not in seen:
            seen[u] = str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/cog/{len(seen)}'))
        return seen[u]
    return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', sub, text)


# ---- what the model reaches, and what a rider can stand on

def extent_report(scene, lens):
    """How far the model reaches in px in every state it can be posed in (the culling box has to cover it), and which part reaches
    furthest; and the surfaces the riders' spots stand on. Returns the text."""
    rng = np.random.default_rng(11)
    lo = np.full(3, 1e9)
    hi = np.full(3, -1e9)
    who_lo, who_hi = [''] * 3, [''] * 3
    part_lo, part_hi = {}, {}

    def take(st, label=''):
        P = pose(st, lens)
        idx, A, B, D, n, ok = scene.quads(P)
        C = B + D - A
        pts = np.stack([A, B, D, C], 1)
        mn, mx = pts.min(1), pts.max(1)
        for ax in range(3):
            k = int(mn[:, ax].argmin())
            if mn[k, ax] < lo[ax]:
                lo[ax], who_lo[ax] = mn[k, ax], f'{scene.parts[int(scene.FP[idx[k]])].name} ({label})'
            k = int(mx[:, ax].argmax())
            if mx[k, ax] > hi[ax]:
                hi[ax], who_hi[ax] = mx[k, ax], f'{scene.parts[int(scene.FP[idx[k]])].name} ({label})'
        for k in range(len(idx)):
            nm = scene.parts[int(scene.FP[idx[k]])].name
            if nm.startswith(('pennant', 'anchor', 'rudder', 'yard', 'sail', 'lantern', 'bell', 'wheel', 'brace', 'lift', 'furl')):
                g = re.sub(r'\d+[lr]?$', '', nm.split('_')[0])
                a, b = part_lo.get(g), part_hi.get(g)
                part_lo[g] = mn[k] if a is None else np.minimum(a, mn[k])
                part_hi[g] = mx[k] if b is None else np.maximum(b, mx[k])

    # the rigging's extremes, then a spread of random states, then the sweeps that matter most
    for sail in (0.0, 1.0):
        for brace in (-50.0, 0.0, 50.0):
            for fill in (-1.0, 0.0, 1.0):
                take(CogState(sail=sail, fill=fill, luff=1.0, brace=brace, wind=1.0, time=3.0), f'sail {sail} brace {brace} fill {fill}')
    for wy in range(-180, 181, 15):
        for wind in (0.0, 0.5, 1.0):
            for t in (0.0, 2.3, 4.7, 6.1):
                take(CogState(windYaw=float(wy), wind=wind, time=t, speed=0.2), f'pennant {wy}')
    for a in (0.0, 0.5, 1.0):
        for pitch, heel in ((0, 0), (6, 10), (-6, -10), (6, -10), (-6, 10)):
            for t in (0.0, 3.1):
                take(CogState(sail=0.0, anchor=a, pitch=float(pitch), heel=float(heel), time=t, wind=1.0, speed=0.2), f'anchor {a}')
    for r in (-30.0, 0.0, 30.0):
        for w in (-270.0, 0.0, 270.0):
            take(CogState(rudder=r, wheel=w), f'rudder {r}')
    for _ in range(300):
        take(CogState(sail=float(rng.choice([0.0, 1.0, rng.random()])), fill=float(rng.uniform(-1, 1)), luff=float(rng.random()), brace=float(rng.uniform(-50, 50)),
                      windYaw=float(rng.uniform(-180, 180)), wind=float(rng.random()), wheel=float(rng.uniform(-270, 270)), rudder=float(rng.uniform(-30, 30)),
                      anchor=float(rng.random()), lanterns=float(rng.random()), bell=float(rng.choice([-1.0, rng.uniform(0, 80)])), time=float(rng.uniform(0, 5000)),
                      speed=float(rng.uniform(0, 0.3)), hurtTime=float(rng.choice([0.0, rng.uniform(0, 10)])), hurtDir=int(rng.choice([-1, 1]))), 'random')
    out = ['Model extent over every state tried (px, 1/16 block; x + to port, y down, bow -z; origin the waterline amidships):', '']
    for ax, nm in enumerate('xyz'):
        out.append(f'  {nm}: {lo[ax]:8.1f} .. {hi[ax]:7.1f}   ({lo[ax] / 16:6.2f} .. {hi[ax] / 16:5.2f} blocks)   min: {who_lo[ax]}   max: {who_hi[ax]}')
    out += ['', 'Per moving group (px):']
    for g in sorted(part_lo):
        a, b = part_lo[g], part_hi[g]
        out.append(f'  {g:10s} x {a[0]:7.1f} .. {b[0]:6.1f}   y {a[1]:7.1f} .. {b[1]:6.1f}   z {a[2]:7.1f} .. {b[2]:6.1f}')
    return '\n'.join(out) + '\n'


SPOTS = [('helm', (0.0, -30.0, 54.0), 32.0), ('lookout', (0.0, -20.0, -56.0), 32.0), ('seat starboard', (-14.0, -19.0, 6.0), 24.0),
         ('seat port', (14.0, -19.0, 6.0), 24.0), ('seat stern', (-14.0, -36.0, 64.0), 24.0)]


def spot_report(scene):
    """For each rider's spot: what the feet stand on, and how far up the body's volume (8 px wide, 4 deep) is free of solid timber
    (the sail, the pennant, the grating and the thinnest lines don't count)."""
    M, vis = scene.mats(None)
    inv = np.linalg.inv(M)
    solid = [k for k, (pi, c) in enumerate(scene.cubes) if c.mat not in ('canvas', 'pennant', 'furl_dummy', 'grate', 'rope') and min(c.size) > 1.2]

    def inside(k, pts, grow=0.0):
        pi, c = scene.cubes[k]
        loc = pts @ inv[pi].T
        o, s = np.array(c.origin), np.array(c.size)
        return ((loc[:, :3] > o + 0.05 - grow) & (loc[:, :3] < o + s - 0.05 + grow)).all(1)

    lines = ["Riders' spots (model px; feet at the point):"]
    for nm, (x, y, z), body in SPOTS:
        xs, zs = np.arange(x - 3.5, x + 3.6, 1.0), np.arange(z - 1.5, z + 1.6, 1.0)
        gx, gy, gz = np.meshgrid(xs, np.arange(y - body, y - 0.29, 1.0), zs, indexing='ij')
        vol = np.stack([gx.ravel(), gy.ravel(), gz.ravel(), np.ones(gx.size)], 1)
        fx, fz = np.meshgrid(xs, zs)
        feet = np.stack([fx.ravel(), np.full(fx.size, y + 0.4), fz.ravel(), np.ones(fx.size)], 1)
        hits, under = {}, set()
        for k in solid:
            c = scene.cubes[k][1]
            ins = inside(k, vol)
            if ins.any():
                hits[c.name] = float(y - vol[ins, 1].max())
            if inside(k, feet).any():
                under.add(c.name)
        free = min(hits.values()) if hits else body
        what = ', '.join(f'{k} at {v:.1f}' for k, v in sorted(hits.items(), key=lambda kv: kv[1])[:4]) or 'nothing'
        lines.append(f'  {nm:15s} ({x:5.1f}, {y:6.1f}, {z:5.1f})  feet on: {", ".join(sorted(under)) or "NOTHING"};  free above: {free:.1f} of {body:.0f} px;  nearest solid: {what}')
    return '\n'.join(lines) + '\n'


# ---- the game's own sailing rules (ship/Sailing.kt, Cog.clientTick), so the animations show what the game would do

def wrap180(a):
    a = (a + 180.0) % 360.0 - 180.0
    return 180.0 if a == -180.0 else a


def approach(a, b, step):
    return min(b, a + step) if a < b else max(b, a - step)


def sail_efficiency(rel):
    off = min(abs(rel), 180.0)
    ofs, drive = (0.0, 45.0, 60.0, 80.0, 110.0, 150.0, 180.0), (0.0, 0.0, 0.4, 0.8, 1.0, 1.0, 0.9)
    for i in range(1, len(ofs)):
        if off <= ofs[i]:
            return drive[i - 1] + (off - ofs[i - 1]) / (ofs[i] - ofs[i - 1]) * (drive[i] - drive[i - 1])
    return drive[-1]


def sail_brace(rel):
    turn = clampf((180.0 - abs(rel)) * 0.42, 0.0, 50.0)
    return turn if rel >= 0.0 else -turn


def sail_fill(rel, sail, strength):
    off = abs(rel)
    wind = 0.35 + 0.65 * strength
    if off < 30.0:
        return -sail * (0.3 + 0.5 * strength) * (1.0 - off / 30.0)
    return sail * wind * sail_efficiency(rel)


def sail_luff(rel, sail):
    return sail * (1.0 - smooth(38.0, 60.0, abs(rel)))


def sail_heel(rel, fill, strength):
    return 7.0 * max(fill, 0.0) * math.sin(rel * D2R) * (0.5 + 0.5 * strength)


def keyed(t, keys):
    """A value that eases from key to key: [keys] is [(t, value), ...]."""
    if t <= keys[0][0]:
        return keys[0][1]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t <= t1:
            return v0 + (v1 - v0) * smooth(t0, t1, t)
    return keys[-1][1]


def swell(tick, speed, gain=1.6):
    """The lean the game gives a ship at rest on the swell (Cog.lean), a little exaggerated so a still shows it: (pitch, roll, bob)."""
    moving = clampf(speed / 0.3, 0.0, 1.2)
    pitch = (0.7 + 0.5 * moving) * (math.sin(tick * 0.069) * 0.7 + math.sin(tick * 0.153 + 1.1) * 0.3)
    roll = 1.0 * (math.sin(tick * 0.048 + 0.6) * 0.75 + math.sin(tick * 0.117 + 2.3) * 0.25)
    bob = 0.04 * (math.sin(tick * 0.083 + 0.3) * 0.7 + math.sin(tick * 0.19) * 0.3)
    return pitch * gain, roll * gain, bob * gain


# The first animation, in game ticks (20 to the second): the sail is let down on a fresh wind from the starboard quarter, the wind comes
# round to her beam as the yard is braced and the canvas fills, she comes up into the wind (luffing, then taken aback with the yard
# swung right round), falls off again, fills, and the sail is hauled up into its furl.
SAIL_TICKS = 280
SAIL_REL = [(0, 125.0), (30, 125.0), (70, 82.0), (84, 82.0), (132, 5.0), (160, 5.0), (230, 125.0), (300, 125.0)]
SAIL_STRENGTH = [(0, 0.6), (70, 0.7), (130, 0.9), (170, 0.9), (230, 0.65), (300, 0.6)]


def simulate_sailing():
    sail = fill = luff = heel = speed = 0.0
    brace = sail_brace(SAIL_REL[0][1])
    out = []
    for t in range(SAIL_TICKS + 1):
        rel, strength = keyed(t, SAIL_REL), keyed(t, SAIL_STRENGTH)
        order = 1.0 if 8 <= t < 222 else 0.0
        sail = approach(sail, order, 0.03 if order > sail else 0.022)
        brace = approach(brace, sail_brace(rel), 1.2)
        fill += (sail_fill(rel, sail, strength) - fill) * 0.08
        luff += (sail_luff(rel, sail) - luff) * 0.1
        heel += (sail_heel(rel, fill, strength) - heel) * 0.05
        speed += (0.36 * sail * sail_efficiency(rel) * (0.5 + 0.75 * strength) - speed) * 0.03
        out.append(dict(sail=sail, fill=fill, luff=luff, brace=brace, heelw=heel, rel=rel, wind=strength, speed=speed))
    return out


def sail_state(sim, tick):
    i = min(int(tick), len(sim) - 2)
    f = tick - i
    a, b = sim[i], sim[i + 1]
    v = {k: a[k] + (b[k] - a[k]) * f for k in a}
    pitch, roll, bob = swell(tick, v['speed'])
    return dict(sail=v['sail'], fill=v['fill'], luff=v['luff'], brace=v['brace'], windYaw=wrap180(v['rel'] + 180.0), wind=v['wind'],
                time=tick * 0.6, speed=min(v['speed'], 0.3), pitch=pitch, heel=roll + v['heelw'], bob=bob, lanterns=0.0)


# The second animation: night, the lanterns catch one after the other in the dusk-to-dark ramp the game uses, the bell is struck twice,
# the helm goes hard over to starboard and then to port and is eased back, and the anchor is let go, and weighed.
HELM_TICKS = 200
HELM_ORDERS = [(0, 0.0), (30, 1.0), (80, -1.0), (140, 0.0)]            # (tick, helm) from then on
ANCHOR_DOWN = (64, 140)                                                # anchored between these ticks
BELL_STRIKES = (12, 108)


def simulate_helm():
    helm_cmd = lambda t: [h for (t0, h) in HELM_ORDERS if t >= t0][-1]
    rudder = anchor = lit = 0.0
    out = []
    for t in range(HELM_TICKS + 1):
        to = helm_cmd(t)
        rudder = approach(rudder, to, 0.08 if to == 0.0 else 0.06)
        down = ANCHOR_DOWN[0] <= t < ANCHOR_DOWN[1]
        anchor = approach(anchor, 1.0 if down else 0.0, 0.035 if down else 0.022)
        lit = approach(lit, 1.0, 0.01)
        struck = [t - s for s in BELL_STRIKES if 0 <= t - s <= 80]
        out.append(dict(rudder=rudder, anchor=anchor, lit=lit, bell=float(min(struck)) if struck else -1.0))
    return out


def helm_state(sim, tick):
    i = min(int(tick), len(sim) - 2)
    f = tick - i
    a, b = sim[i], sim[i + 1]
    lerp = lambda k: a[k] + (b[k] - a[k]) * f
    bell = -1.0 if (a['bell'] < 0.0 or b['bell'] < 0.0) else lerp('bell')
    t = tick + 7.0
    lanterns = lerp('lit') * (0.86 + 0.08 * math.sin(t * 0.9) + 0.06 * math.sin(t * 2.3 + 1.7))
    pitch, roll, bob = swell(tick, 0.0)
    helm = lerp('rudder')
    return dict(sail=0.0, wind=0.35, windYaw=-70.0, rudder=helm * 30.0, wheel=helm * 270.0, anchor=lerp('anchor'), lanterns=lanterns, bell=bell, time=tick,
                speed=0.0, pitch=pitch, heel=roll, bob=bob, brace=24.0)


# ---- GIFs: one palette for all the frames, a dither that does not move, and only what changes drawn

def bayer(n):
    m = np.zeros((1, 1))
    while m.shape[0] < n:
        m = np.block([[4 * m, 4 * m + 2], [4 * m + 3, 4 * m + 1]])
    return (m + 0.5) / (n * n) - 0.5


def gif_palette(frames, masks, colours):
    """A palette for all of [frames]: median cut over the ship's pixels in a sample of them, with a sprinkling of the sea and sky."""
    take = []
    for i in range(0, len(frames), max(1, len(frames) // 10)):
        take += [frames[i][masks[i] > 0.02], frames[i][masks[i] <= 0.02][::13]]
    stack = np.concatenate(take, 0).astype(np.uint8)
    q = Image.fromarray(stack.reshape(1, -1, 3)).quantize(colors=colours, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    return np.array(q.getpalette()[:3 * colours], float).reshape(-1, 3)


def gif_lut(pal, bits=6):
    """For every colour of a 2^bits cube: the palette entry nearest to it (green counts most, blue least)."""
    n = 1 << bits
    step = 256 // n
    c = np.arange(n) * step + step / 2.0
    R, G, B = np.meshgrid(c, c, c, indexing='ij')
    cells = np.stack([R, G, B], -1).reshape(-1, 3)
    w = np.array([3.0, 4.0, 2.0])
    out = np.empty(len(cells), np.uint8)
    for i in range(0, len(cells), 4096):
        d = (((cells[i:i + 4096, None, :] - pal[None, :, :]) ** 2) * w).sum(-1)
        out[i:i + 4096] = d.argmin(1)
    return out.reshape(n, n, n)


def write_gif(path, frames, masks, durations, colours=254, bits=6, tol=400.0):
    """Writes [frames] ((H, W, 3) uint8 arrays) as a looping GIF. [masks] (0..1, where the ship is) steer the palette and the dither:
    the sea and sky are dithered (a gradient needs it), the ship's own pixels are not. Every frame after the first draws only the
    pixels that changed (the rest are the transparent index), so a still sea costs next to nothing; a pixel that would change by less
    than [tol] (a weighted squared distance in the palette: a few levels of one channel) is left as it was, which keeps a slowly
    changing glow from being redrawn every frame (what is on screen never drifts further than that from the picture)."""
    pal = gif_palette(frames, masks, colours)
    lut = gif_lut(pal, bits)
    H, W, _ = frames[0].shape
    T = np.tile(bayer(8), (H // 8 + 1, W // 8 + 1))[:H, :W]
    palette_bytes = bytes(np.clip(np.round(np.vstack([pal, np.zeros((256 - len(pal), 3))])), 0, 255).astype(np.uint8).ravel())
    apart = (((pal[:, None, :] - pal[None, :, :]) ** 2) * np.array([3.0, 4.0, 2.0])).sum(-1)
    ims, durs, canvas = [], [], None
    for f, m, d in zip(frames, masks, durations):
        amp = 1.0 + 7.0 * (1.0 - np.clip(m * 4.0, 0.0, 1.0))
        v = f.astype(np.float32) + (T * amp)[..., None]
        q = np.clip(v, 0, 255).astype(np.int32) >> (8 - bits)
        idx = lut[q[..., 0], q[..., 1], q[..., 2]]
        if canvas is None:
            fr, canvas = idx, idx.copy()
        else:
            same = (idx == canvas) | (apart[idx, canvas] <= tol)
            if same.all():
                durs[-1] += d
                continue
            fr = np.where(same, 255, idx).astype(np.uint8)
            canvas = np.where(same, canvas, idx)
        im = Image.fromarray(fr, 'P')
        im.putpalette(palette_bytes)
        ims.append(im)
        durs.append(d)
    ims[0].save(path, save_all=True, append_images=ims[1:], duration=durs, loop=0, disposal=1, transparency=255, optimize=False)
    return os.path.getsize(path)


# ---- the frames, drawn four at a time

_G = {}


def _render(job):
    i, kw, cam = job
    cam = dict(cam)
    fig = cam.pop('fig', False)
    st = CogState(**kw)
    im, mask = _G['P'].frame(st, extras=[_G['fig']] if fig else (), mask=True, **cam)
    return i, np.asarray(im, np.uint8), mask


def render_frames(P, jobs, workers=4):
    """[jobs] are (index, state kwargs, camera kwargs): returns their pictures and ship masks in order."""
    import multiprocessing as mp
    _G['P'] = P
    _G['fig'] = figure_scene()
    ctx = mp.get_context('fork')
    done = {}
    with ctx.Pool(workers) as pool:
        for i, im, m in pool.imap_unordered(_render, jobs, chunksize=1):
            done[i] = (im, m)
    order = sorted(done)
    return [done[i][0] for i in order], [done[i][1] for i in order]


# ---- the previews

HERO_CAM = dict(yaw=38.0, elev=17.0, scale=4.5, size=(1000, 1100), center=(0.0, -80.0, 4.0), ss=2)
# A fresh wind from the starboard quarter: the pennant streams forward and to port, the yard is braced, the sail full
UNDER_SAIL = dict(sail=1.0, fill=0.8, luff=0.0, brace=17.0, windYaw=-40.0, wind=0.65, time=30.0, speed=0.09, pitch=1.2, heel=-2.5)


def sheet_of(frames, cols, gap=4, bg=(16, 16, 20)):
    """A contact sheet of equal pictures (for looking at an animation's frames at a glance)."""
    h, w = frames[0].shape[:2]
    rows = (len(frames) + cols - 1) // cols
    out = np.zeros((rows * (h + gap) + gap, cols * (w + gap) + gap, 3), np.uint8)
    out[:] = bg
    for i, f in enumerate(frames):
        r, c = divmod(i, cols)
        out[gap + r * (h + gap):gap + r * (h + gap) + h, gap + c * (w + gap):gap + c * (w + gap) + w] = f
    return out


def write_previews(P, tex, glow, prev, say=print):
    """The hero stills, the bow with the lookout's stand-in, the turntable and the two animations."""
    os.makedirs(prev, exist_ok=True)
    t0 = time.time()
    # ---- stills, three at once
    stills = [
        (0, dict(UNDER_SAIL, lanterns=0.9), dict(HERO_CAM, sky='dusk'), 'cog_hero.png'),
        (1, dict(UNDER_SAIL, lanterns=0.0), dict(HERO_CAM, yaw=142.0, elev=15.0, sky='day'), 'cog_hero_day.png'),
        (2, dict(UNDER_SAIL, sail=0.0, fill=0.0, brace=0.0, lanterns=0.0), dict(yaw=35.0, elev=20.0, scale=11.0, size=(900, 760), center=(-3.0, -38.0, -62.0), ss=2,
                                                                                 sky='day', fig=True), 'cog_bow_lookout.png'),
    ]
    ims, _ = render_frames(P, [(i, st, cam) for i, st, cam, _n in stills], workers=3)
    for im, (_i, _st, _cam, name) in zip(ims, stills):
        Image.fromarray(im).save(os.path.join(prev, name), optimize=True)
    say(f'  stills {time.time() - t0:.0f} s')
    # ---- the turntable: a smooth sea so only the ship moves
    cam = dict(elev=16.0, scale=1.85, size=(384, 460), center=(0.0, -90.0, 2.0), ss=2, sky='day', sea_t=0.0, sea_speed=0.05, plain=True, light_follow=True)
    n = 36
    jobs = [(i, dict(UNDER_SAIL, lanterns=0.0, heel=0.0, pitch=0.0), dict(cam, yaw=20.0 + 360.0 * i / n)) for i in range(n)]
    frames, masks = render_frames(P, jobs)
    size = write_gif(os.path.join(prev, 'cog_turntable.gif'), frames, masks, [90] * n)
    say(f'  turntable {size / 1e6:.2f} MB, {n} frames, {time.time() - t0:.0f} s')
    # ---- the sail and the wind
    sim = simulate_sailing()
    per = 2.5
    n = int(SAIL_TICKS / per)
    cam = dict(yaw=36.0, elev=14.0, scale=1.78, size=(448, 456), center=(0.0, -80.0, 0.0), ss=2, sky='dusk', sea_t=0.0, sea_speed=0.06)
    jobs = [(i, dict(sail_state(sim, i * per), lanterns=0.5), cam) for i in range(n)]
    frames, masks = render_frames(P, jobs)
    dur = [80] * n
    dur[0] = dur[-1] = 400
    size = write_gif(os.path.join(prev, 'cog_sail_wind.gif'), frames, masks, dur)
    Image.fromarray(sheet_of([frames[int(k * n / 12)] for k in range(12)], 4)).save(os.path.join(prev, 'cog_sail_wind_sheet.png'), optimize=True)
    say(f'  sail and wind {size / 1e6:.2f} MB, {n} frames, {time.time() - t0:.0f} s')
    # ---- the helm, the anchor, the bell and the lanterns at night
    sim = simulate_helm()
    per = 2.0
    n = int(HELM_TICKS / per)
    cam = dict(yaw=145.0, elev=24.0, scale=3.1, size=(480, 440), center=(0.0, -30.0, 2.0), ss=2, sky='night', sea_t=0.0, sea_speed=0.0)
    jobs = [(i, helm_state(sim, i * per), cam) for i in range(n)]
    frames, masks = render_frames(P, jobs)
    dur = [80] * n
    dur[0] = dur[-1] = 400
    size = write_gif(os.path.join(prev, 'cog_helm_anchor_night.gif'), frames, masks, dur)
    Image.fromarray(sheet_of([frames[int(k * n / 12)] for k in range(12)], 4)).save(os.path.join(prev, 'cog_helm_anchor_night_sheet.png'), optimize=True)
    say(f'  helm, anchor, bell, lanterns {size / 1e6:.2f} MB, {n} frames, {time.time() - t0:.0f} s')
    # ---- the texture and its glow map
    checker = np.indices(tex.shape[:2]).sum(0) // 16 % 2
    back = np.where(checker[..., None] == 0, 52, 66).astype(np.float32)
    a = tex[..., 3:4].astype(np.float32) / 255.0
    left = (tex[..., :3] * a + back * (1.0 - a)).astype(np.uint8)
    ga = glow[..., 3:4].astype(np.float32) / 255.0
    right = np.clip(glow[..., :3] * ga + 8, 0, 255).astype(np.uint8)
    Image.fromarray(np.concatenate([left, right], 1)).save(os.path.join(prev, 'cog_texture.png'), optimize=True)


def write_all(out, prev, art, kdir=None, previews=True):
    """Everything: the texture and glow map under [out]/textures/entity/ship, the item, CogModel.kt in [kdir], the .bbmodel in [art],
    the part list, the extent report and the previews in [prev]. Returns the list of problems (empty when all is well)."""
    import ship_item
    t0 = time.time()
    kdir = kdir or DEFAULT_KOTLIN_DIR
    model, scene, tex, glow = painted()
    problems, used = check_atlas(scene)
    ent = os.path.join(out, 'textures', 'entity', 'ship')
    os.makedirs(ent, exist_ok=True)
    os.makedirs(prev, exist_ok=True)
    os.makedirs(art, exist_ok=True)
    os.makedirs(kdir, exist_ok=True)
    img = Image.fromarray(tex, 'RGBA')
    img.save(os.path.join(ent, 'cog.png'), optimize=True)
    Image.fromarray(glow, 'RGBA').save(os.path.join(ent, 'cog_glow.png'), optimize=True)
    lens = {p.name: p.cubes[0].size[1] for p in model.all_parts() if p.name.startswith(('brace_', 'lift_'))}
    with open(os.path.join(kdir, 'CogModel.kt'), 'w') as f:
        f.write(kotlin_source(model))
    with open(os.path.join(prev, 'cog_parts.txt'), 'w') as f:
        f.write('\n'.join(p.name for p in model.all_parts()) + '\n')
    bb = model.bbmodel(img, 'cog')
    bb['textures'][0]['width'] = model.tex_w * HD
    bb['textures'][0]['height'] = model.tex_h * HD
    with open(os.path.join(art, 'cog.bbmodel'), 'w') as f:
        f.write(deterministic_uuids(json.dumps(bb)))
    item = ship_item.write_item(out)
    ship_item.preview(item, prev)
    report = extent_report(scene, lens) + '\n' + spot_report(scene)
    with open(os.path.join(prev, 'cog_extent.txt'), 'w') as f:
        f.write(report)
    print(report)
    parts, cubes = len(list(model.all_parts())), len(list(model.all_cubes()))
    print(f'{parts} parts, {cubes} cubes, {used} texels on faces of {TEX_W * HD * TEX_H * HD}, painted in {time.time() - t0:.0f} s')
    for p in problems:
        print('PROBLEM', p)
    if previews:
        P = Preview(scene, tex, glow, lens)
        write_previews(P, tex, glow, prev, say=lambda s: print(s, flush=True))
    return problems


if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(args) < 3:
        raise SystemExit('usage: ship.py <assets dir> <preview dir> <art/models dir> [kotlin dir] [--no-previews]')
    sys.exit(1 if write_all(args[0], args[1], args[2], args[3] if len(args) > 3 else None, previews='--no-previews' not in sys.argv) else 0)
