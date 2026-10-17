"""
The townsfolk's clips: the smith at the anvil, the baker at the oven, the merchant, the watch, the farmer, the fisher,
children at play, nobles, the tavern, the bard and the priest, and the captive in an outlaw camp (bound, plead,
freed, thank). Same skeleton and kit as the combat clips (animkit.py),
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

# The factions' stations: the library's lectern a block ahead (its desk at counter height, an open book on it)
WALL_Z = 8.0            # a wall at the edge of the mob's block, behind it
DESK_TOP = COUNTER_TOP
BOOK_COVER = ((-5.0, DESK_TOP - 0.7, -18.0), (5.0, DESK_TOP, -10.0), (120, 40, 40), True)
BOOK_PAGE_R = ((-4.6, DESK_TOP - 1.3, -17.5), (-0.3, DESK_TOP - 0.7, -10.5), (236, 224, 190), True)
BOOK_PAGE_L = ((0.3, DESK_TOP - 1.3, -17.5), (4.6, DESK_TOP - 0.7, -10.5), (236, 224, 190), True)
TABLE_TOP = 15.0        # a table in front of a seated figure (the seat's step tops at y = 24, the ground is 8 px below it)
SPAR_GAP = 32.0         # the sparring partner two blocks ahead (centre to centre)
POST_Z = -16.0          # the practice post's axis, a block ahead
DOOR_Z = -14.0          # the face of the door (or chest) the thief works at

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
    elif kind == 'lectern':
        # a lectern a block ahead: foot, post and a desk whose top is at counter height, an open book on it
        b += [((-6, 22, -22), (6, 24, -10), (96, 70, 46), True), ((-2.5, 9.5, -18), (2.5, 22, -14), (120, 88, 58), True),
              ((-8, DESK_TOP, -24), (8, DESK_TOP + 1.5, -8), (140, 100, 62), True)]
        b += [BOOK_COVER, BOOK_PAGE_R, BOOK_PAGE_L]
    elif kind == 'listener':
        # someone close at the mob's right (the whispered-to), facing the same way: legs, body and head as boxes
        b += [((-17, 12, -2), (-9, 24, 2), (74, 78, 96), True), ((-17, 0, -2), (-9, 12, 2), (110, 84, 110), True),
              ((-17, -8, -4), (-9, 0, 4), (214, 170, 136), True), ((-9.2, -5, -1), (-8.8, -2, 1.5), (160, 110, 96), True)]
    elif kind == 'wall':
        # a wall behind the mob (its face at the block's edge): the lounger leans on it
        b += [((-24, -16, WALL_Z), (24, 24, WALL_Z + 6), (104, 100, 112), True)]
    elif kind in ('desk', 'dice'):
        # the seat, and a table in front of it at TABLE_TOP: the scribe's parchment and inkwell (on the left), or the
        # dicers' felt and a pair of dice
        b += [((-8, 24, -6), (8, 32, 10), (150, 112, 70), True), ((-8, 16, 2), (8, 24, 10), (132, 98, 62), True)]
        gy = 32.0
        b += [((-12, TABLE_TOP, -27), (12, TABLE_TOP + 1.5, -7), (150, 110, 70), True)]
        for x0, z0 in ((-11.5, -26.5), (9.5, -26.5), (-11.5, -9.5), (9.5, -9.5)):
            b += [((x0, TABLE_TOP + 1.5, z0), (x0 + 2, 32, z0 + 2), (110, 80, 50), True)]
        if kind == 'desk':
            b += [((-7.5, TABLE_TOP - 0.3, -18.5), (1.0, TABLE_TOP, -8.5), (238, 228, 196), True),
                  ((3.0, TABLE_TOP - 2.4, -14.8), (6.6, TABLE_TOP, -11.2), (44, 40, 76), True),
                  ((3.7, TABLE_TOP - 2.7, -14.1), (5.9, TABLE_TOP - 2.4, -11.9), (14, 12, 30), True)]
        else:
            b += [((-7.0, TABLE_TOP - 0.3, -23.0), (7.0, TABLE_TOP, -11.0), (62, 104, 74), True),
                  ((-2.0, TABLE_TOP - 1.8, -18.0), (-0.2, TABLE_TOP - 0.3, -16.2), (240, 236, 224), True),
                  ((1.4, TABLE_TOP - 1.8, -17.0), (3.2, TABLE_TOP - 0.3, -15.2), (240, 236, 224), True)]
    elif kind == 'post':
        # a practice post a block ahead: a square post from the ground up to head height, wrapped in straw at head and chest
        b += [((-2.5, -8, POST_Z - 2.5), (2.5, 24, POST_Z + 2.5), (112, 82, 54), True),
              ((-3.0, -8, POST_Z - 3.0), (3.0, -2, POST_Z + 3.0), (194, 166, 108), True),
              ((-3.5, 1, POST_Z - 3.5), (3.5, 14, POST_Z + 3.5), (182, 154, 98), True)]
    elif kind == 'door':
        # a door a block ahead (its face at DOOR_Z) in a frame, with a lock plate and keyhole at hand height
        b += [((-10, -16, DOOR_Z - 3), (10, 24, DOOR_Z), (118, 84, 54), True),
              ((-12.5, -18, DOOR_Z - 4), (-10, 24, DOOR_Z + 1), (84, 60, 40), True),
              ((10, -18, DOOR_Z - 4), (12.5, 24, DOOR_Z + 1), (84, 60, 40), True),
              ((-12.5, -18, DOOR_Z - 4), (12.5, -15.5, DOOR_Z + 1), (84, 60, 40), True),
              ((-3.4, 6.2, DOOR_Z), (0.6, 12.2, DOOR_Z + 0.7), (68, 68, 78), True),
              ((-1.7, 8.6, DOOR_Z + 0.7), (-0.9, 10.2, DOOR_Z + 0.75), (8, 8, 12), False)]
    elif kind == 'stacks':
        # the counter a coin-counter works at, three little stacks of coins in a row on it (as they end up)
        b += [((-8, 8, -24), (8, 24, -8), (140, 100, 62), True)]
        for x0 in (-7.9, -5.7, -3.5):
            b += [((x0, 6.0, -11.6), (x0 + 2.0, 8, -9.4), (232, 184, 60), True)]
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
    turns to the character's right: the right shoulder goes back) and tipped [roll] degrees (positive tips to the
    left: the right shoulder rises)."""
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
         walking=None, views=None, partner=None, pan=None):
    c = Clip(name, length, blend_in=0.0, blend_out=0.0)
    for label, tick in events:
        c.mark(label, tick)
    INFO[name] = dict(look=look, tools=set(tools_), station=station_, overlay=overlay, traces=list(traces), loop=loop,
                      events=list(events), walking=overlay if walking is None else walking, views=views, checks=[],
                      partner=partner, pan=None if pan is None else np.asarray(pan, float))
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


def partner_place():
    """Where a sparring partner stands: SPAR_GAP ahead, facing back at the mob."""
    return T(0.0, 0.0, -SPAR_GAP) @ Rq(qeuler(y=180.0))


def partner_quads(partner, t):
    """The partner figure of a sparring preview: [partner] is the name of the clip it plays (or (name, look))."""
    name, look_key = (partner, None) if isinstance(partner, str) else partner
    pc = next(c for c in clips if c.name == name)
    look2 = look_for(look_key or INFO[name]['look'])
    pose2 = pose_at(pc, t, walk_base(t, speed=0.0, age=t))
    G = partner_place()
    mats2 = {k: G @ M for k, M in part_matrices(look2, pose2).items()}
    return model_quads(look2, mats2, INFO[name]['tools'])


def render_cell(look, c, t, project, size, walking, wire=False, solo=False):
    info = INFO[c.name]
    base = walk_base(t, speed=walk_speed(c, walking), age=t)
    pose = pose_at(c, t, base)
    mats = part_matrices(look, pose)
    quads = model_quads(look, mats, info['tools'])
    if info.get('partner') and not solo:
        quads += partner_quads(info['partner'], t)
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
            if info.get('pan') is not None:
                project = (lambda base_, pan_: (lambda p: base_(np.asarray(p, float) - pan_)))(project, info['pan'])
            img, pose = render_cell(look, c, t, project, (W, H), walking, wire=len(view) > 2 and view[2],
                                    solo=len(view) > 3 and view[3] == 'solo')
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
        show = lambda a: '(' + ', '.join(f'{x:.2f}' for x in np.atleast_1d(a)) + ')'
        lines.append(f'  {c.name} {label}: {show(got)} want {show(want)} off {err:.2f}{flag}')
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



# ================================================================== faction clips: begin
# (the mages, knights and thieves of the three factions; see the clips below)

# ---- helpers for the faction clips

def level_arms(fr, qa, qb, w=0.6):
    """Residuals keeping both arms' widths level (no rolled forearms) in a pose solved for hands on something."""
    return [arm_dir('right_arm', qa, fr, (1, 0, 0))[1] * w, arm_dir('left_arm', qb, fr, (1, 0, 0))[1] * w]


def hands_at(rh, lh, w=4.0, level=0.6):
    """Contacts for body_solve: the right fist at [rh] and the left at [lh] (world points; None leaves one free)."""
    def res(fr, qa, qb):
        out = []
        if rh is not None:
            out += list((hand_point('right_arm', qa, fr) - np.asarray(rh, float)) * w)
        if lh is not None:
            out += list((hand_point('left_arm', qb, fr) - np.asarray(lh, float)) * w)
        return out + level_arms(fr, qa, qb, level)
    return res


def gaze_head(fr, target, extra=I, iters=24):
    """A 'set' head key whose eyes look at world [target] from where they really are (head_at settles in three
    passes, which is too few for a target close under the face: a book, a coin stack). [extra] tilts it further."""
    qr, qt = fr.get('qr', I), fr.get('qt', I)
    vt, pr = fr.get('vt', np.zeros(3)), fr.get('pr', np.zeros(3))
    pivot = HIPS + qmat(qr) @ (HIPS + qmat(qt) @ (np.array(PIVOTS['head'], float) - HIPS) + vt - HIPS) + pr
    qw = qmul(qr, qt)
    for _ in range(iters):
        eyes = pivot + qmat(qw) @ EYES
        qw = qslerp(qw, qmul(look_q(np.asarray(target, float) - eyes), extra), 0.5)
    return qmul(qinv(qmul(qr, qt)), qw)


def gaze_miss(fr, q_head, target):
    """Degrees between where a 'set' head key looks and the direction from its eyes to [target]."""
    eyes = head_point(fr, q_head, EYES)
    fwd = head_point(fr, q_head, np.array([0.0, 0.0, -1.0])) - head_point(fr, q_head, np.zeros(3))
    d = unit(np.asarray(target, float) - eyes)
    return math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(unit(fwd), d))))))


def stand_feet(c, t, fr, right=(0.4, 1.0), left=(0.4, -1.0), ease='inout'):
    """Both feet flat under the hips ([right] and [left]: x outward, z from the root's own place), however the body
    has stepped in or sunk: the legs only lean when the hips are away from the feet's own stance."""
    dz = float(fr['pr'][2])
    stand_legs(c, t, root=fr['pr'], right=(right[0], right[1] + dz), left=(left[0], left[1] + dz), ease=ease)


def solve_arm_slack(side, fr, points=(), dirs=(), start=None, slack=1.0, limit=1.6):
    """Like solve_arm, but the shoulder may roll a little too (the arm key's own offset, [slack] weighing against it,
    about 1.5 px at most): a rigid arm cannot put a point that is always 13 px from the shoulder where a writing
    hand needs it as the hand travels. Returns (key, offset)."""
    def res(x):
        q, off = rvec_q(x[:3]), x[3:6]
        Rp, joint = chain_frame(side, arm_pos=off, **fr)
        Ra = Rp @ qmat(q)
        out = []
        for p, tgt, w in points:
            out.extend((joint + Ra @ np.asarray(p, float) - np.asarray(tgt, float)) * w)
        for d, dw, w in dirs:
            out.extend((Ra @ unit(d) - unit(dw)) * w)
        out.extend(off * slack)
        n = float(np.linalg.norm(off))
        if n > limit:
            out.extend(off / n * (n - limit) * 6.0)
        return out
    s0 = [list(q_rvec(start)) + [0.0, 0.0, 0.0]] if start is not None else []
    x = fit(res, s0[0] if s0 else [-1.5, 0.3, -0.3, 0.0, 0.0, 0.0],
            starts=[] if s0 else [[-2.0, 0.0, 0.0, 0, 0, 0], [-1.0, 0.5, 0.2, 0, 0, 0], [-1.0, -0.5, -0.2, 0, 0, 0]])
    return rvec_q(x[:3]), x[3:6]


def interp_keys(keys, t):
    """A point moved along [keys] (t, point), easing between them."""
    for (ta_, pa), (tb, pb) in zip(keys, keys[1:]):
        if ta_ <= t <= tb:
            return pa + (pb - pa) * smooth((t - ta_) / (tb - ta_))
    return keys[0][1]


def spline_keys(keys, t, period=None):
    """A point (or number) moved smoothly through [keys] (t, value): a Catmull-Rom spline, so the motion carries its
    velocity through every key instead of stopping at each. [period]: the keys repeat every [period] ticks (the last
    key is the first), so the loop has no seam."""
    ks = [(float(a), np.asarray(b, float)) for a, b in keys]
    if period is not None:
        ks = [(ks[-2][0] - period, ks[-2][1])] + ks + [(ks[1][0] + period, ks[1][1])]
    else:
        ks = [ks[0]] + ks + [ks[-1]]
    for i in range(1, len(ks) - 2):
        t0, p0 = ks[i]
        t1, p1 = ks[i + 1]
        if t0 <= t <= t1 and t1 > t0:
            u = (t - t0) / (t1 - t0)
            h = t1 - t0
            m0 = (p1 - ks[i - 1][1]) / max(1e-9, t1 - ks[i - 1][0]) * h
            m1 = (ks[i + 2][1] - p0) / max(1e-9, ks[i + 2][0] - t0) * h
            u2, u3 = u * u, u * u * u
            return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1
    return ks[1][1] if t < ks[1][0] else ks[-2][1]


