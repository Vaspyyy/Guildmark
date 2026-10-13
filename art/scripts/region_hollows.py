"""
The regional Hollows: soldiers of the same fallen army that the wild lands have taken back. Each is the Hollow
Soldier (art/scripts/hollow_soldier.py) with its land's mark on it:

- Mire Hollow (the marsh): drowned in the bog. Its mail has rusted through and grown green, weed hangs from its hat
  and shoulders, mud has caked it to the knees and its eyes burn a sickly green.
- Cinder Hollow (the ashen highlands): burnt black. Its iron is heat-blued, its tabard has burnt away to a stub
  still smouldering at the edge, and embers glow in the cracks of its skin.
- Rime Hollow (the frozen peaks): frozen stiff. Frost furs every edge, snow lies on its hat and shoulders,
  icicles hang from its brim, its shoulders and its hem, and its eyes are like winter stars.
- Glimmer Hollow (the crystal deeps): crystal has grown through it, out of its hat, its shoulder and its back.
  The crystal and the veins it sends through the skin glow faintly.

One model carries every land's pieces (the fringe of weed or icicles, the crystals) and shows only its own. The
soldier's own painted design is read back as characters and repainted at twice the model's declared size, each
material with its own fine detail, then each land's weathering is painted over it at that size.

Writes the four textures and their glow maps, the 32x spawn eggs, RegionHollowMesh.kt (straight into the source
tree), art/models/region_hollow.bbmodel and preview sheets:

    python3 -B art/scripts/region_hollows.py src/main/resources/assets/guildmark <preview-dir> art/models
"""
import json, math, os, random, sys, zlib
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import modelkit
from modelkit import Cube, Part, Model
import hollow_soldier as hs

S = 2                       # every texture ships at S times the declared size
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/model/RegionHollowMesh.kt')
VARIANTS = ['mire', 'cinder', 'rime', 'glimmer']


# ================================================================ geometry

def C(o, s, name, **kw):
    return Cube(o, s, None, name=name, **kw)


def clone(part, extra=()):
    """The soldier's part, sharing its cubes (and their painted design), with [extra] pieces hung on it."""
    return Part(part.name, part.pivot, part.rot, cubes=list(part.cubes),
                children=[clone(c) for c in part.children] + list(extra))


def brim_card(name, yaw):
    """A card hanging 5 px from the underside of the hat brim along one edge, turned [yaw] degrees from the front."""
    return Part(name, (0, -5, 0), (0, yaw, 0), cubes=[C((-6, 0, -6), (12, 5, 0), name, faces={'front'})])


def crystal(name, pivot, rot, w, h):
    """A crystal growing from [pivot] (upward before it's turned): a w x h x w column, and a narrower tip on a thick one."""
    cubes = [C((-w / 2, -h, -w / 2), (w, h, w), name)]
    if w >= 2:
        cubes.append(C((-w / 4, -h - w, -w / 4), (w / 2, w, w / 2), name + '_tip'))
    return Part(name, pivot, rot, cubes=cubes)


def build_model():
    # Weed for the mire, icicles for the frost: one fringe of cards round the brim and under each shoulder plate
    fringe = Part('fringe', children=[brim_card('fringe_front', 0), brim_card('fringe_right', 90),
                                      brim_card('fringe_back', 180), brim_card('fringe_left', -90)])
    fringe_r = Part('fringe_r', (-3.5, 0.5, 0), (0, 90, 0), cubes=[C((-2.5, 0, 0), (5, 3, 0), 'fringe_r', faces={'front'})])
    fringe_l = Part('fringe_l', (3.5, 0.5, 0), (0, -90, 0), cubes=[C((-2.5, 0, 0), (5, 3, 0), 'fringe_l', faces={'front'})])
    # Crystal bursting out through the hat, the right shoulder plate, the back and the left forearm
    crown = Part('crown', children=[crystal('crown_a', (-2, -9.5, 0.5), (6, 0, -22), 2, 5),
                                    crystal('crown_b', (2, -9.5, -1), (-14, 0, 18), 1, 4),
                                    crystal('crown_c', (0.5, -9.5, 2.2), (28, 0, 8), 1, 3)])
    growth = Part('growth', children=[crystal('growth_a', (-1.5, 3, 1.5), (-40, 0, -14), 2, 5),
                                      crystal('growth_b', (2.2, 4.5, 1.5), (-58, 0, 24), 1, 4),
                                      crystal('growth_c', (0.5, 7.5, 1.5), (-75, 0, -4), 1, 3)])
    shards_r = Part('shards_r', children=[crystal('shard_r_a', (-1.5, -2.5, 0.5), (8, 0, -30), 2, 4),
                                          crystal('shard_r_b', (0, -2.5, -1.5), (-12, 0, -12), 1, 3)])
    shards_l = Part('shards_l', children=[crystal('shard_l_a', (2.5, 6, 0), (0, 0, 72), 1, 3)])
    model = Model('region_hollow', 128, 64, [
        clone(hs.head, [fringe, crown]), clone(hs.body, [growth]),
        clone(hs.right_arm, [fringe_r, shards_r]), clone(hs.left_arm, [fringe_l, shards_l]),
        clone(hs.right_leg), clone(hs.left_leg)])
    names = [c.name for c in model.all_cubes()]
    if len(set(names)) != len(names):
        raise SystemExit('region_hollow: cube names must be unique')
    model.pack()
    return model


def rects2(c):
    """A cube's face rects (x, y, w, h) on the shipped texture, placed from its true size as Minecraft maps box UV."""
    u, v = c.uv
    w, h, d = c.size

    def R(x0, y0, ww, hh):
        return (int(round(x0 * S)), int(round(y0 * S)), int(round(ww * S)), int(round(hh * S)))
    r = {'top': R(u + d, v, w, d), 'bottom': R(u + d + w, v, w, d), 'right': R(u, v + d, d, h),
         'front': R(u + d, v + d, w, h), 'left': R(u + d + w, v + d, d, h), 'back': R(u + 2 * d + w, v + d, w, h)}
    if c.mirror:
        r['right'], r['left'] = r['left'], r['right']
    return r


# ================================================================ the soldier's design, read back

# What each soldier cube's own painter fills a face it has no rows for with
DEFAULT = {'face': 'M', 'dome': 'j', 'cap': 'I', 'brim': 'j', 'torso': 'M', 'belt': 'L', 'flap': 'c',
           'rarm': 's', 'larm': 's', 'rspaulder': 'I', 'lspaulder': 'I', 'rleg': 'P', 'lleg': 'P',
           'rboot': 'O', 'lboot': 'O', 'shaft': 'w', 'stub': 'w'}


