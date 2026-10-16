"""One of the Maelgrasp's arms: the colossal deep-sea horror's reaching, seizing limb, drawn from the thirteen joints the game works out for it
(`sea/Tentacle.kt`, in `MaelgraspArmRenderState.joints`). Twelve segments, each a part of its own at the root of the model, laid joint to
joint by `MaelgraspArmModel.setupAnim`: thick and muscular at the root and tapering to a hooked tip, a drowned slate-blue skin shading to
bruise-violet and mottled with pale patches, crusted with barnacles along its outer side and scored with old scars and harpoon punctures,
a pale bone-ivory underside with a groove down it and two rows of suckers (bigger at the root, each ringed with small hooked teeth) that
pulse and clench, a ragged membrane frill along the outer edge of the last third that flutters, a hooked tip that tightens its curl as the
arm grips, faint sickly teal veins under the skin (the glow map) and, where it has been hacked through, a raw ragged stump of dark red-black
flesh (two of them: one on the end of what is left on the arm, one on the start of a length that falls away on its own).

Minecraft model space: pixels, y down, the creature faces -z, its right is -x. The arm's own frame (blocks, y up, z toward the ship, x to its
right) is model px = -16 x (x, y, z). A segment part has its own axis along its local +y (from joint i to joint i + 1) and the sucker side
along its local -z; its rotation is carried along the arm by parallel transport (the sucker side starts toward the ship at the root and never
flips), and is turned into the part's xRot, yRot and zRot (the game applies them as Rz Ry Rx). At rest (no setupAnim) the arm stands straight
up from its root with the suckers toward -z, which is how the .bbmodel and the painting see it.

The texture is HD: the model declares 384x256 and the PNG is painted at twice that (the UVs are normalised). Nothing is painted from character
grids: each texel of each face is turned back into its place on the arm (how far along it, and how far round it from the underside) and the
paint is a function of that place, so wrinkles, veins, scars and barnacles run on over the edges of a box and across the slices of a segment.
The glow map holds only the veins under the skin (and a faint trace of them in the frill). The stumps glow nothing.

Python's `pose()` is the Kotlin `setupAnim` line for line (the previews run it; `sea_monsters.py` compiles the Kotlin and compares the two).
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np

import sea_kit as K
import sea_tentacle as T
from sea_kit import Cu, part, SIDES, ALL, Out, Model, HD
from ship import ramp, sstep, hash01, fbm, fbm2, mixc, seed_of, stripes, seg_dist

TEX_W, TEX_H = 384, 256
NAME = 'maelgrasp_arm'

# ================================================================== what the arm is made to

SEGS = T.SEGMENTS
LEN = [16.0 * v for v in T.LENGTHS]                    # px, each segment's rest length
RAD = [16.0 * v for v in T.RADII]                      # px, the radius at each of the thirteen joints
S0 = [sum(LEN[:i]) for i in range(SEGS)]               # px along the arm where each segment starts
S_ALL = sum(LEN)
ROOT_Y = 16.0 * T.ROOT_DEPTH                           # the root joint's y at rest (below the surface, y down)
S_JOINT = [sum(LEN[:i]) for i in range(SEGS + 1)]

SLICES = [3 if i < 4 else 2 if i < 9 else 1 for i in range(SEGS)]   # boxes along a segment, so it tapers in steps no bigger than half a px
COPIES = [4 if RAD[i] >= 7.0 else 3 if RAD[i] >= 5.5 else 2 for i in range(SEGS)]   # turned copies of each slice: a 16-gon at the root, a 12-gon, an octagon
SUCKERS = [3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 1]         # suckers in each of a segment's two rows
SUCKER_ANGLE = 33.0 * K.D2R                            # where round the arm the two rows lie, from the underside's midline
FRILL_FIRST = 8                                        # the frill: two panels on each of the last third's segments
FRILLS = 2 * (SEGS - FRILL_FIRST)
HOOK = 3                                               # the hook's pieces
FRILL_REST = -23.0                                     # degrees: the frill panels are raked back toward the tip
STUMP_R = 8.0                                          # the radius (px) the stump is built to; it is scaled to the joint it sits on
BARNACLES = [5, 5, 4, 4, 3, 3, 2, 1, 0, 0, 0, 0]

GLOW_LAYERS = {'vein': ((58, 204, 164), 0.85)}

# ================================================================== palettes

SLATE = [(15, 22, 36), (26, 38, 58), (40, 56, 80), (58, 78, 106), (82, 104, 130), (112, 134, 156)]
BRUISE = [(24, 13, 34), (42, 24, 58), (64, 38, 82), (92, 58, 108), (122, 82, 132), (152, 112, 158)]
IVORY = [(44, 38, 44), (96, 86, 88), (156, 146, 138), (204, 194, 176), (230, 222, 200), (246, 240, 222)]
SUCK = [(30, 8, 22), (62, 18, 40), (102, 36, 62), (146, 64, 84), (190, 104, 112), (224, 150, 140)]
SHELL = [(30, 32, 34), (66, 70, 70), (108, 112, 108), (154, 156, 146), (196, 196, 180), (226, 224, 206)]
FLESH = [(10, 3, 6), (24, 6, 11), (44, 9, 16), (70, 15, 24), (102, 26, 36), (140, 46, 54)]
BONE = [(50, 44, 40), (98, 90, 76), (148, 138, 114), (190, 182, 154), (222, 216, 190)]
HORN = [(7, 7, 11), (16, 14, 22), (30, 26, 38), (50, 44, 60), (80, 72, 90), (122, 112, 128)]
FRILLC = [(20, 26, 46), (34, 50, 76), (52, 80, 108), (80, 112, 134), (116, 148, 160), (160, 190, 192)]
for _n in ('SLATE', 'BRUISE', 'IVORY', 'SUCK', 'SHELL', 'FLESH', 'BONE', 'HORN', 'FRILLC'):
    globals()[_n] = np.array(globals()[_n], float)


def arm_radius(S):
    return np.interp(S, S_JOINT, RAD)


# ================================================================== the geometry

def slice_edges(i):
    """[(y0, y1, radius px)] of segment i's slices (y from the joint it starts at). The first reaches back past the joint (hidden inside the
    last slice of the segment before it when the arm is straight, filling the outside of a bend when it is not)."""
    n = SLICES[i]
    L = LEN[i]
    out = []
    for j in range(n):
        ya, yb = L * j / n, L * (j + 1) / n
        r = RAD[i] + (RAD[i + 1] - RAD[i]) * 0.5 * (ya + yb) / L
        back = min(3.0, 0.42 * RAD[i]) if (j == 0 and i > 0) else 0.0
        out.append((ya - back, yb, r, ya))
    return out


def hull_radius(S):
    return float(np.interp(S, S_JOINT, RAD))


def segment_part(i, rng):
    """Segment i: slices of 2 or 3 turned boxes (the turned ones hang from the first as child parts), barnacles, suckers, frill panels and, on
    the last, the hook."""
    copies = COPIES[i]
    L = LEN[i]
    sl = slice_edges(i)
    by_copy = [[] for _ in range(copies)]
    for j, (ya, yb, r, y_start) in enumerate(sl):
        a = K.star_half(r, copies)
        faces = SIDES | ({'bottom'} if (i == SEGS - 1 and j == len(sl) - 1) else set()) | ({'top'} if (i == 0 and j == 0) else set())
        for k in range(copies):
            turn = 90.0 * k / copies
            by_copy[k].append(Cu((-a, ya + 0.1 * k, -a), (2 * a, yb - ya, 2 * a), 'skin', f'sg{i}_{j}_{k}', faces=faces, seg=i, turn=turn, slice=j,
                                 copy=k, s0=S0[i], a=a))
    kids = []
    for k in range(1, copies):
        kids.append(part(f'seg{i}_c{k}', (0, 0, 0), (0, 90.0 * k / copies, 0), by_copy[k]))
    # barnacles: little crusts on the outer side, axis aligned and half sunk in the skin
    for b in range(BARNACLES[i]):
        phi = math.radians(rng.uniform(112, 248))
        yb = rng.uniform(0.12, 0.88) * L
        rr = hull_radius(S0[i] + yb) * 0.97
        w, d, h = rng.choice([2.0, 2.5, 3.0, 3.5]), rng.choice([2.0, 2.5, 3.0]), rng.choice([1.5, 2.0, 2.5])
        cx, cz = (rr + 0.4 * d) * math.sin(phi), -(rr + 0.4 * d) * math.cos(phi)
        mouth = 'back' if abs(math.degrees(phi) - 180.0) < 35.0 else ('left' if phi < math.pi else 'right')
        by_copy[0].append(Cu((cx - w / 2, yb - h / 2, cz - d / 2), (w, h, d), 'barnacle', f'ba{i}_{b}', faces=ALL, seg=i, mouth=mouth))
    # suckers: two staggered rows, each standing square to the surface it sits on, a little unevenly as real ones do
    n = SUCKERS[i]
    for k in range(2 * n):
        m, side = divmod(k, 2)
        sigma = 1.0 if side == 0 else -1.0
        y = L * (m + (0.34 if side == 0 else 0.84)) / n + rng.uniform(-0.3, 0.3)
        rr = hull_radius(S0[i] + y) * 0.965
        d = K.half(min(6.0, max(1.5, 0.64 * rr + (0.5 if rng.random() < 0.2 else 0.0) - (0.5 if rng.random() < 0.2 else 0.0))))
        hgt = 1.0 if d >= 3.0 else 0.5
        ang = SUCKER_ANGLE + math.radians(rng.uniform(-3.0, 3.0))
        x, z = sigma * rr * math.sin(ang), -rr * math.cos(ang) * 0.99
        c = Cu((-d / 2, -d / 2, -hgt), (d, d, hgt + 1.2), 'sucker', f'su{i}_{k}', faces={'front', 'left', 'right', 'top', 'bottom'}, seg=i, d=d, h=hgt,
               big=d >= 4.0, sigma=sigma, spin=float(rng.uniform(0, 6.28)))
        kids.append(part(f'su{i}_{k}', (x, y, z), (0, -sigma * math.degrees(ang), 0), [c]))
    # the frill: two ragged panels along the outer edge, raked toward the tip, pivoting on the line they stand on
    if i >= FRILL_FIRST:
        for half_ in range(2):
            j = 2 * (i - FRILL_FIRST) + half_
            yc = L * (0.25 + 0.5 * half_)
            ln = K.half(L * 0.5 + 1.6)
            rr = hull_radius(S0[i] + yc) * 0.9
            hf = K.half(1.9 * hull_radius(S0[i] + yc) + 2.0)
            c = Cu((-0.25, -ln / 2, -0.6), (0.5, ln, hf + 0.6), 'frill', f'fr{j}', faces={'left', 'right'}, seg=i, panel=j, half=half_, ln=ln, hf=hf, s0=S0[i] + yc)
            kids.append(part(f'fr{j}', (0, yc, rr), (FRILL_REST, 0, 0), [c]))
    if i == SEGS - 1:
        kids.append(hook_chain(i))
    return part(f'seg{i}', (0, ROOT_Y - S0[i], 0), (0, 0, 180), by_copy[0], kids)


def hook_chain(i):
    """The hooked tip: three pieces, each hung from the end of the one before and curled toward the suckers' side."""
    L = LEN[i]
    sizes = [(2.0, 4.5), (1.5, 4.0), (1.0, 3.5)]
    chain = None
    for k in range(HOOK - 1, -1, -1):
        w, ln = sizes[k]
        c = Cu((-w / 2, 0.0, -w / 2), (w, ln, w), 'hook', f'hk{k + 1}', faces=ALL, k=k, ln=ln, w=w)
        piv = (0.0, L - 0.4, 0.0) if k == 0 else (0.0, sizes[k - 1][1], 0.0)
        chain = part(f'hk{k + 1}', piv, (-26.0, 0, 0), [c], [chain] if chain else [])
    return chain


