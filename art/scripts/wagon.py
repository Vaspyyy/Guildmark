"""
The caravans' wagon (M4, design-road.md section 5), drawn by client/caravan: a covered goods wagon in the handcart's
manner, built on the same kit. Four spoked wheels with iron tyres, the back pair bigger; a plank bed with stake sides
over the two axles, the front axle on a turntable under the bed so the front wheels steer; a bench across the front
of the box with a raked footboard and the brake lever by the driver's right hand; a patched canvas tilt on four ash
hoops, puckered shut at the front, open at the back and rolled up along both sides so the load shows; the shafts a
single heavy horse goes between (one horse is put to shafts, not beside a pole) and the swingletree its traces pull
on; a lantern on a crook at the bench's left end; a water barrel strapped to the left side; the city's blue pennant
over the front hoop; the tailboard let down on its chains for a guard to ride on. Its load heaps on 16 places in two
tiers, from the real goods. The wreck is the same wagon tipped over onto its right side, a wheel snapped off and
lying apart, the tilt half torn away, the shafts torn off and arrows in the boards; what is left of the load lies
round it as lots, stove-in crates and torn sacks with their spill. The band's loot pile is marked crates and sacks
under a tarp.

    python3 -B art/scripts/wagon.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/caravan art/models [preview-dir]

Writes textures/entity/caravan/wagon.png and wagon_glow.png (the lantern's lit glass, for a full-bright layer) at
SCALE times the declared box UV size, WagonMesh.kt (the layers: the wagon, its load, the wreck, the loot pile and the
straps the renderer stretches into reins and traces; and the numbers it works from), wagon.bbmodel, and with a preview
dir the wagon empty and loaded from four sides, the wreck, the loot pile and the load's pieces. Model space as
everywhere: pixels, y down, the wagon faces -z (toward the horse), its right is -x; the origin is the ground under the
middle of the back axle. draught_horse.py takes the harness's numbers (where the horse stands, the shafts, the
traces) from here.
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
import handcart as hc


def euler_from_axes(x_to, y_to):
    """A part rotation (degrees, ZYX as the game turns parts: R = Rz Ry Rx) taking the part's own x and y axes to
    [x_to] and [y_to] (made orthonormal; z follows); when x goes along z (the turn about y is a right angle) the turn
    about x is taken as none and the turn about z carries the rest."""
    x = np.asarray(x_to, float)
    x = x / np.linalg.norm(x)
    y = np.asarray(y_to, float)
    y = y - x * float(np.dot(x, y))
    y = y / np.linalg.norm(y)
    R = np.stack([x, y, np.cross(x, y)], axis=1)
    b = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    if abs(R[2, 0]) > 0.99999:
        a, c = 0.0, math.atan2(-R[0, 1], R[1, 1])
    else:
        a, c = math.atan2(R[2, 1], R[2, 2]), math.atan2(R[1, 0], R[0, 0])
    out = tuple(round(math.degrees(t), 3) for t in (a, b, c))
    M = hc.rot_part(out)
    assert np.allclose(M[:, :2], R[:, :2], atol=2e-3), (x_to, y_to, out)
    return out

TEX_W, TEX_H = 512, 256     # the declared (box UV) size
SCALE = 2                   # the PNG ships at SCALE times that (the handcart's painters paint at this scale)
assert hc.SCALE == SCALE

# ================================================================== geometry (px)

RR = 12.0                   # the back wheels' radius: the back axle's middle this high over the ground
RF = 8.5                    # the front wheels': they turn under the bed
WHEEL_X = 13.0              # each wheel's middle, out from the middle
WHEELBASE = 32.0            # the front axle this far ahead of the back one
ZF = -WHEELBASE
FELLOE_W = 2.4              # a wheel's rim, across (x)
KINGPIN_Y = -14.5           # the front axle turns about a pin through (0, *, ZF); this is its bolster's top
FLOOR_TOP = -19.0           # the bed's floor, its top inside
FLOOR_T = 1.25
SILL_X = 8.0                # the two sills under the floor
SILL_BOTTOM = FLOOR_TOP + FLOOR_T + 1.5
BOX_X = 11.0                # the box's sides: their outer face (inner face 1 px in)
SIDE_TOP = -26.0
BOX_FRONT, BOX_BACK = -45.5, 10.0
BENCH_TOP = -27.5           # the bench's top, across the box's front, resting on the sides
BENCH_T = 1.5
BENCH_FRONT, BENCH_BACK = -47.5, -39.0
BENCH_X = 13.0
DASH = (0.0, -26.4, -57.2)  # the raked footboard the driver's soles press on: the middle of its back face
DASH_LEAN = 16.0            # its top leaning this far forward
STAKE_Z = (-44.0, -31.5, -19.0, -6.5, 8.4)
STAKE_X = BOX_X + 0.55
HOOP_Z = (-38.5, -24.0, -9.5, 5.0)
TILT_FRONT, TILT_BACK = -39.5, 6.0
ARCH_Y = -30.0              # the tilt's arch: an ellipse round (0, ARCH_Y), ARCH_X wide each side and ARCH_H high
ARCH_H = 20.0
ARCH_X = BOX_X - 0.3
ARCH_N = 8                  # strips of canvas over the arch, one each 1/ARCH_N of a half turn
ARCH_P = 2.5                # the arch's roundness: a superellipse, fuller in the shoulders than an ellipse (2)
ROLLED = 1                  # the lowest strips each side are rolled up and tied: the canvas comes down this far
TAIL_LEN = 6.0              # the tailboard, let down level with the floor on its chains
SHAFT_HINGE = (9.5, -10.5, ZF - 4.0)    # each shaft's hinge on the front axle's hounds (x mirrored for the right)
SWINGLE = (0.0, -10.0, ZF - 6.2)        # the swingletree's middle (it turns with the front axle)
SWINGLE_HALF = 7.5
BRAKE_PIVOT = (-12.4, -21.0, -41.5)     # the brake lever's pivot on its bracket outside the box's right side
BRAKE_LEN = 14.0
BRAKE_REST, BRAKE_ON = -16.0, 22.0      # its lean (degrees about x, + back toward the driver): off, hard on
LANTERN_HOOK = (17.2, -40.6, -43.0)     # where the lantern hangs from its crook
PENNANT_FOOT = (0.0, ARCH_Y - ARCH_H - 0.6, HOOP_Z[0])
PENNANT_TOP = 11.0                      # the staff's top over its foot

# ---------------------------------------------------------------- the horse in the shafts
# The draught horse's origin is the ground under the middle of its body (its model's y 24, as every mob's): it walks
# this far ahead of the kingpin when the shafts are straight. The shafts run from the hinges to their tips by its
# shoulders, through the tugs on its cart saddle; the traces run from the hames on its collar back to the
# swingletree's hooks (draught_horse.py puts the saddle, tugs, collar and hames here).
HORSE_AHEAD = 51.0
HORSE_Z = ZF - HORSE_AHEAD              # where the horse's origin is, the shafts straight
SHAFT_X = 8.2                           # the shafts' middle line by the horse, out from its middle
SHAFT_UP = 21.0                         # how high they run there (the tugs' height)
SHAFT_T = 1.7
TUG_Z = -4.0                            # the tugs, in the horse's z
SHAFT_TIP_Z = -15.0                     # the shafts' tips, in the horse's z
HAMES = (7.0, 21.0, -20.4)              # the trace hooks on the hames: x out, height, the horse's z
TERRET = (2.2, 31.4, -4.0)              # the rein rings on top of the cart saddle (x mirrored)

SEATS = {   # where the townsfolk sit (the seat's top under the middle of their hips) and which way they face (0 ahead, 180 back)
    'driver': ((-6.5, BENCH_TOP, -45.5), 0.0),
    'bench': ((6.5, BENCH_TOP, -45.5), 0.0),
    'tail': ((0.0, FLOOR_TOP, BOX_BACK + TAIL_LEN + 1.0), 180.0),
}
STAND = ((0.0, FLOOR_TOP, BOX_BACK + 2.5), 180.0)   # where the driver stands to hand the load down: on the tailboard

# Where the load heaps: (x, z, yaw degrees, layer, the places a top one rests on). Filled in this order: twelve on the
# floor in four rows of three, front to back, then four on top of them.
ROWS_Z = (-36.0, -27.6, -19.2, -10.8)
COLS_X = (-6.7, 0.0, 6.7)
_J = [1.5, -2.0, 0.5, -1.0, 2.0, -0.5, 1.0, -1.5, 0.0, 1.5, -1.0, 2.0]
SLOTS = [(x, z, _J[i * 3 + j], 0, ()) for i, z in enumerate(ROWS_Z) for j, x in enumerate(COLS_X)]
SLOTS += [(-3.35, -31.8, 7.0, 1, (0, 1, 3, 4)), (3.35, -23.4, -9.0, 1, (4, 5, 7, 8)),
          (-3.35, -15.0, -6.0, 1, (6, 7, 9, 10)), (3.4, -31.6, 12.0, 1, (1, 2, 4, 5))]

# The pieces the load is heaped from: the handcart's five, and the wagon's own. height: how tall it stands; shows:
# where the goods show on an open one ((x, z, yaw, tilt) px and degrees, size of a block), as the handcart's
PIECES = dict(hc.PIECES)
PIECES.update({
    'barrel': dict(height=7.0, shows=()),
    'iron': dict(height=3.0, shows=()),
    'coal': dict(height=4.2, shows=()),
    'wool': dict(height=5.8, shows=()),
    'bottles': dict(height=4.6, shows=()),
    'candles': dict(height=3.25, shows=()),
    'honey': dict(height=4.4, shows=()),
    'flint': dict(height=4.0, shows=()),
    'string': dict(height=2.4, shows=()),
})
PIECE_ORDER = ('sack', 'flour', 'crate', 'basket', 'fish', 'barrel', 'iron', 'coal', 'wool', 'bottles', 'candles',
               'honey', 'flint', 'string')

# Which piece shows each good, by the market's good id (economy/Goods.kt): the renderer picks by these
PIECE_FOR = {
    'wheat': 'sack', 'potato': 'sack', 'beetroot': 'crate', 'carrot': 'crate', 'apple': 'crate', 'bread': 'basket',
    'flour': 'flour', 'cod': 'fish', 'salmon': 'fish', 'beef': 'barrel', 'pork': 'barrel', 'mutton': 'barrel',
    'chicken': 'crate', 'honey': 'honey', 'wool': 'wool', 'string': 'string', 'leather': 'wool', 'arrow': 'crate',
    'feather': 'crate', 'flint': 'flint', 'candle': 'candles', 'bottle': 'bottles', 'iron': 'iron', 'coal': 'coal',
}

# The wreck: the wagon tipped over onto its right side about the line its right wheels stood on (TIP_AXIS: x and y
# in the wagon's space, the axis along z), WRECK_ROLL degrees about z: the renderer turns the wagon so over 20 ticks
# and then draws the wreck in its place. Its right wheels are gone (the front one crushed under it, the back one lying
# apart), its shafts torn off and lying ahead. What is left of the load lies round it as lots, at WRECK_LOTS (x, z,
# yaw; in the wreck's space, which is the wagon's), numbered in the order they're filled; WRECK_PIECE_FOR gives the
# broken piece a lot shows for each whole one.
TIP_AXIS = (-(WHEEL_X + FELLOE_W / 2), 0.0)
WRECK_LOTS = [(-38.0, 24.0, 20.0), (-55.0, 19.0, -30.0), (-72.0, 4.0, 50.0), (-74.0, -16.0, -15.0),
              (-66.0, -34.0, 35.0), (-24.0, 30.0, 70.0), (-56.0, 35.0, -60.0), (-84.0, -4.0, 10.0)]
WRECK_PIECE_FOR = {'sack': 'sack_torn', 'flour': 'flour_torn', 'crate': 'crate_broken', 'basket': 'basket_spilled',
                   'fish': 'crate_broken', 'barrel': 'barrel_broken', 'iron': 'iron', 'coal': 'coal_torn',
                   'wool': 'wool', 'bottles': 'crate_broken', 'candles': 'candles', 'honey': 'crate_broken',
                   'flint': 'sack_torn', 'string': 'string'}
BROKEN_ORDER = ('sack_torn', 'flour_torn', 'coal_torn', 'crate_broken', 'barrel_broken', 'basket_spilled')
WRECK_HORSE = (-30.0, -96.0, 76.0)      # where a dead horse lies in its cut harness (x, z, yaw), if it died

LOOT_PIECES = 6

# ================================================================== palette

BED, BED_D, BED_L, BED_GREY = hc.BED, hc.BED_D, hc.BED_L, hc.BED_GREY
OAK, OAK_D, OAK_L = (124, 86, 52), (80, 54, 32), (164, 120, 78)
ASH, ASH_D, ASH_L = hc.ASH, hc.ASH_D, hc.ASH_L
IRON, IRON_D, IRON_L = hc.IRON, hc.IRON_D, hc.IRON_L
CANVAS = (214, 204, 178); CANVAS_D = (160, 148, 120); CANVAS_L = (236, 230, 210)
PATCHES = [((176, 160, 122), (130, 116, 86)), ((150, 160, 150), (104, 114, 104)), ((196, 176, 140), (148, 128, 96)),
           ((168, 132, 104), (120, 90, 68))]
THREAD = (96, 82, 60)
BLUE = (44, 70, 150); BLUE_D = (28, 44, 100); BLUE_L = (90, 120, 196)     # the city's blue (CityBuilder.BANNER_COLOR)
GOLD = (222, 184, 84)
LEATHER = (92, 58, 34); LEATHER_D = (58, 36, 20); LEATHER_L = (136, 92, 58)
COAL = (40, 38, 40); COAL_L = (92, 90, 96)
WOOL = (232, 226, 210); WOOL_D = (190, 182, 160)
FLINT = (96, 96, 104); FLINT_L = (150, 152, 160)
GLASS_G = (70, 124, 84); GLASS_GL = (150, 200, 160)
HONEY = (222, 150, 40); JAR = (178, 112, 70); JAR_L = (214, 156, 104)
TWINE = (196, 172, 120); TWINE_D = (150, 126, 80)
STENCIL_BLUE = (52, 80, 164)
ROPE = (176, 146, 96)

uv, mix, solid, grain, metal, weave = tt.uv, tt.mix, tt.solid, tt.grain, tt.metal, tt.weave
boards, weather, nail, band = hc.boards, hc.weather, hc.nail, hc.band
shade_edges, noise_fill, painter = tt.shade_edges, tt.noise_fill, tt.painter

# ================================================================== painters


def plank_painter(key, n=3, along='z', broad=('front', 'back', 'right', 'left'), outer=None, base=BED, dark=BED_D,
                  light=BED_L, weathered=0.2, irons=(), worn_top=True):
    """Boards running along the cube's [along] axis, [n] of them side by side on its [broad] faces, weathered silver in
    patches, a pale worn top edge; iron straps across at [irons] (0..1 along the [outer] face) with their nails."""
    def fn(face, w, h, rng):
        ax = tt.AXIS_ON_FACE[along][face]
        if ax is None:
            return grain(w, h, None, dark, (60, 40, 26), base, rng)
        a, _ = boards(w, h, rng, n if face in broad else 1, ax, base, dark, light, knots=1)
        a = weather(a, rng, weathered if (outer is None or face == outer) else weathered * 0.5)
        U, V = uv(w, h)
        if worn_top and face in ('front', 'back', 'right', 'left') and along != 'y':
            a[..., :3] = mix(a[..., :3], light, (0.22 * np.clip(1 - V * 5, 0, 1))[..., None])
        f = U if ax == 'u' else V
        if irons and face == outer:
            k = np.zeros_like(f, dtype=bool)
            for c in irons:
                k |= np.abs(f - c) < max(0.7 / max(w, h), 0.012)
            a = band(a, k, rng)
            for c in irons:
                for q in (0.25, 0.75):
                    nail(a, c if ax == 'u' else q, q if ax == 'u' else c, 0.5)
        return shade_edges(a, 0.14)
    return painter(key, fn)


def floor_painter(key):
    """The bed's floor: seven planks along it, scuffed pale down the middle where loads are dragged, stained, nailed
    to the sills and the bolsters."""
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            a, _ = boards(w, h, rng, 7, 'v', BED, BED_D, BED_L, knots=3)
            a = weather(a, rng, 0.24 if face == 'top' else 0.12)
            U, V = uv(w, h)
            if face == 'top':
                scuff = np.exp(-((U - 0.5) / 0.3) ** 2) * (0.5 + 0.5 * np.sin(V * 21.0 + rng.uniform(0, 6)) ** 2)
                a[..., :3] = mix(a[..., :3], BED_L, (0.15 * scuff)[..., None])
                for _ in range(4):
                    cx, cy, rr = rng.uniform(0.15, 0.85), rng.uniform(0.1, 0.9), rng.uniform(0.03, 0.06)
                    d = np.hypot((U - cx) * w / h, V - cy) / rr
                    a[..., :3] = np.where((d < 1)[..., None], mix(a[..., :3], BED_D, np.clip(0.28 * (1 - d), 0, 1)[..., None]), a[..., :3])
                for z in (BOX_FRONT + 2.0, ZF, -16.0, 0.0, BOX_BACK - 1.5):
                    vv = (BOX_BACK - z) / (BOX_BACK - BOX_FRONT - 1.0)
                    for i in (0, 2, 4, 6):
                        nail(a, (i + 0.5) / 7, vv, 0.6)
            return shade_edges(a, 0.1)
        return grain(w, h, 'u', BED_D, (60, 40, 26), BED, rng, streak=0.3)
    return painter(key, fn)


def stake_painter(key):
    """A stake in its iron pocket on the side: grain upright, an iron cap, the pocket's band low down."""
    def fn(face, w, h, rng):
        if face == 'top':
            return metal(w, h, IRON, IRON_D, IRON_L, rng)
        if face == 'bottom':
            return grain(w, h, None, BED_D, (56, 38, 24), BED, rng)
        a = grain(w, h, 'v', BED_D, (60, 40, 26), BED, rng, streak=0.4)
        U, V = uv(w, h)
        a = band(a, (V < 0.07) | ((V > 0.56) & (V < 0.74)), rng)
        return shade_edges(a, 0.15)
    return painter(key, fn)


