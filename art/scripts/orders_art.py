"""The supplier orders' art for the market screen and the compass: an order's slip (parchment tacked up with a
red-headed pin, its good's icon drawn over it by the game), the wax seal on a standing order's slip, the stamp on one
promised to another supplier, the emblem of each supplier rank (shown under the shop's name), and the compass's
marker for the business an order is for. Drawn at 1x in the screen kit's palette (ui_art.py), then remastered at
2x the way the rest of the kit is (ui_hd.py: Scale2x, a bevel of light from the upper left, a little grain).

    python3 -B art/scripts/orders_art.py src/main/resources/assets/guildmark [preview-dir [minecraft client jar]]

Writes textures/gui/sprites/market/{order_slip,order_seal,order_taken,rank_<rank>}.png and
textures/gui/sprites/compass/order.png. With a preview dir it writes orders_sprites.png (each design at 1x and as
remastered, large) and orders_slips.png (the slips as the screen shows them, at a GUI scale of 4, with the goods'
icons taken from the minecraft jar if it's given).
"""
import io
import math
import os
import random
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui_art
import ui_hd
from hdkit import sheet

CL = ui_art.CL

# Red wax for the pin's head, darkest first, and its glint; the standing order's seal is blue-black wax, so it
# stands clear of the red goods (apples, meat) drawn on the slip
WAX = [(58, 10, 14, 255), (104, 20, 24, 255), (160, 34, 32, 255), (210, 90, 70, 255)]
WAX_GLINT = (250, 190, 170, 255)
SEAL = [(14, 16, 34, 255), (30, 38, 74, 255), (48, 64, 120, 255), (90, 116, 178, 255)]
SEAL_GLINT = (200, 214, 246, 255)
SEAL_MARK = (198, 162, 84, 255)
INK = ui_art.INK
INK_SOFT = ui_art.INK_SOFT
STAMP_RED = (150, 30, 26)


def paper_slip():
    """An order's slip, 18x24: warm parchment, its corners knocked round and its edges a shade darker, tacked at the
    top middle with a red-headed pin; a line of ink at its foot, under where the good's icon sits (16x16 at 1,5)."""
    W, H = 18, 24
    paper = ui_art.parchment(64, 41)
    img = Image.new("RGBA", (W, H), CL)
    rnd = random.Random(42)
    top, bottom, left, right = 2, H - 1, 1, W - 2
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            if (x in (left, right)) and (y in (top, bottom)):
                continue
            c = paper.getpixel((x + 21, y + 17))
            edge = min(x - left, right - x, y - top, bottom - y)
            k = 0.72 if edge == 0 else 0.9 if edge == 1 else 1.0
            # Lit a little toward the top, where the pin holds it flat; a shade darker toward the foot
            k *= 1.04 - 0.08 * (y - top) / (bottom - top)
            img.putpixel((x, y), ui_art.shade(c, k * (1 + (rnd.random() - 0.5) * 0.03)))
    # The ink at its foot: a short line of writing, broken where the pen lifted
    for x in range(4, 14):
        if x in (7, 11):
            continue
        img.putpixel((x, 22), ui_art.shade(INK_SOFT, 0.95 + rnd.random() * 0.1))
    # The pin: a red head with its glint, over a dent in the paper, and its shadow to the lower right
    img.putpixel((10, 3), ui_art.shade(paper.getpixel((31, 20)), 0.7))
    for (x, y), c in (((8, 0), WAX[1]), ((9, 0), WAX[1]), ((7, 1), WAX[1]), ((8, 1), WAX[3]), ((9, 1), WAX[2]),
                      ((10, 1), WAX[1]), ((7, 2), WAX[1]), ((8, 2), WAX[2]), ((9, 2), WAX[2]), ((10, 2), WAX[0]),
                      ((8, 3), WAX[0]), ((9, 3), WAX[0])):
        img.putpixel((x, y), c)
    img.putpixel((8, 1), WAX_GLINT)
    return img


def wax_seal():
    """A standing order's seal, 8x8: a blob of blue-black wax pressed at the slip's corner, lit from the upper
    left, with the guild's gold mark in it."""
    rows = ["..oooo..",
            ".oRRrro.",
            "oRWRrrro",
            "oRrggrdo",
            "orrggddo",
            "orrrdddo",
            ".oddddo.",
            "..oooo.."]
    return ui_art.from_rows(rows, {".": CL, "o": SEAL[0], "d": SEAL[1], "r": SEAL[2], "R": SEAL[3], "W": SEAL_GLINT, "g": SEAL_MARK})


