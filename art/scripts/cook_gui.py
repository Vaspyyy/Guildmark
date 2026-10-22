"""The Hearthcraft GUI sprites (cook_art.py): 21 in `textures/gui/sprites/cook/`, drawn at two texture pixels to a GUI pixel as the mod's other sprites
are (a glyph is 24 x 24 and shown at 12 x 12).

    book_page.png        the Cook's Book's page: parchment, a nine-slice (48 x 64 GUI px, border 8)
    book_ribbon.png      the bookmark ribbon that hangs from its top edge
    book_lock.png        the padlock on an entry the cook has not learned yet
    role_<role>.png      the seven roles of an ingredient: body (a wheat ear), meat (a steak), sea (a fish), green (a leaf), sweet (berries),
                         rich (an egg), savour (a mushroom)
    station_<station>.png  the four stations: stewpot, hob, oven, spit (the flames move)
    tier_<tier>.png      the four tiers' seals: plain (pewter, one pip), sound (bronze, two), fine (silver, three), hallmarked (gold, a star, with a glint)
    hint_pot.png, hint_flame.png, hint_chain.png   the three hints beside the crosshair, 32 x 32 shown at 16 x 16: the pot (the vessel), the flame
                         (the fire; it moves), the chain (the heat)
"""
import json
import math
import os

import numpy as np
from PIL import Image

from hdkit import PAL, Canvas, grid, fbm, rand, bands, light, poly, path_dist, edge, dist_in, outline
from cook_fx import flame
from cook_overlays import sweep, mcmeta

G = 24                                    # a glyph, in texture pixels

WHEAT = [(70, 44, 12), (128, 86, 26), (186, 138, 46), (226, 180, 76), (248, 214, 124), (255, 240, 176)]
STRAW = [(60, 50, 24), (108, 92, 42), (158, 136, 66), (200, 178, 96)]
RED = [(54, 14, 18), (104, 26, 28), (160, 46, 42), (206, 80, 66), (234, 124, 104), (250, 176, 156)]
FATC = [(196, 140, 128), (226, 176, 160), (246, 214, 198), (255, 240, 230)]
BONE = PAL['bone']
SEA = [(14, 34, 76), (28, 66, 128), (56, 112, 180), (100, 160, 216), (160, 206, 238), (214, 238, 252)]
SILVERBELLY = [(120, 150, 176), (176, 200, 220), (220, 236, 246), (248, 252, 255)]
LEAF = [(14, 40, 20), (28, 78, 36), (52, 130, 56), (88, 178, 78), (140, 214, 112), (200, 240, 160)]
BERRY = [(40, 8, 44), (84, 18, 88), (134, 32, 120), (184, 58, 150), (228, 104, 186), (250, 170, 224)]
EGG = [(112, 84, 60), (180, 146, 108), (226, 198, 154), (246, 228, 190), (255, 246, 222), (255, 252, 240)]
YOLK = [(150, 80, 10), (214, 130, 20), (248, 184, 40), (255, 222, 96)]
CAP = [(46, 24, 16), (92, 52, 32), (138, 86, 50), (178, 120, 70), (214, 160, 104), (238, 200, 150)]
STEMC = [(130, 110, 84), (184, 162, 126), (222, 204, 168), (244, 232, 202), (255, 248, 226)]
IRON = PAL['iron']
STEEL = PAL['steel']
EMBER = PAL['ember']
GOLD = PAL['gold']
BRICK = [(36, 14, 12), (82, 30, 24), (132, 54, 38), (170, 82, 54), (204, 114, 76), (230, 150, 108)]
PEWTER = [(34, 36, 42), (70, 74, 84), (110, 116, 126), (150, 156, 164), (188, 194, 200), (226, 230, 234)]
BRONZE = [(40, 20, 10), (84, 44, 20), (130, 76, 34), (172, 108, 50), (208, 144, 76), (238, 186, 122)]
SILVER = [(24, 28, 40), (52, 60, 80), (96, 108, 128), (150, 162, 178), (200, 210, 222), (238, 244, 250)]
PARCH = [(96, 70, 44), (150, 116, 76), (196, 164, 114), (222, 196, 148), (238, 218, 172), (248, 234, 198)]


# ---------------------------------------------------------------- toolkit (size n)

def _ell(cx, cy, rx, ry, n, ang=0.0):
    """Mask and dome height of an ellipse turned [ang] degrees (clockwise on the screen)."""
    xs, ys = grid(n)
    a = math.radians(ang)
    u = (xs - cx) * math.cos(a) + (ys - cy) * math.sin(a)
    v = -(xs - cx) * math.sin(a) + (ys - cy) * math.cos(a)
    d = (u / rx) ** 2 + (v / ry) ** 2
    return d <= 1.0, np.sqrt(np.clip(1 - d, 0, 1))


def _rod(pts, r, n):
    d, _ = path_dist(pts, n)
    return d <= r, np.sqrt(np.clip(1 - (d / r) ** 2, 0, 1))


