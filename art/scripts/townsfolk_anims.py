"""
The townsfolk's clips: the smith at the anvil, the baker at the oven, the merchant, the watch, the farmer, the fisher,
children at play, nobles, the tavern, the bard and the priest. Same skeleton and kit as the combat clips (animkit.py),
played by the game's CombatAnimator on the townsfolk model (art/scripts/townsfolk.py, standard humanoid pivots) with
the held props from art/scripts/townsfolk_tools.py. Run from the repo root:

    python3 -B art/scripts/townsfolk_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]

Writes assets/guildmark/animations/townsfolk/<clip>.json for every clip (blend in and out 0: the game crossfades
between activities itself) and a contact sheet per clip (or only the clips named) into <preview-dir>, drawn with the
townsfolk model and the look that does the work, the clip's tools, and the station (anvil, forge, oven, cauldron,
table, crop, water, seat) as boxes. Contacts are solved and checked with the same forward kinematics as the game and
printed on every run: the hammer face on the hot iron on its tick, the peel in the oven mouth, the hoe in the ground,
the mug at the lips, the hips on the seat.

How CombatAnimator plays a clip (and how these previews pose it): the torso bends about the hips and carries the
arms and the head's pivot; an arm track is relative to the torso ('set') or added to vanilla's swing ('add'); the
head adds to where the mob looks unless its mode is 'set' (then it is relative to the torso); a leg with no track
keeps vanilla's walk; the root moves and turns everything about the hips. Overlay clips (idle, patrol, play, stroll,
carry, carry_sack, flee, stagger, lantern) leave the legs and root alone so the walk shows.
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw
from scipy.optimize import least_squares

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from animkit import (Clip, qeuler, qmul, qaxis, qmat, qslerp, qfrommat, frame_to, unit, I, v, F, B, U, D, R, L,
                     pose_clip, vanilla_pose, raster, ortho, box_quads, T, Rq, apply, EASE)
from modelkit import Cube, Part, Model, fill
import townsfolk_tools as tt

GROUP = 'townsfolk'

# ================================================================== skeleton and stations

PIVOTS = {'head': (0, 0, 0), 'body': (0, 0, 0), 'right_arm': (-5, 2, 0), 'left_arm': (5, 2, 0),
          'right_leg': (-1.9, 12, 0), 'left_leg': (1.9, 12, 0)}
HIPS = np.array([0.0, 12.0, 0.0])
GRIP = {'right_arm': np.array(tt.RIGHT_GRIP), 'left_arm': np.array(tt.LEFT_GRIP)}
EYES = np.array([0.0, -4.0, -3.0])     # between the eyes, in head space
MOUTH = np.array([0.0, -1.5, -4.3])    # the lips, in head space

# The station block is centred at (0, 16, -16): top face y = 8, front face z = -8 (the spec's geometry)
OVEN_MOUTH = np.array([0.0, 16.0, -8.0])
CAULDRON_WATER = np.array([0.0, 10.0, -16.0])
CROP = np.array([-2.0, 24.0, -13.0])
COUNTER_TOP = 8.0
WATER_Y = 40.0          # fishing: the water surface a block below the bank

# ================================================================== small maths

def qinv(q):
    x, y, z, w = q
    return np.array([-x, -y, -z, w])


def rvec_q(r):
    a = float(np.linalg.norm(r))
    if a < 1e-12:
        return I.copy()
    return qaxis(np.asarray(r) / a, math.degrees(a))


def q_rvec(q):
    q = np.asarray(q, float)
    if q[3] < 0:
        q = -q
    s = math.sqrt(max(0.0, 1 - q[3] * q[3]))
    ang = 2 * math.acos(min(1.0, q[3]))
    if s < 1e-9:
        return np.zeros(3)
    return q[:3] / s * ang


def rot_part(rot):
    """A modelkit part's rotation (degrees, ZYX as the game applies it) as a quaternion."""
    return qeuler(*rot)


def look_q(direction, up=U):
    """A head rotation facing [direction] (model space), kept as upright as it goes."""
    return frame_to(np.array([0.0, 0.0, -1.0]), np.array([0.0, -1.0, 0.0]), unit(direction), up)


def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def bump(t, a, b, length=None):
    """0 outside [a, b], rising smoothly to 1 in the middle (a sin^2 hump); wraps round [length] for loops."""
    if length is not None and t < a:
        t += length
    if t <= a or t >= b:
        return 0.0
    return math.sin(math.pi * (t - a) / (b - a)) ** 2

# ================================================================== the model

_TOOLS = None


def tools():
    """Tool parts by name (fresh copies), their parents, and the part tree under each."""
    global _TOOLS
    if _TOOLS is None:
        by_name, parent = {}, {}
        for par, parts in tt.tool_parts().items():
            for p in parts:
                for q in p.walk():
                    by_name[q.name] = q
                parent[p.name] = par
        _TOOLS = by_name, parent
    return _TOOLS


def tool_local(name):
    """The 4x4 transform of a tool part (or a child of one) in its arm's or body's space, at rest."""
    by_name, parent = tools()
    chain = []
    n = name
    while n not in parent:
        owner = next(p for p in by_name.values() if any(c.name == n for c in p.children))
        chain.append(by_name[n])
        n = owner.name
    chain.append(by_name[n])
    M = np.eye(4)
    for p in reversed(chain):
        M = M @ T(*p.pivot) @ Rq(rot_part(p.rot))
    return M


def tool_point(name, p):
    """A point of a tool (its own part space) in its arm's or body's space."""
    return apply(tool_local(name), p)


def tool_dir(name, d):
    return qmat(qfrommat(tool_local(name)[:3, :3])) @ np.asarray(d, float)


def tool_parent(name):
    by_name, parent = tools()
    while name not in parent:
        name = next(p.name for p in by_name.values() if any(c.name == name for c in p.children))
    return parent[name]


# Colours the previews paint a look with until art/scripts/townsfolk.py can paint it: shirt, legs, and the look
# pieces shown (so hats, aprons, robes and capes are there to judge the clips against)
STAND_IN = {
    'citizen': ((150, 118, 84), (96, 82, 64), {}),
    'smith': ((128, 104, 86), (74, 64, 56), {'apron': (104, 66, 40), 'roll_r': (128, 104, 86), 'roll_l': (128, 104, 86),
                                              'cap': (90, 60, 40), 'boot_r': (60, 44, 34), 'boot_l': (60, 44, 34)}),
    'baker': ((232, 228, 216), (150, 128, 104), {'apron': (246, 244, 236), 'baker_cap': (250, 250, 246)}),
    'merchant': ((40, 128, 128), (112, 60, 40), {'wide_hat': (120, 80, 50), 'pouch': (110, 70, 40), 'sash_tails': (180, 40, 50)}),
    'guard': ((70, 74, 86), (60, 62, 72), {'kettle_helm': (170, 176, 188), 'tabard': (40, 60, 140),
                                           'tabard_back': (40, 60, 140), 'boot_r': (50, 40, 34), 'boot_l': (50, 40, 34)}),
    'farmer': ((206, 196, 166), (112, 92, 62), {'straw_hat': (222, 190, 100), 'boot_r': (90, 70, 50), 'boot_l': (90, 70, 50)}),
    'fisher': ((178, 146, 62), (92, 92, 62), {'souwester': (190, 156, 60), 'creel': (180, 136, 74),
                                              'boot_r': (60, 70, 60), 'boot_l': (60, 70, 60)}),
    'child': ((206, 94, 70), (72, 92, 142), {}),
    'noble': ((128, 30, 52), (64, 32, 44), {'plumed_hat': (90, 20, 36), 'cape': (100, 24, 44), 'collar': (246, 240, 230)}),
    'innkeeper': ((176, 152, 120), (92, 72, 52), {'apron': (226, 216, 196), 'shoulder_rag': (236, 232, 220),
                                                  'roll_r': (176, 152, 120), 'roll_l': (176, 152, 120), 'beard': (110, 70, 40)}),
    'bard': ((40, 140, 140), (112, 52, 112), {'bard_cap': (112, 52, 112)}),
    'priest': ((238, 234, 222), (238, 234, 222), {'skirt_r': (238, 234, 222), 'skirt_l': (238, 234, 222),
                                                  'skullcap': (246, 244, 236)}),
}
SKIN_TONE = (214, 170, 136)


