"""
Pictures of Guildmark's shader pack without the game: the preview vale (scene.py) through the pack's programs
(iris_lite.py) at set times of day and weather.

    python3 -I art/shaderkit/preview.py --pack src/main/resources/shaderpack/Guildmark/shaders \\
        --mcjar <minecraft client jar> --assets src/main/resources/assets/guildmark --out <folder> \\
        [--size 960x540] [--only noon,dusk] [-D OPTION=value ...] [--sheet]

Each shot is written as <name>.png; --sheet also puts them side by side in sheet.png.
"""

import argparse
import math
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import moderngl  # noqa: E402
import numpy as np  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

import iris_lite  # noqa: E402
import scene as scenekit  # noqa: E402


def look_at(pos, target):
    """Minecraft yaw and pitch from pos toward target (yaw 0 faces +Z, 90 faces -X; pitch down is positive)."""
    dx, dy, dz = (target[i] - pos[i] for i in range(3))
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
    return yaw, pitch


# The fog colours Minecraft hands the Nether (nether wastes) and the End, which the pack takes its haze from
WORLD_FOG = {
    "world-1": {"fog_color": (0.200, 0.031, 0.031), "sky_color": (0.0, 0.0, 0.0), "dimension": "nether"},
    "world1": {"fog_color": (0.094, 0.075, 0.094), "sky_color": (0.0, 0.0, 0.0), "dimension": "end"},
}


def standing(world, pos, clearance=1.7):
    """pos lifted clear of the ground and anything solid around it (shots are placed by landmark, not by hand)."""
    x, y, z = pos
    ix, iz = int(math.floor(x)), int(math.floor(z))
    top = -999
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            cx, cz = ix + dx, iz + dz
            if 0 <= cx < world.sx and 0 <= cz < world.sz:
                for yy in range(world.y0 + world.sy - 1, world.y0 - 1, -1):
                    n = world.get(cx, yy, cz)
                    if n not in ("air", "short_grass", "fern", "poppy", "dandelion", "water"):
                        top = max(top, yy + 1)
                        break
    return (x, max(y, top + clearance), z)


def shots(world):
    tx, tb, tz = world.landmarks["tower"]
    hx, hb, hz = world.landmarks["cottage"]
    fx, fb, fz = world.landmarks["wardfire"]
    px, ps, pz = world.landmarks["pond"]
    out = {}

    def cam(pos, target, fov=70.0, lift=0.0):
        pos = standing(world, pos)
        pos = (pos[0], pos[1] + lift, pos[2])
        yaw, pitch = look_at(pos, target)
        return {"pos": pos, "yaw": yaw, "pitch": pitch, "fov": fov}

    # Over the yard toward the tower on its hill, the sun high behind the camera's left shoulder
    out["noon"] = dict(camera=cam((hx + 22.5, hb, hz + 14.5), (tx + 4, tb + 6, tz + 2), lift=4.0), time=6000)
    # The tower against a low western sun, its battlements cutting the light into shafts
    out["golden"] = dict(camera=cam((tx + 34.5, tb, tz + 9.5), (tx, tb + 9, tz), lift=1.5), time=11300)
    # Sundown across the pond
    out["dusk"] = dict(camera=cam((px + 15.5, ps, pz + 2.5), (px - 40, ps + 4, pz - 6), lift=1.0), time=12650)
    # The cottage by its own lights, under the moon
    out["night"] = dict(camera=cam((hx - 5.5, hb, hz - 11.5), (hx, hb + 3, hz - 3)), time=18000, moon=0)
    # The yard in a downpour
    out["rain"] = dict(camera=cam((hx - 13.5, hb, hz - 10.5), (hx, hb + 3, hz)), time=8500, rain=1.0)
    # From the tower's battlements east over the misty vale at sunrise
    out["dawn"] = dict(camera=cam((tx + 2.5, tb + 15.1, tz + 1.5), (px + 10, ps, pz + 4), lift=0.0), time=23400)
    # The Wardfire at the tower's foot after dark
    out["wardfire"] = dict(camera=cam((fx + 4.5, fb, fz - 4.5), (fx + 0.5, fb + 1.0, fz + 0.5), lift=0.2), time=15000)
    # A morning sky over the tower: the cloud deck, lit from the east
    out["clouds"] = dict(camera=cam((tx + 26.5, tb, tz + 30.5), (tx - 10, tb + 40, tz - 20), lift=2.0), time=2500)
    # Under the pond, looking along the bottom toward the shore
    out["underwater"] = dict(camera=cam((px + 0.5, ps - 2.6, pz + 0.5), (px - 12, ps - 3.5, pz + 4), lift=0.0),
                             time=5000, water=1)
    # Late sun through the woods in the vale's south-east corner
    wx, wz = int(world.sx * 0.83), int(world.sz * 0.79)
    out["woods"] = dict(camera=cam((wx + 0.5, 0, wz + 0.5), (wx - 30, world.surf[wx, wz] + 11, wz - 2)), time=10800)
    return out


