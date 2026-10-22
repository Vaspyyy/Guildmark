"""The five meal effects' icons, 36 x 36 (cook_art.py), the size of the mod's `vigil.png`: the game draws them at 18 px, so each is one bold
shape that reads in a few pixels, in its own colour, and none of them is a heart or a drumstick.

    warmth       orange   a campfire: a flame over two crossed logs
    hearty       red      a plump cross, the sign of more health
    steady       blue     an anchor
    provisioned  brown    a satchel with a loaf in its mouth
    chilled      pale ice a snowflake

The campfire's flames flicker over six frames; each of the others rests and now and then a band of light crosses it with a sparkle. All are
strips with an .mcmeta (the mob-effect atlas animates them like any other sprite). The cold vignette is `chill()`: one still 256 x 256 image.
"""
import json
import math
import os

import numpy as np
from PIL import Image

from hdkit import PAL, Canvas, grid, fbm, blur, bands, light, poly, path_dist, dist_in, outline
from cook_overlays import sweep, mcmeta

S = 36

EMBER = [(60, 16, 10), (130, 40, 14), (196, 84, 24), (236, 140, 46), (252, 196, 100), (255, 238, 186)]
LOG = [(24, 14, 10), (52, 32, 22), (88, 56, 34), (124, 82, 50), (160, 112, 70), (196, 148, 98)]
LOGEND = [(70, 44, 26), (130, 90, 52), (180, 134, 84), (214, 176, 118), (238, 212, 156)]
RED = [(46, 6, 14), (96, 14, 26), (158, 26, 36), (208, 46, 46), (238, 86, 72), (255, 150, 126)]
BLUE = [(12, 20, 48), (26, 46, 100), (44, 82, 160), (76, 124, 208), (128, 172, 238), (204, 226, 255)]
LEATHER = [(28, 16, 10), (64, 38, 22), (102, 64, 36), (140, 92, 52), (176, 126, 76), (210, 164, 108)]
BREAD = [(70, 36, 14), (126, 70, 26), (176, 112, 44), (214, 152, 70), (240, 190, 110)]
BRASS = PAL['gold']
ICE = [(36, 62, 104), (84, 126, 172), (136, 180, 216), (188, 220, 242), (226, 242, 252), (252, 255, 255)]


def _canvas():
    return Canvas(S)


def _put(cv, mask, pal, t, dither=0.0):
    cv.put(mask, bands(pal, np.where(mask, t, 0), dither))
    cv.a[mask] = 1.0


def _lit(mask, h, base, k=1.0):
    """The tone of a shape lit from the upper left over the height field h."""
    return base + k * light(np.where(mask, h, 0.0), 1.0)


def _rod(pts, r):
    """Mask and dome height of a round rod of radius r along a polyline."""
    d, along = path_dist(pts, S)
    return d <= r, np.sqrt(np.clip(1 - (d / r) ** 2, 0, 1)) * r


def _union(shapes):
    """Mask and height of several (mask, height) shapes drawn together: the highest wins."""
    m = np.zeros((S, S), bool)
    h = np.zeros((S, S))
    for sm, sh in shapes:
        m |= sm
        h = np.maximum(h, np.where(sm, sh, 0.0))
    return m, h


def _bevel(mask, width=3.0, power=0.8):
    """A height field that rises from the shape's edge over [width] px: a soft, pillowed bevel."""
    return np.minimum(dist_in(mask, int(width) + 2), width) ** power


# ---------------------------------------------------------------- warmth

def flame(cx, base_y, h, w, sway, n=S):
    """A flame's mask and its position down the axis: a round belly, a tip that curls to the right."""
    xs, ys = grid(n)
    t = np.clip((base_y - ys) / h, -1, 2)
    belly = 0.3
    half = np.where(t < belly, w * np.sqrt(np.clip(1 - ((t - belly) / belly) ** 2, 0, 1)), w * np.clip(1 - (t - belly) / (1 - belly), 0, 1) ** 1.2)
    xc = cx + sway * np.clip(t, 0, 1) ** 2
    m = (t >= 0) & (t <= 1) & (np.abs(xs - xc) <= half)
    axis = np.clip(1 - np.abs(xs - xc) / np.maximum(half, 1e-6), 0, 1)
    return m, axis, np.clip(t, 0, 1)