def stump_part(name, rng):
    """A raw ragged cap, built on a unit of STUMP_R px: a plug of dark flesh a little bigger than a cut would leave, a ring of torn shards
    round it, a nub of pale cartilage in the middle and a few strips of skin hanging back. Its local +y points out of the stump."""
    a = K.star_half(STUMP_R * 0.95, 2)
    plug = Cu((-a, -1.5, -a), (2 * a, 5.0, 2 * a), 'stump', f'{name}_plug', faces=SIDES | {'bottom'}, kind='flesh', turn=0.0, a=a)
    cubes = [plug]
    for k in range(8):
        ang = (k + rng.uniform(-0.28, 0.28)) * math.pi / 4
        rad = STUMP_R * rng.uniform(0.62, 0.86)
        w, d = rng.choice([2.0, 2.5, 3.0]), rng.choice([2.0, 2.5, 3.0])
        h = rng.choice([2.5, 3.5, 4.5, 5.5, 6.5])
        cubes.append(Cu((rad * math.cos(ang) - w / 2, 2.0, rad * math.sin(ang) - d / 2), (w, h, d), 'stump', f'{name}_sh{k}', faces=ALL, kind='shard', ang=ang))
    cubes.append(Cu((-1.5, 2.5, -1.5), (3.0, 4.0, 3.0), 'stump', f'{name}_core', faces=ALL, kind='bone'))
    kids = [part(f'{name}_c1', (0, 0.1, 0), (0, 45.0, 0), [Cu((-a, -1.5, -a), (2 * a, 5.0, 2 * a), 'stump', f'{name}_plug1', faces=SIDES, kind='flesh', turn=45.0, a=a)])]
    return part(name, (0, 0, 0), (0, 0, 0), cubes, kids)


GROUPS = []


def build():
    """The model: twelve segments (with everything that hangs from them) and the two stump caps."""
    K.REG.clear()
    GROUPS.clear()
    rng = np.random.default_rng(seed_of(NAME, 'build'))
    segs = [segment_part(i, rng) for i in range(SEGS)]
    caps = [stump_part('stump', np.random.default_rng(seed_of(NAME, 'stump'))), stump_part('stump_start', np.random.default_rng(seed_of(NAME, 'stump_start')))]
    GROUPS.append(('segments', segs))
    GROUPS.append(('caps', caps))
    return Model(NAME, TEX_W, TEX_H, segs + caps)


# ================================================================== painting

def worley2(x, y, cx, cy, seed):
    """Nearest and second nearest of a jittered grid of points ([cx] x [cy] cells): (F1, F2, id, vx, vy), the distances in cells, a random
    number for the nearest point and the vector (cells) from that point to the texel."""
    ix, iy = np.floor(x / cx), np.floor(y / cy)
    f1 = np.full(np.shape(x), 9.0)
    f2 = np.full(np.shape(x), 9.0)
    ident = np.zeros(np.shape(x))
    vx = np.zeros(np.shape(x))
    vy = np.zeros(np.shape(x))
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            jx, jy = ix + dx, iy + dy
            px = jx + hash01(jx, jy, 0, seed)
            py = jy + hash01(jx, jy, 1, seed + 7)
            ox, oy = x / cx - px, y / cy - py
            d = np.hypot(ox, oy)
            closer = d < f1
            f2 = np.where(closer, f1, np.minimum(f2, d))
            ident = np.where(closer, hash01(jx, jy, 2, seed + 13), ident)
            vx = np.where(closer, ox, vx)
            vy = np.where(closer, oy, vy)
            f1 = np.where(closer, d, f1)
    return f1, f2, ident, vx, vy


def skin_coords(f):
    """(S, phi, x, y, z): how far along the arm (px from the root), the angle round it from the underside's midline (radians, + toward +x,
    the sucker side at 0 and the outer side at +-pi), and the texel's place in the segment's own frame."""
    x, y, z = K.turned(f, f.kw['turn'], 'y')
    S = f.kw['s0'] + y
    phi = np.arctan2(x, -z)
    return S, phi, x, y, z


def scars_for(seg, rng):
    """Old scars on segment [seg]: (S, c from the outer midline, angle, length, width) in px."""
    out = []
    for _ in range(2 if seg < 9 else 1):
        out.append((S0[seg] + rng.uniform(0.15, 0.85) * LEN[seg], rng.uniform(-0.42, 0.42) * math.pi * hull_radius(S0[seg] + 0.5 * LEN[seg]),
                    rng.uniform(0, math.pi), rng.uniform(6.0, 15.0), rng.uniform(0.7, 1.2)))
    return out


