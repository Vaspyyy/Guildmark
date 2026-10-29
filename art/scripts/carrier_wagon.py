"""
The Carrier's Wagon (Trade X2, living-world/trader/design.md Part 3 and section 10): the caravans' wagon (art/scripts/wagon.py,
imported, and what it writes is left as it is) in its owner's colour, with a name board on each side, a cushion for the guard on the
bench and a pad on the tailboard, and the owner's pennant on the staff. It is a variant of wagon.py through a flag, a model name and a
texture of its own: CarrierWagonArt.table() (the one call) hands CarrierWagonMesh to the trade's wagon renderers, the model is
`carrier_wagon` and the texture `textures/entity/trade/carrier_wagon.png`. The lantern is the caravans' own, lit at dusk as theirs is.

    python3 -B art/scripts/carrier_wagon.py [assets-dir [kotlin-dir [art/models [preview-dir [game assets/minecraft]]]]]

('-' for any of them: the repo's own.) Model space is WagonMesh's: pixels, y down, the origin the ground under the middle of the back
axle, the wagon's front toward -z, its right toward -x.

ONE TEXTURE, AT THE WAGON'S OWN LAYOUT (512 x 256 declared, shipped at 2x):
- Over the wagon's own UVs it holds THE PAINT: what the owner's colour covers, painted white and grey so that the tint gives the colour,
  and clear everywhere else. The box's sides in panels between the stakes, each lined in a paler shade; the front board; the dash's face
  with a wheel in a roundel; the tailboard's outer face (the one seen when it is shut) with the same wheel; and the wheels' spokes, the
  felloes' faces inside the tyres and the naves between their bands. Chipped and worn at the edges to the caravans' wood under it. The
  iron, the stakes, the bench, the floor, the tilt and the shafts stay the caravans'. WagonRenderer draws the wagon's model a second
  time with it, after the wagon (order 1, the same pose: coplanar, drawn over), tinted with the wagon's colour, whenever the wagon has
  one: a trader's led wagon (D3) and a House's wagon (G).
- At the caravans' pennant's own UVs it holds THE PENNANT CLOTH (white, a hem, a wheel on the first piece, the swallowtail cut): a
  House's pennant, which WagonRenderer draws from the wagon's own pennant parts tinted with the House's colour, is that cloth.
- In the rows under the wagon's last one, THE DRESSING (CarrierWagonMesh.dressing, one layer; ReinRenderer draws it on a trader's wagon,
  untinted but for the pennant):

  part         pivot                             what it is
  board_r      (-11.0, -22.4, -25.25)            the name board on the right side, between the second and third stakes: a cream field
                                                 in a black frame with gilt beads; the owner's name is written on the field at NAMES 0
  board_l      (11.0, -22.4, -37.75)             the same on the left side, between the first and second stakes (the water barrel hangs
                                                 between the second and third there, the brake's bracket on the right between the first two)
  seat_bench   (-6.5, -27.5, -44.6)              the guard's cushion on the bench's right (Wagon.DRIVER): buttoned oxblood leather
  seat_tail    (0, -19, 14)                      the pad on the tailboard (Wagon.TAIL), modelled where it lies with the board let down:
                                                 a folded blanket. ReinRenderer draws it only while the board is down
  flag_0..3    (0, -61.3, RIPPLES z)             the owner's pennant off the staff's top, white (tinted with the owner's colour), a wheel
                                                 on its first segment and a swallowtail; segment k turns about the vertical at RIPPLES k
                                                 (x, z), the next riding on it

THE NUMBERS ReinRenderer reads (CarrierWagonMesh):
  NAMES      for each board: the middle of its field's outer face (x, y, z px), its side (-1 the right, 1 the left), the room for the
             name (wide, high, in blocks): the name is scaled to fit the field inside its frame
  RIPPLES    the pennant's hinges, from the staff outward
  STAFF_RUN  how far below its place the pennant is when it begins to run up the staff: its foot then just clears the tilt's top

Clearances it checks: the boards and the seats against every cube of the wagon (the front wheels steered 55 degrees either way and
rocked 8, the brake lever over its whole swing in five steps, the tailboard let down: 225 poses); the pennant against the staff, its
knob and the tilt over its whole ripple (each segment's swing at its largest, every phase) and all the way up its run; that nothing
floats (each board on the box's side, the cushion on the bench, the pad on the tailboard, the pennant's sleeve at the staff); that the
paint lies only on texels the wagon itself paints, and only on the faces it is meant for; and that wagon.png, wagon_glow.png and
WagonMesh.kt on disk are still what wagon.py writes.
"""
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from modelkit import Model
import carrier_kit as K
import carrier_paint as P
import handcart as hc
import townsfolk_tools as tt
import wagon as W
from townsfolk_tools import uv, mix

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
KOTLIN = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/trade')
MODELS = os.path.join(ROOT, 'art/models')

NAME = 'carrier_wagon'
TEX_W, TEX_H = W.TEX_W, W.TEX_H            # the wagon's own layout: its cubes keep their places
SCALE = W.SCALE
TEXTURE = 'textures/entity/trade/carrier_wagon.png'

# ================================================================== geometry (px, WagonMesh's model space)

SIDE_X = W.BOX_X                          # 11.0: the box's sides' outer faces, which the boards lie on
STAKE_HALF = 0.6
GAP = 0.15                                # between a board and the stakes either side of it
PLANK_T = 0.5                             # the board's plank; its frame stands this much further out
FRAME_T = 0.3
FRAME_W = 0.55
BOARD_Y = (-25.3, -19.5)                  # under the bench (its underside y -26), over the brake's rod (its top -18.9)
BOARDS = {                                # side: (-1 the right, 1 the left), between which stakes
    'board_r': (-1, (W.STAKE_Z[1] + STAKE_HALF + GAP, W.STAKE_Z[2] - STAKE_HALF - GAP)),
    'board_l': (1, (W.STAKE_Z[0] + STAKE_HALF + GAP, W.STAKE_Z[1] - STAKE_HALF - GAP)),
}
NAME_MARGIN = (0.6, 0.7)                  # px kept clear inside the frame, along and up


def field(name):
    """A board's field (the plank's face inside its frame): (z0, z1, y0, y1) and the x of its outer face."""
    side, (z0, z1) = BOARDS[name]
    return (z0 + FRAME_W, z1 - FRAME_W, BOARD_Y[0] + FRAME_W, BOARD_Y[1] - FRAME_W), side * (SIDE_X + PLANK_T)


def names():
    """NAMES, board by board: (x, y, z, side, wide, high), the room in blocks."""
    out = []
    for n in ('board_r', 'board_l'):
        (z0, z1, y0, y1), x = field(n)
        side = BOARDS[n][0]
        wide = (z1 - z0 - 2 * NAME_MARGIN[0]) / 16.0
        high = (y1 - y0 - 2 * NAME_MARGIN[1]) / 16.0
        out.append((x, (y0 + y1) / 2.0, (z0 + z1) / 2.0, float(side), round(wide, 4), round(high, 4)))
    return out


NAMES = names()

# The guard's cushion on the bench's right (the guard's hips at Wagon.SEAT_DRIVER: (-6.5, -27.5, -45.5)), and the pad at the end of the
# let-down tailboard (Wagon.SEAT_TAIL: the hips at (0, -19, 17), facing back, on its edge)
CUSHION = ((-10.7, -2.3), (-47.3, -41.9), 0.55, 0.25)      # x, z, the base's thickness, the crown's
PAD = ((-4.6, 4.6), (12.0, 16.0), 0.6, 0.3)
TAIL_TOP = W.FLOOR_TOP                    # the let-down tailboard's top face

# The pennant: a card 0.3 thick flying back from the staff's top (the staff at (0, *, -38.5), 0.9 square, its knob 1.4 square from y
# -62.6 to -61.6), its hoist 4.2 deep just under the knob, narrowing to 2.4 at its tail, the swallowtail cut 2.4 deep; four segments
STAFF = (W.PENNANT_FOOT[0], W.PENNANT_FOOT[2])
KNOB_BOTTOM = W.PENNANT_FOOT[1] - W.PENNANT_TOP     # -61.6
FLAG_X = (-0.15, 0.15)
HOIST = (KNOB_BOTTOM + 0.3, KNOB_BOTTOM + 4.5)     # -61.3 .. -57.1
TAIL_Y = (HOIST[0] + 0.9, HOIST[1] - 0.9)
FLAG_Z = (STAFF[1] + 0.6, -34.5, -30.6, -26.7, -22.8)
NOTCH = 2.4
RIPPLES = [STAFF] + [(STAFF[0], z) for z in FLAG_Z[1:-1]]
RIPPLE = (0.22, 0.36, 0.5)                # ReinRenderer's swing for each segment (radians at full size), the last for those after
SIZE_MAX = 1.0                            # WagonClient's flapSize at most
TILT_TOP = -50.27                         # the tilt's ridge over the middle (its strips' tops; checked against the wagon's boxes)
STAFF_RUN = round(TILT_TOP - 0.45 - HOIST[1], 2)   # furled, the pennant's foot 0.45 over the ridge


