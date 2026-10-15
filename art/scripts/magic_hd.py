"""Guildmark's magic at 32x, painted with the HD art kit (hdkit.py): the six catalysts of the two schools (the
staffs of sorcery and the censers of prayer, painted along the sprite diagonal to match the held geometry
weaponkit.py solves), then the spell scrolls, the twelve spell icons, the spell projectiles, the arcane mote
particle and the Vigil effect icon.

    python3 -B art/scripts/magic_hd.py src/main/resources/assets/guildmark [preview-dir]

Writes the textures, the item models and definitions and the particle definition. Everything is seeded, so a
rerun gives the same files.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, mix, poly, seg_dist, path_dist, disc, ellipse, blur, edge, grow,
                   dist_in, light, rim, outline, glint, sheet)
from hdkit import bands as _bands
import weaponkit

N = 32
ROOT2 = math.sqrt(2.0)
XS, YS = grid(N)
# Every pixel's place along the sprite's diagonal (s, from the bottom-left corner) and across it (w, + is up-left)
S_ = (XS + (N - YS)) / ROOT2
W_ = (-XS - (YS - N)) / ROOT2

# ================================================================ tones

AMETHYST = [(20, 8, 38), (48, 18, 88), (92, 40, 150), (142, 84, 208), (196, 150, 250), (244, 226, 255)]
ENDER = [(6, 26, 30), (10, 58, 58), (20, 112, 96), (52, 178, 142), (132, 236, 190), (226, 255, 236)]
ECHO = [(6, 20, 34), (12, 48, 76), (24, 98, 136), (48, 164, 186), (116, 226, 226), (212, 255, 248)]
DEEPSLATE = [(9, 10, 15), (20, 22, 30), (33, 36, 46), (48, 52, 64), (68, 74, 88), (98, 106, 120)]
SCULK = [(6, 22, 30), (10, 50, 62), (16, 94, 108), (34, 156, 160), (96, 224, 214), (206, 255, 244)]
BLAZE = [(66, 22, 8), (136, 58, 12), (208, 110, 20), (248, 168, 36), (255, 222, 96), (255, 246, 190)]
BLACKGOLD = [(12, 9, 11), (26, 20, 19), (46, 35, 27), (74, 55, 33), (110, 82, 41), (160, 122, 56)]
TWINE = PAL['cork']


def bands(stops, t, dither=0.35):
    """hdkit's bands, taking a plain number for a flat tone too."""
    return _bands(stops, np.broadcast_to(np.asarray(t, float), (N, N)), dither)


# ================================================================ the sprite diagonal

def at_px(s, w):
    """Pixel position (x, y down) of the sprite point s along the diagonal, w across it."""
    x = s / ROOT2 - w / ROOT2
    y = N - (s / ROOT2 + w / ROOT2)
    return x, y


def sw_poly(pts):
    return poly([at_px(s, w) for s, w in pts], N)


def sw_disc(s, w, r):
    cx, cy = at_px(s, w)
    return disc(cx, cy, r, N)


def sw_seg(s0, w0, s1, w1):
    (x0, y0), (x1, y1) = at_px(s0, w0), at_px(s1, w1)
    return seg_dist(x0, y0, x1, y1, N)


def sw_path(pts):
    return path_dist([at_px(s, w) for s, w in pts], N)


def along(s0, s1):
    return (S_ >= s0) & (S_ < s1)


def profile(pts, s=None):
    """A half-width that runs through [(s, r), ...] along the diagonal; nothing outside the first and last."""
    s = S_ if s is None else s
    xs, rs = zip(*pts)
    return np.where((s >= xs[0]) & (s <= xs[-1]), np.interp(s, xs, rs), 0.0)


def revolve(pts, c=0.0):
    """A body of revolution about the diagonal (a collar, a bowl, a lid): its mask, and the height of its surface
    over the axis, for light()."""
    r = profile(pts)
    d = np.abs(W_ - c)
    m = (r > 0.0) & (d <= r)
    return m, np.sqrt(np.clip(r * r - d * d, 0, None))


def pixel_at(s, w):
    """The pixel whose centre is nearest the sprite point (s, w), as (x, y) ints."""
    x, y = at_px(s, w)
    return int(math.floor(x)), int(math.floor(y))


def lat(n, m):
    """The pixel at lattice point (n, m): n = 32 + x - y runs along the diagonal (one step is 0.7 of a pixel), m = 31 -
    x - y across it (+ toward the upper left). Only points with n + m odd are pixel centres."""
    return (n - m - 1) // 2, (63 - n - m) // 2


def nm_of(x, y):
    return 32 + x - y, 31 - x - y


def put_lat(cv, n, m, col, a=1.0):
    if (n + m) % 2 == 0:
        m += 1
    x, y = lat(n, m)
    cv.px(x, y, col, a)


def lit_side(w, k=1.0):
    """A flat tone shift across a thin part: up-left (+w) lit, down-right dark."""
    return k * (0.3 * (w > 0.3) - 0.26 * (w < -0.4))


def wrap(cv, mask, pal, s0, pitch=2.2, seed=1, base=0.38):
    """A leather or cord wrap: diagonal turns across the grip, each turn lit on its upper edge."""
    phase = ((S_ - s0) / pitch) % 1.0
    t = base + 0.32 * (phase < 0.5) - 0.18 * (phase > 0.85) + 0.18 * (W_ > 0.3) - 0.22 * (W_ < -0.5)
    t = t + 0.06 * (vnoise(N, N, 1.5, seed) - 0.5)
    cv.put(mask, bands(pal, t, 0.2))


def metal(cv, mask, pal, base=0.5, shade=None, k=1.0):
    """A fitting: lit on its up-left side, darker down-right."""
    t = base + 0.22 * np.clip(W_ / 3, -1, 1) * k + (0 if shade is None else shade)
    cv.put(mask, bands(pal, t, 0.25))


def finish(cv, glints=(), rim_k=0.26, rim_skip=None):
    """The edge darkening, the dark outline and any glints. [rim_skip] is a mask of pixels whose own edges are
    left bright (a glowing crystal, a flame)."""
    before = cv.rgb.copy()
    rim(cv, rim_k)
    if rim_skip is not None:
        cv.rgb[rim_skip] = before[rim_skip]
    outline(cv, 0.8)
    for x, y, size, col in glints:
        glint(cv, x, y, size, col)
    return cv.image()


# ================================================================ crystals

def crystal(cv, base, tip, hw, pal, shoulder=0.68, tones=(0.80, 0.58, 0.34), spark=(0.5, 0.86), seam=None, keep=None):
    """A faceted crystal: a hexagonal prism from [base] up to a shoulder, then a pyramid to [tip] (points are (s, w)).
    Three facets show: the one turned toward the light, the middle one, the far one in shade, with a short streak of
    light on the lit facet. Where it lies over [seam] (a mask of crystals already drawn) its edge is cut in dark.
    Returns the mask."""
    (s0, w0), (s1, w1) = base, tip
    ax = np.array([s1 - s0, w1 - w0], float)
    L = float(np.hypot(*ax))
    ax /= L
    nrm = np.array([-ax[1], ax[0]])
    ds, dw = S_ - s0, W_ - w0
    u = ds * ax[0] + dw * ax[1]
    v = ds * nrm[0] + dw * nrm[1]
    if nrm[1] < 0:                      # across, signed so that + is the side turned up-left, toward the light
        v = -v
    half = np.where(u < L * shoulder, hw, hw * np.clip((L - u) / (L * (1 - shoulder)), 0, 1))
    m = (u >= 0) & (u <= L) & (np.abs(v) <= half)
    f = v / np.maximum(half, 0.35)
    t = np.where(f > 0.34, tones[0], np.where(f > -0.34, tones[1], tones[2]))
    streak = m & (f > 0.34) & (u > spark[0] * L) & (u < spark[1] * L) & (f < 0.34 + 0.55)
    t = np.where(streak, 0.97, t)
    t = np.where(u < 1.0, t - 0.14, t)
    if keep is not None:
        m = m & keep
    cv.put(m, bands(pal, t, 0.0))
    if seam is not None:
        cut = edge(m) & seam
        cv.put(cut, pal[0])
    return m


# ================================================================ pixel drawings

def draw(cv, rows, x0, y0, pal=None, extra=None):
    """Blit a hand-drawn pixel picture: one string per row, '.' or ' ' for nothing, a digit for that tone of [pal],
    any other character looked up in [extra] (a colour)."""
    extra = extra or {}
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch in '. ':
                continue
            if ch.isdigit():
                col = pal[int(ch)]
            else:
                col = extra[ch]
            cv.px(x0 + i, y0 + j, np.array(col, float))


# ================================================================ the staffs

def wood(cv, mask, pal, c=0.0, seed=5, knots=()):
    """A slim haft of wood: the lit side up-left, grain along it, a few knots."""
    dw = W_ - c
    g = vnoise(N, N, 2.6, seed) - 0.5
    t = 0.42 + lit_side(dw) + 0.16 * g
    for (ks, kw) in knots:
        t = np.where(np.hypot(S_ - ks, (W_ - kw) * 1.2) < 0.75, t - 0.3, t)
    cv.put(mask, bands(pal, t, 0.3))


def ring(cv, s0, s1, hw, pal, base=0.55, c=0.0):
    """A band round the haft."""
    m = along(s0, s1) & (np.abs(W_ - c) <= hw)
    metal(cv, m, pal, base)
    return m


def staff_head_cord(cv, s0, s1, hw0, hw1, pal=TWINE, pitch=1.1):
    """Cord turns lashed round the base of a head: wider at the top, a bell of turns."""
    r = hw0 + (hw1 - hw0) * np.clip((S_ - s0) / max(s1 - s0, 0.1), 0, 1)
    m = along(s0, s1) & (np.abs(W_) <= r)
    phase = ((S_ - s0) / pitch) % 1.0
    t = 0.5 + 0.3 * (phase < 0.5) - 0.2 * (phase > 0.8) + 0.12 * (W_ > 0.3) - 0.18 * (W_ < -0.5)
    cv.put(m, bands(pal, t, 0.2))
    return m


def amethyst_staff():
    cv = Canvas(N)
    c = 0.3 * np.sin(S_ * 0.23 + 0.9)
    # The haft: a dark wood branch, a little gnarled
    haft = along(2.4, 36.5) & (np.abs(W_ - c) <= 0.82)
    wood(cv, haft, PAL['wood'], c, 5, knots=((17.5, 0.3), (27.0, -0.2), (6.0, 0.4)))
    # A cord grip where the hand closes (s = 12)
    grip = along(9.0, 15.2) & (np.abs(W_ - c) <= 1.0)
    wrap(cv, grip, TWINE, 9.0, 1.7, 7)
    # The iron butt cap and its ferrule
    cap = along(0.8, 3.3) & (np.abs(W_) <= 1.2 - 0.25 * (S_ < 1.6))
    metal(cv, cap, PAL['iron'], 0.5)
    ring(cv, 3.3, 4.3, 1.2, PAL['iron'], 0.55, c)
    # The head: a fan of three crystals lashed into the split top of the haft
    tones = (0.86, 0.62, 0.36)
    seen = np.zeros((N, N), bool)
    for base, tip, hw in (((34.6, -0.7), (40.0, -4.0), 1.1), ((34.6, 0.7), (40.0, 4.0), 1.1), ((34.0, 0.0), (44.4, 0.0), 1.5)):
        seen |= crystal(cv, base, tip, hw, AMETHYST, tones=tones, seam=seen)
    staff_head_cord(cv, 33.0, 35.4, 1.5, 1.9)
    return finish(cv)


