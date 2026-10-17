"""Guildmark's screen kit: the dark-iron-and-gold look the combat HUD started, for every new screen.

    python3 art/scripts/ui_art.py src/main/resources/assets/guildmark [preview.png]

Writes sprites to textures/gui/sprites/{ui,compass,map,death}/ (with their .mcmeta scaling), the
map's parchment and guild-lands wash to textures/gui/map/, and an optional contact sheet of everything at 4x.
Same palette as hud_art.py: iron frames with a tarnished-gold inner line, dark see-through fills.
"""
import json
import math
import os
import random
import sys

from PIL import Image

CL = (0, 0, 0, 0)
OUT = (12, 11, 14, 235)
IRON_L = (84, 82, 92, 255)
IRON = (58, 56, 64, 255)
IRON_D = (38, 36, 42, 255)
GOLD_L = (198, 162, 84, 255)
GOLD = (152, 120, 58, 255)
GOLD_D = (106, 82, 40, 255)
FILL = (17, 15, 19, 232)
FILL_DEEP = (9, 8, 11, 214)

INK = (58, 38, 20, 255)
INK_SOFT = (96, 68, 40, 255)
INK_RED = (140, 34, 26, 255)
BONE = (226, 216, 190, 255)

SPRITES = None
written = []


def save(img, rel, meta=None):
    path = os.path.join(SPRITES, rel + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    if meta is not None:
        with open(path + ".mcmeta", "w") as f:
            json.dump(meta, f, indent=2)
            f.write("\n")
    written.append((rel, img))


def nine(w, h, border):
    return {"gui": {"scaling": {"type": "nine_slice", "width": w, "height": h, "border": border}}}


STRETCH = {"gui": {"scaling": {"type": "stretch"}}}


def from_rows(rows, pal):
    img = Image.new("RGBA", (len(rows[0]), len(rows)), CL)
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            img.putpixel((x, y), pal[ch])
    return img


def shade(c, k):
    return tuple(max(0, min(255, int(round(v * k)))) for v in c[:3]) + (c[3],)


def noisy_fill(img, x0, y0, x1, y1, base, amount, seed):
    """fills a box with base colour and a faint grain, so large dark areas aren't flat"""
    rnd = random.Random(seed)
    for y in range(y0, y1):
        for x in range(x0, x1):
            k = 1.0 + (rnd.random() - 0.5) * amount
            img.putpixel((x, y), shade(base, k))


# ---------------------------------------------------------------- frames
def framed(w, h, fill, seed, gold=True, corners=True):
    """outline, bevelled iron, a dark gap, the gold line, then the fill; ornaments in the corners"""
    img = Image.new("RGBA", (w, h), CL)
    noisy_fill(img, 1, 1, w - 1, h - 1, fill, 0.10, seed)
    for x in range(1, w - 1):
        img.putpixel((x, 0), OUT)
        img.putpixel((x, h - 1), OUT)
        img.putpixel((x, 1), IRON_L)
        img.putpixel((x, h - 2), IRON_D)
        img.putpixel((x, 2), IRON)
        img.putpixel((x, h - 3), IRON)
    for y in range(1, h - 1):
        img.putpixel((0, y), OUT)
        img.putpixel((w - 1, y), OUT)
        img.putpixel((1, y), IRON_L)
        img.putpixel((w - 2, y), IRON_D)
        img.putpixel((2, y), IRON)
        img.putpixel((w - 3, y), IRON)
    for x in range(3, w - 3):
        img.putpixel((x, 3), OUT)
        img.putpixel((x, h - 4), OUT)
    for y in range(3, h - 3):
        img.putpixel((3, y), OUT)
        img.putpixel((w - 4, y), OUT)
    if gold:
        for x in range(4, w - 4):
            img.putpixel((x, 4), GOLD_L if x < w // 2 else GOLD)
            img.putpixel((x, h - 5), GOLD_D)
        for y in range(4, h - 4):
            img.putpixel((4, y), GOLD_L if y < h // 2 else GOLD)
            img.putpixel((w - 5, y), GOLD_D)
    if corners:
        # a small gilt boss on each corner of the iron, and a notch of gold just inside it
        for cx, cy in ((1, 1), (w - 4, 1), (1, h - 4), (w - 4, h - 4)):
            for dx in range(3):
                for dy in range(3):
                    img.putpixel((cx + dx, cy + dy), GOLD if (dx + dy) % 2 == 0 else GOLD_D)
            img.putpixel((cx + 1, cy + 1), GOLD_L)
        if gold:
            for cx, cy, sx, sy in ((5, 5, 1, 1), (w - 6, 5, -1, 1), (5, h - 6, 1, -1), (w - 6, h - 6, -1, -1)):
                img.putpixel((cx, cy), GOLD)
                img.putpixel((cx + sx, cy), GOLD_D)
                img.putpixel((cx, cy + sy), GOLD_D)
    return img


def inset(w, h, fill, seed):
    """a sunken box: dark rim above and left, a faint iron edge below and right"""
    img = Image.new("RGBA", (w, h), CL)
    noisy_fill(img, 1, 1, w - 1, h - 1, fill, 0.08, seed)
    for x in range(w):
        img.putpixel((x, 0), OUT)
        img.putpixel((x, h - 1), IRON_D)
    for y in range(h):
        img.putpixel((0, y), OUT)
        img.putpixel((w - 1, y), IRON_D)
    img.putpixel((w - 1, 0), OUT)
    img.putpixel((0, h - 1), OUT)
    return img


def button(w, h, fill, line, top_hi, seed):
    img = Image.new("RGBA", (w, h), CL)
    noisy_fill(img, 2, 2, w - 2, h - 2, fill, 0.06, seed)
    for x in range(1, w - 1):
        img.putpixel((x, 0), OUT)
        img.putpixel((x, h - 1), OUT)
        img.putpixel((x, 1), top_hi)
        img.putpixel((x, h - 2), IRON_D)
    for y in range(1, h - 1):
        img.putpixel((0, y), OUT)
        img.putpixel((w - 1, y), OUT)
        img.putpixel((1, y), top_hi if y < h - 2 else IRON_D)
        img.putpixel((w - 2, y), IRON_D)
    for x in range(3, w - 3):
        img.putpixel((x, 2), line)
        img.putpixel((x, h - 3), shade(line, 0.7))
    for y in range(3, h - 3):
        img.putpixel((2, y), line)
        img.putpixel((w - 3, y), shade(line, 0.7))
    return img


def socket(size, seed):
    """an equipment socket: a dark hollow ringed in iron, with a fleck of gold at each corner"""
    img = Image.new("RGBA", (size, size), CL)
    noisy_fill(img, 2, 2, size - 2, size - 2, (12, 11, 14, 226), 0.08, seed)
    for i in range(1, size - 1):
        for x, y, c in ((i, 0, OUT), (i, size - 1, OUT), (0, i, OUT), (size - 1, i, OUT),
                        (i, 1, IRON_D), (1, i, IRON_D), (i, size - 2, IRON), (size - 2, i, IRON)):
            img.putpixel((x, y), c)
    for x, y in ((1, 1), (size - 2, 1), (1, size - 2), (size - 2, size - 2)):
        img.putpixel((x, y), GOLD_D)
    return img


def glow(size, color, peak):
    """a soft round light, strongest in the middle"""
    img = Image.new("RGBA", (size, size), CL)
    r = size / 2
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - r, y + 0.5 - r) / r
            if d < 1:
                img.putpixel((x, y), color[:3] + (int(peak * (1 - d) ** 1.5),))
    return img


def gradient_band(w, h, color, a0, a1, power=1.0):
    img = Image.new("RGBA", (w, h), CL)
    for x in range(w):
        k = (x / (w - 1)) ** power
        a = int(round(a0 + (a1 - a0) * k))
        for y in range(h):
            img.putpixel((x, y), color[:3] + (a,))
    return img


def divider(w):
    """a gold rule that fades out at both ends"""
    img = Image.new("RGBA", (w, 3), CL)
    for x in range(w):
        t = abs(x - (w - 1) / 2) / ((w - 1) / 2)
        a = max(0.0, 1.0 - t ** 2.2)
        img.putpixel((x, 0), (0, 0, 0, int(90 * a)))
        img.putpixel((x, 1), GOLD[:3] + (int(255 * a),))
        img.putpixel((x, 2), (0, 0, 0, int(120 * a)))
    return img


def diamond(r, outer, inner, hi):
    s = 2 * r + 1
    img = Image.new("RGBA", (s, s), CL)
    for y in range(s):
        for x in range(s):
            d = abs(x - r) + abs(y - r)
            if d == r:
                img.putpixel((x, y), outer)
            elif d < r:
                img.putpixel((x, y), hi if (x < r and y <= r) or (y < r and x <= r) else inner)
    return img


def compass_strip(w, h):
    """the compass band: dark and see-through, edged in gold, fading out toward both ends"""
    img = Image.new("RGBA", (w, h), CL)
    for x in range(w):
        t = abs(x - (w - 1) / 2) / ((w - 1) / 2)
        a = max(0.0, 1.0 - t ** 2.6)
        for y in range(h):
            if y in (0, h - 1):
                c, alpha = GOLD, 225
            elif y in (1, h - 2):
                c, alpha = (0, 0, 0), 185
            else:
                c, alpha = (12, 11, 14), 150
            img.putpixel((x, y), c[:3] + (int(alpha * a),))
    return img


# ---------------------------------------------------------------- icons
MARK_PAL = {
    ".": CL, "o": (10, 9, 12, 255), "g": GOLD, "G": GOLD_L, "d": GOLD_D,
    "w": BONE, "W": (255, 250, 236, 255), "s": (140, 132, 120, 255),
    "r": (170, 46, 38, 255), "R": (220, 84, 64, 255), "b": (64, 104, 178, 255), "B": (120, 160, 226, 255),
    "n": (122, 84, 52, 255), "N": (168, 120, 74, 255), "e": (82, 168, 92, 255), "E": (150, 220, 150, 255),
    "i": IRON_L, "I": (120, 118, 130, 255),
    "P": (255, 255, 255, 255), "q": (218, 218, 218, 255), "Q": (160, 160, 160, 255),
}

COMPASS_ICONS = {
    # a gold diamond: a contract's target
    "contract": ["...o...", "..oGo..", ".oGggo.", "oGgggdo", ".oggdo.", "..odo..", "...o..."],
    # a little house: a village
    "village": ["...o...", "..oNo..", ".oNNNo.", "oNNNNNo", ".owwwo.", ".owowo.", ".ooooo."],
    # a keep with a flag: a town or city
    "town": ["o.o.o.o", "oioioio", "oiiiiio", ".oiIio.", ".oiIio.", ".oioio.", ".ooooo."],
    # a skull: a lair still awake
    "lair": [".ooooo.", "owwwwwo", "owowowo", "owwWwwo", ".owwwo.", ".owowo.", "..ooo.."],
    # a dim skull: a lair cleared
    "lair_cleared": [".ooooo.", "osssss.", "osososo", "osssss.", ".osssso", ".ososo.", "..ooo.."],
    # a guild banner
    "hall": ["ooooooo", "obbbbbo", "obBgBbo", "obbgbbo", "obbbbbo", "obo.obo", "oo...oo"],
    # a crown: one of the shard vaults
    "vault": ["o..o..o", "oGoGoGo", "oGGgGGo", "ogggggo", "odrdrdo", "odddddo", "ooooooo"],
    # a green dot ringed in dark: someone in your party
    "party": [".ooooo.", "oeeeeeo", "oeEEeeo", "oeEeeeo", "oeeeeeo", "oeeeeeo", ".ooooo."],
    # a red horned helm: a boss
    "boss": ["o.....o", "ro...or", "oroooro", "orrrrro", "orRrRro", ".orrro.", "..ooo.."],
    # a cart wheel: a coach stop
    "coach": [".ooooo.", "onnNnno", "on.N.no", "oNNNNNo", "on.N.no", "onnNnno", ".ooooo."],
    # a pale cross: where you last fell
    "death": ["..ooo..", "..owo..", "ooowooo", "owwwwwo", "ooowooo", "..owo..", "..ooo.."],
    # a pin standing on its point, white so the pin's own colour can be laid over it
    "pin": [".ooooo.", "oqPPqqo", "oqPqqQo", "oqqqqQo", ".oqqQo.", "..oQo..", "...o..."],
    # a flame on its ring of stones: a Wardfire you've kindled
    "wardfire": ["...o...", "..oRo..", ".oRGRo.", "oRGWGRo", "oRGWGRo", "ossssso", ".ooooo."],
    # a gold coin with a bright star struck in it: the Guild Marks you lost when you fell
    "lost_marks": ["..ooo..", ".oGGgo.", "oGGggdo", "oGgdgdo", "oggggdo", ".odddo.", "..ooo.."],
    # a mossy troll's head with red eyes and tusks: a field boss's camp
    "troll_camp": [".ooooo.", "oeeeeeo", "oereReo", "oeeoeeo", "oWeeeWo", ".oWoWo.", "..ooo.."],
    # the same head gone grey: its troll is slain
    "troll_camp_slain": [".ooooo.", "osssss.", "osososo", "ossoss.", "oqsssqo", ".oqoqo.", "..ooo.."],
    # a brown hood over a shadowed face, eyes glinting above a red mask: an outlaw band's camp
    "outlaw_camp": ["..ooo..", ".onnno.", "onooono", "onWoWno", "onrrrno", ".orRro.", "..ooo.."],
    # the same hood gone grey: its captain has fallen
    "outlaw_camp_slain": ["..ooo..", ".osss..", "osooos.", "osoooso", "osqqqso", ".oqsqo.", "..oo..."],
}

# ink icons for the map, 11x11, drawn on parchment
MAP_PAL = {".": CL, "k": INK, "s": INK_SOFT, "r": INK_RED, "R": (190, 60, 44, 255),
           "g": (176, 136, 60, 255), "b": (52, 82, 140, 255), "e": (54, 120, 64, 255), "w": (240, 228, 196, 255),
           "P": (255, 255, 255, 255), "q": (218, 218, 218, 255), "Q": (160, 160, 160, 255)}

MAP_ICONS = {
    "village": [
        "...........",
        "...k.......",
        "..ksk......",
        ".ksssk..k..",
        "kkkkkkkksk.",
        ".k...kssssk",
        ".k.k.kkkkkk",
        ".k.k.k.k..k",
        ".kkkkk.k.kk",
        ".....kkkkkk",
        "...........",
    ],
    "town": [
        "...........",
        "k.k.....k.k",
        "kkk..k..kkk",
        ".k..ksk..k.",
        ".k.ksssk.k.",
        ".kkkkkkkkk.",
        ".k.......k.",
        ".k..kkk..k.",
        ".k..k.k..k.",
        ".kkkk.kkkk.",
        "...........",
    ],
    "city": [
        "k.k.k.k.k.k",
        "kkkkkkkkkkk",
        ".k..r.r..k.",
        ".k..rrr..k.",
        ".k.......k.",
        "kkk.kkk.kkk",
        "k.k.k.k.k.k",
        "kkkkkkkkkkk",
        "k...kkk...k",
        "k...k.k...k",
        "kkkkk.kkkkk",
    ],
    "lair": [
        "...........",
        "...kkkkk...",
        "..k.....k..",
        ".k.rr.rr.k.",
        ".k.rr.rr.k.",
        ".k...k...k.",
        "..k.....k..",
        "...k.k.k...",
        "...kkkkk...",
        "...........",
        "...........",
    ],
    "lair_cleared": [
        "...........",
        "...sssss...",
        "..s.....s.k",
        ".s.ss.ss.k.",
        ".s.ss.sk.s.",
        ".s...sk..s.",
        "..s..k..s..",
        "...sks.s...",
        "...ksssss..",
        "..k........",
        "...........",
    ],
    "hall": [
        "...........",
        ".k.........",
        ".kbbbbbbb..",
        ".kbbgbbbb..",
        ".kbgggbbb..",
        ".kbbgbbbb..",
        ".kbbbbbbb..",
        ".kbb...bb..",
        ".kb.....b..",
        ".k.........",
        "kkk........",
    ],
    "vault": [
        "...........",
        "...........",
        ".k...k...k.",
        ".kk.kgk.kk.",
        ".kgkgggkgk.",
        ".kgggggggk.",
        ".kgrgggrgk.",
        ".kkkkkkkkk.",
        "...........",
        "...........",
        "...........",
    ],
    "player": [
        ".....R.....",
        ".....R.....",
        "....RRR....",
        "....RrR....",
        "...RRrRR...",
        "...RrrrR...",
        "..RRrrrRR..",
        "..Rrr.rrR..",
        ".RRr...rRR.",
        ".Rr.....rR.",
        ".R.......R.",
    ],
    "party": [
        "...........",
        "....eee....",
        "...eeeee...",
        "..eewwwee..",
        "..eewwwee..",
        "..eewwwee..",
        "...eeeee...",
        "....eee....",
        "...........",
        "...........",
        "...........",
    ],
    "contract": [
        "...........",
        ".rr.....rr.",
        "..rr...rr..",
        "...rr.rr...",
        "....rrr....",
        "...rr.rr...",
        "..rr...rr..",
        ".rr.....rr.",
        "...........",
        "...........",
        "...........",
    ],
    "coach": [
        "...........",
        "...kkkkk...",
        "..k..k..k..",
        ".k...k...k.",
        ".k.k.k.k.k.",
        ".kkkkkkkkk.",
        ".k.k.k.k.k.",
        ".k...k...k.",
        "..k..k..k..",
        "...kkkkk...",
        "...........",
    ],
    # a pin, white so the pin's own colour can be laid over it; it stands on its point
    "pin": [
        "...kkkkk...",
        "..kqPPqqk..",
        ".kqPPqqqQk.",
        ".kqPqqqqQk.",
        ".kqqqqqqQk.",
        "..kqqqqQk..",
        "...kqqQk...",
        "....kQk....",
        "....kQk....",
        ".....k.....",
        "...........",
    ],
    "death": [
        "...........",
        "....kkk....",
        "....k.k....",
        ".kkkk.kkkk.",
        ".k.......k.",
        ".kkkk.kkkk.",
        "....k.k....",
        "....k.k....",
        "....k.k....",
        "....kkk....",
        "...........",
    ],
    # two coins, one fallen against the other: the Guild Marks you lost
    "lost_marks": [
        "...........",
        "....kkkkk..",
        "...kgggggk.",
        "..kgggggggk",
        "..kgggkgggk",
        ".kkkkggggk.",
        "kgggggkkk..",
        "kgggkgggk..",
        "kgggggggk..",
        ".kgggggk...",
        "..kkkkk....",
    ],
    # a troll's head: ears out wide, a heavy brow over red eyes, a tusk either side of the jaw
    "troll_camp": [
        "...........",
        "....kkk....",
        "..kksssskk.",
        ".ksssssssk.",
        "kkskkskkskk",
        ".k.r...r.k.",
        ".k...k...k.",
        ".k..kkk..k.",
        ".kw.....wk.",
        "..kw.k.wk..",
        "...kkkkk...",
    ],
    # the head in faded ink with a stroke through it: the troll is slain
    "troll_camp_slain": [
        "...........",
        "....sss...k",
        "..sssssssk.",
        ".sssssssk..",
        "sssssssks.s",
        ".s.s..k..s.",
        ".s...k...s.",
        ".s..k.s..s.",
        ".s.k.....s.",
        "..k.....s..",
        ".k.sssss...",
    ],
    # a hood with a shadowed face, two eyes and a red mask: an outlaw band's camp
    "outlaw_camp": [
        "...........",
        ".....k.....",
        "....ksk....",
        "...ksssk...",
        "..kskkksk..",
        ".kskwkwksk.",
        ".kskkkkksk.",
        ".ksrrrrrsk.",
        ".kssrRrssk.",
        "kssssrssssk",
        "kkkkkkkkkkk",
    ],
    # the hood in faded ink with a stroke through it: its captain has fallen
    "outlaw_camp_slain": [
        "...........",
        ".....s....k",
        "....s.s..k.",
        "...s...sk..",
        "..s.sssks..",
        ".s.s.sks.s.",
        ".s.sskss.s.",
        ".s..k....s.",
        ".s.k.....s.",
        "s.k.......s",
        "sksssssssss",
    ],
    # a sword planted in a fire on its ring of stones
    "wardfire": [
        ".....k.....",
        "....kkk....",
        "..r..k..r..",
        "..rr.k.rr..",
        ".rRr.k.rRr.",
        ".rRgrkrgRr.",
        "rRggRkRggRr",
        ".rRggkggRr.",
        "..rRgkgRr..",
        ".kkkkkkkkk.",
        "ksk.ksk.ksk",
    ],
}


def rose(r):
    """a compass rose in ink: four long points and four short, each half dark and half pale, the north
    point in red; drawn large and scaled down so its edges stay clean"""
    from PIL import ImageDraw
    k = 8
    s = 2 * r + 1
    big = Image.new("RGBA", (s * k, s * k), CL)
    draw = ImageDraw.Draw(big)
    c = (r + 0.5) * k
    pale = (246, 238, 216, 255)
    red_pale = (222, 140, 112, 255)

    def at(angle, along, across):
        th = math.radians(angle)
        ux, uy = math.sin(th), -math.cos(th)
        return (c + (ux * along - uy * across) * k, c + (uy * along + ux * across) * k)

    ring = r * 0.46 * k
    draw.ellipse((c - ring, c - ring, c + ring, c + ring), outline=INK_SOFT[:3] + (190,), width=k)
    for angle, length, half in [(a, r * 0.62, 2.4) for a in (45, 135, 225, 315)] + [(a, r - 0.5, 3.4) for a in (0, 90, 180, 270)]:
        north = angle == 0
        tip = at(angle, length, 0)
        hub = at(angle, 0, 0)
        left = at(angle, half * 0.55, -half)
        right = at(angle, half * 0.55, half)
        draw.polygon([hub, left, tip], fill=INK_RED if north else INK)
        draw.polygon([hub, right, tip], fill=red_pale if north else pale)
        draw.line([hub, left, tip, right, hub], fill=INK, width=k, joint="curve")
    hub = 1.4 * k
    draw.ellipse((c - hub, c - hub, c + hub, c + hub), fill=INK)
    return big.resize((s, s), Image.LANCZOS)


def vignette(size, border):
    """the map's inner shadow: the paper darkening toward the frame, with a fine ink rule round it"""
    img = Image.new("RGBA", (size, size), CL)
    for y in range(size):
        for x in range(size):
            d = min(x, y, size - 1 - x, size - 1 - y)
            if d >= border:
                continue
            t = 1 - d / border
            a = int(118 * t ** 2.2)
            c = (54, 34, 16, a)
            if d == 3:
                c = INK[:3] + (150,)
            img.putpixel((x, y), c)
    return img


def territory(size):
    """a guild's lands: a soft wash, stronger toward its painted edge (white, to take the guild's colour)"""
    img = Image.new("RGBA", (size, size), CL)
    r = size / 2
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - r, y + 0.5 - r) / r
            if d >= 1:
                continue
            if d < 0.78:
                a = 0.30 + 0.12 * (d / 0.78) ** 2
            elif d < 0.92:
                a = 0.42 + 0.5 * (d - 0.78) / 0.14
            else:
                a = 0.92 * (1 - (d - 0.92) / 0.08)
            img.putpixel((x, y), (255, 255, 255, int(255 * max(0.0, a))))
    return img


# ---------------------------------------------------------------- parchment
def tile_noise(size, cells, rnd):
    """smooth value noise on a [cells] grid that wraps, so the tile repeats without a seam"""
    grid = [[rnd.random() for _ in range(cells)] for _ in range(cells)]
    out = [[0.0] * size for _ in range(size)]
    step = size / cells
    for y in range(size):
        gy = y / step
        y0 = int(gy) % cells
        y1 = (y0 + 1) % cells
        ty = gy - int(gy)
        ty = ty * ty * (3 - 2 * ty)
        for x in range(size):
            gx = x / step
            x0 = int(gx) % cells
            x1 = (x0 + 1) % cells
            tx = gx - int(gx)
            tx = tx * tx * (3 - 2 * tx)
            a = grid[y0][x0] + (grid[y0][x1] - grid[y0][x0]) * tx
            b = grid[y1][x0] + (grid[y1][x1] - grid[y1][x0]) * tx
            out[y][x] = a + (b - a) * ty
    return out


def parchment(size, seed):
    """a seamless parchment tile: warm paper, soft mottling, a few faint stains and a fine grain"""
    rnd = random.Random(seed)
    base = (226, 206, 162)
    layers = [(tile_noise(size, c, rnd), w) for c, w in ((4, 0.09), (8, 0.05), (16, 0.03), (32, 0.018))]
    stains = [(rnd.random() * size, rnd.random() * size, rnd.uniform(12, 30), rnd.uniform(0.02, 0.04)) for _ in range(4)]
    img = Image.new("RGBA", (size, size))
    for y in range(size):
        for x in range(size):
            k = 1.0
            for noise, weight in layers:
                k += (noise[y][x] - 0.5) * 2 * weight
            for sx, sy, r, depth in stains:
                dx = min(abs(x - sx), size - abs(x - sx))
                dy = min(abs(y - sy), size - abs(y - sy))
                d = math.hypot(dx, dy)
                if d < r:
                    k -= depth * (1 - (d / r) ** 2)
            k += (rnd.random() - 0.5) * 0.035
            c = (int(base[0] * k), int(base[1] * k), int(base[2] * (k ** 1.2)))
            img.putpixel((x, y), tuple(max(0, min(255, v)) for v in c) + (255,))
    return img


def slip(w, h, seed):
    """a torn strip of parchment for the death screen: rough edges all round"""
    rnd = random.Random(seed)
    paper = parchment(64, seed + 1).crop((0, 0, w, h))
    img = Image.new("RGBA", (w, h), CL)
    top = [rnd.randint(0, 2) for _ in range(w)]
    bottom = [rnd.randint(0, 2) for _ in range(w)]
    left = [rnd.randint(0, 2) for _ in range(h)]
    right = [rnd.randint(0, 2) for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if y < top[x] or y >= h - bottom[x] or x < left[y] or x >= w - right[y]:
                continue
            c = paper.getpixel((x, y))
            edge = min(y - top[x], h - 1 - bottom[x] - y, x - left[y], w - 1 - right[y] - x)
            if edge < 3:
                c = shade(c, 0.78 + 0.07 * edge)
            img.putpixel((x, y), c)
    return img


def seal(r, seed):
    """a red wax seal pressed with the guild's mark: a ring and a little crown"""
    rnd = random.Random(seed)
    s = 2 * r + 1
    img = Image.new("RGBA", (s, s), CL)
    for y in range(s):
        for x in range(s):
            dx = x - r
            dy = y - r
            ang = math.atan2(dy, dx)
            edge = r - 0.6 + 0.9 * math.sin(ang * 7 + 0.5) * 0.5 + rnd.uniform(-0.25, 0.25)
            d = math.hypot(dx, dy)
            if d > edge:
                continue
            light = 1.0 - 0.35 * (dx + dy) / (2 * r)
            c = (int(150 * light), int(28 * light), int(26 * light), 255)
            if abs(d - (r - 3.2)) < 0.6:
                c = (96, 14, 16, 255)
            img.putpixel((x, y), c)
    mark = ["k.k.k", "kkkkk", "kkkkk"]
    ox, oy = r - 2, r - 1
    for yy, row in enumerate(mark):
        for xx, ch in enumerate(row):
            if ch == "k":
                img.putpixel((ox + xx, oy + yy), (92, 12, 14, 255))
    img.putpixel((r - 1, r + 2), (196, 70, 60, 255))
    return img


def crest():
    """the guild's shield with the mark's diamond on a night-blue field, for the button that opens the settings"""
    rows = [".ooooooooooooo.",
            "oGGGGGGGGGGGGGo",
            "oGd#########dGo",
            "oG#####g#####Go",
            "oG####gLg####Go",
            "oG###gLLLg###Go",
            "oG##gLLWLLg##Go",
            "oG###gLLLg###Go",
            "oG####gLg####Go",
            ".oG####g####Go.",
            ".oGd#######dGo.",
            "..oG#######Go..",
            "...oGd###dGo...",
            "....ooGGGoo....",
            "......ooo......"]
    img = from_rows(rows, {".": CL, "o": OUT, "G": GOLD_L, "d": GOLD_D, "#": (34, 38, 64, 255), "g": GOLD,
                           "L": GOLD_L, "W": (246, 226, 160, 255)})
    # the field darkens towards the point
    for y in range(img.height):
        for x in range(img.width):
            if rows[y][x] == "#":
                img.putpixel((x, y), shade((34, 38, 64, 255), 1.15 - 0.45 * y / (img.height - 1)))
    return img


# ---------------------------------------------------------------- main
def main():
    global SPRITES
    assets = sys.argv[1]
    preview = sys.argv[2] if len(sys.argv) > 2 else None
    SPRITES = os.path.join(assets, "textures/gui/sprites")

    save(framed(32, 32, FILL, 1), "ui/panel", nine(32, 32, 8))
    save(framed(24, 24, FILL_DEEP, 2, gold=False, corners=False), "ui/panel_plain", nine(24, 24, 4))
    save(inset(16, 16, (10, 9, 12, 196), 3), "ui/inset", nine(16, 16, 2))
    save(button(32, 20, (36, 33, 38, 240), GOLD_D, IRON, 4), "ui/button", nine(32, 20, 4))
    save(button(32, 20, (58, 50, 42, 245), GOLD_L, IRON_L, 5), "ui/button_highlighted", nine(32, 20, 4))
    save(button(32, 20, (24, 23, 26, 220), IRON_D, IRON_D, 6), "ui/button_disabled", nine(32, 20, 4))
    save(gradient_band(32, 4, GOLD, 120, 0, 0.8), "ui/choice_glow", STRETCH)
    save(divider(64), "ui/divider", STRETCH)
    save(diamond(2, OUT, GOLD, GOLD_L), "ui/diamond")
    save(diamond(3, OUT, GOLD, GOLD_L), "ui/diamond_large")
    save(inset(18, 18, (10, 9, 12, 210), 7), "ui/slot")
    save(socket(18, 8), "ui/socket")
    save(glow(64, (226, 176, 92), 92), "ui/glow", STRETCH)
    save(crest(), "ui/crest")

    # the name plate: a dark ribbon edged in gold
    plate = ["..oooooooooooooooooooo..",
             ".oGggggggggggggggggggGo.",
             "oGd##################dGo",
             "og####################go",
             "og####################go",
             "og####################go",
             "og####################go",
             "og####################go",
             "og####################go",
             "og####################go",
             "oGd##################dGo",
             ".oGggggggggggggggggggGo.",
             "..oooooooooooooooooooo.."]
    save(from_rows(plate, {".": CL, "o": OUT, "G": GOLD_L, "g": GOLD, "d": GOLD_D, "#": (14, 12, 16, 236)}),
         "ui/name_plate", nine(24, 13, 4))

    save(compass_strip(96, 13), "compass/strip", STRETCH)
    # the gold notch over the compass's middle
    save(from_rows(["ooooooo", "oGGGGGo", ".oGgGo.", "..oGo..", "...o..."],
                   {".": CL, "o": OUT, "G": GOLD_L, "g": GOLD}), "compass/notch")
    for name, rows in COMPASS_ICONS.items():
        save(from_rows(rows, MARK_PAL), "compass/" + name)
    for name, rows in MAP_ICONS.items():
        save(from_rows(rows, MAP_PAL), "map/" + name)
    save(rose(12), "map/rose")
    save(vignette(32, 12), "map/frame", nine(32, 32, 12))

    save(slip(64, 32, 11), "death/slip", nine(64, 32, 8))
    save(seal(9, 12), "death/seal")

    tex = os.path.join(assets, "textures/gui/map")
    os.makedirs(tex, exist_ok=True)
    paper = parchment(256, 21)
    paper.save(os.path.join(tex, "parchment.png"))
    written.append(("map/parchment (texture)", paper))
    # smoothed when it's drawn large, since it's washed over whole villages
    lands = territory(64)
    lands.save(os.path.join(tex, "territory.png"))
    with open(os.path.join(tex, "territory.png.mcmeta"), "w") as f:
        json.dump({"texture": {"blur": True, "clamp": True}}, f, indent=2)
        f.write("\n")
    written.append(("map/territory (texture)", lands))

    if preview:
        scale = 4
        pad = 8
        cols = 6
        cells = [img.resize((img.width * scale, img.height * scale), Image.NEAREST) for _, img in written]
        rows = [cells[i:i + cols] for i in range(0, len(cells), cols)]
        width = max(sum(c.width + pad for c in r) for r in rows) + pad
        height = sum(max(c.height for c in r) + pad for r in rows) + pad
        sheet = Image.new("RGBA", (width, height), (96, 112, 84, 255))
        y = pad
        for r in rows:
            x = pad
            for c in r:
                sheet.paste(c, (x, y), c)
                x += c.width + pad
            y += max(c.height for c in r) + pad
        sheet.save(preview)
    print(len(written), "images")


if __name__ == "__main__":
    main()
