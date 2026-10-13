"""The dragon: one of the Unbowed, the fire-drakes of the high peaks that never knelt to the Hollow King. A huge, scarred
four-legged wyrm with two great wings: ember-crimson and charcoal scales in overlapping rows, segmented dusky bronze plates
down its throat, chest and belly, ivory horns that fade to black, obsidian spines, wine-red scalloped wing membranes
threaded with veins, molten-gold eyes and, in the cracks between its plates, seams of fire that the glow maps light up.

Built with modelkit like the Dusk Rider: parts nest (neck, head, jaw; hips, tail; four legs; two wings of three
segments each), so the game poses it with the rig animator from clips keyed on these names (art/scripts/dragon_anims.py).
Minecraft model space: pixels, y down, the creature faces -z, its right side is -x, feet on the ground at y = 24. The game
draws it at twice its size.

The texture is HD: the model declares 256x256 and the PNG is painted at twice that (the UVs are normalised), every face at
2x detail. Nothing is painted from character grids here: each texel of each face is turned back into its place on the
model (the cube's own frame), and the paint is a function of that place, so scales, plates, rings and veins run on over
the edges of a box and across both faces of a wing membrane. The membranes' shapes (the scalloped trailing edge, the tears)
are cut out of the texture's alpha.

Glow works from the same painting: every seam, the eyes and the mouth are painted into named glow layers, and each glow map
is an assembly of them: dragon_glow (eyes only), dragon_glow_breath (eyes, mouth and throat, the chest and throat seams) and
dragon_glow2 (enraged: brighter eyes, every molten seam on the body, neck, tail and wing bones, the mouth and throat).

Writes the texture, the three glow maps, the Kotlin body, the part list, a .bbmodel and preview sheets:

    python3 -B art/scripts/dragon.py src/main/resources/assets/guildmark <preview-dir> art/models
"""
import sys, os, json, math, zlib, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image, ImageDraw
from modelkit import Cube, Part, Model
from animkit import qeuler, qmat, qfrommat, frame_to, unit, qmul

TEX_W, TEX_H = 256, 256      # what the model declares
HD = 2                       # the PNG is painted at twice that

# ================================================================== rig math

def krad(deg):
    """Degrees as the Kotlin body prints them (radians to 3 places), so the game and these scripts agree."""
    return math.degrees(round(math.radians(deg), 3))


def krot(rot):
    return tuple(krad(a) for a in rot)


def kpos(p):
    return tuple(round(float(v), 3) for v in p)


def rotm(rot):
    """A part's rotation (degrees about x, then y, then z) as a matrix: Rz Ry Rx, as the game builds it."""
    return qmat(qeuler(*rot))


def euler_of(m):
    """Degrees (x, y, z) with m = Rz(z) Ry(y) Rx(x)."""
    y = math.asin(max(-1.0, min(1.0, -m[2, 0])))
    x = math.atan2(m[2, 1], m[2, 2])
    z = math.atan2(m[1, 0], m[0, 0])
    return tuple(math.degrees(a) for a in (x, y, z))


def mframe(a_from, b_from, a_to, b_to):
    return qmat(frame_to(np.asarray(a_from, float), np.asarray(b_from, float), np.asarray(a_to, float), np.asarray(b_to, float)))


Y = np.array([0.0, 1.0, 0.0])
X = np.array([1.0, 0.0, 0.0])


def two_bone(H, A, L1, L2, pole, side_hint):
    """Hip H to ankle A through a knee bent toward [pole]: world rotations of the upper and lower segments (each
    hangs along its own +y, its +x square to the leg's plane on the [side_hint] side), the knee, the ankle reached."""
    H = np.asarray(H, float); A = np.asarray(A, float)
    d = A - H
    D = float(np.linalg.norm(d))
    e1 = d / D
    D = min(max(D, abs(L1 - L2) + 1e-3), (L1 + L2) * 0.9995)
    p = np.asarray(pole, float) - np.dot(pole, e1) * e1
    e2 = p / np.linalg.norm(p)
    a = (L1 * L1 - L2 * L2 + D * D) / (2 * D)
    h = math.sqrt(max(0.0, L1 * L1 - a * a))
    K = H + e1 * a + e2 * h
    A2 = H + e1 * D
    side = np.cross(e1, e2)
    if np.dot(side, side_hint) < 0:
        side = -side
    R1 = mframe(Y, X, unit(K - H), side)
    R2 = mframe(Y, X, unit(A2 - K), side)
    return R1, R2, K, A2


# ================================================================== colour and noise toolkit

def ramp(stops, t):
    """Colours along [stops] (dark to light, evenly spaced) at [t] in 0..1: an array of any shape in, (..., 3) out."""
    s = np.asarray(stops, float)
    n = len(s) - 1
    x = np.clip(t, 0.0, 1.0) * n
    i = np.minimum(np.floor(x).astype(np.int64), n - 1)
    f = (x - i)[..., None]
    return s[i] * (1.0 - f) + s[i + 1] * f


