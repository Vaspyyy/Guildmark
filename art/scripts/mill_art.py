"""The mills' turning parts, which the game draws turning on their hubs (client/mill): a windmill's sails and a watermill's
wheel.

The sails are two models that turn as one. The timber frame: the windshaft coming out of the cap, the iron-bound poll end
on it, and four sails, each a whip with its sail bars, a hemlath along its outer edge and a middle lath, the outer half of
each on a joint of its own (`tip0` to `tip3`) so that a broken sail can hang from where it broke. It is painted in its
city's wood, one texture a style. The cloth: each sail's canvas spread on the bars (`sail`, `sail_tip`), a torn one shown
instead on a broken sail (`torn`, `torn_tip`), and the roll it makes furled along the whip (`roll`, `roll_tip`), all on one
white canvas texture the game tints with the cloth's dye. The four sails share their cubes' places in the texture. The
sails reach eight and a half blocks from the hub and their cloth three and a half blocks out from the whip, as the old
block-built sails did, and turn in the half block in front of the hub (z -12.5 to -8.5).

The wheel is seven blocks across: an octagonal shroud of boards each side, eight spokes each side into a nave, sixteen
paddles between the shrouds reaching three and a half blocks out, and the stub of the axle out in front; it fills the
two rows the plan keeps clear (z -24 to 7.5) and goes round on the axle. One texture a style.

Box UV declared at 128 x 128 (the wheel 128 x 192) and painted and shipped at twice that, the way the mod's other models
are. Model space is the hub block's: its middle is the origin, one block is 16, y is down and -z is out of the mill.

    python3 -B art/scripts/mill_art.py [--check] [assets dir [preview dir [models dir]]]

Writes textures/entity/mill/ (sails_<style>.png, cloth.png, wheel_<style>.png) into the assets (default
src/main/resources/assets/guildmark), the meshes straight into client/mill/MillMesh.kt, windmill_sails.bbmodel,
windmill_cloth.bbmodel and water_wheel.bbmodel into the models dir (default art/models), and, given a preview dir,
mill_sails.png (each style's sails turning, braked and furled, and broken), mill_wheel.png (each style's wheel in its
stream) and mill_turning.gif (a quarter turn of the sails and of the wheel, which loops).

--check then compiles MillMesh.kt and client/mill/MillModels.kt (with stand-ins for the little of the mod they use)
against Minecraft's own classes with the compile kit (KCHECK_WORK, default /tmp/kcheck), draws each model through
Minecraft's ModelPart in a spread of poses (turning, furled, each sail broken and swinging) and compares every corner
of every quad, and where it samples the texture, with the faces the previews draw and the textures are painted on.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hdkit as hd  # noqa: E402
from modelkit import Cube, Model, Part  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/mill/MillMesh.kt')
HD = 2
STYLES = ['temperate', 'desert', 'northern', 'savanna']

# ================================================================ geometry
# A sail, as arm 0 (pointing up, -y): r is how far out from the hub; its cloth trails on +x, away from the way the sails
# turn (against the clock, seen from in front).
BREAK = 72                          # where a sail's outer half joins its inner half (4.5 blocks out)
FRONT = -12.5                       # the front of the whips and hemlaths
CLOTH_Z = -8.75                     # the cloth, just behind the bars
INNER_BARS = (24, 36, 48, 60)       # the sail bars (each 3 px), r of their inner edges
OUTER_BARS = (74, 86, 98, 110, 122)
CLOTH_X = 4                         # the cloth spreads from the whip's edge ...
CLOTH_W = 50                        # ... to under the hemlath


def t_cube(name, size, faces=None):
    """A distinct piece: its place in the texture, and the shape it is painted on (its own origin is where it's painted)."""
    return Cube((0, 0, 0), size, name=name, faces=faces)


def inst(t, origin):
    """The piece [t] put at [origin], sharing [t]'s place in the texture."""
    c = Cube(origin, t.size, name=t.name, faces=t.faces)
    c.uv = t.uv
    c.template = t
    return c


def sails_pieces():
    """The frame's distinct pieces (the four sails share them)."""
    return {
        'boss': t_cube('boss', (10, 10, 9)),
        'boss45': t_cube('boss45', (10, 10, 8)),
        'shaft': t_cube('shaft', (8, 8, 16)),
        'whip_in': t_cube('whip_in', (8, 68, 4)),
        'whip_out': t_cube('whip_out', (6, 64, 4)),
        'bar0': t_cube('bar0', (59, 3, 3)),
        'bar1': t_cube('bar1', (59, 3, 3)),
        'bar2': t_cube('bar2', (59, 3, 3)),
        'tip_bar': t_cube('tip_bar', (60, 5, 3)),
        'hem_in': t_cube('hem_in', (4, 49, 3)),
        'hem_out': t_cube('hem_out', (4, 58, 3)),
        'mid_in': t_cube('mid_in', (2, 47, 3)),
        'mid_out': t_cube('mid_out', (2, 58, 3)),
    }


def sails_model(t):
    arms = []
    for k in range(4):
        inner = [inst(t['whip_in'], (-4, -BREAK, FRONT))]
        inner += [inst(t[f'bar{j % 3}'], (-6, -r - 3, -12)) for j, r in enumerate(INNER_BARS)]
        inner += [inst(t['hem_in'], (50, -BREAK, FRONT)), inst(t['mid_in'], (27, -BREAK, -12.25))]
        outer = [inst(t['whip_out'], (-3, -64, FRONT))]
        outer += [inst(t[f'bar{(j + 1) % 3}'], (-6, -(r - BREAK) - 3, -12)) for j, r in enumerate(OUTER_BARS)]
        outer += [inst(t['tip_bar'], (-6, -63, -12)), inst(t['hem_out'], (50, -58, FRONT)), inst(t['mid_out'], (27, -58, -12.25))]
        tip = Part(f'tip{k}', (0, -BREAK, 0), cubes=outer)
        arms.append(Part(f'arm{k}', (0, 0, 0), (0, 0, 90 * k), cubes=inner, children=[tip]))
    boss45 = Part('boss45', (0, 0, 0), (0, 0, 45), cubes=[inst(t['boss45'], (-5, -5, -14.5))])
    shaft = [inst(t['shaft'], (-4, -4, z)) for z in (-6, 10, 26)]
    rotor = Part('rotor', (0, 0, 0), cubes=[inst(t['boss'], (-5, -5, -15))] + shaft, children=[boss45] + arms)
    return Model('windmill_sails', 128, 128, [rotor])


def cloth_pieces():
    return {
        'sail_in': t_cube('sail_in', (CLOTH_W, 48, 0), faces=['front']),
        'sail_out': t_cube('sail_out', (CLOTH_W, 59, 0), faces=['front']),
        'torn_in': t_cube('torn_in', (CLOTH_W, 48, 0), faces=['front']),
        'torn_out': t_cube('torn_out', (CLOTH_W, 59, 0), faces=['front']),
        'roll_in': t_cube('roll_in', (4, 46, 3)),
        'roll_out': t_cube('roll_out', (4, 56, 3)),
    }


def cloth_model(t):
    arms = []
    for k in range(4):
        x = (CLOTH_X, 0, 0)
        tip = Part(f'tip{k}', (0, -BREAK, 0), children=[
            Part(f'sail_tip{k}', x, cubes=[inst(t['sail_out'], (0, -59, CLOTH_Z))]),
            Part(f'torn_tip{k}', x, cubes=[inst(t['torn_out'], (0, -59, CLOTH_Z))]),
            Part(f'roll_tip{k}', x, cubes=[inst(t['roll_out'], (0, -57, -10))]),
        ])
        arms.append(Part(f'arm{k}', (0, 0, 0), (0, 0, 90 * k), children=[
            Part(f'sail{k}', x, cubes=[inst(t['sail_in'], (0, -BREAK, CLOTH_Z))]),
            Part(f'torn{k}', x, cubes=[inst(t['torn_in'], (0, -BREAK, CLOTH_Z))]),
            Part(f'roll{k}', x, cubes=[inst(t['roll_in'], (0, -71, -10))]),
            tip,
        ]))
    return Model('windmill_cloth', 128, 128, [Part('rotor', (0, 0, 0), children=arms)])


# The wheel: its shroud's boards meet at the corners of an octagon whose sides are 50.8 px from the hub (its corners 55)
APOTHEM = 50.8
WATER_HUB = (25.8, 41.8)            # how far under the hub's middle the stream's surface lies, hub low and hub high


def wheel_pieces():
    return {
        'nave': t_cube('nave', (14, 14, 33)),
        'nave45': t_cube('nave45', (14, 14, 32)),
        'axle': t_cube('axle', (8, 8, 15)),
        'spoke': t_cube('spoke', (4, 44, 3)),
        'rim0': t_cube('rim0', (42, 6, 4)),
        'rim1': t_cube('rim1', (42, 6, 4)),
        'paddle0': t_cube('paddle0', (2, 19, 24)),
        'paddle1': t_cube('paddle1', (2, 19, 24)),
    }


def wheel_model(t):
    kids = [Part('nave45', (0, 0, 0), (0, 0, 45), cubes=[inst(t['nave45'], (-7, -7, -25))])]
    for k in range(8):
        a = 22.5 + 45 * k
        # Every other board a hair further in, so that where two overlap at a corner their faces don't fight
        kids.append(Part(f'rim{k}', (0, 0, 0), (0, 0, a), cubes=[inst(t[f'rim{k % 2}'], (-21, -APOTHEM, -24 + 0.25 * (k % 2)))]))
        kids.append(Part(f'rim{k + 8}', (0, 0, 0), (0, 0, a), cubes=[inst(t[f'rim{(k + 1) % 2}'], (-21, -APOTHEM, 3.5 + 0.25 * (k % 2)))]))
    for k in range(8):
        kids.append(Part(f'spoke{k}', (0, 0, 0), (0, 0, 45 * k), cubes=[inst(t['spoke'], (-2, -50, -23.5)), inst(t['spoke'], (-2, -50, 4))]))
    for k in range(16):
        kids.append(Part(f'paddle{k}', (0, 0, 0), (0, 0, 22.5 * k), cubes=[inst(t[f'paddle{k % 2}'], (-1, -55, -20))]))
    wheel = Part('wheel', (0, 0, 0), cubes=[inst(t['nave'], (-7, -7, -25.5)), inst(t['axle'], (-4, -4, -40))], children=kids)
    return Model('water_wheel', 128, 192, [wheel])


# ================================================================ packing

def used_box(c):
    """The part of a cube's box-UV footprint that its drawn faces use, from its texture offset: (x0, y0, x1, y1)."""
    old = c.uv
    c.uv = (0, 0)
    rs = [r for f, r in c.rects().items() if c.shows(f) and r[2] > 0 and r[3] > 0]
    c.uv = old
    return min(r[0] for r in rs), min(r[1] for r in rs), max(r[0] + r[2] for r in rs), max(r[1] + r[3] for r in rs)


def pack(pieces, w, h, name):
    """Shelf-packs the distinct pieces' used footprints into a w x h texture (tallest first)."""
    items = sorted(pieces, key=lambda c: (-(used_box(c)[3] - used_box(c)[1]), c.name))
    x = y = shelf = 0
    for c in items:
        x0, y0, x1, y1 = used_box(c)
        fw, fh = x1 - x0, y1 - y0
        if x + fw > w:
            x, y, shelf = 0, y + shelf, 0
        c.uv = (x - x0, y - y0)
        x += fw
        shelf = max(shelf, fh)
    if y + shelf > h:
        raise SystemExit(f'{name}: the texture is too small, it needs {y + shelf} rows')
    return y + shelf


# ================================================================ noise

def _hash(ix, iy, iz, seed):
    a = np.asarray(ix, np.int64).astype(np.uint32)
    b = np.asarray(iy, np.int64).astype(np.uint32)
    c = np.asarray(iz, np.int64).astype(np.uint32)
    with np.errstate(over='ignore'):
        h = (a * np.uint32(73856093)) ^ (b * np.uint32(19349663)) ^ (c * np.uint32(83492791)) ^ np.uint32((int(seed) * 2654435761) & 0xFFFFFFFF)
        h = h ^ (h >> np.uint32(15))
        h = h * np.uint32(2246822519)
        h = h ^ (h >> np.uint32(13))
        h = h * np.uint32(3266489917)
        h = h ^ (h >> np.uint32(16))
    return h.astype(np.float64) / 4294967296.0


def _smooth(t):
    return t * t * (3 - 2 * t)


def vnoise(x, y, z, seed):
    """Smooth value noise in 0..1 at lattice coordinates."""
    x0, y0, z0 = np.floor(x), np.floor(y), np.floor(z)
    fx, fy, fz = _smooth(x - x0), _smooth(y - y0), _smooth(z - z0)
    x0, y0, z0 = x0.astype(np.int64), y0.astype(np.int64), z0.astype(np.int64)
    out = 0.0
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                w = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + w * _hash(x0 + dx, y0 + dy, z0 + dz, seed)
    return out


def fbm(P, cells, seed, octaves=3):
    """Fractal noise in 0..1 over model-space points P (..., 3), features about cells = (cx, cy, cz) px across."""
    k = 1.0 / np.asarray(cells, float)
    tot, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        tot = tot + amp * vnoise(P[..., 0] * k[0] + 17.3 * o, P[..., 1] * k[1] + 5.1 * o, P[..., 2] * k[2] + 11.7 * o, seed + 31 * o)
        norm += amp
        amp *= 0.5
        k = k * 2.0
    return tot / norm


def rand(P, seed):
    """A random number in 0..1 for each HD texel (points on a half-px grid)."""
    q = np.floor(P * HD + 1e-6).astype(np.int64)
    return _hash(q[..., 0], q[..., 1], q[..., 2], seed)


def sstep(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# ================================================================ painting

NORMALS = {'front': (0, 0, -1), 'back': (0, 0, 1), 'right': (-1, 0, 0), 'left': (1, 0, 0), 'top': (0, -1, 0), 'bottom': (0, 1, 0)}
FACE_T = {'top': 0.05, 'bottom': -0.08, 'front': 0.0, 'back': -0.03, 'right': -0.015, 'left': -0.015}


def texel_points(c, face, W, H):
    """Each texel's middle on [face] of [c], in the cube's own model space: (H, W, 3)."""
    x0, y0, z0 = c.origin
    x1, y1, z1 = x0 + c.size[0], y0 + c.size[1], z0 + c.size[2]
    A, B, D = {
        'front': ((x0, y0, z0), (x1, y0, z0), (x0, y1, z0)),
        'back': ((x1, y0, z1), (x0, y0, z1), (x1, y1, z1)),
        'right': ((x0, y0, z1), (x0, y0, z0), (x0, y1, z1)),
        'left': ((x1, y0, z0), (x1, y0, z1), (x1, y1, z0)),
        'top': ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0)),
        # (the bottom's texture runs back to front like the top's, as Minecraft lays it; modelkit's world_faces has it
        # the other way round)
        'bottom': ((x0, y1, z1), (x1, y1, z1), (x0, y1, z0)),
    }[face]
    A, B, D = np.array(A, float), np.array(B, float), np.array(D, float)
    s = (np.arange(W) + 0.5) / W
    t = (np.arange(H) + 0.5) / H
    return A + s[None, :, None] * (B - A) + t[:, None, None] * (D - A)


