"""The Maelgrasp's head: the colossal horror surfacing beside the ship. A bulbous scarred mantle (up to 5.5 blocks above the waterline and 4 below
it, about 5 blocks wide and 5 and a half deep), one enormous wet eye on its face (a stepped sphere of boxes that turns in its socket, a gold-green iris
ringed with light, a pupil that goes from a narrow horizontal bar to a wide round hole, heavy upper and lower lids that close and blink and a milky film
that clouds over it as the beast dies), a black hooked cracked parrot beak below it with a lower jaw that gapes, a crown of eight short writhing
arm-roots round the beak (they trail below it when it is low, splay when it has surfaced, reach when it lunges and hang limp when it dies), a siphon on its flank
that flares and pulses to spew ink, barnacle crusts, old harpoon scars with the broken shafts still in them and weed trailing from it. Veins under the
skin and the ring of the iris glow (the glow map).

Minecraft model space: pixels, y down, the creature faces -z, its right is -x, the origin at the waterline under the middle of the head. Fixed points
(the game aims at them): the eye's centre (0, -32, -37), its radius 11, and the beak's tip (0, -6, -46).

The mantle is a stack of round slices (each two to four boxes turned on each other: a 16-gon, a 12-gon, an octagon) hung from a spine of four joints
that bend forward when it lunges, so the whole of it leans; each slice breathes on its own (the heave of the mantle). The texture is HD (declared
512x512, painted at twice that); every texel is painted from the place it has on the beast, so the scars and the veins run on across the boxes.
`pose()` is `MaelgraspModel.setupAnim` in Python, line for line (checked against the compiled Kotlin by sea_monsters.py).
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np

import sea_kit as K
from sea_kit import Cu, part, prism, SIDES, ALL, Out, Model, HD
from ship import ramp, sstep, hash01, fbm, fbm2, mixc, seed_of, stripes, seg_dist
import sea_arm as ARM

TEX_W, TEX_H = 512, 512
NAME = 'maelgrasp'

# ================================================================== what the head is made to

EYE = (0.0, -32.0, -37.0)            # the eye's centre: the game aims at it
EYE_R = 11.0
BEAK_TIP = (0.0, -6.0, -46.0)        # the upper beak's tip
MANTLE_R, MANTLE_B, MANTLE_YC = 42.0, 76.0, -12.0       # the mantle: a bulb, 42 px round at its fullest (y = -12), from y = -88 to y = 64
Y_TOP, Y_BOT = -88.0, 64.0
SPINE = [36.0, 16.0, -4.0, -24.0, -46.0, -66.0]    # y of the spine's six joints (the first never moves): the mantle shears forward joint by joint as it leans
J_MOUTH, J_FACE, J_BROW = 2, 3, 4      # the joints the beak, the roots and the siphon hang from; the eye and its lids; the brow and the fins
ROOTS = 8
ROOT_SEGS = 5
ROOT_LEN = [9.5, 9.0, 8.5, 7.5, 6.5]
ROOT_RAD = [7.4, 6.3, 5.2, 4.2, 3.2, 2.3]
ROOT_RING = (0.0, -3.0, -36.0)         # the middle of the horseshoe of roots (round the beak), and its radius
ROOT_RING_R = 12.0
ROOT_A0, ROOT_STEP = -55.0, 40.0       # degrees: the first root's angle round the beak (0 = its right... +x, 90 = down) and the step to the next: a gap is left above, under the eye
SIPHON_AT = (40.0, -8.0, 4.0)
FILMS = 4                              # the milky film that clouds the eye as it dies: four dithered discs, each with a quarter of the specks, that show one after the other
FILM_Z = -12.0                         # where the film's discs stand in front of the eye's centre
FILM_R = 10.6
FILM_HIDDEN_Z = 40.0                   # a disc's rest place is deep inside the head, out of sight
PUPIL_STRIPS = 5                       # the pupil: five stacked strips that go from a narrow bar to a round hole
PUPIL_A = (5.4, 4.6)                   # half width of the bar and of the round hole (px)
PUPIL_B = (1.15, 4.6)                  # half height of the bar and of the round hole
PUPIL_FRONT = -10.7                    # z of the strips' fronts, in the eye's frame (just in front of the eyeball's flat face)
PUPIL_REST = 0.3                       # the openness the rest pose is built at
FIN_SEGS = [9.0, 9.0, 8.0, 7.0]        # the two fins on the flanks of the dome: four slats each along the span (px)
FIN_CHORD = [32.0, 30.0, 26.0, 18.0]   # the width of each slat's card (front to back)
FIN_AT = (33.0, -54.0, 10.0)           # where the left fin's root sits (x is mirrored for the right fin), relative to the model's origin
FIN_REST = 16.0                        # degrees a fin is lifted at rest
LID_OPEN = {'lid_u': -38.0, 'lid_l': 24.0}        # degrees the lids are turned open at rest (they are built closed)
LID_OPEN_RAD = (math.radians(-38.0), math.radians(24.0))
GLOW_LAYERS = {'iris': ((214, 226, 74), 1.0), 'vein': ((58, 204, 164), 0.85), 'fin': ((58, 204, 164), 0.85)}


def smooth(a, b, x):
    """Smoothstep: 0 below a, 1 above b."""
    t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)


def pupil_layout(p):
    """The pupil's five strips for openness [p] (0 a narrow bar, 1 a round hole), from the top: [(half width, half height, y)] (px) in the eye's frame. The
    strips are stacked without a gap; as p rises they grow taller and the outer ones narrow, so the bar rounds off into a hole."""
    p = min(1.0, max(0.0, p))
    A = PUPIL_A[0] + (PUPIL_A[1] - PUPIL_A[0]) * p
    B = PUPIL_B[0] + (PUPIL_B[1] - PUPIL_B[0]) * p
    s = smooth(0.25, 0.85, p)
    hb = 2.0 * B / PUPIL_STRIPS
    out = []
    for k in range(PUPIL_STRIPS):
        yk = (k - (PUPIL_STRIPS - 1) / 2.0) * hb
        r = abs(yk) / B
        out.append((A * (1.0 - s * (1.0 - math.sqrt(max(0.0, 1.0 - r * r)))), hb / 2.0, yk))
    return out


def mantle_r(y):
    """The mantle's radius (px) at height y."""
    t = (y - MANTLE_YC) / MANTLE_B
    return MANTLE_R * math.sqrt(max(0.0, 1.0 - t * t))


def mantle_zc(y):
    """Where the mantle's axis is in z at height y: the dome leans back a little as it rises."""
    return 6.0 + 0.10 * (MANTLE_YC - y)


def mantle_slices():
    """[(y0, y1, radius, copies)] from the top of the mantle down, thinner where the outline changes quickly."""
    out = []
    y = Y_TOP
    while y < Y_BOT - 0.5:
        step = 3.0
        while step < 8.0:
            y1 = min(y + step + 1.0, Y_BOT)
            if abs(mantle_r((y + y1) / 2) - mantle_r(y)) > 2.0 or abs(mantle_r(y1) - mantle_r(y)) > 4.0:
                break
            step += 1.0
        y1 = min(y + step, Y_BOT)
        r = mantle_r((y + y1) / 2)
        ym = (y + y1) / 2
        copies = 4 if (r >= 33.0 and ym < 6.0) else 3 if r >= 11.0 else 2
        if ym > 30.0:
            copies = min(copies, 3 if r >= 20.0 else 2)
        out.append((y, y1, r, copies))
        y = y1
    return out


def near_joint(y):
    """The spine joint nearest the height [y]: what a slice or a decoration at that height hangs from."""
    return min(range(len(SPINE)), key=lambda i: (abs(SPINE[i] - y), i))


def rel(i, p):
    """A point given in the model's frame, relative to spine joint i's pivot (whose rest pose is only a shift of y)."""
    return (p[0], p[1] - SPINE[i], p[2])


def sph(c, r, lat, lon):
    """The point on the sphere about c of radius r at latitude lat (up positive) and longitude lon (0 = toward -z, + toward +x), radians."""
    return (c[0] + r * math.cos(lat) * math.sin(lon), c[1] - r * math.sin(lat), c[2] - r * math.cos(lat) * math.cos(lon))


GROUPS = []
TARGETS = {}            # what the build has to hit exactly, for the checks


# ================================================================== the eye

def eyeball():
    """The eye: a stepped sphere of z-slices (a 12- or 16-gon each) from a flat front back into the mantle, and its pupil (five strips) and the four discs
    of the film. Returns the part `eye` (pivot at the eye's centre, relative to the joint it hangs from)."""
    slices = [(-10.3, -9.5), (-9.5, -8.6), (-8.6, -7.6), (-7.6, -6.5), (-6.5, -5.2), (-5.2, -3.6), (-3.6, -1.5), (-1.5, 1.5), (1.5, 4.5)]
    kids = []
    for j, (z0, z1) in enumerate(slices):
        zm = (z0 + z1) / 2
        r = math.sqrt(max(EYE_R ** 2 - zm * zm, 1.0))
        copies = 4 if r > 6.0 else 3 if r > 3.5 else 2
        a = K.star_half(r, copies)
        faces = {'front', 'right', 'left', 'top', 'bottom'}
        kids.append(prism(f'eb{j}', a, z0, z1, 'eyeball', axis='z', copies=copies, faces=faces, r=r, z0=z0, z1=z1, slice=j))
    # the pupil: five strips standing just in front of the eye's flat face, reshaped by setupAnim from a bar to a round hole
    rest = pupil_layout(PUPIL_REST)
    for k, (w, hh, yk) in enumerate(rest):
        c = Cu((-5.0, -0.5, -0.5), (10.0, 1.0, 1.0), 'pupil', f'pu{k}', faces={'front', 'right', 'left', 'top', 'bottom'}, k=k)
        kids.append(part(f'pu{k}', (0.0, yk, PUPIL_FRONT + 0.5), (0, 0, 0), [c]))
    # the film: four flat discs (each a quarter of the specks of a dithered, milky cataract) that show one after the other as the beast dies
    for k in range(FILMS):
        c = Cu((-FILM_R, -FILM_R, -0.25), (2 * FILM_R, 2 * FILM_R, 0.5), 'film', f'fm{k}', faces={'front'}, k=k)
        kids.append(part(f'fm{k}', (0.0, 0.0, FILM_HIDDEN_Z), (0, 0, 0), [c]))
    return part('eye', rel(J_FACE, EYE), (0, 0, 0), [], kids)


def lids():
    """The two lids: each a part that turns about the eye's centre, hung with slats along a shell round the eyeball (built closed: the upper lid's
    edge on the eye's horizontal midline, the lower lid's just above it)."""
    out = []
    Rl = 13.0
    for name, elevs, w, th, cols in (('lid_u', [4.0 + 14.0 * k for k in range(8)], 31.0, 3.4, 'up'), ('lid_l', [-4.0 - 14.0 * k for k in range(6)], 27.0, 3.0, 'low')):
        slats = []
        for k, e in enumerate(elevs):
            ww = w - (3.0 if k >= 6 else 0.0) * 1.0
            c = Cu((-ww / 2, -2.5, -(Rl + th / 2)), (ww, 5.0, th), 'lid', f'{name}{k}', faces=ALL, lid=name, k=k, e=e)
            slats.append(part(f'{name}{k}', (0, 0, 0), (-e, 0, 0), [c]))
        out.append(part(name, rel(J_FACE, EYE), (LID_OPEN[name], 0, 0), [], slats))
    return out


# ================================================================== the mantle

def mantle_parts(rng):
    """The spine (four joints, each hung from the one below) with the mantle's slices, the barnacles and the rest hung from the joint they sit on.
    Returns (the joints' parts, the nodes {joint index: part}, to hang more on)."""
    sp = [part('sp0', (0.0, SPINE[0], 0.0))]
    for i in range(1, len(SPINE)):
        sp.append(part(f'sp{i}', (0.0, SPINE[i] - SPINE[i - 1], 0.0)))
        sp[i - 1].children.append(sp[i])
    slices = mantle_slices()
    n = len(slices)
    for j, (y0, y1, r, copies) in enumerate(slices):
        ym = (y0 + y1) / 2
        a = K.star_half(r, copies)
        h = y1 - y0
        faces = set(SIDES)
        if j == 0:
            faces.add('top')
        if j == n - 1:
            faces.add('bottom')
        ji = near_joint(ym)
        zc = mantle_zc(ym)
        p = prism(f'mt{j}', a, -h / 2, h / 2, 'mantle', axis='y', copies=copies, faces=faces, pivot=(0.0, ym - SPINE[ji], zc), r=r, y0=y0, y1=y1, zc=zc, slice=j)
        sp[ji].children.append(p)
    return sp


