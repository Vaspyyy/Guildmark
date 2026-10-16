"""
The four companions' clips: sitting at a Wardfire, standing up, going down and being helped up, nodding and frowning at what
the player does, loosing an arrow, and each one's own way of standing about. Same skeleton and kit as the townsfolk's clips
(animkit.py, townsfolk_anims.py: the companions wear the townsfolk model, role `companion`), played by the game's
CombatAnimator. Run from the repo root:

    python3 -B art/scripts/companion_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]

Writes assets/guildmark/animations/companion/<clip>.json for every clip, a contact sheet per clip (or only the clips named)
and, with no clips named, companion_moves.gif (all of them in a row) into <preview-dir>. The companions hold real items,
drawn by the game's item-in-hand layer, not townsfolk props: Bram an iron greatsword, Ysolde an iron censer, Kestrel a
vanilla bow (an iron dagger up close), Lucan an amethyst staff. The sheets draw each item the way the layer does (its
model's third-person display transform: weaponkit.py's geometry; set GUILDMARK_VANILLA_BOW to the vanilla client's
textures/item/bow.png to draw the real bow, a painted copy stands in without it), and every run checks the item against the
body (a blade through the head, a censer through a leg) and the ground, and each loop's seam, and prints what it found.

How a clip plays (see townsfolk_anims.py's header for CombatAnimator): the torso bends about the hips and carries the arms
and the head's pivot; an arm or leg track is relative to the torso ('set') or added to the pose vanilla gives that limb
('add': the walk's swing, or the bow pose's raised arms); the head adds to where the companion looks unless its mode is
'set' (then it is relative to the torso); a limb with no track keeps vanilla's pose (walking legs survive any gesture);
the root moves and turns everything about the hips. Every clip says which it drives:

    sit_fire       80  loop   root, torso, head, both arms, both legs   the whole body on the ground by a fire
    stand_up       20  once   root, torso, head, both arms, both legs   up off the ground from sit_fire's pose
    downed         28  once   root, torso, head, both arms, both legs   hit hard, on one knee; ticks 16 to 28 loop (held)
    revive         30  once   root, torso, head, both arms, both legs   from the downed pose, a hand taken, hauled up
    approve        24  once   torso, head, left arm (upper body, plays walking)
    disapprove     24  once   torso, head, left arm (upper body, plays walking)
    bow_release     8  once   both arms, added over the bow pose (BOW_AND_ARROW), nothing else
    idle_bram     100  loop   torso, head, both arms (the greatsword)
    idle_ysolde   100  loop   torso, head, both arms (the censer; the left hand to her brow)
    idle_kestrel  100  loop   torso, head, both arms (the bow, thumb on the string)
    idle_lucan    100  loop   torso, head, both arms (the book in the left hand, the staff in the right)

The idles and the gestures leave the legs and the root alone, so they play over a walk; every loop begins and ends at the same
easy stance, so a loop's seam (and the fade in and out at its ends) never shows.
"""
import math, os, sys
from collections import namedtuple
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from animkit import (Clip, qeuler, qmul, qaxis, qmat, qslerp, qfrommat, frame_to, unit, I, v, F, B, U, D, R, L,
                     pose_clip, vanilla_pose, raster, ortho, box_quads, T, Rq, S, apply, EASE, Geo, sprite_quads)
import townsfolk_anims as ta
from townsfolk_anims import (F_, bend, relaxed, free, rel, reach, solve_arm, body_solve, head_at, gaze_head, stand_legs,
                             stand_feet, chain_frame, hand_point, arm_world, arm_dir, head_point, solve_arm_slack, smooth,
                             bump, ticks, track, frame_at, key_frame, legs, PIVOTS, HIPS, GRIP, rvec_q, q_rvec, qinv,
                             spline_keys, interp_keys, torso_to_world, sink_for, level_arms, hands_at, leg_point, EYES,
                             mirror_q, mirror_dir, fit, seal, leg_corners, torso_local, clear_of_body)
import weaponkit

ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
GROUP = 'companion'
ASSETS = os.path.join(ROOT, 'src/main/resources/assets/guildmark')
GROUND = 24.0

# ================================================================== the companions and what they hold

# Where the vanilla bow's texture may be found when it isn't in the mod: the previews only need to show it
BOW_TEXTURES = [os.environ.get('GUILDMARK_VANILLA_BOW', '')]

# The vanilla bow sprite (16x16), painted from its map when the real one isn't to hand: W wood, w light wood, G grip, S string
_BOW_MAP = [
    '................', '...........wWWW.', '........WWWwWWWW', '......WWWWWWWWW.', '.....WGWWWWW..S.', '....WGGG....S...',
    '...WGGG....S....', '...WWG....S.....', '..WWW....S......', '..WWW...S.......', '..WWW..S........', '.WWW..S.........',
    '.WWW.S..........', '.WWWS...........', '.WWW............', '..W.............']
_BOW_RGB = {'W': (74, 52, 24), 'w': (128, 92, 40), 'G': (116, 116, 118), 'S': (70, 70, 72)}


def bow_texture():
    for path in BOW_TEXTURES:
        if path and os.path.exists(path):
            return Image.open(path).convert('RGBA')
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(_BOW_MAP):
        for x, ch in enumerate(row):
            if ch in _BOW_RGB:
                im.putpixel((x, y), _BOW_RGB[ch] + (255,))
    return im


def model_display(name):
    """thirdperson_righthand of models/item/<name>.json: (rotation, translation, scale), as the item layer applies it."""
    import json
    with open(os.path.join(ASSETS, 'models/item', name + '.json')) as f:
        d = json.load(f)['display']['thirdperson_righthand']
    return tuple(d['rotation']), tuple(d['translation']), tuple(d['scale'])


class Item:
    """A held item as the game draws it: its sprite, extruded a pixel, placed in the right hand by its model's third-person
    display transform. [geo] is weaponkit's geometry (grip, tip), [pts] named points of the sprite (item space 0..1, y up)."""

    def __init__(self, name, tex, geo, pts):
        self.name, self.tex, self.geo, self.pts = name, tex, geo, pts
        self.arr = np.array(tex.convert('RGBA'), float)
        self.size = tex.width
        self.mesh = None
        # the opaque texels' centres (item space), to test the real shape against the body
        a = self.arr[..., 3] > 100
        js, is_ = np.nonzero(a)
        self.texels = np.array([[(i + 0.5) / self.size, 1 - (j + 0.5) / self.size, 0.5] for j, i in zip(js, is_)])
        self._zone = None

    def pt(self, name):
        """A named point of the sprite in the arm's own frame (model px)."""
        p = self.pts[name]
        return self.geo.tp_point(np.array([p[0], p[1], 0.5]))

    def dir(self, a, b):
        return unit(self.pt(b) - self.pt(a))

    def quads(self, M_arm):
        """The item's quads (front and back faces, and the sides of every opaque texel) for an arm placed by [M_arm]."""
        M = M_arm @ S(16) @ self.geo.tp_item()
        if self.mesh is None:
            self.mesh = self._mesh()
        return [(apply(M, A), apply(M, Bq), apply(M, Dq), rect, light, self.arr) for A, Bq, Dq, rect, light in self.mesh]

    def _mesh(self):
        n, z0, z1 = self.size, 7.5 / 16, 8.5 / 16
        a = self.arr[..., 3] > 100
        mesh = []
        for z in (z0, z1):
            mesh.append((np.array([0.0, 1.0, z]), np.array([1.0, 1.0, z]), np.array([0.0, 0.0, z]), (0, 0, n, n), 0.95))
        for j in range(n):
            for i in range(n):
                if not a[j, i]:
                    continue
                x0, x1, yt, yb = i / n, (i + 1) / n, 1 - j / n, 1 - (j + 1) / n
                rect = (i, j, 1, 1)
                P = lambda x, y, z: np.array([x, y, z])
                if i == 0 or not a[j, i - 1]:
                    mesh.append((P(x0, yt, z0), P(x0, yt, z1), P(x0, yb, z0), rect, 0.75))
                if i == n - 1 or not a[j, i + 1]:
                    mesh.append((P(x1, yt, z0), P(x1, yt, z1), P(x1, yb, z0), rect, 0.8))
                if j == 0 or not a[j - 1, i]:
                    mesh.append((P(x0, yt, z0), P(x1, yt, z0), P(x0, yt, z1), rect, 1.0))
                if j == n - 1 or not a[j + 1, i]:
                    mesh.append((P(x0, yb, z0), P(x1, yb, z0), P(x0, yb, z1), rect, 0.55))
        return mesh

    def arm_points(self):
        """Every opaque texel's centre in the arm's frame (model px), for the collision test."""
        M = S(16) @ self.geo.tp_item()
        return np.array([apply(M, p) for p in self.texels])

    def blade_points(self):
        """The same, without the stretch round the grip that the hand itself fills (a hand resting on a knee or a
        shoulder presses the item's butt end into it; only the rest of the item is checked against the body)."""
        pts = self.arm_points()
        d = np.linalg.norm(pts - self.pt('grip'), axis=1)
        if self._zone is None:
            self._zone = min(8.0, 0.35 * float(d.max()))
        return pts[d > self._zone]


def _item(name, geo, pts, tex=None):
    tex = tex or Image.open(os.path.join(ASSETS, 'textures/item', name + '.png'))
    return Item(name, tex, geo, pts)


def _diag(s, w=0.0, size=32):
    return weaponkit.at(s, w, size)


def build_items():
    L_ = weaponkit.LAYOUT
    bow_geo = Geo('bow', ((-80, 260, -40), (-1, -2, 2.5), (0.9, 0.9, 0.9)), ((0, -90, 25), (1.13, 3.2, 1.13), (0.68, 0.68, 0.68)),
                  grip=(5.5 / 16, 1 - 5.5 / 16), tip=(15 / 16, 1 - 1.5 / 16))
    out = {}
    for name, key, geo in (('iron_greatsword', 'greatsword', weaponkit.GREATSWORD), ('iron_censer', 'censer', weaponkit.CENSER),
                           ('amethyst_staff', 'staff', weaponkit.STAFF), ('iron_dagger', 'dagger', weaponkit.DAGGER)):
        lay = L_[key]
        grip_s = lay['grip'] if np.isscalar(lay['grip']) else lay['grip'][0]
        pts = {'grip': _diag(grip_s), 'tip': _diag(lay['tip'][0], lay['tip'][1]), 'butt': _diag(0.5), 'mid': _diag((grip_s + lay['tip'][0]) / 2)}
        if 'second' in lay:
            pts['second'] = _diag(lay['second'])
        if key == 'greatsword':
            pts['guard'] = _diag(15.5)
        out[key] = _item(name, geo, pts)
        # the geometry the game uses is the model's own display: make sure weaponkit still agrees with it
        rot, trans, sc = model_display(name)
        g = geo.tp
        if max(abs(np.array(rot) - np.array(g[0])).max(), abs(np.array(trans) - np.array(g[1])).max(), abs(np.array(sc) - np.array(g[2])).max()) > 0.01:
            print(f'!! {name}: models/item/{name}.json no longer matches weaponkit.{key.upper()}: the sheets draw weaponkit\'s')
    out['bow'] = Item('bow', bow_texture(), bow_geo, {
        'grip': (5.5 / 16, 1 - 5.5 / 16), 'tip': (15 / 16, 1 - 1.5 / 16), 'lower': (1.8 / 16, 1 - 14.5 / 16),
        'string': (8.5 / 16, 1 - 8.5 / 16), 'belly': (4.5 / 16, 1 - 4.5 / 16)})
    return out


