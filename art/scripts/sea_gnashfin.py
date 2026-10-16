"""The Gnashfin: the pack predator that hunts under the ships. About two and a half blocks of armoured pike: a long low skull with an underslung
jaw that juts past the snout and a pair of needle fangs standing up in front of it, a tall ragged dorsal fin that cuts the surface, long pectorals, a
narrow body that tapers to a deeply forked tail, slate-blue with darker bands on the back, mottled flanks, a pale belly and old scars. A row of small
sea-green lights runs along each flank and a lure hangs from the chin on a stalk with a glowing bead (the glow map).

Minecraft model space: pixels, y down, the fish faces -z (its right is -x); the origin is the middle of the body, on the spine. The body is a chain of
segments (`mid` at the origin; `fore` and the neck and `skull` toward the head; `aft`, `stalk` and `tail` toward the tail) so it can bend, ripple in a
travelling wave and arch; the fins are strings of slats cut to shape in the texture's alpha. The texture is HD (declared 128x128, painted at twice
that); every texel is painted from the place it has on the fish. `pose()` is `GnashfinModel.setupAnim` in Python, line for line (sea_monsters.py checks it
against the compiled Kotlin).
"""
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np

import sea_kit as K
from sea_kit import Cu, part, ALL, Out, Model
from ship import ramp, sstep, hash01, fbm, fbm2, mixc, seed_of, stripes
import sea_arm as ARM

TEX_W, TEX_H = 128, 64
NAME = 'gnashfin'

# ================================================================== what the fish is made to

# body tubes: (local z of the tube's start, length, width, height); a tube is two boxes, one wide and low, one narrow and tall
TUBES = {
    'mid': (-4.5, 9.0, 5.5, 7.0),
    'fore': (-9.0, 9.0, 6.0, 7.5),
    'aft': (0.0, 8.0, 4.0, 5.0),
    'stalk': (0.0, 5.5, 2.0, 3.0),
}
JOINTS = {'fore': (0.0, 0.0, -4.5), 'aft': (0.0, 0.0, 4.5), 'stalk': (0.0, 0.0, 8.0), 'tail': (0.0, 0.0, 5.5), 'skull': (0.0, -0.4, -9.0)}   # pivots, relative to the parent
HEAD_TUBES = [('skull', -6.0, 6.0, 6.0, 5.5), ('snoutA', -9.0, 3.0, 4.5, 4.0), ('snoutB', -12.0, 3.0, 3.0, 3.0)]     # the skull and the two steps of the snout: (name, z start, length, width, height)
JAW_AT = (0.0, 2.75, -4.0)                # the jaw's hinge in the skull's frame
JAW_LEN = 11.0
DORSAL_H = [5.0, 9.0, 13.0, 15.0, 14.0, 12.5, 11.0, 9.0, 7.5, 6.0, 4.0]       # the dorsal fin's slats: how tall each stands
DORSAL_Z = [-8.25 + 1.5 * k for k in range(11)]
DORSAL_REST = -0.30                       # rad: raked back
PEC_SEGS = [5.5, 4.5]                     # a pectoral fin: two slats out from the shoulder
PEC_CHORD = [5.0, 3.5]
PEC_REST_SWEEP = 0.5
PEC_REST_DROOP = 0.35
ANAL_H = [4.0, 3.0]
TAIL_L = [3.5, 3.0]                       # a lobe of the tail: two slats
TAIL_H = [3.0, 2.5]
TAIL_FORK = 0.5                           # rad: how far each lobe is turned from the axis
LIGHT_SEG = ['fore', 'mid', 'mid', 'aft', 'aft']          # the flank lights, five a side: the segment each hangs from, where along it
LIGHT_Z = [-4.5, -1.5, 2.5, 1.5, 5.5]
GLOW_LAYERS = {'light': ((92, 255, 190), 1.0), 'lure': ((170, 255, 220), 1.0)}

GROUPS = []


def half_h(z):
    """Half the height of the body at [z] (px)."""
    return np.interp(z, [-13.5, -4.5, 4.5, 12.5, 18.0], [3.75, 3.75, 3.5, 2.5, 1.5])


def r5(v):
    """Rounded to the half pixel (the finest a box can be sized at HD)."""
    return round(v * 2.0) / 2.0


def tube(pname, cname, z0, ln, w, h, mat, faces, **kw):
    """A round-ish body tube of a part: two boxes (wide and low, narrow and tall) whose union is a rounded-rectangle section, from [z0] for [ln]
    (local z), centred on the part's axis; [faces] are drawn on both."""
    a = Cu((-w / 2, -r5(0.82 * h) / 2, z0), (w, r5(0.82 * h), ln), mat, cname + '_a', faces=faces, w=w, h=h, ln=ln, z0=z0, **kw)
    b = Cu((-r5(0.84 * w) / 2, -h / 2, z0), (r5(0.84 * w), h, ln), mat, cname + '_b', faces=faces, w=w, h=h, ln=ln, z0=z0, **kw)
    return [a, b]


def joint_cube(pname, w, h, mat='body'):
    """A little block inside a joint (it turns with the part after the joint): where the neighbouring tubes part as the body bends, flesh shows, not the hollow."""
    return Cu((-(w - 1.5) / 2, -(h - 1.5) / 2, -1.5), (w - 1.5, h - 1.5, 3.0), mat, pname + '_j', faces=ALL)


def slat(name, mat, origin, size, faces, **kw):
    return Cu(origin, size, mat, name, faces=faces, **kw)


def fin_chain(prefix, n, pivots, rots, cubes_fn):
    """A chain of [n] parts `prefix1`..: the first at pivots[0] with rots[0]; each next hangs from the end of the one before at pivots[k], rots[k]."""
    out = None
    for k in range(n - 1, -1, -1):
        out = part(f'{prefix}{k + 1}', pivots[k], rots[k], cubes_fn(k), [out] if out else [])
    return out


