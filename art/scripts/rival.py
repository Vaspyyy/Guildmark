"""Rival guild adventurers: one humanoid model with each guild's headgear and a tabard in its colours.
Captains add a cape and pauldrons. Writes textures, a .bbmodel, a preview sheet and the Kotlin body."""
import sys, json
sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0])
from modelkit import *
from PIL import Image
import numpy as np

def C(o, s, name, **kw): return Cube(o, s, None, name=name, **kw)

head = Part('head', cubes=[C((-4, -8, -4), (8, 8, 8), 'face')], children=[
    Part('hat', cubes=[C((-4, -8, -4), (8, 8, 8), 'hat', inflate=0.5)]),
    Part('hound_helm', cubes=[C((-4.5, -8.5, -4.5), (9, 9, 9), 'helm'), C((-0.5, -11, -3.5), (1, 3, 8), 'plume')]),
    Part('ledger_cap', cubes=[C((-4.5, -9.5, -4.5), (9, 2, 9), 'beret'), C((-5, -8, -5), (10, 1, 10), 'band')],
         children=[Part('quill', (3.5, -9, 1), (0, 0, 25), cubes=[C((0, -5, 0), (1, 5, 1), 'quill')])]),
    Part('stag_crown', cubes=[C((-4.5, -7, -4.5), (9, 1, 9), 'circlet')], children=[
        Part('antler_r', (-3, -7, 0), (0, 0, -25), cubes=[C((-0.5, -6, -0.5), (1, 6, 1), 'antler'), C((-3, -5, -0.5), (3, 1, 1), 'tine'),
                                                         C((-2, -8, -0.5), (1, 2, 1), 'tip')]),
        Part('antler_l', (3, -7, 0), (0, 0, 25), cubes=[C((-0.5, -6, -0.5), (1, 6, 1), 'antler2'), C((0, -5, -0.5), (3, 1, 1), 'tine2'),
                                                       C((1, -8, -0.5), (1, 2, 1), 'tip2')]),
    ]),
    Part('crow_hood', cubes=[C((-4.5, -8.5, -4.5), (9, 9, 9), 'hood'), C((-1, -4, -7.5), (2, 2, 3), 'beak')]),
])
body = Part('body', cubes=[C((-4, 0, -2), (8, 12, 4), 'torso')], children=[
    Part('tabard', cubes=[C((-3.5, 1, -3), (7, 14, 1), 'tabard')]),
    Part('ledger_book', cubes=[C((4, 8, -1.5), (1, 3, 3), 'book')]),
    Part('cape', (0, 0, 2), (6, 0, 0), cubes=[C((-5, 0, 0), (10, 20, 1), 'cape')]),
])
right_arm = Part('right_arm', (-5, 2, 0), cubes=[C((-3, -2, -2), (4, 12, 4), 'rarm')],
                 children=[Part('r_paul', cubes=[C((-3.5, -2.5, -2.5), (5, 4, 5), 'rpaul')])])
left_arm = Part('left_arm', (5, 2, 0), cubes=[C((-1, -2, -2), (4, 12, 4), 'larm')],
                children=[Part('l_paul', cubes=[C((-1.5, -2.5, -2.5), (5, 4, 5), 'lpaul')])])
right_leg = Part('right_leg', (-1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'rleg')])
left_leg = Part('left_leg', (1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'lleg')])

model = Model('rival_adventurer', 128, 128, [head, body, right_arm, left_arm, right_leg, left_leg])
model.pack()

CL = (0, 0, 0, 0)
def limb(rows, P, default):
    return grid({'front': rows, 'back': rows, 'left': rows, 'right': rows}, P, default=default)

def face_rows(hair, eyes='e'):
    return {
        'front': [hair * 8, hair * 8, hair + "SSSSSS" + hair, "SSSSSSSS", f"SW{eyes}SS{eyes}WS", "SSSnnSSS", "SSSmmSSS", "SSSSSSSS"],
        'right': [hair * 8] * 3 + [hair * 4 + "SSSS"] * 5, 'left': [hair * 8] * 3 + ["SSSS" + hair * 4] * 5,
        'back': [hair * 8] * 8, 'top': [hair * 8] * 8,
    }

