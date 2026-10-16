"""The sea monsters' shared kit: what `sea_arm.py`, `sea_head.py` and `sea_gnashfin.py` (the Maelgrasp's arm, the Maelgrasp's head and
the Gnashfin; `sea_monsters.py` runs them all) are built on. It borrows the cog's toolkit (`ship.py`: rig math, noise, the camera and
rasteriser, the sea and sky, the GIF writer) and adds what three creatures made of many small boxes need:

* parts and cubes in Minecraft model space (pixels, y down, a creature faces -z, its right is -x), `Cu` cubes that know what they are made
  of (`mat` picks the painting, `kw` its parameters) and may draw only some of their faces, and `prism`, a round slice made of two to four
  boxes turned an eighth, a twelfth or a sixteenth of a turn on each other (the cog's masts and barrels use the same trick);
* an atlas packer that places only the faces a cube really draws (a mantle slice has no use for its top and bottom), checked afterwards
  for faces outside the texture and for overlaps;
* the painting driver: every texel of every face is turned back into its place on the model and the paint is a function of that place,
  so scales, veins and barnacles run on over the edges of a box; glow layers are painted alongside and assembled into the glow map;
* the Kotlin body of a model (parts, their rest poses and the faces each cube draws), cut into helper methods so none gets near the JVM's
  64 KB method limit, the same way `ship.py` writes the cog's;
* a numeric check of the generated Kotlin: the real model class is compiled and run headless (Minecraft's own `ModelPart` classes load on
  Java 25) and every part's pose is compared with the Python `pose()` that the previews run;
* a stage for the previews: any number of models in one scene, depth merged, over the cog's sea and sky, with foam where they meet the water.

Nothing here draws a creature: see the three model scripts.
"""
import sys, os, math, json, re, subprocess, shutil, collections
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np
from PIL import Image, ImageFilter
import ship
from modelkit import Cube, Part, Model, DIRECTIONS         # (Model: the model scripts take it from here)
from ship import krot, kpos, half, rotm_rad, ramp, sstep, fbm2, mixc, seg_dist, Out        # (Out: the model scripts take it from here)

HD = 2                           # every PNG is painted at twice the size the model declares
D2R = math.pi / 180.0

SIDES = frozenset({'right', 'front', 'left', 'back'})         # the four faces round a box's long axis: what a tube's slice draws
ALL = frozenset(DIRECTIONS)


# ================================================================== parts and cubes

class Cu(Cube):
    """A cube that knows what it is made of: [mat] picks its painting, [kw] holds its parameters. [faces] (a set of 'top' 'bottom' 'front'
    'back' 'left' 'right') are the only faces it draws and packs; the box UV layout is the usual one (a cube's front is its -z face, its top
    the face at its smallest y). Sizes are rounded to whole HD texels (half a declared one), never below one."""

    def __init__(self, origin, size, mat, name, faces=None, **kw):
        faces = None if faces is None or set(faces) == set(ALL) else frozenset(faces)
        super().__init__(tuple(float(v) for v in origin), tuple(half(v) if v else 0.0 for v in size), None, name=name, faces=faces)
        self.mat, self.kw = mat, kw

    def drawn(self):
        """The faces this cube draws, in the order the atlas lists them."""
        return [f for f in ('top', 'bottom', 'right', 'front', 'left', 'back') if self.shows(f)]


class Reg:
    """Part names must be unique within a model (the Kotlin looks parts up by name)."""

    def __init__(self):
        self.names = set()

    def unique(self, name):
        assert name not in self.names, f'duplicate part name {name}'
        self.names.add(name)
        return name

    def clear(self):
        self.names.clear()


REG = Reg()


def part(name, pivot=(0, 0, 0), rot=(0, 0, 0), cubes=(), children=()):
    """A part at [pivot] (px) turned by [rot] (degrees about x, y, z, applied as Rz Ry Rx, as the game does)."""
    return Part(REG.unique(name), kpos(pivot), krot(rot), list(cubes), list(children))


def box(name, o, s, mat, pivot=None, rot=(0, 0, 0), faces=None, children=(), **kw):
    """One cube from its min corner [o] and size [s] as a part of its own: at the origin, or at [pivot] (then [o] is relative to it)."""
    c = Cu(o, s, mat, name, faces=faces, **kw)
    return part(name, pivot or (0, 0, 0), rot, [c], children)


def prism(name, a, lo, hi, mat, axis='y', copies=2, faces=None, pivot=(0, 0, 0), rot=(0, 0, 0), kids=(), centre=(0.0, 0.0), drop=0.1, **kw):
    """A round slice: one part holding a box of half-side [a] along [axis] ('y' or 'z') from [lo] to [hi], and [copies] - 1 child parts
    holding the same box turned 90 / copies degrees about that axis. The union of the boxes is a star whose hull is a regular polygon of
    4 * copies sides and circumradius 1.414 a (two boxes: an octagon; three: a 12-gon; four: a 16-gon), which is what a roundish limb or
    mantle looks like in boxes. The turned copies sit [drop] px lower along the axis each so no two caps are coplanar (they would shimmer).
    [centre] moves the slice off its axis (the two cross coordinates). Cubes carry kw `turn` (their copy's angle in degrees), `axis`,
    `a`, `lo`, `hi`, `copy`; the part's [kids] hang from the first copy's part."""
    cx, cz = centre
    s = 2 * a
    parts_ = []
    for k in range(copies):
        nm = name if k == 0 else f'{name}_c{k}'
        turn = 90.0 * k / copies
        d = drop * k
        if axis == 'y':
            c = Cu((cx - a, lo + d, cz - a), (s, hi - lo, s), mat, nm, faces=faces, turn=turn, axis='y', a=a, lo=lo, hi=hi, copy=k, **kw)
            rr = (0, turn, 0)
        else:
            c = Cu((cx - a, cz - a, lo + d), (s, s, hi - lo), mat, nm, faces=faces, turn=turn, axis='z', a=a, lo=lo, hi=hi, copy=k, **kw)
            rr = (0, 0, turn)
        parts_.append((nm, c, rr))
    first = part(name, pivot, rot, [parts_[0][1]], list(kids))
    for nm, c, rr in parts_[1:]:
        first.children.append(part(nm, (0, 0, 0), rr, [c]))
    return first


