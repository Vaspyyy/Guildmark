"""
The dragon's clips. Its parts nest (art/scripts/dragon.py), so every clip keys parts by name and the game's rig animator
(client/combat/RigAnimator.kt) turns each part from its rest pose ('set') or from where the layers under it left it
('add'), and moves it by an offset in pixels. Run:

    python3 -B art/scripts/dragon_anims.py src/main/resources/assets/guildmark <preview-dir> [names]

Clips are written as poses: a Rig (rig below) holds the body, hips, neck, tail, jaw, wings and four legs as the game's
animator would, a clip is a function of time that returns the Rig, and the baker samples it twice a tick. Legs are
placed by two-bone IK on feet that stay where they are put on the ground (the walk's feet do not skate), wings are
blends of key poses with a lag down the arm, and the neck can be steered to put the jaw tip on a point.

How the game layers them (DragonModel.kt, the lead's): the ground base is 'idle' by time blended into 'walk' by
speed (walk plays by distance, one loop per WALK_STRIDE blocks), 'sleep' by time when asleep; the air base is
'hover', 'fly' and 'glide' blended by speed and climb. An attack clip goes on top (blend in ~2, out ~6). Air attacks
(roar_air, air_breath, fireball, fireball_volley) never key the wings or legs; takeoff, land, dive, wake, enrage,
death and stagger key every part ('set'), since the base under them changes partway through.

Model space is the model's own: pixels, y down, the dragon faces -z and its right side is -x. The game draws it at
twice that size (1 px = 0.125 blocks) with y = 24 at its feet.
"""
import math, os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from animkit import Clip, EASE, qeuler, qmul, qaxis, qslerp, qmat, qfrommat, frame_to, unit, I
import dragon as dg

GROUP = 'dragon'
IDX = {p.name: p for p in dg.model.all_parts()}
PARENT = {}
for _p in dg.model.all_parts():
    for _ch in _p.children:
        PARENT[_ch.name] = _p.name
SCALE = 2.0            # the game draws the model at this scale
PX = SCALE / 16.0      # blocks per model pixel
GROUND = 24.0          # y of the ground the feet stand on

# ================================================================== the parts the clips key

NECK = ['neck1', 'neck2', 'neck3', 'head']
TAIL = ['tail1', 'tail2', 'tail3', 'tail4', 'tail5']
LEG_KEYS = ['fr', 'fl', 'hr', 'hl']
LEG_PARTS = {k: [f'leg_{k}', f'shin_{k}', f'foot_{k}'] for k in LEG_KEYS}
WING_R = ['wing_r', 'wing_r2', 'wing_r3', 'wing_r_f2', 'wing_r_f3']
WING_L = ['wing_l', 'wing_l2', 'wing_l3', 'wing_l_f2', 'wing_l_f3']
BODY_BONES = ['body', 'hips'] + NECK[:3] + ['head', 'jaw'] + TAIL
LEG_BONES = [p for k in LEG_KEYS for p in LEG_PARTS[k]]
WING_BONES = WING_R + WING_L
EVERYTHING = BODY_BONES + LEG_BONES + WING_BONES                  # the parts takeoff, land, dive, wake, enrage, stagger and death key
AIR_ATTACK = NECK[:3] + ['head', 'jaw']                           # what an air attack keys 'set' (no wings, no legs)
POS_BONES = {'body', 'hips', 'lids'}                              # parts a clip may move as well as turn


# ================================================================== math

def Rx(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], float)


def Ry(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], float)


def Rz(d):
    a = math.radians(d); c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], float)


def qinv(q):
    x, y, z, w = q
    return np.array([-x, -y, -z, w])


def mirror_q(q):
    """The same rotation on the other side of the dragon (x -> -x)."""
    x, y, z, w = q
    return np.array([x, -y, -z, w])


def qm(m):
    return qfrommat(m)


def V(*a):
    return np.array(a, float)


def lerp(a, b, k):
    return a + (b - a) * k


def smooth(a):
    a = min(1.0, max(0.0, a))
    return a * a * (3 - 2 * a)


def clamp(a, lo=0.0, hi=1.0):
    return max(lo, min(hi, a))


Y_AX = V(0.0, 1.0, 0.0)
X_AX = V(1.0, 0.0, 0.0)


def mframe(a_from, b_from, a_to, b_to):
    """The rotation taking direction a_from to a_to and b_from (made square to it) as near b_to as it goes."""
    return qmat(frame_to(np.asarray(a_from, float), np.asarray(b_from, float), np.asarray(a_to, float), np.asarray(b_to, float)))


REST_Q = {name: qeuler(*p.rot) for name, p in IDX.items()}
REST_M = {name: qmat(q) for name, q in REST_Q.items()}


def world_transforms(pose):
    """Each part's world rotation and pivot position. [pose] maps every part to (local rotation matrix, pivot offset)."""
    out = {}

    def go(p, R, T):
        local, off = pose[p.name]
        Rw = R @ local
        Tw = T + R @ (np.array(p.pivot, float) + off)
        out[p.name] = (Rw, Tw)
        for ch in p.children:
            go(ch, Rw, Tw)
    for p in dg.model.parts:
        go(p, np.eye(3), np.zeros(3))
    return out


REST_WT = world_transforms({b: (REST_M[b], np.zeros(3)) for b in IDX})


def about(R, point):
    """The offset that turns a part by [R] about [point] (in its parent's frame) rather than about its pivot."""
    p = np.asarray(point, float)
    return p - R @ p


# ================================================================== curves

def kf(t, keys, ease='inout'):
    """The value at [t] of keys [(time, value[, ease])]: floats or arrays, eased by the key each segment ends at."""
    if t <= keys[0][0]:
        return np.array(keys[0][1], float) if not np.isscalar(keys[0][1]) else float(keys[0][1])
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            span = b[0] - a[0]
            x = 1.0 if span <= 0 else (t - a[0]) / span
            e = EASE[b[2] if len(b) > 2 else ease](x)
            va, vb = a[1], b[1]
            return va + (vb - va) * e if np.isscalar(va) else np.asarray(va, float) + (np.asarray(vb, float) - np.asarray(va, float)) * e
    last = keys[-1][1]
    return float(last) if np.isscalar(last) else np.array(last, float)


def wave(p):
    """A cosine in [-1, 1] of the loop phase p (1 at 0)."""
    return math.cos(2 * math.pi * p)


def bump(a, lo=0.0, hi=1.0):
    """0 outside [lo, hi], a smooth hill inside (1 in the middle)."""
    if a <= lo or a >= hi:
        return 0.0
    return math.sin(math.pi * (a - lo) / (hi - lo)) ** 2


# ================================================================== the rig

def two_bone(H, A, L1, L2, pole, hind=False):
    """Hip H to ankle A through a knee bent toward [pole]: world rotations of the upper and lower segments (each hangs
    along its own +y, its +x square to the leg's plane), the knee and the ankle reached. As dragon.two_bone, with a way
    out when the pole lies along the leg."""
    H = np.asarray(H, float); A = np.asarray(A, float)
    d = A - H
    D = float(np.linalg.norm(d))
    e1 = d / max(D, 1e-6)
    D = min(max(D, abs(L1 - L2) + 1e-3), (L1 + L2) * 0.9995)
    pole = np.asarray(pole, float)
    p = pole - np.dot(pole, e1) * e1
    if np.linalg.norm(p) < 0.2:
        for alt in ((0, -1.0, 0), (1.0, 0, 0), (0, 0, -1.0)):
            p = np.asarray(alt, float) - np.dot(alt, e1) * e1
            if np.linalg.norm(p) > 0.2:
                break
    e2 = p / np.linalg.norm(p)
    a = (L1 * L1 - L2 * L2 + D * D) / (2 * D)
    h = math.sqrt(max(0.0, L1 * L1 - a * a))
    K = H + e1 * a + e2 * h
    A2 = H + e1 * D
    # the parts' own x axes run along the hinge, their z axes toward the elbow (away from it for a hind leg, whose knee
    # bends forward): with that fixed, a part turns smoothly about its own length as the elbow swings round it
    side = np.cross(e1, e2)
    if hind:
        side = -side
    R1 = mframe(Y_AX, X_AX, unit(K - H), side)
    R2 = mframe(Y_AX, X_AX, unit(A2 - K), side)
    return R1, R2, K, A2


class Leg:
    """What a leg is: its three parts, where the shoulder or hip is and how long its two segments are."""
    def __init__(self, key):
        self.key = key
        self.parts = LEG_PARTS[key]
        front = key[0] == 'f'
        self.front = front
        self.side = key[1]
        self.sgn = -1.0 if self.side == 'r' else 1.0
        leg, shin, foot = [IDX[n] for n in self.parts]
        self.L1, self.L2 = float(shin.pivot[1]), float(foot.pivot[1])
        self.parent = 'body' if front else 'hips'
        self.pole = V(0, 0, 1.0) if front else V(0, 0, -1.0)     # which way the elbow (front) or knee (hind) bends, in the parent's frame
        self.ankle = float(max(c.origin[1] + c.size[1] for c in foot.cubes))     # how far below its pivot the sole is
        self.stand = REST_WT[foot.name][1].copy()                                 # where its foot stands at rest
        self.stand[1] = GROUND - self.ankle


LEGS = {k: Leg(k) for k in LEG_KEYS}


def auto_pole(L, d, swing=0.0):
    """Which way a leg's elbow or knee bends (in its parent's frame) when its foot is in direction [d] from the hip: the way
    the leg's own plane runs, perpendicular to it, from the standing leg's backward elbow (a hind leg's forward knee)
    through the forward-reaching leg's downward one to the raised leg's forward one. Unlike a fixed direction it never
    lies along the leg, so the bend does not flip as the foot moves about. [swing] turns it about the leg, degrees,
    positive toward the leg's own side."""
    d = np.asarray(d, float)
    e1 = d / max(float(np.linalg.norm(d)), 1e-6)
    r = np.cross(X_AX, e1)
    if np.linalg.norm(r) < 0.05:
        return L.pole.copy()
    r = r / np.linalg.norm(r)
    if not L.front:
        r = -r
    if swing:
        t = np.cross(e1, r)
        if np.dot(t, X_AX * L.sgn) < 0:
            t = -t
        a = math.radians(swing)
        r = r * math.cos(a) + t * math.sin(a)
    return r


class Rig:
    """A pose, as the game's rig animator holds it: every part's rotation (relative to its parent) and pivot offset.
    Set parts in this order: body, hips, then the legs (which find their hip joints from them)."""
    def __init__(self):
        self.q = {b: REST_Q[b].copy() for b in IDX}
        self.v = {b: np.zeros(3) for b in IDX}
        self.adds = {}               # 'add' tracks: part -> (rotation, offset) laid over what is under the clip
        self._w = {}

    # ---- forward kinematics of what has been set so far
    def world(self, bone):
        chain = []
        b = bone
        while b:
            chain.append(b)
            b = PARENT.get(b)
        R = np.eye(3); T = np.zeros(3)
        for b in reversed(chain):
            T = T + R @ (np.array(IDX[b].pivot, float) + self.v[b])
            R = R @ qmat(self.q[b])
        return R, T

    def point(self, bone, local):
        R, T = self.world(bone)
        return T + R @ np.asarray(local, float)

    # ---- the midline: pitch up, yaw to the left and roll to the left, in degrees from rest
    def mid(self, bone, pitch=0.0, yaw=0.0, roll=0.0):
        rx = IDX[bone].rot[0]
        self.q[bone] = qm(Ry(-yaw) @ Rx(rx - pitch) @ Rz(roll))
        return self

    def body(self, pos=(0, 0, 0), pitch=0.0, yaw=0.0, roll=0.0, about_pt=None):
        R = Ry(-yaw) @ Rx(-pitch) @ Rz(roll)
        self.q['body'] = qm(R)
        off = np.asarray(pos, float)
        if about_pt is not None:
            off = off + about(R, about_pt)
        self.v['body'] = off
        return self

    def hips(self, pos=(0, 0, 0), pitch=0.0, yaw=0.0, roll=0.0, about_pt=None):
        R = Ry(-yaw) @ Rx(-pitch) @ Rz(roll)
        self.q['hips'] = qm(R)
        off = np.asarray(pos, float)
        if about_pt is not None:
            off = off + about(R, about_pt)
        self.v['hips'] = off
        return self

    def neck(self, segs):
        """[(pitch, yaw, roll)] for neck1, neck2, neck3 and the head."""
        for b, s in zip(NECK, segs):
            self.mid(b, *s)
        return self

    def tail(self, segs):
        for b, s in zip(TAIL, segs):
            self.mid(b, *s)
        return self

    def jaw(self, open_deg):
        self.q['jaw'] = qeuler(x=open_deg)
        return self

    def lids(self, shut):
        """0 open (the lids slid back into the head) to 1 shut."""
        self.v['lids'] = V(0, 0, 3.2 * (1 - shut))
        return self

    # ---- legs
    def leg(self, key, target, foot=(0.0, 0.0, 0.0), pole=None, frame='w'):
        """Puts leg [key]'s ankle at [target] ('w' in the model's frame with no pose on it: the ground; 'b' in the
        body's frame), the foot turned [(toes down, to the left, roll)] in that frame, the elbow or knee toward [pole] (in
        the parent's frame)."""
        L = LEGS[key]
        Rp, Tp = self.world(L.parent)
        H = Tp + Rp @ np.array(IDX[L.parts[0]].pivot, float)
        A = np.asarray(target, float)
        fr = Rx(foot[0]) @ Rz(foot[2]) if foot[1] == 0 else Ry(-foot[1]) @ Rx(foot[0]) @ Rz(foot[2])
        if frame == 'b':
            Rb, Tb = self.world('body')
            A = Tb + Rb @ A
            fr = Rb @ fr
        if pole is None or np.isscalar(pole):
            pw = auto_pole(L, Rp.T @ (A - H), 0.0 if pole is None else float(pole))
        elif isinstance(pole, tuple):                       # (direction, k): from the automatic bend (k = 0) to the direction
            v = np.asarray(pole[0], float)
            pw = lerp(auto_pole(L, Rp.T @ (A - H)), v / np.linalg.norm(v), float(pole[1]))
        else:
            pw = np.asarray(pole, float)
        R1, R2, K, A2 = two_bone(H, A, L.L1, L.L2, Rp @ pw, hind=not L.front)
        self.q[L.parts[0]] = qm(Rp.T @ R1)
        self.q[L.parts[1]] = qm(R1.T @ R2)
        self.q[L.parts[2]] = qm(R2.T @ fr)
        return self

    def ankle(self, key):
        return self.point(LEGS[key].parts[2], (0, 0, 0))

    def stand_legs(self):
        for k in LEG_KEYS:
            self.leg(k, LEGS[k].stand)
        return self

    # ---- wings
    def wing(self, side, deltas):
        """Right-wing style deltas (rotations from the spread rest pose) for the five parts of the [side] wing; the left
        wing mirrors them."""
        names = WING_R if side == 'r' else WING_L
        for b, d in zip(names, deltas):
            d = d if side == 'r' else mirror_q(d)
            self.q[b] = qmul(REST_Q[b], d)
        return self

    # ---- 'add' tracks (a clip lays them over whatever is under it)
    def add(self, bone, q=None, pos=None):
        self.adds[bone] = (I.copy() if q is None else q, np.zeros(3) if pos is None else np.asarray(pos, float))
        return self

    def copy(self):
        r = Rig()
        r.q = {b: q.copy() for b, q in self.q.items()}
        r.v = {b: v.copy() for b, v in self.v.items()}
        r.adds = {b: (q.copy(), v.copy()) for b, (q, v) in self.adds.items()}
        return r


def abs_pose(rig):
    """{part: (matrix, offset)} for dragon.world_transforms."""
    return {b: (qmat(q), rig.v[b]) for b, q in rig.q.items()}


def fk(rig):
    return world_transforms(abs_pose(rig))


# ================================================================== wings

WING_OWN = [None, 0, 1, 2, 2]      # the part each part hangs from, as an index into the five


def wing_from_dirs(dirs, normals):
    """Right-wing deltas that point each part's bone along [dirs] (body frame) with its membrane's upper side toward
    [normals]."""
    W = [mframe(V(-1.0, 0, 0), V(0, 0, 1.0), unit(d), unit(n)) for d, n in zip(dirs, normals)]
    out = []
    for i, name in enumerate(WING_R):
        par = WING_OWN[i]
        local = W[i] if par is None else W[par].T @ W[i]
        out.append(qmul(qinv(REST_Q[name]), qm(local)))
    return out


def wing_zero():
    return [I.copy() for _ in WING_R]


def wslerp(A, B, k):
    """Blends two right-wing poses; [k] a number or one per part."""
    ks = k if hasattr(k, '__len__') else [k] * 5
    return [qslerp(a, b, c) for a, b, c in zip(A, B, ks)]


def wing_euler(angles):
    """Right-wing deltas from [(twist, flap up, sweep back)] per part, in degrees, each turned in its own part's frame
    (so each is added to its parent's turn). Twist raises the trailing edge, flap up raises the tip, sweep back
    swings it toward the tail."""
    return [qeuler(x=tw, y=fl, z=-sw) for tw, fl, sw in angles]


# ================================================================== previews: the rendering

TEX_W = getattr(dg, 'TEX_W', 256)
TEX_H = getattr(dg, 'TEX_H', 256)
HD = getattr(dg, 'HD', 2)
LIGHT0 = unit([-0.2, -1.0, -0.7])
LIGHT1 = unit([0.2, -1.0, 0.7])
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark')


def load_texture():
    """The painted texture and glow map if the texture helper has written them, else the debug colours."""
    base = os.path.join(ASSETS, 'textures', 'entity')
    tex = getattr(dg, 'TEX_HD', None)
    if tex is None:
        tex = np.zeros((TEX_H * HD, TEX_W * HD, 4), np.uint8)
        tex[...] = (120, 32, 30, 255)
    glow = None
    p = os.path.join(base, 'dragon.png')
    if os.path.exists(p):
        try:
            img = Image.open(p).convert('RGBA')
            if img.size == (TEX_W * HD, TEX_H * HD):
                tex = np.array(img)
        except Exception:
            pass
    return tex, glow


def camera(yaw, elev, scale, size, center=(0.0, 4.0, 0.0)):
    """An orthographic view: [yaw] about the vertical (90 sees the dragon's right side, head to the right), [elev] looking
    down from above, [scale] screen px per model px."""
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    ce, se = math.cos(math.radians(elev)), math.sin(math.radians(elev))
    W, H = size
    cx, cyy, cz = center

    def project(p):
        x, y, z = p[..., 0] - cx, p[..., 1] - cyy, p[..., 2] - cz
        x2 = x * cy - z * sy
        z2 = x * sy + z * cy
        y3 = y * ce - z2 * se
        z3 = z2 * ce + y * se
        return np.stack([W / 2 + x2 * scale, H / 2 + y3 * scale, z3], -1)
    view = np.array([sy * ce, se, cy * ce])
    return project, view


def posed_quads(pose):
    """Every face of the posed model as (corner A, B along u, D along v, normal, texture rect at HD)."""
    if hasattr(dg, 'posed_quads'):
        return dg.posed_quads(pose)
    out = []
    for A, B, D, (u, v, w, h), face, n in dg.model.world_faces(pose):
        if w and h:
            out.append((A, B, D, n, (int(round(u * HD)), int(round(v * HD)), int(round(w * HD)), int(round(h * HD)))))
    return out