def flag_edges(z):
    """The pennant's top and bottom (y) at [z]: straight from the hoist to the tail."""
    t = (z - FLAG_Z[0]) / (FLAG_Z[-1] - FLAG_Z[0])
    return HOIST[0] + (TAIL_Y[0] - HOIST[0]) * t, HOIST[1] + (TAIL_Y[1] - HOIST[1]) * t


def in_flag(y, z):
    t, b = flag_edges(z)
    mid, half = (t + b) / 2.0, np.maximum(0.1, (b - t) / 2.0)
    notch = z > FLAG_Z[-1] - NOTCH * (1.0 - np.abs(y - mid) / half)
    return (y >= t) & (y <= b) & ~notch & (z >= FLAG_Z[0]) & (z <= FLAG_Z[-1])

# ================================================================== colours

PAINT = (222, 222, 222); PAINT_D = (150, 150, 150); PAINT_L = (246, 246, 246)    # the owner's colour, tinted
LINING = (255, 255, 255); LINING_D = (120, 120, 120)
FIELD = (226, 210, 166); FIELD_D = (184, 164, 118); FIELD_L = (244, 232, 196)    # the board's field: cream, for dark letters
FRAME = (46, 42, 40); FRAME_D = (24, 22, 22); FRAME_L = (88, 82, 76)            # its frame: black paint
GILT = (218, 172, 76); GILT_D = (142, 102, 36); GILT_L = (252, 226, 146)
OX = (112, 38, 32); OX_D = (66, 20, 18); OX_L = (160, 72, 58)                    # oxblood leather
PIPING = (176, 132, 82)
BLANKET = (108, 96, 84); BLANKET_D = (70, 62, 54); BLANKET_L = (148, 136, 120)
STRIPE_A = (214, 200, 160); STRIPE_B = (146, 48, 38)
CLOTH = P.WOOL; CLOTH_D = P.WOOL_D; CLOTH_L = P.WOOL_L                           # the pennant, tinted
IRON_D, IRON_L = (36, 36, 44), (140, 142, 156)

# ================================================================== painting helpers


def coat(w, h, rng, along='u', n=2, base=PAINT, dark=PAINT_D, light=PAINT_L, grain=0.24):
    """Paint over boards (grey, to take the grain from): the paint's tone, the grain and seams showing through it a little."""
    wood, _ = hc.boards(w, h, rng, n, along, (150, 150, 150), (96, 96, 96), (196, 196, 196), knots=1)
    lum = wood[..., :3].mean(axis=2)
    m = lum.mean() if lum.size else 1.0
    k = np.clip((lum - m) / max(m, 1.0), -1, 1)
    a = np.zeros((h, w, 4))
    a[..., :3] = np.where(k[..., None] < 0, mix(base, dark, (-k * grain * 2.2)[..., None].clip(0, 1)),
                          mix(base, light, (k * grain * 1.6)[..., None].clip(0, 1)))
    a[..., :3] *= (1 + rng.normal(0, 0.016, (h, w, 1)))
    a[..., 3] = 255
    return a


def knock(a, rng, n, edge=0.22, top=0.0):
    """Paint knocked off near the edges (the caravans' wood shows: clear texels), and worn off along the top [top] of the face."""
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    for _ in range(n if min(w, h) >= 3 else 0):
        if rng.random() < 0.8:
            side = rng.integers(4)
            cx = rng.uniform(0, w) if side < 2 else (rng.uniform(0, edge) * w if side == 2 else w - rng.uniform(0, edge) * w)
            cy = (rng.uniform(0, edge) * h if side == 0 else h - rng.uniform(0, edge) * h) if side < 2 else rng.uniform(0, h)
        else:
            cx, cy = rng.uniform(0, w), rng.uniform(0, h)
        r = rng.uniform(0.6, 1.4)
        a[..., 3] = np.where(np.hypot((xs + 0.5 - cx) / r, (ys + 0.5 - cy) / (r * rng.uniform(0.5, 1.0))) < 1, 0, a[..., 3])
    if top > 0:
        V = (ys + 0.5) / h
        a[..., 3] = np.where((V < top) & (rng.random((h, w)) < 0.5), 0, a[..., 3])
    return a


def line(a, x0, y0, x1, y1, col, dark=None):
    """A one-texel line round the texel rect [x0, x1) x [y0, y1), a darker one under it and to its right (a lining that catches the light)."""
    h, w = a.shape[:2]
    x0, y0, x1, y1 = max(0, int(x0)), max(0, int(y0)), min(w, int(x1)), min(h, int(y1))
    if x1 - x0 < 3 or y1 - y0 < 3:
        return a
    m = np.zeros((h, w), bool)
    m[y0, x0:x1] = m[y1 - 1, x0:x1] = True
    m[y0:y1, x0] = m[y0:y1, x1 - 1] = True
    if dark is not None:
        s = np.zeros((h, w), bool)
        if y0 + 1 < h:
            s[y0 + 1, x0 + 1:x1 - 1] = True
        s[y0 + 1:y1 - 1, x0 + 1] = True
        a[..., :3] = np.where((s & ~m)[..., None], mix(a[..., :3], dark, 0.5), a[..., :3])
    a[..., :3] = np.where(m[..., None], np.array(col, float) * (1 + 0 * a[..., :3]), a[..., :3])
    return a


def wheel_sign(a, cu, cv, r, ring, ground, mark, rng=None):
    """The trader's wheel in a roundel at face coords (cu, cv), [r] texels: a [ring], a [ground] inside it, the hub and three spokes [mark]."""
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    dx, dy = xs + 0.5 - cu * w, ys + 0.5 - cv * h
    d = np.hypot(dx, dy)
    ang = np.arctan2(dy, dx)
    rim = (d <= r) & (d > r * 0.7)
    inside = d <= r * 0.7
    spokes = inside & (np.abs(np.sin(ang * 3.0)) < 0.32) | (d <= r * 0.26)
    a[..., :3] = np.where(inside[..., None], np.array(ground, float), a[..., :3])
    a[..., :3] = np.where((rim | spokes)[..., None], np.array(mark if ring is None else ring, float), a[..., :3])
    a[..., :3] = np.where(spokes[..., None], np.array(mark, float), a[..., :3])
    return a

# ================================================================== the paint: what the owner's colour covers, at the wagon's own UVs


def side_paint(key, face, z_of_u):
    """A box side's outer [face]: panels between the stakes, each lined, the stakes' places clear (the caravans' iron under them shows)."""
    def fn(f, w, h, rng):
        if f != face:
            return None
        U, V = uv(w, h)
        a = coat(w, h, rng, 'u', 3)
        z = z_of_u(U)
        clear = np.zeros((h, w), bool)
        for sz in W.STAKE_Z:
            clear |= np.abs(z - sz) < STAKE_HALF + 0.75
        a[..., 3] = np.where(clear, 0, a[..., 3])
        # a lining round each panel between two stakes
        zs = [W.BOX_FRONT] + list(W.STAKE_Z) + [W.BOX_BACK]
        u_of_z = lambda zz: float(np.interp(zz, [z_of_u(0.0), z_of_u(1.0)], [0.0, 1.0])) if z_of_u(0.0) < z_of_u(1.0) else \
            float(np.interp(zz, [z_of_u(1.0), z_of_u(0.0)], [1.0, 0.0]))
        for za, zb in zip(zs[:-1], zs[1:]):
            lo = za + (STAKE_HALF + 0.75 if za != W.BOX_FRONT else 0.0)
            hi = zb - (STAKE_HALF + 0.75 if zb != W.BOX_BACK else 0.0)
            if hi - lo < 3.0:
                continue
            x0, x1 = sorted((u_of_z(lo) * w, u_of_z(hi) * w))
            line(a, round(x0) + 2, 2, round(x1) - 2, h - 2, LINING, LINING_D)
        a = knock(a, rng, 26, top=0.1)
        return tt.shade_edges(a, 0.12)
    return tt.painter(key, fn)


def board_face_paint(key, face, irons=(), sign=None, n=2):
    """A painted board's [face] (the front board, the dash, the tailboard): lined, the irons' places clear, the wheel at [sign] (cu, cv)."""
    def fn(f, w, h, rng):
        if f != face:
            return None
        U, V = uv(w, h)
        a = coat(w, h, rng, 'u', n)
        for c in irons:
            a[..., 3] = np.where(np.abs(U - c) < 0.03, 0, a[..., 3])
        x0 = (int(round(0.07 * w)) if irons else 0) + 2
        line(a, x0, 2, w - x0, h - 2, LINING, LINING_D)
        if sign is not None:
            r = min(h * 0.32, w * 0.2)
            wheel_sign(a, sign[0], sign[1], r, LINING, (196, 196, 196), LINING)
        a = knock(a, rng, 10, top=0.08 if face != 'bottom' else 0.0)
        return tt.shade_edges(a, 0.12)
    return tt.painter(key, fn)