def _fill(cv, mask, pal, h, base=0.55, k=1.5, noise=None, dither=0.0):
    t = base + k * light(np.where(mask, h, 0.0), 1.0)
    if noise is not None:
        t = t + 0.14 * (noise - 0.5)
    cv.put(mask, bands(pal, np.where(mask, t, 0), dither))
    cv.a[mask] = 1.0


def _px(cv, x, y, col, a=1.0):
    if 0 <= x < cv.w and 0 <= y < cv.h:
        cv.px(int(x), int(y), tuple(float(c) for c in col), a)


# ---------------------------------------------------------------- the seven roles

def role_body():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    # the straw and a bent leaf
    m, h = _rod([(11.0, 23.0), (12.4, 11.0), (12.8, 5.0)], 0.9, n)
    _fill(cv, m, STRAW, h, 0.6, 1.0)
    lm, lh = _rod([(12.0, 19.0), (15.5, 17.0), (19.5, 18.4)], 1.1, n)
    _fill(cv, lm, LEAF, lh, 0.6, 1.0)
    # the ear: pairs of grains up the stem, a grain at the tip
    grains = []
    for i in range(5):
        y = 6.6 + i * 2.7
        x = 12.8 - (y - 5.0) * 0.07
        grains.append((x - 2.0, y, 2.5, 1.45, -34))
        grains.append((x + 2.0, y, 2.5, 1.45, 34))
    grains.append((12.9, 3.6, 1.35, 2.3, 0))
    for (cx, cy, rx, ry, ang) in reversed(grains):
        gm, gh = _ell(cx, cy, rx, ry, n, ang)
        _fill(cv, gm, WHEAT, gh * 1.6, 0.64, 1.4)
        cv.over(gm & edge(gm), np.array(WHEAT[1], float), 0.5)
    # a few awns
    for (x0, y0, x1, y1) in ((10.6, 6.4, 8.6, 2.6), (15.2, 6.4, 17.2, 2.6), (12.9, 1.6, 12.9, 0.6)):
        d, _ = path_dist([(x0, y0), (x1, y1)], n)
        cv.put((d <= 0.45) & (cv.a < 0.5), np.array(WHEAT[4], float))
        cv.a[(d <= 0.45) & (cv.a < 0.5)] = 1.0
    outline(cv, 0.8)
    return cv.image()


def role_meat():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    # the cut: a plump kidney of meat, a rim of fat round it, marbling, a bone at its end
    m1, h1 = _ell(11.6, 12.6, 9.4, 7.0, n, -18)
    m2, h2 = _ell(17.4, 8.6, 4.2, 3.6, n, 20)
    cut = m1 | m2
    hh = np.maximum(h1, h2)
    _fill(cv, cut, FATC, hh * 1.2, 0.7, 1.0)
    inner = cut & (dist_in(cut, 3) >= 2)
    _fill(cv, inner, RED, hh * 2.2, 0.56, 1.6, fbm(n, n, 5, 3, 3))
    for (x0, y0, x1, y1) in ((6.5, 14.5, 12.0, 11.8), (10.0, 17.0, 16.0, 13.6), (13.5, 9.8, 18.5, 8.2)):
        d, _ = path_dist([(x0, y0), (x1, y1)], n)
        cv.over((d <= 0.55) & inner, np.array(FATC[2], float), 0.7)
    bm, bh = _ell(5.6, 18.2, 3.0, 3.0, n)
    _fill(cv, bm, BONE, bh * 2.0, 0.66, 1.3)
    hole, _ = _ell(5.4, 18.2, 1.2, 1.2, n)
    cv.put(hole, np.array(BONE[1], float))
    outline(cv, 0.8)
    return cv.image()


def role_sea():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    body, bh = _ell(13.2, 12.0, 8.2, 4.9, n, -8)
    tail = poly([(6.0, 12.4), (1.2, 6.2), (3.2, 12.0), (1.2, 17.8)], n)
    dorsal = poly([(10.5, 7.8), (13.5, 3.8), (16.5, 8.2)], n)
    belly = poly([(12.0, 15.4), (16.0, 17.8), (18.0, 14.6)], n)
    _fill(cv, tail, SEA, np.full((n, n), 1.0), 0.52, 0.0)
    _fill(cv, dorsal, SEA, np.full((n, n), 1.0), 0.5, 0.0)
    _fill(cv, belly, SEA, np.full((n, n), 1.0), 0.42, 0.0)
    _fill(cv, body, SEA, bh * 2.4, 0.6, 1.7)
    under = body & (ys > 12.6 + (xs - 13.0) * -0.14)
    cv.put(under, bands(SILVERBELLY, np.where(under, 0.55 + 0.4 * light(np.where(body, bh * 2.0, 0), 1.0), 0), 0.0))
    for (cx, cy) in ((11.6, 11.6), (14.6, 10.8), (14.0, 13.8), (9.8, 13.6)):
        d = np.hypot(xs - cx, ys - cy)
        arc = body & (d > 1.5) & (d < 2.4) & (xs > cx)
        cv.over(arc, np.array(SEA[4], float), 0.5)
    gill = body & (np.abs(xs - 17.6) < 0.6) & (ys > 8.6) & (ys < 15.4)
    cv.over(gill, np.array(SEA[1], float), 0.8)
    _px(cv, 19, 10, (250, 252, 255))
    _px(cv, 19, 11, (14, 16, 30))
    _px(cv, 20, 11, (14, 16, 30))
    outline(cv, 0.8)
    return cv.image()


