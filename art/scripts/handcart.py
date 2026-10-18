"""
The carters' handcart (cart/Handcart, drawn by client/cart/HandcartRenderer) and what heaps on its bed. Two big spoked
wheels with iron tyres on an iron axle; a plank bed with low sides, nailed on two long ash poles that run on forward
as the shafts, braced by a bar ahead of the bed; the load: sacks of grain, paler sacks of flour, crates (for roots and
fruit), a basket (for loaves) and a box of fish on ice, the goods themselves drawn on top of the open ones by the game;
and a household's goods when they move (HOUSEHOLD): a chest, the cooking pot, their bundles (PACK's), a hen in her coop
looking about (HandcartMesh.henHead), the mattress rolled and tied, and a chair, heaped on the same places.
In the capitals' manner: their block carts (city/plan/FarmYard) have a plank bed between round wheels of dark planks
and rest tipped forward on their shafts, as this does when it's parked.

    python3 -B art/scripts/handcart.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/cart art/models [preview-dir]

Writes textures/entity/cart/handcart.png (box UV on TEX_W x TEX_H, the PNG at SCALE times that, painted at the higher
resolution as the townsfolk's are), HandcartMesh.kt (the cart's layer and the load's), handcart.bbmodel, and (with a
preview dir) the cart empty, loaded with sacks, loaded with produce, parked on its shafts, and from the side and above,
and a household's goods heaped on it (a still, and a GIF of them loaded one by one and the hen in her coop).
Prints the geometry the game's code works from: where the hands take the shafts, the tilt it rests at when parked,
the places on the bed. Model space as everywhere: pixels, y down, the cart faces -z (the shafts' way), its right is
-x; the origin is the middle of the axle, a wheel's radius over the ground.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from modelkit import Cube, Part, Model
import townsfolk_tools as tt

TEX_W, TEX_H = 256, 128     # the declared (box UV) size
SCALE = 2                   # the PNG ships at SCALE times that

# ================================================================== geometry (px)

R = 10.0                    # the wheels' radius: the axle stands this high over the ground
WHEEL_X = 9.0               # each wheel's middle, out from the cart's middle
TRACK = 2 * WHEEL_X
RAIL_X = 6.0                # the poles' axis (the hands take them here: the townsfolk's hands are 6 px out)
RAIL_Y = -3.0               # level with the hands when it's pulled on the flat
RAIL_T = 1.5                # the poles' thickness
RAIL_FRONT = -26.0          # the shafts' tips
RAIL_BACK = 11.0
GRIP_Z = -24.5              # where the hands are on them (clips: art/scripts/handcart_anims.py)
FLOOR_TOP = -4.75           # the bed's floor, inside
BED_X = 6.5                 # the floor's half width (the sides stand outside it)
BED_FRONT, BED_BACK = -14.0, 10.0
SIDE_TOP = -7.75            # the low sides: 3 px over the floor

# Where things heap on the bed: (x, z, yaw degrees, layer, the places a top one rests on). Filled in this order.
SLOTS = [
    (-3.25, -1.9, 1.5, 0, ()), (3.25, -1.9, -2.0, 0, ()),
    (-3.25, -9.2, -1.5, 0, ()), (3.25, -9.2, 1.5, 0, ()),
    (-3.25, 5.4, 2.0, 0, ()), (3.25, 5.4, -1.5, 0, ()),
    (-0.6, -5.55, 9.0, 1, (0, 1, 2, 3)), (0.7, 1.75, -11.0, 1, (0, 1, 4, 5)),
]

# What can heap there: its model part, how tall it stands, and where the goods show on top of the open ones
# ((x, z, yaw, tilt) px and degrees, the item's size as a fraction of a block).
PIECES = {
    'sack': dict(height=4.5, shows=()),
    'flour': dict(height=4.5, shows=()),
    'crate': dict(height=4.6, shows=((-1.3, -1.5, 25.0, 8.0), (1.4, -0.3, -40.0, -6.0), (-0.1, 1.7, 75.0, 5.0)), size=0.36),
    'basket': dict(height=3.6, shows=((-1.0, -1.2, 30.0, 10.0), (1.1, 1.0, -20.0, -8.0)), size=0.40),
    'fish': dict(height=2.8, shows=((-1.2, 0.0, 80.0, 4.0), (1.2, 0.3, 100.0, -5.0)), size=0.42),
}


# The pieces' order in the Kotlin (HandcartMesh.PIECES)
PIECE_ORDER = ('sack', 'flour', 'crate', 'basket', 'fish')

# A household's goods for a move (household_parts()): after the produce in HandcartMesh.PIECES (5 to 10), kept out of
# PIECES and load_parts(), which the caravan's wagon heaps from
HOUSE_PIECES = {
    'chest': dict(height=4.7, shows=()),
    'pot': dict(height=3.8, shows=()),
    'bundle': dict(height=6.4, shows=()),
    'cage': dict(height=4.0, shows=()),
    'mattress': dict(height=3.2, shows=()),
    'chair': dict(height=4.4, shows=()),
}
HOUSE_ORDER = ('chest', 'pot', 'bundle', 'cage', 'mattress', 'chair')
ALL_PIECES = {**PIECES, **HOUSE_PIECES}
KT_ORDER = PIECE_ORDER + HOUSE_ORDER

# What a household's move heaps, place by place (SLOTS' order): the chest and the pot over the axle, the hen's coop and
# a sack from their larder ahead, their two bundles at the tail (place 5's where PACK sets its bundle down), the
# mattress and the chair on top
HOUSEHOLD = ('chest', 'pot', 'cage', 'sack', 'bundle', 'bundle', 'mattress', 'chair')

# The hen in her coop looks about as hens do: her head snapped from one look to the next every HEN_BEAT ticks (in
# HEN_SNAP) and held there, cocked now and then, and in some beats a peck at the straw (HEN_PECK ticks, down to
# HEN_PECK_DEG), or two. A function of the time alone (and a seed, the cart's), the same here and in the Kotlin.
HEN_BEAT, HEN_SNAP = 8.0, 1.2
HEN_PECK, HEN_PECK_AT, HEN_PECK_DEG = 1.8, (2.4, 4.6), 34.0


def hen_hash(k, j):
    """0..1 from two ints, as 32-bit ints mix them (the Kotlin's Int arithmetic, wrapping)."""
    h = (k * 374761393 + j * 668265263) & 0xffffffff
    h = ((h ^ (h >> 13)) * 1274126177) & 0xffffffff
    h ^= h >> 16
    return (h & 0xffffff) / 16777216.0


def hen_look(k, seed, which):
    """Beat [k]'s look for the hen on cart [seed]: [which] 0 her pitch (down +), 1 her yaw, 2 her roll (degrees)."""
    j = seed * 8
    if which == 0:
        return (hen_hash(k, j + 3) - 0.65) * 24.0
    if which == 1:
        return (2.0 * hen_hash(k, j) - 1.0) * 42.0
    return (2.0 * hen_hash(k, j + 1) - 1.0) * 14.0 if hen_hash(k, j + 2) < 0.35 else 0.0


def _smooth(x):
    c = min(1.0, max(0.0, x))
    return c * c * (3.0 - 2.0 * c)


def hen_peck(u):
    """A peck's dip, 0..1, [u] ticks into it: quick down, a moment at the straw, back up."""
    if u <= 0.0 or u >= HEN_PECK:
        return 0.0
    x = u / HEN_PECK
    return _smooth(x / 0.3) if x < 0.3 else 1.0 if x < 0.5 else 1.0 - _smooth((x - 0.5) / 0.5)


def hen_head(t, seed=0):
    """"cage_hen_head"'s turn at [t] ticks: (xRot, yRot, zRot) degrees, as ModelPart takes them (radians there)."""
    k = math.floor(t / HEN_BEAT)
    s = t - k * HEN_BEAT
    f = _smooth(s / HEN_SNAP)
    pitch, yaw, roll = [hen_look(k - 1, seed, i) + (hen_look(k, seed, i) - hen_look(k - 1, seed, i)) * f for i in range(3)]
    pecks = hen_hash(k, seed * 8 + 4)
    if pecks < 0.3:
        dip = hen_peck(s - HEN_PECK_AT[0]) + (hen_peck(s - HEN_PECK_AT[1]) if pecks < 0.12 else 0.0)
        pitch += (HEN_PECK_DEG - pitch) * dip
        yaw *= 1.0 - 0.6 * dip
        roll *= 1.0 - dip
    return pitch, yaw, roll


def parked_tilt():
    """Degrees the cart tips forward when it's let go on the flat: until the shafts' tips (their lower edge) touch."""
    a, b = -RAIL_FRONT, -(RAIL_Y + RAIL_T / 2)     # the tip's lower edge: this far ahead, this far up
    h = R                                            # ... and must come down to the ground
    return math.degrees(math.asin(h / math.hypot(a, b)) + math.atan2(b, a))

# ================================================================== palette

BED = (138, 100, 64); BED_D = (88, 60, 38); BED_L = (176, 138, 94); BED_GREY = (128, 118, 104)      # weathered oak
WHEEL = (92, 64, 44); WHEEL_D = (54, 36, 26); WHEEL_L = (128, 94, 66)                                 # dark oak
SPOKE = (110, 78, 52); SPOKE_D = (66, 46, 30); SPOKE_L = (150, 112, 76)
ASH = (170, 134, 90); ASH_D = (120, 90, 58); ASH_L = (206, 172, 124)                                  # the shafts
GRIP = (96, 64, 40)                                                                                   # hands' polish
IRON = (64, 62, 66); IRON_D = (32, 30, 34); IRON_L = (126, 122, 120)                                  # black iron
TYRE = (74, 72, 76); TYRE_D = (38, 36, 40); TYRE_L = (152, 150, 148)
BURLAP = tt.BURLAP; BURLAP_D = tt.BURLAP_D; BURLAP_L = tt.BURLAP_L
LINEN = (204, 194, 170); LINEN_D = (150, 140, 118); LINEN_L = (230, 224, 206); FLOUR = (246, 244, 236)
STRIPE = (82, 104, 140)
STENCIL = (112, 48, 36)
STRAW = tt.STRAW; STRAW_D = tt.STRAW_D; STRAW_L = tt.STRAW_L
CRATE = (166, 126, 80); CRATE_D = (112, 78, 46); CRATE_L = (200, 164, 112)
FISHBOX = (104, 96, 88); FISHBOX_D = (62, 56, 52); FISHBOX_L = (146, 138, 128)
ICE = (206, 224, 230); ICE_D = (150, 176, 190); ICE_L = (244, 250, 252)
CLOTH = (176, 52, 44); CLOTH_L = (232, 220, 196)
WICKER = tt.WICKER; WICKER_D = tt.WICKER_D; WICKER_L = tt.WICKER_L

# ================================================================== painting

uv, mix, solid, grain, metal, weave = tt.uv, tt.mix, tt.solid, tt.grain, tt.metal, tt.weave


def painter(key, fn):
    return tt.painter(key, fn)


def boards(w, h, rng, n, along, base, dark, light, knots=1, streak=0.45, seam=0.55):
    """Planks running [along] the face ('u' or 'v'), [n] of them side by side, a dark seam between each, each its own
    tone; returns the array and which board each texel is on."""
    a = grain(w, h, along, base, dark, light, rng, streak=streak, knots=knots)
    U, V = uv(w, h)
    across = U if along == 'v' else V
    k = np.minimum((across * n).astype(int), n - 1)
    tone = rng.normal(0, 0.07, n)
    a[..., :3] *= (1 + tone[k])[..., None]
    cells = (w if along == 'v' else h) / n
    if cells >= 2:
        f = (across * n) % 1.0
        line = (f < 0.6 / cells) | (f > 1 - 0.6 / cells)
        line &= (across > 0.5 / (w if along == 'v' else h) * 2) & (across < 1 - 0.5 / (w if along == 'v' else h) * 2)
        a[..., :3] = np.where(line[..., None], mix(a[..., :3], dark, seam), a[..., :3])
    return a, k


def weather(a, rng, amount=0.18, grey=BED_GREY):
    """Old wood gone silver in patches."""
    h, w = a.shape[:2]
    m = rng.normal(0, 1, (h // 3 + 2, w // 3 + 2))
    m = np.kron(m, np.ones((3, 3)))[:h, :w]
    m = np.clip(0.5 + 0.4 * m, 0, 1) * amount
    a[..., :3] = mix(a[..., :3], grey, m[..., None])
    return a


def nail(a, cu, cv, r_px=0.9, col=IRON_D, hi=IRON_L):
    """A nail head (or a rivet) at face coords (cu, cv) 0..1."""
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    d = np.hypot(xs + 0.5 - cu * w, ys + 0.5 - cv * h)
    r = r_px * SCALE / 2
    on = d <= max(0.6, r)
    a[..., :3] = np.where(on[..., None], np.array(col, float), a[..., :3])
    hl = (np.hypot(xs + 0.5 - cu * w + 0.35 * r, ys + 0.5 - cv * h + 0.35 * r) <= max(0.4, r * 0.45)) & on
    a[..., :3] = np.where(hl[..., None], mix(a[..., :3], hi, 0.7), a[..., :3])
    return a


def band(a, mask, rng, base=IRON, dark=IRON_D, light=IRON_L):
    """Black iron (a strap, a bracket, a tyre) over the texels in [mask], lit along its upper edge."""
    h, w = a.shape[:2]
    m = metal(w, h, base, dark, light, rng, bevel=False, wear=0.05)
    a[..., :3] = np.where(mask[..., None], m[..., :3], a[..., :3])
    edge = mask & ~np.roll(mask, 1, 0)
    a[..., :3] = np.where(edge[..., None], mix(a[..., :3], light, 0.35), a[..., :3])
    low = mask & ~np.roll(mask, -1, 0)
    a[..., :3] = np.where(low[..., None], mix(a[..., :3], dark, 0.4), a[..., :3])
    return a


def along_z(face):
    """Which way a cube's length (z) runs on [face]: 'u' or 'v', None on its ends; and whether that runs toward the
    front (so 1 is the front end)."""
    return {'right': ('u', True), 'left': ('u', False), 'top': ('v', True), 'bottom': ('v', True)}.get(face, (None, False))


def front_frac(face, w, h, z0, z1, at):
    """How far along [face] (0..1, in its own u or v) the cube's z = [at] falls, for a cube from z0 to z1."""
    ax, to_front = along_z(face)
    f = (z1 - at) / (z1 - z0)
    return f if to_front else 1 - f

# ---------------------------------------------------------------- the bed


def floor_painter(key):
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            a, k = boards(w, h, rng, 5, 'v', BED, BED_D, BED_L, knots=2)
            a = weather(a, rng, 0.22 if face == 'top' else 0.1)
            U, V = uv(w, h)
            if face == 'top':
                # scuffed pale down the middle where the sacks slide, a stain or two
                scuff = np.exp(-((U - 0.5) / 0.28) ** 2) * (0.5 + 0.5 * np.sin(V * 9.0 + rng.uniform(0, 6)) ** 2)
                a[..., :3] = mix(a[..., :3], BED_L, (0.16 * scuff)[..., None])
                for _ in range(2):
                    cx, cy, rr = rng.uniform(0.2, 0.8), rng.uniform(0.2, 0.8), rng.uniform(0.06, 0.11)
                    d = np.hypot((U - cx) * w / h, V - cy) / rr
                    a[..., :3] = np.where((d < 1)[..., None], mix(a[..., :3], BED_D, (0.25 * (1 - d))[..., None].clip(0, 1)), a[..., :3])
                # nails where the planks sit on the bearers and the poles, at the ends
                for z in (-11.25, 0.0, 6.75):
                    vv = (BED_BACK - z) / (BED_BACK - BED_FRONT)
                    for i in range(5):
                        if z == 0.0 and i not in (0, 4):
                            continue
                        nail(a, (i + 0.5) / 5, vv, 0.7)
            return tt.shade_edges(a, 0.12)
        a = grain(w, h, 'u' if face in ('front', 'back') else 'u', BED_D, (60, 40, 26), BED, rng, streak=0.3)
        return a
    return painter(key, fn)


def side_painter(key, outer):
    """A side board: two planks one over the other, iron brackets at both ends and a strap over the axle on its
    outer face ([outer] is that face's name)."""
    def fn(face, w, h, rng):
        if face in ('right', 'left'):
            a, _ = boards(w, h, rng, 2, 'u', BED, BED_D, BED_L, knots=1)
            a = weather(a, rng, 0.25 if face == outer else 0.12)
            U, V = uv(w, h)
            # worn pale along the top edge, where loads are dragged over
            a[..., :3] = mix(a[..., :3], BED_L, (0.25 * np.clip(1 - V * 5, 0, 1))[..., None])
            if face == outer:
                f_axle = front_frac(face, w, h, BED_FRONT, BED_BACK, 0.0)
                ends = (U < 0.055) | (U > 0.945)
                strap = np.abs(U - f_axle) < 0.035
                a = band(a, ends | strap, rng)
                for cu in (0.028, 0.972, f_axle):
                    for cv in (0.25, 0.72):
                        nail(a, cu, cv, 0.6, IRON_D, IRON_L)
            return tt.shade_edges(a, 0.14)
        if face in ('top', 'bottom'):
            a = grain(w, h, 'v', BED_L if face == 'top' else BED_D, BED_D, BED_L, rng, streak=0.35)
            return a
        a = grain(w, h, None, BED, BED_D, BED_L, rng)
        return a
    return painter(key, fn)


def end_painter(key):
    """The front and the tail boards: planks across, iron at the corners."""
    def fn(face, w, h, rng):
        if face in ('front', 'back'):
            a, _ = boards(w, h, rng, 2, 'u', BED, BED_D, BED_L, knots=1)
            a = weather(a, rng, 0.2)
            U, V = uv(w, h)
            a[..., :3] = mix(a[..., :3], BED_L, (0.2 * np.clip(1 - V * 5, 0, 1))[..., None])
            return tt.shade_edges(a, 0.14)
        if face in ('top', 'bottom'):
            return grain(w, h, 'u', BED_L if face == 'top' else BED_D, BED_D, BED_L, rng, streak=0.35)
        return grain(w, h, None, BED, BED_D, BED_L, rng)
    return painter(key, fn)


def post_painter(key):
    """A corner stake: grain upright, an iron cap on its top."""
    def fn(face, w, h, rng):
        if face == 'top':
            return metal(w, h, IRON, IRON_D, IRON_L, rng)
        if face == 'bottom':
            return grain(w, h, None, BED_D, (56, 38, 24), BED, rng)
        a = grain(w, h, 'v', BED_D, (60, 40, 26), BED, rng, streak=0.4)
        U, V = uv(w, h)
        a = band(a, V < 0.14, rng)
        return tt.shade_edges(a, 0.15)
    return painter(key, fn)


def rail_painter(key, z0, z1):
    """An ash pole, the bed's beam and the shaft in one: grain along it, dark and smooth where the hands take it
    (the last few px), the tip's end grain."""
    def fn(face, w, h, rng):
        ax, _ = along_z(face)
        if ax is None:
            a = grain(w, h, None, ASH, ASH_D, ASH_L, rng)
            return a * np.array([0.92, 0.92, 0.92, 1.0])
        a = grain(w, h, ax, ASH, ASH_D, ASH_L, rng, streak=0.5, knots=1)
        U, V = uv(w, h)
        f = U if ax == 'u' else V
        tip = front_frac(face, w, h, z0, z1, RAIL_FRONT)
        hold = front_frac(face, w, h, z0, z1, GRIP_Z - 1.6)
        lo, hi = sorted((tip, hold))
        polish = (f >= lo) & (f <= hi)
        a[..., :3] = np.where(polish[..., None], mix(a[..., :3], GRIP, 0.55), a[..., :3])
        # an iron ferrule where the shaft leaves the bed, and over the axle
        for zf in (BED_FRONT - 0.6, 0.0):
            c = front_frac(face, w, h, z0, z1, zf)
            ring = np.abs(f - c) < 0.6 / (z1 - z0)
            if zf == 0.0 and face == 'top':
                continue
            a = band(a, ring, rng)
        return tt.shade_edges(a, 0.2)
    return painter(key, fn)


def axle_painter(key):
    def fn(face, w, h, rng):
        a = metal(w, h, IRON, IRON_D, IRON_L, rng, wear=0.08)
        U, V = uv(w, h)
        grease = rng.random((h, w)) < 0.12
        a[..., :3] = np.where(grease[..., None], mix(a[..., :3], (24, 22, 20), 0.6), a[..., :3])
        return a
    return painter(key, fn)


def hanger_painter(key):
    """The block under each pole the axle runs through: dark wood bound with an iron strap."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('front', 'back', 'right', 'left'):
            a = grain(w, h, 'v', WHEEL, WHEEL_D, WHEEL_L, rng, streak=0.35)
            a = band(a, (V > 0.62) | (np.abs(U - 0.5) < 0.12) & (V > 0.2), rng)
            nail(a, 0.5, 0.32, 0.55)
            return tt.shade_edges(a, 0.18)
        if face == 'bottom':
            return metal(w, h, IRON, IRON_D, IRON_L, rng)
        return grain(w, h, None, WHEEL, WHEEL_D, WHEEL_L, rng)
    return painter(key, fn)


def bar_painter(key):
    """A cross piece (the bar bracing the shafts, the bearers under the floor): grain across."""
    def fn(face, w, h, rng):
        if face in ('right', 'left'):
            return grain(w, h, None, ASH_D, (80, 60, 38), ASH, rng)
        a = grain(w, h, 'u', ASH_D, (86, 64, 40), ASH, rng, streak=0.45)
        U, V = uv(w, h)
        a = band(a, (U < 0.07) | (U > 0.93), rng)
        return tt.shade_edges(a, 0.2)
    return painter(key, fn)

# ---------------------------------------------------------------- the wheels


def rim_painter(key):
    """One felloe of a wheel's rim (they all share it): the iron tyre on its outer face, nailed on, worn bright;
    the felloe's wood on the wheel's faces with the tyre's edge over it; end grain where the felloes meet."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':           # the tread
            a = metal(w, h, TYRE, TYRE_D, TYRE_L, rng, wear=0.1)
            worn = np.exp(-((V - 0.5) / 0.3) ** 2)
            a[..., :3] = mix(a[..., :3], TYRE_L, (0.3 * worn)[..., None])
            for cv in (0.25, 0.75):
                nail(a, 0.5, cv, 0.55, TYRE_D, TYRE_L)
            return a
        if face in ('right', 'left'):   # the wheel's flat faces
            a = grain(w, h, 'u', WHEEL, WHEEL_D, WHEEL_L, rng, streak=0.4)
            a = band(a, V < 0.24, rng, TYRE, TYRE_D, TYRE_L)
            a[..., :3] = np.where((V > 0.85)[..., None], mix(a[..., :3], WHEEL_D, 0.4), a[..., :3])
            nail(a, 0.5, 0.62, 0.5, IRON_D, IRON_L)
            return a
        if face == 'bottom':
            return grain(w, h, 'u', WHEEL_D, (40, 28, 20), WHEEL, rng, streak=0.3)
        a = grain(w, h, None, WHEEL, WHEEL_D, WHEEL_L, rng)
        a = band(a, V < 0.3, rng, TYRE, TYRE_D, TYRE_L)
        return a
    return painter(key, fn)


def spoke_painter(key):
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            return grain(w, h, None, SPOKE_D, (44, 30, 20), SPOKE, rng)
        a = grain(w, h, 'v', SPOKE, SPOKE_D, SPOKE_L, rng, streak=0.45)
        U, V = uv(w, h)
        # rounded: lit down the middle, dark at its edges
        a[..., :3] *= (0.82 + 0.3 * np.sin(np.pi * U))[..., None]
        return a
    return painter(key, fn)


def hub_painter(key):
    """The nave: end grain on its faces round the axle's bore, iron bands at both ends of its round."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('right', 'left'):
            a = grain(w, h, None, WHEEL, WHEEL_D, WHEEL_L, rng)
            d = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = np.where((d > 0.36)[..., None], mix(a[..., :3], IRON, 0.75), a[..., :3])
            a[..., :3] = np.where((d < 0.16)[..., None], np.array(IRON_D, float), a[..., :3])
            return a
        a = grain(w, h, 'u', WHEEL, WHEEL_D, WHEEL_L, rng, streak=0.35)
        a = band(a, (U < 0.2) | (U > 0.8), rng)
        return a
    return painter(key, fn)


def cap_painter(key):
    """The iron cap on the axle's end, its linchpin through it."""
    def fn(face, w, h, rng):
        a = metal(w, h, IRON, IRON_D, IRON_L, rng, wear=0.08)
        U, V = uv(w, h)
        if face in ('right', 'left'):
            pin = (np.abs(U - 0.5) < 0.12)
            a[..., :3] = np.where(pin[..., None], mix(a[..., :3], IRON_L, 0.4), a[..., :3])
        return a
    return painter(key, fn)

# ---------------------------------------------------------------- the load


def sack_painter(key, flour=False, part='body'):
    """A sack lying on the bed: burlap with a stencilled sheaf on top for grain; pale linen with a blue stripe at each
    end, dusted white, for flour. Lumpy, sagging at its seams; [part] is which of its cubes: the 'body', the swell
    of its 'top', the gathered 'neck' (tied off with cord)."""
    base, dark, light = (LINEN, LINEN_D, LINEN_L) if flour else (BURLAP, BURLAP_D, BURLAP_L)
    neck = part == 'neck'

    def fn(face, w, h, rng):
        a = weave(w, h, base, dark, light, rng, cell=max(1, min(w, h) // 8))
        U, V = uv(w, h)
        lump = np.sin(U * 4.3 + rng.uniform(0, 6)) * np.sin(V * 3.7 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.12 * lump)[..., None]
        # creases: a few soft folds across it, dark in their troughs
        for _ in range(2):
            ang, off = rng.uniform(0, math.pi), rng.uniform(-0.3, 0.3)
            d = (U - 0.5) * math.cos(ang) + (V - 0.5) * math.sin(ang) - off
            a[..., :3] *= (1 - 0.16 * np.exp(-(d / 0.045) ** 2))[..., None]
        if part == 'base':
            a[..., :3] *= 0.8
        if flour:
            dust = rng.random((h, w)) < 0.12
            a[..., :3] = np.where(dust[..., None], mix(a[..., :3], FLOUR, 0.7), a[..., :3])
            a[..., :3] = mix(a[..., :3], FLOUR, (0.18 * (1 - V))[..., None])
            if not neck and face in ('top', 'right', 'left') and min(w, h) >= 6:
                ax, _ = along_z(face)
                f = U if ax == 'u' else V
                stripe = (np.abs(f - 0.14) < 0.035) | (np.abs(f - 0.86) < 0.035)
                a[..., :3] = np.where(stripe[..., None], mix(a[..., :3], STRIPE, 0.75), a[..., :3])
        elif part == 'top' and face == 'top' and min(w, h) >= 8:
            # the stencilled sheaf: three stalks fanning from a band, ears at their heads
            x, y = (U - 0.5) * w / h * 1.2, V - 0.5
            st = (np.abs(x) < 0.03) | (np.abs(x - y * 0.45) < 0.03) | (np.abs(x + y * 0.45) < 0.03)
            st &= (y > -0.28) & (y < 0.28)
            ears = (np.hypot(x, y + 0.3) < 0.07) | (np.hypot(x + 0.13, y + 0.26) < 0.06) | (np.hypot(x - 0.13, y + 0.26) < 0.06)
            bnd = (np.abs(y - 0.08) < 0.03) & (np.abs(x) < 0.12)
            a[..., :3] = np.where((st | ears | bnd)[..., None], mix(a[..., :3], STENCIL, 0.62), a[..., :3])
        return tt.shade_edges(a, 0.34 if not neck else 0.2, width=max(1, min(w, h) // 4))
    return painter(key, fn)


def cord_painter(key, flour=False):
    def fn(face, w, h, rng):
        col = (214, 206, 186) if flour else (116, 90, 54)
        a = tt.noise_fill(w, h, col, rng, 0.08)
        U, V = uv(w, h)
        tw = np.sin((U + V) * max(w, h) * 1.6) > 0
        a[..., :3] = np.where(tw[..., None], a[..., :3] * 0.8, a[..., :3])
        return a
    return painter(key, fn)


def crate_painter(key, base=CRATE, dark=CRATE_D, light=CRATE_L, inside='straw'):
    """An open crate: slats nailed to corner posts, gaps between them; open on top, its bedding inside (straw for
    roots and fruit, crushed ice for fish), the goods (drawn by the game) on it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            if inside == 'ice':
                a = tt.noise_fill(w, h, ICE, rng, 0.06)
                chips = rng.random((h, w))
                a[..., :3] = np.where((chips < 0.18)[..., None], np.array(ICE_L, float), a[..., :3])
                a[..., :3] = np.where((chips > 0.9)[..., None], np.array(ICE_D, float), a[..., :3])
            else:
                a = solid(w, h, (58, 40, 24))
                for _ in range(max(8, w * h // 6)):
                    x0, y0 = rng.uniform(0, w), rng.uniform(0, h)
                    ang = rng.uniform(0, math.pi)
                    ln = rng.uniform(1.5, 4.0) * SCALE / 2
                    for s in np.linspace(0, ln, int(ln * 2) + 2):
                        xi, yi = int(x0 + s * math.cos(ang)), int(y0 + s * math.sin(ang))
                        if 0 <= xi < w and 0 <= yi < h:
                            a[yi, xi, :3] = mix(STRAW_D, STRAW_L, rng.random())
            rim = (U < 0.09) | (U > 0.91) | (V < 0.09) | (V > 0.91)
            a[..., :3] = np.where(rim[..., None], grain(w, h, 'u', light, dark, light, rng)[..., :3], a[..., :3])
            return a
        if face == 'bottom':
            a, _ = boards(w, h, rng, 3, 'u', dark, (70, 50, 30), base)
            return a
        a = grain(w, h, 'u', base, dark, light, rng, streak=0.4)
        n = 3
        gap = ((V * n) % 1.0) > 0.84
        a[..., :3] = np.where(gap[..., None], np.array((34, 24, 16), float), a[..., :3])
        post = (U < 0.12) | (U > 0.88)
        postw = grain(w, h, 'v', dark, (60, 40, 24), base, rng, streak=0.3)
        a[..., :3] = np.where(post[..., None], postw[..., :3], a[..., :3])
        for row in range(n):
            for cu in (0.06, 0.94):
                nail(a, cu, (row + 0.42) / n, 0.45)
        if inside == 'ice':
            wet = np.clip(1.2 - V * 1.6, 0, 1) * 0.25
            a[..., :3] = mix(a[..., :3], (40, 40, 44), wet[..., None])
        return tt.shade_edges(a, 0.15)
    return painter(key, fn)


def basket_painter(key):
    """A baker's basket: wicker woven over and under, a plaited rim, a checked cloth tucked in over the loaves."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = tt.noise_fill(w, h, CLOTH, rng, 0.04)
            check = ((U * 5).astype(int) + (V * 5).astype(int)) % 2 == 0
            a[..., :3] = np.where(check[..., None], np.array(CLOTH_L, float), a[..., :3])
            fold = np.sin(U * 7.0 + V * 3.0) * 0.06
            a[..., :3] *= (1 + fold)[..., None]
            rim = (U < 0.1) | (U > 0.9) | (V < 0.1) | (V > 0.9)
            a[..., :3] = np.where(rim[..., None], weave(w, h, WICKER_D, (90, 60, 30), WICKER, rng, cell=2)[..., :3], a[..., :3])
            return a
        a = weave(w, h, WICKER, WICKER_D, WICKER_L, rng, cell=max(2, min(w, h) // 4))
        if face != 'bottom':
            rim = V < 0.2
            plait = weave(w, h, WICKER_L, WICKER_D, (236, 204, 144), rng, cell=2)
            a[..., :3] = np.where(rim[..., None], plait[..., :3], a[..., :3])
        return tt.shade_edges(a, 0.2)
    return painter(key, fn)


def handle_painter(key):
    """The basket's handle, arched across it from side to side (drawn on a flat cube across the basket)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, WICKER, WICKER_D, WICKER_L, rng, cell=max(1, min(w, h) // 3))
        if face in ('front', 'back'):
            x = (U - 0.5) * 2
            y = 1 - V
            r = np.hypot(x, y * 1.05)
            keep = (r <= 1.0) & (r >= 0.68) & (y >= 0)
            a[~keep] = 0
        elif face == 'top':
            a[np.abs(U - 0.5) > 0.16] = 0
        elif face == 'bottom':
            a[np.abs(U - 0.5) < 0.34] = 0
        return a
    return painter(key, fn)

# ---------------------------------------------------------------- a household's goods, heaped for a move

CHEST = (92, 58, 36); CHEST_D = (54, 32, 20); CHEST_L = (132, 90, 56)          # a chest, its oak stained dark
POT = (54, 52, 56); POT_D = (24, 22, 26); POT_L = (112, 108, 106)                # a cooking pot, black iron
TICK = (222, 214, 192); TICK_D = (164, 154, 130); TICK_L = (240, 236, 222)       # a mattress's ticking
TICK_STRIPE = (92, 108, 150)
HEN = (236, 228, 210); HEN_D = (178, 160, 128); COMB = (196, 40, 36); BEAK = (226, 178, 70)
CHAIR = (152, 114, 72); CHAIR_D = (100, 72, 44); CHAIR_L = (188, 150, 102)      # a beech chair


def chest_painter(key, part='body'):
    """A household's chest: dark-stained boards, iron straps over its corners, a lock plate on its front and the hasp
    over it from the lid, a ring for a handle at each end; the lid's top curved, two straps across it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('top', 'bottom'):
            a, _ = boards(w, h, rng, 3, 'u', CHEST, CHEST_D, CHEST_L, knots=0)
            if part == 'lid' and face == 'top':
                a[..., :3] *= (0.86 + 0.24 * np.sin(np.pi * V))[..., None]
                a = band(a, (np.abs(U - 0.22) < 0.05) | (np.abs(U - 0.78) < 0.05), rng)
            return tt.shade_edges(a, 0.16)
        a, _ = boards(w, h, rng, 2 if part == 'body' else 1, 'u', CHEST, CHEST_D, CHEST_L, knots=1)
        a = weather(a, rng, 0.08, grey=CHEST_L)
        straps = (U < 0.08) | (U > 0.92)
        if part == 'lid' and face in ('front', 'back'):
            straps = straps | (np.abs(U - 0.22) < 0.05) | (np.abs(U - 0.78) < 0.05)
        a = band(a, straps, rng)
        for cu in (0.04, 0.96):
            for cv in ((0.3, 0.75) if part == 'body' else (0.5,)):
                nail(a, cu, cv, 0.5)
        if face == 'front' and part == 'body':
            a = band(a, (np.abs(U - 0.5) < 0.1) & (V < 0.44), rng)
            hole = (np.abs(U - 0.5) < 0.03) & (np.abs(V - 0.24) < 0.08)
            a[..., :3] = np.where(hole[..., None], np.array((10, 8, 8), float), a[..., :3])
        if face == 'front' and part == 'lid':
            a = band(a, (np.abs(U - 0.5) < 0.06) & (V > 0.3), rng)
        if face in ('right', 'left') and part == 'body':
            ring = np.abs(np.hypot((U - 0.5) * w / h, V - 0.42) - 0.17) < 0.06
            a[..., :3] = np.where(ring[..., None], np.array(IRON_L, float) * 0.85, a[..., :3])
        return tt.shade_edges(a, 0.18)
    return painter(key, fn)


def pot_painter(key, part='body'):
    """A family's cooking pot: black iron sooted toward its foot, a rim round its mouth, a lid with a knob, a lug each
    side; eight-sided (the body and the lid twice, the second turned an eighth)."""
    def fn(face, w, h, rng):
        a = metal(w, h, POT, POT_D, POT_L, rng, wear=0.06)
        U, V = uv(w, h)
        if part == 'body' and face not in ('top', 'bottom'):
            a[..., :3] = mix(a[..., :3], (14, 12, 12), (0.55 * V ** 2)[..., None])
            a = band(a, V < 0.14, rng, POT, POT_D, POT_L)
            a[..., :3] *= (0.86 + 0.24 * np.sin(np.pi * U))[..., None]
        elif part == 'lid' and face == 'top':
            a[..., :3] *= (1.1 - 0.5 * np.hypot(U - 0.5, V - 0.5))[..., None]
        return a
    return painter(key, fn)


def cage_painter(key, part):
    """A hen's coop for the road, of willow: [part] 'floor' (woven, solid), 'top' (rods along it, clear between) or a
    wall ('wall_z' the front and back, 'wall_x' the ends: upright rods, clear between, a frame round them). Each wall
    is its own thin cube, so from any side the far wall's rods show through the near one's (the game culls a cube's
    inside faces)."""
    big = {'top': ('top', 'bottom'), 'wall_z': ('front', 'back'), 'wall_x': ('right', 'left')}.get(part, ())

    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, WICKER, WICKER_D, WICKER_L, rng, cell=2)
        if face not in big:
            a[..., :3] = mix(a[..., :3], WICKER_D, 0.2)
            return a
        # rods a texel wide, three texels apart, and a texel of frame round the edge: the hen shows between them; the
        # top's run lengthwise, at CAGE_RODS (px across), so her neck has the middle gap to itself
        ys, xs = np.mgrid[0:h, 0:w]
        if part == 'top':
            x = (U - 0.5) * CAGE_W
            rod = np.min([np.abs(x - r) for r in CAGE_RODS], axis=0) < 0.25
        else:
            rod = ((xs - (w % 3) // 2) % 3) == 1
        frame = (xs == 0) | (xs == w - 1) | (ys == 0) | (ys == h - 1)
        a[..., :3] = np.where(rod[..., None], mix(a[..., :3], WICKER_L, 0.15), a[..., :3])
        a[..., :3] = np.where(frame[..., None], mix(a[..., :3], WICKER_D, 0.35), a[..., :3])
        a[~(rod | frame)] = 0
        return a
    return painter(key, fn)


def hen_painter(key, part='body'):
    """The hen in it: cream feathers in soft scallops, speckled brown; a red comb and wattle, a yellow beak, black
    eyes."""
    def fn(face, w, h, rng):
        if part == 'comb':
            return tt.noise_fill(w, h, COMB, rng, 0.08)
        if part == 'beak':
            return tt.noise_fill(w, h, BEAK, rng, 0.06)
        U, V = uv(w, h)
        a = tt.noise_fill(w, h, HEN, rng, 0.05)
        sc = (np.sin(U * w * 1.4) * np.sin(V * h * 1.4 + U * 2.0)) > 0.55
        a[..., :3] = np.where(sc[..., None], mix(a[..., :3], HEN_D, 0.35), a[..., :3])
        speck = rng.random((h, w)) < 0.1
        a[..., :3] = np.where(speck[..., None], mix(a[..., :3], (124, 86, 50), 0.6), a[..., :3])
        if part == 'head' and face in ('right', 'left'):
            eye = np.hypot(U - 0.45, V - 0.42) < 0.17
            a[..., :3] = np.where(eye[..., None], np.array((20, 14, 10), float), a[..., :3])
        return tt.shade_edges(a, 0.22)
    return painter(key, fn)


ROLL_RINGS = 1.25      # the rolled mattress's end: rings per px out from its middle


def mattress_painter(key, part='roll'):
    """A straw mattress rolled for the move: ticking striped round the roll, its end the rolled layers in rings with
    straw poking out between them; [part] 'cord' for the cords tied round it. The roll is three boxes on one axis (a
    stepped round, as pixels draw a circle), so the rings and stripes are laid out in px from the roll's axis and
    middle, and the three boxes' meet."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if part == 'cord':
            a = tt.noise_fill(w, h, (116, 90, 54), rng, 0.08)
            tw = np.sin((U + V) * max(w, h) * 1.6) > 0
            a[..., :3] = np.where(tw[..., None], a[..., :3] * 0.8, a[..., :3])
            return tt.shade_edges(a, 0.2)
        if face in ('right', 'left'):
            r = np.hypot((U - 0.5) * w / SCALE, (V - 0.5) * h / SCALE)
            ring = (r * ROLL_RINGS) % 1.0
            a = solid(w, h, TICK)
            a[..., :3] = mix(np.array(TICK_D, float) * 0.8, np.array(TICK_L, float), (np.sin(ring * math.pi) ** 0.6)[..., None])
            straw = (rng.random((h, w)) < 0.18) & (ring < 0.3) & (r > 0.3)
            a[..., :3] = np.where(straw[..., None], np.array(STRAW, float), a[..., :3])
            return a
        a = weave(w, h, TICK, TICK_D, TICK_L, rng, cell=1)
        along = np.abs(U - 0.5) * w / SCALE
        stripe = ((along * 0.9) % 1.0) < 0.3
        a[..., :3] = np.where(stripe[..., None], mix(a[..., :3], TICK_STRIPE, 0.7), a[..., :3])
        a[..., :3] *= (0.88 + 0.16 * np.sin(np.pi * V))[..., None]
        return a
    return painter(key, fn)


def chair_painter(key, part):
    """A rush-seated chair of beech: turned legs, two slats across its back, the rush seat woven in from its corners;
    [part] 'seat', 'leg', 'post', 'rail' or 'stretcher'."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if part == 'seat' and face in ('top', 'bottom'):
            a = weave(w, h, STRAW, STRAW_D, STRAW_L, rng, cell=2)
            diag = np.abs(np.abs(U - 0.5) - np.abs(V - 0.5)) < 0.035
            a[..., :3] = np.where(diag[..., None], mix(a[..., :3], STRAW_D, 0.6), a[..., :3])
            rim = (U < 0.08) | (U > 0.92) | (V < 0.08) | (V > 0.92)
            a[..., :3] = np.where(rim[..., None], grain(w, h, 'u', CHAIR, CHAIR_D, CHAIR_L, rng)[..., :3], a[..., :3])
            return a
        upright = part in ('leg', 'post')
        a = grain(w, h, 'v' if upright else 'u', CHAIR, CHAIR_D, CHAIR_L, rng, streak=0.4)
        if upright and face not in ('top', 'bottom'):
            a[..., :3] *= (0.8 + 0.3 * np.sin(np.pi * U))[..., None]
            rings = np.abs(((V * (5 if part == 'post' else 3)) % 1.0) - 0.5) < 0.07
            a[..., :3] = np.where(rings[..., None], mix(a[..., :3], CHAIR_D, 0.5), a[..., :3])
        return tt.shade_edges(a, 0.15)
    return painter(key, fn)

# ================================================================== the model


def C(origin, size, name, paint, share=None):
    c = Cube(origin, size, paint, name=name)
    c.share = share
    return c


def bed():
    cubes = []
    fl = C((-BED_X, FLOOR_TOP, BED_FRONT), (2 * BED_X, 1.0, BED_BACK - BED_FRONT), 'c_floor', floor_painter('c_floor'))
    cubes.append(fl)
    for s, n in ((-1, 'r'), (1, 'l')):
        x0 = BED_X if s > 0 else -BED_X - 1.0
        cubes.append(C((x0, SIDE_TOP, BED_FRONT), (1.0, -SIDE_TOP + FLOOR_TOP + 1.0, BED_BACK - BED_FRONT), f'c_side_{n}',
                       side_painter(f'c_side_{n}', 'right' if s < 0 else 'left')))
        rx = s * RAIL_X - RAIL_T / 2
        cubes.append(C((rx, RAIL_Y - RAIL_T / 2, RAIL_FRONT), (RAIL_T, RAIL_T, RAIL_BACK - RAIL_FRONT), f'c_rail_{n}',
                       rail_painter(f'c_rail_{n}', RAIL_FRONT, RAIL_BACK)))
        cubes.append(C((s * RAIL_X - 0.75, RAIL_Y + RAIL_T / 2, -1.75), (1.5, 3.5, 3.5), f'c_hanger_{n}', hanger_painter(f'c_hanger_{n}')))
        for z in (BED_FRONT + 0.4, BED_BACK - 1.6):
            cubes.append(C((s * (BED_X + 0.5) - 0.6, SIDE_TOP - 1.0, z), (1.2, 5.0, 1.2), f'c_post_{n}{int(z > 0)}', post_painter(f'c_post_{n}{int(z > 0)}')))
    cubes.append(C((-BED_X, FLOOR_TOP - 2.5, BED_FRONT), (2 * BED_X, 2.5, 1.0), 'c_front', end_painter('c_front')))
    cubes.append(C((-BED_X, FLOOR_TOP - 2.5, BED_BACK - 1.0), (2 * BED_X, 2.5, 1.0), 'c_tail', end_painter('c_tail')))
    cubes.append(C((-RAIL_X + RAIL_T / 2, RAIL_Y - RAIL_T / 2, BED_FRONT - 3.2), (2 * RAIL_X - RAIL_T, RAIL_T, 1.0), 'c_stretcher', bar_painter('c_stretcher')))
    for i, z in enumerate((-12.0, 6.0)):
        cubes.append(C((-RAIL_X + RAIL_T / 2, FLOOR_TOP + 1.0, z), (2 * RAIL_X - RAIL_T, 1.0, 1.5), f'c_bearer{i}', bar_painter(f'c_bearer{i}')))
    cubes.append(C((-WHEEL_X - 1.0, -0.75, -0.75), (2 * WHEEL_X + 2.0, 1.5, 1.5), 'c_axle', axle_painter('c_axle')))
    return Part('bed', (0, 0, 0), (0, 0, 0), cubes=cubes)


def wheel(side):
    """A wheel at [side] (-1 the right, 1 the left): twelve felloes round twelve spokes, the nave on the axle, a cap
    on the axle's end outside. Every wheel shares the one felloe's, spoke's and nave's paint (and texture)."""
    name = 'wheel_r' if side < 0 else 'wheel_l'
    kids = []
    for k in range(12):
        kids.append(Part(f'{name}_rim{k}', (0, 0, 0), (k * 30.0, 0, 0), cubes=[
            C((-1.0, -R, -2.75), (2.0, 1.5, 5.5), f'c_{name}_rim{k}', None, share='c_rim')]))
    for k in range(6):
        kids.append(Part(f'{name}_spoke{k}', (0, 0, 0), (k * 30.0, 0, 0), cubes=[
            C((-0.5, -R + 1.4, -0.5), (1.0, 2 * (R - 1.4), 1.0), f'c_{name}_spoke{k}', None, share='c_spoke')]))
    out = side          # +x is outward on the left wheel
    hub0 = -1.25 if out > 0 else -1.75
    kids.append(Part(f'{name}_nave45', (0, 0, 0), (45.0, 0, 0), cubes=[
        C((hub0, -1.75, -1.75), (3.0, 3.5, 3.5), f'c_{name}_nave45', None, share='c_nave')]))
    cubes = [C((hub0, -1.75, -1.75), (3.0, 3.5, 3.5), f'c_{name}_nave', None, share='c_nave'),
             C((1.75 if out > 0 else -2.75, -1.0, -1.0), (1.0, 2.0, 2.0), f'c_{name}_cap', None, share='c_cap')]
    return Part(name, (side * WHEEL_X, 0, 0), (0, 0, 0), cubes=cubes, children=kids)


def load_parts():
    """The things that heap on the bed, each standing on the middle of its underside at its part's origin."""
    def sack(name, flour):
        # a pillow of a sack: narrower where it sits and on top than round its belly; the neck gathered at the front
        base = C((-2.8, -1.2, -2.8), (5.6, 1.2, 5.6), f'l_{name}_base', sack_painter(f'l_{name}_base', flour, 'base'))
        body = C((-3.1, -3.5, -3.1), (6.2, 2.3, 6.2), f'l_{name}', sack_painter(f'l_{name}', flour, 'body'))
        top = C((-2.6, -4.5, -2.7), (5.2, 1.0, 5.4), f'l_{name}_top', sack_painter(f'l_{name}_top', flour, 'top'))
        neck = C((-1.2, -3.3, -3.9), (2.4, 2.2, 0.8), f'l_{name}_neck', sack_painter(f'l_{name}_neck', flour, 'neck'))
        tie = C((-1.35, -3.45, -3.6), (2.7, 2.5, 0.45), f'l_{name}_tie', cord_painter(f'l_{name}_tie', flour))
        return Part(name, (0, 0, 0), (0, 0, 0), cubes=[base, body, top, neck, tie])
    crate = Part('crate', (0, 0, 0), (0, 0, 0), cubes=[C((-3.1, -4.6, -3.3), (6.2, 4.6, 6.6), 'l_crate', crate_painter('l_crate'))])
    basket = Part('basket', (0, 0, 0), (0, 0, 0), cubes=[
        C((-3.0, -3.6, -3.2), (6.0, 3.6, 6.4), 'l_basket', basket_painter('l_basket')),
        C((-2.8, -6.6, -0.25), (5.6, 3.0, 0.5), 'l_basket_handle', handle_painter('l_basket_handle')),
    ])
    fish = Part('fish', (0, 0, 0), (0, 0, 0), cubes=[C((-3.1, -2.8, -3.3), (6.2, 2.8, 6.6), 'l_fish',
                                                     crate_painter('l_fish', FISHBOX, FISHBOX_D, FISHBOX_L, inside='ice'))])
    return [sack('sack', False), sack('flour', True), crate, basket, fish]


ROLL_R = 1.6                     # the rolled mattress's radius (it lies across, its axis this high)
HEN_HEAD = (0.0, -3.0, -0.9)     # the hen's neck, in the coop's frame: "cage_hen_head" turns about it, her neck up
                                 # through the top's middle gap (a peck tips it along the gap, a look turns it in it)
CAGE_W = 5.6                     # the coop's width, and the top's rods (px across: they run lengthwise)
CAGE_RODS = (-1.95, -0.8, 0.8, 1.95)


def household_parts():
    """A household's goods for a move, each standing on the middle of its underside: a chest, the cooking pot, the
    bundle of their bedding and clothes (PACK's own: the same cubes and the same paint, so the one it sets on the bed
    and the one the cart then draws are one thing), a hen in her coop (her head on a part of its own,
    "cage_hen_head", that HandcartMesh.henHead turns as she looks about and pecks), the mattress rolled and tied, and
    a chair. (Not in load_parts(): the caravan's wagon heaps those, and these are a household's, not a trader's.)"""
    import living_props as lp       # the bundle's paint (here, so the wagon's script importing this one stays light)
    chest = Part('chest', (0, 0, 0), (0, 0, 0), cubes=[
        C((-3.2, -3.5, -2.4), (6.4, 3.5, 4.8), 'l_chest', chest_painter('l_chest', 'body')),
        C((-3.35, -4.7, -2.55), (6.7, 1.2, 5.1), 'l_chest_lid', chest_painter('l_chest_lid', 'lid'))])
    # eight-sided: a box and the same turned an eighth (a little smaller, so its corners round the first's sides off
    # and its top sits a hair under the first's)
    pot = Part('pot', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.4, -3.3, -2.4), (4.8, 3.3, 4.8), 'l_pot', pot_painter('l_pot', 'body')),
        C((-2.05, -3.8, -2.05), (4.1, 0.5, 4.1), 'l_pot_lid', pot_painter('l_pot_lid', 'lid')),
        C((-0.5, -4.4, -0.5), (1.0, 0.6, 1.0), 'l_pot_knob', pot_painter('l_pot_knob', 'knob')),
        C((-3.6, -3.0, -0.45), (0.7, 0.6, 0.9), 'l_pot_lug_r', None, share='l_pot_lug'),
        C((2.9, -3.0, -0.45), (0.7, 0.6, 0.9), 'l_pot_lug_l', None, share='l_pot_lug'),
    ], children=[Part('pot45', (0, 0, 0), (0, 45.0, 0), cubes=[
        C((-2.1, -3.2, -2.1), (4.2, 3.2, 4.2), 'l_pot45', pot_painter('l_pot45', 'body')),
        C((-1.8, -3.7, -1.8), (3.6, 0.4, 3.6), 'l_pot_lid45', pot_painter('l_pot_lid45', 'lid'))])])
    bundle = Part('bundle', (0, 0, 0), (0, 0, 0), cubes=[
        C((-3.6, -5.6, -3.0), (7.2, 5.6, 6.0), 'l_bundle', lp.bundle_painter('t_bundle')),
        C((-2.5, -6.4, -2.1), (5.0, 0.8, 4.2), 'l_bundle_top', lp.bundle_painter('t_bundle_top')),
        C((-1.0, -7.6, -1.0), (2.0, 1.4, 2.0), 'l_bundle_knot', lp.bundle_painter('t_bundle_knot', knot=True)),
        C((-2.0, -7.3, -0.3), (4.0, 0.8, 0.6), 'l_bundle_ears', lp.bundle_painter('t_bundle_ears', knot=True))])
    # the coop: a woven floor, rods across the top, and its four walls each a thin cube of its own; the hen sits in it
    # with her neck up between two of the top's rods, her head out over it
    cage = Part('cage', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.8, -0.4, -2.4), (5.6, 0.4, 4.8), 'l_cage_floor', cage_painter('l_cage_floor', 'floor')),
        C((-2.8, -4.0, -2.4), (5.6, 0.3, 4.8), 'l_cage_top', cage_painter('l_cage_top', 'top')),
        C((-2.8, -3.7, -2.4), (5.6, 3.3, 0.3), 'l_cage_front', None, share='l_cage_wz'),
        C((-2.8, -3.7, 2.1), (5.6, 3.3, 0.3), 'l_cage_back', None, share='l_cage_wz'),
        C((-2.8, -3.7, -2.1), (0.3, 3.3, 4.2), 'l_cage_right', None, share='l_cage_wx'),
        C((2.5, -3.7, -2.1), (0.3, 3.3, 4.2), 'l_cage_left', None, share='l_cage_wx'),
        C((-1.3, -3.0, -1.5), (2.6, 2.6, 3.0), 'l_hen', hen_painter('l_hen', 'body')),
        C((-0.5, -3.6, 1.1), (1.0, 1.3, 0.75), 'l_hen_tail', hen_painter('l_hen_tail', 'tail')),
    ], children=[Part('cage_hen_head', HEN_HEAD, (0, 0, 0), cubes=[
        C((-0.35, -1.75, -0.35), (0.7, 1.85, 0.7), 'l_hen_neck', hen_painter('l_hen_neck', 'neck')),
        C((-0.6, -2.8, -0.85), (1.2, 1.2, 1.3), 'l_hen_head', hen_painter('l_hen_head', 'head')),
        C((-0.15, -3.25, -0.65), (0.3, 0.5, 0.95), 'l_hen_comb', hen_painter('l_hen_comb', 'comb')),
        C((-0.2, -2.35, -1.3), (0.4, 0.3, 0.45), 'l_hen_beak', hen_painter('l_hen_beak', 'beak')),
        C((-0.2, -2.05, -1.05), (0.4, 0.45, 0.2), 'l_hen_wattle', hen_painter('l_hen_wattle', 'comb'))])])
    # the roll lies across the bed: three boxes on its axis, 1.6 px up (wide, tall and square: a stepped round, each
    # one's ends a hair inside the last's), and a cord round it near either end, the same three a little bigger
    def stepped(name, x0, length, r, inset, share=None):
        out = []
        for k, (hz, hy) in enumerate(((r, 0.625 * r), (0.625 * r, r), (0.8625 * r, 0.8625 * r))):
            g = inset * k
            out.append(C((x0 + g, -ROLL_R - hy, -hz), (length - 2 * g, 2 * hy, 2 * hz), f'{name}_{"abc"[k]}',
                         None if share else mattress_painter(f'{name}_{"abc"[k]}'), share=share and f'{share}_{"abc"[k]}'))
        return out
    mattress = Part('mattress', (0, 0, 0), (0, 0, 0), cubes=stepped('l_roll', -3.7, 7.4, ROLL_R, 0.04)
                    + stepped('l_cord_r', -2.45, 0.5, ROLL_R + 0.1, 0.03, share='l_cord')
                    + stepped('l_cord_l', 1.95, 0.5, ROLL_R + 0.1, 0.03, share='l_cord'))
    legs = [C((sx * 2.25 - 0.35, -3.7, sz * 2.05 - 0.35), (0.7, 3.7, 0.7), f'l_chair_leg{i}', None, share='l_chair_leg')
            for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1)))]
    posts = [C((sx * 2.25 - 0.35, -10.4, 1.7), (0.7, 6.0, 0.7), f'l_chair_post{i}', None, share='l_chair_post')
             for i, sx in enumerate((-1, 1))]
    rails = [C((-1.9, y, 1.8), (3.8, 0.9, 0.4), f'l_chair_rail{i}', None, share='l_chair_rail') for i, y in enumerate((-9.9, -7.5))]
    stretchers = [C((-1.9, -1.6, sz * 2.05 - 0.2), (3.8, 0.4, 0.4), f'l_chair_str{i}', None, share='l_chair_str')
                  for i, sz in enumerate((-1, 1))]
    chair = Part('chair', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.8, -4.4, -2.6), (5.6, 0.7, 5.2), 'l_chair_seat', chair_painter('l_chair_seat', 'seat'))] + legs + posts + rails + stretchers)
    return [chest, pot, bundle, cage, mattress, chair]