ITEMS = build_items()

# Who they are: the look each previews in (the companion looks once the art helper has painted them, else a stand-in
# with a like build), and what they hold
COMPANIONS = {
    'bram': dict(look=('companion', 0), stand_in=('knight', 0), item='greatsword'),
    'ysolde': dict(look=('companion', 1), stand_in=('priest', 0), item='censer'),
    'kestrel': dict(look=('companion', 2), stand_in=('thief', 1), item='bow'),
    'lucan': dict(look=('companion', 3), stand_in=('mage', 1), item='staff'),
}


def look_of(who):
    """The companion's look: role companion when townsfolk.py has it, else the stand-in."""
    spec = COMPANIONS[who]
    role, n = spec['look']
    try:
        import townsfolk as tf
        if role in getattr(tf, 'LOOKS', {}) and len(tf.LOOKS[role]) > n:
            return ta.look_for((role, n))
    except Exception as e:
        print(f'(townsfolk not importable just now: {e!r})')
    return ta.look_for(spec['stand_in'])


# ================================================================== what vanilla gives (the base the clips play over)

def bob(age):
    """AnimationUtils.bobModelPart: what the arms add at rest (z, x radians) for the right arm; the left's is the negative."""
    return math.cos(age * 0.09) * 0.05 + 0.05, math.sin(age * 0.067) * 0.05


def stand_base(t, walking=False, age=None):
    """Vanilla standing, or walking at the townsfolk's pace: arms hanging (the EMPTY pose), swinging with the legs."""
    return ta.walk_base(t, speed=0.55 if walking else 0.0, age=t if age is None else age)


def bow_base(t, walking=False, head=(0.0, 0.0), age=None):
    """HumanoidModel.poseRightArm for BOW_AND_ARROW with the bow in the right hand: both arms level ahead (xRot -90 degrees
    plus the head's pitch), the right a hair out (yRot -0.1 plus the head's turn), the left (the drawing arm) turned in 0.5
    radian; then the idle bob."""
    pose = ta.walk_base(t, speed=0.55 if walking else 0.0, age=t if age is None else age)
    age = t if age is None else age
    bz, bx = bob(age)
    hx, hy = math.radians(head[0]), math.radians(head[1])
    pose['head'] = (qeuler(x=head[0], y=head[1]), pose['head'][1])
    pose['right_arm'] = (qeuler(x=math.degrees(-math.pi / 2 + hx + bx), y=math.degrees(-0.1 + hy), z=math.degrees(bz)), pose['right_arm'][1])
    pose['left_arm'] = (qeuler(x=math.degrees(-math.pi / 2 + hx - bx), y=math.degrees(0.1 + hy + 0.4), z=-math.degrees(bz)), pose['left_arm'][1])
    return pose


# ================================================================== the registry

clips = []
INFO = {}


def cclip(name, length, loop, who, kind='stand', props=(), station_=None, walking=False, events=(), blend=(0.0, 0.0), rate=2,
          held=None, views=None, note='', cell=None, zoom=None, center=None, helper=None):
    """A clip and how to preview it: [who] holds the item ([kind] picks the pose vanilla gives under it: 'stand' or 'bow'),
    [props] the townsfolk props shown (the book), [station_] what is drawn round it (fire)."""
    c = Clip(name, length, blend_in=blend[0], blend_out=blend[1], rate=rate)
    for label, tick in events:
        c.mark(label, tick)
    INFO[name] = dict(who=who, kind=kind, props=set(props), station=station_, loop=loop, walking=walking, events=list(events),
                      held=held, views=views, checks=[], note=note, cell=cell, zoom=zoom, center=center, helper=helper)
    clips.append(c)
    return c


def getc(name):
    return next(c for c in clips if c.name == name)


def base_for(c, t, who=None, walking=None):
    info = INFO[c.name]
    walking = info['walking'] if walking is None else walking
    if info['kind'] == 'bow':
        return bow_base(t, walking)
    return stand_base(t, walking)


def pose_of(c, t, walking=None):
    """The posed skeleton at full strength (the previews show every key, not the fade)."""
    full = Clip(c.name, c.length, blend_in=0.0, blend_out=0.0, rate=c.rate)
    full.keys, full.modes = c.keys, c.modes
    return pose_clip(full, t, base=base_for(c, t, walking=walking), pivots=PIVOTS, hips=HIPS)


# ================================================================== collisions: the item against the body

BODY_BOXES = {'head': ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0)), 'body': ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)),
              'right_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)), 'left_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)),
              'right_arm': ((-3.0, -2.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, -2.0, -2.0), (3.0, 10.0, 2.0))}

# What each companion wears that an item could meet, boxes (bone, lo, hi, name) in the bone's own frame, from the cubes of
# townsfolk.py's _companion_pieces (the knife, the quiver, a tilted part, as the box round it)
DRESS = {
    'bram': [('left_arm', (-1.25, -3.5, -2.5), (4.25, -2.0, 2.5), 'pauldron'), ('left_arm', (-1.75, -2.25, -3.25), (4.75, 2.25, 3.25), 'pauldron'),
             ('left_arm', (-1.5, 1.75, -3.0), (4.5, 3.25, 3.0), 'pauldron'), ('body', (-5.0, -1.75, -3.25), (5.0, 1.75, 3.25), 'fur collar')],
    'ysolde': [('body', (1.4, 6.0, -4.4), (5.9, 10.5, -1.9), 'satchel'), ('body', (-1.0, 0.8, 1.9), (1.0, 11.3, 3.4), 'plait'),
               ('body', (-1.0, 3.4, -2.8), (1.0, 5.9, -1.8), 'lamp charm')],
    'kestrel': [('body', (-4.4, 6.5, -3.2), (-2.4, 13.5, -1.6), 'knife'), ('body', (-3.3, -1.5, 2.8), (0.4, 11.0, 5.8), 'quiver'),
                ('body', (-5.0, -0.5, 1.9), (5.0, 7.0, 2.9), 'short cape')],
    'lucan': [('body', (1.4, 6.0, -4.4), (5.9, 10.5, -1.9), 'satchel'), ('body', (0.6, 1.0, 1.9), (3.6, 11.5, 3.4), 'scarf')],
}
DRESS_NAMES = {name for boxes in DRESS.values() for _, _, _, name in boxes}


def mats_of(pose):
    """Each humanoid part's 4x4 in model space (as part_matrices, without a look)."""
    qr, pr = pose['root']
    Mroot = T(*pr) @ Rq(qr)
    out = {}
    for name in BODY_BOXES:
        q, p = pose[name if name != 'body' else 'body']
        out[name] = Mroot @ T(*p) @ Rq(q)
    return out


def depth_inside(M, box, pts):
    """How deep (px) each world point is inside a box placed by M (0 when outside)."""
    lo, hi = (np.array(b) for b in box)
    inv = np.linalg.inv(M)
    local = (inv[:3, :3] @ pts.T).T + inv[:3, 3]
    d = np.minimum(local - lo, hi - local).min(axis=1)
    return np.maximum(d, 0.0)


def item_world_points(item, pose, arm='right_arm', whole=False):
    mats = mats_of(pose)
    pts = item.arm_points() if whole else item.blade_points()
    return (mats[arm][:3, :3] @ pts.T).T + mats[arm][:3, 3]


def item_clearance(item, pose, skip=('right_arm',), margin=0.0, dress=()):
    """(deepest point inside the body px, which part, how far the item dips below the ground px); [dress]: the look's pieces
    (DRESS boxes) counted as body too."""
    pts = item_world_points(item, pose)
    mats = mats_of(pose)
    worst, part = 0.0, ''
    for name, box in BODY_BOXES.items():
        if name in skip:
            continue
        d = depth_inside(mats[name], box, pts).max()
        if d > worst:
            worst, part = float(d), name
    for bone, lo, hi, name in dress:
        d = depth_inside(mats[bone], (lo, hi), pts).max()
        if d > worst:
            worst, part = float(d), name
    below = max(0.0, float(pts[:, 1].max()) - GROUND)
    return worst, part, below


def limb_clearance(pose, limb, other, shrink=0.25, n=4):
    """How deep (px) a limb's box sinks into another part's box: a grid of points inside the limb, shrunk a little so a mere
    touch isn't counted (an arm passing through the head)."""
    lo, hi = (np.array(b) + s for b, s in zip(BODY_BOXES[limb], (shrink, -shrink)))
    g = np.linspace(0.0, 1.0, n)
    local = np.array([[lo[0] + (hi[0] - lo[0]) * a, lo[1] + (hi[1] - lo[1]) * b, lo[2] + (hi[2] - lo[2]) * c_] for a in g for b in g for c_ in g])
    mats = mats_of(pose)
    world = (mats[limb][:3, :3] @ local.T).T + mats[limb][:3, 3]
    return float(depth_inside(mats[other], BODY_BOXES[other], world).max())


# ================================================================== previews

FIRE_AT = np.array([0.0, GROUND, -30.0])     # the campfire's middle, on the ground two blocks ahead of a sitter


def station_quads(kind, t=0.0, helper=None):
    """What is drawn round the clip, as quads: the ground, and a campfire for the sitting clips, or the player helping a
    downed companion up (a figure of boxes with one arm out to where [helper] says their hand is at tick [t])."""
    q = []
    q += ta.box((-40, GROUND, -44), (40, GROUND + 30.0, 30), (86, 104, 70), True)
    if kind == 'fire':
        fx, fz = FIRE_AT[0], FIRE_AT[2]
        for (x0, z0, x1, z1) in ((-7, -2, 7, 2), (-2, -7, 2, 7)):          # two crossed logs and a third on top
            q += ta.box((fx + x0, GROUND - 3.0, fz + z0), (fx + x1, GROUND, fz + z1), (96, 66, 40), True)
        q += ta.box((fx - 6, GROUND - 5.5, fz - 1.5), (fx + 6, GROUND - 3.0, fz + 1.5), (84, 58, 36), True)
        q += ta.box((fx - 3.5, GROUND - 11.0, fz - 3.5), (fx + 3.5, GROUND - 5.5, fz + 3.5), (236, 120, 36), False)
        q += ta.box((fx - 2.0, GROUND - 15.0, fz - 2.0), (fx + 2.0, GROUND - 11.0, fz + 2.0), (255, 196, 70), False)
        q += ta.box((fx - 0.8, GROUND - 17.5, fz - 0.8), (fx + 0.8, GROUND - 15.0, fz + 0.8), (255, 240, 170), False)
    elif kind == 'friend':
        # the player standing ahead and to the companion's left, facing them: legs, body and head as boxes, one arm out
        z0, z1, x0 = -22.0, -18.0, 8.0
        q += ta.box((x0 - 5, 12, z0), (x0 + 5, 24, z1), (74, 78, 96), True)
        q += ta.box((x0 - 5, 0, z0), (x0 + 5, 12, z1), (60, 110, 150), True)
        q += ta.box((x0 - 4, -8, z0 - 0.5), (x0 + 4, 0, z1 + 0.5), (214, 170, 136), True)
        q += ta.box((x0 + 5, 0, z0), (x0 + 8, 10, z1), (60, 110, 150), True)             # their left arm at their side
        if helper is not None:
            hand = np.asarray(helper(t), float)
            shoulder = np.array([x0 - 3.5, 2.0, (z0 + z1) / 2])                          # their right arm, out toward the companion
            for k in range(7):
                p = shoulder + (hand - shoulder) * (k / 6.0)
                q += ta.box(p - np.array([1.6, 1.6, 1.6]), p + np.array([1.6, 1.6, 1.6]), (60, 110, 150) if k < 5 else (214, 170, 136), True)
    return q


