"""
Combat animation kit. Clips are written as keyframes on a small skeleton (root, torso, head, arms, legs),
interpolated with easing and quaternion slerp, and baked to samples the game plays back (CombatAnimator
and FirstPersonCombat). The same forward kinematics as the game poses a player model holding a sword, and
contact sheets show each clip from the side, from three quarters, and through the player's own eyes.

Model space is Minecraft's: pixels, y down, the character faces -z and its right hand is at -x.
"""
import json, math, os
import numpy as np
from PIL import Image, ImageDraw

# ---------------------------------------------------------------- quaternions (x, y, z, w), as JOML

def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([aw * bx + ax * bw + ay * bz - az * by,
                     aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw,
                     aw * bw - ax * bx - ay * by - az * bz])

def qaxis(axis, deg):
    a = np.asarray(axis, float)
    a = a / np.linalg.norm(a)
    h = math.radians(deg) / 2
    return np.array([*(a * math.sin(h)), math.cos(h)])

I = np.array([0.0, 0.0, 0.0, 1.0])

def qeuler(x=0.0, y=0.0, z=0.0):
    """Degrees, in the order a model part turns: about x, then y, then z (rotationZYX)."""
    return qmul(qmul(qaxis((0, 0, 1), z), qaxis((0, 1, 0), y)), qaxis((1, 0, 0), x))

def qmat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)],
                     [2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
                     [2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)]])