SHARED = {'c_rim': rim_painter('c_rim'), 'c_spoke': spoke_painter('c_spoke'), 'c_nave': hub_painter('c_nave'), 'c_cap': cap_painter('c_cap'),
          'l_pot_lug': pot_painter('l_pot_lug', 'lug'), 'l_cage_wz': cage_painter('l_cage_wz', 'wall_z'),
          'l_cage_wx': cage_painter('l_cage_wx', 'wall_x'), 'l_cord_a': mattress_painter('l_cord_a', 'cord'),
          'l_cord_b': mattress_painter('l_cord_b', 'cord'), 'l_cord_c': mattress_painter('l_cord_c', 'cord'),
          'l_chair_leg': chair_painter('l_chair_leg', 'leg'),
          'l_chair_post': chair_painter('l_chair_post', 'post'), 'l_chair_rail': chair_painter('l_chair_rail', 'rail'),
          'l_chair_str': chair_painter('l_chair_str', 'stretcher')}


def build():
    cart = Model('handcart', TEX_W, TEX_H, [bed(), wheel(-1), wheel(1)])
    load = Model('handcart_load', TEX_W, TEX_H, load_parts() + household_parts())
    every = list(cart.all_cubes()) + list(load.all_cubes())
    names = [c.name for c in every]
    dup = {n for n in names if names.count(n) > 1}
    if dup:
        raise SystemExit(f'handcart: cube names must be unique: {sorted(dup)}')
    pack(every)
    return cart, load