def scene_quads(look, pose, who, props=(), station_=None, item=None, t=0.0, helper=None):
    mats = ta.part_matrices(look, pose)
    quads = ta.model_quads(look, mats, set(props))
    it = ITEMS[item or COMPANIONS[who]['item']]
    quads += it.quads(mats['right_arm'])
    quads += station_quads(station_, t, helper)
    return quads, mats


def render_cell(c, t, project, size, who=None, item=None, walking=None):
    info = INFO[c.name]
    who = who or info['who']
    look = look_of(who)
    pose = pose_of(c, t, walking)
    quads, mats = scene_quads(look, pose, who, info['props'], info['station'], item, t, info['helper'])
    return raster(quads, project, size, bg=(54, 60, 74)), pose


def frame_times(c, n=None):
    info = INFO[c.name]
    n = n or (13 if c.length >= 60 else 10)
    ts = [c.length * i / (n - 1) for i in range(n)]
    for _, tick in info['events']:
        j = min(range(len(ts)), key=lambda k: abs(ts[k] - tick))
        ts[j] = float(tick)
    return sorted(set(round(t, 2) for t in ts))



# ================================================================== building blocks

def leg_q(d, toe=None):
    """A leg ('set' key, relative to the root) pointing along world [d], its toes as near [toe] as it goes (by default
    forward when it hangs and up when it lies out flat)."""
    d = unit(d)
    if toe is None:
        k = min(1.0, max(0.0, float(d[1])))
        toe = k * F + (1.0 - k) * U
    return frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), d, np.asarray(toe, float))


def legs_to(c, t, fr, rfoot, lfoot, ease='inout', toes=(None, None)):
    """Both legs reaching from their hips (under the root of [fr]) to world points [rfoot] and [lfoot]: where the 12 px
    long leg ends (the middle of its bottom face)."""
    for side, foot, toe in (('right_leg', rfoot, toes[0]), ('left_leg', lfoot, toes[1])):
        hip = np.array(PIVOTS[side], float) + np.asarray(fr['pr'], float)
        d = np.asarray(foot, float) - hip
        n = float(np.linalg.norm(d))
        if abs(n - 12.0) > 0.4:
            print(f'   ({c.name} t={t}: {side} reaches {n:.1f} px, not 12)')
        c.key(side, t, leg_q(d, toe), ease=ease)


def flat_foot(side, fr, x_out, lift=0.0, level=None):
    """The end of a leg lying out forward from the hip with its end [x_out] px outward of the hip (negative: crossed in)
    and raised [lift] px: found from the 12 px length. [level]: the y the leg lies at (default the hip's own)."""
    s = -1.0 if side == 'right_leg' else 1.0
    hip = np.array(PIVOTS[side], float) + np.asarray(fr['pr'], float)
    y = hip[1] - lift if level is None else level
    dx, dy = s * x_out, y - hip[1]
    dz = -math.sqrt(max(0.0, 144.0 - dx * dx - dy * dy))
    return np.array([hip[0] + dx, y, hip[2] + dz])


def stood_foot(side, fr, x_out=0.4, z=0.0):
    """The foot flat on the ground ([z] ahead of the hip's own z), [x_out] outward of it, for hips as the frame has them."""
    s = -1.0 if side == 'right_leg' else 1.0
    hip = np.array(PIVOTS[side], float) + np.asarray(fr['pr'], float)
    return np.array([hip[0] + s * x_out, 24.0, hip[2] + z])


def planted(side, fr, x_out, along):
    """The foot on the ground [along] px ahead of the hip (negative: behind it), the leg's length 12 fixing how far it
    reaches (the hips' height above the ground is fr['pr'][1] + 12 below...), [x_out] outward: the world point."""
    s = -1.0 if side == 'right_leg' else 1.0
    hip = np.array(PIVOTS[side], float) + np.asarray(fr['pr'], float)
    dy = 24.0 - hip[1]
    dz = math.sqrt(max(0.0, 144.0 - dy * dy - x_out * x_out))
    return np.array([hip[0] + s * x_out, 24.0, hip[2] - np.sign(along) * dz])


def view_for(spec, size, center_y, zoom):
    """A camera: spec is (yaw, pitch[, centre y[, zoom[, (x, z) the camera looks at]]]); None leaves a default."""
    yaw, pitch = spec[0], spec[1]
    cy = spec[2] if len(spec) > 2 and spec[2] is not None else center_y
    zm = spec[3] if len(spec) > 3 and spec[3] is not None else zoom
    pan = np.array([spec[4][0], 0.0, spec[4][1]]) if len(spec) > 4 and spec[4] is not None else np.zeros(3)
    base = ortho(yaw, pitch, zm, size, cy if pitch > -60 else 0.0)
    if not pan.any():
        return base
    return lambda p: base(np.asarray(p, float) - pan)


def sheet(c, times=None, views=None, cell=None, zoom=None, center_y=None, who=None, item=None, title_extra=''):
    info = INFO[c.name]
    cell = cell or info['cell'] or (200, 250)
    zoom = zoom or info['zoom'] or 4.0
    center_y = center_y if center_y is not None else (info['center'] if info['center'] is not None else 6.0)
    who = who or info['who']
    times = times or frame_times(c)
    views = views or info['views'] or ((32, 10), (90, 2))
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 20), (30, 32, 40))
    d = ImageDraw.Draw(out)
    ev = ' '.join(f'{name}@{tick}' for name, tick in info['events'])
    look = look_of(who)
    kind = 'textured' if look.real else 'flat' if look.real_geometry else 'stand-in'
    d.text((4, 4), f'{c.name}  {c.length}t {"loop" if info["loop"] else "once"}{" walking" if info["walking"] else ""}  {ev}  '
           f'[{who}: {ITEMS[item or COMPANIONS[who]["item"]].name}; {look.role} {look.n}, {kind}] {title_extra}', fill=(235, 235, 235))
    for i, t in enumerate(times):
        for j, view in enumerate(views):
            project = view_for(view, (W, H), center_y, zoom)
            img, pose = render_cell(c, t, project, (W, H), who=who, item=item)
            out.paste(img, (i * W, 20 + j * H))
        label = f't={t:g}'
        for name, tick in info['events']:
            if abs(tick - t) < 1e-6:
                label += f'  {name}!'
        d.text((i * W + 4, 23), label, fill=(255, 255, 160) if '!' in label else (255, 255, 255))
        d.line([(i * W, 20), (i * W, out.height)], fill=(20, 20, 26))
    return out


# ================================================================== key poses, solved for every sample

ITEM_AXIS = np.array([0.0, 0.16, -0.99])           # a held blade (staff, rod) along the arm's own axes: forward, a little down
SCALARS = ('pitch', 'yaw', 'roll', 'dx', 'dz', 'drop', 'sink')
Sol = namedtuple('Sol', 'fr legs ra la head rpos lpos')      # a solved pose: frame, legs {name: q}, arms, head, shoulder offsets


class P:
    """One key pose: the body (the torso's lean: pitch forward, yaw, roll; the root's step dx, dz and its sink [drop]; the
    torso's own [sink], by default what a bent back wants) and where each limb goes, as descriptors that are solved afresh
    under the body of every sample, so a hand stays on its knee as the knee rises:

        arms  ('hang', out, fwd)                    hanging easy (degrees out and forward)
              ('dir', direction, front)             pointing along a world direction, its front face to [front]
              ('at', point, front[, weight[, p]])   the fist (or arm point [p]) on a world point
              ('thigh', leg, along, lift, front)    on top of a leg [along] px down it
              ('rest', leg, along, lift, dirn[, w])  the same, an item's length lying along world direction [dirn]
              ('q', quaternion)                     the 'set' key itself
        legs  ('stand', x_out, z)                   flat on the ground under the hip
              ('plant', x_out, dirn)                on the ground ahead (dirn < 0) or behind (> 0), as far as 12 px reach
              ('flat', x_out, lift)                 lying out forward, [x_out] outward (negative: crossed in), end lifted
              ('aim', direction[, toe])             along a world direction
        head  ('rel', x, y, z)                      turned degrees from the torso
              ('gaze', point[, pitch[, roll]])      the eyes on a world point, tipped a little further
    """

    def __init__(self, pitch=0.0, yaw=0.0, roll=0.0, dx=0.0, dz=0.0, drop=0.0, sink=None, ra=('hang', 4.0, 3.0),
                 la=('hang', 4.0, 3.0), head=('rel', 0.0, 0.0, 0.0), rf=('stand', 0.4, 0.0), lf=('stand', 0.4, 0.0),
                 ease='inout', rpos=(0.0, 0.0, 0.0), lpos=(0.0, 0.0, 0.0)):
        self.rpos, self.lpos = np.asarray(rpos, float), np.asarray(lpos, float)
        self.pitch, self.yaw, self.roll, self.dx, self.dz, self.drop = pitch, yaw, roll, dx, dz, drop
        self.sink = 0.5 * sink_for(pitch) if sink is None else sink
        self.ra, self.la, self.head, self.rf, self.lf, self.ease = ra, la, head, rf, lf, ease
        self._sink_auto = sink is None

    def but(self, **kw):
        d = {k: getattr(self, k) for k in ('pitch', 'yaw', 'roll', 'dx', 'dz', 'drop', 'ra', 'la', 'head', 'rf', 'lf', 'ease', 'rpos', 'lpos')}
        d['sink'] = None if self._sink_auto else self.sink
        d.update(kw)
        return P(**d)


def frame_of(v):
    return F_(qt=bend(v['pitch'], v['yaw'], v['roll']), vt=(0.0, v['sink'], 0.0), pr=(v['dx'], v['drop'], v['dz']))


