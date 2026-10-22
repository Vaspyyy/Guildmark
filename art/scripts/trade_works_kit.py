"""What store_art.py and house_art.py share: the Carriers' Store's and the Counting House's fittings are painted, modelled, checked and
previewed here in one way, the way the town works' are (works_art.py, whose materials, painters and mesh packing this takes as they are).

Every fitting is a block of the trade, authored FACING NORTH (its front toward -z, the wall it hangs on or the back of it at +z), in model
space as the works' are (px, 16 to a block, x east, y up, z south), turned by its blockstate's `facing`. It is two things:
  - a block model (JSON, `models/block/<id>.json`, textures `textures/block/trade/*.png` and the works' own materials
    `textures/block/works/{oak,oak_h,oak_end,pine,iron,bronze,straw}.png`): what stands there with no renderer;
  - a mesh (a `LayerDefinition` in the GENERATED table, its texture `textures/block/trade/<name>.png` painted at twice the declared
    size, two texels to a pixel as the HD blocks have it): the pieces that move, each a named part whose own origin is its pivot, which the
    renderer draws at the place the table gives, turned about that pivot by the number the table's functions give.

PARTS ARE AUTHORED IN MODEL SPACE HERE (`mbox`, `mpart`: x east, y up, z south, from the part's own origin) and converted to the mesh's
(ModelPart space: y down, x mirrored: a renderer turns model space into it with `poseStack.scale(-1f, -1f, 1f)` after it has translated to the
part's place and turned it, exactly as WorksHoistRenderer does). A turn about z is the same in both; about x or y it is the opposite, which
`mrot` takes care of. In a part's faces, 'front' is north (-z), 'back' south, 'top' up, 'bottom' down, 'right' east (+x) and 'left' west.
"""
import json
import math
import os
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import works_art as W  # noqa: E402
import townsfolk_tools as tt  # noqa: E402
import handcart as hc  # noqa: E402
import island_blockview as BV  # noqa: E402
from hdkit import (PAL, GEMS, Canvas, grid, vnoise, fbm, rand, bands, poly, seg_dist, disc, ellipse, rect, blur,  # noqa: E402,F401
                   shift, grow, edge, dist_in, light, cavity, outline, glint, bevel, tone, mix, ramp)
from haul_board_icons import Pic  # noqa: E402,F401
from modelkit import Cube, Part, Model  # noqa: E402,F401

T = W.T
ALL = W.ALL
TEX_NS = 'guildmark:block/trade/'
WORKS_NS = 'guildmark:block/works/'
SIX = W.SIX
SIDES = W.SIDES

# ================================================================== the six colours of a trader's house
# The booker's six (haul_board_icons.SEAL_COLOURS), the colours a pennant is dyed in (trade_items.DYES: the cart's), in this order. The
# game keeps a colour as an RGB int; `rgb` is the dye's own, `ramp` the painted tones (dark to light) the pictures use, `mark` the die the
# same colour's wax seal is pressed with.
COLOURS = [
    ('red', (196, 52, 48), PAL['wax'], 'wheel'),
    ('blue', (56, 100, 190), GEMS['blue'], 'sheaf'),
    ('green', (62, 150, 76), GEMS['green'], 'bell'),
    ('amber', (214, 150, 40), GEMS['amber'], 'key'),
    ('violet', (132, 72, 190), GEMS['violet'], 'scales'),
    ('teal', (44, 150, 146), [(8, 30, 34), (16, 62, 68), (28, 104, 108), (60, 152, 148), (150, 214, 206)], 'star'),
]
NAMES = [c[0] for c in COLOURS]


def rgb_int(rgb):
    return (int(rgb[0]) << 16) | (int(rgb[1]) << 8) | int(rgb[2])


