"""The Warden's Flask: a round glass flask with a cork and a gold collar, holding a warm amber draught
that glows like the Wardfire it's filled from. Drawn at four levels, full to empty, so the item shows
how many draughts are left.

    python3 art/scripts/flask_art.py <assets dir> [preview.png]
"""
import json
import math
import os
import sys

from PIL import Image

CL = (0, 0, 0, 0)
OUT = (38, 24, 16, 255)
GLASS = (84, 98, 108, 255)
GLASS_D = (56, 66, 76, 255)
GLASS_L = (142, 162, 172, 255)
SHINE = (226, 240, 244, 255)
CORK = (150, 104, 62, 255)
CORK_D = (108, 72, 40, 255)
CORK_L = (186, 140, 92, 255)
GOLD = (226, 182, 64, 255)
GOLD_L = (255, 232, 146, 255)
GOLD_D = (164, 116, 32, 255)
# the draught, from its deep edges to the bright surface
DRAUGHT = [(150, 62, 18), (204, 104, 30), (240, 152, 48), (255, 196, 92), (255, 232, 160)]

# the round body
CX, CY, R = 7.5, 9.9, 5.1
# the neck, inclusive pixel columns and rows
NECK_X = (6, 9)
NECK_Y = (2, 5)
# where the draught's surface sits when the flask is full, and the body's floor
FULL_Y = 6.2
FLOOR_Y = CY + R


def in_body(x, y):
    return (x + 0.5 - CX) ** 2 + (y + 0.5 - CY) ** 2 <= R * R


def in_neck(x, y):
    return NECK_X[0] <= x <= NECK_X[1] and NECK_Y[0] <= y <= NECK_Y[1]


def shade(colors, t):
    t = min(max(t, 0.0), 1.0) * (len(colors) - 1)
    i = min(int(t), len(colors) - 2)
    f = t - i
    a, b = colors[i], colors[i + 1]
    return tuple(int(a[k] + (b[k] - a[k]) * f) for k in range(3)) + (255,)


def flask(fill):
    img = Image.new("RGBA", (16, 16), CL)
    inside = [[in_body(x, y) or in_neck(x, y) for x in range(16)] for y in range(16)]
    level = FLOOR_Y - (FLOOR_Y - FULL_Y) * fill
    for y in range(16):
        for x in range(16):
            if not inside[y][x]:
                continue
            dx = (x + 0.5 - CX) / R
            dy = (y + 0.5 - CY) / R
            # light comes from the upper left
            lit = 0.55 - 0.35 * dx - 0.3 * dy
            edge = math.sqrt(dx * dx + dy * dy)
            if fill > 0 and y + 0.5 >= level and in_body(x, y):
                # the draught glows from within: brightest in the middle and at its surface
                glow = 0.75 - edge * 0.55 + lit * 0.25
                if y + 0.5 - level < 1.0:
                    glow += 0.35
                img.putpixel((x, y), shade(DRAUGHT, glow))
            else:
                img.putpixel((x, y), shade([GLASS_D, GLASS, GLASS_L], lit))
    # a curved glint on the glass, upper left of the belly
    for x, y in [(4, 8), (4, 9), (5, 7), (4, 10)]:
        img.putpixel((x, y), SHINE)
    img.putpixel((6, 6), GLASS_L)
    img.putpixel((7, 3), GLASS_L)
    # outline everything that touches the inside
    for y in range(16):
        for x in range(16):
            if inside[y][x]:
                continue
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + ox, y + oy
                if 0 <= nx < 16 and 0 <= ny < 16 and inside[ny][nx]:
                    img.putpixel((x, y), OUT)
                    break
    # the gold collar where the neck meets the belly
    for x in range(5, 11):
        img.putpixel((x, 4), GOLD_D if x in (5, 10) else (GOLD_L if x == 6 else GOLD))
    for x in range(6, 10):
        img.putpixel((x, 5), GOLD_D)
    # the cork, stoutly pushed in
    for x in range(6, 10):
        img.putpixel((x, 0), OUT)
        img.putpixel((x, 1), CORK_L if x == 6 else CORK)
        img.putpixel((x, 2), CORK_D if x == 9 else CORK)
    img.putpixel((5, 1), OUT)
    img.putpixel((10, 1), OUT)
    img.putpixel((5, 2), OUT)
    img.putpixel((10, 2), OUT)
    return img


# how high the draught stands at each level; a round belly holds little at the bottom, so a third of
# the draughts sits higher than a third of the way up
LEVELS = [("wardens_flask_empty", 0.0), ("wardens_flask_low", 0.42), ("wardens_flask_half", 0.72), ("wardens_flask", 1.0)]


def main():
    assets = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    tex = os.path.join(assets, "textures/item")
    models = os.path.join(assets, "models/item")
    items = os.path.join(assets, "items")
    for d in (tex, models, items):
        os.makedirs(d, exist_ok=True)
    images = []
    for name, fill in LEVELS:
        img = flask(fill)
        img.save(os.path.join(tex, name + ".png"))
        images.append(img)
        with open(os.path.join(models, name + ".json"), "w") as f:
            json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "guildmark:item/" + name}}, f, indent=2)
            f.write("\n")

    def ref(name):
        return {"type": "minecraft:model", "model": "guildmark:item/" + name}

    # how full the flask looks follows the draughts its holder has left
    definition = {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "guildmark:flask_fill",
            "entries": [
                {"threshold": 0.01, "model": ref("wardens_flask_low")},
                {"threshold": 0.34, "model": ref("wardens_flask_half")},
                {"threshold": 0.67, "model": ref("wardens_flask")},
            ],
            "fallback": ref("wardens_flask_empty"),
        }
    }
    with open(os.path.join(items, "wardens_flask.json"), "w") as f:
        json.dump(definition, f, indent=2)
        f.write("\n")

    if preview:
        scale = 10
        sheet = Image.new("RGBA", (len(images) * (16 * scale + 10) + 10, 16 * scale + 20), (96, 112, 84, 255))
        for i, img in enumerate(images):
            big = img.resize((16 * scale, 16 * scale), Image.NEAREST)
            sheet.paste(big, (10 + i * (16 * scale + 10), 10), big)
        sheet.save(preview)


if __name__ == "__main__":
    main()
