"""The Counting House's fittings, painted and modelled with the works' kit (trade_works_kit.py) beside the Store's (store_art.py, whose
materials and the name board's bracket this takes as they are): the wall map (a framed parchment, a sill with the clerk's spools and pins
on it, and the pieces a renderer sets on it: brass pins in four sizes, threads, a tiny wagon in each of six pennant colours, their
wheels and pennants), the House's name board (six boards, one for each pennant colour, on the Store's bracket) and the bell.

    python3 -B art/scripts/house_art.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/trade art/models [preview-dir]

Every fitting is a block of the trade, authored FACING NORTH (its front toward -z, the wall at z 16) in model px (16 to a block; x east,
y up, z south) and turned by its blockstate's `facing`, as store_art.py's are (read its docstring for the model-space rules of the meshes):
  models/block/<id>.json, blockstates/<id>.json        the block, with what stands still
  textures/block/trade/house_*.png                     its pictures; the meshes' pictures and the parchment among them
  art/models/house_*.bbmodel                           the meshes, for Blockbench
  client/trade/HouseArt.kt (GENERATED)                 the numbers a renderer needs beside them, and the meshes' LayerDefinitions
Nothing here is drawn by a renderer or placed by a block: those are F2's (HouseMapRenderer, TradeBlocks) and G's. The ids are

  house_map          THE MAP IS FORTY-EIGHT BY THIRTY-TWO PX: THREE BLOCKS WIDE AND TWO HIGH, hung on its block's wall face with the block as the
                     middle of its bottom row (its model reaches from x -16 to 32 and y 0 to 32, the most a model may). A frame, the
                     parchment (44 x 28 px, painted with a graticule, a compass rose, a scale, folds and a faint country, and no
                     lettering), a sill along the bottom with spools of thread, a tin of pins and an ink pot on it. The pins, threads,
                     wagons, wheels, pennants and glints are the mesh's (HouseArt.mapMesh), drawn on the parchment by the renderer.
  house_name_board   the Store's wall bracket for a hanging board; the board is the mesh's (HouseArt.boardsMesh: "board_<colour>")
  house_bell         a bracket arm with the bell's cheeks and axle; the bell, its clapper and its rope are the mesh's (HouseArt.bellMesh)

The map's frame is the model, so a blockstate `facing` turns the whole map, and a renderer turns what it draws by the same: the map's own
axes are fixed (up on the wall is world north, the viewer's right is world east), whichever way the wall faces.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import trade_works_kit as K  # noqa: E402
from trade_works_kit import mbox, mpart, np, Image, Canvas, PAL, grid, bands, fbm, vnoise, rand  # noqa: E402
import store_art as S  # noqa: E402
from hdkit import BAYER  # noqa: E402

W = K.W
Pic = K.Pic
BRASS, IRON, GILT = PAL['brass'], PAL['iron'], W.GILT
tl = S.tl
box, mesh_box, model_box = S.box, S.mesh_box, S.model_box
COLOURS = K.COLOURS
NAMES = K.NAMES

# ================================================================== the map
MAP_X, MAP_Y = (-14.0, 30.0), (2.0, 30.0)          # the parchment, in the map block's model space: from the viewer's right edge to the left, bottom to top
MAP_Z = 15.0                                       # its face: pins and the rest are drawn out from this plane toward the viewer (-z)
MAP_W, MAP_H = MAP_X[1] - MAP_X[0], MAP_Y[1] - MAP_Y[0]
MAP_CX, MAP_CY = (MAP_X[0] + MAP_X[1]) / 2.0, (MAP_Y[0] + MAP_Y[1]) / 2.0
MAP_MARGIN = 4.5                                   # the least a pin or a thread's end stands from the parchment's edge (the border and its ticks lie in it)
MAP_MAX_SCALE = 0.25                               # the most px a world block is drawn as (two capitals close together are not flung apart)
MAP_TPX = 4                                        # texels to a pixel, of the parchment's picture
PIN_D = (1.8, 2.4, 3.0, 3.6)                       # a pin head's diameter, px, for sizes 0 to 3
PIN_HOME_RING = 1.45                               # the ring round the largest pin (home) is this many times its head
WG = 1.2                                           # the wagon's size against the first drawing of it: every number of its own below is that times this
WHEEL_R = 0.62 * WG                                # a wagon's wheel
WHEEL_AT = ((-1.05 * WG, 0.62 * WG), (1.05 * WG, 0.62 * WG))   # the wheels' centres in the wagon's frame (x, y); the wagon faces -x (the viewer's right)
WHEEL_Z = 0.78 * WG                                # ... and how far out to each side of its middle (the wheel is drawn at z +- this)
FLAG_AT = (1.26 * WG, 2.95 * WG)                   # the mast's tip in the wagon's frame (the pennant streams from it toward +x)
WAGON_LEN = 3.9 * WG
SHADOW_COL = (74, 52, 34)


def map_scale(span_x, span_z):
    """Px a world block is drawn as, so that capitals [span_x] x [span_z] blocks apart fit the parchment inside its margin; a lone capital
    (no span) is drawn at the cap."""
    inner_w, inner_h = MAP_W - 2 * MAP_MARGIN, MAP_H - 2 * MAP_MARGIN
    if span_x < 1.0 and span_z < 1.0:
        return MAP_MAX_SCALE
    return min(MAP_MAX_SCALE, inner_w / max(span_x, 1.0), inner_h / max(span_z, 1.0))


def map_x(wx, cx, k):
    """The map's x (model space) of world x [wx], the middle of what is shown at world [cx], [k] px a block: east is the viewer's right, so toward -x."""
    return MAP_CX - (wx - cx) * k


def map_y(wz, cz, k):
    """The map's y of world z [wz]: north (toward -z) is up."""
    return MAP_CY - (wz - cz) * k


# ---------------------------------------------------------------- how the pieces move
POP_TICKS = 8.0                                    # a pin's growing in, when a town is founded
POP_OVER = 1.3
GLINT_PERIOD, GLINT_TICKS = 70.0, 9.0              # a pin's sparkle: how often, how long
THREAD_GROW_TICKS = 12.0                           # a thread's running out from one pin to the next
WAGON_DZ = -0.95 * WG                                   # a wagon's origin stands this far toward the viewer from the parchment's plane
BOB_PX, ROCK_DEG = 0.07, 1.6                       # a wagon's hop and its rocking, a step of its wheels
FLAG_YAW, FLAG_ROLL = (9.0, 24.0), (5.0, 9.0)      # a pennant's flutter at rest and at a run (degrees)


def pin_pop(age):
    """The scale of a pin [age] ticks after it was set: grows from nothing past full size and settles."""
    if age <= 0:
        return 0.0
    if age >= POP_TICKS:
        return 1.0
    w = age / POP_TICKS - 1.0
    return 1.0 + (POP_OVER + 1.0) * w ** 3 + POP_OVER * w ** 2


def glint_at(t, i):
    """The scale of the sparkle over pin [i] at tick [t]: nothing for most of GLINT_PERIOD, then a flare and gone."""
    u = ((t + i * 23.0) % GLINT_PERIOD) / GLINT_TICKS
    return math.sin(math.pi * u) if u < 1.0 else 0.0


def thread_grow(age):
    """How much of a thread (0 to 1 of its length) is run out [age] ticks after it was laid."""
    return S.smooth(age / THREAD_GROW_TICKS)


def wheel_turn(dist):
    """Degrees a wheel is turned about z after its wagon has gone [dist] px toward the viewer's right (negative: toward the left)."""
    return math.degrees(dist / WHEEL_R)


def wagon_flip(dx):
    """Degrees the wagon is turned about up, after its tilt, for a move of [dx] px along the map's x: it faces -x (the viewer's right), so 180 for dx > 0."""
    return 180.0 if dx > 0 else 0.0


def wagon_tilt(dx, dy):
    """Degrees the wagon is turned about z (positive: clockwise as the viewer sees it) to lie along a move of ([dx], [dy]) px, the map's x and y."""
    if abs(dx) < 1e-6 and abs(dy) < 1e-6:
        return 0.0
    a = math.degrees(math.atan2(-dy, -dx)) if dx <= 0 else math.degrees(math.atan2(dy, dx))
    return max(-38.0, min(38.0, a))


def wagon_bob(dist, phase):
    """The wagon's hop over the ground, px, after it has gone [dist] px."""
    return BOB_PX * abs(math.sin(dist * 1.9 + phase))


def wagon_rock(dist, phase):
    """The wagon's rocking about z, degrees, after it has gone [dist] px."""
    return ROCK_DEG * math.sin(dist * 1.3 + phase)


def flag_yaw(t, phase, run):
    """Degrees a pennant is turned about up at its mast at tick [t], [run] 0 (standing) to 1 (at a run): it flutters in and out of the page."""
    amp = FLAG_YAW[0] + (FLAG_YAW[1] - FLAG_YAW[0]) * run
    return amp * (math.sin(0.33 * t + phase) + 0.45 * math.sin(0.81 * t + 2.0 * phase))


def flag_roll(t, phase, run):
    """Degrees a pennant is turned about z at its mast (positive lifts its tail): it streams a little up, and ripples."""
    base = FLAG_ROLL[0] + (FLAG_ROLL[1] - FLAG_ROLL[0]) * run
    return base * (0.7 + 0.5 * math.sin(0.47 * t + phase + 1.0))


def nearest(rgb):
    """The index of the pennant colour nearest [rgb] (an int 0xRRGGBB): the House's board, its wagons and its flags."""
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return min(range(len(COLOURS)), key=lambda i: (COLOURS[i][1][0] - r) ** 2 + (COLOURS[i][1][1] - g) ** 2 + (COLOURS[i][1][2] - b) ** 2)


