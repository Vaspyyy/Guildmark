"""Seeing Hearthcraft's models the way the game draws them, without the game: a scene of several block models, each moved
and turned as a renderer would place it (a pot lowered on its chain, a door opened on its hinge, a crane swung round its
post), drawn with `island_blockview.py`'s renderer, with the vanilla campfire and furnace (read from an unpacked game
assets folder) for scale, boxes drawn as outlines, and noon or dusk light. Only for previews: nothing here is written
into the mod.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import island_blockview as BV
from cook_geo import Rx, Ry, Rz

NOON = dict(ambient=1.0, bg=(120, 150, 186))
DUSK = dict(ambient=0.42, bg=(44, 38, 62))


class Part:
    """A model placed in a scene: [model] a name under the cook assets (`models/block/<name>.json`), a `minecraft:`
    reference to a game model, or a model dict; turned by [rot] (degrees x, y, z as the game's elements are, or a matrix)
    about [pivot] (model px), then moved by [move] px, shown at animation [tick]."""

    def __init__(self, model, move=(0, 0, 0), rot=(0, 0, 0), pivot=(8, 0, 8), at=(0, 0, 0)):
        self.model = model
        self.move = np.asarray(move, float)
        self.rot = rot
        self.pivot = np.asarray(pivot, float)
        self.at = np.asarray(at, float)           # the block it is in, as a block-to-block offset in px (16 per block)

    def matrix(self):
        r = self.rot
        if isinstance(r, np.ndarray):
            return r
        x, y, z = r
        return Rx(x) @ Ry(y) @ Rz(z)


class Roots:
    """Where models and textures are read from: the mod's assets, and a game assets folder (assets/minecraft) if given. Preview-only
    textures (the ground a scene stands on) are painted into a temporary folder that is removed at exit, never into the assets."""

    def __init__(self, assets, game=None):
        self.assets = assets
        self.game = game
        self._tmp = None

    def scratch(self, name, image):
        """The reference (an absolute path with no extension) of a preview-only [image], saved under [name] in the temporary folder."""
        import atexit
        import shutil
        import tempfile
        if self._tmp is None:
            self._tmp = tempfile.mkdtemp(prefix='cook_view_')
            atexit.register(shutil.rmtree, self._tmp, True)
        path = os.path.join(self._tmp, name)
        if not os.path.exists(path + '.png'):
            image.save(path + '.png')
        return path

    def ground(self):
        """The paving a scene stands on: the cook flagstone, dimmed, so the models stand out from it."""
        import cook_tex
        im = cook_tex.flagstone()
        arr = np.asarray(im, float)
        arr[..., :3] *= 0.62
        return self.scratch('ground', Image.fromarray(arr.astype(np.uint8), 'RGBA'))

    def root(self, ns):
        if ns == 'minecraft':
            if not self.game:
                raise FileNotFoundError('no game assets given')
            return self.game
        return self.assets

    def model_path(self, ref):
        ns, _, path = ref.partition(':')
        if not path:
            ns, path = 'guildmark', ns
        if not path.startswith(('block/', 'item/')):
            path = 'block/' + path
        return os.path.join(self.root(ns), 'models', path + '.json'), ns

    def texture_ref(self, ref, ns):
        """A texture reference as `island_blockview` can load it: a path under the mod's assets, or the absolute path of
        a game texture (it joins an absolute path over its base)."""
        n, _, path = ref.partition(':')
        if not path:
            n, path = ns, n
        if n == 'minecraft':
            return os.path.join(self.root('minecraft'), 'textures', path)
        return 'guildmark:' + path


def load_model(roots, model, ns='guildmark'):
    """A model with its parents merged: elements from the nearest that has them, textures from all (the child's win)."""
    if isinstance(model, dict):
        m = dict(model)
        m_ns = ns
    else:
        f, m_ns = roots.model_path(model)
        with open(f) as fh:
            m = json.load(fh)
    textures = {}
    elements = m.get('elements')
    parent = m.get('parent')
    if parent and ':' not in parent:
        parent = 'minecraft:' + parent                    # a reference with no namespace means the game's, as it does in the game
    if parent and parent not in ('minecraft:block/block',):
        pm = load_model(roots, parent)
        textures.update(pm.get('textures', {}))
        if elements is None:
            elements = pm.get('elements')
    # Textures of this model name their namespace by where this model was read from
    own = {}
    for k, v in m.get('textures', {}).items():
        if v.startswith('#'):
            own[k] = v
        else:
            own[k] = roots.texture_ref(v, m_ns)
    textures.update(own)
    return {'textures': textures, 'elements': elements or [], 'ao': m.get('ambientocclusion', True)}


def faces(model, part):
    """The faces of a model dict, each turned and moved as [part] says: (corners, uv, texture ref, shade, glows, normal)."""
    out = []
    R = part.matrix()
    tex = model['textures']

    def resolve(r):
        seen = 0
        while isinstance(r, str) and r.startswith('#') and seen < 16:
            r = tex.get(r[1:], r)
            seen += 1
        return r
    for el in model['elements']:
        frm, to = [float(v) for v in el['from']], [float(v) for v in el['to']]
        rot = el.get('rotation')
        Re = origin = None
        if rot:
            Re = BV.rotation_matrix(rot)
            origin = np.array(rot.get('origin', [8, 8, 8]), float)
        emit = el.get('light_emission', 0) > 0
        override = el.get('shade_direction_override')
        shaded = el.get('shade', True)
        for d, f in el.get('faces', {}).items():
            c = np.array(BV.face_corners(frm, to, d), float)
            if Re is not None:
                c = (c - origin) @ Re.T + origin
            c = (c - part.pivot) @ R.T + part.pivot + part.move + part.at
            uv = f.get('uv') or BV.default_uv(frm, to, d)
            minu, minv, maxu, maxv = uv
            vert = [(minu, minv), (minu, maxv), (maxu, maxv), (maxu, minv)]
            shift = (int(f.get('rotation', 0)) // 90) % 4
            vuv = [vert[(i + shift) % 4] for i in range(4)]
            n = np.cross(c[1] - c[0], c[2] - c[1])
            n = n / max(np.linalg.norm(n), 1e-9)
            facing = override or BV.nearest_direction(n)
            sh = 1.0 if (not shaded and not override) else BV.SHADE[facing]
            out.append((c, vuv, resolve(f['texture']), sh, emit, n))
    return out


def box_corners(lo, hi):
    return [np.array([x, y, z], float) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]


def render(parts, roots, yaw=150.0, elev=30.0, scale=10.0, size=(220, 220), tick=0, center=(8, 5, 8), boxes=(), ground=True,
           mode=NOON, labels_px=None, ss=3, bloom=1.0):
    """A scene: [parts] (Part list, or (model, Part) pairs resolved through [roots]) drawn from [yaw] and [elev]; [boxes] a list of
    (lo, hi, rgb) outlines in model px. Returns a PIL RGB image."""
    allf = []
    if ground:
        gp = Part({'textures': {'g': roots.ground()}, 'elements': [
            {'from': [0, -0.4, 0], 'to': [16, 0, 16], 'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': '#g'}, 'north': {'uv': [0, 0, 16, 1], 'texture': '#g'},
                                                              'south': {'uv': [0, 0, 16, 1], 'texture': '#g'}, 'west': {'uv': [0, 0, 16, 1], 'texture': '#g'},
                                                              'east': {'uv': [0, 0, 16, 1], 'texture': '#g'}}}]})
        gm = load_model(roots, gp.model)
        allf += faces(gm, gp)
    for p in parts:
        m = load_model(roots, p.model)
        allf += faces(m, p)
    orig = BV.faces_of
    BV.faces_of = lambda model: allf
    try:
        img = BV.render_model({}, roots.assets, yaw=yaw, elev=elev, scale=scale, size=size, tick=tick, center=center, ambient=mode['ambient'],
                              bg=mode['bg'], ss=ss, bloom=bloom)
    finally:
        BV.faces_of = orig
    im = Image.fromarray(img).convert('RGB')
    if boxes:
        d = ImageDraw.Draw(im)
        for lo, hi, col in boxes:
            draw_box(d, lo, hi, col, yaw, elev, scale, size, center)
    return im


def project(p, yaw, elev, scale, size, center):
    phi, th = math.radians(yaw), math.radians(elev)
    cam = np.array([math.cos(th) * math.sin(phi), math.sin(th), math.cos(th) * math.cos(phi)])
    right = np.array([math.cos(phi), 0.0, -math.sin(phi)])
    up = np.cross(cam, right)
    q = np.asarray(p, float) - np.asarray(center, float)
    return (size[0] / 2 + (q @ right) * scale, size[1] / 2 - (q @ up) * scale)


def draw_box(d, lo, hi, col, yaw, elev, scale, size, center):
    pts = {}
    for i, x in enumerate((lo[0], hi[0])):
        for j, y in enumerate((lo[1], hi[1])):
            for k, z in enumerate((lo[2], hi[2])):
                pts[(i, j, k)] = project((x, y, z), yaw, elev, scale, size, center)
    for a in pts:
        for b in pts:
            if sum(abs(a[t] - b[t]) for t in range(3)) == 1 and a < b:
                d.line([pts[a], pts[b]], fill=col, width=1)


def cell_outline(d, yaw, elev, scale, size, center, col=(255, 255, 255)):
    """The block's own cell (0 to 16) as a faint wire box."""
    draw_box(d, (0, 0, 0), (16, 16, 16), col, yaw, elev, scale, size, center)