def role_green():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    x0, y0, x1, y1 = 4.2, 20.0, 20.4, 3.8
    d, t = path_dist([(x0, y0), (x1, y1)], n)
    # signed side of the axis: positive toward the lower right
    ax, ay = x1 - x0, y1 - y0
    L = math.hypot(ax, ay)
    side = ((xs - x0) * ay - (ys - y0) * ax) / L * -1.0
    tt = np.clip(((xs - x0) * ax + (ys - y0) * ay) / (L * L), 0, 1)
    prof = 5.6 * np.sin(np.pi * np.clip(tt, 0, 1)) ** 0.85 * (1.0 - 0.15 * tt)
    leaf = (np.abs(side) <= prof) & (tt > 0.04) & (tt < 0.98)
    h = np.sqrt(np.clip(1 - (side / np.maximum(prof, 0.3)) ** 2, 0, 1)) * 2.4
    _fill(cv, leaf, LEAF, h, 0.6, 1.5, fbm(n, n, 4, 8, 3))
    rib = leaf & (np.abs(side) < 0.55)
    cv.over(rib, np.array(LEAF[1], float), 0.9)
    for k in (0.3, 0.5, 0.7):
        for sgn in (-1, 1):
            # a vein leaves the rib toward the leaf's edge, slanting toward the tip
            px, py = x0 + ax * k, y0 + ay * k
            nx, ny = -ay / L * sgn, ax / L * sgn
            ex, ey = px + nx * 4.0 + ax / L * 2.4, py + ny * 4.0 + ay / L * 2.4
            dd, _ = path_dist([(px, py), (ex, ey)], n)
            cv.over((dd <= 0.45) & leaf, np.array(LEAF[1], float), 0.55)
    sm, sh = _rod([(x0 + 0.4, y0 - 0.4), (x0 - 2.0, y0 + 2.2)], 0.9, n)
    _fill(cv, sm & ~leaf, LEAF, sh, 0.4, 1.0)
    outline(cv, 0.8)
    return cv.image()


def role_sweet():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    sm, sh = _rod([(12.0, 10.0), (12.6, 4.4), (14.4, 2.6)], 0.8, n)
    _fill(cv, sm, LEAF, sh, 0.5, 1.0)
    lm, lh = _ell(16.8, 4.6, 3.2, 1.6, n, -25)
    _fill(cv, lm, LEAF, lh * 1.6, 0.6, 1.2)
    for (cx, cy, r) in ((7.6, 15.4, 5.6), (15.8, 14.2, 5.2)):
        bm, bh = _ell(cx, cy, r, r, n)
        _fill(cv, bm, BERRY, bh * 3.0, 0.58, 1.6)
        cv.over(bm & edge(bm), np.array(BERRY[1], float), 0.4)
        _px(cv, cx - r * 0.38, cy - r * 0.42, (255, 226, 246))
        _px(cv, cx - r * 0.38 + 1, cy - r * 0.42, (250, 190, 232))
        calyx, _ = _ell(cx + 0.2, cy - r + 1.0, 1.7, 0.9, n)
        cv.put(calyx & (cv.a > 0.5), np.array(LEAF[2], float))
    outline(cv, 0.8)
    return cv.image()


def role_rich():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    cy, cx = 13.0, 12.0
    u = (ys - cy) / 9.4
    prof = 6.7 * np.sqrt(np.clip(1 - u * u, 0, 1)) * (1.0 + 0.16 * u)
    egg = (np.abs(xs - cx) <= prof) & (np.abs(u) < 1.0)
    h = np.sqrt(np.clip(1 - ((xs - cx) / np.maximum(prof, 0.3)) ** 2, 0, 1)) * np.sqrt(np.clip(1 - u * u, 0, 1)) * 4.0
    _fill(cv, egg, EGG, h, 0.62, 1.7)
    # a shine and a cool shadow under it
    for (sx, sy) in ((9.4, 8.2), (9.4, 9.2), (10.4, 7.4)):
        _px(cv, sx, sy, (255, 255, 250))
    outline(cv, 0.8)
    return cv.image()