def plant_leg(side, fr, x_out, dirn):
    """A leg whose far end rests on the ground (its lowest corner at y = 24), [dirn] < 0 ahead of the hip, > 0 behind it."""
    s = -1.0 if side == 'right_leg' else 1.0
    hip = np.array(PIVOTS[side], float) + np.asarray(fr['pr'], float)
    y = 24.0
    q = I
    for _ in range(5):
        dy = y - hip[1]
        dz = math.sqrt(max(0.0, 144.0 - dy * dy - x_out * x_out))
        foot = np.array([hip[0] + s * x_out, y, hip[2] + (-1.0 if dirn < 0 else 1.0) * dz * (0.0 if dirn == 0 else 1.0)])
        q = leg_q(foot - hip)
        low = max(cn[1] for cn in leg_corners(side, q, fr))
        if abs(low - 24.0) < 0.02:
            break
        y += 24.0 - low
    return q


def eval_leg(side, d, fr):
    s = -1.0 if side == 'right_leg' else 1.0
    kind = d[0]
    hip = np.array(PIVOTS[side], float) + np.asarray(fr['pr'], float)
    if kind == 'stand':
        foot = stood_foot(side, fr, d[1], d[2])
        n = float(np.linalg.norm(foot - hip))
        if n > 12.4:
            print(f'   (a {side} would have to stretch to {n:.1f} px)')
        return leg_q(foot - hip)
    if kind == 'plant':
        return plant_leg(side, fr, d[1], d[2])
    if kind == 'flat':
        return leg_q(flat_foot(side, fr, d[1], lift=d[2]) - hip)
    if kind == 'aim':
        return leg_q(d[1], d[2] if len(d) > 2 else None)
    if kind == 'q':
        return d[1]
    raise ValueError(kind)


def eval_arm(side, d, fr, legs, guess=None):
    """The arm's 'set' key for descriptor [d] under frame [fr] (whose 'arm_pos', if any, is this shoulder's offset)."""
    kind = d[0]
    if kind == 'hang':
        return relaxed(fr, side, out=d[1], fwd=d[2])
    if kind == 'dir':
        return free(fr, d[1], d[2] if len(d) > 2 else None)
    if kind == 'at':
        w = d[3] if len(d) > 3 and d[3] is not None else 0.3
        p = d[4] if len(d) > 4 else None
        return reach(side, fr, np.asarray(d[1], float), palm=d[2], palm_w=w, guess=guess, p=p)
    if kind == 'thigh':
        leg, along, lift, front = d[1], d[2], d[3], d[4]
        hip = np.array(PIVOTS[leg], float) + np.asarray(fr['pr'], float)
        Rl = qmat(legs[leg])
        target = hip + Rl @ np.array([0.0, along, 0.0]) + Rl @ np.array([0.0, 0.0, -1.0]) * (4.0 + lift)
        return reach(side, fr, target, palm=front, palm_w=0.3, guess=guess)
    if kind == 'rest':
        leg, along, lift, blade = d[1], d[2], d[3], d[4]
        hip = np.array(PIVOTS[leg], float) + np.asarray(fr['pr'], float)
        Rl = qmat(legs[leg])
        target = hip + Rl @ np.array([0.0, along, 0.0]) + Rl @ np.array([0.0, 0.0, -1.0]) * (4.0 + lift)
        q, _ = solve_arm(side, fr, [(GRIP[side], target, 1.0)], [(ITEM_AXIS, unit(blade), d[5] if len(d) > 5 else 1.0)], guess=guess)
        return q
    if kind == 'q':
        return d[1]
    if kind == 'fn':
        return d[1](fr, legs, guess)
    raise ValueError(kind)


def eval_head(d, fr):
    if d[0] == 'rel':
        return qeuler(x=d[1], y=d[2], z=d[3])
    if d[0] == 'gaze':
        return gaze_head(fr, np.asarray(d[1], float), extra=qeuler(x=d[2] if len(d) > 2 else 0.0, z=d[3] if len(d) > 3 else 0.0))
    raise ValueError(d[0])


def solve_between(a, b, f, cache, tag=''):
    """The pose a fraction [f] of the way from key pose [a] to [b]: the body's numbers blend, then every limb is solved for
    both keys under that body and the two blended. Returns (frame, legs, right arm, left arm, head)."""
    v_ = {k: getattr(a, k) + (getattr(b, k) - getattr(a, k)) * f for k in SCALARS}
    fr = frame_of(v_)
    legs = {}
    for side, attr in (('right_leg', 'rf'), ('left_leg', 'lf')):
        da, db = getattr(a, attr), getattr(b, attr)
        qa = eval_leg(side, da, fr)
        legs[side] = qa if da is db or f <= 0 else qslerp(qa, eval_leg(side, db, fr), f)
    arms, poss = {}, {}
    for side, attr, pos_attr in (('right_arm', 'ra', 'rpos'), ('left_arm', 'la', 'lpos')):
        da, db = getattr(a, attr), getattr(b, attr)
        pos = getattr(a, pos_attr) + (getattr(b, pos_attr) - getattr(a, pos_attr)) * f
        poss[side] = pos
        frm = dict(fr, arm_pos=pos)
        qa = eval_arm(side, da, frm, legs, cache.get((tag, id(da), side)))
        cache[(tag, id(da), side)] = qa
        if da is db or f <= 0:
            arms[side] = qa
        else:
            qb = eval_arm(side, db, frm, legs, cache.get((tag, id(db), side)))
            cache[(tag, id(db), side)] = qb
            arms[side] = qslerp(qa, qb, f)
    ha, hb = eval_head(a.head, fr), eval_head(b.head, fr)
    head = ha if a.head is b.head or f <= 0 else qslerp(ha, hb, f)
    return Sol(fr, legs, arms['right_arm'], arms['left_arm'], head, poss['right_arm'], poss['left_arm'])


def key_solved(c, t, sol, ease='linear', legs=True, root=True, arms=('right_arm', 'left_arm'), head=True, torso=True, head_mode='set'):
    """Keys a solved pose (frame, legs, right arm, left arm, head) into the clip, only the bones asked for. A head keyed for
    'add' mode (on top of where the companion looks) is made torso-relative the way a 'set' head already is, taking the
    look as straight ahead."""
    fr, lg = sol.fr, sol.legs
    key_frame(c, t, fr, ease=ease, root=root, torso=torso)
    if 'right_arm' in arms:
        c.key('right_arm', t, sol.ra, pos=sol.rpos if (c.has_pos('right_arm') or sol.rpos.any()) else None, ease=ease)
    if 'left_arm' in arms:
        c.key('left_arm', t, sol.la, pos=sol.lpos if (c.has_pos('left_arm') or sol.lpos.any()) else None, ease=ease)
    if head:
        c.key('head', t, sol.head if head_mode == 'set' else qmul(fr['qt'], sol.head), ease=ease)
    if legs:
        c.key('right_leg', t, lg['right_leg'], ease=ease)
        c.key('left_leg', t, lg['left_leg'], ease=ease)


def solve_one(p, cache=None, tag=''):
    return solve_between(p, p, 0.0, {} if cache is None else cache, tag)


def bake_poses(c, keys, step=1.0, tag='', **bones):
    """Samples the key poses [(t, P)] every [step] ticks (and at every key) and keys the clip linearly through them. [bones]
    are key_solved's options (legs, root, arms, head, torso, head_mode) for clips that leave some bones alone."""
    keys = sorted(keys, key=lambda k: k[0])
    times = sorted(set([round(k[0], 4) for k in keys] + [round(x, 4) for x in ticks(keys[0][0], keys[-1][0], step)]))
    cache = {}
    for t in times:
        j = 0
        while j < len(keys) - 2 and t > keys[j + 1][0] + 1e-9:
            j += 1
        (ta_, pa), (tb, pb) = keys[j], keys[j + 1]
        x = 0.0 if tb <= ta_ else min(1.0, max(0.0, (t - ta_) / (tb - ta_)))
        f = EASE[pb.ease](x)
        key_solved(c, t, solve_between(pa, pb, f, cache, tag), **bones)
    c.mode('head', bones.get('head_mode', 'set'))
    return c


# ================================================================== sit_fire: on the ground by a campfire

SIT_DROP = 10.0                                   # the hips this far down: the thighs lie on the ground
FIRE_HEART = FIRE_AT + np.array([0.0, -8.0, 0.0])  # where the flames are brightest
ARM_END = {'right_arm': (-1.0, 11.6, 0.0), 'left_arm': (1.0, 11.6, 0.0)}     # the middle of an arm's end
ITEM_REST = v(f=0.6, u=0.35, r=0.8)                # the item's own direction at rest: low, forward and out to the right
ITEM_RISE = v(f=0.7, u=0.8, r=0.5)                # and as its holder gets up: lifted clear of the ground
CROSSED = (('flat', -3.9, 0.0), ('flat', -4.4, 3.0))     # legs out toward the fire, the ankles crossed (the left over the right)

SIT = P(pitch=14.0, drop=SIT_DROP, rf=CROSSED[0], lf=CROSSED[1], ra=('rest', 'right_leg', 6.0, 0.0, ITEM_REST),
        la=('thigh', 'left_leg', 6.0, 0.0, U), head=('gaze', FIRE_HEART, 0.0, 0.0))
SIT_WARM = SIT.but(pitch=19.0, ra=('dir', v(f=1.0, u=-0.18, r=0.1), R), la=('dir', v(f=1.0, u=-0.18, r=-0.1), U))
SIT_WARM_L = SIT.but(pitch=17.0, la=('dir', v(f=1.0, u=-0.2, r=-0.05), U))
GLANCE_AT = np.array([6.0, -12.0, -46.0])

c = cclip('sit_fire', 80, True, 'bram', station_='fire', blend=(8.0, 8.0), cell=(250, 250), zoom=5.0, center=9.0,
          views=((32, 10, None, None, (0, -12)), (90, 2, None, 4.6, (0, -14))))
_cache = {}
for t in ticks(0, 80, 2):
    breath = math.sin(2 * math.pi * t / 40.0)
    sway = math.sin(2 * math.pi * t / 80.0)
    w1, w2 = smooth(bump(t, 10, 38) * 1.6), smooth(bump(t, 54, 70) * 1.6)
    A = SIT.but(pitch=14.0 + 1.1 * breath, roll=1.2 * sway)
    B = SIT_WARM.but(pitch=19.0 + 1.1 * breath, roll=1.2 * sway)
    B2 = SIT_WARM_L.but(pitch=17.0 + 1.1 * breath, roll=1.2 * sway)
    # two hands to the fire, then a while later one
    sol = solve_between(A, B, w1, _cache, 'w1') if w1 > 0 or w2 == 0 else solve_between(A, B2, w2, _cache, 'w2')
    if w2 > 0:
        sol = solve_between(A, B2, w2, _cache, 'w2')
    g = smooth(bump(t, 42, 58) * 1.5)
    qf = eval_head(('gaze', FIRE_HEART, -3.0 * breath, 0.0), sol.fr)
    qg = eval_head(('gaze', GLANCE_AT, 0.0, -4.0), sol.fr)
    key_solved(c, t, sol._replace(head=qslerp(qf, qg, g)))
c.mode('head', 'set')
seal(c)


# ================================================================== stand_up: pushing off a knee, up from the ground

