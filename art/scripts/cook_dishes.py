"""The solid dishes' 32 x 32 textures (cook_art.py): glazed roots on a plate, honey cakes on a board, a trail loaf, a wedge of raised pie, a wedge
of orchard tart, planked salmon, a carved roast on its bone, smoked strips on a string, and the scorched mess. The bowls and the shared
brushes are `cook_items.py`'s.
"""
import math

import numpy as np

from hdkit import Canvas, grid, fbm, bands, poly, path_dist, edge, dist_in, rim, outline
from cook_items import (N, ell, paint, put, stroke, WOODL, CEDAR, STONEWARE, CARROT, POTATO, LEAFG, BONE, CRUST, DOUGH, HONEY, APPLE_SKIN, SALMON,
                        FAT, ROAST, PINK, SMOKED, STRING, CHAR, EMBER)


TWINE = [(70, 54, 36), (130, 106, 70), (186, 160, 112), (220, 198, 150), (240, 224, 180), (252, 244, 214)]       # the loaf's pale cord


def ell_rot(cx, cy, rx, ry, ang, w=N):
    """Mask and dome of an ellipse turned [ang] degrees (image coordinates, positive turns clockwise on the screen)."""
    xs, ys = grid(w)
    a = math.radians(ang)
    dx, dy = xs - cx, ys - cy
    u = (dx * math.cos(a) + dy * math.sin(a)) / rx
    v = (-dx * math.sin(a) + dy * math.cos(a)) / ry
    d2 = u * u + v * v
    return d2 <= 1, np.sqrt(np.clip(1 - d2, 0, 1))


def shine(cv, m, h, col=(255, 246, 220), thresh=0.55):
    """One bright texel (and a faint one beside it) at the upper left of the lit crown of a shape."""
    ys_, xs_ = np.nonzero(m & (h > thresh))
    if len(xs_):
        i = np.argmin(xs_ * 1.0 + ys_ * 1.2)
        put(cv, xs_[i], ys_[i], col)
        if m[ys_[i], min(xs_[i] + 1, N - 1)]:
            put(cv, xs_[i] + 1, ys_[i], tuple(0.55 * np.array(col) + 0.45 * np.array(cv.rgb[ys_[i], min(xs_[i] + 1, N - 1)])))


# ================================================================ roots

def roots():
    """Glazed carrot coins and potato chunks on a small plate, glossy."""
    cv = Canvas(N)
    cx, cy = 16.0, 17.6
    side, sh = ell(cx, cy + 1.7, 14.4, 8.8)
    paint(cv, side, STONEWARE, sh * 3.0, 0.38, 1.0, dither=0.3)
    top, th = ell(cx, cy, 14.4, 8.8)
    well, wh = ell(cx, cy + 0.3, 10.8, 6.0)
    ring = top & ~well
    xs, ys = grid(N)
    rd = np.hypot((xs - cx) / 14.4, (ys - cy) / 8.8)
    paint(cv, ring, STONEWARE, np.clip(1 - np.abs(rd - 0.86) / 0.2, 0, 1) * 1.6 + 0.8, 0.66, 1.0, dither=0.3)
    paint(cv, well, STONEWARE, wh * 1.0, 0.5, 0.9, fbm(N, N, 3, 61), 0.3, 0.12)
    line = top & ~well & (np.abs(rd - 0.9) < 0.035)
    cv.put(line, (46, 74, 118))
    # the pieces, back to front, each with a glaze
    # carrot coins: an orange disc, a paler ring round its core, a gloss
    # potato chunks: a pale top and a darker, browned front
    order = [('p', 9.8, 14.2), ('c', 16.4, 12.8), ('p', 22.6, 14.4), ('c', 11.6, 19.0), ('p', 17.8, 19.2), ('c', 23.2, 19.8)]
    for kind, px, py in order:
        if kind == 'c':
            side_m, side_h = ell(px, py + 1.5, 3.6, 2.2)
            topm, th2 = ell(px, py, 3.6, 2.2)
            paint(cv, side_m & ~topm, CARROT, side_h * 2.0, 0.34, 0.9, None, 0.3)
            paint(cv, topm, CARROT, th2 * 3.0, 0.62, 1.1, fbm(N, N, 2, 71 + int(px)), 0.4, 0.2)
            core, _ = ell(px, py, 1.4, 0.9)
            cv.over(core & topm, np.array(CARROT[4], float), 0.55)
            shine(cv, topm, th2, (255, 250, 232), 0.5)
        else:
            frontm = poly([(px - 3.2, py), (px + 3.2, py), (px + 3.2, py + 2.8), (px - 3.2, py + 2.8)], N)
            topm = poly([(px - 3.0, py - 2.0), (px + 2.2, py - 2.0), (px + 3.2, py), (px - 3.2, py)], N)
            paint(cv, frontm, POTATO, np.full((N, N), 1.0), 0.36, 0.0, fbm(N, N, 2, 77 + int(px)), 0.4, 0.3)
            paint(cv, topm, POTATO, np.full((N, N), 1.0), 0.72, 0.0, fbm(N, N, 2, 79 + int(px)), 0.4, 0.2)
            put(cv, px - 2, py - 1, (255, 250, 230))
            put(cv, px - 1, py - 1, POTATO[5])
            cv.over(frontm & (ys >= py + 2), np.array(POTATO[0], float), 0.4)
    for (px, py) in ((13, 17), (19, 16), (14, 22), (21, 22), (8, 18)):
        if cv.a[py, px] > 0:
            put(cv, px, py, LEAFG[3])
            put(cv, px + 1, py, LEAFG[2])
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ cakes

