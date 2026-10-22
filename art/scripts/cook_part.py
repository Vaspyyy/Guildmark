"""The seven kitchen particles (cook_art.py): 16 x 16 sprites painted pale and tinted in the game, with `region_particles.py`'s brushes, the way
`season_art.py` paints its own. Each is a short run of frames the game steps through as the particle ages (the first is the newest).

    cook_steam       4   a soft white curl that thins as it rises
    stew_bubble      4   a bubble that swells and pops
    cook_smoke_dark  4   a dark, greasy curl (painted dark: it is not tinted pale like the rest)
    cook_sizzle      4   specks of fat that spit up and fall
    cook_crumb       3   a crumb, a puff of flour, a crumb turned over
    cook_call        4   a small gold glint that lifts and fades
    cook_foam        4   beads of white foam

Every figure is drawn from fixed numbers, so a rerun gives the same files.
"""
import json
import math
import os
import random

import region_particles as rp

S = rp.S

WHITE = [(255, 255, 255), (244, 248, 252), (222, 232, 242)]
GREASE = [(126, 120, 118), (90, 85, 85), (56, 52, 56)]
FAT = [(255, 252, 234), (255, 232, 160), (232, 186, 96)]
FLOUR = [(255, 253, 246), (246, 236, 214), (222, 200, 160)]
GOLD = [(255, 255, 238), (255, 232, 150), (244, 190, 70), (200, 134, 34)]
FILM = [(255, 255, 255), (234, 245, 255), (198, 224, 248)]


def _path(points_at, n, radius_at, colors, peak_at, falloff=1.3):
    """A soft wisp along a path: [n] soft dots from the first point to the last, each [radius_at](t) wide and [peak_at](t) strong."""
    img = rp.blank()
    for i in range(n + 1):
        t = i / n
        x, y = points_at(t)
        rp.soft_dot(img, x, y, radius_at(t), colors, falloff, peak_at(t))
    return img


def _rise(t, lead=0.1, span=0.8):
    """How strong a column of vapour is along its height: nothing at its foot, full below the middle, fading at the top."""
    return 0.4 + 0.6 * math.sin(math.pi * min(0.999, t * span + lead))


def steam():
    """A curl that rises and thins: one S, drifting a little and spreading as it ages, each frame wider and fainter than the last."""
    out = []
    for phase, base, peak in ((0.0, 2.3, 225), (0.05, 2.6, 185), (0.1, 2.9, 135), (0.15, 3.2, 82)):
        def pts(t, phase=phase):
            return 8.0 + 3.1 * math.sin(2 * math.pi * (t * 1.05 + phase)), 14.2 - 12.2 * t
        out.append(_path(pts, 28, lambda t, b=base: b * (1.0 - 0.45 * t) + 0.3, WHITE, lambda t, p=peak: p * _rise(t), 1.25))
    return out


def smoke_dark():
    """A greasy curl: thicker and darker than steam, billowing in lumps, thinning to a haze."""
    out = []
    rng = random.Random(7)
    for phase, base, peak, lump in ((0.0, 3.4, 240, 0.9), (0.06, 3.5, 205, 1.1), (0.12, 3.5, 155, 1.3), (0.18, 3.5, 100, 1.5)):
        wob = [rng.uniform(-1, 1) for _ in range(40)]

        def pts(t, phase=phase, wob=wob):
            return 8.0 + 3.0 * math.sin(2 * math.pi * (t * 0.95 + phase)) + 0.5 * wob[int(t * 20)], 14.0 - 11.6 * t

        out.append(_path(pts, 24, lambda t, b=base, lm=lump: b * (0.75 + 0.25 * math.sin(t * 9.0 * lm) ** 2) * (1.0 - 0.3 * t) + 0.3, GREASE,
                         lambda t, p=peak: p * _rise(t, 0.08, 0.85), 1.0))
    return out


def _ring(img, cx, cy, r, width, alpha, a_from=0.0, a_to=2 * math.pi):
    """A thin ring of the bubble's skin, or the arc of one between two angles, lit brightest where it faces the upper left."""
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            e = abs(math.hypot(dx, dy) - r)
            if e >= width:
                continue
            ang = math.atan2(dy, dx) % (2 * math.pi)
            if not (a_from <= ang <= a_to):
                continue
            t = 1.0 - e / width
            facing = 0.5 - 0.5 * (dx + dy) / (math.hypot(dx, dy) * 1.414 + 1e-6)
            rp.over(img, x, y, rp.ramp(FILM, 0.15 + 0.7 * facing), alpha * t ** 0.8)