# ---------------------------------------------------------------- the parchment's picture
BAND_INK = (46, 32, 26)
SEA_INK = (70, 104, 118)
SEA_WASH = (104, 148, 160)
LOW_GREEN = (116, 128, 70)
HILL_INK, HILL_LIT = (92, 62, 40), (176, 140, 96)
TREE_DARK, TREE_LIT = (62, 84, 50), (108, 134, 72)


def _dilate(mask, r):
    out = mask.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy <= r * r:
                out |= np.roll(np.roll(mask, dy, 0), dx, 1)
    return out


def _glyph(cv, rows, x0, y0, key, w, h):
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            c = key.get(ch)
            if c is not None and 0 <= x0 + i < w and 0 <= y0 + j < h:
                cv.px(x0 + i, y0 + j, c, 1.0)


def parchment_map():
    """The map's face, 176 x 112 texels (the parchment's 44 x 28 px at four to a pixel), read as the viewer reads it: old vellum with a
    country washed on it (a sea round a land with a coast inked and rippled, hills, woods, a few rivers), a dotted graticule, a border of
    black and cream blocks, a compass rose at the lower left (north up) and a scale at the lower right, a small cartouche over the top,
    and folds. No lettering: the pins and threads on it are the renderer's, and the land is calm enough for them to stand out of."""
    w, h = int(MAP_W * MAP_TPX), int(MAP_H * MAP_TPX)
    p = Pic(w, h)
    cv, xs, ys = p.cv, p.xs, p.ys
    full = lambda v: np.full((h, w), float(v))
    rng = np.random.default_rng(8101)
    n = fbm(w, h, 12, 8101, 4)
    cv.put(np.ones((h, w), bool), bands(K.PARCH, 0.70 + 0.2 * (n - 0.5) + 0.05 * (rand(w, h, 8102) - 0.5), 0.3))
    # the country: a continent and a few islands, a sea margin all round, a bay or two
    ex = ((xs - w * 0.52) / (w * 0.44)) ** 2
    ey = ((ys - h * 0.50) / (h * 0.42)) ** 2
    f = 0.62 * fbm(w, h, 48, 8111, 4, 0.55) + 0.55 * (1.0 - (ex + ey) ** 0.8) + 0.05 * (fbm(w, h, 7, 8112, 2) - 0.5)
    land = f > 0.50
    land &= (xs > 12) & (xs < w - 12) & (ys > 12) & (ys < h - 12)
    sea = ~land
    d1, d2, d3, d4 = _dilate(land, 1), _dilate(land, 2), _dilate(land, 4), _dilate(land, 8)
    cv.over(sea, SEA_WASH, 0.46)
    cv.over(sea & d4, (150, 188, 190), 0.20)
    cv.over(sea & d3, (170, 204, 200), 0.20)
    lowland = land & (fbm(w, h, 26, 8121, 3) > 0.46)
    cv.over(lowland, LOW_GREEN, 0.22)
    hillf = fbm(w, h, 30, 8131, 3)
    cv.over(land & (hillf > 0.58), (150, 112, 70), 0.16)
    # the sea's ripples round the coast, dotted, and a few waves out in it
    for k0, k1 in ((4, 5), (8, 9)):
        ring = _dilate(land, k1) & ~_dilate(land, k0) & sea
        cv.over(ring & ((xs + ys * 2) % 4 < 2), SEA_INK, 0.55)
    far = sea & ~d4
    waves = 0
    while waves < 34:
        x, y = int(rng.integers(16, w - 24)), int(rng.integers(14, h - 14))
        if far[y, x] and far[y, x + 6] and far[y + 2, x]:
            for i, dy in enumerate((0, 1, 1, 0, 0, 1)):
                cv.px(x + i, y + dy, SEA_INK, 1.0)
            waves += 1
    # hills
    placed = []

    def spaced(x, y, d, pts):
        return all((x - a) ** 2 + (y - b) ** 2 > d * d for a, b in pts)
    interior = land & ~_dilate(sea, 4)
    for _ in range(2400):
        x, y = int(rng.integers(14, w - 14)), int(rng.integers(14, h - 14))
        if interior[y, x] and hillf[y, x] > 0.59 and spaced(x, y, 10, placed) and len(placed) < 30:
            placed.append((x, y))
            _glyph(cv, ['...#...', '..#.#..', '.#...#.', '#.....#'], x - 3, y - 2, {'#': HILL_INK}, w, h)
            _glyph(cv, ['....', '...L', '..LL'], x, y - 1, {'L': HILL_LIT}, w, h)
    # woods: clumps of round trees (a dark crown, a lit one over it, a trunk)
    woodf = fbm(w, h, 24, 8132, 3)
    trees = []
    for _ in range(3000):
        x, y = int(rng.integers(12, w - 12)), int(rng.integers(12, h - 12))
        if interior[y, x] and woodf[y, x] > 0.60 and spaced(x, y, 4.5, trees) and spaced(x, y, 6, placed) and len(trees) < 70:
            trees.append((x, y))
            _glyph(cv, ['.TT.', 'TLTT', 'TTTT', '.TT.', '.B..'], x - 2, y - 2, {'T': TREE_DARK, 'L': TREE_LIT, 'B': (84, 56, 38)}, w, h)
    # rivers: from a hill down the land to the sea
    pick = rng.permutation(len(placed))[:4] if placed else []
    for ix in pick:
        x, y = float(placed[ix][0]), float(placed[ix][1])
        for _ in range(260):
            xi, yi = int(x), int(y)
            if not (4 < xi < w - 5 and 4 < yi < h - 5) or sea[yi, xi]:
                break
            cv.px(xi, yi, SEA_INK, 1.0)
            best, bd = (1, 0), 1e9
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (1, -1), (-1, -1)):
                v = f[min(h - 1, max(0, yi + 2 * dy)), min(w - 1, max(0, xi + 2 * dx))] + 0.05 * rng.random()
                if v < bd:
                    best, bd = (dx, dy), v
            x, y = x + best[0], y + best[1]
    # the coast, inked, with the water's side lit
    coast = land & ~(np.roll(land, 1, 0) & np.roll(land, -1, 0) & np.roll(land, 1, 1) & np.roll(land, -1, 1))
    cv.over(coast, BAND_INK, 0.82)
    # the graticule, dotted
    for gx in range(22, w, 22):
        cv.over((np.abs(xs - gx - 0.5) < 0.5) & (np.floor(ys) % 3 == 0), BAND_INK, 0.22)
    for gy in range(22, h, 22):
        cv.over((np.abs(ys - gy - 0.5) < 0.5) & (np.floor(xs) % 3 == 0), BAND_INK, 0.22)
    # the folds: two down, one across, a dark line with a lit one beside it
    for fx in (w // 3, 2 * w // 3):
        d = np.abs(xs - fx - 0.5 + 1.2 * (fbm(w, h, 30, 8141) - 0.5))
        cv.over(d < 0.6, (64, 44, 30), 0.26)
        cv.over((d >= 0.6) & (d < 1.6), (246, 236, 208), 0.2)
    d = np.abs(ys - h // 2 - 0.5 + 1.2 * (fbm(w, h, 30, 8142) - 0.5))
    cv.over(d < 0.6, (64, 44, 30), 0.26)
    cv.over((d >= 0.6) & (d < 1.6), (246, 236, 208), 0.2)
    # the border: a ruled frame, a ladder of black and cream blocks between two rules
    def frame(i0, i1):
        return ((xs > i0) & (xs < w - i0) & (ys > i0) & (ys < h - i0)) & ~((xs > i1) & (xs < w - i1) & (ys > i1) & (ys < h - i1))
    band = frame(2.0, 6.0)
    cv.put(band, bands(K.PARCH, full(0.82), 0.0))
    alongx = band & ((ys < 6) | (ys > h - 6))
    alongy = band & ~alongx
    cv.put(alongx & (np.floor(xs / 6) % 2 == 0), BAND_INK)
    cv.put(alongy & (np.floor(ys / 6) % 2 == 0), BAND_INK)
    cv.put(frame(2.0, 3.0), BAND_INK)
    cv.put(frame(5.0, 6.0), BAND_INK)
    # the cartouche: a ribbon over the top edge with a gilt lozenge, nothing written on it
    cx0 = w / 2.0
    rib = (np.abs(xs - cx0) < 17) & (np.abs(ys - 17) < 4)
    tails = ((np.abs(np.abs(xs - cx0) - 19) < 5) & (np.abs(ys - 19) < 4) & ~rib)
    cv.put(tails, bands(K.PARCH, full(0.5), 0.0))
    cv.put(tails & ((np.abs(ys - 19) > 3.0) | (np.abs(np.abs(xs - cx0) - 19) > 4.0)), BAND_INK)
    cv.put(rib, bands(K.PARCH, full(0.88), 0.0))
    cv.put(rib & ((np.abs(ys - 17) > 3.0) | (np.abs(xs - cx0) > 16.0)), BAND_INK)
    loz = (np.abs(xs - cx0) + np.abs(ys - 17) * 1.6) < 6.5
    cv.put(loz, bands(GILT, full(0.62), 0.0))
    cv.put(loz & ((np.abs(xs - cx0) + np.abs(ys - 17) * 1.6) > 5.2), BAND_INK)
    # the compass rose, lower left, north up: two rings, four long points and four short, each point half dark and half light
    rcx, rcy, rr = 27.0, h - 30.0, 17.0
    dist = np.hypot(xs - rcx, ys - rcy)
    cv.over(dist < rr + 3, bands(K.PARCH, full(0.9), 0.0), 0.32)
    cv.put((np.abs(dist - rr * 0.55) < 0.5) | (np.abs(dist - rr * 0.86) < 0.5), BAND_INK)
    for k_ in range(8):
        a = math.radians(k_ * 45)
        long_ = k_ % 2 == 0
        L = rr * (1.12 if long_ else 0.72)
        wd = rr * (0.17 if long_ else 0.13)
        tip = (rcx + L * math.sin(a), rcy - L * math.cos(a))
        lx, ly = math.cos(a), math.sin(a)
        left = (rcx - wd * lx, rcy - wd * ly)
        right = (rcx + wd * lx, rcy + wd * ly)
        cv.put(p.P([tip, (rcx, rcy), left]), BAND_INK if long_ else (118, 88, 60))
        cv.put(p.P([tip, (rcx, rcy), right]), bands(K.PARCH, full(0.95), 0.0))
        cv.over(p.L(tip[0], tip[1], left[0], left[1], 0.9) | p.L(tip[0], tip[1], right[0], right[1], 0.9), BAND_INK, 0.9)
    north = p.P([(rcx, rcy - rr * 1.12 - 0.5), (rcx - 2.6, rcy - rr * 0.62), (rcx + 2.6, rcy - rr * 0.62)])
    cv.put(north, (166, 44, 36))
    cv.put(dist < 1.8, BAND_INK)
    # the scale: four bars, black and cream in turn, boxed
    sx0, sy0, seg = w - 62, h - 22, 11
    for i in range(4):
        m = (xs >= sx0 + i * seg) & (xs < sx0 + (i + 1) * seg) & (ys >= sy0) & (ys < sy0 + 4)
        cv.put(m, BAND_INK if i % 2 == 0 else bands(K.PARCH, full(0.92), 0.0))
    box_ = (xs >= sx0 - 1) & (xs <= sx0 + 4 * seg) & (ys >= sy0 - 1) & (ys <= sy0 + 4)
    cv.put(box_ & ~((xs >= sx0) & (xs < sx0 + 4 * seg) & (ys >= sy0) & (ys < sy0 + 4)), BAND_INK)
    for i in range(5):
        cv.put((xs == sx0 + i * seg - (1 if i == 4 else 0)) & (ys >= sy0 - 3) & (ys <= sy0 + 6), BAND_INK)
    # a few stains, and the vellum's dark edge
    for _ in range(4):
        sx_, sy_, sr = rng.uniform(20, w - 20), rng.uniform(14, h - 14), rng.uniform(5, 11)
        d = np.hypot(xs - sx_, ys - sy_) / sr
        cv.over((d < 1.0) & (fbm(w, h, 4, int(sx_ * 7)) > 0.42), (150, 112, 70), 0.10 * (1.0 - d.clip(0, 1)) ** 0.5)
    edge_d = np.minimum.reduce([xs, ys, w - xs, h - ys])
    cv.over(edge_d < 5, (96, 66, 40), 0.30 * (1.0 - edge_d.clip(0, 5) / 5.0))
    cv.a[:] = 1.0
    return cv.image()


# ================================================================== the map's pieces
TAR = [(28, 20, 14), (56, 42, 28), (92, 68, 44), (128, 98, 64), (170, 140, 96)]            # a waxed dark thread
CRIMSON = [(40, 12, 16), (82, 24, 28), (128, 40, 40), (170, 64, 56), (210, 106, 90)]


def pic(key, draw, faces=('front',)):
    return K.pic_painter(key, faces, lambda w_, h_, face: draw(w_, h_))


def _bayer(w, h):
    b = np.asarray(BAYER, float)
    b = (b - b.min()) / max(1e-9, b.max() - b.min())
    return np.tile(b, (h // b.shape[0] + 1, w // b.shape[1] + 1))[:h, :w]


def pin_head_pic(w, h):
    """A pin's head seen from the front: a polished brass disc, its rim lit upper left and dark lower right."""
    p = Pic(w, h)
    cx, cy = w / 2.0, h / 2.0
    r = min(w, h) / 2.0 - 1.0
    d = np.hypot(p.xs - cx, p.ys - cy)
    disc = d <= r
    p.round(disc, BRASS, cx - r * 0.2, cy - r * 0.25, r * 1.25, base=0.76, k=0.32)
    ring = disc & (d > r - max(1.0, r * 0.24))
    lit = ((p.xs - cx) + (p.ys - cy)) < 0
    p.flat(ring & lit, BRASS, 0.86)
    p.flat(ring & ~lit, BRASS, 0.3)
    return p.finish(k=0.8, rim_k=0.0)


def pin_dome_pic(w, h):
    """The dome on a pin's head: a smaller, brighter disc with a spark of light on it."""
    p = Pic(w, h)
    cx, cy = w / 2.0, h / 2.0
    r = min(w, h) / 2.0 - 1.0
    d = np.hypot(p.xs - cx, p.ys - cy)
    disc = d <= r
    p.round(disc, BRASS, cx - r * 0.3, cy - r * 0.35, r * 1.3, base=0.84, k=0.34)
    p.flat(disc & (np.hypot(p.xs - (cx - r * 0.38), p.ys - (cy - r * 0.42)) <= max(0.8, r * 0.2)), BRASS, 1.0)
    return p.finish(k=0.7, rim_k=0.0)


def pin_ring_pic(w, h):
    """The gilt ring round the home pin."""
    p = Pic(w, h)
    cx, cy = w / 2.0, h / 2.0
    r = min(w, h) / 2.0 - 1.0
    d = np.hypot(p.xs - cx, p.ys - cy)
    ring = (d <= r) & (d > r * 0.74)
    p.cel(ring, GILT, base=0.64, hi=0.24, lo=0.3)
    return p.finish(k=0.7, rim_k=0.0)


def pin_shadow_pic(w, h):
    """The shadow a pin throws on the parchment, dithered (the mesh is drawn alpha-cut, so a soft shadow is a scatter of dark texels)."""
    p = Pic(w, h)
    d = np.hypot((p.xs - w / 2.0) / (w / 2.0 - 0.5), (p.ys - h / 2.0) / (h / 2.0 - 0.5))
    shade = np.clip(1.15 - d, 0.0, 1.0)
    show = shade > _bayer(w, h) * 0.9
    p.cv.put(show & (d <= 1.0), SHADOW_COL)
    return p.cv.image()


def pin_part(k):
    D = PIN_D[k]
    r = D / 2.0
    n = f'pin_{k}'
    ox, oy = -0.2 * D, -0.28 * D                    # the shadow falls toward the viewer's lower right (their right is -x)
    cubes = [mbox(n + '_shadow', ox - r * 1.05, ox + r * 1.05, oy - r * 1.0, oy + r * 1.0, -0.03, 0.0, pic(n + '_shadow', pin_shadow_pic), ['front']),
             mbox(n + '_shaft', -0.12, 0.12, -0.12, 0.12, -0.22, 0.0, tl(n + '_shaft', 'brass', 0.1), ['left', 'right', 'top', 'bottom']),
             mbox(n + '_head', -r, r, -r, r, -0.5, -0.22, pic(n + '_head', pin_head_pic), ['front']),
             mbox(n + '_dome', -0.62 * r, 0.62 * r, -0.62 * r, 0.62 * r, -0.85, -0.5, pic(n + '_dome', pin_dome_pic), ['front'])]
    if k == len(PIN_D) - 1:
        R = r * PIN_HOME_RING
        cubes.append(mbox(n + '_ring', -R, R, -R, R, -0.36, -0.3, pic(n + '_ring', pin_ring_pic), ['front']))
    return mpart(n, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), cubes)


def thread_paint(key, ramp_):
    def fn(face, w, h, rng):
        a = np.zeros((h, w, 4))
        rows = np.arange(h)[:, None] * np.ones((1, w))
        if face == 'front':
            t = np.clip(0.88 - 0.62 * rows / max(1.0, h - 1.0), 0, 1)
        elif face == 'top':
            t = np.full((h, w), 0.92)
        elif face == 'bottom':
            t = np.full((h, w), 0.12)
        else:
            t = np.full((h, w), 0.45)
        t = t + 0.03 * (rng.random((h, w)) - 0.5)
        a[..., :3] = bands(ramp_, t, 0.0)
        a[..., 3] = 255
        return a
    return K.tt.painter(key, fn, light=False)


def thread_part(name, ramp_):
    """A thread: a unit length along +x from the origin, the renderer turning it about z to the line and scaling its x to the length."""
    return mpart(name, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [mbox(name, 0.0, 1.0, -0.17, 0.17, -0.34, -0.06, thread_paint(name, ramp_),
                                                              ['front', 'top', 'bottom', 'left', 'right'])])


def wheel_pic(w, h):
    """A spoked wheel: an iron tyre, an oak felloe, four spokes and a brass hub, the gaps between them open."""
    p = Pic(w, h)
    cx, cy = w / 2.0, h / 2.0
    r = min(w, h) / 2.0 - 0.6
    d = np.hypot(p.xs - cx, p.ys - cy)
    disc = d <= r
    tyre = disc & (d > r - 1.25)
    felloe = disc & ~tyre & (d > r - 2.4)
    spoke = disc & ~tyre & ((np.abs(p.xs - cx) < 0.75) | (np.abs(p.ys - cy) < 0.75))
    hub = d < 1.7
    p.flat(tyre, IRON, 0.5)
    p.cel(felloe | spoke, W.OAK, base=0.55, hi=0.22, lo=0.2, lw=1, dw=1)
    p.flat(hub, BRASS, 0.8)
    p.flat(d < 0.7, BRASS, 0.2)
    return p.finish(k=0.8, rim_k=0.0)


def wheel_part():
    R = WHEEL_R
    return mpart('wheel', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [mbox('wheel', -R, R, -R, R, -0.15, 0.15, pic('wheel', wheel_pic, ('front', 'back')), ['front', 'back'])])


def cover_paint(key, ramp_, hoops=True):
    """A wagon's canvas in the House's colour: hoops (a dark line and a lit one), a pale trim along its foot, seams, folds."""
    def front(w, h, rng):
        a = np.zeros((h, w, 4))
        ys, xs = np.mgrid[0:h, 0:w]
        t = 0.58 + 0.05 * (rng.random((h, w)) - 0.5) + 0.12 * (1.0 - ys / max(1.0, h - 1.0))
        if hoops:
            for hx in (0.1, 0.38, 0.66, 0.92):
                c = int(hx * w)
                t = np.where(xs == c, 0.2, t)
                t = np.where(xs == c + 1, 0.82, t)
        t = np.where(ys >= h - max(2, h // 5), np.where(ys == h - max(2, h // 5), 0.25, 0.92), t)
        a[..., :3] = bands(ramp_, t, 0.0)
        a[..., 3] = 255
        return a

    def fn(face, w, h, rng):
        if face in ('front', 'back'):
            a = front(w, h, rng)
            if face == 'back':
                a = a[:, ::-1]
            return K.tt.shade_edges(a.copy(), 0.12)
        a = np.zeros((h, w, 4))
        t = (0.62 if face == 'top' else 0.3) + 0.05 * (rng.random((h, w)) - 0.5)
        a[..., :3] = bands(ramp_, t, 0.0)
        a[..., 3] = 255
        return K.tt.shade_edges(a, 0.12)
    return K.tt.painter(key, fn, light=False)


def wagon_part(ci):
    """A wagon in pennant colour [ci], standing on the contact of its wheels at the origin, facing -x (the viewer's right): a bed and a
    seat, the hooped cover in the colour, a tailboard, two shafts, and a mast whose pennant is the part "flag_<colour>". Its wheels are
    drawn by the renderer ("wheel", two to a wagon, on the viewer's side: WHEEL_AT at z -WHEEL_Z), turned as it rolls."""
    name, rgb, ramp_, _ = COLOURS[ci]
    p = f'wagon_{name}'
    wood = lambda k, **bf: tl(f'{p}_{k}', 'oak_h', 0.15, **bf)
    dark = lambda k: tl(f'{p}_{k}', 'dark_h', 0.15)
    bx = lambda name, x0, x1, y0, y1, z0, z1, paint, faces=None: mbox(name, x0 * WG, x1 * WG, y0 * WG, y1 * WG, z0 * WG, z1 * WG, paint, faces)
    cubes = [bx(p + '_bed', -1.55, 1.6, 0.5, 1.0, -0.62, 0.62, wood('bed')),
             bx(p + '_seat', -1.6, -1.05, 1.0, 1.42, -0.62, 0.62, wood('seat')),
             bx(p + '_cover', -0.95, 1.45, 1.0, 1.85, -0.58, 0.58, cover_paint(p + '_cover', ramp_)),
             bx(p + '_crown', -0.82, 1.32, 1.85, 2.18, -0.42, 0.42, cover_paint(p + '_crown', ramp_)),
             bx(p + '_tail', 1.6, 1.76, 0.5, 1.3, -0.62, 0.62, dark('tail')),
             bx(p + '_shaft_l', -2.4, -1.55, 0.62, 0.78, -0.42, -0.26, dark('shaft_l')),
             bx(p + '_shaft_r', -2.4, -1.55, 0.62, 0.78, 0.26, 0.42, dark('shaft_r')),
             mbox(p + '_mast', FLAG_AT[0] - 0.07, FLAG_AT[0] + 0.07, 2.18 * WG, FLAG_AT[1], -0.07, 0.07, dark('mast')),
             bx(p + '_lamp', -1.75, -1.55, 1.0, 1.32, -0.12, 0.12, tl(f'{p}_lamp', 'brass', 0.1)),
             # its shadow on the parchment, behind it (the parchment's plane is -WAGON_DZ from its origin), dithered, drawn from either side
             mbox(p + '_shadow', -2.3 * WG, 2.3 * WG, -0.75 * WG, 0.2 * WG, -WAGON_DZ - 0.04, -WAGON_DZ, pic(p + '_shadow', pin_shadow_pic, ('front', 'back')),
                  ['front', 'back'])]
    for i, (wx, wy) in enumerate(WHEEL_AT):
        cubes.append(mbox(f'{p}_axle{i}', wx - 0.1, wx + 0.1, wy - 0.1, wy + 0.1, -WHEEL_Z, WHEEL_Z, tl(f'{p}_axle{i}', 'iron', 0.1)))
    return mpart(p, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), cubes)


def flag_pic(w, h, ramp_):
    """A swallow-tailed pennant, read from the front with its mast at the right (it streams to the viewer's left): the colour with a pale
    stripe along its middle, the tail notched."""
    p = Pic(w, h)
    notch = w * 0.28
    shape = p.P([(w, 0.5), (w * 0.02, 0.5), (w * 0.02 + notch, h / 2.0), (w * 0.02, h - 0.5), (w, h - 0.5)])
    p.cel(shape, ramp_, base=0.58, hi=0.26, lo=0.3, lw=1, dw=1)
    stripe = shape & (np.abs(p.ys - h / 2.0) < max(0.6, h * 0.1)) & (p.xs > w * 0.02 + notch + 1)
    p.flat(stripe, ramp_, 0.95)
    return p.cv.image()


def flag_part(ci):
    name, rgb, ramp_, _ = COLOURS[ci]
    n = f'flag_{name}'

    def draw(w_, h_, face):
        im = flag_pic(w_, h_, ramp_)
        return im.transpose(Image.FLIP_LEFT_RIGHT) if face == 'back' else im
    return mpart(n, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [mbox(n, 0.0, 1.5 * WG, -0.36 * WG, 0.36 * WG, -0.05, 0.05, K.pic_painter(n, ('front', 'back'), draw), ['front', 'back'])])


def glint_pic(w, h):
    """A four-point sparkle, warm white, its points long and fine."""
    p = Pic(w, h)
    cx, cy = w / 2.0, h / 2.0
    dx, dy = np.abs(p.xs - cx) / (w / 2.0), np.abs(p.ys - cy) / (h / 2.0)
    star = (dx * dy < 0.045) & (np.hypot(dx, dy) < 1.0)
    core = np.hypot(dx, dy) < 0.22
    p.flat(star, PAL['bone'], 0.95)
    p.flat(core, PAL['bone'], 1.0)
    return p.cv.image()


def glint_part():
    return mpart('glint', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [mbox('glint', -1.2, 1.2, -1.2, 1.2, -0.94, -0.9, pic('glint', glint_pic), ['front'])])


def map_parts():
    cord = CRIMSON
    return ([pin_part(k) for k in range(len(PIN_D))] + [glint_part(), thread_part('thread', TAR), thread_part('thread_hot', cord), wheel_part()]
            + [wagon_part(i) for i in range(len(COLOURS))] + [flag_part(i) for i in range(len(COLOURS))])


def map_mesh():
    return K.Mesh('mapMesh', 'house_pieces', map_parts(), 160, 16, 8,
                  'The wall map\'s pieces, each standing at its own origin on the parchment\'s plane (z [HouseArt.MAP_Z]) and drawn toward the viewer: '
                  '"pin_0" to "pin_3" (a brass pin, its head at the origin, its shadow falling to the lower right; pin_3, the House\'s own town, '
                  'wears a gilt ring), "glint" (a sparkle to draw over a pin), "thread" and "thread_hot" (a unit long along +x: turn about z '
                  'to the line, scale x to its length), "wheel" (a spoked wheel at its hub), "wagon_<colour>" (standing on its wheels\' contact, '
                  'facing -x) and "flag_<colour>" (a pennant at its mast\'s tip, streaming toward +x). The colours are HouseArt.COLOUR_NAMES.')


# ================================================================== the name board: six boards on the Store's bracket
def abacus_device(size=22):
    """The House's device: a counting frame (a gilt frame, three rods, a few beads slid to each side), in gilt on the dark of a roundel."""
    p = Pic(size)
    c = size / 2.0
    d = np.hypot(p.xs - c, p.ys - c)
    p.cel(d < c - 0.4, S.FIELD, base=0.32, hi=0.14, lo=0.1)
    ring = (d > c - 3.0) & (d < c - 1.6)
    p.flat(ring, GILT, 0.62)
    p.flat(ring & (p.xs + p.ys < c * 2 - 1), GILT, 0.9)
    x0, x1, y0, y1 = 4, 18, 5, 17
    p.flat(p.R(x0, y0, x1, y1), GILT, 0.74)
    p.flat(p.R(x0 + 1, y0 + 1, x1 - 1, y1 - 1), S.FIELD, 0.08)
    for ry, beads in ((7, (5, 8, 11)), (10, (5, 11, 14)), (13, (5, 8, 14))):
        p.flat(p.R(x0 + 1, ry, x1 - 1, ry + 1), GILT, 0.36)                          # the rod
        for bx in beads:
            p.flat(p.R(bx, ry - 1, bx + 2, ry + 1), GILT, 0.96)
            p.flat(p.R(bx, ry, bx + 2, ry + 1), GILT, 0.7)
    return p.cv.image()


board_swing = S.board_swing                        # the House's boards swing as the Store's name board does (HouseArt.boardSwing repeats it)
CREAM_RULE = [(70, 62, 46), (120, 108, 80), (170, 156, 118), (222, 210, 176), (246, 238, 212)]


def board_ramps():
    """Each pennant colour's board field, its ramp without the darkest stop, so the board is the pennant's colour (not a deep shade of it) with the
    dark panel for the lettering still dark."""
    return [list(COLOURS[i][2][1:]) + [COLOURS[i][2][-1]] for i in range(len(COLOURS))]


def boards_parts():
    w, h, d = S.BOARD
    parts = []
    dev = abacus_device()
    for i, (name, rgb, ramp_, _) in enumerate(COLOURS):
        n = f'board_{name}'
        rule = CREAM_RULE if name in ('amber',) else GILT
        face = S.sign_face(int(w * S.BOARD_TPX), int(h * S.BOARD_TPX), dev, 6400 + i * 13, field=board_ramps()[i], rule=rule)

        def sign_paint(key, img=face):
            def fn(face_, fw, fh, rng):
                if face_ in ('front', 'back'):
                    return np.asarray(img.resize((fw, fh), Image.NEAREST) if img.size != (fw, fh) else img, float).copy()
                return K.tt.shade_edges(W.from_tile('dark_h', fw, fh, rng), 0.2)
            return K.tt.painter(key, fn, light=False)
        parts.append(mpart(n, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
            mbox(n, -w / 2, w / 2, -S.BOARD_DROP - h, -S.BOARD_DROP, -d / 2, d / 2, sign_paint(n)),
            mbox(n + '_ring_w', -S.BOARD_RINGS - 0.3, -S.BOARD_RINGS + 0.3, -S.BOARD_DROP - 0.2, 0.1, -0.3, 0.3, tl(n + '_ring_w', 'iron', 0.1)),
            mbox(n + '_ring_e', S.BOARD_RINGS - 0.3, S.BOARD_RINGS + 0.3, -S.BOARD_DROP - 0.2, 0.1, -0.3, 0.3, tl(n + '_ring_e', 'iron', 0.1))]))
    return parts


def boards_mesh():
    return K.Mesh('boardsMesh', 'house_boards', boards_parts(), 64, 68, S.BOARD_TPX,
                  'The House\'s six name boards, "board_<colour>" (the colours are HouseArt.COLOUR_NAMES): the House\'s pennant colour with its device, a counting '
                  'frame, at each end and the dark panel between them the name is lettered on; each swings about the line through its two rings, '
                  'which is its origin, drawn where [HouseArt.BOARD_X] and the rest put it. Draw the one whose colour is nearest the House\'s ([HouseArt.nearest]).')


# ================================================================== the bell
BELL_AXLE = (8.0, 11.6, 3.0)           # the bell's axle in the block's model space: the bell, its clapper and the rope's wheel turn about x through it
WHEEL_X, WHEEL_RAD = 3.9, 1.9          # the rope wheel on the axle: its distance east of the bell's middle, its radius
RING_TICKS = 140.0                     # a ring's length
BELL_AMP, BELL_PERIOD, BELL_TAU, BELL_RISE = 30.0, 22.0, 60.0, 10.0
CLAPPER_GAIN, CLAPPER_LAG, CLAPPER_GAP = 1.9, 3.5, 14.0
ROPE_TAU, ROPE_PERIOD, ROPE_AMP = 26.0, 17.0, 6.0


def ring_fade(age):
    """1 while a ring rings, easing to 0 over its last 24 ticks."""
    return 1.0 - S.smooth((age - (RING_TICKS - 24.0)) / 24.0)


def bell_swing(age):
    """Degrees the bell is turned about x (positive: its mouth toward -z, the street) [age] ticks after the rope was pulled: pulled down
    first (so the first swing is negative), the swing building over BELL_RISE ticks and dying away, gone by RING_TICKS."""
    if age <= 0 or age >= RING_TICKS:
        return 0.0
    amp = BELL_AMP * S.smooth(age / BELL_RISE) * math.exp(-max(0.0, age - BELL_RISE) / BELL_TAU) * ring_fade(age)
    return -amp * math.sin(2.0 * math.pi * age / BELL_PERIOD)


def idle_swing(t):
    """The bell at rest, never quite still in the wind: degrees at tick [t]."""
    return 0.9 * math.sin(0.05 * t) + 0.5 * math.sin(0.13 * t + 1.0)


def clapper_free(age):
    """Where the clapper would swing if nothing stopped it (absolute degrees): it follows the bell a little late and further."""
    return CLAPPER_GAIN * bell_swing(age - CLAPPER_LAG) * ring_fade(age)


def clapper_rel(age):
    """The clapper's turn against the bell, in degrees: where it hangs from the axle less the bell's own turn, never more than
    CLAPPER_GAP, the distance to the lip it strikes."""
    return max(-CLAPPER_GAP, min(CLAPPER_GAP, clapper_free(age) - bell_swing(age)))


def strikes():
    """The ticks after a pull at which the clapper meets the bell: each time its free swing carries it out to the gap on the side it did not
    strike last (a clapper that rattles on one wall is one stroke)."""
    out, side, t = [], 0, 0.0
    while t < RING_TICKS:
        v = clapper_free(t) - bell_swing(t)
        if abs(v) >= CLAPPER_GAP and (1 if v > 0 else -1) != side:
            out.append(round(t, 1))
            side = 1 if v > 0 else -1
        t += 0.1
    return out


def rope_sway(age):
    """Degrees the hanging rope swings about x from the wheel, after a pull."""
    if age <= 0 or age >= RING_TICKS:
        return 0.0
    return ROPE_AMP * math.exp(-age / ROPE_TAU) * math.sin(2.0 * math.pi * age / ROPE_PERIOD + 1.0) * ring_fade(age)


def rope_at(theta):
    """The rope's top (the wheel's front rim) moved by the bell's turn [theta] degrees: (dy, dz) from where it hangs at rest, which is
    (WHEEL_X, 0, -WHEEL_RAD) from the axle: the rim rises as the mouth swings toward the street."""
    a = math.radians(theta)
    return WHEEL_RAD * math.sin(a), WHEEL_RAD * (1.0 - math.cos(a))


def vdisc(name, r, x0, x1, paint):
    """An octagon standing in the y-z plane (its axis along x) from x0 to x1: a square and, in a child turned 45 degrees about x, a second."""
    sq = mbox(name, x0, x1, -r, r, -r, r, paint)
    sq2 = mbox(name + '_d', x0, x1 - 0.02, -r, r, -r, r, paint)
    return [sq], [mpart(name + '_d', (0.0, 0.0, 0.0), (45.0, 0.0, 0.0), [sq2])]


BELL_RAMP = [(36, 24, 12), (86, 54, 22), (136, 90, 34), (184, 134, 58), (226, 180, 104)]            # old bell bronze, warm gold-brown
BELL_PROFILE = [(-3.5, 1.75), (-4.1, 1.95), (-4.7, 2.15), (-5.3, 2.35), (-5.9, 2.62), (-6.5, 2.98), (-7.0, 3.32)]   # (the ring's foot, its outer radius), each 0.6 tall but the last (0.5)


def bell_paint(key, base=0.56):
    """Bell bronze: a lit streak down its left, a darker foot, hammer-flecks, here and there a spot of green."""
    def fn(face, w, h, rng):
        ys, xs = np.mgrid[0:h, 0:w]
        t = base + 0.07 * (rng.random((h, w)) - 0.5) + 0.1 * (1.0 - ys / max(1.0, h - 1.0)) + 0.14 * np.exp(-((xs - w * 0.28) / (w * 0.22 + 0.5)) ** 2)
        if face == 'top':
            t = t + 0.12
        if face == 'bottom':
            t = t - 0.2
        a = np.zeros((h, w, 4))
        a[..., :3] = bands(BELL_RAMP, t, 0.2)
        green = rng.random((h, w)) > 0.985
        a[..., :3] = np.where(green[..., None], np.array([84.0, 104.0, 62.0]), a[..., :3])
        a[..., 3] = 255
        return K.tt.shade_edges(a, 0.12)
    return K.tt.painter(key, fn, light=False)


def bell_parts():
    cubes, kids = [], []
    cubes.append(mbox('bell_head', -1.7, 1.7, -0.7, 0.7, -0.7, 0.7, tl('bell_head', 'dark_h', 0.15)))                  # the headstock
    cubes.append(mbox('bell_axle', -5.2, 5.2, -0.3, 0.3, -0.3, 0.3, tl('bell_axle', 'iron', 0.1)))
    cubes.append(mbox('bell_strap', -0.5, 0.5, -1.7, -0.7, -0.35, 0.35, tl('bell_strap', 'iron', 0.1)))
    c_, k_ = K.octa_disc('bell_crown', 1.2, -2.3, -1.7, bell_paint('bell_crown'))                                     # the crown, solid
    cubes += c_
    kids += k_
    c_, k_ = K.octa_disc('bell_cap', 1.55, -2.9, -2.3, bell_paint('bell_cap'))
    cubes += c_
    kids += k_
    for i, (y0, r) in enumerate(BELL_PROFILE):                                                                         # the body, ring on ring
        thick = 0.5 if i < 4 else (0.6 if i < 6 else 0.8)
        c_, k_ = K.octa_ring(f'bell_r{i}', r, thick, y0, y0 + 0.6 if i < len(BELL_PROFILE) - 1 else y0 + 0.5, bell_paint(f'bell_r{i}', 0.5 + 0.015 * i))
        cubes += c_
        kids += k_
    c_, k_ = vdisc('bell_wheel', WHEEL_RAD, WHEEL_X - 0.3, WHEEL_X + 0.3, tl('bell_wheel', 'oak_h', 0.15))
    cubes += c_
    kids += k_
    return mpart('bell', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), cubes, kids)


def clapper_part():
    return mpart('clapper', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
        mbox('clapper_rod', -0.17, 0.17, -5.2, 0.0, -0.17, 0.17, tl('clapper_rod', 'iron', 0.1)),
        mbox('clapper_ball', -0.62, 0.62, -6.2, -5.0, -0.62, 0.62, tl('clapper_ball', 'iron', 0.12))])


def rope_part():
    return mpart('rope', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
        mbox('rope_strand', -0.17, 0.17, -6.2, 0.0, -0.17, 0.17, tl('rope_strand', 'rope_v', 0.05)),
        mbox('rope_knot', -0.5, 0.5, -7.0, -6.2, -0.5, 0.5, tl('rope_knot', 'rope_v', 0.15)),
        mbox('rope_tassel', -0.38, 0.38, -8.2, -7.0, -0.38, 0.38, flat_cord('rope_tassel'))])