def beam_painter(key, axis='x', base=OAK, dark=OAK_D, light=OAK_L, ends=True, straps=()):
    """A heavy timber (a bolster, a sill, the hounds) with grain along [axis], iron at its ends and [straps]."""
    def fn(face, w, h, rng):
        ax = tt.AXIS_ON_FACE[axis][face]
        if ax is None:
            return grain(w, h, None, dark, (56, 38, 24), base, rng)
        a = grain(w, h, ax, base, dark, light, rng, streak=0.45, knots=1)
        U, V = uv(w, h)
        f = U if ax == 'u' else V
        if ends:
            a = band(a, (f < 0.06) | (f > 0.94), rng)
        for c in straps:
            a = band(a, np.abs(f - c) < 0.04, rng)
        return shade_edges(a, 0.18)
    return painter(key, fn)


def pole_painter(key, axis='x', ferrules=(), base=ASH, dark=ASH_D, light=ASH_L, polish=None):
    """A round pole (the shafts, the pennant's staff, the brake's handle): grain along [axis], lit down its middle,
    iron ferrules at [ferrules] (0..1 along u or v as the face runs), a [polish] span (lo, hi) dark where hands take it."""
    def fn(face, w, h, rng):
        ax = tt.AXIS_ON_FACE[axis][face]
        if ax is None:
            return grain(w, h, None, base, dark, light, rng) * np.array([0.92, 0.92, 0.92, 1.0])
        a = grain(w, h, ax, base, dark, light, rng, streak=0.5, knots=1)
        U, V = uv(w, h)
        f, g = (U, V) if ax == 'u' else (V, U)
        a[..., :3] *= (0.84 + 0.26 * np.sin(np.pi * g))[..., None]
        if polish:
            p = (f >= polish[0]) & (f <= polish[1])
            a[..., :3] = np.where(p[..., None], mix(a[..., :3], hc.GRIP, 0.5), a[..., :3])
        for c in ferrules:
            a = band(a, np.abs(f - c) < max(0.6 / max(w, h), 0.02), rng)
        return a
    return painter(key, fn)


def iron_painter(key, wear=0.08):
    def fn(face, w, h, rng):
        return shade_edges(metal(w, h, IRON, IRON_D, IRON_L, rng, wear=wear), 0.2)
    return painter(key, fn)


def hoop_painter(key):
    """An ash hoop: bent wood, its grain along it, darkened where the canvas rubs."""
    def fn(face, w, h, rng):
        a = grain(w, h, 'u' if face in ('front', 'back', 'top', 'bottom') else None, ASH, ASH_D, ASH_L, rng, streak=0.5)
        a[..., :3] *= 0.9
        return a
    return painter(key, fn)


def canvas_painter(key, patches=1, torn=0.0, edge=None):
    """The tilt's canvas, a strip of it running the wagon's length (u along it, v across): heavy linen, its weave
    showing, seams sewn across, slack folds between the hoops, rain-grimed; patched here and there (a square of
    another cloth, its stitching round it); the inside (its back face) duller. [torn] (0..1) rips ragged holes through
    it with frayed edges, for the wreck; [edge] ('v0' or 'v1') hems one long edge (where it's rolled up)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, CANVAS, CANVAS_D, CANVAS_L, rng, cell=2)
        a[..., :3] *= (1 + 0.04 * rng.normal(0, 1, (h, w)))[..., None]
        if face in ('front', 'back'):
            for c in np.arange(rng.uniform(0.08, 0.2), 1.0, 0.27):
                seam = np.abs(U - c) < max(0.5 / w, 0.006)
                a[..., :3] = np.where(seam[..., None], mix(a[..., :3], CANVAS_D, 0.6), a[..., :3])
                st = seam & ((np.arange(h)[:, None] % 3) == 0)
                a[..., :3] = np.where(st[..., None], np.array(THREAD, float), a[..., :3])
            hoops = [(z - TILT_FRONT) / (TILT_BACK - TILT_FRONT) for z in HOOP_Z]
            sag = np.zeros_like(U)
            for p, q in zip(hoops, hoops[1:]):
                inside = (U > p) & (U < q)
                sag = np.where(inside, np.sin(np.pi * (U - p) / (q - p)), sag)
            a[..., :3] *= (1 - 0.07 * sag ** 2 + 0.04 * (1 - sag) ** 6)[..., None]
            g = np.clip(0.05 + 0.04 * rng.normal(0, 1, (h, w)), 0, 0.2)
            a[..., :3] = mix(a[..., :3], (120, 110, 90), g[..., None])
            if face == 'front':
                for i in range(patches):
                    pw, ph = rng.uniform(0.07, 0.13), rng.uniform(0.4, 0.75)
                    cx, cy = rng.uniform(0.1, 0.9), rng.uniform(0.3, 0.7)
                    col, dark = PATCHES[int(rng.integers(len(PATCHES)))]
                    inside = (np.abs(U - cx) < pw / 2) & (np.abs(V - cy) < ph / 2)
                    patch = weave(w, h, col, dark, tuple(min(255, c + 30) for c in col), rng, cell=2)
                    a[..., :3] = np.where(inside[..., None], patch[..., :3], a[..., :3])
                    rim = inside & ~((np.abs(U - cx) < pw / 2 - 1.5 / w) & (np.abs(V - cy) < ph / 2 - 1.5 / h))
                    dots = rim & (((np.arange(w)[None, :] + np.arange(h)[:, None]) % 2) == 0)
                    a[..., :3] = np.where(dots[..., None], np.array(THREAD, float), a[..., :3])
            if edge:
                hem = (V > 0.82) if edge == 'v1' else (V < 0.18)
                a[..., :3] = np.where(hem[..., None], mix(a[..., :3], CANVAS_D, 0.35), a[..., :3])
        if face == 'back':
            a[..., :3] *= 0.74
        if torn > 0 and face in ('front', 'back'):
            n = rng.normal(0, 1, (h // 2 + 2, w // 2 + 2))
            n = np.kron(n, np.ones((2, 2)))[:h, :w]
            ragged = 0.55 * np.sin(U * 13.0 + rng.uniform(0, 6)) + n * 0.6
            level = (V - 0.5) * 1.4 + 0.8 * np.sin(U * 5.0 + rng.uniform(0, 6))
            hole = ragged + level > 1.9 - 2.6 * torn
            fray = (ragged + level > 1.9 - 2.6 * torn - 0.3) & ~hole
            a[..., :3] = np.where(fray[..., None], mix(a[..., :3], CANVAS_D, 0.55), a[..., :3])
            a[hole] = 0
        return a
    return painter(key, fn)


def roll_painter(key):
    """The canvas rolled up along the side and tied: turns of cloth along it, a tie at each hoop."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, CANVAS, CANVAS_D, CANVAS_L, rng, cell=2)
        ax = tt.AXIS_ON_FACE['z'][face]
        if ax is None:
            r = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] *= (0.75 + 0.3 * (np.sin(r * 30) > 0))[..., None]
            return a
        f, g = (U, V) if ax == 'u' else (V, U)
        a[..., :3] *= (0.8 + 0.3 * np.sin(np.pi * g))[..., None]
        turns = np.sin(g * 9.0 + f * 2.0) > 0.6
        a[..., :3] = np.where(turns[..., None], a[..., :3] * 0.86, a[..., :3])
        for z in HOOP_Z:
            c = (TILT_BACK + 0.3 - z) / (TILT_BACK - TILT_FRONT + 0.6)      # 0 at the back on the right face and top
            c = c if face in ('right', 'top', 'bottom') else 1 - c
            tie = np.abs(f - c) < 0.012
            a[..., :3] = np.where(tie[..., None], mix(np.array(TWINE, float), TWINE_D, 0.4), a[..., :3])
        return a
    return painter(key, fn)


def pucker_painter(key):
    """The tilt's front end, drawn shut round a small oval opening by its cord: canvas gathered in folds radiating from
    the opening, the shape of the arch (clear outside it)."""
    span = SIDE_TOP - (ARCH_Y - ARCH_H - 0.2)
    k = (SIDE_TOP - ARCH_Y) / span          # where the ellipse begins, from the bottom

    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, CANVAS, CANVAS_D, CANVAS_L, rng, cell=2)
        if face not in ('front', 'back'):
            return a
        x = (U - 0.5) * 2
        y = 1.0 - V
        e = np.where(y < k, np.abs(x) <= 1.0, np.abs(x) ** ARCH_P + np.abs((y - k) / (1 - k)) ** ARCH_P <= 1.0)
        oy = 0.5
        r = np.hypot(x, (y - oy) * 1.5)
        ang = np.arctan2(y - oy, x)
        gathers = 0.5 + 0.5 * np.sin(ang * 14.0 + rng.uniform(0, 6))
        a[..., :3] *= (0.84 + 0.22 * gathers * np.clip(1.1 - r, 0, 1))[..., None]
        a[..., :3] = np.where((r < 0.19)[..., None], np.array((34, 28, 22), float), a[..., :3])
        cord = (r >= 0.19) & (r < 0.25)
        a[..., :3] = np.where(cord[..., None], mix(np.array(TWINE, float), TWINE_D, ((np.sin(ang * 20) > 0) * 0.5)[..., None]), a[..., :3])
        a[..., :3] = mix(a[..., :3], (120, 110, 90), (np.clip(0.4 - y, 0, 1) * 0.4)[..., None])
        if face == 'back':
            a[..., :3] *= 0.74
        a[~e] = 0
        return a
    return painter(key, fn)


def pennant_painter(key, part=0):
    """The city's pennant: blue wool with a gold edge along its top and bottom, a gold roundel on the first piece,
    swallow-tailed at the last (the cut drawn clear)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, BLUE, BLUE_D, BLUE_L, rng, cell=2)
        if face in ('front', 'back'):
            uu = U if face == 'front' else 1 - U      # 0 at the root, 1 at the free end, on both faces
            edge = (V < 0.12) | (V > 0.88)
            a[..., :3] = np.where(edge[..., None], np.array(GOLD, float), a[..., :3])
            if part == 0:
                r = np.hypot((uu - 0.55) * w / h, V - 0.5)
                a[..., :3] = np.where((r < 0.24)[..., None], np.array(GOLD, float), a[..., :3])
                a[..., :3] = np.where((r < 0.13)[..., None], np.array(BLUE, float), a[..., :3])
            if part == 2:
                a[uu > 1.0 - (0.5 - np.abs(V - 0.5)) * 1.1] = 0
            a[..., :3] *= (1 + 0.06 * np.sin(uu * 9 + rng.uniform(0, 6)))[..., None]
        return a
    return painter(key, fn)


def strap_painter(key, base=LEATHER, dark=LEATHER_D, light=LEATHER_L, along='y'):
    """Leather: a strap's darker edges along it, its grain worn shiny down the middle."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.06)
        ax = tt.AXIS_ON_FACE[along][face]
        if ax is None:
            return a
        U, V = uv(w, h)
        across = V if ax == 'u' else U
        a[..., :3] = mix(a[..., :3], light, (0.25 * np.exp(-((across - 0.4) / 0.2) ** 2))[..., None])
        a[..., :3] = np.where(((across < 0.12) | (across > 0.88))[..., None], mix(a[..., :3], dark, 0.6), a[..., :3])
        return a
    return painter(key, fn)


def barrel_painter(key, base=OAK, dark=OAK_D, light=OAK_L, along='y', hoops=(0.14, 0.86), head=None, burst=False):
    """A barrel's staves along its length ([along]: the cube axis it lies along), iron hoops round it, its heads end
    grain inside an iron rim ([head] paints them instead); [burst] springs a few staves (dark gaps) and the head in."""
    def fn(face, w, h, rng):
        ax = tt.AXIS_ON_FACE[along][face]
        U, V = uv(w, h)
        if ax is None:
            if head is not None:
                return head(w, h, rng)
            a = grain(w, h, None, base, dark, light, rng)
            r = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = np.where((r > 0.42)[..., None], mix(a[..., :3], IRON, 0.6), a[..., :3])
            if burst:
                a[..., :3] = np.where((r < 0.36)[..., None], np.array((30, 22, 16), float), a[..., :3])
            return shade_edges(a, 0.25)
        a = grain(w, h, ax, base, dark, light, rng, streak=0.5)
        f, g = (U, V) if ax == 'u' else (V, U)
        n = max(2, round((h if ax == 'u' else w) / 2.4))
        seam = ((g * n) % 1.0) < max(0.12, 1.0 / max(w, h))
        a[..., :3] = np.where(seam[..., None], mix(a[..., :3], dark, 0.6), a[..., :3])
        a[..., :3] *= (0.86 + 0.24 * np.sin(np.pi * f))[..., None]
        for c in hoops:
            b = np.abs(f - c) < max(0.06, 0.6 / max(w, h))
            hoop = metal(w, h, IRON, IRON_D, IRON_L, rng)
            a[..., :3] = np.where(b[..., None], hoop[..., :3], a[..., :3])
        if burst:
            gap = (((g * n) % 1.0) > 0.4) & (((g * n) % 1.0) < 0.62) & ((g * n).astype(int) % 3 == 1) & (f > 0.3)
            a[..., :3] = np.where(gap[..., None], np.array((26, 18, 12), float), a[..., :3])
        return shade_edges(a, 0.16)
    return painter(key, fn)