def bubble():
    """A bubble swelling on the surface and bursting: a small ring, a bigger one, a thin full one, then the broken skin and the spray."""
    out = []
    for r, a in ((2.3, 235), (3.7, 225), (5.1, 205)):
        img = rp.blank()
        rp.soft_dot(img, 8.0, 8.0, r, FILM[1:], 0.4, 70)
        _ring(img, 8.0, 8.0, r, 1.0, a)
        # the shine, upper left, and a fainter one low on the right
        rp.over(img, int(8 - r * 0.55), int(8 - r * 0.55), (255, 255, 255), 255)
        if r > 5:
            rp.over(img, int(8 - r * 0.55) + 1, int(8 - r * 0.55), (255, 255, 255), 200)
            rp.over(img, int(8 + r * 0.5), int(8 + r * 0.45), (255, 255, 255), 150)
        out.append(img)
    # the burst: broken bits of the skin, and droplets thrown off it
    img = rp.blank()
    for lo, hi, a in ((0.2, 1.1, 150), (2.4, 3.3, 150), (4.2, 5.0, 120)):
        _ring(img, 8.0, 8.0, 5.6, 0.85, a, lo, hi)
    for ang, d, a in ((0.7, 6.4, 255), (1.9, 6.8, 230), (3.0, 6.2, 255), (4.0, 6.6, 210), (5.0, 6.4, 255), (5.8, 5.8, 200)):
        rp.over(img, int(8.0 + d * math.cos(ang)), int(8.0 + d * math.sin(ang)), (255, 255, 255), a)
    rp.soft_dot(img, 8.0, 8.0, 3.2, FILM[1:], 1.6, 60)
    out.append(img)
    return out


def sizzle():
    """Specks of fat spat up from a hot pan: climbing, then falling and fading. Each is a bright head with a little dark gold to its lower right."""
    out = []
    # (x, y, tail length, opacity) of the specks in each frame
    frames = (
        [(5, 12, 0, 255), (8, 13, 0, 255), (11, 12, 0, 235), (7, 11, 0, 150)],
        [(4, 9, 2, 255), (8, 7, 3, 255), (12, 9, 2, 245), (6, 10, 1, 205), (10, 11, 1, 185)],
        [(3, 7, 2, 240), (8, 4, 2, 255), (13, 7, 2, 225), (5, 5, 1, 205), (11, 5, 1, 195)],
        [(2, 10, 1, 155), (7, 8, 1, 175), (13, 10, 1, 145), (4, 12, 0, 115), (10, 13, 0, 105)],
    )
    for sp in frames:
        img = rp.blank()
        for x, y, tail, a in sp:
            rp.over(img, x, y, FAT[0], a)
            rp.over(img, x + 1, y, FAT[1], a * 0.6)
            rp.over(img, x + 1, y + 1, FAT[2], a * 0.45)
            rp.over(img, x, y + 1, FAT[2], a * 0.55 if tail == 0 else 0)
            for j in range(1, tail + 1):
                rp.over(img, x, y + j, FAT[1], a * (0.6 - 0.18 * j))
            rp.over(img, x, y - 1, FAT[2], a * 0.25)
        out.append(img)
    return out


def _lump(img, points, k=1.0, colors=FLOUR, cut=0.35):
    """A solid scrap from a polygon, lit from the upper left: bright where it faces the light, its lower right a shade toward crust."""
    cov = rp.polygon(points, k)
    xs = [8 + (x - 8) * k for x, _ in points]
    ys = [y for _, y in points]
    cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
    size = max(max(xs) - min(xs), max(ys) - min(ys), 1.0)
    for y in range(S):
        for x in range(S):
            c = cov[y][x]
            if c < cut:
                continue
            lit = (x + 0.5 - cx) * 0.55 + (y + 0.5 - cy) * 0.75
            rp.over(img, x, y, rp.ramp(colors, 0.5 + lit / size * 0.9), 255 * min(1.0, c * 1.3))


