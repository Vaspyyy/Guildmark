"""Block-model geometry for Hearthcraft's art (cook_art.py): elements, rotations, rings, crossed quads, and the JSON
the game reads, written the way the rest of the mod's generators write it (lists of numbers on one line).

Model space: x east, y up, z south, 0 to 16 a block; every model is authored facing north (its front toward -z).
An element's `rotation` is the game's Euler form {origin, x, y, z}, applied z first, then y, then x (the same as
`island_blockview.py` reads it and `works_art.py` writes it, whose `cube`, `along`, `ring` and `disc` these follow).
"""
import json
import math
import os

import numpy as np

SIX = ('down', 'up', 'north', 'south', 'west', 'east')
SIDES = ('north', 'south', 'west', 'east')
TEX_NS = 'guildmark:block/cook/'


def r3(v):
    v = round(float(v), 3)
    return 0 if v == 0 else (int(v) if v == int(v) else v)


def Rx(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[1.0, 0, 0], [0, c, -s], [0, s, c]])


def Ry(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[c, 0, s], [0, 1.0, 0], [-s, 0, c]])


def Rz(a):
    c, s = math.cos(math.radians(a)), math.sin(math.radians(a))
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])


def euler_xyz(R):
    """(x, y, z) degrees with R = Rx(x) Ry(y) Rz(z): an element's `rotation`, which the game applies z first, then y,
    then x."""
    y = math.asin(max(-1.0, min(1.0, R[0, 2])))
    if abs(math.cos(y)) > 1e-6:
        return math.degrees(math.atan2(-R[1, 2], R[2, 2])), math.degrees(y), math.degrees(math.atan2(-R[0, 1], R[0, 0]))
    return math.degrees(math.atan2(R[2, 1], R[1, 1])), math.degrees(y), 0.0


def turn_to(v_from, v_to):
    """The rotation taking the direction [v_from] to [v_to] by the shortest turn."""
    a = np.asarray(v_from, float) / np.linalg.norm(v_from)
    b = np.asarray(v_to, float) / np.linalg.norm(v_to)
    k = np.cross(a, b)
    sn, cs = float(np.linalg.norm(k)), float(a @ b)
    if sn < 1e-9:
        return np.eye(3) if cs > 0 else Rx(180.0)
    k = k / sn
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + sn * K + (1 - cs) * (K @ K)


def _uv_default(frm, to, d):
    fx, fy, fz = frm
    tx, ty, tz = to
    return {'down': (fx, 16 - tz, tx, 16 - fz), 'up': (fx, fz, tx, tz),
            'north': (16 - tx, 16 - ty, 16 - fx, 16 - fy), 'south': (fx, 16 - ty, tx, 16 - fy),
            'west': (fz, 16 - ty, tz, 16 - fy), 'east': (16 - tz, 16 - ty, 16 - fz, 16 - fy)}[d]


def _into(a, b):
    """A uv span moved whole into the texture (0 to 16), keeping its texels to the pixel; squeezed only if it's longer."""
    span = b - a
    if span > 16.0:
        return 0.0, 16.0
    a2 = a % 16.0
    if a2 + span > 16.0 + 1e-6:
        a2 = 16.0 - span
    return a2, a2 + span


def uv_for(frm, to, d):
    """The uv a face takes by default, moved into the texture: so boards, bricks and grain run on from one element to
    the next, two texels to a pixel, wherever the element is."""
    u0, v0, u1, v1 = _uv_default(frm, to, d)
    u0, u1 = _into(u0, u1)
    v0, v1 = _into(v0, v1)
    return u0, v0, u1, v1