def make_veins():
    """The arm's veins as curves in (S along the arm in px, psi the angle round it from the outer midline): seven that run most of its length,
    wandering a little, and branches that fork off them at a shallow angle and fade out. Each is (psi at S0, wander, S0, S1, fade-in, thickness)."""
    rng = np.random.default_rng(seed_of('arm', 'veins'))
    mains, branches = [], []
    for k, psi0 in enumerate([-1.55, -1.0, -0.5, 0.0, 0.45, 1.0, 1.55]):
        m = dict(psi0=psi0 + rng.uniform(-0.1, 0.1), a1=rng.uniform(0.05, 0.10), f1=rng.uniform(0.035, 0.06), p1=rng.uniform(0, 6.28),
                 a2=rng.uniform(0.015, 0.04), f2=rng.uniform(0.09, 0.14), p2=rng.uniform(0, 6.28), s0=rng.uniform(0.0, 60.0),
                 s1=S_ALL * rng.uniform(0.80, 0.97), w=1.0 if k == 3 else rng.uniform(0.65, 0.9), k=k)
        mains.append(m)
        for _ in range(int(rng.integers(2, 5))):
            sb = rng.uniform(m['s0'] + 10.0, m['s1'] - 25.0)
            branches.append(dict(parent=m, sb=sb, dir=rng.choice([-1.0, 1.0]), slope=rng.uniform(0.004, 0.011), ln=rng.uniform(18.0, 48.0),
                                 a=rng.uniform(0.01, 0.03), f=rng.uniform(0.08, 0.16), p=rng.uniform(0, 6.28), w=rng.uniform(0.5, 0.7)))
    return mains, branches


VEINS = make_veins()


def psi_main(m, S):
    return m['psi0'] + m['a1'] * np.sin(S * m['f1'] + m['p1']) + m['a2'] * np.sin(S * m['f2'] + m['p2'])


def vein_mask(S, psi, R):
    """1 along the veins at the texels' places (S px along the arm, psi radians round from the outer midline, R px the arm's radius there)."""
    mains, branches = VEINS
    out = np.zeros(np.shape(S))
    for m in mains:
        d = np.abs(psi - psi_main(m, S)) * R
        rng_ = sstep(m['s0'], m['s0'] + 14.0, S) * (1.0 - sstep(m['s1'] - 16.0, m['s1'], S))
        line = (1.0 - sstep(0.12 * m['w'], 0.30 + 0.34 * m['w'], d)) * rng_
        pulse = 0.65 + 0.35 * np.sin(S * 0.07 + m['k'] * 1.9)
        out = np.maximum(out, line * pulse)
    for b in branches:
        m = b['parent']
        t = (S - b['sb']) / b['ln']
        pb = psi_main(m, b['sb']) + b['dir'] * b['slope'] * (S - b['sb']) * np.maximum(1.0, 1.0 + 0.0 * t) + b['a'] * np.sin(S * b['f'] + b['p']) * np.clip(t, 0, 1)
        d = np.abs(psi - pb) * R
        inside = (t >= 0.0) & (t <= 1.0)
        fade = (1.0 - np.clip(t, 0, 1)) ** 0.8
        line = (1.0 - sstep(0.10 * b['w'], 0.28 + 0.30 * b['w'], d)) * inside * fade
        out = np.maximum(out, line * 0.85)
    return out


def paint_skin(f):
    S, phi, x, y, z = skin_coords(f)
    return skin_core(f, S, phi, arm_radius(S), S / S_ALL, f.kw['seg'], 'arm')


def skin_core(f, S, phi, R, tip, i, key, crust_k=1.0, scar_k=1.0, vent_w=1.0):
    """The arm's skin at the place (S px along it, phi round it from the underside, R px its radius there, [tip] how far toward the tip, in 0..1): the
    colour (Out with its vein glow). [i] and [key] seed its scars and its pebbling; the head's arm-roots paint themselves with it too."""
    u = np.abs(phi) / math.pi
    cd = np.where(phi >= 0, phi - math.pi, phi + math.pi) * R
    cu = phi * R
    seed = seed_of(key, 'skin')
    sn, cs = np.sin(phi), np.cos(phi)
    P3 = np.stack([S / 2.2, R * sn, -R * cs], -1)
    PL = np.stack([S / 5.0, R * sn, -R * cs], -1)
    big = fbm(P3, 13.0, seed + 1, 3)
    mid = fbm(P3, 4.6, seed + 2, 3)
    fine = fbm(np.stack([S / 1.3, R * sn / 1.6, -R * cs / 1.6], -1), 1.1, seed + 3, 2)
    edge = (fbm(P3, 3.2, seed + 4, 2) - 0.5) * 0.24
    vent = 1.0 - sstep(0.31 * vent_w + edge, 0.43 * vent_w + edge, u)
    # ---- the outer skin: drowned slate-blue, going to bruise-violet toward the tip and in patches, pale where waterlogged
    t = 0.34 + 0.42 * (mid - 0.5) + 0.30 * (big - 0.5) + 0.05 * (fine - 0.5)
    base = ramp(SLATE, t + 0.05 * tip)
    violet = ramp(BRUISE, t + 0.06)
    k_v = np.clip(0.75 * sstep(0.30, 1.0, tip) + 0.85 * sstep(0.52, 0.66, fbm(P3, 17.0, seed + 5, 2)), 0.0, 1.0)
    dorsal = mixc(base, violet, k_v)
    pale = sstep(0.60, 0.74, fbm(P3, 10.0, seed + 6, 3)) * 0.42
    dorsal = mixc(dorsal, np.array([128.0, 146.0, 150.0]) * (0.72 + 0.4 * mid)[..., None], pale)
    # creases: rings round the arm, long cords of muscle, a leathery pebbling
    warp = 2.0 * (fbm(P3, 5.0, seed + 7, 2) - 0.5)
    ring = stripes(S + warp, 3.4, 0.14, 0.08)
    cords = 0.5 + 0.5 * np.cos(phi * 6.0 + 3.0 * (fbm(PL, 9.0, seed + 8, 2) - 0.5))
    cord = (1.0 - cords) ** 3 * sstep(0.40, 0.80, u) * (1.0 - 0.6 * tip)
    f1b, f2b, idb, _vx, _vy = worley2(S, cd, 1.5, 1.5, seed + 30)
    pebble = 0.5 + 0.5 * (1.0 - np.clip(f1b * 1.4, 0, 1))
    dorsal = dorsal * (1.0 - 0.32 * ring - 0.24 * cord)[..., None] * (0.90 + 0.17 * pebble + 0.04 * idb)[..., None]
    # ---- scars (pale, puckered, stitched with tick marks) and harpoon punctures
    rng = np.random.default_rng(seed_of(key, 'scars', i))
    scar = np.zeros(S.shape)
    ticks = np.zeros(S.shape)
    outer = np.abs(cd) < 0.9 * math.pi * R
    for (s_c, c_c, ang, ln, wd) in (scars_for(i, rng) if scar_k else []):
        dx, dy = math.cos(ang) * ln / 2, math.sin(ang) * ln / 2
        m, tt = K.line_mask(S, cd, (s_c - dx, c_c - dy), (s_c + dx, c_c + dy), wd)
        scar = np.maximum(scar, m * outer)
        d, _t = seg_dist(S, cd, (s_c - dx, c_c - dy), (s_c + dx, c_c + dy))
        ticks = np.maximum(ticks, ((np.abs((tt * ln) % 1.9 - 0.95) < 0.2) & (d < 1.5) & (d > 0.5)) * outer)
    dorsal = mixc(dorsal, np.array([182.0, 156.0, 160.0]), np.clip(scar * 0.8 + ticks * 0.5, 0, 1))
    if scar_k and i % 2 == 0 and i < 10:
        h_s, h_c = S0[i] + rng.uniform(0.2, 0.8) * LEN[i], rng.uniform(-0.3, 0.3) * math.pi * hull_radius(S0[i] + 0.5 * LEN[i])
        d = np.hypot(S - h_s, cd - h_c)
        dorsal = mixc(dorsal, np.array([170.0, 142.0, 150.0]), (1.0 - sstep(1.6, 2.1, d)) * 0.7)
        dorsal = mixc(dorsal, np.array([9.0, 6.0, 12.0]), 1.0 - sstep(0.7, 1.1, d))
    # ---- barnacle crusts: clusters of little cones on the outer side, thickest toward the root
    cl = fbm(P3, 7.5, seed + 9, 3)
    crust = sstep(0.55, 0.64, cl) * sstep(0.50, 0.70, u) * (1.0 - 0.75 * sstep(0.2, 0.95, tip)) * crust_k
    f1, f2, ident, vx, vy = worley2(S, cd, 2.4, 2.4, seed + 10)
    rad = 0.33 + 0.21 * ident
    dome = np.sqrt(np.clip(1.0 - (f1 / rad) ** 2, 0, 1))
    lit = np.clip(0.5 - 0.55 * vx / rad - 0.65 * vy / rad, -1, 1)
    cone = (f1 < rad) & (ident > 0.22)
    shell = ramp(SHELL, 0.24 + 0.34 * dome + 0.22 * lit * dome + 0.10 * (fine - 0.5))
    mouth = (f1 < rad * 0.36) & cone
    shell = np.where(mouth[..., None], np.array([14.0, 12.0, 17.0]), shell)
    plates = stripes(np.arctan2(vy, vx) * 3.0 / math.pi, 1.0, 0.12, 0.06) * (f1 > rad * 0.36) * (f1 < rad * 0.9)
    shell = shell * (1.0 - 0.22 * plates)[..., None]
    chalk = mixc(dorsal * 0.8, ramp(SHELL, 0.28 + 0.3 * fine), 0.62)
    on_crust = crust > 0.35
    dorsal = np.where((on_crust & ~cone)[..., None], chalk, dorsal)
    dorsal = np.where((on_crust & cone)[..., None], shell, dorsal)
    # ---- the underside: pale bone ivory, creased, a groove down its middle between the rows of suckers, flushed round each row
    tv = 0.60 + 0.18 * (mid - 0.5) + 0.12 * (fine - 0.5) - 0.07 * tip
    ivory = ramp(IVORY, tv)
    groove = np.exp(-(cu / 0.7) ** 2)
    ivory = ivory * (1.0 - 0.38 * groove)[..., None]
    ivory = ivory * (1.0 - 0.10 * stripes(S + 1.6 * (mid - 0.5), 2.3, 0.13, 0.06))[..., None]
    row = np.exp(-((np.abs(phi) - SUCKER_ANGLE) / 0.26) ** 2)
    ivory = mixc(ivory, np.array([166.0, 112.0, 128.0]), row * 0.40)
    ivory = mixc(ivory, np.array([116.0, 100.0, 126.0]), 0.30 * sstep(0.22, 0.38, u) * (1.0 - sstep(0.38, 0.46, u)))
    rgb = mixc(dorsal, ivory, vent)
    lip = np.exp(-((u - (0.375 + edge)) / 0.022) ** 2)
    rgb = rgb * (1.0 - 0.40 * lip)[..., None]
    # ---- veins under the skin: a few long ones with branches, on the outer side only
    psi = np.where(phi >= 0, phi - math.pi, phi + math.pi)
    vein = vein_mask(S, psi, R) * sstep(0.36, 0.58, u) * (1.0 - 0.9 * on_crust * cone)
    vein = vein * (1.0 - 0.45 * tip)
    rgb = mixc(rgb, np.array([30.0, 92.0, 88.0]) * (0.7 + 0.5 * mid)[..., None], np.clip(vein * 0.45, 0, 1))
    if f.face in ('bottom', 'top'):
        rgb = rgb * 0.80
    return Out(rgb, None, {'vein': vein})