def build_head():
    """The skull, the two steps of the snout, eyes, armour plates, gill covers, teeth, the jaw with its fangs and the chin lure; returns the part `skull`."""
    cubes = []
    for nm, z0, ln, w, h in HEAD_TUBES:
        cubes += tube('skull', nm, z0, ln, w, h, 'head', {'top', 'bottom', 'left', 'right', 'front'}, kind=nm)
    cubes.append(joint_cube('skull', HEAD_TUBES[0][3], HEAD_TUBES[0][4]))
    for k, z in enumerate((-1.5, -3.5, -5.5)):
        cubes.append(Cu((-1.5, -2.75 - 0.4, z - 1.0), (3.0, 0.75, 2.0), 'scute', f'sk_plate{k}', faces=ALL, plate='skull', k=k))
    cubes.append(Cu((-1.0, -2.0 - 0.4, -8.5), (2.0, 0.5, 2.5), 'scute', 'sk_plate3', faces=ALL, plate='snout', k=3))
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        # the eye: a ringed disc on the side of the skull; upper teeth hang from the snout's lower edge
        cubes.append(Cu((3.0 - 0.25 if sgn > 0 else -3.0 - 0.25, -1.5, -5.5), (0.5, 1.5, 1.5), 'eye', f'eye_{nm}', faces=ALL, side=nm))
        for k, (z, yb, xw) in enumerate(((-6.5, 2.0, 1.9), (-8.0, 2.0, 1.9), (-9.5, 1.5, 1.2), (-11.0, 1.5, 1.2))):
            cubes.append(Cu((sgn * xw - 0.25, yb - 0.25, z - 0.25), (0.5, 1.5, 0.5), 'tooth', f'ut_{nm}{k}', faces=ALL, kind='upper', k=k))
    # the jaw: underslung, jutting past the snout; needle teeth along it, two fangs standing up at its tip, the chin lure under it
    jaw_cubes = [Cu((-1.5, 0.0, -JAW_LEN), (3.0, 2.0, JAW_LEN), 'jaw', 'jaw_c', faces={'top', 'bottom', 'left', 'right', 'front'})]
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        for k, z in enumerate((-2.0, -3.5, -5.0, -6.5, -8.0)):
            jaw_cubes.append(Cu((sgn * 1.25 - 0.25, -1.5, z - 0.25), (0.5, 1.5, 0.5), 'tooth', f'jt_{nm}{k}', faces=ALL, kind='lower', k=k))
    fangs = []
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        tip = part(f'fang_{nm}2', (0.0, -2.5, 0.0), (-0.40, 0, 0), [Cu((-0.25, -2.0, -0.25), (0.5, 2.0, 0.5), 'tooth', f'fang_{nm}2c', faces=ALL, kind='fang', k=1)])
        fangs.append(part(f'fang_{nm}1', (sgn * 1.0, 0.0, -10.0), (0.15, 0, 0), [Cu((-0.5, -2.5, -0.5), (1.0, 2.5, 1.0), 'tooth', f'fang_{nm}1c', faces=ALL, kind='fang', k=0)], [tip]))
    bead = part('bead', (0.0, 4.0, 0.0), (0, 0, 0), [Cu((-1.0, -1.0, -1.0), (2.0, 2.0, 2.0), 'lure', 'bead_c', faces=ALL, kind='bead')])
    lure = part('lure', (0.0, 2.0, -3.0), (0, 0, 0), [Cu((-0.25, 0.0, -0.25), (0.5, 4.0, 0.5), 'lure', 'lure_c', faces=ALL, kind='stalk')], [bead])
    jaw = part('jaw', JAW_AT, (0, 0, 0), jaw_cubes, fangs + [lure])
    gills = []
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        gills.append(part(f'gill_{nm}', (sgn * 3.0, 0.0, -3.5), (0, 0, 0), [Cu((0.0 if sgn > 0 else -0.5, -2.25, 0.0), (0.5, 4.5, 3.5), 'gill', f'gill_{nm}c', faces=ALL, side=nm)]))
    return part('skull', JOINTS['skull'], (0, 0, 0), cubes, [jaw] + gills)


def build_fins_and_lights(segs):
    """Hangs the fins, the flank lights and the scutes on the segments: [segs] {name: part}."""
    # the dorsal fin: six slats along the back of `mid`, tall and raked back
    for k, (z, H) in enumerate(zip(DORSAL_Z, DORSAL_H)):
        segs['mid'].children.append(part(f'dn{k}', (0.0, -float(half_h(z)) + 0.4, z), (math.degrees(DORSAL_REST), 0, 0),
                                         [Cu((-0.25, -H, -0.75), (0.5, H, 1.5), 'fin', f'dn{k}c', faces={'left', 'right'}, kind='dorsal', k=k, H=H)]))
    # pectorals: two slats each, swept back and drooping
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        def cubes_fn(k, sgn=sgn, nm=nm):
            L, ch = PEC_SEGS[k], PEC_CHORD[k]
            return [Cu((0.0 if sgn > 0 else -L, -0.25, -0.4 * ch), (L, 0.5, ch), 'fin', f'pec_{nm}{k + 1}c', faces={'top', 'bottom'}, kind='pec', k=k, L=L, ch=ch)]
        chain = fin_chain(f'pec_{nm}', 2, [(sgn * 2.8, 1.5, -5.0), (sgn * PEC_SEGS[0], 0.0, 0.0)],
                          [(0.0, -sgn * math.degrees(PEC_REST_SWEEP), sgn * math.degrees(PEC_REST_DROOP)), (0, 0, 0)], cubes_fn)
        segs['fore'].children.append(chain)
    # pelvics: one small slat each under the middle of the body
    for sgn, nm in ((1.0, 'l'), (-1.0, 'r')):
        L, ch = 3.5, 3.0
        segs['mid'].children.append(part(f'pel_{nm}', (sgn * 1.4, 3.3, 1.0), (0.0, -sgn * 23.0, sgn * 52.0),
                                         [Cu((0.0 if sgn > 0 else -L, -0.25, -0.4 * ch), (L, 0.5, ch), 'fin', f'pel_{nm}c', faces={'top', 'bottom'}, kind='pelvic', k=0, L=L, ch=ch)]))
    # the anal fin: two slats under `aft`
    for k, (z, H) in enumerate(zip((1.5, 3.5), ANAL_H)):
        segs['aft'].children.append(part(f'an{k}', (0.0, 2.2, z), (math.degrees(0.3), 0, 0), [Cu((-0.25, 0.0, -0.75), (0.5, H, 1.5), 'fin', f'an{k}c', faces={'left', 'right'}, kind='anal', k=k, H=H)]))
    # the tail: an upper and a lower lobe, each two slats, turned apart into a deep fork
    for sgn, nm in ((1.0, 'u'), (-1.0, 'l')):
        def cubes_fn(k, nm=nm):
            L, H = TAIL_L[k], TAIL_H[k]
            return [Cu((-0.25, -H / 2, 0.0), (0.5, H, L), 'fin', f't{nm}{k + 1}c', faces={'left', 'right'}, kind='tail', k=k, L=L, H=H, lobe=nm)]
        chain = fin_chain(f't{nm}', 2, [(0.0, 0.0, 0.0), (0.0, 0.0, TAIL_L[0])], [(sgn * math.degrees(TAIL_FORK), 0, 0), (0, 0, 0)], cubes_fn)
        segs['tail'].children.append(chain)
    # the flank lights and the scutes
    n = 0
    for side, sgn in ((0, 1.0), (1, -1.0)):
        for k in range(5):
            seg = LIGHT_SEG[k]
            w = TUBES[seg][2]
            segs[seg].children.append(part(f'lt{n}', (sgn * (w / 2 - 0.25), 0.8, LIGHT_Z[k]), (0, 0, 0),
                                           [Cu((0.0 if sgn > 0 else -1.0, -0.75, -0.75), (1.0, 1.5, 1.5), 'light', f'lt{n}c', faces=ALL, side=side, k=k)]))
            n += 1
    for seg, zs in (('fore', (-6.0, -2.5)), ('aft', (2.0, 5.5))):
        z0, ln, w, h = TUBES[seg]
        for k, z in enumerate(zs):
            segs[seg].cubes.append(Cu((-1.25, -h / 2 - 0.4, z - 1.25), (2.5, 0.75, 2.5), 'scute', f'{seg}_plate{k}', faces=ALL, plate=seg, k=k))
    segs['stalk'].cubes.append(Cu((-0.75, -1.5 - 0.4, 1.5), (1.5, 0.5, 2.0), 'scute', 'stalk_plate0', faces=ALL, plate='stalk', k=0))


def build():
    """The model."""
    K.REG.clear()
    GROUPS.clear()
    segs = {}
    for nm in ('mid', 'fore', 'aft', 'stalk'):
        z0, ln, w, h = TUBES[nm]
        faces = {'top', 'bottom', 'left', 'right'}
        if nm == 'fore':
            faces = faces | {'front'}
        if nm in ('aft', 'stalk'):
            faces = faces | {'back'}
        cubes = tube(nm, nm, z0, ln, w, h, 'body', faces, seg=nm)
        if nm != 'mid':
            cubes.append(joint_cube(nm, w, h))
        segs[nm] = part(nm, JOINTS.get(nm, (0.0, 0.0, 0.0)), (0, 0, 0), cubes)
    segs['tail'] = part('tail', JOINTS['tail'], (0, 0, 0), [Cu((-0.5, -0.75, -1.0), (1.0, 1.5, 2.0), 'body', 'tail_j', faces=ALL)])
    build_fins_and_lights(segs)
    segs['fore'].children.append(build_head())
    segs['stalk'].children.append(segs['tail'])
    segs['aft'].children.append(segs['stalk'])
    segs['mid'].children += [segs['fore'], segs['aft']]
    GROUPS.append(('fish', [segs['mid']]))
    return Model(NAME, TEX_W, TEX_H, [segs['mid']])