def edges(W, H):
    """How far each texel is from the face's border, in texels (0.5 on the border)."""
    i = np.arange(W) + 0.5
    j = np.arange(H) + 0.5
    return np.minimum(np.minimum(i[None, :], W - i[None, :]), np.minimum(j[:, None], H - j[:, None]))


def paint(pieces, w, h, painters):
    """The HD texture of a model's distinct pieces, each face painted by its piece's painter."""
    rgb = np.zeros((h * HD, w * HD, 3))
    alpha = np.zeros((h * HD, w * HD))
    for c in pieces:
        p = painters[c.name]
        for face, (x, y, fw, fh) in c.rects().items():
            if not c.shows(face) or fw == 0 or fh == 0:
                continue
            X, Y, W, H = x * HD, y * HD, fw * HD, fh * HD
            col, a = p(texel_points(c, face, W, H), face, np.array(NORMALS[face], float), edges(W, H), c)
            rgb[Y:Y + H, X:X + W] = col
            alpha[Y:Y + H, X:X + W] = a
    out = np.dstack([np.clip(rgb, 0, 255), np.clip(alpha, 0, 1) * 255]).round().astype(np.uint8)
    out[out[..., 3] == 0] = 0
    return Image.fromarray(out, 'RGBA')


def bands2(pal, t, dither=0.35):
    """hdkit.bands over any 2D face."""
    return hd.bands(pal, np.clip(t, 0, 1), dither)


def long_axis(c):
    return int(np.argmax(c.size))