class Look:
    """The model and texture of one look: the townsfolk model and its painted texture when art/scripts/townsfolk.py
    has them; until then its geometry painted in flat colours here (tools with their own painters); and if even the
    geometry is not importable, a stand-in humanoid. The clips can be judged at every stage."""

    def __init__(self, role='citizen', n=0):
        self.role, self.n = role, n
        self.real = self.real_geometry = False
        self.pieces, self.optional = set(), set()
        tf = None
        try:
            import townsfolk as tf
            self.model = tf.build_model()
            self.rects = tf.rects2x
            self.optional = set(getattr(tf, 'PIECES', []))
            self.real_geometry = True
        except Exception as e:
            print(f'(townsfolk model not importable: {e!r}; drawing a stand-in)')
        if self.real_geometry and hasattr(tf, 'look_texture') and hasattr(tf, 'LOOKS'):
            try:
                self.tex = tf.look_texture(role, n).convert('RGBA')
                self.pieces = set(self._look_parts(tf.LOOKS, role, n))
                self.real = True
            except Exception as e:
                print(f'(townsfolk look {role} {n} not usable yet: {e!r}; painting it flat)')
        if not self.real:
            if self.real_geometry:
                self._flat_paint()
            else:
                self._stand_in()
        self.scale = self.tex.width // self.model.tex_w
        self.arr = np.array(self.tex.convert('RGBA'), float)
        self.parts = {p.name: p for p in self.model.all_parts()}
        self.parent = {}
        for p in self.model.all_parts():
            for c in p.children:
                self.parent[c.name] = p.name

    @staticmethod
    def _look_parts(looks, role, n):
        look = looks[role][n] if isinstance(looks, dict) else looks[(role, n)]
        for attr in ('parts', 'pieces'):
            if hasattr(look, attr):
                return getattr(look, attr)
            if isinstance(look, dict) and attr in look:
                return look[attr]
        if isinstance(look, (tuple, list)):
            return next((x for x in look if isinstance(x, (set, frozenset, list, tuple))), ())
        return ()

    def _paint_cubes(self, colour_of):
        m = self.model
        img = Image.new('RGBA', (m.tex_w * 2, m.tex_h * 2), (0, 0, 0, 0))
        for c in m.all_cubes():
            rects = self.rects(c)
            for face, (x, y, w, h) in rects.items():
                if w <= 0 or h <= 0:
                    continue
                if c.paint:
                    c.paint(img, face, x, y, w, h)
                    continue
                col = colour_of(c.name)
                if col is None:
                    continue
                k = {'top': 1.06, 'bottom': 0.8, 'front': 1.0, 'back': 0.9, 'right': 0.95, 'left': 0.95}[face]
                img.paste(tuple(int(min(255, ch * k)) for ch in col) + (255,), (x, y, x + w, y + h))
        px = img.load()
        hc = next(c for c in m.all_cubes() if c.name == 'head')
        x, y, w, h = self.rects(hc)['front']
        for ex in (x + w * 2 // 8, x + w * 5 // 8):        # eyes and a mouth, so the head reads which way it looks
            for dx in range(max(1, w // 8)):
                for dy in range(max(1, h // 8)):
                    px[ex + dx, y + h // 2 + dy] = (40, 40, 60, 255)
        for dx in range(w * 3 // 8, w * 5 // 8):
            px[x + dx, y + h * 6 // 8] = (140, 70, 60, 255)
        bc = next(c for c in m.all_cubes() if c.name == 'body')
        bx, by, bw, bh = self.rects(bc)['front']
        for dx in range(bw):                                # a belt, so the waist reads
            for dy in range(max(1, bh // 12)):
                px[bx + dx, by + bh * 10 // 12 + dy] = (70, 46, 30, 255)
        self.tex = img

    def _flat_paint(self):
        shirt, legs, pieces = STAND_IN.get(self.role, STAND_IN['citizen'])
        self.pieces = set(pieces)
        owner = {}
        for p in self.model.all_parts():
            for q in p.walk():
                for c in q.cubes:
                    owner[c.name] = q.name
        piece_of = {}
        for p in self.model.all_parts():
            if p.name in self.optional:
                for q in p.walk():
                    for c in q.cubes:
                        piece_of[c.name] = p.name

        def colour_of(name):
            if name == 'hat':
                return None
            if name in piece_of:
                return pieces.get(piece_of[name], tuple(int(ch * 0.8) for ch in shirt))
            if name == 'head':
                return SKIN_TONE
            if name in ('right_leg', 'left_leg'):
                return legs
            return shirt
        self._paint_cubes(colour_of)

    def _stand_in(self):
        tparts = tt.tool_parts()
        def C(o, s, n):
            return Cube(o, s, None, name=n)
        def kids(name): return list(tparts.get(name, []))
        head = Part('head', cubes=[C((-4, -8, -4), (8, 8, 8), 'head')], children=kids('head'))
        body = Part('body', cubes=[C((-4, 0, -2), (8, 12, 4), 'body')], children=kids('body'))
        ra = Part('right_arm', (-5, 2, 0), cubes=[C((-3, -2, -2), (4, 12, 4), 'right_arm')], children=kids('right_arm'))
        la = Part('left_arm', (5, 2, 0), cubes=[C((-1, -2, -2), (4, 12, 4), 'left_arm')], children=kids('left_arm'))
        rl = Part('right_leg', (-1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'right_leg')])
        ll = Part('left_leg', (1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'left_leg')])
        self.model = Model('townsfolk_stand_in', 256, 128, [head, body, ra, la, rl, ll])
        self.model.pack()
        self.rects = stand_in_rects
        shirt, legs, _ = STAND_IN.get(self.role, STAND_IN['citizen'])
        self._paint_cubes(lambda n: SKIN_TONE if n == 'head' else legs if n.endswith('_leg') else shirt)

    def visible(self, name, shown_tools):
        """Whether a part draws: tools only when the clip shows them, look pieces only when the look has them."""
        by_name, tparent = tools()
        n = name
        while True:
            if n in tparent and n not in shown_tools:
                return False
            if n in by_name and n not in tparent and n in ('hot_iron', 'line') and n not in shown_tools:
                return False
            if n in self.optional and n not in self.pieces:
                return False
            if n not in self.parent:
                return True
            n = self.parent[n]


def stand_in_rects(c, s=2):
    u, v_ = c.uv
    w, h, d = c.size
    Rr = lambda a: int(round(a * s))
    return {'top': (Rr(u + d), Rr(v_), Rr(w), Rr(d)), 'bottom': (Rr(u + d + w), Rr(v_), Rr(w), Rr(d)),
            'right': (Rr(u), Rr(v_ + d), Rr(d), Rr(h)), 'front': (Rr(u + d), Rr(v_ + d), Rr(w), Rr(h)),
            'left': (Rr(u + d + w), Rr(v_ + d), Rr(d), Rr(h)), 'back': (Rr(u + 2 * d + w), Rr(v_ + d), Rr(w), Rr(h))}

# ================================================================== posing (as CombatAnimator, plus the parts below)

def walk_base(t, speed=0.0, period=16.0, age=0.0):
    """Vanilla's pose for a mob walking at [speed] (0: standing), the stride [period] ticks long: legs and arms swing,
    the arms bob a little. Rotations and pivots, as HumanoidModel.setupAnim leaves them."""
    pose = vanilla_pose(item_pose=False, pivots=PIVOTS)
    ph = 2 * math.pi * t / period
    if speed:
        pose['right_leg'] = (qeuler(x=math.degrees(math.cos(ph) * 1.4 * speed)), pose['right_leg'][1])
        pose['left_leg'] = (qeuler(x=math.degrees(math.cos(ph + math.pi) * 1.4 * speed)), pose['left_leg'][1])
        ra = math.cos(ph + math.pi) * speed
        la = math.cos(ph) * speed
    else:
        ra = la = 0.0
    bob_z = math.cos(age * 0.09) * 0.05 + 0.05
    bob_x = math.sin(age * 0.067) * 0.05
    pose['right_arm'] = (qeuler(x=math.degrees(ra + bob_x), z=math.degrees(bob_z)), pose['right_arm'][1])
    pose['left_arm'] = (qeuler(x=math.degrees(la - bob_x), z=-math.degrees(bob_z)), pose['left_arm'][1])
    return pose


def pose_at(clip, t, base=None):
    """The six humanoid parts and the root at [t] (rotation, pivot), exactly as CombatAnimator places them."""
    return pose_clip(clip, t, base=base or walk_base(t), pivots=PIVOTS, hips=HIPS)


def part_matrices(look, pose, hang=True):
    """Every part's 4x4 transform in model space. [hang]: the line hangs straight down from the rod's tip and the
    lantern upright from the hand, as the game turns them each frame."""
    qr, pr = pose['root']
    Mroot = T(*pr) @ Rq(qr)
    out = {}
    def go(part, M):
        out[part.name] = M
        for c in part.children:
            Mc = M @ T(*c.pivot) @ Rq(rot_part(c.rot))
            if hang and c.name in ('line', 'lantern'):
                Mc = Mc.copy()
                Mc[:3, :3] = np.eye(3)
            go(c, Mc)
    for p in look.model.parts:
        q, piv = pose[p.name]
        go(p, Mroot @ T(*piv) @ Rq(q))
    return out


def world_point(look, pose, part, p):
    return apply(part_matrices(look, pose)[part], p)

# ================================================================== stations and props drawn in the previews

def solid_tex(col):
    a = np.zeros((4, 4, 4), float)
    a[..., :3] = col
    a[..., 3] = 255
    return a


def box(lo, hi, col, light=True):
    """A plain box (model space corners) as quads for the rasterizer."""
    lo, hi = np.asarray(lo, float), np.asarray(hi, float)
    tex = solid_tex(col)
    quads = []
    for A, Bq, Dq, rect, face in box_quads(tuple(lo), tuple(hi - lo), (0, 0)):
        k = {'top': 1.0, 'front': 0.86, 'back': 0.6, 'right': 0.74, 'left': 0.8, 'bottom': 0.5}[face] if light else 1.0
        quads.append((A, Bq, Dq, (0, 0, 1, 1), k, tex))
    return quads


GROUND = (86, 104, 70)


def station_boxes(kind):
    """The station block in front (centred (0, 16, -16)) as boxes (lo, hi, colour, lit), and the ground's colour and
    height."""
    b = []
    ground, gy = GROUND, 24.0
    if kind == 'anvil':
        # an anvil set across the smith's front (its long top along x): top y 8..14, z -11..-21
        b += [((-8, 8, -21), (8, 14, -11), (92, 92, 100), True), ((-4, 14, -19), (4, 19, -13), (70, 70, 76), True),
              ((-5, 19, -20), (5, 20, -12), (64, 64, 70), True), ((-6, 20, -22), (6, 24, -10), (58, 58, 64), True)]
    elif kind == 'forge':
        b += [((-8, 8, -24), (8, 24, -8), (96, 90, 86), True), ((-4, 13, -8.4), (4, 19, -8.0), (255, 140, 40), False)]
    elif kind == 'oven':
        b += [((-8, 8, -24), (8, 24, -8), (120, 104, 92), True), ((-4, 13, -8.4), (4, 19, -8.0), (40, 26, 20), False)]
    elif kind == 'cauldron':
        b += [((-8, 22, -24), (8, 24, -8), (50, 50, 56), True),
              ((-8, 8, -24), (-6, 22, -8), (64, 64, 72), True), ((6, 8, -24), (8, 22, -8), (64, 64, 72), True),
              ((-6, 8, -24), (6, 22, -22), (64, 64, 72), True), ((-6, 8, -10), (6, 22, -8), (64, 64, 72), True),
              ((-6, 10, -22), (6, 10.4, -10), (60, 110, 200), False)]
    elif kind in ('table', 'bar'):
        b += [((-8, 8, -24), (8, 9.5, -8), (150, 110, 70), True)]
        for x0, z0 in ((-7, -23), (5, -23), (-7, -11), (5, -11)):
            b += [((x0, 9.5, z0), (x0 + 2, 24, z0 + 2), (110, 80, 50), True)]
        if kind == 'table':
            b += [((-3, 7, -13), (3, 8, -9), (236, 220, 180), True)]          # dough
    elif kind == 'counter':
        b += [((-8, 8, -24), (8, 24, -8), (140, 100, 62), True),
              ((-6, 5, -14), (-2, 8, -10), (196, 50, 40), True), ((1, 6, -13), (5, 8, -10), (220, 190, 90), True)]
    elif kind == 'crop':
        ground = (104, 76, 50)
        for x, z in ((-5, -12), (-2, -14), (1, -11.5), (-3, -10.5), (2, -15)):
            b += [((x - 0.5, 15, z - 0.5), (x + 0.5, 24, z + 0.5), (96, 150, 60), True),
                  ((x - 1.5, 15, z - 0.4), (x + 1.5, 16, z + 0.4), (210, 180, 70), True)]
    elif kind == 'field':
        ground = (104, 76, 50)
    elif kind == 'water':
        # the bank ends half a block ahead; the water a block below, from there on
        b += [((-24, 24, -8), (24, 40, 24), (110, 90, 60), True), ((-24, 40, -72), (24, 40.3, -8), (60, 110, 200), False)]
        ground, gy = None, None
    elif kind == 'seat':
        # a stair the mob sits on, set 2 px behind it: the step (its top y = 24) and the riser behind
        b += [((-8, 24, -6), (8, 32, 10), (150, 112, 70), True), ((-8, 16, 2), (8, 24, 10), (132, 98, 62), True)]
        gy = 32.0
    if ground is not None:
        b.append(((-24, gy, -24), (24, gy + 0.2, 24), ground, True))
    return b


def station(kind):
    q = []
    for lo, hi, col, light in station_boxes(kind):
        q += box(lo, hi, col, light)
    return q


def station_wire(draw, kind, project):
    """The station's boxes as outlines (the ground solid is drawn already), so a view from the front sees through."""
    for lo, hi, col, light in station_boxes(kind):
        if hi[1] - lo[1] < 0.5 and hi[0] - lo[0] > 30:
            continue
        xs, ys, zs = (lo[0], hi[0]), (lo[1], hi[1]), (lo[2], hi[2])
        P = {(i, j, k): tuple(project((xs[i], ys[j], zs[k]))[:2]) for i in (0, 1) for j in (0, 1) for k in (0, 1)}
        colour = tuple(min(255, int(ch * 1.4) + 30) for ch in col)
        for a in P:
            for axis in range(3):
                if a[axis] == 0:
                    b_ = list(a); b_[axis] = 1
                    draw.line([P[a], P[tuple(b_)]], fill=colour, width=1)


def model_quads(look, mats, shown_tools):
    quads = []
    for name, part in look.parts.items():
        if not look.visible(name, shown_tools):
            continue
        M = mats[name]
        for c in part.cubes:
            rects = look.rects(c)
            for A, Bq, Dq, _, face in box_quads(c.origin, c.size, (0, 0), c.inflate):
                u, v_, w, h = rects[face]
                if w <= 0 or h <= 0:
                    continue
                if c.mirror and face in ('front', 'back', 'top', 'bottom'):
                    A, Bq = Bq, A
                k = {'top': 1.0, 'front': 0.92, 'back': 0.62, 'right': 0.78, 'left': 0.82, 'bottom': 0.5}[face]
                if c.name in tt.GLOW_CUBES:
                    k = 1.15
                quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), (u, v_, w, h), k, look.arr))
    return quads

# ================================================================== frames and inverse kinematics

def F_(qt=I, vt=(0, 0, 0), pr=(0, 0, 0), qr=I):
    """A frame: the torso's rotation and offset and the root's rotation and offset that a key sits under."""
    return {'qt': np.asarray(qt, float), 'vt': np.asarray(vt, float), 'pr': np.asarray(pr, float), 'qr': np.asarray(qr, float)}


def bend(pitch=0.0, yaw=0.0, roll=0.0):
    """A torso rotation: bent forward [pitch] degrees at the hips (negative leans back), turned [yaw] degrees (positive
    brings the right shoulder back) and tipped [roll] degrees (positive drops the right shoulder)."""
    return qmul(qeuler(y=yaw, z=roll), qeuler(x=pitch))


def frame_at(c, t):
    """The frame the clip's torso and root tracks give at [t]."""
    qt, vt = c.sample('torso', t)
    qr, pr = c.sample('root', t)
    return F_(qt, vt, pr, qr)


def chain_frame(side, qr=I, pr=np.zeros(3), qt=I, vt=np.zeros(3), arm_pos=np.zeros(3)):
    """The rotation an arm's own 'set' key is multiplied by (root, then torso) and where its joint sits, as
    CombatAnimator places it."""
    Rp = qmat(qmul(qr, qt))
    piv = np.array(PIVOTS[side], float) + arm_pos
    joint_root = HIPS + qmat(qt) @ (piv - HIPS) + vt
    joint = HIPS + qmat(qr) @ (joint_root - HIPS) + pr
    return Rp, joint


def arm_world(side, q_arm, **frame):
    """World rotation matrix and joint of an arm keyed [q_arm] (set mode) under [frame]."""
    Rp, joint = chain_frame(side, **frame)
    return Rp @ qmat(q_arm), joint


def hand_point(side, q_arm, fr, p=None):
    """Where an arm-space point (default the grip) lands in the world for a 'set' arm key under [fr]."""
    Ra, j = arm_world(side, q_arm, **fr)
    return j + Ra @ (GRIP[side] if p is None else np.asarray(p, float))


def solve_arm(side, frame, points=(), dirs=(), guess=None, rest_w=0.0):
    """The arm's 'set' key (relative to the torso) that brings arm-space [points] to world targets and turns
    arm-space [dirs] towards world directions: points [(p_local, target, weight)], dirs [(d_local, d_world, weight)].
    Least squares from a few starts; returns (q, residual cost)."""
    Rp, joint = chain_frame(side, **frame)
    def res(r):
        Ra = Rp @ qmat(rvec_q(r))
        out = []
        for p, tgt, w in points:
            out.extend((joint + Ra @ np.asarray(p, float) - np.asarray(tgt, float)) * w)
        for d, dw, w in dirs:
            out.extend((Ra @ unit(d) - unit(dw)) * w)
        if rest_w:
            out.extend(np.asarray(r) * rest_w)
        return np.array(out)
    starts = [np.zeros(3), np.array([-1.2, 0, 0]), np.array([1.2, 0, 0]), np.array([-2.2, 0, 0]),
              np.array([-0.8, 0.6, 0.4]), np.array([-0.8, -0.6, -0.4]), np.array([0, 0, 1.0]), np.array([0, 0, -1.0])]
    if guess is not None:
        starts = [q_rvec(guess)]
    best = None
    for s0 in starts:
        s = least_squares(res, s0)
        if best is None or s.cost < best.cost - 1e-9:
            best = s
    return rvec_q(best.x), best.cost


def free(frame, direction, palm=None):
    """A free arm pointing along world [direction], palm towards world [palm]: the 'set' key under [frame]."""
    qw = frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), unit(direction), F if palm is None else unit(palm))
    return qmul(qinv(qmul(frame.get('qr', I), frame.get('qt', I))), qw)


def rel(frame, q_world):
    """A world rotation as a 'set' key under [frame]."""
    return qmul(qinv(qmul(frame.get('qr', I), frame.get('qt', I))), q_world)


def wield(tool, frame, direction, tool_direction):
    """An arm pointing along world [direction] with [tool]'s own +y axis turned as near world [tool_direction] as
    the grip allows: the 'set' key under [frame]."""
    td = tool_dir(tool, (0, 1, 0))
    qw = frame_to(np.array([0.0, 1.0, 0.0]), td, unit(direction), unit(tool_direction))
    return rel(frame, qw)


def reach(side, frame, target, palm=None, palm_w=0.15, guess=None, p=None):
    """A free arm whose grip (or arm-space point [p]) lands as near world [target] as a straight arm can."""
    g = GRIP[side] if p is None else np.asarray(p, float)
    Rp, joint = chain_frame(side, **frame)
    d = unit(np.asarray(target, float) - joint)
    q0 = free(frame, d, palm) if guess is None else guess
    pts = [(g, target, 1.0)]
    dirs = [] if palm is None else [((0, 0, -1), palm, palm_w)]
    q, cost = solve_arm(side, frame, pts, dirs, guess=q0)
    return q


def head_at(frame, target, mode='set', base=I, extra=I):
    """A head key looking at world [target]: 'set' (relative to the torso, as CombatAnimator plays a set head) or
    'add' (on top of the look [base] vanilla gives it). [extra] tilts it further, in its own frame."""
    qr, qt = frame.get('qr', I), frame.get('qt', I)
    vt, pr = frame.get('vt', np.zeros(3)), frame.get('pr', np.zeros(3))
    pivot = HIPS + qmat(qr) @ (HIPS + qmat(qt) @ (np.array(PIVOTS['head'], float) - HIPS) + vt - HIPS) + pr
    qw = qmul(qr, qt)
    for _ in range(3):
        eyes = pivot + qmat(qw) @ EYES
        qw = qmul(look_q(np.asarray(target, float) - eyes), extra)
    if mode == 'set':
        return qmul(qinv(qmul(qr, qt)), qw)
    return qmul(qinv(qmul(qr, base)), qw)


def head_point(frame, q_head, p, mode='set'):
    """Where a head-space point (the lips, the eyes) lands for a 'set' head key under [frame]."""
    qr, qt = frame.get('qr', I), frame.get('qt', I)
    vt, pr = frame.get('vt', np.zeros(3)), frame.get('pr', np.zeros(3))
    pivot = HIPS + qmat(qr) @ (HIPS + qmat(qt) @ (np.array(PIVOTS['head'], float) - HIPS) + vt - HIPS) + pr
    return pivot + qmat(qmul(qmul(qr, qt), q_head)) @ np.asarray(p, float)

# ================================================================== clip helpers

clips = []
INFO = {}       # clip name -> preview info: look, tools, station, traces, overlay


def clip(name, length, loop, look=('citizen', 0), tools_=(), station_=None, overlay=False, traces=(), events=(),
         walking=None, views=None):
    c = Clip(name, length, blend_in=0.0, blend_out=0.0)
    for label, tick in events:
        c.mark(label, tick)
    INFO[name] = dict(look=look, tools=set(tools_), station=station_, overlay=overlay, traces=list(traces), loop=loop,
                      events=list(events), walking=overlay if walking is None else walking, views=views, checks=[])
    clips.append(c)
    return c


def key_frame(c, t, fr, ease='inout', root=True, torso=True):
    """Keys the torso (rotation and offset) and the root (rotation and offset) from a frame."""
    if torso:
        c.key('torso', t, fr['qt'], pos=fr['vt'], ease=ease)
    if root:
        c.key('root', t, fr['qr'], pos=fr['pr'], ease=ease)


def legs(c, t, rx=0.0, lx=0.0, rz=0.0, lz=0.0, ry=0.0, ly=0.0, ease='inout', rpos=None, lpos=None):
    c.key('right_leg', t, qeuler(x=rx, y=ry, z=rz), pos=rpos, ease=ease)
    c.key('left_leg', t, qeuler(x=lx, y=ly, z=lz), pos=lpos, ease=ease)


def stand_legs(c, t, root=(0, 0, 0), right=(0.0, 0.0), left=(0.0, 0.0), ease='inout'):
    """Legs that keep each foot planted (feet at [right] and [left]: x offset outward from under the hip, z) however
    the root has moved the hips: crouches, sways and leans without the feet sliding."""
    out = []
    root = np.asarray(root, float)
    for side, s, (ox, oz) in (('right_leg', -1, right), ('left_leg', 1, left)):
        hip = np.array(PIVOTS[side], float) + root
        foot = np.array([PIVOTS[side][0] + s * ox, 24.0, oz])
        d = foot - hip
        n = float(np.linalg.norm(d))
        # qeuler(x, 0, z) sends the leg's +y to (-cos(ax) sin(az), cos(ax) cos(az), sin(ax))
        ax = math.degrees(math.asin(max(-1.0, min(1.0, d[2] / n))))
        az = math.degrees(math.atan2(-d[0], d[1]))
        out.append(qeuler(x=ax, z=az))
        if n < 11.0:
            print(f'   ({c.name} t={t}: {side} would need to shorten to {n:.1f})')
    c.key('right_leg', t, out[0], ease=ease)
    c.key('left_leg', t, out[1], ease=ease)


def track(c, bone, times, fn, ease='linear'):
    """Keys [bone] at every one of [times] with fn(t, frame) (the frame the clip's torso and root give there):
    for hands that must stay on a thing however the body moves. Replaces the bone's keys."""
    c.keys[bone] = []
    for t in times:
        c.key(bone, t, fn(t, frame_at(c, t)), ease=ease)


def ticks(a, b, step=1.0):
    n = int(round((b - a) / step))
    return [a + i * step for i in range(n + 1)]


def seal(c):
    """A loop's last keys made exactly its first (after checking they were already close)."""
    for bone, keys in c.keys.items():
        t0, q0, p0, _ = keys[0]
        t1, q1, p1, e1 = keys[-1]
        if t0 != 0 or abs(t1 - c.length) > 1e-6:
            raise SystemExit(f'{c.name}: {bone} keys span {t0}..{t1}, not the whole loop')
        dq = math.degrees(2 * math.acos(min(1.0, abs(float(np.dot(q0, q1))))))
        dp = 0.0 if p0 is None and p1 is None else float(np.linalg.norm((p0 if p0 is not None else 0) - (p1 if p1 is not None else 0)))
        if dq > 2.0 or dp > 0.2:
            print(f'!! {c.name}: {bone} loop seam off by {dq:.1f} deg / {dp:.2f} px')
        keys[-1] = (t1, q0.copy(), None if p0 is None else p0.copy(), e1)


def check(c, label, got, want, tol=0.35):
    """Records a contact to verify: printed with the clip, and flagged when off by more than [tol] px."""
    INFO[c.name]['checks'].append((label, np.asarray(got, float), np.asarray(want, float), tol))


def fit(fn, x0, starts=()):
    """Least squares on the residual vector fn(x), from x0 and any other [starts]; the best x."""
    best = None
    for s0 in [x0, *starts]:
        r = least_squares(lambda x: np.asarray(fn(x), float), np.asarray(s0, float))
        if best is None or r.cost < best.cost:
            best = r
    return best.x


def arm_dir(side, q, fr, d):
    """An arm-space direction in the world, for a 'set' key [q] under [fr]."""
    return arm_world(side, q, **fr)[0] @ np.asarray(d, float)


def body_solve(residuals, pitch0=0.0, yaw0=0.0, root0=(0.0, 0.0, 0.0), roll0=0.0, guess=None, pitch_w=0.05,
               yaw_w=0.03, sink_w=0.4, root_w=0.3, roll_w=0.08, arms_w=0.0, sink0=0.0, drop=0.0):
    """Solves a whole pose: the torso's lean, turn, tip and sink, the root's step (x, z) and both arms' 'set' keys,
    so that residuals(frame, right, left) (contacts, as px errors) vanish, staying near the lean, turn and step asked
    for, and the sink near [sink0] (a torso bent forward wants to sink a little, or its back lifts off the legs).
    [guess]: an earlier solution to start from (keeps the arms in the same family of answers). [drop]: the hips
    lowered that far (a crouch), held fixed.
    Returns (frame, right arm key, left arm key, the solution vector)."""
    def unpack(x):
        fr = F_(qt=bend(x[0], x[1], x[2]), vt=(0, x[3], 0), pr=(x[4], drop, x[5]))
        return fr, rvec_q(x[6:9]), rvec_q(x[9:12])

    def res(x):
        fr, qa, qb = unpack(x)
        out = list(residuals(fr, qa, qb))
        out += [(x[0] - pitch0) * pitch_w, (x[1] - yaw0) * yaw_w, (x[2] - roll0) * roll_w, (x[3] - sink0) * sink_w,
                (x[4] - root0[0]) * root_w, (x[5] - root0[2]) * root_w]
        if arms_w:
            out += list(x[6:12] * arms_w)
        return out
    if guess is not None:
        x = fit(res, guess)
    else:
        x0 = [pitch0, yaw0, roll0, 0.0, root0[0], root0[2]]
        x = fit(res, x0 + [-0.4, 0.3, 0.0, -0.4, -0.3, 0.0],
                starts=[x0 + [-0.8, 0.5, 0.3, -0.8, -0.5, -0.3], x0 + [-0.2, 0.0, 0.2, -0.2, 0.0, -0.2],
                        x0 + [-1.2, 0.2, 0.0, -1.2, -0.2, 0.0]])
    fr, qa, qb = unpack(x)
    return fr, qa, qb, x


def sink_for(pitch):
    """How far a torso bent [pitch] degrees forward sinks so its back stays on the legs."""
    return 2.0 * math.sin(math.radians(max(0.0, pitch)))


def torso_local(fr, p):
    """A world point in the torso's own space (where the body box spans x -4..4, y 0..12, z -2..2)."""
    pr_ = qmat(fr['qr']).T @ (np.asarray(p, float) - HIPS - fr['pr']) + HIPS
    return qmat(fr['qt']).T @ (pr_ - HIPS - fr['vt']) + HIPS


def clear_of_body(fr, p, front=4.0, w=2.0):
    """A residual that pushes a fist (world point [p]) out in front of the torso when it has sunk into it."""
    x, y, z = torso_local(fr, p)
    if abs(x) < 6.0 and -1.0 < y < 13.0 and z > -front:
        return (z + front) * w
    return 0.0


def mirror_dir(d):
    """A direction mirrored left for right."""
    return np.array([-d[0], d[1], d[2]])


def mirror_q(q):
    """A rotation mirrored left for right (x flipped): for keys that do on one side what another did on the other."""
    x, y, z, w = q
    return np.array([x, -y, -z, w])

# ================================================================== previews

LOOK_CACHE = {}


def look_for(key):
    if key not in LOOK_CACHE:
        LOOK_CACHE[key] = Look(*key)
    return LOOK_CACHE[key]


def trace_point(look, pose, part, p):
    return apply(part_matrices(look, pose)[part], p)


def walk_speed(c, walking):
    return 0.55 if walking else 0.0


def frame_times(c, n=None):
    info = INFO[c.name]
    n = n or (13 if c.length >= 60 else 10)
    ts = [c.length * i / (n - 1) for i in range(n)]
    for _, tick in info['events']:
        # put every event tick on the sheet: replace the nearest frame
        j = min(range(len(ts)), key=lambda k: abs(ts[k] - tick))
        ts[j] = float(tick)
    return sorted(set(round(t, 2) for t in ts))


def render_cell(look, c, t, project, size, walking, wire=False):
    info = INFO[c.name]
    base = walk_base(t, speed=walk_speed(c, walking), age=t)
    pose = pose_at(c, t, base)
    mats = part_matrices(look, pose)
    quads = model_quads(look, mats, info['tools'])
    kind = info['station']
    if kind and not wire:
        quads += station(kind)
    else:
        for lo, hi, col, light in station_boxes(kind or ''):
            if hi[1] - lo[1] < 0.5 and hi[0] - lo[0] > 30:
                quads += box(lo, hi, col, light)
    img = raster(quads, project, size, bg=(54, 60, 74))
    if kind and wire:
        station_wire(ImageDraw.Draw(img), kind, project)
    return img, pose


def sheet(c, times=None, views=None, cell=(200, 250), zoom=4.0, center_y=6.0):
    info = INFO[c.name]
    look = look_for(info['look'])
    times = times or frame_times(c)
    views = views or info['views'] or ((32, -12, True), (90, -2))
    walking = info['walking']
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 20), (30, 32, 40))
    d = ImageDraw.Draw(out)
    ev = ' '.join(f'{name}@{tick}' for name, tick in info['events'])
    kind = 'textured' if look.real else 'flat' if look.real_geometry else 'stand-in'
    d.text((4, 4), f'{c.name}  {c.length}t {"loop" if info["loop"] else "once"}{" overlay (walking)" if walking else ""}  '
           f'{ev}  [{look.role} {look.n}, {kind}]', fill=(235, 235, 235))
    paths = []
    for part, p, col in info['traces']:
        pts = []
        k = 0
        while k / 2 <= c.length + 1e-6:
            tt_ = k / 2
            pose = pose_at(c, tt_, walk_base(tt_, speed=walk_speed(c, walking), age=tt_))
            pts.append((tt_, trace_point(look, pose, part, p)))
            k += 1
        paths.append((pts, col))
    for i, t in enumerate(times):
        for j, view in enumerate(views):
            yaw, pitch = view[0], view[1]
            project = ortho(yaw, pitch, zoom, (W, H), center_y if pitch > -60 else 0.0)
            img, pose = render_cell(look, c, t, project, (W, H), walking, wire=len(view) > 2 and view[2])
            dd = ImageDraw.Draw(img)
            for pts, col in paths:
                line = [tuple(project(p)[:2]) for tt_, p in pts]
                dd.line(line, fill=tuple(int(x * 0.45) for x in col), width=1)
                now = [tuple(project(p)[:2]) for tt_, p in pts if tt_ <= t + 1e-6]
                if now:
                    x, y = now[-1]
                    dd.ellipse((x - 2, y - 2, x + 2, y + 2), outline=col)
            out.paste(img, (i * W, 20 + j * H))
        label = f't={t:g}'
        for name, tick in info['events']:
            if abs(tick - t) < 1e-6:
                label += f'  {name}!'
        d.text((i * W + 4, 23), label, fill=(255, 255, 160) if '!' in label else (255, 255, 255))
        d.line([(i * W, 20), (i * W, out.height)], fill=(20, 20, 26))
    return out


def check_loop(c):
    """A loop must end where it starts (every bone's first and last key equal)."""
    bad = []
    for bone, keys in c.keys.items():
        q0, p0 = keys[0][1], keys[0][2]
        q1, p1 = keys[-1][1], keys[-1][2]
        if keys[0][0] != 0 or abs(keys[-1][0] - c.length) > 1e-6:
            bad.append(f'{bone}: keys span {keys[0][0]}..{keys[-1][0]}')
            continue
        if abs(abs(float(np.dot(q0, q1))) - 1) > 1e-9:
            bad.append(f'{bone}: rotation differs')
        a = np.zeros(3) if p0 is None else p0
        b = np.zeros(3) if p1 is None else p1
        if np.linalg.norm(a - b) > 1e-9:
            bad.append(f'{bone}: position differs')
    return bad


def check_overlay(c):
    """Overlays play while walking: no leg tracks and the root left alone."""
    bad = [b for b in ('right_leg', 'left_leg', 'root') if b in c.keys]
    return [f'{b} is tracked' for b in bad]


def report(c):
    """Forward kinematics of the clip's traced points at its event ticks, as the game will pose it, and its checks."""
    info = INFO[c.name]
    look = look_for(info['look'])
    lines = []
    for name, tick in info['events']:
        pose = pose_at(c, tick, walk_base(tick))
        bits = []
        for part, p, col in info['traces']:
            w = trace_point(look, pose, part, p)
            bits.append(f'{part} ({w[0]:.2f}, {w[1]:.2f}, {w[2]:.2f})')
        lines.append(f'  {c.name} t={tick} {name}: ' + ', '.join(bits))
    for label, got, want, tol in info['checks']:
        err = float(np.linalg.norm(got - want))
        flag = '' if err <= tol else '   !! OFF'
        lines.append(f'  {c.name} {label}: ({got[0]:.2f}, {got[1]:.2f}, {got[2]:.2f}) want ({want[0]:.2f}, {want[1]:.2f}, '
                     f'{want[2]:.2f}) off {err:.2f}{flag}')
    return lines


def fk(c, t, part, p, walking=False):
    """A part-space point at [t] of clip [c], posed exactly as the game poses it."""
    look = look_for(INFO[c.name]['look'])
    pose = pose_at(c, t, walk_base(t, speed=walk_speed(c, walking), age=t))
    return trace_point(look, pose, part, p)


def main(argv):
    assets, previews = argv[1], argv[2]
    only = set(argv[3:])
    unknown = only - {c.name for c in clips}
    if unknown:
        raise SystemExit(f'no such clip: {sorted(unknown)}')
    os.makedirs(previews, exist_ok=True)
    for c in clips:
        info = INFO[c.name]
        bad = check_loop(c) if info['loop'] else []
        if info['overlay']:
            bad += check_overlay(c)
        if bad:
            print(f'!! {c.name}: ' + '; '.join(bad))
        c.write(assets, GROUP)
    print(f'wrote {len(clips)} clips to {os.path.join(assets, "animations", GROUP)}')
    for c in clips:
        for line in report(c):
            print(line)
    for c in clips:
        if only and c.name not in only:
            continue
        sheet(c).save(os.path.join(previews, f'townsfolk_{c.name}.png'))

# ================================================================== the smith

# The smith stands a few px back from the anvil, the left foot forward. The tongs in the left hand hold the bar flat
# on the anvil's near edge; at the bottom of every blow the hammer's face lands flat on it. The strike pose is solved
# for exactly that (the root's offset back, the torso's sink and turn, both arms), the swing is built round it.
HAMMER_FACE = (0.0, 5.0, 2.5)           # hammer part space: the centre of the striking face
HAMMER_N = (0.0, 0.0, 1.0)              # its normal
IRON_END = (0.0, 3.6, 0.0)              # hot_iron part space: where the hammer works it, near its far end
IRON_AXIS = (0.0, 1.0, 0.0)
STRIKE = np.array([-2.0, 7.0, -12.5])   # where the face meets the top of the bar
IRON_AT = STRIKE + np.array([0.0, 0.5, 0.0])     # the bar's axis there: it is 1 px thick and lies on the top (y = 8)
SMITH_PITCH = 13.0


def smith_strike():
    face_p, face_n = tool_point('hammer', HAMMER_FACE), tool_dir('hammer', HAMMER_N)
    iron_p, iron_a = tool_point('hot_iron', IRON_END), tool_dir('hot_iron', IRON_AXIS)
    heading = unit(np.array([-0.86, 0.0, -0.51]))

    def unpack(x):
        return F_(qt=bend(SMITH_PITCH, x[2]), vt=(0, x[1], 0), pr=(0, 0, x[0])), rvec_q(x[3:6]), rvec_q(x[6:9])

    def res(x):
        fr, qa, qb = unpack(x)
        Ra, ja = arm_world('right_arm', qa, **fr)
        Rb, jb = arm_world('left_arm', qb, **fr)
        ax = Rb @ iron_a
        return np.concatenate([(ja + Ra @ face_p - STRIKE) * 5, (Ra @ face_n - D) * 1.5, (jb + Rb @ iron_p - IRON_AT) * 5,
                               [ax[1] * 4], (ax - heading) * 0.3, [x[1] * 0.3, x[2] * 0.03]])
    best = None
    for s0 in ([4, 0.5, 0, -1.2, 0, 0, -1.2, 0, 0], [4, 0.5, 0, -1.4, 0.3, 0, -1.0, -0.5, 0.5],
               [3, 0, 5, -1.5, -0.2, 0.2, -0.9, 0.6, -0.4]):
        s = least_squares(res, np.array(s0, float))
        if best is None or s.cost < best.cost:
            best = s
    fr, qa, qb = unpack(best.x)
    Rb, _ = arm_world('left_arm', qb, **fr)
    return dict(root=np.array([0.0, 0.0, best.x[0]]), sink=best.x[1], yaw=best.x[2], ra=qa, la=qb, frame=fr,
                heading=Rb @ iron_a)


SMITH = smith_strike()


def swing_q(fr, raise_deg, turn=0.0, strike=SMITH):
    """The right arm raised [raise_deg] degrees about the shoulder from where it lands the blow (up through the
    front and over), turned [turn] degrees further round to the right: the 'set' key under [fr]."""
    Ra = qfrommat(arm_world('right_arm', strike['ra'], **strike['frame'])[0])
    h = qmat(Ra) @ np.array([0.0, 1.0, 0.0])
    h = unit(np.array([h[0], 0.0, h[2]]))
    axis = np.cross(h, U)
    qw = qmul(qaxis(U, -turn), qmul(qaxis(axis, raise_deg), Ra))
    return rel(fr, qw)


def tongs_q(fr, lift=0.0, turn=0.0, guess=None, strike=SMITH):
    """The left arm holding the bar with the tongs: on the anvil where the blows land, lifted [lift] px and swung
    [turn] degrees (to turn the work between blows)."""
    iron_p, iron_a = tool_point('hot_iron', IRON_END), tool_dir('hot_iron', IRON_AXIS)
    heading = qmat(qaxis(U, turn)) @ strike['heading']
    q, cost = solve_arm('left_arm', fr, [(iron_p, IRON_AT - np.array([0.0, lift, 0.0]), 4.0)], [(iron_a, heading, 2.0)],
                        guess=strike['la'] if guess is None else guess)
    return q


c = clip('hammer', 24, True, look=('smith', 0), tools_=('hammer', 'tongs', 'hot_iron'), station_='anvil',
         traces=[('hammer', HAMMER_FACE, (255, 200, 80)), ('hot_iron', IRON_END, (255, 120, 40))],
         events=[('smith.hammer', 14)])
# (t, torso pitch, yaw, sink, arm raised above the strike, turned further right, ease into this key)
SWING = [(0.0, 2.0, 12.0, -0.3, 106.0, 4.0, 'inout'),     # the hammer up over the shoulder
         (7.0, -1.0, 15.0, -0.5, 117.0, 7.0, 'inout'),    # a breath higher and back: aim
         (14.0, None, None, None, 0.0, 0.0, 'in'),        # down onto the iron, still gathering speed
         (15.5, 11.5, None, -0.1, 17.0, 0.0, 'out'),      # the bounce off it
         (24.0, 2.0, 12.0, -0.3, 106.0, 4.0, 'inout')]    # and up again
for t, pitch, yaw, sink, up, turn, ease in SWING:
    fr = F_(qt=bend(SMITH_PITCH if pitch is None else pitch, SMITH['yaw'] + (1.0 if t == 15.5 else 0.0) if yaw is None else yaw),
            vt=(0, SMITH['sink'] + (sink or 0.0) if pitch is None or t == 15.5 else sink, 0), pr=SMITH['root'])
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, SMITH['ra'] if t == 14.0 else swing_q(fr, up, turn), ease=ease)
# the bar stays on the anvil through the blow; between blows the smith lifts it a hair and turns it
track(c, 'left_arm', ticks(0, 24, 0.5),
      lambda t, fr: tongs_q(fr, lift=1.3 * bump(t, 16.0, 30.0, 24), turn=-14.0 * bump(t, 16.0, 30.0, 24)))
track(c, 'head', ticks(0, 24, 1.0), lambda t, fr: head_at(fr, IRON_AT + np.array([0.6, -0.5, 0.0])))
c.mode('head', 'set')
for t in (0.0, 24.0):
    legs(c, t, rx=7, lx=-10, rz=4, lz=-4)
seal(c)
check(c, 'hammer face at the blow (t=14)', fk(c, 14, 'hammer', HAMMER_FACE), STRIKE)
check(c, 'bar under it (t=14)', fk(c, 14, 'hot_iron', IRON_END), IRON_AT)


LEFT_VIEWS = ((-32, -12, True), (-90, -2))     # work done with the left hand, seen from the left
TONGS_TIP = (0.0, 10.0, 0.0)            # tongs part space: the tip of the jaws
IRON_FAR = (0.0, 4.5, 0.0)              # hot_iron part space: its far end
FORGE_MOUTH = np.array([0.0, 15.5, -8.0])


def tongs_reach(fr, point, part, target, direction, w_dir=1.0, guess=None):
    """The left arm putting a point of the tongs (or the bar) at [target], the tongs pointing along [direction]."""
    q, cost = solve_arm('left_arm', fr, [(tool_point(part, point), target, 4.0)],
                        [(tool_dir('tongs', (0, 1, 0)), direction, w_dir)], guess=guess)
    return q


def lean_to(side, point, target, direction, pitch0, yaw0, root0=(0.0, 0.0, 0.0), w_dir=1.0, part_dir=(0, 1, 0), part='tongs'):
    """Solves the torso's lean, turn and sink, the root's step and one arm so a tool point reaches [target] with the
    tool along [direction], staying near the lean [pitch0] and turn [yaw0] asked for. Returns (frame, arm key)."""
    local = tool_point(part, point) if isinstance(point, tuple) else point
    tdir = tool_dir(part, part_dir)

    def unpack(x):
        fr = F_(qt=bend(x[0], x[1]), vt=(0, x[2], 0), pr=(x[3], 0, x[4]))
        return fr, rvec_q(x[5:8])

    def res(x):
        fr, q = unpack(x)
        Ra, j = arm_world(side, q, **fr)
        return np.concatenate([(j + Ra @ local - target) * 5, (Ra @ tdir - unit(direction)) * 2 * w_dir,
                               [(x[0] - pitch0) * 0.04, (x[1] - yaw0) * 0.03, x[2] * 0.4, (x[3] - root0[0]) * 0.5,
                                (x[4] - root0[2]) * 0.3]])
    fr0 = F_(qt=bend(pitch0, yaw0), pr=root0)
    q0 = q_rvec(free(fr0, unit(np.asarray(target, float) - chain_frame(side, **fr0)[1])))
    x = fit(res, [pitch0, yaw0, 0.0, root0[0], root0[2], *q0], starts=[[pitch0, yaw0, 0.0, root0[0], root0[2], -1.2, 0, 0]])
    return unpack(x)


# ---- tend: rake the forge's coals with the tongs (the fire flares on tick 12), stir, rake again
c = clip('tend', 40, True, look=('smith', 1), tools_=('hammer', 'tongs'), station_='forge',
         traces=[('tongs', TONGS_TIP, (255, 160, 60))], events=[('smith.bellows', 12)], views=LEFT_VIEWS)
POKE = [  # (t, tip target, tongs direction, torso pitch, yaw, ease)
    (0.0, (1.5, 14.0, -5.0), v(f=1.0, u=-0.45, r=0.15), 9.0, -6.0, 'inout'),
    (7.0, (2.0, 12.5, -3.0), v(f=1.0, u=-0.25, r=0.1), 5.0, -4.0, 'inout'),      # draw back
    (12.0, (-0.5, 16.0, -11.0), v(f=1.0, u=-0.5, r=0.2), 22.0, -12.0, 'in'),     # thrust into the fire
    (15.0, (-2.0, 15.0, -10.5), v(f=1.0, u=-0.45, r=0.45), 21.0, -14.0, 'inout'), # stir
    (18.0, (1.0, 16.5, -11.5), v(f=1.0, u=-0.55, r=-0.1), 23.0, -10.0, 'inout'),
    (21.0, (-1.0, 15.5, -10.0), v(f=1.0, u=-0.45, r=0.25), 21.0, -12.0, 'inout'),
    (25.0, (1.5, 14.0, -5.5), v(f=1.0, u=-0.4, r=0.1), 10.0, -6.0, 'inout'),     # out
    (29.0, (0.5, 13.0, -10.5), v(f=1.0, u=-0.25, r=0.1), 17.0, -9.0, 'inout'),   # in high, over the coals
    (33.0, (1.0, 15.5, -6.5), v(f=1.0, u=-0.5, r=0.1), 13.0, -7.0, 'inout'),     # rake them forward
    (40.0, (1.5, 14.0, -5.0), v(f=1.0, u=-0.45, r=0.15), 9.0, -6.0, 'inout'),
]
TEND_ROOT = (0.0, 0.0, 0.5)
for t, tip, d, pitch, yaw, ease in POKE:
    fr, q = lean_to('left_arm', TONGS_TIP, np.array(tip), d, pitch, yaw, TEND_ROOT)
    key_frame(c, t, fr, ease=ease)
    c.key('left_arm', t, q, ease=ease)
    # the hammer hangs from the right hand, swung a little forward as the smith leans in
    c.key('right_arm', t, rel(fr, qmul(qaxis(R, -(4 + 0.5 * pitch)), qeuler(z=6))), ease=ease)
    c.key('head', t, head_at(fr, np.array(tip) + np.array([0, -1.0, -1.0])), ease=ease)
c.mode('head', 'set')
legs(c, 0, rx=6, lx=-9, rz=3, lz=-3)
legs(c, 12, rx=8, lx=-12, rz=3, lz=-3, ease='in')
legs(c, 25, rx=6, lx=-9, rz=3, lz=-3)
legs(c, 40, rx=6, lx=-9, rz=3, lz=-3)
seal(c)
check(c, 'tongs in the forge mouth (t=12)', fk(c, 12, 'tongs', TONGS_TIP), (-0.5, 16.0, -11.0))

# ---- quench: carry the glowing bar over the cauldron, plunge it (tick 20), hold it in the steam, lift and look
c = clip('quench', 50, False, look=('smith', 2), tools_=('hammer', 'tongs', 'hot_iron'), station_='cauldron',
         traces=[('hot_iron', IRON_FAR, (255, 120, 40))], events=[('smith.quench', 20)], views=LEFT_VIEWS)
QUENCH = [  # (t, far end of the bar, bar direction, pitch, yaw, ease)
    (0.0, (5.0, 2.0, -13.0), v(f=1.0, u=0.55, r=-0.1), 2.0, -4.0, 'inout'),      # the glowing bar held up, forward
    (9.0, (1.0, 2.5, -15.0), v(f=0.6, u=-1.0, r=0.1), 6.0, -8.0, 'inout'),       # over the water, pointing down
    (15.0, (1.0, 1.0, -15.0), v(f=0.55, u=-1.0, r=0.1), 4.0, -8.0, 'inout'),     # a breath up: aim
    (20.0, (0.5, 13.5, -16.0), v(f=0.55, u=-1.0, r=0.1), 15.0, -10.0, 'in'),     # in
    (25.0, (1.5, 13.0, -15.0), v(f=0.5, u=-1.0, r=0.0), 15.0, -9.0, 'inout'),    # held in the steam, stirred
    (30.0, (-0.5, 13.5, -16.5), v(f=0.6, u=-1.0, r=0.2), 16.0, -11.0, 'inout'),
    (33.0, (0.5, 13.0, -16.0), v(f=0.55, u=-1.0, r=0.1), 15.0, -10.0, 'inout'),
    (39.0, (1.5, 0.0, -14.0), v(f=0.7, u=-1.0, r=0.0), 3.0, -6.0, 'out'),        # out
    (44.0, (2.0, -3.0, -9.5), v(f=1.0, u=0.9, r=-0.3), -2.0, -4.0, 'inout'),     # turned up to look it over
    (50.0, (5.0, 2.0, -13.0), v(f=1.0, u=0.55, r=-0.1), 2.0, -4.0, 'inout'),
]
for t, end, d, pitch, yaw, ease in QUENCH:
    fr, q = lean_to('left_arm', IRON_FAR, np.array(end), d, pitch, yaw, (0.0, 0.0, 0.0), part='hot_iron')
    key_frame(c, t, fr, ease=ease)
    c.key('left_arm', t, q, ease=ease)
    c.key('right_arm', t, rel(fr, qmul(qaxis(R, -(3 + 0.4 * pitch)), qeuler(z=5))), ease=ease)
    look = np.array(end) if t < 39 else np.array(end) + np.array([0, -2.0, 0])
    c.key('head', t, head_at(fr, look), ease=ease)
c.mode('head', 'set')
legs(c, 0, rx=2, lx=-2, rz=2, lz=-2)
legs(c, 9, rx=6, lx=-8, rz=3, lz=-3)
legs(c, 39, rx=6, lx=-8, rz=3, lz=-3)
legs(c, 50, rx=2, lx=-2, rz=2, lz=-2)
check(c, 'bar under water (t=20)', fk(c, 20, 'hot_iron', IRON_FAR), (0.5, 13.5, -16.0))


# ================================================================== the baker

# ---- knead: lean over the dough on the table, both hands press it away (ticks 8 and 22) and fold it back
c = clip('knead', 30, True, look=('baker', 0), station_='table',
         traces=[('right_arm', GRIP['right_arm'], (255, 230, 160)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
         events=[('baker.knead', 8), ('baker.knead', 22)])
KNEAD = [  # (t, right hand, left hand, pitch, yaw, ease)
    (0.0, (-2.0, 5.0, -10.0), (2.0, 5.0, -10.0), 15.0, 0.0, 'inout'),     # folded back, hands up
    (4.0, (-2.0, 7.2, -10.0), (2.0, 7.2, -10.0), 17.0, 0.0, 'inout'),     # down on the near edge
    (8.0, (-1.8, 7.6, -13.0), (1.8, 7.6, -13.0), 25.0, 0.0, 'in'),        # pressed away, the weight on it
    (11.5, (-1.8, 5.8, -13.2), (1.8, 5.8, -13.2), 21.0, 0.0, 'out'),      # up at the far edge
    (15.0, (-2.0, 5.0, -10.0), (2.0, 5.0, -10.0), 15.0, -3.0, 'inout'),   # folded back
    (18.0, (-2.0, 7.2, -10.0), (2.0, 7.2, -10.0), 17.0, -4.0, 'inout'),
    (22.0, (-2.6, 7.6, -13.2), (1.2, 7.6, -12.4), 25.0, -6.0, 'in'),      # pressed, turning it
    (25.5, (-2.0, 5.8, -13.0), (1.6, 5.8, -12.6), 21.0, -3.0, 'out'),
    (30.0, (-2.0, 5.0, -10.0), (2.0, 5.0, -10.0), 15.0, 0.0, 'inout'),
]
prev = None
for t, rh, lh, pitch, yaw, ease in KNEAD:
    rh, lh = np.array(rh), np.array(lh)
    sink = 0.0

    def contacts(fr, qa, qb, rh=rh, lh=lh):
        return np.concatenate([(hand_point('right_arm', qa, fr) - rh) * 4, (hand_point('left_arm', qb, fr) - lh) * 4,
                               (arm_dir('right_arm', qa, fr, (0, 0, -1)) - unit(v(f=0.5, u=-1.0))) * 0.3,
                               (arm_dir('left_arm', qb, fr, (0, 0, -1)) - unit(v(f=0.5, u=-1.0))) * 0.3])
    fr, qa, qb, prev = body_solve(contacts, pitch, yaw, (0.0, 0.0, 0.0), guess=prev, sink_w=0.25)
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, head_at(fr, np.array([0.0, 7.0, -11.5])), ease=ease)
c.mode('head', 'set')
legs(c, 0, rx=5, lx=-5, rz=4, lz=-4)
legs(c, 8, rx=8, lx=-3, rz=4, lz=-4, ease='in')
legs(c, 15, rx=5, lx=-5, rz=4, lz=-4)
legs(c, 22, rx=8, lx=-3, rz=4, lz=-4, ease='in')
legs(c, 30, rx=5, lx=-5, rz=4, lz=-4)
seal(c)
for tick in (8, 22):
    check(c, f'hands on the dough (t={tick})', (fk(c, tick, 'right_arm', GRIP['right_arm']) + fk(c, tick, 'left_arm', GRIP['left_arm'])) / 2,
          ((-1.8 + 1.8) / 2, 7.6, -13.0) if tick == 8 else ((-2.6 + 1.2) / 2, 7.6, -12.8), tol=0.6)

# ---- oven: both hands on the peel, lunge to slide it deep into the oven (tick 26), work it, draw it back
PEEL_BLADE = (0.0, 15.0, 0.0)          # peel part space: the blade's centre
PEEL_HOLD = (0.0, 5.0, 0.0)            # where the left hand takes the pole
c = clip('oven', 60, False, look=('baker', 1), tools_=('peel',), station_='oven',
         traces=[('peel', PEEL_BLADE, (255, 220, 140))], events=[('baker.oven', 26)])
OVEN = [  # (t, blade centre, pole slope (degrees down), pitch, yaw, root z, ease)
    (0.0, (-0.5, 16.3, -13.5), 18.0, 18.0, 12.0, 3.3, 'inout'),     # the blade in the mouth
    (6.0, (-0.5, 16.0, -12.5), 20.0, 15.0, 11.0, 3.8, 'inout'),     # drawn back a hair: set
    (26.0, (-0.5, 18.4, -20.5), 14.0, 28.0, 16.0, -3.2, 'inout'),   # lunged: deep in, along the floor under the bread
    (29.0, (-0.5, 18.3, -19.0), 15.0, 27.0, 15.0, -2.4, 'inout'),   # worked back and forth
    (32.0, (-0.5, 18.4, -20.5), 14.0, 28.0, 16.0, -3.2, 'inout'),
    (47.0, (-0.5, 16.2, -13.5), 18.0, 18.0, 12.0, 3.3, 'inout'),    # drawn out with the bread
    (60.0, (-0.5, 16.0, -13.0), 20.0, 12.0, 10.0, 3.5, 'inout'),    # straighten
]
prev = None
for t, blade, slope, pitch, yaw, rz, ease in OVEN:
    blade = np.array(blade)
    d = np.array([0.0, math.sin(math.radians(slope)), -math.cos(math.radians(slope))])

    def contacts(fr, qa, qb, blade=blade, d=d, t=t):
        Ra, ja = arm_world('right_arm', qa, **fr)
        pole = ja + Ra @ tool_point('peel', PEEL_HOLD)
        return np.concatenate([(ja + Ra @ tool_point('peel', PEEL_BLADE) - blade) * 5,
                               (Ra @ tool_dir('peel', (0, 1, 0)) - d) * (6 if t in (26.0, 29.0, 32.0) else 3),
                               (hand_point('left_arm', qb, fr) - pole) * 2,
                               (Ra @ tool_dir('peel', (0, 0, 1)) - U) * 0.5])      # the blade lying flat
    fr, qa, qb, prev = body_solve(contacts, pitch, yaw, (0.0, 0.0, rz), guess=prev)
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, head_at(fr, OVEN_MOUTH + np.array([0, 0.5, -2.0])), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(0.4, 3.5), left=(0.4, -4.0), ease=ease)
c.mode('head', 'set')
check(c, 'peel blade in the oven (t=26)', fk(c, 26, 'peel', PEEL_BLADE), (-0.5, 18.4, -20.5))

# the pole must pass through the mouth, not the stone round it
for tick in (0, 14, 20, 23, 26, 29, 32, 38, 44, 47):
    look = look_for(INFO['oven']['look'])
    pose = pose_at(c, tick)
    M = part_matrices(look, pose)['peel']
    a, b = apply(M, (0, 0, 0)), apply(M, (0, 12, 0))
    k = (-8.0 - a[2]) / (b[2] - a[2])
    _x = a + (b - a) * k
    check(c, f'pole crosses the oven face inside the mouth (t={tick})', _x,
          (min(3.0, max(-3.0, _x[0])), min(18.5, max(13.5, _x[1])), -8.0), tol=0.01)

# ================================================================== the merchant

def relaxed(fr=None, side='right_arm', out=4.0, fwd=3.0):
    """An arm hanging easy at the side, a touch out and forward: the 'set' key under [fr]."""
    s_ = -1 if side == 'right_arm' else 1
    qw = qmul(qaxis((0, 0, 1), -s_ * out), qaxis((1, 0, 0), -fwd))
    return rel(fr or F_(), qw)


STALL = np.array([0.0, 7.0, -12.0])

# ---- call_out: a hand cupped to the mouth, a lean to the street, the other arm sweeping over the goods
c = clip('call_out', 40, False, look=('merchant', 0), station_='counter',
         traces=[('right_arm', GRIP['right_arm'], (255, 220, 120))])
f0 = F_()
CALL = [  # (t, pitch, yaw, right arm direction, palm, left hand to the mouth?, head target, ease)
    (0.0, 0.0, 0.0, None, None, False, STALL, 'inout'),
    (5.0, -4.0, 3.0, v(f=0.2, u=-1.0, r=0.1), v(f=-0.3, r=1.0), True, (0.0, -6.0, -40.0), 'inout'),   # breath in, hand up
    (9.0, 9.0, -3.0, v(f=1.0, u=-0.45, r=0.75), U, True, (-6.0, -2.0, -40.0), 'out'),                # the call: lean out
    (17.0, 8.0, -6.0, v(f=1.0, u=-0.4, r=0.1), U, True, (0.0, -2.0, -40.0), 'inout'),
    (25.0, 7.0, -10.0, v(f=1.0, u=-0.4, r=-0.55), U, True, (6.0, -1.0, -40.0), 'inout'),             # sweep over the goods
    (31.0, 3.0, -4.0, v(f=0.8, u=-0.6, r=-0.3), v(f=0.3, u=1.0), False, (2.0, 2.0, -30.0), 'inout'),
    (40.0, 0.0, 0.0, None, None, False, STALL, 'inout'),
]
for t, pitch, yaw, rd, palm, cup, look_at, ease in CALL:
    fr = F_(qt=bend(pitch, yaw))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, relaxed(fr) if rd is None else free(fr, rd, palm), ease=ease)
    if cup:
        hq, _ = solve_arm('left_arm', fr, [(GRIP['left_arm'], head_point(fr, head_at(fr, np.array(look_at)), (2.6, -1.6, -5.0)), 1.0)],
                          [((0, 0, -1), v(r=1.0, u=0.2), 0.3)])
    else:
        hq = relaxed(fr, 'left_arm')
    c.key('left_arm', t, hq, ease=ease)
    c.key('head', t, head_at(fr, np.array(look_at)), ease=ease)