# ================================================================== painting

FSLATE = np.array([(14, 22, 36), (26, 42, 64), (40, 64, 92), (58, 88, 118), (82, 114, 142), (116, 148, 170)], float)
FFLANK = np.array([(30, 46, 64), (52, 76, 98), (82, 110, 130), (116, 144, 160), (154, 178, 188), (192, 208, 210)], float)
FBELLY = np.array([(96, 104, 110), (144, 152, 154), (184, 190, 188), (214, 218, 212), (236, 236, 226)], float)
FFIN = np.array([(18, 30, 46), (32, 52, 76), (52, 80, 108), (80, 112, 140), (112, 146, 168), (156, 186, 198)], float)
FHEAD = np.array([(10, 16, 26), (20, 30, 46), (32, 46, 66), (48, 66, 88), (68, 90, 112), (98, 122, 140)], float)
FTEETH = np.array([(70, 64, 56), (132, 126, 110), (194, 188, 168), (232, 228, 208), (250, 248, 236)], float)
FGUM = np.array([(20, 4, 8), (48, 10, 16), (86, 20, 28), (122, 34, 42), (160, 58, 62)], float)

# old scars: (side +1 left / -1 right, z, y, angle, length, width) in px on the flank
SCARS = [(1.0, -6.5, 0.8, 0.55, 6.0, 0.55), (1.0, 3.0, -1.2, -0.35, 7.0, 0.5), (-1.0, -2.0, 0.4, -0.6, 8.0, 0.55), (-1.0, 6.5, 0.0, 0.3, 4.5, 0.5),
         (1.0, 9.0, 0.5, 0.5, 3.5, 0.45), (-1.0, -9.0, -1.0, -0.4, 5.0, 0.5), (1.0, -1.0, 2.2, -0.2, 3.5, 0.45)]


def fish_skin(f, head=False):
    """The fish's skin at the place of every texel: slate back with darker chevron bands, mottled steel-blue flanks, a pale belly, plate seams, a pale
    lateral line and old scars. [head] paints the darker, plated skin of the skull."""
    W = f.W
    x, y, z = W[..., 0], W[..., 1], W[..., 2]
    seed = seed_of('gnashfin', 'skin')
    hh = half_h(z)
    v = (y + (0.5 if head else 0.0)) / hh
    n_big = fbm(W, 5.0, seed + 1, 3)
    n_mid = fbm(W, 2.0, seed + 2, 3)
    n_fine = fbm(W, 0.7, seed + 3, 2)
    side = np.where(x >= 0, 1.0, -1.0)
    c1, c2, ident, vx, vy = ARM.worley2(z + 37.0 * (side > 0), y, 1.7 if not head else 2.3, 1.5 if not head else 2.0, seed + 4)
    seam = 1.0 - sstep(0.0, 0.11, c2 - c1)
    bands = stripes(z - 0.75 * np.abs(x) + 2.5 * (n_big - 0.5), 5.2, 0.42, 0.22)
    if head:
        back = ramp(FHEAD, 0.34 + 0.40 * (n_mid - 0.5) + 0.20 * (n_fine - 0.5) + 0.14 * (ident - 0.5))
        flank = ramp(FHEAD, 0.46 + 0.40 * (n_mid - 0.5) + 0.14 * (ident - 0.5))
    else:
        back = ramp(FSLATE, 0.42 + 0.40 * (n_mid - 0.5) + 0.20 * (n_fine - 0.5) - 0.30 * bands + 0.08 * (ident - 0.5))
        flank = ramp(FFLANK, 0.40 + 0.50 * (n_mid - 0.5) + 0.25 * (n_big - 0.5) + 0.07 * (ident - 0.5))
        blot = sstep(0.56, 0.68, fbm(W, 3.0, seed + 5, 3)) * 0.5
        flank = flank * (1.0 - blot)[..., None]
    belly = ramp(FBELLY, 0.62 + 0.30 * (n_mid - 0.5) + 0.06 * (ident - 0.5))
    t_back = sstep(-0.05, -0.55, v)
    t_belly = sstep(0.25, 0.70, v)
    rgb = mixc(flank, back, t_back)
    rgb = mixc(rgb, belly, t_belly * (0.55 if head else 1.0))
    rgb = rgb * (1.0 - 0.26 * seam * (0.6 if head else 1.0))[..., None]
    if not head:
        ll = np.exp(-((y - 0.2) / 0.28) ** 2) * (1.0 - t_belly)
        rgb = mixc(rgb, np.array([150.0, 176.0, 190.0]), 0.45 * ll)
        for (sg, zc, yc, ang, ln, wd) in SCARS:
            dx, dy = math.cos(ang) * ln / 2, math.sin(ang) * ln / 2
            m, tt = K.line_mask(z, y, (zc - dx, yc - dy), (zc + dx, yc + dy), wd * 1.1)
            m = m * (side == sg)
            rgb = mixc(rgb, np.array([196.0, 186.0, 184.0]), 0.55 * m)
    if f.face in ('top', 'bottom') and f.cube.kw.get('seg') is not None:
        rgb = rgb * 0.92
    return rgb, t_back


def paint_body(f):
    rgb, _tb = fish_skin(f)
    return Out(rgb)


def paint_head(f):
    rgb, tb = fish_skin(f, head=True)
    W = f.W
    x, y, z = W[..., 0], W[..., 1], W[..., 2]
    # a bone crest along the top of the skull and snout, the eye sockets darkened, the mouth line along the side
    crest = np.exp(-(x / 0.55) ** 2) * sstep(-1.5, -2.6, y)
    rgb = mixc(rgb, np.array([138.0, 150.0, 150.0]), 0.5 * crest)
    for sg in (1.0, -1.0):
        d = np.hypot((z + 18.25) / 1.6, (y + 1.15) / 1.4)
        rgb = rgb * (1.0 - 0.45 * (1.0 - sstep(0.8, 1.6, d)) * (x * sg > 0))[..., None]
    mouth = np.exp(-((y - 2.35) / 0.26) ** 2) * (z < -19.0)
    rgb = mixc(rgb, np.array([14.0, 8.0, 12.0]), 0.7 * mouth)
    return Out(rgb)


def paint_jaw(f):
    W = f.W
    seed = seed_of('gnashfin', 'jaw')
    n = fbm(W, 1.6, seed, 3)
    rgb = mixc(ramp(FBELLY, 0.50 + 0.3 * (n - 0.5)), ramp(FHEAD, 0.45 + 0.4 * (n - 0.5)), 0.55 * sstep(0.2, 0.7, fbm(W, 3.0, seed + 1, 2)))
    if f.face == 'top':
        rgb = ramp(FGUM, 0.25 + 0.4 * n)
    return Out(rgb)


def paint_tooth(f):
    kw = f.kw
    y = f.P[..., 1]
    o, sz = f.o[1], max(f.sz[1], 0.5)
    u = np.clip((y - o) / sz, 0.0, 1.0)
    tip = u if kw['kind'] == 'upper' else 1.0 - u
    rgb = ramp(FTEETH, 0.15 + 0.80 * tip)
    if kw['kind'] == 'fang':
        rgb = ramp(FTEETH, 0.30 + 0.70 * tip)
    return Out(rgb)


