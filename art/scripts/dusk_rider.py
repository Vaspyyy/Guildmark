"""The Dusk Rider: one of the Wardens' outriders, who carried the lamp from Wardfire to Wardfire at dusk, fell on the
road when the Hollow King rose and rose hollow, still riding it. A gaunt black destrier in a ragged night-blue
caparison with tarnished gold trim and an iron chanfron, its mane and tail gone pale as ash; on its back a hollow
knight in blackened plate under a tattered cloak, a crested helm with a pale plume and a narrow lit visor, a long
halberd in the right hand and the Wardens' lantern hanging from the left. In its second phase cold fire takes the
steed's mane, tail and hooves.

Unlike the other creatures this is no humanoid: the parts nest (steed, then rider, then arms, halberd and lantern),
so moving the steed carries the rider, and the game poses it with the rig animator from clips keyed on these names
(art/scripts/dusk_rider_anims.py).

Glow works by exact marker colours, as for the Fallen Warden: the eyes, the visor slit, the lantern's glass and the
flames are painted as exact colours that each glow map swaps for light. Phase one lights the eyes and the lantern;
phase two lights them brighter, and the flames too (the flame parts only show in phase two).

Writes the texture, both glow maps, a .bbmodel, a preview sheet and the Kotlin body."""
import sys, json, random
sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0] if '/' in sys.argv[0] else '.')
from modelkit import *
from PIL import Image
import numpy as np

COAT = (40, 39, 46); COAT_D = (27, 26, 32); COAT_L = (60, 59, 68); RIB = (70, 68, 78)
MANE = (178, 184, 192); MANE_D = (126, 132, 142); MANE_L = (214, 218, 224)
HOOF = (34, 31, 33); HOOF_L = (60, 55, 56)
IRON = (54, 56, 64); IRON_D = (37, 39, 46); IRON_L = (88, 92, 104); GAP = (20, 20, 27)
GOLD = (146, 116, 56); GOLD_D = (100, 78, 40); GOLD_L = (182, 148, 78)
CLOTH = (40, 48, 82); CLOTH_D = (27, 33, 58); CLOTH_L = (58, 68, 104)
CLOAK = (36, 32, 42); CLOAK_D = (24, 22, 30); CLOAK_L = (52, 47, 59)
LEATHER = (68, 47, 33); LEATHER_D = (45, 31, 23)
HAFT = (78, 57, 38); HAFT_D = (52, 38, 27)
STEEL = (138, 144, 156); STEEL_D = (98, 104, 116); STEEL_L = (190, 196, 206)
CL = (0, 0, 0, 0)
# Exact marker colours (no grain) the glow maps key on; none sits one even step off a palette colour
EYE = (96, 136, 176, 255); SLIT = (22, 30, 44, 255); LAMP = (176, 196, 206, 255); CORE = (226, 236, 240, 255)
FLAME = (88, 146, 204, 255); FLAME_L = (168, 208, 238, 255); FLAME_D = (52, 92, 150, 255)
GLOW1 = {EYE: (110, 170, 230, 255), SLIT: (40, 74, 120, 255), LAMP: (120, 176, 214, 255), CORE: (200, 230, 255, 255)}
GLOW2 = {EYE: (190, 236, 255, 255), SLIT: (90, 150, 220, 255), LAMP: (170, 220, 255, 255), CORE: (240, 250, 255, 255),
         FLAME: (90, 170, 255, 255), FLAME_L: (200, 236, 255, 255), FLAME_D: (40, 100, 200, 255)}

P = {'C': COAT, 'c': COAT_D, 'K': COAT_L, 'r': RIB, 'M': MANE, 'm': MANE_D, 'N': MANE_L, 'H': HOOF, 'h': HOOF_L,
     'I': IRON, 'i': IRON_D, 'J': IRON_L, 'k': GAP, 'G': GOLD, 'g': GOLD_D, 'Y': GOLD_L,
     'B': CLOTH, 'b': CLOTH_D, 'U': CLOTH_L, 'V': CLOAK, 'v': CLOAK_D, 'u': CLOAK_L, 'L': LEATHER, 'l': LEATHER_D,
     'W': HAFT, 'w': HAFT_D, 'S': STEEL, 's': STEEL_D, 'T': STEEL_L, '.': CL,
     'E': EYE, 'e': SLIT, 'O': LAMP, 'o': CORE, 'F': FLAME, 'f': FLAME_D, 'Q': FLAME_L}

