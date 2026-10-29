"""
The Chapman's Cart (Trade, art stream X2): the carters' handcart (`handcart.py`) in a trader's own livery, with a pennant on a short pole at
the rear in the cart's saved colour, a canvas over the load that folds back onto the tail in three steps, and a wet look for that canvas
in rain. It is a variant of `handcart.py`'s cart, not an edit of it (the villages' pedlar's cart will be another): the same model, which
HandcartRenderer draws from HandcartMesh's layer, painted on a texture of its own; and a second layer, the dressing (the pole, the pennant
and the canvas), on that texture's free rows. Running this writes nothing of `handcart.py`'s and changes nothing it writes.

    python3 -B art/scripts/player_cart.py [assets dir [kotlin dir [model dir [preview dir]]]]

writes `textures/entity/trade/player_cart.png` (under [assets dir], default `src/main/resources/assets/guildmark`; box UV on the handcart's own
TEX_W x TEX_H, shipped at SCALE times that: the cart's cubes painted where HandcartMesh puts them, the dressing below them),
`player_cart_wet.png` (the same layout, only the canvas: the water beaded on it, drawn over the canvas as it gets wet),
`client/trade/PlayerCartMesh.kt` (GENERATED; under [kotlin dir], default `src/main/kotlin/io/github/vaspyyy/guildmark/client/trade`),
`player_cart.bbmodel` (under [model dir], default `art/models`; the cart and its dressing in one) and, with a [preview dir], the sheets and
GIFs. `-` stands for a default. A rerun gives the same files. Before writing it checks its own numbers (`check_contract`: the pictures, the
fold, the pennant's swing, the clearances below); after, what it wrote (`check_written`); it stops with the list if anything is off. Run it
from the repository's root.

THE VARIANT --------------------------------------------------------------------------------------------------------------------
  model name   `player_cart` (the bbmodel; the dressing's layer is PlayerCartArt.LAYER, `guildmark:player_cart#dressing`)
  texture      `player_cart.png`: HandcartRenderer draws the handcart's own model with it instead of `handcart.png` when the renderer's
               `texture(state)` says so (PlayerCartRenderer: `PlayerCartArt.Table.body`); the load is still drawn from `handcart.png`
  flag         PlayerCartArt.table() (THE ONE CALL): null, and the Chapman's Cart is the carters' handcart with D2's stand-in dressing

THE LIVERY: the bed's boards painted a deep green with a cream line round each panel, chipped at the edges to the oak under it; the
wheels red with black iron tyres and brass caps; brass on the posts' tops and the shafts' ferrules; the shafts' grips bound in leather; the
floor oiled oak; a gilt roundel on the tailboard. The carters' cart beside it is grey weathered oak: the two are told apart at a glance.

THE DRESSING (one layer, `dressing()`, in the handcart's model space: px, y down, the origin the middle of the axle, the shafts toward -z,
its left +x). Every part is a direct child of the root, its pivot where it turns:

  part              pivot (px)                    what it is, and how PlayerCartRenderer moves it
  ----------------  ----------------------------  ----------------------------------------------------------------------------------
  pole              (9.4, -6.0, 9.0)              an ash pole on two iron straps off the left rear post, a brass knob, two brass rings
                                                  for the pennant and its cord down to a cleat; still
  flag_0 .. flag_3  (9.4, -27.4, RIPPLES z)       the pennant, from the pole outward, white (tinted with the cart's colour); segment k
                                                  turns about the vertical at RIPPLES[k] (x, z), the next riding on it
  canvas_0          (0, -9.15, 12.3)              the tail strip, reaching 2.3 past the tailboard, its flap hanging over the board;
                                                  still but for the lift and the rock
  canvas_1 .. _3    (0, FOLDS y, FOLDS z)         the strips forward of it; strip k folds onto the stack about FOLDS[k - 1] (y, z):
                                                  rotX(-180 * folded), the strips after it riding on it

The canvas folds as a roll: the front strip flips back onto the one behind it, then those two onto the next, then all three onto the tail
strip, each flip over the top. A strip's hinge lies half the thickness of what it carries above the canvas's top face, so the flipped stack
lands on the strip behind it and nothing passes through anything at any step (`check_fold`). Folded, the four lie on the tail strip in the
order 0, 2, 3, 1 (strip 1's underside on top), 1.6 px deep.

THE NUMBERS PlayerCartRenderer reads (PlayerCartMesh):
  CANVAS_TOP -9.15    the canvas's top face over an empty bed: it lies on the posts' tops (y -8.75)
  CANVAS_FRONT -14.1  its front edge over the load; the tail strip's front edge is FOLDS[0].z, 5.7, behind every piece of the load
                      ahead of the tail places (the eighth place's piece reaches z 5.58 at most), so folded it lies over the tail places only
  CANVAS_LIFT 0.45    how far the top face is over the highest point under it: the sheet (0.4) and 0.05 clear
  TOPS                each of HandcartMesh.PIECES' highest point over its foot, handles, knots, a chair's back and the goods on an open
                      one included (its `height` is what stacks on it; this is what the canvas must clear)
  PIECE_REACH 3.85    how far any piece reaches toward the tail from its place (turned as its place turns it)
A piece whose back edge is under the folded canvas lifts it; one further forward lifts it as the canvas, unfolding, comes to it (from a
sixth of the cover short of it, eased, at least a tenth of the cover long: `weight`), so the folded canvas rests on what is at the tail,
not on top of the heap ahead of it, and is up before the strip that lands over the heap comes down.

THE WET LOOK: `player_cart_wet.png` holds the canvas only, darker where the water has soaked it, beaded with drops on both faces of each
strip (folded, the stack's top is strip 1's underside; over the load the undersides face down, out of sight), running down the flap in
streaks. Draw each canvas strip a second time, in the same pose, through `RenderTypes.entityTranslucent` with it, white with the alpha
of how wet the canvas is (PlayerCartClient: beading up over three seconds of rain on it, drying over a minute after).

Clearances it checks: the pole, its straps and the pennant against the wheels at every turn; the pennant at its widest flutter against the
pole and against the canvas lifted over the tallest heap the bed can carry; the canvas rocked as far as the load rocks against the pole;
every pair of strips at every step of the fold.
"""
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from modelkit import Model
import carrier_kit as K
import carrier_paint as P
import handcart as hc
import townsfolk_tools as tt
from townsfolk_tools import uv, mix

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/trade')
MODELS = os.path.join(ROOT, 'art/models')

NAME = 'player_cart'
TEX_W, TEX_H = hc.TEX_W, hc.TEX_H         # the handcart's own layout: the cart's cubes keep their places
SCALE = hc.SCALE
TEXTURE = 'textures/entity/trade/player_cart.png'
WET = 'textures/entity/trade/player_cart_wet.png'

# ================================================================== geometry (px, the handcart's model space)

POST_TOP = hc.SIDE_TOP - 1.0              # -8.75: the four corner posts' tops, which the canvas lies on
SHEET = 0.4                               # the canvas's thickness
CANVAS_X = 7.7                            # its half width: just over the posts' outer faces (7.6)
CANVAS_TOP = POST_TOP - SHEET             # -9.15
STRIPS = 4
CANVAS_FRONT = -14.1                      # 0.1 past the front board
CANVAS_TAIL = 12.3                        # 2.3 past the tailboard: the tail strip, which the others fold onto, lies behind every piece
                                          # of the load ahead of the tail places (their backs reach z 5.58 at most), so the folded canvas
                                          # rests on what is at the tail and not on the top of the heap
EDGES = tuple(round(CANVAS_TAIL - (CANVAS_TAIL - CANVAS_FRONT) * k / STRIPS, 4) for k in range(STRIPS + 1))
FLAP = (CANVAS_TAIL, CANVAS_TAIL + SHEET, -7.35)   # the flap at the tail: from z, to z, its hem's y (just over the tailboard's top)
CANVAS_LIFT = SHEET + 0.05                # the top face over the highest point under it
LEAD = 1.0 / 6.0                          # a piece ahead of the folded canvas lifts it from this much cover before the canvas reaches it
RAMP_MIN = 0.1                            # (half a strip's step: the strip coming down over it is upright then) and over this much at least


def hinge(k):
    """Strip k's fold (y, z): its back edge, half the thickness of what it carries (itself and the strips folded on it) over the top face."""
    return (CANVAS_TOP - SHEET * (STRIPS - 1 - k) / 2.0, EDGES[k])


FOLDS = [hinge(k) for k in range(1, STRIPS)]

# The pole: on two iron straps off the left rear post (x 6.4 to 7.6, z 8.4 to 9.6), out past what the canvas sweeps as it rocks over a
# heaped bed, and clear of the wheel (a felloe's corner is 10.37 px from the axle)
POLE_X = (9.0, 9.8)
POLE_Z = (8.6, 9.4)
POLE_Y = (-27.9, -6.0)
POLE_AXIS = (9.4, 9.0)
STRAPS_Y = ((-7.7, -7.2), (-6.6, -6.1))  # under the canvas's edge as it rocks down onto the post (to y -8.06), over the wheel's reach
KNOB_Y = (-28.9, -27.9)
RINGS_Y = ((-27.7, -27.4), (-23.4, -23.1))

# The pennant: a card 0.3 thick flying back from the pole, its hoist 4 px between the rings, narrowing to 2.2 at its tail, a swallowtail
# notch 2.2 deep; four segments, each turning at its hinge (RIPPLES: the first at the pole's axis). It flies over the highest the canvas
# is ever lifted (a chair's back on the top layer of the load: its top face at y -22, rocked 1.1 higher at its tail), so that streaming
# in toward the middle as the cart runs it passes over the canvas
FLAG_X = (POLE_AXIS[0] - 0.15, POLE_AXIS[0] + 0.15)
HOIST = (RINGS_Y[0][1], RINGS_Y[1][0])    # -27.4 .. -23.4
TAIL_Y = (-26.9, -24.7)
FLAG_Z = (9.65, 12.6, 15.6, 18.6, 21.4)
NOTCH = 2.2
RIPPLES = [(POLE_AXIS[0], POLE_AXIS[1])] + [(POLE_AXIS[0], z) for z in FLAG_Z[1:-1]]

# What the renderer does with them (PlayerCartRenderer and PlayerCartClient, D2's: read, not set, here; the checks pose with them)
FLUTTER_MAX = 0.55                        # radians: the pennant's swing at a run
FLUTTER_STAND = 0.2                       # and standing (0.1, eased toward from a run)
RIPPLE_LAG = 1.1
SLACK = 0.05
ROCK_MAX = 5.0                            # degrees the load (and the canvas) rocks at most, about the floor


def flag_edges(z):
    """The pennant's top and bottom edge (y) at [z]."""
    f = (z - FLAG_Z[0]) / (FLAG_Z[-1] - FLAG_Z[0])
    return HOIST[0] + (TAIL_Y[0] - HOIST[0]) * f, HOIST[1] + (TAIL_Y[1] - HOIST[1]) * f


