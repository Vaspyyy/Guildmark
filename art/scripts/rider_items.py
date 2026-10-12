"""Item art for the Dusk Rider's spoils: the Dusk Bridle (a black leather headstall with a steel curb bit and a
lantern-blue stone on the browband) and the Lantern Wisp a rider casts (a glowing orb, drawn full bright).

    python3 art/scripts/rider_items.py src/main/resources/assets/guildmark/textures/item [preview.png]
"""
import math
import sys
from PIL import Image

out_dir = sys.argv[1]
preview = sys.argv[2] if len(sys.argv) > 2 else None

# ---- Dusk Bridle: drawn from a pixel map, then outlined
BRIDLE = [
    "................",
    ".....hhhhhh.....",
    "....hlllllll....",
    "...hl......lL...",
    "..wsGgsssgGsw...",
    "...l........L...",
    "...l........L...",
    "...l........L...",
    "..wsssssssssw...",
    "...l........L...",
    "..sws......sws..",
    "..s.sSSSSSSs.s..",
    "..sws......sws..",
    "...l........L...",
    "....ll....LL....",
    "......lLLL......",
]
COLOURS = {
    'L': (60, 42, 44), 'l': (92, 66, 62), 'h': (128, 96, 84),
    'S': (78, 84, 98), 's': (134, 142, 158), 'w': (200, 208, 222),
    'G': (96, 176, 240), 'g': (206, 242, 255),
}
OUTLINE = (16, 12, 16)


def draw(rows):
    im = Image.new('RGBA', (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in COLOURS:
                im.putpixel((x, y), (*COLOURS[ch], 255))
    # A dark line round the outside, as items are drawn; the gaps inside the straps stay open
    solid = {(x, y) for y in range(16) for x in range(16) if im.getpixel((x, y))[3] > 0}
    outside = set()
    todo = [(x, y) for x in range(16) for y in (0, 15)] + [(x, y) for y in range(16) for x in (0, 15)]
    while todo:
        x, y = todo.pop()
        if not (0 <= x < 16 and 0 <= y < 16) or (x, y) in outside or (x, y) in solid:
            continue
        outside.add((x, y))
        todo += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
    for x, y in outside:
        if any((x + dx, y + dy) in solid for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            im.putpixel((x, y), (*OUTLINE, 255))
    return im


bridle = draw(BRIDLE)
bridle.save(f'{out_dir}/dusk_bridle.png')

# ---- Lantern Wisp: a white core in pale blue light that fades out at the edge, with a few sparks
wisp = Image.new('RGBA', (16, 16))
for y in range(16):
    for x in range(16):
        d = math.hypot(x - 7.5, y - 7.5) / 7.5
        if d > 1.0:
            continue
        core = max(0.0, 1.0 - d / 0.38)
        glow = max(0.0, 1.0 - d) ** 1.6
        r = round(110 + 145 * core)
        g = round(200 + 55 * core)
        b = 255
        a = round(255 * min(1.0, glow * 1.5 + core))
        if a > 8:
            wisp.putpixel((x, y), (r, g, b, a))
for x, y in ((3, 4), (12, 3), (13, 11), (2, 12)):
    wisp.putpixel((x, y), (220, 246, 255, 200))
wisp.save(f'{out_dir}/lantern_wisp.png')

if preview:
    sheet = Image.new('RGBA', (16 * 2 * 12 + 24, 16 * 12 + 16), (40, 42, 50, 255))
    sheet.paste(bridle.resize((192, 192), Image.NEAREST), (8, 8), bridle.resize((192, 192), Image.NEAREST))
    sheet.paste(wisp.resize((192, 192), Image.NEAREST), (16 + 192, 8), wisp.resize((192, 192), Image.NEAREST))
    sheet.save(preview)