# How the campfire's flames move over the loop: per frame, the big flame's change in height and in lean, then the two small flames' changes in height.
FLICKER = ((0.0, 0.0, 0.0, 0.0), (1.6, -1.2, -1.2, 1.0), (-1.2, 1.0, 1.2, -0.8), (2.2, -0.4, -0.6, 1.6), (-0.6, 1.6, 1.0, -1.2), (0.8, -1.6, -1.4, 0.4))
FLICKER_TICKS = (3, 2, 3, 2, 3, 2)


def warmth(frame=0):
    dh, ds, dl, dr = FLICKER[frame]
    cv = _canvas()
    # the logs: two rods crossed, their ends cut
    logs = (((5.0, 32.0), (31.0, 25.0)), ((5.0, 25.0), (31.0, 32.0)))
    for (a, b) in logs:
        m, h = _rod([a, b], 2.9)
        _put(cv, m, LOG, _lit(m, h, 0.52, 1.5), 0.0)
    # the cut ends: a pale disc with a ring in it
    xs, ys = grid(S)
    for (a, b) in logs:
        for (ex, ey) in (a, b):
            dd = np.hypot((xs - ex) / 1.9, (ys - ey) / 2.9)
            disc = dd <= 1.0
            _put(cv, disc, LOGEND, 0.55 + 0.25 * np.where(disc, 1 - dd, 0) - 0.4 * (dd > 0.55) * (dd < 0.75), 0.0)
    # the flame: three flames, one inside the next, red to pale yellow
    m1, ax1, t1 = flame(17.0, 26.0, 24.0 + dh, 8.0, 5.0 + ds)
    _put(cv, m1, EMBER, 0.34 + 0.34 * ax1 + 0.12 * (1 - t1), 0.0)
    m2, ax2, t2 = flame(17.6, 26.0, 16.0 + 0.7 * dh, 5.2, 3.0 + 0.6 * ds)
    _put(cv, m2, EMBER, 0.62 + 0.26 * ax2 + 0.1 * (1 - t2), 0.0)
    m3, ax3, t3 = flame(18.2, 26.0, 8.6 + 0.4 * dh, 2.8, 1.0 + 0.3 * ds)
    _put(cv, m3, EMBER, 0.9 + 0.1 * ax3, 0.0)
    # a small flame beside the big one, leaning the other way
    m4, ax4, t4 = flame(8.8, 26.0, 11.0 + dl, 3.4, -2.4 - 0.4 * ds)
    _put(cv, m4, EMBER, 0.32 + 0.4 * ax4, 0.0)
    m5, ax5, t5 = flame(27.6, 26.0, 9.0 + dr, 3.0, 2.4 + 0.4 * ds)
    _put(cv, m5, EMBER, 0.32 + 0.4 * ax5, 0.0)
    outline(cv, 0.84)
    return cv.image()


# ---------------------------------------------------------------- hearty

def hearty():
    cv = _canvas()
    xs, ys = grid(S)
    # two plump arms with rounded ends, crossing in a square middle
    hm, _ = _rod([(8.4, 18.0), (27.6, 18.0)], 5.4)
    vm, _ = _rod([(18.0, 8.4), (18.0, 27.6)], 5.4)
    plus = hm | vm
    h = _bevel(plus, 3.4, 0.85)
    _put(cv, plus, RED, _lit(plus, h, 0.58, 1.7) + 0.02, 0.0)
    # a glint along the upper left of each arm
    glint = plus & (h > 1.0) & (h < 2.2) & (((xs - 18) + (ys - 18)) < -4)
    cv.over(glint, np.array(RED[5], float), 0.45)
    cv.px(8, 15, (255, 224, 204), 1.0)
    cv.px(15, 8, (255, 224, 204), 1.0)
    outline(cv, 0.84)
    return cv.image()


# ---------------------------------------------------------------- steady