def cakes():
    """Three thick honey cakes on a wooden board: golden crumb sides, a glossy honey glaze over each crust, a short drip at the front."""
    cv = Canvas(N)
    top_board = poly([(1.5, 15.0), (25.0, 9.0), (30.5, 15.8), (7.5, 26.4)], N)
    front_edge = poly([(7.5, 26.4), (30.5, 15.8), (30.5, 18.6), (7.5, 29.2)], N)
    left_edge = poly([(1.5, 15.0), (7.5, 26.4), (7.5, 29.2), (1.5, 17.8)], N)
    paint(cv, front_edge, WOODL, np.full((N, N), 1.0), 0.5, 0.0, dither=0.2)
    paint(cv, left_edge, WOODL, np.full((N, N), 1.0), 0.62, 0.0, dither=0.2)
    xs, ys = grid(N)
    n = fbm(N, N, 4, 81)
    v = (ys - 15.0) * 0.97 + (xs - 1.5) * 0.24
    t = 0.9 + 0.12 * np.sin(v * 1.2 + n * 3) + 0.08 * (n - 0.5)
    cv.put(top_board, bands(WOODL, np.where(top_board, t, 0), 0.3))
    cv.a[top_board] = 1
    cv.over(top_board & edge(top_board), np.array(WOODL[2], float), 0.5)
    for (px, py, rx, ry, seed) in ((10.0, 14.2, 5.7, 3.3, 91), (20.8, 12.4, 5.7, 3.3, 92), (15.8, 20.4, 6.2, 3.6, 93)):
        side, sh = ell(px, py + 3.0, rx, ry)
        topm, th = ell(px, py, rx, ry)
        wall = side & ~topm
        paint(cv, wall, CRUST, 0.35 + sh * 1.2, 0.6, 1.0, fbm(N, N, 3, seed), 0.35, 0.2)
        cv.over(wall & ~ell(px, py + 2.2, rx - 0.6, ry)[0], np.array(CRUST[5], float), 0.5)
        paint(cv, topm, CRUST, th * 2.4, 0.48, 1.15, fbm(N, N, 3, seed + 1), 0.4, 0.3)
        cv.over(topm & edge(topm), np.array(CRUST[2], float), 0.6)
        gm, gh = ell(px + 0.2, py - 0.1, rx - 1.3, ry - 0.8)
        paint(cv, gm, HONEY, gh * 2.0, 0.6, 1.1, None, 0.3)
        cv.over(gm & edge(gm), np.array(HONEY[1], float), 0.7)
        shine(cv, gm, gh, (255, 248, 206), 0.62)
        for dx_, ln in ((-1.6, 2), (2.2, 1)):
            for dy_ in range(ln):
                qx, qy = int(px + dx_), int(py + ry - 0.8 + dy_)
                if 0 <= qy < N and 0 <= qx < N:
                    put(cv, qx, qy, HONEY[2 + (dy_ == 0)])
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ loaf