def sstep(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def hash01(ix, iy=0, iz=0, seed=0):
    """A repeatable pseudo-random number in [0, 1) for each integer lattice point."""
    ix = np.asarray(ix, np.int64); iy = np.asarray(iy, np.int64); iz = np.asarray(iz, np.int64)
    h = (ix * 73856093) ^ (iy * 19349663) ^ (iz * 83492791) ^ ((int(seed) * 2654435761) & 0xFFFFFFFF)
    h = h & 0xFFFFFFFF
    h ^= h >> 15
    h = (h * 2246822519) & 0xFFFFFFFF
    h ^= h >> 13
    h = (h * 3266489917) & 0xFFFFFFFF
    h ^= h >> 16
    return h / 4294967296.0


def vnoise(x, y, z, seed=0):
    """Smooth value noise in 0..1 at lattice coordinates (one random value per integer point, eased between them)."""
    x0 = np.floor(x); y0 = np.floor(y); z0 = np.floor(z)
    fx = sstep(0, 1, x - x0); fy = sstep(0, 1, y - y0); fz = sstep(0, 1, z - z0)
    x0 = x0.astype(np.int64); y0 = y0.astype(np.int64); z0 = z0.astype(np.int64)
    out = 0.0
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                w = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + w * hash01(x0 + dx, y0 + dy, z0 + dz, seed)
    return out


def fbm(P, cell, seed=0, octaves=3):
    """Fractal noise in 0..1 over model-space points [P] (..., 3): features about [cell] model px across."""
    tot = 0.0; amp = 1.0; norm = 0.0; k = 1.0 / cell
    for o in range(octaves):
        tot = tot + amp * vnoise(P[..., 0] * k + 17.3 * o, P[..., 1] * k + 5.1 * o, P[..., 2] * k + 11.7 * o, seed + 31 * o)
        norm += amp; amp *= 0.5; k *= 2.0
    return tot / norm


def seg_dist(px, pz, a, b):
    """Distance from points (px, pz) to the segment a-b (2D), and how far along it they are (0..1)."""
    ab = np.asarray(b, float) - np.asarray(a, float)
    L2 = float(ab @ ab) + 1e-12
    t = np.clip(((px - a[0]) * ab[0] + (pz - a[1]) * ab[1]) / L2, 0.0, 1.0)
    return np.hypot(px - (a[0] + t * ab[0]), pz - (a[1] + t * ab[1])), t


def mixc(a, b, t):
    t = np.asarray(t)[..., None] if np.ndim(t) else t
    return a * (1 - t) + b * t


def seed_of(*keys):
    return zlib.crc32('/'.join(str(k) for k in keys).encode())


# Colour ramps, dark to light. The tone t (0..1) of a material picks along its ramp.
RAMP = {
    'crimson': [(18, 4, 9), (46, 7, 15), (84, 11, 22), (122, 18, 28), (160, 28, 34), (198, 46, 42), (232, 88, 58), (252, 144, 98)],
    'char': [(8, 7, 9), (18, 15, 18), (30, 26, 30), (46, 40, 42), (66, 58, 58), (92, 82, 78), (128, 112, 104), (170, 150, 140)],
    'bronze': [(36, 20, 12), (68, 40, 22), (104, 66, 34), (142, 96, 48), (180, 130, 66), (214, 166, 96), (240, 200, 136), (255, 230, 180)],
    'ivory': [(52, 44, 36), (108, 94, 76), (166, 150, 122), (206, 192, 160), (232, 222, 192), (246, 240, 218), (255, 252, 238), (255, 255, 250)],
    'obsidian': [(3, 3, 6), (9, 9, 14), (18, 18, 26), (32, 31, 42), (52, 50, 66), (84, 82, 104), (128, 126, 152), (196, 194, 220)],
    'wine': [(30, 5, 12), (58, 9, 20), (92, 16, 28), (128, 30, 40), (160, 50, 54), (192, 80, 72), (224, 124, 98), (248, 176, 140)],
    'gold': [(96, 48, 6), (160, 90, 10), (218, 140, 22), (250, 184, 40), (255, 214, 88), (255, 236, 150), (255, 248, 206), (255, 255, 244)],
    'mouth': [(20, 3, 6), (44, 6, 12), (76, 10, 16), (112, 20, 22), (150, 34, 32), (186, 58, 46), (220, 94, 70), (244, 140, 108)],
    'seam': [(24, 5, 4), (46, 10, 6), (70, 18, 10), (98, 28, 14), (130, 40, 18), (170, 58, 22), (214, 88, 30), (250, 140, 50)],
}
RAMP = {k: np.array(v, float) for k, v in RAMP.items()}

# Glow layers (every one is painted as an intensity 0..1 per texel); which maps carry them and in what light
GLOW_LAYERS = ['eye', 'mouth', 'throat', 'chest', 'body', 'neck', 'tail', 'wing']
GLOW_MAPS = {
    # map name: {layer: (colour, gain)}
    'glow': {'eye': ((255, 170, 40), 1.0)},
    'glow_breath': {'eye': ((255, 176, 46), 1.0), 'mouth': ((255, 86, 22), 1.0), 'throat': ((255, 112, 28), 1.0),
                    'chest': ((255, 104, 24), 1.0)},
    'glow2': {'eye': ((255, 238, 168), 1.0), 'mouth': ((255, 92, 24), 1.0), 'throat': ((255, 120, 30), 1.0),
              'chest': ((255, 112, 26), 1.0), 'body': ((255, 100, 22), 1.0), 'neck': ((255, 104, 24), 1.0),
              'tail': ((255, 100, 22), 1.0), 'wing': ((255, 112, 28), 1.0)},
}

# ================================================================== one face being painted

class Face:
    """One cube face on the shipped texture. Every texel is turned back into its place on the model: [P] is where each texel's
    centre lies, in the cube's part frame (model px), [n] the face's outward normal, [part] and [wt] the part's name and
    the world transforms of the rest pose (for paint that needs the creature's own layout, like the wings)."""

    def __init__(self, part, cube, face, rect, A, B, D, n, wt):
        x0, y0, w, h = rect
        self.part, self.cube, self.name, self.face = part, cube, cube.name, face
        self.rect, self.w, self.h = rect, w, h
        s = (np.arange(w) + 0.5) / w
        t = (np.arange(h) + 0.5) / h
        self.P = A + s[None, :, None] * (B - A) + t[:, None, None] * (D - A)
        self.n = np.asarray(n, float)
        self.wt = wt
        self.rng = np.random.default_rng(seed_of(cube.name, face))
        self.o = np.array(cube.origin, float)
        self.sz = np.array(cube.size, float)

    @property
    def X(self): return self.P[..., 0]

    @property
    def Y(self): return self.P[..., 1]

    @property
    def Z(self): return self.P[..., 2]

    def world(self):
        """The texel centres in the rest pose's world (model) space."""
        R, T = self.wt[self.part]
        return T + self.P @ R.T


class Out:
    """What a shader hands back: colour (h, w, 3), alpha (h, w) 0..1 and glow layers {name: intensity (h, w)}."""
    def __init__(self, rgb, alpha=None, glow=None):
        self.rgb = rgb
        self.alpha = np.ones(rgb.shape[:2]) if alpha is None else alpha
        self.glow = glow or {}


AXIS = {'x': 0, 'y': 1, 'z': 2}
CROSS = {'z': (0, 1), 'y': (0, 2), 'x': (2, 1)}     # the two axes round a box's long one: p (across), q (down)


def unroll(f, axis, rev=False):
    """Where a texel lies on a box's skin as (a, c, cc, L, cap): [a] along the long axis (from the end that [rev] picks),
    [c] round the cross-section from the middle of its q-min face (the top of a body, the front of a leg) counted in px
    along the surface and signed (so it runs on over the edges), [cc] 0 at that midline to 1 at the opposite one, [L] the
    perimeter, [cap] whether the face is an end of the box (c and cc then only follow its own plane)."""
    ax = AXIS[axis]
    pi, qi = CROSS[axis]
    o, sz, P, n = f.o, f.sz, f.P, f.n
    a_raw = P[..., ax] - o[ax]
    a = sz[ax] - a_raw if rev else a_raw
    hp = sz[pi] / 2
    pc = o[pi] + hp
    sq = sz[qi]
    L = 4 * hp + 2 * sq
    pp = P[..., pi] - pc
    qq = P[..., qi] - o[qi]
    if abs(n[ax]) > 0.5:
        return qq, pp, np.full(pp.shape, 0.5), L, True
    if abs(n[qi]) > 0.5:
        if n[qi] < 0:
            c = pp
        else:
            c = np.where(pp >= 0, 1.0, -1.0) * (L / 2 - np.abs(pp))
    else:
        c = hp + qq if n[pi] > 0 else -(hp + qq)
    return a, c, np.abs(c) / (L / 2), L, False


# ---- scales, plates

def fish(a, c, cw, hs, ra, nar, seed):
    """Overlapping rounded scales, each lying over the next row (its free edge toward +a). [a] runs along the rows' axis,
    [c] across them (period nar * cw), [cw] a scale's width, [hs] the rows' spacing, [ra] a scale's half-length. Every texel
    belongs to the scale of the earliest row that covers it. Returns the owner's row, column, texel offset from its centre
    (dx, dy, in its own ellipse's units: the rim is at 1), its ellipse radius e, the distance beyond the rim of the nearest
    scale lying over it (ds, in the same units, 9 when none), and a random number per scale."""
    R = cw * 0.5 * 1.07
    rows = np.floor(a / hs).astype(np.int64)
    cand = []
    for dr in (-2, -1, 0, 1):
        r = rows + dr
        off = (r % 2) * (cw / 2)
        k = np.round((c - off) / cw).astype(np.int64)
        dx = (c - (k * cw + off)) / R
        dy = (a - (r + 0.5) * hs) / ra
        cand.append((r, k, dx, dy, np.hypot(dx, dy)))
    R_ = np.stack([cd[0] for cd in cand]); K_ = np.stack([cd[1] for cd in cand])
    DX = np.stack([cd[2] for cd in cand]); DY = np.stack([cd[3] for cd in cand]); E_ = np.stack([cd[4] for cd in cand])
    # the covering candidate of the earliest row; failing that (it cannot happen at these radii) the nearest
    key = np.where(E_ <= 1.0, R_, 10 ** 6 + np.arange(len(cand))[:, None, None] * 0 + (E_ * 1000).astype(np.int64))
    j = np.argmin(key, axis=0)[None]
    pick = lambda arr: np.take_along_axis(arr, j, 0)[0]
    best_r, k_o, dx_o, dy_o, e_o = pick(R_), pick(K_), pick(DX), pick(DY), pick(E_)
    over = R_ < best_r[None]
    ds = np.where(over, E_ - 1.0, 9.0).min(axis=0)
    rnd = hash01(np.mod(k_o, nar), best_r, 0, seed)
    return best_r, np.mod(k_o, nar), dx_o, dy_o, e_o, np.maximum(ds, 0.0), rnd


def plates(a, v, ph, bend, vw, seed, gw=0.5):
    """Bands across a surface, each lying over the next (free edge toward +a), bowed so the middle (v = 0, the midline)
    trails behind the sides. [v] is the distance from the midline in px, [vw] the half-width of the strip they cover.
    Returns the band index, the position in the band (0 at the top edge to 1 at the free edge), the gap (1 inside the
    narrow line between bands, the seam), and a random number per band."""
    u = np.clip(v / vw, 0.0, 1.0)
    ae = a - bend * (1.0 - u * u)
    q = ae / ph
    idx = np.floor(q).astype(np.int64)
    fy = q - idx
    gap = (fy > 1.0 - gw / ph).astype(float)
    return idx, fy, gap, hash01(idx, 0, 0, seed)


# ================================================================== shaders

def zone_color(cc, t, zones):
    """Colour at tone [t] where each ramp has its say by [cc] (0 at the midline of the skin's q-min side, 1 opposite):
    zones is [(cc, ramp name, tone offset)], blended linearly between neighbours."""
    cs = np.array([z[0] for z in zones], float)
    idx = np.clip(np.searchsorted(cs, cc, side='right') - 1, 0, len(zones) - 2)
    f = np.clip((cc - cs[idx]) / (cs[idx + 1] - cs[idx] + 1e-9), 0.0, 1.0)
    out = 0.0
    for i, (_, name, off) in enumerate(zones):
        w = np.where(idx == i, 1 - f, 0.0) + np.where(idx == i - 1, f, 0.0)
        if w.any():
            out = out + ramp(RAMP[name], t + off) * w[..., None]
    return out


def halo(m, k=1, gain=0.4):
    """A soft glow round an intensity layer [m] (h, w): its neighbours within [k] texels take [gain] of it."""
    h, w = m.shape
    pad = np.pad(m, k, mode='constant')
    out = np.zeros_like(m)
    for dy in range(-k, k + 1):
        for dx in range(-k, k + 1):
            if dx or dy:
                out = np.maximum(out, pad[k + dy:k + dy + h, k + dx:k + dx + w] * gain / (1 + 0.5 * (abs(dx) + abs(dy) - 1)))
    return np.maximum(m, out)


class Plain:
    """A flat material with a little grain: the stand-in for anything not yet given its own painting."""
    def __init__(self, ramp_name='crimson', t=0.5, noise=0.08, cell=3.0):
        self.ramp, self.t, self.noise, self.cell = ramp_name, t, noise, cell

    def paint(self, f):
        n = fbm(f.P, self.cell, seed_of(f.name))
        return Out(ramp(RAMP[self.ramp], self.t + (n - 0.5) * 2 * self.noise))


ZONES_BODY = [(0.0, 'char', 0.0), (0.07, 'char', 0.04), (0.17, 'crimson', -0.2), (0.34, 'crimson', -0.08), (0.58, 'crimson', 0.0),
              (0.72, 'crimson', 0.12), (0.84, 'bronze', -0.02), (1.0, 'bronze', 0.0)]


def worley(x, z, cell, seed):
    """Worley noise: F2 - F1 (in cells) is small along the borders of random cells."""
    gx = np.floor(x / cell).astype(np.int64); gz = np.floor(z / cell).astype(np.int64)
    f1 = np.full(x.shape, 9.0); f2 = np.full(x.shape, 9.0)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            cx, cz = gx + dx, gz + dz
            px = (cx + hash01(cx, cz, 1, seed)) * cell
            pz = (cz + hash01(cx, cz, 2, seed)) * cell
            d = np.hypot(x - px, z - pz) / cell
            f2 = np.where(d < f1, f1, np.minimum(f2, d))
            f1 = np.minimum(f1, d)
    return f1, f2


def scratches(a, c, seed, n_groups, area):
    """Claw marks: groups of thin parallel strokes, a dark furrow with a pale lip beside it. Returns (furrow, lip)."""
    rng = np.random.default_rng(seed)
    furrow = np.zeros(a.shape); lip = np.zeros(a.shape)
    amin, amax, cmin, cmax = float(a.min()), float(a.max()), float(c.min()), float(c.max())
    for _ in range(n_groups):
        if rng.random() > 0.40:
            continue
        ca = rng.uniform(amin, amax); cc_ = rng.uniform(cmin, cmax)
        ang = rng.uniform(0, math.pi)
        ln = rng.uniform(3.5, 7.5)
        d = np.array([math.cos(ang), math.sin(ang)]); nrm = np.array([-d[1], d[0]])
        for k in range(rng.integers(2, 4)):
            off = (k - 1) * rng.uniform(0.9, 1.3)
            a0 = ca + nrm[0] * off; c0 = cc_ + nrm[1] * off
            sh = rng.uniform(-1.2, 1.2)
            A = np.array([a0 - d[0] * (ln / 2 + sh), c0 - d[1] * (ln / 2 + sh)])
            B = np.array([a0 + d[0] * (ln / 2 - sh), c0 + d[1] * (ln / 2 - sh)])
            dist, t = seg_dist(a, c, A, B)
            wd = 0.22 * np.sin(np.pi * np.clip(t, 0.02, 0.98)) ** 0.6
            furrow = np.maximum(furrow, (dist < wd).astype(float))
            side = ((a - A[0]) * d[1] - (c - A[1]) * d[0])
            lip = np.maximum(lip, ((dist > wd) & (dist < wd + 0.34) & (side < 0)).astype(float) * (np.sin(np.pi * np.clip(t, 0, 1)) > 0.25))
    return furrow, lip * (1 - furrow)


class Hide:
    """Scaled hide on a box: rows of overlapping scales (free edges toward the tail, or down a leg), a strip of charcoal
    plates down the middle of the back, bands of bronze plates down the belly, ramps of colour from the back round to the
    belly, the creases between segments dark, claw marks and cracks of fire, and the seams between the plates ready to glow."""

    def __init__(self, axis='z', rev=False, zones=ZONES_BODY, cw=3.6, hs=2.0, ra=2.7, belly=0.80, back=0.08, ph=2.5, bend=1.0,
                 seam='body', seam_back=None, tone=0.0, ao=1.0, var=1.0, wear=1.0, contrast=0.8, cracks=0.0, scars=1.0,
                 crack_layer=None, bevel=1.0):
        self.axis, self.rev, self.zones, self.cw, self.hs, self.ra = axis, rev, zones, cw, hs, ra
        self.belly, self.back, self.ph, self.bend = belly, back, ph, bend
        self.seam, self.seam_back = seam, seam_back or seam
        self.tone, self.ao, self.var, self.wear, self.contrast = tone, ao, var, wear, contrast
        self.cracks, self.scars, self.bevel = cracks, scars, bevel
        self.crack_layer = crack_layer or seam or 'body'

    def paint(self, f):
        a, c, cc, L, cap = unroll(f, self.axis, self.rev)
        ax = AXIS[self.axis]
        seed = seed_of(f.name)
        K = self.contrast
        nar = max(4, int(round(L / self.cw)))
        r, k, dx, dy, e, ds, rnd = fish(a, c, L / nar if not cap else self.cw, self.hs, self.ra, nar if not cap else 10 ** 5, seed)
        # --- the scales
        rim = sstep(0.60, 0.97, e) * sstep(0.0, 0.5, dy)
        outline = sstep(0.78, 1.0, e) * (1 - sstep(0.0, 0.5, dy))
        shadow = np.exp(-ds / 0.22)
        t = 0.46 + self.tone + (rnd - 0.5) * 0.16 * self.var + 0.08 * np.clip(dy, -1, 1) + 0.20 * K * rim - 0.26 * K * shadow - 0.13 * K * outline
        t = t + (fbm(f.P, 7.0, seed + 5) - 0.5) * 0.10 * self.wear
        # --- the strip of plates down the back and the bands down the belly
        v_back = cc * L / 2
        v_belly = (1 - cc) * L / 2
        glow = np.zeros(cc.shape)
        gold = np.zeros(cc.shape)
        plated = np.zeros(cc.shape, bool)
        if self.belly < 1.0 and not cap:
            vw = (1 - self.belly) * L / 2
            idx, fy, gap, prnd = plates(a, v_belly, self.ph, self.bend, vw, seed + 11)
            on = cc >= self.belly
            tp = 0.52 + (prnd - 0.5) * 0.16 + 0.30 * (1 - sstep(0.0, 0.22, fy)) - 0.30 * sstep(0.70, 0.98, fy) - 0.08 * fy
            t = np.where(on, tp, t)
            glow = np.where(on, gap, glow)
            t = np.where(on & (gap > 0), -0.9, t)
            line = np.abs(cc - self.belly) * L / 2 < 0.3
            t = np.where(line, t - 0.3, t)
            plated |= on
        if self.back > 0 and not cap:
            vw = self.back * L / 2
            idx, fy, gap, prnd = plates(a, v_back, self.ph + 0.5, self.bend * 0.6, vw, seed + 13)
            on = cc <= self.back
            tp = 0.46 + (prnd - 0.5) * 0.14 + 0.34 * (1 - sstep(0.0, 0.2, fy)) - 0.26 * sstep(0.72, 0.98, fy)
            t = np.where(on, tp, t)
            gb = np.where(on, gap, 0.0)
            t = np.where(on & (gap > 0), -0.9, t)
            gold = gb
            plated |= on
        rgb = zone_color(cc, t, self.zones)
        seamc = ramp(RAMP['seam'], np.full(cc.shape, 0.30))
        rgb = np.where(((glow > 0) | (gold > 0))[..., None] & (t < -0.5)[..., None], seamc, rgb)
        glow_c = np.zeros(cc.shape)
        # --- claw marks and cracks of fire, on the scales only
        scaled = ~plated
        if self.scars > 0 and not cap and f.w * f.h > 120:
            furrow, lip = scratches(a, c, seed + 77, 2 if f.w * f.h > 400 else 1, f.w * f.h)
            furrow = furrow * scaled * self.scars; lip = lip * scaled * self.scars
            rgb = np.where(lip[..., None] > 0, rgb * 1.0 + np.array([62.0, 34.0, 26.0]), rgb)
            rgb = np.where(furrow[..., None] > 0, ramp(RAMP['crimson'], np.full(cc.shape, 0.14)), rgb)
        if self.cracks > 0 and not cap:
            f1, f2 = worley(a, c, 6.5, seed + 3)
            line = 1 - sstep(0.035, 0.11, f2 - f1)
            patch = sstep(0.50 - 0.3 * self.cracks, 0.62 - 0.3 * self.cracks, vnoise(a / 11.0, c / 11.0, np.full(a.shape, 3.0), seed + 9))
            crack = line * patch * scaled
            core = ramp(RAMP['seam'], np.full(cc.shape, 0.34))
            rgb = mixc(rgb, core, np.clip(crack * 1.2, 0, 1))
            glow_c = crack
        # --- bevel: the edges of the box catch a little light
        if self.bevel > 0:
            x0, y0, w, h = f.rect
            ex = np.minimum(np.arange(w) + 0.5, w - np.arange(w) - 0.5)[None, :] / HD
            ey = np.minimum(np.arange(h) + 0.5, h - np.arange(h) - 0.5)[:, None] / HD
            ed = np.minimum(ex, ey)
            up = np.clip(-f.n[1], -1, 1)
            lift = (ed < 0.34) * (0.05 + 0.04 * up) * self.bevel
            rgb = rgb * (1 + lift[..., None])
        # --- the creases at the ends of the box
        end = (1 - sstep(0.0, 1.6, f.P[..., ax] - f.o[ax])) + (1 - sstep(0.0, 1.6, f.o[ax] + f.sz[ax] - f.P[..., ax]))
        rgb = rgb * (1 - 0.34 * self.ao * np.clip(end, 0, 1))[..., None]
        out = Out(rgb)
        if self.seam:
            out.glow[self.seam] = halo(glow * 1.0) if glow.any() else glow
        if self.seam_back and gold.any():
            out.glow[self.seam_back] = np.maximum(out.glow.get(self.seam_back, 0), halo(gold))
        if glow_c.any():
            out.glow[self.crack_layer] = np.maximum(out.glow.get(self.crack_layer, 0), halo(glow_c, 1, 0.35))
        return out


class Thorn:
    """A cut-out silhouette for a flat card: a profile in the (len, side) plane, extruded through the card's thin axis, applied
    to every face of the box at once (so the sides take the shape, the thin faces keep or lose their edge). [prof] maps the
    fraction along the length (0 base, 1 tip) to the span of the shape across it as (lo, hi) fractions of the card's side
    extent."""

    def __init__(self, len_axis, tip_sign, side_axis, prof):
        self.la, self.ts, self.sa, self.prof = AXIS[len_axis], tip_sign, AXIS[side_axis], prof

    def keep(self, f):
        o, sz, P = f.o, f.sz, f.P
        u = (P[..., self.la] - o[self.la]) / max(sz[self.la], 1e-6)
        u = u if self.ts > 0 else 1 - u
        side = (P[..., self.sa] - o[self.sa]) / max(sz[self.sa], 1e-6)
        lo, hi = self.prof(np.clip(u, 0, 1))
        return (side >= lo - 1e-6) & (side <= hi + 1e-6), u, side


class Needle(Thorn):
    """A spike that narrows to a point on every side: each face keeps a band about the axis that narrows with the length, so
    seen from any side it is a thorn. [power] bends the taper (1 is straight)."""

    def __init__(self, len_axis='z', tip_sign=1, power=1.0):
        self.la, self.ts, self.power = AXIS[len_axis], tip_sign, power

    def keep(self, f):
        o, sz, P, n = f.o, f.sz, f.P, f.n
        u = (P[..., self.la] - o[self.la]) / max(sz[self.la], 1e-6)
        u = np.clip(u if self.ts > 0 else 1 - u, 0, 1)
        nax = int(np.argmax(np.abs(n)))
        if nax == self.la:                                   # the base end shows, the tip end is gone
            return np.full(u.shape, bool(u.mean() < 0.5)), u, np.full(u.shape, 0.5)
        wax = [i for i in range(3) if i not in (self.la, nax)][0]
        hw = sz[wax] / 2
        w = (P[..., wax] - (o[wax] + hw)) / hw               # -1 .. 1 across the face
        return np.abs(w) <= 1.0 - u ** self.power + 1e-6, u, 0.5 + 0.5 * w


def spine_prof(power=0.7):
    return lambda u: (np.zeros_like(u), 1 - u ** power)


class Obsidian:
    """Black volcanic glass: spines, blades and claws. [thorn] cuts its silhouette, [gloss] sets how bright its glints are,
    [ridge] a bright line down the middle of a blade, [ember] the glow layer of a molten line round the foot of a spine."""

    def __init__(self, thorn=None, gloss=1.0, tone=0.0, tip_glint=True, ridge=False, ember=None):
        self.thorn, self.gloss, self.tone, self.tip, self.ridge, self.ember = thorn, gloss, tone, tip_glint, ridge, ember

    def paint(self, f):
        seed = seed_of(f.name)
        alpha = np.ones(f.P.shape[:2])
        u = np.zeros(alpha.shape); side = np.zeros(alpha.shape)
        if self.thorn is not None:
            keep, u, side = self.thorn.keep(f)
            alpha = keep.astype(float)
        n = fbm(f.P, 2.2, seed)
        # a glassy sheen: a pale streak slanting across the faces, bright along the leading edge, dark where it meets the body
        streak = np.exp(-((side - (0.18 + 0.5 * u)) / 0.07) ** 2)
        lead = np.exp(-(side / 0.09) ** 2) * (u > 0.12)
        t = 0.27 + self.tone + (n - 0.5) * 0.14 + 0.34 * self.gloss * (streak * 0.7 + lead * 0.9) * (u < 0.98) + 0.16 * u * self.gloss
        t = t - 0.14 * (1 - sstep(0.0, 0.2, u))
        if self.ridge:
            t = t + 0.34 * np.exp(-((side - 0.5) / 0.05) ** 2) * (u > 0.1) * (u < 0.96)
        # the silhouette catches the light
        a = alpha > 0.5
        pad = np.pad(a, 1, mode='edge')
        nb = pad[:-2, 1:-1] & pad[2:, 1:-1] & pad[1:-1, :-2] & pad[1:-1, 2:]
        edge = a & ~nb
        t = t + 0.24 * edge * self.gloss
        rgb = ramp(RAMP['obsidian'], np.clip(t, 0, 0.74))
        if self.tip and self.thorn is not None:
            rgb = rgb + (np.exp(-((1 - u) / 0.06) ** 2) * 26.0)[..., None] * np.array([0.8, 0.8, 1.0])
        out = Out(rgb, alpha)
        if self.ember and self.thorn is not None:
            band = (u > 0.07) & (u < 0.17) & a
            rgb = np.where(band[..., None], ramp(RAMP['seam'], np.full(u.shape, 0.42)), rgb)
            out.rgb = rgb
            out.glow[self.ember] = band.astype(float)
        return out


class Horn:
    """Ivory horn in growth rings, a faint spiral, streaked along its length, dirty at the root and burnt black toward the
    tip: [s0] to [s1] is how far along the whole horn (0 root, 1 tip) this segment runs."""

    def __init__(self, s0, s1, black=0.58, ring=1.6, axis='z'):
        self.s0, self.s1, self.black, self.ring, self.axis = s0, s1, black, ring, axis

    def paint(self, f):
        a, c, cc, L, cap = unroll(f, self.axis)
        seed = seed_of(f.name)
        ax = AXIS[self.axis]
        s = self.s0 + (self.s1 - self.s0) * np.clip((f.P[..., ax] - f.o[ax]) / f.sz[ax], 0, 1)
        sp = a + 0.45 * c
        g = (sp / self.ring) % 1.0
        groove = (1 - sstep(0.0, 0.17, np.minimum(g, 1 - g)))
        lit = sstep(0.55, 0.85, g) * (1 - sstep(0.85, 1.0, g))
        streak = vnoise(c * 1.7 + 4.0, a * 0.18, np.zeros_like(a), seed)
        speck = fbm(f.P, 1.3, seed + 3)
        t = 0.66 + (streak - 0.5) * 0.24 + (speck - 0.5) * 0.10 - 0.30 * groove + 0.10 * lit - 0.10 * (1 - s)
        t = t - 0.34 * (1 - sstep(0.0, 0.16, s)) - 0.12 * (1 - cc) * 0 + 0.08 * (cc < 0.15)
        b = sstep(self.black, self.black + 0.3, s)
        ivory = ramp(RAMP['ivory'], t)
        dark = ramp(RAMP['obsidian'], 0.22 + (streak - 0.5) * 0.14 + 0.18 * (1 - groove) * 0 + 0.1 * lit - 0.12 * groove)
        rgb = ivory * (1 - b[..., None]) + dark * b[..., None]
        # the root is skin, a ring of crimson scale round it
        skin = ramp(RAMP['crimson'], 0.28 + (streak - 0.5) * 0.2)
        root = (1 - sstep(0.0, 0.07, s))
        rgb = rgb * (1 - root[..., None]) + skin * root[..., None]
        return Out(rgb)


class Scute:
    """Charcoal armour plates: heavy slabs in rows with pale-ember edges where the light catches them. [row] is their
    length along the body, [cols] how many across."""

    def __init__(self, axis='z', row=3.0, cw=3.4, edge=1.0, tone=0.0, seam=None, bend=0.7):
        self.axis, self.row, self.cw, self.edge, self.tone, self.seam, self.bend = axis, row, cw, edge, tone, seam, bend

    def paint(self, f):
        a, c, cc, L, cap = unroll(f, self.axis)
        seed = seed_of(f.name)
        nar = max(2, int(round(L / self.cw)))
        r, k, dx, dy, e, ds, rnd = fish(a, c, L / nar if not cap else self.cw, self.row * 0.78, self.row * 0.62, nar if not cap else 10 ** 5, seed)
        rim = sstep(0.62, 0.97, e) * sstep(0.0, 0.5, dy)
        outline = sstep(0.8, 1.0, e) * (1 - sstep(0.0, 0.5, dy))
        shadow = np.exp(-ds / 0.2)
        t = 0.40 + self.tone + (rnd - 0.5) * 0.22 + 0.1 * np.clip(dy, -1, 1) + 0.22 * rim - 0.26 * shadow - 0.15 * outline
        t += (fbm(f.P, 5.0, seed + 2) - 0.5) * 0.18
        rgb = ramp(RAMP['char'], t)
        ember = ramp(RAMP['crimson'], 0.5 + 0.3 * rim)
        rgb = mixc(rgb, ember, np.clip(rim * 0.55 * self.edge, 0, 1) * (rnd > 0.35))
        glow = np.zeros(a.shape)
        out = Out(rgb)
        return out


class Plates:
    """Bands of bronze plate, each over the next, bowed across: the breastplate, the keel, the belly. Each gap is a seam."""

    def __init__(self, axis='z', ph=2.5, bend=1.0, seam='chest', tone=0.0, zones=None, vw=None):
        self.axis, self.ph, self.bend, self.seam, self.tone, self.zones, self.vw = axis, ph, bend, seam, tone, zones, vw

    def paint(self, f):
        a, c, cc, L, cap = unroll(f, self.axis)
        seed = seed_of(f.name)
        pi, qi = CROSS[self.axis]
        # the distance from the middle line of the strip: across the face
        v = np.abs(f.P[..., pi] - (f.o[pi] + f.sz[pi] / 2))
        vw = self.vw or f.sz[pi] / 2
        idx, fy, gap, prnd = plates(a, v, self.ph, self.bend, vw, seed)
        t = 0.50 + self.tone + (prnd - 0.5) * 0.18 + 0.32 * (1 - sstep(0.0, 0.2, fy)) - 0.30 * sstep(0.68, 0.98, fy) - 0.08 * fy
        t += (fbm(f.P, 4.0, seed + 9) - 0.5) * 0.16
        # a keel along the middle, edges of the strip darker
        t += 0.10 * np.exp(-(v / 0.5) ** 2) - 0.14 * sstep(0.7, 1.0, v / vw)
        t = np.where(gap > 0, -0.9, t)
        rgb = ramp(RAMP['bronze'], t)
        seamc = ramp(RAMP['seam'], np.full(a.shape, 0.30))
        rgb = np.where((gap > 0)[..., None], seamc, rgb)
        end = (1 - sstep(0.0, 1.4, f.P[..., AXIS[self.axis]] - f.o[AXIS[self.axis]])) + (1 - sstep(0.0, 1.4, f.o[AXIS[self.axis]] + f.sz[AXIS[self.axis]] - f.P[..., AXIS[self.axis]]))
        rgb = rgb * (1 - 0.3 * np.clip(end, 0, 1))[..., None]
        out = Out(rgb)
        if self.seam:
            out.glow[self.seam] = halo(gap)
        return out


# ================================================================== the wing's layout (before it is painted or built)

# ---- wings: the spread pose is the rest pose. Each segment's bone runs along its local -x (+x on the left wing),
# its membrane spans its local +y (toward the trailing edge) and its local +z is the membrane's upper side.
WING_ROOT = np.array([-8.0, -9.5, -9.0])
HUMERUS, FOREARM, F1, F2, F3 = 13.0, 19.5, 25.0, 31.0, 30.0
SPREAD = {   # world directions of each bone in the spread pose (right wing)
    'wing_r': unit([-1.0, -0.18, -0.10]),
    'wing_r2': unit([-1.0, -0.05, 0.22]),
    'wing_r3': unit([-1.0, 0.04, 0.30]),
    'wing_r_f2': unit([-0.62, 0.04, 0.78]),
    'wing_r_f3': unit([-0.04, 0.03, 1.0]),
}
SPREAD_NORMAL = unit([0.08, -1.0, 0.0])


def wing_frames():
    """World rotations and joint positions of the right wing's segments in the spread (rest) pose."""
    out = {}
    lb = np.array([-1.0, 0, 0]); ln = np.array([0, 0, 1.0])
    for name, d in SPREAD.items():
        out[name] = mframe(lb, ln, d, SPREAD_NORMAL)
    pos = {'wing_r': WING_ROOT}
    pos['wing_r2'] = pos['wing_r'] + SPREAD['wing_r'] * HUMERUS
    pos['wing_r3'] = pos['wing_r2'] + SPREAD['wing_r2'] * FOREARM
    pos['wing_r_f2'] = pos['wing_r3']
    pos['wing_r_f3'] = pos['wing_r3']
    return out, pos


class WingPlan:
    """The right wing's planform in the spread pose, seen from above (x outboard-negative, z backward): the bones, the
    scalloped trailing edge between their tips, and how the membrane is shared out between the segments' panels (each panel
    is a flat card in its segment's frame, and the texture cuts it to its share). Used to size the cards and to paint them."""
    FRAMES = {1: 'wing_r', 2: 'wing_r2', 3: 'wing_r3', 4: 'wing_r_f2', 5: 'wing_r_f3'}
    MARGIN = 0.9          # px a panel's share runs over its neighbour's, so no crack opens at the line between them
    REACH = {3: 27.0, 4: 31.0, 5: 30.0}

    def __init__(self):
        Rw, Pw = wing_frames()
        xz = lambda p: np.array([p[0], p[2]], float)
        self.Rw, self.Pw = Rw, Pw
        self.S, self.E, self.W = xz(WING_ROOT), xz(Pw['wing_r2']), xz(Pw['wing_r3'])
        self.T1 = xz(Pw['wing_r3'] + SPREAD['wing_r3'] * (F1 + 2))
        self.T2 = xz(Pw['wing_r3'] + SPREAD['wing_r_f2'] * F2)
        self.T3 = xz(Pw['wing_r3'] + SPREAD['wing_r_f3'] * F3)
        self.H = np.array([-10.0, 17.0]); self.B = np.array([-6.0, 17.0]); self.M = np.array([-26.0, 21.5])
        self.outline = [self.S, self.E, self.W, self.T1, self.T2, self.T3, self.M, self.H, self.B]
        self.spans = [(self.T1, self.T2, 3.8), (self.T2, self.T3, 3.8), (self.T3, self.M, 3.6), (self.M, self.H, 3.2)]
        self.a1, self.a2, self.a3 = (self.angle(p, self.W) for p in (self.T1, self.T2, self.T3))
        self.b12, self.b23 = (self.a1 + self.a2) / 2, (self.a2 + self.a3) / 2
        self.a_end = self.a3 + 26.0
        self.boxes = {k: self._box(k) for k in self.FRAMES}

    @staticmethod
    def angle(p, o):
        """Degrees about [o], from outboard (-x) toward backward (+z)."""
        d = np.asarray(p, float) - o
        return np.degrees(np.arctan2(d[..., 1], -d[..., 0]))

    def inside(self, px, pz):
        """Whether points are on the wing at all: inside the outline, outside the lens a scallop takes from it."""
        ins = np.zeros(px.shape, bool)
        pts = self.outline
        for i in range(len(pts)):
            a, b = pts[i], pts[(i + 1) % len(pts)]
            cond = ((a[1] > pz) != (b[1] > pz)) & (px < (b[0] - a[0]) * (pz - a[1]) / (b[1] - a[1] + 1e-12) + a[0])
            ins ^= cond
        return ins & (self.trail(px, pz) > 0)

    def trail(self, px, pz):
        """Distance in px from the trailing edge, positive on the wing (large far from every scallop)."""
        best = np.full(px.shape, 99.0)
        for A, Bp, sag in self.spans:
            c = Bp - A; L = float(np.hypot(*c)); u = c / L
            nrm = np.array([-u[1], u[0]])
            if nrm @ (self.W - (A + Bp) / 2) < 0:
                nrm = -nrm
            rx, rz = px - A[0], pz - A[1]
            t = (rx * u[0] + rz * u[1]) / L
            d = rx * nrm[0] + rz * nrm[1]
            sg = sag * np.sin(np.pi * np.clip(t, 0, 1)) ** 0.9
            near = (t >= -0.04) & (t <= 1.04)
            best = np.where(near, np.minimum(best, d - sg), best)
        return best

    def panel(self, k, px, pz, grow=0.0):
        """Whether the points are in panel [k]'s share (it grows into its neighbours by [grow] px)."""
        p = np.stack([px, pz], -1)
        th = self.angle(p, self.W)
        rad = np.hypot(px - self.W[0], pz - self.W[1])
        g = np.degrees(grow / np.maximum(rad, 8.0))
        if k in (3, 4, 5):
            lo, hi = {3: (self.a1 - 40, self.b12), 4: (self.b12, self.b23), 5: (self.b23, self.a_end)}[k]
            return (th >= lo - g) & (th < hi + g)
        rest = (th >= self.a_end - g) | (th < -60)
        ex, ez = self.H - self.E
        side = ((px - self.E[0]) * ez - (pz - self.E[1]) * ex)
        s_body = (self.B[0] - self.E[0]) * ez - (self.B[1] - self.E[1]) * ex
        sd = side * np.sign(s_body) / np.hypot(ex, ez)       # px, positive toward the body
        return rest & ((sd >= -grow) if k == 1 else (sd <= grow))

    def local(self, k, px, pz):
        """A point of the wing's plane in panel [k]'s own frame (x along its bone, y toward the trailing edge)."""
        name = self.FRAMES[k]
        R = self.Rw[name]
        o = self.Pw[name]
        A = np.stack([R[[0, 2], 0], R[[0, 2], 1]], 1)
        d = np.stack([px - o[0], pz - o[2]], -1)
        loc = d @ np.linalg.inv(A).T
        return loc[..., 0], loc[..., 1]

    def _box(self, k):
        xs = np.arange(-70, -4, 0.25); zs = np.arange(-16, 32, 0.25)
        PX, PZ = np.meshgrid(xs, zs)
        m = self.inside(PX, PZ) & self.panel(k, PX, PZ, self.MARGIN)
        lx, ly = self.local(k, PX[m], PZ[m])
        lo = lambda v: math.floor((v - 0.5) * 2) / 2
        hi = lambda v: math.ceil((v + 0.5) * 2) / 2
        x0 = lo(lx.min())
        if k in self.REACH:                  # a membrane never reaches past the bone it hangs from (the animator measures the bones by their cubes)
            x0 = max(x0, -self.REACH[k])
        return x0, hi(lx.max()), min(0.0, lo(ly.min())) if k in (4, 5) else 0.0, hi(ly.max())


WP = WingPlan()


# ---- the wing membrane: veins, tears, hems (drawn in the planform plane, right-wing coordinates)

def _edge_pt(i, u, inset=0.0):
    """A point on the scalloped trailing edge, on span [i] (0 = T1 to T2 ... 3 = M to H) at fraction [u] along it,
    drawn [inset] px further in toward the wrist."""
    A, B, sag = WP.spans[i]
    c = B - A
    L = float(np.hypot(*c)); un = c / L
    nrm = np.array([-un[1], un[0]])
    if nrm @ (WP.W - (A + B) / 2) < 0:
        nrm = -nrm
    return A + c * u + nrm * (sag * math.sin(math.pi * u) ** 0.9 + inset)


def _rib(o, tip, rng, w0=0.95, w1=0.3, wob=0.7, start=0.1, branch=True):
    """A vein from [o] to [tip]: a slightly bent run of segments tapering from w0 to w1, with a fork near its end."""
    o = np.asarray(o, float); tip = np.asarray(tip, float)
    d = tip - o
    L = float(np.hypot(*d)); un = d / L; nrm = np.array([-un[1], un[0]])
    pts = [o + d * start, o + d * 0.42 + nrm * rng.uniform(-wob, wob), o + d * 0.74 + nrm * rng.uniform(-wob, wob) * 0.7, tip]
    segs = []
    n = len(pts) - 1
    for i in range(n):
        segs.append((pts[i], pts[i + 1], w0 + (w1 - w0) * i / n, w0 + (w1 - w0) * (i + 1) / n))
    if branch:
        base = pts[2]
        for sgn in (-1, 1):
            if rng.random() < 0.75:
                ang = math.radians(sgn * rng.uniform(14, 24))
                dd = (tip - base) * rng.uniform(0.75, 1.1)
                rot = np.array([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
                segs.append((base, base + rot @ dd, 0.5, 0.22))
    return segs


def make_veins():
    rng = np.random.default_rng(23)
    segs = []
    W, E, S = WP.W, WP.E, WP.S
    for span, us in ((0, (0.22, 0.5, 0.78)), (1, (0.22, 0.5, 0.78))):
        for u in us:
            segs += _rib(W + (E - W) * 0.0, _edge_pt(span, u, 0.4), rng, start=0.14)
    for span, us in ((2, (0.12, 0.38, 0.66, 0.9)), (3, (0.2, 0.5, 0.8))):
        for u in us:
            segs += _rib(E if span == 3 or u > 0.3 else W, _edge_pt(span, u, 0.4), rng, start=0.1, w0=1.0)
    for u in (0.55, 0.9):
        segs += _rib(S, _edge_pt(3, u, 0.6), rng, start=0.12, w0=0.9, branch=False)
    return segs


VEINS = make_veins()
BONES2D = [(WP.S, WP.E), (WP.E, WP.W), (WP.W, WP.T1), (WP.W, WP.T2), (WP.W, WP.T3)]


def _tears(side):
    """The tears of a wing: [(kind, data)], in plane coordinates. Each wing has its own."""
    e = _edge_pt
    if side == 'r':
        return [('tri', (e(1, 0.30), e(1, 0.5), e(1, 0.40, 5.6))),
                ('slash', (e(3, 0.46), e(3, 0.46, 9.0), 0.8)),
                ('tri', (e(0, 0.60), e(0, 0.76), e(0, 0.68, 3.2))),
                ('circ', (np.array([-52.0, 11.0]), 1.5)), ('circ', (np.array([-47.5, 16.5]), 0.9)),
                ('circ', (np.array([-31.0, 6.0]), 1.3))]
    return [('tri', (e(2, 0.52), e(2, 0.70), e(2, 0.60, 4.8))),
            ('slash', (e(0, 0.50), e(0, 0.50, 7.5), 0.7)),
            ('tri', (e(1, 0.74), e(1, 0.86), e(1, 0.80, 2.8))),
            ('circ', (np.array([-48.0, 9.0]), 1.2)), ('circ', (np.array([-34.0, 13.0]), 1.6)),
            ('circ', (np.array([-45.0, 19.0]), 0.8))]


def _cut_shapes(px, pz, shapes, seed):
    """(cut, rim): where the tears take the membrane away, and how far each texel lies outside them (px)."""
    cut = np.zeros(px.shape, bool)
    rim = np.full(px.shape, 9.0)
    for kind, d in shapes:
        if kind == 'tri':
            a, b, c = d
            def sgn(p1, p2, x, z):
                return (x - p2[0]) * (p1[1] - p2[1]) - (p1[0] - p2[0]) * (z - p2[1])
            d1, d2, d3 = sgn(a, b, px, pz), sgn(b, c, px, pz), sgn(c, a, px, pz)
            inside = ~(((d1 < 0) | (d2 < 0) | (d3 < 0)) & ((d1 > 0) | (d2 > 0) | (d3 > 0)))
            edge = np.minimum(np.minimum(seg_dist(px, pz, a, b)[0], seg_dist(px, pz, b, c)[0]), seg_dist(px, pz, c, a)[0])
        elif kind == 'slash':
            a, b, w = d
            dist, t = seg_dist(px, pz, a, b)
            inside = dist < w * 0.5 * (1.0 - 0.6 * t)
            edge = dist - w * 0.5 * (1.0 - 0.6 * t)
            edge = np.abs(edge)
        else:
            cen, r = d
            ang = np.arctan2(pz - cen[1], px - cen[0])
            rr = r * (1 + 0.28 * np.sin(3 * ang + seed % 7) + 0.16 * np.sin(7 * ang + 1.3))
            dist = np.hypot(px - cen[0], pz - cen[1])
            inside = dist < rr
            edge = np.abs(dist - rr)
        cut |= inside
        rim = np.minimum(rim, np.where(inside, 0.0, edge))
    return cut, rim


def _cell_edges(x, z, cell, seed):
    """Worley noise: F2 - F1 is small along the borders of random cells, a net of fine capillaries."""
    gx = np.floor(x / cell).astype(np.int64); gz = np.floor(z / cell).astype(np.int64)
    f1 = np.full(x.shape, 9.0); f2 = np.full(x.shape, 9.0)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            cx, cz = gx + dx, gz + dz
            px = (cx + hash01(cx, cz, 1, seed)) * cell
            pz = (cz + hash01(cx, cz, 2, seed)) * cell
            d = np.hypot(x - px, z - pz) / cell
            f2 = np.where(d < f1, f1, np.minimum(f2, d))
            f1 = np.minimum(f1, d)
    return f1, f2


class Membrane:
    """A wing's skin: wine-dark, a darker hem along the scalloped trailing edge, shadow along the bones, ribs of vein
    that fork toward the edge, a net of fine capillaries, patches of rust, tears and holes cut through. One painting is
    laid over all the panels of both wings in the plane of the spread wing, so the veins run on from one panel to the next."""

    def __init__(self, k, side):
        self.k, self.side = k, side

    def paint(self, f):
        wx, wy, wz = (f.world()[..., i] for i in range(3))
        px = wx if self.side == 'r' else -wx
        pz = wz
        k = self.k
        region = WP.inside(px, pz) & WP.panel(k, px, pz, WP.MARGIN)
        cut, rimd = _cut_shapes(px, pz, _tears(self.side), seed_of('tears', self.side))
        alpha = (region & ~cut).astype(float)
        seed = 7
        # distance to the bones and to the nearest vein
        db = np.full(px.shape, 99.0)
        for a, b in BONES2D:
            db = np.minimum(db, seg_dist(px, pz, a, b)[0])
        vf = np.full(px.shape, 9.0)
        for a, b, w0, w1 in VEINS:
            d, t = seg_dist(px, pz, a, b)
            vf = np.minimum(vf, d - (w0 + (w1 - w0) * t) * 0.5)
        tr = WP.trail(px, pz)
        big = vnoise(px / 8.0, pz / 8.0, np.zeros_like(px), seed)
        mid = vnoise(px / 3.2, pz / 3.2, np.zeros_like(px) + 4, seed + 1)
        f1, f2 = _cell_edges(px, pz, 3.4, seed)
        capill = 1 - sstep(0.04, 0.17, f2 - f1)
        t = 0.40 + (big - 0.5) * 0.22 + (mid - 0.5) * 0.07
        # thin skin between the bones is lighter and glows; it darkens toward the bones and the hem
        t = t + 0.11 * sstep(2.0, 9.0, db) * (1 - sstep(8.0, 16.0, tr)) - 0.18 * np.exp(-np.maximum(db - 0.8, 0) / 2.4)
        t = t - 0.26 * (1 - sstep(0.0, 1.1, tr)) - 0.06 * capill
        core = vf < 0.0
        halo_ = (vf >= 0.0) & (vf < 0.36)
        t = np.where(core, t - 0.20, t)
        t = np.where(halo_, t + 0.07, t)                      # the lip of a vein catches the light
        scorch = 1 - sstep(0.0, 0.8, rimd)
        t = t - 0.34 * scorch
        if f.face == 'front':
            t = t + 0.03
        rgb = ramp(RAMP['wine'], t)
        rust = sstep(0.60, 0.75, vnoise(px / 6.5 + 9, pz / 6.5, np.zeros_like(px) + 2, seed + 7))
        rgb = mixc(rgb, np.array([176.0, 70.0, 38.0]) * (0.55 + t[..., None] * 0.5), rust * 0.30)
        return Out(rgb, alpha)


class Mouth:
    """The inside of the mouth: dark red, wet, ridged across the roof, grooved down the tongue (forked at its tip). It glows when
    the dragon breathes fire."""

    def __init__(self, kind='roof', glow=0.8):
        self.kind, self.glow = kind, glow

    def paint(self, f):
        seed = seed_of(f.name)
        P = f.P
        n = fbm(P, 2.4, seed)
        sp = np.zeros(P.shape[:2])
        alpha = np.ones(P.shape[:2])
        if self.kind == 'roof':
            ridge = 0.5 + 0.5 * np.sin(P[..., 2] * 5.2 + 0.8 * np.abs(P[..., 0]))
            t = 0.40 + (n - 0.5) * 0.18 + 0.16 * ridge - 0.12 * np.exp(-(P[..., 0] / 0.35) ** 2)
        elif self.kind == 'tongue':
            u = (P[..., 2] - f.o[2]) / max(f.sz[2], 1e-6)
            cx = P[..., 0] - (f.o[0] + f.sz[0] / 2)
            pap = vnoise(P[..., 0] * 2.1, P[..., 2] * 2.1, np.zeros_like(n), seed + 5)
            t = 0.52 + (n - 0.5) * 0.14 + 0.14 * (pap > 0.62) - 0.2 * np.exp(-(cx / 0.28) ** 2) + 0.06 * u
            # a forked tip: the V cut into its far end
            tip = (P[..., 2] - f.o[2]) < 1.8 if True else False
            fork = (np.abs(cx) < (1.9 - (P[..., 2] - f.o[2])) * 0.34) & ((P[..., 2] - f.o[2]) < 1.9)
            alpha = (~fork).astype(float)
        else:
            t = 0.36 + (n - 0.5) * 0.16 + 0.1 * np.sin(P[..., 2] * 3.0 + 1.0) * 0.5
        wet = (vnoise(P[..., 0] * 3.3, P[..., 2] * 3.3, P[..., 1] * 3.3, seed + 8) > 0.82)
        t = t + 0.18 * wet
        rgb = ramp(RAMP['mouth'], t)
        return Out(rgb, alpha, {'mouth': np.clip(self.glow * (0.7 + 0.5 * (t - 0.4)), 0, 1) * alpha})


class Teeth:
    """A row of ivory teeth set in a dark-red gum round the edge of a jaw; its inside face is the mouth, edged with the tooth ends. [gum]
    is which side the gum is on: 'top' (the y-min side) for the upper teeth, 'bottom' for the lower."""

    def __init__(self, gum='top', pitch=1.5):
        self.gum, self.pitch = gum, pitch

    def paint(self, f):
        seed = seed_of(f.name)
        P, o, sz, n = f.P, f.o, f.sz, f.n
        if abs(n[1]) > 0.5:
            # the face that looks into the mouth: the tooth ends dashed round its edge, the mouth between
            dxe = np.minimum(P[..., 0] - o[0], o[0] + sz[0] - P[..., 0])
            dze = np.minimum(P[..., 2] - o[2], o[2] + sz[2] - P[..., 2])
            d = np.minimum(dxe, dze)
            along = np.where(dxe < dze, P[..., 2], P[..., 0]) if min(sz[0], sz[2]) > 1.2 else (P[..., 2] if sz[2] >= sz[0] else P[..., 0])
            g = (along / self.pitch) % 1.0
            idx = np.floor(along / self.pitch).astype(np.int64)
            m = Mouth('floor' if self.gum == 'bottom' else 'roof').paint(f)
            tone_ = 0.78 + (hash01(idx, 3, 0, seed) - 0.5) * 0.16 + (fbm(P, 1.4, seed) - 0.5) * 0.1
            ivory = ramp(RAMP['ivory'], tone_)
            teeth = (d < 0.8) & (g < 0.66)
            rgb = np.where(teeth[..., None], ivory, m.rgb)
            return Out(rgb, None, {'mouth': np.where(teeth, 0.0, m.glow['mouth'])})
        along = P[..., 0] if abs(n[2]) > 0.5 else P[..., 2]
        yy = (P[..., 1] - o[1]) / sz[1]
        yy = yy if self.gum == 'top' else 1 - yy                      # 0 at the gum, 1 at the tips
        q = along / self.pitch
        g = q - np.floor(q)
        tooth_w = 0.62 * (1 - 0.55 * sstep(0.45, 1.0, yy))                # each tooth narrows toward its tip
        is_tooth = (np.abs((g - 0.5) * self.pitch) < tooth_w) & (yy > 0.26)
        sh = (g - 0.5)
        t = 0.80 + (fbm(P, 1.4, seed + 1) - 0.5) * 0.2 + 0.12 * (-sh * 2) - 0.16 * (yy > 0.86)
        ivory = ramp(RAMP['ivory'], t)
        gum = ramp(RAMP['mouth'], 0.34 + 0.14 * (1 - yy))
        rgb = np.where(is_tooth[..., None], ivory, gum)
        return Out(rgb)


ZONES_HEAD = [(0.0, 'char', 0.1), (0.10, 'crimson', -0.18), (0.30, 'crimson', -0.02), (0.60, 'crimson', 0.04), (0.78, 'crimson', 0.10),
              (0.88, 'bronze', -0.04), (1.0, 'bronze', 0.02)]


class HeadHide(Hide):
    """The head's own hide: finer scales, bronze under the jaw, the mouth inside, and the eyes and nostrils set in it."""

    def __init__(self, mouth_roof=False, jaw=False, eyes=False, nostrils=False, **kw):
        kw.setdefault('zones', ZONES_HEAD)
        kw.setdefault('cw', 2.7); kw.setdefault('hs', 1.5); kw.setdefault('ra', 2.0)
        super().__init__(**kw)
        self.mouth_roof, self.jaw, self.eyes, self.nostrils = mouth_roof, jaw, eyes, nostrils

    def paint(self, f):
        if self.mouth_roof and f.face == 'bottom':
            return Mouth('roof').paint(f)
        if self.jaw and f.face == 'top':
            return Mouth('floor').paint(f)
        out = Hide.paint(self, f)
        if self.eyes:
            self.eye(f, out)
        if self.nostrils:
            self.nostril(f, out)
        return out

    def eye(self, f, out):
        """A molten-gold slit eye on the corner of the skull under the brow, wrapped round the edge so it shows from the
        front as well as the side: an almond of gold deepening to orange at its rim, a thin black slit, a dark lid round it."""
        P = f.P
        if f.face not in ('front', 'left', 'right'):
            return
        if f.face == 'front':
            hh = -(5.0 - np.abs(P[..., 0]))               # round the corner: negative on the face, positive on the side
            near = np.abs(P[..., 0]) > 2.6
        else:
            hh = P[..., 2] + 9.0
            near = np.ones(hh.shape, bool)
        hh0, ah, ay = 0.55, 2.1, 1.1
        yc = -3.2 + 0.28 * (hh0 - hh)                      # the outer end rides higher: a hard look
        u = (hh - hh0) / ah
        dy = P[..., 1] - yc
        lens = (np.abs(dy) < ay * np.clip(1 - np.abs(u) ** 1.8, 0, 1)) & (np.abs(u) < 1) & near
        u2 = u / 1.22
        ring = (np.abs(dy) < (ay + 0.50) * np.clip(1 - np.abs(u2) ** 1.8, 0, 1)) & (np.abs(u2) < 1) & near & ~lens
        r = np.clip(np.hypot(u * 0.85, dy / ay), 0, 1)
        iris = ramp(RAMP['gold'], 0.60 - 0.32 * r ** 1.5)
        pupil = lens & (np.abs(hh - hh0 - 0.1) < 0.27 * (1 - 0.4 * r))
        rgb = out.rgb
        rgb = np.where(ring[..., None], ramp(RAMP['crimson'], np.full(r.shape, 0.07)), rgb)
        rgb = np.where(lens[..., None], iris, rgb)
        rgb = np.where(pupil[..., None], ramp(RAMP['obsidian'], np.full(r.shape, 0.03)), rgb)
        spark = lens & ~pupil & (np.abs(hh - (hh0 - 0.85)) < 0.28) & (dy < -0.15) & (dy > -0.75)
        rgb = np.where(spark[..., None], np.array([255.0, 250.0, 224.0]), rgb)
        out.rgb = rgb
        iris_on = np.where(lens & ~pupil, 0.62 + 0.38 * (1 - r ** 1.5), 0.0)       # the middle of the eye burns hottest
        out.glow['eye'] = np.maximum(out.glow.get('eye', 0.0), np.maximum(iris_on, halo(iris_on, 1, 0.30) * ring))

    def nostril(self, f, out):
        P = f.P
        if f.face not in ('front', 'top'):
            return
        for sx in (-1, 1):
            cx = sx * 1.25
            if f.face == 'front':
                m = (np.abs(P[..., 0] - cx) < 0.34 * (1 - 0.4 * (P[..., 1] + 3.5) / 3)) & (P[..., 1] > -2.7) & (P[..., 1] < -1.3)
            else:
                m = (np.abs(P[..., 0] - cx) < 0.5) & (P[..., 2] < -16.2)
            out.rgb = np.where(m[..., None], ramp(RAMP['obsidian'], np.full(m.shape, 0.05)), out.rgb)

class Nostril(HeadHide):
    """A raised nostril on the nose: scaled skin round a dark slit that opens to the front."""

    def paint(self, f):
        out = Hide.paint(self, f)
        P, o, sz = f.P, f.o, f.sz
        cx = o[0] + sz[0] / 2
        if f.face == 'front':
            dxn = np.abs(P[..., 0] - cx)
            dyn = P[..., 1] - (o[1] + sz[1] * 0.5)
            m = ((dxn / 0.30) ** 2 + (dyn / 0.50) ** 2) < 1.0
        else:
            return out
        out.rgb = np.where(m[..., None], ramp(RAMP['obsidian'], np.full(m.shape, 0.04)), out.rgb)
        return out


# ================================================================== geometry

def C(o, s, name, inflate=0.0):
    """A cube (painted later, by name: see STYLE)."""
    return Cube(tuple(float(v) for v in o), tuple(float(v) for v in s), None, inflate=inflate, name=name)


def P_(name, pivot, rot=(0, 0, 0), cubes=(), children=()):
    return Part(name, kpos(pivot), krot(rot), list(cubes), list(children))


def spine(name, pivot, h, d=2.5, lean=-30.0, w=1.0, yaw=0.0, roll=0.0):
    """A dorsal spine standing up from [pivot], leaning back by [lean] (negative tips it toward the tail)."""
    return P_(name, pivot, (lean, yaw, roll), [C((-w / 2, -h, -d / 2), (w, h + 0.5, d), name)])


# ---- head: pivot at the atlas, the snout along -z
def head_part():
    jaw = P_('jaw', (0, 0, -2.5), cubes=[
        C((-3.5, 0, -9.75), (7, 3, 7.5), 'jaw_c'),
        C((-2.75, 0, -13.75), (5.5, 3, 4), 'chin'),
        C((-4.75, -0.5, -5.5), (9.5, 4, 6.5), 'jaw_back'),
        C((-3.5, -1.5, -10), (1, 1.5, 7), 'teeth_lo_r'),
        C((2.5, -1.5, -10), (1, 1.5, 7), 'teeth_lo_l'),
        C((-2.75, -1.5, -14), (1, 1.5, 4), 'teeth_lo2_r'),
        C((1.75, -1.5, -14), (1, 1.5, 4), 'teeth_lo2_l'),
        C((-1.75, -1.5, -14), (3.5, 1.5, 1), 'teeth_lo_f'),
        C((-2, -0.5, -12), (4, 0.5, 9), 'tongue'),
    ], children=[
        P_('jspike_r', (-4.5, 3, -3.5), (-25, -30, 0), [C((-0.75, -0.75, -0.5), (1.5, 1.5, 4.5), 'jspike_r')]),
        P_('jspike_l', (4.5, 3, -3.5), (-25, 30, 0), [C((-0.75, -0.75, -0.5), (1.5, 1.5, 4.5), 'jspike_l')]),
        P_('jspike_r2', (-4, 3, -7.5), (-30, -22, 0), [C((-0.5, -0.5, -0.5), (1, 1, 3.5), 'jspike_r2')]),
        P_('jspike_l2', (4, 3, -7.5), (-30, 22, 0), [C((-0.5, -0.5, -0.5), (1, 1, 3.5), 'jspike_l2')]),
    ])
    lids = P_('lids', (0, 0, 0), cubes=[
        C((-5.5, -4.25, -9.25), (1.5, 2, 3), 'lid_r'),
        C((4, -4.25, -9.25), (1.5, 2, 3), 'lid_l'),
    ])

    def horn(side):
        s = -1 if side == 'r' else 1
        return P_(f'horn_{side}', (3.5 * s, -5.5, -2.5), (10, 18 * s, 0), [C((-1.25, -1.25, -1), (2.5, 2.5, 8), f'horn_{side}')], [
            P_(f'horn_{side}2', (0, 0, 7), (-8, 5 * s, 0), [C((-1, -1, -0.5), (2, 2, 7.5), f'horn_{side}2')], [
                P_(f'horn_{side}3', (0, 0, 7), (16, -4 * s, 0), [C((-0.75, -0.75, -0.5), (1.5, 1.5, 6.5), f'horn_{side}3')], [
                    P_(f'horn_{side}4', (0, 0, 6), (-12, -3 * s, 0), [C((-0.5, -0.5, -0.5), (1, 1, 4.5), f'horn_{side}4')])])])])

    def cheek_horn(side):
        s = -1 if side == 'r' else 1
        return P_(f'chorn_{side}', (5.25 * s, -1.5, -3), (6, 30 * s, 0), [C((-0.75, -0.75, -0.5), (1.5, 1.5, 5.5), f'chorn_{side}')])

    def brow_spike(side):
        s = -1 if side == 'r' else 1
        return P_(f'brow_spike_{side}', (5.0 * s, -6.0, -6.5), (18, 40 * s, 0), [C((-0.75, -0.75, -0.5), (1.5, 1.5, 4.5), f'brow_spike_{side}')])

    return P_('head', (0, 0, -8), (26, 0, 0), cubes=[
        C((-5, -5, -9), (10, 7, 9.5), 'cranium'),
        C((-5.5, -2.5, -7.5), (11, 4.5, 6), 'cheeks'),
        C((-6, -6.75, -10.5), (4.5, 2.5, 5.5), 'brow_r'),
        C((1.5, -6.75, -10.5), (4.5, 2.5, 5.5), 'brow_l'),
        C((-3.5, -4, -12.5), (7, 4, 4), 'snout'),
        C((-3, -3.5, -15), (6, 3.5, 2.5), 'snout2'),
        C((-2.75, -3.5, -17), (5.5, 3.5, 2), 'nose'),
        C((-1, -5, -15), (2, 1.5, 6), 'nasal'),
        C((-2.75, -4.5, -16.5), (1.5, 1, 1.5), 'nostril_r'),
        C((1.25, -4.5, -16.5), (1.5, 1, 1.5), 'nostril_l'),
        C((-3.5, -6, -4.5), (7, 1.5, 5), 'crest'),
        C((-3.75, 0, -12.5), (7.5, 1.5, 4), 'teeth_up'),
        C((-3.25, 0, -16.5), (6.5, 1.5, 4), 'teeth_up2'),
        C((-3.75, 1.5, -15.5), (1, 2, 1), 'fang_r'),
        C((2.75, 1.5, -15.5), (1, 2, 1), 'fang_l'),
    ], children=[jaw, lids, horn('r'), horn('l'), cheek_horn('r'), cheek_horn('l'), brow_spike('r'), brow_spike('l'),
                 spine('spine_h1', (0, -5.75, -1.5), 2.5, 2, -40), spine('spine_h2', (0, -5.5, 1), 2, 2, -40)])


def neck_parts():
    head = head_part()
    n3 = P_('neck3', (0, 0, -8), (14, 0, 0), [C((-4, -4, -9.5), (8, 8.5, 10), 'neck3_c')],
            [head, spine('spine_n5', (0, -3.75, -4.5), 3, 2, -25)])
    n2 = P_('neck2', (0, 0, -8), (10, 0, 0), [C((-4.75, -4.75, -9.5), (9.5, 9.5, 10), 'neck2_c')],
            [n3, spine('spine_n3', (0, -4.5, -2.5), 4, 2.5, -25), spine('spine_n4', (0, -4.5, -7), 3.5, 2.5, -25)])
    n1 = P_('neck1', (0, -3, -15), (-38, 0, 0), [C((-5.5, -5, -9.5), (11, 11.5, 10.5), 'neck1_c')],
            [n2, spine('spine_n1', (0, -4.75, -2.5), 4.5, 2.5, -25), spine('spine_n2', (0, -4.75, -7), 4, 2.5, -25)])
    return n1


def tail_parts():
    tip = [
        C((-2.25, -2, -1), (4.5, 4, 10), 'tail5_c'),
        C((-0.5, -3.5, 6), (1, 7, 9), 'tail_blade'),
    ]
    t5 = P_('tail5', (0, 0, 8.5), (6, 0, 0), tip, [
        P_('tspike_r', (-1.5, 0, 6), (0, -40, 0), [C((-1, -1, -0.5), (2, 2, 6.5), 'tspike_r')]),
        P_('tspike_l', (1.5, 0, 6), (0, 40, 0), [C((-1, -1, -0.5), (2, 2, 6.5), 'tspike_l')]),
        P_('tspike_r2', (-1.5, 0, 2), (10, -55, 0), [C((-1, -1, -0.5), (2, 2, 5), 'tspike_r2')]),
        P_('tspike_l2', (1.5, 0, 2), (10, 55, 0), [C((-1, -1, -0.5), (2, 2, 5), 'tspike_l2')]),
    ])
    t4 = P_('tail4', (0, 0, 9), (8, 0, 0), [C((-3, -2.75, -1), (6, 5.5, 10), 'tail4_c')],
            [t5, spine('spine_t7', (0, -2.5, 3.5), 3, 2, -45)])
    t3 = P_('tail3', (0, 0, 9), (6, 0, 0), [C((-4, -3.5, -1), (8, 7, 10.5), 'tail3_c')],
            [t4, spine('spine_t5', (0, -3.25, 2.5), 3.5, 2, -45), spine('spine_t6', (0, -3.25, 7), 3, 2, -45)])
    t2 = P_('tail2', (0, 0, 9), (5, 0, 0), [C((-5, -4.25, -1), (10, 8.5, 10.5), 'tail2_c')],
            [t3, spine('spine_t3', (0, -4, 2.5), 4, 2.5, -40), spine('spine_t4', (0, -4, 7), 3.5, 2.5, -40)])
    t1 = P_('tail1', (0, -2, 19), (-20, 0, 0), [C((-6, -5, -1.5), (12, 10, 11.5), 'tail1_c')],
            [t2, spine('spine_t1', (0, -4.75, 2.5), 4.5, 2.5, -40), spine('spine_t2', (0, -4.75, 7), 4, 2.5, -40)])
    return t1


# ---- legs: each segment hangs along its own +y; the next joint is straight below at the segment's length
FRONT = dict(L1=12.0, L2=11.0, ankle=2.5)
HIND = dict(L1=12.0, L2=12.0, ankle=2.5)
SHOULDER = {'r': (-9.5, 3.0, -9.0), 'l': (9.5, 3.0, -9.0)}      # in the body's frame
HIP = {'r': (-9.0, 3.0, 11.0), 'l': (9.0, 3.0, 11.0)}           # in the hips' frame
HIPS_PIVOT = (0.0, -1.0, 3.0)
STAND_FRONT = {'r': (-11.0, 24 - FRONT['ankle'], -10.0), 'l': (11.0, 24 - FRONT['ankle'], -10.0)}
STAND_HIND = {'r': (-10.5, 24 - HIND['ankle'], 15.0), 'l': (10.5, 24 - HIND['ankle'], 15.0)}


def leg_rest(hip_world, ankle_world, L1, L2, pole):
    R1, R2, K, A = two_bone(hip_world, ankle_world, L1, L2, pole, X)
    return euler_of(R1), euler_of(R1.T @ R2), euler_of(R2.T)


def barb(name, pivot, Rparent, world_dir, size=(1.5, 1.5, 5.0), up=(0.0, -1.0, 0.0)):
    """A spike growing out of its parent in a chosen direction of the rest pose's world (given the parent's world rotation)."""
    R = mframe(np.array([0.0, 0.0, 1.0]), np.array([0.0, -1.0, 0.0]), unit(np.asarray(world_dir, float)), unit(np.asarray(up, float)))
    w, h, L = size
    return P_(name, pivot, euler_of(Rparent.T @ R), [C((-w / 2, -h / 2, -0.5), size, name)])


def front_leg(side):
    s = -1 if side == 'r' else 1
    r1, r2, r3 = leg_rest(np.array(SHOULDER[side]), np.array(STAND_FRONT[side]), FRONT['L1'], FRONT['L2'], np.array([0, 0, 1.0]))
    foot = P_(f'foot_f{side}', (0, FRONT['L2'], 0), r3, [
        C((-3, -1, -5), (6, 3.5, 7.5), f'foot_f{side}_c'),
        C((-3.25, 0.5, -8.5), (1.5, 2, 4), f'toe_f{side}1'),
        C((-0.75, 0.5, -9), (1.5, 2, 4.5), f'toe_f{side}2'),
        C((1.75, 0.5, -8.5), (1.5, 2, 4), f'toe_f{side}3'),
        C((-3, 1, -10.5), (1, 1.5, 2), f'claw_f{side}1'),
        C((-0.5, 1, -11), (1, 1.5, 2), f'claw_f{side}2'),
        C((2, 1, -10.5), (1, 1.5, 2), f'claw_f{side}3'),
    ])
    shin = P_(f'shin_f{side}', (0, FRONT['L1'], 0), r2, [C((-2.5, -1, -2.75), (5, 12.5, 5.5), f'shin_f{side}_c')], [foot])
    sx = -1 if side == 'r' else 1
    spike = barb(f'kspike_f{side}', (sx * 1.5, 10.5, 3.0), rotm(r1), (sx * 0.45, -0.1, 1.0), (2.0, 2.0, 5.5))
    return P_(f'leg_f{side}', SHOULDER[side], r1, [C((-3.25, -4, -3.5), (6.5, 16.5, 7), f'leg_f{side}_c')], [shin, spike])


def hind_leg(side):
    hip_w = np.array(HIP[side]) + np.array(HIPS_PIVOT)
    r1, r2, r3 = leg_rest(hip_w, np.array(STAND_HIND[side]), HIND['L1'], HIND['L2'], np.array([0, 0, -1.0]))
    foot = P_(f'foot_h{side}', (0, HIND['L2'], 0), r3, [
        C((-3, -1, -4.5), (6, 3.5, 8), f'foot_h{side}_c'),
        C((-0.75, -1.5, 3), (1.5, 2, 3), f'heel_h{side}'),
        C((-3.25, 0.5, -8.5), (1.5, 2, 4.5), f'toe_h{side}1'),
        C((-0.75, 0.5, -9), (1.5, 2, 5), f'toe_h{side}2'),
        C((1.75, 0.5, -8.5), (1.5, 2, 4.5), f'toe_h{side}3'),
        C((-3, 1, -10.5), (1, 1.5, 2), f'claw_h{side}1'),
        C((-0.5, 1, -11), (1, 1.5, 2), f'claw_h{side}2'),
        C((2, 1, -10.5), (1, 1.5, 2), f'claw_h{side}3'),
    ])
    shin = P_(f'shin_h{side}', (0, HIND['L1'], 0), r2, [C((-2.5, -1.5, -2.5), (5, 13.5, 5), f'shin_h{side}_c')], [foot])
    sx = -1 if side == 'r' else 1
    spike = barb(f'kspike_h{side}', (sx * 1.5, 11.0, -4.0), rotm(r1), (sx * 0.45, -0.1, -1.0), (2.0, 2.0, 5.5))
    return P_(f'leg_h{side}', HIP[side], r1, [C((-3.75, -6, -4.5), (7.5, 18, 9.5), f'leg_h{side}_c')], [shin, spike])




def wing(side):
    s = -1 if side == 'r' else 1
    Rw, Pw = wing_frames()
    M = np.diag([-1.0, 1.0, 1.0])

    def local(name, parent):
        R = Rw[name] if parent is None else Rw[parent].T @ Rw[name]
        if side == 'l':
            R = M @ R @ M
        return euler_of(R)

    def bx(x0, x1, y0, y1, z0, z1, name):
        """A box given in right-wing coordinates, mirrored for the left wing."""
        if side == 'l':
            x0, x1 = -x1, -x0
        return C((x0, y0, z0), (x1 - x0, y1 - y0, z1 - z0), name)

    n = lambda base: base.replace('_r', '_' + side, 1)
    memb = lambda k: bx(*WP.boxes[k][:2], *WP.boxes[k][2:], 0, 0, n(f'memb_r{k}'))
    f3 = P_(n('wing_r_f3'), (0, 0, -0.3), local('wing_r_f3', 'wing_r3'), [bx(-30, 0, -0.75, 0.75, -0.75, 0.75, n('fing_r3')), memb(5)])
    f2 = P_(n('wing_r_f2'), (0, 0, -0.15), local('wing_r_f2', 'wing_r3'), [bx(-31, 0, -0.75, 0.75, -0.75, 0.75, n('fing_r2')), memb(4)])
    hand = P_(n('wing_r3'), (s * FOREARM, 0, 0), local('wing_r3', 'wing_r2'), [
        bx(-2, 2, -2, 2, -2, 2, n('wrist_r')),
        bx(-1.75, -0.25, -5, -1.5, -0.75, 0.75, n('thumb_r')),
        bx(-25, 0, -0.75, 0.75, -0.75, 0.75, n('fing_r1')),
        bx(-27, -25, -0.5, 0.5, -0.5, 0.5, n('ftip_r1')),
        memb(3),
    ], [f2, f3])
    Rf = Rw['wing_r2'] if side == 'r' else M @ Rw['wing_r2'] @ M
    elbow_spike = barb(n('wspike_r'), (0.0, 0.0, 1.8), Rf, (s * 0.25, -0.75, 0.55), (2.0, 2.0, 5.5))
    fore = P_(n('wing_r2'), (s * HUMERUS, 0, 0), local('wing_r2', 'wing_r'), [
        bx(-1.75, 1.75, -2, 2, -2, 2, n('elbow_r')),
        bx(-19.5, 0, -1.5, 1.5, -1.5, 1.5, n('forearm_r')),
        memb(2),
    ], [hand, elbow_spike])
    root = WING_ROOT if side == 'r' else WING_ROOT * np.array([-1.0, 1.0, 1.0])
    return P_(n('wing_r'), root, local('wing_r', None), [
        bx(-2, 2, -2, 2, -2, 2, n('wshoulder_r')),
        bx(-13, 0, -1.75, 1.75, -1.75, 1.75, n('humerus_r')),
        memb(1),
    ], [fore])


def build():
    hips = P_('hips', HIPS_PIVOT, cubes=[
        C((-10, -7, -3), (20, 14.5, 20), 'pelvis'),
        C((-9, 6, -4), (18, 3, 14), 'belly'),
        C((-6, -8.5, -2), (12, 2, 19), 'croup'),
        C((-7, -6, 15), (14, 11, 5), 'tailroot'),
    ], children=[tail_parts(), hind_leg('r'), hind_leg('l'),
                 spine('spine_b5', (0, -8.25, 3), 5, 3, -30), spine('spine_b6', (0, -8.25, 8), 4.5, 3, -30),
                 spine('spine_b7', (0, -8.25, 13), 4, 2.5, -30)])
    body = P_('body', (0, 0, 0), cubes=[
        C((-11, -9, -17), (22, 18, 20), 'chest'),
        C((-9, -5.5, -19.5), (18, 12, 3), 'breast'),
        C((-6, 7, -16), (12, 5, 15), 'keel'),
        C((-7, -11, -15.5), (14, 2.5, 19), 'withers'),
        C((-12.5, -6, -15), (4, 10, 11), 'shoulder_r'),
        C((8.5, -6, -15), (4, 10, 11), 'shoulder_l'),
    ], children=[neck_parts(), hips, front_leg('r'), front_leg('l'), wing('r'), wing('l'),
                 barb('sspike_r', (-11.5, -5.0, -10.0), np.eye(3), (-0.85, -0.3, 0.45), (2.0, 2.0, 6.0)),
                 barb('sspike_l', (11.5, -5.0, -10.0), np.eye(3), (0.85, -0.3, 0.45), (2.0, 2.0, 6.0)),
                 spine('spine_b1', (0, -10.75, -13), 5, 3, -30), spine('spine_b2', (0, -10.75, -8.5), 6, 3, -30),
                 spine('spine_b3', (0, -10.75, -4), 6, 3, -30), spine('spine_b4', (0, -10.75, 0.5), 5, 3, -30)])
    return Model('dragon', TEX_W, TEX_H, [body])


model = build()
IDX = {p.name: p for p in model.all_parts()}
PARENT = {}
for _p in model.all_parts():
    for _ch in _p.children:
        PARENT[_ch.name] = _p.name


# ================================================================== faces in the game's box-UV layout (at HD)

def face_rects(c):
    """Each face's rectangle in the texture (declared texels, floats: the game maps fractional sizes as they are)."""
    u, v = c.uv
    w, h, d = c.size
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h),
            'front': (u + d, v + d, w, h), 'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}


def face_corners(c, g=None):
    """(A, B, D, normal) per face in the cube's part frame: A at the rect's top-left texel corner, B along u, D along v,
    the way ModelPart.Cube maps them (the bottom face's first row is the cube's back edge)."""
    g = c.inflate if g is None else g
    x0, y0, z0 = [o - g for o in c.origin]
    x1, y1, z1 = [o + s + g for o, s in zip(c.origin, c.size)]
    P = lambda x, y, z: np.array([x, y, z], float)
    return {
        'top': (P(x0, y0, z1), P(x1, y0, z1), P(x0, y0, z0), (0, -1, 0)),
        'bottom': (P(x0, y1, z1), P(x1, y1, z1), P(x0, y1, z0), (0, 1, 0)),
        'right': (P(x0, y0, z1), P(x0, y0, z0), P(x0, y1, z1), (-1, 0, 0)),
        'front': (P(x0, y0, z0), P(x1, y0, z0), P(x0, y1, z0), (0, 0, -1)),
        'left': (P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z0), (1, 0, 0)),
        'back': (P(x1, y0, z1), P(x0, y0, z1), P(x1, y1, z1), (0, 0, 1)),
    }


def rest_pose():
    return {name: (rotm(p.rot), np.zeros(3)) for name, p in IDX.items()}


def world_transforms(pose=None):
    """Each part's world rotation and pivot position. [pose] maps names to (local rotation matrix, pivot offset)."""
    pose = pose or {}
    out = {}

    def go(p, R, T):
        local, off = pose.get(p.name, (rotm(p.rot), np.zeros(3)))
        Rw = R @ local
        Tw = T + R @ (np.array(p.pivot, float) + off)
        out[p.name] = (Rw, Tw)
        for ch in p.children:
            go(ch, Rw, Tw)
    for p in model.parts:
        go(p, np.eye(3), np.zeros(3))
    return out


CUBE_FACES = []      # (part name, cube, face, A, B, D, n, hd rect) in the part's frame
def _collect():
    CUBE_FACES.clear()
    for p in model.all_parts():
        for c in p.cubes:
            rects = face_rects(c)
            for face, (A, B, D, n) in face_corners(c).items():
                x, y, w, h = rects[face]
                if w <= 0 or h <= 0:
                    continue
                CUBE_FACES.append((p.name, c, face, A, B, D, np.array(n, float),
                                   (int(round(x * HD)), int(round(y * HD)), int(round(w * HD)), int(round(h * HD)))))


# ================================================================== what each cube is made of

# First match wins: (pattern on the cube's name, shader factory)
def _hide(**kw):
    return lambda: Hide(**kw)


def _head(**kw):
    return lambda: HeadHide(**kw)


def claw_prof(u):
    """A talon hooking down: full height at the root, the top edge sweeping down to a point at the bottom."""
    return 0.9 * u ** 1.4, np.ones_like(u)


def heel_prof(u):
    return 0.5 * u ** 1.3, np.ones_like(u) - 0.1 * u


def blade_prof(u):
    """The tail's blade: a narrow neck, swelling to a broad spade and drawn to a point."""
    w = np.where(u < 0.5, 0.30 + 0.70 * sstep(0.28, 0.52, u), 1.0 - (np.maximum(u - 0.5, 0.0) / 0.5) ** 1.5)
    w = np.maximum(w, 0.0)
    return 0.5 - 0.5 * w, 0.5 + 0.5 * w


def barb_prof(u):
    """A short barb: wide at the root, a straight taper to the point."""
    return np.zeros_like(u), 1.0 - u


LEG_ZONES = [(0.0, 'char', 0.0), (0.2, 'crimson', -0.12), (0.5, 'crimson', 0.0), (0.8, 'crimson', -0.1), (1.0, 'bronze', -0.05)]
BONE_ZONES = [(0.0, 'char', 0.12), (0.2, 'crimson', -0.18), (0.5, 'crimson', -0.04), (0.8, 'crimson', -0.12), (1.0, 'bronze', -0.12)]

STYLE = [
    # --- the body
    (r'^chest$', _hide(axis='z', seam='chest', seam_back='body', cw=4.0, hs=2.3, ra=3.0, cracks=0.6, crack_layer='body')),
    (r'^(pelvis|shoulder_[rl])$', _hide(axis='z', seam='body', cw=4.0, hs=2.3, ra=3.0, cracks=0.6)),
    (r'^tailroot$', _hide(axis='z', seam='tail', seam_back='tail', cw=3.6, hs=2.1, ra=2.8, cracks=0.5)),
    (r'^(withers|croup)$', lambda: Scute('z', row=3.2, cw=3.6, seam='body')),
    (r'^breast$', lambda: Plates('y', ph=2.5, bend=1.2, seam='chest')),
    (r'^keel$', lambda: Plates('z', ph=2.6, bend=1.0, seam='chest')),
    (r'^belly$', lambda: Plates('z', ph=2.6, bend=1.0, seam='body')),
    (r'^neck\d_c$', _hide(axis='z', seam='throat', seam_back='neck', cw=3.6, hs=2.1, ra=2.8, cracks=0.5, crack_layer='neck')),
    (r'^tail\d_c$', _hide(axis='z', seam='tail', seam_back='tail', cw=3.4, hs=2.0, ra=2.6, cracks=0.5)),
    # --- spines, blade, spikes
    (r'^tail_blade$', lambda: Obsidian(Thorn('z', 1, 'y', blade_prof), ridge=True, ember='tail')),
    (r'^tspike_', lambda: Obsidian(Needle('z', 1, 0.9), gloss=0.9)),
    (r'^spine_b\d$', lambda: Obsidian(Thorn('y', -1, 'z', spine_prof()), ember='body')),
    (r'^spine_[nh]\d$', lambda: Obsidian(Thorn('y', -1, 'z', spine_prof()), ember='neck')),
    (r'^spine_t\d$', lambda: Obsidian(Thorn('y', -1, 'z', spine_prof()), ember='tail')),
    (r'^(sspike|kspike|wspike)_', lambda: Obsidian(Needle('z', 1, 0.9), gloss=0.9)),
    # --- the head
    (r'^cranium$', _head(axis='z', eyes=True, mouth_roof=True, belly=1.0, back=0.1)),
    (r'^cheeks$', _head(axis='z', mouth_roof=True, belly=1.0, back=0.0)),
    (r'^brow_[rl]$', lambda: Scute('z', row=2.4, cw=2.6, edge=1.2, tone=0.04)),
    (r'^snout2?$', _head(axis='z', mouth_roof=True, belly=1.0, back=0.12)),
    (r'^nose$', _head(axis='z', mouth_roof=True, belly=1.0, back=0.0)),
    (r'^nostril_[rl]$', lambda: Nostril(axis='z', belly=1.0, back=0.0, cw=1.8, hs=1.2, ra=1.5, ao=0.4)),
    (r'^(nasal|crest)$', lambda: Scute('z', row=2.0, cw=2.0, edge=1.2, tone=0.06)),
    (r'^teeth_up2?$', lambda: Teeth('top', 1.5)),
    (r'^teeth_lo', lambda: Teeth('bottom', 1.5)),
    (r'^fang_[rl]$', lambda: Horn(0.0, 0.9, black=2.0, ring=1.0, axis='y')),
    (r'^tongue$', lambda: Mouth('tongue')),
    (r'^(jaw_c|chin)$', _head(axis='z', jaw=True, belly=0.74, back=0.0)),
    (r'^jaw_back$', _head(axis='z', jaw=True, belly=0.74, back=0.0)),
    (r'^lid_[rl]$', _head(axis='z', belly=1.0, back=0.0, tone=-0.04)),
    (r'^horn_[rl]$', lambda: Horn(0.0, 0.34)),
    (r'^horn_[rl]2$', lambda: Horn(0.31, 0.66)),
    (r'^horn_[rl]3$', lambda: Horn(0.66, 0.9)),
    (r'^horn_[rl]4$', lambda: Horn(0.86, 1.0)),
    (r'^(chorn|jspike|brow_spike)_', lambda: Horn(0.06, 1.0, black=0.62)),
    # --- legs and feet
    (r'^(leg|shin)_[fh][rl]_c$', _hide(axis='y', belly=1.0, back=0.0, zones=LEG_ZONES)),
    (r'^foot_[fh][rl]_c$', _hide(axis='z', belly=0.62, back=0.0, cw=2.8, hs=1.6, ra=2.1, ph=1.8)),
    (r'^toe_', _hide(axis='z', belly=1.0, back=0.0, cw=1.8, hs=1.3, ra=1.6, zones=LEG_ZONES)),
    (r'^claw_', lambda: Obsidian(Thorn('z', -1, 'y', claw_prof), gloss=0.7)),
    (r'^heel_', lambda: Obsidian(Thorn('z', 1, 'y', heel_prof), gloss=0.7)),
    # --- wings
    (r'^(wshoulder|elbow|wrist)_[rl]$', lambda: Scute('x', row=2.4, cw=2.4, edge=1.0, tone=0.04, seam='wing')),
    (r'^(humerus|forearm)_[rl]$', ('bone', dict(belly=1.0, back=0.2, cw=3.0, hs=1.7, ra=2.3, zones=BONE_ZONES, seam='wing', seam_back='wing', ph=3.0))),
    (r'^fing_[rl]\d$', ('bone', dict(belly=1.0, back=0.0, cw=1.8, hs=1.6, ra=2.0, zones=BONE_ZONES, seam=None, wear=0.7))),
    (r'^ftip_', lambda: Obsidian(Thorn('x', -1, 'y', barb_prof), gloss=0.7)),
    (r'^thumb_', lambda: Obsidian(Thorn('y', 1, 'x', barb_prof), gloss=0.8)),
    (r'^memb_([rl])(\d)$', ('memb', None)),
    (r'.*', lambda: Plain('crimson', 0.45)),
]


def style_for(name):
    for pat, make in STYLE:
        m = re.match(pat, name)
        if m:
            if isinstance(make, tuple):
                kind, kw = make
                if kind == 'bone':
                    return Hide(axis='x', rev=bool(re.search(r'_r\d?$', name)), **kw)
                if kind == 'memb':
                    return Membrane(int(m.group(2)), m.group(1))
            return make()
    return Plain()


def apply_styles():
    for c in model.all_cubes():
        c.paint = style_for(c.name)


# ================================================================== painting

def paint_model():
    """The whole texture (H, W, 4 uint8) and the glow layers {name: (H, W) intensity 0..1}, painted face by face."""
    W, H = model.tex_w * HD, model.tex_h * HD
    tex = np.zeros((H, W, 4), np.float64)
    layers = {k: np.zeros((H, W)) for k in GLOW_LAYERS}
    wt = world_transforms()
    for pname, c, face, A, B, D, n, rect in CUBE_FACES:
        x, y, w, h = rect
        f = Face(pname, c, face, rect, A, B, D, n, wt)
        out = c.paint.paint(f)
        tex[y:y + h, x:x + w, :3] = np.clip(out.rgb, 0, 255)
        tex[y:y + h, x:x + w, 3] = out.alpha * 255.0
        for k, v in out.glow.items():
            layers[k][y:y + h, x:x + w] = np.maximum(layers[k][y:y + h, x:x + w], np.where(out.alpha > 0.5, v, 0.0))
    return np.round(tex).astype(np.uint8), layers


def glow_map(layers, name):
    """One glow map: each layer it carries lit in its own colour, additive where layers overlap; clear elsewhere."""
    H, W = next(iter(layers.values())).shape
    acc = np.zeros((H, W, 3))
    on = np.zeros((H, W), bool)
    for layer, (col, gain) in GLOW_MAPS[name].items():
        v = np.clip(layers[layer] * gain, 0, 1)
        acc += v[..., None] * np.array(col, float)
        on |= v > 0.01
    out = np.zeros((H, W, 4), np.uint8)
    out[..., :3] = np.clip(acc, 0, 255).astype(np.uint8)
    out[..., 3] = np.where(on, 255, 0)
    out[..., :3] = np.where(on[..., None], out[..., :3], 0)
    return out


_PAINTED = {}


def painted():
    if not _PAINTED:
        tex, layers = paint_model()
        _PAINTED['tex'], _PAINTED['layers'] = tex, layers
        _PAINTED['glow'] = {k: glow_map(layers, k) for k in GLOW_MAPS}
    return _PAINTED


def __getattr__(name):
    """TEX_HD (the painted texture, painted on first use) and GLOW_HD (the glow maps by name)."""
    if name == 'TEX_HD':
        return painted()['tex']
    if name == 'GLOW_HD':
        return painted()['glow']
    raise AttributeError(name)


# ================================================================== posing and rendering

L0 = unit([-0.2, -1.0, -0.7]); L1 = unit([0.2, -1.0, 0.7])


def posed_quads(pose=None, hidden=()):
    wt = world_transforms(pose)
    quads = []
    for name, c, face, A, B, D, n, rect in CUBE_FACES:
        if name in hidden:
            continue
        R, T = wt[name]
        quads.append((T + R @ A, T + R @ B, T + R @ D, R @ n, rect))
    return quads


def camera(yaw, elev, scale, size, center=(0.0, 4.0, 0.0)):
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    ce, se = math.cos(math.radians(elev)), math.sin(math.radians(elev))
    W, H = size
    cx, cyy, cz = center

    def project(p):
        x, y, z = p[..., 0] - cx, p[..., 1] - cyy, p[..., 2] - cz
        x2 = x * cy - z * sy
        z2 = x * sy + z * cy
        y3 = y * ce - z2 * se
        z3 = z2 * ce + y * se
        return np.stack([W / 2 + x2 * scale, H / 2 + y3 * scale, z3], -1)
    view = np.array([sy * ce, se, cy * ce])   # direction the camera looks along, in model space
    return project, view


def raster(quads, cam, size, tex, glow=None, bg=(46, 50, 62), ground=True, ground_shift=0.0, alpha_cut=25):
    project, view = cam
    W, H = size
    img = np.zeros((H, W, 3), float); img[...] = bg
    zbuf = np.full((H, W), 1e9)
    T = np.asarray(tex, float)
    G = None if glow is None else np.asarray(glow, float)
    ys, xs = np.mgrid[0:H, 0:W]
    items = list(quads)
    if ground:
        # A ground grid at y = 24: one cell per block (8 px), drawn first so the feet can be checked against it
        items = [('ground',)] + items
    for q in items:
        if isinstance(q[0], str):
            ext = 120
            corners = np.array([[-ext, 24, -ext], [ext, 24, -ext], [-ext, 24, ext]], float)
            a, b, d = project(corners)
            e1, e2 = b - a, d - a
            det = e1[0] * e2[1] - e1[1] * e2[0]
            if abs(det) < 1e-6:
                continue
            px = xs + 0.5 - a[0]; py = ys + 0.5 - a[1]
            s = (px * e2[1] - py * e2[0]) / det
            t = (e1[0] * py - e1[1] * px) / det
            inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
            depth = a[2] + s * e1[2] + t * e2[2]
            gx = (s * 2 * ext - ext) ; gz = (t * 2 * ext - ext) + ground_shift
            line = (np.abs(((gx + 4) % 8) - 4) < 0.35) | (np.abs(((gz + 4) % 8) - 4) < 0.35)
            col = np.where(line[..., None], np.array([78, 84, 98.0]), np.array([58, 62, 74.0]))
            ok = inside
            img[ok] = col[ok]
            zbuf[ok] = depth[ok] + 0.05
            continue
        A, B, D, n, (u0, v0, w, h) = q
        if np.dot(n, view) > 1e-4:      # facing away (the game culls these)
            continue
        a, b, d = project(np.array([A, B, D]))
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        corners = [a, b, d, b + e2]
        x0 = int(max(0, math.floor(min(c[0] for c in corners)))); x1 = int(min(W, math.ceil(max(c[0] for c in corners)) + 1))
        y0 = int(max(0, math.floor(min(c[1] for c in corners)))); y1 = int(min(H, math.ceil(max(c[1] for c in corners)) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]; py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        tu = np.clip(u0 + np.floor(s * w).astype(int), u0, u0 + w - 1)
        tv = np.clip(v0 + np.floor(t * h).astype(int), v0, v0 + h - 1)
        texel = T[tv, tu]
        ok = inside & (texel[..., 3] > alpha_cut) & (depth < zbuf[y0:y1, x0:x1])
        light = min(1.0, 0.4 + 0.6 * (max(0.0, float(np.dot(n, L0))) + max(0.0, float(np.dot(n, L1)))))
        col = texel[..., :3] * light
        if G is not None:
            col = col + G[tv, tu][..., :3] * (G[tv, tu][..., 3:4] / 255.0)
        region = img[y0:y1, x0:x1]
        region[ok] = col[ok]
        zbuf[y0:y1, x0:x1][ok] = depth[ok]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


def render(pose=None, yaw=35, elev=20, scale=2.0, size=(420, 360), center=(0.0, 4.0, 0.0), tex=None, glow=None,
           hidden=(), ground=True, ground_shift=0.0):
    return raster(posed_quads(pose, hidden), camera(yaw, elev, scale, size, center), size, tex if tex is not None else painted()['tex'],
                  glow, ground=ground, ground_shift=ground_shift)



def pack_atlas(m):
    """Place every cube's box-UV layout in the atlas: big ones first, each at the first spot (top to bottom, left to right on the
    declared grid) where the two strips it really occupies (the cross's arms, at HD, from the true fractional sizes) are free,
    so small cubes drop into the voids at the corners of big ones. Raises if the atlas is too small."""
    W, H = m.tex_w, m.tex_h
    occ = np.zeros((H * HD, W * HD), bool)
    area = lambda c: (2 * c.size[2] + 2 * c.size[0]) * (c.size[2] + c.size[1])
    cubes = sorted(m.all_cubes(), key=lambda c: (-area(c), c.name))
    hd = lambda v: int(round(v * HD))
    for c in cubes:
        w, h, d = c.size
        for v in (w, h, d):
            assert abs(v * HD - round(v * HD)) < 1e-6, f'{c.name}: size {v} is not a whole number of HD texels'
        pieces = [(hd(d), 0, hd(2 * w), hd(d)), (0, hd(d), hd(2 * d + 2 * w), hd(h))]
        pieces = [q for q in pieces if q[2] > 0 and q[3] > 0]
        tw, th = math.ceil(2 * d + 2 * w), math.ceil(d + h)
        S = np.zeros((H * HD + 1, W * HD + 1), np.int32)
        S[1:, 1:] = occ.cumsum(0).cumsum(1)
        nv, nu = H - th + 1, W - tw + 1
        ok = np.ones((nv, nu), bool)
        for dx, dy, pw, ph in pieces:
            ys = HD * np.arange(nv)[:, None] + dy
            xs = HD * np.arange(nu)[None, :] + dx
            win = S[ys + ph, xs + pw] - S[ys, xs + pw] - S[ys + ph, xs] + S[ys, xs]
            ok &= win == 0
        pos = np.argwhere(ok)
        if not len(pos):
            raise SystemExit(f'{m.name}: texture too small for {c.name}')
        y, x = pos[0]
        for dx, dy, pw, ph in pieces:
            occ[HD * y + dy:HD * y + dy + ph, HD * x + dx:HD * x + dx + pw] = True
        c.uv = (int(x), int(y))
    return int(np.nonzero(occ.any(axis=1))[0].max()) // HD + 1


ROWS_USED = pack_atlas(model)
_collect()
apply_styles()


# ================================================================== checks

def check_atlas():
    """Every face's rectangle lies inside the atlas and on no other face's; every face that should show has paint on it."""
    W, H = model.tex_w * HD, model.tex_h * HD
    occ = np.zeros((H, W), np.int32)
    problems = []
    for pname, c, face, A, B, D, n, (x, y, w, h) in CUBE_FACES:
        if x < 0 or y < 0 or x + w > W or y + h > H:
            problems.append(f'{c.name}.{face} leaves the atlas')
            continue
        occ[y:y + h, x:x + w] += 1
    over = int((occ > 1).sum())
    if over:
        problems.append(f'{over} texels belong to more than one face')
    tex = painted()['tex']
    for pname, c, face, A, B, D, n, (x, y, w, h) in CUBE_FACES:
        solid = float((tex[y:y + h, x:x + w, 3] > 127).mean())
        cut = isinstance(c.paint, (Membrane, Obsidian)) or c.name.startswith('tongue')
        if solid == 0.0 and not cut:
            problems.append(f'{c.name}.{face} is empty')
        elif solid < 1.0 and not cut and not (isinstance(c.paint, (Teeth, Mouth))):
            problems.append(f'{c.name}.{face} has holes ({solid:.2f} solid)')
    return problems, int((occ > 0).sum())


# ================================================================== previews

AWAKE_HIDDEN = ('lids',)


def jaw_open(deg):
    """A pose with the jaw swung open [deg] degrees (for the previews)."""
    return {'jaw': (rotm((deg, 0, 0)), np.zeros(3))}


def subtree(name):
    """[name] and every part under it."""
    out = {name}
    for ch, par in PARENT.items():
        if par in out:
            out.add(ch)
    for _ in range(8):
        for ch, par in PARENT.items():
            if par in out:
                out.add(ch)
    return out


def fit_view(yaw, elev, size, pose=None, only=None, hidden=AWAKE_HIDDEN, margin=0.06):
    """The scale and centre that frame the model (or just the parts in [only]) in a view of [size]."""
    wt = world_transforms(pose)
    pts = []
    for name, c, face, A, B, D, n, rect in CUBE_FACES:
        if name in hidden or (only is not None and name not in only):
            continue
        R, T = wt[name]
        a, b, d = T + R @ A, T + R @ B, T + R @ D
        pts += [a, b, d, b + d - a]
    pts = np.array(pts)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    ce, se = math.cos(math.radians(elev)), math.sin(math.radians(elev))
    x2 = pts[:, 0] * cy - pts[:, 2] * sy
    z2 = pts[:, 0] * sy + pts[:, 2] * cy
    y3 = pts[:, 1] * ce - z2 * se
    w, h = size
    sc = min(w * (1 - 2 * margin) / (x2.max() - x2.min()), h * (1 - 2 * margin) / (y3.max() - y3.min()))
    X2, Y3 = (x2.max() + x2.min()) / 2, (y3.max() + y3.min()) / 2
    return sc, (X2 * cy, Y3 / ce, -X2 * sy)


def _shot(yaw, elev, size, scale=None, center=None, tex=None, glow=None, hidden=AWAKE_HIDDEN, ground=True, ss=2, bg=(46, 50, 62), pose=None,
          only=None, margin=0.06):
    if scale is None:
        scale, center = fit_view(yaw, elev, size, pose, only, hidden, margin)
    big = raster(posed_quads(pose, hidden), camera(yaw, elev, scale * ss, (size[0] * ss, size[1] * ss), center), (size[0] * ss, size[1] * ss),
                 painted()['tex'] if tex is None else tex, glow, bg=bg, ground=ground)
    return big.resize(size, Image.LANCZOS)


def _label(img, text):
    d = ImageDraw.Draw(img)
    d.text((11, 9), text, fill=(0, 0, 0))
    d.text((10, 8), text, fill=(226, 230, 244))
    return img


def preview_sheet():
    P = painted()
    head = subtree('head')
    nowing = tuple(sorted(subtree('wing_r') | subtree('wing_l')))     # close-ups: the wings would only clutter the backdrop
    tiles = [
        ('3/4 front', dict(yaw=32, elev=14, ground=True)),
        ('right side', dict(yaw=90, elev=6, ground=False)),
        ('3/4 rear', dict(yaw=146, elev=22, ground=True)),
        ('from above', dict(yaw=0, elev=82, ground=False)),
        ('head', dict(yaw=40, elev=10, ground=False, only=head, margin=0.04, hidden=AWAKE_HIDDEN + nowing)),
        ('head, front', dict(yaw=0, elev=6, ground=False, only=head, margin=0.04, hidden=AWAKE_HIDDEN + nowing)),
        ('enraged (glow2)', dict(yaw=30, elev=12, ground=True, glow=P['glow']['glow2'])),
        ('roaring, breath glow', dict(yaw=-38, elev=10, ground=False, glow=P['glow']['glow_breath'], pose=jaw_open(34), only=head, margin=0.04,
                                      hidden=AWAKE_HIDDEN + nowing)),
        ('asleep (lids shut)', dict(yaw=36, elev=10, ground=False, hidden=nowing, only=head, margin=0.04)),
    ]
    W, H = 600, 430
    cols = 3
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * W, rows * H), (46, 50, 62))
    for i, (label, kw) in enumerate(tiles):
        img = _shot(size=(W, H), **kw)
        sheet.paste(_label(img, label), ((i % cols) * W, (i // cols) * H))
    return sheet


def glow_preview_sheet():
    P = painted()
    dark = (P['tex'].astype(float) * np.array([0.26, 0.26, 0.26, 1.0])).astype(np.uint8)
    head = subtree('head')
    nowing = AWAKE_HIDDEN + tuple(sorted(subtree('wing_r') | subtree('wing_l')))
    W, H = 640, 460
    names = [('glow', 'dragon_glow: eyes, always on', 0), ('glow_breath', 'dragon_glow_breath: drawing breath', 30), ('glow2', 'dragon_glow2: enraged', 30)]
    sheet = Image.new('RGB', (3 * W, 2 * H), (18, 20, 28))
    for i, (key, label, open_deg) in enumerate(names):
        g = P['glow'][key]
        a = _shot(yaw=32, elev=14, size=(W, H), tex=dark, glow=g, bg=(18, 20, 28), ground=False)
        b = _shot(yaw=-36, elev=8, size=(W, H), tex=dark, glow=g, bg=(18, 20, 28), ground=False, only=head, margin=0.05, hidden=nowing,
                  pose=jaw_open(open_deg) if open_deg else None)
        sheet.paste(_label(a, label), (i * W, 0))
        sheet.paste(b, (i * W, H))
    return sheet


# ================================================================== files

def deterministic_uuids(text):
    import uuid
    seen = {}

    def sub(m):
        u = m.group(0)
        if u not in seen:
            seen[u] = str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/dragon/{len(seen)}'))
        return seen[u]
    return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', sub, text)


def write_all(out, prev, art):
    P = painted()
    ent = os.path.join(out, 'textures', 'entity')
    os.makedirs(ent, exist_ok=True)
    os.makedirs(prev, exist_ok=True)
    os.makedirs(art, exist_ok=True)
    tex = Image.fromarray(P['tex'], 'RGBA')
    tex.save(os.path.join(ent, 'dragon.png'))
    for key, fname in (('glow', 'dragon_glow.png'), ('glow_breath', 'dragon_glow_breath.png'), ('glow2', 'dragon_glow2.png')):
        Image.fromarray(P['glow'][key], 'RGBA').save(os.path.join(ent, fname))
    open(os.path.join(prev, 'dragon.kt.txt'), 'w').write(model.kotlin() + '\n')
    open(os.path.join(prev, 'parts.txt'), 'w').write('\n'.join(p.name for p in model.all_parts()) + '\n')
    bb = model.bbmodel(tex, 'dragon')
    bb['textures'][0]['width'] = model.tex_w * HD
    bb['textures'][0]['height'] = model.tex_h * HD
    open(os.path.join(art, 'dragon.bbmodel'), 'w').write(deterministic_uuids(json.dumps(bb)))
    preview_sheet().save(os.path.join(prev, 'dragon_preview.png'))
    glow_preview_sheet().save(os.path.join(prev, 'dragon_glow_preview.png'))
    problems, used = check_atlas()
    print(f'{len(list(model.all_parts()))} parts, {len(list(model.all_cubes()))} cubes, atlas rows used {ROWS_USED}/{model.tex_h}, {used} texels on faces')
    for p in problems:
        print('PROBLEM', p)
    return problems


if __name__ == '__main__':
    if len(sys.argv) < 4:
        raise SystemExit('usage: dragon.py <assets dir> <preview dir> <art/models dir>')
    sys.exit(1 if write_all(sys.argv[1], sys.argv[2], sys.argv[3]) else 0)