def barrel_head(w, h, rng):
    a = grain(w, h, None, OAK_L, OAK, (190, 150, 104), rng)
    U, V = uv(w, h)
    r = np.hypot(U - 0.5, V - 0.5)
    a[..., :3] = np.where((r > 0.43)[..., None], mix(a[..., :3], IRON, 0.7), a[..., :3])
    a[..., :3] = np.where((np.hypot(U - 0.66, V - 0.5) < 0.07)[..., None], np.array(tt.CORK, float), a[..., :3])
    return a


def dark_glass_painter(key):
    """A lantern's glass gone dark: smoked panes, the wick burnt out."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, (92, 74, 50), rng, 0.05)
        a[..., :3] = mix(a[..., :3], (150, 128, 92), (0.4 * np.exp(-((U - 0.3) / 0.12) ** 2))[..., None])
        a[..., :3] = mix(a[..., :3], (30, 24, 20), (0.5 * np.clip(0.4 - V, 0, 1))[..., None])
        return a
    return painter(key, fn)


def bars_painter(key):
    """Bar iron: dark mill scale, rust blooming, the cut ends bright."""
    def fn(face, w, h, rng):
        a = metal(w, h, (72, 70, 74), (40, 38, 42), (130, 126, 124), rng, wear=0.12)
        rust = rng.random((h, w)) < 0.08
        a[..., :3] = np.where(rust[..., None], mix(a[..., :3], (128, 70, 40), 0.6), a[..., :3])
        if face in ('front', 'back'):
            a[..., :3] = mix(a[..., :3], (176, 170, 164), 0.4)
        return a
    return painter(key, fn)


def coal_sack_painter(key, part='body'):
    """A coal sack: dark, sooty burlap, black dust worked into its weave, lumps showing at the gathered neck."""
    def fn(face, w, h, rng):
        a = weave(w, h, (88, 80, 70), (56, 50, 44), (116, 106, 92), rng, cell=max(1, min(w, h) // 8))
        soot = np.clip(0.45 + 0.25 * rng.normal(0, 1, (h, w)), 0, 0.8)
        a[..., :3] = mix(a[..., :3], COAL, soot[..., None] * 0.6)
        if part == 'top' and face == 'top':
            lumps = rng.random((h, w)) < 0.35
            a[..., :3] = np.where(lumps[..., None], mix(np.array(COAL, float), COAL_L, rng.random((h, w))[..., None] * 0.5), a[..., :3])
        if part == 'base':
            a[..., :3] *= 0.8
        return shade_edges(a, 0.3, width=max(1, min(w, h) // 4))
    return painter(key, fn)


def wool_painter(key):
    """A wool bale: pressed fleece in a burlap wrap, tufts of wool at its seams, roped twice round."""
    def fn(face, w, h, rng):
        a = weave(w, h, tt.BURLAP, tt.BURLAP_D, tt.BURLAP_L, rng, cell=max(1, min(w, h) // 8))
        U, V = uv(w, h)
        seam = (np.abs(V - 0.5) < 0.12) if face not in ('top', 'bottom') else (np.abs(U - 0.5) < 0.14)
        tufts = seam & (rng.random((h, w)) < 0.7)
        a[..., :3] = np.where(tufts[..., None], mix(np.array(WOOL, float), WOOL_D, rng.random((h, w))[..., None] * 0.6), a[..., :3])
        if face in ('top', 'bottom', 'front', 'back'):
            rope = (np.abs(U - 0.28) < 0.04) | (np.abs(U - 0.72) < 0.04)
        else:
            rope = np.zeros_like(U, dtype=bool)
        a[..., :3] = np.where(rope[..., None], mix(np.array(TWINE, float), TWINE_D, (0.3 + 0.4 * (np.sin(V * 40) > 0))[..., None]), a[..., :3])
        return shade_edges(a, 0.28, width=max(1, min(w, h) // 4))
    return painter(key, fn)


def straw_top(w, h, rng):
    a = solid(w, h, (58, 40, 24))
    for _ in range(max(10, w * h // 5)):
        x0, y0 = rng.uniform(0, w), rng.uniform(0, h)
        ang = rng.uniform(0, math.pi)
        ln = rng.uniform(1.5, 4.0) * SCALE / 2
        for s in np.linspace(0, ln, int(ln * 2) + 2):
            xi, yi = int(x0 + s * math.cos(ang)), int(y0 + s * math.sin(ang))
            if 0 <= xi < w and 0 <= yi < h:
                a[yi, xi, :3] = mix(hc.STRAW_D, hc.STRAW_L, rng.random())
    return a


def lid_top(w, h, rng):
    a, _ = boards(w, h, rng, 2, 'u', hc.CRATE, hc.CRATE_D, hc.CRATE_L)
    return a


def mark(a, U, V, w, h, r=0.22):
    """The band's chalk mark: a blue ring struck through, on what they took."""
    x, y = (U - 0.5) * w / h, V - 0.5
    d = np.hypot(x, y)
    ring = (np.abs(d - r) < 0.045) | ((np.abs(x + y * 0.3) < 0.035) & (d < r))
    a[..., :3] = np.where(ring[..., None], mix(a[..., :3], STENCIL_BLUE, 0.85), a[..., :3])
    return a


def box_painter(key, base=hc.CRATE, dark=hc.CRATE_D, light=hc.CRATE_L, top=None, stencil=None, broken=0.0, marked=False):
    """A crate or a box: slats nailed to corner posts (gaps between them), its [top] painted by a function (straw, a
    lid), a [stencil] on its sides ('candle', 'bottle', 'honey'), [broken] (0..1) splitting slats out of it, [marked]
    the band's blue chalk mark (the loot pile's)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = top(w, h, rng) if top is not None else boards(w, h, rng, 3, 'u', base, dark, light)[0]
            rim = (U < 0.09) | (U > 0.91) | (V < 0.09) | (V > 0.91)
            a[..., :3] = np.where(rim[..., None], grain(w, h, 'u', light, dark, light, rng)[..., :3], a[..., :3])
            return mark(a, U, V, w, h) if marked else a
        if face == 'bottom':
            return boards(w, h, rng, 3, 'u', dark, (70, 50, 30), base)[0]
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
        if stencil and min(w, h) >= 6:
            x, y = (U - 0.5) * w / h, V - 0.48
            if stencil == 'candle':
                m = ((np.abs(x) < 0.05) & (y > -0.12) & (y < 0.25)) | (np.hypot(x, (y + 0.22) * 0.7) < 0.06)
            elif stencil == 'bottle':
                m = ((np.abs(x) < 0.09) & (y > -0.02) & (y < 0.26)) | ((np.abs(x) < 0.035) & (y > -0.22) & (y <= -0.02))
            else:   # a honey pot
                m = (np.hypot(x, (y - 0.06) * 1.1) < 0.17) | ((np.abs(x) < 0.12) & (np.abs(y + 0.14) < 0.035))
            a[..., :3] = np.where(m[..., None], mix(a[..., :3], hc.STENCIL, 0.7), a[..., :3])
        if marked:
            a = mark(a, U, V, w, h)
        if broken > 0:
            row = int(rng.integers(n))
            split = ((V * n).astype(int) == row) & (U > rng.uniform(0.15, 0.4)) & (U < rng.uniform(0.6, 0.9)) & (((V * n) % 1.0) < 0.84)
            a[..., :3] = np.where(split[..., None], np.array((26, 18, 12), float), a[..., :3])
            if broken > 0.5 and face in ('front', 'right'):
                split2 = ((V * n).astype(int) == (row + 1) % n) & (U > 0.25) & (U < 0.7)
                a[split2] = 0
        return shade_edges(a, 0.15)
    return painter(key, fn)


def bottle_painter(key):
    """A bottle's green glass, lit down one side."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, GLASS_G, rng, 0.04)
        U, V = uv(w, h)
        a[..., :3] = mix(a[..., :3], GLASS_GL, (0.5 * np.exp(-((U - 0.3) / 0.12) ** 2))[..., None])
        return a
    return painter(key, fn)


def cork_painter(key):
    def fn(face, w, h, rng):
        return noise_fill(w, h, tt.CORK, rng, 0.08)
    return painter(key, fn)