def cube(frm, to, tex, faces=SIX, uv=None, rot=None, origin=None, **extra):
    """An element from [frm] to [to] (px) with [faces], each in texture [tex] (a name, or {face: name} with '*' for the
    rest; None leaves the face out) at [uv] (None: uv_for's; one uv for all; or {face: uv} with '*'), turned by [rot] (a
    matrix, or (x, y, z) degrees as the game takes them) about [origin]; [extra]: light_emission, shade and the like."""
    e = {'from': [r3(v) for v in frm], 'to': [r3(v) for v in to]}
    if rot is not None:
        ang = euler_xyz(rot) if isinstance(rot, np.ndarray) else rot
        if any(abs(a) > 1e-4 for a in ang):
            e['rotation'] = {'origin': [r3(v) for v in origin], 'x': r3(ang[0]), 'y': r3(ang[1]), 'z': r3(ang[2])}
    e.update(extra)
    fs = {}
    for d in faces:
        t = (tex.get(d, tex.get('*')) if isinstance(tex, dict) else tex)
        if t is None:
            continue
        u = uv.get(d, uv.get('*')) if isinstance(uv, dict) else uv
        if u is None:
            u = uv_for(frm, to, d)
        fs[d] = {'uv': [r3(v) for v in u], 'texture': '#' + t}
    e['faces'] = fs
    return e


def along(p0, p1, w, d, tex, faces=SIX, roll=0.0, uv=None, **extra):
    """A [w] x [d] bar from p0 to p1: laid up its y, rolled [roll] degrees about its length, then turned onto the line."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    m = (p0 + p1) / 2
    L = float(np.linalg.norm(p1 - p0))
    R = turn_to((0, 1, 0), p1 - p0) @ Ry(roll)
    return cube(m - (w / 2, L / 2, d / 2), m + (w / 2, L / 2, d / 2), tex, faces, uv=uv, rot=R, origin=m, **extra)


def ring(cx, cz, y0, y1, r_out, thick, tex, inner=None, top=True, bottom=False, turn=0.0, uv=None, inside=True):
    """An octagonal wall round (cx, cz): eight slabs, the outer faces at apothem [r_out], [thick] px thick, from y0 to
    y1; the inside in [inner] (or [tex], and left out with [inside] False); turned [turn] degrees. The four turned slabs'
    tops and bottoms sit a hair in from the others' so the overlaps at the corners never fight."""
    s = 2.0 * r_out * math.tan(math.pi / 8)
    slabs = [((cx - s / 2, cz - r_out), (cx + s / 2, cz - r_out + thick), 'north', 'south'),
             ((cx - s / 2, cz + r_out - thick), (cx + s / 2, cz + r_out), 'south', 'north'),
             ((cx - r_out, cz - s / 2), (cx - r_out + thick, cz + s / 2), 'west', 'east'),
             ((cx + r_out - thick, cz - s / 2), (cx + r_out, cz + s / 2), 'east', 'west')]
    els = []
    for diag in (0.0, 45.0):
        eps = 0.0 if diag == 0.0 else 0.012
        for (ax, az), (bx, bz), out, inn in slabs:
            fs = [out] + ([inn] if inside else []) + (['up'] if top else []) + (['down'] if bottom else [])
            els.append(cube((ax, y0 + (eps if bottom else 0.0), az), (bx, y1 - (eps if top else 0.0), bz), {inn: inner or tex, '*': tex}, fs, uv=uv,
                            rot=(0.0, diag + turn, 0.0), origin=(cx, y0, cz)))
    return els


def lathe(cx, cz, profile, thick, tex, inner=None, outer_tex=None, uv=None, inside=True, grow=0.0):
    """A hollow vessel turned from a [profile] of (y0, y1, apothem) bands, bottom first: each band a ring of eight slabs [thick] thick, with
    its top and bottom faces (a ledge where a band is wider or narrower than its neighbour shows, and where two meet the faces face away
    from each other and never show)."""
    els = []
    for (y0, y1, r) in profile:
        els += ring(cx, cz, y0, y1, r + grow, thick, outer_tex or tex, inner=inner, top=True, bottom=True, uv=uv, inside=inside)
    return els


def disc(cx, cz, y0, y1, r, tex, top=None, faces=SIX, turn=0.0, uv=None):
    """A filled regular octagon round (cx, cz), apothem [r], from y0 to y1: four bars [2r] long and an octagon side wide, at 0, 45, 90 and
    135 degrees (their union is exactly the octagon: each bar's two ends are two of its sides, and the points where the bars' long edges
    cross are its corners), the tops a hair apart so they never fight, the bars' long faces (all inside the others) left out."""
    s = 2.0 * r * math.tan(math.pi / 8)
    els = []
    for k, diag in enumerate((0.0, 45.0, 90.0, 135.0)):
        yt = y1 - 0.008 * k
        fs = [f for f in faces if f not in ('north', 'south')]
        els.append(cube((cx - r, y0, cz - s / 2), (cx + r, yt, cz + s / 2), {'up': top or tex, '*': tex}, fs,
                        rot=(0.0, diag + turn, 0.0), origin=(cx, y0, cz), uv=uv))
    return els