def flat_cord(key):
    return K.flat_paint(key, [(40, 12, 16), (82, 24, 28), (128, 40, 40), (170, 64, 56), (210, 106, 90)], 0.55, 0.08, 0.1)


def bell_mesh():
    return K.Mesh('bellMesh', 'house_bell', [bell_parts(), clapper_part(), rope_part()], 64, 32, 4,
                  'The bell\'s moving pieces, each standing at its origin: "bell" and "clapper" at the axle ([HouseArt.BELL_X] and the rest; both turn about x, '
                  'the bell by [HouseArt.bellSwing] and the clapper by that plus [HouseArt.clapperRel]), and "rope", hung from the wheel\'s front rim '
                  '(from [HouseArt.ropeY] and [HouseArt.ropeZ]; it hangs plumb and swings by [HouseArt.ropeSway]).')


def house_bell_model():
    """A wall bracket for the bell: an iron plate, a timber arm out from it under a knee brace, a cross piece at its end and two iron
    cheeks hanging from it, the axle's bearings in them. The bell, its clapper and its rope are the mesh's."""
    c = W.cube
    ax, ay, az = BELL_AXLE
    els = [c((5.5, 10.0, 14.6), (10.5, 16.0, 16.0), 'w_iron'),
           c((7.2, 13.2, 2.4), (8.8, 14.8, 14.6), {'north': 'w_oak_end', '*': 'w_oak_h'}),
           c((5.4, 13.2, 2.4), (10.6, 14.8, 3.6), {'west': 'w_oak_end', 'east': 'w_oak_end', '*': 'w_oak_h'}),
           W.along((8.0, 9.0, 14.7), (8.0, 13.4, 8.4), 1.2, 1.0, 'w_oak')]
    for x0, x1 in ((5.6, 6.2), (9.8, 10.4)):                                          # the cheeks
        els.append(c((x0, ay - 1.2, az - 0.6), (x1, 13.2, az + 0.6), 'w_iron'))
        els.append(c((x0 - 0.1, ay - 0.5, az - 0.5), (x1 + 0.1, ay + 0.5, az + 0.5), 'w_bronze'))
    els.append(c((10.4, ay - 0.5, az - 0.5), (13.0, ay + 0.5, az + 0.5), 'w_iron'))      # the axle's ends outside the cheeks
    els.append(c((3.0, ay - 0.5, az - 0.5), (5.6, ay + 0.5, az + 0.5), 'w_iron'))
    return K.model(els, 'w_oak')