def jar_painter(key):
    """A honey jar: glazed clay, a cloth tied over its mouth, a drip of honey down its side."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            return noise_fill(w, h, (226, 214, 186), rng, 0.05)
        a = noise_fill(w, h, JAR, rng, 0.05)
        a[..., :3] = mix(a[..., :3], JAR_L, (0.35 * np.exp(-((U - 0.35) / 0.2) ** 2))[..., None])
        a[..., :3] = np.where((V < 0.28)[..., None], noise_fill(w, h, (226, 214, 186), rng, 0.05)[..., :3], a[..., :3])
        a[..., :3] = np.where((np.abs(V - 0.3) < 0.06)[..., None], np.array(TWINE_D, float), a[..., :3])
        drip = (V > 0.3) & (V < 0.55) & (np.abs(U - 0.7) < 0.08)
        a[..., :3] = np.where(drip[..., None], np.array(HONEY, float), a[..., :3])
        return a
    return painter(key, fn)


def flint_sack_painter(key, part='body'):
    """A flint sack: grey-brown burlap, the nodules' knobbly shapes pressing through, flints at the neck."""
    def fn(face, w, h, rng):
        a = weave(w, h, (150, 136, 112), (110, 98, 78), (180, 166, 140), rng, cell=max(1, min(w, h) // 8))
        U, V = uv(w, h)
        for _ in range(6):
            cx, cy, r = rng.uniform(0.1, 0.9), rng.uniform(0.1, 0.9), rng.uniform(0.08, 0.16)
            d = np.hypot((U - cx) * w / max(h, 1), V - cy) / r
            a[..., :3] *= (1 - 0.18 * np.exp(-d * d) + 0.1 * np.exp(-(d - 0.6) ** 2 * 8))[..., None]
        if part == 'top' and face == 'top':
            fl = rng.random((h, w)) < 0.3
            a[..., :3] = np.where(fl[..., None], mix(np.array(FLINT, float), FLINT_L, rng.random((h, w))[..., None] * 0.6), a[..., :3])
        if part == 'base':
            a[..., :3] *= 0.8
        return shade_edges(a, 0.3, width=max(1, min(w, h) // 4))
    return painter(key, fn)


def twine_painter(key):
    """Twine wound round and round: fine diagonal turns."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, TWINE, rng, 0.05)
        turns = np.sin((U * w + V * h * 0.7) * 2.4) > 0.2
        a[..., :3] = np.where(turns[..., None], mix(a[..., :3], TWINE_D, 0.5), a[..., :3])
        return shade_edges(a, 0.2)
    return painter(key, fn)


def spill_painter(key, kind='grain'):
    """Spilled goods on the ground: grain (pale gold, every grain a speck), flour, coal, or roots rolled out of a crate."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if kind == 'grain':
            a = noise_fill(w, h, (206, 176, 96), rng, 0.08)
            sp = rng.random((h, w))
            a[..., :3] = np.where((sp < 0.25)[..., None], np.array((234, 210, 140), float), a[..., :3])
            a[..., :3] = np.where((sp > 0.9)[..., None], np.array((150, 120, 60), float), a[..., :3])
        elif kind == 'flour':
            a = noise_fill(w, h, (240, 236, 226), rng, 0.03)
        elif kind == 'coal':
            a = noise_fill(w, h, COAL, rng, 0.2)
            a[..., :3] = np.where((rng.random((h, w)) < 0.2)[..., None], np.array(COAL_L, float), a[..., :3])
        else:
            a = noise_fill(w, h, (176, 134, 78), rng, 0.1)
            bump = np.sin(U * 14 + rng.uniform(0, 6)) * np.sin(V * 14 + rng.uniform(0, 6)) > 0
            a[..., :3] *= (0.8 + 0.3 * bump)[..., None]
        return shade_edges(a, 0.25)
    return painter(key, fn)


def arrow_painter(key, part='shaft'):
    def fn(face, w, h, rng):
        if part == 'shaft':
            return grain(w, h, 'v', (178, 150, 104), (130, 104, 66), (206, 182, 140), rng)
        a = noise_fill(w, h, (226, 222, 214), rng, 0.04)      # the fletching: grey goose
        U, V = uv(w, h)
        a[..., :3] = np.where((V > 0.6)[..., None], mix(a[..., :3], (110, 104, 100), 0.6), a[..., :3])
        a[(V < 0.25) & (U > 0.5 + V)] = 0
        return a
    return painter(key, fn)


def tarp_painter(key):
    """The loot pile's tarp: oiled, dark, stained and creased."""
    def fn(face, w, h, rng):
        a = weave(w, h, (104, 98, 76), (70, 64, 48), (134, 128, 102), rng, cell=2)
        U, V = uv(w, h)
        for _ in range(3):
            ang, off = rng.uniform(0, math.pi), rng.uniform(-0.3, 0.3)
            d = (U - 0.5) * math.cos(ang) + (V - 0.5) * math.sin(ang) - off
            a[..., :3] *= (1 - 0.18 * np.exp(-(d / 0.03) ** 2))[..., None]
        st = rng.random((h, w)) < 0.04
        a[..., :3] = np.where(st[..., None], a[..., :3] * 0.7, a[..., :3])
        return shade_edges(a, 0.2)
    return painter(key, fn)


def rope_painter(key, along='x'):
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, ROPE, rng, 0.06)
        ax = tt.AXIS_ON_FACE[along][face]
        if ax is not None:
            f, g = (U, V) if ax == 'u' else (V, U)
            tw = np.sin((f * max(w, h) * 1.4 + g * 3.0) * 2.0) > 0
            a[..., :3] = np.where(tw[..., None], a[..., :3] * 0.78, a[..., :3])
        return a
    return painter(key, fn)


def cloth_painter(key):
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = noise_fill(w, h, hc.CLOTH, rng, 0.04)
        check = ((U * 5).astype(int) + (V * 5).astype(int)) % 2 == 0
        a[..., :3] = np.where(check[..., None], np.array(hc.CLOTH_L, float), a[..., :3])
        return a
    return painter(key, fn)


def marked_sack_painter(key):
    """The band's sacks: plain burlap, lumpy, the blue mark on them."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = weave(w, h, tt.BURLAP, tt.BURLAP_D, tt.BURLAP_L, rng, cell=max(1, min(w, h) // 8))
        lump = np.sin(U * 4.3 + rng.uniform(0, 6)) * np.sin(V * 3.7 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.12 * lump)[..., None]
        if face != 'bottom' and min(w, h) >= 6:
            a = mark(a, U, V, w, h)
        return shade_edges(a, 0.32, width=max(1, min(w, h) // 4))
    return painter(key, fn)

# ================================================================== building blocks


def C(origin, size, name, paint, share=None, faces=None):
    c = Cube(origin, size, paint, name=name, faces=faces)
    c.share = share
    return c


def plate(name, a, b, width, thick, paint, normal_hint, share=None, extra=0.0, faces=None, cube=None):
    """A thin straight strip from point [a] to [b] (its middle line), [width] across and [thick] through, its 'front'
    face turned toward [normal_hint]: modelled along its own x (so its box UV stays one short strip) and turned into
    place by its part, whose pivot is [a]. [extra] lengthens it at both ends to close the joins."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    x = b - a
    L = float(np.linalg.norm(x))
    x /= L
    n = np.asarray(normal_hint, float)
    n = n - x * float(np.dot(n, x))
    n /= np.linalg.norm(n)
    z = -n                          # the part's -z (its front face) looks along the hint
    y = np.cross(z, x)
    c = C((-extra, -width / 2, -thick / 2), (L + 2 * extra, width, thick), cube or f'c_{name}', paint, share=share, faces=faces)
    return Part(name, tuple(round(float(v), 4) for v in a), euler_from_axes(x, y), cubes=[c])


def arch_point(phi, inset=0.0):
    """A point (x, y) on the tilt's arch, phi 0 at the left (+x) side over to pi at the right; [inset] in from it."""
    c, s = math.cos(phi), math.sin(phi)
    e = 2.0 / ARCH_P
    return np.array([(ARCH_X - inset) * math.copysign(abs(c) ** e, c), ARCH_Y - (ARCH_H - inset) * abs(s) ** e])


def arch_points(inset=0.0, foot=True):
    """The arch as a polyline: from the left side's top up the straight to where the ellipse begins, over it in ARCH_N
    steps, and down the right."""
    pts = [np.array([ARCH_X - inset, SIDE_TOP - 0.2])] if foot else []
    pts += [arch_point(math.pi * k / ARCH_N, inset) for k in range(ARCH_N + 1)]
    if foot:
        pts.append(np.array([-(ARCH_X - inset), SIDE_TOP - 0.2]))
    return pts

# ---------------------------------------------------------------- the wheels


def wheel(name, side, r, axle_y, tag, nfel, spokes, hub, drop=(), stubs=()):
    """A wheel at [side] (-1 the right, 1 the left) of radius [r] with its middle at (side * WHEEL_X, [axle_y], 0):
    [nfel] felloes with the iron tyre round [spokes] spokes through the nave (twice as many seen), the nave (two
    crossed cubes, round enough) and the cap on the axle's end. Every wheel of a size shares its felloe's, spoke's and
    nave's paint ([tag]). [drop]: felloes ('rim', k) and spokes ('spoke', k) left out, [stubs]: spokes snapped short."""
    fl = 2 * math.pi * r / nfel * 1.07
    kids = []
    for k in range(nfel):
        if ('rim', k) in drop:
            continue
        kids.append(Part(f'{name}_rim{k}', (0, 0, 0), (k * 360.0 / nfel, 0, 0), cubes=[
            C((-FELLOE_W / 2, -r, -fl / 2), (FELLOE_W, 1.6, fl), f'c_{name}_rim{k}', None, share=f'c_rim_{tag}')]))
    for k in range(spokes):
        if ('spoke', k) in drop:
            continue
        if k in stubs:
            kids.append(Part(f'{name}_spoke{k}', (0, 0, 0), (k * 180.0 / spokes, 0, 0), cubes=[
                C((-0.55, -1.5, -0.55), (1.1, r - 4.6, 1.1), f'c_{name}_spoke{k}', None, share=f'c_stub_{tag}')]))
            continue
        kids.append(Part(f'{name}_spoke{k}', (0, 0, 0), (k * 180.0 / spokes, 0, 0), cubes=[
            C((-0.55, -r + 1.4, -0.55), (1.1, 2 * (r - 1.4), 1.1), f'c_{name}_spoke{k}', None, share=f'c_spoke_{tag}')]))
    hl, hw = hub
    h0 = -hl / 2 + 0.4 * side
    kids.append(Part(f'{name}_nave45', (0, 0, 0), (45.0, 0, 0), cubes=[
        C((h0, -hw / 2, -hw / 2), (hl, hw, hw), f'c_{name}_nave45', None, share=f'c_nave_{tag}')]))
    cubes = [C((h0, -hw / 2, -hw / 2), (hl, hw, hw), f'c_{name}_nave', None, share=f'c_nave_{tag}'),
             C((h0 + hl if side > 0 else h0 - 1.0, -1.1, -1.1), (1.0, 2.2, 2.2), f'c_{name}_cap', None, share='c_cap')]
    return Part(name, (side * WHEEL_X, axle_y, 0.0), (0, 0, 0), cubes=cubes, children=kids)


REAR = dict(r=RR, nfel=14, spokes=7, hub=(4.2, 5.0), tag='r')
FRONT = dict(r=RF, nfel=10, spokes=5, hub=(3.2, 3.8), tag='f')

SHARED = {}


def shared_painters():
    SHARED.update({
        'c_rim_r': hc.rim_painter('c_rim_r'), 'c_spoke_r': hc.spoke_painter('c_spoke_r'), 'c_nave_r': hc.hub_painter('c_nave_r'),
        'c_rim_f': hc.rim_painter('c_rim_f'), 'c_spoke_f': hc.spoke_painter('c_spoke_f'), 'c_nave_f': hc.hub_painter('c_nave_f'),
        'c_stub_r': hc.spoke_painter('c_stub_r'), 'c_cap': hc.cap_painter('c_cap'),
        'c_strap_iron': iron_painter('c_strap_iron'), 'c_ferrule': iron_painter('c_ferrule', 0.12),
        'c_shaft_tipcap': iron_painter('c_shaft_tipcap', 0.12),
        'c_stake': stake_painter('c_stake'), 'c_chain': iron_painter('c_chain', 0.2),
        'l_iron_bar': bars_painter('l_iron_bar'), 'l_bottle_neck': bottle_painter('l_bottle_neck'), 'l_cork': cork_painter('l_cork'),
        'l_jar': jar_painter('l_jar'), 'l_slat': hc.crate_painter('l_slat'), 'l_arrow': arrow_painter('l_arrow'),
        'l_fletch': arrow_painter('l_fletch', 'fletch'), 'l_root': spill_painter('l_root', 'roots'),
        'k_crate': box_painter('k_crate', top=lid_top, marked=True), 'k_sack': marked_sack_painter('k_sack'),
        'k_sack_neck': hc.sack_painter('k_sack_neck', False, 'neck'), 'k_barrel': barrel_painter('k_barrel', head=barrel_head),
        'k_peg': beam_painter('k_peg', 'y', ends=False), 'k_rope_x': rope_painter('k_rope_x', 'x'),
    })

# ---------------------------------------------------------------- the wagon


def body_cubes():
    """The fixed frame: the floor and its sills, the box's sides, front board and stakes, the back axle and its
    bolster, the bed's half of the turntable, the bench, the footboard's irons, the brake's beam, blocks and rods, the
    water barrel on its brackets, the lantern's crook and the pennant's staff."""
    cubes = []
    span = BOX_BACK - BOX_FRONT
    cubes.append(C((-(BOX_X - 1.0), FLOOR_TOP, BOX_FRONT + 1.0), (2 * (BOX_X - 1.0), FLOOR_T, span - 1.0), 'c_floor', floor_painter('c_floor')))
    irons = tuple((BOX_BACK - z) / span for z in STAKE_Z)
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(C((s * SILL_X - 1.0, FLOOR_TOP + FLOOR_T, BOX_FRONT + 1.5), (2.0, 1.5, span - 2.5), f'c_sill_{n}',
                       beam_painter(f'c_sill_{n}', 'z', ends=False)))
        x0 = BOX_X - 1.0 if s > 0 else -BOX_X
        outer = 'left' if s > 0 else 'right'
        cubes.append(C((x0, SIDE_TOP, BOX_FRONT), (1.0, FLOOR_TOP + FLOOR_T - SIDE_TOP, span), f'c_side_{n}',
                       plank_painter(f'c_side_{n}', 3, 'z', outer=outer, weathered=0.26,
                                     irons=irons if s < 0 else tuple(1 - c for c in irons))))
        for i, z in enumerate(STAKE_Z):
            cubes.append(C((s * STAKE_X - 0.6, SIDE_TOP - 1.4, z - 0.6), (1.2, 7.6 + FLOOR_T, 1.2), f'c_stake_{n}{i}', None, share='c_stake'))
    cubes.append(C((-(BOX_X - 1.0), SIDE_TOP, BOX_FRONT), (2 * (BOX_X - 1.0), FLOOR_TOP - SIDE_TOP, 1.0), 'c_front',
                   plank_painter('c_front', 3, 'x', outer='front')))
    # the back axle on its bolster, its ends through the naves
    cubes.append(C((-WHEEL_X - 1.6, -RR - 1.25, -1.25), (2 * WHEEL_X + 3.2, 2.5, 2.5), 'c_axle_r', hc.axle_painter('c_axle_r')))
    cubes.append(C((-BOX_X, SILL_BOTTOM, -1.75), (2 * BOX_X, -RR - 1.25 - SILL_BOTTOM, 3.5), 'c_bolster_r',
                   beam_painter('c_bolster_r', 'x', straps=(0.3, 0.7))))
    # the bed's half of the turntable: a bolster across under the sills, the front axle's under it
    cubes.append(C((-BOX_X, SILL_BOTTOM, ZF - 1.75), (2 * BOX_X, KINGPIN_Y - SILL_BOTTOM, 3.5), 'c_bolster_f',
                   beam_painter('c_bolster_f', 'x', straps=(0.3, 0.7))))
    # the bench: two planks across the box's front, on the sides' tops, overhanging them and the front
    cubes.append(C((-BENCH_X, BENCH_TOP, BENCH_FRONT), (2 * BENCH_X, BENCH_T, BENCH_BACK - BENCH_FRONT), 'c_bench',
                   plank_painter('c_bench', 2, 'x', broad=('top', 'bottom'), outer='top', weathered=0.12)))
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(C((s * (BOX_X - 0.5) - 0.4, SIDE_TOP, BOX_FRONT - 0.5), (0.8, 3.0, 0.5), f'c_bench_iron_{n}', SHARED['c_strap_iron']))
    # the footboard's irons, from the box's front forward under it
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(C((s * 8.5 - 0.5, FLOOR_TOP - 3.0, DASH[2] + 0.8), (1.0, 1.0, BOX_FRONT - DASH[2] - 0.8), f'c_dash_iron_{n}',
                       iron_painter(f'c_dash_iron_{n}')))
    # the brake: a beam across in front of the back wheels on two hangers, a block at each end against the tyre; a rod
    # along the box's right side from the lever's foot back, and a link down from it to the beam
    zb = -RR - 1.2
    cubes.append(C((-WHEEL_X - 0.5, -9.8, zb - 1.0), (2 * WHEEL_X + 1.0, 1.6, 1.6), 'c_brake_beam', beam_painter('c_brake_beam', 'x')))
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(C((s * WHEEL_X - 1.4, -11.4, zb + 0.1), (2.8, 4.2, 1.6), f'c_brake_block_{n}',
                       beam_painter(f'c_brake_block_{n}', 'y', base=(70, 50, 34), dark=(40, 28, 20), light=(100, 74, 50))))
        cubes.append(C((s * SILL_X - 0.3, SILL_BOTTOM, zb - 0.6), (0.6, -9.8 - SILL_BOTTOM, 0.6), f'c_brake_hanger_{n}', SHARED['c_strap_iron']))
    rod_x = -BOX_X - 0.45
    cubes.append(C((rod_x - 0.3, -18.9, BRAKE_PIVOT[2] + 0.2), (0.6, 0.6, zb - 0.8 - BRAKE_PIVOT[2] - 0.2), 'c_brake_rod', iron_painter('c_brake_rod')))
    cubes.append(C((rod_x - 0.3, -18.9, zb - 1.0), (0.6, -9.8 + 18.9, 0.6), 'c_brake_link', iron_painter('c_brake_link')))
    cubes.append(C((BRAKE_PIVOT[0] - 0.2, BRAKE_PIVOT[1] - 1.5, BRAKE_PIVOT[2] - 1.5), (1.4, 3.0, 3.0), 'c_brake_bracket', iron_painter('c_brake_bracket')))
    # the water barrel, lying along the left side between the wheels outside the stakes, on two brackets, strapped
    bz0, bz1, bx = -23.0, -15.4, BOX_X + 1.2
    cubes.append(C((bx, -25.4, bz0), (5.0, 5.0, bz1 - bz0), 'c_water', barrel_painter('c_water', along='z', head=barrel_head)))
    cubes.append(C((bx + 0.8, -26.0, bz0 + 1.2), (3.4, 6.2, bz1 - bz0 - 2.4), 'c_water_bulge', barrel_painter('c_water_bulge', along='z', hoops=())))
    for i, z in enumerate((bz0 + 0.9, bz1 - 1.5)):
        cubes.append(C((BOX_X, -20.4, z), (6.6, 0.6, 0.6), f'c_water_bracket{i}', SHARED['c_strap_iron']))
        cubes.append(C((bx + 0.3, -26.3, z), (4.6, 0.4, 0.6), f'c_water_strap{i}', strap_painter(f'c_water_strap{i}', along='x')))
    cubes.append(C((bx + 2.0, -26.5, (bz0 + bz1) / 2 - 0.5), (1.0, 0.6, 1.0), 'c_water_bung', cork_painter('c_water_bung')))
    # the lantern's crook: an iron rod up from the bench's left end and out to the hook
    lx, ly, lz = LANTERN_HOOK
    cubes.append(C((BENCH_X - 1.6, ly - 0.4, lz - 0.4), (0.8, BENCH_TOP - ly + 0.4, 0.8), 'c_crook_post', iron_painter('c_crook_post')))
    cubes.append(C((BENCH_X - 1.6, ly - 0.4, lz - 0.4), (lx - BENCH_X + 2.0, 0.8, 0.8), 'c_crook_arm', iron_painter('c_crook_arm')))
    # the pennant's staff over the front hoop
    px, py, pz = PENNANT_FOOT
    cubes.append(C((px - 0.45, py - PENNANT_TOP, pz - 0.45), (0.9, PENNANT_TOP + 1.2, 0.9), 'c_staff', pole_painter('c_staff', 'y', ferrules=(0.9,))))
    cubes.append(C((px - 0.7, py - PENNANT_TOP - 1.0, pz - 0.7), (1.4, 1.0, 1.4), 'c_staff_knob', iron_painter('c_staff_knob')))
    return cubes


def dash_part():
    """The raked footboard: a board leaning forward DASH_LEAN degrees, its back face where the driver's soles press."""
    return Part('dash', DASH, (DASH_LEAN, 0, 0), cubes=[
        C((-BENCH_X + 0.5, -3.2, -1.2), (2 * BENCH_X - 1.0, 6.4, 1.2), 'c_dash',
          plank_painter('c_dash', 2, 'x', outer='front', weathered=0.15, irons=(0.04, 0.96))),
        C((-BENCH_X + 0.2, -3.6, -1.4), (2 * BENCH_X - 0.4, 0.6, 1.6), 'c_dash_rail', iron_painter('c_dash_rail')),
    ])


def tailboard():
    """The tailboard, hinged at the floor's back edge and let down level on its two chains (it turns about x at the
    hinge: 0 let down, 90 shut up against the back stakes)."""
    w = 2 * (BOX_X - 1.0)
    cubes = [C((-w / 2, 0.0, 0.0), (w, 1.2, TAIL_LEN), 'c_tail',
               plank_painter('c_tail', 3, 'x', broad=('top', 'bottom'), outer='top', weathered=0.25, irons=(0.06, 0.94)))]
    kids = []
    for s, n in ((-1, 'r'), (1, 'l')):
        a = np.array([s * (BOX_X - 0.4), SIDE_TOP - 0.8 - FLOOR_TOP, STAKE_Z[-1] - BOX_BACK])
        b = np.array([s * (BOX_X - 1.6), 0.0, TAIL_LEN - 0.6])
        kids.append(plate(f'tail_chain_{n}', a, b, 0.5, 0.5, None, (s, 0, 0), share='c_chain'))
    return Part('tailboard', (0.0, FLOOR_TOP, BOX_BACK), (0, 0, 0), cubes=cubes, children=kids)


def brake_lever():
    """The brake lever on its pivot (it turns about x, its lean: + back toward the driver; BRAKE_REST off, BRAKE_ON
    hard on); its grip at the top, its foot a little under the pivot where the rod is."""
    return Part('brake_lever', BRAKE_PIVOT, (BRAKE_REST, 0, 0), cubes=[
        C((-0.5, -BRAKE_LEN, -0.6), (1.0, BRAKE_LEN + 2.6, 1.2), 'c_brake_lever', iron_painter('c_brake_lever', wear=0.12)),
        C((-0.75, -BRAKE_LEN - 0.4, -0.85), (1.5, 3.0, 1.7), 'c_brake_grip', pole_painter('c_brake_grip', 'y', base=(96, 66, 42), dark=(60, 40, 26), light=(130, 92, 60))),
        C((-0.3, -BRAKE_LEN + 4.0, -1.6), (0.6, 0.6, 1.0), 'c_brake_pawl', iron_painter('c_brake_pawl')),
    ])


def tilt(torn=None):
    """The canvas tilt on its four hoops, puckered shut at the front, open at the back, its lowest strips rolled up
    and tied along each side; the part's pivot is the middle of the box's top, so turning it about z sways it as the
    wagon rocks. [torn]: for the wreck, {strip index: tear} (a strip not in it is gone), hoops' arcs broken off."""
    kids = []
    piv = np.array([0.0, SIDE_TOP, 0.0])
    w = '' if torn is None else 'w_'
    hp = arch_points(inset=0.55)
    hoop_paint = hoop_painter('c_hoop')
    broken = ({(1, k) for k in range(4, 10)} | {(2, k) for k in range(3, 8)}) if torn is not None else set()
    for i, z in enumerate(HOOP_Z):
        for k in range(len(hp) - 1):
            if (i, k) in broken:
                continue
            a = np.array([hp[k][0], hp[k][1], z]) - piv
            b = np.array([hp[k + 1][0], hp[k + 1][1], z]) - piv
            mid = (a + b) / 2
            out = np.array([mid[0], mid[1] - (ARCH_Y - SIDE_TOP), 0.0])
            if torn is None:
                kids.append(plate(f'hoop{i}_{k}', a, b, 1.0, 0.9, hoop_paint, out, extra=0.35, cube=f'c_hoop{i}_{k}'))
            else:
                kids.append(plate(f'w_hoop{i}_{k}', a, b, 1.0, 0.9, None, out, extra=0.35, cube=f'w_hoop{i}_{k}', share=f'c_hoop{i}_{k}'))
    pts = arch_points(foot=False)
    patches = [0, 1, 0, 1, 0, 0, 1, 0]
    n = len(pts) - 1
    for k in range(ROLLED, n - ROLLED):
        if torn is not None and k not in torn:
            continue
        a2, b2 = pts[k], pts[k + 1]
        step = float(np.linalg.norm(b2 - a2))
        mid2 = (a2 + b2) / 2
        a = np.array([mid2[0], mid2[1], TILT_FRONT]) - piv
        b = np.array([mid2[0], mid2[1], TILT_BACK]) - piv
        x = (b - a) / np.linalg.norm(b - a)
        t = np.array([b2[0] - a2[0], b2[1] - a2[1], 0.0])
        t /= np.linalg.norm(t)
        out = np.array([mid2[0], mid2[1] - ARCH_Y, 0.0])
        if float(np.dot(np.cross(x, t), out)) > 0:      # the part's -z (the strip's front) must look outward
            t = -t
        edge = None
        if k == ROLLED or k == n - ROLLED - 1:
            edge = 'v0' if k == ROLLED else 'v1'
        key = f'{w or "c_"}canvas{k}'
        paint = canvas_painter(key, patches[k % len(patches)], torn=0.0 if torn is None else torn[k], edge=edge)
        c = C((0.0, -step / 2 - 0.25, -0.22), (TILT_BACK - TILT_FRONT, step + 0.5, 0.45), key, paint)
        kids.append(Part(f'{w}canvas{k}', tuple(round(float(v), 4) for v in a), euler_from_axes(x, t), cubes=[c]))
    # the rolled-up canvas along each side, tied to the hoops
    for s, nm, k in ((1, 'l', ROLLED), (-1, 'r', n - ROLLED)):
        p = pts[k]
        kids.append(Part(f'{w}tilt_roll_{nm}', (float(p[0]) + 0.4 * s, float(p[1] - SIDE_TOP) + 0.3, 0.0), (0, 0, 0), cubes=[
            C((-1.0, -1.0, TILT_FRONT - 0.3), (2.0, 2.0, TILT_BACK - TILT_FRONT + 0.6), f'{w or "c_"}tilt_roll_{nm}',
              roll_painter(f'c_tilt_roll_{nm}') if torn is None else None, share=f'c_tilt_roll_{nm}' if torn is not None else None)]))
    # the front end, puckered round its opening, just inside the front hoop
    top = ARCH_Y - ARCH_H - 0.2
    kids.append(Part(f'{w}pucker', (0.0, 0.0, TILT_FRONT + 0.6), (0, 0, 0) if torn is None else (8.0, 0, 5.0), cubes=[
        C((-ARCH_X, top - SIDE_TOP, -0.25), (2 * ARCH_X, SIDE_TOP - top, 0.5), f'{w or "c_"}pucker',
          pucker_painter('c_pucker') if torn is None else None, share='c_pucker' if torn is not None else None,
          faces=('front', 'back'))]))
    return Part(f'{w}tilt', tuple(piv), (0, 0, 0), cubes=[], children=kids)


def lantern():
    """The lantern hanging from its crook's hook (the part's pivot), which it swings about: an iron bail, cap and base,
    four corner posts and warm glass (its lit panes are the glow texture's)."""
    posts = [C((x, 3.4, z), (0.7, 4.4, 0.7), f'c_lantern_post_{i}', SHARED['c_strap_iron'])
             for i, (x, z) in enumerate(((-2.1, -2.1), (1.4, -2.1), (-2.1, 1.4), (1.4, 1.4)))]
    return Part('lantern', LANTERN_HOOK, (0, 0, 0), cubes=[
        C((-1.4, 0.0, -0.3), (2.8, 2.4, 0.6), 'c_lantern_bail', tt.bail_painter('c_lantern_bail')),
        C((-2.1, 2.2, -2.1), (4.2, 1.2, 4.2), 'c_lantern_cap', tt.lantern_iron_painter('c_lantern_cap', rivets=True)),
        C((-1.75, 3.4, -1.75), (3.5, 4.4, 3.5), 'c_lantern_glass', tt.lantern_glass_painter('c_lantern_glass')),
        *posts,
        C((-2.1, 7.8, -2.1), (4.2, 1.0, 4.2), 'c_lantern_base', tt.lantern_iron_painter('c_lantern_base', rivets=True)),
    ])


GLOW_CUBES = {'c_lantern_glass'}


def pennant():
    """The city's pennant off the staff's top: three pieces, each turning about y at its root so the renderer can
    ripple it in the wind; the last swallow-tailed. It flies back over the tilt."""
    px, py, pz = PENNANT_FOOT
    p2 = Part('pennant_2', (4.6, 0.0, 0.0), (0, 0, 0), cubes=[
        C((0.0, -1.5, -0.2), (4.8, 3.0, 0.4), 'c_pennant_2', pennant_painter('c_pennant_2', 2), faces=('front', 'back'))])
    p1 = Part('pennant_1', (4.6, 0.0, 0.0), (0, 0, 0), cubes=[
        C((0.0, -1.8, -0.2), (4.8, 3.6, 0.4), 'c_pennant_1', pennant_painter('c_pennant_1', 1), faces=('front', 'back'))], children=[p2])
    return Part('pennant', (px, py - PENNANT_TOP + 1.6, pz), (0, -90.0, 0), cubes=[
        C((0.0, -2.1, -0.2), (4.8, 4.2, 0.4), 'c_pennant_0', pennant_painter('c_pennant_0', 0), faces=('front', 'back'))], children=[p1])


def shaft_line(s):
    """Where a shaft runs, in the wagon's space with the front straight: from its hinge to its tip by the horse."""
    a = np.array([s * SHAFT_HINGE[0], SHAFT_HINGE[1], SHAFT_HINGE[2]])
    b = np.array([s * SHAFT_X, -SHAFT_UP, HORSE_Z + SHAFT_TIP_Z])
    return a, b


def shafts():
    """The two shafts on their hinges (the part's pivot is the middle of the axis through both): ash poles running
    from the front axle's hounds up to the horse's shoulders, through the tugs on its saddle, braced near the hinges by
    a bar, each with an iron tip and a hook for the breeching. It turns about x: + lifts the tips."""
    piv = np.array([0.0, SHAFT_HINGE[1], SHAFT_HINGE[2]])
    kids = []
    for s, n in ((-1, 'r'), (1, 'l')):
        a, b = shaft_line(s)
        L = float(np.linalg.norm(b - a))
        tug = (HORSE_Z + TUG_Z - a[2]) / (b[2] - a[2])
        kids.append(plate(f'shaft_{n}', a - piv, b - piv, SHAFT_T, SHAFT_T,
                          pole_painter(f'c_shaft_{n}', 'x', ferrules=(0.04, tug - 0.03, tug + 0.03, 0.985)), (0, -1, 0)))
        breech = a + (b - a) * ((HORSE_Z + 6.0 - a[2]) / (b[2] - a[2])) - piv
        kids.append(Part(f'shaft_hook_{n}', tuple(float(v) for v in breech), (0, 0, 0), cubes=[
            C((-0.5 - 0.6 * s, -0.5, -0.5), (1.0, 1.0, 1.0), f'c_shaft_hook_{n}', None, share='c_ferrule2')]))
        tip = a + (b - a) * (1 + 0.6 / L) - piv
        kids.append(Part(f'shaft_tip_{n}', tuple(float(v) for v in tip), (0, 0, 0), cubes=[
            C((-1.05, -1.05, -1.05), (2.1, 2.1, 2.1), f'c_shaft_tip_{n}', None, share='c_shaft_tipcap')]))
    cubes = [C((-SHAFT_HINGE[0] + 0.4, -0.7, -6.8), (2 * SHAFT_HINGE[0] - 0.8, 1.4, 1.6), 'c_shaft_bar', beam_painter('c_shaft_bar', 'x'))]
    return Part('shafts', tuple(float(v) for v in piv - np.array([0.0, KINGPIN_Y, ZF])), (0, 0, 0), cubes=cubes, children=kids)


def front_assembly(prefix='', wheels=('fr', 'fl'), with_shafts=True):
    """What turns with the front axle about the kingpin: the axle and its bolster, the hounds that run forward to the
    shafts' hinges, the swingletree, the front wheels and the shafts. The part's pivot is the kingpin at the
    bolster's top; turn it about y to steer. With a [prefix] (the wreck's copy) every cube takes the wagon's paint."""
    piv = np.array([0.0, KINGPIN_Y, ZF])
    ay = -RF - KINGPIN_Y        # the axle's middle below the pivot
    p = prefix or 'c_'

    def own(name, paint):
        return (None, f'c_{name}') if prefix else (paint, None)
    specs = [('axle_f', (-WHEEL_X - 1.4, ay - 1.1, -1.1), (2 * WHEEL_X + 2.8, 2.2, 2.2), lambda: hc.axle_painter('c_axle_f')),
             ('bolster_ff', (-BOX_X + 1.0, 0.0, -1.6), (2 * BOX_X - 2.0, ay - 1.1, 3.2), lambda: beam_painter('c_bolster_ff', 'x', straps=(0.32, 0.68))),
             ('fifth', (-2.2, -0.35, -2.2), (4.4, 0.35, 4.4), lambda: iron_painter('c_fifth'))]
    hy, hz = SHAFT_HINGE[1] - KINGPIN_Y, SHAFT_HINGE[2] - ZF
    for s, n in ((-1, 'r'), (1, 'l')):
        specs.append((f'hound_{n}', (s * SHAFT_HINGE[0] - 0.8, hy - 0.8, hz - 0.8), (1.6, 1.6, -hz + 0.8), lambda n=n: beam_painter(f'c_hound_{n}', 'z')))
    sy, sz = SWINGLE[1] - KINGPIN_Y, SWINGLE[2] - ZF
    specs.append(('swingle', (-SWINGLE_HALF, sy - 0.75, sz - 0.75), (2 * SWINGLE_HALF, 1.5, 1.5), lambda: beam_painter('c_swingle', 'x')))
    cubes = []
    for name, o, size, mk in specs:
        paint, share = own(name, None)
        cubes.append(C(o, size, f'{p}{name}', mk() if not prefix else None, share=share))
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(C((s * SHAFT_HINGE[0] - 1.1, hy - 1.1, hz - 1.1), (2.2, 2.2, 2.2), f'{p}hinge_{n}', None, share='c_ferrule'))
        cubes.append(C((s * SWINGLE_HALF - 0.5, sy - 0.4, sz - 1.4), (1.0, 0.8, 1.0), f'{p}swingle_hook_{n}', None, share='c_ferrule2'))
    cubes.append(C((-0.6, sy - 0.75, sz + 0.75), (1.2, 1.0, -sz - 0.75 - 1.1), f'{p}swingle_pin', None, share='c_swingle_pin'))
    kids = []
    for side, k in ((-1, 'fr'), (1, 'fl')):
        if k in wheels:
            wh = wheel(f'{prefix}wheel_{k}', side, RF, ay, FRONT['tag'], FRONT['nfel'], FRONT['spokes'], FRONT['hub'])
            if prefix:
                wh = rename(wh, prefix)
            kids.append(wh)
    if with_shafts:
        kids.append(shafts())
    return Part(f'{prefix}front', tuple(float(v) for v in piv), (0, 0, 0), cubes=cubes, children=kids)


def rename(part, prefix):
    """A copy of [part] whose cubes are named [prefix]... and take the wagon's own cubes' paint (by their old names)."""
    def go(p):
        cubes = [C(c.origin, c.size, prefix + c.name[2:], None, share=c.share or c.name, faces=c.faces) for c in p.cubes]
        name = p.name if p.name.startswith(prefix) else prefix + p.name
        return Part(name, p.pivot, p.rot, cubes=cubes, children=[go(k) for k in p.children])
    return go(part)


def rear_wheel(name, side, **kw):
    return wheel(name, side, RR, -RR, REAR['tag'], REAR['nfel'], REAR['spokes'], REAR['hub'], **kw)


def wagon_parts():
    body = Part('body', (0, 0, 0), (0, 0, 0), cubes=body_cubes(), children=[dash_part()])
    return [body, tailboard(), brake_lever(), tilt(), lantern(), pennant(),
            rear_wheel('wheel_rr', -1), rear_wheel('wheel_rl', 1), front_assembly()]

# ---------------------------------------------------------------- the load


def sack_cubes(name, paint, size=1.0):
    """A pillow of a sack (the handcart's shape): narrower where it sits and on top than round its belly, its neck
    gathered at the front and tied off."""
    return [C((-2.8 * size, -1.2, -2.8 * size), (5.6 * size, 1.2, 5.6 * size), f'l_{name}_base', paint(f'l_{name}_base', 'base')),
            C((-3.1 * size, -3.3, -3.1 * size), (6.2 * size, 2.1, 6.2 * size), f'l_{name}', paint(f'l_{name}', 'body')),
            C((-2.5 * size, -4.2, -2.6 * size), (5.0 * size, 0.9, 5.2 * size), f'l_{name}_top', paint(f'l_{name}_top', 'top')),
            C((-1.2, -3.1, -3.1 * size - 0.8), (2.4, 2.1, 0.8), f'l_{name}_neck', paint(f'l_{name}_neck', 'neck')),
            C((-1.35, -3.25, -3.1 * size - 0.5), (2.7, 2.4, 0.45), f'l_{name}_tie', hc.cord_painter(f'l_{name}_tie'))]


def load_parts():
    """The pieces that heap on the bed, each standing on the middle of its underside at its part's origin: the
    handcart's sack, flour, crate, basket and fish box, and the wagon's own barrel, iron bars, coal sack, wool bale,
    bottle crate, candle box, honey crate, flint sack and coil of string; then the broken ones the wreck's lots show."""
    parts = hc.load_parts()
    barrel = Part('barrel', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.6, -7.0, -2.6), (5.2, 7.0, 5.2), 'l_barrel', barrel_painter('l_barrel', head=barrel_head))],
        children=[Part('barrel45', (0, 0, 0), (0, 45.0, 0), cubes=[
            C((-2.6, -6.9, -2.6), (5.2, 6.8, 5.2), 'l_barrel45', barrel_painter('l_barrel45', head=barrel_head))])])
    bars = [C((x - 0.7, y, -3.4), (1.4, 1.4, 6.8), f'l_iron{i}', None, share='l_iron_bar')
            for i, (x, y) in enumerate(((-2.4, -1.4), (-0.8, -1.4), (0.8, -1.4), (2.4, -1.4), (-1.6, -2.8), (0.0, -2.8), (1.6, -2.8)))]
    bars += [C((-3.3, -3.0, z), (6.6, 3.0, 0.5), f'l_iron_wire{i}', None, share='l_wire') for i, z in enumerate((-2.5, 2.0))]
    iron = Part('iron', (0, 0, 0), (0, 0, 0), cubes=bars)
    coal = Part('coal', (0, 0, 0), (0, 0, 0), cubes=sack_cubes('coal', coal_sack_painter))
    wool = Part('wool', (0, 0, 0), (0, 0, 0), cubes=[C((-3.2, -5.4, -3.3), (6.4, 5.4, 6.6), 'l_wool', wool_painter('l_wool')),
                                                     C((-2.8, -5.8, -2.9), (5.6, 0.4, 5.8), 'l_wool_top', wool_painter('l_wool_top'))])
    bottle_cubes = [C((-3.1, -4.6, -3.3), (6.2, 4.6, 6.6), 'l_bottles', box_painter('l_bottles', top=straw_top, stencil='bottle'))]
    for i, (x, z) in enumerate(((-1.6, -1.8), (0.0, -1.7), (1.6, -1.8), (-1.6, 0.0), (1.6, 0.0), (-1.6, 1.8), (0.0, 1.8))):
        bottle_cubes.append(C((x - 0.45, -5.6, z - 0.45), (0.9, 1.4, 0.9), f'l_bottle{i}', None, share='l_bottle_neck'))
        bottle_cubes.append(C((x - 0.35, -6.0, z - 0.35), (0.7, 0.45, 0.7), f'l_bottle_cork{i}', None, share='l_cork'))
    bottles = Part('bottles', (0, 0, 0), (0, 0, 0), cubes=bottle_cubes)
    candles = Part('candles', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.9, -2.9, -2.3), (5.8, 2.9, 4.6), 'l_candles', box_painter('l_candles', base=(150, 112, 74), dark=(100, 70, 44), light=(186, 150, 104), top=lid_top, stencil='candle')),
        C((-3.0, -3.25, -2.4), (6.0, 0.35, 4.8), 'l_candles_lid', box_painter('l_candles_lid', base=(160, 120, 80), dark=(110, 78, 50), light=(196, 158, 110), top=lid_top))])
    honey_cubes = [C((-3.1, -4.4, -3.3), (6.2, 4.4, 6.6), 'l_honey', box_painter('l_honey', top=straw_top, stencil='honey'))]
    for i, (x, z) in enumerate(((-1.5, -1.6), (1.5, -1.6), (-1.5, 1.6), (1.5, 1.6))):
        honey_cubes.append(C((x - 1.1, -5.0, z - 1.1), (2.2, 2.0, 2.2), f'l_jar{i}', None, share='l_jar'))
    honey = Part('honey', (0, 0, 0), (0, 0, 0), cubes=honey_cubes)
    flint = Part('flint', (0, 0, 0), (0, 0, 0), cubes=sack_cubes('flint', flint_sack_painter, 0.92))
    coil = [C(o, s, f'l_string{i}', twine_painter(f'l_string{i}')) for i, (o, s) in enumerate((
        ((-2.6, -2.4, -2.6), (5.2, 2.4, 1.6)), ((-2.6, -2.4, 1.0), (5.2, 2.4, 1.6)),
        ((-2.6, -2.4, -1.0), (1.6, 2.4, 2.0)), ((1.0, -2.4, -1.0), (1.6, 2.4, 2.0))))]
    string = Part('string', (0, 0, 0), (0, 0, 0), cubes=coil)
    parts += [barrel, iron, coal, wool, bottles, candles, honey, flint, string]
    return parts + broken_parts()