def torso_to_world(fr, p):
    """A point fixed to the torso (in torso space) as the clip's torso and root carry it."""
    pt = HIPS + qmat(fr['qt']) @ (np.asarray(p, float) - HIPS) + fr['vt']
    return HIPS + qmat(fr['qr']) @ (pt - HIPS) + fr['pr']


def leg_corners(side, q_leg, fr, end_only=False):
    """Where the corners of a leg box (a 'set' key [q_leg]) are, under the root of [fr]."""
    s_ = -1 if side == 'right_leg' else 1
    piv = np.array(PIVOTS[side], float) + fr['pr']
    ys = (12.0,) if end_only else (0.0, 12.0)
    return [piv + qmat(q_leg) @ np.array([x, y, z]) for x in (-2.0, 2.0) for y in ys for z in (-2.0, 2.0)]


STAFF_BUTT = np.array([-6.0, 24.0, -2.6])       # where a staff stands on the ground beside the right foot
STAFF_FOOT = (0.0, 12.0, 0.0)                   # staff part space: the bottom of its butt


def planted_staff(fr, butt=STAFF_BUTT, guess=None):
    """The right arm with the staff's butt standing on the ground at [butt] and the staff upright: the 'set' key."""
    q, _ = solve_arm('right_arm', fr, [(tool_point('staff', STAFF_FOOT), np.asarray(butt, float), 3.0)],
                     [(tool_dir('staff', (0, 1, 0)), D, 0.3)], guess=guess)
    return q