def ender_staff():
    cv = Canvas(N)
    gold = PAL['gold']
    # The haft: dark wood, straight, with gold bands
    haft = along(2.6, 33.0) & (np.abs(W_) <= 0.82)
    wood(cv, haft, PAL['darkwood'], 0.0, 15)
    grip = along(9.0, 15.2) & (np.abs(W_) <= 1.0)
    wrap(cv, grip, PAL['blackleather'], 9.0, 1.9, 17, base=0.5)
    # Gold butt cap, bands and a collar
    cap = along(0.8, 3.6) & (np.abs(W_) <= 1.3 - 0.3 * (S_ < 1.7))
    metal(cv, cap, gold, 0.5)
    for s0 in (4.4, 8.0, 15.5, 22.0):
        ring(cv, s0, s0 + 1.3, 1.35, gold, 0.58)
    ring(cv, 27.2, 28.9, 1.35, gold, 0.58)
    # The head: a gold collar, a cage of prongs and the eye held in it
    collar = along(31.6, 34.2) & (np.abs(W_) <= 1.2 + 0.5 * np.clip((S_ - 31.6) / 2.6, 0, 1))
    metal(cv, collar, gold, 0.55)
    eye, d = sw_disc(38.2, 0.0, 3.0)
    ex, ey = at_px(38.2, 0.0)
    r = np.hypot(XS - ex, YS - ey) / 3.0
    k = light(d * 2.2, 1.0)
    et = np.where(r < 0.36, 0.06, np.where(r < 0.7, 0.78 + 0.3 * k, 0.46 + 0.8 * k))
    cv.put(eye, bands(ENDER, et, 0.0))
    cage = np.zeros((N, N), bool)
    for side in (1, -1):
        dd, t = sw_path([(33.8, 1.3 * side), (35.4, 3.0 * side), (38.2, 3.9 * side), (40.6, 3.2 * side),
                         (42.2, 1.9 * side), (43.2, 0.6 * side), (43.5, 0.0)])
        prong = dd < 0.5
        cage |= prong
        cv.put(prong, bands(gold, (0.84 if side > 0 else 0.62) - 0.12 * t / 14.0, 0.0))
    # the eye's glint
    gx, gy = pixel_at(39.6, 1.5)
    cv.px(gx, gy, (236, 255, 244))
    return finish(cv, rim_skip=eye | cage)


def echo_staff():
    cv = Canvas(N)
    # The haft: deepslate, straight, a thread of sculk light inlaid along it
    haft = along(2.6, 33.8) & (np.abs(W_) <= 0.82)
    t = 0.42 + lit_side(W_) + 0.14 * (vnoise(N, N, 2.6, 25) - 0.5)
    cv.put(haft, bands(DEEPSLATE, t, 0.3))
    inlay = haft & (np.abs(W_) < 0.4) & ((S_ < 7.6) | (S_ > 15.6))
    cv.put(inlay, bands(SCULK, 0.62 + 0.25 * np.sin(S_ * 1.1), 0.0))
    grip = along(8.6, 15.2) & (np.abs(W_) <= 1.0)
    wrap(cv, grip, PAL['blackleather'], 8.6, 1.9, 27, base=0.46)
    cap = along(0.8, 3.4) & (np.abs(W_) <= 1.2 - 0.3 * (S_ < 1.7))
    metal(cv, cap, DEEPSLATE, 0.6)
    ring(cv, 4.2, 5.2, 1.25, DEEPSLATE, 0.65)
    ring(cv, 16.4, 17.4, 1.25, DEEPSLATE, 0.65)
    ring(cv, 27.0, 28.2, 1.25, DEEPSLATE, 0.65)
    # The head: black claws holding a tall echo shard
    for side in (1, -1):
        claw = sw_path([(32.6, 1.0 * side), (34.6, 2.7 * side), (37.0, 3.1 * side)])[0] < 0.68
        metal(cv, claw, DEEPSLATE, 0.78 if side > 0 else 0.7, 0.0)
        tx, ty = pixel_at(37.0, 3.1 * side)
        cv.px(tx, ty, SCULK[4])
    shard = crystal(cv, (34.4, 0.0), (43.8, 0.0), 2.4, ECHO, shoulder=0.62, tones=(0.80, 0.64, 0.46), spark=(0.45, 0.8))
    core = (np.abs(W_) < 0.45) & along(36.0, 43.0)
    cv.put(core, bands(ECHO, 0.88 + 0.12 * ((S_ - 36.0) / 7), 0.0))
    return finish(cv, rim_skip=shard)


# ================================================================ the censers

def censer_haft(cv, pal, s0, s1, hw=1.0, base=0.42, seed=3):
    haft = along(s0, s1) & (np.abs(W_) <= hw)
    t = base + lit_side(W_) + 0.12 * (vnoise(N, N, 2.4, seed) - 0.5)
    cv.put(haft, bands(pal, t, 0.3))


def sphere_burner(cv, pal, pts, centre=32.0, R=5.6, base=0.6, k=1.3, floor=0.3, only=None):
    """A body of revolution (a censer's collar and foot) shaded as a ball lit from the upper left."""
    m, h = revolve(pts)
    if only is not None:
        m = m & only
    cx, cy = at_px(centre, 0.0)
    d = np.hypot(XS - cx, YS - cy)
    z = np.sqrt(np.clip(R * R - d * d, 0, None))
    t = np.clip(base + k * light(np.maximum(z, h), 1.0), floor, 1.0)
    cv.put(m, bands(pal, t, 0.0))
    return m


def knob(cv, pal, s, r, base=0.5):
    m, d = sw_disc(s, 0.0, r)
    cv.put(m, bands(pal, base + 1.1 * light(d * 2, 1.0) + 0.2 * d, 0.0))
    return m


# The burners are drawn by hand, a pixel at a time: an 11 x 11 ball lit from the upper left (the digits are tones of
# the metal's palette), pole to pole along the sprite diagonal, set so its middle is at s = 32. Holes are lit pixels
# painted over it ('o' ember, 'y' amber, 'w' white-hot; 'g' and 'W' are the gilded censer's orange and warm white).
BALL = ["....433....",
        "..4444332..",
        ".455544332.",
        ".455544332.",
        "44555443322",
        "34444433221",
        "33444333221",
        ".333333221.",
        ".233322221.",
        "..2222211..",
        "....211...."]
BALL_AT = (17, 4)
FINIAL = ["54", "43"]
FINIAL_AT = (26, 4)


def ball(edits):
    """The ball's rows with [edits] ({(u, v): char}) painted over it (cells outside the ball are left out)."""
    rows = [list(r) for r in BALL]
    for (u, v), ch in edits.items():
        if 0 <= v < len(rows) and 0 <= u < len(rows[v]) and rows[v][u] != '.':
            rows[v][u] = ch
    return [''.join(r) for r in rows]


def iron_censer():
    cv = Canvas(N)
    iron = PAL['iron']
    ember = PAL['ember']
    lit = {'o': ember[3], 'y': ember[4], 'w': ember[5]}
    censer_haft(cv, iron, 2.2, 23.0, 1.0, 0.44, 31)
    grip = along(5.0, 12.4) & (np.abs(W_) <= 1.15)
    wrap(cv, grip, PAL['blackleather'], 5.0, 1.9, 33, base=0.48)
    knob(cv, iron, 1.9, 1.7, 0.4)
    ring(cv, 12.6, 13.4, 1.4, iron)
    sphere_burner(cv, iron, [(22.8, 1.9), (24.6, 1.9), (24.6, 1.3), (26.0, 1.3), (26.0, 1.9), (27.0, 2.6)],
                  base=0.62, k=1.3)
    # a groove round the dome where the lid sits, and ember light through six holes
    edits = {(k + 2, k): '2' if k > 4 else '3' for k in range(2, 9)}
    edits.update({(5, 2): 'w', (7, 3): 'y', (8, 5): 'o', (4, 6): 'y', (2, 5): 'o', (5, 8): 'o'})
    draw(cv, ball(edits), *BALL_AT, iron, lit)
    draw(cv, FINIAL, *FINIAL_AT, iron)
    return finish(cv)


def gilded_censer():
    cv = Canvas(N)
    gold, dw = PAL['gold'], PAL['darkwood']
    lit = {'g': (255, 160, 44), 'W': (255, 246, 208)}
    censer_haft(cv, dw, 2.2, 23.0, 1.0, 0.5, 41)
    grip = along(5.0, 12.4) & (np.abs(W_) <= 1.15)
    wrap(cv, grip, PAL['blackleather'], 5.0, 1.9, 43, base=0.5)
    knob(cv, gold, 1.9, 1.8, 0.5)
    ring(cv, 12.6, 13.6, 1.4, gold, 0.6)
    ring(cv, 17.6, 18.4, 1.3, gold, 0.6)
    sphere_burner(cv, gold, [(21.0, 1.4), (22.0, 1.4), (22.0, 1.9), (24.8, 1.9), (24.8, 1.4), (26.0, 1.4), (26.0, 1.9),
                             (27.0, 2.6)], base=0.62, k=1.3)
    # filigree slits: a warm orange light with a white-hot spark beside it
    edits = {}
    for (u, v) in [(5, 7), (7, 8), (3, 8), (6, 3), (8, 5), (4, 5)]:
        edits[(u, v)] = 'g'
        edits[(u + 1, v - 1)] = 'W'
    draw(cv, ball(edits), *BALL_AT, gold, lit)
    draw(cv, FINIAL, *FINIAL_AT, gold)
    img = finish(cv)
    # the loop of the lid's chain, drawn after the edges so it keeps its own outline
    cvl = Canvas.of(img)
    draw(cvl, [".4.", "3.4", ".2."], 27, 1, gold)
    outline(cvl, 0.8)
    return cvl.image()


# The blazing censer: a blackened-gold cage-ball with glowing ribs and a flame roaring from its lid. The head is
# drawn in one picture, 14 x 14 pixels from (17, 1): digits are tones of BLACKGOLD, 'r' 'o' 'y' 'Y' 'W' the fire.
BLAZE_HEAD = [
    ".........Y....",
    ".........YW...",
    "........YWYy..",
    "....433yYWWYo.",
    "..44443yYWWYo.",
    ".43554yoyYYyo.",
    ".4554y43ooyo..",
    "4444y443o22...",
    "344y443o221...",
    "33y443o3221...",
    ".3333o3221....",
    ".333o22221....",
    "..o222211.....",
    "....211.......",
]