def broken_parts():
    """The wreck's lots: a torn grain sack slumped by its spill, the same for flour and for coal, a stove-in crate on
    its side with its slats sprung and roots rolled out of it, a barrel on its side with its head knocked in, a basket
    on its side with its cloth out."""
    out = []

    def torn(name, paint, spill_kind):
        return Part(name, (0, 0, 0), (0, 0, 0), cubes=[
            C((-3.2, -2.2, -3.4), (6.4, 2.2, 6.8), f'l_{name}', paint(f'l_{name}', 'body')),
            C((-2.4, -3.0, -2.6), (4.6, 0.8, 4.6), f'l_{name}_top', paint(f'l_{name}_top', 'top')),
            C((-1.2, -1.8, -4.4), (2.4, 1.8, 1.0), f'l_{name}_neck', paint(f'l_{name}_neck', 'neck')),
            C((-2.6, -1.1, 3.0), (5.2, 1.1, 3.8), f'l_{name}_spill', spill_painter(f'l_{name}_spill', spill_kind)),
            C((-3.6, -0.5, 5.6), (7.2, 0.5, 3.0), f'l_{name}_spill2', spill_painter(f'l_{name}_spill2', spill_kind))])
    out.append(torn('sack_torn', lambda k, p: hc.sack_painter(k, False, p), 'grain'))
    out.append(torn('flour_torn', lambda k, p: hc.sack_painter(k, True, p), 'flour'))
    out.append(torn('coal_torn', coal_sack_painter, 'coal'))
    out.append(Part('crate_broken', (0, 0, 0), (0, 0, 0), cubes=[
        C((-3.1, -4.6, -3.3), (6.2, 4.6, 6.6), 'l_crate_broken', box_painter('l_crate_broken', top=straw_top, broken=1.0)),
        C((-3.6, -0.6, 3.6), (6.0, 0.6, 1.2), 'l_slat0', None, share='l_slat'),
        C((-1.0, -0.6, 5.2), (6.0, 0.6, 1.2), 'l_slat1', None, share='l_slat'),
        C((-2.4, -1.2, 4.6), (2.0, 1.2, 2.0), 'l_root0', None, share='l_root'),
        C((0.4, -1.2, 6.8), (2.0, 1.2, 2.0), 'l_root1', None, share='l_root'),
        C((-4.6, -1.2, 1.0), (2.0, 1.2, 2.0), 'l_root2', None, share='l_root'),
    ]))
    out.append(Part('barrel_broken', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.6, -5.2, -3.6), (5.2, 5.2, 7.2), 'l_barrel_broken', barrel_painter('l_barrel_broken', along='z', burst=True)),
        C((-1.6, -6.0, -2.6), (3.2, 0.8, 5.2), 'l_barrel_broken_top', barrel_painter('l_barrel_broken_top', along='z', hoops=(0.2, 0.8))),
        C((-2.2, -0.4, 3.9), (4.4, 0.4, 4.4), 'l_barrel_head', barrel_painter('l_barrel_head', along='y', head=barrel_head)),
    ]))
    out.append(Part('basket_spilled', (0, 0, 0), (0, 0, 0), cubes=[
        C((-2.6, -0.6, 1.9), (5.2, 0.6, 3.4), 'l_basket_cloth', cloth_painter('l_basket_cloth'))], children=[
        Part('basket_spilled_on_side', (0.0, -3.2, 0.0), (-90.0, 0, 0), cubes=[     # its open top toward +z
            C((-3.0, -1.8, -3.2), (6.0, 3.6, 6.4), 'l_basket_spilled', hc.basket_painter('l_basket_spilled'))])]))
    return out

