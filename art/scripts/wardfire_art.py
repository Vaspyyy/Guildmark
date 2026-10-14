"""The Wardfire: a ring of old carved stones round a bed of ash, and a Warden's sword planted in it.
Lit, a golden fire burns round the blade, the runes on three stones glow, and embers drift up.

    python3 art/scripts/wardfire_art.py src/main/resources/assets/guildmark [preview.png]

Writes the block and particle textures, the two block models (cold and lit), the blockstate, the item
model and an optional preview of both models from two sides and the flame frames. All randomness is
seeded, so a rerun gives the same files.
"""
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hdkit import PAL, GEMS, Canvas, grid, vnoise, fbm, rand, tone, ramp, bands, rect, path_dist, light

CL = (0, 0, 0, 0)


# ---- Painting (32x, with the HD art kit; the models' UVs stay in 16ths, so every region is simply twice as fine) ----

T = 32


def stone(seed, mossy):
    """Weathered standing stone: chipped faces lit from the upper left, a crack or two, lichen, and moss
    gathering along the top (more on the mossy stones)."""
    xs, ys = grid(T)
    h = fbm(T, T, 10, seed) * 1.6 + fbm(T, T, 4, seed + 1) * 0.5
    # Flat chisel facets: the height snapped to steps, so the light breaks into planes
    facets = np.round(h * 5) / 5
    t = 0.48 + 0.6 * light(facets * 3.0, 1.0) + 0.16 * (fbm(T, T, 3, seed + 2) - 0.5)
    t = t - 0.25 * (ys > T - 2) + 0.12 * (ys < 2)
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(PAL['stone'], t, 0.3))
    rng = np.random.default_rng(seed)
    for _ in range(3):
        x, y = int(rng.integers(4, T - 4)), int(rng.integers(6, T - 8))
        for _ in range(int(rng.integers(6, 13))):
            cv.px(x, y, PAL['stone'][0])
            cv.px(min(T - 1, x + 1), y, tone(PAL['stone'][1], 0.1))
            if 0 <= x - 1 and rng.random() < 0.5:
                cv.px(x - 1, y, PAL['stone'][4])
            x = int(np.clip(x + rng.choice((-1, 0, 0, 1)), 1, T - 2))
            y = min(T - 1, y + 1)
    lichen = fbm(T, T, 3, seed + 5)
    cv.over((lichen > 0.7) & (ys > 8), (120, 118, 84), 0.45)
    moss = fbm(T, T, 4, seed + 7) + vnoise(T, T, 2, seed + 8) * 0.25
    reach = 13 if mossy else 7
    grow_m = moss > 0.48 + ys / reach * 0.42
    cv.put(grow_m, bands(PAL['moss'], 0.35 + 0.8 * (moss - 0.5) + 0.25 * (ys < 3), 0.4))
    cv.put(grow_m & ~np.roll(grow_m, -1, axis=0) & (ys > 1), PAL['moss'][0])
    return cv.image()


# Each rune as strokes in the 32x texture's middle (it's laid over a standing stone's face at twice the density)
def rune_glyph(seed):
    rng = random.Random(seed)
    cx, top, bottom = 16, 6, 26
    strokes = [[(cx, top), (cx, bottom)]]
    arm = rng.randrange(12, 18)
    strokes.append([(cx - 6, arm - 6), (cx, arm)])
    strokes.append([(cx + 6, arm - 6), (cx, arm)])
    foot = rng.randrange(3)
    if foot == 0:
        strokes.append([(cx - 5, bottom - 1), (cx + 5, bottom - 1)])
    elif foot == 1:
        strokes.append([(cx - 5, bottom - 5), (cx, bottom - 1), (cx + 5, bottom - 5)])
    else:
        strokes.append([(cx - 6, bottom - 9), (cx - 6, bottom - 3)])
        strokes.append([(cx + 6, bottom - 9), (cx + 6, bottom - 3)])
    strokes.append([(cx - 2.5, top - 2), (cx - 1, top)])
    strokes.append([(cx + 2.5, top - 2), (cx + 1, top)])
    d = np.full((T, T), 9.0)
    for st in strokes:
        dd, _ = path_dist(st, T)
        d = np.minimum(d, dd)
    return d