def in_flag(y, z):
    """True where the pennant's cloth is (the swallowtail cut out of its tail)."""
    t, b = flag_edges(z)
    inside = (y >= t) & (y <= b) & (z >= FLAG_Z[0]) & (z <= FLAG_Z[-1])
    mid = (t + b) / 2.0
    half = (b - t) / 2.0
    into = (z - (FLAG_Z[-1] - NOTCH)) / NOTCH          # 0 where the notch begins, 1 at the tail
    cut = (into > 0) & (np.abs(y - mid) < half * into)
    return inside & ~cut


# ================================================================== palette

GREEN = (44, 90, 62); GREEN_D = (22, 52, 34); GREEN_L = (82, 134, 96)           # the bed's paint
RED = (162, 54, 36); RED_D = (100, 28, 18); RED_L = (204, 98, 64)               # the wheels'
LINE = (228, 204, 140)                                                           # the cream line round a panel
GILT = (220, 174, 78); GILT_D = (146, 104, 36); GILT_L = (252, 226, 146)
HONEY = (158, 112, 64); HONEY_D = (100, 64, 34); HONEY_L = (198, 154, 98)       # oak, oiled
SMOKE = (58, 52, 50); SMOKE_D = (30, 28, 28); SMOKE_L = (96, 88, 82)            # black paint, worn
GRIPS = (78, 48, 30); GRIPS_D = (44, 26, 16); GRIPS_L = (122, 82, 52)           # the leather on the grips
BRASS, BRASS_D, BRASS_L = P.BRASS, P.BRASS_D, P.BRASS_L
IRON, IRON_D, IRON_L = hc.IRON, hc.IRON_D, hc.IRON_L
CANVAS = (178, 160, 112); CANVAS_D = (126, 110, 74); CANVAS_L = (210, 196, 152)  # oiled duck, its top
UNDER = (206, 194, 162); UNDER_D = (160, 148, 118); UNDER_L = (228, 220, 194)    # its underside, unoiled
HEM = (140, 122, 84)
CORD = P.ROPE; CORD_D = P.ROPE_D; CORD_L = P.ROPE_L
CLOTH = P.WOOL; CLOTH_D = P.WOOL_D; CLOTH_L = P.WOOL_L                            # the pennant: tinted with the cart's colour
WET_TONE = (92, 80, 56)                                                          # soaked canvas
BEAD = (236, 242, 246); BEAD_D = (70, 62, 46)

# ================================================================== painting helpers


def painted(wood, base, dark, light, rng, grain=0.22):
    """Paint over [wood] (an RGBA array): the paint's colour, the wood's grain and seams showing through it a little."""
    lum = wood[..., :3].mean(axis=2)
    m = lum.mean() if lum.size else 1.0
    k = np.clip((lum - m) / max(m, 1.0), -1, 1)
    a = wood.copy()
    col = np.where(k[..., None] < 0, mix(base, dark, (-k * grain * 2.2)[..., None].clip(0, 1)),
                   mix(base, light, (k * grain * 1.6)[..., None].clip(0, 1)))
    a[..., :3] = col * (1 + rng.normal(0, 0.018, wood.shape[:2] + (1,)))
    return a


def chips(a, wood, rng, n, edge=0.22):
    """Paint knocked off near the edges (and a few elsewhere): the oak under it shows."""
    h, w = a.shape[:2]
    if min(w, h) < 3:
        return a
    ys, xs = np.mgrid[0:h, 0:w]
    for _ in range(n):
        if rng.random() < 0.8:
            side = rng.integers(4)
            cx = rng.uniform(0, w) if side < 2 else (rng.uniform(0, edge) * w if side == 2 else w - rng.uniform(0, edge) * w)
            cy = (rng.uniform(0, edge) * h if side == 0 else h - rng.uniform(0, edge) * h) if side < 2 else rng.uniform(0, h)
        else:
            cx, cy = rng.uniform(0, w), rng.uniform(0, h)
        r = rng.uniform(0.6, 1.5)
        on = np.hypot((xs + 0.5 - cx) / r, (ys + 0.5 - cy) / (r * rng.uniform(0.5, 1.0))) < 1
        a[..., :3] = np.where(on[..., None], wood[..., :3] * 0.92, a[..., :3])
    return a


def line_rect(a, x0, y0, x1, y1, col, alpha=0.9):
    """A one-texel line round the texel rect [x0, x1) x [y0, y1)."""
    h, w = a.shape[:2]
    x0, y0 = max(0, int(x0)), max(0, int(y0))
    x1, y1 = min(w, int(x1)), min(h, int(y1))
    if x1 - x0 < 2 or y1 - y0 < 2:
        return a
    m = np.zeros((h, w), bool)
    m[y0, x0:x1] = m[y1 - 1, x0:x1] = True
    m[y0:y1, x0] = m[y0:y1, x1 - 1] = True
    a[..., :3] = np.where(m[..., None], mix(a[..., :3], col, alpha), a[..., :3])
    return a


def brass(w, h, rng, wear=0.08):
    a = tt.metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=wear)
    if min(w, h) >= 3:
        U, V = uv(w, h)
        g = np.exp(-((U - 0.3) ** 2 + (V - 0.3) ** 2) * 12)
        a[..., :3] = mix(a[..., :3], (255, 242, 196), (g * 0.6)[..., None])
    return a


def roundel(a, cu, cv, r, rng):
    """The trader's gilt roundel at face coords (cu, cv), [r] texels: a gilt ring, a red ground, a gilt hub and spokes (a wheel)."""
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    dx, dy = xs + 0.5 - cu * w, ys + 0.5 - cv * h
    d = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)
    ring = (d <= r) & (d > r * 0.68)
    ground = d <= r * 0.68
    spokes = ground & (np.abs(np.sin(ang * 3.0)) < 0.34) | (d <= r * 0.26)
    g = tt.metal(w, h, GILT, GILT_D, GILT_L, rng, bevel=False, wear=0.04)
    a[..., :3] = np.where(ground[..., None], np.array(RED_D, float), a[..., :3])
    a[..., :3] = np.where((ring | spokes)[..., None], g[..., :3], a[..., :3])
    hl = (d <= r) & (dx + dy < -r * 0.6)
    a[..., :3] = np.where(hl[..., None], mix(a[..., :3], GILT_L, 0.35), a[..., :3])
    return a


def iron(w, h, rng, wear=0.05):
    return tt.metal(w, h, IRON, IRON_D, IRON_L, rng, bevel=False, wear=wear)


# ================================================================== the livery: a painter for each of the handcart's cubes

def side_painter(key, outer):
    """A side board: two planks painted green, a cream line round the panel each side of the axle's strap, black iron at the ends and
    over the axle, nailed; chipped where loads knock it and worn to the oak along its top; the inside oiled oak."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == outer:
            wood, _ = hc.boards(w, h, rng, 2, 'u', HONEY, HONEY_D, HONEY_L, knots=1)
            a = painted(wood, GREEN, GREEN_D, GREEN_L, rng)
            f_axle = hc.front_frac(face, w, h, hc.BED_FRONT, hc.BED_BACK, 0.0)
            ends = (U < 0.055) | (U > 0.945)
            strap = np.abs(U - f_axle) < 0.035
            # the line round each panel: inset a texel from the iron and from the top and bottom edges
            ax = int(round(f_axle * w))
            e0, e1 = int(math.ceil(0.055 * w)) + 1, int(math.floor(0.945 * w)) - 1
            s0, s1 = int(math.floor((f_axle - 0.035) * w)) - 1, int(math.ceil((f_axle + 0.035) * w)) + 1
            for x0, x1 in ((e0, s0), (s1, e1)):
                line_rect(a, x0, 1, x1, h - 1, LINE, 0.85)
            a = chips(a, wood, rng, 9)
            # worn to the oak along the top edge, where loads are dragged over
            top = (V < 0.13) & (rng.random((h, w)) < 0.55)
            a[..., :3] = np.where(top[..., None], mix(a[..., :3], wood[..., :3], 0.7), a[..., :3])
            a = hc.band(a, ends | strap, rng, SMOKE, SMOKE_D, IRON_L)
            for cu in (0.028, 0.972, f_axle):
                for cv in (0.25, 0.72):
                    hc.nail(a, cu, cv, 0.6, IRON_D, IRON_L)
            return tt.shade_edges(a, 0.14)
        if face in ('right', 'left'):
            a, _ = hc.boards(w, h, rng, 2, 'u', HONEY, HONEY_D, HONEY_L, knots=1)
            a[..., :3] = mix(a[..., :3], HONEY_L, (0.2 * np.clip(1 - V * 5, 0, 1))[..., None])
            return tt.shade_edges(a, 0.12)
        if face == 'top':
            a = tt.grain(w, h, 'v', GREEN, GREEN_D, GREEN_L, rng, streak=0.3)
            worn = rng.random((h, w)) < 0.45
            a[..., :3] = np.where(worn[..., None], mix(a[..., :3], HONEY_L, 0.6), a[..., :3])
            return a
        if face == 'bottom':
            return tt.grain(w, h, 'v', HONEY_D, (70, 46, 26), HONEY, rng, streak=0.3)
        a = tt.grain(w, h, None, GREEN, GREEN_D, GREEN_L, rng)
        return a
    return tt.painter(key, fn)


def end_painter(key, outer, mark=False):
    """The front board and the tailboard: painted green on their outer face ([outer]) with the cream line round it, a gilt roundel on the
    tailboard's ([mark]); oiled oak inside."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == outer:
            wood, _ = hc.boards(w, h, rng, 2, 'u', HONEY, HONEY_D, HONEY_L, knots=1)
            a = painted(wood, GREEN, GREEN_D, GREEN_L, rng)
            line_rect(a, 2, 1, w - 2, h - 1, LINE, 0.85)
            a = chips(a, wood, rng, 5)
            if mark:
                roundel(a, 0.5, 0.56, min(h * 0.45, 2.6), rng)
            corner = (U < 0.05) | (U > 0.95)
            a = hc.band(a, corner, rng, SMOKE, SMOKE_D, IRON_L)
            return tt.shade_edges(a, 0.14)
        if face in ('front', 'back'):
            a, _ = hc.boards(w, h, rng, 2, 'u', HONEY, HONEY_D, HONEY_L, knots=1)
            return tt.shade_edges(a, 0.12)
        if face == 'top':
            a = tt.grain(w, h, 'u', GREEN, GREEN_D, GREEN_L, rng, streak=0.3)
            worn = rng.random((h, w)) < 0.4
            a[..., :3] = np.where(worn[..., None], mix(a[..., :3], HONEY_L, 0.6), a[..., :3])
            return a
        if face == 'bottom':
            return tt.grain(w, h, 'u', HONEY_D, (70, 46, 26), HONEY, rng, streak=0.3)
        return tt.grain(w, h, None, GREEN, GREEN_D, GREEN_L, rng)
    return tt.painter(key, fn)


