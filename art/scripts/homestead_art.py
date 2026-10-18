"""The homestead's art: the Homestead Charter and the builders' hammer, saw and mallet; the Charter Post (its post
and cap, the flagpole and its brass finial, the ledger box, and the lantern on its iron arm with its flame
flickering), the supply crate and the shipping crate; and the textures the client draws with (client/homestead):
the boundary's curtain of light, the glowing marks on the ground, the pennant's cloth and the lantern's glow.

    python3 -B art/scripts/homestead_art.py src/main/resources/assets/guildmark [preview-dir]

Writes the textures (32x, the HD kit's), the block models and blockstates, the item models and item definitions.
Every block is modelled facing south (the road at +z, the plot behind it) and turned by its blockstate, as
CharterPostRenderer turns the pennant: the lantern hangs at +x, 5 pixels out from the post's middle and 8.5 up
(its LANTERN_X and LANTERN_Y), the pennant flies from the pole's top toward -x. With a preview directory it also
writes homestead_art.png (the models from two sides, the items and the textures) and charter_post.gif (the post
through a day: the pennant flying, the lantern lit at dusk). All randomness is seeded: a rerun gives the same files.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, fbm, rand, tone, mix, ramp, bands, poly, seg_dist, disc,
                   ellipse, rect, blur, grow, dist_in, light, rim, outline, glint, sheet)

T = 32
CL = (0, 0, 0, 0)

OAK = [(34, 24, 20), (60, 42, 30), (90, 64, 42), (122, 90, 58), (152, 116, 76), (182, 146, 98)]
PINE = [(52, 36, 26), (92, 66, 42), (132, 98, 62), (170, 130, 84), (202, 164, 110), (226, 196, 144)]
ASH = [(62, 48, 36), (104, 84, 62), (146, 122, 92), (184, 160, 124), (212, 192, 154), (234, 220, 188)]
INSIDE = (18, 13, 12)
NSEW = ("north", "south", "east", "west")


# ================================================================ wood

def grain_t(w, h, seed, vertical=True, freq=1.9, warp=5.0):
    """The tone of plain wood: streaks running along the grain, wandering a little, with a fine fibre over them."""
    xs, ys = grid(w, h)
    across = xs if vertical else ys
    wv = fbm(w, h, 10, seed) * warp
    lines = np.sin((across + wv) * freq) * 0.5 + 0.5
    fine = vnoise(w, h, 2, seed + 7) - 0.5
    long = fbm(w, h, 6, seed + 3) - 0.5
    return 0.5 + 0.16 * (lines - 0.5) + 0.06 * fine + 0.12 * long


def knot(t, cx, cy, rx, ry):
    """A knot in the wood: a dark eye with the grain swirling round it."""
    xs, ys = grid(t.shape[1], t.shape[0])
    d = np.hypot((xs - cx) / rx, (ys - cy) / ry)
    t = t - 0.32 * np.exp(-d * d * 1.6)
    return t + 0.06 * np.sin(d * 6.0) * np.exp(-d * 0.8)


def boards(w, h, pal, seed, size=8, vertical=False, offset=0.0, lift=0.0, gap=0, nails=True, inset=2):
    """Boards side by side: each its own tone with the grain along it, a lit edge and a dark seam between, and a
    nail near each end. gap leaves that many pixels open between the boards, dark as the inside of a crate."""
    xs, ys = grid(w, h)
    across = (xs if vertical else ys) - offset
    row = np.floor(across / size)
    within = across - row * size
    t = grain_t(w, h, seed, vertical=vertical)
    shade = np.random.default_rng(seed).uniform(-0.09, 0.09, 64)
    t = t + lift + shade[row.astype(int) % 64]
    t = np.where(within < 1, t + 0.12, t)
    t = np.where(within >= size - 1 - gap, t - 0.3, t)
    cv = Canvas(w, h)
    solid = within < size - gap if gap else np.ones((h, w), bool)
    cv.put(solid, bands(pal, t, 0.3))
    if gap:
        cv.put(~solid, INSIDE)
        cv.put(~solid & (within < size - gap + 1), (34, 24, 20))
    if nails:
        length = h if vertical else w
        rows = int(math.ceil(((w if vertical else h) - offset) / size)) + 1
        for r in range(-1, rows):
            mid = int(offset + r * size + (size - gap) / 2 - 0.5)
            for along in (inset, length - inset - 1):
                x, y = (mid, along) if vertical else (along, mid)
                cv.px(x, y, PAL['iron'][1])
                cv.px(x - 1 if vertical else x, y if vertical else y - 1, PAL['iron'][4])
    return cv


def stencil(cv, mask, col, strength, seed):
    """Paint stencilled on the boards and worn since: patchy, thin where the grain stood proud."""
    wear = fbm(cv.w, cv.h, 3, seed) + 0.3 * rand(cv.w, cv.h, seed + 1)
    cv.over(mask & (wear > 0.36), col, strength)


# ================================================================ the Charter Post

def post_wood(seed=3):
    """The post's sides: weathered dark oak with the grain running up it, a crack, two iron bands with rivets."""
    xs, ys = grid(T)
    t = grain_t(T, T, seed, vertical=True, freq=1.7) - 0.04
    t = knot(t, 9.5, 18.5, 1.6, 2.6)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(OAK, t, 0.3))
    # A long check down the grain
    for y in range(9, 24):
        x = 22 + int(round(math.sin(y * 0.5) * 0.6))
        cv.px(x, y, OAK[0])
        cv.px(x + 1, y, OAK[3])
    # Weathered grey creeping up from the foot
    cv.over(ys > 27, (96, 92, 88), np.clip((ys - 27) / 8, 0, 0.45))
    for y0 in (5, 26):
        band = rect(0, y0, T, y0 + 2, T)
        cv.put(band, bands(PAL['iron'], 0.55 - 0.3 * (ys - y0 - 0.5) + 0.06 * (vnoise(T, T, 3, seed + y0) - 0.5), 0.2))
        cv.put(rect(0, y0 + 2, T, y0 + 3, T), OAK[0])
        for x in (3, 13, 23):
            cv.px(x, y0, PAL['iron'][5])
            cv.px(x, y0 + 1, PAL['iron'][2])
    return cv.image()


def end_grain(seed=5):
    """The top of the post and the cap board: the rings of the tree, a check from the heart, the edge darkened."""
    xs, ys = grid(T)
    wob = (fbm(T, T, 8, seed) - 0.5) * 3
    r = np.hypot(xs - 15 + wob, ys - 17 - wob)
    t = 0.56 + 0.12 * np.sin(r * 1.9) + 0.08 * (vnoise(T, T, 2, seed + 1) - 0.5)
    edge_d = np.minimum(np.minimum(xs, T - xs), np.minimum(ys, T - ys))
    t = t - 0.22 * np.clip(1 - edge_d / 3, 0, 1)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(OAK, t, 0.3))
    d, _ = seg_dist(15, 17, 27, 9, T)
    cv.put(d < 0.6, OAK[0])
    return cv.image()


def pole():
    """The flagpole: pale ash, smooth, lit down one side."""
    xs, ys = grid(T)
    t = 0.62 + 0.06 * (vnoise(T, T, 3, 8) - 0.5) + 0.1 * np.sin(xs * 1.2)
    t = np.where(xs % 2 < 1, t + 0.12, t - 0.06)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(ASH, t, 0.3))
    return cv.image()


def brass():
    """The finial's brass: warm, polished where hands and weather wear it, darker in the hollows."""
    xs, ys = grid(T)
    t = 0.66 - 0.022 * ys + 0.1 * np.sin(xs * 0.9) + 0.08 * (fbm(T, T, 4, 12) - 0.5)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(PAL['brass'], t, 0.25))
    for x, y in ((5, 3), (21, 2), (13, 18)):
        glint(cv, x, y, 1)
    return cv.image()