def rune_decal(d, lit):
    """The groove alone, for a plane laid over the stone: cut dark when cold, with a lit lower lip; filled
    with gold light when the fire burns."""
    cv = Canvas(T)
    groove = d < 1.15
    if lit:
        cv.put(groove, bands(PAL['ember'], 0.62 + 0.45 * (d < 0.5) - 0.1 * (d > 0.9), 0))
    else:
        cv.put(groove, PAL['stone'][0])
        lip = groove & ~np.roll(groove, -1, axis=0)
        cv.put(lip, PAL['stone'][1])
        cv.put(groove & ~np.roll(groove, 1, axis=0), (14, 13, 18))
    return cv.image()


def ash(seed, lit):
    """The bed of ash: soft grey drifts, lumps of charcoal, and live embers glowing through when lit."""
    xs, ys = grid(T)
    n = fbm(T, T, 7, seed) * 0.8 + vnoise(T, T, 2, seed + 3) * 0.2
    t = 0.32 + 0.55 * (n - 0.3) + 0.3 * light(n * 4, 1.0)
    if lit:
        t = t - 0.15
    cv = Canvas(T)
    cv.put(np.ones((T, T), bool), bands(PAL['ash'], t, 0.4))
    rng = np.random.default_rng(seed)
    for _ in range(16):
        x, y = int(rng.integers(0, T - 2)), int(rng.integers(0, T - 2))
        w, h = int(rng.integers(2, 4)), int(rng.integers(1, 3))
        m = rect(x, y, x + w, y + h, T)
        cv.put(m, (16, 14, 16))
        cv.px(x, y, (52, 46, 46))
        if lit and rng.random() < 0.5:
            cv.px(x + w - 1, y + h - 1, PAL['ember'][2])
    if lit:
        heat = fbm(T, T, 5, seed + 11)
        d = np.hypot(xs - 16, ys - 16) / 16
        h2 = heat * (1.3 - d * 0.9)
        glow = h2 > 0.62
        cv.put(glow, bands(PAL['ember'], (h2 - 0.62) / 0.42, 0.5))
        # Glowing seams between the lumps
        seam = (np.abs(vnoise(T, T, 4, seed + 13) - 0.5) < 0.04) & (d < 0.8)
        cv.put(seam & ~glow, PAL['ember'][2])
    return cv.image()


