"""The Carriers' Store's fittings, painted and modelled with the works' kit (trade_works_kit.py, works_art.py's materials): the
clerk's scale (a bench, a post with a dial, a beam, two pans), the Store's name board and its open / shut door board; and, for the
Counting House's desk and the Store's strong room, the strongbox with its hinged lid and the counting frame with its sliding beads.

    python3 -B art/scripts/store_art.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/trade art/models [preview-dir]

Every fitting is a block of the trade, authored FACING NORTH (its front toward -z; what it hangs on, or the back of it, at z 16) in
model px (16 to a block; x east, y up, z south) and turned by its blockstate's `facing`, as the works' blocks are:
  models/block/<id>.json, blockstates/<id>.json       the block, with what stands still
  textures/block/trade/store_*.png, fit_*.png         its pictures (the works' oak, iron and bronze are borrowed as they are)
  textures/block/trade/<mesh>.png + art/models/store_<mesh>.bbmodel   the moving pieces, each a named part standing at its own pivot
  client/trade/StoreArt.kt (GENERATED)                the numbers a renderer needs beside them, and the meshes' LayerDefinitions
Nothing here is drawn by a renderer or placed by a block: those are F2's (StoreScaleRenderer, TradeBlocks). A model on its own is a
whole, plain block (the pieces a renderer adds are moving ones and live only in the mesh); the ids are

  store_scale          the bench, the post with its fork and dial, the weights in a row: the beam and the pans are the mesh's
  store_name_board     the wall bracket and crossbar the Store's name board hangs from; the board is the mesh's
  store_door_board     the hook the open / shut board hangs from; the board is the mesh's
  strongbox            the iron-bound oak body, open on top with its coins; the lid is the mesh's (strongbox_shut is the whole, shut)
  counting_frame       the frame, its rods and its stand; the beads are the mesh's

ModelPart space is as everywhere: px, y down, the front -z, x mirrored; the parts here are written in model space (x east, y up) and
turned into it by trade_works_kit.mpart/mbox, so a part's `at` is the place its origin goes in the block's model space, and a turn
about z means the same in both. The renderer draws a part as WorksHoistRenderer does: translate to its place, turn about its pivot,
then scale(-1f, -1f, 1f) and submitModelPart.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import trade_works_kit as K  # noqa: E402
from trade_works_kit import mbox, mpart, np, Image, Canvas, PAL, grid, bands, fbm, vnoise, rand  # noqa: E402
import haul_board_icons as HI  # noqa: E402

W = K.W
Pic = K.Pic
BRASS, IRON, GILT = PAL['brass'], PAL['iron'], W.GILT
OAK = W.OAK
FIELD = W.GREEN

MODEL_NS = 'guildmark:block/'


# ================================================================== what the pieces are made of
def tl(key, name, edge=0.2, **by_face):
    """A cube painter giving every face a piece of tile [name] (by_face: another tile for a face)."""
    return K.tiled(key, {'*': name, **by_face}, edge)


def chain_painter(key):
    """A chain's links: pairs of bright and dark rows along its length, so a thread of brass reads as links."""
    def fn(face, w, h, rng):
        n = max(w, h)
        run = np.arange(n)
        link = np.where(run % 4 < 2, 0.62, 0.4) + 0.06 * (rng.random(n) - 0.5)
        t = np.broadcast_to(link[:, None], (h, w)) if h >= w else np.broadcast_to(link[None, :], (h, w))
        a = np.zeros((h, w, 4))
        a[..., :3] = bands(BRASS, t, 0.0)
        a[..., 3] = 255
        return a
    return K.tt.painter(key, fn)


def lit_tip_painter(key, ramp_, t=0.6):
    """A flat tone of [ramp_] with a lit edge: a small knob or a tip."""
    def fn(face, w, h, rng):
        a = np.zeros((h, w, 4))
        a[..., :3] = bands(ramp_, np.full((h, w), t) + 0.12 * (rng.random((h, w)) - 0.5), 0.2)
        a[..., 3] = 255
        return K.tt.shade_edges(a, 0.2)
    return K.tt.painter(key, fn)


# ================================================================== the scale
SCALE_PIVOT = (8.0, 24.2, 8.0)     # the beam's pivot, in the block's model space (px)
BEAM_HOOK = (6.6, -1.4)            # a pan's hook from the pivot, in the beam's own frame: across (east pan +x, west pan -x), down
BEAM_MAX = 16.0                    # the most the beam tips (degrees)
CHAIN = 5.9                        # a pan's chains: how far the rim hangs under the hook
RIM_R = 3.0                        # the pan's rim: its outside's apothem
GOODS, WEIGHTS = -1, 1             # a pan's side: the goods go on the west pan (the clerk's left), the weights on the east
SETTLE_TICKS = 40.0
SETTLE_TAU, SETTLE_PERIOD = 8.5, 18.0     # ticks the swing's amplitude falls by e; ticks it takes to swing once
SWAY_DEG, SWAY_TAU, SWAY_PERIOD = 5.0, 10.0, 24.0
IDLE_DEG, IDLE_W = 0.45, 0.036


def smooth(u):
    u = min(1.0, max(0.0, u))
    return u * u * (3.0 - 2.0 * u)


def damped(age, frm, to, ticks, tau, period, fade):
    """A damped swing from [frm] to [to]: [tau] ticks for its size to fall by e, [period] ticks to swing once, the last [fade] ticks
    easing the remnant out so it is exactly [to] at [ticks]."""
    if age <= 0:
        return frm
    if age >= ticks:
        return to
    w = 2 * math.pi / period
    k = math.exp(-age / tau) * (math.cos(w * age) + math.sin(w * age) / (w * tau))
    return to + (frm - to) * k * (1.0 - smooth((age - (ticks - fade)) / fade))


def settle(age, frm, to):
    """The beam's damped swing from [frm] degrees to [to], [age] ticks after the load changed: it overshoots about a third of the way back,
    swings back and is level (exact) by SETTLE_TICKS."""
    return damped(age, frm, to, SETTLE_TICKS, SETTLE_TAU, SETTLE_PERIOD, 10.0)


def pan_sway(age, phase):
    """Degrees a pan swings about its hook [age] ticks after the beam moved (phase: 0 for the swing from side to side, about 1.6 for
    the one to and fro), dying away by SETTLE_TICKS."""
    if age <= 0 or age >= SETTLE_TICKS:
        return 0.0
    return SWAY_DEG * math.exp(-age / SWAY_TAU) * math.sin(2 * math.pi * age / SWAY_PERIOD + phase) * (1.0 - smooth((age - 30.0) / 10.0))


def idle_tilt(t):
    """The beam's rest, never quite still: degrees at tick [t]."""
    return IDLE_DEG * math.sin(IDLE_W * t) + 0.6 * IDLE_DEG * math.sin(2.3 * IDLE_W * t + 1.0)


def hook_at(side, tilt):
    """Where the hook of the pan on [side] is in the block's model space (x, y) with the beam turned [tilt] degrees (positive: the
    west, goods end down)."""
    a = math.radians(tilt)
    hx, hy = side * BEAM_HOOK[0], BEAM_HOOK[1]
    return (SCALE_PIVOT[0] + hx * math.cos(a) - hy * math.sin(a), SCALE_PIVOT[1] + hx * math.sin(a) + hy * math.cos(a))


def clip_pan(side, bz=-1):
    """For the clerk's WEIGH clip (X4): the middle of a pan's floor at rest, in the clip space of a clerk standing in the block south of
    the scale (the block [bz] = -1 blocks from them, they facing it, north): their left, the goods pan, is at positive x. See
    works_art.clip_pt for the space; add a pan's tilt by passing the beam's tilt to hook_at yourself."""
    hx, hy = hook_at(side, 0.0)
    return W.clip_pt(hx, hy - CHAIN - 0.7, SCALE_PIVOT[2], bz=bz)


BENCH_TOP = 12.0                    # the clerk's bench top: the weights stand on it in a row, the ledger lies on it


# ---------------------------------------------------------------- the scale's picture on its post
DIAL_TPX = 4
DIAL_LO, DIAL_HI = (4.5, 14.8), (11.5, 19.8)      # the plate's corners on the post's front (px)
DIAL_Z = (6.3, 6.8)
DIAL_TICKS = 5.0                                  # degrees between its marks; the long ones at 0 and every 10
DIAL_SPAN = 20.0                                  # the marks run this far each side of level


def dial_texture():
    """The scale's dial (28 x 20 texels, the plate's 7 x 5 px at four to a pixel): polished brass in a chamfered frame, the beam's tongue
    swinging across it, marks every five degrees round the beam's pivot above the plate (long at level and every ten), a red mark
    at level."""
    w, h = int((DIAL_HI[0] - DIAL_LO[0]) * DIAL_TPX), int((DIAL_HI[1] - DIAL_LO[1]) * DIAL_TPX)
    p = Pic(w, h)
    plate = p.R(0, 0, w, h)
    p.cel(plate, BRASS, base=0.66, hi=0.25, lo=0.3, lw=1, dw=1)
    p.cv.over(plate & (fbm(w, h, 3, 6101) > 0.6), BRASS[4], 0.1)
    cx = (SCALE_PIVOT[0] - DIAL_LO[0]) * DIAL_TPX
    cy = (DIAL_HI[1] - SCALE_PIVOT[1]) * DIAL_TPX                      # the pivot, above the plate: negative
    tip = 8.4 * DIAL_TPX                                               # how far down the tongue's tip hangs, in texels
    r_in, r_out = tip - 4.0, tip + 1.6                                 # the two arcs the marks stand between
    d = np.hypot(p.xs - cx, p.ys - cy)
    within = np.abs(np.arctan2(p.xs - cx, p.ys - cy)) < math.radians(DIAL_SPAN + 1.5)
    for r in (r_in, r_out):
        p.cv.put((np.abs(d - r) < 0.55) & within & plate, BRASS[1])
    for k in range(-4, 5):
        a = math.radians(k * DIAL_TICKS)
        long_ = k % 2 == 0
        r1 = r_out if long_ else r_in + 2.4
        m = p.L(cx + r_in * math.sin(a), cy + r_in * math.cos(a), cx + r1 * math.sin(a), cy + r1 * math.cos(a), 1.0) & plate
        p.cv.put(m, BRASS[0])
    zero = (np.abs(p.xs - cx) < 1.2) & (d > r_in - 0.5) & (d < r_out + 0.5)
    p.cel(zero & plate, PAL['wax'], base=0.55, hi=0.2, lo=0.2)
    return p.cv.image()


