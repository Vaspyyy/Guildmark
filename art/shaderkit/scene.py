"""
A small voxel world for the shader previews: rolling hills with a stream and a pond, oak and spruce woods, a ruined
watchtower, a cottage with a lantern-lit path and a Wardfire. Meshed the way Minecraft meshes terrain (only faces that
can be seen, smooth light and ambient occlusion per corner, plants as crossed cards), with the vanilla textures read
out of a Minecraft client jar and Guildmark's own from the repo.
"""

import io
import math
import os
import re
import zipfile

import moderngl
import numpy as np
from PIL import Image

# Block -> (top, side, bottom textures, layer, model, light, tint)
# layer: solid | cutout | translucent; model: cube | cross | torch | lantern | water | path | flame
BLOCKS = {
    "air": None,
    "grass_block": ("grass_block_top", "grass_block_side", "dirt", "solid", "cube", 0, "grass"),
    "dirt": ("dirt", "dirt", "dirt", "solid", "cube", 0, None),
    "coarse_dirt": ("coarse_dirt", "coarse_dirt", "coarse_dirt", "solid", "cube", 0, None),
    "dirt_path": ("dirt_path_top", "dirt_path_side", "dirt", "solid", "path", 0, None),
    "stone": ("stone", "stone", "stone", "solid", "cube", 0, None),
    "andesite": ("andesite", "andesite", "andesite", "solid", "cube", 0, None),
    "gravel": ("gravel", "gravel", "gravel", "solid", "cube", 0, None),
    "sand": ("sand", "sand", "sand", "solid", "cube", 0, None),
    "cobblestone": ("cobblestone", "cobblestone", "cobblestone", "solid", "cube", 0, None),
    "mossy_cobblestone": ("mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "solid", "cube", 0, None),
    "stone_bricks": ("stone_bricks", "stone_bricks", "stone_bricks", "solid", "cube", 0, None),
    "mossy_stone_bricks": ("mossy_stone_bricks", "mossy_stone_bricks", "mossy_stone_bricks", "solid", "cube", 0, None),
    "cracked_stone_bricks": ("cracked_stone_bricks", "cracked_stone_bricks", "cracked_stone_bricks", "solid", "cube", 0, None),
    "oak_log": ("oak_log_top", "oak_log", "oak_log_top", "solid", "cube", 0, None),
    "spruce_log": ("spruce_log_top", "spruce_log", "spruce_log_top", "solid", "cube", 0, None),
    "oak_planks": ("oak_planks", "oak_planks", "oak_planks", "solid", "cube", 0, None),
    "spruce_planks": ("spruce_planks", "spruce_planks", "spruce_planks", "solid", "cube", 0, None),
    "dark_oak_planks": ("dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "solid", "cube", 0, None),
    "oak_leaves": ("oak_leaves", "oak_leaves", "oak_leaves", "cutout", "cube", 0, "foliage"),
    "spruce_leaves": ("spruce_leaves", "spruce_leaves", "spruce_leaves", "cutout", "cube", 0, "spruce"),
    "short_grass": ("short_grass", "short_grass", "short_grass", "cutout", "cross", 0, "grass"),
    "fern": ("fern", "fern", "fern", "cutout", "cross", 0, "grass"),
    "poppy": ("poppy", "poppy", "poppy", "cutout", "cross", 0, None),
    "dandelion": ("dandelion", "dandelion", "dandelion", "cutout", "cross", 0, None),
    "torch": ("torch", "torch", "torch", "cutout", "torch", 14, None),
    "lantern": ("lantern", "lantern", "lantern", "cutout", "lantern", 15, None),
    "water": ("water_still", "water_still", "water_still", "translucent", "water", 0, "water"),
    "guildmark:wardfire": ("guildmark:wardfire_flame", "guildmark:wardfire_flame", "guildmark:wardfire_flame", "cutout", "flame", 15, None),
    "guildmark:wardfire_stone": ("guildmark:wardfire_stone", "guildmark:wardfire_stone", "guildmark:wardfire_stone", "solid", "cube", 0, None),
}

TINTS = {"grass": (0x91, 0xBD, 0x59), "foliage": (0x77, 0xAB, 0x2F), "spruce": (0x61, 0x99, 0x61), "water": (0x3F, 0x76, 0xE4)}

FACES = {
    # name: normal, 4 corners (counter-clockwise seen from outside), uv corners
    "up": ((0, 1, 0), [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)]),
    "down": ((0, -1, 0), [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)]),
    "north": ((0, 0, -1), [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)]),
    "south": ((0, 0, 1), [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)]),
    "west": ((-1, 0, 0), [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)]),
    "east": ((1, 0, 0), [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)]),
}
QUAD_UV = [(0, 1), (1, 1), (1, 0), (0, 0)]


