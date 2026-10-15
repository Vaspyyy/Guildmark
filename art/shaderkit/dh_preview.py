"""
The pack with Distant Horizons: a stony clifftop over the sea, the real chunks out to the render distance and DH's far
land and water past it, drawn the way Iris has DH draw (see iris_lite.Pipeline.render). It is the place Ransom found
the far sea painted over the clifftop (2026-10-15), so a fix for anything DH does can be checked here.

    python3 -I art/shaderkit/dh_preview.py --pack src/main/resources/shaderpack/Guildmark/shaders \\
        --mcjar <minecraft client jar> --assets src/main/resources/assets/guildmark --out <folder> \\
        [--before <an older copy of the shaders folder>] [--size 960x540] [-D OPTION=value ...]

Two shots: clifftop (standing on the shelf, the sea hidden below its edge) and seaview (at the cliff's edge, the
real chunks' sea running out into DH's). Writes <shot>.png, and with --before also <shot>_before.png and
<shot>_compare.png (old above, new below).
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import moderngl  # noqa: E402
import numpy as np  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

import iris_lite  # noqa: E402
import scene as scenekit  # noqa: E402

SIZE = 256          # the real chunks: a square this wide round the camera, cut to the render distance
FAR = 128.0         # render distance 8, as in Ransom's dev run
DH_REACH = 1024     # how far DH's land goes each way
DH_CELL = 8         # DH's detail out there: one column per 8 x 8 blocks
SEA = 62


def heights(xs, zs, rng_noise):
    """Ground height at world x, z: a stony shelf at about 100 whose cliff runs south-west into the sea, turning north."""
    # The coast: x of the cliff's top edge for each z. South of the camera it runs north-south a little west of it;
    # further north it swings away west, so looking west there's sea on the left and land on the right
    coast = np.where(zs >= 120, 104 + 5 * np.sin(zs / 19.0), 104 - (120 - zs) * 2.2)
    shelf = 99 + np.floor(rng_noise(xs, zs) * 3.2)
    past = np.maximum(coast - xs, 0.0)
    cliff = shelf - past * 3.2 + np.where(past > 6, 3.0, 0.0)
    return np.maximum(np.where(xs >= coast, shelf, cliff), 50.0)


def noise_fn(seed):
    rng = np.random.default_rng(seed)
    grid = rng.random((512, 512))

    def n(xs, zs):
        x = np.asarray(xs, dtype=float) / 7.0 + 256
        z = np.asarray(zs, dtype=float) / 7.0 + 256
        x0 = np.floor(x).astype(int) % 511
        z0 = np.floor(z).astype(int) % 511
        fx = x - np.floor(x)
        fz = z - np.floor(z)
        fx = fx * fx * (3 - 2 * fx)
        fz = fz * fz * (3 - 2 * fz)
        a, b = grid[x0, z0], grid[x0 + 1, z0]
        c, d = grid[x0, z0 + 1], grid[x0 + 1, z0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz

    return n


def build_world(cam_xz):
    n = noise_fn(11)
    w = scenekit.World(SIZE, 64, SIZE, 44)
    xs, zs = np.meshgrid(np.arange(SIZE), np.arange(SIZE), indexing="ij")
    h = np.round(heights(xs, zs, n)).astype(int)
    for x in range(SIZE):
        for z in range(SIZE):
            if math.hypot(x + 0.5 - cam_xz[0], z + 0.5 - cam_xz[1]) > FAR:
                continue
            top = h[x, z]
            for y in range(w.y0, top + 1):
                w.set(x, y, z, "stone")
            if top < SEA:
                w.set(x, top, z, "gravel")
                for y in range(top + 1, SEA + 1):
                    w.set(x, y, z, "water")
            elif (x * 7 + z * 13) % 23 == 0:
                w.set(x, top, z, "gravel")
    w.surf = h
    w.sea = SEA
    return w, n


def dh_meshes(center, n):
    """DH's land and water round center, everywhere (inside the render distance too, as DH has it)."""
    solid, water = scenekit.DhMesh(), scenekit.DhMesh()
    cx, cz = (int(c) // DH_CELL * DH_CELL for c in center)
    cells = np.arange(-DH_REACH, DH_REACH, DH_CELL)
    gx, gz = np.meshgrid(cells + cx, cells + cz, indexing="ij")
    # Each column's height: the highest ground in it, as DH keeps it
    h = np.full(gx.shape, -1e9)
    for ox in range(0, DH_CELL, 2):
        for oz in range(0, DH_CELL, 2):
            h = np.maximum(h, np.round(heights(gx + ox, gz + oz, n)))
    h = h.astype(int)
    stone = (0.42, 0.42, 0.42, 1.0)
    grass = (0.33, 0.45, 0.22, 1.0)
    for i in range(h.shape[0]):
        for j in range(h.shape[1]):
            x0, z0 = float(gx[i, j]), float(gz[i, j])
            x1, z1 = x0 + DH_CELL, z0 + DH_CELL
            top = h[i, j] + 1.0
            far_land = math.hypot(x0 - cx, z0 - cz) > 420 and top > SEA + 2
            col = grass if far_land else stone
            mat = 13 if far_land else 2
            solid.flat([(x0, top, z1), (x1, top, z1), (x1, top, z0), (x0, top, z0)], col, (0, 1, 0), 15, mat)
            if top <= SEA + 1:
                ws = SEA + 1.0
                water.flat([(x0, ws, z1), (x1, ws, z1), (x1, ws, z0), (x0, ws, z0)], (0.20, 0.32, 0.62, 0.75), (0, 1, 0), 15, 12)
            # Walls down to lower neighbours
            for di, dj, normal in ((1, 0, (1, 0, 0)), (-1, 0, (-1, 0, 0)), (0, 1, (0, 0, 1)), (0, -1, (0, 0, -1))):
                ni, nj = i + di, j + dj
                if not (0 <= ni < h.shape[0] and 0 <= nj < h.shape[1]):
                    continue
                low = h[ni, nj] + 1.0
                if low >= top:
                    continue
                if di == 1:
                    q = [(x1, low, z1), (x1, low, z0), (x1, top, z0), (x1, top, z1)]
                elif di == -1:
                    q = [(x0, low, z0), (x0, low, z1), (x0, top, z1), (x0, top, z0)]
                elif dj == 1:
                    q = [(x0, low, z1), (x1, low, z1), (x1, top, z1), (x0, top, z1)]
                else:
                    q = [(x1, low, z0), (x0, low, z0), (x0, top, z0), (x1, top, z0)]
                solid.flat(q, col, normal, 15, mat)
    solid.finish()
    water.finish()
    return {"solid": solid, "water": water}


def render(ctx, pack, sc, dh, camera, size, overrides):
    pipe = iris_lite.Pipeline(ctx, pack, size, option_overrides=overrides, extra_defines={"DISTANT_HORIZONS": None})
    consts = pipe.consts
    u = iris_lite.frame_uniforms(
        camera, 2300, size, far=FAR, frame_counter=7, time_s=37.0,
        sun_path_rotation=consts.get("sunPathRotation", 0.0), shadow_distance=consts.get("shadowDistance", 128.0),
        shadow_interval=consts.get("shadowIntervalSize", 2.0), eye_brightness=(0, 240))
    return pipe.render(sc, u, dh=dh)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--before", default="")
    ap.add_argument("--mcjar", required=True)
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", default="960x540")
    ap.add_argument("-D", action="append", default=[])
    a = ap.parse_args()
    w, h = (int(v) for v in a.size.split("x"))
    overrides = {}
    for d in a.D:
        k, _, v = d.partition("=")
        overrides[k] = True if v == "" or v == "true" else (False if v == "false" else v)
    os.makedirs(a.out, exist_ok=True)
    ctx = moderngl.create_standalone_context(backend="egl", require=450)
    cam_xz = (128.5, 128.5)
    world, n = build_world(cam_xz)
    sc = scenekit.Scene(ctx, a.mcjar, a.assets, os.path.join(a.pack, "block.properties"), world=world)
    dh = dh_meshes(cam_xz, n)

    def standing(x, z):
        return (x, world.surf[int(x), int(z)] + 1 + 1.62, z)

    # Ransom's view: west-south-west and a little down, the morning sun behind; and out to sea from the cliff's edge
    shots = {
        "clifftop": {"pos": standing(*cam_xz), "yaw": 66.0, "pitch": 18.0, "fov": 90.0},
        "seaview": {"pos": standing(110.5, 150.5), "yaw": 80.0, "pitch": 6.0, "fov": 80.0},
    }
    for name, camera in shots.items():
        sc.set_camera(camera["pos"])
        for m in dh.values():
            m.set_camera(ctx, camera["pos"])
        after = render(ctx, a.pack, sc, dh, camera, (w, h), overrides)
        Image.fromarray(after).save(os.path.join(a.out, name + ".png"))
        if not a.before:
            continue
        before = render(ctx, a.before, sc, dh, camera, (w, h), overrides)
        Image.fromarray(before).save(os.path.join(a.out, name + "_before.png"))
        sheet = Image.new("RGB", (w, h * 2), (0, 0, 0))
        sheet.paste(Image.fromarray(before), (0, 0))
        sheet.paste(Image.fromarray(after), (0, h))
        draw = ImageDraw.Draw(sheet)
        for y, label in ((0, "before"), (h, "after")):
            draw.rectangle((8, y + 8, 92, y + 30), fill=(0, 0, 0))
            draw.text((16, y + 12), label, fill=(255, 255, 255))
        sheet.save(os.path.join(a.out, name + "_compare.png"))

if __name__ == "__main__":
    main()
