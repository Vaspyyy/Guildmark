"""Halvard, the Fallen Warden: a road-keeper of the Old Guild who fell marching on the Hollow King and rose hollow,
still keeping the road. Battered dark plate with tarnished gold trim, a horned great helm (one horn snapped) with a
narrow visor slit, layered pauldrons, a long frayed green-grey travelling cloak, the Wardens' lantern-over-road
sigil on the chest, and a greatsword. Hollow flame shows through cracks in the plate in his second phase.

Glow works by exact marker colours: the eye slit, cracks, runes, the blade's edges and the sigil's lamp and road are
painted into the base texture as exact colours (dark, or tarnished gold), and each glow map swaps those exact colours
for its own light. Phase one lights only the eye slit; phase two lights the eyes brighter plus the cracks (with a faint
halo), the lantern and road of the sigil, and the greatsword's edges and runes.
Writes the texture, both glow maps, a .bbmodel, a preview sheet and the Kotlin body."""
import sys, json, random
sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0])
from modelkit import *
from PIL import Image
import numpy as np

STEEL = (60, 62, 72); STEEL_D = (42, 43, 52); STEEL_L = (92, 96, 108); GAP = (24, 24, 32)
GOLD = (150, 118, 56); GOLD_D = (104, 80, 40); GOLD_L = (186, 152, 80)
CLOAK = (70, 86, 78); CLOAK_D = (50, 62, 56); CLOAK_L = (86, 102, 92); LINING = (40, 47, 44)
LEATHER = (68, 46, 32); LEATHER_D = (46, 32, 24); MAIL = (50, 50, 58); MAIL_L = (68, 68, 78)
HORN = (56, 50, 50); HORN_D = (38, 34, 36); HORN_L = (104, 94, 84); BLADE = (112, 118, 132); FULLER = (66, 68, 80)
CL = (0, 0, 0, 0)
# Exact marker colours (no grain) that the glow maps key on. Grain shifts all three channels of a colour by the same
# amount, so no marker may differ from a palette colour by the same step in r, g and b (SLIT is one off GAP for that).
CRACK = (26, 20, 40, 255); EYE = (64, 72, 124, 255); SLIT = (18, 18, 27, 255)
ROAD = (124, 96, 46, 255); LAMP = (92, 70, 44, 255)
RUNE = (34, 28, 50, 255); EDGE = (80, 84, 100, 255)
GLOW1 = {EYE: (60, 72, 140, 255), SLIT: (14, 16, 36, 255)}
GLOW2 = {EYE: (200, 196, 255, 255), SLIT: (70, 60, 170, 255), CRACK: (140, 112, 255, 255),
         ROAD: (56, 46, 196, 255), LAMP: (176, 160, 255, 255),
         RUNE: (176, 156, 255, 255), EDGE: (116, 94, 240, 255)}

P = {'A': STEEL, 'a': STEEL_D, 'H': STEEL_L, 'K': GAP, 'G': GOLD, 'g': GOLD_D, 'J': GOLD_L,
     'V': CLOAK, 'v': CLOAK_D, 'u': CLOAK_L, 'n': LINING, 'L': LEATHER, 'l': LEATHER_D, 'M': MAIL, 'm': MAIL_L,
     'O': HORN, 'o': HORN_D, 'W': HORN_L, 'B': BLADE, 'S': FULLER, '.': CL,
     'x': CRACK, 'E': EYE, 'e': SLIT, 'y': ROAD, 'F': LAMP, 'R': RUNE, 'Q': EDGE}

lay = random.Random(5)

def same(rows): return {'front': rows, 'back': rows, 'left': rows, 'right': rows}
def mirror(rows): return [r[::-1] for r in rows]

