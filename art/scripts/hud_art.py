"""Combat HUD sprites: dark iron frames with a tarnished-gold inner line, bar fills, the lock-on reticle and the
stance-broken icon. hud_art.py <sprites/hud dir>"""
import sys, os, json
from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

CL = (0, 0, 0, 0)
OUT = (12, 11, 14, 235)        # outer outline, a touch see-through so frames sit lightly on the world
IRON_L = (84, 82, 92, 255); IRON = (58, 56, 64, 255); IRON_D = (38, 36, 42, 255)
GOLD_L = (198, 162, 84, 255); GOLD = (152, 120, 58, 255); GOLD_D = (106, 82, 40, 255)
INSIDE = (8, 7, 10, 150)       # the empty part of a bar: dark, the world faintly through it

def save(img, name, meta=None):
    img.save(f'{out}/{name}.png')
    if meta is not None:
        with open(f'{out}/{name}.png.mcmeta', 'w') as f:
            json.dump(meta, f, indent=2); f.write('\n')

def nine(w, h, border): return {'gui': {'scaling': {'type': 'nine_slice', 'width': w, 'height': h, 'border': border}}}
STRETCH = {'gui': {'scaling': {'type': 'stretch'}}}

def from_rows(rows, pal):
    img = Image.new('RGBA', (len(rows[0]), len(rows)), CL)
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            img.putpixel((x, y), pal[ch])
    return img

PAL = {'.': CL, 'o': OUT, 'i': IRON_L, 'I': IRON, 'd': IRON_D, 'J': GOLD_L, 'G': GOLD, 'g': GOLD_D, '#': INSIDE}

# ---- bar_frame 32x9, border 3: outline, iron, gold line, 3px dark interior ----
def frame_rows(w, h, inner_rows):
    top = ['.' + 'o' * (w - 2) + '.', 'o' + 'i' * (w - 2) + 'o', 'oI' + 'J' * (w - 4) + 'Io']
    mid = ['oIG' + '#' * (w - 6) + 'GIo' for _ in range(inner_rows)]
    bot = ['oI' + 'g' * (w - 4) + 'Io', 'o' + 'd' * (w - 2) + 'o', '.' + 'o' * (w - 2) + '.']
    return top + mid + bot
rows = frame_rows(32, 9, 3)
rows[4] = 'oGG' + rows[4][3:-3] + 'GGo'   # gold rivets on the end caps
save(from_rows(rows, PAL), 'bar_frame', nine(32, 9, 3))

# ---- fills: a lighter top row, a darker bottom row; stretched, so rows stay flat ----
def fill(name, top, mid, bot, w=16, h=3):
    img = Image.new('RGBA', (w, h))
    bands = [top] + [mid] * (h - 2) + [bot]
    for y in range(h):
        for x in range(w):
            img.putpixel((x, y), bands[y] + (255,))
    save(img, name, STRETCH)
fill('bar_health', (186, 52, 46), (146, 30, 32), (98, 18, 24))
fill('bar_focus', (78, 110, 206), (48, 74, 168), (30, 44, 114))
fill('bar_stamina', (110, 172, 84), (72, 136, 58), (44, 92, 40))
fill('bar_trail', (244, 226, 160), (216, 192, 118), (172, 146, 84))
fill('bar_poison', (178, 192, 70), (138, 152, 44), (92, 104, 30))
fill('bar_wither', (104, 98, 100), (60, 56, 60), (32, 30, 34))
fill('boss_fill', (176, 48, 44), (140, 28, 30), (118, 22, 26), h=5)
fill('boss_trail', (244, 226, 160), (216, 192, 118), (176, 150, 88), h=5)
fill('enemy_fill', (178, 50, 44), (138, 30, 32), (92, 18, 24), w=8)

# ---- boss_frame 64x11, border 4: heavier, with clasped ends around a 5px interior ----
def boss_rows(w):
    m = w - 8
    rows = [
        '..oo' + 'o' * m + 'oo..',
        '.oii' + 'i' * m + 'iio.',
        'oiGJ' + 'J' * m + 'JGio',
        'oGJo' + '#' * m + 'oJGo',
        'oJGo' + '#' * m + 'oGJo',
        'GJgo' + '#' * m + 'ogJG',
        'oJGo' + '#' * m + 'oGJo',
        'oGgo' + '#' * m + 'ogGo',
        'odGg' + 'g' * m + 'gGdo',
        '.odd' + 'd' * m + 'ddo.',
        '..oo' + 'o' * m + 'oo..',
    ]
    return rows
save(from_rows(boss_rows(64), PAL), 'boss_frame', nine(64, 11, 4))

# ---- enemy_frame 24x5, border 2: a thin outline around a 3px interior ----
erows = ['.' + 'o' * 22 + '.', 'o' + '#' * 22 + 'o', 'G' + '#' * 22 + 'G', 'o' + '#' * 22 + 'o', '.' + 'o' * 22 + '.']
save(from_rows(erows, PAL), 'enemy_frame', nine(24, 5, 2))

# ---- lock_on 11x11: a white diamond with a soft dark outline ----
W_ = (255, 255, 255, 255); W2 = (214, 216, 224, 255); SH = (10, 10, 14, 210); SOFT = (10, 10, 14, 90)
img = Image.new('RGBA', (11, 11), CL)
for y in range(11):
    for x in range(11):
        d = abs(x - 5) + abs(y - 5)
        if d <= 2: img.putpixel((x, y), W_ if (y < 5 or (y == 5 and x <= 5)) else W2)
        elif d == 3: img.putpixel((x, y), SH)
        elif d == 4: img.putpixel((x, y), SOFT)
save(img, 'lock_on')

# ---- stance_broken 11x11: a gold diamond split by a zigzag crack, chips flying off where it broke ----
srows = [
    ".....o..oo.",
    "....oWo.oWo",
    "...oWLoo.o.",
    "..oWLLoMo..",
    ".oWLLoMMDo.",
    "oWLLMoMMMDo",
    ".oLMoMMMDo.",
    "..oMoMDDo..",
    ".o.ooMDo...",
    "oLo.oDo....",
    ".oo..o.....",
]
SP = {'.': CL, 'o': (14, 12, 14, 235), 'W': (255, 248, 222, 255), 'L': (236, 200, 104, 255), 'M': (204, 160, 62, 255),
      'D': (140, 100, 36, 255)}
save(from_rows(srows, SP), 'stance_broken')
# ---- flask_slot 24x24: the quick slot for the Warden's Flask, the bar frames' iron and gold round a dark well ----
frows = frame_rows(24, 24, 18)
for y in (11, 12):
    frows[y] = 'oGG' + frows[y][3:-3] + 'GGo'   # gold rivets on the sides
save(from_rows(frows, PAL), 'flask_slot')

print('written to', out)
