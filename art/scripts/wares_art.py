"""The capitals' market displays: the blocks their goods are heaped on (economy/WaresBlock.kt, drawn with the goods by
client/economy/WaresRenderer.kt), at 32x in the dark-fantasy look of the mod's other blocks.

    python3 -B art/scripts/wares_art.py [assets dir [preview dir]]

With no arguments it writes into the repo's assets (`src/main/resources/assets/guildmark`; a `-` means that too). Eight blocks, all
authored facing north (their front, the side the customers stand at, toward -z):

    produce_basket   a low wicker basket lined with sacking, a willow handle at each end
    fish_crate       a slatted crate with a bed of crushed ice in it, its corner posts standing proud
    meat_rack        a butcher's board with a rack behind it, four iron hooks hanging from its bar
    cloth_board      a trestle board for folded cloth and hides
    arrow_rack       a board with a bin for arrows, a wicker basket for feathers and a shallow tray for flint
    candle_tray      two broad steps for candles and bottles to stand in rows on
    bread_shelf      a bottom board and a shelf above it at the back, a rail behind the loaves
    grain_sacks      two open sacks of grain, their mouths rolled down

The goods themselves are the game's own item models, drawn by the renderer, so the places they lie (economy/WaresLayout.kt) and
these models have to agree: the floors, rims, hooks, bins and mouths are at the same pixels in both. The textures are shared
between the blocks (textures/block/wares/): slatted crate boards, planed planks, dark posts, woven willow and its braided rim,
burlap and a sack's open mouth, crushed ice, and iron. Everything is seeded: a rerun gives the same files. With a preview dir it
also writes `wares_art.png`, each block drawn from its model file from the front and from above, and the textures, magnified.

Files written (under the assets dir):
    textures/block/wares/crate.png, plank.png, post.png, wicker.png, burlap.png, sack_mouth.png, ice.png, iron.png      32x32
    models/block/<name>.json, blockstates/<name>.json (facing north, east, south, west), items/<name>.json   for the eight above
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
from hdkit import PAL, Canvas, grid, vnoise, fbm, rand, bands, mix, tone, blur, rect, light, cavity, shift, sheet
import island_blockview

N = 32
NAMES = ['produce_basket', 'fish_crate', 'meat_rack', 'cloth_board', 'arrow_rack', 'candle_tray', 'bread_shelf', 'grain_sacks']

# Pale weathered pine for crates, a warmer planed wood for boards and shelves, dark stained posts; willow, burlap, ice, iron
PINE = [(34, 26, 24), (66, 52, 42), (100, 80, 60), (132, 108, 80), (162, 136, 100), (190, 164, 124)]
OAK = PAL['wood']
DARK = PAL['darkwood']
WILLOW = [(32, 24, 20), (60, 46, 32), (92, 72, 46), (122, 98, 62), (150, 124, 80), (176, 150, 102)]
BURLAP = [(36, 28, 22), (68, 54, 40), (100, 82, 58), (130, 108, 76), (158, 134, 96), (184, 162, 120)]
ICE = [(34, 46, 62), (62, 84, 104), (98, 126, 146), (138, 166, 182), (178, 202, 212), (214, 230, 234)]
IRON = PAL['iron']


# ================================================================ textures

def boards(cv, pal, seed, height, gap_dark=0.36, lift=0.0, vertical=False, nails=()):
    """Boards across the texture `height` texels wide with a dark crack between, grain running along them, a knot or two;
    nail heads at the (along, board) spots in `nails`."""
    xs, ys = grid(N)
    along, across = (ys, xs) if vertical else (xs, ys)
    row = np.floor(across / height)
    within = across - row * height
    n = fbm(N, N, 4, seed)
    streak = vnoise(N, N, 3, seed + 9)
    t = 0.5 + lift + 0.15 * (n - 0.5) + 0.09 * np.sin(across * 2.3 + streak * 6.5 + row * 2.9) + 0.08 * np.sin(row * 2.1 + seed)
    # Each board's own shade, its top edge catching the light and its bottom edge in the crack's shadow
    t += 0.06 * np.sin(row * 4.7 + seed * 1.3)
    t = np.where(within < 1.0, t + 0.12, t)
    t = np.where(within >= height - 1.0, t - gap_dark, t)
    t = np.where((within >= height - 2.0) & (within < height - 1.0), t - 0.06, t)
    # A knot: a dark eye with grain bending round it
    rng = np.random.default_rng(seed)
    for _ in range(2):
        kx, ky = rng.uniform(4, 28), rng.uniform(4, 28)
        d = np.hypot((along - kx) * 0.7, across - ky)
        t = np.where(d < 1.3, t - 0.28, np.where(d < 2.4, t + 0.05 * np.cos(d * 4), t))
    cv.put(np.ones((N, N), bool), bands(pal, t, 0.22))
    for a, b in nails:
        x, y = (b, a) if vertical else (a, b)
        cv.px(x, y, IRON[3])
        cv.px(x + 1, y, IRON[1])
        cv.px(x, y + 1, IRON[1])
        cv.px(x + 1, y + 1, IRON[0])


def crate():
    """Slatted crate boards of pale pine, two to a crate's side (five texels and a crack), nailed at the ends."""
    cv = Canvas(N)
    nails = [(x, y) for y in (1, 6, 11, 16, 21, 26) for x in (3, 27)]
    boards(cv, PINE, 41, 5, gap_dark=0.42, lift=0.04, nails=nails)
    # Grime in the cracks, a brand burnt on one board
    m = rand(N, N, 43) > 0.86
    cv.over(m, PINE[1], 0.35)
    xs, ys = grid(N)
    brand = (np.abs(xs - 15.5) < 2.2) & (np.abs(ys - 23) < 1.2) & (((xs + ys) % 3) < 1.6)
    cv.over(brand, PINE[0], 0.55)
    return cv.image()


