"""The Hill Troll: a field boss that sleeps by its fire in the hills and wakes to anyone who comes near. Twice a
man's height and hunched, with a small head slung low before a hump of shoulder, a jutting jaw with two tusks, a nose
like a root, little ears and a tuft of black hair. Grey-green hide mottled with moss and old scars, a belly gone pale,
a hide loincloth on a rope belt with a skull tied at the hip, a broken iron shackle on the left wrist, and a club
cut from a tree with iron bands and nails driven through it. The eyes glow a dull amber.

Writes the texture, the eye glow map, a .bbmodel, a preview sheet and the Kotlin body."""
import sys, json, random
sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0])
from modelkit import *
from PIL import Image
import numpy as np

SKIN = (112, 122, 98); SKIN_D = (86, 96, 76); SKIN_L = (136, 144, 118); MOSS = (78, 104, 58); MOSS_L = (104, 132, 70)
BELLY = (146, 142, 112); BELLY_D = (122, 118, 94); SCAR = (154, 120, 104); CREASE = (66, 74, 58)
HAIR = (52, 44, 36); HAIR_D = (34, 29, 25); TUSK = (224, 212, 174); TUSK_D = (184, 170, 136)
MOUTH = (46, 28, 28); GUM = (112, 58, 56); PUPIL = (36, 26, 14)
HIDE = (110, 86, 60); HIDE_D = (82, 64, 46); HIDE_L = (134, 108, 76); ROPE = (138, 112, 70); ROPE_D = (100, 80, 50)
BONE = (214, 204, 176); BONE_D = (170, 160, 134); SOCKET = (38, 32, 28)
BARK = (92, 70, 48); BARK_D = (64, 49, 35); BARK_L = (116, 90, 62); CUT = (156, 124, 84); RING = (126, 98, 66)
IRON = (84, 84, 92); IRON_D = (56, 56, 64); IRON_L = (122, 122, 132); RUST = (122, 74, 48)
CL = (0, 0, 0, 0)
# Exact marker colour the glow map keys on (no grain)
EYE = (226, 178, 70, 255)
GLOW = {EYE: (255, 196, 80, 255)}

P = {'S': SKIN, 's': SKIN_D, 'T': SKIN_L, 'w': MOSS, 'W': MOSS_L, 'B': BELLY, 'b': BELLY_D, 'V': SCAR, 'k': CREASE,
     'H': HAIR, 'h': HAIR_D, 'N': TUSK, 'n': TUSK_D, 'M': MOUTH, 'G': GUM, 'E': EYE, 'e': PUPIL,
     'L': HIDE, 'l': HIDE_D, 'j': HIDE_L, 'R': ROPE, 'r': ROPE_D, 'K': BONE, 'q': BONE_D, 'O': SOCKET,
     'X': BARK, 'x': BARK_D, 'Y': BARK_L, 'C': CUT, 'c': RING, 'I': IRON, 'i': IRON_D, 'J': IRON_L, 'U': RUST, '.': CL}

lay = random.Random(11)  # where moss, scars and wear fall; the kit's own rnd does the grain