def read_block_ids(block_properties):
    """block.properties as minecraft id -> number, the way Iris hands mc_Entity.x over."""
    ids = {}
    if not os.path.exists(block_properties):
        return ids
    text = open(block_properties, encoding="utf-8").read().replace("\\\n", " ")
    for line in text.split("\n"):
        line = line.strip()
        m = re.match(r"block\.(\d+)\s*=\s*(.*)", line)
        if not m:
            continue
        for name in m.group(2).split():
            # namespace:block[:property=value...]; the scene's palette names minecraft blocks bare
            parts = name.split(":")
            if parts[0] == "minecraft":
                key = parts[1]
            elif len(parts) > 1 and "=" not in parts[1]:
                key = parts[0] + ":" + parts[1]
            else:
                key = parts[0]
            ids.setdefault(key, int(m.group(1)))
    return ids


class Atlas:
    """Every texture the palette uses, 16x16 cells in one image, tinted where Minecraft tints them."""

    def __init__(self, mcjar, repo_assets):
        self.cells = {}
        self.jar = zipfile.ZipFile(mcjar)
        self.repo = repo_assets
        names = set()
        for spec in BLOCKS.values():
            if spec:
                names.update((spec[0], spec[1], spec[2]))
        names.update(["grass_block_side_overlay", "rain", "snow"])
        self.size = 512
        self.image = Image.new("RGBA", (self.size, self.size), (0, 0, 0, 0))
        for i, n in enumerate(sorted(names)):
            img = self._load(n)
            if img is None:
                continue
            cx, cy = (i % 32) * 16, (i // 32) * 16
            self.image.paste(img, (cx, cy))
            self.cells[n] = (cx, cy)

    def _load(self, name):
        try:
            if name.startswith("guildmark:"):
                data = open(os.path.join(self.repo, "textures/block", name.split(":")[1] + ".png"), "rb").read()
            elif name in ("rain", "snow"):
                data = self.jar.read("assets/minecraft/textures/environment/%s.png" % name)
            else:
                data = self.jar.read("assets/minecraft/textures/block/%s.png" % name)
        except (KeyError, FileNotFoundError):
            return None
        img = Image.open(io.BytesIO(data)).convert("RGBA")
        w, h = img.size
        img = img.crop((0, 0, w, w)) if h > w else img
        return img.resize((16, 16), Image.NEAREST)

    def tinted(self, name, tint):
        """A copy of the cell under name with the biome colour multiplied in (the grass side gets its overlay)."""
        key = name + "#" + (tint or "")
        if key in self.cells:
            return key
        if name not in self.cells:
            return name
        cx, cy = self.cells[name]
        img = self.image.crop((cx, cy, cx + 16, cy + 16))
        t = np.array(TINTS[tint], dtype=np.float32) / 255.0
        a = np.asarray(img).astype(np.float32) / 255.0
        if name == "grass_block_side" and "grass_block_side_overlay" in self.cells:
            ox, oy = self.cells["grass_block_side_overlay"]
            o = np.asarray(self.image.crop((ox, oy, ox + 16, oy + 16))).astype(np.float32) / 255.0
            a[..., :3] = a[..., :3] * (1 - o[..., 3:]) + o[..., :3] * t * o[..., 3:]
        else:
            a[..., :3] *= t
        i = len(self.cells)
        nx, ny = (i % 32) * 16, (i // 32) * 16
        self.image.paste(Image.fromarray((a * 255).clip(0, 255).astype(np.uint8)), (nx, ny))
        self.cells[key] = (nx, ny)
        return key

    def uv(self, name, u, v):
        cx, cy = self.cells.get(name, (0, 0))
        return ((cx + u * 16) / self.size, (cy + v * 16) / self.size)

    def mid(self, name):
        return self.uv(name, 0.5, 0.5)

    def texture(self, ctx):
        # The image's top row goes first, so v = 0 is the top of the atlas, as in Minecraft
        tex = ctx.texture(self.image.size, 4, self.image.tobytes())
        tex.build_mipmaps(0, 4)
        tex.filter = (moderngl.NEAREST_MIPMAP_LINEAR, moderngl.NEAREST)
        return tex


class Mesh:
    """One render layer's vertices, uploaded as one buffer per attribute so a program takes just what it declares."""

    def __init__(self):
        self.data = {k: [] for k in ("pos", "color", "uv0", "uv2", "normal", "entity", "mid", "tangent")}
        self.count = 0
        self._vbos = None
        self._vaos = {}
        self.offset = (0.0, 0.0, 0.0)

    def quad(self, corners, color4, uvs, light, normal, entity, mid, tangent):
        order = (0, 1, 2, 0, 2, 3)
        for i in order:
            self.data["pos"].append(corners[i])
            self.data["color"].append(color4[i])
            self.data["uv0"].append(uvs[i])
            self.data["uv2"].append(light[i])
            self.data["normal"].append(normal)
            self.data["entity"].append((entity, 0, 0, 0))
            self.data["mid"].append(mid)
            self.data["tangent"].append(tangent)
        self.count += 6

    def finish(self):
        self.arrays = {k: np.asarray(v, dtype="f4") for k, v in self.data.items()}
        self.data = None

    def set_camera(self, ctx, cam):
        if self.count == 0:
            return
        rel = self.arrays["pos"] - np.asarray(cam, dtype="f4")
        if self._vbos is None:
            self._vbos = {k: ctx.buffer(v.tobytes()) for k, v in self.arrays.items() if k != "pos"}
            self._vbos["pos"] = ctx.buffer(rel.tobytes())
        else:
            self._vbos["pos"].write(rel.astype("f4").tobytes())

    def vao(self, ctx, prog):
        if self.count == 0:
            return None
        key = id(prog)
        if key in self._vaos:
            return self._vaos[key]
        names = {"pos": ("iris_Position", "3f"), "color": ("iris_Color", "4f"), "uv0": ("iris_UV0", "2f"),
                 "uv2": ("iris_UV2", "2f"), "normal": ("iris_Normal", "3f"), "entity": ("mc_Entity", "4f"),
                 "mid": ("mc_midTexCoord", "2f"), "tangent": ("at_tangent", "4f")}
        content = []
        for k, (attr, fmt) in names.items():
            if attr in prog:
                a = prog[attr]
                comps = a.dimension if hasattr(a, "dimension") else int(fmt[0])
                buf = self._vbos[k]
                if comps < int(fmt[0]):
                    # The program declares fewer components (vec2 mc_Entity, say): read the start of each one
                    fmt = "%df %dx" % (comps, (int(fmt[0]) - comps) * 4)
                content.append((buf, fmt, attr))
        v = ctx.vertex_array(prog, content)
        self._vaos[key] = v
        return v


class World:
    def __init__(self, sx, sy, sz, y0):
        self.sx, self.sy, self.sz, self.y0 = sx, sy, sz, y0
        self.names = ["air"]
        self.index = {"air": 0}
        self.b = np.zeros((sx, sy, sz), dtype=np.uint8)

    def id(self, name):
        if name not in self.index:
            self.index[name] = len(self.names)
            self.names.append(name)
        return self.index[name]

    def set(self, x, y, z, name):
        y -= self.y0
        if 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz:
            self.b[x, y, z] = self.id(name)

    def get(self, x, y, z):
        y -= self.y0
        if 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz:
            return self.names[self.b[x, y, z]]
        return "air" if y >= 0 else "stone"

    def spec(self, name):
        return BLOCKS.get(name)


def value_noise(shape, scale, rng):
    """Smooth 2D noise in 0..1 over shape, features about scale cells wide."""
    gx, gz = shape[0] // scale + 3, shape[1] // scale + 3
    grid = rng.random((gx, gz))
    xs = np.arange(shape[0]) / scale
    zs = np.arange(shape[1]) / scale
    x0 = np.floor(xs).astype(int)
    z0 = np.floor(zs).astype(int)
    fx = xs - x0
    fz = zs - z0
    fx = fx * fx * (3 - 2 * fx)
    fz = fz * fz * (3 - 2 * fz)
    a = grid[x0][:, z0]
    b = grid[x0 + 1][:, z0]
    c = grid[x0][:, z0 + 1]
    d = grid[x0 + 1][:, z0 + 1]
    return (a * (1 - fx)[:, None] + b * fx[:, None]) * (1 - fz)[None, :] + (c * (1 - fx)[:, None] + d * fx[:, None]) * fz[None, :]


def build_vale(seed=7, size=256):
    """The preview world: about size x size blocks around (0, 0), sea level 62."""
    rng = np.random.default_rng(seed)
    w = World(size, 72, size, 40)
    n1 = value_noise((size, size), 40, rng)
    n2 = value_noise((size, size), 14, rng)
    n3 = value_noise((size, size), 6, rng)
    height = 63 + (n1 - 0.4) * 26 + (n2 - 0.5) * 7 + (n3 - 0.5) * 2.0
    xs, zs = np.meshgrid(np.arange(size), np.arange(size), indexing="ij")
    # A stream winding through the vale into a pond, and a hill for the tower
    stream_z = size * 0.62 + np.sin(xs / 17.0) * 9.0
    d_stream = np.abs(zs - stream_z)
    height = np.where(d_stream < 9, np.minimum(height, 59 + d_stream * 0.6), height)
    pond = np.hypot(xs - size * 0.70, zs - size * 0.56)
    height = np.where(pond < 13, np.minimum(height, 57 + pond * 0.45), height)
    hill = np.hypot(xs - size * 0.32, zs - size * 0.30)
    height = height + np.maximum(0, 13 - hill) * 0.9
    # The cottage's flat yard
    yard = np.maximum(np.abs(xs - size * 0.52), np.abs(zs - size * 0.42))
    height = np.where(yard < 9, 66 + (height - 66) * np.clip((yard - 6) / 3, 0, 1), height)
    height = np.round(height).astype(int)
    sea = 62
    for x in range(size):
        for z in range(size):
            h = height[x, z]
            for y in range(w.y0, h - 3):
                w.set(x, y, z, "stone")
            near_water = h <= sea + 1 and (d_stream[x, z] < 11 or pond[x, z] < 15)
            for y in range(h - 3, h):
                w.set(x, y, z, "gravel" if near_water and h < sea else ("sand" if near_water else "dirt"))
            if h <= sea:
                w.set(x, h, z, "gravel" if (x * 7 + z * 3) % 5 else "sand")
                for y in range(h + 1, sea + 1):
                    w.set(x, y, z, "water")
            else:
                top = "grass_block"
                if hill[x, z] < 6 and rng.random() < 0.35:
                    top = "coarse_dirt"
                w.set(x, h, z, top)
                if top == "grass_block" and n3[x, z] > 0.62 and rng.random() < 0.3:
                    w.set(x, h, z, "stone")
                    w.set(x, h - 1, z, "stone")
    surf = height.copy()

    def ground(x, z):
        return int(surf[x, z])

    # Woods: oaks in the vale, spruces up the slopes
    trees = []
    for _ in range(int(140 * (size / 160.0) ** 2)):
        x, z = int(rng.integers(4, size - 4)), int(rng.integers(4, size - 4))
        h = ground(x, z)
        if h <= sea or yard[x, z] < 12 or hill[x, z] < 9 or d_stream[x, z] < 6:
            continue
        if any(abs(x - a) < 4 and abs(z - b) < 4 for a, b in trees):
            continue
        trees.append((x, z))
        if h > 70 or rng.random() < 0.3:
            th = int(rng.integers(7, 11))
            for y in range(h + 1, h + th):
                w.set(x, y, z, "spruce_log")
            for i, y in enumerate(range(h + 3, h + th + 2)):
                r = max(0, int((h + th + 2 - y) / 2.4))
                if i % 2 == 1:
                    r = max(0, r - 1)
                for dx in range(-r, r + 1):
                    for dz in range(-r, r + 1):
                        if abs(dx) + abs(dz) <= r + (1 if r > 1 else 0) and w.get(x + dx, y, z + dz) == "air":
                            w.set(x + dx, y, z + dz, "spruce_leaves")
            w.set(x, h + th + 1, z, "spruce_leaves")
        else:
            th = int(rng.integers(4, 7))
            for y in range(h + 1, h + th + 1):
                w.set(x, y, z, "oak_log")
            for y in range(h + th - 2, h + th + 2):
                r = 2 if y < h + th else 1
                for dx in range(-r, r + 1):
                    for dz in range(-r, r + 1):
                        if abs(dx) == r and abs(dz) == r and (y >= h + th or rng.random() < 0.6):
                            continue
                        if w.get(x + dx, y, z + dz) == "air":
                            w.set(x + dx, y, z + dz, "oak_leaves")
    # Plants
    for x in range(size):
        for z in range(size):
            h = ground(x, z)
            if w.get(x, h, z) != "grass_block" or w.get(x, h + 1, z) != "air":
                continue
            r = rng.random()
            if r < 0.22:
                w.set(x, h + 1, z, "short_grass")
            elif r < 0.25:
                w.set(x, h + 1, z, "fern")
            elif r < 0.257:
                w.set(x, h + 1, z, "poppy")
            elif r < 0.262:
                w.set(x, h + 1, z, "dandelion")
    # The ruined watchtower on the hill
    tx, tz = int(size * 0.32), int(size * 0.30)
    tb = ground(tx, tz)
    for y in range(tb - 2, tb + 15):
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                edge = max(abs(dx), abs(dz)) == 3
                if not edge:
                    if y <= tb:
                        w.set(tx + dx, y, tz + dz, "cobblestone")
                    elif w.get(tx + dx, y, tz + dz) != "air":
                        w.set(tx + dx, y, tz + dz, "air")
                    continue
                top = tb + 14 - (2 if dx > 0 and dz > 0 else 0) - (4 if dx == 3 and dz < 0 else 0)
                if y > top:
                    continue
                if dx == 0 and dz == -3 and tb < y < tb + 4:
                    continue  # the doorway
                if dz == 3 and abs(dx) == 1 and y in (tb + 7, tb + 8):
                    continue  # windows
                r = rng.random()
                block = "stone_bricks" if r < 0.6 else ("mossy_stone_bricks" if r < 0.85 else "cracked_stone_bricks")
                if y >= top and (dx + dz) % 2 == 0 and abs(dx) + abs(dz) > 3:
                    continue  # battlements
                w.set(tx + dx, y, tz + dz, block)
    w.set(tx, tb + 1, tz + 2, "torch")
    w.set(tx - 2, tb + 1, tz - 2, "torch")
    # A Wardfire at the tower's foot
    fx_, fz_ = tx + 6, tz - 3
    fb = ground(fx_, fz_)
    w.set(fx_, fb, fz_, "guildmark:wardfire_stone")
    w.set(fx_, fb + 1, fz_, "guildmark:wardfire")
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if w.get(fx_ + dx, fb + 1, fz_ + dz) in ("short_grass", "fern"):
            w.set(fx_ + dx, fb + 1, fz_ + dz, "air")
    # The cottage in its yard
    hx, hz = int(size * 0.52), int(size * 0.42)
    hb = 66
    for dx in range(-4, 5):
        for dz in range(-3, 4):
            for y in range(hb + 1, hb + 12):
                w.set(hx + dx, y, hz + dz, "air")
            w.set(hx + dx, hb, hz + dz, "cobblestone")
    for y in range(hb + 1, hb + 5):
        for dx in range(-4, 5):
            for dz in range(-3, 4):
                edge_x, edge_z = abs(dx) == 4, abs(dz) == 3
                if not (edge_x or edge_z):
                    continue
                if edge_x and edge_z:
                    w.set(hx + dx, y, hz + dz, "spruce_log")
                elif y == hb + 1:
                    w.set(hx + dx, y, hz + dz, "cobblestone")
                else:
                    w.set(hx + dx, y, hz + dz, "spruce_planks" if not edge_x else "oak_planks")
    for y in (hb + 1, hb + 2):
        w.set(hx, y, hz - 3, "air")  # door
    for dx in (-2, 2):
        w.set(hx + dx, hb + 3, hz - 3, "air")  # windows
        w.set(hx + dx, hb + 3, hz + 3, "air")
    for step in range(0, 5):
        y = hb + 5 + step
        for dx in range(-5, 6):
            for dz in (-4 + step, 4 - step):
                w.set(hx + dx, y, hz + dz, "dark_oak_planks")
        if step == 4:
            for dx in range(-5, 6):
                w.set(hx + dx, y, hz, "dark_oak_planks")
    for dz in range(-3, 4):
        for y in range(hb + 5, hb + 9 - abs(dz) + 1):
            for dx in (-4, 4):
                if w.get(hx + dx, y, hz + dz) == "air":
                    w.set(hx + dx, y, hz + dz, "spruce_planks")
    w.set(hx - 1, hb + 3, hz - 4, "torch")
    w.set(hx + 2, hb + 1, hz + 1, "lantern")
    # The path from the yard toward the stream, with lantern posts
    px, pz = hx, hz - 4
    for i in range(30):
        x, z = px + int(round(math.sin(i / 6.0) * 2)), pz - i
        if not (0 <= x < size and 0 <= z < size):
            break
        h = ground(x, z)
        if h <= sea:
            break
        for dx in (0, 1):
            if w.get(x + dx, h, z) == "grass_block":
                w.set(x + dx, h, z, "dirt_path")
                if w.get(x + dx, h + 1, z) in ("short_grass", "fern", "poppy", "dandelion"):
                    w.set(x + dx, h + 1, z, "air")
        if i % 9 == 4:
            w.set(x - 1, h + 1, z, "spruce_log")
            w.set(x - 1, h + 2, z, "spruce_log")
            w.set(x - 1, h + 3, z, "lantern")
    w.sea = sea
    w.surf = surf
    w.landmarks = {"tower": (tx, tb, tz), "cottage": (hx, hb, hz), "wardfire": (fx_, fb, fz_), "pond": (int(size * 0.70), sea, int(size * 0.56))}
    return w


def opaque(spec):
    return spec is not None and spec[3] == "solid" and spec[4] in ("cube",)


def light_fields(w):
    """Sky light and block light per block, 0..15, spread the way Minecraft spreads them (one less per step)."""
    sx, sy, sz = w.sx, w.sy, w.sz
    opaque_ids = np.zeros(len(w.names), dtype=bool)
    leaves_ids = np.zeros(len(w.names), dtype=bool)
    water_ids = np.zeros(len(w.names), dtype=bool)
    emit = np.zeros(len(w.names), dtype=np.int32)
    for i, n in enumerate(w.names):
        s = BLOCKS.get(n)
        opaque_ids[i] = opaque(s)
        leaves_ids[i] = n.endswith("leaves")
        water_ids[i] = n == "water"
        emit[i] = s[5] if s else 0
    solid = opaque_ids[w.b]
    dim = leaves_ids[w.b] | water_ids[w.b]
    sky = np.zeros((sx, sy, sz), dtype=np.int32)
    col = np.full((sx, sz), 15, dtype=np.int32)
    for y in range(sy - 1, -1, -1):
        col = np.where(solid[:, y, :], 0, np.where(dim[:, y, :], np.maximum(col - 1, 0), col))
        sky[:, y, :] = col
    block = np.where(solid, 0, emit[w.b]).astype(np.int32)
    for field in (sky, block):
        for _ in range(15):
            spread = field.copy()
            for axis in range(3):
                for shift in (1, -1):
                    rolled = np.roll(field, shift, axis=axis) - 1
                    if shift == 1:
                        idx = [slice(None)] * 3
                        idx[axis] = 0
                        rolled[tuple(idx)] = 0
                    else:
                        idx = [slice(None)] * 3
                        idx[axis] = -1
                        rolled[tuple(idx)] = 0
                    spread = np.maximum(spread, rolled)
            spread = np.where(solid, 0, spread)
            if np.array_equal(spread, field):
                break
            field[...] = spread
    return sky, block


def mesh_world(w, atlas, ids, area=None):
    """Meshes per layer: solid, cutout, translucent."""
    sky, block = light_fields(w)
    meshes = {"solid": Mesh(), "cutout": Mesh(), "translucent": Mesh()}
    names = w.names
    specs = [BLOCKS.get(n) for n in names]
    is_opaque = np.array([opaque(s) for s in specs])
    b = w.b
    y0 = w.y0

    def at(x, y, z):
        yy = y - y0
        if 0 <= x < w.sx and 0 <= yy < w.sy and 0 <= z < w.sz:
            return b[x, yy, z]
        return 0

    def lightat(x, y, z):
        yy = y - y0
        if 0 <= x < w.sx and 0 <= yy < w.sy and 0 <= z < w.sz:
            return sky[x, yy, z], block[x, yy, z]
        return (15, 0) if yy >= 0 else (0, 0)

    def tex_for(name, face):
        s = BLOCKS[name]
        t = s[0] if face == "up" else (s[2] if face == "down" else s[1])
        tint = s[6]
        if tint and not (name == "grass_block" and face == "down"):
            if name == "grass_block" and face != "up":
                t = atlas.tinted("grass_block_side", "grass")
            elif tint:
                t = atlas.tinted(t, tint)
        return t

    xs, ys, zs = np.nonzero(b)
    for x, yy, z in zip(xs.tolist(), ys.tolist(), zs.tolist()):
        y = yy + y0
        name = names[b[x, yy, z]]
        s = specs[b[x, yy, z]]
        if s is None:
            continue
        layer, model = s[3], s[4]
        ent = ids.get(name, 0)
        mesh = meshes[layer]
        if model in ("cube", "path"):
            top_h = 15.0 / 16.0 if model == "path" else 1.0
            for face, (n, corners) in FACES.items():
                nb = at(x + n[0], y + n[1], z + n[2])
                nbs = specs[nb]
                if is_opaque[nb] and not (model == "path" and face == "up"):
                    continue
                if name.endswith("leaves") is False and nbs is not None and nbs[4] == "path" and face == "up":
                    pass
                if nbs is not None and names[nb] == name and name.endswith("leaves") is False and s[3] != "solid":
                    continue
                t = tex_for(name, face)
                pts, cols, lights, uvs = [], [], [], []
                for ci, c in enumerate(corners):
                    px, py, pz = x + c[0], y + c[1] * top_h, z + c[2]
                    # The three blocks round this corner in front of the face, as Minecraft's smooth lighting reads them
                    ax = [i for i in range(3) if n[i] == 0]
                    o = [x + n[0], y + n[1], z + n[2]]
                    d1 = [0, 0, 0]
                    d2 = [0, 0, 0]
                    d1[ax[0]] = 1 if c[ax[0]] == 1 else -1
                    d2[ax[1]] = 1 if c[ax[1]] == 1 else -1
                    s1 = (o[0] + d1[0], o[1] + d1[1], o[2] + d1[2])
                    s2 = (o[0] + d2[0], o[1] + d2[1], o[2] + d2[2])
                    cc = (o[0] + d1[0] + d2[0], o[1] + d1[1] + d2[1], o[2] + d1[2] + d2[2])
                    o1, o2, o3 = is_opaque[at(*s1)], is_opaque[at(*s2)], is_opaque[at(*cc)]
                    if o1 and o2:
                        o3 = True
                    ao = (1.0 + (0.2 if o1 else 1.0) + (0.2 if o2 else 1.0) + (0.2 if o3 else 1.0)) / 4.0
                    samples = [lightat(*o)]
                    for p, occ in ((s1, o1), (s2, o2), (cc, o3)):
                        if not occ:
                            samples.append(lightat(*p))
                    sl = sum(v[0] for v in samples) / len(samples)
                    bl = sum(v[1] for v in samples) / len(samples)
                    pts.append((px, py, pz))
                    cols.append((ao, ao, ao, 1.0))
                    lights.append((bl * 16.0, sl * 16.0))
                    u, v = QUAD_UV[ci]
                    if face in ("up", "down"):
                        uvs.append(atlas.uv(t, u, v))
                    else:
                        vv = v if model != "path" or v == 0 else v
                        uvs.append(atlas.uv(t, u, 1 - (1 - vv) * (1.0 if c[1] == 0 or model != "path" else 1.0)))
                tangent = (1.0, 0.0, 0.0, 1.0) if face in ("up", "down", "south", "north") else (0.0, 0.0, 1.0, 1.0)
                mesh.quad(pts, cols, uvs, lights, n, ent, atlas.mid(t), tangent)
        elif model in ("cross", "flame"):
            t = tex_for(name, "north")
            sl, bl = lightat(x, y, z)
            light = [(bl * 16.0, sl * 16.0)] * 4
            col = [(1.0, 1.0, 1.0, 1.0)] * 4
            h = 1.0
            planes = [((0.15, 0.15), (0.85, 0.85)), ((0.85, 0.15), (0.15, 0.85))]
            for (ax_, az_), (bx_, bz_) in planes:
                for flip in (False, True):
                    p0, p1 = (x + ax_, z + az_), (x + bx_, z + bz_)
                    if flip:
                        p0, p1 = p1, p0
                    pts = [(p0[0], y, p0[1]), (p1[0], y, p1[1]), (p1[0], y + h, p1[1]), (p0[0], y + h, p0[1])]
                    nx, nz = (p1[1] - p0[1]), -(p1[0] - p0[0])
                    ln = math.hypot(nx, nz)
                    uvs = [atlas.uv(t, *QUAD_UV[i]) for i in range(4)]
                    mesh.quad(pts, col, uvs, light, (nx / ln, 0.0, nz / ln), ent, atlas.mid(t), (p1[0] - p0[0], 0.0, p1[1] - p0[1], 1.0))
        elif model in ("torch", "lantern"):
            t = tex_for(name, "north")
            sl, bl = lightat(x, y, z)
            light = [(bl * 16.0, sl * 16.0)] * 4
            if model == "torch":
                x0, x1, y1, z0, z1 = 7 / 16, 9 / 16, 10 / 16, 7 / 16, 9 / 16
                tu = (7 / 16, 9 / 16, 6 / 16, 1.0)
            else:
                x0, x1, y1, z0, z1 = 5 / 16, 11 / 16, 7 / 16, 5 / 16, 11 / 16
                tu = (0.0, 6 / 16, 2 / 16, 9 / 16)
            for face, (n, corners) in FACES.items():
                pts, uvs = [], []
                for ci, c in enumerate(corners):
                    pts.append((x + (x1 if c[0] else x0), y + (y1 if c[1] else 0.0), z + (z1 if c[2] else z0)))
                    u, v = QUAD_UV[ci]
                    uvs.append(atlas.uv(t, tu[0] + (tu[1] - tu[0]) * u, tu[2] + (tu[3] - tu[2]) * v if face not in ("up", "down") else tu[2] + (2 / 16) * v))
                mesh.quad(pts, [(1, 1, 1, 1)] * 4, uvs, light, n, ent, atlas.mid(t), (1.0, 0.0, 0.0, 1.0))
        elif model == "water":
            above = names[at(x, y + 1, z)]
            top_h = 1.0 if above == "water" else 14.0 / 16.0
            t = tex_for(name, "up")
            for face, (n, corners) in FACES.items():
                nb = names[at(x + n[0], y + n[1], z + n[2])]
                if nb == "water" or is_opaque[at(x + n[0], y + n[1], z + n[2])]:
                    continue
                if face == "down":
                    continue
                sl, bl = lightat(x + n[0], y + n[1], z + n[2]) if face != "up" else lightat(x, y + 1, z)
                pts = [(x + c[0], y + c[1] * top_h, z + c[2]) for c in corners]
                uvs = [atlas.uv(t, *QUAD_UV[i]) for i in range(4)]
                mesh.quad(pts, [(1, 1, 1, 1)] * 4, uvs, [(bl * 16.0, sl * 16.0)] * 4, n, ent, atlas.mid(t), (1.0, 0.0, 0.0, 1.0))
    for m in meshes.values():
        m.finish()
    return meshes


class Scene:
    def __init__(self, ctx, mcjar, repo_assets, block_properties, seed=7, size=256):
        self.world = build_vale(seed, size)
        self.atlas = Atlas(mcjar, repo_assets)
        self.ids = read_block_ids(block_properties)
        self.meshes = mesh_world(self.world, self.atlas, self.ids)
        self.textures = {}
        self.ctx = ctx
        self.atlas_tex = self.atlas.texture(ctx)
        lm = np.zeros((16, 16, 4), dtype=np.uint8)
        for s in range(16):
            for bl in range(16):
                v = max(s / 15.0, bl / 15.0 * np.array([1.0, 0.85, 0.6]).max())
                c = np.array([v, v, v]) * 255
                lm[s, bl] = (c[0], c[1], c[2], 255)
        self.lightmap = ctx.texture((16, 16), 4, lm.tobytes())
        flat = np.zeros((4, 4, 4), dtype=np.uint8)
        flat[...] = (128, 128, 255, 255)
        self.normals = ctx.texture((4, 4), 4, flat.tobytes())
        self.specular = ctx.texture((4, 4), 4, np.zeros((4, 4, 4), dtype=np.uint8).tobytes())
        self.meshes["weather"] = None
        self.meshes["particles"] = None
        self.meshes["entities"] = None
        rain = Image.open(io.BytesIO(self.atlas.jar.read("assets/minecraft/textures/environment/rain.png"))).convert("RGBA")
        self.rain_tex = ctx.texture(rain.size, 4, rain.tobytes())
        self.rain_tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        # Meshes drawn with a texture of their own instead of the block atlas
        self.mesh_textures = {"weather": {"gtexture": self.rain_tex, "texture": self.rain_tex, "tex": self.rain_tex}}

    def gbuffer_textures(self):
        return {"gtexture": self.atlas_tex, "texture": self.atlas_tex, "tex": self.atlas_tex,
                "lightmap": self.lightmap, "normals": self.normals, "specular": self.specular}

    def set_camera(self, cam):
        for m in self.meshes.values():
            if m is not None:
                m.set_camera(self.ctx, cam)

    def rain_mesh(self, cam, strength, time_s, rng_seed=3):
        """
        Rain the way Minecraft draws it: one card per column round the camera, turned to face it, with the rain
        texture repeating every four blocks up the card (drawn with its own texture, see mesh_textures).
        """
        m = Mesh()
        rng = np.random.default_rng(rng_seed)
        if strength <= 0:
            self.meshes["weather"] = None
            return
        cx, cy, cz = cam
        for dx in range(-10, 11):
            for dz in range(-10, 11):
                if dx * dx + dz * dz > 100:
                    continue
                x, z = int(math.floor(cx)) + dx, int(math.floor(cz)) + dz
                ground = cy - 8
                if 0 <= x < self.world.sx and 0 <= z < self.world.sz:
                    ground = self.world.surf[x, z] + 1
                    if self.world.get(x, int(ground), z) == "water":
                        ground = self.world.sea + 1
                top = cy + 10
                if top <= ground:
                    continue
                mx, mz = x + 0.5, z + 0.5
                ax, az = mx - cx, mz - cz
                d = math.hypot(ax, az) or 1.0
                px, pz = -az / d * 0.5, ax / d * 0.5
                pts = [(mx - px, ground, mz - pz), (mx + px, ground, mz + pz), (mx + px, top, mz + pz), (mx - px, top, mz - pz)]
                off = rng.random() + time_s * 1.7
                v0, v1 = -ground * 0.25 + off, -top * 0.25 + off
                uvs = [(0.0, v0), (1.0, v0), (1.0, v1), (0.0, v1)]
                m.quad(pts, [(1, 1, 1, strength)] * 4, uvs, [(0.0, 240.0)] * 4, (0, 1, 0), 0, (0, 0), (1, 0, 0, 1))
        m.finish()
        m.set_camera(self.ctx, cam)
        self.meshes["weather"] = m