def plank():
    """Planed oak planks, three pixels to a plank, for boards, shelves, steps and trays."""
    cv = Canvas(N)
    boards(cv, OAK, 51, 6, gap_dark=0.3, lift=0.08, nails=[(2, 2), (29, 2), (2, 14), (29, 14), (2, 26), (29, 26)])
    # Worn pale where hands and goods rub it, darker scuffs
    xs, ys = grid(N)
    wear = fbm(N, N, 8, 53)
    cv.over(wear > 0.62, OAK[5], 0.25)
    cv.over(rand(N, N, 55) > 0.93, OAK[1], 0.4)
    return cv.image()


def post():
    """Dark stained wood with its grain up and down, for posts, rails and trestles."""
    cv = Canvas(N)
    xs, ys = grid(N)
    n = fbm(N, N, 5, 61)
    streak = vnoise(N, N, 2, 63)
    t = 0.5 + 0.22 * (n - 0.5) + 0.12 * np.sin(xs * 1.9 + streak * 5.0)
    # Darker toward the ends, where it's handled
    t -= 0.08 * (np.abs(ys - 16) / 16) ** 2
    cv.put(np.ones((N, N), bool), bands(DARK, t + 0.1, 0.22))
    return cv.image()


def wicker():
    """Woven willow: courses of weavers four texels high, each passing in front of the upright stakes for six texels and
    behind one for two, every course set half a turn on from the one below; across the top four rows a braided rim (the
    baskets map their rims to those rows)."""
    cv = Canvas(N)
    xs, ys = grid(N)
    x = np.floor(xs).astype(int)
    y = np.floor(ys).astype(int)
    course = y // 4
    row = y % 4
    u = (x + course * 4) % 8
    n = fbm(N, N, 3, 71)
    # A weaver is round: lit along its top, in shadow along its bottom, darker toward its ends where it turns behind
    t = np.choose(row, [0.6, 0.52, 0.42, 0.24])
    t = t - np.where((u == 2) | (u == 7), 0.08, 0.0)
    # The stake showing between, set back in the weavers' shadow and lit on its left
    t = np.where(u < 2, np.where(u == 0, 0.32, 0.22) - 0.04 * row, t)
    # Each willow rod its own shade
    rod = rand(8, 8, 73)[course % 8, ((x + course * 4) // 8) % 8]
    t = t + 0.08 * (rod - 0.5) + 0.08 * (n - 0.5)
    # The braided rim: two strands twisting along it, each a slanted lozenge lit on its upper left
    s = (x + y) % 4
    braid = np.choose(s, [0.58, 0.5, 0.4, 0.2]) - 0.06 * (row == 3)
    t = np.where(y < 4, braid + 0.06 * (n - 0.5), t)
    cv.put(np.ones((N, N), bool), bands(WILLOW, t, 0.18))
    return cv.image()


def burlap():
    """Coarse sack cloth: a loose weave of thick threads, slubs here and there, a few creases running down and a seam."""
    cv = Canvas(N)
    xs, ys = grid(N)
    warp = np.sin(xs * math.pi) ** 2
    weft = np.sin(ys * math.pi) ** 2
    over = ((np.floor(xs) + np.floor(ys)) % 2) == 0
    thread = np.where(over, warp * 0.6 + 0.4, weft * 0.6 + 0.4)
    n = fbm(N, N, 6, 81)
    slub = rand(N, N, 83)
    # Creases: soft dark folds running down, lit on their left
    crease = 0.5 * np.sin(xs * 0.55 + 2.0 * vnoise(N, N, 8, 85) * math.pi) + 0.3 * np.sin(xs * 1.3 + 1.0)
    t = 0.42 + 0.18 * (thread - 0.7) + 0.2 * (n - 0.5) + 0.1 * crease
    t = np.where(slub > 0.94, t + 0.12, t)
    t = np.where(slub < 0.04, t - 0.14, t)
    # A seam down one side, stitched
    seam = np.abs(xs - 27.5) < 0.6
    t = np.where(seam, t - 0.22, t)
    t = np.where(seam & ((ys % 3) < 1), t + 0.3, t)
    cv.put(np.ones((N, N), bool), bands(BURLAP, t, 0.2))
    return cv.image()


def sack_mouth():
    """A sack's open mouth from above: the rolled-down rim round the edge, lit from the upper left, and inside it the dark
    of the empty sack (what it holds the renderer stands in it)."""
    cv = Canvas(N)
    xs, ys = grid(N)
    dx, dy = (xs - 16) / 14.5, (ys - 16) / 14.5
    # A rounded square, the shape the rolled rim makes
    d = (np.abs(dx) ** 3.2 + np.abs(dy) ** 3.2) ** (1 / 3.2)
    roll = np.clip(1 - np.abs(d - 0.82) / 0.2, 0, 1)
    H = np.sqrt(roll)
    lit = light(H * 4, 1.0)
    n = fbm(N, N, 4, 91)
    weave = ((np.floor(xs) + np.floor(ys)) % 2) * 0.05
    t_rim = 0.3 + 0.42 * H + lit * 1.1 + 0.1 * (n - 0.5) + weave
    inside = d < 0.64
    # The hollow: the far (lower right) wall in the rim's shadow, the near one catching a little light down its side
    hole = np.clip(1 - d / 0.64, 0, 1)
    t_hole = 0.06 + 0.14 * hole + 0.1 * np.clip(-(dx + dy), 0, 1) - 0.08 * np.clip(dx + dy, 0, 1) + 0.05 * (n - 0.5)
    cv.put(np.ones((N, N), bool), bands(BURLAP, np.where(inside, t_hole, t_rim), 0.2))
    # Creases running down into it from the rim
    for k in range(10):
        a = k * math.tau / 10 + 0.3
        seg = (np.abs((xs - 16) * math.sin(a) - (ys - 16) * math.cos(a)) < 0.5) & ((xs - 16) * math.cos(a) + (ys - 16) * math.sin(a) > 0)
        cv.over(seg & (d > 0.5) & (d < 0.7), BURLAP[0], 0.35)
    return cv.image()


def ice():
    """Crushed ice: angular chunks, each lit on the faces toward the light, with blue shadow and wet dark between them."""
    cv = Canvas(N)
    xs, ys = grid(N)
    rng = np.random.default_rng(101)
    pts = rng.uniform(0, N, (34, 2))
    # Nearest and second nearest chunk centre, wrapping so the texture tiles
    best = np.full((N, N), 1e9)
    second = np.full((N, N), 1e9)
    owner = np.zeros((N, N), int)
    for i, (px, py) in enumerate(pts):
        for ox in (-N, 0, N):
            for oy in (-N, 0, N):
                d = np.hypot(xs - px - ox, ys - py - oy)
                closer = d < best
                second = np.where(closer, best, np.minimum(second, d))
                owner = np.where(closer, i, owner)
                best = np.where(closer, d, best)
    edge = second - best
    # Each chunk a low pyramid tilted its own way
    tilt = rng.uniform(-1, 1, (len(pts), 2))
    H = np.clip(edge, 0, 3) * 0.9 + tilt[owner, 0] * (xs % 7) * 0.08 + tilt[owner, 1] * (ys % 7) * 0.08
    lit = light(H, 1.4)
    n = fbm(N, N, 4, 103)
    t = 0.56 + lit * 1.0 + 0.1 * (n - 0.5) + 0.08 * tilt[owner, 0]
    t = np.where(edge < 0.7, t - 0.24, t)
    cv.put(np.ones((N, N), bool), bands(ICE, t, 0.25))
    # Glints of wet light on a few chunk tops
    for i, (px, py) in enumerate(pts[:12]):
        x, y = int(px) % N, int(py) % N
        if (i % 3) == 0:
            cv.px(x, y, (232, 242, 244))
    return cv.image()


def iron():
    """Dark wrought iron with a bloom of rust."""
    cv = Canvas(N)
    xs, ys = grid(N)
    n = fbm(N, N, 4, 111)
    t = 0.45 + 0.3 * (n - 0.5) + 0.1 * np.sin(ys * 1.7)
    cv.put(np.ones((N, N), bool), bands(IRON, t, 0.25))
    rust = (fbm(N, N, 5, 113) > 0.6) & (rand(N, N, 115) > 0.4)
    cv.over(rust, (112, 62, 36), 0.45)
    return cv.image()


TEXTURES = {
    'crate': crate, 'plank': plank, 'post': post, 'wicker': wicker, 'burlap': burlap, 'sack_mouth': sack_mouth, 'ice': ice, 'iron': iron,
}


# ================================================================ models

def r3(v):
    v = round(float(v), 3)
    return int(v) if v == int(v) else v


def default_uv(frm, to, d):
    """The uv a face would take by default (FaceBakery.defaultFaceUV), written out."""
    fx, fy, fz = frm
    tx, ty, tz = to
    return {
        'down': (fx, 16 - tz, tx, 16 - fz), 'up': (fx, fz, tx, tz),
        'north': (16 - tx, 16 - ty, 16 - fx, 16 - fy), 'south': (fx, 16 - ty, tx, 16 - fy),
        'west': (fz, 16 - ty, tz, 16 - fy), 'east': (16 - tz, 16 - ty, 16 - fz, 16 - fy),
    }[d]


def box(frm, to, tex, faces=('down', 'up', 'north', 'south', 'west', 'east'), uv=None, cull=(), rot=None):
    """An element from `frm` to `to` (pixels) with the given faces in texture `tex`: each face's uv its default (so boards and
    weave run on from one element to the next) unless `uv` gives one ({face: uv} or one uv for all). Faces at the block's
    bottom in `cull` are left to the block below to hide."""
    e = {'from': [r3(v) for v in frm], 'to': [r3(v) for v in to]}
    if rot:
        axis, angle, origin = rot
        e['rotation'] = {'angle': angle, 'axis': axis, 'origin': [r3(v) for v in origin]}
    fs = {}
    for d in faces:
        u = uv.get(d) if isinstance(uv, dict) else uv
        if u is None:
            u = default_uv(frm, to, d)
            # A face's uv must run within the texture: keep a default one inside 0..16
            u = tuple(min(16, max(0, v)) for v in u)
        f = {'uv': [r3(v) for v in u], 'texture': '#' + tex}
        if d in cull:
            f['cullface'] = d
        fs[d] = f
    e['faces'] = fs
    return e


def model(textures, elements, particle):
    tex = {'particle': 'guildmark:block/wares/' + particle}
    for t in textures:
        tex[t] = 'guildmark:block/wares/' + t
    return {'parent': 'minecraft:block/block', 'textures': tex, 'elements': elements}


def produce_basket():
    """A low wicker basket, wider than deep, lined with sacking (its floor at 3.6, the rim at 6.5), a willow handle at each end."""
    els = [
        box((2, 0, 3), (14, 1, 13), 'wicker', cull=('down',)),
        # The walls, the corners left open so it reads round
        box((2, 0, 2), (14, 6, 3), 'wicker', faces=('north', 'south', 'down'), cull=('down',)),
        box((2, 0, 13), (14, 6, 14), 'wicker', faces=('north', 'south', 'down'), cull=('down',)),
        box((1, 0, 3), (2, 6, 13), 'wicker', faces=('west', 'east', 'down'), cull=('down',)),
        box((14, 0, 3), (15, 6, 13), 'wicker', faces=('west', 'east', 'down'), cull=('down',)),
        # The sacking it's lined with, on its false floor
        box((2, 1, 3), (14, 3.6, 13), 'burlap', faces=('up',)),
    ]
    # The braided rim, a little proud of the walls all round
    band = {'north': (0, 0, 16, 2), 'south': (0, 0, 16, 2), 'east': (0, 0, 16, 2), 'west': (0, 0, 16, 2), 'up': (0, 0, 16, 2), 'down': (0, 0, 16, 2)}
    els += [
        box((1.75, 5.5, 1.75), (14.25, 6.5, 3.25), 'wicker', uv=band),
        box((1.75, 5.5, 12.75), (14.25, 6.5, 14.25), 'wicker', uv=band),
        box((0.75, 5.5, 3.25), (2.25, 6.5, 12.75), 'wicker', uv=band),
        box((13.75, 5.5, 3.25), (15.25, 6.5, 12.75), 'wicker', uv=band),
    ]
    # A willow loop for a handle at each end
    for x0, x1 in ((0.25, 1.0), (15.0, 15.75)):
        els += [
            box((x0, 4.5, 6.4), (x1, 7.8, 7.1), 'wicker', uv=band),
            box((x0, 4.5, 8.9), (x1, 7.8, 9.6), 'wicker', uv=band),
            box((x0, 7.8, 6.4), (x1, 8.5, 9.6), 'wicker', uv=band),
        ]
    return model(['wicker', 'burlap'], els, 'wicker')


def fish_crate():
    """A slatted crate with crushed ice packed in it to 3 pixels, its sides to 6, corner posts standing a little proud."""
    els = [
        box((1, 0, 1), (15, 1, 15), 'crate', cull=('down',)),
        box((2, 0, 1), (14, 6, 2), 'crate', faces=('north', 'south', 'up')),
        box((2, 0, 14), (14, 6, 15), 'crate', faces=('north', 'south', 'up')),
        box((1, 0, 2), (2, 6, 14), 'crate', faces=('west', 'east', 'up')),
        box((14, 0, 2), (15, 6, 14), 'crate', faces=('west', 'east', 'up')),
        box((2, 1, 2), (14, 3, 14), 'ice', faces=('up',)),
    ]
    for x in (0.75, 13.75):
        for z in (0.75, 13.75):
            els.append(box((x, 0, z), (x + 1.5, 6.5, z + 1.5), 'post', cull=('down',)))
    return model(['crate', 'post', 'ice'], els, 'crate')


def meat_rack():
    """A thick butcher's board, a rack behind it of two posts and a bar, and four iron hooks hanging from the bar."""
    els = [
        box((1, 0, 2), (15, 1.5, 12), 'plank', cull=('down',)),
        box((1, 0, 12.5), (15, 1, 14), 'post', cull=('down',)),
        box((1, 1, 12.5), (2.5, 15, 14), 'post'),
        box((13.5, 1, 12.5), (15, 15, 14), 'post'),
        box((2.5, 13.5, 12.75), (13.5, 14.75, 13.75), 'post'),
    ]
    for x in (12.5, 9.5, 6.5, 3.5):
        els += [
            box((x - 0.25, 12.4, 12.95), (x + 0.25, 13.5, 13.45), 'iron', uv=(0, 0, 1, 2)),
            box((x - 0.25, 11.9, 12.2), (x + 0.25, 12.4, 13.45), 'iron', uv=(2, 0, 3, 2)),
            box((x - 0.25, 12.4, 12.2), (x + 0.25, 12.9, 12.7), 'iron', uv=(4, 0, 5, 1)),
        ]
    return model(['plank', 'post', 'iron'], els, 'plank')


def cloth_board():
    """A trestle board: a plank top at 2.6 on two dark trestles."""
    els = [
        box((1, 1, 2), (15, 2.6, 14), 'plank'),
        box((2, 0, 2.5), (3.5, 1, 13.5), 'post', cull=('down',)),
        box((12.5, 0, 2.5), (14, 1, 13.5), 'post', cull=('down',)),
    ]
    return model(['plank', 'post'], els, 'plank')


def arrow_rack():
    """A board with a bin of boards for arrows at the back left, a wicker basket for feathers at the back right, and a
    shallow tray for flint across the front."""
    els = [box((1, 0, 2), (15, 1, 14), 'plank', cull=('down',))]
    # The bin, 8 high, its arrows' feet at 1.8
    els += [
        box((9, 1, 8), (14, 8, 8.75), 'crate', faces=('north', 'south', 'up', 'east', 'west')),
        box((9, 1, 12.25), (14, 8, 13), 'crate', faces=('north', 'south', 'up', 'east', 'west')),
        box((9, 1, 8.75), (9.75, 8, 12.25), 'crate', faces=('west', 'east', 'up')),
        box((13.25, 1, 8.75), (14, 8, 12.25), 'crate', faces=('west', 'east', 'up')),
        box((9.75, 1, 8.75), (13.25, 1.8, 12.25), 'crate', faces=('up',)),
    ]
    # The basket, 6 high, its feathers' quills at 2.6, a braided rim round its mouth
    band = (0, 0, 16, 2)
    els += [
        box((2, 1, 8), (7, 5.5, 8.75), 'wicker', faces=('north', 'south', 'east', 'west')),
        box((2, 1, 12.25), (7, 5.5, 13), 'wicker', faces=('north', 'south', 'east', 'west')),
        box((2, 1, 8.75), (2.75, 5.5, 12.25), 'wicker', faces=('west', 'east')),
        box((6.25, 1, 8.75), (7, 5.5, 12.25), 'wicker', faces=('west', 'east')),
        box((1.75, 5.5, 7.75), (7.25, 6.5, 8.75), 'wicker', uv=band),
        box((1.75, 5.5, 12.25), (7.25, 6.5, 13.25), 'wicker', uv=band),
        box((1.75, 5.5, 8.75), (2.75, 6.5, 12.25), 'wicker', faces=('up', 'down', 'west', 'east'), uv=band),
        box((6.25, 5.5, 8.75), (7.25, 6.5, 12.25), 'wicker', faces=('up', 'down', 'west', 'east'), uv=band),
        box((2.75, 1, 8.75), (6.25, 2.6, 12.25), 'burlap', faces=('up',)),
    ]
    # The tray, its floor at 1.6 and its lip at 2.4
    els += [
        box((2, 1, 1.6), (14, 1.6, 7.6), 'plank', faces=('up', 'north', 'south', 'east', 'west')),
        box((2, 1.6, 1.6), (14, 2.4, 2.2), 'post'),
        box((2, 1.6, 7.0), (14, 2.4, 7.6), 'post'),
        box((2, 1.6, 2.2), (2.6, 2.4, 7.0), 'post'),
        box((13.4, 1.6, 2.2), (14, 2.4, 7.0), 'post'),
    ]
    return model(['plank', 'crate', 'wicker', 'burlap', 'post'], els, 'plank')


def candle_tray():
    """Two broad steps: the front one 2 high, the back one 5, with a lip along the front."""
    els = [
        box((1, 0, 2), (15, 2, 8), 'plank', cull=('down',)),
        box((1, 0, 8), (15, 5, 14), 'plank', cull=('down',)),
        box((1, 2, 2), (15, 2.5, 2.6), 'post'),
    ]
    return model(['plank', 'post'], els, 'plank')


def bread_shelf():
    """A bottom board at 1.5 and a shelf at 8.5 over its back half between two thin side boards, a rail behind the loaves on each."""
    els = [
        box((1, 0, 2), (15, 1.5, 14), 'plank', cull=('down',)),
        box((1, 1.5, 7), (1.75, 10.5, 14), 'post'),
        box((14.25, 1.5, 7), (15, 10.5, 14), 'post'),
        box((1.75, 7, 7), (14.25, 8.5, 14), 'plank'),
        box((1.75, 8.5, 13.4), (14.25, 10, 14), 'post'),
        box((1.75, 1.5, 13.4), (14.25, 3, 14), 'post'),
    ]
    return model(['plank', 'post'], els, 'plank')


def grain_sacks():
    """Two open sacks side by side, bellied low, their mouths rolled down (the tops at 9 and 8): what they hold stands in
    them from 5 and 4.4."""
    mouth = {'up': (0, 0, 16, 16)}
    els = []
    for (x0, x1, z0, z1, top) in ((1.5, 7.5, 5, 12, 9), (8.5, 14.5, 4, 11, 8)):
        els += [
            # The body, its belly sagging out low down, the neck drawn in under the roll
            box((x0, 0, z0), (x1, top - 1.5, z1), 'burlap', faces=('north', 'south', 'east', 'west', 'down'), cull=('down',)),
            box((x0 - 0.5, 0.5, z0 - 0.5), (x1 + 0.5, top - 4.5, z1 + 0.5), 'burlap', faces=('north', 'south', 'east', 'west', 'up')),
            box((x0 - 0.25, top - 1.5, z0 - 0.25), (x1 + 0.25, top, z1 + 0.25), 'burlap', faces=('north', 'south', 'east', 'west', 'down')),
            box((x0 - 0.25, top - 1.5, z0 - 0.25), (x1 + 0.25, top, z1 + 0.25), 'sack_mouth', faces=('up',), uv=mouth),
        ]
    return model(['burlap', 'sack_mouth'], els, 'burlap')


MODELS = {
    'produce_basket': produce_basket, 'fish_crate': fish_crate, 'meat_rack': meat_rack, 'cloth_board': cloth_board,
    'arrow_rack': arrow_rack, 'candle_tray': candle_tray, 'bread_shelf': bread_shelf, 'grain_sacks': grain_sacks,
}


def blockstate(name):
    return {'variants': {f'facing={f}': ({'model': f'guildmark:block/{name}'} | ({'y': y} if y else {}))
                         for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}}


def item_definition(name):
    return {'model': {'type': 'minecraft:model', 'model': f'guildmark:block/{name}'}}


# ================================================================ writing

def dump_json(obj, indent=2, _level=0):
    """JSON the way the game's own files read, but a list of plain numbers or words on one line."""
    pad = ' ' * (indent * (_level + 1))
    end = ' ' * (indent * _level)
    if isinstance(obj, dict):
        if not obj:
            return '{}'
        rows = [f'{pad}{json.dumps(k)}: {dump_json(v, indent, _level + 1)}' for k, v in obj.items()]
        return '{\n' + ',\n'.join(rows) + '\n' + end + '}'
    if isinstance(obj, (list, tuple)):
        if all(not isinstance(v, (dict, list, tuple)) for v in obj):
            return '[' + ', '.join(json.dumps(v) for v in obj) + ']'
        rows = [pad + dump_json(v, indent, _level + 1) for v in obj]
        return '[\n' + ',\n'.join(rows) + '\n' + end + ']'
    return json.dumps(obj)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(text + '\n')


def build(assets):
    tex_dir = os.path.join(assets, 'textures', 'block', 'wares')
    os.makedirs(tex_dir, exist_ok=True)
    images = {}
    for name, paint in TEXTURES.items():
        images[name] = paint()
        images[name].save(os.path.join(tex_dir, name + '.png'))
    for name in NAMES:
        write(os.path.join(assets, 'models', 'block', name + '.json'), dump_json(MODELS[name]()))
        write(os.path.join(assets, 'blockstates', name + '.json'), dump_json(blockstate(name)))
        write(os.path.join(assets, 'items', name + '.json'), dump_json(item_definition(name)))
    return images


def preview(assets, images, out_dir):
    """Each block from the front and from above (drawn from its model file), and the textures."""
    tiles = []
    labels = []
    for name in NAMES:
        m = island_blockview.load_model(assets, name)
        for yaw, elev in ((200.0, 28.0), (160.0, 62.0)):
            im = island_blockview.render_model(m, assets, yaw=yaw, elev=elev, scale=11.0, size=(200, 200), center=(8, 5, 8))
            tiles.append(Image.fromarray(im).convert('RGBA'))
            labels.append(name)
    blocks = sheet(tiles, scale=1, cols=4, labels=labels)
    texs = sheet(list(images.values()), scale=5, cols=8, labels=list(images.keys()))
    out = Image.new('RGBA', (max(blocks.width, texs.width), blocks.height + texs.height), (40, 38, 46, 255))
    out.alpha_composite(blocks, (0, 0))
    out.alpha_composite(texs, (0, blocks.height))
    os.makedirs(out_dir, exist_ok=True)
    out.save(os.path.join(out_dir, 'wares_art.png'))


def main(argv):
    root = os.path.normpath(os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark'))
    assets = argv[0] if argv and argv[0] != '-' else root
    images = build(assets)
    if len(argv) > 1:
        preview(assets, images, argv[1])


if __name__ == '__main__':
    main(sys.argv[1:])
