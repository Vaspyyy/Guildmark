"""Hollow Soldier: a foot soldier of the Hollow King's old army, risen as a husk. A dented kettle hat over a
rusted mail coif, a gaunt grey-blue face with two cold eye points in the brim's shadow, rusted mail and a torn
crimson tabard with the king's faded crown. Sword and shield come from the vanilla held-item layer, so the
model has no weapon. Writes the texture, the eye glow map, a .bbmodel, a preview sheet and the Kotlin body."""
import os, sys, json, random
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from modelkit import *
from PIL import Image
import numpy as np

SKIN = (110, 124, 132); SKIN_D = (84, 95, 105); SKIN_L = (138, 150, 156); SHADOW = (56, 62, 72); HOLLOW = (20, 20, 28)
TEETH = (142, 136, 112); EYE = (172, 228, 255, 255); CL = (0, 0, 0, 0)
MAIL = (82, 82, 86); MAIL_L = (104, 104, 108); MAIL_D = (56, 56, 62); RUST = (98, 66, 48); RUST_D = (74, 50, 38)
IRON = (78, 80, 88); IRON_L = (110, 112, 120); IRON_D = (52, 52, 60)
CRIM = (88, 22, 30); CRIM_D = (60, 15, 23); CRIM_L = (110, 32, 38); FADED = (138, 108, 58); FADED_D = (100, 76, 42)
LEATHER = (78, 54, 36); LEATHER_D = (54, 37, 26); BRASS = (140, 116, 66)
CLOTH = (74, 66, 60); CLOTH_D = (54, 48, 46); BOOT = (64, 46, 34); BOOT_D = (40, 30, 24)
WOOD = (104, 80, 54); WOOD_D = (70, 52, 36); FLETCH = (150, 146, 134)

P = {'S': SKIN, 's': SKIN_D, 'h': SKIN_L, 'd': SHADOW, 'D': HOLLOW, 'T': TEETH, 'E': EYE, '.': CL,
     'M': MAIL, 'm': MAIL_L, 'k': MAIL_D, 'r': RUST, 'R': RUST_D, 'I': IRON, 'i': IRON_L, 'j': IRON_D,
     'C': CRIM, 'c': CRIM_D, 'q': CRIM_L, 'Y': FADED, 'y': FADED_D, 'L': LEATHER, 'l': LEATHER_D, 'B': BRASS,
     'P': CLOTH, 'p': CLOTH_D, 'O': BOOT, 'o': BOOT_D, 'W': WOOD, 'w': WOOD_D, 'F': FLETCH}

lay = random.Random(3)  # where rust and wear fall; the kit's own rnd does the grain