def hide(w, h, moss=0.03, light_top=1, scars=0, belly=False):
    """Mottled troll hide: soft blotches of darker and lighter skin, moss in patches, a scar or two."""
    base = 'B' if belly else 'S'
    dark = 'b' if belly else 's'
    light = 'B' if belly else 'T'
    rows = [[base] * w for _ in range(h)]

    def blob(cx, cy, r, c):
        for j in range(cy - r, cy + r + 1):
            for i in range(cx - r, cx + r + 1):
                if 0 <= i < w and 0 <= j < h and abs(i - cx) + abs(j - cy) <= r and lay.random() < 0.85:
                    rows[j][i] = c
    for _ in range(max(1, w * h // 26)):
        blob(lay.randrange(w), lay.randrange(h), lay.choice((1, 1, 2)), dark if lay.random() < 0.65 else light)
    for j in range(min(light_top, h)):
        for i in range(w):
            if rows[j][i] == base: rows[j][i] = light
    if not belly:
        for _ in range(int(w * h * moss / 3 + 0.5)):
            blob(lay.randrange(w), lay.randrange(h), lay.choice((0, 1, 1)), lay.choice('wwW'))
    for _ in range(scars):
        x, y = lay.randrange(w), lay.randrange(max(1, h - 3))
        for k in range(3):
            if 0 <= x + k - 1 < w and y + k < h: rows[y + k][x + k - 1] = 'V'
    return [''.join(r) for r in rows]


def skin(moss=0.03, scars=0):
    """A painter that lays fresh mottled hide on every face (each face its own pattern)."""
    def p(img, face, x, y, w, h):
        rows = hide(w, h, moss={'top': 0.45, 'bottom': 0.0}.get(face, moss), light_top=1 if face != 'bottom' else 0,
                    scars=scars if face in ('front', 'back') else 0)
        grid({face: rows}, P)(img, face, x, y, w, h)
    return p


def over(base, top):
    """Lay the rows of top over base; '_' keeps the base character."""
    return [''.join(b if t == '_' else t for b, t in zip(br, tr)) for br, tr in zip(base, top)]


def mirror(rows): return [r[::-1] for r in rows]


def C(o, s, p, name=None, **kw): return Cube(o, s, p, name=name, **kw)


# ---- head: a heavy brow over small amber eyes, a nose like a root, a jutting jaw with two tusks ----
skull = grid({
    'front': over(hide(8, 7, moss=0.04), ["_______", "________", "_kk__kk_", "_eE__Ee_", "k______k", "________", "s______s"]),
    'right': hide(8, 7, scars=1), 'left': hide(8, 7), 'back': hide(8, 7, moss=0.2),
    'top': over(hide(8, 8, moss=0.25), ["________", "__HHHH__", "_HhHHhH_", "_HHhHHH_", "__HHhH__", "________", "________", "________"]),
    'bottom': ["ssssssss"] * 8,
}, P)
jaw = grid({
    'front': ["MGMGGMGM_", "SsSSSSSsS", "sSSssSSss"],
    'right': ["kSSSSSs", "SSsSSSS", "sssssss"], 'left': ["sSSSSSk", "SSSSsSS", "sssssss"],
    'back': ["sssssssss"] * 3, 'top': ["MMMMMMMMM"] * 7, 'bottom': ["sssssssss"] * 7,
}, P, default=fill(SKIN_D, 4))
brow = grid({'front': ["TTTSTSTTT", "kSSsssSSk"], 'top': ["TTTTTTTTT"], 'bottom': ["kkkkkkkkk"],
             'left': ["T", "k"], 'right': ["T", "k"]}, P, default=fill(SKIN, 4))
nose = grid({'front': ["sTs", "STS", "kSk"], 'top': ["TTT", "TTT"], 'bottom': ["kMk", "sss"],
             'left': ["Ts", "Ss", "ks"], 'right': ["sT", "sS", "sk"]}, P, default=fill(SKIN, 4))
tusk = grid(dict(front=["N", "N", "n"], back=["n", "n", "n"], left=["N", "N", "n"], right=["n", "n", "n"], top=["N"], bottom=["n"]), P)
ear = grid({'front': ["sSSs", "kss."], 'back': ["sSSs", "sss."], 'top': ["TTTT"], 'bottom': ["ssss"],
            'left': ["S", "s"], 'right': ["S", "s"]}, P, default=fill(SKIN, 4))
tuft = grid({'top': ["hHHHh", "HhHHH", "HHhHh", "hHHHH", "HhHhH"], 'front': ["HhHHh"], 'back': ["hHhHH"],
             'left': ["HhHhH"], 'right': ["hHHhH"], 'bottom': ["hhhhh"] * 5}, P, default=fill(HAIR, 4))

# ---- body: a slab of chest, a pale belly, a hump of shoulder, a hide loincloth on a rope belt ----
chest = skin(scars=2)
belly = grid({
    'front': over(hide(12, 7, belly=True), ["____________", "_____k______", "____________", "______V_____", "_______V____", "____________", "kkkkkkkkkkkk"]),
    'right': hide(9, 7), 'left': hide(9, 7), 'back': hide(12, 7), 'top': hide(12, 9), 'bottom': ["ssssssssssss"] * 9,
}, P)
hump = grid({'top': hide(10, 7, moss=0.45), 'back': hide(10, 5, moss=0.3), 'left': hide(7, 5, moss=0.3), 'right': hide(7, 5, moss=0.3),
             'front': hide(10, 5, moss=0.15), 'bottom': ["ssssssssss"] * 7}, P)


waist = grid({
    'front': ["RrRRrRRrRRrRR", "lLLLjLLLLjLLl", "LLlLLLLLLLlLL", "lLLLLlLLLLLLl"],
    'back': ["RRrRRrRRrRRrR", "LLLlLLLLLlLLL", "LlLLLLjLLLLLl", "lLLLLlLLLLlLL"],
    'right': ["RrRRrRRr", "LLLlLLLL", "LlLLLLlL", "lLLLLLLl"], 'left': ["RRrRRrRR", "LLLLlLLL", "LLlLLLLl", "lLLLlLLL"],
    'top': ["SSSSSSSSSSSSS"] * 8, 'bottom': ["lllllllllllll"] * 8,
}, P, default=fill(HIDE, 4))
flap = grid({'front': ["LLjLLLL", "LlLLLjL", "LLLLlLL", "jLLLLLl", "LLlLLLL", "L.LL.LL"],
             'back': ["lllllll", "lLllllL", "llllLll", "Llllllj", "lllLlll", "l.ll.ll"],
             'left': ["L"] * 6, 'right': ["L"] * 6, 'top': ["LLLLLLL"], 'bottom': ["l.ll.ll"]}, P, default=fill(HIDE, 4))
flap_back = grid({'front': ["llllllll"] * 5, 'back': ["LLjLLLLL", "LlLLLjLL", "LLLLlLLL", "jLLLLLlL", ".LL.LLL."],
                  'left': ["L"] * 5, 'right': ["L"] * 5, 'top': ["LLLLLLLL"], 'bottom': [".ll.lll."]}, P, default=fill(HIDE, 4))
trophy = grid({'front': ["KKK", "OKO", "qKq"], 'back': ["KKq", "KKK", "qqq"], 'left': ["KKq", "KKK", "qKq"],
               'right': ["qKK", "KKK", "qKq"], 'top': ["KKK"] * 3, 'bottom': ["qqq"] * 3}, P)
cord = grid(dict(front=["R", "r"], back=["r", "R"], left=["R", "r"], right=["r", "R"], top=["R"], bottom=["r"]), P)

# ---- arms: long and heavy, mossy shoulders, huge hands; an iron shackle on the left wrist ----
arm = skin(scars=1)
shoulder = grid({'top': hide(7, 7, moss=0.55), 'front': hide(7, 4, moss=0.3), 'back': hide(7, 4, moss=0.3),
                 'left': hide(7, 4, moss=0.3), 'right': hide(7, 4, moss=0.3), 'bottom': ["sssssss"] * 7}, P)
hand = lambda right: grid({
    'front': ["SSSSSSS", "kSSkSSk", "SSSSSSS", "SsSsSsS", "NSNSNSN", "sssssss"],
    'back': ["TSSSSST", "SSkSSSS", "SSSSkSS", "SSSSSSS", "SsSSSsS", "sssssss"],
    'right' if right else 'left': ["SSSSSSS", "SSSkSSS", "SSSSSSS", "SSSSSSS", "SsSSsSS", "sssssss"],
    'left' if right else 'right': ["SSSSSSS", "SSSSSSS", "kSSSSSS", "SSSSSSS", "NSSSSSS", "sssssss"],
    'top': hide(7, 7), 'bottom': ["sksksks", "kssssss", "sssksss", "ssssssk", "skssssk", "ssssssk", "sksssss"],
}, P)
shackle = grid({'front': ["JIIUIII", "iiiiUii"], 'back': ["IIIIJII", "iiUiiii"], 'left': ["IJIIIII", "iiiiiUi"],
                'right': ["IIIUIJI", "iiiiiii"], 'top': ["JJJJJJJ"] * 7, 'bottom': ["iiiiiii"] * 7}, P, default=fill(IRON, 4))
chain = grid(dict(front=["J", "i"], back=["I", "i"], left=["J", "U"], right=["I", "i"], top=["J"], bottom=["i"]), P)

# ---- the club: a young tree, its roots trimmed to a knot, iron bands and nails driven through ----
grip = grid({'front': ["xXx"] * 9, 'back': ["Xxx"] * 9, 'left': ["xXY"] * 9, 'right': ["YXx"] * 9,
             'top': ["CcC", "cCc", "CcC"], 'bottom': ["xxx"] * 3}, P, default=fill(BARK, 4))
club_side = ["XxXYXX", "XXxXXY", "IJIIIU", "iIiiIi", "XXYXxX", "xXXXXx", "XYXxXX", "XXxXXY", "xXXXYX", "IIJIUI",
             "iiIiii", "XxXXXx", "YXXxXX", "XXYXxX"]
club = grid({'front': club_side, 'back': mirror(club_side), 'left': club_side, 'right': mirror(club_side),
             'top': ["CcCCcC", "cCCcCc", "CCcCCC", "cCCCcC", "CcCcCC", "CCCcCc"],
             'bottom': ["xXxxXx", "XxxXxx", "xxXxxX", "xXxxXx", "XxxxXx", "xxXxxX"]}, P, default=fill(BARK, 4))
nail = grid(dict(front=["J"], back=["I"], left=["J"], right=["I"], top=["J"], bottom=["i"]), P)
knot = grid({'front': ["XxX", "xYx"], 'back': ["xXx", "XxX"], 'left': ["Xx", "xX"], 'right': ["xX", "Xx"],
             'top': ["YXX", "XxX"], 'bottom': ["xxx", "xxx"]}, P, default=fill(BARK, 4))

# ---- the boulder it tears up and throws (only shown while it does) ----
rock = grid({f: [''.join(lay.choice('KqqO') if lay.random() < 0.15 else lay.choice('ww' if f == 'top' else 'xX') if lay.random() < 0.2 else 'I'
                         for _ in range(7)) for _ in range(7)] for f in ('front', 'back', 'left', 'right', 'top', 'bottom')},
            {**P, 'I': (110, 112, 104), 'x': (84, 88, 80), 'X': (128, 130, 120), 'K': (96, 120, 70), 'q': (74, 96, 58), 'O': (60, 62, 58)})

# ---- legs: short and thick, broad flat feet with dark nails ----
leg = skin()
foot = grid({
    'front': ["SSSSSS", "SkSSkS", "NsNsNs"], 'back': ["TSSSSS", "SSSSkS", "ssssss"],
    'right': ["SSSSSSS", "SSkSSSS", "sssssss"], 'left': ["SSSSSSS", "SSSSkSS", "sssssss"],
    'top': hide(6, 7), 'bottom': ["kkkkkk"] * 7,
}, P)

head = Part('head', (0, -5, -3.5), cubes=[
    C((-4, -7, -4), (8, 7, 8), skull, 'skull'), C((-4.5, -2, -5), (9, 3, 7), jaw, 'jaw'),
    C((-4.5, -6, -5), (9, 2, 1), brow, 'brow'), C((-1.5, -4.5, -6.5), (3, 3, 2), nose, 'nose'),
    C((-2.5, -8, -2.5), (5, 1, 5), tuft, 'tuft'),
], children=[
    Part('hat'),
    Part('tusk_r', (-3, -2, -4.4), (-8, 0, -10), cubes=[C((-0.5, -2.5, -0.5), (1, 3, 1), tusk, 'tusk_r')]),
    Part('tusk_l', (3, -2, -4.4), (-8, 0, 10), cubes=[C((-0.5, -2.5, -0.5), (1, 3, 1), tusk, 'tusk_l')]),
    Part('ear_r', (-4, -4, -0.5), (0, 25, -30), cubes=[C((-4, -1, -0.5), (4, 2, 1), ear, 'ear_r')]),
    Part('ear_l', (4, -4, -0.5), (0, -25, 30), cubes=[C((0, -1, -0.5), (4, 2, 1), ear, 'ear_l', mirror=True)]),
])
body = Part('body', cubes=[
    C((-7, -5, -4), (14, 11, 8), chest, 'chest'), C((-6, 5, -5), (12, 7, 9), belly, 'belly'),
    C((-6.5, 11, -4), (13, 4, 8), waist, 'waist'), C((-5, -9, -2), (10, 5, 7), hump, 'hump'),
], children=[
    Part('loincloth', (0, 14, -4.2), cubes=[C((-3.5, 0, -0.5), (7, 6, 1), flap, 'flap')]),
    Part('loincloth_back', (0, 14, 4.2), cubes=[C((-4, 0, -0.5), (8, 5, 1), flap_back, 'flap_back')]),
    Part('trophy', (5.8, 13.5, -3.6), (0, 0, -6), cubes=[C((-0.5, 0, -0.5), (1, 2, 1), cord, 'cord'),
                                                            C((-1.5, 2, -1.5), (3, 3, 3), trophy, 'trophy')]),
])
club = Part('club', (-1, 18, -0.5), (-90, 0, 0), cubes=[
    C((-1.5, -3, -1.5), (3, 9, 3), grip, 'grip'), C((-3, 6, -3), (6, 14, 6), club, 'club_head'),
    C((-4, 10, -0.5), (1, 1, 1), nail, 'nail_a'), C((3, 13, 0.5), (1, 1, 1), nail, 'nail_b'),
    C((-0.5, 16, -4), (1, 1, 1), nail, 'nail_c'), C((0.5, 9, 3), (1, 1, 1), nail, 'nail_d'),
    C((-1.5, 18.5, -1), (3, 2, 2), knot, 'knot'),
])
right_arm = Part('right_arm', (-9, -2, 0), cubes=[
    C((-4, -2, -3), (6, 17, 6), arm, 'rarm'), C((-4.5, -3, -3.5), (7, 4, 7), shoulder, 'rshoulder', inflate=0.2),
    C((-4.5, 14, -3.5), (7, 6, 7), hand(True), 'rhand'),
], children=[club])
left_arm = Part('left_arm', (9, -2, 0), cubes=[
    C((-2, -2, -3), (6, 17, 6), arm, 'larm'), C((-2.5, -3, -3.5), (7, 4, 7), shoulder, 'lshoulder', inflate=0.2),
    C((-2.5, 14, -3.5), (7, 6, 7), hand(False), 'lhand'), C((-2.5, 11.5, -3.5), (7, 2, 7), shackle, 'shackle', inflate=0.3),
], children=[Part('chain', (1, 13.5, -3.6), (0, 0, 0), cubes=[C((-0.5, 0, -0.5), (1, 2, 1), chain, 'chain_a'),
                                                              C((-0.5, 2, -0.5), (1, 2, 1), chain, 'chain_b')]),
             Part('boulder', (1, 19, -0.5), cubes=[C((-3.5, -1, -3.5), (7, 7, 7), rock, 'boulder')])])
right_leg = Part('right_leg', (-3.4, 15, 0), cubes=[C((-2.5, 0, -2.5), (5, 7, 5), leg, 'rleg'), C((-3, 6, -4), (6, 3, 7), foot, 'rfoot')])
left_leg = Part('left_leg', (3.4, 15, 0), cubes=[C((-2.5, 0, -2.5), (5, 7, 5), leg, 'lleg'), C((-3, 6, -4), (6, 3, 7), foot, 'lfoot')])

model = Model('hill_troll', 128, 128, [head, body, right_arm, left_arm, right_leg, left_leg])
model.pack()
tex = model.paint()


def glow_map(colors):
    """Swap each exact marker colour for its light; everything else stays fully clear."""
    arr = np.array(tex); g = np.zeros_like(arr)
    for src, dst in colors.items():
        g[np.all(arr == np.array(src), axis=-1)] = dst
    return Image.fromarray(g, 'RGBA')


glow = glow_map(GLOW)


def lit(g):
    a = np.array(tex).astype(int); b = np.array(g).astype(int)
    on = b[..., 3] > 0
    out = np.zeros_like(a); out[on, :3] = np.clip(a[on, :3] + b[on, :3], 0, 255); out[on, 3] = 255
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


if __name__ == '__main__':
    out, prev, art = sys.argv[1], sys.argv[2], sys.argv[3]
    tex.save(f'{out}/textures/entity/hill_troll.png')
    glow.save(f'{out}/textures/entity/hill_troll_glow.png')
    open(f'{prev}/hill_troll.kt.txt', 'w').write(model.kotlin())
    json.dump(model.bbmodel(tex, 'hill_troll'), open(f'{art}/hill_troll.bbmodel', 'w'))
    sheet = Image.new('RGBA', (4 * 300, 400))
    for i, yaw in enumerate([25, 160, -70, 90]):
        sheet.paste(model.render(tex, yaw=yaw, scale=6, size=(300, 400), glow=lit(glow)), (i * 300, 0))
    sheet.save(f'{prev}/hill_troll_preview.png'); sheet.save(f'{art}/hill_troll_preview.png')
    tex.resize((512, 512), Image.NEAREST).save(f'{prev}/hill_troll_tex.png')