def taken_stamp():
    """The stamp on an order someone else has taken, 18x10: a box of red ink struck a little crooked, a heavy bar of
    letters across it, the ink patchy where the stamp didn't meet the paper."""
    W, H = 18, 10
    ys, xs = np.mgrid[0:H, 0:W].astype(float)
    # Turned a few degrees about the middle
    a = math.radians(-10)
    cx, cy = (W - 1) / 2, (H - 1) / 2
    u = (xs - cx) * math.cos(a) + (ys - cy) * math.sin(a)
    v = -(xs - cx) * math.sin(a) + (ys - cy) * math.cos(a)
    box = (np.abs(np.abs(u) - 7.6) < 0.75) & (np.abs(v) < 3.9) | (np.abs(np.abs(v) - 3.4) < 0.7) & (np.abs(u) < 8.3)
    # The letters: short heavy strokes along the middle, gaps between the words' letters
    letters = (np.abs(v) < 1.3) & (np.abs(u) < 5.6) & (np.floor((u + 5.6) / 2.25) % 1 == 0) & (((u + 5.6) % 2.25) < 1.7)
    mask = box | letters
    rnd = np.random.default_rng(7)
    patchy = rnd.random((H, W))
    alpha = np.where(mask, np.where(patchy < 0.15, 0.5, 0.94), 0.0)
    out = np.zeros((H, W, 4), np.uint8)
    out[..., 0], out[..., 1], out[..., 2] = STAMP_RED
    out[..., 3] = (alpha * 255).round().astype(np.uint8)
    return Image.fromarray(out, "RGBA")


# The ranks' emblems, 9x9: a coin with a rim lit from the upper left, its face and its mark in the rank's colours
COIN = ["..ooooo..",
        ".oLLLLLo.",
        "oLLFFFLDo",
        "oLFFFFFDo",
        "oLFFFFFDo",
        "oLFFFFFDo",
        "oLDFFFDDo",
        ".oDDDDDo.",
        "..ooooo.."]

# Each rank's coin: its rim's lit and shadowed tones, its face, and the mark struck on the face (5x5, its letters
# standing for the colours given)
RANK_MARKS = {
    # A stranger: plain dark iron, nothing struck on it but a dot
    "stranger": ((84, 82, 92), (38, 36, 42), (46, 44, 52),
                 [".....", ".....", "..m..", ".....", "....."], {"m": (84, 82, 92)}),
    # A grower: a green sprout on brown earth
    "grower": ((150, 108, 66), (90, 60, 36), (52, 40, 30),
               ["L...L", "lL.Ll", ".lLl.", "..s..", "..s.."], {"l": (82, 168, 92), "L": (150, 220, 150), "s": (122, 84, 52)}),
    # A known supplier: a gold ear of wheat on bronze
    "known": ((204, 134, 76), (120, 70, 36), (150, 92, 48),
              ["..G..", ".GgG.", "..g..", ".GgG.", "..g.."], {"g": (198, 162, 84), "G": (246, 226, 160)}),
    # A trusted supplier: a small white star on deep blue, in a silver rim
    "trusted": ((214, 218, 228), (128, 132, 146), (40, 62, 116),
                [".....", "..w..", ".wWw.", "..w..", "....."], {"w": (200, 206, 220), "W": (255, 255, 255)}),
    # The town's purveyor: a cut gold jewel, bright at its heart, on crimson, in a bright gold rim
    "purveyor": ((236, 208, 140), (152, 120, 58), (120, 30, 30),
                 ["..G..", ".GgG.", "GgWgG", ".GgG.", "..G.."], {"g": (198, 162, 84), "G": (246, 226, 160), "W": (255, 246, 214)}),
}


def rank_emblem(name):
    """[name]'s rank as a coin, 9x9."""
    light, dark, face, rows, colours = RANK_MARKS[name]
    pal = {".": CL, "o": ui_art.OUT, "L": light + (255,), "D": dark + (255,), "F": face + (255,)}
    img = ui_art.from_rows(COIN, pal)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colours:
                img.putpixel((2 + x, 2 + y), colours[ch] + (255,))
    return img