HORIZON = np.array([0.0, 4.0, -70.0])

c = cclip('stand_up', 20, False, 'kestrel', station_='fire', blend=(0.0, 0.0), cell=(250, 250), zoom=5.0, center=9.0,
          views=((32, 10, None, None, (0, -12)), (90, 2, None, 4.6, (0, -14))))
STAND_UP = [
    (0.0, SIT),
    (3.0, SIT.but(pitch=30.0, drop=9.4, dz=-1.0, rf=('plant', 1.5, -1), lf=('plant', 1.5, -1),
                  ra=('rest', 'right_leg', 6.5, 0.0, ITEM_RISE), la=('thigh', 'left_leg', 6.5, 0.0, U),
                  head=('gaze', np.array([0.0, 8.0, -30.0]), 0.0, 0.0))),
    (7.0, SIT.but(pitch=44.0, drop=6.5, dz=-2.0, rf=('plant', 1.5, -1), lf=('plant', 1.5, -1),
                  ra=('rest', 'right_leg', 7.5, 0.0, ITEM_RISE), la=('thigh', 'left_leg', 7.5, 0.0, U),
                  head=('gaze', np.array([0.0, 6.0, -40.0]), 0.0, 0.0))),
    (11.0, SIT.but(pitch=38.0, drop=3.5, dz=-2.0, rf=('plant', 1.2, -1), lf=('plant', 1.2, -1),
                   ra=('rest', 'right_leg', 8.5, 0.0, ITEM_RISE), la=('thigh', 'left_leg', 8.5, 0.0, U),
                   head=('gaze', HORIZON, 0.0, 0.0))),
    (14.0, SIT.but(pitch=22.0, drop=1.4, dz=-1.0, rf=('plant', 0.8, -1), lf=('plant', 0.8, -1),
                   ra=('hang', 5.0, 8.0), la=('hang', 5.0, 8.0), head=('gaze', HORIZON, 0.0, 0.0))),
    (17.0, SIT.but(pitch=7.0, drop=0.3, rf=('plant', 0.5, -1), lf=('plant', 0.5, -1), ra=('hang', 4.0, 4.0),
                   la=('hang', 4.0, 4.0), head=('rel', 0.0, 0.0, 0.0))),
    (20.0, P(rf=('stand', 0.4, 0.0), lf=('stand', 0.4, 0.0))),
]
bake_poses(c, STAND_UP, 1.0, 'stand_up')


# ================================================================== downed: struck down onto one knee, a hand on the ground

BACK_LEG = ('aim', v(f=-0.34, u=-0.94, r=0.0))                       # the left leg down and a little back, its lower half in the ground
KNEEL_FEET = dict(rf=('plant', 0.8, -1), lf=BACK_LEG)                 # the right foot ahead, the left knee on the ground
GROUND_HAND = np.array([4.6, 23.2, -13.0])                          # where the left hand rests, ahead of the left knee
R_LIMP = ('dir', v(f=0.4, u=-1.0, r=-0.12), F)                       # the sword arm hanging, the weapon trailing

HIT = P(pitch=-10.0, roll=4.0, dz=1.2, drop=0.4, rf=('stand', 1.4, 0.5), lf=('stand', 1.4, -0.5),
        ra=('dir', v(f=-0.1, u=-0.15, r=1.0), F), la=('dir', v(f=-0.1, u=-0.1, r=-1.0), F), head=('rel', -24.0, 10.0, 6.0))
BUCKLE = P(pitch=16.0, roll=2.0, dz=0.4, drop=2.8, rf=('plant', 1.0, -1), lf=('plant', 0.6, +1), ra=('dir', v(f=0.3, u=-1.0, r=0.3), F),
           la=('at', GROUND_HAND + np.array([0.0, -7.0, 3.0]), None, 0.0, ARM_END['left_arm']), head=('rel', 16.0, 4.0, 0.0))
LOW = P(pitch=36.0, yaw=4.0, roll=3.0, dz=0.0, drop=4.6, ra=R_LIMP, la=('at', GROUND_HAND, None, 0.0, ARM_END['left_arm']),
        head=('rel', 28.0, 4.0, 2.0), **KNEEL_FEET)
HELD = P(pitch=48.0, yaw=6.0, roll=6.0, dz=0.0, drop=6.0, ra=R_LIMP, la=('at', GROUND_HAND, None, 0.0, ARM_END['left_arm']),
         head=('rel', 34.0, 6.0, 3.0), **KNEEL_FEET)
HELD_IN = HELD.but(pitch=49.6, roll=6.6, drop=6.1, head=('rel', 35.5, 6.0, 2.4))      # the chest rising with a breath

c = cclip('downed', 28, False, 'bram', station_=None, blend=(0.0, 0.0), cell=(250, 250), zoom=5.4, center=9.0, held=(16.0, 28.0),
          views=((32, 10, None, None, (0, -3)), (90, 2, None, None, (0, -3))))
DOWNED = [
    (0.0, P()),
    (2.5, HIT.but(ease='out')),
    (5.5, BUCKLE.but(ease='inout')),
    (9.5, LOW.but(ease='in')),
    (13.0, HELD.but(ease='out')),
    (16.0, HELD.but(ease='inout')),
    (22.0, HELD_IN.but(ease='inout')),
    (28.0, HELD.but(ease='inout')),
]
bake_poses(c, DOWNED, 1.0, 'downed')


def seal_held(c, a, b):
    """The held stretch [a, b] of a one-shot is played as a loop: make its last key exactly its first."""
    for bone, keys in c.keys.items():
        ka = next(k for k in keys if abs(k[0] - a) < 1e-6)
        for i, k in enumerate(keys):
            if abs(k[0] - b) < 1e-6:
                keys[i] = (k[0], ka[1].copy(), None if ka[2] is None else ka[2].copy(), k[3])


seal_held(c, 16.0, 28.0)


# ================================================================== revive: a hand taken, hauled up, a shake of the head

HELPER_PATH = [(0.0, (5.0, 9.5, -16.5)), (6.0, (5.0, 9.0, -16.0)), (9.0, (4.8, 8.0, -15.5)), (14.0, (4.6, 5.2, -14.0)),
               (19.0, (4.4, 3.6, -12.0)), (23.0, (4.2, 3.4, -10.5)), (30.0, (4.2, 3.4, -10.5))]


def helper_hand(t):
    """Where the player's hand is, held out to the fallen and then drawing them up."""
    return spline_keys([(a, np.array(b)) for a, b in HELPER_PATH], min(max(t, 0.0), 30.0))


c = cclip('revive', 30, False, 'bram', station_='friend', blend=(0.0, 0.0), cell=(250, 250), zoom=5.0, center=8.0, helper=helper_hand,
          views=((32, 10, None, None, (-4, -9)), (90, 2, None, None, (0, -9))))


def taken(t, dz=0.0):
    return ('at', helper_hand(t) + np.array([0.0, 0.0, dz]), None, 0.0, None)


REVIVE = [
    (0.0, HELD),
    (4.0, HELD.but(pitch=44.0, roll=3.0, drop=6.0, la=taken(4.0), head=('gaze', np.array([0.0, -2.0, -19.0]), -4.0, 0.0), ease='inout')),
    (8.0, HELD.but(pitch=36.0, yaw=2.0, roll=0.0, drop=5.6, la=taken(8.0), head=('gaze', np.array([0.0, -2.0, -19.0]), -2.0, 0.0), ease='inout')),
    (13.0, HELD.but(pitch=24.0, yaw=0.0, roll=0.0, drop=3.6, la=taken(13.0), ra=('dir', v(f=0.3, u=-1.0, r=-0.1), F),
                    rf=('plant', 0.8, -1), lf=('plant', 0.6, +1), head=('gaze', np.array([0.0, -2.0, -19.0]), 0.0, 0.0), ease='inout')),
    (18.0, P(pitch=9.0, drop=1.2, rf=('stand', 0.5, 0.0), lf=('stand', 0.5, 0.0), la=taken(18.0), ra=('hang', 5.0, 8.0),
             head=('gaze', np.array([0.0, -2.0, -19.0]), 0.0, 0.0), ease='inout')),
    (22.0, P(pitch=-2.0, drop=0.0, la=taken(22.0), ra=('hang', 4.0, 3.0), head=('rel', 4.0, 0.0, 0.0), ease='inout')),
    (24.5, P(pitch=1.5, la=('hang', 4.0, 3.0), head=('rel', 3.0, 15.0, 2.0), ease='inout')),
    (27.0, P(pitch=0.0, head=('rel', 1.0, -13.0, -2.0), ease='inout')),
    (30.0, P(head=('rel', 0.0, 0.0, 0.0), ease='inout')),
]
bake_poses(c, REVIVE, 1.0, 'revive')


# ================================================================== approve and disapprove: upper body, played over a walk

HANG = ('hang', 4.0, 3.0)
UPPER = dict(legs=False, root=False, arms=('left_arm',), head_mode='add')      # torso, head (on top of where they look), left arm


def head_nod(x=0.0, y=0.0, z=0.0):
    return ('rel', x, y, z)


c = cclip('approve', 24, False, 'ysolde', walking=True, blend=(3.0, 4.0), cell=(220, 250), zoom=4.6, center=7.0,
          views=((32, 10), (90, 2)))
FIST = ('dir', v(f=0.62, u=0.95, r=-0.28), D)           # the left fist up in front of the shoulder
approve = [
    (0.0, P(ra=HANG, la=HANG, head=head_nod(0, 0, 0))),
    (3.0, P(pitch=-3.0, la=('dir', v(f=0.8, u=0.15, r=-0.35), D), head=head_nod(-5, 0, 0), ease='inout')),
    (6.5, P(pitch=4.5, la=FIST, head=head_nod(15, 0, 0), ease='out')),
    (9.0, P(pitch=1.0, la=FIST, head=head_nod(1, 0, 0), ease='inout')),
    (11.5, P(pitch=3.0, la=('dir', v(f=0.72, u=0.5, r=-0.3), D), head=head_nod(11, 0, 0), ease='inout')),
    (14.0, P(pitch=0.5, la=FIST, head=head_nod(0, 0, 0), ease='inout')),
    (17.0, P(pitch=-1.0, la=FIST, head=head_nod(-3, 0, 0), ease='inout')),
    (21.0, P(pitch=0.0, la=('dir', v(f=0.8, u=0.0, r=-0.3), D), head=head_nod(0, 0, 0), ease='inout')),
    (24.0, P(la=HANG, head=head_nod(0, 0, 0), ease='inout')),
]
bake_poses(c, approve, 1.0, 'approve', **UPPER)

c = cclip('disapprove', 24, False, 'lucan', walking=True, blend=(3.0, 4.0), cell=(220, 250), zoom=4.6, center=7.0,
          views=((32, 10), (90, 2)))