def steady():
    cv = _canvas()
    xs, ys = grid(S)
    shapes = []
    # the ring at the top
    d = np.hypot(xs - 18, ys - 6.6)
    ring = (d <= 4.4) & (d >= 2.0)
    shapes.append((ring, 1.6 * np.sqrt(np.clip(1 - ((d - 3.2) / 1.2) ** 2, 0, 1)) + 0.4))
    # the shaft and the stock
    shapes.append(_rod([(18, 10.0), (18, 29.6)], 2.1))
    shapes.append(_rod([(10.5, 13.2), (25.5, 13.2)], 1.7))
    # the arms: the lower half of a circle, ending in flukes
    cx, cy, R = 18.0, 19.0, 11.0
    arc = [(cx + R * math.cos(math.radians(a)), cy + R * math.sin(math.radians(a))) for a in range(14, 167, 8)]
    shapes.append(_rod(arc, 2.0))
    m, h = _union(shapes)
    # flukes: a small triangle at each end of the arc, pointing up and out
    for sgn in (-1, 1):
        ex, ey = cx + sgn * R * math.cos(math.radians(14)), cy + R * math.sin(math.radians(14))
        tri = poly([(ex + sgn * 3.8, ey - 5.4), (ex + sgn * 0.2, ey - 1.0), (ex - sgn * 4.2, ey - 1.2)], S)
        m |= tri
        h = np.maximum(h, np.where(tri, 1.6, 0))
    _put(cv, m, BLUE, _lit(m, h, 0.6, 1.5), 0.0)
    outline(cv, 0.84)
    return cv.image()


# ---------------------------------------------------------------- provisioned

def provisioned():
    cv = _canvas()
    xs, ys = grid(S)
    # the loaf, behind the flap, tilted: golden, three scores
    ang = math.radians(-28)
    ca, sa = math.cos(ang), math.sin(ang)
    u = (xs - 24.0) * ca + (ys - 9.5) * sa
    v = -(xs - 24.0) * sa + (ys - 9.5) * ca
    loaf = (u / 8.0) ** 2 + (v / 4.1) ** 2 <= 1.0
    hh = np.sqrt(np.clip(1 - (u / 8.0) ** 2 - (v / 4.1) ** 2, 0, 1)) * 3.0
    _put(cv, loaf, BREAD, _lit(loaf, hh, 0.58, 1.5) + 0.04, 0.0)
    for k in (-3.6, 0.0, 3.6):
        sc = loaf & (np.abs(u - k) < 0.6) & (np.abs(v) < 2.6)
        cv.over(sc, np.array(BREAD[0], float), 0.7)
    # the satchel's body: a rounded block of leather
    body = (xs >= 5.5) & (xs <= 30.5) & (ys >= 14.0) & (ys <= 32.5)
    rr = 3.6
    cx_lo, cx_hi, cy_hi = 5.5 + rr, 30.5 - rr, 32.5 - rr
    corner = (ys > cy_hi) & (((xs < cx_lo) & (np.hypot(xs - cx_lo, ys - cy_hi) > rr)) | ((xs > cx_hi) & (np.hypot(xs - cx_hi, ys - cy_hi) > rr)))
    body &= ~corner
    hb = _bevel(body, 3.0, 0.8)
    _put(cv, body, LEATHER, _lit(body, hb, 0.52, 1.5), 0.0)
    # the flap: a deep flap with a rounded point
    flap = poly([(5.5, 9.0), (30.5, 9.0), (30.5, 20.0), (24.0, 23.6), (18.0, 25.2), (12.0, 23.6), (5.5, 20.0)], S)
    hf = _bevel(flap, 3.0, 0.8)
    _put(cv, flap, LEATHER, _lit(flap, hf, 0.64, 1.5), 0.0)
    # a seam of stitches inside the flap's edge
    inner = flap & (dist_in(flap, 4) == 3)
    stitch = inner & (((xs + ys) % 3) < 1.2)
    cv.over(stitch, np.array(LEATHER[5], float), 0.7)
    # the strap and the buckle
    strap = (xs >= 16.6) & (xs <= 19.4) & (ys >= 18.0) & (ys <= 23.0)
    _put(cv, strap, LEATHER, 0.34 + 0.2 * ((xs - 16.6) < 1.0), 0.0)
    buckle = (xs >= 14.6) & (xs <= 21.4) & (ys >= 20.4) & (ys <= 26.4)
    hk = _bevel(buckle, 2.0, 0.9)
    _put(cv, buckle, BRASS, _lit(buckle, hk, 0.62, 1.6), 0.0)
    hole = (xs >= 16.6) & (xs <= 19.4) & (ys >= 22.2) & (ys <= 24.6)
    cv.put(hole, np.array(BRASS[0], float))
    outline(cv, 0.84)
    return cv.image()


# ---------------------------------------------------------------- chilled

