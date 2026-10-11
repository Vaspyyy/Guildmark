"""Relic cache item textures: a small iron-banded chest with a wax seal coloured by its tier."""
import sys
from PIL import Image
out_dir = sys.argv[1]
rows = [
 "................",
 "................",
 "................",
 "..oooooooooooo..",
 ".oWwWWwIIWWwWWo.",
 ".oWwWwWIIwWWwWo.",
 ".owwwwwIIwwwwwo.",
 ".oIIIIISSIIIIIo.",
 ".oWWwWSssSWwWWo.",
 ".oWwWWSssSWWwWo.",
 ".oWWwWWSSWWWwWo.",
 ".owwWwWIIwWwwwo.",
 ".oIIIIIIIIIIIIo.",
 "..oooooooooooo..",
 "................",
 "................",
]
BASE = {'o': (40, 26, 18), 'W': (150, 104, 60), 'w': (116, 78, 44), 'I': (176, 176, 186)}
SEALS = {
    'common': ((216, 216, 216), (150, 150, 150)),
    'uncommon': ((110, 220, 110), (52, 150, 52)),
    'rare': ((110, 170, 255), (46, 100, 200)),
    'epic': ((200, 140, 255), (128, 70, 200)),
    'legendary': ((255, 206, 90), (210, 140, 20)),
}
for name, (hi, lo) in SEALS.items():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    colors = dict(BASE, S=lo, s=hi)
    for y, r in enumerate(rows):
        for x, c in enumerate(r):
            if c in colors: img.putpixel((x, y), colors[c] + (255,))
    img.save(f"{out_dir}/relic_cache_{name}.png")