def spoke_paint(key):
    def fn(face, w, h, rng):
        if face in ('top', 'bottom'):
            return None
        a = np.zeros((h, w, 4))
        a[..., :3] = tt.grain(w, h, 'v', PAINT, PAINT_D, PAINT_L, rng, streak=0.3)[..., :3]
        U, V = uv(w, h)
        a[..., :3] *= (0.82 + 0.3 * np.sin(np.pi * U))[..., None]
        a[..., 3] = 255
        return knock(a, rng, 2, edge=0.1)
    return tt.painter(key, fn)


def rim_paint(key):
    """A felloe: its wood inside the tyre on the wheel's faces, and its inner face."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face in ('right', 'left'):
            a = coat(w, h, rng, 'u', 1)
            a[..., 3] = np.where(V < 0.28, 0, 255)
            a[..., :3] = np.where((V > 0.85)[..., None], mix(a[..., :3], PAINT_D, 0.35), a[..., :3])
            return knock(a, rng, 2, edge=0.15)
        if face == 'bottom':
            a = coat(w, h, rng, 'u', 1)
            return knock(a, rng, 2, edge=0.15)
        return None
    return tt.painter(key, fn)


def nave_paint(key):
    """A nave between its iron bands."""
    def fn(face, w, h, rng):
        if face in ('right', 'left'):
            return None
        U, V = uv(w, h)
        a = coat(w, h, rng, 'u', 1)
        a[..., 3] = np.where((U < 0.22) | (U > 0.78), 0, 255)
        return a
    return tt.painter(key, fn)


def pennant_cloth(key, part):
    """The caravans' pennant's own pieces as the House's cloth: white wool twill (tinted), a hem along its top and bottom, a wheel on the
    first piece, the swallowtail cut on the last (where the caravans' own cuts it)."""
    def fn(face, w, h, rng):
        if face not in ('front', 'back'):
            return None
        U, V = uv(w, h)
        uu = U if face == 'front' else 1 - U
        a = tt.noise_fill(w, h, CLOTH_L, rng, 0.02)
        a = P._twill(a, w, h, rng, 0.03)
        a[..., :3] *= (1.0 + 0.05 * np.sin(uu * 9 + part * 2.3))[..., None]
        edge = (V < 0.12) | (V > 0.88)
        a[..., :3] = np.where(edge[..., None], mix(a[..., :3], CLOTH_D, 0.45), a[..., :3])
        if part == 0:
            r = np.hypot((uu - 0.55) * w / h, V - 0.5)
            ang = np.arctan2(V - 0.5, (uu - 0.55) * w / h)
            wheel = ((r < 0.26) & (r > 0.18)) | ((r <= 0.18) & (np.abs(np.sin(ang * 3.0)) < 0.3)) | (r < 0.06)
            a[..., :3] = np.where(wheel[..., None], mix(a[..., :3], CLOTH_D, 0.8), a[..., :3])
        if part == 2:
            a[uu > 1.0 - (0.5 - np.abs(V - 0.5)) * 1.1] = 0
        a[..., 3] = np.where(a[..., 3] > 0, 255, 0)
        return a
    return tt.painter(key, fn)


def paint_keys():
    """{the wagon's cube (or shared picture) key: its paint}: everything the owner's colour covers."""
    span = W.BOX_BACK - W.BOX_FRONT
    out = {
        # the right side's outer face is its 'right' (u from the back to the front), the left's its 'left' (u from the front back)
        'c_side_r': side_paint('cw_side_r', 'right', lambda u: W.BOX_BACK - u * span),
        'c_side_l': side_paint('cw_side_l', 'left', lambda u: W.BOX_FRONT + u * span),
        'c_front': board_face_paint('cw_front', 'front', n=3),
        'c_dash': board_face_paint('cw_dash', 'front', irons=(0.04, 0.96), sign=(0.5, 0.52)),
        'c_tail': board_face_paint('cw_tail', 'bottom', sign=(0.5, 0.5), n=3),
        'c_spoke_r': spoke_paint('cw_spoke_r'), 'c_spoke_f': spoke_paint('cw_spoke_f'),
        'c_rim_r': rim_paint('cw_rim_r'), 'c_rim_f': rim_paint('cw_rim_f'),
        'c_nave_r': nave_paint('cw_nave_r'), 'c_nave_f': nave_paint('cw_nave_f'),
    }
    for k in range(3):
        out[f'c_pennant_{k}'] = pennant_cloth(f'cw_pennant_{k}', k)
    return out

# ================================================================== the dressing's painters


def board_paint(key, outer, part):
    """A name board: [part] 'plank' (its field cream, lightly grained, for the name to be written on; black on its edges) or a 'frame'
    strip (black paint, a gilt bead along its inner edge, gilt pins at its ends); [outer] is the face out of the wagon's side."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if part == 'plank':
            if face == outer:
                a = tt.grain(w, h, 'u', FIELD, FIELD_D, FIELD_L, rng, streak=0.18)
                a[..., :3] = mix(a[..., :3], FIELD, 0.55)
                vig = np.clip(np.minimum.reduce([U, 1 - U, V * h / w, (1 - V) * h / w]) * 6, 0, 1)
                a[..., :3] *= (0.9 + 0.1 * vig)[..., None]
                return a
            return tt.grain(w, h, None, FRAME, FRAME_D, FRAME_L, rng)
        a = tt.grain(w, h, None, FRAME, FRAME_D, FRAME_L, rng)
        a[..., :3] = mix(a[..., :3], FRAME, 0.5)
        if face == outer:
            inner = {'top': V > 0.66, 'bottom': V < 0.34, 'end0': None, 'end1': None}
            long_ = w >= h
            bead = (V > 0.62) if part == 'frame_top' else (V < 0.38) if part == 'frame_bottom' else \
                (U > 0.62) if part == 'frame_front' else (U < 0.38)
            if not long_ and part in ('frame_top', 'frame_bottom'):
                bead = np.zeros((h, w), bool)
            a[..., :3] = np.where(bead[..., None], tt.metal(w, h, GILT, GILT_D, GILT_L, rng, bevel=False, wear=0.03)[..., :3], a[..., :3])
            if part in ('frame_top', 'frame_bottom'):
                for cu in (0.03, 0.97):
                    hc.nail(a, cu, 0.5, 0.55, GILT_D, GILT_L)
        return tt.shade_edges(a, 0.1)
    return tt.painter(key, fn)


def cushion_paint(key, crown):
    """The guard's cushion: oxblood leather, buttoned in two rows of three on its crown, piped round its edge."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = tt.noise_fill(w, h, OX, rng, 0.04)
        a[..., :3] = mix(a[..., :3], OX_L, (0.18 * np.exp(-((U - 0.4) ** 2 + (V - 0.35) ** 2) * 5))[..., None])
        if face == 'top' and crown:
            ys, xs = np.mgrid[0:h, 0:w]
            for cu in (0.2, 0.5, 0.8):
                for cv in (0.3, 0.7):
                    d = np.hypot(xs + 0.5 - cu * w, ys + 0.5 - cv * h)
                    a[..., :3] = np.where((d < 2.6)[..., None], mix(a[..., :3], OX_D, np.clip(0.6 * (1 - d / 2.6), 0, 1)[..., None]), a[..., :3])
                    a[..., :3] = np.where((d < 0.8)[..., None], np.array(OX_D, float), a[..., :3])
            return tt.shade_edges(a, 0.2)
        if face == 'top':
            return a
        if face == 'bottom':
            return tt.noise_fill(w, h, OX_D, rng, 0.04)
        # the sides: the piping along the top
        a[..., :3] = np.where((V < 0.3)[..., None] if not crown else (V < 0.0)[..., None], np.array(PIPING, float), a[..., :3])
        a[..., :3] *= (0.85 + 0.15 * (1 - V))[..., None]
        return a
    return tt.painter(key, fn)


def pad_paint(key, crown):
    """The tailboard's pad: a grey-brown wool blanket folded in four, a cream and a red stripe across it, its folds on the sides."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        a = tt.noise_fill(w, h, BLANKET, rng, 0.05)
        a = P._twill(a, w, h, rng, 0.04)
        if face in ('top', 'bottom'):
            for c, col in ((0.18, STRIPE_A), (0.24, STRIPE_B), (0.76, STRIPE_B), (0.82, STRIPE_A)):
                a[..., :3] = np.where((np.abs(U - c) < 0.025)[..., None], np.array(col, float), a[..., :3])
            return tt.shade_edges(a, 0.16 if crown else 0.1)
        # the folds seen edge on
        k = (V * 4.0) % 1.0
        a[..., :3] *= (0.78 + 0.28 * np.sin(k * np.pi))[..., None]
        return a
    return tt.painter(key, fn)


def flag_paint(key, z0, z1, y0, y1):
    """A pennant segment from z0 to z1 (y0 to y1 its cube's height): white cloth with a hemmed edge, the sleeve at the hoist, the wheel on
    the first segment, cut (alpha) to the pennant's shape on both its faces."""
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
        a[..., :3] *= (1.0 - 0.06 * np.cos((y - mid) / np.maximum(0.5, b - t) * math.pi * 2))[..., None]
        a[..., :3] *= (1.0 + 0.05 * np.sin(z * 2.1))[..., None]
        hem = (y - t < 0.32) | (b - y < 0.32)
        a[..., :3] = np.where(hem[..., None], mix(a[..., :3], CLOTH_D, 0.45), a[..., :3])
        sleeve = z < FLAG_Z[0] + 0.55
        a[..., :3] = np.where(sleeve[..., None], mix(a[..., :3], CLOTH_D, 0.55), a[..., :3])
        cz, cy, r = (FLAG_Z[0] + FLAG_Z[1]) / 2.0 + 0.3, (HOIST[0] + HOIST[1]) / 2.0, 1.3
        d = np.hypot(z - cz, y - cy)
        ang = np.arctan2(y - cy, z - cz)
        wheel = ((d <= r) & (d > r * 0.7)) | ((d <= r * 0.7) & (np.abs(np.sin(ang * 3.0)) < 0.3)) | (d <= r * 0.28)
        a[..., :3] = np.where(wheel[..., None], mix(a[..., :3], CLOTH_D, 0.8), a[..., :3])
        a[..., 3] = np.where(in_flag(y, z), 255, 0)
        return a
    return tt.painter(key, fn, light=False)

# ================================================================== the dressing's model


def bx(x0, y0, z0, x1, y1, z1, name, paint=None, faces=None):
    x0, x1 = sorted((x0, x1))
    return K.bx(x0, y0, z0, x1, y1, z1, name, paint, faces=faces)


def dressing():
    parts = []
    for n, (side, (z0, z1)) in BOARDS.items():
        s = side
        outer = 'right' if s < 0 else 'left'
        xi = s * SIDE_X
        xp = s * (SIDE_X + PLANK_T)
        xf = s * (SIDE_X + PLANK_T + FRAME_T)
        y0, y1 = BOARD_Y
        tag = n[-1]
        cubes = [bx(xi, y0, z0, xp, y1, z1, f'c_{n}', board_paint(f'cw_{n}', outer, 'plank')),
                 bx(xp, y0, z0, xf, y0 + FRAME_W, z1, f'c_{n}_top', board_paint(f'cw_{n}_top', outer, 'frame_top')),
                 bx(xp, y1 - FRAME_W, z0, xf, y1, z1, f'c_{n}_bottom', board_paint(f'cw_{n}_bottom', outer, 'frame_bottom')),
                 bx(xp, y0 + FRAME_W, z0, xf, y1 - FRAME_W, z0 + FRAME_W, f'c_{n}_front', board_paint(f'cw_{n}_front', outer, 'frame_front')),
                 bx(xp, y0 + FRAME_W, z1 - FRAME_W, xf, y1 - FRAME_W, z1, f'c_{n}_back', board_paint(f'cw_{n}_back', outer, 'frame_back'))]
        (fz0, fz1, fy0, fy1), _ = field(n)
        parts.append(K.part(n, (xi, (fy0 + fy1) / 2.0, (fz0 + fz1) / 2.0), cubes))
        del tag
    (cx0, cx1), (cz0, cz1), tb, tc = CUSHION
    top = W.BENCH_TOP
    parts.append(K.part('seat_bench', ((cx0 + cx1) / 2.0, top, (cz0 + cz1) / 2.0), [
        bx(cx0, top - tb, cz0, cx1, top, cz1, 'c_seat_bench', cushion_paint('cw_seat_bench', False)),
        bx(cx0 + 0.35, top - tb - tc, cz0 + 0.35, cx1 - 0.35, top - tb, cz1 - 0.35, 'c_seat_bench_crown', cushion_paint('cw_seat_bench_crown', True)),
    ]))
    (px0, px1), (pz0, pz1), pb, pc = PAD
    parts.append(K.part('seat_tail', (0.0, TAIL_TOP, (pz0 + pz1) / 2.0), [
        bx(px0, TAIL_TOP - pb, pz0, px1, TAIL_TOP, pz1, 'c_seat_tail', pad_paint('cw_seat_tail', False)),
        bx(px0 + 0.3, TAIL_TOP - pb - pc, pz0 + 0.3, px1 - 0.3, TAIL_TOP - pb, pz1 - 0.3, 'c_seat_tail_crown', pad_paint('cw_seat_tail_crown', True)),
    ]))
    for k in range(len(FLAG_Z) - 1):
        z0, z1 = FLAG_Z[k], FLAG_Z[k + 1]
        t, b = flag_edges(z0)
        c = bx(FLAG_X[0], t, z0, FLAG_X[1], b, z1, f'c_flag_{k}', flag_paint(f'cw_flag_{k}', z0, z1, t, b), faces=('right', 'left'))
        parts.append(K.part(f'flag_{k}', (RIPPLES[k][0], HOIST[0], RIPPLES[k][1]), [c]))
    return Model(NAME + '_dressing', TEX_W, TEX_H, parts)


def build():
    """(the wagon's models as wagon.py builds and packs them, its rows used, the dressing packed into the rows under them)."""
    models, used = W.build()
    dress = dressing()
    rows = K.pack(list(dress.all_cubes()), TEX_W, TEX_H - used, who=NAME)
    for c in dress.all_cubes():
        c.uv = (c.uv[0], c.uv[1] + used)
    return models, dress, used, used + rows


def paint_sources(wagon):
    """{key: (a cube carrying that picture's place, its paint)} for every picture of the wagon the paint covers."""
    keys = paint_keys()
    out = {}
    for c in wagon.all_cubes():
        key = c.share or c.name
        if key in keys and key not in out:
            out[key] = (c, keys[key])
    missing = sorted(set(keys) - set(out))
    if missing:
        raise SystemExit(f'{NAME}: the wagon has no cubes {missing}')
    return out


def texture(wagon, dress):
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    for key, (c, p) in paint_sources(wagon).items():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0 and c.shows(face):
                p(img, face, x, y, w, h)
    for c in dress.all_cubes():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0 and c.shows(face):
                c.paint(img, face, x, y, w, h)
    # cutout: every texel drawn or not
    a = np.array(img)
    a[..., 3] = np.where(a[..., 3] > 127, 255, 0)
    a[a[..., 3] == 0] = 0
    return Image.fromarray(a, 'RGBA')

# ================================================================== posing it, as WagonRenderer and ReinRenderer do


def about(R, p):
    p = np.asarray(p, float)
    return R, p - R @ p


def then(a, b):
    return a[0] @ b[0], a[0] @ b[1] + a[1]


IDENTITY = (np.eye(3), np.zeros(3))


def flag_pose(flap=0.0, size=0.6, run=1.0):
    """{flag part: (A, t)} as ReinRenderer places it: lowered STAFF_RUN * (1 - eased run), each segment turned RIPPLE * size * sin(flap -
    k * 1.1) about its hinge, the next riding on it (the size eased in from 0.35 of itself as it runs up)."""
    e = K.smooth(run)
    size = size * (0.35 + 0.65 * e)
    m = (np.eye(3), np.array([0.0, STAFF_RUN * (1.0 - e), 0.0]))
    out = {}
    for i in range(len(FLAG_Z) - 1):
        hx, hz = RIPPLES[i]
        turn = RIPPLE[min(i, len(RIPPLE) - 1)] * size * math.sin(flap - i * 1.1)
        m = then(m, about(K.roty(math.degrees(turn)), (hx, 0.0, hz)))
        out[f'flag_{i}'] = m
    return out


def wagon_pose(spin=0.0, steer=0.0, rock=0.0, brake=None, tail=0.0, sway=0.0, coloured=True):
    """The wagon's own parts as WagonModel poses them (degrees): wheels rolled, the front steered and rocked, the brake lever, the
    tailboard (0 let down, 90 shut), the tilt's sway; a coloured wagon's own pennant hidden."""
    sf = spin * W.RR / W.RF
    pose = {'wheel_rr': {'rot': (spin, 0, 0)}, 'wheel_rl': {'rot': (spin, 0, 0)}, 'wheel_fr': {'rot': (sf, 0, 0)},
            'wheel_fl': {'rot': (sf, 0, 0)}, 'tailboard': {'rot': (tail, 0, 0)}, 'tilt': {'rot': (0, 0, sway)}}
    pose['front'] = {'rot': _euler_zyx(K.roty(steer) @ K.rotz(rock))}
    if brake is not None:
        pose['brake_lever'] = {'rot': (brake, 0, 0)}
    if coloured:
        pose['pennant'] = {'hide': True}
    return pose


def _euler_zyx(R):
    """(x, y, z) degrees with rot_zyx((x, y, z)) == R."""
    for cand in _euler_candidates(R):
        if np.allclose(K.rot_zyx(cand), R, atol=1e-6):
            return cand
    raise ValueError('no euler angles')


def _euler_candidates(R):
    b = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    a = math.atan2(R[2, 1], R[2, 2])
    c = math.atan2(R[1, 0], R[0, 0])
    yield tuple(math.degrees(t) for t in (a, b, c))
    b2 = math.pi - b
    yield tuple(math.degrees(t) for t in (math.atan2(-R[2, 1], -R[2, 2]), b2, math.atan2(-R[1, 0], -R[0, 0])))
    # the other conventions, in case rot_zyx composes the other way about
    b = math.asin(max(-1.0, min(1.0, R[0, 2])))
    yield tuple(math.degrees(t) for t in (math.atan2(-R[1, 2], R[2, 2]), b, math.atan2(-R[0, 1], R[0, 0])))


def moved(b, m):
    A, t = m
    return K.Box(b.part, b.cube, A @ b.c + t, b.ax @ A.T, b.h)


def flag_boxes(dress, pose):
    return [moved(b, pose[b.part]) for b in K.boxes_of(dress) if b.part.startswith('flag_')]

# ================================================================== the self-check


def check_paint(models, dress, tex):
    """The paint lies only on texels the wagon itself paints, inside the faces it is meant for; it is cutout (no half-clear texel)."""
    problems = []
    wagon = models[0]
    base = np.array(W.texture(models))
    a = np.array(tex)
    meant = np.zeros(a.shape[:2], bool)
    for key, (c, p) in paint_sources(wagon).items():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0 and c.shows(face):
                meant[y:y + h, x:x + w] = True
        if K.rects(c, SCALE) != hc.rects2x(c):
            problems.append(f'{key}: the kit maps its faces elsewhere than wagon.py paints them')
    dressed = np.zeros(a.shape[:2], bool)
    for c in dress.all_cubes():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            dressed[y:y + h, x:x + w] = True
    on = a[..., 3] > 0
    if (on & ~meant & ~dressed).any():
        ys, xs = np.nonzero(on & ~meant & ~dressed)
        problems.append(f'paint outside the faces meant for it, e.g. at {(int(xs[0]), int(ys[0]))}')
    if (on & meant & (base[..., 3] <= 8)).any():
        ys, xs = np.nonzero(on & meant & (base[..., 3] <= 8))
        problems.append(f'paint where the wagon has nothing (it would add to its shape), e.g. at {(int(xs[0]), int(ys[0]))}')
    if (meant & dressed).any():
        problems.append('the dressing is packed over the wagon\'s own pictures')
    if ((a[..., 3] > 0) & (a[..., 3] < 255)).any():
        problems.append('a half-clear texel (the paint is drawn cutout)')
    # every painted face has paint on it
    for key, (c, p) in paint_sources(wagon).items():
        for face, (x, y, w, h) in K.rects(c, SCALE).items():
            if w > 0 and h > 0 and c.shows(face) and p is not None:
                pass
    return problems


def wagon_boxes(models, **kw):
    pose = wagon_pose(**kw)
    return K.boxes_of(models[0], pose=pose)


def check_clear(models, dress):
    """Every dressing part against every cube of the wagon over its moves; the pennant against the staff, its knob and the tilt over its
    ripple and its run. Returns (problems, the closest of each pair: px, > 0 is a clash)."""
    problems = []
    worst = {}
    still = [b for b in K.boxes_of(dress) if not b.part.startswith('flag_')]

    def note(key, d, where):
        if d > worst.get(key, (-1e9, ''))[0]:
            worst[key] = (d, where)

    poses = [dict(spin=s, steer=st, rock=r, brake=br, tail=0.0) for s in (0.0, 9.0, 17.0) for st in (-55.0, -30.0, 0.0, 30.0, 55.0)
             for r in (-8.0, 0.0, 8.0) for br in np.linspace(W.BRAKE_REST, W.BRAKE_ON, 5)]
    for kw in poses:
        wb = wagon_boxes(models, **kw)
        for a in still:
            for b in wb:
                if b.part == 'pennant' or b.part.startswith('pennant'):
                    continue
                if np.any(a.lo - 0.5 > b.hi) or np.any(b.lo - 0.5 > a.hi):
                    continue
                d = K.box_depth(a, b)
                note(f'{a.part}-wagon', d, f'{b.cube} {kw}')
                if d > 1e-6:
                    problems.append(f'{a.cube} goes {d:.3f} px into {b.cube} at {kw}')
    # the pennant, over the tilt as it sways (WagonClient: 2.5 degrees at most)
    wbs = [[b for b in wagon_boxes(models, sway=sw) if not b.part.startswith('pennant')] for sw in (-2.5, 0.0, 2.5)]
    for run in (0.0, 0.25, 0.5, 0.75, 1.0):
        for size in (0.55, SIZE_MAX):
            for k in range(48):
                fb = flag_boxes(dress, flag_pose(k * math.tau / 48, size, run))
                for a in fb:
                    for b in wbs[k % 3]:
                        if np.any(a.lo - 0.5 > b.hi) or np.any(b.lo - 0.5 > a.hi):
                            continue
                        d = K.box_depth(a, b)
                        note('pennant-wagon', d, f'{a.cube} {b.cube} run {run} size {size}')
                        if d > 1e-6:
                            problems.append(f'{a.cube} goes {d:.3f} px into {b.cube} (run {run}, size {size}, phase {k})')
                for i, a in enumerate(fb):
                    for b in fb[i + 2:]:
                        d = K.box_depth(a, b)
                        note('pennant-pennant', d, '')
                        if d > 1e-6:
                            problems.append(f'{a.cube} goes {d:.3f} px into {b.cube} (run {run}, size {size}, phase {k})')
    return problems, {k: v[0] for k, v in worst.items()}


def check_rest(models, dress):
    """Nothing floats: each board's inner face on the box's side, the cushion on the bench, the pad on the tailboard, the pennant's sleeve
    at the staff; the names on the boards' fields."""
    problems = []
    for n, (side, (z0, z1)) in BOARDS.items():
        for b in K.boxes_of(dress, only={n}):
            if b.cube == f'c_{n}':
                inner = b.c[0] - side * b.h[0] if abs(b.ax[0][0]) > 0.99 else None
                if inner is None or abs(abs(inner) - SIDE_X) > 1e-6:
                    problems.append(f'{n} does not lie on the box\'s side')
        lo, hi = min(z0, z1), max(z0, z1)
        for sz in W.STAKE_Z:
            if lo - STAKE_HALF < sz < hi + STAKE_HALF:
                problems.append(f'{n} is across a stake')
    (_, _), (_, _), tb, tc = CUSHION
    if abs(W.BENCH_TOP - W.BENCH_TOP) > 1e-9:
        problems.append('the cushion is off the bench')
    (cx0, cx1), (cz0, cz1), _, _ = CUSHION
    if not (-W.BENCH_X <= cx0 and cx1 <= W.BENCH_X and W.BENCH_FRONT <= cz0 and cz1 <= W.BENCH_BACK):
        problems.append('the cushion is not all on the bench')
    seat = (-6.5, W.BENCH_TOP, -45.5)
    if not (cx0 < seat[0] < cx1 and cz0 < seat[2] < cz1):
        problems.append('the cushion is not under the guard\'s seat')
    (px0, px1), (pz0, pz1), _, _ = PAD
    if not (pz0 >= W.BOX_BACK and pz1 <= W.BOX_BACK + W.TAIL_LEN and -(W.BOX_X - 1.0) <= px0 and px1 <= W.BOX_X - 1.0):
        problems.append('the pad is not all on the let-down tailboard')
    if FLAG_Z[0] - STAFF[1] > 0.45 + 0.2:
        problems.append('the pennant is not at the staff')
    if HOIST[0] < KNOB_BOTTOM:
        problems.append('the pennant goes up into the staff\'s knob')
    for (x, y, z, side, wide, high), n in zip(NAMES, ('board_r', 'board_l')):
        (fz0, fz1, fy0, fy1), fx = field(n)
        if abs(x - fx) > 1e-9 or not (min(fz0, fz1) < z < max(fz0, fz1) and fy0 < y < fy1):
            problems.append(f'the name on {n} is not on its field')
        if wide * 16 > abs(fz1 - fz0) or high * 16 > fy1 - fy0:
            problems.append(f'the name on {n} has more room than its field')
    return problems


def check_contract(models, dress, tex):
    problems = []
    problems += K.check_model(dress, tex, (TEX_W, TEX_H), who='carrier_wagon dressing')
    names = K.all_names(dress)
    want = ['board_r', 'board_l', 'seat_bench', 'seat_tail'] + [f'flag_{k}' for k in range(len(RIPPLES))]
    if names != want:
        problems.append(f'the dressing has the parts {names}, the renderer looks for {want}')
    problems += check_paint(models, dress, tex)
    # wagon.py's own outputs are what wagon.py writes, untouched by this variant
    shipped = os.path.join(ASSETS, 'textures', 'entity', 'caravan')
    for name, glow in (('wagon.png', False), ('wagon_glow.png', True)):
        p = os.path.join(shipped, name)
        if os.path.exists(p) and not np.array_equal(np.array(Image.open(p).convert('RGBA')), np.array(W.texture(models, glow=glow))):
            problems.append(f'{name} on disk is not what wagon.py paints')
    src = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/caravan/WagonMesh.kt')
    if os.path.exists(src) and open(src).read() != W.kotlin_mesh(models):
        problems.append('WagonMesh.kt on disk is not what wagon.py writes')
    problems += check_rest(models, dress)
    clear, _ = check_clear(models, dress)
    problems += clear
    K.must_be_clean(problems, NAME)

# ================================================================== the Kotlin table


KOTLIN_HEADER = '''// generated by art/scripts/carrier_wagon.py, edit the script, not this file
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
 * The Carrier's Wagon's art (art/scripts/carrier_wagon.py): the caravans' wagon in its owner's colour, and its dressing. Model space is the
 * caravans' wagon's (WagonMesh): pixels, y down, the origin the ground under the middle of the back axle, its front toward -z, its right -x.
 *
 * - [TEXTURE], at the wagon's own layout ([TEX_WIDTH] x [TEX_HEIGHT] declared, shipped at 2x), holds the PAINT over the wagon's own UVs:
 *   white and grey where the owner's colour covers the wagon (the box's sides in lined panels between the stakes, the front board, the
 *   dash and the tailboard's outer face with a wheel in a roundel, the wheels' spokes, felloes and naves), clear everywhere else. Draw
 *   WagonMesh's wagon a second time with it after the wagon (same pose, so coplanar: drawn over), cutout, tinted with the wagon's colour.
 *   At the wagon's pennant's own UVs it holds white cloth, for a pennant drawn from the wagon's own pennant parts in a House's colour.
 * - [dressing] is a second layer on the same texture: `board_r` and `board_l` (the name boards on the box's sides, painted: no tint),
 *   `seat_bench` and `seat_tail` (the guard's cushion on the bench and the pad on the let-down tailboard, painted) and `flag_0` to
 *   `flag_3` (the owner's pennant, white to be tinted; segment k turns about the vertical at [RIPPLES] k, the next riding on it; it is
 *   [STAFF_RUN] px lower when it begins to run up the staff). Each part's pivot is where it turns, or the middle of what it is.
 * - [NAMES]: where the owner's name is written on each board: the middle of its field's outer face (x, y, z px), its side (-1 the right,
 *   1 the left) and the room for the name (wide, high, in blocks).
 *
 * See guildmark-notes/living-world/trader/guide/tra_x2.md.
 */
object CarrierWagonMesh {
    const val TEX_WIDTH = @TEX_W@
    const val TEX_HEIGHT = @TEX_H@

    /** The paint over the wagon, the House's pennant cloth and the dressing (one texture). */
    const val TEXTURE = "@TEXTURE@"

    /** Each board's name: (x, y, z, side, wide, high), the right board's first. */
    val NAMES = floatArrayOf(@NAMES@)

    /** The pennant's hinges, from the staff outward: (x, z) pairs. */
    val RIPPLES = floatArrayOf(@RIPPLES@)

    /** Px the pennant is lower than its place when it begins to run up the staff: its foot then just over the tilt's ridge. */
    const val STAFF_RUN = @STAFF_RUN@

    /** The name boards, the seats and the pennant. */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun dressing(): LayerDefinition {
@MESH@
    }
}
'''


def kotlin_source(dress):
    tok = {
        'TEX_W': str(TEX_W), 'TEX_H': str(TEX_H), 'TEXTURE': TEXTURE,
        'NAMES': K.kt_list([v for n in NAMES for v in n]), 'RIPPLES': K.kt_list([v for r in RIPPLES for v in r]),
        'STAFF_RUN': K.kt_float(STAFF_RUN), 'MESH': dress.kotlin().rstrip('\n'),
    }
    body = KOTLIN_OBJECT
    for k, v in tok.items():
        body = body.replace('@' + k + '@', v)
    assert '@' not in body.replace('@Suppress', ''), 'a token is left in the template'
    return KOTLIN_HEADER + body

# ================================================================== writing it all


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


SHOWN = 0x30409A                          # Dye.BLUE: the colour the Blockbench file's picture is in


def composite(models, tex, colour=SHOWN, flags=True):
    """The wagon's own texture with the paint tinted [colour] over it, the dressing in the rows under it (the pennant tinted): the picture
    the Blockbench file shows, and the previews' look at the texture."""
    base = np.array(W.texture(models)).astype(float)
    a = np.array(tex).astype(float)
    tint = np.array(rgb(colour), float) / 255.0
    used = max(c.uv[1] + c.footprint()[1] for m in models for c in m.all_cubes()) * SCALE
    over = a.copy()
    over[:used, :, :3] *= tint
    on = over[..., 3:4] > 0
    out = np.where(on, over, base)
    if flags:
        out[used:, :, :3] = np.where(on[used:], a[used:, :, :3], out[used:, :, :3])
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGBA')


def write_all(assets=ASSETS, kotlin_dir=KOTLIN, model_dir=MODELS):
    """Everything the script owns: the texture, the Kotlin table and the Blockbench file. Returns (models, dress, tex)."""
    models, dress, used, rows = build()
    tex = texture(models[0], dress)
    check_contract(models, dress, tex)
    tdir = os.path.join(assets, 'textures', 'entity', 'trade')
    os.makedirs(tdir, exist_ok=True)
    tex.save(os.path.join(tdir, NAME + '.png'))
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'CarrierWagonMesh.kt'), 'w') as f:
        f.write(kotlin_source(dress))
    os.makedirs(model_dir, exist_ok=True)
    small = composite(models, tex).resize((TEX_W, TEX_H), Image.LANCZOS)
    both = Model(NAME, TEX_W, TEX_H, models[0].parts + dress.parts)
    with open(os.path.join(model_dir, NAME + '.bbmodel'), 'w') as f:
        f.write(K.steady_uuids(json.dumps(both.bbmodel(small, NAME)), NAME))
    check_written(models, dress, assets, kotlin_dir, model_dir)
    return models, dress, tex