OUT_L = lambda f: ('dir', v(f=f, u=0.3, r=-1.0), D)       # the left hand swept away to the side, palm down
IN_L = ('dir', v(f=0.9, u=0.3, r=-0.35), D)
disapprove = [
    (0.0, P(ra=HANG, la=HANG, head=head_nod(0, 0, 0))),
    (3.0, P(pitch=-3.5, yaw=-3.0, la=IN_L, head=head_nod(4, 18, 3), ease='inout')),
    (5.5, P(pitch=-1.0, yaw=3.0, la=OUT_L(0.5), head=head_nod(5, -18, -3), ease='inout')),
    (8.0, P(pitch=-2.5, yaw=-3.0, la=IN_L, head=head_nod(5, 16, 3), ease='inout')),
    (10.5, P(pitch=0.0, yaw=3.0, la=OUT_L(0.5), head=head_nod(6, -14, -3), ease='inout')),
    (13.0, P(pitch=1.0, yaw=-2.0, la=IN_L, head=head_nod(6, 10, 2), ease='inout')),
    (15.5, P(pitch=2.0, yaw=1.0, la=OUT_L(0.4), head=head_nod(7, -6, -1), ease='inout')),
    (19.0, P(pitch=1.0, yaw=0.0, la=('dir', v(f=0.7, u=0.0, r=-0.5), D), head=head_nod(5, 0, 0), ease='inout')),
    (24.0, P(la=HANG, head=head_nod(0, 0, 0), ease='inout')),
]
bake_poses(c, disapprove, 1.0, 'disapprove', **UPPER)


# ================================================================== bow_release: the string let go, over the vanilla bow pose

c = cclip('bow_release', 8, False, 'kestrel', kind='bow', blend=(0.25, 1.5), rate=4, cell=(220, 250), zoom=4.6, center=7.0,
          views=((32, 10), (90, 2)), events=[('release', 1)])
# Both arms 'add' to what vanilla gives the pose (xRot -90 degrees plus the head's pitch, level and ahead): the keys turn an
# arm about its own axes after that, so z swings it across (an arm level ahead turns about the vertical), x lifts or drops
# its tip and y rolls it about its length.
RELEASE_L = [   # the drawing hand: snaps back and outward as the string goes, flung open, then eased home (x, y, z degrees)
    (0.0, (0.0, 0.0, 0.0), 'inout'),
    (0.9, (-14.0, 0.0, -42.0), 'out'),
    (2.0, (-20.0, 3.0, -50.0), 'inout'),
    (4.0, (-10.0, 2.0, -30.0), 'inout'),
    (6.2, (-3.0, 0.0, -9.0), 'inout'),
    (8.0, (0.0, 0.0, 0.0), 'inout'),
]
RELEASE_R = [   # the bow hand: the recoil kicks the bow up and back, a roll, and settles
    (0.0, (0.0, 0.0, 0.0), 'inout'),
    (0.8, (-9.0, 7.0, 3.5), 'out'),
    (2.2, (2.5, -3.0, -1.5), 'inout'),
    (4.2, (-1.8, 1.4, 0.7), 'inout'),
    (8.0, (0.0, 0.0, 0.0), 'inout'),
]
for bone, table in (('left_arm', RELEASE_L), ('right_arm', RELEASE_R)):
    for t, (x, y, z), ease in table:
        c.key(bone, t, qeuler(x=x, y=y, z=z), ease=ease)
    c.mode(bone, 'add')


# ================================================================== holding an item: the arm that puts it where asked

def hold_item(item_key, grip_t, dir_t, w_dir=1.0, flat=None, w_flat=0.25, side='right_arm', point='grip', other='tip'):
    """An arm descriptor ('fn') for the arm holding [item_key]: its grip at torso-space point [grip_t] and its length (grip
    to tip) along torso-space direction [dir_t] (both turn and lean with the torso); [flat]: a torso-space direction its
    flat should face, either side of it. [point]: which named point of the item goes to [grip_t] instead of the grip."""
    item = ITEMS[item_key]

    def fn(fr, legs, guess):
        G = torso_to_world(fr, grip_t)
        Rt = qmat(fr['qr']) @ qmat(fr['qt'])
        D = Rt @ unit(dir_t)
        best = None
        for sign in ((1.0, -1.0) if flat is not None else (0.0,)):
            dirs = [(item.dir(point, other), D, w_dir)]
            if flat is not None:
                dirs.append((np.array([sign, 0.0, 0.0]), Rt @ unit(flat), w_flat))
            q, cost = solve_arm(side, fr, [(item.pt(point), G, 1.0)], dirs, guess=guess)
            if best is None or cost < best[1]:
                best = (q, cost)
        return best[0]
    return ('fn', fn)


def shoulder_rest(item_key, S, D, side='right_arm', **kw):
    """The arm holding [item_key] so that its length passes through torso-space point [S] (the top of the shoulder) along
    torso-space direction [D]: the grip sits where a rigid arm can reach on that line (the grip stays a fixed distance
    from the shoulder), found from the line and the sphere the grip can move on."""
    item = ITEMS[item_key]
    P0 = np.array(PIVOTS[side], float)
    r = float(np.linalg.norm(item.pt('grip')))
    D = unit(D)
    w = np.asarray(S, float) - P0
    b = float(np.dot(w, D))
    d = b + math.sqrt(max(0.0, b * b - (float(np.dot(w, w)) - r * r)))
    G = np.asarray(S, float) - d * D
    return hold_item(item_key, G, D, side=side, **kw), G


# ================================================================== the idles: torso, head and both arms, over a walk

ZERO3 = np.zeros(3)
CUR = {}                                           # the sample being solved: its head key, for arms that follow the head
IDLE_HANG = ('hang', 4.0, 3.0)                    # an arm easy at the side: what an idle begins and ends in


def seg(timeline, t):
    """Where a timeline [(tick, descriptor, ease), ...] is at [t]: the two descriptors it is between and how far along (eased)."""
    if t <= timeline[0][0]:
        return timeline[0][1], timeline[0][1], 0.0
    for (ta_, da, _), (tb, db, eb) in zip(timeline, timeline[1:]):
        if t <= tb:
            x = 0.0 if tb <= ta_ else (t - ta_) / (tb - ta_)
            return da, db, EASE[eb](x)
    return timeline[-1][1], timeline[-1][1], 0.0


def idle_sol(cache, tag, body, head, ra, la, rpos=ZERO3, lpos=ZERO3):
    """One sample of an idle: the torso's (pitch, yaw, roll), the head descriptor, each arm as a descriptor or a (from, to,
    fraction) triple from seg(), and the shoulders' offsets."""
    fr = frame_of(dict(pitch=body[0], yaw=body[1], roll=body[2], dx=0.0, dz=0.0, drop=0.0, sink=0.0))
    hq = eval_head(head, fr)
    CUR['hq'] = hq
    arms = {}
    for side, spec, pos in (('right_arm', ra, rpos), ('left_arm', la, lpos)):
        da, db, f = spec if not isinstance(spec[0], str) else (spec, spec, 0.0)
        frm = dict(fr, arm_pos=np.asarray(pos, float))
        qa = eval_arm(side, da, frm, {}, cache.get((tag, id(da), side)))
        cache[(tag, id(da), side)] = qa
        if db is da or f <= 0:
            arms[side] = qa
        else:
            qb = eval_arm(side, db, frm, {}, cache.get((tag, id(db), side)))
            cache[(tag, id(db), side)] = qb
            arms[side] = qslerp(qa, qb, f)
        if side == 'right_arm':
            CUR['rq'], CUR['rpos'] = arms[side], np.asarray(pos, float)
    return Sol(fr, {}, arms['right_arm'], arms['left_arm'], hq, np.asarray(rpos, float), np.asarray(lpos, float))


def on_head(p_head, side='left_arm', p_arm=None, palm=None, palm_w=0.0):
    """An arm descriptor: the arm's point [p_arm] (default the grip, the hand's middle) on a point of the head ([p_head], in
    head space), wherever the head has turned or bowed this sample."""
    def fn(fr, legs, guess):
        frame = {k: val for k, val in fr.items() if k != 'head_q'}
        target = head_point(frame, CUR['hq'], np.asarray(p_head, float))
        return reach(side, frame, target, palm=palm, palm_w=palm_w, guess=guess, p=p_arm)
    return ('fn', fn)


def on_item(item_key, point, off=(0.0, 0.0, 0.0), side='left_arm', p_arm=None, palm=None, palm_w=0.0):
    """An arm descriptor: the arm's point [p_arm] (default the grip) on a named [point] of the item the right arm holds this
    sample (its string, say), [off] further along in the world."""
    item = ITEMS[item_key]

    def fn(fr, legs, guess):
        frame = {k: val for k, val in fr.items() if k != 'head_q'}
        src = dict(frame, arm_pos=CUR['rpos'])
        target = hand_point('right_arm', CUR['rq'], src, p=item.pt(point)) + np.asarray(off() if callable(off) else off, float)
        return reach(side, frame, target, palm=palm, palm_w=palm_w, guess=guess, p=p_arm)
    return ('fn', fn)


def aim_arm(side, axis_dir, local_axis, world_dir, w_axis=1.0, w_local=0.5):
    """An arm descriptor: the arm's own axis along torso-space direction [axis_dir] and its local direction [local_axis]
    (either way round: the nearer sign) as near torso-space [world_dir] as that allows. For a held thing whose face has to
    point somewhere (the book's cover to the reader's eyes)."""
    def fn(fr, legs, guess):
        Rt = qmat(fr['qr']) @ qmat(fr['qt'])
        A, W = Rt @ unit(axis_dir), Rt @ unit(world_dir)
        best = None
        for sign in (1.0, -1.0):
            q, cost = solve_arm(side, fr, [], [((0.0, 1.0, 0.0), A, w_axis), (sign * np.asarray(local_axis, float), W, w_local)], guess=guess)
            if best is None or cost < best[1]:
                best = (q, cost)
        return best[0]
    return ('fn', fn)


def key_idle(c, t, sol, head_mode='add', ease='linear'):
    """Keys an idle sample: torso, both arms (with their shoulder offsets) and the head."""
    key_solved(c, t, sol, ease=ease, legs=False, root=False, head_mode=head_mode)


def sway(t, length, k=1, phase=0.0):
    """A loop-safe sine: [k] whole periods over the clip's length."""
    return math.sin(2.0 * math.pi * (k * t / length + phase))


def ramp(t, a, b):
    """0 before [a], 1 after [b], smooth between."""
    return smooth((t - a) / (b - a)) if b > a else float(t >= a)


def pulse(t, a, b, c_, d):
    """0 before [a], 1 from [b] to [c_], 0 after [d], smooth ramps between."""
    return ramp(t, a, b) * (1.0 - ramp(t, c_, d))


# ================================================================== idle_bram: rolls his neck and shoulders, rests the greatsword on a shoulder

BRAM_REST = hold_item('greatsword', (1.8, 5.68, -4.09), (0.73, -0.64, -0.02), flat=U)      # across the chest, the blade over the left shoulder
BRAM_REST2 = hold_item('greatsword', (2.1, 5.2, -4.2), (0.71, -0.62, 0.10), flat=U)       # shifted a little on the shoulder
c = cclip('idle_bram', 100, True, 'bram', blend=(8.0, 8.0), cell=(230, 260), zoom=5.4, center=6.0,
          views=((32, 10, None, None), (0, 10, None, None)))