def paint_eye(f):
    kw = f.kw
    outward = 'left' if kw['side'] == 'l' else 'right'
    y, z = f.P[..., 1], f.P[..., 2]
    if f.face != outward:
        return Out(np.ones(y.shape + (3,)) * np.array([14.0, 20.0, 28.0]))
    d = np.hypot(y - np.mean(y), z - np.mean(z)) / 0.75
    ring = ramp(np.array([(120, 100, 30), (206, 188, 80), (240, 226, 130)], float), 0.8 - 0.5 * d)
    rgb = np.where((d < 0.45)[..., None], np.array([4.0, 6.0, 8.0]), ring)
    rgb = np.where((d > 0.95)[..., None], np.array([14.0, 20.0, 28.0]), rgb)
    return Out(rgb)


def paint_gill(f):
    z = f.P[..., 2]
    t = np.clip(z / 3.5, 0.0, 1.0)
    n = fbm(f.W, 1.2, f.seed(), 2)
    rgb = ramp(FHEAD, 0.55 + 0.25 * (n - 0.5) - 0.1 * t)
    fil = stripes(f.P[..., 1] * 2.0, 1.0, 0.5, 0.2) * sstep(0.62, 0.95, t)
    rgb = mixc(rgb, np.array([170.0, 50.0, 56.0]), 0.75 * sstep(0.78, 1.0, t))
    rgb = mixc(rgb, np.array([210.0, 90.0, 84.0]), 0.5 * fil)
    return Out(rgb)


def paint_scute(f):
    ed = K.edge_dist(f)
    seed = f.seed()
    n = fbm(f.W, 1.0, seed, 2)
    rgb = ramp(FSLATE, 0.50 + 0.3 * (n - 0.5) + 0.25 * (1.0 - sstep(0.0, 0.7, ed)))
    rgb = mixc(rgb, np.array([170.0, 188.0, 190.0]), 0.5 * (1.0 - sstep(0.0, 0.35, ed)))
    return Out(rgb)


def paint_light(f):
    kw = f.kw
    outward = 'left' if kw['side'] == 0 else 'right'
    y, z = f.P[..., 1], f.P[..., 2]
    if f.face != outward:
        return Out(np.ones(y.shape + (3,)) * np.array([22.0, 70.0, 56.0]), None, {'light': np.full(y.shape, 0.25)})
    d = np.hypot(y - np.mean(y), z - np.mean(z)) / 0.8
    core = np.clip(1.0 - d, 0.0, 1.0)
    rgb = mixc(np.array([60.0, 190.0, 150.0]), np.array([225.0, 255.0, 240.0]), core ** 1.5)
    rgb = np.where((d > 1.05)[..., None], np.array([20.0, 56.0, 50.0]), rgb)
    return Out(rgb, None, {'light': np.clip(0.55 + 0.45 * core, 0.0, 1.0) * (d < 1.05)})


def paint_lure(f):
    kw = f.kw
    if kw['kind'] == 'stalk':
        return Out(np.ones(f.P.shape[:2] + (3,)) * np.array([58.0, 72.0, 82.0]))
    d = np.linalg.norm(f.P, axis=-1) / 1.6
    core = np.clip(1.0 - d * 0.7, 0.0, 1.0)
    rgb = mixc(np.array([90.0, 220.0, 190.0]), np.array([240.0, 255.0, 246.0]), core ** 1.2)
    return Out(rgb, None, {'lure': np.clip(0.7 + 0.3 * core, 0, 1) * np.ones(d.shape)})


def paint_fin(f):
    """A fin slat: its shape cut out in the alpha (the membrane narrows to ragged points, torn and nicked), rays along it, slate going pale at the edges."""
    kw = f.kw
    kind = kw['kind']
    P = f.P
    seed = seed_of('gnashfin', 'fin', kind, kw['k'])
    if kind == 'dorsal':
        H = kw['H']
        s, u = P[..., 2], -P[..., 1] / H                      # across (z) and up the slat (0 base .. 1 tip)
        k = kw['k']
        tipw = 0.75 * (1.0 - sstep(0.42, 1.0, u)) ** 0.9
        rag = 0.70 + 0.55 * fbm2(u * 9.0, s * 0.0 + k * 3.0, 1.0, 1.0, seed, 2)
        solid = (np.abs(s) < tipw * rag + 0.03) & (u < 0.995 - 0.1 * (hash01(k, 7, 0, seed) - 0.5))
        bite = (fbm2(u * 7.0, s * 3.0 + k, 1.0, 1.0, seed + 1, 2) > 0.80) & (u > 0.3)
        solid &= ~bite
        ray = np.exp(-(s / 0.20) ** 2)
        mem = ramp(FFIN, 0.30 + 0.30 * (1.0 - u) + 0.20 * (fbm2(u * 6.0, s * 4.0, 1.0, 1.0, seed + 2, 2) - 0.5))
        rgb = mixc(mem, ramp(FSLATE, 0.62 - 0.25 * u), 0.55 * ray)
        rgb = mixc(rgb, np.array([160.0, 180.0, 186.0]), 0.35 * sstep(0.7, 1.0, u))
        return Out(rgb, solid.astype(float))
    if kind == 'anal':
        H = kw['H']
        s, u = P[..., 2], P[..., 1] / H
        tipw = 0.75 * (1.0 - sstep(0.45, 1.0, u)) ** 0.9
        rag = 0.75 + 0.5 * fbm2(u * 9.0, s * 0.0 + kw['k'] * 3.0, 1.0, 1.0, seed, 2)
        solid = (np.abs(s) < tipw * rag + 0.03) & (u < 0.995)
        ray = np.exp(-(s / 0.2) ** 2)
        rgb = mixc(ramp(FFIN, 0.38 + 0.3 * (1.0 - u)), ramp(FSLATE, 0.55), 0.5 * ray)
        return Out(rgb, solid.astype(float))
    if kind in ('pec', 'pelvic'):
        L, ch = kw['L'], kw['ch']
        D = (sum(PEC_SEGS[:kw['k']]) if kind == 'pec' else 0.0) + np.abs(P[..., 0])
        tot = sum(PEC_SEGS) if kind == 'pec' else L
        z = P[..., 2]
        t = np.clip(D / tot, 0.0, 1.0)
        chord = (sum(PEC_CHORD) / 2 + 1.2 if kind == 'pec' else ch) * (1.0 - t ** 1.5 * 0.85)
        lead = -0.28 * chord * (1.0 + 0.1 * np.sin(t * 6.0))
        trail = 0.72 * chord * (0.82 + 0.4 * fbm2(D * 1.5, z * 0.0 + kw['k'], 1.0, 1.0, seed, 2))
        solid = (z > lead) & (z < trail) & (t < 0.985)
        bite = (fbm2(D * 1.4, z * 1.4, 1.0, 1.0, seed + 3, 2) > 0.80) & (t > 0.25)
        solid &= ~bite
        ray = stripes(np.arctan2(z, np.maximum(D, 0.6)) * 14.0 / math.pi, 1.0, 0.3, 0.1)
        rgb = mixc(ramp(FFIN, 0.34 + 0.34 * (1.0 - t)), ramp(FSLATE, 0.56 - 0.2 * t), 0.5 * ray)
        rgb = mixc(rgb, np.array([170.0, 190.0, 196.0]), 0.4 * sstep(0.1, 0.0, z - lead))
        return Out(rgb, solid.astype(float))
    # the tail lobes
    L, H = kw['L'], kw['H']
    D = sum(TAIL_L[:kw['k']]) + P[..., 2]
    tot = sum(TAIL_L)
    y = P[..., 1]
    t = np.clip(D / tot, 0.0, 1.0)
    hy = 1.5 * (1.0 - 0.80 * t ** 1.3) * (0.80 + 0.45 * fbm2(D * 1.6, y * 0.0 + (1.0 if kw['lobe'] == 'u' else 2.0), 1.0, 1.0, seed, 2))
    solid = (np.abs(y) < hy) & (t < 0.985)
    bite = (fbm2(D * 1.5, y * 1.5, 1.0, 1.0, seed + 2, 2) > 0.80) & (t > 0.3)
    solid &= ~bite
    ray = np.exp(-(y / 0.22) ** 2)
    rgb = mixc(ramp(FFIN, 0.36 + 0.34 * (1.0 - t)), ramp(FSLATE, 0.56 - 0.2 * t), 0.5 * ray)
    return Out(rgb, solid.astype(float))