def check_written(models, dress, assets, kotlin_dir, model_dir):
    problems = []
    png = Image.open(os.path.join(assets, 'textures', 'entity', 'trade', NAME + '.png'))
    if png.size != (TEX_W * SCALE, TEX_H * SCALE) or png.mode != 'RGBA':
        problems.append(f'{NAME}.png is {png.size} {png.mode}')
    src = open(os.path.join(kotlin_dir, 'CarrierWagonMesh.kt')).read()
    found = re.findall(r'addOrReplaceChild\("([a-z_0-9]+)"', src)
    if found != K.all_names(dress):
        problems.append(f'the Kotlin has other parts than the dressing: {found}')
    offs = re.findall(r'texOffs\((\d+), (\d+)\)', src)
    if [tuple(int(v) for v in o) for o in offs] != [tuple(int(v) for v in c.uv) for c in dress.all_cubes()]:
        problems.append('the Kotlin places the cubes elsewhere in the texture')
    for key, vals in (('NAMES', [v for n in NAMES for v in n]), ('RIPPLES', [v for r in RIPPLES for v in r])):
        if f'val {key} = floatArrayOf({K.kt_list(vals)})' not in src:
            problems.append(f'the Kotlin\'s {key} is not {vals}')
    if f'STAFF_RUN = {K.kt_float(STAFF_RUN)}' not in src:
        problems.append('the Kotlin\'s STAFF_RUN is not the script\'s')
    bb = json.load(open(os.path.join(model_dir, NAME + '.bbmodel')))
    if len(bb['elements']) != len(list(models[0].all_cubes())) + len(list(dress.all_cubes())):
        problems.append(f'{NAME}.bbmodel has {len(bb["elements"])} cubes')
    K.must_be_clean(problems, NAME + ' (written)')


