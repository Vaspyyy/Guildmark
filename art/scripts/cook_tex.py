"""The block textures of Hearthcraft's stations (cook_art.py), 32x32, in the mod's HD look (hdkit.py): the materials the
models are built of (iron, brick, firestone, bark, charred wood, ash, cord, chain, brass, enamel, slate, boards, slats) and
the few made for one part (the oven door, the coal bed, the flames, the soot).

Each painter returns a PIL image (an animated texture, a strip of frames), seeded, so a rerun gives the same pixels. Two texels
to a model pixel; textures that run on from one element to the next (brick, stone, wood) are drawn to tile at the edge.
"""
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from hdkit import PAL, Canvas, grid, vnoise, fbm, rand, bands, light, cavity, seg_dist, edge

T = 32
ALL = np.ones((T, T), bool)

# ---------------------------------------------------------------- tones (darkest first)
IRON = [(12, 12, 17), (24, 25, 32), (42, 44, 52), (64, 66, 74), (94, 96, 102), (136, 136, 138)]            # black wrought iron
IRON_SOOT = [(8, 8, 11), (16, 16, 21), (28, 29, 35), (42, 43, 50), (62, 63, 70), (92, 92, 98)]
BRICK = [(28, 17, 17), (58, 33, 29), (92, 52, 40), (124, 74, 54), (152, 96, 68), (182, 124, 90)]           # hearth brick, red-brown
MORTAR = [(34, 30, 30), (58, 52, 48), (84, 76, 68), (112, 102, 90)]
CLAY = [(44, 30, 24), (88, 58, 40), (128, 88, 58), (166, 120, 80), (198, 150, 104), (224, 184, 136)]        # the oven's clay brick, ochre
STONE = [(22, 22, 27), (40, 39, 44), (60, 58, 60), (84, 80, 78), (110, 104, 98), (140, 132, 122)]
BARK = [(18, 14, 14), (34, 26, 22), (52, 40, 32), (72, 58, 46), (94, 78, 62), (118, 100, 80)]
LOGB = [(16, 11, 10), (38, 26, 20), (62, 44, 31), (90, 66, 46), (122, 92, 64), (156, 124, 90)]        # a burning log: brown wood under black char
CHAR = [(8, 7, 8), (17, 15, 16), (30, 27, 27), (46, 41, 40), (66, 60, 56), (92, 85, 78)]
ASH = [(26, 24, 26), (50, 47, 48), (80, 76, 74), (112, 107, 102), (146, 140, 132), (178, 172, 162)]
ROPE = [(52, 40, 26), (92, 72, 46), (128, 104, 68), (160, 134, 92), (190, 164, 118), (214, 192, 146)]
ENAMEL = [(70, 74, 82), (116, 122, 128), (164, 168, 168), (200, 200, 192), (226, 224, 212), (244, 242, 230)]
ENAMEL_BLUE = [(18, 28, 48), (30, 48, 82), (46, 72, 116), (70, 104, 148), (104, 140, 178)]
PINE = [(36, 27, 24), (68, 53, 41), (102, 82, 60), (136, 110, 80), (168, 140, 104), (196, 170, 130)]
MAPLE = [(58, 42, 30), (104, 78, 52), (148, 116, 80), (184, 152, 108), (210, 182, 136), (232, 210, 166)]  # a chopping board
STONEWARE = [(48, 50, 58), (92, 94, 102), (140, 142, 144), (184, 184, 178), (214, 212, 202), (238, 234, 222)]
GLAZE_BLUE = [(20, 30, 52), (34, 52, 88), (54, 80, 120), (84, 114, 152)]
SLATE = PAL['slate']
BRASS = PAL['brass']
EMBER = PAL['ember']
GLOW = [(120, 28, 12), (196, 64, 20), (236, 110, 34), (252, 166, 66), (255, 214, 120), (255, 242, 190)]


def _cv(t=None):
    return Canvas(T)


def _wrap_vor(n, seed, w=T):
    """Nearest and second nearest of [n] random points on a wrapping w x w grid: distances, the owner and the points."""
    xs, ys = grid(w)
    rng = np.random.default_rng(seed)
    pts = rng.uniform(0, w, (n, 2))
    best = np.full((w, w), 1e9)
    second = np.full((w, w), 1e9)
    owner = np.zeros((w, w), int)
    for i, (px, py) in enumerate(pts):
        for ox in (-w, 0, w):
            for oy in (-w, 0, w):
                d = np.hypot(xs - px - ox, ys - py - oy)
                closer = d < best
                second = np.where(closer, best, np.minimum(second, d))
                owner = np.where(closer, i, owner)
                best = np.where(closer, d, best)
    return best, second, owner, pts