def paint_sucker(f):
    kw = f.kw
    d = kw['d']
    seed = f.seed()
    x, y, z = f.P[..., 0], f.P[..., 1], f.P[..., 2]
    fine = fbm(f.W, 1.0, seed, 2)
    if f.face == 'front':
        rho = np.hypot(x, y) / (d / 2.0)
        th = np.arctan2(y, x) + kw['spin']
        n_teeth = max(5, int(round(2.3 * d)))
        # the pad the sucker stands on, a pearl rim, a pink slope and a dark wet cup, and a ring of small hooked teeth round the edge
        rgb = ramp(IVORY, 0.60 + 0.16 * (fine - 0.5) - 0.20 * sstep(0.95, 1.5, rho)) * np.ones(rho.shape + (1,))
        cup = ramp(SUCK, 0.06 + 0.50 * sstep(0.0, 0.58, rho) ** 1.2 + 0.08 * (fine - 0.5))
        shine = np.exp(-(((x + 0.22 * d) ** 2 + (y + 0.22 * d) ** 2) / (0.10 * d) ** 2)) * (rho < 0.5)
        cup = mixc(cup, np.array([230.0, 200.0, 200.0]), 0.65 * shine)
        slope = ramp(SUCK, 0.55 + 0.20 * np.cos(th - 2.4) * sstep(0.45, 0.8, rho) + 0.08 * (fine - 0.5))
        rim = ramp(IVORY, 0.82 - 0.10 * np.cos(th - 2.4) + 0.06 * (fine - 0.5))
        rgb = np.where((rho < 0.56)[..., None], cup, rgb)
        rgb = np.where(((rho >= 0.56) & (rho < 0.70))[..., None], slope, rgb)
        rgb = np.where(((rho >= 0.70) & (rho < 0.84))[..., None], rim, rgb)
        saw = ((th * n_teeth / (2 * math.pi) + 0.45 * rho) % 1.0)
        tooth = (rho >= 0.84) & (rho < 1.02) & (((rho - 0.84) / 0.18) < (1.0 - saw) * 1.15)
        rgb = np.where(tooth[..., None], np.array([238.0, 232.0, 212.0]), np.where(((rho >= 0.84) & (rho < 1.02))[..., None], ramp(SUCK, 0.35), rgb))
        return Out(rgb)
    t = np.clip(0.58 - 0.20 * sstep(-kw['h'], 1.2, z) + 0.12 * (fine - 0.5), 0, 1)
    rgb = mixc(ramp(IVORY, t), ramp(SUCK, 0.5), 0.22)
    return Out(rgb)


def paint_barnacle(f):
    kw = f.kw
    seed = f.seed()
    x, y, z = f.P[..., 0], f.P[..., 1], f.P[..., 2]
    n = fbm(f.W, 1.2, seed, 2)
    rgb = ramp(SHELL, 0.40 + 0.35 * (n - 0.5) - 0.10 * sstep(0.0, 1.0, np.abs(y) / max(f.sz[1], 0.5)))
    plates = stripes(y * 1.2 + 0.4 * x, 1.0, 0.16, 0.08)
    rgb = rgb * (1.0 - 0.26 * plates)[..., None]
    if f.face == kw.get('mouth', 'back'):
        a, b = (x, y) if f.face in ('front', 'back') else (z, y)
        d = np.hypot(a - np.mean(a), (b - np.mean(b)) * 0.8)
        rgb = np.where((d < 0.75)[..., None], np.array([14.0, 12.0, 18.0]), rgb)
    return Out(rgb)


def paint_frill(f):
    kw = f.kw
    y, z = f.P[..., 1], f.P[..., 2]
    hf, j = kw['hf'], kw['panel']
    seed = seed_of('arm', 'frill', j)
    s = kw['s0'] + y
    spacing = 2.6
    ph = ((s + 0.9 * j) / spacing) % 1.0
    ray = 1.0 - sstep(0.0, 0.17, np.minimum(ph, 1.0 - ph))
    scallop = np.sin(math.pi * ph) ** 0.8
    wob = fbm2(s, z * 0.0 + 1.0 + j, 7.0, 3.0, seed, 2)
    edge_h = hf * (1.0 - 0.40 * scallop) * (0.70 + 0.34 * wob)
    spine_h = hf * (0.90 + 0.30 * hash01(np.floor((s + 0.9 * j) / spacing), j, 3, seed))
    tear = sstep(0.60, 0.74, fbm2(s, z, 2.0, 2.0, seed + 1, 2))
    membrane = z < edge_h * (1.0 - 0.75 * tear)
    spine = (ray > 0.5) & (z < spine_h)
    solid = (z < 1.1) | membrane | spine
    tz = np.clip(z / max(hf, 1e-6), 0, 1)
    jitter = fbm2(s, z, 2.5, 2.5, seed + 3, 2) - 0.5
    mem = ramp(FRILLC, 0.30 + 0.42 * tz + 0.20 * jitter)
    mem = mixc(mem, ramp(BRUISE, 0.5 + 0.3 * tz), 0.25 + 0.4 * (j / max(FRILLS - 1, 1)))
    rib = ramp(SLATE, 0.30 + 0.25 * tz)
    rgb = mixc(mem, rib, ray * 0.85)
    skin = ramp(SLATE, 0.42 + 0.1 * (fbm(f.W, 3.0, seed + 4, 2) - 0.5))
    rgb = np.where((z < 1.1)[..., None], skin, rgb)
    glow = ray * sstep(1.2, 3.2, z) * 0.35
    return Out(rgb, solid.astype(float), {'vein': glow})