def qfrommat(m):
    t = m[0, 0] + m[1, 1] + m[2, 2]
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        q = [(m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s]
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = math.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        q = [0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s]
    elif m[1, 1] > m[2, 2]:
        s = math.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        q = [(m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s]
    else:
        s = math.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        q = [(m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s]
    q = np.array(q)
    return q / np.linalg.norm(q)

def qslerp(a, b, t):
    a = np.asarray(a, float); b = np.asarray(b, float)
    d = float(np.dot(a, b))
    if d < 0:
        b = -b; d = -d
    if d > 0.9995:
        r = a + (b - a) * t
        return r / np.linalg.norm(r)
    th = math.acos(min(1.0, d))
    s = math.sin(th)
    return a * (math.sin((1 - t) * th) / s) + b * (math.sin(t * th) / s)

def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)

# Directions in the character's own frame (or its torso's)
F = np.array([0.0, 0.0, -1.0])   # forward
B = -F
U = np.array([0.0, -1.0, 0.0])   # up
D = -U
R = np.array([-1.0, 0.0, 0.0])   # the character's right
L = -R

def v(f=0.0, u=0.0, r=0.0):
    """A direction: so much forward, up and to the right (negatives for back, down, left)."""
    return unit(f * F + u * U + r * R)

def frame_to(a_from, b_from, a_to, b_to):
    """The rotation taking direction a_from to a_to, and b_from (made square to a) as near b_to as it goes."""
    def basis(a, b):
        a = unit(a)
        b = unit(b - np.dot(b, a) * a)
        return np.column_stack([a, b, np.cross(a, b)])
    return qfrommat(basis(a_to, b_to) @ basis(a_from, b_from).T)

# ---------------------------------------------------------------- the held sword

def T(x, y, z):
    m = np.eye(4); m[:3, 3] = (x, y, z); return m

def Rq(q):
    m = np.eye(4); m[:3, :3] = qmat(q); return m

def S(s):
    m = np.eye(4); m[0, 0] = m[1, 1] = m[2, 2] = s; return m

def rot_xyz(x, y, z):
    """JOML rotationXYZ in degrees (item display transforms)."""
    return qmul(qmul(qaxis((1, 0, 0), x), qaxis((0, 1, 0), y)), qaxis((0, 0, 1), z))

def apply(m, p):
    return (m @ np.array([*p, 1.0]))[:3]

# Points on the sword sprite, in item model space (0..1, y up)
GRIP_ITEM = (0.19, 0.21, 0.5)
TIP_ITEM = (0.97, 0.97, 0.5)

def third_person_item(left=False):
    """Arm-local transform (blocks) from item space, as ItemInHandLayer and the handheld model place a sword."""
    sx = -1 if left else 1
    disp = rot_xyz(0, 90, -55) if left else rot_xyz(0, -90, 55)
    return (Rq(qaxis((1, 0, 0), -90)) @ Rq(qaxis((0, 1, 0), 180)) @ T(sx / 16, 2 / 16, -10 / 16)
            @ T(0, 4 / 16, 0.5 / 16) @ Rq(disp) @ S(0.85) @ T(-0.5, -0.5, -0.5))

FP_BASE = np.array([0.56, -0.52, -0.72])

def first_person_display():
    return T(1.13 / 16, 3.2 / 16, 1.13 / 16) @ Rq(rot_xyz(0, -90, 25)) @ S(0.68) @ T(-0.5, -0.5, -0.5)

# The sword in its vanilla first-person place, measured from FP_BASE: the game turns it about FP_GRIP
FP_GRIP = apply(first_person_display(), GRIP_ITEM)
FP_BLADE = unit(apply(first_person_display(), TIP_ITEM) - FP_GRIP)
FP_FLAT = unit(apply(first_person_display(), (0.5, 0.5, 1.0)) - apply(first_person_display(), (0.5, 0.5, 0.0)))
FP_EDGE = unit(np.cross(FP_FLAT, FP_BLADE))

# The sword in the arm's own frame (third person): blade and edge directions
_TP = third_person_item()
TP_BLADE = unit(apply(_TP, TIP_ITEM) - apply(_TP, GRIP_ITEM))

# Camera-space directions for first person: x right, y up, -z ahead
VF = np.array([0.0, 0.0, -1.0]); VU = np.array([0.0, 1.0, 0.0]); VR = np.array([1.0, 0.0, 0.0])

def vv(f=0.0, u=0.0, r=0.0):
    return unit(f * VF + u * VU + r * VR)

def fp(hand, blade, edge=None):
    """First-person item key: the grip at [hand] (camera space, blocks), the blade along [blade] and its
    cutting edge facing [edge]. Returns (offset, rotation) the way FirstPersonCombat applies them."""
    blade = unit(blade)
    if edge is None:
        edge = FP_EDGE
    q = frame_to(FP_BLADE, FP_EDGE, blade, edge)
    offset = np.asarray(hand, float) - FP_BASE - FP_GRIP
    return offset, q

def fp_rest():
    return FP_BASE + FP_GRIP

# ---------------------------------------------------------------- arms

def arm(direction, blade, left=False):
    """Arm rotation (relative to the torso) pointing the arm along [direction] with the sword's blade
    turned as near [blade] as the grip allows. The blade sits square-ish to the arm, like a fist round a hilt."""
    blade_local = TP_BLADE if not left else TP_BLADE * np.array([-1, 1, 1])
    return frame_to(np.array([0.0, 1.0, 0.0]), blade_local, direction, blade)

def free_arm(direction, palm=None):
    """A hand with nothing in it: just point the arm."""
    palm = F if palm is None else palm
    return frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), direction, palm)

# ---------------------------------------------------------------- clips

EASE = {
    'linear': lambda a: a,
    'in': lambda a: a * a,
    'out': lambda a: 1 - (1 - a) * (1 - a),
    'in3': lambda a: a * a * a,
    'out3': lambda a: 1 - (1 - a) ** 3,
    'inout': lambda a: a * a * (3 - 2 * a),
    'step': lambda a: 0.0 if a < 1 else 1.0,
    'outback': lambda a: 1 + 2.2 * (a - 1) ** 3 + 1.2 * (a - 1) ** 2,
}