def star_half(r, copies=2):
    """The half-side of the boxes of a [copies]-box star slice whose silhouette is about [r] across from the axis (the hull's
    circumradius is 1.414 a, its inradius 1.414 a cos(45 / copies degrees); the mean of the two is what a roundish limb reads as)."""
    inr = math.cos(math.radians(45.0 / copies))
    return r / (0.5 * 1.414 * (1.0 + inr))


def unit_or(v, fallback):
    v = np.asarray(v, float)
    n = float(np.linalg.norm(v))
    return v / n if n > 1e-9 else np.asarray(fallback, float)


# ================================================================== the scene: faces, poses (as ship.Scene, drawing only the faces a cube draws)

class SScene(ship.Scene):
    """The model flattened for posing and drawing: every part with its parent and rest pose, every drawn face of every cube."""

    def __init__(self, model, double=()):
        self.model = model
        self.parts = list(model.all_parts())
        self.index = {p.name: i for i, p in enumerate(self.parts)}
        self.parent = np.full(len(self.parts), -1)
        for p in self.parts:
            for ch in p.children:
                self.parent[self.index[ch.name]] = self.index[p.name]
        self.rest_pos = np.array([p.pivot for p in self.parts], float)
        self.rest_rot = np.array([[math.radians(a) for a in p.rot] for p in self.parts], float)
        fp, fa, fb, fd, fn, fr, fc, ff = [], [], [], [], [], [], [], []
        self.cubes = []
        for pi, p in enumerate(self.parts):
            for c in p.cubes:
                ci = len(self.cubes)
                self.cubes.append((pi, c))
                rects = ship.face_rects(c)
                for face, (A, B, D, n) in ship.face_corners(c).items():
                    if not c.shows(face):
                        continue
                    x, y, w, h = rects[face]
                    if w <= 0 or h <= 0:
                        continue
                    fp.append(pi); fa.append(A); fb.append(B); fd.append(D); fn.append(n); fc.append(ci); ff.append(face)
                    fr.append((int(round(x * HD)), int(round(y * HD)), int(round(w * HD)), int(round(h * HD))))
        self.FP = np.array(fp); self.FA = np.array(fa); self.FB = np.array(fb); self.FD = np.array(fd)
        self.FN = np.array(fn, float); self.FR = np.array(fr); self.FC = np.array(fc); self.FF = ff
        dbl = set(double)
        self.double = np.array([self.cubes[ci][1].mat in dbl for ci in fc])


class Face(ship.Face):
    """ship.Face with the face's world normal worked out properly when a part is scaled (the inverse transpose of its matrix)."""

    def __init__(self, scene, M, fi):
        super().__init__(scene, M, fi)
        R = self.R
        try:
            nw = np.linalg.inv(R).T @ self.n
            self.nw = nw / max(float(np.linalg.norm(nw)), 1e-12)
        except np.linalg.LinAlgError:
            pass


# ================================================================== the atlas

def face_rects_of(c):
    """{face: (x, y, w, h)} in declared texels, for the faces [c] draws, relative to its uv origin."""
    w, h, d = c.size
    r = {'top': (d, 0, w, d), 'bottom': (d + w, 0, w, d), 'right': (0, d, d, h), 'front': (d, d, w, h), 'left': (d + w, d, d, h),
         'back': (2 * d + w, d, w, h)}
    return {k: v for k, v in r.items() if c.shows(k) and v[2] > 0 and v[3] > 0}


def pack_atlas(m, late=()):
    """Place every cube's drawn faces in the atlas: big ones first, each at the first spot (top to bottom, left to right on the declared
    grid) where every one of its faces' rectangles (at HD, from the true fractional sizes) is free, so small cubes drop into the voids
    between big ones. Cubes named in [late] go after all the rest (so adding them moves nothing already placed). Raises if the atlas is too
    small; returns (rows used, share of the atlas used). The running sums of the free texels are kept by `ship.Atlas`."""
    W, H = m.tex_w, m.tex_h
    atlas = ship.Atlas(W, H)
    area = lambda c: sum(r[2] * r[3] for r in face_rects_of(c).values())
    cubes = sorted(m.all_cubes(), key=lambda c: (c.name in late, -area(c), c.name))
    hd = lambda v: int(round(v * HD))
    for c in cubes:
        for v in c.size:
            assert abs(v * HD - round(v * HD)) < 1e-6, f'{c.name}: size {v} is not a whole number of HD texels'
        pieces = [(hd(x), hd(y), hd(w), hd(h)) for x, y, w, h in face_rects_of(c).values()]
        if not pieces:
            c.uv = (0, 0)
            continue
        tw = max(math.ceil((x + w) / HD) for x, y, w, h in pieces)
        th = max(math.ceil((y + h) / HD) for x, y, w, h in pieces)
        if H - th + 1 <= 0 or W - tw + 1 <= 0:
            raise SystemExit(f'{m.name}: {c.name} is bigger than the texture')
        spot = atlas.first_free(pieces, tw, th)
        if spot is None:
            raise SystemExit(f'{m.name}: texture too small for {c.name}')
        atlas.take(*spot, pieces)
        c.uv = spot
    return atlas.used()