def pack(cubes):
    """Skyline-pack every cube's box UV (the lowest fit first), each shared paint once: cubes with the same [share]
    take the first one's place in the texture."""
    W = TEX_W
    sky = [[0, 0, W]]
    placed = {}
    todo = []
    for c in cubes:
        if c.share and c.share in placed:
            continue
        placed[c.share or c.name] = c
        todo.append(c)
    for c in sorted(todo, key=lambda c: (-c.footprint()[1], -c.footprint()[0], c.name)):
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
                best = (key, x, y)
        if best is None:
            raise SystemExit(f'handcart: {c.name} ({fw}x{fh}) does not fit')
        _, x, y = best
        c.uv = (x, y)
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
        sky = []
        for seg in new:
            if sky and sky[-1][1] == seg[1] and sky[-1][0] + sky[-1][2] == seg[0]:
                sky[-1][2] += seg[2]
            else:
                sky.append(seg)
    for c in cubes:
        if c.share and c.uv is None:
            c.uv = placed[c.share].uv
    used = max(c.uv[1] + c.footprint()[1] for c in cubes)
    if used > TEX_H:
        raise SystemExit(f'handcart: texture too small, need {used} rows of {TEX_H}')
    return used


def rects2x(c, s=SCALE):
    """A cube's six face rects at [s] times the declared size, placed from its true (float) size as Minecraft maps
    box UV (as townsfolk.py's)."""
    u, v = c.uv
    w, h, d = c.size

    def Rr(x0, y0, ww, hh):
        X0, Y0 = math.floor(round(x0 * s, 6)), math.floor(round(y0 * s, 6))
        X1, Y1 = math.ceil(round((x0 + ww) * s, 6)), math.ceil(round((y0 + hh) * s, 6))
        return (X0, Y0, X1 - X0, Y1 - Y0)
    return {'top': Rr(u + d, v, w, d), 'bottom': Rr(u + d + w, v, w, d), 'right': Rr(u, v + d, d, h),
            'front': Rr(u + d, v + d, w, h), 'left': Rr(u + d + w, v + d, d, h), 'back': Rr(u + 2 * d + w, v + d, w, h)}