def chain_of_rods(prefix, p0, lengths, angles, widths, thick, mat, joint=J_MOUTH, base_rot=0.0, **kw):
    """A chain of rectangular rods in the yz-plane starting at [p0] (y, z) (model frame): rod k is lengths[k] long along the direction angles[k] (degrees from
    +y toward +z: what an x rotation by that angle does to +y), widths[k] across (x) and thick[k] through; each hangs from the end of the one before.
    Returns the first part."""
    out = None
    n = len(lengths)
    for k in range(n - 1, -1, -1):
        w, t, L = widths[k], thick[k], lengths[k]
        c = Cu((-w / 2, 0.0, -t / 2), (w, L, t), mat, f'{prefix}{k}', faces=ALL, k=k, ln=L, w=w, t=t, **kw)
        if k == 0:
            piv = (0.0, p0[0] - SPINE[joint], p0[1])
            rot = (angles[0] + base_rot, 0.0, 0.0)
        else:
            piv = (0.0, lengths[k - 1], 0.0)
            rot = (angles[k] - angles[k - 1], 0.0, 0.0)
        out = part(f'{prefix}{k}', piv, rot, [c], [out] if out else [])
    return out


def ik2(p0, p1, L0, a0, L1, L2):
    """Angles (degrees, from +y toward +z) for a three-rod chain from [p0] to [p1] (y, z): the first rod at [a0] degrees, the other two found so the end
    is exactly [p1] (a two-link solution that bends toward +y... the hook)."""
    ry = p1[0] - p0[0] - L0 * math.cos(math.radians(a0))
    rz = p1[1] - p0[1] - L0 * math.sin(math.radians(a0))
    d = math.hypot(ry, rz)
    assert abs(L1 - L2) < d < L1 + L2, (d, L1, L2)
    cb = (d * d - L1 * L1 - L2 * L2) / (2 * L1 * L2)
    beta = math.acos(max(-1.0, min(1.0, cb)))          # the bend between the two rods
    base = math.atan2(rz, ry)
    a1 = base - math.atan2(L2 * math.sin(beta), L1 + L2 * math.cos(beta))
    a2 = a1 + beta
    return math.degrees(a1), math.degrees(a2)


def beak_parts():
    """The upper beak (a hooked chain of three rods, its tip at BEAK_TIP) and the lower beak (a shorter blunt pair) with a dark throat between them."""
    p0 = (-15.5, -35.0)
    L = [6.0, 5.5, 5.0]
    a1, a2 = ik2(p0, (BEAK_TIP[1], BEAK_TIP[2]), L[0], -80.0, L[1], L[2])
    TARGETS['beak_chain'] = (p0, L, [-80.0, a1, a2])
    upper = chain_of_rods('bu', p0, L, [-80.0, a1, a2], [14.0, 10.0, 5.0], [10.0, 7.0, 4.0], 'beak', joint=J_MOUTH, part_='upper')
    lower = chain_of_rods('bl', (-9.0, -35.0), [6.5, 5.0], [-62.0, -36.0], [12.0, 8.0], [6.0, 4.5], 'beak', joint=J_MOUTH, part_='lower')
    throat = part('throat', rel(J_MOUTH, (0.0, -8.0, -37.0)), (0, 0, 0), [Cu((-5.0, -4.0, -3.0), (10.0, 8.0, 6.0), 'throat', 'throat_c', faces=ALL)])
    return [upper, lower, throat]


def frame_euler(d, u):
    """Degrees (x, y, z) of the rotation (Rz Ry Rx) whose y axis runs along [d] and whose -z axis points as near [u] as is square to it."""
    d = np.asarray(d, float)
    d = d / np.linalg.norm(d)
    u = np.asarray(u, float)
    u = u - d * float(d @ u)
    u = u / np.linalg.norm(u)
    ez = -u
    ex = np.cross(d, ez)
    x, y, z = ARM.euler_axes(ex[0], ex[1], ex[2], d[0], d[1], d[2], ez[0], ez[1], ez[2])
    return math.degrees(x), math.degrees(y), math.degrees(z)


def root_dir(k):
    """Root k's rest direction (outward and forward) and the direction its underside faces (in, toward the beak), unit vectors."""
    th = math.radians(ROOT_A0 + k * ROOT_STEP)
    d = np.array([0.85 * math.cos(th), 0.85 * math.sin(th) + 0.2, -0.5])
    u = np.array([-math.cos(th), -math.sin(th), 0.0])
    return th, d / np.linalg.norm(d), u


def root_parts():
    """The eight arm-roots round the beak: each a holder (turned to its direction) with five segments hung one from the next."""
    out = []
    for k in range(ROOTS):
        th, d, u = root_dir(k)
        pivot = (ROOT_RING[0] + ROOT_RING_R * math.cos(th), ROOT_RING[1] + ROOT_RING_R * math.sin(th), ROOT_RING[2])
        segs = None
        for j in range(ROOT_SEGS, 0, -1):
            L = ROOT_LEN[j - 1]
            r = (ROOT_RAD[j - 1] + ROOT_RAD[j]) / 2
            copies = 3 if r >= 4.5 else 2
            a = K.star_half(r, copies)
            s0 = sum(ROOT_LEN[:j - 1])
            faces = set(SIDES) | ({'bottom'} if j == ROOT_SEGS else set())
            prs = prism(f'rt{k}_{j}', a, 0.0, L, 'root', axis='y', copies=copies, faces=faces,
                        pivot=((0.0, 0.0, 0.0) if j == 1 else (0.0, ROOT_LEN[j - 2], 0.0)), kids=[segs] if segs else [], seg=j, s0=s0, r0=ROOT_RAD[j - 1], r1=ROOT_RAD[j], root=k)
            segs = prs
        holder = part(f'rh{k}', rel(J_MOUTH, pivot), frame_euler(d, u), [], [segs])
        out.append(holder)
    return out


def siphon_part():
    """The siphon on the flank: a short flared funnel, three slices, pointing out and back and down."""
    d = np.array([0.85, 0.35, 0.40])
    u = np.array([0.0, -1.0, 0.0])
    radii = [4.4, 5.0, 6.2, 6.8]
    lens = [5.0, 4.5, 3.5]
    kids = None
    for j in range(len(lens), 0, -1):
        r = (radii[j - 1] + radii[j]) / 2
        a = K.star_half(r, 2)
        faces = set(SIDES) | ({'bottom'} if j == len(lens) else set())
        kids = prism(f'sf{j}', a, 0.0, lens[j - 1], 'siphon', axis='y', copies=2, faces=faces, pivot=((0, 0, 0) if j == 1 else (0.0, lens[j - 2], 0.0)),
                     kids=[kids] if kids else [], j=j, r0=radii[j - 1], r1=radii[j])
    return part('siphon', rel(J_MOUTH, SIPHON_AT), frame_euler(d, u), [], [kids])


def brow_parts():
    """The heavy brow over the eye: a ridge and two wings that droop to either side."""
    parts_ = [part('brow', rel(J_BROW, (0.0, -49.0, -41.5)), (0, 0, 0), [Cu((-13.0, -3.0, -3.5), (26.0, 6.0, 7.0), 'mantle', 'brow_c', faces=ALL, brow=1)])]
    parts_.append(part('brow_l', rel(J_BROW, (13.0, -49.0, -41.5)), (0, 0, 24.0), [Cu((0.0, -3.0, -3.5), (13.0, 6.0, 7.0), 'mantle', 'brow_l_c', faces=ALL, brow=1)]))
    parts_.append(part('brow_r', rel(J_BROW, (-13.0, -49.0, -41.5)), (0, 0, -24.0), [Cu((-13.0, -3.0, -3.5), (13.0, 6.0, 7.0), 'mantle', 'brow_r_c', faces=ALL, brow=1)]))
    return parts_


def fin_parts():
    """The two fins on the flanks of the dome: each a chain of four flat slats (cards cut to shape in the texture) hung out from the mantle, lifted a
    little; setupAnim ripples them in a wave running out to the tips. Returns the two chains, left first (+x), hung from the spine's top joint."""
    out = []
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        chain = None
        for j in range(len(FIN_SEGS), 0, -1):
            L, ch = FIN_SEGS[j - 1], FIN_CHORD[j - 1]
            c = Cu((0.0 if sgn > 0 else -L, -0.25, -0.38 * ch), (L, 0.5, ch), 'fin', f'fn_{nm}{j}', faces={'top', 'bottom'}, side=nm, j=j, L=L, ch=ch)
            piv = rel(J_BROW, (sgn * FIN_AT[0], FIN_AT[1], FIN_AT[2])) if j == 1 else (sgn * FIN_SEGS[j - 2], 0.0, 0.0)
            rot = (0.0, 0.0, -sgn * FIN_REST) if j == 1 else (0.0, 0.0, 0.0)
            chain = part(f'fn_{nm}{j}', piv, rot, [c], [chain] if chain else [])
        out.append(chain)
    return out


def surface_point(y, psi):
    """The point on the mantle at height y and angle psi (degrees round its axis from the front, + toward +x) and the outward direction there."""
    r = mantle_r(y)
    zc = mantle_zc(y)
    p = math.radians(psi)
    return (r * math.sin(p), y, zc - r * math.cos(p)), (math.sin(p), 0.0, -math.cos(p))


WEED_AT = [(-60.0, 100.0), (-46.0, -112.0), (-31.0, 138.0), (-23.0, -92.0), (-76.0, 168.0), (-12.0, 72.0), (-50.0, -40.0)]     # (y, psi degrees) where weed hangs from the mantle
HARPOON_AT = [(-52.0, 52.0), (-40.0, -74.0), (-70.0, 118.0)]
WEED_PIECES = 3
WEED_LEN = [11.0, 10.0, 9.0]
WEED_W = [5.0, 4.0, 3.0]


def decoration_parts(rng):
    """Barnacle clusters, harpoons stuck in the mantle (their ropes hang from them) and weed trailing from it: {joint index: [parts]}."""
    out = {i: [] for i in range(len(SPINE))}
    n = 0
    # barnacle clusters: not on the face (the eye, the beak, the roots), mostly on the flanks and the dome
    for _ in range(11):
        y = float(rng.uniform(-80.0, 40.0))
        psi = float(rng.uniform(-160.0, 160.0))
        if abs(psi) < 38.0 and -62.0 < y < 8.0:
            continue
        for q in range(int(rng.integers(3, 6))):
            yy = y + float(rng.uniform(-4.0, 4.0))
            pp = psi + float(rng.uniform(-5.0, 5.0))
            pos, nrm = surface_point(yy, pp)
            w, h, d = float(rng.choice([2.5, 3.0, 3.5, 4.0, 5.0])), float(rng.choice([2.0, 2.5, 3.0, 4.0])), float(rng.choice([2.5, 3.0, 3.5, 4.0]))
            ji = near_joint(yy)
            c = Cu((-w / 2, -h / 2, -d / 2), (w, h, d), 'barnacle', f'bh{n}', faces=ALL, w=w, h=h, d=d, mouth='front')
            pos = (pos[0] + nrm[0] * d * 0.3, pos[1], pos[2] + nrm[2] * d * 0.3)
            out[ji].append(part(f'bh{n}', rel(ji, pos), (0.0, -pp, 0.0), [c]))
            n += 1
    # harpoons: a broken shaft standing out of the mantle slanting up, an iron socket, splinters at the break, a frayed rope hanging from it
    for k, (y, psi) in enumerate(HARPOON_AT):
        pos, nrm = surface_point(y, psi)
        d = np.array([nrm[0], -0.55, nrm[2]])
        ji = near_joint(y)
        sh = [Cu((-1.0, -4.0, -1.0), (2.0, 26.0, 2.0), 'harpoon', f'hs{k}', faces=ALL, kind='shaft'),
              Cu((-2.0, 0.0, -2.0), (4.0, 3.5, 4.0), 'harpoon', f'hc{k}', faces=ALL, kind='iron'),
              Cu((-1.0, 21.5, -0.5), (1.0, 4.5, 1.0), 'harpoon', f'hx{k}', faces=ALL, kind='splinter'),
              Cu((0.0, 21.0, -1.0), (1.0, 3.5, 1.0), 'harpoon', f'hy{k}', faces=ALL, kind='splinter')]
        out[ji].append(part(f'hp{k}', rel(ji, (pos[0], pos[1], pos[2])), frame_euler(d, (0.0, 1.0, 0.0)), sh))
        end = np.array(pos) + d / np.linalg.norm(d) * 19.0
        rope = None
        for j in range(2, 0, -1):
            c = Cu((-0.75, 0.0, -0.25), (1.5, 8.0, 0.5), 'rope', f'hr{k}_{j}c', faces={'front', 'back'}, k=k, j=j)
            rope = part(f'hr{k}_{j}', ((end[0], end[1] - SPINE[ji], end[2]) if j == 1 else (0.0, 8.0, 0.0)), (0, 0, 0), [c], [rope] if rope else [])
        out[ji].append(rope)
    # weed: three chained strips hung from a point on the mantle, drifting with the sea
    for k, (y, psi) in enumerate(WEED_AT):
        pos, nrm = surface_point(y, psi)
        ji = near_joint(y)
        tt = (y - MANTLE_YC) / MANTLE_B
        slope = math.degrees(math.atan(max(0.0, -MANTLE_R * tt / (MANTLE_B * math.sqrt(max(1.0 - tt * tt, 0.05))))))
        tilt = slope + 10.0
        piece = None
        for j in range(WEED_PIECES, 0, -1):
            L, w = WEED_LEN[j - 1], WEED_W[j - 1]
            c = Cu((-w / 2, 0.0, -0.25), (w, L, 0.5), 'weed', f'wd{k}_{j}c', faces={'front', 'back'}, k=k, j=j, ln=L, w=w)
            piece = part(f'wd{k}_{j}', ((pos[0] + nrm[0] * 0.6, pos[1] - SPINE[ji], pos[2] + nrm[2] * 0.6) if j == 1 else (0.0, WEED_LEN[j - 2], 0.0)),
                         ((-tilt, -psi, 0.0) if j == 1 else (0.45 * tilt, 0.0, 0.0)), [c], [piece] if piece else [])
        out[ji].append(piece)
    return out