def check_atlas(scene, tex_w, tex_h):
    """Every drawn face's rectangle must lie inside the texture and none may overlap another. Returns (problems, texels used)."""
    H, W = tex_h * HD, tex_w * HD
    occ = np.zeros((H, W), np.int32)
    problems = []
    for k in range(len(scene.FC)):
        x, y, w, h = [int(v) for v in scene.FR[k]]
        if x < 0 or y < 0 or x + w > W or y + h > H:
            problems.append(f'{scene.cubes[int(scene.FC[k])][1].name} {scene.FF[k]}: outside the texture')
            continue
        occ[y:y + h, x:x + w] += 1
    over_ = int((occ > 1).sum())
    if over_:
        problems.append(f'{over_} texels belong to more than one face')
    return problems, int((occ > 0).sum())


# ================================================================== painting

def paint_model(scene, mats, glow_layers, tex_w, tex_h, pose=None, only_names=None):
    """The whole texture (H, W, 4) uint8 and the glow layers {name: (H, W) 0..1}, face by face. [mats] maps a cube's material to its painter
    (a function of a Face returning an Out); a texel the painter makes transparent (alpha under half) becomes clear and takes the colour of
    the face's opaque texels, which is what a mip level or a filter blends in at a cut edge."""
    H, W = tex_h * HD, tex_w * HD
    tex = np.zeros((H, W, 4), np.float64)
    layers = {k: np.zeros((H, W)) for k in glow_layers}
    M, _ = scene.mats(pose)
    for fi in range(len(scene.FP)):
        f = Face(scene, M, fi)
        out = mats[f.mat](f)
        rgb = np.clip(out.rgb, 0, 255)
        a = np.clip(out.alpha, 0, 1)
        solid = a > 0.5
        if solid.any() and not solid.all():
            rgb = np.where(solid[..., None], rgb, rgb[solid].mean(axis=0))
        y, x, h, w = f.y0, f.x0, f.h, f.w
        tex[y:y + h, x:x + w, :3] = rgb
        tex[y:y + h, x:x + w, 3] = np.where(solid, 255.0, 0.0)
        for k, v in out.glow.items():
            layers[k][y:y + h, x:x + w] = np.maximum(layers[k][y:y + h, x:x + w], np.where(solid, np.clip(v, 0, 1), 0.0))
    return np.round(tex).astype(np.uint8), layers


def glow_map(layers, glow_layers):
    """The glow map: each layer lit in its own colour (name: (rgb, gain)), additive where they overlap; clear elsewhere."""
    H, W = next(iter(layers.values())).shape
    acc = np.zeros((H, W, 3))
    on = np.zeros((H, W), bool)
    for name, (col, gain) in glow_layers.items():
        v = np.clip(layers[name] * gain, 0, 1)
        acc += v[..., None] * np.array(col, float)
        on |= v > 0.01
    out = np.zeros((H, W, 4), np.uint8)
    out[..., :3] = np.where(on[..., None], np.clip(acc, 0, 255), 0).astype(np.uint8)
    out[..., 3] = np.where(on, 255, 0)
    return out


def turned(f, deg, axis='y'):
    """The face's texel positions (cube frame) turned back by the angle the cube's copy was turned by: its place in the frame of the
    part the slice belongs to. Returns (x, y, z) arrays."""
    P = f.P
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    if not deg:
        return x, y, z
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    if axis == 'y':
        return x * c + z * s, y, -x * s + z * c
    return x * c - y * s, x * s + y * c, z


def edge_dist(f):
    """Distance (model px) from each texel to the nearest edge of its own face."""
    ex = np.minimum(np.arange(f.w) + 0.5, f.w - np.arange(f.w) - 0.5)[None, :] / HD
    ey = np.minimum(np.arange(f.h) + 0.5, f.h - np.arange(f.h) - 0.5)[:, None] / HD
    return np.minimum(ex, ey)


def colour(ramp_name, table, t):
    return ramp(table[ramp_name], t)


def tonemix(a, b, t):
    return mixc(np.asarray(a, float), np.asarray(b, float), t)


def lum(rgb):
    return rgb @ np.array([0.299, 0.587, 0.114])


def blob(x, y, cx, cy, rx, ry=None, soft=0.25):
    """1 inside an ellipse, fading out over [soft] of its size."""
    ry = rx if ry is None else ry
    d = np.hypot((x - cx) / rx, (y - cy) / ry)
    return 1.0 - sstep(1.0 - soft, 1.0, d)


def line_mask(px, py, a, b, width, soft=0.35):
    """1 along the segment a-b ((x, y) pairs) of [width] px, soft at the edges."""
    d, t = seg_dist(px, py, a, b)
    return 1.0 - sstep(width * 0.5 * (1.0 - soft), width * 0.5 * (1.0 + soft), d), t


# ================================================================== the Kotlin body

KT_KEYWORDS = ship.KT_KEYWORDS
KT_BUDGET = ship.KT_BUDGET
kt_float = ship.kt_float
kt_list = ship.kt_list


def face_set_name(faces):
    order = ('top', 'bottom', 'right', 'front', 'left', 'back')
    return 'FACES_' + ''.join(f[0].upper() if f not in ('top', 'back') else {'top': 'T', 'back': 'K'}[f] for f in order if f in faces)


def kt_cost(p):
    """About what a part's addOrReplaceChild call weighs in bytecode."""
    return 80 + 36 * len(p.cubes) + sum(kt_cost(c) for c in p.children)