# ================================================================== the map's block
def spool(cx, cz, y0, tex):
    els = W.disc(cx, cz, y0, y0 + 0.3, 1.25, 'w_oak')
    els += W.disc(cx, cz, y0 + 0.3, y0 + 2.0, 0.85, tex)
    els += W.disc(cx, cz, y0 + 2.0, y0 + 2.3, 1.25, 'w_oak')
    return els


def house_map_model():
    c = W.cube
    X0, X1 = -16.0, 32.0
    px0, px1 = MAP_X
    py0, py1 = MAP_Y
    plank = {'*': 'fit_dark_h', 'up': 'fit_dark_end', 'down': 'fit_dark_end'}
    side = {'*': 'fit_dark_v', 'up': 'fit_dark_end', 'down': 'fit_dark_end'}
    els = [c((X0, py1, 13.8), (X1, 32.0, 16.0), plank),                                           # the frame: top
           c((X0, py0, 13.8), (px0, py1, 16.0), side),                                            # the viewer's right side (west)
           c((px1, py0, 13.8), (X1, py1, 16.0), side),                                            # the viewer's left side (east)
           c((X0, 0.0, 9.6), (X1, py0, 16.0), plank),                                             # the sill
           c((px0, py0, MAP_Z), (px1, py1, MAP_Z + 0.4), {'north': 'house_map_parchment', '*': 'fit_parchment'},
             uv={'north': (0, 0, 16, 16)})]
    gilt = 'fit_brass'
    for fr, to in (((px0, py1 - 0.5, 13.8), (px1, py1, 14.4)), ((px0, py0, 13.8), (px1, py0 + 0.5, 14.4)),
                   ((px0, py0 + 0.5, 13.8), (px0 + 0.5, py1 - 0.5, 14.4)), ((px1 - 0.5, py0 + 0.5, 13.8), (px1, py1 - 0.5, 14.4))):
        els.append(c(fr, to, gilt, ('north', 'up', 'down', 'west', 'east')))                      # the brass inner moulding
    for x in (X0, X1 - 3.0):                                                                      # corner caps
        for y0, y1 in ((0.0, 3.0), (29.0, 32.0)):
            els.append(c((x, y0, 13.4), (x + 3.0, y1, 13.8), 'fit_brass', ('north', 'up', 'down', 'west', 'east')))
    # on the sill: three spools of thread, a tin of pins, an ink pot and its quill
    els += spool(11.0, 11.8, 2.0, 'house_cord_red')
    els += spool(8.0, 12.4, 2.0, 'house_cord_twine')
    els += spool(5.0, 11.6, 2.0, 'house_cord_blue')
    els += W.disc(0.0, 12.0, 2.0, 3.5, 1.9, 'fit_brass')
    for i, (dx, dz) in enumerate(((-0.6, 0.4), (0.5, -0.3), (0.1, 0.8), (-0.3, -0.7))):
        els.append(c((0.0 + dx - 0.15, 3.5, 12.0 + dz - 0.15), (0.0 + dx + 0.15, 5.1, 12.0 + dz + 0.15), 'fit_brass'))
        els.append(c((0.0 + dx - 0.35, 5.1, 12.0 + dz - 0.35), (0.0 + dx + 0.35, 5.5, 12.0 + dz + 0.35), 'fit_brass'))
    els.append(c((-6.0, 2.0, 11.2), (-3.6, 3.7, 13.4), 'fit_dark_end'))
    els.append(c((-5.7, 3.7, 11.5), (-3.9, 4.0, 13.1), 'fit_dark_end'))
    els.append(W.along((-4.8, 4.0, 12.3), (-8.0, 7.6, 10.6), 0.3, 0.3, 'fit_parchment'))
    return K.model(els, 'fit_dark_h')