GROUPS_BUILT = {}


def build():
    """The model."""
    K.REG.clear()
    GROUPS.clear()
    rng = np.random.default_rng(seed_of(NAME, 'build'))
    sp = mantle_parts(rng)
    sp[J_FACE].children.append(eyeball())
    sp[J_FACE].children += lids()
    sp[J_MOUTH].children += beak_parts()
    sp[J_MOUTH].children += root_parts()
    sp[J_MOUTH].children.append(siphon_part())
    sp[J_BROW].children += brow_parts()
    sp[J_BROW].children += fin_parts()
    for ji, ps in decoration_parts(rng).items():
        sp[ji].children += ps
    head = part('head', (0, 0, 0), (0, 0, 0), [], [sp[0]])
    GROUPS.append(('head', [head]))
    return Model(NAME, TEX_W, TEX_H, [head])


# ================================================================== painting

SLATE, BRUISE, IVORY, SHELL, HORN, FLESH, BONE, FRILLC = ARM.SLATE, ARM.BRUISE, ARM.IVORY, ARM.SHELL, ARM.HORN, ARM.FLESH, ARM.BONE, ARM.FRILLC
WEED = np.array([(8, 14, 10), (16, 28, 18), (28, 48, 28), (44, 72, 38), (66, 98, 46), (96, 126, 58)], float)
TIMBER = np.array([(22, 17, 14), (44, 34, 27), (70, 56, 44), (98, 80, 62), (126, 106, 84), (156, 136, 110)], float)
RUST = np.array([(20, 12, 10), (46, 24, 16), (82, 40, 22), (120, 62, 30), (156, 88, 42), (190, 120, 60)], float)
HEMP = np.array([(36, 30, 22), (74, 62, 44), (112, 96, 68), (146, 128, 92), (176, 156, 116), (206, 188, 142)], float)


def mantle_coords(f):
    """(x, y, z, psi, r, u): the texel's place on the mantle: its model-space position, the angle round the mantle's axis from the front (+ toward +x),
    the mantle's radius at that height and the arc length along the circumference from the front (px)."""
    W = f.W
    x, y, z = W[..., 0], W[..., 1], W[..., 2]
    zc = 6.0 + 0.10 * (MANTLE_YC - y)
    psi = np.arctan2(x, -(z - zc))
    r = np.maximum(MANTLE_R * np.sqrt(np.clip(1.0 - ((y - MANTLE_YC) / MANTLE_B) ** 2, 0.0, 1.0)), 4.0)
    return x, y, z, psi, r, psi * r


def make_scars():
    """The mantle's old scars and harpoon punctures: ([(psi, y, angle, length, width)], [(psi, y, radius)]), a fixed set."""
    rng = np.random.default_rng(seed_of('head', 'scars'))
    scars, holes = [], []
    while len(scars) < 16:
        y, psi = float(rng.uniform(-80.0, 40.0)), float(rng.uniform(-math.pi, math.pi))
        if abs(psi) < 0.75 and -66.0 < y < 4.0:
            continue
        scars.append((psi, y, float(rng.uniform(0, math.pi)), float(rng.uniform(10.0, 40.0)), float(rng.uniform(0.9, 1.7))))
    while len(holes) < 14:
        y, psi = float(rng.uniform(-78.0, 30.0)), float(rng.uniform(-math.pi, math.pi))
        if abs(psi) < 0.75 and -66.0 < y < 4.0:
            continue
        holes.append((psi, y, float(rng.uniform(1.4, 3.4))))
    return scars, holes


SCARS = make_scars()


def make_head_veins():
    """Veins on the mantle: long ones that run up it (angle round it as a wandering function of height) and branches."""
    rng = np.random.default_rng(seed_of('head', 'veins'))
    mains, branches = [], []
    for k in range(10):
        m = dict(psi0=k * 2 * math.pi / 10 + float(rng.uniform(-0.15, 0.15)), a1=float(rng.uniform(0.08, 0.18)), f1=float(rng.uniform(0.03, 0.055)), p1=float(rng.uniform(0, 6.28)),
                 a2=float(rng.uniform(0.02, 0.05)), f2=float(rng.uniform(0.08, 0.13)), p2=float(rng.uniform(0, 6.28)), y0=float(rng.uniform(-30.0, 30.0)),
                 y1=float(rng.uniform(-84.0, -50.0)), w=float(rng.uniform(0.6, 0.9)), k=k)
        mains.append(m)
        for _ in range(int(rng.integers(2, 5))):
            branches.append(dict(parent=m, yb=float(rng.uniform(m['y1'] + 10.0, m['y0'] - 8.0)), dir=float(rng.choice([-1.0, 1.0])), slope=float(rng.uniform(0.004, 0.012)),
                                 ln=float(rng.uniform(16.0, 44.0)), a=float(rng.uniform(0.01, 0.03)), f=float(rng.uniform(0.08, 0.16)), p=float(rng.uniform(0, 6.28)),
                                 w=float(rng.uniform(0.5, 0.7))))
    return mains, branches


HVEINS = make_head_veins()


def head_vein_mask(psi, y, r):
    """1 along the mantle's veins at the place (psi radians round, y px, mantle radius r px)."""
    mains, branches = HVEINS
    out = np.zeros(np.shape(y))
    for m in mains:
        pv = m['psi0'] + m['a1'] * np.sin(y * m['f1'] + m['p1']) + m['a2'] * np.sin(y * m['f2'] + m['p2'])
        d = np.abs(np.angle(np.exp(1j * (psi - pv)))) * r
        rng_ = sstep(m['y0'], m['y0'] - 14.0, y) * (1.0 - sstep(m['y1'] + 16.0, m['y1'], y))
        pulse = 0.65 + 0.35 * np.sin(y * 0.07 + m['k'] * 1.9)
        out = np.maximum(out, (1.0 - sstep(0.12 * m['w'], 0.30 + 0.34 * m['w'], d)) * rng_ * pulse)
    for b in branches:
        m = b['parent']
        pv0 = m['psi0'] + m['a1'] * math.sin(b['yb'] * m['f1'] + m['p1']) + m['a2'] * math.sin(b['yb'] * m['f2'] + m['p2'])
        t = (b['yb'] - y) / b['ln']
        pb = pv0 + b['dir'] * b['slope'] * (b['yb'] - y) + b['a'] * np.sin(y * b['f'] + b['p']) * np.clip(t, 0, 1)
        d = np.abs(np.angle(np.exp(1j * (psi - pb)))) * r
        out = np.maximum(out, (1.0 - sstep(0.10 * b['w'], 0.28 + 0.30 * b['w'], d)) * ((t >= 0) & (t <= 1)) * (1.0 - np.clip(t, 0, 1)) ** 0.8 * 0.85)
    return out


def paint_mantle(f):
    x, y, z, psi, r, u = mantle_coords(f)
    seed = seed_of('head', 'mantle')
    W = f.W
    big = fbm(W, 22.0, seed + 1, 3)
    mid = fbm(W, 7.0, seed + 2, 3)
    fine = fbm(W, 1.5, seed + 3, 2)
    top = sstep(-24.0, -82.0, y)
    belly = sstep(-8.0, 44.0, y)
    t = 0.32 + 0.40 * (mid - 0.5) + 0.34 * (big - 0.5) + 0.05 * (fine - 0.5) - 0.06 * top + 0.08 * belly
    base = ramp(SLATE, t)
    violet = ramp(BRUISE, t + 0.05)
    k_v = np.clip(0.9 * sstep(0.50, 0.64, fbm(W, 20.0, seed + 5, 2)) + 0.30 * top, 0.0, 1.0)
    rgb = mixc(base, violet, k_v)
    pale = sstep(0.58, 0.72, fbm(W, 11.0, seed + 6, 3)) * 0.40
    rgb = mixc(rgb, np.array([126.0, 146.0, 150.0]) * (0.72 + 0.4 * mid)[..., None], pale)
    alg = sstep(0.54, 0.70, fbm(W, 6.0, seed + 7, 3)) * sstep(-16.0, 8.0, y) * 0.60
    rgb = mixc(rgb, np.array([46.0, 80.0, 58.0]) * (0.6 + 0.7 * fine)[..., None], alg)
    # muscle ribs up it, growth rings round it, a leathery pebbling
    ribs = 0.5 + 0.5 * np.cos(psi * 13.0 + 3.0 * (fbm(W, 10.0, seed + 8, 2) - 0.5))
    rings = stripes(y + 4.0 * (fbm(W, 9.0, seed + 9, 2) - 0.5), 8.5, 0.12, 0.07)
    f1b, f2b, idb, _vx, _vy = ARM.worley2(u, y, 1.7, 1.7, seed + 30)
    pebble = 0.5 + 0.5 * (1.0 - np.clip(f1b * 1.4, 0, 1))
    rgb = rgb * (1.0 - 0.22 * (1.0 - ribs) ** 3 - 0.18 * rings)[..., None] * (0.90 + 0.17 * pebble + 0.04 * idb)[..., None]
    # the face: the skin round the eye is creased in folds that radiate from it and flushed darker
    dx, dy = x - EYE[0], y - EYE[1]
    de = np.hypot(dx, dy)
    face = (z < -22.0) * (1.0 - sstep(16.0, 36.0, de))
    folds = stripes(np.arctan2(dy, dx) * 9.0 / math.pi + 1.6 * (fbm(W, 6.0, seed + 12, 2) - 0.5), 1.0, 0.18, 0.1)
    rgb = rgb * (1.0 - 0.30 * folds * face)[..., None]
    rgb = mixc(rgb, np.array([70.0, 36.0, 66.0]), 0.34 * face * sstep(13.0, 19.0, de))
    # scars (pale, puckered, stitched) and the harpoon punctures
    scars, holes = SCARS
    scar = np.zeros(y.shape)
    ticks = np.zeros(y.shape)
    for (p_c, y_c, ang, ln, wd) in scars:
        rr = mantle_r(y_c)
        uc = p_c * rr
        dxs, dys = math.cos(ang) * ln / 2, math.sin(ang) * ln / 2
        m, tt = K.line_mask(u, y, (uc - dxs, y_c - dys), (uc + dxs, y_c + dys), wd)
        d, _t = seg_dist(u, y, (uc - dxs, y_c - dys), (uc + dxs, y_c + dys))
        scar = np.maximum(scar, m)
        ticks = np.maximum(ticks, ((np.abs((tt * ln) % 2.1 - 1.05) < 0.22) & (d < 1.7) & (d > 0.55)))
    rgb = mixc(rgb, np.array([186.0, 156.0, 160.0]), np.clip(scar * 0.82 + ticks * 0.5, 0, 1))
    for (p_c, y_c, rad) in holes:
        rr = mantle_r(y_c)
        d = np.hypot(u - p_c * rr, y - y_c)
        rgb = mixc(rgb, np.array([176.0, 148.0, 154.0]), (1.0 - sstep(rad * 0.9, rad * 1.25, d)) * 0.75)
        rgb = mixc(rgb, np.array([9.0, 6.0, 12.0]), 1.0 - sstep(rad * 0.38, rad * 0.55, d))
    # barnacle crusts: clusters of cones, more of them low down and on the flanks
    cl = fbm(W, 8.5, seed + 10, 3)
    crust = sstep(0.60, 0.68, cl + 0.10 * sstep(-30.0, 30.0, y)) * (1.0 - face)
    c1, c2, ident, vx, vy = ARM.worley2(u, y, 2.6, 2.6, seed + 11)
    rad = 0.30 + 0.20 * ident
    dome = np.sqrt(np.clip(1.0 - (c1 / rad) ** 2, 0, 1))
    lit = np.clip(0.5 - 0.55 * vx / rad - 0.65 * vy / rad, -1, 1)
    cone = (c1 < rad) & (ident > 0.22)
    shell = ramp(SHELL, 0.18 + 0.30 * dome + 0.20 * lit * dome + 0.10 * (fine - 0.5)) * np.array([1.0, 0.96, 0.88])
    shell = np.where(((c1 < rad * 0.30) & cone)[..., None], shell * 0.28, shell)
    chalk = mixc(rgb * 0.75, ramp(SHELL, 0.22 + 0.25 * fine) * np.array([1.0, 0.96, 0.88]), 0.55)
    on = crust > 0.35
    rgb = np.where((on & ~cone)[..., None], chalk, rgb)
    rgb = np.where((on & cone)[..., None], shell, rgb)
    # veins under the skin
    vein = head_vein_mask(psi, y, r) * (1.0 - 0.9 * (on & cone)) * (1.0 - 0.7 * face * (de < 22.0)) * (0.45 + 0.55 * sstep(0.3, 0.7, fbm(W, 12.0, seed + 13, 2)))
    rgb = mixc(rgb, np.array([30.0, 92.0, 88.0]) * (0.7 + 0.5 * mid)[..., None], np.clip(vein * 0.45, 0, 1))
    if f.face in ('top', 'bottom') and f.cube.kw.get('axis') == 'y':
        rgb = rgb * 0.84
    return Out(rgb, None, {'vein': vein})


