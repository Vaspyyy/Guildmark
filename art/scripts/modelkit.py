"""
A tiny Blockbench stand-in: models are written as parts and box-UV cubes in Minecraft model space
(y down, front is -z, character's right is -x). From one definition it emits the Kotlin LayerDefinition,
paints the texture (each cube paints its own faces), writes a .bbmodel Blockbench can open, and renders
a preview so the result can be checked by eye.
"""
import base64, io, json, math, random, uuid
import numpy as np
from PIL import Image

FACES = ['right', 'front', 'left', 'back', 'top', 'bottom']

# The Direction Minecraft gives each face of a cube (model space has y down, so the top is DOWN)
DIRECTIONS = {'right': 'WEST', 'front': 'NORTH', 'left': 'EAST', 'back': 'SOUTH', 'top': 'DOWN', 'bottom': 'UP'}

class Cube:
    def __init__(self, origin, size, paint=None, inflate=0.0, mirror=False, name=None, faces=None):
        """[faces], if given, is the only faces drawn (a flat card is one face, seen from both sides)."""
        self.origin, self.size, self.paint, self.inflate, self.mirror, self.name = origin, size, paint, inflate, mirror, name
        self.faces = faces
        if faces and inflate:
            raise SystemExit(f'{name}: a cube with only some faces drawn cannot be inflated')
        self.uv = None

    def shows(self, face):
        return self.faces is None or face in self.faces

    def footprint(self):
        w, h, d = [math.ceil(s) for s in self.size]
        return 2 * d + 2 * w, d + h

    def rects(self):
        u, v = self.uv
        w, h, d = [math.ceil(s) for s in self.size]
        r = {
            'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
            'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h),
        }
        if self.mirror:
            r['right'], r['left'] = r['left'], r['right']
        return r

class Part:
    def __init__(self, name, pivot=(0, 0, 0), rot=(0, 0, 0), cubes=(), children=()):
        self.name, self.pivot, self.rot = name, pivot, rot
        self.cubes, self.children = list(cubes), list(children)

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