c.mode('head', 'set')

# ---- arrange: reach to the goods, shift one along (set down on tick 22), straighten another, step back, brush hands
c = clip('arrange', 60, True, look=('merchant', 1), station_='counter',
         traces=[('right_arm', GRIP['right_arm'], (255, 220, 120))], events=[('merchant.arrange', 22)])
ARR = [  # (t, right hand target or None, left hand target or None, pitch, yaw, root z, head target, ease)
    (0.0, None, None, 2.0, 0.0, 0.0, STALL, 'inout'),
    (9.0, (-3.5, 6.2, -12.5), None, 17.0, 6.0, -0.5, (-3.5, 6.0, -12.5), 'inout'),          # reach to it
    (12.0, (-3.5, 6.6, -12.5), None, 17.0, 6.0, -0.5, (-3.5, 6.0, -12.5), 'inout'),         # take hold
    (16.0, (-1.0, 3.6, -12.0), None, 15.0, 2.0, -0.5, (0.5, 6.0, -12.0), 'inout'),          # lift it over
    (22.0, (2.0, 6.4, -11.5), None, 17.0, -6.0, -0.5, (2.0, 6.0, -11.5), 'in'),             # set it down
    (25.0, (1.5, 5.0, -11.0), (3.0, 6.5, -13.5), 17.0, -10.0, -0.5, (3.0, 6.0, -13.5), 'inout'),   # and square another
    (29.0, None, (2.5, 6.5, -12.8), 16.0, -8.0, -0.5, (2.5, 6.0, -13.0), 'inout'),
    (35.0, None, None, -2.0, 0.0, 1.2, STALL + np.array([0, 0, -2.0]), 'inout'),            # step back: look it over
    (39.0, (0.4, 8.5, -6.0), (-0.4, 8.5, -6.0), -1.0, 0.0, 1.2, STALL, 'inout'),           # brush the hands
    (42.0, (0.4, 9.8, -5.5), (-0.4, 7.6, -6.2), -1.0, 0.0, 1.2, STALL, 'inout'),
    (45.0, (0.4, 7.8, -6.2), (-0.4, 9.6, -5.6), -1.0, 0.0, 1.2, STALL, 'inout'),
    (48.0, (0.4, 9.5, -5.6), (-0.4, 8.0, -6.0), -1.0, 0.0, 1.2, STALL, 'inout'),
    (52.0, None, None, 0.0, 2.0, 0.6, STALL + np.array([2.0, 0, 0]), 'inout'),
    (60.0, None, None, 2.0, 0.0, 0.0, STALL, 'inout'),
]
for t, rh, lh, pitch, yaw, rz, look_at, ease in ARR:
    fr = F_(qt=bend(pitch, yaw), pr=(0, 0, rz))
    qa = relaxed(fr) if rh is None else reach('right_arm', fr, np.array(rh), palm=D if rh[1] > 6 else None)
    qb = relaxed(fr, 'left_arm') if lh is None else reach('left_arm', fr, np.array(lh), palm=D if lh[1] > 7 and lh[2] < -9 else None)
    if rh is not None and (lh is not None or rh[2] < -9):
        # hands on the goods: lean as far as it takes for them to land
        def res(fr_, qa_, qb_, rh=rh, lh=lh):
            out = list((hand_point('right_arm', qa_, fr_) - np.array(rh, float)) * 3)
            if rh[1] > 6:
                out += list((arm_dir('right_arm', qa_, fr_, (0, 0, -1)) - D) * 0.3)
            if lh is not None:
                out += list((hand_point('left_arm', qb_, fr_) - np.array(lh, float)) * 3)
            else:
                out += list((hand_point('left_arm', qb_, fr_) - hand_point('left_arm', relaxed(fr_, 'left_arm'), fr_)) * 0.3)
            return out
        fr, qa, qb, _ = body_solve(res, pitch, yaw, (0.0, 0.0, rz), sink0=sink_for(pitch), sink_w=2.0, root_w=1.0)
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, head_at(fr, np.array(look_at)), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(0.3, 0.5), left=(0.3, -0.5), ease=ease)
c.mode('head', 'set')
seal(c)
check(c, 'goods set down (t=22)', fk(c, 22, 'right_arm', GRIP['right_arm']), (2.0, 6.4, -11.5))