def paint_eyeball(f):
    """The eyeball seen from the front of the beast: sclera, a gold-green iris of radial fibres with a ring of light round the pupil (glow), the
    dark limbal ring, a wet highlight, pink veins."""
    W = f.W
    px, py, pz = W[..., 0] - EYE[0], W[..., 1] - EYE[1], W[..., 2] - EYE[2]
    rho = np.hypot(px, py)
    th = np.arctan2(rho, -pz)
    ang = np.arctan2(py, px)
    seed = seed_of('head', 'eye')
    TH_IRIS = 0.86
    fib = fbm2(ang * 6.0, th * 3.0, 1.0, 1.0, seed, 3)
    fib2 = fbm2(ang * 14.0, th * 7.0, 1.0, 1.0, seed + 1, 2)
    # the sclera: wet cream shading to a dusky rim
    scl = ramp(np.array([(70, 50, 52), (150, 126, 118), (206, 192, 168), (232, 224, 200), (244, 238, 218)], float), 0.78 - 0.55 * sstep(0.9, 1.9, th) + 0.14 * (fib - 0.5))
    # radial bloodshot veins
    vang = ang + 0.5 * np.sin(th * 5.0 + ang * 3.0)
    vrad = stripes(vang * 11.0 / (2 * math.pi), 1.0, 0.07, 0.05) * sstep(TH_IRIS + 0.08, TH_IRIS + 0.4, th) * (1.0 - sstep(1.5, 1.9, th))
    vrad = vrad * (0.45 + 0.55 * fib2)
    scl = mixc(scl, np.array([196.0, 70.0, 84.0]), np.clip(vrad * 0.8, 0, 1))
    # the iris: gold at the pupil, yellow-green, dark green at the edge, fibres running out from the pupil
    it = np.clip(th / TH_IRIS, 0.0, 1.0)
    iris = ramp(np.array([(252, 214, 86), (226, 200, 72), (168, 178, 54), (104, 140, 48), (46, 92, 44), (16, 44, 30)], float), it ** 0.9 * 0.95 + 0.22 * (fib - 0.5) + 0.10 * (fib2 - 0.5) * 2 * it)
    collar = np.exp(-((it - 0.34 - 0.04 * np.sin(ang * 9.0)) / 0.045) ** 2)
    iris = mixc(iris, np.array([255.0, 238.0, 130.0]), 0.55 * collar)
    ring = np.exp(-((it - 0.97) / 0.06) ** 2)
    iris = mixc(iris, np.array([6.0, 14.0, 10.0]), 0.85 * ring)
    rgb = np.where((th < TH_IRIS)[..., None], iris, scl)
    rgb = np.where((th < 0.20)[..., None], np.array([60.0, 40.0, 10.0]), rgb)
    # the wet highlight and a second faint one
    front = pz < -5.0
    hl = np.exp(-(((px + 3.1) / 1.5) ** 2 + ((py + 3.4) / 1.1) ** 2)) * front
    hl2 = np.exp(-(((px - 3.6) / 0.9) ** 2 + ((py - 3.0) / 0.8) ** 2)) * front
    rgb = mixc(rgb, np.array([252.0, 252.0, 244.0]), np.clip(hl * 1.2 + hl2 * 0.45, 0, 1))
    glow_iris = np.where(th < TH_IRIS, np.clip(0.30 + 0.70 * np.exp(-((it - 0.30) / 0.22) ** 2) + 0.35 * collar - 0.9 * ring, 0.0, 1.0) * (0.55 + 0.45 * fib), 0.0) * (th > 0.16)
    glow_vein = np.clip(vrad * 0.9, 0, 1)
    return Out(rgb, None, {'iris': glow_iris, 'vein': glow_vein})


def paint_pupil(f):
    x, y = f.P[..., 0], f.P[..., 1]
    rgb = np.ones(f.P.shape[:2] + (3,)) * np.array([5.0, 5.0, 7.0])
    rgb = rgb + 12.0 * np.exp(-(((x + 0.4) / 0.5) ** 2 + ((y + 0.4) / 0.5) ** 2))[..., None]
    return Out(rgb)


def paint_film(f):
    """One disc of the milky film over the dying eye: a round cataract of pale blue-grey, thickest in the middle, dithered so that each of the four
    discs holds a quarter of its specks (together they fill the whole disc)."""
    x, y = f.P[..., 0], f.P[..., 1]
    seed = seed_of('head', 'film')
    n = fbm(f.W, 2.6, seed, 3)
    rho = np.hypot(x, y)
    k = f.kw['k']
    cell = np.floor((x + 40.0) * HD)
    celly = np.floor((y + 40.0) * HD)
    bucket = np.floor(hash01(cell, celly, 0, seed + 5) * FILMS)
    reach = FILM_R * (0.93 + 0.12 * (fbm(f.W, 3.0, seed + 2, 2) - 0.5))
    solid = (rho < reach) & (bucket == k)
    rgb = ramp(np.array([(120, 128, 142), (176, 184, 196), (214, 220, 228), (236, 238, 242)], float), 0.62 + 0.38 * n - 0.20 * sstep(5.0, 10.0, rho))
    web = stripes(np.arctan2(y, x) * 7.0 / math.pi + 2.0 * (n - 0.5), 1.0, 0.1, 0.06) * sstep(2.0, 8.0, rho)
    rgb = mixc(rgb, np.array([150.0, 168.0, 186.0]), 0.4 * web)
    return Out(rgb, solid.astype(float))


def paint_fin(f):
    """A slat of a fin: a ragged membrane that narrows toward the tip, ribbed with rays that run out from the root, slate-blue shading to bruise-violet,
    pale along the rim; the rays carry a little glow."""
    kw = f.kw
    x, z = f.P[..., 0], f.P[..., 2]
    s0 = sum(FIN_SEGS[:kw['j'] - 1])
    D = s0 + np.abs(x)
    tot = sum(FIN_SEGS)
    seed = seed_of('fin', kw['side'])
    C = 30.0 * (1.0 - np.clip(D / tot, 0, 1) ** 1.5)
    n = fbm2(D, z, 2.4, 2.4, seed, 2)
    front = 0.36 * C * (0.85 + 0.3 * n)
    rear = 0.64 * C * (0.78 + 0.44 * fbm2(D, z * 0.0 + 1.0, 3.0, 3.0, seed + 1, 2))
    tear = sstep(0.66, 0.78, fbm2(D, z, 1.8, 1.8, seed + 2, 2)) * sstep(0.3, 0.9, np.abs(z) / np.maximum(C * 0.5, 1.0))
    solid = (z > -front) & (z < rear) & (tear < 0.5) & (D < tot - 0.3)
    ang = np.arctan2(z, np.maximum(D, 0.5))
    ray = stripes(ang * 16.0 / math.pi + 0.6 * (n - 0.5), 1.0, 0.22, 0.1)
    edge = np.clip(np.minimum((z + front) / 1.6, (rear - z) / 1.6), 0.0, 1.0)
    mem = ramp(FRILLC, 0.34 + 0.26 * (1.0 - np.clip(D / tot, 0, 1)) + 0.25 * (n - 0.5))
    mem = mixc(mem, ramp(BRUISE, 0.5 + 0.3 * (n - 0.5)), 0.25 + 0.35 * sstep(0.1, 0.9, np.abs(z) / np.maximum(C * 0.5, 1.0)))
    rgb = mixc(mem, ramp(SLATE, 0.30 + 0.2 * n), ray * 0.8)
    rgb = mixc(rgb, np.array([170.0, 188.0, 190.0]), 0.5 * (1.0 - edge))
    glow = ray * sstep(1.5, 4.5, D) * 0.35
    return Out(rgb, solid.astype(float), {'fin': glow})


def paint_lid(f):
    """The lids: the mantle's skin (heavily creased across, darker along the edge where the lids meet)."""
    out = paint_mantle(f)
    W = f.W
    py, pz = W[..., 1] - EYE[1], W[..., 2] - EYE[2]
    el = np.degrees(np.arctan2(-py, -pz))                         # elevation round the eye: 0 on the horizontal midline
    seed = seed_of('head', 'lid')
    wr = stripes(np.abs(el) + 2.0 * (fbm(W, 3.0, seed, 2) - 0.5), 9.0, 0.16, 0.1)
    rgb = out.rgb * (1.0 - 0.34 * wr)[..., None]
    edge = np.exp(-((np.abs(el) - 3.5) / 5.0) ** 2) * (np.abs(el) < 22.0)
    rgb = mixc(rgb, np.array([34.0, 16.0, 34.0]), 0.55 * edge)
    rgb = mixc(rgb, np.array([128.0, 70.0, 100.0]), 0.28 * np.exp(-((np.abs(el) - 14.0) / 6.0) ** 2))
    out.rgb = rgb
    out.glow = {k: v * 0.0 for k, v in out.glow.items()}
    return out


def paint_beak(f):
    kw = f.kw
    seed = f.seed()
    W = f.W
    y = f.P[..., 1]
    n = fbm(W, 2.4, seed, 3)
    streak = stripes(W[..., 0] * 1.6 + 0.8 * (fbm(W, 3.0, seed + 1, 2) - 0.5) * 3, 3.2, 0.16, 0.09)
    t = 0.26 + 0.20 * (n - 0.5) + 0.12 * streak + 0.20 * sstep(-0.5, 0.5, np.sin(W[..., 0] * 0.9)) * 0.0
    rgb = ramp(HORN, t)
    # the highlight along the ridge, the pale bone at the root, the cracks (lines where a cellular pattern's cells meet) and the chips
    ridge = np.exp(-(W[..., 0] / 1.3) ** 2)
    rgb = mixc(rgb, np.array([120.0, 118.0, 138.0]), 0.34 * ridge * sstep(0.2, 0.9, n))
    if kw.get('part_') == 'upper' and kw['k'] == 0:
        rgb = mixc(rgb, np.array([176.0, 160.0, 126.0]), 0.55 * (1.0 - sstep(0.0, 6.0, y)))
    c1, c2, _id, _vx, _vy = ARM.worley2(W[..., 0] * 1.0 + W[..., 2] * 0.3, W[..., 1] + W[..., 2] * 0.5, 4.2, 3.4, seed + 5)
    crack = (1.0 - sstep(0.0, 0.07, c2 - c1)) * (0.5 + 0.5 * (_id > 0.45))
    rgb = rgb * (1.0 - 0.80 * crack)[..., None]
    rgb = mixc(rgb, np.array([158.0, 150.0, 132.0]), 0.5 * (1.0 - sstep(0.0, 0.04, c2 - c1)) * (_id > 0.8) * 0.0)
    chip = (fbm(W, 1.8, seed + 6, 2) > 0.74) & (n > 0.5)
    rgb = np.where(chip[..., None], rgb * 1.5 + 18.0, rgb)
    return Out(rgb)