# ---- head: a great helm, gold brow over a narrow eye slit, a faceplate with breaths, swept horns ----
helm = grid({
    'front': ["HHHHHHHHH", "AAAAGAAAA", "AAAAGAAAA", "aaaaGaaaa", "AeEeKeEeA", "AAAAGAAAA", "AAAAGAAAA", "AAAAGAAAA", "ggggggggg"],
    'left': ["HHHHHHHHH", "AAAAAAAAA", "AAxAAAAAA", "AAxAAAAAA", "AAAxAAAAA", "AAAxxAAAA", "AAAAAxAAA", "aAAAAAAAa", "ggggggggg"],
    'right': ["HHHHHHHHH", "AAAAAAAAA", "AAAAAAAAA", "AAAAHAAAA", "AAAAAAAAA", "AAAAAAAAA", "AAAAAAAAA", "aAAAAAAAa", "ggggggggg"],
    'back': ["HHHHHHHHH", "AAAAGAAAA", "AAAAGAAAA", "AAAAGAAAA", "AAAAGAxAA", "AAAAGAxAA", "AAAAGAAxA", "aAAAGAAxa", "ggggggggg"],
    'top': ["HHHHHHHHH"] * 9, 'bottom': ["KKKKKKKKK"] * 9,
}, P, default=fill(STEEL, 4))
helm_top = grid({'top': ["HHHGHHH"] * 7, 'front': ["HHHGHHH"], 'back': ["HHHGHHH"], 'left': ["HHHHHHH"], 'right': ["HHHHHHH"]},
                P, default=fill(STEEL_L, 4))
brow = grid({'front': ["GJGGGGGJG"], 'top': ["HHHHHHHHH"], 'bottom': ["aaaaaaaaa"], 'left': ["G"], 'right': ["G"]}, P, default=fill(GOLD, 4))
visor = grid({'front': ["HHHHGHHHH", "AAAAGAAAA", "AKAKGKAKA", "aaaaGaaxa"], 'top': ["HHHHHHHHH"], 'bottom': ["aaaaaaaaa"],
              'left': ["H", "A", "A", "a"], 'right': ["H", "A", "A", "a"]}, P, default=fill(STEEL, 4))
horn = grid({'top': ["OO", "OO", "oO", "GG"], 'bottom': ["GG", "oo", "oo", "oo"], 'right': ["OOoG", "OooG"], 'left': ["GoOO", "GooO"],
             'front': ["GG", "GG"], 'back': ["OO", "oO"]}, P, default=fill(HORN, 4))
horn_tip = grid({'top': ["W", "O", "O"], 'bottom': ["o", "o", "W"], 'right': ["WOo"], 'left': ["oOW"], 'back': ["W"]}, P, default=fill(HORN, 4))
horn_broken = grid({'top': ["WO", "OO", "oO", "GG"], 'bottom': ["GG", "oo", "oo", "oW"], 'right': ["WOoG", "WooG"],
                    'left': ["GoOW", "GooW"], 'front': ["GG", "GG"], 'back': ["WW", "WO"]}, P, default=fill(HORN, 4))

# ---- body: a cuirass with the lantern-over-road sigil, plate faulds, a long frayed cloak ----
cuirass = grid({
    'front': ["HHHGGHHH", "AAJGGJAx", "AAGFFGAx", "xAGFFGAA", "AxgGGgAA", "AxAyyAAA", "AAyAAyAA", "ayaaaaya"],
    'back': ["HHHHHHHH", "AAAAAAAA", "AAxAAAAA", "AAAxAAAA", "AAAxxAAA", "AAAAAxAA", "AAAAAAAA", "aaaaaaaa"],
    'right': ["HHHH", "AAAA", "AAAA", "AAAA", "AAAA", "AAAA", "AAAA", "aaaa"],
    'left': ["HHHH", "AAAA", "AAxA", "AxAA", "AAAA", "AAAA", "AAAA", "aaaa"],
    'top': ["HHHHHHHH"] * 4, 'bottom': ["aaaaaaaa"] * 4,
}, P, default=fill(STEEL, 4))
faulds = grid({'front': ["LLLGGLLL", "HHHHHHxH", "aaaaaxaa", "HHHHHxHH"], 'back': ["LLLLLLLL", "HHHHHHHH", "aaaaaaaa", "HHHHHHHH"],
               'right': ["LLLL", "HHHH", "aaaa", "HHHH"], 'left': ["LLLL", "HHHH", "aaaa", "HHHH"],
               'top': ["LLLLLLLL"] * 4, 'bottom': ["aaaaaaaa"] * 4}, P, default=fill(STEEL, 4))