MATS = {'body': paint_body, 'head': paint_head, 'jaw': paint_jaw, 'tooth': paint_tooth, 'eye': paint_eye, 'gill': paint_gill, 'scute': paint_scute,
        'light': paint_light, 'lure': paint_lure, 'fin': paint_fin}
DOUBLE = {'fin'}


def painted():
    """Builds the model, packs its atlas and paints it: (model, scene, texture (256x128 RGBA), glow map (the same))."""
    model = build()
    K.pack_atlas(model)
    scene = K.SScene(model, DOUBLE)
    tex, layers = K.paint_model(scene, MATS, GLOW_LAYERS, TEX_W, TEX_H)
    return model, scene, tex, K.glow_map(layers, GLOW_LAYERS)


# ================================================================== the pose: what GnashfinModel.setupAnim does

class GnashState:
    """What the renderer gives the model (see GnashfinRenderState.kt), with the values of a fish idling in the water."""

    def __init__(self, **kw):
        self.time = 0.0
        self.swim = 0.0
        self.bend = 0.0
        self.jaw = 0.0
        self.leap = -1.0
        self.stranded = 0.0
        self.gnaw = 0.0
        self.glow = 0.0
        self.pitch = 0.0
        self.roll = 0.0
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


def smooth(a, b, x):
    t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3.0 - 2.0 * t)


FREQ = (0.17, 0.31, 0.52)                  # rad per tick of the swimming wave at a idle, a cruise and a sprint (crossfaded, so the beat quickens without its phase ever jumping)
# per segment of the chain: the phase lag of the wave behind the head's, the wave's amplitude (rad), the yaw a full bend gives it, the pitch a full arch gives it, the pitch its buck gives it
CHAIN = [('skull', -0.9, 0.045, 0.30, -0.50, 0.20), ('fore', -0.45, 0.05, 0.16, -0.20, 0.30), ('mid', 0.0, 0.03, 0.0, 0.0, 0.0),
         ('aft', 0.55, 0.11, -0.16, 0.14, -0.40), ('stalk', 1.15, 0.20, -0.20, 0.22, -0.35), ('tail', 1.8, 0.28, -0.16, 0.24, -0.25)]


def swim_wave(t, s, ph):
    """The travelling wave at time [t] for swimming effort [s], with phase lag [ph]: three fixed beats (idle, cruise, sprint) crossfaded by [s]."""
    w0 = 1.0 - smooth(0.0, 0.5, s)
    w2 = smooth(0.5, 1.0, s)
    w1 = 1.0 - w0 - w2
    return w0 * math.sin(FREQ[0] * t - ph) + w1 * math.sin(FREQ[1] * t - ph) + w2 * math.sin(FREQ[2] * t - ph)