# ---- beckon: the right hand up, palm to the sky, scooping them over: come here
c = clip('beckon', 30, False, look=('merchant', 2), traces=[('right_arm', GRIP['right_arm'], (255, 220, 120))])
BECK = [  # (t, arm direction, palm, pitch, head nod, ease)
    (0.0, None, None, 0.0, 0.0, 'inout'),
    (6.0, v(f=1.0, u=0.15, r=0.25), U, 5.0, 0.0, 'out'),
    (9.5, v(f=1.0, u=0.75, r=0.2), B, -2.0, 8.0, 'inout'),     # scoop: come...
    (12.5, v(f=1.0, u=0.1, r=0.25), U, 5.0, 0.0, 'inout'),
    (16.0, v(f=1.0, u=0.75, r=0.2), B, -2.0, 8.0, 'inout'),    # ...here
    (19.0, v(f=1.0, u=0.1, r=0.25), U, 5.0, 0.0, 'inout'),
    (22.5, v(f=1.0, u=0.75, r=0.2), B, -2.0, 6.0, 'inout'),
    (30.0, None, None, 0.0, 0.0, 'inout'),
]
for t, d, palm, pitch, nod, ease in BECK:
    fr = F_(qt=bend(pitch, -4.0 if d is not None else 0.0))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, relaxed(fr) if d is None else free(fr, d, palm), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=5.0, fwd=2.0 + 0.3 * pitch), ease=ease)
    c.key('head', t, qeuler(x=nod, z=-6.0 if d is not None else 0.0), ease=ease)