def _jit(x, y, seed):
    """A repeatable hash of a pixel in 0..1."""
    h = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)
    h = ((h ^ (h >> 13)) * 0x5BD1E995) & 0xFFFFFFFF
    return (h % 1000) / 1000.0


# ================================================================ iron

def iron(seed=301, rust=0.0, hammered=True, pal=IRON, dark=0.0):
    """Black wrought iron, its face dented by the hammer and catching the light on the dents' rims."""
    n = fbm(T, T, 4, seed)
    H = n * 1.4 + (vnoise(T, T, 3, seed + 1) - 0.5) * (1.6 if hammered else 0.4)
    t = 0.52 - dark + 0.22 * (n - 0.5) + 1.1 * light(H, 1.3)
    cv = Canvas(T)
    cv.put(ALL, bands(pal, t, 0.3))
    if rust:
        hollow = cavity(H, 2) > 0.05
        bloom = (fbm(T, T, 5, seed + 3) > 0.58) | hollow
        cv.over(bloom & (rand(T, T, seed + 5) > 0.35), (104, 58, 34), rust)
    return cv.image()


IRONB = [(18, 19, 26), (38, 40, 50), (64, 67, 78), (94, 98, 110), (130, 134, 146), (174, 176, 184)]   # the pot's iron: bluer, a good deal brighter


def pot_iron(seed=301):
    """Cast iron for the pot: the same hammered look, lighter and bluer than the fittings so the pot is the brightest thing in the fire,
    and the soot has something to darken."""
    n = fbm(T, T, 4, seed)
    H = n * 1.4 + (vnoise(T, T, 3, seed + 1) - 0.5) * 1.3
    t = 0.52 + 0.2 * (n - 0.5) + 1.0 * light(H, 1.3)
    cv = Canvas(T)
    cv.put(ALL, bands(IRONB, t, 0.3))
    return cv.image()


def iron_inside(seed=311):
    """The pot's inside: iron gone black from the fire and the stew, a dull brown crust in the hollows."""
    cv = Canvas.of(iron(seed, hammered=False, pal=IRON_SOOT))
    cv.over(fbm(T, T, 5, seed + 2) > 0.55, (44, 28, 18), 0.5)
    cv.rgb *= 0.75
    return cv.image()


def iron_band(seed=321):
    """Wrought iron strap work: a horizontal band at the top rows with a row of rivets, plain iron under it (for the pot's rim, the oven
    door's frame)."""
    cv = Canvas.of(iron(seed))
    xs, ys = grid(T)
    band = ys < 5
    cv.over(band, IRON[4], 0.25)
    cv.over(ys == 5.5, IRON[0], 0.6)
    for x in range(3, T, 8):
        cv.px(x, 2, IRON[5])
        cv.px(x + 1, 2, IRON[3])
        cv.px(x, 3, IRON[3])
        cv.px(x + 1, 3, IRON[1])
    return cv.image()


def brass(seed=331):
    """Hammered brass: warm, dented, bright on the dents' rims."""
    n = fbm(T, T, 5, seed)
    H = n * 1.2 + (vnoise(T, T, 3, seed + 1) - 0.5) * 1.1
    t = 0.5 + 0.2 * (n - 0.5) + 0.9 * light(H, 1.3)
    cv = Canvas(T)
    cv.put(ALL, bands(BRASS, t, 0.3))
    return cv.image()


# ================================================================ masonry

