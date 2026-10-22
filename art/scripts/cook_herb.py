"""The herb: its four crop textures (a sprout, a young plant, a full bush, a flowering bush; 32 x 32, see-through between the leaves) and the
sprig you pick (a tied bundle, 32 x 32 with the item outline). A grey-green, sage-like plant: pairs of small oval leaves on upright stems,
purple flower spikes with a few white flowers at the last age.
"""
import math

import numpy as np

from hdkit import Canvas, grid, seg_dist, bands, rand, outline, rim, disc

T = 32
LEAF = [(20, 34, 28), (38, 62, 46), (62, 98, 64), (96, 134, 84), (138, 170, 108), (186, 208, 148)]       # sage green, a little grey in the lights
STEM = [(22, 28, 22), (44, 58, 40), (74, 92, 56), (110, 124, 76)]
LILAC = [(46, 30, 72), (82, 56, 118), (122, 90, 158), (164, 132, 196), (208, 182, 224)]
WHITE = [(160, 160, 176), (206, 204, 218), (238, 236, 244), (255, 254, 255)]
CORD = [(52, 40, 26), (92, 72, 46), (128, 104, 68), (160, 134, 92), (190, 164, 118)]

LIGHT = np.array([-0.55, -0.83])                         # toward the light, in image coordinates


def _leaf(cv, x, y, ang, length, width, seed):
    """One oval leaf from (x, y) in direction [ang] (radians, image coordinates, 0 = right, -pi/2 = up): lit on the side that faces the light,
    a darker midrib, a paler tip."""
    x1, y1 = x + length * math.cos(ang), y + length * math.sin(ang)
    d, t = seg_dist(x, y, x1, y1, T)
    prof = width * np.sin(np.pi * np.clip(t, 0, 1)) ** 0.8
    m = (d <= prof) & (prof > 0.35)
    if not m.any():
        return
    xs, ys = grid(T)
    dx, dy = math.cos(ang), math.sin(ang)
    side = (xs - x) * (-dy) + (ys - y) * dx                # signed offset to the leaf's normal side
    normal = np.array([-dy, dx])
    sgn = 1.0 if normal @ LIGHT > 0 else -1.0
    u = np.clip(side * sgn / np.maximum(prof, 0.4), -1, 1)
    n = rand(T, T, seed)
    tone_v = 0.5 + 0.28 * u + 0.1 * (t - 0.5) + 0.08 * (n - 0.5)
    tone_v = np.where(d < 0.38, tone_v - 0.2, tone_v)       # the midrib
    cv.put(m, bands(LEAF, tone_v, 0.25))
    cv.a[m] = 1.0


def _stem(cv, pts, thick=0.62):
    from hdkit import path_dist
    d, _ = path_dist(pts, T)
    m = d <= thick
    xs, ys = grid(T)
    lit = (xs < np.interp(ys, [p[1] for p in pts][::-1], [p[0] for p in pts][::-1]) )
    cv.put(m, bands(STEM, np.where(lit, 0.62, 0.35), 0.1))
    cv.a[m] = 1.0


def _plant(stems, seed, flowers=False):
    """A plant of [stems]: each (x_base, height, lean, n_pairs). Leaves in pairs up each stem, smaller toward the tip."""
    cv = Canvas(T)
    rng = np.random.default_rng(seed)
    order = sorted(stems, key=lambda s: s[1])              # the tall ones last (in front)
    tips = []
    for (xb, h, lean, pairs) in order:
        yb = T - 0.5
        pts = [(xb + lean * (k / 6.0) ** 1.6 * h * 0.25 + rng.uniform(-0.2, 0.2), yb - h * k / 6.0) for k in range(7)]
        _stem(cv, pts)
        for p in range(pairs * 2):
            f = (p + 0.8) / (pairs * 2 + 0.4)
            i = min(int(f * 6), 5)
            a, b = pts[i], pts[i + 1]
            ff = f * 6 - i
            px, py = a[0] + (b[0] - a[0]) * ff, a[1] + (b[1] - a[1]) * ff
            size = (1.2 - 0.5 * f) * (0.85 + 0.15 * min(h / 22, 1.2))
            side = -1 if p % 2 == 0 else 1
            ang = -math.pi / 2 + side * (0.78 + 0.16 * rng.uniform(-1, 1) - 0.2 * f)
            _leaf(cv, px, py, ang, 6.6 * size, 1.75 * size, seed + 31 * p)
        # The tip: a last small pair, straight up
        tx, ty = pts[-1]
        _leaf(cv, tx, ty + 0.4, -math.pi / 2 - 0.35, 4.0, 1.4, seed + 91)
        _leaf(cv, tx, ty + 0.4, -math.pi / 2 + 0.35, 4.0, 1.4, seed + 92)
        tips.append((tx, ty - 3.0))
    if flowers:
        for k, (tx, ty) in enumerate(tips):
            # A little spike of lilac flowers: five small discs up the last stretch, white ones among them
            for j in range(5):
                fx, fy = tx + 0.3 * math.sin(j * 1.7 + k), ty - 0.2 - j * 1.35
                m, hgt = disc(fx, fy, 1.55 - 0.1 * j, T)
                tv = 0.35 + 0.65 * hgt - 0.1 * j / 4
                cv.put(m, bands(LILAC, np.clip(tv, 0, 1), 0.2))
                cv.a[m] = 1.0
            for fx, fy in ((tx - 2.2, ty + 2.0), (tx + 2.4, ty + 0.6), (tx - 1.6, ty - 4.2)):
                m, hgt = disc(fx, fy, 1.15, T)
                cv.put(m, bands(WHITE, np.clip(0.3 + 0.7 * hgt, 0, 1), 0.15))
                cv.a[m] = 1.0
    return cv