TEX, GLOW = load_texture()
BG = (46, 50, 62)


def raster(quads, cam, size, tex, ground_y=GROUND, ground_shift=0.0, bg=BG, glow=None, ext=160):
    """dragon.raster, with the ground at any height (or none) so a flier can be seen over it."""
    project, view = cam
    W, H = size
    img = np.zeros((H, W, 3), float); img[...] = bg
    zbuf = np.full((H, W), 1e9)
    T = np.asarray(tex, float)
    G = None if glow is None else np.asarray(glow, float)
    ys, xs = np.mgrid[0:H, 0:W]
    items = list(quads)
    if ground_y is not None:
        items = [('ground',)] + items
    for q in items:
        if isinstance(q[0], str):
            corners = np.array([[-ext, ground_y, -ext], [ext, ground_y, -ext], [-ext, ground_y, ext]], float)
            a, b, d = project(corners)
            e1, e2 = b - a, d - a
            det = e1[0] * e2[1] - e1[1] * e2[0]
            if abs(det) < 1e-6:
                continue
            px = xs + 0.5 - a[0]; py = ys + 0.5 - a[1]
            s = (px * e2[1] - py * e2[0]) / det
            t = (e1[0] * py - e1[1] * px) / det
            inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
            depth = a[2] + s * e1[2] + t * e2[2]
            gx = (s * 2 * ext - ext); gz = (t * 2 * ext - ext) + ground_shift
            line = (np.abs(((gx + 4) % 8) - 4) < 0.35) | (np.abs(((gz + 4) % 8) - 4) < 0.35)
            col = np.where(line[..., None], np.array([78, 84, 98.0]), np.array([58, 62, 74.0]))
            img[inside] = col[inside]
            zbuf[inside] = depth[inside] + 0.05
            continue
        A, B, D, n, (u0, v0, w, h) = q
        if np.dot(n, view) > 1e-4:
            continue
        a, b, d = project(np.array([A, B, D]))
        e1, e2 = b - a, d - a
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        corners = [a, b, d, b + e2]
        x0 = int(max(0, math.floor(min(c[0] for c in corners)))); x1 = int(min(W, math.ceil(max(c[0] for c in corners)) + 1))
        y0 = int(max(0, math.floor(min(c[1] for c in corners)))); y1 = int(min(H, math.ceil(max(c[1] for c in corners)) + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        px = xs[y0:y1, x0:x1] + 0.5 - a[0]; py = ys[y0:y1, x0:x1] + 0.5 - a[1]
        s = (px * e2[1] - py * e2[0]) / det
        t = (e1[0] * py - e1[1] * px) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        depth = a[2] + s * e1[2] + t * e2[2]
        tu = np.clip(u0 + np.floor(s * w).astype(int), u0, u0 + w - 1)
        tv = np.clip(v0 + np.floor(t * h).astype(int), v0, v0 + h - 1)
        texel = T[tv, tu]
        ok = inside & (texel[..., 3] > 25) & (depth < zbuf[y0:y1, x0:x1])
        light = min(1.0, 0.4 + 0.6 * (max(0.0, float(np.dot(n, LIGHT0))) + max(0.0, float(np.dot(n, LIGHT1)))))
        col = texel[..., :3] * light
        if G is not None:
            col = col + G[tv, tu][..., :3] * (G[tv, tu][..., 3:4] / 255.0)
        region = img[y0:y1, x0:x1]
        region[ok] = col[ok]
        zbuf[y0:y1, x0:x1][ok] = depth[ok]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


# ================================================================== wing poses

IN = V(1.0, 0, 0)
OUT = V(-1.0, 0, 0)


def fold_pose(spec, normals=(IN, IN, OUT, OUT, OUT)):
    """A right-wing pose from bone directions in the body frame, humerus first, with the membranes hanging as the bones
    zigzag (the upper side faces in on the arm, out on the hand)."""
    return wing_from_dirs([unit(V(*d)) for d in spec], list(normals))


# Folded on the back: the elbow stands high over the shoulder, the forearm lies back along the flank and the hand
# turns forward again under it, the membranes hanging between like a cloak
FOLD = fold_pose([(-0.25, -0.60, 0.76), (-0.12, 0.25, 0.96), (-0.05, 0.12, -0.99), (-0.04, 0.22, -0.97), (-0.02, 0.38, -0.92)])
SPREAD = wing_zero()


def beat(p, top=0.55):
    """A wingbeat's height at loop phase [p]: 0 at the bottom of the downbeat (p = 0), 1 at the top of the upbeat
    ([top] of the way round), smooth at both ends."""
    p = p % 1.0
    if p < top:
        return 0.5 - 0.5 * math.cos(math.pi * p / top)
    return 0.5 + 0.5 * math.cos(math.pi * (p - top) / (1 - top))


def flap(p, lows, highs, lag, tuck=0.0, top=0.55, twist=(0, 0, 0, 0, 0)):
    """A right wing at beat phase [p]. [lows] and [highs] are each part's own turn (relative to its parent, degrees of
    flap up) at the bottom and the top of the beat; [lag] is how much later down the arm each part gets there (in
    loops); [tuck] folds the wing in a little at the wrist and elbow (degrees of sweep back each part gets on the
    upbeat, scaled by how far up it is moving)."""
    ang = []
    for i in range(5):
        h = beat(p - lag[i], top)
        up = max(0.0, math.sin(math.pi * ((p - lag[i]) % 1.0) / top)) if (p - lag[i]) % 1.0 < top else 0.0
        tw = twist[i] * (h - 0.5)
        ang.append((tw, lerp(lows[i], highs[i], h), tuck * up if i in (1, 2) else 0.0))
    return wing_euler(ang)


def head_pitch(rig):
    """How far the head's snout points below level (degrees; negative above), whatever the pose."""
    R, T = rig.world('head')
    d = R @ V(0, 0, -1.0)
    return math.degrees(math.asin(clamp(d[1], -1, 1)))


def hang(rig, key, drop, fwd=0.0, out=0.0, foot=(0.0, 0.0, 0.0), pole=None):
    """Lets leg [key] dangle: its ankle [drop] px straight below its hip joint (world down), [fwd] ahead and [out] to
    the side, whatever the body does."""
    L = LEGS[key]
    Rp, Tp = rig.world(L.parent)
    H = Tp + Rp @ np.array(IDX[L.parts[0]].pivot, float)
    rig.leg(key, H + V(L.sgn * out, drop, -fwd), foot=foot, pole=pole)
    return rig


# ================================================================== the ground pose and the walk

def sin_(p):
    return math.sin(2 * math.pi * p)


def cos_(p):
    return math.cos(2 * math.pi * p)


def folded(R, jig=0.0, clear=False, margin=0.3):
    """Wings folded on its back; [jig] lifts them a little (degrees). With [clear] the arms are turned about their own length
    just as far as keeps the membranes off the ground (for a dragon lying down)."""
    def put(tw):
        d = [qmul(FOLD[0], qeuler(y=jig, z=0))] + list(FOLD[1:])
        if tw:
            d[0] = qmul(d[0], wing_euler([(tw, 0, 0)] + [(0, 0, 0)] * 4)[0])
        R.wing('r', d); R.wing('l', d)
    put(0.0)
    if clear and lowest(R, WING_BONES)[0] > GROUND - margin:
        lo, hi = 0.0, 70.0
        for _ in range(18):
            m = 0.5 * (lo + hi)
            put(m)
            if lowest(R, WING_BONES)[0] > GROUND - margin:
                lo = m
            else:
                hi = m
        put(hi)
    return R


# Where each foot lands and where it lifts, in px along the body, and the loop it walks: one loop is WALK_STRIDE blocks
WALK_STRIDE = 3.5
WALK_LENGTH = 40
STRIDE_PX = WALK_STRIDE / PX                    # model px the body covers in one loop (28)
DUTY = 0.75                                     # how much of a loop each foot is on the ground
LAND = {'fr': 0.0, 'hl': 0.25, 'fl': 0.5, 'hr': 0.75}
STANCE_Z = {'f': -10.0, 'h': 15.0}               # where each foot is at the middle of its stance
LIFT = {'f': 5.5, 'h': 6.5}                      # how high each foot clears the ground
TOE_OUT = 4.0


# The lowest points of a foot in its own frame (y down, z forward is negative): the toes' tips and the heel
FOOT_TOE = (2.5, -10.5)
FOOT_HEEL = {'f': (2.5, 2.5), 'h': (2.5, 3.5)}


def _yz(theta, c):
    a = math.radians(theta)
    return c[0] * math.cos(a) - c[1] * math.sin(a), c[0] * math.sin(a) + c[1] * math.cos(a)


def foot_low(theta, kind):
    """How far below the ankle the foot's lowest point is when its toes are turned down by [theta] degrees."""
    return max(_yz(theta, FOOT_TOE)[0], _yz(theta, FOOT_HEEL[kind])[0])


def foot_theta_stance(tau, duty=DUTY):
    """On the ground: heel first, then flat, then the heel comes up as the foot leaves (toes down)."""
    return 26.0 * smooth((tau - (duty - 0.10)) / 0.10) - 11.0 * (1.0 - smooth(tau / 0.08))


def foot_theta_swing(s):
    return 26.0 * (1.0 - smooth(s / 0.35)) - 11.0 * smooth((s - 0.6) / 0.4)


def _stance_ankle(L, tau, stride, duty, zc):
    """The ankle with the foot turned about whichever end of it is on the ground, so that end stays put."""
    kind = L.key[0]
    rng = stride * duty / 2
    zf = zc - rng + stride * tau
    th = foot_theta_stance(tau, duty)
    c = FOOT_TOE if th > 0 else FOOT_HEEL[kind]
    yy, zz = _yz(th, c)
    return V(0.0, GROUND - yy, zf + c[1] - zz), th


def foot_path(key, u, stride=STRIDE_PX, duty=DUTY, lift=None):
    """Where leg [key]'s ankle is at walk phase [u] in the treadmill frame (the body stands still, the ground runs back
    [stride] px a loop), and how its foot is turned (toes down). On the ground the foot goes back with the ground, rolling
    heel to toe; in the air it swings forward round the outside."""
    L = LEGS[key]
    kind = key[0]
    tau = (u - LAND[key]) % 1.0
    zc = STANCE_Z[kind]
    lift = LIFT[kind] if lift is None else lift
    x = L.stand[0]
    if tau < duty:
        a, th = _stance_ankle(L, tau, stride, duty, zc)
        return V(x, a[1], a[2]), th
    s = (tau - duty) / (1 - duty)
    a0, _ = _stance_ankle(L, duty - 1e-9, stride, duty, zc)
    a1, _ = _stance_ankle(L, 0.0, stride, duty, zc)
    th = foot_theta_swing(s)
    z = lerp(a0[2], a1[2], smooth(s))
    h = lift * math.sin(math.pi * s) ** 0.85
    y = GROUND - foot_low(th, kind) - h
    return V(x + L.sgn * 1.6 * math.sin(math.pi * s), y, z), th


def walk_pose(u):
    """The walk at loop phase [u]: a four-beat walk, feet landing FR 0, HL 0.25, FL 0.5, HR 0.75."""
    R = Rig()
    dip = 0.55 * cos_(4 * (u - 0.05)) + 0.45 * cos_(2 * (u - 0.14))
    rock = -cos_(u - 0.25)                                       # -1 leaning right (the right feet bear it), 1 left
    R.body(pos=(0.9 * rock * -1 * 0 + 0.9 * -cos_(u - 0.25), 1.6 + dip, 0.0), pitch=1.0 * cos_(2 * (u - 0.2)) - 0.5,
           yaw=2.6 * cos_(u), roll=-1.7 * cos_(u - 0.25))
    R.hips(pitch=0.8 * cos_(2 * (u - 0.35)), yaw=-4.0 * sin_(u) - 1.5 * cos_(u), roll=-0.8 * cos_(u - 0.25))
    for k in LEG_KEYS:
        pos, pitch = foot_path(k, u)
        L = LEGS[k]
        R.leg(k, pos, foot=(pitch, L.sgn * -TOE_OUT, 0.0))
    nod = lambda lag: cos_(2 * (u - 0.08 - lag))
    R.neck([(1.2 * nod(0.0), -1.5 * cos_(u) - 0.5 * sin_(u), 0), (1.8 * nod(0.05), -1.2 * cos_(u), 0),
            (2.2 * nod(0.1), -0.8 * cos_(u), 0), (-1.0 * nod(0.18), -0.5 * cos_(u) , 0.6 * cos_(u))])
    R.tail([(1.0 * cos_(2 * (u - 0.3 - 0.06 * i)) - 3.0 * (4 - i) / 4.0, A * sin_(u - 0.05 - 0.085 * i), 0)
            for i, A in enumerate((3.2, 5.0, 6.5, 8.0, 9.0))])
    folded(R, jig=1.6 * cos_(2 * (u - 0.3)))
    return R


def walk_report():
    """How far each foot slides on the ground in its stance and how stretched the legs get."""
    worst, ext = 0.0, 0.0
    for k in LEG_KEYS:
        L = LEGS[k]
        zs = []
        for i in range(0, 401):
            u = i / 400
            R = walk_pose(u)
            a = R.ankle(k)
            tau = (u - LAND[k]) % 1.0
            if tau < DUTY - 0.11 and tau > 0.09:
                zs.append(a[2] - STRIDE_PX * tau)
                Rp, Tp = R.world(L.parent)
                H = Tp + Rp @ np.array(IDX[L.parts[0]].pivot, float)
                ext = max(ext, np.linalg.norm(a - H) / (L.L1 + L.L2))
        worst = max(worst, max(zs) - min(zs))
    return worst, ext


# ================================================================== checks

def _corners(name):
    out = []
    for c in IDX[name].cubes:
        g = c.inflate
        x0, y0, z0 = [o - g for o in c.origin]
        x1, y1, z1 = [o + s + g for o, s in zip(c.origin, c.size)]
        for x in (x0, x1):
            for y in (y0, y1):
                for z in (z0, z1):
                    out.append((x, y, z))
    return np.array(out, float).reshape(-1, 3)


CORNERS = {n: _corners(n) for n in IDX}


def lowest(rig, bones=None):
    """The lowest point (largest y) of the posed model's cubes, and the part it belongs to."""
    best, who = -1e9, None
    names = list(IDX) if bones is None else bones
    wt = fk(rig) if bones is None else None
    for name in names:
        c = CORNERS[name]
        if not len(c):
            continue
        R, T = wt[name] if wt is not None else rig.world(name)
        y = float((c @ R[1] + T[1]).max())
        if y > best:
            best, who = y, name
    return best, who


# ================================================================== steering the neck

YAW_SPREAD = (0.50, 0.25, 0.15, 0.10)    # how a turn of the neck is shared between its four parts
NECK_BASE = V(*IDX['neck1'].pivot)       # the base of the neck, in the body's frame
NECK_SEG = 8.0                           # the spacing of the neck's joints
HEAD_REACH = 16.5                        # from the head's pivot to the mouth point
NECK_REST_REL = [-IDX[b].rot[0] for b in NECK]    # each part's pitch up from its parent at rest


def neck_abs(R, a, yaw=0.0, roll=0.0, head_roll=0.0):
    """Sets the neck from the angles (degrees nose up, from the body's own level) each of its four parts points at,
    turned [yaw] degrees to the left as a whole (shared as YAW_SPREAD)."""
    prev = 0.0
    pitches = []
    for ai, rest in zip(a, NECK_REST_REL):
        pitches.append((ai - prev) - rest)
        prev = ai
    segs = []
    for i in range(4):
        segs.append((pitches[i], yaw * YAW_SPREAD[i], roll * (0.25 if i < 3 else 0.0) + (head_roll if i == 3 else 0.0)))
    R.neck(segs)
    return R


def neck_pose(R, segs):
    """Sets the four neck parts from the way each points in the body's frame: [(heading, pitch[, roll])] for neck1, neck2,
    neck3 and the head, in degrees (heading to the left of straight ahead, pitch nose up, roll to the left-down)."""
    prev = np.eye(3)
    for b, s in zip(NECK, segs):
        hd, pt = s[0], s[1]
        rl = s[2] if len(s) > 2 else 0.0
        M = Ry(-hd) @ Rx(-pt) @ Rz(rl)
        R.q[b] = qm(prev.T @ M)
        prev = M
    return R


def neck_points(segs, base=None):
    """Where the neck's parts begin ([base, neck2's pivot, neck3's, the head's]) and the mouth point, in the body's frame."""
    p = np.array(NECK_BASE if base is None else base, float)
    pts = [p.copy()]
    for i, s in enumerate(segs):
        M = Ry(-s[0]) @ Rx(-s[1])
        d = M @ V(0, 0, -1.0)
        if i < 3:
            p = p + NECK_SEG * d
            pts.append(p.copy())
        else:
            pts.append(p + HEAD_REACH * d)
    return pts


def neck_ik(target, head, prior, wp=0.02, iters=40, roll=(0.0, 0.0, 0.0), _inner=False):
    """The neck bent so the head's pivot is at [target] (the body's frame, px) with the head pointing [head] = (heading,
    pitch[, roll]), the three neck parts staying as near [prior] = [(heading, pitch)] * 3 as the reach lets them (a larger
    [wp] holds them nearer). Returns the four (heading, pitch) pairs for neck_pose."""
    tgt = np.asarray(target, float)
    x = np.array([prior[0][0], prior[1][0], prior[2][0], prior[0][1], prior[1][1], prior[2][1]], float)
    x0 = x.copy()

    def pos(x):
        segs = [(x[0], x[3]), (x[1], x[4]), (x[2], x[5]), (0.0, 0.0)]
        return neck_points(segs)[3]

    def res(x):
        return np.concatenate([pos(x) - tgt, math.sqrt(wp) * (x - x0)])
    lam = 1.0
    r = res(x)
    for _ in range(iters):
        J = np.zeros((len(r), 6))
        for j in range(6):
            dx = x.copy(); dx[j] += 0.05
            J[:, j] = (res(dx) - r) / 0.05
        A = J.T @ J + lam * np.eye(6)
        step = np.linalg.solve(A, -J.T @ r)
        xn = x + step
        rn = res(xn)
        if rn @ rn < r @ r:
            x, r, lam = xn, rn, max(lam * 0.5, 1e-4)
        else:
            lam *= 4.0
        if np.linalg.norm(r[:3]) < 1e-3 and np.linalg.norm(step) < 1e-3:
            break
    if wp > 1e-4 and np.linalg.norm(r[:3]) > 0.05 and not _inner:
        # hold the prior less if the reach was not met
        return neck_ik(target, head, [(x[0], x[3]), (x[1], x[4]), (x[2], x[5])], wp=wp * 0.1, iters=iters, roll=roll, _inner=True)
    segs = [(x[0], x[3]), (x[1], x[4]), (x[2], x[5])] + [tuple(head)]
    return segs


def dir_of(heading, pitch):
    """The unit direction a head points when it faces [heading] degrees to the left of straight ahead and [pitch] nose up."""
    return Ry(-heading) @ Rx(-pitch) @ V(0, 0, -1.0)


def angles_of(d):
    """(heading, pitch) in degrees of a direction."""
    d = np.asarray(d, float)
    d = d / np.linalg.norm(d)
    return math.degrees(math.atan2(d[0], -d[2])), -math.degrees(math.asin(clamp(d[1], -1, 1)))


def mouth_to(R, mouth, head_dir, prior, wp=0.02, sweep=0.0):
    """Bends the neck (the body must be set already) so the head's mouth point is at [mouth] (the model's frame, px) with the
    head pointing along [head_dir] (a unit vector in the model's frame). [prior] = [(heading, pitch)] * 3 holds the neck's
    own curve (body frame); [sweep] then turns the whole neck, rigidly, [sweep] degrees to the left about the neck's base."""
    Rb, Tb = R.world('body')
    d_b = Rb.T @ np.asarray(head_dir, float)
    pivot_w = np.asarray(mouth, float) - HEAD_REACH * np.asarray(head_dir, float)
    pivot_b = Rb.T @ (pivot_w - Tb)
    hd, pt = angles_of(d_b)
    segs = neck_ik(pivot_b, (hd, pt), prior, wp=wp)
    segs = [(a + sweep, b) for a, b in segs]
    neck_pose(R, segs)
    return segs


def lay_tail(R, yaws, margin=0.3, lift=0.0, sweeps=5):
    """Pitches (degrees, positive down) that lay the tail on the ground along the turns [yaws], as the body and hips
    are set on [R]: no part of it below the ground, none held more than a little above it, and as little bend between
    neighbouring parts as that allows. Sets them on [R] and returns them."""
    Rh, Th = R.world('hips')
    rx = [IDX[b].rot[0] for b in TAIL]
    piv = [np.array(IDX[b].pivot, float) + R.v[b] for b in TAIL]
    limit = GROUND - margin - lift

    def lows(p):
        Rc, Tc = Rh, Th
        out = []
        for j, b in enumerate(TAIL):
            Tc = Tc + Rc @ piv[j]
            Rc = Rc @ (Ry(-yaws[j]) @ Rx(rx[j] - p[j]))
            out.append(float((CORNERS[b] @ Rc[1] + Tc[1]).max()))
        return out

    def cost(p):
        c = 0.0
        for y in lows(p):
            over = y - limit
            if over > 0:
                c += 4000.0 * over * over
            elif over < -1.0:
                c += 4.0 * (over + 1.0) ** 2
        c += 0.2 * p[0] ** 2 + sum((p[j] - p[j - 1]) ** 2 for j in range(1, 5))
        return c

    p = [0.0] * 5
    for _ in range(sweeps):
        for j in range(5):
            best = (cost(p), p[j])
            for step, span in ((2.5, 24), (0.25, 10)):
                centre = best[1]
                for k in range(-span, span + 1):
                    p[j] = centre + k * step
                    c = cost(p)
                    if c < best[0]:
                        best = (c, p[j])
            p[j] = best[1]
    R.tail([(p[j], yaws[j], 0.0) for j in range(5)])
    return p


def neck_ik_pose(R, target, head, prior, wp=0.02):
    """neck_ik, with the result put on [R] as well as returned."""
    segs = neck_ik(target, head, prior, wp=wp)
    neck_pose(R, segs)
    return segs


def tree_of(name):
    """A part and every part below it."""
    out, todo = [], [name]
    while todo:
        n = todo.pop()
        out.append(n)
        todo += [c.name for c in IDX[n].children]
    return out


NECK_TREE = tree_of('neck1')              # the neck, the head, the jaw and everything fixed to them


def ground_neck(R, make, y, margin=0.25, iters=6):
    """[make](y) bends the neck on [R] so the head's pivot is at height [y] (px in the body's frame; smaller is higher) and
    returns its parts' angles. Finds the y that lays the neck and head on the ground without going through it."""
    segs = make(y)
    for _ in range(iters):
        pen = lowest(R, NECK_TREE)[0] - (GROUND - margin)
        if abs(pen) < 0.05:
            break
        y -= pen
        segs = make(y)
    return segs


def lift_neck(R, segs, margin=0.2):
    """Poses the neck as [segs] say, but pitched up about its base as far as that takes to keep it and the head off the ground."""
    neck_pose(R, segs)
    if lowest(R, NECK_TREE)[0] <= GROUND - margin:
        return segs
    lo, hi = 0.0, 70.0
    for _ in range(14):
        m = 0.5 * (lo + hi)
        neck_pose(R, [(h, p + m) + tuple(rest) for (h, p, *rest) in segs])
        if lowest(R, NECK_TREE)[0] > GROUND - margin:
            lo = m
        else:
            hi = m
    out = [(h, p + hi) + tuple(rest) for (h, p, *rest) in segs]
    neck_pose(R, out)
    return out


REST_NECK = [38.0, 28.0, 14.0, -12.0]            # the angles (nose up) the neck's four parts point at in the rest pose


_CACHE = {}


def cached(key, fn):
    if key not in _CACHE:
        _CACHE[key] = fn()
    return _CACHE[key]


def neck_blend(R, A, B, k, sweep=0.0, wob=None):
    """The neck between two solved poses [A] and [B] (lists of four (heading, pitch)), [k] of the way, turned [sweep] more."""
    segs = []
    for i, (a, b) in enumerate(zip(A, B)):
        segs.append((lerp(a[0], b[0], k) + sweep, lerp(a[1], b[1], k) + (wob[i] if wob else 0.0)))
    neck_pose(R, segs)
    return segs


REST_SEGS = [(0.0, a) for a in REST_NECK]

# where the breath's body stands at the stream: crouched and leaning into it
BREATH_BODY = dict(up=-3.0, fwd=3.0, pitch=-3.0)


def _body_pose(up=0.0, fwd=0.0, pitch=0.0):
    R = Rig()
    R.body(pos=(0, -up, -fwd), pitch=pitch)
    return R


def aim_segs(key, up, fwd, pitch, mouth, head_pitch, prior, heading=0.0, wp=0.02):
    """Solved neck (heading, pitch) pairs for a body held at ([up], [fwd], [pitch]) and the mouth at [mouth] pointing
    [head_pitch] nose up in the model's frame."""
    def go():
        R = _body_pose(up, fwd, pitch)
        return mouth_to(R, mouth, dir_of(heading, head_pitch), prior, wp=wp)
    return cached(key, go)


# ================================================================== clips: definitions, baking, layering

RATE = 2                       # samples a tick, as the other clips are baked


class Def:
    """One clip: [fn](t) returns the Rig at time t. [bones] are the parts it keys 'set' (every one, at every sample, so
    it replaces what is under it), [adds] the ones it lays on top ('add'), taken from rig.adds."""
    def __init__(self, name, length, fn, bones, adds=(), loop=False, blend_in=2.0, blend_out=6.0, base=None, marks=None, rate=RATE, air=False):
        self.name, self.length, self.fn = name, length, fn
        self.air = air
        self.bones, self.adds = list(bones), list(adds)
        self.loop = loop
        self.blend_in = 0.0 if loop else blend_in
        self.blend_out = 0.0 if loop else blend_out
        self.base = base           # what the preview puts under it: callable t -> [(clip name, time, weight)]
        self.marks = marks or {}
        self.rate = rate
        self._clip = None

    def clip(self):
        if self._clip is None:
            self._clip = self.bake_clip()
        return self._clip

    def bake_clip(self):
        c = Clip(self.name, self.length, blend_in=self.blend_in, blend_out=self.blend_out, rate=self.rate)
        n = int(round(self.length * self.rate))
        samples = []
        for i in range(n + 1):
            t = i / self.rate
            samples.append(self.fn(min(t, self.length)))
        for b in self.bones:
            moves = b in POS_BONES and any(np.abs(r.v[b]).max() > 1e-6 for r in samples)
            for i, r in enumerate(samples):
                c.key(b, i / self.rate, qmul(qinv(REST_Q[b]), r.q[b]), pos=r.v[b] if moves else None, ease='linear')
        for b in self.adds:
            moves = any(b in r.adds and np.abs(r.adds[b][1]).max() > 1e-6 for r in samples)
            for i, r in enumerate(samples):
                q, v = r.adds.get(b, (I, np.zeros(3)))
                c.key(b, i / self.rate, q, pos=v if moves else None, ease='linear')
            c.mode(b, 'add')
        for k, v in self.marks.items():
            c.mark(k, v)
        return c


DEFS = {}


def define(name, length, bones, adds=(), **kw):
    """Registers the function below it as a clip."""
    def deco(fn):
        DEFS[name] = Def(name, length, fn, bones, adds, **kw)
        return fn
    return deco


def bake_json(d):
    """The clip as the game reads it, with the tracks that never move kept to a single sample (the game holds a short track
    at its last sample)."""
    out = d.clip().bake()
    for b, tr in out['bones'].items():
        rot = np.array(tr['rot']).reshape(-1, 4)
        const = np.abs(rot - rot[0]).max() < 2e-4 or np.abs(rot + rot[0]).max() < 2e-4
        pos = tr.get('pos')
        if pos is not None:
            p = np.array(pos).reshape(-1, 3)
            const = const and np.abs(p - p[0]).max() < 2e-3
        if const:
            tr['rot'] = tr['rot'][:4]
            if pos is not None:
                tr['pos'] = tr['pos'][:3]
    return out


def write_all(assets, names=None):
    paths = []
    for name, d in DEFS.items():
        if names and name not in names:
            continue
        path = os.path.join(assets, 'animations', GROUP, name + '.json')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            json.dump(bake_json(d), f, separators=(',', ':'))
        paths.append(path)
    return paths


# ---- layering, as RigAnimator does it

def rest_pose_state():
    return {b: [REST_Q[b].copy(), np.zeros(3)] for b in IDX}


def layer(state, clip, t, w):
    """Blends [clip] at [t] over [state] by [w]: a 'set' track turns a part from its rest pose, an 'add' track from where
    the layers under it left it."""
    if w <= 0:
        return
    for bone in clip.keys:
        if bone not in state:
            continue
        qk, pk = clip.sample(bone, t)
        q0, v0 = state[bone]
        if clip.modes.get(bone) == 'add':
            gq, gv = qmul(q0, qk), v0 + pk
        else:
            gq, gv = qmul(REST_Q[bone], qk), pk
        state[bone] = [qslerp(q0, gq, w), v0 + (gv - v0) * w]


def compose(layers):
    """The posed model for [(clip name or Def, time, weight)] layers, bottom first, as {part: (matrix, offset)}."""
    state = rest_pose_state()
    for item in layers:
        d, t, w = item
        d = DEFS[d] if isinstance(d, str) else d
        layer(state, d.clip(), t, w)
    return {b: (qmat(q), v) for b, (q, v) in state.items()}


def stack(d, t, w=None):
    """What the game would show for clip [d] at [t]: the clip's own base under it, then the clip at its blend weight."""
    d = DEFS[d] if isinstance(d, str) else d
    under = d.base(t) if d.base else []
    return list(under) + [(d, t, d.clip().weight(t) if w is None else w)]


# ================================================================== previews: the sheets and the frames

VIEWS = {
    'side': dict(yaw=90, elev=6, scale=2.7, size=(440, 300), center=(0.0, -2.0, 8.0)),
    'three': dict(yaw=35, elev=14, scale=2.4, size=(440, 300), center=(0.0, -2.0, 8.0)),
    'front': dict(yaw=0, elev=8, scale=2.4, size=(440, 300), center=(0.0, -2.0, 8.0)),
    'sideL': dict(yaw=-90, elev=6, scale=2.7, size=(440, 300), center=(0.0, -2.0, 8.0)),
    'threeL': dict(yaw=-35, elev=14, scale=2.4, size=(440, 300), center=(0.0, -2.0, 8.0)),
    'back': dict(yaw=180, elev=8, scale=2.4, size=(440, 300), center=(0.0, -2.0, 8.0)),
    'top': dict(yaw=180, elev=84, scale=2.2, size=(440, 300), center=(0.0, 0.0, 8.0)),
}
FONT = None


def font(size=13):
    global FONT
    try:
        return ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', size)
    except Exception:
        return ImageFont.load_default()


def render_pose(pose, view='side', ground=GROUND, shift=0.0, cam=None, glow=None):
    v = dict(VIEWS[view]) if isinstance(view, str) else dict(view)
    if cam:
        v.update({k: val for k, val in cam.items() if k in v})
        c = list(v['center'])
        for i, k in enumerate(('cx', 'cy', 'cz')):
            if k in cam:
                c[i] = cam[k]
        v['center'] = tuple(c)
    quads = posed_quads(pose)
    return raster(quads, camera(v['yaw'], v['elev'], v['scale'], v['size'], v['center']), v['size'], TEX, ground_y=ground,
                  ground_shift=shift, glow=glow)


def frame_for(d, t, view='side', airborne=None, cam=None, glow=None):
    """The picture of clip [d] at [t] over its base layers."""
    d = DEFS[d] if isinstance(d, str) else d
    pose = compose(stack(d, t))
    air = d.air if airborne is None else airborne
    air = air(t) if callable(air) else air
    img = render_pose(pose, view, ground=None if air else GROUND, cam=cam, glow=glow)
    if air and view in ('side', 'front', 'three'):
        dr_ = ImageDraw.Draw(img)
        W, H = img.size
        v = VIEWS[view] if isinstance(view, str) else view
        # a faint level line at the feet's height
        cy = H / 2 + (GROUND - v['center'][1]) * v['scale'] * math.cos(math.radians(v['elev']))
        dr_.line([(0, cy), (W, cy)], fill=(70, 76, 92), width=1)
    return img


def sample_times(d, n):
    L = d.length
    ts = [L * i / n for i in range(n)] if d.loop else [L * i / (n - 1) for i in range(n)]
    ts = [float(round(t)) for t in ts]
    # always show the marked ticks
    for k, tm in d.marks.items():
        if all(abs(tm - t) > 0.75 for t in ts):
            ts.append(float(tm))
    return sorted(set(ts))


def sheet(d, path, n=12, views=('side',), cols=4, times=None, cam=None, glow=None, scale=None):
    d = DEFS[d] if isinstance(d, str) else d
    ts = times or sample_times(d, n)
    sub = []
    for view in views:
        for t in ts:
            img = frame_for(d, t, view, cam=cam, glow=glow)
            dr_ = ImageDraw.Draw(img)
            marks = [k for k, tm in d.marks.items() if abs(tm - t) < 0.26]
            w = d.clip().weight(t) if not d.loop else 1.0
            dr_.text((6, 4), f'{d.name} t={t:g}' + (f'  w={w:.2f}' if not d.loop else ''), fill=(235, 235, 245), font=font(14))
            if marks:
                dr_.text((6, 22), ' '.join(marks), fill=(255, 210, 90), font=font(14))
                dr_.rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], outline=(255, 210, 90))
            sub.append(img)
    W, H = sub[0].size
    rows = (len(sub) + cols - 1) // cols
    out = Image.new('RGB', (W * cols, H * rows), (20, 22, 28))
    for i, im in enumerate(sub):
        out.paste(im, ((i % cols) * W, (i // cols) * H))
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    out.save(path)
    return path


# ================================================================== shared poses

STAND_FOOT = {k: (0.0, LEGS[k].sgn * -TOE_OUT, 0.0) for k in LEG_KEYS}


def stance(R, up=0.0, fwd=0.0, lat=0.0, pitch=0.0, yaw=0.0, roll=0.0, about_pt=None, hips=None, feet=None, toe=TOE_OUT, pole=None):
    """The body (raised [up] px, [fwd] ahead, [lat] to the left, pitched, turned and rolled about [about_pt]), its hips
    (a dict with any of up, pitch, yaw, roll, relative to the body), and all four feet standing where they stand at rest
    (or at [feet]: leg -> offset in the model's frame, or a (target, foot angles) pair)."""
    R.body(pos=(lat, -up, -fwd), pitch=pitch, yaw=yaw, roll=roll, about_pt=about_pt)
    h = hips or {}
    R.hips(pos=(0, -h.get('up', 0.0), 0), pitch=h.get('pitch', 0.0), yaw=h.get('yaw', 0.0), roll=h.get('roll', 0.0))
    for k in LEG_KEYS:
        tgt = LEGS[k].stand.copy()
        foot = (0.0, LEGS[k].sgn * -toe, 0.0)
        if feet and k in feet:
            f = feet[k]
            if isinstance(f, tuple) and len(f) == 2 and not np.isscalar(f[0]):
                tgt = tgt + np.asarray(f[0], float)
                foot = f[1]
            else:
                tgt = tgt + np.asarray(f, float)
        R.leg(k, tgt, foot=foot, pole=None if pole is None else pole.get(k))
    return R


def tail_curve(R, u, amp=(2.0, 2.8, 3.5, 4.2, 5.0), lag=0.08, droop=(1.0, 1.0, 1.0, 1.0, 1.0), pitch_amp=0.0, pitch_phase=0.0, yaw_bias=0.0, rate=1.0):
    """The tail swaying side to side at loop phase [u] (a wave running down it), hanging a little lower than at rest."""
    segs = []
    for i in range(5):
        segs.append((droop[i] + pitch_amp * sin_(rate * (u - pitch_phase - lag * i)), amp[i] * sin_(rate * (u - lag * i)) + yaw_bias / 5.0, 0.0))
    R.tail(segs)
    return R


def neck_set(R, a=None, dp=(0, 0, 0, 0), yaw=0.0, roll=0.0, head_roll=0.0):
    """Neck from the rest angles (or [a]) plus [dp] degrees on each part, turned [yaw] to the left."""
    a = list(REST_NECK if a is None else a)
    neck_abs(R, [x + d for x, d in zip(a, dp)], yaw, roll, head_roll)
    return R


# ================================================================== loops: idle

@define('idle', 80, EVERYTHING, loop=True)
def idle(t):
    u = t / 80.0
    R = Rig()
    br = -cos_(t / 40.0)                         # a breath every 40 ticks: 0 at the bottom of the exhale, rising
    br = 0.5 + 0.5 * br                          # 0..1 (1 = lungs full)
    # weight rocks from one side to the other and back over the loop
    lat = 1.3 * sin_(u)
    R_roll = -0.9 * sin_(u)
    stance(R, up=-0.8 + 0.6 * br, lat=lat, pitch=0.5 * br - 0.2, yaw=0.6 * sin_(u + 0.2), roll=R_roll,
           hips=dict(pitch=-0.4 * br + 0.3 * cos_(u), yaw=-1.0 * sin_(u - 0.1), roll=0.7 * sin_(u)))
    # the head looks about: left, back, right, back
    gaze = kf(t, [(0, (0, 0)), (9, (0, 0)), (17, (15, -3)), (30, (16, -2)), (37, (3, 1)), (46, (-3, 2)),
                  (54, (-14, 3)), (63, (-15, 3)), (72, (0, 0)), (80, (0, 0))])
    yaw, pit = gaze
    gaze_n = kf(t, [(0, (0, 0)), (11, (0, 0)), (20, (11, -2)), (31, (12, -2)), (39, (2, 0)), (48, (-2, 1)),
                    (56, (-10, 2)), (65, (-11, 2)), (74, (0, 0)), (80, (0, 0))])
    nod = lambda lag: -cos_(2 * (t - lag) / 80.0)
    neck_set(R, dp=(0.9 * br - 0.4 * pit * 0.2, 0.7 * br + 0.6 * pit * 0.3, 0.8 * br + 0.8 * pit, -1.4 * br + 1.1 * pit - 0.6 * nod(0)),
             yaw=gaze_n[0] * 0.7 + 1.0 * sin_(u + 0.15), roll=0.0, head_roll=0.35 * gaze[0] / 15.0)
    # the head leads the neck a little further round
    R.q['head'] = qmul(R.q['head'], qeuler(y=-(yaw - gaze_n[0]) * 0.18))
    R.jaw(1.0 + 1.4 * (1 - br) + 0.5 * bump(t, 28, 40))
    tail_curve(R, u, pitch_amp=0.5, pitch_phase=0.2)
    folded(R, jig=1.2 * br - 0.5 + 1.0 * bump(t, 48, 62))
    return R


# ================================================================== loops: walk, sleep

@define('walk', WALK_LENGTH, EVERYTHING, loop=True)
def walk(t):
    return walk_pose(t / float(WALK_LENGTH))


LIE_DROP = 11.0          # how far the body sinks when it lies on its belly
LIE_FEET = {'fr': V(-12.5, 21.5, -31.0), 'fl': V(12.5, 21.5, -31.0), 'hr': V(-14.5, 21.5, 27.0), 'hl': V(14.5, 21.5, 27.0)}
LIE_POLE = {'fr': V(0, -1.0, 0.6), 'fl': V(0, -1.0, 0.6), 'hr': V(0, -0.5, -1.0), 'hl': V(0, -0.5, -1.0)}


def _lie_swing():
    """How far (degrees, toward the leg's own side) each leg's elbow or knee swings round its leg to point as LIE_POLE says,
    for the leg lying on the ground. Turning the bend by this much is smooth where blending two directions is not."""
    R = Rig()
    R.body(pos=(0, LIE_DROP, 0))
    R.hips(pos=(0, 4.5, 0), pitch=-1.0)
    out = {}
    for k in LEG_KEYS:
        L = LEGS[k]
        Rp, Tp = R.world(L.parent)
        H = Tp + Rp @ np.array(IDX[L.parts[0]].pivot, float)
        d = Rp.T @ (LIE_FEET[k] - H)
        e1 = d / np.linalg.norm(d)
        r = auto_pole(L, d)
        v = LIE_POLE[k] - np.dot(LIE_POLE[k], e1) * e1
        v = v / np.linalg.norm(v)
        out[k] = math.degrees(math.acos(clamp(float(np.dot(r, v)), -1.0, 1.0)))
    return out


LIE_SWING = _lie_swing()


def lying(R, s=1.0, br=0.0, pitch=0.0, lat=0.0, roll=0.0, yaw=0.0, feet=None, sf=None, sh=None, hips_up=None, lift=None):
    """The body lying on its belly ([s] = 1) or standing ([s] = 0), the feet reaching their folded places on the ground
    ([feet] overrides them); [sf] and [sh] say how far the forelegs and the hind legs are folded if not [s]. [lift] raises
    a foot (leg -> px) while it is in the air."""
    sf = s if sf is None else sf
    sh = s if sh is None else sh
    R.body(pos=(lat, LIE_DROP * s - br, 0.0), pitch=pitch, yaw=yaw, roll=roll)
    R.hips(pos=(0, (4.5 * s if hips_up is None else hips_up), 0), pitch=-1.0 * s)
    for k in LEG_KEYS:
        kk = sf if LEGS[k].front else sh
        tgt = lerp(LEGS[k].stand, LIE_FEET[k] if not feet or k not in feet else feet[k], kk)
        if lift and k in lift:
            tgt = tgt + V(0, -lift[k], 0)
        R.leg(k, tgt, foot=(0.0, LEGS[k].sgn * -TOE_OUT, 0.0), pole=LIE_SWING[k] * smooth(kk))
    return R


def _sleep_neck():
    R = Rig()
    lying(R, 1.0, br=0.0)
    R.jaw(2.0)
    return ground_neck(R, lambda y: neck_ik_pose(R, (18.0, y, -21.0), (-8, -10), [(50, -10), (65, -30), (40, -35)]), 7.5)


SLEEP_SEGS = cached('sleep_neck', _sleep_neck)


def sleep_pose(t, breath=True):
    u = t / 120.0
    R = Rig()
    br = 0.5 - 0.5 * cos_(t / 60.0) if breath else 0.5          # a breath each 60 ticks, 1 = lungs full
    lying(R, 1.0, br=1.9 * br, pitch=0.6 * br)
    # the neck curls round to the left and the head rests on the ground by the left forefoot
    neck_pose(R, [(h, p + (2.0 * br if i == 0 else 0.0)) for i, (h, p) in enumerate(SLEEP_SEGS)])
    R.jaw(2.0 + 1.0 * br)
    R.lids(1.0)
    # the tail curls round to the left
    R.tail([(p, y, 0.0) for p, y in zip(SLEEP_TAIL_PITCH, SLEEP_TAIL_YAWS)])
    folded(R, jig=0.8 * br, clear=True)
    return R


SLEEP_TAIL_YAWS = [-8, -24, -26, -26, -22]


define('sleep', 120, EVERYTHING + ['lids'], loop=True)(sleep_pose)


# ================================================================== loops: hover, fly, glide

def wingbeat(p, lows, highs, lag, sweep_lo=(0, 0, 0, 0, 0), sweep_hi=(0, 0, 0, 0, 0), twist=(0, 0, 0, 0, 0), tuck=0.0, top=0.55):
    """A right wing at beat phase [p] (0 at the bottom of the downbeat). Each part's turn from rest (flap up, sweep back and
    twist, in its parent's frame) runs between its [lows] and [highs] as the beat rises and falls, [lag] loops later down
    the arm; [tuck] bends the elbow and wrist back on the upstroke."""
    ang = []
    for i in range(5):
        q = p - lag[i]
        h = beat(q, top)
        up = math.sin(math.pi * (q % 1.0) / top) if (q % 1.0) < top else 0.0
        fl = lerp(lows[i], highs[i], h)
        sw = lerp(sweep_lo[i], sweep_hi[i], h) + (tuck * up if i in (1, 2) else 0.0)
        tw = twist[i] * (h - 0.5)
        ang.append((tw, fl, sw))
    return wing_euler(ang)


HOVER_PITCH = 30.0


def hanging_legs(R, drop_f=17.0, drop_h=19.0, fwd_f=3.0, fwd_h=-1.0, sway=0.0, kick=0.0):
    """Both pairs of legs dangling below the body."""
    for k in ('fr', 'fl'):
        hang(R, k, drop_f, fwd=fwd_f + kick, out=1.5, foot=(18, 0, 0), pole=V(0, 0, 1.0))
    for k in ('hr', 'hl'):
        hang(R, k, drop_h, fwd=fwd_h + kick * 0.5, out=1.0, foot=(14, 0, 0), pole=V(0, 0, -1.0))
    return R


def hover_pose(t):
    p = (t - 7) / 20.0                                   # the downbeat finishes at t = 7
    h = beat(p)
    R = Rig()
    R.body(pos=(0, lerp(-2.8, 2.0, h), 0), pitch=HOVER_PITCH + 2.0 * (0.5 - h), about_pt=(0, 0, 6))
    R.hips(pitch=-9 + 2.0 * (0.5 - h))
    # the neck arches up and over, the head looking out ahead and down a little
    neck_pose(R, [(1.0 * sin_(p), 28 - 30 + 2 * h), (1.5 * sin_(p - 0.05), 18 - 30 + 2 * h), (2 * sin_(p - 0.1), -2 - 30 + 2 * h),
                  (3 * sin_(p - 0.15), -28 + 3 * h)])
    R.jaw(2.0)
    R.tail([(-14 + 3 * h, 2 * sin_(p - 0.1 * i), 0) for i in range(5)])
    hanging_legs(R, sway=0.0)
    lows = (-34, -10, -12, -4, -2)
    highs = (46, 10, 10, 4, 2)
    lag = (0, 0.05, 0.10, 0.14, 0.16)
    d = wingbeat(p, lows, highs, lag, tuck=18)
    R.wing('r', d); R.wing('l', d)
    return R


define('hover', 20, EVERYTHING, loop=True, air=True)(hover_pose)


def tuck_legs(R, fold=1.0, front=(9.0, 15.0, -13.0), hind=(8.0, 15.0, 33.0)):
    """The legs drawn up for flight ([fold] 1) or hanging as hover leaves them (0): the forelegs folded against the chest,
    the hind legs trailing back under the tail."""
    for k in LEG_KEYS:
        L = LEGS[k]
        fr = L.front
        tx, ty, tz = front if fr else hind
        tgt = V(L.sgn * tx, ty, tz)
        R.leg(k, tgt, foot=(30 if fr else 22, 0, 0), pole=V(0, 0.2, 1.0) if fr else V(0, 0.3, -1.0), frame='b')
    return R


def fly_pose(t, glide=False, u=0.0):
    p = (t - 9) / 30.0
    h = beat(p)
    R = Rig()
    R.body(pos=(0, lerp(-2.4, 2.4, h), 0), pitch=2.2 * (0.5 - h), about_pt=(0, 0, 6))
    R.hips(pitch=1.0 * (h - 0.5))
    neck_pose(R, [(0, 22 + 2.5 * (0.5 - h)), (0, 14 + 3 * (0.5 - h)), (0, 5 + 3 * (0.5 - h)), (0, -6 + 4 * (h - 0.5))])
    R.jaw(0.0)
    R.tail([(0.0 + 3.5 * (h - 0.5) * (1 + 0.3 * i), 3.0 * sin_(p - 0.07 * i), 0) for i in range(5)])
    tuck_legs(R)
    lows = (-26, -8, -10, -4, -2)
    highs = (38, 8, 12, 4, 2)
    lag = (0, 0.06, 0.12, 0.17, 0.2)
    d = wingbeat(p, lows, highs, lag, sweep_lo=(-8, 0, 0, 0, 0), sweep_hi=(6, 0, 0, 0, 0), tuck=22)
    R.wing('r', d); R.wing('l', d)
    return R


define('fly', 30, EVERYTHING, loop=True, air=True)(fly_pose)


def glide_pose(t):
    u = t / 80.0
    R = Rig()
    R.body(pos=(0, 1.2 * sin_(2 * u) , 0), pitch=-1.0 + 1.2 * sin_(2 * u - 0.15), roll=1.2 * sin_(u), about_pt=(0, 0, 6))
    R.hips(pitch=0.6 * sin_(2 * u - 0.3), roll=-0.6 * sin_(u))
    neck_pose(R, [(2.0 * sin_(u), 20 + 1.2 * sin_(2 * u)), (3.0 * sin_(u - 0.04), 12), (4.0 * sin_(u - 0.08), 3), (5.0 * sin_(u - 0.12), -8 + 1.5 * sin_(2 * u))])
    R.jaw(0.0)
    R.tail([(0.8 * sin_(2 * (u - 0.07 * i)), 3.5 * sin_(u - 0.08 * i), 0.0) for i in range(5)])
    tuck_legs(R, hind=(7.0, 14.0, 34.0))
    lag = (0.0, 0.04, 0.08, 0.12, 0.14)
    amp = (2.2, 2.0, 3.0, 3.2, 3.5)
    ang = []
    base = [(0, 13, 7), (0, 2, 4), (0, 3, 3), (0, 2, 0), (0, 3, 0)]
    for i in range(5):
        w = sin_(2 * u - lag[i] * 2)
        tw, fl, sw = base[i]
        ang.append((tw + (3.0 * w if i == 2 else 1.0 * w), fl + amp[i] * w, sw + 0.8 * w))
    d = wing_euler(ang)
    R.wing('r', d); R.wing('l', d)
    return R


define('glide', 80, EVERYTHING, loop=True, air=True)(glide_pose)


# ================================================================== attacks on the ground

def add_mid(R, bone, pitch=0.0, yaw=0.0, roll=0.0):
    """An 'add' key that turns [bone] a little further than the layers under it do (pitch up, yaw left, roll left-down)."""
    R.add(bone, qm(Ry(-yaw) @ Rx(-pitch) @ Rz(roll)))
    return R


def tail_add(R, pitch=None, yaw=None):
    """'add' keys for the tail: [pitch] (down is positive, as for the tail's own pitch) and [yaw] each a list of five."""
    for i, b in enumerate(TAIL):
        add_mid(R, b, pitch=(pitch[i] if pitch else 0.0), yaw=(yaw[i] if yaw else 0.0))
    return R


def head_to(R, py, pz, pitch, yaw=0.0, roll=0.0, prior=None, wp=0.02, hx=0.0):
    """Neck bent so the head's pivot is at ([hx], py, pz) in the body's frame with the head [pitch] degrees nose up and
    turned [yaw] to the left; the neck's own bend is held near [prior]."""
    # the neck lies in a vertical plane turned by the head's yaw share, so aim the pivot along it
    prior = prior or [(0.5 * yaw, 28.0 - 10.0), (0.75 * yaw, 18.0), (0.9 * yaw, 6.0)]
    segs = neck_ik((hx, py, pz), (yaw, pitch, roll), prior, wp=wp)
    neck_pose(R, segs)
    return segs


GROUND_SET = ['body', 'hips'] + NECK[:3] + ['head', 'jaw'] + LEG_BONES      # what a ground attack that moves the body keys 'set'
IDLE_BASE = lambda t: [('idle', t, 1.0)]


@define('bite', 32, GROUND_SET, adds=TAIL, base=IDLE_BASE, marks={'blow': 16})
def bite(t):
    R = Rig()
    # weight goes back on the haunches, then the body lunges in as the head strikes
    back = kf(t, [(0, 0.0), (12, 1.0, 'inout'), (13, 1.0), (16, 0.0, 'in'), (32, 0.0, 'inout')])
    lunge = kf(t, [(0, 0.0), (11.5, 0.0), (16, 1.0, 'out'), (22, 1.0), (32, 0.0, 'inout')])
    step = kf(t, [(0, 0.0), (11, 0.0), (13.5, 1.0, 'out'), (16, 1.0), (22, 1.0), (27, 1.0), (32, 0.0, 'inout')])   # the forefeet step in
    lift_f = bump(t, 11, 16.5) * 3.2 + bump(t, 23, 31) * 2.4
    feet = {}
    for k in ('fr', 'fl'):
        feet[k] = V(0.0, -lift_f, -5.0 * step)
    stance(R, up=-0.5 + 1.2 * back - 2.4 * lunge, fwd=-1.5 * back + 7.0 * lunge, pitch=3.5 * back - 6.5 * lunge,
           hips=dict(pitch=-1.5 * back + 2.0 * lunge, up=-0.8 * back), feet=feet)
    # the head: drawn back and up, jaws opening; thrown forward and down; shut on the blow; drawn back in
    py = kf(t, [(0, -13.6), (12, -19.0, 'inout'), (13, -19.0), (16, -1.0, 'out'), (22, 0.0, 'inout'), (32, -13.6, 'inout')])
    pz = kf(t, [(0, -36.1), (12, -22.0, 'inout'), (13, -22.0), (16, -39.0, 'out'), (22, -38.0, 'inout'), (32, -36.1, 'inout')])
    hp = kf(t, [(0, -12.0), (12, 14.0, 'inout'), (13, 14.0), (16, -26.0, 'out'), (22, -22.0, 'inout'), (32, -12.0, 'inout')])
    shake = bump(t, 16, 24) * sin_((t - 16) / 3.0)
    head_to(R, py, pz, hp, yaw=5.0 * shake, prior=[(0, 28), (0, 18), (0, 6)], wp=0.03)
    R.jaw(kf(t, [(0, 1.5), (3, 2.5, 'inout'), (11, 38.0, 'inout'), (14.5, 40.0), (16, 0.0, 'in3'), (17.5, 4.0, 'out'), (19, 0.5, 'in'), (24, 0.5), (32, 1.5, 'inout')]))
    tail_add(R, pitch=[-5.0 * bump(t, 12, 28) * (1 + 0.3 * i) for i in range(5)], yaw=[3.0 * bump(t, 12, 30) * sin_((t - 12) / 24.0 + 0.1 * i) for i in range(5)])
    return R


REAR_PT = (0.0, 2.0, 14.0)           # the line through the hind hip joints: the body rears up about it


def to_world(R, bone, p):
    return R.point(bone, p)


def blend_leg(R, key, w_target, b_target, k, foot=(0, 0, 0), foot_b=(30, 0, 0), pole=None):
    """A leg whose foot goes from a place on the ground [w_target] to a place on the body [b_target] as [k] goes 0 -> 1."""
    Rb, Tb = R.world('body')
    wb = Tb + Rb @ np.asarray(b_target, float)
    tgt = lerp(np.asarray(w_target, float), wb, k)
    ft = tuple(lerp(np.array(foot, float), np.array(foot_b, float), k))
    R.leg(key, tgt, foot=ft, pole=None if pole is None else np.asarray(pole, float))
    return R


@define('claw', 36, GROUND_SET, adds=TAIL, base=IDLE_BASE, marks={'blow': 18})
def claw(t):
    R = Rig()
    rise = kf(t, [(0, 0.0), (12, 1.0, 'inout'), (16, 1.0), (21, 0.9, 'out'), (34, 0.0, 'inout')])
    yaw = kf(t, [(0, 0.0), (12, -7.0, 'inout'), (15, -9.0), (20, 13.0, 'out'), (24, 10.0), (34, 0.0, 'inout')])
    roll = -1.5 * rise + kf(t, [(0, 0), (15, 0), (20, 3.0, 'out'), (34, 0, 'inout')])
    R.body(pos=(1.8 * rise, -0.5 - 0.3 * rise, 0), pitch=15.0 * rise, yaw=yaw, roll=roll, about_pt=REAR_PT)
    R.hips(pitch=-5.0 * rise, yaw=-yaw * 0.3)
    for k in ('hr', 'hl'):
        R.leg(k, LEGS[k].stand, foot=STAND_FOOT[k])
    # the left forepaw comes off the ground and draws up under the chest
    blend_leg(R, 'fl', LEGS['fl'].stand, V(9.0, 13.0, -17.0), smooth(rise * 1.2), foot=STAND_FOOT['fl'], foot_b=(35, 0, 0))
    # the right forepaw: up and back (cocked), then over the top and across in front of the chest, then home. Its path is
    # in the body's frame, so the paw goes with the body as it rears; at the end it is put back on the ground.
    cock = kf(t, [(0, 0.0), (12, 1.0, 'inout'), (14.5, 1.0), (16, 0.0, 'in')])
    swipe = kf(t, [(0, 0.0), (15, 0.0), (20, 1.0, 'out'), (22.5, 1.0), (30, 0.0, 'inout')])
    stand_b = V(-11.0, 21.5, -10.0)
    raised = V(-18.0, -14.0, -7.0)
    out_ = V(2.0, 12.0, -25.0)
    shoulder = V(*IDX['leg_fr'].pivot)
    out_mid = shoulder + 18.0 * unit(V(-0.9, 0.2, -0.35))               # swung out to the side on the way up
    if t < 12:
        e = smooth(t / 12.0)
        b_tgt = (1 - e) * (1 - e) * stand_b + 2 * (1 - e) * e * (2 * out_mid - 0.5 * (stand_b + raised)) + e * e * raised
    elif t < 15:
        b_tgt = raised + V(-0.8, -0.5, 0.0) * smooth((t - 12) / 3.0)
    elif t < 20:
        e = smooth((t - 15) / 5.0)
        mid = V(-7.0, -2.0, -29.0)
        b_tgt = (1 - e) * (1 - e) * (raised + V(-0.8, -0.5, 0.0)) + 2 * (1 - e) * e * (mid + V(-1.0, -4.0, -4.0)) + e * e * out_
    elif t < 22.5:
        b_tgt = out_
    else:
        b_tgt = lerp(out_, stand_b, smooth((t - 22.5) / 11.5)) + V(0.0, -6.0 * bump(t, 22.5, 34), 0.0)
    home = smooth((t - 28.0) / 6.0)                                  # the paw ends on the ground where it began
    w_tgt = lerp(to_world(R, 'body', b_tgt), LEGS['fr'].stand, home)
    away = smooth(min(1.0, abs(b_tgt[1] - 21.5) / 12.0)) * (1.0 - home)       # how far off the ground it is
    R.leg('fr', w_tgt, foot=((35.0 - 20.0 * swipe) * away, STAND_FOOT['fr'][1] * (1 - away), 0.0), pole=(V(-1.0, 0.25, 0.35), away))
    a = [38 - 8 * rise + 6 * cock, 28 - 13 * rise + 4 * cock, 14 - 14 * rise, -12 - 13 * rise + 5 * cock]
    neck_set(R, a=a, yaw=kf(t, [(0, 0.0), (12, -6.0, 'inout'), (15, -8.0), (20, 14.0, 'out'), (24, 10.0), (34, 0.0, 'inout')]))
    R.jaw(kf(t, [(0, 1.5), (8, 18.0, 'inout'), (14, 24.0), (18, 30.0, 'out'), (24, 14.0, 'inout'), (34, 1.5, 'inout')]))
    tail_add(R, pitch=[-4.0 * rise] * 5, yaw=[2.0 * sin_((t - 12) / 24.0 + 0.1 * i) * bump(t, 10, 32) for i in range(5)])
    return R


GROUND_TAIL_SET = GROUND_SET + TAIL
IDLE_DROOP = 1.0                       # how far each tail part hangs below its rest pitch in the idle (degrees)


@define('tail_sweep', 46, GROUND_TAIL_SET, base=IDLE_BASE, marks={'blow0': 22, 'blow1': 26})
def tail_sweep(t):
    R = Rig()
    coil = kf(t, [(0, 0.0), (18, 1.0, 'inout'), (20.5, 1.0, 'in'), (28, 0.0, 'out'), (31, -0.1), (46, 0.0, 'inout')])
    whip = kf(t, [(0, 0.0), (19.5, 0.0), (28, 1.0, 'inout'), (31, 1.0), (46, 0.0, 'inout')])
    # the body winds up to the left, then unwinds into the whip
    yaw = 20.0 * coil - 14.0 * whip if t < 31 else kf(t, [(31, -14.0), (46, 0.0, 'inout')])
    hyaw = -16.0 * coil + 10.0 * whip if t < 31 else kf(t, [(31, 10.0), (46, 0.0, 'inout')])
    R.body(pos=(-1.2 * coil, -0.5 + 0.8 * coil, 0.0), pitch=-1.0 * coil, yaw=yaw, roll=-2.5 * coil + 4.0 * whip if t < 31 else kf(t, [(31, 4.0), (46, 0, 'inout')]))
    R.hips(yaw=hyaw, roll=0.0)
    for k in LEG_KEYS:
        R.leg(k, LEGS[k].stand, foot=STAND_FOOT[k])
    # the head looks back over the left shoulder, then round with the whip
    hd = kf(t, [(0, 0.0), (18, 38.0, 'inout'), (22, 38.0), (28, -12.0, 'inout'), (34, -8.0), (46, 0.0, 'inout')])
    neck_set(R, a=[38 - 6 * coil, 28 - 4 * coil, 14 - 2 * coil, -12 - 6 * coil], yaw=hd, head_roll=-6.0 * coil)
    R.jaw(kf(t, [(0, 1.5), (10, 14.0, 'inout'), (22, 20.0), (30, 4.0, 'inout'), (46, 1.5, 'inout')]))
    # the tail: curled to the left (cocked), whipped round through the back to the right, then settling
    segs = []
    for i in range(5):
        lag = 0.7 * i
        c = kf(t, [(0, 0.0), (18, 1.0, 'inout'), (20, 1.0)])
        w = kf(t - lag, [(0, 0.0), (20, 0.0), (28, 1.0, 'inout'), (46, 0.0, 'inout')]) if t < 28 + lag else kf(t - lag, [(28, 1.0), (31, 1.0), (46, 0.0, 'inout')])
        yawi = lerp(-24.0 * c, 21.0, w) if t < 28 + lag else 21.0 * w
        lift = 6.0 * bump(t, 19, 31 + 2 * i) * (1.0 if i > 0 else 0.4) + 5.0 * c * (1 if i < 2 else 0.0)
        segs.append((IDLE_DROOP - lift, yawi, 0.0))
    R.tail(segs)
    return R


def wing_open(k, flap=0.0, sweep=0.0, twist=0.0, tip=0.0):
    """A right wing [k] of the way from folded (0) to spread (1), then raised [flap] degrees at the shoulder (and the same
    again, shared down the arm), swept back [sweep] and twisted."""
    base = wslerp(FOLD, SPREAD, k)
    d = wing_euler([(twist, flap, sweep), (0, 0.25 * flap, 0.3 * sweep), (0, 0.2 * flap + tip, 0), (0, 0.1 * flap + tip, 0), (0, 0.1 * flap + tip, 0)])
    return [qmul(b, e) for b, e in zip(base, d)]


def wings(R, d, left=None):
    R.wing('r', d)
    R.wing('l', d if left is None else left)
    return R


def wings_clear(R, make, flap, margin=0.5):
    """Sets both wings as make(flap) -> (right, left) says, with [flap] (degrees up) raised just as far as it must be to keep
    the wings off the ground. Needs the body already set."""
    def ok(f):
        wings(R, *make(f))
        return lowest(R, WING_BONES)[0] <= GROUND - margin
    if ok(flap):
        return flap
    lo, hi = flap, max(flap, 0.0)
    if not ok(hi):
        hi = hi + 40.0
    for _ in range(14):
        m = 0.5 * (lo + hi)
        if ok(m):
            hi = m
        else:
            lo = m
    ok(hi)
    return hi


@define('stomp', 42, GROUND_SET + WING_BONES, adds=TAIL, base=IDLE_BASE, marks={'blow': 22})
def stomp(t):
    R = Rig()
    crouch = kf(t, [(0, 0.0), (5, 1.0, 'inout'), (8, 0.6, 'inout'), (8.01, 0.6)]) if t < 8 else 0.6 * (1 - smooth((t - 8) / 4.0))
    rise = kf(t, [(0, 0.0), (5, 0.0), (17, 1.0, 'inout'), (18.5, 1.0), (22, 0.0, 'in'), (24.5, -0.04, 'out'), (31, 0.0, 'inout'), (42, 0.0)])
    sink = kf(t, [(0, 0.0), (21, 0.0), (22.3, 3.4, 'in'), (24.5, 3.8, 'out'), (30, 1.4, 'inout'), (38, 0.0, 'inout'), (42, 0.0)])
    R.body(pos=(0, -0.5 + 1.8 * crouch - 3.2 * rise + sink, 0), pitch=34.0 * rise, about_pt=REAR_PT)
    R.hips(pitch=-9.0 * rise + 0.6 * sink)
    for k in ('hr', 'hl'):
        R.leg(k, LEGS[k].stand + V(0, 0, 0), foot=STAND_FOOT[k])
    ground = {'fr': V(-11.5, 21.5, -12.5), 'fl': V(11.5, 21.5, -12.5)}
    k_down = smooth((t - 16.0) / 5.5)
    for k in ('fr', 'fl'):
        sgn = LEGS[k].sgn
        tucked = V(sgn * 10.5, 9.0 + 2.0 * bump(t, 0, 20), -17.0)
        # the forefeet are drawn up before they come down: held at the chest, lifting to the first rear, then striking
        if t < 17:
            lift_k = smooth(clamp(t / 9.0))
            R.leg(k, to_world(R, 'body', lerp(V(sgn * 11.0, 21.5, -10.0), tucked, lift_k)), foot=(30 * lift_k, sgn * -TOE_OUT * (1 - lift_k), 0))
        else:
            tw = to_world(R, 'body', tucked + V(0, 4.0 * smooth((t - 17) / 4.0), -5.0 * smooth((t - 17) / 4.0)))
            tgt = lerp(tw, ground[k], k_down)
            R.leg(k, tgt, foot=(lerp(30, 0, k_down), sgn * -TOE_OUT * k_down, 0))
    rise_a = V(46.0, 42.0, 24.0, 10.0)
    slam_a = V(30.0, 14.0, -2.0, -30.0)
    neck_set(R, a=kf(t, [(0, V(*REST_NECK)), (5, V(40.0, 28.0, 14.0, -14.0), 'inout'), (15, rise_a, 'inout'), (19, rise_a), (22, slam_a, 'in'), (26, V(34.0, 18.0, 4.0, -26.0), 'out'),
                         (38, V(*REST_NECK), 'inout')]))
    R.jaw(kf(t, [(0, 1.5), (12, 16.0, 'inout'), (19, 24.0), (22, 6.0, 'in'), (30, 2.0, 'inout'), (42, 1.5, 'inout')]))
    wide = kf(t, [(0, 0.0), (14, 0.55, 'inout'), (19, 0.6), (22, 0.85, 'out'), (24, 0.5, 'inout'), (34, 0.0, 'inout')])
    lift = kf(t, [(0, 0.0), (14, 14.0, 'inout'), (19, 18.0), (22, -22.0, 'out'), (26, -5.0), (34, 0.0, 'inout')])
    d = wing_open(wide, flap=lift, sweep=0.0)
    wings(R, d)
    tail_add(R, pitch=[-3.0 * rise + 2.0 * bump(t, 20, 34) for i in range(5)], yaw=[0.0] * 5)
    return R


@define('wing_buffet', 38, GROUND_SET + WING_BONES, adds=TAIL, base=IDLE_BASE, marks={'blow': 18})
def wing_buffet(t):
    R = Rig()
    lean = kf(t, [(0, 0.0), (14, 1.0, 'inout'), (15, 1.0), (19, -0.7, 'out'), (22, -0.6), (32, 0.0, 'inout'), (38, 0.0)])
    R.body(pos=(0, -0.5 - 1.2 * max(lean, 0.0) + 1.5 * max(-lean, 0.0), 2.5 * max(-lean, 0.0)), pitch=9.0 * lean, about_pt=REAR_PT)
    R.hips(pitch=-3.0 * max(lean, 0.0))
    for k in LEG_KEYS:
        tgt = LEGS[k].stand + (V(0, 0, -2.0 * max(-lean, 0.0)) if k[0] == 'f' else V(0, 0, 0))
        R.leg(k, tgt, foot=STAND_FOOT[k])
    # the head goes up and back, then drives forward with the beat
    neck_set(R, a=kf(t, [(0, V(*REST_NECK)), (14, V(46.0, 38.0, 22.0, 4.0), 'inout'), (16, V(46.0, 38.0, 22.0, 4.0)), (20, V(30.0, 12.0, -4.0, -24.0), 'out'),
                         (26, V(32.0, 16.0, 2.0, -20.0), 'inout'), (38, V(*REST_NECK), 'inout')]))
    R.jaw(kf(t, [(0, 1.5), (10, 14.0, 'inout'), (16, 18.0), (20, 4.0, 'in'), (38, 1.5, 'inout')]))
    # wings: up and open, held a beat, then one great stroke forward and down, and folded away
    openk = kf(t, [(0, 0.0), (14, 1.0, 'inout'), (20, 1.0), (22, 1.0), (34, 0.0, 'inout')])
    flap = kf(t, [(0, 0.0), (14, 52.0, 'inout'), (15, 54.0), (18, 8.0, 'inout'), (20, -26.0, 'out'), (23, -20.0, 'inout'), (34, 0.0, 'inout')])
    sweep = kf(t, [(0, 0.0), (14, 14.0, 'inout'), (15, 16.0), (18, -20.0, 'inout'), (20, -46.0, 'out'), (23, -40.0, 'inout'), (34, 0.0, 'inout')])
    tipk = kf(t, [(0, 0.0), (14, 3.0), (15, 4.0), (18, -2.0), (20, -8.0, 'out'), (24, -4.0), (34, 0.0, 'inout')])
    wings_clear(R, lambda f: (wing_open(openk, flap=f, sweep=sweep, tip=tipk), None), flap)
    tail_add(R, pitch=[-2.0 * lean for i in range(5)], yaw=[0.0] * 5)
    return R


@define('breath', 72, GROUND_SET, adds=TAIL, base=IDLE_BASE, marks={'stream0': 26, 'stream1': 58})
def breath(t):
    R = Rig()
    inh = kf(t, [(0, 0.0), (21, 1.0, 'inout'), (22, 1.0), (24, 0.0, 'in'), (72, 0.0)])         # the chest swells and the head rears
    aim = kf(t, [(0, 0.0), (22, 0.0), (26, 1.0, 'out'), (58, 1.0), (66, 0.0, 'inout'), (72, 0.0)])
    # the body: chest lifted for the inhale, then crouched and leaning into the stream
    stance(R, up=-0.5 + 2.4 * inh + (BREATH_BODY['up'] + 0.5) * aim, fwd=BREATH_BODY['fwd'] * aim, pitch=4.0 * inh + BREATH_BODY['pitch'] * aim,
           hips=dict(pitch=-1.5 * inh + 1.0 * aim, roll=-1.0 * sin_((t - 26) / 64.0) * aim))
    inhale = aim_segs('breath_inhale', -0.5 + 2.4, 0.0, 4.0, V(0.0, -27.0, -40.0), 16.0, [(0, 66), (0, 36), (0, 16)])
    # (the stream pose is solved for the body as it stands during the stream: see BREATH_AIM)
    aimed = BREATH_AIM()
    sweep = lerp(-30.0, 30.0, clamp((t - 26.0) / 32.0)) if 26.0 <= t <= 58.0 else (-30.0 if t < 26.0 else 30.0)
    base_a = REST_SEGS
    seg_in = [(lerp(a[0], b[0], inh), lerp(a[1], b[1], inh)) for a, b in zip(base_a, inhale)]
    if aim > 0:
        pulse = [0.8 * sin_((t - 26) / 6.0 - 0.08 * i) * bump(t, 26, 58) for i in range(4)]
        neck_blend(R, seg_in, aimed, aim, sweep=sweep * aim, wob=pulse)
    else:
        neck_blend(R, base_a, inhale, inh)
    R.jaw(kf(t, [(0, 1.5), (14, 0.0, 'inout'), (22, 0.0), (25.5, 40.0, 'out'), (58, 40.0), (64, 1.5, 'inout'), (72, 1.5)]) + 1.6 * sin_((t - 26) / 3.0) * bump(t, 27, 57))
    tail_add(R, pitch=[-3.0 * inh - 2.0 * aim for i in range(5)], yaw=[-0.18 * sweep * aim * (0.6 + 0.2 * i) for i in range(5)])
    return R


def BREATH_AIM():
    """The neck at the stream, from the crouched body: the mouth about 5.75 blocks ahead and 3.4 up, the head 22 degrees down."""
    return aim_segs('breath_aim', BREATH_BODY['up'], BREATH_BODY['fwd'], BREATH_BODY['pitch'], V(0.0, -4.0, -47.0), -20.0, [(0, 70), (0, 30), (0, -10)])


ROAR_MOUTH = V(0.0, -38.0, -44.0)


def ROAR_AIM():
    """The neck for the ground roar: head thrown up and forward, the body's chest lifted."""
    return aim_segs('roar_aim', -2.0, 0.0, 6.0, ROAR_MOUTH, 34.0, [(0, 58), (0, 50), (0, 34)])


@define('roar', 50, GROUND_SET + WING_BONES, adds=TAIL, base=IDLE_BASE, marks={'sound': 8, 'jaw_open': 13, 'jaw_shut': 40})
def roar(t):
    R = Rig()
    wind = kf(t, [(0, 0.0), (7, 1.0, 'inout'), (8, 0.8, 'in'), (13, 0.0, 'out'), (50, 0.0)])
    up = kf(t, [(0, 0.0), (8, 0.0), (13, 1.0, 'out'), (37, 1.0), (45, 0.0, 'inout'), (50, 0.0)])
    heave = sin_((t - 8) / 10.0) * bump(t, 8, 44)
    stance(R, up=-0.5 + 1.5 * wind + 2.0 * up + 0.5 * heave, pitch=2.0 * wind + 6.0 * up + 0.6 * heave, fwd=0.0,
           hips=dict(pitch=-1.0 * up), feet={k: V(LEGS[k].sgn * 1.2 * up, 0, 0) for k in LEG_KEYS})
    drawn = [(0.0, 40.0), (0.0, 38.0), (0.0, 30.0), (0.0, 6.0)]
    aimed = ROAR_AIM()
    seg_w = [(lerp(a[0], b[0], wind), lerp(a[1], b[1], wind)) for a, b in zip(REST_SEGS, drawn)]
    shake = [1.2 * sin_(t / 4.0 + 0.1 * i) * bump(t, 12, 38) for i in range(4)]
    side = 5.0 * sin_((t - 13) / 24.0) * bump(t, 13, 38)
    neck_blend(R, seg_w, aimed, up, sweep=side * up, wob=shake)
    R.jaw(kf(t, [(0, 1.5), (7, 0.5, 'inout'), (8, 0.5), (13, 44.0, 'out'), (37, 44.0), (42, 2.0, 'inout'), (50, 1.5, 'inout')]) + 2.4 * sin_(t / 3.0) * bump(t, 15, 36))
    spread = kf(t, [(0, 0.0), (6, 0.1, 'inout'), (14, 0.55, 'inout'), (38, 0.55), (46, 0.0, 'inout')])
    raise_ = kf(t, [(0, 0.0), (14, 12.0, 'inout'), (38, 12.0), (46, 0.0, 'inout')])
    trem = 3.0 * sin_(t / 4.0) * bump(t, 14, 40)
    dr = wing_open(spread, flap=raise_ + trem, sweep=2.0 * trem)
    dl = wing_open(spread, flap=raise_ - trem, sweep=-2.0 * trem)
    wings(R, dr, dl)
    tail_add(R, pitch=[-2.0 * up] * 5, yaw=[6.0 * sin_((t - 10) / 30.0 - 0.1 * i) * bump(t, 10, 46) for i in range(5)])
    return R


# ================================================================== attacks in the air (neck, head and jaw only)

AIR_SET = AIR_ATTACK                  # neck1-3, head, jaw: 'set'
AIR_ADD = ['body', 'hips'] + TAIL     # and a little 'add'
HOVER_BASE = lambda t: [('hover', t, 1.0)]
FLY_BASE = lambda t: [('fly', t, 1.0)]


def world_neck(R, angles, sweep=0.0, pitch=HOVER_PITCH, wob=None):
    """Neck from angles measured against the horizon (a body held at [pitch]): [angles] are the four parts' pitches (nose up)."""
    segs = [(sweep * c, a - pitch + (wob[i] if wob else 0.0)) for i, (a, c) in enumerate(zip(angles, (0.5, 0.75, 0.9, 1.0)))]
    neck_pose(R, segs)
    return segs


def air_adds(R, lean=0.0, tail_lift=0.0, yaw=0.0):
    add_mid(R, 'body', pitch=lean)
    add_mid(R, 'hips', pitch=-0.5 * lean)
    tail_add(R, pitch=[tail_lift] * 5, yaw=[yaw * (0.4 + 0.2 * i) for i in range(5)])
    return R


def hover_neck_at(t):
    """What the hover's neck holds (world angles) at any time: the hover pose's own."""
    p = (t - 7) / 20.0
    h = beat(p)
    return [28 + 2 * h, 18 + 2 * h, -2 + 2 * h, 2 + 3 * h]


@define('roar_air', 40, AIR_SET, adds=AIR_ADD, base=HOVER_BASE, air=True, marks={'sound': 6, 'jaw_open': 12, 'jaw_shut': 32})
def roar_air(t):
    R = Rig()
    wind = kf(t, [(0, 0.0), (5, 1.0, 'inout'), (6, 0.8, 'in'), (11, 0.0, 'out'), (40, 0.0)])
    up = kf(t, [(0, 0.0), (6, 0.0), (11, 1.0, 'out'), (33, 1.0), (40, 0.0, 'inout')])
    hov = [28.0, 18.0, -2.0, 2.0]
    drawn = [40.0, 38.0, 24.0, 4.0]
    roar_ = [54.0, 58.0, 50.0, 32.0]
    ang = [lerp(lerp(hov[i], drawn[i], wind), roar_[i], up) for i in range(4)]
    shake = [1.4 * sin_(t / 4.0 + 0.1 * i) * bump(t, 10, 34) for i in range(4)]
    world_neck(R, ang, sweep=4.0 * sin_((t - 11) / 22.0) * bump(t, 11, 34), wob=shake)
    R.jaw(kf(t, [(0, 2.0), (5, 0.5, 'inout'), (6, 0.5), (12, 46.0, 'out'), (33, 46.0), (38, 2.0, 'inout'), (40, 2.0)]) + 2.6 * sin_(t / 3.0) * bump(t, 14, 32))
    air_adds(R, lean=2.0 * up - 1.0 * wind, tail_lift=-2.0 * up, yaw=3.0 * sin_((t - 8) / 20.0) * bump(t, 8, 36))
    return R


@define('fireball', 36, AIR_SET, adds=AIR_ADD, base=HOVER_BASE, air=True, marks={'spit': 18})
def fireball(t):
    R = Rig()
    rear = kf(t, [(0, 0.0), (14, 1.0, 'inout'), (15, 1.0), (18, 0.0, 'in'), (36, 0.0)])
    snap = kf(t, [(0, 0.0), (14, 0.0), (18, 1.0, 'out'), (22, 1.0), (34, 0.0, 'inout'), (36, 0.0)])
    hov = [28.0, 18.0, -2.0, 2.0]
    back = [58.0, 52.0, 34.0, 22.0]
    spit = [38.0, 16.0, -10.0, -30.0]
    ang = [lerp(lerp(hov[i], back[i], rear), spit[i], snap) for i in range(4)]
    world_neck(R, ang)
    R.jaw(kf(t, [(0, 2.0), (8, 12.0, 'inout'), (14, 26.0), (17, 38.0, 'out'), (18, 40.0), (20, 6.0, 'in'), (24, 3.0), (36, 2.0, 'inout')]))
    air_adds(R, lean=-3.0 * snap + 2.0 * rear, tail_lift=2.0 * snap - 2.0 * rear)
    return R


@define('fireball_volley', 46, AIR_SET, adds=AIR_ADD, base=HOVER_BASE, air=True, marks={'spit1': 16, 'spit2': 26, 'spit3': 36})
def fireball_volley(t):
    R = Rig()
    hov = [28.0, 18.0, -2.0, 2.0]
    back = [56.0, 50.0, 32.0, 20.0]
    spit = [38.0, 16.0, -10.0, -28.0]
    rear = kf(t, [(0, 0.0), (11, 1.0, 'inout'), (12, 1.0), (16, 0.0, 'in'), (21, 0.7, 'out'), (22, 0.85), (26, 0.0, 'in'), (31, 0.7, 'out'), (32, 0.85), (36, 0.0, 'in'), (46, 0.0)])
    snap = kf(t, [(0, 0.0), (12, 0.0), (16, 1.0, 'out'), (19, 0.8, 'inout'), (22, 0.0), (26, 1.0, 'out'), (29, 0.8, 'inout'), (32, 0.0), (36, 1.0, 'out'), (39, 1.0), (44, 0.0, 'inout'), (46, 0.0)])
    ang = [lerp(lerp(hov[i], back[i], rear), spit[i], snap) for i in range(4)]
    side = kf(t, [(0, 0.0), (16, 0.0), (26, 5.0, 'inout'), (36, -5.0, 'inout'), (46, 0.0, 'inout')])
    world_neck(R, ang, sweep=side)
    jaw = kf(t, [(0, 2.0), (8, 14.0, 'inout'), (15, 38.0, 'out'), (16, 40.0), (18, 8.0, 'in'), (22, 18.0, 'out'), (25, 38.0, 'out'), (26, 40.0), (28, 8.0, 'in'), (32, 18.0, 'out'), (35, 38.0, 'out'), (36, 40.0), (38, 8.0, 'in'), (44, 3.0, 'inout'), (46, 2.0)])
    R.jaw(jaw)
    air_adds(R, lean=-2.5 * snap + 1.5 * rear, tail_lift=2.0 * snap - 1.5 * rear)
    return R


AIR_BREATH_MOUTH = V(0.0, 5.0, -40.0)         # model px: about 5 blocks ahead of the torso's centre and a little under the belly


def AIR_BREATH_AIM():
    return aim_segs('air_breath_aim', 0.0, 0.0, 0.0, AIR_BREATH_MOUTH, -50.0, [(0, 74), (0, 30), (0, -22)])


FLY_NECK = [(0.0, 22.0), (0.0, 14.0), (0.0, 5.0), (0.0, -6.0)]


@define('air_breath', 56, AIR_SET, adds=AIR_ADD, base=FLY_BASE, air=True, marks={'stream0': 18, 'stream1': 48})
def air_breath(t):
    R = Rig()
    curl = kf(t, [(0, 0.0), (10, 1.0, 'inout'), (48, 1.0), (56, 0.0, 'inout')])
    aimed = AIR_BREATH_AIM()
    pulse = [0.9 * sin_((t - 18) / 5.0 - 0.1 * i) * bump(t, 18, 48) for i in range(4)]
    neck_blend(R, FLY_NECK, aimed, curl, sweep=0.0, wob=[w * 1.0 for w in pulse])
    R.jaw(kf(t, [(0, 0.0), (10, 2.0, 'inout'), (18, 40.0, 'out'), (48, 40.0), (54, 1.0, 'inout'), (56, 0.0)]) + 1.4 * sin_((t - 18) / 3.0) * bump(t, 19, 47))
    air_adds(R, lean=-2.5 * curl, tail_lift=-2.0 * curl)
    return R


# ================================================================== takeoff, land, dive

def hang_pt(R, key, drop, fwd=0.0, out=0.0):
    L = LEGS[key]
    Rp, Tp = R.world(L.parent)
    H = Tp + Rp @ np.array(IDX[L.parts[0]].pivot, float)
    return H + V(L.sgn * out, drop, -fwd)


def wing_h(hf, t, lows, highs, lag_t=(0.0, 0.5, 1.0, 1.4, 1.7), sweep_lo=(0, 0, 0, 0, 0), sweep_hi=(0, 0, 0, 0, 0), tuck=18.0, amp=1.0, twist=(0, 0, 0, 0, 0)):
    """A right wing whose height is the function [hf] of time (1 raised, 0 down), each part [lag_t] ticks behind its parent."""
    ang = []
    for i in range(5):
        tt = t - lag_t[i]
        h = hf(tt)
        dh = h - hf(tt - 0.5)
        up = clamp(dh * 3.0, 0.0, 1.0)
        fl = lerp(lows[i] * amp, highs[i] * amp, h)
        sw = lerp(sweep_lo[i], sweep_hi[i], h) + (tuck * up if i in (1, 2) else 0.0)
        ang.append((twist[i] * (h - 0.5), fl, sw))
    return wing_euler(ang)


HOVER_LOWS = (-34, -10, -12, -4, -2)
HOVER_HIGHS = (46, 10, 10, 4, 2)

TAKEOFF_H = [(0, 0.55), (6, 0.85, 'inout'), (9, 1.0, 'inout'), (13, 0.0, 'in'), (17, 0.95, 'out'), (21, 0.0, 'in'), (24.5, 0.9, 'out'), (29, 0.0, 'in'), (30, 0.12, 'out')]


def takeoff_h(t):
    return float(kf(t, TAKEOFF_H))


@define('takeoff', 30, EVERYTHING, base=lambda t: [('idle', t, 1.0)] if t < 10 else [('hover', t - 22, 1.0)], air=lambda t: t >= 11.5, marks={'leaves ground': 10, 'downbeat': 13})
def takeoff(t):
    R = Rig()
    crouch = kf(t, [(0, 0.0), (8, 1.0, 'inout'), (9, 0.6, 'out'), (10, 0.0, 'out')]) if t < 10 else 0.0
    pitch = kf(t, [(0, 0.0), (7, -4.0, 'inout'), (9.5, 4.0, 'inout'), (13, 24.0, 'out'), (18, 30.0, 'inout'), (30, 31.0, 'inout')])
    lift = kf(t, [(0, 0.0), (8, 0.0), (10, -3.5, 'out'), (13, -3.0), (17, 0.0, 'inout'), (30, 0.0)])
    body_y = -0.5 + 5.0 * crouch + lift
    R.body(pos=(0, body_y, 0), pitch=pitch, about_pt=(0, 0, 6) if t > 9 else REAR_PT)
    R.hips(pitch=kf(t, [(0, 0.0), (8, 2.0, 'inout'), (12, -4.0, 'inout'), (20, -9.0, 'inout'), (30, -9.0)]))
    # the feet push off the ground until they leave it, then hang
    leave = smooth((t - 9.5) / 6.0)
    for k in LEG_KEYS:
        L = LEGS[k]
        fr = L.front
        ground = L.stand + (V(0, 0, 0))
        hp = hang_pt(R, k, 17.0 if fr else 19.0, fwd=(3.0 if fr else -1.0), out=1.5 if fr else 1.0)
        tgt = lerp(ground, hp, leave)
        ang = lerp(0.0, 18.0 if fr else 14.0, leave)
        R.leg(k, tgt, foot=(ang, L.sgn * -TOE_OUT * (1 - leave), 0), pole=V(0, 0, 1.0) if fr else V(0, 0, -1.0))
    # the neck coils back, is flung up with the leap, then settles into the hover's
    wa = kf(t, [(0, V(*REST_NECK) + 0.0), (7, V(32.0, 20.0, 6.0, -18.0), 'inout'), (11, V(56.0, 50.0, 30.0, 12.0), 'out'), (16, V(48.0, 34.0, 12.0, 0.0), 'inout'), (22, V(*hover_neck_at(t - 22))), (30, V(*hover_neck_at(t - 22)))])
    # (angles above are against the horizon after 9: the body's own pitch is taken off)
    if t < 9:
        a = wa
    else:
        a = wa
    segs = [(0.0, (a[i] - (pitch if t > 9 else 0.0) * smooth(clamp((t - 9) / 2.0)))) for i in range(4)]
    neck_pose(R, segs)
    R.jaw(kf(t, [(0, 1.5), (8, 3.0), (11, 22.0, 'out'), (16, 14.0, 'inout'), (22, 2.0, 'inout'), (30, 2.0)]))
    # the tail drops with the crouch, whips up at the leap and trails
    tail_grounded(R, [IDLE_DROOP + 5.0 * crouch - 12.0 * bump(t, 8, 22 + 2 * i) * (0.5 + 0.2 * i) - 8.0 * smooth(clamp((t - 14) / 8.0)) for i in range(5)],
                  [2.0 * sin_((t - 10 - 2 * i) / 18.0) for i in range(5)], 1.0 - smooth((t - 8.0) / 3.0))
    # the wings: unfold on the crouch, up for the leap, a huge downbeat, two more
    openk = kf(t, [(0, 0.0), (3, 0.05, 'inout'), (8, 1.0, 'inout'), (30, 1.0)])
    fl = wing_h(takeoff_h, t, HOVER_LOWS, HOVER_HIGHS, amp=1.0 + 0.3 * bump(t, 5, 19), sweep_lo=(-14, 0, 0, 0, 0), sweep_hi=(8, 0, 0, 0, 0), tuck=20.0)
    d = [qslerp(f, g, openk) for f, g in zip(FOLD, fl)]
    wings(R, d)
    return R


LAND_H = [(0, 0.5), (3, 0.9, 'out'), (7, 0.0, 'in'), (10, 0.95, 'out'), (13.5, 0.3, 'in'), (16, 0.6, 'out'), (18, 0.55)]


@define('land', 28, EVERYTHING, base=lambda t: [('hover', t, 1.0)] if t < 14 else [('idle', t, 1.0)], air=lambda t: t <= 12.5, marks={'touchdown': 14})
def land(t):
    R = Rig()
    flare = kf(t, [(0, 0.0), (7, 0.0), (10.5, 1.0, 'inout'), (13, 0.8, 'in'), (14, 0.0, 'in'), (28, 0.0)])
    pitch = kf(t, [(0, HOVER_PITCH), (6, 32.0, 'inout'), (10.5, 40.0, 'inout'), (13.5, 6.0, 'in'), (14, 0.0, 'in'), (16, -1.5, 'out'), (22, 0.0, 'inout'), (28, 0.0)])
    sink = kf(t, [(0, 0.0), (13, 0.0), (14.0, 2.0, 'in'), (16.5, 4.2, 'out'), (21, 1.8, 'inout'), (28, 0.5, 'inout')])
    y = kf(t, [(0, lerp(-2.8, 2.0, beat(-7 / 20.0))), (10.5, -3.0, 'inout'), (13.0, -2.0, 'in'), (14, -0.5, 'in'), (16.5, 0.0), (28, -0.5)])
    R.body(pos=(0, y + sink, 0), pitch=pitch, about_pt=(0, 0, 6) if t < 13 else REAR_PT)
    R.hips(pitch=kf(t, [(0, -9.0), (10, -9.0), (14, 0.0, 'in'), (16, 1.5, 'out'), (24, 0.0, 'inout'), (28, 0.0)]))
    # the legs reach down and forward and meet the ground together
    reach = smooth(clamp((t - 2.0) / 11.5))
    for k in LEG_KEYS:
        L = LEGS[k]
        fr = L.front
        stand = L.stand + (V(0, 0, -3.0) if fr else V(0, 0, 3.0)) * (1 - smooth(clamp((t - 14.0) / 3.0)))
        hp = hang_pt(R, k, 17.0 if fr else 19.0, fwd=(3.0 if fr else -1.0) + 5.0 * flare, out=1.5)
        tgt = lerp(hp, stand, reach) if t < 14 else stand
        ang = lerp(18.0 if fr else 14.0, 0.0, reach)
        R.leg(k, tgt, foot=(ang, L.sgn * -TOE_OUT * reach, 0), pole=V(0, 0, 1.0) if fr else V(0, 0, -1.0))
    # neck: the hover's; head up through the flare, dipping at the touchdown, then home
    world = kf(t, [(0, V(*hover_neck_at(t))), (8, V(36.0, 28.0, 10.0, 2.0), 'inout'), (12, V(46.0, 40.0, 22.0, 8.0), 'inout'), (14, V(38.0, 26.0, 6.0, -18.0), 'in'), (17, V(32.0, 18.0, 2.0, -24.0), 'out'),
                  (24, V(*REST_NECK), 'inout'), (28, V(*REST_NECK))])
    segs = [(0.0, world[i] - (pitch if t < 14 else 0.0) * (1.0 if i >= 0 else 0.0)) for i in range(4)]
    if t >= 14:
        segs = [(0.0, world[i] - pitch) for i in range(4)]
    neck_pose(R, segs)
    R.jaw(kf(t, [(0, 2.0), (12, 6.0, 'inout'), (14, 24.0, 'out'), (18, 4.0, 'inout'), (28, 1.5, 'inout')]))
    tail_grounded(R, [kf(t, [(0, -14.0), (10, -6.0, 'inout'), (14, IDLE_DROOP + 10.0, 'in'), (17, IDLE_DROOP - 4.0, 'out'), (28, IDLE_DROOP, 'inout')])] * 5,
                  [2.0 * sin_(t / 20.0 - 0.1 * i) for i in range(5)], smooth((t - 11.5) / 2.5))
    # wings: the hover's beats, braked wide on the flare, one last stroke at the touchdown, folded away
    flap_h = lambda tt: float(kf(tt, LAND_H))
    fl = wing_h(flap_h, t, HOVER_LOWS, HOVER_HIGHS, amp=1.0 + 0.2 * flare, sweep_lo=(-10, 0, 0, 0, 0), sweep_hi=(6 - 22 * flare, 0, 0, 0, 0), tuck=14.0)
    close = kf(t, [(0, 1.0), (14, 1.0), (24, 0.0, 'inout'), (28, 0.0)])
    d = [qslerp(f, g, close) for f, g in zip(FOLD, fl)]
    wings(R, d)
    return R


DIVE_FOLD = wing_euler([(0, 4, 82), (0, -2, 16), (0, 0, 12), (0, 0, 6), (0, 0, 4)])


@define('dive', 44, EVERYTHING, base=lambda t: [('fly', t, 1.0)] if t < 28 else [('idle', t, 1.0)], air=lambda t: t < 27.5, marks={'impact': 28})
def dive(t):
    R = Rig()
    tuck = kf(t, [(0, 0.0), (12, 1.0, 'inout'), (26, 1.0), (28, 0.0, 'out'), (44, 0.0)])
    flare = kf(t, [(0, 0.0), (25.5, 0.0), (28, 1.0, 'out'), (31, 0.7, 'inout'), (37, 0.0, 'inout'), (44, 0.0)])
    f = fly_pose(t)                                          # the flight it comes from
    crouch = kf(t, [(0, 0.0), (28, 0.0), (29.5, 1.0, 'out'), (34, 0.75, 'inout'), (44, 0.0, 'inout')])
    pitch = kf(t, [(0, 0.0), (12, -2.0, 'inout'), (24, -1.0), (27, 14.0, 'inout'), (28, 4.0, 'in'), (31, 0.0, 'out'), (44, 0.0)])
    y = kf(t, [(0, 0.0), (12, 0.8, 'inout'), (26, 0.8), (28, 0.0, 'in'), (44, -0.5)]) + 4.5 * crouch
    R.body(pos=(0, y, 0), pitch=pitch, about_pt=(0, 0, 6) if t < 28 else REAR_PT)
    R.hips(pitch=kf(t, [(0, 0.0), (12, 1.0, 'inout'), (27, 1.0), (28, 3.0), (34, 0.0, 'inout'), (44, 0.0)]))
    # legs: tucked, then the hind claws thrown out ahead, then the strike and the landing: the claws meet the ground well
    # ahead of the hips and the dragon steps back onto them as it settles
    for k in LEG_KEYS:
        L = LEGS[k]
        fr = L.front
        Rb, Tb = R.world('body')
        if fr:
            tuck_t = V(L.sgn * 9.0, 15.0, -13.0)
            claw_t = V(L.sgn * 9.0, 14.0, -14.0)
            land = L.stand + V(0, 0, -2.0 * flare)
        else:
            tuck_t = V(L.sgn * 8.0, 15.0, 33.0)
            claw_t = V(L.sgn * 9.5, 10.0, -12.0)         # thrown forward under the belly
            land = lerp(V(L.sgn * 10.0, GROUND - L.ankle, -2.0), L.stand, smooth((t - 31.0) / 3.5)) + V(0, -4.5 * bump(t, 31, 34.5), 0)
        e = smooth(clamp((t - 10.0) / 6.0)) * smooth(clamp(t / 10.0))
        if fr:
            b = lerp(tuck_t, claw_t, e)
        else:                                            # the hind legs swing down and through, well clear of the hips
            via = V(L.sgn * 8.5, 22.0, 10.0)
            b = (1 - e) * (1 - e) * tuck_t + 2 * (1 - e) * e * (2 * via - 0.5 * (tuck_t + claw_t)) + e * e * claw_t
        w = Tb + Rb @ b
        g = smooth(clamp((t - 25.5) / 2.5))
        R.leg(k, lerp(w, land, g), foot=(lerp(30 if fr else 20, 0.0, g), 0.0, 0.0))
    # the neck reaches ahead and down at the prey; head up as it lands
    world = kf(t, [(0, V(*[a for a in (22.0, 14.0, 5.0, -6.0)])), (12, V(14.0, 8.0, -2.0, -22.0), 'inout'), (26, V(14.0, 6.0, -6.0, -30.0)), (28, V(34.0, 26.0, 8.0, -14.0), 'out'),
                  (32, V(46.0, 38.0, 20.0, 4.0), 'inout'), (40, V(*REST_NECK), 'inout'), (44, V(*REST_NECK))])
    neck_pose(R, [(0.0, world[i]) for i in range(4)])
    R.jaw(kf(t, [(0, 0.0), (10, 20.0, 'inout'), (26, 30.0), (28, 4.0, 'in'), (32, 26.0, 'out'), (38, 2.0, 'inout'), (44, 1.5)]))
    tail_grounded(R, [kf(t, [(0, 0.0), (12, -4.0, 'inout'), (26, -6.0), (28, 8.0, 'out'), (34, IDLE_DROOP, 'inout'), (44, IDLE_DROOP)])] * 5,
                  [3.0 * sin_(t / 24.0 - 0.07 * i) * (1 - flare) for i in range(5)], smooth((t - 26.5) / 1.0))
    # wings: the flight's, folded back along the body for the dive, flung open at the impact, folded away
    fold = [qslerp(a, b, tuck) for a, b in zip(wings_of(f), DIVE_FOLD)]
    flared = wing_open(1.0, flap=34.0, sweep=-34.0, tip=-6.0)
    d = [qslerp(a, b, flare) for a, b in zip(fold, flared)]
    after = smooth(clamp((t - 31.0) / 9.0))
    d = [qslerp(a, b, after) for a, b in zip(d, FOLD)]
    wings(R, d)
    return R


def wings_of(rig):
    """The right wing's five deltas from rest, read back off a rig."""
    return [qmul(qinv(REST_Q[n]), rig.q[n]) for n in WING_R]


# ================================================================== waking, staggering, dying, raging

def _sleep_tail():
    R = Rig()
    lying(R, 1.0, br=0.0, pitch=0.0)
    return lay_tail(R, SLEEP_TAIL_YAWS)


SLEEP_TAIL_PITCH = cached('sleep_tail', _sleep_tail)
RAISED_SEGS = [(14.0, 34.0), (8.0, 26.0), (2.0, 12.0), (0.0, -8.0)]          # the head up off the ground, looking about
SLEEP_BASE = lambda t: [('sleep', t, 1.0)] if t < 22 else [('idle', t, 1.0)]


@define('wake', 40, EVERYTHING + ['lids'], base=SLEEP_BASE, marks={'eyes': 4, 'head up': 16, 'standing': 30})
def wake(t):
    R = Rig()
    up = smooth((t - 16.0) / 14.0)                           # how far it has stood up
    sf = 1.0 - smooth((t - 15.0) / 9.0)
    sh = 1.0 - smooth((t - 20.0) / 10.0)
    br = 0.45 + 0.4 * sin_(t / 14.0) * bump(t, 0, 20)
    lift = {'fr': 3.0 * bump(t, 15, 25), 'fl': 3.0 * bump(t, 17, 27), 'hr': 3.0 * bump(t, 21, 31), 'hl': 3.0 * bump(t, 23, 33)}
    s_body = 1.0 - up
    lying(R, s_body, br=0.9 * br * (1 - up), pitch=0.3 * br * (1 - up) + 3.0 * bump(t, 14, 26) - 2.0 * bump(t, 24, 34), sf=sf, sh=sh, lift=lift,
          hips_up=4.5 * s_body)
    # the shake: the head wags, the tail and wings ruffle
    shake = bump(t, 29, 40) * sin_((t - 29.0) / 3.6)
    # head: lifts off the ground, yawns, looks round, then stands up and shakes
    k1 = smooth((t - 4.0) / 12.0)
    k2 = smooth((t - 16.0) / 14.0)
    segs = []
    for i in range(4):
        a = SLEEP_SEGS[i]
        b = RAISED_SEGS[i]
        c = REST_SEGS[i]
        h = lerp(lerp(a[0], b[0], k1), c[0], k2)
        p = lerp(lerp(a[1], b[1], k1), c[1], k2)
        segs.append((h + 16.0 * shake * YAW_SPREAD[i] * 2.0, p + 1.5 * shake * (1 if i == 3 else 0.5)))
    look = sin_((t - 10.0) / 12.0) * bump(t, 10, 22) * 10.0
    segs = [(h + look * (0.5, 0.3, 0.2, 0.0)[i] * 1.5, p) for i, (h, p) in enumerate(segs)]
    neck_pose(R, segs)
    R.jaw(kf(t, [(0, 3.0), (6, 3.0, 'inout'), (10, 34.0, 'out'), (14, 34.0), (18, 2.0, 'inout'), (30, 1.5), (40, 1.5)]))
    lift_neck(R, segs)
    R.lids(1.0 - smooth(t / 4.0))
    # tail: uncurls with a flick, then wags with the shake
    tp = [lerp(SLEEP_TAIL_PITCH[i], IDLE_DROOP, smooth((t - 14.0) / 14.0)) for i in range(5)]
    ty = [lerp(SLEEP_TAIL_YAWS[i], 0.0, smooth((t - 12.0) / 14.0)) + 5.0 * shake * (0.4 + 0.15 * i) for i in range(5)]
    tail_on_ground(R, tp, ty)
    # wings: folded, ruffling with the shake
    folded(R, jig=0.8 * br * (1 - up) + 5.0 * abs(shake) * 1.0, clear=True)
    return R


SLUMP_PAWS = {'fr': V(-13.0, 21.5, -33.0), 'fl': V(13.0, 21.5, -33.0)}


def slump(R, a, pitch=-14.0, drop=3.0, extra=None, lift=None, breath=0.0):
    """The forequarters down on the ground ([a] = 1), the hind legs still standing: the body pitched down about the hind hips
    and the forepaws reaching out on the ground ahead."""
    R.body(pos=(0, -0.5 * (1 - a) + (drop + breath) * a, 0), pitch=pitch * a, about_pt=REAR_PT)
    R.hips(pitch=-pitch * 0.45 * a + (extra or 0.0))
    for k in ('hr', 'hl'):
        R.leg(k, LEGS[k].stand + (V(0, -lift[k], 0) if lift and k in lift else V(0, 0, 0)), foot=STAND_FOOT[k])
    for k in ('fr', 'fl'):
        tgt = lerp(LEGS[k].stand, SLUMP_PAWS[k], smooth(a))
        if lift and k in lift:
            tgt = tgt + V(0, -lift[k], 0)
        R.leg(k, tgt, foot=(0.0, LEGS[k].sgn * -TOE_OUT, 0.0), pole=LIE_SWING[k] * smooth(a))
    return R


def _head_down():
    R = Rig()
    slump(R, 1.0)
    R.jaw(6.0)
    return ground_neck(R, lambda y: neck_ik_pose(R, (0.0, y, -40.0), (4, -14), [(6, -6), (8, -24), (6, -34)], wp=0.01), 8.0)


STAGGER_DOWN = cached('stagger_down', _head_down)


@define('stagger', 50, EVERYTHING, base=IDLE_BASE, marks={'hit': 3, 'down': 20, 'rises': 38})
def stagger(t):
    R = Rig()
    # the hit: the forequarters rear back, then give way
    recoil = kf(t, [(0, 0.0), (3, 1.0, 'out'), (7, 0.7, 'inout'), (10, 0.0, 'in'), (50, 0.0)])
    down = kf(t, [(0, 0.0), (6, 0.0), (18, 1.0, 'in'), (20, 1.0), (38, 1.0), (47, 0.0, 'inout'), (50, 0.0)])
    pant = 0.7 * sin_((t - 20.0) / 12.0) * bump(t, 20, 42)
    twitch = bump(t, 27, 31) * 2.5
    slump(R, down, breath=pant, lift={'fl': 1.5 * twitch, 'fr': 0.0, 'hr': 0.0, 'hl': 0.0})
    # head: whips up and back with the hit, drops to the ground, lies dazed, lifts as it rises
    up_a = V(52.0, 42.0, 24.0, 10.0)
    lead = kf(t, [(0, V(*REST_NECK)), (3, up_a, 'out'), (8, V(40.0, 30.0, 14.0, -4.0), 'inout'), (11, V(38.0, 28.0, 14.0, -12.0))])
    segs = []
    for i in range(4):
        rest_ = (0.0, lead[i]) if t < 11 else (0.0, REST_NECK[i])
        a = STAGGER_DOWN[i]
        k = down
        yaw_wob = 3.0 * sin_((t - 22.0) / 14.0) * bump(t, 20, 40) * (0.5 + 0.25 * i)
        segs.append((lerp(rest_[0], a[0], k) + yaw_wob, lerp(rest_[1], a[1], k) + (2.0 * pant if i == 3 else 0.0)))
    neck_pose(R, segs)
    R.jaw(kf(t, [(0, 1.5), (3, 30.0, 'out'), (9, 16.0, 'inout'), (20, 8.0, 'inout'), (30, 6.0), (38, 5.0), (47, 1.5, 'inout'), (50, 1.5)]))
    lift_neck(R, segs)
    # tail: lashes, then lies slack on the ground
    lash = bump(t, 0, 14) * sin_(t / 6.0)
    R.tail([(IDLE_DROOP + 5.0 * down + 3.0 * recoil * (i % 2), 6.0 * lash * (0.4 + 0.2 * i) + 3.0 * down * (1 if i > 2 else 0), 0.0) for i in range(5)])
    # wings: flung wide by the hit, then crumpled and slack at its sides
    flare = wing_open(0.8, flap=26.0, sweep=4.0, tip=2.0)
    crumple = wing_open(0.32, flap=-18.0, sweep=8.0, tip=-4.0)
    wk = kf(t, [(0, 0.0), (3.5, 1.0, 'inout'), (8, 0.9, 'inout'), (16, 0.0, 'inout'), (50, 0.0)])
    mid = [qslerp(f, c, down) for f, c in zip(folded_deltas(), crumple)]
    d = [qslerp(m, fl, wk) for m, fl in zip(mid, flare)]
    wings(R, d)
    return R


def folded_deltas():
    return [qmul(FOLD[0], qeuler(y=0, z=0))] + list(FOLD[1:])


DEATH_FEET = {'fr': V(-21.0, 21.5, -25.0), 'fl': V(22.0, 21.5, -29.0), 'hr': V(-19.0, 21.5, 22.0), 'hl': V(20.0, 21.5, 29.0)}
DEATH_LIE_ROLL = 9.0


def _death_neck(kind):
    # the head lying on the ground ahead and to the left of the forepaws, or lifted weakly off it
    if kind == 'down':
        R = Rig()
        lying(R, 1.0, roll=DEATH_LIE_ROLL, feet=DEATH_FEET)
        R.jaw(6.0)
        return ground_neck(R, lambda y: neck_ik_pose(R, (12.0, y, -36.0), (-14, -12), [(18, -8), (22, -16), (14, -22)], wp=0.01), 7.5)
    return neck_ik((10.0, -4.0, -33.0), (-10, 4), [(16, 14), (16, 8), (10, 2)], wp=0.01)


DEATH_DOWN = cached('death_down', lambda: _death_neck('down'))
DEATH_LIFT = cached('death_lift', lambda: _death_neck('lift'))


def _death_wings(a):
    """The wings slack on the ground: [a] 1 = lying out to either side, drooping to the floor."""
    d = wing_euler([(0, -34 * a, 10 * a), (0, -22 * a, 6 * a), (0, -14 * a, 2 * a), (0, -8 * a, 0), (0, -6 * a, 0)])
    return d


def _wing_slump(R, a):
    """Lays the wings on the ground, as far as they droop without going through it ([a] 0 = folded, 1 = slack and spread)."""
    lo, hi = 0.0, 1.0
    def put(x):
        base = wslerp(FOLD, SPREAD, 0.55 * a)
        dr = _death_wings(x)
        R.wing('r', [qmul(b, e) for b, e in zip(base, dr)])
        R.wing('l', [qmul(b, e) for b, e in zip(base, dr)])
    put(a)
    for _ in range(16):
        m = 0.5 * (lo + hi)
        put(m * a)
        y, _w = lowest(R, WING_BONES)
        if y > GROUND:
            hi = m
        else:
            lo = m
    put(lo * a)
    return R


@define('death', 100, EVERYTHING + ['lids'], base=IDLE_BASE, blend_out=0.0, marks={'hits ground': 44, 'head lifts': 50, 'head falls': 85})
def death(t):
    R = Rig()
    q = kf(t, [(0, 0.0), (18, 0.0), (28, 0.30, 'inout'), (38, 0.72, 'inout'), (43.0, 0.9, 'in'), (44.0, 1.0, 'in'), (45.6, 0.97, 'out'), (48, 1.0, 'inout'), (100, 1.0)])
    thrash = bump(t, 0, 36) * (1.0 if t > 4 else smooth(t / 4.0))
    drop = LIE_DROP * q
    roll = 7.0 * sin_(t / 7.0) * thrash * (1 - q * 0.6) + DEATH_LIE_ROLL * smooth((t - 30.0) / 14.0)
    yaw = 9.0 * sin_(t / 11.0 + 0.2) * thrash * (1 - q) + 0.0
    breath = 0.5 * sin_((t - 50.0) / 18.0) * bump(t, 50, 92) * (1.0 - smooth((t - 70.0) / 22.0))
    R.body(pos=(0.0, drop - 1.3 * bump(t, 44.2, 47.5) - breath, 0.0), pitch=(-3.0 * smooth((t - 20.0) / 18.0) + 4.0 * thrash * sin_(t / 9.0)) * (1 - q), yaw=yaw, roll=roll)
    R.hips(pos=(0, 4.5 * q - 0.9 * bump(t, 44.2, 47.5), 0), pitch=-1.0 * q + 2.0 * thrash * sin_(t / 10.0 + 1.0), yaw=-0.6 * yaw, roll=-0.4 * roll)
    # legs: scuffing at first, buckling, then splayed out on the ground
    sf = smooth((t - 24.0) / 18.0)
    sh = smooth((t - 22.0) / 20.0)
    feet = {k: DEATH_FEET[k] for k in LEG_KEYS}
    scuff = {'fr': 3.0 * max(0.0, sin_(t / 6.0)) * thrash, 'fl': 3.0 * max(0.0, sin_(t / 6.0 + 0.5)) * thrash, 'hr': 2.5 * max(0.0, sin_(t / 7.0 + 0.3)) * thrash, 'hl': 2.5 * max(0.0, sin_(t / 7.0 + 0.8)) * thrash}
    for k in LEG_KEYS:
        kk = sf if LEGS[k].front else sh
        tgt = lerp(LEGS[k].stand + V(2.0 * thrash * sin_(t / 6.0 + (0.0 if k[1] == 'r' else 0.5)), 0, 0), feet[k], smooth(kk) if kk < 1 else 1.0) + V(0, -scuff[k], 0)
        R.leg(k, tgt, foot=(0.0, LEGS[k].sgn * -TOE_OUT, 0.0), pole=LIE_SWING[k] * smooth(kk))
    # neck: whipping with the thrash, then drooping, the head lifted weakly 50-70 and let fall by 85
    lift = kf(t, [(0, 0.0), (50, 0.0), (62, 1.0, 'inout'), (70, 1.0), (78, 0.35, 'in'), (85, 0.0, 'in'), (86.6, 0.07, 'out'), (88, 0.0, 'inout'), (100, 0.0)])
    segs = []
    for i in range(4):
        r = (0.0, REST_NECK[i])
        whip = (22.0 * sin_(t / 6.0 - 0.12 * i) * thrash * (0.5 + 0.2 * i), 12.0 * sin_(t / 5.0 - 0.1 * i + 0.5) * thrash)
        d_ = DEATH_DOWN[i]
        l_ = DEATH_LIFT[i]
        k = smooth((t - 18.0) / 28.0)
        pose = (lerp(d_[0], l_[0], lift), lerp(d_[1], l_[1], lift))
        hh = lerp(r[0] + whip[0], pose[0], k)
        pp = lerp(r[1] + whip[1], pose[1], k)
        segs.append((hh + (1.5 * sin_(t / 9.0) * lift * (i + 1) * 0.3), pp))
    neck_pose(R, segs)
    jaw = kf(t, [(0, 2.0), (5, 34.0, 'out'), (18, 30.0), (30, 14.0, 'inout'), (44, 8.0, 'inout'), (50, 6.0), (60, 18.0, 'inout'), (70, 14.0), (85, 3.0, 'in'), (100, 3.0)])
    R.jaw(jaw + 3.0 * sin_(t / 3.0) * bump(t, 4, 24))
    lift_neck(R, segs)
    R.lids(kf(t, [(0, 0.0), (78, 0.0), (92, 1.0, 'inout'), (100, 1.0)]))
    # tail: whipping, then lying slack on the ground
    tail_found = cached('death_tail', lambda: _death_tail())
    segs_t = []
    for i in range(5):
        wp = IDLE_DROOP - 3.0 * thrash * sin_(t / 6.0 - 0.1 * i)
        wy = 24.0 * sin_(t / 7.0 - 0.14 * i) * thrash * (0.4 + 0.2 * i)
        segs_t.append((lerp(wp, tail_found[0][i], smooth((t - 22.0) / 24.0)), lerp(wy, tail_found[1][i], smooth((t - 22.0) / 24.0)) , 0.0))
    R.tail(segs_t)
    # wings: flailing, then slack and spread on the ground
    flail = bump(t, 0, 34) * sin_(t / 5.0)
    k = smooth((t - 26.0) / 20.0)
    flare = 0.5 * smooth(t / 5.0) + 0.3 * thrash
    fl_r = wing_open(flare, flap=20.0 * flail + 8.0 * smooth(t / 5.0), sweep=0.0, tip=2.0)
    fl_l = wing_open(flare, flap=-20.0 * flail + 8.0 * smooth(t / 5.0), sweep=0.0, tip=2.0)
    wings(R, [qslerp(a, b, k) for a, b in zip(fl_r, DEATH_SLACK)], [qslerp(a, b, k) for a, b in zip(fl_l, DEATH_SLACK)])
    return R


def _death_tail():
    R = Rig()
    lying(R, 1.0, roll=DEATH_LIE_ROLL, feet=DEATH_FEET)
    yaws = [-4.0, 5.0, 12.0, 10.0, 6.0]
    pitches = lay_tail(R, yaws)
    return pitches, yaws


def tail_grounded(R, free, yaws, g):
    """The tail as [free] (pitches, degrees down) and [yaws] say, kept off the ground by the share [g] (1: as tail_on_ground
    does, 0: not at all, for a dragon in the air)."""
    if g <= 1e-3:
        R.tail([(free[i], yaws[i], 0.0) for i in range(5)])
        return list(free)
    p = tail_on_ground(R, free, yaws)
    out = [lerp(free[i], p[i], g) for i in range(5)]
    R.tail([(out[i], yaws[i], 0.0) for i in range(5)])
    return out


def _death_slack():
    R = Rig()
    lying(R, 1.0, roll=DEATH_LIE_ROLL, feet=DEATH_FEET)
    _wing_slump(R, 1.0)
    return wings_of(R)


DEATH_SLACK = cached('death_slack', _death_slack)


def tail_on_ground(R, free, yaws, margin=0.3, lo=-75.0):
    """The tail as [free] (each part's pitch, degrees, positive down) and [yaws] say, but with no part of it below the
    ground: each part in turn, root first, is raised as far as that takes. Needs the body and hips already set."""
    out = []
    for i, b in enumerate(TAIL):
        R.mid(b, pitch=free[i], yaw=yaws[i])
        p = free[i]
        if lowest(R, [b])[0] > GROUND - margin:
            a, c = lo, free[i]
            for _ in range(28):
                m = 0.5 * (a + c)
                R.mid(b, pitch=m, yaw=yaws[i])
                if lowest(R, [b])[0] > GROUND - margin:
                    c = m
                else:
                    a = m
            p = a
            R.mid(b, pitch=p, yaw=yaws[i])
        out.append(p)
    return out


ENRAGE_UP = V(60.0, 58.0, 48.0, 36.0)          # the neck thrown back while it roars, reared up
ENRAGE_SLAM = V(28.0, 10.0, -8.0, -34.0)       # and snapped down with the crash


@define('enrage', 64, EVERYTHING, base=IDLE_BASE, marks={'rears': 10, 'glow': 30, 'crash': 36})
def enrage(t):
    R = Rig()
    # it crouches, springs up onto its hind legs, roars and shakes with it, ignites, and falls on its forefeet
    coil = kf(t, [(0, 0.0), (6, 1.0, 'inout'), (8, 1.0), (11, 0.0, 'out'), (64, 0.0)])
    rear = kf(t, [(0, 0.0), (6, 0.0), (14, 1.0, 'out'), (32.0, 1.0), (36, 0.0, 'in'), (38.4, -0.05, 'out'), (46, 0.0, 'inout'), (64, 0.0)])
    quake = (sin_(t / 3.6) * 1.4 + 0.6 * sin_(t / 2.3)) * bump(t, 12, 35)
    arch = bump(t, 28.5, 36.0)                                  # the glow ignites: the chest arches and swells
    sink = kf(t, [(0, 0.0), (33.5, 0.0), (36, 3.4, 'in'), (38.5, 5.0, 'out'), (46, 2.4, 'inout'), (54, 0.6, 'inout'), (64, 0.0)])
    R.body(pos=(0, -0.5 + 3.5 * coil - 2.5 * rear + sink + 0.4 * quake, 0), pitch=44.0 * rear + 3.0 * arch + 0.8 * quake - 3.0 * coil,
           roll=0.45 * quake, about_pt=REAR_PT)
    R.hips(pitch=-12.0 * rear + 0.3 * sink + 2.0 * coil)
    for k in ('hr', 'hl'):
        R.leg(k, LEGS[k].stand + V(LEGS[k].sgn * 1.5 * rear, 0, 0), foot=STAND_FOOT[k])
    # forelegs: drawn up under the chest as it rises, pawing the air, then driven down onto the ground ahead
    k_down = smooth((t - 32.0) / 4.0)
    back = smooth((t - 42.0) / 14.0)                            # and shuffled back under it
    for k in ('fr', 'fl'):
        sg = LEGS[k].sgn
        flail = sin_(t / 9.0 + (0.0 if k == 'fr' else 0.5)) * bump(t, 14, 32)         # the forepaws claw at the air
        up_t = V(sg * 12.5 + sg * 1.5 * flail, 5.0 - 2.5 * flail, -24.0)
        plant = LEGS[k].stand + V(sg * 0.5, 0.0, -3.5 * (1.0 - back))
        a = smooth((t - 5.0) / 9.0)
        w_up = to_world(R, 'body', up_t)
        if t < 32.0:
            R.leg(k, lerp(LEGS[k].stand, w_up, a), foot=(40.0 * a, sg * -TOE_OUT * (1 - a), 0), pole=V(0, 0, 1.0))
        else:
            R.leg(k, lerp(w_up, plant, k_down), foot=(lerp(40.0, 0.0, k_down), sg * -TOE_OUT * k_down, 0), pole=V(0, 0, 1.0))
    # the neck: drawn in as it crouches, thrown back with the rise, jolted when the glow ignites, whipped down with the fall
    a = kf(t, [(0, V(*REST_NECK)), (6, V(30.0, 18.0, 4.0, -20.0), 'inout'), (11, V(*REST_NECK), 'out'), (15, ENRAGE_UP, 'out'), (29, ENRAGE_UP),
               (31, ENRAGE_UP + V(6.0, 8.0, 6.0, 8.0), 'out'), (33, ENRAGE_UP + V(4.0, 4.0, 2.0, 2.0)), (36, ENRAGE_SLAM, 'in'),
               (39, V(30.0, 14.0, -4.0, -30.0), 'out'), (50, V(*REST_NECK), 'inout'), (64, V(*REST_NECK))])
    segs = []
    for i in range(4):
        shake = 1.6 * sin_(t / 3.0 + 0.12 * i) * bump(t, 14, 34)
        yaw = 6.0 * sin_((t - 14.0) / 16.0) * bump(t, 14, 34) * (0.5, 0.75, 0.9, 1.0)[i]
        segs.append((yaw, a[i] + shake))
    neck_pose(R, segs)
    R.jaw(kf(t, [(0, 1.5), (6, 8.0, 'inout'), (10, 6.0), (13, 48.0, 'out'), (33, 48.0), (36, 4.0, 'in'), (40, 3.0), (50, 1.5, 'inout'), (64, 1.5)])
          + 2.5 * sin_(t / 2.7) * bump(t, 15, 33))
    # the tail props it as it rears (lying along the ground), lashes with the roar, and whips up after the crash
    lash = bump(t, 12, 56)
    free = [IDLE_DROOP + 4.0 * coil - 9.0 * bump(t, 35.5, 46) * (0.6 + 0.2 * i) for i in range(5)]
    yaws = [5.0 * sin_((t - 12.0) / 14.0 - 0.1 * i) * lash * (0.4 + 0.2 * i) for i in range(5)]
    tail_on_ground(R, free, yaws)
    # the wings: lifting as it crouches, spread wide as it rises and trembling, then beaten down and forward with the crash
    open_ = kf(t, [(0, 0.0), (6, 0.22, 'inout'), (14, 1.0, 'inout'), (37, 1.0), (50, 0.0, 'inout'), (64, 0.0)])
    flap = kf(t, [(0, 0.0), (6, 14.0, 'inout'), (14, 32.0, 'out'), (22, 38.0, 'inout'), (28, 30.0, 'inout'), (34, 40.0, 'inout'), (36, 30.0, 'in'),
                   (39, -24.0, 'out'), (43, -8.0, 'inout'), (50, 0.0, 'inout'), (64, 0.0)])
    sweep = kf(t, [(0, 0.0), (14, 8.0), (34, 10.0), (36, 8.0, 'in'), (39, -34.0, 'out'), (44, -10.0, 'inout'), (50, 0.0, 'inout'), (64, 0.0)])
    tipk = kf(t, [(0, 0.0), (14, 3.0), (36, 3.0), (39, -6.0, 'out'), (44, -3.0), (50, 0.0, 'inout'), (64, 0.0)])
    trem = 3.5 * sin_(t / 3.3) * bump(t, 14, 35)
    wings_clear(R, lambda f: (wing_open(open_, flap=f + trem, sweep=sweep, tip=tipk), wing_open(open_, flap=f - trem, sweep=sweep, tip=tipk)), flap)
    return R


# ================================================================== reports and checks

def _angle(a, b):
    """Degrees between two rotations (quaternions, however well rounded)."""
    a = np.asarray(a, float) / np.linalg.norm(a)
    b = np.asarray(b, float) / np.linalg.norm(b)
    if np.dot(a, b) < 0:
        b = -b
    return 4.0 * math.degrees(math.asin(min(1.0, float(np.linalg.norm(a - b)) / 2.0)))


def pose_points(pose):
    """The world position of every part's pivot, the lowest point of the whole model and the part it belongs to, the
    lowest point of each part, and the world transforms, for a composed pose."""
    wt = world_transforms(pose)
    piv = {b: np.asarray(wt[b][1], float) for b in IDX}
    low, who, lows = -1e9, None, {}
    for name, c in CORNERS.items():
        if not len(c):
            continue
        R, T = wt[name]
        y = float((c @ R[1] + T[1]).max())
        lows[name] = y
        if y > low:
            low, who = y, name
    return piv, low, who, lows, wt


def layers_over(name, t, base_t=0.0):
    """Clip [name] at [t] over its own base held at [base_t], both at full weight."""
    d = DEFS[name]
    under = [(b, base_t, 1.0) for (b, _, _) in d.base(base_t)] if d.base else []
    return under + [(d, t, 1.0)]


def mouth_points(layers):
    """Where the mouth is, for composed [layers]: the lower jaw's tip, the snout's, the head's pivot and the neck's."""
    wt = world_transforms(compose(layers))

    def pt(bone, local):
        R, T = wt[bone]
        return T + R @ np.asarray(local, float)
    jaw, snout = pt('jaw', (0.0, 0.0, -14.0)), pt('head', (0.0, 1.0, -16.5))
    return dict(jaw=jaw, snout=snout, mouth=0.5 * (jaw + snout), head=pt('head', (0.0, 0.0, 0.0)), neck1=pt('neck1', (0.0, 0.0, 0.0)))


def to_game(p):
    """Model px -> blocks from the entity's centre and its feet: (forward, up, left)."""
    return (-p[2] * PX, (GROUND - p[1]) * PX, p[0] * PX)


def fk_report():
    lines = []
    cases = [('idle t0', layers_over('idle', 0.0, 0.0)), ('breath t26', layers_over('breath', 26.0)), ('breath t42', layers_over('breath', 42.0)),
             ('breath t58', layers_over('breath', 58.0)), ('air_breath t30 (over fly t0)', layers_over('air_breath', 30.0)),
             ('fireball t18 (over hover t0)', layers_over('fireball', 18.0)), ('roar t20', layers_over('roar', 20.0))]
    lines.append('FK: model px (x to the dragon\'s left, y down, -z ahead) | game blocks (forward of the entity centre, up from its feet, left)')
    for label, ly in cases:
        m = mouth_points(ly)
        for key in ('jaw', 'mouth', 'snout', 'head'):
            p = m[key]
            f, u, l = to_game(p)
            lines.append(f'  {label:30s} {key:5s} px ({p[0]:7.2f}, {p[1]:7.2f}, {p[2]:7.2f})  blocks fwd {f:5.2f} up {u:5.2f} left {l:5.2f}')
    m = mouth_points(layers_over('idle', 0.0, 0.0))
    f, u, l = to_game(m['neck1'])
    lines.append(f'  neck1 pivot at idle: px ({m["neck1"][0]:.2f}, {m["neck1"][1]:.2f}, {m["neck1"][2]:.2f}), {f:.3f} blocks forward of the entity centre, {u:.2f} up')
    return lines


def clip_tracks(name):
    """What the written clip keys: ({set bones}, {add bones})."""
    out = bake_json(DEFS[name])
    sets = {b for b, tr in out['bones'].items() if tr.get('mode') != 'add'}
    adds = {b for b, tr in out['bones'].items() if tr.get('mode') == 'add'}
    return sets, adds


def keyed_report(names=None):
    """One line a clip (length, loop or one-shot, the parts it keys) and the parts every clip together keys."""
    order = {b: i for i, b in enumerate(EVERYTHING + ['lids'])}
    lines, every = [], set()
    for name, d in DEFS.items():
        if names and name not in names:
            continue
        sets, adds = clip_tracks(name)
        every |= sets | adds
        what = f'set {len(sets)}' + (f' + add {sorted(adds, key=order.get)}' if adds else '')
        if len(sets) <= len(AIR_ATTACK) + 1:
            what += f' (set: {sorted(sets, key=order.get)})'
        lines.append(f'  {name:16s} {d.length:3d} ticks, {"loop" if d.loop else "one-shot":8s} {what}')
    lines.append(f'  the parts all of them key ({len(every)}): {" ".join(sorted(every, key=order.get))}')
    return lines


def _runs(idx, step):
    """Consecutive indices grouped as (first, last) pairs."""
    out = []
    for i in idx:
        if out and i - out[-1][1] <= 1:
            out[-1][1] = i
        else:
            out.append([i, i])
    return out


def check_clip(name, hard_deg=30.0, fast_deg=22.0, floor_tol=1.0, verbose=True):
    """Looks for what would look wrong in the clip as the game would play it: a part that jumps between two samples
    (over [hard_deg] in half a tick is a pop; over [fast_deg] is only very fast), a loop that does not close, any part
    below the ground, a clip that begins or ends away from what is under it."""
    d = DEFS[name]
    problems, notes = [], []
    out = bake_json(d)
    n = int(round(d.length * d.rate)) + 1
    for b, tr in out['bones'].items():
        rot = np.array(tr['rot'], float).reshape(-1, 4)
        if len(rot) not in (1, n):
            problems.append(f'{b}: {len(rot)} rotation samples, expected {n} or 1')
        if len(rot) > 1:
            steps = np.array([_angle(rot[i], rot[i + 1]) for i in range(len(rot) - 1)])
            for lo, hi in _runs(list(np.nonzero(steps > hard_deg)[0]), 1):
                problems.append(f'{b}: turns {steps[lo:hi + 1].max():.0f} deg in half a tick at t={lo / d.rate:g}-{(hi + 1) / d.rate:g}')
            for lo, hi in _runs(list(np.nonzero((steps > fast_deg) & (steps <= hard_deg))[0]), 1):
                notes.append(f'{b}: fast, {steps[lo:hi + 1].max():.0f} deg in half a tick at t={lo / d.rate:g}-{(hi + 1) / d.rate:g}')
            if d.loop and _angle(rot[0], rot[-1]) > 0.1:
                problems.append(f'{b}: loop does not close in rotation ({_angle(rot[0], rot[-1]):.2f} deg)')
        if 'pos' in tr:
            pos = np.array(tr['pos'], float).reshape(-1, 3)
            if len(pos) > 1:
                step = np.linalg.norm(np.diff(pos, axis=0), axis=1)
                for lo, hi in _runs(list(np.nonzero(step > 5.0)[0]), 1):
                    notes.append(f'{b}: moves {step[lo:hi + 1].max():.1f} px in half a tick at t={lo / d.rate:g}-{(hi + 1) / d.rate:g}')
                if d.loop and np.linalg.norm(pos[0] - pos[-1]) > 0.01:
                    problems.append(f'{b}: loop does not close in position ({np.linalg.norm(pos[0] - pos[-1]):.3f} px)')
    # the whole model against the floor, and the ends against what is under the clip
    deepest, deepest_t = {}, {}
    air = d.air
    worst, worst_t = None, None
    for i in range(n):
        t = min(i / d.rate, d.length)
        is_air = air(t) if callable(air) else air
        if is_air:
            continue
        piv, low, who, lows, _wt = pose_points(compose(stack(d, t, 1.0)))
        if worst is None or low > worst:
            worst, worst_t = low, t
        for b, y in lows.items():
            if y > deepest.get(b, -1e9):
                deepest[b], deepest_t[b] = y, t
    for b, y in sorted(deepest.items(), key=lambda kv: -kv[1]):
        if y > GROUND + floor_tol:
            problems.append(f'{b} goes {y - GROUND:.2f} px below the ground at t={deepest_t[b]:g}')
    gaps = {}
    if not d.loop and d.base:
        for label, t in (('start', 0.0), ('end', float(d.length))):
            under = d.base(t)
            a = pose_points(compose(under))[0]
            b = pose_points(compose(under + [(d, t, 1.0)]))[0]
            g, who = max((float(np.linalg.norm(a[k] - b[k])), k) for k in IDX)
            gaps[label] = (g, who)
    if verbose:
        tag = ', '.join(f'{k} {v[0]:.1f}px ({v[1]})' for k, v in gaps.items())
        low = f'lowest {worst - GROUND:+5.2f} px at t={worst_t:g}' if worst is not None else 'in the air'
        print(f'{name:16s} len {d.length:3d} {"loop" if d.loop else "once":4s} {low}{"  | off the base at " + tag if tag else ""}')
        for p in problems:
            print('      !', p)
        for p in notes:
            print('      ~', p)
    return problems, gaps


def main(argv):
    if len(argv) < 2:
        print('usage: python3 -B dragon_anims.py <assets dir> <preview dir> [clip ...]')
        return 1
    assets, prev = argv[0], argv[1]
    names = argv[2:] or None
    for n in names or []:
        if n not in DEFS:
            print(f'no clip called {n}; the clips are: {" ".join(DEFS)}')
            return 1
    paths = write_all(assets, names)
    print(f'wrote {len(paths)} clips to {os.path.join(assets, "animations", GROUP)}')
    os.makedirs(prev, exist_ok=True)
    for name, d in DEFS.items():
        if names and name not in names:
            continue
        sheet(d, os.path.join(prev, name + '.png'), n=8, views=('side', 'three'), cols=4)
    print(f'contact sheets in {prev}')
    if not names:
        slide, ext = walk_report()
        print(f'walk: {WALK_STRIDE:g} blocks a loop ({STRIDE_PX:g} px), planted feet slide {slide:.3f} px at most, legs reach {ext * 100:.1f}% of their length at most')
        for line in fk_report():
            print(line)
    print('keyed parts:')
    for line in keyed_report(names):
        print(line)
    for name in DEFS:
        if names and name not in names:
            continue
        check_clip(name)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