def timelapse(world):
    """One day over the vale from the tower's battlements, looking south: the sun rises on the left, sets on the right."""
    tx, tb, tz = world.landmarks["tower"]
    pos = standing(world, (tx + 1.5, tb + 15.1, tz - 1.5))
    yaw, pitch = look_at(pos, (tx + 6, tb + 4, tz + 60))
    camera = {"pos": pos, "yaw": yaw, "pitch": pitch - 6.0, "fov": 75.0}
    times = [22300, 22700, 23000, 23300, 23600, 23900, 300, 900, 1700, 2700, 3800, 5000, 6200, 7400, 8500, 9500, 10300,
             10900, 11400, 11800, 12100, 12400, 12650, 12900, 13150, 13450, 13800, 14400, 15500, 17000, 18000, 19500]
    out = {}
    for i, t in enumerate(times):
        out["%03d" % i] = dict(camera=camera, time=t, seconds=37.0 + i * 9.0, frame=i)
    return out


def eye_light(world, sky_field, pos):
    x, y, z = (int(math.floor(c)) for c in pos)
    yy = y - world.y0
    if 0 <= x < world.sx and 0 <= yy < world.sy and 0 <= z < world.sz:
        return int(sky_field[x, yy, z]) * 16
    return 240


def dump(pipe, out, state, name):
    """Each buffer after pass name: its range printed, its picture saved as <shot>-<n>-<pass>-c<i>.png."""
    state["n"] += 1
    print("after %s:" % name)
    for i in range(6):
        a = pipe.read(i)
        rgb = a[..., :3] if a.shape[2] >= 3 else np.repeat(a[..., :1], 3, axis=2)
        print("  colortex%d %-8s min %s  mean %s  max %s" % (
            i, pipe.buffers[i].fmt, np.round(a.reshape(-1, a.shape[2]).min(0), 4),
            np.round(a.reshape(-1, a.shape[2]).mean(0), 4), np.round(a.reshape(-1, a.shape[2]).max(0), 4)))
        img = rgb / (1.0 + rgb) if pipe.buffers[i].fmt.endswith("F") else rgb
        img = (np.clip(np.nan_to_num(img), 0, 1) ** (1 / 2.2 if pipe.buffers[i].fmt.endswith("F") else 1.0) * 255).astype("u1")
        Image.fromarray(img).save(os.path.join(out, "%s-%02d-%s-c%d.png" % (state["shot"], state["n"], name, i)))
    if state["n"] == 1:
        res = pipe.shadow_res
        pipe.shadow0.compare_func = ""
        d = np.frombuffer(pipe.shadow0.read(), dtype="f4").reshape(res, res)[::-1]
        print("  shadowtex0 min %.4f mean %.4f max %.4f" % (d.min(), d.mean(), d.max()))
        Image.fromarray((np.clip(d, 0, 1) * 255).astype("u1")).resize((512, 512)).save(
            os.path.join(out, "%s-00-shadowtex0.png" % state["shot"]))


