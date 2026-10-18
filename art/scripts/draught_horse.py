"""
The caravans' draught horse (M4, design-road.md section 5), drawn by client/caravan: the heavy horse put to the wagon's
shafts. It is built on the Dusk Rider's steed rig (art/scripts/dusk_rider.py: a body carrying the neck and head, the
tail and four legs in two parts each, posed by the rig animator from clips keyed on the part names) grown into a cart
horse: a deep barrel, broad quarters, a short thick crested neck, a big Roman-nosed head, heavy legs with long white
feathering over broad shod hooves. It wears its harness: a padded collar with brass-tipped hames, the trace hooks and
a strap of bells across the hame tops; a bridle with blinkers, browband, noseband and bit; a cart saddle with the rein
rings and the tugs the shafts run through; the belly band, the breeching round its quarters and the crupper; and the
reins from the bit back to the rings, which the renderer lays from the bit to the ring each frame (the driver's hands
hold them on from there: living_props.py). Hidden until wanted: its nosebag, the reins tied up at the saddle (nobody
driving), and the cut ends of its traces and reins (cut loose, or lying dead in its cut harness).

Three coats share one layout: bay (black points, white socks, a blaze), dapple grey, and chestnut with a flaxen mane.
The coat is painted by where each texel lies on the horse rather than face by face, so its shading, dapples, socks and
markings run on across the cubes, and the bridle's cheekpieces are painted on along their true line.

    python3 -B art/scripts/draught_horse.py src/main/resources/assets/guildmark src/main/kotlin/io/github/vaspyyy/guildmark/client/caravan art/models [preview-dir]

Writes textures/entity/caravan/draught_horse_<coat>.png (SCALE times the declared box UV size), DraughtHorseMesh.kt (the
layer, and the numbers the renderer needs: the parts the clips move, the parts it hides and shows, where the reins,
traces and shafts meet the horse), draught_horse.bbmodel and, with a preview dir, each coat from four sides. Model space
as every mob's: pixels, y down, the horse faces -z, its right is -x, the ground at y 24, the origin under the middle of
its body. The harness's numbers come from wagon.py (the shafts' height and tips, the tugs, the hames, the terrets), so
it fits the wagon it pulls. Its clips are draught_horse_anims.py.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from modelkit import Cube, Part, Model
import townsfolk_tools as tt
import handcart as hc
import wagon as wg

TEX_W, TEX_H = 256, 128     # the declared (box UV) size
SCALE = 2                   # the PNG ships at SCALE times that
assert hc.SCALE == SCALE

GROUND = 24.0               # the ground's y in model space (as every mob's)
BODY_H = wg.SHAFT_UP        # the body part's pivot this high: the tugs' and the shafts' height


def Y(h):
    """The model-space y of a height over the ground."""
    return GROUND - h


def by(h):
    """The body part's local y of a height over the ground."""
    return Y(h) - Y(BODY_H)


# ================================================================== the rig's joints (px)
# The legs: each turns about x at its pivot (in the body's frame), its shin at the knee or hock (in the leg's frame),
# its hoof at the fetlock (in the shin's frame); the sole is the middle of the hoof's underside, in the hoof's frame.
# draught_horse_anims.py plants the hooves by these.
LEGS = {
    'fr': dict(pivot=(-3.7, by(21.0), -11.2), knee=(0.0, 11.5, 0.0), fetlock=(0.0, 6.5, 0.0), sole=(0.0, 3.0, -0.3), front=True),
    'fl': dict(pivot=(3.7, by(21.0), -11.2), knee=(0.0, 11.5, 0.0), fetlock=(0.0, 6.5, 0.0), sole=(0.0, 3.0, -0.3), front=True),
    'hr': dict(pivot=(-3.9, by(22.0), 11.0), knee=(0.0, 12.0, 1.2), fetlock=(0.0, 7.0, 0.0), sole=(0.0, 3.0, -0.3), front=False),
    'hl': dict(pivot=(3.9, by(22.0), 11.0), knee=(0.0, 12.0, 1.2), fetlock=(0.0, 7.0, 0.0), sole=(0.0, 3.0, -0.3), front=False),
}
HOOF_HALF = 2.7             # half a hoof's length: how far its toe and heel reach from the sole's middle

NECK_PIVOT = (0.0, by(26.5), -12.6)
NECK_ROT = 50.0             # the neck leans forward this far from upright
NECK_LEN = 18.0
HEAD_PIVOT = (0.0, -NECK_LEN, 0.8)     # the poll, in the neck's frame
HEAD_ROT = 8.0              # the head from the neck: its face 58 degrees under the level
TAIL_PIVOT = (0.0, by(28.4), 16.3)
COLLAR_PIVOT = (0.0, by(25.6), -15.0)
COLLAR_ROT = -30.0          # the collar's ring leans back at the top, against the shoulders
BARREL_BOT = 16.0           # the barrel's underside, and the belly's lowest
BELLY = 15.0
BIT = (3.05, 3.3, -12.1)    # the bit's rings at the corners of the mouth (x mirrored), in the head's frame

# ================================================================== coats

BRASS, BRASS_D, BRASS_L = tt.BRASS, tt.BRASS_D, tt.BRASS_L
STEEL, STEEL_D, STEEL_L = tt.STEEL, tt.STEEL_D, tt.STEEL_L
HARN, HARN_D, HARN_L = (64, 42, 28), (38, 25, 16), (104, 72, 48)       # the harness's leather, dark with use
TRACE, TRACE_D, TRACE_L = (78, 46, 26), (50, 30, 18), (116, 76, 46)    # the traces (wagon.py's)
REIN, REIN_D, REIN_L = wg.LEATHER, wg.LEATHER_D, wg.LEATHER_L          # the reins (wagon.py's)
TICKING = (202, 186, 150); TICKING_D = (150, 132, 100)                 # the collar's lining
BLUE, BLUE_D, BLUE_L = wg.BLUE, wg.BLUE_D, wg.BLUE_L                   # the city's blue
CANVAS, CANVAS_D, CANVAS_L = wg.CANVAS, wg.CANVAS_D, wg.CANVAS_L

COATS = {
    # base, dark, light: the coat; points: the legs below the knee; hair: mane and tail; sock: white legs and
    # feathers; skin: the muzzle; mark: the face's white ('blaze', 'stripe' or None); dapple: how strongly
    'bay': dict(base=(122, 66, 34), dark=(74, 38, 20), light=(170, 104, 58), points=(34, 27, 25), points_top=13.5,
                hair=(30, 24, 22), hair_d=(14, 11, 10), hair_l=(82, 64, 52), sock=(236, 232, 222), sock_d=(186, 180, 168),
                sock_top=(7.0, 8.0), skin=(72, 60, 58), pink=(200, 162, 152), mark='blaze', dapple=0.10,
                dapple_d=(98, 52, 26), dapple_l=(146, 84, 46), head=(0.94, 0.94, 0.96), horn=(182, 164, 132)),
    'grey': dict(base=(168, 168, 164), dark=(110, 110, 110), light=(214, 214, 208), points=(98, 98, 100), points_top=12.0,
                 hair=(222, 222, 216), hair_d=(150, 150, 148), hair_l=(246, 246, 240), sock=(236, 236, 232),
                 sock_d=(184, 184, 182), sock_top=(5.5, 6.5), skin=(58, 56, 58), pink=(170, 140, 140), mark=None,
                 dapple=0.55, dapple_d=(118, 118, 118), dapple_l=(218, 218, 212), head=(1.12, 1.12, 1.1),
                 horn=(84, 80, 78)),
    'chestnut': dict(base=(150, 78, 36), dark=(100, 48, 20), light=(196, 118, 64), points=(122, 60, 28), points_top=11.0,
                     hair=(226, 196, 142), hair_d=(170, 132, 84), hair_l=(246, 226, 182), sock=(238, 234, 224),
                     sock_d=(190, 184, 170), sock_top=(5.0, 6.5), skin=(96, 70, 60), pink=(206, 166, 150),
                     mark='stripe', dapple=0.08, dapple_d=(124, 62, 28), dapple_l=(178, 100, 52), head=(0.96, 0.96, 0.98),
                     horn=(176, 156, 124)),
}
COAT_ORDER = ('bay', 'grey', 'chestnut')

# ================================================================== noise


_NR = np.random.default_rng(20261018)
_PERM = np.concatenate([_NR.permutation(256)] * 2)
_VAL = _NR.random(256)
_OFF = _NR.random((256, 3))


def _h(ix, iy, iz):
    return _PERM[(_PERM[(_PERM[ix & 255] + iy) & 255] + iz) & 255]


def vnoise(P, scale, seed=0):
    """Smooth value noise in 0..1 over model space, features [scale] px across."""
    q = np.asarray(P, float) / scale + seed * 17.13
    i = np.floor(q).astype(np.int64)
    f = q - i
    u = f * f * (3 - 2 * f)
    out = np.zeros(q.shape[:-1])
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = ((u[..., 0] if dx else 1 - u[..., 0]) * (u[..., 1] if dy else 1 - u[..., 1]) *
                     (u[..., 2] if dz else 1 - u[..., 2]))
                out += w * _VAL[_h(i[..., 0] + dx, i[..., 1] + dy, i[..., 2] + dz)]
    return out


def fbm(P, scale, octaves=3, seed=0):
    tot, norm, s, a = 0.0, 0.0, scale, 1.0
    for k in range(octaves):
        tot = tot + a * vnoise(P, s, seed + 7 * k)
        norm += a
        s *= 0.5
        a *= 0.5
    return tot / norm


def worley(P, cell, seed=0):
    """Distance (in cells) to the nearest of a scatter of points one to a cell: the dapples' rings."""
    q = np.asarray(P, float) / cell + seed * 3.71
    i = np.floor(q).astype(np.int64)
    best = np.full(q.shape[:-1], 9.0)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                c = i + np.array([dx, dy, dz])
                fp = c + _OFF[_h(c[..., 0], c[..., 1], c[..., 2])]
                best = np.minimum(best, np.linalg.norm(q - fp, axis=-1))
    return best