def pose(st):
    """{part: {pos (px), rot (radians), scale, visible}} for state [st] (see the Kotlin setupAnim, which this follows line by line); parts it does not
    mention stay as they were built."""
    P = {}

    def put(n, **kw):
        P.setdefault(n, {}).update(kw)

    def rrot(n, dx=0.0, dy=0.0, dz=0.0):
        r = rest_of(n)[1]
        return (r[0] + dx, r[1] + dy, r[2] + dz)

    t = st.time
    swim = clampf(st.swim, 0.0, 1.0)
    bend = clampf(st.bend, -1.0, 1.0)
    jaw = clampf(st.jaw, 0.0, 1.0)
    stranded = clampf(st.stranded, 0.0, 1.0)
    gnaw = clampf(st.gnaw, 0.0, 1.0)
    glow = clampf(st.glow, 0.0, 1.0)
    leaping = st.leap >= 0.0
    u = clampf(st.leap, 0.0, 1.0) if leaping else 0.0
    arch = math.cos(math.pi * u) if leaping else 0.0
    spread = (math.sin(math.pi * u) ** 0.7) if leaping else 0.0
    flick = math.exp(-(((u - 0.5) / 0.10) ** 2)) if leaping else 0.0
    tuck = smooth(0.35, 0.95, swim)
    effort = (0.30 + 0.70 * swim) * (1.0 - stranded)
    buck = math.sin(0.83 * t) * (0.6 + 0.4 * math.sin(0.31 * t))
    shake = math.sin(0.62 * t)
    slap = math.sin(1.05 * t)
    # ---- the body: every segment yaws in the travelling wave (growing toward the tail), the bend curves it into turns, the arch pitches it in a leap,
    # a stranded fish bucks and slaps its tail, a gnawing one shakes its head
    for name, ph, amp, byaw, apitch, bpitch in CHAIN:
        yaw = amp * effort * swim_wave(t, swim, ph) + byaw * bend
        pitch = apitch * arch + bpitch * stranded * buck
        roll = 0.0
        if name == 'skull':
            yaw += 0.34 * gnaw * shake
            roll = 0.14 * gnaw * math.sin(0.62 * t + 1.0)
        elif name == 'fore':
            yaw += 0.12 * gnaw * math.sin(0.62 * t - 0.6)
        elif name == 'mid':
            yaw += 0.04 * gnaw * math.sin(0.62 * t - 1.2)
        elif name == 'stalk':
            yaw += 0.55 * stranded * slap + 0.45 * flick * math.sin(0.8 * t)
        elif name == 'tail':
            yaw += 0.80 * stranded * math.sin(1.05 * t + 0.5) + 0.60 * flick * math.sin(0.8 * t + 0.6)
        put(name, rot=rrot(name, pitch, yaw, roll))
    # ---- the jaw: gapes with `jaw` (less as it clamps down gnawing), trembles gnawing, snaps when stranded
    snap = stranded * (0.15 + 0.80 * (0.5 + 0.5 * math.sin(0.75 * t + 0.5)) ** 2)
    jaw_a = 0.95 * jaw * (1.0 - 0.85 * gnaw) + 0.05 * gnaw * math.sin(0.9 * t) + snap
    put('jaw', rot=rrot('jaw', jaw_a))
    for nm in 'lr':
        put(f'fang_{nm}2', rot=rrot(f'fang_{nm}2', 0.10 * math.sin(0.3 * t + (0.0 if nm == 'l' else 1.0))))
    # ---- the gill covers pulse, flaring wide as it gasps stranded and as the jaw opens
    gill = 0.26 * (0.5 + 0.5 * math.sin(0.21 * t)) + 0.35 * stranded * (0.5 + 0.5 * math.sin(0.8 * t)) + 0.15 * jaw
    put('gill_l', rot=rrot('gill_l', 0.0, gill, 0.0))
    put('gill_r', rot=rrot('gill_r', 0.0, -gill, 0.0))
    # ---- the chin lure sways, streams back when it swims hard, and its bead pulses
    put('lure', rot=rrot('lure', 0.35 * math.sin(0.14 * t) + 0.5 * swim, 0.0, 0.25 * math.sin(0.11 * t + 1.0)))
    bp = 1.0 + (0.22 * math.sin(0.31 * t)) * (0.3 + 0.7 * glow)
    put('bead', rot=rrot('bead', 0.4 * math.sin(0.2 * t + 1.0)), scale=(bp, bp, bp))
    # ---- the flank lights pulse in a wave along each side
    for n in range(10):
        side, k = n // 5, n % 5
        pl = 0.5 + 0.5 * math.sin(0.19 * t - 0.9 * k - 2.5 * side)
        sc = 1.0 + 0.28 * pl * (0.3 + 0.7 * glow)
        put(f'lt{n}', scale=(sc, sc, sc))
    # ---- the dorsal fin: slat by slat it sways in a wave, folds back as it swims hard, stands as it leaps, thrashes when stranded
    for k in range(len(DORSAL_H)):
        sway = 0.14 * math.sin(0.27 * t - 0.7 * k + 0.4) * (1.0 + 0.5 * swim) + 0.40 * stranded * math.sin(0.9 * t + 0.8 * k)
        rake = -0.25 * swim + 0.10 * spread + 0.07 * math.sin(0.31 * t - 0.5 * k)
        put(f'dn{k}', rot=rrot(f'dn{k}', rake, 0.0, sway))
    # ---- the pectorals: sculling at rest, swept back flat along the flank when it swims hard, thrown wide in a leap, flailing when stranded
    for side, sgn, nm in ((0, 1.0, 'l'), (1, -1.0, 'r')):
        flutter = 0.18 * (1.0 - tuck) * math.sin(0.23 * t + 0.5 * side) + 0.5 * stranded * math.sin(0.95 * t + 1.3 * side)
        sweep = 0.75 * tuck - 0.40 * spread
        put(f'pec_{nm}1', rot=rrot(f'pec_{nm}1', 0.0, -sgn * sweep, sgn * flutter - sgn * 0.5 * spread))
        put(f'pec_{nm}2', rot=rrot(f'pec_{nm}2', 0.0, 0.0, sgn * 0.3 * (1.0 - tuck) * math.sin(0.23 * t - 0.9 + side) + sgn * 0.3 * stranded * math.sin(0.95 * t - 1.0 + side)))
        put(f'pel_{nm}', rot=rrot(f'pel_{nm}', 0.0, 0.0, sgn * 0.15 * math.sin(0.29 * t + side)))
    for k in range(len(ANAL_H)):
        put(f'an{k}', rot=rrot(f'an{k}', 0.08 * math.sin(0.25 * t - 0.7 * k + 2.0), 0.0, 0.12 * math.sin(0.25 * t - 0.7 * k + 1.0)))
    # ---- the tail's lobes ripple, spread with the flick at the top of a leap and thrash when stranded
    for nm, sgn in (('u', 1.0), ('l', -1.0)):
        ph = 0.0 if nm == 'u' else 0.8
        put(f't{nm}1', rot=rrot(f't{nm}1', sgn * (0.10 * math.sin(0.37 * t - 1.0 + ph) + 0.35 * flick + 0.12 * stranded * slap)))
        put(f't{nm}2', rot=rrot(f't{nm}2', sgn * 0.18 * math.sin(0.37 * t - 1.9 + ph)))
    return P


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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * A Gnashfin: the pack predator that hunts under the ships, two and a half blocks of armoured pike. Models are in pixels with y down and the fish
 * facing -z (its right is -x); the origin is the middle of its body, on the spine. It has a long low skull, an underslung jaw that juts past the
 * snout with a pair of needle fangs standing up at its tip, a tall ragged dorsal fin (eleven slats) that cuts the surface, long pectorals, a
 * narrow body that tapers to a deeply forked tail, and a row of small sea-green lights along each flank and a lure on a stalk under its chin that
 * glow (the glow map).
 *
 * The body is a chain of segments (`mid` at the origin; `fore` and `skull` toward the head; `aft`, `stalk` and `tail` toward the tail). Swimming
 * yaws every segment in a wave that runs back along it, growing toward the tail; [GnashfinRenderState.swim] makes the wave wider and quicker (three
 * fixed beats, idle, cruise and sprint, crossfaded by `swim`: a beat that changed with time would jump its phase) and tucks the pectorals back
 * along the flank; [GnashfinRenderState.bend] curves the body into a turn (its nose swings to its own right above 0); [GnashfinRenderState.jaw]
 * gapes the jaw; [GnashfinRenderState.leap] (-1 when it is not leaping, otherwise 0 to 1) arches it nose up while rising and nose down while
 * falling, throws the pectorals wide and flicks the tail at the top; [GnashfinRenderState.stranded] makes it flounder (the body bucks, the tail
 * slaps, the fins flail, the gills gasp, the jaw gapes and snaps); [GnashfinRenderState.gnaw] shakes its head side to side with the jaw clamped;
 * [GnashfinRenderState.time] flutters the fins, pulses the gills, the lights and the lure. The renderer does the pitch and the roll of the whole
 * fish and tints the glow layer by [GnashfinRenderState.glow].
 *
 * The geometry comes from art/scripts/sea_gnashfin.py (edit it there: it writes this file's body and the textures, and its `pose()` is the same
 * animation in Python, run for the previews and checked against this class by sea_monsters.py). The texture is HD: the model declares
 * @@W@@x@@H@@ and the PNGs are painted at twice that. It is drawn with [RenderTypes.entityCutout] (the fins are cut to shape in the texture's
 * alpha, and that type does not cull back faces); the glow layer is the same model drawn again with [RenderTypes.eyes] over gnashfin_glow.png.
 */
class GnashfinModel(root: ModelPart) : EntityModel<GnashfinRenderState>(root, RenderTypes::entityCutout) {
    private val find = root.createPartLookup()

    private fun part(name: String): ModelPart = find.apply(name)!!

    // The chain of body segments, in the order of CHAIN_PHASE
    private val chain = Array(6) { part(CHAIN_NAMES[it]) }
    private val jaw = part("jaw")
    private val fangTips = arrayOf(part("fang_l2"), part("fang_r2"))
    private val gills = arrayOf(part("gill_l"), part("gill_r"))
    private val lure = part("lure")
    private val bead = part("bead")
    private val lights = Array(10) { part("lt$it") }
    private val dorsal = Array(DORSAL) { part("dn$it") }
    private val pec1 = arrayOf(part("pec_l1"), part("pec_r1"))
    private val pec2 = arrayOf(part("pec_l2"), part("pec_r2"))
    private val pelvic = arrayOf(part("pel_l"), part("pel_r"))
    private val anal = Array(2) { part("an$it") }
    private val lobe1 = arrayOf(part("tu1"), part("tl1"))
    private val lobe2 = arrayOf(part("tu2"), part("tl2"))