def role_savour():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    stem = poly([(9.6, 12.0), (14.6, 12.0), (15.4, 20.6), (13.4, 21.8), (10.6, 21.8), (8.6, 20.6)], n)
    sh = np.clip(1 - np.abs(xs - 12.0) / 3.4, 0, 1) * 2.0
    _fill(cv, stem, STEMC, sh, 0.62, 1.5)
    cap = ((xs - 12.0) / 9.6) ** 2 + ((ys - 12.4) / 7.6) ** 2 <= 1.0
    cap &= ys < 13.6
    ch = np.sqrt(np.clip(1 - ((xs - 12.0) / 9.6) ** 2 - ((ys - 12.4) / 7.6) ** 2, 0, 1)) * 3.2
    _fill(cv, cap, CAP, ch, 0.58, 1.6, fbm(n, n, 4, 12, 3))
    gills = cap & (ys > 12.4)
    cv.put(gills, np.array(STEMC[1], float))
    cv.over(gills & (np.sin(xs * 2.6) > 0.3), np.array(STEMC[0], float), 0.6)
    for (sx, sy, r) in ((8.4, 8.4, 1.3), (13.2, 6.6, 1.6), (16.6, 9.8, 1.2)):
        sm, shh = _ell(sx, sy, r, r * 0.85, n)
        cv.put(sm & cap, bands(STEMC, np.where(sm, 0.8 + 0.2 * shh, 0), 0.0))
    outline(cv, 0.8)
    return cv.image()


ROLES = {'role_body': role_body, 'role_meat': role_meat, 'role_sea': role_sea, 'role_green': role_green, 'role_sweet': role_sweet,
         'role_rich': role_rich, 'role_savour': role_savour}

ROASTC = [(40, 18, 12), (80, 38, 22), (122, 62, 34), (162, 92, 48), (196, 124, 68), (222, 158, 98)]


def _tone_fill(cv, mask, pal, tone, dither=0.0):
    """Fill [mask] with [pal] at the tone [tone] (an array or a number)."""
    t = np.broadcast_to(np.asarray(tone, float), mask.shape)
    cv.put(mask, bands(pal, np.where(mask, t, 0), dither))
    cv.a[mask] = 1.0


def _lit_fill(cv, mask, pal, hgt, base, k=1.1):
    _tone_fill(cv, mask, pal, base + k * light(np.where(mask, hgt, 0.0), 1.0))


# ---------------------------------------------------------------- the four stations (the flames move: four frames each)

FLAME_FRAMES = 4


def _flames(cv, n, frame, tongues, base_y):
    """Tongues of flame on the canvas: each (x, height, width, phase). They lean and rise and fall as the frame goes round."""
    for (x, hgt, w, ph) in tongues:
        a = 2 * math.pi * (frame / FLAME_FRAMES + ph)
        m, ax, t = flame(x + 0.5 * math.sin(a), base_y, hgt * (1.0 + 0.12 * math.sin(a + 1.3)), w, 1.6 * math.sin(a + 0.6), n)
        _tone_fill(cv, m, EMBER, 0.3 + 0.38 * ax + 0.1 * (1 - t))
        im, iax, _ = flame(x + 0.3 * math.sin(a), base_y, hgt * 0.62 * (1.0 + 0.12 * math.sin(a + 1.3)), w * 0.55, 0.8 * math.sin(a + 0.6), n)
        _tone_fill(cv, im, EMBER, 0.78 + 0.2 * iax)


def _pot(cv, n, cx, top, w, h):
    """An iron pot seen from the side: a rim, a belly that narrows to a round bottom, two ears."""
    xs, ys = grid(n)
    t = np.clip((ys - top) / h, 0, 1)
    half = w / 2 * np.sqrt(np.clip(1 - (t * 0.9) ** 2.4, 0, 1))
    body = (ys >= top) & (ys <= top + h) & (np.abs(xs - cx) <= half + 0.001)
    hh = np.sqrt(np.clip(1 - ((xs - cx) / np.maximum(half, 0.5)) ** 2, 0, 1)) * 2.8
    _lit_fill(cv, body, IRON, hh, 0.44, 0.9)
    rim_m = (np.abs(xs - cx) <= w / 2 + 0.7) & (ys >= top) & (ys <= top + 1.8)
    _tone_fill(cv, rim_m, IRON, 0.62 + 0.2 * (ys < top + 0.9))
    for sgn in (-1, 1):
        e = (np.abs(xs - (cx + sgn * (w / 2 + 1.1))) <= 1.2) & (ys >= top + 1.0) & (ys <= top + 3.2)
        _tone_fill(cv, e, IRON, 0.5)


def station_stewpot(frame=0):
    n = G
    cv = Canvas(n)
    # two logs, the flames between them, the pot hung over them by its bail
    for (x0, y0, x1, y1) in ((3.5, 22.0, 20.5, 20.2), (3.5, 20.2, 20.5, 22.0)):
        m, h = _rod([(x0, y0), (x1, y1)], 1.4, n)
        _lit_fill(cv, m, PAL['wood'], h, 0.55, 0.9)
    _flames(cv, n, frame, ((8.6, 6.4, 2.3, 0.0), (12.0, 9.4, 3.2, 0.3), (15.6, 6.8, 2.3, 0.62)), 20.8)
    _pot(cv, n, 12.0, 6.8, 13.0, 9.4)
    bail, _ = _rod([(5.2, 8.4), (7.0, 3.6), (12.0, 2.2), (17.0, 3.6), (18.8, 8.4)], 0.55, n)
    _tone_fill(cv, bail & (cv.a < 0.5), IRON, 0.8)
    outline(cv, 0.8)
    return cv.image()


