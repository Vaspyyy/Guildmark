"""The Hearthcraft items' 32 x 32 textures (cook_art.py): the twelve dishes, the gruel and the scorched mess, the four tools, the Cook's Book.
(The herb sprig is `cook_herb.py`'s.) Each is painted the way the mod's other items are: shapes as height fields, `light` from the upper
left, a few hard tones with a dither where they meet, the shape's own edge darkened and a dark outline round it all.
"""

import numpy as np

from hdkit import PAL, Canvas, grid, fbm, bands, light, poly, ellipse, path_dist, rim, outline

N = 32

# ---------------------------------------------------------------- tones (darkest first)
WOOD = PAL['wood']
WOODL = [(30, 20, 16), (58, 40, 28), (92, 66, 44), (128, 96, 62), (166, 130, 86), (204, 168, 116)]               # a pale carved bowl
CEDAR = [(40, 18, 14), (82, 38, 26), (124, 62, 40), (160, 90, 56), (192, 122, 78), (220, 158, 108)]             # a plank of cedar
STONEWARE = [(48, 50, 58), (92, 94, 102), (140, 142, 144), (184, 184, 178), (214, 212, 202), (238, 234, 222)]  # a cream glaze
GREYWARE = [(30, 32, 38), (58, 60, 68), (88, 90, 96), (120, 122, 126), (152, 154, 156), (184, 184, 184)]       # the gruel's plain grey bowl
BLUEGLAZE = [(14, 22, 40), (28, 46, 80), (46, 74, 118), (74, 108, 152), (112, 148, 186), (164, 196, 222)]      # the chowder's bowl
POTTAGE = [(30, 34, 16), (52, 60, 24), (82, 92, 36), (116, 124, 52), (152, 156, 74), (188, 186, 104)]
STEW = [(40, 20, 10), (74, 38, 18), (108, 58, 26), (146, 84, 36), (182, 112, 50), (214, 146, 74)]
BROTH = [(96, 70, 34), (146, 112, 54), (190, 152, 80), (220, 188, 110), (240, 216, 144), (252, 238, 188)]
CHOWDER = [(120, 100, 76), (170, 148, 114), (206, 186, 150), (230, 214, 178), (244, 232, 200), (252, 246, 224)]
GRUEL = [(60, 62, 62), (90, 92, 90), (120, 122, 118), (148, 150, 144), (176, 178, 170), (202, 204, 196)]
BEEF = [(38, 16, 10), (70, 30, 18), (104, 48, 28), (140, 70, 40), (172, 98, 58), (200, 128, 80)]
CARROT = [(110, 36, 12), (170, 66, 18), (220, 104, 28), (244, 142, 50), (255, 184, 92), (255, 220, 150)]
POTATO = [(104, 80, 40), (150, 120, 60), (190, 158, 86), (218, 188, 116), (238, 214, 148), (252, 238, 184)]
LEAFG = [(18, 34, 20), (34, 64, 34), (58, 100, 48), (90, 136, 66), (130, 172, 92), (176, 208, 132)]
BONE = PAL['bone']
CRUST = [(46, 24, 12), (86, 46, 20), (134, 78, 30), (176, 112, 44), (210, 148, 68), (234, 184, 106)]            # golden-brown pastry
DOUGH = [(40, 26, 18), (74, 48, 30), (110, 74, 42), (146, 104, 58), (182, 140, 82), (212, 176, 114)]           # a dark trail loaf
HONEY = [(110, 56, 10), (170, 96, 18), (214, 140, 30), (240, 178, 56), (252, 214, 104), (255, 240, 170)]
APPLE = [(150, 120, 70), (196, 168, 98), (224, 200, 130), (240, 222, 160), (250, 238, 196)]
APPLE_SKIN = [(70, 14, 16), (120, 28, 28), (168, 50, 40), (206, 90, 60)]
SALMON = [(120, 44, 36), (170, 70, 46), (212, 100, 62), (238, 134, 86), (250, 170, 120), (255, 208, 168)]
FAT = [(210, 150, 120), (232, 184, 150), (246, 214, 184), (255, 238, 216)]
ROAST = [(40, 18, 12), (80, 38, 22), (122, 62, 34), (162, 92, 48), (196, 124, 68), (222, 158, 98)]
PINK = [(130, 54, 52), (176, 84, 78), (208, 116, 104), (230, 150, 134), (244, 184, 166)]
SMOKED = [(30, 14, 12), (60, 26, 20), (92, 42, 30), (126, 62, 42), (158, 86, 58), (188, 116, 82)]
STRING = [(52, 40, 26), (92, 72, 46), (128, 104, 68), (160, 134, 92), (190, 164, 118)]
CHAR = [(8, 7, 8), (17, 15, 16), (30, 27, 27), (46, 41, 40), (66, 60, 56), (92, 85, 78)]
IRON = PAL['iron']
STEEL = PAL['steel']
GOLD = PAL['gold']
LEATHER = PAL['leather']
EMBER = PAL['ember']

