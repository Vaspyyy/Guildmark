"""
The townsfolk: the people of the capitals (citizens, smiths, bakers, merchants, guards, farmers, fishers, children,
nobles, innkeepers, bards and priests). One humanoid model carries every look's pieces (hair, beards, hats, helms,
aprons, skirts, capes, tabards, robes, creels...) plus the held tools from townsfolk_tools.py; each look shows only
its own pieces and has its own texture.

Textures are HD: the model declares TEX_W x TEX_H (box UV packed by modelkit) and every PNG ships at twice that, painted
at the doubled resolution. Skirts, coats, smocks, robes and gowns are one shell split in two halves that ride on the
legs (skirt_r on right_leg, skirt_l on left_leg), so they never let a leg through and drape over the lap when seated;
how long the garment is, is painted (the shell below the hem is clear).

Writes every look's texture, the shared glow map, TownsfolkMesh.kt and TownsfolkLooks.kt (straight into the source
tree), art/models/townsfolk.bbmodel and preview sheets. Importable without side effects: build_model() gives the
modelkit Model, look_texture(role, n) a look's HD texture, LOOKS every look.

    python3 -B art/scripts/townsfolk.py src/main/resources/assets/guildmark <preview-dir> art/models
"""
import json, math, os, random, sys, uuid, zlib
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import modelkit
from modelkit import Cube, Part, Model

TEX_W, TEX_H = 256, 128     # the declared (box UV) size
SCALE = 2                   # every PNG ships at SCALE times that
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
KOTLIN_DIR = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/townsfolk')


# ================================================================ geometry

def C(o, s, name, **kw):
    return Cube(o, s, None, name=name, **kw)


# Every look piece by the part it hangs from. Children of a piece (a hat's plume) show and hide with it.
def _pieces():
    head = [
        Part('hair_bun', cubes=[C((-2, -7.5, 3.5), (4, 4, 3), 'bun')]),
        Part('ponytail', cubes=[C((-1, -5, 4), (2, 9, 2), 'braid')]),
        Part('pigtails', cubes=[C((-5.5, -5, 0.5), (2, 5, 2), 'pigtail_r'), C((3.5, -5, 0.5), (2, 5, 2), 'pigtail_l')]),
        Part('hair_bow', cubes=[C((-2, -9.5, 1), (4, 2, 1), 'bow')]),
        Part('beard', cubes=[C((-4, -3, -4.6), (8, 6, 1), 'beard', inflate=0.1)]),
        Part('kerchief_knot', cubes=[C((-1.5, -4.5, 4.4), (3, 4, 1), 'knot')]),
        Part('straw_hat', cubes=[C((-7, -7, -7), (14, 1, 14), 'straw_brim'), C((-5, -11, -5), (10, 4, 10), 'straw_crown')]),
        Part('wide_hat', cubes=[C((-7, -7.5, -7), (14, 1, 14), 'wide_brim'), C((-4.5, -11.5, -4.5), (9, 4, 9), 'wide_crown', inflate=0.15)]),
        Part('kettle_helm', cubes=[C((-6.5, -7.5, -6.5), (13, 1, 13), 'kettle_brim'), C((-4.5, -10.5, -4.5), (9, 3, 9), 'kettle_crown', inflate=0.15),
                                   C((-3.5, -11.5, -3.5), (7, 1, 7), 'kettle_dome')]),
        Part('souwester', cubes=[C((-4.5, -10, -4.5), (9, 3, 9), 'sou_crown', inflate=0.15), C((-6, -7.5, -6.5), (12, 1, 13), 'sou_brim'),
                                 C((-5, -6.5, 4.6), (10, 3, 1), 'sou_flap')]),
        Part('cap', cubes=[C((-4.5, -9.5, -4.5), (9, 3, 9), 'cap', inflate=0.2)]),
        Part('baker_cap', cubes=[C((-4.5, -9, -4.5), (9, 2, 9), 'baker_band', inflate=0.15), C((-5, -11.5, -5), (10, 3, 10), 'baker_puff')]),
        Part('head_wrap', cubes=[C((-4.5, -9, -4.5), (9, 3, 9), 'wrap_band', inflate=0.3), C((-4, -10.5, -4), (8, 2, 8), 'wrap_top'),
                                 C((-1, -6, 4.6), (2, 5, 1), 'wrap_tail')]),
        Part('plumed_hat', (0, -8, 0), (0, 0, -8), cubes=[C((-6, 0, -6), (12, 1, 12), 'plumed_brim'), C((-4, -3, -4), (8, 3, 8), 'plumed_crown')],
             children=[Part('plume', (3.2, -2.5, -2.5), (12, 0, 18), cubes=[C((-0.5, -1, 0), (1, 2, 9), 'plume')])]),
        Part('bard_cap', cubes=[C((-4.5, -10, -4.5), (9, 3, 9), 'bard_crown', inflate=0.1), C((-4, -7.5, -7), (8, 1, 3), 'bard_peak'),
                                C((-2.5, -11.5, 0.5), (5, 2, 5), 'bard_tip')],
             children=[Part('bard_feather', (-4.6, -9, 1.5), (-25, 0, -22), cubes=[C((-0.5, -7, -0.5), (1, 7, 1), 'bard_feather')])]),
        Part('circlet', cubes=[C((-4.5, -7, -4.5), (9, 1, 9), 'circlet', inflate=0.12)]),
        Part('skullcap', cubes=[C((-3.5, -9, -3.5), (7, 2, 7), 'skullcap')]),
        Part('veil', cubes=[C((-5, -9, -5), (10, 10, 10), 'veil')]),
    ]
    body = [
        Part('apron', (0, 11, -2.25), cubes=[C((-3.5, 0, -0.5), (7, 9, 1), 'apron')]),
        Part('belly', cubes=[C((-3.5, 4, -3.5), (7, 6, 2), 'belly')]),
        Part('tabard', (0, 11.5, -2.3), cubes=[C((-3.5, 0, -0.5), (7, 6, 1), 'tabard_front')]),
        Part('tabard_back', (0, 11.5, 2.3), cubes=[C((-3.5, 0, -0.5), (7, 6, 1), 'tabard_rear')]),
        Part('cape', (0, 0, 2.2), (5, 0, 0), cubes=[C((-5, 0, 0), (10, 18, 1), 'cape')]),
        Part('collar', cubes=[C((-4.5, -1, -2.5), (9, 3, 5), 'collar')]),
        Part('hair_long', cubes=[C((-4, 0, 2), (8, 9, 2), 'hair_long')]),
        Part('pouch', cubes=[C((-5.2, 9.5, -3.3), (2, 3, 2), 'pouch')]),
        Part('creel', cubes=[C((0, 7.5, 2), (4, 4, 3), 'creel')]),
        Part('sash_tails', cubes=[C((4.5, 10, -1), (1, 6, 2), 'sash_tails')]),
    ]
    right_arm = [
        Part('sleeve_r', cubes=[C((-3.5, 3, -2.5), (5, 5, 5), 'sleeve_r')]),
        Part('puff_r', cubes=[C((-3.5, -2.5, -2.5), (5, 4, 5), 'puff_r')]),
        Part('roll_r', cubes=[C((-3, 3, -2), (4, 1, 4), 'roll_r', inflate=0.35)]),
    ]
    left_arm = [
        Part('sleeve_l', cubes=[C((-1.5, 3, -2.5), (5, 5, 5), 'sleeve_l')]),
        Part('puff_l', cubes=[C((-1.5, -2.5, -2.5), (5, 4, 5), 'puff_l')]),
        Part('roll_l', cubes=[C((-1, 3, -2), (4, 1, 4), 'roll_l', inflate=0.35)]),
        Part('shoulder_rag', cubes=[C((0, -2.5, -2.5), (3, 1, 5), 'rag_top'), C((0, -1.5, -2.7), (3, 4, 1), 'rag_front'),
                                    C((0, -1.5, 1.7), (3, 3, 1), 'rag_back')]),
    ]
    right_leg = [
        Part('skirt_r', cubes=[C((-3.1, -0.5, -3), (5, 12, 6), 'skirt_r')]),
        Part('boot_r', cubes=[C((-2, 5, -2), (4, 2, 4), 'boot_r', inflate=0.35)]),
    ]
    left_leg = [
        Part('skirt_l', cubes=[C((-1.9, -0.5, -3), (5, 12, 6), 'skirt_l')]),
        Part('boot_l', cubes=[C((-2, 5, -2), (4, 2, 4), 'boot_l', inflate=0.35)]),
    ]
    return {'head': head, 'body': body, 'right_arm': right_arm, 'left_arm': left_arm, 'right_leg': right_leg, 'left_leg': left_leg}


PIECES = sorted(p.name for ps in _pieces().values() for p in ps)


def _tools():
    """The held props (townsfolk_tools.py, the animation helper's): parts by parent, painters, glowing cubes."""
    try:
        import townsfolk_tools as tt
    except ImportError:
        return {}, {}, set()
    return tt.tool_parts(), tt.tool_painters(), set(getattr(tt, 'GLOW_CUBES', set()))


def tool_names():
    """Names of the top-level tool parts (what the lead's code toggles)."""
    return sorted(p.name for ps in _tools()[0].values() for p in ps)


def build_model():
    """The townsfolk model: the humanoid at the standard pivots, every look piece and every tool, UV packed."""
    pieces = _pieces()
    tools = _tools()[0]
    def kids(name): return pieces.get(name, []) + list(tools.get(name, []))
    head = Part('head', cubes=[C((-4, -8, -4), (8, 8, 8), 'head')],
                children=[Part('hat', cubes=[C((-4, -8, -4), (8, 8, 8), 'hat', inflate=0.5)])] + kids('head'))
    body = Part('body', cubes=[C((-4, 0, -2), (8, 12, 4), 'body')], children=kids('body'))
    right_arm = Part('right_arm', (-5, 2, 0), cubes=[C((-3, -2, -2), (4, 12, 4), 'right_arm')], children=kids('right_arm'))
    left_arm = Part('left_arm', (5, 2, 0), cubes=[C((-1, -2, -2), (4, 12, 4), 'left_arm')], children=kids('left_arm'))
    right_leg = Part('right_leg', (-1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'right_leg')], children=kids('right_leg'))
    left_leg = Part('left_leg', (1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'left_leg')], children=kids('left_leg'))
    model = Model('townsfolk', TEX_W, TEX_H, [head, body, right_arm, left_arm, right_leg, left_leg])
    names = [c.name for c in model.all_cubes()]
    dup = {n for n in names if names.count(n) > 1}
    if dup or None in names:
        raise SystemExit(f'townsfolk: cube names must be unique and set: {sorted(dup)}')
    pack_uv(model)
    return model


def pack_uv(model):
    """Box-UV pack every cube with a skyline (lowest fit first): tighter than modelkit's shelves, so the looks' pieces
    and the tools share TEX_W x TEX_H with room to spare. Deterministic."""
    W = model.tex_w
    cubes = sorted(model.all_cubes(), key=lambda c: (-c.footprint()[1], -c.footprint()[0], c.name))
    sky = [[0, 0, W]]              # segments: x, height, width
    for c in cubes:
        fw, fh = c.footprint()
        best = None
        for i in range(len(sky)):
            x = sky[i][0]
            if x + fw > W:
                break
            y, j, room = 0, i, 0
            while room < fw:
                y = max(y, sky[j][1])
                room += sky[j][2]
                j += 1
            key = (y + fh, y, x)
            if best is None or key < best[0]:
                best = (key, i, x, y)
        if best is None:
            raise SystemExit(f'{model.name}: {c.name} ({fw}x{fh}) does not fit')
        _, i, x, y = best
        c.uv = (x, y)
        # raise the skyline under the new rect
        new, end = [], x + fw
        for sx, sy, sw in sky:
            if sx + sw <= x or sx >= end:
                new.append([sx, sy, sw])
                continue
            if sx < x:
                new.append([sx, sy, x - sx])
            if sx + sw > end:
                new.append([end, sy, sx + sw - end])
        new.append([x, y + fh, fw])
        new.sort()
        merged = []
        for seg in new:
            if merged and merged[-1][1] == seg[1] and merged[-1][0] + merged[-1][2] == seg[0]:
                merged[-1][2] += seg[2]
            else:
                merged.append(seg)
        sky = merged
    used = max(c.uv[1] + c.footprint()[1] for c in model.all_cubes())
    if used > model.tex_h:
        raise SystemExit(f'{model.name}: texture too small, need {used} rows of {model.tex_h}')
    return used


def rects2x(c, s=SCALE):
    """A cube's six face rects (x, y, w, h) at [s] times the declared size, placed from its true (float) size as
    Minecraft maps box UV; a face edge that falls inside a texel takes in the whole texel."""
    u, v = c.uv
    w, h, d = c.size

    def R(x0, y0, ww, hh):
        X0, Y0 = math.floor(round(x0 * s, 6)), math.floor(round(y0 * s, 6))
        X1, Y1 = math.ceil(round((x0 + ww) * s, 6)), math.ceil(round((y0 + hh) * s, 6))
        return (X0, Y0, X1 - X0, Y1 - Y0)
    r = {'top': R(u + d, v, w, d), 'bottom': R(u + d + w, v, w, d), 'right': R(u, v + d, d, h),
         'front': R(u + d, v + d, w, h), 'left': R(u + d + w, v + d, d, h), 'back': R(u + 2 * d + w, v + d, w, h)}
    if c.mirror:
        r['right'], r['left'] = r['left'], r['right']
    return r


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


# ================================================================ canvas

class Canvas:
    """One cube face at the HD scale: x right, y down, as the face is seen from outside."""

    def __init__(self, w, h, col=None):
        self.W, self.H = w, h
        self.a = np.zeros((h, w, 4), np.uint8)
        if col is not None:
            self.a[...] = rgba(col)

    def ok(self, x, y):
        return 0 <= x < self.W and 0 <= y < self.H

    def set(self, x, y, c):
        if self.ok(x, y):
            self.a[y, x] = rgba(c) if len(c) == 3 or c[3] else CLEAR

    def get(self, x, y):
        return tuple(int(v) for v in self.a[y, x])

    def opaque(self, x, y):
        return self.ok(x, y) and self.a[y, x, 3] > 0

    def rect(self, x0, y0, x1, y1, c):
        x0, x1, y0, y1 = max(0, x0), min(self.W, x1), max(0, y0), min(self.H, y1)
        if x0 < x1 and y0 < y1:
            self.a[y0:y1, x0:x1] = rgba(c) if len(c) == 3 or c[3] else CLEAR

    def fill(self, c):
        self.rect(0, 0, self.W, self.H, c)

    def shade(self, x, y, k):
        if self.opaque(x, y) and k:
            self.a[y, x] = tone(self.a[y, x], k)

    def shade_rect(self, x0, y0, x1, y1, k):
        for y in range(max(0, y0), min(self.H, y1)):
            for x in range(max(0, x0), min(self.W, x1)):
                self.shade(x, y, k)

    def tint(self, x, y, c, t):
        if self.opaque(x, y):
            self.a[y, x] = mix(self.a[y, x], c, t)

    def clear(self, x, y):
        if self.ok(x, y):
            self.a[y, x] = CLEAR

    def clear_rect(self, x0, y0, x1, y1):
        self.rect(x0, y0, x1, y1, CLEAR)

    def hline(self, x0, x1, y, c):
        for x in range(x0, x1 + 1):
            self.set(x, y, c)

    def vline(self, x, y0, y1, c):
        for y in range(y0, y1 + 1):
            self.set(x, y, c)

    def line(self, x0, y0, x1, y1, c, k=None):
        """A pixel line; with [k] it shades what is there instead of painting [c]."""
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        seen = set()
        for i in range(n + 1):
            x, y = int(round(x0 + (x1 - x0) * i / n)), int(round(y0 + (y1 - y0) * i / n))
            if (x, y) in seen:
                continue
            seen.add((x, y))
            if k is None:
                self.set(x, y, c)
            else:
                self.shade(x, y, k)

    def stamp(self, x0, y0, rows, pal):
        """Paint character rows at (x0, y0): each char is a palette colour, a number shades by that many steps
        ('-' and '+' prefixed in the palette), and ' ' or '.' leaves the pixel alone."""
        for j, row in enumerate(rows):
            for i, ch in enumerate(row):
                if ch in ' .':
                    continue
                v = pal[ch]
                if isinstance(v, (int, float)):
                    self.shade(x0 + i, y0 + j, v)
                else:
                    self.set(x0 + i, y0 + j, v)

    def flip(self):
        """Mirrored left to right (the left side's face from the right side's)."""
        c = Canvas(self.W, self.H)
        c.a = self.a[:, ::-1].copy()
        return c

    def crop(self, x0, y0, w, h):
        c = Canvas(w, h)
        c.a = self.a[y0:y0 + h, x0:x0 + w].copy()
        return c


def mask_rows(rows):
    """A boolean mask from '#'/'.' rows."""
    return np.array([[ch == '#' for ch in r] for r in rows], bool)


def depth_mask(depths, h):
    m = np.zeros((h, len(depths)), bool)
    for x, d in enumerate(depths):
        m[:max(0, min(h, d)), x] = True
    return m


# ================================================================ people: skin, eyes, hair

SKIN = {
    'porcelain': (244, 214, 194), 'fair': (238, 196, 166), 'rosy': (232, 180, 150), 'light': (224, 174, 136),
    'olive': (202, 154, 112), 'tan': (186, 132, 94), 'brown': (152, 100, 68), 'dark': (114, 74, 52), 'deep': (86, 56, 42),
}
EYES = {
    'brown': (104, 64, 40), 'dark': (62, 42, 32), 'hazel': (124, 100, 54), 'green': (70, 122, 72), 'blue': (62, 104, 176),
    'grey': (108, 120, 132), 'amber': (172, 118, 46), 'teal': (54, 118, 120),
}
HAIR = {
    'black': (40, 34, 36), 'raven': (30, 30, 40), 'dark': (66, 46, 34), 'brown': (104, 68, 42), 'chestnut': (134, 76, 42),
    'auburn': (152, 66, 38), 'ginger': (198, 102, 48), 'copper': (176, 88, 44), 'strawberry': (214, 146, 96),
    'blond': (222, 180, 104), 'flaxen': (232, 208, 148), 'grey': (152, 150, 148), 'white': (226, 224, 218),
    'salt': (116, 112, 110), 'iron': (96, 94, 96),
}


def skin_ramp(base):
    r, g, b = base[:3]
    lum = 0.3 * r + 0.59 * g + 0.11 * b
    light = (255, 238, 222) if lum > 140 else (232, 184, 146)
    return {
        'hi2': mix(base, light, 0.42), 'hi': mix(base, light, 0.2), 's': rgba(base),
        'sh': (clamp(r * .91), clamp(g * .82), clamp(b * .80), 255),
        'sh2': (clamp(r * .80), clamp(g * .67), clamp(b * .66), 255),
        'line': (clamp(r * .58), clamp(g * .42), clamp(b * .42), 255),
        'blush': mix(base, (222, 96, 92), 0.30 if lum > 140 else 0.2),
        'lip': mix(base, (182, 82, 86), 0.36 if lum > 140 else 0.26),
        'mouth': (clamp(r * .54), clamp(g * .35), clamp(b * .36), 255),
        'lum': lum,
    }