c.mode('head', 'add')

# ================================================================== the watch

SPEAR_BUTT = (0.0, 12.0, 0.0)          # spear part space: the bottom of the butt cap
SPEAR_TIP = (0.0, -26.0, 0.0)

# ---- patrol (overlay): the spear carried upright, a hand's width off the ground, the left arm swinging, eyes about
c = clip('patrol', 40, True, look=('guard', 0), tools_=('spear',), overlay=True,
         traces=[('spear', SPEAR_BUTT, (220, 220, 255))])
for t in (0.0, 10.0, 20.0, 30.0, 40.0):
    ph = 2 * math.pi * t / 40.0
    c.key('torso', t, bend(-2.0 + 0.6 * math.sin(2 * ph), 0.0, 1.2 * math.sin(ph)), ease='inout')
    c.key('right_arm', t, qeuler(x=-5.0 - 1.0 * math.sin(2 * ph), z=3.0), pos=(0, -1.6, 0), ease='inout')
    c.key('left_arm', t, qeuler(x=0.0, z=-4.0), ease='inout')
    c.key('head', t, qeuler(x=-2.0, y=14.0 * math.sin(ph)), ease='inout')
c.mode('left_arm', 'add')
c.mode('head', 'add')
seal(c)

# ---- stand_guard: at attention, the spear grounded by the right foot, the left hand on the belt, a slow look round
c = clip('stand_guard', 120, True, look=('guard', 1), tools_=('spear',),
         traces=[('spear', SPEAR_BUTT, (220, 220, 255))])
BELT = np.array([1.2, 10.6, -3.4])
for t in (0.0, 15.0, 30.0, 45.0, 60.0, 75.0, 90.0, 105.0, 120.0):
    breath = math.sin(2 * math.pi * t / 30.0)
    fr = F_(qt=bend(-1.0 - 0.7 * breath))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, rel(fr, qeuler(z=1.5)), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, BELT, palm=B), ease='inout')
LOOKS = [(0.0, 0.0), (20.0, 0.0), (36.0, 30.0), (56.0, 30.0), (78.0, -30.0), (98.0, -30.0), (114.0, 0.0), (120.0, 0.0)]
for t, yaw in LOOKS:
    c.key('head', t, qeuler(x=-3.0 + (2.0 if yaw else 0.0), y=yaw), ease='inout')
c.mode('head', 'set')
for t in (0.0, 120.0):
    legs(c, t, rz=1.5, lz=-1.5)
seal(c)
_butt = fk(c, 0, 'spear', SPEAR_BUTT)
check(c, 'spear grounded (t=0)', _butt, (_butt[0], 24.0, _butt[2]), tol=0.3)

# ---- salute: the left fist brought smartly across the chest, a nod, the fist down
c = clip('salute', 30, False, look=('guard', 2), tools_=('spear',), traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
SAL = [(0.0, None, 0.0, 0.0, 'inout'), (6.0, True, 0.0, 0.0, 'out'), (10.0, True, 0.0, 0.0, 'inout'),
       (14.0, True, 5.0, 16.0, 'inout'), (19.0, True, 0.0, 0.0, 'inout'), (24.0, True, 0.0, 0.0, 'inout'),
       (30.0, None, 0.0, 0.0, 'inout')]
for t, up, bow, nod, ease in SAL:
    fr = F_(qt=bend(-1.0 + bow))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, rel(fr, qeuler(z=1.5)), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=2.0, fwd=1.0) if up is None else free(fr, v(f=0.62, u=-0.12, r=0.78), B), ease=ease)
    c.key('head', t, qeuler(x=nod), ease=ease)
c.mode('head', 'add')
for t in (0.0, 30.0):
    legs(c, t, rz=1.0, lz=-1.0)


# ================================================================== the farmer

HOE_EDGE = (0.0, 18.4, 4.5)            # hoe part space: the middle of the blade's edge
HOE_HOLD = (0.0, 4.5, 0.0)             # where the left hand takes the shaft
DIRT = np.array([-2.0, 24.0, -13.0])


def hoe_contacts(edge=None, shaft=None, fist=None, w_shaft=4.0):
    """Residuals for a hoe pose: the blade's edge (or the right fist) at a point, the shaft along a direction, the
    left hand on the shaft, the blade square (its width level), both fists clear of the body."""
    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        out = []
        if edge is not None:
            out += list((ja + Ra @ tool_point('hoe', HOE_EDGE) - np.asarray(edge, float)) * 5)
        if fist is not None:
            out += list((ja + Ra @ GRIP['right_arm'] - np.asarray(fist, float)) * 3)
        if shaft is not None:
            out += list((Ra @ tool_dir('hoe', (0, 1, 0)) - unit(shaft)) * w_shaft)
        lh = hand_point('left_arm', qb, fr)
        out += list((lh - (ja + Ra @ tool_point('hoe', HOE_HOLD))) * 2.5)
        out += [(Ra @ tool_dir('hoe', (1, 0, 0)))[1] * 1.0]
        out += [clear_of_body(fr, ja + Ra @ GRIP['right_arm']), clear_of_body(fr, lh)]
        return out
    return res


def slope(deg):
    """Forward and [deg] degrees down (negative: up; past -90 tipping back)."""
    return np.array([0.0, math.sin(math.radians(deg)), -math.cos(math.radians(deg))])


# ---- hoe: both hands on the shaft, lift, chop the blade into the soil (tick 16), drag it back
c = clip('hoe', 32, True, look=('farmer', 0), tools_=('hoe',), station_='field',
         traces=[('hoe', HOE_EDGE, (230, 210, 150))], events=[('farmer.hoe', 16)])
HOE_STANCE = np.array([0.0, 0.0, 3.0])    # the farmer works from 3 px further back than the block's centre
HOE = {  # t: (contacts, pitch, yaw, ease, solve from)
    16.0: (hoe_contacts(edge=DIRT + np.array([0, 0.4, 0]), shaft=slope(62)), 20.0, 20.0, "in", None),   # chop
    18.0: (hoe_contacts(edge=DIRT + np.array([0, 0.7, 0.2]), shaft=slope(64)), 21.0, 20.0, "out", 16.0),
    26.0: (hoe_contacts(edge=(-2.0, 24.4, -10.0), shaft=slope(80)), 15.0, 20.0, 'inout', 18.0),   # dragged back
    0.0: (hoe_contacts(edge=(-2.0, 22.6, -10.6), shaft=slope(72)), 10.0, 18.0, 'inout', 26.0),    # lifting off
    10.0: (hoe_contacts(fist=(-3.0, 0.5, -3.0), shaft=slope(-100)), -6.0, 14.0, 'inout', None),   # raised
    12.0: (hoe_contacts(fist=(-3.0, -0.5, -2.5), shaft=slope(-106)), -7.0, 14.0, 'inout', 10.0),  # the top: aim
    5.0: (hoe_contacts(fist=(-2.0, 5.0, -6.0), shaft=slope(-20)), 4.0, 16.0, 'inout', 10.0),      # rising
}
sols = {}
for t, (res, pitch, yaw, ease, start) in HOE.items():
    fr, qa, qb, x = body_solve(res, pitch, yaw, HOE_STANCE, guess=None if start is None else sols[start][4],
                               sink0=sink_for(pitch), pitch_w=0.3, yaw_w=0.3, sink_w=3.0, root_w=3.0)
    sols[t] = (fr, qa, qb, ease, x)
sols[32.0] = sols[0.0]
for t, (fr, qa, qb, ease, x) in sorted(sols.items()):
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, head_at(fr, DIRT + np.array([0.0, -1.0, 1.0])), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(0.3, HOE_STANCE[2] + 3.0), left=(0.3, HOE_STANCE[2] - 4.0), ease=ease)
c.mode('head', 'set')
seal(c)
check(c, 'hoe blade in the soil (t=16)', fk(c, 16, 'hoe', HOE_EDGE), DIRT + np.array([0, 0.4, 0]))
for _t in (19, 21, 23, 25):
    _e = fk(c, _t, 'hoe', HOE_EDGE)
    check(c, f'hoe blade dragging (t={_t})', _e, (_e[0], 24.5, _e[2]), tol=0.6)

# ---- harvest: a step into a low crouch, the hand to the crop, pluck it (tick 20), up to look at it, stand
def leg_point(side, root, foot, along):
    """A point [along] px down a leg (from the hip) whose foot is planted at [foot] (x outward, z) under hips moved
    by [root]: where a hand rests on a thigh."""
    s_ = -1 if side == 'right_leg' else 1
    hip = np.array(PIVOTS[side], float) + np.asarray(root, float)
    f = np.array([PIVOTS[side][0] + s_ * foot[0], 24.0, foot[1]])
    return hip + unit(f - hip) * along


def crouch_feet(drop, out=1.0, right_back=True):
    """Feet planted so straight legs reach the ground under hips lowered [drop] px: one forward, one back."""
    dz = math.sqrt(max(0.0, 12.0 ** 2 - (12.0 - drop) ** 2 - out ** 2))
    back, fwd = (out, dz), (out, -dz)
    return (back, fwd) if right_back else (fwd, back)


c = clip('harvest', 40, False, look=('farmer', 1), station_='crop',
         traces=[('right_arm', GRIP['right_arm'], (160, 230, 120))], events=[('farmer.harvest', 20)])
CROP_AT = np.array([-1.0, 18.8, -13.8])
STEP = crouch_feet(3.6)      # right knee back, left foot forward
HARV = [  # (t, drop, right hand (None: relaxed), pitch, yaw, feet (right, left) or None, ease)
    (0.0, 0.0, None, 0.0, 0.0, ((0.3, 0.6), (0.3, -0.6)), 'inout'),
    (10.0, 3.2, (-1.5, 15.5, -11.5), 50.0, 6.0, None, 'inout'),        # down, reaching
    (16.0, 3.6, CROP_AT + np.array([0.0, -0.6, 0.3]), 62.0, 8.0, None, 'inout'),   # takes hold
    (20.0, 3.6, CROP_AT, 63.0, 8.0, None, 'in'),                         # a twist and a tug: pluck
    (23.0, 3.4, (-1.5, 13.0, -11.5), 50.0, 6.0, None, 'out'),           # up it comes
    (28.0, 2.4, (-2.0, 3.5, -8.5), 22.0, 2.0, None, 'inout'),            # held up to look at
    (34.0, 0.4, (-4.0, 8.5, -6.0), 4.0, 0.0, ((0.3, 0.6), (0.3, -0.6)), 'inout'),   # standing
    (40.0, 0.0, None, 0.0, 0.0, ((0.3, 0.6), (0.3, -0.6)), 'inout'),
]
prev = None
for t, drop, rh, pitch, yaw, feet, ease in HARV:
    feet = feet or STEP
    if rh is None:
        fr = F_(qt=bend(pitch, yaw), pr=(0, drop, 0))
        qa, qb = relaxed(fr), relaxed(fr, 'left_arm')
    else:
        knee = leg_point('left_leg', (0, drop, 0), feet[1], 5.5) + np.array([0.0, -2.0, 0.0])
        def res(fr, qa, qb, rh=rh, knee=knee):
            out = list((hand_point('right_arm', qa, fr) - np.asarray(rh, float)) * 3)
            out += list((hand_point('left_arm', qb, fr) - knee) * (1.0 if drop > 2 else 0.3))
            out += list((arm_dir('right_arm', qa, fr, (0, 0, -1)) - unit(v(f=0.4, u=-1.0))) * 0.3)
            return out
        fr, qa, qb, prev = body_solve(res, pitch, yaw, (0.0, 0.0, 0.0), guess=prev, sink0=sink_for(pitch), sink_w=2.0,
                                      root_w=1.0, pitch_w=0.08, drop=drop)
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    look_at = CROP_AT if rh is None or t < 26 else np.array(rh, float) + np.array([0.0, 0.0, -1.0])
    if rh is None or t >= 34:
        look_at = np.array([0.0, 4.0, -40.0])
    c.key('head', t, head_at(fr, look_at), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=feet[0], left=feet[1], ease=ease)
# the step: the left foot lifts on its way forward, the right slides back onto its knee
legs(c, 5.0, rx=8.0, lx=-30.0, rz=2.0, lz=-2.0)
c.mode('head', 'set')
check(c, 'hand on the crop (t=20)', fk(c, 20, 'right_arm', GRIP['right_arm']), CROP_AT, tol=0.4)

# ================================================================== the fisher

ROD_TIP = (0.0, 26.0, 0.0)             # rod part space: the tip, where the line hangs from
FISH_TIP = np.array([-4.0, -5.0, -26.0])


def rod_q(fr, tip, guess=None):
    """The right arm holding the rod with its tip at [tip], the reel hanging under it."""
    q, cost = solve_arm('right_arm', fr, [(tool_point('rod', ROD_TIP), np.asarray(tip, float), 1.0)],
                        [(tool_dir('rod', (0, 0, 1)), D, 0.4)], guess=guess)
    return q