def kt_call(part_, parent):
    """The Kotlin expression that adds [part_] (its cubes and rest pose, not its children) under the PartDefinition [parent]."""
    f = kt_float
    b = 'CubeListBuilder.create()'
    for c in part_.cubes:
        x, y, z = c.origin
        w, h, d = c.size
        b += f'.texOffs({c.uv[0]}, {c.uv[1]}).addBox({f(x)}, {f(y)}, {f(z)}, {f(w)}, {f(h)}, {f(d)}'
        if c.faces:
            b += f', {face_set_name(c.faces)}'
        b += ')'
    px, py, pz = part_.pivot
    rx, ry, rz = [math.radians(a) for a in part_.rot]
    pose_ = (f'PartPose.offsetAndRotation({f(px)}, {f(py)}, {f(pz)}, {f(rx)}, {f(ry)}, {f(rz)})' if any(part_.rot)
             else f'PartPose.offset({f(px)}, {f(py)}, {f(pz)})')
    return f'{parent}.addOrReplaceChild("{part_.name}", {b}, {pose_})'


def kt_emit(part_, parent, out, used):
    """Appends the Kotlin that adds [part_] (and, nested, its children) to [parent]; [used] collects the face sets the cubes draw."""
    assert part_.name.isidentifier() and part_.name not in KT_KEYWORDS and part_.name not in ('p', 'root', 'mesh'), part_.name
    for c in part_.cubes:
        if c.faces:
            used[face_set_name(c.faces)] = c.faces
    var = part_.name if part_.children else None
    call = kt_call(part_, parent)
    out.append('            ' + (f'val {var} = {call}' if var else call))
    for ch in part_.children:
        kt_emit(ch, var, out, used)


def kt_split(parts_, budget):
    """The parts cut into runs of about [budget] bytecode each (a part heavier than that is a run of its own)."""
    runs, cur, cost = [], [], 0
    for p in parts_:
        c = kt_cost(p)
        if cur and cost + c > budget:
            runs.append(cur)
            cur, cost = [], 0
        cur.append(p)
        cost += c
    if cur:
        runs.append(cur)
    return runs


def kt_methods(parts_, label, methods, used, counter):
    """Writes [parts_] as helper methods `fun buildX(p: PartDefinition)` (each adds a run of the parts under `p`; a part too heavy for one
    method is added by one and its children, in runs, by methods that take it) into [methods] [(name, lines)]; returns the method names."""
    names = []
    for run in kt_split(parts_, KT_BUDGET):
        counter[0] += 1
        name = f'build{label[:1].upper() + label[1:]}{counter[0]}'
        lines = []
        for part_ in run:
            if kt_cost(part_) <= KT_BUDGET:
                kt_emit(part_, 'p', lines, used)
            else:
                assert part_.name.isidentifier() and part_.name not in KT_KEYWORDS and part_.name not in ('p', 'root', 'mesh'), part_.name
                for c in part_.cubes:
                    if c.faces:
                        used[face_set_name(c.faces)] = c.faces
                lines.append(f'            val {part_.name} = {kt_call(part_, "p")}')
                for m in kt_methods(part_.children, label, methods, used, counter):
                    lines.append(f'            {m}({part_.name})')
        methods.append((name, lines))
        names.append(name)
    return names


def kt_chunks(groups, used):
    """[(method name, body lines)] for every group of top-level parts (cut into helper methods that stay far under the JVM's size limit,
    whatever depth the parts nest to), in the order they have to be called from the top: the call is `name(root)` for the first of a
    group's methods (the callee-first ones that only build subtrees are called from inside); returns (methods, calls)."""
    methods, calls, counter = [], [], [0]
    for label, parts_ in groups:
        calls += kt_methods(parts_, label, methods, used, counter)
    return methods, calls


def kt_face_constants(used):
    lines = []
    for nm, faces in sorted(used.items()):
        dirs = ', '.join(f'Direction.{DIRECTIONS[f]}' for f in ('top', 'bottom', 'right', 'front', 'left', 'back') if f in faces)
        lines.append(f'        private val {nm}: Set<Direction> = EnumSet.of({dirs})')
    return '\n'.join(lines)


def kt_mesh(groups, tex_w, tex_h):
    """(calls, helper methods, face constants) of a model's `createBodyLayer`: the calls go in its body, the rest beside it."""
    used = {}
    methods, calls = kt_chunks(groups, used)
    callsrc = '\n'.join(f'            {name}(root)' for name in calls)
    body = ''
    for name, lines in methods:
        body += '\n        @Suppress("LocalVariableName", "UNUSED_VARIABLE")\n'
        body += f'        private fun {name}(p: PartDefinition) {{\n' + '\n'.join(lines) + '\n        }\n'
    return callsrc, body, kt_face_constants(used)


def deterministic_uuids(text, key):
    import uuid
    seen = {}

    def sub(m):
        u = m.group(0)
        if u not in seen:
            seen[u] = str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/{key}/{len(seen)}'))
        return seen[u]
    return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', sub, text)


def write_bbmodel(model, img, name, path):
    """The model as a Blockbench file (Modded Entity), its texture embedded at HD."""
    bb = model.bbmodel(img, name)
    bb['textures'][0]['width'] = model.tex_w * HD
    bb['textures'][0]['height'] = model.tex_h * HD
    with open(path, 'w') as f:
        f.write(deterministic_uuids(json.dumps(bb), name))


# ================================================================== extents

def extent_of(scene, pose=None, root=None):
    """(lo, hi) over every corner of every drawn face of the posed model, in px."""
    idx, A, B, D, n, ok = scene.quads(pose, root)
    if not len(idx):
        return np.zeros(3), np.zeros(3)
    C = B + D - A
    pts = np.stack([A, B, D, C], 1).reshape(-1, 3)
    return pts.min(0), pts.max(0)


# ================================================================== the stage: models in one scene, over the cog's sea and sky

Layer = collections.namedtuple('Layer', 'img depth ybuf hit gbuf')