def smooth(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def mixc(a, b, t):
    t = np.asarray(t, float)
    if t.ndim and t.shape[-1:] != (1,):
        t = t[..., None]
    return np.asarray(a, float) * (1 - t) + np.asarray(b, float) * t


# ================================================================== building

def C(origin, size, name, paint=None, region=None, faces=None, share=None):
    c = Cube(origin, size, paint, name=name, faces=faces)
    c.share = share
    c.region = region
    return c


def hb(name, x, top, bot, z, paint=None, region=None, faces=None):
    """A box in the body's frame by heights over the ground: [x] a half-width or (x0, x1), [top] and [bot] heights,
    [z] (z0, z1)."""
    x0, x1 = (-x, x) if isinstance(x, (int, float)) else x
    return C((x0, by(top), z[0]), (x1 - x0, top - bot, z[1] - z[0]), name, paint, region, faces)


def mirror_x(p):
    return (-p[0], p[1], p[2])


def plate(name, a, b, width, thick, paint, hint, extra=0.0, faces=None):
    return wg.plate(name, a, b, width, thick, paint, hint, extra=extra, faces=faces)


# ---------------------------------------------------------------- painters (the harness: face by face)

uv, mix, solid, metal, noise_fill, shade_edges = tt.uv, tt.mix, tt.solid, tt.metal, tt.noise_fill, tt.shade_edges


def leather_painter(key, along='x', base=HARN, dark=HARN_D, light=HARN_L, stitch=True, buckle=None):
    """Harness leather: a strap's darker edges along it, worn shiny down the middle, a row of stitches each side;
    [buckle] (0..1 along it) puts a brass buckle there."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.06)
        ax = tt.AXIS_ON_FACE[along][face]
        if ax is None:
            return shade_edges(a, 0.2)
        U, V = uv(w, h)
        f, g = (U, V) if ax == 'u' else (V, U)
        a[..., :3] = mix(a[..., :3], light, (0.32 * np.exp(-((g - 0.42) / 0.2) ** 2))[..., None])
        a[..., :3] = np.where(((g < 0.13) | (g > 0.87))[..., None], mix(a[..., :3], dark, 0.6), a[..., :3])
        n_along = w if ax == 'u' else h
        n_across = h if ax == 'u' else w
        if stitch and n_across >= 4:
            idx = np.arange(n_along)[None, :] if ax == 'u' else np.arange(n_along)[:, None]
            run = (idx % 3 == 0)
            for gg in (0.24, 0.76):
                row = np.abs(g - gg) * n_across < 0.55
                a[..., :3] = np.where((run & row)[..., None], mix(a[..., :3], (150, 120, 86), 0.55), a[..., :3])
        if buckle is not None:
            on = (np.abs(f - buckle) < max(0.7 / n_along, 0.04)) | (np.abs(f - buckle - 0.06) < max(0.5 / n_along, 0.025))
            a[..., :3] = np.where(on[..., None], mix(BRASS, BRASS_L, (0.3 * (g < 0.5))[..., None]), a[..., :3])
        return a
    return tt.painter(key, fn)


def brass_painter(key, wear=0.08):
    def fn(face, w, h, rng):
        a = metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=wear)
        if min(w, h) >= 3:
            U, V = uv(w, h)
            glint = np.exp(-((U - 0.32) ** 2 + (V - 0.3) ** 2) * 12)
            a[..., :3] = mix(a[..., :3], (255, 240, 196), (glint * 0.6)[..., None])
        return a
    return tt.painter(key, fn)


def steel_painter(key, wear=0.06):
    def fn(face, w, h, rng):
        return shade_edges(metal(w, h, STEEL, STEEL_D, STEEL_L, rng, wear=wear), 0.2)
    return tt.painter(key, fn)


def ring_painter(key, base=BRASS, dark=BRASS_D, light=BRASS_L, hole=(0.34, 0.3), on=('left', 'right')):
    """A ring seen face on (a terret, a bit ring): metal round a hole cut clean through its faces [on]; edge on,
    plain metal."""
    def fn(face, w, h, rng):
        a = metal(w, h, base, dark, light, rng, wear=0.1)
        if face in on and min(w, h) >= 2:
            U, V = uv(w, h)
            r = np.hypot((U - 0.5) / max(hole[0], 1e-3), (V - 0.5) / max(hole[1], 1e-3))
            a[..., 3] = np.where(r < 1.0, 0, 255)
            a[..., :3] = np.where(((r >= 1.0) & (r < 1.5))[..., None], mix(a[..., :3], dark, 0.35), a[..., :3])
        return a
    return tt.painter(key, fn)


def collar_painter(key):
    """The collar's roll: black-brown leather plumped with straw, a seam along its outer face; its inner face (against
    the shoulders and neck) the striped ticking of the lining."""
    def fn(face, w, h, rng):
        U, V = uv(w, h)
        if face == 'back':
            a = noise_fill(w, h, TICKING, rng, 0.05)
            stripe = (np.floor(V * h / 1.5) % 3 == 0)
            a[..., :3] = np.where(stripe[..., None], mix(a[..., :3], BLUE, 0.45), a[..., :3])
            return shade_edges(a, 0.25)
        a = noise_fill(w, h, (54, 40, 32), rng, 0.07)
        across = V if face in ('front', 'back', 'top', 'bottom') else U
        a[..., :3] *= (0.78 + 0.34 * np.sin(np.pi * across))[..., None]
        a[..., :3] = mix(a[..., :3], (120, 96, 76), (0.22 * np.exp(-((across - 0.36) / 0.16) ** 2))[..., None])
        if face == 'front' and h >= 3:
            seam = np.abs(V - 0.5) * h < 0.5
            a[..., :3] = np.where(seam[..., None], mix(a[..., :3], (24, 18, 14), 0.7), a[..., :3])
            dots = seam & (np.arange(w)[None, :] % 3 == 1)
            a[..., :3] = np.where(dots[..., None], mix(a[..., :3], (160, 132, 96), 0.6), a[..., :3])
        return a
    return tt.painter(key, fn)


def hame_painter(key):
    """A hame: a steel bar riding the collar's front, a brass cap down its face."""
    def fn(face, w, h, rng):
        a = metal(w, h, STEEL, STEEL_D, STEEL_L, rng, wear=0.08)
        if face in ('front', 'top', 'bottom') and min(w, h) >= 2:
            U, V = uv(w, h)
            across = V if face == 'front' else V
            cap = np.abs(across - 0.5) < 0.3
            a[..., :3] = np.where(cap[..., None], metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.12)[..., :3], a[..., :3])
        return shade_edges(a, 0.18)
    return tt.painter(key, fn)


def bell_painter(key):
    """A bell: polished brass, darker toward its mouth, the mouth itself dark."""
    def fn(face, w, h, rng):
        a = metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.05, polish=0.1)
        U, V = uv(w, h)
        if face == 'bottom':
            r = np.hypot(U - 0.5, V - 0.5)
            a[..., :3] = np.where((r < 0.36)[..., None], np.array((40, 30, 18), float), a[..., :3])
        elif face != 'top':
            a[..., :3] = mix(a[..., :3], BRASS_D, (0.45 * V)[..., None])
            a[..., :3] = mix(a[..., :3], (255, 236, 180), (0.5 * np.exp(-((U - 0.3) ** 2) * 30) * (1 - V))[..., None])
        return a
    return tt.painter(key, fn)


def frayed(key, along='y', base=TRACE, dark=TRACE_D, light=TRACE_L, end=1.0):
    """A cut strap: leather along [along], its cut end (at [end] of it, 0 or 1) frayed ragged."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.07)
        ax = tt.AXIS_ON_FACE[along][face]
        if ax is None:
            return a
        U, V = uv(w, h)
        f, g = (U, V) if ax == 'u' else (V, U)
        a[..., :3] = mix(a[..., :3], light, (0.25 * np.exp(-((g - 0.4) / 0.2) ** 2))[..., None])
        a[..., :3] = np.where(((g < 0.14) | (g > 0.86))[..., None], mix(a[..., :3], dark, 0.6), a[..., :3])
        n = w if ax == 'u' else h
        jag = rng.random(h if ax == 'u' else w)
        jag = (jag[:, None] if ax == 'u' else jag[None, :])
        dist = (f if end == 1.0 else 1 - f)
        cut = dist > 1 - (0.4 + 1.4 * jag) / max(n, 1)
        a[..., 3] = np.where(cut, 0, 255)
        lip = (dist > 1 - (1.4 + 1.4 * jag) / max(n, 1)) & ~cut
        a[..., :3] = np.where(lip[..., None], mix(a[..., :3], (150, 112, 76), 0.6), a[..., :3])
        return a
    return tt.painter(key, fn)


def nosebag_painter(key):
    """The nosebag: a bucket of heavy canvas, a leather bottom and rim, darkened where the horse's breath wets it."""
    def fn(face, w, h, rng):
        a = tt.weave(w, h, CANVAS, CANVAS_D, CANVAS_L, rng, cell=2)
        a[..., :3] *= 0.92
        U, V = uv(w, h)
        if face == 'front':      # the bottom, where the oats are
            a = noise_fill(w, h, HARN, rng, 0.06)
            return shade_edges(a, 0.25)
        if face == 'back':       # the mouth, gathered round the face
            r = np.maximum(np.abs(U - 0.5) * 2, np.abs(V - 0.5) * 2)
            a[..., :3] = np.where((r > 0.8)[..., None], mix(a[..., :3], HARN, 0.8), mix(a[..., :3], (60, 50, 36), 0.6))
            return a
        along = U if face in ('right', 'left') else V
        # u runs along z on the sides (0 at the back) and v along -z on the top and bottom (0 at the back)
        to_bottom = (1 - along) if face in ('right', 'left') else along
        a[..., :3] = np.where((to_bottom > 0.8)[..., None], noise_fill(w, h, HARN, rng, 0.06)[..., :3], a[..., :3])
        a[..., :3] = np.where((to_bottom < 0.12)[..., None], noise_fill(w, h, HARN_D, rng, 0.06)[..., :3], a[..., :3])
        damp = np.exp(-((to_bottom - 0.6) / 0.25) ** 2) * 0.18
        a[..., :3] = mix(a[..., :3], (110, 96, 70), damp[..., None])
        return a
    return tt.painter(key, fn)