def design(model):
    """Every soldier cube's faces as rows of the soldier's palette characters, in this model's UV layout: its own
    painters run with every character's colour swapped for a marker no real colour can match."""
    saved = dict(hs.P)
    marks = {}
    for i, k in enumerate(sorted(saved)):
        if k == '.':
            continue
        col = (i + 1, 199, 7, 255)
        hs.P[k] = col
        marks[col] = k
    try:
        img = np.array(model.paint())
    finally:
        hs.P.clear()
        hs.P.update(saved)
    chars = {}
    for c in model.all_cubes():
        if c.name not in DEFAULT:
            continue
        for face, (x, y, w, h) in c.rects().items():
            rows = []
            for j in range(h):
                row = ''
                for i in range(w):
                    p = tuple(int(v) for v in img[y + j, x + i])
                    row += '.' if p[3] == 0 else marks.get(p, DEFAULT[c.name])
                rows.append(row)
            chars[(c.name, face)] = rows
    return chars


# ================================================================ colour

def clamp(v):
    return max(0, min(255, int(round(v))))


def rgba(c):
    return (int(c[0]), int(c[1]), int(c[2]), int(c[3]) if len(c) > 3 else 255)


def mix(a, b, t):
    return tuple(clamp(int(a[i]) + (int(b[i]) - int(a[i])) * t) for i in range(3)) + (255,)


WARM = (255, 244, 222)


def tone(c, k):
    """[c] moved [k] steps along its ramp: down is darker, a touch cooler and richer; up is lighter and warmer."""
    if k == 0:
        return rgba(c)
    r, g, b = float(c[0]), float(c[1]), float(c[2])
    a = c[3] if len(c) > 3 else 255
    if k < 0:
        f = 0.87 ** (-k)
        r, g, b = r * f, g * f, b * f ** 0.8
        m = (r + g + b) / 3
        s = 1 + 0.06 * -k
        r, g, b = m + (r - m) * s, m + (g - m) * s, m + (b - m) * s
    else:
        t = 1 - 0.86 ** k
        r, g, b = r + (WARM[0] - r) * t, g + (WARM[1] - g) * t, b + (WARM[2] - b) * t
    return (clamp(r), clamp(g), clamp(b), a)


def seed_of(*keys):
    return zlib.crc32('/'.join(str(k) for k in keys).encode())


CLEAR = (0, 0, 0, 0)


# ================================================================ the lands' palettes

MATERIAL = {}
for _k in 'MmkrR': MATERIAL[_k] = 'mail'
for _k in 'Iij': MATERIAL[_k] = 'iron'
for _k in 'SshdDT': MATERIAL[_k] = 'skin'
for _k in 'CcqYy': MATERIAL[_k] = 'tabard'
for _k in 'Pp': MATERIAL[_k] = 'cloth'
for _k in 'LlB': MATERIAL[_k] = 'leather'
for _k in 'Oo': MATERIAL[_k] = 'boot'
for _k in 'WwF': MATERIAL[_k] = 'wood'
MATERIAL['E'] = 'eye'

PALETTES = {
    # Bog-drowned: green-grey swollen skin, mail rusted brown, the crimson rotted to mud, brass gone to verdigris
    'mire': {'S': (100, 118, 94), 's': (78, 94, 74), 'h': (128, 144, 114), 'd': (50, 62, 46), 'D': (16, 22, 14), 'T': (150, 140, 96),
             'M': (62, 66, 56), 'm': (84, 88, 72), 'k': (34, 36, 30), 'r': (110, 74, 44), 'R': (82, 56, 36),
             'I': (70, 72, 60), 'i': (96, 98, 80), 'j': (46, 48, 40),
             'C': (58, 66, 40), 'c': (42, 48, 30), 'q': (74, 82, 50), 'Y': (112, 104, 60), 'y': (86, 80, 48),
             'P': (60, 60, 46), 'p': (44, 44, 34), 'L': (66, 50, 32), 'l': (46, 34, 22), 'B': (92, 116, 84),
             'O': (54, 44, 30), 'o': (36, 30, 22), 'W': (86, 80, 52), 'w': (62, 56, 36), 'F': (112, 114, 86),
             'E': (206, 240, 128)},
    # Burnt: charred skin, sooted mail, scorched cloth
    'cinder': {'S': (48, 42, 40), 's': (34, 30, 29), 'h': (74, 64, 58), 'd': (24, 20, 20), 'D': (12, 8, 8), 'T': (112, 98, 82),
               'M': (54, 50, 52), 'm': (76, 70, 68), 'k': (26, 24, 26), 'r': (98, 58, 40), 'R': (72, 42, 30),
               'I': (62, 60, 68), 'i': (94, 88, 98), 'j': (38, 36, 42),
               'C': (70, 26, 24), 'c': (44, 18, 17), 'q': (90, 36, 28), 'Y': (116, 88, 48), 'y': (84, 62, 36),
               'P': (48, 42, 38), 'p': (32, 28, 26), 'L': (50, 36, 28), 'l': (32, 24, 20), 'B': (134, 100, 54),
               'O': (42, 32, 26), 'o': (26, 20, 16), 'W': (44, 36, 30), 'w': (28, 22, 20), 'F': (62, 54, 50),
               'E': (255, 176, 70)},
    # Frozen: frost-blue skin, cold steel, the crimson faded to a dusty rose
    'rime': {'S': (150, 174, 194), 's': (118, 140, 162), 'h': (188, 208, 224), 'd': (80, 98, 120), 'D': (30, 38, 58), 'T': (198, 206, 214),
             'M': (102, 110, 124), 'm': (136, 146, 160), 'k': (58, 64, 80), 'r': (110, 92, 88), 'R': (84, 70, 70),
             'I': (110, 120, 136), 'i': (154, 164, 180), 'j': (70, 78, 94),
             'C': (124, 66, 78), 'c': (94, 48, 60), 'q': (148, 88, 98), 'Y': (172, 158, 122), 'y': (132, 120, 94),
             'P': (96, 100, 110), 'p': (72, 76, 88), 'L': (96, 80, 72), 'l': (70, 58, 54), 'B': (172, 162, 132),
             'O': (80, 70, 68), 'o': (58, 52, 52), 'W': (120, 108, 94), 'w': (90, 80, 72), 'F': (202, 208, 214),
             'E': (226, 246, 255)},
    # Crystal-grown: grey-violet skin, dark mail, the crimson gone to wine
    'glimmer': {'S': (114, 106, 134), 's': (88, 80, 108), 'h': (144, 134, 166), 'd': (58, 52, 76), 'D': (22, 16, 34), 'T': (172, 162, 186),
                'M': (72, 68, 84), 'm': (98, 92, 114), 'k': (40, 36, 50), 'r': (150, 110, 220), 'R': (110, 76, 170),
                'I': (82, 78, 98), 'i': (116, 110, 136), 'j': (52, 48, 66),
                'C': (90, 32, 66), 'c': (62, 22, 46), 'q': (114, 46, 84), 'Y': (146, 120, 72), 'y': (106, 88, 56),
                'P': (70, 64, 80), 'p': (52, 46, 60), 'L': (74, 54, 46), 'l': (52, 38, 34), 'B': (152, 132, 92),
                'O': (60, 46, 44), 'o': (40, 30, 30), 'W': (100, 84, 72), 'w': (70, 58, 50), 'F': (182, 172, 202),
                'E': (226, 178, 255)},
}

