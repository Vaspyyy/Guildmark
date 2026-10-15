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
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, poly, seg_dist, path_dist, disc,
                   ellipse, rect, blur, edge, grow, dist_in, light, rim, outline, glint, sheet, bevel, paste)
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


def main():
    assets = sys.argv[1]
    previews = sys.argv[2] if len(sys.argv) > 2 else None
    made = {name: paint() for name, (paint, geo, kind) in CATALYSTS.items()}
    write_catalysts(assets, made)
    if previews:
        os.makedirs(previews, exist_ok=True)
        catalyst_preview(made, previews)


if __name__ == '__main__':
    main()