def sword_sheet(seed):
    """Every part of the planted sword on one 32x32 texture (the regions are the 16x layout doubled)."""
    xs, ys = grid(T)
    rng = random.Random(seed)
    cv = Canvas(T)
    steel = PAL['steel']
    # Blade flat [0,0,4,24]: a fuller down the middle, darker toward the tip, rust and pitting
    flat = rect(0, 0, 4, 24, T)
    t = 0.7 - ys * 0.012 + 0.12 * (xs < 1) - 0.08 * (xs > 3)
    t = np.where((xs > 1) & (xs < 3) & (ys > 1) & (ys < 20), t - 0.3, t)
    cv.put(flat, bands(steel, t, 0.3))
    for _ in range(5):
        x, y = rng.randrange(4), rng.randrange(3, 23)
        cv.px(x, y, (98, 62, 40))
        cv.px(x, y + 1, (70, 44, 32))
    # Blade edge [4,0,6,24]: bright, with nicks
    for y in range(24):
        nick = rng.random() < 0.18
        cv.px(4, y, steel[3] if nick else steel[5])
        cv.px(5, y, steel[2] if nick else steel[4])
    # Blade end [6,0,8,4]
    cv.put(rect(6, 0, 8, 4, T), steel[3])
    # Guard [8,0,20,4]: tarnished bronze-gold, a boss in the middle, darker underneath
    guard = rect(8, 0, 20, 4, T)
    gt = 0.62 - 0.35 * (ys > 2) - 0.12 * ((xs < 9) | (xs > 19)) + 0.15 * (ys < 1)
    cv.put(guard, bands(PAL['gold'], gt, 0))
    cv.put(rect(13, 0, 15, 2, T), PAL['gold'][5])
    cv.px(14, 1, GEMS['amber'][3])
    # Grip [20,0,22,12]: dark leather in a spiral wrap
    for y in range(12):
        cv.px(20, y, PAL['leather'][3] if y % 3 != 2 else PAL['leather'][1])
        cv.px(21, y, PAL['leather'][2] if y % 3 != 0 else PAL['leather'][0])
    # Pommel [24,0,28,4]: gold, lit at the upper left
    cv.put(rect(24, 0, 28, 4, T), bands(PAL['gold'], 0.75 - 0.12 * (xs - 24) - 0.1 * ys, 0))
    # Cloth [8,8,16,24]: a faded crimson strip with a gold thread, frayed at the end
    cloth = rect(8, 8, 16, 24, T)
    fray = (ys > 20) & (rand(T, T, seed) < 0.3 + (ys - 20) * 0.15)
    ct = 0.55 + 0.18 * (np.abs(xs - 10.5) < 1) - 0.25 * (xs > 14) - (ys - 8) * 0.012 + 0.08 * (vnoise(T, T, 2, seed) - 0.5)
    cv.put(cloth & ~fray, bands(PAL['crimson'], ct, 0.3))
    cv.put(cloth & ~fray & (np.abs(ys - 11.5) < 0.6), PAL['gold'][3])
    return cv.image()


def flame_frames(frames=8, seed=5):
    """Golden flames licking up round the blade: thin tongues, ember-red at the edges, pale gold at the heart.
    Every tongue moves a whole number of cycles per loop, so the last frame runs into the first."""
    rng = random.Random(seed)
    w = h = T
    tongues = []
    for i in range(7):
        tongues.append((8.6 + i * 2.5 + rng.uniform(-0.6, 0.6), rng.uniform(0, math.tau), rng.choice((1, 2)), rng.uniform(21, 31)))
    sheet = Canvas(w, h * frames)
    xs, ys = grid(w, h)
    lift = h - ys
    for f in range(frames):
        t = f / frames * math.tau
        heat = np.zeros((h, w))
        for cx, phase, speed, tall in tongues:
            top = tall * (0.8 + 0.2 * math.sin(t * speed + phase))
            k = np.clip(lift / top, 0, 1)
            sway = np.sin(t * speed + phase + k * 2.5) * 2.4 * k
            half = 3.2 * (1 - k) ** 0.6 + 0.6
            heat += np.where(lift <= top, np.exp(-((xs - cx - sway) / half) ** 2) * (1 - k ** 1.5), 0)
        heat += np.clip(1 - lift / 6.0, 0, None) * np.exp(-((xs - 16) / 9) ** 2) * 0.9
        m = heat > 0.42
        layer = Canvas(w, h)
        layer.put(m, bands(PAL['ember'][1:], np.clip((heat - 0.42) / 1.25, 0, 1), 0.5))
        sheet.rgb[f * h:(f + 1) * h] = layer.rgb
        sheet.a[f * h:(f + 1) * h] = layer.a
    return sheet.image()


def ember_frames():
    """Four frames of a drifting ember, 16x16: a hot point in a soft halo, dimming and shrinking."""
    out = []
    xs, ys = grid(16)
    for (r, core) in [(6.0, 2.6), (5.0, 2.0), (4.0, 1.5), (3.0, 1.0)]:
        cv = Canvas(16)
        d = np.hypot(xs - 8, ys - 8)
        k = np.clip(1 - (d - core) / (r - core), 0, 1)
        cv.put(d < r, ramp([(220, 90, 30), (255, 170, 70), (255, 220, 140)], k), 0.25 + 0.75 * k ** 1.3)
        cv.put(d < core, (255, 246, 214), 1.0)
        out.append(cv.image())
    return out