def floor_painter(key):
    """The floor: five oak planks, oiled and kept, nailed to the bearers and the poles; a pale scuff down the middle where the load slides."""
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            a, _ = hc.boards(w, h, rng, 5, 'v', HONEY, HONEY_D, HONEY_L, knots=2)
            a = hc.weather(a, rng, 0.06, (150, 132, 108))
            U, V = uv(w, h)
            if face == 'top':
                scuff = np.exp(-((U - 0.5) / 0.28) ** 2) * (0.5 + 0.5 * np.sin(V * 9.0 + rng.uniform(0, 6)) ** 2)
                a[..., :3] = mix(a[..., :3], HONEY_L, (0.14 * scuff)[..., None])
                for z in (-11.25, 0.0, 6.75):
                    vv = (hc.BED_BACK - z) / (hc.BED_BACK - hc.BED_FRONT)
                    for i in range(5):
                        if z == 0.0 and i not in (0, 4):
                            continue
                        hc.nail(a, (i + 0.5) / 5, vv, 0.7)
            return tt.shade_edges(a, 0.12)
        return tt.grain(w, h, 'u', HONEY_D, (66, 42, 24), HONEY, rng, streak=0.3)
    return tt.painter(key, fn)


def post_painter(key):
    """A corner post: green, a brass cap on its top and a brass band round it under the cap."""
    def fn(face, w, h, rng):
        if face == 'top':
            return brass(w, h, rng)
        if face == 'bottom':
            return tt.grain(w, h, None, HONEY_D, (60, 40, 24), HONEY, rng)
        wood = tt.grain(w, h, 'v', HONEY, HONEY_D, HONEY_L, rng, streak=0.4)
        a = painted(wood, GREEN, GREEN_D, GREEN_L, rng)
        a = chips(a, wood, rng, 2, 0.3)
        U, V = uv(w, h)
        band = V < 0.14
        b = brass(w, h, rng)
        a[..., :3] = np.where(band[..., None], b[..., :3], a[..., :3])
        return tt.shade_edges(a, 0.15)
    return tt.painter(key, fn)


def rail_painter(key, z0, z1):
    """An ash pole, the bed's beam and the shaft in one, varnished: the grip bound in leather where the hands take it, brass ferrules where it
    leaves the bed, a brass cap on its tip."""
    def fn(face, w, h, rng):
        ax, _ = hc.along_z(face)
        if ax is None:
            if face == 'front':                       # the tip's end
                return brass(w, h, rng)
            return tt.grain(w, h, None, hc.ASH, hc.ASH_D, hc.ASH_L, rng) * np.array([0.92, 0.92, 0.92, 1.0])
        a = tt.grain(w, h, ax, (182, 140, 88), hc.ASH_D, hc.ASH_L, rng, streak=0.5, knots=1)
        a[..., :3] = mix(a[..., :3], (196, 150, 86), 0.18)                         # the varnish's warmth
        U, V = uv(w, h)
        f = U if ax == 'u' else V
        g = V if ax == 'u' else U
        tip = hc.front_frac(face, w, h, z0, z1, hc.RAIL_FRONT)
        hold = hc.front_frac(face, w, h, z0, z1, hc.GRIP_Z - 1.6)
        lo, hi = sorted((tip, hold))
        lo += 0.6 / (z1 - z0)                                                        # the cap's width short of the tip
        wrap = (f >= lo) & (f <= hi)
        n = (z1 - z0) * (hi - lo) / 0.5                                              # a turn of leather every half px
        turns = ((f - lo) / max(hi - lo, 1e-6) * n + g * 0.5) % 1.0
        leather = mix(GRIPS_D, GRIPS_L, (0.35 + 0.65 * np.sin(turns * math.pi) ** 2)[..., None])
        a[..., :3] = np.where(wrap[..., None], leather, a[..., :3])
        for zf in (hc.BED_FRONT - 0.6, hc.RAIL_FRONT + 0.3):
            c = hc.front_frac(face, w, h, z0, z1, zf)
            ring = np.abs(f - c) < 0.3 / (z1 - z0) * (2.0 if zf < hc.RAIL_FRONT + 1 else 1.0)
            b = brass(w, h, rng)
            a[..., :3] = np.where(ring[..., None], b[..., :3], a[..., :3])
        return tt.shade_edges(a, 0.2)
    return tt.painter(key, fn)