# ================================================================== materials (tiles the meshes' painters cut their faces from)
BRASS = PAL['brass']
DARKOAK = PAL['darkwood']
BONE = PAL['bone']
INKS = [(18, 12, 14), (34, 24, 24), (52, 38, 34)]
COIN = [(46, 30, 12), (110, 76, 24), (170, 124, 40), (222, 176, 66), (250, 226, 142)]
PARCH = PAL['parchment']
CREAM = W.CREAM
LEATHERS = PAL['leather']
CORD = [(34, 18, 20), (74, 30, 34), (116, 44, 46), (156, 62, 58), (196, 96, 82)]          # a crimson cord
TWINE = [(70, 54, 34), (122, 98, 62), (170, 142, 92), (208, 182, 128), (236, 214, 160)]


def brass_tile(seed=4001):
    """Cast brass, worn warm and bright along its edges' wear, tarnished a little darker in its hollows, fine scratches across it."""
    n = fbm(T, T, 6, seed)
    xs, ys = grid(T)
    t = 0.62 + 0.2 * (n - 0.5) + 0.05 * (vnoise(T, T, 2, seed + 1) - 0.5)
    t += 0.05 * np.sin((xs * 0.7 + ys * 0.3) * 1.3 + fbm(T, T, 9, seed + 2) * 5.0)
    cv = Canvas(T)
    cv.put(ALL, bands(BRASS, t, 0.3))
    scr = (rand(T, T, seed + 3) > 0.965) & (np.abs(np.sin(xs * 0.9 + ys * 0.5)) > 0.6)
    cv.over(scr, BRASS[5], 0.5)
    cv.over(cavity(n * 1.2, 2) > 0.05, BRASS[1], 0.5)
    return cv


def darkoak_tile(seed=4101, vertical=True, checks=1):
    return W.wood(DARKOAK, seed, vertical=vertical, checks=checks, knots=1, lift=0.06)


def darkoak_end(seed=4103):
    return W.end_grain(DARKOAK, seed, cx=15.0, cy=17.0)


def parchment_tile(seed=4201):
    """Old vellum: warm cream, mottled, a few spots of foxing, its fibres faint."""
    xs, ys = grid(T)
    t = 0.66 + 0.14 * (fbm(T, T, 7, seed) - 0.5) + 0.05 * (vnoise(T, T, 2, seed + 1) - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(PARCH, t, 0.3))
    fox = (fbm(T, T, 3, seed + 2) > 0.7) & (rand(T, T, seed + 3) > 0.35)
    cv.over(fox, PARCH[1], 0.4)
    return cv


def leather_tile(seed=4301):
    xs, ys = grid(T)
    t = 0.5 + 0.2 * (fbm(T, T, 4, seed) - 0.5) + 0.1 * (rand(T, T, seed + 1) - 0.5)
    cv = Canvas(T)
    cv.put(ALL, bands(LEATHERS, t, 0.3))
    return cv


def tin_tile(seed=4401):
    """Pewter and tin, dull grey with a cold shine."""
    n = fbm(T, T, 5, seed)
    cv = Canvas(T)
    cv.put(ALL, bands(PAL['steel'], 0.5 + 0.25 * (n - 0.5) + 0.06 * (vnoise(T, T, 2, seed + 1) - 0.5), 0.3))
    return cv


W.TILE_PAINTERS.update({
    'brass': brass_tile,
    'dark_h': lambda: darkoak_tile(4101, vertical=False),
    'dark_v': lambda: darkoak_tile(4103, vertical=True),
    'dark_end': darkoak_end,
    'parch': parchment_tile,
    'leather': leather_tile,
    'tin': tin_tile,
    'sackcloth': lambda: W.canvas_tex(7031, seams=False),
    'oak_end': lambda: W.end_grain(W.OAK, 7033),
})
tile = W.tile
tiled = W.tiled


# ================================================================== a part authored in model space
def mrot(rx=0.0, ry=0.0, rz=0.0):
    """A turn given in model space (degrees about x, y and z, right-handed in model space) as a part's own `rot` (ModelPart space)."""
    return (-rx, -ry, rz)


def mbox(name, x0, x1, y0, y1, z0, z1, paint, faces=None):
    """A cube from model-space bounds (relative to its part's origin: x east, y up, z south), painted by [paint] on [faces] (all if None)."""
    return W.C((-x1, -y1, z0), (x1 - x0, y1 - y0, z1 - z0), name, paint, faces)


