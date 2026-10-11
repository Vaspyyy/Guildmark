import sys, json
sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0])
from modelkit import *
from PIL import Image
import numpy as np

BONE = (222, 214, 190); BONE_D = (160, 150, 128); DARK = (34, 24, 40)
ROBE = (62, 32, 90); ROBE_D = (40, 20, 60); GOLD = (220, 176, 64); GOLD_D = (150, 108, 32)
IRON = (66, 66, 80); IRON_L = (112, 112, 128); LINING = (120, 28, 60)
GLOW = (196, 120, 255, 255); GLOW2 = (240, 200, 255, 255); CLEAR = (0, 0, 0, 0)
LEATHER = (70, 44, 28); STEEL = (44, 38, 58)

P = {'B': BONE, 'b': BONE_D, 'D': DARK, 'E': DARK, 'g': GLOW, 'G': GLOW2, 'N': (60, 50, 50), 'T': (240, 234, 214),
     'R': BONE, 'S': BONE_D, 'H': GLOW, 'h': (120, 60, 170), 'O': ROBE, 'o': ROBE_D, 'Y': GOLD, 'y': GOLD_D,
     'I': IRON, 'i': IRON_L, 'L': LINING, '.': CLEAR, 'K': STEEL, 'k': (70, 60, 92), 'l': LEATHER}

skull = grid({
    'front': ["BBBBBBBB", "BbBBBBbB", "BEEBBEEB", "BEgBBgEB", "BbBNNBbB", "BBBBBBBB", "BTDTDTDB", "bBBBBBBb"],
    'back': ["BBBBBBBB", "BBBbBBBB", "BBBBBbBB", "BbBBBBBB", "BBBBBBbB", "BBbBBBBB", "BBBBBBBB", "bbBBBBbb"],
    'top': ["BBBBBBBB", "BBbBBBBB", "BBBBBBbB", "BBBBBBBB", "BbBBBBBB", "BBBBbBBB", "BBBBBBBB", "BBBBBBBB"],
}, P, default=fill(BONE, 5))
jaw = grid({'front': ["BTDTDB", "bBBBBb"]}, P, default=fill(BONE_D, 4))
ribs = grid({
    'front': ["RRRSSRRR", "DDDSSDDD", "RRhHHhRR", "DDHGGHDD", "RRhHHhRR", "DDDSSDDD", "RRRSSRRR", "DDDSSDDD"],
    'back': ["RRRSSRRR", "DDDSSDDD", "RRRSSRRR", "DDDSSDDD", "RRRSSRRR", "DDDSSDDD", "RRRSSRRR", "DDDSSDDD"],
    'left': ["RRRR", "DDDD", "RRRR", "DDDD", "RRRR", "DDDD", "RRRR", "DDDD"],
    'right': ["RRRR", "DDDD", "RRRR", "DDDD", "RRRR", "DDDD", "RRRR", "DDDD"],
}, P, default=fill(BONE, 4))
robe = grid({
    'front': ["YYYYYYYYY", "ooooYoooo", "OOOOYOOOO", "OOOOYOOOO", "OOOOYOOOO", "OOOOYOOOO", "OOOOYOOOO", "OOOOYOOOO",
              "OOOOYOOOO", "OOOOYOOOO", "YYYYYYYYY", "O.OO.OO.O"],
    'back': ["YYYYYYYYY", "ooooooooo", "OOOOOOOOO", "OOOOOOOOO", "OOOOOOOOO", "OOOOOOOOO", "OOOOOOOOO", "OOOOOOOOO",
             "OOOOOOOOO", "OOOOOOOOO", "YYYYYYYYY", "O.OO.O.OO"],
    'left': ["YYYYY", "ooooo", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "YYYYY", "O.O.O"],
    'right': ["YYYYY", "ooooo", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "OOOOO", "YYYYY", "O.O.O"],
}, P, default=fill(ROBE_D, 4))
collar = grid({
    'front': ["YyYyYyYyYy", "oOoOoOoOoO"], 'back': ["YyYyYyYyYy", "oOoOoOoOoO"],
    'top': ["oOoOoOoOoO", "OoOoOoOoOo", "oO......Oo", "OoOoOoOoOo", "oOoOoOoOoO"],
}, P, default=fill(ROBE, 6))
# The cape: royal purple outside, crimson lining inside, frayed at the hem
cape_rows_out = ["YYYYYYYYYY"] + ["OOOOOOOOOO"] * 2 + ["OOOOyOOOOO", "OOOyYyOOOO", "OOOOyOOOOO"] + ["OOOOOOOOOO"] * 12 + ["oOOoOOoOOo", "O.OO.OO.OO", ".O..O..O.O"]
cape_rows_in = ["YYYYYYYYYY"] + ["LLLLLLLLLL"] * 17 + ["LLLLLLLLLL", "L.LL.LL.LL", ".L..L..L.L"]
cape = grid({'back': cape_rows_out, 'front': cape_rows_in, 'left': ["O"] * 18 + ["O", ".", "."], 'right': ["O"] * 18 + ["O", ".", "."]}, P, default=fill(ROBE_D, 3))
arm = grid({
    'front': ["BB", "BB", "BB", "BB", "BB", "bb", "BB", "BB", "BB", "BB", "BB", "bb"],
    'back': ["BB", "BB", "BB", "BB", "BB", "bb", "BB", "BB", "BB", "BB", "BB", "bb"],
}, P, default=fill(BONE, 5))
pauldron = grid({
    'top': ["IIIII", "IiiiI", "IiIiI", "IiiiI", "IIIII"],
    'front': ["iiiii", "IIIII", "YYYYY"], 'back': ["iiiii", "IIIII", "YYYYY"],
    'left': ["iiiii", "IIIII", "YYYYY"], 'right': ["iiiii", "IIIII", "YYYYY"],
}, P, default=fill(IRON, 4))
spike = fill(IRON_L, 6)
leg = fill(BONE, 6)
greave = grid({'front': ["iii", "IYI", "III", "III", "kkk"], 'left': ["iii", "III", "III", "III", "kkk"],
               'right': ["iii", "III", "III", "III", "kkk"], 'back': ["iii", "III", "III", "III", "kkk"]}, P, default=fill(IRON, 4))