def hanger_painter(key):
    """The block under each pole the axle runs through: painted black, an iron strap round it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('front', 'back', 'right', 'left'):
            wood = tt.grain(w, h, 'v', hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng, streak=0.35)
            a = painted(wood, SMOKE, SMOKE_D, SMOKE_L, rng)
            a = hc.band(a, (V > 0.62) | (np.abs(U - 0.5) < 0.12) & (V > 0.2), rng)
            hc.nail(a, 0.5, 0.32, 0.55)
            return tt.shade_edges(a, 0.18)
        if face == 'bottom':
            return tt.metal(w, h, IRON, IRON_D, IRON_L, rng)
        return tt.grain(w, h, None, SMOKE, SMOKE_D, SMOKE_L, rng)
    return tt.painter(key, fn)


def bar_painter(key, base=RED, dark=RED_D, light=RED_L):
    """A cross piece (the bar bracing the shafts, the bearers under the floor): painted, iron at its ends."""
    def fn(face, w, h, rng):
        if face in ('right', 'left'):
            return tt.grain(w, h, None, dark, (60, 30, 20), base, rng)
        wood = tt.grain(w, h, 'u', HONEY, HONEY_D, HONEY_L, rng, streak=0.45)
        a = painted(wood, base, dark, light, rng)
        U, V = uv(w, h)
        a = hc.band(a, (U < 0.07) | (U > 0.93), rng)
        return tt.shade_edges(a, 0.2)
    return tt.painter(key, fn)


def rim_painter(key):
    """A felloe (every wheel's twelve share it): the black iron tyre on its tread, nailed; red on the wheel's faces with a cream line round
    the wheel inside the tyre."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = tt.metal(w, h, hc.TYRE, hc.TYRE_D, hc.TYRE_L, rng, wear=0.1)
            worn = np.exp(-((V - 0.5) / 0.3) ** 2)
            a[..., :3] = mix(a[..., :3], hc.TYRE_L, (0.3 * worn)[..., None])
            for cv in (0.25, 0.75):
                hc.nail(a, 0.5, cv, 0.55, hc.TYRE_D, hc.TYRE_L)
            return a
        wood = tt.grain(w, h, 'u', hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng, streak=0.4)
        a = painted(wood, RED, RED_D, RED_L, rng)
        if face in ('right', 'left'):
            a = hc.band(a, V < 0.24, rng, hc.TYRE, hc.TYRE_D, hc.TYRE_L)
            ln = (V > 0.4) & (V < 0.4 + 1.2 / h)
            a[..., :3] = np.where(ln[..., None], mix(a[..., :3], LINE, 0.8), a[..., :3])
            a[..., :3] = np.where((V > 0.86)[..., None], mix(a[..., :3], RED_D, 0.35), a[..., :3])
            hc.nail(a, 0.5, 0.68, 0.5, IRON_D, IRON_L)
            return a
        if face == 'bottom':
            return a * np.array([0.85, 0.85, 0.85, 1.0])
        return hc.band(a, V < 0.3, rng, hc.TYRE, hc.TYRE_D, hc.TYRE_L)
    return tt.painter(key, fn)


def spoke_painter(key):
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            return tt.grain(w, h, None, RED_D, (60, 22, 14), RED, rng)
        wood = tt.grain(w, h, 'v', hc.SPOKE, hc.SPOKE_D, hc.SPOKE_L, rng, streak=0.45)
        a = painted(wood, RED, RED_D, RED_L, rng)
        U, V = uv(w, h)
        a[..., :3] *= (0.82 + 0.3 * np.sin(np.pi * U))[..., None]
        return a
    return tt.painter(key, fn)


def hub_painter(key):
    """The nave: red, a brass band at each end of its round, its face a brass ring round the dark bore."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        b = brass(w, h, rng)
        if face in ('right', 'left'):
            wood = tt.grain(w, h, None, hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng)
            a = painted(wood, RED, RED_D, RED_L, rng)
            d = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = np.where((d > 0.36)[..., None], b[..., :3], a[..., :3])
            a[..., :3] = np.where((d < 0.16)[..., None], np.array(IRON_D, float), a[..., :3])
            return a
        wood = tt.grain(w, h, 'u', hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng, streak=0.35)
        a = painted(wood, RED, RED_D, RED_L, rng)
        a[..., :3] = np.where(((U < 0.2) | (U > 0.8))[..., None], b[..., :3], a[..., :3])
        return a
    return tt.painter(key, fn)


def cap_painter(key):
    """The brass cap on the axle's end, the iron linchpin through it."""
    def fn(face, w, h, rng):
        a = brass(w, h, rng)
        U, V = uv(w, h)
        if face in ('right', 'left'):
            pin = np.abs(U - 0.5) < 0.14
            a[..., :3] = np.where(pin[..., None], np.array(IRON, float), a[..., :3])
        return a
    return tt.painter(key, fn)


def axle_painter(key):
    def fn(face, w, h, rng):
        a = tt.metal(w, h, IRON, IRON_D, IRON_L, rng, wear=0.08)
        grease = rng.random((h, w)) < 0.12
        a[..., :3] = np.where(grease[..., None], mix(a[..., :3], (24, 22, 20), 0.6), a[..., :3])
        return a
    return tt.painter(key, fn)


def livery():
    """{the handcart's cube (or shared picture) name: its painter in the Chapman's livery}."""
    L = {
        'c_floor': floor_painter('pc_floor'),
        'c_side_r': side_painter('pc_side_r', 'right'), 'c_side_l': side_painter('pc_side_l', 'left'),
        'c_rail_r': rail_painter('pc_rail_r', hc.RAIL_FRONT, hc.RAIL_BACK), 'c_rail_l': rail_painter('pc_rail_l', hc.RAIL_FRONT, hc.RAIL_BACK),
        'c_hanger_r': hanger_painter('pc_hanger_r'), 'c_hanger_l': hanger_painter('pc_hanger_l'),
        'c_front': end_painter('pc_front', 'front'), 'c_tail': end_painter('pc_tail', 'back', mark=True),
        'c_stretcher': bar_painter('pc_stretcher'),
        'c_bearer0': bar_painter('pc_bearer0', SMOKE, SMOKE_D, SMOKE_L), 'c_bearer1': bar_painter('pc_bearer1', SMOKE, SMOKE_D, SMOKE_L),
        'c_axle': axle_painter('pc_axle'),
        'c_rim': rim_painter('pc_rim'), 'c_spoke': spoke_painter('pc_spoke'), 'c_nave': hub_painter('pc_nave'), 'c_cap': cap_painter('pc_cap'),
    }
    for n in ('r0', 'r1', 'l0', 'l1'):
        L[f'c_post_{n}'] = post_painter(f'pc_post_{n}')
    return L


# ================================================================== the dressing's painters

def pole_painter(key, part):
    """The pole (varnished ash, its grain up it), its brass knob and rings, the iron straps, the cord and its cleat."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if part == 'pole':
            if face in ('top', 'bottom'):
                return tt.grain(w, h, None, hc.ASH, hc.ASH_D, hc.ASH_L, rng)
            a = tt.grain(w, h, 'v', (182, 140, 88), hc.ASH_D, hc.ASH_L, rng, streak=0.45)
            a[..., :3] = mix(a[..., :3], (196, 150, 86), 0.18)
            a[..., :3] *= (0.86 + 0.24 * np.sin(np.pi * U))[..., None]             # turned round: lit down its middle
            return a
        if part in ('knob', 'ring'):
            return brass(w, h, rng)
        if part == 'strap':
            a = iron(w, h, rng)
            if face in ('top', 'bottom') and min(w, h) >= 4:
                hc.nail(a, 0.12, 0.5, 0.5, IRON_D, IRON_L)
            return a
        if part == 'cord':
            return _rope(face, w, h, rng)
        if part == 'cleat':
            a = tt.grain(w, h, 'u' if face in ('front', 'back', 'top', 'bottom') else None, hc.ASH_D, (90, 64, 40), hc.ASH, rng)
            return tt.shade_edges(a, 0.2)
        raise KeyError(part)
    return tt.painter(key, fn)


def _rope(face, w, h, rng, along='v'):
    a = tt.noise_fill(w, h, CORD, rng, 0.05)
    U, V = uv(w, h)
    if face in ('top', 'bottom'):
        a[..., :3] = mix(a[..., :3], CORD_D, 0.4)
        return a
    s, t = V * h, U * w
    ph = ((s + t * 0.9) / 3.0) % 1.0
    a[..., :3] = mix(CORD_D, CORD_L, (0.15 + 0.85 * np.sin(ph * math.pi) ** 1.5)[..., None])
    return a


def flag_painter(key, z0, z1, y0, y1):
    """A pennant segment from z0 to z1 (y0 to y1 its cube's height): white cloth with a hemmed edge, the sleeve at the hoist, a wheel in the
    middle of the first segment, and the cloth cut to the pennant's shape (alpha) on both its faces."""
    def fn(face, w, h, rng):
        if face not in ('right', 'left'):
            return None
        U, V = uv(w, h)
        z = (z1 - U * (z1 - z0)) if face == 'right' else (z0 + U * (z1 - z0))
        y = y0 + V * (y1 - y0)
        a = tt.noise_fill(w, h, CLOTH_L, rng, 0.02)
        a = P._twill(a, w, h, rng, 0.03)
        t, b = flag_edges(z)
        mid = (t + b) / 2.0
        # a soft fold shadow running the length, and the cloth's ripple shading across it
        a[..., :3] *= (1.0 - 0.06 * np.cos((y - mid) / np.maximum(0.5, b - t) * math.pi * 2))[..., None]
        a[..., :3] *= (1.0 + 0.05 * np.sin(z * 2.1))[..., None]
        hem = (y - t < 0.32) | (b - y < 0.32)
        a[..., :3] = np.where(hem[..., None], mix(a[..., :3], CLOTH_D, 0.45), a[..., :3])
        sleeve = z < FLAG_Z[0] + 0.55
        a[..., :3] = np.where(sleeve[..., None], mix(a[..., :3], CLOTH_D, 0.55), a[..., :3])
        # the wheel: the Chapman's own sign, as on the tailboard (darker cloth, so the colour comes up deeper)
        cz, cy, r = (FLAG_Z[0] + FLAG_Z[1]) / 2.0 + 0.2, (HOIST[0] + HOIST[1]) / 2.0, 1.25
        d = np.hypot(z - cz, y - cy)
        ang = np.arctan2(y - cy, z - cz)
        wheel = ((d <= r) & (d > r * 0.7)) | ((d <= r * 0.7) & (np.abs(np.sin(ang * 3.0)) < 0.3)) | (d <= r * 0.28)
        a[..., :3] = np.where(wheel[..., None], mix(a[..., :3], CLOTH_D, 0.8), a[..., :3])
        a[..., 3] = np.where(in_flag(y, z), 255, 0)
        return a
    return tt.painter(key, fn, light=False)


def canvas_painter(key, k):
    """Strip [k] of the canvas: oiled duck on top (khaki, a fine twill, darker where it creases at its edges), unoiled and paler underneath,
    a rolled hem down both sides with brass eyelets in it, and the seam where it meets the next strip."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        top = face == 'top'
        if face in ('top', 'bottom'):
            base, dark, light = (CANVAS, CANVAS_D, CANVAS_L) if top else (UNDER, UNDER_D, UNDER_L)
            a = tt.noise_fill(w, h, base, rng, 0.03)
            a = P._twill(a, w, h, rng, 0.05)
            # blotches of the oil, a stain or two
            m = rng.normal(0, 1, (h // 3 + 2, w // 3 + 2))
            m = np.kron(m, np.ones((3, 3)))[:h, :w]
            a[..., :3] = mix(a[..., :3], dark if top else light, (np.clip(m, 0, 2) * 0.08)[..., None])
            # creases where it folds: dark at the strip's ends, a lighter ridge just in from them
            ends = np.minimum(V, 1 - V) * h
            a[..., :3] = np.where((ends < 0.9)[..., None], mix(a[..., :3], HEM, 0.55), a[..., :3])
            a[..., :3] = np.where(((ends >= 0.9) & (ends < 1.9))[..., None], mix(a[..., :3], light, 0.25), a[..., :3])
            # the hems down its sides, the eyelets in them: two to a strip, at its thirds
            side = np.minimum(U, 1 - U) * w
            a[..., :3] = np.where((side < 1.2)[..., None], mix(a[..., :3], HEM, 0.5), a[..., :3])
            stitch = (np.abs(side - 1.6) < 0.5) & ((np.arange(h)[:, None] + np.zeros((1, w))) % 2 == 0)
            a[..., :3] = np.where(stitch[..., None], mix(a[..., :3], dark, 0.35), a[..., :3])
            for x in (0, w - 1):
                for cv in (0.33, 0.67):
                    y = min(h - 2, int(cv * h))
                    a[y, x, :3] = BRASS_L
                    a[y + 1, x, :3] = BRASS_D
            return a
        a = tt.noise_fill(w, h, HEM, rng, 0.04)
        return a
    return tt.painter(key, fn)


def flap_painter(key):
    """The flap over the tailboard: the canvas turned down, its hem at the bottom, two cords hanging from it to tie it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('back', 'front'):
            base = CANVAS if face == 'back' else UNDER
            a = tt.noise_fill(w, h, base, rng, 0.03)
            a = P._twill(a, w, h, rng, 0.05)
            a[..., :3] = np.where((V < 1.2 / h)[..., None], mix(a[..., :3], CANVAS_L, 0.3), a[..., :3])     # the fold over the board
            hem = V > 1 - 1.4 / h
            a[..., :3] = np.where(hem[..., None], mix(a[..., :3], HEM, 0.55), a[..., :3])
            if face == 'back':
                for cu in (0.2, 0.8):
                    cord = (np.abs(U - cu) < 0.6 / w) & (V > 0.25)
                    a[..., :3] = np.where(cord[..., None], mix(CORD_D, CORD_L, 0.4 + 0.3 * np.sin(V * h * 2.0)[..., None]), a[..., :3])
                    knot = (np.abs(U - cu) < 1.2 / w) & (np.abs(V - 0.25) < 0.9 / h)
                    a[..., :3] = np.where(knot[..., None], np.array(CORD_D, float), a[..., :3])
                for cu in (0.08, 0.5, 0.92):
                    hc.nail(a, cu, 0.86, 0.5, BRASS_D, BRASS_L)
            return a
        if face == 'top':
            a = tt.noise_fill(w, h, CANVAS, rng, 0.03)
            return P._twill(a, w, h, rng, 0.05)
        return tt.noise_fill(w, h, HEM, rng, 0.04)
    return tt.painter(key, fn)


# ================================================================== the wet look: the canvas only, on the same layout

def wet_painter(key, k, flap=False):
    """The water on strip [k] (or the flap): soaked darker and beaded with drops of every size on both of a strip's faces (the underside is
    the stack's top once the canvas is folded back), a damp edge on the flap's inner face, running down the flap in streaks and hanging in
    drops from its hem."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = np.zeros((h, w, 4), float)
        a[..., :3] = WET_TONE
        sky = face == 'top' or (face == 'bottom' and not flap)
        if sky or (flap and face == 'back'):
            a[..., 3] = 120 + rng.normal(0, 14, (h, w)).clip(-30, 30)
        elif flap and face in ('bottom', 'front'):
            edge = np.minimum(np.minimum(U, 1 - U) * w, np.minimum(V, 1 - V) * h)
            a[..., 3] = np.clip(110 - edge * 40, 0, 110)
        else:
            a[..., 3] = 150
        ys, xs = np.mgrid[0:h, 0:w]
        if sky:
            # beads: a dark rim on their lower right, a glint on their upper left; most one texel, a few two
            for _ in range(int(w * h * 0.04)):
                x, y = int(rng.integers(0, w)), int(rng.integers(0, h))
                big = rng.random() < 0.25
                if big and x + 1 < w and y + 1 < h:
                    a[y:y + 2, x:x + 2, :3] = mix(WET_TONE, BEAD, 0.35)
                    a[y:y + 2, x:x + 2, 3] = 200
                    a[y + 1, x + 1, :3] = BEAD_D
                    a[y + 1, x + 1, 3] = 220
                    a[y, x, :3] = BEAD
                    a[y, x, 3] = 245
                else:
                    a[y, x, :3] = BEAD
                    a[y, x, 3] = 235
                    if x + 1 < w and y + 1 < h:
                        a[y + 1, x + 1, :3] = BEAD_D
                        a[y + 1, x + 1, 3] = 200
        if flap and face == 'back':
            for _ in range(max(3, w // 4)):
                cx = rng.uniform(0, w)
                run = (np.abs(xs + 0.5 - cx - np.sin(ys * 0.9 + cx) * 0.4) < 0.55) & (ys >= rng.uniform(0, h * 0.4))
                a[..., :3] = np.where(run[..., None], mix(a[..., :3], BEAD, 0.55), a[..., :3])
                a[..., 3] = np.where(run, 210, a[..., 3])
            drops = (ys >= h - 2) & ((xs % 5) == rng.integers(5))
            a[..., :3] = np.where(drops[..., None], np.array(BEAD, float), a[..., :3])
            a[..., 3] = np.where(drops, 240, a[..., 3])
        return a
    return tt.painter(key, fn, light=False)


# ================================================================== building it

def bx(x0, y0, z0, x1, y1, z1, name, paint=None, faces=None):
    return K.bx(x0, y0, z0, x1, y1, z1, name, paint, faces=faces)


def dressing():
    """The dressing's model: the pole, the pennant's four segments, the canvas's four strips (the tail one with its flap)."""
    px0, px1 = POLE_X
    pz0, pz1 = POLE_Z
    ax, az = POLE_AXIS
    pole = [bx(px0, POLE_Y[0], pz0, px1, POLE_Y[1], pz1, 'c_pole', pole_painter('pc_pole', 'pole')),
            bx(px0 + 0.05, KNOB_Y[0], pz0 + 0.05, px1 - 0.05, KNOB_Y[1], pz1 - 0.05, 'c_pole_knob', pole_painter('pc_pole_knob', 'knob'))]
    for i, (y0, y1) in enumerate(RINGS_Y):
        pole.append(bx(px0 - 0.1, y0, pz0 - 0.1, px1 + 0.1, y1, pz1 + 0.1, f'c_pole_ring{i}', pole_painter(f'pc_pole_ring{i}', 'ring')))
    for i, (y0, y1) in enumerate(STRAPS_Y):
        pole.append(bx(7.5, y0, pz0 - 0.1, px1 + 0.1, y1, pz1 + 0.1, f'c_pole_strap{i}', pole_painter(f'pc_pole_strap{i}', 'strap')))
    # the cord from the lower ring down the pole's back to a cleat between the straps
    pole.append(bx(ax - 0.1, RINGS_Y[1][1], pz1, ax + 0.1, -9.6, pz1 + 0.2, 'c_pole_cord', pole_painter('pc_pole_cord', 'cord')))
    pole.append(bx(ax - 0.35, -9.6, pz1, ax + 0.35, -9.0, pz1 + 0.35, 'c_pole_cleat', pole_painter('pc_pole_cleat', 'cleat')))
    parts = [K.part('pole', (ax, POLE_Y[1], az), pole)]
    for k in range(len(FLAG_Z) - 1):
        z0, z1 = FLAG_Z[k], FLAG_Z[k + 1]
        t, _ = flag_edges(z0)
        _, b = flag_edges(z0)
        c = bx(FLAG_X[0], t, z0, FLAG_X[1], b, z1, f'c_flag_{k}', flag_painter(f'pc_flag_{k}', z0, z1, t, b), faces=('right', 'left'))
        parts.append(K.part(f'flag_{k}', (RIPPLES[k][0], HOIST[0], RIPPLES[k][1]), [c]))
    for k in range(STRIPS):
        z0, z1 = EDGES[k + 1], EDGES[k]
        cubes = [bx(-CANVAS_X, CANVAS_TOP, z0, CANVAS_X, CANVAS_TOP + SHEET, z1, f'c_canvas_{k}', canvas_painter(f'pc_canvas_{k}', k))]
        if k == 0:
            cubes.append(bx(-CANVAS_X, CANVAS_TOP, FLAP[0], CANVAS_X, FLAP[2], FLAP[1], 'c_canvas_flap', flap_painter('pc_canvas_flap')))
        pivot = (0.0, CANVAS_TOP, EDGES[0]) if k == 0 else (0.0, FOLDS[k - 1][0], FOLDS[k - 1][1])
        parts.append(K.part(f'canvas_{k}', pivot, cubes))
    return Model(NAME + '_dressing', TEX_W, TEX_H, parts)


def build():
    """(the handcart's own model, its load, the dressing): the handcart's packed as handcart.py packs it, the dressing packed into the rows
    below the handcart's last one."""
    cart, load = hc.build()
    used = max(c.uv[1] + c.footprint()[1] for c in list(cart.all_cubes()) + list(load.all_cubes()))
    dress = dressing()
    rows = K.pack(list(dress.all_cubes()), TEX_W, TEX_H - used, who=NAME)
    for c in dress.all_cubes():
        c.uv = (c.uv[0], c.uv[1] + used)
    return cart, load, dress, used, used + rows


def paint_cart(img, cart):
    """The handcart's own cubes in the livery, where HandcartMesh puts them."""
    L = livery()
    done = set()
    for c in cart.all_cubes():
        key = c.share or c.name
        if key in done:
            continue
        done.add(key)
        p = L[key]
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0:
                p(img, face, x, y, w, h)
    return img


def texture(cart, dress):
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    paint_cart(img, cart)
    for c in dress.all_cubes():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0 and c.shows(face):
                c.paint(img, face, x, y, w, h)
    return img


def wet_texture(dress):
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    for p in dress.parts:
        if not p.name.startswith('canvas_'):
            continue
        for c in p.cubes:
            flap = c.name == 'c_canvas_flap'
            pt = wet_painter('pw_' + c.name, int(p.name[-1]), flap)
            for face, (x, y, w, h) in K.rects(c, SCALE).items():
                if w > 0 and h > 0:
                    pt(img, face, x, y, w, h)
    return img


# ================================================================== posing it, as PlayerCartRenderer does

def ease(t):
    return K.smooth(t)


def about(R, p):
    """The affine map turning by R about the point p: (A, t), x -> A x + t."""
    p = np.asarray(p, float)
    return R, p - R @ p


def then(a, b):
    """Map a after map b (b first): x -> a(b(x))."""
    return a[0] @ b[0], a[0] @ b[1] + a[1]


IDENTITY = (np.eye(3), np.zeros(3))


def tops():
    """Each of HandcartMesh.PIECES' highest point over its foot (px): its cubes' highest, or the goods shown on an open one (laid 0.25 to
    0.55 over its top, tipped up to 10 degrees: 1.3 over it), whichever is higher."""
    _, load = hc.build()
    out = []
    for kind in hc.KT_ORDER:
        p = next(q for q in load.parts if q.name == kind)
        ys = []

        def go(q, off):
            o = np.asarray(off, float) + np.asarray(q.pivot, float)
            for c in q.cubes:
                ys.append(o[1] + c.origin[1])
            for ch in q.children:
                go(ch, o)
        go(p, (0, 0, 0))
        info = hc.ALL_PIECES[kind]
        out.append(round(float(max(-min(ys), info['height'] + (1.3 if info['shows'] else 0.0))), 3))
    return out


TOPS = tops()


def piece_reach():
    """How far toward the tail any piece reaches from its place (px), turned as its place turns it: rounded up to a twentieth."""
    _, load = hc.build()
    corners = np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)], float)
    most = 0.0
    for x, z, yaw, _, _ in hc.SLOTS:
        R = K.roty(-yaw)
        for kind in hc.KT_ORDER:
            for b in K.boxes_of(load, only={kind}, skip=('cage_hen_head',)):
                most = max(most, float((R @ (b.c + (corners * b.h) @ b.ax).T)[2].max()))
    return math.ceil(most * 20 - 1e-9) / 20.0