def loaf():
    """A dark round trail loaf, scored and floured, tied across with a thin cord and a small bow."""
    cv = Canvas(N)
    cx, cy, rx, ry = 16.0, 17.0, 13.6, 10.0
    m, h = ell(cx, cy, rx, ry)
    xs, ys = grid(N)
    n = fbm(N, N, 3, 101)
    dome = h * 7.0
    foot = np.clip((ys - (cy + ry * 0.55)) / (ry * 0.5), 0, 1)
    paint(cv, m, DOUGH, dome, 0.56 - 0.12 * foot, 1.2, n, 0.45, 0.3)
    # a dusting of flour in drifts on the crown, not in seeds
    drift = (fbm(N, N, 4, 17) > 0.58) & m & (xs + ys < 30) & (ys < 17)
    cv.over(drift, np.array((226, 208, 174), float), 0.55)
    # three scores above the cord
    for (x0, y0, x1, y1) in ((7.0, 8.6, 9.6, 13.0), (13.4, 7.0, 15.4, 11.6), (19.6, 7.8, 22.6, 12.2)):
        pts = [(x0 + (x1 - x0) * s / 4.0 + 0.7 * math.sin(s / 4.0 * math.pi), y0 + (y1 - y0) * s / 4.0) for s in range(5)]
        d, _ = path_dist(pts, N)
        groove = (d <= 0.7) & m
        cv.put(groove, DOUGH[0])
        lip = (d > 0.7) & (d <= 1.5) & m & (xs < np.interp(ys, [p[1] for p in pts], [p[0] for p in pts]) + 0.4)
        cv.over(lip, np.array(DOUGH[5], float), 0.6)
        cv.over((d <= 0.45) & m, np.array((214, 170, 108), float), 0.6)
    # the cord: a thin twisted band round the middle, sagging in front like the loaf's own curve
    band_pts = [(2.6, 16.4), (8.0, 18.2), (16.0, 19.2), (24.0, 18.2), (29.4, 16.4)]
    d, along = path_dist(band_pts, N)
    bm = (d <= 1.0) & m
    t = 0.72 + 0.2 * np.sin(along * 2.1) - 0.2 * np.clip(d - 0.4, 0, 1)
    cv.put(bm, bands(TWINE, np.where(bm, t, 0), 0.3))
    cv.a[bm] = 1
    cv.over((d > 1.0) & (d <= 1.7) & m, np.array(DOUGH[0], float), 0.55)
    for (bx, by, ang) in ((13.8, 20.4, 22), (18.2, 20.6, -22)):
        lm, lh = ell_rot(bx, by, 2.5, 1.4, ang)
        cv.over(lm & edge(lm), np.array(TWINE[1], float), 0.7)
        paint(cv, lm & ~edge(lm), TWINE, lh * 2.0, 0.78, 0.8, None, 0.3)
    km, kh = ell(16.0, 20.4, 1.3, 1.2)
    paint(cv, km, TWINE, kh * 2.0, 0.85, 0.8, None, 0.3)
    for (x0, y0, x1, y1) in ((15.6, 21.4, 13.8, 25.2), (16.6, 21.4, 18.6, 25.0)):
        stroke(cv, [(x0, y0), (x1, y1)], 1.2, TWINE, 0.8, 0.8)
    rim(cv, 0.32)
    outline(cv, 0.8)
    return cv.image()


FILLING =[(24, 10, 8), (46, 20, 14), (72, 34, 20), (104, 52, 30), (138, 74, 42)]


# ================================================================ wedges

def _wedge(cv, top_poly, left_face, right_face, front_face):
    pass