def blazing_censer():
    cv = Canvas(N)
    bg = BLACKGOLD
    censer_haft(cv, PAL['blacksteel'], 2.2, 23.0, 1.0, 0.42, 51)
    grip = along(5.0, 12.4) & (np.abs(W_) <= 1.15)
    wrap(cv, grip, PAL['crimson'], 5.0, 1.9, 53, base=0.4)
    knob(cv, bg, 1.9, 1.8, 0.5)
    ring(cv, 12.6, 13.6, 1.4, bg, 0.6)
    ring(cv, 18.0, 18.8, 1.3, bg, 0.6)
    sphere_burner(cv, bg, [(22.4, 1.9), (24.6, 1.9), (24.6, 1.4), (26.0, 1.4), (26.0, 1.9), (27.0, 3.0)],
                  base=0.55, k=1.2)
    fire = {'r': BLAZE[1], 'o': BLAZE[2], 'y': BLAZE[3], 'Y': BLAZE[4], 'W': BLAZE[5]}
    draw(cv, BLAZE_HEAD, 17, 1, bg, fire)
    glow = np.zeros((N, N), bool)
    for j, row in enumerate(BLAZE_HEAD):
        for i, ch in enumerate(row):
            if ch in fire:
                glow[1 + j, 17 + i] = True
    return finish(cv, rim_skip=glow)


# ================================================================ spell art: shared helpers

CX = N / 2.0          # the middle of a 32 x 32 sprite, in pixel-centre coordinates
ICE = GEMS['frost']
VIOLET = GEMS['violet']
WARM_WHITE = (255, 244, 208)


def cover(cv, cov, col):
    """Lay [col] over the canvas wherever [cov] (0..1 per pixel) says: the usual 'over', alpha kept right."""
    cov = np.clip(np.asarray(cov, float), 0, 1)
    col = np.asarray(col, float)
    if col.ndim == 1:
        col = np.broadcast_to(col, cv.rgb.shape)
    out_a = cov + cv.a * (1 - cov)
    w = np.where(out_a > 1e-6, cov / np.maximum(out_a, 1e-6), 0.0)[..., None]
    cv.rgb = cv.rgb * (1 - w) + col * w
    cv.a = out_a


def cover_px(cv, x, y, col, a):
    if not (0 <= x < cv.w and 0 <= y < cv.h):
        return
    out_a = a + cv.a[y, x] * (1 - a)
    w = a / max(out_a, 1e-6)
    cv.rgb[y, x] = cv.rgb[y, x] * (1 - w) + np.asarray(col, float) * w
    cv.a[y, x] = out_a


def frame(p0, p1):
    """Local coordinates along and across the segment p0 -> p1: u runs from 0 at p0 to L at p1, v is the distance
    to the left of the way you are going (screen-up when going right, up-left when going up-right)."""
    (x0, y0), (x1, y1) = p0, p1
    L = math.hypot(x1 - x0, y1 - y0)
    ex, ey = (x1 - x0) / L, (y1 - y0) / L
    rx, ry = XS - x0, YS - y0
    return rx * ex + ry * ey, rx * ey - ry * ex, L


def polar(cx=CX, cy=CX):
    dx, dy = XS - cx, YS - cy
    return np.hypot(dx, dy), np.arctan2(dy, dx)


def rot(p, a, c=(CX, CX)):
    """The point p turned by [a] degrees (clockwise on screen) about c."""
    r = math.radians(a)
    x, y = p[0] - c[0], p[1] - c[1]
    return (c[0] + x * math.cos(r) - y * math.sin(r), c[1] + x * math.sin(r) + y * math.cos(r))


def core_shade(cv, mask, pal, lo=0.4, step=0.2, limit=5, dither=0.0):
    """Colour a glowing shape from its edge in: the dark-ish edge, brighter and brighter toward the middle."""
    d = dist_in(mask, limit)
    cv.put(mask, bands(pal, lo + step * (d - 1), dither))
    return d


def tongue(path, r0, r1, rmax=None, peak=0.35, size=N):
    """A tapering stroke along a polyline (pixel points): radius r0 at the start, a swell to [rmax] a [peak] of the
    way along, down to r1 at the end. Returns its mask and how far along it each pixel is (0..1)."""
    d, a = path_dist(path, size)
    total = sum(math.hypot(x1 - x0, y1 - y0) for (x0, y0), (x1, y1) in zip(path, path[1:]))
    f = np.clip(a / total, 0, 1)
    rmax = max(r0, r1) if rmax is None else rmax
    peak = min(max(peak, 0.001), 0.999)
    r = np.where(f < peak, r0 + (rmax - r0) * (f / peak), rmax + (r1 - rmax) * ((f - peak) / (1 - peak)))
    return d <= r, f, d / np.maximum(r, 1e-6)


def shard(cv, p0, p1, hw, pal, shoulder=0.6, tones=(0.8, 0.56, 0.3), spark=(0.5, 0.86), base_cut=0.0, mask_only=False,
          streak_t=0.97, base_hw=None, keep=None):
    """A slender faceted crystal from p0 to p1 (widening from [base_hw] to [hw] by a shoulder, then a pyramid to the
    point): the facet turned toward the light, the middle one, the far one in shade, a streak of light on the lit
    one."""
    u, v, L = frame(p0, p1)
    if (p1[0] - p0[0]) - (p1[1] - p0[1]) < 0:        # going toward the lower right: the lit facet is on the other side
        v = -v
    bh = hw if base_hw is None else base_hw
    rise = bh + (hw - bh) * np.clip(u / max(L * shoulder, 1e-6), 0, 1)
    half = np.where(u < L * shoulder, rise, hw * np.clip((L - u) / (L * (1 - shoulder)), 0, 1))
    m = (u >= base_cut) & (u <= L) & (np.abs(v) <= half)
    if keep is not None:
        m = m & keep
    if mask_only:
        return m
    f = v / np.maximum(half, 0.35)
    t = np.where(f > 0.34, tones[0], np.where(f > -0.34, tones[1], tones[2]))
    streak = m & (f > 0.34) & (u > spark[0] * L) & (u < spark[1] * L)
    t = np.where(streak, streak_t, t)
    cv.put(m, bands(pal, t, 0.0))
    return m


def twinkle(cv, x, y, r, col, core=None, a=1.0):
    """A little four-pointed twinkle centred on the pixel (x, y), arms r long, thinning outward."""
    core = col if core is None else core
    cover_px(cv, x, y, core, a)
    for i in range(1, r + 1):
        k = a * (1.0 - 0.7 * (i - 1) / max(r, 1)) if r > 1 else a * 0.7
        for dx, dy in ((i, 0), (-i, 0), (0, i), (0, -i)):
            cover_px(cv, x + dx, y + dy, col, k)


def poly_height(pts, size=N):
    """A polygon's mask and how far in each pixel sits from its edge: a ridge along its spine, for faceted shading."""
    m = poly(pts, size)
    xs, ys = grid(size)
    d = np.full((size, size), 1e9)
    for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
        d = np.minimum(d, seg_dist(x0, y0, x1, y1, size)[0])
    return m, np.where(m, d, 0.0)


def facets(cv, mask, h, pal, base=0.55, k=0.34, strength=1.4):
    """Shade a pointed shape as a few flat facets: the sides turned toward the light bright, those turned away dark."""
    lt = light(h * strength, 1.0)
    cv.put(mask, bands(pal, base + k * np.sign(lt) * (np.abs(lt) > 0.02) * 1.0 + 0.0, 0.0))


# ================================================================ spell icons: the round dark backing

SORCERY_BACK = ((12, 8, 24), (44, 26, 78))          # edge, middle
PRAYER_BACK = ((20, 12, 10), (72, 40, 20))
RIM_STEEL = [(8, 7, 14), (24, 22, 38), (46, 44, 66), (80, 78, 104), (124, 122, 150), (176, 172, 196)]
RIM_BRONZE = [(12, 8, 8), (36, 22, 16), (74, 46, 26), (118, 78, 38), (170, 120, 56), (222, 176, 98)]
ICON_R = 12.9            # the glyph's room, inside the rim
DISC_R = 15.4            # the disc's edge


def backing(school):
    """The round dark disc every spell icon sits on: a rim of dark metal with a lit upper left, and a deep
    backing that warms toward the middle, tinted by the school. A pixel of margin all round."""
    cv = Canvas(N)
    dx, dy = XS - CX, YS - CX
    r = np.hypot(dx, dy)
    edge_c, mid_c = SORCERY_BACK if school == 'sorcery' else PRAYER_BACK
    pal = RIM_STEEL if school == 'sorcery' else RIM_BRONZE
    body = r <= DISC_R
    cv.put(body, (0, 0, 0))
    lit = -(dx + dy) / np.maximum(r * ROOT2, 1e-6)
    cv.put(body & (r > ICON_R + 0.5), bands(pal, 0.46 + 0.34 * lit, 0.0))
    cv.put(body & (r > DISC_R - 0.9), pal[0])
    cv.put(body & (r > ICON_R) & (r <= ICON_R + 0.5), pal[0])
    inside = r <= ICON_R
    k = np.clip(1 - r / ICON_R, 0, 1) ** 1.4
    n = vnoise(N, N, 3.0, 11 if school == 'sorcery' else 12) - 0.5
    cv.put(inside, mix(np.array(edge_c, float), np.array(mid_c, float), np.clip(k + 0.1 * n, 0, 1)))
    return cv


def halo(cv, field, col, strength=0.5, radius=2, outside=False):
    """A soft glow of [col] round the bright shapes of [field], kept inside the backing ([outside]: only beyond them)."""
    f = np.asarray(field, float)
    g = blur(f, radius)
    if outside:
        g = g * (f < 0.5)
    r = np.hypot(XS - CX, YS - CX)
    cover(cv, np.clip(g * strength, 0, 1) * (r <= ICON_R), col)


def inside_disc(mask, margin=0.0):
    return mask & (np.hypot(XS - CX, YS - CX) <= ICON_R + margin)


def seat(cv):
    """The finished icon: nothing outside the rim."""
    cv.a[np.hypot(XS - CX, YS - CX) > DISC_R] = 0
    return cv.image()


# ================================================================ the sorcery icons

def icon_arc_bolt():
    cv = backing('sorcery')
    # a bolt of violet light: a zigzag stroke, thick in the middle, a fork off its bend
    path = [(22.4, 2.6), (14.6, 13.4), (19.6, 15.2), (10.0, 29.0)]
    m, f, e = tongue(path, 0.9, 0.6, 2.7, 0.5)
    fork, ff, fe = tongue([(18.0, 14.6), (22.6, 17.0), (26.2, 16.4)], 1.0, 0.5, 1.2, 0.3)
    m = inside_disc(m | fork)
    halo(cv, m, (130, 70, 220), 0.9, 2)
    core_shade(cv, m, AMETHYST, 0.42, 0.2, 4)
    spine, _ = path_dist(path, N)
    cv.put(m & (spine <= 0.75), AMETHYST[5])
    for (x, y, r) in ((6, 9, 1), (26, 24, 1), (25, 8, 1), (6, 21, 1)):
        twinkle(cv, x, y, r, (196, 150, 250), (244, 226, 255))
    return seat(cv)