def cord_tile(ramp_, seed):
    """Wound thread: rows of cord laid side by side across the spool's core, each lit along its top."""
    def paint():
        xs, ys = grid(W.T)
        u = ys % 3.0
        t = 0.66 - 0.34 * np.abs(u - 1.5) / 1.5 + 0.07 * (fbm(W.T, W.T, 3, seed) - 0.5)
        t = np.where(u < 0.5, t - 0.2, t)
        cv = Canvas(W.T)
        cv.put(W.ALL, bands(ramp_, t, 0.2))
        return cv
    return paint


_CORD = [(40, 12, 16), (82, 24, 28), (128, 40, 40), (170, 64, 56), (210, 106, 90)]
_BLUE = [(14, 22, 52), (26, 44, 96), (44, 72, 150), (78, 112, 192), (130, 160, 224)]
W.TILE_PAINTERS.update({'cord_red': cord_tile(_CORD, 8201), 'cord_twine': cord_tile(K.TWINE, 8203), 'cord_blue': cord_tile(_BLUE, 8205)})


# ================================================================== the tables
def models():
    return {'house_map': house_map_model(), 'house_name_board': S.name_board_model(), 'house_bell': house_bell_model()}


def blockstates():
    return {bid: K.facing_blockstate(bid) for bid in models()}