def pad_painter(key):
    """The cart saddle's pad: thick black leather, quilted in rows, its edge welted."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, (46, 34, 28), rng, 0.06)
        U, V = uv(w, h)
        if face == 'top':
            q = (np.floor(U * w / 3) + np.floor(V * h / 3)) % 2
            a[..., :3] *= (0.94 + 0.08 * q)[..., None]
        a = shade_edges(a, 0.3)
        return a
    return tt.painter(key, fn)


# ================================================================== the horse


def harness_paints():
    P = {}
    P['leather_x'] = leather_painter('h_leather_x', 'x')
    P['leather_y'] = leather_painter('h_leather_y', 'y')
    P['leather_z'] = leather_painter('h_leather_z', 'z')
    P['leather_ys'] = leather_painter('h_leather_ys', 'y', stitch=False)
    P['buckle_y'] = leather_painter('h_buckle_y', 'y', buckle=0.35)
    P['buckle_z'] = leather_painter('h_buckle_z', 'z', buckle=0.5)
    P['brass'] = brass_painter('h_brass')
    P['steel'] = steel_painter('h_steel')
    P['collar'] = collar_painter('h_collar')
    P['hame'] = hame_painter('h_hame')
    P['bell'] = bell_painter('h_bell')
    P['pad'] = pad_painter('h_pad')
    P['terret'] = ring_painter('h_terret', on=('front', 'back'))
    P['bit'] = ring_painter('h_bit', STEEL, STEEL_D, STEEL_L, hole=(0.3, 0.3))
    return P


def leg(side, pre, P):
    """A leg in three parts, the steed's two and a third at the fetlock: the upper leg (the forearm before, the thigh
    and gaskin behind); the shin from the knee or hock down (the cannon, the feathering's upper fringe); and the hoof
    from the fetlock down (the fetlock's knob, the pastern's spreading feather, the shod hoof), so a hoof can stand
    flat while the leg swings over it."""
    L = LEGS[side]
    if L['front']:
        upper = [C((-2.4, -1.4, -2.6), (4.8, 12.2, 5.0), f'{pre}arm', region='coat')]
        lower = [
            C((-2.3, -1.2, -2.3), (4.6, 2.4, 4.4), f'{pre}knee', region='leg'),
            C((-1.9, 0.0, -1.8), (3.8, 6.8, 3.6), f'{pre}cannon', region='leg'),
            C((-2.8, 2.6, -2.6), (5.6, 4.4, 5.8), f'{pre}feather', region='feather'),
        ]
    else:
        upper = [C((-2.9, -4.0, -3.6), (5.8, 10.0, 7.0), f'{pre}thigh', region='coat'),
                 C((-2.4, 4.0, -2.6), (4.8, 8.2, 5.0), f'{pre}gaskin', region='coat')]
        lower = [
            C((-2.3, -1.4, -1.8), (4.6, 2.8, 4.8), f'{pre}hock', region='leg'),
            C((-1.9, 0.0, -1.9), (3.8, 7.3, 3.8), f'{pre}cannon', region='leg'),
            C((-2.8, 3.0, -2.6), (5.6, 4.4, 5.8), f'{pre}feather', region='feather'),
        ]
    foot = [
        C((-2.3, -1.4, -2.1), (4.6, 2.8, 4.5), f'{pre}fetlock', region='leg'),
        C((-3.1, -0.4, -2.9), (6.2, 2.5, 6.9), f'{pre}flare', region='feather'),
        C((-2.6, 1.5, -3.0), (5.2, 1.5, 5.4), f'{pre}hoof', region='hoof'),
    ]
    hoof = Part(f'{side}_hoof', L['fetlock'], cubes=foot)
    shin = Part(f'{side}_shin', L['knee'], cubes=lower, children=[hoof])
    return Part(f'{side}_leg', L['pivot'], cubes=upper, children=[shin])


def head_part(P):
    """The head, hung from the poll: skull, the round cheeks, the long face with its Roman nose, the big muzzle and
    the chin; ears, forelock and blinkers on parts of their own; the bridle (browband, noseband, the bit's rings);
    the nosebag and the reins' cut ends, hidden until wanted."""
    cubes = [
        C((-3.3, -2.2, -6.8), (6.6, 5.4, 7.8), 'skull', region='head'),
        C((-3.6, 0.8, -5.6), (7.2, 4.8, 6.0), 'jowl', region='head'),
        C((-2.6, -1.6, -11.6), (5.2, 5.0, 5.0), 'face', region='head'),
        C((-1.6, -2.1, -10.8), (3.2, 0.5, 5.6), 'nose', region='head'),
        C((-2.9, -1.0, -14.6), (5.8, 5.2, 3.4), 'muzzle', region='muzzle'),
        C((-2.4, 4.2, -14.2), (4.8, 1.1, 3.4), 'lip', region='muzzle'),
        # the bridle
        C((-3.55, -2.6, -1.6), (7.1, 1.6, 0.8), 'browband', P['leather_x'], faces=('top', 'front', 'back', 'right', 'left')),
        C((-3.85, -2.1, -1.75), (0.3, 1.1, 1.1), 'rosette_r', P['brass']),
        C((3.55, -2.1, -1.75), (0.3, 1.1, 1.1), 'rosette_l', P['brass']),
        C((-2.85, -2.4, -9.8), (5.7, 6.1, 0.8), 'noseband', P['leather_x'], faces=('top', 'bottom', 'right', 'left')),
        C((-3.25, 2.5, -12.9), (0.4, 1.6, 1.6), 'bit_r', P['bit']),
        C((2.85, 2.5, -12.9), (0.4, 1.6, 1.6), 'bit_l', P['bit']),
    ]
    ear = lambda s, n: Part(f'ear_{n}', (2.2 * s, -2.2, -0.4), (-36.0, 0.0, 14.0 * s), cubes=[
        C((-0.85, -2.6, -0.7), (1.7, 2.8, 1.4), f'ear_{n}_c', region='ear'),
        C((-0.5, -3.7, -0.45), (1.0, 1.2, 0.9), f'ear_{n}_tip', region='ear')])
    blinker = lambda s, n: Part(f'blinker_{n}', (3.75 * s, -1.2, -6.2), (0.0, 16.0 * s, 0.0), cubes=[
        C((-0.25, -0.2, 0.0), (0.5, 3.2, 3.0), f'blinker_{n}_c', leather_painter(f'h_blinker_{n}', 'z', stitch=False)),
        C((0.2 if s > 0 else -0.7, 0.7, 0.9), (0.5, 1.2, 1.2), f'blinker_{n}_stud', P['brass'])])
    forelock = Part('forelock', (0.0, -2.2, -1.0), cubes=[C((-1.4, -0.5, -5.0), (2.8, 0.8, 5.0), 'forelock_c', region='forelock')])
    bag = Part('nosebag', (0.0, 0.0, 0.0), cubes=[C((-3.3, -2.4, -15.3), (6.6, 8.4, 4.7), 'nosebag_c', nosebag_painter('h_nosebag')),
                                                  C((-3.45, -2.62, -0.2), (6.9, 0.45, 0.9), 'nosebag_strap_top', P['leather_x'])],
               children=[plate(f'nosebag_strap_{n}', (3.38 * s, 0.6, -10.9), (3.4 * s, -2.3, -0.6), 0.9, 0.3, P['leather_x'], (s, 0, 0), extra=0.2)
                         for s, n in ((-1, 'r'), (1, 'l'))])
    cut_reins = [Part(f'rein_{n}_cut', (BIT[0] * s, BIT[1], BIT[2]), (-(NECK_ROT + HEAD_ROT), 0.0, 0.0), cubes=[
        C((-0.35, 0.0, -0.18), (0.7, 9.0, 0.36), f'rein_{n}_cut_c', frayed(f'h_rein_{n}_cut', 'y', REIN, REIN_D, REIN_L))])
        for s, n in ((-1, 'r'), (1, 'l'))]
    return Part('head', HEAD_PIVOT, (HEAD_ROT, 0.0, 0.0), cubes=cubes,
                children=[ear(-1, 'r'), ear(1, 'l'), forelock, blinker(-1, 'r'), blinker(1, 'l'), bag] + cut_reins)


def neck_part(P):
    """The neck, short, deep and crested, leaning forward from the withers; the head at its top, the mane along its
    crest, falling to the right."""
    cubes = [
        C((-4.2, -7.0, -5.2), (8.4, 10.0, 10.6), 'neck_base', region='coat'),
        C((-3.6, -NECK_LEN - 1.5, -4.4), (7.2, NECK_LEN - 5.0, 9.2), 'neck_c', region='coat'),
        C((-2.6, -NECK_LEN - 0.5, 4.8), (5.2, NECK_LEN - 4.0, 1.6), 'crest', region='coat'),
    ]
    mane = Part('mane', (0.0, -NECK_LEN - 1.0, 5.6), cubes=[
        C((-1.4, -0.4, -0.6), (2.8, NECK_LEN - 0.5, 1.8), 'mane_top', region='mane'),
        C((-4.0, 0.6, -6.4), (0.5, NECK_LEN - 2.0, 7.4), 'mane_fall', region='mane'),
    ])
    return Part('neck', NECK_PIVOT, (NECK_ROT, 0.0, 0.0), cubes=cubes, children=[head_part(P), mane])


def tail_part():
    return Part('tail', TAIL_PIVOT, (28.0, 0.0, 0.0), cubes=[C((-1.5, -0.6, -1.4), (3.0, 6.4, 2.9), 'tail_dock', region='tail')],
                children=[Part('tail_end', (0.0, 5.6, 0.2), (-20.0, 0.0, 0.0), cubes=[
                    C((-2.2, -0.8, -1.7), (4.4, 12.8, 3.6), 'tail_hair', region='tail')])])


COLLAR_RING = [(0.0, 7.0), (3.7, 6.4), (5.3, 3.8), (5.5, 0.2), (4.8, -3.4), (3.2, -6.0), (0.0, -7.0)]
HAME_LINE = [(2.6, 7.7), (4.6, 6.6), (5.85, 3.8), (6.1, 0.2), (5.4, -3.6), (3.7, -6.5), (2.8, -9.0)]
HOOK_AT = 2      # the trace hooks ride the hames on their 3rd segment's middle (found by height below)


def collar_part(P):
    """The collar round the neck's base, leaning back against the shoulders: its roll, the two hames along its front
    meeting in brass tips over the top, the trace hooks on them, a strap of three bells across the hame tops; and the
    traces' cut ends, hidden until wanted."""
    kids = []
    ring = [np.array([x, y, 0.0]) for x, y in COLLAR_RING]
    ring = ring + [np.array([-x, y, 0.0]) for x, y in COLLAR_RING[-2:0:-1]]
    n = len(ring)
    for i in range(n):
        a, b = ring[i], ring[(i + 1) % n]
        mid = (a + b) / 2
        out = np.array([mid[0], mid[1], 0.0])
        kids.append(plate(f'collar_{i}', a, b, 2.8, 2.2, P['collar'], out, extra=0.55))
    hz = -1.75
    for s, side in ((1, 'l'), (-1, 'r')):
        pts = [np.array([s * x, y, hz]) for x, y in HAME_LINE]
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            out = np.array([s * 1.0, 0.0, -0.6])
            kids.append(plate(f'hame_{side}_{i}', a, b, 0.8, 0.7, P['hame'], out, extra=0.25))
        top = pts[-1]
        kids.append(Part(f'hame_tip_{side}', tuple(round(float(v), 4) for v in top), cubes=[
            C((-0.55, -1.3, -0.55), (1.1, 1.3, 1.1), f'hame_tip_{side}_c', P['brass'])]))
    kids.append(Part('bells', (0.0, -8.2, -1.9), cubes=[
        C((-2.7, -0.35, -0.3), (5.4, 0.7, 0.6), 'bell_strap', P['leather_x'])] + [
        C((x - 0.55, 0.35, -0.55), (1.1, 1.2, 1.1), f'bell_{i}', P['bell']) for i, x in enumerate((-1.7, 0.0, 1.7))]))
    return Part('collar', COLLAR_PIVOT, (COLLAR_ROT, 0.0, 0.0), children=kids)


def body_part(P):
    """The body: the barrel, its belly and back, the shoulders and withers before, the broad quarters and croup behind,
    the breast between the forelegs; with the cart saddle, the bands and the breeching on it, and carrying the neck,
    the tail, the collar and the legs."""
    tz = wg.TUG_Z
    cubes = [
        hb('barrel', 6.5, 28.6, BARREL_BOT, (-9, 8.5), region='coat'),
        hb('belly', 5.6, BARREL_BOT, BELLY, (-6.5, 5.5), region='coat'),
        hb('back', 5.0, 29.0, 28.6, (-6, 1), region='coat'),
        hb('loin', 5.6, 29.4, 28.6, (1, 6.5), region='coat'),
        hb('shoulders', 6.2, 29.2, BARREL_BOT + 0.4, (-15.5, -8.5), region='coat'),
        hb('withers', 3.0, 30.4, 29.2, (-13.5, -6), region='coat'),
        hb('breast', 5.0, 23.0, BARREL_BOT, (-17, -15.5), region='coat'),
        hb('quarters', 6.8, 29.6, BARREL_BOT + 0.6, (6.5, 14.5), region='coat'),
        hb('croup', 5.2, 30.2, 29.6, (7.5, 14), region='coat'),
        hb('buttocks', 6.0, 28.8, BARREL_BOT + 1.4, (14.5, 16.6), region='coat'),
        # the cart saddle: its pad, the seat with its brass ridge, the rein rings (terrets), the panels down the sides
        hb('saddle_pad', 6.9, 29.6, 28.6, (tz - 2.8, tz + 2.8), P['pad']),
        hb('saddle_seat', 4.2, 30.4, 29.6, (tz - 2.4, tz + 2.4), P['pad']),
        hb('saddle_ridge', 0.8, 30.8, 30.4, (tz - 2.0, tz + 2.0), P['brass']),
        hb('terret_r', (-wg.TERRET[0] - 0.7, -wg.TERRET[0] + 0.7), wg.TERRET[1] + 0.6, 30.4, (tz - 0.4, tz + 0.4), P['terret']),
        hb('terret_l', (wg.TERRET[0] - 0.7, wg.TERRET[0] + 0.7), wg.TERRET[1] + 0.6, 30.4, (tz - 0.4, tz + 0.4), P['terret']),
        hb('panel_r', (-6.9, -6.5), 28.6, 25.0, (tz - 2.6, tz + 2.6), P['pad']),
        hb('panel_l', (6.5, 6.9), 28.6, 25.0, (tz - 2.6, tz + 2.6), P['pad']),
        # the back band down to the tugs, the tugs round the shafts, the belly band under
        hb('backband_r', (-6.9, -6.5), 25.0, BODY_H + 1.4, (tz - 1.0, tz + 1.0), P['leather_y']),
        hb('backband_l', (6.5, 6.9), 25.0, BODY_H + 1.4, (tz - 1.0, tz + 1.0), P['leather_y']),
        hb('tug_r', (-wg.SHAFT_X - 1.2, -6.8), BODY_H + 1.4, BODY_H - 1.4, (tz - 1.4, tz + 1.4), None, 'tug'),
        hb('tug_l', (6.8, wg.SHAFT_X + 1.2), BODY_H + 1.4, BODY_H - 1.4, (tz - 1.4, tz + 1.4), None, 'tug'),
        hb('bellyband_r', (-6.9, -6.5), BODY_H - 1.4, BARREL_BOT, (tz - 0.8, tz + 0.8), P['buckle_y']),
        hb('bellyband_l', (6.5, 6.9), BODY_H - 1.4, BARREL_BOT, (tz - 0.8, tz + 0.8), P['buckle_y']),
        hb('bellyband_s', 6.9, BARREL_BOT, BELLY - 0.3, (tz - 0.8, tz + 0.8), P['leather_x']),
        # the breeching round the quarters, held up by the hip strap; the crupper along the spine to the tail
        hb('breech_r', (-7.2, -6.8), 21.6, 19.6, (7.6, 14.9), P['leather_z']),
        hb('breech_l', (6.8, 7.2), 21.6, 19.6, (7.6, 14.9), P['leather_z']),
        hb('breech_cr', (-7.0, -5.8), 21.6, 19.6, (14.5, 17.0), P['leather_x']),
        hb('breech_cl', (5.8, 7.0), 21.6, 19.6, (14.5, 17.0), P['leather_x']),
        hb('breech_back', 6.0, 21.6, 19.6, (16.6, 17.0), P['leather_x']),
        hb('hipstrap_r', (-7.1, -6.8), 29.6, 21.6, (9.2, 10.6), P['buckle_y']),
        hb('hipstrap_l', (6.8, 7.1), 29.6, 21.6, (9.2, 10.6), P['buckle_y']),
        hb('hipstrap_top', 6.9, 30.5, 29.6, (9.2, 10.6), P['leather_x']),
    ]
    kids = [neck_part(P), tail_part(), collar_part(P)]
    for side in ('fr', 'fl', 'hr', 'hl'):
        kids.append(leg(side, f'{side}_', P))
    # the breeching's straps forward to the shafts' hooks (wagon.py: 6 px behind the horse's middle), and the crupper
    hook_z = 6.0
    a_, b_ = wg.shaft_line(1)
    t = (wg.HORSE_Z + hook_z - a_[2]) / (b_[2] - a_[2])
    hook = a_ + (b_ - a_) * t
    hook_h = -hook[1]
    for s, n in ((-1, 'r'), (1, 'l')):
        kids.append(plate(f'breech_strap_{n}', (s * 7.0, by(20.6), 8.2), (s * (abs(hook[0]) - 0.6), by(hook_h + 0.6), hook_z),
                          1.0, 0.35, P['leather_x'], (s, 0, 0), extra=0.2))
    line = [(0.0, by(29.55), tz + 2.6), (0.0, by(29.75), 7.2), (0.0, by(30.45), 7.4), (0.0, by(30.45), 13.8), (0.0, by(28.9), 16.0)]
    for i in range(len(line) - 1):
        if abs(line[i + 1][2] - line[i][2]) < 0.5:
            continue
        kids.append(plate(f'crupper_{i}', line[i], line[i + 1], 1.2, 0.3, P['leather_x'], (0, -1, 0), extra=0.3))
    # the traces' cut ends, hanging from the hame hooks (placed once the collar's hooks are found: see place_hooks)
    for s, n in ((-1, 'r'), (1, 'l')):
        kids.append(Part(f'trace_{n}_cut', (0.0, 0.0, 0.0), (30.0, 0.0, 0.0), cubes=[
            C((-0.55, 0.0, -0.3), (1.1, 9.0, 0.6), f'trace_{n}_cut_c', frayed(f'h_trace_{n}_cut', 'y'))]))
    return Part('body', (0.0, Y(BODY_H), 0.0), cubes=cubes, children=kids)


# ---------------------------------------------------------------- rest frames and the hooks


def frames(model):
    """Every part's rest frame in model space: name -> (R, T, part)."""
    out = {}

    def go(p, R0, T0):
        R = R0 @ hc.rot_part(p.rot)
        T = T0 + R0 @ np.array(p.pivot, float)
        out[p.name] = (R, T, p)
        for ch in p.children:
            go(ch, R, T)
    for p in model.parts:
        go(p, np.eye(3), np.zeros(3))
    return out


def hame_hooks(model):
    """Where each hame crosses the traces' height (wagon.HAMES's), in the collar's frame and in model space."""
    F = frames(model)
    Rc, Tc, _ = F['collar']
    want = Y(wg.HAMES[1])
    out = {}
    for s, side in ((1, 'l'), (-1, 'r')):
        pts = [np.array([s * x, y, -1.75 - 0.6]) for x, y in HAME_LINE]
        W = [Tc + Rc @ p for p in pts]
        for a, b, pa, pb in zip(W, W[1:], pts, pts[1:]):
            if (a[1] - want) * (b[1] - want) <= 0:
                t = (want - a[1]) / (b[1] - a[1])
                out[side] = (pa + (pb - pa) * t, a + (b - a) * t)
                break
    return out


HOOK_X = 7.0       # the hooks stand out on short arms to here, so the traces clear the barrel and quarters


def place_hooks(model):
    """Put the trace hooks on the hames, standing out on short arms to HOOK_X; hang the traces' cut ends from them
    (as children of the body). Returns each side's hook: (in the collar's frame, in model space)."""
    F = frames(model)
    Rb, Tb, body = F['body']
    Rc, Tc, collar = F['collar']
    hooks = {}
    found = hame_hooks(model)
    if set(found) != {'l', 'r'}:
        raise SystemExit('draught_horse: the hames never reach the traces\' height')
    for side, (loc, world) in found.items():
        s = 1.0 if side == 'l' else -1.0
        tip_w = np.array([s * HOOK_X, world[1], world[2]])
        tip = Rc.T @ (tip_w - Tc)
        collar.children.append(plate(f'hook_arm_{side}', loc, tip, 0.6, 0.5, HARNESS['steel'], (0, -1, 0), extra=0.15))
        collar.children.append(Part(f'hook_{side}', tuple(round(float(v), 4) for v in tip), cubes=[
            C((-0.45, -0.6, -0.55), (0.9, 1.2, 1.1), f'hook_{side}_c', HARNESS['steel'])]))
        cut = next(p for p in body.children if p.name == f'trace_{side}_cut')
        cut.pivot = tuple(round(float(v), 4) for v in Rb.T @ (tip_w - Tb))
        hooks[side] = (tip, tip_w)
        want = np.array([s * wg.HAMES[0], Y(wg.HAMES[1]), wg.HAMES[2]])
        if np.linalg.norm(tip_w - want) > 0.1:
            raise SystemExit(f'draught_horse: the {side} hame hook is at {np.round(tip_w, 2)}, wagon.HAMES says {want}')
    return hooks


def place_reins(model):
    """The reins from each bit ring back to its rein ring on the saddle ("rein_r", "rein_l": the body's, modelled
    REIN_LENGTH along their own +y from the ring, laid at rest toward the bit; the renderer turns each to the bit as
    the head moves and stretches it to reach), and the reins tied up at the saddle when nobody drives ("reins_tied":
    across the saddle from ring to ring and a bight hung down its right side). Returns REIN_LENGTH."""
    F = frames(model)
    Rb, Tb, body = F['body']
    Rh, Th, _ = F['head']
    length = None
    for s_, n in ((-1, 'r'), (1, 'l')):
        ring = np.array([s_ * wg.TERRET[0], by(wg.TERRET[1]), wg.TUG_Z])
        bit = Rb.T @ (Th + Rh @ np.array(BIT if s_ > 0 else mirror_x(BIT), float) - Tb)
        d = bit - ring
        L = float(np.linalg.norm(d))
        length = round(L, 3) if length is None else length
        y = d / L
        x = np.cross(y, np.array([0.0, 1.0, 0.0]))
        x /= np.linalg.norm(x)
        body.children.append(Part(f'rein_{n}', tuple(round(float(v), 4) for v in ring), wg.euler_from_axes(x, y), cubes=[
            C((-0.45, 0.0, -0.2), (0.9, length, 0.4), f'rein_{n}_c', REIN_PAINT)]))
    # tied up: the reins' slack drawn across the saddle and looped down its right side
    tz = wg.TUG_Z
    top = by(wg.TERRET[1] - 0.3)
    pts = [(2.2, top, tz + 0.9), (-2.2, top, tz + 0.9), (-6.9, by(29.9), tz + 0.6), (-7.4, by(23.6), tz + 1.3),
           (-7.3, by(23.4), tz - 0.9), (-6.9, by(29.9), tz - 1.0), (-2.2, top, tz - 0.4)]
    kids = [plate(f'reins_tied_{i}', a, b, 0.9, 0.4, REIN_PAINT_X, (0, -1, 0) if i < 2 or i > 4 else (-1, 0, 0), extra=0.2)
            for i, (a, b) in enumerate(zip(pts, pts[1:]))]
    body.children.append(Part('reins_tied', (0.0, 0.0, 0.0), children=kids))
    return length


REIN_PAINT = leather_painter('h_rein', 'y', base=REIN, dark=REIN_D, light=REIN_L, stitch=False)
REIN_PAINT_X = leather_painter('h_rein_x', 'x', base=REIN, dark=REIN_D, light=REIN_L, stitch=False)
REIN_LENGTH = [None]

HARNESS = {}


def build():
    HARNESS.update(harness_paints())
    model = Model('draught_horse', TEX_W, TEX_H, [body_part(HARNESS)])
    hooks = place_hooks(model)
    REIN_LENGTH[0] = place_reins(model)
    cubes = list(model.all_cubes())
    names = [c.name for c in cubes]
    dup = sorted({n for n in names if names.count(n) > 1})
    if dup:
        raise SystemExit(f'draught_horse: cube names must be unique: {dup[:12]}')
    pnames = [p.name for p in model.all_parts()]
    pdup = sorted({n for n in pnames if pnames.count(n) > 1})
    if pdup:
        raise SystemExit(f'draught_horse: part names must be unique: {pdup[:12]}')
    used = wg.pack(cubes, TEX_W, TEX_H, 'draught_horse')
    return model, hooks, used


# ================================================================== painting the coat (by where each texel lies)

FACE_QUADS = {
    'front': (lambda x0, y0, z0, x1, y1, z1: ((x0, y0, z0), (x1, y0, z0), (x0, y1, z0)), (0, 0, -1)),
    'back': (lambda x0, y0, z0, x1, y1, z1: ((x1, y0, z1), (x0, y0, z1), (x1, y1, z1)), (0, 0, 1)),
    'right': (lambda x0, y0, z0, x1, y1, z1: ((x0, y0, z1), (x0, y0, z0), (x0, y1, z1)), (-1, 0, 0)),
    'left': (lambda x0, y0, z0, x1, y1, z1: ((x1, y0, z0), (x1, y0, z1), (x1, y1, z0)), (1, 0, 0)),
    'top': (lambda x0, y0, z0, x1, y1, z1: ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0)), (0, -1, 0)),
    'bottom': (lambda x0, y0, z0, x1, y1, z1: ((x0, y1, z1), (x1, y1, z1), (x0, y1, z0)), (0, 1, 0)),
}