# ---- read: at a lectern, both hands at the book's edges, head bowed over the page, a page turned (88)
BOOK_RH = np.array([-5.4, 6.9, -14.0])     # the right fist at the book's right edge
BOOK_LH = np.array([5.4, 6.9, -14.0])
c = clip('read', 120, True, look=('mage', 0), station_='lectern', events=[('page', 88)],
         traces=[('right_arm', GRIP['right_arm'], (255, 230, 160)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])


def read_right(t):
    """The right fist: at the book's edge, and over the book to turn the page (80-96): corner, lift, over the spine, down."""
    path = [(80.0, BOOK_RH), (82.0, (-4.6, 6.2, -16.8)), (85.0, (-3.4, 3.6, -16.0)), (88.0, (-0.2, 2.4, -14.6)),
            (91.0, (2.8, 5.0, -14.8)), (94.0, (-0.5, 6.0, -14.0)), (96.0, BOOK_RH)]
    for (ta_, pa), (tb, pb) in zip(path, path[1:]):
        if ta_ <= t <= tb:
            a = smooth((t - ta_) / (tb - ta_))
            return np.array(pa, float) * (1 - a) + np.array(pb, float) * a
    return BOOK_RH


def read_gaze_keys():
    """Where the eyes are on the page, as keys: each line read left to right and a quick return to the next one (six
    lines a page, the left page then the right, a glance up to think, the page turned, the next page begun; the last
    key is the first)."""
    z = lambda k: -17.4 + 0.8 * k
    keys = []
    lines = [(0, 2, 'L', 10), (10, 3, 'L', 10), (20, 4, 'L', 10), (30, 5, 'L', 10), (40, 0, 'R', 10), (50, 1, 'R', 10),
             (70, 2, 'R', 10), (96, 0, 'L', 12), (108, 1, 'L', 12)]
    for t0, k, page, period in lines:
        xs, xe = (1.8, 0.6) if page == 'L' else (-0.6, -1.8)
        keys += [(t0, np.array([xs, 6.4, z(k)])), (t0 + 0.7 * period, np.array([xe, 6.4, z(k)]))]
        if t0 == 50:                       # looks up from the page, thinking, and finds the line again
            keys += [(61, np.array([-7.0, -3.0, -48.0])), (66, np.array([-6.0, -3.0, -48.0]))]
    keys.append((120, keys[0][1]))
    return keys


def read_gaze(t, keys=read_gaze_keys()):
    if 80 <= t <= 96:                      # eyes on the page going over
        return read_right(t) + np.array([0.0, 0.3, -0.8]) if t > 82 else np.array([-2.0, 6.4, -14.0])
    return interp_keys(keys, t)


prev = None
READ_T = sorted(set(ticks(0, 120, 6)) | {82.0, 85.0, 88.0, 91.0, 94.0})
for t in READ_T:
    breath = math.sin(2 * math.pi * t / 30.0)
    pitch = 11.0 + 0.8 * breath - 2.5 * bump(t, 56.0, 72.0)
    yaw = 1.5 * math.sin(2 * math.pi * t / 120.0)
    fr, qa, qb, prev = body_solve(hands_at(read_right(t), BOOK_LH), pitch, yaw, (0.0, 0.0, -4.0), guess=prev,
                                  sink0=sink_for(pitch), sink_w=1.0, root_w=1.0, pitch_w=1.0, yaw_w=0.3)
    key_frame(c, t, fr)
    c.key('right_arm', t, qa)
    c.key('left_arm', t, qb)
    stand_feet(c, t, fr)
track(c, 'head', ticks(0, 120, 2), lambda t, fr: gaze_head(fr, read_gaze(t)), ease='inout')
c.mode('head', 'set')
seal(c)
check(c, 'right fist at the book (t=0)', fk(c, 0, 'right_arm', GRIP['right_arm']), BOOK_RH, tol=0.4)
check(c, 'left fist at the book (t=0)', fk(c, 0, 'left_arm', GRIP['left_arm']), BOOK_LH, tol=0.4)
check(c, 'fist on the page corner (t=82)', fk(c, 82, 'right_arm', GRIP['right_arm']), (-4.6, 6.2, -16.8), tol=0.5)


# ---- lurk: leaning back on a wall, arms folded, one foot up against it, the head turning to watch the street
c = clip('lurk', 100, True, look=('thief', 1), station_='wall',
         traces=[('right_leg', (0.0, 12.0, 0.0), (255, 220, 160))])
LURK_PITCH = -34.0
LURK_ROOT = -3.5
LURK_FOOT = (0.6, -7.2)                 # the standing (left) foot: x outward, z


def lurk_leg():
    """The right leg swung back until its end just touches the wall (W): the angle that does it, and out a little."""
    fr0 = F_(pr=(0.0, 0.0, LURK_ROOT))
    for ax in np.arange(20.0, 80.0, 0.25):
        q = qeuler(x=ax, z=12.0)
        if max(p[2] for p in leg_corners('right_leg', q, fr0)) >= WALL_Z - 0.05:
            return q
    return qeuler(x=60.0, z=12.0)


LURK_RLEG = lurk_leg()
dz_ = LURK_FOOT[1] - LURK_ROOT
LURK_DROP = 12.0 - math.sqrt(max(0.0, 144.0 - dz_ * dz_ - LURK_FOOT[0] ** 2))
LOOK_AT = [(0.0, 0.0), (22.0, -30.0), (40.0, -30.0), (56.0, 0.0), (76.0, 26.0), (88.0, 26.0), (100.0, 0.0)]
for t in ticks(0, 100, 10):
    breath = math.sin(2 * math.pi * t / 25.0)
    sway = math.sin(2 * math.pi * t / 100.0)
    fr = F_(qt=bend(LURK_PITCH + 0.5 * breath, 0.0, 1.2 * sway), pr=(0.0, LURK_DROP, LURK_ROOT))
    key_frame(c, t, fr)
    c.key('right_arm', t, reach('right_arm', fr, torso_to_world(fr, (2.4, 7.2, -4.8)), palm=B, palm_w=0.3), ease='inout')
    c.key('left_arm', t, reach('left_arm', fr, torso_to_world(fr, (-2.4, 6.2, -5.4)), palm=B, palm_w=0.3), ease='inout')
    stand_legs(c, t, root=fr['pr'], right=(0.6, 0.0), left=LURK_FOOT)
    c.keys['right_leg'] = [k for k in c.keys['right_leg'] if k[0] != t]
    c.key('right_leg', t, LURK_RLEG, ease='inout')
for t, x in LOOK_AT:
    fr = frame_at(c, t)
    c.keys.setdefault('head', [])
    c.key('head', t, gaze_head(fr, np.array([x, -3.0 + 0.04 * abs(x), -50.0]), extra=qeuler(x=7.0)), ease='inout')
c.mode('head', 'set')
seal(c)
check(c, 'raised foot at the wall (t=0)', (max(p[2] for p in leg_corners('right_leg', c.sample('right_leg', 0)[0], frame_at(c, 0))), 0, 0), (WALL_Z, 0, 0), tol=0.3)


# ---- whisper: leaning in to someone at the right, the left hand cupped by the mouth, the head tilted in; a glance round
c = clip('whisper', 50, True, look=('thief', 2), station_='listener',
         traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
WHISPER = [  # (t, torso (pitch, yaw, roll), head (pitch, yaw, roll), ease): +yaw turns right, -roll tips right (toward the listener)
    (0.0, (4.0, 8.0, -8.0), (6.0, 30.0, -10.0), 'inout'),
    (7.0, (5.0, 8.0, -8.0), (11.0, 30.0, -10.0), 'inout'),
    (12.0, (4.0, 9.0, -8.0), (5.0, 31.0, -10.0), 'inout'),
    (18.0, (5.0, 8.0, -8.5), (10.0, 29.0, -11.0), 'inout'),
    (24.0, (3.0, 5.0, -6.0), (4.0, 22.0, -8.0), 'inout'),
    (29.0, (1.5, -4.0, -3.0), (0.0, -32.0, 2.0), 'inout'),         # a glance round the room: is anyone listening
    (35.0, (1.5, -5.0, -3.0), (0.0, -34.0, 2.0), 'inout'),
    (41.0, (3.5, 6.0, -7.0), (5.0, 28.0, -9.0), 'inout'),
    (46.0, (5.0, 8.0, -8.0), (10.0, 30.0, -10.0), 'inout'),
    (50.0, (4.0, 8.0, -8.0), (6.0, 30.0, -10.0), 'inout'),
]
MOUTH_SIDE = np.array([2.8, -1.6, -5.2])        # head space: beside the lips, on the left, where the cupped hand rests
for t, (tp, ty, tr), (hp, hy, hr), ease in WHISPER:
    fr = F_(qt=bend(tp, ty, tr))
    key_frame(c, t, fr, root=False, ease=ease)
    qh = qeuler(x=hp, y=hy, z=hr)
    c.key('head', t, qh, ease=ease)
    hq, _ = solve_arm('left_arm', fr, [(GRIP['left_arm'], head_point(fr, qh, MOUTH_SIDE), 2.0)],
                      [((0, 0, -1), v(r=1.0, u=0.3), 0.3)])
    c.key('left_arm', t, hq, ease=ease)
    c.key('right_arm', t, relaxed(fr, out=4.0, fwd=4.0), ease=ease)
    legs(c, t, rx=-1.0, lx=1.0, rz=3.0, lz=-3.0, ease=ease)
c.mode('head', 'set')
seal(c)


# ---- shout: raising the alarm: a breath in, then thrown forward, the right arm pointing at the culprit, the head up, held
c = clip('shout', 30, False, look=('knight', 1), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
POINT = v(f=1.0, u=0.1, r=0.02)
SHOUT = [  # (t, torso (pitch, yaw), root z, right arm (direction, palm) or None, left arm direction or None, head (pitch, yaw), feet, ease)
    (0.0, (0.0, 0.0), 0.0, None, None, (0.0, 0.0), ((0.3, 0.6), (0.3, -0.6)), 'inout'),
    (4.5, (-8.0, 7.0), 1.0, (v(f=0.15, u=0.7, r=0.6), F), v(f=-0.2, u=-0.4, r=-0.9), (-14.0, -7.0), ((0.3, 1.6), (0.3, 0.0)), 'out'),   # the breath in
    (8.0, (17.0, -10.0), -3.0, (POINT, L), v(f=-0.5, u=-0.55, r=-0.65), (-26.0, 10.0), ((0.5, 3.0), (0.5, -5.0)), 'in'),            # thrown forward
    (10.5, (20.0, -11.0), -3.4, (POINT, L), v(f=-0.55, u=-0.5, r=-0.65), (-30.0, 11.0), ((0.5, 3.2), (0.5, -5.2)), 'out'),
    (15.0, (16.5, -10.0), -3.2, (POINT, L), v(f=-0.45, u=-0.6, r=-0.65), (-27.0, 10.0), ((0.5, 3.0), (0.5, -5.0)), 'inout'),
    (30.0, (15.5, -10.0), -3.2, (POINT, L), v(f=-0.4, u=-0.6, r=-0.65), (-26.0, 10.0), ((0.5, 3.0), (0.5, -5.0)), 'inout'),
]
for t, (pitch, yaw), rz, ra, la, (hp, hy), (rf, lf), ease in SHOUT:
    fr = F_(qt=bend(pitch, yaw), pr=(0.0, 0.0, rz))
    key_frame(c, t, fr, ease=ease)
    c.key('right_arm', t, relaxed(fr, out=3.0, fwd=3.0) if ra is None else free(fr, *ra), ease=ease)
    c.key('left_arm', t, relaxed(fr, 'left_arm', out=3.0, fwd=3.0) if la is None else free(fr, la, F), ease=ease)
    c.key('head', t, qeuler(x=hp, y=hy), ease=ease)
    stand_legs(c, t, root=fr['pr'], right=(rf[0], rf[1] + 0.0), left=(lf[0], lf[1]), ease=ease)
c.mode('head', 'set')


# ---- search: a wary watcher standing still, the left hand on the hilt at the left hip; the head turns slowly left
# (0-16), dwells, sweeps right (20-36) and comes back (+yaw turns right)
c = clip('search', 40, True, look=('knight', 2), traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
HILT = np.array([5.3, 9.8, -2.8])         # torso space: the hilt at the left hip, forward and out a little
SEARCH = [  # (t, head yaw, ease)
    (0.0, 0.0, 'inout'), (16.0, -38.0, 'inout'), (20.0, -35.0, 'inout'), (36.0, 38.0, 'inout'), (40.0, 0.0, 'inout')]
for t, yaw, ease in SEARCH:
    breath = math.sin(2 * math.pi * t / 20.0)
    fr = F_(qt=bend(2.5 + 0.4 * breath, 0.3 * yaw, 0.0))
    key_frame(c, t, fr, root=False, ease=ease)
    c.key('left_arm', t, reach('left_arm', fr, torso_to_world(fr, HILT), palm=D, palm_w=0.2), ease=ease)
    c.key('right_arm', t, relaxed(fr, out=6.0, fwd=9.0), ease=ease)
    c.key('head', t, qeuler(x=3.0, y=yaw, z=-0.08 * yaw), ease=ease)
    legs(c, t, rx=-2.0, lx=2.0, rz=3.0, lz=-3.0, ease=ease)
c.mode('head', 'set')
seal(c)


# ---- conjure: standing, the staff planted in the right hand, the left palm up before the chest circling slowly, lifting
# with a flick at 36-44 (the motes fly at 40), settling back into the circle
c = clip('conjure', 80, True, look=('mage', 0), tools_=('staff',), events=[('motes', 40)],
         traces=[('left_arm', GRIP['left_arm'], (200, 230, 255)), ('staff', (0.0, -18.5, 0.0), (190, 140, 238))])
CONJ_C = np.array([2.6, 7.2, -8.0])             # the middle of the hand's slow circle
guess_r = guess_l = None
for t in ticks(0, 80, 2):
    ph = 2 * math.pi * t / 40.0
    lift = bump(t, 28.0, 52.0)                   # the flick: 0 -> 1 at tick 40 -> 0
    breath = math.sin(2 * math.pi * t / 40.0 + 0.6)
    fr = F_(qt=bend(3.0 + 0.5 * breath - 6.5 * lift, 5.0 + 3.0 * lift, 0.0))
    key_frame(c, t, fr, ease='linear', root=False)
    r_ = 2.3 * (1.0 - 0.75 * lift)
    mid = CONJ_C + np.array([1.4, -6.5, -2.0]) * lift
    radial = np.array([r_ * math.cos(ph), 0.0, r_ * math.sin(ph)])
    hand = mid + radial
    palm = unit(U * (1.0 - 0.8 * lift) + v(f=1.0, u=0.0) * 1.1 * lift + R * 0.12)
    guess_r = planted_staff(fr, guess=guess_r)
    guess_l = reach('left_arm', fr, hand, palm=palm, palm_w=0.2, guess=guess_l)
    c.key('right_arm', t, guess_r, ease='linear')
    c.key('left_arm', t, guess_l, ease='linear')
    above = mid + 0.25 * radial + np.array([0.0, -7.0, -1.0])      # the eyes rest on the air above the palm, where the motes will be
    gaze = above * (1.0 - smooth(lift * 1.4)) + np.array([1.0, -14.0, -28.0]) * smooth(lift * 1.4)
    c.key('head', t, gaze_head(fr, gaze), ease='linear')
c.mode('head', 'set')
for t in (0.0, 80.0):
    stand_legs(c, t, right=(0.5, 0.8), left=(0.5, -0.8))
seal(c)
for tt_ in (0, 40):
    check(c, f'staff butt on the ground (t={tt_})', fk(c, tt_, 'staff', STAFF_FOOT), STAFF_BUTT, tol=0.4)


# ---- lecture: standing, the staff planted, the left hand opening outward (an explaining gesture), then pointing; the
# head turns slowly across the audience from the left to the right and back to the left, where the hand points
c = clip('lecture', 60, True, look=('mage', 1), tools_=('staff',),
         traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
LECT_ARM = [  # (t, direction, palm)
    (0.0, v(f=0.55, u=-0.75, r=-0.15), U), (9.0, v(f=0.85, u=-0.25, r=-0.3), U), (16.0, v(f=0.45, u=-0.26, r=-0.85), U),
    (24.0, v(f=0.4, u=-0.14, r=-0.9), U), (29.0, v(f=0.8, u=-0.1, r=-0.4), U),
    (35.0, v(f=0.9, u=0.2, r=-0.38), R), (38.0, v(f=0.95, u=0.19, r=-0.26), R), (45.0, v(f=0.92, u=0.2, r=-0.3), R),
    (53.0, v(f=0.6, u=-0.7, r=-0.2), U), (60.0, v(f=0.55, u=-0.75, r=-0.15), U)]
LECT_HEAD = [(0.0, -10.0), (8.0, 8.0), (18.0, 24.0), (26.0, 4.0), (33.0, -14.0), (40.0, -27.0), (47.0, -26.0), (54.0, -14.0),
             (60.0, -10.0)]      # head yaw: negative looks left, positive right
guess_r = None
for t in ticks(0, 60, 2):
    breath = math.sin(2 * math.pi * t / 30.0)
    hy = float(interp_keys(LECT_HEAD, t))
    emph = bump(t, 30.0, 48.0)
    ty = 0.4 * hy
    fr = F_(qt=bend(1.0 + 0.4 * breath + 2.5 * emph, ty, 0.5 * math.sin(2 * math.pi * t / 60.0)))
    key_frame(c, t, fr, ease='linear', root=False)
    guess_r = planted_staff(fr, guess=guess_r)
    c.key('right_arm', t, guess_r, ease='linear')
    d = interp_keys([(a, b) for a, b, _ in LECT_ARM], t)
    pm = interp_keys([(a, b) for a, _, b in LECT_ARM], t)
    c.key('left_arm', t, free(fr, d, pm), ease='linear')
    c.key('head', t, qeuler(x=3.0 + 4.0 * bump(t, 33.0, 41.0) - 3.0 * emph, y=hy - ty, z=-0.06 * hy), ease='linear')
c.mode('head', 'set')
for t in (0.0, 60.0):
    stand_legs(c, t, right=(0.5, 0.8), left=(0.5, -0.8))
seal(c)
check(c, 'staff butt on the ground (t=0)', fk(c, 0, 'staff', STAFF_FOOT), STAFF_BUTT, tol=0.4)
check(c, 'staff butt on the ground (t=40)', fk(c, 40, 'staff', STAFF_FOOT), STAFF_BUTT, tol=0.4)


# ---- scribe: seated at a table, writing small strokes with the quill (0-36), dipping it in the inkwell on the left (44),
# reading the line back with the quill poised (50-60), the left hand pinning the parchment
NIB = (0.0, 4.0, 0.0)                           # quill part space: the nib
LINE_X0, LINE_X1, LINE_Z = 0.2, -5.6, -13.4     # the line being written: from its left end to its right, on the parchment
PAPER_Y = TABLE_TOP - 0.3
INK_AT = np.array([4.8, 13.4, -13.0])           # the nib in the inkwell (its rim is at y 12.3)
PIN_AT = np.array([0.6, 14.3, -9.8])            # the left fist on the parchment's near left corner


def scribe_nib(t):
    """Where the nib goes: a stroke every 6 ticks along the line (0-36), to the ink and back, poised over the line's start."""
    if t <= 36.0:
        k = t / 36.0
        ph = 2 * math.pi * t / 6.0
        base = np.array([LINE_X0 + (LINE_X1 - LINE_X0) * k, PAPER_Y, LINE_Z])
        return base + np.array([0.55 * math.sin(ph), -0.5 * (0.5 - 0.5 * math.cos(2 * ph)), 0.9 * math.cos(ph)])
    return spline_keys(SCRIBE_PATH, t)


P36 = np.array([LINE_X1, PAPER_Y, LINE_Z + 0.9])
P0 = np.array([LINE_X0, PAPER_Y, LINE_Z + 0.9])
SCRIBE_PATH = [(36.0, P36), (39.0, np.array([-4.6, 10.2, -13.2])), (42.0, np.array([2.4, 9.6, -13.1])), (44.0, INK_AT),
               (46.0, INK_AT + np.array([0.3, 0.0, 0.2])), (48.0, np.array([4.6, 10.4, -13.0])),
               (52.0, np.array([1.4, 9.8, -13.4])), (57.0, np.array([0.4, 10.6, -13.4])), (60.0, P0)]
SCRIBE_PITCH = [(0.0, 7.0), (36.0, 8.0), (44.0, 11.0), (50.0, 5.0), (56.0, 6.0), (60.0, 7.0)]
SCRIBE_YAW = [(0.0, -3.0), (36.0, 3.0), (40.0, 0.0), (44.0, -9.0), (48.0, -6.0), (54.0, -3.0), (60.0, -3.0)]
SCRIBE_GAZE = [(0.0, 0.0), (36.0, -5.8), (42.0, -2.0), (44.0, 4.8), (48.0, 3.0), (51.0, 0.4), (58.0, -5.4), (60.0, 0.2)]
c = clip('scribe', 60, True, look=('mage', 2), tools_=('quill',), station_='desk',
         traces=[('quill', NIB, (255, 230, 160))], events=[('dip', 44)])
guess_r = guess_l = None
worst_off = 0.0
for t in ticks(0, 60, 1):
    breath = math.sin(2 * math.pi * t / 30.0)
    pitch = float(spline_keys(SCRIBE_PITCH, t)) + 0.4 * breath
    yaw = float(spline_keys(SCRIBE_YAW, t))
    nib = scribe_nib(t)
    fr = F_(qt=bend(pitch, yaw, 0.0), vt=(0.0, sink_for(pitch), 0.0), pr=(0.0, SEAT_DROP, 0.0))
    key_frame(c, t, fr, ease='linear')
    qa, off_r = solve_arm_slack('right_arm', fr, [(tool_point('quill', NIB), nib, 3.0)],
                                [(tool_dir('quill', (0, 1, 0)), unit(v(f=0.55, u=-0.8)), 0.4)], start=guess_r)
    qb, off_l = solve_arm_slack('left_arm', fr, [(GRIP['left_arm'], PIN_AT, 3.0)],
                                [((0, 0, -1), D, 0.25)], start=guess_l)
    guess_r, guess_l = qa, qb
    worst_off = max(worst_off, float(np.linalg.norm(off_r)), float(np.linalg.norm(off_l)))
    c.key('right_arm', t, qa, pos=off_r, ease='linear')
    c.key('left_arm', t, qb, pos=off_l, ease='linear')
    gx = float(spline_keys(SCRIBE_GAZE, t))
    c.key('head', t, gaze_head(fr, np.array([gx, PAPER_Y - 0.3, LINE_Z])), ease='linear')
    seat_legs(c, t, ease='linear')
print(f'   (scribe: the shoulders roll {worst_off:.2f} px at most)')
c.mode('head', 'set')
seal(c)
check(c, 'nib on the parchment (t=18)', fk(c, 18, 'quill', NIB), scribe_nib(18.0), tol=0.5)
check(c, 'nib in the ink (t=44)', fk(c, 44, 'quill', NIB), INK_AT, tol=0.5)
check(c, 'left fist on the parchment (t=18)', fk(c, 18, 'left_arm', GRIP['left_arm']), PIN_AT, tol=0.6)
_thigh = fk(c, 0, 'right_leg', (0.0, 6.0, 2.0))
check(c, 'thigh on the step (t=0)', _thigh, (_thigh[0], 24.0, _thigh[2]), tol=0.3)


# ---- dice: seated at a table, the cup shaken (0-20), wound back and thrown (24), the cup lowered while leaning in to see
# the roll (26-40), then back with a little laugh (40-50), the left hand slapping the table
CUP_MOUTH = (0.0, -2.5, -2.6)                   # dice_cup part space: the middle of its mouth
DICE_AT = np.array([0.6, 14.4, -16.6])          # where the dice lie on the felt
DICE_FAR = np.array([0.0, 1.0, -45.0])          # across the table: the other players
PIN_D = np.array([5.2, 14.3, -9.6])             # the left fist on the table
SHAKE_T = 20.0 / 3.0                            # one shake


def dice_arm(t):
    """The right arm's direction (world) and where its front face points: the cup's mouth lies toward the shoulder and
    the front face, so it is upright with the arm 25 degrees down, tilted back at the shaker with the arm level, and
    tipped forward with the arm raised."""
    def shake(sh):
        el = math.radians(1.0 + 13.0 * sh)         # the shake: the fist driven between 12 degrees under level and 14 over
        return v(f=math.cos(el), u=math.sin(el), r=-0.16), unit(U + 0.3 * F * sh + 0.2 * R)
    if t < 20.0:
        return shake(math.cos(2 * math.pi * t / SHAKE_T))
    d_shake, p_shake = shake(1.0)
    keys = [  # (t, direction, palm)
        (20.0, d_shake, p_shake),
        (22.5, v(f=0.3, u=-0.95, r=0.3), unit(U * 0.5 + F * 0.8)),                  # swung down and back: the underhand toss
        (24.0, v(f=0.7, u=0.7, r=0.05), unit(F + D * 0.3)),                          # up and out: the cup tipped, the dice fly
        (26.5, v(f=0.85, u=0.5, r=0.1), unit(F + D * 0.6)),
        (30.0, v(f=1.0, u=-0.05, r=0.35), unit(U + 0.2 * R)),                        # settles low beside the felt
        (39.0, v(f=1.0, u=-0.08, r=0.35), unit(U + 0.2 * R)),
        (44.0, v(f=1.0, u=0.15, r=-0.1), unit(U + 0.3 * F)),
        (50.0, d_shake, p_shake)]
    return (unit(spline_keys([(a, b) for a, b, _ in keys], t)), unit(spline_keys([(a, b) for a, _, b in keys], t)))


DICE_PITCH = [(0.0, 4.0), (20.0, 3.5), (22.0, -3.0), (24.0, 8.0), (27.0, 15.0), (34.0, 20.0), (40.0, 19.0), (44.0, -3.0),
              (47.0, 0.0), (50.0, 4.0)]
c = clip('dice', 50, True, look=('thief', 0), tools_=('dice_cup',), station_='dice',
         traces=[('dice_cup', CUP_MOUTH, (255, 220, 160))], events=[('throw', 24)])
guess_l = None
for t in ticks(0, 50, 1):
    rock = 1.1 * math.cos(2 * math.pi * t / SHAKE_T) * (1.0 - smooth((t - 17.0) / 3.0)) if t < 20.0 else 0.0
    laugh = math.sin(2 * math.pi * (t - 44.0) / 3.3) * math.sin(math.pi * (t - 44.0) / 6.0) if 44.0 <= t <= 50.0 else 0.0
    pitch = float(spline_keys(DICE_PITCH, t)) + rock + 1.8 * laugh
    fr = F_(qt=bend(pitch, 0.0, 1.2 * laugh), vt=(0.0, sink_for(pitch), 0.0), pr=(0.0, SEAT_DROP, 0.0))
    key_frame(c, t, fr, ease='linear')
    d, pm = dice_arm(t)
    c.key('right_arm', t, free(fr, d, pm), ease='linear')
    slap = 0.0
    if 40.0 <= t < 44.5:
        slap = -3.2 * smooth((t - 40.0) / 4.0)
    _, jl = chain_frame('left_arm', **fr)          # the left fist slides over the table as the body leans: the arm is rigid
    reach_z = jl[2] - math.sqrt(max(0.0, 10.05 ** 2 - (PIN_D[0] - jl[0]) ** 2 - (PIN_D[1] + slap - jl[1]) ** 2))
    pin = np.array([PIN_D[0], PIN_D[1] + slap, reach_z])
    qb, off_l = solve_arm_slack('left_arm', fr, [(GRIP['left_arm'], pin, 3.0)], [((0, 0, -1), D, 0.25)], start=guess_l)
    guess_l = qb
    c.key('left_arm', t, qb, pos=off_l, ease='linear')
    # the head: across the table while shaking, down at the felt for the throw and the roll, up for the laugh
    to_felt = smooth((t - 19.0) / 5.0) * (1.0 - smooth((t - 39.0) / 4.0))
    to_laugh = smooth((t - 41.0) / 3.0) * (1.0 - smooth((t - 46.0) / 3.5))
    gaze = DICE_FAR * (1 - to_felt) + DICE_AT * to_felt
    gaze = gaze * (1 - to_laugh) + np.array([0.0, -22.0, -40.0]) * to_laugh
    extra = qeuler(x=2.0 * math.cos(2 * math.pi * t / SHAKE_T) * (1 - smooth((t - 17.0) / 3.0)) if t < 20.0 else -4.0 * laugh, z=3.0 * laugh)
    c.key('head', t, gaze_head(fr, gaze, extra=extra), ease='linear')
    seat_legs(c, t, ease='linear')
c.mode('head', 'set')
seal(c)
_thigh = fk(c, 0, 'right_leg', (0.0, 6.0, 2.0))
check(c, 'thigh on the step (t=0)', _thigh, (_thigh[0], 24.0, _thigh[2]), tol=0.3)
_f = fk(c, 30, 'left_arm', GRIP['left_arm'])
check(c, 'left fist on the table (t=30, height)', (_f[1], 0, 0), (PIN_D[1], 0, 0), tol=0.4)


# ---- count: standing at a counter, the left hand holding the purse open at the chest, the right taking a coin and
# stacking it (12, 24, 36), a look over the stacks, a satisfied nod (48), back to the purse
PURSE_MOUTH = (0.0, -2.5, -2.75)                # coin_purse part space: the middle of its mouth
PURSE_COINS = (0.0, -3.6, -2.75)                # the tops of the coins standing in it
M_L = np.array([-0.2, 3.5, -9.8])               # where the purse's mouth is held
STACK_X = (-6.9, -4.7, -2.5)                    # the three stacks' places on the counter (their y is the counter's, 8)
STACK_Z = -10.5
COUNT_REST = np.array([-4.7, 5.0, -10.0])       # the right fist hovering over the stacks
c = clip('count', 60, True, look=('thief', 3), tools_=('coin_purse',), station_='stacks',
         traces=[('right_arm', GRIP['right_arm'], (255, 220, 120))], events=[('stack', 12), ('stack', 24), ('stack', 36)])
P_PICK = M_L + np.array([0.0, -1.0, 0.0])
P_LIFT = M_L + np.array([-0.6, -3.8, -0.6])


def stack_top(k):
    return np.array([STACK_X[k], 8.0 - 0.7 * (k + 1) - 1.2, STACK_Z])


S_UP = np.array([0.4, -3.4, 0.2])
COUNT_PATH = [(0.0, M_L + np.array([0.8, -3.0, 0.4])), (6.0, P_PICK), (9.0, P_LIFT), (12.0, stack_top(0)),
              (14.5, stack_top(0) + S_UP), (18.0, P_PICK), (21.0, P_LIFT), (24.0, stack_top(1)),
              (26.5, stack_top(1) + S_UP), (30.0, P_PICK), (33.0, P_LIFT), (36.0, stack_top(2)),
              (39.0, stack_top(2) + S_UP), (43.0, COUNT_REST), (52.0, COUNT_REST + np.array([0.0, -0.2, 0.0])),
              (56.0, M_L + np.array([0.9, -4.2, 0.6])), (60.0, M_L + np.array([0.8, -3.0, 0.4]))]
prev = None
for t in ticks(0, 60, 1):
    breath = math.sin(2 * math.pi * t / 30.0)
    tr = spline_keys(COUNT_PATH, t, period=60.0)
    # the glance: at the coin going to the stack, at the stacks after, then up at the nod
    pitch0 = 9.0 + 0.5 * breath
    yaw0 = 4.0 * math.tanh((tr[0] - M_L[0]) / 3.0) * -1.0

    def res(fr, qa, qb, tr=tr):
        out = list((hand_point('right_arm', qa, fr) - tr) * 3.0)
        out += list((hand_point('left_arm', qb, fr, tool_point('coin_purse', PURSE_MOUTH)) - M_L) * 3.0)
        out += list((arm_dir('left_arm', qb, fr, tool_dir('coin_purse', (0, -1, 0))) - unit(U * 0.5 + B * 0.7)) * 0.3)
        return out + level_arms(fr, qa, qb, 0.4)
    fr, qa, qb, prev = body_solve(res, pitch0, yaw0, (0.0, 0.0, 0.0), guess=prev, sink0=sink_for(pitch0), sink_w=0.6,
                                  root_w=0.6, pitch_w=0.12, yaw_w=0.08)
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    nod = 9.0 * bump(t, 44.0, 54.0)
    look = tr + np.array([0.0, 0.5, -0.5]) if (t < 40 or t > 54) else stack_top(1) + np.array([0.0, 0.0, 0.0])
    c.key('head', t, gaze_head(fr, look, extra=qeuler(x=nod)), ease='linear')
c.mode('head', 'set')
for t in ticks(0, 60, 1):
    stand_feet(c, t, frame_at(c, t), right=(0.5, 0.6), left=(0.5, -0.6), ease='linear')
seal(c)
for k, tk in enumerate((12, 24, 36)):
    check(c, f'fist on stack {k + 1} (t={tk})', fk(c, tk, 'right_arm', GRIP['right_arm']), stack_top(k), tol=0.5)
check(c, 'fist in the purse (t=6)', fk(c, 6, 'right_arm', GRIP['right_arm']), P_PICK, tol=0.5)
check(c, 'purse mouth held (t=6)', fk(c, 6, 'coin_purse', PURSE_MOUTH), M_L, tol=0.5)


# ---- sharpen: standing, the sword held across the body in the right hand (blade to the left, tip a little up), the left
# hand drawing the whetstone along its rear edge from hilt to point and lifting it back, twice (centred on 10 and 28)
STONE_FACE = (0.0, 1.0, 0.0)                    # whetstone part space: the middle of its working face
SHARP_RF = np.array([-4.8, 7.5, -8.2])         # the right fist
SHARP_B = unit(0.9 * L + 0.33 * U + 0.22 * F)  # where the blade points
SHARP_S = (5.5, 14.5)                          # the stone's travel along the blade (part y): its centre, hilt to point
REAR_EDGE = -1.25                              # sword part z of the edge on the body's side (the arm's own -y)
LIFT = 1.8                                     # how far the stone leaves the edge on the way back


def sharpen_stone(t):
    """(u, lift): where the stone is along its travel (0 hilt, 1 point) and how far it is off the edge."""
    t = t % 40.0
    if 4.0 <= t < 16.0:                                    # first stroke, pressed on
        return smooth((t - 4.0) / 12.0), 0.0
    if 16.0 <= t < 22.0:                                   # lifted and carried back
        return 1.0 - smooth((t - 16.5) / 5.0), LIFT * smooth((t - 16.0) / 1.5) * (1.0 - smooth((t - 20.5) / 1.5))
    if 22.0 <= t < 34.0:                                   # second stroke
        return smooth((t - 22.0) / 12.0), 0.0
    tt_ = t if t >= 34.0 else t + 40.0                     # the slower return over the turn of the loop (34 .. 44)
    u = 1.0 - smooth((tt_ - 34.5) / 8.5)
    lift = LIFT * smooth((tt_ - 34.0) / 1.5) * (1.0 - smooth((tt_ - 42.0) / 2.0))
    return u, lift


c = clip('sharpen', 40, True, look=('knight', 3), tools_=('sword', 'whetstone'),
         traces=[('whetstone', STONE_FACE, (200, 230, 255))] if False else [], events=[('stroke', 10), ('stroke', 28)])
prev = None
for t in ticks(0, 40, 1):
    breath = math.sin(2 * math.pi * t / 20.0)
    u, lift = sharpen_stone(t)
    s_ = SHARP_S[0] + (SHARP_S[1] - SHARP_S[0]) * u
    pitch0 = 7.0 + 0.4 * breath
    yaw0 = -10.0 * (u - 0.5)
    rf = SHARP_RF

    def res(fr, qa, qb, s_=s_, lift=lift, rf=rf):
        a_r = arm_dir('right_arm', qa, fr, (0, 1, 0))
        edge = hand_point('right_arm', qa, fr, tool_point('sword', (0.0, s_, REAR_EDGE))) - a_r * lift
        stone = hand_point('left_arm', qb, fr, tool_point('whetstone', STONE_FACE))
        b_w = arm_dir('right_arm', qa, fr, tool_dir('sword', (0, 1, 0)))
        out = list((hand_point('right_arm', qa, fr) - rf) * 1.2)
        out += list((b_w - SHARP_B) * 1.5)
        out += list((stone - edge) * 3.0)
        axis = arm_dir('left_arm', qb, fr, tool_dir('whetstone', (0, 0, 1)))
        out += list(np.cross(axis, b_w) * 0.8)
        out += list((arm_dir('left_arm', qb, fr, tool_dir('whetstone', (0, 1, 0))) - a_r) * 0.6)
        return out + level_arms(fr, qa, qb, 0.0)
    fr, qa, qb, prev = body_solve(res, pitch0, yaw0, (0.0, 0.0, 0.0), guess=prev, sink0=sink_for(pitch0), sink_w=0.6,
                                  root_w=0.5, pitch_w=0.15, yaw_w=0.05)
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    contact = hand_point('left_arm', qb, fr, tool_point('whetstone', STONE_FACE))
    c.key('head', t, gaze_head(fr, contact + np.array([0.0, 0.0, 0.0])), ease='linear')
    stand_feet(c, t, fr, ease='linear')
c.mode('head', 'set')
seal(c)
for tk in (10, 28):
    _u, _l = sharpen_stone(float(tk))
    _s = SHARP_S[0] + (SHARP_S[1] - SHARP_S[0]) * _u
    check(c, f'stone on the rear edge (t={tk})', fk(c, tk, 'whetstone', STONE_FACE), fk(c, tk, 'sword', (0.0, _s, REAR_EDGE)), tol=0.5)

# ---- kneel: on the right knee, the sword planted point-down in front of the front foot, both hands on its pommel, the head
# bowed over them; two slow breaths
KNEEL_DROP = 7.0                                # the hips sunk this far (the legs fork front and back to the ground)
KNEEL_TIP = np.array([0.0, 24.0, -18.0])        # where the sword's point stands on the ground, just inside the front foot
KNEEL_B = unit(0.906 * D + 0.423 * F)           # the blade leans away from the kneeler, its pommel toward his chest
KNEEL_RF = KNEEL_TIP - 18.0 * KNEEL_B            # so the right fist (the grip) is here


def kneel_legs(c, t, fr):
    dy = 24.0 - (12.0 + KNEEL_DROP)
    z_back = math.sqrt(max(0.0, 144.0 - dy * dy - 2.5 * 2.5))
    stand_legs(c, t, root=fr['pr'], right=(0.5, fr['pr'][2] + z_back), left=(2.5, fr['pr'][2] - z_back), ease='linear')


c = clip('kneel', 120, True, look=('knight', 1), tools_=('sword',), events=[('breath', 30), ('breath', 90)])
prev_r = prev_l = None
for t in ticks(0, 120, 2):
    breath = 0.5 - 0.5 * math.cos(2 * math.pi * t / 60.0)           # 0 .. 1 .. 0 twice
    pitch = 16.0 - 2.5 * breath
    fr = F_(qt=bend(pitch), vt=(0.0, sink_for(pitch), 0.0), pr=(0.0, KNEEL_DROP, 0.3 - 0.3 * breath))
    key_frame(c, t, fr, ease='linear')
    qa, off_r = solve_arm_slack('right_arm', fr, [(GRIP['right_arm'], KNEEL_RF, 1.0),
                                                  (tool_point('sword', (0.0, 18.0, 0.0)), KNEEL_TIP, 3.0)],
                                [(tool_dir('sword', (0, 1, 0)), KNEEL_B, 1.0)], start=prev_r, slack=0.5)
    prev_r = qa
    c.key('right_arm', t, qa, pos=off_r, ease='linear')
    Ra, joint = arm_world('right_arm', qa, **fr)
    grip_l = joint + off_r + Ra @ tool_point('sword', (0.0, -3.0, 0.0))
    qb, off_l = solve_arm_slack('left_arm', fr, [(GRIP['left_arm'], grip_l, 3.0)], [((1, 0, 0), L, 0.3)], start=prev_l, slack=0.5)
    prev_l = qb
    c.key('left_arm', t, qb, pos=off_l, ease='linear')
    pommel = joint + off_r + Ra @ tool_point('sword', (0.0, -5.0, 0.0))
    c.key('head', t, gaze_head(fr, pommel + np.array([0.0, 1.0, 0.0]), extra=qeuler(x=8.0 - 3.0 * breath)), ease='linear')
    kneel_legs(c, t, fr)
c.mode('head', 'set')
seal(c)
check(c, 'sword point on the ground (t=0)', fk(c, 0, 'sword', (0.0, 18.0, 0.0)), KNEEL_TIP, tol=0.4)
check(c, 'sword point on the ground (t=30)', fk(c, 30, 'sword', (0.0, 18.0, 0.0)), KNEEL_TIP, tol=0.4)
check(c, 'left fist on the grip (t=0)', fk(c, 0, 'left_arm', GRIP['left_arm']), fk(c, 0, 'sword', (0.0, -3.0, 0.0)), tol=0.5)
check(c, 'right foot on the ground (t=0)', (fk(c, 0, 'right_leg', (0.0, 12.0, 0.0))[1],), (24.0,), tol=0.4)
check(c, 'left foot on the ground (t=0)', (fk(c, 0, 'left_leg', (0.0, 12.0, 0.0))[1],), (24.0,), tol=0.4)

# ---- spar_guard: the shield held up to catch two cuts (10 and 26), each met with a lean in and answered with a jolt back,
# the sword hanging low; the partner's cuts land on the shield's face where it is held at those ticks
GUARD_DROP = 0.8                                # a slight crouch: the stance's legs are a little longer than the hips are high
G_BACK = 4.0                                    # this partner stands this far back in its block (the sword's reach is short)
G_PITCH = [(0.0, 3.0), (6.0, 3.5), (8.5, 6.5), (10.0, 8.0), (12.0, -4.5), (14.5, -3.0), (20.0, 3.0), (23.0, 3.5), (24.5, 6.5),
           (26.0, 8.0), (28.0, -4.5), (30.5, -3.0), (36.0, 3.0), (40.0, 3.0)]
G_ROOTZ = [(0.0, 0.0), (6.0, 0.2), (10.0, -1.0), (12.0, 1.8), (14.5, 1.4), (20.0, 0.0), (24.0, 0.2), (26.0, -1.0), (28.0, 1.8),
           (30.5, 1.4), (36.0, 0.0), (40.0, 0.0)]
G_YAW = [(0.0, 12.0), (9.0, 14.0), (10.0, 14.0), (12.0, 20.0), (16.0, 14.0), (24.0, 14.0), (26.0, 11.0), (28.0, 7.0),
         (32.0, 12.0), (40.0, 12.0)]
G_SHIELD = [(0.0, 50.0), (6.0, 52.0), (9.0, 56.0), (10.0, 58.0), (12.0, 46.0), (15.0, 49.0), (20.0, 50.0), (23.0, 50.0),
            (25.0, 47.0), (26.0, 45.0), (28.0, 56.0), (31.0, 51.0), (36.0, 50.0), (40.0, 50.0)]          # forward from hanging
G_SIDE = [(0.0, 0.28), (9.0, 0.32), (10.0, 0.34), (12.0, 0.12), (16.0, 0.24), (24.0, 0.28), (26.0, 0.30), (28.0, 0.14),
          (32.0, 0.26), (40.0, 0.28)]          # the arm swung toward the midline
G_HEAD = [(0.0, 0.0), (8.0, -2.0), (10.0, -3.0), (12.0, 7.0), (16.0, 1.0), (24.0, -2.0), (26.0, -3.0), (28.0, 7.0),
          (33.0, 1.0), (40.0, 0.0)]          # the head tilted back (a flinch) at each blow
c = clip('spar_guard', 40, True, look=('knight', 0), tools_=('sword', 'shield'), events=[('block', 10), ('block', 26)],
         partner=('spar_attack', ('knight', 2)), pan=(0.0, 0.0, -16.0))
for t in ticks(0, 40, 1):
    breath = math.sin(2 * math.pi * t / 40.0)
    pitch = float(spline_keys(G_PITCH, t, period=40.0)) + 0.4 * breath
    yaw = float(spline_keys(G_YAW, t, period=40.0))
    fr = F_(qt=bend(pitch, yaw, 0.0), vt=(0.0, sink_for(pitch), 0.0),
            pr=(0.0, GUARD_DROP, G_BACK + float(spline_keys(G_ROOTZ, t, period=40.0))))
    key_frame(c, t, fr, ease='linear')
    E = math.radians(float(spline_keys(G_SHIELD, t, period=40.0)))
    k = float(spline_keys(G_SIDE, t, period=40.0))
    c.key('left_arm', t, wield('shield', fr, unit(math.cos(E) * D + math.sin(E) * F + k * R), D), ease='linear')
    sway = 0.04 * math.sin(2 * math.pi * t / 40.0 + 1.0)
    blade = unit(0.8 * F + (0.45 + sway) * D + 0.35 * R)                     # the point toward the partner's knees
    c.key('right_arm', t, wield('sword', fr, unit(D - float(np.dot(D, blade)) * blade), blade), ease='linear')
    c.key('head', t, qeuler(x=-float(spline_keys(G_HEAD, t, period=40.0)), y=-0.85 * yaw), ease='linear')
    stand_legs(c, t, root=fr['pr'], right=(1.2, G_BACK + 3.2), left=(1.2, G_BACK - 3.2), ease='linear')
c.mode('head', 'set')
seal(c)


def shield_pt(plate, t, dz=0.0):
    """A point of the guard's shield face (plate coordinates x, y in the shield's part space) at tick [t], in the
    attacking partner's frame (the partner stands facing back at it, SPAR_GAP ahead)."""
    q = partner_place() @ np.append(fk(getc('spar_guard'), t, 'shield', (plate[0], plate[1], -3.5)), 1.0)
    return q[:3]


def getc(name):
    return next(c for c in clips if c.name == name)


# ---- spar_attack: sword and shield against a partner two blocks ahead: a forehand cut (lands 10) and a backhand cut
# (lands 26), the shield kept up, back to the guard by 40
CUT_1 = shield_pt((3.0, -5.0), 10.0)            # the forehand lands on the face's right part, high
CUT_2 = shield_pt((-1.5, -1.0), 26.0)           # the backhand on its left part
R_TIP = float(np.linalg.norm(np.array([-1.0, 10.0, -18.0])))      # the sword's point is always this far from the shoulder


def impact_body(point, a_hint, pitch0, yaw0, root0):
    """The torso's lean, turn, tip and sink and the root's step that put the sword's point on [point]."""
    b_hint = unit(point - np.array([-5.0, 2.0, 0.0]))
    def res(fr, qa, qb):
        tip = hand_point('right_arm', qa, fr, tool_point('sword', (0.0, 18.0, 0.0)))
        out = list((tip - point) * 4.0)
        out += list((arm_dir('right_arm', qa, fr, (0, 1, 0)) - unit(a_hint)) * 0.5)
        out += list(arm_dir('left_arm', qb, fr, (0, 1, 0)) - unit(D * 0.7 + F * 0.7))
        return out
    fr, qa, qb, x = body_solve(res, pitch0, yaw0, root0, pitch_w=0.4, yaw_w=0.3, roll_w=0.8, sink_w=0.5, root_w=0.5,
                               sink0=sink_for(pitch0))
    return x


IMPACT_1 = impact_body(CUT_1, v(f=0.5, u=0.87, r=0.0), 8.0, -4.0, (0.0, 0.0, -0.5))
IMPACT_2 = impact_body(CUT_2, v(f=0.7, u=0.3, r=-0.6), 8.0, 14.0, (0.0, 0.0, -0.5))
print('   spar_attack impact bodies:', np.round(IMPACT_1[:6], 2), np.round(IMPACT_2[:6], 2))

ATT_BACK = 1.0                                  # the attacker's guard: hips this far back of the block's centre
T_GUARD = np.array([-5.0, -13.6, -13.4])        # the point of the sword held up before him
A_BODY = [  # (t, pitch, yaw, root x, root z, roll, sink)
    (0.0, 5.0, 12.0, 0.0, ATT_BACK, 0.0, 0.0),
    (4.0, 3.0, 34.0, 0.0, ATT_BACK + 0.8, 0.0, 0.0),                    # loaded to the right
    (7.0, 6.0, 12.0, 0.0, ATT_BACK - 0.5, 0.0, 0.0),
    (10.0, IMPACT_1[0], IMPACT_1[1], IMPACT_1[4], IMPACT_1[5], IMPACT_1[2], IMPACT_1[3]),
    (12.0, 7.0, 0.0, 0.0, ATT_BACK, 0.0, 0.0),
    (16.0, 4.0, -14.0, 0.0, ATT_BACK + 0.6, 0.0, 0.0),
    (20.0, 3.0, -30.0, 0.0, ATT_BACK + 1.2, 0.0, 0.0),                  # loaded to the left
    (23.0, 6.0, -6.0, 0.0, ATT_BACK - 1.0, 0.0, 0.0),
    (26.0, IMPACT_2[0], IMPACT_2[1], IMPACT_2[4], IMPACT_2[5], IMPACT_2[2], IMPACT_2[3]),
    (28.0, 7.0, 16.0, 0.0, ATT_BACK - 2.0, 0.0, 0.0),
    (33.0, 6.0, 14.0, 0.0, ATT_BACK - 0.4, 0.0, 0.0),
    (36.0, 5.0, 12.0, 0.0, ATT_BACK, 0.0, 0.0),
    (40.0, 5.0, 12.0, 0.0, ATT_BACK, 0.0, 0.0)]
A_ARM = [  # (t, the arm's direction)
    (0.0, v(f=0.98, u=-0.2)), (4.0, v(f=0.0, u=0.95, r=0.3)), (7.0, v(f=0.1, u=0.98, r=0.1)),
    (10.0, v(f=0.5, u=0.87)), (12.0, v(f=0.8, u=0.5)), (16.0, v(f=0.8, u=0.2, r=-0.4)), (20.0, v(f=0.4, u=-0.1, r=-0.9)),
    (23.0, v(f=0.7, u=0.1, r=-0.6)), (26.0, v(f=0.7, u=0.3, r=-0.6)), (28.0, v(f=0.8, u=0.3, r=-0.3)),
    (33.0, v(f=0.9, u=0.1)), (36.0, v(f=0.98, u=-0.2)), (40.0, v(f=0.98, u=-0.2))]
SWING_1 = [(0.0, np.array([-7.0, -12.0, 12.0])), (0.55, np.array([-4.0, -20.0, -4.0])), (1.0, CUT_1)]
SWING_2 = [(0.0, np.array([12.0, 7.0, -6.0])), (0.55, np.array([9.0, 5.0, -16.0])), (1.0, CUT_2)]


def attack_tip(t):
    """Where the sword's point is asked to be (before it is brought onto the sphere the arm can reach)."""
    if t < 4.0:
        u = smooth(t / 4.0)
        return T_GUARD + (SWING_1[0][1] - T_GUARD) * u
    if t < 10.0:
        return spline_keys(SWING_1, ((t - 4.0) / 6.0) ** 1.8)
    if t < 11.5:
        u = 1.0 - (1.0 - (t - 10.0) / 1.5) ** 2
        return CUT_1 + np.array([0.5, -1.0, 1.8]) * u
    if t < 20.0:
        u = smooth((t - 11.5) / 8.5)
        a = CUT_1 + np.array([0.5, -1.0, 1.8])
        return a + (SWING_2[0][1] - a) * u
    if t < 26.0:
        return spline_keys(SWING_2, ((t - 20.0) / 6.0) ** 1.8)
    if t < 27.5:
        u = 1.0 - (1.0 - (t - 26.0) / 1.5) ** 2
        return CUT_2 + np.array([-0.6, 0.0, 1.8]) * u
    if t < 36.0:
        u = smooth((t - 27.5) / 8.5)
        a = CUT_2 + np.array([-0.6, 0.0, 1.8])
        return a + (T_GUARD - a) * u
    return T_GUARD


def spline_rows(rows, t, col):
    return float(spline_keys([(r[0], r[col]) for r in rows], t, period=40.0))


c = clip('spar_attack', 40, True, look=('knight', 2), tools_=('sword', 'shield'), events=[('cut', 10), ('cut', 26)],
         traces=[('sword', (0.0, 18.0, 0.0), (255, 240, 160))], partner=('spar_guard', ('knight', 0)), pan=(0.0, 0.0, -16.0))
prev = None
for t in ticks(0, 40, 1):
    breath = math.sin(2 * math.pi * t / 40.0)
    pitch = spline_rows(A_BODY, t, 1) + 0.3 * breath
    fr = F_(qt=bend(pitch, spline_rows(A_BODY, t, 2), spline_rows(A_BODY, t, 5)),
            vt=(0.0, spline_rows(A_BODY, t, 6) if t in (10.0, 26.0) else sink_for(pitch), 0.0),
            pr=(spline_rows(A_BODY, t, 3), GUARD_DROP, spline_rows(A_BODY, t, 4)))
    key_frame(c, t, fr, ease='linear')
    S_r = chain_frame('right_arm', **fr)[1]
    want = attack_tip(t)
    if t in (10.0, 26.0):
        tip_t = want
    else:
        tip_t = S_r + unit(want - S_r) * R_TIP
    a_hint = spline_keys([(r[0], r[1]) for r in A_ARM], t, period=40.0)
    qa, _ = solve_arm('right_arm', fr, [(tool_point('sword', (0.0, 18.0, 0.0)), tip_t, 4.0)],
                      [((0, 1, 0), a_hint, 0.5)], guess=prev)
    prev = qa
    c.key('right_arm', t, qa, ease='linear')
    E = math.radians(52.0 + 4.0 * math.sin(2 * math.pi * t / 40.0 + 0.5))
    c.key('left_arm', t, wield('shield', fr, unit(math.cos(E) * D + math.sin(E) * F + 0.28 * R), D), ease='linear')
    c.key('head', t, gaze_head(fr, np.array([0.0, 0.0, -30.0])), ease='linear')
    stand_legs(c, t, root=fr['pr'], right=(1.4, ATT_BACK + 3.5), left=(1.4, ATT_BACK - 3.5), ease='linear')
c.mode('head', 'set')
seal(c)
check(c, 'forehand lands on the shield (t=10)', fk(c, 10, 'sword', (0.0, 18.0, 0.0)), CUT_1, tol=0.4)
check(c, 'backhand lands on the shield (t=26)', fk(c, 26, 'sword', (0.0, 18.0, 0.0)), CUT_2, tol=0.4)

# ---- drill: at a practice post a block ahead, an overhead cut that lands at 12 and a rising diagonal that lands at 26,
# the shield held up throughout, back to the ready stance by 32
POST_FACE = POST_Z + 3.5 - 1.2                  # the straw's near face, the point biting in a little
DRILL_BACK = 5.0                                # he stands back this far from his block's centre: the sword reaches 20 px
DRILL_1 = np.array([-1.0, 3.0, POST_FACE])      # the overhead cut lands on the post's chest, below the straw head
DRILL_2 = np.array([2.0, -4.5, POST_FACE])      # the rising cut ends higher, on the head's left
IMPACT_D1 = impact_body(DRILL_1, v(f=0.5, u=0.87), 8.0, 4.0, (0.0, 0.0, DRILL_BACK))
IMPACT_D2 = impact_body(DRILL_2, v(f=0.6, u=0.6, r=-0.4), 8.0, -6.0, (0.0, 0.0, DRILL_BACK))
print('   drill impact bodies:', np.round(IMPACT_D1[:6], 2), np.round(IMPACT_D2[:6], 2))
DR_BODY = [  # (t, pitch, yaw, root x, root z, roll, sink)
    (0.0, 5.0, 10.0, 0.0, DRILL_BACK, 0.0, 0.0),
    (6.0, 2.0, 18.0, 0.0, DRILL_BACK + 0.8, 0.0, 0.0),
    (9.0, 6.0, 8.0, 0.0, DRILL_BACK - 0.4, 0.0, 0.0),
    (12.0, IMPACT_D1[0], IMPACT_D1[1], IMPACT_D1[4], IMPACT_D1[5], IMPACT_D1[2], IMPACT_D1[3]),
    (13.0, 7.0, 4.0, 0.0, DRILL_BACK + 1.8, 0.0, 0.0),                  # the post throws the blow back: he rocks back
    (14.5, 5.0, 6.0, 0.0, DRILL_BACK + 3.0, 0.0, 0.0),
    (20.0, 3.0, 26.0, 0.0, DRILL_BACK + 1.0, 0.0, 0.0),
    (23.0, 6.0, 8.0, 0.0, DRILL_BACK - 0.5, 0.0, 0.0),
    (26.0, IMPACT_D2[0], IMPACT_D2[1], IMPACT_D2[4], IMPACT_D2[5], IMPACT_D2[2], IMPACT_D2[3]),
    (27.0, 7.5, -2.0, 0.0, DRILL_BACK + 1.6, 0.0, 0.0),
    (28.5, 6.0, 0.0, 0.0, DRILL_BACK + 2.6, 0.0, 0.0),
    (32.0, 5.0, 10.0, 0.0, DRILL_BACK, 0.0, 0.0)]
DR_ARM = [(0.0, v(f=0.98, u=-0.2)), (6.0, v(f=0.7, u=0.7)), (9.0, v(f=0.4, u=0.9)), (12.0, v(f=0.5, u=0.87)),
          (14.0, v(f=0.8, u=0.5)), (20.0, v(f=0.3, u=-0.8, r=0.5)), (23.0, v(f=0.5, u=-0.2, r=0.3)),
          (26.0, v(f=0.6, u=0.6, r=-0.4)), (28.0, v(f=0.8, u=0.3)), (32.0, v(f=0.98, u=-0.2))]
DR_SWING_1 = [(0.0, np.array([-5.0, -17.6, 8.6])), (0.55, np.array([-4.0, -19.0, -2.0])), (1.0, DRILL_1)]
DR_SWING_2 = [(0.0, np.array([-14.4, 16.6, -8.4])), (0.55, np.array([-9.0, 8.0, -14.0])), (1.0, DRILL_2)]
T_GUARD_D = T_GUARD + np.array([0.0, 0.0, DRILL_BACK - ATT_BACK])
DR_BOUNCE_1 = np.array([0.4, -0.8, 3.4])
DR_BOUNCE_2 = np.array([-0.6, 0.0, 3.4])


def drill_tip(t):
    if t < 6.0:
        return T_GUARD_D + (DR_SWING_1[0][1] - T_GUARD_D) * smooth(t / 6.0)
    if t < 12.0:
        return spline_keys(DR_SWING_1, ((t - 6.0) / 6.0) ** 1.8)
    if t < 13.5:
        return DRILL_1 + DR_BOUNCE_1 * (1.0 - (1.0 - (t - 12.0) / 1.5) ** 2)
    if t < 20.0:                                # pulled off the post to the right, then down and round for the second cut
        a = DRILL_1 + DR_BOUNCE_1
        return spline_keys([(0.0, a), (0.4, np.array([-8.0, 4.0, -8.0])), (1.0, DR_SWING_2[0][1])], smooth((t - 13.5) / 6.5))
    if t < 26.0:
        return spline_keys(DR_SWING_2, ((t - 20.0) / 6.0) ** 1.8)
    if t < 27.5:
        return DRILL_2 + DR_BOUNCE_2 * (1.0 - (1.0 - (t - 26.0) / 1.5) ** 2)
    a = DRILL_2 + DR_BOUNCE_2
    return a + (T_GUARD_D - a) * smooth((t - 27.5) / 4.5)


def rows_at(rows, t, col, period):
    return float(spline_keys([(r[0], r[col]) for r in rows], t, period=period))


c = clip('drill', 32, True, look=('knight', 1), tools_=('sword', 'shield'), station_='post',
         events=[('chop', 12), ('cut', 26)], traces=[('sword', (0.0, 18.0, 0.0), (255, 240, 160))])
prev = None
for t in ticks(0, 32, 1):
    breath = math.sin(2 * math.pi * t / 32.0)
    pitch = rows_at(DR_BODY, t, 1, 32.0) + 0.3 * breath
    fr = F_(qt=bend(pitch, rows_at(DR_BODY, t, 2, 32.0), rows_at(DR_BODY, t, 5, 32.0)),
            vt=(0.0, rows_at(DR_BODY, t, 6, 32.0) if t in (12.0, 26.0) else sink_for(pitch), 0.0),
            pr=(rows_at(DR_BODY, t, 3, 32.0), GUARD_DROP, rows_at(DR_BODY, t, 4, 32.0)))
    key_frame(c, t, fr, ease='linear')
    S_r = chain_frame('right_arm', **fr)[1]
    want = drill_tip(t)
    tip_t = want if t in (12.0, 26.0) else S_r + unit(want - S_r) * R_TIP
    a_hint = spline_keys([(r[0], r[1]) for r in DR_ARM], t, period=32.0)
    qa, _ = solve_arm('right_arm', fr, [(tool_point('sword', (0.0, 18.0, 0.0)), tip_t, 4.0)], [((0, 1, 0), a_hint, 0.5)], guess=prev)
    prev = qa
    c.key('right_arm', t, qa, ease='linear')
    E = math.radians(52.0 + 4.0 * math.sin(2 * math.pi * t / 32.0 + 0.5))
    c.key('left_arm', t, wield('shield', fr, unit(math.cos(E) * D + math.sin(E) * F + 0.28 * R), D), ease='linear')
    c.key('head', t, gaze_head(fr, np.array([0.0, 0.0, POST_FACE + 1.0])), ease='linear')
    stand_legs(c, t, root=fr['pr'], right=(1.4, DRILL_BACK + 3.5), left=(1.4, DRILL_BACK - 3.5), ease='linear')
c.mode('head', 'set')
seal(c)
check(c, 'overhead cut lands on the post (t=12)', fk(c, 12, 'sword', (0.0, 18.0, 0.0)), DRILL_1, tol=0.4)
check(c, 'rising cut lands on the post (t=26)', fk(c, 26, 'sword', (0.0, 18.0, 0.0)), DRILL_2, tol=0.4)

# ---- pick_lock: bent forward at a door, the pick working in the lock with tiny jiggles, the left hand on the hip, and a
# quick glance back over the right shoulder (40)
KEYHOLE = np.array([-1.3, 9.4, DOOR_Z + 0.7])  # where the pick works: the lock plate's face
PICK_TIP = (0.0, 8.0, 0.0)                      # lockpick part space: the tip
HIP_T = np.array([4.8, 11.6, -1.5])             # torso space: the left hand's place on the hip
PICK_BACK = 4.5                                 # he stands this far back: the pick sits at the end of his reach
c = clip('pick_lock', 60, True, look=('thief', 1), tools_=('lockpick',), station_='door', events=[('glance', 40)],
         traces=[('lockpick', PICK_TIP, (255, 230, 150))])


def jiggle(t):
    """The pick's tiny movements in the lock (px): a rake up and down, a push and pull, never quite the same twice."""
    return np.array([0.22 * math.sin(2 * math.pi * t / 7.5 + 0.4),
                     0.34 * math.sin(2 * math.pi * t / 12.0 + 1.1) + 0.14 * math.sin(2 * math.pi * t / 5.0),
                     0.28 * math.sin(2 * math.pi * t / 10.0 + 2.0)])


prev = None
for t in ticks(0, 60, 1):
    g = bump(t, 33.0, 47.0)                      # the glance back
    breath = math.sin(2 * math.pi * t / 30.0)
    tip = KEYHOLE + jiggle(t) * (1.0 - g) + np.array([0.0, -0.6, 2.4]) * g
    pitch0 = 24.0 - 9.0 * g + 0.4 * breath
    yaw0 = 22.0 * g

    def res(fr, qa, qb, tip=tip):
        out = list((hand_point('right_arm', qa, fr, tool_point('lockpick', PICK_TIP)) - tip) * 3.0)
        out += list((arm_dir('right_arm', qa, fr, tool_dir('lockpick', (0, 1, 0))) - unit(F + 0.25 * D)) * 0.6)
        out += list((hand_point('left_arm', qb, fr) - torso_to_world(fr, HIP_T)) * 1.5)
        return out + level_arms(fr, qa, qb, 0.3)
    fr, qa, qb, prev = body_solve(res, pitch0, yaw0, (0.0, 0.0, PICK_BACK), guess=prev, sink0=sink_for(pitch0), sink_w=0.6,
                                  root_w=0.5, pitch_w=0.4, yaw_w=0.4, roll_w=0.8)
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    glance_at = np.array([-30.0, -2.0, 6.0])
    h_lock = gaze_head(fr, KEYHOLE + np.array([0.0, 0.0, 0.5]))
    h_back = gaze_head(fr, glance_at)
    c.key('head', t, qslerp(h_lock, h_back, smooth(g * 1.8)), ease='linear')
    stand_feet(c, t, fr, right=(1.0, 0.8), left=(1.0, -0.8), ease='linear')
c.mode('head', 'set')
seal(c)
check(c, 'pick tip in the keyhole (t=10)', fk(c, 10, 'lockpick', PICK_TIP), KEYHOLE + jiggle(10.0), tol=0.4)
check(c, 'pick tip in the keyhole (t=24)', fk(c, 24, 'lockpick', PICK_TIP), KEYHOLE + jiggle(24.0), tol=0.4)
check(c, 'left hand on the hip (t=10)', fk(c, 10, 'left_arm', GRIP['left_arm']), torso_to_world(frame_at(c, 10), HIP_T), tol=0.8)

# ================================================================== faction clips: end


# ================================================================== the captive: a villager held in an outlaw camp

# Four clips. bound (a loop): kneeling sat back on his heels, both wrists tied behind him, breathing, glancing about, and
# once a loop hauling at the rope. plead (once, 40): a player is near; he leans toward them, head up, shoulders shaking
# as he calls out twice, and settles back into bound (it starts and ends exactly on bound's first pose). freed (once,
# 50): the arms come round, each wrist rubbed in turn, then up off his knees (a step, the hips rising on the legs
# that unfold) to the standing pose. thank (once, 40): standing, hands clasped at the chest, a small bow, a step of
# relief back to standing. freed and thank end in the plain standing pose (legs, torso and root at rest, the arms
# `relaxed`, the head following the look), so whatever the game plays next fades from there.
# A rigid arm cannot reach behind the back or across the belly without sinking into the torso, so those poses come from
# cap_arm_to: a least-squares solve that lets the shoulder shift a little (the arm key's own offset) to keep the arm's
# box out of the body's. The poses are solved once (the right arm; the left is its mirror) and blended with cap_mix.

CAP_DROP = 8.0                                  # the hips sunk this far: the torso sits on the shins
CAP_HAND = np.array([-2.4, 11.0, 7.5])           # torso space: where the tied right wrist rests, a hand's width behind the small of the back
CAP_PITCH = 6.0                                 # slumped a little forward
CAP_HEAD = (0.0, 12.0, 0.0)                     # (yaw, pitch, roll) at rest: the head hangs
CAP_TORSO_LO, CAP_TORSO_HI = np.array([-4.0, 0.0, -2.0]), np.array([4.0, 12.0, 2.0])
CAP_ARM_BOX = (np.array([-3.0, -2.0, -2.0]), np.array([4.0, 12.0, 4.0]))       # the right arm's box in arm space: origin, size
_CAP_GRID = {}


def cap_arm_samples(side):
    """Points over an arm's box, faces and edges included (arm space), to measure how far it sinks into the body (the
    left arm's are the right arm's mirrored, so the two sides measure alike)."""
    if not _CAP_GRID:
        o, s = CAP_ARM_BOX
        axes = [np.linspace(o[i], o[i] + s[i], int(round(s[i])) + 1) for i in range(3)]
        pts = np.stack(np.meshgrid(*axes, indexing='ij'), -1).reshape(-1, 3)
        _CAP_GRID['right_arm'] = pts
        _CAP_GRID['left_arm'] = pts * np.array([-1.0, 1.0, 1.0])
    return _CAP_GRID[side]


def cap_arm_to(side, target, start=None, p=None, max_off=2.0, pen_w=3.0, off_w=(0.15, 0.6, 0.1), y_min=-0.3, palm=None, palm_w=0.2,
               w=4.0, env=None, env_w=1.0, off_pref=None, fixed_off=None):
    """An arm key and shoulder offset that bring the grip (or the arm-space point [p]) to [target] (torso space) with the
    arm clear of the body: the shoulder may move a little (up to [max_off] px, never up: [y_min]; [off_pref] is where it
    likes to be: (inward, down, back) px; [fixed_off] pins it there) as the arm's own track offset. [palm]: the way the
    arm's front should face. [env]: an envelope the arm's box is kept inside: no further out than env[0] (toward its own
    side), no higher than env[1], no further forward than env[2]. Returns (key, offset)."""
    grip = np.array(GRIP[side] if p is None else p, float)
    pivot = np.array(PIVOTS[side], float)
    ow = np.array(off_w)
    target = np.asarray(target, float)
    palm = None if palm is None else unit(palm)

    sx = 1.0 if side == 'right_arm' else -1.0          # inward is +x for the right arm, -x for the left
    fo = None if fixed_off is None else np.array([sx * fixed_off[0], fixed_off[1], fixed_off[2]], float)

    def res(x):
        q, off = rvec_q(x[:3]), (x[3:6] if fo is None else fo)
        Rm = qmat(q)
        P = pivot + off + cap_arm_samples(side) @ Rm.T
        d = np.maximum(0.0, np.minimum(P - CAP_TORSO_LO, CAP_TORSO_HI - P).min(axis=1))
        n = float(np.linalg.norm(off))
        out = list((pivot + off + Rm @ grip - target) * w)
        out += [float(np.sqrt(np.mean(d * d))) * pen_w * 3.0, float(d.max()) * pen_w]
        out += list((off - (0.0 if off_pref is None else np.array([sx * off_pref[0], off_pref[1], off_pref[2]]))) * ow)
        out += [max(0.0, y_min - off[1]) * 5.0]
        out += list(off / max(n, 1e-9) * max(0.0, n - max_off) * 6.0)
        if palm is not None:
            out += list((Rm @ np.array([0.0, 0.0, -1.0]) - palm) * palm_w)
        if env is not None:
            side_x = -P[:, 0] if side == 'right_arm' else P[:, 0]
            ex = np.maximum(0.0, side_x - env[0])
            ey = np.maximum(0.0, env[1] - P[:, 1])
            ez = np.maximum(0.0, env[2] - P[:, 2])
            for e in (ex, ey, ez):
                out += [float(np.sqrt(np.mean(e * e))) * env_w * 2.0, float(e.max()) * env_w]
        return out
    if start is not None:
        starts = [list(q_rvec(start[0])) + list(start[1])]
    else:
        starts = [[-1, 0.3, 0.3, 0, 0, 0], [-1.2, -0.3, 0.5, 0, 0, 1], [-0.8, 0.6, -0.4, -1, 0, 1], [0.5, 0, 0, 0, 0, 0]]
    best = None
    for s0 in starts:
        r = least_squares(res, np.array(s0, float))
        if best is None or r.cost < best.cost:
            best = r
    return rvec_q(best.x[:3]), (best.x[3:6].copy() if fo is None else fo.copy())


def cap_mix(a, b, w):
    """Between two (key, offset) arm poses."""
    return qslerp(a[0], b[0], w), a[1] + (b[1] - a[1]) * w


def cap_mirror(p):
    """A (key, offset) arm pose mirrored left for right."""
    return mirror_q(p[0]), mirror_dir(p[1])


def cap_pair_mix(a, b, w):
    """Between two pairs of ((right key, offset), (left key, offset))."""
    return cap_mix(a[0], b[0], w), cap_mix(a[1], b[1], w)


def cap_aim(axis, palm=F, off=(0.0, 0.0, 0.0)):
    """A right-arm (key, offset) pointing its length along torso-space [axis], its front facing [palm] as near as it goes."""
    return frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), unit(axis), unit(palm)), np.asarray(off, float)


def cap_legs(c, t, ease='linear', drop=CAP_DROP):
    """Both legs folded flat under him (the shins on the ground trailing back), the knees a touch apart."""
    ly = 24.0 - 2.0 - (12.0 + drop)
    c.key('right_leg', t, qeuler(x=90.0, y=-7.0), pos=(-0.8, ly, -3.5), ease=ease)
    c.key('left_leg', t, qeuler(x=90.0, y=7.0), pos=(0.8, ly, -3.5), ease=ease)


CAP_TIED_ARGS = dict(env=(7.9, -0.6, -2.4), env_w=1.0, max_off=4.0, y_min=-0.5, pen_w=12.0)
CAP_SHOULDERS_REST = (1.2, 0.9, 2.9)       # tied, the shoulders draw in and back (px: inward, down, back)
CAP_SHOULDERS_PULL = (1.2, 0.3, 3.6)
_CAP_TIED = {}


def cap_tied(pull=0.0, shift=(0.0, -2.2, 2.0)):
    """(right key, offset, left key, offset): both wrists tied together behind the back, at rest ([pull] 0) or hauling
    against the rope ([pull] 1: the hands [shift] px up and back, the shoulders drawn further back)."""
    if 'rest' not in _CAP_TIED:
        for name, s_, sh in (('rest', np.zeros(3), CAP_SHOULDERS_REST), ('pull', np.array(shift), CAP_SHOULDERS_PULL)):
            r0 = _CAP_TIED.get('rest')
            pr_ = cap_arm_to('right_arm', CAP_HAND + s_, start=None if r0 is None else r0[0], palm=L, fixed_off=sh, **CAP_TIED_ARGS)
            _CAP_TIED[name] = (pr_, cap_mirror(pr_))
    (r0, l0), (r1, l1) = _CAP_TIED['rest'], _CAP_TIED['pull']
    k = min(1.0, max(0.0, pull))
    return (*cap_mix(r0, r1, k), *cap_mix(l0, l1, k))


def cap_tied_arms(c, t, pull=0.0, ease='linear'):
    qr, orr, ql, ol = cap_tied(pull)
    c.key('right_arm', t, qr, pos=orr, ease=ease)
    c.key('left_arm', t, ql, pos=ol, ease=ease)


def cap_breath(t, period=60.0):
    return 0.5 - 0.5 * math.cos(2 * math.pi * t / period)


def cap_torso(t, pull=0.0, yaw=0.0):
    """The bound captive's torso at tick [t] of his loop: calm (a slow breath, a slow sway), or [pull]ing at the rope and
    twisting [yaw] degrees (the right shoulder back is positive). Returns the frame."""
    br = cap_breath(t)
    pitch = CAP_PITCH - 1.6 * br + 4.0 * pull
    roll = 1.2 * math.sin(2 * math.pi * t / 120.0 + 0.8)
    return F_(qt=bend(pitch, yaw, roll), vt=(0.0, sink_for(pitch) - 0.35 * br, 0.0), pr=(0.0, CAP_DROP, 0.0))


def cap_floor_check(c, t, label):
    """Both legs' undersides on the ground (the flat legs of the kneel: the underside's middle point)."""
    for side in ('right_leg', 'left_leg'):
        check(c, f'{side} lies on the ground ({label})', (fk(c, t, side, (0.0, 6.0, -2.0))[1],), (24.0,), tol=0.4)


# ---- bound: the loop (120). Head glances (t, yaw, pitch, roll), the struggle's twist of the shoulders (yaw) and the
# pulls of the hands on the rope: one struggle, three hauls, at 76-92
BOUND_HEAD = [(0, 0.0, 12.0, 0.0), (8, 0.0, 12.0, 0.0), (15, 30.0, 4.0, -2.0), (24, 34.0, 5.0, -2.0), (31, 32.0, 6.0, -1.0),
              (40, 6.0, 11.0, 0.0), (50, -4.0, 13.0, 0.0), (58, -4.0, 13.0, 0.0), (64, -26.0, 3.0, 2.0), (71, -30.0, 2.0, 2.0),
              (76, -12.0, 16.0, 6.0), (80, 14.0, 16.0, -5.0), (84, 22.0, 14.0, -6.0), (88, 6.0, 16.0, 4.0), (92, -14.0, 18.0, 7.0),
              (97, 0.0, 19.0, 2.0), (104, 0.0, 20.0, 0.0), (112, 0.0, 14.0, 0.0), (120, 0.0, 12.0, 0.0)]
BOUND_YAW = [(0, 0.0), (68, 0.0), (72, 3.0), (76, 10.0), (80, 0.0), (84, -10.0), (88, 0.0), (92, 6.0), (97, -2.0), (104, 0.0), (120, 0.0)]
BOUND_PULLS = [(76.0, 1.0), (84.0, 1.0), (92.0, 0.7)]

c = clip('bound', 120, True, look=('citizen', 0), events=[('struggle', 76)], views=((32, -12), (90, -2), (212, -12)))
for t in ticks(0, 120, 1):
    pull = min(1.0, sum(s * bump(t, tk_ - 3.0, tk_ + 4.0) for tk_, s in BOUND_PULLS))
    fr = cap_torso(t, pull, float(spline_keys(BOUND_YAW, t, period=120.0)))
    key_frame(c, t, fr, ease='linear')
    cap_tied_arms(c, t, pull)
    cap_legs(c, t)
    hy, hp, hr = [float(x) for x in spline_keys([(a_, np.array([b_, c_, d_])) for a_, b_, c_, d_ in BOUND_HEAD], t, period=120.0)]
    c.key('head', t, qeuler(x=hp, y=hy, z=hr), ease='linear')
c.mode('head', 'set')
seal(c)
cap_floor_check(c, 0, 't=0')

# ---- plead (40): a player is near. He leans in toward them, the head comes up, the shoulders shake as he calls out
# (twice: 4 and 13, with the voice line, which the game starts on tick 0: its words fall in the first 15 to 25 ticks) and
# he stays hopeful a while, then settles back into bound. The ends are bound's first pose exactly (the arms, legs, root and
# torso keys; the head is 'add' here, bound's 'set', and rests at the same angle), so the crossfades are clean.
PLEAD_REST = cap_torso(0.0)                                  # the bound loop's calm start: where plead starts and ends
PLEAD_REST_ROLL = 1.2 * math.sin(0.8)
PLEAD_LEAN = [(0, 0.0), (2, 5.0), (4.5, 12.0), (7, 14.0), (12, 14.0), (18, 12.0), (24, 8.0), (31, 3.0), (40, 0.0)]       # more forward lean, deg
PLEAD_SHAKE = [(0, 0.0), (2, 0.0), (3.5, 7.0), (5.5, -6.0), (7.5, 4.0), (9, -1.0), (10.5, 0.0), (11, 0.0), (12.5, 5.0), (14.5, -4.0), (16.5, 1.5), (18, 0.0), (40, 0.0)]
PLEAD_UP = [(0, 0.0), (2, 0.5), (4, 1.0), (12, 0.95), (18, 0.9), (23, 0.7), (29, 0.35), (35, 0.1), (40, 0.0)]      # how far the head has come up (0 hung, 1 up)

c = clip('plead', 40, False, look=('citizen', 0), events=[('call', 4), ('call', 13)], views=((32, -12), (90, -2), (212, -12)))
q_rest_head = qmul(PLEAD_REST['qt'], qeuler(x=CAP_HEAD[1]))      # the head's rest rotation as 'add' needs it (vanilla looking ahead)
for t in ticks(0, 40, 1):
    lean = float(spline_keys(PLEAD_LEAN, t))
    shake = float(spline_keys(PLEAD_SHAKE, t))
    pitch = CAP_PITCH + lean
    fr = F_(qt=bend(pitch, shake, PLEAD_REST_ROLL - 0.3 * shake),
            vt=(0.0, sink_for(pitch) - sink_for(CAP_PITCH) + PLEAD_REST['vt'][1] - 0.6 * abs(shake) / 7.0, -1.1 * lean / 14.0), pr=PLEAD_REST['pr'])
    key_frame(c, t, fr, ease='linear')
    cap_tied_arms(c, t, pull=0.22 * abs(shake) / 7.0)
    cap_legs(c, t)
    up = float(spline_keys(PLEAD_UP, t))
    peak = qmul(qeuler(z=5.0 * up), qeuler(x=-3.0, y=0.0))
    c.key('head', t, qslerp(q_rest_head, peak, smooth(up)), ease='linear')
c.mode('head', 'add')
cap_floor_check(c, 20, 't=20')
for tk_ in (0.0, 40.0):
    for side in ('right_arm', 'left_arm'):
        check(c, f'{side} hand at tick {tk_:g} is where bound has it at 0', fk(c, tk_, side, GRIP[side]),
              fk(getc('bound'), 0, side, GRIP[side]), tol=0.05)

# ---- thank (40): standing, the hands come up and clasp at the chest, a small bow of head and shoulders with a slight
# sway, then a step of relief (the right foot back and returned) as he straightens and the hands drop. Starts and ends in
# the standing pose.
THANK_HANG = np.array([-6.0, 11.5, -0.5])       # the right hand's end, hanging at the side (torso space)
THANK_CLASP = np.array([-1.5, 5.0, -7.6])       # clasped at the chest
THANK_OUT = np.array([-7.5, 7.5, -4.5])         # the way round: out to the side first
THANK_PITCH = [(0, 0.0), (6, 3.0), (10, 5.0), (14, 11.0), (18, 16.0), (21, 16.0), (25, 8.0), (28, -2.5), (32, -2.0), (36, 0.0), (40, 0.0)]
THANK_HEAD = [(0, 0.0), (8, 6.0), (14, 16.0), (19, 24.0), (22, 22.0), (27, -3.0), (32, -4.0), (37, -1.0), (40, 0.0)]    # extra to the bow, 'add'
THANK_ARMS = [(0, 0.0), (9, 1.0), (27, 1.0), (37, 0.0), (40, 0.0)]                  # how far the hands have come up
THANK_STEP = [(0, 0.0), (21, 0.0), (24, 12.0), (28, 14.0), (33, 4.0), (37, 0.0), (40, 0.0)]       # the right foot back (deg)
_THANK_PATH = {}


def thank_arm(s):
    """The right arm (key, offset) [s] of the way from hanging at the side (0) to the hands clasped at the chest (1):
    solved at steps of 1/16 (the hand along a curve out to the side and in), each warm-started from the one before, and
    blended between; the left arm is its mirror."""
    if not _THANK_PATH:
        _THANK_PATH[0] = (relaxed(F_(), 'right_arm'), np.zeros(3))
        prev = _THANK_PATH[0]
        for i in range(1, 17):
            u = i / 16.0
            target = (1 - u) ** 2 * THANK_HANG + 2 * (1 - u) * u * THANK_OUT + u * u * THANK_CLASP
            prev = cap_arm_to('right_arm', target, start=prev, palm=L if i > 10 else None, palm_w=0.3, max_off=3.0, w=4.0)
            _THANK_PATH[i] = prev
    k0 = min(15, int(math.floor(max(0.0, min(1.0, s)) * 16)))
    return cap_mix(_THANK_PATH[k0], _THANK_PATH[k0 + 1], max(0.0, min(1.0, s)) * 16 - k0)


c = clip('thank', 40, False, look=('citizen', 0), events=[('bow', 19)])
for t in ticks(0, 40, 1):
    pitch = float(spline_keys(THANK_PITCH, t))
    roll = 1.5 * bump(t, 22.0, 36.0) * math.sin(math.pi * (t - 22.0) / 14.0 * 2)
    key_frame(c, t, F_(qt=bend(pitch, 0.0, roll)), ease='linear')
    qa = thank_arm(smooth(float(spline_keys(THANK_ARMS, t))))
    c.key('right_arm', t, qa[0], pos=qa[1], ease='linear')
    c.key('left_arm', t, mirror_q(qa[0]), pos=mirror_dir(qa[1]), ease='linear')
    c.key('head', t, qeuler(x=pitch + float(spline_keys(THANK_HEAD, t))), ease='linear')
    th = float(spline_keys(THANK_STEP, t))
    low = 12.0 * math.cos(math.radians(th)) + 2.0 * math.sin(math.radians(th)) - 12.0     # the stepping leg, raised to clear the ground
    c.key('right_leg', t, qeuler(x=th), pos=(0.0, -max(0.0, low), 0.0), ease='linear')
    c.key('left_leg', t, I, ease='linear')
c.mode('head', 'add')
for tk_ in (0.0, 40.0):
    for side in ('right_leg', 'left_leg'):
        check(c, f'{side} foot on the ground (t={tk_:g})', (fk(c, tk_, side, (0.0, 12.0, 0.0))[1],), (24.0,), tol=0.4)

# ---- freed (50): the rope off. The arms come round (to the sides, then both hands in front) and rub one wrist then the
# other (18, 27), the head down at them; then the hands go to the thighs, he leans on them and gets up: the left leg (the
# one that bears him, the toe dragging) unfolds as the hips rise on it, the right steps through ahead of him, the feet
# come together, and he stands (`up` at 40), the arms swinging free and falling into `relaxed`.
FREED_REL, FREED_SIDE, FREED_CLASP, FREED_RUB1, FREED_SWAP, FREED_RUB2, FREED_PUSH, FREED_HOLD, FREED_SWING = 6.0, 9.0, 14.0, 22.0, 24.0, 30.0, 32.0, 35.0, 42.0
FREED_LEG_L = [(0, 90.0), (32, 90.0), (34, 80.0), (36, 64.0), (38, 46.0), (40, 30.0), (42, 16.0), (44, 6.0), (46, 0.0), (50, 0.0)]      # the bearing leg: toe dragging
FREED_LEG_R = [(0, 90.0), (33, 90.0), (35, 70.0), (37, 40.0), (38.5, 8.0), (40, -16.0), (41.5, -20.0), (43, -14.0), (45, -6.0), (47, 0.0), (50, 0.0)]   # the stepping leg
FREED_PITCH = [(0, 6.0), (6, 3.0), (14, 8.0), (22, 10.0), (30, 9.0), (33, 14.0), (36, 16.0), (39, 12.0), (42, 5.0), (45, 1.0), (47, 0.0), (50, 0.0)]
FREED_YAW = [(0, 0.0), (14, 0.0), (16, 3.0), (19, -3.0), (22, 2.0), (25, -2.0), (27, 3.0), (30, 0.0), (50, 0.0)]
FREED_HEAD = [(0, 18.0), (5, 2.0), (10, 4.0), (15, 26.0), (31, 28.0), (36, 10.0), (42, 0.0), (50, 0.0)]       # the head's pitch, 'add': down at his wrists, then up


def mono_keys(keys, t):
    """[keys] (t, value) interpolated monotonically (a cubic Hermite spline whose tangents never overshoot a key), at rest at both ends."""
    ks = [(float(a_), float(b_)) for a_, b_ in keys]
    if t <= ks[0][0]:
        return ks[0][1]
    if t >= ks[-1][0]:
        return ks[-1][1]
    n = len(ks)
    d = [(ks[i + 1][1] - ks[i][1]) / (ks[i + 1][0] - ks[i][0]) for i in range(n - 1)]
    m = [0.0] * n
    for i in range(1, n - 1):
        if d[i - 1] * d[i] > 0:
            h0, h1 = ks[i][0] - ks[i - 1][0], ks[i + 1][0] - ks[i][0]
            w1, w2 = 2 * h1 + h0, h1 + 2 * h0
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
    for i in range(n - 1):
        if ks[i][0] <= t <= ks[i + 1][0]:
            h = ks[i + 1][0] - ks[i][0]
            u = (t - ks[i][0]) / h
            u2, u3 = u * u, u * u * u
            return ((2 * u3 - 3 * u2 + 1) * ks[i][1] + (u3 - 2 * u2 + u) * m[i] * h
                    + (-2 * u3 + 3 * u2) * ks[i + 1][1] + (u3 - u2) * m[i + 1] * h)
    return ks[-1][1]


def freed_drop(theta):
    """How far the hips are down when a leg turned [theta] degrees back has its lowest corner on the ground (the leg's pivot
    carried down 2 sin(theta) under the torso): 12 - 12 cos - 4 sin."""
    th = math.radians(theta)
    return max(0.0, min(8.0, 12.0 - 12.0 * math.cos(th) - 4.0 * math.sin(th)))


def freed_leg(c, side, t, theta, drop):
    """A leg turned [theta] degrees back from standing (90: folded flat under him, the shin trailing; negative: the foot
    ahead), its splay drawn in as it comes up and the leg held up into the body as far as it takes to clear the ground
    while the hips are down [drop]."""
    th = math.radians(abs(theta))
    s_ = -1.0 if side == 'right_leg' else 1.0
    lowest = 12.0 * math.cos(th) + 2.0 * math.sin(th)
    ly = min(2.0 * math.sin(th), 24.0 - (12.0 + drop) - lowest)
    zf = -3.5 * math.sin(th) * (1.0 if theta >= 0 else 0.0)
    c.key(side, t, qeuler(x=theta, y=s_ * 7.0 * math.sin(th)), pos=(s_ * 0.8 * math.sin(th), ly, zf), ease='linear')


def freed_frame(t):
    """The torso and root: the hips follow the bearing leg up, the torso leans over the push and comes upright."""
    d_ = freed_drop(mono_keys(FREED_LEG_L, t))
    pitch = mono_keys(FREED_PITCH, t)
    roll = 1.2 * math.sin(0.8) * (1.0 - smooth(t / 6.0))          # the bound torso's slight roll at tick 0, let go
    return F_(qt=bend(pitch, mono_keys(FREED_YAW, t), roll), vt=(0.0, sink_for(pitch) * min(1.0, d_ / 8.0), 0.0), pr=(0.0, d_, 0.0))


c = clip('freed', 50, False, look=('citizen', 0), events=[('rub', 18), ('rub', 27), ('up', 40)])
# the arm poses (the right arm solved, the left its mirror): tied, hanging, out to the side, a hand over the other's
# wrist (the left over the right, sliding up and down it as they rub; then the right over the left), the hands on the thighs
# as the body is when they land, a swing, at rest
FREED_TIED = cap_tied(0.0)
FREED_K_TIED = ((FREED_TIED[0], FREED_TIED[1]), (FREED_TIED[2], FREED_TIED[3]))
FREED_K_HANG = (lambda p: (p, cap_mirror(p)))(cap_aim((0.02, 0.96, 0.28)))
FREED_K_SIDE = (lambda p: (p, cap_mirror(p)))(cap_aim((-0.55, 0.74, -0.38), off=(-1.2, 0.0, 0.0)))
FREED_KW = dict(palm_w=0.3, max_off=1.5, pen_w=4.0)
FREED_WRIST_R = cap_arm_to('right_arm', np.array([-1.7, 8.4, -5.6]), start=FREED_K_SIDE[0], palm=L, **FREED_KW)
FREED_RUB = {}
_prev = cap_mirror(FREED_K_SIDE[0])
for _s in (-1.0, 0.0, 1.0):
    FREED_RUB[_s] = cap_arm_to('left_arm', np.array([-0.4, 7.4 + 1.3 * _s, -6.3]), start=_prev, palm=R, **FREED_KW)
    _prev = FREED_RUB[_s]


def freed_rub(s):
    """The left arm [s] of the way up (-1) or down (1) the right wrist."""
    s = max(-1.0, min(1.0, s))
    return cap_mix(FREED_RUB[-1.0], FREED_RUB[0.0], s + 1.0) if s < 0 else cap_mix(FREED_RUB[0.0], FREED_RUB[1.0], s)


FREED_K_CLASP = (FREED_WRIST_R, freed_rub(0.0))                                   # the left hand over the right wrist
FREED_K_CLASP2 = (cap_mirror(freed_rub(0.0)), cap_mirror(FREED_WRIST_R))          # the right hand over the left wrist
FREED_FRAMES = {t: freed_frame(t) for t in ticks(0, 50, 1)}


def freed_push(side):
    """The arm with its hand on the thigh, just ahead of the knee, as the body is placed when the push begins."""
    s_ = -1.0 if side == 'right_arm' else 1.0
    return cap_arm_to(side, torso_local(FREED_FRAMES[FREED_PUSH], np.array([s_ * 3.4, 18.6, -4.2])), palm=None, max_off=1.5, pen_w=4.0)


FREED_K_PUSH = (freed_push('right_arm'), freed_push('left_arm'))
FREED_K_SWING = (lambda p: (p, cap_mirror(p)))(cap_aim((-0.22, 0.93, -0.30)))
FREED_K_RELAX = (lambda p: (p, cap_mirror(p)))((relaxed(F_(), 'right_arm'), np.zeros(3)))


def freed_arms(t):
    if t <= FREED_REL:
        return cap_pair_mix(FREED_K_TIED, FREED_K_HANG, smooth(t / FREED_REL))
    if t <= FREED_SIDE:
        return cap_pair_mix(FREED_K_HANG, FREED_K_SIDE, smooth((t - FREED_REL) / (FREED_SIDE - FREED_REL)))
    if t <= FREED_CLASP:
        return cap_pair_mix(FREED_K_SIDE, FREED_K_CLASP, smooth((t - FREED_SIDE) / (FREED_CLASP - FREED_SIDE)))
    if t <= FREED_RUB1:
        return (FREED_WRIST_R, freed_rub(math.sin(2 * math.pi * 1.5 * (t - FREED_CLASP) / (FREED_RUB1 - FREED_CLASP))))
    if t <= FREED_SWAP:
        return cap_pair_mix((FREED_WRIST_R, freed_rub(0.0)), FREED_K_CLASP2, smooth((t - FREED_RUB1) / (FREED_SWAP - FREED_RUB1)))
    if t <= FREED_RUB2:
        return (cap_mirror(freed_rub(math.sin(2 * math.pi * 1.25 * (t - FREED_SWAP) / (FREED_RUB2 - FREED_SWAP)))), cap_mirror(FREED_WRIST_R))
    if t <= FREED_PUSH:
        return cap_pair_mix(FREED_K_CLASP2, FREED_K_PUSH, smooth((t - FREED_RUB2) / (FREED_PUSH - FREED_RUB2)))
    if t <= FREED_HOLD:
        return FREED_K_PUSH
    if t <= FREED_SWING:
        return cap_pair_mix(FREED_K_PUSH, FREED_K_SWING, smooth((t - FREED_HOLD) / (FREED_SWING - FREED_HOLD)))
    return cap_pair_mix(FREED_K_SWING, FREED_K_RELAX, smooth((t - FREED_SWING) / (50.0 - FREED_SWING)))


for t in ticks(0, 50, 1):
    fr = FREED_FRAMES[t]
    key_frame(c, t, fr, ease='linear')
    pr_, pl_ = freed_arms(t)
    c.key('right_arm', t, pr_[0], pos=pr_[1], ease='linear')
    c.key('left_arm', t, pl_[0], pos=pl_[1], ease='linear')
    freed_leg(c, 'right_leg', t, mono_keys(FREED_LEG_R, t), float(fr['pr'][1]))
    freed_leg(c, 'left_leg', t, mono_keys(FREED_LEG_L, t), float(fr['pr'][1]))
    c.key('head', t, qeuler(x=mono_keys(FREED_HEAD, t)), ease='linear')
c.mode('head', 'add')
cap_floor_check(c, 0, 't=0')
for side in ('right_arm', 'left_arm'):
    check(c, f'{side} hand at tick 0 is where bound has it', fk(c, 0, side, GRIP[side]), fk(getc('bound'), 0, side, GRIP[side]), tol=0.05)
for side in ('right_leg', 'left_leg'):
    check(c, f'{side} foot on the ground (t=50)', (fk(c, 50, side, (0.0, 12.0, 0.0))[1],), (24.0,), tol=0.4)

# ================================================================== the captive: end


if __name__ == '__main__':
    main(sys.argv)
