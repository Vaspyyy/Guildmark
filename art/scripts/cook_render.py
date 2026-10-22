"""The four textures the stations' renderers draw that are not block textures (cook_art.py), 32 px each, in textures/entity/cook/:

    surface.png  the liquid in the pot, seen from above: a disc (the renderer lays it on a square quad at the fill height, so its corners
                 stay clear) painted pale and tinted toward the dish in the game. Three arms wind in toward the middle, so it
                 reads as a swirl when the renderer turns the quad while the pot is stirred; bubbles, specks of fat and the shine
                 of the sky sit on it, and the wall shades its upper-left edge.
    film.png     the grey scum of a SKIM call: a disc of ragged islands, thickest in the middle, the renderer lays over the surface
                 and fades or shrinks when the ladle clears it. Left alone by the tint.
    crust.png    the burnt crust: a disc of black, blistered, cracked crust with a few embers glowing deep in the cracks; the
                 renderer shows it in place of the surface once the pot has burnt. Left alone by the tint.
    ring.png     the ready ring: a pale gold ring with four lozenge marks and a ring of tick marks inside it, on a clear ground. The renderer
                 scales it in from the rim toward the centre of the window and turns it slowly; painted gold, it can also be tinted.

Every pixel comes from fixed seeds, so a rerun gives the same files.
"""
import math
import os

import numpy as np

from hdkit import Canvas, grid, fbm, rand, bands, light, blur, edge, dist_in

N = 32
C = N / 2.0

SURF = [(146, 163, 186), (180, 196, 214), (210, 222, 234), (237, 243, 249), (255, 255, 255)]
SCUM = [(78, 80, 84), (108, 110, 114), (140, 142, 144), (172, 172, 170), (204, 202, 196)]
CHAR = [(8, 7, 8), (20, 17, 17), (36, 31, 28), (56, 49, 43), (86, 76, 66)]
EMBER = [(150, 40, 14), (226, 92, 26), (255, 166, 58)]
GOLD = [(255, 253, 232), (255, 232, 150), (244, 190, 70), (196, 134, 40)]


def _polar():
    xs, ys = grid(N)
    dx, dy = xs - C, ys - C
    return xs, ys, dx, dy, np.hypot(dx, dy), np.arctan2(dy, dx)


def _spots(seed, count, lo, hi, rmax, keep=2.0):
    """[count] deterministic spots (x, y, radius) inside the disc, none closer than [keep] to another's edge."""
    rng = np.random.default_rng(seed)
    out = []
    tries = 0
    while len(out) < count and tries < 4000:
        tries += 1
        x, y = rng.uniform(2.0, N - 2.0), rng.uniform(2.0, N - 2.0)
        rad = rng.uniform(lo, hi)
        if math.hypot(x - C, y - C) + rad > rmax:
            continue
        if any(math.hypot(x - a, y - b) < rad + ra + keep for a, b, ra in out):
            continue
        out.append((x, y, rad))
    return out


def _bubble(cv, xs, ys, cx, cy, rad, skin, body, ink):
    """A bubble seen from above: a thin skin lit brightest on its upper left, a body a shade off the ground, a shine, and a darker crescent inside its lower right."""
    dx, dy = xs - cx, ys - cy
    d = np.hypot(dx, dy)
    facing = -(dx + dy) / (d * 1.414 + 1e-6)
    inside = d <= rad
    # a dark line outside its lower right, so a pale bubble stands off the pale ground
    cv.over((d > rad) & (d <= rad + 0.95) & (facing < 0.1), ink, np.clip(0.35 - 0.5 * facing, 0.2, 0.85))
    cv.over(inside, body, 0.55)
    shell = inside & (d >= rad - 0.95)
    cv.over(shell, skin, np.clip(0.5 + 0.5 * facing, 0.2, 1.0))
    crescent = inside & (d >= rad - 1.9) & (d < rad - 0.95) & (facing < -0.15)
    cv.over(crescent, ink, 0.6)
    hx, hy = int(round(cx - rad * 0.45 - 0.5)), int(round(cy - rad * 0.45 - 0.5))
    if 0 <= hx < N and 0 <= hy < N:
        cv.rgb[hy, hx] = (255, 255, 255)