band = grid({
    'front': ["YyYgYyYgY", "yyyyyyyyy"], 'back': ["YyYgYyYgY", "yyyyyyyyy"],
    'left': ["YyYgYyYgY", "yyyyyyyyy"], 'right': ["YyYgYyYgY", "yyyyyyyyy"],
}, P, default=fill(GOLD, 6))
spike_gem = grid({'front': ["G", "g", "Y", "y"], 'back': ["G", "g", "Y", "y"], 'left': ["G", "g", "Y", "y"],
                  'right': ["G", "g", "Y", "y"], 'top': ["G"]}, P, default=fill(GOLD, 4))
grip = fill(LEATHER, 5); pommel = fill(GOLD, 8); guard = grid({'front': ["yYYYYy"], 'back': ["yYYYYy"]}, P, default=fill(GOLD, 6))
blade = grid({
    'left': ["kKk"] + ["KgK", "KKK", "KKK"] * 5, 'right': ["kKk"] + ["KgK", "KKK", "KKK"] * 5,
    'front': ["k"] * 16, 'back': ["k"] * 16,
}, P, default=fill(STEEL, 4))
tip = fill(STEEL, 4)

def C(o, s, p, name=None, **kw): return Cube(o, s, p, name=name, **kw)

crown = Part('crown', (0, -8, 0), cubes=[
    C((-4.5, -1, -4.5), (9, 2, 9), band, 'band'),
    C((-0.5, -5, -4.75), (1, 4, 1), spike_gem), C((-3.5, -4, -4.75), (1, 3, 1), spike_gem), C((2.5, -4, -4.75), (1, 3, 1), spike_gem),
    C((-4.75, -4, -0.5), (1, 3, 1), spike_gem), C((3.75, -4, -0.5), (1, 3, 1), spike_gem),
    C((-3, -4, 3.75), (1, 3, 1), spike_gem), C((2, -4, 3.75), (1, 3, 1), spike_gem),
])
head = Part('head', (0, 0, 0), cubes=[C((-4, -8, -4), (8, 8, 8), skull, 'skull'), C((-3, -1, -4.5), (6, 2, 2), jaw)],
            children=[Part('hat'), crown])