def face_rect_f(c, face):
    """The face's rect on the declared texture, from the cube's true size (as rects2x places it)."""
    u, v = c.uv
    w, h, d = c.size
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h),
            'front': (u + d, v + d, w, h), 'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}[face]


class Ctx:
    """One face being painted: its texels' points in model space (P) and in its part's frame (L), the face's normal
    in model space (N) and in the part's frame (n), and the way to any other part's frame."""

    def __init__(self, F, part, c, face, X0, Y0, W, H):
        R, T, _ = F[part]
        fx0, fy0, fw, fh = face_rect_f(c, face)
        ii, jj = np.meshgrid(np.arange(W), np.arange(H))
        fu = np.clip(((X0 + ii + 0.5) / SCALE - fx0) / max(fw, 1e-6), 0, 1)
        fv = np.clip(((Y0 + jj + 0.5) / SCALE - fy0) / max(fh, 1e-6), 0, 1)
        (x0, y0, z0) = c.origin
        x1, y1, z1 = [o + s for o, s in zip(c.origin, c.size)]
        quad, n = FACE_QUADS[face]
        A, B, D = [np.array(p, float) for p in quad(x0, y0, z0, x1, y1, z1)]
        self.fu, self.fv = fu, fv
        self.L = A + fu[..., None] * (B - A) + fv[..., None] * (D - A)
        self.P = T + self.L @ R.T
        self.n = np.array(n, float)
        self.N = R @ self.n
        self.F, self.face, self.c, self.part = F, face, c, part
        self.h = GROUND - self.P[..., 1]
        self.W, self.H = W, H

    def frame(self, name):
        R, T, _ = self.F[name]
        return (self.P - T) @ R


