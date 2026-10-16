"""The sea monsters' four item icons, 32x32 HD in the repo's item style (hdkit.py: banded material tones, a dark outline round
the shape, light from the upper left): the Gnashfin's needle fang, its raw and its seared meat, and the Maelgrasp's beak.

    python3 -B art/scripts/sea_items.py src/main/resources/assets/guildmark [preview dir]

Each icon is written to textures/item/<name>.png with the two small JSON files that point at it (models/item/<name>.json, a plain
`item/generated` model, and items/<name>.json). Everything is seeded, so a rerun writes the same bytes. Only numpy and Pillow.

With a preview folder it also writes `sea_items.png` (the four at 8x) and `sea_items.gif`: the four as things dropped on a shelf, turning, bobbing,
glinting, the seared cut steaming and the raw one dripping.
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, Canvas, grid, vnoise, fbm, rand, tone, mix, bands, poly, path_dist, disc, ellipse,
                   blur, edge, grow, dist_in, light, outline, glint, sheet)

N = 32

ITEMS = ['gnashfin_fang', 'raw_gnashfin', 'cooked_gnashfin', 'maelgrasp_beak']

# Tones, darkest first, hue-shifted the way hdkit's own materials are: the dark ends cool, the light ends warm
FANG = [(22, 26, 40), (50, 58, 78), (92, 106, 126), (146, 162, 174), (200, 214, 216), (242, 248, 238)]
GUM = [(24, 20, 34), (44, 36, 58), (72, 60, 90), (106, 92, 122), (140, 124, 150)]
FLESH = [(26, 38, 58), (42, 60, 86), (62, 86, 114), (90, 118, 146), (126, 152, 172), (172, 192, 204)]
SKIN = [(10, 14, 24), (20, 28, 42), (34, 46, 62), (52, 66, 84), (78, 94, 110), (116, 132, 144)]
FAT = [(96, 108, 122), (140, 154, 164), (186, 198, 204), (226, 234, 230)]
BLOOD = [(36, 14, 30), (64, 22, 44), (98, 36, 60), (132, 56, 78)]
SEARED = [(22, 13, 12), (50, 28, 20), (94, 56, 30), (142, 92, 46), (188, 138, 74), (226, 184, 116)]
COOKED = [(64, 52, 54), (108, 88, 76), (150, 124, 98), (190, 164, 128), (224, 204, 168), (244, 232, 202)]
CHITIN = [(6, 6, 10), (14, 14, 22), (26, 26, 40), (44, 44, 64), (74, 76, 104), (124, 128, 162)]
AMBER = [(60, 30, 14), (112, 62, 26), (168, 104, 44), (214, 156, 78)]
BRUISE = [(24, 18, 30), (46, 34, 54), (74, 56, 82), (106, 84, 110), (140, 116, 138)]


# ================================================================ helpers

def bez(pts, n=24):
    """Points along a Bezier curve through any number of control points (de Casteljau)."""
    out = []
    for i in range(n + 1):
        t = i / n
        p = [np.array(q, float) for q in pts]
        while len(p) > 1:
            p = [p[k] * (1 - t) + p[k + 1] * t for k in range(len(p) - 1)]
        out.append((float(p[0][0]), float(p[0][1])))
    return out


def curve_field(pts, w, samples=320):
    """For every pixel centre: the distance to a Bezier curve, the parameter t (0..1) of the nearest point, the signed cross position
    (positive toward the left of the travel direction, in the same units as the distance) and that left-hand unit normal."""
    xs, ys = grid(w)
    ts = np.linspace(0.0, 1.0, samples)
    P = [np.array(q, float) for q in pts]
    n = len(P) - 1
    bx = np.zeros(samples)
    by = np.zeros(samples)
    from math import comb
    for i, p in enumerate(P):
        k = comb(n, i) * ts ** i * (1 - ts) ** (n - i)
        bx += k * p[0]
        by += k * p[1]
    tx = np.gradient(bx)
    ty = np.gradient(by)
    tl = np.hypot(tx, ty)
    tx, ty = tx / tl, ty / tl
    best = np.full(xs.shape, 1e9)
    idx = np.zeros(xs.shape, int)
    for i in range(samples):
        d = (xs - bx[i]) ** 2 + (ys - by[i]) ** 2
        c = d < best
        best = np.where(c, d, best)
        idx = np.where(c, i, idx)
    dist = np.sqrt(best)
    nx, ny = -ty[idx], tx[idx]
    cross = (xs - bx[idx]) * nx + (ys - by[idx]) * ny
    return dist, ts[idx], cross, nx, ny, ts, bx, by


def shade(nx, ny, nz):
    """Lambert light of the kit's upper-left light on a unit normal (arrays)."""
    L = np.array([-0.58, -0.64, 0.5])
    L = L / np.linalg.norm(L)
    return nx * L[0] + ny * L[1] + nz * L[2]


