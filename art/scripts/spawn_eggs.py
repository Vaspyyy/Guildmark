"""Spawn egg textures for the new creatures, recoloured from vanilla's zombie egg: helm on top, a band of
colour, and dark below, each mapped through its own palette by brightness, with a glowing visor slit.

    python3 art/scripts/spawn_eggs.py <zombie_spawn_egg.png from the game jar> src/main/resources/assets/guildmark/textures/item
"""
import sys
from PIL import Image

src, out_dir = sys.argv[1], sys.argv[2]
egg = Image.open(src).convert('RGBA')

def cls(p):
    r, g, b, a = p
    if a == 0: return None
    if r < 40 and g < 40 and b < 50: return 'line'
    if b > r + 40 and b > g + 40: return 'low'
    if r < 20 and g > 60: return 'band'
    return 'top'

def lum(p): return 0.3 * p[0] + 0.59 * p[1] + 0.11 * p[2]

ranges = {}
for p in egg.getdata():
    c = cls(p)
    if c and c != 'line':
        lo, hi = ranges.get(c, (999, -1))
        ranges[c] = (min(lo, lum(p)), max(hi, lum(p)))

def ramp(stops, t):
    t = max(0.0, min(1.0, t))
    n = len(stops) - 1
    i = min(int(t * n), n - 1)
    f = t * n - i
    return tuple(round(stops[i][k] + (stops[i + 1][k] - stops[i][k]) * f) for k in range(3))

def make(name, palettes, eyes=None):
    im = Image.new('RGBA', egg.size)
    for y in range(egg.size[1]):
        for x in range(egg.size[0]):
            p = egg.getpixel((x, y))
            c = cls(p)
            if c is None: continue
            if c == 'line':
                im.putpixel((x, y), p); continue
            lo, hi = ranges[c]
            t = (lum(p) - lo) / max(1e-6, hi - lo)
            im.putpixel((x, y), (*ramp(palettes[c], t), 255))
    if eyes:
        for (x, y), col in eyes.items():
            im.putpixel((x, y), (*col, 255))
    im.save(f'{out_dir}/{name}.png')
    return im

STEEL = [(46, 48, 56), (88, 92, 104), (138, 142, 152)]
TABARD = [(70, 22, 24), (112, 36, 34), (146, 58, 50)]
MAIL = [(30, 30, 36), (52, 52, 60), (70, 70, 80)]
make('hollow_soldier_spawn_egg', {'top': STEEL, 'band': TABARD, 'low': MAIL},
     {(6, 6): (20, 20, 28), (7, 6): (150, 200, 230), (8, 6): (20, 20, 28), (9, 6): (150, 200, 230), (10, 6): (20, 20, 28)})

DARK = [(30, 31, 38), (56, 58, 68), (86, 90, 102)]
GOLD = [(96, 72, 34), (150, 118, 56), (196, 162, 88)]
CLOAK = [(36, 44, 40), (58, 72, 64), (80, 96, 86)]
make('fallen_warden_spawn_egg', {'top': DARK, 'band': GOLD, 'low': CLOAK},
     {(6, 6): (14, 14, 22), (7, 6): (176, 160, 255), (8, 6): (14, 14, 22), (9, 6): (176, 160, 255), (10, 6): (14, 14, 22)})

MOSS = [(52, 64, 50), (88, 104, 78), (128, 142, 108)]
HIDE = [(70, 48, 30), (104, 74, 46), (138, 104, 66)]
EARTH = [(34, 30, 26), (56, 50, 42), (78, 70, 58)]
make('hill_troll_spawn_egg', {'top': MOSS, 'band': HIDE, 'low': EARTH},
     {(6, 6): (24, 28, 22), (7, 6): (220, 70, 46), (8, 6): (24, 28, 22), (9, 6): (220, 70, 46), (10, 6): (24, 28, 22)})

BLACKSTEEL = [(22, 22, 28), (44, 46, 56), (72, 76, 90)]
LANTERN = [(36, 64, 104), (78, 128, 178), (150, 200, 236)]
HIDE_BLACK = [(18, 16, 20), (34, 30, 36), (54, 50, 58)]
make('dusk_rider_spawn_egg', {'top': BLACKSTEEL, 'band': LANTERN, 'low': HIDE_BLACK},
     {(6, 6): (10, 10, 16), (7, 6): (176, 232, 255), (8, 6): (10, 10, 16), (9, 6): (176, 232, 255), (10, 6): (10, 10, 16)})