class Actor:
    """A model in a scene: [scene], its painted [tex] and [glow], the [pose] it is in ({part: {pos, rot, scale, visible}}), [root] (a 4x4 that
    puts the model in the scene's own space: the cog's model space, px, y down), how brightly its glow layer burns, and [only] (cube
    indices) to draw a part of it."""

    def __init__(self, scene, tex, glow, pose=None, root=None, glow_gain=0.0, only=None, alpha_cut=60):
        self.scene, self.tex, self.glow, self.pose, self.root = scene, tex, glow, pose, root
        self.glow_gain, self.only, self.alpha_cut = glow_gain, only, alpha_cut


def place(x=0.0, y=0.0, z=0.0, yaw=0.0, pitch=0.0, roll=0.0, scale=1.0):
    """A 4x4 putting a model at (x, y, z) px of the scene, as a plane is flown: rolled [roll] degrees about its own z (the way it faces, for
    a creature that faces -z), then pitched [pitch] degrees nose up, then turned [yaw] degrees about the vertical (see `facing` for the yaw
    that makes its front face a direction)."""
    R = ship.rotm_y(yaw) @ rotm_rad(-math.radians(pitch), 0.0, 0.0) @ rotm_rad(0.0, 0.0, math.radians(roll))
    M = np.eye(4)
    M[:3, :3] = R * scale
    M[:3, 3] = (x, y, z)
    return M


def facing(dx, dz):
    """The yaw (degrees) that turns a model's front (-z) toward the horizontal direction (dx, dz) in the scene."""
    return math.degrees(math.atan2(-dx, -dz))


def render_layer(actor, cam, light):
    img, depth, ybuf, hit, gbuf = ship.raster(actor.scene, actor.tex, actor.glow, actor.pose, cam, light, glow_gain=actor.glow_gain,
                                              root=actor.root, only=actor.only, alpha_cut=actor.alpha_cut)
    return Layer(img, depth, ybuf, hit, gbuf)


def merge(layers):
    """The nearest of [layers] at every pixel: one Layer."""
    out = layers[0]
    img, depth, ybuf, hit, gbuf = out.img.copy(), out.depth.copy(), out.ybuf.copy(), out.hit.copy(), out.gbuf.copy()
    for L in layers[1:]:
        take = L.hit & (L.depth < depth)
        img = np.where(take[..., None], L.img, img)
        gbuf = np.where(take[..., None], L.gbuf, gbuf)
        depth = np.where(take, L.depth, depth)
        ybuf = np.where(take, L.ybuf, ybuf)
        hit = hit | take
    return Layer(img, depth, ybuf, hit, gbuf)


def _window(cam, shape, pts, pad):
    """The rows and columns of a picture of [shape] that the sea points [pts] ((x, z) pairs) can reach when each spreads [pad] model px: the
    window the work on them is done in (None if it lies off the picture)."""
    H, W = shape
    q = np.array([cam.project(np.array([px, 0.0, pz])) for px, pz in pts])
    m = pad * cam.scale
    x0, x1 = int(max(0, math.floor(q[:, 0].min() - m))), int(min(W, math.ceil(q[:, 0].max() + m) + 1))
    y0, y1 = int(max(0, math.floor(q[:, 1].min() - m))), int(min(H, math.ceil(q[:, 1].max() + m) + 1))
    return None if (x0 >= x1 or y0 >= y1) else (slice(y0, y1), slice(x0, x1))


def water_rings(x, z, t, spots, sea_mask, cam=None):
    """Foam and ripples on the sea round the places things come up through it: [spots] are (x, z, radius px, strength) with an optional fifth
    number, how much of the spreading rings there are (1 by default, 0 for foam alone), (x, z) in scene px. A ring of foam hugs each spot, and
    pale rings spread from it and fade. Only the pixels near a spot are worked on (a window of the picture if the [cam] is given)."""
    out = np.zeros(x.shape)
    for spot in spots:
        sx, sz, r, k = spot[:4]
        rings_k = spot[4] if len(spot) > 4 else 1.0
        reach = r + 3.5 * (2.4 * r + 14.0) + 6.0
        if cam is not None:
            win = _window(cam, x.shape, [(sx, sz)], reach)
            if win is None:
                continue
            xs, zs = x[win], z[win]
        else:
            win = (np.abs(x - sx) < reach) & (np.abs(z - sz) < reach)
            if not win.any():
                continue
            xs, zs = x[win], z[win]
        d = np.hypot(xs - sx, zs - sz)
        noise = fbm2(xs + t * 0.2, zs, 2.2, 2.2, 31, 2)
        inner = 0.3 + 0.7 * sstep(0.3 * r, 0.85 * r, d)                       # the water inside a ring is not bare: a thin foam lies on it
        foam = np.exp(-np.maximum(d - r, 0.0) / (0.14 * r + 0.8)) * inner * (0.45 + 0.55 * noise)
        ph = (d - r) / (0.9 * r + 4.0) - t * 0.045
        wave = (0.5 + 0.5 * np.sin(ph * 6.2832)) ** 6 * np.exp(-np.maximum(d - r, 0.0) / (2.4 * r + 14.0)) * (d > r)
        out[win] += k * (foam * 0.9 + wave * 0.38 * rings_k)
    return np.clip(out, 0.0, 1.0) * sea_mask


def foam_streaks(x, z, t, streaks, sea_mask, cam):
    """Streaks of foam on the sea (a wake is made of them): each is (x0, z0, x1, z1, half width at the start, at the end, strength at the start,
    at the end) in scene px; the foam hugs the line between the two points. Returns 0..1 over the picture."""
    out = np.zeros(x.shape)
    for x0, z0, x1, z1, w0, w1, k0, k1 in streaks:
        wm = max(w0, w1)
        win = _window(cam, x.shape, [(x0, z0), (x1, z1)], wm * 2.6 + 3.0)
        if win is None:
            continue
        xs, zs = x[win], z[win]
        d, u = seg_dist(xs, zs, (x0, z0), (x1, z1))
        w = w0 + (w1 - w0) * u
        k = k0 + (k1 - k0) * u
        noise = fbm2(xs + t * 0.3, zs, 2.2, 2.2, 31, 2)
        f = k * np.exp(-np.maximum(d - w, 0.0) / (0.5 * w + 0.7)) * (0.45 + 0.55 * noise)
        out[win] = np.maximum(out[win], f)
    return np.clip(out, 0.0, 1.0) * sea_mask