LS = np.array([-0.58, -0.64])                            # toward the light in image coordinates


def ell(cx, cy, rx, ry, w=N):
    """Mask and dome height of an ellipse."""
    return ellipse(cx, cy, rx, ry, w)


def paint(cv, mask, pal, h, base=0.55, k=1.0, noise=None, dither=0.4, amp=0.1, strength=1.0):
    """Fill [mask] with [pal] lit from the height field [h]: the tone is base plus the light on h, plus a little [noise]."""
    L = light(np.where(mask, h, 0.0), strength)
    t = base + k * L
    if noise is not None:
        t = t + amp * (noise - 0.5)
    cv.put(mask, bands(pal, np.where(mask, t, 0), dither))
    cv.a[mask] = 1.0


def put(cv, x, y, col, a=1.0):
    cv.px(int(x), int(y), tuple(float(c) for c in col), a)


def stroke(cv, pts, w, pal, base=0.55, k=0.9, noise=None, dither=0.35, round_end=True):
    """A thick polyline lit like a rod (the light from the upper left): its mask is returned."""
    d, along = path_dist(pts, N)
    m = d <= w / 2
    h = np.sqrt(np.clip(1 - (d / (w / 2)) ** 2, 0, 1)) * (w / 2)
    paint(cv, m, pal, h, base, k, noise, dither)
    return m


# ================================================================ bowls

def bowl(cv, cx, cy, rx, ry, depth, wall, rim_pal, fill, seed, foot=0.6, band=None, grain=True):
    """A bowl seen from above and in front: its rim an ellipse at (cx, cy), the wall below it, a foot, and the contents painted by [fill]
    (a function taking the canvas, the inner mask and its dome). Returns the inner mask."""
    xs, ys = grid(N)
    # The body below the rim: the lower part of a deeper ellipse, narrowing to the foot
    body_m, body_h = ell(cx, cy, rx, ry + depth)
    below = ys >= cy - 0.5
    outer_top, _ = ell(cx, cy, rx, ry)
    body = (body_m & below) | outer_top
    # The foot
    fm, fh = ell(cx, cy + ry + depth - 0.6, rx * foot, 1.7)
    foot_only = fm & ~body
    paint(cv, foot_only, wall, fh * 2.0, 0.3, 0.8, dither=0.3)
    # The wall: lit round, banded
    dy = (ys - cy) / max(ry + depth, 1e-6)
    dx = (xs - cx) / rx
    h = np.sqrt(np.clip(1 - dx * dx - 0.0 * dy * dy, 0, 1)) * (0.6 + 0.4 * np.clip(dy, 0, 1)) * 5
    gr = fbm(N, N, 4, seed) if grain else None
    # The grain follows the curve of the bowl: bands of darker lines
    curve = ry * (1 - np.sqrt(np.clip(1 - dx * dx, 0, 1)))
    lines = np.sin((ys - curve) * 1.15 + fbm(N, N, 6, seed + 1) * 3.0)
    paint(cv, body & ~outer_top, wall, h, 0.5 - 0.1 * np.clip(dy, 0, 1), 1.1, gr, 0.35, 0.14)
    if grain:
        ln = body & ~outer_top & (lines > 0.82)
        cv.over(ln, np.array(wall[1], float), 0.5)
    if band is not None:                                  # a painted band round the wall
        bm = body & ~outer_top & (np.abs(ys - cy - ry - band[0] + curve) < band[1] / 2)
        cv.put(bm, bands(band[2], np.where(bm, 0.45 + 0.5 * light(np.where(bm, h, 0), 1.0), 0), 0.3))
    # The rim: a ring, rounded, lighter
    inner_m, inner_h = ell(cx, cy + 0.2, rx - 1.7, ry - 1.1)
    ring = outer_top & ~inner_m
    rh = np.clip(1 - np.abs(np.hypot((xs - cx) / rx, (ys - cy) / ry) - 0.9) / 0.14, 0, 1) * 1.5 + 1.0
    paint(cv, ring, rim_pal, rh, 0.62, 1.0, gr, 0.3, 0.1)
    # The inside: the back wall in shadow, then the contents
    back = inner_m
    paint(cv, back, wall, np.full((N, N), 1.0), 0.2, 0.0, dither=0.2)
    fill_m, fill_h = ell(cx, cy + 0.9, rx - 2.0, ry - 1.5)
    fill(cv, fill_m, fill_h, seed)
    return fill_m


