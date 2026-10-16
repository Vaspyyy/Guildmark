"""The factions' art: the Hearthsworn's sworn knight, the new items and the ward-light.

The sworn knight is a living Hearthsworn knight on the Hollow Soldier's own model (hollow_soldier.py, unchanged, its UV
layout and 2x size): a bright steel kettle helm over a mail coif with a stern human face under the brim, clean steel
plate and mail, a deep red tabard with the gold hearth flame on the chest and on its hanging flap, a leather belt, steel
spaulders and vambraces, brown leather boots with steel toes. Every cube face is painted on its own, natively at twice the
declared size; the two arrow cubes (hidden in game) keep the soldier's pixels.

The items are 32x in the house style (hdkit: a few hard tones per material, light from the upper left, a fine dark
outline), each with its model and item definition: the purse, the four heist prizes (one item definition that picks
between them by custom model data), the Starwatch Staff, Widow's Kiss and Hearthbrand. The ward-light is a soft
full-bright orb on a camera-facing quad.

    python3 -B art/scripts/faction_art.py src/main/resources/assets/guildmark [preview-dir]

Everything is seeded, so a rerun gives the same files.
"""
import json
import math
import os
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import hdkit
from hdkit import (PAL, GEMS, bands, light, poly, grow, bevel, edge, Canvas, outline, rim, glint, sheet, tone, vnoise, fbm,
                   grid, disc, ellipse, rect, path_dist)
import weaponkit

S = 2                      # the sworn knight's texture is shipped at twice the soldier model's declared size


def seed_for(*keys):
    return zlib.crc32('/'.join(str(k) for k in keys).encode())


# ================================================================ the sworn knight

STEEL = PAL['steel']
LEATHER = PAL['leather']
GOLD = PAL['gold']
TABARD = [(34, 6, 12), (62, 12, 20), (92, 18, 26), (124, 28, 34), (156, 46, 44)]            # a deep red
SKIN = [(82, 52, 48), (130, 88, 72), (176, 124, 98), (210, 156, 122), (234, 184, 148)]       # warm, living


def surface(W, H):
    """A face's canvas, opaque, to paint on."""
    cv = Canvas(W, H)
    cv.a[:] = 1.0
    return cv


def toned(W, H, ramp, t, dither=0.2):
    cv = surface(W, H)
    cv.rgb = bands(ramp, t, dither).astype(float)
    return cv