def ink_stains(x, z, blobs, sea_mask, cam):
    """Ink on the sea: [blobs] are (x, z, radius px, strength) in scene px, each a soft dark stain with a ragged, billowing edge (the stains of one cloud
    overlap into a single patch). Returns 0..1 over the picture."""
    out = np.zeros(x.shape)
    for bx, bz, r, k in blobs:
        if k <= 0.004 or r <= 0.1:
            continue
        win = _window(cam, x.shape, [(bx, bz)], r * 1.6 + 2.0)
        if win is None:
            continue
        xs, zs = x[win], z[win]
        d = np.hypot(xs - bx, zs - bz) / r
        n = fbm2(xs, zs, 5.5, 5.5, 57, 3)
        edge = 1.0 - np.clip((d - 0.5 - 0.6 * (n - 0.5)) / 0.55, 0.0, 1.0)
        out[win] = np.maximum(out[win], k * edge ** 1.3)
    return np.clip(out, 0.0, 1.0) * sea_mask


def sea_of(cam, sky, size, ss=2, sea_t=0.0, plain=False):
    """The sea and sky as the camera sees them (at [ss] times [size]): (picture, sea x, sea z, sea mask). Cache it: it is the slow part of a frame."""
    return ship.sea_sky(cam, ship.SKIES[sky], sea_t, size[0] * ss, size[1] * ss, plain=plain)


def compose(layer, cam, size, sky, ss=2, sea_t=0.0, bloom=1.0, spots=(), seas=None, glow_on=True, key=None, plain=False, murk=1.0, sea=None, ring_t=None,
            streaks=(), inks=()):
    """The finished picture: the scene's layer over the sea and sky of the cog's previews. Parts below the waterline (y > 0) are seen
    through the water, tinted and darker the deeper they are; foam rings the places things break the surface. [sea] is `sea_of`'s answer if
    the caller has it already (else it is made, and kept in [seas] under [key] if both are given); [ring_t] is the time the foam rings run on (the
    sea itself stays at [sea_t], so a frozen sea can have rings that spread); [streaks] are lines of foam (see `foam_streaks`). Returns a PIL image."""
    sk = ship.SKIES[sky]
    W, H = size
    if sea is None:
        sea = seas.get(key) if (seas is not None and key is not None) else None
    if sea is None:
        sea = sea_of(cam, sky, size, ss, sea_t, plain)
        if seas is not None and key is not None:
            seas[key] = sea
    bg, x, z, mask = sea
    if len(spots) or len(streaks):
        tr = sea_t if ring_t is None else ring_t
        rings = water_rings(x, z, tr, spots, mask, cam) if len(spots) else np.zeros(x.shape)
        if len(streaks):
            rings = np.maximum(rings, foam_streaks(x, z, tr, streaks, mask, cam))
        amb = float(np.array(sk['amb']).mean())
        bg = mixc(bg, np.array([206, 214, 220], float) * (0.55 + 0.45 * amb * 1.5), np.clip(rings, 0, 1) * 0.6)
    if len(inks):
        dark = ink_stains(x, z, inks, mask, cam)
        bg = mixc(bg, np.array([18.0, 10.0, 28.0]) * (0.6 + 0.4 * float(np.array(sk['amb']).mean()) * 2.0), np.clip(dark, 0, 1) * 0.9)
    out = np.where(layer.hit[..., None], layer.img, bg)
    under = layer.hit & (layer.ybuf > 0.0)
    depth_k = np.clip(layer.ybuf / (36.0 * murk), 0.0, 1.0)
    tint = np.array([0.62, 0.88, 0.92])
    sea_c = np.array(sk['sea2'], float)
    under_c = out * (tint[None, None, :] * (0.7 - 0.4 * depth_k[..., None])) + sea_c[None, None, :] * (0.14 + 0.34 * depth_k[..., None])
    out = np.where(under[..., None], under_c, out)
    wl = layer.hit & (np.abs(layer.ybuf) < 0.9)
    out = mixc(out, np.array([200, 208, 214], float) * 0.7, wl * 0.30)
    im = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).resize(size, Image.LANCZOS)
    if glow_on and bloom > 0 and float(layer.gbuf.max()) > 1.0:
        g = Image.fromarray(np.clip(layer.gbuf * np.where(under[..., None], 0.55, 1.0), 0, 255).astype(np.uint8)).resize(size, Image.LANCZOS)
        scale = float(cam.scale) / ss
        halo = np.asarray(g.filter(ImageFilter.GaussianBlur(radius=float(max(2.5, scale * 2.4)))), float) * (1.5 * bloom)
        halo2 = np.asarray(g.filter(ImageFilter.GaussianBlur(radius=float(max(7.0, scale * 7.5)))), float) * (1.3 * bloom)
        im = Image.fromarray(np.clip(np.asarray(im, float) + halo + halo2, 0, 255).astype(np.uint8))
    return im


# ================================================================== running the generated Kotlin against the real classes

KCHECK_LIBS = '/tmp/kcheck/libs'
KOTLINC = '/tmp/kcheck/kotlinc/bin/kotlinc'
EXTRA_LIBS = ['/tmp/claude-0/mc_offline_libs', '/opt/gradle-8.14.3/lib/commons-lang3-3.14.0.jar']