def iron():
    """Black iron for the lantern's arm, chain and cap: hammered, a little rust in the hollows."""
    xs, ys = grid(T)
    ham = fbm(T, T, 3, 14)
    t = 0.42 + 0.35 * light(ham * 3, 1.0) + 0.06 * (vnoise(T, T, 2, 15) - 0.5)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(PAL['iron'], t, 0.3))
    rust = (fbm(T, T, 4, 16) > 0.66) & (ham < 0.5)
    cv.over(rust, (112, 62, 34), 0.5)
    return cv.image()


LANTERN_FRAMES = 8


def lantern_frames():
    """The lantern's glass, a candle burning behind it: 8 frames, the flame leaning and pulsing. Painted where the
    model's glass faces look ([5, 5] to [8, 8], six pixels a side): an iron frame round a pane of warm light."""
    sheet_cv = Canvas(T, T * LANTERN_FRAMES)
    xs, ys = grid(6, 6)
    for f in range(LANTERN_FRAMES):
        a = f / LANTERN_FRAMES * math.tau
        cv = Canvas(T)
        cv.put(np.ones((T, T), bool), bands(PAL['iron'], 0.4 + 0.1 * np.sin(grid(T)[0] * 0.7), 0.3))
        flick = 0.5 + 0.5 * math.sin(a) * math.cos(a * 2 + 0.6)
        lean = math.sin(a * 2.0) * 0.5
        # The pane: warm light, brightest round the flame
        d = np.hypot(xs - 3.0 - lean * 0.6, (ys - 3.6) * 0.8)
        glow = np.clip(1.15 - d / (2.8 + 0.5 * flick), 0, 1)
        pane = Canvas(6)
        pane.put(np.ones((6, 6), bool), ramp([(120, 52, 18), (214, 120, 40), (250, 184, 92), (255, 232, 170)], glow))
        # The flame: a bright tongue, taller as it pulses
        top = 1.2 - 0.9 * flick
        flame = (np.abs(xs - 3.0 - lean * (4.2 - ys) * 0.25) < 0.9 * np.clip((ys - top) / 2.5, 0.25, 1)) & (ys > top) & (ys < 4.6)
        pane.put(flame, (255, 246, 214))
        pane.put(flame & (ys > 3.6), (255, 214, 140))
        # The candle under it
        pane.px(2, 5, (236, 226, 200))
        pane.px(3, 5, (214, 204, 180))
        # The frame round the pane and its cross bar
        frame = (xs < 1) | (xs > 5) | (ys < 1) | (ys > 5)
        pane.put(frame, PAL['iron'][2])
        pane.put(frame & ((xs < 1) | (ys < 1)), PAL['iron'][3])
        cv.rgb[10:16, 10:16] = pane.rgb
        cv.a[10:16, 10:16] = 1.0
        sheet_cv.rgb[f * T:(f + 1) * T] = cv.rgb
        sheet_cv.a[f * T:(f + 1) * T] = cv.a
    return sheet_cv.image()


def ledger_front():
    """The ledger box's face to the road: a dark oak panel with a brass plate, a house cut in it, and two studs."""
    xs, ys = grid(T)
    t = grain_t(T, T, 21, vertical=False) - 0.1
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(OAK, t, 0.3))
    # The panel the face shows: [3, 6] to [8, 9.5], so x 6..16, y 12..19
    cv.put(rect(6, 12, 16, 13, T), OAK[4])
    cv.put(rect(6, 18, 16, 19, T), OAK[0])
    plate = rect(8, 14, 14, 17, T)
    cv.put(plate, bands(PAL['brass'], 0.7 - 0.12 * (ys - 14) + 0.06 * (xs - 8) / 6, 0.2))
    cv.put(rect(8, 16, 14, 17, T), PAL['brass'][2])
    for x, y in ((10, 15), (11, 14), (12, 15)):
        cv.px(x, y, PAL['brass'][1])
    cv.px(11, 15, PAL['brass'][1])
    for x in (7, 15):
        cv.px(x, 15, PAL['brass'][5])
        cv.px(x, 16, PAL['brass'][2])
    return cv.image()


# ================================================================ the crates

def supply_side(seed, front=False):
    """The supply crate's sides: four oak boards, nailed; on the front, the builders' hammer and saw stencilled."""
    cv = boards(T, T, OAK, seed, size=7, offset=4, lift=0.04, inset=3)
    if front:
        xs, ys = grid(T)
        handle, _ = seg_dist(10, 25, 21, 12, T)
        head = poly([(18.5, 9), (24.5, 13.5), (23, 15.5), (17, 11)], T)
        hammer = (handle < 1.1) | head
        saw = poly([(9, 11), (11.5, 9.5), (24, 23), (21.5, 25.5)], T)
        teeth = saw & (np.mod(xs + ys, 2.0) < 1) & (seg_dist(10, 10.2, 22.7, 24.2, T)[0] < 1)
        grip = poly([(20.5, 22.5), (25.5, 20), (27.5, 25), (23, 27.5)], T)
        mark = (hammer | (saw & ~teeth) | grip)
        stencil(cv, mark, (28, 20, 16), 0.72, seed + 40)
    return cv.image()


def supply_top(seed):
    """The lid: boards along it, two cleats across, an iron hasp at the front edge."""
    cv = boards(T, T, OAK, seed, size=8, vertical=True, lift=0.06, inset=2)
    xs, ys = grid(T)
    for x0 in (4, 24):
        cleat = rect(x0, 0, x0 + 4, T, T)
        ct = grain_t(T, T, seed + x0, vertical=True) - 0.08 - 0.25 * (xs >= x0 + 3) + 0.1 * (xs < x0 + 1)
        cv.put(cleat, bands(OAK, ct, 0.3))
        for y in (3, 13, 22, 28):
            cv.px(x0 + 1, y, PAL['iron'][1])
            cv.px(x0 + 2, y, PAL['iron'][4])
    hasp = rect(13, 26, 19, 32, T)
    cv.put(hasp, bands(PAL['iron'], 0.6 - 0.05 * (ys - 26), 0.2))
    cv.put(rect(15, 28, 17, 30, T), PAL['iron'][0])
    return cv.image()


def batten(seed, pal):
    """A corner post: a darker board up the crate's edge with iron brackets top and bottom."""
    xs, ys = grid(T)
    t = grain_t(T, T, seed, vertical=True) - 0.12
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(pal, t, 0.3))
    for y0 in (4, 27):
        cv.put(rect(0, y0, T, y0 + 4, T), bands(PAL['iron'], 0.55 - 0.08 * (ys - y0), 0.2))
        for x in range(1, T, 4):
            cv.px(x, y0 + 1, PAL['iron'][5])
    return cv.image()


def lid_edge(seed, pal):
    """The lid's edge: the end of a thick board, lit along the top."""
    xs, ys = grid(T)
    t = grain_t(T, T, seed, vertical=False) + 0.05 - 0.3 * (ys > 3) + 0.12 * (ys < 1)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(pal, t, 0.3))
    return cv.image()