def pie():
    """A wedge of raised pie: a glazed crimped lid with a steam hole and two pastry leaves, and the cut face showing crust, dark filling and base."""
    cv = Canvas(N)
    xs, ys = grid(N)
    apex = (4.0, 21.0)
    arc = [(7.5, 9.5), (12.0, 6.2), (18.5, 5.2), (24.5, 7.0), (28.4, 11.6), (29.6, 17.8)]
    H = 8.0
    top = poly([apex] + arc, N)
    front = poly([apex, arc[-1], (arc[-1][0], arc[-1][1] + H), (apex[0], apex[1] + H)], N)
    left = poly([apex, arc[0], (arc[0][0], arc[0][1] + H), (apex[0], apex[1] + H)], N)
    layers = [(0.2, CRUST, 0.68), (0.6, FILLING, 0.5), (0.2, CRUST, 0.52)]
    v = _front_layers(cv, front, apex, arc[-1], H, 111, layers)
    layers_l = [(0.2, CRUST, 0.45), (0.6, FILLING, 0.3), (0.2, CRUST, 0.34)]
    _front_layers(cv, left & ~front, apex, arc[0], H, 112, layers_l)
    rng = np.random.default_rng(9)
    for _ in range(16):
        px, py = int(rng.uniform(6, 29)), int(rng.uniform(14, 29))
        if front[py, px] and 0.25 < v[py, px] < 0.78:
            col = (214, 150, 104) if rng.random() < 0.55 else (232, 204, 156)
            put(cv, px, py, col)
            put(cv, px + 1, py, (170, 104, 66))
    d_in = dist_in(top, 12)
    h = np.clip(d_in / 6.0, 0, 1) ** 0.6 * 2.2
    n = fbm(N, N, 3, 113)
    paint(cv, top, CRUST, h, 0.7, 1.1, n, 0.4, 0.3)
    bev = top & (np.abs(ys - (apex[1] + (arc[-1][1] - apex[1]) * np.clip((xs - apex[0]) / (arc[-1][0] - apex[0]), 0, 1))) < 1.4)
    cv.over(bev, np.array(CRUST[5], float), 0.55)
    d, along = path_dist(arc, N)
    crimp = top & (d <= 1.9)
    scal = np.sin(along * 1.7) > -0.2
    cv.put(crimp & scal, bands(CRUST, np.where(crimp, 0.9 + 0.1 * np.sin(along * 1.7), 0), 0.25))
    cv.put(crimp & ~scal, bands(CRUST, np.where(crimp, 0.4, 0), 0.25))
    for (lx, ly, ang) in ((11.0, 13.4, -28), (20.0, 12.6, 24)):
        lm, lh = ell_rot(lx, ly, 3.0, 1.3, ang)
        paint(cv, lm & top, CRUST, lh * 2.0, 0.9, 0.8, None, 0.3)
        cv.over(lm & top & edge(lm), np.array(CRUST[1], float), 0.5)
    hm, hh = ell(15.6, 14.6, 2.1, 1.3)
    cv.put(hm, (30, 14, 8))
    cv.over(hm & ~ell(15.6, 14.9, 1.3, 0.7)[0], np.array(CRUST[1], float), 0.7)
    shine(cv, top & ~crimp, h, (255, 244, 208), 0.9)
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


CUSTARD = [(120, 84, 30), (170, 124, 48), (206, 160, 70), (228, 188, 96), (242, 214, 130)]
FLESH = [(188, 168, 112), (216, 196, 136), (236, 220, 164), (248, 238, 192), (255, 250, 222)]


def tart():
    """A wedge of orchard tart: a pastry rim, golden custard, fanned apple slices with a red skin edge, glazed."""
    cv = Canvas(N)
    xs, ys = grid(N)
    apex = (5.0, 23.0)
    arc = [(7.6, 11.0), (12.6, 7.0), (19.4, 5.8), (25.4, 8.4), (29.0, 13.6), (30.2, 19.6)]
    H = 3.6
    top = poly([apex] + arc, N)
    front = poly([apex, arc[-1], (arc[-1][0], arc[-1][1] + H), (apex[0], apex[1] + H)], N)
    left = poly([apex, arc[0], (arc[0][0], arc[0][1] + H), (apex[0], apex[1] + H)], N)
    n = fbm(N, N, 3, 121)
    paint(cv, front, CRUST, np.full((N, N), 1.0), 0.4, 0.0, n, 0.4, 0.25)
    paint(cv, left & ~front, CRUST, np.full((N, N), 1.0), 0.3, 0.0, n, 0.4, 0.25)
    paint(cv, top, CRUST, np.full((N, N), 1.0), 0.74, 0.0, n, 0.4, 0.25)
    d_in = dist_in(top, 8)
    custard = top & (d_in > 1.6)
    paint(cv, custard, CUSTARD, np.clip(d_in / 5.0, 0, 1) * 1.0, 0.6, 0.8, n, 0.4, 0.2)
    d, along = path_dist(arc, N)
    flute = top & (d <= 1.5) & (np.sin(along * 1.9) > 0.0)
    cv.over(flute, np.array(CRUST[5], float), 0.5)
    ax, ay = apex
    for (r, angs) in ((9.5, (-70, -46, -22)), (15.5, (-68, -50, -32, -14)), (21.5, (-60, -44, -28, -12, 2))):
        for a_ in angs:
            th = math.radians(a_)
            px, py = ax + r * math.cos(th), ay + r * math.sin(th) * 0.84
            tx, ty = -math.sin(th), math.cos(th) * 0.84
            pts = [(px + tx * s * 1.9 + math.cos(th) * (s * s) * -0.5, py + ty * s * 1.9 + math.sin(th) * 0.84 * (s * s) * -0.5) for s in (-1.0, 0.0, 1.0)]
            d1, _ = path_dist(pts, N)
            sm = (d1 <= 1.7) & custard
            if not sm.any():
                continue
            hgt = np.sqrt(np.clip(1 - (d1 / 1.7) ** 2, 0, 1))
            paint(cv, sm, FLESH, hgt * 2.2, 0.72, 1.0, None, 0.3)
            nx, ny = math.cos(th), math.sin(th) * 0.84
            pts2 = [(x + nx * 1.2, y + ny * 1.2) for (x, y) in pts]
            d2, _ = path_dist(pts2, N)
            skin = sm & (d2 < d1 - 0.2)
            cv.put(skin, bands(APPLE_SKIN, np.where(skin, 0.62 + 0.25 * hgt, 0), 0.3))
            shine(cv, sm, hgt, (255, 254, 238), 0.7)
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ fish