def station_hob(frame=0):
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    # the iron top, the flame under the pan, the pan with its long handle and an egg in it
    top_m = (xs >= 1.5) & (xs <= 22.5) & (ys >= 18.6) & (ys <= 22.2)
    _tone_fill(cv, top_m, IRON, 0.42 + 0.3 * (ys < 19.6))
    _flames(cv, n, frame, ((7.8, 3.8, 1.5, 0.0), (11.6, 5.0, 1.9, 0.33), (15.4, 3.8, 1.5, 0.66)), 18.8)
    pm, ph = _ell(11.4, 13.4, 8.2, 2.2, n)
    wall = _ell(11.4, 15.0, 8.2, 2.6, n)[0] | pm
    _tone_fill(cv, wall, IRON, 0.38)
    _tone_fill(cv, pm, IRON, 0.18 + 0.5 * ph)
    _tone_fill(cv, pm & ~_ell(11.4, 13.6, 6.9, 1.5, n)[0], IRON, 0.82)
    hm, hh = _rod([(18.4, 12.6), (22.6, 8.6)], 1.2, n)
    _lit_fill(cv, hm, PAL['wood'], hh, 0.5, 0.9)
    em, _ = _ell(10.4, 13.3, 2.6, 1.0, n)
    _tone_fill(cv, em, PAL['bone'], 0.8)
    ym, _ = _ell(10.6, 13.2, 1.0, 0.6, n)
    cv.put(ym, np.array((250, 190, 50), float))
    outline(cv, 0.8)
    return cv.image()


def station_oven(frame=0):
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    chim = (xs >= 15.0) & (xs <= 18.4) & (ys >= 2.0) & (ys <= 9.0)
    _tone_fill(cv, chim, BRICK, 0.4 + 0.4 * (xs < 16.6))
    dome = (((xs - 12.0) / 10.2) ** 2 + ((ys - 13.0) / 9.4) ** 2 <= 1.0) & (ys <= 13.0)
    body = (xs >= 1.8) & (xs <= 22.2) & (ys >= 12.6) & (ys <= 21.6)
    shell = dome | body
    hh = np.sqrt(np.clip(1 - ((xs - 12.0) / 10.6) ** 2 - ((np.minimum(ys, 13.0) - 13.0) / 9.8) ** 2, 0, 1)) * 3.0
    _lit_fill(cv, shell, BRICK, hh, 0.5, 0.8)
    for yy in (7.0, 10.0, 13.0, 16.0, 19.0):
        cv.over(shell & (np.abs(ys - yy) < 0.4), np.array(BRICK[1], float), 0.5)
    # the arched mouth, glowing and breathing with the fire
    mouth = (((np.abs(xs - 12.0) <= 4.6) & (ys >= 18.6) & (ys <= 21.6)) | (np.hypot(xs - 12.0, ys - 18.6) <= 4.6)) & (ys <= 21.6)
    glow = 0.6 + 0.16 * math.sin(2 * math.pi * frame / FLAME_FRAMES)
    _tone_fill(cv, mouth, EMBER, glow + 0.24 * np.clip(1 - np.hypot(xs - 12.0, ys - 21.0) / 7.0, 0, 1))
    cv.over(mouth & edge(mouth), np.array(BRICK[0], float), 0.6)
    loaf, lh = _ell(12.0, 20.4, 2.8, 1.4, n)
    _lit_fill(cv, loaf, ROASTC, lh * 1.6, 0.55, 1.0)
    outline(cv, 0.8)
    return cv.image()


def station_spit(frame=0):
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    bed = (xs >= 3.0) & (xs <= 21.0) & (ys >= 20.4) & (ys <= 22.4)
    _tone_fill(cv, bed, EMBER, 0.2 + 0.3 * fbm(n, n, 3, 5 + frame, 2))
    _flames(cv, n, frame, ((7.0, 4.4, 1.8, 0.1), (11.0, 5.6, 2.2, 0.5), (15.4, 4.6, 1.8, 0.8)), 20.6)
    # two forked posts and the rod across, a crank at the right
    for px in (4.4, 17.6):
        m, h = _rod([(px, 20.4), (px, 12.0)], 0.9, n)
        _lit_fill(cv, m, IRON, h, 0.5, 0.9)
        for sgn in (-1, 1):
            f, _ = _rod([(px, 12.4), (px + sgn * 1.6, 9.6)], 0.8, n)
            _tone_fill(cv, f, IRON, 0.55)
    rod, rh = _rod([(1.8, 10.4), (21.4, 10.4)], 0.65, n)
    _lit_fill(cv, rod, STEEL, rh, 0.62, 0.6)
    crank, _ = _rod([(21.2, 10.4), (22.2, 10.4), (22.2, 14.4)], 0.75, n)
    _tone_fill(cv, crank, STEEL, 0.6)
    # the roast on the rod, glazed
    jm, jh = _ell(11.6, 10.4, 6.4, 4.4, n)
    _lit_fill(cv, jm, ROASTC, jh * 2.6, 0.5, 1.5)
    cv.over(jm & edge(jm), np.array(ROASTC[0], float), 0.5)
    _px(cv, 9.6, 8.2, (255, 214, 160))
    _px(cv, 10.6, 7.8, (255, 230, 190))
    outline(cv, 0.8)
    return cv.image()