def shipping_side(seed, front=False):
    """The shipping crate's sides: pine slats with gaps to the dark inside; on the front, a wheat sheaf painted."""
    cv = boards(T, T, PINE, seed, size=6, offset=8, gap=1, inset=3)
    if front:
        xs, ys = grid(T)
        gaps = np.mod(ys - 8, 6) >= 5
        # A sheaf of wheat: stalks gathered at a band round the waist, fanning out to the ears above and the cut
        # ends below
        # A label board nailed across the slats, pale ash, and on it a sheaf of wheat painted in brown: five
        # stalks bound at the waist, their ears fanned out above, the cut ends below
        board = rect(7, 6, 25, 26, T)
        bt = grain_t(T, T, seed + 5, vertical=False) + 0.12 + 0.1 * (ys < 7) - 0.22 * (ys >= 25) - 0.1 * (xs >= 24)
        cv.put(board, bands(ASH, bt, 0.3))
        for x, y in ((8, 7), (23, 7), (8, 24), (23, 24)):
            cv.px(x, y, PAL['iron'][1])
            cv.px(x, y - 1, PAL['iron'][4])
        sheaf = np.zeros((T, T), bool)
        wx, wy = 16.0, 18.0
        for i, lean in enumerate((-0.74, -0.37, 0.0, 0.37, 0.74)):
            s, c = math.sin(lean), math.cos(lean)
            tx, ty = wx + (i - 2) * 0.6 + s * 5.0, wy - c * 5.0
            sheaf |= seg_dist(wx + (i - 2) * 0.6, wy, tx, ty, T)[0] < 0.5
            # The ear: a plump head carried on along the stalk, pointed at the tip
            cx, cy = tx + s * 2.4, ty - c * 2.4
            al = (xs + 0.5 - cx) * s - (ys + 0.5 - cy) * c
            ac = (xs + 0.5 - cx) * c + (ys + 0.5 - cy) * s
            sheaf |= (al / 2.9) ** 2 + (ac / (1.0 - 0.4 * np.clip(al / 2.9, 0, 1))) ** 2 < 1
            fx = wx + (i - 2) * 1.6
            sheaf |= seg_dist(wx + (i - 2) * 0.6, wy, fx, 24.0, T)[0] < 0.5
        band = rect(13, 17, 20, 19, T)
        wear = fbm(T, T, 3, seed + 50)
        cv.over(sheaf | band, (60, 36, 20), 0.7 + 0.25 * wear)
        cv.over(band, (120, 40, 30), 0.5)
    return cv.image()


def produce(seed=31):
    """The shipping crate's load seen from above: straw, apples, carrots, potatoes, a beet, a cabbage."""
    xs, ys = grid(T)
    cv = Canvas(T)
    straw = np.sin((xs * 0.8 + ys * 1.6) + fbm(T, T, 6, seed) * 6) * 0.5 + 0.5
    cv.put(np.ones((T, T), bool), bands([(70, 54, 26), (128, 102, 46), (176, 146, 70), (210, 184, 104)], 0.25 + 0.6 * straw, 0.4))
    rng = np.random.default_rng(seed)
    things = []
    for _ in range(26):
        things.append((rng.uniform(1, 31), rng.uniform(1, 31), rng.choice(["apple", "apple", "carrot", "potato", "potato", "beet", "cabbage"]), rng.uniform(0, math.pi)))
    things.sort(key=lambda t: t[1])
    for x, y, kind, ang in things:
        if kind == "apple":
            m, dome = disc(x, y, 2.5, T)
            cv.put(m, bands(GEMS['red'][:4], 0.3 + 0.8 * dome + 0.3 * light(dome * 3, 1.0), 0.2))
            cv.px(int(x), int(y - 2), (70, 50, 26))
            cv.px(int(x - 1), int(y - 1), (255, 200, 180))
        elif kind == "potato":
            m, dome = ellipse(x, y, 2.6, 1.9, T)
            cv.put(m, bands([(66, 44, 26), (118, 84, 48), (164, 124, 74), (196, 160, 104)], 0.25 + 0.7 * dome, 0.3))
        elif kind == "beet":
            m, dome = disc(x, y, 2.2, T)
            cv.put(m, bands([(46, 10, 26), (98, 22, 48), (150, 44, 74), (188, 88, 110)], 0.25 + 0.75 * dome, 0.2))
        elif kind == "cabbage":
            m, dome = disc(x, y, 3.4, T)
            leaf = np.sin(np.arctan2(ys - y, xs - x) * 5 + np.hypot(xs - x, ys - y)) * 0.5 + 0.5
            cv.put(m, bands([(30, 64, 30), (60, 110, 48), (110, 158, 78), (170, 206, 120)], 0.2 + 0.6 * dome + 0.2 * leaf, 0.3))
        else:
            dx, dy = math.cos(ang) * 3.2, math.sin(ang) * 3.2
            d, tt = seg_dist(x - dx, y - dy, x + dx, y + dy, T)
            m = d < 1.3 - tt * 0.8
            cv.put(m, bands([(120, 46, 10), (196, 92, 22), (236, 140, 46), (250, 186, 96)], 0.35 + 0.5 * (1 - d / 1.3), 0.2))
            for k in (-1, 0, 1):
                cv.px(int(x - dx * 1.2 + k), int(y - dy * 1.2), (60, 120, 50))
    return cv.image()


def inner():
    xs, ys = grid(T)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands([(14, 10, 10), (26, 18, 16), (40, 28, 22)], 0.4 + 0.3 * (vnoise(T, T, 4, 3) - 0.5), 0.3))
    return cv.image()


# ================================================================ the items

def charter():
    """The charter: a deed unrolled between its two curls, the plot drawn on it in brown ink with a cottage and its
    pennant, lines of writing, and the guild's seal in red wax on a green ribbon."""
    xs, ys = grid(T)
    cv = Canvas(T)
    n = fbm(T, T, 5, 11)
    sheet_m = rect(9, 7, 24, 26, T)
    t = 0.64 + 0.18 * (n - 0.5) - 0.12 * ((xs - 9) / 15) + 0.05 * ((ys - 7) / 19)
    t = np.where(np.abs(xs - 16.5) < 0.6, t - 0.08, t)
    cv.put(sheet_m, bands(PAL['parchment'], t, 0.3))
    # The curls, top and bottom, each a roll a little wider than the sheet with its end turned to us
    for y0, y1 in ((3, 8), (25, 30)):
        cy, ry = (y0 + y1) / 2, (y1 - y0) / 2
        roll = rect(8, y0, 25, y1, T)
        hgt = np.sqrt(np.clip(1 - ((ys - cy) / ry) ** 2, 0, 1))
        rt = 0.3 + 0.55 * hgt + 0.25 * np.clip(light(hgt * 3, 1.0), -1, 1) - 0.08 * ((xs - 8) / 17)
        cv.put(roll, bands(PAL['parchment'], rt, 0.25))
        end_m, ed = ellipse(25, cy, 1.4, ry, T)
        ang = np.arctan2((ys - cy) / ry, (xs - 25) / 1.4)
        spiral = np.mod(np.hypot((xs - 25) / 1.4, (ys - cy) / ry) * 2.4 + ang / math.tau, 1.0) < 0.45
        cv.put(end_m, np.where(spiral[..., None], np.array(PAL['parchment'][1], float), np.array(PAL['parchment'][3], float)))
    # The plot in ink: its bounds, the cottage and its pennant, the road from the gate
    ink = (78, 52, 34)
    inkl = (120, 88, 58)
    for x in range(11, 22):
        cv.px(x, 10, ink)
        cv.px(x, 18, ink if x not in (15, 16, 17) else inkl)
    for y in range(10, 19):
        cv.px(11, y, ink)
        cv.px(21, y, ink)
    for x, y in ((15, 12), (16, 11), (17, 12), (14, 13), (18, 13), (14, 14), (18, 14), (14, 15), (18, 15), (15, 15), (16, 15), (17, 15)):
        cv.px(x, y, ink)
    cv.px(16, 14, inkl)
    cv.px(20, 12, ink)
    cv.px(20, 13, ink)
    cv.px(20, 14, ink)
    cv.px(20, 15, ink)
    cv.px(19, 12, (170, 40, 34))
    cv.px(19, 11, (170, 40, 34))
    for y in (19, 20):
        cv.px(16, y, inkl)
    # Writing
    rng = np.random.default_rng(4)
    for y, x0, x1 in ((21, 11, 22), (23, 11, 19)):
        for x in range(x0, x1):
            if rng.random() < 0.72:
                cv.px(x, y, inkl)
    # The ribbon: a flat blue band whose two tails hang from under the seal, widening, their ends cut in a V
    sx, sy = 21.0, 24.0
    blue = [(22, 30, 70), (38, 54, 120), (62, 86, 168), (104, 130, 206)]
    for (x1, y1), side in (((17.4, 31.6), -1), ((24.6, 31.6), 1)):
        d, tt = seg_dist(sx + side * 0.6, sy + 1.0, x1, y1, T)
        m = (d < 0.9 + 0.5 * tt) & ~((tt > 0.84) & (d < 0.5 * (tt - 0.84) / 0.16 + 0.05))
        cv.put(m, bands(blue, 0.66 - 0.36 * tt + 0.2 * (side < 0), 0.2))
    # The seal: a round pool of red wax, its rim pressed up in a ring lit on the upper left, the guild's little
    # house raised in the middle
    wax = PAL['wax']
    dx, dy = xs + 0.5 - sx, ys + 0.5 - sy
    r = np.hypot(dx, dy)
    cv.put(r < 3.7, wax[3])
    ring = np.abs(r - 2.75) < 0.6
    cv.put(ring & (dx + dy < -0.6), wax[5])
    cv.put(ring & (np.abs(dx + dy) <= 0.6), wax[4])
    cv.put(ring & (dx + dy > 0.6), wax[1])
    cv.put((r < 3.7) & (r > 3.2) & (dx + dy > 1.5), wax[2])
    ix, iy = int(sx), int(sy)
    for x, y, c in ((ix - 1, iy, 4), (ix, iy - 1, 5), (ix + 1, iy, 2), (ix - 1, iy + 1, 4), (ix + 1, iy + 1, 2), (ix, iy + 1, 1)):
        cv.px(x, y, wax[c])
    outline(cv, 0.82)
    return cv.image()


