"""
The carriers' shared kit: what `pack_harness.py`, `laden_pack.py`, `player_cart.py` and `carrier_wagon.py` (Trade's art
stream X2) all draw with. It holds no art of its own.

  * parts and boxes written in absolute model-space px (`part`, `bx`), packed into a texture by the skyline packer
    (`pack`), their box UV mapped as Minecraft maps it at the shipped HD scale (`rects`);
  * every cube face of a posed model (`faces_of`; a part may turn, move, scale and hide, as the renderer's ModelPart
    does) and a small orthographic renderer with a z-buffer, over a ground with a soft shadow, anti-aliased by
    supersampling (`render`); contact sheets (`sheet`) and GIFs that keep one palette for all frames (`save_gif`);
  * the vanilla donkey, mule, llama and player rebuilt from the numbers in the Minecraft jar (read with `javap -c`:
    `AbstractEquineModel`, `DonkeyModel`, `LlamaModel`, `HumanoidModel`, `PlayerModel`) with their own animation, so
    the carriers' pieces are drawn on the animal they are made for, at its real size (`Vanilla`);
  * the Kotlin emitting helpers the generated tables share, and the self-check every script runs on what it wrote.

Model space as everywhere in the repo's art: pixels, y down, the model faces -z, its right is -x. An entity's ground
is `GROUND_Y` (24.016 px: the 1.501 blocks the entity renderer lifts a model by).
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from modelkit import Cube, Part, Model
import townsfolk_tools as tt

SCALE = 2                   # the shipped PNGs are SCALE times their declared (box UV) size
GROUND_Y = 24.016           # the entity renderer's ground, in model-space px

# ================================================================== rotation, as ModelPart turns a part


def rotx(deg):
    a = math.radians(deg)
    return np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])


def roty(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])


def rotz(deg):
    a = math.radians(deg)
    return np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]])


def rot_zyx(rot):
    """ModelPart's own order: x first, then y, then z (`Quaternionf.rotationZYX`)."""
    return rotz(rot[2]) @ roty(rot[1]) @ rotx(rot[0])


def smooth(x):
    c = min(1.0, max(0.0, x))
    return c * c * (3.0 - 2.0 * c)


# ================================================================== parts and boxes in absolute px

def bx(x0, y0, z0, x1, y1, z1, name, paint=None, share=None, faces=None, mirror=False, inflate=0.0):
    """A cube from corner (x0, y0, z0) to (x1, y1, z1), in model-space px (`part` makes them relative to its pivot).
    [share]: cubes with the same share name use the first one's place in the texture (and its paint)."""
    c = Cube((x0, y0, z0), (x1 - x0, y1 - y0, z1 - z0), paint, inflate=inflate, mirror=mirror, name=name, faces=faces)
    c.share = share
    return c


def part(name, pivot, cubes=(), parent=None, rot=(0.0, 0.0, 0.0), local=False):
    """A part with its pivot at [pivot] and its [cubes] (from `bx`), both written in px of the PARENT's frame: the
    model's own frame (absolute px) when [parent] is None or is not turned, so that a layer reads like a drawing; Minecraft
    wants the pivot relative to the parent's and each cube's origin relative to the part's own, and this does that
    subtraction. [local] says the parent IS turned (or its parent is), where its frame is not the model's: the pivot is
    then already relative to the parent's, the cubes are in the parent's frame and still made relative to the pivot. A
    part's `.abs` is its pivot in the frame its children are written in when this one is not turned (absolute px), or
    relative to the first untruned ancestor otherwise; a child of a turned part is always made with `local=True`."""
    px, py, pz = pivot
    if parent is None:
        rel = (px, py, pz)
        absp = (px, py, pz)
    elif local:
        rel = (px, py, pz)
        absp = (parent.abs[0] + px, parent.abs[1] + py, parent.abs[2] + pz)
    else:
        rel = (px - parent.abs[0], py - parent.abs[1], pz - parent.abs[2])
        absp = (px, py, pz)
    p = Part(name, rel, rot)
    p.abs = absp
    for c in cubes:
        c.origin = (c.origin[0] - px, c.origin[1] - py, c.origin[2] - pz)
        p.cubes.append(c)
    if parent is not None:
        parent.children.append(p)
    return p


def all_names(model):
    return [p.name for p in model.all_parts()]


def find(model, name):
    for p in model.all_parts():
        if p.name == name:
            return p
    raise KeyError(name)


# ================================================================== the texture: skyline packing and box-UV rects

def footprint(c):
    return c.footprint()