def texture(cart, load):
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    done = set()
    for c in list(cart.all_cubes()) + list(load.all_cubes()):
        key = c.share or c.name
        if key in done:
            continue
        done.add(key)
        p = SHARED[c.share] if c.share else c.paint
        for face, (x, y, w, h) in rects2x(c).items():
            if w > 0 and h > 0:
                p(img, face, x, y, w, h)
    return img

# ================================================================== posing and previews (as the game's renderer)


def rotx(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def roty(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rotz(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]])


def rot_part(rot):
    return rotz(rot[2]) @ roty(rot[1]) @ rotx(rot[0])


def model_faces(model, M=np.eye(3), T=np.zeros(3), extra=None, only=None):
    """Every drawn face of [model] (or of its parts named in [only]) as (A, B, D, rect, face, normal) at the HD
    texture's rects, the bottom faces as Minecraft maps them, placed by M and T (model space, y down). [extra] gives
    a part an added rotation about x (degrees): a wheel's turn."""
    out = []
    extra = extra or {}

    def go(part, M0, T0):
        e = extra.get(part.name, 0.0)       # degrees about x (a wheel's turn), or a part's own turn (a matrix)
        Rl = rot_part(part.rot) @ (np.asarray(e, float) if np.ndim(e) == 2 else rotx(e))
        Rm = M0 @ Rl
        P = T0 + M0 @ np.array(part.pivot, float)
        for c in part.cubes:
            x0, y0, z0 = c.origin
            x1, y1, z1 = [o + s for o, s in zip(c.origin, c.size)]

            def W(x, y, z):
                return P + Rm @ np.array([x, y, z], float)
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
                out.append((A, B, D, rects[face], face, Rm @ np.array(n, float)))
        for ch in part.children:
            go(ch, Rm, P)
    for p in model.parts:
        if only and p.name not in only:
            continue
        go(p, M, T)
    return out