def crossed(x0, x1, y0, y1, tex, cx=8.0, cz=8.0, half=None, uv=(0, 0, 16, 16), turn=0.0, **extra):
    """Two upright quads crossing at (cx, cz), each [half]*2 across (default x1 - x0) and from y0 to y1, both faces of each in
    [tex]: the way the game draws a crop or a campfire's flames. [turn] turns the pair about the vertical through (cx, cz)."""
    half = half if half is not None else (x1 - x0) / 2
    els = []
    for k, (a, b) in enumerate(((0.0, 0.0), (90.0, 0.0))):
        f = {'north': {'uv': [r3(v) for v in uv], 'texture': '#' + tex}, 'south': {'uv': [r3(v) for v in uv], 'texture': '#' + tex}}
        e = {'from': [r3(cx - half), r3(y0), r3(cz)], 'to': [r3(cx + half), r3(y1), r3(cz)], 'faces': f}
        if abs(a + turn) > 1e-6:
            e['rotation'] = {'origin': [r3(cx), r3(y0), r3(cz)], 'x': 0, 'y': r3(a + turn), 'z': 0}
        e.update(extra)
        els.append(e)
    return els


def quad(frm, to, face, tex, uv=(0, 0, 16, 16), **extra):
    """One flat face (a plane has no thickness: its [frm] and [to] share one coordinate)."""
    e = {'from': [r3(v) for v in frm], 'to': [r3(v) for v in to], 'faces': {face: {'uv': [r3(v) for v in uv], 'texture': '#' + tex}}}
    e.update(extra)
    return e


def used(elements):
    """The textures [elements] name, in order."""
    out = []
    for e in elements:
        for f in e['faces'].values():
            t = f['texture'][1:]
            if t not in out:
                out.append(t)
    return out


def model(elements, particle, ao=True, ns=TEX_NS, textures=None, **top):
    """A block model of [elements]: its textures read from the elements (in the cook folder, or {name: reference} in
    [textures] for the rest), the particle one of them."""
    m = {'parent': 'minecraft:block/block'}
    if not ao:
        m['ambientocclusion'] = False
    m.update(top)
    tex = {'particle': (textures or {}).get(particle, ns + particle)}
    for t in used(elements):
        tex[t] = (textures or {}).get(t, ns + t)
    m['textures'] = tex
    m['elements'] = elements
    return m


def move(elements, dx=0.0, dy=0.0, dz=0.0):
    """Copies of [elements] moved by (dx, dy, dz) px (their rotation origins with them)."""
    out = []
    for e in elements:
        c = json.loads(json.dumps(e))
        c['from'] = [r3(v + d) for v, d in zip(c['from'], (dx, dy, dz))]
        c['to'] = [r3(v + d) for v, d in zip(c['to'], (dx, dy, dz))]
        if 'rotation' in c:
            c['rotation']['origin'] = [r3(v + d) for v, d in zip(c['rotation']['origin'], (dx, dy, dz))]
        out.append(c)
    return out


# ================================================================ writing

def dump_json(obj, indent=2, _level=0):
    """JSON the way the game's own files read, but a list of plain numbers or words on one line."""
    pad = ' ' * (indent * (_level + 1))
    end = ' ' * (indent * _level)
    if isinstance(obj, dict):
        if not obj:
            return '{}'
        rows = [f'{pad}{json.dumps(k)}: {dump_json(v, indent, _level + 1)}' for k, v in obj.items()]
        return '{\n' + ',\n'.join(rows) + '\n' + end + '}'
    if isinstance(obj, (list, tuple)):
        if all(not isinstance(v, (dict, list, tuple)) for v in obj):
            return '[' + ', '.join(json.dumps(v) for v in obj) + ']'
        rows = [pad + dump_json(v, indent, _level + 1) for v in obj]
        return '[\n' + ',\n'.join(rows) + '\n' + end + ']'
    return json.dumps(obj)


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(text + '\n')


def write_json(path, obj):
    write_text(path, dump_json(obj))