def mpart(name, at=(0.0, 0.0, 0.0), rot=(0.0, 0.0, 0.0), cubes=(), children=()):
    """A part at [at] from its parent's origin (model space) turned [rot] (model-space degrees about x, y and z)."""
    x, y, z = at
    return Part(name, (-x, -y, z), mrot(*rot), cubes=list(cubes), children=list(children))


def strut(name, p0, p1, w, paint, d=None, faces=None):
    """A bar [w] x [d] (square if None) from model-space [p0] to [p1], as a part standing at p0 and turned to reach p1: its box hangs
    down from its origin (x and z across it, y from -length to 0) and is turned by the shortest two turns (about x, then z) that
    take down onto the line. A chain's link, a rod, a strap."""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    v = p1 - p0
    L = float(np.linalg.norm(v))
    dx, dy, dz = v / L
    a = math.degrees(math.asin(max(-1.0, min(1.0, -dz))))
    th = math.degrees(math.atan2(dx, -dy))
    d = w if d is None else d
    return mpart(name, tuple(p0), (a, 0.0, th), [mbox(name, -w / 2, w / 2, -L, 0.0, -d / 2, d / 2, paint, faces)])


def octa_disc(name, r, y0, y1, paint, top=None, bottom=None, at=(0.0, 0.0, 0.0)):
    """A filled octagon round the origin (apothem [r]) from y0 to y1: a square and, in a child turned 45 degrees about up, a second (a
    hair lower so the two tops never fight). Returns (cubes, child parts) to put in a part's cubes and children."""
    cx, cz = at[0], at[2]
    sq = mbox(name, cx - r, cx + r, y0, y1, cz - r, cz + r, paint)
    sq2 = mbox(name + '_d', -r, r, y0, y1 - 0.02, -r, r, paint)
    return [sq], [mpart(name + '_d', (cx, 0.0, cz), (0.0, 45.0, 0.0), [sq2])]


def octa_ring(name, r, thick, y0, y1, paint, at=(0.0, 0.0, 0.0), faces=None):
    """An octagonal wall round the origin: four slabs (the outer faces at apothem [r], [thick] thick) and, in a child turned 45
    degrees, four more. Returns (cubes, child parts)."""
    cx, cz = at[0], at[2]
    s = 2.0 * r * math.tan(math.pi / 8)

    def slabs(tag, ox, oz):
        return [mbox(f'{name}{tag}n', ox - s / 2, ox + s / 2, y0, y1, oz - r, oz - r + thick, paint, faces),
                mbox(f'{name}{tag}s', ox - s / 2, ox + s / 2, y0, y1, oz + r - thick, oz + r, paint, faces),
                mbox(f'{name}{tag}w', ox - r, ox - r + thick, y0, y1, oz - s / 2, oz + s / 2, paint, faces),
                mbox(f'{name}{tag}e', ox + r - thick, ox + r, y0, y1, oz - s / 2, oz + s / 2, paint, faces)]
    return slabs('a', cx, cz), [mpart(name + '_d', (cx, 0.0, cz), (0.0, 45.0, 0.0), slabs('b', 0.0, 0.0))]


# ================================================================== a painter from a function of a face's own texels
def facepaint(key, fn, light=True):
    """A cube painter: fn(face, w, h, rng) -> (h, w, 4) floats or None, as townsfolk_tools.painter takes it."""
    return tt.painter(key, fn, light=light)


def pic_painter(key, faces, draw, light=False, edge=0.0):
    """A painter that gives the faces in [faces] ('front' ...) the picture draw(w, h, face) -> PIL image (RGBA) and the rest nothing."""
    def fn(face, w, h, rng):
        if face not in faces:
            return None
        im = draw(w, h, face)
        a = np.asarray(im.convert('RGBA'), float)
        return tt.shade_edges(a, edge) if edge else a
    return tt.painter(key, fn, light=light)


