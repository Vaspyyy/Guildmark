"""The gull's painting: three birds (a herring gull, a great black-backed gull, a young herring gull) on one atlas.

Every texel of every face is turned back into its place on the bird (`Face.P` in its part's frame, `Face.W` in the rest pose, where the bird
stands with its wings spread flat and every rotation zero) and painted from that place, as the other creatures are. The texture is HD (declared
64x64, painted at 128x128), so one texel is half a model pixel: the painting is pixel art at that size, rows of feathers one or two texels deep,
and nothing here is noise except the young bird's mottling, which is hashed per texel.

The wing is one picture: the rest pose lays it flat, so `(r, z)` (the distance out from the body's middle and the place from front to back) is a
plane that arm, forearm, hand, flaps and primaries all lie in, and the plumage, the feather rows, the white trailing edge, the black tip and its
mirrors are fields over that plane, cut to shape in the alpha. `top` faces are the upper side of every card and `bottom` the underside.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np

import sea_kit as K
from sea_kit import Out, HD
from ship import sstep, hash01
import island_gull_anim as A

BODY_PIVOT_Y = 24.0 - A.BODY_Y


def C(*v):
    return np.array(v, float)


# ================================================================== the three birds
#
# colours, dark to light where there are ramps. Everything is a straight RGB: the game lights the model, so the painting holds only what the
# feathers are, with a little shading to say where a form turns away (kept light: the engine shades each face again).

HERRING = dict(
    young=False,
    white=C(247, 248, 249), white_lo=C(222, 228, 236), white_hi=C(255, 255, 255),
    mantle=C(158, 170, 182), mantle_lo=C(126, 140, 156), mantle_hi=C(186, 196, 206),
    secondary=C(150, 162, 176), edge=C(250, 250, 252),
    prim_in=C(96, 106, 120), prim_black=C(32, 34, 42), prim_tip=C(248, 248, 250), mirror=C(250, 250, 252),
    under_flight=C(214, 220, 228), under_black=C(54, 56, 66),
    bill=C(244, 198, 50), bill_lo=C(208, 158, 30), bill_hi=C(255, 224, 108), spot=C(214, 52, 40), mouth=C(206, 78, 70),
    iris=C(244, 226, 128), pupil=C(26, 20, 18), ring=C(232, 92, 56),
    leg=C(238, 142, 152), leg_lo=C(196, 104, 120), leg_hi=C(250, 176, 182),
    nape=C(214, 222, 232), tail=C(247, 248, 249), tail_band=C(247, 248, 249),
)

BLACKBACK = dict(HERRING,
                 mantle=C(48, 54, 66), mantle_lo=C(30, 34, 44), mantle_hi=C(72, 80, 94),
                 secondary=C(44, 50, 62), edge=C(246, 246, 248),
                 prim_in=C(42, 46, 56), prim_black=C(22, 24, 30), under_flight=C(158, 164, 176), under_black=C(34, 36, 44),
                 bill=C(246, 204, 58), bill_lo=C(212, 164, 36),
                 leg=C(236, 200, 96), leg_lo=C(196, 158, 62), leg_hi=C(250, 228, 150),
                 iris=C(240, 214, 100), ring=C(226, 70, 48))

YOUNG = dict(
    HERRING, young=True,
    white=C(214, 196, 174), white_lo=C(172, 150, 128), white_hi=C(236, 222, 202),
    mantle=C(122, 100, 84), mantle_lo=C(76, 60, 50), mantle_hi=C(176, 150, 124),
    secondary=C(92, 74, 64), edge=C(210, 192, 168),
    prim_in=C(74, 60, 54), prim_black=C(46, 38, 36), prim_tip=C(200, 180, 156), mirror=C(120, 98, 84),
    under_flight=C(120, 100, 88), under_black=C(54, 44, 40),
    bill=C(64, 56, 54), bill_lo=C(40, 34, 34), bill_hi=C(150, 112, 100), spot=C(64, 56, 54), mouth=C(176, 98, 90),
    iris=C(56, 42, 36), pupil=C(20, 14, 12), ring=C(70, 52, 46),
    leg=C(196, 150, 150), leg_lo=C(150, 110, 114), leg_hi=C(220, 182, 178),
    nape=C(150, 126, 108), tail=C(236, 226, 212), tail_band=C(58, 46, 42),
)

PALETTES = [HERRING, BLACKBACK, YOUNG]
NAMES = ['gull', 'gull_blackback', 'gull_young']


# ================================================================== helpers

def tx(v):
    """The texel a coordinate falls in (HD texels, half a model px)."""
    return np.floor(np.asarray(v, float) * HD + 1e-6)


def body_xyz(f):
    """A texel's place in the body's frame (the rest pose has every rotation zero, so this is the world place less the body's pivot)."""
    W = f.W
    return W[..., 0], W[..., 1] - BODY_PIVOT_Y, W[..., 2]


def mixc(a, b, t):
    t = np.asarray(t, float)
    return a * (1.0 - t[..., None]) + b * t[..., None]


def put(img, col, mask):
    return np.where(np.asarray(mask, bool)[..., None], col, img)


def checker(a, b, size=1):
    """1 on every other texel in a checkerboard."""
    return ((np.floor(a * HD / size) + np.floor(b * HD / size)) % 2 == 0).astype(float)


def jitter(f, *keys, seed=0):
    """A random number in 0..1 for each texel (stable)."""
    h, w = f.h, f.w
    iy, ix = np.mgrid[0:h, 0:w]
    return hash01(ix + f.x0, iy + f.y0, 0, seed + f.seed(*keys) % 100000)


# ================================================================== the body

def feather_scales(x, y, z, size, off):
    """A scale-shaped feather edge pattern: 0 at the edge of each scale, 1 in its middle (rows alternate by half a scale)."""
    row = np.floor(y / size)
    cx = np.floor(x / size + 0.5 * (row % 2))
    fx = x / size + 0.5 * (row % 2) - cx - 0.5
    fy = y / size - row - 0.5
    d = np.hypot(fx, fy * 1.2)
    return 1.0 - sstep(0.30, 0.62, d)


def paint_torso(f, pal):
    x, y, z = body_xyz(f)
    base = np.broadcast_to(pal['white'], f.P.shape).copy()
    n = f.n
    # a touch of shade where the body turns down and where the wing lies on it
    under = sstep(0.2, 1.4, y)
    base = mixc(base, pal['white_lo'], 0.45 * under * (1.0 if abs(n[1]) < 0.5 else 1.0))
    if pal['young']:
        # mottled: every feather a dark centre with a pale edge, row on row
        sc = feather_scales(x * 1.0 + z * 0.0, y * 0.9 + z * 0.55, z, 1.0, 0.0)
        rnd = jitter(f, 'torso', seed=3)
        dark = pal['mantle'] * (0.82 + 0.3 * rnd[..., None])
        flank = np.broadcast_to(pal['white'], f.P.shape)
        belly = sstep(0.3, 1.2, y)
        col = mixc(dark, flank, 0.25 + 0.55 * belly)
        col = mixc(col, pal['mantle_hi'], 0.55 * (1.0 - sc) * (1.0 - 0.6 * belly))
        # the breast is streaked, the belly pale
        col = mixc(col, pal['white'], 0.6 * belly * (0.4 + 0.6 * rnd))
        return Out(col)
    # the mantle: the back and the shoulders, ending in a rounded end at the rump
    mant = (y < -0.55 + 0.45 * sstep(1.0, 2.2, z)) & (z > -2.45) & (z < 2.3 - 0.9 * sstep(-0.5, 0.0, -y))
    col = np.where(mant[..., None], pal['mantle'], base)
    edge = mant & ((y > -0.55 + 0.45 * sstep(1.0, 2.2, z) - 0.5) | (z > 1.8))
    col = put(col, mixc(pal['mantle'], pal['mantle_lo'], 0.5), edge & (tx(y) % 2 == 0))
    # a faint scalloping on the shoulders: scapulars laid row on row
    sc = feather_scales(x, y * 0.7 + z * 0.9, z, 1.0, 0.0)
    col = put(col, mixc(pal['mantle'], pal['mantle_hi'], 0.4 * (1.0 - sc)), mant & (z < 1.6))
    return Out(col)


def paint_neck(f, pal):
    x, y, z = body_xyz(f)
    col = np.broadcast_to(pal['white'], f.P.shape).copy()
    # the back of the neck takes a grey wash, the throat is clean
    back = (z > -2.2) & (y < -1.0) & (np.abs(x) < 0.8)
    if pal['young']:
        rnd = jitter(f, 'neck', seed=5)
        sc = feather_scales(x, y, z, 1.0, 0.0)
        col = mixc(pal['mantle'] * (0.9 + 0.2 * rnd[..., None]), pal['white'], 0.35)
        col = mixc(col, pal['mantle_hi'], 0.5 * (1.0 - sc))
        return Out(col)
    col = put(col, mixc(pal['white'], pal['nape'], 0.6), back)
    # fine streaks on the nape in the non-breeding dress
    streak = (np.mod(tx(x) + tx(y) * 3.0, 4) == 0) & back
    col = put(col, mixc(pal['nape'], pal['mantle'], 0.35), streak)
    return Out(col)


def paint_head(f, pal):
    x, y, z = f.P[..., 0], f.P[..., 1], f.P[..., 2]
    col = np.broadcast_to(pal['white'], f.P.shape).copy()
    ax = np.abs(x)
    if pal['young']:
        rnd = jitter(f, 'head', seed=7)
        col = mixc(pal['white'], pal['mantle_hi'], 0.45 + 0.20 * (z > -0.8) + 0.25 * rnd)
        col = mixc(col, pal['mantle'], 0.55 * (y < -0.35))
        col = mixc(col, pal['mantle_lo'], 0.55 * ((ax > 0.85) & (np.abs(y + 0.2) < 0.55) & (z < 0.0) & (z > -1.6)))
        return Out(col)
    # the orbital ring round the eye: a one texel ring of red-orange on the sides of the head, the eye's place at (+-1.0, -0.2, -1.0)
    lid = (ax > 0.7) & (np.abs(z + 1.0) < 0.3) & (np.abs(y + 0.2) > 0.55) & (np.abs(y + 0.2) < 1.05)
    col = put(col, mixc(pal['white'], pal['ring'], 0.65), lid)
    # the nape and crown a shade cooler
    cool = (z > -0.2) & (y < 0.3)
    col = mixc(col, pal['nape'], 0.5 * cool)
    # the chin and throat: white with a shade under the jaw
    col = mixc(col, pal['white_lo'], 0.5 * sstep(0.4, 1.0, y))
    return Out(col)


def paint_eye(f, pal):
    P = f.P
    y, z = P[..., 1], P[..., 2]
    iris = np.broadcast_to(pal['iris'], P.shape).copy()
    # the pupil in the lower front texel of the eye, the iris round it; a glint above it
    pup = (y > -0.05) & (z < 0.0)
    col = put(iris, pal['pupil'], pup)
    if not pal['young']:
        col = put(col, pal['pupil'], (y > -0.05))
    return Out(col)


def paint_bill(f, pal):
    P = f.P
    y, z = P[..., 1], P[..., 2]
    col = np.broadcast_to(pal['bill'], P.shape).copy()
    seg = f.kw.get('seg')
    if f.face == 'bottom':
        return Out(np.broadcast_to(pal['mouth'], P.shape).copy())
    # light along the ridge, shade down the side; the base darkens where it meets the face; a nostril slot on each side
    col = mixc(col, pal['bill_hi'], 0.7 * sstep(-0.3, -0.5, y) * (f.face == 'top'))
    col = mixc(col, pal['bill_lo'], 0.5 * sstep(0.0, 0.5, y) * (f.face in ('left', 'right')))
    if seg == 'base' and f.face in ('left', 'right'):
        nostril = (np.abs(z + 0.45) < 0.26) & (np.abs(y + 0.15) < 0.2)
        col = put(col, pal['bill_lo'] * 0.55, nostril)
    if seg == 'hook':
        col = mixc(col, pal['bill_lo'], 0.35 * (f.face == 'front'))
        col = mixc(col, pal['bill_hi'], 0.5 * (f.face == 'top'))
    if pal['young']:
        # dark bill, paler pink at the base
        base = sstep(-1.0, -0.4, z) if seg == 'base' else 0.0
        col = mixc(col, pal['bill_hi'], 0.7 * base)
    return Out(col)


def paint_jaw(f, pal):
    P = f.P
    y, z = P[..., 1], P[..., 2]
    col = np.broadcast_to(pal['bill'], P.shape).copy()
    seg = f.kw.get('seg')
    if f.face == 'top':
        return Out(np.broadcast_to(pal['mouth'], P.shape).copy())
    col = mixc(col, pal['bill_lo'], 0.5 * sstep(0.0, 0.3, y))
    if seg == 'gonys' and not pal['young']:
        # the red spot on the angle of the lower bill
        col = put(col, pal['spot'], np.ones(y.shape, bool) if f.face in ('left', 'right', 'front', 'bottom') else np.zeros(y.shape, bool))
    if seg == 'mid' and not pal['young'] and f.face in ('left', 'right'):
        col = put(col, pal['spot'], z < -1.12)
    if pal['young']:
        col = mixc(col, pal['bill_hi'], 0.6 * sstep(-1.0, -0.4, z) * (seg == 'base'))
    return Out(col)


# ================================================================== the tail and the legs

def paint_tail(f, pal):
    P = f.P
    x, z = P[..., 0], P[..., 2]
    seg = f.kw.get('seg')
    col = np.broadcast_to(pal['tail'], P.shape).copy()
    alpha = np.ones(x.shape)
    L = A.TAIL['len'] if seg == 'mid' else A.TAIL['side_len']
    # the tail's end: a square end rounded at the corners, a notch between feathers; the outer feathers a touch shorter
    if seg == 'mid':
        alpha = (z < L - 0.5 * (np.abs(x) > 0.55)).astype(float)
    else:
        sx = np.abs(x)
        alpha = (z < (L - 0.5) + 0.5 * (sx < 0.55) - 0.5 * (sx > 0.8)).astype(float)
    # feather division lines running back, one texel wide
    line = (np.mod(tx(x), 2) == 0) & (z > 0.4)
    col = mixc(col, pal['white_lo'], 0.25 * line)
    if pal['young']:
        band = z > L - 1.1
        col = put(col, pal['tail_band'], band)
        col = put(col, pal['edge'], z > L - 0.5)
    else:
        # the underside a shade cooler
        if f.face == 'bottom':
            col = mixc(col, pal['white_lo'], 0.35)
    return Out(col, alpha)


def paint_trouser(f, pal):
    y = f.P[..., 1]
    col = np.broadcast_to(pal['white'], f.P.shape).copy()
    col = mixc(col, pal['white_lo'], 0.45 * sstep(0.2, 1.5, y))
    if pal['young']:
        rnd = jitter(f, 'trouser', seed=11)
        col = mixc(pal['white'], pal['mantle_hi'], 0.4 + 0.3 * rnd)
    return Out(col)


def paint_leg(f, pal):
    y = f.P[..., 1]
    col = np.broadcast_to(pal['leg'], f.P.shape).copy()
    # scutes: a darker line every second texel across the tarsus, the front lit and the back shaded
    scute = (np.mod(tx(y), 2) == 1)
    col = put(col, pal['leg_lo'], scute)
    col = mixc(col, pal['leg_hi'], 0.6 * (f.face == 'front'))
    col = mixc(col, pal['leg_lo'], 0.5 * (f.face in ('back', 'left', 'right')) * (1 - scute))
    return Out(col)


def paint_foot(f, pal):
    """A webbed foot seen from above or below, four texels by four: the middle toe's tip, the outer toes' tips and the web between them, the heel."""
    x, z = f.P[..., 0], f.P[..., 2]
    col_i = np.clip(np.floor((x + 1.0) * HD + 1e-6), 0, 3)
    row_i = np.clip(np.floor((z + 1.75) * HD + 1e-6), 0, 3)
    inner = (col_i == 1) | (col_i == 2)
    alpha = np.where(row_i == 0, inner, np.where(row_i == 3, inner, True))
    toe = ((row_i == 0) & inner) | ((row_i == 1) & ~inner)
    web = (row_i == 1) & inner | (row_i == 2) & ~inner
    col = np.broadcast_to(pal['leg'], f.P.shape).copy()
    col = put(col, pal['leg_lo'], toe)
    col = put(col, pal['leg_hi'], web)
    if f.face == 'bottom':
        col = mixc(col, pal['leg_lo'], 0.30)
    return Out(col, alpha.astype(float))


# ================================================================== the fish

def paint_fish(f, pal):
    P = f.P
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    back = C(58, 104, 118)
    silver = C(196, 210, 218)
    belly = C(238, 242, 244)
    if f.kw.get('seg') == 'tail':
        col = np.broadcast_to(C(76, 120, 132), P.shape).copy()
        return Out(col)
    t = sstep(-0.5, 0.5, y)
    col = mixc(mixc(back, silver, sstep(0.0, 0.35, t * 1.6)), belly, sstep(0.55, 0.95, t))
    # the lateral line and the eye at the head end (-x)
    col = put(col, silver * 0.7, (np.abs(y) < 0.14))
    col = put(col, C(24, 28, 34), (x < -1.8) & (np.abs(y + 0.12) < 0.2) & (np.abs(z) > 0.3))
    return Out(col)


# ================================================================== the wing, one field over the plane (r, z)

def rz(f):
    W = f.W
    return np.abs(W[..., 0]), W[..., 2]


R_WRIST = A.WING['shoulder'][0] + A.WING['arm_len'] + A.WING['fore_len']        # r of the wrist at rest


def lead_edge(r):
    """The wing's leading edge z at r."""
    return -1.0 + 0.2 * sstep(R_WRIST - 0.6, R_WRIST + 1.0, r)


def trail_edge(r):
    """The back of the secondaries at r (a straight slope from the body out to the wrist)."""
    return 2.4 - 0.7 * sstep(0.9, R_WRIST, r)


def wing_plumage(f, pal, dorsal):
    """(rgb, alpha) of the wing's plumage at the texel's (r, z). The picture: coverts along the front, then the secondaries to the white edge,
    the hand's coverts, and the primaries."""
    r, z = rz(f)
    shape = r.shape
    seg = f.kw.get('seg')
    le = lead_edge(r)
    te = trail_edge(r)
    c = z - le                                     # depth behind the leading edge
    d_te = te - z                                  # depth in front of the back of the secondaries
    alpha = np.ones(shape)
    if dorsal:
        base = np.broadcast_to(pal['mantle'], f.P.shape).copy()
    else:
        base = np.broadcast_to(pal['white'], f.P.shape).copy()
    col = base
    # ---------------- rows of feathers (dorsal): the lesser coverts, the median, the greater, then the flight feathers
    row = np.floor(c * HD)
    if dorsal:
        r1 = c < 0.5
        r2 = (c >= 0.5) & (c < 1.0)
        r3 = (c >= 1.0) & (c < 1.5)
        col = put(col, mixc(pal['mantle'], pal['mantle_hi'], 0.45), r1)
        col = put(col, pal['mantle'], r2)
        col = put(col, mixc(pal['mantle'], pal['mantle_hi'], 0.25), r3)
        # the flight feathers (secondaries) a shade deeper and a pale tip: the white trailing edge
        flight = c >= 1.5
        col = put(col, pal['secondary'], flight)
        # a dark line under each row, and a break between feathers every texel pair
        col = put(col, pal['mantle_lo'], (row == np.floor(0.5 * HD)) | (row == np.floor(1.0 * HD)) | (row == np.floor(1.5 * HD)) & False)
        seam = (np.mod(tx(r), 2) == 0)
        col = mixc(col, pal['mantle_lo'], 0.18 * seam * (c > 0.5))
        # the edge: white tips on the secondaries and on the greater coverts
        tipw = (d_te < 0.5)
        col = put(col, pal['edge'], tipw & (seg in ('flap', 'wing', 'cov', 'hand', 'bar') or True))
    else:
        # underside: white coverts, pale grey flight feathers with a faint dark line at their base, the edge white
        flight = c >= 1.5
        col = put(col, pal['under_flight'], flight & (d_te >= 0.5))
        col = put(col, mixc(pal['white'], pal['white_lo'], 0.5), (c >= 0.5) & (c < 1.5))
        col = put(col, pal['white'], d_te < 0.5)
        col = put(col, mixc(pal['white'], pal['under_flight'], 0.6), (np.mod(tx(r), 2) == 0) & flight & (d_te >= 0.5))
    # leading edge: a dark line along the front on top, white underneath
    if dorsal:
        col = put(col, mixc(pal['mantle'], pal['mantle_lo'], 0.5), c < 0.25)
    # ---------------- the cards' own shapes in the alpha
    # the arm's and forearm's flaps end at the sloping back of the secondaries, with a one texel notch between feathers
    notch = (np.mod(tx(r), 2) == 1)
    alpha = np.where(d_te < 0.0, 0.0, alpha)
    alpha = np.where((d_te < 0.5) & notch & (seg in ('flap',)), 0.0, alpha)
    return col, alpha


def paint_wing_card(f, pal):
    dorsal = f.face == 'top'
    col, alpha = wing_plumage(f, pal, dorsal)
    return Out(col, alpha)


def paint_cov(f, pal):
    """The layer of coverts lying along the front of the arm and the forearm: lighter, with its back edge scalloped."""
    r, z = rz(f)
    col, alpha = wing_plumage(f, pal, True)
    le = lead_edge(r)
    c = z - le
    seg = f.kw.get('seg')
    col = mixc(col, pal['mantle_hi'], 0.30)
    # a scalloped back edge (the last texel row alternates) and a dark shadow line under it
    depth = (A.WING['arm_cov'] if seg == 'arm_v' else A.WING['fore_cov'])
    edge = c > depth - 0.5
    alt = (np.mod(tx(r), 2) == 0)
    alpha = np.where(edge & alt, 0.0, alpha)
    col = put(col, mixc(pal['mantle'], pal['mantle_lo'], 0.6), edge & ~alt)
    return Out(col, alpha)


def paint_bar(f, pal):
    """The thick front edge of the arm: the shoulder's ridge of small feathers."""
    r, z = rz(f)
    y = f.P[..., 1]
    col = np.broadcast_to(pal['mantle'], f.P.shape).copy()
    col = mixc(col, pal['mantle_hi'], 0.35 * (f.face == 'top'))
    col = mixc(col, pal['white'], 0.9 * (f.face in ('bottom',)) + 0.7 * (f.face in ('front',)) * (y > 0.0))
    col = mixc(col, pal['white'], 0.8 * sstep(-0.1, 0.3, y) * (f.face in ('front', 'left', 'right', 'back')))
    return Out(col)


def paint_hand(f, pal):
    """The carpal block: the front of the hand, grey above with a dark smudge toward the primaries, white below."""
    dorsal = f.face == 'top'
    col, alpha = wing_plumage(f, pal, dorsal)
    r, z = rz(f)
    if dorsal:
        dark = sstep(R_WRIST + 0.8, R_WRIST + 2.2, r)
        col = mixc(col, pal['prim_in'], 0.7 * dark)
    return Out(col, alpha)


def paint_prim(f, pal):
    """A primary: a long narrow feather, inner ones grey going black at the tip, the outer ones black, a white tip and a mirror on the outermost two."""
    P = f.P
    k = f.kw.get('k', 0)
    L = A.WING['pri_len'][k]
    out = np.abs(P[..., 0])                       # along the feather from its root
    side = P[..., 2]                              # across it: -0.5 (front) to 0.5 (back)
    dorsal = f.face == 'top'
    alpha = np.ones(out.shape)
    # the tip tapers: the last two texels narrow toward the front edge
    tipd = L - out
    alpha = np.where((tipd < 0.5) & (side > -0.5 + 0.5), 0.0, alpha)
    alpha = np.where((tipd < 0.25 + 0.0) & (side > -0.5 + 0.0), 0.0, alpha)
    ink = np.broadcast_to(pal['prim_black'], P.shape).copy()
    if dorsal:
        grey = pal['prim_in']
        # the inner feathers keep the grey, the black comes on from the tip inward; the outer ones are black almost from the root
        black_from = [0.40, 0.55, 0.70, 0.78][k]
        t = out / L
        col = mixc(np.broadcast_to(grey, P.shape).copy(), ink, sstep(black_from - 0.12, black_from + 0.04, t))
        if pal['young']:
            col = mixc(col, pal['prim_black'], 0.5)
    else:
        # underneath the feathers are paler: grey with the black coming on only at the tip
        t = out / L
        col = mixc(np.broadcast_to(pal['under_flight'], P.shape).copy(), np.broadcast_to(pal['under_black'], P.shape).copy(), sstep([0.55, 0.62, 0.68, 0.70][k], [0.8, 0.85, 0.88, 0.9][k], t))
    # the white tip and the mirror
    tipw = tipd < 0.5 + (0.0)
    col = put(col, pal['prim_tip'], tipw & (k >= 2) & ~pal['young'])
    if k == 3 and not pal['young']:
        mir = (tipd > 0.75) & (tipd < 1.5) & (side > -0.5)
        col = put(col, pal['mirror'], mir)
    if k == 2 and not pal['young']:
        mir = (tipd > 1.0) & (tipd < 1.5) & (side > -0.1)
        col = put(col, pal['mirror'], mir)
    # a pale shaft down the middle of each feather
    shaft = (np.abs(side + 0.1) < 0.12) & (out > 0.3)
    col = mixc(col, pal['white_lo'] if dorsal else pal['white'], 0.22 * shaft)
    return Out(col, alpha)


# ================================================================== the whole texture

def mats_for(pal):
    return {
        'body': lambda f: paint_torso(f, pal), 'neck': lambda f: paint_neck(f, pal), 'head': lambda f: paint_head(f, pal),
        'eye': lambda f: paint_eye(f, pal), 'bill': lambda f: paint_bill(f, pal), 'jaw': lambda f: paint_jaw(f, pal),
        'wing': lambda f: paint_wing_card(f, pal), 'flap': lambda f: paint_wing_card(f, pal), 'cov': lambda f: paint_cov(f, pal),
        'bar': lambda f: paint_bar(f, pal), 'hand': lambda f: paint_hand(f, pal), 'prim': lambda f: paint_prim(f, pal),
        'tail': lambda f: paint_tail(f, pal), 'trouser': lambda f: paint_trouser(f, pal), 'leg': lambda f: paint_leg(f, pal),
        'foot': lambda f: paint_foot(f, pal), 'fish': lambda f: paint_fish(f, pal),
    }


def paint_all(scene, model):
    """[(name, texture (128x128 RGBA uint8))] of the three birds."""
    out = []
    for name, pal in zip(NAMES, PALETTES):
        tex, _layers = K.paint_model(scene, mats_for(pal), {}, model.tex_w, model.tex_h)
        out.append((name, tex))
    return out