def coat_base(look, x):
    """The coat's colour at the points of [x], before markings: shading toward the belly and under the neck, the big
    muscles catching the light, dapples, the fine run of the hair."""
    P, N, h = x.P, x.N, x.h
    col = np.zeros(P.shape[:-1] + (3,)) + np.array(look['base'], float)
    ax, z = np.abs(P[..., 0]), P[..., 2]
    # the underside darker, the topline a touch lighter
    under = smooth(18.5, 13.5, h)
    col = mixc(col, look['dark'], 0.38 * under)
    col = mixc(col, look['light'], 0.14 * smooth(27.0, 30.5, h))
    # muscles: shoulder, forearm, hip and stifle, the gaskin, the neck's crest
    sheen = (np.exp(-(((z + 13.0) / 3.6) ** 2 + ((h - 24.5) / 4.2) ** 2)) +
             0.8 * np.exp(-(((z - 11.5) / 4.0) ** 2 + ((h - 24.5) / 4.0) ** 2)) +
             0.6 * np.exp(-(((z - 7.5) / 2.6) ** 2 + ((h - 19.5) / 2.6) ** 2)))
    side = np.clip(np.abs(N[0]), 0, 1)
    col = mixc(col, look['light'], 0.30 * sheen * side * smooth(4.5, 6.5, ax))
    hollow = (np.exp(-(((z - 4.6) / 1.7) ** 2 + ((h - 22.5) / 3.4) ** 2)) +
              np.exp(-(((z + 8.6) / 1.5) ** 2 + ((h - 17.6) / 1.8) ** 2)))
    col = mixc(col, look['dark'], 0.24 * hollow * side)
    col = mixc(col, look['light'], 0.10 * np.exp(-((h - 22.4) / 1.6) ** 2) * side * smooth(-10.0, -8.0, z) * smooth(8.0, 6.0, z))
    # dapples (strong on the grey, a summer sheen on the others), fading down the legs and on the head
    if look['dapple'] > 0:
        d = worley(P, 2.5, seed=3)
        ring = smooth(0.28, 0.62, d)
        fade = smooth(9.0, 14.0, h) * look['dapple']
        col = mixc(col, look['dapple_l'], (1 - ring) * fade * 0.55)
        col = mixc(col, look['dapple_d'], ring * fade * 0.65)
    # the hair's run: fine streaks back and down
    q = P * np.array([1.0, 0.45, 0.22])
    g = vnoise(q, 0.42, seed=11)
    col *= (0.955 + 0.09 * g)[..., None]
    col *= (0.97 + 0.06 * vnoise(P, 3.0, seed=5))[..., None]
    return col