def block_textures():
    """Every picture the block models use that the works and the Store do not have: name -> image, under textures/block/trade/."""
    out = {name: K.tile_image(tile) for name, tile in (('house_cord_red', 'cord_red'), ('house_cord_twine', 'cord_twine'), ('house_cord_blue', 'cord_blue'))}
    out['house_map_parchment'] = parchment_map()
    return out


def meshes():
    return [map_mesh(), boards_mesh(), bell_mesh()]


# ================================================================== HouseArt.kt
KT_HEADER = '// GENERATED by art/scripts/house_art.py: edit the script, not this file.\n'


def _kt_arr(vals):
    return 'floatArrayOf(' + ', '.join(K.kf(v) for v in vals) + ')'


def kotlin_house(meshes_):
    kf = K.kf
    funs = '\n\n'.join(K.mesh_fun(m) for m in meshes_)
    names = ', '.join('"%s"' % n for n in NAMES)
    rgbs = ', '.join(K.kint(K.rgb_int(c[1])) for c in COLOURS)
    lines = ',\n'.join(f'        floatArrayOf({kf(a)}, {kf(b)}, {kf(c)})' for a, b, c in S.NAME_LINES)
    strike = strikes()
    bs = S.BOARD_SWAY
    return KT_HEADER + f'''package io.github.vaspyyy.guildmark.client.trade

{K.KT_MESH_IMPORTS}import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Counting House's fittings as their renderers draw them: the wall map's pieces (pins, threads, wagons with their wheels and
 * pennants, glints), the House's six name boards and the bell with its clapper and rope, with the numbers each is moved by. Sizes are
 * pixels (a sixteenth of a block) in the block's own model space (x east, y up, z south, the block facing north, its front toward -z),
 * which a renderer turns by its facing as the blockstate turns the model; times are ticks; angles degrees. Each mesh part's origin is its
 * pivot: the renderer translates to where the table below puts it, rotates about it, then scale(-1f, -1f, 1f) and submitModelPart, as
 * WorksHoistRenderer does.
 */
internal object HouseArt {{
    const val MAP_TEXTURE = "textures/block/trade/house_pieces.png"
    const val BOARDS_TEXTURE = "textures/block/trade/house_boards.png"
    const val BELL_TEXTURE = "textures/block/trade/house_bell.png"

    // ---------------------------------------------------------------- the six colours

    /** The pennant colours, in the order of the parts "wagon_<colour>", "flag_<colour>" and "board_<colour>" (the booker's six). */
    val COLOUR_NAMES = arrayOf({names})
    val COLOUR_RGB = intArrayOf({rgbs})

    /** The index in [COLOUR_NAMES] of the colour nearest [rgb] (0xRRGGBB): which wagon, pennant and board a House of that colour gets. */
    fun nearest(rgb: Int): Int {{
        val r = (rgb shr 16) and 255
        val g = (rgb shr 8) and 255
        val b = rgb and 255
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in COLOUR_RGB.indices) {{
            val c = COLOUR_RGB[i]
            val dr = ((c shr 16) and 255) - r
            val dg = ((c shr 8) and 255) - g
            val db = (c and 255) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) {{
                bestD = d
                best = i
            }}
        }}
        return best
    }}

    // ---------------------------------------------------------------- the map

    /**
     * The parchment in the map block's model space: the map block is the middle of the bottom row of a panel three blocks wide and two
     * high, so its model reaches from x -16 to 32 and y 0 to 32 (a renderer's bounding box must take in the neighbouring blocks, which
     * must stay free). The parchment is [MAP_X0] (the viewer's right edge, west) to [MAP_X1] and [MAP_Y0] to [MAP_Y1], its face at [MAP_Z]:
     * every piece of the mesh is drawn with its origin on that plane, standing out toward the viewer (-z). The map's axes are fixed on the
     * parchment, whichever way the wall faces: up is world north (-z), the viewer's right is world east (+x), which in model space
     * is toward -x.
     */
    const val MAP_X0 = {kf(MAP_X[0])}
    const val MAP_X1 = {kf(MAP_X[1])}
    const val MAP_Y0 = {kf(MAP_Y[0])}
    const val MAP_Y1 = {kf(MAP_Y[1])}
    const val MAP_Z = {kf(MAP_Z)}
    const val MAP_CX = {kf(MAP_CX)}
    const val MAP_CY = {kf(MAP_CY)}

    /** The least a pin or a thread's end stands from the parchment's edge (the border and its ticks lie in it), and the most px a world block is drawn as. */
    const val MAP_MARGIN = {kf(MAP_MARGIN)}
    const val MAP_MAX_SCALE = {kf(MAP_MAX_SCALE)}

    /** Px a world block is drawn as, so that towns [spanX] x [spanZ] blocks apart fit the parchment inside its margin (one alone is drawn at [MAP_MAX_SCALE]). */
    fun mapScale(spanX: Float, spanZ: Float): Float {{
        val innerW = (MAP_X1 - MAP_X0) - 2f * MAP_MARGIN
        val innerH = (MAP_Y1 - MAP_Y0) - 2f * MAP_MARGIN
        if (spanX < 1f && spanZ < 1f) return MAP_MAX_SCALE
        return minOf(MAP_MAX_SCALE, innerW / maxOf(spanX, 1f), innerH / maxOf(spanZ, 1f))
    }}

    /** The map's x (model space) of world x [wx], with world [cx] at the parchment's middle and [k] px a block (see [mapScale]): east is the viewer's right, so toward -x. */
    fun mapX(wx: Float, cx: Float, k: Float): Float = MAP_CX - (wx - cx) * k

    /** The map's y of world z [wz]: north (toward -z) is up. */
    fun mapY(wz: Float, cz: Float, k: Float): Float = MAP_CY - (wz - cz) * k

    /**
     * A pin head's diameter, "pin_0" to "pin_3": sizes 0 to 2 for the other capitals (the renderer's pick: part 37 shows by a pin's size how
     * much the House trades at that capital; a town's class serves as well), 3 the House's own capital, the last with a gilt ring [PIN_HOME_RING]
     * times its head; and the pin's scale [age] ticks after it was set: grows from nothing, a little past, and settles.
     */
    val PIN_D = {_kt_arr(PIN_D)}
    const val PIN_HOME_RING = {kf(PIN_HOME_RING)}
    const val POP_TICKS = {kf(POP_TICKS)}
    private const val POP_OVER = {kf(POP_OVER)}

    fun pinPop(age: Float): Float {{
        if (age <= 0f) return 0f
        if (age >= POP_TICKS) return 1f
        val w = age / POP_TICKS - 1f
        return 1f + (POP_OVER + 1f) * w * w * w + POP_OVER * w * w
    }}

    /** The scale of the sparkle ("glint", drawn over pin [i], one z in front of it) at tick [t]: nothing for most of {GLINT_PERIOD:g} ticks, then a flare and gone. */
    fun glint(t: Float, i: Int): Float {{
        val u = ((t + i * 23f) % {kf(GLINT_PERIOD)}) / {kf(GLINT_TICKS)}
        return if (u < 1f) sin(PI.toFloat() * u) else 0f
    }}

    /**
     * A thread ("thread", or "thread_hot" for the road in use) is a unit long along +x: draw it at one pin, turn it about z by
     * atan2(dy, dx) in the map's own x and y (so the angle is the model's), and scale it by the length; [threadGrow] is the part of that
     * length run out [age] ticks after it was laid.
     */
    const val THREAD_GROW_TICKS = {kf(THREAD_GROW_TICKS)}
    fun threadGrow(age: Float): Float = smooth(age / THREAD_GROW_TICKS)

    /**
     * A wagon ("wagon_<colour>") stands on the contact of its wheels at the origin, facing -x (the viewer's right), drawn [WAGON_DZ]
     * toward the viewer from the parchment. It is {kf(WAGON_LEN)} px long. Its wheels are not part of it: draw "wheel" at ([WHEEL_X0],
     * [WHEEL_Y]) and ([WHEEL_X1], [WHEEL_Y]) from the origin, at z -[WHEEL_Z] (the side that faces the viewer, whichever way the wagon
     * faces), turned about z by [wheelTurn]. Its pennant, "flag_<colour>", is drawn at ([FLAG_X], [FLAG_Y]) and streams toward +x.
     *
     * In order: translate to the wagon's place (z [WAGON_DZ] from the parchment, y up by [wagonBob]), turn about z by [wagonTilt] +
     * [wagonRock] for its move (dx, dy) in the map's x and y. Then, in that tilted frame: the wheels, drawn as they are, each turned
     * about z by [wheelTurn]; and, turned about y by [wagonFlip] first, the wagon and its pennant (the pennant at ([FLAG_X], [FLAG_Y]) in that
     * flipped frame, turned about y by [flagYaw] and about z by [flagRoll] at its mast). The wagon's own shadow on the parchment is part of it.
     */
    const val WAGON_DZ = {kf(WAGON_DZ)}
    const val WHEEL_X0 = {kf(WHEEL_AT[0][0])}
    const val WHEEL_X1 = {kf(WHEEL_AT[1][0])}
    const val WHEEL_Y = {kf(WHEEL_AT[0][1])}
    const val WHEEL_Z = {kf(WHEEL_Z)}
    const val WHEEL_R = {kf(WHEEL_R)}
    const val FLAG_X = {kf(FLAG_AT[0])}
    const val FLAG_Y = {kf(FLAG_AT[1])}
    private const val BOB_PX = {kf(BOB_PX)}
    private const val ROCK_DEG = {kf(ROCK_DEG)}

    /** Degrees a wheel is turned about z after its wagon has gone [dist] px toward the viewer's right (negative: toward the left). */
    fun wheelTurn(dist: Float): Float = Math.toDegrees((dist / WHEEL_R).toDouble()).toFloat()

    /** Degrees the wagon is turned about up, after its tilt, for a move of [dx] along the map's x: it faces -x (the viewer's right), so 180 for dx > 0. */
    fun wagonFlip(dx: Float): Float = if (dx > 0f) 180f else 0f

    /** Degrees the wagon is turned about z (positive: clockwise as the viewer sees it) to lie along a move of ([dx], [dy]) in the map's x and y. */
    fun wagonTilt(dx: Float, dy: Float): Float {{
        if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return 0f
        val a = Math.toDegrees((if (dx <= 0f) atan2(-dy, -dx) else atan2(dy, dx)).toDouble()).toFloat()
        return a.coerceIn(-38f, 38f)
    }}

    /** The wagon's hop over the ground (px) and its rocking about z (degrees) after it has gone [dist] px. */
    fun wagonBob(dist: Float, phase: Float): Float = BOB_PX * abs(sin(dist * 1.9f + phase))

    fun wagonRock(dist: Float, phase: Float): Float = ROCK_DEG * sin(dist * 1.3f + phase)

    /** Degrees a pennant is turned about up at its mast at tick [t] ([run] 0 standing to 1 at a run): it flutters in and out of the page. */
    fun flagYaw(t: Float, phase: Float, run: Float): Float {{
        val amp = {kf(FLAG_YAW[0])} + ({kf(FLAG_YAW[1] - FLAG_YAW[0])}) * run
        return amp * (sin(0.33f * t + phase) + 0.45f * sin(0.81f * t + 2f * phase))
    }}

    /** Degrees a pennant is turned about z at its mast (positive lifts its tail): it streams a little up, and ripples. */
    fun flagRoll(t: Float, phase: Float, run: Float): Float {{
        val base = {kf(FLAG_ROLL[0])} + ({kf(FLAG_ROLL[1] - FLAG_ROLL[0])}) * run
        return base * (0.7f + 0.5f * sin(0.47f * t + phase + 1f))
    }}

    // ---------------------------------------------------------------- the name board

    /** The name board's rings' line: "board_<colour>" is drawn there, swinging about it (x), and hangs [BOARD_DROP] under it, {S.BOARD[0]:g} across, {S.BOARD[1]:g} high. */
    const val BOARD_X = {kf(S.BOARD_PIVOT[0])}
    const val BOARD_Y = {kf(S.BOARD_PIVOT[1])}
    const val BOARD_Z = {kf(S.BOARD_PIVOT[2])}
    const val BOARD_W = {kf(S.BOARD[0])}
    const val BOARD_H = {kf(S.BOARD[1])}
    const val BOARD_DROP = {kf(S.BOARD_DROP)}

    /**
     * The lettering on both faces of the board: each line's top under the board's top, its capitals' height and the most it may be across (px),
     * centred across the board; written [NAME_TEXT_Z] out from the board's middle on either face (the back's turned about up).
     */
    val NAME_LINES = arrayOf(
{lines},
    )
    const val NAME_TEXT_Z = {kf(S.NAME_TEXT_Z)}
    const val NAME_INK = {K.kint(S.NAME_INK)}
    const val NAME_EDGE = {K.kint(S.NAME_EDGE)}

    /** Degrees the board swings out on its rings at tick [t] (its foot toward the street, -z, when positive; [phase] from its place; twice as far in rain). */
    fun boardSwing(t: Float, phase: Float, wet: Boolean): Float =
        (if (wet) 2f else 1f) * ({kf(bs[0][0])} * sin({kf(bs[0][1])} * t + phase) + {kf(bs[1][0])} * sin({kf(bs[1][1])} * t + 2f * phase))

    // ---------------------------------------------------------------- the bell

    /**
     * The bell's axle in its block's model space. "bell", "clapper" and the wheel on the axle turn about x through it: the bell by [bellSwing]
     * (rotateDegrees(Axis.XP, angle) in model space: positive swings its mouth toward -z, the street), the clapper by that plus [clapperRel].
     * "rope" is drawn at the wheel's front rim, [ropeY] and [ropeZ] from the axle, and swings from there by [ropeSway].
     */
    const val BELL_X = {kf(BELL_AXLE[0])}
    const val BELL_Y = {kf(BELL_AXLE[1])}
    const val BELL_Z = {kf(BELL_AXLE[2])}
    const val ROPE_X = {kf(WHEEL_X)}
    const val WHEEL_RAD = {kf(WHEEL_RAD)}

    /** Ticks a ring lasts; [BELL_STRIKES] are the ticks after the pull at which the clapper meets the bell (a sound each). */
    const val RING_TICKS = {kf(RING_TICKS)}
    val BELL_STRIKES = {_kt_arr(strike)}
    private const val BELL_AMP = {kf(BELL_AMP)}
    private const val BELL_PERIOD = {kf(BELL_PERIOD)}
    private const val BELL_TAU = {kf(BELL_TAU)}
    private const val BELL_RISE = {kf(BELL_RISE)}
    private const val CLAPPER_GAIN = {kf(CLAPPER_GAIN)}
    private const val CLAPPER_LAG = {kf(CLAPPER_LAG)}
    const val CLAPPER_GAP = {kf(CLAPPER_GAP)}
    private const val ROPE_TAU = {kf(ROPE_TAU)}
    private const val ROPE_PERIOD = {kf(ROPE_PERIOD)}
    private const val ROPE_AMP = {kf(ROPE_AMP)}

    /** 1 while a ring rings, easing to 0 over its last 24 ticks. */
    fun ringFade(age: Float): Float = 1f - smooth((age - (RING_TICKS - 24f)) / 24f)

    /** Degrees the bell is turned [age] ticks after the rope was pulled: pulled down first (so the first swing is negative), building over {BELL_RISE:g} ticks and dying away, gone by [RING_TICKS]. */
    fun bellSwing(age: Float): Float {{
        if (age <= 0f || age >= RING_TICKS) return 0f
        val amp = BELL_AMP * smooth(age / BELL_RISE) * exp(-maxOf(0f, age - BELL_RISE) / BELL_TAU) * ringFade(age)
        return -amp * sin(2f * PI.toFloat() * age / BELL_PERIOD)
    }}

    /** The bell at rest, never quite still in the wind: degrees at tick [t]. */
    fun idleSwing(t: Float): Float = 0.9f * sin(0.05f * t) + 0.5f * sin(0.13f * t + 1f)

    /** The clapper's turn against the bell, degrees: where it hangs less the bell's own turn, never more than [CLAPPER_GAP] (the wall it strikes). */
    fun clapperRel(age: Float): Float = (CLAPPER_GAIN * bellSwing(age - CLAPPER_LAG) * ringFade(age) - bellSwing(age)).coerceIn(-CLAPPER_GAP, CLAPPER_GAP)

    /** The rope's top (the wheel's front rim) while the bell is turned [theta] degrees: how far up it is carried from where it hangs at rest, and back toward the wall. */
    fun ropeY(theta: Float): Float = WHEEL_RAD * sin(Math.toRadians(theta.toDouble()).toFloat())

    fun ropeZ(theta: Float): Float = WHEEL_RAD * (1f - cos(Math.toRadians(theta.toDouble()).toFloat()))

    /** Degrees the hanging rope swings about x from the wheel [age] ticks after a pull. */
    fun ropeSway(age: Float): Float {{
        if (age <= 0f || age >= RING_TICKS) return 0f
        return ROPE_AMP * exp(-age / ROPE_TAU) * sin(2f * PI.toFloat() * age / ROPE_PERIOD + 1f) * ringFade(age)
    }}

    // ---------------------------------------------------------------- the common

    private fun smooth(u: Float): Float {{
        val x = u.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }}

    // ---------------------------------------------------------------- the meshes

{funs}
}}
'''