def bricks(pal, mortar, seed, bw=16, bh=8, soot=0.0, chips=0.04, mortar_w=1):
    """Courses of bricks [bh] texels high and [bw] long, laid in running bond, each its own tone, lit on its top and left edges and
    shaded on its bottom and right, a recessed mortar line between; a few chipped corners and a bloom of soot from the bottom."""
    xs, ys = grid(T)
    row = np.floor(ys / bh).astype(int)
    off = (row % 2) * (bw // 2)
    px = np.floor(xs).astype(int)
    py = np.floor(ys).astype(int)
    bx = (px + off) % bw
    by = py % bh
    bid = ((px + off) // bw) % (T // bw) + row * 5
    rng = np.random.default_rng(seed)
    tone_of = rng.uniform(-0.16, 0.16, 64)
    n = fbm(T, T, 4, seed + 1)
    fine = rand(T, T, seed + 2)
    t = 0.52 + tone_of[bid % 64] + 0.16 * (n - 0.5) + 0.07 * (fine - 0.5)
    lit = (by < 2) | (bx < 1)
    dim = (by >= bh - 2) | (bx >= bw - 1)
    t = np.where(lit & ~((by >= bh - mortar_w) | (bx < mortar_w)), t + 0.09, t)
    t = np.where(dim & ~((by >= bh - mortar_w) | (bx < mortar_w)), t - 0.1, t)
    mort = (by >= bh - mortar_w) | (bx < mortar_w)
    cv = Canvas(T)
    cv.put(ALL, bands(pal, t, 0.22))
    mt = 0.35 + 0.35 * fbm(T, T, 3, seed + 3) + 0.15 * (fine - 0.5)
    cv.put(mort, bands(mortar, mt, 0.2))
    # Chipped corners: a dark texel or two cut out of a brick's corner
    chip = (rand(T, T, seed + 4) < chips) & ~mort
    cv.over(chip, pal[0], 0.7)
    if soot:
        sv = np.clip((ys / T) * 1.2 - 0.1, 0, 1) ** 1.5 * soot * (0.6 + 0.8 * fbm(T, T, 6, seed + 5))
        cv.over(ALL, (10, 9, 10), np.clip(sv, 0, 0.8))
    return cv.image()


def hearth_brick(seed=401):
    return bricks(BRICK, MORTAR, seed)


def clay_brick(seed=411):
    return bricks(CLAY, MORTAR, seed, chips=0.03)


def clay_sooty(seed=421):
    """The oven's clay brick blackened round the firebox mouth and flue: soot climbing from the bottom of the texture."""
    return bricks(CLAY, MORTAR, seed, soot=1.0)


def firestone(seed=431):
    """Fire-stones: rounded river cobbles, each domed and lit on its upper left, a dark gap between, blackened by the fire (the grey
    gone brown-black toward the bottom)."""
    best, second, owner, pts = _wrap_vor(11, seed)
    edge_d = second - best
    rng = np.random.default_rng(seed + 1)
    tilt = rng.uniform(-1, 1, (len(pts), 2))
    xs, ys = grid(T)
    H = np.clip(edge_d, 0, 3.6) ** 0.8 * 1.3 + 0.07 * (tilt[owner, 0] * (xs % 9) + tilt[owner, 1] * (ys % 9))
    lit = light(H, 1.5)
    n = fbm(T, T, 4, seed + 2)
    own_tone = rng.uniform(-0.1, 0.1, len(pts))[owner]
    t = 0.74 + own_tone + lit * 1.0 + 0.2 * (n - 0.5)
    t = np.where(edge_d < 0.9, t - 0.3, t)
    cv = Canvas(T)
    cv.put(ALL, bands(STONE, t, 0.25))
    # Soot: the fire's brown-black, thicker lower down and on some stones more than others
    sv = np.clip(ys / T * 1.4 - 0.2, 0, 1) * (0.3 + 0.5 * rng.uniform(0, 1, len(pts))[owner]) * (0.7 + 0.6 * n)
    cv.over(ALL, (30, 22, 18), np.clip(sv, 0, 0.3))
    # A few flecks of quartz
    f = rand(T, T, seed + 3) > 0.985
    cv.over(f, (176, 170, 158), 0.6)
    return cv.image()


def flagstone(seed=441):
    """Hearth paving: irregular flagstones with narrow joints, worn smooth, ash in the joints."""
    best, second, owner, pts = _wrap_vor(7, seed)
    edge_d = second - best
    rng = np.random.default_rng(seed + 1)
    xs, ys = grid(T)
    n = fbm(T, T, 6, seed + 2)
    own = rng.uniform(-0.12, 0.12, len(pts))[owner]
    H = np.clip(edge_d, 0, 1.6) * 0.5 + 0.05 * n
    t = 0.6 + own + 0.2 * (n - 0.5) + 0.6 * light(H, 1.2)
    t = np.where(edge_d < 0.8, t - 0.32, t)
    cv = Canvas(T)
    cv.put(ALL, bands(STONE, t, 0.25))
    cv.over(rand(T, T, seed + 3) > 0.95, ASH[3], 0.5)
    return cv.image()


# ================================================================ wood

def bark(seed=451):
    """Rough bark on a straight stick: ridges running up and down, lit on their left, dark fissures between."""
    xs, ys = grid(T)
    wv = fbm(T, T, 8, seed) * 4.0
    ridge = np.sin((xs + wv) * 1.55)
    t = 0.46 + 0.2 * ridge + 0.12 * (fbm(T, T, 5, seed + 1) - 0.5) + 0.08 * (vnoise(T, T, 2, seed + 2) - 0.5)
    fiss = (np.abs(ridge + 0.9) < 0.12) & (fbm(T, T, 9, seed + 3) > 0.35)
    t = np.where(fiss, t - 0.3, t)
    cv = Canvas(T)
    cv.put(ALL, bands(BARK, t, 0.25))
    return cv.image()


def charred_log(seed=461):
    """A charred log's side: black char broken into scaled checks, grey ash dust on the lit upper left, the pale wood showing in a few cracks."""
    best, second, owner, pts = _wrap_vor(26, seed)
    edge_d = second - best
    xs, ys = grid(T)
    rng = np.random.default_rng(seed + 1)
    # The checks are long in the grain's direction (across the texture)
    H = np.clip(edge_d, 0, 2.2) * 0.9 + 0.1 * fbm(T, T, 3, seed + 2)
    t = 0.5 + rng.uniform(-0.1, 0.1, len(pts))[owner] + 0.9 * light(H, 1.4) + 0.12 * (fbm(T, T, 4, seed + 3) - 0.5)
    t = np.where(edge_d < 0.8, t - 0.22, t)
    cv = Canvas(T)
    cv.put(ALL, bands(LOGB, t, 0.22))
    # Black char over a good part of it, in patches
    patch = fbm(T, T, 6, seed + 6)
    cv.over(patch > 0.52, CHAR[1], np.clip((patch - 0.52) * 5.0, 0, 0.75))
    dust = (fbm(T, T, 6, seed + 4) > 0.5) & (rand(T, T, seed + 5) > 0.3)
    cv.over(dust, ASH[3], 0.45)
    return cv.image()


def charred_end(seed=471):
    """The sawn end of a burnt log: black rings round a grey-brown heart, split by a check or two."""
    xs, ys = grid(T)
    r = np.hypot(xs - 16, ys - 16)
    ang = np.arctan2(ys - 16, xs - 16)
    t = 0.3 + 0.1 * np.sin(r * 1.5 + fbm(T, T, 8, seed) * 4) + 0.1 * (fbm(T, T, 3, seed + 1) - 0.5) - 0.004 * r
    rng = np.random.default_rng(seed)
    for _ in range(2):
        a0 = rng.uniform(-math.pi, math.pi)
        da = np.angle(np.exp(1j * (ang - a0)))
        t = np.where((np.abs(da) < 0.09) & (r > 2), t - 0.25, t)
    t = np.where(r > 13.5, t - 0.2, t)
    cv = Canvas(T)
    cv.put(ALL, bands(CHAR, t, 0.25))
    return cv.image()


def log_glow(seed=481):
    """What glows in a burning log: the cracks of its char, orange and gold, the rest see-through (laid over the log, bright at night)."""
    best, second, owner, pts = _wrap_vor(26, 461)           # the same checks as charred_log
    edge_d = second - best
    xs, ys = grid(T)
    n = fbm(T, T, 4, seed)
    crack = edge_d < 1.0
    patch = fbm(T, T, 9, seed + 7)
    heat = np.clip(1.0 - edge_d / 1.2, 0, 1) * (0.55 + 0.9 * n) * np.clip((patch - 0.38) * 5.0, 0, 1)
    cv = Canvas(T)
    m = crack & (heat > 0.18)
    cv.put(m, bands(GLOW, np.clip(0.15 + heat * 0.95, 0, 1), 0.2))
    cv.a[~m] = 0
    return cv.image()


def darkwood(seed=491, vertical=True):
    """Dark stained planks with the grain up and down, a lit edge and a dark seam between boards, a knot or two and nail heads."""
    xs, ys = grid(T)
    a, b = (xs, ys) if vertical else (ys, xs)
    height = 8
    row = np.floor(a / height)
    within = a - row * height
    wv = fbm(T, T, 10, seed) * 4.0
    t = 0.45 + 0.16 * np.sin((a + wv) * 1.9) * 0.5 + 0.12 * (fbm(T, T, 5, seed + 1) - 0.5) + 0.07 * np.sin(row * 4.7 + seed)
    t = np.where(within < 1.0, t + 0.1, t)
    t = np.where(within >= height - 1.0, t - 0.34, t)
    rng = np.random.default_rng(seed)
    for _ in range(2):
        ka, kb = rng.uniform(4, T - 4), rng.uniform(4, T - 4)
        d = np.hypot(a - ka, (b - kb) * 0.55)
        t = np.where(d < 1.2, t - 0.28, np.where(d < 2.2, t + 0.05 * np.cos(d * 4), t))
    cv = Canvas(T)
    cv.put(ALL, bands(PAL['darkwood'], t, 0.25))
    for k in range(0, 4):
        for yy in (3, 27):
            x, y = (k * height + 3, yy) if vertical else (yy, k * height + 3)
            cv.px(x, y, IRON[4])
            cv.px(x + 1, y, IRON[2])
            cv.px(x, y + 1, IRON[2])
            cv.px(x + 1, y + 1, IRON[1])
    return cv.image()


def planks(seed=501, vertical=False):
    """Pine planks, a little paler and warmer than the dark wood, worn where hands go (counters, shelves, the board's frame)."""
    xs, ys = grid(T)
    a, b = (xs, ys) if vertical else (ys, xs)
    height = 8
    row = np.floor(a / height)
    within = a - row * height
    wv = fbm(T, T, 10, seed) * 4.0
    t = 0.5 + 0.12 * np.sin((b * 0 + a + wv) * 0.0) + 0.14 * np.sin(((b if True else a) + wv) * 0.0)
    along = b
    t = 0.5 + 0.1 * np.sin(along * 0.3 + a * 2.2 + wv) + 0.14 * (fbm(T, T, 5, seed + 1) - 0.5) + 0.07 * np.sin(row * 4.7 + seed)
    t = np.where(within < 1.0, t + 0.11, t)
    t = np.where(within >= height - 1.0, t - 0.3, t)
    cv = Canvas(T)
    cv.put(ALL, bands(PINE, t, 0.25))
    cv.over(fbm(T, T, 8, seed + 2) > 0.62, PINE[5], 0.25)
    return cv.image()


def board(seed=511):
    """A chopping board: pale maple scarred with knife cuts criss-crossing it, a worn hollow in the middle, a dark grain line or two."""
    xs, ys = grid(T)
    n = fbm(T, T, 6, seed)
    t = 0.62 + 0.14 * (n - 0.5) + 0.05 * np.sin(xs * 1.1 + n * 3.0)
    rng = np.random.default_rng(seed)
    cv = Canvas(T)
    cv.put(ALL, bands(MAPLE, t, 0.25))
    # Knife cuts: thin pale-edged dark scars at slants
    for _ in range(26):
        x0, y0 = rng.uniform(0, T), rng.uniform(0, T)
        ang = rng.choice([0.2, 0.5, 1.2, 1.9, 2.6, 3.0]) + rng.normal(0, 0.1)
        L = rng.uniform(4, 12)
        x1, y1 = x0 + math.cos(ang) * L, y0 + math.sin(ang) * L
        for ox in (-T, 0, T):
            for oy in (-T, 0, T):
                d, f = seg_dist(x0 + ox, y0 + oy, x1 + ox, y1 + oy, T)
                m = d < 0.55
                if m.any():
                    cv.over(m, MAPLE[1], 0.55)
                    m2 = (d >= 0.55) & (d < 1.05)
                    cv.over(m2, MAPLE[5], 0.25)
    # A juice groove round the edge
    edge_m = (xs < 2) | (xs > T - 2) | (ys < 2) | (ys > T - 2)
    cv.over(edge_m, MAPLE[1], 0.25)
    return cv.image()


def slats(seed=521):
    """Larder slats: pale pine boards 3 texels wide set on edge with a 1-texel gap between them (the gap see-through), grain along them."""
    xs, ys = grid(T)
    pitch = 4
    a = np.floor(xs).astype(int) % pitch
    wv = fbm(T, T, 8, seed) * 3.0
    t = 0.5 + 0.1 * np.sin(ys * 0.25 + wv) + 0.14 * (fbm(T, T, 5, seed + 1) - 0.5) + 0.07 * np.sin(np.floor(xs / pitch) * 3.3 + seed)
    t = np.where(a == 0, t + 0.1, t)
    t = np.where(a == 2, t - 0.12, t)
    cv = Canvas(T)
    cv.put(ALL, bands(PINE, t, 0.25))
    cv.a[a == 3] = 0
    return cv.image()


def slats_h(seed=531):
    """The same slats laid across (for the shelves' fronts and the door): rows 3 texels high, a see-through gap under each."""
    im = slats(seed)
    return im.transpose(Image.ROTATE_90).transpose(Image.FLIP_TOP_BOTTOM)


def rope(seed=541):
    """Twisted twine: three strands laid in a slant, each lit along its top, a few loose fibres."""
    xs, ys = grid(T)
    u = (ys * 0.5 + xs * 0.75) % 4.0
    t = 0.62 - 0.3 * np.abs(u - 2.0) / 2.0 + 0.08 * (fbm(T, T, 3, seed) - 0.5)
    t = np.where(u < 0.6, t - 0.22, t)
    cv = Canvas(T)
    cv.put(ALL, bands(ROPE, t, 0.25))
    cv.over(rand(T, T, seed + 1) > 0.95, ROPE[5], 0.6)
    return cv.image()


def chain(seed=551):
    """A chain, four texels wide at the left of the texture and tiling up and down: links 8 texels long, one flat to the eye (an
    open oval) and the next edge-on (a thin bar), the rest see-through."""
    cv = Canvas(T)
    for k in range(4):
        y0 = k * 8
        # The flat link: an oval ring 4 wide, 7 high; its hole 2 x 4
        for yy in range(7):
            for xx in range(4):
                ring_ = (xx in (0, 3)) or (yy in (0, 6))
                if (xx in (0, 3)) and (yy in (0, 6)):
                    continue
                if ring_:
                    lit = (xx == 0) or (yy == 0)
                    cv.px(xx, y0 + yy, IRONB[4] if lit else IRONB[2])
        # The edge-on link, between: a bar 2 wide passing through the flat link's ends, with the dark of its inside
        for yy in (6, 7):
            cv.px(1, (y0 + yy) % T, IRONB[3])
            cv.px(2, (y0 + yy) % T, IRONB[1])
    return cv.image()


def pegs(seed=561):
    """A plain dark iron plate with a row of rivets, for small iron fittings."""
    return iron(seed)


def enamel(seed=571):
    """Enamelled iron, a cream glaze (for the spit's drip tray) chipped to black iron along the edges, a rust-brown stain of fat and a blue rim
    band across the top four rows."""
    xs, ys = grid(T)
    n = fbm(T, T, 5, seed)
    t = 0.72 + 0.12 * (n - 0.5) + 0.04 * (rand(T, T, seed + 1) - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(ENAMEL, t, 0.25))
    band = ys < 5
    bt = 0.5 + 0.2 * (n - 0.5) + 0.2 * (ys < 1.5) - 0.2 * ((ys > 3.5) & band)
    cv.put(band, bands(ENAMEL_BLUE, bt, 0.2))
    chip = (rand(T, T, seed + 2) > 0.975) | ((fbm(T, T, 4, seed + 3) > 0.7) & (rand(T, T, seed + 4) > 0.6) & ((xs < 3) | (xs > T - 3)))
    cv.over(chip & ~band, IRON[1], 0.9)
    cv.over(fbm(T, T, 6, seed + 5) > 0.6, (96, 64, 34), 0.3)
    return cv.image()


def slate(seed=581):
    """Slate, dark blue-grey and cleanly wiped: faint smears of old chalk and a fine grain; the ledger's text is the renderer's."""
    xs, ys = grid(T)
    n = fbm(T, T, 6, seed)
    t = 0.32 + 0.1 * (n - 0.5) + 0.04 * np.sin(xs * 0.3 + ys * 0.8 + n * 4)
    cv = Canvas(T)
    cv.put(ALL, bands(SLATE, t, 0.3))
    wipe = (fbm(T, T, 7, seed + 1) > 0.55)
    cv.over(wipe, SLATE[4], 0.2)
    cv.over(rand(T, T, seed + 2) > 0.985, SLATE[5], 0.5)
    return cv.image()


def chalk(seed=591):
    xs, ys = grid(T)
    t = 0.8 + 0.12 * (fbm(T, T, 3, seed) - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(ENAMEL, t, 0.2))
    return cv.image()


def stoneware(seed=601):
    """Glazed stoneware seen from above (the plate's top, its middle at the texture's middle): a cream well, a blue ring where the well
    meets the rim, a dark glazed rim; and the same at its sides along the bottom rows."""
    xs, ys = grid(T)
    r = np.hypot(xs - 16, ys - 16)
    n = fbm(T, T, 6, seed)
    well = np.clip(r / 11.0, 0, 1)
    t = 0.7 - 0.16 * well ** 2 + 0.08 * (n - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(STONEWARE, t, 0.25))
    ring_blue = (r > 9.2) & (r < 10.8)
    cv.put(ring_blue, bands(GLAZE_BLUE, 0.45 + 0.4 * (xs < 16) * (ys < 16) * 0 + 0.3 * (n - 0.5) - 0.2 * ((xs + ys) > 32), 0.2))
    rim = r >= 10.8
    cv.put(rim, bands(STONEWARE, 0.4 + 0.1 * (n - 0.5) + 0.25 * light(np.clip(r - 10.8, 0, 3), 1.0) * 0 - 0.06 * ((xs + ys) > 32), 0.2))
    cv.over((r > 15.0), STONEWARE[0], 0.9)
    cv.a[r > 15.5] = 0
    return cv.image()


# ================================================================ fire

def ember_bed(seed=611, frames=6):
    """A bed of coals, 32x32 a frame, [frames] frames in a strip: black lumps with their cracks glowing, the glow breathing; each lump has its
    own phase. Glow tones are the ember palette; the lumps are charcoal and ash grey."""
    best, second, owner, pts = _wrap_vor(20, seed)
    edge_d = second - best
    xs, ys = grid(T)
    rng = np.random.default_rng(seed + 1)
    H = np.clip(edge_d, 0, 3.0) * 0.8
    lit = light(H, 1.5)
    n = fbm(T, T, 3, seed + 2)
    phase = rng.uniform(0, 2 * math.pi, len(pts))[owner]
    seam = np.clip(1.0 - edge_d / 1.15, 0, 1)
    out = Image.new('RGBA', (T, T * frames))
    for k in range(frames):
        a = 2 * math.pi * k / frames
        breath = 0.55 + 0.45 * np.sin(a + phase) * 0.9
        heat = np.clip(seam * (0.5 + 0.9 * n) * breath, 0, 1)
        # The lump tops: dark, with the glow lapping up their sides
        base = 0.25 + 0.7 * lit + 0.12 * (n - 0.5)
        cv = Canvas(T)
        cv.put(ALL, bands(CHAR, base, 0.22))
        hot = heat > 0.2
        col = bands(GLOW, np.clip(0.12 + heat * 1.0, 0, 1), 0.2)
        cv.put(hot, col)
        # A pale ash dust on the lumps' tops
        cv.over((rand(T, T, seed + 3) > 0.96) & ~hot, ASH[3], 0.5)
        out.paste(cv.image(), (0, k * T))
    return out


def ember_bed_cold(seed=621):
    """The same bed gone out: charcoal and grey ash, no glow."""
    best, second, owner, pts = _wrap_vor(20, 611)
    edge_d = second - best
    H = np.clip(edge_d, 0, 3.0) * 0.8
    n = fbm(T, T, 3, 613)
    t = 0.22 + 0.8 * light(H, 1.5) + 0.12 * (n - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(CHAR, t, 0.22))
    cv.over((fbm(T, T, 6, seed) > 0.55) & (rand(T, T, seed + 1) > 0.3), ASH[3], 0.5)
    return cv.image()


def ash(seed=631):
    """An ash bed: a pale grey powder with charcoal flecks and a few cold lumps, rippled where it was raked."""
    xs, ys = grid(T)
    n = fbm(T, T, 5, seed)
    t = 0.5 + 0.2 * (n - 0.5) + 0.05 * np.sin(xs * 0.6 + ys * 0.4 + n * 5)
    cv = Canvas(T)
    cv.put(ALL, bands(ASH, t, 0.28))
    r = rand(T, T, seed + 1)
    cv.over(r > 0.93, CHAR[1], 0.8)
    cv.over(r < 0.03, ASH[5], 0.6)
    return cv.image()


FLAME_BOX = (4, 16, 28, 32)      # where the flames are in each 32x32 frame: columns 4 to 28, rows 16 to 32 (a 12 x 8 px quad at two texels a pixel)
FLAME_UV = (2, 8, 14, 16)        # the uv of that box


def flame(seed=641, frames=8):
    """A fire's flames, 32x32 a frame, [frames] frames in a strip, drawn in the 24 x 16 texels FLAME_BOX of each frame (a quad 12 x 8 px
    shows just that, FLAME_UV): five tongues swaying and flickering, gold at the root and core, orange and red toward the tips; a hard edge
    (see-through or solid, so the cutout draws them) with a dithered fringe."""
    W, H = 24, 16
    xs, ys = grid(W, H)
    tongues = [(3.6, 9.5, 0.0), (8.2, 13.5, 0.9), (12.0, 15.8, 1.7), (15.8, 12.5, 2.6), (20.4, 9.0, 3.4)]   # x, height, phase
    out = Image.new('RGBA', (T, T * frames))
    for k in range(frames):
        a = 2 * math.pi * k / frames
        heat = np.zeros((H, W))
        mask = np.zeros((H, W), bool)
        for (cx, h, ph) in tongues:
            hh = h * (0.84 + 0.16 * math.sin(a + ph))
            sway = 1.3 * math.sin(a + ph * 1.3)
            yb = H - 0.5
            u = np.clip((yb - ys) / hh, 0, 1)                      # 0 at the root, 1 at the tip
            width = 3.3 * (1 - u) ** 0.75 * (0.6 + 0.4 * np.sin(np.clip(u * math.pi * 0.9 + 0.7, 0, math.pi)))
            centre = cx + sway * u ** 1.4 + 0.9 * np.sin(u * 5 + a + ph) * u
            m = (np.abs(xs - centre) < width) & (u < 1.0)
            core = np.clip(1 - np.abs(xs - centre) / np.maximum(width, 0.1), 0, 1)
            heat = np.maximum(heat, np.where(m, (1 - u) * 0.7 + core * 0.4, 0))
            mask |= m
        fr = rand(W, H, seed + k) > 0.6
        mask &= ~(edge(mask) & fr)
        cv = Canvas(W, H)
        cv.put(mask, bands(GLOW, np.clip(0.1 + heat * 1.1, 0, 1), 0.3))
        cv.a[~mask] = 0
        frame = Image.new('RGBA', (T, T), (0, 0, 0, 0))
        frame.paste(cv.image(), (FLAME_BOX[0], FLAME_BOX[1]))
        out.paste(frame, (0, k * T))
    return out


def glow_window(seed=651, frames=4):
    """The oven door's window when the oven is hot: the fire's orange seen through four small panes, breathing."""
    xs, ys = grid(T)
    n = fbm(T, T, 4, seed)
    out = Image.new('RGBA', (T, T * frames))
    for k in range(frames):
        a = 2 * math.pi * k / frames
        heat = 0.55 + 0.25 * np.sin(a + n * 6) + 0.2 * (n - 0.5)
        centre = 1 - np.clip(np.hypot(xs - 16, ys - 16) / 18, 0, 1)
        t = np.clip(0.3 + heat * 0.5 + centre * 0.35, 0, 1)
        cv = Canvas(T)
        cv.put(ALL, bands(GLOW, t, 0.25))
        out.paste(cv.image(), (0, k * T))
    return out


def dark_window(seed=661):
    """The window cold: sooty dark glass with one pale reflection."""
    xs, ys = grid(T)
    n = fbm(T, T, 4, seed)
    t = 0.18 + 0.1 * (n - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(IRON_SOOT, t, 0.3))
    cv.over((np.abs(xs - ys + 6) < 1.2) & (xs < 16), IRON[4], 0.5)
    return cv.image()


# ================================================================ soot, for the pot and the burnt look

def soot(stage, seed=671):
    """Soot stained over a surface (transparent where clean): [stage] 1 to 3 climbing from the bottom of the texture in streaks, and at 3
    a near-black crust with the iron just showing at the top."""
    xs, ys = grid(T)
    n = fbm(T, T, 5, seed)
    streak = vnoise(T, T, 2, seed + 1)
    u = ys / T                                             # 0 top .. 1 bottom
    reach = {1: 0.5, 2: 0.78, 3: 1.05}[stage]
    dens = np.clip((u - (1 - reach)) / reach, 0, 1) * (0.55 + 0.7 * (n - 0.3)) + 0.25 * (streak - 0.5) * np.clip(u * 3, 0, 1)
    dens = np.clip(dens * {1: 0.8, 2: 1.0, 3: 1.25}[stage], 0, 1)
    cv = Canvas(T)
    hit = dens > 0.22
    cv.put(hit, bands(IRON_SOOT[:4], np.clip(1 - dens, 0, 1) * 0.9, 0.2))
    a = np.where(dens > 0.7, 0.92, np.where(dens > 0.45, 0.78, np.where(dens > 0.22, 0.55, 0)))
    cv.a = np.where(hit, a, 0.0)
    # Dither the fringe so it isn't a hard line
    fr = rand(T, T, seed + 2) > 0.6
    cv.a = np.where(edge(hit) & fr, 0.0, cv.a)
    return cv.image()


def soot_wall(seed=681):
    """A smear of soot for a wall above a firebox: black at the bottom fading to nothing by the top, in tongues."""
    xs, ys = grid(T)
    n = fbm(T, T, 5, seed)
    u = (T - ys) / T
    dens = np.clip(1.0 - u * 1.5 + 0.4 * (n - 0.5) + 0.25 * np.sin(xs * 0.5 + n * 6), 0, 1)
    cv = Canvas(T)
    hit = dens > 0.15
    cv.put(hit, (10, 9, 10))
    cv.a = np.where(dens > 0.65, 0.9, np.where(dens > 0.4, 0.66, np.where(dens > 0.15, 0.36, 0.0)))
    fr = rand(T, T, seed + 2) > 0.5
    cv.a = np.where(edge(hit) & fr, 0.0, cv.a)
    return cv.image()


# ================================================================ registry

PAINTERS = {
    'iron': iron, 'pot_iron': pot_iron, 'iron_inside': iron_inside, 'iron_band': iron_band, 'brass': brass,
    'brick': hearth_brick, 'clay': clay_brick, 'clay_sooty': clay_sooty, 'firestone': firestone, 'flagstone': flagstone,
    'bark': bark, 'charred_log': charred_log, 'charred_end': charred_end, 'log_glow': log_glow,
    'darkwood': darkwood, 'planks': planks, 'board': board, 'slats': slats, 'slats_h': slats_h, 'rope': rope, 'chain': chain,
    'enamel': enamel, 'slate': slate, 'chalk': chalk, 'stoneware': stoneware,
    'coals': ember_bed, 'coals_cold': ember_bed_cold, 'ash': ash, 'flame': flame, 'oven_glow': glow_window, 'oven_dark': dark_window,
    'soot_1': lambda: soot(1), 'soot_2': lambda: soot(2), 'soot_3': lambda: soot(3), 'soot_wall': soot_wall,
}

# Animated ones: the frame time in ticks (and whether the game blends the frames)
ANIMATED = {
    'coals': {'frametime': 4, 'interpolate': True},
    'flame': {'frametime': 2},
    'oven_glow': {'frametime': 5, 'interpolate': True},
}


def contact(names=None, scale=4, cols=8):
    from hdkit import sheet
    names = names or list(PAINTERS)
    ims, labs = [], []
    for n in names:
        im = PAINTERS[n]()
        if im.height > im.width:
            im = im.crop((0, 0, im.width, im.width))
        ims.append(im)
        labs.append(n)
    return sheet(ims, scale=scale, cols=cols, labels=labs)