BRAM_RA = [(0.0, IDLE_HANG, 'inout'), (37.0, IDLE_HANG, 'inout'), (50.0, BRAM_REST, 'inout'), (62.0, BRAM_REST, 'inout'),
           (66.0, BRAM_REST2, 'inout'), (70.0, BRAM_REST, 'inout'), (79.0, BRAM_REST, 'inout'), (92.0, IDLE_HANG, 'inout'),
           (100.0, IDLE_HANG, 'inout')]
_cache = {}
for t in ticks(0, 100, 1):
    roll_phase = 2.0 * math.pi * (t - 8.0) / 18.0
    dip = pulse(t, 3.0, 8.0, 26.0, 31.0)                      # the head down, rolled round, and up again
    ring = pulse(t, 8.0, 8.01, 26.0, 26.01)
    hx = 9.0 * dip - 5.0 * (1.0 - math.cos(roll_phase)) * ring
    hz = -9.0 * math.sin(roll_phase) * ring
    shoulder = pulse(t, 22.0, 25.0, 35.0, 38.0)
    sp = 2.0 * math.pi * 2.0 * (t - 23.0) / 14.0
    rp = np.array([0.0, -1.3 * math.sin(sp), 1.3 * math.cos(sp)]) * shoulder
    lp = rp.copy()
    # at rest: the head turns away from the blade and back, and a breath out as the shoulders let go
    look = pulse(t, 54.0, 60.0, 70.0, 76.0)
    sigh = pulse(t, 56.0, 60.0, 64.0, 70.0)
    lp = lp + np.array([0.0, -0.9 * pulse(t, 49.0, 52.0, 54.0, 57.0) + 0.5 * sigh, 0.0])
    body = (-0.6 * sway(t, 100.0, 2) - 1.2 * shoulder * math.sin(sp * 0.5) ** 2 + 0.8 * sigh, -3.0 * look, -0.5 * math.sin(roll_phase) * ring)
    sol = idle_sol(_cache, 'bram', body, ('rel', hx + 2.0 * sigh, -9.0 * look, hz + 2.0 * look), seg(BRAM_RA, t), IDLE_HANG, rp, lp)
    key_idle(c, t, sol)
c.mode('head', 'add')
seal(c)


# ================================================================== idle_ysolde: swings the censer, then two fingers to her brow

BROW = on_head((5.0, -6.6, -5.8))                       # the left hand at the left side of the brow, as near as the arm clears the head
BLESS = ('dir', v(f=0.85, u=-0.15, r=-0.5), D)           # the hand open and out, giving the blessing
c = cclip('idle_ysolde', 100, True, 'ysolde', blend=(8.0, 8.0), cell=(230, 260), zoom=5.4, center=6.0,
          views=((32, 10, None, None), (90, 2, None, None)))
YS_LA = [(0.0, IDLE_HANG, 'inout'), (46.0, IDLE_HANG, 'inout'), (55.0, BROW, 'inout'), (68.0, BROW, 'inout'), (77.0, BLESS, 'inout'),
         (88.0, IDLE_HANG, 'inout'), (100.0, IDLE_HANG, 'inout')]
_cache = {}
for t in ticks(0, 100, 1):
    u = t - 6.0
    env = ramp(t, 5.0, 9.0) * (1.0 - ramp(t, 38.0, 47.0)) * (0.4 + 0.6 * math.exp(-max(0.0, u) / 26.0))
    swing = -math.sin(2.0 * math.pi * u / 15.0)               # back first, then forward, settling
    ra = ('hang', 4.0, 3.0 + 25.0 * env * swing)
    bow = pulse(t, 47.0, 55.0, 68.0, 80.0)
    tap = bump(t, 58.0, 64.0)
    glance = ramp(t, 4.0, 10.0) * (1.0 - ramp(t, 30.0, 44.0))
    body = (5.0 * bow + 0.7 * env * swing - 0.5 * sway(t, 100.0, 2), 0.0, 0.0)
    head = ('rel', 14.0 * bow + 1.5 * tap + 6.0 * glance, 4.0 * bow, 0.0)
    sol = idle_sol(_cache, 'ysolde', body, head, ra, seg(YS_LA, t))
    key_idle(c, t, sol)
c.mode('head', 'add')
seal(c)


# ================================================================== idle_kestrel: scans the horizon, shades her eyes, tests the bowstring

KEST_BOW = ('dir', v(f=0.86, u=-0.5, r=-0.38), v(u=1.0, r=0.3))        # the bow up before her chest, upright, the string to her
KEST_STRING = on_item('bow', 'string', off=lambda: (0.0, -0.3 * CUR['pull'], CUR['pull']), p_arm=ARM_END['left_arm'])   # the left thumb on it
KEST_VISOR = on_head((4.5, -8.5, -7.0), p_arm=ARM_END['left_arm'])      # the left hand flat above the left brow
c = cclip('idle_kestrel', 100, True, 'kestrel', kind='stand', blend=(8.0, 8.0), cell=(230, 260), zoom=5.4, center=6.0,
          views=((32, 10, None, None), (0, 10, None, None)))
K_RA = [(0.0, IDLE_HANG, 'inout'), (52.0, IDLE_HANG, 'inout'), (62.0, KEST_BOW, 'inout'), (82.0, KEST_BOW, 'inout'),
        (92.0, IDLE_HANG, 'inout'), (100.0, IDLE_HANG, 'inout')]
K_LA = [(0.0, IDLE_HANG, 'inout'), (28.0, IDLE_HANG, 'inout'), (36.0, KEST_VISOR, 'inout'), (47.0, KEST_VISOR, 'inout'),
        (55.0, IDLE_HANG, 'inout'), (60.0, IDLE_HANG, 'inout'), (67.0, KEST_STRING, 'inout'), (83.0, KEST_STRING, 'inout'),
        (91.0, IDLE_HANG, 'inout'), (100.0, IDLE_HANG, 'inout')]
K_HEAD = [(0.0, (0.0, 0.0, 0.0)), (6.0, (0.0, 0.0, 0.0)), (14.0, (-4.0, -38.0, 0.0)), (17.0, (-5.0, -38.0, 0.0)), (27.0, (-3.0, 36.0, 0.0)),
          (29.0, (-4.0, 36.0, 0.0)), (37.0, (-7.0, -20.0, 0.0)), (42.0, (-7.0, -26.0, -2.0)), (47.0, (-6.0, -14.0, 0.0)),
          (54.0, (6.0, -4.0, 0.0)), (62.0, (23.0, -8.0, 2.0)), (82.0, (25.0, -8.0, 2.0)), (92.0, (3.0, -2.0, 0.0)), (100.0, (0.0, 0.0, 0.0))]
_cache = {}
for t in ticks(0, 100, 1):
    hp, hy, hr = spline_keys(K_HEAD, t)
    pull = 3.4 * (bump(t, 69.0, 74.0) + bump(t, 76.0, 81.0))
    CUR['pull'] = pull
    # the string let go: the bow trembles in her hand for a moment
    shiver = 0.05 * sum(math.exp(-(t - t0) / 2.5) * math.sin(2.0 * math.pi * (t - t0) / 3.0) for t0 in (73.5, 80.5) if t >= t0)
    ra = seg(K_RA, t)
    if 62.0 <= t <= 82.0:
        ra = ('dir', v(f=0.86, u=-0.5 + shiver, r=-0.38), v(u=1.0, r=0.3))
    body = (4.0 * pulse(t, 56.0, 64.0, 82.0, 90.0), 0.25 * hy, 0.0)
    sol = idle_sol(_cache, 'kestrel', body, ('rel', hp, hy, hr), ra, seg(K_LA, t))
    key_idle(c, t, sol, head_mode='set')
c.mode('head', 'set')
seal(c)


# ================================================================== idle_lucan: reads, turns the page, glances up, fidgets with the staff

READ_AXIS = v(f=0.85, u=-0.3, r=0.3)                    # the left arm out before his chest, the book at its end
READ_NORMAL = v(u=0.6, f=-0.8)                          # the cover's face: up and back, to his eyes
STAFF_UP = 50.0                                         # the right arm raised this far forward: the staff leans up and ahead


def read_book(lift=0.0, roll=0.0):
    """The left arm holding the book out to be read: [lift] raises it (degrees), [roll] turns the cover about the arm's line."""
    axis = (qmat(qaxis(R, -lift)) @ READ_AXIS) if lift else READ_AXIS
    normal = (qmat(qaxis(axis, roll)) @ READ_NORMAL) if roll else READ_NORMAL
    return aim_arm('left_arm', axis, (1.0, 0.0, 0.0), normal, w_local=0.5)


def staff_arm(fwd, twist=0.0):
    """The right arm raised [fwd] degrees forward, turned [twist] degrees about its own length (the staff's tip swings aside)."""
    def fn(fr, legs, guess):
        q = relaxed(fr, 'right_arm', out=4.0, fwd=fwd)
        return qmul(q, qaxis(np.array([0.0, 1.0, 0.0]), twist)) if twist else q
    return ('fn', fn)


c = cclip('idle_lucan', 100, True, 'lucan', props=('book',), blend=(8.0, 8.0), cell=(230, 260), zoom=5.4, center=6.0,
          views=((32, 10, None, None), (-32, 10, None, None)))
L_HEAD = [(0.0, (0.0, 0.0, 0.0)), (10.0, (0.0, 0.0, 0.0)), (19.0, (26.0, 3.0, 0.0)), (24.0, (28.0, -3.0, 0.0)), (29.0, (28.0, 4.0, 0.0)),
          (34.0, (30.0, -2.0, 0.0)), (38.0, (29.0, 0.0, 1.0)), (43.0, (27.0, 3.0, 0.0)), (47.0, (4.0, 22.0, 3.0)), (53.0, (2.0, 24.0, 4.0)),
          (58.0, (22.0, 4.0, 0.0)), (64.0, (20.0, -12.0, -2.0)), (70.0, (18.0, -10.0, 0.0)), (76.0, (8.0, -2.0, 0.0)), (86.0, (1.0, 0.0, 0.0)),
          (100.0, (0.0, 0.0, 0.0))]
_cache = {}
prev = None
for t in ticks(0, 100, 1):
    hp, hy, hr = spline_keys(L_HEAD, t)
    w_read = pulse(t, 8.0, 18.0, 78.0, 90.0)
    flick = bump(t, 33.0, 39.0)                           # the page turned: the book lifts and rolls, and comes back
    la = (IDLE_HANG, read_book(lift=10.0 * flick + 2.0 * sway(t, 100.0, 4), roll=-22.0 * math.sin(math.pi * min(1.0, flick * 1.6)) * flick), w_read)
    fwd = 3.0 + (STAFF_UP - 3.0) * pulse(t, 6.0, 16.0, 78.0, 90.0)
    fid = bump(t, 56.0, 76.0)
    twist = 13.0 * fid * math.sin(2.0 * math.pi * (t - 56.0) / 7.0)
    tap = -5.0 * fid * max(0.0, math.sin(2.0 * math.pi * (t - 56.0) / 7.0 + 1.2)) ** 3
    ra = staff_arm(fwd + tap, twist)
    body = (3.0 * w_read + 0.5 * sway(t, 100.0, 2), 0.0, 0.0)
    sol = idle_sol(_cache, 'lucan', body, ('rel', hp, hy, hr), ra, la)
    key_idle(c, t, sol, head_mode='set')