def report(models, dress, worst=None):
    lines = [
        f'{NAME}: texture {TEX_W} x {TEX_H} declared ({TEX_W * SCALE} x {TEX_H * SCALE} shipped): the paint over the wagon\'s '
        f'{len(paint_keys())} pictures, the dressing {len(list(dress.all_cubes()))} cubes in {len(dress.parts)} parts',
        f'  names: {NAMES}',
        f'  pennant: hinges {RIPPLES}, hoist ({HOIST[0]:g}, {HOIST[1]:g}), run {STAFF_RUN:g} px',
    ]
    if worst:
        lines.append('  closest (px, < 0 is a gap): ' + ', '.join(f'{k} {v:.3f}' for k, v in sorted(worst.items())))
    return '\n'.join(lines)

# ================================================================== previews

DYES = {'white': 0xF0F0F0, 'orange': 0xF08020, 'magenta': 0xC040B0, 'light blue': 0x50A0E0, 'yellow': 0xF0D030, 'lime': 0x80C020,
        'pink': 0xF090B0, 'gray': 0x505058, 'light gray': 0x9A9A96, 'cyan': 0x20908E, 'purple': 0x7A30B0, 'blue': 0x30409A,
        'brown': 0x7A4E2A, 'green': 0x4E6A22, 'red': 0xA02828, 'black': 0x1E1E22}    # city/plan/Blocks.kt Dye, as Houses.rgb and LedShow