def grain(P, n, edge, c, seed, ring=2.4, figure=1.0, knots=True):
    """A timber's tone (0..1, before its palette): flat-sawn grain along its long axis, from a pith off to one side of it so
    that its faces show the arcs of the rings, long streaks in the fibre, a knot now and then, its edges worn darker."""
    a = long_axis(c)
    q = [i for i in range(3) if i != a]
    cen = [c.origin[i] + c.size[i] / 2 for i in q]
    off = max(c.size[q[0]], c.size[q[1]]) * 1.6 + 3
    ang = (seed % 97) / 97 * 2 * math.pi
    p0, p1 = cen[0] + off * math.cos(ang), cen[1] + off * math.sin(ang)
    cells = [3.0, 3.0, 3.0]
    cells[a] = 18.0
    warp = 2.0 * fbm(P, cells, seed, 3)
    r = np.hypot(P[..., q[0]] - p0, P[..., q[1]] - p1) + warp
    f = (r / ring) % 1.0
    late = np.clip(1 - np.abs(f - 0.2) / 0.2, 0, 1) ** 1.5
    cells = [0.9, 0.9, 0.9]
    cells[a] = 10.0
    streak = fbm(P, cells, seed + 7, 2)
    t = 0.56 + 0.32 * (streak - 0.5) - 0.2 * late * figure
    if abs(n[a]) > 0.5:  # end grain: darker, the rings plain
        t = t - 0.1 - 0.08 * late
    if knots and c.size[a] >= 16:
        L = P[..., a] - c.origin[a]
        m = np.floor(L / 22.0)
        h1 = _hash(m.astype(np.int64), 3, 1, seed)
        h2 = _hash(m.astype(np.int64), 5, 2, seed)
        lk = m * 22.0 + 6 + 10 * h1
        across = P[..., q[0]] - (c.origin[q[0]] + c.size[q[0]] * h2)
        k = np.exp(-((L - lk) / 1.1) ** 2 - (across / 0.9) ** 2) * (h1 > 0.45)
        t = t - 0.32 * k
    t = t + (rand(P, seed + 3) - 0.5) * 0.05
    t = t - 0.2 * (edge < 1.0) - 0.06 * ((edge >= 1.0) & (edge < 2.0))
    return t


def nails(P, at, size=0.65):
    """1 on the heads of nails at points [at] (model px), 0 elsewhere; the heads' tone across them in the second array."""
    head = np.zeros(P.shape[:2])
    lit = np.zeros(P.shape[:2])
    for x, y, z in at:
        d = np.sqrt((P[..., 0] - x) ** 2 + (P[..., 1] - y) ** 2 + (P[..., 2] - z) ** 2)
        m = d < size
        head = np.maximum(head, m)
        lit = np.where(m, 0.5 - 0.5 * ((P[..., 0] - x) + (P[..., 1] - y)) / size, lit)
    return head > 0, lit


# Each style's woods, darkest first: the sails' frame (the city's planks), the windshaft and the wheel's spokes and nave
# (its timber), and the wheel's boards and paddles (its darker planks)
PLANKS = {
    'temperate': [(30, 24, 22), (54, 42, 34), (80, 62, 46), (104, 82, 58), (128, 104, 74), (152, 128, 96)],
    'desert': [(80, 68, 50), (124, 108, 80), (160, 144, 108), (188, 174, 132), (210, 198, 156), (230, 220, 182)],
    'northern': [(16, 13, 13), (29, 24, 22), (44, 36, 31), (61, 50, 41), (80, 66, 52), (104, 87, 68)],
    'savanna': [(50, 26, 20), (90, 46, 30), (128, 68, 40), (158, 90, 52), (182, 112, 68), (204, 136, 90)],
}
TIMBER = {
    'temperate': [(20, 15, 13), (38, 28, 21), (58, 44, 31), (80, 62, 43), (102, 80, 57), (124, 100, 73)],
    'desert': [(58, 40, 28), (98, 70, 46), (134, 100, 66), (164, 126, 84), (188, 150, 104), (208, 172, 126)],
    'northern': [(14, 10, 9), (28, 20, 15), (44, 31, 21), (60, 43, 29), (78, 57, 39), (98, 74, 53)],
    'savanna': [(42, 23, 17), (78, 41, 27), (114, 61, 37), (144, 81, 49), (170, 101, 63), (192, 123, 81)],
}
BOARDS = {
    'temperate': [(14, 10, 9), (26, 19, 15), (40, 29, 21), (56, 41, 29), (73, 55, 38), (92, 70, 50)],
    'desert': [(46, 30, 22), (80, 54, 38), (112, 80, 56), (140, 104, 72), (164, 126, 90), (184, 146, 108)],
    'northern': [(14, 10, 9), (26, 19, 15), (40, 29, 21), (56, 41, 29), (73, 55, 38), (92, 70, 50)],
    'savanna': [(14, 10, 9), (26, 19, 15), (40, 29, 21), (56, 41, 29), (73, 55, 38), (92, 70, 50)],
}
IRON = hd.PAL['iron']
RUST = [(40, 20, 14), (72, 36, 20), (104, 54, 28), (132, 74, 38)]


def weather(style, rgb, P, n, seed):
    """Each city's weathering over its wood: the temperate lands' silver-grey and lichen, the desert's bleaching and dust, the
    north's tar rubbed brown at the edges, the savanna's sun-faded red-brown and dust."""
    up = max(0.0, -n[1]) + 0.5 * max(0.0, -n[2])
    if style == 'temperate':
        g = fbm(P, (7, 7, 7), seed + 11, 3)
        rgb = hd.mix(rgb, (120, 116, 110), np.clip(0.12 + 0.35 * (g - 0.35) + 0.12 * up, 0, 0.45))
        li = fbm(P, (2.2, 2.2, 2.2), seed + 13, 2)
        rgb = hd.mix(rgb, (118, 124, 74), np.clip((li - 0.7) * 4.0, 0, 0.55) * (0.4 + 0.6 * up))
    elif style == 'desert':
        g = fbm(P, (8, 8, 8), seed + 11, 3)
        rgb = hd.mix(rgb, (226, 214, 184), np.clip(0.1 + 0.25 * (g - 0.4) + 0.1 * up, 0, 0.35))
        dust = fbm(P, (3, 3, 3), seed + 17, 2)
        rgb = hd.mix(rgb, (196, 170, 128), np.clip((dust - 0.6) * 2.5, 0, 0.4))
    elif style == 'northern':
        g = fbm(P, (2.5, 2.5, 2.5), seed + 11, 2)
        rgb = hd.mix(rgb, (98, 72, 50), np.clip((g - 0.66) * 2.2, 0, 0.45))
        drip = fbm(P, (0.8, 9, 0.8), seed + 19, 2)
        rgb = hd.mix(rgb, (8, 7, 8), np.clip((drip - 0.62) * 3.0, 0, 0.5))
    elif style == 'savanna':
        g = fbm(P, (8, 8, 8), seed + 11, 3)
        rgb = hd.mix(rgb, (184, 132, 98), np.clip(0.08 + 0.3 * (g - 0.4) + 0.1 * up, 0, 0.35))
        dust = fbm(P, (3, 3, 3), seed + 17, 2)
        rgb = hd.mix(rgb, (178, 140, 100), np.clip((dust - 0.62) * 2.5, 0, 0.35))
    return rgb


def wood_painter(style, pal, seed, nail_at=(), ring=2.4, figure=1.0, wet=None):
    pal = np.asarray(pal, float)

    def p(P, face, n, edge, c):
        t = grain(P, n, edge, c, seed, ring, figure) + FACE_T[face]
        if wet is not None:
            t = t - 0.16 * wet(P)
        rgb = bands2(pal, t)
        rgb = weather(style, rgb, P, n, seed)
        if wet is not None:
            w = wet(P)
            slime = fbm(P, (1.6, 1.6, 1.6), seed + 23, 2)
            rgb = hd.mix(rgb, (34, 52, 30), np.clip((slime - 0.55) * 3.0, 0, 0.7) * w)
            rgb = hd.mix(rgb, (12, 14, 16), 0.25 * w)
        if nail_at:
            head, lit = nails(P, nail_at)
            rgb = np.where(head[..., None], bands2(IRON, 0.35 + 0.35 * lit, 0.2), rgb)
        return rgb, np.ones(P.shape[:2])
    return p


