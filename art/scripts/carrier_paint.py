"""
The carriers' painters (`pack_harness.py`, `laden_pack.py`, `player_cart.py`, `carrier_wagon.py`): wicker, leather, brass, rope,
burlap, linen, straw, wool and the rest, each a modelkit painter made with `townsfolk_tools.painter` (so a rect of any size
gets a texture of its own, seeded from the painter's key, the face and the size: the same inputs always paint the same
pixels). They paint at the shipped HD size, 2 texels to a model px; the numbers a painter reads from the face are fractions
of it (0..1 across, 0..1 down), so a painter works on any cube.

A painter that has a long axis takes it ('x', 'y' or 'z' of the cube) and lays its grain, stitches or strands along it on
the faces that run that way (`townsfolk_tools.AXIS_ON_FACE`) and shows the cut end on the faces that point along it.
"""
import math

import numpy as np

import townsfolk_tools as tt
from townsfolk_tools import uv, mix, solid, noise_fill, shade_edges, AXIS_ON_FACE

# ================================================================== colours

LEATHER = (126, 80, 48); LEATHER_D = (80, 48, 28); LEATHER_L = (176, 122, 78)      # saddle leather, a warm brown
STITCH = (230, 208, 154)
WICKER = tt.WICKER; WICKER_D = tt.WICKER_D; WICKER_L = tt.WICKER_L
STRAW = tt.STRAW; STRAW_D = tt.STRAW_D; STRAW_L = tt.STRAW_L
ROPE = (192, 164, 112); ROPE_D = (132, 106, 66); ROPE_L = (226, 204, 154)
BURLAP = tt.BURLAP; BURLAP_D = tt.BURLAP_D; BURLAP_L = tt.BURLAP_L
LINEN = (222, 214, 192); LINEN_D = (168, 158, 134); LINEN_L = (244, 240, 226)
WOOL = (232, 228, 220); WOOL_D = (160, 154, 146); WOOL_L = (252, 250, 246)         # the blanket's neutral cloth: the saved colour tints it
BRASS = tt.BRASS; BRASS_D = tt.BRASS_D; BRASS_L = tt.BRASS_L
IRON = (70, 70, 78); IRON_D = (36, 36, 44); IRON_L = (140, 142, 156)
OAK = tt.OAK; OAK_D = tt.OAK_D; OAK_L = tt.OAK_L
ASH = tt.WOOD; ASH_D = tt.WOOD_D; ASH_L = tt.WOOD_L
STENCIL = (116, 50, 38)                                                              # the stencilled wheel on a sack
CLAY = tt.CLAY; CLAY_D = tt.CLAY_D; CLAY_L = tt.CLAY_L


# Which way a strap runs on each face, when it goes round a cross-section instead of straight along one axis
RING_Y = {'left': 'v', 'right': 'v', 'top': 'u', 'bottom': 'u', 'front': 'v', 'back': 'v'}      # round the x-y plane (a girth, a noseband)
BAND_Z = {'left': 'u', 'right': 'u', 'top': 'v', 'bottom': 'v', 'front': 'u', 'back': 'u'}      # along the flank, its front crossing the chest


def _axes(axis):
    """A cube axis ('x', 'y', 'z') or a ready map {face: 'u' | 'v' | None} of how the grain runs on each face."""
    return axis if isinstance(axis, dict) else AXIS_ON_FACE[axis]


def _clip(a):
    a[..., :3] = np.clip(a[..., :3], 0, 255)
    return a


def _strand_tones(rng, n, spread=0.08):
    return 1.0 + rng.normal(0, spread, n)


# ================================================================== wicker