body = Part('body', (0, 0, 0), cubes=[
    C((-4, 0, -2), (8, 8, 4), ribs, 'ribs'), C((-1, 8, -1), (2, 3, 2), fill(BONE_D, 4)),
    C((-4.5, 8, -2.5), (9, 12, 5), robe, 'robe'), C((-5, -0.5, -2.5), (10, 2, 5), collar, 'collar'),
], children=[Part('cape', (0, 0.5, 2.5), (6, 0, 0), cubes=[C((-5, 0, 0), (10, 21, 1), cape, 'cape')])])
sword = Part('sword', (0, 9.5, 0), (-70, 0, 0), cubes=[
    C((-0.5, -2, -0.5), (1, 4, 1), grip), C((-1, -3, -1), (2, 1, 2), pommel), C((-3, 2, -1), (6, 1, 2), guard),
    C((-0.5, 3, -1.5), (1, 16, 3), blade, 'blade'), C((-0.5, 19, -1), (1, 2, 2), tip),
])
right_arm = Part('right_arm', (-5, 2, 0), cubes=[
    C((-1, -2, -1), (2, 12, 2), arm), C((-3, -3, -2.5), (5, 3, 5), pauldron), C((-1, -5, -0.5), (1, 2, 1), spike),
], children=[sword])
left_arm = Part('left_arm', (5, 2, 0), cubes=[
    C((-1, -2, -1), (2, 12, 2), arm), C((-2, -3, -2.5), (5, 3, 5), pauldron), C((0, -5, -0.5), (1, 2, 1), spike),
])
right_leg = Part('right_leg', (-2, 12, 0), cubes=[C((-1, 0, -1), (2, 12, 2), leg), C((-1.5, 7, -2), (3, 5, 3.5), greave)])
left_leg = Part('left_leg', (2, 12, 0), cubes=[C((-1, 0, -1), (2, 12, 2), leg), C((-1.5, 7, -2), (3, 5, 3.5), greave)])

model = Model('hollow_king', 128, 64, [head, body, right_arm, left_arm, right_leg, left_leg])
model.pack()
tex = model.paint()
# Glow map: just the exact glow colours
arr = np.array(tex)
mask = np.zeros(arr.shape[:2], bool)
for g in (GLOW, GLOW2):
    mask |= np.all(arr == np.array(g), axis=-1)
glow = np.zeros_like(arr); glow[mask] = arr[mask]
glow = Image.fromarray(glow, 'RGBA')

out = sys.argv[1]; prev = sys.argv[2]
tex.save(f'{out}/textures/entity/hollow_king.png'); glow.save(f'{out}/textures/entity/hollow_king_glow.png')
open(f'{prev}/hollow_king.kt.txt', 'w').write(model.kotlin())
json.dump(model.bbmodel(tex, 'hollow_king'), open(f'{sys.argv[3]}/hollow_king.bbmodel', 'w'))
a = model.render(tex, yaw=25, glow=glow); b = model.render(tex, yaw=200, glow=glow); c = model.render(tex, yaw=-70, glow=glow)
sheet = Image.new('RGBA', (768, 320)); sheet.paste(a, (0, 0)); sheet.paste(b, (256, 0)); sheet.paste(c, (512, 0))
sheet.save(f'{prev}/hollow_king_preview.png')
tex.resize((512, 256), Image.NEAREST).save(f'{prev}/hollow_king_tex.png')