c.mode('head', 'set')
seal(c)


# ================================================================== checks

SHARED = ('sit_fire', 'stand_up', 'downed', 'revive', 'approve', 'disapprove')     # played by all four companions
ALL_ITEMS = ('greatsword', 'censer', 'staff', 'bow', 'dagger')
OWNER = dict({spec['item']: who for who, spec in COMPANIONS.items()}, dagger='kestrel')      # whose look's pieces an item is checked against
UPPER_ONLY = ('approve', 'disapprove', 'bow_release', 'idle_bram', 'idle_ysolde', 'idle_kestrel', 'idle_lucan')
LIMITS = {'head': 0.3, 'body': 1.2, 'right_leg': 2.0, 'left_leg': 2.0, 'left_arm': 2.0, 'ground': 0.3}      # px an item may sink before it is flagged


def check_loop(c):
    """A loop must end where it starts (every bone's first and last key equal)."""
    bad = []
    for bone, keys in c.keys.items():
        q0, p0, q1, p1 = keys[0][1], keys[0][2], keys[-1][1], keys[-1][2]
        if keys[0][0] != 0 or abs(keys[-1][0] - c.length) > 1e-6:
            bad.append(f'{bone}: keys span {keys[0][0]}..{keys[-1][0]}')
            continue
        if abs(abs(float(np.dot(q0, q1))) - 1) > 1e-9:
            bad.append(f'{bone}: rotation differs')
        a, b = (np.zeros(3) if p is None else p for p in (p0, p1))
        if np.linalg.norm(a - b) > 1e-9:
            bad.append(f'{bone}: position differs')
    return bad


def check_held(c, held):
    """The held stretch of a one-shot loops: its first and last samples must agree on every bone."""
    bad = []
    for bone in c.keys:
        qa, pa = c.sample(bone, held[0])
        qb, pb = c.sample(bone, held[1])
        if abs(abs(float(np.dot(qa, qb))) - 1) > 1e-6 or np.linalg.norm(pa - pb) > 1e-6:
            bad.append(f'{bone}: held ticks {held[0]:g} and {held[1]:g} differ')
    return bad


def check_upper(c):
    """Gestures and idles play over a walk: no leg tracks and the root left alone."""
    return [f'{b} is tracked' for b in ('right_leg', 'left_leg', 'root') if b in c.keys]


def ground_report(c, step=1.0):
    """The lowest point of the body over the clip (the ground is y = 24) and, of the parts, which sink furthest below it."""
    lowest, sunk = 0.0, {}
    for t in ticks(0, c.length, step):
        pose = pose_of(c, float(t))
        mats = mats_of(pose)
        low = 0.0
        for name, (lo, hi) in BODY_BOXES.items():
            corners = np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])
            ys = (mats[name][:3, :3] @ corners.T).T[:, 1] + mats[name][1, 3]
            d = float(ys.max()) - GROUND
            sunk[name] = max(sunk.get(name, -99.0), d)
            low = max(low, d)
        lowest = max(lowest, low)
    return lowest, sunk


def item_report(c, items):
    """For each item in the hand, the worst the item sinks into the body or the ground over the clip (px, which part, tick)."""
    out = {}
    for key in items:
        worst = {}
        for t in ticks(0, c.length, 1.0 if c.length >= 12 else 0.5):
            pose = pose_of(c, float(t))
            d, part, below = item_clearance(ITEMS[key], pose, dress=DRESS.get(OWNER.get(key), ()))
            if part and d > worst.get(part, (0.0, 0))[0]:
                worst[part] = (d, t)
            if below > worst.get('ground', (0.0, 0))[0] and 'right_arm' in c.keys:
                worst['ground'] = (below, t)
        out[key] = worst
    return out


def report(c):
    info = INFO[c.name]
    lines = []
    items = ALL_ITEMS if c.name in SHARED else (COMPANIONS[info['who']]['item'],)
    if c.name == 'bow_release':
        items = ('bow',)
    for key, worst in item_report(c, items).items():
        bits = []
        for part, (d, t) in sorted(worst.items()):
            flag = '' if d <= LIMITS.get(part, 1.0 if part in DRESS_NAMES else 0.5) else '  !! '
            if d > 0.05:
                bits.append(f'{flag}{part} {d:.2f}@{t:g}')
        lines.append(f'  {c.name}: {key}: ' + (', '.join(bits) if bits else 'clear'))
    low, sunk = ground_report(c)
    notes = ', '.join(f'{n} {d:+.1f}' for n, d in sorted(sunk.items()) if d > 0.3)
    lines.append(f'  {c.name}: lowest point {low:+.2f} px below the ground' + (f' ({notes})' if notes else ''))
    return lines


def bones_text(c):
    return ' '.join(b + ('+' if c.modes.get(b) == 'add' else '') for b in ('root', 'torso', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg') if b in c.keys)


# ================================================================== the moving preview

GIF_SIZE = (300, 360)
GIF_HEAD = 58                                     # the caption band at the top
GIF_FRAMING = {'sit_fire': (0.85, (-5.0, -12.0)), 'stand_up': (0.88, (0.0, -12.0), 8.0), 'downed': (0.88, (0.0, -3.0), 8.0),
               'revive': (0.85, (-5.0, -9.0))}     # clip: (zoom factor, camera's x z[, centre y]), where the sheet's framing cuts something off
GIF_CAPTION = {
    'sit_fire': 'sits by the fire, hands held out to the warmth', 'stand_up': 'gets up, pushing off a knee',
    'downed': 'struck down; the last ticks are held', 'revive': 'a hand taken, hauled up, a shake of the head',
    'approve': 'a nod and a raised fist, walking on', 'disapprove': 'shakes the head, waves it off, walking on',
    'bow_release': 'the string let go (a quarter speed)', 'idle_bram': 'rolls neck and shoulders, rests the blade',
    'idle_ysolde': 'swings the censer, two fingers to the brow', 'idle_kestrel': 'scans, shades the eyes, tests the string',
    'idle_lucan': 'reads, turns the page, fidgets with the staff'}


def _font(size):
    from PIL import ImageFont
    for path in ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/usr/share/fonts/truetype/freefont/FreeSans.ttf'):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size=size)


def gif_frames(c, size=GIF_SIZE):
    """The frames of one clip for the moving preview, [(image, milliseconds)]: the front three-quarter view and a caption.
    The slow loops (the fire, the idles) go at the game's speed, two ticks to a frame; the gestures one tick to a frame
    (50 ms, the game's speed again); bow_release is slowed to a quarter. A one-shot rests on its last pose, and the downed
    clip plays its held stretch once more."""
    info = INFO[c.name]
    W, H = size
    cw, ch = info['cell'] or (230, 260)
    spec = (info['views'] or ((32, 10),))[0]
    zoom = (info['zoom'] or 4.0) * H / ch * 0.98
    center = info['center'] if info['center'] is not None else 6.0
    if c.name in GIF_FRAMING:
        factor, pan, *cy = GIF_FRAMING[c.name]
        zoom, spec = zoom * factor, (spec[0], spec[1], None, None, pan)
        center = cy[0] if cy else center
    project = view_for(spec, size, center, zoom)
    f1, f2 = _font(16), _font(11)
    who = info['who']
    item = ITEMS[COMPANIONS[who]['item']].name.replace('_', ' ')
    slow = c.name == 'bow_release'
    step, ms = (0.25, 50) if slow else (2.0, 100) if info['loop'] else (1.0, 50)
    times = [float(t) for t in ticks(0, c.length, step)]
    if info['held']:
        times += [float(t) for t in ticks(info['held'][0], info['held'][1], step)][1:]
    frames = []
    for k, t in enumerate(times):
        img, _ = render_cell(c, min(t, float(c.length)), project, size)
        d = ImageDraw.Draw(img)
        d.rectangle([0, 0, W, GIF_HEAD], fill=(30, 32, 40))
        d.text((8, 4), c.name, fill=(255, 236, 170), font=f1)
        d.text((W - 8, 6), f'{c.length:g} ticks, {"loops" if info["loop"] else "once"}', fill=(150, 156, 170), font=f2, anchor='ra')
        d.text((8, 25), f'{who.capitalize()}, {item}', fill=(205, 210, 220), font=f2)
        d.text((8, 41), GIF_CAPTION.get(c.name, ''), fill=(205, 210, 220), font=f2)
        frames.append([img, ms])
    if not info['loop']:
        frames[-1][1] = 800
    return frames


def make_gif(path, names=None):
    """All the clips one after the other (a shared small palette, only what changes stored): the moving preview."""
    names = names or [c.name for c in clips]
    frames, spans = [], []
    for n in names:
        fs = gif_frames(getc(n))
        spans.append((n, len(frames), len(fs)))
        frames += fs
    sample = [frames[i][0] for i in range(0, len(frames), max(1, len(frames) // 24))]
    sheet_ = Image.new('RGB', (GIF_SIZE[0] * 6, GIF_SIZE[1] * ((len(sample) + 5) // 6)))
    for i, f in enumerate(sample):
        sheet_.paste(f, ((i % 6) * GIF_SIZE[0], (i // 6) * GIF_SIZE[1]))
    pal = sheet_.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    out = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f, _ in frames]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out[0].save(path, save_all=True, append_images=out[1:], duration=[ms for _, ms in frames], loop=0, optimize=False, disposal=1)
    return len(frames), os.path.getsize(path), spans


# ================================================================== main

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
        if c.name in UPPER_ONLY:
            bad += check_upper(c)
        if info['held']:
            bad += check_held(c, info['held'])
        if bad:
            print(f'!! {c.name}: ' + '; '.join(bad))
        c.write(assets, GROUP)
    print(f'wrote {len(clips)} clips to {os.path.join(assets, "animations", GROUP)}')
    for c in clips:
        info = INFO[c.name]
        held = f'  held ticks {info["held"][0]:g} to {info["held"][1]:g} loop' if info['held'] else ''
        print(f'{c.name:13s} {c.length:>4g} ticks {"loop" if info["loop"] else "once"}  blend {c.blend_in:g}/{c.blend_out:g}  rate {c.rate}  '
              f'[{bones_text(c)}]{held}')
        for line in report(c):
            print(line)
    for c in clips:
        if only and c.name not in only:
            continue
        sheet(c).save(os.path.join(previews, f'companion_{c.name}.png'))
    if not only:
        gif = os.path.join(previews, 'companion_moves.gif')
        n, size, spans = make_gif(gif)
        print(f'wrote {gif}: {n} frames, {size / 1e6:.2f} MB')


if __name__ == '__main__':
    main(sys.argv)