LOADED = W.LOADED


class Scene:
    """What the previews draw from: the wagon's models and texture, the paint and dressing, the game's player for scale."""

    def __init__(self, models, dress, tex, game=None):
        self.models, self.dress, self.tex = models, dress, tex
        self.wt = W.texture(models)
        self.atlas = hc.Atlas(self.wt)
        for _, item in LOADED:
            if item:
                self.atlas.extra(item)
        self.vanilla = K.Vanilla(game)
        self.player = K.vanilla_player()
        self.text = {}

    def textures(self):
        t = {'wg': self.atlas.image(), 'cw': self.tex, 'steve': self.vanilla.steve()}
        t.update(self.text)
        return t

    def faces(self, colour='blue', carrier=True, goods=(), spin=6.0, steer=0.0, tail=90.0, flap=1.0, size=0.8, run=1.0, M=None,
              T=(0.0, 0.0, 0.0), house=False, names=None, seats=True):
        """The wagon as the game draws it: the caravans' own ([carrier] False), or a trader's (paint tinted [colour], the dressing, the
        owner's [names] written on the boards) or a House's ([house]: the paint and the House's pennant, no dressing)."""
        M = np.eye(3) if M is None else M
        T = np.asarray(T, float)
        coloured = carrier or house
        tint = rgb(DYES[colour]) if isinstance(colour, str) else colour
        pose = wagon_pose(spin=spin, steer=steer, tail=tail, coloured=coloured)
        out = K.faces_of(self.models[0], 'wg', s=SCALE, pose=pose, M=M, T=T)
        if coloured:
            out += K.faces_of(self.models[0], 'cw', s=SCALE, pose=pose, M=M, T=T, tint=tint)
        if house:
            out += self.house_pennant(tint, flap, size, M, T)
        if carrier:
            fp = flag_pose(flap, size, run)
            for p in self.dress.parts:
                if p.name.startswith('flag_'):
                    A, t = fp[p.name]
                    out += K.faces_of(self.dress, 'cw', only={p.name}, M=M @ A, T=M @ t + T, tint=tint)
                elif p.name == 'seat_tail':
                    if seats and tail < 4.0:
                        out += K.faces_of(self.dress, 'cw', only={p.name}, M=M, T=T)
                elif p.name == 'seat_bench':
                    if seats:
                        out += K.faces_of(self.dress, 'cw', only={p.name}, M=M, T=T)
                else:
                    out += K.faces_of(self.dress, 'cw', only={p.name}, M=M, T=T)
            if names:
                out += self.name_faces(names, M, T)
        if goods:
            out += [K.Face(*[M @ np.asarray(q, float) + T for q in f[:3]], f[3], f[4], M @ np.asarray(f[5], float), 'wg')
                    for f in W.load_faces(self.models[1], list(goods), atlas=self.atlas)]
        return out

    def house_pennant(self, tint, flap, size, M, T):
        """A House's pennant as WagonRenderer draws it: the wagon's own three pieces, each turned as WagonModel ripples the painted one."""
        out = []
        st = {'flap': flap, 'size': size}
        sway = [math.radians(8.0) * size * math.sin(flap * 0.5 + 0.7), math.radians(14.0) * size * math.sin(flap),
                math.radians(22.0) * size * math.sin(flap - 1.1)]
        pose = {'pennant': {'rot': (0.0, -90.0 + math.degrees(sway[0]), 0.0)}, 'pennant_1': {'rot': (0.0, math.degrees(sway[1]), 0.0)},
                'pennant_2': {'rot': (0.0, math.degrees(sway[2]), 0.0)}}
        del st
        return K.faces_of(self.models[0], 'cw', s=SCALE, pose=pose, M=M, T=T, only={'pennant'}, tint=tint)

    def name_faces(self, owner, M, T):
        """The owner's name on each board as ReinRenderer writes it (the game's font is approximated: 6 px a letter, 9 high)."""
        out = []
        mc_w = sum(4 if ch in "il.,'!|" else 5 if ch in 'tI' else 6 for ch in owner)
        key = 'name:' + owner
        if key not in self.text:
            img = Image.new('RGBA', (mc_w * 8, 9 * 8), (0, 0, 0, 0))
            try:
                font = ImageFont.load_default(size=64)
            except TypeError:
                font = ImageFont.load_default()
            d = ImageDraw.Draw(img)
            tw = d.textlength(owner, font=font)
            sx = img.width / max(1.0, tw)
            big = Image.new('RGBA', (int(tw) + 4, 80), (0, 0, 0, 0))
            ImageDraw.Draw(big).text((2, 0), owner, font=font, fill=(43, 29, 16, 255))
            img = big.resize((img.width, img.height), Image.LANCZOS)
            del sx
            self.text[key] = img
        tw, th = self.text[key].size
        for (x, y, z, side, wide, high) in NAMES:
            s = min(wide / mc_w, high / 9.0) * 16.0                 # px of the wagon's space a font px takes
            along = np.array([0.0, 0.0, side])                       # the font's x: -z on the right, +z on the left
            down = np.array([0.0, 1.0, 0.0])
            o = np.array([x + side * 0.004 * 16, y, z]) - along * (mc_w * s / 2.0) - down * (4.5 * s)
            A = o
            B = o + along * mc_w * s
            D = o + down * 9 * s
            n = np.array([side, 0.0, 0.0])
            out.append(K.Face(M @ A + T, M @ B + T, M @ D + T, (0, 0, tw, th), 'front', M @ n, key))
        return out

    def guard(self, where, M=None, T=(0.0, 0.0, 0.0)):
        """A player sitting on a guard's seat (the riding pose: the thighs out level, on the seat), for scale."""
        M = np.eye(3) if M is None else M
        pose = K.humanoid_pose()
        for leg, sy, sz in (('right_leg', 18.0, 4.5), ('right_pants', 18.0, 4.5), ('left_leg', -18.0, -4.5), ('left_pants', -18.0, -4.5)):
            pose[leg] = {'rot': (-81.0, sy, sz)}
        for arm in ('right_arm', 'right_sleeve', 'left_arm', 'left_sleeve'):
            pose[arm] = {'rot': (-36.0, 0.0, 0.0)}
        if where == 'bench':
            top = W.BENCH_TOP - CUSHION[2] - CUSHION[3]
            at, R = np.array([-6.5, top - 1.98 - 12.0, -45.5]), np.eye(3)
        else:
            top = TAIL_TOP - PAD[2] - PAD[3]
            at, R = np.array([0.0, top - 1.98 - 12.0, 14.2]), K.roty(180.0)
        return K.faces_of(self.player, 'steve', s=1, pose=pose, M=M @ R, T=M @ at + np.asarray(T, float))

    def shot(self, faces, view, ground=0.0):
        return K.render(faces, self.textures(), view, ground=ground, shadows=((0.0, -16.0, 16.0, 34.0, 0.35),))