def paint_throat(f):
    seed = f.seed()
    n = fbm(f.W, 2.0, seed, 3)
    rgb = ramp(FLESH, 0.28 + 0.4 * n)
    wet = (hash01(np.floor(f.W[..., 0] * HD), np.floor(f.W[..., 1] * HD), np.floor(f.W[..., 2] * HD), seed) > 0.97)
    return Out(np.where(wet[..., None], np.array([180.0, 90.0, 96.0]), rgb))


def paint_root(f):
    """An arm-root: the arm's own skin along its length, with the two rows of suckers painted on its underside."""
    kw = f.kw
    x, y, z = K.turned(f, kw['turn'], 'y')
    S = kw['s0'] + y
    phi = np.arctan2(x, -z)
    L = ROOT_LEN[kw['seg'] - 1]
    R = kw['r0'] + (kw['r1'] - kw['r0']) * np.clip(y / L, 0, 1)
    tot = sum(ROOT_LEN)
    out = ARM.skin_core(f, S, phi, R, S / tot, 100 + kw['root'] * 7 + kw['seg'], 'headroot', crust_k=0.0, scar_k=0.0, vent_w=0.78)
    rgb = out.rgb
    # suckers: discs on two rows each side of the midline of the underside, spaced about their own width apart
    for sgn in (1.0, -1.0):
        row_s = (S + (4.2 if sgn > 0 else 0.0)) % 8.4 - 4.2
        dd = np.hypot(row_s, (phi - sgn * 0.62) * R)
        rad = np.maximum(0.46 * R, 0.8)
        inside = dd < rad
        k = dd / np.maximum(rad, 1e-3)
        cup = ramp(ARM.SUCK, 0.06 + 0.5 * sstep(0.0, 0.6, k))
        rim = ramp(IVORY, 0.86 - 0.1 * np.cos(np.arctan2(row_s, (phi - sgn * 0.62) * R) - 2.4))
        col = np.where((k < 0.58)[..., None], cup, rim)
        use = inside & (np.abs(phi) < 1.35) & (np.abs(phi) > 0.12)
        rgb = np.where(use[..., None], col, rgb)
    return Out(rgb, None, out.glow)


def paint_siphon(f):
    kw = f.kw
    seed = f.seed()
    x, y, z = K.turned(f, kw['turn'], 'y')
    n = fbm(f.W, 3.0, seed, 3)
    rgb = ramp(SLATE, 0.26 + 0.3 * n)
    rgb = rgb * (1.0 - 0.3 * stripes(y + kw['j'] * 2.0, 2.2, 0.2, 0.1))[..., None]
    if f.face == 'bottom':
        rho = np.hypot(x, z) / max(kw['r1'], 1.0)
        rgb = np.where((rho < 0.72)[..., None], np.array([5.0, 6.0, 9.0]), mixc(rgb, np.array([60.0, 40.0, 70.0]), 0.4))
    return Out(rgb)


def paint_barnacle_h(f):
    out = ARM.paint_barnacle(f)
    return out


def paint_weed(f):
    """A strip of the weed that trails from the beast: a ragged ribbon with a pale midrib and slanting veins that narrows to a point over the
    three strips it is made of (the shape is cut out in the alpha, so the strips are bounded by their own outline, not by a box)."""
    kw = f.kw
    x, y = f.P[..., 0], f.P[..., 1]
    S = sum(WEED_LEN[:kw['j'] - 1]) + y
    tot = sum(WEED_LEN)
    seed = seed_of('weed', kw['k'])
    wob = fbm2(S, x * 0.0 + kw['k'] * 3.0, 3.0, 3.0, seed, 2)
    hw = 2.35 * (1.0 - 0.82 * np.clip(S / tot, 0, 1) ** 1.25) * (0.74 + 0.5 * wob) + 0.22 * np.sin(S * 0.9 + kw['k'] * 2.0)
    hw = np.minimum(hw, kw['w'] / 2 - 0.08)
    tear = sstep(0.70, 0.80, fbm2(S, x, 1.6, 1.6, seed + 1, 2)) * sstep(0.7, 1.3, np.abs(x))
    solid = (np.abs(x) < hw) & (tear < 0.5) & (S < tot - 0.4)
    k01 = np.clip(1.0 - np.abs(x) / np.maximum(hw, 0.2), 0, 1)
    n = fbm(f.W, 2.2, seed + 2, 3)
    t = 0.30 + 0.30 * (n - 0.5) + 0.28 * k01 ** 0.7 + 0.12 * sstep(tot, 0.0, S) * 0.0
    rgb = ramp(WEED, t)
    rgb = mixc(rgb, np.array([84.0, 78.0, 40.0]), 0.30 * sstep(0.55, 0.8, fbm(f.W, 3.5, seed + 3, 2)))
    rib = np.exp(-(x / 0.22) ** 2)
    veins = stripes(S * 1.05 + np.abs(x) * 2.4, 2.6, 0.12, 0.07) * (1.0 - rib)
    rgb = rgb * (1.0 - 0.30 * veins)[..., None]
    rgb = mixc(rgb, np.array([150.0, 168.0, 96.0]), 0.34 * rib)
    return Out(rgb, solid.astype(float))


def paint_rope(f):
    """The frayed rope trailing from a harpoon: two-strand lay (slanting stripes), unravelling into loose strands at the end of the last strip."""
    kw = f.kw
    x, y = f.P[..., 0], f.P[..., 1]
    seed = seed_of('rope', kw['k'], kw['j'])
    lay = stripes(y * 1.6 + x * 3.2, 1.0, 0.5, 0.12)
    n = fbm(f.W, 1.0, seed, 2)
    rgb = ramp(HEMP, 0.32 + 0.30 * (n - 0.5) + 0.22 * lay)
    rgb = mixc(rgb, np.array([46.0, 66.0, 44.0]), 0.28 * sstep(0.5, 0.75, fbm(f.W, 2.0, seed + 1, 2)))
    solid = np.ones(x.shape)
    if kw['j'] == 2:
        col = np.floor((x + 0.75) * 2.0 * HD / 2.0)
        cut = 5.4 + 2.6 * hash01(col, 0, 0, seed)
        solid = (y < cut).astype(float)
    return Out(rgb, solid)


def paint_harpoon(f):
    """A harpoon broken off in the beast: the iron collar still bedded in the skin, rust-brown; the shaft's wet grey-brown timber, bleached
    toward the break; fresh pale splinters at the break; a lashing of hemp round the shaft."""
    kw = f.kw
    seed = f.seed()
    x, y, z = f.P[..., 0], f.P[..., 1], f.P[..., 2]
    kind = kw['kind']
    if kind == 'iron':
        n = fbm(f.W, 0.9, seed, 3)
        rgb = ramp(RUST, 0.28 + 0.34 * n + 0.18 * stripes(y, 1.7, 0.2, 0.1))
        pit = fbm(f.W, 0.55, seed + 1, 2) > 0.70
        return Out(np.where(pit[..., None], rgb * 0.45, rgb))
    grain = fbm2(x + z, y, 0.55, 6.0, seed, 3)
    n = fbm(f.W, 2.0, seed + 2, 2)
    if kind == 'splinter':
        return Out(ramp(TIMBER, 0.72 + 0.22 * (grain - 0.5) + 0.1 * (n - 0.5)))
    t = 0.34 + 0.42 * (grain - 0.5) + 0.10 * (n - 0.5) + 0.16 * sstep(6.0, 21.0, y)
    rgb = ramp(TIMBER, t)
    rgb = mixc(rgb, np.array([52.0, 84.0, 56.0]), 0.34 * (1.0 - sstep(-2.0, 8.0, y)) * sstep(0.35, 0.7, fbm(f.W, 2.5, seed + 3, 2)))
    band = (y > 13.5) & (y < 15.5)
    lash = ramp(HEMP, 0.36 + 0.24 * stripes(y * 1.8 + (x + z) * 0.6, 1.0, 0.5, 0.1) + 0.12 * (n - 0.5))
    return Out(np.where(band[..., None], lash, rgb))


MATS = {'mantle': paint_mantle, 'eyeball': paint_eyeball, 'pupil': paint_pupil, 'film': paint_film, 'lid': paint_lid, 'beak': paint_beak, 'throat': paint_throat,
        'root': paint_root, 'siphon': paint_siphon, 'barnacle': paint_barnacle_h, 'weed': paint_weed, 'rope': paint_rope, 'harpoon': paint_harpoon, 'fin': paint_fin}
DOUBLE = {'weed', 'rope', 'fin', 'film'}


def painted():
    """Builds the model, packs its atlas and paints it: (model, scene, texture (1024x1024 RGBA), glow map (the same))."""
    model = build()
    K.pack_atlas(model)
    scene = K.SScene(model, DOUBLE)
    tex, layers = K.paint_model(scene, MATS, GLOW_LAYERS, TEX_W, TEX_H)
    return model, scene, tex, K.glow_map(layers, GLOW_LAYERS)


# ================================================================== the pose: what MaelgraspModel.setupAnim does

class HeadState:
    """What the renderer gives the model (see MaelgraspRenderState.kt), with the values of a head idling at the surface."""

    def __init__(self, **kw):
        self.time = 0.0
        self.rise = 1.0
        self.lookYaw = 0.0
        self.lookPitch = 0.0
        self.pupil = 0.3
        self.lid = 0.0
        self.beak = 0.0
        self.lunge = 0.0
        self.ink = 0.0
        self.rage = 0.0
        self.dying = 0.0
        self.glow = 0.0
        for k, v in kw.items():
            if not hasattr(self, k):
                raise AttributeError(k)
            setattr(self, k, v)


_REST = {}


def rest_of(name):
    """(position px, rotation radians) of the part [name] at rest, from the built model."""
    if not _REST:
        for p in build().all_parts():
            _REST[p.name] = (np.array(p.pivot, float), np.array([math.radians(a) for a in p.rot], float))
    return _REST[name]