def icon_splinter_hail():
    cv = backing('sorcery')
    org = (16.0, 28.6)
    # five amethyst splinters fanned out across the front, the outer ones first
    spec = [(-46, 16.0, 1.7), (46, 16.0, 1.7), (-23, 20.5, 1.9), (23, 20.5, 1.9), (0, 23.5, 2.2)]
    ends = []
    allm = np.zeros((N, N), bool)
    for ang, L, hw in spec:
        a = math.radians(ang)
        p0 = (org[0] + 1.0 * math.sin(a), org[1] - 1.0 * math.cos(a))
        p1 = (org[0] + L * math.sin(a), org[1] - L * math.cos(a))
        ends.append((p0, p1, hw))
        allm |= shard(cv, p0, p1, hw, AMETHYST, 0.45, mask_only=True, base_hw=0.6)
    halo(cv, allm, (120, 60, 210), 0.6, 2, outside=True)
    seen = np.zeros((N, N), bool)
    for p0, p1, hw in ends:
        m = shard(cv, p0, p1, hw, AMETHYST, 0.45, (0.88, 0.68, 0.42), (0.5, 0.86), base_hw=0.6)
        cv.put(edge(m) & seen, AMETHYST[0])
        seen |= m
        # a glint at each point
        tip = (int(p1[0] - (p1[0] - p0[0]) * 0.06), int(p1[1] - (p1[1] - p0[1]) * 0.06))
        cover_px(cv, tip[0], tip[1], AMETHYST[5], 1.0)
    return seat(cv)


def icon_rime_lance():
    cv = backing('sorcery')
    p0, p1 = (5.2, 26.2), (27.4, 5.6)
    ux, uy = (p1[0] - p0[0]), (p1[1] - p0[1])
    L = math.hypot(ux, uy)
    ux, uy = ux / L, uy / L                      # along the lance
    nx, ny = uy, -ux                             # to its lit side
    allm = np.zeros((N, N), bool)
    # frost barbs swept back from the shaft, both sides
    barbs = []
    for t0, ln, sgn in ((0.30, 7.5, 1), (0.30, 7.5, -1), (0.54, 5.8, 1), (0.54, 5.8, -1)):
        bx, by = p0[0] + ux * L * t0, p0[1] + uy * L * t0
        tx, ty = bx - ux * ln * 0.45 + sgn * nx * ln * 0.88, by - uy * ln * 0.45 + sgn * ny * ln * 0.88
        barbs.append(((bx, by), (tx, ty)))
        allm |= shard(cv, (bx, by), (tx, ty), 1.4, ICE, 0.5, mask_only=True)
    shaft = shard(cv, p0, p1, 3.0, ICE, 0.64, mask_only=True, base_hw=1.4)
    halo(cv, allm | shaft, (110, 180, 255), 0.8, 2, outside=True)
    for (bx, by), (tx, ty) in barbs:
        shard(cv, (bx, by), (tx, ty), 1.4, ICE, 0.5, (0.84, 0.6, 0.36), (0.5, 0.8), base_hw=0.6)
    m = shard(cv, p0, p1, 3.0, ICE, 0.64, (0.9, 0.66, 0.38), (0.4, 0.92), base_hw=1.4)
    u, v, LL = frame(p0, p1)
    cv.put(m & (v > 2.0) & (u > 0.18 * LL) & (u < 0.9 * LL), (236, 248, 255))
    cv.put(m & (u > 0.93 * LL), (236, 248, 255))
    twinkle(cv, 25, 12, 1, (200, 232, 255), (255, 255, 255), 0.9)
    twinkle(cv, 8, 13, 1, (160, 200, 255), (230, 245, 255), 0.8)
    return seat(cv)


def icon_undertow():
    cv = backing('sorcery')
    r, th = polar(CX - 0.0, CX - 0.0)
    # the pull: three arms of a spiral wound inward, a dark body with a bright leading rim to each arm
    arms = 3
    phase = th / (2 * math.pi) * arms + r * 0.095
    ph = phase - np.floor(phase)
    body = r <= 11.4 + 1.6 * (ph < 0.5)
    body &= r > 1.6
    t = np.where(ph < 0.12, 1.0, np.where(ph < 0.5, 0.58 - 0.12 * (ph - 0.12) / 0.38, 0.22))
    t = np.where((ph >= 0.5) & (r < 9.0), 0.16, t)
    halo(cv, body.astype(float) * 0.7, (110, 50, 190), 0.8, 2)
    pal = AMETHYST
    cv.put(inside_disc(body), bands(pal, t, 0.0))
    # the edge of the vortex catches the light
    rim = edge(body) & (ph < 0.5) & (r > 9)
    cv.put(inside_disc(rim), AMETHYST[4])
    # the dark eye with a pinprick of light
    eye = r <= 1.9
    cv.put(eye, (8, 4, 16))
    cv.px(16, 16, AMETHYST[3])
    return seat(cv)


def chevron(x, y, arm, back, hw):
    path = [(x - back, y - arm), (x, y), (x - back, y + arm)]
    return tongue(path, hw, hw, hw, 0.5)[0]


def icon_flicker():
    cv = backing('sorcery')
    # three chevrons of light, each fainter behind the last, a flash at the head, motes shed behind
    allm = np.zeros((N, N), bool)
    layers = []
    for i, (x, tone_k, a) in enumerate(((9.0, 0.4, 0.5), (15.5, 0.58, 0.8), (22.0, 0.78, 1.0))):
        m = inside_disc(chevron(x, 16.0, 8.2 - 0.5 * (2 - i), 6.0, 1.8))
        layers.append((m, tone_k, a))
        allm |= m
    halo(cv, allm, (130, 70, 220), 0.7, 2, outside=True)
    for m, tone_k, a in layers:
        d = dist_in(m, 3)
        cover(cv, m * a, bands(AMETHYST, tone_k + 0.11 * (d - 1), 0.0))
    front = layers[2][0]
    cv.put(front & (dist_in(front, 3) >= 2) & (XS > 22), AMETHYST[5])
    for (x, y, a) in ((5, 10, 0.7), (4, 22, 0.7), (7, 6, 0.4), (6, 25, 0.4)):
        cover_px(cv, x, y, AMETHYST[4], a)
    twinkle(cv, 27, 16, 2, (200, 160, 255), (250, 240, 255))
    return seat(cv)


def star_poly(cx, cy, ro, ri, n=4, rot=-90.0):
    pts = []
    for i in range(2 * n):
        a = math.radians(rot + i * 180.0 / n)
        r = ro if i % 2 == 0 else ri
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def star_art(cv, cx, cy, ro, ri, n, rot_deg, pal, lit_t=0.8, dark_t=0.5):
    """A star painted as flat facets: each arm split down its spine, the side turned toward the light bright, the other
    in shade. Returns its mask."""
    mask = poly(star_poly(cx, cy, ro, ri, n, rot_deg), N)
    th = np.arctan2(YS - cy, XS - cx)
    alphas = np.array([math.radians(rot_deg + i * 360.0 / n) for i in range(n)])
    diff = np.angle(np.exp(1j * (th[..., None] - alphas)))          # signed angle to each arm's spine, -pi..pi
    k = np.argmin(np.abs(diff), axis=-1)
    off = np.take_along_axis(diff, k[..., None], -1)[..., 0]
    a = alphas[k]
    side = np.where(off >= 0, 1.0, -1.0)
    px_, py_ = np.cos(a + side * math.pi / 2), np.sin(a + side * math.pi / 2)
    lit = -(px_ + py_) / ROOT2 > 0
    cv.put(mask, bands(pal, np.where(lit, lit_t, dark_t), 0.0))
    return mask


def icon_starbreak():
    cv = backing('sorcery')
    # the crash: a ring of light on the ground
    ring_o, _ = ellipse(CX, 25.0, 10.6, 3.4, N)
    ring_i, _ = ellipse(CX, 25.0, 7.8, 2.0, N)
    ring = ring_o & ~ring_i
    star = poly(star_poly(CX, 13.0, 9.6, 4.2, 5, -90 + 10), N)
    halo(cv, star | ring, (150, 170, 255), 0.9, 3, outside=True)
    cv.put(ring, bands(ICE, np.where(YS < 25.0, 0.42, 0.72), 0.0))
    star_art(cv, CX, 13.0, 9.6, 4.2, 5, -90 + 10, ICE, 0.82, 0.48)
    cv.put(star & (np.hypot(XS - CX, YS - 13.2) < 2.2), (255, 250, 236))
    # streaks of its fall above it
    for x0, y0, x1, y1, w in ((9.0, 3.2, 10.6, 7.6, 0.6), (22.6, 3.0, 21.4, 7.4, 0.6)):
        d, t = seg_dist(x0, y0, x1, y1, N)
        cv.put(inside_disc(d <= w * (0.6 + t) * (star == 0)), ICE[3])
    return seat(cv)


# ================================================================ the prayer icons

EMBER = PAL['ember']
GOLD = PAL['gold']


def flame_tongue(cv, path, r0, r1, rmax, peak, lo=0.55, step=0.2, pal=EMBER, tip_fade=0.28, limit=4, paint=True):
    """A tongue of fire along a path: hot at its heart, orange at the edge, cooling toward the tip. Returns its mask
    (and, with [paint] off, how far along it each pixel is, so it can be painted later over a halo)."""
    m, f, e = tongue(path, r0, r1, rmax, peak)
    m = inside_disc(m)
    if not paint:
        return m, f
    d = dist_in(m, limit)
    cv.put(m, bands(pal, lo + step * (d - 1) - tip_fade * f, 0.0))
    return m


def fire_paths(cx, by, h, w, lean=0.0, lick=-1):
    """The paths of a flame standing on (cx, by), h tall and w wide at the belly: a main tongue, and a smaller lick
    to one side ([lick] -1 left, 1 right, 0 none). Each is (path, r0, r1, rmax, peak)."""
    main = ([(cx, by), (cx - 0.1 * w * (1 if lean >= 0 else -1), by - 0.35 * h), (cx + lean * 0.5, by - 0.7 * h),
             (cx + lean, by - h)], w * 0.5, 0.25, w * 0.5, 0.28)
    out = [main]
    if lick:
        out.append(([(cx + lick * w * 0.42, by - 0.04 * h), (cx + lick * w * 0.62, by - 0.28 * h),
                     (cx + lick * w * 0.5 + lean * 0.3, by - 0.58 * h)], w * 0.3, 0.2, w * 0.3, 0.3))
    return out