def cloak_rows(w, h, base, fold, light):
    """A travelling cloak seen from behind: long vertical folds, wear, and a frayed hem."""
    rows = []
    for j in range(h):
        r = ''
        for i in range(w):
            c = base
            if i in (2, 5, 8, 10): c = fold
            if j < 2: c = light if c == base else base
            if lay.random() < 0.05: c = fold
            r += c
        rows.append(r)
    hem = [(lay.random() < 0.45, lay.random() < 0.7) for _ in range(w)]
    rows[h - 3] = ''.join(fold if a else rows[h - 3][i] for i, (a, b) in enumerate(hem))
    rows[h - 2] = ''.join('.' if a else fold for a, b in hem)
    rows[h - 1] = ''.join('.' if (a or b) else fold for a, b in hem)
    return rows
cloak_back = cloak_rows(12, 23, 'V', 'v', 'u')
# The Wardens' lantern-over-road, stitched in faded gold thread between the shoulders
for j, pat in enumerate(["_____gg_____", "____gggg____", "____gJJg____", "____gggg____", "____g__g____", "___g____g___",
                         "__g______g__"]):
    cloak_back[3 + j] = ''.join(b if t == '_' else t for b, t in zip(cloak_back[3 + j], pat))
cloak_front = mirror([''.join('.' if ch == '.' else 'n' for ch in r) for r in cloak_back])
hood = grid({'back': ["uuuuuuuu", "VvVVvVVV", "vvvvvvvv"], 'top': ["VVVVVVVV", "uuuuuuuu"], 'right': ["Vu", "VV", "vv"],
             'left': ["uV", "VV", "vv"], 'bottom': ["vvvvvvvv"] * 2, 'front': ["nnnnnnnn"] * 3}, P, default=fill(CLOAK, 4))
cloak = grid({'back': cloak_back, 'front': cloak_front, 'right': [r[-1] if r[-1] == '.' else 'v' for r in cloak_back],
              'left': [r[0] if r[0] == '.' else 'v' for r in cloak_back], 'top': ["vvvvvvvvvvvv"], 'bottom': ["............"]},
             P, default=fill(CLOAK_D, 4))

# ---- arms: mail under plate, gold-trimmed gauntlets, stacked pauldrons ----
arm = grid(same(["MMMM", "MmMM", "MMMm", "MMMM", "HHHH", "AAAA", "aaaa", "AAAA", "AAAA", "AAAA", "AAAA", "aaaa"]), P, default=fill(STEEL, 4))
gauntlet = lambda crack: grid({
    'front': ["GGGG", "HHHH", "AAAA" if not crack else "AxAA", "aAAa", "KaKa"], 'back': ["GGGG", "HHHH", "AAAA", "aAAa", "aaaa"],
    'right': ["GGGG", "HHHH", "AAAA", "aAAa", "aaaa"], 'left': ["GGGG", "HHHH", "AAAA", "aAAa", "aaaa"],
    'top': ["GGGG"] * 4, 'bottom': ["aaaa"] * 4}, P, default=fill(STEEL, 4))