BONES = ['root', 'torso', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg']

class Clip:
    def __init__(self, name, length, blend_in=1.0, blend_out=3.0, rate=2):
        self.name, self.length, self.blend_in, self.blend_out, self.rate = name, length, blend_in, blend_out, rate
        self.keys = {}    # bone -> [(t, q, pos, ease)]
        self.modes = {}   # bone -> 'set' | 'add'
        self.marks = {}   # label -> tick, drawn on the contact sheet

    def key(self, bone, t, q=None, pos=None, ease='inout'):
        q = I.copy() if q is None else np.asarray(q, float)
        self.keys.setdefault(bone, []).append((float(t), q, None if pos is None else np.asarray(pos, float), ease))
        self.keys[bone].sort(key=lambda k: k[0])
        return self

    def mode(self, bone, mode):
        self.modes[bone] = mode
        return self

    def mark(self, label, t):
        self.marks[label] = t
        return self

    def has_pos(self, bone):
        return any(k[2] is not None for k in self.keys.get(bone, []))

    def sample(self, bone, t):
        keys = self.keys.get(bone)
        if not keys:
            return I.copy(), np.zeros(3)
        def pos(k):
            return k[2] if k[2] is not None else np.zeros(3)
        if t <= keys[0][0]:
            return keys[0][1], pos(keys[0])
        for a, b in zip(keys, keys[1:]):
            if t <= b[0]:
                span = b[0] - a[0]
                x = 0.0 if span <= 0 else (t - a[0]) / span
                e = EASE[b[3]](x)
                return qslerp(a[1], b[1], e), pos(a) + (pos(b) - pos(a)) * e
        return keys[-1][1], pos(keys[-1])

    def weight(self, t):
        def smooth(x): return x * x * (3 - 2 * x)
        fi = min(1.0, max(0.0, t / self.blend_in)) if self.blend_in > 0 else 1.0
        fo = min(1.0, max(0.0, (self.length - t) / self.blend_out)) if self.blend_out > 0 else 1.0
        return smooth(fi) * smooth(fo)

    def bake(self):
        n = int(math.floor(self.length * self.rate)) + 1
        out = {'length': self.length, 'rate': self.rate, 'blend_in': self.blend_in, 'blend_out': self.blend_out, 'bones': {}}
        for bone in self.keys:
            rot, pos = [], []
            prev = None
            for i in range(n):
                q, p = self.sample(bone, i / self.rate)
                if prev is not None and np.dot(prev, q) < 0:
                    q = -q   # keep neighbours on the same side so the game's slerp takes the short way
                prev = q
                rot += [round(float(c), 4) for c in q]
                pos += [round(float(c), 3) for c in p]
            entry = {'rot': rot}
            if self.has_pos(bone):
                entry['pos'] = pos
            if self.modes.get(bone, 'set') == 'add':
                entry['mode'] = 'add'
            out['bones'][bone] = entry
        return out

    def write(self, assets_dir, group):
        path = os.path.join(assets_dir, 'animations', group, self.name + '.json')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            json.dump(self.bake(), f, separators=(',', ':'))
        return path

# ---------------------------------------------------------------- forward kinematics (as CombatAnimator)

PIVOTS = {'head': (0, 0, 0), 'body': (0, 0, 0), 'right_arm': (-5, 2, 0), 'left_arm': (5, 2, 0),
          'right_leg': (-1.9, 12, 0), 'left_leg': (1.9, 12, 0)}
HIPS = np.array([0.0, 12.0, 0.0])

def vanilla_pose(item_pose=True, pivots=None):
    """What vanilla shows standing still with a sword: everything at rest, the sword arm a little raised.
    [pivots] are the model's joints, when they aren't a player's."""
    pivots = pivots or PIVOTS
    pose = {p: (I.copy(), np.array(pivots[p], float)) for p in pivots}
    if item_pose:
        pose['right_arm'] = (qeuler(x=-18), pose['right_arm'][1])
    pose['root'] = (I.copy(), np.zeros(3))
    return pose

def pose_clip(clip, t, base=None, yaw_deg=0.0, pivots=None, hips=None):
    """Part rotations and pivots (root space) for [clip] at [t], blended over [base] by the clip's weight.
    [pivots] and [hips] are the model's joints, when they aren't a player's (CombatAnimator reads them off
    the model the same way)."""
    pivots = pivots or PIVOTS
    hips = HIPS if hips is None else np.asarray(hips, float)
    base = base or vanilla_pose(pivots=pivots)
    w = clip.weight(t)
    qt, pt = clip.sample('torso', t)
    out = {}
    def blend(name, q, p):
        bq, bp = base[name]
        out[name] = (qslerp(bq, q, w), bp + (p - bp) * w)
    def attached(pivot, offset=np.zeros(3)):
        return hips + qmat(qt) @ (np.array(pivot, float) + offset - hips) + pt
    blend('body', qt, attached(pivots['body']))
    for a in ('right_arm', 'left_arm'):
        bq = base[a][0]
        if a in clip.keys:
            qa, pa = clip.sample(a, t)
            q = qmul(qmul(qt, bq), qa) if clip.modes.get(a) == 'add' else qmul(qt, qa)
        else:
            q, pa = qmul(qt, bq), np.zeros(3)
        blend(a, q, attached(pivots[a], pa))
    bq = base['head'][0]
    if 'head' in clip.keys:
        qh, _ = clip.sample('head', t)
        q = qmul(bq, qh) if clip.modes.get('head', 'add') == 'add' else qmul(qt, qh)
    else:
        q = bq
    blend('head', q, attached(pivots['head']))
    for leg in ('right_leg', 'left_leg'):
        if leg in clip.keys:
            ql, pl = clip.sample(leg, t)
            q = qmul(base[leg][0], ql) if clip.modes.get(leg) == 'add' else ql
            blend(leg, q, np.array(pivots[leg], float) + pl)
        else:
            out[leg] = base[leg]
    qr, pr = clip.sample('root', t)
    qr = qslerp(I, qr, w)
    if yaw_deg:
        qr = qmul(qaxis((0, 1, 0), yaw_deg * w), qr)
    pr = pr * w
    out['root'] = (qr, hips - qmat(qr) @ hips + pr)
    return out

# ---------------------------------------------------------------- drawing

FACE_LIGHT = {'top': 1.0, 'front': 0.92, 'back': 0.62, 'right': 0.78, 'left': 0.82, 'bottom': 0.5}

def box_quads(origin, size, uv, inflate=0.0):
    """The six faces of a box-UV cube: (A, B, D, texture rect, face) with A-B along u and A-D along v."""
    g = inflate
    x0, y0, z0 = [o - g for o in origin]
    x1, y1, z1 = [o + s + g for o, s in zip(origin, size)]
    u, vv_ = uv
    w, h, d = [int(math.ceil(s)) for s in size]
    rect = {'top': (u + d, vv_, w, d), 'bottom': (u + d + w, vv_, w, d), 'right': (u, vv_ + d, d, h),
            'front': (u + d, vv_ + d, w, h), 'left': (u + d + w, vv_ + d, d, h), 'back': (u + 2 * d + w, vv_ + d, w, h)}
    P = lambda x, y, z: np.array([x, y, z], float)
    return [
        (P(x0, y0, z0), P(x1, y0, z0), P(x0, y1, z0), rect['front'], 'front'),
        (P(x1, y0, z1), P(x0, y0, z1), P(x1, y1, z1), rect['back'], 'back'),
        (P(x0, y0, z1), P(x0, y0, z0), P(x0, y1, z1), rect['right'], 'right'),
        (P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z0), rect['left'], 'left'),
        (P(x0, y0, z1), P(x1, y0, z1), P(x0, y0, z0), rect['top'], 'top'),
        (P(x0, y1, z0), P(x1, y1, z0), P(x0, y1, z1), rect['bottom'], 'bottom'),
    ]