def tabard_rows(emblem):
    # 7x14 front flap: a border, the guild's emblem near the top, a fringe at the bottom
    rows = ["BBBBBBB"] + ["BTTTTTB"] + [f"BT{r}TB" for r in emblem] + ["BTTTTTB"] * (10 - len(emblem)) + ["TBTBTBT", "T.T.T.T"]
    return rows

def paint_guild(P, face, torso, arms, legs, tabard, cape_main, cape_dark, paul, extra):
    d = {
        'face': grid(face, P, default=fill(P['S'])),
        'hat': fill(CL, 0, False),
        'torso': grid(torso, P, default=fill(P['U'])),
        'rarm': limb(arms, P, fill(P['U'])), 'larm': limb(arms, P, fill(P['U'])),
        'rleg': limb(legs, P, fill(P['O'])), 'lleg': limb(legs, P, fill(P['O'])),
        'tabard': grid({'front': tabard, 'back': ["T" * 7] * 14}, P, default=fill(P['T'], 4)),
        'cape': grid({'back': [cape_main * 10] * 17 + [cape_dark * 10, (cape_main + '.') * 5, ('.' + cape_main) * 5],
                      'front': [cape_dark * 10] * 20}, P, default=fill(P[cape_dark], 4)),
        'rpaul': grid({'top': [paul * 5] * 5}, P, default=fill(P[paul], 5)), 'lpaul': grid({'top': [paul * 5] * 5}, P, default=fill(P[paul], 5)),
    }
    d.update(extra)
    return d

GOLD = (222, 180, 70); GOLDD = (168, 128, 40); BOOT = (70, 50, 34); BOOTD = (44, 32, 24)

# ---------------- The Iron Hounds: steel helms, mail, grey tabard with a white hound ----------------
PH = {'S': (214, 170, 140), 'H': (60, 44, 32), 'W': (240, 240, 240), 'e': (60, 90, 140), 'n': (190, 140, 110), 'm': (150, 90, 80),
      'I': (170, 172, 180), 'i': (120, 122, 132), 'k': (30, 30, 36), 'M': (128, 130, 140), 'm2': (100, 102, 112),
      'T': (92, 94, 102), 'B': (210, 210, 214), 'E': (240, 240, 240), 'U': (128, 130, 140), 'L': (88, 60, 36), 'O': BOOT, 'o': BOOTD,
      'D': (66, 66, 74), 'R': (170, 40, 40), '.': CL, 'G': (128, 130, 140), 'g': (100, 102, 112), 'P': (170, 172, 180)}
hounds = paint_guild(PH, face_rows('H'),
    {'front': ["MMMMMMMM"] * 7 + ["LLLLLLLL"] + ["MMMMMMMM"] * 4, 'back': ["MMMMMMMM"] * 7 + ["LLLLLLLL"] + ["MMMMMMMM"] * 4},
    ["IIII", "MMMM", "MMMM", "MMMM", "MMMM", "MMMM", "MMMM", "MMMM", "IIII", "SSSS", "SSSS", "SSSS"],
    ["DDDD"] * 7 + ["OOOO"] * 4 + ["oooo"],
    tabard_rows(["E.E", "EEE", ".E.", ".E."]), 'G', 'g', 'P',
    {
        'helm': grid({'front': ["IIIIIIIII", "IIIIIIIII", "IiiiIiiiI", "IkkkIkkkI", "IIIIIIIII", "IIIkIkIII", "IIIkIkIII", "IIIIIIIII", "iiiiiiiii"],
                      'back': ["IIIIIIIII"] * 8 + ["iiiiiiiii"], 'left': ["IIIIIIIII"] * 8 + ["iiiiiiiii"],
                      'right': ["IIIIIIIII"] * 8 + ["iiiiiiiii"], 'top': ["IIIIIIIII"] * 9}, PH, default=fill(PH['I'], 5)),
        'plume': fill(PH['R'], 10),
    })