    override fun setupAnim(state: GnashfinRenderState) {
        super.setupAnim(state)
        val t = state.time
        val swim = state.swim.coerceIn(0f, 1f)
        val bend = state.bend.coerceIn(-1f, 1f)
        val jawOpen = state.jaw.coerceIn(0f, 1f)
        val stranded = state.stranded.coerceIn(0f, 1f)
        val gnaw = state.gnaw.coerceIn(0f, 1f)
        val glow = state.glow.coerceIn(0f, 1f)
        val leaping = state.leap >= 0f
        val u = if (leaping) state.leap.coerceIn(0f, 1f) else 0f
        val arch = if (leaping) cos(PI.toFloat() * u) else 0f
        val spread = if (leaping) sin(PI.toFloat() * u).pow(0.7f) else 0f
        val flick = if (leaping) exp(-(((u - 0.5f) / 0.10f) * ((u - 0.5f) / 0.10f))) else 0f
        val tuck = smooth(0.35f, 0.95f, swim)
        val effort = (0.30f + 0.70f * swim) * (1f - stranded)
        val buck = sin(0.83f * t) * (0.6f + 0.4f * sin(0.31f * t))
        val shake = sin(0.62f * t)
        val slap = sin(1.05f * t)

        // The body: every segment yaws in the travelling wave (growing toward the tail), the bend curves it into turns, the arch pitches it in a leap,
        // a stranded fish bucks and slaps its tail, a gnawing one shakes its head
        for (i in 0 until 6) {
            var yaw = CHAIN_AMP[i] * effort * swimWave(t, swim, CHAIN_PHASE[i]) + CHAIN_BEND[i] * bend
            val pitch = CHAIN_ARCH[i] * arch + CHAIN_BUCK[i] * stranded * buck
            var roll = 0f
            when (i) {
                0 -> {
                    yaw += 0.34f * gnaw * shake
                    roll = 0.14f * gnaw * sin(0.62f * t + 1f)
                }
                1 -> yaw += 0.12f * gnaw * sin(0.62f * t - 0.6f)
                2 -> yaw += 0.04f * gnaw * sin(0.62f * t - 1.2f)
                4 -> yaw += 0.55f * stranded * slap + 0.45f * flick * sin(0.8f * t)
                5 -> yaw += 0.80f * stranded * sin(1.05f * t + 0.5f) + 0.60f * flick * sin(0.8f * t + 0.6f)
            }
            chain[i].xRot += pitch
            chain[i].yRot += yaw
            chain[i].zRot += roll
        }

        // The jaw gapes with `jaw` (less as it clamps down gnawing), trembles gnawing, snaps when stranded
        val snap = stranded * (0.15f + 0.80f * (0.5f + 0.5f * sin(0.75f * t + 0.5f)).pow(2))
        jaw.xRot += 0.95f * jawOpen * (1f - 0.85f * gnaw) + 0.05f * gnaw * sin(0.9f * t) + snap
        fangTips[0].xRot += 0.10f * sin(0.3f * t)
        fangTips[1].xRot += 0.10f * sin(0.3f * t + 1f)

        // The gill covers pulse, flaring wide as it gasps stranded and as the jaw opens
        val gill = 0.26f * (0.5f + 0.5f * sin(0.21f * t)) + 0.35f * stranded * (0.5f + 0.5f * sin(0.8f * t)) + 0.15f * jawOpen
        gills[0].yRot += gill
        gills[1].yRot -= gill

        // The chin lure sways, streams back when it swims hard, and its bead pulses
        lure.xRot += 0.35f * sin(0.14f * t) + 0.5f * swim
        lure.zRot += 0.25f * sin(0.11f * t + 1f)
        val bp = 1f + 0.22f * sin(0.31f * t) * (0.3f + 0.7f * glow)
        bead.xRot += 0.4f * sin(0.2f * t + 1f)
        bead.xScale = bp
        bead.yScale = bp
        bead.zScale = bp

        // The flank lights pulse in a wave along each side
        for (n in 0 until 10) {
            val side = n / 5
            val k = n % 5
            val pl = 0.5f + 0.5f * sin(0.19f * t - 0.9f * k - 2.5f * side)
            val sc = 1f + 0.28f * pl * (0.3f + 0.7f * glow)
            lights[n].xScale = sc
            lights[n].yScale = sc
            lights[n].zScale = sc
        }

        // The dorsal fin: slat by slat it sways in a wave, folds back as it swims hard, stands as it leaps, thrashes when stranded
        for (k in 0 until DORSAL) {
            dorsal[k].zRot += 0.14f * sin(0.27f * t - 0.7f * k + 0.4f) * (1f + 0.5f * swim) + 0.40f * stranded * sin(0.9f * t + 0.8f * k)
            dorsal[k].xRot += -0.25f * swim + 0.10f * spread + 0.07f * sin(0.31f * t - 0.5f * k)
        }

        // The pectorals: sculling at rest, swept back flat along the flank when it swims hard, thrown wide in a leap, flailing when stranded
        for (side in 0..1) {
            val sgn = if (side == 0) 1f else -1f
            val flutter = 0.18f * (1f - tuck) * sin(0.23f * t + 0.5f * side) + 0.5f * stranded * sin(0.95f * t + 1.3f * side)
            val sweep = 0.75f * tuck - 0.40f * spread
            pec1[side].yRot += -sgn * sweep
            pec1[side].zRot += sgn * flutter - sgn * 0.5f * spread
            pec2[side].zRot += sgn * 0.3f * (1f - tuck) * sin(0.23f * t - 0.9f + side) + sgn * 0.3f * stranded * sin(0.95f * t - 1f + side)
            pelvic[side].zRot += sgn * 0.15f * sin(0.29f * t + side)
        }
        for (k in 0..1) {
            anal[k].xRot += 0.08f * sin(0.25f * t - 0.7f * k + 2f)
            anal[k].zRot += 0.12f * sin(0.25f * t - 0.7f * k + 1f)
        }

        // The tail's lobes ripple, spread with the flick at the top of a leap and thrash when stranded
        for (n in 0..1) {
            val sgn = if (n == 0) 1f else -1f
            val ph = if (n == 0) 0f else 0.8f
            lobe1[n].xRot += sgn * (0.10f * sin(0.37f * t - 1f + ph) + 0.35f * flick + 0.12f * stranded * slap)
            lobe2[n].xRot += sgn * 0.18f * sin(0.37f * t - 1.9f + ph)
        }
    }