def rod_swing(fr, angle, out=8.0):
    """The right arm raised [angle] degrees forward (0 hanging, 180 straight up), the rod at right angles to it."""
    return rel(fr, qmul(qaxis((0, 0, 1), out), qaxis((1, 0, 0), -angle)))


HIP_L = np.array([4.6, 10.5, -1.2])

# ---- fish: the rod held out over the water, the left hand on the hip, a small bob now and then, patient
c = clip('fish', 80, True, look=('fisher', 0), tools_=('rod', 'line'), station_='water',
         traces=[('rod', ROD_TIP, (230, 230, 200))], views=((40, -14, True), (90, -2)))
FISH = [(0.0, 0.0, 0.0, 0.0), (14.0, 0.6, 1.0, 0.0), (22.0, -1.6, 0.5, 0.0), (26.0, 0.2, 0.5, 0.0),
        (40.0, 0.0, -1.0, 6.0), (52.0, 0.0, -0.5, 6.0), (60.0, -1.2, 0.0, 0.0), (64.0, 0.3, 0.5, 0.0), (80.0, 0.0, 0.0, 0.0)]
for t, dip, sway, glance in FISH:
    fr = F_(qt=bend(4.0, -6.0, sway))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, rod_q(fr, FISH_TIP + np.array([0.0, -dip * 1.4, 0.0])), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, HIP_L, palm=R), ease='inout')
    c.key('head', t, head_at(fr, np.array([-4.0 + glance * 3, 40.0, -44.0]), extra=qeuler(z=glance * 1.2)), ease='inout')
c.mode('head', 'set')
legs(c, 0, rx=4, lx=-6, rz=3, lz=-3)
legs(c, 80, rx=4, lx=-6, rz=3, lz=-3)
seal(c)
_tip = fk(c, 0, 'rod', ROD_TIP)
check(c, 'line reaches the water (tip y + 46 >= 40)', (0, _tip[1] + 46.0, 0), (0, max(40.0, _tip[1] + 46.0), 0), tol=0.01)

# ---- cast: the rod back over the shoulder, flicked out (tick 16), followed through, settled into the hold
c = clip('cast', 30, False, look=('fisher', 1), tools_=('rod',), station_='water',
         traces=[('rod', ROD_TIP, (230, 230, 200))], events=[('fisher.cast', 16)], views=((40, -14, True), (90, -2)))
CAST = [  # (t, arm raise, pitch, yaw, left arm direction, ease)
    (0.0, 28.0, 0.0, 0.0, None, 'inout'),
    (9.0, 172.0, -6.0, 16.0, v(f=1.0, u=0.3, r=-0.2), 'inout'),     # rod back over the shoulder
    (13.0, 184.0, -8.0, 19.0, v(f=1.0, u=0.4, r=-0.2), 'inout'),    # load
    (16.0, 52.0, 8.0, -4.0, v(f=0.2, u=-1.0, r=-0.2), 'in'),        # flick
    (19.0, 22.0, 9.0, -6.0, v(f=-0.3, u=-1.0, r=-0.2), 'out'),      # follow through
    (30.0, None, 4.0, -6.0, None, 'inout'),                         # into the patient hold
]
for t, angle, pitch, yaw, ld, ease in CAST:
    fr = F_(qt=bend(pitch, yaw))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, rod_q(fr, FISH_TIP) if angle is None else rod_swing(fr, angle), ease=ease)
    c.key('left_arm', t, (reach('left_arm', fr, HIP_L, palm=R) if t == 30 else relaxed(fr, 'left_arm')) if ld is None else free(fr, ld), ease=ease)
    c.key('head', t, head_at(fr, np.array([-4.0, 40.0, -44.0])), ease=ease)
c.mode('head', 'set')
legs(c, 0, rx=2, lx=-3, rz=3, lz=-3)
legs(c, 13, rx=-2, lx=-9, rz=3, lz=-3)
legs(c, 16, rx=6, lx=-6, rz=3, lz=-3, ease='in')
legs(c, 30, rx=4, lx=-6, rz=3, lz=-3)