def fill_surface(pal, base=0.55, sparkle=0.0, seed=0, flecks=None):
    """Contents that are a flat surface of one stuff: [pal] lit by a gentle swell, mottled, and a slick of gloss on the upper left."""
    def go(cv, m, hh, s):
        n = fbm(N, N, 3, s + seed)
        n2 = fbm(N, N, 2, s + seed + 5)
        swell = hh * 1.6 + 0.5 * (n2 - 0.5)
        paint(cv, m, pal, swell, base, 1.3, n, 0.45, 0.28)
        if flecks:
            rng = np.random.default_rng(s + seed + 9)
            for (col, count, big) in flecks:
                for _ in range(count):
                    px, py = int(rng.uniform(0, N)), int(rng.uniform(0, N))
                    if 0 <= px < N and 0 <= py < N and m[py, px] and hh[py, px] > 0.25:
                        put(cv, px, py, col)
                        if big and rng.random() < 0.6 and m[py, min(px + 1, N - 1)]:
                            put(cv, px + 1, py, col)
        if sparkle:
            ys_, xs_ = np.nonzero(m & (hh > 0.45))
            if len(xs_):
                pick = np.argmin(xs_ * 1.0 + ys_ * 1.0)
                put(cv, xs_[pick] + 1, ys_[pick], (255, 248, 226))
    return go


def spoon_in(cv, x0, y0, x1, y1):
    """A wooden spoon standing in a bowl: its handle from (x0, y0) at the bowl up to (x1, y1)."""
    stroke(cv, [(x0, y0), (x1, y1)], 1.9, WOODL, 0.55, 0.9)
    m, h = ell(x0 - 0.4, y0 + 0.2, 2.0, 1.3)
    paint(cv, m, WOODL, h * 2.0, 0.5, 0.9)


def pottage():
    cv = Canvas(N)
    fill = fill_surface(POTTAGE, 0.5, sparkle=1, seed=1, flecks=[((60, 84, 38), 14, False), ((196, 190, 120), 8, False), ((120, 74, 40), 6, True)])
    bowl(cv, 15.5, 17.5, 13.0, 6.6, 6.0, WOODL, WOODL, fill, 3)
    spoon_in(cv, 19.5, 14.5, 26.5, 3.0)
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv.image()


def stew():
    """Beef chunks, carrot discs and a leaf in a brown stew, in a dark banded bowl."""
    cv = Canvas(N)

    def fill(cv_, m, hh, s):
        n = fbm(N, N, 3, s + 21)
        paint(cv_, m, STEW, hh * 1.4 + 0.3 * (n - 0.5), 0.5, 1.2, n, 0.45, 0.3)
        # beef: dark rounded chunks lit from the upper left
        for (px, py, r) in ((11.5, 17.0, 2.5), (17.5, 15.6, 2.8), (21.8, 18.2, 2.2), (14.2, 19.8, 2.0)):
            cm, ch = ell(px, py, r * 1.2, r)
            cm &= m
            paint(cv_, cm, BEEF, ch * 3.0, 0.52, 1.1, fbm(N, N, 2, s + int(px)), 0.4, 0.3)
        # carrot discs: orange ellipses with a pale rim
        for (px, py) in ((9.0, 15.4), (20.0, 14.8), (16.0, 19.6)):
            cm, ch = ell(px, py, 2.5, 1.6)
            cm &= m
            paint(cv_, cm, CARROT, ch * 2.0, 0.55, 1.0, None, 0.3)
            ci, _ = ell(px, py, 1.1, 0.7)
            cv_.over(ci & m, np.array(CARROT[4], float), 0.5)
        # a bay leaf
        lm = poly([(12.0, 14.2), (15.4, 12.8), (17.0, 13.4), (15.0, 15.2)], N) & m
        paint(cv_, lm, LEAFG, np.full((N, N), 1.0), 0.6, 0.0, dither=0.2)
        for (px, py) in ((14.0, 14.4), (15.0, 14.0)):
            if m[int(py), int(px)]:
                put(cv_, px, py, LEAFG[1])
    bowl(cv, 15.5, 17.5, 13.0, 6.6, 6.0, WOOD, WOODL, fill, 5, band=(3.4, 1.7, [(24, 18, 16), (50, 40, 36), (84, 74, 66)]))
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv.image()