def fish():
    """A plank of cedar with a salmon fillet on it, seared in stripes, and a sprig of herb."""
    cv = Canvas(N)
    xs, ys = grid(N)
    top = poly([(2.0, 16.4), (24.0, 8.0), (30.0, 12.4), (8.0, 23.4)], N)
    front = poly([(8.0, 23.4), (30.0, 12.4), (30.0, 15.4), (8.0, 26.4)], N)
    left = poly([(2.0, 16.4), (8.0, 23.4), (8.0, 26.4), (2.0, 19.4)], N)
    for face, shade in ((front, 0.34), (left, 0.5)):
        paint(cv, face, CEDAR, np.full((N, N), 1.0), shade, 0.0, None, 0.3)
    n = fbm(N, N, 4, 131)
    v = (ys - 16.4) * 0.9 + (xs - 2.0) * 0.38
    t = 0.56 + 0.25 * np.sin(v * 1.1 + n * 3.2) + 0.1 * (n - 0.5)
    cv.put(top, bands(CEDAR, np.where(top, t, 0), 0.3))
    cv.a[top] = 1
    cv.over(top & edge(top), np.array(CEDAR[1], float), 0.5)
    # the fillet: a long oval, tail to the right, pink-orange, with white fat lines running across it
    fm, fh = ell_rot(15.2, 15.2, 11.2, 4.6, -24)
    fm &= True
    skin = fm & ~ell_rot(15.2, 14.4, 10.6, 4.0, -24)[0]
    paint(cv, fm, SALMON, fh * 2.8, 0.56, 1.1, fbm(N, N, 3, 133), 0.4, 0.25)
    cv.over(skin, np.array(SALMON[0], float), 0.5)
    for off in (-3.6, -1.2, 1.2, 3.6):
        d, _ = path_dist([(9.2 + off * 0.9, 18.4 - off * 0.35), (14.4 + off * 0.9, 12.8 - off * 0.35)], N)
        lm = fm & (d <= 0.7)
        cv.put(lm, bands(FAT, np.where(lm, 0.5 + 0.4 * fh, 0), 0.3))
    # sear marks
    for off in (-5.0, -0.6, 3.8):
        d, _ = path_dist([(8.0 + off, 15.0 - off * 0.2), (13.0 + off, 12.8 - off * 0.2)], N)
        sm = fm & (d <= 0.45)
        cv.over(sm, np.array(SALMON[0], float), 0.55)
    shine(cv, fm, fh, (255, 236, 220), 0.5)
    # a sprig at the left end: three leaves on a stem
    stroke(cv, [(4.8, 19.4), (9.4, 15.6)], 0.9, LEAFG, 0.55, 0.8)
    for (lx, ly, ang) in ((6.4, 16.6, -50), (8.2, 18.4, 30), (9.8, 14.6, -20)):
        lm, lh = ell_rot(lx, ly, 2.2, 1.0, ang)
        paint(cv, lm, LEAFG, lh * 2.0, 0.65, 1.0, None, 0.3)
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ roast