lay = random.Random(23)  # where wear, folds and tears fall; the kit's own rnd does the grain


def painter(fn):
    """A painter from fn(face, w, h) -> rows of palette characters, sized to the face."""
    def p(img, face, x, y, w, h):
        rows = fn(face, w, h)
        if rows is not None:
            grid({face: rows}, P)(img, face, x, y, w, h)
    return p


def over(base, top):
    """Lay the rows of top over base; '_' keeps the base character (and so does anything top leaves out)."""
    top = list(top) + [''] * (len(base) - len(top))
    return [''.join(b if t == '_' else t for b, t in zip(br, tr.ljust(len(br), '_'))) for br, tr in zip(base, top)]


def mirror(rows): return [r[::-1] for r in rows]


def C(o, s, p, name=None, **kw): return Cube(o, s, p, name=name, **kw)


# ---- the steed's hide: black, dull, the ribs showing through on its flanks ----
def hide_rows(face, w, h, ribs=False, sheen=True):
    rows = []
    for j in range(h):
        r = ''
        for i in range(w):
            x = lay.random()
            c = 'c' if x < 0.14 else 'K' if x < 0.19 else 'C'
            if sheen and face != 'bottom' and j == 0 and face != 'top': c = 'K'
            if face == 'top' and x < 0.3: c = 'K'
            if face == 'bottom': c = 'c' if x < 0.6 else 'C'
            r += c
        rows.append(r)
    if ribs and face in ('left', 'right'):
        # Gaunt: a ladder of ribs down the middle of each flank, under the caparison's gap
        for k, i0 in enumerate(range(w // 2 - 4, w // 2 + 5, 2)):
            for j in range(3, h - 3):
                if 0 <= i0 < w and (j + k) % 5 != 0:
                    rows[j] = rows[j][:i0] + 'r' + rows[j][i0 + 1:]
    return rows


hide = lambda ribs=False: painter(lambda f, w, h: hide_rows(f, w, h, ribs))


def mane_rows(face, w, h, ragged='bottom'):
    """Pale, matted hair in strands, its free end torn into wisps."""
    rows = []
    for j in range(h):
        r = ''
        for i in range(w):
            c = 'M' if (i + j // 3) % 3 else 'm'
            if lay.random() < 0.12: c = 'N'
            r += c
        rows.append(r)
    if ragged == 'bottom' and face in ('front', 'back', 'left', 'right'):
        cut = [lay.choice((0, 1, 1, 2, 3)) for _ in range(w)]
        rows = [''.join('.' if j >= h - cut[i] else rows[j][i] for i in range(w)) for j in range(h)]
    return rows


mane = painter(lambda f, w, h: mane_rows(f, w, h))


def hoof_rows(face, w, h):
    return ['h' * w] + ['H' * w for _ in range(h - 1)] if face not in ('top', 'bottom') else ['H' * w] * h


def feather_rows(face, w, h):
    """The long pale hair round the fetlock, hanging over the hoof in wisps."""
    if face == 'top':
        return ['M' * w] * h
    if face == 'bottom':
        return ['.' * w] * h
    return [''.join('M' if (i + j) % 3 else 'm' for i in range(w)) if j == 0 else
            ''.join(('m' if (i + j) % 2 else 'M') if lay.random() < 0.7 else '.' for i in range(w)) for j in range(h)]


# ---- iron: the chanfron, the rider's plate ----
def plate_rows(face, w, h, trim=True, cracks=0, rivets=True):
    rows = []
    for j in range(h):
        r = ''
        for i in range(w):
            c = 'I'
            if j == 0 and face not in ('top', 'bottom'): c = 'J'
            if face == 'top': c = 'J' if lay.random() < 0.5 else 'I'
            if face == 'bottom': c = 'i'
            if trim and j == h - 1 and face not in ('top', 'bottom') and h > 2: c = 'g'
            if lay.random() < 0.06 and c == 'I': c = 'i'
            r += c
        rows.append(r)
    if rivets and face in ('front', 'back', 'left', 'right') and w >= 4 and h >= 4:
        for (i, j) in ((1, 1), (w - 2, 1)):
            rows[j] = rows[j][:i] + 'J' + rows[j][i + 1:]
    for _ in range(cracks):
        if face in ('front', 'left', 'right') and w > 3 and h > 3:
            i, j = lay.randrange(1, w - 1), lay.randrange(1, h - 2)
            for k in range(3):
                if 0 <= i + k // 2 < w and j + k < h:
                    rows[j + k] = rows[j + k][:i + k // 2] + 'k' + rows[j + k][i + k // 2 + 1:]
    return rows


plate = lambda trim=True, cracks=0: painter(lambda f, w, h: plate_rows(f, w, h, trim, cracks))


# ---- cloth: the caparison and the rider's cloak, worn and torn ----
def cloth_rows(face, w, h, base='B', fold='b', light='U', trim='G', hem=True, sigil=None):
    rows = []
    for j in range(h):
        r = ''
        for i in range(w):
            c = base
            if i % 4 == 2: c = fold
            if j == 0 and trim: c = trim
            elif j == 1 and trim: c = light if c == base else base
            if lay.random() < 0.05: c = fold
            r += c
        rows.append(r)
    if sigil and face in sigil[0]:
        x0 = (w - len(sigil[1][0])) // 2
        for k, pat in enumerate(sigil[1]):
            j = sigil[2] + k
            if 0 <= j < h:
                rows[j] = rows[j][:x0] + ''.join(b if t == '_' else t for b, t in zip(rows[j][x0:], pat)) + rows[j][x0 + len(pat):]
    if hem and face in ('front', 'back', 'left', 'right') and h > 4:
        cuts = [(lay.random() < 0.45, lay.random() < 0.7) for _ in range(w)]
        rows[h - 3] = ''.join(fold if a else rows[h - 3][i] for i, (a, b) in enumerate(cuts))
        rows[h - 2] = ''.join('.' if a else fold for a, b in cuts)
        rows[h - 1] = ''.join('.' if (a or b) else fold for a, b in cuts)
    if face == 'bottom':
        rows = ['.' * w] * h
    return rows


# The Wardens' lantern over the road, in faded gold thread
SIGIL = ["__gg__", "_gggg_", "_gYYg_", "_gggg_", "_g__g_", "g____g"]
SIGIL_S = ["_gg_", "gYYg", "gggg", "g__g"]
drape = lambda sigil=False: painter(lambda f, w, h: cloth_rows(f, w, h, sigil=(('left', 'right'), SIGIL, 3) if sigil else None))
cloak_paint = painter(lambda f, w, h: cloth_rows(f, w, h, 'V', 'v', 'u', 'g', sigil=(('back',), SIGIL, 3)) if f != 'front'
                      else [''.join('.' if ch == '.' else 'v' for ch in r) for r in cloth_rows(f, w, h, 'V', 'v', 'u', 'g')])


# ---- the flames of its second phase: tongues of cold fire, hollow between ----
def flame_rows(face, w, h, up=True):
    rows = []
    tongues = [lay.randrange(h // 3, h) for _ in range(w)]
    for j in range(h):
        r = ''
        for i in range(w):
            depth = (h - 1 - j) if up else j          # how far from the flame's root
            if depth >= tongues[i]:
                c = '.'
            elif depth > tongues[i] - 2:
                c = 'Q'
            elif lay.random() < 0.3:
                c = 'f'
            else:
                c = 'F'
            r += c
        rows.append(r)
    if face in ('top', 'bottom'):
        rows = [''.join('F' if lay.random() < 0.5 else '.' for _ in range(w)) for _ in range(h)]
    return rows


flame = lambda up=True: painter(lambda f, w, h: flame_rows(f, w, h, up))

# ================================================================== the steed

barrel = hide(ribs=True)
chest = hide()
rump = painter(lambda f, w, h: hide_rows(f, w, h) if f != 'back' else
               over(hide_rows(f, w, h), ['_' * w] * (h - 2) + ['_' + 'c' * (w - 2) + '_'] + ['_' * w]))
neck = hide()
skull = painter(lambda f, w, h: over(hide_rows(f, w, h), ['_' * w, '_' * w, '_E' + '_' * (w - 2), '_' * w, '_' * w][:h])
                if f in ('left', 'right') else hide_rows(f, w, h))
muzzle = painter(lambda f, w, h: ['c' * w] * h if f == 'front' else hide_rows(f, w, h, sheen=False))
ear = hide()

# The chanfron: an iron face plate with a ridge down the middle, eye guards and a short spike
chanfron = painter(lambda f, w, h: (['JJJJJJ', 'IIJJII', 'IIGGII', 'iIJJIi', 'IIJJII', 'iIJJIi', 'IIIIII', 'iiiiii', 'IIIIII'][:h]
                                    if f == 'top' else plate_rows(f, w, h, trim=True)))
eye_guard = plate(trim=False)
spike = painter(lambda f, w, h: ['J' * w] * h if f != 'bottom' else ['i' * w] * h)

saddle = painter(lambda f, w, h: (['L' * w] * h if f == 'top' else
                                  ['g' * w] + [''.join('l' if (i + j) % 5 == 0 else 'L' for i in range(w)) for j in range(h - 1)]))
cantle = painter(lambda f, w, h: ['G' * w] + ['L' * w] * (h - 1) if f not in ('top', 'bottom') else ['L' * w] * h)
pommel = painter(lambda f, w, h: ['Y' * w] * h if f == 'top' else ['G' * w] * h)

thigh = hide()
shin = hide()
hoof = painter(hoof_rows)
feather = painter(feather_rows)
tail = painter(lambda f, w, h: mane_rows(f, w, h))
mane_paint = mane
forelock = painter(lambda f, w, h: mane_rows(f, w, h))

leg_parts = {}


def steed_leg(name, x, z, front):
    """A leg in two parts: thigh, then shin with its hoof and the hair over it (and the flame round it later)."""
    t = (4, 9, 4) if front else (4, 10, 5)
    to = (-2, -1, -2) if front else (-2, -2, -2.5)
    shin_part = Part(name + '_shin', (0, 8, 0 if front else 0.5), cubes=[
        C((-1.5, 0, -1.5), (3, 4.5, 3), shin, name + '_shin_c'),
        C((-2, 4.5, -2), (4, 1.5, 4), hoof, name + '_hoof'),
        C((-2.5, 3, -2.5), (5, 2, 5), feather, name + '_feather'),
    ], children=[Part(name + '_fire', (0, 4, 0), cubes=[C((-2.5, -4, -2.5), (5, 5, 5), flame(), name + '_fire_c')])])
    return Part(name + '_leg', (x, 3, z), cubes=[C(to, t, thigh, name + '_thigh')], children=[shin_part])


horse_head = Part('horse_head', (0, -12.5, -1.5), (28, 0, 0), cubes=[
    C((-3, -3, -6), (6, 5, 7), skull, 'skull'), C((-2.5, -2, -11), (5, 4, 5), muzzle, 'muzzle'),
    C((-3, -3.4, -11.3), (6, 2, 9), chanfron, 'chanfron', inflate=0.1),
], children=[
    Part('ear_r', (-2, -3, 0), (-10, 0, -15), cubes=[C((-0.5, -3, -0.5), (1, 3, 2), ear, 'ear_r')]),
    Part('ear_l', (2, -3, 0), (-10, 0, 15), cubes=[C((-0.5, -3, -0.5), (1, 3, 2), ear, 'ear_l')]),
    Part('horn', (0, -3.6, -7), (-35, 0, 0), cubes=[C((-0.5, -3, -0.5), (1, 3, 1), spike, 'horn_c')]),
    Part('forelock', (0, -3, -1), (-20, 0, 0), cubes=[C((-1.5, -0.5, -4), (3, 1, 4), forelock, 'forelock_c')]),
])
neck_part = Part('neck', (0, -4, -9), (20, 0, 0), cubes=[
    C((-2.5, -13, -3.5), (5, 14, 7), neck, 'neck_c'),
], children=[
    horse_head,
    Part('mane', (0, -14, 2.5), cubes=[C((-1, 0, 0), (2, 15, 2.5), mane_paint, 'mane_c')]),
    Part('mane_fire', (0, -14, 2.5), cubes=[C((-1.5, -2, -0.5), (3, 17, 4), flame(), 'mane_fire_c')]),
])
tail_part = Part('tail', (0, -5, 11.5), (55, 0, 0), cubes=[C((-1.5, -0.5, -1), (3, 7, 3), tail, 'tail_c')], children=[
    Part('tail_end', (0, 6.5, 0), (-30, 0, 0), cubes=[C((-2, 0, -1.5), (4, 9, 3), tail, 'tail_end_c')]),
    Part('tail_fire', (0, 0, 0), cubes=[C((-2, -0.5, -1.5), (4, 9, 4), flame(up=False), 'tail_fire_c')]),
])

# ================================================================== the rider

cuirass = painter(lambda f, w, h: over(plate_rows(f, w, h, trim=False, cracks=1),
                                      ['', '', '___gg___', '__gYYg__', '__gggg__', '__g__g__', '_g____g_'])
                  if f == 'front' and w == 8 else plate_rows(f, w, h, trim=False, cracks=1))
fauld = painter(lambda f, w, h: ['GGGGGGGG'[:w]] + [''.join('J' if j % 2 == 0 else 'i' for _ in range(w)) for j in range(h - 1)]
                if f not in ('top', 'bottom') else ['i' * w] * h)
helm = painter(lambda f, w, h: (['JJJJJJJJ', 'IIIIIIII', 'IIIGIIII', 'IIIGIIII', 'eeeeeeee', 'IEeIIeEI', 'IIIGIIII', 'gggggggg']
                                if f == 'front' else plate_rows(f, w, h, trim=True, rivets=False)))
crest = painter(lambda f, w, h: ['Y' * w] + ['G' * w] * (h - 1) if f not in ('bottom',) else ['g' * w] * h)
plume = painter(lambda f, w, h: mane_rows(f, w, h, ragged=None) if f != 'back' else ['M' * w] * h)
pauldron = plate(trim=True)
arm_c = painter(lambda f, w, h: [''.join('i' if (i + j) % 3 == 0 else 'I' for i in range(w)) for j in range(h)]
                if f not in ('top', 'bottom') else ['I' * w] * h)
gauntlet = plate(trim=True)
thigh_r = plate(trim=False)
greave = plate(trim=True)
sabaton = plate(trim=False)
cloak_p = cloak_paint
collar = painter(lambda f, w, h: ['u' * w] + ['V' * w] * (h - 1))

haft = painter(lambda f, w, h: (['W' if (j // 3) % 4 else 'w' for j in range(h)] and
                                [''.join('W' if (j + i) % 7 else 'w' for i in range(w)) for j in range(h)]))
socket = plate(trim=False)
axe = painter(lambda f, w, h: (['T' * w] + ['S' * w] * (h - 2) + ['T' * w] if f in ('front', 'back') else
                               [''.join('T' if i >= w - 1 else 'S' if (i + j) % 4 else 's' for i in range(w)) for j in range(h)]))
beak = painter(lambda f, w, h: ['s' * w] * h)
point = painter(lambda f, w, h: ['T' * w] * h)

chain = painter(lambda f, w, h: [''.join('i' if j % 2 else 'J' for _ in range(w)) for j in range(h)])
cage = painter(lambda f, w, h: (['iiii', 'i..i', 'iiii', 'i..i', 'iiii'] if f in ('front', 'back', 'left', 'right')
                                else ['iiii'] * h))
glass = painter(lambda f, w, h: ['OOO', 'OoO', 'OoO', 'OOO'] if f not in ('top', 'bottom') else ['O' * w] * h)
cap = painter(lambda f, w, h: ['J' * w] + ['i' * w] * (h - 1) if f != 'top' else ['J' * w] * h)

halberd = Part('halberd', (-1, 10, 0), (-90, 0, 0), cubes=[
    C((-0.75, -0.75, -27), (1.5, 1.5, 35), haft, 'haft'),
    C((-1, -1, -29), (2, 2, 3), socket, 'socket'),
    C((-0.5, 1, -33), (1, 5, 8), axe, 'axe'),
    C((-0.5, -4, -31), (1, 3, 2), beak, 'beak'),
    C((-0.5, -0.5, -38), (1, 1, 6), point, 'spearpoint'),
    C((-1, -1, 7), (2, 2, 1.5), socket, 'butt'),
])
lantern = Part('lantern', (1, 10, 0), cubes=[C((-0.5, 0, -0.5), (1, 3, 1), chain, 'lantern_chain')], children=[
    Part('lamp', (0, 3, 0), cubes=[
        C((-2, 1, -2), (4, 5, 4), cage, 'cage'), C((-2.5, 0, -2.5), (5, 1, 5), cap, 'cap'),
        C((-1.5, 1.5, -1.5), (3, 4, 3), glass, 'glass'), C((-2, 6, -2), (4, 1, 4), cap, 'base'),
    ]),
])

rider_head = Part('rider_head', (0, -12, 0), cubes=[
    C((-4, -8, -4), (8, 8, 8), helm, 'helm'), C((-0.5, -10, -3.5), (1, 2, 8), crest, 'crest'),
], children=[Part('plume', (0, -9.5, 3.5), (-38, 0, 0), cubes=[C((-1.5, -0.5, -0.5), (3, 1.5, 9), plume, 'plume_c')])])

right_arm = Part('rider_right_arm', (-5, -10, 0), (-18, 0, 8), cubes=[
    C((-3, -2, -2), (4, 12, 4), arm_c, 'rarm'), C((-3, 6, -2), (4, 4, 4), gauntlet, 'rgauntlet', inflate=0.4),
    C((-4.5, -3, -3), (6, 4, 6), pauldron, 'rpauldron'),
], children=[halberd])
left_arm = Part('rider_left_arm', (5, -10, 0), (-30, 0, -6), cubes=[
    C((-1, -2, -2), (4, 12, 4), arm_c, 'larm'), C((-1, 6, -2), (4, 4, 4), gauntlet, 'lgauntlet', inflate=0.4),
    C((-1.5, -3, -3), (6, 4, 6), pauldron, 'lpauldron'),
], children=[lantern])

rider_body = Part('rider_body', cubes=[
    C((-4, -12, -2), (8, 12, 4), cuirass, 'cuirass', inflate=0.4), C((-4, -3, -2), (8, 3, 4), fauld, 'fauld', inflate=0.8),
], children=[
    rider_head, right_arm, left_arm,
    Part('cloak', (0, -11.5, 2.4), (36, 0, 0), cubes=[C((-5, 0, 0), (10, 15, 1), cloak_p, 'cloak_c'),
                                                       C((-4.5, -1, -0.5), (9, 2, 2), collar, 'collar')]),
])


def rider_leg(side):
    s = -1 if side == 'r' else 1
    return Part(f'rider_{"right" if side == "r" else "left"}_leg', (2.4 * s, -1, 0), (-34, 0, -26 * s), cubes=[
        C((-2, 0, -2), (4, 7, 4), thigh_r, f'{side}cuisse', inflate=0.2)],
        children=[Part(f'{side}_shin', (0, 6.5, 0), (40, 0, 0), cubes=[
            C((-2, 0, -2), (4, 6, 4), greave, f'{side}greave', inflate=0.2),
            C((-2, 5, -4.5), (4, 1.5, 6), sabaton, f'{side}sabaton')])])


rider = Part('rider', (0, -8, 1), children=[rider_body, rider_leg('r'), rider_leg('l')])

steed = Part('steed', (0, 7, 0), cubes=[
    C((-5, -6, -11), (10, 10, 22), barrel, 'barrel'),
    C((-4.5, -5, -13.5), (9, 9, 3), chest, 'chest'),
    C((-5.5, -6.5, 5.5), (11, 7, 6.5), rump, 'rump'),
    C((-4, -8, -3), (8, 2, 9), saddle, 'saddle'),
    C((-3.5, -10, 5), (7, 2, 1.5), cantle, 'cantle'),
    C((-2, -9.5, -4), (4, 1.5, 1), pommel, 'pommel'),
    # The caparison hangs in four drapes, a gap left on each side for the rider's legs
    C((-6, -5.5, -12), (1, 12, 9), drape(), 'drape_fr'), C((5, -5.5, -12), (1, 12, 9), drape(), 'drape_fl'),
    C((-6.3, -6.3, 4), (1, 13, 9), drape(sigil=True), 'drape_br'), C((5.3, -6.3, 4), (1, 13, 9), drape(sigil=True), 'drape_bl'),
    C((-5.5, -5.5, -14), (11, 9, 1), drape(), 'drape_chest'),
], children=[
    neck_part, tail_part, rider,
    steed_leg('fr', -3, -8, True), steed_leg('fl', 3, -8, True),
    steed_leg('hr', -3, 8, False), steed_leg('hl', 3, 8, False),
])

model = Model('dusk_rider', 256, 128, [steed])
model.pack()
tex = model.paint()


def glow_map(colors):
    """Swap each exact marker colour for its light; everything else stays fully clear."""
    arr = np.array(tex); g = np.zeros_like(arr)
    for src, dst in colors.items():
        g[np.all(arr == np.array(src), axis=-1)] = dst
    return Image.fromarray(g, 'RGBA')


glow = glow_map(GLOW1)
glow2 = glow_map(GLOW2)


def lit(g):
    """The eyes render type adds the glow onto the lit texture; approximate that for the preview."""
    a = np.array(tex).astype(int); b = np.array(g).astype(int)
    on = b[..., 3] > 0
    out = np.zeros_like(a); out[on, :3] = np.clip(a[on, :3] + b[on, :3], 0, 255); out[on, 3] = 255
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


FLAME_PARTS = {'mane_fire', 'tail_fire', 'fr_fire', 'fl_fire', 'hr_fire', 'hl_fire'}


def without(parts):
    """The model with the named parts left out (the flames, before its second phase)."""
    def strip(p):
        return Part(p.name, p.pivot, p.rot, p.cubes, [strip(c) for c in p.children if c.name not in parts])
    m = Model(model.name, model.tex_w, model.tex_h, [strip(p) for p in model.parts])
    return m


if __name__ == '__main__':
    out, prev, art = sys.argv[1], sys.argv[2], sys.argv[3]
    tex.save(f'{out}/textures/entity/dusk_rider.png')
    glow.save(f'{out}/textures/entity/dusk_rider_glow.png')
    glow2.save(f'{out}/textures/entity/dusk_rider_glow2.png')
    open(f'{prev}/dusk_rider.kt.txt', 'w').write(model.kotlin())
    json.dump(model.bbmodel(tex, 'dusk_rider'), open(f'{art}/dusk_rider.bbmodel', 'w'))
    calm = without(FLAME_PARTS)
    sheet = Image.new('RGBA', (4 * 360, 480))
    for i, (yaw, m, g) in enumerate([(30, calm, glow), (150, calm, glow), (-90, calm, glow), (-35, model, glow2)]):
        sheet.paste(m.render(tex, yaw=yaw, scale=5, size=(360, 480), glow=lit(g), y_shift=22), (i * 360, 0))
    sheet.save(f'{prev}/dusk_rider_preview.png'); sheet.save(f'{art}/dusk_rider_preview.png')
    tex.resize((1024, 512), Image.NEAREST).save(f'{prev}/dusk_rider_tex.png')