# ---- Model elements ----

def face(uv, tex, cull=None):
    f = {"uv": [round(v, 3) for v in uv], "texture": tex}
    if cull:
        f["cullface"] = cull
    return f


def box(frm, to, faces, rotation=None, light=None, unshaded=False):
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces}
    if rotation:
        e["rotation"] = rotation
    if light:
        e["light_emission"] = light
    if unshaded:
        e["shade_direction_override"] = "up"
    return e


def all_faces(tex, uv=(0, 0, 16, 16), skip=()):
    return {d: face(uv, tex) for d in ("north", "south", "east", "west", "up", "down") if d not in skip}


# The sword stands at the middle, leaning a little toward the south-east, every part turned about one point
SWORD_TILT = {"origin": [8, 1, 8], "x": 7, "y": 0, "z": -5}


def sword_elements():
    e = []
    # Blade: 1 thick (x), 2 wide (z), from the ash to the guard
    e.append(box([7.5, 0.5, 7], [8.5, 12.5, 9], {
        "east": face([0, 0, 2, 12], "#sword"), "west": face([2, 0, 0, 12], "#sword"),
        "north": face([2, 0, 3, 12], "#sword"), "south": face([2, 0, 3, 12], "#sword"),
        "up": face([3, 0, 4, 2], "#sword"),
    }, SWORD_TILT))
    # Guard across the blade
    e.append(box([7.25, 12.5, 5], [8.75, 13.5, 11], {
        "east": face([4, 0, 10, 1], "#sword"), "west": face([4, 0, 10, 1], "#sword"),
        "north": face([4, 1, 6, 2], "#sword"), "south": face([4, 1, 6, 2], "#sword"),
        "up": face([4, 0, 10, 1], "#sword"), "down": face([4, 1, 10, 2], "#sword"),
    }, SWORD_TILT))
    # Grip
    e.append(box([7.5, 13.5, 7.5], [8.5, 17.5, 8.5], {
        d: face([10, 0, 11, 4], "#sword") for d in ("north", "south", "east", "west")
    }, SWORD_TILT))
    # Pommel
    e.append(box([7.25, 17.5, 7.25], [8.75, 19, 8.75], all_faces("#sword", (12, 0, 14, 2)), SWORD_TILT))
    # A strip of cloth tied under the guard, hanging down the west side of the blade
    e.append(box([7.4, 7.5, 9.05], [8.6, 12.5, 9.05], {
        "north": face([4, 4, 6, 12], "#sword"), "south": face([4, 4, 6, 12], "#sword"),
    }, SWORD_TILT))
    return e


# Eight stones in a ring, each turned about the middle. Three are taller standing stones with a rune
# carved on the outer face; the rest are low and worn. (angle, height, rune or None)
RING = [(0, 7.0, 0), (45, 2.6, None), (90, 3.4, None), (135, 6.2, 1), (180, 3.0, None), (225, 6.6, 2), (270, 2.7, None), (315, 3.1, None)]


def ring_elements(lit):
    e = []
    for i, (angle, height, rune) in enumerate(RING):
        rot = {"origin": [8, 0, 8], "x": 0, "y": angle, "z": 0}
        tex = "#stone" if i % 2 == 0 else "#stone2"
        tall = rune is not None
        # The stone sits on the north side of the middle before it's turned into place
        x0, x1 = (6.0, 10.0) if tall else (5.6, 10.4)
        wide = x1 - x0
        v0 = max(0.0, 16 - height)
        e.append(box([x0, 0, 1.2], [x1, height, 4.0], {
            "north": face([1, v0, 1 + wide, 16], tex), "south": face([6, v0, 6 + wide, 16], tex),
            "east": face([9, v0, 11.8, 16], tex), "west": face([3, v0, 5.8, 16], tex),
            "up": face([2, 1, 2 + wide, 3.8], tex), "down": face([0, 0, wide, 2.8], tex, "down"),
        }, rot))
        if tall:
            # The rune: a decal just off the outer face, drawn at twice the density, lit from inside when the fire burns
            e.append(box([x0, 0.6, 1.18], [x1, height - 0.4, 1.18], {
                "north": face([4, 16 - (height - 1.0) * 2, 12, 16], f"#rune{rune}"),
            }, rot, light=10 if lit else None))
    return e


