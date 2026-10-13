"""
Held props for the townsfolk, as modelkit parts the townsfolk model (art/scripts/townsfolk.py) hangs under its arms
and body, so the clips (art/scripts/townsfolk_anims.py) move them. The lead's code shows and hides them by part name.

    tool_parts()    -> {'right_arm' | 'left_arm' | 'body': [Part, ...]}   (fresh parts on every call)
    tool_painters() -> {cube name: painter p(img, face, x, y, w, h)}      (any face size: 1x or the 2x HD texture)
    GLOW_CUBES      -> cube names whose painted pixels are emissive (the hot iron, the lantern's panes)

Model space as everywhere: pixels, y down, the character faces -z, its right hand is at -x. Right-hand grip centre
(-1, 10, 0) in right_arm space, left-hand grip (1, 10, 0) in left_arm space. Each tool is modelled along its own +y
from the grip; its rest rotation is chosen for the clips that use it (see TOOL_NOTES). Every cube is named t_*.

The painters draw at whatever resolution they are handed: wood grain, iron with bevel highlights, the glowing bar
and the lantern's flame are drawn per texel, so the 2x HD texture gets twice the detail rather than a blown-up 1x.
Noise is seeded from the cube name, the face and the size, so reruns are bit-identical.
"""
import math, os, sys, zlib
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from modelkit import Cube, Part

RIGHT_GRIP = (-1.0, 10.0, 0.0)
LEFT_GRIP = (1.0, 10.0, 0.0)

# ================================================================== palette

WOOD = (150, 110, 68); WOOD_D = (104, 70, 42); WOOD_L = (186, 146, 98)            # ash handles
OAK = (132, 92, 56); OAK_D = (88, 58, 34); OAK_L = (166, 124, 80)                 # mugs, crates
PALE = (196, 166, 118); PALE_D = (150, 120, 80); PALE_L = (222, 198, 150)         # the peel, broom handle
EBONY = (52, 34, 30); EBONY_D = (30, 20, 18); EBONY_L = (96, 66, 54)              # the noble's cane
LUTE = (168, 96, 46); LUTE_D = (112, 60, 28); LUTE_L = (206, 140, 74)             # the lute's bowl
SPRUCE = (226, 196, 140); SPRUCE_D = (186, 150, 96)                               # its soundboard
IRON = (82, 84, 94); IRON_D = (46, 48, 56); IRON_L = (150, 156, 170)
STEEL = (168, 174, 186); STEEL_D = (112, 118, 132); STEEL_L = (230, 234, 242)
GOLD = (214, 170, 62); GOLD_D = (150, 108, 34); GOLD_L = (252, 222, 128)
HOT = [(122, 30, 18), (196, 52, 18), (246, 112, 26), (255, 176, 58), (255, 228, 140), (255, 252, 230)]
GLASS = [(150, 70, 20), (232, 132, 40), (255, 190, 80), (255, 232, 150), (255, 252, 228)]
STRAW = (212, 178, 92); STRAW_D = (164, 128, 56); STRAW_L = (240, 214, 140)
WICKER = (182, 136, 74); WICKER_D = (128, 90, 44); WICKER_L = (214, 176, 112)
BURLAP = (178, 148, 104); BURLAP_D = (138, 110, 72); BURLAP_L = (206, 180, 136)
LINEN = (226, 220, 204); LINEN_D = (180, 172, 154)
LEATHER = (112, 36, 34); LEATHER_D = (72, 22, 22); LEATHER_L = (150, 60, 52)
PAPER = (238, 228, 200); PAPER_D = (196, 184, 152)
CLAY = (178, 100, 62); CLAY_D = (124, 64, 38); CLAY_L = (212, 140, 96); GLAZE = (92, 120, 128); GLAZE_L = (136, 168, 172)
ALE = (198, 132, 42); FOAM = (248, 242, 222); FOAM_D = (222, 210, 182)
CORK = (192, 150, 104); CORK_D = (150, 110, 72)
SILK = (176, 44, 64); SILK_L = (226, 104, 120); SILK_D = (120, 26, 44); LACE = (246, 240, 230)
LINE = (232, 236, 240)
APPLE = (196, 40, 36); APPLE_L = (236, 96, 80); LEAF = (84, 132, 52); LEAF_D = (56, 96, 36)
BREAD = (204, 146, 76); BREAD_L = (232, 186, 112); BREAD_D = (150, 96, 44)
CLEAR = (0, 0, 0, 0)

# How each face catches the light, as a brightness factor baked into the texture (the game shades faces too)
FACE_K = {'top': 1.06, 'bottom': 0.82, 'front': 1.0, 'back': 0.94, 'right': 0.97, 'left': 0.97}

# ================================================================== painting kit (float arrays, 0..255, RGBA)

def _rng(key, face, w, h):
    return np.random.default_rng(zlib.crc32(f'{key}|{face}|{w}x{h}'.encode()))


def solid(w, h, col):
    a = np.zeros((h, w, 4), float)
    a[..., :3] = col[:3]
    a[..., 3] = 255
    return a


def mix(a, b, t):
    t = np.asarray(t, float)
    return np.asarray(a, float) * (1 - t) + np.asarray(b, float) * t


def uv(w, h):
    """Texel centres in 0..1 across (u) and down (v)."""
    u = (np.arange(w) + 0.5) / w
    v = (np.arange(h) + 0.5) / h
    return np.meshgrid(u, v)


# Which texture direction a cube axis runs along on each face ('u' across, 'v' down, None: the axis points at you)
AXIS_ON_FACE = {
    'x': {'front': 'u', 'back': 'u', 'top': 'u', 'bottom': 'u', 'right': None, 'left': None},
    'y': {'front': 'v', 'back': 'v', 'right': 'v', 'left': 'v', 'top': None, 'bottom': None},
    'z': {'right': 'u', 'left': 'u', 'top': 'v', 'bottom': 'v', 'front': None, 'back': None},
}