def paint_hook(f):
    kw = f.kw
    seed = f.seed()
    y = f.P[..., 1]
    t = 0.30 + 0.22 * stripes(y + kw['k'] * 3.0, 1.3, 0.12, 0.08) + 0.25 * (fbm(f.W, 1.5, seed, 2) - 0.5)
    rgb = ramp(HORN, t)
    if kw['k'] == 0:
        rgb = mixc(rgb, np.array([176.0, 166.0, 150.0]), 0.5 * (1.0 - sstep(0.0, 1.5, y)))
    return Out(rgb)


def paint_stump(f):
    kw = f.kw
    seed = f.seed()
    P = f.P
    kind = kw['kind']
    fine = fbm(f.W, 0.9, seed, 2)
    mid = fbm(f.W, 2.6, seed + 1, 3)
    if kind == 'bone':
        t = 0.45 + 0.3 * (mid - 0.5) + 0.12 * (fine - 0.5)
        rgb = ramp(BONE, t)
        if f.face == 'bottom':
            d = np.hypot(P[..., 0], P[..., 2])
            rgb = np.where((d < 0.8)[..., None], np.array([60.0, 14.0, 20.0]), rgb)
        return Out(rgb)
    t = 0.30 + 0.34 * (mid - 0.5) + 0.20 * (fine - 0.5)
    if f.face == 'bottom':
        d = np.hypot(P[..., 0], P[..., 2]) / STUMP_R
        t = t - 0.10 + 0.12 * sstep(0.2, 0.9, d)
    rgb = ramp(FLESH, t)
    strand = stripes(P[..., 0] + P[..., 2] + 2.0 * (mid - 0.5), 1.6, 0.16, 0.08)
    rgb = rgb * (1.0 - 0.28 * strand)[..., None]
    clot = sstep(0.62, 0.78, fbm(f.W, 2.0, seed + 5, 2))
    rgb = rgb * (1.0 - 0.6 * clot)[..., None]
    wet = (hash01(np.floor(P[..., 0] * HD), np.floor(P[..., 1] * HD), np.floor(P[..., 2] * HD), seed) > 0.975)
    rgb = np.where(wet[..., None], np.array([196.0, 84.0, 86.0]), rgb)
    if kind == 'shard' and f.face == 'bottom':
        rgb = mixc(rgb, np.array([170.0, 150.0, 130.0]), 0.55)
    if kind == 'flesh' and f.face != 'bottom':
        rgb = rgb * (0.9 + 0.2 * sstep(-1.5, 3.5, P[..., 1]))[..., None]
    return Out(rgb)


MATS = {'skin': paint_skin, 'sucker': paint_sucker, 'barnacle': paint_barnacle, 'frill': paint_frill, 'hook': paint_hook, 'stump': paint_stump}
DOUBLE = {'frill'}


def painted():
    """Builds the model, packs its atlas and paints it: (model, scene, texture (768x512 RGBA), glow map (the same))."""
    model = build()
    K.pack_atlas(model)
    scene = K.SScene(model, DOUBLE)
    tex, layers = K.paint_model(scene, MATS, GLOW_LAYERS, TEX_W, TEX_H)
    return model, scene, tex, K.glow_map(layers, GLOW_LAYERS)


# ================================================================== the pose: what MaelgraspArmModel.setupAnim does

class ArmState:
    """What the renderer gives the model (see MaelgraspArmRenderState.kt), with the rest values."""

    def __init__(self, joints=None, **kw):
        self.joints = list(joints) if joints is not None else rest_joints()
        self.cut = SEGS
        self.start = 0
        self.time = 0.0
        self.grip = 0.0
        self.glow = 0.0
        self.rage = 0.0
        for k, v in kw.items():
            if not hasattr(self, k):
                raise AttributeError(k)
            setattr(self, k, v)


def rest_joints():
    """The arm standing straight up from its root (arm frame, blocks)."""
    out = []
    y = -T.ROOT_DEPTH
    for i in range(SEGS + 1):
        out += [0.0, y, 0.0]
        if i < SEGS:
            y += T.LENGTHS[i]
    return out


FRILL_REST_RAD = round(math.radians(FRILL_REST), 3)