def clampf(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def shear(y, lean):
    """How far (px, along z) the mantle at height [y] has been shifted by a lean of [lean] px at its very top: nothing at the base, more the higher it is."""
    return lean * min(1.2, max(0.0, (SPINE[0] - y) / (SPINE[0] - Y_TOP))) ** 1.3


def root_blend(k, rise, lunge, dying, t, rage):
    """Root k's direction at this moment (unit vector): the splayed crown of a head at the surface, trailing below when it is low in the water,
    reaching forward as it lunges, or hanging limp as it dies, with a slow sway of its own."""
    th = math.radians(ROOT_A0 + k * ROOT_STEP)
    c, s = math.cos(th), math.sin(th)
    splay = (0.85 * c, 0.85 * s + 0.2, -0.5)
    trail = (0.35 * c, 1.0, 0.30)
    reach = (0.30 * c, 0.30 * s, -1.0)
    limp = (0.55 * c, 1.0, -0.15)
    wd = dying
    wl = (1.0 - dying) * lunge
    wt = (1.0 - dying) * (1.0 - lunge) * (1.0 - smooth(0.15, 0.9, rise))
    ws = 1.0 - wd - wl - wt
    sw = 0.07 * (1.0 + 0.8 * rage) * (1.0 - 0.6 * dying)
    d = [ws * splay[i] + wt * trail[i] + wl * reach[i] + wd * limp[i] for i in range(3)]
    d[0] += sw * math.sin(t * 0.037 + 1.3 * k)
    d[1] += 0.6 * sw * math.sin(t * 0.029 + 0.8 * k + 1.0)
    d[2] += 0.5 * sw * math.sin(t * 0.043 + 2.1 * k + 2.0)
    n = math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
    return th, (d[0] / n, d[1] / n, d[2] / n)


def pose(st):
    """{part: {pos (px), rot (radians), scale, visible}} for state [st] (see the Kotlin setupAnim, which this follows line by line); parts it does not
    mention stay as they were built."""
    P = {}

    def put(n, **kw):
        P.setdefault(n, {}).update(kw)

    def rrot(n, dx=0.0, dy=0.0, dz=0.0):
        r = rest_of(n)[1]
        return (r[0] + dx, r[1] + dy, r[2] + dz)

    def rpos(n, dx=0.0, dy=0.0, dz=0.0):
        r = rest_of(n)[0]
        return (r[0] + dx, r[1] + dy, r[2] + dz)

    t = st.time
    rise = clampf(st.rise, 0.0, 1.0)
    lunge = clampf(st.lunge, 0.0, 1.0)
    rage = clampf(st.rage, 0.0, 1.0)
    dying = clampf(st.dying, 0.0, 1.0)
    ink = clampf(st.ink, 0.0, 1.0)
    beak = clampf(st.beak, 0.0, 1.0)
    alive = 1.0 - dying
    look_yaw = math.radians(clampf(st.lookYaw, -70.0, 70.0))
    look_pitch = math.radians(clampf(st.lookPitch, -50.0, 50.0))
    # ---- the lean: the whole head surges forward and ducks as it lunges, and the mantle shears forward joint by joint (slices stay level, so no wedge
    # opens at a joint); it slumps back as it dies; the dome sways a little
    put('head', pos=(0.0, 5.0 * lunge + 3.0 * dying, -9.0 * lunge))
    lean = -16.0 * lunge + 14.0 * dying
    for i in range(1, len(SPINE)):
        sx = 0.012 * math.sin(t * 0.031) * alive if i == len(SPINE) - 1 else 0.0
        sz = (0.022 * math.sin(t * 0.027 + 1.0) * alive * rise if i == len(SPINE) - 1 else 0.012 * math.sin(t * 0.023 + 0.5 * i) * alive * rise if i == len(SPINE) - 2 else 0.0) + 0.02 * dying
        put(f'sp{i}', pos=rpos(f'sp{i}', 0.0, 0.0, shear(SPINE[i], lean) - shear(SPINE[i - 1], lean)), rot=rrot(f'sp{i}', sx, 0.0, sz))
    # ---- the mantle heaves: a slow breath that runs up it, deeper at the surface and in a rage, drawn in as it lunges; still as it dies
    amp = (0.010 + 0.016 * rise + 0.014 * rage) * alive
    for j, (y0, y1, r, cp) in enumerate(mantle_slices()):
        ym = (y0 + y1) / 2
        a = amp * math.sin(t * 0.085 - 0.13 * j) - 0.05 * lunge + 0.03 * dying
        put(f'mt{j}', pos=rpos(f'mt{j}', 0.0, 0.0, shear(ym, lean) - shear(SPINE[near_joint(ym)], lean)), scale=(1.0 + a, 1.0, 1.0 + a))
    # ---- the eye turns in its socket (rolling up as it dies) with a faint tremor
    put('eye', rot=(-look_pitch - 0.55 * dying + 0.010 * math.sin(t * 0.37 + 1.0) * alive, look_yaw + 0.012 * math.sin(t * 0.29) * alive, 0.0))
    p = clampf(st.pupil + 0.03 * math.sin(t * 0.21) * alive + 0.6 * dying * (1.0 - st.pupil), 0.0, 1.0)
    for k, (w, hh, yk) in enumerate(pupil_layout(p)):
        put(f'pu{k}', pos=(0.0, yk, PUPIL_FRONT + 0.5), scale=(w / 5.0, hh / 0.5, 1.0))
    for k in range(FILMS):
        shown = dying > 0.06 + 0.11 * k
        put(f'fm{k}', pos=(0.0, 0.0, FILM_Z if shown else FILM_HIDDEN_Z), visible=shown)
    # ---- the lids: shut when told to, half shut as it dies, narrowed in a rage, following the eye's gaze up and down (built closed, they rest turned open)
    closed = clampf(max(st.lid, 0.55 * dying), 0.0, 1.0)
    openness = 1.0 - closed
    put('lid_u', rot=rrot('lid_u', -LID_OPEN_RAD[0] * closed + 0.20 * rage * openness - 0.35 * look_pitch * openness))
    put('lid_l', rot=rrot('lid_l', -LID_OPEN_RAD[1] * closed - 0.12 * rage * openness - 0.20 * look_pitch * openness))
    # ---- the brow lifts a little when the pupil opens, and scowls down in a rage and as the lids shut
    drop = -0.8 * (clampf(st.pupil, 0.0, 1.0) - 0.3) + 1.8 * rage + 1.0 * closed
    put('brow', pos=rpos('brow', 0.0, drop, 0.0))
    put('brow_l', pos=rpos('brow_l', 0.0, drop, 0.0), rot=rrot('brow_l', 0.0, 0.0, 0.35 * rage))
    put('brow_r', pos=rpos('brow_r', 0.0, drop, 0.0), rot=rrot('brow_r', 0.0, 0.0, -0.35 * rage))
    # ---- the beak: the lower one drops, the upper lifts; the throat shows; it chatters faintly at rest and grinds in a rage
    chat = (0.03 * math.sin(t * 0.11) + 0.05 * rage * math.sin(t * 0.83)) * alive
    put('bu0', rot=rrot('bu0', -0.20 * beak))
    put('bl0', rot=rrot('bl0', 0.70 * beak + chat))
    put('bl1', rot=rrot('bl1', 0.15 * beak))
    put('throat', pos=rpos('throat', 0.0, 3.0 * beak, 0.0), scale=(1.0, 1.0 + 1.2 * beak, 1.0))
    # ---- the arm-roots: each a holder turned to its direction (see root_blend) and five segments that curl toward their suckers and writhe in waves
    for k in range(ROOTS):
        th, d = root_blend(k, rise, lunge, dying, t, rage)
        ux, uy = -math.cos(th), -math.sin(th)
        dot = ux * d[0] + uy * d[1]
        ux, uy, uz = ux - dot * d[0], uy - dot * d[1], -dot * d[2]
        ul = math.sqrt(ux * ux + uy * uy + uz * uz)
        if ul < 1.0e-4:
            ux, uy, uz, ul = 0.0, 0.0, 1.0, 1.0
        ux, uy, uz = ux / ul, uy / ul, uz / ul
        ez = (-ux, -uy, -uz)
        ex = (d[1] * ez[2] - d[2] * ez[1], d[2] * ez[0] - d[0] * ez[2], d[0] * ez[1] - d[1] * ez[0])
        rx, ry, rz = ARM.euler_axes(ex[0], ex[1], ex[2], d[0], d[1], d[2], ez[0], ez[1], ez[2])
        put(f'rh{k}', rot=(rx, ry, rz))
        for j in range(1, ROOT_SEGS + 1):
            wave = math.sin(t * 0.082 - 0.85 * j + 1.7 * k)
            wave2 = math.sin(t * 0.057 - 0.7 * j + 1.1 * k + 2.0)
            amp_j = (0.10 + 0.05 * j) * (1.0 + 0.9 * rage) * (1.0 + 0.6 * (1.0 - rise)) * (1.0 - 0.75 * dying) * (1.0 - 0.5 * lunge)
            curl = (0.16 + 0.05 * j) * (1.0 - 0.75 * lunge) + 0.30 * dying * (0.6 + 0.1 * j)
            put(f'rt{k}_{j}', rot=(-curl + amp_j * wave, 0.0, 0.8 * amp_j * wave2))
    # ---- the siphon breathes out with the mantle, and flares and pulses as it spews ink
    breath = 0.05 * math.sin(t * 0.085 - 0.9) * alive
    pulse = 0.5 + 0.5 * math.sin(t * 0.85)
    pulse2 = 0.5 + 0.5 * math.sin(t * 0.85 + 1.0)
    put('sf1', scale=(1.0 + breath * 0.5 + 0.15 * ink, 1.0, 1.0 + breath * 0.5 + 0.15 * ink))
    put('sf2', scale=(1.0 + breath + 0.30 * ink + 0.10 * ink * pulse, 1.0, 1.0 + breath + 0.30 * ink + 0.10 * ink * pulse))
    put('sf3', scale=(1.0 + breath + 0.45 * ink + 0.18 * ink * pulse2, 1.0 + 0.40 * ink * pulse2, 1.0 + breath + 0.45 * ink + 0.18 * ink * pulse2))
    put('siphon', rot=rrot('siphon', -0.20 * ink - 0.06 * ink * pulse, 0.0, 0.0))
    # ---- the fins ripple in a wave that runs out to the tips; they sweep back as it lunges and droop as it dies
    fa = (0.5 + 0.5 * rise) * (1.0 + 0.9 * rage) * alive
    for side, sgn, nm in ((0, 1.0, 'l'), (1, -1.0, 'r')):
        for j in range(1, len(FIN_SEGS) + 1):
            flap = (0.10 + 0.045 * j) * fa * math.sin(t * 0.11 - 0.8 * j + 1.9 * side)
            flap2 = 0.04 * j * fa * math.sin(t * 0.07 - 0.6 * j + 0.7 * side + 1.0)
            dz = -sgn * flap + sgn * 0.25 * dying * (0.5 + 0.2 * j)
            dy = (-sgn * 0.5 * lunge if j == 1 else 0.0)
            put(f'fn_{nm}{j}', rot=rrot(f'fn_{nm}{j}', flap2, dy, dz))
    # ---- the weed and the harpoons' ropes drift in the water
    drive = 1.0 + 0.6 * lunge + 0.5 * rage
    for k in range(len(WEED_AT)):
        for j in range(1, WEED_PIECES + 1):
            put(f'wd{k}_{j}', rot=rrot(f'wd{k}_{j}', 0.10 * drive * math.sin(t * 0.055 + 1.9 * k - 0.9 * j), 0.0, 0.08 * drive * math.sin(t * 0.043 + 2.3 * k - 0.7 * j)))
    for k in range(len(HARPOON_AT)):
        for j in (1, 2):
            put(f'hr{k}_{j}', rot=rrot(f'hr{k}_{j}', 0.14 * drive * math.sin(t * 0.047 + 2.1 * k - 0.9 * j), 0.0, 0.10 * drive * math.sin(t * 0.039 + 1.4 * k - 0.6 * j)))
    return P


def euler_xyz(exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz):
    return ARM.euler_axes(exx, exy, exz, eyx, eyy, eyz, ezx, ezy, ezz)


# ================================================================== the Kotlin

KT_HEAD = r"""package io.github.vaspyyy.guildmark.client.sea

import io.github.vaspyyy.guildmark.Guildmark
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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Maelgrasp's head: the colossal horror that comes up beside the ship. Models are in pixels with y down and the creature facing -z
 * (its right is -x); the origin is the waterline under the middle of the head, which stands about 5.5 blocks above it and reaches 4
 * below. The eye's centre is at (0, -32, -37) (radius 11) and the beak's tip at (0, -6, -46): the game aims at them.
 *
 * It is a scarred, bulbous mantle (a stack of round slices hung from a spine of six joints, which the lean shears forward joint by joint so
 * no gap opens), one enormous wet eye on its face (a stepped sphere that turns in its socket: [MaelgraspRenderState.lookYaw] to its right,
 * [MaelgraspRenderState.lookPitch] up; a gold-green iris whose ring glows; a pupil of five strips that goes from a narrow bar to a round
 * hole with [MaelgraspRenderState.pupil]; heavy lids that shut and blink with [MaelgraspRenderState.lid], follow the gaze, narrow in a rage and
 * half close as it dies; four dithered discs of milky film that cloud over it one after the other as it dies), a heavy brow, a black hooked
 * cracked beak whose lower half drops with [MaelgraspRenderState.beak], a crown of eight short arm-roots (suckers on their inner side) that
 * trail below when it sits low in the water, splay when it has surfaced, reach forward as it lunges and hang limp as it dies, writhing in
 * waves of their own all the while, a siphon on its flank that flares and pulses with [MaelgraspRenderState.ink], two ripple fins on the
 * flanks of the dome, barnacle crusts, harpoons broken off in it with their ropes and weed trailing from it. Its mantle heaves with a slow breath that
 * runs up it, deeper when it has risen and in a rage, and is drawn in as it lunges. Veins under its skin, the fins' rays and the ring of the
 * iris glow (the glow map).
 *
 * The geometry comes from art/scripts/sea_head.py (edit it there: it writes this file's body and the textures, and its `pose()` is the same
 * animation in Python, run for the previews and checked against this class by sea_monsters.py). The texture is HD: the model declares
 * @@W@@x@@H@@ and the PNGs are painted at twice that. It is drawn with [RenderTypes.entityCutout] (the fins, weed, ropes and film are cut to shape in the
 * texture's alpha, and that type does not cull back faces); the glow layer is the same model drawn again with [RenderTypes.eyes] over
 * maelgrasp_glow.png, tinted by [MaelgraspRenderState.glow].
 */
class MaelgraspModel(root: ModelPart) : EntityModel<MaelgraspRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The head as a whole, the spine's joints (the first never moves) and the mantle's slices
    private val head = part("head")
    private val spine = Array(SPINE_Y.size) { part("sp$it") }
    private val slices = Array(SLICE_Y.size) { part("mt$it") }

    // The eye, its pupil's strips and the discs of film that cloud it, the lids and the brow
    private val eye = part("eye")
    private val pupil = Array(PUPIL_STRIPS) { part("pu$it") }
    private val films = Array(FILMS) { part("fm$it") }
    private val lidU = part("lid_u")
    private val lidL = part("lid_l")
    private val brow = part("brow")
    private val browL = part("brow_l")
    private val browR = part("brow_r")

    // The beak (the upper one's base, the lower one's two rods, the throat between them) and the crown of arm-roots
    private val beakUpper = part("bu0")
    private val jawA = part("bl0")
    private val jawB = part("bl1")
    private val throat = part("throat")
    private val holders = Array(ROOTS) { part("rh$it") }
    private val roots = Array(ROOTS) { k -> Array(ROOT_SEGS) { part("rt${k}_${it + 1}") } }

    // The siphon, the fins (left, right), the weed and the harpoons' ropes
    private val siphon = part("siphon")
    private val flare = Array(3) { part("sf${it + 1}") }
    private val fins = Array(2) { side -> Array(FIN_SEGS) { part("fn_${if (side == 0) "l" else "r"}${it + 1}") } }
    private val weeds = Array(WEEDS) { k -> Array(WEED_PIECES) { part("wd${k}_${it + 1}") } }
    private val ropes = Array(HARPOONS) { k -> Array(2) { part("hr${k}_${it + 1}") } }

    // Scratch: nothing is allocated per frame
    private val pupilW = FloatArray(PUPIL_STRIPS)
    private val pupilH = FloatArray(PUPIL_STRIPS)
    private val pupilY = FloatArray(PUPIL_STRIPS)
    private val dir = FloatArray(3)

    override fun setupAnim(state: MaelgraspRenderState) {
        super.setupAnim(state)
        val t = state.time
        val rise = state.rise.coerceIn(0f, 1f)
        val lunge = state.lunge.coerceIn(0f, 1f)
        val rage = state.rage.coerceIn(0f, 1f)
        val dying = state.dying.coerceIn(0f, 1f)
        val ink = state.ink.coerceIn(0f, 1f)
        val beak = state.beak.coerceIn(0f, 1f)
        val alive = 1f - dying
        val lookYaw = state.lookYaw.coerceIn(-70f, 70f) * RAD
        val lookPitch = state.lookPitch.coerceIn(-50f, 50f) * RAD

        // The lean: the whole head surges forward and ducks as it lunges, and the mantle shears forward joint by joint (the slices stay level, so no
        // wedge opens at a joint); it slumps back as it dies; the dome sways a little
        head.y += 5f * lunge + 3f * dying
        head.z += -9f * lunge
        val lean = -16f * lunge + 14f * dying
        val top = spine.size - 1
        for (i in 1..top) {
            val sx = if (i == top) 0.012f * sin(t * 0.031f) * alive else 0f
            val sz = (if (i == top) 0.022f * sin(t * 0.027f + 1f) * alive * rise
            else if (i == top - 1) 0.012f * sin(t * 0.023f + 0.5f * i) * alive * rise else 0f) + 0.02f * dying
            spine[i].z += shear(SPINE_Y[i], lean) - shear(SPINE_Y[i - 1], lean)
            spine[i].xRot += sx
            spine[i].zRot += sz
        }
        // The mantle heaves: a slow breath that runs up it, deeper at the surface and in a rage, drawn in as it lunges; still as it dies
        val amp = (0.010f + 0.016f * rise + 0.014f * rage) * alive
        for (j in slices.indices) {
            val a = amp * sin(t * 0.085f - 0.13f * j) - 0.05f * lunge + 0.03f * dying
            val s = slices[j]
            s.z += shear(SLICE_Y[j], lean) - shear(SPINE_Y[SLICE_JOINT[j]], lean)
            s.xScale = 1f + a
            s.zScale = 1f + a
        }

        // The eye turns in its socket (rolling up as it dies) with a faint tremor; the pupil's strips reshape from a bar to a round hole
        eye.xRot = -lookPitch - 0.55f * dying + 0.010f * sin(t * 0.37f + 1f) * alive
        eye.yRot = lookYaw + 0.012f * sin(t * 0.29f) * alive
        val p = (state.pupil + 0.03f * sin(t * 0.21f) * alive + 0.6f * dying * (1f - state.pupil)).coerceIn(0f, 1f)
        pupilLayout(p)
        for (k in 0 until PUPIL_STRIPS) {
            pupil[k].y = pupilY[k]
            pupil[k].z = PUPIL_FRONT + 0.5f
            pupil[k].xScale = pupilW[k] / 5f
            pupil[k].yScale = pupilH[k] / 0.5f
        }
        // The milky film clouds over the dying eye: four dithered discs, shown one after the other
        for (k in 0 until FILMS) {
            val shown = dying > 0.06f + 0.11f * k
            films[k].visible = shown
            films[k].z = if (shown) FILM_Z else FILM_HIDDEN_Z
        }
        // The lids: shut when told to, half shut as it dies, narrowed in a rage, following the gaze up and down (built closed, they rest turned open)
        val closed = max(state.lid, 0.55f * dying).coerceIn(0f, 1f)
        val open = 1f - closed
        lidU.xRot += -LID_U_OPEN * closed + 0.20f * rage * open - 0.35f * lookPitch * open
        lidL.xRot += -LID_L_OPEN * closed - 0.12f * rage * open - 0.20f * lookPitch * open
        // The brow lifts a little when the pupil opens, and scowls down in a rage and as the lids shut
        val drop = -0.8f * (state.pupil.coerceIn(0f, 1f) - 0.3f) + 1.8f * rage + 1f * closed
        brow.y += drop
        browL.y += drop
        browL.zRot += 0.35f * rage
        browR.y += drop
        browR.zRot -= 0.35f * rage

        // The beak: the lower one drops, the upper lifts, the throat shows; it chatters faintly at rest and grinds in a rage
        val chat = (0.03f * sin(t * 0.11f) + 0.05f * rage * sin(t * 0.83f)) * alive
        beakUpper.xRot += -0.20f * beak
        jawA.xRot += 0.70f * beak + chat
        jawB.xRot += 0.15f * beak
        throat.y += 3f * beak
        throat.yScale = 1f + 1.2f * beak

        // The arm-roots: each a holder turned to its direction, and five segments that curl toward their suckers and writhe in waves
        for (k in 0 until ROOTS) {
            val th = (ROOT_A0 + k * ROOT_STEP) * RAD
            rootDirection(th, k, rise, lunge, dying, t, rage)
            var ux = -cos(th)
            var uy = -sin(th)
            val dot = ux * dir[0] + uy * dir[1]
            ux -= dot * dir[0]
            uy -= dot * dir[1]
            var uz = -dot * dir[2]
            var ul = sqrt(ux * ux + uy * uy + uz * uz)
            if (ul < 1.0e-4f) {
                ux = 0f
                uy = 0f
                uz = 1f
                ul = 1f
            }
            ux /= ul
            uy /= ul
            uz /= ul
            val ezx = -ux
            val ezy = -uy
            val ezz = -uz
            turn(holders[k], dir[1] * ezz - dir[2] * ezy, dir[2] * ezx - dir[0] * ezz, dir[0] * ezy - dir[1] * ezx, dir[0], dir[1], dir[2], ezx, ezy, ezz)
            for (j in 1..ROOT_SEGS) {
                val wave = sin(t * 0.082f - 0.85f * j + 1.7f * k)
                val wave2 = sin(t * 0.057f - 0.7f * j + 1.1f * k + 2f)
                val ampJ = (0.10f + 0.05f * j) * (1f + 0.9f * rage) * (1f + 0.6f * (1f - rise)) * (1f - 0.75f * dying) * (1f - 0.5f * lunge)
                val curl = (0.16f + 0.05f * j) * (1f - 0.75f * lunge) + 0.30f * dying * (0.6f + 0.1f * j)
                roots[k][j - 1].xRot = -curl + ampJ * wave
                roots[k][j - 1].zRot = 0.8f * ampJ * wave2
            }
        }

        // The siphon breathes out with the mantle, and flares and pulses as it spews ink
        val breath = 0.05f * sin(t * 0.085f - 0.9f) * alive
        val pulse = 0.5f + 0.5f * sin(t * 0.85f)
        val pulse2 = 0.5f + 0.5f * sin(t * 0.85f + 1f)
        val s1 = 1f + breath * 0.5f + 0.15f * ink
        flare[0].xScale = s1
        flare[0].zScale = s1
        val s2 = 1f + breath + 0.30f * ink + 0.10f * ink * pulse
        flare[1].xScale = s2
        flare[1].zScale = s2
        val s3 = 1f + breath + 0.45f * ink + 0.18f * ink * pulse2
        flare[2].xScale = s3
        flare[2].yScale = 1f + 0.40f * ink * pulse2
        flare[2].zScale = s3
        siphon.xRot += -0.20f * ink - 0.06f * ink * pulse

        // The fins ripple in a wave that runs out to the tips; they sweep back as it lunges and droop as it dies
        val fa = (0.5f + 0.5f * rise) * (1f + 0.9f * rage) * alive
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            for (j in 1..FIN_SEGS) {
                val flap = (0.10f + 0.045f * j) * fa * sin(t * 0.11f - 0.8f * j + 1.9f * side)
                val flap2 = 0.04f * j * fa * sin(t * 0.07f - 0.6f * j + 0.7f * side + 1f)
                val f = fins[side][j - 1]
                f.xRot += flap2
                if (j == 1) f.yRot += -sgn * 0.5f * lunge
                f.zRot += -sgn * flap + sgn * 0.25f * dying * (0.5f + 0.2f * j)
            }
        }

        // The weed and the harpoons' ropes drift in the water
        val drive = 1f + 0.6f * lunge + 0.5f * rage
        for (k in 0 until WEEDS) {
            for (j in 1..WEED_PIECES) {
                weeds[k][j - 1].xRot += 0.10f * drive * sin(t * 0.055f + 1.9f * k - 0.9f * j)
                weeds[k][j - 1].zRot += 0.08f * drive * sin(t * 0.043f + 2.3f * k - 0.7f * j)
            }
        }
        for (k in 0 until HARPOONS) {
            for (j in 1..2) {
                ropes[k][j - 1].xRot += 0.14f * drive * sin(t * 0.047f + 2.1f * k - 0.9f * j)
                ropes[k][j - 1].zRot += 0.10f * drive * sin(t * 0.039f + 1.4f * k - 0.6f * j)
            }
        }
    }

    /** The five strips of the pupil for openness [p] (0 a narrow bar, 1 a round hole): half width, half height and y of each, from the top. */
    private fun pupilLayout(p: Float) {
        val a = PUPIL_A0 + (PUPIL_A1 - PUPIL_A0) * p
        val b = PUPIL_B0 + (PUPIL_B1 - PUPIL_B0) * p
        val s = smooth(0.25f, 0.85f, p)
        val hb = 2f * b / PUPIL_STRIPS
        for (k in 0 until PUPIL_STRIPS) {
            val yk = (k - (PUPIL_STRIPS - 1) / 2f) * hb
            val r = abs(yk) / b
            pupilW[k] = a * (1f - s * (1f - sqrt(max(0f, 1f - r * r))))
            pupilH[k] = hb / 2f
            pupilY[k] = yk
        }
    }

    /** Root [k]'s direction now, into [dir] (a unit vector): splayed at the surface, trailing below when low in the water, reaching forward in a lunge, limp as it dies. */
    private fun rootDirection(th: Float, k: Int, rise: Float, lunge: Float, dying: Float, t: Float, rage: Float) {
        val c = cos(th)
        val s = sin(th)
        val wd = dying
        val wl = (1f - dying) * lunge
        val wt = (1f - dying) * (1f - lunge) * (1f - smooth(0.15f, 0.9f, rise))
        val ws = 1f - wd - wl - wt
        val sw = 0.07f * (1f + 0.8f * rage) * (1f - 0.6f * dying)
        var dx = ws * (0.85f * c) + wt * (0.35f * c) + wl * (0.30f * c) + wd * (0.55f * c)
        var dy = ws * (0.85f * s + 0.2f) + wt * 1f + wl * (0.30f * s) + wd * 1f
        var dz = ws * -0.5f + wt * 0.30f + wl * -1f + wd * -0.15f
        dx += sw * sin(t * 0.037f + 1.3f * k)
        dy += 0.6f * sw * sin(t * 0.029f + 0.8f * k + 1f)
        dz += 0.5f * sw * sin(t * 0.043f + 2.1f * k + 2f)
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        dir[0] = dx / n
        dir[1] = dy / n
        dir[2] = dz / n
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

    private fun abs(v: Float): Float = if (v < 0f) -v else v

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How far (px, along z) the mantle at height [y] is shifted by a lean of [lean] px at its very top: nothing at the base, more the higher it is. */
    private fun shear(y: Float, lean: Float): Float = lean * min(1.2f, max(0f, (SPINE_Y[0] - y) / (SPINE_Y[0] - Y_TOP))).pow(1.3f)

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "maelgrasp"), "main")

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
    sl = mantle_slices()
    ym = [(a + b) / 2 for a, b, r, c in sl]
    return '\n'.join([
        '        private const val RAD = 0.017453292f',
        '',
        '        // The spine: the height (y, px) of each joint, and the top of the mantle; each slice\'s middle and the joint it hangs from',
        f'        private val SPINE_Y = floatArrayOf({", ".join(f(v) for v in SPINE)})',
        f'        private const val Y_TOP = {f(Y_TOP)}',
        f'        private val SLICE_Y = floatArrayOf({", ".join(f(v) for v in ym)})',
        f'        private val SLICE_JOINT = intArrayOf({", ".join(str(near_joint(v)) for v in ym)})',
        '',
        '        // The pupil: five strips from a bar (half width A0, half height B0) to a round hole (A1, B1), their fronts at PUPIL_FRONT; the film\'s four discs',
        f'        private const val PUPIL_STRIPS = {PUPIL_STRIPS}',
        f'        private const val PUPIL_A0 = {f(PUPIL_A[0])}',
        f'        private const val PUPIL_A1 = {f(PUPIL_A[1])}',
        f'        private const val PUPIL_B0 = {f(PUPIL_B[0])}',
        f'        private const val PUPIL_B1 = {f(PUPIL_B[1])}',
        f'        private const val PUPIL_FRONT = {f(PUPIL_FRONT)}',
        f'        private const val FILMS = {FILMS}',
        f'        private const val FILM_Z = {f(FILM_Z)}',
        f'        private const val FILM_HIDDEN_Z = {f(FILM_HIDDEN_Z)}',
        '',
        '        // The lids\' resting turn (radians: they are built closed); the arm-roots: how many, their segments, the angle of the first round the beak and the step to the next (degrees)',
        f'        private const val LID_U_OPEN = {f(LID_OPEN_RAD[0])}',
        f'        private const val LID_L_OPEN = {f(LID_OPEN_RAD[1])}',
        f'        private const val ROOTS = {ROOTS}',
        f'        private const val ROOT_SEGS = {ROOT_SEGS}',
        f'        private const val ROOT_A0 = {f(ROOT_A0)}',
        f'        private const val ROOT_STEP = {f(ROOT_STEP)}',
        '',
        '        // The fins\' slats on each side, the weed (strips each of its pieces) and the harpoons whose ropes hang',
        f'        private const val FIN_SEGS = {len(FIN_SEGS)}',
        f'        private const val WEEDS = {len(WEED_AT)}',
        f'        private const val WEED_PIECES = {WEED_PIECES}',
        f'        private const val HARPOONS = {len(HARPOON_AT)}',
        '',
    ])


def kotlin_source(model):
    """MaelgraspModel.kt as a string: the hand-written class (see KT_HEAD) with the constants and the mesh generated from [model]."""
    calls, body, faces = K.kt_mesh(GROUPS, model.tex_w, model.tex_h)
    out = (KT_HEAD.replace('@@CONSTANTS@@', kt_constants()).replace('@@CALLS@@', calls).replace('@@CHUNKS@@', body)
           .replace('@@FACES@@', '\n' + faces + '\n' if faces else '').replace('@@W@@', str(model.tex_w)).replace('@@H@@', str(model.tex_h)))
    names = {p.name for p in model.all_parts()}
    for m_ in re.finditer(r'part\("([a-z0-9_$]+)"\)', out):
        if '$' not in m_.group(1):
            assert m_.group(1) in names, m_.group(1)
    need = ['head', 'eye', 'lid_u', 'lid_l', 'brow', 'brow_l', 'brow_r', 'bu0', 'bl0', 'bl1', 'throat', 'siphon']
    need += [f'sp{i}' for i in range(len(SPINE))] + [f'mt{j}' for j in range(len(mantle_slices()))] + [f'pu{k}' for k in range(PUPIL_STRIPS)] + [f'fm{k}' for k in range(FILMS)]
    need += [f'rh{k}' for k in range(ROOTS)] + [f'rt{k}_{j}' for k in range(ROOTS) for j in range(1, ROOT_SEGS + 1)] + [f'sf{j}' for j in (1, 2, 3)]
    need += [f'fn_{n}{j}' for n in 'lr' for j in range(1, len(FIN_SEGS) + 1)] + [f'wd{k}_{j}' for k in range(len(WEED_AT)) for j in range(1, WEED_PIECES + 1)]
    need += [f'hr{k}_{j}' for k in range(len(HARPOON_AT)) for j in (1, 2)]
    for nm in need:
        assert nm in names, nm
    return out


# ================================================================== checking the Kotlin against pose()

def check_cases(n=60, seed=11):
    """Render states to compare the Kotlin with pose() on: every field over its range at random, and the corners (all zero, all one, one at a time)."""
    rng = np.random.default_rng(seed)
    out = []
    fields = [('rise', 0.0, 1.0), ('lookYaw', -90.0, 90.0), ('lookPitch', -60.0, 60.0), ('pupil', 0.0, 1.0), ('lid', 0.0, 1.0), ('beak', 0.0, 1.0), ('lunge', 0.0, 1.0),
              ('ink', 0.0, 1.0), ('rage', 0.0, 1.0), ('dying', 0.0, 1.0), ('glow', 0.0, 1.0)]
    for i in range(n):
        c = {k: float(rng.uniform(lo, hi)) for k, lo, hi in fields}
        c['time'] = float(rng.uniform(0, 8000))
        if i % 4 == 1:
            c['dying'] = 0.0
        if i % 5 == 2:
            c['lunge'] = 0.0
            c['rise'] = float(rng.choice([0.0, 1.0]))
        out.append(c)
    base = dict(time=0.0, rise=0.0, lookYaw=0.0, lookPitch=0.0, pupil=0.0, lid=0.0, beak=0.0, lunge=0.0, ink=0.0, rage=0.0, dying=0.0, glow=0.0)
    out.append(dict(base))
    out.append(dict(base, rise=1.0, pupil=0.3, time=40.0))
    for k, lo, hi in fields:
        out.append(dict(base, **{k: hi, 'time': 77.0}))
        out.append(dict(base, **{k: lo, 'time': 91.0, 'rise': 1.0}))
    out.append({k: 1.0 for k in base} | {'time': 5.0, 'lookYaw': 120.0, 'lookPitch': -80.0})
    return out


def painted_geometry():
    """The built model and its scene without painting (for the checks that do not need the texture)."""
    model = build()
    K.pack_atlas(model)
    return model, K.SScene(model, DOUBLE), None, None


def check(work, n=60):
    """Compiles MaelgraspModel.kt (what `kotlin_source` makes) with the game's own render state and Minecraft's ModelPart, runs its setupAnim on
    [check_cases] and compares every part with `pose()`. Returns (worst position error px, worst turn error, worst scale error, parts compared, problems)."""
    model, scene, _t, _g = painted_geometry()
    files = K.scratch_tree(work, {'client/sea/MaelgraspModel.kt': kotlin_source(model)}, ['client/sea/MaelgraspRenderState.kt'])
    r = K.verify_pose(files, 'io.github.vaspyyy.guildmark.client.sea', 'MaelgraspModel', 'MaelgraspRenderState', scene, check_cases(n), lambda c: HeadState(**c), pose, work)
    return r[0], r[1], r[2], r[3], list(r[4]) + fixed_point_problems(model, scene)


def fixed_point_problems(model, scene):
    """The two points the game aims at, as built: the eye's centre (0, -32, -37) and the beak's tip (0, -6, -46), must be exact."""
    M, _vis = scene.mats(None)
    problems = []
    e = M[scene.index['eye']] @ np.array([0.0, 0.0, 0.0, 1.0])
    if np.abs(e[:3] - np.array(EYE)).max() > 0.01:
        problems.append(f'the eye is at {e[:3]}, not {EYE}')
    L = TARGETS['beak_chain'][1]
    tip = M[scene.index['bu2']] @ np.array([0.0, L[2], 0.0, 1.0])
    if np.abs(tip[:3] - np.array(BEAK_TIP)).max() > 0.01:
        problems.append(f'the beak tip is at {tip[:3]}, not {BEAK_TIP}')
    return problems


# ================================================================== the report

NOTES = [
    (r'^head$', 'head', 'the whole head: surges forward and ducks with `lunge`, sinks a little as it dies (`dying`)'),
    (r'^sp\d$', 'spine joints', 'sp1..sp5 shift forward in z with `lunge` and back with `dying` (the mantle shears joint by joint); the top two sway'),
    (r'^mt\d+(_c\d+)?$', 'mantle slices (and their turned copies _c1..)', 'heave: a breath running up the mantle (xz scale), deeper with `rise` and `rage`, drawn in by `lunge`, still as it dies; shifted by the lean'),
    (r'^eye$', 'eye', '`lookYaw` / `lookPitch`: turns in its socket about the eye\'s centre (0, -32, -37), rolls up with `dying`, faint tremor'),
    (r'^eb\d+(_c\d+)?$', 'eyeball slices', 'children of `eye` (the iris ring glows)'),
    (r'^pu\d$', 'pupil strips', '`pupil`: five strips reshaped (position and scale) from a narrow bar to a round hole; `dying` blows it wide'),
    (r'^fm\d$', 'film discs', '`dying`: show one after the other (a dithered quarter each), hidden deep inside the head otherwise'),
    (r'^lid_[ul]$', 'lids', '`lid` (blinks, and half shut with `dying`): turn about the eye\'s centre; narrow with `rage`; follow `lookPitch`'),
    (r'^lid_[ul]\d$', 'lid slats', 'children of the lids'),
    (r'^brow(_[lr])?$', 'brow and its wings', 'drop with `rage` and `lid`, lift with `pupil`; the wings scowl down with `rage`'),
    (r'^bu\d$', 'upper beak (hooked chain; its tip is at (0, -6, -46))', '`beak`: bu0 lifts'),
    (r'^bl\d$', 'lower beak', '`beak`: bl0 drops (bl1 follows), chatters at rest, grinds with `rage`'),
    (r'^throat$', 'throat', '`beak`: drops and stretches to show the gape'),
    (r'^rh\d$', 'arm-root holders (eight, a horseshoe round the beak)', 'turned each frame to the direction blended from splayed (`rise`), trailing (low), reaching (`lunge`) and limp (`dying`)'),
    (r'^rt\d_\d(_c\d+)?$', 'arm-root segments (five each)', 'curl toward their suckers and writhe in travelling waves, each root on its own phase; wilder with `rage`'),
    (r'^siphon$', 'siphon', '`ink`: kicks back as it spews'),
    (r'^sf\d(_c\d+)?$', 'siphon funnel slices', '`ink`: flare and pulse; breathe out with the mantle'),
    (r'^fn_[lr]\d$', 'fins (four slats each side)', 'ripple in a wave out to the tips, wider with `rise` and `rage`; sweep back with `lunge`; droop with `dying`'),
    (r'^wd\d+_\d$', 'weed strips', 'drift in the water (more with `lunge` and `rage`)'),
    (r'^hr\d+_\d$', 'harpoon rope strips', 'drift in the water'),
    (r'^hp\d+$', 'broken harpoons', 'static (they ride the mantle)'),
    (r'^bh\d+$', 'barnacles', 'static (they ride the mantle)'),
]


def report_poses():
    """The poses the report's extents are taken over."""
    st = lambda **kw: pose(HeadState(**kw))
    return {
        'idle (surfaced)': st(time=40.0),
        'lunge, beak wide': st(time=40.0, lunge=1.0, beak=1.0, pupil=1.0),
        'rage': st(time=90.0, rage=1.0, beak=0.4, pupil=0.1),
        'ink': st(time=47.0, ink=1.0, lookYaw=25.0),
        'looking hard right/up': st(time=40.0, lookYaw=-70.0, lookPitch=50.0, pupil=0.9),
        'dying': st(time=40.0, dying=1.0, lid=0.5),
        'sunk (rise 0)': st(time=40.0, rise=0.0),
    }


def parts_report(model, scene):
    """The text written beside the preview: the parts, what animates them, the extents, the two fixed points as built."""
    head = ['Origin: the waterline under the middle of the head; px (1/16 block), y down, the creature faces -z, its right is -x.',
            f'Eye centre as built {EYE} (radius {EYE_R:g}); beak tip as built {BEAK_TIP}.',
            f'Mantle: radius up to {MANTLE_R:g} px (about {2 * MANTLE_R / 16:.1f} blocks across), from y = {Y_TOP:g} (top, {-Y_TOP / 16:.1f} blocks above the waterline) to y = {Y_BOT:g} ({Y_BOT / 16:.1f} below).',
            '']
    return K.report_text('The Maelgrasp\'s head (MaelgraspModel)', model, scene, NOTES, report_poses(), head)