class Model:
    def __init__(self, name, tex_w, tex_h, parts):
        self.name, self.tex_w, self.tex_h, self.parts = name, tex_w, tex_h, parts

    def all_parts(self):
        for p in self.parts:
            yield from p.walk()

    def all_cubes(self):
        for p in self.all_parts():
            for c in p.cubes:
                yield c

    def pack(self):
        """Shelf-pack every cube's box UV into the texture."""
        cubes = sorted(self.all_cubes(), key=lambda c: -c.footprint()[1])
        x = y = shelf = 0
        for c in cubes:
            fw, fh = c.footprint()
            if x + fw > self.tex_w:
                x, y, shelf = 0, y + shelf, 0
            c.uv = (x, y)
            x += fw
            shelf = max(shelf, fh)
        if y + shelf > self.tex_h:
            raise SystemExit(f'{self.name}: texture too small, need {y + shelf} rows')

    # ---- texture ----
    def paint(self, painters_override=None):
        img = Image.new('RGBA', (self.tex_w, self.tex_h), (0, 0, 0, 0))
        for c in self.all_cubes():
            p = (painters_override or {}).get(c.name, c.paint) if c.name else c.paint
            if p is None:
                continue
            for face, (x, y, w, h) in c.rects().items():
                if c.shows(face): p(img, face, x, y, w, h)
        return img

    # ---- Kotlin ----
    def kotlin(self):
        out = ['        val mesh = MeshDefinition()', '        val root = mesh.root']
        def f(v):
            s = ('%.3f' % v).rstrip('0').rstrip('.')
            return (s if s not in ('-0', '') else '0') + 'f'
        def emit(part, parent):
            var = part.name if part.children else None
            b = 'CubeListBuilder.create()'
            for c in part.cubes:
                b += f'.texOffs({c.uv[0]}, {c.uv[1]})'
                if c.mirror: b += '.mirror()'
                x, y, z = c.origin; w, h, d = c.size
                deform = f', CubeDeformation({f(c.inflate)})' if c.inflate else ''
                if c.faces:
                    deform = ', EnumSet.of(' + ', '.join(f'Direction.{DIRECTIONS[n]}' for n in FACES if n in c.faces) + ')'
                b += f'.addBox({f(x)}, {f(y)}, {f(z)}, {f(w)}, {f(h)}, {f(d)}{deform})'
                if c.mirror: b += '.mirror(false)'
            px, py, pz = part.pivot
            rx, ry, rz = [math.radians(a) for a in part.rot]
            pose = (f'PartPose.offsetAndRotation({f(px)}, {f(py)}, {f(pz)}, {f(rx)}, {f(ry)}, {f(rz)})'
                    if any(part.rot) else f'PartPose.offset({f(px)}, {f(py)}, {f(pz)})')
            call = f'{parent}.addOrReplaceChild("{part.name}", {b}, {pose})'
            out.append(f'        val {var} = {call}' if var else f'        {call}')
            for ch in part.children:
                emit(ch, var)
        for p in self.parts:
            emit(p, 'root')
        out.append(f'        return LayerDefinition.create(mesh, {self.tex_w}, {self.tex_h})')
        return '\n'.join(out)

    # ---- geometry ----
    def world_faces(self, pose=None):
        """Every cube face in model space: (corners A, B along u, D along v), uv rect, face, normal. [pose] can
        give a part its own local rotation matrix and an offset to its pivot: {name: (matrix, offset)}."""
        faces = []
        pose = pose or {}
        def rotm(rot):
            rx, ry, rz = [math.radians(a) for a in rot]
            X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
            Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
            Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
            return Z @ Y @ X
        def go(part, M, T):
            local, offset = pose.get(part.name, (None, None))
            R = M @ (rotm(part.rot) if local is None else local)
            P = T + M @ (np.array(part.pivot, float) + (0 if offset is None else np.asarray(offset, float)))
            for c in part.cubes:
                g = c.inflate
                x0, y0, z0 = [o - g for o in c.origin]
                x1, y1, z1 = [o + s + g for o, s in zip(c.origin, c.size)]
                def W(x, y, z): return P + R @ np.array([x, y, z], float)
                quads = {
                    'front': (W(x0, y0, z0), W(x1, y0, z0), W(x0, y1, z0), (0, 0, -1)),
                    'back': (W(x1, y0, z1), W(x0, y0, z1), W(x1, y1, z1), (0, 0, 1)),
                    'right': (W(x0, y0, z1), W(x0, y0, z0), W(x0, y1, z1), (-1, 0, 0)),
                    'left': (W(x1, y0, z0), W(x1, y0, z1), W(x1, y1, z0), (1, 0, 0)),
                    'top': (W(x0, y0, z1), W(x1, y0, z1), W(x0, y0, z0), (0, -1, 0)),
                    'bottom': (W(x0, y1, z0), W(x1, y1, z0), W(x0, y1, z1), (0, 1, 0)),
                }
                rects = c.rects()
                for face, (A, B, D, n) in quads.items():
                    if not c.shows(face): continue
                    if c.mirror and face in ('front', 'back', 'top', 'bottom'):
                        A, B = B, A  # mirrored cubes flip the texture across u
                    faces.append((A, B, D, rects[face], face, R @ np.array(n, float)))
            for ch in part.children:
                go(ch, R, P)
        for p in self.parts:
            go(p, np.eye(3), np.zeros(3))
        return faces

    def render(self, tex, yaw=30, pitch=-12, scale=8, size=(256, 320), glow=None, y_shift=0.0, faces=None):
        img = np.zeros((size[1], size[0], 4), float)
        img[..., :3] = (58, 64, 80); img[..., 3] = 255
        zbuf = np.full((size[1], size[0]), 1e9)
        T = np.array(tex.convert('RGBA'), float)
        G = np.array(glow.convert('RGBA'), float) if glow else None
        cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        def view(p):
            x, y, z = p
            x, z = x * cy - z * sy, x * sy + z * cy
            y, z = y * cp - z * sp, y * sp + z * cp
            return np.array([size[0] / 2 + x * scale, size[1] * 0.08 + (y + 12 + y_shift) * scale * 0.9 + 20, z])
        light = {'top': 1.0, 'front': 0.9, 'back': 0.6, 'right': 0.75, 'left': 0.8, 'bottom': 0.5}
        ys, xs = np.mgrid[0:size[1], 0:size[0]]
        for A, B, D, (u, v, w, h), face, n in (faces if faces is not None else self.world_faces()):
            if w == 0 or h == 0: continue
            a, b, d = view(A), view(B), view(D)
            e1, e2 = b - a, d - a
            det = e1[0] * e2[1] - e1[1] * e2[0]
            if abs(det) < 1e-6: continue
            corners = [a, b, d, b + e2]
            x0 = int(max(0, min(c[0] for c in corners))); x1 = int(min(size[0] - 1, max(c[0] for c in corners) + 1))
            y0 = int(max(0, min(c[1] for c in corners))); y1 = int(min(size[1] - 1, max(c[1] for c in corners) + 1))
            if x0 >= x1 or y0 >= y1: continue
            px = xs[y0:y1, x0:x1] + 0.5 - a[0]; py = ys[y0:y1, x0:x1] + 0.5 - a[1]
            s = (px * e2[1] - py * e2[0]) / det
            t = (e1[0] * py - e1[1] * px) / det
            inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
            depth = a[2] + s * e1[2] + t * e2[2]
            tu = np.clip((u + s * w).astype(int), u, u + w - 1)
            tv = np.clip((v + t * h).astype(int), v, v + h - 1)
            texel = T[np.clip(tv, 0, T.shape[0] - 1), np.clip(tu, 0, T.shape[1] - 1)]
            ok = inside & (texel[..., 3] > 8) & (depth < zbuf[y0:y1, x0:x1])
            col = texel[..., :3] * light[face]
            if G is not None:
                gl = G[np.clip(tv, 0, G.shape[0] - 1), np.clip(tu, 0, G.shape[1] - 1)]
                col = np.where(gl[..., 3:4] > 8, gl[..., :3], col)
            region = img[y0:y1, x0:x1]
            alpha = texel[..., 3:4] / 255.0
            blended = col * alpha + region[..., :3] * (1 - alpha)
            region[..., :3] = np.where(ok[..., None], blended, region[..., :3])
            zb = zbuf[y0:y1, x0:x1]
            zb[ok & (texel[..., 3] > 150)] = depth[ok & (texel[..., 3] > 150)]
        return Image.fromarray(img.astype(np.uint8), 'RGBA')

    # ---- Blockbench ----
    def bbmodel(self, tex, tex_name):
        buf = io.BytesIO(); tex.save(buf, 'PNG')
        elements, outliner = [], []
        def go(part, parent_abs):
            P = [parent_abs[i] + part.pivot[i] for i in range(3)]
            group = {'name': part.name, 'origin': [-P[0], 24 - P[1], P[2]],
                     'rotation': [-part.rot[0], -part.rot[1], part.rot[2]],
                     'uuid': str(uuid.uuid4()), 'export': True, 'isOpen': False, 'visibility': True, 'children': []}
            for c in part.cubes:
                mn = [P[i] + c.origin[i] for i in range(3)]
                mx = [mn[i] + c.size[i] for i in range(3)]
                el = {'name': c.name or part.name, 'box_uv': True, 'rescale': False, 'locked': False,
                      'from': [-mx[0], 24 - mx[1], mn[2]], 'to': [-mn[0], 24 - mn[1], mx[2]],
                      'autouv': 0, 'color': 0, 'inflate': c.inflate, 'mirror_uv': c.mirror,
                      'origin': group['origin'], 'uv_offset': list(c.uv), 'faces': {}, 'type': 'cube',
                      'uuid': str(uuid.uuid4())}
                for fname, face in [('north', 'front'), ('east', 'right'), ('south', 'back'), ('west', 'left'), ('up', 'top'), ('down', 'bottom')]:
                    el['faces'][fname] = {'uv': [0, 0, 0, 0], 'texture': 0 if c.shows(face) else None}
                elements.append(el)
                group['children'].append(el['uuid'])
            for ch in part.children:
                group['children'].append(go(ch, P))
            return group
        for p in self.parts:
            outliner.append(go(p, [0, 0, 0]))
        return {
            'meta': {'format_version': '4.10', 'model_format': 'modded_entity', 'box_uv': True},
            'name': self.name, 'model_identifier': self.name, 'visible_box': [1, 1, 0],
            'resolution': {'width': self.tex_w, 'height': self.tex_h},
            'elements': elements, 'outliner': outliner,
            'textures': [{'name': tex_name, 'id': '0', 'width': self.tex_w, 'height': self.tex_h,
                          'uv_width': self.tex_w, 'uv_height': self.tex_h, 'render_mode': 'default',
                          'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode(),
                          'uuid': str(uuid.uuid4())}],
        }