def ash_elements(lit):
    tex = "#ash"
    # A square bed turned 45 degrees inside the ring, and a second, smaller one square to it, so it reads round
    return [
        box([3.5, 0, 3.5], [12.5, 1.2, 12.5], {"up": face([0, 0, 16, 16], tex), "down": face([0, 0, 16, 16], tex, "down")},
            {"origin": [8, 0, 8], "x": 0, "y": 45, "z": 0}, light=8 if lit else None),
        box([3.2, 0, 3.2], [12.8, 1.0, 12.8], {"up": face([0, 0, 16, 16], tex), "down": face([0, 0, 16, 16], tex, "down")},
            light=8 if lit else None),
    ]


def flame_elements():
    # Two crossed sheets of flame round the blade, as a campfire's
    return [
        box([3.5, 0.8, 8], [12.5, 15.8, 8], {"north": face([2, 1, 14, 16], "#fire"), "south": face([2, 1, 14, 16], "#fire")},
            {"origin": [8, 8, 8], "axis": "y", "angle": 45}, light=15, unshaded=True),
        box([8, 0.8, 3.5], [8, 15.8, 12.5], {"east": face([2, 1, 14, 16], "#fire"), "west": face([2, 1, 14, 16], "#fire")},
            {"origin": [8, 8, 8], "axis": "y", "angle": 45}, light=15, unshaded=True),
    ]


def model(lit):
    textures = {
        "particle": "guildmark:block/wardfire_stone",
        "stone": "guildmark:block/wardfire_stone",
        "stone2": "guildmark:block/wardfire_stone_mossy",
        "ash": "guildmark:block/wardfire_ash_lit" if lit else "guildmark:block/wardfire_ash",
        "sword": "guildmark:block/wardfire_sword",
    }
    for i in range(3):
        textures[f"rune{i}"] = f"guildmark:block/wardfire_rune_{i}" + ("_lit" if lit else "")
    elements = ring_elements(lit) + ash_elements(lit) + sword_elements()
    if lit:
        textures["fire"] = "guildmark:block/wardfire_flame"
        elements += flame_elements()
    return {
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "textures": textures,
        "elements": elements,
    }


# ---- Preview ----