def socks(look, x, col, legname):
    """The legs' points below the knees and the white of the socks above the feathers, with a ragged edge."""
    h = x.h
    hind = legname.startswith('h')
    pts = smooth(look['points_top'] + 1.2, look['points_top'] - 2.0, h)
    col = mixc(col, look['points'], pts * 0.92)
    top = look['sock_top'][1 if hind else 0] + 1.3 * (vnoise(x.P, 0.9, seed=21) - 0.5)
    white = smooth(top + 0.4, top - 0.4, h)
    sock = mixc(np.zeros_like(col) + np.array(look['sock'], float), look['sock_d'],
                0.35 * vnoise(x.P * np.array([1.0, 0.3, 1.0]), 0.5, seed=4))
    return mixc(col, sock, white), white


def leg_of(x):
    for side in ('fr', 'fl', 'hr', 'hl'):
        if x.part.startswith(side):
            return side
    return ''


def strands(along, across, seed, width=0.42, run=7.0):
    """Hair: a tone in 0..1 for each texel, streaked along the hair."""
    q = np.stack([across / width, along / run, np.zeros_like(along) + seed * 1.37], axis=-1)
    return 0.62 * vnoise(q, 1.0, seed=seed) + 0.38 * vnoise(q * np.array([2.3, 1.7, 1.0]), 1.0, seed=seed + 3)


def hair_colour(look, tone, key='hair'):
    base, dark, light = np.array(look[key], float), np.array(look[key + '_d'], float), np.array(look[key + '_l'], float)
    return np.where(tone[..., None] < 0.5, mixc(dark, base, np.clip(tone * 2, 0, 1)), mixc(base, light, np.clip(tone * 2 - 1, 0, 1) * 0.8))


def paint_coat(look, x):
    col = coat_base(look, x)
    alpha = np.full(col.shape[:-1], 255.0)
    if leg_of(x):
        col, _ = socks(look, x, col, leg_of(x))
        # the inside of the legs, between them, darker
        inner = (np.sign(x.N[0]) == -np.sign(x.P[..., 0])) & (abs(x.N[0]) > 0.5)
        col = np.where(inner[..., None], col * 0.86, col)
    return col, alpha


def paint_leg(look, x):
    col = coat_base(look, x)
    col, white = socks(look, x, col, leg_of(x))
    # the knee's and hock's bony fronts lighter, the tendons down the back of the cannon darker
    if x.face == 'back':
        col *= 0.9
    col *= (0.97 + 0.06 * vnoise(x.P * np.array([2.0, 0.5, 2.0]), 0.6, seed=8))[..., None]
    return col, np.full(col.shape[:-1], 255.0)


def paint_feather(look, x):
    """The feathering: long silky hair from the back of the cannon down over the hoof, parted into locks, longest
    behind; the upper layer's fringe starts high at the back and low at the front, the flare's hem is ragged."""
    L = x.L
    side = leg_of(x)
    hind = side.startswith('h')
    along = L[..., 1]                  # down the leg
    across = L[..., 0] * (1 if x.face in ('front', 'back') else 0) + L[..., 2] * (1 if x.face in ('left', 'right') else 0)
    if x.face in ('top', 'bottom'):
        across = np.arctan2(L[..., 2], L[..., 0]) * 2.6
    tone = strands(along, across + (2.0 if hind else 0.0), seed=31 + len(side), width=0.55, run=3.5)
    tone = np.clip((tone - 0.3) / 0.45, 0, 1)
    col = mixc(np.array(look['sock_d'], float) * 0.78, look['sock'], tone ** 1.3)
    y_top, y_bot = x.c.origin[1], x.c.origin[1] + x.c.size[1]
    back = smooth(-2.6, 2.6, L[..., 2])
    a = np.full(along.shape, 255.0)
    if not x.c.name.endswith('flare'):
        start = y_top + 2.6 * (1 - back) + 0.9 * vnoise(np.stack([across * 2.0, along, along * 0], -1), 1.0, seed=41)
        a = np.where(along < start, 0.0, a)
        if x.face == 'top':
            a = np.where(back >= 0.5, 255.0, 0.0)
        col *= (0.84 + 0.16 * np.clip((along - start) / 1.5, 0, 1))[..., None]
    else:
        lock = vnoise(np.stack([across * 1.8, along * 0, along * 0 + 3.3], -1), 1.0, seed=43)
        hem = y_bot - 0.1 - 1.7 * lock ** 1.5 + 0.25 * back
        a = np.where(along > hem, 0.0, a)
        if x.face == 'top':
            a = np.zeros_like(a)
        col *= (1.0 - 0.12 * smooth(hem - 1.0, hem, along))[..., None]
    if x.face == 'bottom':
        a = np.zeros_like(a)
    return col, a


def paint_hoof(look, x):
    """Horn with growth rings, pale under white socks; the iron shoe round the bottom, its nails."""
    L = x.L
    horn = np.array(look['horn'], float)
    horn_d = horn * 0.72
    col = mixc(horn, horn_d, 0.5 * vnoise(np.stack([L[..., 0] * 3.0, L[..., 1] * 0.4, L[..., 2] * 3.0], -1), 1.0, seed=51))
    y_bot = x.c.origin[1] + x.c.size[1]
    shoe = (y_bot - L[..., 1]) < 0.42
    col = np.where(shoe[..., None], np.array((104, 106, 112), float) * (0.9 + 0.2 * vnoise(L * 3, 1.0, 9))[..., None], col)
    if x.face == 'bottom':
        r = np.maximum(np.abs(L[..., 0]) / 2.6, np.abs(L[..., 2] + 0.3) / 2.7)
        col = np.where((r < 0.72)[..., None], np.array((70, 62, 56), float), np.array((110, 112, 118), float))
    return col, np.full(col.shape[:-1], 255.0)


def mark_mask(look, x, H):
    """The face's white, in the head's frame: a blaze from the forehead to the muzzle, or a stripe down the nose."""
    if look['mark'] is None:
        return np.zeros(H.shape[:-1])
    z, xx, y = H[..., 2], H[..., 0], H[..., 1]
    if look['mark'] == 'blaze':
        w = np.interp(z, [-15.0, -12.5, -9.0, -6.0, -3.5, -1.8], [1.9, 1.7, 0.95, 0.85, 1.35, 0.0])
    else:
        w = np.interp(z, [-15.0, -12.8, -10.0, -6.5, -4.0, -3.0], [0.0, 0.55, 0.45, 0.42, 1.0, 0.0])
    jag = 0.25 * (vnoise(H * 2.0, 1.0, seed=61) - 0.5)
    on = (np.abs(xx) < w + jag) & (y < -0.9)
    return on.astype(float)


def paint_head(look, x):
    H = x.frame('head')
    col = coat_base(look, x) * np.array(look['head'], float)
    col = np.clip(col, 0, 255)
    # the face's bones: lighter over the nose and the brow, the hollows over the eyes and under the cheek darker
    col = mixc(col, look['light'], 0.18 * smooth(-1.0, -2.2, H[..., 1]))
    hollow = np.exp(-(((H[..., 2] + 3.0) / 1.3) ** 2 + ((H[..., 1] + 1.6) / 1.1) ** 2)) * (np.abs(x.N[0]) > 0.3)
    col = mixc(col, look['dark'], 0.3 * hollow)
    m = mark_mask(look, x, H)
    col = mixc(col, look['sock'], m)
    # the eye, under the blinker: dark, wet, a glint; its lid and lashes
    if x.c.name == 'skull' and x.face in ('left', 'right'):
        d = np.hypot((H[..., 1] - 0.1) / 0.85, (H[..., 2] + 4.6) / 1.05)
        col = np.where((d < 1.35)[..., None], mixc(col, look['dark'], 0.55), col)
        col = np.where((d < 1.0)[..., None], np.array((34, 22, 18), float), col)
        glint = np.hypot((H[..., 1] + 0.25) / 0.3, (H[..., 2] + 4.95) / 0.32) < 1.0
        col = np.where(glint[..., None], np.array((214, 214, 220), float), col)
        lid = (d >= 1.0) & (d < 1.3) & (H[..., 1] < 0.0)
        col = np.where(lid[..., None], np.array((26, 20, 18), float), col)
    # the bridle painted on: cheekpieces down the sides to the bit, the crownpiece over the poll, the throatlatch
    strap = np.zeros(H.shape[:-1], bool)
    buckle = np.zeros(H.shape[:-1], bool)
    if x.face in ('left', 'right'):
        a, b = np.array([-1.2, -0.9]), np.array([BIT[1], BIT[2] + 0.2])
        p = np.stack([H[..., 1], H[..., 2]], -1)
        t = np.clip(((p - a) @ (b - a)) / float((b - a) @ (b - a)), 0, 1)
        d = np.linalg.norm(p - (a + t[..., None] * (b - a)), axis=-1)
        strap |= d < 0.48
        buckle |= (d < 0.62) & (np.abs(t - 0.22) < 0.035)
        a2, b2 = np.array([-1.4, 0.0]), np.array([5.6, 0.25])
        t2 = np.clip(((p - a2) @ (b2 - a2)) / float((b2 - a2) @ (b2 - a2)), 0, 1)
        strap |= np.linalg.norm(p - (a2 + t2[..., None] * (b2 - a2)), axis=-1) < 0.38
    if x.face in ('top', 'left', 'right'):
        strap |= (np.abs(H[..., 2] + 0.15) < 0.42) & (H[..., 1] < -0.9)
    if x.face == 'bottom':
        strap |= np.abs(H[..., 2] - 0.2) < 0.38
    if x.face == 'back':
        strap |= (np.abs(H[..., 1] - 2.0) < 6.0) & (x.c.name == 'jowl')
        strap &= np.abs(H[..., 0]) > 2.6
    leather = mixc(np.zeros_like(col) + np.array(HARN, float), HARN_L, 0.25 * vnoise(H * 3, 1.0, 71))
    col = np.where(strap[..., None], leather, col)
    col = np.where(buckle[..., None], np.array(BRASS, float), col)
    return col, np.full(col.shape[:-1], 255.0)