def frame_uv(ax, ay, bx, by):
    """Each pixel's coordinates along a tool's handle from (ax, ay) to (bx, by), and across it (+ to its right)."""
    xs, ys = grid(T)
    L = math.hypot(bx - ax, by - ay)
    dx, dy = (bx - ax) / L, (by - ay) / L
    u = (xs - ax) * dx + (ys - ay) * dy
    v = (xs - ax) * -dy + (ys - ay) * dx
    return u, v, L


def framed(ax, ay, bx, by):
    """The other way: (u, v) points in the frame frame_uv makes, back to the image's (x, y)."""
    L = math.hypot(bx - ax, by - ay)
    dx, dy = (bx - ax) / L, (by - ay) / L
    return lambda pts: [(ax + u * dx - v * dy, ay + u * dy + v * dx) for u, v in pts]


def tube(pts, r0, r1):
    """A stroke along a path, its radius going from r0 to r1: the mask, and how far along it each pixel lies."""
    m = np.zeros((T, T), bool)
    along = np.zeros((T, T))
    n = len(pts) - 1
    for i in range(n):
        d, tt = seg_dist(*pts[i], *pts[i + 1], T)
        s = (i + tt) / n
        hit = d < r0 + (r1 - r0) * s
        along = np.where(hit & ~m, s, along)
        m |= hit
    return m, along


def handle(cv, u, v, L, half=1.35, wrap=6.0, seed=1, pal=ASH):
    """A wooden handle along u, lit down its upper side, with a cord wrapped round the grip."""
    m = (u >= 0) & (u <= L) & (np.abs(v) <= half)
    t = 0.56 - 0.3 * (v / half) + 0.05 * (vnoise(T, T, 2, seed) - 0.5)
    cv.put(m, bands(pal, t, 0.2))
    grip = m & (u < wrap)
    cord = np.mod(u + v * 0.6, 2.0) < 1.0
    cv.put(grip, bands(PAL['leather'], 0.55 - 0.3 * (v / half) - 0.25 * cord, 0.2))
    return m


def hammer():
    """The builder's claw hammer: an ash handle with a corded grip, a steel head, the face square to strike with and
    the claw curving back and split for pulling nails."""
    cv = Canvas(T)
    ax, ay, bx, by = 6.0, 27.0, 18.5, 14.5
    u, v, L = frame_uv(ax, ay, bx, by)
    xy = framed(ax, ay, bx, by)
    handle(cv, u, v, L + 1.6, seed=3)
    # The head across the handle's top (v < 0 toward the upper left): the striking end, a squat drum seen side on,
    # its neck, the cheeks round the handle, and the claw bending back toward the handle and split at its tip
    face_m = poly(xy([(L - 2.3, -8.6), (L + 2.5, -8.6), (L + 2.5, -5.8), (L - 2.3, -5.8)]), T)
    neck = poly(xy([(L - 1.3, -6.0), (L + 1.7, -6.0), (L + 2.0, -1.8), (L - 1.6, -1.8)]), T)
    cheek = poly(xy([(L - 2.0, -2.0), (L + 2.2, -2.0), (L + 2.2, 1.8), (L - 2.0, 1.8)]), T)
    path = xy([(L + 0.3 - 3.6 * s * s, 1.4 + 5.6 * s) for s in np.linspace(0, 1, 10)])
    claw, along = tube(path, 2.0, 0.7)
    split, _ = tube(path[5:], 0.45, 0.45)
    claw &= ~split
    steel = face_m | neck | cheek | claw
    up = (u - (L - 2.3)) / 4.8
    st = 0.46 + 0.32 * up + 0.04 * (vnoise(T, T, 2, 7) - 0.5)
    st = np.where(face_m, 0.5 + 0.36 * up + 0.12 * (v < -7.8), st)
    st = np.where(claw & ~(face_m | neck | cheek), 0.62 - 0.3 * along + 0.18 * up, st)
    cv.put(steel, bands(PAL['steel'], st, 0.2))
    # The handle's wedge showing in the eye, and the nail slot dark between the claws
    for p in xy([(L + 1.4, -0.4), (L + 1.4, 0.6)]):
        cv.px(int(p[0]), int(p[1]), ASH[3])
    rim(cv, 0.28, steel)
    outline(cv, 0.85)
    gx, gy = xy([(L + 1.6, -7.6)])[0]
    glint(cv, int(gx), int(gy), 1)
    return cv.image()