DIRS = {
    # vertex 0 (u0, v0), vertex 3 (u1, v0), vertex 1 (u0, v1), as Minecraft lays a face out
    "down": (("x0", "y0", "z1"), ("x1", "y0", "z1"), ("x0", "y0", "z0")),
    "up": (("x0", "y1", "z0"), ("x1", "y1", "z0"), ("x0", "y1", "z1")),
    "north": (("x1", "y1", "z0"), ("x0", "y1", "z0"), ("x1", "y0", "z0")),
    "south": (("x0", "y1", "z1"), ("x1", "y1", "z1"), ("x0", "y0", "z1")),
    "west": (("x0", "y1", "z0"), ("x0", "y1", "z1"), ("x0", "y0", "z0")),
    "east": (("x1", "y1", "z1"), ("x1", "y1", "z0"), ("x1", "y0", "z1")),
}
LIGHT = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def rotation_matrix(rot):
    if "axis" in rot:
        a = math.radians(rot["angle"])
        c, s = math.cos(a), math.sin(a)
        return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
                "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
                "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[rot["axis"]]
    x, y, z = (math.radians(rot.get(k, 0)) for k in ("x", "y", "z"))
    Rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    Rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def render(mdl, textures, yaw=35, pitch=28, scale=14, size=(300, 320), frame=0):
    img = np.zeros((size[1], size[0], 4), float)
    img[..., :3] = (54, 60, 74)
    img[..., 3] = 255
    zbuf = np.full((size[1], size[0]), 1e9)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def view(p):
        x, y, z = p[0] - 8, p[1], p[2] - 8
        x, z = x * cy - z * sy, x * sy + z * cy
        y2 = y * cp - z * sp
        z2 = y * sp + z * cp
        return np.array([size[0] / 2 + x * scale, size[1] * 0.78 - y2 * scale, z2])

    ys, xs = np.mgrid[0:size[1], 0:size[0]]
    for el in mdl["elements"]:
        (x0, y0, z0), (x1, y1, z1) = el["from"], el["to"]
        coords = {"x0": x0, "x1": x1, "y0": y0, "y1": y1, "z0": z0, "z1": z1}
        R = rotation_matrix(el["rotation"]) if "rotation" in el else np.eye(3)
        origin = np.array(el["rotation"]["origin"], float) if "rotation" in el else np.zeros(3)
        if "rotation" in el and el["rotation"].get("rescale"):
            # As the game does: stretch each axis so the turned element still spans its full width
            R = R @ np.diag([1 / max(abs(R[:, i]).max(), 1e-6) for i in range(3)])
        for d, f in el["faces"].items():
            tex = textures[mdl["textures"][f["texture"][1:]]]
            if tex.size[1] > tex.size[0]:
                n = tex.size[1] // tex.size[0]
                tex = tex.crop((0, (frame % n) * tex.size[0], tex.size[0], (frame % n + 1) * tex.size[0]))
            T = np.array(tex, float)
            sc = T.shape[1] / 16
            A, B, D = [origin + R @ (np.array([coords[k] for k in v], float) - origin) for v in DIRS[d]]
            a, b, dd = view(A), view(B), view(D)
            e1, e2 = b - a, dd - a
            det = e1[0] * e2[1] - e1[1] * e2[0]
            if abs(det) < 1e-6:
                continue
            corners = [a, b, dd, b + e2]
            bx0 = int(max(0, min(c[0] for c in corners))); bx1 = int(min(size[0] - 1, max(c[0] for c in corners) + 1))
            by0 = int(max(0, min(c[1] for c in corners))); by1 = int(min(size[1] - 1, max(c[1] for c in corners) + 1))
            if bx0 >= bx1 or by0 >= by1:
                continue
            px = xs[by0:by1, bx0:bx1] + 0.5 - a[0]
            py = ys[by0:by1, bx0:bx1] + 0.5 - a[1]
            s = (px * e2[1] - py * e2[0]) / det
            t = (e1[0] * py - e1[1] * px) / det
            inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
            depth = a[2] + s * e1[2] + t * e2[2]
            u0, v0, u1, v1 = f["uv"]
            tu = np.clip(((u0 + s * (u1 - u0)) * sc).astype(int), 0, T.shape[1] - 1)
            tv = np.clip(((v0 + t * (v1 - v0)) * sc).astype(int), 0, T.shape[0] - 1)
            texel = T[tv, tu]
            # Painter's order is not enough for crossed planes: keep the nearest opaque texel
            ok = inside & (texel[..., 3] > 128) & (depth < zbuf[by0:by1, bx0:bx1] - 1e-4)
            k = 1.0 if (el.get("light_emission") or el.get("shade_direction_override")) else LIGHT[d]
            region = img[by0:by1, bx0:bx1]
            region[..., :3] = np.where(ok[..., None], texel[..., :3] * k, region[..., :3])
            zb = zbuf[by0:by1, bx0:bx1]
            zb[ok] = depth[ok]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


# ---- Writing it all out ----

