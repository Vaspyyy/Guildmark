"""A small renderer for block models: to see a model file the way the game draws it, without the game.

    python3 -B art/scripts/island_blockview.py <assets dir> <model name, e.g. gull_nest_3> [out.png]

It reads `models/block/<name>.json` (elements with their faces and uv, `rotation` in either of the game's two spellings, `light_emission`,
`shade_direction_override`) and the textures it names (`textures/block/*.png`; an animated strip shows the frame asked for, or the frame the game
would show at a tick, blended with the next if its .mcmeta says `interpolate`), and draws it in an orthographic view the way the inventory shows a
block: nearest-texel sampling (a cutout is a cutout, a half-clear texel blends), the game's face shading (top 1.0, north and south 0.8, east and
west 0.6, bottom 0.5), glowing elements drawn at full light and a soft bloom round them, and faces turned from the camera left out (the game's
terrain pipelines cull them). Supersampled and boxed down.

The conventions are the 26.3 game's own, read from its sources (`CuboidModelElement`, `CuboidRotation`, `FaceBakery`, `FaceInfo`): the corners of a
face are taken in `FaceInfo`'s order and take the face's uv corners in the order (minU, minV), (minU, maxV), (maxU, maxV), (maxU, minV), turned by
the face's `rotation`; an element's `x`, `y`, `z` turn it about `origin` as `rotationZYX` does (z first, then y, then x, each by the right hand rule
about the east, up and south axes), and `axis` and `angle` as a single turn; a rotated face is shaded as the direction it now most nearly faces.

Not the game: no ambient occlusion, no biome tints, no sky or block light (only a flat `ambient`); this is for checking geometry, uv and texture
together. Parents other than `minecraft:block/block` and `minecraft:block/cube_all` are not read.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

# the game's face shading for the overworld
SHADE = {'up': 1.0, 'down': 0.5, 'north': 0.8, 'south': 0.8, 'east': 0.6, 'west': 0.6}
DIRS = ('down', 'up', 'north', 'south', 'west', 'east')            # Direction.values() order
UNIT = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}


def load_texture(assets, ref, frame=0, tick=None):
    """A texture as float RGBA; an animated strip (taller than wide) gives its frame number `frame`, or, if `tick` is given, what the game shows at
    that tick: the frame `tick // frametime`, blended toward the next by the part of the frame gone if the .mcmeta says `interpolate`."""
    ns, _, path = ref.partition(':')
    if not path:
        path = ns
    file = os.path.join(assets, 'textures', path + '.png')
    im = np.asarray(Image.open(file).convert('RGBA'), np.float32)
    w = im.shape[1]
    if im.shape[0] > w and im.shape[0] % w == 0:
        n = im.shape[0] // w
        if tick is None:
            k = frame % n
            return im[k * w:(k + 1) * w]
        meta = {}
        if os.path.exists(file + '.mcmeta'):
            with open(file + '.mcmeta') as fh:
                meta = json.load(fh).get('animation', {})
        step = tick / max(1, int(meta.get('frametime', 1)))
        k = int(math.floor(step)) % n
        a = im[k * w:(k + 1) * w]
        if meta.get('interpolate'):
            b = im[((k + 1) % n) * w:((k + 1) % n + 1) * w]
            return a + (b - a) * (step - math.floor(step))
        return a
    return im


def rotation_matrix(rot):
    """The matrix of an element's `rotation`: Euler (`x`, `y`, `z`; JOML's rotationZYX, so a vector is turned about z first) or a single `axis` and `angle`."""
    if 'axis' in rot or 'angle' in rot:
        a = math.radians(rot['angle'])
        axis = rot['axis'].lower()
        c, s = math.cos(a), math.sin(a)
        if axis == 'x':
            return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
        if axis == 'y':
            return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
        return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
    rx, ry, rz = (math.radians(rot.get(k, 0.0)) for k in ('x', 'y', 'z'))
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rx @ Ry @ Rz


def face_corners(frm, to, d):
    """The four corners of a face in FaceInfo's order: vertex 0 takes (minU, minV), 1 (minU, maxV), 2 (maxU, maxV), 3 (maxU, minV)."""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    return {
        'down': [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
        'up': [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
        'north': [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
        'south': [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
        'west': [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
        'east': [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
    }[d]


def default_uv(frm, to, d):
    """The uv a face takes when it gives none (FaceBakery.defaultFaceUV)."""
    fx, fy, fz = frm
    tx, ty, tz = to
    return {
        'down': (fx, 16 - tz, tx, 16 - fz), 'up': (fx, fz, tx, tz),
        'north': (16 - tx, 16 - ty, 16 - fx, 16 - fy), 'south': (fx, 16 - ty, tx, 16 - fy),
        'west': (fz, 16 - ty, tz, 16 - fy), 'east': (16 - tz, 16 - ty, 16 - fz, 16 - fy),
    }[d]


def nearest_direction(n):
    """The direction a rotated face is shaded as (FaceBakery.findClosestDirection)."""
    best, top = 'up', 0.0
    for d in DIRS:
        p = float(np.dot(n, UNIT[d]))
        if p >= 0.0 and p > top:
            best, top = d, p
    return best


def faces_of(model):
    """Every face of every element as (4 corners in FaceInfo order with the element's rotation applied, 4 (u, v) pairs, texture reference, shade, glows,
    the outward normal)."""
    tex = model.get('textures', {})

    def resolve(r):
        seen = 0
        while isinstance(r, str) and r.startswith('#') and seen < 16:
            r = tex.get(r[1:], r)
            seen += 1
        return r
    out = []
    for el in model.get('elements', []):
        frm, to = [float(v) for v in el['from']], [float(v) for v in el['to']]
        rot = el.get('rotation')
        R = origin = None
        if rot:
            R = rotation_matrix(rot)
            origin = np.array(rot.get('origin', [8, 8, 8]), float)
        emit = el.get('light_emission', 0) > 0
        override = el.get('shade_direction_override')
        shaded = el.get('shade', True)
        for d, f in el.get('faces', {}).items():
            c = np.array(face_corners(frm, to, d), float)
            if R is not None:
                c = (c - origin) @ R.T + origin
            uv = f.get('uv') or default_uv(frm, to, d)
            minu, minv, maxu, maxv = uv
            vert = [(minu, minv), (minu, maxv), (maxu, maxv), (maxu, minv)]
            shift = (int(f.get('rotation', 0)) // 90) % 4
            vuv = [vert[(i + shift) % 4] for i in range(4)]
            n = np.cross(c[1] - c[0], c[2] - c[1])
            n = n / max(np.linalg.norm(n), 1e-9)
            facing = override or nearest_direction(n)
            sh = 1.0 if (not shaded and not override) else SHADE[facing]
            out.append((c, vuv, resolve(f['texture']), sh, emit, n))
    return out


def render_model(model, assets, yaw=45.0, elev=30.0, scale=14.0, size=(256, 256), frame=0, bg=(74, 78, 90), ss=3, bloom=1.0, center=(8, 8, 8),
                 emissive_scale=1.0, cull=True, tick=None, ambient=1.0):
    """The picture (uint8 RGB) of a model dict. `yaw` is the camera's bearing round from the south toward the east (45: from the south-east), `elev`
    its height above the horizon, `scale` the pixels to one model pixel (1/16 block). Faces turned away from the camera are not drawn, as the game's
    terrain pipelines cull them (`cull=False` draws them). The animated textures are at frame `frame` of their strips, or, given `tick`, as the game
    has them that many ticks after the textures loaded (each strip by its own frametime). `ambient` scales the light on the faces that do not glow
    (1: the game's day, less for a night)."""
    W, H = size
    Ws, Hs = W * ss, H * ss
    phi, th = math.radians(yaw), math.radians(elev)
    cam = np.array([math.cos(th) * math.sin(phi), math.sin(th), math.cos(th) * math.cos(phi)])     # toward the camera
    right = np.array([math.cos(phi), 0.0, -math.sin(phi)])
    up = np.cross(cam, right)
    cen = np.array(center, float)
    color = np.zeros((Hs, Ws, 3), np.float32)
    color[:] = np.array(bg, np.float32)
    depth = np.full((Hs, Ws), -1e9, np.float32)
    emis = np.zeros((Hs, Ws), np.float32)
    texcache = {}
    faces = faces_of(model)

    def tex_of(ref):
        if ref not in texcache:
            texcache[ref] = load_texture(assets, ref, frame, tick)
        return texcache[ref]

    def proj(p):
        q = np.asarray(p, float) - cen
        return np.array([Ws / 2 + (q @ right) * scale * ss, Hs / 2 - (q @ up) * scale * ss]), float(q @ cam)

    def draw(i, face, translucent_pass):
        c, vuv, ref, shade, emit, nrm = face
        if cull and float(nrm @ cam) <= 1e-6:
            return
        t = tex_of(ref)
        th_, tw_ = t.shape[:2]
        (s0, d0), (s1, d1), (s2, d2), (s3, d3) = (proj(p) for p in c)
        # the quad is a parallelogram: P = v0 + a (v3 - v0) + b (v1 - v0); the uv follow the same two edges
        ea, eb = s3 - s0, s1 - s0
        det = ea[0] * eb[1] - ea[1] * eb[0]
        if abs(det) < 1e-3:
            return
        xs = [s0[0], s1[0], s2[0], s3[0]]
        ys = [s0[1], s1[1], s2[1], s3[1]]
        x0, x1 = max(0, int(math.floor(min(xs)))), min(Ws, int(math.ceil(max(xs))) + 1)
        y0, y1 = max(0, int(math.floor(min(ys)))), min(Hs, int(math.ceil(max(ys))) + 1)
        if x0 >= x1 or y0 >= y1:
            return
        gy, gx = np.mgrid[y0:y1, x0:x1].astype(np.float32)
        px, py = gx + 0.5 - s0[0], gy + 0.5 - s0[1]
        a = (px * eb[1] - py * eb[0]) / det
        b = (ea[0] * py - ea[1] * px) / det
        m = (a >= 0) & (a < 1) & (b >= 0) & (b < 1)
        if not m.any():
            return
        dep = d0 + a * (d3 - d0) + b * (d1 - d0) + i * 1e-4
        (u0, v0), (u1, v1), (u2, v2), (u3, v3) = vuv
        u = u0 + a * (u3 - u0) + b * (u1 - u0)
        v = v0 + a * (v3 - v0) + b * (v1 - v0)
        tx = np.clip(np.floor(u / 16.0 * tw_).astype(int), 0, tw_ - 1)
        ty = np.clip(np.floor(v / 16.0 * th_).astype(int), 0, th_ - 1)
        texel = t[ty, tx]
        al = texel[..., 3] / 255.0
        vis = m & (al > 0.02) & (dep > depth[y0:y1, x0:x1] - 1e-6)
        vis &= (al <= 0.999) if translucent_pass else (al > 0.999)
        if not vis.any():
            return
        rgb = texel[..., :3] * (shade * ambient if not emit else emissive_scale)
        reg = color[y0:y1, x0:x1]
        if translucent_pass:
            reg[vis] = reg[vis] * (1 - al[vis, None]) + rgb[vis] * al[vis, None]
        else:
            reg[vis] = rgb[vis]
            depth[y0:y1, x0:x1][vis] = dep[vis]
        if emit:
            emis[y0:y1, x0:x1][vis] = np.maximum(emis[y0:y1, x0:x1][vis], al[vis])

    for i, f in enumerate(faces):
        draw(i, f, False)
    # the half-clear faces, far to near
    order = sorted(range(len(faces)), key=lambda k: float(np.mean([float((np.asarray(p) - cen) @ cam) for p in faces[k][0]])))
    for i in order:
        draw(i, faces[i], True)
    img = color
    if bloom > 0 and emis.max() > 0:
        k = max(1, int(round(scale * ss * 0.7)))
        glow = np.clip(_blur(emis, k) * 1.6, 0, 1)[..., None] * np.array([255, 226, 150], np.float32) * 0.55 * bloom
        img = np.clip(img + glow, 0, 255)
    img = img.reshape(H, ss, W, ss, 3).mean(axis=(1, 3))
    return np.clip(img, 0, 255).astype(np.uint8)


def _blur(a, r):
    """A soft blur of radius ~r by repeated box passes along both axes."""
    out = a.astype(np.float32)
    k = max(1, r // 2)
    n = 2 * k + 1
    for _ in range(3):
        for axis in (0, 1):
            pad = [(k, k) if ax == axis else (0, 0) for ax in (0, 1)]
            c = np.cumsum(np.pad(out, pad, mode='edge'), axis=axis, dtype=np.float64)
            zero_shape = list(out.shape)
            zero_shape[axis] = 1
            c = np.concatenate([np.zeros(zero_shape), c], axis=axis)
            hi = np.take(c, range(n, n + out.shape[axis]), axis=axis)
            lo = np.take(c, range(0, out.shape[axis]), axis=axis)
            out = ((hi - lo) / n).astype(np.float32)
    return out


def load_model(assets, name):
    """models/block/<name>.json, with the `cube_all` parent written out as the element it stands for."""
    with open(os.path.join(assets, 'models', 'block', name + '.json')) as f:
        m = json.load(f)
    if m.get('parent') == 'minecraft:block/cube_all' and 'elements' not in m:
        m = dict(m)
        m['elements'] = [{'from': [0, 0, 0], 'to': [16, 16, 16],
                          'faces': {d: {'uv': [0, 0, 16, 16], 'texture': '#all', 'cullface': d} for d in UNIT}}]
    return m


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__)
    assets, name = argv[0], argv[1]
    out = argv[2] if len(argv) > 2 else name + '.png'
    Image.fromarray(render_model(load_model(assets, name), assets)).save(out)


if __name__ == '__main__':
    main(sys.argv[1:])