# ---------------- The Scarlet Ledger: red berets with quills, dark coats, crimson tabard with a gold coin ----------------
PS = {'S': (232, 196, 168), 'H': (36, 28, 26), 'W': (245, 245, 245), 'e': (90, 60, 40), 'n': (206, 160, 132), 'm': (170, 80, 80),
      'U': (62, 40, 36), 'u': (44, 28, 26), 'T': (168, 30, 40), 'B': (110, 18, 26), 'E': GOLD, 'Y': GOLDD,
      'L': (30, 22, 20), 'O': BOOTD, 'D': (50, 36, 34), 'C': (230, 220, 200), 'R': (168, 30, 40), 'r': (120, 20, 28),
      'Q': (245, 245, 240), '.': CL, 'P': GOLD}
ledger = paint_guild(PS, face_rows('H'),
    {'front': ["CUUUUUUC", "UCUUUUCU", "UUUUUUUU"] + ["UUUUUUUU"] * 4 + ["LLLELLLL"] + ["UUUUUUUU"] * 3 + ["uuuuuuuu"],
     'back': ["UUUUUUUU"] * 7 + ["LLLLLLLL"] + ["UUUUUUUU"] * 3 + ["uuuuuuuu"]},
    ["UUUU"] * 8 + ["uuuu", "CCCC", "SSSS", "SSSS"],
    ["DDDD"] * 8 + ["OOOO"] * 4,
    tabard_rows([".E.", "EYE", "EYE", ".E."]), 'R', 'r', 'P',
    {
        'beret': grid({'top': ["RRRRRRRRR"] * 9, 'front': ["RRRRRRRRR", "rrrrrrrrr"]}, PS, default=fill(PS['R'], 5)),
        'band': grid({'top': ["rrrrrrrrrr"] * 10}, PS, default=fill(PS['r'], 4)),
        'quill': grid({'front': ["Q", "Q", "Q", "Q", "H"]}, PS, default=fill(PS['Q'], 6)),
        'book': grid({'right': ["EEE", "TTT", "TTT"], 'left': ["EEE", "TTT", "TTT"]}, PS, default=fill(PS['B'], 4)),
    })

# ---------------- The Gilded Stag: gold circlets with antlers, green tunics, gold tabard with a green stag ----------------
PG = {'S': (198, 150, 112), 'H': (150, 104, 54), 'W': (245, 245, 245), 'e': (60, 120, 60), 'n': (176, 128, 92), 'm': (150, 84, 70),
      'U': (54, 104, 58), 'u': (40, 78, 44), 'T': (214, 172, 58), 'B': (160, 120, 34), 'E': (40, 92, 46),
      'L': (96, 64, 34), 'O': (88, 62, 36), 'D': (70, 60, 44), 'A': (226, 214, 184), 'a': (186, 170, 134),
      'Y': GOLD, 'y': GOLDD, '.': CL, 'P': GOLD, 'G': (54, 104, 58), 'g': (40, 78, 44)}
stag = paint_guild(PG, face_rows('H'),
    {'front': ["UUUUUUUU"] * 7 + ["LLLYLLLL"] + ["UUUUUUUU"] * 3 + ["uuuuuuuu"],
     'back': ["UUUUUUUU"] * 7 + ["LLLLLLLL"] + ["UUUUUUUU"] * 3 + ["uuuuuuuu"]},
    ["UUUU"] * 8 + ["uuuu", "SSSS", "SSSS", "SSSS"],
    ["DDDD"] * 7 + ["OOOO"] * 5,
    tabard_rows(["E.E", ".E.", "EEE", "E.E"]), 'G', 'g', 'P',
    {
        'circlet': grid({'front': ["YYYYEYYYY"], 'back': ["YYYYYYYYY"], 'left': ["YYYYYYYYY"], 'right': ["YYYYYYYYY"],
                         'top': ["yyyyyyyyy"] + ["y.......y"] * 7 + ["yyyyyyyyy"], 'bottom': ["y.......y"] * 9}, PG, default=fill(GOLD, 5)),
        'antler': fill(PG['A'], 6), 'tine': fill(PG['A'], 6), 'tip': fill(PG['a'], 6),
        'antler2': fill(PG['A'], 6), 'tine2': fill(PG['A'], 6), 'tip2': fill(PG['a'], 6),
    })

