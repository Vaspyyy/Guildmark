"""Townsfolk particle sprites: the sparks off a smith's hammer (forge_spark_0..3).

Usage: python3 -B art/scripts/townsfolk_particles.py src/main/resources/assets/guildmark

Each frame is an 8x8 white-hot point with a soft halo, smaller frame by frame as the spark dies (the particle
tints it from white through gold to red as it cools, so the sprite stays near white).
"""
import json
import os
import sys

from PIL import Image

FRAMES = [
    # (core radius, halo radius, halo strength): a fat spark first, then smaller and fainter
    (1.8, 3.6, 0.55),
    (1.4, 3.0, 0.45),
    (1.0, 2.4, 0.35),
    (0.75, 1.8, 0.25),
]


def spark(core, halo, strength, size=8):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            d = ((x - c) ** 2 + (y - c) ** 2) ** 0.5
            if d <= core:
                a = 1.0
            elif d <= halo:
                t = (d - core) / (halo - core)
                a = strength * (1.0 - t) ** 2
            else:
                continue
            # The core is pure white; the halo leans a little warm so it reads as heat once tinted
            warm = 0 if d <= core else int(40 * min(1.0, (d - core) / (halo - core)))
            px[x, y] = (255, 255 - warm // 2, 255 - warm, int(round(255 * a)))
    return img


def main():
    assets = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/guildmark"
    tex = os.path.join(assets, "textures/particle")
    os.makedirs(tex, exist_ok=True)
    os.makedirs(os.path.join(assets, "particles"), exist_ok=True)
    names = []
    for i, (core, halo, strength) in enumerate(FRAMES):
        spark(core, halo, strength).save(os.path.join(tex, f"forge_spark_{i}.png"))
        names.append(f"guildmark:forge_spark_{i}")
    with open(os.path.join(assets, "particles/forge_spark.json"), "w") as f:
        json.dump({"textures": names}, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