def probe(pipe, points, name):
    if name.startswith("gbuffers_terrain") or name == "gbuffers_entities":
        return
    w, h = pipe.size
    c0 = pipe.read(0)
    c3 = pipe.read(3)
    line = []
    for x, y in points:
        px, py = min(int(x * w), w - 1), min(int(y * h), h - 1)
        line.append("(%.2f,%.2f) c0 %s c3 %s" % (x, y, np.round(c0[py, px, :3], 3), np.round(c3[py, px], 3)))
    print("%-16s %s" % (name, "  ".join(line)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--mcjar", required=True)
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--size", default="960x540")
    ap.add_argument("--only", default="")
    ap.add_argument("--world", default="")
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("-D", action="append", default=[])
    ap.add_argument("-v", action="store_true")
    ap.add_argument("--timelapse", action="store_true",
                    help="instead of the shots, a day over the vale from the tower: timelapse/NNN.png and timelapse.gif")
    ap.add_argument("--dump", action="store_true", help="after every pass, print what each buffer holds and save it")
    ap.add_argument("--probe", action="append", default=[],
                    help="x,y in 0..1 from the top left: print colortex0 and colortex3 there after every pass")
    a = ap.parse_args()
    w, h = (int(v) for v in a.size.split("x"))
    overrides = {}
    for d in a.D:
        k, _, v = d.partition("=")
        overrides[k] = True if v == "" or v == "true" else (False if v == "false" else v)
    os.makedirs(a.out, exist_ok=True)
    ctx = moderngl.create_standalone_context(backend="egl", require=450)
    t0 = time.time()
    sc = scenekit.Scene(ctx, a.mcjar, a.assets, os.path.join(a.pack, "block.properties"))
    sky_field, _ = scenekit.light_fields(sc.world)
    print("scene ready in %.1fs (%d solid, %d cutout, %d translucent vertices)" % (
        time.time() - t0, sc.meshes["solid"].count, sc.meshes["cutout"].count, sc.meshes["translucent"].count))
    pipe = iris_lite.Pipeline(ctx, a.pack, (w, h), world=a.world, option_overrides=overrides, verbose=a.v)
    consts = pipe.consts
    dump_state = {}
    probes = [tuple(float(v) for v in p.split(",")) for p in a.probe]
    if a.dump:
        pipe.on_pass = lambda name: dump(pipe, a.out, dump_state, name)
    elif probes:
        pipe.on_pass = lambda name: probe(pipe, probes, name)
    wanted = [s for s in a.only.split(",") if s]
    images = []
    plan = timelapse(sc.world) if a.timelapse else shots(sc.world)
    if a.timelapse:
        os.makedirs(os.path.join(a.out, "timelapse"), exist_ok=True)
    for name, shot in plan.items():
        if wanted and name not in wanted:
            continue
        t1 = time.time()
        camera = shot["camera"]
        sc.set_camera(camera["pos"])
        rain = shot.get("rain", 0.0)
        sc.rain_mesh(camera["pos"], rain, 1.0)
        u = iris_lite.frame_uniforms(
            camera, shot["time"], (w, h), far=float(shot.get("far", 192.0)), rain=rain, thunder=shot.get("thunder", 0.0),
            frame_counter=int(shot.get("frame", 7)), time_s=float(shot.get("seconds", 37.0)),
            sun_path_rotation=consts.get("sunPathRotation", 0.0), shadow_distance=consts.get("shadowDistance", 128.0),
            shadow_interval=consts.get("shadowIntervalSize", 2.0),
            eye_brightness=(0, eye_light(sc.world, sky_field, camera["pos"])), held_light=shot.get("held", 0),
            moon_phase=shot.get("moon", 0), eye_in_water=shot.get("water", 0), **WORLD_FOG.get(a.world, {}))
        dump_state.update(shot=name, n=0)
        img = pipe.render(sc, u)
        Image.fromarray(img).save(os.path.join(a.out, ("timelapse/" if a.timelapse else "") + name + ".png"))
        images.append((name, img))
        print("%-9s %.1fs" % (name, time.time() - t1))
    if a.timelapse and images:
        frames = [Image.fromarray(img).resize((w // 2, h // 2), Image.LANCZOS) for _, img in images]
        frames = [f.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG) for f in frames]
        frames[0].save(os.path.join(a.out, "timelapse.gif"), save_all=True, append_images=frames[1:], duration=260, loop=0,
                       optimize=True)
    if a.sheet and images:
        cols = 2
        rows = (len(images) + cols - 1) // cols
        sw, sh = w // 2, h // 2
        sheet = Image.new("RGB", (cols * sw, rows * sh), (0, 0, 0))
        draw = ImageDraw.Draw(sheet)
        for i, (name, img) in enumerate(images):
            x, y = (i % cols) * sw, (i // cols) * sh
            sheet.paste(Image.fromarray(img).resize((sw, sh), Image.LANCZOS), (x, y))
            draw.text((x + 8, y + 6), name, fill=(255, 255, 255))
        sheet.save(os.path.join(a.out, "sheet.png"))


if __name__ == "__main__":
    main()