def fire_field(paths, tip_fade=0.3, clip=True, size=N):
    """The union of some tongues of fire and how hot each pixel is (0..1): hottest along each tongue's spine and
    at its root, cooler to its edge and tip."""
    union = np.zeros((size, size), bool)
    best = np.zeros((size, size))
    for path, r0, r1, rmax, peak in paths:
        m, f, e = tongue(path, r0, r1, rmax, peak, size)
        val = (1 - np.clip(e, 0, 1)) ** 0.8 - tip_fade * f
        best = np.where(m & (val > best), val, best)
        union |= m
    if clip:
        union = inside_disc(union)
    return union, np.clip(best, 0, 1)


def paint_fire(cv, m, hot, lo=0.42, gain=0.55, pal=EMBER):
    cv.put(m, bands(pal, lo + gain * hot, 0.0))


def fire_mask(cx, by, h, w, lean=0.0, lick=-1):
    return fire_field(fire_paths(cx, by, h, w, lean, lick))


def icon_hearthlight():
    cv = backing('prayer')
    # a flame resting in a bowl of gold, its light spreading round it
    mouth, _ = ellipse(CX, 21.0, 9.0, 2.7, N)
    belly, _ = ellipse(CX, 21.0, 9.0, 6.6, N)
    body = belly & (YS >= 21.0)
    fm, hot = fire_mask(CX, 20.6, 15.5, 8.2, 0.9, -1)
    halo(cv, fm.astype(float) + 0.6 * mouth, (255, 190, 70), 1.0, 3, outside=True)
    lit = (XS - CX) / 9.0
    cv.put(body, bands(GOLD, 0.62 - 0.3 * np.clip(lit + 0.2, -1, 1) - 0.12 * np.clip((YS - 23) / 4, 0, 1), 0.0))
    cv.put(mouth, bands(EMBER, 0.5, 0.0))
    cv.put(edge(mouth), GOLD[4])
    cv.put(body & edge(body) & (XS < CX), GOLD[5])
    paint_fire(cv, fm, hot, 0.5, 0.52)
    for (x, y) in ((6, 8), (26, 10)):
        twinkle(cv, x, y, 1, (255, 214, 140), WARM_WHITE)
    return seat(cv)


def icon_flare():
    cv = backing('prayer')
    # a gout of holy fire thrown from the censer: tongues fanned out to the right from a white-hot mouth
    org = (6.2, 16.0)
    spec = [(-34, 15.0, 1.5, 2.8), (34, 15.0, 1.5, 2.8), (-14, 19.4, 1.9, 3.5), (14, 19.4, 1.9, 3.5), (0, 21.4, 2.1, 3.4)]
    paths = []
    for ang, L, r0, rmax in spec:
        a = math.radians(ang)
        ex, ey = math.cos(a), math.sin(a)
        nx, ny = -ey, ex
        bend = 1.8 * (1 if ang >= 0 else -1) * (0.3 if ang == 0 else 1.0)
        paths.append(([org, (org[0] + ex * L * 0.35 - nx * bend * 0.4, org[1] + ey * L * 0.35 - ny * bend * 0.4),
                       (org[0] + ex * L * 0.7 + nx * bend, org[1] + ey * L * 0.7 + ny * bend),
                       (org[0] + ex * L, org[1] + ey * L)], r0, 0.3, rmax, 0.4))
    m, hot = fire_field(paths, 0.3)
    halo(cv, m, (255, 80, 24), 0.8, 3, outside=True)
    base = np.clip(1 - np.hypot(XS - org[0] - 1.0, (YS - org[1]) * 1.3) / 6.0, 0, 1)          # white-hot at the mouth
    hot = np.maximum(hot * 0.62, base)
    paint_fire(cv, m, hot, 0.2, 0.74)
    for (x, y) in ((27, 9), (28, 22), (29, 15)):
        cover_px(cv, x, y, EMBER[4], 1.0)
    return seat(cv)


def shield_pts(cx, top, bottom, half):
    """A heater shield: a gently bowed top, straight sides, tapering to a point."""
    h = bottom - top
    return [(cx - half, top + 1.3), (cx - half * 0.5, top + 0.3), (cx, top), (cx + half * 0.5, top + 0.3),
            (cx + half, top + 1.3), (cx + half, top + h * 0.42), (cx + half * 0.72, top + h * 0.7), (cx, bottom),
            (cx - half * 0.72, top + h * 0.7), (cx - half, top + h * 0.42)]


# a small flame, drawn by hand: r dark orange rim, o orange, y yellow, W white-hot
FLAME_S = ["...r...",
           "...o...",
           "..roy..",
           "..oyy..",
           ".royyo.",
           ".royWyo",
           "royWWyo",
           "royWWWo",
           "royWWWo",
           ".royWyo",
           "..rooo."]
FLAME_COLS = {'r': EMBER[2], 'o': EMBER[3], 'y': EMBER[4], 'W': EMBER[5], 'k': EMBER[0]}


def draw_flame(cv, rows, x0, y0, outline=True):
    mask = np.zeros((N, N), bool)
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch != '.':
                mask[y0 + j, x0 + i] = True
    if outline:
        ring = grow(mask, 1, True) & ~mask
        cv.put(ring, EMBER[0])
    draw(cv, rows, x0, y0, None, FLAME_COLS)
    return mask


def icon_vigil():
    cv = backing('prayer')
    pts = shield_pts(CX, 4.6, 28.0, 9.4)
    outer = poly(pts, N)
    c = (CX, 14.8)
    inner = poly([(c[0] + (x - c[0]) * 0.74, c[1] + (y - c[1]) * 0.74) for x, y in pts], N)
    halo(cv, outer, (255, 190, 80), 0.55, 2, outside=True)
    dx, dy = XS - c[0], YS - c[1]
    dist = np.maximum(np.hypot(dx, dy), 1e-6)
    lit = -(dx + dy) / (dist * ROOT2)
    ring = outer & ~inner
    cv.put(ring, bands(GOLD, 0.8 + 0.2 * lit, 0.0))
    cv.put(inner, bands(GOLD, 0.6 + 0.3 * np.clip(1 - dist / 9.0, 0, 1), 0.0))
    cv.put(edge(inner) & inner, GOLD[3])
    cv.put(edge(outer), GOLD[2])
    # the flame at its heart, ringed in dark red so it stands out of the gold
    draw_flame(cv, FLAME_S, 13, 10)
    return seat(cv)


def icon_wayflame():
    cv = backing('prayer')
    # a slow ember that seeks its foe: a white-hot head with a flame streaming behind it in a curl
    head = (20.8, 11.2)
    paths = [([head, (16.2, 14.8), (11.6, 18.8), (6.0, 26.6)], 3.2, 0.3, 3.5, 0.25),
             ([(head[0] - 2.0, head[1] - 1.5), (15.6, 9.6), (11.0, 11.4), (6.4, 15.8)], 1.8, 0.25, 2.0, 0.3),
             ([(head[0] + 0.2, head[1] + 2.2), (18.6, 17.4), (15.6, 22.0), (12.4, 27.0)], 1.8, 0.25, 2.0, 0.3)]
    fm, hot = fire_field(paths, 0.4)
    hd = (np.hypot(XS - head[0], YS - head[1]) <= 4.3)
    # little licks of flame off the head, away from the tail
    licks = [tongue([(head[0] + dx0, head[1] + dy0), (head[0] + dx1, head[1] + dy1)], 1.3, 0.2, 1.4, 0.3)[0]
             for (dx0, dy0, dx1, dy1) in ((0.5, -3.0, 1.4, -6.4), (2.6, -2.4, 4.6, -5.0), (3.4, -0.4, 6.2, -1.8))]
    allm = inside_disc(fm | hd | np.any(licks, axis=0))
    halo(cv, allm, (255, 150, 40), 0.9, 3, outside=True)
    paint_fire(cv, fm, hot * 0.9, 0.38, 0.55)
    head_all = inside_disc(hd | np.any(licks, axis=0))
    dh = np.hypot(XS - head[0], YS - head[1])
    cv.put(head_all, bands(EMBER, np.clip(0.52 + 0.4 * (1 - dh / 5.0), 0.45, 1.0), 0.0))
    cv.put(inside_disc(hd) & (dh <= 2.6), WARM_WHITE)
    for (x, y) in ((27, 20), (23, 25), (3, 14), (15, 27), (26, 7)):
        cover_px(cv, x, y, EMBER[4], 1.0)
    return seat(cv)


def icon_hallowed_ground():
    cv = backing('prayer')
    # a ring of hallowed ground: a bright gold ring on the ground with a sigil in it, small flames standing along it
    cy = 19.8
    ro, _ = ellipse(CX, cy, 12.3, 6.4, N)
    ri, _ = ellipse(CX, cy, 9.1, 3.9, N)
    ring = ro & ~ri
    fl = []
    for (x, base, h, w) in ((7.6, 18.4, 7.0, 3.2), (11.6, 15.0, 9.0, 3.4), (16.0, 13.8, 10.0, 3.8), (20.4, 15.0, 9.0, 3.4), (24.4, 18.4, 7.0, 3.2)):
        fl.append(fire_field(fire_paths(x, base, h, w, 0.3 if x > CX else -0.3, 0)))
    fm = np.any([f[0] for f in fl], axis=0)
    halo(cv, ro.astype(float) * 0.5 + fm, (255, 170, 60), 0.8, 3, outside=True)
    # the ground inside the ring, warm, with a four-pointed sigil lying on it
    glow = np.clip(1 - np.hypot((XS - CX) / 9.1, (YS - cy) / 3.9), 0, 1)
    cv.put(ri, bands(EMBER, 0.18 + 0.3 * glow, 0.0))
    sig = poly([(CX + (x - CX) * 0.72, cy + (y - CX) * 0.34) for x, y in star_poly(CX, CX, 11.0, 3.2, 4, -90)], N)
    cv.put(sig & ri, bands(EMBER, 0.95, 0.0))
    lit = -((XS - CX) * 0.7 + (YS - cy)) / 12.0
    cv.put(ring, bands(GOLD, 0.74 + 0.26 * np.clip(lit * 1.6, -1, 1), 0.0))
    cv.put(ring & edge(ro), GOLD[2])
    cv.put(ring & edge(ri), GOLD[2])
    # runes cut into the ring
    for ang in (20, 60, 100, 140, 180, 220, 260, 300, 340):
        a = math.radians(ang)
        x, y = CX + 10.7 * math.cos(a), cy + 5.15 * math.sin(a)
        cover_px(cv, int(x), int(y), GOLD[1], 1.0)
    for f_m, f_hot in fl:
        paint_fire(cv, f_m & ~ring, f_hot, 0.42, 0.56)
    return seat(cv)


RAY = [(150, 96, 40), (214, 150, 60), (255, 208, 110), (255, 232, 160), (255, 246, 208)]