# ---------------------------------------------------------------- the block
def scale_model():
    """The clerk's bench (oak, a lower shelf, the ledger and a row of iron weights on it), the brass-collared post with its dial, the
    forked head the beam turns in, and the brass cap. The beam and the pans are the mesh's (StoreArt.scaleMesh)."""
    c = W.cube
    els = []
    oakh = {'west': 'w_oak_end', 'east': 'w_oak_end', '*': 'w_oak_h'}
    els.append(c((0.5, 10.0, 2.0), (15.5, 12.0, 14.0), {'up': 'w_oak_h', **oakh}))                      # the top: boards
    for x0 in (1.2, 13.0):
        for z0 in (2.8, 11.4):
            els.append(c((x0, 0.0, z0), (x0 + 1.8, 10.0, z0 + 1.8), {'down': 'w_oak_end', '*': 'w_oak'}))   # the legs
    for z0 in (3.2, 11.8):
        els.append(c((3.0, 7.4, z0), (13.0, 9.2, z0 + 1.0), 'w_oak_h', ('north', 'south', 'up', 'down')))    # the aprons
    for x0 in (1.6, 13.4):
        els.append(c((x0, 7.4, 4.6), (x0 + 1.0, 9.2, 11.4), 'w_oak_h', ('west', 'east', 'up', 'down')))
    els.append(c((3.0, 3.0, 4.6), (13.0, 4.0, 11.4), 'w_oak_h', ('up', 'down', 'north', 'south')))        # the lower shelf
    for x0 in (1.4, 13.2):                                                                                  # its cleats
        els.append(c((x0, 2.6, 4.4), (x0 + 1.4, 4.4, 11.6), 'w_oak_h', ('up', 'down', 'north', 'south', 'west', 'east')))
    els.append(c((1.4, 12.0, 2.5), (5.6, 12.9, 6.1), {'up': 'fit_leather', '*': 'fit_leather'}))             # the ledger
    els.append(c((1.4, 12.9, 2.5), (5.6, 13.1, 6.1), {'up': 'fit_parchment', '*': 'fit_parchment'}))
    els.append(c((1.9, 13.1, 2.9), (5.2, 13.3, 5.7), {'up': 'fit_parchment', '*': 'fit_parchment'}))
    for i, x in enumerate((7.0, 9.0, 11.2, 13.2)):                                                           # the weights
        r, hgt = (1.0, 1.1) if i % 2 == 0 else (0.8, 0.8)
        els += W.disc(x, 3.6, 12.0, 12.0 + hgt, r, 'w_iron', top='w_iron')
        els.append(c((x - 0.3, 12.0 + hgt, 3.3), (x + 0.3, 12.0 + hgt + 0.35, 3.9), 'w_iron'))
    els.append(c((5.2, 12.0, 5.4), (10.8, 12.9, 10.6), {'up': 'fit_brass', '*': 'fit_brass'}))             # the plinth
    els.append(c((6.8, 12.9, 6.8), (9.2, 21.0, 9.2), {'up': 'w_oak_end', '*': 'w_oak'}))                    # the post
    for y0, y1 in ((12.9, 13.7), (20.2, 21.0)):
        els.append(c((6.4, y0, 6.4), (9.6, y1, 9.6), 'fit_brass'))
    x0, y0, x1, y1 = DIAL_LO[0], DIAL_LO[1], DIAL_HI[0], DIAL_HI[1]
    els.append(c((x0, y0, DIAL_Z[0]), (x1, y1, DIAL_Z[1]), {'north': 'store_scale_dial', '*': 'fit_brass'}, uv={'north': (0, 0, 16, 16)}))
    for z0, z1 in ((6.3, 7.3), (8.7, 9.7)):                                                                  # the fork's cheeks
        els.append(c((6.6, 21.0, z0), (9.4, 27.0, z1), {'up': 'w_oak_end', '*': 'w_oak'}))
    els.append(c((6.6, 21.0, 7.3), (9.4, 22.2, 8.7), {'up': 'w_oak_end', '*': 'w_oak'}, ('up', 'down', 'west', 'east')))   # the crotch
    els.append(c((6.2, 27.0, 6.1), (9.8, 27.8, 9.9), 'fit_brass'))                                           # the cap and its knob
    els.append(c((7.3, 27.8, 7.3), (8.7, 28.8, 8.7), 'fit_brass'))
    els.append(c((7.7, 28.8, 7.7), (8.3, 29.5, 8.3), 'fit_brass'))
    for y in (SCALE_PIVOT[1],):                                                                              # the pin's heads through the cheeks
        els.append(c((7.1, y - 0.9, 5.7), (8.9, y + 0.9, 6.3), 'fit_brass', ('north', 'up', 'down', 'west', 'east')))
        els.append(c((7.1, y - 0.9, 9.7), (8.9, y + 0.9, 10.3), 'fit_brass', ('south', 'up', 'down', 'west', 'east')))
    return K.model(els, 'w_oak')


# ---------------------------------------------------------------- the mesh: the beam and its two pans
def beam_part():
    br = lambda k: tl('beam_' + k, 'brass')
    cubes = [
        mbox('beam_c', -2.2, 2.2, -1.25, 1.25, -0.55, 0.55, br('c')),
        mbox('beam_w1', -4.6, -2.2, -0.85, 0.85, -0.55, 0.55, br('w1')),
        mbox('beam_e1', 2.2, 4.6, -0.85, 0.85, -0.55, 0.55, br('e1')),
        mbox('beam_w2', -6.6, -4.6, -0.55, 0.55, -0.55, 0.55, br('w2')),
        mbox('beam_e2', 4.6, 6.6, -0.55, 0.55, -0.55, 0.55, br('e2')),
        mbox('beam_wk', -7.5, -6.6, -0.75, 0.75, -0.75, 0.75, br('wk')),
        mbox('beam_ek', 6.6, 7.5, -0.75, 0.75, -0.75, 0.75, br('ek')),
        mbox('beam_wh', -6.95, -6.25, -1.9, -0.55, -0.3, 0.3, tl('beam_wh', 'iron')),
        mbox('beam_eh', 6.25, 6.95, -1.9, -0.55, -0.3, 0.3, tl('beam_eh', 'iron')),
        mbox('beam_pf', -0.9, 0.9, -0.9, 0.9, -2.2, -1.7, br('pf')),
        mbox('beam_pb', -0.9, 0.9, -0.9, 0.9, 1.7, 2.2, br('pb')),
    ]
    tongue = mpart('tongue', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
        mbox('tongue_n', -0.28, 0.28, -8.4, 0.9, -2.75, -2.2, tl('tongue_n', 'iron', edge=0.1)),
        mbox('tongue_tip', -0.62, 0.62, -8.6, -7.6, -2.85, -2.15, lit_tip_painter('tongue_tip', PAL['wax'], 0.7)),
        mbox('tongue_cw', -0.6, 0.6, 0.9, 1.7, -2.8, -2.15, lit_tip_painter('tongue_cw', BRASS, 0.7)),
    ])
    return mpart('beam', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), cubes, [tongue])


def pan_part(name, side):
    """A pan, standing at its hook: three chains down to the rim, the rim, the dish and its foot; on the goods pan the sack
    ("sack"), on the other the weights ("weight_0" to "weight_2", set on one at a time)."""
    chain = chain_painter(name + '_chain')
    brass = tl(name + '_brass', 'brass')
    kids = []
    for k, ang in enumerate((-90.0, 150.0, 30.0)):
        a = math.radians(ang)
        kids.append(K.strut(f'{name}_chain{k}', (0, 0, 0), ((RIM_R - 0.4) * math.cos(a), -CHAIN, (RIM_R - 0.4) * math.sin(a)), 0.5, chain))
    dish_c, dish_k = K.octa_disc(name + '_dish', RIM_R - 0.3, -CHAIN - 1.6, -CHAIN - 0.7, brass)
    foot_c, foot_k = K.octa_disc(name + '_foot', 1.7, -CHAIN - 2.2, -CHAIN - 1.6, tl(name + '_foot', 'brass'))
    rim_c, rim_k = K.octa_ring(name + '_rim', RIM_R, 0.6, -CHAIN - 0.8, -CHAIN + 0.1, tl(name + '_rim', 'brass', edge=0.1))
    rest = -CHAIN - 0.7
    if side == GOODS:
        sack = mpart('sack', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
            mbox('sack_body', -1.5, 1.4, rest, rest + 2.5, -1.3, 1.2, tl('sack_body', 'sackcloth', edge=0.25)),
            mbox('sack_lump', 0.3, 1.9, rest, rest + 1.3, 0.5, 2.0, tl('sack_lump', 'sackcloth', edge=0.25)),
            mbox('sack_neck', -0.8, 0.8, rest + 2.5, rest + 3.1, -0.7, 0.7, tl('sack_neck', 'sackcloth', edge=0.25)),
            mbox('sack_tie', -1.0, 1.0, rest + 2.35, rest + 2.7, -1.0, 1.0, tl('sack_tie', 'rope_h', edge=0.1)),
        ])
        kids.append(sack)
    else:
        for i, (x0, x1, z0, z1, hgt) in enumerate(((-1.9, -0.3, -0.8, 0.8, 1.3), (0.2, 1.6, -0.7, 0.7, 0.9), (-0.9, 0.5, 0.9, 2.2, 0.6))):
            kids.append(mpart(f'weight_{i}', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
                mbox(f'weight_{i}', x0, x1, rest, rest + hgt, z0, z1, tl(f'weight_{i}', 'iron', edge=0.15)),
                mbox(f'weight_{i}_knob', (x0 + x1) / 2 - 0.3, (x0 + x1) / 2 + 0.3, rest + hgt, rest + hgt + 0.35, (z0 + z1) / 2 - 0.3, (z0 + z1) / 2 + 0.3,
                     tl(f'weight_{i}_knob', 'iron', edge=0.1))]))
    return mpart(name, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), dish_c + foot_c + rim_c, kids + dish_k + foot_k + rim_k)