def cart_pose(pitch=0.0, roll=0.0, spin=0.0):
    """The cart's own rotation (pitch nose-up, roll its right side up, degrees, about the axle) as the renderer gives
    it, in model space; and each wheel's turn, kept still against the ground as the bed tips (as HandcartModel)."""
    # The renderer turns the world frame (y up) by pitch about x and roll about z, then flips to model space (y down,
    # x mirrored): in model space that is the same turns with their signs changed.
    M = rotx(-pitch) @ rotz(-roll)
    turn = spin + pitch
    return M, {'wheel_r': turn, 'wheel_l': turn}


def piece_faces(load, kind, slot, lift=0.0, layer_y=0.0, M=np.eye(3), T=np.zeros(3), extra=None):
    """One of the load's pieces in its place on the bed ([layer_y] up, on whatever it rests on), the cart turned by M
    and moved by T; [extra] turns its parts (model_faces)."""
    x, z, yaw, layer, _ = SLOTS[slot]
    P = np.asarray(T, float) + M @ np.array([x, FLOOR_TOP - layer_y - lift, z])
    return model_faces(load, M @ roty(-yaw), P, extra=extra, only={kind})


SPRITES = {}


def sprite(name):
    """A stand-in for a good's item, for the previews only (the game draws the real item): 16x16, flat."""
    if name in SPRITES:
        return SPRITES[name]
    a = np.zeros((16, 16, 4), float)
    ys, xs = np.mgrid[0:16, 0:16] + 0.5
    def ell(cx, cy, rx, ry, col, ang=0.0):
        ca, sa = math.cos(ang), math.sin(ang)
        u = ((xs - cx) * ca + (ys - cy) * sa) / rx
        v = (-(xs - cx) * sa + (ys - cy) * ca) / ry
        d = u * u + v * v
        on = d <= 1
        shade = 1.0 - 0.35 * np.clip(d, 0, 1)
        a[on, :3] = np.array(col, float) * shade[on, None]
        a[on, 3] = 255
    if name == 'carrot':
        ell(8, 9, 2.4, 6.0, (232, 120, 36), 0.6)
        ell(12, 3.5, 1.6, 2.4, (70, 140, 50), 0.6)
    elif name == 'apple':
        ell(8, 9, 5.5, 5.0, (196, 36, 32))
        ell(9, 3, 1.0, 2.0, (90, 60, 30))
        ell(6, 7, 1.6, 1.2, (240, 130, 120))
    elif name == 'potato':
        ell(8, 8.5, 5.5, 4.2, (176, 134, 78), 0.3)
    elif name == 'beetroot':
        ell(8, 9.5, 4.6, 4.6, (128, 30, 52))
        ell(8, 3.5, 2.2, 2.6, (60, 120, 50))
    elif name == 'bread':
        ell(8, 8.5, 6.8, 3.6, (198, 140, 70), 0.25)
        ell(8, 7.5, 5.2, 2.0, (226, 180, 106), 0.25)
    elif name == 'cod':
        ell(7.5, 8, 6.0, 2.8, (176, 160, 120))
        ell(14, 8, 1.6, 2.6, (150, 130, 96))
    elif name == 'salmon':
        ell(7.5, 8, 6.0, 2.8, (196, 90, 70))
        ell(14, 8, 1.6, 2.6, (160, 70, 56))
    img = Image.fromarray(a.astype(np.uint8), 'RGBA')
    SPRITES[name] = img
    return img