# The left pauldron is cracked across its top, the right one down its front
shell = lambda left: grid({
    'top': ["HHHHHH", "HAAAxH", "HAJxAH", "HAAxAH", "HAxAAH", "HHHHHH"] if left else
           ["HHHHHH", "HAAAAH", "HAJAAH", "HAAAAH", "HAAAAH", "HHHHHH"],
    'front': ["HHHHHH", "AAAAAA", "GGGGGG"] if left else ["HHHxHH", "AAxAAA", "GGxGGG"], 'back': ["HHHHHH", "AAAAAA", "GGGGGG"],
    'right': ["HHHHHH", "AAAAAA", "GGGGGG"], 'left': ["HHHHHH", "AAxAAA" if left else "AAAAAA", "GGGGGG"], 'bottom': ["aaaaaa"] * 6,
}, P, default=fill(STEEL, 4))
lame = grid({'front': ["aaaaaa", "AAAAAA"], 'back': ["aaaaaa", "AAAAAA"], 'right': ["aaaaaa", "AAAAAA"],
             'left': ["aaaaaa", "AAAAAA"], 'top': ["HHHHHH"] * 6, 'bottom': ["aaaaaa"] * 6}, P, default=fill(STEEL, 4))
pcap = lambda crack: grid({'top': ["HHHH", "HJJH", "HJJH", "HHHH"] if not crack else ["HHHH", "HJxH", "HxJH", "HHHH"],
                           'front': ["HHHH"], 'back': ["HHHH"], 'right': ["HHHH"], 'left': ["HHHH"]}, P, default=fill(STEEL_L, 4))

# ---- the greatsword: gold pommel and guard, leather grip, a honed blade with runes down a dark fuller ----
pommel = grid({'top': ["JG", "Gg"], 'bottom': ["gg", "gg"]}, P, default=fill(GOLD, 6))
grip = grid(same(["ll", "LL", "ll", "LL", "ll"]), P, default=fill(LEATHER, 4))
guard = grid({'left': ["gGGJGGg"], 'right': ["gGGJGGg"], 'top': ["gG"] + ["GG"] * 5 + ["gG"], 'bottom': ["gG"] + ["gg"] * 5 + ["gG"],
              'front': ["JG"], 'back': ["Gg"]}, P, default=fill(GOLD, 5))
flat = ["BSB", "BSB", "BRB", "BRB", "BSB", "BRB", "QSB", "BRB", "BRB", "BSB", "BSQ", "BRB", "BSB", "BRB", "BRB", "QSB", "BSB",
        "BRB", "BSB", "BSB"]
blade = grid({'right': flat, 'left': mirror(flat), 'front': ["Q"] * 20, 'back': ["Q"] * 20, 'top': ["S"] * 3, 'bottom': ["S"] * 3},
             P, default=fill(BLADE, 4))
tip = grid({'right': ["BB", "QB"], 'left': ["BB", "BQ"], 'front': ["Q", "Q"], 'back': ["Q", "Q"]}, P, default=fill(BLADE, 4))
point = grid({'right': ["Q"], 'left': ["Q"], 'front': ["Q"], 'back': ["Q"], 'bottom': ["Q"]}, P, default=fill(BLADE, 4))

# ---- legs: plate cuisses, knee cops, greaves and pointed sabatons ----
leg = grid(same(["MMMM", "MMMM", "MMMM", "MMMM", "MMMM", "MmMM", "KKKK", "MMMM", "MMMM", "MMMM", "MMMM", "aaaa"]), P, default=fill(MAIL, 4))
cuisse = lambda crack: grid({
    'front': ["aaaa", "HHHH", "AAAA" if not crack else "AAxA", "AAAA" if not crack else "AxAA", "aaaa"],
    'back': ["aaaa", "HHHH", "AAAA", "AAAA", "aaaa"], 'right': ["aaaa", "HHHH", "AAAA", "AAAA", "aaaa"],
    'left': ["aaaa", "HHHH", "AAAA", "AAAA", "aaaa"], 'top': ["aaaa"] * 4, 'bottom': ["aaaa"] * 4}, P, default=fill(STEEL, 4))
knee = grid({'front': ["HGH", "AgA"], 'top': ["HHH"], 'bottom': ["aaa"]}, P, default=fill(STEEL, 4))
greave = lambda crack: grid({
    'front': ["HHHH", "HAAA", "HAAx" if not crack else "HAxA", "HAAA" if not crack else "HxAA", "aaaa"],
    'back': ["HHHH", "AAAA", "AAAA", "AAAA", "aaaa"], 'right': ["HHHH", "HAAA", "AAAA", "AAAA", "aaaa"],
    'left': ["HHHH", "AAAH", "AAAA", "AAAA", "aaaa"], 'top': ["HHHH"] * 4, 'bottom': ["KKKK"] * 4}, P, default=fill(STEEL, 4))