PIECE_REACH = piece_reach()


def heap(goods):
    """[goods] ((kind, item or None) in place order) as the renderer heaps them: (kind, place, foot y, item) each."""
    out = []
    for i, (kind, item) in enumerate(goods):
        _, _, _, _, under = hc.SLOTS[i]
        rest = max((hc.ALL_PIECES[goods[j][0]]['height'] for j in under if j < len(goods)), default=0.0)
        out.append((kind, i, hc.FLOOR_TOP - rest, item))
    return out


def weight(z, cover):
    """How much a piece at place z lifts the canvas at [cover], as PlayerCartRenderer.clearance has it: fully if its back edge
    (PIECE_REACH behind it) is under the folded canvas (behind the tail strip's front edge); otherwise from when the canvas, unfolding,
    is LEAD short of reaching that edge to when it reaches it (at least RAMP_MIN of cover), eased."""
    tail, span = EDGES[1], EDGES[1] - CANVAS_FRONT
    back = z + PIECE_REACH
    if back >= tail:
        return 1.0
    at = (tail - back) / span
    lo = max(0.0, at - LEAD)
    hi = max(at, lo + RAMP_MIN)
    return ease((cover - lo) / (hi - lo))


def clearance(pieces, cover, old=False):
    """Where the canvas's top face lies (px, y down), as PlayerCartRenderer.clearance works it out: CANVAS_LIFT over the highest point
    (TOPS) of each piece, by its `weight`; on the posts if nothing is higher. [old]: as D2's stand-in did it (every piece, its stacking
    height, 2.4 over)."""
    top = CANVAS_TOP
    for kind, slot, foot, _ in pieces:
        k = hc.KT_ORDER.index(kind)
        if old:
            top = min(top, foot - hc.ALL_PIECES[kind]['height'] - 2.4)
            continue
        need = foot - TOPS[k] - CANVAS_LIFT
        top = min(top, CANVAS_TOP + (need - CANVAS_TOP) * weight(hc.SLOTS[slot][1], cover))
    return top


def dress_pose(cover=1.0, wind=0.0, flutter=0.1, lift=CANVAS_TOP, roll=0.0, pitch=0.0):
    """{part of the dressing: (A, t)} as the renderer places it: the pennant rippling ([wind], [flutter]), the canvas folded back as far as
    [cover] (0 back, 1 over), its top face at [lift], rocked by the load's [roll] and [pitch] (degrees) about the floor."""
    out = {'pole': IDENTITY}
    m = IDENTITY
    for i in range(len(RIPPLES)):
        hx, hz = RIPPLES[i]
        turn = math.degrees(flutter * math.sin(wind - i * RIPPLE_LAG) + SLACK * i)
        m = then(m, about(K.roty(turn), (hx, 0.0, hz)))
        out[f'flag_{i}'] = m
    base = (np.eye(3), np.array([0.0, lift - CANVAS_TOP, 0.0]))
    rock = about(K.rotz(roll) @ K.rotx(pitch), (0.0, hc.FLOOR_TOP, 0.0))
    m = then(base, rock)
    out['canvas_0'] = m
    for k in range(1, STRIPS):
        a, b = FOLDS[k - 1]
        folded = 1.0 - ease(cover * (STRIPS - 1) - (k - 1))
        m = then(m, about(K.rotx(-180.0 * folded), (0.0, a, b)))
        out[f'canvas_{k}'] = m
    return out


def dress_faces(dress, tex, pose, M=np.eye(3), skip=(), tint=None, wet=None):
    """The dressing's faces as posed by [pose] (dress_pose), the cart turned by [M]; the pennant tinted [tint] (RGB); [wet] (0..1, and the
    texture's name) draws the canvas a second time with the wet look."""
    faces = []
    for p in dress.parts:
        if p.name in skip:
            continue
        A, t = pose[p.name]
        faces += K.faces_of(dress, tex, M=M @ A, T=M @ t, only={p.name}, tint=tint if p.name.startswith('flag_') else None)
    if wet:
        amount, name = wet
        for p in dress.parts:
            if p.name.startswith('canvas_') and p.name not in skip:
                A, t = pose[p.name]
                faces += K.faces_of(dress, name, M=M @ A, T=M @ t, only={p.name})
    return faces


def cart_faces(cart, tex, pitch=0.0, roll=0.0, spin=0.0):
    M, extra = hc.cart_pose(pitch, roll, spin)
    return K.faces_of(cart, tex, pose={k: {'rot': (v, 0.0, 0.0)} for k, v in extra.items()}, M=M), M