# ================================================================== the check of what was written
def check(assets, kotlin_dir, models_dir, meshes_):
    """What this script wrote, looked at as the game would load it (trade_works_kit.Check)."""
    c = K.Check(assets)
    for bid in models():
        c.blockstate(bid)
    for m in meshes_:
        c.mesh_texture(m.texture, m.model.tex_w, m.model.tex_h, m.scale)
        if not os.path.exists(os.path.join(models_dir, m.texture + '.bbmodel')):
            c.bad('art/models/%s.bbmodel is missing' % m.texture)
    byname = {m.fun: m for m in meshes_}
    c.mesh_parts(byname['mapMesh'], [f'pin_{k}' for k in range(len(PIN_D))] + ['glint', 'thread', 'thread_hot', 'wheel']
                 + [f'wagon_{n}' for n in NAMES] + [f'flag_{n}' for n in NAMES])
    c.mesh_parts(byname['boardsMesh'], [f'board_{n}' for n in NAMES])
    c.mesh_parts(byname['bellMesh'], ['bell', 'clapper', 'rope'])
    ks = strikes()
    if len(ks) < 4 or ks[0] > 14.0:
        c.bad('the bell strikes %d times, the first at %s: it should ring out' % (len(ks), ks[:1]))
    top = max(abs(bell_swing(a)) for a in np.arange(0.0, RING_TICKS, 0.25))
    if top > BELL_AMP * 1.05:
        c.bad('the bell swings past its amplitude')
    if abs(bell_swing(RING_TICKS - 0.01)) > 0.05 or abs(clapper_rel(RING_TICKS - 0.01)) > 0.05:
        c.bad('the bell does not come to rest by RING_TICKS')
    if not (MAP_X[0] >= -16.0 and MAP_X[1] <= 32.0 and MAP_Y[0] >= 0.0 and MAP_Y[1] <= 32.0):
        c.bad('the parchment reaches beyond the three-by-two blocks one block model may cover')
    if MAP_Z + WAGON_DZ - WHEEL_Z - 0.2 < 0.0:
        c.bad('the map\'s wagons stand out of the block\'s own depth')
    for i, (n, rgb, _, _) in enumerate(COLOURS):
        if nearest(K.rgb_int(rgb)) != i:
            c.bad('the colour ' + n + ' is not its own nearest')
    kt = os.path.join(kotlin_dir, 'HouseArt.kt')
    if not os.path.exists(kt):
        c.bad('HouseArt.kt is missing')
    return c.problems