def flat_paint(key, ramp_, t=0.5, noise=0.05, edge=0.12, light=True):
    """A plain ramp tone with a little grain on every face."""
    def fn(face, w, h, rng):
        tt_ = t + noise * (rng.random((h, w)) - 0.5) * 2
        a = np.zeros((h, w, 4))
        a[..., :3] = bands(ramp_, tt_, 0.2)
        a[..., 3] = 255
        return tt.shade_edges(a, edge)
    return tt.painter(key, fn, light=light)


# ================================================================== block models
def model(elements, particle, ao=True, extra_tex=None):
    """A block model: the textures it uses named from the trade's own folder, or the works' by a `w_` prefix on the name."""
    names = W.used(elements)
    tex = {'particle': ref_of(particle)}
    for n in names:
        tex[n] = ref_of(n)
    if extra_tex:
        tex.update(extra_tex)
    m = {'parent': 'minecraft:block/block'}
    if not ao:
        m['ambientocclusion'] = False
    m['textures'] = tex
    m['elements'] = elements
    return m


def ref_of(name):
    return (WORKS_NS + name[2:]) if name.startswith('w_') else (TEX_NS + name)


cube = W.cube
along = W.along
Rx, Ry, Rz = W.Rx, W.Ry, W.Rz
r3 = W.r3
FACING_Y = W.FACING_Y


def facing_blockstate(model_name):
    return {'variants': {f'facing={f}': {'model': 'guildmark:block/' + model_name} | ({'y': y} if y else {}) for f, y in FACING_Y}}


def octagon_ring(cx, cz, y0, y1, r_out, thick, tex, top=True, bottom=False, turn=0.0, inner=None):
    return W.ring(cx, cz, y0, y1, r_out, thick, tex, inner=inner, top=top, bottom=bottom, turn=turn)


def octagon_disc(cx, cz, y0, y1, r, tex, top=None, faces=SIX, turn=0.0):
    return W.disc(cx, cz, y0, y1, r, tex, top=top, faces=faces, turn=turn)


# ================================================================== meshes
class Mesh(W.Mesh):
    """One LayerDefinition: its parts, its texture's name and declared size, and the scale it is painted at (W.Mesh's)."""


def kf(v):
    return W.kf(v)


def kfs(vals):
    return 'floatArrayOf(' + ', '.join(kf(v) for v in vals) + ')'


def kint(v):
    return '0x%06X' % int(v)


def mesh_fun(m):
    """The Kotlin function of mesh [m] (a LayerDefinition), its doc line and its texture's size."""
    body = m.model.kotlin().rstrip('\n')
    doc = ' '.join(m.doc.split())
    return f'''    /** {doc} On a {m.model.tex_w} x {m.model.tex_h} texture ({m.texture}.png ships at {m.scale}x that). */
    @Suppress("LocalVariableName", "UNUSED_VARIABLE")
    fun {m.fun}(): LayerDefinition {{
{body}
    }}'''


KT_MESH_IMPORTS = '''import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.core.Direction
import java.util.EnumSet
'''


# ================================================================== output
def dump_json(obj):
    return json.dumps(obj, indent=2) + '\n'


def write(path, text, mode='w'):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as f:
        f.write(text)