def mail(w, h, rust=0.025, phase=0):
    """Rows of riveted mail: a lattice of dark ring holes, staggered every other row, rust spotted through it."""
    rows = []
    for j in range(h):
        jj = j + phase
        r = ''
        for i in range(w):
            if jj % 2 == 0: c = 'M' if lay.random() > 0.15 else 'm'
            else: c = 'k' if (i + jj // 2) % 2 == 0 else 'M'
            if lay.random() < rust: c = 'R' if c == 'k' else 'r'
            r += c
        rows.append(r)
    return rows

def over(base, top):
    """Lay the rows of top over base; '_' keeps the base character."""
    return [''.join(b if t == '_' else t for b, t in zip(br, tr)) for br, tr in zip(base, top)]

def mirror(rows): return [r[::-1] for r in rows]

# ---- head: a husk's face framed by a mail coif, under a dented kettle hat ----
face = grid({
    'front': ["kMkMkMkM", "MMMMMMMM", "kkkkkkkk", "kDDddDDk", "MDEssEDM", "MshDDhsM", "MdTTTTdM", "kMsddsMk"],
    'right': over(mail(8, 8), ["________"] * 3 + ["kkkkkkkk"] + ["________"] * 4),
    'left': over(mail(8, 8), ["________"] * 3 + ["kkkkkkkk"] + ["________"] * 4),
    'back': over(mail(8, 8, phase=1), ["________"] * 3 + ["kkkkkkkk"] + ["________"] * 4),
    'top': mail(8, 8), 'bottom': mail(8, 8),
}, P, default=fill(MAIL, 5))
dome = grid({
    'front': ["iiiijiiii", "IIIIiIIjI", "jjIIIIjRj"], 'back': ["iiiiiiiji", "IIRIIIIiI", "jjjjjjIjj"],
    'right': ["iiiiiiiii", "IIIIjIIII", "jjjjiRjjj"], 'left': ["iiijiiiii", "IIIiIIIII", "jjjjjjjRj"],
    'top': ["iiiiiiiii"] * 9,
}, P, default=fill(IRON_D, 4))
cap = grid({'top': ["IIIiIII", "IIIiIII", "IjIiIII", "IIIiIIR", "IIIiIII", "IIIiIjI", "IIIiIII"],
            'front': ["iiiiiii"], 'back': ["iiiiiii"], 'right': ["iiiiiii"], 'left': ["iiiiiii"]}, P, default=fill(IRON, 4))
brim_top = ["iiiiiiiiiiii", "iIIIIIIIIIRi", "iIRIIIIIIIIi", "iIIIIIIIIIIi", "iIIIIIIIIIIi", "iIIIIIIIIIIi",
            "iIIIIIIIIIIi", "iIIIIIIIIIIi", "iIIIIIIIIjIi", "iIjIIIIIIIIi", "iIIIIRIIIIIi", "iiiiiiiijiii"]
brim = grid({'top': brim_top, 'bottom': ["jjjjjjjjjjjj"] * 12,
             'front': ["iiiRiiiiijii"], 'back': ["iiiiiiiiRiii"], 'right': ["iiiijiiiiiRi"], 'left': ["iiiiiiRiiiii"]},
            P, default=fill(IRON_D, 4))

# ---- body: rusted mail under a crimson tabard bearing the king's crown, cinched by a belt ----
torso = grid({
    'front': over(mail(8, 12), ["________", "cCCCCCCc", "CCCCCCCC", "CYCYYCYC", "CYYYYYYC", "CyYyyYyC",
                                "CCCCCCCC", "CCqCCCM_", "cCCCCCCc", "LLLLLLLL", "________", "________"]),
    'back': over(mail(8, 12, phase=1), ["________", "cCCCCCCc", "CCCCCCCC", "CCCCCqCC", "CCCCCCCC", "CCCCCCCC",
                                        "CCqCCCCC", "CCCCCCcC", "cCCCCCCc", "LLLLLLLL", "________", "________"]),
    'right': over(mail(4, 12), ["____"] * 9 + ["LLLL", "____", "____"]),
    'left': over(mail(4, 12), ["____"] * 9 + ["LLLL", "____", "____"]),
}, P, default=fill(MAIL, 6))
belt = grid({'front': ["LLLBBLLL"], 'back': ["LLLLLLLL"], 'right': ["LlLL"], 'left': ["LLlL"],
             'top': ["llllllll"] * 4, 'bottom': ["llllllll"] * 4}, P, default=fill(LEATHER, 4))
# The hanging flap: torn hem, the inside darker. Back and side faces mirror the front's holes so they line up.
flap_front = ["ccccccc", "CCCCCCC", "CCCCCCC", "CCCqCCC", "CCCCCCC", "CCCCCcC", "YyYYyYY", "CC.CCcC", "C..C.C."]
flap = grid({'front': flap_front, 'back': mirror([r.replace('C', 'c').replace('q', 'c').replace('Y', 'y') for r in flap_front]),
             'right': [r[0] for r in flap_front], 'left': [r[-1] for r in flap_front],
             'top': ["ccccccc"], 'bottom': ["......."]}, P, default=fill(CRIM_D, 4))

# ---- limbs: mail sleeves, bare dead forearms with leather bracers; ragged trousers and boots ----
arm_rows = mail(3, 6) + ["MkM", "SsS", "LLL", "lLl", "SSs", "sss"]
arm = lambda: grid({'front': arm_rows, 'back': arm_rows, 'left': arm_rows, 'right': arm_rows,
                    'top': ["MMM"] * 3, 'bottom': ["sss"] * 3}, P, default=fill(SKIN_D, 4))
spaulder = grid({'top': ["iiiii", "iIIIi", "iIrIi", "iIIIi", "jIIIj"], 'front': ["iiiii", "IIrII", "jjRjj"],
                 'back': ["iiiii", "IIIII", "jjjjj"], 'right': ["iiiii", "IIrII", "jjjjj"], 'left': ["iiiii", "IIIII", "jjjjj"],
                 'bottom': ["jjjjj"] * 5}, P, default=fill(IRON, 4))
leg_rows = ["PPPP", "PpPP", "PPPP", "LLLL", "PPpP", "PPPP", "pPPp", "PPPP", "lLlL", "OOOO", "OOOO", "oooo"]
leg_front = ["PPPP", "PpPP", "PPPP", "LLLL", "PPpP", "pSsP", "PSSp", "PPPP", "lLlL", "OOOO", "OOOO", "oooo"]
leg = grid({'front': leg_front, 'back': leg_rows, 'left': leg_rows, 'right': leg_rows}, P, default=fill(CLOTH, 5))
boot = grid({'front': ["llll", "OOOO", "oooo"], 'back': ["llll", "OOOO", "oooo"],
             'right': ["llll", "OlOO", "oooo"], 'left': ["llll", "OOlO", "oooo"],
             'top': ["llll"] * 4, 'bottom': ["oooo"] * 4}, P, default=fill(BOOT, 4))

# Two arrows from the war it died in, still lodged in its back: one whole, one snapped short
shaft = grid({'right': ["FFWWww"], 'left': ["wwWWFF"], 'top': ["F", "F", "W", "W", "w", "w"], 'bottom': ["w", "w", "W", "W", "F", "F"],
              'back': ["w"]}, P, default=fill(WOOD_D, 4))
stub = grid({'right': ["WWww"], 'left': ["wwWW"], 'top': ["W", "W", "w", "w"], 'bottom': ["w", "w", "W", "W"], 'back': ["F"]},
            P, default=fill(WOOD_D, 4))

def C(o, s, p, name=None, **kw): return Cube(o, s, p, name=name, **kw)

head = Part('head', cubes=[
    C((-4, -8, -4), (8, 8, 8), face, 'face'), C((-4.5, -9, -4.5), (9, 3, 9), dome, 'dome'),
    C((-3.5, -10, -3.5), (7, 1, 7), cap, 'cap'), C((-6, -6, -6), (12, 1, 12), brim, 'brim'),
], children=[Part('hat')])
body = Part('body', cubes=[C((-4, 0, -2), (8, 12, 4), torso, 'torso'), C((-4, 9, -2), (8, 1, 4), belt, 'belt', inflate=0.35)],
            children=[Part('tabard', (0, 10, -2.2), cubes=[C((-3.5, 0, -1), (7, 9, 1), flap, 'flap')]),
                      Part('arrow', (1.5, 3.5, 2), (28, 18, 0), cubes=[C((-0.5, -0.5, -1), (1, 1, 6), shaft, 'shaft')]),
                      Part('arrow_stub', (-2.5, 7, 2), (10, -16, 0), cubes=[C((-0.5, -0.5, -1), (1, 1, 4), stub, 'stub')])])
right_arm = Part('right_arm', (-5, 2, 0), cubes=[C((-2.5, -2, -1.5), (3, 12, 3), arm(), 'rarm'),
                                                  C((-3.5, -2.5, -2.5), (5, 3, 5), spaulder, 'rspaulder')])
left_arm = Part('left_arm', (5, 2, 0), cubes=[C((-0.5, -2, -1.5), (3, 12, 3), arm(), 'larm'),
                                               C((-1.5, -2.5, -2.5), (5, 3, 5), spaulder, 'lspaulder')])
# Leg cubes sit 0.1 off their pivots so the legs meet at x=0 instead of overlapping; the boots differ by a hair
right_leg = Part('right_leg', (-1.9, 12, 0), cubes=[C((-2.1, 0, -2), (4, 12, 4), leg, 'rleg'), C((-2.1, 9, -2), (4, 3, 4), boot, 'rboot', inflate=0.25)])
left_leg = Part('left_leg', (1.9, 12, 0), cubes=[C((-1.9, 0, -2), (4, 12, 4), leg, 'lleg'),
                                                 C((-1.9, 9, -2), (4, 3, 4), boot, 'lboot', inflate=0.3)])

model = Model('hollow_soldier', 128, 64, [head, body, right_arm, left_arm, right_leg, left_leg])
model.pack()
tex = model.paint()
# Glow map: just the exact eye colour
arr = np.array(tex)
mask = np.all(arr == np.array(EYE), axis=-1)
glow = np.zeros_like(arr); glow[mask] = arr[mask]
glow = Image.fromarray(glow, 'RGBA')

def main(out, prev, art):
    tex.save(f'{out}/textures/entity/hollow_soldier.png'); glow.save(f'{out}/textures/entity/hollow_soldier_glow.png')
    open(f'{prev}/hollow_soldier.kt.txt', 'w').write(model.kotlin())
    json.dump(model.bbmodel(tex, 'hollow_soldier'), open(f'{art}/hollow_soldier.bbmodel', 'w'))
    views = [model.render(tex, yaw=y, glow=glow) for y in (25, 200, -70)]
    sheet = Image.new('RGBA', (768, 320))
    for i, v in enumerate(views): sheet.paste(v, (i * 256, 0))
    sheet.save(f'{prev}/hollow_soldier_preview.png'); sheet.save(f'{art}/hollow_soldier_preview.png')
    tex.resize((512, 256), Image.NEAREST).save(f'{prev}/hollow_soldier_tex.png')


# Importable (region_hollows.py builds on the soldier's parts and paint); run it to write the soldier's files
if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