# ================================================================== the name board and the door board
BOARD = (28.0, 8.5, 1.2)                          # the name board: wide, high, thick (px)
BOARD_PIVOT = (8.0, 13.8, 3.1)                    # the line through its rings (along x), in the block's model space
BOARD_RINGS = 10.0                                # the rings' distance each side of the middle
BOARD_DROP = 1.2                                  # the board's top under the line
BOARD_TPX = 4                                     # texels to a pixel, of the boards' mesh texture
BOARD_TEXT = (-9.2, 9.2)                          # the lettering field's ends (px, across the board from its middle)
DOOR = (6.0, 5.0, 0.6)                            # the door board: wide, high, thick
DOOR_PIVOT = (8.0, 12.0, 13.7)                    # the hook's tip, which the board and its cord turn about (up)
DOOR_CORD = 1.5                                   # the cord's length


def sign_face(w, h, device, seed, field=FIELD, rule=GILT):
    """A painted sign's face [w] x [h] texels: boards of a deep green painted field in a dark oak frame with a gilt rule inside it, a
    roundel with [device] (an RGBA picture, or None) at each end, and between them the dark panel the name is lettered on (the
    renderer's: the lettering is ink on this panel, its field BOARD_TEXT across)."""
    cv = Canvas(w, h)
    xs, ys = grid(w, h)
    n = fbm(w, h, 5, seed)
    plank = np.floor(ys / (h / 3.0))
    t = 0.46 + 0.1 * (n - 0.5) + 0.04 * np.sin(plank * 3.1 + seed) + 0.03 * (rand(w, h, seed + 1) - 0.5)
    cv.put(np.ones((h, w), bool), bands(field, t, 0.3))
    seam = (np.abs(ys - h / 3.0) < 0.6) | (np.abs(ys - 2 * h / 3.0) < 0.6)
    cv.over(seam, field[0], 0.6)
    fr = 4                                                              # the frame, a pixel wide
    edge_ = (xs < fr) | (xs > w - fr) | (ys < fr) | (ys > h - fr)
    cv.put(edge_, bands(PAL['darkwood'], 0.5 + 0.1 * (n - 0.5) + 0.2 * ((xs < fr - 1.5) | (ys < fr - 1.5)) - 0.18 * ((xs > w - fr + 1) | (ys > h - fr + 1)), 0.3))
    rl = (np.maximum(np.abs(xs - w / 2) - (w / 2 - fr - 1.0), np.abs(ys - h / 2) - (h / 2 - fr - 1.0)))
    full = lambda v: np.full((h, w), float(v))
    cv.put((rl > -0.6) & (rl < 0.6), bands(rule, full(0.72), 0.0))
    cv.put((rl > 0.6) & (rl < 1.4), bands(rule, full(0.2), 0.0))
    # the panel for the name
    px0, px1 = (w / 2 + BOARD_TEXT[0] * BOARD_TPX), (w / 2 + BOARD_TEXT[1] * BOARD_TPX)
    panel = (xs > px0) & (xs < px1) & (ys > fr + 3) & (ys < h - fr - 3)
    cv.put(panel, bands(field, 0.2 + 0.03 * (n - 0.5), 0.2))
    edge2 = panel & ((xs < px0 + 1.2) | (ys < fr + 4.2))
    cv.put(edge2, bands(field, full(0.05), 0.0))
    cv.put(panel & ((xs > px1 - 1.2) | (ys > h - fr - 4.2)), bands(rule, full(0.3), 0.0))
    if device is not None:
        dh, dw = device.size[1], device.size[0]
        dev = Canvas.of(device)
        for cx in (fr + 2 + dw / 2 + 1.0, w - fr - 2 - dw / 2 - 1.0):
            x0, y0 = int(round(cx - dw / 2)), int(round(h / 2 - dh / 2))
            for j in range(dh):
                for i in range(dw):
                    if dev.a[j, i] > 0.02 and 0 <= x0 + i < w and 0 <= y0 + j < h:
                        cv.px(x0 + i, y0 + j, dev.rgb[j, i], 1.0)
    return cv.image()


def crate_hook_device(size=22):
    """The Store's device: a crate hung from the hoist's hook, in gilt on the dark of a roundel."""
    p = Pic(size)
    c = size / 2.0
    d = np.hypot(p.xs - c, p.ys - c)
    p.cel(d < c - 0.4, FIELD, base=0.32, hi=0.14, lo=0.1)
    ring = (d > c - 3.2) & (d < c - 1.9)
    p.flat(ring, GILT, 0.62)
    p.flat(ring & (p.xs + p.ys < c * 2 - 1), GILT, 0.9)
    p.flat(p.path([(c, c - 0.2), (c, c - 3.6)], 1.0), GILT, 0.78)                     # the chain from the hook
    ringd = np.abs(np.hypot(p.xs - c, p.ys - (c - 4.9)) - 1.7)
    p.flat(ringd < 0.6, GILT, 0.8)                                                      # the hook's eye
    crate = p.R(c - 4.6, c - 0.2, c + 4.6, c + 6.4)
    p.cel(crate, GILT, base=0.56, hi=0.22, lo=0.3)
    for y in (c + 2.0, c + 4.2):
        p.flat(p.R(c - 4.6, y, c + 4.6, y + 0.8), GILT, 0.2)                           # the gaps between its slats
    p.flat(p.R(c - 4.6, c - 0.2, c - 3.4, c + 6.4) | p.R(c + 3.4, c - 0.2, c + 4.6, c + 6.4), GILT, 0.8)   # its stiles
    p.flat(p.R(c - 4.6, c - 0.2, c + 4.6, c + 0.8), GILT, 0.9)                         # its lit lid edge
    return p.cv.image()


def door_open_face(w=24, h=20):
    """OPEN: a rising sun over a door standing wide, the dark of its leaf and the light of the room behind it, on a pale board."""
    p = Pic(w, h)
    board = p.R(0, 0, w, h)
    p.cel(board, W.CANVAS, base=0.72, hi=0.2, lo=0.3, lw=1, dw=1)
    p.flat(board & ((p.xs < 1.5) | (p.xs > w - 1.5) | (p.ys < 1.5) | (p.ys > h - 1.5)), FIELD, 0.5)
    frame = p.R(4, 5, 14, 18)
    p.flat(frame, PAL['darkwood'], 0.4)
    room = p.R(5, 6, 13, 18)
    p.flat(room, W.STRAW, 0.9)
    p.flat(p.R(5, 6, 13, 7), W.STRAW, 0.6)
    leaf = p.P([(13, 6.2), (19, 8.0), (19, 16.8), (13, 18)])
    p.cel(leaf, PAL['wood'], base=0.5, hi=0.25, lo=0.25)
    p.flat(p.path([(14.5, 6.6), (14.5, 17.4)], 0.8) & leaf, PAL['wood'], 0.2)
    p.flat(np.hypot(p.xs - 17.5, p.ys - 11.2) < 0.9, BRASS, 0.8)
    sun = (np.hypot(p.xs - 17.5, p.ys - 5.2) < 2.8) & (p.ys < 5.6) & (p.xs > 3)
    p.cel(sun, GILT, base=0.78, hi=0.2, lo=0.25)
    for a in (-70, -35, 0, 35, 70):
        r = math.radians(a)
        p.flat(p.path([(17.5 + 3.4 * math.sin(r), 5.2 - 3.4 * math.cos(r)), (17.5 + 4.6 * math.sin(r), 5.2 - 4.6 * math.cos(r))], 0.8) & (p.ys > 2.0), GILT, 0.7)
    return p.cv.image()


def door_shut_face(w=24, h=20):
    """SHUT: a crescent moon over a door barred across, on a dusk-blue board."""
    p = Pic(w, h)
    board = p.R(0, 0, w, h)
    p.cel(board, PAL['slate'], base=0.52, hi=0.25, lo=0.3, lw=1, dw=1)
    p.flat(board & ((p.xs < 1.5) | (p.xs > w - 1.5) | (p.ys < 1.5) | (p.ys > h - 1.5)), PAL['blacksteel'], 0.5)
    door = p.R(5, 5, 15, 18)
    p.cel(door, PAL['wood'], base=0.42, hi=0.25, lo=0.3)
    for x in (8, 11):
        p.flat(p.R(x, 6, x + 1, 17), PAL['wood'], 0.18)
    bar = p.R(3, 10, 17, 13)
    p.cel(bar, PAL['darkwood'], base=0.5, hi=0.3, lo=0.3)
    p.flat(p.R(3, 10, 17, 11), PAL['darkwood'], 0.82)
    for x in (4, 15):
        p.flat(p.R(x, 9, x + 2, 14), PAL['iron'], 0.55)
    moon = (np.hypot(p.xs - 19.0, p.ys - 5.0) < 3.2) & ~(np.hypot(p.xs - 20.4, p.ys - 4.2) < 2.8)
    p.flat(moon, W.CANVAS, 0.8)
    p.flat(p.D(15.5, 2.6, 0.8), W.CANVAS, 0.9)
    return p.cv.image()