# ---------------------------------------------------------------- the four tiers' seals

def _seal(pal, field, pips, star=False):
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    dx, dy = xs - 12.0, ys - 12.0
    d = np.hypot(dx, dy)
    th = np.arctan2(dy, dx)
    outer = d <= 10.3 + 0.5 * np.cos(16 * th)
    lit = -(dx + dy) / np.maximum(d, 1e-6) * math.sqrt(0.5)
    _tone_fill(cv, outer, pal, 0.62 + 0.28 * lit)
    ring_in = d <= 6.9
    _tone_fill(cv, ring_in & ~(d <= 6.0), pal, 0.34 - 0.2 * lit)
    fld = d <= 6.0
    _tone_fill(cv, fld, field, 0.5 + 0.22 * lit + 0.2 * (1 - d / 6.0))
    cv.over(outer & (d > 9.0) & (lit > 0.4), np.array(pal[5], float), 0.5)
    mark = pal[5]
    spots = {1: [(12, 12)], 2: [(8.6, 12), (15.4, 12)], 3: [(12, 8.6), (8.6, 14.6), (15.4, 14.6)], 4: [(12, 12)]}[pips]
    for (px, py) in spots:
        if star:
            for (ox, oy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2), (3, 0), (-3, 0), (0, 3), (0, -3)):
                _px(cv, px + ox - 0.5, py + oy - 0.5, mark if abs(ox) + abs(oy) <= 2 else pal[4])
            for (ox, oy) in ((1, 1), (-1, -1), (1, -1), (-1, 1)):
                _px(cv, px + ox - 0.5, py + oy - 0.5, pal[3])
        else:
            for (ox, oy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                _px(cv, px + ox - 0.5, py + oy - 0.5, mark)
            _px(cv, px + 1.5, py + 1.5, field[0])
    outline(cv, 0.8)
    return cv.image()


FIELD_PEWTER = [(26, 30, 38), (44, 50, 62), (66, 74, 88), (92, 102, 118)]
FIELD_BRONZE = [(44, 22, 12), (82, 44, 22), (120, 66, 32), (158, 94, 46)]
FIELD_SILVER = [(20, 26, 44), (36, 46, 76), (58, 72, 110), (84, 102, 146)]
FIELD_GOLD = [(48, 22, 14), (92, 44, 22), (140, 72, 30), (178, 104, 44)]


def tier_plain():
    return _seal(PEWTER, FIELD_PEWTER, 1)


def tier_sound():
    return _seal(BRONZE, FIELD_BRONZE, 2)


def tier_fine():
    return _seal(SILVER, FIELD_SILVER, 3)


def tier_hallmarked():
    return _seal(GOLD, FIELD_GOLD, 4, star=True)


# ---------------------------------------------------------------- the three hints beside the crosshair (32 x 32, shown at 16 x 16)

H = 32


def hint_pot():
    n = H
    cv = Canvas(n)
    xs, ys = grid(n)
    # a steel pot with a lid, a knob and ears, two curls of steam over it: light, so that it reads against any ground
    t = np.clip((ys - 12.0) / 14.0, 0, 1)
    half = 10.4 * np.sqrt(np.clip(1 - (t * 0.92) ** 2.2, 0, 1))
    body = (ys >= 12.0) & (ys <= 26.0) & (np.abs(xs - 16.0) <= half)
    hh = np.sqrt(np.clip(1 - ((xs - 16.0) / np.maximum(half, 0.5)) ** 2, 0, 1)) * 3.2
    _lit_fill(cv, body, STEEL, hh, 0.58, 1.1)
    _tone_fill(cv, (np.abs(xs - 16.0) <= 11.4) & (ys >= 10.4) & (ys <= 13.2), STEEL, 0.86)
    lid = (((xs - 16.0) / 9.0) ** 2 + ((ys - 10.6) / 3.6) ** 2 <= 1.0) & (ys < 10.6)
    _tone_fill(cv, lid, STEEL, 0.7 + 0.2 * (ys < 8.6))
    _tone_fill(cv, np.hypot(xs - 16.0, ys - 6.2) <= 1.6, STEEL, 0.92)
    for sgn in (-1, 1):
        _tone_fill(cv, (np.abs(xs - (16.0 + sgn * 12.4)) <= 1.7) & (ys >= 12.6) & (ys <= 16.6), STEEL, 0.7)
    for (x0, ph) in ((12.2, 0.0), (19.8, 2.0)):
        pts = [(x0 + 1.2 * math.sin(ph + s * 1.1), 6.6 - s * 0.9) for s in range(0, 3)]
        d, _ = path_dist(pts, n)
        m = (d <= 0.8) & (cv.a < 0.5)
        cv.put(m, np.array((236, 244, 250), float))
        cv.a[m] = 0.9
    outline(cv, 0.84)
    return cv.image()


def hint_flame(frame=0):
    n = H
    cv = Canvas(n)
    a = 2 * math.pi * frame / FLAME_FRAMES
    for (x, hgt, w, ph, sw) in ((16.0, 25.0, 8.6, 0.0, 3.0), (8.8, 12.0, 3.6, 0.35, -2.2), (23.6, 10.0, 3.2, 0.7, 2.4)):
        aa = a + 2 * math.pi * ph
        m, ax, t = flame(x + 0.5 * math.sin(aa), 28.5, hgt * (1.0 + 0.1 * math.sin(aa + 1.3)), w, sw + 1.4 * math.sin(aa + 0.6), n)
        _tone_fill(cv, m, EMBER, 0.34 + 0.34 * ax + 0.12 * (1 - t))
        m2, ax2, _ = flame(x + 0.3 * math.sin(aa), 28.5, hgt * 0.64 * (1.0 + 0.1 * math.sin(aa + 1.3)), w * 0.56, sw * 0.5 + 0.8 * math.sin(aa + 0.6), n)
        _tone_fill(cv, m2, EMBER, 0.66 + 0.26 * ax2)
        m3, _, _ = flame(x, 28.5, hgt * 0.32, w * 0.3, 0.0, n)
        _tone_fill(cv, m3, EMBER, 0.95)
    outline(cv, 0.84)
    return cv.image()


def hint_chain():
    n = H
    cv = Canvas(n)
    xs, ys = grid(n)
    # three links hung one from the next: end-on, edge-on, end-on
    for (cx, cy, rx, ry, thin) in ((16.0, 7.6, 4.8, 6.4, False), (16.0, 15.6, 1.9, 6.4, True), (16.0, 23.6, 4.8, 6.4, False)):
        d = np.hypot((xs - cx) / rx, (ys - cy) / ry)
        if thin:
            m = d <= 1.0
            _lit_fill(cv, m, STEEL, np.sqrt(np.clip(1 - d * d, 0, 1)) * 2.0, 0.6, 0.9)
        else:
            m = (d <= 1.0) & (d >= 0.52)
            _lit_fill(cv, m, STEEL, np.sqrt(np.clip(1 - ((d - 0.76) / 0.24) ** 2, 0, 1)) * 2.4, 0.6, 1.1)
    outline(cv, 0.84)
    return cv.image()


# ---------------------------------------------------------------- the Book: page, ribbon, lock

def book_page():
    """The page: 96 x 128 texture, a nine-slice of 48 x 64 GUI pixels with an 8 pixel border. Parchment that darkens toward its edges, a double
    rule of ink inside the border and a small curl in each corner."""
    W, Hh = 96, 128
    xs, ys = grid(W, Hh)
    cv = Canvas(W, Hh)
    grain = fbm(W, Hh, 9, 31, 4)
    fine = rand(W, Hh, 32)
    dd = np.minimum.reduce([xs, W - xs, ys, Hh - ys])
    vig = np.clip(dd / 14.0, 0, 1)
    t = 0.74 + 0.12 * (grain - 0.5) + 0.05 * (fine - 0.5) - 0.34 * (1 - vig) ** 1.5
    cv.put(np.ones((Hh, W), bool), bands(PARCH, t, 0.2))
    cv.a[:] = 1.0
    cv.over(dd < 1.0, np.array(PARCH[0], float), 1.0)
    cv.over((dd >= 1.0) & (dd < 2.0), np.array(PARCH[1], float), 0.8)
    cv.over((ys > Hh - 3.0) | (xs > W - 3.0), np.array(PARCH[0], float), 0.45)
    ink = np.array((92, 64, 38), float)
    for off, al in ((12.0, 0.8), (14.0, 0.5)):
        cv.over(np.abs(dd - off) < 0.55, ink, al)
    for (cx, cy, sx, sy) in ((12.0, 12.0, 1, 1), (W - 12.0, 12.0, -1, 1), (12.0, Hh - 12.0, 1, -1), (W - 12.0, Hh - 12.0, -1, -1)):
        d = np.hypot(xs - cx, ys - cy)
        for r, al in ((4.6, 0.8), (7.0, 0.5)):
            arc = (np.abs(d - r) < 0.55) & ((xs - cx) * sx >= -0.5) & ((ys - cy) * sy >= -0.5)
            cv.over(arc, ink, al)
        cv.over(np.hypot(xs - cx - 2.0 * sx, ys - cy - 2.0 * sy) < 1.3, ink, 0.8)
    return cv.image()


def book_ribbon():
    """The bookmark ribbon: 20 x 64, crimson, a lit edge, a woven line down its middle, a V cut in its end."""
    W, Hh = 20, 64
    xs, ys = grid(W, Hh)
    cv = Canvas(W, Hh)
    body = (xs >= 2.0) & (xs <= W - 2.0)
    cut = (ys > Hh - 9.0) & (np.abs(xs - W / 2.0) < (ys - (Hh - 9.0)) * (W / 2.0 - 2.0) / 9.0)
    body &= ~cut
    lit = np.clip(1 - (xs - 2.0) / (W - 4.0), 0, 1)
    tt = 0.66 + 0.5 * (lit - 0.5) + 0.06 * (rand(W, Hh, 5) - 0.5)
    _tone_fill(cv, body, [(40, 8, 14), (92, 16, 28), (150, 28, 40), (196, 44, 56), (228, 78, 80), (250, 130, 124)], tt)
    cv.over(body & (np.abs(xs - W / 2.0) < 0.6) & (np.mod(ys, 3.0) < 1.5), np.array((250, 150, 140), float), 0.7)
    cv.over(body & (np.abs(xs - 3.5) < 0.5), np.array((40, 8, 14), float), 0.35)
    cv.over(body & (np.abs(xs - (W - 3.5)) < 0.5), np.array((40, 8, 14), float), 0.35)
    outline(cv, 0.8)
    return cv.image()


def book_lock():
    n = G
    cv = Canvas(n)
    xs, ys = grid(n)
    shackle = [(8.2, 11.0), (8.2, 7.4), (12.0, 3.4), (15.8, 7.4), (15.8, 11.0)]
    sh, shh = _rod(shackle, 1.6, n)
    _lit_fill(cv, sh, STEEL, shh * 2.0, 0.55, 1.0)
    body = (xs >= 4.6) & (xs <= 19.4) & (ys >= 10.4) & (ys <= 21.4)
    body &= ~(((xs < 6.2) | (xs > 17.8)) & (ys > 19.8) & (np.hypot(np.minimum(np.abs(xs - 4.6), np.abs(xs - 19.4)) - 1.6, ys - 19.8) > 1.6))
    bh = np.minimum.reduce([xs - 4.6, 19.4 - xs, ys - 10.4, 21.4 - ys]).clip(0, 3.0)
    _lit_fill(cv, body, GOLD, bh, 0.58, 1.2)
    cv.over(body & edge(body), np.array(GOLD[1], float), 0.5)
    key = (np.hypot(xs - 12.0, ys - 14.6) <= 1.7) | ((np.abs(xs - 12.0) <= 0.9) & (ys >= 14.6) & (ys <= 18.4))
    cv.put(key & body, np.array(GOLD[0], float))
    outline(cv, 0.8)
    return cv.image()


STATIC = {**ROLES, 'tier_plain': tier_plain, 'tier_sound': tier_sound, 'tier_fine': tier_fine,
          'hint_pot': hint_pot, 'hint_chain': hint_chain, 'book_page': book_page, 'book_ribbon': book_ribbon, 'book_lock': book_lock}
FLAMES = {'station_stewpot': station_stewpot, 'station_hob': station_hob, 'station_oven': station_oven, 'station_spit': station_spit,
          'hint_flame': hint_flame}
NAMES = list(STATIC) + list(FLAMES) + ['tier_hallmarked']


def frames(name):
    """The sprite as a list of frames: one for a still sprite; the flames' four; the Hallmarked seal's glint (the plain seal, then a band of
    light and a sparkle crossing it)."""
    if name in STATIC:
        return [STATIC[name]()]
    if name in FLAMES:
        return [FLAMES[name](f) for f in range(FLAME_FRAMES)]
    if name == 'tier_hallmarked':
        return sweep(tier_hallmarked(), -2.0, 30.0, 9, 2.0, star=(5, 5))
    raise KeyError(name)


def times(name):
    """How long each frame of [frames] lasts, in ticks (empty for a still sprite)."""
    if name in FLAMES:
        return [3] * FLAME_FRAMES
    if name == 'tier_hallmarked':
        return [70] + [2] * 8
    return []


# a sprite's .mcmeta: the page and the ribbon scale, the rest are drawn as they are
SCALING = {'book_page': {'type': 'nine_slice', 'width': 48, 'height': 64, 'border': 8}}


PAINTERS = {name: (lambda n=name: frames(n)[0]) for name in NAMES}


def write(assets):
    """Every sprite into textures/gui/sprites/cook/<name>.png: a moving one as a strip of its frames with the .mcmeta that times them, the
    Book's page with the nine-slice that stretches it."""
    d = os.path.join(assets, 'textures/gui/sprites/cook')
    os.makedirs(d, exist_ok=True)
    for name in NAMES:
        fr = frames(name)
        w, h = fr[0].size
        img = fr[0]
        meta = None
        if len(fr) > 1:
            img = Image.new('RGBA', (w, h * len(fr)), (0, 0, 0, 0))
            for i, f in enumerate(fr):
                img.paste(f, (0, h * i))
            meta = mcmeta(times(name))
        elif name in SCALING:
            meta = {'gui': {'scaling': SCALING[name]}}
        img.save(os.path.join(d, name + '.png'))
        if meta:
            with open(os.path.join(d, name + '.png.mcmeta'), 'w') as f:
                json.dump(meta, f, indent=2)
                f.write('\n')