PLAYER = {
    'head': [((-4, -8, -4), (8, 8, 8), (0, 0), 0.0), ((-4, -8, -4), (8, 8, 8), (32, 0), 0.5)],
    'body': [((-4, 0, -2), (8, 12, 4), (16, 16), 0.0), ((-4, 0, -2), (8, 12, 4), (16, 32), 0.25)],
    'right_arm': [((-3, -2, -2), (4, 12, 4), (40, 16), 0.0), ((-3, -2, -2), (4, 12, 4), (40, 32), 0.25)],
    'left_arm': [((-1, -2, -2), (4, 12, 4), (32, 48), 0.0), ((-1, -2, -2), (4, 12, 4), (48, 48), 0.25)],
    'right_leg': [((-2, 0, -2), (4, 12, 4), (0, 16), 0.0), ((-2, 0, -2), (4, 12, 4), (0, 32), 0.25)],
    'left_leg': [((-2, 0, -2), (4, 12, 4), (16, 48), 0.0), ((-2, 0, -2), (4, 12, 4), (0, 48), 0.25)],
}

def sprite_quads(sub=1):
    """The flat sword sprite as front and back faces (item space), split for perspective views."""
    quads = []
    for z in (7.5 / 16, 8.5 / 16):
        for i in range(sub):
            for j in range(sub):
                x0, x1 = i / sub, (i + 1) / sub
                y0, y1 = 1 - j / sub, 1 - (j + 1) / sub
                quads.append((np.array([x0, y0, z]), np.array([x1, y0, z]), np.array([x0, y1, z]),
                              (16 * x0, 16 * j / sub, 16 / sub, 16 / sub), 'front'))
    return quads