def ragged(mask, seed, amount=0.5):
    """The mask with its border chewed: pixels on the edge are dropped where noise says so."""
    n = rand(N, N, seed)
    return mask & ~(edge(mask) & (n < amount))


# ================================================================ the fang

def gnashfin_fang():
    """A long curved needle fang: ivory enamel that darkens toward the root, a groove down its inner curve, growth rings, a torn bit of
    slate gum at the root and a hard glint on the point."""
    xs, ys = grid(N)
    pts = [(8.2, 28.0), (10.2, 12.8), (26.8, 4.4)]
    dist, t, cross, nx, ny, ts, bx, by = curve_field(pts, N)
    rad = np.maximum(0.72, 0.3 + 3.25 * (1 - t) ** 1.05)
    rad = np.where(t > 0.95, np.maximum(0.42, 0.72 - (t - 0.95) * 6.0), rad)
    # a root that flares a little where it sits in the jaw
    rad = rad + 0.55 * np.exp(-((t - 0.02) / 0.07) ** 2)
    body = dist <= rad
    c = np.clip(cross / np.maximum(rad, 0.5), -1, 1)
    nz = np.sqrt(np.clip(1 - c * c, 0, 1))
    lam = shade(c * nx, c * ny, nz) - 0.5
    grain = fbm(N, N, 4, 11)
    # Growth rings across the fang, every few pixels along its length
    arc = np.zeros_like(ts)
    arc[1:] = np.cumsum(np.hypot(np.diff(bx), np.diff(by)))
    along = np.interp(t, ts, arc)
    ring = ((along % 3.6) < 0.85) & (t > 0.18) & (t < 0.9)
    v = 0.62 + 0.5 * lam + 0.08 * (grain - 0.5) - 0.07 * ring
    # the enamel is dingier near the root and clean toward the point
    v = v - 0.22 * np.clip((0.42 - t) / 0.42, 0, 1)
    cv = Canvas(N)
    cv.put(body, bands(FANG, v, 0.3))
    # a thin groove runs down the inner curve like a drinking straw, with a thread of pale glow in it
    groove = body & (np.abs(c - 0.36) < 0.2) & (t > 0.2) & (t < 0.84)
    cv.put(groove, bands(FANG, v - 0.3, 0))
    wet = groove & (t > 0.3) & (t < 0.78) & ((along % 3.6) < 1.7)
    cv.over(wet, (96, 224, 196), 0.42)
    # the lit edge: a hard thin line of light along the outside of the curve
    edge_lit = body & (c < -0.5) & (c > -0.86) & (t > 0.08) & (t < 0.93) & (lam > -0.3)
    cv.put(edge_lit, tone(FANG[4], 0.25))
    # the root: a pale bulb of dentin with the dark pulp showing at its cut end, and two torn scraps of the gum that held it
    root = body & (t < 0.13)
    rt = 0.55 + 0.8 * lam + 0.16 * (grain - 0.5) - 0.12 * np.clip((t - 0.06) / 0.07, 0, 1)
    cv.put(root, bands(PAL['bone'], rt, 0.3))
    endcap = body & (t < 0.035) & (cross > -3.2)
    cv.put(endcap, PAL['bone'][1])
    pulp, _ = ellipse(8.2, 27.9, 2.1, 1.0, N)
    cv.put(pulp & endcap, (30, 20, 34))
    jag = vnoise(N, N, 2.2, 5)
    for (gx, gy, grx, gry) in ((6.0, 25.6, 1.9, 2.6), (10.6, 26.0, 1.6, 2.2)):
        gm = ellipse(gx, gy, grx, gry, N)[0] & body & (jag > 0.3)
        gl = light(blur(gm.astype(float), 1) * 2.2, 1.0)
        cv.put(gm, bands(GUM, 0.5 + 0.8 * gl + 0.2 * (grain - 0.5), 0.3))
    seam = body & (np.abs(t - 0.13) < 0.018)
    cv.over(seam, PAL['bone'][0], 0.55)
    outline(cv, 0.82)
    # the point, and a glint along the blade
    cv.px(int(bx[-1]), int(by[-1]), FANG[5])
    glint(cv, 13, 15, 1, (236, 248, 244))
    return cv.image()


# ================================================================ the meat

PLATE = [(18, 26, 38), (38, 54, 70), (66, 88, 104), (104, 128, 142), (156, 178, 186)]