def shade_edges(a, k=0.18, width=None):
    """Darken the rim of a face a little (soft occlusion where faces meet), lighten its top-left edge."""
    h, w = a.shape[:2]
    width = width or max(1, min(w, h) // 6)
    if min(w, h) < 3:
        return a
    ys, xs = np.mgrid[0:h, 0:w]
    d = np.minimum.reduce([xs, ys, w - 1 - xs, h - 1 - ys]).astype(float)
    f = np.clip(d / width, 0, 1)
    a[..., :3] *= (1 - k) + k * f[..., None]
    return a


def grain(w, h, along, base, dark, light, rng, streak=0.45, knots=0, rings=True):
    """Wood: streaks running [along] the face ('u' or 'v'), fine fibres, a few knots; end grain when along is None."""
    if along is None:
        a = solid(w, h, base)
        if rings and min(w, h) >= 2:
            U, V = uv(w, h)
            r = np.hypot(U - 0.42, V - 0.46) * max(w, h)
            ring = (np.sin(r * 2.4) * 0.5 + 0.5) ** 2
            a[..., :3] = mix(base, dark, 0.15 + 0.45 * ring[..., None])
        a[..., :3] *= 1 + rng.normal(0, 0.035, (h, w, 1))
        return a
    n_across = w if along == 'v' else h
    n_along = h if along == 'v' else w
    # one tone per fibre line across the grain, wandering slowly along it
    tone = rng.normal(0, 1, n_across)
    tone = np.convolve(np.pad(tone, 1, mode='edge'), [0.25, 0.5, 0.25], mode='valid')
    wander = np.cumsum(rng.normal(0, 0.18, n_along))
    wander -= wander.mean()
    T = tone[None, :] + 0.25 * np.sin(np.arange(n_along)[:, None] * 0.7 / max(1, n_along / 8) + tone[None, :] * 3)
    T = T * streak + 0.15 * wander[:, None] / (1 + abs(wander).max())
    fine = rng.normal(0, 0.08, (n_along, n_across))
    t = np.clip(T + fine, -1.2, 1.2)
    if along == 'u':
        t = t.T
    a = solid(w, h, base)
    a[..., :3] = np.where(t[..., None] < 0, mix(base, dark, -t[..., None] * 0.8), mix(base, light, t[..., None] * 0.6))
    # dark grain lines
    lines = rng.random(n_across) < 0.18
    L = np.repeat(lines[None, :], n_along, 0)
    if along == 'u':
        L = L.T
    a[..., :3] = np.where(L[..., None], mix(a[..., :3], dark, 0.55), a[..., :3])
    for _ in range(knots):
        if min(w, h) < 4:
            break
        cx, cy = rng.uniform(0.2, 0.8) * w, rng.uniform(0.2, 0.8) * h
        ys, xs = np.mgrid[0:h, 0:w]
        rr = np.hypot((xs + 0.5 - cx) / max(1.0, w / 8), (ys + 0.5 - cy) / max(1.0, h / 8))
        a[..., :3] = np.where(rr[..., None] < 1.0, mix(a[..., :3], dark, 0.7 * (1 - rr[..., None])), a[..., :3])
    return a


def metal(w, h, base, dark, light, rng, bevel=True, wear=0.06, polish=None):
    """Iron or steel: a mottled base, a light bevel along the top and left edges, a dark lower edge, worn specks."""
    a = solid(w, h, base)
    m = rng.normal(0, 1, (h, w))
    if min(w, h) >= 3:
        m = (m + np.roll(m, 1, 0) + np.roll(m, 1, 1) + np.roll(m, -1, 0)) / 3
    a[..., :3] = mix(base, dark if True else base, np.clip(-m * 0.22, 0, 0.5)[..., None])
    a[..., :3] = np.where((m > 1.1)[..., None], mix(a[..., :3], light, 0.35), a[..., :3])
    if bevel and min(w, h) >= 2:
        e = max(1, min(w, h) // 5)
        a[:e, :, :3] = mix(a[:e, :, :3], light, 0.55)
        a[:, :e, :3] = mix(a[:, :e, :3], light, 0.35)
        a[-e:, :, :3] = mix(a[-e:, :, :3], dark, 0.5)
        a[:, -e:, :3] = mix(a[:, -e:, :3], dark, 0.3)
    specks = rng.random((h, w)) < wear
    a[..., :3] = np.where(specks[..., None], mix(a[..., :3], light, 0.6), a[..., :3])
    if polish is not None:
        a[..., :3] = mix(a[..., :3], light, polish)
    return a


def weave(w, h, base, dark, light, rng, cell=None):
    """Basketry or sackcloth: an over-under check of strands, each strand lit along its middle."""
    cell = cell or max(2, min(w, h) // 3)
    ys, xs = np.mgrid[0:h, 0:w]
    cx, cy = xs // cell, ys // cell
    over = (cx + cy) % 2 == 0
    fu = (xs % cell + 0.5) / cell
    fv = (ys % cell + 0.5) / cell
    hump = np.where(over, np.sin(fv * math.pi), np.sin(fu * math.pi))
    a = solid(w, h, base)
    a[..., :3] = mix(dark, light, (0.25 + 0.75 * hump)[..., None])
    a[..., :3] *= 1 + rng.normal(0, 0.05, (h, w, 1))
    return a


def noise_fill(w, h, base, rng, amount=0.05):
    a = solid(w, h, base)
    a[..., :3] *= 1 + rng.normal(0, amount, (h, w, 1))
    return a


def lit(a, face):
    a[..., :3] *= FACE_K.get(face, 1.0)
    return a


def painter(key, fn, light=True):
    """A modelkit painter from fn(face, w, h, rng) -> (h, w, 4) array, sized to whatever rect it is handed."""
    def p(img, face, x, y, w, h):
        if w <= 0 or h <= 0:
            return
        a = fn(face, w, h, _rng(key, face, w, h))
        if a is None:
            return
        if light:
            a = lit(a, face)
        img.paste(Image.fromarray(np.clip(np.round(a), 0, 255).astype(np.uint8), 'RGBA'), (x, y))
    return p


def ramp(colors, t):
    """Sample a colour ramp (list of RGB) at t in 0..1 (arrays welcome)."""
    t = np.clip(np.asarray(t, float), 0, 1) * (len(colors) - 1)
    i = np.clip(np.floor(t).astype(int), 0, len(colors) - 2)
    f = (t - i)[..., None]
    C = np.array(colors, float)
    return C[i] * (1 - f) + C[i + 1] * f


def disc_mask(w, h, cx=0.5, cy=0.5, r=0.5):
    U, V = uv(w, h)
    return np.hypot((U - cx) * w / max(w, h), (V - cy) * h / max(w, h)) <= r * min(w, h) / max(w, h)

# ================================================================== the painters, one family per tool

def wood_painter(key, base, dark, light, axis, knots=0, streak=0.45, end=None, ends_dark=True):
    """Wood grain running along the cube's [axis]; end grain on the faces the axis points at."""
    def fn(face, w, h, rng):
        along = AXIS_ON_FACE[axis][face]
        a = grain(w, h, along, base, dark, light, rng, streak=streak, knots=knots)
        if along is None and end is not None:
            a[..., :3] = mix(a[..., :3], end, 0.5)
        if along is None and ends_dark:
            a[..., :3] *= 0.86
        return shade_edges(a, 0.16)
    return painter(key, fn)


def iron_painter(key, base=IRON, dark=IRON_D, light=IRON_L, polish_faces=(), soot=0.0, wear=0.06):
    def fn(face, w, h, rng):
        a = metal(w, h, base, dark, light, rng, wear=wear, polish=0.55 if face in polish_faces else None)
        if face in polish_faces and min(w, h) >= 3:
            # a struck face: bright and smooth in the middle, a darker rim round it
            U, V = uv(w, h)
            r = np.maximum(abs(U - 0.5), abs(V - 0.5)) * 2
            a[..., :3] = mix(a[..., :3], STEEL_L, np.clip(0.85 - r, 0, 0.6)[..., None])
            a[..., :3] = np.where((r > 0.8)[..., None], mix(a[..., :3], dark, 0.45), a[..., :3])
        if soot:
            s = rng.random((h, w)) < soot
            a[..., :3] = np.where(s[..., None], mix(a[..., :3], (24, 22, 24), 0.6), a[..., :3])
        return a
    return painter(key, fn)


def gold_painter(key):
    def fn(face, w, h, rng):
        a = metal(w, h, GOLD, GOLD_D, GOLD_L, rng, wear=0.12)
        if min(w, h) >= 2:
            U, V = uv(w, h)
            glint = np.exp(-((U - 0.32) ** 2 + (V - 0.3) ** 2) * 18)
            a[..., :3] = mix(a[..., :3], (255, 246, 200), (glint * 0.8)[..., None])
        return a
    return painter(key, fn)

# ---- the smith ---------------------------------------------------------------------------------------------------

def hammer_head_painter(key):
    """Dark forged iron, its edges worn bright; the striking face (+z, the 'back' face) polished from use. The handle's
    eye shows as wood with an iron wedge on the top and bottom."""
    def fn(face, w, h, rng):
        a = metal(w, h, (74, 76, 86), (40, 42, 50), (160, 166, 180), rng, wear=0.08)
        if face == 'back':
            # the face of the hammer: polished, bright centre, dark rim
            U, V = uv(w, h)
            r = np.maximum(abs(U - 0.5), abs(V - 0.5)) * 2
            a[..., :3] = mix(a[..., :3], (226, 230, 238), np.clip(1.0 - r, 0, 0.85)[..., None])
            a[..., :3] = np.where((r > 0.78)[..., None], mix(a[..., :3], (38, 40, 48), 0.5), a[..., :3])
        if face in ('top', 'bottom') and min(w, h) >= 4:
            # the eye: end grain of the handle, split by a wedge
            U, V = uv(w, h)
            # the handle runs through 1 px from the head's -z end (v runs towards -z on the top, +z on the bottom)
            vc = 0.71 if face == 'top' else 0.29
            eye = (abs(U - 0.5) < 0.26) & (abs(V - vc) < 0.15)
            wood = grain(w, h, None, WOOD, WOOD_D, WOOD_L, rng)
            a[..., :3] = np.where(eye[..., None], wood[..., :3], a[..., :3])
            wedge = eye & (abs(V - vc) < 0.05)
            a[..., :3] = np.where(wedge[..., None], mix(a[..., :3], IRON_L, 0.7), a[..., :3])
        if face in ('right', 'left', 'top', 'bottom') and min(w, h) >= 3:
            # forging marks: faint hammer dimples
            for _ in range(max(1, w * h // 40)):
                cx, cy = rng.uniform(0.1, 0.9) * w, rng.uniform(0.1, 0.9) * h
                ys, xs = np.mgrid[0:h, 0:w]
                d = np.hypot(xs + 0.5 - cx, ys + 0.5 - cy)
                a[..., :3] = np.where((d < max(1.0, min(w, h) / 6))[..., None], mix(a[..., :3], (52, 54, 62), 0.4), a[..., :3])
        return a
    return painter(key, fn)


def tongs_painter(key):
    """Two blackened iron reins side by side (the gap between them clear), lit along their upper edges."""
    def fn(face, w, h, rng):
        a = metal(w, h, (66, 66, 72), (34, 34, 40), (136, 140, 152), rng, wear=0.05)
        if face in ('front', 'back') and w >= 3:
            U, V = uv(w, h)
            gap = (abs(U - 0.5) < 0.17) & (V < 0.86)
            a[gap] = 0
            rim = (abs(U - 0.5) >= 0.17) & ((U < 0.2) | (U > 0.8))
            a[..., :3] = np.where(rim[..., None] & (a[..., 3:] > 0), mix(a[..., :3], (150, 154, 166), 0.35), a[..., :3])
        if face in ('top', 'bottom') and w >= 3:
            U, V = uv(w, h)
            a[abs(U - 0.5) < 0.17] = 0
        return a
    return painter(key, fn)


def hot_iron_painter(key):
    """A bar at forging heat: white-yellow where the hammer works it, cooling to orange and a dull red at the jaws.
    Runs along the cube's y axis (the jaws at the top, the worked end at the bottom)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('top',):
            t = np.full((h, w), 0.12)
        elif face == 'bottom':
            t = np.full((h, w), 0.95)
        else:
            t = 0.05 + 0.95 * V ** 0.8
        # scale and flicker: a crust of dark scale flecks on the cooler part, bright sparkle near the end
        core = 1 - np.clip(np.maximum(abs(U - 0.5), 0) * 1.4, 0, 1) * 0.25
        tt = np.clip(t * core + rng.normal(0, 0.035, (h, w)), 0, 1)
        a = np.zeros((h, w, 4), float)
        a[..., :3] = ramp(HOT, tt)
        scale = (rng.random((h, w)) < 0.12) & (tt < 0.55)
        a[..., :3] = np.where(scale[..., None], mix(a[..., :3], (70, 22, 14), 0.55), a[..., :3])
        a[..., 3] = 255
        return a
    return painter(key, fn, light=False)

# ---- the baker -----------------------------------------------------------------------------------------------------

def peel_blade_painter(key):
    """A thin paddle of pale wood, grain along it, dusted with flour and scorched along its leading edge."""
    def fn(face, w, h, rng):
        along = AXIS_ON_FACE['y'][face]
        a = grain(w, h, along if along else 'u', PALE, PALE_D, PALE_L, rng, streak=0.35)
        U, V = uv(w, h)
        if face in ('front', 'back'):
            # rounded shoulders where it meets the pole, the far end squared off and scorched
            corner = (V < 0.18) & (abs(U - 0.5) > 0.5 - (0.18 - V) * 1.6 - 0.2)
            a[corner & (V < 0.12) & (abs(U - 0.5) > 0.36)] = 0
            burn = np.clip((V - 0.8) / 0.2, 0, 1) * (0.5 + 0.5 * rng.random((h, w)))
            a[..., :3] = mix(a[..., :3], (70, 42, 22), (burn * 0.75)[..., None])
            flour = rng.random((h, w)) < 0.09 + 0.10 * (1 - V)
            a[..., :3] = np.where(flour[..., None] & (a[..., 3:] > 0), mix(a[..., :3], (250, 248, 240), 0.75), a[..., :3])
        elif face == 'bottom':
            a[..., :3] = mix(a[..., :3], (70, 42, 22), 0.6)
        return shade_edges(a, 0.12)
    return painter(key, fn)

# ---- the guard -----------------------------------------------------------------------------------------------------

def spear_head_painter(key):
    """A leaf-shaped steel blade with a raised midrib, drawn on a flat cube (clear outside the leaf)."""
    def fn(face, w, h, rng):
        a = metal(w, h, STEEL, STEEL_D, STEEL_L, rng, bevel=False, wear=0.05)
        U, V = uv(w, h)
        if face in ('front', 'back', 'right', 'left'):
            # leaf: widest a third of the way up from the socket (the cube's bottom), a point at the top
            prof = np.where(V < 0.72, np.sin(np.clip(V / 0.72, 0, 1) * math.pi * 0.5) ** 0.7, 1 - (V - 0.72) / 0.28 * 0.62)
            half = 0.5 * prof
            inside = abs(U - 0.5) <= half
            a[~inside] = 0
            rib = abs(U - 0.5) < max(0.08, 0.6 / w)
            a[..., :3] = np.where(rib[..., None], mix(a[..., :3], STEEL_L, 0.7), a[..., :3])
            edge = inside & (abs(U - 0.5) > half - max(0.1, 1.0 / w))
            a[..., :3] = np.where(edge[..., None], mix(a[..., :3], (246, 248, 252), 0.6), a[..., :3])
            side = (U < 0.5) & ~rib
            a[..., :3] = np.where(side[..., None], a[..., :3] * 0.86, a[..., :3])
        return a
    return painter(key, fn)


def lantern_glass_painter(key):
    """Warm glass panes lit from inside: a flame standing in the middle of each pane, its glow fading out to the
    corners. Every pixel is emissive (GLOW_CUBES); the iron frame is separate cubes."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = np.zeros((h, w, 4), float)
        a[..., 3] = 255
        if face in ('top', 'bottom'):
            a[..., :3] = ramp(GLASS, 0.55 + 0.2 * (1 - np.hypot(U - 0.5, V - 0.5) * 2))
            return a
        glow = 1 - np.clip(np.hypot((U - 0.5) * 1.3, (V - 0.62) * 1.0) * 1.6, 0, 1)
        t = 0.32 + 0.38 * glow
        # the flame: a teardrop, widest low, its tip up
        fy = np.clip((V - 0.22) / 0.62, 0, 1)
        half = 0.22 * np.sin(np.clip(fy, 0, 1) * math.pi * 0.85) ** 0.8
        flame = (abs(U - 0.5) < half) & (V > 0.22) & (V < 0.84)
        core = (abs(U - 0.5) < half * 0.5) & (V > 0.45) & (V < 0.8)
        t = np.where(flame, 0.82, t)
        t = np.where(core, 1.0, t)
        a[..., :3] = ramp(GLASS, t + rng.normal(0, 0.02, (h, w)))
        # the wick at the foot of the flame
        wick = (abs(U - 0.5) < max(0.06, 0.5 / w)) & (V > 0.84) & (V < 0.95)
        a[..., :3] = np.where(wick[..., None], (90, 50, 26), a[..., :3])
        return a
    return painter(key, fn, light=False)

# ---- the farmer, the fisher ---------------------------------------------------------------------------------------

def hoe_blade_painter(key):
    """A forged blade: dark iron, its cutting edge ground bright, dirt caked on the back."""
    def fn(face, w, h, rng):
        a = metal(w, h, (88, 86, 90), (50, 48, 52), (156, 158, 166), rng, wear=0.08)
        U, V = uv(w, h)
        # the edge is the far end along +z (the plate's back face, and that end of the other faces)
        if face == 'back':
            a[..., :3] = mix(a[..., :3], STEEL_L, 0.55)
        elif face in ('top', 'bottom'):
            # box UV runs v towards -z on the top face and towards +z on the bottom one
            e = V < 0.22 if face == 'top' else V > 0.78
            a[..., :3] = np.where(e[..., None], mix(a[..., :3], STEEL_L, 0.5), a[..., :3])
        elif face == 'right':
            e = U < 0.2
            a[..., :3] = np.where(e[..., None], mix(a[..., :3], STEEL_L, 0.5), a[..., :3])
        elif face == 'left':
            e = U > 0.8
            a[..., :3] = np.where(e[..., None], mix(a[..., :3], STEEL_L, 0.5), a[..., :3])
        dirt = rng.random((h, w)) < 0.16
        a[..., :3] = np.where(dirt[..., None], mix(a[..., :3], (92, 66, 40), 0.65), a[..., :3])
        return a
    return painter(key, fn)


def rod_painter(key, base=(120, 132, 70), dark=(78, 90, 40), light=(176, 186, 110)):
    """A cane rod: green-gold with darker nodes every few pixels and silk whippings at the guides."""
    def fn(face, w, h, rng):
        along = AXIS_ON_FACE['y'][face]
        a = grain(w, h, along if along else 'v', base, dark, light, rng, streak=0.3, knots=0)
        if along == 'v':
            V = (np.arange(h) + 0.5)
            step = max(3, h // 5)
            node = (V % step) < max(1, step // 6)
            a[node, :, :3] = mix(a[node, :, :3], dark, 0.6)
            whip = (V % (step * 2)) > step * 2 - max(1, step // 4) - 0.5
            a[whip, :, :3] = mix(a[whip, :, :3], (150, 36, 40), 0.6)
        return a
    return painter(key, fn)


def cork_painter(key):
    def fn(face, w, h, rng):
        a = noise_fill(w, h, CORK, rng, 0.06)
        pits = rng.random((h, w)) < 0.16
        a[..., :3] = np.where(pits[..., None], mix(a[..., :3], CORK_D, 0.7), a[..., :3])
        if AXIS_ON_FACE['y'][face] == 'v' and h >= 4:
            a[:max(1, h // 8), :, :3] = mix(a[:max(1, h // 8), :, :3], (60, 40, 30), 0.6)
        return a
    return painter(key, fn)


def reel_painter(key):
    """A small wooden reel: a dark rim, the spool of line showing pale in the middle."""
    def fn(face, w, h, rng):
        a = grain(w, h, 'u', OAK, OAK_D, OAK_L, rng, streak=0.3)
        if face in ('right', 'left') and min(w, h) >= 3:
            U, V = uv(w, h)
            r = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = np.where((r < 0.32)[..., None], mix(LINE, (190, 196, 204), np.clip(r * 2, 0, 1)[..., None]), a[..., :3])
            a[..., :3] = np.where((r < 0.1)[..., None], np.array(IRON_L, float), a[..., :3])
        return shade_edges(a, 0.2)
    return painter(key, fn)


def line_painter(key):
    def fn(face, w, h, rng):
        a = solid(w, h, LINE)
        a[..., :3] *= 1 + rng.normal(0, 0.03, (h, w, 1))
        return a
    return painter(key, fn, light=False)

# ---- the tavern ----------------------------------------------------------------------------------------------------

def staves_painter(key, base=OAK, dark=OAK_D, light=OAK_L, hoops=(0.18, 0.8), top=None):
    """Coopered wood: vertical staves with dark seams between them and iron hoops round them; [top] paints the top."""
    def fn(face, w, h, rng):
        if face == 'top' and top is not None:
            return top(w, h, rng)
        if face in ('top', 'bottom'):
            a = grain(w, h, None, base, dark, light, rng)
            return shade_edges(a, 0.25)
        a = grain(w, h, 'v', base, dark, light, rng, streak=0.5)
        U, V = uv(w, h)
        n = max(2, round(w / max(2, h / 3)))
        seam = ((U * n) % 1.0) < max(0.12, 1.0 / w)
        a[..., :3] = np.where(seam[..., None], mix(a[..., :3], dark, 0.6), a[..., :3])
        for c in hoops:
            band = abs(V - c) < max(0.07, 0.6 / h)
            hoop = metal(w, h, IRON, IRON_D, IRON_L, rng)
            a[..., :3] = np.where(band[..., None], hoop[..., :3], a[..., :3])
            under = (V > c + max(0.07, 0.6 / h)) & (V < c + max(0.12, 1.2 / h))
            a[..., :3] = np.where(under[..., None], a[..., :3] * 0.8, a[..., :3])
        return shade_edges(a, 0.15)
    return painter(key, fn)


def ale_top(w, h, rng):
    """Ale in the mug: a thick head of foam, bubbly, over amber showing at the rim."""
    a = solid(w, h, FOAM)
    U, V = uv(w, h)
    r = np.maximum(abs(U - 0.5), abs(V - 0.5)) * 2
    a[..., :3] = mix(FOAM, FOAM_D, (rng.random((h, w)) * 0.6)[..., None])
    bub = rng.random((h, w)) < 0.12
    a[..., :3] = np.where(bub[..., None], np.array((255, 252, 240), float), a[..., :3])
    a[..., :3] = np.where((r > 0.72)[..., None], mix(OAK_D, OAK, 0.4), a[..., :3])
    return a


def jug_painter(key):
    """Earthenware: terracotta below, a band of blue-green glaze round the shoulder with drips, a dark mouth."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, CLAY, rng, 0.05)
        if face == 'top':
            r = np.maximum(abs(U - 0.5), abs(V - 0.5)) * 2
            a[..., :3] = np.where((r < 0.62)[..., None], mix((40, 26, 20), (70, 44, 30), r[..., None]), mix(GLAZE, GLAZE_L, 0.3))
            return a
        if face == 'bottom':
            return shade_edges(noise_fill(w, h, CLAY_D, rng), 0.2)
        # throwing rings, a glazed shoulder with runs dripping down
        rings = np.sin(V * h * 1.6) * 0.5 + 0.5
        a[..., :3] = mix(a[..., :3], CLAY_D, (rings * 0.12)[..., None])
        drip = rng.uniform(0.3, 0.5, w)
        glazed = V < drip[None, :]
        a[..., :3] = np.where(glazed[..., None], mix(GLAZE, GLAZE_L, np.clip(1 - V * 2.2, 0, 1)[..., None]), a[..., :3])
        a[..., :3] *= (1.12 - 0.3 * abs(U - 0.38))[..., None]
        return shade_edges(a, 0.15)
    return painter(key, fn)


def rag_painter(key):
    """A worn linen cloth: soft folds, a grey stain or two, a frayed hem (clear notches at the bottom)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, LINEN, rng, 0.04)
        fold = np.sin(U * math.pi * 3 + V * 2) * 0.5 + 0.5
        a[..., :3] = mix(a[..., :3], LINEN_D, (fold * 0.35)[..., None])
        for _ in range(2):
            cx, cy = rng.uniform(0.2, 0.8), rng.uniform(0.2, 0.8)
            d = np.hypot(U - cx, V - cy)
            a[..., :3] = np.where((d < 0.16)[..., None], mix(a[..., :3], (150, 140, 120), 0.35), a[..., :3])
        if face in ('front', 'back', 'left', 'right') and h >= 4:
            fray = rng.random(w) < 0.35
            a[-1, fray] = 0
        return a
    return painter(key, fn)

# ---- the bard, the priest, the noble -----------------------------------------------------------------------------

def lute_bowl_painter(key):
    """The lute's round back: ribs of two woods alternating, outlined dark; the soundboard (front) pale spruce with
    a carved rose, a dark bridge and the strings running over it to the neck. The front is rounded off at the
    corners (clear), so the body reads as a pear, not a box."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'front':
            a = grain(w, h, 'u', SPRUCE, SPRUCE_D, (240, 216, 166), rng, streak=0.25)
            # pear outline: round at the bottom-left (the bowl), narrowing to the neck on the right (+x is u... the
            # neck leaves on the +x side, which the front face shows on the right)
            cx = 0.42
            rx = np.where(U < cx, 0.5, 0.5 - (U - cx) * 0.55)
            ry = np.where(U < cx, 0.5, 0.5 - (U - cx) * 0.5)
            ell = ((U - cx) / 0.62) ** 2 + ((V - 0.5) / np.maximum(ry, 0.05)) ** 2 <= 1.0
            ell |= (abs(V - 0.5) <= ry * 0.9) & (U >= cx)
            a[~ell] = 0
            # the rose: a dark ring with fretwork
            r = np.hypot((U - 0.48) * w / h, V - 0.5)
            rose = r < 0.17
            a[..., :3] = np.where(rose[..., None], mix((54, 32, 18), (110, 70, 40), ((np.sin(np.arctan2(V - 0.5, U - 0.48) * 6) > 0.3) & (r < 0.12))[..., None] * 0.6), a[..., :3])
            ring = (r >= 0.17) & (r < 0.21)
            a[..., :3] = np.where(ring[..., None], np.array(LUTE_D, float), a[..., :3])
            # the bridge, low on the bowl side
            bridge = (abs(U - 0.18) < max(0.04, 0.5 / w)) & (abs(V - 0.5) < 0.22)
            a[..., :3] = np.where(bridge[..., None], np.array((60, 34, 20), float), a[..., :3])
            # strings: from the bridge to the neck
            for s in (-0.12, -0.04, 0.04, 0.12):
                st = (abs(V - 0.5 - s) < max(0.02, 0.35 / h)) & (U > 0.18) & ell
                a[..., :3] = np.where(st[..., None], mix(a[..., :3], (250, 246, 236), 0.75), a[..., :3])
            return a
        a = grain(w, h, 'u' if face in ('top', 'bottom', 'back') else 'v', LUTE, LUTE_D, LUTE_L, rng, streak=0.3)
        # ribs: alternate light and dark strips with fine dark lines between
        n = 7
        axis = V if face in ('back',) else U
        rib = ((axis * n) % 1.0)
        a[..., :3] = np.where(((axis * n).astype(int) % 2 == 0)[..., None], a[..., :3], mix(a[..., :3], LUTE_L, 0.35))
        a[..., :3] = np.where((rib < max(0.08, 1.0 / max(w, h)))[..., None], mix(a[..., :3], (50, 26, 12), 0.7), a[..., :3])
        if face == 'back':
            U2 = U
            ell = ((U2 - 0.42) / 0.62) ** 2 + ((V - 0.5) / 0.5) ** 2 <= 1.0
            ell |= (abs(V - 0.5) <= 0.42) & (U2 >= 0.42)
            a[~ell] = 0
        return shade_edges(a, 0.18)
    return painter(key, fn)


def lute_neck_painter(key):
    """The neck: dark wood, pale frets across the fingerboard (front), the strings running along it."""
    def fn(face, w, h, rng):
        along = AXIS_ON_FACE['x'][face]
        a = grain(w, h, along if along else 'v', (92, 56, 30), (60, 34, 18), (128, 84, 50), rng, streak=0.3)
        U, V = uv(w, h)
        if face == 'front':
            frets = ((U * 7) % 1.0) < max(0.1, 1.0 / w)
            a[..., :3] = np.where(frets[..., None], mix(a[..., :3], (226, 214, 186), 0.75), a[..., :3])
            for s in (0.3, 0.7):
                st = abs(V - s) < max(0.06, 0.35 / h)
                a[..., :3] = np.where(st[..., None], mix(a[..., :3], (250, 246, 236), 0.7), a[..., :3])
        return a
    return painter(key, fn)


def pegbox_painter(key):
    def fn(face, w, h, rng):
        along = AXIS_ON_FACE['x'][face]
        a = grain(w, h, along if along else 'v', (92, 56, 30), (60, 34, 18), (128, 84, 50), rng, streak=0.3)
        U, V = uv(w, h)
        if face in ('top', 'bottom', 'right', 'left') and min(w, h) >= 2:
            pegs = ((U * 3) % 1.0 < 0.35) if face in ('top', 'bottom') else ((V * 2) % 1.0 < 0.4)
            a[..., :3] = np.where(pegs[..., None], np.array((236, 222, 190), float), a[..., :3])
        return a
    return painter(key, fn)


def strap_painter(key):
    def fn(face, w, h, rng):
        a = noise_fill(w, h, (96, 58, 34), rng, 0.05)
        along = AXIS_ON_FACE['y'][face]
        if along == 'v' and w >= 3:
            a[:, :1, :3] *= 0.7
            a[:, -1:, :3] *= 0.7
            st = (np.arange(h) % 3 == 0)
            a[st, w // 2:w // 2 + 1, :3] = mix(a[st, w // 2:w // 2 + 1, :3], (220, 200, 150), 0.6)
        return a
    return painter(key, fn)


def book_painter(key):
    """A prayer book: oxblood leather boards with gilt corners and a gilt sun on the front, raised bands across
    the spine (-z), cream page edges (+z, top and bottom) ruled with fine lines."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('back', 'top', 'bottom'):
            # page edges: the back (+z) face shows the fore-edge, top and bottom the page tops
            a = noise_fill(w, h, PAPER, rng, 0.03)
            lines = (np.arange(w if face != 'back' else h) % 2 == 0)
            if face == 'back':
                a[..., :3] = mix(a[..., :3], PAPER_D, 0.15)
            else:
                a[:, lines[:w], :3] = mix(a[:, lines[:w], :3], PAPER_D, 0.35)
            # the boards cover the outer rim
            a[:, :max(1, w // 6), :3] = np.array(LEATHER_D, float) if face != 'back' else a[:, :max(1, w // 6), :3]
            a[:, -max(1, w // 6):, :3] = np.array(LEATHER_D, float) if face != 'back' else a[:, -max(1, w // 6):, :3]
            return a
        a = noise_fill(w, h, LEATHER, rng, 0.06)
        crack = rng.random((h, w)) < 0.06
        a[..., :3] = np.where(crack[..., None], mix(a[..., :3], LEATHER_D, 0.6), a[..., :3])
        if face == 'front':
            # the spine: raised bands
            bands = ((V * 5) % 1.0) < 0.2
            a[..., :3] = np.where(bands[..., None], mix(a[..., :3], GOLD, 0.6), a[..., :3])
            return shade_edges(a, 0.25)
        # the boards (left/right faces): gilt corners and a sun in the middle
        k = 0.2
        corner = ((U < k) | (U > 1 - k)) & ((V < k * 0.8) | (V > 1 - k * 0.8)) & (abs(U - 0.5) + abs(V - 0.5) > 0.62)
        a[..., :3] = np.where(corner[..., None], np.array(GOLD, float), a[..., :3])
        r = np.hypot((U - 0.5) * w / h, V - 0.5)
        ang = np.arctan2(V - 0.5, U - 0.5)
        sun = (r < 0.12) | ((r < 0.22) & ((np.sin(ang * 8) > 0.55)))
        a[..., :3] = np.where(sun[..., None], mix(np.array(GOLD, float), np.array(GOLD_L, float), 0.3), a[..., :3])
        a[..., :3] = np.where(((U < 0.08) | (U > 0.92) | (V < 0.06) | (V > 0.94))[..., None], a[..., :3] * 0.75, a[..., :3])
        return a
    return painter(key, fn)


def cane_painter(key):
    def fn(face, w, h, rng):
        along = AXIS_ON_FACE['y'][face]
        a = grain(w, h, along, EBONY, EBONY_D, EBONY_L, rng, streak=0.25)
        if along == 'v':
            # lacquer: a bright streak down one side
            hi = max(1, w // 4)
            a[:, :hi, :3] = mix(a[:, :hi, :3], (150, 110, 96), 0.45)
        return a
    return painter(key, fn)


def fan_painter(key):
    """An open folding fan: a half-disc of pleated silk (pleats radiating from the rivet at the bottom centre), a lace
    rim and painted flowers; clear outside the half-disc. Drawn on the front and back of a flat cube."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face not in ('front', 'back'):
            a = solid(w, h, SILK_D)
            return a
        x = (U - 0.5) * w / h
        y = 1.0 - V
        r = np.hypot(x, y)
        ang = np.arctan2(x, y)
        a = solid(w, h, SILK)
        inside = (r <= 0.98) & (r >= 0.18) & (abs(ang) <= math.radians(80))
        sticks = (r < 0.18) & (abs(ang) <= math.radians(80))
        pleat = (np.sin(ang * 14) > 0)
        a[..., :3] = np.where(pleat[..., None], np.array(SILK, float), np.array(SILK_D, float) * 1.1)
        rim = (r > 0.84) & inside
        a[..., :3] = np.where(rim[..., None], mix(np.array(LACE, float), np.array(SILK_L, float), (np.sin(ang * 40) > 0.4)[..., None] * 0.4), a[..., :3])
        flower = (abs(r - 0.55) < 0.1) & (np.sin(ang * 5) > 0.7)
        a[..., :3] = np.where((flower & inside)[..., None], np.array(GOLD_L, float), a[..., :3])
        a[..., :3] = np.where(sticks[..., None], np.array(EBONY_L, float), a[..., :3])
        a[~(inside | sticks)] = 0
        return a
    return painter(key, fn)

# ---- carrying ------------------------------------------------------------------------------------------------------

def crate_painter(key):
    """A crate of slats nailed to corner posts, gaps between the slats; on top, apples and a cabbage."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = solid(w, h, (40, 28, 18))
            # fruit: red apples with a highlight and a few green leaves, packed in
            n = max(4, (w * h) // 10)
            ys, xs = np.mgrid[0:h, 0:w]
            for i in range(n):
                cx, cy = rng.uniform(0.12, 0.88) * w, rng.uniform(0.12, 0.88) * h
                rad = max(0.8, min(w, h) * rng.uniform(0.13, 0.2))
                d = np.hypot(xs + 0.5 - cx, ys + 0.5 - cy) / rad
                col = APPLE if rng.random() < 0.7 else (176, 196, 64)
                ball = d < 1
                a[..., :3] = np.where(ball[..., None], mix(np.array(col, float), np.array(col, float) * 0.6, np.clip(d, 0, 1)[..., None] ** 2), a[..., :3])
                hl = np.hypot(xs + 0.5 - cx + rad * 0.3, ys + 0.5 - cy + rad * 0.3) / rad < 0.3
                a[..., :3] = np.where(hl[..., None], mix(a[..., :3], (255, 240, 220), 0.6), a[..., :3])
            rim = (U < 0.1) | (U > 0.9) | (V < 0.1) | (V > 0.9)
            a[..., :3] = np.where(rim[..., None], np.array(OAK, float), a[..., :3])
            return a
        along = 'u'
        a = grain(w, h, along, (170, 128, 80), (116, 80, 46), (204, 166, 112), rng, streak=0.4)
        if face == 'bottom':
            return shade_edges(a, 0.2)
        n = 3
        gap = ((V * n) % 1.0) > 0.86
        a[..., :3] = np.where(gap[..., None], np.array((34, 24, 16), float), a[..., :3])
        post = (U < 0.12) | (U > 0.88)
        postw = grain(w, h, 'v', OAK_D, (60, 40, 24), OAK, rng, streak=0.3)
        a[..., :3] = np.where(post[..., None], postw[..., :3], a[..., :3])
        nails = post & (((V * n) % 1.0 - 0.4) ** 2 < 0.01) & (abs(U - np.where(U < 0.5, 0.06, 0.94)) < 0.05)
        a[..., :3] = np.where(nails[..., None], np.array(IRON_L, float), a[..., :3])
        return shade_edges(a, 0.15)
    return painter(key, fn)


def sack_painter(key):
    """A burlap grain sack: a loose weave, lumpy shading, a stencilled sheaf, the neck tied off with cord."""
    def fn(face, w, h, rng):
        a = weave(w, h, BURLAP, BURLAP_D, BURLAP_L, rng, cell=max(1, min(w, h) // 8))
        U, V = uv(w, h)
        lump = np.sin(U * 5.3 + rng.uniform(0, 6)) * np.sin(V * 4.1 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.12 * lump)[..., None]
        if face in ('left', 'right') and min(w, h) >= 6:
            # the stencilled wheat sheaf: three stalks and a band
            st = ((abs(U - 0.5) < 0.04) | (abs(U - 0.5 - (0.5 - V) * 0.4) < 0.04) | (abs(U - 0.5 + (0.5 - V) * 0.4) < 0.04)) & (V > 0.25) & (V < 0.75)
            band = (abs(V - 0.6) < 0.04) & (abs(U - 0.5) < 0.12)
            a[..., :3] = np.where((st | band)[..., None], mix(a[..., :3], (110, 46, 34), 0.6), a[..., :3])
        return shade_edges(a, 0.25)
    return painter(key, fn)


def cord_painter(key):
    def fn(face, w, h, rng):
        a = noise_fill(w, h, (120, 94, 56), rng, 0.08)
        U, V = uv(w, h)
        tw = np.sin((U + V) * max(w, h) * 1.4) > 0
        a[..., :3] = np.where(tw[..., None], a[..., :3] * 0.8, a[..., :3])
        return a
    return painter(key, fn)


def bristle_painter(key):
    """A besom's bundle of twigs: dense straw strands down its length, ragged at the tip (clear notches)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('top', 'bottom'):
            a = noise_fill(w, h, STRAW_D, rng, 0.1)
            return a
        a = solid(w, h, STRAW)
        tone = rng.normal(0, 1, w)
        a[..., :3] = mix(np.array(STRAW_D, float), np.array(STRAW_L, float), (0.5 + 0.35 * np.tanh(tone))[None, :, None].repeat(h, 0))
        a[..., :3] *= (0.85 + 0.15 * V)[..., None] ** -1 * 0.95
        dirt = np.clip((V - 0.75) / 0.25, 0, 1)
        a[..., :3] = mix(a[..., :3], (110, 92, 60), (dirt * 0.5)[..., None])
        if h >= 4:
            cut = (rng.random(w) * max(1, h // 8)).astype(int)
            for i in range(w):
                if cut[i]:
                    a[h - cut[i]:, i] = 0
        return a
    return painter(key, fn)


def basket_painter(key):
    """Wicker: an over-under weave, a plaited rim, and on top a cloth with bread rolls peeking out."""
    def fn(face, w, h, rng):
        if face == 'top':
            a = noise_fill(w, h, (200, 60, 50), rng, 0.04)
            U, V = uv(w, h)
            check = ((U * 4).astype(int) + (V * 4).astype(int)) % 2 == 0
            a[..., :3] = np.where(check[..., None], np.array((232, 220, 200), float), a[..., :3])
            ys, xs = np.mgrid[0:h, 0:w]
            for cx, cy in ((0.35, 0.4), (0.62, 0.58)):
                d = np.hypot((xs + 0.5) / w - cx, ((ys + 0.5) / h - cy) * 1.3)
                loaf = d < 0.2
                a[..., :3] = np.where(loaf[..., None], mix(np.array(BREAD_L, float), np.array(BREAD_D, float), np.clip(d * 5, 0, 1)[..., None]), a[..., :3])
            rim = (U < 0.12) | (U > 0.88) | (V < 0.12) | (V > 0.88)
            a[..., :3] = np.where(rim[..., None], np.array(WICKER_D, float), a[..., :3])
            return a
        a = weave(w, h, WICKER, WICKER_D, WICKER_L, rng, cell=max(2, min(w, h) // 4))
        if face != 'bottom' and h >= 4:
            r = max(1, h // 6)
            a[:r, :, :3] = mix(a[:r, :, :3], WICKER_D, 0.5)
        return shade_edges(a, 0.2)
    return painter(key, fn)


def handle_arch_painter(key, base=WICKER, dark=WICKER_D, light=WICKER_L, thick=0.22):
    """An arched handle drawn on a flat cube: a band in a half-ring, clear inside and outside."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, base, dark, light, rng, cell=max(1, min(w, h) // 5))
        if face in ('right', 'left') or face in ('front', 'back'):
            x = (U - 0.5) * 2
            y = 1 - V
            r = np.hypot(x, y)
            band = (r <= 1.0) & (r >= 1.0 - thick * 2) & (y >= 0)
            if face in ('front', 'back') and w < h:
                band = np.ones_like(band)
            a[~band] = 0
        return a
    return painter(key, fn)


def mug_handle_painter(key):
    def fn(face, w, h, rng):
        a = grain(w, h, 'v', OAK_D, (60, 38, 20), OAK, rng, streak=0.3)
        U, V = uv(w, h)
        if face in ('right', 'left') and min(w, h) >= 3:
            hole = (abs(U - 0.58) < 0.22) & (abs(V - 0.5) < 0.28)
            a[hole] = 0
        return a
    return painter(key, fn)


def lantern_iron_painter(key, rivets=False):
    def fn(face, w, h, rng):
        a = metal(w, h, (58, 56, 60), (30, 28, 32), (128, 124, 120), rng, wear=0.06)
        if rivets and face not in ('top', 'bottom') and w >= 3:
            U, V = uv(w, h)
            rv = ((U * 3) % 1.0 - 0.5) ** 2 + (V - 0.5) ** 2 < 0.04
            a[..., :3] = np.where(rv[..., None], np.array((190, 172, 120), float), a[..., :3])
        return a
    return painter(key, fn)


def bail_painter(key):
    """The lantern's carrying ring: an iron hoop on a flat cube, clear in its middle."""
    def fn(face, w, h, rng):
        a = metal(w, h, (58, 56, 60), (30, 28, 32), (128, 124, 120), rng, bevel=False)
        U, V = uv(w, h)
        if face in ('front', 'back') and min(w, h) >= 3:
            hole = (abs(U - 0.5) < 0.3) & (V > 0.3)
            a[hole] = 0
        return a
    return painter(key, fn)

# ================================================================== the tools

def C(origin, size, name, paint, inflate=0.0):
    return Cube(origin, size, paint, inflate=inflate, name=name)


def _hammer():
    # Held cocked 12 degrees forward of the arm's line, so it hangs from the fist at rest; at the bottom of a blow
    # (the arm reaching forward 12 degrees below level) the handle runs level and the striking face (+z, the back
    # face) comes down flat. The face sits 2.5 px off the handle: with the shoulders a little over 2 px down from
    # their rest height that lands it on iron lying on a table-height anvil.
    return 'right_arm', Part('hammer', RIGHT_GRIP, (-12, 0, 0), cubes=[
        C((-0.5, -2.0, -0.5), (1, 7.5, 1), 't_hammer_handle', wood_painter('t_hammer_handle', WOOD, WOOD_D, WOOD_L, 'y')),
        C((-1.0, 4.0, -1.0), (2, 2, 3.5), 't_hammer_head', hammer_head_painter('t_hammer_head')),
        C((-0.6, 4.4, -2.5), (1.2, 1.2, 1.5), 't_hammer_peen', iron_painter('t_hammer_peen', (70, 72, 80), (40, 42, 50), (150, 156, 170))),
    ])


def _tongs():
    # Forward out of the fist like a held sword; the bar sticks straight on out of the jaws. Fist to the bar's
    # worked end: 13.5 px.
    hot = Part('hot_iron', (0, 9.5, 0), (0, 0, 0), cubes=[
        C((-0.5, 0.0, -0.5), (1, 4.5, 1), 't_hot_iron', hot_iron_painter('t_hot_iron')),
    ])
    return 'left_arm', Part('tongs', LEFT_GRIP, (-90, 0, 0), cubes=[
        C((-0.75, -2.0, -0.3), (1.5, 9.5, 0.6), 't_tongs_reins', tongs_painter('t_tongs_reins')),
        C((-0.75, 7.0, -0.6), (1.5, 1.2, 1.2), 't_tongs_rivet', iron_painter('t_tongs_rivet', (60, 60, 66), (30, 30, 36), (170, 170, 176), wear=0.2)),
        C((-0.6, 8.2, -0.7), (1.2, 2.0, 1.4), 't_tongs_jaws', iron_painter('t_tongs_jaws', (52, 50, 54), (26, 24, 26), (120, 110, 104), soot=0.25)),
    ], children=[hot])


def _peel():
    # A baker's peel: an ash pole and a broad thin blade, worked with both hands (the left takes the pole 5 px ahead
    # of the right fist). The pole leaves the fist 75 degrees forward of the arm's line: with the arms low and the
    # baker leaning in, it runs a little downhill into the oven's mouth.
    return 'right_arm', Part('peel', RIGHT_GRIP, (-75, 0, 0), cubes=[
        C((-0.5, -3.0, -0.5), (1, 15, 1), 't_peel_pole', wood_painter('t_peel_pole', PALE, PALE_D, PALE_L, 'y')),
        C((-2.5, 12.0, -0.25), (5, 6, 0.5), 't_peel_blade', peel_blade_painter('t_peel_blade')),
    ])


def _spear():
    # The watch's spear, 2.3 blocks: held upright in front of the fist along the arm, so with the arm hanging its
    # butt stands on the ground beside the right foot and its head rises well over the helm.
    return 'right_arm', Part('spear', (-1.0, 10.0, -2.6), (0, 0, 0), cubes=[
        C((-0.5, -19.5, -0.5), (1, 31, 1), 't_spear_shaft', wood_painter('t_spear_shaft', WOOD, WOOD_D, WOOD_L, 'y', knots=1)),
        C((-0.7, 11.0, -0.7), (1.4, 1.0, 1.4), 't_spear_butt', iron_painter('t_spear_butt')),
        C((-0.75, -21.0, -0.75), (1.5, 2.0, 1.5), 't_spear_socket', iron_painter('t_spear_socket', IRON, IRON_D, IRON_L)),
        C((-1.5, -26.0, -0.25), (3, 5, 0.5), 't_spear_head', spear_head_painter('t_spear_head')),
    ])


def _lantern():
    # Hangs from the left hand along +y; the game keeps it hanging upright whatever the arm does.
    return 'left_arm', Part('lantern', LEFT_GRIP, (0, 0, 0), cubes=[
        C((-1.0, -0.5, -0.25), (2, 2, 0.5), 't_lantern_bail', bail_painter('t_lantern_bail')),
        C((-1.5, 1.5, -1.5), (3, 1, 3), 't_lantern_cap', lantern_iron_painter('t_lantern_cap', rivets=True)),
        C((-1.25, 2.5, -1.25), (2.5, 3.5, 2.5), 't_lantern_glass', lantern_glass_painter('t_lantern_glass')),
        C((-1.5, 2.5, -1.5), (0.5, 3.5, 0.5), 't_lantern_post_a', lantern_iron_painter('t_lantern_post_a')),
        C((1.0, 2.5, -1.5), (0.5, 3.5, 0.5), 't_lantern_post_b', lantern_iron_painter('t_lantern_post_b')),
        C((-1.5, 2.5, 1.0), (0.5, 3.5, 0.5), 't_lantern_post_c', lantern_iron_painter('t_lantern_post_c')),
        C((1.0, 2.5, 1.0), (0.5, 3.5, 0.5), 't_lantern_post_d', lantern_iron_painter('t_lantern_post_d')),
        C((-1.5, 6.0, -1.5), (3, 1, 3), 't_lantern_base', lantern_iron_painter('t_lantern_base', rivets=True)),
    ])


def _hoe():
    # Held in both hands (the left takes the shaft 4 px ahead of the right fist); the shaft leaves the fist 70 degrees
    # forward of the arm's line, so a hanging arm carries it pointing forward and a little down, the blade hanging
    # from its far end. The blade is turned back towards the farmer (+z of the shaft), a draw hoe: chopped down with
    # the shaft sloping away, its edge (the plate's far side, z = 4.5) bites the soil.
    return 'right_arm', Part('hoe', RIGHT_GRIP, (-70, 0, 0), cubes=[
        C((-0.5, -2.5, -0.5), (1, 21, 1), 't_hoe_shaft', wood_painter('t_hoe_shaft', WOOD, WOOD_D, WOOD_L, 'y', knots=1)),
        C((-0.75, 17.5, -0.75), (1.5, 1.5, 1.5), 't_hoe_socket', iron_painter('t_hoe_socket', (80, 78, 82), (44, 42, 46), (150, 150, 156))),
        C((-1.75, 18.0, 0.5), (3.5, 0.75, 4), 't_hoe_blade', hoe_blade_painter('t_hoe_blade')),
    ])


def _rod():
    # Forward out of the fist like a held sword: with the arm reaching forward and down it rises out over the
    # water. The line hangs from the tip (the game turns it to hang straight down each frame).
    line = Part('line', (0, 26.0, 0), (0, 0, 0), cubes=[
        C((-0.25, 0.0, -0.25), (0.5, 46, 0.5), 't_rod_line', line_painter('t_rod_line')),
    ])
    return 'right_arm', Part('rod', RIGHT_GRIP, (-90, 0, 0), cubes=[
        C((-0.6, -3.0, -0.6), (1.2, 5, 1.2), 't_rod_grip', cork_painter('t_rod_grip')),
        C((-0.5, 2.0, -0.5), (1, 11, 1), 't_rod_butt', rod_painter('t_rod_butt')),
        C((-0.3, 13.0, -0.3), (0.6, 13.5, 0.6), 't_rod_tip', rod_painter('t_rod_tip', (132, 142, 80), (90, 100, 50), (186, 196, 122))),
        C((-0.75, 0.0, 0.6), (1.5, 2, 1.5), 't_rod_reel', reel_painter('t_rod_reel')),
    ], children=[line])


def _cane():
    # Along the arm, a touch forward: hanging at the side its ferrule rests on the ground by the foot.
    return 'right_arm', Part('cane', RIGHT_GRIP, (-6, 0, 0), cubes=[
        C((-0.5, -0.5, -0.5), (1, 12.5, 1), 't_cane_shaft', cane_painter('t_cane_shaft')),
        C((-1.0, -1.5, -1.0), (2, 1.5, 2), 't_cane_knob', gold_painter('t_cane_knob')),
        C((-0.6, 11.5, -0.6), (1.2, 0.8, 1.2), 't_cane_ferrule', gold_painter('t_cane_ferrule')),
    ])


def _fan():
    # An open fan held low at the side, its face to the front.
    return 'left_arm', Part('fan', LEFT_GRIP, (-15, 0, 0), cubes=[
        C((-3.5, -0.5, -0.25), (7, 4.5, 0.5), 't_fan_leaf', fan_painter('t_fan_leaf')),
    ])


def _rag():
    # A cloth bunched in the left hand (the right holds the mug), a corner hanging free.
    return 'left_arm', Part('rag', LEFT_GRIP, (0, 0, 0), cubes=[
        C((-1.6, -1.0, -1.6), (3.2, 2.5, 3.2), 't_rag_wad', rag_painter('t_rag_wad')),
        C((-1.2, 1.5, -1.4), (2.4, 3, 0.6), 't_rag_tail', rag_painter('t_rag_tail')),
    ])


def _mug():
    # A coopered tankard held by its handle, the body on the thumb side. Tilted 45 degrees on the arm so it stands
    # upright with the arm reaching 45 degrees forward (chest height); raising the arm tips it to the lips.
    return 'right_arm', Part('mug', RIGHT_GRIP, (45, 0, 0), cubes=[
        C((-1.5, -2.5, -4.75), (3, 4, 3), 't_mug_body', staves_painter('t_mug_body', top=ale_top)),
        C((-0.5, -2.0, -1.75), (1, 3, 1.75), 't_mug_handle', mug_handle_painter('t_mug_handle')),
    ])


def _jug():
    # An earthenware jug in the left hand, its body on the thumb side, spout away from the handle.
    return 'left_arm', Part('jug', LEFT_GRIP, (45, 0, 0), cubes=[
        C((-2.0, -3.0, -5.5), (4, 5, 4), 't_jug_body', jug_painter('t_jug_body')),
        C((-1.25, -4.0, -4.75), (2.5, 1, 2.5), 't_jug_neck', jug_painter('t_jug_neck')),
        C((-0.5, -4.2, -6.5), (1, 1, 1.5), 't_jug_spout', jug_painter('t_jug_spout')),
        C((-0.5, -2.5, -1.75), (1, 3.5, 1.5), 't_jug_handle', mug_handle_painter('t_jug_handle')),
    ])


def _lute_parts(prefix, painter_key):
    """The lute's pieces in its own frame: the bowl centred on the origin, its face (soundboard) to -z, the neck
    running off along +x and the pegbox turned back at the end."""
    head = Part(f'{prefix}_head', (10.0, 0, -0.5), (0, -28, 0), cubes=[
        C((0.0, -0.75, -0.25), (2.5, 1.5, 1.0), f't_{painter_key}_pegbox', pegbox_painter(f't_{painter_key}_pegbox')),
    ])
    cubes = [
        C((-3.5, -2.5, -1.5), (6.5, 5, 3), f't_{painter_key}_bowl', lute_bowl_painter(f't_{painter_key}_bowl')),
        C((3.0, -0.75, -1.5), (7.0, 1.5, 1.0), f't_{painter_key}_neck', lute_neck_painter(f't_{painter_key}_neck')),
    ]
    return cubes, head


def _lute():
    # Played: held across the belly, the bowl at the right hip, the neck rising to the left hand.
    cubes, head = _lute_parts('lute', 'lute')
    return 'body', Part('lute', (-1.0, 9.5, -4.2), (0, 12, -16), cubes=cubes, children=[head])


def _lute_slung():
    # Carried: across the back, the neck up over the left shoulder, on a strap across the chest.
    cubes, head = _lute_parts('lute_slung', 'lute_slung')
    strap = Part('lute_slung_strap', (0, 5.5, 0), (0, 0, -42), cubes=[
        C((-0.6, -7.5, -2.35), (1.2, 15, 0.3), 't_lute_slung_strap_front', strap_painter('t_lute_slung_strap_front')),
    ])
    return 'body', Part('lute_slung', (-0.5, 6.5, 3.6), (180, 0, 55), cubes=cubes, children=[head, strap])


def _book():
    # A prayer book carried at the side by its top edge, spine forward.
    return 'left_arm', Part('book', LEFT_GRIP, (0, 0, 0), cubes=[
        C((-0.85, -1.0, -2.0), (1.7, 5, 4), 't_book', book_painter('t_book')),
    ])


def _crate():
    # Carried in both arms in front of the belly; the hands hold its sides.
    return 'body', Part('crate', (0, 7.0, -7.5), (0, 0, 0), cubes=[
        C((-4.5, -3.0, -3.0), (9, 6, 6), 't_crate', crate_painter('t_crate')),
    ])


def _sack():
    # A grain sack slung over the right shoulder, the neck tied off in front.
    return 'body', Part('sack', (-5.6, -1.2, 0.4), (0, 0, -12), cubes=[
        C((-2.6, -3.0, -4.6), (5.2, 5, 9), 't_sack', sack_painter('t_sack')),
        C((-1.5, -2.6, -6.2), (3, 3, 1.6), 't_sack_neck', sack_painter('t_sack_neck')),
        C((-1.6, -2.7, -5.0), (3.2, 3.2, 0.6), 't_sack_cord', cord_painter('t_sack_cord')),
    ])


def _broom():
    # A besom held in both hands; the handle leaves the fist 40 degrees forward of the arm's line.
    return 'right_arm', Part('broom', RIGHT_GRIP, (-40, 0, 0), cubes=[
        C((-0.5, -2.0, -0.5), (1, 14, 1), 't_broom_handle', wood_painter('t_broom_handle', PALE, PALE_D, PALE_L, 'y')),
        C((-1.4, 11.5, -1.4), (2.8, 1, 2.8), 't_broom_binding', cord_painter('t_broom_binding')),
        C((-1.25, 12.0, -1.25), (2.5, 6, 2.5), 't_broom_bristles', bristle_painter('t_broom_bristles'), inflate=0.25),
    ])


def _basket():
    # Hangs from the left hand by its arched handle.
    return 'left_arm', Part('basket', LEFT_GRIP, (0, 0, 0), cubes=[
        C((-0.25, -1.0, -2.5), (0.5, 3.5, 5), 't_basket_handle', handle_arch_painter('t_basket_handle')),
        C((-2.0, 2.5, -3.0), (4, 3.5, 6), 't_basket_body', basket_painter('t_basket_body')),
    ])


BUILDERS = [_hammer, _tongs, _peel, _spear, _lantern, _hoe, _rod, _cane, _fan, _rag, _mug, _jug, _lute, _lute_slung,
            _book, _crate, _sack, _broom, _basket]

GLOW_CUBES = {'t_hot_iron', 't_lantern_glass'}

# What each tool's clips expect of it, for the lead (also in the report)
TOOL_NOTES = {
    'hammer': 'right hand; striking face is the head\'s +z end (part space (0, 5, 2.5)); clip hammer lands it on tick 14',
    'tongs': 'left hand; jaws\' tip at part space (0, 10, 0); hot_iron runs on from the jaws (worked end (0, 3.6, 0) '
             'in its own space, far end (0, 4.5, 0))',
    'peel': 'right hand, the left on the pole 5 px ahead; blade centre at part space (0, 15, 0)',
    'hoe': 'right hand, the left on the shaft 4.5 px ahead; the blade\'s edge at part space (0, 18.4, 4.5)',
    'spear': 'right hand; butt end at part space (0, 12, 0), point at (0, -26, 0)',
    'rod': 'right hand; tip at part space (0, 26, 0)',
    'line': 'child of rod at the tip (0, 26, 0); turn it to hang straight down each frame',
    'lantern': 'left hand; turn it to hang upright each frame',
    'mug': 'right hand (also in wipe, where the rag is in the left); rim centre at part space (0, -2.5, -3.25)',
    'jug': 'left hand; spout lip at part space (0, -3.7, -7.2)',
    'broom': 'right hand, the left on the handle 4.5 px ahead; bristle ends at part space (0, 18.2, 0)',
    'lute': 'on the body (played); lute_slung on the body otherwise; both have child pieces (head, strap)',
}


def tool_parts():
    """Parent part name -> list of fresh tool Parts (build again on every call; callers may mutate them)."""
    out = {}
    for b in BUILDERS:
        parent, part = b()
        out.setdefault(parent, []).append(part)
    return out


def tool_painters():
    """Cube name -> painter, for every tool cube."""
    out = {}
    for parts in tool_parts().values():
        for p in parts:
            for q in p.walk():
                for c in q.cubes:
                    out[c.name] = c.paint
    return out


def tool_names():
    """Every tool part name, children included (hot_iron, line, and the lute's pieces)."""
    return [q.name for parts in tool_parts().values() for p in parts for q in p.walk()]