# the boards' mesh: texture 64 x 32 declared, painted at BOARD_TPX
def boards_parts():
    w, h, d = BOARD
    pw = 'oakbar'
    dev = crate_hook_device()
    face = sign_face(int(w * BOARD_TPX), int(h * BOARD_TPX), dev, 6301)

    def sign_paint(key, img, edge_tile='dark_h'):
        def fn(face_, fw, fh, rng):
            if face_ in ('front', 'back'):
                a = np.asarray(img.resize((fw, fh), Image.NEAREST) if img.size != (fw, fh) else img, float)
                return a.copy()
            return K.tt.shade_edges(W.from_tile(edge_tile, fw, fh, rng), 0.2)
        return K.tt.painter(key, fn, light=False)

    ring_p = tl('ring', 'iron', edge=0.1)
    board = mpart('board', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
        mbox('board', -w / 2, w / 2, -BOARD_DROP - h, -BOARD_DROP, -d / 2, d / 2, sign_paint('board', face)),
        mbox('board_ring_w', -BOARD_RINGS - 0.3, -BOARD_RINGS + 0.3, -BOARD_DROP - 0.2, 0.1, -0.3, 0.3, ring_p),
        mbox('board_ring_e', BOARD_RINGS - 0.3, BOARD_RINGS + 0.3, -BOARD_DROP - 0.2, 0.1, -0.3, 0.3, tl('ring_e', 'iron', edge=0.1)),
    ])
    dw, dh, dd = DOOR
    open_ = door_open_face(int(dw * BOARD_TPX), int(dh * BOARD_TPX))
    shut = door_shut_face(int(dw * BOARD_TPX), int(dh * BOARD_TPX))

    def door_paint(key):
        def fn(face_, fw, fh, rng):
            if face_ == 'front':
                return np.asarray(open_, float).copy()
            if face_ == 'back':
                return np.asarray(shut.transpose(Image.FLIP_LEFT_RIGHT), float).copy()
            return K.tt.shade_edges(W.from_tile('dark_h', fw, fh, rng), 0.2)
        return K.tt.painter(key, fn, light=False)
    door = mpart('door_board', (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [
        mbox('door_board', -dw / 2, dw / 2, -DOOR_CORD - dh, -DOOR_CORD, -dd / 2, dd / 2, door_paint('door_board')),
        mbox('door_cord', -0.2, 0.2, -DOOR_CORD - 0.1, 0.0, -0.2, 0.2, tl('door_cord', 'rope_v', edge=0.05)),
    ])
    return [board, door]


def boards_mesh():
    return K.Mesh('boardsMesh', 'store_boards', boards_parts(), 64, 32, BOARD_TPX,
                  'The Store\'s two hanging boards: "board" (the name board, swinging about the line through its two rings, which is the origin; '
                  'its front face toward -z and its back both the Store\'s device and the dark panel the name is lettered on) and "door_board" '
                  '(the open and shut board, turning about up through the hook\'s tip at its origin: OPEN faces -z, SHUT the back).')


def scale_mesh():
    return K.Mesh('scaleMesh', 'store_scale', [beam_part(), pan_part('pan_goods', GOODS), pan_part('pan_weights', WEIGHTS)], 128, 64, 2,
                  'The scale\'s moving pieces: "beam" (turning about z at its pivot, [StoreArt.SCALE_PIVOT_X], with "tongue" swinging across '
                  'the dial), "pan_goods" (west) and "pan_weights" (east), each standing at the hook it hangs from (x, y from '
                  '[StoreArt.hookX], [StoreArt.hookY]); on the goods pan "sack", on the other "weight_0" to "weight_2".')


# ================================================================== the boards' blocks
def name_board_model():
    """A wall bracket: an iron plate on the wall, a timber arm out from it with a knee brace under, and across its end a crossbar the
    Store's name board hangs from by two rings (the board and its rings are the mesh's, StoreArt.boardsMesh), two iron eyes
    under the bar where they take it. Out toward the street, as the "To let" board's, and a good deal wider."""
    c = W.cube
    ends = {'west': 'w_oak_end', 'east': 'w_oak_end', '*': 'w_oak_h'}
    els = [c((6.0, 11.2, 14.4), (10.0, 16.0, 16.0), 'w_iron'),
           c((7.1, 14.0, 3.9), (8.9, 15.5, 14.4), {'north': 'w_oak_end', '*': 'w_oak_h'}),
           c((8.0 - 13.0, 13.8, BOARD_PIVOT[2] - 0.8), (8.0 + 13.0, 15.6, BOARD_PIVOT[2] + 0.8), ends),
           W.along((8.0, 10.4, 14.5), (8.0, 14.0, 9.6), 1.2, 1.0, 'w_oak')]
    for x in (8.0 - BOARD_RINGS, 8.0 + BOARD_RINGS):
        els.append(c((x - 0.5, 13.5, BOARD_PIVOT[2] - 0.5), (x + 0.5, 13.8, BOARD_PIVOT[2] + 0.5), 'w_iron'))
    return K.model(els, 'w_oak')


def door_board_model():
    """A hook on the wall for the door board: an iron plate, an arm out from it and its tip turned down."""
    c = W.cube
    x, y, z = DOOR_PIVOT
    els = [c((x - 1.2, y - 1.4, 15.0), (x + 1.2, y + 1.4, 16.0), 'w_iron'),
           c((x - 0.4, y - 0.4, z - 0.4), (x + 0.4, y + 0.4, 15.0), 'w_iron'),
           c((x - 0.4, y - 1.0, z - 0.4), (x + 0.4, y - 0.4, z + 0.4), 'w_iron')]
    return K.model(els, 'w_iron')


# ================================================================== the strongbox and the counting frame: their materials
def coin_tile(seed=7301):
    """A heap of gold Marks seen from above, 32 x 32 texels (a block face at two to a pixel), wrapping on itself: about fifty coins
    lying about each other, each a bright rim round a worn field with a small mark struck in it and a shadow under it, the dark of
    the gaps between them showing through."""
    n = 32
    p = Pic(n)
    p.flat(p.R(0, 0, n, n), K.COIN, 0.05)
    rng = np.random.default_rng(seed)
    coins = sorted(((rng.uniform(0, n), rng.uniform(0, n), rng.uniform(2.6, 3.4), rng.uniform(0.7, 1.0), rng.uniform(0, 180),
                     rng.uniform(0.42, 0.78), rng.uniform(0.0, 1.0)) for _ in range(52)), key=lambda c: c[6])
    for cx0, cy0, r, sq, rot, tone, _ in coins:
        for ox in (-n, 0, n):
            for oy in (-n, 0, n):
                cx, cy = cx0 + ox, cy0 + oy
                if cx < -r - 2 or cx > n + r + 2 or cy < -r - 2 or cy > n + r + 2:
                    continue
                body = p.E(cx, cy, r, r * sq, rot)
                p.cv.over(p.E(cx + 0.9, cy + 1.1, r, r * sq, rot) & ~body, K.COIN[0], 0.6)
                p.cel(body, K.COIN, base=tone, hi=0.26, lo=0.3, lw=1, dw=1)
                inner = p.E(cx, cy, r - 1.2, (r - 1.2) * sq, rot)
                p.flat(inner, K.COIN, tone - 0.1)
                p.flat(p.E(cx + 0.3, cy + 0.3, 0.8, 0.8 * sq, rot) & inner, K.COIN, tone - 0.22)
                if 0 <= int(cx - 0.4) < n and 0 <= int(cy - 0.4) < n:
                    p.px(cx - 0.4, cy - 0.4, K.COIN[4], 0.9)
    return p.cv


W.TILE_PAINTERS['coins'] = coin_tile

# the block models name a mesh painter's tile by the picture it is saved as
BLOCK_TEX = {'dark_h': 'fit_dark_h', 'dark_v': 'fit_dark_v', 'dark_end': 'fit_dark_end', 'brass': 'fit_brass', 'leather': 'fit_leather',
             'parch': 'fit_parchment', 'coins': 'fit_coins', 'iron': 'w_iron'}
FACE_BLOCK = {'front': 'north', 'back': 'south', 'top': 'up', 'bottom': 'down', 'right': 'east', 'left': 'west'}
ALL_FACES = tuple(FACE_BLOCK)


def box(name, b, tiles, faces=ALL_FACES):
    """A box of a mesh part, written once for the mesh and for the block model: [b] is (x0, x1, y0, y1, z0, z1) from its part's origin,
    [tiles] the tile of each face ('*' for the rest), [faces] the faces it shows (the mesh's names)."""
    return (name, b, tiles, tuple(faces))


def mesh_box(spec, edge=0.2):
    name, (x0, x1, y0, y1, z0, z1), tiles, faces = spec
    return mbox(name, x0, x1, y0, y1, z0, z1, K.tiled(name, tiles, edge), faces)


def model_box(spec, origin, turn=0.0):
    """The block-model element of [spec]: its part standing at [origin] (block px), turned [turn] degrees about x (model space)."""
    name, (x0, x1, y0, y1, z0, z1), tiles, faces = spec
    ox, oy, oz = origin
    tex = {FACE_BLOCK[f]: BLOCK_TEX[t] for f, t in tiles.items() if f != '*'}
    tex['*'] = BLOCK_TEX[tiles['*']]
    return W.cube((ox + x0, oy + y0, oz + z0), (ox + x1, oy + y1, oz + z1), tex, [FACE_BLOCK[f] for f in faces],
                  rot=W.Rx(turn) if turn else None, origin=origin)


# ================================================================== the strongbox
BOX_X, BOX_Z, BOX_Y = (1.0, 15.0), (2.7, 11.3), (0.7, 7.0)      # the body: across, front to back, the foot of its walls to its rim
LID_PIVOT = (8.0, 7.0, 11.6)                                    # the hinge pin, in the block's model space: the lid turns about x through it
LID_OPEN = 94.0                                                 # the most it opens (degrees): it leans back a little, short of the wall behind
LID_OPEN_TICKS, LID_SHUT_TICKS, LID_SETTLE_TICKS = 6.0, 8.0, 30.0
LID_OVER, LID_FALL, LID_BOUNCE = 0.9, 6.0, 0.045                # opening: the curve's overshoot (3%); shutting: ticks it falls, the rebound's fraction
HASP_SWING, HASP_TAU, HASP_PERIOD = 16.0, 7.0, 10.0
LID_HALF, LID_FRONT, LID_BACK = 7.3, -9.7, 0.5                  # in the lid's frame (origin at the pin, z south): half its width, its front, its back
LID_SKIRT, LID_CROWN = 1.5, 3.2                                 # the height of its rim, and of the top of its arch
LID_FLAT = (-6.0, -3.2)                                         # the flat of the arch, front to back
BEVEL = math.degrees(math.atan2(LID_CROWN - LID_SKIRT, LID_FLAT[0] - LID_FRONT))      # the arch's two slopes
BEVEL_LEN = math.hypot(LID_CROWN - LID_SKIRT, LID_FLAT[0] - LID_FRONT)
HASP_AT = (0.0, 0.0, LID_FRONT)                                 # where the hasp hangs from, in the lid's frame: its pin, at the seam
BAND_X, BAND_W = 4.3, 1.5                                       # the lid's two iron straps: their middles' distance from the middle, their width
LOCK = ((5.8, 10.2), (1.4, 5.6), BOX_Z[0] - 0.8)                          # the lock case on the body's front: across, from the floor, how far out its face stands (z)


def lid_layout():
    """The lid by the part that holds each box: [(part, (y, z) of its origin in the lid's frame, turn about x, [boxes])]. The part "lid" is
    the root (a skirt, the flat of the arch, the straps over it, the hinge's knuckles); "lid_slope_f" and "lid_slope_b" are the arch's two
    slopes (turned about their lower edges so that their tops rise to the flat), its children; "hasp" is the iron strap that hangs from
    the lid's front over the lock case: a part of its own, since it hangs from the lid and swings free of it."""
    H, SK, CR, F, B = LID_HALF, LID_SKIRT, LID_CROWN, LID_FRONT, LID_BACK
    fl0, fl1 = LID_FLAT
    wood = {'*': 'dark_h', 'left': 'dark_end', 'right': 'dark_end'}
    iron = {'*': 'iron'}
    root = [box('lid_skirt', (-H, H, 0.0, SK, F, B), {**wood, 'bottom': 'leather'}, ('front', 'back', 'bottom', 'left', 'right')),
            box('lid_flat', (-H, H, SK, CR, fl0, fl1), wood, ('top', 'left', 'right'))]
    f_ = [box('lid_slope_f', (-H, H, -1.0, 0.0, 0.0, BEVEL_LEN), wood, ('top', 'left', 'right'))]
    b_ = [box('lid_slope_b', (-H, H, -1.0, 0.0, -BEVEL_LEN, 0.0), wood, ('top', 'left', 'right'))]
    for k, sx in enumerate((-BAND_X, BAND_X)):
        x0, x1 = sx - BAND_W / 2, sx + BAND_W / 2
        root += [box(f'lid_band_flat{k}', (x0, x1, CR, CR + 0.22, fl0, fl1), iron, ('top', 'left', 'right')),
                 box(f'lid_band_front{k}', (x0, x1, 0.0, SK, F - 0.18, F), iron, ('front', 'left', 'right', 'bottom', 'top')),
                 box(f'lid_band_back{k}', (x0, x1, 0.0, SK, B, B + 0.18), iron, ('back', 'left', 'right', 'bottom', 'top')),
                 box(f'lid_hinge{k}', (x0, x1, 0.0, SK, B + 0.18, B + 0.4), iron, ('back', 'left', 'right', 'bottom', 'top')),
                 box(f'lid_knuckle{k}', (sx - 1.5, sx + 1.5, -0.5, 0.5, -0.5, 0.5), iron)]
        f_.append(box(f'lid_band_slope_f{k}', (x0, x1, 0.0, 0.22, 0.0, BEVEL_LEN), iron, ('top', 'left', 'right')))
        b_.append(box(f'lid_band_slope_b{k}', (x0, x1, 0.0, 0.22, -BEVEL_LEN, 0.0), iron, ('top', 'left', 'right')))
        for j, z in enumerate((-5.4, -3.8)):                                       # nail heads along the strap's flat
            root.append(box(f'lid_nail{k}{j}', (sx - 0.3, sx + 0.3, CR + 0.22, CR + 0.42, z - 0.3, z + 0.3), {'*': 'brass'}, ('top', 'front', 'back', 'left', 'right')))
    hasp = [box('hasp_strap', (-1.3, 1.3, -2.8, 0.6, -0.5, 0.0), iron, ('front', 'left', 'right', 'bottom', 'top')),
            box('hasp_tongue', (-0.9, 0.9, -3.6, -2.8, -0.5, 0.0), iron, ('front', 'left', 'right', 'bottom')),
            box('hasp_knuckle', (-1.7, 1.7, -0.5, 0.7, -0.8, 0.2), {'*': 'brass'}),
            box('hasp_rivet0', (-1.0, -0.4, -1.8, -1.2, -0.7, -0.5), {'*': 'brass'}, ('front', 'top', 'left', 'right')),
            box('hasp_rivet1', (0.4, 1.0, -1.8, -1.2, -0.7, -0.5), {'*': 'brass'}, ('front', 'top', 'left', 'right'))]
    return [('lid', (0.0, 0.0), 0.0, root), ('lid_slope_f', (SK, F), -BEVEL, f_), ('lid_slope_b', (SK, B), BEVEL, b_),
            ('hasp', (HASP_AT[1], HASP_AT[2]), 0.0, hasp)]


def lid_parts():
    """The strongbox mesh's parts: "lid" (with the two slopes as its children) and "hasp", each standing at its own origin."""
    built = {}
    for name, (y, z), turn, boxes in lid_layout():
        built[name] = mpart(name, (0.0, y, z), (turn, 0.0, 0.0), [mesh_box(b) for b in boxes])
    built['hasp'].pivot = (0.0, 0.0, 0.0)           # a part drawn on its own stands at its origin: the table gives where
    built['lid'].children = [built['lid_slope_f'], built['lid_slope_b']]
    return [built['lid'], built['hasp']]


def lid_reach(angle):
    """The furthest south (z from the pin) any corner of the lid and the hasp (hanging plumb) comes with the lid turned [angle] degrees."""
    th = math.radians(angle)
    far = -1e9
    for name, (oy, oz), turn, boxes in lid_layout():
        t = math.radians(turn)
        for _, (x0, x1, y0, y1, z0, z1), _, _ in boxes:
            if name == 'hasp':                         # it hangs plumb from its pivot, which goes with the lid
                far = max(far, oy * math.sin(th) + oz * math.cos(th) + z1)
                continue
            for y in (y0, y1):
                for z in (z0, z1):
                    yl, zl = oy + y * math.cos(t) - z * math.sin(t), oz + y * math.sin(t) + z * math.cos(t)
                    far = max(far, yl * math.sin(th) + zl * math.cos(th))
    return far


def lid_elements():
    """The lid and its hasp, shut, as block-model elements: what strongbox_shut shows."""
    px, py, pz = LID_PIVOT
    els = []
    for name, (y, z), turn, boxes in lid_layout():
        els += [model_box(b, (px, py + y, pz + z), turn) for b in boxes]
    return els


def strongbox_body(coins=True):
    """The strongbox without its lid: dark oak planks bound in iron (straps up the front and back, plates on the corners, a band and
    a carrying ring on each end, four iron shoes), a brass lock case in the middle of its front, and, open to the top, the coins heaped in it."""
    c = W.cube
    x0, x1 = BOX_X
    z0, z1 = BOX_Z
    y0, y1 = BOX_Y
    t = 1.1
    plank = {'*': 'fit_dark_h', 'up': 'fit_dark_end'}
    els = [c((x0, y0, z0), (x1, y0 + 0.9, z1), {'*': 'fit_dark_h', 'down': 'fit_dark_end'}),
           c((x0, y0 + 0.9, z0), (x1, y1, z0 + t), plank),
           c((x0, y0 + 0.9, z1 - t), (x1, y1, z1), plank),
           c((x0, y0 + 0.9, z0 + t), (x0 + t, y1, z1 - t), plank),
           c((x1 - t, y0 + 0.9, z0 + t), (x1, y1, z1 - t), plank)]
    for fx in (x0 + 0.4, x1 - 2.4):                                           # the iron shoes
        for fz in (z0 + 0.5, z1 - 2.5):
            els.append(c((fx, 0.0, fz), (fx + 2.0, y0 + 0.02, fz + 2.0), 'w_iron'))
    for cx, sx in ((x0, 1.0), (x1, -1.0)):                                    # the corners' plates
        for cz, sz in ((z0, 1.0), (z1, -1.0)):
            ax, bx = sorted((cx - 0.15 * sx, cx + 1.4 * sx))
            az, bz = sorted((cz - 0.15 * sz, cz + 1.4 * sz))
            els.append(c((ax, y0, az), (bx, y1, bz), 'w_iron', ('north', 'south', 'west', 'east', 'up')))
    for sx in (4.8, 11.2):                                                    # the straps up the front and the back, rivets on the front's
        els.append(c((sx - 0.75, y0, z0 - 0.15), (sx + 0.75, y1, z0), 'w_iron', ('north', 'west', 'east', 'up')))
        els.append(c((sx - 0.75, y0, z1), (sx + 0.75, y1, z1 + 0.15), 'w_iron', ('south', 'west', 'east', 'up')))
        for ry in (1.9, 3.5, 5.1, 6.3):
            els.append(c((sx - 0.3, ry, z0 - 0.3), (sx + 0.3, ry + 0.5, z0 - 0.15), 'fit_brass', ('north', 'up', 'west', 'east')))
    for ax, bx, out, s in ((x0 - 0.15, x0, 'west', -1.0), (x1, x1 + 0.15, 'east', 1.0)):       # a band round each end, and a carrying ring
        els.append(c((ax, 2.4, z0 + 1.4), (bx, 3.9, z1 - 1.4), 'w_iron', (out, 'north', 'south', 'up')))
        ex = x0 if s < 0 else x1
        for ez in (z0 + 3.0, z1 - 3.8):
            a, b = sorted((ex, ex + 0.6 * s))
            els.append(c((a, 4.1, ez), (b, 5.3, ez + 0.8), 'w_iron'))
        a, b = sorted((ex + 0.5 * s, ex + 1.1 * s))
        els.append(c((a, 4.5, z0 + 3.0), (b, 5.3, z1 - 3.0), 'w_iron'))
    (lx0, lx1), (ly0, ly1), lz = LOCK                                          # the lock case, its keyhole
    els.append(c((lx0, ly0, lz), (lx1, ly1, z0), 'fit_brass', ('north', 'up', 'down', 'west', 'east')))
    els.append(c((7.55, ly0 + 0.9, lz - 0.1), (8.45, ly0 + 1.7, lz), 'fit_dark_end', ('north',)))
    if coins:
        els += [c((x0 + 1.3, y0 + 0.9, z0 + t + 0.2), (x1 - 1.3, 5.4, z1 - t - 0.2), 'fit_coins'),
                c((x0 + 1.6, 5.4, z0 + t + 0.5), (x1 - 1.6, 6.1, z1 - t - 0.5), 'fit_coins'),
                c((4.0, 6.1, 6.6), (12.0, 6.7, 10.2), 'fit_coins', ('up', 'north', 'south', 'west', 'east'))]
        for cx, cz in ((5.4, 7.0), (7.4, 9.6), (9.0, 7.6)):                                        # coins lying on the heap
            els += W.disc(cx, cz, 6.7, 6.95, 1.0, 'fit_coins')
        els += W.disc(11.0, 8.6, 6.7, 6.85, 1.0, 'fit_coins') + W.disc(11.0, 8.6, 6.85, 7.0, 0.9, 'fit_coins')      # and a pair stacked
    return els


def strongbox_model(shut=False):
    return K.model(strongbox_body(coins=not shut) + (lid_elements() if shut else []), 'fit_dark_h')


def strongbox_mesh():
    return K.Mesh('strongboxMesh', 'store_strongbox', lid_parts(), 96, 48, 2,
                  'The strongbox\'s moving pieces: "lid" (turning about x at the hinge pin, [StoreArt.LID_X] and the rest, as [StoreArt.lidAngle] says; '
                  'positive lifts its front, toward -z; "lid_slope_f" and "lid_slope_b" are its children) and "hasp", the iron strap that hangs from the '
                  'lid\'s front over the lock case and swings free of it (drawn at [StoreArt.HASP_X] and the rest, in the lid\'s own frame, turned about '
                  'x by [StoreArt.haspAngle]).')


# ================================================================== the counting frame
FRAME_X, POST_W = (1.0, 15.0), 1.2
FRAME_INNER = (FRAME_X[0] + POST_W, FRAME_X[1] - POST_W)       # the rods' free length: from post to post
ROD_Z = 8.0
ROWS, COLS = 6, 9                                              # rods, and beads on a rod
ROD_Y0, ROD_DY = 3.4, 1.7                                      # the lowest rod, the distance between rods
BEAD = (0.85, 1.5, 1.5)                                        # a bead: along the rod, tall, deep
BEAD_TICKS, BEAD_STAGGER, BEAD_ROW_GAP = 7.0, 0.7, 5.0         # ticks a bead takes to slide; between one bead's start and the next's; between one row's and the next's
BEAD_REBOUND = 0.035                                           # how far a bead springs back off the stack it hits (a fraction of its slide)


def bead_y(row):
    return ROD_Y0 + ROD_DY * row


def bead_rest(i, counted):
    """The x bead [i] rests at: [counted] (against the west post, the viewer's right, bead 0 nearest it) or not (against the east post,
    bead 8 nearest it)."""
    w = BEAD[0]
    return FRAME_INNER[0] + (i + 0.5) * w if counted else FRAME_INNER[1] - (COLS - i - 0.5) * w


def counting_frame_model():
    c = W.cube
    x0, x1 = FRAME_X
    inner0, inner1 = FRAME_INNER
    z0, z1 = 6.5, 9.5
    cap = {'*': 'w_oak_h', 'up': 'w_oak_end'}
    els = []
    for fx0, fx1 in ((x0, x0 + 2.4), (x1 - 2.4, x1)):                                                # the feet, crossways
        els.append(c((fx0, 0.0, 4.9), (fx1, 0.8, 11.1), {'*': 'w_oak_h', 'up': 'w_oak_end', 'down': 'w_oak_end'}))
    for px0, px1 in ((x0, x0 + POST_W), (x1 - POST_W, x1)):                                          # the posts
        els.append(c((px0, 0.8, z0), (px1, 14.6, z1), {'*': 'w_oak', 'up': 'w_oak_end'}))
    els.append(c((inner0, 0.8, z0 + 0.4), (inner1, 2.6, z1 - 0.4), cap))                              # the bottom beam
    els.append(c((inner0, 12.8, z0 + 0.4), (inner1, 13.9, z1 - 0.4), cap))                            # the top beam
    els.append(c((x0 - 0.3, 13.9, z0 - 0.3), (x1 + 0.3, 14.6, z1 + 0.3), cap))                        # its cap rail
    for kx in (x0 + 0.2, x1 - 1.4):                                                                  # a brass knob on each post
        els.append(c((kx + 0.2, 14.6, z0 + 0.9), (kx + 1.0, 15.2, z1 - 0.9), 'fit_brass'))
    for r in range(ROWS):                                                                            # the rods, a brass nut at each end
        y = bead_y(r)
        els.append(c((inner0, y - 0.22, ROD_Z - 0.22), (inner1, y + 0.22, ROD_Z + 0.22), 'fit_brass'))
        els.append(c((x0 - 0.25, y - 0.4, ROD_Z - 0.4), (x0, y + 0.4, ROD_Z + 0.4), 'fit_brass'))
        els.append(c((x1, y - 0.4, ROD_Z - 0.4), (x1 + 0.25, y + 0.4, ROD_Z + 0.4), 'fit_brass'))
    els.append(c((6.6, 12.9, z0 + 0.3), (9.4, 13.8, z0 + 0.4), 'fit_brass', ('north',)))               # the maker's plate on the top beam
    els.append(c((3.0, 1.0, z0 + 0.3), (6.6, 2.4, z0 + 0.4), 'fit_parchment', ('north',)))            # a tally slip pinned on the bottom beam
    return K.model(els, 'w_oak_h')


def bead_painter(key, ramp_):
    """A bead: glossy, lit from above left, its ends (against its neighbours) in shade."""
    def fn(face, w, h, rng):
        a = np.zeros((h, w, 4))
        ys, xs = np.mgrid[0:h, 0:w]
        v = ys / max(1.0, h - 1.0)
        u = xs / max(1.0, w - 1.0)
        if face in ('front', 'back'):
            t = 0.82 - 0.52 * v - 0.08 * u + 0.04 * (rng.random((h, w)) - 0.5)
            t = np.where((v < 0.34) & (u < 0.55), t + 0.12, t)
        elif face == 'top':
            t = np.full((h, w), 0.8) + 0.04 * (rng.random((h, w)) - 0.5)
        elif face == 'bottom':
            t = np.full((h, w), 0.18)
        else:
            t = np.full((h, w), 0.34) + 0.04 * (rng.random((h, w)) - 0.5)
        a[..., :3] = bands(ramp_, t, 0.0)
        a[..., 3] = 255
        return a
    return K.tt.painter(key, fn, light=False)


def beads_parts():
    out = []
    bw, bh, bd = BEAD
    for r in range(ROWS):
        ramp_ = K.COLOURS[r][2]
        for i in range(COLS):
            n = f'bead_{r}_{i}'
            out.append(mpart(n, (0.0, 0.0, 0.0), (0.0, 0.0, 0.0), [mbox(n, -bw / 2, bw / 2, -bh / 2, bh / 2, -bd / 2, bd / 2, bead_painter(n, ramp_))]))
    return out


def beads_mesh():
    return K.Mesh('beadsMesh', 'store_beads', beads_parts(), 64, 40, 4,
                  'The counting frame\'s %d beads, each its own part "bead_<row>_<i>" (row 0 the lowest rod, painted red, blue, green, amber, violet '
                  'and teal going up; i from 0 to %d, bead 0 the one that rests nearest the west post when counted), standing at its own middle: '
                  'draw it where [StoreArt.beadY] and [StoreArt.beadX] put it.' % (ROWS * COLS, COLS - 1))


def models():
    """Every block model: its name under models/block/ and its JSON."""
    return {'store_scale': scale_model(), 'store_name_board': name_board_model(), 'store_door_board': door_board_model(),
            'strongbox': strongbox_model(), 'strongbox_shut': strongbox_model(shut=True), 'counting_frame': counting_frame_model()}


def blockstates():
    return {bid: K.facing_blockstate(bid) for bid in models()}


def block_textures():
    """Every picture the block models use that the works do not have: name -> image, under textures/block/trade/."""
    out = {name: K.tile_image(tile) for name, tile in (('fit_brass', 'brass'), ('fit_leather', 'leather'), ('fit_parchment', 'parch'),
                                                       ('fit_dark_h', 'dark_h'), ('fit_dark_v', 'dark_v'), ('fit_dark_end', 'dark_end'),
                                                       ('fit_coins', 'coins'))}
    out['store_scale_dial'] = dial_texture()
    return out


def meshes():
    return [scale_mesh(), boards_mesh(), strongbox_mesh(), beads_mesh()]


# ================================================================== the boards' motion
BOARD_SWAY = ((5.0, 0.07), (2.5, 0.031))         # the name board's two sways, as the "To let" board's: degrees, radians a tick
DOOR_TICKS, DOOR_TAU, DOOR_PERIOD, DOOR_FADE = 16.0, 3.4, 8.0, 4.0
NAME_LINES = ((2.0, 2.2, 18.4), (4.9, 1.3, 16.0))   # the lettering: top under the board's top, the capitals' height, the most across (px)
NAME_TEXT_Z = 0.62                                # the lettering stands this far out from the middle of the board, on both faces
NAME_INK, NAME_EDGE = 0xF2E2AE, 0x241810          # the lettering's colour and the dark behind it


def board_swing(t, phase, wet=False):
    """Degrees the name board swings out on its rings at tick [t] (its foot toward the street, -z, when positive); [phase]: from its
    place, so no two swing together; twice as far in rain."""
    (a1, w1), (a2, w2) = BOARD_SWAY
    return (2.0 if wet else 1.0) * (a1 * math.sin(w1 * t + phase) + a2 * math.sin(w2 * t + 2.0 * phase))


def door_turn(age, frm, to):
    """The door board's turn about up, in degrees, [age] ticks after it was told to turn from [frm] to [to] (0 shows OPEN, 180 SHUT):
    it swings round, overshoots a little, rocks back and hangs still by DOOR_TICKS."""
    return damped(age, frm, to, DOOR_TICKS, DOOR_TAU, DOOR_PERIOD, DOOR_FADE)


# ================================================================== the strongbox's and the frame's motion
def lid_angle(age, frm, to):
    """Degrees the lid is turned about its pin (positive lifts its front), [age] ticks after it was told to go from [frm] to [to]. Opening
    (LID_OPEN_TICKS): quick off the mark, a little past the stop and back. Shutting (LID_SHUT_TICKS): it falls, quickening, strikes the
    rim at LID_FALL and springs up a few degrees and down again."""
    if age <= 0:
        return frm
    if to > frm:
        if age >= LID_OPEN_TICKS:
            return to
        w = age / LID_OPEN_TICKS - 1.0
        return frm + (to - frm) * (1.0 + (LID_OVER + 1.0) * w ** 3 + LID_OVER * w ** 2)
    if age >= LID_SHUT_TICKS:
        return to
    d = frm - to
    if age < LID_FALL:
        u = age / LID_FALL
        return to + d * (1.0 - u * u)
    return to + d * LID_BOUNCE * math.sin(math.pi * (age - LID_FALL) / (LID_SHUT_TICKS - LID_FALL))


def hasp_angle(age, frm, to):
    """Degrees the hasp is turned about x relative to the lid (positive lifts its tip toward -z): it hangs plumb, so against the lid it
    turns the opposite way to the lid's own turn, and as the lid starts and stops it swings a little, rattling on for LID_SETTLE_TICKS."""
    swing = 0.0
    if 0.0 < age < LID_SETTLE_TICKS:
        swing = (HASP_SWING * abs(to - frm) / LID_OPEN * math.exp(-age / HASP_TAU) * math.sin(2.0 * math.pi * age / HASP_PERIOD)
                 * (1.0 - smooth((age - 22.0) / 8.0)))
    return -lid_angle(age, frm, to) + swing


def bead_progress(age):
    """How far (0 to 1) a bead has slid [age] ticks after it was pushed: away fast, slowing, and as it meets the stack it springs back a hair."""
    if age <= 0:
        return 0.0
    if age >= BEAD_TICKS:
        return 1.0
    u = age / BEAD_TICKS
    s = 1.0 - (1.0 - u) ** 3
    if u > 0.8:
        s -= BEAD_REBOUND * math.sin(math.pi * (u - 0.8) / 0.2)
    return s


def bead_x(i, to_counted, age, order):
    """The x of bead [i] sliding to the counted side (or back off it) [age] ticks after its row was told, [order] beads ahead of it in the
    push (0 for the first away): each starts BEAD_STAGGER after the one before, so a push runs along the row."""
    frm, to = bead_rest(i, not to_counted), bead_rest(i, to_counted)
    return frm + (to - frm) * bead_progress(age - order * BEAD_STAGGER)



# ================================================================== StoreArt.kt
KT_HEADER = '// GENERATED by art/scripts/store_art.py: edit the script, not this file.\n'


def kotlin_store(meshes_):
    kf = K.kf
    funs = '\n\n'.join(K.mesh_fun(m) for m in meshes_)
    px, py, pz = SCALE_PIVOT
    lines = ',\n'.join(f'        floatArrayOf({kf(a)}, {kf(b)}, {kf(c)})' for a, b, c in NAME_LINES)
    return KT_HEADER + f'''package io.github.vaspyyy.guildmark.client.trade

{K.KT_MESH_IMPORTS}import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Carriers' Store's fittings as their renderers draw them: the clerk's scale (its beam and two pans), the name board over the
 * door and the open / shut board on it, the strongbox's lid and hasp and the counting frame's beads, with the numbers each is moved
 * by. Sizes are pixels (a sixteenth of a block) in the block's
 * own model space (x east, y up, z south, the block facing north, its front toward -z), which a renderer turns by its facing as
 * the blockstate turns the model; times are ticks; angles degrees. Each mesh part's origin is its pivot: the renderer translates to
 * where the table below puts it, rotates about it, then scale(-1f, -1f, 1f) and submitModelPart, as WorksHoistRenderer does.
 */
internal object StoreArt {{
    const val SCALE_TEXTURE = "textures/block/trade/store_scale.png"
    const val BOARDS_TEXTURE = "textures/block/trade/store_boards.png"
    const val STRONGBOX_TEXTURE = "textures/block/trade/store_strongbox.png"
    const val BEADS_TEXTURE = "textures/block/trade/store_beads.png"

    // ---------------------------------------------------------------- the scale

    /** Where the beam turns, in the scale block's model space; the beam is drawn there, turned about z by the tilt. */
    const val SCALE_PIVOT_X = {kf(px)}
    const val SCALE_PIVOT_Y = {kf(py)}
    const val SCALE_PIVOT_Z = {kf(pz)}

    /** The two pans' sides: the goods go on the west pan (the clerk's left), the weights on the east. */
    const val GOODS = {GOODS}
    const val WEIGHTS = {WEIGHTS}

    /**
     * A pan's hook from the pivot in the beam's own frame (across, down). The tilt is the beam's turn about z in degrees, positive
     * when the west, goods end goes down (rotateDegrees(Axis.ZP, tilt) in model space does that); [BEAM_MAX] is as far as it goes.
     */
    const val BEAM_HOOK_X = {kf(BEAM_HOOK[0])}
    const val BEAM_HOOK_Y = {kf(BEAM_HOOK[1])}
    const val BEAM_MAX = {kf(BEAM_MAX)}

    /** The block's model x and y of the hook of the pan on [side] ([GOODS] or [WEIGHTS]) with the beam at [tilt]: where "pan_goods" or "pan_weights" is drawn (z {SCALE_PIVOT[2]:g}), the pan hanging from it. */
    fun hookX(side: Int, tilt: Float): Float {{
        val a = Math.toRadians(tilt.toDouble()).toFloat()
        return SCALE_PIVOT_X + side * BEAM_HOOK_X * cos(a) - BEAM_HOOK_Y * sin(a)
    }}

    fun hookY(side: Int, tilt: Float): Float {{
        val a = Math.toRadians(tilt.toDouble()).toFloat()
        return SCALE_PIVOT_Y + side * BEAM_HOOK_X * sin(a) + BEAM_HOOK_Y * cos(a)
    }}

    /** A pan's floor, under its hook (the sack and the weights stand on it). */
    const val PAN_FLOOR = {kf(-(CHAIN + 0.7))}

    /** Ticks a weighing's swing takes to die away. */
    const val SETTLE_TICKS = {kf(SETTLE_TICKS)}
    private const val SETTLE_TAU = {kf(SETTLE_TAU)}
    private const val SETTLE_PERIOD = {kf(SETTLE_PERIOD)}

    /**
     * The beam's damped swing from [from] to [to] degrees, [age] ticks after the load changed (the sack set down, a weight added): it
     * goes past [to] by about a third of the way it came, swings back and is exactly [to] by [SETTLE_TICKS].
     */
    fun settle(age: Float, from: Float, to: Float): Float = damped(age, from, to, SETTLE_TICKS, SETTLE_TAU, SETTLE_PERIOD, 10f)

    private const val SWAY_DEG = {kf(SWAY_DEG)}
    private const val SWAY_TAU = {kf(SWAY_TAU)}
    private const val SWAY_PERIOD = {kf(SWAY_PERIOD)}

    /**
     * Degrees a pan swings about its hook [age] ticks after the beam moved: [phase] 0 for the swing from side to side (about z), about
     * 1.6 for the one to and fro (about x). Gone by [SETTLE_TICKS].
     */
    fun panSway(age: Float, phase: Float): Float {{
        if (age <= 0f || age >= SETTLE_TICKS) return 0f
        return SWAY_DEG * exp(-age / SWAY_TAU) * sin(2f * PI.toFloat() * age / SWAY_PERIOD + phase) * (1f - smooth((age - 30f) / 10f))
    }}

    /** The beam's rest, never quite still: degrees at tick [t]. */
    fun idleTilt(t: Float): Float = {kf(IDLE_DEG)} * sin({kf(IDLE_W)} * t) + {kf(0.6 * IDLE_DEG)} * sin({kf(2.3 * IDLE_W)} * t + 1f)

    // ---------------------------------------------------------------- the boards

    /** The name board's rings' line: the board is drawn there, swinging about it (x), and it hangs [BOARD_DROP] under it, {BOARD[0]:g} across, {BOARD[1]:g} high. */
    const val BOARD_X = {kf(BOARD_PIVOT[0])}
    const val BOARD_Y = {kf(BOARD_PIVOT[1])}
    const val BOARD_Z = {kf(BOARD_PIVOT[2])}
    const val BOARD_W = {kf(BOARD[0])}
    const val BOARD_H = {kf(BOARD[1])}
    const val BOARD_DROP = {kf(BOARD_DROP)}

    /**
     * The lettering on both faces of the name board: each line's top under the board's top, its capitals' height and the most it may be
     * across (px), centred across the board; written [NAME_TEXT_Z] out from the board's middle on either face (the back's turned about up).
     */
    val NAME_LINES = arrayOf(
{lines},
    )
    const val NAME_TEXT_Z = {kf(NAME_TEXT_Z)}
    const val NAME_INK = {K.kint(NAME_INK)}
    const val NAME_EDGE = {K.kint(NAME_EDGE)}

    /** Degrees the name board swings out on its rings at tick [t] (its foot toward the street, -z, when positive; [phase] from its place; twice as far in rain). */
    fun boardSwing(t: Float, phase: Float, wet: Boolean): Float =
        (if (wet) 2f else 1f) * ({kf(BOARD_SWAY[0][0])} * sin({kf(BOARD_SWAY[0][1])} * t + phase) + {kf(BOARD_SWAY[1][0])} * sin({kf(BOARD_SWAY[1][1])} * t + 2f * phase))

    /** Where the door board's hook's tip is: "door_board" is drawn there and turned about up (OPEN shows toward -z at 0 degrees, SHUT at 180). */
    const val DOOR_X = {kf(DOOR_PIVOT[0])}
    const val DOOR_Y = {kf(DOOR_PIVOT[1])}
    const val DOOR_Z = {kf(DOOR_PIVOT[2])}
    const val DOOR_TICKS = {kf(DOOR_TICKS)}

    /** The door board's turn in degrees, [age] ticks after it was told to turn from [from] to [to] (0: OPEN, 180: SHUT): round, a little past, back, still by [DOOR_TICKS]. */
    fun doorTurn(age: Float, from: Float, to: Float): Float = damped(age, from, to, DOOR_TICKS, {kf(DOOR_TAU)}, {kf(DOOR_PERIOD)}, {kf(DOOR_FADE)})

    // ---------------------------------------------------------------- the strongbox

    /**
     * The strongbox's hinge pin in its block's model space: "lid" is drawn there, turned about x by [lidAngle] (rotateDegrees(Axis.XP, angle)
     * in model space: positive lifts the lid's front, toward -z); it opens as far as [LID_OPEN] and then leans back a little. The two
     * slopes of its arch are its children and go with it.
     */
    const val LID_X = {kf(LID_PIVOT[0])}
    const val LID_Y = {kf(LID_PIVOT[1])}
    const val LID_Z = {kf(LID_PIVOT[2])}
    const val LID_OPEN = {kf(LID_OPEN)}

    /** Where "hasp" is drawn, in the lid's own frame (its origin is the pin; x across, y up, z south): after the lid's turn, translate here and turn about x by [haspAngle], then draw it. */
    const val HASP_X = {kf(HASP_AT[0])}
    const val HASP_Y = {kf(HASP_AT[1])}
    const val HASP_Z = {kf(HASP_AT[2])}

    /** Ticks the lid takes to open and to shut, and after which the lid and its hasp are quite still. */
    const val LID_OPEN_TICKS = {kf(LID_OPEN_TICKS)}
    const val LID_SHUT_TICKS = {kf(LID_SHUT_TICKS)}
    const val LID_SETTLE_TICKS = {kf(LID_SETTLE_TICKS)}
    private const val LID_OVER = {kf(LID_OVER)}
    private const val LID_FALL = {kf(LID_FALL)}
    private const val LID_BOUNCE = {kf(LID_BOUNCE)}
    private const val HASP_SWING = {kf(HASP_SWING)}
    private const val HASP_TAU = {kf(HASP_TAU)}
    private const val HASP_PERIOD = {kf(HASP_PERIOD)}

    /**
     * The lid's turn in degrees [age] ticks after it was told to go from [from] to [to] (0 shut, [LID_OPEN] open). Opening takes
     * [LID_OPEN_TICKS]: quick off the mark, a little past the stop and back. Shutting takes [LID_SHUT_TICKS]: it falls, quickening, strikes
     * the rim at {LID_FALL:g} ticks and springs up a few degrees and down again.
     */
    fun lidAngle(age: Float, from: Float, to: Float): Float {{
        if (age <= 0f) return from
        if (to > from) {{
            if (age >= LID_OPEN_TICKS) return to
            val w = age / LID_OPEN_TICKS - 1f
            return from + (to - from) * (1f + (LID_OVER + 1f) * w * w * w + LID_OVER * w * w)
        }}
        if (age >= LID_SHUT_TICKS) return to
        val d = from - to
        if (age < LID_FALL) {{
            val u = age / LID_FALL
            return to + d * (1f - u * u)
        }}
        return to + d * LID_BOUNCE * sin(PI.toFloat() * (age - LID_FALL) / (LID_SHUT_TICKS - LID_FALL))
    }}

    /**
     * The hasp's turn in degrees about x in model space, relative to the lid (positive lifts its tip toward -z), [age] ticks after the lid was
     * told to go from [from] to [to]: it hangs plumb, so against the lid it turns the other way to the lid's own turn, and as the lid starts
     * and stops it swings, rattling on until [LID_SETTLE_TICKS].
     */
    fun haspAngle(age: Float, from: Float, to: Float): Float {{
        var swing = 0f
        if (age > 0f && age < LID_SETTLE_TICKS) {{
            swing = HASP_SWING * abs(to - from) / LID_OPEN * exp(-age / HASP_TAU) * sin(2f * PI.toFloat() * age / HASP_PERIOD) *
                (1f - smooth((age - 22f) / 8f))
        }}
        return -lidAngle(age, from, to) + swing
    }}

    // ---------------------------------------------------------------- the counting frame

    /** Rods (rows, row 0 the lowest) and the beads on each of them: [beadsMesh] has "bead_<row>_<i>" for each. */
    const val BEAD_ROWS = {ROWS}
    const val BEAD_COLS = {COLS}

    /** Each rod's z and the first one's y in the block's model space, and the distance between rods: a bead is drawn at its x, [beadY] and [BEAD_Z]. */
    const val BEAD_Z = {kf(ROD_Z)}
    const val BEAD_Y0 = {kf(ROD_Y0)}
    const val BEAD_DY = {kf(ROD_DY)}
    private const val ROD_LO = {kf(FRAME_INNER[0])}
    private const val ROD_HI = {kf(FRAME_INNER[1])}
    private const val BEAD_W = {kf(BEAD[0])}

    fun beadY(row: Int): Float = BEAD_Y0 + BEAD_DY * row

    /**
     * The x bead [i] rests at: [counted], set against the west post (the viewer's right, bead 0 nearest it), or not, against the east post
     * (bead 8 nearest it). A row showing a count of n has beads 0 until n counted.
     */
    fun beadRestX(i: Int, counted: Boolean): Float =
        if (counted) ROD_LO + (i + 0.5f) * BEAD_W else ROD_HI - (BEAD_COLS - i - 0.5f) * BEAD_W

    /** Ticks a bead takes to slide, between one bead's start and the next's in a push, and between one row's start and the next's when several change. */
    const val BEAD_TICKS = {kf(BEAD_TICKS)}
    const val BEAD_STAGGER = {kf(BEAD_STAGGER)}
    const val BEAD_ROW_GAP = {kf(BEAD_ROW_GAP)}
    private const val BEAD_REBOUND = {kf(BEAD_REBOUND)}

    /** How far (0 to 1) a bead has slid [age] ticks after it was pushed: away fast, slowing, and as it meets the stack it springs back a hair. */
    fun beadProgress(age: Float): Float {{
        if (age <= 0f) return 0f
        if (age >= BEAD_TICKS) return 1f
        val u = age / BEAD_TICKS
        var s = 1f - (1f - u) * (1f - u) * (1f - u)
        if (u > 0.8f) s -= BEAD_REBOUND * sin(PI.toFloat() * (u - 0.8f) / 0.2f)
        return s
    }}

    /**
     * The x of bead [i] sliding to the counted side (or back off it) [age] ticks after its row was told, [order] beads ahead of it in the
     * push (0 for the first away: the lowest-numbered when counting up, the highest when counting down), each starting [BEAD_STAGGER] after
     * the one before. A bead not moving is at [beadRestX].
     */
    fun beadX(i: Int, toCounted: Boolean, age: Float, order: Int): Float {{
        val from = beadRestX(i, !toCounted)
        val to = beadRestX(i, toCounted)
        return from + (to - from) * beadProgress(age - order * BEAD_STAGGER)
    }}

    /** Ticks a row takes to settle when [moved] of its beads slide. */
    fun rowTicks(moved: Int): Float = BEAD_TICKS + BEAD_STAGGER * (moved - 1).coerceAtLeast(0)

    // ---------------------------------------------------------------- the common

    private fun smooth(u: Float): Float {{
        val x = u.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }}

    /** A damped swing from [from] to [to]: [tau] ticks for its size to fall by e, [period] ticks to swing once, the last [fade] ticks easing the remnant out. */
    private fun damped(age: Float, from: Float, to: Float, ticks: Float, tau: Float, period: Float, fade: Float): Float {{
        if (age <= 0f) return from
        if (age >= ticks) return to
        val w = 2f * PI.toFloat() / period
        val k = exp(-age / tau) * (cos(w * age) + sin(w * age) / (w * tau))
        return to + (from - to) * k * (1f - smooth((age - (ticks - fade)) / fade))
    }}

    // ---------------------------------------------------------------- the meshes

{funs}
}}
'''


# ================================================================== the check of what was written
def check(assets, kotlin_dir, models_dir, meshes_):
    """What this script wrote, looked at as the game would load it (trade_works_kit.Check): every block has its blockstate (the four
    facings), its models, their elements and pictures; every mesh has its picture at its size, its parts, its .bbmodel and its Kotlin."""
    c = K.Check(assets)
    for bid in models():
        c.blockstate(bid)
    for m in meshes_:
        c.mesh_texture(m.texture, m.model.tex_w, m.model.tex_h, m.scale)
        if not os.path.exists(os.path.join(models_dir, m.texture + '.bbmodel')):
            c.bad('art/models/%s.bbmodel is missing' % m.texture)
    byname = {m.fun: m for m in meshes_}
    c.mesh_parts(byname['scaleMesh'], ['beam', 'pan_goods', 'pan_weights'])
    c.mesh_parts(byname['boardsMesh'], ['board', 'door_board'])
    c.mesh_parts(byname['strongboxMesh'], ['lid', 'hasp'])
    c.mesh_parts(byname['beadsMesh'], [f'bead_{r}_{i}' for r in range(ROWS) for i in range(COLS)])
    have = [p.name for m in meshes_ for p in m.model.all_parts()]
    for want in ('beam', 'tongue', 'pan_goods', 'pan_weights', 'sack', 'weight_0', 'weight_1', 'weight_2', 'lid_slope_f', 'lid_slope_b'):
        if want not in have:
            c.bad('a mesh lacks the part ' + want)
    # the lid's tip, at its widest open, is short of the wall behind the box; every bead rests between the posts and clear of its neighbours
    if LID_PIVOT[2] + lid_reach(LID_OPEN * (1.0 + 4.0 * LID_OVER ** 3 / (27.0 * (LID_OVER + 1.0) ** 2))) > 16.0:
        c.bad('the strongbox lid, open, reaches the wall behind it')
    for counted in (False, True):
        xs = [bead_rest(i, counted) for i in range(COLS)]
        if min(np.diff(xs)) < BEAD[0] - 1e-6 or xs[0] - BEAD[0] / 2 < FRAME_INNER[0] - 1e-6 or xs[-1] + BEAD[0] / 2 > FRAME_INNER[1] + 1e-6:
            c.bad('the beads do not fit on the rod')
    for lo, hi in ((0.0, LID_OPEN), (LID_OPEN, 0.0)):
        top = max(lid_angle(a, lo, hi) for a in np.arange(0.0, 12.0, 0.25))
        if top > LID_OPEN * 1.05:
            c.bad('the lid goes past its stop by more than a twentieth')
    kt = os.path.join(kotlin_dir, 'StoreArt.kt')
    if not os.path.exists(kt):
        c.bad('StoreArt.kt is missing')
    return c.problems


def main(argv):
    if len(argv) < 4:
        raise SystemExit(__doc__)
    assets, kotlin_dir, models_dir = argv[1], argv[2], argv[3]
    prev = argv[4] if len(argv) > 4 else None
    tdir = os.path.join(assets, 'textures', 'block', 'trade')
    for name, img in block_textures().items():
        K.save_png(os.path.join(tdir, name + '.png'), img)
    for name, m in models().items():
        K.write(os.path.join(assets, 'models', 'block', name + '.json'), K.dump_json(m))
    for bid, bs in blockstates().items():
        K.write(os.path.join(assets, 'blockstates', bid + '.json'), K.dump_json(bs))
    ms = meshes()
    for m in ms:
        tex = m.paint()
        K.save_png(os.path.join(tdir, m.texture + '.png'), tex)
        K.write_bbmodel(models_dir, m.texture, m, tex)
    K.write(os.path.join(kotlin_dir, 'StoreArt.kt'), kotlin_store(ms))
    problems = check(assets, kotlin_dir, models_dir, ms)
    print('store_art: %d blocks, %d meshes (%d cubes)' % (len(models()), len(ms), sum(len(list(m.model.all_cubes())) for m in ms)))
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  ' + p)
        raise SystemExit(1)
    if prev:
        import trade_works_preview as TP
        for p in TP.store_previews(assets, prev):
            print('preview', p, os.path.getsize(p) // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv)