def _egg(cx, cy, rx, ry, w, back=0.1):
    """An egg shape (the back a little narrower than the belly): the mask, and the unit coordinates (u, v, r)."""
    xs, ys = grid(w)
    u = (xs - cx) / rx
    v = (ys - cy) / ry
    uu = u / (1 + back * v)
    r = np.hypot(uu, v)
    return r <= 1, uu, v, r


def _steak(cooked):
    """The thick cut, raw or seared, as a Canvas: a slice stood on its edge and turned toward the viewer. The cut face holds a backbone
    high in it and nested pale seams either side, and is rimmed with the Gnashfin's bony scutes; the thickness shows as a darker wall of
    armoured skin on the lower right, away from the light."""
    xs, ys = grid(N)
    cx, cy, rx, ry = 14.4, 14.2, 11.2, 10.2
    depth = 3.8
    top, uu, vv, r = _egg(cx, cy, rx, ry, N, back=0.07)
    swept = top.copy()
    for k in np.arange(0.5, depth + 0.01, 0.5):
        swept |= _egg(cx + 0.74 * k, cy + 0.68 * k, rx, ry, N, back=0.07)[0]
    wall = swept & ~top
    cv = Canvas(N)
    g = fbm(N, N, 4, 7)
    g2 = vnoise(N, N, 1.6, 8)
    # how far toward the light a pixel lies: 1 at the upper left of the slice, 0 at the lower right
    ud = np.clip(0.5 - 0.5 * ((xs - cx) * 0.7 + (ys - cy) * 0.7) / 10.0, 0, 1)
    # ------ the wall: slate skin in upright scutes, darker toward the bottom
    along = (xs - cx) * 0.68 + (ys - cy) * 0.74
    groove = ((along + 40.0) % 3.4) < 0.8
    wt = 0.44 - 0.2 * np.clip(((xs - cx) + (ys - cy)) / 24.0, 0, 1) + 0.1 * (g - 0.5) - 0.2 * groove
    if cooked:
        cv.put(wall, bands(SEARED, wt * 0.85 + 0.1, 0.3))
        cv.over(wall & groove, SKIN[0], 0.55)
        cv.over(wall & (rand(N, N, 17) > 0.88), SEARED[4], 0.5)
    else:
        cv.put(wall, bands(SKIN, wt + 0.1, 0.3))
        cv.over(wall & ~groove & (rand(N, N, 17) > 0.84), FANG[3], 0.3)
    # ------ the cut face
    ring = top & (r > 0.8)
    flesh = top & (r <= 0.8)
    sx, sy = cx, cy - 2.2                           # the backbone sits high, above the middle
    dx_, dy_ = xs - sx, ys - sy
    rho = np.hypot(dx_, dy_)
    ang = np.arctan2(dy_, np.abs(dx_))
    # nested seams either side of the backbone, like the brackets on a cut salmon, and the lateral line across
    seam = np.zeros((N, N), bool)
    for k, R in enumerate((4.8, 7.8)):
        seam |= (np.abs(rho - R) < 0.5) & (np.abs(ang) < 0.95 - 0.1 * k) & (np.abs(dx_) > 2.6)
    seam &= flesh
    # four lobes of muscle, each a touch brighter in its upper left, so the face has a gentle swell
    swell = np.zeros((N, N))
    for (lx, ly, lrx, lry) in ((-5.4, -2.6, 4.4, 3.2), (5.4, -2.6, 4.4, 3.2), (-5.2, 4.2, 4.4, 3.0), (5.2, 4.2, 4.4, 3.0)):
        d = np.hypot((dx_ - lx) / lrx, (dy_ - ly - 2.2) / lry)
        swell = np.maximum(swell, np.clip(1 - d, 0, 1))
    swell_lit = light(blur(swell, 1) * 2.4, 1.0)
    # the rim of bony scutes round the face: little plates with dark joints
    nsc = 13
    seg = (np.arctan2(vv, uu) + math.pi) / math.tau * nsc
    fr = seg - np.floor(seg)
    joint = (fr < 0.2) | (fr > 0.93)
    edge_ring = top & (r > 0.93)
    if not cooked:
        ft = 0.3 + 0.36 * ud + 0.5 * swell_lit + 0.08 * (g - 0.5) * 2 + 0.05 * (g2 - 0.5)
        cv.put(flesh, bands(FLESH, ft, 0.3))
        cv.put(ring, bands(PLATE, 0.4 + 0.55 * ud + 0.2 * (g - 0.5), 0.25))
        cv.put(ring & joint, PLATE[0])
        cv.put(edge_ring, bands(SKIN, 0.3 + 0.4 * ud, 0.2))
        cv.put(seam, bands(FAT, 0.34 + 0.46 * ud + 0.2 * (g2 - 0.5), 0.3))
        # the blood line beneath the backbone, dark violet
        cv.put(flesh & (np.abs(dx_) < 0.6) & (dy_ > 2.4) & (dy_ < 5.4), bands(BLOOD, 0.5 + 0.5 * (g2 - 0.5), 0))
        for (gx, gy) in ((7, 9), (10, 6), (19, 10)):
            if flesh[gy, gx]:
                cv.px(gx, gy, (222, 236, 240))
    else:
        ft = 0.34 + 0.34 * ud + 0.5 * swell_lit + 0.08 * (g - 0.5) * 2 + 0.05 * (g2 - 0.5)
        edge_b = np.clip((r - 0.5) / 0.3, 0, 1)
        flesh_col = bands(COOKED, ft, 0.3)
        brown = bands(SEARED, 0.4 + 0.4 * ud + 0.3 * swell_lit + 0.14 * (g - 0.5) - 0.1 * edge_b, 0.3)
        cv.put(flesh, mix(flesh_col, brown, np.clip(edge_b * 1.2, 0, 1) ** 1.2))
        cv.put(ring, bands(SEARED, 0.34 + 0.5 * ud + 0.2 * (g - 0.5), 0.25))
        cv.put(ring & joint, SEARED[0])
        cv.put(edge_ring, bands(SKIN, 0.2 + 0.4 * ud, 0.2))
        cv.over(seam, COOKED[5], 0.5)
        # char: three bars of grill, as if pressed on an iron
        for off in (-5.4, 0.2, 5.8):
            dline = np.abs((xs - cx) * 0.72 + (ys - cy) * 0.69 - off)
            bar = flesh & (dline < 1.3) & (r < 0.8)
            bt = 0.16 + 0.2 * (g2 - 0.5) + 0.3 * (dline > 0.75) + 0.2 * ud * (dline > 0.75)
            cv.put(bar, bands(SEARED, bt, 0.2))
        cv.over(flesh & (rand(N, N, 23) > 0.93), SEARED[1], 0.6)
        for (gx, gy) in ((7, 9), (10, 6), (19, 10)):
            if flesh[gy, gx]:
                cv.px(gx, gy, (255, 236, 196))
    # the backbone: a ring of bone with a dark marrow and short spines above and below
    vr = np.hypot(dx_, dy_)
    bone = flesh & (vr < 2.6)
    spine_up = flesh & (np.abs(dx_) < 0.8 * np.clip(-dy_ / 3.2, 0.2, 1) + 0.15) & (dy_ < 0) & (dy_ > -4.2)
    spine_dn = flesh & (np.abs(dx_) < 0.65) & (dy_ > 0) & (dy_ < 3.8)
    bt2 = 0.5 + 0.7 * light(blur(bone.astype(float), 1) * 3, 1.0)
    cv.put(bone | spine_up | spine_dn, bands(PAL['bone'], bt2, 0.2))
    cv.put(vr < 1.05, PAL['bone'][0] if cooked else (52, 34, 58))
    cv.over(bone & (vr > 2.1), tone(PAL['bone'][1], -0.2), 0.9)
    cut = top & grow(wall) & ~ring
    cv.put(cut, tone(PLATE[3], 0.1) if not cooked else tone(SEARED[4], 0.1))
    outline(cv, 0.82)
    return cv