# ---------------------------------------------------------------- the wreck


def hull_parts():
    """The wagon's parts as the wreck has them, still upright: its right wheels gone, the tilt half torn, the shafts
    torn off, the brake rod sprung, the tailboard hanging, arrows in the boards."""
    by = {p.name: p for p in wagon_parts()}

    def copy(p, drop=()):
        cubes = [C(c.origin, c.size, 'w_' + c.name[2:], None, share=c.share or c.name, faces=c.faces)
                 for c in p.cubes if c.name not in drop]
        return Part('w_' + p.name, p.pivot, p.rot, cubes=cubes, children=[copy(k, drop) for k in p.children if k.name not in drop])
    kids = [copy(by['body'], drop={'c_brake_rod', 'c_brake_link'})]
    tb = copy(by['tailboard'])
    tb.rot = (-38.0, 0, 0)      # sagging on its chains
    kids.append(tb)
    kids.append(copy(by['brake_lever']))
    kids.append(tilt(torn={2: 0.45, 5: 0.55, 6: 0.35}))
    kids.append(copy(by['pennant']))
    kids.append(copy(by['wheel_rl']))
    nave = by['wheel_rr'].cubes[0]
    kids.append(Part('w_wheel_rr', by['wheel_rr'].pivot, (0, 0, 0), cubes=[
        C((nave.origin[0] + 1.4, nave.origin[1], nave.origin[2]), (nave.size[0] - 1.4, nave.size[1], nave.size[2]),
          'w_wheel_rr_nave', hc.hub_painter('w_wheel_rr_nave'))]))
    fr = front_assembly('w_', wheels=('fl',), with_shafts=False)
    fr.rot = (0, -24.0, 0)
    kids.append(fr)
    # arrows: in the left side (it will be on top), the tilt and the footboard; each (where, which way it stands out)
    for i, (p, d) in enumerate((((BOX_X, -22.0, -36.0), (1.0, -0.3, 0.25)), ((BOX_X, -23.5, -4.0), (1.0, 0.2, -0.3)),
                                ((BOX_X, -20.5, -27.0), (1.0, -0.15, 0.45)),
                                ((float(arch_point(math.pi / 4)[0]), float(arch_point(math.pi / 4)[1]), -14.0), (0.7, -0.7, 0.2)),
                                ((float(arch_point(math.pi * 0.4)[0]), float(arch_point(math.pi * 0.4)[1]), -30.0), (0.4, -0.9, -0.3)),
                                ((-3.0, -27.0, DASH[2] - 1.6), (0.25, -0.2, -1.0)))):
        kids.append(arrow_part(f'w_arrow{i}', p, d))
    return kids


def corners(parts, R=np.eye(3), T=np.zeros(3), skip=()):
    """Every cube corner of [parts] placed by R and T (as faces_of places them)."""
    pts = []

    def go(part, M0, T0):
        if part.name in skip:
            return
        Rm = M0 @ hc.rot_part(part.rot)
        P = T0 + M0 @ np.array(part.pivot, float)
        for c in part.cubes:
            o, s = np.array(c.origin, float), np.array(c.size, float)
            for d in np.array(np.meshgrid([0, 1], [0, 1], [0, 1])).T.reshape(-1, 3):
                pts.append(P + Rm @ (o + d * s))
        for ch in part.children:
            go(ch, Rm, P)
    for p in parts:
        go(p, R, T)
    return np.array(pts)


def roll_about_tip(deg):
    """The wagon turned [deg] about z through TIP_AXIS, as (R, T): a point p goes to R p + T."""
    R = hc.rotz(deg)
    ax = np.array([TIP_AXIS[0], TIP_AXIS[1], 0.0])
    return R, ax - R @ ax


def wreck_roll():
    """How far the wagon rolls (degrees about z, negative: over onto its right side) about TIP_AXIS before its box
    (the body, the stakes and the bench) meets the ground; the back axle's stub may dig in a little."""
    body = [p for p in hull_parts() if p.name == 'w_body']
    for deg in np.arange(-60.0, -130.0, -0.25):
        R, T = roll_about_tip(deg)
        if corners(body, R, T, skip={'w_dash'})[:, 1].max() >= 0.3:
            return float(deg)
    raise SystemExit('wagon: the wreck never meets the ground')


def wreck_parts():
    """The wreck, in the wagon's space where it stood: 'wreck' (the hull, rolled WRECK_ROLL about TIP_AXIS: inside
    it 'w_hull' with the wagon's parts), 'w_wheel_loose' (the back right wheel lying flat apart, felloes and spokes
    broken), 'w_shafts_down' (the shafts torn off, one snapped, lying ahead), 'w_strewn' (a few goods in the grass) and
    'w_lantern' (thrown off its crook, lying in the grass, out)."""
    ax = (TIP_AXIS[0], TIP_AXIS[1], 0.0)
    hull = Part('wreck', ax, (0, 0, WRECK_ROLL), cubes=[], children=[
        Part('w_hull', (-ax[0], -ax[1], 0.0), (0, 0, 0), cubes=[], children=hull_parts())])
    loose = rear_wheel('w_wheel_loose', -1, drop={('rim', 3), ('rim', 4), ('spoke', 2)}, stubs={5, 6})
    loose = rename(loose, 'w_')
    loose.name = 'w_wheel_loose'
    loose.rot = (0, 28.0, 90.0)        # lying flat on its outer face
    loose.pivot = (0.0, 0.0, 0.0)
    lowest = corners([loose])[:, 1].max()
    loose.pivot = (-20.0, -lowest, 34.0)
    down = []
    for name, a, b in (('w_shaft_a', (-22.0, -SHAFT_T / 2, -60.0), (-31.0, -SHAFT_T / 2, -122.0)),
                       ('w_shaft_b', (-6.0, -SHAFT_T / 2, -64.0), (-13.0, -SHAFT_T / 2, -95.0)),
                       ('w_shaft_c', (-2.0, -SHAFT_T / 2, -101.0), (-21.0, -SHAFT_T / 2, -126.0))):
        down.append(plate(name, a, b, SHAFT_T, SHAFT_T, pole_painter('c' + name[1:], 'x', ferrules=(0.04,)), (0, -1, 0)))
    shafts_down = Part('w_shafts_down', (0, 0, 0), (0, 0, 0), cubes=[], children=down)
    strewn = Part('w_strewn', (0, 0, 0), (0, 0, 0), cubes=[
        C((-46.0, -1.2, 30.0), (2.0, 1.2, 2.0), 'w_strewn0', None, share='l_root'),
        C((-79.0, -1.2, 14.0), (2.0, 1.2, 2.0), 'w_strewn1', None, share='l_root'),
        C((-60.0, -1.2, -24.0), (2.0, 1.2, 2.0), 'w_strewn2', None, share='l_root'),
        C((-36.0, -0.5, 36.0), (7.2, 0.5, 3.0), 'w_strewn3', None, share='l_sack_torn_spill2'),
        C((-88.0, -0.5, -20.0), (7.2, 0.5, 3.0), 'w_strewn4', None, share='l_sack_torn_spill2'),
    ])
    lamp = lantern()
    lamp = Part('w_lantern', (-62.0, -2.1, -50.0), (0, 35.0, 90.0), cubes=[
        C(c.origin, c.size, 'w_' + c.name[2:], dark_glass_painter('w_lantern_glass') if c.name == 'c_lantern_glass' else None,
          share=None if c.name == 'c_lantern_glass' else (c.share or c.name), faces=c.faces) for c in lamp.cubes])
    return [hull, loose, shafts_down, strewn, lamp]