def clampf(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def pose(st):
    """{part: {pos (px), rot (radians), scale, visible}} for state [st] (see the Kotlin setupAnim, which this follows line by line)."""
    P = {}

    def put(n, **kw):
        P.setdefault(n, {}).update(kw)

    t = st.time
    grip = clampf(st.grip, 0.0, 1.0)
    rage = clampf(st.rage, 0.0, 1.0)
    # ---- the frame carried along the arm: its +y runs joint to joint, its -z is the underside, which starts toward the ship
    J = [-16.0 * v for v in st.joints]
    ux, uy, uz = 0.0, 0.0, -1.0
    px, py, pz = 0.0, -1.0, 0.0
    frames = []
    lens = []
    for i in range(SEGS):
        dx, dy, dz = J[i * 3 + 3] - J[i * 3], J[i * 3 + 4] - J[i * 3 + 1], J[i * 3 + 5] - J[i * 3 + 2]
        ln = math.sqrt(dx * dx + dy * dy + dz * dz)
        if ln > 1.0e-4:
            dx /= ln
            dy /= ln
            dz /= ln
        else:
            dx, dy, dz = px, py, pz
        if i > 0:
            ax, ay, az = py * dz - pz * dy, pz * dx - px * dz, px * dy - py * dx
            s = math.sqrt(ax * ax + ay * ay + az * az)
            if s > 1.0e-5:
                c = px * dx + py * dy + pz * dz
                ax /= s
                ay /= s
                az /= s
                kx, ky, kz = ay * uz - az * uy, az * ux - ax * uz, ax * uy - ay * ux
                kd = ax * ux + ay * uy + az * uz
                ux, uy, uz = ux * c + kx * s + ax * kd * (1.0 - c), uy * c + ky * s + ay * kd * (1.0 - c), uz * c + kz * s + az * kd * (1.0 - c)
        k = ux * dx + uy * dy + uz * dz
        ux -= dx * k
        uy -= dy * k
        uz -= dz * k
        ul = math.sqrt(ux * ux + uy * uy + uz * uz)
        if ul < 1.0e-4:
            # the underside lost: any side will do
            ux, uy, uz = (1.0, 0.0, 0.0) if abs(dx) < 0.9 else (0.0, 1.0, 0.0)
            k = ux * dx + uy * dy + uz * dz
            ux -= dx * k
            uy -= dy * k
            uz -= dz * k
            ul = math.sqrt(ux * ux + uy * uy + uz * uz)
        ux /= ul
        uy /= ul
        uz /= ul
        # the part's axes: y along the segment, z away from the underside, x = y cross z
        ezx, ezy, ezz = -ux, -uy, -uz
        exx, exy, exz = dy * ezz - dz * ezy, dz * ezx - dx * ezz, dx * ezy - dy * ezx
        frames.append((exx, exy, exz, dx, dy, dz, ezx, ezy, ezz))
        lens.append(ln)
        px, py, pz = dx, dy, dz
    # ---- the segments
    first, last = max(st.start, 0), min(st.cut, SEGS)
    for i in range(SEGS):
        exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz = frames[i]
        rx, ry, rz = euler_axes(exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz)
        ripple = 0.018 * (1.0 + 1.3 * rage) * math.sin(t * 0.21 - 0.9 * i) + 0.07 * grip + 0.04 * rage
        put(f'seg{i}', pos=(J[i * 3], J[i * 3 + 1], J[i * 3 + 2]), rot=(rx, ry, rz), scale=(1.0 + ripple, lens[i] / LEN[i], 1.0 + ripple),
            visible=first <= i < last)
    # ---- suckers: each pulses on its own beat, and clenches in as the arm grips (smaller mouths, standing higher)
    for i in range(SEGS):
        n = SUCKERS[i]
        for k in range(2 * n):
            ph = 0.9 * i + 2.1 * (k // 2) + (1.7 if k % 2 else 0.0)
            pulse = 0.5 + 0.5 * math.sin(t * 0.17 - ph)
            mouth = 1.0 + 0.12 * pulse * (1.0 - grip) - 0.34 * grip
            rise = 1.0 + 0.35 * pulse + 1.0 * grip
            put(f'su{i}_{k}', scale=(mouth, mouth, rise))
    # ---- the frill streams and flutters, a wave running along it, the harder in a rage
    for j in range(FRILLS):
        a = (0.34 + 0.30 * rage) * math.sin(t * 0.33 - 0.8 * j) + 0.16 * math.sin(t * 0.71 - 1.9 * j + 1.0)
        b = 0.14 * math.sin(t * 0.43 - 0.8 * j + 2.0)
        put(f'fr{j}', rot=(FRILL_REST_RAD + b, a, 0.0), scale=(1.0, 1.0, 1.0 + 0.22 * rage))
    # ---- the hook curls toward the suckers, tighter as it grips
    for k in range(HOOK):
        curl = -(0.45 + 0.42 * grip + 0.05 * math.sin(t * 0.19 + k)) * (1.0 + 0.16 * k)
        put(f'hk{k + 1}', rot=(curl, 0.0, 0.0))
    # ---- the stumps: one on the end of what is left on the arm, one on the start of a length that has fallen away; each faces out of its cut
    for name, joint, seg, at_start, shown in (('stump', last, last - 1, False, 0 < last < SEGS and last > first),
                                              ('stump_start', first, first, True, first > 0 and first < last)):
        if not shown:
            put(name, visible=False)
            continue
        exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz = frames[seg]
        if at_start:
            eyx, eyy, eyz, ezx, ezy, ezz = -eyx, -eyy, -eyz, -ezx, -ezy, -ezz
        rx, ry, rz = euler_axes(exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz)
        s = 16.0 * T.RADII[joint] / STUMP_R
        put(name, pos=(J[joint * 3], J[joint * 3 + 1], J[joint * 3 + 2]), rot=(rx, ry, rz), scale=(s, s ** 0.7, s), visible=True)
    return P


def euler_axes(exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz):
    """(x, y, z) radians with the matrix whose columns are (ex, ey, ez) equal to Rz(z) Ry(y) Rx(x): what the game applies for xRot, yRot, zRot."""
    m20 = exz
    y = math.asin(max(-1.0, min(1.0, -m20)))
    if math.cos(y) > 1.0e-4:
        return math.atan2(eyz, ezz), y, math.atan2(exy, exx)
    return math.atan2(-ezy, eyy), y, 0.0


# ================================================================== the Kotlin

KT_HEAD = """package io.github.vaspyyy.guildmark.client.sea

import io.github.vaspyyy.guildmark.Guildmark
import io.github.vaspyyy.guildmark.sea.Tentacle
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.model.geom.builders.PartDefinition
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One of the Maelgrasp's arms, drawn from the thirteen joints [MaelgraspArmRenderState.joints] holds (the arm's frame, blocks;
 * [Tentacle] works them out). It is twelve segments, each a part of its own at the root of the model: segment i sits on joint i, is
 * turned to run along the segment to joint i + 1 and is stretched to the distance between them (it tapers from the radius [Tentacle.RADII]
 * gives at one joint to the radius at the next), and the turn is carried from segment to segment so the suckers' side never flips: it
 * starts toward the ship at the root (arm +z) and is turned the shortest way at every joint. Models are in pixels with y down and
 * the creature facing -z, so a joint is at -16 times its arm-frame coordinates; the model's origin is where the arm breaks the surface.
 *
 * Thick and muscular at the root, drowned slate-blue going to bruise-violet, crusted with barnacles and scarred along its outer side;
 * its underside is pale bone-ivory with two rows of suckers (bigger at the root, each ringed with small hooked teeth) that pulse on
 * their own beats and clench as it grips, a ragged frill along the outer edge of its last third that flutters (the harder in a rage), a
 * hooked tip that tightens its curl as it grips, and veins under the skin that glow (the glow map). Where it has been hacked through
 * ([MaelgraspArmRenderState.cut]: segments from there to the tip are hidden) the last one left ends in a raw ragged stump, and the length
 * hacked off that falls away alone ([MaelgraspArmRenderState.start]: segments before it are hidden) begins with the same stump facing
 * back along it; they are two parts, so both can be drawn.
 *
 * The geometry comes from art/scripts/sea_arm.py (edit it there: it writes this file's body and the textures, and its `pose()` is the
 * same animation in Python, run for the previews and checked against this class by sea_monsters.py). The texture is HD: the model
 * declares @@W@@x@@H@@ and the PNGs are painted at twice that. It is drawn with [RenderTypes.entityCutout] (the frill is cut to shape in
 * the texture's alpha, and that type does not cull back faces); the glow layer is the same model drawn again with [RenderTypes.eyes]
 * over maelgrasp_arm_glow.png, tinted by [MaelgraspArmRenderState.glow] (it holds only the veins).
 */
class MaelgraspArmModel(root: ModelPart) : EntityModel<MaelgraspArmRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The twelve segments (each with its suckers, barnacles and, on the last third, its frill panels), the hook's three pieces, the two stumps
    private val segs = Array(SEGMENTS) { part("seg$it") }
    private val suckers = Array(SEGMENTS) { i -> Array(2 * SUCKERS[i]) { part("su${i}_$it") } }
    private val frills = Array(FRILLS) { part("fr$it") }
    private val hooks = Array(HOOKS) { part("hk${it + 1}") }
    private val stump = part("stump")
    private val stumpStart = part("stump_start")

    // Scratch for the frame carried along the arm (each segment's x, y and z axes) and each segment's length: nothing allocated per frame
    private val frames = FloatArray(SEGMENTS * 9)
    private val lens = FloatArray(SEGMENTS)

    override fun setupAnim(state: MaelgraspArmRenderState) {
        super.setupAnim(state)
        val t = state.time
        val grip = state.grip.coerceIn(0f, 1f)
        val rage = state.rage.coerceIn(0f, 1f)
        val j = state.joints
        // The frame carried along the arm: its +y runs joint to joint, its -z is the underside, which starts toward the ship
        var ux = 0f
        var uy = 0f
        var uz = -1f
        var px = 0f
        var py = -1f
        var pz = 0f
        for (i in 0 until SEGMENTS) {
            var dx = (j[i * 3 + 3] - j[i * 3]) * -16f
            var dy = (j[i * 3 + 4] - j[i * 3 + 1]) * -16f
            var dz = (j[i * 3 + 5] - j[i * 3 + 2]) * -16f
            val ln = sqrt(dx * dx + dy * dy + dz * dz)
            if (ln > 1.0e-4f) {
                dx /= ln
                dy /= ln
                dz /= ln
            } else {
                dx = px
                dy = py
                dz = pz
            }
            if (i > 0) {
                // Turn the underside by the turn that takes the last direction to this one (about their cross product)
                var ax = py * dz - pz * dy
                var ay = pz * dx - px * dz
                var az = px * dy - py * dx
                val s = sqrt(ax * ax + ay * ay + az * az)
                if (s > 1.0e-5f) {
                    val c = px * dx + py * dy + pz * dz
                    ax /= s
                    ay /= s
                    az /= s
                    val kx = ay * uz - az * uy
                    val ky = az * ux - ax * uz
                    val kz = ax * uy - ay * ux
                    val kd = ax * ux + ay * uy + az * uz
                    val nx = ux * c + kx * s + ax * kd * (1f - c)
                    val ny = uy * c + ky * s + ay * kd * (1f - c)
                    val nz = uz * c + kz * s + az * kd * (1f - c)
                    ux = nx
                    uy = ny
                    uz = nz
                }
            }
            var k = ux * dx + uy * dy + uz * dz
            ux -= dx * k
            uy -= dy * k
            uz -= dz * k
            var ul = sqrt(ux * ux + uy * uy + uz * uz)
            if (ul < 1.0e-4f) {
                // The underside was lost (it ran along the arm): any side will do
                if (abs(dx) < 0.9f) {
                    ux = 1f
                    uy = 0f
                    uz = 0f
                } else {
                    ux = 0f
                    uy = 1f
                    uz = 0f
                }
                k = ux * dx + uy * dy + uz * dz
                ux -= dx * k
                uy -= dy * k
                uz -= dz * k
                ul = sqrt(ux * ux + uy * uy + uz * uz)
            }
            ux /= ul
            uy /= ul
            uz /= ul
            // The part's axes: y along the segment, z away from the underside, x = y cross z
            val ezx = -ux
            val ezy = -uy
            val ezz = -uz
            val at = i * 9
            frames[at] = dy * ezz - dz * ezy
            frames[at + 1] = dz * ezx - dx * ezz
            frames[at + 2] = dx * ezy - dy * ezx
            frames[at + 3] = dx
            frames[at + 4] = dy
            frames[at + 5] = dz
            frames[at + 6] = ezx
            frames[at + 7] = ezy
            frames[at + 8] = ezz
            lens[i] = ln
            px = dx
            py = dy
            pz = dz
        }
        // The segments, each from its joint along its length: the first one drawn to the last, thicker as it grips and ripple-skinned
        val first = max(state.start, 0)
        val last = min(state.cut, SEGMENTS)
        for (i in 0 until SEGMENTS) {
            val at = i * 9
            val seg = segs[i]
            turn(seg, frames[at], frames[at + 1], frames[at + 2], frames[at + 3], frames[at + 4], frames[at + 5], frames[at + 6], frames[at + 7], frames[at + 8])
            val ripple = 0.018f * (1f + 1.3f * rage) * sin(t * 0.21f - 0.9f * i) + 0.07f * grip + 0.04f * rage
            seg.x = j[i * 3] * -16f
            seg.y = j[i * 3 + 1] * -16f
            seg.z = j[i * 3 + 2] * -16f
            seg.xScale = 1f + ripple
            seg.yScale = lens[i] / LEN[i]
            seg.zScale = 1f + ripple
            seg.visible = i >= first && i < last
        }
        // The suckers each pulse on a beat of their own, and clench (smaller mouths, standing higher) as the arm grips
        for (i in 0 until SEGMENTS) {
            val row = suckers[i]
            for (k in row.indices) {
                val ph = 0.9f * i + 2.1f * (k / 2) + (if (k % 2 == 1) 1.7f else 0f)
                val pulse = 0.5f + 0.5f * sin(t * 0.17f - ph)
                val mouth = 1f + 0.12f * pulse * (1f - grip) - 0.34f * grip
                row[k].xScale = mouth
                row[k].yScale = mouth
                row[k].zScale = 1f + 0.35f * pulse + grip
            }
        }
        // The frill streams and flutters, a wave running along it, the harder in a rage
        for (f in 0 until FRILLS) {
            val a = (0.34f + 0.30f * rage) * sin(t * 0.33f - 0.8f * f) + 0.16f * sin(t * 0.71f - 1.9f * f + 1f)
            val b = 0.14f * sin(t * 0.43f - 0.8f * f + 2f)
            frills[f].xRot = FRILL_REST + b
            frills[f].yRot = a
            frills[f].zScale = 1f + 0.22f * rage
        }
        // The hook curls toward the suckers, tighter as it grips
        for (k in 0 until HOOKS) {
            hooks[k].xRot = -(0.45f + 0.42f * grip + 0.05f * sin(t * 0.19f + k)) * (1f + 0.16f * k)
        }
        // The stumps: one on the end of what is left on the arm, one on the start of a length that has fallen away, each facing out of its cut
        val showEnd = last in 1 until SEGMENTS && last > first
        stump.visible = showEnd
        if (showEnd) cap(stump, j, last, last - 1, false)
        val showStart = first > 0 && first < last
        stumpStart.visible = showStart
        if (showStart) cap(stumpStart, j, first, first, true)
    }

    /** Puts a stump on joint [joint] with the frame of segment [seg] (turned about its x axis to face back along the arm when [back]). */
    private fun cap(p: ModelPart, j: FloatArray, joint: Int, seg: Int, back: Boolean) {
        val at = seg * 9
        val m = if (back) -1f else 1f
        turn(p, frames[at], frames[at + 1], frames[at + 2], m * frames[at + 3], m * frames[at + 4], m * frames[at + 5], m * frames[at + 6], m * frames[at + 7], m * frames[at + 8])
        p.x = j[joint * 3] * -16f
        p.y = j[joint * 3 + 1] * -16f
        p.z = j[joint * 3 + 2] * -16f
        val s = 16f * Tentacle.RADII[joint] / STUMP_R
        p.xScale = s
        p.yScale = s.pow(0.7f)
        p.zScale = s
    }

    /** Sets [p]'s xRot, yRot and zRot (the game applies them as Rz Ry Rx) to the turn whose columns are the axes (ex), (ey) and (ez). */
    private fun turn(p: ModelPart, exx: Float, exy: Float, exz: Float, eyx: Float, eyy: Float, eyz: Float, ezx: Float, ezy: Float, ezz: Float) {
        val y = asin((-exz).coerceIn(-1f, 1f))
        p.yRot = y
        if (cos(y) > 1.0e-4f) {
            p.xRot = atan2(eyz, ezz)
            p.zRot = atan2(exy, exx)
        } else {
            p.xRot = atan2(-ezy, eyy)
            p.zRot = 0f
        }
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "maelgrasp_arm"), "main")

@@CONSTANTS@@
        @Suppress("LocalVariableName", "UNUSED_VARIABLE")
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
@@CALLS@@
            return LayerDefinition.create(mesh, @@W@@, @@H@@)
        }
@@CHUNKS@@@@FACES@@    }
}
"""


def kt_constants():
    f = K.kt_float
    return '\n'.join([
        '        private const val SEGMENTS = Tentacle.SEGMENTS',
        '        private val LEN = FloatArray(SEGMENTS) { Tentacle.LENGTHS[it] * 16f }',
        '',
        '        // Suckers in each of a segment\'s two rows; the frill\'s panels (two on each of the last four segments); the hook\'s pieces',
        f'        private val SUCKERS = intArrayOf({", ".join(str(n) for n in SUCKERS)})',
        f'        private const val FRILLS = {FRILLS}',
        f'        private const val HOOKS = {HOOK}',
        f'        private const val FRILL_REST = {f(FRILL_REST_RAD)}',
        '',
        '        // The radius (px) the stumps are built to: each is scaled to the radius of the joint it sits on',
        f'        private const val STUMP_R = {f(STUMP_R)}',
        '',
    ])


def kotlin_source(model):
    """MaelgraspArmModel.kt as a string: the hand-written class (see KT_HEAD) with the constants and the mesh generated from [model]."""
    calls, body, faces = K.kt_mesh(GROUPS, model.tex_w, model.tex_h)
    out = (KT_HEAD.replace('@@CONSTANTS@@', kt_constants()).replace('@@CALLS@@', calls).replace('@@CHUNKS@@', body)
           .replace('@@FACES@@', '\n' + faces + '\n' if faces else '').replace('@@W@@', str(model.tex_w)).replace('@@H@@', str(model.tex_h)))
    names = {p.name for p in model.all_parts()}
    for m_ in re.finditer(r'part\("([a-z0-9_$]+)"\)', out):
        if '$' not in m_.group(1):
            assert m_.group(1) in names, m_.group(1)
    for need in [f'seg{i}' for i in range(SEGS)] + [f'fr{j}' for j in range(FRILLS)] + [f'hk{k + 1}' for k in range(HOOK)] + ['stump', 'stump_start']:
        assert need in names, need
    return out


# ================================================================== checking the Kotlin against pose()

def check_cases(n=70, seed=5):
    """Render states to compare the Kotlin with pose() on: arms reaching all over (the game's own solver, ported), whole and hacked, plus
    the edge cases (straight, folded back on itself, upside down, a segment of no length)."""
    rng = np.random.default_rng(seed)
    out = []

    def unit3():
        v = rng.normal(size=3)
        return v / np.linalg.norm(v)
    for i in range(n):
        tx, ty, tz = rng.uniform(-3.5, 3.5), rng.uniform(-1.0, 5.0), rng.uniform(-3.0, 4.5)
        f = unit3()
        j = T.solve(tx, ty, tz, f[0], f[1], f[2], rng.uniform(0, 1), rng.uniform(0, 0.6), rng.uniform(0, 4000), rng.uniform(0, 6.28))
        cut = int(rng.integers(1, SEGS + 1)) if i % 3 else SEGS
        start = int(rng.integers(0, cut)) if i % 4 == 1 else 0
        if i % 5 == 3:
            start, cut = cut - 1 if cut > 1 else 0, SEGS
        out.append(dict(joints=j, cut=cut, start=start, time=float(rng.uniform(0, 6000)), grip=float(rng.random()), glow=float(rng.random()),
                        rage=float(rng.random())))
    out.append(dict(joints=rest_joints(), cut=SEGS, start=0, time=0.0, grip=0.0, glow=0.0, rage=0.0))
    out.append(dict(joints=rest_joints(), cut=5, start=0, time=11.0, grip=1.0, glow=1.0, rage=1.0))
    down = [0.0] * 39
    for k in range(13):
        down[k * 3 + 1] = -T.ROOT_DEPTH - 0.8 * k
    out.append(dict(joints=down, cut=SEGS, start=0, time=3.0, grip=0.2, glow=0.0, rage=0.0))
    fold = rest_joints()
    for k in range(7, 13):
        fold[k * 3 + 1] = fold[(14 - k) * 3 + 1]
    out.append(dict(joints=fold, cut=SEGS, start=0, time=3.0, grip=0.2, glow=0.0, rage=0.0))
    flat = [0.0] * 39
    out.append(dict(joints=flat, cut=SEGS, start=0, time=3.0, grip=0.0, glow=0.0, rage=0.0))
    side = [0.0] * 39
    for k in range(13):
        side[k * 3] = 1.1 * k
    out.append(dict(joints=side, cut=7, start=2, time=9.0, grip=0.5, glow=0.0, rage=0.3))
    return out


def check(work, n=70):
    """Compiles MaelgraspArmModel.kt (what `kotlin_source` makes) with the game's own Tentacle.kt and render state and Minecraft's ModelPart,
    runs its setupAnim on [check_cases] and compares every part with `pose()`. Returns (worst position error px, worst turn error, worst
    scale error, parts compared, problems)."""
    model, scene, tex, glow = painted_geometry()
    files = K.scratch_tree(work, {'client/sea/MaelgraspArmModel.kt': kotlin_source(model)},
                           ['sea/Tentacle.kt', 'client/sea/MaelgraspArmRenderState.kt'])
    return K.verify_pose(files, 'io.github.vaspyyy.guildmark.client.sea', 'MaelgraspArmModel', 'MaelgraspArmRenderState', scene, check_cases(n),
                         lambda c: ArmState(**c), pose, work)


def painted_geometry():
    """The built model and its scene without painting (for the checks that do not need the texture)."""
    model = build()
    K.pack_atlas(model)
    return model, K.SScene(model, DOUBLE), None, None


# ================================================================== the report: parts, what animates them, extents

NOTES = [
    (r'^seg\d+$', 'segments (twelve, one part each, laid joint to joint)', '`joints`: seg i sits on joint i, runs along the segment to joint i + 1 and is stretched to the distance between them; the underside is carried along the arm so it never flips; `cut` hides segments from there to the tip, `start` hides those before it; `grip` and `rage` swell the skin (ripple)'),
    (r'^seg\d+_c\d+$', 'turned copies of a segment (children: a 16-gon, 12-gon or octagon made of boxes turned about the axis)', 'none of their own: they ride their segment'),
    (r'^su\d+_\d+$', 'suckers (two rows on each segment: three to a row near the root, one at the tip)', 'each pulses on its own beat; `grip` shrinks the mouth and stands it higher (clenches)'),
    (r'^fr\d+$', 'frill panels (two on each of the last four segments)', 'stream and flutter in a wave along the arm; the harder (and longer) in a rage'),
    (r'^hk\d$', 'the hook (three pieces, each hung from the end of the one before)', 'curl toward the suckers, tighter as it grips'),
    (r'^stump$', 'stump cap on the end of what is left of the arm', 'shown when `cut` is short of the tip (and something is left); turned to face out of the cut and scaled to the joint it sits on'),
    (r'^stump_start$', 'stump cap on the start of a hacked-off length', 'shown when `start` is above 0 (and something is left); faces back toward the root'),
    (r'^stump(_start)?_c\d+$', 'the caps\' turned plug copies (children)', 'none of their own: they ride their cap'),
]


def reach_cases():
    """{label: (joints, cut, start)} for a spread of arms: standing, reaching, slamming, sweeping, gripping, draped over a rail, hacked."""
    def reach(tx, ty, tz, fx, fy, fz, curl=0.3, ripple=0.2, time=1234.0, phase=0.7):
        return T.solve(tx, ty, tz, fx, fy, fz, curl, ripple, time, phase)
    out = {
        'standing': (rest_joints(), SEGS, 0),
        'rearing, tip overhead': (reach(0.0, 7.5, 1.5, 0.0, 1.0, 0.0), SEGS, 0),
        'slam across the deck': (reach(0.0, 1.4, 5.6, 0.0, -0.8, 0.6, 0.0, 0.1), SEGS, 0),
        'sweep low, side on': (reach(3.0, 0.3, 4.5, 1.0, 0.0, 0.0, 0.2, 0.4), SEGS, 0),
        'gripping, tip curled': (reach(0.4, 3.0, 5.0, 0.0, 0.5, 0.0, 1.0, 0.0), SEGS, 0),
        'draped over the rail': (reach(0.0, 1.9, 4.2, 0.0, -0.2, 1.0, 0.5, 0.2), SEGS, 0),
        'folded on itself': (reach(0.3, 0.2, 0.4, 0.0, -1.0, 0.0, 1.0, 0.0), SEGS, 0),
        'hacked at the fifth joint': (reach(1.0, 4.5, 3.0, 0.0, 0.3, 0.3), 5, 0),
        'the severed length': (reach(-1.0, 0.5, 6.0, 0.0, -0.4, 0.0), SEGS, 6),
    }
    return out


def report_poses():
    """The poses the report's extents are taken over (the model's own px, around the point where the arm breaks the surface)."""
    out = {}
    for k, (joints, cut, start) in reach_cases().items():
        out[k] = pose(ArmState(joints=joints, cut=cut, start=start, time=777.0, grip=1.0 if 'grip' in k else 0.3, glow=1.0, rage=0.4))
    out['all raging, at rest'] = pose(ArmState(time=300.0, rage=1.0, grip=1.0, glow=1.0))
    return out


def skin_reach(scene):
    """(worst distance (px) of any drawn point from the line through the joints, label) over [reach_cases]: how far the arm's skin, barnacles,
    suckers and frill stand out from the joint line."""
    worst, where = 0.0, ''
    for label, p in zip(reach_cases(), report_poses().values()):
        joints, cut, start = reach_cases()[label]
        idx, A, B, D, n, ok = scene.quads(p, None)
        if not len(idx):
            continue
        pts = np.stack([A, B, D, B + D - A], 1).reshape(-1, 3)
        J = np.array(joints, float).reshape(-1, 3) * -16.0
        lo, hi = max(start, 0), min(cut, SEGS)
        d_min = np.full(len(pts), 1.0e9)
        for i in range(lo, hi):
            a, b = J[i], J[i + 1]
            ab = b - a
            tt = np.clip(((pts - a) @ ab) / max(float(ab @ ab), 1.0e-9), 0.0, 1.0)
            d_min = np.minimum(d_min, np.linalg.norm(pts - (a + tt[:, None] * ab), axis=1))
        m = float(d_min.max())
        if m > worst:
            worst, where = m, label
    return worst, where


def parts_report(model, scene):
    """The text written beside the preview: the parts, what animates them, the extents and how far the skin stands off the joint line."""
    reach, where = skin_reach(scene)
    head = ['Origin: where the arm breaks the surface; px (1/16 block), y down. The joints come in the arm\'s frame (blocks, y up, +z toward the ship) and the model',
            'places a joint at -16 times its coordinates; nothing else is needed from the renderer (no per-part work).',
            f'Length: {S_ALL / 16.0:.2f} blocks of arm (twelve segments, radius {16 * T.RADII[0]:.1f} px at the root, {16 * T.RADII[-1]:.1f} px at the tip), rising from {ROOT_Y / 16.0:.1f} blocks under the surface.',
            f'Skin, suckers, barnacles and frill stand at most {reach:.1f} px ({reach / 16.0:.2f} blocks) off the line through the joints ({where}); '
            f'so a culling box round the joints wants {math.ceil(reach / 16.0 * 10) / 10:.1f} blocks of margin all round.',
            'Glow map: only the veins under the skin (teal, a trace in the frill); drawn again with RenderTypes.eyes, tinted ARGB.white(glow).',
            '']
    return K.report_text('The Maelgrasp\'s arm (MaelgraspArmModel)', model, scene, NOTES, report_poses(), head)