def broth():
    """A cream stoneware bowl of clear broth with a bone across it."""
    cv = Canvas(N)

    def fill(cv_, m, hh, s):
        n = fbm(N, N, 3, s + 31)
        paint(cv_, m, BROTH, hh * 1.3 + 0.4 * (n - 0.5), 0.58, 1.2, n, 0.45, 0.2)
        # fat eyes: a few paler rings floating on it
        for (px, py) in ((10.5, 17.6), (21.5, 18.4), (16.5, 20.0)):
            cm, _ = ell(px, py, 1.4, 0.8)
            cv_.over(cm & m, np.array(BROTH[5], float), 0.7)
    bowl(cv, 15.5, 17.5, 13.0, 6.6, 6.0, STONEWARE, STONEWARE, fill, 7, band=(2.0, 1.4, [(28, 46, 80), (46, 74, 118), (74, 108, 152)]), grain=False)
    # The bone across the bowl: a shaft with a knuckle at each end, lying a little tilted, standing over the rim
    p0, p1 = (3.0, 13.2), (28.4, 11.0)
    stroke(cv, [p0, p1], 2.4, BONE, 0.58, 1.0, fbm(N, N, 2, 41), 0.35)
    for (px, py) in (p0, p1):
        for dy in (-1.1, 1.1):
            m, h = ell(px + (0.4 if px < 10 else -0.4), py + dy, 1.8, 1.7)
            paint(cv, m, BONE, h * 2.4, 0.6, 1.0, None, 0.3)
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv.image()


def chowder():
    """A blue-glazed bowl of cream chowder with flaked fish and green flecks."""
    cv = Canvas(N)

    def fill(cv_, m, hh, s):
        n = fbm(N, N, 3, s + 41)
        paint(cv_, m, CHOWDER, hh * 1.3 + 0.4 * (n - 0.5), 0.6, 1.2, n, 0.45, 0.22)
        rng = np.random.default_rng(s + 3)
        # flaked fish: pale pink-white flakes, each two or three texels, with a darker lower edge
        for _ in range(9):
            px, py = rng.uniform(7, 24), rng.uniform(15, 21)
            if m[int(py), int(px)]:
                fm = poly([(px - 1.8, py), (px - 0.4, py - 1.4), (px + 1.8, py - 0.8), (px + 1.4, py + 1.0), (px - 0.6, py + 1.3)], N) & m
                paint(cv_, fm, PINK, np.full((N, N), 1.0), 0.78, 0.0, dither=0.2)
                put(cv_, px - 0.6, py - 0.6, (255, 240, 232))
                for xx in range(int(px - 1), int(px + 2)):
                    if 0 <= xx < N and fm[int(py + 1), xx]:
                        put(cv_, xx, int(py + 1), PINK[1])
        # parsley flecks
        for _ in range(16):
            px, py = int(rng.uniform(5, 27)), int(rng.uniform(14, 22))
            if m[py, px]:
                put(cv_, px, py, LEAFG[3] if rng.random() < 0.6 else LEAFG[2])
        # corn or potato dice
        for _ in range(5):
            px, py = int(rng.uniform(8, 24)), int(rng.uniform(15, 21))
            if m[py, px]:
                put(cv_, px, py, POTATO[3])
                put(cv_, px + 1, py, POTATO[2])
    bowl(cv, 15.5, 17.5, 13.0, 6.6, 6.0, BLUEGLAZE, BLUEGLAZE, fill, 9, band=(2.6, 1.2, [(110, 130, 150), (164, 188, 206), (214, 228, 238)]), grain=False)
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv.image()


def gruel():
    """A plain grey bowl of grey gruel, lumpy, a single dark crust at the edge."""
    cv = Canvas(N)

    def fill(cv_, m, hh, s):
        n = fbm(N, N, 3, s + 51)
        paint(cv_, m, GRUEL, hh * 1.0 + 0.6 * (fbm(N, N, 2, s + 52) - 0.5), 0.5, 1.4, n, 0.5, 0.3)
        rng = np.random.default_rng(s + 7)
        for _ in range(11):
            px, py = int(rng.uniform(7, 25)), int(rng.uniform(15, 21))
            if m[py, px]:
                put(cv_, px, py, GRUEL[4])
                put(cv_, px, py + 1, GRUEL[1])
    bowl(cv, 15.5, 17.5, 13.0, 6.6, 6.0, GREYWARE, GREYWARE, fill, 11, grain=False)
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ the rest are added below

PAINTERS = {
    'dish_pottage': pottage, 'dish_stew': stew, 'dish_broth': broth, 'dish_chowder': chowder, 'gruel': gruel,
}