def surface():
    xs, ys, dx, dy, r, ang = _polar()
    R = 15.6
    disc = r <= R
    n1 = fbm(N, N, 10, 3, 3)
    n2 = fbm(N, N, 4, 11, 2)
    # a surface that has been stirred: three arms winding in toward a shallow dimple, a faint ripple, and small unevenness over both
    h = 0.5 * np.sin(3 * ang - 0.44 * r + 1.2 * n1) + 0.14 * np.sin(1.05 * r + 2.0 * n1) + 0.28 * (n2 - 0.5)
    t = 0.6 + 1.2 * light(h, 1.4) + 0.16 * h - 0.1 * np.exp(-(r / 3.6) ** 2)
    # the shine of the sky, along the upper left
    t += 0.18 * np.exp(-(((xs - 10.5) / 5.0) ** 2 + ((ys - 9.5) / 2.2) ** 2))
    # the wall: shade where it stands over the liquid on the upper left, a catch of light along the lower right
    side = -(dx + dy) / (r * 1.414 + 1e-6)
    e = np.clip((r - (R - 2.6)) / 2.6, 0, 1)
    t -= e * (0.30 + 0.22 * side)
    t += e * np.clip(-side, 0, 1) * 0.1
    cv = Canvas(N)
    cv.put(disc, bands(SURF, np.clip(t, 0, 1), 0.3), 1.0)
    # the edge itself: a firm line, dark where the wall is above the liquid
    rim = disc & (r > R - 1.1)
    cv.over(rim, SURF[0], np.clip(0.55 + 0.4 * side, 0.15, 0.95))
    # specks of fat: a bright point with a paler body beside it
    for x, y, _ in _spots(5, 11, 0.5, 0.9, 12.6, 2.2):
        px, py = int(x), int(y)
        cv.rgb[py, px] = (255, 255, 255)
        cv.rgb[py, px + 1] = SURF[3]
    # bubbles, bigger toward the middle
    for x, y, rad in ((22.4, 9.2, 2.7), (8.6, 21.8, 2.4), (23.4, 21.6, 2.0), (14.0, 6.2, 1.5), (16.6, 25.4, 1.6), (6.0, 13.4, 1.4), (26.0, 15.0, 1.4)):
        _bubble(cv, xs, ys, x, y, rad, (255, 255, 255), SURF[3], SURF[0])
    return cv.image()


def film():
    xs, ys, dx, dy, r, ang = _polar()
    R = 15.4
    n = fbm(N, N, 8, 41, 3)
    m = fbm(N, N, 3.5, 77, 2)
    cover = ((n + 0.24 * (1 - r / R) + 0.10 * (m - 0.5)) > 0.52) & (r <= R)
    # drop islands too small to read
    depth = dist_in(cover, 6)
    height = blur(np.where(cover, 1.0, 0.0), 1) * 1.2 + 0.35 * m
    t = 0.5 + 2.2 * light(height, 3.0) + 0.5 * (m - 0.5) + 0.05 * np.clip(depth, 0, 3)
    cv = Canvas(N)
    cv.put(cover, bands(SCUM, np.clip(t, 0, 1), 0.3), np.where(depth <= 1, 0.84, 0.97))
    # the edge: pale where it faces the light, dull and dark on the far side
    border = edge(cover)
    side = -(dx + dy) / (r * 1.414 + 1e-6)
    cv.over(border, SCUM[4], np.clip(0.25 + 0.4 * side, 0, 0.7))
    cv.over(border, SCUM[0], np.clip(0.25 - 0.4 * side, 0, 0.7))
    # froth caught in it
    for x, y, rad in _spots(9, 5, 1.2, 1.9, 14.0, 1.4):
        if cover[int(y), int(x)]:
            _bubble(cv, xs, ys, x, y, rad, SCUM[4], SCUM[3], SCUM[1])
    # and a few dark flecks of whatever the meat gave up
    flecks = (rand(N, N, 13) < 0.035) & cover & (depth >= 2)
    cv.over(flecks, (60, 50, 44), 0.8)
    return cv.image()