def paint_muzzle(look, x):
    H = x.frame('head')
    col = coat_base(look, x) * np.array(look['head'], float)
    col = mixc(col, look['skin'], smooth(-11.6, -13.4, H[..., 2]) * 0.85)
    col = mixc(col, look['skin'], 0.8 * (x.c.name == 'lip'))
    m = mark_mask(look, x, H)
    col = mixc(col, look['pink'], m * smooth(-12.0, -13.5, H[..., 2]))
    col = mixc(col, look['sock'], m * smooth(-13.5, -12.0, H[..., 2]))
    if look['mark'] is None:   # the grey: pink mottling on dark skin
        spots = smooth(0.62, 0.7, vnoise(H * 1.5, 1.0, 81)) * smooth(-12.0, -13.5, H[..., 2])
        col = mixc(col, look['pink'], spots * 0.7)
    # fine whiskery hair on the skin
    col *= (0.93 + 0.12 * vnoise(H * 4.0, 1.0, 83))[..., None]
    if x.c.name == 'muzzle':
        if x.face == 'front':
            for s in (-1, 1):
                d = np.hypot((H[..., 0] - 1.55 * s) / 0.72, (H[..., 1] - 0.7) / 1.15)
                col = np.where((d < 1.25)[..., None], mixc(col, look['skin'], 0.5), col)
                slit = (d < 1.0) & ((H[..., 0] - 1.55 * s) * s > -0.35)
                col = np.where(slit[..., None], np.array((26, 18, 18), float), col)
            mouth = (H[..., 1] > 3.85)
            col = np.where(mouth[..., None], np.array((40, 30, 30), float), col)
        if x.face in ('left', 'right'):
            mouth = (H[..., 1] > 3.85) & (H[..., 2] < -12.2)
            col = np.where(mouth[..., None], np.array((40, 30, 30), float), col)
            nos = np.hypot((H[..., 2] + 14.0) / 0.6, (H[..., 1] - 0.6) / 0.9) < 1.0
            col = np.where(nos[..., None], mixc(col, (30, 22, 22), 0.6), col)
    return col, np.full(col.shape[:-1], 255.0)


def paint_ear(look, x):
    """An ear: the coat outside, darker toward the tip; inside (its front) dark, pale hair along its edges."""
    L = x.L
    col = coat_base(look, x) * np.array(look['head'], float)
    t = np.clip(-L[..., 1] / 3.7, 0, 1)                    # 0 at the ear's root, 1 at its tip
    if x.face == 'front':
        half = 0.85 if x.c.name.endswith('_c') else 0.5
        edge = np.abs(L[..., 0]) / half
        col = np.where((edge < 0.62)[..., None], mixc(col, (40, 30, 26), 0.75), mixc(col, look['light'], 0.3))
    col = mixc(col, look['dark'], 0.45 * smooth(0.55, 1.0, t))
    return col, np.full(col.shape[:-1], 255.0)


def paint_hair(look, x, kind):
    """The mane, the forelock and the tail: thick hair in strands, its free end ragged."""
    L = x.L
    c = x.c
    if kind == 'tail':
        along = L[..., 1] + (6.4 if c.name == 'tail_hair' else 0.0)
        across = L[..., 0] * (1 if x.face in ('front', 'back') else 0) + L[..., 2] * (1 if x.face in ('left', 'right') else 0)
        if x.face in ('top', 'bottom'):
            across = L[..., 0] + 0.5 * L[..., 2]
        tone = strands(along, across, seed=91, width=0.4, run=8.0)
        col = hair_colour(look, tone)
        if c.name == 'tail_dock':
            col = mixc(col, coat_base(look, x), 0.35 * smooth(2.0, 0.0, L[..., 1]))
            ribbon = np.abs(L[..., 1] - 1.2) < 0.4
            col = np.where(ribbon[..., None], mixc(np.array(BLUE, float), BLUE_L, 0.3 * (L[..., 1] < 1.2)), col)
        a = np.full(col.shape[:-1], 255.0)
        if c.name == 'tail_hair':
            end = c.origin[1] + c.size[1] - 0.3 - 3.2 * vnoise(np.stack([across * 1.6, across * 0, across * 0 + 5.0], -1), 1.0, 93)
            a = np.where(L[..., 1] > end, 0.0, a)
            if x.face == 'bottom':
                a = np.zeros_like(a)
        return col, a
    if kind == 'forelock':
        along, across = -L[..., 2], L[..., 0]
        tone = strands(along, across, seed=95, width=0.35, run=4.0)
        col = hair_colour(look, tone)
        end = 4.2 + 0.9 * vnoise(np.stack([across * 2.5, across * 0, across * 0 + 2.0], -1), 1.0, 97)
        a = np.where(along > end, 0.0, 255.0)
        a = np.where((np.abs(across) > 1.4 - 0.5 * smooth(2.5, 5.0, along)), 0.0, a)
        return col, a
    # the mane: on the crest it stands in a ridge (strands along it), down the side it falls (strands across the neck)
    if c.name == 'mane_top':
        along, across = L[..., 1], L[..., 0] + 0.6 * L[..., 2]
        tone = strands(along * 0.4, across, seed=99, width=0.38, run=2.5)
        col = hair_colour(look, tone)
        return col, np.full(col.shape[:-1], 255.0)
    along, across = -(L[..., 2] - 1.0), L[..., 1]
    tone = strands(along, across, seed=101, width=0.42, run=6.0)
    col = hair_colour(look, tone)
    col *= (1.0 - 0.18 * smooth(1.0, 6.0, along))[..., None]
    end = 6.6 - 2.2 * vnoise(np.stack([across * 1.2, across * 0, across * 0 + 7.0], -1), 1.0, 103)
    end -= 1.4 * smooth(9.0, 13.5, across)       # shorter where it meets the withers
    return col, np.where(along > end, 0.0, 255.0)


def paint_tug(look, x):
    """A tug: a leather loop the shaft runs through, the hole cut clean through its ends."""
    L = x.L
    col = np.zeros(L.shape[:-1] + (3,)) + np.array(HARN, float)
    col *= (0.92 + 0.12 * vnoise(L * 3, 1.0, 111))[..., None]
    a = np.full(col.shape[:-1], 255.0)
    if x.face in ('front', 'back'):
        hx = np.abs(np.abs(L[..., 0]) - wg.SHAFT_X)
        hy = np.abs(L[..., 1] - by(BODY_H))
        hole = (hx < wg.SHAFT_T / 2 + 0.05) & (hy < wg.SHAFT_T / 2 + 0.05)
        a = np.where(hole, 0.0, a)
        rim = (hx < wg.SHAFT_T / 2 + 0.45) & (hy < wg.SHAFT_T / 2 + 0.45) & ~hole
        col = np.where(rim[..., None], mixc(col, BRASS, 0.75), col)
    else:
        col = np.where((np.abs(x.fu - 0.5) < 0.08)[..., None], mixc(col, HARN_D, 0.6), col)
    return col, a


REGIONS = {
    'coat': paint_coat, 'leg': paint_leg, 'feather': paint_feather, 'hoof': paint_hoof, 'head': paint_head,
    'muzzle': paint_muzzle, 'ear': paint_ear, 'tug': paint_tug,
    'mane': lambda look, x: paint_hair(look, x, 'mane'), 'forelock': lambda look, x: paint_hair(look, x, 'forelock'),
    'tail': lambda look, x: paint_hair(look, x, 'tail'),
}


def part_of(model):
    out = {}
    for p in model.all_parts():
        for c in p.cubes:
            out[c.name] = p.name
    return out


def texture(model, coat):
    look = COATS[coat]
    F = frames(model)
    owner = part_of(model)
    img = Image.new('RGBA', (TEX_W * SCALE, TEX_H * SCALE), (0, 0, 0, 0))
    done = set()
    for c in model.all_cubes():
        if c.uv in done:
            continue
        done.add(c.uv)
        rects = hc.rects2x(c)
        for face, (x, y, w, h) in rects.items():
            if w <= 0 or h <= 0 or not c.shows(face):
                continue
            if getattr(c, 'region', None):
                ctx = Ctx(F, owner[c.name], c, face, x, y, w, h)
                col, a = REGIONS[c.region](look, ctx)
                col = col * tt.FACE_K.get(face, 1.0)
                arr = np.concatenate([np.clip(col, 0, 255), a[..., None]], -1)
                img.paste(Image.fromarray(np.round(arr).astype(np.uint8), 'RGBA'), (x, y))
            elif c.paint is not None:
                c.paint(img, face, x, y, w, h)
    return img


# ================================================================== posing and drawing


def posed_faces(model, pose=None, M=np.eye(3), T=np.zeros(3), hide=(), show=()):
    """Every drawn face (A, B, D, rect, face, normal) of the horse posed by [pose] ({part: (R, offset)}, rotations
    relative to the parent as the rig animator leaves them; parts left out at rest), placed by M and T. Parts in
    HIDDEN are left out unless named in [show]; parts in [hide] always."""
    pose = pose or {}
    out = []
    skip = (set(HIDDEN) - set(show)) | set(hide)

    def go(part, M0, T0):
        if part.name in skip:
            return
        R, off = pose.get(part.name, (hc.rot_part(part.rot), np.zeros(3)))
        Rm = M0 @ R
        P = T0 + M0 @ (np.array(part.pivot, float) + off)
        for c in part.cubes:
            x0, y0, z0 = c.origin
            x1, y1, z1 = [o + s for o, s in zip(c.origin, c.size)]
            rects = hc.rects2x(c)
            for face, (quad, n) in FACE_QUADS.items():
                if not c.shows(face):
                    continue
                A, B, D = [P + Rm @ np.array(p, float) for p in quad(x0, y0, z0, x1, y1, z1)]
                out.append((A, B, D, rects[face], face, Rm @ np.array(n, float)))
        for ch in part.children:
            go(ch, Rm, P)
    for p in model.parts:
        go(p, M, T)
    return out


HIDDEN = ('nosebag', 'reins_tied', 'trace_r_cut', 'trace_l_cut', 'rein_r_cut', 'rein_l_cut')
TO_GROUND = np.array([0.0, -GROUND, 0.0])     # model space to wagon.render's (the ground at y 0)


def view(model, tex, yaw, pitch=-14, scale=6.0, size=(360, 300), pose=None, center=(0.0, -16.0, -4.0), show=(), extra=(), bg=(58, 64, 80)):
    faces = posed_faces(model, pose, T=TO_GROUND, show=show) + list(extra)
    return wg.render(faces, tex, yaw, pitch, scale, size, center, bg=bg, extent=(-8, 8, -10, 8))


# ================================================================== output

KT_HEADER = '// generated by art/scripts/draught_horse.py, edit the script, not this file\n'
kf, kv = wg.kf, wg.kv