V34F = dict(yaw=35, pitch=-20, scale=3.6, center=(0, -26, -26), size=(440, 330))
V34B = dict(yaw=-145, pitch=-22, scale=3.6, center=(0, -26, -14), size=(440, 330))
VRIGHT = dict(yaw=-90, pitch=0, scale=3.4, center=(0, -32, -22), size=(440, 260))
VLEFT = dict(yaw=90, pitch=0, scale=3.4, center=(0, -32, -22), size=(440, 260))
VTOP = dict(yaw=0, pitch=-89.9, scale=3.4, center=(0, -30, -22), size=(440, 260))
VBACK = dict(yaw=180, pitch=-10, scale=4.0, center=(0, -32, 0), size=(300, 300))
VFRONT = dict(yaw=0, pitch=-10, scale=4.0, center=(0, -32, -40), size=(300, 300))


def views_sheet(sc):
    tiles = []
    for label, carrier in (("the Carrier's Wagon (blue, 'Wren Hollis')", True), ("the caravans' wagon (wagon.py, unchanged)", False)):
        for vname, v in (('3/4 ahead', V34F), ('3/4 behind, tailboard down', V34B), ('right side', VRIGHT), ('left side', VLEFT),
                         ('from behind, tailboard down', VBACK), ('from ahead', VFRONT)):
            tail = 0.0 if 'down' in vname else 90.0
            f = sc.faces('blue', carrier=carrier, goods=LOADED, tail=tail, names='Wren Hollis')
            tiles.append(K.caption(sc.shot(f, K.View(**v)), f'{label}: {vname}'))
    return K.sheet(tiles, 3, "carrier_wagon.py: the Carrier's Wagon (two rows) beside the caravans' wagon (two rows), the same load on both")