def paint_hair(cv, mask, col, seed, sheen=None, curly=False, tips=True, edge=True, flow='down', dark_edge_rows=None):
    """Hair over the masked texels: strands in clumps with a dark parting between them, a sheen band where the light
    catches, darker roots and tips, and a dark edge where hair meets skin. Curly hair is tight coils instead."""
    H, W = mask.shape
    rng = random.Random(seed)
    base = rgba(col)
    if flow == 'across':   # strands run left-right: paint transposed
        t = Canvas(H, W)
        t.a = cv.a.transpose(1, 0, 2).copy()
        paint_hair(t, mask.T.copy(), col, seed, sheen, curly, tips, edge, 'down')
        cv.a = t.a.transpose(1, 0, 2).copy()
        return
    k = np.zeros((H, W), float)
    if curly:
        # tight coils: small round tufts on a jittered grid, each lit on its upper side, the gaps between them dark
        k[:, :] = -1.0
        tuft = ((0.35, 0.8, 0.25), (0.55, 0.15, -0.35), (-0.05, -0.45, -0.85))
        for gy in range(-2, H, 3):
            off = rng.randrange(0, 3)
            for gx in range(-2 + off, W, 3):
                ox, oy = gx + rng.choice((0, 0, 0, 1)), gy + rng.choice((0, 0, 0, 1))
                for ty in range(3):
                    for tx in range(3):
                        if 0 <= ox + tx < W and 0 <= oy + ty < H:
                            k[oy + ty, ox + tx] = tuft[ty][tx]
        if sheen:
            for y in range(sheen[0], sheen[1] + 1):
                if 0 <= y < H:
                    k[y] += 0.35
    else:
        gaps = np.zeros((H, W), bool)
        x = rng.randrange(0, 3)
        while x < W:
            turn, d = rng.randrange(H // 3, H + 2), rng.choice((-1, 1))
            for y in range(H):
                xx = x + (d if y >= turn else 0)
                if 0 <= xx < W:
                    gaps[y, xx] = True
            x += rng.choice((3, 4, 4, 5))
        for y in range(H):
            for x in range(W):
                if gaps[y, x]:
                    k[y, x] = -1.0
                elif x > 0 and gaps[y, x - 1]:
                    k[y, x] = 0.45
                elif x + 1 < W and gaps[y, x + 1]:
                    k[y, x] = -0.35
        if sheen:
            # each clump catches the light on its lit side, a streak a little longer or shorter than its neighbours
            for x in range(W):
                if gaps[min(H - 1, sheen[0]), x]:
                    continue
                y0 = sheen[0] + rng.choice((-1, 0, 0, 1))
                y1 = sheen[1] + rng.choice((-1, 0, 1))
                lit = x > 0 and gaps[min(H - 1, sheen[0]), x - 1]
                for y in range(max(0, y0), min(H, y1 + 1)):
                    if not gaps[y, x]:
                        k[y, x] += 1.6 if lit else 0.8
    out = np.zeros((H, W), bool)
    for y in range(H):
        for x in range(W):
            if not mask[y, x]:
                continue
            kk = k[y, x]
            if tips and (y + 1 >= H or not mask[y + 1, x]):
                kk -= 0.8
            if edge:
                for dx, dy in ((1, 0), (-1, 0), (0, -1)):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < W and 0 <= yy < H and not mask[yy, xx]:
                        kk -= 0.7
                        break
            if dark_edge_rows and y in dark_edge_rows:
                kk -= 0.6
            cv.a[y, x] = tone(base, kk)
            out[y, x] = True
    return out


def hair_shadow(cv, mask, k=-0.8):
    """The skin right under a hair edge sits in its shadow."""
    H, W = mask.shape
    for y in range(1, H):
        for x in range(W):
            if not mask[y, x] and mask[y - 1, x]:
                cv.shade(x, y, k)


# Hairstyles: which texels of each head face are hair. Side masks are the head's right side, back at column 0;
# the left side is the mirror. 'front' as depths (rows of hair per column, the character's right first).
def _side(rows): return mask_rows(rows)


STYLES = {
    'crop': {
        'front': [6, 5, 5, 4, 4, 4, 5, 4, 3, 3, 3, 3, 3, 4, 5, 6],
        'side': ["################", "################", "################", "################", "################",
                 "############....", "############....", "############....", "######...###....", "######...###....",
                 "#####....##.....", "#####....#......", "####............", "###.............", "................",
                 "................"],
        'back': 13, 'sheen': (1, 3),
    },
    'swept': {
        'front': [7, 7, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 3, 4, 5],
        'side': ["################", "################", "################", "################", "################",
                 "#############...", "############....", "############....", "######...###....", "######...###....",
                 "#####....##.....", "#####....#......", "####............", "###.............", "................",
                 "................"],
        'back': 13, 'sheen': (1, 3),
    },
    'messy': {
        'front': [6, 7, 5, 6, 7, 5, 4, 6, 5, 7, 5, 4, 6, 5, 7, 6],
        'side': ["################", "################", "################", "################", "################",
                 "################", "#############.#.", "############....", "######...###....", "######...##.....",
                 "######...#......", "#####...........", "#####...........", "###.#...........", "................",
                 "................"],
        'back': 14, 'sheen': (1, 3),
    },
    'curly': {
        'front': [6, 5, 4, 4, 4, 3, 3, 3, 3, 3, 3, 4, 4, 4, 5, 6],
        'side': ["################", "################", "################", "################", "################",
                 "################", "############....", "############....", "######...###....", "######...###....",
                 "#####....##.....", "#####...........", "####............", "###.............", "................",
                 "................"],
        'back': 13, 'sheen': (1, 2), 'curly': True,
    },
    'tied': {
        'front': [6, 5, 4, 4, 3, 3, 3, 2, 2, 3, 3, 3, 4, 4, 5, 6],
        'side': ["################", "################", "################", "################", "################",
                 "##############..", "#############...", "##########......", "######..........", "#####...........",
                 "#####...........", "####............", "###.............", "................", "................",
                 "................"],
        'back': 13, 'sheen': (1, 3),
    },
    'parted': {   # centre parting, pulled back (pigtails, buns, braids)
        'front': [7, 6, 5, 4, 4, 3, 3, 2, 2, 3, 3, 4, 4, 5, 6, 7],
        'side': ["################", "################", "################", "################", "################",
                 "##############..", "#############...", "##########......", "#######.........", "######..........",
                 "#####...........", "####............", "###.............", "................", "................",
                 "................"],
        'back': 13, 'sheen': (1, 3), 'part': 'centre',
    },
    'long': {
        'front': [16, 16, 9, 6, 5, 4, 3, 2, 2, 3, 4, 5, 6, 9, 16, 16],
        'side': ["################"] * 16,
        'back': 16, 'sheen': (1, 3), 'part': 'centre', 'bottom': 10,
    },
    'long_swept': {
        'front': [16, 16, 10, 8, 7, 6, 5, 5, 4, 4, 3, 3, 3, 4, 16, 16],
        'side': ["################"] * 16,
        'back': 16, 'sheen': (1, 3), 'part': 'side', 'bottom': 10,
    },
    'bob': {
        'front': [14, 14, 8, 6, 5, 5, 5, 5, 5, 5, 5, 5, 6, 8, 14, 14],
        'side': ["################"] * 13 + ["##############..", "................", "................"],
        'back': 15, 'sheen': (1, 3),
    },
    'balding': {
        'front': [7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7],
        'side': ["................", "................", "................", "####............", "#######.........",
                 "##########......", "############....", "############....", "######...###....", "######...##.....",
                 "#####....##.....", "#####...........", "####............", "###.............", "................",
                 "................"],
        'back': 13, 'back_top': 2, 'sheen': (5, 6), 'top': 'fringe',
    },
    'bald': {'front': [0] * 16, 'side': ["." * 16] * 16, 'back': 0, 'top': 'none'},
    'shaved': {  # close-cropped: the scalp shows through a short dark shadow of hair
        'front': [5, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 5],
        'side': ["################", "################", "################", "################", "################",
                 "############....", "############....", "###########.....", "######...##.....", "######...#......",
                 "#####...........", "####............", "###.............", "................", "................",
                 "................"],
        'back': 12, 'sheen': None, 'stubble': True,
    },
}


# ================================================================ the head

def brow_colour(h):
    if h.get('brows'):
        return rgba(h['brows'])
    hc = h.get('hair') or h.get('beard_col') or (90, 70, 60)
    lum = 0.3 * hc[0] + 0.59 * hc[1] + 0.11 * hc[2]
    return tone(hc, -2.4 if lum > 150 else -1.4)


def face_front(h, sk, st):
    cv = Canvas(16, 16, sk['s'])
    age, fem = h.get('age', 'adult'), h.get('fem', False)
    child, old = age == 'child', age == 'old'
    # Light from above and in front: the forehead, cheekbones and chin catch it, the face's sides and jaw turn away
    for y in range(16):
        cv.shade(0, y, -0.7)
        cv.shade(15, y, -0.7)
    cv.shade_rect(4, 3, 12, 6, 0.35)
    cv.shade_rect(2, 10, 4, 12, 0.3)
    cv.shade_rect(12, 10, 14, 12, 0.3)
    for x, y in ((1, 12), (1, 13), (1, 14), (2, 14), (1, 15), (2, 15), (3, 15), (14, 12), (14, 13), (14, 14), (13, 14), (14, 15),
                 (13, 15), (12, 15)):
        cv.shade(x, y, -0.8)
    cv.shade_rect(4, 15, 12, 16, -0.45)
    cv.shade_rect(2, 7, 6, 8, -0.45)
    cv.shade_rect(10, 7, 14, 8, -0.45)
    # Cheeks: a warm flush (bakers, children, cold fishers)
    if h.get('blush'):
        t = h['blush']
        for x, y in ((2, 10), (3, 10), (4, 10), (2, 11), (3, 11), (11, 10), (12, 10), (13, 10), (12, 11), (13, 11)):
            cv.tint(x, y, sk['blush'], t)
        cv.tint(4, 11, sk['blush'], t * 0.5)
        cv.tint(11, 11, sk['blush'], t * 0.5)
    # Nose
    nose = h.get('nose', 'small' if (fem or child) else 'plain')
    if nose == 'small':
        cv.shade(7, 10, 0.3); cv.shade(7, 11, -0.8); cv.shade(8, 11, -0.9); cv.shade(8, 10, -0.3)
    elif nose == 'broad':
        cv.shade(7, 9, 0.3); cv.shade(7, 10, 0.4); cv.shade(8, 10, -0.5); cv.shade(9, 10, -0.3)
        for x, k in zip(range(5, 11), (-0.4, -0.9, -1.0, -1.1, -0.9, -0.4)):
            cv.shade(x, 11, k)
    elif nose == 'long':
        cv.shade(7, 8, 0.3); cv.shade(7, 9, 0.4); cv.shade(7, 10, 0.4); cv.shade(8, 9, -0.4); cv.shade(8, 10, -0.6)
        cv.shade(6, 11, -0.4); cv.shade(7, 11, -1.0); cv.shade(8, 11, -1.1); cv.shade(9, 11, -0.5)
    else:
        cv.shade(7, 9, 0.25); cv.shade(7, 10, 0.4); cv.shade(8, 10, -0.5)
        for x, k in zip(range(6, 10), (-0.5, -1.0, -1.1, -0.5)):
            cv.shade(x, 11, k)
    # Mouth
    mouth = h.get('mouth', 'lips' if fem and not child else 'smile' if child else 'plain')
    if mouth == 'lips':
        cv.set(6, 12, tone(sk['lip'], -0.6)); cv.set(7, 12, sk['mouth']); cv.set(8, 12, sk['mouth']); cv.set(9, 12, tone(sk['lip'], -0.6))
        cv.set(7, 13, sk['lip']); cv.set(8, 13, tone(sk['lip'], 0.5))
        cv.shade(6, 13, -0.2); cv.shade(9, 13, -0.2)
    elif mouth == 'smile':
        cv.shade(5, 11, -0.7); cv.shade(10, 11, -0.7)
        cv.shade(6, 12, -1.1); cv.set(7, 12, sk['mouth']); cv.set(8, 12, sk['mouth']); cv.shade(9, 12, -1.1)
        cv.shade(7, 13, 0.3); cv.shade(8, 13, 0.3)
    elif mouth == 'grin':
        cv.shade(5, 11, -0.9); cv.shade(10, 11, -0.9)
        cv.set(6, 12, sk['mouth']); cv.set(7, 12, (236, 230, 220)); cv.set(8, 12, (236, 230, 220)); cv.set(9, 12, sk['mouth'])
        cv.set(7, 13, sk['mouth']); cv.set(8, 13, sk['mouth'])
    elif mouth == 'stern':
        for x in range(6, 10):
            cv.set(x, 12, mix(sk['mouth'], sk['s'], 0.25))
        cv.shade(5, 13, -0.7); cv.shade(10, 13, -0.7)
    else:
        cv.shade(6, 12, -1.1); cv.set(7, 12, sk['mouth']); cv.set(8, 12, sk['mouth']); cv.shade(9, 12, -1.1)
        cv.shade(7, 13, 0.35); cv.shade(8, 13, 0.25)
    cv.shade(7, 14, 0.3); cv.shade(8, 14, 0.2)
    # Age
    if old:
        for x, y in ((1, 8), (1, 9), (2, 9), (14, 8), (14, 9), (13, 9)):
            cv.shade(x, y, -0.8)
        for x in range(3, 6):
            cv.shade(x, 10, -0.5)
        for x in range(10, 13):
            cv.shade(x, 10, -0.5)
        for x, y in ((5, 11), (5, 12), (10, 11), (10, 12)):
            cv.shade(x, y, -0.7)
        for x in (4, 6, 9, 11):
            cv.shade(x, 4, -0.5)
        for x in (5, 7, 8, 10):
            cv.shade(x, 5, -0.4)
    if h.get('freckles'):
        n = h['freckles']
        spots = [(3, 10), (5, 10), (4, 11), (10, 10), (12, 10), (11, 11), (6, 9), (9, 9)]
        if n > 1:
            spots += [(2, 11), (13, 11), (7, 9), (6, 10), (9, 10)]
        fc = mix(sk['sh2'], (170, 92, 50), 0.35)
        for x, y in spots:
            cv.tint(x, y, fc, 0.75)
    # Eyes
    iris = h.get('eyes', EYES['brown'])
    pupil = tone(iris, -2.4)
    il = tone(iris, 0.9)
    w1, w2 = (240, 236, 230), (212, 206, 204)
    glint = (252, 252, 248)
    lash = (46, 32, 30) if sk['lum'] > 120 else (30, 20, 18)
    eyes = h.get('eye_shape', 'child' if child else 'old' if old else 'adult')
    for side in (0, 1):
        # outer white column, then two iris columns toward the nose
        wx = 3 if side == 0 else 12
        ix = (4, 5) if side == 0 else (11, 10)    # the outer iris column first
        if eyes == 'old':
            for x in (3, 4, 5) if side == 0 else (10, 11, 12):
                cv.shade(x, 8, -1.0)
            cv.set(wx, 9, w2); cv.set(ix[0], 9, pupil); cv.set(ix[1], 9, iris)
            cv.set(min(ix), 9, tone(iris, -0.6) if side else pupil)
        elif eyes == 'child':
            cv.set(wx, 9, w2); cv.set(wx, 10, w1)
            cv.set(ix[0], 8, pupil); cv.set(ix[1], 8, pupil)
            cv.set(ix[0], 9, glint); cv.set(ix[1], 9, pupil)
            cv.set(ix[0], 10, iris); cv.set(ix[1], 10, il)
            if side == 1:   # the light comes from the same side for both eyes
                cv.set(10, 9, glint); cv.set(11, 9, pupil)
        else:
            cv.set(wx, 8, w2); cv.set(wx, 9, w1)
            cv.set(ix[0], 8, pupil); cv.set(ix[1], 8, pupil)
            cv.set(ix[0], 9, iris); cv.set(ix[1], 9, il)
            cv.set(4 if side == 0 else 10, 8, glint)
            cv.set(4 if side == 0 else 10, 9, iris)
            cv.set(5 if side == 0 else 11, 9, il)
        if fem and eyes != 'old':
            top = 7 if eyes != 'child' else 7
            for x in ((2, 3, 4, 5) if side == 0 else (10, 11, 12, 13)):
                cv.set(x, top, lash)
            cv.set(2 if side == 0 else 13, top + 1, mix(lash, sk['s'], 0.4))
        elif eyes == 'child':
            for x in ((3, 4, 5) if side == 0 else (10, 11, 12)):
                cv.shade(x, 7, -1.2)
    # Brows
    bc = brow_colour(h)
    bs = h.get('brow_style', 'arched' if fem else 'soft' if child else 'bushy' if old else 'straight')
    rb = {'straight': ([(2, 6), (3, 6), (4, 6), (5, 6)], [(3, 5), (4, 5)]),
          'thick': ([(2, 6), (3, 6), (4, 6), (5, 6), (2, 5), (3, 5), (4, 5)], []),
          'stern': ([(2, 5), (3, 5), (4, 6), (5, 6)], [(3, 6)]),
          'arched': ([(3, 5), (4, 5), (5, 5), (2, 6)], []),
          'soft': ([(3, 6), (4, 6), (5, 6)], []),
          'bushy': ([(2, 6), (3, 6), (4, 6), (5, 6), (3, 5), (4, 5)], [(2, 5), (5, 5)])}[bs]
    for x, y in rb[0]:
        cv.set(x, y, bc); cv.set(15 - x, y, bc)
    for x, y in rb[1]:
        cv.set(x, y, mix(bc, cv.get(x, y), 0.5)); cv.set(15 - x, y, mix(bc, cv.get(15 - x, y), 0.5))
    if bs in ('straight', 'thick', 'bushy'):
        cv.set(5, 6, tone(bc, 0.6)); cv.set(10, 6, tone(bc, 0.6))
    # Stubble and moustache
    hc = h.get('beard_col') or h.get('hair') or (80, 60, 50)
    if h.get('stubble'):
        t = h['stubble'] if isinstance(h['stubble'], float) else 0.28
        for y in range(11, 16):
            for x in range(1, 15):
                if y == 11 and 5 <= x <= 10:
                    continue
                if y == 12 and 6 <= x <= 9:
                    continue
                cv.tint(x, y, tone(hc, -1), t * (0.6 if y == 11 or x in (1, 14) else 1.0))
    ms = h.get('moustache')
    if ms:
        mc = hc
        if ms == 'brush':
            px = [(x, 11) for x in range(5, 11)] + [(5, 12), (10, 12)]
        elif ms == 'handlebar':
            px = [(x, 11) for x in range(5, 11)] + [(4, 10), (11, 10), (4, 11), (11, 11), (5, 12), (10, 12)]
        elif ms == 'droop':
            px = [(x, 11) for x in range(5, 11)] + [(5, 12), (10, 12), (5, 13), (10, 13), (5, 14), (10, 14)]
        else:  # thin
            px = [(x, 11) for x in range(6, 10)]
        for x, y in px:
            k = -0.6 if x in (7, 8) else 0.4 if (x + y) % 3 == 0 else 0
            cv.set(x, y, tone(mc, k))
    if h.get('soot'):
        for x, y, t in ((11, 10, .4), (12, 10, .45), (12, 11, .35), (13, 11, .3), (11, 11, .2), (3, 4, .3), (4, 4, .35), (4, 5, .25),
                        (2, 12, .25), (3, 13, .3)):
            cv.tint(x, y, (54, 48, 46), t)
    if h.get('flour'):
        for x, y, t in ((12, 9, .45), (12, 10, .55), (13, 10, .4), (3, 5, .35), (4, 5, .3)):
            cv.tint(x, y, (248, 244, 234), t)
    if h.get('scar'):
        for x, y in ((11, 6), (11, 7), (12, 8)):
            cv.set(x, y, mix(sk['sh2'], sk['blush'], 0.4))
        cv.shade(12, 9, -0.6)
    # Hair over the brow: the fringe, then its shadow on the forehead
    if h.get('hair') and st.get('front') is not None:
        m = depth_mask(st['front'], 16)
        if m.any():
            paint_hair(cv, m, h['hair'], h['seed'] + 1, sheen=(1, 2), curly=st.get('curly', False))
            hair_shadow(cv, m)
    if h.get('hat_shadow'):
        y0 = h['hat_shadow']
        for x in range(16):
            cv.shade(x, y0, -0.7)
            cv.shade(x, y0 + 1, -0.35)
    return cv


def beard_mask_side():
    # the jaw of the head's right side (back at column 0): from below the ear forward to the chin
    return mask_rows(["................", "................", "................", "................", "................",
                      "................", "................", "................", "..........##....", "..........###...",
                      ".........######.", ".........#######", "........########", ".......#########", "........########",
                      "..........######"])


def face_side(h, sk, st, side):
    cv = Canvas(16, 16, sk['s'])
    for x in range(16):
        cv.shade(x, 15, -0.5)
    cv.shade_rect(0, 0, 3, 16, -0.3)
    cv.shade_rect(13, 8, 16, 12, 0.3)
    # the jaw line and the neck in its shadow
    for x, y in ((9, 13), (10, 13), (11, 14), (12, 14), (13, 15), (14, 15), (15, 15)):
        cv.shade(x, y, -0.6)
    cv.shade_rect(3, 13, 11, 16, -0.7)
    cv.shade_rect(4, 14, 10, 16, -0.3)
    hair = h.get('hair')
    m = _side(st['side']) if hair else np.zeros((16, 16), bool)
    ear_hidden = m[8:12, 6:9].all()
    if not ear_hidden and not h.get('no_ears'):
        cv.stamp(6, 8, ["+..", ".2-", ".2-", "-+."], {'+': 0.4, '-': -0.6, '2': -1.3})
        cv.shade(9, 9, -0.3); cv.shade(9, 10, -0.3)
        if h.get('earrings'):
            cv.set(7, 12, h['earrings']); cv.set(7, 13, tone(h['earrings'], -1))
    if h.get('blush'):
        cv.tint(14, 10, sk['blush'], h['blush'] * 0.6); cv.tint(15, 10, sk['blush'], h['blush'] * 0.8)
        cv.tint(15, 11, sk['blush'], h['blush'] * 0.6)
    hc = h.get('beard_col') or hair or (80, 60, 50)
    if h.get('stubble'):
        t = h['stubble'] if isinstance(h['stubble'], float) else 0.28
        bm = beard_mask_side()
        for y in range(16):
            for x in range(16):
                if bm[y, x]:
                    cv.tint(x, y, tone(hc, -1), t)
    if h.get('beard_side'):
        bm = beard_mask_side()
        paint_hair(cv, bm, hc, h['seed'] + 7, sheen=None, curly=h.get('beard_curly', False))
    if h.get('soot') and side == 'right':
        cv.tint(14, 11, (54, 48, 46), 0.3); cv.tint(13, 12, (54, 48, 46), 0.25)
    if hair:
        paint_hair(cv, m, hair, h['seed'] + (2 if side == 'right' else 3), sheen=st.get('sheen'), curly=st.get('curly', False))
        hair_shadow(cv, m)
    if h.get('hat_shadow'):
        y0 = h['hat_shadow']
        for x in range(16):
            cv.shade(x, y0, -0.6)
    return cv if side == 'right' else cv.flip()


def face_back(h, sk, st):
    cv = Canvas(16, 16, sk['s'])
    cv.shade_rect(0, 12, 16, 16, -0.5)
    cv.shade_rect(4, 13, 12, 16, -0.4)
    for y in range(16):
        cv.shade(0, y, -0.4); cv.shade(15, y, -0.4)
    hair = h.get('hair')
    if hair and st.get('back'):
        rng = random.Random(h['seed'] + 4)
        d = st['back']
        depths = [d if d >= 16 else d - (1 if x in (0, 15) else 0) + (rng.choice((0, 0, 1)) if 2 < x < 13 else 0) for x in range(16)]
        m = depth_mask(depths, 16)
        top = st.get('back_top', 0)
        m[:top] = False
        paint_hair(cv, m, hair, h['seed'] + 5, sheen=st.get('sheen') and (st['sheen'][0] + 1, st['sheen'][1] + 2),
                   curly=st.get('curly', False))
        hair_shadow(cv, m)
    if h.get('beard_side'):
        pass
    return cv


def face_top(h, sk, st):
    cv = Canvas(16, 16, sk['s'])
    hair = h.get('hair')
    kind = st.get('top', 'full')
    if not hair or kind in ('none', 'fringe'):
        # bare scalp, with a shine where the light falls
        cv.shade_rect(0, 0, 16, 16, 0.2)
        cv.shade_rect(5, 6, 11, 12, 0.5)
        cv.shade(7, 8, 1.0); cv.shade(8, 8, 0.8); cv.shade(7, 9, 0.7)
        for i in range(16):
            cv.shade(i, 0, -0.4); cv.shade(0, i, -0.4); cv.shade(15, i, -0.4)
        if hair and kind == 'fringe':
            m = np.zeros((16, 16), bool)
            m[0:3, :] = True
            m[:9, 0:2] = True
            m[:9, 14:16] = True
            paint_hair(cv, m, hair, h['seed'] + 6, curly=st.get('curly', False))
        return cv
    m = np.ones((16, 16), bool)
    paint_hair(cv, m, hair, h['seed'] + 6, sheen=(10, 13), curly=st.get('curly', False), edge=False, tips=False)
    part = st.get('part')
    if part:
        px = 8 if part == 'centre' else 5
        for y in range(5, 16):
            cv.set(px, y, mix(sk['sh'], hair, 0.35))
            cv.shade(px - 1, y, -0.6)
            cv.shade(px + 1, y, 0.4)
    return cv


def face_bottom(h, sk, st):
    # rows run from the back (row 0) to the chin (row 15)
    cv = Canvas(16, 16, sk['sh'])
    cv.shade_rect(2, 3, 14, 12, -0.7)
    hair = h.get('hair')
    rows = st.get('bottom', 0) if hair else 0
    if rows:
        m = np.zeros((16, 16), bool)
        m[:rows] = True
        paint_hair(cv, m, hair, h['seed'] + 8, edge=False)
    elif hair and st.get('back', 0) >= 13:
        m = np.zeros((16, 16), bool)
        m[:1] = True
        paint_hair(cv, m, hair, h['seed'] + 8, edge=False)
    if h.get('beard_side'):
        hc = h.get('beard_col') or hair
        m = np.zeros((16, 16), bool)
        m[11:16, 1:15] = True
        paint_hair(cv, m, hc, h['seed'] + 9, edge=False)
    return cv


def head_painter(h):
    sk = skin_ramp(h['skin'])
    st = STYLES[h.get('style', 'crop')]
    faces = {'front': face_front(h, sk, st), 'right': face_side(h, sk, st, 'right'), 'left': face_side(h, sk, st, 'left'),
             'back': face_back(h, sk, st), 'top': face_top(h, sk, st), 'bottom': face_bottom(h, sk, st)}
    if h.get('head_extra'):
        h['head_extra'](faces, sk)
    return lambda face, w, hh: faces[face]


# ================================================================ cloth, leather, metal

STEEL = (156, 162, 174)
GOLD = (214, 168, 60)
GOLD_L = (246, 212, 120)


def drape(cv, col, face=None, top=0.5, bottom=-0.45, edges=-0.45):
    """Fill with [col], lit from above: the top rows lighter, the bottom rows and the side edges in shade."""
    cv.fill(col)
    W, H = cv.W, cv.H
    for x in range(W):
        cv.shade(x, 0, top)
        if H > 6:
            cv.shade(x, 1, top * 0.5)
            cv.shade(x, H - 1, bottom)
            cv.shade(x, H - 2, bottom * 0.45)
    if edges and face not in ('top', 'bottom') and W > 4:
        for y in range(H):
            cv.shade(0, y, edges)
            cv.shade(W - 1, y, edges)
    return cv


def mail(cv, mask=None, base=STEEL, lit=0.0):
    """Riveted mail: rows of small rings, each lit on its upper left and dark below, every other row offset."""
    for y in range(cv.H):
        for x in range(cv.W):
            if mask is not None and not mask[y, x]:
                continue
            r, c = y % 2, (x + (y // 2) % 2) % 2
            k = {(0, 0): 0.9, (0, 1): 0.1, (1, 0): -0.4, (1, 1): -1.4}[(r, c)]
            k += lit - 0.5 * (y / max(1, cv.H - 1))
            cv.a[y, x] = tone(base, k)


def stitches(cv, pts, k=0.9):
    """A dashed seam: every other texel along [pts] picks up the light."""
    for i, (x, y) in enumerate(pts):
        if i % 2 == 0:
            cv.shade(x, y, k)


def seam(cv, pts, k=-0.8, lit=0.35):
    for x, y in pts:
        cv.shade(x, y, k)


def smudge(cv, rng, col, n, area, size=(2, 3), t=(0.25, 0.45)):
    """[n] soft blotches of [col] (soot, flour, mud, stains) somewhere in [area] (x0, y0, x1, y1)."""
    x0, y0, x1, y1 = area
    for _ in range(n):
        cx, cy = rng.randrange(x0, max(x0 + 1, x1)), rng.randrange(y0, max(y0 + 1, y1))
        r = rng.choice(size)
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                d = abs(dx) + abs(dy)
                if d <= r and rng.random() < 1.0 - d / (r + 1.5):
                    cv.tint(cx + dx, cy + dy, col, rng.uniform(*t) * (1 - d / (r + 2)))


SOOT = (52, 48, 48)
FLOUR = (250, 247, 238)
MUD = (104, 80, 56)


# ================================================================ the hat layer: hair volume, kerchiefs, caps, mail coifs

def hat_layer_painter(h):
    kind = h.get('layer')
    if not kind:
        return None
    col = h.get('layer_col') or h.get('hair')
    seed = h['seed'] + 40

    def p(face, W, H):
        cv = Canvas(W, H)
        if face == 'bottom':
            if kind in ('coif', 'wimple'):
                c = Canvas(W, H)
                if kind == 'coif':
                    mail(c, base=col, lit=-0.6)
                else:
                    drape(c, col, 'bottom', 0, 0, 0)
                    c.shade_rect(0, 0, W, H, -1.0)
                c.clear_rect(2, 2, W - 2, H - 2)
                return c
            return cv
        if kind == 'volume':
            curly = h.get('layer_curly', False)
            if face == 'top':
                m = np.ones((H, W), bool)
                for x, y in ((0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)):
                    m[y, x] = False
            elif face == 'front':
                d = h.get('layer_front', [4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4])
                m = depth_mask(d, H)
            elif face in ('right', 'left'):
                rows = h.get('layer_side', ["################"] * 6 + ["###########.....", "#########.......", "########........",
                                                                         "#######.........", "######..........", "####............"])
                m = mask_rows(rows)
                if face == 'left':
                    m = m[:, ::-1]
            else:
                m = depth_mask([h.get('layer_back', 12)] * W, H)
            if h.get('layer_ragged'):
                rng = random.Random(seed + hash(face) % 7)
                for x in range(W):
                    col_rows = np.where(m[:, x])[0]
                    if len(col_rows) and face != 'top':
                        last = col_rows[-1]
                        cut = rng.choice((0, 0, 1, 1, 2))
                        m[max(0, last - cut + 1):last + 1, x] = False
            paint_hair(cv, m, col, seed + len(face), sheen=(1, 3) if face != 'top' else (9, 12), curly=curly)
            return cv
        if kind == 'fringe':
            if face != 'front':
                return cv
            m = depth_mask(h.get('layer_front', [3, 4, 4, 5, 4, 5, 4, 3, 3, 4, 5, 4, 5, 4, 4, 3]), H)
            paint_hair(cv, m, col, seed + 3, sheen=(1, 2), curly=h.get('layer_curly', False))
            return cv
        if kind == 'kerchief':
            pat = h.get('layer_pattern')
            c2 = h.get('layer_col2', tone(col, -1.5))
            drape(cv, col, face, 0.4, -0.3, -0.3)
            if pat:
                for y in range(H):
                    for x in range(W):
                        if pat == 'dots' and (x % 4 == 1 and y % 4 == 1 or x % 4 == 3 and y % 4 == 3):
                            cv.set(x, y, c2)
                        elif pat == 'check' and ((x // 3) + (y // 3)) % 2 == 0:
                            cv.tint(x, y, c2, 0.45)
                        elif pat == 'stripe' and x % 5 == 2:
                            cv.tint(x, y, c2, 0.7)
            if face == 'top':
                # gathered from the brow back to the knot (the top face's first row is the back): folds converge there
                for x0, k in ((1, -0.5), (4, 0.35), (6, -0.4), (10, -0.4), (12, 0.35), (14, -0.5)):
                    cv.line(x0, H - 2, 7 if x0 < 8 else 8, 0, None, k=k)
                return cv
            if face == 'front':
                m = depth_mask([4] * W, H)
            elif face in ('right', 'left'):
                d = [14, 14, 13, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 4, 4, 4]
                m = depth_mask(d if face == 'right' else d[::-1], H)
                # folds sweep back and down toward the knot
                for x0, k in ((5, -0.45), (9, -0.45), (7, 0.3)):
                    xs = x0 if face == 'right' else W - 1 - x0
                    cv.line(xs, 1, (1 if face == 'right' else W - 2), 10, None, k=k)
            else:
                m = depth_mask([13, 13, 13, 13, 14, 14, 15, 16, 16, 15, 14, 14, 13, 13, 13, 13], H)
                for x0, k in ((0, -0.5), (3, 0.3), (5, -0.5), (10, -0.5), (12, 0.3), (15, -0.5)):
                    cv.line(x0, 0, 7 if x0 < 8 else 8, 7, None, k=k)
                if h.get('knot', True):
                    # the knot's shadow on the cloth round it
                    for y in range(7, 16):
                        for x in range(4, 12):
                            cv.shade(x, y, -0.7 if 5 <= x <= 10 else -0.35)
            for y in range(H):
                for x in range(W):
                    if not m[y, x]:
                        cv.clear(x, y)
                    elif y + 1 < H and not m[y + 1, x]:
                        cv.shade(x, y, 0.8)          # the folded hem catches the light
                        cv.shade(x, y - 1, -0.4)
            return cv
        if kind == 'leather_cap':
            drape(cv, col, face, 0.45, 0, -0.3)
            if face == 'top':
                # four panels sewn together, the seams stitched, the crown worn shiny
                for i in range(W):
                    cv.shade(i, H // 2, -0.8); cv.shade(W // 2, i, -0.8)
                    if i % 2 == 0:
                        cv.shade(i, H // 2 + 1, 0.5); cv.shade(W // 2 + 1, i, 0.5)
                cv.shade_rect(4, 9, 7, 12, 0.4)
                for i in range(W):
                    cv.shade(i, 0, -0.4); cv.shade(0, i, -0.4); cv.shade(W - 1, i, -0.4)
                return cv
            if face == 'front':
                depth = [3] * W
            elif face == 'back':
                depth = [6] * W
            else:
                d = [6, 6, 6, 6, 6, 6, 5, 5, 5, 5, 4, 4, 4, 3, 3, 3]
                depth = d if face == 'right' else d[::-1]
            m = depth_mask(depth, H)
            for y in range(H):
                for x in range(W):
                    if not m[y, x]:
                        cv.clear(x, y)
                    elif y + 1 >= H or not m[y + 1, x]:
                        cv.shade(x, y, -0.9)           # the rolled edge
                        cv.shade(x, y - 1, 0.55)
            if face in ('front', 'back'):
                seam(cv, [(W // 2, y) for y in range(depth[0] - 1)], -0.8)
            elif face in ('right', 'left'):
                seam(cv, [(W // 2, y) for y in range(depth[W // 2] - 1)], -0.7)
            return cv
        if kind == 'headband':
            if face == 'top':
                return cv
            y0 = h.get('band_row', 4)
            for y in range(y0, y0 + 3):
                for x in range(W):
                    cv.set(x, y, tone(col, 0.6 if y == y0 else -0.5 if y == y0 + 2 else 0))
            if face == 'back':
                cv.stamp(6, y0, ["####", "####", "-##-", ".#..", ".#.#", "#..#", "#..#"],
                         {'#': tone(col, -0.2), '-': tone(col, -1.2)})
            return cv
        if kind == 'coif':
            mail(cv, base=col, lit=0.3 if face == 'top' else 0)
            if face == 'front':
                for y in range(5, 15):
                    for x in range(3, 13):
                        if (y == 14 and x in (3, 12)):
                            continue
                        cv.clear(x, y)
                for x in range(3, 13):
                    cv.shade(x, 4, -0.8)
                for y in range(5, 15):
                    cv.shade(2, y, 0.5)
                    cv.shade(13, y, 0.3)
            return cv
        if kind == 'wimple':
            if face == 'top':
                return cv
            drape(cv, col, face, 0.4, -0.3, -0.3)
            if face == 'front':
                for y in range(5, 14):
                    for x in range(3, 13):
                        cv.clear(x, y)
                for x in range(3, 13):
                    cv.shade(x, 14, -0.6)
                for y in range(5, 14):
                    cv.shade(2, y, -0.6)
                    cv.shade(13, y, -0.6)
                cv.clear_rect(0, 0, W, 3)
                for x in range(W):
                    cv.shade(x, 3, 0.5)
            else:
                for y in range(3, H, 4):
                    cv.line(2, y, W - 3, y + 2, None, k=-0.4)
            return cv
        return cv
    return p


# ================================================================ the torso

NECKS = {
    'crew': [(x, 0) for x in range(5, 11)] + [(x, 1) for x in range(6, 10)],
    'v': [(x, 0) for x in range(5, 11)] + [(x, 1) for x in range(6, 10)] + [(x, 2) for x in range(6, 10)] + [(7, 3), (8, 3), (7, 4), (8, 4)],
    'deep_v': [(x, 0) for x in range(4, 12)] + [(x, 1) for x in range(5, 11)] + [(x, 2) for x in range(6, 10)] +
              [(x, y) for y in (3, 4, 5) for x in (7, 8)] + [(7, 6), (8, 6)],
    'slit': [(x, 0) for x in range(5, 11)] + [(x, 1) for x in range(6, 10)] + [(7, 2), (8, 2), (7, 3), (8, 3), (7, 4)],
    'square': [(x, y) for y in range(0, 4) for x in range(4, 12)],
    'scoop': [(x, 0) for x in range(3, 13)] + [(x, 1) for x in range(4, 12)] + [(x, 2) for x in range(5, 11)],
    'wide': [(x, 0) for x in range(2, 14)] + [(x, 1) for x in range(3, 13)] + [(x, 2) for x in range(4, 12)] + [(x, 3) for x in range(6, 10)],
    'high': [], 'none': [],
}


def torso_painter(t, h):
    """The torso's faces for one look, from its garment description [t]: a base garment and what lies over it
    (vest, bodice, doublet, coat, tabard, apron bib, shawl, sash, straps, belt...), lit from above."""
    sk = skin_ramp(h['skin'])
    seed = h['seed'] + 100
    rng = random.Random(seed)
    col = t['col']
    faces = {}
    belt_y = t.get('belt_y', 18)
    waist = t.get('waist')

    def base(face, W, H):
        cv = drape(Canvas(W, H), col, face)
        pat = t.get('pattern')
        if pat and face in ('front', 'back', 'right', 'left', 'top'):
            kind, c2 = pat[0], pat[1]
            for y in range(H):
                for x in range(W):
                    use2 = False
                    if kind == 'harlequin':
                        u, v = (x + y + 2) // 4, (x - y + 64) // 4
                        use2 = (u + v) % 2 == 1
                    elif kind == 'quarter':
                        use2 = (x < W // 2) != (y < H // 2)
                        if face in ('right', 'left'):
                            use2 = (y < H // 2) != (face == 'left')
                    elif kind == 'stripes':
                        use2 = (y // pat[2]) % 2 == 1
                    elif kind == 'vstripes':
                        use2 = (x // pat[2]) % 2 == 1
                    elif kind == 'check':
                        use2 = ((x // 3) + (y // 3)) % 2 == 1
                    if use2:
                        k = tone(cv.get(x, y), 0)
                        cv.set(x, y, mix(c2, k, 0.0) if kind != 'check' else mix(cv.get(x, y), c2, 0.5))
                        # keep the lighting of the base on the second colour
                        if y == 0:
                            cv.shade(x, y, 0.5)
                        elif y >= H - 1:
                            cv.shade(x, y, -0.45)
            if kind == 'harlequin':
                for y in range(H):
                    for x in range(W):
                        if (x + y + 2) % 4 == 0 and (x - y + 64) % 4 == 0:
                            cv.set(x, y, t.get('pattern_dot', GOLD))
        if face in ('front', 'back') and W >= 16:
            # creases under the arms, the chest in the light, the flanks turning away
            for x, y in ((1, 4), (1, 5), (2, 6), (14, 4), (14, 5), (13, 6)):
                cv.shade(x, y, -0.5)
            if face == 'front':
                cv.shade_rect(3, 2, 13, 8, 0.15)
            for y in range(H):
                cv.shade(1, y, -0.2)
                cv.shade(14, y, -0.25)
        return cv

    def skirt_below(cv, face):
        """Below a dress's waist the torso is the top of its skirt, gathered."""
        if waist is None:
            return
        sc = t.get('skirt', col)
        folds = (3, 8, 12) if cv.W >= 16 else (3,)
        for y in range(waist, cv.H):
            for x in range(cv.W):
                k = 0.3 if y == waist + 1 else 0
                if y > waist + 1 and x in folds:
                    k -= 0.5
                if y > waist + 1 and x + 1 in folds:
                    k += 0.3
                if y >= cv.H - 1:
                    k -= 0.3
                cv.set(x, y, tone(sc, k))
        for x in range(cv.W):
            cv.shade(x, waist, -0.8)
        if face in ('front', 'back') and cv.W >= 16:
            for y in range(waist, cv.H):
                cv.shade(0, y, -0.4)
                cv.shade(cv.W - 1, y, -0.4)

    def belt(cv, face):
        bc = t.get('belt')
        if not bc:
            return
        for x in range(cv.W):
            cv.set(x, belt_y, tone(bc, 0.5))
            cv.set(x, belt_y + 1, tone(bc, -0.3))
            cv.shade(x, belt_y + 2, -0.7)
            cv.shade(x, belt_y - 1, -0.3)
        if face == 'front' and t.get('buckle', True):
            bk = t.get('buckle_col', (196, 184, 150) if t.get('buckle') is not False else bc)
            if isinstance(t.get('buckle'), tuple):
                bk = t['buckle']
            bx = t.get('buckle_x', 6)
            cv.stamp(bx, belt_y - 1, ["abb", "a.b", "a.b", "bbc"], {'a': tone(bk, 1.2), 'b': rgba(bk), 'c': tone(bk, -1.2)})
            cv.set(bx + 1, belt_y, tone(bc, 0.3)); cv.set(bx + 1, belt_y + 1, tone(bc, -0.5))
            cv.set(bx + 3, belt_y, tone(bc, -1.0))
        if face == 'back':
            for x in range(1, cv.W, 4):
                cv.shade(x, belt_y, 0.6)
        pouch = t.get('belt_pouch')
        if pouch and face == 'left':
            cv.stamp(2, belt_y - 1, ["aaaa", "abba", "abba", "abba", "cccc"],
                     {'a': tone(pouch, 0.6), 'b': rgba(pouch), 'c': tone(pouch, -1.2)})

    for face, W, H in (('front', 16, 24), ('back', 16, 24), ('right', 8, 24), ('left', 8, 24), ('top', 16, 8), ('bottom', 16, 8)):
        cv = base(face, W, H)
        if face == 'top':
            # the shoulders, and the neck in the head's shadow
            cv.rect(5, 2, 11, 6, sk['sh'])
            cv.shade_rect(5, 2, 11, 3, -0.6)
            if t.get('mail'):
                mail(cv, base=t.get('mail_col', STEEL), lit=0.4)
                cv.rect(5, 2, 11, 6, sk['sh'])
            faces[face] = cv
            continue
        if face == 'bottom':
            cv.shade_rect(0, 0, W, H, -1.0)
            if waist is not None:
                cv.fill(tone(t.get('skirt', col), -1.2))
            faces[face] = cv
            continue
        if t.get('mail'):
            mail(cv, base=t.get('mail_col', STEEL))
            if face in ('front', 'back'):
                for x, y in ((1, 4), (1, 5), (2, 6), (14, 4), (14, 5), (13, 6)):
                    cv.shade(x, y, -0.5)
        skirt_below(cv, face)
        faces[face] = cv

    F, B, R, L = faces['front'], faces['back'], faces['right'], faces['left']

    # ---- the neckline
    nk = t.get('neck', 'crew')
    under = t.get('under')
    for x, y in NECKS[nk]:
        if under:
            F.set(x, y, tone(under, 0.3 if y == 0 else 0))
        else:
            F.set(x, y, sk['sh'] if y == 0 else sk['s'])
    if under:
        for x in range(6, 10):
            F.set(x, 0, sk['sh'])
        if nk in ('square', 'scoop', 'wide'):
            for x in range(6, 10):
                F.set(x, 1, sk['s'])
            F.set(5, 0, sk['sh']); F.set(10, 0, sk['sh'])
    opening = set(NECKS[nk])
    for x, y in NECKS[nk]:
        for dx, dy in ((1, 0), (-1, 0), (0, 1)):
            q = (x + dx, y + dy)
            if q not in opening and 0 <= q[0] < 16 and q[1] < 24:
                if t.get('neck_trim'):
                    F.set(q[0], q[1], t['neck_trim'])
                else:
                    F.shade(q[0], q[1], -0.7)
    if nk == 'high':
        cc = t.get('neck_trim', tone(col, 0.6))
        for x in range(3, 13):
            F.set(x, 0, tone(cc, 0.6)); F.set(x, 1, rgba(cc))
        for x in range(4, 12):
            B.set(x, 0, tone(cc, 0.4)); B.set(x, 1, tone(cc, -0.3))
    if nk == 'slit' and t.get('laces'):
        for y in (1, 2, 3):
            F.set(6, y, t['laces']); F.set(9, y, t['laces'])
        F.set(7, 2, t['laces']); F.set(8, 3, t['laces'])
    # neck at the back of the collar
    for x in range(6, 10):
        B.set(x, 0, sk['sh'])

    # ---- over-garments
    v = t.get('vest')
    if v:
        vc, kind = v['col'], v.get('kind', 'vest')
        trim = v.get('trim')
        if kind in ('vest', 'jerkin', 'coat'):
            open_x = v.get('open', (5, 10) if kind != 'jerkin' else (7, 8))
            y0 = v.get('top', 0)
            y1 = v.get('bottom', 24)
            for y in range(y0, y1):
                for x in range(16):
                    if open_x[0] <= x <= open_x[1] and kind != 'jerkin':
                        continue
                    if (x, y) in opening:
                        continue
                    F.set(x, y, tone(vc, 0.5 if y == y0 else -0.45 if x in (0, 15) else 0))
            if kind == 'coat':
                # lapels turned back at the chest
                for y in range(0, 7):
                    for x in (open_x[0] - 1, open_x[0] - 2) if y < 5 else (open_x[0] - 1,):
                        F.set(x, y, tone(vc, 0.9 if x == open_x[0] - 1 else 0.4))
                    for x in (open_x[1] + 1, open_x[1] + 2) if y < 5 else (open_x[1] + 1,):
                        F.set(x, y, tone(vc, 0.7 if x == open_x[1] + 1 else 0.3))
            if kind != 'jerkin':
                for y in range(y0, y1):
                    F.set(open_x[0] - 1, y, tone(F.get(open_x[0] - 1, y), -0.6) if kind != 'coat' or y >= 7 else F.get(open_x[0] - 1, y))
                    F.set(open_x[1] + 1, y, tone(F.get(open_x[1] + 1, y), 0.5) if kind != 'coat' or y >= 7 else F.get(open_x[1] + 1, y))
                    F.shade(open_x[0], y, -0.5)
            else:
                for y in range(max(2, y0), y1 - 2):
                    F.shade(7, y, -0.9); F.shade(8, y, -0.5)
                    if y % 3 == 0:
                        F.set(6, y, v.get('laces', (210, 196, 160))); F.set(9, y, v.get('laces', (210, 196, 160)))
                        F.set(7, y + 1, v.get('laces', (210, 196, 160)))
            if trim:
                for y in range(y0, y1):
                    F.set(open_x[0] - 1, y, trim) if kind != 'jerkin' else None
                    F.set(open_x[1] + 1, y, tone(trim, -0.4)) if kind != 'jerkin' else None
            if v.get('buttons'):
                for y in range(y0 + 3, y1 - 2, 4):
                    F.set(open_x[0] - 2, y, tone(v['buttons'], 0.8)); F.set(open_x[0] - 2, y + 1, tone(v['buttons'], -0.8))
            for cv in (B, R, L):
                for y in range(y0, y1):
                    for x in range(cv.W):
                        if cv is not B and t.get('mail'):
                            continue
                        cv.set(x, y, tone(vc, 0.5 if y == y0 else -0.45 if x in (0, cv.W - 1) else 0))
            for y in range(y0, y1):
                R.shade(3, y, -0.5); L.shade(4, y, -0.5)
            if y1 < 24:
                for x in range(16):
                    F.shade(x, y1, -0.6); B.shade(x, y1, -0.6)
                for x in range(8):
                    R.shade(x, y1, -0.6); L.shade(x, y1, -0.6)
        elif kind == 'bodice':
            y0, y1 = v.get('top', 4), v.get('bottom', waist if waist else 18)
            for y in range(y0, y1):
                inset = 1 if y >= y1 - 4 else 0
                for x in range(2 + inset, 14 - inset):
                    F.set(x, y, tone(vc, 0.6 if y == y0 else -0.5 if x in (2 + inset, 13 - inset) else 0))
            lc = v.get('laces', (226, 214, 180))
            for y in range(y0 + 1, y1 - 1):
                F.set(7, y, tone(vc, -1.2)); F.set(8, y, tone(vc, -1.0))
                if y % 2 == 0:
                    F.set(7, y, lc); F.set(8, y + 1, lc) if y + 1 < y1 - 1 else None
            for x in range(2, 14):
                F.shade(x, y1, -0.6)
            for cv in (B, R, L):
                for y in range(y0, y1):
                    for x in range(cv.W):
                        cv.set(x, y, tone(vc, 0.6 if y == y0 else 0))
            if trim:
                for x in range(2, 14):
                    F.set(x, y0, trim)
                for x in range(16):
                    B.set(x, y0, trim)
                for x in range(8):
                    R.set(x, y0, trim); L.set(x, y0, trim)
        elif kind == 'doublet':
            # closed, high-collared, buttoned down the front, slashed to show the lining, its hem dipping to a point
            lin = v.get('lining', GOLD)
            for cv in (F, B, R, L):
                for y in range(0, 24):
                    for x in range(cv.W):
                        if cv is F and (x, y) in opening:
                            continue
                        cv.set(x, y, tone(vc, 0.5 if y == 0 else -0.45 if x in (0, cv.W - 1) else 0))
            for x0 in (3, 11):
                for y in range(4, 15):
                    if y % 5 != 4:
                        F.set(x0, y, tone(lin, 0.2 if y % 5 == 0 else 0)); F.shade(x0 + 1, y, -0.6)
            for y in range(4, 15):
                if y % 5 != 4:
                    B.set(4, y, tone(lin, 0)); B.set(11, y, tone(lin, 0))
            bcol = v.get('buttons', GOLD)
            for y in range(2, 20, 3):
                F.set(7, y, tone(bcol, 1.0)); F.set(8, y, tone(bcol, 0.0)); F.set(8, y + 1, tone(bcol, -1.2))
            for y in range(1, 21):
                F.shade(7, y, -0.15)
            trim = v.get('trim', GOLD)
            pts = [(x, 21 + min(x, 15 - x) // 3) for x in range(16)]
            for x, y in pts:
                F.set(x, y, trim)
                for yy in range(y + 1, 24):
                    F.set(x, yy, tone(t.get('below', tone(vc, -0.6)), 0))
            for x in range(16):
                B.set(x, 21, trim)
            for x in range(8):
                R.set(x, 21, trim); L.set(x, 21, trim)
    tb = t.get('tabard')
    if tb:
        tc, trim = tb['col'], tb.get('trim', GOLD)
        for cv, x0, x1 in ((F, 2, 13), (B, 2, 13)):
            for y in range(0, 24):
                for x in range(x0, x1 + 1):
                    if cv is F and (x, y) in opening:
                        continue
                    k = 0.5 if y == 0 else 0
                    cv.set(x, y, tone(tc, k))
                cv.set(x0, y, trim); cv.set(x1, y, tone(trim, -0.5))
                cv.shade(x0 + 1, y, -0.4)
            # trim round the neck
            for x in range(4, 12):
                if (x, 2) not in opening:
                    cv.set(x, 2 if cv is F else 1, tone(trim, -0.2)) if cv is F else cv.set(x, 1, tone(trim, -0.2))
        if tb.get('emblem'):
            # the city's tower in gold on the chest: battlements, a gate, a window
            pal = {'#': rgba(trim), 'h': tone(trim, 1.1), 'd': tone(trim, -1.0), 'w': tone(tc, -1.4)}
            F.stamp(5, 5, ["h.h.h.", "######", "##hh##", "##ww##", "######", "##hh##", "#d##d#", "##ww##", "##ww##", "dddddd"], pal)
    bib = t.get('bib')
    if bib:
        bc = bib['col']
        x0, x1, y0 = bib.get('x0', 3), bib.get('x1', 12), bib.get('top', 4)
        for y in range(y0, 24):
            for x in range(x0, x1 + 1):
                if (x, y) in opening:
                    continue
                k = 0.6 if y == y0 else -0.4 if x in (x0, x1) else 0
                F.set(x, y, tone(bc, k))
        sc = bib.get('strap', tone(bc, -0.6))
        for y in range(0, y0):
            F.set(x0, y, tone(sc, 0.4 if y == 0 else 0)); F.set(x1, y, tone(sc, 0.3 if y == 0 else -0.2))
            B.set(15 - x1, y, sc); B.set(15 - x0, y, sc)
        for y in range(0, 12):
            B.set(15 - x1 + y // 2, y, sc) if False else None
        if bib.get('stitch', True):
            stitches(F, [(x0 + 1, y) for y in range(y0 + 1, 24)], 0.7)
            stitches(F, [(x1 - 1, y) for y in range(y0 + 1, 24)], 0.7)
            stitches(F, [(x, y0 + 1) for x in range(x0 + 1, x1)], 0.7)
        if bib.get('pocket'):
            pc = tone(bc, -0.4)
            for y in range(13, 18):
                for x in range(x0 + 2, x0 + 6):
                    F.set(x, y, tone(pc, 0.5 if y == 13 else 0))
            for x in range(x0 + 2, x0 + 6):
                F.shade(x, 18, -0.6)
        # the ties round the waist and the bow at the back
        tie_y = bib.get('tie', belt_y)
        for cv in (R, L):
            for x in range(cv.W):
                cv.set(x, tie_y, tone(sc, 0.3)); cv.set(x, tie_y + 1, tone(sc, -0.5))
        for x in range(16):
            B.set(x, tie_y, tone(sc, 0.3)); B.set(x, tie_y + 1, tone(sc, -0.5))
        B.stamp(5, tie_y - 2, ["aa..aa", "abaaba", "aabbaa", "abaaba", "aa..aa", ".a..a.", "a....a"],
                {'a': tone(sc, 0.2), 'b': tone(sc, -1.0)})
    sh = t.get('shawl')
    if sh:
        # a knitted shawl over the shoulders, its ends crossed and knotted on the chest, its point down the back
        sc = sh['col']

        def knit(x, y):
            return 0.12 - (0.38 if (x + y // 2) % 3 == 0 else 0)
        for y in range(0, 14):
            for x in range(16):
                if y < 3:
                    inside = True
                elif y < 9:
                    t0 = (y - 3) / 5.0
                    inside = x <= 5 + 2 * t0 or x >= 10 - 2 * t0
                    inside = inside and (x >= round(3 * t0) - 1 and x <= 15 - round(3 * t0) + 1)
                else:
                    inside = 5 <= x <= 7 or 8 <= x <= 10
                if not inside or (x, y) in opening and y < 2:
                    continue
                k = knit(x, y) + (0.5 if y == 0 else 0)
                if y >= 3 and (x in (0, 15) or (y < 9 and (x == int(5 + 2 * (y - 3) / 5.0) or x == int(10 - 2 * (y - 3) / 5.0)))):
                    k -= 0.45
                F.set(x, y, tone(sc, k))
        fr = sh.get('fringe', tone(sc, 0.7))
        for x in range(5, 11):
            if x != 7 and x != 8:
                F.set(x, 13, fr if x % 2 else tone(fr, -0.9))
        F.stamp(6, 7, ["abba", "bccb", "bccb", ".bb."], {'a': tone(sc, 0.6), 'b': tone(sc, -0.1), 'c': tone(sc, -0.9)})
        for y in range(0, 16):
            for x in range(16):
                if abs(x - 7.5) <= 8 - y * 0.5:
                    B.set(x, y, tone(sc, (0.5 if y == 0 else 0) + knit(x, y) - (0.3 if abs(x - 7.5) > 7 - y * 0.5 else 0)))
        for y in range(0, 9):
            for x in range(8):
                R.set(x, y, tone(sc, knit(x, y) - 0.2)); L.set(x, y, tone(sc, knit(x, y) - 0.2))
        for x in range(16):
            yb = int(16 - abs(x - 7.5) * 2)
            if 0 <= yb < 24:
                B.set(x, yb, fr if x % 2 == 0 else tone(fr, -0.9))
    sash = t.get('sash')
    if sash:
        sc = sash['col']
        y0 = sash.get('y', belt_y - 1)
        for y in range(y0, y0 + 4):
            k = (0.7, 0.1, -0.3, -0.9)[y - y0]
            for cv in (F, B, R, L):
                for x in range(cv.W):
                    cv.set(x, y, tone(sc, k + (0.3 if (x + y) % 5 == 0 and y in (y0 + 1, y0 + 2) else 0)))
        for x in range(16):
            F.shade(x, y0 + 4, -0.6)
        # the knot on the left hip
        L.stamp(3, y0, ["abba", "bccb", "abba", ".bb."], {'a': tone(sc, 0.8), 'b': rgba(sc), 'c': tone(sc, -1.2)})
    strap = t.get('strap')
    if strap:
        # across the chest from the right shoulder to the left hip, and down the back the other way
        for y in range(0, 20):
            x = int(round(1 + y * 12.0 / 19))
            F.set(x, y, tone(strap, 0.6)); F.set(x + 1, y, rgba(strap)); F.set(x + 2, y, tone(strap, -0.8))
            bx = 14 - int(round(y * 12.0 / 19))
            B.set(bx, y, tone(strap, 0.4)); B.set(bx - 1, y, rgba(strap)); B.set(bx - 2, y, tone(strap, -0.8))
        for x in range(0, 4):
            faces['top'].set(x, 0, tone(strap, 0.5)); faces['top'].set(x, 1, strap)
        for y in range(16, 22):
            L.set(3, y, strap); L.set(4, y, tone(strap, -0.7))
    if t.get('stole'):
        sc = t['stole']
        for y in range(0, 24):
            for x in (4, 5, 10, 11):
                if (x, y) in opening:
                    continue
                F.set(x, y, tone(sc, (0.5 if x in (4, 10) else -0.3) + (0.4 if y == 0 else 0)))
        for x in range(2, 14):
            B.set(x, 0, tone(sc, 0.4)); B.set(x, 1, tone(sc, -0.2))
        for x in range(0, 8):
            faces['top'].set(x + 3, 1, sc) if False else None
        for y in (6, 7):
            for x in (4, 5, 10, 11):
                F.set(x, y, GOLD_L if y == 6 else GOLD)
    if t.get('robe_trim'):
        gc = t['robe_trim']
        for (x, y) in list(opening):
            for dx, dy in ((1, 0), (-1, 0), (0, 1)):
                q = (x + dx, y + dy)
                if q not in opening and 0 <= q[0] < 16:
                    F.set(q[0], q[1], gc)
        for y in range(3, 24):
            if not t.get('stole'):
                F.set(7, y, tone(gc, 0.3)); F.set(8, y, tone(gc, -0.5))
    if t.get('smocking'):
        # the smock's gathered chest, honeycomb-stitched
        sc = t['smocking']
        for cv in (F, B):
            for y0 in (4, 7, 10):
                for x in range(2, 14):
                    yy = y0 + (x % 2)
                    cv.set(x, yy, tone(sc, 0.3 if x % 2 else -0.1))
                    cv.shade(x, yy + 1, -0.45)
            for x in (3, 6, 9, 12):
                for y in range(13, 24):
                    cv.shade(x, y, -0.5); cv.shade(x - 1, y, 0.25)
    if t.get('shine'):
        # oiled cloth: bright streaks where it creases
        for cv, xs in ((F, (2, 13)), (B, (3, 12)), (R, (2,)), (L, (5,))):
            for x0 in xs:
                for y in range(2, 22):
                    if (y + x0) % 9 < 6:
                        cv.shade(x0, y, 0.75)
    belt(F, 'front'); belt(B, 'back'); belt(R, 'right'); belt(L, 'left')
    nc = t.get('neckerchief')
    if nc:
        # knotted at the throat, its point hanging down the back
        for x in range(4, 12):
            F.set(x, 0, tone(nc, 0.6)); F.set(x, 1, tone(nc, -0.3))
        F.stamp(6, 1, ["abba", "bccb", ".bc.", ".bc.", "..c."], {'a': tone(nc, 0.8), 'b': rgba(nc), 'c': tone(nc, -1.0)})
        for y in range(0, 6):
            for x in range(4 + y, 12 - y):
                B.set(x, y, tone(nc, (0.5 if y == 0 else 0) - (0.6 if x in (4 + y, 11 - y) else 0)))
        T = faces['top']
        for x in range(4, 12):
            T.set(x, 1, tone(nc, 0.3)); T.set(x, 6, tone(nc, 0.5))
        for y in range(1, 7):
            T.set(4, y, tone(nc, 0.2)); T.set(11, y, tone(nc, 0.2))
    if t.get('cord'):
        cc = t['cord']
        for cv in (F, B, R, L):
            for x in range(cv.W):
                cv.set(x, belt_y, tone(cc, 0.5 if x % 2 else -0.3))
        F.stamp(9, belt_y + 1, ["a.", "a.", "b.", "aa", "bb"], {'a': rgba(cc), 'b': tone(cc, -0.8)})
    nl = t.get('necklace')
    if nl:
        kind = nl[0]
        pts = [(4, 1), (5, 2), (5, 3), (6, 4), (7, 5), (8, 5), (9, 4), (10, 3), (10, 2), (11, 1)]
        for i, (x, y) in enumerate(pts):
            if kind == 'pearls':
                F.set(x, y, (246, 240, 228) if i % 2 == 0 else (204, 196, 186))
            else:
                F.set(x, y, GOLD_L if i % 2 == 0 else GOLD)
        if len(nl) > 1:
            g = nl[1]
            F.set(7, 6, GOLD_L); F.set(8, 6, tone(GOLD, -0.5))
            F.set(7, 7, tone(g, 1.4)); F.set(8, 7, rgba(g))
            F.set(7, 8, rgba(g)); F.set(8, 8, tone(g, -1.3))
    if t.get('buttons'):
        bx = t.get('button_x', 7)
        for y in range(3, 17, 4):
            F.set(bx, y, tone(t['buttons'], 0.9)); F.set(bx, y + 1, tone(t['buttons'], -0.9))
    if t.get('laces') and nk != 'slit':
        for y in range(1, 6):
            F.set(7 if y % 2 else 8, y, t['laces'])
    if t.get('folds', True):
        for x, k in ((4, -0.6), (5, 0.35), (11, -0.6), (12, 0.35)):
            for y in range(belt_y - 3, belt_y):
                F.shade(x, y, k * (1 if y > belt_y - 3 else 0.5))
        for x, k in ((3, -0.6), (4, 0.3), (8, -0.5), (9, 0.3), (12, -0.6)):
            for y in range(belt_y + 3, min(24, belt_y + 6)):
                F.shade(x, y, k)
    if t.get('hem'):
        hc = t['hem']
        for cv in (F, B, R, L):
            for x in range(cv.W):
                cv.set(x, 23, hc); cv.set(x, 22, tone(hc, 0.5))
    if t.get('soot'):
        smudge(F, rng, SOOT, 3, (2, 4, 14, 22), size=(1, 2), t=(0.12, 0.25))
        smudge(B, rng, SOOT, 1, (2, 4, 14, 22), size=(1, 2), t=(0.08, 0.16))
    if t.get('flour'):
        smudge(F, rng, FLOUR, 3, (3, 6, 13, 23), size=(1, 2), t=(0.15, 0.3))
    if t.get('stains'):
        smudge(F, rng, t['stains'], 4, (4, 8, 12, 23), size=(1, 2), t=(0.25, 0.45))
    if t.get('extra'):
        t['extra'](faces, sk)
    return lambda face, w, hh: faces[face]


# ================================================================ arms and hands

SLEEVE_END = {'long': 20, 'three': 15, 'rolled': 8, 'short': 6, 'cap': 3, 'none': 0}


def arm_painter(a, h, side):
    """An arm's faces: sleeve (long, three-quarter, rolled, short or none), cuff, bare forearm, hand; or mail,
    bracers and gloves. [side] 'r' or 'l'."""
    sk = skin_ramp(h['skin'])
    seed = h['seed'] + (200 if side == 'r' else 300)
    rng = random.Random(seed)
    col = a.get('col_l', a.get('col')) if side == 'l' else a.get('col')
    length = a.get('len', 'long')
    se = SLEEVE_END[length]
    outer, inner = ('right', 'left') if side == 'r' else ('left', 'right')
    faces = {}
    for face in ('front', 'back', 'right', 'left'):
        cv = Canvas(8, 24, sk['s'])
        # the bare arm: lit down its outer side, the inside in shade, the wrist a touch narrower
        for y in range(24):
            cv.shade(0, y, -0.4); cv.shade(7, y, -0.4)
        if face == inner:
            cv.shade_rect(0, 0, 8, 24, -0.35)
        if face == outer:
            cv.shade_rect(2, se + 1, 6, se + 5, 0.25)
        if face in (outer, 'front'):
            cv.shade_rect(2, 0, 3, 24, 0.15)
        cv.shade_rect(0, 18, 8, 20, -0.15)
        # the hand
        cv.shade_rect(0, 20, 8, 21, -0.3)
        if face == outer:
            for x in (1, 3, 5):
                cv.shade(x, 21, -0.6); cv.shade(x + 1, 21, 0.3)
            for x in (2, 4):
                cv.shade(x, 22, -0.5); cv.shade(x, 23, -0.6)
        elif face == 'front':
            tx = 4 if side == 'r' else 3
            for y in (20, 21, 22):
                cv.shade(tx, y, -0.9)
            cv.shade(tx + (1 if side == 'r' else -1), 20, 0.4)
        elif face == inner:
            for x in range(1, 7):
                cv.shade(x, 21, -0.5)
                cv.shade(x, 23, -0.6)
        cv.shade_rect(0, 23, 8, 24, -0.3)
        if a.get('hairy') and face in (outer, 'back'):
            for x, y in ((2, 13), (4, 15), (3, 17), (5, 14)):
                if y >= se + 1:
                    cv.tint(x, y, a['hairy'], 0.45)
        if a.get('soot'):
            for y in range(max(se, 8), 24):
                for x in range(8):
                    cv.tint(x, y, SOOT, 0.04 + (y - 8) * 0.017)
            smudge(cv, rng, SOOT, 1, (1, 15, 7, 22), size=(1,), t=(0.18, 0.28))
        if a.get('flour'):
            for y in range(max(se, 8), 24):
                for x in range(8):
                    cv.tint(x, y, FLOUR, 0.03 + (y - 8) * 0.016)
            smudge(cv, rng, FLOUR, 1, (1, 16, 7, 23), size=(1,), t=(0.2, 0.3))
        # the sleeve
        if se and col:
            if a.get('mail'):
                m = np.zeros((24, 8), bool)
                m[:se] = True
                mail(cv, m, base=a.get('mail_col', STEEL), lit=0.2)
            else:
                for y in range(se):
                    for x in range(8):
                        k = 0.5 if y == 0 else 0.25 if y == 1 else 0
                        if x in (0, 7):
                            k -= 0.4
                        if face == inner:
                            k -= 0.35
                        cv.set(x, y, tone(col, k))
                pat = a.get('pattern')
                if pat:
                    kind, c2 = pat[0], pat[1]
                    for y in range(se):
                        for x in range(8):
                            if kind == 'stripes' and (y // pat[2]) % 2 == 1:
                                cv.set(x, y, tone(c2, cv.get(x, y) and (0.4 if y == 0 else 0)))
                            if kind == 'harlequin':
                                u, v = (x + y + 2) // 4, (x - y + 64) // 4
                                if (u + v) % 2:
                                    cv.set(x, y, tone(c2, -0.35 if face == inner else 0))
                if se > 12:
                    # the elbow: a crease inside, a fold in front
                    if face == inner:
                        for x in range(2, 6):
                            cv.shade(x, 11, -0.7)
                        cv.shade(3, 10, 0.3); cv.shade(4, 12, 0.3)
                    if face == 'front':
                        cv.shade(2, 10, -0.6); cv.shade(3, 11, -0.6); cv.shade(4, 10, 0.3)
                    if face == 'back':
                        cv.shade(4, 9, 0.4); cv.shade(3, 10, -0.4)
                if face == 'back':
                    for y in range(2, se - 1):
                        cv.shade(3, y, -0.25)
            cuff = a.get('cuff')
            if length == 'rolled':
                rc = a.get('roll_col', col)
                cv.shade_rect(0, se - 1, 8, se, -0.5)
                for x in range(8):
                    cv.set(x, se, tone(rc, -0.6)); cv.set(x, se + 1, tone(rc, 0.6)); cv.set(x, se + 2, tone(rc, 0.2))
                    cv.set(x, se + 3, tone(rc, -0.3)); cv.set(x, se + 4, tone(rc, -1.0))
                cv.shade_rect(0, se + 5, 8, se + 6, -0.5)
            elif cuff:
                for x in range(8):
                    cv.set(x, se - 2, tone(cuff, 0.6)); cv.set(x, se - 1, tone(cuff, -0.4))
                cv.shade_rect(0, se, 8, se + 1, -0.5)
            elif length != 'none':
                cv.shade_rect(0, se - 1, 8, se, -0.6)
                cv.shade_rect(0, se, 8, se + 1, -0.45)
        rf = a.get('ruffle')
        if rf:
            for x in range(8):
                cv.set(x, se, tone(rf, 0.5)); cv.set(x, se + 1, rgba(rf))
                if x % 2 == 0:
                    cv.set(x, se + 2, tone(rf, -0.6))
        br = a.get('bracer')
        if br:
            for y in range(13, 19):
                for x in range(8):
                    cv.set(x, y, tone(br, 0.6 if y == 13 else -0.6 if y == 18 else -0.4 if x in (0, 7) else 0))
            if face == outer:
                for y in (14, 16):
                    for x in range(1, 7):
                        cv.set(x, y, tone(br, -1.0))
                    cv.set(3, y, (190, 180, 150)); cv.set(4, y, (150, 140, 110))
        gl = a.get('glove')
        if gl:
            g0 = a.get('glove_from', 19)
            for y in range(g0, 24):
                for x in range(8):
                    k = 0.6 if y == g0 else -0.5 if y == g0 + 1 else -0.35 if x in (0, 7) else 0
                    if face == inner:
                        k -= 0.3
                    cv.set(x, y, tone(gl, k))
            if face == outer:
                for x in (1, 3, 5):
                    cv.shade(x, 21, 0.6); cv.shade(x + 1, 22, -0.6)
            if face == 'front':
                tx = 4 if side == 'r' else 3
                for y in (20, 21, 22):
                    cv.shade(tx, y, -0.9)
        if a.get('ring') and face == outer:
            cv.set(3, 22, GOLD_L); cv.set(4, 22, GOLD)
        faces[face] = cv
    top = Canvas(8, 8, tone(col, 0.5) if (se and col) else sk['s'])
    if a.get('mail') and se:
        mail(top, base=a.get('mail_col', STEEL), lit=0.6)
    faces['top'] = top
    gl = a.get('glove')
    bot = Canvas(8, 8, tone(gl, -0.6) if gl else sk['sh'])
    for x in range(1, 7):
        bot.shade(x, 2, -0.6); bot.shade(x, 5, -0.6)
    for y in range(8):
        bot.shade(0, y, -0.4); bot.shade(7, y, -0.4)
    faces['bottom'] = bot
    return lambda face, w, hh: faces[face]


# ================================================================ legs and feet

def leg_painter(l, h, side):
    """A leg's faces: trousers, hose, breeches or shorts; boots or shoes, tall or short, muddy or polished."""
    sk = skin_ramp(h['skin'])
    seed = h['seed'] + (400 if side == 'r' else 500)
    rng = random.Random(seed)
    kind = l.get('kind', 'trousers')
    col = l.get('col_l', l.get('col')) if side == 'l' else l.get('col')
    bt = dict(l.get('boots') or {})
    bcol = bt.get('col', (82, 58, 40))
    btype = bt.get('kind', 'boot')
    b0 = bt.get('top', {'boot': 14, 'tall': 10, 'shoe': 20, 'slipper': 21}[btype])
    outer, inner = ('right', 'left') if side == 'r' else ('left', 'right')
    faces = {}
    for face in ('front', 'back', 'right', 'left'):
        cv = Canvas(8, 24)
        for y in range(24):
            for x in range(8):
                k = -0.4 if x in (0, 7) else 0
                if face == inner:
                    k -= 0.35
                if y == 0:
                    k -= 0.5
                cv.set(x, y, tone(col, k))
        if face == 'front':
            for y in range(1, b0):
                cv.shade(2, y, 0.18)
                cv.shade(6, y, -0.12)
        if kind == 'trousers':
            if face == 'front':
                for y in range(2, b0 - 1):
                    cv.shade(3, y, -0.35); cv.shade(4, y, 0.2)
                cv.shade_rect(1, 10, 7, 12, 0.3)
                for x in (1, 2, 5, 6):
                    cv.shade(x, 12, -0.4)
            if face == outer:
                for y in range(1, b0):
                    cv.shade(4, y, -0.5)
                stitches(cv, [(5, y) for y in range(2, b0)], 0.5)
            if b0 < 24:
                for x in range(8):
                    cv.shade(x, b0 - 1, 0.35); cv.shade(x, b0 - 2, -0.3)
        elif kind in ('hose', 'breeches'):
            if face == 'front':
                cv.shade_rect(2, 9, 6, 12, 0.35)
                for y in range(1, 24):
                    cv.shade(2, y, 0.2)
            if kind == 'breeches':
                stock = l.get('stock', (232, 228, 216))
                for y in range(11, 24):
                    for x in range(8):
                        k = -0.4 if x in (0, 7) else (-0.35 if face == inner else 0)
                        cv.set(x, y, tone(stock, k))
                for x in range(8):
                    cv.set(x, 10, tone(l.get('band', col), 0.5)); cv.set(x, 11, tone(l.get('band', col), -0.4))
                if face == outer and l.get('garter'):
                    cv.stamp(2, 10, ["ab", "bb", ".b", ".a"], {'a': tone(l['garter'], 0.6), 'b': rgba(l['garter'])})
        elif kind == 'shorts':
            for y in range(9, 24):
                for x in range(8):
                    cv.set(x, y, tone(sk['s'], -0.4 if x in (0, 7) else (-0.3 if face == inner else 0)))
            for x in range(8):
                cv.set(x, 8, tone(col, -0.6))
                cv.shade(x, 9, -0.5)
            if face == 'front':
                cv.shade(3, 11, 0.4); cv.shade(4, 11, 0.4); cv.shade(3, 12, -0.4); cv.shade(4, 13, -0.3)
            sc = l.get('stock')
            if sc:
                for y in range(15, 24):
                    for x in range(8):
                        cv.set(x, y, tone(sc, (0.6 if y == 15 else 0) + (-0.4 if x in (0, 7) else 0)))
                for x in range(8):
                    cv.set(x, 16, tone(sc, -0.6))
        if l.get('patch') and face == 'front' and side == 'r':
            pc = l['patch']
            for y in range(9, 13):
                for x in range(1, 5):
                    cv.set(x, y, tone(pc, 0.4 if y == 9 else 0))
            stitches(cv, [(x, 8) for x in range(1, 5)] + [(5, y) for y in range(9, 13)] + [(x, 13) for x in range(1, 5)], 1.0)
        tun = l.get('tunic')
        if tun:
            tc, tr = tun['col'], tun['rows']
            for y in range(0, tr):
                for x in range(8):
                    k = (-0.4 if x in (0, 7) else 0) + (-0.35 if face == inner else 0)
                    if y == tr - 1:
                        k -= 0.4
                    cv.set(x, y, tone(tc, k))
            if tun.get('hem'):
                for x in range(8):
                    cv.set(x, tr - 1, tun['hem'])
            for x in range(8):
                cv.shade(x, tr, -0.6)
            if face == 'front':
                for y in range(1, tr - 1):
                    cv.shade(2 if side == 'r' else 5, y, -0.5)
        # the boot or shoe
        for y in range(b0, 24):
            for x in range(8):
                k = (-0.45 if x in (0, 7) else 0) + (-0.35 if face == inner else 0)
                cv.set(x, y, tone(bcol, k))
        if btype in ('boot', 'tall'):
            cuff = bt.get('cuff_col', bcol)
            for x in range(8):
                cv.set(x, b0, tone(cuff, 0.8)); cv.set(x, b0 + 1, tone(cuff, 0.2))
                cv.shade(x, b0 + 2, -0.6)
            if face == 'front':
                for y in range(b0 + 3, 20):
                    cv.shade(2, y, 0.55)
                cv.shade(2, b0 + 5, 0.4)
                for x in (2, 5):
                    cv.shade(x, 19, -0.5)
            if bt.get('laces') and face == 'front':
                lc = bt['laces']
                for y in range(b0 + 1, 20, 2):
                    cv.set(3, y, lc); cv.set(4, y + 1, lc) if y + 1 < 20 else None
                    cv.set(4, y, tone(lc, -0.8))
            if bt.get('strap') and face == outer:
                for x in range(8):
                    cv.set(x, 18, tone(bt['strap'], -0.6))
                cv.set(3, 18, (196, 184, 150))
        else:
            for x in range(8):
                cv.set(x, b0, tone(bcol, 0.7))
            if bt.get('buckle') and face == 'front':
                bk = bt['buckle']
                cv.stamp(2, b0, ["abba", "b..b"], {'a': tone(bk, 1.0), 'b': rgba(bk)})
        if face == 'front':
            cv.shade_rect(1, 21, 7, 23, 0.35)
            cv.shade(3, 21, 0.4)
        if face == 'back':
            cv.shade_rect(0, 21, 8, 24, -0.6)
        for x in range(8):
            cv.set(x, 23, tone(bt.get('sole', bcol), -2.0))
        if bt.get('mud'):
            m = bt['mud'] if isinstance(bt['mud'], tuple) else MUD
            for y in range(18, 23):
                for x in range(8):
                    cv.tint(x, y, m, 0.12 + (y - 18) * 0.1)
            smudge(cv, rng, m, 3, (0, 15, 8, 23), size=(1, 2), t=(0.4, 0.7))
            if kind == 'trousers' and b0 > 12:
                smudge(cv, rng, m, 1, (0, b0 - 4, 8, b0), size=(1,), t=(0.3, 0.5))
        faces[face] = cv
    top = Canvas(8, 8, tone(col, -0.6))
    faces['top'] = top
    sole = Canvas(8, 8, tone(bt.get('sole', bcol), -1.8))
    sole.shade_rect(1, 0, 7, 3, 0.5)
    faces['bottom'] = sole
    return lambda face, w, hh: faces[face]


# ================================================================ skirts, robes, coats, smocks: the shell on the legs

def skirt_painter(s, h):
    """Both halves of the shell (skirt_r rides the right leg, skirt_l the left). The garment is painted whole -- a
    20-texel front and back, 12-texel sides -- then cut down the middle; below its hem the shell is clear."""
    seed = h['seed'] + 600
    rng = random.Random(seed)
    col = s['col']
    n = s.get('len', 24)
    kind = s.get('kind', 'dress')
    front, back = Canvas(20, 24), Canvas(20, 24)
    side, inner = Canvas(12, 24), Canvas(12, 24)
    for cv, face in ((front, 'front'), (back, 'back'), (side, 'right'), (inner, 'inner')):
        drape(cv, col, face, 0.3, -0.4, -0.35)
        if face == 'inner':
            cv.shade_rect(0, 0, cv.W, cv.H, -1.4)
    # gathered at the waist
    for cv in (front, back, side):
        for x in range(cv.W):
            for y in range(0, 3):
                cv.shade(x, y, (0.35 if x % 3 == 0 else -0.35 if x % 3 == 1 else 0) * (1 - y / 3))
    pat = s.get('pattern')
    if pat:
        kind_p, c2 = pat[0], pat[1]
        for cv in (front, back, side):
            for y in range(cv.H):
                for x in range(cv.W):
                    use = False
                    if kind_p == 'panels':
                        use = (x // pat[2]) % 2 == 1
                    elif kind_p == 'stripes':
                        use = (y // pat[2]) % 2 == 1
                    elif kind_p == 'band':
                        use = pat[2] <= y < pat[3]
                    if use:
                        cv.set(x, y, mix(c2, cv.get(x, y), 0.0) if kind_p != 'band' else tone(c2, -0.2 if y == pat[3] - 1 else 0.3 if y == pat[2] else 0))
                        if kind_p != 'band' and y == 0:
                            cv.shade(x, y, 0.3)
    # the skirt is round: its flanks turn from the light
    for cv in (front, back):
        for y in range(n):
            for x in range(cv.W):
                d = abs(x - (cv.W - 1) / 2) / (cv.W / 2)
                cv.shade(x, y, -0.55 * d * d - 0.12 * x / cv.W)
    # folds falling from the waist, opening out toward the hem: valleys in shade with a lit lip, a few ridges
    if kind not in ('trunks',) and s.get('folds', True):
        for cv, count in ((front, 4), (back, 4), (side, 2)):
            folds = []
            for i in range(count):
                x = int(round((i + 0.5) * cv.W / count + rng.choice((-1, 0, 0, 1))))
                folds.append((x, rng.randrange(2, 7), rng.choice(('valley', 'valley', 'ridge'))))
            for fx, y0, fk in folds:
                for y in range(y0, n):
                    t = (y - y0) / max(1, n - y0)
                    x = fx + int(round(t * 1.4 * (1 if fx >= cv.W / 2 else -1)))
                    if fk == 'valley':
                        cv.shade(x, y, -0.45 - 0.5 * t)
                        cv.shade(x - 1, y, 0.3 + 0.1 * t)
                        if t > 0.45:
                            cv.shade(x + 1, y, -0.3)
                    else:
                        cv.shade(x, y, 0.45)
                        cv.shade(x + 1, y, -0.35 - 0.35 * t)
    if kind == 'trunks':
        # paned trunk hose: bands of the cloth over a contrasting lining, puffed
        lin = s.get('lining', GOLD)
        for cv in (front, back, side):
            for y in range(0, n):
                for x in range(cv.W):
                    pane = x % 3 == 2
                    c = lin if pane else col
                    k = 0.4 if 2 <= y <= n - 4 else -0.5
                    if pane:
                        k = 0.2 if 2 <= y <= n - 4 else -0.6
                    if (x % 3 == 1) and not pane:
                        k -= 0.4
                    cv.set(x, y, tone(c, k))
            for x in range(cv.W):
                cv.set(x, n - 1, tone(s.get('hem', col), -0.6))
                cv.set(x, n - 2, tone(s.get('hem', col), 0.3))
    # the front panel of a gown (petticoat), an apron, a coat's opening, a stole's ends
    pn = s.get('panel')
    if pn:
        pc = pn['col']
        for y in range(0, n):
            half = 1 + y // 3
            for x in range(10 - half, 10 + half):
                if 0 <= x < 20:
                    k = 0.0
                    if (x + y) % 4 == 0 and pn.get('brocade'):
                        front.set(x, y, tone(pn['brocade'], 0.2))
                        continue
                    front.set(x, y, tone(pc, k + (0.3 if y < 2 else 0)))
            front.set(10 - half - 1, y, pn.get('edge', GOLD)) if 10 - half - 1 >= 0 else None
            front.set(10 + half, y, tone(pn.get('edge', GOLD), -0.5)) if 10 + half < 20 else None
    if kind == 'coat':
        oc = s.get('open', (60, 50, 44))
        for y in range(0, n):
            w = 1 + y // 8
            for x in range(10 - w, 10 + w):
                front.set(x, y, tone(oc, -0.3 if y < 3 else 0))
            front.set(10 - w - 1, y, tone(front.get(10 - w - 1, y), 0.7))
            front.set(10 + w, y, tone(front.get(10 + w, y), -0.6))
        for y in range(n - 7, n):
            back.shade(9, y, -1.0); back.shade(10, y, 0.4)
        if s.get('buttons'):
            for y in range(1, min(n - 2, 12), 4):
                front.set(7, y, tone(s['buttons'], 0.8)); front.set(7, y + 1, tone(s['buttons'], -0.8))
    ap = s.get('apron')
    if ap:
        ac, alen = ap['col'], ap.get('len', n - 3)
        ax0, ax1 = ap.get('x0', 4), ap.get('x1', 15)
        for y in range(0, alen):
            for x in range(ax0, ax1 + 1):
                k = (0.3 if y < 2 else 0) + (-0.45 if x in (ax0, ax1) else 0)
                if x in (ax0 + 3, ax1 - 3) and y > 3:
                    k -= 0.5
                if x in (ax0 + 2, ax1 - 4) and y > 3:
                    k += 0.3
                if y == alen - 1:
                    k -= 0.8
                front.set(x, y, tone(ac, k))
        for x in range(ax0, ax1 + 1):
            front.shade(x, alen, -0.6)
        if ap.get('stitch', True):
            stitches(front, [(x, alen - 2) for x in range(ax0 + 1, ax1)], 0.6)
        if ap.get('dust'):
            smudge(front, rng, (196, 184, 160), 2, (ax0 + 1, 4, ax1, alen), size=(1,), t=(0.15, 0.25))
        if ap.get('stains'):
            smudge(front, rng, ap['stains'], 3, (ax0 + 1, 2, ax1, alen), size=(1, 2), t=(0.2, 0.4))
    if s.get('stole'):
        sc = s['stole']
        sl = s.get('stole_len', 15)
        for x0 in (4, 14):
            for y in range(0, sl):
                front.set(x0, y, tone(sc, 0.4)); front.set(x0 + 1, y, tone(sc, -0.3))
            front.stamp(x0, sl - 5, ["ab", "bb", "ab"], {'a': GOLD_L, 'b': GOLD})
            for y in (sl, sl + 1):
                front.set(x0, y, GOLD if y == sl else tone(GOLD, -0.8)); front.set(x0 + 1, y, tone(GOLD, -0.4) if y == sl else CLEAR)
    if s.get('orphrey'):
        oc = s['orphrey']
        for y in range(0, n):
            front.set(9, y, tone(oc, 0.6)); front.set(10, y, tone(oc, -0.3))
    # the hem: a band of trim or a darker turned edge, stitched
    trim = s.get('trim')
    for cv in (front, back, side):
        if trim:
            for x in range(cv.W):
                cv.set(x, n - 4, tone(trim, 0.8)); cv.set(x, n - 3, rgba(trim)); cv.set(x, n - 2, tone(trim, -0.6))
                if s.get('trim_dots') and x % 3 == 1:
                    cv.set(x, n - 3, s['trim_dots'])
        else:
            for x in range(cv.W):
                cv.shade(x, n - 2, -0.35)
            if kind != 'trunks':
                stitches(cv, [(x, n - 3) for x in range(cv.W)], 0.45)
        for x in range(cv.W):
            cv.shade(x, n - 1, -0.9)
    if s.get('dags'):
        for cv in (front, back, side):
            for x in range(cv.W):
                if x % 3 == 1:
                    cv.set(x, n, tone(cv.get(x, n - 1), 0))
    if s.get('shine'):
        for cv, xs in ((front, (2, 6, 13, 17)), (back, (3, 8, 12, 16)), (side, (2, 7))):
            for x0 in xs:
                for y in range(rng.randrange(2, 5), rng.randrange(n // 2, n - 3)):
                    cv.shade(x0 + 1, y, 0.9)
    if s.get('mud'):
        for cv in (front, back, side):
            smudge(cv, rng, MUD, 3, (0, n - 5, cv.W, n), size=(1, 2), t=(0.3, 0.55))
    if s.get('flour'):
        smudge(front, rng, FLOUR, 3, (2, 2, 18, n - 2), t=(0.2, 0.4))
    if s.get('extra'):
        s['extra'](front, back, side, n)
    # below the hem the shell is open
    for cv in (front, back, side, inner):
        hem_row = n + (1 if s.get('dags') else 0)
        for y in range(hem_row, 24):
            for x in range(cv.W):
                if not (s.get('dags') and y == n and x % 3 == 1):
                    cv.clear(x, y)
        if s.get('dags'):
            for x in range(cv.W):
                if x % 3 != 1:
                    cv.clear(x, n)
    top = Canvas(10, 12, tone(col, 0.25))
    for x in range(10):
        for y in range(12):
            if x % 3 == 0:
                top.shade(x, y, -0.3)
    side_l = side.flip()
    halves = {
        'r': {'front': front.crop(0, 0, 10, 24), 'back': back.crop(10, 0, 10, 24), 'right': side, 'left': inner,
              'top': top, 'bottom': Canvas(10, 12)},
        'l': {'front': front.crop(10, 0, 10, 24), 'back': back.crop(0, 0, 10, 24), 'right': inner.flip(), 'left': side_l,
              'top': top.flip(), 'bottom': Canvas(10, 12)},
    }
    return (lambda face, w, hh: halves['r'][face]), (lambda face, w, hh: halves['l'][face])


# ================================================================ look pieces

def faces_of(fn):
    """A painter from fn(face, W, H) -> Canvas or None."""
    return lambda face, w, hh: fn(face, w, hh)


def round_corners(cv, n=1):
    W, H = cv.W, cv.H
    pts = [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]
    if n > 1:
        pts += [(1, 0), (0, 1), (W - 2, 0), (W - 1, 1), (0, H - 2), (1, H - 1), (W - 1, H - 2), (W - 2, H - 1)]
    for x, y in pts:
        cv.clear(x, y)


# ---- hair pieces

def bun_painter(col, seed, pin=None):
    def fn(face, W, H):
        cv = Canvas(W, H)
        if face == 'back':
            # wound round: a coil of strands, lit from above on the left
            cx, cy = (W - 1) / 2, (H - 1) / 2
            for y in range(H):
                for x in range(W):
                    dx, dy = x - cx, y - cy
                    r = math.hypot(dx, dy) / (W / 2)
                    ph = (r * 2.3 + math.atan2(dy, dx) / (2 * math.pi)) % 1.0
                    k = 0.55 if ph < 0.28 else -0.75 if ph > 0.72 else 0.0
                    k += -0.45 * (dx + dy) / W
                    cv.set(x, y, tone(col, k))
            cv.shade(int(cx), int(cy), -0.6)
        else:
            m = np.ones((H, W), bool)
            paint_hair(cv, m, col, seed + len(face), sheen=(1, 2), edge=False, flow='across' if face != 'top' else 'down')
            if face == 'bottom':
                cv.shade_rect(0, 0, W, H, -0.8)
        if pin and face in ('back', 'left'):
            cv.line(0, H - 2, W - 2, 1, pin)
            cv.set(W - 2, 1, tone(pin, 1.2)); cv.set(W - 1, 0, tone(pin, 0.6))
        round_corners(cv, 2 if min(W, H) >= 8 else 1)
        return cv
    return faces_of(fn)


def braid_painter(col, seed, tie=None, plain=False):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if face in ('top', 'bottom'):
            cv.shade_rect(0, 0, W, H, -0.5 if face == 'top' else -0.2)
            return cv
        for y in range(H):
            for x in range(W):
                if plain:
                    k = (-0.7 if x == 1 else 0.5 if x == 2 else 0) + (0.6 if 1 <= y <= 3 else 0)
                else:
                    lobe = (y // 3) % 2
                    r = y % 3
                    left = x < W // 2
                    if left == (lobe == 0):
                        k = (0.9, 0.3, -0.5)[r]
                    else:
                        k = (-0.9, -0.3, 0.1)[r]
                cv.set(x, y, tone(col, k))
        if tie:
            for x in range(W):
                cv.set(x, 1, tone(tie, 0.6)); cv.set(x, 2, tone(tie, -0.4))
        # the tuft at the end
        for x in range(W):
            if x in (0, W - 1):
                cv.clear(x, H - 1); cv.clear(x, H - 2)
            cv.shade(x, H - 3, -0.6) if not plain else None
        return cv
    return faces_of(fn)


def pigtail_painter(col, seed, tie):
    def fn(face, W, H):
        cv = Canvas(W, H)
        m = np.ones((H, W), bool)
        paint_hair(cv, m, col, seed + len(face), sheen=(2, 4), edge=False)
        if face in ('top', 'bottom'):
            return cv
        for x in range(W):
            cv.set(x, 0, tone(tie, 0.7)); cv.set(x, 1, tone(tie, -0.3))
        cv.clear(0, H - 1); cv.clear(W - 1, H - 1)
        return cv
    return faces_of(fn)


def bow_painter(col):
    def fn(face, W, H):
        cv = Canvas(W, H)
        if face in ('front', 'back'):
            cv.stamp(0, 0, ["aab..baa", "abbccbba", "abbccbba", "aab..baa"],
                     {'a': tone(col, 0.6), 'b': rgba(col), 'c': tone(col, -1.1)})
        else:
            cv.fill(tone(col, -0.5))
        return cv
    return faces_of(fn)


BEARDS = {
    'full': ["................", "....########....", ".###.######.###.", ".####......####.", "################",
             "################", "################", ".##############.", "..############..", "...##########...",
             ".....######.....", "................"],
    'long': ["................", "....########....", ".###.######.###.", ".####......####.", "################",
             "################", "################", "################", ".##############.", "..############..",
             "....########....", "......####......"],
    'short': ["................", "....########....", "..##.######.##..", "..###......###..", ".##############.",
              ".##############.", "..############..", "....########....", "................", "................",
              "................", "................"],
    'goatee': ["................", "....########....", "....#......#....", "................", ".....######.....",
               ".....######.....", "......####......", ".......##.......", "................", "................",
               "................", "................"],
    'chin': ["................", "................", "................", ".##..........##.", ".###........###.",
             ".##############.", "..############..", "...##########...", "................", "................",
             "................", "................"],
}


def beard_painter(col, style, seed, sk, curly=False):
    rows = BEARDS[style]
    front = mask_rows(rows)

    def fn(face, W, H):
        cv = Canvas(W, H)
        if face == 'front':
            paint_hair(cv, front, col, seed, sheen=None, curly=curly, edge=True)
            # the moustache and cheeks catch the light, the beard darkens toward its end
            for y in range(H):
                for x in range(W):
                    if front[y, x]:
                        cv.shade(x, y, 0.3 if y <= 2 else -0.08 * max(0, y - 6))
            if style != 'chin':
                # the mouth set in the beard: a dark line, the lower lip below it
                for x in range(6, 10):
                    cv.set(x, 3, sk['mouth'] if x in (7, 8) else mix(sk['mouth'], col, 0.5))
                if front[4, 7]:
                    cv.set(7, 4, mix(sk['lip'], col, 0.25)); cv.set(8, 4, mix(sk['lip'], col, 0.4))
            return cv
        if face in ('right', 'left'):
            # the beard's thickness shows only where it reaches its outer edge
            edge = front[:, 0] if face == 'right' else front[:, front.shape[1] - 1]
            m = np.zeros((H, W), bool)
            for y in range(min(H, len(edge))):
                m[y, :] = edge[y]
            if m.any():
                paint_hair(cv, m, col, seed + 1, edge=False, curly=curly)
                cv.shade_rect(0, 0, W, H, -0.4)
            return cv
        return cv
    return faces_of(fn)


def knot_painter(col, c2=None):
    def fn(face, W, H):
        cv = Canvas(W, H)
        if face in ('back', 'front'):
            # a twisted knot, its two ends hanging apart below
            cv.stamp(0, 0, [".abbc.", "abbccd", "bacbcd", ".dccd.", ".bd.cd", "abd.bd", "bd..cd", "d...d."],
                     {'a': tone(col, 0.8), 'b': tone(col, 0.1), 'c': tone(col, -0.6), 'd': tone(col, -1.4)})
        elif face in ('right', 'left'):
            cv.fill(tone(col, -0.4))
            cv.clear_rect(0, 4, W, H)
            cv.clear(0, 0)
        elif face == 'top':
            cv.fill(tone(col, 0.5))
            cv.clear(0, 0); cv.clear(W - 1, 0)
        return cv
    return faces_of(fn)


def hair_long_painter(col, seed, length=18, shape='round'):
    def fn(face, W, H):
        cv = Canvas(W, H)
        m = np.zeros((H, W), bool)
        if face in ('back', 'front'):
            for x in range(W):
                d = length
                if shape == 'round':
                    d = length - int(round(abs(x - (W - 1) / 2) ** 2 / 14))
                elif shape == 'v':
                    d = length - int(abs(x - (W - 1) / 2) // 1.5)
                elif shape == 'ragged':
                    d = length - (x * 7 % 3)
                m[:max(0, min(H, d)), x] = True
        elif face in ('right', 'left'):
            m[:max(0, min(H, length - 3)), :] = True
        else:
            m[:, :] = True
        paint_hair(cv, m, col, seed + len(face), sheen=(1, 3) if face in ('back', 'right', 'left') else None,
                   edge=face not in ('top', 'bottom'))
        if face == 'front':
            cv.shade_rect(0, 0, W, H, -0.8)
        return cv
    return faces_of(fn)


# ---- hats

def weave(cv, col, k=0.35, x0=0, y0=0):
    for y in range(cv.H):
        for x in range(cv.W):
            if not cv.opaque(x, y):
                continue
            blk = ((x + x0) // 2 + (y + y0) // 2) % 2
            kk = (k if (y + y0) % 2 == 0 else -k) if blk == 0 else (k if (x + x0) % 2 == 0 else -k)
            cv.a[y, x] = tone(cv.a[y, x], kk)


def straw_painter(part, col, band=None, worn=False):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if part == 'brim':
            if face == 'top':
                weave(cv, col)
                # the crown's shadow round its foot, a lighter ring where the brim bends
                for y in range(H):
                    for x in range(W):
                        d = min(x, y, W - 1 - x, H - 1 - y)
                        if d in (3, 4):
                            cv.shade(x, y, -0.6 if d == 4 else -0.3)
                        if d == 0:
                            cv.shade(x, y, -0.4)
                        if d == 1:
                            cv.shade(x, y, 0.4)
                for x in range(0, W, 5):
                    cv.clear(x, 0); cv.clear(W - 1, x); cv.clear(W - 1 - x, H - 1); cv.clear(0, H - 1 - x)
                if worn:
                    for x, y in ((6, 2), (7, 2), (20, 25), (21, 25), (2, 15)):
                        cv.shade(x, y, -1.0)
            elif face == 'bottom':
                weave(cv, col, 0.25)
                cv.shade_rect(0, 0, W, H, -1.3)
                for x in range(0, W, 5):
                    cv.clear(x, 0); cv.clear(W - 1, x); cv.clear(W - 1 - x, H - 1); cv.clear(0, H - 1 - x)
            else:
                weave(cv, col, 0.3)
                cv.shade_rect(0, 1, W, 2, -0.6)
                for x in range(0, W, 5):
                    cv.clear(x, 0); cv.clear(x, 1)
            return cv
        # the crown
        weave(cv, col, 0.3)
        if face == 'top':
            for y in range(H):
                for x in range(W):
                    d = min(x, y, W - 1 - x, H - 1 - y)
                    cv.shade(x, y, 0.5 if d > 6 else 0.2 if d > 2 else -0.2)
            if worn:
                cv.shade_rect(8, 8, 12, 11, -0.6)
            return cv
        if face == 'bottom':
            cv.shade_rect(0, 0, W, H, -1.4)
            return cv
        for x in range(W):
            cv.shade(x, 0, 0.5); cv.shade(x, 1, 0.2)
        if band:
            for x in range(W):
                cv.set(x, H - 3, tone(band, 0.5)); cv.set(x, H - 2, rgba(band)); cv.set(x, H - 1, tone(band, -0.8))
        else:
            for x in range(W):
                cv.shade(x, H - 1, -0.6)
        for y in range(H):
            cv.shade(0, y, -0.4); cv.shade(W - 1, y, -0.4)
        return cv
    return faces_of(fn)


def felt_painter(part, col, band=None, gem=None, edge=None):
    """Felt and velvet hats: the merchant's wide hat, the noble's plumed hat, the bard's cap."""
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.5, -0.4, -0.4)
        if part == 'brim':
            if face == 'top':
                for y in range(H):
                    for x in range(W):
                        d = min(x, y, W - 1 - x, H - 1 - y)
                        if d == 0:
                            cv.set(x, y, tone(edge, 0.3) if edge else tone(col, 0.6))
                        elif d in (3, 4):
                            cv.shade(x, y, -0.5 if d == 3 else -0.25)
                        elif d == 1:
                            cv.shade(x, y, 0.3)
            elif face == 'bottom':
                cv.shade_rect(0, 0, W, H, -1.2)
                if edge:
                    for y in range(H):
                        for x in range(W):
                            if min(x, y, W - 1 - x, H - 1 - y) == 0:
                                cv.set(x, y, tone(edge, -0.6))
            else:
                cv.fill(tone(edge, 0) if edge else tone(col, 0.3))
                for x in range(W):
                    cv.shade(x, H - 1, -0.7)
            return cv
        if part == 'crown':
            if face == 'top':
                cv.shade_rect(2, 2, W - 2, H - 2, 0.35)
                for x in range(W):
                    cv.shade(x, H // 2, -0.5)
                return cv
            if face == 'bottom':
                cv.shade_rect(0, 0, W, H, -1.4)
                return cv
            if band:
                for x in range(W):
                    cv.set(x, H - 3, tone(band, 0.7)); cv.set(x, H - 2, rgba(band)); cv.set(x, H - 1, tone(band, -0.7))
            if gem and face == 'front':
                gx = W // 2 - 1
                cv.stamp(gx - 1, H - 4, [".gg.", "gabg", "gbcg", ".gg."], {'g': GOLD, 'a': tone(gem, 1.5), 'b': rgba(gem), 'c': tone(gem, -1.2)})
            for y in range(H):
                cv.shade(W // 2, y, 0.25) if face == 'front' else None
            return cv
        return cv
    return faces_of(fn)


def feather_painter(col, quill=None, tip=None, axis='u'):
    """A feather along its length (u or v): barbs in a herringbone, the quill down the middle, the tip coloured."""
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        L = W if axis == 'u' else H
        across = H if axis == 'u' else W
        if L < across:
            cv.fill(tone(col, -0.3))
            return cv
        for i in range(L):
            for j in range(across):
                k = 0.6 if (i + j) % 3 == 0 else -0.4 if (i + j) % 3 == 2 else 0
                if j == across // 2:
                    k = -0.2
                if tip and i >= L - max(2, L // 5):
                    c = tip
                else:
                    c = col
                x, y = (i, j) if axis == 'u' else (j, i)
                cv.set(x, y, tone(c, k))
        if quill:
            for i in range(L):
                x, y = (i, across // 2) if axis == 'u' else (across // 2, i)
                if across >= 3:
                    cv.set(x, y, quill)
        return cv
    return faces_of(fn)


def steel_painter(part, col=STEEL):
    """The watch's kettle helm: a rolled brim, a riveted crown, a ridged dome, a bright streak where the light runs."""
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if part == 'brim':
            if face == 'top':
                for y in range(H):
                    for x in range(W):
                        d = min(x, y, W - 1 - x, H - 1 - y)
                        k = 0.2
                        if d == 0:
                            k = 1.0
                        elif d == 1:
                            k = -0.5
                        elif d in (5, 6):
                            k = -0.6
                        if x + y in (8, 9) and d < 5:
                            k += 0.9
                        cv.set(x, y, tone(col, k))
            elif face == 'bottom':
                cv.fill(tone(col, -1.6))
                for y in range(H):
                    for x in range(W):
                        if min(x, y, W - 1 - x, H - 1 - y) == 0:
                            cv.set(x, y, tone(col, -0.6))
            else:
                cv.fill(tone(col, 0.8))
                for x in range(W):
                    cv.set(x, H - 1, tone(col, -0.6))
            return cv
        if part == 'crown':
            if face in ('top', 'bottom'):
                cv.fill(tone(col, 0.3 if face == 'top' else -1.4))
                return cv
            for y in range(H):
                for x in range(W):
                    k = 0.6 - y * 0.25
                    if x in (0, W - 1):
                        k -= 0.4
                    cv.set(x, y, tone(col, k))
            for x in range(1, W, 4):
                cv.set(x, H - 2, tone(col, 1.3)); cv.set(x, H - 1, tone(col, -0.9))
            if face == 'front':
                for y in range(H):
                    cv.shade(4, y, 0.9); cv.shade(5, y, 0.5)
            return cv
        # the dome
        if face == 'top':
            for y in range(H):
                for x in range(W):
                    d = min(x, y, W - 1 - x, H - 1 - y)
                    cv.set(x, y, tone(col, 0.2 + 0.25 * min(d, 4)))
            for y in range(H):
                cv.set(W // 2, y, tone(col, 1.4)); cv.set(W // 2 - 1, y, tone(col, -0.5))
            cv.set(4, H - 5, tone(col, 2.0)); cv.set(5, H - 5, tone(col, 1.5))
            return cv
        cv.fill(tone(col, 0.7))
        cv.shade_rect(0, H - 1, W, H, -0.6)
        return cv
    return faces_of(fn)


def oilskin_painter(part, col):
    """The sou'wester: oiled canvas, stitched in rings, shining along its creases."""
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.4, -0.4, -0.3)
        if face in ('top',) and part in ('brim', 'crown'):
            for y in range(H):
                for x in range(W):
                    d = min(x, y, W - 1 - x, H - 1 - y)
                    if d % 3 == 1:
                        cv.shade(x, y, -0.6 if (x + y) % 2 else -0.2)
                    if d == 0:
                        cv.shade(x, y, -0.3)
            for i in range(2, min(W, H) - 2):
                cv.shade(i, i, 0.8) if part == 'crown' else None
        elif face == 'bottom':
            cv.shade_rect(0, 0, W, H, -1.2)
        elif part == 'crown':
            for x in range(1, W, 3):
                cv.shade(x, 1, 0.9)
            for x in range(W):
                cv.shade(x, H - 1, -0.6)
        elif part == 'flap' and face in ('back', 'front'):
            for x in range(0, W, 3):
                for y in range(H):
                    cv.shade(x, y, -0.4)
                cv.shade(x + 1, 0, 0.8)
        return cv
    return faces_of(fn)


def knit_painter(col, rib=None, kind='knit'):
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.35, -0.3, -0.35)
        if face == 'top':
            for y in range(H):
                for x in range(W):
                    if kind == 'knit':
                        cx, cy = x - (W - 1) / 2, y - (H - 1) / 2
                        ang = math.atan2(cy, cx)
                        cv.shade(x, y, 0.45 if int((ang + math.pi) * 4) % 2 else -0.3)
                    else:
                        cv.shade(x, y, 0.2)
            cv.shade_rect(W // 2 - 1, H // 2 - 1, W // 2 + 1, H // 2 + 1, -0.8)
            return cv
        if face == 'bottom':
            cv.fill(tone(col, -1.4))
            return cv
        if kind == 'knit':
            for y in range(H):
                for x in range(W):
                    if y >= H - 3:
                        rc = rib or col
                        cv.set(x, y, tone(rc, (0.5 if x % 2 == 0 else -0.5) + (0.3 if y == H - 3 else -0.3 if y == H - 1 else 0)))
                    else:
                        v = (x + (1 if (y // 2) % 2 else 0)) % 2
                        cv.shade(x, y, 0.4 if (v == 0) == (y % 2 == 0) else -0.3)
            for x in range(W):
                cv.shade(x, H - 4, -0.7)
        else:
            for x in range(W):
                cv.shade(x, H - 1, -0.6)
                cv.shade(x, H - 2, 0.4)
            if face == 'front':
                seam(cv, [(W // 2, y) for y in range(H - 2)], -0.6)
        return cv
    return faces_of(fn)


def baker_cap_painter(part, col=(246, 244, 238)):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        shadow = (196, 202, 214)
        if part == 'band':
            for y in range(H):
                for x in range(W):
                    cv.set(x, y, mix(col, shadow, 0.15 + 0.15 * (y / max(1, H - 1))))
            for x in range(W):
                cv.set(x, H - 1, mix(col, shadow, 0.55))
            if face == 'front':
                stitches(cv, [(x, 1) for x in range(W)], -0.6)
            return cv
        if face == 'top':
            for y in range(H):
                for x in range(W):
                    cx, cy = x - (W - 1) / 2, y - (H - 1) / 2
                    ang = math.atan2(cy, cx)
                    fold = (int((ang + math.pi) * 7 / math.pi) % 2)
                    r = math.hypot(cx, cy) / (W / 2)
                    cv.set(x, y, mix(col, shadow, (0.32 if fold else 0.06) * min(1, r * 1.3)))
            cv.set(W // 2, H // 2, mix(col, shadow, 0.7))
            return cv
        if face == 'bottom':
            cv.fill(mix(col, shadow, 0.75))
            return cv
        # puffed sides gathered into the band: soft vertical folds, the light from above
        for y in range(H):
            for x in range(W):
                f = (x % 4)
                t = {0: 0.05, 1: 0.0, 2: 0.18, 3: 0.38}[f]
                t += 0.1 * (y / H) + (0.25 if y == H - 1 else 0)
                cv.set(x, y, mix(col, shadow, min(0.8, t)))
        for x in range(W):
            cv.set(x, 0, mix(col, (255, 255, 252), 0.6))
        return cv
    return faces_of(fn)


def wrap_painter(part, col, c2=None, gem=None):
    """A wrapped head cloth: windings in diagonal bands, a jewel pinned at the front, the end hanging behind."""
    c2 = c2 or tone(col, -1.0)

    def fn(face, W, H):
        cv = Canvas(W, H, col)
        for y in range(H):
            for x in range(W):
                band = (x + y * 2) % 6
                k = (0.7, 0.3, 0.0, -0.3, -0.7, -0.2)[band]
                c = c2 if part == 'band' and (x + y * 2) % 18 in (3, 4) else col
                cv.set(x, y, tone(c, k + (0.3 if y == 0 else 0)))
        if part == 'band' and face == 'front' and gem:
            gx = W // 2 - 2
            cv.stamp(gx, 1, [".gg.", "gabg", "gbcg", ".gg."], {'g': GOLD, 'a': tone(gem, 1.6), 'b': rgba(gem), 'c': tone(gem, -1.2)})
        if face == 'bottom':
            cv.shade_rect(0, 0, W, H, -1.2)
        if part == 'tail' and face in ('front', 'back'):
            for x in range(W):
                if x % 2:
                    cv.clear(x, H - 1)
        return cv
    return faces_of(fn)


def circlet_painter(gem=None, col=GOLD):
    def fn(face, W, H):
        cv = Canvas(W, H)
        if face in ('top', 'bottom'):
            for y in range(H):
                for x in range(W):
                    if min(x, y, W - 1 - x, H - 1 - y) == 0:
                        cv.set(x, y, tone(col, 0.4 if face == 'top' else -0.8))
            return cv
        for x in range(W):
            cv.set(x, 0, tone(col, 1.0 if x % 4 else 0.4)); cv.set(x, 1, tone(col, -0.6))
        if gem and face == 'front':
            cx = W // 2 - 1
            cv.set(cx, 0, tone(gem, 1.6)); cv.set(cx + 1, 0, rgba(gem)); cv.set(cx, 1, rgba(gem)); cv.set(cx + 1, 1, tone(gem, -1.2))
            cv.set(cx - 1, 0, GOLD_L); cv.set(cx + 2, 0, GOLD_L)
        return cv
    return faces_of(fn)


def skullcap_painter(col, trim=None):
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.4, -0.3, -0.3)
        if face == 'top':
            for i in range(W):
                cv.shade(i, H // 2, -0.4); cv.shade(W // 2, i, -0.4)
            cv.stamp(W // 2 - 1, H // 2 - 1, ["ab", "bc"], {'a': GOLD_L, 'b': GOLD, 'c': tone(GOLD, -1)})
            if trim:
                for y in range(H):
                    for x in range(W):
                        if min(x, y, W - 1 - x, H - 1 - y) == 0:
                            cv.set(x, y, trim)
        elif face != 'bottom':
            for x in range(W):
                cv.set(x, H - 1, trim or tone(col, -0.6))
        return cv
    return faces_of(fn)


def veil_painter(col, edge=GOLD):
    """The priestess's veil, open at the face, falling to the shoulders."""
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.4, -0.5, -0.3)
        if face == 'bottom':
            c = Canvas(W, H)
            for y in range(H):
                for x in range(W):
                    if min(x, y, W - 1 - x, H - 1 - y) <= 1:
                        c.set(x, y, tone(col, -1.0))
            return c
        if face == 'top':
            for x in range(W):
                cv.shade(x, H // 2, -0.3)
            return cv
        if face == 'front':
            for y in range(4, H):
                for x in range(3, W - 3):
                    cv.clear(x, y)
            for x in range(2, W - 2):
                cv.set(x, 2, tone(edge, 0.6)); cv.set(x, 3, tone(edge, -0.4))
            for y in range(3, H):
                cv.set(2, y, tone(edge, 0.3)); cv.set(W - 3, y, tone(edge, -0.3))
            return cv
        # folds down the sides and back, a gold hem
        for x0 in range(2, W, 4):
            for y in range(3, H - 1):
                cv.shade(x0, y, -0.6); cv.shade(x0 - 1, y, 0.35)
        for x in range(W):
            cv.set(x, H - 1, tone(edge, -0.3)); cv.set(x, H - 2, tone(edge, 0.5))
        return cv
    return faces_of(fn)


# ---- body pieces

def cloth_piece(col, hem=None, folds=(), trim=None, extra=None, side_col=None, top_col=None, clear_bottom=False):
    """A flat hanging piece (apron, tabard flap, sash end): folds, an edge trim, a hem."""
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.4, -0.4, -0.35)
        if face in ('front', 'back') and W > 4:
            for fx in folds:
                for y in range(2, H - 1):
                    cv.shade(fx, y, -0.6 if y < H // 2 else -0.8)
                    cv.shade(fx - 1, y, 0.35)
            if trim:
                for y in range(H):
                    cv.set(0, y, tone(trim, 0.4)); cv.set(W - 1, y, tone(trim, -0.4))
            if hem:
                for x in range(W):
                    cv.set(x, H - 2, tone(hem, 0.6)); cv.set(x, H - 1, tone(hem, -0.5))
            if extra:
                extra(cv, face)
        elif face in ('right', 'left'):
            cv.fill(side_col or tone(col, -0.4))
            if trim:
                cv.fill(tone(trim, -0.2))
        elif face == 'top':
            cv.fill(top_col or tone(col, 0.5))
        elif face == 'bottom':
            if clear_bottom:
                return Canvas(W, H)
            cv.fill(tone(hem or col, -1.0))
        return cv
    return faces_of(fn)


def cape_painter(col, lining, trim=None, emblem=None):
    def fn(face, W, H):
        if face == 'front':
            cv = drape(Canvas(W, H), lining, face, 0.2, -0.6, -0.3)
            for x0 in (4, 9, 14):
                for y in range(4, H - 2):
                    cv.shade(x0, y, -0.5); cv.shade(x0 - 1, y, 0.25)
            return cv
        if face in ('right', 'left', 'top', 'bottom'):
            return Canvas(W, H, tone(trim or col, -0.3))
        cv = drape(Canvas(W, H), col, face, 0.5, -0.5, -0.4)
        for x0 in (3, 7, 12, 16):
            for y in range(3, H - 1):
                k = -0.75 if y > 10 else -0.5
                cv.shade(x0, y, k); cv.shade(x0 - 1, y, 0.4)
                if y > 22:
                    cv.shade(x0 + 1, y, -0.35)
        if trim:
            for y in range(H):
                cv.set(0, y, tone(trim, 0.4)); cv.set(1, y, tone(trim, -0.3))
                cv.set(W - 1, y, tone(trim, -0.4)); cv.set(W - 2, y, tone(trim, 0.2))
            for x in range(W):
                cv.set(x, H - 3, tone(trim, 0.6)); cv.set(x, H - 2, rgba(trim)); cv.set(x, H - 1, tone(trim, -0.7))
                cv.set(x, 0, tone(trim, 0.5))
        if emblem:
            emblem(cv)
        return cv
    return faces_of(fn)


def fur_painter(col, kind='fur'):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if kind == 'fur':
            for y in range(H):
                for x in range(W):
                    k = 0.0
                    if (x * 3 + y * 5) % 7 == 0:
                        k = 0.8
                    elif (x * 5 + y * 3) % 6 == 0:
                        k = -0.8
                    elif (x + y) % 2 == 0:
                        k = 0.25
                    if y == H - 1:
                        k -= 0.6
                    if y == 0:
                        k += 0.4
                    cv.set(x, y, tone(col, k))
            if face in ('front', 'back', 'right', 'left'):
                for x in range(0, W, 3):
                    cv.clear(x, H - 1)
        elif kind == 'knit':
            for y in range(H):
                for x in range(W):
                    cv.set(x, y, tone(col, (0.4 if (x + (y // 2)) % 2 == 0 else -0.3) + (0.3 if y == 0 else 0)))
        else:  # a cowl of cloth: deep soft folds
            for y in range(H):
                for x in range(W):
                    cv.set(x, y, tone(col, (0.5, 0.1, -0.3, -0.7, -0.2)[(x + y // 2) % 5] + (0.4 if y == 0 else 0)))
        if face == 'bottom':
            cv.shade_rect(0, 0, W, H, -1.0)
        return cv
    return faces_of(fn)


def pouch_painter(col, coin=False):
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.5, -0.6, -0.4)
        if face in ('front', 'right', 'left', 'back'):
            for x in range(W):
                cv.set(x, 0, tone(col, -0.6)); cv.set(x, 1, tone(col, 0.4)); cv.set(x, 2, tone(col, -0.8))
            if face == 'front':
                cv.set(W // 2, 2, (204, 190, 150))
                if coin:
                    cv.set(1, 4, GOLD_L)
        return cv
    return faces_of(fn)


def creel_painter(col=(176, 132, 74), strap=(96, 66, 40)):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if face in ('top', 'bottom'):
            for y in range(H):
                for x in range(W):
                    cv.set(x, y, tone(col, (0.4 if (x + y) % 2 == 0 else -0.3) + (-1.2 if face == 'bottom' else 0)))
            if face == 'top':
                for x in range(W):
                    cv.set(x, 0, tone(col, -0.8)); cv.set(x, H - 1, tone(col, -0.8))
                cv.set(W // 2, H - 1, (90, 66, 40)); cv.set(W // 2 - 1, H - 1, (90, 66, 40))
            return cv
        for y in range(H):
            for x in range(W):
                row = y // 2
                k = 0.5 if (x + row) % 2 == 0 else -0.4
                if y % 2 == 1:
                    k -= 0.35
                cv.set(x, y, tone(col, k))
        for x in range(W):
            cv.set(x, 0, tone(col, 0.9)); cv.set(x, 1, tone(col, -0.7))
        if face in ('right', 'left'):
            for y in range(H):
                cv.set(W // 2, y, tone(strap, 0.2 if y == 0 else 0))
        return cv
    return faces_of(fn)


def rag_painter(col=(232, 224, 204), check=(176, 58, 52)):
    def fn(face, W, H):
        cv = drape(Canvas(W, H), col, face, 0.4, -0.4, -0.3)
        for y in range(H):
            for x in range(W):
                if x % 4 == 1 or y % 4 == 1:
                    cv.tint(x, y, check, 0.55 if (x % 4 == 1 and y % 4 == 1) else 0.35)
        if face == 'front' and H > 4:
            for x in range(0, W, 2):
                cv.clear(x, H - 1)
        return cv
    return faces_of(fn)


def sleeve_painter(col, trim=None, lining=None):
    """A bell sleeve's cuff: hangs open (the hand shows below), a trim round its edge."""
    def fn(face, W, H):
        if face == 'bottom':
            cv = Canvas(W, H)
            for y in range(H):
                for x in range(W):
                    if min(x, y, W - 1 - x, H - 1 - y) == 0:
                        cv.set(x, y, tone(trim or col, -0.6))
                    elif min(x, y, W - 1 - x, H - 1 - y) == 1 and lining:
                        cv.set(x, y, tone(lining, -0.8))
            return cv
        cv = drape(Canvas(W, H), col, face, 0.4, -0.4, -0.35)
        if face == 'top':
            return cv
        for x0 in (3, 7):
            for y in range(1, H - 2):
                cv.shade(x0, y, -0.6); cv.shade(x0 - 1, y, 0.3)
        if trim:
            for x in range(W):
                cv.set(x, H - 3, tone(trim, 0.7)); cv.set(x, H - 2, rgba(trim)); cv.set(x, H - 1, tone(trim, -0.6))
        return cv
    return faces_of(fn)


def puff_painter(col, lining):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        for y in range(H):
            for x in range(W):
                pane = x % 3 == 1
                c = lining if pane else col
                k = (0.6 if 1 <= y <= H - 3 else -0.4) if face != 'top' else 0.4
                if y == H - 1 and face not in ('top', 'bottom'):
                    c, k = col, -0.6
                cv.set(x, y, tone(c, k))
        if face == 'bottom':
            cv.shade_rect(0, 0, W, H, -1.2)
        return cv
    return faces_of(fn)


def roll_painter(col):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if face in ('top', 'bottom'):
            cv.fill(tone(col, 0.2 if face == 'top' else -1.0))
            return cv
        for x in range(W):
            cv.set(x, 0, tone(col, 0.8)); cv.set(x, H - 1, tone(col, -0.6))
            if x % 3 == 0:
                cv.shade(x, H - 1, -0.5)
        return cv
    return faces_of(fn)


def boot_cuff_painter(col):
    def fn(face, W, H):
        cv = Canvas(W, H, col)
        if face == 'top':
            for y in range(H):
                for x in range(W):
                    d = min(x, y, W - 1 - x, H - 1 - y)
                    cv.set(x, y, tone(col, 0.8 if d == 0 else -1.6))
            return cv
        if face == 'bottom':
            cv.fill(tone(col, -1.3))
            return cv
        for x in range(W):
            cv.set(x, 0, tone(col, 1.0)); cv.set(x, 1, tone(col, 0.3)); cv.set(x, 2, tone(col, 0.0)); cv.set(x, 3, tone(col, -0.9))
        if face == 'front':
            cv.shade(2, 1, 0.6)
        return cv
    return faces_of(fn)


def belly_painter(torso, col):
    """The front of a big belly carries on whatever the torso wears there, rounded by the light."""
    def fn(face, W, H):
        if face == 'front':
            src = torso('front', 16, 24)
            cv = src.crop(1, 8, 14, 12)
            for x in range(14):
                cv.shade(x, 0, 0.5); cv.shade(x, 1, 0.2); cv.shade(x, 11, -0.7); cv.shade(x, 10, -0.3)
            for y in range(12):
                cv.shade(0, y, -0.5); cv.shade(13, y, -0.5)
            return cv
        if face in ('right', 'left'):
            src = torso(face, 8, 24)
            cv = src.crop(0, 8, 4, 12)
            cv.shade_rect(0, 0, 4, 12, -0.3)
            return cv
        if face == 'top':
            src = torso('front', 16, 24)
            return Canvas(W, H, tone(src.get(7, 8), 0.4))
        src = torso('front', 16, 24)
        return Canvas(W, H, tone(src.get(7, 19), -1.2))
    return faces_of(fn)


# ================================================================ looks

ROLES = ['citizen', 'smith', 'baker', 'merchant', 'guard', 'farmer', 'fisher', 'child', 'noble', 'innkeeper', 'bard', 'priest']

# Which cubes each piece paints (pieces with two sides list both parts)
PIECE_CUBES = {
    'hair_bun': ['bun'], 'ponytail': ['braid'], 'pigtails': ['pigtail_r', 'pigtail_l'], 'hair_bow': ['bow'], 'beard': ['beard'],
    'kerchief_knot': ['knot'], 'straw_hat': ['straw_brim', 'straw_crown'], 'wide_hat': ['wide_brim', 'wide_crown'],
    'kettle_helm': ['kettle_brim', 'kettle_crown', 'kettle_dome'], 'souwester': ['sou_crown', 'sou_brim', 'sou_flap'],
    'cap': ['cap'], 'baker_cap': ['baker_band', 'baker_puff'], 'head_wrap': ['wrap_band', 'wrap_top', 'wrap_tail'],
    'plumed_hat': ['plumed_brim', 'plumed_crown', 'plume'], 'bard_cap': ['bard_crown', 'bard_peak', 'bard_tip', 'bard_feather'],
    'circlet': ['circlet'], 'skullcap': ['skullcap'], 'veil': ['veil'], 'apron': ['apron'], 'belly': ['belly'],
    'tabard': ['tabard_front'], 'tabard_back': ['tabard_rear'], 'cape': ['cape'], 'collar': ['collar'],
    'hair_long': ['hair_long'], 'pouch': ['pouch'], 'creel': ['creel'], 'sash_tails': ['sash_tails'],
    'sleeve_r': ['sleeve_r'], 'sleeve_l': ['sleeve_l'], 'puff_r': ['puff_r'], 'puff_l': ['puff_l'], 'roll_r': ['roll_r'],
    'roll_l': ['roll_l'], 'shoulder_rag': ['rag_top', 'rag_front', 'rag_back'], 'skirt_r': ['skirt_r'], 'skirt_l': ['skirt_l'],
    'boot_r': ['boot_r'], 'boot_l': ['boot_l'],
}
# Pairs: a look names the pair, the model has both parts
PAIRS = {'skirt': ('skirt_r', 'skirt_l'), 'boots': ('boot_r', 'boot_l'), 'rolls': ('roll_r', 'roll_l'),
         'sleeves': ('sleeve_r', 'sleeve_l'), 'puffs': ('puff_r', 'puff_l')}


class Look:
    """One townsperson's look: its texture name, voice, the pieces and look tools it shows (parts), and how it is
    painted (head, torso, arms, legs and each piece)."""

    def __init__(self, role, n, timbre, head, torso, arms, legs, pieces=None, tools=(), note=''):
        self.role, self.n, self.timbre, self.note = role, n, timbre, note
        self.texture = f'{role}_{n}'
        self.head, self.torso, self.arms, self.legs = head, torso, arms, legs
        self.pieces = dict(pieces or {})
        self.tools = set(tools)
        names = set()
        for k in self.pieces:
            names |= set(PAIRS.get(k, (k,)))
        self.piece_names = names
        self.parts = names | self.tools
        self.head.setdefault('seed', seed_of(role, n) % 100000)

    def __repr__(self):
        return f'Look({self.texture}, {self.timbre}, {sorted(self.parts)})'


def piece_painters(look, torso_p):
    """Cube name -> painter for every piece this look shows."""
    h = look.head
    sk = skin_ramp(h['skin'])
    seed = h['seed']
    out = {}
    for name, spec in look.pieces.items():
        spec = dict(spec) if isinstance(spec, dict) else {'col': spec}
        col = spec.get('col')
        if name == 'hair_bun':
            out['bun'] = bun_painter(col or h['hair'], seed + 11, spec.get('pin'))
        elif name == 'ponytail':
            out['braid'] = braid_painter(col or h['hair'], seed + 12, spec.get('tie'), spec.get('plain', False))
        elif name == 'pigtails':
            out['pigtail_r'] = pigtail_painter(col or h['hair'], seed + 13, spec.get('tie', (200, 60, 70)))
            out['pigtail_l'] = pigtail_painter(col or h['hair'], seed + 14, spec.get('tie', (200, 60, 70)))
        elif name == 'hair_bow':
            out['bow'] = bow_painter(col)
        elif name == 'beard':
            out['beard'] = beard_painter(col or h.get('beard_col') or h['hair'], spec.get('style', 'full'), seed + 15, sk,
                                         spec.get('curly', False))
        elif name == 'kerchief_knot':
            out['knot'] = knot_painter(col or h.get('layer_col'))
        elif name == 'straw_hat':
            out['straw_brim'] = straw_painter('brim', col, spec.get('band'), spec.get('worn', False))
            out['straw_crown'] = straw_painter('crown', col, spec.get('band'), spec.get('worn', False))
        elif name == 'wide_hat':
            out['wide_brim'] = felt_painter('brim', col, edge=spec.get('edge'))
            out['wide_crown'] = felt_painter('crown', col, spec.get('band'), spec.get('gem'))
        elif name == 'kettle_helm':
            for part in ('brim', 'crown', 'dome'):
                out['kettle_' + part] = steel_painter(part, col or STEEL)
        elif name == 'souwester':
            out['sou_crown'] = oilskin_painter('crown', col)
            out['sou_brim'] = oilskin_painter('brim', col)
            out['sou_flap'] = oilskin_painter('flap', col)
        elif name == 'cap':
            out['cap'] = knit_painter(col, spec.get('rib'), spec.get('kind', 'knit'))
        elif name == 'baker_cap':
            out['baker_band'] = baker_cap_painter('band', col or (246, 244, 238))
            out['baker_puff'] = baker_cap_painter('puff', col or (246, 244, 238))
        elif name == 'head_wrap':
            for part, cube in (('band', 'wrap_band'), ('top', 'wrap_top'), ('tail', 'wrap_tail')):
                out[cube] = wrap_painter(part, col, spec.get('col2'), spec.get('gem'))
        elif name == 'plumed_hat':
            out['plumed_brim'] = felt_painter('brim', col, edge=spec.get('edge'))
            out['plumed_crown'] = felt_painter('crown', col, spec.get('band', GOLD), spec.get('gem'))
            out['plume'] = feather_painter(spec.get('plume', (244, 240, 230)), quill=spec.get('quill'), tip=spec.get('tip'), axis='u')
        elif name == 'bard_cap':
            out['bard_crown'] = felt_painter('crown', col, spec.get('band'))
            out['bard_peak'] = felt_painter('brim', col, edge=spec.get('edge'))
            out['bard_tip'] = felt_painter('crown', col)
            out['bard_feather'] = feather_painter(spec.get('feather', (230, 190, 60)), tip=spec.get('tip'), axis='v')
        elif name == 'circlet':
            out['circlet'] = circlet_painter(spec.get('gem'), col or GOLD)
        elif name == 'skullcap':
            out['skullcap'] = skullcap_painter(col, spec.get('trim'))
        elif name == 'veil':
            out['veil'] = veil_painter(col, spec.get('edge', GOLD))
        elif name == 'apron':
            out['apron'] = spec.get('painter') or cloth_piece(col, hem=spec.get('hem'), folds=spec.get('folds', (4, 9)),
                                                              trim=spec.get('trim'), extra=spec.get('extra'))
        elif name == 'belly':
            out['belly'] = belly_painter(torso_p, col)
        elif name == 'tabard':
            out['tabard_front'] = cloth_piece(col, hem=spec.get('trim', GOLD), folds=(4, 9), trim=spec.get('trim', GOLD),
                                              extra=spec.get('extra'))
        elif name == 'tabard_back':
            out['tabard_rear'] = cloth_piece(col, hem=spec.get('trim', GOLD), folds=(5, 10), trim=spec.get('trim', GOLD))
        elif name == 'cape':
            out['cape'] = cape_painter(col, spec.get('lining', tone(col, -1.0)), spec.get('trim'), spec.get('emblem'))
        elif name == 'collar':
            out['collar'] = fur_painter(col, spec.get('kind', 'fur'))
        elif name == 'hair_long':
            out['hair_long'] = spec.get('painter') or hair_long_painter(col or h['hair'], seed + 16, spec.get('len', 14),
                                                                        spec.get('shape', 'round'))
        elif name == 'pouch':
            out['pouch'] = pouch_painter(col, spec.get('coin', False))
        elif name == 'creel':
            out['creel'] = creel_painter(col or (176, 132, 74), spec.get('strap', (96, 66, 40)))
        elif name == 'sash_tails':
            out['sash_tails'] = cloth_piece(col, folds=(), extra=lambda cv, f: [cv.clear(x, cv.H - 1) for x in range(0, cv.W, 2)])
        elif name in ('sleeves',):
            out['sleeve_r'] = out['sleeve_l'] = sleeve_painter(col, spec.get('trim'), spec.get('lining'))
        elif name in ('puffs',):
            out['puff_r'] = puff_painter(col, spec.get('lining', GOLD))
            out['puff_l'] = puff_painter(spec.get('col_l', col), spec.get('lining_l', spec.get('lining', GOLD)))
        elif name in ('rolls',):
            out['roll_r'] = out['roll_l'] = roll_painter(col)
        elif name == 'shoulder_rag':
            p = rag_painter(col or (232, 224, 204), spec.get('check', (176, 58, 52)))
            out['rag_top'] = out['rag_front'] = out['rag_back'] = p
        elif name == 'skirt':
            r, l = skirt_painter(spec, h)
            out['skirt_r'], out['skirt_l'] = r, l
        elif name == 'boots':
            out['boot_r'] = out['boot_l'] = boot_cuff_painter(col)
        else:
            raise SystemExit(f'townsfolk: no painter for piece {name}')
    return out


def look_painters(look):
    h = look.head
    torso_p = torso_painter(look.torso, h)
    p = {'head': head_painter(h), 'body': torso_p,
         'right_arm': arm_painter(look.arms, h, 'r'), 'left_arm': arm_painter(look.arms, h, 'l'),
         'right_leg': leg_painter(look.legs, h, 'r'), 'left_leg': leg_painter(look.legs, h, 'l')}
    hl = hat_layer_painter(h)
    if hl:
        p['hat'] = hl
    p.update(piece_painters(look, torso_p))
    return p


_MODEL = []


def _model():
    if not _MODEL:
        _MODEL.append(build_model())
    return _MODEL[0]


def look_texture(role, n, model=None):
    """The HD texture (SCALE times the declared size) of look [n] of [role]."""
    look = LOOKS[role][n]
    model = model or _model()
    painters = look_painters(look)
    a = np.zeros((TEX_H * SCALE, TEX_W * SCALE, 4), np.uint8)
    for c in model.all_cubes():
        p = painters.get(c.name)
        if p is None:
            continue
        for face, (x, y, w, h) in rects2x(c).items():
            if w <= 0 or h <= 0:
                continue
            cv = p(face, w, h)
            if cv is None:
                continue
            if cv.a.shape[:2] != (h, w):
                raise SystemExit(f'townsfolk: {look.texture} {c.name} {face} painted {cv.a.shape[1]}x{cv.a.shape[0]}, wants {w}x{h}')
            a[y:y + h, x:x + w] = cv.a
    img = Image.fromarray(a, 'RGBA')
    tool_p = _tools()[1]
    for c in model.all_cubes():
        tp = tool_p.get(c.name)
        if tp:
            for face, (x, y, w, h) in rects2x(c).items():
                if w > 0 and h > 0:
                    tp(img, face, x, y, w, h)
    return img


def glow_texture(model=None):
    """Transparent but for the emissive tool cubes (the hot iron, the lantern's panes), as painted on every look."""
    model = model or _model()
    _, tool_p, glow = _tools()
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    for c in model.all_cubes():
        if c.name in glow and c.name in tool_p:
            for face, (x, y, w, h) in rects2x(c).items():
                if w > 0 and h > 0:
                    tool_p[c.name](img, face, x, y, w, h)
    return img


# ================================================================ previews

def _rotm(rot):
    rx, ry, rz = [math.radians(a) for a in rot]
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X


def look_faces(model, shown, child=False, only=None):
    """Every visible face as modelkit's renderer wants it, at the HD texture's rects and with the bottom faces the
    way Minecraft maps them. [shown]: the pieces and tools to draw (every other piece and tool hides). A child is drawn
    as the game will: 0.6 scale with a 1.3x head."""
    optional = set(PIECES) | set(tool_names())
    out = []

    def go(part, M, T, top):
        if part.name in optional and part.name not in shown:
            return
        R = M @ _rotm(part.rot)
        P = T + M @ np.array(part.pivot, float)
        for c in part.cubes:
            g = c.inflate
            x0, y0, z0 = [o - g for o in c.origin]
            x1, y1, z1 = [o + s + g for o, s in zip(c.origin, c.size)]

            def W(x, y, z):
                p = P + R @ np.array([x, y, z], float)
                if child:
                    if top == 'head':
                        p = p * 1.3
                    p = (p - np.array([0, 24, 0])) * 0.6 + np.array([0, 24, 0])
                return p
            quads = {
                'front': (W(x0, y0, z0), W(x1, y0, z0), W(x0, y1, z0), (0, 0, -1)),
                'back': (W(x1, y0, z1), W(x0, y0, z1), W(x1, y1, z1), (0, 0, 1)),
                'right': (W(x0, y0, z1), W(x0, y0, z0), W(x0, y1, z1), (-1, 0, 0)),
                'left': (W(x1, y0, z0), W(x1, y0, z1), W(x1, y1, z0), (1, 0, 0)),
                'top': (W(x0, y0, z1), W(x1, y0, z1), W(x0, y0, z0), (0, -1, 0)),
                'bottom': (W(x0, y1, z1), W(x1, y1, z1), W(x0, y1, z0), (0, 1, 0)),
            }
            rects = rects2x(c)
            for face, (A, B, D, n) in quads.items():
                out.append((A, B, D, rects[face], face, R @ np.array(n, float)))
        for ch in part.children:
            go(ch, R, P, top)
    for p in model.parts:
        if only and p.name not in only:
            continue
        go(p, np.eye(3), np.zeros(3), p.name)
    return out


IDLE_TOOLS = {'smith': {'hammer'}, 'guard': {'spear'}, 'farmer': {'hoe'}, 'fisher': {'rod'}, 'bard': {'lute_slung'},
              'priest': {'book'}}


def render_look(model, look, tex, glow=None, yaw=25, pitch=-10, scale=6, size=(150, 230), tools=None, only=None, y_shift=0.0):
    shown = set(look.parts) | (IDLE_TOOLS.get(look.role, set()) if tools is None else set(tools))
    faces = look_faces(model, shown, child=look.role == 'child', only=only)
    lit = None
    if glow is not None:
        a, b = np.array(tex).astype(int), np.array(glow).astype(int)
        on = b[..., 3] > 0
        o = np.zeros_like(a)
        o[on, :3] = np.clip(a[on, :3] + b[on, :3], 0, 255)
        o[on, 3] = 255
        lit = Image.fromarray(o.astype(np.uint8), 'RGBA')
    return model.render(tex, yaw=yaw, pitch=pitch, scale=scale, size=size, glow=lit, faces=faces, y_shift=y_shift)


# ---------------------------------------------------------------- the palette of the city

LEATHER = (110, 72, 44); LEATHER_D = (78, 50, 32); LEATHER_L = (146, 100, 62); HIDE = (128, 84, 50)
BOOT = (86, 58, 38); BOOT_D = (58, 40, 30); BOOT_B = (44, 38, 36)
LINEN = (226, 216, 190); CREAM = (236, 228, 206); WHITE = (246, 244, 238); OAT = (204, 188, 152); FLAX = (214, 200, 160)
RUST = (150, 76, 50); BRICK = (136, 62, 48); MADDER = (160, 66, 60); OCHRE = (186, 138, 62); MUSTARD = (198, 162, 70)
OLIVE = (108, 112, 60); MOSS = (90, 112, 66); SAGE = (140, 152, 112); UMBER = (104, 76, 52); BROWN = (122, 86, 56)
TAN = (174, 138, 96); SLATE = (90, 100, 114); INDIGO = (74, 90, 128); CHARCOAL = (66, 64, 68); HEATHER = (124, 110, 128)
PLUM_D = (104, 66, 82); TEAL_D = (62, 104, 104); WOAD = (96, 122, 150); FOREST = (62, 92, 62)
SAFFRON = (232, 168, 40); TEAL = (32, 140, 140); CRIMSON = (176, 30, 48); PLUM = (122, 52, 112); PEACOCK = (24, 108, 128)
WATCH = (34, 52, 120); WATCH_D = (24, 36, 88)
BURGUNDY = (122, 26, 44); ROYAL = (40, 64, 160); EMERALD = (24, 116, 72); IVORY = (240, 232, 210); BLACK = (40, 36, 42)
SCARLET = (184, 36, 40); ROSE = (220, 110, 130); SKY = (110, 170, 220); LEAF = (98, 168, 72); SUN = (240, 196, 60)
OILSKIN = (198, 150, 52); OILSKIN_G = (112, 120, 60)
GEM_RUBY = (200, 30, 60); GEM_EMERALD = (30, 170, 90); GEM_SAPPHIRE = (40, 80, 210); GEM_AMBER = (240, 150, 30)
PEARL = (240, 236, 226)


def person(skin, hair=None, style='crop', eyes='brown', **kw):
    d = dict(skin=SKIN[skin] if isinstance(skin, str) else skin, hair=HAIR[hair] if isinstance(hair, str) else hair,
             style=style, eyes=EYES[eyes] if isinstance(eyes, str) else eyes)
    d.update(kw)
    return d


def man(*a, **kw): return person(*a, **kw)
def woman(*a, **kw): return person(*a, fem=True, **kw)
def oldman(*a, **kw): return person(*a, age='old', **kw)
def oldwoman(*a, **kw): return person(*a, fem=True, age='old', **kw)
def boy(*a, **kw): return person(*a, age='child', **kw)
def girl(*a, **kw): return person(*a, fem=True, age='child', **kw)


def _looks():
    L = {r: [] for r in ROLES}

    def add(role, timbre, head, torso, arms, legs, pieces=None, tools=(), note=''):
        L[role].append(Look(role, len(L[role]), timbre, head, torso, arms, legs, pieces, tools, note))

    # ---------------------------------------------------------------- citizens: tunics, trousers, dresses, headscarves
    add('citizen', 'man',
        man('fair', 'brown', 'crop', 'blue', stubble=0.16),
        dict(col=RUST, neck='slit', laces=CREAM, belt=LEATHER, buckle=True, belt_pouch=LEATHER_L),
        dict(col=RUST, len='long', cuff=tone(RUST, -0.6)),
        dict(col=UMBER, tunic=dict(col=RUST, rows=5), boots=dict(col=BOOT)),
        note='young man in a rust tunic')
    add('citizen', 'man',
        man('dark', 'black', 'curly', 'dark', beard_col=HAIR['black'], beard_side=True, beard_curly=True, nose='broad'),
        dict(col=MUSTARD, neck='crew', vest=dict(kind='jerkin', col=LEATHER, laces=OAT), belt=LEATHER_D, buckle=True),
        dict(col=MUSTARD, len='long', cuff=tone(MUSTARD, -0.7)),
        dict(col=OLIVE, tunic=dict(col=LEATHER, rows=4), boots=dict(col=BOOT_D)),
        {'beard': dict(style='short', curly=True)},
        note='man in a leather jerkin over a mustard shirt')
    add('citizen', 'man',
        man('olive', 'dark', 'swept', 'hazel', moustache='thin', stubble=0.12, hat_shadow=2),
        dict(col=WOAD, neck='v', under=LINEN, buttons=(200, 186, 150), belt=LEATHER, buckle=True, belt_pouch=LEATHER),
        dict(col=WOAD, len='long', cuff=LINEN),
        dict(col=SLATE, boots=dict(col=BOOT, top=15)),
        {'cap': dict(col=INDIGO, kind='felt')},
        note='man in a woad tunic and felt cap')
    add('citizen', 'old',
        oldman('rosy', 'grey', 'balding', 'grey', beard_col=HAIR['white'], beard_side=True, brows=(200, 198, 192), nose='long'),
        dict(col=OAT, neck='crew', belt=LEATHER_D, buckle=False, folds=True),
        dict(col=OAT, len='long', cuff=tone(OAT, -0.8)),
        dict(col=BROWN, tunic=dict(col=OAT, rows=9), boots=dict(col=BOOT, kind='shoe')),
        {'beard': dict(style='long', col=HAIR['white']), 'collar': dict(col=UMBER, kind='cowl')},
        note='old man with a long white beard and a brown cowl')
    add('citizen', 'woman',
        woman('fair', 'auburn', 'tied', 'green', layer='kerchief', layer_col=CREAM),
        dict(col=CREAM, neck='square', vest=dict(kind='bodice', col=BROWN, top=4), waist=16, skirt=MOSS),
        dict(col=CREAM, len='three'),
        dict(col=CHARCOAL, kind='hose', boots=dict(col=BOOT, kind='shoe')),
        {'kerchief_knot': dict(col=CREAM), 'skirt': dict(col=MOSS)},
        note='woman in a green dress and cream headscarf')
    add('citizen', 'woman',
        woman('brown', 'black', 'tied', 'dark', layer='kerchief', layer_col=OCHRE, layer_pattern='dots', layer_col2=CREAM,
              nose='broad'),
        dict(col=MADDER, neck='scoop', under=LINEN, waist=16, skirt=MADDER, bib=dict(col=LINEN, top=9, x0=4, x1=11, strap=OAT)),
        dict(col=LINEN, len='three'),
        dict(col=UMBER, kind='hose', boots=dict(col=BOOT, kind='shoe')),
        {'kerchief_knot': dict(col=OCHRE), 'skirt': dict(col=MADDER, apron=dict(col=LINEN, len=19, x0=5, x1=14))},
        tools={'basket'}, note='woman in a madder dress and spotted ochre headscarf, with a basket')
    add('citizen', 'woman',
        woman('light', 'chestnut', 'parted', 'hazel'),
        dict(col=WOAD, neck='scoop', under=CREAM, waist=16, skirt=WOAD, belt=LEATHER, belt_y=15, buckle=False,
             laces=CREAM),
        dict(col=WOAD, len='long', cuff=CREAM),
        dict(col=CHARCOAL, kind='hose', boots=dict(col=BOOT_D, kind='shoe')),
        {'ponytail': dict(tie=CREAM), 'skirt': dict(col=WOAD, trim=tone(WOAD, -1.2))},
        note='woman in a woad dress with a long braid')
    add('citizen', 'woman',
        oldwoman('rosy', 'grey', 'parted', 'blue', brow_style='soft', blush=0.25),
        dict(col=PLUM_D, neck='crew', shawl=dict(col=(176, 128, 80)), waist=16, skirt=PLUM_D),
        dict(col=PLUM_D, len='long', cuff=tone(PLUM_D, -0.8)),
        dict(col=CHARCOAL, kind='hose', boots=dict(col=BOOT_D, kind='shoe')),
        {'hair_bun': dict(col=HAIR['grey']), 'skirt': dict(col=PLUM_D, apron=dict(col=OAT, len=20, x0=4, x1=15))},
        note='old woman with a grey bun and a woollen shawl')

    # ---------------------------------------------------------------- smiths: leather aprons, soot, rolled sleeves
    add('smith', 'man',
        man('tan', 'dark', 'crop', 'brown', stubble=0.3, soot=True, brow_style='thick', layer='headband', layer_col=(150, 40, 36)),
        dict(col=CHARCOAL, neck='v', bib=dict(col=HIDE, strap=LEATHER_D, pocket=True), soot=True),
        dict(col=CHARCOAL, len='rolled', soot=True, hairy=(40, 30, 26)),
        dict(col=(70, 60, 54), boots=dict(col=BOOT_B)),
        {'apron': dict(col=HIDE, hem=tone(HIDE, -0.5), folds=(4, 9)), 'rolls': dict(col=CHARCOAL)},
        note='smith with a red headband')
    add('smith', 'old',
        oldman('dark', 'white', 'balding', 'brown', beard_col=HAIR['white'], beard_side=True, brows=(214, 210, 204), soot=True,
               layer='leather_cap', layer_col=(70, 64, 62), nose='broad'),
        dict(col=SLATE, neck='crew', bib=dict(col=LEATHER, strap=LEATHER_D, x0=2, x1=13, top=3), soot=True),
        dict(col=SLATE, len='rolled', soot=True, hairy=(200, 196, 190)),
        dict(col=CHARCOAL, boots=dict(col=BOOT_B)),
        {'beard': dict(style='full', col=HAIR['white']), 'apron': dict(col=LEATHER, hem=tone(LEATHER, -0.6), folds=(3, 10)),
         'rolls': dict(col=SLATE)},
        note='old smith with a white beard and a leather cap')
    add('smith', 'woman',
        woman('fair', 'ginger', 'parted', 'green', freckles=1, soot=True, layer='headband', layer_col=LEATHER, band_row=3),
        dict(col=OAT, neck='v', bib=dict(col=LEATHER_L, strap=LEATHER, top=5), soot=True),
        dict(col=OAT, len='rolled', soot=True),
        dict(col=BROWN, boots=dict(col=BOOT_D, laces=(150, 120, 90))),
        {'ponytail': dict(tie=LEATHER_D), 'apron': dict(col=LEATHER_L, hem=tone(LEATHER_L, -0.6), folds=(3, 8)),
         'rolls': dict(col=OAT)},
        note='smith with a ginger braid and a leather headband')

    # ---------------------------------------------------------------- bakers: white caps, floury aprons, rosy cheeks
    add('baker', 'man',
        man('rosy', 'chestnut', 'crop', 'brown', moustache='handlebar', blush=0.5, flour=True, hat_shadow=1, nose='broad'),
        dict(col=WHITE, neck='slit', bib=dict(col=WHITE, strap=OAT, top=6, stitch=True), flour=True),
        dict(col=WHITE, len='rolled', flour=True, hairy=(120, 70, 40)),
        dict(col=OAT, boots=dict(col=BOOT, kind='shoe')),
        {'baker_cap': {}, 'belly': {}, 'apron': dict(col=WHITE, hem=OAT, folds=(4, 9)), 'rolls': dict(col=WHITE)},
        note='round baker with a handlebar moustache')
    add('baker', 'woman',
        woman('brown', 'dark', 'tied', 'brown', blush=0.35, flour=True, layer='kerchief', layer_col=WHITE),
        dict(col=OCHRE, neck='square', under=LINEN, waist=16, skirt=OCHRE, bib=dict(col=WHITE, top=6, x0=3, x1=12, strap=WHITE),
             flour=True),
        dict(col=OCHRE, len='rolled', flour=True),
        dict(col=UMBER, kind='hose', boots=dict(col=BOOT, kind='shoe')),
        {'kerchief_knot': dict(col=WHITE), 'rolls': dict(col=OCHRE),
         'skirt': dict(col=OCHRE, apron=dict(col=WHITE, len=20, x0=4, x1=15, dust=True))},
        note='baker in an ochre dress and white kerchief')
    add('baker', 'woman',
        woman('porcelain', 'blond', 'parted', 'blue', blush=0.45, freckles=1, flour=True, hat_shadow=1),
        dict(col=WHITE, neck='square', vest=dict(kind='bodice', col=INDIGO, top=5), waist=16, skirt=INDIGO, flour=True),
        dict(col=WHITE, len='three', flour=True),
        dict(col=CHARCOAL, kind='hose', boots=dict(col=BOOT_D, kind='shoe')),
        {'baker_cap': {}, 'ponytail': dict(tie=SKY),
         'skirt': dict(col=INDIGO, apron=dict(col=WHITE, len=21, x0=3, x1=16, dust=True))},
        note='baker with a blond braid and a blue dress')

    # ---------------------------------------------------------------- merchants: rich colours, sashes, purses, hats
    add('merchant', 'man',
        man('deep', 'black', 'shaved', 'amber', beard_col=HAIR['black'], beard_side=True, nose='broad', hat_shadow=2),
        dict(col=IVORY, neck='slit', vest=dict(kind='coat', col=TEAL, trim=SAFFRON), sash=dict(col=CRIMSON, y=16)),
        dict(col=TEAL, len='long', cuff=SAFFRON, ring=True),
        dict(col=(70, 50, 40), boots=dict(col=(120, 74, 40), top=15, cuff_col=(150, 96, 52))),
        {'head_wrap': dict(col=SAFFRON, col2=CRIMSON, gem=GEM_RUBY), 'beard': dict(style='short', col=HAIR['black']),
         'pouch': dict(col=LEATHER_L, coin=True), 'sash_tails': dict(col=CRIMSON),
         'skirt': dict(col=TEAL, kind='coat', len=17, open=IVORY, trim=SAFFRON, folds=True)},
        note='merchant in a teal coat and saffron head wrap')
    add('merchant', 'man',
        man('fair', 'brown', 'swept', 'grey', beard_col=HAIR['brown'], moustache='brush', blush=0.3, hat_shadow=2),
        dict(col=SAFFRON, neck='crew', vest=dict(kind='coat', col=CRIMSON, buttons=GOLD, trim=GOLD), sash=dict(col=SAFFRON, y=16),
             necklace=('chain', GEM_AMBER)),
        dict(col=CRIMSON, len='long', cuff=GOLD, ring=True),
        dict(col=PLUM_D, kind='hose', boots=dict(col=(70, 40, 40), kind='shoe', buckle=GOLD)),
        {'wide_hat': dict(col=PLUM, band=GOLD, edge=tone(PLUM, -0.6)), 'belly': {}, 'beard': dict(style='goatee'),
         'pouch': dict(col=PLUM_D, coin=True),
         'skirt': dict(col=CRIMSON, kind='coat', len=15, open=SAFFRON, trim=GOLD)},
        note='portly merchant in crimson with a plum hat')
    add('merchant', 'woman',
        woman('olive', 'black', 'tied', 'amber', earrings=GOLD_L, hat_shadow=1),
        dict(col=SAFFRON, neck='scoop', under=IVORY, neck_trim=TEAL, vest=dict(kind='bodice', col=TEAL, top=4, trim=GOLD), waist=16,
             skirt=SAFFRON, sash=dict(col=CRIMSON, y=15), necklace=('chain', GEM_RUBY)),
        dict(col=SAFFRON, len='long', cuff=TEAL, ring=True),
        dict(col=(90, 50, 40), kind='hose', boots=dict(col=(110, 60, 40), kind='shoe')),
        {'head_wrap': dict(col=TEAL, col2=PEACOCK, gem=GEM_AMBER), 'sash_tails': dict(col=CRIMSON), 'pouch': dict(col=LEATHER, coin=True),
         'skirt': dict(col=SAFFRON, pattern=('band', TEAL, 15, 18), trim=CRIMSON, trim_dots=GOLD)},
        note='merchant in saffron with a teal head wrap')
    add('merchant', 'woman',
        woman('light', 'copper', 'long_swept', 'teal', earrings=GOLD_L, hat_shadow=2),
        dict(col=PLUM, neck='square', under=IVORY, vest=dict(kind='bodice', col=PLUM, top=3, trim=GOLD), waist=16, skirt=PLUM,
             sash=dict(col=TEAL, y=14), necklace=('chain', GEM_AMBER)),
        dict(col=PLUM, len='long', cuff=TEAL),
        dict(col=PLUM_D, kind='hose', boots=dict(col=(60, 40, 50), kind='shoe')),
        {'wide_hat': dict(col=TEAL, band=GOLD, edge=tone(TEAL, -0.8)), 'hair_long': dict(len=14), 'pouch': dict(col=LEATHER_L, coin=True),
         'sash_tails': dict(col=TEAL),
         'skirt': dict(col=PLUM, panel=dict(col=TEAL, edge=GOLD), trim=GOLD)},
        note='merchant in plum with a teal hat')

    # ---------------------------------------------------------------- the watch: kettle helm, mail, blue tabard
    def guard(skin, hair, eyes, timbre='man', pieces=None, **kw):
        note = kw.pop('note', 'watch')
        h = (woman if kw.pop('fem', False) else man)(skin, hair, 'crop', eyes, layer='coif', layer_col=STEEL, hat_shadow=4, **kw)
        pc = {'kettle_helm': {}, 'tabard': dict(col=WATCH), 'tabard_back': dict(col=WATCH), 'boots': dict(col=BOOT_D)}
        pc.update(pieces or {})
        add('guard', timbre, h,
            dict(col=STEEL, mail=True, tabard=dict(col=WATCH, trim=GOLD, emblem=True), neck='none', belt=LEATHER_D,
                 buckle=(204, 196, 170)),
            dict(col=STEEL, mail=True, len='long', bracer=LEATHER, glove=LEATHER_D, glove_from=20),
            dict(col=(58, 60, 76), boots=dict(col=BOOT_D, kind='tall', top=10)), pc, note=note)
    guard('fair', 'brown', 'grey', moustache='brush', note='watchman with a moustache')
    guard('dark', 'black', 'dark', mouth='stern', brow_style='stern', nose='broad', note='watchman, clean-shaven')
    guard('olive', 'black', 'brown', beard_col=HAIR['black'], scar=True, pieces={'beard': dict(style='short', col=HAIR['black'])},
          note='watchman with a black beard and a scar')
    guard('light', 'auburn', 'green', fem=True, timbre='woman', freckles=1, pieces={'ponytail': dict(tie=LEATHER_D)},
          note='watchwoman with an auburn braid')

    # ---------------------------------------------------------------- farmers: straw hats, smocks, muddy boots
    add('farmer', 'man',
        man('tan', 'blond', 'messy', 'blue', stubble=0.2, hat_shadow=3, blush=0.2),
        dict(col=LINEN, neck='slit', laces=OAT, neckerchief=BRICK, belt=LEATHER, buckle=True, strap=None),
        dict(col=LINEN, len='rolled'),
        dict(col=UMBER, patch=OLIVE, boots=dict(col=BOOT, mud=True)),
        {'straw_hat': dict(col=(222, 190, 112), band=BRICK), 'rolls': dict(col=LINEN)},
        note='young farmer in a linen shirt and red neckerchief')
    add('farmer', 'old',
        oldman('dark', 'white', 'shaved', 'brown', stubble=0.26, beard_col=HAIR['white'], brows=(220, 216, 210), hat_shadow=3),
        dict(col=SAGE, neck='slit', smocking=OAT, folds=False),
        dict(col=SAGE, len='long', cuff=tone(SAGE, -0.7)),
        dict(col=OLIVE, patch=BROWN, boots=dict(col=BOOT_D, mud=True)),
        {'straw_hat': dict(col=(206, 174, 100), worn=True), 'skirt': dict(col=SAGE, kind='smock', len=9, mud=True)},
        note='old farmer in a sage smock')
    add('farmer', 'woman',
        woman('fair', 'strawberry', 'parted', 'green', freckles=2, hat_shadow=3, blush=0.3),
        dict(col=LINEN, neck='square', vest=dict(kind='bodice', col=MUSTARD, top=5), waist=16, skirt=MUSTARD),
        dict(col=LINEN, len='rolled'),
        dict(col=UMBER, kind='hose', boots=dict(col=BOOT, mud=True, top=16)),
        {'straw_hat': dict(col=(230, 200, 120), band=SKY), 'ponytail': dict(tie=SKY), 'rolls': dict(col=LINEN),
         'skirt': dict(col=MUSTARD, len=22, apron=dict(col=LINEN, len=18, x0=4, x1=15), mud=True)},
        tools={'basket'}, note='farmer with a strawberry braid and a basket')

    # ---------------------------------------------------------------- fishers: knit caps, oilskins, tall boots, creels
    add('fisher', 'man',
        man('fair', 'ginger', 'crop', 'blue', stubble=0.35, blush=0.45, hat_shadow=1),
        dict(col=INDIGO, neck='high', neck_trim=tone(INDIGO, 0.4), vest=dict(kind='coat', col=OILSKIN, buttons=(220, 210, 180)),
             shine=True),
        dict(col=OILSKIN, len='long', cuff=tone(OILSKIN, -0.8)),
        dict(col=(70, 72, 80), boots=dict(col=BOOT_B, kind='tall', top=9)),
        {'cap': dict(col=SCARLET, rib=tone(SCARLET, -0.5)), 'creel': {},
         'skirt': dict(col=OILSKIN, kind='coat', len=16, open=INDIGO, shine=True, folds=True)},
        note='fisherman in an ochre oilskin and red knit cap')
    add('fisher', 'old',
        oldman('tan', 'white', 'crop', 'grey', beard_col=HAIR['white'], beard_side=True, brows=(220, 216, 210), blush=0.3),
        dict(col=OAT, neck='high', neck_trim=OAT, vest=dict(kind='coat', col=OILSKIN_G, buttons=(210, 200, 170)), shine=True),
        dict(col=OILSKIN_G, len='long', cuff=tone(OILSKIN_G, -0.8)),
        dict(col=(80, 76, 70), boots=dict(col=(60, 62, 56), kind='tall', top=9)),
        {'souwester': dict(col=OILSKIN), 'beard': dict(style='full', col=HAIR['white']), 'creel': {},
         'skirt': dict(col=OILSKIN_G, kind='coat', len=16, open=OAT, shine=True)},
        note="old fisherman with a white beard and a sou'wester")
    add('fisher', 'woman',
        woman('dark', 'black', 'tied', 'dark', hat_shadow=1, nose='broad'),
        dict(col=(150, 60, 50), neck='high', neck_trim=tone((150, 60, 50), 0.4),
             vest=dict(kind='coat', col=(176, 136, 60), buttons=(220, 210, 180)), shine=True),
        dict(col=(176, 136, 60), len='long', cuff=tone((176, 136, 60), -0.8)),
        dict(col=(62, 64, 74), boots=dict(col=BOOT_B, kind='tall', top=9)),
        {'cap': dict(col=WATCH_D, rib=tone(WATCH_D, 0.6)), 'creel': {}, 'ponytail': dict(tie=(150, 60, 50), plain=True),
         'skirt': dict(col=(176, 136, 60), kind='coat', len=16, open=(150, 60, 50), shine=True)},
        note='fisherwoman in an oilskin and navy knit cap')

    # ---------------------------------------------------------------- children: bright, simple, freckled, messy
    add('child', 'child',
        boy('fair', 'ginger', 'messy', 'blue', freckles=2, blush=0.3, layer='fringe'),
        dict(col=SKY, neck='crew', belt=LEATHER, buckle=False, belt_y=17),
        dict(col=SKY, len='short'),
        dict(col=BROWN, kind='shorts', boots=dict(col=BOOT, kind='shoe')),
        note='ginger boy in a sky-blue tunic')
    add('child', 'child',
        boy('dark', 'black', 'curly', 'dark', blush=0.15, nose='broad', mouth='grin'),
        dict(col=SUN, neck='crew', belt=BRICK, buckle=False, belt_y=17),
        dict(col=SUN, len='short'),
        dict(col=INDIGO, patch=SKY, boots=dict(col=BOOT, kind='shoe')),
        note='curly-haired boy in a yellow tunic')
    add('child', 'child',
        boy('light', 'brown', 'messy', 'hazel', freckles=1, blush=0.25, layer='fringe', layer_front=[4, 5, 3, 5, 4, 3, 5, 4, 3, 4, 5, 3, 4, 5, 4, 3]),
        dict(col=SCARLET, neck='slit', laces=CREAM, belt=LEATHER, buckle=True, belt_y=17),
        dict(col=SCARLET, len='long', cuff=tone(SCARLET, -0.7)),
        dict(col=OLIVE, kind='shorts', stock=(190, 186, 176), boots=dict(col=BOOT_D, kind='shoe')),
        note='boy in a scarlet tunic')
    add('child', 'child',
        girl('fair', 'blond', 'parted', 'blue', blush=0.35, freckles=1),
        dict(col=ROSE, neck='scoop', under=WHITE, waist=15, skirt=ROSE),
        dict(col=ROSE, len='short'),
        dict(col=WHITE, kind='hose', boots=dict(col=(150, 70, 70), kind='shoe')),
        {'pigtails': dict(tie=SKY), 'skirt': dict(col=ROSE, len=16, trim=WHITE)},
        note='girl with blond pigtails in a pink dress')
    add('child', 'child',
        girl('brown', 'black', 'parted', 'dark', blush=0.2),
        dict(col=LEAF, neck='crew', waist=15, skirt=LEAF, buttons=CREAM),
        dict(col=LEAF, len='short'),
        dict(col=CREAM, kind='hose', boots=dict(col=BOOT, kind='shoe')),
        {'hair_bow': dict(col=SCARLET), 'skirt': dict(col=LEAF, len=16)},
        note='girl with a red bow in a green dress')
    add('child', 'child',
        girl('fair', 'chestnut', 'parted', 'green', freckles=2, blush=0.3),
        dict(col=SKY, neck='square', under=WHITE, waist=15, skirt=SKY, bib=dict(col=WHITE, top=5, x0=3, x1=12, strap=WHITE)),
        dict(col=SKY, len='short'),
        dict(col=(200, 196, 186), kind='hose', boots=dict(col=BOOT_D, kind='shoe')),
        {'ponytail': dict(tie=SCARLET), 'skirt': dict(col=SKY, len=16, apron=dict(col=WHITE, len=13, x0=4, x1=15))},
        note='freckled girl with a braid and a white pinafore')

    # ---------------------------------------------------------------- nobles: velvet, gold, capes, plumes, jewels
    add('noble', 'man',
        man('fair', 'chestnut', 'swept', 'grey', beard_col=HAIR['chestnut'], moustache='thin', hat_shadow=1),
        dict(col=BURGUNDY, neck='high', neck_trim=IVORY, vest=dict(kind='doublet', col=BURGUNDY, lining=GOLD, trim=GOLD,
                                                                    below=BURGUNDY), necklace=('chain',)),
        dict(col=BURGUNDY, len='long', cuff=IVORY, ruffle=None, ring=True),
        dict(col=(56, 30, 40), kind='hose', boots=dict(col=BLACK, kind='tall', top=13, cuff_col=(70, 50, 50))),
        {'plumed_hat': dict(col=BURGUNDY, band=GOLD, plume=IVORY, tip=GOLD, edge=tone(BURGUNDY, -0.6)),
         'beard': dict(style='goatee'), 'cape': dict(col=BURGUNDY, lining=GOLD, trim=GOLD),
         'puffs': dict(col=BURGUNDY, lining=GOLD), 'skirt': dict(col=BURGUNDY, kind='trunks', len=9, lining=GOLD)},
        tools={'cane'}, note='nobleman in burgundy and gold')
    add('noble', 'old',
        oldman('brown', 'salt', 'swept', 'dark', beard_col=HAIR['grey'], beard_side=True, brows=(170, 168, 164), hat_shadow=1),
        dict(col=EMERALD, neck='high', neck_trim=GOLD, vest=dict(kind='doublet', col=EMERALD, lining=GOLD, trim=GOLD,
                                                                  below=EMERALD), necklace=('chain', GEM_EMERALD)),
        dict(col=EMERALD, len='long', cuff=GOLD, ring=True),
        dict(col=BLACK, kind='hose', boots=dict(col=BLACK, kind='tall', top=13)),
        {'plumed_hat': dict(col=BLACK, band=GOLD, plume=(60, 170, 110), tip=IVORY, gem=GEM_EMERALD),
         'beard': dict(style='short', col=HAIR['grey']), 'cape': dict(col=BLACK, lining=EMERALD, trim=GOLD),
         'collar': dict(col=(236, 232, 224), kind='fur'), 'puffs': dict(col=EMERALD, lining=GOLD),
         'skirt': dict(col=EMERALD, kind='trunks', len=9, lining=GOLD)},
        tools={'cane'}, note='old lord in emerald with a black cape and ermine')
    add('noble', 'woman',
        woman('porcelain', 'blond', 'tied', 'blue', earrings=GOLD_L),
        dict(col=ROYAL, neck='square', under=IVORY, vest=dict(kind='bodice', col=ROYAL, top=3, trim=GOLD), waist=16,
             skirt=ROYAL, necklace=('pearls', GEM_SAPPHIRE)),
        dict(col=ROYAL, len='long', cuff=GOLD),
        dict(col=IVORY, kind='hose', boots=dict(col=(60, 40, 70), kind='shoe')),
        {'hair_bun': dict(pin=GOLD_L), 'circlet': dict(gem=GEM_SAPPHIRE), 'sleeves': dict(col=IVORY, trim=GOLD),
         'skirt': dict(col=ROYAL, panel=dict(col=IVORY, brocade=GOLD, edge=GOLD), trim=GOLD)},
        tools={'fan'}, note='lady in royal blue')
    add('noble', 'woman',
        woman('brown', 'raven', 'long', 'amber', earrings=GOLD_L),
        dict(col=IVORY, neck='square', under=IVORY, vest=dict(kind='bodice', col=GOLD, top=3, trim=GOLD_L), waist=16, skirt=IVORY,
             necklace=('chain', GEM_EMERALD)),
        dict(col=IVORY, len='long', cuff=GOLD),
        dict(col=IVORY, kind='hose', boots=dict(col=GOLD, kind='shoe')),
        {'hair_long': dict(len=16), 'circlet': dict(gem=GEM_EMERALD), 'sleeves': dict(col=IVORY, trim=GOLD),
         'skirt': dict(col=IVORY, panel=dict(col=GOLD, brocade=GOLD_L, edge=GOLD_L), trim=GOLD)},
        tools={'fan'}, note='lady in ivory and gold')

    # ---------------------------------------------------------------- innkeepers: aprons, rolled sleeves, a rag
    add('innkeeper', 'man',
        man('rosy', 'brown', 'balding', 'brown', beard_col=HAIR['brown'], beard_side=True, blush=0.45, brow_style='bushy',
            nose='broad'),
        dict(col=LINEN, neck='slit', laces=OAT, vest=dict(kind='vest', col=BROWN, buttons=(200, 180, 130)), belt=LEATHER_D,
             buckle=True),
        dict(col=LINEN, len='rolled', hairy=(100, 64, 40)),
        dict(col=UMBER, boots=dict(col=BOOT)),
        {'beard': dict(style='long'), 'belly': {}, 'apron': dict(col=CREAM, hem=OAT, folds=(3, 9)), 'rolls': dict(col=LINEN),
         'shoulder_rag': {}},
        note='big-bearded innkeeper')
    add('innkeeper', 'woman',
        woman('tan', 'dark', 'parted', 'brown', blush=0.3, layer='kerchief', layer_col=MUSTARD, layer_pattern='check',
              layer_col2=BRICK),
        dict(col=CREAM, neck='square', vest=dict(kind='bodice', col=BRICK, top=4), waist=16, skirt=BRICK),
        dict(col=CREAM, len='rolled'),
        dict(col=CHARCOAL, kind='hose', boots=dict(col=BOOT, kind='shoe')),
        {'hair_bun': {}, 'rolls': dict(col=CREAM), 'shoulder_rag': dict(col=CREAM, check=WOAD),
         'skirt': dict(col=BRICK, apron=dict(col=CREAM, len=20, x0=4, x1=15, stains=(150, 110, 70)))},
        note='innkeeper with a bun and a checked kerchief')

    # ---------------------------------------------------------------- bards: teal and plum motley, feathered caps
    add('bard', 'man',
        man('dark', 'black', 'curly', 'amber', moustache='thin', beard_col=HAIR['black'], hat_shadow=1),
        dict(col=TEAL, pattern=('harlequin', PLUM), pattern_dot=GOLD, neck='crew', neck_trim=GOLD, belt=LEATHER, buckle=GOLD),
        dict(col=TEAL, col_l=PLUM, len='long', cuff=GOLD),
        dict(col=PLUM, col_l=TEAL, kind='hose', boots=dict(col=(80, 50, 40), kind='tall', top=14)),
        {'bard_cap': dict(col=PLUM, band=GOLD, feather=GOLD_L, tip=TEAL), 'puffs': dict(col=TEAL, lining=PLUM, col_l=PLUM, lining_l=TEAL),
         'skirt': dict(col=TEAL, kind='trunks', len=7, lining=PLUM)},
        note='bard in teal and plum motley')
    add('bard', 'woman',
        woman('fair', 'copper', 'long', 'green', freckles=1, hat_shadow=1),
        dict(col=TEAL, pattern=('quarter', PLUM), neck='square', under=IVORY, neck_trim=GOLD, waist=16, skirt=PLUM, belt=GOLD,
             belt_y=15, buckle=False),
        dict(col=PLUM, col_l=TEAL, len='long', cuff=GOLD),
        dict(col=TEAL, kind='hose', boots=dict(col=(80, 50, 40), kind='shoe')),
        {'bard_cap': dict(col=TEAL, band=GOLD, feather=PLUM, tip=GOLD_L), 'hair_long': dict(len=10, shape='v'),
         'skirt': dict(col=PLUM, pattern=('panels', TEAL, 4), trim=GOLD)},
        note='bard in a teal and plum motley dress')

    # ---------------------------------------------------------------- priests: white and gold, stoles, the book
    add('priest', 'man',
        man('deep', 'grey', 'shaved', 'brown', beard_col=HAIR['grey'], beard_side=True, nose='broad', brows=(150, 148, 146)),
        dict(col=WHITE, neck='crew', robe_trim=GOLD, stole=SCARLET, cord=GOLD, belt_y=16),
        dict(col=WHITE, len='long', cuff=GOLD),
        dict(col=WHITE, kind='hose', boots=dict(col=(120, 90, 60), kind='slipper')),
        {'skullcap': dict(col=WHITE, trim=GOLD), 'beard': dict(style='short', col=HAIR['grey']),
         'sleeves': dict(col=WHITE, trim=GOLD, lining=SCARLET),
         'skirt': dict(col=WHITE, kind='robe', stole=SCARLET, stole_len=17, trim=GOLD)},
        note='priest in white and gold with a scarlet stole')
    add('priest', 'woman',
        woman('fair', 'brown', 'tied', 'grey', layer='wimple', layer_col=WHITE, no_ears=True),
        dict(col=WHITE, neck='high', neck_trim=WHITE, robe_trim=GOLD, stole=SCARLET, cord=GOLD, belt_y=16,
             necklace=('chain', GEM_AMBER)),
        dict(col=WHITE, len='long', cuff=GOLD),
        dict(col=WHITE, kind='hose', boots=dict(col=(120, 90, 60), kind='slipper')),
        {'veil': dict(col=WHITE, edge=GOLD), 'sleeves': dict(col=WHITE, trim=GOLD, lining=SCARLET),
         'skirt': dict(col=WHITE, kind='robe', stole=SCARLET, stole_len=17, trim=GOLD)},
        note='priestess in a white veil and wimple')
    return L


LOOKS = _looks()


# ================================================================ sheets

BG = (52, 58, 72, 255)


def sheet_all(model, glow, scale=5, cell=(92, 196)):
    """Every look, three-quarter front and back, a row per role, each with the tools it idles with."""
    cw, ch = cell
    cols = max(len(v) for v in LOOKS.values())
    W, H = 12 + cols * (2 * cw + 10), 10 + len(ROLES) * (ch + 14)
    sheet = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(sheet)
    for r, role in enumerate(ROLES):
        y = 6 + r * (ch + 14)
        for i, look in enumerate(LOOKS[role]):
            tex = look_texture(role, i, model)
            x = 8 + i * (2 * cw + 10)
            for j, yaw in enumerate((25, 205)):
                im = render_look(model, look, tex, glow, yaw=yaw, pitch=-8, scale=scale, size=(cw, ch), y_shift=-2)
                sheet.paste(im, (x + j * cw, y))
            d.text((x + 3, y + ch), f'{look.texture} ({look.timbre})', fill=(226, 228, 236, 255))
    return sheet


def sheet_role(model, glow, role, scale=8, cell=(150, 300)):
    """One role's looks big: front, three-quarter, side, back, and the face close up."""
    cw, ch = cell
    looks = LOOKS[role]
    W, H = 5 * cw + 10, len(looks) * (ch + 4) + 6
    sheet = Image.new('RGBA', (W, H), BG)
    d = ImageDraw.Draw(sheet)
    for i, look in enumerate(looks):
        tex = look_texture(role, i, model)
        y = 4 + i * (ch + 4)
        for j, yaw in enumerate((0, 30, 90, 180)):
            im = render_look(model, look, tex, glow, yaw=yaw, pitch=-6, scale=scale, size=(cw, ch), y_shift=-1.5)
            sheet.paste(im, (4 + j * cw, y))
        face = render_look(model, look, tex, glow, yaw=20, pitch=-6, scale=17 if role != 'child' else 21, size=(cw, ch),
                           only={'head'}, y_shift=-1.0 if role != 'child' else -12.5)
        sheet.paste(face, (4 + 4 * cw + 2, y))
        d.text((8, y + 4), f'{look.texture} ({look.timbre})', fill=(226, 228, 236, 255))
        d.text((8, y + ch - 14), look.note, fill=(190, 194, 206, 255))
    return sheet


# ================================================================ output

KT_HEADER = '// generated by art/scripts/townsfolk.py, edit the script, not this file\n'


def kotlin_mesh(model):
    return KT_HEADER + f'''package io.github.vaspyyy.guildmark.client.townsfolk

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition

/**
 * The townsfolk model: the humanoid at its standard pivots (head with the hat layer, body, arms, legs), every look
 * piece ([TownsfolkLooks.OPTIONAL]) and every held tool, box UV on a [TEX_WIDTH] x [TEX_HEIGHT] texture. The look
 * textures ship at {SCALE}x that size, painted at the higher resolution.
 */
object TownsfolkMesh {{
    const val TEX_WIDTH = {model.tex_w}
    const val TEX_HEIGHT = {model.tex_h}

    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun createBodyLayer(): LayerDefinition {{
{model.kotlin()}
    }}
}}
'''


def optional_pieces():
    """Every look piece: the model part names a look shows or hides (a piece's children follow it)."""
    return list(PIECES)


def kotlin_looks():
    def q(v):
        return '"' + v + '"'
    rows = []
    for role in ROLES:
        looks = []
        for look in LOOKS[role]:
            parts = ', '.join(q(n) for n in sorted(look.parts))
            looks.append(f'            Look({q(look.texture)}, {q(look.timbre)}, setOf({parts})),')
        rows.append(f'        {q(role)} to listOf(\n' + '\n'.join(looks) + '\n        ),')
    opt = optional_pieces()
    opt_lines, line = [], '        '
    for n in opt:
        piece = q(n) + ', '
        if len(line) + len(piece) > 118:
            opt_lines.append(line.rstrip())
            line = '        '
        line += piece
    opt_lines.append(line.rstrip())
    return KT_HEADER + '''package io.github.vaspyyy.guildmark.client.townsfolk

/**
 * Every townsfolk look: by role, the texture (textures/entity/townsfolk/<texture>.png), the voice it speaks in
 * (man, woman, old or child) and the parts it shows: its look pieces and anything it carries as part of the look
 * (a noble's cane or fan, a basket).
 */
object TownsfolkLooks {
    class Look(val texture: String, val timbre: String, val parts: Set<String>)

    /** Every look piece some look shows and others hide (never the tools the lead toggles). */
    val OPTIONAL: Set<String> = setOf(
''' + '\n'.join(opt_lines) + '''
    )

    /** By role id (lowercase role name), in look order. */
    val BY_ROLE: Map<String, List<Look>> = mapOf(
''' + '\n'.join(rows) + '''
    )
}
'''


def bbmodel_json(model, look):
    """The Blockbench model with [look]'s texture; every piece and tool that look does not show is hidden. The
    texture is HD: its pixel size is SCALE times the UV size. UUIDs are derived, so a rebuild is byte-identical."""
    tex = look_texture(look.role, look.n, model)
    bb = model.bbmodel(tex, look.texture)
    shown = set(look.parts) | IDLE_TOOLS.get(look.role, set())
    optional = set(PIECES) | set(tool_names())

    def hide(group, hidden):
        hidden = hidden or (group['name'] in optional and group['name'] not in shown)
        if hidden:
            group['visibility'] = False
        for ch in group['children']:
            if isinstance(ch, dict):
                hide(ch, hidden)
            elif hidden:
                hidden_elements.add(ch)
    hidden_elements = set()
    for g in bb['outliner']:
        hide(g, False)
    for el in bb['elements']:
        if el['uuid'] in hidden_elements:
            el['visibility'] = False
    t = bb['textures'][0]
    t['width'], t['height'] = tex.width, tex.height
    text = json.dumps(bb)
    import re
    ids = {}
    for m in re.finditer(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', text):
        ids.setdefault(m.group(0), str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/townsfolk/{len(ids)}')))
    return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', lambda m: ids[m.group(0)], text)


def check(model):
    """Every look's parts exist in the model; pieces it shows have painters; nothing a look shows is unpainted."""
    names = {p.name for p in model.all_parts()}
    for role in ROLES:
        for look in LOOKS[role]:
            missing = look.parts - names
            if missing:
                raise SystemExit(f'townsfolk: {look.texture} shows parts the model lacks: {sorted(missing)}')
            if look.timbre not in ('man', 'woman', 'old', 'child'):
                raise SystemExit(f'townsfolk: {look.texture} has no voice {look.timbre}')


def main(assets, previews, models):
    model = build_model()
    check(model)
    rows = pack_uv(model)
    tex_dir = os.path.join(assets, 'textures', 'entity', 'townsfolk')
    os.makedirs(tex_dir, exist_ok=True)
    os.makedirs(previews, exist_ok=True)
    os.makedirs(models, exist_ok=True)
    os.makedirs(KOTLIN_DIR, exist_ok=True)
    for role in ROLES:
        for n, look in enumerate(LOOKS[role]):
            look_texture(role, n, model).save(os.path.join(tex_dir, look.texture + '.png'), optimize=True)
    glow = glow_texture(model)
    glow.save(os.path.join(tex_dir, 'townsfolk_glow.png'), optimize=True)
    with open(os.path.join(KOTLIN_DIR, 'TownsfolkMesh.kt'), 'w') as f:
        f.write(kotlin_mesh(model))
    with open(os.path.join(KOTLIN_DIR, 'TownsfolkLooks.kt'), 'w') as f:
        f.write(kotlin_looks())
    with open(os.path.join(models, 'townsfolk.bbmodel'), 'w') as f:
        f.write(bbmodel_json(model, LOOKS['noble'][0]))
    sheet = sheet_all(model, glow)
    sheet.save(os.path.join(previews, 'townsfolk_looks.png'))
    sheet.save(os.path.join(models, 'townsfolk_preview.png'), optimize=True)
    for role in ROLES:
        sheet_role(model, glow, role).save(os.path.join(previews, f'townsfolk_{role}.png'))
    n = sum(len(v) for v in LOOKS.values())
    print(f'townsfolk: {n} looks, texture {model.tex_w}x{model.tex_h} (PNGs {model.tex_w * SCALE}x{model.tex_h * SCALE}), '
          f'{rows} rows packed, {len(list(model.all_cubes()))} cubes')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('usage: python3 -B art/scripts/townsfolk.py <assets dir> <preview dir> <models dir>')
    main(*sys.argv[1:])
