"""The mist gate that closes a dungeon's boss hall: a slow, pale veil of fog drifting upward, thin enough
to make out shapes through. Animated, and it tiles both ways, so a doorway of gate blocks reads as one wall.

    python3 art/scripts/dungeon_art.py src/main/resources/assets/guildmark [preview.png]

Writes the animated block texture and its .mcmeta, the block model, the blockstate and an optional preview
strip of every frame. All randomness is seeded, so a rerun gives the same files.
"""
import json
import os
import random
import sys

import numpy as np
from PIL import Image

SIZE = 16
FRAMES = 32
FRAME_TIME = 2


def tile_noise(size, cx, cy, seed):
    """Smooth value noise in 0..1 on a size x size torus, with cells cx wide and cy tall (each dividing size)."""
    rng = random.Random(seed)
    gx, gy = size // cx, size // cy
    grid = np.array([[rng.random() for _ in range(gx)] for _ in range(gy)])
    out = np.zeros((size, size))
    for y in range(size):
        for x in range(size):
            fx, fy = x / cx, y / cy
            x0, y0 = int(fx) % gx, int(fy) % gy
            x1, y1 = (x0 + 1) % gx, (y0 + 1) % gy
            tx, ty = fx - int(fx), fy - int(fy)
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = grid[y0, x0] * (1 - tx) + grid[y0, x1] * tx
            b = grid[y1, x0] * (1 - tx) + grid[y1, x1] * tx
            out[y, x] = a * (1 - ty) + b * ty
    return out


def shifted(field, dx, dy):
    """The field moved by a fraction of a pixel, wrapping round: bilinear, so the drift is smooth."""
    size = field.shape[0]
    ys, xs = np.mgrid[0:size, 0:size].astype(float)
    sx, sy = (xs - dx) % size, (ys - dy) % size
    x0, y0 = np.floor(sx).astype(int), np.floor(sy).astype(int)
    x1, y1 = (x0 + 1) % size, (y0 + 1) % size
    tx, ty = sx - x0, sy - y0
    a = field[y0, x0] * (1 - tx) + field[y0, x1] * tx
    b = field[y1, x0] * (1 - tx) + field[y1, x1] * tx
    return a * (1 - ty) + b * ty


def frames():
    big = tile_noise(SIZE, 8, 8, 11)
    # Tall, thin streaks: the fog rising in strands
    streak = tile_noise(SIZE, 4, 8, 12)
    drift = tile_noise(SIZE, 8, 4, 13)
    out = []
    for f in range(FRAMES):
        t = f / FRAMES
        # Each layer drifts a whole number of tiles per loop, so the last frame meets the first
        a = shifted(big, 0, -t * SIZE)
        b = shifted(streak, 0, -2 * t * SIZE)
        c = shifted(drift, t * SIZE, -t * SIZE)
        fog = 0.45 * a + 0.35 * b + 0.2 * c
        light = np.clip(0.6 + 0.8 * (fog - 0.5), 0, 1)
        r = 168 + 80 * light
        g = 172 + 78 * light
        bl = 186 + 66 * light
        alpha = np.clip(110 + 170 * (fog - 0.4), 85, 205)
        img = np.stack([r, g, bl, alpha], axis=-1).clip(0, 255).astype(np.uint8)
        out.append(img)
    return out


def main():
    assets = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    imgs = frames()
    strip = np.concatenate(imgs, axis=0)
    tex_dir = os.path.join(assets, "textures", "block")
    os.makedirs(tex_dir, exist_ok=True)
    Image.fromarray(strip, "RGBA").save(os.path.join(tex_dir, "mist_gate.png"))
    with open(os.path.join(tex_dir, "mist_gate.png.mcmeta"), "w") as f:
        json.dump({"animation": {"frametime": FRAME_TIME, "interpolate": True}}, f, indent=2)
        f.write("\n")

    model = {"parent": "minecraft:block/cube_all", "textures": {"all": "guildmark:block/mist_gate"}}
    with open(os.path.join(assets, "models", "block", "mist_gate.json"), "w") as f:
        json.dump(model, f, indent=2)
        f.write("\n")
    state = {"variants": {f"facing={d}": {"model": "guildmark:block/mist_gate"} for d in ("north", "south", "east", "west")}}
    with open(os.path.join(assets, "blockstates", "mist_gate.json"), "w") as f:
        json.dump(state, f, indent=2)
        f.write("\n")

    if preview:
        # Every frame side by side at 8x, over a dark checker so the see-through shows
        scale = 8
        w = SIZE * scale
        sheet = Image.new("RGBA", (w * 8, w * (FRAMES // 8)), (0, 0, 0, 255))
        checker = np.indices((w, w)).sum(axis=0) // 16 % 2
        back = np.where(checker[..., None] == 1, [40, 36, 48, 255], [24, 22, 30, 255]).astype(np.uint8)
        for i, img in enumerate(imgs):
            fr = Image.fromarray(img, "RGBA").resize((w, w), Image.NEAREST)
            tile = Image.fromarray(back, "RGBA")
            tile.alpha_composite(fr)
            sheet.paste(tile, ((i % 8) * w, (i // 8) * w))
        sheet.save(preview)


if __name__ == "__main__":
    main()