def load_faces(load, tex, pieces, M, roll=0.0, pitch=0.0, atlas=None, atlas_name='atlas'):
    """The load's pieces where the renderer puts them (rocked about the floor as the canvas is), and the goods on the open ones."""
    rock = about(K.rotz(roll) @ K.rotx(pitch), (0.0, hc.FLOOR_TOP, 0.0))
    R = M @ rock[0]
    faces = []
    for kind, slot, foot, item in pieces:
        x, z, yaw, _, _ = hc.SLOTS[slot]
        P0 = M @ (rock[0] @ np.array([x, foot, z]) + rock[1])
        faces += K.faces_of(load, tex, M=R @ K.roty(-yaw), T=P0, only={kind})
        if item and atlas is not None:
            for A, B, D, rect, face, n in hc.item_faces(kind, slot, item, atlas, layer_y=hc.FLOOR_TOP - foot, M=np.eye(3)):
                W = lambda q: M @ (rock[0] @ np.asarray(q, float) + rock[1])
                nn = R @ np.asarray(n, float)
                faces.append(K.Face(W(A), W(B), W(D), rect, face, nn / np.linalg.norm(nn), atlas_name))
    return faces


# ================================================================== the self-check: the pictures, the fold, the swing and the clearances

def moved(b, m):
    """Box [b] carried by the map [m] = (A, t)."""
    A, t = m
    return K.Box(b.part, b.cube, A @ b.c + t, b.ax @ A.T, b.h)


def dress_boxes(dress, pose, M=np.eye(3)):
    out = []
    for p in dress.parts:
        A, t = pose[p.name]
        out += [moved(b, (M @ A, M @ t)) for b in K.boxes_of(dress, only={p.name})]
    return out


def load_boxes(load, pieces, roll=0.0, pitch=0.0):
    """The load's pieces as boxes where the renderer puts them (the hen's head left out: she turns it inside her coop)."""
    rock = about(K.rotz(roll) @ K.rotx(pitch), (0.0, hc.FLOOR_TOP, 0.0))
    out = []
    for kind, slot, foot, _ in pieces:
        x, z, yaw, _, _ = hc.SLOTS[slot]
        m = then(rock, (K.roty(-yaw), np.array([x, foot, z])))
        out += [moved(b, m) for b in K.boxes_of(load, only={kind}, skip=('cage_hen_head',))]
    return out


def wheel_boxes(cart, spin):
    return [b for b in K.boxes_of(cart, pose={'wheel_r': {'rot': (spin, 0, 0)}, 'wheel_l': {'rot': (spin, 0, 0)}}) if b.part.startswith('wheel_l')]


# The heaps the checks pose: the bed empty, a little, full of each kind, produce with baskets on top, a household's move (the tallest: the
# chair's back on the top layer), and the seventh place filled without the eighth (a top piece ahead of the tail, none over it)
def check_heaps():
    sacks = [('sack', None)] * 8
    return {
        'empty': [], 'two sacks': sacks[:2], 'sacks': sacks, 'seven': sacks[:7],
        'crates': [('crate', 'apple')] * 8, 'baskets on top': [('crate', 'apple')] * 6 + [('basket', 'bread')] * 2,
        'fish': [('fish', 'cod')] * 8, 'household': [(k, None) for k in hc.HOUSEHOLD],
        'bundles': [('bundle', None)] * 8,
    }


COVERS = [i / 36.0 for i in range(37)]


def check_fold(dress):
    """No two strips of the canvas pass through each other at any step of folding (adjacent strips meet only along their hinge)."""
    problems = []
    for cover in COVERS:
        bs = dress_boxes(dress, dress_pose(cover))
        strips = {}
        for b in bs:
            if b.part.startswith('canvas_'):
                strips.setdefault(int(b.part[-1]), []).append(b)
        for i in range(STRIPS):
            for j in range(i + 1, STRIPS):
                d = max(K.box_depth(a, b) for a in strips[i] for b in strips[j])
                if d > 0.01:
                    problems.append(f'canvas_{i} and canvas_{j} meet {d:.3f} px deep at cover {cover:.3f}')
    return problems


def check_clear(cart, load, dress):
    """What moves against what stands: the pennant against the pole at its widest flutter; the pole, its straps and the pennant against the
    wheels at every turn; the canvas, laid over each heap and rocked, against the pole and the load; the pennant against the canvas."""
    problems = []
    still = dress_boxes(dress, dress_pose())
    pole = [b for b in still if b.part == 'pole']
    # the wheel: a felloe repeats every 30 degrees
    for spin in np.arange(0.0, 30.0, 2.5):
        wb = wheel_boxes(cart, spin)
        for b in pole + [b for b in still if b.part.startswith('flag_')]:
            d = max(K.box_depth(b, w) for w in wb)
            if d > 1e-6:
                problems.append(f'{b.cube} meets the wheel {d:.3f} px deep at a turn of {spin} degrees')
    worst = {'pennant-pole': -9.0, 'pennant-canvas': -9.0, 'canvas-pole': -9.0, 'canvas-load': -9.0}
    for wind in np.linspace(0, 2 * math.pi, 25):
        for flutter in (0.0, FLUTTER_MAX * 0.5, FLUTTER_MAX):
            fb = [b for b in dress_boxes(dress, dress_pose(wind=wind, flutter=flutter)) if b.part.startswith('flag_')]
            worst['pennant-pole'] = max(worst['pennant-pole'], max(K.box_depth(a, b) for a in fb for b in pole if b.cube != 'c_pole_cord'))
    for name, goods in check_heaps().items():
        pieces = heap(goods)
        for cover in COVERS[::2]:
            lift = clearance(pieces, cover)
            for roll, pitch in ((0, 0), (ROCK_MAX, 0), (-ROCK_MAX, 0), (0, ROCK_MAX), (0, -ROCK_MAX)):
                pose = dress_pose(cover, lift=lift, roll=roll, pitch=pitch, flutter=FLUTTER_MAX)
                bs = dress_boxes(dress, pose)
                cv = [b for b in bs if b.part.startswith('canvas_')]
                d = max(K.box_depth(a, b) for a in cv for b in pole)
                if d > worst['canvas-pole']:
                    worst['canvas-pole'] = d
                    worst['canvas-pole at'] = (name, cover, roll, pitch)
                if roll == 0 and pitch == 0:
                    lb = load_boxes(load, pieces)
                    if lb:
                        d = max(K.box_depth(a, b) for a in cv for b in lb)
                        if d > worst['canvas-load']:
                            worst['canvas-load'] = d
                            worst['canvas-load at'] = (name, cover)
                # the canvas is pulled over or folded back standing (the shafts down, the pennant hardly stirring); it rides over or
                # folded at a run, the pennant streaming as far as it goes
                flutter = FLUTTER_MAX if cover in (0.0, 1.0) else FLUTTER_STAND
                for wind in np.linspace(0, 2 * math.pi, 9):
                    fb = [b for b in dress_boxes(dress, dress_pose(cover, wind, flutter, lift, roll, pitch)) if b.part.startswith('flag_')]
                    d = max(K.box_depth(a, b) for a in fb for b in cv)
                    if d > worst['pennant-canvas']:
                        worst['pennant-canvas'] = d
                        worst['pennant-canvas at'] = (name, cover, roll, pitch)
    for key in ('pennant-pole', 'pennant-canvas', 'canvas-pole'):
        if worst[key] > 1e-6:
            problems.append(f'{key} meet {worst[key]:.3f} px deep ({worst.get(key + " at")})')
    if worst['canvas-load'] > 0.05:
        problems.append(f'the canvas goes {worst["canvas-load"]:.3f} px into the load ({worst.get("canvas-load at")})')
    return problems, worst


def check_rest(cart, dress):
    """At rest the canvas lies on the posts' tops (touching, not in them, not over them) and the pole's straps reach into the post."""
    problems = []
    bs = dress_boxes(dress, dress_pose(1.0))
    body = [b for b in K.boxes_of(cart) if b.part == 'bed']
    posts = [b for b in body if b.cube.startswith('c_post')]
    cv = [b for b in bs if b.part.startswith('canvas_')]
    d = max(K.box_depth(a, b) for a in cv for b in body)
    if d > 0.01:
        problems.append(f'the canvas goes {d:.3f} px into the bed at rest')
    gap = max(K.box_depth(a, b) for a in cv for b in posts)
    if gap < -0.01:
        problems.append(f'the canvas floats {-gap:.3f} px over the posts')
    for s in ('c_pole_strap0', 'c_pole_strap1'):
        sb = next(b for b in bs if b.cube == s)
        if K.box_depth(sb, next(b for b in posts if b.cube == 'c_post_l1')) <= 0.05:
            problems.append(f'{s} does not reach into the left rear post')
        if K.box_depth(sb, next(b for b in bs if b.cube == 'c_pole')) <= 0.05:
            problems.append(f'{s} does not hold the pole')
    folded = dress_boxes(dress, dress_pose(0.0))
    stack = [b for b in folded if b.part.startswith('canvas_') and b.cube != 'c_canvas_flap']
    top = min(float(b.lo[1]) for b in stack)
    if abs(top - (CANVAS_TOP - SHEET * (STRIPS - 1))) > 0.01:
        problems.append(f'the folded stack tops out at {top:.3f}')
    return problems


def check_contract(cart, load, dress, tex, wet):
    problems = []
    problems += K.check_model(cart, tex, (TEX_W, TEX_H), who='player_cart (the handcart in the livery)')
    problems += K.check_model(dress, tex, (TEX_W, TEX_H), who='player_cart dressing')
    union = Model('union', TEX_W, TEX_H, cart.parts + dress.parts)
    problems += [p for p in K.check_model(union, tex, (TEX_W, TEX_H), who='player_cart') if 'paints over' in p]
    canvas_only = Model('wet', TEX_W, TEX_H, [p for p in dress.parts if p.name.startswith('canvas_')])
    problems += K.check_model(canvas_only, wet, (TEX_W, TEX_H), who='player_cart_wet')
    a = np.array(wet)
    inside = np.zeros(a.shape[:2], bool)
    for c in canvas_only.all_cubes():
        for _, (x, y, w, h) in K.rects(c, SCALE).items():
            inside[y:y + h, x:x + w] = True
    if (a[..., 3][~inside] > 0).any():
        problems.append('player_cart_wet.png has paint outside the canvas')
    names = K.all_names(dress)
    want = ['pole'] + [f'flag_{k}' for k in range(len(RIPPLES))] + [f'canvas_{k}' for k in range(STRIPS)]
    if names != want:
        problems.append(f'the dressing has the parts {names}, the renderer looks for {want}')
    if len(FOLDS) != STRIPS - 1:
        problems.append('a fold for each strip from the second on')
    # handcart.py's own outputs are what handcart.py writes, untouched by this variant
    hcart, hload = hc.build()
    if [c.uv for c in hcart.all_cubes()] != [c.uv for c in cart.all_cubes()]:
        problems.append('the handcart packs its cubes elsewhere than this texture paints them')
    shipped = os.path.join(ASSETS, 'textures', 'entity', 'cart', 'handcart.png')
    if os.path.exists(shipped) and not np.array_equal(np.array(Image.open(shipped).convert('RGBA')), np.array(hc.texture(hcart, hload))):
        problems.append('handcart.png on disk is not what handcart.py paints')
    src = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/cart/HandcartMesh.kt')
    if os.path.exists(src) and open(src).read() != hc.kotlin_mesh(hcart, hload):
        problems.append('HandcartMesh.kt on disk is not what handcart.py writes')
    problems += check_rest(cart, dress)
    problems += check_fold(dress)
    clear, _ = check_clear(cart, load, dress)
    problems += clear
    K.must_be_clean(problems, NAME)