def saw():
    """The builder's hand saw: a steel blade tapering from the grip to its tip, teeth along its lower edge, its back
    lit, an ash handle with a hole for the hand and two brass rivets."""
    cv = Canvas(T)
    ax, ay, bx, by = 25.5, 26.0, 4.5, 5.5
    u, v, L = frame_uv(ax, ay, bx, by)
    xy = framed(ax, ay, bx, by)
    # The blade (v > 0 toward its back, the upper right): a straight back, the toothed edge rising to the tip
    k = np.clip((u - 5.0) / (L - 5.0), 0, 1)
    back = 2.3 - 0.5 * k
    edge = -4.8 + 3.0 * k
    tri = 1.0 - np.abs(np.mod(u, 2.0) - 1.0)
    blade = (u >= 4.0) & (u <= L - 0.4) & (v <= back) & (v >= edge - 1.1 * tri)
    bt = 0.42 + 0.34 * np.clip((v - edge) / (back - edge), 0, 1) + 0.04 * (vnoise(T, T, 2, 9) - 0.5)
    bt = np.where(v > back - 0.9, bt + 0.14, bt)
    bt = np.where(v < edge, 0.3, bt)
    cv.put(blade, bands(PAL['steel'], bt, 0.2))
    # A few freckles of rust, and the maker's mark punched near the handle
    rng = np.random.default_rng(5)
    for _ in range(5):
        x, y = xy([(rng.uniform(10, L - 3), rng.uniform(-2.0, 1.2))])[0]
        cv.px(int(x), int(y), (120, 70, 40))
    for p in xy([(7.6, -0.6), (8.6, -0.6), (8.1, 0.4)]):
        cv.px(int(p[0]), int(p[1]), PAL['steel'][1])
    # The handle: a closed grip round the blade's root, the hole for the fingers through it, two brass screws
    grip_m = poly(xy([(6.6, 3.6), (6.6, -6.0), (3.0, -7.6), (-2.2, -7.4), (-5.0, -4.6), (-5.2, 1.0), (-3.2, 3.8), (1.2, 4.4)]), T)
    hole_m = ((u - 0.4) / 1.7) ** 2 + ((v + 1.8) / 3.1) ** 2 < 1
    grip_m &= ~hole_m
    height = dist_in(grip_m, 3)
    gt = 0.5 + 0.9 * light(height, 0.8) + 0.06 * (vnoise(T, T, 2, 19) - 0.5)
    cv.put(grip_m, bands(OAK, gt + 0.1, 0.2))
    for p in xy([(5.2, 1.6), (5.2, -4.0)]):
        cv.px(int(p[0]), int(p[1]), PAL['brass'][5])
        cv.px(int(p[0]) + 1, int(p[1]) + 1, PAL['brass'][2])
    outline(cv, 0.85)
    gx, gy = xy([(L - 6.0, 1.4)])[0]
    glint(cv, int(gx), int(gy), 1)
    return cv.image()


def mallet():
    """The builder's mallet: a beech head bound with iron at both ends, on an ash handle."""
    cv = Canvas(T)
    u, v, L = frame_uv(6.0, 27.0, 19.5, 13.5)
    handle(cv, u, v, L + 1.0, half=1.25, wrap=5.0, seed=6)
    head = (u >= L - 2.8) & (u <= L + 3.6) & (np.abs(v) <= 6.6)
    ht = 0.6 - 0.32 * ((u - (L + 0.4)) / 3.2) + 0.06 * (vnoise(T, T, 2, 13) - 0.5)
    cv.put(head, bands(PINE, ht, 0.25))
    for vb in (-5.0, 4.4):
        band = head & (np.abs(v - vb) < 0.8)
        cv.put(band, bands(PAL['iron'], 0.6 - 0.3 * ((u - (L + 0.4)) / 3.2), 0.2))
    ends = head & (np.abs(v) > 6.0)
    cv.put(ends, bands(PINE, 0.75 - 0.2 * ((u - (L + 0.4)) / 3.2), 0.2))
    rim(cv, 0.3, head)
    outline(cv, 0.85)
    return cv.image()


# ================================================================ the client's textures

def boundary():
    """The curtain of light round a plot (eyes render type, tinted by the owner's colour): white, bright along the
    ground and fading upward, with soft rays in it. It repeats across each block of the ribbon, so the rays tile."""
    w, h = 32, 64
    xs, ys = grid(w, h)
    up = 1 - ys / h
    rays = 0.72 + 0.18 * np.sin(xs / w * math.tau * 2) + 0.1 * np.sin(xs / w * math.tau * 5 + 1.3)
    rays = rays + 0.18 * (vnoise(w, h, 6, 3) - 0.5)
    a = np.exp(-up * 3.2) * rays
    a = a + 0.55 * np.exp(-((ys - (h - 1.5)) / 1.6) ** 2)
    a = np.clip(a, 0, 1) * np.clip((h - 1 - ys + 0.5) / 1.0, 0, 1)
    out = np.zeros((h, w, 4), np.uint8)
    out[..., :3] = 255
    out[..., 3] = (a * 255).round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def mark():
    """A glowing frame for the blocks at fault: bright edges and corners over a faint face."""
    n = 16
    xs, ys = grid(n)
    e = np.minimum(np.minimum(xs, n - xs), np.minimum(ys, n - ys))
    a = 0.16 + 0.84 * np.exp(-((e - 0.5) / 1.1) ** 2)
    corner = (np.minimum(xs, n - xs) < 3) & (np.minimum(ys, n - ys) < 3)
    a = np.where(corner, np.maximum(a, 0.85), a)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = 255
    out[..., 3] = (np.clip(a, 0, 1) * 255).round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def pennant():
    """The pennant's cloth, tinted by the owner's colour as it's drawn: pale woven cloth, the sleeve round the pole,
    a stitched hem, a cottage worked in darker thread, and the end cut in a swallowtail."""
    w, h = 32, 16
    xs, ys = grid(w, h)
    weave = 0.9 + 0.05 * ((np.floor(xs) + np.floor(ys)) % 2) + 0.05 * (vnoise(w, h, 3, 4) - 0.5)
    g = weave.copy()
    g = np.where(xs < 3, 0.72 + 0.08 * np.sin(ys * 1.6), g)
    hem = ((ys < 1.2) | (ys > h - 1.2)) & (xs > 3)
    g = np.where(hem, 0.8, g)
    stitch = ((np.abs(ys - 1.8) < 0.5) | (np.abs(ys - (h - 1.8)) < 0.5)) & (xs > 4) & (np.floor(xs) % 3 != 0)
    g = np.where(stitch, 0.68, g)
    house = np.zeros((h, w), bool)
    house |= poly([(10, 8), (14, 4.5), (18, 8)], w, h)
    house |= (xs > 11) & (xs < 17) & (ys > 8) & (ys < 12)
    door = (xs > 13.4) & (xs < 14.6) & (ys > 9.5) & (ys < 12)
    g = np.where(house & ~door, 0.55, g)
    a = np.ones((h, w))
    notch = (xs > 25) & (np.abs(ys - h / 2) < (xs - 25) * 1.1)
    a[notch] = 0
    out = np.zeros((h, w, 4), np.uint8)
    out[..., :3] = (np.clip(g, 0, 1) * 255).round().astype(np.uint8)[..., None]
    out[..., 3] = (a * 255).astype(np.uint8)
    out[out[..., 3] == 0] = 0
    return Image.fromarray(out, "RGBA")


def lantern_glow():
    """The lantern's halo after dark: a soft round glow, white, tinted warm as it's drawn."""
    n = 32
    xs, ys = grid(n)
    r = np.hypot(xs - n / 2, ys - n / 2) / (n / 2)
    a = 0.85 * np.exp(-(r / 0.42) ** 2) + 0.15 * np.exp(-(r / 0.12) ** 2)
    a = a * np.clip((1 - r) / 0.15, 0, 1)
    out = np.zeros((n, n, 4), np.uint8)
    out[..., :3] = 255
    out[..., 3] = (np.clip(a, 0, 1) * 255).round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


# ================================================================ models

def face(uv, tex, cull=None):
    f = {"uv": [round(v, 3) for v in uv], "texture": tex}
    if cull:
        f["cullface"] = cull
    return f


def box(frm, to, faces, rotation=None, light_level=None):
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces}
    if rotation:
        e["rotation"] = rotation
    if light_level:
        e["light_emission"] = light_level
    return e


def sides(uv, tex, skip=()):
    return {d: face(uv, tex) for d in NSEW if d not in skip}