# The parts the clips move (draught_horse_anims.py keys them by these names)
RIG = ['body', 'neck', 'head', 'ear_r', 'ear_l', 'forelock', 'mane', 'tail', 'tail_end', 'collar', 'bells',
       'fr_leg', 'fr_shin', 'fr_hoof', 'fl_leg', 'fl_shin', 'fl_hoof', 'hr_leg', 'hr_shin', 'hr_hoof',
       'hl_leg', 'hl_shin', 'hl_hoof',
       'trace_r_cut', 'trace_l_cut', 'rein_r_cut', 'rein_l_cut', 'nosebag']


def kotlin_mesh(model, hooks, strides, moments):
    F = frames(model)
    Rb, Tb, _ = F['body']
    rig = ', '.join(f'"{n}"' for n in RIG)
    hidden = ', '.join(f'"{n}"' for n in HIDDEN)
    coats = ', '.join(f'"{c}"' for c in COAT_ORDER)

    def point(part, loc):
        R, T, _ = F[part]
        world = T + R @ np.array(loc, float)
        return f'Point("{part}", {kv(loc)})', world
    bit_l, bit_l_w = point('head', BIT)
    bit_r, bit_r_w = point('head', mirror_x(BIT))
    ter_l, ter_l_w = point('body', (wg.TERRET[0], by(wg.TERRET[1]), wg.TUG_Z))
    ter_r, ter_r_w = point('body', (-wg.TERRET[0], by(wg.TERRET[1]), wg.TUG_Z))
    tug_l, tug_l_w = point('body', (wg.SHAFT_X, by(wg.SHAFT_UP), wg.TUG_Z))
    tug_r, tug_r_w = point('body', (-wg.SHAFT_X, by(wg.SHAFT_UP), wg.TUG_Z))
    hame_l, hame_l_w = point('collar', hooks['l'][0])
    hame_r, hame_r_w = point('collar', hooks['r'][0])
    mom = []
    for clip, ms in moments.items():
        inner = ', '.join(f'Moment({kf(t)}, "{what}")' for t, what in ms)
        mom.append(f'        "{clip}" to listOf({inner}),')
    mom = '\n'.join(mom)
    legs = []
    for side in ('fr', 'fl', 'hr', 'hl'):
        L = LEGS[side]
        legs.append(f'        "{side}" to floatArrayOf({kv(L["pivot"])}, {kv(L["knee"])}, {kv(L["fetlock"])}, {kv(L["sole"])}),')
    legs = '\n'.join(legs)
    imports = ['import net.minecraft.client.model.geom.PartPose', 'import net.minecraft.client.model.geom.builders.CubeListBuilder',
               'import net.minecraft.client.model.geom.builders.LayerDefinition', 'import net.minecraft.client.model.geom.builders.MeshDefinition',
               'import net.minecraft.core.Direction', 'import java.util.EnumSet']
    body = model.kotlin().rstrip('\n')
    return KT_HEADER + 'package io.github.vaspyyy.guildmark.client.caravan\n\n' + '\n'.join(imports) + f'''

/**
 * The caravan's draught horse in its harness (art/scripts/draught_horse.py), box UV on one [TEX_WIDTH] x [TEX_HEIGHT]
 * layout shared by its coats (textures/entity/caravan/draught_horse_<coat>.png, shipped at {SCALE}x that). Model space as
 * every mob's: pixels, y down, the horse faces -z, its right is -x, the ground at y 24, its origin under the middle of
 * its body. Its parts nest as the Dusk Rider's steed's, each leg a leg, shin and hoof: play the clips under
 * animations/draught_horse/ on [RIG] with the rig animator (client/combat/RigAnimator.kt). Draw it cutout (some hair
 * and the rings have clear texels). See guildmark-notes/living-world/caravan-art.md.
 */
object DraughtHorseMesh {{
    const val TEX_WIDTH = {TEX_W}
    const val TEX_HEIGHT = {TEX_H}

    /** The coats: textures/entity/caravan/draught_horse_<coat>.png. */
    val COATS: List<String> = listOf({coats})

    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun horse(): LayerDefinition {{
{body}
    }}

    /** The parts the clips move, for the rig animator. */
    val RIG: List<String> = listOf({rig})

    /**
     * Hidden unless wanted: "nosebag" (feeding at the stand), "reins_tied" (the reins tied up at the saddle while
     * nobody drives), and the cut ends of the traces ("trace_r_cut", "trace_l_cut") and reins ("rein_r_cut",
     * "rein_l_cut") once it's cut loose or lies dead by the wreck: then hide "rein_r" and "rein_l".
     */
    val HIDDEN: List<String> = listOf({hidden})

    /**
     * The reins from the bit back to the rein rings: "rein_r" and "rein_l" (the body's, pivoted at [TERRET_RIGHT] and
     * [TERRET_LEFT]) are [REIN_LENGTH] px along their own +y, laid at rest toward the bit. Each frame, turn each so its
     * +y runs from its ring to its bit ring ([BIT_RIGHT], [BIT_LEFT] as the head is posed) and set yScale to the
     * distance over [REIN_LENGTH]. The driver's hands hold them on from the rings (living_props.py's "rein_*" pieces).
     */
    const val REIN_LENGTH = {kf(REIN_LENGTH[0])}

    /** A point on the horse: [x], [y], [z] (px) in the frame of [part] (from its pivot, turned with it). */
    class Point(val part: String, val x: Float, val y: Float, val z: Float)

    /** The bit's rings, where the reins start (the right one's x mirrored). At rest: {kv(bit_l_w)} (model space). */
    val BIT_LEFT = {bit_l}
    val BIT_RIGHT = {bit_r}
    /** The rein rings on the saddle the reins run through (WagonMesh.TERRET). At rest: {kv(ter_l_w)}. */
    val TERRET_LEFT = {ter_l}
    val TERRET_RIGHT = {ter_r}
    /** The tugs the shafts run through (the shafts' middle line by the horse: wagon.py's SHAFT_X). At rest: {kv(tug_l_w)}. */
    val TUG_LEFT = {tug_l}
    val TUG_RIGHT = {tug_r}
    /** The hame hooks, the traces' front ends (WagonMesh.HAMES). At rest: {kv(hame_l_w)}. */
    val HAME_LEFT = {hame_l}
    val HAME_RIGHT = {hame_r}

    /** Blocks it goes in one loop of "walk" (and "strain") and of "bolt": play them by distance. */
    const val WALK_STRIDE = {kf(strides["walk"])}
    const val BOLT_STRIDE = {kf(strides["bolt"])}

    /**
     * Something to hear on a clip's tick: "hoof" (a hoof down at the walk or the bolt), "stamp", "snort", "neigh",
     * "thud" (it comes down on the ground) or "tear" (a mouthful of grass torn up).
     */
    class Moment(val tick: Float, val what: String)

    val MOMENTS: Map<String, List<Moment>> = mapOf(
{mom}
    )

    /**
     * Each leg's joints, three numbers each: its pivot (in the body's frame), its knee or hock (in the leg's), its
     * fetlock (in the shin's) and the middle of its sole (in the hoof's).
     */
    val LEGS: Map<String, FloatArray> = mapOf(
{legs}
    )
}}
'''


def report(model, hooks, used):
    F = frames(model)
    lines = [f'draught horse: texture {TEX_W}x{TEX_H} ({used} rows packed), {sum(1 for _ in model.all_cubes())} cubes, '
             f'{sum(1 for _ in model.all_parts())} parts']
    for side, (loc, world) in sorted(hooks.items()):
        lines.append(f'  hame hook {side}: {np.round(world, 2)} (height {GROUND - world[1]:.2f}); wagon.HAMES {wg.HAMES}')
    for name in ('head', 'neck', 'collar', 'tail'):
        R, T, _ = F[name]
        lines.append(f'  {name} pivot at {np.round(T, 2)} (height {GROUND - T[1]:.2f})')
    R, T, _ = F['head']
    lines.append(f'  bit at {np.round(T + R @ np.array(BIT), 2)}; muzzle tip at {np.round(T + R @ np.array((0, 1.6, -14.6)), 2)}')
    return '\n'.join(lines)


def previews(model, texes, out):
    os.makedirs(out, exist_ok=True)
    paths = []
    for coat in COAT_ORDER:
        views = [view(model, texes[coat], yaw) for yaw in (-90, -35, 35, 150)]
        img = wg.sheet(views, 4, f'draught horse, {coat}', (360, 300))
        p = os.path.join(out, f'horse_{coat}.png')
        img.save(p)
        paths.append(p)
    close = [view(model, texes['bay'], yaw, pitch=-8, scale=14.0, size=(420, 360), center=(0.0, -30.0, -24.0), show=('nosebag',) if i == 2 else ())
             for i, yaw in enumerate((-60, -120, -30))]
    img = wg.sheet(close, 3, 'head and harness', (420, 360))
    p = os.path.join(out, 'horse_head.png')
    img.save(p)
    paths.append(p)
    return paths


def main(argv):
    assets, kotlin_dir, models_dir = argv[1], argv[2], argv[3]
    prev = argv[4] if len(argv) > 4 else None
    model, hooks, used = build()
    texes = {coat: texture(model, coat) for coat in COAT_ORDER}
    tdir = os.path.join(assets, 'textures', 'entity', 'caravan')
    os.makedirs(tdir, exist_ok=True)
    for coat, tex in texes.items():
        tex.save(os.path.join(tdir, f'draught_horse_{coat}.png'), optimize=True)
    import draught_horse_anims as anims
    strides, moments = anims.summary()
    os.makedirs(kotlin_dir, exist_ok=True)
    with open(os.path.join(kotlin_dir, 'DraughtHorseMesh.kt'), 'w') as f:
        f.write(kotlin_mesh(model, hooks, strides, moments))
    os.makedirs(models_dir, exist_ok=True)
    small = texes['bay'].resize((TEX_W, TEX_H), Image.LANCZOS)
    with open(os.path.join(models_dir, 'draught_horse.bbmodel'), 'w') as f:
        json.dump(model.bbmodel(small, 'draught_horse'), f)
    print(report(model, hooks, used))
    if prev:
        for p in previews(model, texes, prev):
            print('preview', p)


if __name__ == '__main__':
    main(sys.argv)