# ================================================================== the Kotlin table

KOTLIN_HEADER = '''// generated by art/scripts/player_cart.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.trade

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.core.Direction
import java.util.EnumSet
'''

KOTLIN_OBJECT = '''
/**
 * The Chapman's Cart's art (art/scripts/player_cart.py): the carters' handcart in a trader's livery, and its dressing. Model space is the
 * handcart's (HandcartMesh): pixels, y down, the origin the middle of the axle, the shafts toward -z, its left +x.
 *
 * - [TEXTURE] paints the handcart's own model (HandcartMesh's layer, the same UVs) in the Chapman's livery: green boards lined in cream, red
 *   wheels, brass caps and ferrules, leather on the grips, a gilt wheel on the tailboard. The load is still drawn from the handcart's texture.
 * - [dressing] is a second layer on the same texture (box UV on [TEX_WIDTH] x [TEX_HEIGHT], shipped at 2x): `pole` (still), `flag_0` to `flag_3`
 *   (the pennant, white to be tinted with the cart's colour; segment k turns about the vertical at [RIPPLES] k, the next riding on it) and
 *   `canvas_0` to `canvas_3` (the canvas from the tail forward; strip k folds onto the stack about [FOLDS] k - 1 by rotX(-180 * folded), the
 *   strips after it riding on it; its top face at [CANVAS_TOP] over an empty bed). Each part's pivot is where it turns.
 * - The canvas folds as a roll, the front strip first: each hinge lies half the thickness of what its strip carries over the top face, so the
 *   flipped stack lands on the strip behind it and nothing goes through anything at any step. Folded, it is [STACK] px deep on the tail strip.
 * - [WET] holds the canvas only, beaded with rain: draw each strip a second time in the same pose through `RenderTypes.entityTranslucent` with
 *   it, white with the alpha of how wet the canvas is.
 * - To lift the canvas clear of the load: its top face [CANVAS_LIFT] over the highest point ([TOPS], by HandcartMesh.PIECES) of each piece the
 *   canvas is laid over. Folded it covers the tail strip only (back to [FOLDS] 0's z); over, it reaches [CANVAS_FRONT]; a piece reaches
 *   [PIECE_REACH] px toward the tail from its place. A piece further forward than the folded canvas counts from a sixth of the cover before
 *   the unfolding canvas reaches it, eased over at least a tenth (PlayerCartRenderer.clearance), so the canvas is up before it comes down.
 *
 * See guildmark-notes/living-world/trader/guide/tra_x2.md.
 */
object PlayerCartMesh {
    const val TEX_WIDTH = @TEX_W@
    const val TEX_HEIGHT = @TEX_H@

    /** The handcart's model in the livery, and the dressing (one texture); the canvas wet. */
    const val TEXTURE = "@TEXTURE@"
    const val WET = "@WET@"

    /** The canvas: its top face over an empty bed (on the posts' tops), its front edge over the load, how far its top face is over what it rests on, how deep it lies folded. */
    const val CANVAS_TOP = @CANVAS_TOP@
    const val CANVAS_FRONT = @CANVAS_FRONT@
    const val CANVAS_LIFT = @CANVAS_LIFT@
    const val STACK = @STACK@

    /** How far a piece of the load reaches toward the tail from its place (px). */
    const val PIECE_REACH = @PIECE_REACH@

    /** Strip k's fold, from the second strip on: (y, z) pairs. */
    val FOLDS = floatArrayOf(@FOLDS@)

    /** The pennant's hinges, from the pole outward: (x, z) pairs. */
    val RIPPLES = floatArrayOf(@RIPPLES@)

    /** Each of HandcartMesh.PIECES' highest point over its foot (px): handles, knots, a chair's back and the goods shown on an open one included. */
    val TOPS = floatArrayOf(@TOPS@)

    /** The pole, the pennant and the canvas. */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun dressing(): LayerDefinition {
@MESH@
    }
}
'''


def kotlin_source(dress):
    tok = {
        'TEX_W': str(TEX_W), 'TEX_H': str(TEX_H), 'TEXTURE': TEXTURE, 'WET': WET,
        'CANVAS_TOP': K.kt_float(CANVAS_TOP), 'CANVAS_FRONT': K.kt_float(CANVAS_FRONT), 'CANVAS_LIFT': K.kt_float(CANVAS_LIFT),
        'STACK': K.kt_float(SHEET * STRIPS), 'PIECE_REACH': K.kt_float(PIECE_REACH),
        'FOLDS': K.kt_list([v for f in FOLDS for v in f]), 'RIPPLES': K.kt_list([v for r in RIPPLES for v in r]),
        'TOPS': K.kt_list(TOPS), 'MESH': dress.kotlin().rstrip('\n'),
    }
    body = KOTLIN_OBJECT
    for k, v in tok.items():
        body = body.replace('@' + k + '@', v)
    assert '@' not in body.replace('@Suppress', ''), 'a token is left in the template'
    return KOTLIN_HEADER + body


# ================================================================== writing it all

def write_all(assets=ASSETS, kotlin_dir=KOTLIN, model_dir=MODELS):
    """Everything the script owns: the two textures, the Kotlin table and the Blockbench file. Returns (cart, load, dress, tex, wet)."""
    cart, load, dress, used, rows = build()
    tex = texture(cart, dress)
    wet = wet_texture(dress)
    check_contract(cart, load, dress, tex, wet)
    tdir = os.path.join(assets, 'textures', 'entity', 'trade')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, 'player_cart.png'))
    wet.save(os.path.join(tdir, 'player_cart_wet.png'))
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'PlayerCartMesh.kt'), 'w') as f:
        f.write(kotlin_source(dress))
    os.makedirs(model_dir, exist_ok=True)
    small = tex.resize((TEX_W, TEX_H), Image.LANCZOS)
    both = Model(NAME, TEX_W, TEX_H, cart.parts + dress.parts)
    with open(os.path.join(model_dir, NAME + '.bbmodel'), 'w') as f:
        f.write(K.steady_uuids(json.dumps(both.bbmodel(small, NAME)), NAME))
    check_written(cart, dress, assets, kotlin_dir, model_dir)
    return cart, load, dress, tex, wet


def check_written(cart, dress, assets, kotlin_dir, model_dir):
    problems = []
    for name in ('player_cart.png', 'player_cart_wet.png'):
        png = Image.open(os.path.join(assets, 'textures', 'entity', 'trade', name))
        if png.size != (TEX_W * SCALE, TEX_H * SCALE) or png.mode != 'RGBA':
            problems.append(f'{name} is {png.size} {png.mode}')
    src = open(os.path.join(kotlin_dir, 'PlayerCartMesh.kt')).read()
    names = re.findall(r'addOrReplaceChild\("([a-z_0-9]+)"', src)
    if names != K.all_names(dress):
        problems.append(f'the Kotlin has other parts than the dressing: {names}')
    offs = re.findall(r'texOffs\((\d+), (\d+)\)', src)
    if [tuple(int(v) for v in o) for o in offs] != [tuple(int(v) for v in c.uv) for c in dress.all_cubes()]:
        problems.append('the Kotlin places the cubes elsewhere in the texture')
    for const, val in (('CANVAS_TOP', CANVAS_TOP), ('CANVAS_FRONT', CANVAS_FRONT), ('CANVAS_LIFT', CANVAS_LIFT), ('PIECE_REACH', PIECE_REACH)):
        if f'{const} = {K.kt_float(val)}' not in src:
            problems.append(f'the Kotlin does not carry {const} = {K.kt_float(val)}')
    for key, vals in (('FOLDS', [v for f in FOLDS for v in f]), ('RIPPLES', [v for r in RIPPLES for v in r]), ('TOPS', TOPS)):
        if f'val {key} = floatArrayOf({K.kt_list(vals)})' not in src:
            problems.append(f'the Kotlin\'s {key} is not {vals}')
    if len(TOPS) != len(hc.KT_ORDER):
        problems.append('TOPS has another length than HandcartMesh.PIECES')
    bb = json.load(open(os.path.join(model_dir, NAME + '.bbmodel')))
    if len(bb['elements']) != len(list(cart.all_cubes())) + len(list(dress.all_cubes())):
        problems.append(f'{NAME}.bbmodel has {len(bb["elements"])} cubes')
    K.must_be_clean(problems, NAME + ' (written)')


def report(cart, dress, worst=None):
    cubes = len(list(dress.all_cubes()))
    lines = [
        f'{NAME}: texture {TEX_W} x {TEX_H} declared ({TEX_W * SCALE} x {TEX_H * SCALE} shipped), the handcart\'s {len(list(cart.all_cubes()))} cubes in the livery, '
        f'the dressing {cubes} cubes in {len(dress.parts)} parts',
        f'  canvas: top {CANVAS_TOP:g} on the posts, {STRIPS} strips {EDGES}, folds {FOLDS}, {SHEET * STRIPS:g} px deep folded',
        f'  pennant: hinges {RIPPLES}; pole x {POLE_X} z {POLE_Z} y {POLE_Y}',
        f'  tops: {dict(zip(hc.KT_ORDER, TOPS))}',
    ]
    if worst:
        lines.append('  closest (px, < 0 is a gap): ' + ', '.join(f'{k} {v:.3f}' for k, v in worst.items() if not k.endswith(' at')))
    return '\n'.join(lines)


# ================================================================== previews

DYES = {'red': 0xC43630, 'blue': 0x3864BE, 'green': 0x3E964C, 'amber': 0xD69628, 'violet': 0x8448BE, 'teal': 0x2C9692}   # PlayerCart.DYES


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


PRODUCE = [('crate', 'carrot'), ('crate', 'apple'), ('basket', 'bread'), ('fish', 'cod'), ('crate', 'potato'), ('sack', None),
           ('crate', 'beetroot'), ('basket', 'bread')]