def basket_weave(w, h, rng, su=2.0, sv=2.0, over=2, base=WICKER, dark=WICKER_D, light=WICKER_L, rod=4):
    """Basketry: horizontal weavers going over [over] stakes and under [over] stakes, each row stepping on by one, the stakes
    standing 'su' texels apart and the weavers 'sv'; every strand lit along its middle and shaded at its edges, its own
    tone; every [rod]th stake a darker, thicker rod."""
    ys, xs = np.mgrid[0:h, 0:w]
    cu = np.floor(xs / su).astype(int)
    cv = np.floor(ys / sv).astype(int)
    fu = (xs / su) % 1.0
    fv = (ys / sv) % 1.0
    over_h = ((cu // over) + cv) % 2 == 0               # the weaver of this row is over this stake
    hump_h = np.sin(np.clip(fv, 0.02, 0.98) * math.pi)
    hump_v = np.sin(np.clip(fu, 0.02, 0.98) * math.pi)
    hump = np.where(over_h, hump_h, hump_v)
    nu, nv = int(cu.max()) + 1, int(cv.max()) + 1
    tone_h = _strand_tones(rng, nv + 2)[cv]
    tone_v = _strand_tones(rng, nu + 2)[cu]
    tone = np.where(over_h, tone_h, tone_v)
    a = solid(w, h, base)
    a[..., :3] = mix(dark, light, (0.18 + 0.82 * hump)[..., None])
    a[..., :3] *= tone[..., None]
    if rod:
        thick = (cu % rod == 0) & ~over_h
        a[..., :3] = np.where(thick[..., None], mix(a[..., :3], dark, 0.35), a[..., :3])
    # a dark seam where one strand dives under another
    seam = (np.minimum(fu, 1 - fu) < 0.1) | (np.minimum(fv, 1 - fv) < 0.1)
    a[..., :3] = np.where(seam[..., None], a[..., :3] * 0.82, a[..., :3])
    return a


def wicker_painter(key, tilt=0.0):
    """A wicker wall: basket weave on the four sides, the base's close dark weave below, the lid's plain weave on top."""
    def fn(face, w, h, rng):
        if face == 'bottom':
            a = basket_weave(w, h, rng, 2, 2, 1, WICKER_D, tuple(c * 0.6 for c in WICKER_D), WICKER)
            return a
        a = basket_weave(w, h, rng, 2.0, 2.0, 2)
        if face == 'top':
            a[..., :3] *= 0.9
        return shade_edges(a, 0.2, width=max(1, min(w, h) // 8))
    return tt.painter(key, fn)


def plait_painter(key, axis='x', base=WICKER, dark=WICKER_D, light=WICKER_L):
    """A plaited rail: three strands braided along the cube's [axis], one crossing the next every few texels."""
    def fn(face, w, h, rng):
        along = _axes(axis).get(face)
        a = solid(w, h, base)
        U, V = uv(w, h)
        if along is None:
            a[..., :3] = mix(base, dark, 0.35)
            return shade_edges(a, 0.22)
        s = (U * w if along == 'u' else V * h)
        t = (V if along == 'u' else U)
        period = 5.0
        phase = (s / period + t * 1.5) % 1.0
        strand = np.sin(phase * math.pi) ** 0.8
        a[..., :3] = mix(dark, light, (0.2 + 0.8 * strand)[..., None])
        crease = phase < 0.07
        a[..., :3] = np.where(crease[..., None], np.array(dark, float) * 0.8, a[..., :3])
        a[..., :3] *= 1 + rng.normal(0, 0.04, (h, w, 1))
        return shade_edges(a, 0.18, width=max(1, min(w, h) // 5))
    return tt.painter(key, fn)


# ================================================================== leather and straps

def leather_painter(key, axis='x', base=LEATHER, dark=LEATHER_D, light=LEATHER_L, stitch=True, worn=0.0):
    """A leather strap along [axis]: grain, the edges dark and rounded, a row of stitches along each edge (when it is wide
    enough for them), the cut ends dark and flat; [worn] lightens rubbed patches."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.05)
        U, V = uv(w, h)
        along = _axes(axis).get(face)
        # a soft mottle, like hide
        m = rng.normal(0, 1, (h // 2 + 2, w // 2 + 2))
        m = np.kron(m, np.ones((2, 2)))[:h, :w]
        a[..., :3] *= (1 + 0.035 * m)[..., None]
        if along is None:
            a[..., :3] = mix(a[..., :3], dark, 0.45)
            return shade_edges(a, 0.2)
        across = V if along == 'u' else U
        n_across = h if along == 'u' else w
        n_along = w if along == 'u' else h
        e = np.abs(across - 0.5) * 2
        a[..., :3] = mix(a[..., :3], dark, np.clip((e - 0.55) * 1.6, 0, 0.7)[..., None])
        a[..., :3] = mix(a[..., :3], light, np.clip((0.3 - e) * 0.5, 0, 0.18)[..., None])
        if stitch and n_across >= 5:
            pos = (np.arange(n_along)[None, :] if along == 'u' else np.arange(n_along)[:, None]) * np.ones((h, w))
            dash = (pos % 4) < 2
            row = (np.abs(across - 0.2) * n_across < 0.6) | (np.abs(across - 0.8) * n_across < 0.6)
            a[..., :3] = np.where((dash & row)[..., None], mix(a[..., :3], STITCH, 0.8), a[..., :3])
            a[..., :3] = np.where(((~dash) & row)[..., None], a[..., :3] * 0.8, a[..., :3])
        if worn:
            wm = (rng.random((h, w)) < worn)
            a[..., :3] = np.where(wm[..., None], mix(a[..., :3], light, 0.45), a[..., :3])
        return a
    return tt.painter(key, fn)


def pad_painter(key, base=(138, 92, 56), dark=(88, 56, 32)):
    """The thick leather pad of a collar or a saddle seat: quilted lozenges, a stitched edge."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.05)
        U, V = uv(w, h)
        if face not in ('top', 'bottom') and min(w, h) >= 4:
            q = np.abs(((U * w / 3.0) % 1.0) - 0.5) + np.abs(((V * h / 3.0) % 1.0) - 0.5)
            a[..., :3] = mix(a[..., :3], dark, np.clip((q - 0.4) * 2.2, 0, 0.55)[..., None])
        a[..., :3] = mix(a[..., :3], dark, np.clip((np.abs(V - 0.5) * 2 - 0.7) * 2, 0, 0.5)[..., None])
        return shade_edges(a, 0.22)
    return tt.painter(key, fn)


# ================================================================== brass and iron

def brass_painter(key, wear=0.1, glint=0.7):
    def fn(face, w, h, rng):
        a = tt.metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=wear)
        if min(w, h) >= 3:
            U, V = uv(w, h)
            g = np.exp(-((U - 0.3) ** 2 + (V - 0.3) ** 2) * 14)
            a[..., :3] = mix(a[..., :3], (255, 240, 190), (g * glint)[..., None])
        return a
    return tt.painter(key, fn)


def bell_painter(key, part='waist'):
    """A cast brass bell, built from stepped boxes: [part] 'cap' (the crown, with the loop's stub), 'shoulder', 'waist' and
    'lip' (the mouth: dark inside underneath). Each side is lit from the upper left along a vertical ridge, with a
    moulded ring at the part's foot."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.05, bevel=False)
        U, V = uv(w, h)
        if face == 'bottom':
            if part == 'lip':
                r = np.maximum(np.abs(U - 0.5), np.abs(V - 0.5)) * 2
                a[..., :3] = mix((40, 26, 12), (96, 66, 26), np.clip(1.2 - r, 0, 1)[..., None])
                a[..., :3] = np.where((r > 0.84)[..., None], np.array(BRASS_D, float), a[..., :3])
            return a
        if face == 'top':
            a[..., :3] = mix(a[..., :3], BRASS_L, 0.15)
            return shade_edges(a, 0.18)
        # a vertical highlight band, brighter on the lit sides
        lit = {'front': 0.30, 'left': 0.62, 'right': 0.40, 'back': 0.5}[face]
        g = np.exp(-((U - lit) / 0.14) ** 2)
        a[..., :3] = mix(a[..., :3], (255, 238, 176), (g * 0.55)[..., None])
        a[..., :3] *= (0.78 + 0.22 * np.clip(1.0 - np.abs(U - 0.5) * 1.1, 0, 1))[..., None]
        # the moulded ring at the foot, and a fine one at the head
        foot = (V > 0.82)
        a[..., :3] = np.where(foot[..., None], a[..., :3] * 0.8, a[..., :3])
        ridge = (V > 0.7) & (V <= 0.82)
        a[..., :3] = np.where(ridge[..., None], mix(a[..., :3], BRASS_L, 0.4), a[..., :3])
        if part == 'lip':
            a[..., :3] = np.where((V > 0.9)[..., None], np.array(BRASS_D, float) * 0.9, a[..., :3])
        return a
    return tt.painter(key, fn)


def iron_painter(key, wear=0.06):
    return tt.iron_painter(key, IRON, IRON_D, IRON_L, wear=wear)


def ring_painter(key, base=BRASS, dark=BRASS_D, light=BRASS_L, thick=0.26, broad=('left', 'right')):
    """A flat ring drawn on the [broad] faces of a thin cube (clear in the middle and round it): a halter's or a collar's
    ring, a lantern's bail. The thin faces are plain metal."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, base, dark, light, rng, bevel=False, wear=0.05)
        U, V = uv(w, h)
        if min(w, h) < 4 or face not in broad:
            return a
        r = np.hypot((U - 0.5) * w, (V - 0.5) * h) / (min(w, h) / 2)
        ring = (r <= 1.0) & (r >= 1.0 - 2 * thick)
        a[~ring] = 0
        lit = np.exp(-((U - 0.3) ** 2 + (V - 0.3) ** 2) * 10)
        a[..., :3] = mix(a[..., :3], light, (lit * 0.6)[..., None])
        return a
    return tt.painter(key, fn)


def buckle_painter(key):
    """A brass buckle seen from the side of a strap: a frame round a dark slot, the tongue across it."""
    def fn(face, w, h, rng):
        a = tt.metal(w, h, BRASS, BRASS_D, BRASS_L, rng, wear=0.08)
        U, V = uv(w, h)
        if face in ('left', 'right') and min(w, h) >= 4:
            slot = (np.abs(U - 0.5) < 0.30) & (np.abs(V - 0.5) < 0.30)
            a[..., :3] = np.where(slot[..., None], np.array((44, 30, 16), float), a[..., :3])
            tongue = (np.abs(V - 0.5) < 0.09) & (U > 0.22) & (U < 0.86)
            a[..., :3] = np.where(tongue[..., None], np.array(BRASS_L, float), a[..., :3])
        return a
    return tt.painter(key, fn)


# ================================================================== rope, burlap, linen, straw

def rope_painter(key, axis='x', base=ROPE, dark=ROPE_D, light=ROPE_L, lay=4.0):
    """Laid rope along [axis]: diagonal strands, a dark crease between each, a light ridge on each; frayed to a darker flat
    end where it is cut."""
    def fn(face, w, h, rng):
        along = _axes(axis).get(face)
        a = noise_fill(w, h, base, rng, 0.05)
        U, V = uv(w, h)
        if along is None:
            a[..., :3] = mix(a[..., :3], dark, 0.4)
            return a
        s = (U * w if along == 'u' else V * h)
        t = (V * h if along == 'u' else U * w)
        ph = ((s + t * 0.9) / lay) % 1.0
        ridge = np.sin(ph * math.pi) ** 1.5
        a[..., :3] = mix(dark, light, (0.15 + 0.85 * ridge)[..., None]) * (1 + rng.normal(0, 0.04, (h, w, 1)))
        return a
    return tt.painter(key, fn)


def burlap_painter(key, part='body', mark=True, tint=1.0):
    """A burlap sack: loose weave, lumpy; creased; a stitched seam up one side; a stencilled wheel on the broad faces when
    [mark]. [part] 'body', 'top' (the swell above the tie) or 'neck' (the gathered mouth, tied)."""
    neck = part == 'neck'

    def fn(face, w, h, rng):
        a = tt.weave(w, h, BURLAP, BURLAP_D, BURLAP_L, rng, cell=max(1, min(w, h) // 8))
        U, V = uv(w, h)
        lump = np.sin(U * 4.7 + rng.uniform(0, 6)) * np.sin(V * 3.9 + rng.uniform(0, 6))
        a[..., :3] *= (1 + 0.12 * lump)[..., None]
        for _ in range(2):
            ang, off = rng.uniform(0, math.pi), rng.uniform(-0.3, 0.3)
            d = (U - 0.5) * math.cos(ang) + (V - 0.5) * math.sin(ang) - off
            a[..., :3] *= (1 - 0.16 * np.exp(-(d / 0.05) ** 2))[..., None]
        if part == 'top':
            a[..., :3] *= 1.04
        if mark and not neck and face in ('front', 'back', 'left', 'right') and min(w, h) >= 8:
            # the stencilled wheel: a ring, a hub and six spokes
            x, y = (U - 0.5) * w, (V - 0.5) * h
            r = np.hypot(x, y) / (min(w, h) * 0.22)
            ang = np.arctan2(y, x)
            spoke = (np.abs(((ang / (math.pi / 3) + 0.5) % 1.0) - 0.5) * r * 1.6 < 0.16) & (r < 1.0)
            rim = (r > 0.8) & (r < 1.0)
            hub = r < 0.2
            a[..., :3] = np.where((rim | spoke | hub)[..., None], mix(a[..., :3], STENCIL, 0.62), a[..., :3])
        if face in ('left',) and not neck and min(w, h) >= 6:
            seam = (np.abs(U - 0.9) * w < 0.7) & ((np.arange(h)[:, None] % 3) < 2)
            a[..., :3] = np.where(seam[..., None], mix(a[..., :3], BURLAP_D, 0.7), a[..., :3])
        a[..., :3] *= tint
        return shade_edges(a, 0.34 if not neck else 0.2, width=max(1, min(w, h) // 4))
    return tt.painter(key, fn)


def linen_painter(key, bundle=True):
    """A bundle of folded linen: fine weave, soft folds and a faint blue selvedge line."""
    def fn(face, w, h, rng):
        a = tt.weave(w, h, LINEN, LINEN_D, LINEN_L, rng, cell=max(1, min(w, h) // 10))
        U, V = uv(w, h)
        for _ in range(3):
            ang, off = rng.uniform(0, math.pi), rng.uniform(-0.35, 0.35)
            d = (U - 0.5) * math.cos(ang) + (V - 0.5) * math.sin(ang) - off
            a[..., :3] *= (1 - 0.14 * np.exp(-(d / 0.06) ** 2))[..., None]
        if min(w, h) >= 6 and face not in ('top', 'bottom'):
            line = np.abs(V - 0.22) * h < 0.8
            a[..., :3] = np.where(line[..., None], mix(a[..., :3], (96, 116, 150), 0.55), a[..., :3])
        return shade_edges(a, 0.3, width=max(1, min(w, h) // 4))
    return tt.painter(key, fn)


def straw_painter(key, plate=True):
    """Straw: a mat of crossing stalks, some light, some dark, with the odd bright stalk standing out."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, STRAW_D, rng, 0.06)
        ys, xs = np.mgrid[0:h, 0:w]
        n = max(6, (w * h) // 6)
        for _ in range(n):
            cx, cy = rng.uniform(0, w), rng.uniform(0, h)
            ang = rng.uniform(0, math.pi)
            ln = rng.uniform(2.5, 6.0)
            d = np.abs((xs + 0.5 - cx) * math.sin(ang) - (ys + 0.5 - cy) * math.cos(ang))
            al = np.abs((xs + 0.5 - cx) * math.cos(ang) + (ys + 0.5 - cy) * math.sin(ang))
            on = (d < 0.55) & (al < ln)
            col = STRAW_L if rng.random() < 0.55 else STRAW
            a[..., :3] = np.where(on[..., None], np.array(col, float) * (1 + rng.normal(0, 0.04)), a[..., :3])
        return shade_edges(a, 0.2, width=max(1, min(w, h) // 5))
    return tt.painter(key, fn)


# ================================================================== wool (the blanket: neutral, tinted by the owner's colour)

def _twill(a, w, h, rng, k=0.035):
    ys, xs = np.mgrid[0:h, 0:w]
    tw = (((xs + ys) % 3) == 0)
    a[..., :3] *= np.where(tw, 1 - k, 1 + k * 0.4)[..., None]
    a[..., :3] *= 1 + rng.normal(0, 0.012, (h, w, 1))
    return a


def blanket_pattern(c, v, wdt=1.0):
    """The blanket's stripes as a function of [c], 0..1 across the animal's length (front to back, mirrored so the pattern
    reads the same from either end), returning a brightness factor and a mask for the lighter weft bands."""
    e = np.minimum(c, 1 - c)                       # 0 at either end, 0.5 in the middle
    k = np.ones_like(e)
    light = np.zeros_like(e, bool)
    k = np.where(e < 0.060, 0.60, k)               # the broad dark border at each end
    light |= (e >= 0.060) & (e < 0.085)
    k = np.where((e >= 0.085) & (e < 0.105), 0.76, k)
    k = np.where((e >= 0.105) & (e < 0.13), 1.0, k)
    k = np.where((e >= 0.20) & (e < 0.225), 0.78, k)    # a thin dark band
    light |= (e >= 0.225) & (e < 0.25)
    k = np.where((e >= 0.30) & (e < 0.33), 0.78, k)
    return k, light


def wool_painter(key, part='top'):
    """The blanket's cloth, neutral so that the saved colour (the model's tint) colours it: a fine twill, a broad dark border
    and thin bands at each end, a field of diamonds between them. [part] 'top' (over the back), 'flap' (hanging at the
    side) or 'edge' (the slab's thin ends)."""
    def fn(face, w, h, rng):
        a = solid(w, h, WOOL)
        U, V = uv(w, h)
        if part == 'top' and face in ('top', 'bottom'):
            along_len = V                           # along z
            across = U                              # along x
        elif part == 'flap' and face in ('left', 'right'):
            along_len = U
            across = V
        else:
            a[..., :3] = np.array(WOOL_D, float) * 1.05
            return _twill(shade_edges(a, 0.18), w, h, rng)
        k, light = blanket_pattern(along_len, across)
        a[..., :3] *= k[..., None]
        a[..., :3] = np.where(light[..., None], np.array(WOOL_L, float), a[..., :3])
        # the diamond field between the inner bands
        e = np.minimum(along_len, 1 - along_len)
        field = (e > 0.34)
        dx = np.abs(((across * (5.0 if part == 'top' else 2.0)) % 1.0) - 0.5)
        dz = np.abs(((along_len * 7.0) % 1.0) - 0.5)
        dia = (dx + dz) < 0.26
        a[..., :3] = np.where((field & dia)[..., None], a[..., :3] * 0.8, a[..., :3])
        if part == 'flap':
            # the hem: a dark band, a light line above it, and the tassels' knotted heading
            hem = across > 0.86
            a[..., :3] = np.where(hem[..., None], np.array(WOOL_D, float) * 0.95, a[..., :3])
            line = (across > 0.80) & (across <= 0.86)
            a[..., :3] = np.where(line[..., None], np.array(WOOL_L, float), a[..., :3])
        return shade_edges(_twill(a, w, h, rng), 0.16, width=max(1, min(w, h) // 8))
    return tt.painter(key, fn)


def tassel_painter(key):
    """A tassel: a knotted heading with a binding cord, strands hanging below it in neutral wool (it takes the blanket's
    colour), ragged at the bottom."""
    def fn(face, w, h, rng):
        a = solid(w, h, WOOL)
        U, V = uv(w, h)
        if face in ('top', 'bottom'):
            a[..., :3] = np.array(WOOL_D, float)
            return a
        strands = np.sin(U * w * math.pi * 1.6 + rng.uniform(0, 6)) * 0.5 + 0.5
        a[..., :3] *= (0.78 + 0.22 * strands)[..., None]
        head = V < 0.26
        a[..., :3] = np.where(head[..., None], np.array(WOOL_D, float) * 1.1, a[..., :3])
        cord = (V >= 0.26) & (V < 0.36)
        a[..., :3] = np.where(cord[..., None], np.array((150, 120, 84), float), a[..., :3])
        # ragged ends
        if h >= 4:
            cut = (rng.random(w) * max(1, h // 5)).astype(int)
            for i in range(w):
                if cut[i]:
                    a[h - cut[i]:, i] = 0
        return a
    return tt.painter(key, fn, light=True)


# ================================================================== wood and clay

def wood_painter(key, axis='z', base=OAK, dark=OAK_D, light=OAK_L, knots=0):
    return tt.wood_painter(key, base, dark, light, axis, knots=knots)


def pot_painter(key, part='body', base=CLAY, dark=CLAY_D, light=CLAY_L, glaze=False):
    """A clay pot: terracotta with fine throwing rings, a darker rim, a glaze run down the side when [glaze]; [part]
    'body', 'neck' or 'rim'."""
    def fn(face, w, h, rng):
        a = noise_fill(w, h, base, rng, 0.05)
        U, V = uv(w, h)
        if face == 'top':
            # the open mouth seen from above: a dark well in a clay rim
            well = np.clip(1 - np.hypot(U - 0.5, V - 0.5) * 3, 0, 1)[..., None]
            a[..., :3] = mix(np.array(dark, float), np.array((60, 40, 26), float), well)
            return a
        if face == 'bottom':
            a[..., :3] *= 0.8
            return a
        rings = np.sin(V * h * 2.2) * 0.04
        a[..., :3] *= (1 + rings)[..., None]
        lit = np.exp(-((U - 0.28) / 0.16) ** 2)
        a[..., :3] = mix(a[..., :3], light, (lit * 0.45)[..., None])
        a[..., :3] *= (0.8 + 0.2 * (1 - np.abs(U - 0.5) * 1.6).clip(0, 1))[..., None]
        if part == 'rim':
            a[..., :3] = mix(a[..., :3], dark, 0.35)
        if glaze:
            run = (V < 0.45 + 0.25 * np.sin(U * 9 + rng.uniform(0, 6))) & (V > 0.05)
            a[..., :3] = np.where(run[..., None], mix(a[..., :3], tt.GLAZE, 0.8), a[..., :3])
            a[..., :3] = np.where((run & (lit > 0.5))[..., None], mix(a[..., :3], tt.GLAZE_L, 0.5), a[..., :3])
        return shade_edges(a, 0.22, width=max(1, min(w, h) // 5))
    return tt.painter(key, fn)


def lantern_glass_painter(key):
    return tt.lantern_glass_painter(key)


def lantern_iron_painter(key):
    return tt.lantern_iron_painter(key)