def icon_dawnburst():
    cv = backing('prayer')
    # a burst of dawn light: rays all round a white-gold sun
    r, th = polar()
    rays = []
    n = 12
    for i in range(n):
        ang = -90 + i * 360 / n + 7.5
        long = (i % 2 == 0)
        L = 12.8 if long else 9.8
        a = math.radians(ang)
        p0 = (CX + 4.2 * math.cos(a), CX + 4.2 * math.sin(a))
        p1 = (CX + L * math.cos(a), CX + L * math.sin(a))
        rays.append((p0, p1, 1.7 if long else 1.3))
    allm = np.zeros((N, N), bool)
    for p0, p1, hw in rays:
        allm |= shard(cv, p0, p1, hw, RAY, 0.12, mask_only=True, base_hw=hw)
    halo(cv, allm | (r <= 6), (255, 190, 80), 0.8, 3, outside=True)
    for p0, p1, hw in rays:
        shard(cv, p0, p1, hw, RAY, 0.12, (0.62, 0.86, 0.34), (0.15, 0.9), streak_t=1.0, base_hw=hw)
    sun = r <= 5.4
    d = dist_in(sun, 6)
    cv.put(sun, bands(RAY, np.clip(0.55 + 0.12 * (d - 1), 0, 1.0), 0.0))
    cv.put(r <= 3.4, WARM_WHITE)
    cv.put(sun & edge(sun), RAY[1])
    return seat(cv)


# ================================================================ the scrolls

PARCH = PAL['parchment']
WAX_VIOLET = [(26, 10, 44), (62, 26, 104), (104, 52, 160), (150, 98, 208), (206, 164, 248)]
WAX_EMBER = [(50, 10, 10), (110, 24, 16), (170, 52, 22), (214, 96, 36), (250, 160, 70)]
CORD_VIOLET = [(22, 16, 48), (48, 36, 100), (82, 62, 156), (128, 104, 206), (178, 156, 240)]
CORD_GOLD = [(52, 34, 16), (104, 70, 28), (158, 112, 44), (206, 160, 70), (240, 206, 118)]

STAR_GLYPH = ["...X...",
              "...X...",
              "..XXX..",
              "XXXXXXX",
              "..XXX..",
              "...X...",
              "...X..."]
FLAME_GLYPH = ["..X..",
               ".XX..",
               ".XXX.",
               "XXXXX",
               "XXXXX",
               ".XXX."]


def seal(cv, cx, cy, r, wax, glyph_rows, glyph_col, glyph_deep):
    """A wax seal: a lumpy disc, a raised rim, the stamp pressed into it, lit from the upper left."""
    ang = np.arctan2(YS - cy, XS - cx)
    d = np.hypot(XS - cx, YS - cy)
    wob = 0.22 * np.sin(ang * 3 + 0.8) + 0.18 * np.sin(ang * 5 + 2.0)
    m = d <= r + wob
    dome = np.sqrt(np.clip(1 - (d / (r + 0.8)) ** 2, 0, 1))
    k = light(dome * 3.0, 1.0)
    t = 0.56 + 1.2 * k
    t = np.where(d > r - 1.1, t - 0.12, t)           # the raised rim's outer shade
    cv.put(m, bands(wax, t, 0.0))
    # the stamp: pressed in, so its upper-left edge is dark and its lower-right edge catches the light
    gh, gw = len(glyph_rows), len(glyph_rows[0])
    gx0, gy0 = int(round(cx - gw / 2.0)), int(round(cy - gh / 2.0))
    gm = np.zeros((N, N), bool)
    for j, row in enumerate(glyph_rows):
        for i, ch in enumerate(row):
            if ch == 'X':
                gm[gy0 + j, gx0 + i] = True
    up = np.roll(np.roll(gm, 1, 0), 1, 1) | np.roll(gm, 1, 0) | np.roll(gm, 1, 1)     # up-left of the stamp
    cv.put(up & ~gm & m, glyph_deep)
    cv.put(gm, glyph_col)
    return m, gm


def scroll(cord, wax, glyph, glyph_col, glyph_deep):
    cv = Canvas(N)
    p0, p1 = (3.2, 21.2), (28.4, 11.4)
    u, v, L = frame(p0, p1)
    R = 4.5
    body = (u >= 0) & (u <= L) & (np.abs(v) <= R)
    left = (u < 0) & (u >= -1.6) & (((u / 1.6) ** 2 + (v / R) ** 2) <= 1.0)
    face_ell = (((u - L) / 2.3) ** 2 + (v / R) ** 2) <= 1.0
    roll = body | left
    # the paper: a cylinder lit from the upper left, with grain
    grain = vnoise(N, N, 2.2, 61) - 0.5
    vv = np.clip(v / R, -1, 1)
    t = 0.62 + 0.3 * vv + 0.1 * (vv > 0.15) * (vv < 0.55) + 0.06 * (vv < -0.8) + 0.1 * grain
    t = np.where(left, t - 0.22, t)
    cv.put(roll, bands(PARCH, t, 0.0))
    # the open end of the roll: the paper wound round, a darker spiral on a pale face
    cut = face_ell & (u >= L - 2.3)
    rho = np.sqrt(((u - L) / 2.3) ** 2 + (v / R) ** 2)
    phi = np.arctan2(v / R, (u - L) / 2.3)
    spiral = ((rho * 2.2 + phi / (2 * math.pi)) % 1.0) < 0.3
    cv.put(cut, bands(PARCH, np.where(spiral, 0.3, 0.86), 0.0))
    # the cord: a turn round the roll near its left end, drawn in, with a knot below and two tails
    cu = 0.2 * L
    band = (np.abs(u - cu) <= 1.3) & (np.abs(v) <= R + 0.05) & roll
    cv.put(band, bands(cord, 0.5 + 0.3 * vv + 0.12 * (vv > 0.15), 0.0))
    cv.put(band & (np.abs(u - cu) > 0.75), bands(cord, 0.3 + 0.3 * vv, 0.0))
    kx, ky = 9.8, 20.8
    for path in ([(kx, ky), (kx - 1.0, 24.6), (kx - 3.4, 28.6)], [(kx, ky), (kx + 1.6, 24.4), (kx + 3.8, 29.0)]):
        tm, f, e = tongue(path, 1.0, 0.65, 1.0, 0.4)
        cv.put(tm, bands(cord, 0.66 + 0.2 * (XS < path[-1][0] + 2) - 0.16 * f + 0.1 * (e < 0.5), 0.0))
    kd = np.hypot(XS - kx, YS - ky) <= 1.8
    cv.put(kd, bands(cord, 0.62, 0.0))
    cv.put(kd & (XS < kx), cord[4])
    # the wax seal pressed on the roll's face, at the middle
    seal(cv, 18.6, 15.4, 4.6, wax, glyph, glyph_col, glyph_deep)
    return cv


def sorcery_scroll():
    img = finish(scroll(CORD_VIOLET, WAX_VIOLET, STAR_GLYPH, (232, 214, 255), WAX_VIOLET[1]))
    cv = Canvas.of(img)
    for (x, y, a) in ((7, 7, 0.9), (22, 4, 0.8), (28, 24, 0.6)):
        twinkle(cv, x, y, 1, (176, 140, 250), (244, 230, 255), a)
    return cv.image()


def prayer_scroll():
    img = finish(scroll(CORD_GOLD, WAX_EMBER, FLAME_GLYPH, (255, 214, 110), WAX_EMBER[1]))
    cv = Canvas.of(img)
    for (x, y, a) in ((7, 7, 0.9), (22, 4, 0.8), (28, 24, 0.6)):
        twinkle(cv, x, y, 1, (255, 190, 90), (255, 244, 208), a)
    return cv.image()


# ================================================================ the spell projectiles

def emit(I, stops):
    """Turn a field of light [I] into a glowing sprite with a soft edge: [stops] is [(threshold, colour, alpha), ...]
    from the dimmest up; each pixel takes the last stop its light reaches. No outline, transparent round it."""
    cv = Canvas(N)
    I = np.asarray(I, float)
    for thr, col, a in stops:
        m = I >= thr
        cv.rgb[m] = col
        cv.a[m] = a
    return cv


def spark_lines(cx, cy, specs):
    """A field of thin jagged lines of light: [(angle_deg, r0, r1, jitter)] from the centre outward, fading out."""
    I = np.zeros((N, N))
    for ang, r0, r1, jit in specs:
        pts = []
        steps = 4
        for i in range(steps + 1):
            f = i / steps
            r = r0 + (r1 - r0) * f
            a = math.radians(ang + jit * (1 if i % 2 else -1) * (i > 0) * 1.0)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        d, along = path_dist(pts, N)
        total = max(sum(math.hypot(x1 - x0, y1 - y0) for (x0, y0), (x1, y1) in zip(pts, pts[1:])), 1e-6)
        I = np.maximum(I, np.clip(1.0 - d / 0.62, 0, 1) * (0.95 - 0.55 * along / total))
    return I


ARC_STOPS = [(0.05, (96, 44, 176), 0.20), (0.12, (118, 62, 208), 0.38), (0.22, (146, 90, 236), 0.62),
             (0.38, (180, 128, 252), 0.86), (0.58, (214, 178, 255), 1.0), (0.82, (246, 236, 255), 1.0),
             (1.05, (255, 255, 255), 1.0)]


def proj_arc_bolt():
    r = np.hypot(XS - CX, YS - CX)
    I = 1.3 * np.exp(-(r / 3.0) ** 2) + 0.66 * np.exp(-(r / 5.4) ** 2) + 0.2 * np.exp(-(r / 9.0) ** 2)
    sp = spark_lines(CX, CX, [(-68, 5.0, 14.6, 10), (22, 5.0, 12.4, -9), (118, 5.0, 14.2, 10), (172, 5.0, 12.2, -10),
                              (222, 5.0, 14.8, 9), (306, 5.0, 12.8, -9)])
    I = np.maximum(I, np.where(sp > 0.05, 0.5 + sp * 0.6, 0))
    # a few loose sparks
    for (x, y) in ((4, 8), (28, 23), (25, 4), (5, 27)):
        I[y, x] = max(I[y, x], 0.75)
    return emit(I, ARC_STOPS).image()


def glow_of(mask, radius, strength):
    """A soft halo field round a mask: 0 inside it, falling off outside."""
    g = blur(mask.astype(float), radius) * strength
    return g * (~mask)


def proj_splinter():
    p0, p1 = (4.6, 27.0), (27.6, 4.4)
    u, v, L = frame(p0, p1)
    brk = 1.2 + 2.2 * np.clip((v / 3.2 + 1) / 2, 0, 1)                   # a broken, slanted butt
    m = shard(None, p0, p1, 3.3, AMETHYST, 0.6, mask_only=True, base_hw=2.0) & (u >= brk)
    I = glow_of(m, 3, 1.5)
    cv = emit(I, [(0.05, (96, 44, 176), 0.18), (0.12, (120, 66, 210), 0.34), (0.24, (150, 96, 238), 0.55),
                  (0.4, (184, 134, 252), 0.78)])
    f = v / np.maximum(np.where(u < 0.6 * L, 2.0 + 1.3 * np.clip(u / (0.6 * L), 0, 1), 3.3 * np.clip((L - u) / (0.4 * L), 0, 1)), 0.35)
    t = np.where(f > 0.34, 0.9, np.where(f > -0.34, 0.62, 0.34))
    streak = m & (f > 0.34) & (u > 0.35 * L) & (u < 0.88 * L)
    t = np.where(streak, 1.0, t)
    cv.put(m, bands(AMETHYST, t, 0.0))
    cv.put(m & (u > 0.94 * L), AMETHYST[5])
    cv.put(m & (np.abs(u - brk) < 0.8) & (t < 0.9), AMETHYST[2])
    for (x, y) in ((9, 8), (26, 23), (5, 18)):
        cover_px(cv, x, y, (196, 150, 250), 0.9)
    return cv.image()