def crumb():
    """Three looks of crumb: a chunk with a scrap beside it, a puff of flour with two flecks, a crumb turned over."""
    out = []
    img = rp.blank()
    _lump(img, [(4.2, 6.4), (6.6, 4.6), (9.4, 5.0), (10.2, 7.6), (8.6, 9.6), (5.6, 9.2)])
    _lump(img, [(10.4, 10.4), (12.6, 9.8), (13.2, 12.2), (11.0, 12.8)])
    rp.over(img, 6, 6, FLOUR[0], 255)
    rp.over(img, 7, 5, FLOUR[0], 255)
    rp.over(img, 3, 11, FLOUR[1], 255)
    out.append(img)
    img = rp.blank()
    rp.soft_dot(img, 7.6, 8.2, 5.0, FLOUR[:2], 0.9, 225)
    rp.soft_dot(img, 9.2, 6.6, 3.2, FLOUR[:2], 0.8, 190)
    for x, y in ((3, 11), (12, 4), (13, 11)):
        rp.over(img, x, y, FLOUR[0], 255)
        rp.over(img, x + 1, y, FLOUR[1], 255)
    out.append(img)
    img = rp.blank()
    _lump(img, [(6.0, 4.4), (9.4, 5.4), (10.4, 8.4), (8.6, 11.0), (5.8, 10.0), (4.8, 7.0)], 0.78)
    rp.over(img, 7, 6, FLOUR[0], 255)
    for x, y in ((12, 11), (3, 12)):
        rp.over(img, x, y, FLOUR[1], 255)
    out.append(img)
    return out


def call():
    """A small gold glint that lifts and fades: a sparkle that grows, peaks, and rises away dimmer."""
    out = []
    for size, dy, fade in ((1, 2, 0.9), (2, 1, 1.0), (3, 0, 1.0), (2, -2, 0.55)):
        g = rp.glint(GOLD, size)
        img = rp.blank()
        for y in range(S):
            for x in range(S):
                r, gg, b, a = g.getpixel((x, y))
                if a and 0 <= y + dy < S:
                    img.putpixel((x, y + dy), (r, gg, b, int(a * fade)))
        out.append(img)
    return out


def _bead(img, x, y, r):
    """A bead of foam: round with a soft edge, white in the upper left, a pale blue in its lower right, a bright spot of shine."""
    for py in range(S):
        for px in range(S):
            dx, dy = px + 0.5 - x, py + 0.5 - y
            d = math.hypot(dx, dy)
            cov = max(0.0, min(1.0, r - d + 0.5))
            if cov <= 0:
                continue
            lit = (dx * 0.6 + dy * 0.75) / max(r, 1.0)
            rp.over(img, px, py, rp.ramp(WHITE, 0.15 + 0.55 * (d / r) * 0.5 + 0.35 * max(0.0, lit)), 255 * min(1.0, cov * 1.1))
    rp.over(img, int(x - r * 0.4), int(y - r * 0.45), (255, 255, 255), 255)


def foam():
    """Beads of white foam piling up: three small ones, then more and bigger, then a heap of them with a film."""
    beads = (
        [(6.0, 9.6, 1.7), (9.4, 9.2, 1.5), (7.6, 6.9, 1.3)],
        [(5.2, 10.2, 2.1), (9.8, 9.8, 1.9), (7.4, 6.8, 1.7), (11.8, 6.6, 1.2)],
        [(4.6, 10.8, 2.4), (9.2, 10.6, 2.2), (6.8, 7.0, 2.0), (11.4, 7.2, 1.7), (8.0, 4.0, 1.3)],
        [(4.2, 11.0, 2.7), (9.0, 11.0, 2.5), (13.0, 10.0, 1.6), (6.6, 6.8, 2.3), (11.0, 6.6, 2.0), (8.2, 3.4, 1.6)],
    )
    out = []
    for k, bs in enumerate(beads):
        img = rp.blank()
        if k == 3:
            rp.soft_dot(img, 8.0, 9.0, 6.4, WHITE[1:], 1.3, 90, 0.8)
        for x, y, r in bs:
            _bead(img, x, y, r)
        out.append(img)
    return out


PARTICLES = {
    'cook_steam': steam,
    'stew_bubble': bubble,
    'cook_smoke_dark': smoke_dark,
    'cook_sizzle': sizzle,
    'cook_crumb': crumb,
    'cook_call': call,
    'cook_foam': foam,
}


def write(assets):
    """textures/particle/<id>_<n>.png for every frame and particles/<id>.json listing them, as region_particles.py writes its own."""
    tex = os.path.join(assets, 'textures/particle')
    defs = os.path.join(assets, 'particles')
    os.makedirs(tex, exist_ok=True)
    os.makedirs(defs, exist_ok=True)
    for name, paint in PARTICLES.items():
        sprites = paint()
        for i, img in enumerate(sprites):
            img.save(os.path.join(tex, '%s_%d.png' % (name, i)))
        with open(os.path.join(defs, name + '.json'), 'w') as f:
            json.dump({'textures': ['guildmark:%s_%d' % (name, i) for i in range(len(sprites))]}, f, indent=2)
            f.write('\n')
