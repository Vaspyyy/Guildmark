"""The lighthouse lamp's flare: the glare of its lens when a beam swings round to face you, and the steady glow of the
lamp seen from far out at sea. A warm white core in a soft halo, drawn out sideways into a streak, with six faint rays.
White and warm only: the renderer tints and fades it.

    python3 art/scripts/island_lamp_flare.py

writes src/main/resources/assets/guildmark/textures/entity/island/lamp_flare.png (64x64).
"""
import math
import os

from PIL import Image

SIZE = 64
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "guildmark", "textures", "entity", "island", "lamp_flare.png")


def flare():
    img = Image.new("RGBA", (SIZE, SIZE))
    px = img.load()
    c = (SIZE - 1) / 2
    for y in range(SIZE):
        for x in range(SIZE):
            dx = (x - c) / c
            dy = (y - c) / c
            r = math.hypot(dx, dy)
            core = math.exp(-(r / 0.13) ** 2)
            halo = math.exp(-(r / 0.42) ** 2) * 0.55
            streak = math.exp(-(dy / 0.05) ** 2) * math.exp(-(abs(dx) / 0.75) ** 1.6) * 0.6
            a = math.atan2(dy, dx)
            rays = (abs(math.cos(a * 3)) ** 24) * math.exp(-(r / 0.6) ** 2) * 0.35
            edge = max(0.0, 1 - max(0.0, r - 0.82) / 0.18)  # nothing reaches the square's edge
            v = min(1.0, core + halo + streak + rays) * edge
            # White at the heart, warming to lamp-gold at the rim
            warm = min(1.0, r * 1.6)
            red = 255
            green = round(255 - 22 * warm)
            blue = round(250 - 80 * warm)
            px[x, y] = (red, green, blue, round(255 * v))
    return img


if __name__ == "__main__":
    out = os.path.normpath(OUT)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    flare().save(out)
    print("wrote", out)