def proj_rime_lance():
    p0, p1 = (5.4, 27.0), (28.4, 4.0)
    u, v, L = frame(p0, p1)
    ux, uy = (p1[0] - p0[0]) / L, (p1[1] - p0[1]) / L
    nx, ny = uy, -ux
    shaft = shard(None, p0, p1, 3.0, ICE, 0.66, mask_only=True, base_hw=1.2)
    allm = shaft.copy()
    barbs = []
    for t0, ln, sgn in ((0.27, 8.0, 1), (0.27, 8.0, -1), (0.49, 6.4, 1), (0.49, 6.4, -1), (0.69, 4.6, 1), (0.69, 4.6, -1)):
        bx, by = p0[0] + ux * L * t0, p0[1] + uy * L * t0
        tx, ty = bx - ux * ln * 0.4 + sgn * nx * ln * 0.9, by - uy * ln * 0.4 + sgn * ny * ln * 0.9
        barbs.append(((bx, by), (tx, ty)))
        allm |= shard(None, (bx, by), (tx, ty), 1.5, ICE, 0.5, mask_only=True, base_hw=0.6)
    I = glow_of(allm, 2, 1.3)
    cv = emit(I, [(0.06, (70, 130, 210), 0.16), (0.14, (96, 164, 236), 0.32), (0.28, (130, 196, 250), 0.54),
                  (0.46, (180, 224, 255), 0.78)])
    for (bx, by), (tx, ty) in barbs:
        shard(cv, (bx, by), (tx, ty), 1.5, ICE, 0.5, (0.86, 0.62, 0.38), (0.5, 0.8), base_hw=0.6)
    m = shard(cv, p0, p1, 3.0, ICE, 0.66, (0.92, 0.68, 0.4), (0.4, 0.92), base_hw=1.2)
    cv.put(m & (v > 2.0) & (u > 0.15 * L) & (u < 0.9 * L), (236, 248, 255))
    cv.put(m & (u > 0.93 * L), (255, 255, 255))
    for (x, y) in ((8, 6), (25, 26), (28, 14), (4, 18)):
        cover_px(cv, x, y, (200, 232, 255), 0.9)
    return cv.image()


def proj_undertow():
    r, th = polar()
    R = 10.6
    body = r <= R
    phase = th / (2 * math.pi) * 3 + r * 0.16
    ph = phase - np.floor(phase)
    t = np.where(ph < 0.14, 0.62, np.where(ph < 0.5, 0.4 - 0.1 * (ph - 0.14) / 0.36, 0.2))
    t = np.where(r < 3.0, 0.08, t)
    # the glow beyond the rim
    out = np.clip(r - R, 0, None)
    I = np.where(body, 0.0, 0.75 * np.exp(-(out / 1.7) ** 2))
    cv = emit(I, [(0.06, (90, 40, 170), 0.2), (0.16, (118, 62, 208), 0.4), (0.34, (150, 96, 238), 0.7)])
    cv.put(body, bands(AMETHYST, t, 0.0))
    # the bright rim, brightest where the arms meet it
    rim_t = np.where(ph < 0.5, 0.95, 0.7)
    rim = body & (r > R - 1.3)
    cv.put(rim, bands(AMETHYST, rim_t, 0.0))
    cv.put(r <= 1.6, (6, 2, 14))
    cv.px(16, 16, AMETHYST[3])
    return cv.image()


STAR_STOPS = [(0.05, (150, 100, 40), 0.18), (0.12, (200, 140, 60), 0.38), (0.25, (240, 180, 80), 0.64),
              (0.42, (255, 214, 120), 0.88), (0.62, (255, 236, 170), 1.0), (0.85, (255, 248, 220), 1.0),
              (1.05, (255, 255, 250), 1.0)]


def arm(along, perp, L, w0, w1, power=0.8):
    """A thin tapering ray of light: [along] its length, [perp] across it; w0 wide at the root, w1 at the tip."""
    f = np.clip(1 - np.abs(along) / L, 0, 1)
    w = w1 + (w0 - w1) * f
    return f ** power * np.exp(-(perp / np.maximum(w, 0.05)) ** 2) * (np.abs(along) < L)


def proj_star():
    dx, dy = XS - CX, YS - CX
    r = np.hypot(dx, dy)
    I = 1.4 * np.exp(-(r / 2.3) ** 2) + 0.5 * np.exp(-(r / 5.0) ** 2) + 0.18 * np.exp(-(r / 9.0) ** 2)
    # the long arms along the axes, the short ones on the diagonals
    I = np.maximum(I, 1.25 * arm(dx, dy, 15.0, 1.15, 0.25))
    I = np.maximum(I, 1.25 * arm(dy, dx, 15.0, 1.15, 0.25))
    ex, ey = (dx + dy) / ROOT2, (dy - dx) / ROOT2
    I = np.maximum(I, 0.95 * arm(ex, ey, 8.8, 0.85, 0.2))
    I = np.maximum(I, 0.95 * arm(ey, ex, 8.8, 0.85, 0.2))
    return emit(I, STAR_STOPS).image()


def proj_wayflame():
    # a wisp of gold-white flame, upright, its tip curling, a thin lick beside it
    paths = fire_paths(CX, 22.4, 18.0, 8.6, 2.2, -1)
    fm, hot = fire_field(paths, 0.2, clip=False)
    lick = tongue([(CX + 4.2, 21.6), (CX + 6.0, 17.2), (CX + 5.2, 13.2)], 1.7, 0.2, 1.9, 0.3)[0]
    allm = fm | lick
    I = glow_of(allm, 3, 1.4)
    cv = emit(I, [(0.05, (150, 70, 20), 0.18), (0.12, (200, 110, 30), 0.36), (0.24, (240, 160, 50), 0.6),
                  (0.4, (255, 200, 90), 0.82)])
    t = 0.42 + 0.58 * hot
    cv.put(fm, bands(EMBER, t, 0.0))
    cv.put(lick & ~fm, bands(EMBER, 0.55, 0.0))
    core = fm & (np.hypot(XS - 16.4, YS - 17.2) < 2.5)
    cv.put(core, WARM_WHITE)
    for (x, y) in ((8, 8), (25, 6), (5, 19), (27, 21)):
        cover_px(cv, x, y, EMBER[4], 0.95)
    return cv.image()


# ================================================================ the arcane mote (a 16 x 16 particle)

def mote(i):
    """A violet-white glimmer: a four-pointed sparkle that shrinks over four frames to a dot."""
    cv = Canvas(16)
    cx = cy = 8
    arms = (7, 5, 3, 0)[i]
    glow = (2, 2, 1, 1)[i]
    # a soft violet glow round the heart
    for dy in range(-glow, glow + 1):
        for dx in range(-glow, glow + 1):
            d = math.hypot(dx, dy)
            if d <= glow + 0.4:
                cover_px(cv, cx + dx, cy + dy, (130, 80, 220), 0.34 if i < 3 else 0.28)
    stops = [(250, 244, 255), (230, 208, 255), (200, 164, 250), (168, 120, 240), (140, 92, 224), (118, 72, 204), (100, 58, 184)]
    for k in range(1, arms + 1):
        col = stops[min(k, len(stops) - 1)]
        a = max(0.3, 1.0 - 0.1 * k) if k < arms else max(0.3, 0.55 - 0.05 * k)
        for dx, dy in ((k, 0), (-k, 0), (0, k), (0, -k)):
            cover_px(cv, cx + dx, cy + dy, col, a)
    if i == 0:
        for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            cover_px(cv, cx + dx, cy + dy, (200, 164, 250), 0.85)
        for dx, dy in ((2, 2), (-2, 2), (2, -2), (-2, -2)):
            cover_px(cv, cx + dx, cy + dy, (140, 92, 224), 0.45)
    if i == 1:
        for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
            cover_px(cv, cx + dx, cy + dy, (190, 150, 248), 0.7)
    cv.px(cx, cy, (255, 252, 255), 1.0)
    if i == 3:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            cover_px(cv, cx + dx, cy + dy, (190, 150, 248), 0.5)
    return cv.image()


def arcane_mote_0():
    return mote(0)


def arcane_mote_1():
    return mote(1)


def arcane_mote_2():
    return mote(2)


def arcane_mote_3():
    return mote(3)


# ================================================================ the Vigil effect icon (36 x 36)

def flame_rows(h, wmax, lean=0.0, cx=0.0):
    """A teardrop flame as (left, right) edges per pixel row, top (the tip) to bottom: it swells to its full width
    low down and rounds off at the base, the tip leaning [lean] pixels."""
    rows = []
    for j in range(h):
        t = (j + 0.5) / h
        if t < 0.7:
            w = wmax / 2 * (t / 0.7) ** 1.2
        else:
            w = wmax / 2 * math.sqrt(max(0.0, 1 - ((t - 0.7) / 0.3) ** 2)) * 0.97 + 0.4 * (1 - t)
        c = cx + lean * (1 - t) ** 2
        rows.append((c - w, c + w))
    return rows


def paint_flame_rows(cv, rows, x0, y0, n, hot_row=0.62):
    """Paint the rows of a flame at (x0, y0): a dark red line round it, then orange, yellow and a white-hot heart."""
    xs_ = np.arange(n) + 0.5
    mask = np.zeros((n, n), bool)
    edge_d = np.zeros((n, n))
    h = len(rows)
    for j, (l, r) in enumerate(rows):
        y = y0 + j
        if not 0 <= y < n:
            continue
        inside = (xs_ >= x0 + l) & (xs_ <= x0 + r)
        mask[y] |= inside
        edge_d[y] = np.where(inside, np.minimum(xs_ - (x0 + l), (x0 + r) - xs_) + 0.5, 0)
    ring = grow(mask, 1) & ~mask
    cv.put(ring, EMBER[0])
    jj = (np.arange(n)[:, None] - y0) / max(h - 1, 1)
    heat = np.clip(edge_d / 3.4, 0, 1) * (0.6 + 0.4 * np.clip(1.0 - np.abs(jj - hot_row) / 0.5, 0, 1))
    t = 0.36 + 0.66 * heat
    cv.put(mask, _bands(EMBER, t, 0.0))
    return mask