def save_png(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def tile_image(name):
    """Tile [name] (32 x 32 texels, a block's face at two to a pixel) as a picture."""
    a = W.tile(name)
    return Image.fromarray(np.clip(np.round(a), 0, 255).astype(np.uint8), 'RGBA')


def write_bbmodel(models_dir, name, m, tex):
    """The mesh's Blockbench file (a modded entity, as the works' are), its texture shown at the declared size."""
    os.makedirs(models_dir, exist_ok=True)
    small = tex.resize((m.model.tex_w, m.model.tex_h), Image.LANCZOS)
    with open(os.path.join(models_dir, name + '.bbmodel'), 'w') as f:
        json.dump(m.model.bbmodel(small, m.texture), f)


# ================================================================== the check of what was written
FLAT_FACES = {0: ('west', 'east'), 1: ('down', 'up'), 2: ('north', 'south')}


class Check:
    """What a script wrote, looked at as the game would load it: every blockstate names models that exist, every model's textures are there
    (the trade's own or the works'), its elements are in the range the game takes and its faces on the sides they can be, its uvs lie
    in the picture; every mesh texture is the size its mesh says; every picture has something in it. Problems are collected, not raised."""

    def __init__(self, assets):
        self.assets = assets
        self.problems = []
        self.models = {}
        self.textures = set()

    def bad(self, msg):
        self.problems.append(msg)

    def path(self, rel):
        return os.path.join(self.assets, rel)

    def json(self, rel, who=''):
        p = self.path(rel)
        if not os.path.exists(p):
            self.bad('missing %s%s' % (rel, ' (named by %s)' % who if who else ''))
            return None
        try:
            with open(p) as f:
                return json.load(f)
        except ValueError as e:
            self.bad('%s is not JSON: %s' % (rel, e))
            return None

    def picture(self, rel, who=''):
        if not os.path.exists(self.path(rel)):
            self.bad('missing %s%s' % (rel, ' (named by %s)' % who if who else ''))
            return None
        im = Image.open(self.path(rel)).convert('RGBA')
        if not im.getchannel('A').getbbox():
            self.bad('%s has nothing in it' % rel)
        return im

    def texture(self, ref, who):
        ns, _, name = ref.rpartition(':')
        if ns != 'guildmark' or name in self.textures:
            return
        self.textures.add(name)
        self.picture('textures/%s.png' % name, who)

    def element(self, name, i, e, textures):
        fr, to = e['from'], e['to']
        for a in range(3):
            if fr[a] > to[a] + 1e-9:
                self.bad('models/%s.json element %d: from is past to on axis %d' % (name, i, a))
            if fr[a] < -16 or to[a] > 32:
                self.bad('models/%s.json element %d: %s to %s is outside the -16 to 32 the game takes' % (name, i, fr, to))
        flat = [a for a in range(3) if abs(fr[a] - to[a]) < 1e-9]
        if len(flat) >= 2:
            self.bad('models/%s.json element %d: flat along two axes' % (name, i))
        for d, face in e['faces'].items():
            t = face['texture']
            if not t.startswith('#') or t[1:] not in textures:
                self.bad('models/%s.json element %d: the face %s names a texture the model does not define (%s)' % (name, i, d, t))
            uv = face.get('uv')
            if uv and (min(uv) < -1e-9 or max(uv) > 16 + 1e-9):
                self.bad('models/%s.json element %d: the uv of %s is outside the picture: %s' % (name, i, d, uv))
            if len(flat) == 1 and d not in FLAT_FACES[flat[0]]:
                self.bad('models/%s.json element %d: a %s face on an element flat along axis %d' % (name, i, d, flat[0]))
        rot = e.get('rotation')
        if rot:
            if not ('x' in rot and 'y' in rot and 'z' in rot or 'axis' in rot and 'angle' in rot):
                self.bad('models/%s.json element %d: a rotation of neither form the game reads: %s' % (name, i, rot))
            if 'origin' not in rot:
                self.bad('models/%s.json element %d: a rotation without an origin' % (name, i))

    def model(self, ref, who):
        ns, _, name = ref.rpartition(':')
        if ns != 'guildmark' or name in self.models:
            return
        self.models[name] = None
        m = self.json('models/%s.json' % name, who)
        if m is None:
            return
        self.models[name] = m
        textures = m.get('textures', {})
        for t in textures.values():
            if not t.startswith('#'):
                self.texture(t, 'models/%s.json' % name)
        for i, e in enumerate(m.get('elements', [])):
            self.element(name, i, e, textures)

    def blockstate(self, block, facings=True):
        s = self.json('blockstates/%s.json' % block)
        if s is None:
            return
        refs = []
        for k, v in s.get('variants', {}).items():
            refs += [v['model']] if isinstance(v, dict) else [x['model'] for x in v]
        if facings:
            want = {'facing=north', 'facing=east', 'facing=south', 'facing=west'}
            if set(s.get('variants', {})) != want:
                self.bad('blockstates/%s.json: its variants are not the four facings' % block)
        if not refs:
            self.bad('blockstates/%s.json names no model' % block)
        for r in refs:
            self.model(r, 'blockstates/%s.json' % block)

    def mesh_texture(self, name, tex_w, tex_h, scale):
        im = self.picture('textures/block/trade/%s.png' % name)
        if im is not None and im.size != (tex_w * scale, tex_h * scale):
            self.bad('textures/block/trade/%s.png is %s, its mesh says %s' % (name, im.size, (tex_w * scale, tex_h * scale)))

    def mesh_parts(self, m, want):
        """The mesh [m] has each of the part names in [want] at the top level, and every cube is painted (it has something on each face
        it shows)."""
        names = [p.name for p in m.parts]
        for w_ in want:
            if w_ not in names:
                self.bad('mesh %s lacks the part %s' % (m.fun, w_))


# ================================================================== a palette for GIFs: one for every frame
def save_gif(frames, path, ms=50, colours=255):
    """A GIF whose frames all carry the one palette: a sheet of every frame is quantized once and each frame is mapped to that palette
    (a GIF whose frames each carry their own comes out in false colours), saved without optimising."""
    rgb = [f.convert('RGB') for f in frames]
    w, h = rgb[0].size
    step = max(1, len(rgb) // 24)
    pick = rgb[::step]
    cols = 4
    rows = (len(pick) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * w, rows * h), (0, 0, 0))
    for i, f in enumerate(pick):
        sheet.paste(f, ((i % cols) * w, (i // cols) * h))
    pal = sheet.quantize(colors=colours, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    mapped = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in rgb]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    mapped[0].save(path, save_all=True, append_images=mapped[1:], duration=ms, loop=0, optimize=False, disposal=1)
    return path


# ================================================================== previews: the works' scene, with the trade's textures
PREVIEW_BG = W.PREVIEW_BG
label = W.label
tiles = W.tiles
placer = W.placer
blocker = W.blocker
S_FLIP = W.S_FLIP


class Scene(W.Scene):
    """W.Scene, its mesh parts' textures read from the trade's folder (or another, `ref` a texture name under textures/)."""

    def mesh(self, mesh, part, place, pose=None, scale=1.0, double=False, ref=None):
        p = next(q for q in mesh.parts if q.name == part)
        Wd, H = mesh.model.tex_w, mesh.model.tex_h
        ref = ref or ('guildmark:block/trade/' + mesh.texture)
        for A, B, D, (u, v, w, h) in W.part_faces(p, pose):
            a, b, d = place(A * scale), place(B * scale), place(D * scale)
            u0, v0, u1, v1 = u / Wd * 16, v / H * 16, (u + w) / Wd * 16, (v + h) / H * 16
            self._add([a, d, d + (b - a), b], [(u0, v0), (u0, v1), (u1, v1), (u1, v0)], ref)
            if double:
                self._add([b, b + (d - a), d, a], [(u1, v0), (u1, v1), (u0, v1), (u0, v0)], ref)


def preview_assets(assets, out):
    """A scratch assets folder with the trade's textures and the works' as written, and a plain floor for the previews."""
    import shutil
    pa = os.path.join(out, '_assets')
    for sub in ('block/works', 'block/trade', 'block/preview'):
        os.makedirs(os.path.join(pa, 'textures', sub), exist_ok=True)
    for sub in ('block/works', 'block/trade'):
        src = os.path.join(assets, 'textures', sub)
        if os.path.isdir(src):
            for f in os.listdir(src):
                if f.endswith('.png'):
                    shutil.copy(os.path.join(src, f), os.path.join(pa, 'textures', sub, f))
    g = Canvas(T)
    xs, ys = grid(T)
    g.put(ALL, bands([(58, 52, 40), (78, 70, 52), (96, 86, 62), (112, 100, 72)], 0.5 + 0.25 * (fbm(T, T, 5, 9001) - 0.5)
                     + 0.1 * (rand(T, T, 9003) - 0.5), 0.3))
    g.image().save(os.path.join(pa, 'textures', 'block', 'preview', 'ground.png'))
    return pa