# ---- painting helpers ----
rnd = random.Random(7)

def shade(col, k):
    return tuple(max(0, min(255, int(c + k))) for c in col[:3]) + ((col[3],) if len(col) > 3 else (255,))

FACE_LIGHT = {'top': 10, 'bottom': -22, 'front': 0, 'back': -8, 'right': -5, 'left': -5}

def fill(base, noise=6, face_light=True):
    """Paint every face a flat colour with a little grain."""
    def p(img, face, x, y, w, h):
        k = FACE_LIGHT[face] if face_light else 0
        for j in range(h):
            for i in range(w):
                img.putpixel((x + i, y + j), shade(base, k + rnd.randint(-noise, noise)))
    return p

def grid(rows, palette, default=None, faces=None):
    """Paint from character grids. rows: dict face -> list of strings (scaled to the face if needed)."""
    def p(img, face, x, y, w, h):
        g = rows.get(face) if faces is None or face in faces else None
        if g is None:
            if default: default(img, face, x, y, w, h)
            return
        gh, gw = len(g), len(g[0])
        for j in range(h):
            for i in range(w):
                ch = g[min(gh - 1, j * gh // h)][min(gw - 1, i * gw // w)]
                if ch == ' ' or ch not in palette:
                    if default is None: continue
                    # Let the default paint just this pixel
                    tmp = Image.new('RGBA', (1, 1)); default(tmp, face, 0, 0, 1, 1)
                    img.putpixel((x + i, y + j), tmp.getpixel((0, 0)))
                    continue
                c = palette[ch]
                # Four-tuple colours are exact (glow and transparency), three-tuples get shading and grain
                img.putpixel((x + i, y + j), c if len(c) == 4 else shade(c, FACE_LIGHT[face] // 2 + rnd.randint(-3, 3)))
    return p

def layered(*painters):
    def p(img, face, x, y, w, h):
        for q in painters: q(img, face, x, y, w, h)
    return p

def rows_band(colors_by_row, noise=5):
    """Paint side faces in horizontal bands (list of (fraction_end, colour)) top to bottom."""
    def p(img, face, x, y, w, h):
        for j in range(h):
            frac = (j + 0.5) / h
            col = next(c for f, c in colors_by_row if frac <= f)
            for i in range(w):
                img.putpixel((x + i, y + j), shade(col, FACE_LIGHT[face] + rnd.randint(-noise, noise)))
    return p

def only(faces, painter):
    def p(img, face, x, y, w, h):
        if face in faces: painter(img, face, x, y, w, h)
    return p

def save_all(model, tex, outdir, kotlin_path=None, glow=None, previews=None):
    import os
    os.makedirs(outdir, exist_ok=True)
    json.dump(model.bbmodel(tex, model.name), open(f'{outdir}/{model.name}.bbmodel', 'w'))