def chilled():
    cv = _canvas()
    xs, ys = grid(S)
    cx, cy = 18.0, 18.0
    shapes = []
    for k in range(6):
        a = math.radians(90 + 60 * k)
        ex, ey = cx + 15.0 * math.cos(a), cy - 15.0 * math.sin(a)
        shapes.append(_rod([(cx, cy), (ex, ey)], 1.9))
        for frac, ln in ((0.52, 5.6), (0.78, 3.8)):
            bx, by = cx + 15.0 * frac * math.cos(a), cy - 15.0 * frac * math.sin(a)
            for sgn in (-1, 1):
                b = a + sgn * math.radians(58)
                shapes.append(_rod([(bx, by), (bx + ln * math.cos(b), by - ln * math.sin(b))], 1.35))
    m, h = _union(shapes)
    hexa = np.hypot(xs - cx, ys - cy) <= 4.2
    m |= hexa
    h = np.maximum(h, np.where(hexa, 2.4 * np.sqrt(np.clip(1 - (np.hypot(xs - cx, ys - cy) / 4.2) ** 2, 0, 1)) + 0.4, 0))
    _put(cv, m, ICE, _lit(m, h, 0.7, 1.4), 0.0)
    for (gx, gy) in ((17, 5), (30, 12), (6, 26)):
        cv.px(gx, gy, (255, 255, 255), 1.0)
    outline(cv, 0.84)
    return cv.image()


# ---------------------------------------------------------------- the cold vignette

