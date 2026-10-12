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

CL = (0, 0, 0, 0)


# ---- Painting ----

def value_noise(w, h, cell, seed):
    """Smooth noise in 0..1, tiling at the texture's edges."""
    rng = random.Random(seed)
    gw, gh = max(1, w // cell), max(1, h // cell)
    grid = [[rng.random() for _ in range(gw)] for _ in range(gh)]
    out = np.zeros((h, w))
    for y in range(h):
        for x in range(w):
            fx, fy = x / cell, y / cell
            x0, y0 = int(fx) % gw, int(fy) % gh
            x1, y1 = (x0 + 1) % gw, (y0 + 1) % gh
            tx, ty = fx - int(fx), fy - int(fy)
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = grid[y0][x0] * (1 - tx) + grid[y0][x1] * tx
            b = grid[y1][x0] * (1 - tx) + grid[y1][x1] * tx
            out[y, x] = a * (1 - ty) + b * ty
    return out


def ramp(colors, t):
    """A colour from a list of stops, for t in 0..1."""
    t = min(max(t, 0.0), 0.9999) * (len(colors) - 1)
    i = int(t)
    f = t - i
    a, b = colors[i], colors[i + 1]
    return tuple(int(a[k] * (1 - f) + b[k] * f) for k in range(3)) + (255,)


STONE = [(70, 66, 62), (96, 91, 85), (122, 116, 107), (146, 140, 129), (166, 160, 148)]
MOSS = [(52, 70, 38), (70, 92, 50), (92, 116, 62)]


def stone(seed):
    """Weathered grey-brown stone, a crack or two, moss gathering along the top."""
    w = h = 16
    n = value_noise(w, h, 4, seed) * 0.6 + value_noise(w, h, 2, seed + 1) * 0.3 + value_noise(w, h, 8, seed + 2) * 0.1
    img = Image.new("RGBA", (w, h))
    rng = random.Random(seed)
    for y in range(h):
        for x in range(w):
            img.putpixel((x, y), ramp(STONE, 0.15 + n[y, x] * 0.75))
    # Chipped edges catch the light, hollows don't
    for x in range(w):
        if rng.random() < 0.55:
            img.putpixel((x, 0), ramp(STONE, 0.95))
        if rng.random() < 0.5:
            img.putpixel((x, h - 1), ramp(STONE, 0.1))
    # Cracks: short random walks in near-black
    for _ in range(2):
        x, y = rng.randrange(2, w - 2), rng.randrange(3, h - 3)
        for _ in range(rng.randrange(4, 8)):
            img.putpixel((x, y), (48, 45, 42, 255))
            if 0 <= x + 1 < w:
                img.putpixel((x + 1, y), ramp(STONE, 0.35))
            x = min(w - 1, max(0, x + rng.choice((-1, 0, 1))))
            y = min(h - 1, max(0, y + 1))
    # Moss along the top, thinning downward
    moss = value_noise(w, h, 3, seed + 7)
    for y in range(6):
        for x in range(w):
            if moss[y, x] > 0.42 + y * 0.09:
                img.putpixel((x, y), ramp(MOSS, moss[y, x]))
    return img


def rune_glyph(seed):
    """A carved Warden's rune, as a mask of groove pixels. It's drawn in the middle 8x12 of a 16x16
    texture and laid over a standing stone's face at twice the usual texel density."""
    rng = random.Random(seed)
    mask = np.zeros((16, 16), bool)
    cx = 8
    top, bottom = 3, 13
    # A spine, a pair of arms, and a foot that differs on each stone
    for y in range(top, bottom):
        mask[y, cx] = True
    arm = rng.randrange(6, 9)
    for i in range(1, 4):
        mask[arm - i + 1, cx - i] = mask[arm - i + 1, cx + i] = True
    foot = rng.randrange(3)
    if foot == 0:
        for x in range(cx - 2, cx + 3):
            mask[bottom - 1, x] = True
    elif foot == 1:
        mask[bottom - 2, cx - 2] = mask[bottom - 2, cx + 2] = True
        mask[bottom - 1, cx - 1] = mask[bottom - 1, cx + 1] = True
    else:
        for y in range(bottom - 4, bottom - 1):
            mask[y, cx - 3] = mask[y, cx + 3] = True
    mask[top - 1, cx - 1] = mask[top - 1, cx + 1] = True
    return mask


def rune_decal(mask, lit):
    """Just the groove pixels, for a plane laid over the stone: dark when cold, gold when lit."""
    img = Image.new("RGBA", (16, 16), CL)
    for y in range(16):
        for x in range(16):
            if mask[y, x]:
                img.putpixel((x, y), ((255, 210, 112, 255) if y % 3 else (255, 236, 170, 255)) if lit else (40, 37, 35, 255))
    return img


def ash(seed, lit):
    """The bed of ash: grey and black, with charcoal chunks, and live embers when lit."""
    w = h = 16
    rng = random.Random(seed)
    n = value_noise(w, h, 3, seed) * 0.7 + value_noise(w, h, 2, seed + 3) * 0.3
    img = Image.new("RGBA", (w, h))
    for y in range(h):
        for x in range(w):
            v = n[y, x]
            img.putpixel((x, y), ramp([(40, 38, 37), (62, 59, 57), (86, 82, 78), (112, 107, 101)], v) if not lit
                         else ramp([(28, 26, 26), (46, 43, 42), (66, 62, 60), (92, 88, 84)], v))
    for _ in range(9):
        x, y = rng.randrange(w), rng.randrange(h)
        img.putpixel((x, y), (20, 18, 18, 255))
        if x + 1 < w:
            img.putpixel((x + 1, y), (34, 31, 30, 255))
    if lit:
        heat = value_noise(w, h, 3, seed + 11)
        for y in range(h):
            for x in range(w):
                # Hottest in the middle of the bed
                d = math.hypot(x - 7.5, y - 7.5) / 8
                t = heat[y, x] * (1.25 - d * 0.8)
                if t > 0.5:
                    img.putpixel((x, y), ramp([(150, 52, 18), (226, 110, 30), (255, 178, 70), (255, 230, 150)], (t - 0.5) / 0.5))
    return img


STEEL = [(54, 56, 64), (88, 92, 102), (124, 128, 138), (162, 166, 174), (196, 200, 206)]


def sword_sheet(seed):
    """Every part of the planted sword on one 16x16 texture (regions listed in SWORD_UV)."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), CL)
    # Blade flat [0,0,2,12]: a fuller down the middle, darker toward the tip, notches and rust
    for y in range(12):
        for x in range(2):
            t = 0.75 - y * 0.02 + (0.12 if x == 0 else -0.05)
            img.putpixel((x, y), ramp(STEEL, t))
    for y in range(1, 10):
        img.putpixel((1, y), ramp(STEEL, 0.38))
    for y in rng.sample(range(2, 11), 3):
        img.putpixel((rng.randrange(2), y), (108, 70, 44, 255))
    # Blade edge [2,0,3,12]: bright, nicked
    for y in range(12):
        img.putpixel((2, y), ramp(STEEL, 0.95 if rng.random() > 0.2 else 0.55))
    # Blade end [3,0,4,2]: the cut face where it meets the ash, and the top under the guard
    for y in range(2):
        img.putpixel((3, y), ramp(STEEL, 0.5))
    # Guard [4,0,10,2]: tarnished bronze, a gold boss in the middle
    for y in range(2):
        for x in range(4, 10):
            img.putpixel((x, y), ramp([(96, 68, 32), (140, 102, 48), (182, 140, 70)], 0.75 - y * 0.35 - (0.15 if x in (4, 9) else 0)))
    img.putpixel((6, 0), (214, 176, 96, 255))
    img.putpixel((7, 0), (214, 176, 96, 255))
    # Grip [10,0,11,6]: leather with a spiral wrap
    for y in range(6):
        img.putpixel((10, y), (88, 58, 36, 255) if (y % 2 == 0) else (58, 38, 24, 255))
    # Pommel [12,0,14,2]: gold
    for y in range(2):
        for x in range(12, 14):
            img.putpixel((x, y), ramp([(120, 88, 40), (176, 138, 66), (220, 184, 104)], 0.8 - y * 0.4 - (x - 12) * 0.2))
    # Cloth [4,4,8,12]: a faded crimson strip, frayed at the end
    for y in range(4, 12):
        for x in range(4, 8):
            if y >= 10 and rng.random() < 0.35 + (y - 10) * 0.25:
                continue
            fold = 0.55 + (0.2 if x == 5 else 0) - (0.2 if x == 7 else 0) - (y - 4) * 0.03
            img.putpixel((x, y), ramp([(70, 18, 20), (112, 30, 30), (150, 46, 40), (176, 70, 56)], fold))
    return img


def flame_frames(frames=8, w=16, h=16, seed=5):
    """Golden flames licking up round the blade: thin tongues, amber at the edges, pale gold at the heart.
    Every tongue moves at a whole number of cycles per loop, so the last frame runs into the first."""
    rng = random.Random(seed)
    tongues = []
    for i in range(5):
        tongues.append((4.4 + i * 1.8 + rng.uniform(-0.4, 0.4), rng.uniform(0, math.tau), rng.choice((1, 2)), rng.uniform(9, 14.5)))
    sheet = Image.new("RGBA", (w, h * frames), CL)
    for f in range(frames):
        t = f / frames * math.tau
        for y in range(h):
            lift = h - 1 - y
            for x in range(w):
                heat = 0.0
                for cx, phase, speed, tall in tongues:
                    top = tall * (0.8 + 0.2 * math.sin(t * speed + phase))
                    if lift > top:
                        continue
                    k = lift / top
                    sway = math.sin(t * speed + phase + k * 2.5) * 1.3 * k
                    half = 1.6 * (1 - k) ** 0.6 + 0.3
                    heat += math.exp(-((x - cx - sway) / half) ** 2) * (1 - k ** 1.5)
                # The bed of the fire, where the tongues meet
                heat += max(0.0, 1 - lift / 3.0) * math.exp(-((x - 7.5) / 4.5) ** 2) * 0.9
                if heat > 0.45:
                    sheet.putpixel((x, f * h + y), ramp([(196, 74, 20), (236, 124, 34), (252, 172, 58), (255, 210, 102), (255, 236, 168)],
                                                        min(1.0, (heat - 0.45) / 1.3)))
    return sheet


def ember_frames():
    """Four frames of a drifting ember, 8x8: a hot point in a soft halo, dimming and shrinking."""
    out = []
    for i, (r, core) in enumerate([(3.2, 1.6), (2.6, 1.2), (2.0, 0.9), (1.4, 0.6)]):
        img = Image.new("RGBA", (8, 8), CL)
        for y in range(8):
            for x in range(8):
                d = math.hypot(x - 3.5, y - 3.5)
                if d < core:
                    img.putpixel((x, y), (255, 244, 200, 255))
                elif d < r:
                    k = 1 - (d - core) / (r - core)
                    img.putpixel((x, y), (255, int(160 + 60 * k), int(60 + 40 * k), int(70 + 170 * k)))
        out.append(img)
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
        "guildmark:block/wardfire_stone": stone(21),
        "guildmark:block/wardfire_stone_mossy": stone(34),
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
        flames = textures["guildmark:block/wardfire_flame"].resize((16 * 6, 128 * 6), Image.NEAREST)
        sheet = Image.new("RGBA", (300 * 4 + flames.width + 20, max(320, flames.height)), (40, 44, 54, 255))
        for i, s in enumerate(shots):
            sheet.paste(s, (i * 300, 0))
        sheet.paste(flames, (1200 + 10, 0), flames)
        parts = [textures[k] for k in ("guildmark:block/wardfire_stone", "guildmark:block/wardfire_stone_mossy",
                                       "guildmark:block/wardfire_ash", "guildmark:block/wardfire_ash_lit",
                                       "guildmark:block/wardfire_sword", "guildmark:block/wardfire_rune_0_lit")] + embers
        strip = Image.new("RGBA", (sum(p.width * 6 + 8 for p in parts) + 10, 110), (40, 44, 54, 255))
        x = 10
        for p in parts:
            big = p.resize((p.width * 6, p.height * 6), Image.NEAREST)
            strip.paste(big, (x, 6), big)
            x += big.width + 8
        out = Image.new("RGBA", (max(sheet.width, strip.width), sheet.height + strip.height), (40, 44, 54, 255))
        out.paste(sheet, (0, 0))
        out.paste(strip, (0, sheet.height))
        out.save(preview)
    print("wardfire: %d textures, 2 models" % (len(textures) + len(embers)))


if __name__ == "__main__":
    main()