class Scene:
    def __init__(self, skin, sword):
        self.skin = np.array(skin.convert('RGBA'), float)
        self.sword = np.array(sword.convert('RGBA'), float)

    def at(self, clip, t):
        """Told which clip and moment it's about to draw, for parts that come and go."""

    def player_world(self, pose, sword=True):
        """Every quad of the posed player (and sword) in model space, with its texture."""
        quads = []
        qr, pr = pose['root']
        Mroot = T(*pr) @ Rq(qr)
        for part, cubes in PLAYER.items():
            q, p = pose[part]
            M = Mroot @ T(*p) @ Rq(q)
            for origin, size, uv, g in cubes:
                for A, Bq, Dq, rect, face in box_quads(origin, size, uv, g):
                    quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), rect, FACE_LIGHT[face], self.skin))
        if sword:
            q, p = pose['right_arm']
            M = Mroot @ T(*p) @ Rq(q) @ S(16) @ third_person_item()
            for A, Bq, Dq, rect, face in sprite_quads():
                quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), rect, 0.95, self.sword))
        return quads

    def sword_points(self, pose):
        qr, pr = pose['root']
        q, p = pose['right_arm']
        M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q) @ S(16) @ third_person_item()
        return apply(M, GRIP_ITEM), apply(M, TIP_ITEM)