def roast():
    """A carved slice of roast on its bone: a browned crust round a pink middle, the juices pooling dark."""
    cv = Canvas(N)
    xs, ys = grid(N)
    # the juices under it
    jm, jh = ell(17.0, 23.8, 12.4, 4.4)
    paint(cv, jm, ROAST, jh * 1.4, 0.2, 0.8, None, 0.2)
    cv.over(ell(13.5, 22.8, 4.6, 1.4)[0] & jm, np.array(ROAST[3], float), 0.55)
    # the bone: from the lower left, a shaft and a knuckle, lying under the slice
    stroke(cv, [(5.0, 27.0), (14.0, 18.6)], 3.0, BONE, 0.58, 1.0, fbm(N, N, 2, 141), 0.35)
    for (px, py) in ((3.8, 26.6), (5.2, 28.4)):
        km, kh = ell(px, py, 2.1, 2.1)
        paint(cv, km, BONE, kh * 2.6, 0.62, 1.0, None, 0.3)
    # the slice: an upright oval, crust all round, pink inside
    sm, sh = ell_rot(18.0, 13.6, 9.6, 8.6, -14)
    paint(cv, sm, ROAST, sh * 3.0, 0.5, 1.1, fbm(N, N, 3, 143), 0.4, 0.3)
    inner, ih = ell_rot(18.0, 13.8, 7.2, 6.3, -14)
    paint(cv, inner, PINK, ih * 2.4, 0.56, 1.1, fbm(N, N, 3, 145), 0.45, 0.25)
    # the grain of the meat: faint darker lines
    for off in (-3.0, 0.0, 3.0):
        d, _ = path_dist([(12.4 + off * 0.5, 8.4 - off * 0.2 + 3.4), (23.6 + off * 0.5, 12.6 - off * 0.2 + 3.4)], N)
        gm = inner & (d <= 0.45)
        cv.over(gm, np.array(PINK[0], float), 0.4)
    # a rim of fat: a pale band at the top of the crust
    fatm = sm & ~ell_rot(18.6, 14.8, 9.2, 8.0, -14)[0]
    cv.over(fatm, np.array(FAT[2], float), 0.55)
    shine(cv, inner, ih, (255, 232, 222), 0.5)
    # a drip of juice down to the pool
    for dy_ in range(0, 5):
        put(cv, 22 + (dy_ > 2), 21 + dy_, ROAST[1 + (dy_ == 0)])
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ strips