def post_model():
    b = "guildmark:block/charter_post_"
    textures = {"particle": b + "wood", "wood": b + "wood", "top": b + "top", "pole": b + "pole", "brass": b + "brass",
                "iron": b + "iron", "lantern": b + "lantern", "ledger": b + "ledger"}
    e = []
    # The post, its cap board, the pole and the finial
    e.append(box([6, 0, 6], [10, 14, 10], {**sides([6, 2, 10, 16], "#wood"), "down": face([6, 6, 10, 10], "#top", "down")}))
    e.append(box([5.5, 14, 5.5], [10.5, 15.5, 10.5], {**sides([0, 0, 5, 1.5], "#wood"), "up": face([5.5, 5.5, 10.5, 10.5], "#top"),
                                                      "down": face([5.5, 5.5, 10.5, 10.5], "#top")}))
    e.append(box([7.5, 15.5, 7.5], [8.5, 29, 8.5], sides([7.5, 1, 8.5, 14.5], "#pole")))
    e.append(box([7.25, 29, 7.25], [8.75, 29.5, 8.75], {**sides([2, 4, 3.5, 4.5], "#brass"), "down": face([2, 2, 3.5, 3.5], "#brass")}))
    e.append(box([7, 29.5, 7], [9, 31, 9], {**sides([4, 2, 6, 3.5], "#brass"), "up": face([4, 4, 6, 6], "#brass"), "down": face([4, 4, 6, 6], "#brass")}))
    # The ledger box on the road side, its lid sloped like a lectern's
    e.append(box([5.5, 8, 10], [10.5, 11.5, 11.5], {"south": face([3, 6, 8, 9.5], "#ledger"), "east": face([0, 8, 1.5, 11.5], "#wood"),
                                                    "west": face([0, 8, 1.5, 11.5], "#wood"), "down": face([3, 4, 8, 5.5], "#wood")}))
    e.append(box([5.25, 11.5, 9.75], [10.75, 12.25, 11.75], {**sides([0, 4, 5.5, 4.75], "#wood"), "up": face([0, 9, 5.5, 11], "#top")},
                 {"origin": [8, 11.5, 10], "axis": "x", "angle": 22.5}))
    # The arm out over the lantern, a curl of iron under it, the chain and the lantern
    e.append(box([10, 12.5, 7.5], [14, 13.5, 8.5], {**sides([0, 0, 4, 1], "#iron"), "up": face([0, 2, 4, 3], "#iron"), "down": face([0, 4, 4, 5], "#iron")}))
    e.append(box([10, 11, 7.75], [11.5, 12.5, 8.25], {**sides([6, 0, 7.5, 1.5], "#iron")}, {"origin": [10, 12.5, 8], "axis": "z", "angle": 45}))
    e.append(box([12.75, 10.5, 7.75], [13.25, 12.5, 8.25], sides([9, 0, 9.5, 2], "#iron")))
    e.append(box([11.75, 10, 6.75], [14.25, 10.5, 9.25], {**sides([0, 6, 2.5, 6.5], "#iron"), "up": face([0, 7, 2.5, 9.5], "#iron"),
                                                          "down": face([0, 7, 2.5, 9.5], "#iron")}))
    e.append(box([11.5, 7, 6.5], [14.5, 10, 9.5], {**sides([5, 5, 8, 8], "#lantern"), "up": face([5, 5, 8, 8], "#iron"),
                                                   "down": face([5, 5, 8, 8], "#iron")}, light_level=15))
    e.append(box([11.25, 6.5, 6.25], [14.75, 7, 9.75], {**sides([0, 10, 3.5, 10.5], "#iron"), "up": face([0, 11, 3.5, 14.5], "#iron"),
                                                        "down": face([0, 11, 3.5, 14.5], "#iron")}))
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": textures, "elements": e}


def crate_model(kind):
    """The supply crate (oak, a lid) or the shipping crate (pine slats, open, its load heaped up inside)."""
    b = f"guildmark:block/{kind}_crate_"
    e = []
    if kind == "supply":
        textures = {"particle": b + "side", "side": b + "side", "front": b + "front", "top": b + "top", "batten": b + "batten", "lid": b + "lid"}
        body_h, post_h = 14, 14
    else:
        textures = {"particle": b + "side", "side": b + "side", "front": b + "front", "batten": b + "batten", "lid": b + "lid",
                    "load": b + "load", "inner": b + "inner"}
        body_h, post_h = 12, 14
    e.append(box([1, 0, 1], [15, body_h, 15], {"north": face([1, 16 - body_h, 15, 16], "#side"), "south": face([1, 16 - body_h, 15, 16], "#front"),
                                               "east": face([1, 16 - body_h, 15, 16], "#side"), "west": face([1, 16 - body_h, 15, 16], "#side"),
                                               "down": face([1, 1, 15, 15], "#side", "down")}))
    for x0, z0 in ((0, 0), (14, 0), (0, 14), (14, 14)):
        e.append(box([x0, 0, z0], [x0 + 2, post_h, z0 + 2], {**sides([7, 16 - post_h, 9, 16], "#batten"), "up": face([7, 7, 9, 9], "#batten"),
                                                             "down": face([7, 7, 9, 9], "#batten", "down")}))
    if kind == "supply":
        e.append(box([0, 14, 0], [16, 16, 16], {"north": face([0, 0, 16, 2], "#lid", "north"), "south": face([0, 0, 16, 2], "#lid", "south"),
                                                "east": face([0, 0, 16, 2], "#lid", "east"), "west": face([0, 0, 16, 2], "#lid", "west"),
                                                "up": face([0, 0, 16, 16], "#top", "up"), "down": face([0, 0, 16, 16], "#top")}))
    else:
        # The rim round the open top, the inside dark, the load heaped in it
        for frm, to in (([2, 12, 1], [14, 14, 2]), ([2, 12, 14], [14, 14, 15]), ([1, 12, 2], [2, 14, 14]), ([14, 12, 2], [15, 14, 14])):
            e.append(box(frm, to, {**sides([2, 0, 14, 2], "#lid"), "up": face([2, 0, 14, 1], "#lid")}))
        e.append(box([2, 11.25, 2], [14, 13.25, 14], {"up": face([2, 2, 14, 14], "#load"), **sides([2, 2, 14, 4], "#inner")}))
    return {"parent": "minecraft:block/block", "textures": textures, "elements": e}


def facing_states(model):
    return {"variants": {f"facing={d}": ({"model": model, "y": y} if y else {"model": model})
                         for d, y in (("south", 0), ("west", 90), ("north", 180), ("east", 270))}}


# ================================================================ previews: a small rasteriser for model elements and loose quads

DIRS = {
    "down": (("x0", "y0", "z1"), ("x1", "y0", "z1"), ("x0", "y0", "z0")),
    "up": (("x0", "y1", "z0"), ("x1", "y1", "z0"), ("x0", "y1", "z1")),
    "north": (("x1", "y1", "z0"), ("x0", "y1", "z0"), ("x1", "y0", "z0")),
    "south": (("x0", "y1", "z1"), ("x1", "y1", "z1"), ("x0", "y0", "z1")),
    "west": (("x0", "y1", "z0"), ("x0", "y1", "z1"), ("x0", "y0", "z0")),
    "east": (("x1", "y1", "z1"), ("x1", "y1", "z0"), ("x1", "y0", "z1")),
}
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def _rot(rot):
    if not rot:
        return np.eye(3), np.zeros(3)
    a = math.radians(rot["angle"])
    c, s = math.cos(a), math.sin(a)
    m = {"x": [[1, 0, 0], [0, c, -s], [0, s, c]], "y": [[c, 0, s], [0, 1, 0], [-s, 0, c]], "z": [[c, -s, 0], [s, c, 0], [0, 0, 1]]}[rot["axis"]]
    return np.array(m, float), np.array(rot["origin"], float)