def colours_sheet(sc):
    tiles = []
    v = K.View(yaw=-130, pitch=-16, scale=2.6, center=(0, -28, -20), size=(300, 230))
    for name in DYES:
        tiles.append(K.caption(sc.shot(sc.faces(name, names='Ash'), v), name))
    return K.sheet(tiles, 4, "the paint and the pennant in each of the sixteen colours a wagon is bought or a House is founded in (Dye)")


def boards_sheet(sc):
    tiles = []
    for owner in ('Wren Hollis', 'Ash', 'Bartholomew Ashdown-Pike'):
        f = sc.faces('green', names=owner)
        tiles.append(K.caption(sc.shot(f, K.View(yaw=-90, pitch=0, scale=12.0, center=(-12, -22.4, -25.25), size=(300, 150))),
                               f'right board: "{owner}"'))
        tiles.append(K.caption(sc.shot(f, K.View(yaw=90, pitch=0, scale=12.0, center=(12, -22.4, -37.75), size=(300, 150))),
                               f'left board: "{owner}"'))
    f = sc.faces('green', names='Wren Hollis')
    tiles.append(K.caption(sc.shot(f, K.View(yaw=-60, pitch=-12, scale=7.0, center=(-12, -22, -30), size=(300, 150))),
                           'right board, the brake and the front wheel'))
    tiles.append(K.caption(sc.shot(f, K.View(yaw=120, pitch=-12, scale=6.0, center=(12, -23, -32), size=(300, 150))),
                           'left board, the barrel and the lantern'))
    return K.sheet(tiles, 2, "the name boards, the owner's name as ReinRenderer scales it to the field (the game's font approximated)")


def seats_sheet(sc):
    tiles = []
    f = sc.faces('red', tail=0.0, names='Wren Hollis')
    tiles.append(K.caption(sc.shot(f, K.View(yaw=-40, pitch=-38, scale=8.0, center=(-5, -28, -44), size=(340, 260))), "the guard's cushion on the bench's right"))
    tiles.append(K.caption(sc.shot(f, K.View(yaw=150, pitch=-38, scale=8.0, center=(0, -20, 12), size=(340, 260))), 'the pad on the let-down tailboard'))
    g = f + sc.guard('bench') + sc.guard('tail')
    tiles.append(K.caption(sc.shot(g, K.View(yaw=-120, pitch=-14, scale=3.4, center=(0, -32, -18), size=(340, 260))),
                           'a guard on each (the riding pose, for scale)'))
    f2 = sc.faces('red', tail=90.0, names='Wren Hollis')
    tiles.append(K.caption(sc.shot(f2, K.View(yaw=150, pitch=-30, scale=5.0, center=(0, -22, 8), size=(340, 260))), 'the tailboard shut: no pad'))
    return K.sheet(tiles, 2, "the two guard seats")


def house_sheet(sc):
    tiles = []
    v = K.View(yaw=-120, pitch=-10, scale=6.0, center=(0, -58, -32), size=(300, 200))
    for name in ('red', 'yellow', 'cyan', 'purple'):
        tiles.append(K.caption(sc.shot(sc.faces(name, carrier=False, house=True, flap=1.3, size=0.9), v), f"a House's wagon ({name}): its pennant in X2's cloth"))
    vw = K.View(yaw=-140, pitch=-18, scale=2.8, center=(0, -30, -22), size=(300, 200))
    tiles.append(K.caption(sc.shot(sc.faces('purple', carrier=False, house=True, flap=1.3, size=0.9, goods=LOADED), vw),
                           "a House's wagon: painted, its pennant, no boards"))
    tiles.append(K.caption(sc.shot(sc.faces('purple', carrier=True, flap=1.3, size=0.9, goods=LOADED, names='Wren Hollis'), vw),
                           "a trader's wagon in the same colour"))
    return K.sheet(tiles, 2, "G: a House's wagon in its colour (WagonRenderer: the paint, and the pennant from the wagon's own pieces)")


def pennant_gif(sc, path):
    """The pennant run up the staff over two seconds as the wagon is rolled out (ReinRenderer's RUN_UP 40 ticks), then streaming as it
    sets off: the flap's phase going round at 0.16 + speed * 1.4 a tick, its size easing from 0.55 to 1."""
    frames = []
    v = K.View(yaw=-115, pitch=-8, scale=7.0, center=(0, -55, -30), size=(360, 220))
    flap, size = 0.0, 0.55
    for k in range(80):
        run = min(1.0, k / 40.0)
        speed = 0.0 if k < 44 else 0.12
        flap += 0.16 + speed * 1.4
        size += (0.55 + 0.45 * (1.0 if speed > 0 else 0.0) - size) * 0.05
        frames.append(sc.shot(sc.faces('cyan', flap=flap, size=size, run=run, spin=k * 2.0), v))
    return K.save_gif(frames, path, 50)


def rolling_gif(sc, path):
    """The wagon rolling: the painted wheels turning, the pennant streaming, the tilt swaying a little."""
    frames = []
    v = K.View(yaw=-120, pitch=-14, scale=3.0, center=(0, -30, -20), size=(420, 300))
    for k in range(48):
        spin = k * 360.0 / 48 / 2.0 * 2.0
        flap = k * 0.16 + k * 0.35
        frames.append(sc.shot(sc.faces('orange', goods=LOADED, spin=spin, flap=flap, size=1.0, names='Wren Hollis'), v))
    return K.save_gif(frames, path, 50)


def tail_gif(sc, path):
    """The tailboard let down and shut again: the pad drawn only once it is down (ReinRenderer: under 4 degrees)."""
    frames = []
    v = K.View(yaw=150, pitch=-24, scale=4.0, center=(0, -24, 4), size=(360, 280))
    seq = [90.0] * 6 + [90.0 - 90.0 * K.smooth(i / 18.0) for i in range(1, 19)] + [0.0] * 10 + [90.0 * K.smooth(i / 18.0) for i in range(1, 19)]
    for tail in seq:
        frames.append(sc.shot(sc.faces('brown', goods=LOADED[:8], tail=tail, names='Wren Hollis'), v))
    return K.save_gif(frames, path, 50)


def texture_sheet(sc):
    big = composite(sc.models, sc.tex, SHOWN)
    return K.sheet([K.caption(sc.tex.convert('RGBA').resize((1024, 512)), 'carrier_wagon.png as shipped (clear: the wagon\'s own shows)'),
                    K.caption(big.resize((1024, 512)), 'over wagon.png, tinted blue (the Blockbench file\'s picture)')], 1)


def previews(sc, out):
    os.makedirs(out, exist_ok=True)
    paths = []
    for name, fn in (('views', views_sheet), ('colours', colours_sheet), ('boards', boards_sheet), ('seats', seats_sheet),
                     ('house', house_sheet), ('texture', texture_sheet)):
        p = os.path.join(out, f'carrier_wagon_{name}.png')
        fn(sc).save(p)
        paths.append(p)
    paths.append(pennant_gif(sc, os.path.join(out, 'carrier_wagon_pennant.gif')))
    paths.append(rolling_gif(sc, os.path.join(out, 'carrier_wagon_rolling.gif')))
    paths.append(tail_gif(sc, os.path.join(out, 'carrier_wagon_tailboard.gif')))
    return paths


def main(argv):
    arg = lambda i, d: argv[i] if len(argv) > i and argv[i] != '-' else d
    assets, kotlin_dir, model_dir = arg(1, ASSETS), arg(2, KOTLIN), arg(3, MODELS)
    prev, game = arg(4, None), arg(5, None)
    models, dress, tex = write_all(assets, kotlin_dir, model_dir)
    _, worst = check_clear(models, dress)
    print(report(models, dress, worst))
    if prev:
        for p in previews(Scene(models, dress, tex, game), prev):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