def pack(cubes, W, H, who='model'):
    """Skyline-pack every cube's box UV into a W x H declared texture (the lowest fit first): cubes with the same
    [share] take the first one's place. Returns the rows used."""
    sky = [[0, 0, W]]
    placed = {}
    todo = []
    for c in cubes:
        sh = getattr(c, 'share', None)
        if sh and sh in placed:
            o = placed[sh]
            if any(abs(a - b) > 1e-6 for a, b in zip(o.size, c.size)):
                raise SystemExit(f'{who}: {c.name} shares a picture with {o.name} but is another size {tuple(c.size)} / {tuple(o.size)}')
            continue
        placed[sh or c.name] = c
        todo.append(c)
    for c in sorted(todo, key=lambda c: (-c.footprint()[1], -c.footprint()[0], c.name)):
        fw, fh = c.footprint()
        best = None
        for i in range(len(sky)):
            x = sky[i][0]
            if x + fw > W:
                break
            y, j, room = 0, i, 0
            while room < fw:
                y = max(y, sky[j][1])
                room += sky[j][2]
                j += 1
            key = (y + fh, y, x)
            if best is None or key < best[0]:
                best = (key, x, y)
        if best is None:
            raise SystemExit(f'{who}: {c.name} ({fw}x{fh}) does not fit a {W}x{H} texture')
        _, x, y = best
        c.uv = (x, y)
        new, end = [], x + fw
        for sx, sy, sw in sky:
            if sx + sw <= x or sx >= end:
                new.append([sx, sy, sw])
                continue
            if sx < x:
                new.append([sx, sy, x - sx])
            if sx + sw > end:
                new.append([end, sy, sx + sw - end])
        new.append([x, y + fh, fw])
        new.sort()
        sky = []
        for seg in new:
            if sky and sky[-1][1] == seg[1] and sky[-1][0] + sky[-1][2] == seg[0]:
                sky[-1][2] += seg[2]
            else:
                sky.append(seg)
    for c in cubes:
        sh = getattr(c, 'share', None)
        if sh and c.uv is None:
            c.uv = placed[sh].uv
    used = max(c.uv[1] + c.footprint()[1] for c in cubes)
    if used > H:
        raise SystemExit(f'{who}: texture too small, need {used} rows of {H}')
    return used


def rects(c, s=SCALE):
    """A cube's six face rects (x, y, w, h) in a texture [s] times the declared size, placed from the cube's true
    (float) size as Minecraft maps box UV (so a 2.4 px face covers three texels' worth rounded out, as the game does);
    a mirrored cube swaps its left and right faces."""
    u, v = c.uv
    w, h, d = c.size

    def R(x0, y0, ww, hh):
        X0, Y0 = math.floor(round(x0 * s, 6)), math.floor(round(y0 * s, 6))
        X1, Y1 = math.ceil(round((x0 + ww) * s, 6)), math.ceil(round((y0 + hh) * s, 6))
        return (X0, Y0, X1 - X0, Y1 - Y0)
    r = {'top': R(u + d, v, w, d), 'bottom': R(u + d + w, v, w, d), 'right': R(u, v + d, d, h),
         'front': R(u + d, v + d, w, h), 'left': R(u + d + w, v + d, d, h), 'back': R(u + 2 * d + w, v + d, w, h)}
    if c.mirror:
        r['right'], r['left'] = r['left'], r['right']
    return r


def paint_texture(cubes, W, H, s=SCALE, painters=None):
    """The HD texture for [cubes]: each distinct paint once, on its own rects (cubes that share a place use the paint of
    whichever of them has one, or [painters][share name])."""
    img = Image.new('RGBA', (W * s, H * s), (0, 0, 0, 0))
    painters = painters or {}
    first, paint = {}, {}
    for c in cubes:
        key = getattr(c, 'share', None) or c.name
        first.setdefault(key, c)
        if c.paint is not None:
            paint.setdefault(key, c.paint)
    for key, c in first.items():
        p = paint.get(key) or painters.get(key)
        if p is None:
            continue
        for face, (x, y, w, h) in rects(c, s).items():
            if w > 0 and h > 0 and c.shows(face):
                p(img, face, x, y, w, h)
    return img


# ================================================================== every face of a posed model

class Face:
    __slots__ = ('A', 'B', 'D', 'rect', 'face', 'n', 'tex', 'tint', 'glow', 'part', 'cube')

    def __init__(self, A, B, D, rect, face, n, tex, tint=None, glow=False, part=None, cube=None):
        self.A, self.B, self.D, self.rect, self.face, self.n = A, B, D, rect, face, n
        self.tex, self.tint, self.glow, self.part, self.cube = tex, tint, glow, part, cube


def faces_of(model, tex, s=SCALE, pose=None, M=None, T=None, root=(1.0, 0.0), tint=None, only=None, skip=(), glow=False):
    """Every drawn face of [model] as ModelPart would place it, for the renderer. [tex] names the texture the faces
    sample (`render`'s `textures`); [s] is that texture's size over the declared one. [pose]: {part name: dict} with
    any of `rot` (x, y, z degrees: replaces the part's own), `move` (x, y, z px: added to its pivot), `scale` (a
    number or x, y, z), `hide` (the part and its children are not drawn). [M], [T]: the whole model turned and moved
    (model space, y down). [root]: (scale, dy) the root part's pose, as `MeshTransformer.scaling` gives it: a model
    point p lands at (0, dy, 0) + scale * p. [only]: draw just these parts (and their children); [skip]: leave these
    out (and their children)."""
    pose = pose or {}
    M = np.eye(3) if M is None else M
    T = np.zeros(3) if T is None else np.asarray(T, float)
    rs, rdy = root
    out = []

    def go(p, Mp, Pp, drawing):
        e = pose.get(p.name, {})
        if e.get('hide') or p.name in skip:
            return
        rot = e.get('rot', p.rot)
        sc = e.get('scale', 1.0)
        sc = np.array([sc, sc, sc], float) if np.isscalar(sc) else np.asarray(sc, float)
        piv = np.array(p.pivot, float) + np.asarray(e.get('move', (0, 0, 0)), float)
        Mq = Mp @ rot_zyx(rot) @ np.diag(sc)
        Pq = Pp + Mp @ piv
        here = drawing or only is None or p.name in only
        if here:
            for c in p.cubes:
                g = c.inflate
                x0, y0, z0 = [o - g for o in c.origin]
                x1, y1, z1 = [o + sz + g for o, sz in zip(c.origin, c.size)]

                def W(x, y, z):
                    q = Pq + Mq @ np.array([x, y, z], float)
                    return T + M @ (np.array([0.0, rdy, 0.0]) + rs * q)
                quads = {
                    'front': (W(x0, y0, z0), W(x1, y0, z0), W(x0, y1, z0), (0, 0, -1)),
                    'back': (W(x1, y0, z1), W(x0, y0, z1), W(x1, y1, z1), (0, 0, 1)),
                    'right': (W(x0, y0, z1), W(x0, y0, z0), W(x0, y1, z1), (-1, 0, 0)),
                    'left': (W(x1, y0, z0), W(x1, y0, z1), W(x1, y1, z0), (1, 0, 0)),
                    'top': (W(x0, y0, z1), W(x1, y0, z1), W(x0, y0, z0), (0, -1, 0)),
                    'bottom': (W(x0, y1, z1), W(x1, y1, z1), W(x0, y1, z0), (0, 1, 0)),
                }
                rc = rects(c, s)
                for face, (A, B, D, n) in quads.items():
                    if not c.shows(face):
                        continue
                    if c.mirror and face in ('front', 'back', 'top', 'bottom'):
                        A, B, D = B, A, D + (B - A)               # the picture runs the other way across the face
                    nn = M @ (Mq @ np.array(n, float))
                    ln = np.linalg.norm(nn)
                    out.append(Face(A, B, D, rc[face], face, nn / ln if ln > 1e-9 else nn, tex, tint, glow, p.name, c.name))
        for ch in p.children:
            go(ch, Mq, Pq, here)

    for p in model.parts:
        go(p, np.eye(3), np.zeros(3), False)
    return out