def _smooth(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


def _frost_line(arr, x0, y0, x1, y1, val, thick):
    """Lay a line of frost on [arr] (max of what is there): [thick] 1 is a hairline, 2 a hairline with a soft side."""
    n = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    h, w = arr.shape
    for i in range(n + 1):
        t = i / n
        x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        ix, iy = int(round(x)), int(round(y))
        if 0 <= ix < w and 0 <= iy < h:
            arr[iy, ix] = max(arr[iy, ix], val)
            if thick > 1:
                for (ox, oy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    jx, jy = ix + ox, iy + oy
                    if 0 <= jx < w and 0 <= jy < h:
                        arr[jy, jx] = max(arr[jy, jx], val * 0.55)


def _feather(arr, rng, x, y, ang, length, depth):
    """A frost fern: a stem that drifts a little as it grows, with a branch to one side or both every few pixels, each branch a smaller fern."""
    segs = max(2, int(length // 3))
    cx, cy, a = x, y, ang
    run = 0.0
    step = 8.0 if depth == 0 else 5.5
    nxt = step * rng.uniform(0.6, 1.1)
    for _ in range(segs):
        a += rng.normal(0.0, 0.11)
        nx, ny = cx + 3.0 * math.cos(a), cy + 3.0 * math.sin(a)
        _frost_line(arr, cx, cy, nx, ny, 1.0 - 0.14 * depth, 2 if depth == 0 else 1)
        run += 3.0
        if run >= nxt and depth < 3 and length - run > 6:
            for sgn in (-1, 1):
                if rng.random() < 0.78:
                    a2 = a + sgn * math.radians(rng.uniform(50, 72))
                    _feather(arr, rng, nx, ny, a2, (length - run) * rng.uniform(0.36, 0.58), depth + 1)
            nxt += step * rng.uniform(0.8, 1.2)
        cx, cy = nx, ny


def chill():
    """The cold vignette, 256 x 256, transparent in the middle: frost thickening to the screen's edges, ferns of ice reaching in from them, a
    few glints. The game stretches it over the screen and may pulse its strength."""
    n = 256
    xs, ys = grid(n)
    dx, dy = (xs - 128.0) / 128.0, (ys - 128.0) / 128.0
    rad = (np.abs(dx) ** 3 + np.abs(dy) ** 3) ** (1.0 / 3.0)
    cloud = fbm(n, n, 46, 5, 5)
    grain = fbm(n, n, 5, 6, 3)
    edge_t = rad + 0.34 * (cloud - 0.5)
    base = _smooth((edge_t - 0.52) / 0.62) ** 1.15 * 0.9
    base *= _smooth((rad - 0.42) / 0.2)
    # ferns of frost growing inward from the border
    rng = np.random.default_rng(23)
    ice = np.zeros((n, n))
    seeds = []
    for k in range(40):                                   # along the four edges
        side = k % 4
        t = rng.uniform(4, n - 4)
        seeds.append({0: (t, 0.0, math.pi / 2), 1: (t, n - 1.0, -math.pi / 2), 2: (0.0, t, 0.0), 3: (n - 1.0, t, math.pi)}[side])
    for (cx, cy) in ((0.0, 0.0), (n - 1.0, 0.0), (0.0, n - 1.0), (n - 1.0, n - 1.0)):          # a few more in each corner
        for j in range(4):
            to_mid = math.atan2(128.0 - cy, 128.0 - cx)
            seeds.append((cx, cy, to_mid + rng.uniform(-0.9, 0.9)))
    for (x, y, ang) in seeds:
        _feather(ice, rng, x, y, ang + rng.uniform(-0.3, 0.3), rng.uniform(26, 62), 0)
    # the ice and the frost, coloured: deep blue where it thins, pale at the rim, white on the ferns
    t = np.clip((edge_t - 0.5) / 0.7, 0, 1)
    deep = np.array([78.0, 128.0, 190.0])
    pale = np.array([226.0, 243.0, 255.0])
    white = np.array([248.0, 253.0, 255.0])
    rgb = deep + (pale - deep) * t[..., None]
    rgb = rgb + (np.array([40.0, 36.0, 28.0]) * (grain - 0.5)[..., None])
    halo = blur(ice, 2)
    rgb = rgb + (white - rgb) * (np.clip(ice * 1.2 + halo * 1.4, 0, 1) * 0.8)[..., None]
    alpha = np.maximum(base * (0.78 + 0.22 * grain), np.clip(ice * 0.9 + halo * 0.9, 0, 1) * 0.9 * _smooth((rad - 0.35) / 0.3))
    # glints in the thick of it
    rng2 = np.random.default_rng(99)
    for _ in range(46):
        gx, gy = int(rng2.uniform(2, n - 2)), int(rng2.uniform(2, n - 2))
        if alpha[gy, gx] > 0.55:
            rgb[gy, gx] = (255, 255, 255)
            alpha[gy, gx] = 1.0
            for (ox, oy) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                rgb[gy + oy, gx + ox] = rgb[gy + oy, gx + ox] + (white - rgb[gy + oy, gx + ox]) * 0.6
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = np.clip(rgb + 0.5, 0, 255).astype(np.uint8)
    out[..., 3] = np.clip(alpha * 255 + 0.5, 0, 255).astype(np.uint8)
    return Image.fromarray(out, 'RGBA')


PAINTERS = {'warmth': warmth, 'hearty': hearty, 'steady': steady, 'provisioned': provisioned, 'chilled': chilled}

# The other four icons are still, and now and then a band of light crosses them with a sparkle where the band meets it: (the sparkle's
# place on the sprite, the rest between two passes in ticks). The rests differ so four icons side by side never flash together.
GLINTS = {'hearty': ((15, 15), 80), 'steady': ((13, 13), 110), 'provisioned': ((15, 21), 95), 'chilled': ((16, 16), 70)}


def frames(name):
    """An icon as a list of frames (one for none moving): the campfire's flicker, or a still icon followed by its glint's pass."""
    if name == 'warmth':
        return [warmth(f) for f in range(len(FLICKER))]
    return sweep(PAINTERS[name](), -2.0, 44.0, 9, 2.4, star=GLINTS[name][0])


def times(name):
    """How long each frame of [frames] lasts, in ticks."""
    if name == 'warmth':
        return list(FLICKER_TICKS)
    return [GLINTS[name][1]] + [2] * 8


def write(assets):
    """The icons into textures/mob_effect/ (a strip and an .mcmeta where one moves) and the vignette into textures/gui/."""
    d = os.path.join(assets, 'textures/mob_effect')
    os.makedirs(d, exist_ok=True)
    for name in PAINTERS:
        fr = frames(name)
        strip = Image.new('RGBA', (S, S * len(fr)), (0, 0, 0, 0))
        for i, f in enumerate(fr):
            strip.paste(f, (0, S * i))
        strip.save(os.path.join(d, name + '.png'))
        with open(os.path.join(d, name + '.png.mcmeta'), 'w') as f:
            json.dump(mcmeta(times(name)), f, indent=2)
            f.write('\n')
    g = os.path.join(assets, 'textures/gui')
    os.makedirs(g, exist_ok=True)
    chill().save(os.path.join(g, 'chill.png'))