class Scene:
    """What the previews draw from: the models and textures, the carters' handcart's for the load and for the cart beside."""

    def __init__(self, cart, load, dress, tex, wet, game=None):
        self.cart, self.load, self.dress, self.tex, self.wet = cart, load, dress, tex, wet
        self.hct = hc.texture(cart, load)
        self.atlas = hc.Atlas(self.hct)
        for _, item in PRODUCE:
            if item:
                self.atlas.extra(item)
        self.atlas.extra('cod')
        self.vanilla = K.Vanilla(game)
        self.player = K.vanilla_player()

    def textures(self, wet=0.0):
        w = np.array(self.wet).astype(float)
        w[..., 3] *= wet
        return {'pc': self.tex, 'hc': self.hct, 'atlas': self.atlas.image(), 'wet': w, 'steve': self.vanilla.steve()}

    def faces(self, goods=(), cover=1.0, wind=1.0, flutter=0.3, colour='red', pitch=0.0, roll=0.0, spin=10.0, sway=(0.0, 0.0), wet=0.0,
              carters=False, T=(0.0, 0.0, 0.0)):
        cf, M = cart_faces(self.cart, 'hc' if carters else 'pc', pitch, roll, spin)
        pieces = heap(goods)
        out = cf + load_faces(self.load, 'hc', pieces, M, sway[0], sway[1], self.atlas)
        if not carters:
            pose = dress_pose(cover, wind, flutter, clearance(pieces, cover), sway[0], sway[1])
            out += dress_faces(self.dress, 'pc', pose, M, tint=rgb(DYES[colour]) if isinstance(colour, str) else colour,
                               wet=(wet, 'wet') if wet > 0 else None)
        T = np.asarray(T, float)
        for f in out:
            f.A, f.B, f.D = f.A + T, f.B + T, f.D + T
        return out

    def puller(self, T=(0.0, 0.0, 0.0), walk=0.0, speed=0.0):
        pose = K.humanoid_pose(walk, speed)
        back = math.degrees(0.3)
        for arm in ('right_arm', 'right_sleeve', 'left_arm', 'left_sleeve'):
            pose[arm] = {'rot': (back, 0.0, 0.0)}
        return K.faces_of(self.player, 'steve', s=1, pose=pose, T=np.asarray(T, float) + np.array([0.0, hc.R - 24.0, hc.GRIP_Z - 3.5]))

    def shot(self, faces, view, wet=0.0, ground=hc.R):
        return K.render(faces, self.textures(wet), view, ground=ground, shadows=((0.0, -2.0, 13.0, 16.0, 0.35),))


V34 = dict(yaw=-35, pitch=-24, scale=6.0, center=(0, -7, -5), size=(380, 300))
VSIDE = dict(yaw=-90, pitch=0, scale=6.0, center=(0, -10, -5), size=(380, 300))
VBACK = dict(yaw=180, pitch=-8, scale=7.0, center=(0, -10, 0), size=(300, 300))
VTOP = dict(yaw=0, pitch=-89.9, scale=6.0, center=(0, -6, -3), size=(300, 300))


def views_sheet(sc):
    tiles = []
    rows = (('the Chapman\'s Cart, canvas over', dict(goods=PRODUCE, cover=1.0)),
            ('the Chapman\'s Cart, canvas folded back', dict(goods=PRODUCE, cover=0.0)),
            ('the carters\' handcart (handcart.py, unchanged)', dict(goods=PRODUCE, carters=True)))
    for label, kw in rows:
        for vname, v in (('3/4', V34), ('side', VSIDE), ('rear', VBACK), ('above', VTOP)):
            tiles.append(K.caption(sc.shot(sc.faces(**kw), K.View(**v), ground=None if vname == 'above' else hc.R), f'{label}: {vname}'))
    return K.sheet(tiles, 4, 'player_cart.py: the Chapman\'s Cart beside the carters\' handcart, the same load on both')


def steps_sheet(sc):
    tiles = []
    for cover in (0.0, 0.1, 0.22, 1 / 3, 0.45, 0.6, 2 / 3, 0.8, 1.0):
        pieces = heap(PRODUCE)
        tiles.append(K.caption(sc.shot(sc.faces(PRODUCE, cover=cover), K.View(**{**V34, 'yaw': -60, 'pitch': -20})),
                               f'cover {cover:.2f}: top face at y {clearance(pieces, cover):.2f}'))
    return K.sheet(tiles, 3, 'the canvas folding: the front strip flips back first, then those two, then all three onto the tail strip (cover 1 to 0)')


def loads_sheet(sc):
    tiles = []
    for name, goods in check_heaps().items():
        if name in ('fish',):
            continue
        for cover in (1.0, 0.0):
            pieces = heap(goods)
            tiles.append(K.caption(sc.shot(sc.faces(goods, cover=cover), K.View(**VSIDE)),
                                   f'{name}, {"over" if cover else "folded"}: top {clearance(pieces, cover):.2f} (D2\'s stand-in {clearance(pieces, cover, old=True):.2f})'))
    return K.sheet(tiles, 4, 'the canvas lifted clear of the load: on its highest point (handles, knots, the goods on an open crate), or on the posts')


def colours_sheet(sc):
    tiles = []
    v = dict(yaw=-120, pitch=-12, scale=8.0, center=(6, -16, 9), size=(300, 300))
    for i, name in enumerate(DYES):
        tiles.append(K.caption(sc.shot(sc.faces(PRODUCE[:4], cover=1.0, colour=name, wind=i * 1.3, flutter=0.35), K.View(**v)), name))
    return K.sheet(tiles, 3, 'the pennant in each of the six colours a cart is bought in (PlayerCart.DYES): white cloth tinted')


def rain_sheet(sc):
    tiles = []
    for cover in (1.0, 0.0):
        for wet in (0.0, 0.5, 1.0):
            tiles.append(K.caption(sc.shot(sc.faces(PRODUCE[:6], cover=cover, wet=wet), K.View(yaw=-40, pitch=-38, scale=8.0, center=(0, -8, 0), size=(340, 300)), wet=wet),
                                   f'{"over" if cover else "folded"}, wet {wet:g}'))
    return K.sheet(tiles, 3, 'rain on the canvas: player_cart_wet.png drawn over it, its alpha how wet it is (beading up over 3 s, drying over a minute)')


def parked_sheet(sc):
    tilt = hc.parked_tilt()
    tiles = [
        K.caption(sc.shot(sc.faces(PRODUCE[:5], cover=0.0, pitch=-tilt), K.View(**V34)), f'parked, tipped {tilt:.1f} degrees onto its shafts'),
        K.caption(sc.shot(sc.faces(PRODUCE[:5], cover=1.0, pitch=-tilt), K.View(**dict(VSIDE, scale=5.2, center=(0, -11, -4)))), 'parked, canvas over, side'),
        K.caption(sc.shot(sc.faces(PRODUCE, cover=1.0, pitch=-5.8) + sc.puller(), K.View(yaw=-50, pitch=-14, scale=4.6, center=(0, -6, -14), size=(380, 300))),
                  'pulled: the shafts in the hands'),
        K.caption(sc.shot(sc.faces(PRODUCE, cover=1.0, pitch=-5.8) + sc.puller(), K.View(yaw=-90, pitch=0, scale=4.6, center=(0, -6, -14), size=(380, 300))),
                  'pulled, side: the pennant over the player\'s head height'),
    ]
    return K.sheet(tiles, 4, 'parked on its shafts, and pulled (a player at the shafts, for its size)')


def fold_gif(sc, path):
    frames = []
    seq = [1.0] * 8 + [1.0 - i / 36.0 for i in range(37)] + [0.0] * 10 + [i / 36.0 for i in range(37)] + [1.0] * 4
    v = K.View(yaw=-55, pitch=-22, scale=6.5, center=(0, -7, -2), size=(380, 300))
    for k, cover in enumerate(seq):
        frames.append(sc.shot(sc.faces(PRODUCE, cover=cover, wind=k * 0.3, flutter=0.12), v))
    return K.save_gif(frames, path, 50)


def pulled_gif(sc, path):
    """It sets off, rolls at the pulled walk and stops: the wheels turn by how far it goes, the pennant streams the harder the faster, the
    load and the canvas rock back as it starts and forward as it stops (PlayerCartClient's springs, run here tick by tick)."""
    frames = []
    v0 = K.View(yaw=-70, pitch=-16, scale=5.5, center=(0, -9, -4), size=(400, 300))
    z = 0.0
    spin = 0.0
    wind, flutter = 0.0, 0.1
    speed_o = 0.0
    sp = sv = 0.0
    walk = 0.0
    for k in range(90):
        speed = 3.67 * (K.smooth(k / 20.0) - K.smooth((k - 62) / 18.0))          # blocks a second
        step = speed / 20.0 * 16.0                                                # px this tick
        z -= step
        spin += math.degrees(step / hc.R)
        walk += step / 16.0 * 2.2
        wind += 0.28 + speed * 0.22
        flutter += (0.1 + min(speed / 3.7, 1.0) * 0.45 - flutter) * 0.15
        lurch = (speed - speed_o) * 3.0
        sv = (sv + (-max(-5.0, min(5.0, lurch)) - sp) * 0.18) * 0.82
        sp = max(-5.0, min(5.0, sp + sv))
        speed_o = speed
        T = (0.0, 0.0, z)
        faces = sc.faces(PRODUCE, cover=1.0, wind=wind, flutter=flutter, spin=-spin, pitch=-5.8, sway=(0.0, sp), T=T)
        faces += sc.puller(T=T, walk=walk, speed=min(1.0, speed / 3.67) * 0.7)
        view = K.View(v0.yaw, v0.pitch, v0.scale, (0.0, -9.0, z - 4.0), v0.size)
        frames.append(sc.shot(faces, view))
    return K.save_gif(frames, path, 50)


def rain_gif(sc, path):
    frames = []
    v = K.View(yaw=-40, pitch=-40, scale=8.0, center=(0, -8, 0), size=(340, 300))
    seq = [min(1.0, i / 60.0) for i in range(0, 72, 3)] + [max(0.0, 1.0 - i / 60.0) for i in range(0, 72, 3)]
    for k, wet in enumerate(seq):
        frames.append(sc.shot(sc.faces(PRODUCE[:6], cover=1.0, wet=wet, wind=k * 0.4, flutter=0.12), v, wet=wet))
    return K.save_gif(frames, path, 80)


def texture_sheet(tex, wet):
    big = tex.resize((tex.width * 2, tex.height * 2), Image.NEAREST)
    wbig = wet.resize((wet.width * 2, wet.height * 2), Image.NEAREST)
    out = Image.new('RGB', (big.width, big.height * 2 + 4), (30, 32, 40))
    out.paste(big, (0, 0), big)
    out.paste(wbig, (0, big.height + 4), wbig)
    return out


def previews(sc, out):
    os.makedirs(out, exist_ok=True)
    paths = []
    for name, fn in (('views', views_sheet), ('canvas_steps', steps_sheet), ('loads', loads_sheet), ('colours', colours_sheet),
                     ('rain', rain_sheet), ('parked_and_pulled', parked_sheet)):
        p = os.path.join(out, f'player_cart_{name}.png')
        fn(sc).save(p)
        paths.append(p)
    p = os.path.join(out, 'player_cart_texture.png')
    texture_sheet(sc.tex, sc.wet).save(p)
    paths.append(p)
    paths.append(fold_gif(sc, os.path.join(out, 'player_cart_canvas_fold.gif')))
    paths.append(pulled_gif(sc, os.path.join(out, 'player_cart_pulled.gif')))
    paths.append(rain_gif(sc, os.path.join(out, 'player_cart_rain.gif')))
    return paths


def main(argv):
    arg = lambda i, d: argv[i] if len(argv) > i and argv[i] != '-' else d
    assets, kotlin_dir, model_dir = arg(1, ASSETS), arg(2, KOTLIN), arg(3, MODELS)
    prev, game = arg(4, None), arg(5, None)
    cart, load, dress, tex, wet = write_all(assets, kotlin_dir, model_dir)
    _, worst = check_clear(cart, load, dress)
    print(report(cart, dress, worst))
    if prev:
        for p in previews(Scene(cart, load, dress, tex, wet, game), prev):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