def iron_painter(seed, rivets=(), hoops=None, base=0.42):
    """Forged iron, blackened and a little rusty; rivets at [rivets]."""
    def p(P, face, n, edge, c):
        g = fbm(P, (2.5, 2.5, 2.5), seed, 3)
        t = base + 0.25 * (g - 0.5) + FACE_T[face] * 1.5 - 0.22 * (edge < 1.0) + 0.06 * ((edge >= 1.0) & (edge < 2.0))
        t = t + (rand(P, seed + 1) - 0.5) * 0.06
        rgb = bands2(IRON, t, 0.3)
        rust = fbm(P, (1.8, 1.8, 1.8), seed + 5, 2)
        rgb = hd.mix(rgb, bands2(RUST, 0.3 + 0.6 * rust, 0.3), np.clip((rust - 0.6) * 2.5, 0, 0.6))
        if rivets:
            head, lit = nails(P, rivets, 0.75)
            rgb = np.where(head[..., None], bands2(IRON, 0.5 + 0.45 * lit, 0.2), rgb)
        return rgb, np.ones(P.shape[:2])
    return p


def banded(wood, iron, inside):
    """Wood with iron hoops where inside(P) says."""
    def p(P, face, n, edge, c):
        w, a = wood(P, face, n, edge, c)
        i, _ = iron(P, face, n, edge, c)
        m = inside(P)
        return np.where(m[..., None], i, w), a
    return p


# ---- canvas ----
CANVAS = [(62, 58, 54), (108, 102, 92), (156, 150, 136), (196, 192, 176), (224, 222, 208), (244, 243, 234)]
PANEL = CLOTH_W / 6.0           # the cloths a sail is sewn of, side by side along its length
REEFS = (42, 56, 90, 104, 118)  # rows of reef points across the sail


def sail_coords(P, c):
    """(x across the sail from the whip's edge, r out from the hub) of points on a cloth card."""
    out = c.name.endswith('_out')
    return P[..., 0], (BREAK - P[..., 1]) if out else -P[..., 1]


# Where the cloth is held, along a sail (its bars) and across it (the whip's edge, the middle lath, the hemlath)
BAYS_R = [(a, a + 3) for a in INNER_BARS + OUTER_BARS] + [(130, 135)]
BAYS_X = [(-2, 0), (23, 25), (46, 50)]


def belly(v, held):
    """0 where the cloth is held, rising to 1 halfway between two holds."""
    out = np.zeros(np.shape(v))
    for (a0, a1), (b0, b1) in zip(held, held[1:]):
        f = (v - a1) / (b0 - a1)
        m = (f >= 0) & (f <= 1)
        out = np.where(m, np.sin(np.pi * np.clip(f, 0, 1)), out)
    return out