def raw_gnashfin():
    """A thick cut of the Gnashfin: slate-blue flesh in nested seams round the backbone, a pale fat ring, the armoured skin round it."""
    return _steak(False).image()


def cooked_gnashfin():
    """The same cut seared: golden-brown flesh charred in three grill bars, the skin crisped, the slate-blue left only in the armour."""
    return _steak(True).image()


# ================================================================ the beak

def _poly_curve(*curves):
    """One outline from several Bezier pieces, each a list of control points; the pieces join end to end."""
    out = []
    for c in curves:
        pts = bez(c, 28)
        out.extend(pts if not out else pts[1:])
    return out


def maelgrasp_beak():
    """The Maelgrasp's beak in profile, open: a hooked upper mandible and a shorter blunt lower one in black laminated chitin, a dark mouth
    between them, amber on the cutting edges, a ragged scrap of bruise-purple mantle clinging to the back and a drop of ink at the gape."""
    xs, ys = grid(N)
    cv = Canvas(N)
    upper = _poly_curve([(3.2, 23.4), (2.0, 13.0), (7.5, 4.4), (16.0, 3.2)],
                        [(16.0, 3.2), (20.0, 2.4), (24.0, 3.0), (26.6, 5.4)],
                        [(26.6, 5.4), (28.6, 9.0), (27.8, 13.4), (24.6, 16.2)],
                        [(24.6, 16.2), (18.0, 16.6), (10.5, 17.6), (7.0, 21.0)],
                        [(7.0, 21.0), (5.0, 22.4), (3.2, 23.4)])
    lower = _poly_curve([(8.0, 27.8), (10.6, 24.4), (16.0, 21.6), (24.6, 19.8)],
                        [(24.6, 19.8), (28.6, 22.0), (28.4, 26.8), (23.0, 29.6)],
                        [(23.0, 29.6), (16.5, 30.2), (11.0, 30.0), (8.0, 27.8)])
    um = poly(upper, N)
    lm = poly(lower, N)
    g = fbm(N, N, 4, 31)
    ridge_n = vnoise(N, N, 2, 41)

    def paint(mask, base_shift):
        h = dist_in(mask, 7)
        hh = np.sqrt(np.clip(h / 7.0, 0, 1)) * 6.0
        lt = light(hh, 1.5)
        ridge = ((h + ridge_n * 0.9) % 2.8) < 0.75
        t = 0.44 + 0.7 * lt + 0.07 * (g - 0.5) + base_shift
        t = t + np.where(ridge & (h > 1.5), 0.06, 0.0)
        cv.put(mask, bands(CHITIN, t, 0.3))
        return h, lt

    # the mouth between the mandibles: dark, a pale tongue in it, open to the front
    mouth = poly([(24.6, 16.2), (18.0, 16.6), (13.0, 17.6), (13.0, 22.4), (16.0, 21.6), (24.6, 19.8)], N) & ~um & ~lm
    cv.put(mouth, (10, 6, 16))
    cv.put(mouth & (xs > 16.5) & (xs < 23.5) & (ys > 18.2), BRUISE[2])
    cv.put(mouth & (xs > 17.5) & (xs < 22.5) & (ys > 18.2) & (ys < 19.2), BRUISE[3])
    hl, ll = paint(lm, -0.08)
    hu, lu = paint(um, 0.0)
    # a hard specular streak down the back of the hook and a second sheen further in
    spec = um & (hu >= 1.5) & (hu < 3) & (lu > 0.2)
    cv.put(spec, tone(CHITIN[5], 0.15))
    cv.over(um & (hu >= 3) & (hu <= 4.4) & (lu > 0.05), CHITIN[4], 0.5)
    cv.put(lm & (hl >= 1.5) & (hl < 2.6) & (ll > 0.15), CHITIN[4])
    # amber along the cutting edges, where the black wears thin, brightest at the points
    du, _ = path_dist(bez([(24.6, 16.2), (18.0, 16.6), (10.5, 17.6), (7.0, 21.0)], 20) + [(5.0, 22.4), (3.2, 23.4)], N)
    dl, _ = path_dist(bez([(8.0, 27.8), (10.6, 24.4), (16.0, 21.6), (24.6, 19.8)], 20), N)
    cv.put(um & (du < 1.35) & (xs < 25), bands(AMBER, 0.4 + 0.5 * lu + 0.2 * (du < 0.8), 0.2))
    cv.put(lm & (dl < 1.35) & (xs < 25), bands(AMBER, 0.34 + 0.5 * ll + 0.2 * (dl < 0.8), 0.2))
    cv.put(um & (np.hypot(xs - 3.8, ys - 22.6) < 2.2), AMBER[3])
    # the mantle's torn scrap on the back: bruise-purple muscle with two pale suckers
    edge_x = 25.2 + 1.6 * (vnoise(N, N, 3.0, 51) - 0.5) * 2.0 + 0.9 * np.sin(ys * 0.9)
    scrap = (um | lm | mouth) & (xs > edge_x)
    sh = blur(scrap.astype(float), 1) * 2.4
    cv.put(scrap, bands(BRUISE, 0.5 + 0.7 * light(sh, 1.0) + 0.16 * (g - 0.5), 0.3))
    for (sx, sy, rr) in ((27.4, 9.4, 1.6), (27.6, 24.6, 1.5)):
        m, dm = disc(sx, sy, rr, N)
        cv.put(m & scrap, bands(BRUISE, 0.84 - 0.3 * dm, 0))
        cv.put((np.hypot(xs - sx, ys - sy) < rr * 0.5) & scrap, BRUISE[0])
    # a drop of ink hanging from the hook's inner edge into the mouth
    for (dx, dy) in ((12, 18), (12, 19), (12, 20)):
        cv.px(dx, dy, (22, 24, 56))
    cv.px(11, 21, (22, 24, 56))
    cv.px(12, 21, (30, 34, 78))
    cv.px(13, 21, (22, 24, 56))
    cv.px(12, 22, (22, 24, 56))
    cv.px(12, 20, (96, 104, 170))
    outline(cv, 0.86)
    glint(cv, 11, 6, 1, (186, 190, 230))
    return cv.image()