toe = grid({'front': ["AHA"], 'top': ["HHH"], 'bottom': ["KKK"]}, P, default=fill(STEEL_D, 4))

def C(o, s, p, name=None, **kw): return Cube(o, s, p, name=name, **kw)

head = Part('head', cubes=[
    C((-4.5, -9, -4.5), (9, 9, 9), helm, 'helm'), C((-3.5, -10, -3.5), (7, 1, 7), helm_top, 'helm_top'),
    C((-4.5, -6, -5.5), (9, 1, 1), brow, 'brow'), C((-4.5, -4, -5.5), (9, 4, 1), visor, 'visor'),
], children=[
    Part('hat'),
    Part('horn_r', (-4.5, -7, 0.5), (40, -35, 0), cubes=[C((-1, -1, 0), (2, 2, 4), horn, 'horn_r'), C((-0.5, -0.5, 4), (1, 1, 3), horn_tip, 'horn_r_tip')]),
    Part('horn_l', (4.5, -7, 0.5), (40, 35, 0), cubes=[C((-1, -1, 0), (2, 2, 4), horn_broken, 'horn_l')]),
])
body = Part('body', cubes=[C((-4, 0, -2), (8, 8, 4), cuirass, 'cuirass', inflate=0.45), C((-4, 8, -2), (8, 4, 4), faulds, 'faulds', inflate=0.75)],
            children=[Part('cloak', (0, 0.5, 2.5), (6, 0, 0), cubes=[C((-6, 0, 0), (12, 23, 1), cloak, 'cloak'),
                                                                     C((-4, -1.5, -0.4), (8, 3, 2), hood, 'hood')])])
greatsword = Part('greatsword', (0, 10, 0), (-90, 0, 0), cubes=[
    C((-2, -5, -2), (2, 2, 2), pommel, 'pommel'), C((-2, -3, -2), (2, 5, 2), grip, 'grip'), C((-2, 2, -4.5), (2, 1, 7), guard, 'guard'),
    C((-1.5, 3, -2.5), (1, 20, 3), blade, 'blade'), C((-1.5, 23, -2), (1, 2, 2), tip, 'tip'), C((-1.5, 25, -1.5), (1, 1, 1), point, 'point'),
])
right_arm = Part('right_arm', (-5, 2, 0), cubes=[C((-3, -2, -2), (4, 12, 4), arm, 'rarm'), C((-3, 5, -2), (4, 5, 4), gauntlet(False), 'rgauntlet', inflate=0.42)],
                 children=[Part('r_pauldron', (-1, -2, 0), (0, 0, -10), cubes=[
                     C((-3.5, -3.5, -2), (4, 1, 4), pcap(False), 'rcap'), C((-4.5, -2.5, -3), (6, 3, 6), shell(False), 'rshell'),
                     C((-4.5, 0.5, -3), (6, 2, 6), lame, 'rlame', inflate=0.35)]), greatsword])
left_arm = Part('left_arm', (5, 2, 0), cubes=[C((-1, -2, -2), (4, 12, 4), arm, 'larm'), C((-1, 5, -2), (4, 5, 4), gauntlet(True), 'lgauntlet', inflate=0.42)],
                children=[Part('l_pauldron', (1, -2, 0), (0, 0, 10), cubes=[
                    C((-0.5, -3.5, -2), (4, 1, 4), pcap(True), 'lcap'), C((-1.5, -2.5, -3), (6, 3, 6), shell(True), 'lshell'),
                    C((-1.5, 0.5, -3), (6, 2, 6), lame, 'llame', inflate=0.35)])])