    /** The travelling wave at time [t] for swimming effort [s], with phase lag [ph]: three fixed beats (idle, cruise, sprint) crossfaded by [s]. */
    private fun swimWave(t: Float, s: Float, ph: Float): Float {
        val w0 = 1f - smooth(0f, 0.5f, s)
        val w2 = smooth(0.5f, 1f, s)
        val w1 = 1f - w0 - w2
        return w0 * sin(FREQ0 * t - ph) + w1 * sin(FREQ1 * t - ph) + w2 * sin(FREQ2 * t - ph)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    companion object {
        val LAYER = ModelLayerLocation(Identifier.fromNamespaceAndPath(Guildmark.MOD_ID, "gnashfin"), "main")

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
    names = [c[0] for c in CHAIN]
    return '\n'.join([
        '        // The beats of the swimming wave (rad per tick): idle, cruise, sprint',
        f'        private const val FREQ0 = {f(FREQ[0])}',
        f'        private const val FREQ1 = {f(FREQ[1])}',
        f'        private const val FREQ2 = {f(FREQ[2])}',
        '',
        '        // The body chain from the head to the tail: each segment\'s part, the phase its wave lags by, the wave\'s amplitude (rad), the yaw a full bend and the pitch a full arch and a full buck give it',
        f'        private val CHAIN_NAMES = arrayOf({", ".join(chr(34) + n + chr(34) for n in names)})',
        f'        private val CHAIN_PHASE = floatArrayOf({", ".join(f(c[1]) for c in CHAIN)})',
        f'        private val CHAIN_AMP = floatArrayOf({", ".join(f(c[2]) for c in CHAIN)})',
        f'        private val CHAIN_BEND = floatArrayOf({", ".join(f(c[3]) for c in CHAIN)})',
        f'        private val CHAIN_ARCH = floatArrayOf({", ".join(f(c[4]) for c in CHAIN)})',
        f'        private val CHAIN_BUCK = floatArrayOf({", ".join(f(c[5]) for c in CHAIN)})',
        '',
        '        // The slats of the dorsal fin',
        f'        private const val DORSAL = {len(DORSAL_H)}',
        '',
    ])


def kotlin_source(model):
    """GnashfinModel.kt as a string: the hand-written class (see KT_HEAD) with the constants and the mesh generated from [model]."""
    calls, body, faces = K.kt_mesh(GROUPS, model.tex_w, model.tex_h)
    out = (KT_HEAD.replace('@@CONSTANTS@@', kt_constants()).replace('@@CALLS@@', calls).replace('@@CHUNKS@@', body)
           .replace('@@FACES@@', '\n' + faces + '\n' if faces else '').replace('@@W@@', str(model.tex_w)).replace('@@H@@', str(model.tex_h)))
    names = {p.name for p in model.all_parts()}
    for m_ in re.finditer(r'part\("([a-z0-9_$]+)"\)', out):
        if '$' not in m_.group(1):
            assert m_.group(1) in names, m_.group(1)
    need = [c[0] for c in CHAIN] + ['jaw', 'fang_l2', 'fang_r2', 'gill_l', 'gill_r', 'lure', 'bead', 'pec_l1', 'pec_r1', 'pec_l2', 'pec_r2', 'pel_l', 'pel_r', 'tu1', 'tl1', 'tu2', 'tl2']
    need += [f'lt{n}' for n in range(10)] + [f'dn{k}' for k in range(len(DORSAL_H))] + [f'an{k}' for k in range(len(ANAL_H))]
    for nm in need:
        assert nm in names, nm
    return out


# ================================================================== checking the Kotlin against pose()

def check_cases(n=60, seed=17):
    """Render states to compare the Kotlin with pose() on: every field over its range at random (the leap off one time in three), and the corners."""
    rng = np.random.default_rng(seed)
    out = []
    fields = [('swim', 0.0, 1.0), ('bend', -1.0, 1.0), ('jaw', 0.0, 1.0), ('stranded', 0.0, 1.0), ('gnaw', 0.0, 1.0), ('glow', 0.0, 1.0)]
    for i in range(n):
        c = {k: float(rng.uniform(lo, hi)) for k, lo, hi in fields}
        c['time'] = float(rng.uniform(0, 8000))
        c['leap'] = -1.0 if i % 3 == 0 else float(rng.uniform(0, 1))
        c['pitch'] = float(rng.uniform(-60, 60))
        c['roll'] = float(rng.uniform(-90, 90))
        if i % 4 == 1:
            c['stranded'] = 0.0
        out.append(c)
    base = dict(time=0.0, swim=0.0, bend=0.0, jaw=0.0, leap=-1.0, stranded=0.0, gnaw=0.0, glow=0.0, pitch=0.0, roll=0.0)
    out.append(dict(base))
    for k, lo, hi in fields:
        out.append(dict(base, **{k: hi, 'time': 77.0}))
        out.append(dict(base, **{k: lo, 'time': 91.0}))
    for u in (0.0, 0.25, 0.5, 0.75, 1.0):
        out.append(dict(base, leap=u, time=31.0))
    out.append({k: 1.0 for k in base} | {'time': 5.0, 'leap': 0.4, 'bend': -1.0})
    return out


def painted_geometry():
    """The built model and its scene without painting (for the checks that do not need the texture)."""
    model = build()
    K.pack_atlas(model)
    return model, K.SScene(model, DOUBLE), None, None


def check(work, n=60):
    """Compiles GnashfinModel.kt (what `kotlin_source` makes) with the game's own render state and Minecraft's ModelPart, runs its setupAnim on
    [check_cases] and compares every part with `pose()`. Returns (worst position error px, worst turn error, worst scale error, parts compared, problems)."""
    model, scene, _t, _g = painted_geometry()
    files = K.scratch_tree(work, {'client/sea/GnashfinModel.kt': kotlin_source(model)}, ['client/sea/GnashfinRenderState.kt'])
    r = K.verify_pose(files, 'io.github.vaspyyy.guildmark.client.sea', 'GnashfinModel', 'GnashfinRenderState', scene, check_cases(n), lambda c: GnashState(**c), pose, work)
    return r[0], r[1], r[2], r[3], list(r[4])


# ================================================================== the report: parts, what animates them, extents

NOTES = [
    (r'^mid$', 'body, middle (the origin: mid-body on the spine)', 'the root of the whole fish: carries the fore and aft halves; yaws a touch in the swimming wave; shakes a little as it gnaws'),
    (r'^fore$', 'forebody', '`swim`: yaws in the travelling wave; `bend`: swings the nose to its own right (above 0); `leap`: arches nose-up rising, nose-down falling; `stranded`: bucks; `gnaw`: shakes'),
    (r'^aft$', 'afterbody', '`swim`: yaws in the wave (growing toward the tail); `bend` curves it the other way; arches in a leap; bucks when stranded'),
    (r'^stalk$', 'tail stalk', 'the wave at its widest; slaps with `stranded`; whips at the top of a leap'),
    (r'^tail$', 'tail root', 'the last link of the wave; thrashes with `stranded`; flicks at the top of a leap'),
    (r'^skull$', 'skull and snout', 'the head end of the wave; `gnaw`: the head shakes and rolls; `bend`; `leap` arches it'),
    (r'^jaw$', 'lower jaw (underslung, jutting past the snout)', '`jaw`: gapes (to 0.95 rad); `gnaw`: clamps down and trembles; `stranded`: gapes and snaps'),
    (r'^fang_[lr]1$', 'jaw fangs, lower (two standing up at the jaw\'s tip)', 'ride the jaw'),
    (r'^fang_[lr]2$', 'jaw fangs, upper half', 'tremble'),
    (r'^lure$', 'chin lure stalk', 'sways, and streams back when it swims hard (`swim`)'),
    (r'^bead$', 'lure bead (glows)', 'pulses in scale with `glow`, swings'),
    (r'^gill_[lr]$', 'gill covers', 'pulse open and shut (`time`); flare wide as it gasps (`stranded`) and with `jaw`'),
    (r'^dn\d+$', 'dorsal fin (eleven slats along the back, each its own part)', 'sway in a travelling wave; fold back with `swim`; stand in a leap; thrash when `stranded`'),
    (r'^pec_[lr]1$', 'pectoral fins, root slat', '`swim` tucks them back flat along the flank (`tuck`); sculling at rest; thrown wide in a `leap`; flail when `stranded`'),
    (r'^pec_[lr]2$', 'pectoral fins, tip slat', 'follows the root slat with its own flutter'),
    (r'^pel_[lr]$', 'pelvic fins', 'flutter'),
    (r'^an\d$', 'anal fin (two slats)', 'ripple'),
    (r'^t[ul][12]$', 'tail fork (upper and lower lobe, two slats each)', 'ripple; spread with the flick at the top of a `leap`; thrash with `stranded`'),
    (r'^lt\d$', 'flank lights (five a side) - they glow', 'pulse in scale in a wave along each side, deeper with `glow`'),
]


def report_poses():
    """The poses the report's extents are taken over."""
    st = lambda **kw: pose(GnashState(**kw))
    poses = {
        'idle': st(time=40.0),
        'cruise': st(time=90.0, swim=0.5),
        'sprint, hard bend': st(time=130.0, swim=1.0, bend=1.0),
        'sprint, hard bend left': st(time=131.0, swim=1.0, bend=-1.0),
        'jaw wide': st(time=40.0, jaw=1.0),
        'gnawing': st(time=55.0, gnaw=1.0, jaw=0.4),
        'stranded (a few beats)': st(time=60.0, stranded=1.0, jaw=0.6),
        'stranded (another)': st(time=64.5, stranded=1.0),
    }
    for u in (0.0, 0.25, 0.5, 0.75, 1.0):
        poses[f'leap {u:g}'] = st(time=31.0, leap=u, swim=1.0)
    return poses


def parts_report(model, scene):
    """The text written beside the preview: the parts, what animates them and the extents."""
    head = ['Origin: the middle of the body on the spine (mid-body); px (1/16 block), y down, the fish faces -z, its right is -x.',
            'At rest: about 3 blocks nose to tail, 1.5 blocks across the spread fins and 1.7 blocks from the dorsal fin to the pelvic fins.',
            'The renderer pitches and rolls the whole fish (`pitch`, `roll`); the model does the swimming wave, the bend, the leap, the stranded flounder and the gnaw.',
            'Glow map: ten flank lights, the chin lure and its bead (teal); drawn again with RenderTypes.eyes, tinted ARGB.white(glow).',
            '']
    return K.report_text('The Gnashfin (GnashfinModel)', model, scene, NOTES, report_poses(), head)