def stage(age):
    """The herb at [age] 0 to 3."""
    if age == 0:
        cv = _plant([(15.8, 8.5, 0.0, 1)], 11)
        cv2 = _plant([(19.0, 5.5, 0.3, 0)], 12)
        # a seedling: two round seed leaves and a bud
        from hdkit import paste
        paste(cv, cv2)
    elif age == 1:
        cv = _plant([(15.5, 15.0, -0.3, 2), (20.0, 10.0, 0.5, 1)], 21)
    elif age == 2:
        cv = _plant([(12.5, 18.0, -0.5, 3), (16.5, 24.0, 0.0, 4), (21.0, 19.0, 0.6, 3), (9.0, 11.0, -0.6, 2), (24.0, 12.0, 0.6, 2)], 31)
    else:
        cv = _plant([(12.0, 19.0, -0.5, 3), (16.5, 26.0, 0.0, 4), (21.2, 21.0, 0.6, 3), (8.5, 12.0, -0.6, 2), (24.5, 13.0, 0.6, 2)], 41, flowers=True)
    rim(cv, 0.28)
    return cv.image()


def sprig():
    """The herb sprig as an item: three stems bound with a cord, tilted up to the right, leaves in alternate pairs, a few lilac flowers at the
    tips, an outline round it all (32 x 32)."""
    from hdkit import path_dist
    cv = Canvas(T)
    base = (8.0, 27.0)
    for k, (ang, L) in enumerate(((-1.42, 20.0), (-0.9, 25.0), (-0.38, 20.0))):
        ex, ey = base[0] + L * math.cos(ang), base[1] + L * math.sin(ang)
        pts = [(base[0] + (ex - base[0]) * i / 6.0, base[1] + (ey - base[1]) * i / 6.0) for i in range(7)]
        _stem(cv, pts, 0.7)
        for p in range(4):
            f = (p + 1.9) / 6.0
            i = min(int(f * 6), 5)
            a, b = pts[i], pts[i + 1]
            ff = f * 6 - i
            px, py = a[0] + (b[0] - a[0]) * ff, a[1] + (b[1] - a[1]) * ff
            side = -1 if p % 2 == 0 else 1
            _leaf(cv, px, py, ang + side * (0.8 - 0.12 * f), 5.4 - 1.4 * f, 1.5 - 0.3 * f, 50 + 13 * k + 5 * p)
        _leaf(cv, ex, ey, ang - 0.22, 4.2, 1.4, 70 + k)
        _leaf(cv, ex, ey, ang + 0.22, 4.2, 1.4, 80 + k)
        # A lilac flower on the tip
        for j in range(3):
            fx, fy = ex + math.cos(ang) * (1.6 + j * 1.5), ey + math.sin(ang) * (1.6 + j * 1.5)
            m, hgt = disc(fx, fy, 1.5 - 0.15 * j, T)
            cv.put(m, bands(LILAC, np.clip(0.3 + 0.7 * hgt, 0, 1), 0.2))
            cv.a[m] = 1.0
    # The cord: two turns round the stems, a knot, two ends
    for yy in (22.4, 24.2):
        d, _ = path_dist([(5.4, yy + 1.2), (8.6, yy - 0.7), (12.0, yy + 0.2)], T)
        m = d <= 0.95
        cv.put(m, bands(CORD, np.where(rand(T, T, 6) > 0.5, 0.66, 0.4), 0.15))
        cv.a[m] = 1.0
    for (x0, y0, x1, y1) in ((8.8, 23.2, 5.6, 27.8), (9.4, 23.4, 12.8, 27.6)):
        d, _ = path_dist([(x0, y0), (x1, y1)], T)
        m = d <= 0.75
        cv.put(m, bands(CORD, np.full((T, T), 0.55), 0.1))
        cv.a[m] = 1.0
    rim(cv, 0.3)
    outline(cv, 0.78)
    return cv.image()


PAINTERS = {'herb_stage%d' % a: (lambda a=a: stage(a)) for a in range(4)}
