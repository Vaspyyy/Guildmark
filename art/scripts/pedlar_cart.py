"""
The pedlar's cart (Villages, V12a; living-world/villages/design.md 17.2, `handcart_pedlar`): the carters' handcart (`handcart.py`) as a
village pedlar keeps it, with a striped canvas hood on four posts, half drawn back, and a row of pots and a lantern hung along its left
side. It is a variant of `handcart.py`'s cart, not an edit of it (as the Chapman's Cart, `player_cart.py`, is another): the same model,
which HandcartRenderer draws from HandcartMesh's layer, painted on a texture of its own; and a second layer, the dressing (the hood, the
pots, the lantern), on that texture's free rows. Running this writes nothing of `handcart.py`'s and changes nothing it writes.

    python3 -B art/scripts/pedlar_cart.py [assets dir [kotlin dir [model dir [preview dir]]]]

writes `textures/entity/cart/handcart_pedlar.png` (under [assets dir], default `src/main/resources/assets/guildmark`; box UV on the
handcart's own TEX_W x TEX_H, shipped at SCALE times that: the cart's cubes painted where HandcartMesh puts them, the dressing below them),
`client/cart/PedlarCartMesh.kt` (GENERATED; under [kotlin dir], default `src/main/kotlin/io/github/vaspyyy/guildmark/client/cart`),
`handcart_pedlar.bbmodel` (under [model dir], default `art/models`; the cart and its dressing in one) and, with a [preview dir], the
sheets and a GIF. `-` stands for a default. A rerun gives the same files. Before writing it checks its own numbers (`check_contract`);
after, what it wrote (`check_written`); it stops with the list if anything is off. Run it from the repository's root.

THE VARIANT
  model name   `handcart_pedlar` (the bbmodel; the dressing's layer is PedlarCartRenderer.LAYER, `guildmark:handcart_pedlar#dressing`)
  texture      `handcart_pedlar.png`: HandcartRenderer draws the handcart's own model with it instead of `handcart.png` when the
               renderer's `texture(state)` says so; the load is still drawn from `handcart.png`
  flag         `Handcart.pedlar` (synced, never saved, as the cart is not): set on the server when a village's pedlar takes their cart up
               (Pedlar.kt); a cart without it is drawn as the carters' handcart, exactly as before

THE LOOK: a pedlar's cart kept bright for the green. The bed's boards painted a faded cornflower blue with an ochre line round each panel
and a red rose painted in each, chipped to the oak at the edges, three roses on the tailboard; the wheels ochre with a red line inside
black iron tyres, the naves red; the hood's frame ochre; the canvas cream and madder in broad stripes across the cart, sun-faded and a
little grimed toward its hem, drawn back to the middle and gathered there in folds tied to the frame; along the left side an iron rail on
two arms, with a black cooking pot, a copper kettle and a tin pan hung from it; off the front left post an iron arm with a horn lantern,
lit after dark.

THE DRESSING (one layer, `dressing()`, in the handcart's model space: px, y down, the origin the middle of the axle, the shafts toward -z,
its left +x). Every part's pivot is where it turns:

  part            pivot (px)              what it is, and how PedlarCartRenderer moves it
  --------------  ----------------------  ----------------------------------------------------------------------------------------------
  hood            (0, 0, 0)               the four posts on the bed's corner posts, the eave rails, the ridge pole, the two bows and the
                                          canvas over the back half (its roof halves child parts turned SLOPE about the ridge), its folds
                                          and ties, the pot rail and the lantern's arm; still
  pot_0 .. pot_2  HOOKS 0 .. 2            the pot, the kettle and the pan, each on its hook along the rail
  lantern         HOOKS 3                 the lantern on its ring at the end of its arm
  lantern_light   HOOKS 3                 its panes lit and its flame: drawn over the lantern, full bright, after dark

THE SWING (PedlarCartRenderer, tick by tick, `Swing` here for the previews): each hanging thing is a pendulum about its hook, its angles
in the cart's own frame, `rotX(fore) rotZ(side)` about the hook (fore > 0 swings its foot toward the tail, side > 0 toward the cart's
middle). It is pulled toward plumb (wherever the cart is tipped or leaning: parked on its shafts it hangs some 27 degrees off the cart's
own upright), with its own period (PERIOD, ticks), damped (DAMP), and pushed by what the cart does: back as it starts, forward as it
stops, out as it turns (PUSH of its acceleration over G, as a pendulum takes it), and jolted now and then by the road as it rolls
(JOLT_P, JOLT).
What it hits, it knocks against and swings back from (BOUNCE): the canvas and the frame inward (side at most SWING_IN), the rail fore
and aft (|fore| at most FORE), and each pot its neighbour (fore of the one nearer the front at most CLASH more than the next one's).
Never more than SWING_MAX from plumb. `check_contract` proves those limits: nothing goes into anything anywhere inside them.
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
import handcart as hc
import player_cart as pc
import townsfolk_tools as tt
from townsfolk_tools import uv, mix

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/cart')
MODELS = os.path.join(ROOT, 'art/models')

NAME = 'handcart_pedlar'
TEX_W, TEX_H = hc.TEX_W, hc.TEX_H         # the handcart's own layout: the cart's cubes keep their places
SCALE = hc.SCALE
TEXTURE = 'textures/entity/cart/handcart_pedlar.png'

# ================================================================== geometry (px, the handcart's model space)

POST_X = hc.BED_X + 0.5                   # 7.0: the bed's corner posts' middle, out from the cart's middle
POST_Z = (hc.BED_FRONT + 1.0, hc.BED_BACK - 1.0)   # -13.0 and 9.0: the front and the rear posts' middles
POST_TOP = hc.SIDE_TOP - 1.0              # -8.75: the corner posts' tops, which the hood's posts stand on
POST_W = 0.9                              # the hood's posts, square
EAVE_Y = (-19.3, -18.5)                   # the eave rails (the posts' tops), along each side from the front post to the rear one
RIDGE = -21.0                             # the canvas's top face at the ridge
SHEET = 0.35                              # the canvas's thickness
CANVAS_X = POST_X + 0.9                   # 7.9: the side canvas's outer face
SLOPE = math.degrees(math.atan2(1.6, CANVAS_X))     # the roof's fall from the ridge to the eave (about 11.5 degrees)
EAVE_OUT = 0.45                           # how far the roof reaches past the side canvas
CANVAS_FRONT = -2.6                       # the canvas's front edge: drawn back to here, the folds gathered ahead of it
CANVAS_TAIL = hc.BED_BACK + 0.3           # 10.3: just past the tailboard
CANVAS_FOOT = -9.2                        # the side canvas's hem, over the side boards' tops (-7.75) and inside the wheels (x 8)
FOLDS = 3                                 # the gathered canvas: this many folds ahead of its edge, each FOLD_W along and FOLD_T thick
FOLD_W, FOLD_T = 0.9, 0.7
STRIPE = 2.0                              # the stripes across the canvas, this wide
RAIL_X = 10.7                             # the pots' rail along the left side, out past the canvas on two iron arms
RAIL_Y = (-19.0, -18.6)                   # its top and bottom (inside the eave rail's height: the arms are bolted to it)
HOOK_Y = -17.4                            # the hooks' bottoms on it: each pot's pivot
POTS_Z = (-0.2, 4.4, 8.9)                 # the hooks along it
RAIL_Z = (-1.5, 10.2)
ARMS_Z = (-1.1, 9.2)
LANTERN_HOOK = (9.9, -16.8, POST_Z[0])    # the lantern's ring on the end of its arm off the front left post
LANTERN_ARM_Y = (-17.9, -17.5)

HANGING = ['pot_0', 'pot_1', 'pot_2', 'lantern']
NEIGHBOURS = [('pot_0', 'pot_1'), ('pot_1', 'pot_2')]

# The swing's own numbers (PedlarCartMesh carries them to the renderer): periods as a pendulum this long swings, a lightly damped swing,
# gravity, the road's jolts, the knock off what it hits, the most it swings
PERIOD = {'pot_0': 17.0, 'pot_1': 16.0, 'pot_2': 19.0, 'lantern': 18.0}
DAMP = 0.07
G = 10.0 / 400.0                          # gravity, near enough (10 m/s2), in blocks a tick a tick
PUSH = 0.6                                # how much of the cart's acceleration reaches a pot (the rest lost at the hook)
ACC_MAX = 0.02                            # blocks a tick a tick: what a jerk is taken as at most
JOLT_P = 0.15                             # the chance a tick, while it rolls, of a jolt to each
JOLT = 16.0                               # degrees a tick of kick per block a tick of speed, at most
BOUNCE = 0.4
SWING_MAX = 30.0
OUT_MAX = 40.0                            # outward (side < 0) nothing is in the way: the most it swings out
MARGIN = 1.5                              # degrees kept off each limit the check finds
FORE_PAST = 15.0                          # fore and aft, past plumb on a cart parked on its shafts: the most a pot needs

# ================================================================== the palette

BLUE = (78, 108, 152); BLUE_D = (44, 64, 100); BLUE_L = (122, 152, 192)           # the bed's paint, faded cornflower
OCHRE = (196, 146, 56); OCHRE_D = (138, 96, 32); OCHRE_L = (228, 190, 106)       # the lines, the wheels, the hood's frame
MADDER = (172, 66, 52); MADDER_D = (116, 38, 30); MADDER_L = (210, 112, 92)       # the stripes, the roses
CREAM = (226, 214, 182); CREAM_D = (170, 156, 122); CREAM_L = (244, 236, 212)     # the canvas
LEAF = (86, 124, 70)
OAK, OAK_D, OAK_L = hc.BED, hc.BED_D, hc.BED_L
IRON, IRON_D, IRON_L = hc.IRON, hc.IRON_D, hc.IRON_L
COPPER = (184, 100, 58); COPPER_D = (112, 56, 32); COPPER_L = (232, 156, 104)
TIN = (170, 172, 172); TIN_D = (106, 108, 112); TIN_L = (222, 224, 222)
HORN = (96, 80, 54); HORN_L = (140, 120, 84)                                      # the lantern's horn panes, unlit
FLAME = (255, 214, 130); FLAME_L = (255, 246, 214); EMBER = (230, 150, 60)

# ================================================================== painting helpers


def either(faces, here, other):
    """A painter: [here] on [faces], [other] on the rest."""
    def p(img, face, x, y, w, h):
        (here if face in faces else other)(img, face, x, y, w, h)
    return p


def rose(a, cu, cv, r, petal=MADDER, heart=OCHRE_L):
    """A painted rose at face coords (cu, cv), [r] texels across: five petals round a heart, two leaves under it."""
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    x0, y0 = cu * w, cv * h
    for s in (-1, 1):
        lx, ly = x0 + s * r * 0.95, y0 + r * 0.45
        on = np.hypot((xs + 0.5 - lx) * 0.8, ys + 0.5 - ly) <= r * 0.36
        a[..., :3] = np.where(on[..., None], np.array(LEAF, float), a[..., :3])
    for k in range(5):
        ang = k * 2 * math.pi / 5 - math.pi / 2
        px, py = x0 + math.cos(ang) * r * 0.5, y0 + math.sin(ang) * r * 0.5
        on = np.hypot(xs + 0.5 - px, ys + 0.5 - py) <= r * 0.46
        a[..., :3] = np.where(on[..., None], mix(petal, MADDER_L, 0.25 if k % 2 else 0.0), a[..., :3])
    on = np.hypot(xs + 0.5 - x0, ys + 0.5 - y0) <= max(0.7, r * 0.26)
    a[..., :3] = np.where(on[..., None], np.array(heart, float), a[..., :3])
    return a


def wood(w, h, rng, along='u', n=2):
    a, _ = hc.boards(w, h, rng, n, along, OAK, OAK_D, OAK_L, knots=1)
    return a


def z_at(face, w, h, z0, z1):
    """The model z of each texel of [face] of a cube from z0 to z1 (on its ends: all z0 or all z1)."""
    U, V = uv(w, h)
    if face == 'front':
        return np.full((h, w), float(z0))
    if face == 'back':
        return np.full((h, w), float(z1))
    ax, to_front = hc.along_z(face)
    f = U if ax == 'u' else V
    return z1 - f * (z1 - z0) if to_front else z0 + f * (z1 - z0)

# ================================================================== the livery: the handcart's cubes the pedlar has painted


def side_painter(key, outer):
    """A side board's outer face: two planks painted blue, an ochre line round each panel either side of the axle's strap, a red rose in
    each panel with its leaves, black iron at the ends and over the axle; chipped to the oak where loads knock it, worn along its top.
    Its inside and its edges are the carters' own (`handcart.py`'s side)."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        base = wood(w, h, rng)
        a = pc.painted(base, BLUE, BLUE_D, BLUE_L, rng)
        f_axle = hc.front_frac(face, w, h, hc.BED_FRONT, hc.BED_BACK, 0.0)
        e0, e1 = int(math.ceil(0.055 * w)) + 1, int(math.floor(0.945 * w)) - 1
        s0, s1 = int(math.floor((f_axle - 0.035) * w)) - 1, int(math.ceil((f_axle + 0.035) * w)) + 1
        for x0, x1 in ((e0, s0), (s1, e1)):
            pc.line_rect(a, x0, 1, x1, h - 1, OCHRE, 0.9)
            rose(a, (x0 + x1) / 2 / w, 0.5, min(3.0, h * 0.34))
        a = pc.chips(a, base, rng, 10)
        top = (V < 0.13) & (rng.random((h, w)) < 0.5)
        a[..., :3] = np.where(top[..., None], mix(a[..., :3], base[..., :3], 0.7), a[..., :3])
        a = hc.band(a, (U < 0.055) | (U > 0.945) | (np.abs(U - f_axle) < 0.035), rng)
        for cu in (0.028, 0.972, f_axle):
            for cv in (0.25, 0.72):
                hc.nail(a, cu, cv, 0.6)
        return tt.shade_edges(a, 0.14)
    return either({outer}, tt.painter(key, fn), hc.side_painter(key + '_oak', outer))