def main(argv):
    if len(argv) < 4:
        raise SystemExit(__doc__)
    assets, kotlin_dir, models_dir = argv[1], argv[2], argv[3]
    prev = argv[4] if len(argv) > 4 else None
    tdir = os.path.join(assets, 'textures', 'block', 'trade')
    for name, img in block_textures().items():
        K.save_png(os.path.join(tdir, name + '.png'), img)
    for name, m in models().items():
        K.write(os.path.join(assets, 'models', 'block', name + '.json'), K.dump_json(m))
    for bid, bs in blockstates().items():
        K.write(os.path.join(assets, 'blockstates', bid + '.json'), K.dump_json(bs))
    ms = meshes()
    for m in ms:
        tex = m.paint()
        K.save_png(os.path.join(tdir, m.texture + '.png'), tex)
        K.write_bbmodel(models_dir, m.texture, m, tex)
    K.write(os.path.join(kotlin_dir, 'HouseArt.kt'), kotlin_house(ms))
    problems = check(assets, kotlin_dir, models_dir, ms)
    print('house_art: %d blocks, %d meshes (%d cubes)' % (len(models()), len(ms), sum(len(list(m.model.all_cubes())) for m in ms)))
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  ' + p)
        raise SystemExit(1)
    if prev:
        import trade_works_preview as TP
        for p in TP.house_previews(assets, prev):
            print('preview', p, os.path.getsize(p) // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv)