def k_mail(W, H, seed, base=0.44, phase=0):
    """Bright riveted mail: rings four pixels across and two down, every other row of them shifted half a ring, each lit at
    its top left with a dark hollow at its bottom right."""
    ys, xs = np.mgrid[0:H, 0:W]
    cx = (xs + ((ys // 2 + phase) % 2) * 2) % 4
    cy = ys % 2
    t = np.where((cx == 0) & (cy == 0), base + 0.2, np.where((cx >= 2) & (cy == 1), base - 0.2, base))
    t = t + 0.06 * (vnoise(W, H, 6, seed) - 0.5)
    return toned(W, H, STEEL, t, 0.1)


def k_plate(W, H, seed, base=0.52, sheen=0.07, bevel_px=2.0):
    """Polished plate: edges bevelled to the light (lit on the left and top, shaded on the right and bottom), a faint
    brushing, a soft sheen across the upper part and one thin bright streak."""
    xs, ys = grid(W, H)
    d = np.minimum.reduce([xs, W - xs, ys, H - ys])
    h = np.clip(d, 0, bevel_px) * 0.6
    t = base + 1.5 * light(h)
    t += 0.035 * (vnoise(W, H, 2.5, seed) - 0.5) + sheen * (1 - ys / H) * 0.8
    t += 0.09 * np.exp(-((xs - ys * 0.45 - W * 0.32) ** 2) / (2 * 1.3 ** 2))
    return toned(W, H, STEEL, t, 0.18)


def k_leather(W, H, seed, base=0.5, ramp=LEATHER, along_x=True):
    xs, ys = grid(W, H)
    grain = vnoise(W, H, 2.0, seed) if along_x else vnoise(W, H, 2.0, seed)
    t = base + 0.18 * (grain - 0.5) + 0.12 * light(np.clip(np.minimum(ys, H - ys), 0, 1.5) * 0.6)
    return toned(W, H, ramp, t, 0.2)


def k_cloth(W, H, seed, base=0.5, period=7.0, amp=0.28, fall=0.1):
    """The tabard's deep red wool: soft vertical folds lit from the upper left."""
    xs, ys = grid(W, H)
    rng = np.random.default_rng(seed)
    u = (xs + 1.5 * (vnoise(W, H, 6, seed + 1) - 0.5)) / period
    h = np.sin(2 * math.pi * u + rng.random() * 6.283) + 0.4 * np.sin(2 * math.pi * u * 2.3 + rng.random() * 6.283)
    t = base + 1.4 * light(h * amp / 1.4) + 0.03 * (vnoise(W, H, 1.3, seed + 2) - 0.5) - fall * (ys / H)
    return toned(W, H, TABARD, t, 0.2)


# ---------------------------------------------------------------- the hearth flame

FLAME = [(6.2, 0.0), (6.6, 2.0), (7.8, 4.2), (8.8, 6.8), (9.2, 9.4), (8.6, 12.0), (7.0, 14.2), (5.0, 15.0), (3.0, 14.2),
         (1.4, 12.0), (0.8, 9.4), (1.2, 6.8), (2.0, 4.4), (2.4, 3.4), (2.9, 5.2), (3.1, 6.6), (3.8, 4.6), (4.8, 2.6), (5.7, 1.0)]
FLAME_INNER = [(5.0, 4.6), (6.2, 7.0), (6.8, 9.6), (6.2, 12.0), (5.0, 13.0), (3.8, 12.0), (3.2, 9.6), (3.8, 7.0)]
FLAME_CORE = [(5.0, 8.2), (5.7, 9.9), (5.5, 11.6), (5.0, 12.2), (4.5, 11.6), (4.3, 9.9)]


def flame(cv, cx, cy, s, shadow=True):
    """The hearth flame, gold with a paler heart: [s] pixels to a unit, its middle at (cx, cy), drawn with a dark red
    shadow on its lower right and a lit edge on its upper left."""
    def pts(poly_pts, ox=0.0, oy=0.0):
        return [(cx + (x - 5.0 + ox) * s, cy + (y - 7.5 + oy) * s) for x, y in poly_pts]
    W, H = cv.w, cv.h
    if shadow:
        cv.put(poly(pts(FLAME, 0.35, 0.35), W, H), TABARD[0])
    body = poly(pts(FLAME), W, H)
    b = bevel(body)
    cv.put(body, GOLD[3])
    cv.put(body & (b > 0), GOLD[5])
    cv.put(body & (b < 0), GOLD[2])
    inner = poly(pts(FLAME_INNER), W, H)
    cv.put(inner, GOLD[4])
    core = poly(pts(FLAME_CORE), W, H)
    cv.put(core, GOLD[5])


# ---------------------------------------------------------------- the face

FACE_KEY = {
    'M': STEEL[3], 'm': STEEL[4], 'k': STEEL[2], 'K': STEEL[1],
    'D': (96, 60, 54), 'd': SKIN[1], 's': SKIN[2], 'S': SKIN[3], 'h': SKIN[4],
    'b': (62, 42, 32), 'B': (44, 30, 24), 'w': (226, 218, 206), 'i': (58, 86, 124), 'p': (22, 26, 36),
    'l': (156, 90, 80), 'L': (112, 62, 58), 't': (128, 92, 74), 'x': (226, 176, 152),
}


def face_rows():
    """The knight's face, 16 by 16 (the helm's brim hides the top six rows, and the coif frames the rest), set down feature by
    feature: a stern living face, heavy brows slanting to the nose, narrow blue eyes, a tight mouth, a day's stubble and one
    old scar."""
    g = [['M'] * 16 for _ in range(16)]

    def put(y, x0, chars):
        for i, ch in enumerate(chars):
            if ch != '.':
                g[y][x0 + i] = ch

    def both(y, x0, chars):
        put(y, x0, chars)
        put(y, 16 - x0 - len(chars), chars[::-1])
    # the skin the coif leaves: ten pixels wide and eight deep under the brim, in its shadow at the top
    for y in range(6, 14):
        for x in range(3, 13):
            g[y][x] = 's'
    for x in range(3, 13):
        g[6][x] = 'D'
    for x in range(3, 13):
        g[7][x] = 'd' if x not in (7, 8) else 's'
    both(13, 3, 'dd')
    for y in range(14, 16):
        for x in range(4, 12):
            g[y][x] = 'd' if y == 14 else 'M'
    put(14, 5, 'dsssSd')
    # a rim of shade where the coif meets the face
    for y in range(8, 14):
        g[y][3] = 'd'
        g[y][12] = 'd'
    # the brows: thick, slanting down to the nose, close over narrow eyes
    both(7, 4, 'bbB')
    both(8, 5, 'dB')
    # the eyes: a white, the blue iris and a white, with a lid line above and a bag below
    both(9, 4, 'wiw')
    both(10, 4, 'ddd')
    # the nose and its shadow, the cheekbones
    put(9, 7, 'sd')
    put(10, 7, 'Sd')
    put(11, 7, 'Sd')
    put(12, 7, 'dD')
    both(10, 3, 'S')
    both(11, 4, 'S')
    # the mouth: tight and level, a shadow under the lower lip, a hard chin
    put(12, 5, 'tt')
    put(12, 9, 'tt')
    put(13, 5, 'LLLLLL')
    put(13, 7, 'll')
    put(14, 6, 'dd')
    put(14, 8, 'sd')
    # stubble along the jaw and over the lip
    rng = np.random.default_rng(seed_for('knight', 'stubble'))
    for y in range(11, 15):
        for x in range(4, 12):
            if g[y][x] in 'sSd' and rng.random() < 0.28 and not (y == 13 and 5 <= x <= 10):
                g[y][x] = 't'
    # one old scar through the cheek
    for x, y in ((10, 10), (11, 11), (11, 12)):
        g[y][x] = 'x'
    rows = [''.join(r) for r in g]
    assert all(len(r) == 16 for r in rows) and len(rows) == 16
    return rows


def paint_face(face, W, H):
    if face == 'front':
        cv = surface(W, H)
        mail = k_mail(W, H, seed_for('knight', 'coif', 'front'))
        cv.rgb = mail.rgb.copy()
        for j, r in enumerate(face_rows()):
            for i, ch in enumerate(r):
                if ch not in 'Mmk':
                    cv.rgb[j, i] = FACE_KEY[ch]
        return cv
    if face == 'bottom':
        return toned(W, H, STEEL, np.full((H, W), 0.2))
    cv = k_mail(W, H, seed_for('knight', 'coif', face), phase=1 if face == 'back' else 0)
    if face in ('right', 'left'):
        # the coif's edge where it meets the face, at the front
        xs, ys = grid(W, H)
        front_x = (W - xs) if face == 'right' else xs
        cv.rgb = np.where(((front_x < 1.5) & (ys > 6))[..., None], np.array(STEEL[2], float), cv.rgb)
    return cv


# ---------------------------------------------------------------- the helm

def paint_dome(face, W, H):
    if face == 'top':
        return k_plate(W, H, seed_for('knight', 'dome', 'top'), base=0.58, bevel_px=2.5)
    if face == 'bottom':
        return toned(W, H, STEEL, np.full((H, W), 0.3))
    cv = k_plate(W, H, seed_for('knight', 'dome', face), base=0.55, bevel_px=1.2)
    # a riveted band round the brow: a darker course with a rivet every four pixels
    cv.rgb[H - 2:H] = np.array(STEEL[2], float)
    cv.rgb[H - 3, :] = np.array(STEEL[4], float)
    for x in range(1, W, 4):
        cv.rgb[H - 2, x] = STEEL[5]
    return cv


def paint_cap(face, W, H):
    if face == 'top':
        cv = k_plate(W, H, seed_for('knight', 'cap'), base=0.6, bevel_px=2.0)
        cv.rgb[:, W // 2 - 1:W // 2 + 1] = np.array(STEEL[5], float)          # the comb of the helm
        cv.rgb[:, W // 2 + 1:W // 2 + 2] = np.array(STEEL[3], float)
        return cv
    return k_plate(W, H, seed_for('knight', 'cap', face), base=0.58, bevel_px=0.8)


def paint_brim(face, W, H):
    if face == 'top':
        cv = k_plate(W, H, seed_for('knight', 'brim', 'top'), base=0.5, bevel_px=2.5, sheen=0.12)
        return cv
    if face == 'bottom':
        xs, ys = grid(W, H)
        return toned(W, H, STEEL, 0.22 + 0.1 * (ys / H) + 0.04 * (vnoise(W, H, 3, 4) - 0.5), 0.2)
    cv = surface(W, H)
    cv.rgb[:] = STEEL[3]                       # the rolled rim: bright above, dark below
    cv.rgb[H - 1:] = STEEL[2]
    return cv


# ---------------------------------------------------------------- the body

def paint_torso(face, W, H):
    if face in ('right', 'left'):
        cv = k_mail(W, H, seed_for('knight', 'torso', face))
        cv.rgb[18:20] = np.array(LEATHER[3], float)                                      # the belt
        edge_x = slice(0, 1) if face == 'left' else slice(W - 1, W)                      # the tabard's edge
        cv.rgb[2:18, edge_x] = np.array(TABARD[2], float)
        return cv
    if face in ('top', 'bottom'):
        return k_mail(W, H, seed_for('knight', 'torso', face)) if face == 'top' else toned(W, H, STEEL, np.full((H, W), 0.2))
    big = face == 'front'
    cv = k_cloth(W, H, seed_for('knight', 'tabard', face), base=0.52, period=8.0, amp=0.3)
    cv.rgb[:2] = k_mail(W, 2, 3).rgb                                                     # the coif's collar, over the shoulders
    cv.rgb[2, :] = GOLD[4]                                                              # the neckline's gold edge
    cv.rgb[3, :] = TABARD[1]
    cv.rgb[2:20, 0] = GOLD[3]                                                           # the tabard's gold borders
    cv.rgb[2:20, W - 1] = GOLD[2]
    cv.rgb[18:20] = np.array(LEATHER[3], float)                                          # under the belt
    cv.rgb[20:] = k_mail(W, 4, 5).rgb
    if big:
        flame(cv, 8.0, 10.2, 0.92)
    else:
        flame(cv, 8.0, 10.0, 0.7)
        cv.rgb[20:22, 1:W - 1] = np.array(GOLD[3], float)
    return cv


def paint_belt(face, W, H):
    cv = k_leather(W, H, seed_for('knight', 'belt', face), base=0.55)
    if face in ('top', 'bottom'):
        return toned(W, H, LEATHER, np.full((H, W), 0.3))
    if face == 'front':
        cx = W // 2
        cv.rgb[:, cx - 2:cx + 2] = np.array(STEEL[4], float)                           # a steel buckle
        cv.rgb[0, cx - 2:cx + 2] = STEEL[5]
        cv.rgb[H - 1, cx - 2:cx + 2] = STEEL[2]
        cv.rgb[:, cx - 1:cx + 1] = np.array(LEATHER[1], float)
        cv.rgb[0, cx - 1:cx + 1] = LEATHER[2]
    return cv


def paint_flap(face, W, H):
    if face == 'front':
        cv = k_cloth(W, H, seed_for('knight', 'flap'), base=0.5, period=6.0, amp=0.3, fall=0.14)
        flame(cv, 7.0, 7.4, 0.62)
        cv.rgb[:, 0] = GOLD[3]                                                          # the borders, and a gold hem
        cv.rgb[:, W - 1] = GOLD[2]
        cv.rgb[H - 4] = GOLD[4]
        cv.rgb[H - 3] = GOLD[2]
        cv.rgb[H - 2:] = TABARD[1]
        return cv
    if face == 'back':
        cv = k_cloth(W, H, seed_for('knight', 'flap', 'in'), base=0.34, period=6.0, amp=0.2, fall=0.14)
        cv.rgb[H - 3] = GOLD[2]
        return cv
    cv = k_cloth(W, H, seed_for('knight', 'flap', face), base=0.4, period=6.0, amp=0.2)
    return cv


# ---------------------------------------------------------------- limbs

def paint_arm(side, face, W, H):
    if face == 'top':
        return k_mail(W, H, seed_for('knight', 'arm', side, 'top'))
    if face == 'bottom':
        return k_leather(W, H, seed_for('knight', 'arm', side, 'bottom'), base=0.35)
    cv = k_mail(W, H, seed_for('knight', 'arm', side, face), phase=side == 'l')
    # vambrace: a plate over the forearm, rolled at both ends, then the leather gauntlet with a steel cuff
    plate = k_plate(W, 7, seed_for('knight', 'vambrace', side, face), base=0.5, bevel_px=1.5)
    cv.rgb[13:20] = plate.rgb
    cv.rgb[13] = STEEL[4]
    cv.rgb[19] = STEEL[2]
    cv.rgb[20:] = k_leather(W, H - 20, seed_for('knight', 'glove', side, face), base=0.5).rgb
    cv.rgb[20] = STEEL[3]
    cv.rgb[21] = STEEL[2]
    cv.rgb[H - 1] = LEATHER[1]
    # the knuckle line and the finger seams
    cv.rgb[H - 4, 1:W - 1] = LEATHER[4]
    for x in range(2, W - 1, 2):
        cv.rgb[H - 3:H - 1, x] = LEATHER[1]
    return cv


def paint_spaulder(face, W, H):
    if face == 'bottom':
        return toned(W, H, STEEL, np.full((H, W), 0.25))
    if face == 'top':
        cv = k_plate(W, H, seed_for('knight', 'spaulder', 'top'), base=0.56, bevel_px=2.5)
        c = W // 2
        cv.rgb[c - 1:c + 1, c - 1:c + 1] = GOLD[4]                                      # a gold stud
        cv.rgb[c - 1, c - 1] = GOLD[5]
        return cv
    # three lames, each lit along its upper edge and shaded below
    cv = k_plate(W, H, seed_for('knight', 'spaulder', face), base=0.5, bevel_px=1.0)
    for y0 in (0, 2, 4):
        cv.rgb[y0] = STEEL[4]
        cv.rgb[y0 + 1] = np.array(cv.rgb[y0 + 1]) * 0.8
    cv.rgb[H - 1] = STEEL[1]
    return cv


def paint_leg(side, face, W, H):
    if face == 'top':
        return k_cloth(W, H, 7, base=0.3)
    if face == 'bottom':
        return k_leather(W, H, 8, base=0.3)
    xs, ys = grid(W, H)
    cv = toned(W, H, PAL['darkwood'], 0.55 + 0.2 * (vnoise(W, H, 1.5, seed_for('knight', 'hose', side, face)) - 0.5) + 0.2 * (xs < 2))
    cv.rgb[15:17] = LEATHER[2]                                                          # a garter strap below the knee
    cv.rgb[15] = LEATHER[3]
    return cv


def paint_boot(face, W, H):
    if face == 'top':
        return k_leather(W, H, 9, base=0.45)
    if face == 'bottom':
        return toned(W, H, LEATHER, np.full((H, W), 0.12))
    cv = k_leather(W, H, seed_for('knight', 'boot', face), base=0.55)
    cv.rgb[0] = LEATHER[4]                                                              # a turned-down cuff, lit along its top
    cv.rgb[1] = LEATHER[2]
    cv.rgb[H - 1] = LEATHER[0]                                                          # the sole
    xs = W // 2
    cv.rgb[2:4, xs - 1:xs + 1] = STEEL[4]                                                # a steel buckle on the strap
    if face == 'front':
        cap = k_plate(W - 2, 3, 6, base=0.5, bevel_px=1.0)                            # a steel toe cap
        cv.rgb[H - 4:H - 1, 1:W - 1] = cap.rgb
    return cv


PAINTERS = {
    'face': paint_face, 'dome': paint_dome, 'cap': paint_cap, 'brim': paint_brim,
    'torso': paint_torso, 'belt': paint_belt, 'flap': paint_flap,
    'rarm': lambda f, W, H: paint_arm('r', f, W, H), 'larm': lambda f, W, H: paint_arm('l', f, W, H),
    'rspaulder': paint_spaulder, 'lspaulder': paint_spaulder,
    'rleg': lambda f, W, H: paint_leg('r', f, W, H), 'lleg': lambda f, W, H: paint_leg('l', f, W, H),
    'rboot': paint_boot, 'lboot': paint_boot,
}
KEEP = ('shaft', 'stub')        # the arrows: hidden in game, left as the soldier has them


def soldier_model():
    """The Hollow Soldier's own model, packed (hollow_soldier.py builds it on import)."""
    import hollow_soldier
    return hollow_soldier.model


def sworn_knight(assets=None):
    """The knight's texture at twice the soldier's declared size."""
    model = soldier_model()
    W, H = model.tex_w * S, model.tex_h * S
    tex = np.zeros((H, W, 4), np.uint8)
    old = None
    if assets:
        path = os.path.join(assets, 'textures', 'entity', 'hollow_soldier.png')
        if os.path.exists(path):
            old = np.asarray(Image.open(path).convert('RGBA'))
    for c in model.all_cubes():
        for face, (x, y, w, h) in c.rects().items():
            X, Y = x * S, y * S
            if c.name in KEEP:
                if old is not None and old.shape == tex.shape:
                    tex[Y:Y + h * S, X:X + w * S] = old[Y:Y + h * S, X:X + w * S]
                continue
            cv = PAINTERS[c.name](face, w * S, h * S)
            tex[Y:Y + h * S, X:X + w * S] = np.dstack([np.clip(np.round(cv.rgb), 0, 255), np.where(cv.a > 0.5, 255, 0)]).astype(np.uint8)
    return Image.fromarray(tex, 'RGBA'), model


# ================================================================ items

N = 32
ROOT2 = math.sqrt(2.0)
XS, YS = grid(N)
# Every pixel's place along the sprite's diagonal (s, from the bottom-left corner) and across it (w, + toward the upper
# left, the side the light is on): the way weapons_hd.py and magic_hd.py paint their blades and staffs
S_ = (XS + (N - YS)) / ROOT2
W_ = (-XS - (YS - N)) / ROOT2

IRONWOOD = [(12, 10, 13), (24, 20, 24), (38, 33, 38), (54, 47, 52), (72, 63, 66), (94, 83, 82)]       # a cool, nearly black wood
JET = [(5, 5, 9), (12, 12, 19), (24, 24, 36), (44, 46, 64), (84, 90, 116), (170, 178, 204)]            # polished black stone
OXBLOOD = [(22, 6, 10), (46, 10, 16), (74, 16, 22), (104, 26, 30), (134, 40, 40), (166, 66, 56)]       # deep red leather
BLACKBOOK = [(8, 7, 11), (17, 14, 19), (29, 24, 30), (43, 36, 43), (62, 53, 60), (88, 77, 82)]         # black leather, a shade lighter
SILK = [(40, 6, 12), (86, 12, 22), (140, 22, 32), (186, 40, 46), (224, 78, 70), (248, 138, 118)]       # red silk
EMBER = PAL['ember']
FROST = GEMS['frost']
SILVER = [(24, 26, 36), (52, 56, 72), (92, 98, 116), (140, 148, 164), (192, 200, 214), (236, 242, 250)]  # bright, cool silver


def nb(stops, t, dither=0.35):
    """hdkit's bands on the 32 x 32 sprite, taking a plain number for a flat tone too."""
    return bands(stops, np.broadcast_to(np.asarray(t, float), (N, N)), dither)


def at_px(s, w):
    """Pixel position (x, y down) of the sprite point s along the diagonal, w across it."""
    return s / ROOT2 - w / ROOT2, N - (s / ROOT2 + w / ROOT2)


def along(s0, s1):
    return (S_ >= s0) & (S_ < s1)


def lerp_w(s, pts):
    """A half-width that runs through [(s, w), ...] along a blade."""
    xs, ws = zip(*pts)
    return np.interp(s, xs, ws)


def ridge_shade(dw, half, k=1.0):
    """A blade's diamond section: the up-left facet lit, the down-right one in shade, a bright ridge between."""
    h = np.clip(1 - np.abs(dw) / np.maximum(half, 0.3), 0, 1)
    return light(h * 2.6 * k, 1.0), h


def sw_poly(pts):
    return poly([at_px(s, w) for s, w in pts], N)


def sw_disc(s, w, r):
    cx, cy = at_px(s, w)
    xs, ys = XS, YS
    d = np.hypot(xs - cx, ys - cy) / r
    return d <= 1, np.sqrt(np.clip(1 - d * d, 0, 1))


def sw_path(pts):
    """Distance to a polyline given in sprite (s, w) points, and the running length along it."""
    return hdkit.path_dist([at_px(s, w) for s, w in pts], N)


def wrap(cv, mask, pal, s0, pitch=2.2, seed=1, base=0.38):
    """A leather or cord wrap: diagonal turns across the grip, each turn lit on its upper edge."""
    phase = ((S_ - s0) / pitch) % 1.0
    t = base + 0.32 * (phase < 0.5) - 0.18 * (phase > 0.85) + 0.18 * (W_ > 0.3) - 0.22 * (W_ < -0.5)
    t = t + 0.06 * (vnoise(N, N, 1.5, seed) - 0.5)
    cv.put(mask, nb(pal, t, 0.2))


def metal(cv, mask, pal, base=0.5, shade=None, k=1.0):
    """A fitting: lit on its up-left side, darker down-right."""
    t = base + 0.22 * np.clip(W_ / 3, -1, 1) * k + (0 if shade is None else shade)
    cv.put(mask, nb(pal, t, 0.25))


def ball(cv, pal, s, w, r, base=0.45, gain=1.0):
    """A small round knob, lit from the upper left."""
    m, d = sw_disc(s, w, r)
    cv.put(m, nb(pal, base + gain * light(d * 2, 1.0) + 0.2 * d, 0.0))
    return m


def finish(cv, glints=(), rim_k=0.28, rim_skip=None):
    """The edge darkening, the dark outline and any glints; [rim_skip] is a mask whose own edges stay bright."""
    before = cv.rgb.copy()
    rim(cv, rim_k)
    if rim_skip is not None:
        cv.rgb[rim_skip] = before[rim_skip]
    outline(cv, 0.8)
    for x, y, size, col in glints:
        glint(cv, x, y, size, col)
    return cv.image()


def shape(rows, x0, y0, size=N):
    """A hand-drawn pixel picture as a mask: one string per row, any character but '.' or ' ' sets the pixel."""
    m = np.zeros((size, size), bool)
    for j, row in enumerate(rows):
        for i, ch in enumerate(row):
            if ch not in '. ' and 0 <= y0 + j < size and 0 <= x0 + i < size:
                m[y0 + j, x0 + i] = True
    return m


def stamp(cv, mask, ramp, shadow, base=0.62, lit=0.28):
    """Metal pressed onto leather: a dark shadow to its lower right, its upper left edges catching the light."""
    cv.put(np.roll(np.roll(mask, 1, 0), 1, 1) & ~mask, shadow)
    cv.put(mask, nb(ramp, base + lit * bevel(mask), 0.0))


# ---------------------------------------------------------------- the Starwatch Staff

def starwatch_staff():
    """A staff of dark ironwood bound in turns of silver wire, a silver cage at its head holding a small white-blue star."""
    cv = Canvas(N)
    c = 0.18 * np.sin(S_ * 0.21 + 0.4)
    haft = along(2.4, 33.4) & (np.abs(W_ - c) <= 0.85)
    g = vnoise(N, N, 2.6, seed_for('starwatch', 'wood')) - 0.5
    cv.put(haft, nb(IRONWOOD, 0.46 + 0.3 * (W_ - c > 0.25) - 0.24 * (W_ - c < -0.35) + 0.16 * g, 0.3))
    # the grip: close turns of silver wire, the dark wood between them
    grip = along(9.0, 15.4) & (np.abs(W_ - c) <= 1.05)
    ph = ((S_ - 9.0) / 1.6) % 1.0
    wire = grip & (ph < 0.62)
    cv.put(grip, nb(IRONWOOD, 0.3 + 0.1 * (W_ > 0.3), 0.0))
    cv.put(wire, nb(SILVER, 0.6 + 0.22 * (W_ > 0.2) - 0.25 * (W_ < -0.5) - 0.12 * (ph > 0.45), 0.0))
    # slower turns up the haft, and silver rings where the wood changes hands
    for s0, s1 in ((19.0, 27.6),):
        run = along(s0, s1) & (np.abs(W_ - c) <= 0.95)
        ph2 = ((S_ - s0) / 2.7) % 1.0
        cv.put(run & (ph2 < 0.4), nb(SILVER, 0.55 + 0.22 * (W_ > 0.2) - 0.25 * (W_ < -0.5), 0.0))
    for s0 in (4.4, 15.6, 18.0, 28.2):
        metal(cv, along(s0, s0 + 1.1) & (np.abs(W_ - c) <= 1.2), SILVER, 0.58)
    # the butt: a silver cap drawn to a point
    cap = along(0.9, 3.4) & (np.abs(W_) <= 1.25 - 0.28 * (S_ < 1.7))
    metal(cv, cap, SILVER, 0.5)
    # the head: a flared silver collar, then a cage of two arcs and a finial holding the star
    collar = along(30.8, 33.4) & (np.abs(W_) <= 1.05 + 0.75 * np.clip((S_ - 30.8) / 2.6, 0, 1))
    metal(cv, collar, SILVER, 0.58)
    cage = np.zeros((N, N), bool)
    for side in (1, -1):
        d, t = sw_path([(33.0, 1.5 * side), (34.6, 3.1 * side), (37.4, 3.9 * side), (39.8, 3.2 * side),
                        (41.6, 1.9 * side), (42.9, 0.7 * side)])
        prong = d < 0.55
        cage |= prong
        cv.put(prong, nb(SILVER, (0.8 if side > 0 else 0.55) - 0.1 * t / 16.0, 0.0))
    # a third rib down the middle, the star hung in front of it
    mid = along(33.0, 43.0) & (np.abs(W_) < 0.5)
    cv.put(mid, nb(SILVER, 0.42, 0.0))
    ball(cv, SILVER, 43.3, 0.0, 1.05, 0.55, 1.0)
    # the star, upright on the screen whichever way the staff leans: a white heart, pale blue arms
    sx, sy = at_px(37.4, 0.0)
    cx, cy = int(math.floor(sx)), int(math.floor(sy))
    star = {(0, 0): (255, 255, 255)}
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        star[(dx, dy)] = FROST[4]
        star[(2 * dx, 2 * dy)] = FROST[3]
    for dx, dy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        star[(dx, dy)] = FROST[2]
    for (dx, dy), col in star.items():
        cv.px(cx + dx, cy + dy, np.array(col, float))
    return finish(cv, rim_skip=cage | (np.hypot(XS - sx, YS - sy) < 4.0))


# ---------------------------------------------------------------- Widow's Kiss

def tongue(pts, r0, r1, size=N):
    """A tapering stroke along a polyline of sprite points: its mask, and how far along it each pixel is (0..1)."""
    d, run = sw_path(pts)
    total = sum(math.hypot(s1 - s0, w1 - w0) for (s0, w0), (s1, w1) in zip(pts, pts[1:])) * 1.0
    t = np.clip(run / max(total, 1e-6), 0, 1)
    return d <= r0 + (r1 - r0) * t, t


GUARD_HAND = [
    "...X....",
    ".X.X.X..",
    ".X.X.X.X",
    ".X.X.X.X",
    "XXXXXXXX",
    "XXXXXXXX",
    ".XXXXXX.",
    "..XXXX..",
]


def widows_kiss():
    """A slim dagger: a black blade with a polished silver edge, a little silver hand for a guard, a black grip and a pommel
    of jet."""
    cv = Canvas(N)
    half = lerp_w(S_, [(16.6, 1.2), (18.6, 1.9), (23.0, 2.35), (29.0, 2.05), (34.5, 1.15), (38.4, 0.0)])
    blade = along(16.6, 38.6) & (np.abs(W_) <= half)
    lit, h = ridge_shade(W_, half, 1.2)
    bt = 0.36 + 0.5 * lit + 0.2 * h + 0.04 * (vnoise(N, N, 3, seed_for('kiss', 'blade')) - 0.5)
    cv.put(blade, nb(PAL['blacksteel'], bt, 0.3))
    cv.put(blade & (np.abs(W_ - 0.1) < 0.3) & along(18.5, 34.0), nb(PAL['blacksteel'], 0.7, 0.0))      # the ridge catching light
    cv.put(blade & (W_ > half - 0.62), nb(SILVER, 0.9))                                            # the silver edge, bright on the lit side
    cv.put(blade & (W_ < -half + 0.5), nb(SILVER, 0.4))
    cv.put(blade & (S_ > 37.4) & (W_ > -0.3), nb(SILVER, 0.92))
    # the grip, wrapped in black leather, and the jet pommel on a silver collar
    grip = along(9.6, 14.4) & (np.abs(W_) <= 1.0)
    wrap(cv, grip, PAL['blackleather'], 9.6, 1.6, seed_for('kiss', 'grip'), base=0.5)
    metal(cv, along(8.8, 9.8) & (np.abs(W_) <= 1.25), SILVER, 0.55)
    m, d = sw_disc(7.6, 0.0, 1.9)
    cv.put(m, nb(JET, 0.5 + 1.0 * light(d * 2, 1.0) + 0.2 * d, 0.0))
    gx, gy = at_px(7.1, 0.7)
    cv.px(int(gx), int(gy), np.array(JET[5], float))
    # the guard: a little silver hand, open and upright over the blade's root, its fingers up and its thumb out, the grip
    # held in its palm; it is drawn on the screen's own grid, not the blade's diagonal, so that it reads as a hand
    hand = shape(GUARD_HAND, 7, 17)
    stamp(cv, hand, SILVER, nb(PAL['blacksteel'], 0.1, 0.0)[0, 0], 0.6, 0.3)
    gx, gy = at_px(27.0, 1.0)
    return finish(cv, [(int(gx), int(gy), 1, (255, 250, 236))], rim_skip=hand)


# ---------------------------------------------------------------- Hearthbrand

def hearthbrand():
    """A knight's longsword on the vanilla sword's diagonal: a broad steel blade with a faint ember glow down its fuller, a
    red-gold hilt in the shape of the hearth flame."""
    cv = Canvas(N)
    half = lerp_w(S_, [(15.0, 2.0), (18.0, 2.4), (36.0, 2.4), (40.0, 1.7), (43.8, 0.0)])
    blade = along(15.0, 44.0) & (np.abs(W_) <= half)
    lit, h = ridge_shade(W_, half, 0.8)
    bt = 0.5 + 0.8 * lit + 0.2 * h + 0.04 * (vnoise(N, N, 3, seed_for('hearthbrand', 'blade')) - 0.5)
    fuller = along(17.6, 38.4) & (np.abs(W_ + 0.1) < 0.55)
    bt = np.where(fuller, 0.22 + 0.12 * (W_ < -0.1), bt)
    edge_line = blade & (np.abs(W_) > half - 0.7)
    bt = np.where(edge_line & (W_ > 0), 0.9, np.where(edge_line, 0.56, bt))
    cv.put(blade, nb(STEEL, bt))
    glow = fuller & (np.abs(W_ + 0.1) < 0.36)
    fire = 0.12 + 0.24 * np.sin(math.pi * np.clip((S_ - 17.6) / 20.8, 0, 1)) + 0.14 * (vnoise(N, N, 2, seed_for('hearthbrand', 'ember')) - 0.5)
    cv.put(glow, nb(EMBER, fire, 0.5))
    # the guard: a flame laid over the blade's root and two flame tongues curling out and up from it
    for side in (1, -1):
        m, t = tongue([(14.0, 1.6 * side), (13.4, 3.4 * side), (14.2, 5.0 * side), (16.0, 5.8 * side), (18.2, 5.6 * side)], 1.3, 0.2)
        metal(cv, m, GOLD, 0.62 + 0.14 * (side < 0))
    flame_pts = [(12.8, 0.0), (13.6, -2.0), (15.4, -2.5), (17.4, -2.0), (19.4, -0.6), (20.2, 0.3), (19.0, 1.0), (17.2, 2.0), (15.4, 2.5), (13.6, 2.0)]
    boss = sw_poly(flame_pts)
    metal(cv, boss, GOLD, 0.62)
    core = sw_poly([(14.2, 0.0), (15.2, -1.1), (16.8, -0.9), (18.0, 0.0), (16.8, 0.9), (15.2, 1.1)])
    cv.put(core, nb(PAL['wax'], 0.5 + 0.6 * (W_ > 0), 0.0))
    # the grip, wrapped in red leather, and the pommel a drop of flame
    grip = along(7.4, 12.8) & (np.abs(W_) <= 1.05)
    wrap(cv, grip, OXBLOOD, 7.4, 1.8, seed_for('hearthbrand', 'grip'), base=0.5)
    metal(cv, along(12.4, 13.2) & (np.abs(W_) <= 1.3), GOLD, 0.6)
    metal(cv, along(7.0, 7.8) & (np.abs(W_) <= 1.3), GOLD, 0.55)
    m, d = sw_disc(5.8, 0.0, 1.9)
    drop, t = tongue([(5.8, 0.0), (3.4, 0.0)], 1.9, 0.2)
    cv.put(m | drop, nb(GOLD, 0.5 + 0.9 * light(np.maximum(d, 0) * 2, 1.0) + 0.1 * (W_ > 0), 0.0))
    gx, gy = at_px(5.6, 0.0)
    cv.px(int(gx), int(gy), np.array(PAL['wax'][4], float))
    gx, gy = at_px(30.0, 1.2)
    return finish(cv, [(int(gx), int(gy), 1, (255, 250, 236))])


# ---------------------------------------------------------------- the purse

TWINE = [(46, 32, 22), (86, 62, 40), (130, 98, 60), (172, 136, 86), (208, 174, 120), (236, 208, 156)]


def purse():
    """A small leather coin purse: a squat sack drawn in at the neck by a twine drawstring, its ruffled mouth stuffed with a
    couple of gold coins, the knot and two tails of the string hanging over its shoulder."""
    cv = Canvas(N)
    xs, ys = XS, YS
    cx = 15.6
    L = PAL['leather']
    # a coin standing up out of the mouth, another leaning behind it
    behind = None
    for (kx, ky, kr, tilt) in ((18.4, 4.4, 2.3, 1.0), (14.0, 5.5, 3.0, 0.0)):
        sx_ = 0.78 if tilt else 1.0
        coin, cd = ellipse(kx, ky, kr * sx_, kr, N)
        r = np.hypot((xs - kx) / sx_, ys - ky) / kr
        if behind is not None:
            cv.put(grow(coin) & behind, GOLD[0])                                           # a dark line where it lies over the other
        ct = np.where(r > 0.66, 0.62 + 0.26 * (xs - kx < 0) + 0.2 * (ys - ky < 0) - 0.3 * ((xs - kx) + (ys - ky) > kr * 0.6), 0.56 + 0.1 * (xs < kx))
        ct = np.where((r < 0.3), 0.4, ct)
        cv.put(coin, nb(GOLD, ct, 0.0))
        behind = coin if behind is None else behind | coin
    # the ruffle: the neck gathered at the cord and fanning up to a scalloped lip, a pleat to each scallop
    cord_y = 13.0
    ang = np.arctan2(xs - cx, np.maximum(cord_y - ys + 1.6, 0.2))
    lip = 7.9 + 0.8 * np.cos(ang * 7.0)
    fan = 4.3 + (7.6 - 4.3) * np.clip((cord_y - ys) / 5.5, 0, 1)
    ruffle = (ys >= lip) & (ys < cord_y + 0.6) & (np.abs(xs - cx) <= fan)
    pleat = np.sin(ang * 7.0 + math.pi / 2)
    rt = 0.6 + 0.2 * np.sign(pleat) * np.abs(pleat) ** 0.6 + 0.1 * (xs < cx) - 0.1 * (xs > cx + 3)
    cv.put(ruffle, nb(L, rt, 0.0))
    cv.put(ruffle & (ys < lip + 1.1), nb(L, 0.86 + 0.08 * (xs < cx), 0.0))                  # the lip catching the light
    # the sack: widest low down, rounded below, a cylinder lit from the left
    y_n, y_w, y_b, hw_n, hw_m = 13.0, 22.0, 29.6, 4.0, 11.2
    u = np.clip((ys - y_n) / (y_w - y_n), 0, 1)
    hw = np.where(ys < y_w, hw_n + (hw_m - hw_n) * np.sin(u * math.pi / 2) ** 0.9,
                  hw_m * np.clip(1 - np.abs((ys - y_w) / (y_b - y_w)) ** 3.0, 0, 1) ** (1 / 3.0))
    sack = (ys >= y_n - 0.4) & (ys <= y_b) & (np.abs(xs - cx) <= hw)
    dx = (xs - cx) / np.maximum(hw, 0.5)
    hgt = np.sqrt(np.clip(1 - dx * dx, 0, 1)) * np.minimum(hw, 8.0)
    t = 0.56 + 0.75 * light(hgt * 1.1, 1.0) + 0.1 * (vnoise(N, N, 3, seed_for('purse', 'sack')) - 0.5) - 0.05 * ((ys - y_n) / (y_b - y_n))
    # creases fanning down from the neck, each a dark line with a lit lip beside it
    ang2 = np.arctan2(xs - cx, np.maximum(ys - cord_y + 0.6, 0.1))
    rad = np.hypot(xs - cx, ys - cord_y + 0.6)
    for a_ in (-0.55, -0.2, 0.2, 0.55):
        t = np.where((np.abs(ang2 - a_) < 0.045 + 0.002 * rad) & (rad > 2.2) & (rad < 7.5 + 3 * (1 - abs(a_))), t - 0.22, t)
        t = np.where((np.abs(ang2 - a_ - 0.1) < 0.04) & (rad > 2.8) & (rad < 6.5), t + 0.1, t)
    cv.put(sack, nb(L, np.clip(t, 0.2, 1.0), 0.25))
    # the drawstring: two turns round the neck, a knot to the right, two tails over the shoulder
    for yy in (12.0, 13.6):
        band = (np.abs(ys - yy) < 0.72) & (np.abs(xs - cx) <= 4.9 - 0.4 * (yy > 13))
        cv.put(band, nb(TWINE, 0.56 + 0.3 * (ys < yy) - 0.2 * (xs > cx + 3), 0.0))
    knot, kd = disc(19.2, 13.0, 1.7, N)
    cv.put(knot, nb(TWINE, 0.5 + 0.9 * light(kd * 1.8, 1.0) + 0.2 * kd, 0.0))
    for path in ([(19.4, 13.4), (21.0, 17.2), (21.6, 21.4)], [(19.0, 13.6), (17.8, 17.8), (18.6, 22.0)]):
        d, tt = hdkit.path_dist(path, N)
        cv.put(d < 0.62, nb(TWINE, 0.56 + 0.2 * (xs < path[-1][0] + 1) - 0.12 * (tt > 4), 0.0))
        ex, ey = path[-1]
        cv.px(int(ex), int(ey) + 1, np.array(GOLD[3], float))
    # a coin lying against the sack's foot, its face to us: a lit rim, a ring pressed round the field and a stamp in it
    kx, ky, kr = 22.4, 24.6, 3.5
    coin, cd = disc(kx, ky, kr, N)
    r = np.hypot(xs - kx, ys - ky) / kr
    cv.put(grow(coin) & ~coin, GOLD[0])
    ct = np.where(r > 0.74, 0.7 + 0.3 * ((xs - kx) + (ys - ky) < -0.6) - 0.34 * ((xs - kx) + (ys - ky) > 1.2), 0.56 + 0.1 * (xs < kx))
    ct = np.where((r > 0.52) & (r <= 0.62), 0.36, ct)
    cv.put(coin, nb(GOLD, ct, 0.0))
    cv.put(rect(int(kx) - 1, int(ky) - 1, int(kx) + 1, int(ky) + 1, N) & coin, nb(GOLD, 0.84, 0.0))
    cv.px(int(kx) - 1, int(ky) - 1, np.array(GOLD[5], float))
    return finish(cv, [(12, 3, 1, (255, 244, 208))])


# ---------------------------------------------------------------- the heist prizes

def book(cv, cover, x0, y0, w, h, dx, dy, seed, trim, spine=3, bands_at=(), pages=None):
    """A closed book seen from the upper right: its front cover as a rectangle at (x0, y0), w by h, the leaves' fore-edge and
    head showing beyond it by (dx, dy), a rolled spine down the left with raised bands across it. Returns the cover's mask."""
    pages = pages or PAL['parchment']
    xs, ys = grid(N)
    x1, y1 = x0 + w, y0 + h
    face = rect(x0, y0, x1, y1, N)
    # the leaves behind: the fore-edge (right) and the head (top), each a strip of paper edges, the back cover beyond them
    right = poly([(x1, y0 + 1), (x1 + dx, y0 + dy + 1), (x1 + dx, y1 + dy), (x1, y1)], N)
    top = poly([(x0 + 1, y0), (x0 + dx + 1, y0 + dy), (x1 + dx, y0 + dy), (x1, y0)], N)
    leaves = right | top
    back = leaves & (((xs >= x1 + dx - 1) & right) | ((ys < y0 + dy + 1) & top))
    stripe = np.where(right, ((xs - x1) * 2.0 + (ys - y0) * 0.2) % 2.0 < 1.0, ((ys - y0) * 1.0) % 2.0 < 1.0)
    lt = 0.62 + 0.14 * stripe - 0.25 * (np.where(right, (xs - x1) / max(dx, 1), 0.0)) + 0.1 * (vnoise(N, N, 2, seed) - 0.5)
    lt = np.where(top, 0.78 + 0.12 * stripe - 0.1 * ((ys - y0 - dy) / max(-dy, 1)) , lt)
    cv.put(leaves, nb(pages, lt, 0.2))
    cv.put(back, nb(cover, 0.3))
    # the front cover: leather with a faint mottle, its edges rolled to the light, the spine dark and rounded
    gx, gy = grid(N)
    g = fbm(N, N, 5, seed) - 0.5
    u = (xs - x0) / w
    t = 0.5 + 0.15 * g + 0.1 * (1 - u) - 0.05 * ((ys - y0) / h)
    t = t + 0.28 * (face & (ys < y0 + 1)) + 0.2 * (face & (xs < x0 + 1)) - 0.3 * (face & (ys > y1 - 1)) - 0.34 * (face & (xs > x1 - 1))
    cv.put(face, nb(cover, t, 0.18))
    hinge = face & (np.abs(xs - (x0 + spine)) < 0.55)
    cv.put(hinge, nb(cover, 0.12, 0.0))
    cv.put(face & (np.abs(xs - (x0 + spine + 1)) < 0.55), nb(cover, 0.58, 0.0))
    spine_m = face & (xs < x0 + spine)
    cv.put(spine_m, nb(cover, 0.34 + 0.22 * (xs < x0 + 1.2) - 0.06 * (xs > x0 + spine - 1.2) + 0.12 * g, 0.2))
    for yb in bands_at:
        m = spine_m & (np.abs(ys - yb) < 0.9)
        cv.put(m, nb(trim, 0.62 + 0.25 * (ys < yb) - 0.1 * (xs > x0 + 1.4), 0.0))
    # the tooled border: a dark line and a light one, a little way in from the edges
    frame = face & ~rect(x0 + spine + 3, y0 + 3, x1 - 3, y1 - 3, N) & rect(x0 + spine + 2, y0 + 2, x1 - 2, y1 - 2, N)
    cv.put(frame, nb(cover, 0.2 + 0.16 * (ys < y0 + 3.4) + 0.14 * (xs < x0 + spine + 3.4), 0.0))
    return face


def corner_caps(cv, face, x0, y0, w, h, trim, size=3, skip_left=True):
    """Little metal corners on a book's cover, lit at the top left."""
    for cx_, cy_ in ((x0 + w - size, y0), (x0 + w - size, y0 + h - size), (x0 + size - 1 if False else x0, y0), (x0, y0 + h - size)):
        pass
    xs, ys = grid(N)
    caps = np.zeros((N, N), bool)
    corners = ((x0 + w - size, y0), (x0 + w - size, y0 + h - size)) + (() if skip_left else ((x0, y0), (x0, y0 + h - size)))
    for ax, ay in corners:
        right = ax > x0 + 2
        notch = rect(ax + (0 if right else 1), ay + (1 if ay == y0 else 0), ax + size - (1 if right else 0), ay + size - (0 if ay == y0 else 1), N)
        caps |= rect(ax, ay, ax + size, ay + size, N) & ~notch
    caps &= face
    cv.put(caps, nb(trim, 0.55 + 0.3 * (ys < y0 + 1.5) + 0.15 * (xs < x0 + 1.5) - 0.2 * (xs > x0 + w - 1.5) - 0.25 * (ys > y0 + h - 1.5), 0.0))
    return caps


STAG = [
    # a stag's head full on: the antlers sweeping up and out in two beams with a brow tine and a prong each, then the ears and
    # a narrowing muzzle; two eyes are cut through it
    "X.X.......X.X",
    "X.X.......X.X",
    "XXX.......XXX",
    ".XX.X...X.XX.",
    ".XXXX...XXXX.",
    "..XXX...XXX..",
    "...XXXXXXX...",
    ".XX.XXXXX.XX.",
    "..X.XXXXX.X..",
    ".....XXX.....",
    ".....XXX.....",
    "......X......",
]
STAG_EYES = ((5, 7), (7, 7))


def varn_ledger():
    """Varn's ledger: a thick book of oxblood leather, a brass clasp across its fore-edge, brass corners and spine bands and
    a gilt stag's head on the cover."""
    cv = Canvas(N)
    x0, y0, w, h = 4, 5, 20, 24
    face = book(cv, OXBLOOD, x0, y0, w, h, 4, -3, seed_for('varn', 'cover'), PAL['brass'], 3, (10, 17, 24))
    xs, ys = grid(N)
    corner_caps(cv, face, x0, y0, w, h, PAL['brass'])
    crest = shape(STAG, 10, 8)
    stamp(cv, crest, PAL['gold'], nb(OXBLOOD, 0.1, 0.0)[0, 0])
    for ex, ey in STAG_EYES:
        cv.px(10 + ex, 8 + ey, np.array(OXBLOOD[0], float))
    # the clasp: a strap across the lower cover and over the fore-edge, ending in a catch with a keyhole
    strap = rect(x0 + 10, 22, x0 + w + 4, 25, N) & (face | rect(x0 + w, 22, x0 + w + 4, 25, N))
    cv.put(strap, nb(PAL['brass'], 0.5 + 0.34 * (ys < 23.2) - 0.22 * (ys > 24.2), 0.0))
    catch = rect(x0 + 9, 21, x0 + 13, 26, N)
    cv.put(catch, nb(PAL['brass'], 0.58 + 0.3 * bevel(catch), 0.0))
    cv.put(rect(x0 + 10, 22, x0 + 12, 24, N), nb(PAL['brass'], 0.08, 0.0))
    cv.px(x0 + 11, 24, np.array(PAL['brass'][0], float))
    for rx in (x0 + 15, x0 + 19):
        cv.px(rx, 23, np.array(PAL['brass'][5], float))
    return finish(cv)


HAND = [
    # an open hand, palm to the viewer: four fingers up, the thumb out to the left, the wrist below
    "....X....",
    "..X.X.X..",
    "..X.X.X..",
    "..X.X.X.X",
    "..X.X.X.X",
    "X.XXXXXXX",
    "XXXXXXXXX",
    ".XXXXXXXX",
    "..XXXXXX.",
    "..XXXXXX.",
    "...XXXX..",
]
MOURNING = [(30, 26, 32), (58, 52, 56), (96, 88, 88), (138, 128, 120), (176, 166, 152), (206, 196, 178)]       # black-edged leaves


def widows_ledger():
    """The Widow's ledger: a slim book of black leather with silver corners and a silver hand stamped on its cover, its leaves
    edged for mourning, a dark red ribbon hanging from the foot."""
    cv = Canvas(N)
    x0, y0, w, h = 6, 6, 17, 22
    xs, ys = grid(N)
    # the ribbon first, so the book lies over its top
    ribbon = rect(x0 + 10, y0 + h - 2, x0 + 12, y0 + h + 3, N) & ~((ys > y0 + h + 1.5) & (xs < x0 + 11))
    cv.put(ribbon, nb(SILK, 0.46 + 0.3 * (xs < x0 + 10.5) - 0.12 * (ys > y0 + h + 1), 0.0))
    face = book(cv, BLACKBOOK, x0, y0, w, h, 3, -2, seed_for('widow', 'cover'), SILVER, 3, (11, 17, 23), pages=MOURNING)
    corner_caps(cv, face, x0, y0, w, h, SILVER)
    hand = shape(HAND, 13, 10)
    stamp(cv, hand, SILVER, nb(BLACKBOOK, 0.0, 0.0)[0, 0], 0.66, 0.26)
    return finish(cv)


def hearth_ember():
    """The Hearth Ember: a small gold reliquary, a hipped lid over a casket with a little arched window in its front through
    which a live ember glows, a gold flame for a finial."""
    cv = Canvas(N)
    xs, ys = XS, YS
    G = GOLD
    # the casket: a box on a plinth, its left edge lit and its right in shade
    body = rect(5, 16, 27, 27, N)
    bt = 0.62 + 0.2 * (xs < 7) + 0.1 * (xs < 9) - 0.14 * (xs > 22) - 0.3 * (xs > 25) + 0.03 * (vnoise(N, N, 3, seed_for('ember', 'gold')) - 0.5)
    cv.put(body, nb(G, bt, 0.0))
    cv.put(rect(4, 26, 28, 28, N), nb(G, 0.4 + 0.1 * (xs < 6), 0.0))                           # the plinth, a shade darker
    cv.put(rect(4, 26, 28, 27, N), nb(G, 0.74 - 0.3 * (xs > 25), 0.0))
    # the hipped lid: a trapezoid rising from the casket's top edge, its slopes lit differently, a bright ridge along the top
    lid = poly([(3.6, 15.6), (8.4, 8.8), (23.6, 8.8), (28.4, 15.6)], N)
    lt = np.where(xs < 16, 0.74, 0.56) + 0.1 * (ys < 11) - 0.08 * ((ys - 8.8) / 7.0) + 0.03 * (vnoise(N, N, 2, seed_for('ember', 'lid')) - 0.5)
    cv.put(lid, nb(G, lt, 0.0))
    cv.put(lid & (ys < 9.9), nb(G, 0.92, 0.0))
    cv.put(rect(3, 15, 29, 16, N), nb(G, 0.86 - 0.3 * (xs > 26), 0.0))                         # the eaves: a bright cornice and its shadow
    cv.put(rect(3, 16, 29, 17, N), nb(G, 0.3, 0.0))
    for sx in (10, 14, 18, 22):                                                                  # studs along the lid
        cv.px(sx, 12, np.array(G[5], float) if sx < 16 else np.array(G[4], float))
        cv.px(sx + 1, 13, np.array(G[1], float))
    # the window: an arched opening with a gold frame, the ember burning in the dark inside
    win = poly([(11.0, 25.6), (11.0, 21.0), (12.0, 18.8), (14.0, 17.6), (18.0, 17.6), (20.0, 18.8), (21.0, 21.0), (21.0, 25.6)], N)
    frame = grow(win, 1) & ~win
    cv.put(frame, nb(G, 0.78 + 0.2 * (xs < 16) - 0.3 * (xs > 20), 0.0))
    inside = np.hypot((xs - 16.0) / 4.6, (ys - 24.4) / 4.4)
    heat = np.clip(1.15 - inside, 0, 1)
    cv.put(win, nb(EMBER, 0.06 + 0.78 * heat ** 0.8 + 0.1 * (vnoise(N, N, 1.5, seed_for('ember', 'coal')) - 0.5), 0.35))
    cv.put(win & (ys > 24.2) & (np.abs(xs - 16) < 3.0), nb(EMBER, 0.62 + 0.3 * heat, 0.35))
    cv.put(ellipse(16.0, 24.2, 1.9, 1.3, N)[0], nb(EMBER, 0.95, 0.0))
    for fx, fy in ((15, 21), (17, 20), (16, 22)):                                               # a lick of flame above the coals
        cv.px(fx, fy, np.array(EMBER[3 + (fy > 21)], float))
    cv.over(frame, EMBER[4], 0.3)                                                               # the light of it falling on the frame
    # the finial: the hearth flame in gold on the ridge
    flame(cv, 16.0, 5.6, 0.46, shadow=False)
    return finish(cv, [(7, 18, 1, (255, 240, 200))], rim_skip=win)


CROWN = [
    # three points, the middle one tallest, over a band
    "X....X....X",
    "XX..XXX..XX",
    "XXXXXXXXXXX",
    "XXXXXXXXXXX",
    "XXXXXXXXXXX",
    ".XXXXXXXXX.",
]


def regent_seal():
    """The Regent's Seal: a heavy gold seal with a crown raised on its face, hung from a ring on a loop of red silk whose tail
    falls beside it to a tassel."""
    cv = Canvas(N)
    xs, ys = XS, YS
    G = GOLD
    cx, cy, R = 15.6, 19.2, 9.4
    # the silk first, so the seal lies over it: a loop above the ring, one tail falling down the right to a tassel
    loop = [(cx + 4.8 * math.cos(a), 4.7 + 3.4 * math.sin(a)) for a in np.linspace(0, math.tau, 25)]
    tail = [(cx + 1.4, 9.6), (cx + 6.4, 8.6), (26.6, 12.2), (27.8, 17.8), (27.8, 22.2)]
    for path, width in ((loop, 0.95), (tail, 1.0)):
        d, run = hdkit.path_dist(path, N)
        m = d < width
        cv.put(m, nb(SILK, 0.46 + 0.34 * (d < 0.45) * (xs < path[-1][0] + 9) - 0.2 * (d > 0.75) + 0.1 * np.sin(run * 1.6), 0.0))
    th = rect(27, 22, 29, 24, N)                                                                 # the tassel's gold head, then its skirt
    cv.put(th, nb(G, 0.66 + 0.25 * (xs < 28), 0.0))
    skirt = rect(26, 24, 30, 29, N) & ~((ys > 27.5) & ((xs == 27) | (xs == 28)))
    cv.put(skirt, nb(SILK, 0.52 + 0.26 * (xs < 28) - 0.1 * (ys > 26), 0.0))
    cv.put(skirt & (((xs.astype(int) + ys.astype(int)) % 2) == 0) & (ys > 25.5), nb(SILK, 0.3, 0.0))
    # the seal: a thick disc, its edge milled, the face dished inside a raised rim, the crown stamped in it
    edge_m, _ = disc(cx, cy + 2.0, R, N)
    face, dome = disc(cx, cy, R, N)
    cv.put(edge_m & ~face, nb(G, 0.3 + 0.14 * (xs < cx - 4) - 0.1 * (xs > cx + 4) + 0.12 * ((xs.astype(int) % 2) == 0), 0.0))
    r = np.hypot(xs - cx, ys - cy) / R
    grain = fbm(N, N, 5, seed_for('seal', 'gold'))
    t = 0.5 + 0.45 * light(dome * 2.5, 1.0) + 0.06 * (grain - 0.5)
    rim_m = face & (r > 0.8)
    t = np.where(rim_m, 0.78 + 0.22 * bevel(rim_m) + 0.2 * light(dome * 2.5, 1.0), t)
    t = np.where(face & (r <= 0.8) & (r > 0.72), t - 0.2, t)
    cv.put(face, nb(G, t, 0.15))
    crown = shape(CROWN, 10, 16)
    cv.put(np.roll(np.roll(crown, 1, 0), 1, 1) & ~crown & face, nb(G, 0.2, 0.0)[0, 0])
    ct = 0.8 + 0.2 * bevel(crown) - 0.1 * (ys > 20.5)
    cv.put(crown, nb(G, ct, 0.0))
    cv.put(rect(15, 18, 17, 20, N), nb(GEMS['red'], 0.75 - 0.25 * ((ys > 18.5) | (xs > 16.5)), 0.0))         # a ruby set in the band
    cv.px(15, 18, np.array(GEMS['red'][4], float))
    for jx in (12, 19):
        cv.px(jx, 19, np.array(G[5], float))
    for jx in (10, 15, 20):
        cv.px(jx, 16, np.array(G[5], float))
    # the bail: a gold ring on the seal's crown, the silk run through it
    ring_out, _ = disc(cx, 9.0, 2.3, N)
    ring_in, _ = disc(cx, 9.0, 1.0, N)
    cv.put(ring_out & ~ring_in, nb(G, 0.5 + 0.35 * (xs < cx) + 0.2 * (ys < 9.0) - 0.2 * (xs > cx + 1), 0.0))
    return finish(cv, [(11, 13, 1, (255, 244, 208))])


# ---------------------------------------------------------------- the ward-light

WARD_STOPS = [(0.05, (104, 146, 255), 0.12), (0.10, (126, 168, 255), 0.24), (0.18, (152, 192, 255), 0.40), (0.30, (184, 214, 255), 0.58),
              (0.46, (210, 231, 255), 0.76), (0.70, (232, 243, 255), 0.92), (1.00, (248, 251, 255), 1.0), (1.30, (255, 255, 255), 1.0)]


def ray(along_, across, length, w0, w1, power=0.8):
    """A thin ray of light that narrows and fades from its root outward: [along_] how far from the middle, [across] how far off
    its line."""
    f = np.clip(1 - np.abs(along_) / length, 0, 1)
    w = w1 + (w0 - w1) * f
    return f ** power * np.exp(-(across / np.maximum(w, 0.05)) ** 2) * (np.abs(along_) < length)


def ward_light():
    """The ward-light, a floating full-bright quad: a soft blue-white orb with a bright core and a faint four-pointed star
    across it, transparent round it, no outline. Its light is quantised into a few steps, as the spell glows are."""
    dx, dy = XS - N / 2, YS - N / 2
    r = np.hypot(dx, dy)
    I = 1.4 * np.exp(-(r / 2.0) ** 2) + 0.6 * np.exp(-(r / 3.6) ** 2) + 0.14 * np.exp(-(r / 6.5) ** 2)
    I = np.maximum(I, 0.6 * ray(dx, dy, 14.5, 1.0, 0.3, 0.9))
    I = np.maximum(I, 0.6 * ray(dy, dx, 14.5, 1.0, 0.3, 0.9))
    cv = Canvas(N)
    for thr, col, a in WARD_STOPS:
        m = I >= thr
        cv.rgb[m] = col
        cv.a[m] = a
    return cv.image()


# ================================================================ writing the items

PRIZES = ('varn_ledger', 'hearth_ember', 'regent_seal', 'widows_ledger')          # the heist prizes, one item definition
HEIST = {'varn_ledger': varn_ledger, 'hearth_ember': hearth_ember, 'regent_seal': regent_seal, 'widows_ledger': widows_ledger}
HELD = {'starwatch_staff': (starwatch_staff, weaponkit.STAFF), 'widows_kiss': (widows_kiss, weaponkit.DAGGER),
        'hearthbrand': (hearthbrand, None)}                                         # None: vanilla's handheld sword as it is


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def plain_model(texture):
    return {'parent': 'minecraft:item/generated', 'textures': {'layer0': texture}}


def one_model(name):
    return {'model': {'type': 'minecraft:model', 'model': 'guildmark:item/' + name}}


def write_items(assets):
    """Paint every item and write its texture, model and item definition; returns {name: image} in the order they are listed."""
    made = {}
    models = os.path.join(assets, 'models', 'item')
    defs = os.path.join(assets, 'items')

    def texture(key, img):
        path = os.path.join(assets, 'textures', key + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path, optimize=True)

    made['purse'] = purse()
    texture('item/purse', made['purse'])
    write_json(os.path.join(models, 'purse.json'), plain_model('guildmark:item/purse'))
    write_json(os.path.join(defs, 'purse.json'), one_model('purse'))
    for name in PRIZES:
        made[name] = HEIST[name]()
        texture('item/heist/' + name, made[name])
        write_json(os.path.join(models, 'heist', name + '.json'), plain_model('guildmark:item/heist/' + name))
    write_json(os.path.join(defs, 'heist_prize.json'), {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:custom_model_data',
        'cases': [{'when': name, 'model': {'type': 'minecraft:model', 'model': 'guildmark:item/heist/' + name}} for name in PRIZES],
        'fallback': {'type': 'minecraft:model', 'model': 'guildmark:item/heist/' + PRIZES[0]}}})
    for name, (paint, geo) in HELD.items():
        made[name] = paint()
        texture('item/' + name, made[name])
        if geo:
            model = weaponkit.model('guildmark:item/' + name, geo)
        else:
            model = {'parent': 'minecraft:item/handheld', 'textures': {'layer0': 'guildmark:item/' + name}}
        write_json(os.path.join(models, name + '.json'), model)
        write_json(os.path.join(defs, name + '.json'), one_model(name))
    return made


# ================================================================ previews

BG_DARK = (58, 64, 80)
BG_LIGHT = (206, 204, 198)
BACKDROPS = (('dark', BG_DARK), ('light', BG_LIGHT))


def knight_views(tex, model, bg, yaws=(25, 160, 200, -70)):
    faces = [(A, B, D, (u * S, v * S, w * S, h * S), face, n) for A, B, D, (u, v, w, h), face, n in model.world_faces()]
    sh = Image.new('RGBA', (300 * len(yaws), 400), (*bg, 255))
    for i, y in enumerate(yaws):
        view = np.array(model.render(tex, yaw=y, scale=11, size=(300, 400), faces=faces, y_shift=-1))
        view[(view[..., :3] == BG_DARK).all(-1), :3] = bg                    # the model kit paints its own dark backdrop
        sh.alpha_composite(Image.fromarray(view, 'RGBA'), (i * 300, 0))
    return sh


def lit_on(img, bg):
    """The ward-light as the game draws it: full-bright and added to what is behind it (alpha scales its light)."""
    base = np.array([*bg], float)
    a = np.asarray(img, float)
    out = np.clip(base + a[..., :3] * (a[..., 3:4] / 255.0), 0, 255)
    return Image.fromarray(np.dstack([out, np.full(out.shape[:2], 255.0)]).astype(np.uint8), 'RGBA')


def tiles(images, labels, scale, cols, bg, pad=10, ward=()):
    """Nearest-neighbour tiles on a backdrop, each with its name beneath; the names in [ward] are drawn added to the backdrop."""
    cw = max(i.width for i in images) * scale
    rows = (len(images) + cols - 1) // cols
    out = Image.new('RGBA', (cols * (cw + pad) + pad, rows * (cw + pad + 14) + pad), (*bg, 255))
    d = ImageDraw.Draw(out)
    ink = (226, 220, 206, 255) if sum(bg) < 400 else (40, 38, 46, 255)
    for i, (im, name) in enumerate(zip(images, labels)):
        x, y = pad + (i % cols) * (cw + pad), pad + (i // cols) * (cw + pad + 14)
        big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
        if name in ward:
            out.paste(lit_on(big, bg).crop((0, 0, cw, cw)), (x, y))
        else:
            out.alpha_composite(big, (x, y))
        d.text((x, y + cw + 1), name, fill=ink)
    return out


def hotbar(images, bg, zoom=1):
    """The items 1x as a hotbar shows them: each 32 pixels in its slot (and again at 16 and at 2x for how they read small)."""
    slot = 40 * zoom
    out = Image.new('RGBA', (len(images) * slot + 8, slot + 8), (*bg, 255))
    d = ImageDraw.Draw(out)
    slot_bg = tuple(int(c * 0.78) for c in bg)
    for i, im in enumerate(images):
        x = 4 + i * slot
        d.rectangle([x, 4, x + slot - 2, 4 + slot - 2], fill=(*slot_bg, 255), outline=tuple(int(c * 0.55) for c in bg) + (255,))
        pic = im.resize((32 * zoom, 32 * zoom), Image.NEAREST) if zoom != 1 else im
        out.alpha_composite(pic, (x + 4 * zoom - 1, 4 + 4 * zoom - 1))
    return out


def item_previews(made, ward, tex, model, outdir):
    """The sheets: the knight, the items large, the items 1x as in a hotbar, the ward-light; on the dark backdrop and a light
    one. Returns the combined sheet."""
    names = list(made) + ['ward_light']
    images = list(made.values()) + [ward]
    parts = {}
    for key, bg in BACKDROPS:
        views = knight_views(tex, model, bg)
        views.save(os.path.join(outdir, f'sworn_knight_views_{key}.png'))
        grid_ = tiles(images, names, 6, 6, bg, ward=('ward_light',) if key == 'dark' else ())
        grid_.save(os.path.join(outdir, f'items_{key}.png'))
        small = [lit_on(ward, bg) if n == 'ward_light' and key == 'dark' else im for n, im in zip(names, images)]
        bar = hotbar(small, bg)
        bar.save(os.path.join(outdir, f'items_hotbar_{key}.png'))
        parts[key] = (views, grid_, bar, hotbar(small, bg, 2))
    big = tex.resize((tex.width * 3, tex.height * 3), Image.NEAREST)
    s2 = Image.new('RGBA', big.size, (*BG_DARK, 255))
    s2.alpha_composite(big)
    s2.save(os.path.join(outdir, 'sworn_knight_tex.png'))
    # one sheet of everything: the knight, then the items large, then the hotbars, on each backdrop
    width = max(max(p.width for p in ps) for ps in parts.values())
    height = sum(p.height for ps in parts.values() for p in ps)
    sh = Image.new('RGBA', (width, height), (*BG_DARK, 255))
    y = 0
    for key, bg in BACKDROPS:
        for p in parts[key]:
            sh.alpha_composite(Image.new('RGBA', (width, p.height), (*bg, 255)), (0, y))
            sh.alpha_composite(p, (0, y))
            y += p.height
    sh.save(os.path.join(outdir, 'faction_art_sheet.png'))
    return sh


# ================================================================ main

def main(assets, previews_dir=None):
    tdir = os.path.join(assets, 'textures', 'entity')
    os.makedirs(tdir, exist_ok=True)
    tex, model = sworn_knight(assets)
    tex.save(os.path.join(tdir, 'sworn_knight.png'), optimize=True)
    made = write_items(assets)
    ward = ward_light()
    ward.save(os.path.join(tdir, 'ward_light.png'), optimize=True)
    if previews_dir:
        os.makedirs(previews_dir, exist_ok=True)
        item_previews(made, ward, tex, model, previews_dir)
    print('faction_art: sworn_knight, ' + ', '.join(made) + ', ward_light')


if __name__ == '__main__':
    args = sys.argv[1:]
    if not 1 <= len(args) <= 2:
        raise SystemExit('usage: python3 -B art/scripts/faction_art.py <assets dir> [preview dir]')
    main(*args)