# ---- reel: a bite (the tip nods), the rod yanked up (tick 10), the line drawn in hand over hand, the rod lowered
c = clip('reel', 40, False, look=('fisher', 2), tools_=('rod', 'line'), station_='water',
         traces=[('rod', ROD_TIP, (230, 230, 200)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
         events=[('fisher.reel', 10)], views=((40, -14, True), (90, -2)))
REEL = [  # (t, tip or arm raise, pitch, left hand target / None, ease)
    (0.0, FISH_TIP, 4.0, 'hip', 'inout'),
    (3.0, FISH_TIP + np.array([0, 2.5, 0]), 5.0, 'hip', 'in'),       # a bite
    (5.0, FISH_TIP, 4.0, 'hip', 'out'),
    (7.5, FISH_TIP + np.array([0, 3.0, 1.0]), 6.0, 'hip', 'in'),
    (10.0, 66.0, -9.0, 'hip', 'out'),                                 # yank! (the tip stays out over the water)
    (13.0, 60.0, -5.0, 'up', 'inout'),                                # hand over hand
    (16.0, 58.0, -4.0, 'down', 'inout'),
    (19.0, 57.0, -4.0, 'up', 'inout'),
    (22.0, 56.0, -3.0, 'down', 'inout'),
    (25.0, 55.0, -3.0, 'up', 'inout'),
    (28.0, 53.0, -2.0, 'down', 'inout'),
    (34.0, 42.0, 0.0, 'hip', 'inout'),
    (40.0, 32.0, 0.0, None, 'inout'),
]
for t, rod, pitch, lh, ease in REEL:
    fr = F_(qt=bend(pitch, -6.0))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, rod_q(fr, rod) if isinstance(rod, np.ndarray) else rod_swing(fr, rod), ease=ease)
    if lh == 'hip':
        lq = reach('left_arm', fr, HIP_L, palm=R)
    elif lh == 'up':
        lq = free(fr, v(f=0.9, u=0.6, r=0.35), palm=R)
    elif lh == 'down':
        lq = free(fr, v(f=0.2, u=-1.0, r=0.1), palm=B)
    else:
        lq = relaxed(fr, 'left_arm')
    c.key('left_arm', t, lq, ease=ease)
    c.key('head', t, head_at(fr, np.array([-4.0, 40.0 if t < 30 else 30.0, -44.0])), ease=ease)
c.mode('head', 'set')
legs(c, 0, rx=4, lx=-6, rz=3, lz=-3)
legs(c, 10, rx=-4, lx=-8, rz=3, lz=-3, ease='out')
legs(c, 40, rx=2, lx=-3, rz=3, lz=-3)
for _t in ticks(0, 40, 2):
    _tip = fk(c, _t, 'rod', ROD_TIP)
    check(c, f'rod tip out over the water (t={_t:g})', _tip, (_tip[0], _tip[1], min(_tip[2], -10.0)), tol=0.01)


# ================================================================== the children

def wave_keys(c, bone, times, fn, ease='inout'):
    """Keys [bone] at [times] with fn(t) (a rotation, or a rotation and an offset)."""
    for t in times:
        r = fn(t)
        if isinstance(r, tuple):
            c.key(bone, t, r[0], pos=r[1], ease=ease)
        else:
            c.key(bone, t, r, ease=ease)


# ---- play (overlay): running about with the arms out like wings, banking into the turns, flapping
c = clip('play', 16, True, look=('child', 0), overlay=True,
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
for t in ticks(0, 16, 2):
    ph = 2 * math.pi * t / 16.0
    roll = 11.0 * math.sin(ph)
    flap = 0.32 * math.sin(2 * ph)
    fr = F_(qt=bend(9.0, 6.0 * math.sin(ph), roll))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, free(fr, v(f=0.3, u=0.12 + flap, r=1.0), D), ease='inout')
    c.key('left_arm', t, free(fr, v(f=0.3, u=0.12 + flap, r=-1.0), D), ease='inout')
    c.key('head', t, qeuler(x=-8.0, z=-0.6 * roll), ease='inout')
c.mode('head', 'add')
seal(c)

# ---- jump: crouch, swing the arms and spring (the root ~6 px up), arms high at the top, land and soak it up
c = clip('jump', 16, False, look=('child', 1), traces=[('right_leg', (0, 12, 0), (255, 220, 160))])
JUMP = [  # (t, root y, pitch, arms (direction), palm, legs ('stand' or (rx, lx)), ease)
    (0.0, 0.0, 0.0, None, None, 'stand', 'inout'),
    (3.0, 1.6, 14.0, v(f=-0.5, u=-1.0), F, 'stand', 'inout'),          # crouch, arms back
    (4.5, -0.4, -2.0, v(f=0.9, u=0.2), D, 'stand', 'in'),              # spring
    (8.0, -6.0, -6.0, v(f=0.35, u=1.0, r=0.45), F, (14.0, -10.0), 'out'),   # the top: arms up, a little tuck
    (11.5, 0.0, 2.0, v(f=0.5, u=0.4, r=0.5), F, 'stand', 'in'),        # land
    (12.5, 1.4, 12.0, v(f=0.8, u=-0.3, r=0.35), D, 'stand', 'out'),    # soak it up
    (16.0, 0.0, 0.0, None, None, 'stand', 'inout'),
]
for t, y, pitch, d, palm, lg, ease in JUMP:
    fr = F_(qt=bend(pitch), pr=(0, y, 0))
    key_frame(c, t, fr, ease=ease)
    if d is None:
        c.key('right_arm', t, relaxed(fr), ease=ease)
        c.key('left_arm', t, relaxed(fr, 'left_arm'), ease=ease)
    else:
        c.key('right_arm', t, free(fr, d, palm), ease=ease)
        c.key('left_arm', t, free(fr, mirror_dir(d), palm), ease=ease)
    c.key('head', t, qeuler(x=-0.6 * pitch - (10.0 if y < -3 else 0.0)), ease=ease)
    if lg == 'stand':
        stand_legs(c, t, root=fr['pr'], right=(2.6 if y > 0.5 else 0.3, 0.6), left=(2.6 if y > 0.5 else 0.3, -0.6), ease=ease)
    else:
        legs(c, t, rx=lg[0], lx=lg[1], rz=4.0, lz=-4.0, ease=ease)
c.mode('head', 'add')

# ---- spin: a little wind-up the other way, then a full twirl on the spot (root yaw), arms flung out, a bob
c = clip('spin', 24, False, look=('child', 2), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
SPIN = [(0.0, 0.0, 0.0, 0.0, 'inout'), (3.5, -28.0, 0.6, 0.2, 'inout'), (7.0, 50.0, -0.6, 0.7, 'in'),
        (10.0, 150.0, -1.2, 1.0, 'linear'), (13.0, 250.0, -1.0, 1.0, 'linear'), (16.5, 345.0, -0.4, 0.8, 'out'),
        (19.5, 368.0, 0.4, 0.4, 'inout'), (24.0, 360.0, 0.0, 0.0, 'inout')]
for t, yaw, lift, arms, ease in SPIN:
    fr = F_(qt=bend(-4.0 * arms, 0.0, 0.0), pr=(0, lift, 0), qr=qeuler(y=yaw))
    key_frame(c, t, fr, ease=ease)
    if arms <= 0.05:
        c.key('right_arm', t, relaxed(fr), ease=ease)
        c.key('left_arm', t, relaxed(fr, 'left_arm'), ease=ease)
    else:
        c.key('right_arm', t, qslerp(relaxed(), free(F_(), v(f=-0.15, u=0.25, r=1.0), F), arms), ease=ease)
        c.key('left_arm', t, qslerp(relaxed(side='left_arm'), free(F_(), v(f=-0.15, u=0.25, r=-1.0), F), arms), ease=ease)
    c.key('head', t, qeuler(x=-12.0 * arms, z=6.0 * arms), ease=ease)
    legs(c, t, rz=3.0 * arms, lz=-3.0 * arms, ease=ease)
c.mode('head', 'add')

# ---- wave: the right arm up high, the hand waving side to side (three times), down again
c = clip('wave', 30, False, look=('child', 3), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
WAVE = [(0.0, None, 'inout')] + [(5.0, 0.0, 'out')] + [(5.0 + 3.0 * i, (-1) ** i, 'inout') for i in range(1, 7)] + \
       [(26.0, 0.3, 'inout'), (30.0, None, 'inout')]
for t, sway, ease in WAVE:
    up = sway is not None
    fr = F_(qt=bend(-2.0 if up else 0.0, 0.0, -2.5 if up else 0.0))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, relaxed(fr) if not up else free(fr, v(f=0.3, u=0.9, r=0.55 + 0.42 * sway), F), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=4.0, fwd=1.0), ease=ease)
    c.key('head', t, qeuler(x=-4.0 if up else 0.0, z=(4.0 * sway) if up else 0.0), ease=ease)
c.mode('head', 'add')
for t in (0.0, 30.0):
    legs(c, t, rz=1.0, lz=-1.0)

# ---- clap: hands apart and together, three claps (ticks 6, 12, 18), a happy bounce
c = clip('clap', 24, False, look=('child', 4), events=[('clap', 6), ('clap', 12), ('clap', 18)],
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
CLAP_AT = np.array([0.0, 6.4, -8.6])
CLAPS = [(0.0, None, 'inout'), (3.0, 'open', 'out'), (6.0, 'shut', 'in'), (9.0, 'open', 'out'), (12.0, 'shut', 'in'),
         (15.0, 'open', 'out'), (18.0, 'shut', 'in'), (20.5, 'open', 'out'), (24.0, None, 'inout')]
for t, hands, ease in CLAPS:
    fr = F_(qt=bend(3.0 if hands == 'shut' else 0.0), pr=(0, 0.6 if hands == 'shut' else 0.0, 0))
    key_frame(c, t, fr, ease=ease)
    if hands is None:
        qa, qb = relaxed(fr), relaxed(fr, 'left_arm')
    else:
        gap = 2.0 if hands == 'shut' else 6.0
        up = 0.0 if hands == 'shut' else -1.0
        qa = reach('right_arm', fr, CLAP_AT + np.array([-gap, up, 1.0 if hands == 'open' else 0.0]), palm=L, palm_w=0.6)
        qb = reach('left_arm', fr, CLAP_AT + np.array([gap, up, 1.0 if hands == 'open' else 0.0]), palm=R, palm_w=0.6)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, qeuler(x=-6.0 if hands else 0.0, z=3.0 if hands == 'shut' else 0.0), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(2.4 if hands == 'shut' else 0.3, 0.4), left=(2.4 if hands == 'shut' else 0.3, -0.4), ease=ease)
c.mode('head', 'add')
for _t in (6, 12, 18):
    _r, _l = fk(c, _t, 'right_arm', GRIP['right_arm']), fk(c, _t, 'left_arm', GRIP['left_arm'])
    check(c, f'hands meet (t={_t})', _r, _l + np.array([-4.0, 0.0, 0.0]), tol=0.6)


# ================================================================== nobles and talk in the street

# ---- stroll (overlay): upright, chest out, chin up, hands clasped behind the back, an easy sway
c = clip('stroll', 40, True, look=('noble', 0), tools_=('cane',), overlay=True,
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in ticks(0, 40, 5):
    ph = 2 * math.pi * t / 40.0
    fr = F_(qt=bend(-3.0 + 0.5 * math.sin(2 * ph), 2.0 * math.sin(ph), 1.2 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, reach('right_arm', fr, np.array([-1.9, 10.6, 4.3]) + np.array([0, 0, 0.3 * math.sin(2 * ph)]), palm=L, palm_w=0.3), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, np.array([1.9, 10.6, 4.3]) + np.array([0, 0, 0.3 * math.sin(2 * ph)]), palm=R, palm_w=0.3), ease='inout')
    c.key('head', t, qeuler(x=-9.0, y=9.0 * math.sin(ph + 0.6), z=-1.0 * math.sin(ph)), ease='inout')
c.mode('head', 'add')
seal(c)

# ---- admire: weight on the cane, the other hand at the chin, a slow tilt of the head, a thoughtful nod
c = clip('admire', 100, True, look=('noble', 2), tools_=('cane', 'fan'),
         traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
CHIN = np.array([1.6, -0.6, -8.6])
for t in ticks(0, 100, 10):
    ph = 2 * math.pi * t / 100.0
    fr = F_(qt=bend(-2.5 + 0.6 * math.sin(2 * ph), -6.0, 0.8 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, rel(fr, qmul(qeuler(z=4.0), qeuler(x=-9.0 - 0.8 * math.sin(2 * ph)))), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, CHIN + np.array([0.0, 0.25 * math.sin(3 * ph), 0.0]), palm=v(u=1.0, f=-0.3), palm_w=0.4),
          ease='inout')
    c.key('head', t, qeuler(x=-4.0 + 3.0 * max(0.0, math.sin(2 * ph - 1.0)), y=-4.0, z=7.0 * math.sin(ph)), ease='inout')
c.mode('head', 'add')
for t in (0.0, 100.0):
    legs(c, t, rx=-4.0, lx=4.0, rz=2.0, lz=-3.0)
seal(c)

# ---- talk: open hands, a point, a shrug of agreement, nods
c = clip('talk', 60, True, look=('citizen', 0),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
TALK = [  # (t, right (direction, palm) or None, left, pitch, yaw, head (pitch, yaw, roll), ease)
    (0.0, None, None, 0.0, 0.0, (0, 0, 0), 'inout'),
    (6.0, (v(f=1.0, u=-0.45, r=0.45), U), None, 2.0, -4.0, (4, 0, 3), 'out'),          # an open hand
    (12.0, (v(f=1.0, u=-0.25, r=0.3), U), None, 3.0, -6.0, (8, 0, 3), 'inout'),         # ...and a nod
    (18.0, None, None, 0.0, 0.0, (0, 0, 0), 'inout'),
    (25.0, (v(f=1.0, u=0.05, r=0.15), L), None, 1.0, -10.0, (-2, -6, 0), 'out'),       # a point: there
    (31.0, (v(f=1.0, u=0.08, r=0.2), L), None, 1.0, -10.0, (6, -6, 0), 'inout'),
    (37.0, None, None, 0.0, 0.0, (0, 0, 0), 'inout'),
    (43.0, (v(f=1.0, u=-0.4, r=0.6), U), (v(f=1.0, u=-0.4, r=-0.6), U), -2.0, 0.0, (-4, 0, -4), 'out'),   # both hands open
    (49.0, (v(f=1.0, u=-0.5, r=0.5), U), (v(f=1.0, u=-0.5, r=-0.5), U), 0.0, 0.0, (8, 0, 0), 'inout'),
    (54.0, None, None, 0.0, 0.0, (2, 0, 0), 'inout'),
    (60.0, None, None, 0.0, 0.0, (0, 0, 0), 'inout'),
]
for t, ra, la, pitch, yaw, hd, ease in TALK:
    fr = F_(qt=bend(pitch, yaw))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, relaxed(fr, out=3.0, fwd=6.0) if ra is None else free(fr, *ra), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=3.0, fwd=6.0) if la is None else free(fr, *la), ease=ease)
    c.key('head', t, qeuler(x=hd[0], y=hd[1], z=hd[2]), ease=ease)
c.mode('head', 'add')
for t in (0.0, 60.0):
    legs(c, t, rx=-2.0, lx=2.0, rz=1.5, lz=-1.5)
seal(c)

HIP_R = np.array([-4.6, 10.6, -1.0])
HIP_LL = np.array([4.6, 10.6, -1.0])


def hands_on_hips(fr):
    return (reach('right_arm', fr, HIP_R, palm=L, palm_w=0.3), reach('left_arm', fr, HIP_LL, palm=R, palm_w=0.3))


# ---- talk_b: hands on the hips, a big shrug, a dismissive wave of the hand
c = clip('talk_b', 50, True, look=('citizen', 1),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
TB = [  # (t, pose, pitch, yaw, roll, head, ease)
    (0.0, 'hips', 0.0, 0.0, 0.0, (0, 0, 0), 'inout'),
    (6.0, 'hips', -1.0, 0.0, 0.0, (-3, 0, 5), 'inout'),
    (11.0, 'shrug', -4.0, 0.0, 0.0, (-6, 0, -9), 'out'),        # who knows?
    (16.0, 'shrug', -3.0, 0.0, 0.0, (-4, 0, -6), 'inout'),
    (21.0, 'hips', 0.0, 0.0, 0.0, (0, 0, 0), 'inout'),
    (30.0, 'hips', 0.0, 4.0, 0.0, (2, -8, 0), 'inout'),
    (34.0, 'wave_in', 1.0, -8.0, 0.0, (2, 10, 0), 'inout'),     # pah!
    (37.0, 'wave_out', 0.0, -12.0, 0.0, (-2, 14, 4), 'out'),
    (43.0, 'hips', 0.0, 0.0, 0.0, (0, 0, 0), 'inout'),
    (50.0, 'hips', 0.0, 0.0, 0.0, (0, 0, 0), 'inout'),
]
for t, pose, pitch, yaw, roll, hd, ease in TB:
    fr = F_(qt=bend(pitch, yaw, roll))
    key_frame(c, t, fr, ease=ease, root=False)
    qa, qb = hands_on_hips(fr)
    if pose == 'shrug':
        qa = free(fr, v(f=0.7, u=-0.55, r=0.75), U)
        qb = free(fr, v(f=0.7, u=-0.55, r=-0.75), U)
    elif pose == 'wave_in':
        qa = free(fr, v(f=0.8, u=0.1, r=-0.35), L)
    elif pose == 'wave_out':
        qa = free(fr, v(f=0.6, u=-0.2, r=1.0), F)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, qeuler(x=hd[0], y=hd[1], z=hd[2]), ease=ease)
c.mode('head', 'add')
for t in (0.0, 50.0):
    legs(c, t, rx=-1.0, lx=1.0, rz=3.0, lz=-3.0)
seal(c)

# ---- listen: arms crossed, weight on one leg, slow nods
c = clip('listen', 70, True, look=('citizen', 2),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in ticks(0, 70, 10):
    ph = 2 * math.pi * t / 70.0
    fr = F_(qt=bend(-1.5 + 0.5 * math.sin(2 * ph), 0.0, 1.5 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, reach('right_arm', fr, np.array([2.4, 7.2, -4.8]), palm=B, palm_w=0.3), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, np.array([-2.4, 6.2, -5.4]), palm=B, palm_w=0.3), ease='inout')
NODS = [(0.0, 0.0), (8.0, 0.0), (11.0, 9.0), (14.0, 1.0), (17.0, 8.0), (21.0, 0.0), (36.0, 0.0), (40.0, 7.0),
        (44.0, 0.0), (55.0, 0.0), (58.0, 10.0), (62.0, 0.0), (70.0, 0.0)]
for t, nod in NODS:
    c.key('head', t, qeuler(x=nod, z=3.0 * math.sin(2 * math.pi * t / 70.0)), ease='inout')
c.mode('head', 'add')
for t in ticks(0, 70, 35):
    ph = 2 * math.pi * t / 70.0
    legs(c, t, rx=-1.0, lx=1.0, rz=2.5, lz=-2.5)
seal(c)

# ---- laugh: a lean back, a hand on the belly, the shoulders shaking with it, back to standing
c = clip('laugh', 30, False, look=('citizen', 3),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
BELLY = np.array([-1.4, 9.6, -4.0])
for t in [0.0, 4.0] + ticks(6.0, 22.0, 2.0) + [26.0, 30.0]:
    k = 0.0 if t in (0.0, 30.0) else (0.6 if t in (4.0, 26.0) else 1.0)
    shake = 0.0 if k < 1.0 else (1.0 if int(round(t)) % 4 == 2 else -1.0)
    fr = F_(qt=bend(-11.0 * k + 1.6 * shake, 0.0, 1.0 * shake))
    key_frame(c, t, fr, root=False, ease='inout')
    if k == 0.0:
        qa, qb = relaxed(fr), relaxed(fr, 'left_arm')
    else:
        qa = qslerp(relaxed(fr), reach('right_arm', fr, BELLY, palm=B, palm_w=0.4), k)
        qb = qslerp(relaxed(fr, 'left_arm'), free(fr, v(f=0.5, u=-1.0, r=-0.55), F), k)
    c.key('right_arm', t, qa, ease='inout')
    c.key('left_arm', t, qb, ease='inout')
    c.key('head', t, qeuler(x=-14.0 * k + 2.0 * shake, z=3.0 * shake), ease='inout')
c.mode('head', 'add')
for t in (0.0, 30.0):
    legs(c, t, rz=1.5, lz=-1.5)

# ---- idle (overlay): breathing, a slow weight shift, the arms easing a little ('add' over the walk)
c = clip('idle', 80, True, look=('citizen', 4), overlay=True)
for t in ticks(0, 80, 10):
    ph = 2 * math.pi * t / 80.0
    breath = math.sin(2 * ph)
    c.key('torso', t, bend(-0.6 * breath, 1.2 * math.sin(ph), 0.9 * math.sin(ph)), ease='inout')
    c.key('right_arm', t, qeuler(x=-1.0 * breath, z=1.5 + 1.0 * breath), ease='inout')
    c.key('left_arm', t, qeuler(x=1.0 * breath, z=-1.5 - 1.0 * breath), ease='inout')
    c.key('head', t, qeuler(x=0.8 * breath, z=-1.2 * math.sin(ph)), ease='inout')
c.mode('right_arm', 'add')
c.mode('left_arm', 'add')
c.mode('head', 'add')
seal(c)


# ================================================================== the tavern

MUG_MID = (0.0, -0.5, -3.25)           # mug part space: the middle of its body
MUG_RIM = (0.0, -2.5, -3.25)           # the middle of its open top
MUG_UP = (0.0, -1.0, 0.0)
LIPS = np.array([0.0, -1.3, -5.2])     # head space: just in front of the mouth, where a rim meets it
JUG_SPOUT = (0.0, -3.7, -7.2)          # jug part space: the spout's lip
JUG_UP = (0.0, -1.0, 0.0)


def mug_hold(fr, at=(-3.0, 6.0, -8.5), tip=0.0, guess=None):
    """The right arm holding the mug upright (or tipped [tip] degrees towards the body) with its middle at [at]."""
    up = qmat(qaxis((1, 0, 0), tip)) @ U
    q, _ = solve_arm('right_arm', fr, [(tool_point('mug', MUG_MID), np.asarray(at, float), 1.0)],
                     [(tool_dir('mug', MUG_UP), up, 0.8)], guess=guess)
    return q


def mug_sip(fr, q_head):
    """The right arm with the mug tipped up towards the face and its rim's edge at the lips: (key, shoulder offset).
    A straight arm can't bring a rim that close to its own shoulder, so the shoulder rolls back a touch (the arm's
    track offset, about 1.5 px)."""
    lips = head_point(fr, q_head, LIPS)

    def res(x):
        q, off = rvec_q(x[:3]), x[3:6]
        Rp, joint = chain_frame('right_arm', arm_pos=off, **fr)
        Ra = Rp @ qmat(q)
        rim = joint + Ra @ tool_point('mug', MUG_RIM)
        up = Ra @ tool_dir('mug', MUG_UP)
        d = lips - rim
        along = float(np.dot(d, up))
        out = [along * 2.0, (np.linalg.norm(d - along * up) - 1.3) * 2.0]
        out += list((up - unit(v(f=-1.0, u=0.45))) * 0.6)
        out += list(off * 1.0)
        return out
    x = fit(res, [-2.0, 0.3, -0.3, 0.0, 0.0, 0.0], starts=[[-2.4, -0.4, 0.4, 0, 0, 0], [-1.6, 0.6, 0.0, 0, 0, 0]])
    return rvec_q(x[:3]), x[3:6]


def rim_gap(c, t):
    """How far the lips are from the mug's rim (its circle) at [t]."""
    fr = frame_at(c, t)
    lips = head_point(fr, c.sample('head', t)[0], LIPS)
    rim = fk(c, t, 'mug', MUG_RIM)
    up = unit(fk(c, t, 'mug', MUG_RIM) - fk(c, t, 'mug', (0.0, 0.0, -3.25)))
    d = lips - rim
    along = float(np.dot(d, up))
    across = d - along * up
    return math.hypot(along, max(0.0, np.linalg.norm(across) - 1.5))


def drink_clip(name, look, seated):
    """drink and sit_drink: the mug held at the chest, raised to the lips for a long sip (tick 38), lowered."""
    c = clip(name, 80, True, look=look, tools_=('mug',), station_='seat' if seated else None,
             traces=[('mug', MUG_RIM, (240, 210, 120))], events=[('tavern.sip', 38)])
    drop = 9.6 if seated else 0.0
    hold = np.array([-3.0, 6.0 + drop, -8.5])
    KEYS = [  # (t, 'hold' / 'up' / 'sip', pitch, head pitch, ease)
        (0.0, 'hold', 0.0, 0.0, 'inout'), (14.0, 'hold', 0.5, 2.0, 'inout'), (26.0, 'hold', 0.0, 0.0, 'inout'),
        (32.0, 'up', -2.0, -6.0, 'inout'), (38.0, 'sip', -4.0, -18.0, 'out'), (44.0, 'sip', -5.0, -22.0, 'inout'),
        (50.0, 'up', -1.0, -4.0, 'inout'), (58.0, 'hold', 0.5, 3.0, 'inout'), (68.0, 'hold', 0.0, 1.0, 'inout'),
        (80.0, 'hold', 0.0, 0.0, 'inout')]
    for t, pose, pitch, hp, ease in KEYS:
        fr = F_(qt=bend(pitch, 0.0, 0.0), pr=(0, drop, 0))
        key_frame(c, t, fr, ease=ease, root=seated)
        qh = qeuler(x=hp)
        off = np.zeros(3)
        if pose == 'hold':
            qa = mug_hold(fr, hold + np.array([0.0, 0.2 * math.sin(t / 7.0), 0.0]))
        elif pose == 'up':
            qa = mug_hold(fr, hold + np.array([0.5, -6.0, 2.5]), tip=35.0)
        else:
            qa, off = mug_sip(fr, qh)
        c.key('right_arm', t, qa, pos=off, ease=ease)
        c.key('left_arm', t, knee_hand(fr, 'left_arm') if seated else relaxed(fr, 'left_arm', out=3.0, fwd=3.0), ease=ease)
        c.key('head', t, qh, ease=ease)
        if seated:
            seat_legs(c, t, ease=ease)
    c.mode('head', 'set')
    if not seated:
        for t in (0.0, 80.0):
            legs(c, t, rx=-1.0, lx=1.0, rz=2.0, lz=-2.0)
    seal(c)
    check(c, 'rim at the lips (t=38, gap)', (rim_gap(c, 38), 0, 0), (0, 0, 0))
    return c


SEAT_DROP = 9.6


def seat_legs(c, t, ease='inout', spread=10.0, lift=0.0):
    """Seated legs: straight out in front over the step's edge, a little apart."""
    c.key('right_leg', t, qeuler(x=-86.0 - lift, y=spread), ease=ease)
    c.key('left_leg', t, qeuler(x=-86.0 - lift, y=-spread), ease=ease)


def knee_hand(fr, side):
    """A hand resting on its thigh, seated."""
    s_ = -1 if side == 'right_arm' else 1
    return reach(side, fr, np.array([s_ * 3.1, 19.4, -7.0]), palm=D, palm_w=0.3)


# ---- drink: the mug at the chest, raised to the lips for a long sip (tick 38), lowered
drink_clip('drink', ('citizen', 5), seated=False)

# ---- cheer: the mug lowered, then flung up high to clink with the others (tick 12), a shake, back down
c = clip('cheer', 30, False, look=('citizen', 6), tools_=('mug',), traces=[('mug', MUG_RIM, (240, 210, 120))],
         events=[('tavern.clink', 12)])
CHEER = [  # (t, mug middle, tip, pitch, roll, head pitch, ease)
    (0.0, (-3.0, 6.0, -8.5), 0.0, 0.0, 0.0, 0.0, 'inout'),
    (5.0, (-3.5, 8.0, -7.5), 0.0, 4.0, 0.0, 2.0, 'inout'),            # a dip
    (12.0, (-2.0, -12.0, -7.5), -10.0, -6.0, -3.0, -18.0, 'in'),     # up! clink
    (14.0, (-2.2, -11.0, -7.2), -6.0, -5.0, -2.0, -16.0, 'out'),
    (18.0, (-2.6, -12.5, -6.8), -12.0, -6.0, -4.0, -18.0, 'inout'),  # a hearty shake
    (22.0, (-2.4, -11.0, -7.2), -6.0, -4.0, -1.0, -12.0, 'inout'),
    (30.0, (-3.0, 6.0, -8.5), 0.0, 0.0, 0.0, 0.0, 'inout'),
]
for t, at, tip, pitch, roll, hp, ease in CHEER:
    fr = F_(qt=bend(pitch, 0.0, roll))
    key_frame(c, t, fr, ease=ease, root=False)
    c.key('right_arm', t, mug_hold(fr, at, tip), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=5.0 if t in (12.0, 14.0, 18.0) else 3.0, fwd=3.0), ease=ease)
    c.key('head', t, qeuler(x=hp, z=-0.5 * roll), ease=ease)
c.mode('head', 'add')
for t in (0.0, 30.0):
    legs(c, t, rx=-1.0, lx=1.0, rz=2.0, lz=-2.0)

# ---- sit: seated on the stair (hips down ~10 px, legs out over the step), hands on the knees, breathing
c = clip('sit', 100, True, look=('citizen', 7), station_='seat',
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in ticks(0, 100, 10):
    ph = 2 * math.pi * t / 100.0
    breath = math.sin(4 * ph)
    fr = F_(qt=bend(3.0 - 0.8 * breath, 0.0, 0.8 * math.sin(ph)), pr=(0, SEAT_DROP, 0))
    key_frame(c, t, fr)
    c.key('right_arm', t, knee_hand(fr, 'right_arm'), ease='inout')
    c.key('left_arm', t, knee_hand(fr, 'left_arm'), ease='inout')
    c.key('head', t, qeuler(x=1.0 * breath, z=-2.0 * math.sin(ph)), ease='inout')
    seat_legs(c, t, spread=10.0 + 1.5 * math.sin(ph))
c.mode('head', 'add')
seal(c)
_back = fk(c, 0, 'right_leg', (0.0, 6.0, 2.0))
check(c, 'thigh on the step (t=0)', _back, (_back[0], 24.0, _back[2]), tol=0.3)

# ---- sit_drink: seated, the mug raised for a sip (tick 38)
drink_clip('sit_drink', ('citizen', 0), seated=True)

# ---- sit_talk: seated, hands on the knees, now one hand and now the other up to make a point, nods
c = clip('sit_talk', 60, True, look=('citizen', 1), station_='seat',
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
ST = [  # (t, right gesture, left gesture, pitch, head (pitch, yaw, roll), ease)
    (0.0, None, None, 3.0, (0, 0, 0), 'inout'),
    (7.0, (v(f=1.0, u=-0.15, r=0.4), U), None, 5.0, (3, -6, 4), 'out'),
    (13.0, (v(f=1.0, u=-0.3, r=0.25), U), None, 6.0, (9, -6, 4), 'inout'),
    (19.0, None, None, 3.0, (0, 0, 0), 'inout'),
    (30.0, None, (v(f=1.0, u=-0.1, r=-0.45), U), 1.0, (-3, 8, -4), 'out'),
    (36.0, None, (v(f=1.0, u=0.15, r=-0.2), L), 2.0, (-2, 10, -3), 'inout'),
    (42.0, None, None, 3.0, (6, 0, 0), 'inout'),
    (48.0, (v(f=1.0, u=-0.35, r=0.6), U), (v(f=1.0, u=-0.35, r=-0.6), U), 0.0, (-4, 0, 0), 'out'),
    (53.0, None, None, 3.0, (4, 0, 0), 'inout'),
    (60.0, None, None, 3.0, (0, 0, 0), 'inout'),
]
for t, ra, la, pitch, hd, ease in ST:
    fr = F_(qt=bend(pitch), pr=(0, SEAT_DROP, 0))
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, knee_hand(fr, 'right_arm') if ra is None else free(fr, *ra), ease=ease)
    c.key('left_arm', t, knee_hand(fr, 'left_arm') if la is None else free(fr, *la), ease=ease)
    c.key('head', t, qeuler(x=hd[0], y=hd[1], z=hd[2]), ease=ease)
    seat_legs(c, t, ease=ease)
c.mode('head', 'add')
seal(c)

# ---- wipe: the innkeeper polishes a mug, the rag going round and round inside it
c = clip('wipe', 40, True, look=('innkeeper', 0), tools_=('mug', 'rag'), station_='bar',
         traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
WIPE_MUG = np.array([-2.0, 5.5, -9.0])
for t in ticks(0, 40, 2.5):
    ph = 2 * math.pi * t / 20.0          # twice round in the loop
    fr = F_(qt=bend(6.0, -4.0, 0.0))
    key_frame(c, t, fr, root=False)
    qa = mug_hold(fr, WIPE_MUG + np.array([0.0, 0.0, 0.0]), tip=-12.0)
    c.key('right_arm', t, qa, ease='linear')
    Ra, ja = arm_world('right_arm', qa, **fr)
    rim = ja + Ra @ tool_point('mug', MUG_RIM)
    ring = rim + np.array([1.2 * math.cos(ph), -1.6 + 0.4 * math.sin(ph), 1.2 * math.sin(ph)])
    c.key('left_arm', t, reach('left_arm', fr, ring, palm=D, palm_w=0.3), ease='linear')
    c.key('head', t, head_at(fr, WIPE_MUG + np.array([0.0, -1.0, 0.0]), extra=qeuler(z=2.0 * math.sin(ph / 2))), ease='linear')
c.mode('head', 'set')
for t in (0.0, 40.0):
    legs(c, t, rx=-1.0, lx=1.0, rz=2.0, lz=-2.0)
seal(c)

# ---- pour: the jug lifted and tipped over the mug (it pours on tick 14), held, righted, lowered
c = clip('pour', 40, False, look=('innkeeper', 1), tools_=('mug', 'jug'), station_='bar',
         traces=[('jug', JUG_SPOUT, (230, 180, 140))], events=[('tavern.pour', 14)])
POUR_MUG = np.array([-4.0, 6.0, -12.0])        # on the bar top, held by the handle
POUR = [  # (t, jug: None (low) / 'lift' / 'tip' / 'pour', pitch, ease)
    (0.0, None, 0.0, 'inout'), (6.0, 'lift', 4.0, 'inout'), (11.0, 'tip', 6.0, 'inout'), (14.0, 'pour', 7.0, 'out'),
    (22.0, 'pour', 7.0, 'inout'), (27.0, 'tip', 6.0, 'inout'), (33.0, 'lift', 3.0, 'inout'), (40.0, None, 0.0, 'inout'),
]
prev = None
for t, jug, pitch, ease in POUR:
    if jug is None:
        fr = F_(qt=bend(pitch, 6.0, 0.0))
        qa, qb = mug_hold(fr, POUR_MUG), relaxed(fr, 'left_arm', out=3.0, fwd=8.0)
    else:
        over = {'lift': np.array([4.5, -3.5, 0.5]), 'tip': np.array([2.2, -3.0, 0.0]), 'pour': np.array([1.0, -2.2, 0.0])}[jug]
        up = {'lift': v(u=1.0, r=0.15), 'tip': v(u=0.7, r=0.8), 'pour': v(u=0.25, r=1.0)}[jug]

        def res(fr, qa, qb, over=over, up=up):
            Ra, ja = arm_world('right_arm', qa, **fr)
            Rb, jb = arm_world('left_arm', qb, **fr)
            rim = ja + Ra @ tool_point('mug', MUG_RIM)
            out = list((ja + Ra @ tool_point('mug', MUG_MID) - POUR_MUG) * 1.5)
            out += list((Ra @ tool_dir('mug', MUG_UP) - U) * 1.0)
            out += list((jb + Rb @ tool_point('jug', JUG_SPOUT) - (rim + over)) * 3.0)
            out += list((Rb @ tool_dir('jug', JUG_UP) - unit(up)) * 0.6)
            return out
        fr, qa, qb, prev = body_solve(res, pitch, 6.0, (0.0, 0.0, 0.0), guess=prev, sink0=sink_for(pitch), root_w=2.0)
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, head_at(fr, POUR_MUG + np.array([1.0, -2.0, 0.0])), ease=ease)
c.mode('head', 'set')
for t in (0.0, 40.0):
    legs(c, t, rx=-1.0, lx=1.0, rz=2.0, lz=-2.0)
_fr = frame_at(c, 14)
_rim = fk(c, 14, 'mug', MUG_RIM)
check(c, 'spout over the mug (t=14)', fk(c, 14, 'jug', JUG_SPOUT), _rim + np.array([1.0, -2.2, 0.0]), tol=0.6)


# ================================================================== the bard, the priest, chores, and the walk-alongs

def lute_point(p):
    """A point of the played lute (its own part space) in model space, at rest."""
    return tool_point('lute', p)


LUTE_STRUM = (1.0, -0.5, -2.6)         # lute part space: just off the soundboard where the strings cross the bowl
LUTE_FRET = (8.0, 0.0, 0.0)            # on the neck, where the left hand stops the strings

# ---- lute: strumming (down on the beat, up between), the left hand on the neck, the head bobbing, a foot tapping
c = clip('lute', 20, True, look=('bard', 0), tools_=('lute',),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in ticks(0, 20, 1.25):
    beat = (t % 10.0) / 10.0                     # two beats a loop
    stroke = math.cos(2 * math.pi * t / 5.0)     # four strokes: down on 0, 5, 10, 15... up between
    bob = math.cos(2 * math.pi * beat)
    fr = F_(qt=bend(2.0 + 1.2 * bob, 0.0, 1.0 * math.sin(2 * math.pi * t / 20.0)))
    key_frame(c, t, fr, root=False, ease='linear')
    strum = lute_point(LUTE_STRUM) + np.array([0.3 * stroke, -1.4 * stroke, -0.3])
    strum = HIPS + qmat(fr['qt']) @ (strum - HIPS) + fr['vt']
    c.key('right_arm', t, reach('right_arm', fr, strum, palm=B, palm_w=0.2), ease='linear')
    fret = HIPS + qmat(fr['qt']) @ (lute_point(LUTE_FRET) - HIPS) + fr['vt']
    c.key('left_arm', t, reach('left_arm', fr, fret, palm=B, palm_w=0.2, p=(1.0, 7.8, 0.0)), ease='linear')
    c.key('head', t, qeuler(x=4.0 * bob - 2.0, z=3.0 * math.sin(2 * math.pi * t / 20.0)), ease='linear')
    tap = max(0.0, math.cos(2 * math.pi * beat)) ** 3
    legs(c, t, rx=-1.0 - 7.0 * tap, lx=1.5, rz=2.0, lz=-2.0, ease='linear', rpos=(0.0, -0.6 * tap, 0.0))
c.mode('head', 'add')
seal(c)

# ---- pray: hands pressed together before the chest, head bowed, a slow sway
c = clip('pray', 120, True, look=('priest', 0), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in ticks(0, 120, 15):
    ph = 2 * math.pi * t / 120.0
    fr = F_(qt=bend(4.0 + 1.0 * math.sin(2 * ph), 0.0, 1.5 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    c.key('right_arm', t, reach('right_arm', fr, np.array([-1.95, 5.2, -8.8]), palm=L, palm_w=0.5), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, np.array([1.95, 5.2, -8.8]), palm=R, palm_w=0.5), ease='inout')
    c.key('head', t, qeuler(x=24.0 + 3.0 * math.sin(2 * ph + 1.0), z=-2.0 * math.sin(ph)), ease='inout')
c.mode('head', 'set')
for t in (0.0, 120.0):
    legs(c, t, rz=1.0, lz=-1.0)
seal(c)

# ---- sweep: both hands on the broom, sweeping across in front, a flick at each end (ticks 8 and 23)
BROOM_TIP = (0.0, 18.2, 0.0)           # broom part space: the middle of the bristles' ends
BROOM_HOLD = (0.0, 4.5, 0.0)


def broom_contacts(tip, shaft=None, w_shaft=2.0):
    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        out = list((ja + Ra @ tool_point('broom', BROOM_TIP) - np.asarray(tip, float)) * 5)
        if shaft is not None:
            out += list((Ra @ tool_dir('broom', (0, 1, 0)) - unit(shaft)) * w_shaft)
        lh = hand_point('left_arm', qb, fr)
        out += list((lh - (ja + Ra @ tool_point('broom', BROOM_HOLD))) * 2.5)
        out += [clear_of_body(fr, ja + Ra @ GRIP['right_arm']), clear_of_body(fr, lh)]
        return out
    return res


c = clip('sweep', 30, True, look=('citizen', 2), tools_=('broom',), station_='field',
         traces=[('broom', BROOM_TIP, (230, 210, 150))], events=[('sweep', 8), ('sweep', 23)])
SWEEP_R = np.array([-4.8, 23.6, -14.4])
SWEEP_L = np.array([4.8, 23.6, -14.4])
SWEEP = {  # t: (tip, shaft, yaw, ease, solve from)
    0.0: (np.array([0.0, 23.0, -13.8]), v(f=1.0, u=-1.3, r=0.1), 0.0, 'inout', None),
    8.0: (SWEEP_R, v(f=1.0, u=-1.4, r=0.45), 12.0, 'out', 0.0),
    15.5: (np.array([0.0, 22.8, -13.6]), v(f=1.0, u=-1.3, r=-0.1), 0.0, 'inout', 0.0),
    23.0: (SWEEP_L, v(f=1.0, u=-1.4, r=-0.45), -12.0, 'out', 15.5),
}
sols = {}
for t, (tip, shaft, yaw, ease, start) in SWEEP.items():
    fr, qa, qb, x = body_solve(broom_contacts(tip, shaft), 14.0, yaw, (0.0, 0.0, 1.5),
                               guess=None if start is None else sols[start][4], sink0=sink_for(14.0),
                               pitch_w=0.3, yaw_w=0.2, sink_w=3.0, root_w=2.0)
    sols[t] = (fr, qa, qb, ease, x)
sols[30.0] = sols[0.0]
for t, (fr, qa, qb, ease, x) in sorted(sols.items()):
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, qa, ease=ease)
    c.key('left_arm', t, qb, ease=ease)
    c.key('head', t, head_at(fr, np.array([0.0, 22.0, -16.0])), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(0.8, 1.5 + 2.0), left=(0.8, 1.5 - 2.5), ease=ease)
c.mode('head', 'set')
seal(c)
for _t, _w in ((8, SWEEP_R), (23, SWEEP_L)):
    check(c, f'bristles at the dust (t={_t})', fk(c, _t, 'broom', BROOM_TIP), _w, tol=0.5)

# ---- carry (overlay): the crate held in front by its sides, leaning back against the weight
c = clip('carry', 40, True, look=('citizen', 3), tools_=('crate',), overlay=True)
for t in ticks(0, 40, 10):
    ph = 2 * math.pi * t / 40.0
    fr = F_(qt=bend(-3.0 + 0.6 * math.sin(2 * ph), 0.0, 1.0 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    side = np.array([6.3, 7.6 + 0.2 * math.sin(2 * ph), -8.2])
    c.key('right_arm', t, reach('right_arm', fr, side * np.array([-1, 1, 1]), palm=L, palm_w=0.4), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, side, palm=R, palm_w=0.4), ease='inout')
    c.key('head', t, qeuler(x=-3.0, z=-1.0 * math.sin(ph)), ease='inout')
c.mode('head', 'add')
seal(c)

# ---- carry_sack (overlay): the sack on the right shoulder, the right hand up holding its neck, a lean against it
SACK_NECK = (0.0, -1.1, -5.4)          # sack part space
c = clip('carry_sack', 40, True, look=('farmer', 2), tools_=('sack',), overlay=True)
for t in ticks(0, 40, 10):
    ph = 2 * math.pi * t / 40.0
    fr = F_(qt=bend(4.0 + 0.6 * math.sin(2 * ph), 0.0, 3.0 + 1.0 * math.sin(ph)))
    key_frame(c, t, fr, root=False)
    neck = HIPS + qmat(fr['qt']) @ (tool_point('sack', SACK_NECK) - HIPS) + fr['vt']
    c.key('right_arm', t, reach('right_arm', fr, neck, palm=B, palm_w=0.3, p=(-1.0, 7.0, 0.0)), ease='inout')
    c.key('left_arm', t, qeuler(z=-3.0 - 1.0 * math.sin(ph)), ease='inout')
    c.key('head', t, qeuler(x=0.0, z=-5.0), ease='inout')
c.mode('left_arm', 'add')
c.mode('head', 'add')
seal(c)

# ---- flee (overlay): arms flung up and flailing, leaning into the run, a glance back over the shoulder
c = clip('flee', 12, True, look=('citizen', 4), overlay=True,
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
for t in ticks(0, 12, 1.5):
    ph = 2 * math.pi * t / 12.0
    fr = F_(qt=bend(12.0 + 2.0 * math.sin(2 * ph), 4.0 * math.sin(ph), 3.0 * math.sin(ph)))
    key_frame(c, t, fr, root=False, ease='linear')
    c.key('right_arm', t, free(fr, v(f=0.45 + 0.5 * math.sin(ph), u=1.0, r=0.55 + 0.25 * math.cos(2 * ph)), F), ease='linear')
    c.key('left_arm', t, free(fr, v(f=0.45 - 0.5 * math.sin(ph), u=1.0, r=-0.55 - 0.25 * math.cos(2 * ph)), F), ease='linear')
    c.key('head', t, qeuler(x=-10.0, y=25.0 * max(0.0, math.sin(ph)) ** 2, z=-3.0 * math.sin(ph)), ease='linear')
c.mode('head', 'add')
seal(c)

# ---- stagger (overlay): drunk, the torso and head lolling, arms loose, a hiccup (tick 30)
c = clip('stagger', 60, True, look=('citizen', 5), overlay=True, events=[('hiccup', 30)])
ST_KEYS = [0.0, 7.5, 15.0, 22.5, 28.0, 30.0, 32.0, 37.5, 45.0, 52.5, 60.0]
for t in ST_KEYS:
    ph = 2 * math.pi * t / 60.0
    hic = {28.0: 0.0, 30.0: 1.0, 32.0: 0.35}.get(t, 0.0)
    fr = F_(qt=bend(5.0 + 3.0 * math.sin(2 * ph + 0.5) - 7.0 * hic, 3.0 * math.sin(ph), 7.0 * math.sin(ph)))
    ease = 'in' if t == 30.0 else 'inout'
    key_frame(c, t, fr, root=False, ease=ease)
    c.key('right_arm', t, qeuler(x=-6.0 * math.sin(ph) - 10.0 * hic, z=8.0 + 4.0 * math.sin(ph + 1.0) + 6.0 * hic), ease=ease)
    c.key('left_arm', t, qeuler(x=6.0 * math.sin(ph) - 10.0 * hic, z=-8.0 + 4.0 * math.sin(ph + 1.0) - 6.0 * hic), ease=ease)
    c.key('head', t, qeuler(x=8.0 + 6.0 * math.sin(2 * ph) - 16.0 * hic, y=6.0 * math.sin(ph + 2.0), z=-12.0 * math.sin(ph + 0.6)), ease=ease)
c.mode('right_arm', 'add')
c.mode('left_arm', 'add')
c.mode('head', 'add')
seal(c)

# ---- lantern (overlay): the left arm out in front, the lantern held up to see by, peering about
c = clip('lantern', 40, True, look=('guard', 3), tools_=('lantern', 'spear'), overlay=True,
         traces=[('lantern', (0.0, 4.0, 0.0), (255, 220, 120))])
for t in ticks(0, 40, 5):
    ph = 2 * math.pi * t / 40.0
    fr = F_(qt=bend(3.0 + 0.6 * math.sin(2 * ph), -4.0 * math.sin(ph), 0.0))
    key_frame(c, t, fr, root=False)
    c.key('left_arm', t, free(fr, v(f=1.0, u=0.38 + 0.05 * math.sin(2 * ph), r=0.12 + 0.12 * math.sin(ph)), R), ease='inout')
    c.key('right_arm', t, qeuler(x=-2.0, z=2.0), ease='inout')
    c.key('head', t, qeuler(x=-2.0, y=18.0 * math.sin(ph)), ease='inout')
c.mode('right_arm', 'add')
c.mode('head', 'add')
seal(c)


if __name__ == '__main__':
    main(sys.argv)