def kotlin_classpath():
    jars = sorted(os.path.join(KCHECK_LIBS, f) for f in os.listdir(KCHECK_LIBS) if f.endswith('.jar'))
    for e in EXTRA_LIBS:
        if os.path.isdir(e):
            jars += sorted(os.path.join(e, f) for f in os.listdir(e) if f.endswith('.jar'))
        elif os.path.exists(e):
            jars.append(e)
    return jars


def run_kotlin(sources, work, main_class, stdin_text, jvm='java', timeout=900):
    """Compiles [sources] (paths) into [work] with the kit's Kotlin compiler against Minecraft's own jars and runs [main_class] with
    [stdin_text] on its input; returns (stdout, stderr). Needs Java 25 (Minecraft 26's classes) and the kit under /tmp/kcheck."""
    os.makedirs(work, exist_ok=True)
    cp = ':'.join(kotlin_classpath())
    out = os.path.join(work, 'out')
    shutil.rmtree(out, ignore_errors=True)
    env = dict(os.environ, JAVA_OPTS='-Xmx4g -Xss4m')
    r = subprocess.run([KOTLINC, '-jvm-target', '21', '-nowarn', '-cp', cp, '-d', out] + list(sources), capture_output=True, text=True, env=env, timeout=timeout)
    errs = [ln for ln in (r.stdout + r.stderr).splitlines() if 'error:' in ln]
    if errs:
        raise SystemExit('Kotlin did not compile:\n' + '\n'.join(errs[:30]))
    stdlib = '/tmp/kcheck/kotlinc/lib/kotlin-stdlib.jar'
    r = subprocess.run([jvm, '-cp', ':'.join([out, cp, stdlib]), main_class], input=stdin_text, capture_output=True, text=True, timeout=timeout)
    if r.returncode:
        raise SystemExit('Kotlin harness failed:\n' + r.stderr[-3000:])
    return r.stdout, r.stderr


KT_HARNESS = r"""import java.io.BufferedReader
import java.io.InputStreamReader
import @@PKG@@.@@MODEL@@
import @@PKG@@.@@STATE@@

/** Sets one field of a render state from its text: a float, an int, a boolean, or a list of floats copied into a FloatArray it holds. */
fun setField(state: Any, key: String, value: String) {
    val cap = key.substring(0, 1).uppercase() + key.substring(1)
    for (m in state.javaClass.methods) {
        if (m.name == "set" + cap && m.parameterCount == 1) {
            when (m.parameterTypes[0]) {
                java.lang.Float.TYPE -> m.invoke(state, value.toFloat())
                Integer.TYPE -> m.invoke(state, value.toInt())
                java.lang.Boolean.TYPE -> m.invoke(state, value.toBoolean())
                else -> error("unsupported field type " + m.parameterTypes[0])
            }
            return
        }
    }
    val arr = state.javaClass.getMethod("get" + cap).invoke(state) as FloatArray
    val vals = value.split(",")
    for (i in vals.indices) arr[i] = vals[i].toFloat()
}

fun main() {
    val root = @@MODEL@@.createBodyLayer().bakeRoot()
    val model = @@MODEL@@(root)
    val find = root.createPartLookup()
    val reader = BufferedReader(InputStreamReader(System.`in`))
    var names = listOf<String>()
    val out = StringBuilder()
    var state: @@STATE@@? = null
    while (true) {
        val line = reader.readLine() ?: break
        if (line.startsWith("PARTS ")) {
            names = line.substring(6).split(" ")
        } else if (line == "CASE") {
            state = @@STATE@@()
        } else if (line.startsWith("SET ")) {
            val p = line.split(" ")
            setField(state!!, p[1], p[2])
        } else if (line == "END") {
            model.setupAnim(state!!)
            out.append("CASE\n")
            for (n in names) {
                val p = find.apply(n)!!
                out.append(n).append(' ').append(p.x).append(' ').append(p.y).append(' ').append(p.z).append(' ')
                    .append(p.xRot).append(' ').append(p.yRot).append(' ').append(p.zRot).append(' ')
                    .append(p.xScale).append(' ').append(p.yScale).append(' ').append(p.zScale).append(' ')
                    .append(if (p.visible) 1 else 0).append('\n')
            }
        }
    }
    print(out)
}
"""


def verify_pose(sources, pkg, model_class, state_class, scene, cases, make_state, pose_fn, work, tol_pos=0.02, tol_mat=3e-3, tol_scale=3e-3):
    """Runs the generated Kotlin model's `setupAnim` (compiled against Minecraft's own ModelPart classes) on every case (a dict of render-state
    fields) and compares every part's position, turn, scale and visibility with what the Python `pose_fn(make_state(case))` gives over the
    rest pose. Returns (worst position error px, worst error in the turn's matrix, worst scale error, number of parts compared, problems)."""
    os.makedirs(work, exist_ok=True)
    harness = os.path.join(work, 'Harness.kt')
    with open(harness, 'w') as f:
        f.write(KT_HARNESS.replace('@@PKG@@', pkg).replace('@@MODEL@@', model_class).replace('@@STATE@@', state_class))
    names = [p.name for p in scene.parts]
    text = 'PARTS ' + ' '.join(names) + '\n'
    for c in cases:
        text += 'CASE\n'
        for k, v in c.items():
            if isinstance(v, bool):
                v = 'true' if v else 'false'
            elif isinstance(v, (list, tuple, np.ndarray)):
                v = ','.join(repr(float(x)) for x in v)
            text += f'SET {k} {v}\n'
        text += 'END\n'
    out, _err = run_kotlin(list(sources) + [harness], work, 'HarnessKt', text)
    blocks = out.split('CASE\n')[1:]
    assert len(blocks) == len(cases), f'{len(blocks)} answers for {len(cases)} cases'
    worst_p = worst_m = worst_s = 0.0
    problems = []
    n = 0
    for ci, (c, blk) in enumerate(zip(cases, blocks)):
        want = pose_fn(make_state(c))
        lines = blk.strip().split('\n')
        assert len(lines) == len(names)
        for pi, ln in enumerate(lines):
            parts_ = ln.split()
            name = parts_[0]
            kv = [float(v) for v in parts_[1:]]
            w = want.get(name, {})
            pos = np.array(w.get('pos', scene.rest_pos[pi]), float)
            rot = np.array(w.get('rot', scene.rest_rot[pi]), float)
            scl = np.array(w.get('scale', (1.0, 1.0, 1.0)), float)
            vis = bool(w.get('visible', True))
            ep = float(np.abs(np.array(kv[0:3]) - pos).max())
            em = float(np.abs(rotm_rad(*kv[3:6]) - rotm_rad(*rot)).max())
            es = float(np.abs(np.array(kv[6:9]) - scl).max())
            worst_p, worst_m, worst_s = max(worst_p, ep), max(worst_m, em), max(worst_s, es)
            n += 1
            if ep > tol_pos or em > tol_mat or es > tol_scale or bool(kv[9]) != vis:
                problems.append(f'case {ci} {name}: position {ep:.4f}, turn {em:.5f}, scale {es:.5f}, visible {bool(kv[9])} (python {vis})')
    return worst_p, worst_m, worst_s, n, problems


REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
KT_SRC = os.path.join(REPO, 'src', 'main', 'kotlin', 'io', 'github', 'vaspyyy', 'guildmark')


def scratch_tree(work, generated, repo_files=()):
    """A throwaway source tree under [work] for compiling the generated models alone: the stub Guildmark object, the repo files named in
    [repo_files] (paths under src/main/kotlin/io/github/vaspyyy/guildmark) copied as they are and the [generated] ({relative path: text}).
    Returns the list of source paths."""
    src = os.path.join(work, 'src')
    shutil.rmtree(src, ignore_errors=True)
    out = []

    def put(rel, text):
        p = os.path.join(src, 'io', 'github', 'vaspyyy', 'guildmark', rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w') as f:
            f.write(text)
        out.append(p)
    put('Guildmark.kt', 'package io.github.vaspyyy.guildmark\n\nobject Guildmark {\n    const val MOD_ID = "guildmark"\n}\n')
    for rel in repo_files:
        with open(os.path.join(KT_SRC, rel)) as f:
            put(rel, f.read())
    for rel, text in generated.items():
        put(rel, text)
    return out


# ================================================================== reports: the parts and their extents

def group_parts(model, notes):
    """[(label, what, [part names])] in the order of [notes] ((regex, label, what) triples; a part goes under the first one it matches, the rest under 'other')."""
    import re as _re
    groups = [(label, what, []) for _rx, label, what in notes]
    other = []
    for p in model.all_parts():
        for gi, (rx, _l, _w) in enumerate(notes):
            if _re.match(rx, p.name):
                groups[gi][2].append(p.name)
                break
        else:
            other.append(p.name)
    if other:
        groups.append(('other', '', other))
    return [g for g in groups if g[2]]


def span_text(names):
    """A short way to write a list of part names: the first, the last and how many."""
    return names[0] if len(names) == 1 else f'{names[0]} .. {names[-1]} ({len(names)})'


def extent_lines(scene, poses, root=None, unit_blocks=True):
    """Lines giving the extents (px, and blocks) of the posed model for each of [poses] ({label: pose}) and the union over all of them."""
    lo_all = np.full(3, 1e9)
    hi_all = np.full(3, -1e9)
    lines = []
    for label, pose in poses.items():
        lo, hi = extent_of(scene, pose, root)
        lo_all = np.minimum(lo_all, lo)
        hi_all = np.maximum(hi_all, hi)
        lines.append(f'  {label:<22} x {lo[0]:8.1f} .. {hi[0]:7.1f}   y {lo[1]:8.1f} .. {hi[1]:7.1f}   z {lo[2]:8.1f} .. {hi[2]:7.1f}  px')
    lines.append(f'  {"union":<22} x {lo_all[0]:8.1f} .. {hi_all[0]:7.1f}   y {lo_all[1]:8.1f} .. {hi_all[1]:7.1f}   z {lo_all[2]:8.1f} .. {hi_all[2]:7.1f}  px')
    if unit_blocks:
        b = lambda v: f'{v / 16.0:.2f}'
        lines.append(f'  {"union in blocks":<22} x {b(lo_all[0])} .. {b(hi_all[0])}   y {b(lo_all[1])} .. {b(hi_all[1])}   z {b(lo_all[2])} .. {b(hi_all[2])}   (model space: y is down, the creature faces -z)')
    return lines, lo_all, hi_all


def report_text(title, model, scene, notes, poses, header=(), root=None):
    """The parts report of one model: what it is made of, what animates each group, and its extents over [poses]."""
    out = [title, '=' * len(title), '']
    out += list(header)
    parts = list(model.all_parts())
    cubes = list(model.all_cubes())
    out.append(f'{len(parts)} parts, {len(cubes)} cubes, texture {model.tex_w}x{model.tex_h} (HD: the PNGs are {model.tex_w * HD}x{model.tex_h * HD})')
    out.append('')
    out.append('Parts, and what animates them')
    for label, what, names in group_parts(model, notes):
        out.append(f'  {label}: {span_text(names)}')
        if what:
            out.append(f'      {what}')
    out.append('')
    out.append('Extents of the drawn model over a set of poses (every corner of every drawn face)')
    lines, _lo, _hi = extent_lines(scene, poses, root)
    out += lines
    return '\n'.join(out) + '\n'