def raster(quads, project, size, bg=(58, 64, 80)):
    W, H = size
    img = np.zeros((H, W, 3), float); img[...] = bg
    zbuf = np.full((H, W), 1e9)
    ys, xs = np.mgrid[0:H, 0:W]
    for A, Bq, Dq, (u, v_, w, h), light, tex in quads:
        a, b, d = project(A), project(Bq), project(Dq)
        if a is None or b is None or d is None:
            continue
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        corners = [a, b, d, b + e2]
        x0 = int(max(0, min(c[0] for c in corners))); x1 = int(min(W - 1, max(c[0] for c in corners) + 1))
        y0 = int(max(0, min(c[1] for c in corners))); y1 = int(min(H - 1, max(c[1] for c in corners) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]; py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        tu = np.clip(np.floor(u + s * w).astype(int), 0, tex.shape[1] - 1)
        tv = np.clip(np.floor(v_ + t * h).astype(int), 0, tex.shape[0] - 1)
        texel = tex[tv, tu]
        ok = inside & (texel[..., 3] > 100) & (depth < zbuf[y0:y1, x0:x1])
        region = img[y0:y1, x0:x1]
        region[ok] = texel[..., :3][ok] * light
        zbuf[y0:y1, x0:x1][ok] = depth[ok]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')

def ortho(yaw, pitch, scale, size, center_y=12.0):
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    W, H = size
    def project(p):
        x, y, z = p
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        return np.array([W / 2 + x * scale, H / 2 + (y - center_y) * scale, z])
    return project

def perspective(size, fov=70.0):
    W, H = size
    f = (H / 2) / math.tan(math.radians(fov / 2))
    def project(p):
        x, y, z = p
        if z > -0.05:
            return None
        return np.array([W / 2 + f * x / -z, H / 2 - f * y / -z, -z])
    return project

def first_person_quads(scene, offset, q):
    M = T(*FP_BASE) @ T(*offset) @ T(*FP_GRIP) @ Rq(q) @ T(*(-FP_GRIP)) @ first_person_display()
    return [(apply(M, A), apply(M, Bq), apply(M, Dq), rect, 0.95, scene.sword) for A, Bq, Dq, rect, face in sprite_quads(4)]

def fp_sample(clip, t):
    """The first-person item transform at [t], as FirstPersonCombat blends it."""
    w = clip.weight(t)
    q, p = clip.sample('item', t)
    return p * w, qslerp(I, q, w)

def sheet(scene, clip, fp_clip=None, times=None, yaw_deg=0.0, cell=(190, 230), title=None, zoom=4.0, center_y=6.0):
    """Rows: three-quarter view, from above, and through the player's own eyes. Columns: moments through
    the clip. The yellow line is the path of the sword's tip so far. A scene with a [skeleton] (its own
    pivots and hips) is posed with it; [zoom] and [center_y] frame bigger creatures."""
    if times is None:
        n = 9
        times = [clip.length * i / (n - 1) for i in range(n)]
    W, H = cell
    FH = int(W * 9 / 16)
    views = [ortho(35, -14, zoom, (W, H), center_y), ortho(0, 90, zoom, (W, int(H * 0.8)), 0.0)]
    skeleton = getattr(scene, 'skeleton', {})
    heights = [H, int(H * 0.8)]
    rows_h = sum(heights) + (FH if fp_clip is not None else 0)
    out = Image.new('RGB', (W * len(times), rows_h + 18), (30, 32, 40))
    draw = ImageDraw.Draw(out)
    draw.text((4, 3), title or clip.name, fill=(235, 235, 235))
    path = []
    k = 0
    while k / 4 <= clip.length:
        scene.at(clip, k / 4)
        path.append((k / 4, scene.sword_points(pose_clip(clip, k / 4, yaw_deg=yaw_deg, **skeleton))[1]))
        k += 1
    for i, t in enumerate(times):
        scene.at(clip, t)
        pose = pose_clip(clip, t, yaw_deg=yaw_deg, **skeleton)
        quads = scene.player_world(pose)
        y = 18
        for project, h in zip(views, heights):
            img = raster(quads, project, (W, h))
            d = ImageDraw.Draw(img)
            pts = [tuple(project(p)[:2]) for tt, p in path if tt <= t]
            if len(pts) > 1:
                d.line(pts, fill=(255, 200, 80), width=2)
            out.paste(img, (i * W, y))
            y += h
        if fp_clip is not None:
            off, q = fp_sample(fp_clip, t)
            img = raster(first_person_quads(scene, off, q), perspective((W, FH)), (W, FH), bg=(70, 92, 120))
            out.paste(img, (i * W, y))
        label = f't={t:.1f}'
        for name, mt in clip.marks.items():
            if abs(mt - t) < clip.length / 16:
                label += ' ' + name
        draw.text((i * W + 4, 18 + 3), label, fill=(255, 255, 255))
        draw.line([(i * W, 18), (i * W, rows_h + 18)], fill=(20, 20, 26))
    return out