def arrow_part(name, p, d):
    """An arrow stuck in a board at [p], its shaft standing out along [d], the fletching at the far end."""
    d = np.asarray(d, float)
    d /= np.linalg.norm(d)
    x = np.cross(d, np.array([0.0, 0.0, 1.0])) if abs(d[2]) < 0.9 else np.cross(d, np.array([1.0, 0.0, 0.0]))
    return Part(name, tuple(float(v) for v in p), euler_from_axes(x, d), cubes=[
        C((-0.25, -1.0, -0.25), (0.5, 8.0, 0.5), f'{name}_shaft', None, share='l_arrow'),
        C((-0.05, 5.0, -0.8), (0.1, 2.4, 1.6), f'{name}_fletch', None, share='l_fletch', faces=('right', 'left')),
    ])

# ---------------------------------------------------------------- the loot pile


def loot_parts():
    """The band's loot: three marked crates, two marked sacks and a barrel heaped by the camp, a tarp thrown over the
    top of the heap and pegged down ('tarp' and 'tarp_ropes', shown while any of it is left; the pieces 'loot_0' to
    'loot_5', hidden one by one as they're taken: 'loot_4' sits on top of 'loot_0' and 'loot_1')."""
    pieces = []
    places = [((-3.6, 0.0, -3.0), 8.0, 'crate'), ((3.0, 0.0, -3.4), -12.0, 'crate'), ((-2.6, 0.0, 4.2), 20.0, 'sack'),
              ((4.6, 0.0, 3.6), -30.0, 'sack'), ((-0.4, -4.6, -3.0), 15.0, 'crate'), ((-9.6, 0.0, 1.0), 0.0, 'barrel')]
    for i, (p, yaw, kind) in enumerate(places):
        if kind == 'crate':
            cubes = [C((-3.1, -4.6, -3.3), (6.2, 4.6, 6.6), f'k_crate{i}', None, share='k_crate')]
        elif kind == 'sack':
            cubes = [C((-3.0, -3.8, -3.0), (6.0, 3.8, 6.0), f'k_sack{i}', None, share='k_sack'),
                     C((-1.2, -3.4, -3.8), (2.4, 2.0, 0.8), f'k_sack_neck{i}', None, share='k_sack_neck')]
        else:
            cubes = [C((-2.6, -7.0, -2.6), (5.2, 7.0, 5.2), f'k_barrel{i}', None, share='k_barrel')]
        pieces.append(Part(f'loot_{i}', p, (0, yaw, 0), cubes=cubes))
    tarp = Part('tarp', (0.0, -9.4, -0.6), (0, 0, 4.0), cubes=[
        C((-7.0, -0.3, -6.5), (14.0, 0.6, 13.0), 'k_tarp', tarp_painter('k_tarp'))],
        children=[Part(f'tarp_side_{n}', (s * 7.0, 0.0, 0.0), (0, 0, -s * 58.0), cubes=[
            C((-0.3, 0.0, -6.5), (0.6, 9.0, 13.0), f'k_tarp_side_{n}', tarp_painter(f'k_tarp_side_{n}'))])
            for s, n in ((-1, 'r'), (1, 'l'))])
    ropes = Part('tarp_ropes', (0, 0, 0), (0, 0, 0), cubes=[
        C((-0.3, -10.1, -7.2), (0.6, 0.6, 14.4), 'k_rope0', rope_painter('k_rope0', 'z')),
        C((-15.2, -1.6, -0.6), (1.2, 1.6, 1.2), 'k_peg0', None, share='k_peg'),
        C((14.0, -1.6, -0.6), (1.2, 1.6, 1.2), 'k_peg1', None, share='k_peg'),
    ], children=[plate(f'tarp_rope_{n}', (s * 7.0, -9.8, 0.0), (s * 14.6, -1.2, 0.0), 0.5, 0.5, None, (0, -1, 0), share='k_rope_x', cube=f'k_rope_{n}')
                 for s, n in ((-1, 'r'), (1, 'l'))])
    return pieces + [tarp, ropes]

# ---------------------------------------------------------------- the straps


def strap_parts():
    """What the renderer stretches between two points: 'rein' (from the driver's fist to the bit) and 'trace' (from the
    hame's hook to the swingletree's), each 16 px along +y from its pivot: scale its y by the distance / 16 and turn
    its +y to point at the far end."""
    return [
        Part('rein', (0, 0, 0), (0, 0, 0), cubes=[C((-0.35, 0.0, -0.18), (0.7, 16.0, 0.36), 'c_rein', strap_painter('c_rein'))]),
        Part('trace', (0, 0, 0), (0, 0, 0), cubes=[C((-0.55, 0.0, -0.3), (1.1, 16.0, 0.6), 'c_trace',
                                                    strap_painter('c_trace', (78, 46, 26), (50, 30, 18), (116, 76, 46)))]),
    ]

# ================================================================== packing and painting

WRECK_ROLL = None


def build():
    global WRECK_ROLL
    shared_painters()
    SHARED.update({'c_ferrule2': iron_painter('c_ferrule2', 0.12), 'c_swingle_pin': iron_painter('c_swingle_pin'),
                   'l_wire': iron_painter('l_wire')})
    WRECK_ROLL = wreck_roll()
    models = [Model('wagon', TEX_W, TEX_H, wagon_parts()), Model('wagon_load', TEX_W, TEX_H, load_parts()),
              Model('wagon_wreck', TEX_W, TEX_H, wreck_parts()), Model('wagon_loot', TEX_W, TEX_H, loot_parts()),
              Model('wagon_straps', TEX_W, TEX_H, strap_parts())]
    every = [c for m in models for c in m.all_cubes()]
    names = [c.name for c in every]
    dup = sorted({n for n in names if names.count(n) > 1})
    if dup:
        raise SystemExit(f'wagon: cube names must be unique: {dup[:12]}')
    for m in models:
        pnames = [p.name for p in m.all_parts()]
        pdup = sorted({n for n in pnames if pnames.count(n) > 1})
        if pdup:
            raise SystemExit(f'wagon: {m.name}: part names must be unique: {pdup[:12]}')
    used = pack(every)
    return models, used


def pack(cubes, W=TEX_W, H=TEX_H, who='wagon'):
    """Skyline-pack every cube's box UV (the lowest fit first) into [W] x [H], each shared paint once: a cube whose
    [share] names another cube takes that cube's place; cubes sharing a painter by key take the first one's."""
    sky = [[0, 0, W]]
    by_name = {c.name: c for c in cubes}
    placed, todo = {}, []
    for c in cubes:
        if c.share == c.name:
            c.share = None
        if c.share and c.share in by_name:
            continue
        key = c.share or c.name
        if key in placed:
            continue
        placed[key] = c
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
            raise SystemExit(f'{who}: {c.name} ({fw}x{fh}) does not fit')
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

    def source(c):
        seen = set()
        while c.uv is None and c.name not in seen:
            seen.add(c.name)
            c = by_name[c.share] if c.share in by_name else placed[c.share]
        return c
    for c in cubes:
        if c.uv is None:
            src = source(c)
            if src.uv is None:
                raise SystemExit(f'{who}: {c.name} shares {c.share}, which has no place')
            if [math.ceil(s) for s in src.size] != [math.ceil(s) for s in c.size]:
                raise SystemExit(f'{who}: {c.name} {c.size} shares {src.name} {src.size}, another size')
            c.uv = src.uv
    used = max(c.uv[1] + c.footprint()[1] for c in cubes)
    if used > H:
        raise SystemExit(f'{who}: texture too small, need {used} rows of {H}')
    return used


def paint_of(c):
    return c.paint if c.paint is not None else SHARED.get(c.share)


def texture(models, glow=False):
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    done = set()
    for m in models:
        for c in m.all_cubes():
            if c.uv in done or (glow and c.name not in GLOW_CUBES):
                continue
            p = paint_of(c)
            if p is None:
                continue
            done.add(c.uv)
            for face, (x, y, w, h) in hc.rects2x(c).items():
                if w > 0 and h > 0 and c.shows(face):
                    p(img, face, x, y, w, h)
    return img

# ================================================================== posing and previews


def faces_of(model_or_parts, M=np.eye(3), T=np.zeros(3), turns=None, only=None, hide=()):
    """Every drawn face (A, B, D, rect, face, normal) of a model (or a list of parts), the parts in [only] if given,
    placed by M and T; [turns] gives parts an extra turn on their own pose (degrees about x, y, z), [hide] leaves
    parts out."""
    out = []
    turns = turns or {}
    parts = model_or_parts.parts if isinstance(model_or_parts, Model) else model_or_parts

    def go(part, M0, T0):
        if part.name in hide:
            return
        tx, ty, tz = turns.get(part.name, (0.0, 0.0, 0.0))
        Rm = M0 @ hc.rot_part(part.rot) @ hc.rotz(tz) @ hc.roty(ty) @ hc.rotx(tx)
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
            rects = hc.rects2x(c)
            for face, (A, B, D, n) in quads.items():
                if c.shows(face):
                    out.append((A, B, D, rects[face], face, Rm @ np.array(n, float)))
        for ch in part.children:
            go(ch, Rm, P)
    for p in parts:
        if only and p.name not in only:
            continue
        go(p, M, T)
    return out


def load_faces(load, goods, M=np.eye(3), T=np.zeros(3), atlas=None):
    """The load heaped in slot order, as the renderer heaps it: [goods] is a list of (piece, item or None)."""
    faces = []
    for i, (kind, item) in enumerate(goods):
        x, z, yaw, layer, under = SLOTS[i]
        rest = max((PIECES[goods[j][0]]['height'] for j in under if j < len(goods)), default=0.0)
        P = np.asarray(T, float) + M @ np.array([x, FLOOR_TOP - rest, z])
        L = M @ hc.roty(-yaw)
        faces += faces_of(load, L, P, only={kind})
        info = PIECES[kind]
        if item and atlas is not None and info['shows']:
            hs = info.get('size', 0.4) * 8
            for j, (ix, iz, iyaw, tilt_) in enumerate(info['shows']):
                u0 = atlas.extra(item)
                Q = P + L @ np.array([ix, -info['height'] - 0.25 - 0.15 * j, iz])
                R2 = L @ hc.roty(-iyaw) @ hc.rotx(tilt_)
                faces.append((Q + R2 @ np.array([-hs, 0, -hs]), Q + R2 @ np.array([hs, 0, -hs]), Q + R2 @ np.array([-hs, 0, hs]),
                              (u0, atlas.h0, 16, 16), 'top', R2 @ np.array([0, -1.0, 0])))
    return faces


def wagon_faces(models, goods=(), atlas=None, steer=0.0, spin=0.0, brake=0.0, sway=0.0, M=np.eye(3), T=np.zeros(3), hide=()):
    wagon, load = models[0], models[1]
    sf = spin * RR / RF
    turns = {'wheel_rr': (spin, 0, 0), 'wheel_rl': (spin, 0, 0), 'wheel_fr': (sf, 0, 0), 'wheel_fl': (sf, 0, 0),
             'front': (0, steer, 0), 'brake_lever': (brake, 0, 0), 'tilt': (0, 0, sway)}
    return faces_of(wagon, M, T, turns, hide=hide) + load_faces(load, goods, M, T, atlas)


WRECK_SHOWN = ['sack_torn', 'crate_broken', 'barrel_broken', 'flour_torn', 'coal_torn', 'basket_spilled', 'crate_broken', 'sack_torn']


def wreck_faces(models, lots=None):
    faces = faces_of(models[2])
    for (x, z, yaw), kind in zip(WRECK_LOTS, lots if lots is not None else WRECK_SHOWN):
        faces += faces_of(models[1], hc.roty(-yaw), np.array([x, 0.0, z]), only={kind})
    return faces


LOADED = [('sack', None), ('crate', 'carrot'), ('flour', None), ('barrel', None), ('fish', 'cod'), ('wool', None),
          ('crate', 'apple'), ('iron', None), ('sack', None), ('bottles', None), ('coal', None), ('honey', None),
          ('candles', None), ('flint', None), ('string', None), ('basket', 'bread')]


def render(faces, tex, yaw, pitch, scale, size, center, ground=True, bg=(58, 64, 80), glow=None, extent=(-12, 8, -18, 8)):
    """An orthographic view of [faces] (model space, y down), lit from the upper left front, over the ground (y 0);
    with [glow] (a texture), texels lit there are drawn full-bright."""
    W, H = size
    img = np.zeros((H, W, 3), float)
    img[...] = bg
    zbuf = np.full((H, W), 1e9)
    T = np.array(tex.convert('RGBA'), float)
    G = np.array(glow.convert('RGBA'), float) if glow is not None else None
    V = hc.rotx(-pitch) @ hc.roty(yaw)
    c = np.asarray(center, float)
    light = np.array([-0.45, -0.8, -0.4])
    light /= np.linalg.norm(light)

    def view(p):
        q = V @ (np.asarray(p, float) - c)
        return np.array([W / 2 + q[0] * scale, H / 2 + q[1] * scale, q[2]])
    ys, xs = np.mgrid[0:H, 0:W]
    quads = list(faces)
    if ground:
        g = []
        for i in range(extent[0], extent[1]):
            for j in range(extent[2], extent[3]):
                A = np.array([i * 8.0, 0.0, j * 8.0])
                g.append((A, A + [8, 0, 0], A + [0, 0, 8], None, ((i + j) % 2), np.array([0, -1.0, 0])))
        quads = g + quads
    for A, B, D, rect, face, n in quads:
        a, b, d = view(A), view(B), view(D)
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        corners_ = [a, b, d, b + e2]
        x0 = int(max(0, min(q[0] for q in corners_))); x1 = int(min(W - 1, max(q[0] for q in corners_) + 1))
        y0 = int(max(0, min(q[1] for q in corners_))); y1 = int(min(H - 1, max(q[1] for q in corners_) + 1))
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
            tvc, tuc = np.clip(tv, 0, T.shape[0] - 1), np.clip(tu, 0, T.shape[1] - 1)
            texel = T[tvc, tuc]
            nn = np.asarray(n, float)
            dl = float(np.dot(nn, -light))
            k = 0.55 + 0.5 * (dl if dl > 0 else -0.35 * dl) + 0.1 * max(0.0, float(-nn[1]))
            col = texel[..., :3] * min(1.15, k)
            if G is not None:
                gl = G[np.clip(tvc, 0, G.shape[0] - 1), np.clip(tuc, 0, G.shape[1] - 1)]
                col = np.where(gl[..., 3:4] > 8, gl[..., :3], col)
            alpha = texel[..., 3] / 255.0
            ok = inside & (texel[..., 3] > 8) & (depth < zbuf[y0:y1, x0:x1])
        region = img[y0:y1, x0:x1]
        region[...] = np.where(ok[..., None], col * alpha[..., None] + region * (1 - alpha[..., None]), region)
        zb = zbuf[y0:y1, x0:x1]
        zb[ok & (alpha > 0.6)] = depth[ok & (alpha > 0.6)]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