def strips():
    """Three strips of smoked mutton hanging from a string: dark, glossy, streaked with fat."""
    cv = Canvas(N)
    xs, ys = grid(N)
    # the string: a sagging line across the top, a loop at each end
    string_pts = [(1.5, 3.2), (8.0, 5.4), (16.0, 6.2), (24.0, 5.4), (30.5, 3.2)]
    # the strips: (x of the top, length, width, sway)
    strips_ = [(8.2, 20.0, 5.2, 1.4), (16.0, 24.0, 5.6, -1.0), (23.8, 18.0, 5.0, 1.2)]
    for (sx, L, w, sway) in strips_:
        y0 = 4.4 + 1.6 * (1 - abs(sx - 16) / 8.0) + 0.0
        pts = [(sx + sway * math.sin(k / 6.0 * math.pi * 1.2), y0 + L * k / 6.0) for k in range(7)]
        d, along = path_dist(pts, N)
        width = w / 2 * (1.0 - 0.18 * (along / L)) * (0.92 + 0.08 * np.sin(along * 0.9))
        m = d <= width
        side = xs - np.interp(ys, [p[1] for p in pts], [p[0] for p in pts])
        n = fbm(N, N, 3, 151 + int(sx))
        t = 0.5 + 0.32 * (-side / np.maximum(width, 0.3)) * 0.8 + 0.2 * (n - 0.5) - 0.12 * (along / L)
        cv.put(m, bands(SMOKED, np.where(m, t, 0), 0.4))
        cv.a[m] = 1
        # fat streaks: pale diagonal flecks
        for yy in range(int(y0 + 2), int(y0 + L - 1), 4):
            xx = int(np.interp(yy, [p[1] for p in pts], [p[0] for p in pts]) + (1 if (yy // 4) % 2 else -1))
            for dx_, dy_ in ((0, 0), (1, 1)):
                if 0 <= xx + dx_ < N and 0 <= yy + dy_ < N and m[yy + dy_, xx + dx_]:
                    put(cv, xx + dx_, yy + dy_, (210, 150, 118))
        # a gloss line
        gl = m & (np.abs(side + width * 0.38) < 0.5) & (n > 0.38)
        cv.over(gl, np.array(SMOKED[5], float), 0.7)
        # the hole at the top and the string through it
        hole = ell(pts[0][0], pts[0][1] + 0.8, 1.0, 0.8)[0]
        cv.put(hole & m, SMOKED[0])
    d, along = path_dist(string_pts, N)
    sm = d <= 0.7
    cv.put(sm, bands(STRING, np.where(sm, 0.56 + 0.3 * np.sin(along * 2.0), 0), 0.3))
    cv.a[sm] = 1
    # a loop at each end
    for (lx, ly) in ((1.8, 4.4), (30.2, 4.4)):
        lm, _ = ell(lx, ly, 1.7, 1.7)
        cv.put(lm & ~ell(lx, ly, 0.9, 0.9)[0], STRING[2])
        cv.a[lm & ~ell(lx, ly, 0.9, 0.9)[0]] = 1
    rim(cv, 0.3)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ scorched mess

def scorched_mess():
    """A black lump: charred crust, ember-red cracks showing in it, a few grey ash flecks."""
    cv = Canvas(N)
    xs, ys = grid(N)
    cx, cy = 16.0, 18.0
    ang = np.arctan2(ys - cy, xs - cx)
    rr = np.hypot((xs - cx) / 1.12, (ys - cy) / 0.92)
    R = 11.0 + 1.5 * np.sin(2 * ang + 0.4) + 1.2 * np.sin(3 * ang + 1.9) + 0.8 * np.sin(5 * ang + 3.0) + 0.5 * np.sin(8 * ang + 0.7)
    m = rr <= R
    d_in = dist_in(m, 14)
    h = np.clip(d_in / 6.0, 0, 1) ** 0.7 * 3.2 + 1.2 * fbm(N, N, 3, 161)
    n = fbm(N, N, 3, 163)
    paint(cv, m, CHAR, h, 0.4, 1.2, n, 0.5, 0.3)
    # cracks: thin dark-red lines
    for (x0, y0, x1, y1) in ((10, 14, 17, 19), (17, 19, 23, 17), (14, 21, 19, 25), (15, 11, 19, 15)):
        d, _ = path_dist([(x0, y0), ((x0 + x1) / 2 + 1.2, (y0 + y1) / 2 - 1.0), (x1, y1)], N)
        cm = m & (d <= 0.55)
        cv.over(cm, np.array(EMBER[1], float), 0.7)
        cm2 = m & (d <= 0.3)
        cv.over(cm2, np.array(EMBER[2], float), 0.7)
    # blistered bumps catching the light, and ash flecks
    rng = np.random.default_rng(163)
    for _ in range(14):
        px, py = int(rng.uniform(6, 26)), int(rng.uniform(8, 27))
        if m[py, px]:
            put(cv, px, py, CHAR[4] if rng.random() < 0.7 else CHAR[5])
    rim(cv, 0.28)
    outline(cv, 0.8)
    return cv.image()



def _front_layers(cv, face, apex, far, H, seed, layers):
    """Paint a cut face: its rows run from the top edge (the line apex to far) down H px: [layers] a list of (share of the height, palette, base)."""
    xs, ys = grid(N)
    span = far[0] - apex[0]
    edge_y = apex[1] + (far[1] - apex[1]) * np.clip((xs - apex[0]) / (span if abs(span) > 1e-9 else 1.0), 0, 1)
    v = np.where(face, (ys - edge_y) / H, 0)
    n = fbm(N, N, 2, seed)
    acc = 0.0
    for share, pal, base in layers:
        sel = face & (v >= acc) & (v < acc + share + 1e-9)
        cv.put(sel, bands(pal, np.where(sel, base + 0.3 * (n - 0.5), 0), 0.4))
        acc += share
    cv.a[face] = 1
    return v


PAINTERS = {
    'dish_roots': roots, 'dish_cakes': cakes, 'dish_loaf': loaf, 'dish_pie': pie, 'dish_tart': tart, 'dish_fish': fish, 'dish_roast': roast,
    'dish_strips': strips, 'scorched_mess': scorched_mess,
}