def vigil_effect():
    n = 36
    xs, ys = grid(n)
    cv = Canvas(n)
    pts = shield_pts(18.0, 2.6, 33.2, 12.6)
    outer = poly(pts, n)
    c = (18.0, 17.0)
    inner = poly([(c[0] + (x - c[0]) * 0.76, c[1] + (y - c[1]) * 0.76) for x, y in pts], n)
    dx, dy = xs - c[0], ys - c[1]
    dist = np.maximum(np.hypot(dx, dy), 1e-6)
    lit = -(dx + dy) / (dist * ROOT2)

    def tb(pal, t):
        return _bands(pal, np.broadcast_to(np.asarray(t, float), (n, n)), 0.0)
    ring = outer & ~inner
    cv.put(ring, tb(GOLD, 0.8 + 0.2 * lit))
    cv.put(inner, tb(GOLD, 0.56 + 0.4 * np.clip(1 - dist / 11.0, 0, 1)))
    cv.put(edge(inner) & inner, GOLD[3])
    cv.put(edge(outer), GOLD[2])
    # the flame at its heart: a hearth flame, ringed in dark red so it stands out of the gold
    paint_flame_rows(cv, flame_rows(16, 9.0, 1.2), 18.0 - 0.0, 9, n)
    outline(cv, 0.8)
    return cv.image()


# ================================================================ previews

def mark(img, scale, s, w, col):
    x, y = at_px(s, w)
    d = ImageDraw.Draw(img)
    cx, cy = x * scale, y * scale
    d.line([(cx - 5, cy), (cx + 5, cy)], fill=col, width=1)
    d.line([(cx, cy - 5), (cx, cy + 5)], fill=col, width=1)


def marked(img, grip, head, scale=12, bg=(40, 38, 46)):
    big = Image.new('RGBA', (N * scale, N * scale), bg + (255,))
    big.alpha_composite(img.resize((N * scale, N * scale), Image.NEAREST))
    mark(big, scale, grip, 0, (80, 220, 255, 255))
    mark(big, scale, head, 0, (255, 80, 220, 255))
    return big


CATALYSTS = {
    'amethyst_staff': (amethyst_staff, weaponkit.STAFF, 'staff'),
    'ender_staff': (ender_staff, weaponkit.STAFF, 'staff'),
    'echo_staff': (echo_staff, weaponkit.STAFF, 'staff'),
    'iron_censer': (iron_censer, weaponkit.CENSER, 'censer'),
    'gilded_censer': (gilded_censer, weaponkit.CENSER, 'censer'),
    'blazing_censer': (blazing_censer, weaponkit.CENSER, 'censer'),
}
MARKS = {'staff': (12.0, 43.0), 'censer': (9.0, 32.0)}


def hotbar(items, scale=3, size=32, back=(52, 50, 60), gap=4):
    """The items as they sit in a hotbar: each in its slot at [size] px (32 is the 32x texture drawn 1:1; 16 is the
    same drawn at half size), the whole strip magnified by [scale] with nearest neighbour."""
    slot = size + 8
    out = Image.new('RGBA', (len(items) * (slot + gap) + gap, slot + 2 * gap), (24, 22, 30, 255))
    for i, im in enumerate(items):
        x = gap + i * (slot + gap)
        d = ImageDraw.Draw(out)
        d.rectangle([x, gap, x + slot - 1, gap + slot - 1], fill=back + (255,), outline=(86, 82, 96, 255))
        small = im if im.width == size else im.resize((size, size), Image.BOX)
        out.alpha_composite(small, (x + 4, gap + 4))
    return out.resize((out.width * scale, out.height * scale), Image.NEAREST)


SCROLLS = {'sorcery_scroll': sorcery_scroll, 'prayer_scroll': prayer_scroll}
SPELL_ICONS = {
    'arc_bolt': icon_arc_bolt, 'splinter_hail': icon_splinter_hail, 'rime_lance': icon_rime_lance,
    'undertow': icon_undertow, 'flicker': icon_flicker, 'starbreak': icon_starbreak,
    'hearthlight': icon_hearthlight, 'flare': icon_flare, 'vigil': icon_vigil,
    'wayflame': icon_wayflame, 'hallowed_ground': icon_hallowed_ground, 'dawnburst': icon_dawnburst,
}
PROJECTILES = {
    'arc_bolt': proj_arc_bolt, 'splinter': proj_splinter, 'rime_lance': proj_rime_lance,
    'undertow': proj_undertow, 'star': proj_star, 'wayflame': proj_wayflame,
}
MOTES = [arcane_mote_0, arcane_mote_1, arcane_mote_2, arcane_mote_3]


def flat_model(texture):
    """An item model json for a flat sprite (a scroll, a projectile): the generated parent, one layer."""
    return {'parent': 'minecraft:item/generated', 'textures': {'layer0': texture}}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def save_png(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print(path)


def write_catalysts(assets, made):
    for name, (paint, geo, kind) in CATALYSTS.items():
        save_png(made[name], os.path.join(assets, 'textures', 'item', name + '.png'))
        write_json(os.path.join(assets, 'models', 'item', name + '.json'), weaponkit.model('guildmark:item/' + name, geo))
        write_json(os.path.join(assets, 'items', name + '.json'),
                   {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}})


def write_spell_art(assets, made):
    """The scrolls, the spell icons, the projectiles, the arcane mote and the Vigil icon, with their models and
    definitions."""
    for name in SCROLLS:
        save_png(made['scrolls'][name], os.path.join(assets, 'textures', 'item', name + '.png'))
        write_json(os.path.join(assets, 'models', 'item', name + '.json'), flat_model('guildmark:item/' + name))
        write_json(os.path.join(assets, 'items', name + '.json'),
                   {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}})
    for sid in SPELL_ICONS:
        save_png(made['icons'][sid], os.path.join(assets, 'textures', 'gui', 'sprites', 'spell', sid + '.png'))
    for kind in PROJECTILES:
        save_png(made['projectiles'][kind], os.path.join(assets, 'textures', 'item', 'spell', kind + '.png'))
        write_json(os.path.join(assets, 'models', 'item', 'spell', kind + '.json'), flat_model('guildmark:item/spell/' + kind))
    for i, img in enumerate(made['motes']):
        save_png(img, os.path.join(assets, 'textures', 'particle', 'arcane_mote_%d.png' % i))
    write_json(os.path.join(assets, 'particles', 'arcane_mote.json'),
               {'textures': ['guildmark:arcane_mote_%d' % i for i in range(len(MOTES))]})
    save_png(made['vigil'], os.path.join(assets, 'textures', 'mob_effect', 'vigil.png'))


def catalyst_preview(made, previews):
    tiles = [marked(made[name], *MARKS[kind], scale=12) for name, (paint, geo, kind) in CATALYSTS.items()]
    w = tiles[0].width
    out = Image.new('RGBA', (3 * w + 40, 2 * w + 30), (30, 28, 36, 255))
    for i, t in enumerate(tiles):
        out.alpha_composite(t, (10 + (i % 3) * (w + 10), 10 + (i // 3) * (w + 10)))
    out.save(os.path.join(previews, 'catalysts.png'))
    imgs = [made[n] for n in CATALYSTS]
    a = hotbar(imgs, 3, 32)
    b = hotbar(imgs, 6, 16)
    sh = Image.new('RGBA', (max(a.width, b.width), a.height + b.height), (24, 22, 30, 255))
    sh.alpha_composite(a, (0, 0))
    sh.alpha_composite(b, (0, a.height))
    sh.save(os.path.join(previews, 'catalysts_hotbar.png'))


DARK, LIGHT = (40, 38, 46), (214, 208, 196)


def everything_sheet(made, bg, scale=5):
    """Every texture the script makes, scaled up with nearest neighbour on the one backdrop."""
    imgs, labels = [], []
    for name in CATALYSTS:
        imgs.append(made[name]); labels.append(name)
    for name in SCROLLS:
        imgs.append(made['scrolls'][name]); labels.append(name)
    for sid in SPELL_ICONS:
        imgs.append(made['icons'][sid]); labels.append('spell/' + sid)
    for kind in PROJECTILES:
        imgs.append(made['projectiles'][kind]); labels.append('projectile/' + kind)
    for i, im in enumerate(made['motes']):
        imgs.append(im); labels.append('arcane_mote_%d' % i)
    imgs.append(made['vigil']); labels.append('mob_effect/vigil')
    out = sheet(imgs, scale, 8, bg, 8, [''] * len(imgs))
    # the labels are written here, so they stay readable on a light backdrop too
    d = ImageDraw.Draw(out)
    cw = max(i.width for i in imgs) * scale
    ch = max(i.height for i in imgs) * scale
    ink = (56, 52, 62, 255) if sum(bg) > 400 else (220, 214, 200, 255)
    for i, label in enumerate(labels):
        d.text((8 + (i % 8) * (cw + 8), 8 + (i // 8) * (ch + 8 + 14) + ch + 1), label, fill=ink)
    return out


def icon_check(made, previews):
    """The spell icons as the HUD draws them: 1:1 (GUI scale 2, a 16 px slot), and at 16 x 16 (box and nearest
    sampled), each in a slot, magnified for looking at."""
    ids = list(SPELL_ICONS)
    rows = [[made['icons'][i] for i in ids]]
    rows.append([made['icons'][i].resize((16, 16), Image.BOX) for i in ids])
    rows.append([made['icons'][i].resize((16, 16), Image.NEAREST) for i in ids])
    scales = (4, 8, 8)
    pad = 6
    W = len(ids) * (32 * 4 + pad) + pad
    H = sum(r[0].height * s + pad for r, s in zip(rows, scales)) + pad
    out = Image.new('RGBA', (W, H), (52, 50, 60, 255))
    y = pad
    for r, s in zip(rows, scales):
        for i, im in enumerate(r):
            tile = Image.new('RGBA', im.size, (52, 50, 60, 255))
            tile.alpha_composite(im)
            big = tile.resize((im.width * s, im.height * s), Image.NEAREST)
            out.alpha_composite(big, (pad + i * (32 * 4 + pad), y))
        y += r[0].height * s + pad
    out.save(os.path.join(previews, 'spell_icons_check.png'))


def main():
    assets = sys.argv[1]
    previews = sys.argv[2] if len(sys.argv) > 2 else None
    made = {name: paint() for name, (paint, geo, kind) in CATALYSTS.items()}
    write_catalysts(assets, made)
    if previews:
        os.makedirs(previews, exist_ok=True)
        catalyst_preview(made, previews)
    made['scrolls'] = {name: fn() for name, fn in SCROLLS.items()}
    made['icons'] = {sid: fn() for sid, fn in SPELL_ICONS.items()}
    made['projectiles'] = {kind: fn() for kind, fn in PROJECTILES.items()}
    made['motes'] = [fn() for fn in MOTES]
    made['vigil'] = vigil_effect()
    write_spell_art(assets, made)
    if previews:
        everything_sheet(made, DARK).save(os.path.join(previews, 'everything_dark.png'))
        everything_sheet(made, LIGHT).save(os.path.join(previews, 'everything_light.png'))
        icon_check(made, previews)


if __name__ == '__main__':
    main()