def canvas_tone(x, r, P, seed, end):
    """The sailcloth's tone at (x, r): weathered canvas sewn of six cloths, hemmed, a row of reef points here and there and
    a newer patch on one; grimier toward the whip and the hub."""
    q = np.dstack([x, r, np.zeros_like(x)])
    t = 0.74 + 0.1 * (fbm(q, (9, 16, 1), seed, 3) - 0.5)
    t = t - 0.16 * np.clip(fbm(q, (5, 9, 1), seed + 1, 3) - 0.56, 0, 1) * 2.0
    t = t - 0.07 * np.exp(-x / 6.0) - 0.05 * np.exp(-(r - 24) / 22.0)
    t = t - 0.05 * (fbm(q, (1.2, 22, 1), seed + 2, 2) - 0.5)
    ix = np.floor(x * HD + 1e-6).astype(np.int64)
    ir = np.floor(r * HD + 1e-6).astype(np.int64)
    t = t + 0.03 * (((ix + ir) % 2) - 0.5) + 0.03 * (_hash(ix, ir, 7, seed) - 0.5)
    # each bay of cloth between two bars, and between the whip, the middle lath and the hemlath, bellies out a little
    t = t + 0.07 * (belly(r, BAYS_R) * belly(x, BAYS_X) ** 0.5 - 0.55)
    # the seams between the cloths, a fold and a run of stitches beside each
    d = np.abs(((x + PANEL / 2) % PANEL) - PANEL / 2)
    seam = (d < 0.45) & (x > 1) & (x < CLOTH_W - 1)
    t = np.where(seam, t - 0.14, t)
    stitch = (np.abs(d - 0.9) < 0.3) & ((ir // 2) % 2 == 0) & (x > 1) & (x < CLOTH_W - 1)
    t = np.where(stitch, t - 0.08, t)
    t = np.where((d >= 0.45) & (d < 0.8), t + 0.04, t)
    # the hems, doubled cloth with a stitched line inside
    hem = (x < 1.3) | (x > CLOTH_W - 1.3) | (r < 25.3) | ((r > end - 1.3) if end else False)
    t = np.where(hem, t + 0.03, t)
    inner = ((np.abs(x - 1.5) < 0.3) | (np.abs(x - (CLOTH_W - 1.5)) < 0.3)) & ((ir // 2) % 2 == 0)
    t = np.where(inner, t - 0.08, t)
    # reef points: short ties across the sail
    for rr in REEFS:
        tie = (np.abs(r - rr) < 0.35) & ((x % 5.0) < 0.9) & (x > 2) & (x < CLOTH_W - 2)
        t = np.where(tie, t - 0.22, t)
    # a patch sewn on, newer and paler cloth
    patch = (x > 17) & (x < 27) & (r > 95) & (r < 105)
    t = np.where(patch, t + 0.06, t)
    rim = patch & ((np.abs(x - 17.4) < 0.35) | (np.abs(x - 26.6) < 0.35) | (np.abs(r - 95.4) < 0.35) | (np.abs(r - 104.6) < 0.35))
    t = np.where(rim & ((ir + ix) // 2 % 2 == 0), t - 0.12, t)
    return t


def rip(x, r, path, seed, rough=1.6):
    """Distance outside a ragged rip along [path], points (r, x, half its width there): negative inside it."""
    best = np.full(x.shape, 1e9)
    wid = np.zeros(x.shape)
    for (r0, x0, w0), (r1, x1, w1) in zip(path, path[1:]):
        dr, dx = r1 - r0, x1 - x0
        L2 = dr * dr + dx * dx
        t = np.clip(((r - r0) * dr + (x - x0) * dx) / L2, 0, 1)
        d = np.hypot(r - (r0 + t * dr), x - (x0 + t * dx))
        closer = d < best
        best = np.where(closer, d, best)
        wid = np.where(closer, w0 + (w1 - w0) * t, wid)
    q = np.dstack([x, r, np.zeros_like(x)])
    return best - wid + rough * (fbm(q, (2.2, 2.2, 1), seed, 3) - 0.5) * 2.0


def torn_mask(c, x, r, seed):
    """How much of a broken sail's cloth is left: an inner half ripped from the hemlath in toward the whip, its corner gone;
    an outer half in tatters, a few ragged strips hanging from the bars."""
    q = np.dstack([x, r, np.zeros_like(x)])
    if c.name == 'torn_in':
        d1 = rip(x, r, [(68, 53, 4.0), (58, 40, 3.2), (49, 28, 2.4), (44, 16, 1.4), (41, 8, 0.4)], seed)
        d2 = rip(x, r, [(74, 30, 7.0), (74, 54, 9.0)], seed + 1, 2.5)
        d3 = rip(x, r, [(35, 47, 2.0), (31, 52, 2.6)], seed + 2, 1.0)
        d = np.minimum(np.minimum(d1, d2), d3)
    else:
        # strips: cloth left where a strip runs down from the inner end, each torn off at its own length
        keep = np.full(x.shape, 1e9)
        for xc, w, end in ((5.5, 4.5, 118), (16, 3.0, 96), (25, 3.5, 128), (35.5, 2.6, 84), (45, 4.0, 108)):
            wob = 1.2 * (fbm(q, (3, 7, 1), seed + int(xc), 2) - 0.5) * 2
            inside = np.abs(x - xc + wob) - w
            along = r - end + 5 * (fbm(q, (1.5, 3, 1), seed + int(xc) + 5, 2) - 0.5) * 2
            keep = np.minimum(keep, np.maximum(inside, along))
        # and a band of cloth still sewn to the inner end
        band = r - (77 + 3 * (fbm(q, (2.5, 2, 1), seed + 9, 2) - 0.5) * 2)
        d = -np.minimum(keep, band)
    return d


def cloth_painter(seed):
    def p(P, face, n, edge, c):
        x, r = sail_coords(P, c)
        end = 131 if c.name.endswith('_out') else None
        t = canvas_tone(x, r, P, seed, end)
        a = np.ones(x.shape)
        if c.name.startswith('torn'):
            d = torn_mask(c, x, r, seed + 40)
            a = (d > 0).astype(float)
            # frayed: darker, threads loose at the edge
            fray = (d > 0) & (d < 0.9)
            t = np.where(fray, t - 0.18, t)
            q = np.dstack([x, r, np.zeros_like(x)])
            loose = (d <= 0) & (d > -0.9) & (rand(q, seed + 41) > 0.72)
            a = np.where(loose, 1.0, a)
            t = np.where(loose, t - 0.12, t)
            # scorched and stained where it flogged in the wind
            t = t - 0.1 * np.clip(1.6 - d, 0, 1.6) / 1.6 * (d > 0)
        return bands2(CANVAS, t), a
    return p


def roll_painter(seed):
    """The cloth furled round the whip: a fat roll of canvas, creased where it wraps, tied every so often."""
    def p(P, face, n, edge, c):
        out = c.name.endswith('_out')
        r = (BREAK - P[..., 1]) if out else -P[..., 1]
        a = long_axis(c)
        acr = P[..., 0] + P[..., 2]
        t = 0.66 + 0.08 * (fbm(P, (2, 6, 2), seed, 2) - 0.5) + FACE_T[face]
        f = ((r + 1.3 * acr) / 2.8) % 1.0
        t = t - 0.14 * (f < 0.22) + 0.04 * ((f > 0.4) & (f < 0.6))
        t = t - 0.12 * (edge < 1.0)
        ties = (82, 97, 112, 125) if out else (31, 45, 59, 70)
        for tr in ties:
            m = np.abs(r - tr) < 0.8
            t = np.where(m, 0.36 + 0.1 * (np.abs(r - tr) < 0.35), t)
        if abs(n[a]) > 0.5:
            t = t - 0.12
        return bands2(CANVAS, t), np.ones(P.shape[:2])
    return p


# ---- each model's painters ----

def sails_painters(style):
    s = STYLES.index(style) * 1000
    pl = PLANKS[style]
    whip_nails_in = [(x, -(r + 1.5), FRONT) for r in INNER_BARS for x in (-2.0, 2.0)]
    whip_nails_out = [(x, -(r - BREAK + 1.5), FRONT) for r in OUTER_BARS for x in (-1.5, 1.5)] + [(x, -60.5, FRONT) for x in (-1.5, 1.5)]
    hem_nails_in = [(52, -(r + 1.5), FRONT) for r in INNER_BARS]
    hem_nails_out = [(52, -(r - BREAK + 1.5), FRONT) for r in OUTER_BARS]

    shaft = wood_painter(style, TIMBER[style], s + 1, ring=3.0, figure=0.8)
    hoops = iron_painter(s + 2, base=0.36)
    return {
        'boss': iron_painter(s + 3, rivets=[(x, y, 0) for x in (1.5, 8.5) for y in (1.5, 8.5)], base=0.4),
        'boss45': iron_painter(s + 4, rivets=[(5, 1.3, 0), (5, 8.7, 0), (1.3, 5, 0), (8.7, 5, 0)], base=0.34),
        'shaft': banded(shaft, hoops, lambda P: (P[..., 2] < 1.6) | (P[..., 2] > 14.4)),
        'whip_in': wood_painter(style, pl, s + 5, shifted(whip_nails_in, (-4, -BREAK, FRONT)), figure=1.1),
        'whip_out': wood_painter(style, pl, s + 6, shifted(whip_nails_out, (-3, -64, FRONT)), figure=1.1),
        'bar0': wood_painter(style, pl, s + 7),
        'bar1': wood_painter(style, pl, s + 8),
        'bar2': wood_painter(style, pl, s + 9),
        'tip_bar': wood_painter(style, pl, s + 10),
        'hem_in': wood_painter(style, pl, s + 11, shifted(hem_nails_in, (50, -BREAK, FRONT))),
        'hem_out': wood_painter(style, pl, s + 12, shifted(hem_nails_out, (50, -58, FRONT))),
        'mid_in': wood_painter(style, pl, s + 13),
        'mid_out': wood_painter(style, pl, s + 14),
    }


def shifted(points, origin):
    """Points given where a piece is first put, moved into the frame its template is painted in (its corner at the origin)."""
    return [(x - origin[0], y - origin[1], z - origin[2]) for x, y, z in points]


def cloth_painters():
    return {
        'sail_in': cloth_painter(501), 'sail_out': cloth_painter(501),
        'torn_in': cloth_painter(501), 'torn_out': cloth_painter(501),
        'roll_in': roll_painter(511), 'roll_out': roll_painter(512),
    }


def cloth_frame(pieces):
    """The cloth cards are painted in the place they're put (so that their weathering runs on from one half of a sail to
    the other): the templates take the inner and outer halves' origins."""
    for name, origin in (('sail_in', (0, -BREAK, CLOTH_Z)), ('torn_in', (0, -BREAK, CLOTH_Z)), ('sail_out', (0, -59, CLOTH_Z)),
                         ('torn_out', (0, -59, CLOTH_Z)), ('roll_in', (0, -71, -10)), ('roll_out', (0, -57, -10))):
        pieces[name].origin = origin


def wheel_painters(style):
    s = STYLES.index(style) * 1000 + 300
    tb, bo = TIMBER[style], BOARDS[style]
    hoop = iron_painter(s + 1, base=0.34)
    nave = wood_painter(style, tb, s + 2, ring=2.0)

    def paddle_wet(P):  # the paddles' outer ends are always wet (the template's y runs 0 at the tip to 19 at the root)
        return sstep(13, 2, P[..., 1])

    def rim_wet(P):
        return np.full(P.shape[:2], 0.45)
    return {
        'nave': banded(nave, hoop, lambda P: ((P[..., 2] > 1.0) & (P[..., 2] < 3.0)) | ((P[..., 2] > 29.5) & (P[..., 2] < 31.5))),
        'nave45': banded(wood_painter(style, tb, s + 3, ring=2.0), hoop,
                         lambda P: ((P[..., 2] > 0.5) & (P[..., 2] < 2.5)) | ((P[..., 2] > 29.0) & (P[..., 2] < 31.0))),
        'axle': banded(wood_painter(style, tb, s + 4, ring=1.8), iron_painter(s + 5, rivets=[(4, 4, 0)], base=0.38),
                       lambda P: (P[..., 2] < 2.0)),
        'spoke': wood_painter(style, tb, s + 6, ring=2.2, wet=lambda P: np.full(P.shape[:2], 0.25)),
        'rim0': wood_painter(style, bo, s + 7, [(2.0, 3.0, 0), (40.0, 3.0, 0)], wet=rim_wet),
        'rim1': wood_painter(style, bo, s + 8, [(2.0, 3.0, 0), (40.0, 3.0, 0)], wet=rim_wet),
        'paddle0': wood_painter(style, bo, s + 9, wet=paddle_wet),
        'paddle1': wood_painter(style, bo, s + 10, wet=paddle_wet),
    }


# ================================================================ Kotlin

def kotlin_file(sails, cloth, wheel):
    def body(m):
        return m.kotlin()
    return f'''// generated by art/scripts/mill_art.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.mill

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.core.Direction
import java.util.EnumSet

/**
 * The meshes of the mills' turning parts, box UV, each texture painted and shipped at twice the size declared here: a
 * windmill's sails, as their timber frame and their cloth (two models that turn as one, [SailsModel] and [ClothModel]),
 * and a watermill's wheel ([WheelModel]). Model space is the hub block's own: its middle is the origin, y is down and -z is
 * out of the mill.
 */
object MillMesh {{
    /**
     * The sails' timber frame on a {sails.tex_w} x {sails.tex_h} texture: the windshaft and the poll end on the `rotor`, and
     * four sails (`arm0` up, the others a quarter turn on each, against the way they turn) of whip, sail bars, hemlath and
     * middle lath, each one's outer half on a joint of its own (`tip0` to `tip3`, {BREAK} px out) so that a broken one can
     * hang from where it broke.
     */
    fun sails(): LayerDefinition {{
{body(sails)}
    }}

    /**
     * The sails' cloth on a {cloth.tex_w} x {cloth.tex_h} texture, on the same rotor, arms and joints as [sails]: each sail's
     * canvas in two halves (`sail0`, `sail_tip0` ...), the torn canvas shown instead on a broken one (`torn0`, `torn_tip0`
     * ...) and the roll the canvas makes furled along the whip (`roll0`, `roll_tip0` ...). The canvas and the rolls hang
     * on joints at the whip's edge, so that drawing them narrower furls them toward it.
     */
    fun cloth(): LayerDefinition {{
{body(cloth)}
    }}

    /**
     * The watermill's wheel on a {wheel.tex_w} x {wheel.tex_h} texture, all of it on the one joint `wheel` that turns on the
     * axle: the nave and the stub of the axle out in front, two octagonal shrouds of boards, eight spokes a side and
     * sixteen paddles.
     */
    fun wheel(): LayerDefinition {{
{body(wheel)}
    }}
}}
'''


# ================================================================ previews

def rz(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])


def rotm(rot):
    rx, ry, rz_ = [math.radians(a) for a in rot]
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz_), -math.sin(rz_), 0], [math.sin(rz_), math.cos(rz_), 0], [0, 0, 1]])
    return Z @ Y @ X


def posed_faces(model, pose=None, hidden=(), du=0, dv=0):
    """Every drawn face of [model], posed as the game would: [pose] gives a part its own matrix (its turn, and a scale as
    the game's xScale gives one) in place of its rest turn; a hidden part, with all it holds, is left out. The faces' uv
    rects are scaled to the HD texture and moved by (du, dv) for a preview atlas."""
    pose = pose or {}
    faces = []

    def go(part, M, T):
        if part.name in hidden:
            return
        local = pose.get(part.name)
        R = M @ (rotm(part.rot) if local is None else local)
        P = T + M @ np.array(part.pivot, float)
        for c in part.cubes:
            x0, y0, z0 = c.origin
            x1, y1, z1 = x0 + c.size[0], y0 + c.size[1], z0 + c.size[2]

            def W(x, y, z):
                return P + R @ np.array([x, y, z], float)
            quads = {
                'front': (W(x0, y0, z0), W(x1, y0, z0), W(x0, y1, z0)),
                'back': (W(x1, y0, z1), W(x0, y0, z1), W(x1, y1, z1)),
                'right': (W(x0, y0, z1), W(x0, y0, z0), W(x0, y1, z1)),
                'left': (W(x1, y0, z0), W(x1, y0, z1), W(x1, y1, z0)),
                'top': (W(x0, y0, z1), W(x1, y0, z1), W(x0, y0, z0)),
                'bottom': (W(x0, y1, z1), W(x1, y1, z1), W(x0, y1, z0)),
            }
            for face, (A, B, D) in quads.items():
                if not c.shows(face):
                    continue
                u, v, w, h = c.rects()[face]
                if w == 0 or h == 0:
                    continue
                # (the bottom's corners go round the other way, as its texture runs like the top's)
                n = np.cross(D - A, B - A) * (-1 if face == 'bottom' else 1)
                nl = np.linalg.norm(n)
                faces.append((A, B, D, (u * HD + du, v * HD + dv, w * HD, h * HD), face, n / nl if nl > 1e-9 else n))
        for ch in part.children:
            go(ch, R, P)
    for p in model.parts:
        go(p, np.eye(3), np.zeros(3))
    return faces


SUN = np.array([-0.35, -0.8, -0.5])
SUN = SUN / np.linalg.norm(SUN)


def raster(faces, tex, size, scale, yaw, pitch, centre, sky=True):
    """Draws faces (model space, y down, seen from -z) with a z-buffer, lit by a sun from the upper front left."""
    Wd, Ht = size
    img = np.zeros((Ht, Wd, 3))
    if sky:
        g = np.linspace(0, 1, Ht)[:, None]
        img[:] = hd.mix((96, 126, 160), (178, 194, 204), g)
    else:
        img[:] = (58, 64, 80)
    zbuf = np.full((Ht, Wd), 1e9)
    T = np.asarray(tex.convert('RGBA'), float)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        x, y, z = p
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        return np.array([centre[0] + x * scale, centre[1] + y * scale, z])
    ys, xs = np.mgrid[0:Ht, 0:Wd]
    for A, B, D, (u, v, w, h), face, n in faces:
        a, b, d = view(A), view(B), view(D)
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        corners = [a, b, d, b + e2]
        x0 = int(max(0, min(c[0] for c in corners)))
        x1 = int(min(Wd - 1, max(c[0] for c in corners) + 1))
        y0 = int(max(0, min(c[1] for c in corners)))
        y1 = int(min(Ht - 1, max(c[1] for c in corners) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]
        py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        tu = np.clip((u + s * w).astype(int), int(u), int(u + w - 1))
        tv = np.clip((v + t * h).astype(int), int(v), int(v + h - 1))
        texel = T[np.clip(tv, 0, T.shape[0] - 1), np.clip(tu, 0, T.shape[1] - 1)]
        ok = inside & (texel[..., 3] > 25) & (depth < zbuf[y0:y1, x0:x1])
        lit = 0.52 + 0.48 * max(0.0, float(n @ SUN))
        col = texel[..., :3] * lit
        alpha = texel[..., 3:4] / 255.0
        region = img[y0:y1, x0:x1]
        blended = col * alpha + region * (1 - alpha)
        region[...] = np.where(ok[..., None], blended, region)
        zb = zbuf[y0:y1, x0:x1]
        solid = ok & (texel[..., 3] > 150)
        zb[solid] = depth[solid]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


def tinted(img, rgb):
    """The cloth texture as the game tints it with a dye's colour."""
    a = np.asarray(img, float)
    a[..., :3] *= np.array(rgb, float) / 255.0
    return Image.fromarray(a.round().astype(np.uint8), 'RGBA')


def atlas(*texes):
    """Textures side by side, and where each starts."""
    w = sum(t.width for t in texes)
    h = max(t.height for t in texes)
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    x, at = 0, []
    for t in texes:
        out.paste(t, (x, 0))
        at.append(x)
        x += t.width
    return out, at


QUARTER = 90.0


def sails_pose(turn_deg, furl=0.0, broken=-1, swing_deg=0.0):
    """The pose SailsModel and ClothModel give the parts (the same sums as client/mill/MillModels.kt)."""
    rotor = -turn_deg  # the sails turn against the clock, seen from in front: the rotor's turn is the opposite of theirs
    pose = {'rotor': rz(rotor)}
    hidden = set()
    spread = 1.0 - 0.94 * furl
    for k in range(4):
        pose[f'arm{k}'] = rz(QUARTER * k)
        if k == broken:
            pose[f'tip{k}'] = rz(180.0 + swing_deg - rotor - QUARTER * k)
            hidden |= {f'sail{k}', f'sail_tip{k}', f'roll{k}', f'roll_tip{k}'}
        else:
            pose[f'tip{k}'] = rz(0.0)
            hidden |= {f'torn{k}', f'torn_tip{k}'}
            for name in (f'sail{k}', f'sail_tip{k}'):
                pose[name] = np.diag([spread, 1.0, 1.0])
            if furl > 0.02:
                for name in (f'roll{k}', f'roll_tip{k}'):
                    pose[name] = np.diag([furl, 1.0, 1.0])
            else:
                hidden |= {f'roll{k}', f'roll_tip{k}'}
    return pose, hidden


def sails_shot(wood_m, cloth_m, wood_tex, cloth_tex, dye, pose, size, yaw=24, pitch=-8, scale=None):
    tex, at = atlas(wood_tex, tinted(cloth_tex, dye))
    p, hidden = pose
    faces = posed_faces(wood_m, p, hidden) + posed_faces(cloth_m, p, hidden, du=at[1])
    scale = scale or size[0] / 300.0
    return raster(faces, tex, size, scale, yaw, pitch, (size[0] / 2, size[1] / 2))


def water_card(y, tex_x):
    """A sheet of water at depth y for the wheel's previews (the preview atlas's last column holds its colour)."""
    A = np.array([-80.0, y, -48.0])
    B = np.array([80.0, y, -48.0])
    D = np.array([-80.0, y, 10.0])
    return (A, B, D, (tex_x, 0, 2, 2), 'top', np.array([0.0, -1.0, 0.0]))


def wheel_shot(wheel_m, wheel_tex, turn_deg, water_y, size, yaw=34, pitch=-12):
    water = Image.new('RGBA', (2, 2), (54, 92, 108, 168))
    tex, at = atlas(wheel_tex, water)
    faces = posed_faces(wheel_m, {'wheel': rz(turn_deg)}) + [water_card(water_y, at[1])]
    return raster(faces, tex, size, size[0] / 150.0, yaw, pitch, (size[0] / 2, size[1] * 0.42))


def label(img, text):
    d = ImageDraw.Draw(img)
    d.rectangle((0, img.height - 16, img.width, img.height), fill=(30, 30, 38))
    d.text((5, img.height - 14), text, fill=(232, 226, 210))
    return img


def sheet(images, cols, pad=6, bg=(30, 30, 38)):
    w, h = images[0].size
    rows = (len(images) + cols - 1) // cols
    out = Image.new('RGB', (cols * (w + pad) + pad, rows * (h + pad) + pad), bg)
    for i, im in enumerate(images):
        out.paste(im, (pad + (i % cols) * (w + pad), pad + (i // cols) * (h + pad)))
    return out


DYES = {'white': (0xF0, 0xF0, 0xF0), 'light_gray': (0x9A, 0x9A, 0x96), 'gray': (0x50, 0x50, 0x58), 'red': (0xA0, 0x28, 0x28)}
STYLE_DYE = {'temperate': 'white', 'desert': 'light_gray', 'northern': 'gray', 'savanna': 'red'}


def previews(out_dir, sails_m, cloth_m, wheel_m, sails_tex, cloth_tex, wheel_tex):
    os.makedirs(out_dir, exist_ok=True)
    big = (720, 720)
    shots = []
    for style in STYLES:
        dye = DYES[STYLE_DYE[style]]
        for title, pose in ((f'{style}: turning ({STYLE_DYE[style]} cloth)', sails_pose(20.0)),
                            (f'{style}: braked, a sail upright, furled', sails_pose(0.0, furl=1.0)),
                            (f'{style}: broken, a sail hanging', sails_pose(0.0, furl=1.0, broken=3, swing_deg=8.0))):
            im = sails_shot(sails_m, cloth_m, sails_tex[style], cloth_tex, dye, pose, big).resize((360, 360), Image.LANCZOS)
            shots.append(label(im, title))
    p1 = os.path.join(out_dir, 'mill_sails.png')
    sheet(shots, 3).save(p1)
    wheels = []
    for i, style in enumerate(STYLES):
        y = WATER_HUB[1] if i < 2 else WATER_HUB[0]
        im = wheel_shot(wheel_m, wheel_tex[style], 10.0, y, big).resize((360, 360), Image.LANCZOS)
        wheels.append(label(im, f'{style}: wheel, hub {"high" if i < 2 else "low"} over the stream'))
    p2 = os.path.join(out_dir, 'mill_wheel.png')
    sheet(wheels, 2).save(p2)
    frames = []
    n = 16
    for f in range(n):
        a = sails_shot(sails_m, cloth_m, sails_tex['temperate'], cloth_tex, DYES['white'], sails_pose(90.0 * f / n), (480, 480)).resize((240, 240), Image.LANCZOS)
        b = wheel_shot(wheel_m, wheel_tex['temperate'], 90.0 * f / n, WATER_HUB[1], (480, 480)).resize((240, 240), Image.LANCZOS)
        fr = Image.new('RGB', (480, 240))
        fr.paste(a, (0, 0))
        fr.paste(b, (240, 0))
        frames.append(fr)
    p3 = os.path.join(out_dir, 'mill_turning.gif')
    frames[0].save(p3, save_all=True, append_images=frames[1:], duration=120, loop=0)
    return [p1, p2, p3]


# ================================================================ checking the Kotlin

# What the check compiles beside MillMesh.kt and MillModels.kt: the little of the mod they lean on
KT_STUBS = {
    'Guildmark.kt': 'package io.github.vaspyyy.guildmark\n\nobject Guildmark {\n    const val MOD_ID = "guildmark"\n}\n',
    'mill/MillBlockEntity.kt': ('package io.github.vaspyyy.guildmark.mill\n\nclass MillBlockEntity {\n    companion object {\n'
                                '        const val QUARTER = (kotlin.math.PI / 2.0).toFloat()\n    }\n}\n'),
    'client/mill/MillRenderState.kt': ('package io.github.vaspyyy.guildmark.client.mill\n\nclass MillRenderState {\n    var turn = 0f\n'
                                       '    var furl = 0f\n    var broken = -1\n    var swing = 0f\n}\n'),
}

KT_HARNESS = """import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import io.github.vaspyyy.guildmark.client.mill.ClothModel
import io.github.vaspyyy.guildmark.client.mill.MillMesh
import io.github.vaspyyy.guildmark.client.mill.MillRenderState
import io.github.vaspyyy.guildmark.client.mill.SailsModel
import io.github.vaspyyy.guildmark.client.mill.WheelModel
import net.minecraft.client.model.Model

/** Writes down every corner Minecraft would draw: where it is (px) and where in the texture (texels). */
class Recorder(private val out: StringBuilder, private val w: Float, private val h: Float) : VertexConsumer {
    override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer = this
    override fun setColor(r: Int, g: Int, b: Int, a: Int): VertexConsumer = this
    override fun setColor(color: Int): VertexConsumer = this
    override fun setUv(u: Float, v: Float): VertexConsumer = this
    override fun setUv1(u: Int, v: Int): VertexConsumer = this
    override fun setUv2(u: Int, v: Int): VertexConsumer = this
    override fun setUv3(u: Float, v: Float): VertexConsumer = this
    override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer = this
    override fun setLineWidth(width: Float): VertexConsumer = this
    override fun addVertex(x: Float, y: Float, z: Float, color: Int, u: Float, v: Float, overlay: Int, light: Int, nx: Float, ny: Float, nz: Float) {
        out.append("V ").append(x * 16f).append(' ').append(y * 16f).append(' ').append(z * 16f).append(' ')
            .append(u * w).append(' ').append(v * h).append('\\n')
    }
}

fun main() {
    val models = listOf<Triple<String, Model<MillRenderState>, FloatArray>>(
        Triple("sails", SailsModel(MillMesh.sails().bakeRoot()), floatArrayOf(@@SAILS@@)),
        Triple("cloth", ClothModel(MillMesh.cloth().bakeRoot()), floatArrayOf(@@CLOTH@@)),
        Triple("wheel", WheelModel(MillMesh.wheel().bakeRoot()), floatArrayOf(@@WHEEL@@)),
    )
    val out = StringBuilder()
    while (true) {
        val line = readlnOrNull() ?: break
        val p = line.trim().split(" ")
        if (p[0] != "CASE") continue
        val state = MillRenderState()
        state.turn = p[1].toFloat()
        state.furl = p[2].toFloat()
        state.broken = p[3].toInt()
        state.swing = p[4].toFloat()
        out.append("CASE\\n")
        for ((name, model, size) in models) {
            model.setupAnim(state)
            out.append("MODEL ").append(name).append('\\n')
            model.root().render(PoseStack(), Recorder(out, size[0], size[1]), 0xF000F0, 0, -1)
        }
    }
    print(out)
}
"""

EXTRA_LIBS = ['/tmp/claude-0/mc_offline_libs', '/opt/gradle-8.14.3/lib/commons-lang3-3.14.0.jar']

CHECK_CASES = [(0.0, 0.0, -1, 0.0), (0.7, 0.0, -1, 0.0), (2.1, 0.5, -1, 0.0), (-4.0, 1.0, -1, 0.0), (0.0, 1.0, 3, 0.12),
               (1.5708, 1.0, 0, -0.2), (3.14159, 0.3, 2, 0.05), (5.0, 1.0, 1, 0.3), (11.0, 0.01, -1, 0.0)]


def python_quads(model, pose, hidden):
    """Each face [posed_faces] gives, as its four corners (px) and their places in the texture (texels, 1x)."""
    out = []
    for A, B, D, (u, v, w, h), face, n in posed_faces(model, pose, hidden):
        u, v, w, h = u / HD, v / HD, w / HD, h / HD
        out.append((np.array([A, B, D, B + D - A]), np.array([(u, v), (u + w, v), (u, v + h), (u + w, v + h)])))
    return out


def compare(mc, py):
    """(worst corner error px, worst texture error texels, problems) between Minecraft's quads and the Python faces."""
    problems = []
    if len(mc) != len(py):
        problems.append(f'{len(mc)} quads drawn, {len(py)} faces in Python')
    if not mc or not py:
        return 0.0, 0.0, problems
    pc = np.array([q[0].mean(0) for q in py])
    pt = np.array([q[1].mean(0) for q in py])
    used = set()
    worst_p = worst_t = 0.0
    for pos, uv in mc:
        cost = np.abs(pc - pos.mean(0)).sum(1) + np.abs(pt - uv.mean(0)).sum(1)
        j = int(np.argmin(cost))
        if j in used:
            problems.append(f'two quads drawn on face {j}')
        used.add(j)
        corners, uvs = py[j]
        for x, t in zip(pos, uv):
            k = int(np.argmin(np.abs(corners - x).sum(1)))
            worst_p = max(worst_p, float(np.abs(corners[k] - x).max()))
            worst_t = max(worst_t, float(np.abs(uvs[k] - t).max()))
    return worst_p, worst_t, problems


def check(work, sails_m, cloth_m, wheel_m):
    """Compiles MillMesh.kt and MillModels.kt (as they are in the source tree) against Minecraft's own classes, draws each model
    through Minecraft's ModelPart in a spread of poses into a recorder, and compares every corner of every quad, and where it
    samples the texture, with the faces the previews draw (and the textures are painted on). Returns the problems found."""
    import shutil
    import subprocess
    kit = os.environ.get('KCHECK_WORK', '/tmp/kcheck')
    libs = os.path.join(kit, 'libs')
    kotlinc = os.path.join(kit, 'kotlinc', 'bin', 'kotlinc')
    if not os.path.isdir(libs) or not os.path.exists(kotlinc):
        raise SystemExit(f'--check needs the compile kit (KCHECK_WORK={kit}: its libs and kotlinc)')
    src = os.path.join(work, 'src')
    shutil.rmtree(src, ignore_errors=True)
    files = []

    def put(rel, text):
        path = os.path.join(src, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            f.write(text)
        files.append(path)
    for rel, text in KT_STUBS.items():
        put(rel, text)
    mill_src = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/mill')
    for name in ('MillMesh.kt', 'MillModels.kt'):
        with open(os.path.join(mill_src, name)) as f:
            put(os.path.join('client/mill', name), f.read())
    size = {m.name: f'{m.tex_w}f, {m.tex_h}f' for m in (sails_m, cloth_m, wheel_m)}
    put('Harness.kt', KT_HARNESS.replace('@@SAILS@@', size['windmill_sails']).replace('@@CLOTH@@', size['windmill_cloth'])
        .replace('@@WHEEL@@', size['water_wheel']))
    jars = sorted(os.path.join(libs, f) for f in os.listdir(libs) if f.endswith('.jar'))
    # (the kit's libraries are enough to compile; running needs a few more of Minecraft's, where sea_kit.py finds them too)
    for e in os.environ.get('KCHECK_EXTRA_LIBS', ':'.join(EXTRA_LIBS)).split(':'):
        if os.path.isdir(e):
            jars += sorted(os.path.join(e, f) for f in os.listdir(e) if f.endswith('.jar'))
        elif os.path.exists(e):
            jars.append(e)
    cp = ':'.join(jars)
    out = os.path.join(work, 'out')
    shutil.rmtree(out, ignore_errors=True)
    r = subprocess.run([kotlinc, '-jvm-target', '21', '-nowarn', '-cp', cp, '-d', out] + files, capture_output=True, text=True,
                       env=dict(os.environ, JAVA_OPTS='-Xmx3g'), timeout=900)
    errs = [ln for ln in (r.stdout + r.stderr).splitlines() if 'error:' in ln]
    if errs:
        raise SystemExit('the Kotlin did not compile:\n' + '\n'.join(errs[:20]))
    stdlib = os.path.join(kit, 'kotlinc', 'lib', 'kotlin-stdlib.jar')
    text = ''.join(f'CASE {t} {f} {b} {s}\n' for t, f, b, s in CHECK_CASES)
    r = subprocess.run(['java', '-cp', ':'.join([out, cp, stdlib]), 'HarnessKt'], input=text, capture_output=True, text=True, timeout=900)
    if r.returncode:
        raise SystemExit('the harness failed:\n' + r.stderr[-3000:])
    problems = []
    worst_p = worst_t = 0.0
    blocks = r.stdout.split('CASE\n')[1:]
    if len(blocks) != len(CHECK_CASES):
        raise SystemExit(f'{len(blocks)} answers for {len(CHECK_CASES)} cases')
    quads = 0
    for (turn, furl, broken, swing), blk in zip(CHECK_CASES, blocks):
        drawn = {}
        name = None
        for line in blk.strip().split('\n'):
            if line.startswith('MODEL '):
                name = line[6:]
                drawn[name] = []
            elif line.startswith('V '):
                drawn[name].append([float(v) for v in line.split()[1:]])
        pose, hidden = sails_pose(math.degrees(turn), furl, broken, math.degrees(swing))
        for name, model, pz, hz in (('sails', sails_m, pose, hidden), ('cloth', cloth_m, pose, hidden),
                                    ('wheel', wheel_m, {'wheel': rz(math.degrees(turn))}, ())):
            v = np.array(drawn[name]).reshape(-1, 4, 5)
            mc = [(q[:, :3], q[:, 3:]) for q in v]
            quads += len(mc)
            p, t, probs = compare(mc, python_quads(model, pz, hz))
            worst_p, worst_t = max(worst_p, p), max(worst_t, t)
            problems += [f'turn {turn} furl {furl} broken {broken} swing {swing}, {name}: {x}' for x in probs]
    # (the generated Kotlin gives each turn to three places, so a corner far from its pivot can be a few hundredths out)
    if worst_p > 0.05 or worst_t > 0.01:
        problems.append(f'corners off by up to {worst_p:.4f} px, texture by up to {worst_t:.4f} texels')
    print(f'check: {quads} quads over {len(CHECK_CASES)} poses; corners within {worst_p:.5f} px of the Python faces, the texture within '
          f'{worst_t:.5f} texels; {len(problems)} problems')
    for x in problems[:30]:
        print('  ' + x)
    return problems


def steady_uuids(text, key):
    """The .bbmodel with its uuids made from [key] and their order, so that a rerun writes the same file."""
    import re
    import uuid
    count = [0]

    def sub(_m):
        count[0] += 1
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/{key}/{count[0]}'))
    return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', sub, text)


# ================================================================ main

def main():
    flags = [a for a in sys.argv[1:] if a.startswith('--')]
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    assets = args[0] if len(args) > 0 else os.path.join(ROOT, 'src/main/resources/assets/guildmark')
    preview_dir = args[1] if len(args) > 1 else None
    models_dir = args[2] if len(args) > 2 else os.path.join(ROOT, 'art/models')

    sp, cp, wp = sails_pieces(), cloth_pieces(), wheel_pieces()
    rows = (pack(sp.values(), 128, 128, 'sails'), pack(cp.values(), 128, 128, 'cloth'), pack(wp.values(), 128, 192, 'wheel'))
    sails_m, cloth_m, wheel_m = sails_model(sp), cloth_model(cp), wheel_model(wp)
    cloth_frame(cp)

    tex_dir = os.path.join(assets, 'textures/entity/mill')
    os.makedirs(tex_dir, exist_ok=True)
    sails_tex, wheel_tex = {}, {}
    for style in STYLES:
        sails_tex[style] = paint(sp.values(), 128, 128, sails_painters(style))
        sails_tex[style].save(os.path.join(tex_dir, f'sails_{style}.png'))
        wheel_tex[style] = paint(wp.values(), 128, 192, wheel_painters(style))
        wheel_tex[style].save(os.path.join(tex_dir, f'wheel_{style}.png'))
    cloth_tex = paint(cp.values(), 128, 128, cloth_painters())
    cloth_tex.save(os.path.join(tex_dir, 'cloth.png'))

    os.makedirs(os.path.dirname(KOTLIN), exist_ok=True)
    with open(KOTLIN, 'w') as f:
        f.write(kotlin_file(sails_m, cloth_m, wheel_m))

    os.makedirs(models_dir, exist_ok=True)
    for m, tex in ((sails_m, sails_tex['temperate']), (cloth_m, cloth_tex), (wheel_m, wheel_tex['temperate'])):
        with open(os.path.join(models_dir, f'{m.name}.bbmodel'), 'w') as f:
            f.write(steady_uuids(json.dumps(m.bbmodel(tex, m.name)), m.name))

    print(f'textures: {tex_dir} (sails, cloth and wheel use {rows[0]}, {rows[1]} and {rows[2]} rows)')
    print(f'kotlin: {KOTLIN}')
    if preview_dir:
        for p in previews(preview_dir, sails_m, cloth_m, wheel_m, sails_tex, cloth_tex, wheel_tex):
            print('preview:', p)
    if '--check' in flags:
        import tempfile
        with tempfile.TemporaryDirectory(prefix='mill_check_') as work:
            if check(work, sails_m, cloth_m, wheel_m):
                raise SystemExit(1)


if __name__ == '__main__':
    main()