def item_faces(kind, slot, item, atlas, layer_y=0.0, M=np.eye(3)):
    """The goods shown on an open piece, as the renderer lays them: flat, a little turned and tipped, on its top."""
    info = ALL_PIECES[kind]
    x, z, yaw, layer, _ = SLOTS[slot]
    out = []
    size = info.get('size', 0.4) * 16
    for i, (ix, iz, iyaw, tilt) in enumerate(info['shows']):
        u0 = atlas.extra(item)
        L = M @ roty(-yaw)
        P = M @ np.array([x, FLOOR_TOP - layer_y - info['height'] - 0.25 - 0.15 * i, z]) + L @ np.array([ix, 0.0, iz])
        R2 = L @ roty(-iyaw) @ rotx(tilt)
        hs = size / 2
        A = P + R2 @ np.array([-hs, 0, -hs])
        B = P + R2 @ np.array([hs, 0, -hs])
        D = P + R2 @ np.array([-hs, 0, hs])
        out.append((A, B, D, (u0, atlas.h0, 16, 16), 'top', R2 @ np.array([0, -1.0, 0])))
    return out


class Atlas:
    """The texture with the preview stand-in sprites below it."""
    def __init__(self, tex):
        self.h0 = tex.height
        self.names = []
        self.tex = tex

    def extra(self, name):
        if name not in self.names:
            self.names.append(name)
        return self.names.index(name) * 16

    def image(self):
        out = Image.new('RGBA', (self.tex.width, self.tex.height + 16), (0, 0, 0, 0))
        out.paste(self.tex, (0, 0))
        for i, n in enumerate(self.names):
            out.paste(sprite(n), (i * 16, self.h0))
        return out