def main():
    assets = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    block_tex = os.path.join(assets, "textures/block")
    particle_tex = os.path.join(assets, "textures/particle")
    for d in (block_tex, particle_tex, os.path.join(assets, "models/block"), os.path.join(assets, "models/item"),
              os.path.join(assets, "blockstates"), os.path.join(assets, "items"), os.path.join(assets, "particles")):
        os.makedirs(d, exist_ok=True)

    textures = {
        "guildmark:block/wardfire_stone": stone(21, False),
        "guildmark:block/wardfire_stone_mossy": stone(34, True),
        "guildmark:block/wardfire_ash": ash(3, False),
        "guildmark:block/wardfire_ash_lit": ash(3, True),
        "guildmark:block/wardfire_sword": sword_sheet(9),
        "guildmark:block/wardfire_flame": flame_frames(),
    }
    for i in range(3):
        mask = rune_glyph(40 + i)
        textures[f"guildmark:block/wardfire_rune_{i}"] = rune_decal(mask, False)
        textures[f"guildmark:block/wardfire_rune_{i}_lit"] = rune_decal(mask, True)
    for key, img in textures.items():
        img.save(os.path.join(block_tex, key.split("/")[-1] + ".png"))
    with open(os.path.join(block_tex, "wardfire_flame.png.mcmeta"), "w") as f:
        json.dump({"animation": {"frametime": 2}}, f, indent=2)
        f.write("\n")

    embers = ember_frames()
    for i, img in enumerate(embers):
        img.save(os.path.join(particle_tex, f"ward_ember_{i}.png"))
    with open(os.path.join(assets, "particles/ward_ember.json"), "w") as f:
        json.dump({"textures": [f"guildmark:ward_ember_{i}" for i in range(len(embers))]}, f, indent=2)
        f.write("\n")

    cold, lit = model(False), model(True)
    for name, mdl in (("wardfire", cold), ("wardfire_lit", lit)):
        with open(os.path.join(assets, f"models/block/{name}.json"), "w") as f:
            json.dump(mdl, f, indent=2)
            f.write("\n")
    with open(os.path.join(assets, "blockstates/wardfire.json"), "w") as f:
        json.dump({"variants": {"lit=false": {"model": "guildmark:block/wardfire"},
                                "lit=true": {"model": "guildmark:block/wardfire_lit"}}}, f, indent=2)
        f.write("\n")
    with open(os.path.join(assets, "items/wardfire.json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "guildmark:block/wardfire_lit"}}, f, indent=2)
        f.write("\n")

    if preview:
        shots = [render(cold, textures, yaw=35), render(lit, textures, yaw=35, frame=0),
                 render(lit, textures, yaw=-130, frame=3), render(lit, textures, yaw=80, pitch=12, frame=5)]
        flames = textures["guildmark:block/wardfire_flame"].resize((T * 3, T * 8 * 3), Image.NEAREST)
        sheet = Image.new("RGBA", (300 * 4 + flames.width + 20, max(320, flames.height)), (40, 44, 54, 255))
        for i, s in enumerate(shots):
            sheet.paste(s, (i * 300, 0))
        sheet.paste(flames, (1200 + 10, 0), flames)
        parts = [textures[k] for k in ("guildmark:block/wardfire_stone", "guildmark:block/wardfire_stone_mossy",
                                       "guildmark:block/wardfire_ash", "guildmark:block/wardfire_ash_lit",
                                       "guildmark:block/wardfire_sword", "guildmark:block/wardfire_rune_0_lit")] + embers
        strip = Image.new("RGBA", (sum(p.width * 4 + 8 for p in parts) + 10, 140), (40, 44, 54, 255))
        x = 10
        for p in parts:
            big = p.resize((p.width * 4, p.height * 4), Image.NEAREST)
            strip.paste(big, (x, 6), big)
            x += big.width + 8
        out = Image.new("RGBA", (max(sheet.width, strip.width), sheet.height + strip.height), (40, 44, 54, 255))
        out.paste(sheet, (0, 0))
        out.paste(strip, (0, sheet.height))
        out.save(preview)
    print("wardfire: %d textures, 2 models" % (len(textures) + len(embers)))


if __name__ == "__main__":
    main()