def ground_face_list(*a, **k):                  # kept for the callers that draw a stand-in ground themselves
    return []


# ================================================================== the renderer

class View:
    """An orthographic camera: [yaw], [pitch] degrees (as handcart.py's previews give them: pitch below 0 looks down
    on the model), [scale] screen px per model px, looking at [center] (model space), the picture [size]."""

    def __init__(self, yaw=-35.0, pitch=-20.0, scale=6.0, center=(0.0, 12.0, 0.0), size=(420, 330)):
        self.yaw, self.pitch, self.scale, self.center, self.size = yaw, pitch, scale, center, size


LIGHT = np.array([-0.45, -0.8, -0.4])
LIGHT = LIGHT / np.linalg.norm(LIGHT)
SKY = np.array([58.0, 64.0, 80.0])


def _tex_array(tex):
    if isinstance(tex, np.ndarray):
        return tex
    return np.array(tex.convert('RGBA'), float)


def render(faces, textures, view, ground=GROUND_Y, shadows=(), ssaa=2, sky=SKY, ground_cols=((88, 106, 68), (80, 98, 62)),
           tile=8.0):
    """[faces] (`faces_of`) seen through [view] with the textures in [textures] ({name: PIL image or array}), lit from the
    upper left front, over a ground at y = [ground] (None: no ground) with a soft shadow for each of [shadows]
    ((x, z, radius x, radius z, strength)), supersampled [ssaa] times. A texel under 8/255 alpha is not drawn."""
    W, H = view.size
    Ws, Hs = W * ssaa, H * ssaa
    sc = view.scale * ssaa
    img = np.zeros((Hs, Ws, 3), float)
    img[...] = sky
    zbuf = np.full((Hs, Ws), 1e9)
    arrs = {k: _tex_array(v) for k, v in textures.items()}
    V = rotx(-view.pitch) @ roty(view.yaw)
    c = np.asarray(view.center, float)

    def proj(p):
        q = V @ (np.asarray(p, float) - c)
        return np.array([Ws / 2 + q[0] * sc, Hs / 2 + q[1] * sc, q[2]])
    ys, xs = np.mgrid[0:Hs, 0:Ws]
    if ground is not None:
        Vt = V.T
        qx = (xs + 0.5 - Ws / 2) / sc
        qy = (ys + 0.5 - Hs / 2) / sc
        if abs(Vt[1, 2]) > 1e-6:
            qz = (ground - c[1] - Vt[1, 0] * qx - Vt[1, 1] * qy) / Vt[1, 2]
            wx = c[0] + Vt[0, 0] * qx + Vt[0, 1] * qy + Vt[0, 2] * qz
            wz = c[2] + Vt[2, 0] * qx + Vt[2, 1] * qy + Vt[2, 2] * qz
            chk = ((np.floor(wx / tile) + np.floor(wz / tile)) % 2).astype(int)
            col = np.where(chk[..., None] == 0, np.array(ground_cols[0], float), np.array(ground_cols[1], float))
            # soft grain, so a bare ground does not look like a flat fill
            col = col * (1 + 0.04 * np.sin(wx * 1.7) * np.sin(wz * 1.3))[..., None]
            for sx, sz, rx, rz, st in shadows:
                d2 = ((wx - sx) / rx) ** 2 + ((wz - sz) / rz) ** 2
                col = col * (1 - st * np.exp(-d2 * 1.6))[..., None]
            # fade the far ground into the sky
            far = np.clip((np.hypot(wx - c[0], wz - c[2]) - 70.0) / 70.0, 0, 1)
            col = col * (1 - far[..., None]) + np.array(sky) * far[..., None]
            img[...] = col
            zbuf[...] = qz
    for f in faces:
        rect = f.rect
        if rect is None or rect[2] <= 0 or rect[3] <= 0:
            continue
        a, b, d = proj(f.A), proj(f.B), proj(f.D)
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        pts = [a, b, d, b + e2]
        x0 = int(max(0, math.floor(min(p[0] for p in pts)))); x1 = int(min(Ws - 1, math.ceil(max(p[0] for p in pts)) + 1))
        y0 = int(max(0, math.floor(min(p[1] for p in pts)))); y1 = int(min(Hs - 1, math.ceil(max(p[1] for p in pts)) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]
        py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        u, v, w, h = rect
        T = arrs[f.tex]
        tu = np.clip((u + s * w).astype(int), u, u + w - 1)
        tv = np.clip((v + t * h).astype(int), v, v + h - 1)
        texel = T[np.clip(tv, 0, T.shape[0] - 1), np.clip(tu, 0, T.shape[1] - 1)]
        zb = zbuf[y0:y1, x0:x1]
        ok = inside & (texel[..., 3] > 8) & (depth < zb + 1e-4)
        if f.glow:
            k = 1.0
        else:
            nn = f.n
            k = min(1.15, 0.55 + 0.5 * max(0.0, float(np.dot(nn, -LIGHT))) + 0.1 * max(0.0, float(-nn[1])))
        colr = texel[..., :3] * k
        if f.tint is not None:
            colr = colr * (np.asarray(f.tint, float) / 255.0)
        alpha = texel[..., 3:4] / 255.0
        region = img[y0:y1, x0:x1]
        region[...] = np.where(ok[..., None], colr * alpha + region * (1 - alpha), region)
        zb[ok & (texel[..., 3] > 150)] = depth[ok & (texel[..., 3] > 150)]
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')
    if ssaa > 1:
        out = out.resize((W, H), Image.LANCZOS)
    return out


def caption(img, text, h=18, bg=(30, 32, 40), fg=(235, 235, 235)):
    out = Image.new('RGB', (img.width, img.height + h), bg)
    out.paste(img, (0, h))
    ImageDraw.Draw(out).text((6, 3), text, fill=fg)
    return out


def sheet(tiles, cols, title=None, gap=4, bg=(30, 32, 40)):
    """[tiles] (PIL images, usually with `caption`ed text) in rows of [cols], an optional [title] over them."""
    w = max(t.width for t in tiles)
    h = max(t.height for t in tiles)
    rows = (len(tiles) + cols - 1) // cols
    top = 22 if title else 0
    out = Image.new('RGB', (cols * w + (cols - 1) * gap, rows * h + (rows - 1) * gap + top), bg)
    for i, t in enumerate(tiles):
        out.paste(t, ((i % cols) * (w + gap), top + (i // cols) * (h + gap)))
    if title:
        ImageDraw.Draw(out).text((6, 5), title, fill=(245, 245, 245))
    return out


def save_gif(frames, path, ms=50):
    """A looping GIF of [frames] (PIL images), [ms] each (a number, or one per frame), every frame on ONE palette: a
    sheet of all the frames (each at half size) is quantized once by median cut, no dithering, and every frame is
    mapped onto that palette; saved with optimize=False so the palette is not re-chosen frame by frame. A pixel that
    is the same as in the frame before is left transparent over it (index 255), which keeps a still scene with
    something moving in it small. Nothing in it is random: the same frames give the same bytes."""
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    rgb = [f.convert('RGB') for f in frames]
    w, h = rgb[0].size
    hw, hh = max(1, w // 2), max(1, h // 2)
    cols = max(1, min(len(rgb), 8))
    rows = (len(rgb) + cols - 1) // cols
    mosaic = Image.new('RGB', (cols * hw, rows * hh))
    for i, f in enumerate(rgb):
        mosaic.paste(f.resize((hw, hh), Image.BOX), ((i % cols) * hw, (i // cols) * hh))
    pal = mosaic.quantize(colors=255, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    table = list(pal.getpalette()[:255 * 3])
    table += [0] * (256 * 3 - len(table))
    idx = [np.asarray(f.quantize(palette=pal, dither=Image.Dither.NONE)) for f in rgb]
    out = []
    for k, a in enumerate(idx):
        b = a.copy()
        if k:
            b[a == idx[k - 1]] = 255
        im = Image.fromarray(b, 'P')
        im.putpalette(table)
        out.append(im)
    out[0].save(path, save_all=True, append_images=out[1:], duration=ms, loop=0, optimize=False, disposal=1, transparency=255)
    return path


def steady_uuids(text, key):
    """The .bbmodel [text] with each of its uuids made from [key] and the order it first appears in (the same uuid stays the same
    uuid, so the groups still list their cubes), so that a rerun writes the same file."""
    import re
    import uuid
    seen = {}

    def sub(m):
        if m.group(0) not in seen:
            seen[m.group(0)] = str(uuid.uuid5(uuid.NAMESPACE_URL, f'guildmark/{key}/{len(seen)}'))
        return seen[m.group(0)]
    return re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', sub, text)


# ================================================================== Kotlin helpers

def kt_float(v):
    t = ('%.3f' % v).rstrip('0').rstrip('.')
    return (t if t not in ('-0', '') else '0') + 'f'


def kt_list(vals):
    return ', '.join(kt_float(v) for v in vals)


def layer_body(model, transformer=None):
    """`Model.kotlin()` with the layer returned through a MeshTransformer ([transformer] is Kotlin text, such as
    `MeshTransformer.scaling(DonkeyModel.DONKEY_SCALE)`), as the vanilla donkey's own layer is."""
    body = model.kotlin().rstrip('\n')
    if transformer:
        last = f'        return LayerDefinition.create(mesh, {model.tex_w}, {model.tex_h})'
        assert body.endswith(last), 'the emitted layer ends differently than expected'
        body = body[:-len(last)] + last + f'.apply({transformer})'
    return body


def mesh_body(model):
    """`Model.kotlin()` as the body of a function that returns the `MeshDefinition` (for layers that share one mesh and
    differ in what is done to it)."""
    body = model.kotlin().rstrip('\n')
    last = f'        return LayerDefinition.create(mesh, {model.tex_w}, {model.tex_h})'
    assert body.endswith(last), 'the emitted layer ends differently than expected'
    return body[:-len(last)] + '        return mesh'


# ================================================================== the vanilla animals and the player, from the jar

def _vbox(name, uv, x, y, z, w, h, d, inflate=0.0, mirror=False):
    c = Cube((x, y, z), (w, h, d), None, inflate=inflate, mirror=mirror, name=name)
    c.uv = uv
    c.share = None
    return c


def _vpart(name, pivot, cubes=(), rot=(0.0, 0.0, 0.0), parent=None):
    p = Part(name, pivot, rot, cubes=list(cubes))
    if parent is not None:
        parent.children.append(p)
    return p


DONKEY_SCALE, MULE_SCALE = 0.87, 0.92          # DonkeyModel.DONKEY_SCALE, MULE_SCALE (public constants in the jar)


def root_pose(scale):
    """`MeshTransformer.scaling(scale)`: the root part's pose scaled by it and lowered by 24.016 (1 - scale) px, which
    keeps the feet on the ground."""
    return (scale, 24.016 * (1.0 - scale))


def vanilla_equine():
    """The donkey's and the mule's model (`AbstractEquineModel.createBodyMesh` + `DonkeyModel.modifyMesh`, 64 x 64):
    the body (pivot 0, 11, 5) with the tail (pivot 0, -5, 2 in it) and the two chests (the donkey's own boxes, 8 x 8 x 3,
    turned a quarter about y at pivot +-6, -8, 0 in the body), the neck and head (head_parts, pivot 0, 4, -12, turned
    30 degrees forward) with its mane, muzzle and ears, and the four legs (pivots +-4, 14, 7 and +-4, 14, -10)."""
    body = _vpart('body', (0, 11, 5), [_vbox('body', (0, 32), -5, -8, -17, 10, 10, 22, 0.05)])
    _vpart('tail', (0, -5, 2), [_vbox('tail', (42, 36), -1.5, 0, 0, 3, 14, 4)], rot=(30, 0, 0), parent=body)
    chest = lambda n: _vbox(n, (26, 21), -4, 0, -2, 8, 8, 3)
    _vpart('left_chest', (6, -8, 0), [chest('left_chest')], rot=(0, -90, 0), parent=body)
    _vpart('right_chest', (-6, -8, 0), [chest('right_chest')], rot=(0, 90, 0), parent=body)
    hp = _vpart('head_parts', (0, 4, -12), [_vbox('neck', (0, 35), -2.05, -6, -2, 4, 12, 7)], rot=(30, 0, 0))
    head = _vpart('head', (0, 0, 0), [_vbox('head', (0, 13), -3, -11, -2, 6, 5, 7)], parent=hp)
    _vpart('mane', (0, 0, 0), [_vbox('mane', (56, 36), -1, -11, 5.01, 2, 16, 2)], parent=hp)
    _vpart('upper_mouth', (0, 0, 0), [_vbox('upper_mouth', (0, 25), -2, -11, -7, 4, 5, 5)], parent=hp)
    ear = lambda n, x: _vbox(n, (0, 12), x, -7, 0, 2, 7, 1)
    # DonkeyModel.modifyMesh: the long ears, each leaning out 15 degrees and back 15
    _vpart('left_ear', (1.25, -10, 4), [ear('left_ear', -1)], rot=(15, 0, 15), parent=head)
    _vpart('right_ear', (-1.25, -10, 4), [ear('right_ear', -1)], rot=(15, 0, -15), parent=head)
    legs = []
    for n, x, z, mir in (('left_hind_leg', 4, 7, True), ('right_hind_leg', -4, 7, False),
                         ('left_front_leg', 4, -10, True), ('right_front_leg', -4, -10, False)):
        zf = -1.9 if 'front' in n else -1.0
        bxl = (-3 if mir else -1)
        legs.append(_vpart(n, (x, 14, z), [_vbox(n, (48, 21), bxl, -1.01, zf, 4, 11, 4, mirror=mir)]))
    return Model('donkey', 64, 64, [body, hp] + legs)


def vanilla_llama():
    """The llama (`LlamaModel.createBodyLayer`, 128 x 64): the head and its tall neck (pivot 0, 7, -6) with two ears, the
    body (pivot 0, 5, 2, turned 90 degrees forward: it lies along z), the two chests (8 x 8 x 3 at +-8.5 / 5.5, 3, 3, a
    quarter about y) and the four legs (pivots +-3.5, 10, 6 and +-3.5, 10, -5)."""
    head = _vpart('head', (0, 7, -6), [_vbox('head', (0, 0), -2, -14, -10, 4, 4, 9), _vbox('neck', (0, 14), -4, -16, -6, 8, 18, 6),
                                       _vbox('ear_r', (17, 0), -4, -19, -4, 3, 3, 2), _vbox('ear_l', (17, 0), 1, -19, -4, 3, 3, 2)])
    body = _vpart('body', (0, 5, 2), [_vbox('body', (29, 0), -6, -10, -7, 12, 18, 10)], rot=(90, 0, 0))
    rc = _vpart('right_chest', (-8.5, 3, 3), [_vbox('right_chest', (45, 28), -3, 0, 0, 8, 8, 3)], rot=(0, 90, 0))
    lc = _vpart('left_chest', (5.5, 3, 3), [_vbox('left_chest', (45, 41), -3, 0, 0, 8, 8, 3)], rot=(0, 90, 0))
    legs = []
    for n, x, z in (('right_hind_leg', -3.5, 6), ('left_hind_leg', 3.5, 6), ('right_front_leg', -3.5, -5), ('left_front_leg', 3.5, -5)):
        legs.append(_vpart(n, (x, 10, z), [_vbox(n, (29, 29), -2, 0, -2, 4, 14, 4)]))
    return Model('llama', 128, 64, [head, body, rc, lc] + legs)


def vanilla_player():
    """The player (`PlayerModel.createMesh`, 64 x 64, wide arms): head (0, 0), hat (32, 0, +0.5), body (16, 16), jacket (16, 32,
    +0.25), the arms (pivots -5, 2, 0 and 5, 2, 0; 4 x 12 x 4) and legs (-1.9, 12, 0 and 1.9, 12, 0) with their sleeves and
    pants (+0.25)."""
    parts = [
        _vpart('head', (0, 0, 0), [_vbox('head', (0, 0), -4, -8, -4, 8, 8, 8)]),
        _vpart('hat', (0, 0, 0), [_vbox('hat', (32, 0), -4, -8, -4, 8, 8, 8, 0.5)]),
        _vpart('body', (0, 0, 0), [_vbox('body', (16, 16), -4, 0, -2, 8, 12, 4)]),
        _vpart('jacket', (0, 0, 0), [_vbox('jacket', (16, 32), -4, 0, -2, 8, 12, 4, 0.25)]),
        _vpart('right_arm', (-5, 2, 0), [_vbox('right_arm', (40, 16), -3, -2, -2, 4, 12, 4)]),
        _vpart('left_arm', (5, 2, 0), [_vbox('left_arm', (32, 48), -1, -2, -2, 4, 12, 4)]),
        _vpart('right_sleeve', (-5, 2, 0), [_vbox('right_sleeve', (40, 32), -3, -2, -2, 4, 12, 4, 0.25)]),
        _vpart('left_sleeve', (5, 2, 0), [_vbox('left_sleeve', (48, 48), -1, -2, -2, 4, 12, 4, 0.25)]),
        _vpart('right_leg', (-1.9, 12, 0), [_vbox('right_leg', (0, 16), -2, 0, -2, 4, 12, 4)]),
        _vpart('left_leg', (1.9, 12, 0), [_vbox('left_leg', (16, 48), -2, 0, -2, 4, 12, 4)]),
        _vpart('right_pants', (-1.9, 12, 0), [_vbox('right_pants', (0, 32), -2, 0, -2, 4, 12, 4, 0.25)]),
        _vpart('left_pants', (1.9, 12, 0), [_vbox('left_pants', (0, 48), -2, 0, -2, 4, 12, 4, 0.25)]),
    ]
    return Model('player', 64, 64, parts)


def equine_pose(walk_pos=0.0, walk_speed=0.0, age=0.0, stand=0.0, eat=0.0, feed=0.0, head_pitch=0.0, head_yaw=0.0,
                tail_wag=True, age_scale=1.0, in_water=False):
    """`AbstractEquineModel.setupAnim` for an adult, as the bytecode computes it: the poses it gives for the state's
    walkAnimationPos and walkAnimationSpeed, ageInTicks, the stand (rear) and eat animations 0..1, the feeding
    animation, the head's own pitch and yaw (degrees; the yaw is clamped to +-20). Returns {part: {'rot': degrees,
    'move': px added to the pivot}} for head_parts, body, tail and the four legs."""
    pi = math.pi
    deg = pi / 180.0
    yaw = max(-20.0, min(20.0, head_yaw))
    xr = head_pitch * deg
    if walk_speed > 0.2:
        xr += math.cos(walk_pos * 0.8) * 0.15 * walk_speed
    stand_i = 1.0 - stand
    c12 = math.cos((0.2 if in_water else 1.0) * walk_pos * 0.6662 + pi)
    c13 = c12 * 0.8 * walk_speed
    m = 1.0 - max(stand, eat)
    hx = m * (0.5235988 + xr + feed * math.sin(age) * 0.05)
    head_x = stand * (0.2617994 + xr) + eat * (2.1816616 + math.sin(age) * 0.05) + hx
    head_y = stand * yaw * deg + m * (yaw * deg)
    lerp = lambda t, a, b: a + (b - a) * t
    d = math.degrees
    c15 = 0.2617994 * stand                                   # getLegStandAngle
    c16 = math.cos(age * 0.6 + pi)
    c17 = -1.0471976                                          # getLegStandingXRotOffset
    c18 = (c17 + c16) * stand + c13 * stand_i
    c19 = (c17 - c16) * stand - c13 * stand_i
    front_move = (0.0, -12.0 * stand, 4.0 * stand)            # getLegStandingYOffset 12, getLegStandingZOffset 4
    return {
        'head_parts': {'rot': (d(head_x), d(head_y), 0.0), 'move': (0.0, lerp(eat, -8.0 * stand, 7.0), 8.0 * stand)},
        'body': {'rot': (d(stand * -0.7853982), 0.0, 0.0)},
        'tail': {'rot': (d(0.5235988 + walk_speed * 0.75), d(math.cos(age * 0.7)) if tail_wag else 0.0, 0.0),
                 'move': (0.0, walk_speed * age_scale, walk_speed * 2.0 * age_scale)},
        'left_hind_leg': {'rot': (d(c15 - c12 * 0.5 * walk_speed * stand_i), 0.0, 0.0)},
        'right_hind_leg': {'rot': (d(c15 + c12 * 0.5 * walk_speed * stand_i), 0.0, 0.0)},
        'left_front_leg': {'rot': (d(c18), 0.0, 0.0), 'move': front_move},
        'right_front_leg': {'rot': (d(c19), 0.0, 0.0), 'move': front_move},
    }


def llama_pose(walk_pos=0.0, walk_speed=0.0, head_pitch=0.0, head_yaw=0.0):
    """`LlamaModel.setupAnim`: the head takes the state's pitch and yaw, the legs swing cos(pos * 0.6662) * 1.4 * speed."""
    pos = walk_pos * 0.6662
    c = lambda a: math.degrees(math.cos(a) * 1.4 * walk_speed)
    return {
        'head': {'rot': (head_pitch, head_yaw, 0.0)},
        'right_hind_leg': {'rot': (c(pos), 0, 0)},
        'left_hind_leg': {'rot': (c(pos + math.pi), 0, 0)},
        'right_front_leg': {'rot': (c(pos + math.pi), 0, 0)},
        'left_front_leg': {'rot': (c(pos), 0, 0)},
    }


def humanoid_pose(walk_pos=0.0, walk_speed=0.0, crouch=False, age=0.0, bob=True):
    """`HumanoidModel.setupAnim` for what a back-pack follows or clears: the arms swing cos(pos * 0.6662) * speed, the legs
    cos(pos * 0.6662) * 1.4 * speed (opposite sides in opposite ways), the arms also bob a little with age (the idle
    sway); crouching leans the body 0.5 rad and lowers it 3.2 px, the head 4.2 px, the arms 3.2 px and 0.4 rad more, the
    legs 4 px back. Returns {part: {'rot', 'move'}} (the jacket, sleeves and pants follow their limbs)."""
    pos = walk_pos * 0.6662
    sp = walk_speed
    d = math.degrees
    ra = math.cos(pos + math.pi) * sp
    la = math.cos(pos) * sp
    rl = math.cos(pos) * 1.4 * sp
    ll = math.cos(pos + math.pi) * 1.4 * sp
    rz = lz = 0.0
    rx_b = lx_b = 0.0
    if bob:                                              # AnimationUtils.bobModelPart(arm, age, +-1)
        rz = math.cos(age * 0.09) * 0.05 + 0.05
        lz = -(math.cos(age * 0.09) * 0.05 + 0.05)
        rx_b = math.sin(age * 0.067) * 0.05
        lx_b = -math.sin(age * 0.067) * 0.05
    body_rot, body_move, head_move, arm_move, leg_move = (0.0, 0.0, 0.0), (0, 0, 0), (0, 0, 0), (0, 0, 0), (0, 0, 0)
    if crouch:
        body_rot = (d(0.5), 0.0, 0.0)
        body_move, head_move, arm_move, leg_move = (0.0, 3.2, 0.0), (0.0, 4.2, 0.0), (0.0, 3.2, 0.0), (0.0, 0.0, 4.0)
        ra += 0.4
        la += 0.4
    arm_r = {'rot': (d(ra + rx_b), 0.0, d(rz)), 'move': arm_move}
    arm_l = {'rot': (d(la + lx_b), 0.0, d(lz)), 'move': arm_move}
    leg_r = {'rot': (d(rl), 0.0, 0.0), 'move': leg_move}
    leg_l = {'rot': (d(ll), 0.0, 0.0), 'move': leg_move}
    return {
        'body': {'rot': body_rot, 'move': body_move}, 'jacket': {'rot': body_rot, 'move': body_move},
        'head': {'move': head_move}, 'hat': {'move': head_move},
        'right_arm': arm_r, 'right_sleeve': arm_r, 'left_arm': arm_l, 'left_sleeve': arm_l,
        'right_leg': leg_r, 'right_pants': leg_r, 'left_leg': leg_l, 'left_pants': leg_l,
    }


class Vanilla:
    """The vanilla animals' and the player's textures, read from a game `assets/minecraft` folder (unpacked from the jar,
    read only), or a plain stand-in where there is none."""

    def __init__(self, game_assets=None):
        self.root = game_assets

    def tex(self, rel, size, fallback=(150, 120, 90)):
        p = os.path.join(self.root, 'textures', rel) if self.root else None
        if p and os.path.exists(p):
            return Image.open(p).convert('RGBA')
        im = Image.new('RGBA', size, fallback + (255,))
        return im

    def donkey(self):
        return self.tex('entity/horse/donkey.png', (64, 64), (130, 120, 112))

    def mule(self):
        return self.tex('entity/horse/mule.png', (64, 64), (110, 84, 62))

    def llama(self, kind='brown'):
        return self.tex(f'entity/llama/llama_{kind}.png', (128, 64), (170, 140, 100))

    def steve(self):
        return self.tex('entity/player/wide/steve.png', (64, 64), (90, 150, 190))

    def alex(self):
        return self.tex('entity/player/wide/alex.png', (64, 64), (190, 130, 90))


# ================================================================== the self-check every script runs on what it wrote

def check_model(model, tex, declared, s=SCALE, who='model', allow_empty=()):
    """The faults of [model] and its HD texture [tex] (declared box-UV size [declared] = (w, h)): unique part and cube
    names, every drawn face's rect inside the texture, no two cubes (that do not share one) drawing on the same
    texels, a texture of the right size, and no drawn face with nothing painted on it. Returns a list of strings."""
    out = []
    names = [p.name for p in model.all_parts()]
    for n in {n for n in names if names.count(n) > 1}:
        out.append(f'{who}: part name used twice: {n}')
    cn = [c.name for c in model.all_cubes()]
    for n in {n for n in cn if cn.count(n) > 1}:
        out.append(f'{who}: cube name used twice: {n}')
    if tex.size != (declared[0] * s, declared[1] * s):
        out.append(f'{who}: texture is {tex.size}, expected {(declared[0] * s, declared[1] * s)}')
    a = np.array(tex.convert('RGBA'))
    owner = {}
    for c in model.all_cubes():
        key = getattr(c, 'share', None) or c.name
        if c.uv is None:
            out.append(f'{who}: {c.name} has no place in the texture')
            continue
        for face, (x, y, w, h) in rects(c, s).items():
            if w <= 0 or h <= 0 or not c.shows(face):
                continue
            if x < 0 or y < 0 or x + w > tex.width or y + h > tex.height:
                out.append(f'{who}: {c.name}.{face} is outside the texture')
                continue
            cell = a[y:y + h, x:x + w, 3]
            if (cell > 8).sum() == 0 and c.name not in allow_empty and key not in allow_empty:
                out.append(f'{who}: {c.name}.{face} has nothing painted on it')
            for j in range(y, y + h):
                for i in range(x, x + w):
                    k = (i, j)
                    if k in owner and owner[k] != key:
                        out.append(f'{who}: {c.name}.{face} paints over {owner[k]} at {k}')
                        break
                    owner[k] = key
                else:
                    continue
                break
    return out


def must_be_clean(problems, who):
    if problems:
        raise SystemExit(f'{who}: the self-check found problems:\n  ' + '\n  '.join(problems[:40]) + (f'\n  ... and {len(problems) - 40} more' if len(problems) > 40 else ''))


# ================================================================== fit and clip: oriented boxes of a posed model

class Box:
    """One cube of a posed model as an oriented box in the renderer's space: its [c]entre, its three unit axes [ax] (rows) and
    its half extents [h] along them (px, scale and the root's scaling included)."""
    __slots__ = ('part', 'cube', 'c', 'ax', 'h', 'lo', 'hi')

    def __init__(self, part, cube, c, ax, h):
        self.part, self.cube, self.c, self.ax, self.h = part, cube, c, ax, h
        r = np.abs(ax.T) @ h                                    # the axis-aligned bounds, for a quick reject
        self.lo, self.hi = c - r, c + r


def boxes_of(model, pose=None, root=(1.0, 0.0), skip=(), only=None):
    """Every cube of [model] as the `Box` that `faces_of` would draw it as (same [pose], [root], [skip], [only])."""
    pose = pose or {}
    rs, rdy = root
    out = []

    def go(p, Mp, Pp, drawing):
        e = pose.get(p.name, {})
        if e.get('hide') or p.name in skip:
            return
        rot = e.get('rot', p.rot)
        sc = e.get('scale', 1.0)
        sc = np.array([sc, sc, sc], float) if np.isscalar(sc) else np.asarray(sc, float)
        piv = np.array(p.pivot, float) + np.asarray(e.get('move', (0, 0, 0)), float)
        Mq = Mp @ rot_zyx(rot) @ np.diag(sc)
        Pq = Pp + Mp @ piv
        here = drawing or only is None or p.name in only
        if here:
            for c in p.cubes:
                g = c.inflate
                lo = np.array([o - g for o in c.origin], float)
                size = np.array([s + 2 * g for s in c.size], float)
                centre = Pq + Mq @ (lo + size / 2)
                cols = Mq * rs                                   # columns: where the cube's own x, y, z go
                lens = np.linalg.norm(cols, axis=0)
                ax = (cols / np.where(lens > 1e-9, lens, 1.0)).T
                out.append(Box(p.name, c.name, np.array([0.0, rdy, 0.0]) + rs * centre, ax, size / 2 * lens))
        for ch in p.children:
            go(ch, Mq, Pq, here)

    for p in model.parts:
        go(p, np.eye(3), np.zeros(3), False)
    return out


def box_depth(a, b):
    """How deep two oriented boxes lie in one another (px): the least overlap along the 15 separating axes of the
    separating-axis test; zero or less when they do not touch (the biggest gap along the best axis, negated)."""
    if np.any(a.lo > b.hi) or np.any(b.lo > a.hi):
        return -float(np.max(np.maximum(a.lo - b.hi, b.lo - a.hi)))
    d = b.c - a.c
    axes = list(a.ax) + list(b.ax)
    for i in range(3):
        for j in range(3):
            x = np.cross(a.ax[i], b.ax[j])
            n = np.linalg.norm(x)
            if n > 1e-6:
                axes.append(x / n)
    best = 1e9
    for L in axes:
        ra = float(np.abs(a.ax @ L) @ a.h)
        rb = float(np.abs(b.ax @ L) @ b.h)
        best = min(best, ra + rb - abs(float(d @ L)))
    return best


def box_inside(a, b, tol=0.05):
    """True when every corner of box [a] lies inside box [b] (a fitted into b: a shell round something, or a pin in it)."""
    corners = a.c + (np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)], float) * a.h) @ a.ax
    local = (corners - b.c) @ b.ax.T
    return bool(np.all(np.abs(local) <= b.h + tol))