class Scene:
    """Textured quads drawn into a z-buffer from one point of view: x right, y up, z toward the viewer (blocks'
    pixels, 16 to a block), turned by yaw about the middle and tilted by pitch."""

    def __init__(self, size, yaw, pitch, scale, centre=(8, 8, 8), ground=0.78, bg=(54, 60, 74)):
        self.w, self.h = size
        self.img = np.zeros((self.h, self.w, 3))
        self.img[...] = bg
        self.z = np.full((self.h, self.w), 1e9)
        self.cy, self.sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        self.cp, self.sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        self.scale = scale
        self.centre = np.array(centre, float)
        self.ground = ground
        self.ys, self.xs = np.mgrid[0:self.h, 0:self.w]

    def view(self, p):
        x, y, z = p[0] - self.centre[0], p[1], p[2] - self.centre[2]
        x, z = x * self.cy - z * self.sy, x * self.sy + z * self.cy
        y2 = y * self.cp - z * self.sp
        z2 = y * self.sp + z * self.cp
        return np.array([self.w / 2 + x * self.scale, self.h * self.ground - y2 * self.scale, -z2])

    def quad(self, A, B, D, tex, uv, k=1.0, tint=None, alpha_mode="cutout", glow=1.0):
        """A parallelogram A, B, D (the fourth corner B + D - A): uv (u0, v0, u1, v1) in 16ths runs A->B along u and
        A->D along v. alpha_mode "cutout" keeps texels over half alpha; "add" lays light over what's there."""
        a, b, dd = self.view(A), self.view(B), self.view(D)
        e1, e2 = b - a, dd - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-9:
            return
        corners = [a, b, dd, b + e2]
        bx0 = int(max(0, min(c[0] for c in corners)))
        bx1 = int(min(self.w - 1, max(c[0] for c in corners) + 1))
        by0 = int(max(0, min(c[1] for c in corners)))
        by1 = int(min(self.h - 1, max(c[1] for c in corners) + 1))
        if bx0 >= bx1 or by0 >= by1:
            return
        px = self.xs[by0:by1, bx0:bx1] + 0.5 - a[0]
        py = self.ys[by0:by1, bx0:bx1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        T_ = tex
        sc = T_.shape[1] / 16.0
        u0, v0, u1, v1 = uv
        tu = np.clip(((u0 + s * (u1 - u0)) * sc).astype(int), 0, T_.shape[1] - 1)
        tv = np.clip(((v0 + t * (v1 - v0)) * sc).astype(int), 0, T_.shape[0] - 1)
        texel = T_[tv, tu]
        col = texel[..., :3] * k
        if tint is not None:
            col = col * (np.array(tint[:3], float) / 255.0)
        region = self.img[by0:by1, bx0:bx1]
        zb = self.z[by0:by1, bx0:bx1]
        if alpha_mode == "add":
            al = texel[..., 3] / 255.0 * glow
            ok = inside & (depth < zb + 1e-4)
            region[...] = np.where(ok[..., None], region + col * al[..., None], region)
            return
        ok = inside & (texel[..., 3] > 128) & (depth < zb - 1e-4)
        region[...] = np.where(ok[..., None], col, region)
        zb[ok] = depth[ok]

    def model(self, mdl, textures, frame=0, turn=0):
        """A block model's elements; turn is the blockstate's y rotation."""
        R0 = np.eye(3)
        if turn:
            R0, _ = _rot({"axis": "y", "angle": -turn, "origin": [8, 8, 8]})
        mid = np.array([8.0, 8.0, 8.0])
        for el in mdl["elements"]:
            (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
            co = {"x0": x0, "x1": x1, "y0": y0, "y1": y1, "z0": z0, "z1": z1}
            R, origin = _rot(el.get("rotation"))
            for d, f in el["faces"].items():
                img = textures[mdl["textures"][f["texture"][1:]]]
                arr = np.asarray(img, float)
                if arr.shape[0] > arr.shape[1]:
                    n = arr.shape[0] // arr.shape[1]
                    i = frame % n
                    arr = arr[i * arr.shape[1]:(i + 1) * arr.shape[1]]
                pts = [origin + R @ (np.array([co[k] for k in v], float) - origin) for v in DIRS[d]]
                pts = [mid + R0 @ (p - mid) for p in pts]
                k = 1.0 if el.get("light_emission") else SHADE[d]
                self.quad(pts[0], pts[1], pts[2], arr, f["uv"], k)

    def image(self):
        return Image.fromarray(np.clip(self.img, 0, 255).astype(np.uint8), "RGB")


# The pennant as CharterPostRenderer draws it (its constants and its wave), in the post's pixels
SEGMENTS, LENGTH, CLOTH, TOP = 10, 0.78, 0.42, 1.74
LANTERN_X, LANTERN_Y = 0.3125, 0.53


def pennant_quads(time, wind):
    xs, zs, tops, bottoms = [], [], [], []
    for i in range(SEGMENTS + 1):
        u = i / SEGMENTS
        wave = math.sin(u * 7.5 - time * 0.32 * wind) * 0.07 * u * wind + math.sin(u * 3 - time * 0.11) * 0.03 * u
        xs.append(-0.03 - u * LENGTH * (1 - 0.04 * wind * u))
        zs.append(wave)
        droop = u * u * 0.08 / wind
        height = CLOTH * (1 - 0.22 * u)
        tops.append(TOP - droop - (CLOTH - height) * 0.15)
        bottoms.append(tops[-1] - height)
    out = []
    for i in range(SEGMENTS):
        dx, dz = xs[i + 1] - xs[i], zs[i + 1] - zs[i]
        ln = max(1e-4, math.hypot(dx, dz))
        nx, nz = -dz / ln, dx / ln
        shade = min(1.0, max(0.6, 0.82 + 0.18 * max(-1.0, min(1.0, nx * 0.6 + nz * 0.8))))
        P = lambda x, y, z: np.array([8 + x * 16, y * 16, 8 + z * 16])
        out.append((P(xs[i], tops[i], zs[i]), P(xs[i + 1], tops[i + 1], zs[i + 1]), P(xs[i], bottoms[i], zs[i]),
                    (i / SEGMENTS * 16, 0, (i + 1) / SEGMENTS * 16, 16), shade))
    return out


DYES = {"red": (176, 46, 38), "blue": (60, 68, 170), "green": (94, 124, 22), "yellow": (254, 216, 61)}


def post_scene(mdl, textures, time, yaw, dark, colour, wind=1.0, size=(260, 340), scale=8.5):
    sky = mix((150, 186, 228), (64, 76, 120), dark)
    sc = Scene(size, yaw, 18, scale, ground=0.86, bg=tuple(sky))
    sc.img *= 1.0
    # A patch of grass under it
    grass = np.asarray(Image.new("RGBA", (32, 32), (92, 132, 60, 255)), float)
    sc.quad(np.array([-12, 0, -12.0]), np.array([28, 0, -12.0]), np.array([-12, 0, 28.0]), grass, (0, 0, 16, 16), 0.9)
    sc.model(mdl, textures, frame=int(time / 3))
    pen = np.asarray(textures["guildmark:entity/homestead/pennant"], float)
    for A, B, D, uv, shade in pennant_quads(time, wind):
        sc.quad(A, B, D, pen, uv, shade, tint=colour)
        sc.quad(B, A, B + (D - A), pen, (uv[2], uv[1], uv[0], uv[3]), shade * 0.85, tint=colour)
    # Day to night: everything dims, the lantern's light falling warm on what's near it, then its glow comes up
    flick = 0.85 + 0.1 * math.sin(time * 0.71) + 0.05 * math.sin(time * 2.3 + 1.7)
    day = sc.img.copy()
    c = sc.view(np.array([8 + LANTERN_X * 16, LANTERN_Y * 16, 8.0]))
    near = np.exp(-(((sc.xs - c[0]) ** 2 + (sc.ys - c[1]) ** 2) / (2 * (13 * scale) ** 2))) * (sc.z < 1e8)
    warm = np.array([1.0, 0.78, 0.5])
    sc.img = day * (1.0 - 0.66 * dark) + day * (near * dark * flick * 0.85)[..., None] * warm
    if dark > 0.05:
        glow = np.asarray(textures["guildmark:entity/homestead/lantern_glow"], float)
        c = sc.view(np.array([8 + LANTERN_X * 16, LANTERN_Y * 16, 8.0]))
        r = 0.55 * 16 * scale * (0.92 + 0.08 * flick)
        g = Image.fromarray(glow.astype(np.uint8), "RGBA").resize((int(2 * r), int(2 * r)), Image.BILINEAR)
        ga = np.asarray(g, float)[..., 3] / 255.0 * dark * 0.75 * flick
        x0, y0 = int(c[0] - r), int(c[1] - r)
        h, w = ga.shape
        ys0, xs0 = max(0, y0), max(0, x0)
        ys1, xs1 = min(sc.h, y0 + h), min(sc.w, x0 + w)
        sub = ga[ys0 - y0:ys1 - y0, xs0 - x0:xs1 - x0]
        warm = np.array([255, 211, 138], float)
        sc.img[ys0:ys1, xs0:xs1] = sc.img[ys0:ys1, xs0:xs1] * (1 - sub[..., None]) + warm * sub[..., None]
    return sc.image()


def preview(out_dir, textures, models):
    os.makedirs(out_dir, exist_ok=True)
    tiles = []
    labels = []
    post, supply, shipping = models["charter_post"], models["supply_crate"], models["shipping_crate"]
    for yaw, turn in ((35, 0), (-145, 0)):
        sc = Scene((260, 340), yaw, 22, 8.5, ground=0.86)
        sc.model(post, textures, frame=0, turn=turn)
        pen = np.asarray(textures["guildmark:entity/homestead/pennant"], float)
        for A, B, D, uv, shade in pennant_quads(40.0, 1.0):
            sc.quad(A, B, D, pen, uv, shade, tint=DYES["blue"])
            sc.quad(B, A, B + (D - A), pen, (uv[2], uv[1], uv[0], uv[3]), shade * 0.85, tint=DYES["blue"])
        tiles.append(sc.image())
    for mdl in (supply, shipping):
        for yaw in (35, -50):
            sc = Scene((260, 340), yaw, 26, 11, ground=0.66)
            sc.model(mdl, textures)
            tiles.append(sc.image())
    top = Image.new("RGB", (sum(t.width for t in tiles), 340), (40, 44, 54))
    x = 0
    for t in tiles:
        top.paste(t, (x, 0))
        x += t.width
    flat = [textures[k] for k in ("guildmark:item/homestead_charter", "guildmark:item/builders_hammer", "guildmark:item/builders_saw",
                                  "guildmark:item/builders_mallet", "guildmark:block/charter_post_wood", "guildmark:block/charter_post_ledger",
                                  "guildmark:block/supply_crate_front", "guildmark:block/supply_crate_top", "guildmark:block/shipping_crate_front",
                                  "guildmark:block/shipping_crate_load", "guildmark:entity/homestead/pennant", "guildmark:entity/homestead/mark")]
    flat.append(textures["guildmark:block/charter_post_lantern"].crop((0, 0, 32, 32)))
    strip = sheet([f if f.height <= 32 else f.crop((0, 0, 32, 32)) for f in flat], scale=6, cols=7, bg=(40, 44, 54))
    out = Image.new("RGB", (max(top.width, strip.width), top.height + strip.height), (40, 44, 54))
    out.paste(top, (0, 0))
    out.paste(strip.convert("RGB"), (0, top.height))
    out.save(os.path.join(out_dir, "homestead_art.png"))
    # The post through a day: the pennant flying, a gust, dusk coming on and the lantern's glow with it
    frames = []
    n = 48
    for i in range(n):
        time = i * 2.0
        dark = max(0.0, min(1.0, (i - 18) / 16))
        wind = 1.0 + 0.6 * max(0.0, math.sin(i / n * math.pi * 2)) ** 2
        frames.append(post_scene(post, textures, time, 30, dark, DYES["red"], wind))
    frames[0].save(os.path.join(out_dir, "charter_post.gif"), save_all=True, append_images=frames[1:], duration=70, loop=0, optimize=True)


# ================================================================ writing it out

def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    for d in ("textures/block", "textures/item", "textures/entity/homestead", "models/block", "models/item", "blockstates", "items"):
        os.makedirs(os.path.join(assets, d), exist_ok=True)
    tex = {
        "guildmark:block/charter_post_wood": post_wood(),
        "guildmark:block/charter_post_top": end_grain(),
        "guildmark:block/charter_post_pole": pole(),
        "guildmark:block/charter_post_brass": brass(),
        "guildmark:block/charter_post_iron": iron(),
        "guildmark:block/charter_post_lantern": lantern_frames(),
        "guildmark:block/charter_post_ledger": ledger_front(),
        "guildmark:block/supply_crate_side": supply_side(61),
        "guildmark:block/supply_crate_front": supply_side(61, front=True),
        "guildmark:block/supply_crate_top": supply_top(62),
        "guildmark:block/supply_crate_batten": batten(63, OAK),
        "guildmark:block/supply_crate_lid": lid_edge(64, OAK),
        "guildmark:block/shipping_crate_side": shipping_side(71),
        "guildmark:block/shipping_crate_front": shipping_side(71, front=True),
        "guildmark:block/shipping_crate_batten": batten(73, PINE),
        "guildmark:block/shipping_crate_lid": lid_edge(74, PINE),
        "guildmark:block/shipping_crate_load": produce(),
        "guildmark:block/shipping_crate_inner": inner(),
        "guildmark:item/homestead_charter": charter(),
        "guildmark:item/builders_hammer": hammer(),
        "guildmark:item/builders_saw": saw(),
        "guildmark:item/builders_mallet": mallet(),
        "guildmark:entity/homestead/boundary": boundary(),
        "guildmark:entity/homestead/mark": mark(),
        "guildmark:entity/homestead/pennant": pennant(),
        "guildmark:entity/homestead/lantern_glow": lantern_glow(),
    }
    for key, img in tex.items():
        kind, name = key.split(":")[1].split("/", 1)
        img.save(os.path.join(assets, "textures", kind, name + ".png"))
    with open(os.path.join(assets, "textures/block/charter_post_lantern.png.mcmeta"), "w") as f:
        json.dump({"animation": {"frametime": 3, "interpolate": True}}, f, indent=2)
        f.write("\n")

    def write(rel, data):
        with open(os.path.join(assets, rel), "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")

    models = {"charter_post": post_model(), "supply_crate": crate_model("supply"), "shipping_crate": crate_model("shipping")}
    for name, mdl in models.items():
        write(f"models/block/{name}.json", mdl)
        write(f"blockstates/{name}.json", facing_states(f"guildmark:block/{name}"))
    write("items/shipping_crate.json", {"model": {"type": "minecraft:model", "model": "guildmark:block/shipping_crate"}})
    for name, parent in (("homestead_charter", "generated"), ("builders_hammer", "handheld"), ("builders_saw", "handheld"), ("builders_mallet", "handheld")):
        write(f"models/item/{name}.json", {"parent": f"minecraft:item/{parent}", "textures": {"layer0": f"guildmark:item/{name}"}})
        write(f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"guildmark:item/{name}"}})
    if prev:
        preview(prev, tex, models)
    print("homestead: %d textures, %d block models" % (len(tex), len(models)))


if __name__ == "__main__":
    main()