def crust():
    xs, ys, dx, dy, r, ang = _polar()
    wob = fbm(N, N, 6, 5, 3)
    R = 14.9 + 2.6 * (wob - 0.5)
    disc = r <= R
    # blisters: domes of various sizes, and a fine pitting between them
    h = 0.35 * fbm(N, N, 5, 23, 3) + 0.12 * rand(N, N, 29)
    for x, y, rad in _spots(31, 12, 1.6, 3.3, 14.4, 0.4):
        d = np.hypot(xs - x, ys - y)
        h += np.where(d < rad, 0.9 * np.sqrt(np.clip(1 - (d / rad) ** 2, 0, 1)), 0.0)
    # the crust has split: cracks along the ridges of a coarse noise
    split = np.abs(fbm(N, N, 8, 61, 2) - 0.5)
    crack = (split < 0.034) & disc & (r < R - 1.5)
    h = np.where(crack, h - 0.8, h)
    t = 0.3 + 1.3 * light(h, 2.4) + 0.25 * (h - 0.3)
    cv = Canvas(N)
    cv.put(disc, bands(CHAR, np.clip(t, 0, 1), 0.28), 1.0)
    cv.over(crack, CHAR[0], 0.85)
    # embers, deep in the cracks
    glow = crack & (rand(N, N, 71) < 0.20)
    ys_i, xs_i = np.nonzero(glow)
    for k, (yy, xx) in enumerate(zip(ys_i, xs_i)):
        cv.rgb[yy, xx] = EMBER[(k + xx) % 2]
    hot = crack & (rand(N, N, 73) < 0.04)
    cv.rgb[hot] = EMBER[2]
    # the rim: a lit lip on the upper left, a deep one on the lower right
    side = -(dx + dy) / (r * 1.414 + 1e-6)
    border = edge(disc)
    cv.over(border, CHAR[4], np.clip(0.1 + 0.55 * side, 0, 0.7))
    cv.over(border, CHAR[0], np.clip(0.2 - 0.7 * side, 0, 0.9))
    # ash on the highest blisters
    ash = (h > 0.78) & disc & (rand(N, N, 79) < 0.45)
    cv.over(ash, (126, 120, 112), 0.7)
    return cv.image()


def ring():
    xs, ys, dx, dy, r, ang = _polar()
    R0 = 13.5
    d = np.abs(r - R0)
    cv = Canvas(N)
    # the band: a bright core, gold at its edges, and a glow either side
    side = -(dx + dy) / (r * 1.414 + 1e-6)
    core = d <= 1.05
    gold = (d > 1.05) & (d <= 1.85)
    halo = (d > 1.85) & (d <= 2.9)
    fade = (d > 2.9) & (d <= 3.7)
    lit = np.clip(0.5 + 0.5 * side, 0, 1)
    cv.put(core, np.where(lit[..., None] > 0.55, np.array(GOLD[0], float), np.array(GOLD[1], float)), 1.0)
    cv.put(gold, np.where(lit[..., None] > 0.45, np.array(GOLD[2], float), np.array(GOLD[3], float)), 0.92)
    cv.put(halo, GOLD[1], 0.34)
    cv.put(fade, GOLD[1], 0.12)
    # a lozenge on each quarter and a short bar on each eighth
    for k in range(4):
        a = k * math.pi / 2
        cx, cy = C + R0 * math.cos(a), C + R0 * math.sin(a)
        dd = np.abs(xs - cx) + np.abs(ys - cy)
        cv.put(dd <= 3.4, GOLD[3], 1.0)
        cv.put(dd <= 2.6, GOLD[2], 1.0)
        cv.put(dd <= 1.7, GOLD[1], 1.0)
        cv.put(dd <= 0.9, GOLD[0], 1.0)
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        cx, cy = C + R0 * math.cos(a), C + R0 * math.sin(a)
        dd = np.hypot(xs - cx, ys - cy)
        cv.put(dd <= 1.9, GOLD[2], 1.0)
        cv.put(dd <= 1.1, GOLD[0], 1.0)
    # the ticks inside: twelve small marks on a ring, fainter
    for k in range(12):
        a = k * math.pi / 6 + math.pi / 12
        cx, cy = C + 9.4 * math.cos(a), C + 9.4 * math.sin(a)
        dd = np.hypot(xs - cx, ys - cy)
        cv.put(dd <= 0.95, GOLD[1], 0.7)
    return cv.image()


PAINTERS = {'surface': surface, 'film': film, 'crust': crust, 'ring': ring}


def write(assets):
    d = os.path.join(assets, 'textures/entity/cook')
    os.makedirs(d, exist_ok=True)
    for name, paint in PAINTERS.items():
        paint().save(os.path.join(d, name + '.png'))