def end_painter(key, outer, mark=False):
    """The front board and the tailboard: blue outside with the ochre line round it, and on the tailboard ([mark]) three roses, the middle
    one red, the two beside it ochre (the pedlar's own sign); the inside the carters' oak."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        base = wood(w, h, rng)
        a = pc.painted(base, BLUE, BLUE_D, BLUE_L, rng)
        pc.line_rect(a, 2, 1, w - 2, h - 1, OCHRE, 0.9)
        if mark:
            for cu in (0.3, 0.5, 0.7):
                mid = cu == 0.5
                rose(a, cu, 0.52, min(3.0, h * 0.34) * (1.0 if mid else 0.8), MADDER if mid else OCHRE, CREAM_L if mid else MADDER)
        a = pc.chips(a, base, rng, 5)
        a = hc.band(a, (U < 0.05) | (U > 0.95), rng)
        return tt.shade_edges(a, 0.14)
    return either({outer}, tt.painter(key, fn), hc.end_painter(key + '_oak'))


def post_painter(key):
    """A corner post: blue, an ochre band round its top; its top face oak (the hood's post stands on it)."""
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            return tt.grain(w, h, None, OAK, OAK_D, OAK_L, rng)
        base = tt.grain(w, h, 'v', OAK, OAK_D, OAK_L, rng, streak=0.4)
        a = pc.painted(base, BLUE, BLUE_D, BLUE_L, rng)
        U, V = uv(w, h)
        a[..., :3] = np.where((V < 0.16)[..., None], mix(np.array(OCHRE, float), OCHRE_L, 0.2), a[..., :3])
        a = pc.chips(a, base, rng, 2, 0.3)
        return tt.shade_edges(a, 0.15)
    return tt.painter(key, fn)


def rim_painter(key):
    """A felloe: the black iron tyre on its tread, nailed; ochre on the wheel's faces with a red line round inside the tyre."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'top':
            a = tt.metal(w, h, hc.TYRE, hc.TYRE_D, hc.TYRE_L, rng, wear=0.1)
            worn = np.exp(-((V - 0.5) / 0.3) ** 2)
            a[..., :3] = mix(a[..., :3], hc.TYRE_L, (0.3 * worn)[..., None])
            for cv in (0.25, 0.75):
                hc.nail(a, 0.5, cv, 0.55, hc.TYRE_D, hc.TYRE_L)
            return a
        base = tt.grain(w, h, 'u', hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng, streak=0.4)
        a = pc.painted(base, OCHRE, OCHRE_D, OCHRE_L, rng)
        if face in ('right', 'left'):
            a = hc.band(a, V < 0.24, rng, hc.TYRE, hc.TYRE_D, hc.TYRE_L)
            ln = (V > 0.4) & (V < 0.4 + 1.2 / h)
            a[..., :3] = np.where(ln[..., None], mix(a[..., :3], MADDER, 0.85), a[..., :3])
            a[..., :3] = np.where((V > 0.86)[..., None], mix(a[..., :3], OCHRE_D, 0.35), a[..., :3])
            hc.nail(a, 0.5, 0.68, 0.5, IRON_D, IRON_L)
            return pc.chips(a, base, rng, 2)
        if face == 'bottom':
            return a * np.array([0.85, 0.85, 0.85, 1.0])
        return hc.band(a, V < 0.3, rng, hc.TYRE, hc.TYRE_D, hc.TYRE_L)
    return tt.painter(key, fn)


def spoke_painter(key):
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            return tt.grain(w, h, None, OCHRE_D, (96, 64, 22), OCHRE, rng)
        base = tt.grain(w, h, 'v', hc.SPOKE, hc.SPOKE_D, hc.SPOKE_L, rng, streak=0.45)
        a = pc.painted(base, OCHRE, OCHRE_D, OCHRE_L, rng)
        U, V = uv(w, h)
        a[..., :3] *= (0.82 + 0.3 * np.sin(np.pi * U))[..., None]
        return a
    return tt.painter(key, fn)


def hub_painter(key):
    """The nave: red, an iron band at each end of its round, its face an iron ring round the dark bore."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        iron = tt.metal(w, h, IRON, IRON_D, IRON_L, rng, bevel=False)
        if face in ('right', 'left'):
            base = tt.grain(w, h, None, hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng)
            a = pc.painted(base, MADDER, MADDER_D, MADDER_L, rng)
            d = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = np.where((d > 0.36)[..., None], iron[..., :3], a[..., :3])
            a[..., :3] = np.where((d < 0.16)[..., None], np.array(IRON_D, float), a[..., :3])
            return a
        base = tt.grain(w, h, 'u', hc.WHEEL, hc.WHEEL_D, hc.WHEEL_L, rng, streak=0.35)
        a = pc.painted(base, MADDER, MADDER_D, MADDER_L, rng)
        a[..., :3] = np.where(((U < 0.2) | (U > 0.8))[..., None], iron[..., :3], a[..., :3])
        return a
    return tt.painter(key, fn)


def livery():
    """{the handcart's cube (or shared picture) name: its painter on the pedlar's cart}; the rest are the carters' own."""
    L = {
        'c_side_r': side_painter('pd_side_r', 'right'), 'c_side_l': side_painter('pd_side_l', 'left'),
        'c_front': end_painter('pd_front', 'front'), 'c_tail': end_painter('pd_tail', 'back', mark=True),
        'c_rim': rim_painter('pd_rim'), 'c_spoke': spoke_painter('pd_spoke'), 'c_nave': hub_painter('pd_nave'),
    }
    for n in ('r0', 'r1', 'l0', 'l1'):
        L[f'c_post_{n}'] = post_painter(f'pd_post_{n}')
    return L

# ================================================================== the dressing's painters


def frame_painter(key, along):
    """The hood's frame: ash painted ochre, worn to the wood on its edges."""
    def fn(face, w, h, rng):
        ax = along.get(face) if isinstance(along, dict) else along
        base = tt.grain(w, h, ax, hc.ASH, hc.ASH_D, hc.ASH_L, rng, streak=0.45)
        a = pc.painted(base, OCHRE, OCHRE_D, OCHRE_L, rng)
        a = pc.chips(a, base, rng, max(1, (w * h) // 60), 0.3)
        return tt.shade_edges(a, 0.2)
    return tt.painter(key, fn)


def canvas_painter(key, z0, z1, fold=False, hem=None):
    """The hood's canvas: a heavy cotton duck in cream and madder stripes STRIPE px wide across the cart (laid by model z, so a stripe runs
    on unbroken from the roof down the side and through the folds), sun-faded in flecks; grimed toward its hem on a side drop ([hem]: the
    face's v where the hem's seam runs). [fold]: a gathered fold, shaded deep in its creases."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        z = z_at(face, w, h, z0, z1)
        k = np.floor((z - CANVAS_FRONT + 1e-3) / STRIPE).astype(int) % 2
        a = tt.weave(w, h, CREAM, CREAM_D, CREAM_L, rng, cell=1)
        a[..., :3] = mix(np.array(CREAM, float), a[..., :3], 0.35)
        red = mix(np.array(MADDER, float), MADDER_L, 0.25)
        a[..., :3] = np.where((k == 1)[..., None], mix(a[..., :3], red, 0.86), a[..., :3])
        a[..., :3] *= 1 + rng.normal(0, 0.03, (h, w, 1))
        sun = rng.random((h, w)) < 0.12
        a[..., :3] = np.where(sun[..., None], mix(a[..., :3], CREAM_L, 0.22), a[..., :3])
        if hem is not None and face in ('right', 'left'):
            a[..., :3] = np.where((V > hem)[..., None], mix(a[..., :3], (120, 104, 80), 0.25), a[..., :3])
            seam = np.abs(V - hem) < 0.5 / max(h, 1)
            a[..., :3] = np.where(seam[..., None], mix(a[..., :3], MADDER_D, 0.6), a[..., :3])
        if fold:
            across = V if face in ('right', 'left') else U
            crease = 0.5 + 0.5 * np.cos(across * math.pi * 4.0)
            a[..., :3] *= (0.78 + 0.26 * crease)[..., None]
        return tt.shade_edges(a, 0.16 if fold else 0.1)
    return tt.painter(key, fn)


def cord_painter(key):
    return tt.painter(key, lambda face, w, h, rng: pc._rope(face, w, h, rng))


def iron_painter(key):
    return tt.painter(key, lambda face, w, h, rng: tt.metal(w, h, IRON, IRON_D, IRON_L, rng, wear=0.06))


def kettle_painter(key, part='body'):
    """A copper kettle, beaten and gone dark from the fire toward its foot, bright where it is handled."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, COPPER, COPPER_D, COPPER_L, rng, wear=0.1)
        U, V = uv(w, h)
        if part == 'body' and face not in ('top', 'bottom'):
            a[..., :3] = mix(a[..., :3], (40, 28, 22), (0.6 * V ** 3)[..., None])
            a[..., :3] *= (0.84 + 0.28 * np.sin(np.pi * U))[..., None]
            spots = rng.random((h, w)) < 0.06
            a[..., :3] = np.where(spots[..., None], mix(a[..., :3], COPPER_L, 0.5), a[..., :3])
        elif part == 'body' and face == 'bottom':
            a[..., :3] = mix(a[..., :3], (30, 24, 22), 0.7)
        elif part == 'lid' and face == 'top':
            a[..., :3] *= (1.12 - 0.5 * np.hypot(U - 0.5, V - 0.5))[..., None]
        return a
    return tt.painter(key, fn)


def tin_painter(key, part='pan'):
    """A tin pan hung by its handle, its inside turned out to the road: bright, a ring of wear round its middle; its back sooted."""
    def fn(face, w, h, rng):
        if part == 'handle':
            return tt.metal(w, h, IRON, IRON_D, IRON_L, rng)
        a = tt.metal(w, h, TIN, TIN_D, TIN_L, rng, wear=0.08)
        U, V = uv(w, h)
        if face == 'right':                     # its back, toward the canvas
            a[..., :3] = mix(a[..., :3], (34, 32, 32), 0.6)
        elif face == 'left':
            d = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = mix(a[..., :3], TIN_L, np.clip(0.4 - d, 0, 0.4)[..., None])
            ring = np.abs(d - 0.32) < 0.05
            a[..., :3] = np.where(ring[..., None], mix(a[..., :3], TIN_D, 0.4), a[..., :3])
        return a
    return tt.painter(key, fn)


def lantern_painter(key, part):
    """The lantern: black iron ring, cap and base; its panes of horn between iron corner bars, a smoky amber while it is out. [part] 'light':
    the same panes lit (drawn full bright over them after dark, the bars left the lantern's own) and 'flame' the flame in it."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if part in ('cap', 'base', 'ring'):
            return tt.metal(w, h, IRON, IRON_D, IRON_L, rng, wear=0.08)
        if part == 'flame':
            a = tt.noise_fill(w, h, FLAME, rng, 0.04)
            a[..., :3] = mix(a[..., :3], FLAME_L, np.clip(1.0 - np.hypot(U - 0.5, V - 0.65) * 2.5, 0, 1)[..., None])
            return a
        lit = part == 'light'
        bars = (U < 0.16) | (U > 0.84)
        if face in ('top', 'bottom'):
            bars = bars | (V < 0.16) | (V > 0.84)
        if lit:
            a = tt.noise_fill(w, h, mix(np.array(FLAME, float), FLAME_L, 0.1), rng, 0.05)
            glow = np.clip(1.0 - np.hypot(U - 0.5, V - 0.6) * 1.6, 0, 1)
            a[..., :3] = mix(np.array(EMBER, float), a[..., :3], glow[..., None] * 0.9 + 0.1)
            a[..., 3] = np.where(bars, 0, 255)
            return a
        a = tt.noise_fill(w, h, HORN, rng, 0.05)
        a[..., :3] = mix(a[..., :3], HORN_L, (np.clip(0.5 - np.abs(U - 0.32), 0, 0.5) * 0.6)[..., None])
        a[..., :3] = np.where(bars[..., None], np.array(IRON, float), a[..., :3])
        return a
    return tt.painter(key, fn, light=part not in ('light', 'flame'))

# ================================================================== building it


def bx(x0, y0, z0, x1, y1, z1, name, paint=None, inflate=0.0):
    return K.bx(min(x0, x1), min(y0, y1), min(z0, z1), max(x0, x1), max(y0, y1), max(z0, z1), name, paint, inflate=inflate)


def eave_y(x, under=0.0):
    """The canvas's top face's height (px, y down) [x] px out from the ridge, [under] below it."""
    return RIDGE + under + abs(x) * math.tan(math.radians(SLOPE))


def roof(hood, z0, z1, name, paint, under=0.0, thick=SHEET, out=EAVE_OUT):
    """A roof piece from z0 to z1 in two halves, each its own part turned SLOPE about the ridge: its top [under] px below the canvas's top
    face at the ridge, [thick] thick, reaching [out] px past the side canvas (less than 0: short of it)."""
    for s, n in ((-1, 'r'), (1, 'l')):
        c = bx(0.0, RIDGE + under, z0, s * (CANVAS_X + out), RIDGE + under + thick, z1, f'{name}_{n}', paint)
        # a positive turn about z takes +x down (y down): the left half (+x) turns +SLOPE, the right -SLOPE
        K.part(f'{name}_{n}', (0.0, RIDGE, 0.0), [c], parent=hood, rot=(0.0, 0.0, s * SLOPE))


def hanging_specs():
    """Each hanging thing's cubes about its pivot (x, y, z offsets: y down from the hook), with their painters."""
    ip = iron_painter('pd_iron')
    return {
        # a black iron pot: its body, the rim round its mouth, a lug at each end where the bail is fixed, the bail up to the hook
        'pot_0': [('body', (-1.5, 1.4, -1.5), (1.5, 3.9, 1.5), hc.pot_painter('pd_pot_body', 'body')),
                  ('rim', (-1.65, 1.2, -1.65), (1.65, 1.6, 1.65), hc.pot_painter('pd_pot_rim', 'lid')),
                  ('lug', (-1.9, 1.45, -0.3), (1.9, 2.05, 0.3), hc.pot_painter('pd_pot_lug', 'lug')),
                  ('bail', (-1.1, 0.0, -0.15), (1.1, 1.2, 0.15), ip)],
        # a copper kettle: its body, its lid, its spout turned out to the road, its bail
        'pot_1': [('body', (-1.3, 1.3, -1.3), (1.3, 3.4, 1.3), kettle_painter('pd_kettle_body')),
                  ('lid', (-0.9, 0.95, -0.9), (0.9, 1.3, 0.9), kettle_painter('pd_kettle_lid', 'lid')),
                  ('spout', (1.3, 1.9, -0.3), (2.3, 2.5, 0.3), kettle_painter('pd_kettle_spout', 'spout')),
                  ('bail', (-0.15, 0.0, -0.8), (0.15, 0.95, 0.8), ip)],
        # a tin pan hung by the hole in its handle, its inside to the road
        'pot_2': [('handle', (-0.2, 0.0, -0.25), (0.2, 2.4, 0.25), tin_painter('pd_pan_handle', 'handle')),
                  ('pan', (-0.35, 2.4, -1.4), (0.35, 5.4, 1.4), tin_painter('pd_pan'))],
        'lantern': [('ring', (-0.3, 0.0, -0.3), (0.3, 0.5, 0.3), lantern_painter('pd_lantern_ring', 'ring')),
                    ('cap', (-1.0, 0.5, -1.0), (1.0, 1.2, 1.0), lantern_painter('pd_lantern_cap', 'cap')),
                    ('glass', (-0.9, 1.2, -0.9), (0.9, 3.6, 0.9), lantern_painter('pd_lantern_glass', 'glass')),
                    ('base', (-1.05, 3.6, -1.05), (1.05, 4.1, 1.05), lantern_painter('pd_lantern_base', 'base'))],
    }


def pivot_of(name):
    if name.startswith('pot_'):
        return (RAIL_X, HOOK_Y, POTS_Z[int(name[-1])])
    return LANTERN_HOOK


def dressing():
    """The dressing's model: the hood (its frame, its canvas, its folds and ties, the pot rail and the lantern's arm), the three pots, the
    lantern and its light."""
    fr = frame_painter('pd_frame', 'v')
    rail = frame_painter('pd_eave', {'right': 'u', 'left': 'u', 'top': 'v', 'bottom': 'v'})
    ip = iron_painter('pd_iron')
    h = POST_W / 2
    cubes = []
    # the four posts, standing on the bed's corner posts, up to the eave rails; the eave rails; the ridge pole under the canvas's ridge
    for s, n in ((-1, 'r'), (1, 'l')):
        for i, z in enumerate(POST_Z):
            cubes.append(bx(s * POST_X - h, EAVE_Y[1], z - h, s * POST_X + h, POST_TOP, z + h, f'h_post_{n}{i}', fr))
        cubes.append(bx(s * POST_X - h, EAVE_Y[0], POST_Z[0] - h, s * POST_X + h, EAVE_Y[1], POST_Z[1] + h, f'h_eave_{n}', rail))
    cubes.append(bx(-0.4, RIDGE + SHEET, POST_Z[0] - 0.4, 0.4, RIDGE + SHEET + 0.8, POST_Z[1] + 0.4, 'h_ridge', rail))
    # the canvas's side drops, from under the roof's edge down to the hem
    side = canvas_painter('pd_canvas_side', CANVAS_FRONT, CANVAS_TAIL, hem=0.88)
    top = eave_y(CANVAS_X - SHEET, SHEET)
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(bx(s * (CANVAS_X - SHEET), top, CANVAS_FRONT, s * CANVAS_X, CANVAS_FOOT, CANVAS_TAIL, f'h_side_{n}', side))
    # the folds: the canvas drawn back gathers ahead of its edge, each fold standing a little out from the next, hanging less low
    zs = [CANVAS_FRONT - FOLD_W * (k + 1) for k in range(FOLDS)]
    folds = []
    for k, z0 in enumerate(zs):
        out = 0.25 + 0.2 * (k % 2)
        fp = canvas_painter(f'pd_fold{k}', z0, z0 + FOLD_W, fold=True)
        folds.append((z0, out, fp, k))
        for s, n in ((-1, 'r'), (1, 'l')):
            cubes.append(bx(s * (CANVAS_X + out - FOLD_T), eave_y(CANVAS_X) - 0.3, z0, s * (CANVAS_X + out), -14.2 + 0.6 * k, z0 + FOLD_W,
                            f'h_fold{k}_{n}', fp))
    # the cords round the gathered canvas, at each side, tied to the eave
    tie = cord_painter('pd_tie')
    for s, n in ((-1, 'r'), (1, 'l')):
        cubes.append(bx(s * (CANVAS_X + 0.45), eave_y(CANVAS_X) + 1.2, zs[-1] - 0.1, s * (CANVAS_X + 0.7), eave_y(CANVAS_X) + 1.6,
                        CANVAS_FRONT + 0.1, f'h_tie_{n}', tie))
    # the pot rail along the left side on two iron arms bolted through the canvas to the eave rail; a hook for each pot
    cubes.append(bx(RAIL_X - 0.2, RAIL_Y[0], RAIL_Z[0], RAIL_X + 0.2, RAIL_Y[1], RAIL_Z[1], 'h_pot_rail', ip))
    for i, z in enumerate(ARMS_Z):
        cubes.append(bx(POST_X + h, RAIL_Y[0], z - 0.2, RAIL_X - 0.2, RAIL_Y[1], z + 0.2, f'h_pot_arm{i}', ip))
    for i, z in enumerate(POTS_Z):
        cubes.append(bx(RAIL_X - 0.15, RAIL_Y[1], z - 0.15, RAIL_X + 0.15, HOOK_Y, z + 0.15, f'h_hook{i}', ip))
    lx, ly, lz = LANTERN_HOOK
    cubes.append(bx(POST_X + h, LANTERN_ARM_Y[0], lz - 0.2, lx + 0.35, LANTERN_ARM_Y[1], lz + 0.2, 'h_lantern_arm', ip))
    cubes.append(bx(lx - 0.15, LANTERN_ARM_Y[1], lz - 0.15, lx + 0.15, ly, lz + 0.15, 'h_lantern_hook', ip))
    hood = K.part('hood', (0.0, 0.0, 0.0), cubes)
    # the roof over the back half, the folds' tops gathered on the ridge pole ahead of it, and the bows, front and rear, under the canvas
    roof(hood, CANVAS_FRONT, CANVAS_TAIL, 'h_roof', canvas_painter('pd_roof', CANVAS_FRONT, CANVAS_TAIL))
    for z0, out, fp, k in folds:
        roof(hood, z0, z0 + FOLD_W, f'h_fold{k}_roof', fp, under=SHEET - FOLD_T - 0.1 * (k % 2), thick=FOLD_T, out=out)
    for i, z in enumerate(POST_Z):
        roof(hood, z - h, z + h, f'h_bow{i}', fr, under=SHEET, thick=0.8, out=-POST_W)
    parts = [hood]
    for name, spec in hanging_specs().items():
        px, py, pz = pivot_of(name)
        tag = 'l' if name == 'lantern' else 'p' + name[-1]
        cs = [bx(px + a[0], py + a[1], pz + a[2], px + b[0], py + b[1], pz + b[2], f'{tag}_{nm}', paint) for nm, a, b, paint in spec]
        parts.append(K.part(name, (px, py, pz), cs))
    light = [bx(lx - 0.9, ly + 1.2, lz - 0.9, lx + 0.9, ly + 3.6, lz + 0.9, 'l_light', lantern_painter('pd_lantern_light', 'light'), inflate=0.03),
             bx(lx - 0.25, ly + 2.1, lz - 0.25, lx + 0.25, ly + 3.3, lz + 0.25, 'l_flame', lantern_painter('pd_lantern_flame', 'flame'))]
    parts.append(K.part('lantern_light', LANTERN_HOOK, light))
    return Model(NAME + '_dressing', TEX_W, TEX_H, parts)


def build():
    """(the handcart's own model, its load, the dressing, the rows the handcart uses, the rows used in all): the handcart's packed as
    handcart.py packs it, the dressing packed into the rows below the handcart's last one."""
    cart, load = hc.build()
    used = max(c.uv[1] + c.footprint()[1] for c in list(cart.all_cubes()) + list(load.all_cubes()))
    dress = dressing()
    rows = K.pack(list(dress.all_cubes()), TEX_W, TEX_H - used, who=NAME)
    for c in dress.all_cubes():
        c.uv = (c.uv[0], c.uv[1] + used)
    return cart, load, dress, used, used + rows


def texture(cart, dress):
    """The cart's cubes in the pedlar's paint (the carters' own pictures where it is not repainted), where HandcartMesh puts them; the
    dressing below them."""
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    L = livery()
    done = set()
    for c in cart.all_cubes():
        key = c.share or c.name
        if key in done:
            continue
        done.add(key)
        p = L.get(key) or (hc.SHARED[c.share] if c.share else c.paint)
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0:
                p(img, face, x, y, w, h)
    for c in dress.all_cubes():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0 and c.shows(face):
                c.paint(img, face, x, y, w, h)
    return img

# ================================================================== the swing: as PedlarCartRenderer works it out


def swing_map(name, fore, side):
    """(A, t): part [name] swung [fore], [side] degrees about its hook, in the cart's own model space: rotX(fore) rotZ(side) about it."""
    piv = np.array(pivot_of(name), float)
    A = K.rotx(fore) @ K.rotz(side)
    return A, piv - A @ piv


def plumb(M):
    """(fore, side) that hang a thing straight down when the cart is turned by [M] (model space to the view's; y down in both)."""
    d = M.T @ np.array([0.0, 1.0, 0.0])
    return math.degrees(math.atan2(d[2], d[1])), math.degrees(math.asin(max(-1.0, min(1.0, -d[0]))))


class Swing:
    """The pots' and the lantern's swing, a tick at a time, as PedlarCartRenderer runs it (the same numbers, the same order). [step]
    takes the cart's acceleration this tick (blocks a tick a tick, toward its front and its left), its speed (blocks a tick), where
    plumb is ((fore, side) for each, `plumb`) and the tick; it returns {part: (fore, side)}."""

    def __init__(self, limits, start=None):
        self.lim = limits
        self.a = {n: list(start[n]) if start else [0.0, 0.0] for n in HANGING}
        self.v = {n: [0.0, 0.0] for n in HANGING}

    def step(self, ahead, left, speed, eq, tick):
        for i, n in enumerate(HANGING):
            w2 = (2 * math.pi / PERIOD[n]) ** 2
            push = tuple(w2 * PUSH * math.degrees(max(-ACC_MAX, min(ACC_MAX, acc)) / G) for acc in (ahead, left))
            for ax in (0, 1):
                kick = 0.0
                if speed > 0.005 and jolt_hash(tick, i, ax) < JOLT_P:
                    kick = JOLT * speed * (2 * jolt_hash(tick, i, ax + 2) - 1)
                self.v[n][ax] += -w2 * (self.a[n][ax] - eq[n][ax]) + push[ax] - DAMP * self.v[n][ax] + kick
                self.a[n][ax] += self.v[n][ax]
        for _ in range(2):
            for lo, hi in NEIGHBOURS:
                over = self.a[lo][0] - self.a[hi][0] - self.lim['clash'][lo]
                if over > 0:
                    self.a[lo][0] -= over / 2
                    self.a[hi][0] += over / 2
                    rel = self.v[lo][0] - self.v[hi][0]
                    if rel > 0:
                        mid = (self.v[lo][0] + self.v[hi][0]) / 2
                        self.v[lo][0] = mid - BOUNCE * rel / 2
                        self.v[hi][0] = mid + BOUNCE * rel / 2
            for n in HANGING:
                f, s = self.a[n]
                fe, se = eq[n]
                self.a[n][0], self.v[n][0] = limit(f, self.v[n][0], max(-self.lim['fore'][n], fe - SWING_MAX), min(self.lim['fore'][n], fe + SWING_MAX))
                self.a[n][1], self.v[n][1] = limit(s, self.v[n][1], max(-OUT_MAX, se - SWING_MAX), min(self.lim['in'][n], se + SWING_MAX))
        return {n: tuple(self.a[n]) for n in HANGING}


def limit(a, v, lo, hi):
    """[a] kept within [lo, hi]; knocked back off either end at BOUNCE of the speed it hit it with."""
    if a > hi:
        return hi, (-v * BOUNCE if v > 0 else v)
    if a < lo:
        return lo, (-v * BOUNCE if v < 0 else v)
    return a, v


def jolt_hash(tick, i, k):
    """A number in [0, 1) for this tick, thing and axis (PedlarCartRenderer.jolt: the same integer hash)."""
    x = (tick * 73856093) ^ ((i + 1) * 19349663) ^ ((k + 1) * 83492791)
    x &= 0xFFFFFFFF
    x ^= x >> 13
    x = (x * 0x5BD1E995) & 0xFFFFFFFF
    x ^= x >> 15
    return (x & 0xFFFFFF) / float(0x1000000)

# ================================================================== the self-check


def moved(b, m):
    A, t = m
    return K.Box(b.part, b.cube, A @ b.c + t, b.ax @ A.T, b.h)


def hanging_boxes(dress, name, fore, side):
    return [moved(b, swing_map(name, fore, side)) for b in K.boxes_of(dress, only={name})]


def hood_boxes(dress, name=None):
    """The hood's boxes, but for [name]'s own hook (the hinge it turns on)."""
    own = {'pot_0': 'h_hook0', 'pot_1': 'h_hook1', 'pot_2': 'h_hook2', 'lantern': 'h_lantern_hook'}.get(name)
    return [b for b in K.boxes_of(dress, only={'hood'}) if b.cube != own]


def deepest(bs, others):
    return max((K.box_depth(a, b) for a in bs for b in others), default=-9.0)


def fit_limits(cart, dress):
    """The swing's limits, found by sweeping each thing: how far fore and aft it swings before it meets the frame or the rail (FORE), how
    far inward at any fore within that (SWING_IN), how far one pot may swing toward the next past what the next does (CLASH); each with
    MARGIN kept off it."""
    wheels = [b for spin in range(0, 360, 30) for b in pc.wheel_boxes(cart, spin)]
    fore, inward, clash = {}, {}, {}

    def clear(name, f, s, extra=()):
        bs = hanging_boxes(dress, name, f, s)
        return deepest(bs, hood_boxes(dress, name)) <= 0.0 and deepest(bs, wheels) <= 0.0 and deepest(bs, list(extra)) <= 0.0

    for n in HANGING:
        top = 0.0
        for f in np.arange(0.0, hc.parked_tilt() + FORE_PAST + 0.01, 0.5):
            if not (clear(n, f, 0.0) and clear(n, -f, 0.0)):
                break
            top = float(f)
        fore[n] = max(0.0, top - MARGIN)
        lo = 0.0
        for s in np.arange(0.0, 60.01, 0.5):
            if not all(clear(n, f, s) for f in np.linspace(-fore[n], fore[n], 13)):
                break
            lo = float(s)
        inward[n] = max(0.0, lo - MARGIN)
    for a, b in NEIGHBOURS:
        fm = min(fore[a], fore[b])
        best = -2 * fm
        for d in np.arange(-2 * fm, 2 * fm + 0.01, 0.5):
            ok = True
            for fa in np.linspace(-fm, fm, 11):
                fb = fa - d
                if fb < -fm or fb > fm:
                    continue
                for sa in (-OUT_MAX, 0.0, inward[a]):
                    for sb in (-OUT_MAX, 0.0, inward[b]):
                        if deepest(hanging_boxes(dress, a, fa, sa), hanging_boxes(dress, b, fb, sb)) > 0.0:
                            ok = False
                            break
                    if not ok:
                        break
                if not ok:
                    break
            if not ok:
                break
            best = float(d)
        clash[a] = best - MARGIN
    return {'fore': fore, 'in': inward, 'clash': clash}


def check_contract(cart, load, dress, tex, lim):
    """The pictures; the hood clear of every heap a pedlar's cart carries (produce: a household's goods are a family's move, never the
    pedlar's); and, inside the swing's limits, every hanging thing clear of the hood, the wheels and its neighbours, sampled over the
    whole of them (corners and between); and the limits generous enough: each pot hangs plumb on a cart parked on its shafts."""
    problems = list(K.check_model(dress, tex, (TEX_W, TEX_H), SCALE, NAME))
    worst = {}
    hood = K.boxes_of(dress, only={'hood'})
    for name, goods in pc.check_heaps().items():
        if name in ('household', 'bundles'):
            continue
        d = deepest(pc.load_boxes(load, pc.heap(goods)), hood)
        worst['the load into the hood'] = max(worst.get('the load into the hood', -9.0), d)
        if d > 0.0:
            problems.append(f'the {name} heap reaches {d:.2f} px into the hood')
    worst['a household into the hood (never the pedlar\'s)'] = deepest(pc.load_boxes(load, pc.heap(pc.check_heaps()['household'])), hood)
    wheels = [b for spin in range(0, 360, 30) for b in pc.wheel_boxes(cart, spin)]
    for n in HANGING:
        F, IN = lim['fore'][n], lim['in'][n]
        for f in np.linspace(-F, F, 9):
            for s in np.linspace(-OUT_MAX, IN, 9):
                bs = hanging_boxes(dress, n, f, s)
                for label, others in (('the hood', hood_boxes(dress, n)), ('a wheel', wheels)):
                    d = deepest(bs, others)
                    worst[f'{n} into {label}'] = max(worst.get(f'{n} into {label}', -9.0), d)
                    if d > 0.0:
                        problems.append(f'{n} goes {d:.2f} px into {label} swung ({f:.1f}, {s:.1f})')
    for a, b in NEIGHBOURS:
        fm = min(lim['fore'][a], lim['fore'][b])
        for fa in np.linspace(-fm, fm, 9):
            for fb in np.linspace(-fm, fm, 9):
                if fa - fb > lim['clash'][a]:
                    continue
                for sa in np.linspace(-OUT_MAX, lim['in'][a], 4):
                    for sb in np.linspace(-OUT_MAX, lim['in'][b], 4):
                        d = deepest(hanging_boxes(dress, a, fa, sa), hanging_boxes(dress, b, fb, sb))
                        worst[f'{a} into {b}'] = max(worst.get(f'{a} into {b}', -9.0), d)
                        if d > 0.0:
                            problems.append(f'{a} and {b} meet ({d:.2f} px) at fore {fa:.1f} and {fb:.1f}')
    tilt = hc.parked_tilt()
    for n in HANGING:
        if lim['fore'][n] < tilt + 8.0:
            problems.append(f'{n} swings {lim["fore"][n]:.1f} fore and aft at most: not to plumb on a cart parked on its shafts ({tilt:.1f}) and a swing past it')
        if lim['in'][n] < 10.0:
            problems.append(f'{n} swings {lim["in"][n]:.1f} inward at most: it would lie on the canvas on a cart leaning a little')
    K.must_be_clean(sorted(set(problems))[:30], NAME)
    return worst

# ================================================================== the Kotlin table


KOTLIN_HEADER = '''// generated by art/scripts/pedlar_cart.py, edit the script, not this file
package io.github.vaspyyy.guildmark.client.cart

import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
'''

KOTLIN_OBJECT = '''
/**
 * The village pedlar's cart's art (art/scripts/pedlar_cart.py): the carters' handcart as a pedlar keeps it, and its dressing. Model space is
 * the handcart's (HandcartMesh): pixels, y down, the origin the middle of the axle, the shafts toward -z, its left +x.
 *
 * - [TEXTURE] paints the handcart's own model (HandcartMesh's layer, the same UVs) in the pedlar's paint: blue boards lined in ochre with
 *   painted roses, ochre wheels lined in red. The load is still drawn from the handcart's texture.
 * - [dressing] is a second layer on the same texture (box UV on [TEX_WIDTH] x [TEX_HEIGHT], shipped at 2x): `hood` (still: the striped canvas
 *   hood on four posts, half drawn back, the pot rail and the lantern's arm), the [HANGING] things (each turned about its hook, [HOOKS] (x, y, z)
 *   in that order) and `lantern_light` (the lantern's panes lit and its flame, about the lantern's hook: drawn over it, full bright, after dark).
 * - The swing (PedlarCartRenderer): each hanging thing turns rotX(fore) rotZ(side) about its hook in the cart's own frame (fore > 0 its foot
 *   toward the tail, side > 0 toward the cart's middle), a pendulum of period [PERIOD] ticks, damped by [DAMP] of its speed a tick, pulled toward
 *   plumb and pushed by [PUSH] of the cart's acceleration (at most [ACC_MAX]) over [G] (blocks a tick a tick), jolted by the road ([JOLT_P],
 *   [JOLT]); kept within [FORE] either
 *   way and [SWING_IN] inward, a pot's fore at most [CLASH] more than the next one's, and [SWING_MAX] off plumb; knocked back at [BOUNCE].
 *   The script proves nothing goes into anything inside those.
 *
 * See guildmark-notes/living-world/villages/guide/vl_v12a.md.
 */
object PedlarCartMesh {
    const val TEX_WIDTH = @TEX_W@
    const val TEX_HEIGHT = @TEX_H@

    /** The handcart's model in the pedlar's paint, and the dressing (one texture). */
    const val TEXTURE = "@TEXTURE@"

    /** The hanging things, by part name; the lantern is the last. */
    val HANGING = listOf(@HANGING@)

    /** Each hanging thing's hook, its pivot: (x, y, z) triples, px. */
    val HOOKS = floatArrayOf(@HOOKS@)

    /** Each one's period (ticks), and how far it swings fore and aft at most and inward at most (degrees, the cart's own frame). */
    val PERIOD = floatArrayOf(@PERIOD@)
    val FORE = floatArrayOf(@FORE@)
    val SWING_IN = floatArrayOf(@SWING_IN@)

    /** How much more a pot's fore may be than the next one's along the rail (degrees): pot k against pot k + 1. */
    val CLASH = floatArrayOf(@CLASH@)

    const val DAMP = @DAMP@
    const val G = @G@
    const val PUSH = @PUSH@
    const val ACC_MAX = @ACC_MAX@
    const val JOLT_P = @JOLT_P@
    const val JOLT = @JOLT@
    const val BOUNCE = @BOUNCE@
    const val SWING_MAX = @SWING_MAX@
    const val OUT_MAX = @OUT_MAX@

    /** The hood, the pots, the lantern and its light. */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun dressing(): LayerDefinition {
@MESH@
    }
}
'''


def kotlin_source(dress, lim):
    tok = {
        'TEX_W': str(TEX_W), 'TEX_H': str(TEX_H), 'TEXTURE': TEXTURE,
        'HANGING': ', '.join(f'"{n}"' for n in HANGING),
        'HOOKS': K.kt_list([v for n in HANGING for v in pivot_of(n)]),
        'PERIOD': K.kt_list([PERIOD[n] for n in HANGING]),
        'FORE': K.kt_list([lim['fore'][n] for n in HANGING]), 'SWING_IN': K.kt_list([lim['in'][n] for n in HANGING]),
        'CLASH': K.kt_list([lim['clash'][a] for a, _ in NEIGHBOURS]),
        'DAMP': K.kt_float(DAMP), 'G': K.kt_float(G), 'PUSH': K.kt_float(PUSH), 'ACC_MAX': K.kt_float(ACC_MAX), 'JOLT_P': K.kt_float(JOLT_P), 'JOLT': K.kt_float(JOLT), 'BOUNCE': K.kt_float(BOUNCE),
        'SWING_MAX': K.kt_float(SWING_MAX), 'OUT_MAX': K.kt_float(OUT_MAX), 'MESH': dress.kotlin().rstrip('\n'),
    }
    body = KOTLIN_OBJECT
    for k, v in tok.items():
        body = body.replace('@' + k + '@', v)
    assert '@' not in body.replace('@Suppress', ''), 'a token is left in the template'
    head = KOTLIN_HEADER
    if 'CubeDeformation(' not in body:
        head = head.replace('import net.minecraft.client.model.geom.builders.CubeDeformation\n', '')
    if 'EnumSet.' in body:
        head += 'import net.minecraft.core.Direction\nimport java.util.EnumSet\n'
    return head + body

# ================================================================== writing it all


def write_all(assets=ASSETS, kotlin_dir=KOTLIN, model_dir=MODELS):
    """Everything the script owns: the texture, the Kotlin table and the Blockbench file. Returns (cart, load, dress, tex, limits, worst)."""
    cart, load, dress, used, rows = build()
    tex = texture(cart, dress)
    lim = fit_limits(cart, dress)
    worst = check_contract(cart, load, dress, tex, lim)
    os.makedirs(os.path.join(assets, os.path.dirname(TEXTURE)), exist_ok=True)
    tex.save(os.path.join(assets, TEXTURE))
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'PedlarCartMesh.kt'), 'w') as f:
        f.write(kotlin_source(dress, lim))
    os.makedirs(model_dir, exist_ok=True)
    small = tex.resize((TEX_W, TEX_H), Image.LANCZOS)
    both = Model(NAME, TEX_W, TEX_H, cart.parts + dress.parts)
    with open(os.path.join(model_dir, NAME + '.bbmodel'), 'w') as f:
        f.write(K.steady_uuids(json.dumps(both.bbmodel(small, NAME)), NAME))
    check_written(cart, dress, lim, assets, kotlin_dir, model_dir)
    return cart, load, dress, tex, lim, worst


def check_written(cart, dress, lim, assets, kotlin_dir, model_dir):
    problems = []
    png = Image.open(os.path.join(assets, TEXTURE))
    if png.size != (TEX_W * SCALE, TEX_H * SCALE) or png.mode != 'RGBA':
        problems.append(f'{TEXTURE} is {png.size} {png.mode}')
    src = open(os.path.join(kotlin_dir, 'PedlarCartMesh.kt')).read()
    names = re.findall(r'addOrReplaceChild\("([a-z_0-9]+)"', src)
    if names != K.all_names(dress):
        problems.append(f'the Kotlin has other parts than the dressing: {names}')
    offs = re.findall(r'texOffs\((\d+), (\d+)\)', src)
    if [tuple(int(v) for v in o) for o in offs] != [tuple(int(v) for v in c.uv) for c in dress.all_cubes()]:
        problems.append('the Kotlin places the cubes elsewhere in the texture')
    for key, vals in (('HOOKS', [v for n in HANGING for v in pivot_of(n)]), ('FORE', [lim['fore'][n] for n in HANGING]),
                      ('SWING_IN', [lim['in'][n] for n in HANGING]), ('CLASH', [lim['clash'][a] for a, _ in NEIGHBOURS])):
        if f'val {key} = floatArrayOf({K.kt_list(vals)})' not in src:
            problems.append(f'the Kotlin\'s {key} is not {vals}')
    bb = json.load(open(os.path.join(model_dir, NAME + '.bbmodel')))
    if len(bb['elements']) != len(list(cart.all_cubes())) + len(list(dress.all_cubes())):
        problems.append(f'{NAME}.bbmodel has {len(bb["elements"])} cubes')
    K.must_be_clean(problems, NAME + ' (written)')


def report(cart, dress, lim, worst):
    lines = [
        f'{NAME}: texture {TEX_W} x {TEX_H} declared ({TEX_W * SCALE} x {TEX_H * SCALE} shipped), the handcart\'s '
        f'{len(list(cart.all_cubes()))} cubes repainted where the pedlar has, the dressing {len(list(dress.all_cubes()))} cubes in '
        f'{len(list(dress.all_parts()))} parts',
        f'  hood: posts at x +/-{POST_X:g}, z {POST_Z}; eaves y {EAVE_Y}; ridge {RIDGE:g}, slope {SLOPE:.1f}; canvas z {CANVAS_FRONT:g}..{CANVAS_TAIL:g}',
        '  hooks: ' + ', '.join(f'{n} {pivot_of(n)}' for n in HANGING),
        '  limits (degrees): ' + ', '.join(f'{n} fore {lim["fore"][n]:.1f} in {lim["in"][n]:.1f}' for n in HANGING)
        + '; clash ' + ', '.join(f'{a}/{b} {lim["clash"][a]:.1f}' for a, b in NEIGHBOURS),
        '  closest (px, < 0 is a gap): ' + ', '.join(f'{k} {v:.2f}' for k, v in sorted(worst.items())),
    ]
    return '\n'.join(lines)

# ================================================================== previews


GOODS = [('crate', 'apple'), ('basket', 'bread'), ('crate', 'carrot'), ('sack', None), ('basket', 'bread'), ('crate', 'potato'),
         ('crate', 'beetroot'), ('basket', 'bread')]


class Scene:
    def __init__(self, cart, load, dress, tex, lim):
        self.cart, self.load, self.dress, self.tex, self.lim = cart, load, dress, tex, lim
        self.hct = hc.texture(cart, load)
        self.atlas = hc.Atlas(self.hct)
        for _, item in GOODS:
            if item:
                self.atlas.extra(item)

    def textures(self):
        return {'pd': self.tex, 'hc': self.hct, 'atlas': self.atlas.image()}

    def faces(self, goods=GOODS, pitch=0.0, roll=0.0, spin=10.0, swings=None, lit=False, carters=False, T=(0.0, 0.0, 0.0)):
        """The cart as the game draws it: turned by [pitch] and [roll], its wheels by [spin], the load heaped, and (not [carters]) the
        dressing, each hanging thing at [swings] ({part: (fore, side)}, the cart's frame; plumb where not given), the lantern [lit]."""
        cf, M = pc.cart_faces(self.cart, 'hc' if carters else 'pd', pitch, roll, spin)
        out = cf + pc.load_faces(self.load, 'hc', pc.heap(goods), M, 0.0, 0.0, self.atlas)
        if not carters:
            out += K.faces_of(self.dress, 'pd', M=M, only={'hood'})
            eq = plumb(M)
            for n in HANGING:
                A, t = swing_map(n, *((swings or {}).get(n, eq)))
                out += K.faces_of(self.dress, 'pd', M=M @ A, T=M @ t, only={n})
                if n == 'lantern' and lit:
                    out += K.faces_of(self.dress, 'pd', M=M @ A, T=M @ t, only={'lantern_light'}, glow=True)
        T = np.asarray(T, float)
        for f in out:
            f.A, f.B, f.D = f.A + T, f.B + T, f.D + T
        return out

    def shot(self, faces, view, night=False):
        img = K.render(faces, self.textures(), view, ground=hc.R, shadows=((0.0, -2.0, 13.0, 16.0, 0.35),))
        if not night:
            return img
        a = np.asarray(img.convert('RGB'), float) * np.array([0.30, 0.34, 0.52])
        glow = [f for f in faces if f.glow]
        if glow:
            # the lit panes over the darkened picture: rendered with everything else to hide what is behind, and kept where they show
            mark = K.render([f if f.glow else K.Face(f.A, f.B, f.D, f.rect, f.face, f.n, 'black') for f in faces],
                            {**self.textures(), 'black': Image.new('RGBA', self.tex.size, (0, 0, 0, 255))}, view, ground=hc.R)
            b = np.asarray(mark.convert('RGB'), float)
            on = b.sum(axis=2) > 120
            a[on] = b[on]
        return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


V34 = dict(yaw=-35, pitch=-24, scale=5.4, center=(0, -9, -4), size=(380, 320))
VRIGHT = dict(yaw=-90, pitch=0, scale=5.4, center=(0, -10, -4), size=(380, 320))
VLEFT = dict(yaw=90, pitch=-6, scale=5.4, center=(0, -10, -4), size=(380, 320))
VBACK = dict(yaw=155, pitch=-14, scale=6.4, center=(0, -10, 0), size=(320, 320))
VTOP = dict(yaw=-20, pitch=-70, scale=5.4, center=(0, -8, -3), size=(320, 320))


def views_sheet(sc):
    tiles = []
    for label, kw in (('the pedlar\'s cart', dict()), ('the carters\' handcart (handcart.py, unchanged)', dict(carters=True))):
        for vname, v in (('3/4', V34), ('right side', VRIGHT), ('left side', VLEFT), ('rear', VBACK), ('above', VTOP)):
            tiles.append(K.caption(sc.shot(sc.faces(**kw), K.View(**v)), f'{label}: {vname}'))
    tilt = hc.parked_tilt()
    tiles.append(K.caption(sc.shot(sc.faces(pitch=-tilt), K.View(**VLEFT)), f'parked on its shafts ({tilt:.1f}): the pots hang plumb'))
    tiles.append(K.caption(sc.shot(sc.faces(pitch=-tilt, lit=True), K.View(**V34), night=True), 'after dark: the lantern lit'))
    tiles.append(K.caption(sc.shot(sc.faces(goods=[]), K.View(**V34)), 'empty'))
    tiles.append(K.caption(sc.shot(sc.faces(roll=8.0), K.View(**VBACK)), 'leaning 8 degrees: they hang plumb'))
    lim = sc.lim
    sw = {n: (lim['fore'][n] * (1 if i % 2 else -1) * 0.8, lim['in'][n]) for i, n in enumerate(HANGING)}
    sw['pot_1'] = (sw['pot_0'][0] - lim['clash']['pot_0'], sw['pot_1'][1])
    tiles.append(K.caption(sc.shot(sc.faces(swings=sw), K.View(**VLEFT)), 'swung hard in to the canvas, at the limits'))
    return K.sheet(tiles, 5, 'pedlar_cart.py: the village pedlar\'s cart (handcart_pedlar) beside the carters\' handcart, the same load on both')


def close_sheet(sc):
    v = [dict(yaw=60, pitch=-10, scale=12.0, center=(10, -15, 4), size=(380, 300)),
         dict(yaw=120, pitch=-14, scale=12.0, center=(9, -16, -9), size=(380, 300)),
         dict(yaw=-150, pitch=-20, scale=9.0, center=(0, -14, 6), size=(380, 300)),
         dict(yaw=180, pitch=-4, scale=9.0, center=(0, -9, 8), size=(380, 300))]
    tiles = [K.caption(sc.shot(sc.faces(), K.View(**v[0])), 'the pots on their rail: an iron pot, a copper kettle, a tin pan'),
             K.caption(sc.shot(sc.faces(lit=True), K.View(**v[1]), night=True), 'the lantern on its arm, lit'),
             K.caption(sc.shot(sc.faces(), K.View(**v[2])), 'the hood from behind: the canvas over the back half'),
             K.caption(sc.shot(sc.faces(goods=[]), K.View(**v[3])), 'the tailboard: the pedlar\'s roses')]
    return K.sheet(tiles, 2, 'up close')


def pulled_gif(sc, path):
    """2 x 2: it sets off, rolls at the pulled walk, comes to a stop and is let go onto its shafts; the wheels turn by how far it goes, the
    pots and the lantern swing back as it starts, jolt and knock together as it rolls, swing forward as it stops and to plumb as it tips
    onto its shafts, settling after (Swing: PedlarCartRenderer's own, tick by tick)."""
    views = [dict(yaw=70, pitch=-12, scale=4.6), dict(yaw=-35, pitch=-24, scale=4.6), dict(yaw=120, pitch=-10, scale=7.0),
             dict(yaw=155, pitch=-14, scale=5.2)]
    centres = [(0.0, -10.0, -4.0), (0.0, -9.0, -4.0), (9.0, -15.0, 2.0), (0.0, -10.0, 0.0)]
    tilt = hc.parked_tilt()
    pitch_of = lambda k: -5.8 if k < 84 else -5.8 - (tilt - 5.8) * K.smooth((k - 84) / 8.0)
    M0, _ = hc.cart_pose(-5.8, 0.0)
    eq0 = plumb(M0)
    sw = Swing(sc.lim, {n: eq0 for n in HANGING})
    z = spin = 0.0
    v_o = 0.0
    frames = []
    for k in range(150):
        speed = 3.4 * (K.smooth(k / 18.0) - K.smooth((k - 58) / 22.0)) / 20.0      # blocks a tick
        z -= speed * 16.0
        spin += math.degrees(speed * 16.0 / hc.R)
        pitch = pitch_of(k)
        M, _ = hc.cart_pose(pitch, 0.0)
        eq = plumb(M)
        swings = sw.step(speed - v_o, 0.0, speed, {n: eq for n in HANGING}, k)
        v_o = speed
        tiles = []
        for v, c in zip(views, centres):
            view = K.View(v['yaw'], v['pitch'], v['scale'], (c[0], c[1], c[2] + z), (300, 240))
            tiles.append(sc.shot(sc.faces(spin=-spin, pitch=pitch, swings=swings, T=(0.0, 0.0, z)), view))
        frames.append(K.sheet(tiles, 2))
    return K.save_gif(frames, path, 50)


def texture_sheet(tex):
    big = tex.resize((tex.width * 2, tex.height * 2), Image.NEAREST)
    out = Image.new('RGB', big.size, (30, 32, 40))
    out.paste(big, (0, 0), big)
    return out


def previews(sc, out):
    os.makedirs(out, exist_ok=True)
    paths = []
    for name, fn in (('views', views_sheet), ('close', close_sheet)):
        p = os.path.join(out, f'pedlar_cart_{name}.png')
        fn(sc).save(p)
        paths.append(p)
    p = os.path.join(out, 'pedlar_cart_texture.png')
    texture_sheet(sc.tex).save(p)
    paths.append(p)
    paths.append(pulled_gif(sc, os.path.join(out, 'pedlar_cart_pulled.gif')))
    return paths


def main(argv):
    arg = lambda i, d: argv[i] if len(argv) > i and argv[i] != '-' else d
    assets, kotlin_dir, model_dir = arg(1, ASSETS), arg(2, KOTLIN), arg(3, MODELS)
    prev = arg(4, None)
    cart, load, dress, tex, lim, worst = write_all(assets, kotlin_dir, model_dir)
    print(report(cart, dress, lim, worst))
    if prev:
        for p in previews(Scene(cart, load, dress, tex, lim), prev):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