def sheet(views, cols, title, cell):
    rows = math.ceil(len(views) / cols)
    img = Image.new('RGB', (cell[0] * cols, 18 + cell[1] * rows), (30, 32, 40))
    for i, v in enumerate(views):
        img.paste(v, ((i % cols) * cell[0], 18 + (i // cols) * cell[1]))
    ImageDraw.Draw(img).text((6, 3), title, fill=(235, 235, 235))
    return img


def previews(models, tex, glow, out):
    os.makedirs(out, exist_ok=True)
    atlas = hc.Atlas(tex)
    paths = []
    empty = wagon_faces(models, (), atlas, spin=8.0)
    loaded = wagon_faces(models, LOADED, atlas, spin=8.0, steer=14.0, brake=BRAKE_ON - BRAKE_REST)
    tex2 = atlas.image()
    for name, faces in (('empty', empty), ('loaded', loaded)):
        views = [render(faces, tex2, yaw, pitch, 4.2, (460, 380), (0.0, -24.0, -26.0), glow=glow) for yaw, pitch in
                 ((215, -22), (35, -26), (130, -18), (-60, -30))]
        p = os.path.join(out, f'wagon_{name}.png')
        sheet(views, 2, f'wagon: {name}', (460, 380)).save(p)
        paths.append(p)
    side = render(loaded, tex2, -90, 0, 3.6, (560, 260), (0, -27, -26))
    top = render(wagon_faces(models, LOADED, atlas, hide={'tilt'}), tex2, 0, -89.9, 3.6, (560, 260), (0, -27, -26), ground=False)
    back = render(loaded, tex2, 180, -8, 4.0, (280, 260), (0, -27, 0))
    front = render(loaded, tex2, 0, -8, 4.0, (280, 260), (0, -27, -40))
    p = os.path.join(out, 'wagon_views.png')
    img = Image.new('RGB', (1120, 538), (30, 32, 40))
    img.paste(side, (0, 18)); img.paste(top, (560, 18)); img.paste(back, (0, 278)); img.paste(front, (280, 278))
    ImageDraw.Draw(img).text((6, 3), 'wagon: right side; from above (tilt off); from behind; from ahead', fill=(235, 235, 235))
    img.save(p)
    paths.append(p)
    wf = wreck_faces(models)
    views = [render(wf, tex2, yaw, pitch, 3.4, (460, 380), (-40.0, -10.0, -30.0), extent=(-14, 4, -20, 8)) for yaw, pitch in
             ((200, -30), (30, -34), (120, -24), (0, -89.9))]
    p = os.path.join(out, 'wagon_wreck.png')
    sheet(views, 2, f'wagon: the wreck (rolled {WRECK_ROLL:g} degrees), its eight lots round it', (460, 380)).save(p)
    paths.append(p)
    lf = faces_of(models[3])
    views = [render(lf, tex2, 210, -26, 9.0, (420, 300), (0, -6, 0)), render(lf, tex2, 40, -30, 9.0, (420, 300), (0, -6, 0))]
    p = os.path.join(out, 'wagon_loot.png')
    sheet(views, 2, "wagon: the band's loot pile", (420, 300)).save(p)
    paths.append(p)
    names = list(PIECE_ORDER) + list(BROKEN_ORDER)
    cells = [render(faces_of(models[1], only={k}), tex2, 210, -28, 11.0, (150, 130), (0, -3, 0)) for k in names]
    img = sheet(cells, 7, "wagon: the load's pieces, and the broken ones the wreck shows", (150, 150))
    d = ImageDraw.Draw(img)
    for i, k in enumerate(names):
        d.text(((i % 7) * 150 + 4, 18 + (i // 7) * 150 + 132), k, fill=(235, 235, 235))
    p = os.path.join(out, 'wagon_pieces.png')
    img.save(p)
    paths.append(p)
    tex.save(os.path.join(out, 'wagon_texture.png'))
    return paths

# ================================================================== output

KT_HEADER = '// generated by art/scripts/wagon.py, edit the script, not this file\n'


def kf(v):
    t = ('%.3f' % v).rstrip('0').rstrip('.')
    return (t if t not in ('-0', '') else '0') + 'f'


def kv(p):
    return ', '.join(kf(v) for v in p)


def kotlin_mesh(models):
    wagon, load, wreck, loot, straps = models

    def body(m):
        return m.kotlin().rstrip('\n')
    slots = '\n'.join(f'        Slot({kf(x)}, {kf(z)}, {kf(yaw)}, intArrayOf({", ".join(str(i) for i in under)})),'
                      for x, z, yaw, layer, under in SLOTS)
    pieces = []
    for name in PIECE_ORDER:
        info = PIECES[name]
        shows = ', '.join('floatArrayOf(' + kv(show) + ')' for show in info['shows'])
        pieces.append(f'        Piece("{name}", {kf(info["height"])}, listOf({shows}), {kf(info.get("size", 0.0))}),')
    pieces = '\n'.join(pieces)
    piece_for = ',\n'.join(f'        "{g}" to "{p}"' for g, p in PIECE_FOR.items())
    wreck_for = ',\n'.join(f'        "{a}" to "{b}"' for a, b in WRECK_PIECE_FOR.items())
    lots = '\n'.join(f'        floatArrayOf({kf(x)}, {kf(z)}, {kf(yaw)}),' for x, z, yaw in WRECK_LOTS)
    seats = '\n'.join(f'        "{k}" to Seat({kv(p)}, {kf(yaw)}),' for k, (p, yaw) in SEATS.items())
    imports = ['import net.minecraft.client.model.geom.PartPose', 'import net.minecraft.client.model.geom.builders.CubeListBuilder',
               'import net.minecraft.client.model.geom.builders.LayerDefinition', 'import net.minecraft.client.model.geom.builders.MeshDefinition',
               'import net.minecraft.core.Direction', 'import java.util.EnumSet']
    hinge = shaft_line(1)[0]
    return KT_HEADER + 'package io.github.vaspyyy.guildmark.client.caravan\n\n' + '\n'.join(imports) + f'''

/**
 * The caravan's wagon (art/scripts/wagon.py), box UV on the one [TEX_WIDTH] x [TEX_HEIGHT] texture
 * (textures/entity/caravan/wagon.png, shipped at {SCALE}x that and painted at the higher resolution; wagon_glow.png holds
 * the lantern's lit glass for a full-bright layer). Model space: pixels, y down, the wagon faces -z (toward the horse),
 * its right is -x; the origin is the ground under the middle of the back axle. See
 * guildmark-notes/living-world/caravan-art.md.
 */
object WagonMesh {{
    const val TEX_WIDTH = {TEX_W}
    const val TEX_HEIGHT = {TEX_H}

    /**
     * The wagon: "body" (the bed, box, bench, the footboard "dash", the brake's beam and rods, the water barrel, the
     * lantern's crook and the pennant's staff), "tailboard" (turns about x at its hinge: 0 let down, 90 shut),
     * "brake_lever" (turns about x: [BRAKE_REST] off, [BRAKE_ON] hard on), "tilt" (sways about z at the box's top),
     * "lantern" (swings at its hook), "pennant" with "pennant_1" and "pennant_2" (each turns about y at its root: ripple
     * them), the back wheels "wheel_rr" and "wheel_rl" (spin about x), and "front" (steers about y at the kingpin)
     * carrying "wheel_fr" and "wheel_fl" (spin about x, [REAR_RADIUS] / [FRONT_RADIUS] times as fast) and "shafts"
     * (turns about x at the hinges: + lifts the tips).
     */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun wagon(): LayerDefinition {{
{body(wagon)}
    }}

    /**
     * The load's pieces, each standing on the middle of its underside at its part's origin: the [PIECES], and the
     * wreck's broken ones ("sack_torn", "flour_torn", "coal_torn", "crate_broken", "barrel_broken", "basket_spilled").
     */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun load(): LayerDefinition {{
{body(load)}
    }}

    /**
     * The wreck, in the wagon's space where it stood: "wreck" (the wagon tipped over onto its right side, as the
     * wagon turned [WRECK_ROLL] degrees about z through [TIP_X], [TIP_Y]; the tilt half torn, its right wheels and
     * shafts gone, arrows in it; "w_wheel_rl" and "w_wheel_fl" may spin about x a while after it goes over),
     * "w_wheel_loose" (the back right wheel lying apart), "w_shafts_down" (the shafts torn off, lying ahead),
     * "w_strewn" (a few goods in the grass) and "w_lantern" (lying in the grass, out). Its lots are the load's broken
     * pieces at [WRECK_LOTS].
     */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun wreck(): LayerDefinition {{
{body(wreck)}
    }}

    /**
     * The band's loot pile: "loot_0" to "loot_{LOOT_PIECES - 1}" (marked crates, sacks and a barrel; "loot_4" sits on
     * "loot_0" and "loot_1"), "tarp" over them and "tarp_ropes" pegging it down.
     */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun loot(): LayerDefinition {{
{body(loot)}
    }}

    /** "rein" and "trace": straps 16 px along +y from the pivot, stretched between two points by the renderer. */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun straps(): LayerDefinition {{
{body(straps)}
    }}

    // ---- the numbers the renderer works from (px, model space) ----

    /** The back wheels' radius (the back axle's height over the ground) and the front ones'. */
    const val REAR_RADIUS = {kf(RR)}
    const val FRONT_RADIUS = {kf(RF)}
    /** Each wheel's middle out from the middle (x); the front axle this far ahead of the back one. */
    const val WHEEL_X = {kf(WHEEL_X)}
    const val WHEELBASE = {kf(WHEELBASE)}
    /** The kingpin the front turns about: (0, [KINGPIN_Y], -[WHEELBASE]). */
    const val KINGPIN_Y = {kf(KINGPIN_Y)}
    /** The left shaft's hinge (the right one's x is mirrored); the "shafts" part turns about x through it. */
    val SHAFT_HINGE = floatArrayOf({kv(hinge)})
    /** The horse's origin (the ground under the middle of its body) this far ahead of the kingpin, the shafts straight. */
    const val HORSE_AHEAD = {kf(HORSE_AHEAD)}
    /** The swingletree's middle (it turns with the front), its hooks +-[SWINGLE_HALF] along x: the traces' back ends. */
    val SWINGLE = floatArrayOf({kv(SWINGLE)})
    const val SWINGLE_HALF = {kf(SWINGLE_HALF)}
    /** The traces' front ends, on the horse's hames: x out (mirrored for the right), height, and z in the horse's space. */
    val HAMES = floatArrayOf({kv(HAMES)})
    /** The rein rings on top of the horse's saddle the reins run through: x out, height, z in the horse's space. */
    val TERRET = floatArrayOf({kv(TERRET)})
    /** The brake lever's pivot, its length (to the grip) and its lean about x, degrees: off and hard on. */
    val BRAKE_PIVOT = floatArrayOf({kv(BRAKE_PIVOT)})
    const val BRAKE_LENGTH = {kf(BRAKE_LEN - 1.2)}
    const val BRAKE_REST = {kf(BRAKE_REST)}
    const val BRAKE_ON = {kf(BRAKE_ON)}
    /** The lantern's hook (its part's pivot); the floor's top inside; the box's back; the tailboard's length. */
    val LANTERN_HOOK = floatArrayOf({kv(LANTERN_HOOK)})
    const val FLOOR_TOP = {kf(FLOOR_TOP)}
    const val BOX_BACK = {kf(BOX_BACK)}
    const val TAILBOARD_LENGTH = {kf(TAIL_LEN)}
    /** The wreck: the wagon turned this far about z, through the line ([TIP_X], [TIP_Y]) its right wheels stood on. */
    const val WRECK_ROLL = {kf(WRECK_ROLL)}
    const val TIP_X = {kf(TIP_AXIS[0])}
    const val TIP_Y = {kf(TIP_AXIS[1])}
    /** Where a dead horse lies in its cut harness by the wreck: x, z (px) and yaw (degrees). */
    val WRECK_HORSE = floatArrayOf({kv(WRECK_HORSE)})

    /** Where a townsperson sits: the seat's top under the middle of their hips, and their yaw from the wagon's (180: back). */
    class Seat(val x: Float, val y: Float, val z: Float, val yaw: Float)

    /** "driver" (the bench's right, by the brake), "bench" (its left) and "tail" (on the let-down tailboard, facing back). */
    val SEATS: Map<String, Seat> = mapOf(
{seats}
    )

    /** Where the driver stands to hand the load down (hand_down): on the tailboard, facing back. */
    val STAND = Seat({kv(STAND[0])}, {kf(STAND[1])})

    /** A place on the bed: [x] and [z] (px), turned [yaw] degrees, resting on the places [under] it (none: the floor). */
    class Slot(val x: Float, val z: Float, val yaw: Float, val under: IntArray)

    /** The 16 places in the order they fill: twelve on the floor (four rows of three, front to back), four on top. */
    val SLOTS: List<Slot> = listOf(
{slots}
    )

    /**
     * What heaps there: the [part] of [load], how tall it stands (px), where goods show on an open one ([shows]: x, z,
     * yaw and tilt each, px and degrees, in the piece's own frame) and how big they are (of a block).
     */
    class Piece(val part: String, val height: Float, val shows: List<FloatArray>, val size: Float)

    val PIECES: List<Piece> = listOf(
{pieces}
    )

    /** The piece each of the market's goods heaps as, by good id. */
    val PIECE_FOR: Map<String, String> = mapOf(
{piece_for},
    )

    /** The wreck's lots: x, z (px, in the wreck's space) and yaw, in the order they're filled. */
    val WRECK_LOTS: List<FloatArray> = listOf(
{lots}
    )

    /** The broken piece a lot shows for each whole one. */
    val WRECK_PIECE_FOR: Map<String, String> = mapOf(
{wreck_for},
    )
}}
'''


def report():
    lines = [f'wagon: back wheels {2 * RR:g} px across, front {2 * RF:g}, {2 * WHEEL_X:g} apart; wheelbase {WHEELBASE:g}; '
             f'bed {2 * (BOX_X - 1):g} x {BOX_BACK - BOX_FRONT - 1:g} inside, floor {-FLOOR_TOP:g} px up; tilt {-(ARCH_Y - ARCH_H):g} px up',
             f'  the horse {HORSE_AHEAD:g} px ahead of the kingpin (its origin at z {HORSE_Z:g}); wreck rolled {WRECK_ROLL:g}']
    for k, (p, yaw) in SEATS.items():
        lines.append(f'  seat {k}: {p} facing {yaw:g}')
    for i, (x, z, yaw, layer, under) in enumerate(SLOTS):
        lines.append(f'  place {i}: x {x:g} z {z:g} turned {yaw:g}' + (f', on top of {list(under)}' if layer else ''))
    return '\n'.join(lines)


def main(argv):
    assets, kotlin_dir, models_dir = argv[1], argv[2], argv[3]
    prev = argv[4] if len(argv) > 4 else None
    models, used = build()
    tex = texture(models)
    glow = texture(models, glow=True)
    tdir = os.path.join(assets, 'textures', 'entity', 'caravan')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, 'wagon.png'), optimize=True)
    glow.save(os.path.join(tdir, 'wagon_glow.png'), optimize=True)
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'WagonMesh.kt'), 'w') as f:
        f.write(kotlin_mesh(models))
    small = tex.resize((TEX_W, TEX_H), Image.LANCZOS)
    os.makedirs(models_dir, exist_ok=True)
    with open(os.path.join(models_dir, 'wagon.bbmodel'), 'w') as f:
        json.dump(Model('wagon', TEX_W, TEX_H, models[0].parts).bbmodel(small, 'wagon'), f)
    print(report())
    print(f'wagon: texture {TEX_W}x{TEX_H} ({used} rows packed), {sum(1 for m in models for _ in m.all_cubes())} cubes')
    if prev:
        for p in previews(models, tex, glow, prev):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