# The light each land's eyes give off (the glow map adds it onto the lit texture)
EYE_GLOW = {'mire': (176, 226, 96), 'cinder': (255, 168, 56), 'rime': (196, 234, 255), 'glimmer': (214, 150, 255)}
EYE_HALO = {'mire': (30, 46, 12), 'cinder': (64, 26, 6), 'rime': (26, 40, 60), 'glimmer': (40, 18, 60)}

# Baked light per face, in tone steps (the game shades faces too; this keeps the soldier's look)
FACE_LIGHT = {'top': 0.6, 'bottom': -1.6, 'front': 0.0, 'back': -0.5, 'right': -0.3, 'left': -0.3}

# Each material's fine detail inside one texel, as tone steps for its 2x2 pixels (top left, top right, bottom left,
# bottom right): a bevel on each ring of mail, a weave in cloth, a bright upper edge on light iron
KERNEL = {
    'M': (0.7, 0.1, 0.1, -0.7), 'm': (0.9, 0.3, 0.2, -0.5), 'k': (-0.1, -0.5, -0.5, -0.9),
    'r': (0.3, 0.0, -0.2, -0.5), 'R': (0.2, -0.2, -0.2, -0.5),
    'I': (0.15, 0.1, 0.0, -0.15), 'i': (0.9, 0.9, 0.2, 0.1), 'j': (-0.1, -0.1, -0.4, -0.4),
    'tabard': (0.3, -0.3, -0.3, 0.3), 'cloth': (0.3, -0.3, -0.3, 0.3),
    'leather': (0.2, 0.0, -0.1, -0.3), 'boot': (0.2, 0.0, -0.1, -0.3), 'wood': (0.3, 0.3, -0.3, -0.3),
    'skin': (0.0, 0.0, 0.0, 0.0),
}
GRAIN = {'mail': 0.22, 'iron': 0.18, 'skin': 0.16, 'tabard': 0.2, 'cloth': 0.22, 'leather': 0.22, 'boot': 0.2, 'wood': 0.2}


# ================================================================ painting one face