# ---------------- The Ashen Crows: black hoods with bone beak masks, charcoal leathers, black tabard with an ash crow ----------------
PC = {'S': (180, 160, 150), 'H': (24, 22, 26), 'W': (220, 220, 210), 'e': (150, 40, 40), 'n': (160, 140, 130), 'm': (110, 80, 80),
      'U': (46, 44, 50), 'u': (32, 30, 36), 'T': (28, 26, 30), 'B': (70, 68, 74), 'E': (176, 172, 168),
      'L': (60, 46, 36), 'O': (30, 28, 30), 'D': (40, 38, 44), 'K': (22, 20, 24), 'k': (14, 12, 16), 'X': (226, 216, 196),
      'x': (180, 168, 150), '.': CL, 'P': (70, 68, 74), 'G': (22, 20, 24), 'g': (14, 12, 16), 'r': (200, 50, 50)}
crows = paint_guild(PC, face_rows('H', 'r'),
    {'front': ["UUUUUUUU"] * 7 + ["LLLLLLLL"] + ["UUUUUUUU"] * 3 + ["uuuuuuuu"],
     'back': ["UUUUUUUU"] * 7 + ["LLLLLLLL"] + ["UUUUUUUU"] * 3 + ["uuuuuuuu"]},
    ["UUUU"] * 8 + ["uuuu", "LLLL", "LLLL", "LLLL"],
    ["DDDD"] * 8 + ["OOOO"] * 4,
    tabard_rows([".E.", "EEE", "E.E", ".E."]), 'G', 'g', 'P',
    {
        'hood': grid({'front': ["KKKKKKKKK", "KKKKKKKKK", "KK.....KK", "K.......K", "K.......K", "K.......K", "K.......K", "K.......K", "KK.....KK"],
                      'back': ["KKKKKKKKK"] * 8 + ["kkkkkkkkk"], 'left': ["KKKKKKKKK"] * 9, 'right': ["KKKKKKKKK"] * 9,
                      'top': ["KKKKKKKKK"] * 9, 'bottom': ["K.......K"] * 9}, PC, default=fill(PC['K'], 4)),
        'beak': grid({'front': ["XX", "xx"], 'top': ["XX"] * 3}, PC, default=fill(PC['x'], 5)),
    })

out, prev, art = sys.argv[1], sys.argv[2], sys.argv[3]
guilds = [('iron_hounds', hounds, {'hound_helm'}), ('scarlet_ledger', ledger, {'ledger_cap', 'ledger_book'}),
          ('gilded_stag', stag, {'stag_crown'}), ('ashen_crows', crows, {'crow_hood'})]
optional = {'hound_helm', 'ledger_cap', 'ledger_book', 'stag_crown', 'crow_hood', 'cape', 'r_paul', 'l_paul'}
parts = {p.name: p for p in model.all_parts()}

def collect(name):
    p = parts[name]; out = []
    for q in p.walk(): out.append(q)
    return out

sheet = Image.new('RGBA', (1024, 660))
for i, (name, painters, shown) in enumerate(guilds):
    tex = model.paint({c.name: painters.get(c.name) for c in model.all_cubes()})
    tex.save(f'{out}/textures/entity/rival/{name}.png')
    for row, captain in enumerate([False, True]):
        visible = shown | ({'cape', 'r_paul', 'l_paul'} if captain else set())
        saved = {}
        for n in optional:
            if n in visible: continue
            for q in collect(n):
                saved[q.name] = q.cubes; q.cubes = []
        img = model.render(tex, yaw=25 if not captain else 200)
        sheet.paste(img.resize((192, 240)) if captain else img.resize((224, 280)), (i * 256 + (32 if captain else 16), 0 if not captain else 300))
        for n, cubes in saved.items(): parts[n].cubes = cubes
    if i == 0:
        json.dump(model.bbmodel(tex, 'rival_adventurer'), open(f'{art}/rival_adventurer.bbmodel', 'w'))
sheet.save(f'{prev}/rival_preview.png')
open(f'{prev}/rival.kt.txt', 'w').write(model.kotlin())