# ================================================================ writing

def build():
    return {'gnashfin_fang': gnashfin_fang(), 'raw_gnashfin': raw_gnashfin(), 'cooked_gnashfin': cooked_gnashfin(), 'maelgrasp_beak': maelgrasp_beak()}


def _write_json(path, doc):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(doc, f, indent=2)
        f.write('\n')


# ================================================================ the preview: the icons as things dropped on a shelf

# where each icon sparkles, in texels (the fang's point, the beak's amber tip, the wet shine on the two cuts)
SPARKLE = {'gnashfin_fang': (26.0, 4.5), 'raw_gnashfin': (10.0, 6.0), 'cooked_gnashfin': (10.0, 6.0), 'maelgrasp_beak': (3.5, 21.0)}
SPARKLE_COL = {'gnashfin_fang': (150, 255, 236), 'raw_gnashfin': (214, 240, 255), 'cooked_gnashfin': (255, 228, 176), 'maelgrasp_beak': (255, 190, 96)}


def _cross(canvas, cx, cy, size, a, col):
    """Adds a four-point sparkle (arms [size] px long, strength [a]) centred on (cx, cy) to the float image [canvas] (h, w, 3)."""
    h, w = canvas.shape[:2]
    x0, x1 = max(0, int(cx - size) - 1), min(w, int(cx + size) + 2)
    y0, y1 = max(0, int(cy - size) - 1), min(h, int(cy + size) + 2)
    if x0 >= x1 or y0 >= y1:
        return
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    dx, dy = np.abs(xs - cx), np.abs(ys - cy)
    arms = np.exp(-np.minimum(dx, dy) ** 2 / 0.7) * np.clip(1.0 - np.maximum(dx, dy) / size, 0.0, 1.0) ** 1.5
    core = np.exp(-(dx * dx + dy * dy) / (0.08 * size * size))
    canvas[y0:y1, x0:x1] += a * np.minimum(arms + core, 1.0)[..., None] * np.asarray(col, np.float32)