class Face:
    """One cube face being painted on the shipped texture: X right, Y down, as the face is seen from outside."""

    def __init__(self, tex, glow, cube, face, rect, chars):
        x, y, w, h = rect
        self.cube, self.face = cube.name, face
        self.W, self.H = w, h
        self.px = tex[y:y + h, x:x + w]
        self.gl = glow[y:y + h, x:x + w]
        self.chars = chars

    def ch(self, X, Y):
        """The soldier's design character under a pixel ('.' clear, None on the new pieces)."""
        if not self.chars:
            return None
        return self.chars[min(len(self.chars) - 1, Y // S)][min(len(self.chars[0]) - 1, X // S)]

    def ok(self, X, Y):
        return 0 <= X < self.W and 0 <= Y < self.H

    def opaque(self, X, Y):
        return self.ok(X, Y) and self.px[Y, X, 3] > 0

    def get(self, X, Y):
        return tuple(int(v) for v in self.px[Y, X])

    def set(self, X, Y, c):
        if self.ok(X, Y):
            self.px[Y, X] = rgba(c)

    def clear(self, X, Y):
        if self.ok(X, Y):
            self.px[Y, X] = CLEAR
            self.gl[Y, X] = CLEAR

    def shade(self, X, Y, k):
        if self.opaque(X, Y) and k:
            self.px[Y, X] = tone(self.px[Y, X], k)

    def tint(self, X, Y, c, t):
        if self.opaque(X, Y):
            self.px[Y, X] = mix(self.px[Y, X], c, t)

    def glow(self, X, Y, c):
        if self.ok(X, Y):
            self.gl[Y, X] = rgba(c)

    def is_side(self):
        return self.face in ('front', 'back', 'right', 'left')


def paint_base(f, pal, rng):
    """The soldier's design in this land's colours, each texel's 2x2 pixels shaded by its material's fine detail."""
    light = FACE_LIGHT[f.face]
    for Y in range(f.H):
        for X in range(f.W):
            ch = f.ch(X, Y)
            if ch is None or ch == '.':
                continue
            mat = MATERIAL[ch]
            base = pal[ch]
            if mat == 'eye':
                f.set(X, Y, base)
                continue
            kern = KERNEL.get(ch) or KERNEL[mat]
            k = kern[(Y % S) * 2 + (X % S)] + light + rng.uniform(-1, 1) * GRAIN[mat]
            f.set(X, Y, tone(base, k))


def refine_face(f, variant):
    """The face under the brim at the finer scale: eyes with a glow spilling round them, a row of teeth with gaps,
    cheekbones catching the light, the nose a dark hollow."""
    if f.cube != 'face' or f.face != 'front':
        return
    pal = PALETTES[variant]
    for Y in range(f.H):
        for X in range(f.W):
            ch = f.ch(X, Y)
            if ch == 'E':
                f.glow(X, Y, EYE_GLOW[variant])
            elif ch == 'T':
                # Teeth: one pixel each, a dark gap between them in the lower row
                if X % 2 == 1 and Y % 2 == 1:
                    f.set(X, Y, tone(pal['D'], 0.5))
                elif X % 2 == 1:
                    f.shade(X, Y, -0.8)
                if Y % 2 == 0 and X % 2 == 0:
                    f.shade(X, Y, 0.5)
            elif ch == 'h' and X % 2 == 0 and Y % 2 == 0:
                f.shade(X, Y, 0.8)
    # Light from the eyes spills onto the dark of the sockets
    for Y in range(f.H):
        for X in range(f.W):
            if f.ch(X, Y) != 'E':
                continue
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    u, v = X + dx, Y + dy
                    if f.ok(u, v) and f.ch(u, v) in ('D', 'd') and f.gl[v, u, 3] == 0:
                        f.glow(u, v, EYE_HALO[variant])
                        f.tint(u, v, EYE_GLOW[variant], 0.18)


# ================================================================ helpers for the weathering

def value_noise(w, h, cell, seed):
    """Smooth noise in 0..1 over a w x h face, one random value per [cell] pixels, eased between them."""
    rng = random.Random(seed)
    gw, gh = w // cell + 2, h // cell + 2
    g = [[rng.random() for _ in range(gw)] for _ in range(gh)]
    out = np.zeros((h, w))
    for y in range(h):
        for x in range(w):
            fx, fy = x / cell, y / cell
            x0, y0 = int(fx), int(fy)
            tx, ty = fx - x0, fy - y0
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = g[y0][x0] * (1 - tx) + g[y0][x0 + 1] * tx
            b = g[y0 + 1][x0] * (1 - tx) + g[y0 + 1][x0 + 1] * tx
            out[y, x] = a * (1 - ty) + b * ty
    return out


def ragged(w, rng, lo, hi, step=1):
    """A ragged edge: a depth for each column between lo and hi that wanders a pixel at a time."""
    d = rng.randint(lo, hi)
    out = []
    for _ in range(w):
        out.append(d)
        if rng.random() < 0.55:
            d = max(lo, min(hi, d + rng.choice((-step, step))))
    return out


UP_FACING = {'dome', 'cap', 'brim', 'rspaulder', 'lspaulder', 'torso'}


def settles(f):
    """Whether snow, ash or moss settles on this face: the top of the hat, the shoulder plates and the shoulders."""
    return f.face == 'top' and f.cube in UP_FACING


def drapes(f):
    """Whether what settles on top hangs over this face's upper edge."""
    return f.is_side() and f.cube in ('dome', 'cap', 'brim', 'rspaulder', 'lspaulder')


def layer(f, cols, rng, density=0.55, cell=3, over=(0, 3), seed=0):
    """Something settled on the face: patches over a top face (noise above a threshold), a ragged band over the
    upper edge of a side face. [cols] is light, mid and dark."""
    light, midc, dark = cols
    if settles(f):
        n = value_noise(f.W, f.H, cell, seed)
        for Y in range(f.H):
            for X in range(f.W):
                if not f.opaque(X, Y) or n[Y, X] < 1 - density:
                    continue
                t = (n[Y, X] - (1 - density)) / density
                c = light if t > 0.55 else midc if t > 0.2 else dark
                f.set(X, Y, tone(c, rng.uniform(-0.25, 0.25)))
    elif drapes(f):
        depth = ragged(f.W, rng, over[0], over[1])
        for X in range(f.W):
            for Y in range(depth[X]):
                if f.opaque(X, Y):
                    c = light if Y == 0 else midc if Y < depth[X] - 1 else dark
                    f.set(X, Y, tone(c, rng.uniform(-0.25, 0.25)))


def streaks(f, col, n, length, rng, t=0.55, where=None):
    """Thin vertical streaks running down the face: water, soot or meltwater."""
    if not f.is_side():
        return
    for _ in range(n):
        X = rng.randrange(f.W)
        Y = rng.randrange(max(1, f.H // 3))
        if where and not where(X, Y):
            continue
        L = rng.randint(*length)
        for i in range(L):
            if f.opaque(X, Y + i):
                f.tint(X, Y + i, col, t * (1 - i / (L + 1) * 0.6))


def specks(f, cols, n, rng, where=None, glow=None):
    """Single pixels scattered over the face (duckweed, embers, frost glints)."""
    for _ in range(n):
        X, Y = rng.randrange(f.W), rng.randrange(f.H)
        if not f.opaque(X, Y) or (where and not where(X, Y)):
            continue
        f.set(X, Y, rng.choice(cols))
        if glow:
            f.glow(X, Y, glow)


def crack(f, rng, X, Y, length, core, edge, glow_core, glow_edge, where):
    """A crack wandering across the face, glowing from inside: a bright core with a dimmer edge, branching once."""
    dx, dy = rng.choice(((1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0)))
    points = []
    for i in range(length):
        if not f.ok(X, Y) or not where(X, Y):
            break
        points.append((X, Y))
        if rng.random() < 0.4:
            dx, dy = rng.choice(((1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (1, -1), (-1, -1)))
        X, Y = X + dx, Y + dy
    for i, (X, Y) in enumerate(points):
        hot = i in range(1, len(points) - 1)
        f.set(X, Y, core if hot else edge)
        f.glow(X, Y, glow_core if hot else glow_edge)
    if len(points) > 4 and rng.random() < 0.6:
        bx, by = points[len(points) // 2]
        crack(f, rng, bx, by, length // 2, core, edge, glow_core, glow_edge, where)
    return points


def facet(f, X, Y, w, h, cols, rng, glow=None):
    """A little crystal painted onto a surface: a lit face and a shaded face either side of a bright ridge."""
    light, midc, dark, hi = cols
    for j in range(h):
        for i in range(w):
            u, v = X + i, Y + j
            if not f.opaque(u, v):
                continue
            # Point it: narrower toward the top
            if j < h // 2 and (i < (h // 2 - j) // 2 or i >= w - (h // 2 - j) // 2):
                continue
            c = light if i < w // 2 else dark
            if i == w // 2 and j < h - 1:
                c = hi
            if j == h - 1:
                c = midc
            f.set(u, v, c)
            if glow:
                f.glow(u, v, glow if j < h - 1 else tuple(v // 2 for v in glow))


# ================================================================ the lands' weathering

def weather_mire(f, rng):
    pal = PALETTES['mire']
    MOSS = ((112, 146, 66), (84, 118, 52), (56, 86, 40))
    MUD_WET, MUD, MUD_DRY = (44, 36, 26), (66, 52, 36), (100, 86, 60)
    seed = seed_of('mire', f.cube, f.face)
    # Moss and algae over the hat and the shoulders
    layer(f, MOSS, rng, density=0.6, cell=3, over=(1, 4), seed=seed)
    # Mud to the knees: wet and dark at the feet, drying paler at a ragged line
    if f.cube in ('rleg', 'lleg') and f.is_side():
        line = ragged(f.W, rng, 9, 13)
        for X in range(f.W):
            for Y in range(line[X], f.H):
                if f.opaque(X, Y):
                    t = (Y - line[X]) / max(1, f.H - line[X])
                    c = MUD_DRY if Y == line[X] else mix(MUD, MUD_WET, t)
                    f.set(X, Y, tone(c, rng.uniform(-0.3, 0.3)))
            # Splashes above the line
            if rng.random() < 0.25:
                f.set(X, line[X] - rng.randint(2, 4), MUD)
    if f.cube in ('rboot', 'lboot'):
        for Y in range(f.H):
            for X in range(f.W):
                if f.opaque(X, Y):
                    f.set(X, Y, tone(mix(MUD, MUD_WET, Y / max(1, f.H)), rng.uniform(-0.3, 0.3)))
    # The hem of the tabard trails in the mud
    if f.cube == 'flap' and f.is_side():
        line = ragged(f.W, rng, 11, 14)
        for X in range(f.W):
            for Y in range(line[X], f.H):
                if f.opaque(X, Y):
                    f.tint(X, Y, MUD_WET, 0.65)
    # Water still running off it
    streaks(f, (30, 40, 30), 6 if f.cube in ('torso', 'flap', 'face') else 2, (3, 9), rng, t=0.45)
    # Rust eating through the mail and the plates in patches
    if f.cube in ('torso', 'face', 'dome', 'brim', 'rspaulder', 'lspaulder') or f.cube.endswith('arm'):
        n = value_noise(f.W, f.H, 4, seed + 1)
        m = value_noise(f.W, f.H, 2, seed + 4)
        for Y in range(f.H):
            for X in range(f.W):
                if MATERIAL.get(f.ch(X, Y)) in ('mail', 'iron') and n[Y, X] > 0.68:
                    c = pal['r'] if m[Y, X] > 0.5 else pal['R']
                    f.set(X, Y, tone(c, (n[Y, X] - 0.68) * 2 + rng.uniform(-0.3, 0.3) - (0.5 if f.ch(X, Y) == 'k' else 0)))
    # Duckweed stuck in clumps on the cloth and the plates
    if f.cube in ('flap', 'torso', 'rspaulder', 'lspaulder', 'brim', 'belt'):
        for _ in range(3 if f.is_side() else 2):
            X0, Y0 = rng.randrange(f.W), rng.randrange(f.H)
            for _ in range(rng.randint(2, 5)):
                X, Y = X0 + rng.randint(-2, 2), Y0 + rng.randint(-1, 1)
                if f.opaque(X, Y) and f.ch(X, Y) != 'E':
                    f.set(X, Y, rng.choice([(128, 176, 70), (104, 150, 58), (146, 188, 84)]))
    # A lily pad stuck flat on the crown of the hat
    if f.cube == 'cap' and f.face == 'top':
        cx, cy, r = f.W / 2 - 0.5, f.H / 2 - 0.5, f.W / 2 - 1.2
        for Y in range(f.H):
            for X in range(f.W):
                dx, dy = X - cx, Y - cy
                d = math.hypot(dx, dy)
                if d > r or (dx > 0 and abs(dy) < dx * 0.35):
                    continue
                c = (86, 132, 54) if d < r - 1.2 else (64, 104, 44)
                if abs(dx) < 0.6 or abs(dy) < 0.6 or abs(abs(dx) - abs(dy)) < 0.5:
                    c = (110, 156, 70)
                f.set(X, Y, tone(c, rng.uniform(-0.25, 0.25)))
    # Slime weeping from the eyes
    if f.cube == 'face' and f.face == 'front':
        for X in range(f.W):
            for Y in range(f.H):
                if f.ch(X, Y) == 'E' and f.ch(X, Y + 2) != 'E' and X % 2 == 0:
                    for i in range(1, rng.randint(3, 6)):
                        if f.ch(X, Y + i) not in ('E', None):
                            f.tint(X, Y + i, (60, 84, 40), 0.5)


def weather_cinder(f, rng):
    pal = PALETTES['cinder']
    ASH = ((168, 162, 154), (132, 126, 120), (96, 92, 88))
    CORE, EDGE = (255, 214, 120), (236, 112, 34)
    G_CORE, G_EDGE = (255, 170, 70), (150, 60, 10)
    seed = seed_of('cinder', f.cube, f.face)
    # Ash fallen on the hat and the shoulders
    layer(f, ASH, rng, density=0.5, cell=2, over=(0, 2), seed=seed)
    # Temper colours where the fire took the iron: straw, bronze, purple and blue toward the edges
    if f.cube in ('brim', 'dome', 'rspaulder', 'lspaulder', 'cap'):
        n = value_noise(f.W, f.H, 5, seed + 2)
        bands = [(150, 124, 70), (132, 86, 52), (88, 60, 98), (58, 76, 120)]
        for Y in range(f.H):
            for X in range(f.W):
                if MATERIAL.get(f.ch(X, Y)) == 'iron' and n[Y, X] > 0.45:
                    b = bands[min(3, int((n[Y, X] - 0.45) / 0.55 * 4))]
                    f.tint(X, Y, b, 0.42)
    # Glowing cracks in the charred skin
    skin = lambda X, Y: f.ch(X, Y) in ('S', 's', 'h', 'd')
    if f.cube == 'face' and f.face == 'front':
        for (X, Y, L) in ((3, 11, 7), (12, 10, 6), (8, 14, 5)):
            crack(f, rng, X, Y, L, CORE, EDGE, G_CORE, G_EDGE, skin)
    elif f.cube in ('rarm', 'larm') and f.is_side():
        sk = [(X, Y) for Y in range(f.H) for X in range(f.W) if skin(X, Y)]
        for _ in range(2):
            if sk:
                X, Y = rng.choice(sk)
                crack(f, rng, X, Y, rng.randint(4, 7), CORE, EDGE, G_CORE, G_EDGE, skin)
    elif f.cube in ('rleg', 'lleg') and f.face == 'front':
        sk = [(X, Y) for Y in range(f.H) for X in range(f.W) if skin(X, Y)]
        if sk:
            X, Y = rng.choice(sk)
            crack(f, rng, X, Y, 5, CORE, EDGE, G_CORE, G_EDGE, skin)
    # The tabard burnt away below a ragged line that still smoulders; char above it
    if f.cube == 'flap':
        if f.is_side():
            line = ragged(f.W, rng, 7, 11)
            for X in range(f.W):
                for Y in range(f.H):
                    if Y > line[X]:
                        f.clear(X, Y)
                    elif Y == line[X]:
                        if f.opaque(X, Y):
                            f.set(X, Y, EDGE if rng.random() < 0.7 else CORE)
                            f.glow(X, Y, G_EDGE if rng.random() < 0.7 else G_CORE)
                    elif Y >= line[X] - 3:
                        f.tint(X, Y, (14, 10, 10), 0.75 - (line[X] - Y) * 0.15)
        elif f.face == 'bottom':
            for Y in range(f.H):
                for X in range(f.W):
                    f.clear(X, Y)
    # Scorch on the torso's tabard and soot streaking up from it
    if f.cube == 'torso' and f.face in ('front', 'back'):
        line = ragged(f.W, rng, 12, 16)
        for X in range(f.W):
            for Y in range(line[X], min(f.H, line[X] + 5)):
                f.tint(X, Y, (16, 12, 12), 0.6)
        specks(f, [CORE, EDGE], 4, rng, where=lambda X, Y: Y > 12 and Y < 20, glow=G_EDGE)
    streaks(f, (16, 14, 14), 3, (2, 6), rng, t=0.5)
    # The arrows charred, their broken ends still glowing
    if f.cube in ('shaft', 'stub') and f.face == 'back':
        for Y in range(f.H):
            for X in range(f.W):
                f.set(X, Y, CORE)
                f.glow(X, Y, G_CORE)
    # Embers caught in the mail
    if f.cube in ('torso', 'rarm', 'larm', 'face') and f.is_side():
        specks(f, [EDGE, (255, 150, 60)], 3, rng, where=lambda X, Y: f.ch(X, Y) in ('k', 'M'), glow=G_EDGE)


def weather_rime(f, rng):
    pal = PALETTES['rime']
    SNOW = ((244, 250, 255), (214, 230, 244), (176, 198, 222))
    FROST = (226, 240, 252)
    ICE, ICE_HI, ICE_LO = (182, 220, 242), (240, 250, 255), (124, 170, 210)
    seed = seed_of('rime', f.cube, f.face)
    # Snow lying on the hat and the shoulders, and over their edges
    layer(f, SNOW, rng, density=0.78, cell=3, over=(1, 4), seed=seed)
    # Frost creeping down from every upper edge, and furring the mail
    if f.is_side() and f.cube not in ('rboot', 'lboot', 'flap'):
        depth = ragged(f.W, rng, 0, 2)
        for X in range(f.W):
            for Y in range(depth[X]):
                f.tint(X, Y, FROST, 0.7)
    if f.cube in ('torso', 'face', 'rarm', 'larm', 'rleg', 'lleg'):
        n = value_noise(f.W, f.H, 3, seed + 3)
        for Y in range(f.H):
            for X in range(f.W):
                ch = f.ch(X, Y)
                if ch == 'k' and n[Y, X] > 0.42:
                    f.tint(X, Y, FROST, 0.35 + (n[Y, X] - 0.42) * 0.8)
                elif MATERIAL.get(ch) == 'tabard':
                    f.tint(X, Y, FROST, 0.16 + max(0.0, n[Y, X] - 0.6) * 0.9)
    if f.cube in ('dome', 'brim', 'cap', 'rspaulder', 'lspaulder', 'torso', 'face'):
        specks(f, [(255, 255, 255)], 3 if f.is_side() else 2, rng)
    # Frost on the brow and the jaw
    if f.cube == 'face' and f.face == 'front':
        for Y in range(f.H):
            for X in range(f.W):
                ch = f.ch(X, Y)
                if ch == 'h' or (ch in ('d', 's') and rng.random() < 0.25):
                    f.tint(X, Y, FROST, 0.5)
    # Icicles hanging from the hem of the tabard
    if f.cube == 'flap' and f.is_side():
        for X in range(0, f.W, 2):
            bottom = max([Y for Y in range(f.H) if f.opaque(X, Y)], default=-1)
            if bottom < f.H - 6 or rng.random() < 0.35:
                continue
            L = rng.randint(2, 4)
            for i in range(L):
                Y = bottom - L + 1 + i
                f.set(X, Y, ICE_HI if i == 0 else ICE if i < L - 1 else ICE_LO)
    # Ice glazing the boots and frozen into the mail's links
    if f.cube in ('rboot', 'lboot') and f.is_side():
        for X in range(f.W):
            if rng.random() < 0.4:
                f.tint(X, 0, ICE, 0.6)


def weather_glimmer(f, rng):
    pal = PALETTES['glimmer']
    CRYS = ((214, 170, 255), (156, 106, 224), (100, 62, 164), (250, 236, 255))
    VEIN, VEIN_GLOW = (196, 150, 255), (70, 36, 112)
    seed = seed_of('glimmer', f.cube, f.face)
    # A faint violet dust over the hat and the shoulders
    layer(f, ((150, 130, 190), (120, 104, 156), (96, 84, 124)), rng, density=0.32, cell=2, over=(0, 1), seed=seed)
    # Crystal growing out of the mail and the plates in little clusters
    n_cl = {'torso': 5, 'rspaulder': 3, 'lspaulder': 2, 'dome': 2, 'rarm': 2, 'larm': 2, 'face': 1, 'rleg': 1, 'lleg': 1}.get(f.cube, 0)
    if f.is_side() and n_cl:
        for _ in range(n_cl if f.face in ('back', 'front') else max(1, n_cl // 2)):
            w = rng.choice((2, 3, 3, 4))
            h = w + rng.randint(1, 3)
            X, Y = rng.randrange(max(1, f.W - w)), rng.randrange(max(1, f.H - h))
            if f.cube == 'face' and f.face == 'front':
                X, Y = 12, 10      # out of the cheek, clear of the eyes
            facet(f, X, Y, w, h, CRYS, rng, glow=(46, 22, 76))
    # Veins of crystal spreading through the skin
    skin = lambda X, Y: f.ch(X, Y) in ('S', 's', 'h', 'd')
    if (f.cube == 'face' and f.face == 'front') or (f.cube in ('rarm', 'larm') and f.is_side()):
        sk = [(X, Y) for Y in range(f.H) for X in range(f.W) if skin(X, Y)]
        for _ in range(2 if f.cube == 'face' else 1):
            if sk:
                X, Y = rng.choice(sk)
                crack(f, rng, X, Y, rng.randint(4, 7), VEIN, tone(VEIN, -1.2), VEIN_GLOW, tuple(v // 2 for v in VEIN_GLOW), skin)


WEATHER = {'mire': weather_mire, 'cinder': weather_cinder, 'rime': weather_rime, 'glimmer': weather_glimmer}


# ================================================================ the new pieces

FRINGE_FRONT_CLEAR = (4, 20)   # the pixels of the front card over the face, kept clear so the eyes show


def paint_weeds(f, rng):
    """Strands of bog weed hanging from the brim or the shoulder plate, darker at the root, brown at the tips, a few
    with a leaf on them. Over the face only a few short wisps."""
    cols = [(40, 66, 34), (54, 88, 42), (72, 108, 50), (88, 118, 56)]
    TIP = (102, 92, 56)
    X = rng.randint(0, 1)
    while X < f.W:
        over_face = f.cube == 'fringe_front' and FRINGE_FRONT_CLEAR[0] <= X < FRINGE_FRONT_CLEAR[1]
        L = rng.randint(1, 3) if over_face else rng.randint(f.H // 3, f.H)
        c = rng.choice(cols)
        x = X
        for Y in range(L):
            if Y and rng.random() < 0.18:
                x = max(0, min(f.W - 1, x + rng.choice((-1, 1))))
            col = TIP if Y >= L - 2 and L > 3 else tone(c, -0.6 + Y * 0.12)
            f.set(x, Y, col)
            if Y < 2 and not over_face and x + 1 < f.W and rng.random() < 0.6:
                f.set(x + 1, Y, tone(c, -0.8))
        if L > 5 and rng.random() < 0.4:
            ly = rng.randint(2, L - 2)
            f.set(min(f.W - 1, x + 1), ly, tone(c, 0.6))
            f.set(min(f.W - 1, x + 1), ly + 1, c)
        X += rng.randint(2, 3) if not over_face else rng.randint(4, 6)


def paint_icicles(f, rng):
    """Icicles hanging from the brim or the shoulder plate: wide at the root, tapering to a point, lit on the left
    and shadowed on the right. Over the face only short ones."""
    ICE, HI, LO, TIP = (180, 218, 240), (240, 250, 255), (122, 168, 208), (214, 236, 250)
    X = rng.randint(0, 1)
    while X < f.W:
        over_face = f.cube == 'fringe_front' and FRINGE_FRONT_CLEAR[0] <= X < FRINGE_FRONT_CLEAR[1]
        w = 1 if over_face else rng.choice((2, 2, 3))
        L = rng.randint(1, 2) if over_face else min(f.H, rng.choice((2, 3, 3, 4, 5, 6, f.H - 1, f.H)))
        for Y in range(L):
            ww = max(1, round(w * (1 - Y / L) + 0.4))
            for i in range(ww):
                c = HI if i == 0 and ww > 1 else LO if i == ww - 1 and ww > 1 else ICE
                if Y >= L - 1:
                    c = TIP
                f.set(X + i, Y, c)
        X += w + rng.randint(1, 4) if not over_face else rng.randint(3, 5)


def paint_crystal(f, rng, glow=True):
    """A crystal column: facets in bands down its sides, bright toward its tip, a glint up one edge."""
    LIGHT, MID, DARK, DEEP, HI = (214, 170, 255), (158, 106, 226), (104, 64, 170), (72, 42, 122), (250, 236, 255)
    tip = f.cube.endswith('_tip')
    for Y in range(f.H):
        for X in range(f.W):
            if f.face in ('top', 'bottom'):
                c = HI if f.face == 'top' else DEEP
            else:
                lit = f.face in ('front', 'right')
                c = LIGHT if lit else MID
                if X == f.W - 1 and f.W > 1:
                    c = DARK if lit else DEEP
                if X == 0 and lit:
                    c = HI
                # Darker toward the root, brighter toward the point
                c = tone(c, -0.9 * (Y / max(1, f.H - 1)) + (0.6 if tip else 0))
            f.set(X, Y, c)
            if glow:
                k = 1.0 if tip else 0.7 - 0.5 * Y / max(1, f.H - 1)
                f.glow(X, Y, tuple(int(v * k) for v in (84, 44, 140)))


# ================================================================ the textures

def paint_variant(model, chars, variant):
    W, H = model.tex_w * S, model.tex_h * S
    tex = np.zeros((H, W, 4), np.uint8)
    glow = np.zeros((H, W, 4), np.uint8)
    pal = PALETTES[variant]
    rng = random.Random(seed_of('region_hollow', variant))
    for c in model.all_cubes():
        for face, rect in rects2(c).items():
            if not c.shows(face) or rect[2] == 0 or rect[3] == 0:
                continue
            f = Face(tex, glow, c, face, rect, chars.get((c.name, face)))
            if c.name in DEFAULT:
                paint_base(f, pal, rng)
                refine_face(f, variant)
                WEATHER[variant](f, rng)
            elif c.name.startswith('fringe'):
                if variant == 'mire':
                    paint_weeds(f, rng)
                elif variant == 'rime':
                    paint_icicles(f, rng)
            elif variant == 'glimmer':
                paint_crystal(f, rng)
    return Image.fromarray(tex, 'RGBA'), Image.fromarray(glow, 'RGBA')


def lit(tex, glow):
    """The eyes render type adds the glow onto the lit texture; approximate that for the previews."""
    a = np.array(tex).astype(int)
    b = np.array(glow).astype(int)
    on = b[..., 3] > 0
    out = np.zeros_like(a)
    out[on, :3] = np.clip(a[on, :3] + b[on, :3], 0, 255)
    out[on, 3] = 255
    return Image.fromarray(out.astype(np.uint8), 'RGBA')


# Which pieces each land shows (RegionHollowModel toggles the rest off)
SHOWS = {'mire': {'fringe', 'fringe_r', 'fringe_l'}, 'rime': {'fringe', 'fringe_r', 'fringe_l'},
         'cinder': set(), 'glimmer': {'crown', 'growth', 'shards_r', 'shards_l'}}
OPTIONAL = ['fringe', 'fringe_r', 'fringe_l', 'crown', 'growth', 'shards_r', 'shards_l']


def faces_for(model, variant):
    """The model's faces for a preview, without the pieces this land hides, its rects on the shipped texture."""
    hidden = set(OPTIONAL) - SHOWS[variant]

    def strip(p):
        if p.name in hidden:
            return None
        return Part(p.name, p.pivot, p.rot, p.cubes, [q for q in (strip(ch) for ch in p.children) if q])
    m = Model(model.name, model.tex_w, model.tex_h, [strip(p) for p in model.parts])
    out = []
    for A, B, D, rect, face, n in m.world_faces():
        out.append((A, B, D, tuple(v * S for v in rect), face, n))
    return out


def render(model, variant, tex, glow, yaw, scale=12, size=(360, 470)):
    return model.render(tex, yaw=yaw, scale=scale, size=size, glow=lit(tex, glow), faces=faces_for(model, variant))


# ================================================================ spawn eggs (32x)

EGG = 32


def egg_mask():
    """The egg's outline at 32x: taller than wide, fuller below the middle. Returns the mask and each pixel's
    surface normal (x, y) for the shading."""
    m = np.zeros((EGG, EGG), bool)
    nx = np.zeros((EGG, EGG))
    ny = np.zeros((EGG, EGG))
    top, bottom, cx = 2.0, 30.0, 15.5
    for y in range(EGG):
        t = ((y + 0.5) - top) / (bottom - top) * 2 - 1      # -1 at the top, 1 at the bottom
        if abs(t) >= 1:
            continue
        half = 10.2 * math.sqrt(1 - t * t) * (1 + 0.1 * t)
        for x in range(EGG):
            dx = (x + 0.5 - cx) / max(half, 0.01)
            if abs(dx) <= 1:
                m[y, x] = True
                nx[y, x] = dx
                ny[y, x] = t
    return m, nx, ny


EGG_LOOKS = {
    # hat, hat shade, face band, tabard, tabard trim, mail, eye
    'mire': dict(hat=(70, 74, 60), band=(26, 32, 22), tabard=(58, 66, 40), trim=(112, 104, 60), mail=(52, 56, 46), eye=(206, 240, 128)),
    'cinder': dict(hat=(62, 58, 64), band=(16, 12, 12), tabard=(70, 26, 24), trim=(116, 88, 48), mail=(40, 36, 38), eye=(255, 176, 70)),
    'rime': dict(hat=(122, 132, 148), band=(40, 50, 72), tabard=(124, 66, 78), trim=(172, 158, 122), mail=(96, 104, 118), eye=(226, 246, 255)),
    'glimmer': dict(hat=(82, 78, 98), band=(26, 18, 38), tabard=(90, 32, 66), trim=(146, 120, 72), mail=(62, 58, 74), eye=(226, 178, 255)),
}


def paint_egg(variant):
    look = EGG_LOOKS[variant]
    rng = random.Random(seed_of('egg', variant))
    m, nx, ny = egg_mask()
    img = np.zeros((EGG, EGG, 4), np.uint8)
    brim, band_end, tab_end = 11, 16, 23

    def put(x, y, c):
        if 0 <= x < EGG and 0 <= y < EGG:
            img[y, x] = rgba(c)
    for y in range(EGG):
        for x in range(EGG):
            if not m[y, x]:
                continue
            # Light from the upper left, a soft highlight on the hat
            k = -nx[y, x] * 0.9 - ny[y, x] * 0.7 + rng.uniform(-0.15, 0.15)
            if y < brim - 1:
                c = look['hat']
            elif y < brim + 1:
                c = tone(look['hat'], 0.9)                       # the brim's edge catching the light
            elif y < band_end:
                c = look['band']
                k *= 0.3
            elif y < tab_end:
                c = look['tabard']
                if (x + y) % 2 == 0:
                    k += 0.25                                     # weave
            else:
                c = look['mail']
                k += 0.6 if y % 2 == 0 and x % 2 == (y // 2) % 2 else -0.3   # rows of rings
            img[y, x] = rgba(tone(c, k))
    # The crown on the tabard
    for (x, y) in ((13, 18), (15, 18), (17, 18), (13, 19), (14, 19), (15, 19), (16, 19), (17, 19), (13, 20), (14, 20), (15, 20), (16, 20), (17, 20)):
        put(x + 1, y + 1, tone(look['trim'], 0.2 if y == 18 else 0))
    # Two eyes under the brim, a glow round them
    for ex in (11, 18):
        for (dx, dy) in ((0, 0), (1, 0), (0, 1), (1, 1)):
            put(ex + dx, 13 + dy, look['eye'])
        for (dx, dy) in ((-1, 0), (2, 0), (-1, 1), (2, 1), (0, -1), (1, -1), (0, 2), (1, 2)):
            put(ex + dx, 13 + dy, mix(look['band'], look['eye'], 0.3))
    # Each land's mark
    if variant == 'mire':
        for x in range(EGG):
            for y in range(EGG):
                if m[y, x] and y < 9 and rng.random() < 0.55 - y * 0.05:
                    put(x, y, tone((92, 132, 58), rng.uniform(-0.6, 0.4)))
        for x in range(6, 27, 2):
            if rng.random() < 0.75:
                L = rng.randint(2, 7)
                for i in range(L):
                    yy = brim + 1 + i
                    if m[yy, x] and not (10 <= x <= 20 and 12 <= yy <= 15 and i > 0):
                        put(x, yy, tone((54, 88, 42), 0.3 - i * 0.15) if i < L - 1 else (102, 92, 56))
        for x in range(EGG):
            for y in range(26, EGG):
                if m[y, x] and rng.random() < 0.75:
                    put(x, y, tone((60, 48, 32), rng.uniform(-0.5, 0.3)))
    elif variant == 'cinder':
        for x in range(EGG):
            for y in range(EGG):
                if m[y, x] and y < 7 and rng.random() < 0.4:
                    put(x, y, tone((150, 146, 140), rng.uniform(-0.6, 0.3)))
        for (x0, y0, L) in ((8, 24, 6), (20, 22, 5), (14, 27, 4)):
            x, y = x0, y0
            for i in range(L):
                if m[y, x]:
                    put(x, y, (255, 214, 120) if 0 < i < L - 1 else (236, 112, 34))
                x += rng.choice((-1, 0, 1, 1))
                y += rng.choice((0, 1))
        for x in range(EGG):
            y = tab_end - 1
            if m[y, x]:
                put(x, y, (236, 112, 34) if rng.random() < 0.6 else (255, 190, 90))
    elif variant == 'rime':
        for x in range(EGG):
            for y in range(EGG):
                if m[y, x] and y < 8 - (1 if rng.random() < 0.4 else 0):
                    put(x, y, tone((236, 244, 252), rng.uniform(-0.5, 0.2) - y * 0.05))
        x = 6
        while x < 26:
            L = rng.choice((2, 3, 4)) if not 10 <= x <= 20 else 1
            for i in range(L):
                yy = brim + 1 + i
                if m[yy, x]:
                    put(x, yy, (240, 250, 255) if i == 0 else (180, 218, 240) if i < L - 1 else (214, 236, 250))
            x += rng.randint(2, 3)
        for x in range(EGG):
            for y in range(tab_end, EGG):
                if m[y, x] and rng.random() < 0.12:
                    put(x, y, (226, 240, 252))
    elif variant == 'glimmer':
        # Crystals bursting out of the top, past the shell's outline
        for (x0, y0, h, lean) in ((9, 7, 7, -1), (14, 5, 6, 0), (20, 8, 5, 1)):
            for i in range(h):
                x = x0 + (lean * i) // 3
                y = y0 - i
                for dx in (0, 1):
                    c = (250, 236, 255) if dx == 0 and i > 1 else (214, 170, 255) if dx == 0 else (156, 106, 226)
                    if i == h - 1:
                        c = (250, 236, 255) if dx == 0 else None
                    if c:
                        put(x + dx, y, c)
                put(x + 2, y, tone((104, 64, 170), -0.3)) if i < h - 1 else None
        for (x, y) in ((7, 19), (23, 25), (11, 27), (24, 17)):
            if m[y, x]:
                put(x, y, (214, 170, 255)); put(x + 1, y, (156, 106, 226)); put(x, y - 1, (250, 236, 255))
    # A darker rim round the shell, as vanilla's eggs have
    out = img.copy()
    for y in range(EGG):
        for x in range(EGG):
            if img[y, x, 3] == 0:
                continue
            edge = any(not (0 <= x + dx < EGG and 0 <= y + dy < EGG) or img[y + dy, x + dx, 3] == 0
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if edge:
                out[y, x] = rgba(tone(img[y, x], -1.6))
    return Image.fromarray(out, 'RGBA')


# ================================================================ Kotlin and Blockbench

def write_kotlin(model):
    body = model.kotlin()
    text = f'''// generated by art/scripts/region_hollows.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.model

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.core.Direction
import java.util.EnumSet

/**
 * The regional Hollows' model: the Hollow Soldier with every land's pieces (the fringe of weed or icicles round the
 * brim and under the shoulder plates, the crystals), box UV on a {model.tex_w} x {model.tex_h} texture. The textures
 * ship at {S}x that size, painted at the higher resolution.
 */
object RegionHollowMesh {{
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun createBodyLayer(): LayerDefinition {{
{body}
    }}
}}
'''
    with open(KOTLIN, 'w') as f:
        f.write(text)


def write_bbmodel(model, texs, path):
    bb = model.bbmodel(texs['mire'], 'mire_hollow')
    first = bb['textures'][0]
    bb['textures'] = []
    import base64, io, uuid
    for v in VARIANTS:
        buf = io.BytesIO()
        texs[v].save(buf, 'PNG')
        t = dict(first)
        t.update({'name': f'{v}_hollow', 'id': str(len(bb['textures'])), 'width': model.tex_w * S, 'height': model.tex_h * S,
                  'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode(), 'uuid': str(uuid.uuid4())})
        bb['textures'].append(t)
    json.dump(bb, open(path, 'w'))


# ================================================================ main

def main(out, prev, art):
    model = build_model()
    chars = design(model)
    texs, glows = {}, {}
    for v in VARIANTS:
        texs[v], glows[v] = paint_variant(model, chars, v)
        texs[v].save(f'{out}/textures/entity/{v}_hollow.png')
        glows[v].save(f'{out}/textures/entity/{v}_hollow_glow.png')
    eggs = Image.new('RGBA', (4 * EGG * 6 + 60, EGG * 6 + 20), (40, 42, 52, 255))
    for i, v in enumerate(VARIANTS):
        egg = paint_egg(v)
        egg.save(f'{out}/textures/item/{v}_hollow_spawn_egg.png')
        big = egg.resize((EGG * 6, EGG * 6), Image.NEAREST)
        eggs.paste(big, (10 + i * (EGG * 6 + 15), 10), big)
    eggs.save(f'{prev}/region_hollow_eggs.png')
    write_kotlin(model)
    write_bbmodel(model, texs, f'{art}/region_hollow.bbmodel')
    sheet = Image.new('RGBA', (4 * 360, 2 * 470))
    for i, v in enumerate(VARIANTS):
        sheet.paste(render(model, v, texs[v], glows[v], 25), (i * 360, 0))
        sheet.paste(render(model, v, texs[v], glows[v], 205), (i * 360, 470))
    sheet.save(f'{prev}/region_hollows_preview.png')
    sheet.save(f'{art}/region_hollow_preview.png')
    for v in VARIANTS:
        texs[v].resize((model.tex_w * S * 3, model.tex_h * S * 3), Image.NEAREST).save(f'{prev}/{v}_hollow_tex.png')
        close = Image.new('RGBA', (3 * 420, 560))
        for i, yaw in enumerate((20, 160, -70)):
            close.paste(render(model, v, texs[v], glows[v], yaw, scale=16, size=(420, 560)), (i * 420, 0))
        close.save(f'{prev}/{v}_hollow_close.png')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