# Leg cubes sit 0.1 off their pivots so the legs meet at x=0 instead of overlapping; left plates are a hair thicker
right_leg = Part('right_leg', (-1.9, 12, 0), cubes=[
    C((-2.1, 0, -2), (4, 12, 4), leg, 'rleg'), C((-2.1, 0, -2), (4, 5, 4), cuisse(False), 'rcuisse', inflate=0.4),
    C((-1.6, 4.5, -3), (3, 2, 1), knee, 'rknee'), C((-2.1, 7, -2), (4, 5, 4), greave(True), 'rgreave', inflate=0.35),
    C((-1.6, 11, -3.4), (3, 1, 1), toe, 'rtoe')])
left_leg = Part('left_leg', (1.9, 12, 0), cubes=[
    C((-1.9, 0, -2), (4, 12, 4), leg, 'lleg'), C((-1.9, 0, -2), (4, 5, 4), cuisse(True), 'lcuisse', inflate=0.45),
    C((-1.4, 4.5, -3.05), (3, 2, 1), knee, 'lknee'), C((-1.9, 7, -2), (4, 5, 4), greave(False), 'lgreave', inflate=0.4),
    C((-1.4, 11, -3.45), (3, 1, 1), toe, 'ltoe')])

model = Model('fallen_warden', 128, 128, [head, body, right_arm, left_arm, right_leg, left_leg])
model.pack()
tex = model.paint()

def glow_map(colors):
    """Swap each exact marker colour for its light; everything else stays fully clear."""
    arr = np.array(tex); g = np.zeros_like(arr)
    for src, dst in colors.items():
        g[np.all(arr == np.array(src), axis=-1)] = dst
    return Image.fromarray(g, 'RGBA')
HALO = (22, 16, 64, 255)
def add_halo(g):
    """Light spilling one pixel around each crack, kept inside the crack's own face so it never bleeds across UVs."""
    arr = np.array(tex); out = np.array(g)
    crack = np.all(arr == np.array(CRACK), axis=-1)
    for c in model.all_cubes():
        for (x, y, w, h) in c.rects().values():
            for j in range(y, y + h):
                for i in range(x, x + w):
                    if not crack[j, i]: continue
                    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        u, v = i + di, j + dj
                        if x <= u < x + w and y <= v < y + h and out[v, u, 3] == 0 and arr[v, u, 3] > 0:
                            out[v, u] = HALO
    return Image.fromarray(out, 'RGBA')
glow = glow_map(GLOW1); glow2 = add_halo(glow_map(GLOW2))

def lit(g):
    """The eyes render type adds the glow onto the lit texture; approximate that for the preview."""
    a = np.array(tex).astype(int); b = np.array(g).astype(int)
    on = b[..., 3] > 0
    out = np.zeros_like(a); out[on, :3] = np.clip(a[on, :3] + b[on, :3], 0, 255); out[on, 3] = 255
    return Image.fromarray(out.astype(np.uint8), 'RGBA')

out, prev, art = sys.argv[1], sys.argv[2], sys.argv[3]
tex.save(f'{out}/textures/entity/fallen_warden.png')
glow.save(f'{out}/textures/entity/fallen_warden_glow.png'); glow2.save(f'{out}/textures/entity/fallen_warden_glow2.png')
open(f'{prev}/fallen_warden.kt.txt', 'w').write(model.kotlin())
json.dump(model.bbmodel(tex, 'fallen_warden'), open(f'{art}/fallen_warden.bbmodel', 'w'))
sheet = Image.new('RGBA', (4 * 300, 360))
for i, (yaw, g) in enumerate([(25, glow), (200, glow), (-70, glow2), (35, glow2)]):
    sheet.paste(model.render(tex, yaw=yaw, scale=7, size=(300, 360), glow=lit(g)), (i * 300, 0))
sheet.save(f'{prev}/fallen_warden_preview.png'); sheet.save(f'{art}/fallen_warden_preview.png')
tex.resize((512, 512), Image.NEAREST).save(f'{prev}/fallen_warden_tex.png')