def render(faces, tex, yaw=-35, pitch=-22, scale=7.0, size=(420, 330), center=(0.0, -2.0, -6.0), ground=True):
    """An orthographic view of [faces] (model space, y down), lit from the upper left front, over a strip of ground."""
    W, H = size
    img = np.zeros((H, W, 3), float)
    img[...] = (58, 64, 80)
    zbuf = np.full((H, W), 1e9)
    T = np.array(tex.convert('RGBA'), float)
    V = rotx(-pitch) @ roty(yaw)     # pitch below 0 looks down on it
    c = np.asarray(center, float)
    light = np.array([-0.45, -0.8, -0.4])
    light /= np.linalg.norm(light)

    def view(p):
        q = V @ (np.asarray(p, float) - c)
        return np.array([W / 2 + q[0] * scale, H / 2 + q[1] * scale, q[2]])
    ys, xs = np.mgrid[0:H, 0:W]
    quads = list(faces)
    if ground:
        g = R
        quads_g = []
        for i in range(-5, 5):
            for j in range(-6, 4):
                A = np.array([i * 8.0, g, j * 8.0])
                quads_g.append((A, A + [8, 0, 0], A + [0, 0, 8], None, ((i + j) % 2), np.array([0, -1.0, 0])))
        quads = quads_g + quads
    for A, B, D, rect, face, n in quads:
        a, b, d = view(A), view(B), view(D)
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        corners = [a, b, d, b + e2]
        x0 = int(max(0, min(q[0] for q in corners))); x1 = int(min(W - 1, max(q[0] for q in corners) + 1))
        y0 = int(max(0, min(q[1] for q in corners))); y1 = int(min(H - 1, max(q[1] for q in corners) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]
        py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        if rect is None:
            col = np.zeros(s.shape + (3,)) + ((84, 100, 66) if face else (78, 94, 62))
            alpha = np.ones(s.shape)
            ok = inside & (depth < zbuf[y0:y1, x0:x1])
        else:
            u, v, w, h = rect
            if w == 0 or h == 0:
                continue
            tu = np.clip((u + s * w).astype(int), u, u + w - 1)
            tv = np.clip((v + t * h).astype(int), v, v + h - 1)
            texel = T[np.clip(tv, 0, T.shape[0] - 1), np.clip(tu, 0, T.shape[1] - 1)]
            nn = np.asarray(n, float)
            k = 0.55 + 0.5 * max(0.0, float(np.dot(nn, -light))) + 0.1 * max(0.0, float(-nn[1]))
            col = texel[..., :3] * min(1.15, k)
            alpha = texel[..., 3] / 255.0
            ok = inside & (texel[..., 3] > 8) & (depth < zbuf[y0:y1, x0:x1])
        region = img[y0:y1, x0:x1]
        region[...] = np.where(ok[..., None], col * alpha[..., None] + region * (1 - alpha[..., None]), region)
        zb = zbuf[y0:y1, x0:x1]
        zb[ok & (alpha > 0.6)] = depth[ok & (alpha > 0.6)]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


def scene(cart, load, atlas, goods=(), pitch=0.0, roll=0.0, spin=0.0):
    """The cart posed (pitch, roll, wheels' turn) with [goods] (kind, item or None) heaped in slot order."""
    M, extra = cart_pose(pitch, roll, spin)
    faces = model_faces(cart, M, extra=extra)
    tops = {}
    for i, (kind, item) in enumerate(goods):
        x, z, yaw, layer, under = SLOTS[i]
        rest = max((ALL_PIECES[goods[j][0]]['height'] for j in under if j < len(goods)), default=0.0)
        faces += piece_faces(load, kind, i, layer_y=rest, M=M)
        if item:
            faces += item_faces(kind, i, item, atlas, layer_y=rest, M=M)
    return faces


def previews(cart, load, tex, out):
    os.makedirs(out, exist_ok=True)
    atlas = Atlas(tex)
    sacks = [('sack', None), ('sack', None), ('flour', None), ('flour', None), ('sack', None), ('flour', None), ('sack', None), ('flour', None)]
    produce = [('crate', 'carrot'), ('crate', 'apple'), ('basket', 'bread'), ('fish', 'cod'), ('crate', 'potato'), ('sack', None),
               ('crate', 'beetroot'), ('basket', 'bread')]
    shots = {
        'empty': scene(cart, load, atlas, (), spin=10.0),
        'sacks': scene(cart, load, atlas, sacks, spin=10.0),
        'produce': scene(cart, load, atlas, produce, spin=10.0),
        'parked': scene(cart, load, atlas, sacks[:5], pitch=-parked_tilt()),
    }
    tex2 = atlas.image()
    paths = []
    for name, faces in shots.items():
        a = render(faces, tex2, yaw=215, pitch=-24, scale=7.5)
        b = render(faces, tex2, yaw=40, pitch=-30, scale=7.5)
        img = Image.new('RGB', (a.width * 2, a.height + 18), (30, 32, 40))
        img.paste(a, (0, 18))
        img.paste(b, (a.width, 18))
        ImageDraw.Draw(img).text((6, 3), f'handcart: {name}' + (f' (tipped {parked_tilt():.1f} degrees onto its shafts)' if name == 'parked' else ''), fill=(235, 235, 235))
        p = os.path.join(out, f'handcart_{name}.png')
        img.save(p)
        paths.append(p)
    side = render(scene(cart, load, atlas, sacks[:6], pitch=-parked_tilt()), tex2, yaw=-90, pitch=0, scale=6, size=(330, 230), center=(0, -2, -8))
    side2 = render(scene(cart, load, atlas, produce[:6], spin=0), tex2, yaw=-90, pitch=0, scale=6, size=(330, 230), center=(0, -2, -8))
    top = render(scene(cart, load, atlas, produce, spin=0), tex2, yaw=0, pitch=-89.9, scale=6, size=(330, 330), center=(0, -2, -8), ground=False)
    img = Image.new('RGB', (330 * 3, 348), (30, 32, 40))
    img.paste(side, (0, 18)); img.paste(side2, (330, 18)); img.paste(top, (660, 18))
    ImageDraw.Draw(img).text((6, 3), 'handcart: parked (side), pulled on the flat (side), from above', fill=(235, 235, 235))
    p = os.path.join(out, 'handcart_views.png')
    img.save(p)
    paths.append(p)
    tex.save(os.path.join(out, 'handcart_texture.png'))
    return paths + household_previews(cart, load, tex2, out)


def household_scene(cart, load, n=len(HOUSEHOLD), lifts=None, t=0.0, pitch=0.0, spin=0.0, seed=3, only=None):
    """The cart posed with a household's goods heaped (HOUSEHOLD, the first [n] of them; piece i [lifts][i] px over
    its place), the hen's head as at [t] ticks; [only]: just those places' pieces, no cart."""
    M, extra = cart_pose(pitch, 0.0, spin)
    faces = [] if only else model_faces(cart, M, extra=extra)
    goods = HOUSEHOLD[:n]
    head = {'cage_hen_head': rot_part(hen_head(t, seed))}
    for i, kind in enumerate(goods):
        if only and i not in only:
            continue
        x, z, yaw, layer, under = SLOTS[i]
        rest = max((ALL_PIECES[goods[j]]['height'] for j in under if j < len(goods)), default=0.0)
        faces += piece_faces(load, kind, i, lift=(lifts[i] if lifts else 0.0), layer_y=rest, M=M, extra=head)
    return faces


def _drop(age):
    """px a piece set on the heap is still over its place [age] frames after it was let go (as a heave lets it fall)."""
    if age < 5.0:
        u = max(0.0, age) / 5.0
        return 7.0 * (1.0 - u * u)
    if age < 8.0:
        return 0.5 * math.sin(math.pi * (age - 5.0) / 3.0)
    return 0.0


def household_previews(cart, load, tex2, out):
    """A household's goods heaped on the cart from two sides; and a GIF of them put on it one by one as it stands
    parked at the door, then the cart taken up level and seen all round, the hen looking about in her coop (a close
    view of her in the corner, as the game turns her head: HandcartMesh.henHead)."""
    paths = []
    full = household_scene(cart, load, spin=10.0)
    a = render(full, tex2, yaw=215, pitch=-24, scale=7.0, center=(0.0, -6.0, -4.0))
    b = render(full, tex2, yaw=40, pitch=-30, scale=7.0, center=(0.0, -6.0, -4.0))
    img = Image.new('RGB', (a.width * 2, a.height + 18), (30, 32, 40))
    img.paste(a, (0, 18))
    img.paste(b, (a.width, 18))
    ImageDraw.Draw(img).text((6, 3), 'handcart: a household\'s move (' + ', '.join(HOUSEHOLD) + ')', fill=(235, 235, 235))
    p = os.path.join(out, 'handcart_household.png')
    img.save(p)
    paths.append(p)
    tilt = parked_tilt()
    starts = [2 + 5 * i for i in range(len(HOUSEHOLD))]
    load_end = starts[-1] + 10
    lift_end = load_end + 10
    turn_end = lift_end + 64
    frames = []
    for f in range(turn_end + 6):
        n = sum(1 for s in starts if s <= f)
        lifts = [_drop(f - s) for s in starts[:n]]
        if f < load_end:
            pitch, yaw, title = -tilt, 215.0, 'loading at the door, parked on its shafts'
        elif f < lift_end:
            pitch, yaw, title = -tilt * (1.0 - _smooth((f - load_end) / 10.0)), 215.0, 'taken up by the shafts'
        else:
            pitch, yaw, title = 0.0, 215.0 + 360.0 * _smooth((f - lift_end) / 64.0), 'heaped for the road'
        main = render(household_scene(cart, load, n, lifts, t=float(f), pitch=pitch, spin=10.0), tex2, yaw=yaw, pitch=-24,
                      scale=7.0, center=(0.0, -6.0, -4.0))
        frame = Image.new('RGB', (main.width, main.height + 18), (30, 32, 40))
        frame.paste(main, (0, 18))
        if n > 2:
            # the coop close, as it sits (place 2), the hen's head as the game turns it
            M, _ = cart_pose(pitch, 0.0, 10.0)
            x, z, _, _, _ = SLOTS[2]
            c = M @ np.array([x, FLOOR_TOP - 2.4, z])
            close = render(household_scene(cart, load, n, lifts, t=float(f), pitch=pitch, spin=10.0, only={2}), tex2,
                           yaw=24, pitch=-14, scale=20.0, size=(150, 124), center=tuple(c), ground=False)
            frame.paste(close, (main.width - 154, 22))
            ImageDraw.Draw(frame).rectangle((main.width - 155, 21, main.width - 4, 146), outline=(200, 200, 200))
        ImageDraw.Draw(frame).text((6, 3), f'handcart: a household\'s move, {title}', fill=(235, 235, 235))
        frames.append(frame)
    pal = [fr.quantize(colors=160, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE) for fr in frames]
    p = os.path.join(out, 'handcart_household.gif')
    pal[0].save(p, save_all=True, append_images=pal[1:], duration=50, loop=0, optimize=True, disposal=1)
    paths.append(p)
    return paths

# ================================================================== output

KT_HEADER = '// generated by art/scripts/handcart.py, edit the script, not this file\n'


def kt_float(v):
    t = ('%.3f' % v).rstrip('0').rstrip('.')
    return (t if t not in ('-0', '') else '0') + 'f'


def kotlin_load_data():
    """The slots and the pieces as Kotlin, so the renderer heaps the load exactly as the previews do."""
    slots = []
    for x, z, yaw, layer, under in SLOTS:
        u = 'intArrayOf(' + ', '.join(str(i) for i in under) + ')'
        slots.append(f'        Slot({kt_float(x)}, {kt_float(z)}, {kt_float(yaw)}, {u}),')
    pieces = []
    for name in KT_ORDER:
        info = ALL_PIECES[name]
        shows = ', '.join('floatArrayOf(' + ', '.join(kt_float(v) for v in show) + ')' for show in info['shows'])
        pieces.append(f'        Piece("{name}", {kt_float(info["height"])}, listOf({shows}), {kt_float(info.get("size", 0.0))}),')
    return '\n'.join(slots), '\n'.join(pieces)


def kotlin_mesh(cart, load):
    def body(model):
        return model.kotlin().rstrip('\n')
    slots, pieces = kotlin_load_data()
    return KT_HEADER + f'''package io.github.vaspyyy.guildmark.client.cart

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import kotlin.math.floor
import kotlin.math.min

/**
 * The handcart's two layers, box UV on the one [TEX_WIDTH] x [TEX_HEIGHT] texture (the PNG ships at {SCALE}x that,
 * painted at the higher resolution). Model space: pixels, y down, the cart faces -z, its right is -x, the origin
 * the middle of the axle ({R:g} px, a wheel's radius, over the ground).
 */
object HandcartMesh {{
    const val TEX_WIDTH = {TEX_W}
    const val TEX_HEIGHT = {TEX_H}

    /**
     * The cart: its bed (the floor and low sides on the two poles that run on forward as the shafts, and the axle)
     * and the two wheels, "wheel_r" and "wheel_l".
     */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun cart(): LayerDefinition {{
{body(cart)}
    }}

    /**
     * What heaps on the bed, each part standing on the middle of its underside: "sack" (grain), "flour", "crate",
     * "basket" and "fish"; and a household's goods for a move: "chest", "pot", "bundle" (PACK's own bundle: the same
     * cubes and paint as the prop it sets down), "cage" (a hen in her coop: her head is its child "cage_hen_head",
     * turned by [henHead]), "mattress" (rolled, lying across) and "chair".
     */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun load(): LayerDefinition {{
{body(load)}
    }}

    /** The bed's floor, its top inside: px from the axle, y down as in the model. */
    const val FLOOR_TOP = {kt_float(FLOOR_TOP)}

    /**
     * A place on the bed for one piece of the load: [x] and [z] (px), turned [yaw] degrees, resting on the places
     * [under] it (none: the floor).
     */
    class Slot(val x: Float, val z: Float, val yaw: Float, val under: IntArray)

    /** The places, in the order they fill: six on the floor, then two on top of them. */
    val SLOTS: List<Slot> = listOf(
{slots}
    )

    /**
     * What can heap there: the [part] of [load], how tall it stands (px), where goods show on its top ([shows]:
     * x, z, yaw and tilt for each, px and degrees, in the piece's own frame) and how big they are (of a block).
     */
    class Piece(val part: String, val height: Float, val shows: List<FloatArray>, val size: Float)

    /**
     * The pieces: grain, flour, a crate of produce, a basket of loaves, a box of fish on ice (0 to 4); a household's
     * chest, cooking pot, bundle, hen's coop, rolled mattress and chair (5 to 10).
     */
    val PIECES: List<Piece> = listOf(
{pieces}
    )

    /**
     * A household's move, place by place ([SLOTS]' order): the [PIECES] index each takes. {', '.join(HOUSEHOLD)}.
     * Place 5 is where PACK sets its bundle down.
     */
    val HOUSEHOLD: IntArray = intArrayOf({', '.join(str(KT_ORDER.index(k)) for k in HOUSEHOLD)})

    /** The hen's neck in the coop's frame (px), "cage_hen_head"'s pivot. */
    val HEN_HEAD: FloatArray = floatArrayOf({', '.join(kt_float(v) for v in HEN_HEAD)})

    /** The hen's look-about: ticks from one look to the next, and how long a snap of her head takes. */
    const val HEN_BEAT = {kt_float(HEN_BEAT)}
    const val HEN_SNAP = {kt_float(HEN_SNAP)}

    /** A peck at the straw: how long it takes (ticks), when in a beat it starts (the second of a double one), how far. */
    const val HEN_PECK = {kt_float(HEN_PECK)}
    const val HEN_PECK_AT = {kt_float(HEN_PECK_AT[0])}
    const val HEN_PECK_AGAIN = {kt_float(HEN_PECK_AT[1])}
    const val HEN_PECK_DEG = {kt_float(HEN_PECK_DEG)}

    private const val DEG = 0.017453292f      // PI / 180

    /** 0..1 from two ints (the script's hen_hash: the same Int arithmetic, wrapping). */
    private fun henHash(k: Int, j: Int): Float {{
        var h = k * 374761393 + j * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xffffff) / 16777216f
    }}

    /** Beat [k]'s look for the hen on cart [seed]: [which] 0 her pitch (down +), 1 her yaw, 2 her roll, degrees. */
    private fun henLook(k: Int, seed: Int, which: Int): Float {{
        val j = seed * 8
        return when (which) {{
            0 -> (henHash(k, j + 3) - 0.65f) * 24f
            1 -> (2f * henHash(k, j) - 1f) * 42f
            else -> if (henHash(k, j + 2) < 0.35f) (2f * henHash(k, j + 1) - 1f) * 14f else 0f
        }}
    }}

    private fun smooth(x: Float): Float {{
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }}

    /** A peck's dip, 0..1, [u] ticks into it: quick down, a moment at the straw, back up. */
    private fun henPeck(u: Float): Float {{
        if (u <= 0f || u >= HEN_PECK) return 0f
        val x = u / HEN_PECK
        return when {{
            x < 0.3f -> smooth(x / 0.3f)
            x < 0.5f -> 1f
            else -> 1f - smooth((x - 0.5f) / 0.5f)
        }}
    }}

    /**
     * How "cage_hen_head" turns at [t] ticks, for the hen on the cart [seed] (its id): her xRot, yRot and zRot
     * (radians, as ModelPart takes them) into [out]. She snaps her head from one look to the next every [HEN_BEAT]
     * ticks and holds it there, as hens do, cocks it now and then, and in some beats pecks at the straw, once or
     * twice. Pass the cart's own tickCount plus the partial tick (a small number: a float of the game time would step).
     */
    fun henHead(t: Float, seed: Int, out: FloatArray = FloatArray(3)): FloatArray {{
        val k = floor(t / HEN_BEAT).toInt()
        val s = t - k * HEN_BEAT
        val f = smooth(min(1f, s / HEN_SNAP))
        var pitch = henLook(k - 1, seed, 0) + (henLook(k, seed, 0) - henLook(k - 1, seed, 0)) * f
        var yaw = henLook(k - 1, seed, 1) + (henLook(k, seed, 1) - henLook(k - 1, seed, 1)) * f
        var roll = henLook(k - 1, seed, 2) + (henLook(k, seed, 2) - henLook(k - 1, seed, 2)) * f
        val pecks = henHash(k, seed * 8 + 4)
        if (pecks < 0.3f) {{
            val dip = henPeck(s - HEN_PECK_AT) + (if (pecks < 0.12f) henPeck(s - HEN_PECK_AGAIN) else 0f)
            pitch += (HEN_PECK_DEG - pitch) * dip
            yaw *= 1f - 0.6f * dip
            roll *= 1f - dip
        }}
        out[0] = pitch * DEG
        out[1] = yaw * DEG
        out[2] = roll * DEG
        return out
    }}
}}
'''


def report():
    tilt = parked_tilt()
    grip = np.array([RAIL_X, RAIL_Y, GRIP_Z])
    lines = [
        f'handcart: wheels {2 * R:g} px across, {TRACK:g} px apart; bed {2 * BED_X:g} x {BED_BACK - BED_FRONT:g} px, floor {R - FLOOR_TOP:g} px up',
        f'  the hands take the shafts at x +-{RAIL_X:g}, {R - RAIL_Y:g} px over the ground, {-GRIP_Z:g} px ahead of the axle '
        f'(the shaft: {float(np.hypot(GRIP_Z, RAIL_Y)):.3f} px from the axle, rising {math.degrees(math.atan2(-RAIL_Y, -GRIP_Z)):.3f} degrees)',
        f'  parked: tipped {tilt:.2f} degrees forward onto the shafts\' tips ({-RAIL_FRONT:g} px ahead)',
    ]
    for i, (x, z, yaw, layer, under) in enumerate(SLOTS):
        lines.append(f'  place {i}: x {x:g} z {z:g} turned {yaw:g}' + (f', on top of {list(under)}' if layer else ''))
    for k, info in ALL_PIECES.items():
        lines.append(f'  {k}: {info["height"]:g} px tall' + (f', goods shown at {list(info["shows"])} size {info.get("size")}' if info['shows'] else ''))
    return '\n'.join(lines)


def main(argv):
    assets, kotlin_dir, models = argv[1], argv[2], argv[3]
    prev = argv[4] if len(argv) > 4 else None
    cart, load = build()
    tex = texture(cart, load)
    tdir = os.path.join(assets, 'textures', 'entity', 'cart')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, 'handcart.png'))
    with open(os.path.join(kotlin_dir, 'HandcartMesh.kt'), 'w') as f:
        f.write(kotlin_mesh(cart, load))
    both = Model('handcart', TEX_W, TEX_H, cart.parts + load.parts)
    small = tex.resize((TEX_W, TEX_H), Image.LANCZOS)
    os.makedirs(models, exist_ok=True)
    with open(os.path.join(models, 'handcart.bbmodel'), 'w') as f:
        json.dump(both.bbmodel(small, 'handcart'), f)
    print(report())
    if prev:
        for p in previews(cart, load, tex, prev):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