def _puff(canvas, cx, cy, r, a, col):
    """Adds a soft round puff (radius [r], strength [a]) to the float image [canvas]."""
    h, w = canvas.shape[:2]
    x0, x1 = max(0, int(cx - 2.5 * r)), min(w, int(cx + 2.5 * r) + 1)
    y0, y1 = max(0, int(cy - 2.5 * r)), min(h, int(cy + 2.5 * r) + 1)
    if x0 >= x1 or y0 >= y1:
        return
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    k = np.exp(-((xs - cx) ** 2 + (ys - cy) ** 2) / (2.0 * r * r))
    canvas[y0:y1, x0:x1] = canvas[y0:y1, x0:x1] * (1.0 - a * k[..., None]) + a * k[..., None] * np.asarray(col, np.float32)


def animate(imgs, path, frames=60, scale=3, ms=80):
    """The four icons as things dropped on a shelf, a looping GIF: each turns (the cut sprite shows its thickness as it comes edge on, as an item in the
    game does), bobs, and catches a glint as it faces the front; the fang's point and the beak's amber tip sparkle, the seared cut steams, the raw one
    drips onto the shelf, and motes drift up through the light. Only Pillow and numpy; deterministic."""
    from PIL import Image, ImageDraw
    n = len(ITEMS)
    S = N * scale
    slot = S + 18
    W, H = slot * n, 192
    cy0, shelf = 76.0, 142.0
    layers = 6                                               # the sprite's thickness, in slices (1/16 block: 6 px at this size)
    thick = 6.0
    rng = np.random.default_rng(31)
    xs_ = np.arange(W, dtype=np.float32)[None, :, None]
    ys_ = np.arange(H, dtype=np.float32)[:, None, None]
    # ---- the still part: a dusk that darkens toward a dark shelf, a pool of light under each icon, the names
    bg = np.zeros((H, W, 3), np.float32)
    v = np.clip(ys_ / shelf, 0.0, 1.0)
    bg[:] = np.asarray((40, 44, 80), np.float32) * (1.0 - v) + np.asarray((14, 16, 32), np.float32) * v
    centres = [slot * (i + 0.5) for i in range(n)]
    for cx in centres:
        pool = np.exp(-((xs_ - cx) ** 2 / (2 * 62.0 ** 2) + (ys_ - 84.0) ** 2 / (2 * 62.0 ** 2)))
        bg += pool * np.asarray((34, 34, 46), np.float32)
    shelf_rows = (ys_ >= shelf).astype(np.float32)
    bg = bg * (1.0 - shelf_rows) + shelf_rows * (np.asarray((22, 20, 30), np.float32) * (1.0 - 0.5 * np.clip((ys_ - shelf) / (H - shelf), 0, 1)))
    bg[int(shelf):int(shelf) + 2] += np.asarray((22, 24, 34), np.float32)
    for cx in centres:
        pool = np.exp(-((xs_ - cx) ** 2 / (2 * 56.0 ** 2) + (ys_ - shelf - 10.0) ** 2 / (2 * 14.0 ** 2)))
        bg += shelf_rows * pool * np.asarray((26, 26, 34), np.float32)
    base = Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(base)
    for name, cx in zip(ITEMS, centres):
        d.text((cx - 3.1 * len(name), H - 19), name, fill=(170, 176, 196))
    bg = np.asarray(base, np.float32)
    # ---- the sprites, enlarged without blur; each has its own phase and moments
    big = [np.asarray(imgs[name].convert('RGBA').resize((S, S), Image.NEAREST), np.float32) for name in ITEMS]
    phi = [i * math.pi / 2.0 for i in range(n)]
    face_at = [(-p / math.tau * frames) % frames for p in phi]                # the frame each one faces the front at
    # ---- motes in the light: a closed loop each (they rise a whole number of turns of the shelf's height in the loop)
    motes = [(rng.uniform(8, W - 8), rng.uniform(0, shelf - 10), int(rng.integers(1, 3)), rng.uniform(0, math.tau), int(rng.integers(1, 4)), rng.uniform(0.6, 1.5))
             for _ in range(30)]
    rise_h = shelf - 14.0
    out = []
    for f in range(frames):
        img = bg.copy()
        u = f / frames
        # motes
        for (mx, my, k, ph, m, r) in motes:
            y = (my - k * rise_h * u) % rise_h + 6.0
            x = mx + 5.0 * math.sin(math.tau * m * u + ph)
            tw = 0.5 + 0.5 * math.sin(math.tau * (m + 1) * u + ph * 2.0)
            _puff(img, x, y, r, 0.35 * tw * min(1.0, y / 30.0), (190, 200, 230))
        sprites = []
        for i, name in enumerate(ITEMS):
            cx = centres[i]
            theta = math.tau * u + phi[i]
            c, s_ = math.cos(theta), math.sin(theta)
            bob = 4.5 * math.sin(math.tau * u + 1.1 * i)
            cy = cy0 + bob
            # the shadow on the shelf: smaller and fainter as it rises
            hgt = (shelf - cy0 - S / 2) - bob
            sh_r = 30.0 - 0.18 * hgt + 2.0 * abs(s_)
            ys, xs = np.mgrid[int(shelf) + 2:int(shelf) + 30, int(cx - 44):int(cx + 45)].astype(np.float32)
            sh = np.exp(-(((xs - cx) / sh_r) ** 2 + ((ys - shelf - 14.0) / 6.5) ** 2) * 1.6) * (0.62 - 0.003 * hgt)
            img[int(shelf) + 2:int(shelf) + 30, int(cx - 44):int(cx + 45)] *= (1.0 - sh)[..., None]
            # the glint: a band across the face, in a window round the moment it faces the front
            g0 = (face_at[i] - 7.0) % frames
            gp = ((f - g0) % frames) / 15.0
            band = None
            if gp < 1.0:
                vv, uu = np.mgrid[0:N, 0:N].astype(np.float32)
                pos = -6.0 + 50.0 * gp
                band = np.exp(-((uu + 0.55 * vv - pos) / 2.4) ** 2)
                band = np.kron(band, np.ones((scale, scale), np.float32))
            sprites.append((cx, cy, c, s_, band))
        # draw each sprite as a stack of slices, the far ones first
        canvas = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).convert('RGBA')
        for i, name in enumerate(ITEMS):
            cx, cy, c, s_, band = sprites[i]
            w = max(1, int(round(S * abs(c))))
            order = range(layers) if c >= 0 else range(layers - 1, -1, -1)       # far to near
            for k in order:
                z = (k - (layers - 1) / 2.0) * thick / (layers - 1)               # px toward the viewer (+) from the middle
                near = (k == layers - 1) if c >= 0 else (k == 0)
                arr = big[i].copy()
                if not near:
                    arr[..., :3] *= 0.52 + 0.1 * (0.5 + z / thick)
                elif band is not None:
                    arr[..., :3] = np.clip(arr[..., :3] + 120.0 * band[..., None] * (arr[..., 3:4] > 0), 0, 255)
                if c < 0:
                    arr = arr[:, ::-1]
                im = Image.fromarray(arr.astype(np.uint8), 'RGBA').resize((w, S), Image.NEAREST)
                canvas.alpha_composite(im, (int(round(cx - w / 2.0 + z * s_)), int(round(cy - S / 2.0))))
        img = np.asarray(canvas.convert('RGB'), np.float32)
        # sparkles on the front face while it faces the viewer
        for i, name in enumerate(ITEMS):
            cx, cy, c, s_, band = sprites[i]
            gp = ((f - ((face_at[i] - 3.0) % frames)) % frames) / 10.0
            if gp < 1.0 and c > 0.5:
                tu, tv = SPARKLE[name]
                sx = cx - S * c / 2.0 + (tu + 0.5) / N * S * c + 3.0 * s_
                sy = cy - S / 2.0 + (tv + 0.5) * scale
                a = math.sin(math.pi * gp) ** 1.5
                _cross(img, sx, sy, 5.0 + 9.0 * a, 0.95 * a, SPARKLE_COL[name])
        # the seared cut steams
        ci = ITEMS.index('cooked_gnashfin')
        for j in range(9):
            ph = ((f - j * frames / 9.0) % frames) / (frames * 0.8)
            if ph >= 1.0:
                continue
            wob = 7.0 * math.sin(math.tau * (1.5 * ph + j * 0.37)) * (0.4 + ph)
            _puff(img, centres[ci] + wob + 4.0 * math.sin(j * 2.3), sprites[ci][1] - S / 2.0 + 22.0 - 58.0 * ph, 4.5 + 7.0 * ph,
                  0.22 * math.sin(math.pi * ph) ** 1.3, (214, 214, 224))
        # the raw cut drips onto the shelf
        ri = ITEMS.index('raw_gnashfin')
        t_d = ((f - 4) % frames)
        rx = centres[ri] + 3.0
        ry = sprites[ri][1] + S / 2.0 - 7.0
        if t_d < 14:
            gr = t_d / 14.0
            _puff(img, rx, ry + 2.5 * gr, 1.2 + 1.6 * gr, 0.9, (120, 176, 206))
        elif t_d < 14 + 15:
            fall = t_d - 14
            y = ry + 3.0 + 0.5 * 0.55 * fall * fall * 0.9
            if y < shelf + 12:
                _puff(img, rx, y, 2.1, 0.95, (130, 186, 214))
                _puff(img, rx, y - 4.0, 1.4, 0.45, (110, 160, 192))
        if 14 + 12 <= t_d < 14 + 12 + 14:
            sp = (t_d - 26) / 14.0
            ring = Image.new('L', (W, H), 0)
            ImageDraw.Draw(ring).ellipse((rx - 3 - 20 * sp, shelf + 12 - 1.2 - 5 * sp, rx + 3 + 20 * sp, shelf + 12 + 1.2 + 5 * sp), outline=int(210 * (1 - sp)), width=1)
            ra = np.asarray(ring, np.float32)[..., None] / 255.0
            img = img * (1.0 - ra * 0.8) + ra * 0.8 * np.asarray((150, 200, 226), np.float32)
        out.append(np.clip(img, 0, 255).astype(np.uint8))
    # one palette for the whole loop (a palette per picture would shimmer)
    pal_src = Image.fromarray(np.concatenate([out[k] for k in range(0, frames, 3)], axis=0)).quantize(colors=250, method=Image.Quantize.MEDIANCUT)
    pics = [Image.fromarray(a).quantize(palette=pal_src, dither=Image.Dither.NONE) for a in out]
    pics[0].save(path, save_all=True, append_images=pics[1:], duration=ms, loop=0, disposal=1, optimize=False)
    return os.path.getsize(path)


def write_all(out, prev=None):
    """Writes the four textures and their model and item JSON under [out] (the guildmark assets folder); with [prev], also a contact
    sheet of them at 8x and at the size they show in a slot."""
    imgs = build()
    for name, img in imgs.items():
        path = os.path.join(out, 'textures', 'item', name + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        _write_json(os.path.join(out, 'models', 'item', name + '.json'),
                    {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'guildmark:item/' + name}})
        _write_json(os.path.join(out, 'items', name + '.json'),
                    {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}})
    if prev:
        os.makedirs(prev, exist_ok=True)
        ims = [imgs[n] for n in ITEMS]
        sheet(ims, 8, 4, labels=ITEMS).save(os.path.join(prev, 'sea_items.png'))
        animate(imgs, os.path.join(prev, 'sea_items.gif'))
    print('items: ' + ', '.join(ITEMS))
    return imgs


if __name__ == '__main__':
    write_all(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