# The compass's marker for an order's business: a square of paper with a red pin, two lines of writing on it
ORDER_MARKER = ["ooooooo",
                "owwRwwo",
                "owwwwwo",
                "onnnnwo",
                "owwwwwo",
                "onnnwwo",
                "ooooooo"]

RANKS = ["stranger", "grower", "known", "trusted", "purveyor"]


def designs():
    """Every sprite at 1x: (sprite path, image, whether it's a compass marker)."""
    out = [("market/order_slip", paper_slip(), False), ("market/order_seal", wax_seal(), False),
           ("market/order_taken", taken_stamp(), False)]
    for r in RANKS:
        out.append(("market/rank_" + r, rank_emblem(r), False))
    out.append(("compass/order", ui_art.from_rows(ORDER_MARKER, ui_art.MARK_PAL), True))
    return out


def remastered(items):
    """Each design at 2x as ui_hd.py remasters the screen kit; the stamp keeps its soft see-through ink."""
    out = []
    for i, (key, img, compass) in enumerate(items):
        if key == "market/order_taken":
            big = img.resize((img.width * 2, img.height * 2), Image.NEAREST)
        else:
            big = ui_hd.remaster(img, seed=900 + i, thin=not compass)
        out.append((key, big))
    return out


def item_icon(jar, name):
    """A vanilla item's 16x16 icon from the minecraft jar, or a grey square if it can't be had."""
    if jar:
        try:
            with zipfile.ZipFile(jar) as z:
                data = z.read("assets/minecraft/textures/item/%s.png" % name)
            return Image.open(io.BytesIO(data)).convert("RGBA").crop((0, 0, 16, 16))
        except (KeyError, OSError, zipfile.BadZipFile):
            pass
    return Image.new("RGBA", (16, 16), (120, 120, 120, 255))


def mock(sprites, jar):
    """The slips as an order's row on the market screen shows them, at a GUI scale of 4 (one screen pixel to four):
    open, yours, a standing order with its seal, one promised to someone else with its stamp; and the five ranks'
    emblems as the header shows them."""
    S = 4
    got = dict(sprites)
    W, H = 4 * 30 * S + 40, 30 * S + 30 * S
    out = Image.new("RGBA", (W, H), (17, 15, 19, 255))

    def put(img, x, y, w, h):
        out.alpha_composite(img.resize((w * S, h * S), Image.NEAREST), (x, y))

    for i, (good, state) in enumerate((("wheat", "open"), ("carrot", "yours"), ("apple", "standing"), ("cod", "promised"))):
        x0 = 20 + i * 30 * S
        y0 = 20
        put(got["market/order_slip"], x0, y0, 18, 24)
        put(item_icon(jar, good), x0 + 1 * S, y0 + 5 * S, 16, 16)
        if state == "promised":
            put(got["market/order_taken"], x0, y0 + 8 * S, 18, 10)
        if state == "standing":
            put(got["market/order_seal"], x0 + 11 * S, y0 + 17 * S, 8, 8)
    d = ImageDraw.Draw(out)
    for i, r in enumerate(RANKS):
        x0 = 20 + i * 24 * S
        y0 = 30 * S + 10
        put(got["market/rank_" + r], x0, y0, 9, 9)
        d.text((x0, y0 + 10 * S + 4), r, fill=(220, 214, 200, 255))
    put(got["compass/order"], W - 20 - 7 * S * 2, 30 * S + 10, 14, 14)
    return out


def main():
    assets = sys.argv[1]
    prev = sys.argv[2] if len(sys.argv) > 2 else None
    jar = sys.argv[3] if len(sys.argv) > 3 else None
    items = designs()
    big = remastered(items)
    for key, img in big:
        path = os.path.join(assets, "textures/gui/sprites", key + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
    if prev:
        os.makedirs(prev, exist_ok=True)
        pairs = []
        labels = []
        for (key, small, _), (_, large) in zip(items, big):
            pairs += [small.resize((small.width * 2, small.height * 2), Image.NEAREST), large]
            labels += [key.split("/")[-1] + " 1x", key.split("/")[-1] + " 2x"]
        sheet(pairs, 6, 6, labels=labels).save(os.path.join(prev, "orders_sprites.png"))
        mock(big, jar).save(os.path.join(prev, "orders_slips.png"))
    print(len(big), "sprites")


if __name__ == "__main__":
    main()
