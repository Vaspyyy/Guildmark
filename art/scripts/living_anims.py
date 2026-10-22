"""
The Living World's townsfolk clips: getting up and sitting down, yawns and stretches, climbing ladders, eating, washing
and drawing water, goods changing hands, greetings and gestures, the market, the fields, the mill, the woodpile, the
crier and the lamplighter. Built with the townsfolk kit (art/scripts/townsfolk_anims.py: the same skeleton, IK and
forward kinematics as the game's CombatAnimator) on the townsfolk model, holding the props of art/scripts/living_props.py.
Run from the repo root:

    python3 -B art/scripts/living_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]

Writes only these clips to assets/guildmark/animations/townsfolk/ (blend in and out 0: the game crossfades between
activities itself), checks each against its Activity in townsfolk/TownRole.kt (length, loop, overlay), checks every loop
ends where it starts, every overlay moves nothing but the arms and the head, and every join (the first and last frames
against the clips they meet: sit, standing idle), and draws a contact sheet per clip (or only the clips named).

The seat transitions are drawn on the seat: sit_down starts standing where standUp() leaves someone (the access block's
middle, 14.08 px ahead of where sitOn() puts them and 8 px lower) with their back to the seat, and ends on exactly the
first frame of sit; stand_up is the way back, ending on the first frame of idle. The client keeps the figure where it was
as the server moves it between seat and floor (living transitions in LivingWorld.kt).

The climb is one 16-tick cycle that the client plays by height, not by time: tick 2h for a body h px up the ladder (two
rungs a cycle), so the hands and feet stay on the rungs at any speed; 1/32 of a block a tick plays it at its own pace.
"""
import math, os, re, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import townsfolk_anims as ta
from townsfolk_anims import (qeuler, qmul, qaxis, qmat, qslerp, qfrommat, frame_to, unit, I, v, F, B, U, D, R, L, T, Rq,
                             apply, PIVOTS, HIPS, GRIP, EYES, MOUTH, F_, bend, frame_at, chain_frame, arm_world,
                             hand_point, solve_arm, free, rel, reach, head_at, head_point, key_frame, track, ticks, seal,
                             check, fit, arm_dir, body_solve, sink_for, torso_local, mirror_dir, mirror_q, qinv, rvec_q,
                             q_rvec, look_q, smooth, bump, tool_point, tool_dir, relaxed, knee_hand, seat_legs, SEAT_DROP,
                             spline_keys, mono_keys, gaze_head, hands_at, level_arms, solve_arm_slack, torso_to_world,
                             COUNTER_TOP, TABLE_TOP, DOOR_Z, LIPS)
import living_props as lp

GROUP = ta.GROUP
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
TOWN_ROLE = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/townsfolk/TownRole.kt')

# ================================================================== the kit, taught the new props and stations

HANG = set(lp.HANG) | {'line', 'lantern'}

# Prop tracks: parts a clip turns (and may place and stretch) tick by tick, in the model's own space, whatever the arm
# or body carrying them does: the grindstone's crank turning on its axle, the well rope running over the curb to the
# hands, the bucket coming up the shaft. TRACKS[clip][part] = fn(t, pivot) -> (R, P, S): the part's rotation in model
# space (3 x 3), its pivot in model space (None: where its parent carries it, [pivot]) and its scale (None: 1). main()
# bakes them into the clip's file as extra bones ("prop:<part>": rotation and pivot; "prop:<part>:scale": the scale as
# a position) that LivingProps.kt plays back: the part turned to that rotation by undoing its parents' turns.
TRACKS = {}
_CUR = [None, 0.0]          # the clip and time being drawn (the tracks need them)
_TRACKED = {}               # what the tracks gave, part by part, the last time part_matrices ran


def part_matrices(look, pose, hang=True):
    """ta.part_matrices, hanging the bucket and the empty sack straight down from the hand too, and the clip's prop
    tracks turning, placing and stretching their parts (as the game does each frame)."""
    qr, pr = pose['root']
    Mroot = T(*pr) @ Rq(qr)
    out = {}
    tracks = TRACKS.get(_CUR[0], {}) if hang else {}

    def go(part, M):
        out[part.name] = M
        for c in part.children:
            Mc = M @ T(*c.pivot) @ Rq(ta.rot_part(c.rot))
            if c.name in tracks:
                R, P, S = tracks[c.name](_CUR[1], Mc[:3, 3].copy())
                _TRACKED[c.name] = (R, P, S)
                Mc = Mc.copy()
                Mc[:3, :3] = R if S is None else R @ np.diag(S)
                if P is not None:
                    Mc[:3, 3] = P
            elif hang and c.name in HANG:
                Mc = Mc.copy()
                Mc[:3, :3] = np.eye(3)
            go(c, Mc)
    for p in look.model.parts:
        q, piv = pose[p.name]
        go(p, Mroot @ T(*piv) @ Rq(q))
    return out


ta.part_matrices = part_matrices
_fk = ta.fk


def fk(c, t, part, p, walking=False):
    """ta.fk, with the clip's prop tracks."""
    was = list(_CUR)
    _CUR[0], _CUR[1] = c.name, t
    try:
        return _fk(c, t, part, p, walking)
    finally:
        _CUR[0], _CUR[1] = was


ta.fk = fk


def aim(frm, to, length0):
    """A track's rotation and scale for a straight piece (a rope) modelled [length0] px along its +y from its pivot:
    turned from [frm] to point at [to] and stretched to reach it."""
    d = np.asarray(to, float) - np.asarray(frm, float)
    n = float(np.linalg.norm(d))
    if n < 1e-6:
        return np.eye(3), (1.0, 1e-3, 1.0)
    y = d / n
    x = np.cross(y, np.array([0.0, 0.0, 1.0])) if abs(y[2]) < 0.9 else np.cross(y, np.array([1.0, 0.0, 0.0]))
    x /= np.linalg.norm(x)
    z = np.cross(x, y)
    return np.column_stack([x, y, z]), (1.0, n / length0, 1.0)


def bake_tracks(c, path):
    """Adds [c]'s prop tracks to its clip file at [path] (the samples the kit bakes: rate per tick)."""
    import json
    tracks = TRACKS.get(c.name)
    if not tracks:
        return
    data = json.load(open(path))
    look = ta.look_for(ta.INFO[c.name]['look'])
    n = int(math.floor(c.length * c.rate)) + 1
    rot = {k: [] for k in tracks}
    pos = {k: [] for k in tracks}
    scale = {k: [] for k in tracks}
    placed, stretched, prev = set(), set(), {}
    for i in range(n):
        t = i / c.rate
        _CUR[0], _CUR[1] = c.name, t
        _TRACKED.clear()
        part_matrices(look, ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t)))
        for k in tracks:
            R, P, S = _TRACKED[k]
            q = qfrommat(R)
            if k in prev and float(np.dot(prev[k], q)) < 0:
                q = -q
            prev[k] = q
            rot[k] += [round(float(v), 4) for v in q]
            pos[k] += [round(float(v), 3) for v in (P if P is not None else (0.0, 0.0, 0.0))]
            scale[k] += [round(float(v), 4) for v in (S if S is not None else (1.0, 1.0, 1.0))]
            if P is not None:
                placed.add(k)
            if S is not None:
                stretched.add(k)
    _CUR[0] = None
    for k in tracks:
        data['bones'][f'prop:{k}'] = {'rot': rot[k], **({'pos': pos[k]} if k in placed else {})}
        if k in stretched:
            data['bones'][f'prop:{k}:scale'] = {'pos': scale[k]}
    with open(path, 'w') as f:
        json.dump(data, f, separators=(',', ':'))

# The stations the new clips work at, as boxes (lo, hi, colour, lit) in the clip's own space (the feet at y 24, facing -z)
STONE = (128, 128, 136)
STONE_D = (100, 100, 110)
PLANK = (150, 112, 70)
DIRT = (104, 76, 50)


_RISE = [0.0]           # how far up the ladder the body being drawn is (render_cell sets it from INFO 'rise')


def ladder_boxes(rise=None):
    """The ladder's wall, rails and rungs (world fixed: a body [rise] px up the ladder sees them that much lower), as
    vanilla draws a ladder: a plane 0.8 px off the wall, its rails 2 px wide 4 to 6 px out from the middle, its rungs 2
    px high and 14 across with their middles 2, 6, 10 and 14 px up each block. The floor is at the bottom block's foot
    (y 24 when rise is 0)."""
    rise = _RISE[0] if rise is None else rise
    foot = min(40.0, 24.0 + rise)
    b = [((-24, -56.0, -14.0), (24, foot, -8.0), (118, 108, 98), True)]
    for x0 in (-6.0, 4.0):
        b.append(((x0, -56.0, -7.6), (x0 + 2.0, foot, -6.8), (112, 80, 48), True))
    for k in range(int(math.floor((rise - 10.0) / 4.0)), int(math.ceil((rise + 80.0) / 4.0))):
        mid = 24.0 - 2.0 - 4.0 * k + rise          # world height 2 + 4k px over the floor
        if k >= 0 and -58.0 < mid < foot:
            b.append(((-7.0, mid - 1.0, -7.5), (7.0, mid + 1.0, -6.9), (150, 110, 64), True))
    if rise < 16.0:
        b.append(((-24, 24 + rise, -24), (24, 24.2 + rise, 24), (96, 92, 84), True))
    return b


def ladder_top_boxes(rise=None):
    """The last two blocks of a ladder and the ledge it ends at (the wall's top, a landing beyond it), world fixed as
    ladder_boxes are: the ledge 32 px over the feet of a body [rise] 0 px up (climb_top's tick 0)."""
    rise = _RISE[0] if rise is None else rise
    top = -8.0 + rise
    b = [((-24, top, -40.0), (24, 72.0, -8.0), (118, 108, 98), True)]
    for x0 in (-6.0, 4.0):
        b.append(((x0, top, -7.6), (x0 + 2.0, 72.0, -6.8), (112, 80, 48), True))
    for k in range(-14, 8):
        mid = 22.0 + rise - 4.0 * k
        if mid < 72.0:
            b.append(((-7.0, mid - 1.0, -7.5), (7.0, mid + 1.0, -6.9), (150, 110, 64), True))
    return b


def _well():
    # the town well a block ahead: a ring of stone a block high round the water (three blocks across, the water in the
    # middle one, two blocks ahead, its face under the ground), fence posts on the ring's corners carrying a roof of
    # slabs, and a chain hanging over the water from it (Props.kt and FarmProps.kt build them so)
    b = []
    for x0 in (-24, -8, 8):
        for z0 in (-24, -40, -56):
            if (x0, z0) != (-8, -40):
                b.append(((x0, 8, z0), (x0 + 16, 34, z0 + 16), STONE if (x0 + z0) % 32 else STONE_D, True))
    b.append(((-8, 25.8, -40), (8, 26.0, -24), (46, 80, 150), False))
    for cx, cz in ((-16, -16), (16, -16), (-16, -48), (16, -48)):
        b.append(((cx - 2, -24, cz - 2), (cx + 2, 8, cz + 2), (110, 80, 50), True))
    b.append(((-24, -32, -56), (24, -24, -8), STONE_D, True))
    b.append(((-0.8, -24, -32.8), (0.8, -8, -31.2), (70, 70, 76), True))
    return b


def _stall():
    # the stall's counter a block ahead (its top at 8), goods on it, a barrel the block to the merchant's left, the posts
    # and the striped awning three blocks up, its scalloped valance hanging to just over the merchant's head
    b = [((-8, 8, -24), (8, 24, -8), (140, 100, 62), True),
         ((-6, 5, -14), (-2, 8, -10), (196, 50, 40), True), ((-7, 6, -20), (-1, 8, -16), (220, 190, 90), True),
         ((8, 8.5, -8), (24, 24, 8), (122, 86, 52), True), ((9, 8, -7), (23, 8.5, 7), (96, 66, 40), True)]
    for x0 in (-33, 31):
        for z0 in (-17, -1):
            b.append(((x0, -24, z0), (x0 + 2, 24, z0 + 2), (110, 80, 50), True))
    for i, x0 in enumerate(range(-40, 40, 8)):
        col = (190, 50, 46) if i % 2 == 0 else (236, 232, 222)
        b.append(((x0, -26, -40), (x0 + 8, -24, 8), col, True))
        b.append(((x0, -24, -8), (x0 + 8, -11 if i % 2 == 0 else -12, -7.4), col, True))
    return b


def _grindstone():
    # a grindstone a block ahead, its axle pointing at the miller: the wheel (12 across) between two legs and bearings,
    # the crank on the near end of the axle
    b = [((-6, 8, -20), (6, 20, -12), (150, 150, 156), True),
         ((-2, 17, -22), (2, 24, -20), (110, 80, 50), True), ((-2, 17, -12), (2, 24, -10), (110, 80, 50), True),
         ((-3, 11, -22), (3, 17, -20), (96, 70, 44), True), ((-3, 11, -12), (3, 17, -10), (96, 70, 44), True)]
    return b


def _hopper():
    # a hopper (a bin) a block ahead: its open top at 8
    b = [((-8, 8, -24), (8, 13, -22), (70, 70, 78), True), ((-8, 8, -10), (8, 13, -8), (70, 70, 78), True),
         ((-8, 8, -22), (-6, 13, -10), (70, 70, 78), True), ((6, 8, -22), (8, 13, -10), (70, 70, 78), True),
         ((-6, 12, -22), (6, 13, -10), (40, 40, 46), True),
         ((-4, 13, -20), (4, 19, -12), (64, 64, 72), True), ((-2, 19, -18), (2, 24, -14), (64, 64, 72), True)]
    return b


def _block():
    # a log on end a block ahead for a chopping block, a billet standing on it
    return [((-7, 8, -23), (7, 24, -9), (118, 86, 54), True), ((-6, 7.8, -22), (6, 8, -10), (196, 160, 110), True),
            ((-2, 5, -22), (2, 7.8, -18), (190, 152, 104), True)]


def _lamp():
    # a street lamp a block ahead: a wall post, two fences and a lantern on top (its glass 52 px up)
    return [((-4, 8, -20), (4, 24, -12), STONE, True), ((-2, -24, -18), (2, 8, -14), (110, 80, 50), True),
            ((-3, -31, -19), (3, -24, -13), (60, 58, 64), True), ((-2.5, -30, -18.5), (2.5, -25, -13.5), (255, 200, 90), False),
            ((-2, -33, -18), (2, -31, -14), (60, 58, 64), True)]


def _wheat():
    # standing wheat ahead and to the left, in rows, where the scythe sweeps: stalks with their ears on top
    b = []
    for i, x in enumerate(np.arange(-22.0, 14.1, 4.0)):
        for j, z in enumerate((-14.0, -19.0, -24.0, -29.0)):
            jit = ((i * 7 + j * 3) % 5) * 0.4
            b.append(((x - 0.4, 16 + jit, z - 0.4), (x + 0.4, 24, z + 0.4), (176, 160, 70), True))
            b.append(((x - 0.8, 14.5 + jit, z - 0.6), (x + 0.8, 17 + jit, z + 0.6), (222, 196, 96), True))
    return b


def _crop_row():
    # carrots in a row ahead: leafy tops on the dirt
    b = []
    for x, z in ((-5, -10), (-1, -11), (3, -10.5), (-3, -13), (1, -14)):
        b.append(((x - 1.2, 21, z - 1.2), (x + 1.2, 24, z + 1.2), (96, 150, 60), True))
    return b


def _table_seat():
    # the seat and a table in front of it, its top at TABLE_TOP
    b = [((-8, 24, -6), (8, 32, 10), PLANK, True), ((-8, 16, 2), (8, 24, 10), (132, 98, 62), True),
         ((-12, TABLE_TOP, -27), (12, TABLE_TOP + 1.5, -7), PLANK, True)]
    for x0, z0 in ((-11.5, -26.5), (9.5, -26.5), (-11.5, -9.5), (9.5, -9.5)):
        b.append(((x0, TABLE_TOP + 1.5, z0), (x0 + 2, 32, z0 + 2), (110, 80, 50), True))
    return b


STATIONS = {
    'ladder': ladder_boxes,
    'ladder_top': ladder_top_boxes,
    'well': _well,
    'stall': _stall,
    'grindstone': _grindstone,
    'hopper': _hopper,
    'block': _block,
    'lamp': _lamp,
    'wheat': _wheat,
    'crop_row': _crop_row,
    'table_seat': _table_seat,
}
GROUND_AT = {'table_seat': 32.0}
GROUND_COLOUR = {'wheat': DIRT, 'crop_row': DIRT}

_station_boxes = ta.station_boxes


def station_boxes(kind, rise=0.0):
    if kind in STATIONS:
        b = list(STATIONS[kind](rise or None) if kind in ('ladder', 'ladder_top') else STATIONS[kind]())
        if kind not in ('ladder', 'ladder_top'):
            gy = GROUND_AT.get(kind, 24.0)
            b.append(((-24, gy, -24), (24, gy + 0.2, 24), GROUND_COLOUR.get(kind, ta.GROUND), True))
        return b
    return _station_boxes(kind)


ta.station_boxes = station_boxes

# What the previews show of a clip's props and goods tick by tick (the game does the same: LivingProps.kt, from the
# LivingSchedule.kt main() writes): INFO[name] 'tools_at' (t -> the props shown, when they come and go within the clip)
# and 'goods' (t, part matrices -> the middle and size of what is carried, drawn as a box where the game draws the
# carried item, or None), which set_goods() makes from the clip's spans in GOODS_SPANS.
GOODS_COLOUR = (214, 168, 92)

# What is carried (Townsperson.carried, drawn by the game with the item renderer) and where, clip by clip: spans (from,
# to, how, size) of ticks (to None: on to the end, and held there). how is ('hands', ahead): between the two grips and
# [ahead] px further forward, turned with the body; ('part', part, offset): at a point in a part's own space, turned with
# it; ('ground', point): at a point in the clip's own space (on the ground, on a stall's counter); ('toss', point, part,
# offset, arc): thrown from a point in the clip's space to one in a part's, rising [arc] px over the way. size: its
# largest extent, px. GOODS_REPLACE: the prop the goods take the place of while they show (the crate carry holds when
# there are no goods); GOODS_ONLY_CARRIED: clips that draw nothing when nothing is carried (pay counts its own coins
# then; gather pulls up a carrot).
GOODS_SPANS = {}
GOODS_REPLACE = {'carry': 'crate', 'pay': 'coin'}
GOODS_ONLY_CARRIED = {'pay', 'carry'}
GOODS_DEFAULT = {'gather': 'carrot'}


def goods_point(how, t, t0, t1, mats):
    """Where a span puts the goods at [t] (the clip's space), from the part matrices of the pose."""
    kind = how[0]
    if kind == 'hands':
        return between_hands(mats, how[1])
    if kind == 'part':
        return apply(mats[how[1]], np.asarray(how[2], float))
    if kind == 'ground':
        return np.asarray(how[1], float)
    _, start, part, offset, arc = how
    end = apply(mats[part], np.asarray(offset, float))
    u = (t - t0) / max(1e-6, t1 - t0)
    return np.asarray(start, float) + (end - np.asarray(start, float)) * u + np.array([0.0, -4.0 * arc * u * (1 - u), 0.0])


def set_goods(name, spans):
    """[name]'s goods spans, for the game (GOODS) and the previews (INFO 'goods')."""
    GOODS_SPANS[name] = [(float(a), None if b is None else float(b), how, float(size)) for a, b, how, size in spans]
    if name not in ta.INFO:
        return

    def fn(t, mats, name=name):
        if name in GOODS_ONLY_CARRIED:
            return None
        for a, b, how, size in GOODS_SPANS[name]:
            if a <= t and (b is None or t < b):
                return goods_point(how, t, a, b, mats), size
        return None
    ta.INFO[name]['goods'] = fn


def shown_tools(name, t):
    info = ta.INFO[name]
    fn = info.get('tools_at')
    return set(fn(t)) if fn else info['tools']


def goods_quads(name, t, mats):
    fn = ta.INFO[name].get('goods')
    got = fn(t, mats) if fn else None
    if got is None:
        return []
    mid, size = np.asarray(got[0], float), float(got[1])
    return ta.box(mid - size / 2, mid + size / 2, GOODS_COLOUR, True)


def partner_quads(partner, t):
    name, look_key = (partner, None) if isinstance(partner, str) else partner
    pc = next(c for c in ta.clips if c.name == name)
    look2 = ta.look_for(look_key or ta.INFO[name]['look'])
    pose2 = ta.pose_at(pc, t, ta.walk_base(t, speed=0.0, age=t))
    G = ta.partner_place()
    mats2 = {k: G @ M for k, M in part_matrices(look2, pose2).items()}
    return ta.model_quads(look2, mats2, shown_tools(name, t)) + goods_quads(name, t, mats2)


def render_cell(look, c, t, project, size, walking, wire=False, solo=False):
    info = ta.INFO[c.name]
    base = ta.walk_base(t, speed=ta.walk_speed(c, walking), age=t)
    pose = ta.pose_at(c, t, base)
    _CUR[0], _CUR[1] = c.name, t
    mats = part_matrices(look, pose)
    quads = ta.model_quads(look, mats, shown_tools(c.name, t)) + goods_quads(c.name, t, mats)
    if info.get('partner') and not solo:
        _CUR[0] = info['partner'] if isinstance(info['partner'], str) else info['partner'][0]
        quads += partner_quads(info['partner'], t)
    _CUR[0] = None
    kind = info['station']
    _RISE[0] = info['rise'](t) if info.get('rise') else 0.0
    try:
        if kind and not wire:
            quads += ta.station(kind)
        else:
            for lo, hi, col, light in station_boxes(kind or ''):
                if hi[1] - lo[1] < 0.5 and hi[0] - lo[0] > 30:
                    quads += ta.box(lo, hi, col, light)
        img = ta.raster(quads, project, size, bg=(54, 60, 74))
        if kind and wire:
            ta.station_wire(ImageDraw.Draw(img), kind, project)
    finally:
        _RISE[0] = 0.0
    return img, pose


ta.partner_quads = partner_quads
ta.render_cell = render_cell


def grips(mats):
    """Both hands' grips in the clip's space, from the part matrices of a pose."""
    return apply(mats['right_arm'], GRIP['right_arm']), apply(mats['left_arm'], GRIP['left_arm'])

# ================================================================== the activities, and a registry of these clips

def activities():
    """Activity name -> (clip, ticks, loop, overlay), read from TownRole.kt."""
    out = {}
    src = open(TOWN_ROLE).read()
    for m in re.finditer(r'^\s*([A-Z_]+)\("([a-z_]+)",\s*(\d+),\s*(true|false)(?:,\s*(true|false))?\)', src, re.M):
        out[m.group(1)] = (m.group(2), int(m.group(3)), m.group(4) == 'true', m.group(5) == 'true')
    return out


clips = []


def clip(name, length, loop, **kw):
    """ta.clip, registered here too (and a clip of the same name built before in this session replaced)."""
    ta.clips[:] = [c for c in ta.clips if c.name != name]
    clips[:] = [c for c in clips if c.name != name]
    c = ta.clip(name, length, loop, **kw)
    clips.append(c)
    return c


def getc(name):
    return next(c for c in ta.clips if c.name == name)

# ================================================================== helpers

def keyed(c, times, fn):
    """Calls fn(t) at each of [times]; fn keys whatever it likes at t (ease 'linear': dense keys carry the curve)."""
    for t in times:
        fn(t)


def every(length, step=0.5):
    return ticks(0, length, step)


def leg_q(d, toe=None):
    """A leg ('set', relative to the root) pointing along world [d], its toes turned as near [toe] as they go (forward
    when it hangs, up when it lies out)."""
    d = unit(d)
    if toe is None:
        k = min(1.0, max(0.0, float(d[1])))
        toe = k * F + (1.0 - k) * U
    return frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), d, np.asarray(toe, float))


def hip_of(side, pr):
    return np.array(PIVOTS[side], float) + np.asarray(pr, float)


def leg_to(c, t, side, pr, foot, ease='linear', toe=None, lift=None):
    """Keys [side] so its end (the sole's middle) reaches world [foot] from its hip under root offset [pr]. A foot nearer
    than the leg's 12 px lifts the leg into the body (its pivot raised: a bent knee, as a block leg shows one)."""
    hip = hip_of(side, pr)
    d = np.asarray(foot, float) - hip
    n = float(np.linalg.norm(d))
    raise_ = 0.0
    if n < 12.0:
        # raise the pivot straight up until the leg's 12 px just reach
        dy = d[1]
        horiz = math.hypot(d[0], d[2])
        new_dy = math.sqrt(max(0.0, 144.0 - horiz * horiz))
        raise_ = new_dy - dy
        d = np.array([d[0], new_dy, d[2]])
    elif n > 12.05:
        print(f'   ({c.name} t={t}: {side} reaches {n:.2f} px, more than 12)')
    if lift is not None:
        raise_ = lift
    c.key(side, t, leg_q(d, toe), pos=(0.0, -raise_, 0.0) if raise_ > 1e-6 else None, ease=ease)
    return raise_


def leg_end(c, t, side):
    """Where the end of a leg (the middle of its sole) is at [t], as the game poses it."""
    return ta.fk(c, t, side, (0.0, 12.0, 0.0))


def arm_hang(side, out=4.0, fwd=0.0):
    """An arm hanging at rest ('set' relative to the torso): [out] degrees out from the side, [fwd] forward."""
    s = 1.0 if side == 'right_arm' else -1.0
    return qeuler(x=-fwd, z=s * out)


def seat_frame(t):
    """The first frame of sit (where sit_down ends and stand_up begins)."""
    return F_(qt=bend(3.0, 0.0, 0.0), pr=(0, SEAT_DROP, 0))


def interp(keys, t, kind='mono'):
    """A number through [keys] (t, value): 'mono' never overshoots a key, 'spline' carries its speed through them."""
    return float(mono_keys(keys, t)) if kind == 'mono' else float(spline_keys(keys, t))


def vec_keys(keys, t, kind='mono'):
    """A point through [keys] (t, (x, y, z)), each coordinate as interp does it."""
    return np.array([interp([(k, p[i]) for k, p in keys], t, kind) for i in range(3)])


def deg_between(qa, qb):
    return math.degrees(2 * math.acos(min(1.0, abs(float(np.dot(qa, qb))))))

# ================================================================== joins: does one clip's frame meet another's?

JOIN_POINTS = {
    'head': [(0.0, -4.0, -3.0), (0.0, -8.0, 0.0), (4.0, -4.0, 0.0)],
    'body': [(0.0, 0.0, 0.0), (0.0, 12.0, 0.0), (4.0, 6.0, -2.0)],
    'right_arm': [(-1.0, 10.0, 0.0), (-1.0, 0.0, -2.0)],
    'left_arm': [(1.0, 10.0, 0.0), (1.0, 0.0, -2.0)],
    'right_leg': [(0.0, 12.0, 0.0), (0.0, 6.0, -2.0)],
    'left_leg': [(0.0, 12.0, 0.0), (0.0, 6.0, -2.0)],
}


def pose_points(c, t, M=None):
    """The world points of a pose (the head, the torso, both hands, both feet) at [t] of [c], standing still, under an
    optional 4x4 [M] (another clip's frame)."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    pose = ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=0.0))
    mats = part_matrices(look, pose)
    out = {}
    for part, pts in JOIN_POINTS.items():
        for i, p in enumerate(pts):
            w = apply(mats[part], p)
            if M is not None:
                w = apply(M, w)
            out[(part, i)] = w
    return out


def join_gap(a, ta_, b, tb, M=None):
    """The farthest any of the pose points of [a] at [ta_] is from where [b] has them at [tb] (in px), and where."""
    pa = pose_points(a, ta_, M)
    pb = pose_points(b, tb)
    worst = max(pa, key=lambda k: np.linalg.norm(pa[k] - pb[k]))
    return float(np.linalg.norm(pa[worst] - pb[worst])), worst


JOINS = []      # (label, clip a, tick, clip b, tick, M (a's frame in b's), tolerance px)


def join(label, a, ta_, b, tb, M=None, tol=0.6):
    JOINS.append((label, a, ta_, b, tb, M, tol))

# ================================================================== writing them out, with every check


OVERLAY_BONES = {'right_arm', 'left_arm', 'head'}     # all an overlay may move: it plays while walking


def verify():
    """Every clip against its Activity (length, loop, overlay), loops sealed, overlays moving only the arms and the head,
    the joins, the contacts. Returns the lines to print and whether all is well."""
    acts = {clip_: (name, ticks_, loop, overlay) for name, (clip_, ticks_, loop, overlay) in activities().items()}
    lines, ok = [], True
    for c in clips:
        info = ta.INFO[c.name]
        bad = []
        if c.name in EXTRA:
            ticks_, loop = EXTRA[c.name]
            if c.length != ticks_ or info['loop'] != loop:
                bad.append(f'{c.length} ticks long, loop {info["loop"]}: wanted {ticks_}, loop {loop}')
        elif c.name not in acts:
            bad.append('no Activity plays it')
        else:
            act, ticks_, loop, overlay = acts[c.name]
            if c.length != ticks_:
                bad.append(f'{c.length} ticks long, {act} lasts {ticks_}')
            if info['loop'] != loop:
                bad.append(f'loop {info["loop"]}, {act} loop {loop}')
            if info['overlay'] != overlay:
                bad.append(f'overlay {info["overlay"]}, {act} overlay {overlay}')
            if overlay:
                bad += [f'{b} is keyed (an overlay moves only the arms and head)' for b in c.keys if b not in OVERLAY_BONES]
        if info['loop']:
            bad += ta.check_loop(c)
        for bone, keys in c.keys.items():
            if keys[0][0] != 0 or abs(keys[-1][0] - c.length) > 1e-6:
                bad.append(f'{bone} keyed over {keys[0][0]}..{keys[-1][0]}')
        lines.append(f'{c.name:12s} {c.length:3d}t {"loop" if info["loop"] else "once"}'
                     f'{" overlay" if info["overlay"] else ""}: ' + ('ok' if not bad else '!! ' + '; '.join(bad)))
        ok = ok and not bad
        for line in ta.report(c):
            lines.append(line)
            ok = ok and '!! OFF' not in line
    missing = [act for act, (clip_, *_rest) in activities().items() if clip_ in WANTED and clip_ not in {c.name for c in clips}]
    if missing:
        lines.append('!! not made yet: ' + ', '.join(missing))
        ok = False
    for label, a, t1, b, t2, M, tol in JOINS:
        gap, where = join_gap(a, t1, b, t2, M)
        lines.append(f'  join {label}: {gap:.2f} px (at {where[0]})' + ('   !! OFF' if gap > tol else ''))
        ok = ok and gap <= tol
    return lines, ok


# clips no Activity plays, which the game plays in another's place: (ticks, loop)
EXTRA = {'climb_top': (64, False)}

# props with a flame that glows in the dark (LivingSchedule.GLOW)
GLOW_PROPS = ['lamp_pole']

# the clips this script makes: every Activity added for the Living World
WANTED = ['sit_down', 'stand_up', 'yawn', 'stretch', 'climb', 'eat', 'sit_eat', 'wash', 'draw_water', 'pick_up',
          'put_down', 'hand_over', 'receive', 'pay', 'bow', 'nod', 'shrug', 'point', 'browse', 'set_up', 'sow', 'scythe',
          'gather', 'carry_basket', 'grind', 'pour_sack', 'chop', 'ring_bell', 'announce', 'light_lamp', 'knock']


# ================================================================== the game's half: LivingSchedule.kt

SCHEDULE_KT = os.path.join(ROOT, 'src/main/kotlin/io/github/vaspyyy/guildmark/client/townsfolk/LivingSchedule.kt')


def shown_spans(c):
    """The props [c] shows and the ticks it shows each between: (part, from, to), to None for on to the end and held
    there (a one-shot keeps showing its last frame)."""
    info = ta.INFO[c.name]
    if not info.get('tools_at'):
        return [(p, 0.0, None) for p in sorted(info['tools'])]
    spans, since = [], {}
    n = int(round(c.length * 2))
    for i in range(n + 1):
        t = i / 2.0
        now = set(shown_tools(c.name, t))
        for p in sorted(now - set(since)):
            since[p] = t
        for p in sorted(set(since) - now):
            spans.append((p, since.pop(p), t))
    spans += [(p, t0, None) for p, t0 in since.items()]
    return sorted(spans, key=lambda sp: (sp[0], sp[1]))


def kt_float(x):
    """A Kotlin float literal (None: END, on to the end)."""
    if x is None:
        return 'END'
    x = round(float(x), 3)
    if x == int(x):
        return f'{int(x)}f'
    return f'{x:.3f}'.rstrip('0').rstrip('.') + 'f'


def kt_goods(span):
    a, b, how, size = span
    kind, zero = how[0], ('0f', '0f', '0f')
    part, at, to_part, to, arc = '""', zero, '""', zero, '0f'
    if kind == 'hands':
        at = (kt_float(how[1]), '0f', '0f')
    elif kind == 'part':
        part, at = f'"{how[1]}"', tuple(kt_float(v) for v in how[2])
    elif kind == 'ground':
        at = tuple(kt_float(v) for v in how[1])
    else:
        at = tuple(kt_float(v) for v in how[1])
        to_part, to, arc = f'"{how[2]}"', tuple(kt_float(v) for v in how[3]), kt_float(how[4])
    return (f'Goods({kt_float(a)}, {kt_float(b)}, How.{kind.upper()}, {part}, {", ".join(at)}, {to_part}, '
            f'{", ".join(to)}, {arc}, {kt_float(size)})')


def moment_specs():
    """Where each clip's moments happen, so their sounds and dust come from there: by clip and moment, ('part', part,
    offset) for a point of a part as posed on the moment's tick, ('hands', ahead) for between the hands, ('point', p) for
    a point of the clip's own space, or by tick when the same moment happens in different places (climb's grabs). The
    rest happen at the chest."""
    rg, lg = tuple(GRIP['right_arm']), tuple(GRIP['left_arm'])
    mouth, face, hips = ('part', 'head', (0.0, -2.2, -4.4)), ('part', 'head', (0.0, -3.5, -4.6)), ('part', 'body', (0.0, 12.0, 0.0))
    right, left, hands = ('part', 'right_arm', rg), ('part', 'left_arm', lg), ('hands', 0.0)
    bell = ('part', 'handbell', BELL_MOUTH)
    return {
        'sit_down': {'glance': ('part', 'head', (0.0, -4.0, 0.0)), 'down': hips},
        'stand_up': {'feet': ('part', 'right_leg', (0.0, 12.0, -1.0)), 'up': hips},
        'knock': {'knock': right},
        'climb': {'grab': {0.0: left, 8.0: right},
                  'step': {4.0: ('part', 'right_leg', (0.0, 12.0, -1.5)), 12.0: ('part', 'left_leg', (0.0, 12.0, -1.5))}},
        'climb_top': {'step': {k: ('part', 'right_leg' if k % 16 == 4 else 'left_leg', (0.0, 12.0, -1.5))
                               for k in (4.0, 12.0, 20.0, 28.0, 36.0, 44.0, 52.0)},
                      'palm': {9.5: right, 19.0: left}, 'ledge': ('part', 'left_leg', (0.0, 12.0, -1.0))},
        'eat': {'bite': mouth},
        'sit_eat': {'spoon': mouth},
        'wash': {'scoop': hands, 'splash': face, 'shake': hands},
        'pick_up': {'grab': hands},
        'put_down': {'set_down': ('point', tuple(GOODS_ON_GROUND))},
        'hand_over': {'handoff': ('hands', 2.0)},
        'receive': {'handoff': ('hands', 2.0)},
        'pay': {'clink': ('part', 'right_arm', tuple(np.add(rg, COIN)))},
        'browse': {'pick': right, 'look': left, 'put back': right, 'feel': left},
        'set_up': {'take': left, 'place': ('point', tuple(STALL_SET)), 'tug': hands},
        'sow': {'seed': right},
        'gather': {'pull': right, 'toss': right, 'in_basket': ('part', 'body', (5.6, 7.8, -4.0))},
        'scythe': {'swish': ('part', 'scythe', SC_BLADE_MID), 'cut': ('part', 'scythe', SC_BLADE_MID)},
        'grind': {'grind': ('point', (0.0, 19.0, -16.0))},
        'chop': {'chop': ('point', tuple(CHOP_AT))},
        'pour_sack': {'swing': ('part', 'sack_held', (0.0, 0.0, 0.0)), 'pour': ('point', (0.0, 8.5, -16.0)),
                      'shake': ('point', (0.0, 8.5, -16.0)), 'empty': right},
        'ring_bell': {'ring': bell},
        'announce': {'sweep': bell, 'beat': bell, 'show': bell, 'ring': bell},
        'light_lamp': {'reach': ('part', 'lamp_pole', LAMP_WICK), 'lit': ('point', (0.0, -27.5, -16.0))},
        'draw_water': {'pull': hands, 'knock': ('part', 'well_bucket', (0.0, 4.0, 0.0)),
                       'scrape': ('part', 'well_bucket', (0.0, 4.0, 0.0)), 'grab': right,
                       'slosh': ('part', 'bucket', (0.0, 4.0, 0.0))},
    }


def moment_point(c, name, t, specs):
    """Where [c]'s moment [name] on tick [t] happens, in the clip's space."""
    spec = specs.get(c.name, {}).get(name, ('part', 'body', (0.0, 3.0, -2.5)))
    if isinstance(spec, dict):
        spec = spec[float(t)]
    if spec[0] == 'point':
        return np.asarray(spec[1], float)
    was = list(_CUR)
    _CUR[0], _CUR[1] = c.name, t
    try:
        look = ta.look_for(ta.INFO[c.name]['look'])
        mats = part_matrices(look, ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t)))
    finally:
        _CUR[0], _CUR[1] = was
    if spec[0] == 'hands':
        return between_hands(mats, spec[1])
    return apply(mats[spec[1]], np.asarray(spec[2], float))


def write_schedule(path=SCHEDULE_KT):
    """Writes what each clip shows of its props and goods when, for LivingProps.kt."""
    parents = [(part.name, parent) for parent, part in (b() for b in lp.BUILDERS)]
    out = ['// Written by art/scripts/living_anims.py (write_schedule) from its clips and art/scripts/living_props.py: run',
           '// the script rather than editing this by hand.',
           'package io.github.vaspyyy.guildmark.client.townsfolk', '',
           '/**',
           " * What the Living World's clips show of their props, and of the goods someone carries, tick by tick (the previews",
           ' * of art/scripts/living_anims.py show the same). [LivingProps] shows and hides the props and runs their tracks;',
           ' * [LivingGoodsLayer] draws the goods.',
           ' */',
           'internal object LivingSchedule {',
           '    /** On to the end of the clip, and held there. */',
           '    const val END = Float.MAX_VALUE', '',
           '    /** A prop shown from tick [from] up to [to]. */',
           '    class Shown(val part: String, val from: Float, val to: Float)', '',
           '    /**',
           '     * Goods shown from tick [from] up to [to], [size] px at their largest: between the hands and [x] px further',
           "     * forward (HANDS), at ([x], [y], [z]) in [part]'s own space (PART), at ([x], [y], [z]) in the clip's space",
           '     * (GROUND: on the ground, on a counter), or thrown from there to ([tx], [ty], [tz]) in [toPart]\'s space,',
           '     * rising [arc] px on the way (TOSS).',
           '     */',
           '    class Goods(',
           '        val from: Float, val to: Float, val how: How, val part: String, val x: Float, val y: Float, val z: Float,',
           '        val toPart: String, val tx: Float, val ty: Float, val tz: Float, val arc: Float, val size: Float,',
           '    )', '',
           '    enum class How { HANDS, PART, GROUND, TOSS }', '',
           '    /** A moment of a clip: what happens ([name]), on which tick, and where: ([x], [y], [z]) in the clip\'s space. */',
           '    class Moment(val name: String, val tick: Float, val x: Float, val y: Float, val z: Float)', '',
           '    /** The clips these are for. */',
           '    val CLIPS: Set<String> = setOf(' + ', '.join(f'"{c.name}"' for c in clips) + ')', '',
           "    /** Each prop's own part, and the part it hangs on. */",
           '    val PARENT: Map<String, String> = mapOf(']
    out += [f'        "{n}" to "{p}",' for n, p in parents]
    out += ['    )', '', '    /** By clip: the props it shows, and when. */', '    val SHOWN: Map<String, List<Shown>> = mapOf(']
    for c in clips:
        spans = shown_spans(c)
        if spans:
            out.append(f'        "{c.name}" to listOf(')
            out += [f'            Shown("{p}", {kt_float(a)}, {kt_float(b)}),' for p, a, b in spans]
            out.append('        ),')
    out += ['    )', '',
            '    /** By clip: the props its file turns, places and stretches itself tick by tick ("prop:<part>" bones). */',
            '    val TRACKED: Map<String, List<String>> = mapOf(']
    out += [f'        "{n}" to listOf(' + ', '.join(f'"{p}"' for p in sorted(tr)) + '),' for n, tr in TRACKS.items() if tr]
    out += ['    )', '',
            '    /** Props that hang straight down from the hand holding them (where no track runs them). */',
            '    val HANG: Set<String> = setOf(' + ', '.join(f'"{p}"' for p in lp.HANG) + ')', '',
            '    /** Props with a flame that glows in the dark. */',
            '    val GLOW: Set<String> = setOf(' + ', '.join(f'"{p}"' for p in GLOW_PROPS) + ')', '',
            '    /** By clip: where the goods someone carries are, and when. */',
            '    val GOODS: Map<String, List<Goods>> = mapOf(']
    for name, spans in GOODS_SPANS.items():
        out.append(f'        "{name}" to listOf(')
        out += [f'            {kt_goods(sp)},' for sp in spans]
        out.append('        ),')
    out += ['    )', '',
            '    /** By clip: the prop the goods take the place of while they show. */',
            '    val GOODS_REPLACE: Map<String, String> = mapOf('
            + ', '.join(f'"{k}" to "{v}"' for k, v in GOODS_REPLACE.items()) + ')', '',
            '    /** Clips that show goods only when something is carried (and their own props otherwise). */',
            '    val GOODS_ONLY_CARRIED: Set<String> = setOf(' + ', '.join(f'"{k}"' for k in sorted(GOODS_ONLY_CARRIED)) + ')', '',
            '    /** By clip: the item shown when nothing is carried (a crop pulled up is a carrot). */',
            '    val GOODS_DEFAULT: Map<String, String> = mapOf('
            + ', '.join(f'"{k}" to "minecraft:{v}"' for k, v in GOODS_DEFAULT.items()) + ')', '',
            '    /** By clip: its moments, the ticks things happen on (a knock, a bite, a bell rung), for their sounds and dust. */',
            '    val MOMENTS: Map<String, List<Moment>> = mapOf(']
    specs = moment_specs()
    for c in clips:
        ev = ta.INFO[c.name]['events']
        if ev:
            out.append(f'        "{c.name}" to listOf(')
            for n, k in ev:
                x, y, z = (kt_float(v) for v in moment_point(c, n, k, specs))
                out.append(f'            Moment("{n}", {kt_float(k)}, {x}, {y}, {z}),')
            out.append('        ),')
    out += ['    )', '}', '']
    with open(path, 'w') as f:
        f.write('\n'.join(out))
    return path


def main(argv):
    assets, previews = argv[1], argv[2]
    only = set(argv[3:])
    unknown = only - {c.name for c in clips}
    if unknown:
        raise SystemExit(f'no such clip: {sorted(unknown)}')
    lines, ok = verify()
    for line in lines:
        print(line)
    for c in clips:
        bake_tracks(c, c.write(assets, GROUP))
    print('wrote', os.path.relpath(write_schedule(), ROOT))
    print(f'wrote {len(clips)} clips to {os.path.join(assets, "animations", GROUP)}' + ('' if ok else '   (with problems: see !!)'))
    os.makedirs(previews, exist_ok=True)
    for c in clips:
        if only and c.name not in only:
            continue
        ta.sheet(c).save(os.path.join(previews, f'living_{c.name}.png'))

# ================================================================== getting up and sitting down

# Where standUp() leaves someone, from where sitOn() sat them: the access block's middle, 0.88 of a block ahead (sitOn
# puts the sitter 0.12 of a block forward of the stair's middle) and half a block down (the stair's step is half a block
# high, the floor its foot).
STAND_SPOT = np.array([0.0, 8.0, -14.08])
SEAT_FLOOR = 32.0                   # the floor in front of the seat, in the seat's frame
STEP_EDGE = (-6.0, 24.0)            # the step's front edge (z) and top (y)
SIT_LEGS = (-86.0, 10.0)            # sit's legs at its first frame: out over the step, a little apart


def leg_collides(pr, th, margin=0.15):
    """Whether a leg turned [th] degrees forward from hanging (negative: back), from hips at root offset [pr], cuts into
    the step (the leg is 4 px thick, 12 long; the step fills everything behind its front edge and below its top)."""
    hip_y, hip_z = 12.0 + pr[1], pr[2]
    r = math.radians(th)
    for s in np.arange(0.0, 12.01, 0.5):
        for off in (2.0, 0.0, -2.0):
            y = hip_y + s * math.cos(r) + off * math.sin(r)
            z = hip_z - s * math.sin(r) + off * math.cos(r)
            if z > STEP_EDGE[0] + margin and y > STEP_EDGE[1] + margin:
                return True
    return False


def leg_clear_angle(pr, margin=0.15):
    """How far forward (degrees) a leg from hips at root offset [pr] must be raised to clear the step's front edge."""
    for th in np.arange(0.0, 90.5, 0.5):
        if not leg_collides(pr, th, margin):
            return float(th)
    return 90.0


def floor_leg(pr, side, foot_z, foot_x_out=0.6):
    """A leg from hips at [pr] to a foot flat on the floor at [foot_z] (z), [foot_x_out] outward: (foot point, angle
    forward from straight down), or None when the floor is out of reach."""
    s = -1.0 if side == 'right_leg' else 1.0
    hip = hip_of(side, pr)
    dy = SEAT_FLOOR - hip[1]
    dx = s * foot_x_out
    dz = foot_z - hip[2]
    if math.hypot(dx, dz) > math.sqrt(max(0.0, 144.0 - dy * dy)) + 0.6:
        return None
    return np.array([hip[0] + dx, SEAT_FLOOR, foot_z]), math.degrees(math.atan2(-dz, dy))


def seat_leg(c, t, side, pr, angle, splay):
    """A leg raised [angle] degrees forward from hanging, splayed [splay] degrees outward (as sit has them at 86, 10)."""
    s = 1.0 if side == 'right_leg' else -1.0
    c.key(side, t, qeuler(x=-angle, y=s * splay), ease='linear')


# ---- sit_down (16): back to the seat, a glance down over the right shoulder at it, a step back to it (the right foot,
# then the left) leaning forward to sit, the hips going back onto the step as the legs come up over its edge, the hands
# reaching for the knees; a little bounce as they land (tick 12) and the torso settling back upright into sit.
SD_ROOT_Z = [(0, -14.08), (1.5, -14.0), (4.5, -12.2), (7.0, -9.6), (9.0, -6.0), (11.0, -2.0), (12.5, -0.4), (14.0, 0.15), (16, 0.0)]
SD_ROOT_Y = [(0, 8.0), (3.0, 8.15), (7.0, 8.5), (9.5, 9.0), (11.5, 9.75), (12.5, 10.05), (14.0, 9.75), (16, SEAT_DROP)]
SD_PITCH = [(0, 0.0), (2.0, 2.0), (5.0, 7.0), (8.0, 15.0), (10.5, 22.0), (12.0, 17.0), (13.5, 6.0), (14.5, 1.5), (16, 3.0)]
SD_YAW = [(0, 0.0), (2.5, 6.0), (5.0, 4.0), (8.0, 0.0), (16, 0.0)]
SD_HEAD_YAW = [(0, 0.0), (2.5, 34.0), (5.0, 26.0), (8.0, 4.0), (16, 0.0)]
SD_HEAD_PITCH = [(0, 0.0), (2.5, 18.0), (5.0, 16.0), (8.0, 10.0), (11.0, 2.0), (12.8, 7.0), (14.5, -1.0), (16, 0.0)]
# the feet: the right steps back first, then the left; planted until the legs must lift over the step's edge
SD_FOOT = {'right_leg': [(0, -14.08), (1.0, -14.08), (4.0, -10.8), (16, -10.8)],
           'left_leg': [(0, -14.08), (3.5, -14.08), (6.5, -10.2), (16, -10.2)]}
SD_STEP = {'right_leg': (1.0, 4.0), 'left_leg': (3.5, 6.5)}       # when each foot is off the floor stepping


def sd_frame(t):
    pitch = interp(SD_PITCH, t)
    return F_(qt=bend(pitch, interp(SD_YAW, t), 0.0), vt=(0.0, 0.0, 0.0),
              pr=(0.0, interp(SD_ROOT_Y, t), interp(SD_ROOT_Z, t)))


SD_LIFT_FROM = {}       # when each leg leaves the floor for good (filled as the clip is built)
SD_LIFT_ANGLE = {}      # and how far forward it was then


def sd_legs(c, t, fr):
    """The legs of sit_down at [t]: a foot planted (or stepping) on the floor while it can be, then raised clear of the
    step's edge and on up to sit's 86 degrees."""
    for side in ('right_leg', 'left_leg'):
        s = -1.0 if side == 'right_leg' else 1.0
        clear = leg_clear_angle(fr['pr'])
        fz = interp(SD_FOOT[side], t)
        a0, a1 = SD_STEP[side]
        lift = 1.6 * bump(t, a0, a1)                 # a stepping foot clears the floor
        fl = floor_leg(fr['pr'], side, fz)
        angle_floor = None if fl is None else fl[1]
        end = smooth((t - 12.0) / 4.0)                 # the last ticks ease into sit's own leg pose
        if angle_floor is not None and not leg_collides(fr['pr'], angle_floor) and side not in SD_LIFT_FROM:
            foot = fl[0] + np.array([0.0, -lift, 0.0])
            leg_to(c, t, side, fr['pr'], foot, toe=F)
            continue
        SD_LIFT_FROM.setdefault(side, t)
        t0 = SD_LIFT_FROM[side]
        # from where it left the floor, up past the edge to 86 (a touch over, then back), splaying out to 10
        a_start = SD_LIFT_ANGLE.setdefault(side, clear if angle_floor is None else max(clear, angle_floor))
        keys = [(t0, a_start)]
        if t0 + 2.5 < 13.3:
            keys.append((t0 + 2.5, 80.0))
        keys += [(max(13.8, t0 + 0.5), 90.0), (16.0, -SIT_LEGS[0])]
        angle = max(clear, interp(keys, t))
        splay = SIT_LEGS[1] * smooth((t - t0) / max(1.0, 16.0 - t0))
        seat_leg(c, t, side, fr['pr'], angle, splay)


def sd_arms(c, t, fr):
    """Hanging, then swinging forward for balance as they lean to sit, the hands coming down onto the knees."""
    ra0, la0 = arm_hang('right_arm', 4.0), arm_hang('left_arm', 4.0)
    reach_ = smooth((t - 4.0) / 6.0)
    land = smooth((t - 10.5) / 4.0)
    for side, q0 in (('right_arm', ra0), ('left_arm', la0)):
        s = -1.0 if side == 'right_arm' else 1.0
        q_bal = free(fr, v(f=0.75, u=-0.62, r=-s * 0.18), D if side == 'right_arm' else D)
        q_knee = knee_hand(fr, side)
        q = qslerp(q0, q_bal, reach_)
        q = qslerp(q, q_knee, land)
        if t >= 16.0 - 1e-9:
            q = knee_hand(seat_frame(16), side)
        c.key(side, t, q, ease='linear')


c = clip('sit_down', 16, False, look=('citizen', 7), station_='seat', events=[('glance', 3), ('down', 12)],
         views=((32, -12, True), (90, -2), (150, -10)),
         traces=[('body', (0.0, 12.0, 0.0), (255, 200, 120))])
SD_LIFT_FROM.clear()
SD_LIFT_ANGLE.clear()
for t in every(16):
    fr = sd_frame(t) if t < 16 else seat_frame(16)
    key_frame(c, t, fr, ease='linear')
    sd_legs(c, t, fr)
    sd_arms(c, t, fr)
    c.key('head', t, qeuler(x=interp(SD_HEAD_PITCH, t), y=interp(SD_HEAD_YAW, t)), ease='linear')
c.mode('head', 'add')
for side in ('right_leg', 'left_leg'):
    check(c, f'{side} foot on the floor (t=0)', (leg_end(c, 0, side)[1],), (SEAT_FLOOR,), tol=0.3)
join('sit_down ends on sit', getc('sit_down'), 16, getc('sit'), 0, tol=0.05)
join('sit_down starts on idle (at the access block)', getc('sit_down'), 0, getc('idle'), 0, M=T(*(-STAND_SPOT)), tol=1.2)

# ---- stand_up (14): from sit, the hands pressing on the thighs and the torso leaning forward as the hips slide to the
# step's edge and over it, the legs swinging down off it (never through its edge) to plant the feet where standUp()
# stands them (tick 5); the arms swinging forward as the push lifts the hips up over the feet on straight legs, the
# torso straightening with a little sway back, the arms swinging down to hang: the first frame of idle there.
SU_LAND = 5.0                                   # the feet meet the floor, and stay
SU_FEET = {'right_leg': np.array([-1.9, SEAT_FLOOR, STAND_SPOT[2]]),
           'left_leg': np.array([1.9, SEAT_FLOOR, STAND_SPOT[2]])}
SU_ROOT_Z = [(0, 0.0), (1.0, -0.5), (2.5, -3.0), (3.6, -5.8), (4.4, -7.4), (5.0, -8.1), (6.5, -10.2), (8.0, -12.3),
             (9.5, -13.6), (11.0, -14.02), (14, -14.08)]
SU_PITCH = [(0, 3.0), (1.0, 7.0), (2.5, 13.0), (4.0, 20.0), (5.5, 25.0), (7.0, 20.0), (8.5, 11.0), (10.0, 3.5),
            (11.3, -1.6), (12.6, 0.6), (14, 0.0)]
SU_ROLL = [(0, 0.0), (8.5, 0.0), (10.0, 1.2), (11.8, -0.9), (13.0, 0.3), (14, 0.0)]
SU_HEAD_PITCH = [(0, 0.0), (2.0, 5.0), (4.5, 7.0), (7.0, -2.0), (9.0, -5.0), (11.0, -1.0), (14, 0.0)]
SU_ARM_FWD = [(2.5, 34.0), (4.5, 42.0), (6.5, 38.0), (8.5, 20.0), (10.3, -7.0), (12.0, 3.0), (13.2, -1.0), (14, 0.0)]
SU_ARM_OUT = [(2.5, 6.0), (4.5, 10.0), (8.0, 8.0), (11.0, 5.0), (14, 4.4)]
IDLE_ARM_OUT = 4.4      # idle's arms at rest: its 1.5 degrees out on vanilla's bob (0 to 5.7, 2.9 on the whole)


def su_root(t):
    """The root offset: on the seat until the feet land, then wherever straight legs from the planted feet put the hips."""
    z = interp(SU_ROOT_Z, t)
    if t <= SU_LAND:
        return np.array([0.0, SEAT_DROP, z])
    dz = z - STAND_SPOT[2]
    return np.array([0.0, SEAT_FLOOR - math.sqrt(144.0 - dz * dz) - 12.0, z])


def su_frame(t):
    if t <= 0:
        return seat_frame(0)
    return F_(qt=bend(interp(SU_PITCH, t), 0.0, interp(SU_ROLL, t)), pr=su_root(t))


_hip = hip_of('right_leg', su_root(SU_LAND))
SU_LAND_ANGLE = math.degrees(math.atan2(_hip[2] - STAND_SPOT[2], SEAT_FLOOR - _hip[1]))
# leg space: the point of each thigh that sit's hands rest on (knee_hand's target, carried by the leg as it swings)
SU_THIGH = {}
for _leg, _s in (('right_leg', 1.0), ('left_leg', -1.0)):
    _target = np.array([-_s * 3.1, 19.4, -7.0])
    SU_THIGH[_leg] = qmat(qeuler(x=SIT_LEGS[0], y=_s * SIT_LEGS[1])).T @ (_target - hip_of(_leg, (0.0, SEAT_DROP, 0.0)))


def su_legs(c, t, fr):
    """Sit's legs swinging down off the step (never through its edge) until the feet land, then planted (straight legs:
    the hips ride up over them)."""
    for side in ('right_leg', 'left_leg'):
        if t <= 0:
            seat_leg(c, t, side, fr['pr'], -SIT_LEGS[0], SIT_LEGS[1])
        elif t < SU_LAND:
            keys = [(0.0, -SIT_LEGS[0]), (1.5, 85.0), (3.0, 72.0), (4.0, 50.0), (SU_LAND, SU_LAND_ANGLE)]
            angle = max(leg_clear_angle(fr['pr']), interp(keys, t))
            seat_leg(c, t, side, fr['pr'], angle, SIT_LEGS[1] * (1.0 - smooth(t / SU_LAND)))
        elif t >= 14 - 1e-9:
            c.key(side, t, I, ease='linear')
        else:
            leg_to(c, t, side, fr['pr'], SU_FEET[side], toe=F)


def su_arms(c, t, fr):
    """The hands on the thighs, pressing as the hips slide; leaving them to swing forward with the push, then down past
    the hang and back to it."""
    for side, leg in (('right_arm', 'right_leg'), ('left_arm', 'left_leg')):
        if t <= 0:
            q = knee_hand(seat_frame(0), side)
        elif t >= 14 - 1e-9:
            q = arm_hang(side, IDLE_ARM_OUT)
        else:
            _, q_leg, p_leg, _ = c.keys[leg][-1]
            thigh = hip_of(leg, fr['pr']) + (np.zeros(3) if p_leg is None else np.asarray(p_leg, float)) \
                + qmat(q_leg) @ SU_THIGH[leg]
            q_on = reach(side, fr, thigh, palm=D, palm_w=0.3)
            q_swing = arm_hang(side, interp(SU_ARM_OUT, t), fwd=interp(SU_ARM_FWD, t) + interp(SU_PITCH, t))
            q = qslerp(q_on, q_swing, smooth((t - 2.5) / 2.0))
        c.key(side, t, q, ease='linear')


c = clip('stand_up', 14, False, look=('citizen', 7), station_='seat', events=[('feet', SU_LAND), ('up', 10)],
         views=((32, -12, True), (90, -2), (150, -10)),
         traces=[('body', (0.0, 12.0, 0.0), (255, 200, 120))])
for t in every(14):
    fr = su_frame(t)
    key_frame(c, t, fr, ease='linear')
    su_legs(c, t, fr)
    su_arms(c, t, fr)
    c.key('head', t, qeuler(x=interp(SU_HEAD_PITCH, t)), ease='linear')
c.mode('head', 'add')
for side in ('right_leg', 'left_leg'):
    for _t in (SU_LAND, 9.0, 14.0):
        check(c, f'{side} foot planted (t={_t:g})', leg_end(c, _t, side), SU_FEET[side], tol=0.3)
join('stand_up starts on sit', getc('stand_up'), 0, getc('sit'), 0, tol=0.05)
join('stand_up ends on idle (at the access block)', getc('stand_up'), 14, getc('idle'), 0, M=T(*(-STAND_SPOT)), tol=1.2)

# ================================================================== standing about: yawns, stretches, greetings, gestures

def K(keys, t, loop=None):
    """A number through [keys] (t, value): round a loop of [loop] ticks a periodic spline (the keys' first and last values
    the same), else monotone (at rest at each end and at every turn)."""
    if loop:
        return float(spline_keys(keys, t, period=loop))
    return float(mono_keys(keys, t))


def KV(keys, t, loop=None):
    """A point through [keys] (t, (x, y, z)), as K does a number."""
    if loop:
        return np.asarray(spline_keys(keys, t, period=loop), float)
    return vec_keys(keys, t)


def hang(side, fr=None, out=None, fwd=0.0):
    """An arm hanging straight down in the world whatever the torso does, [out] degrees out from the side and [fwd]
    forward: its 'set' key under [fr] (idle's own arm at rest by default)."""
    s = 1.0 if side == 'right_arm' else -1.0
    q = qeuler(x=-fwd, z=s * (IDLE_ARM_OUT if out is None else out))
    return q if fr is None else rel(fr, q)


class Arms:
    """Arm solutions that each start from the one before, so a hand following a path never flips."""

    def __init__(self):
        self.prev = {}

    def reach(self, side, fr, target, palm=None, palm_w=0.3, p=None):
        q = reach(side, fr, target, palm=palm, palm_w=palm_w, guess=self.prev.get(side), p=p)
        self.prev[side] = q
        return q

    def solve(self, side, fr, points=(), dirs=()):
        q, _ = solve_arm(side, fr, list(points), list(dirs), guess=self.prev.get(side))
        self.prev[side] = q
        return q


def plant(c, t, fr, right=(-1.9, 0.0), left=(1.9, 0.0), floor=24.0, lift=(0.0, 0.0)):
    """Both feet planted at world (x, z) on the floor (or lifted [lift] px) however the root moves the hips."""
    for side, (x, z), up in (('right_leg', right, lift[0]), ('left_leg', left, lift[1])):
        leg_to(c, t, side, fr['pr'], np.array([x, floor - up, z]), toe=F)


def root_for(feet, x=0.0, z=0.0, floor=24.0, dip=0.0):
    """The root offset that puts the hips (moved [x], [z]) as high as straight legs to every planted foot allow, dipped
    [dip] px more (a bend of the knees)."""
    y = 0.0
    for side, (fx, fz) in feet.items():
        hip = hip_of(side, (x, 0.0, z))
        d2 = (fx - hip[0]) ** 2 + (fz - hip[2]) ** 2
        y = max(y, floor - math.sqrt(max(0.0, 144.0 - d2)) - 12.0)
    return np.array([x, y + dip, z])


# ---- nod (20): two nods, the second smaller, the torso dipping a touch with each
c = clip('nod', 20, False, look=('citizen', 2), events=[('nod', 3.5), ('nod', 10.5)], views=((32, -12), (90, -2)))
NOD_HEAD = [(0, 0.0), (3.5, 14.0), (7.0, -3.0), (10.5, 9.0), (14.0, -1.5), (17.0, 0.5), (20, 0.0)]
NOD_PITCH = [(0, 0.0), (4.0, 2.0), (7.5, -0.4), (11.0, 1.2), (14.5, 0.0), (20, 0.0)]
NOD_ARMS = [(0, 0.0), (4.5, 1.5), (8.0, -0.5), (11.5, 1.0), (15.0, 0.0), (20, 0.0)]
for t in every(20):
    fr = F_(qt=bend(K(NOD_PITCH, t)))
    key_frame(c, t, fr, root=False, ease='linear')
    for side in ('right_arm', 'left_arm'):
        c.key(side, t, hang(side, fr, fwd=K(NOD_ARMS, t)), ease='linear')
    c.key('head', t, qeuler(x=K(NOD_HEAD, t)), ease='linear')
c.mode('head', 'add')

# ---- shrug (24): a dip, then the shoulders up and the arms out and forward with the palms turned up, the head tipped
# and the chin up ("who knows?"), a second little lift, and down again
SHRUG_U = [(0, 0.0), (2.5, -0.15), (6.5, 1.0), (9.0, 0.9), (12.0, 1.0), (17.5, 0.0), (19.5, -0.06), (24, 0.0)]
c = clip('shrug', 24, False, look=('citizen', 1), events=[('shrug', 6.5)], views=((32, -12), (0, -4), (90, -2)),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
for t in every(24):
    u = K(SHRUG_U, t)
    fr = F_(qt=bend(-2.5 * u, 0.0, 1.5 * u))
    key_frame(c, t, fr, root=False, ease='linear')
    for side in ('right_arm', 'left_arm'):
        s = 1.0 if side == 'right_arm' else -1.0
        out = IDLE_ARM_OUT + 22.0 * max(0.0, u) - 2.0 * min(0.0, u)
        q = qmul(qeuler(x=-24.0 * u, z=s * out), qaxis((0, 1, 0), s * 45.0 * max(0.0, u)))
        c.key(side, t, rel(fr, q), pos=(0.0, -1.2 * max(0.0, u), 0.0), ease='linear')
    c.key('head', t, qeuler(x=-4.0 * u, y=5.0 * u, z=9.0 * u), ease='linear')
c.mode('head', 'add')

# ---- point (30): a little draw back, then the right arm flung out to point ahead and to the right, the body turning
# with it and the head looking along it; two jabs ("there!"), the arm coming down
POINT_U = [(0, 0.0), (2.5, -0.06), (7.0, 1.04), (9.0, 1.0), (19.0, 1.0), (25.0, 0.0), (30, 0.0)]
POINT_DIR = v(f=1.0, u=0.14, r=0.42)
c = clip('point', 30, False, look=('citizen', 3), events=[('point', 7), ('jab', 12.5), ('jab', 16)],
         views=((32, -12), (90, -2), (160, -10)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
for t in every(30):
    u = K(POINT_U, t)
    jab = bump(t, 11.0, 14.0) + 0.8 * bump(t, 14.8, 17.5)
    fr = F_(qt=bend(2.0 * u + 1.2 * jab, 9.0 * u, -1.0 * u))
    key_frame(c, t, fr, root=False, ease='linear')
    d = unit(POINT_DIR + np.array([0.0, 0.12 * jab, 0.0]))
    q_point = free(fr, d, D)
    c.key('right_arm', t, qslerp(hang('right_arm', fr), q_point, max(0.0, min(1.04, u))) if u >= 0
          else hang('right_arm', fr, fwd=-6.0 * u / 0.06), ease='linear')
    c.key('left_arm', t, hang('left_arm', fr, out=IDLE_ARM_OUT + 3.0 * u, fwd=-5.0 * u), ease='linear')
    c.key('head', t, qeuler(x=-3.0 * u + 4.0 * jab, y=16.0 * u), ease='linear')
c.mode('head', 'add')

# ---- bow (30): a courtly bow: the right foot slides back, the right hand comes across the belly and the left goes behind
# the back as the body bends deep from the hips (the hips going back over the heels), held a moment, and up
BOW_P = [(0, 0.0), (2.5, -2.0), (6.0, 14.0), (9.5, 36.0), (12.0, 40.0), (15.5, 38.0), (19.5, 20.0), (23.5, 2.0), (26.0, -1.5), (30, 0.0)]
BOW_Z = [(0, 0.0), (2.5, 0.0), (9.5, 1.6), (15.5, 1.6), (23.5, 0.0), (30, 0.0)]
BOW_FOOT = [(0, 0.0), (3.0, 0.0), (7.0, 4.5), (19.5, 4.5), (23.5, 0.0), (30, 0.0)]
BOW_ARMS = [(0, 0.0), (3.0, 0.0), (9.0, 1.0), (18.0, 1.0), (25.0, 0.0), (30, 0.0)]
BOW_HEAD = [(0, 0.0), (6.0, 2.0), (11.0, 8.0), (16.0, 8.0), (21.0, -2.0), (25.0, 0.0), (30, 0.0)]
c = clip('bow', 30, False, look=('noble', 1), events=[('bow', 12)], views=((32, -12), (90, -2), (150, -10)),
         traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))])
arms = Arms()
for t in every(30):
    feet = {'right_leg': (-1.9, K(BOW_FOOT, t)), 'left_leg': (1.9, 0.0)}
    pr = root_for(feet, z=K(BOW_Z, t), dip=0.5 * bump(t, 5.0, 20.0))
    fr = F_(qt=bend(K(BOW_P, t)), pr=pr)
    key_frame(c, t, fr, ease='linear')
    lift = 1.4 * (bump(t, 3.0, 7.0) + bump(t, 19.5, 23.5))
    plant(c, t, fr, right=feet['right_leg'], left=feet['left_leg'], lift=(lift, 0.0))
    w = smooth(K(BOW_ARMS, t))
    w_l = smooth(K(BOW_ARMS, t - 1.0))
    q_belly = arms.reach('right_arm', fr, torso_to_world(fr, (0.8, 10.0, -3.6)), palm=None)
    q_back = arms.reach('left_arm', fr, torso_to_world(fr, (-0.5, 10.0, 3.8)), palm=None)
    c.key('right_arm', t, qslerp(hang('right_arm', fr), q_belly, w), ease='linear')
    c.key('left_arm', t, qslerp(hang('left_arm', fr), q_back, w_l), ease='linear')
    c.key('head', t, qeuler(x=K(BOW_HEAD, t)), ease='linear')
c.mode('head', 'add')
for side in ('right_leg', 'left_leg'):
    check(c, f'{side} foot on the ground (t=12)', (leg_end(c, 12, side)[1],), (24.0,), tol=0.3)

# ---- yawn (40): a slump, then a deep breath in with the head going back and the right hand coming up over the mouth,
# the left arm stretching out behind; held with a shiver, then the hand drops, a shake of the head, and settle
Y_P = [(0, 0.0), (4.0, 2.0), (10.0, -6.0), (21.0, -8.0), (26.0, -1.0), (30.0, 2.5), (34.0, 0.5), (40, 0.0)]
Y_R = [(0, 0.0), (10.0, 2.5), (21.0, 3.5), (27.0, 0.0), (40, 0.0)]
Y_HP = [(0, 0.0), (4.0, 4.0), (10.0, -20.0), (17.0, -26.0), (21.0, -22.0), (26.0, -6.0), (30.0, 6.0), (33.5, 1.0), (40, 0.0)]
Y_HR = [(0, 0.0), (10.0, 7.0), (21.0, 9.0), (26.0, 2.0), (40, 0.0)]
Y_HAND = [(0, 0.0), (4.0, 0.0), (9.0, 1.0), (23.0, 1.0), (28.0, 0.0), (40, 0.0)]
Y_LEFT = [(0, 0.0), (6.0, 0.0), (11.0, 1.0), (22.0, 1.0), (27.0, 0.0), (40, 0.0)]
c = clip('yawn', 40, False, look=('citizen', 4), events=[('yawn', 12)], views=((32, -12), (90, -2), (0, -4)),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
arms = Arms()
for t in every(40):
    shiver = 1.2 * bump(t, 15.0, 22.0) * math.sin(2 * math.pi * (t - 15.0) / 2.5)
    fr = F_(qt=bend(K(Y_P, t) + 0.4 * shiver, 0.0, K(Y_R, t)))
    key_frame(c, t, fr, root=False, ease='linear')
    shake = 6.0 * bump(t, 29.5, 36.0) * math.sin(2 * math.pi * (t - 29.5) / 3.2)
    q_head = qeuler(x=K(Y_HP, t) + shiver, y=shake, z=K(Y_HR, t))
    mouth = head_point(fr, q_head, MOUTH + np.array([-0.8, 1.0, -3.4]))
    q_mouth = arms.reach('right_arm', fr, mouth, palm=B, palm_w=0.2)
    c.key('right_arm', t, qslerp(hang('right_arm', fr), q_mouth, smooth(K(Y_HAND, t))), ease='linear')
    wl = smooth(K(Y_LEFT, t))
    c.key('left_arm', t, hang('left_arm', fr, out=IDLE_ARM_OUT + 12.0 * wl, fwd=-9.0 * wl + 0.6 * shiver), ease='linear')
    c.key('head', t, q_head, ease='linear')
c.mode('head', 'add')

# ---- stretch (40): the fingers laced in front, a little hunch, then the joined hands pushed up in front and on overhead
# as the back arches; a lean to the left with them up there, then the arms sweep out to the sides and down, and settle
S_P = [(0, 0.0), (5.0, 4.0), (9.0, 1.0), (13.0, -6.0), (19.0, -10.0), (22.0, -6.0), (26.0, -3.0), (30.0, -1.0), (34.0, 1.0), (37.0, 0.0), (40, 0.0)]
S_R = [(0, 0.0), (19.0, 0.0), (23.5, 9.0), (26.0, 4.0), (29.0, 0.0), (40, 0.0)]
S_HP = [(0, 0.0), (5.0, 6.0), (12.0, -12.0), (20.0, -18.0), (24.0, -8.0), (30.0, -2.0), (34.0, 2.0), (40, 0.0)]
S_HR = [(0, 0.0), (19.0, 0.0), (23.5, 6.0), (27.0, 0.0), (40, 0.0)]
# the right hand's path in torso space while the hands are joined (the left mirrors it): low in front, out in front, overhead
S_PATH = [(4.0, (-1.0, 10.5, -4.5)), (7.0, (-0.9, 9.0, -6.0)), (10.0, (-1.6, 2.0, -10.0)), (12.5, (-3.8, -7.4, -2.0)),
          (19.0, (-4.2, -7.8, 0.0)), (25.5, (-4.4, -7.6, 0.0))]
S_OUT = [(25.5, 178.0), (30.0, 92.0), (34.0, 14.0), (36.5, 2.0), (40.0, IDLE_ARM_OUT)]
S_LIFT = [(0, 0.0), (12.0, 0.0), (16.0, 1.0), (22.0, 1.0), (27.0, 0.0), (35.0, 0.0), (37.0, -0.3), (40, 0.0)]
c = clip('stretch', 40, False, look=('citizen', 5), events=[('stretch', 19)], views=((32, -12), (90, -2), (0, -4)),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
arms = Arms()
for t in every(40):
    fr = F_(qt=bend(K(S_P, t), 0.0, K(S_R, t)))
    key_frame(c, t, fr, root=False, ease='linear')
    lift = K(S_LIFT, t)
    for side in ('right_arm', 'left_arm'):
        s = 1.0 if side == 'right_arm' else -1.0
        p = KV(S_PATH, t) * np.array([s, 1.0, 1.0])
        q_path = arms.reach(side, fr, torso_to_world(fr, p), palm=None)
        q_side = qeuler(z=s * K(S_OUT, t))
        if t < 4.0:
            q = hang(side, fr)
        elif t < 6.0:
            q = qslerp(hang(side, fr), q_path, smooth((t - 4.0) / 2.0))
        elif t < 25.5:
            q = q_path
        elif t < 28.0:
            q = qslerp(q_path, q_side, smooth((t - 25.5) / 2.5))
        else:
            q = q_side
        if t >= 40 - 1e-9:
            q = hang(side, fr)
        c.key(side, t, q, pos=(0.0, -1.0 * lift, 0.0) if abs(lift) > 1e-6 else None, ease='linear')
    c.key('head', t, qeuler(x=K(S_HP, t), z=K(S_HR, t)), ease='linear')
c.mode('head', 'add')

# ---- knock (24): the right fist raised to the door, three knocks (ticks 7, 10, 13), each with a little jolt through the
# body, the hand held up a moment, lowered; the head tipped to listen. The door's face half a block ahead (a door on the
# near side of the next block).
KNOCK_Z = -8.0
KNOCKS = (7.0, 10.0, 13.0)
KNOCK_GAP = 0.8        # the fist's middle this far off the door: its lower edge, tipped up, just meets it


def _knock_door():
    return [((-8, -24, KNOCK_Z - 3), (8, 24, KNOCK_Z), (118, 84, 54), True),
            ((-10.5, -26, KNOCK_Z - 4), (-8, 24, KNOCK_Z + 1), (84, 60, 40), True),
            ((8, -26, KNOCK_Z - 4), (10.5, 24, KNOCK_Z + 1), (84, 60, 40), True),
            ((-10.5, -26, KNOCK_Z - 4), (10.5, -23.5, KNOCK_Z + 1), (84, 60, 40), True),
            ((4.0, 4.0, KNOCK_Z), (5.0, 7.0, KNOCK_Z + 1.2), (60, 60, 66), True)]


STATIONS['knock_door'] = _knock_door
KN_P = [(0, 0.0), (3.0, 3.0), (6.0, 5.0), (14.0, 5.0), (18.0, 1.5), (21.0, -0.5), (24, 0.0)]
KN_YAW = [(0, 0.0), (4.0, -8.0), (14.0, -8.0), (19.0, -2.0), (24, 0.0)]
KN_RAISE = [(0, 0.0), (1.0, 0.0), (5.0, 1.0), (15.0, 1.0), (20.5, 0.0), (24, 0.0)]
KN_HEAD = [(0, 0.0), (4.0, -4.0), (14.0, -4.0), (17.0, 3.0), (24, 0.0)]
KN_LISTEN = [(0, 0.0), (14.0, 0.0), (17.0, 1.0), (21.0, 1.0), (24, 0.0)]


def knock_back(t):
    """How far the fist is drawn back from the door (0 on it): a quick hit at each knock, a slower draw back."""
    d = 1.0
    for k in KNOCKS:
        if k - 1.4 <= t <= k:
            d = min(d, 1.0 - smooth((t - (k - 1.4)) / 1.4) ** 2)
        elif k < t <= k + 1.6:
            d = min(d, smooth((t - k) / 1.6))
    return d


c = clip('knock', 24, False, look=('citizen', 6), station_='knock_door', events=[('knock', k) for k in KNOCKS],
         views=((32, -12, True), (90, -2), (150, -10)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
arms = Arms()
for t in every(24):
    jolt = sum(bump(t, k - 0.2, k + 1.2) for k in KNOCKS)
    fr = F_(qt=bend(K(KN_P, t) + 0.8 * jolt, K(KN_YAW, t), 0.0))
    key_frame(c, t, fr, root=False, ease='linear')
    hit = arms.reach('right_arm', fr, np.array([-2.0, -5.4, KNOCK_Z + KNOCK_GAP]), palm=D, palm_w=0.2)
    back = qmul(qaxis((1, 0, 0), -16.0), hit)
    q_up = qslerp(hit, back, knock_back(t))
    c.key('right_arm', t, qslerp(hang('right_arm', fr), q_up, smooth(K(KN_RAISE, t))), ease='linear')
    c.key('left_arm', t, hang('left_arm', fr, out=IDLE_ARM_OUT + 1.0 * jolt), ease='linear')
    li = K(KN_LISTEN, t)
    c.key('head', t, qeuler(x=K(KN_HEAD, t) + 1.5 * jolt, y=10.0 * li, z=6.0 * li), ease='linear')
c.mode('head', 'add')
for k in KNOCKS:
    check(c, f'knuckles on the door (t={k:g})', (ta.fk(c, k, 'right_arm', GRIP['right_arm'])[2],), (KNOCK_Z + KNOCK_GAP,), tol=0.3)

# ================================================================== ladders

# ---- climb (16, loop): hand over hand up a ladder, facing it, one limb after another four ticks apart as a crawl goes:
# the right hand lets go at tick 1, the left foot at 5, the left hand at 9, the right foot at 13. Each comes off its
# rung, up and out past the next one and down onto the one above that, two rungs (8 px) a go, 7 ticks off and 9 on. The
# body rises 0.5 px a tick all the while (the server moves it), so a hand or foot on a rung slides 4.5 px down the body
# while it holds. The arms are rigid, so the torso turns from side to side (the shoulder of the lower hand going back)
# and the shoulders shrug up for each reach and down for each pull to keep the fists on their rungs; the hips sink a
# little as each foot takes its rung and rise as the leg drives; the head looks up the ladder after the hand going up.
# The client plays it by height, not by time: tick 32 y mod 16 for feet y blocks up (2 ticks a px), so the rungs stay
# in the hands and under the feet at any speed, and a climber going down plays it backwards.
CL_LEN = 16.0
CL_RISE = 0.5                       # px a tick up the ladder
CL_MOVE = 7.0                       # ticks a hand or foot is off its rung
CL_HOLD = CL_LEN - CL_MOVE
CL_LET_GO = {'right_arm': 1.0, 'left_leg': 5.0, 'left_arm': 9.0, 'right_leg': 13.0}
CL_HAND = (4.8, -6.0)               # a fist on a rung: x out from the middle (by the rail), z (its front at the wall)
CL_IN = 1.0                         # the rung's middle that far into the fist from its end
CL_SLACK = (0.5, 0.6)               # and the fist free to sit that much off it along the rung (x) and up or down (y)
CL_FOOT = (2.2, -5.4)               # a sole's middle on a rung: x out from the middle, z (the toes to the wall)
CL_GRAB_Y = -6.0                    # about where a hand takes its next rung (the rung's middle)
CL_STEP_Y = 20.0                    # about where a foot takes its next rung (the rung's middle)
CL_ROOT_Z = -1.2
CL_PARTS = ('right_arm', 'left_arm', 'right_leg', 'left_leg')


def cl_rung_mid(t, near):
    """The middle of the rung nearest [near] at [t] (the rungs' middles 2 + 4k px up the block, the body risen 0.5 t)."""
    base = 22.0 + CL_RISE * t
    return base - 4.0 * round((base - near) / 4.0)


def cl_phase(part, t):
    """[part]'s rung at [t]: (the y of its middle, None) while it holds it, (None, how far through its move, 0 to 1)."""
    t_take = CL_LET_GO[part] + CL_MOVE
    mid0 = cl_rung_mid(t_take, CL_GRAB_Y if part.endswith('arm') else CL_STEP_Y)
    u = (t - t_take) % CL_LEN
    if u <= CL_HOLD + 1e-9:
        return mid0 + CL_RISE * u, None
    return None, (u - CL_HOLD) / CL_MOVE


def cl_side(part):
    return -1.0 if part.startswith('right') else 1.0


def cl_hold_point(part, mid):
    """Where the grip (a hand) or the sole's middle (a foot) is on a rung whose middle is at y [mid]."""
    s = cl_side(part)
    if part.endswith('arm'):
        return np.array([s * CL_HAND[0], mid - CL_IN, CL_HAND[1]])
    return np.array([s * CL_FOOT[0], mid - 1.0, CL_FOOT[1]])


def cl_target(part, t):
    """Where [part] is wanted at [t] (the grip of a hand, the sole's middle of a foot), and whether it is on a rung."""
    mid, u = cl_phase(part, t)
    if mid is not None:
        return cl_hold_point(part, mid), True
    t_off = CL_LET_GO[part]
    p0 = cl_hold_point(part, cl_phase(part, t_off)[0])
    p1 = cl_hold_point(part, cl_phase(part, t_off + CL_MOVE)[0])
    s = cl_side(part)
    if part.endswith('arm'):
        # off the rung (back from it and a touch down), up and out past the next one, over the one above and down onto it
        keys = [(0.0, p0), (0.14, p0 + np.array([0.0, 0.4, 1.6])),
                (0.55, p0 + (p1 - p0) * 0.62 + np.array([s * 1.4, -2.4, 3.2])),
                (0.86, p1 + np.array([0.0, -1.3, 1.2])), (1.0, p1)]
    else:
        # the heel up and the toes back off the rung, up and out past the next one, over the one above and down onto it
        keys = [(0.0, p0), (0.15, p0 + np.array([0.0, -0.5, 1.8])),
                (0.55, p0 + (p1 - p0) * 0.6 + np.array([s * 0.3, -1.2, 3.0])),
                (0.85, p1 + np.array([0.0, -1.0, 1.0])), (1.0, p1)]
    return KV(keys, u), False


def cl_shrug(side, t):
    """The shoulder raised (-y) to reach up for a rung and pulled down through the hold: the arm's pivot offset."""
    mid, u = cl_phase(side, t)
    if mid is not None:
        return -1.0 + 1.6 * smooth((t - CL_LET_GO[side] - CL_MOVE) % CL_LEN / CL_HOLD)
    return 0.6 - 1.6 * smooth(u)


def cl_wave(t, at):
    """A cycle's cosine, peaking at tick [at]."""
    return math.cos(2 * math.pi * (t - at) / CL_LEN)


def cl_drop(t):
    """The hips' bob: lowest as a foot takes its rung (ticks 12, 4), highest as its leg drives (8, 16)."""
    return 0.35 * math.cos(2 * math.pi * (t - 4.5) / 8.0)


def cl_solve():
    sols, guess = {}, None
    for lap in range(2):
        for t in ticks(0, CL_LEN - 0.5, 0.5):
            want = {p: cl_target(p, t) for p in CL_PARTS}
            pos = {s: np.array([0.0, cl_shrug(s, t), 0.0]) for s in ('right_arm', 'left_arm')}

            def res(fr, qa, qb, want=want, pos=pos):
                out = []
                for side, q in (('right_arm', qa), ('left_arm', qb)):
                    Rp, j = chain_frame(side, qr=fr['qr'], pr=fr['pr'], qt=fr['qt'], vt=fr['vt'], arm_pos=pos[side])
                    e = j + Rp @ qmat(q) @ GRIP[side] - want[side][0]
                    if want[side][1]:
                        ex = 0.0 if abs(e[0]) <= CL_SLACK[0] else e[0] - math.copysign(CL_SLACK[0], e[0])
                        ey = 0.0 if abs(e[1]) <= CL_SLACK[1] else e[1] - math.copysign(CL_SLACK[1], e[1])
                        out += [ex * 3.0, ey * 3.0, e[2] * 3.0]
                    else:
                        out += list(e * 0.4)
                for side in ('right_leg', 'left_leg'):
                    hip = np.array(PIVOTS[side], float) + fr['pr']
                    n = float(np.linalg.norm(want[side][0] - hip))
                    out.append(max(0.0, n - 11.85) * (4.0 if want[side][1] else 1.0))
                return out
            fr, qa, qb, guess = body_solve(res, pitch0=-8.0, yaw0=14.0 * cl_wave(t, 1.0),
                                           roll0=5.0 * math.sin(2 * math.pi * (t - 0.5) / CL_LEN),
                                           root0=(0.0, 0.0, CL_ROOT_Z), guess=guess, pitch_w=0.05, yaw_w=0.04,
                                           roll_w=0.06, root_w=0.3, drop=cl_drop(t))
            sols[t] = (fr, qa, qb)
    sols[CL_LEN] = sols[0.0]
    return sols


CL_SOL = cl_solve()


def cl_look(fr, t):
    """Up the ladder, after the hand going up (the right in the first half, the left in the second)."""
    side = -math.sin(2 * math.pi * (t - 2.0) / CL_LEN)
    d = np.array([math.sin(math.radians(9.0)) * side, -math.sin(math.radians(24.0)), -math.cos(math.radians(24.0))])
    return np.array([0.0, -4.0, -3.0]) + fr['pr'] + 40.0 * d


c = clip('climb', 16, True, look=('citizen', 3), station_='ladder',
         events=[('grab', 0), ('step', 4), ('grab', 8), ('step', 12)],
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
         views=((150, -10), (90, -2), (30, -8, True)), pan=(0.0, -4.0, -2.0))
ta.INFO['climb']['rise'] = lambda t: CL_RISE * t
for t in every(16):
    fr, qa, qb = CL_SOL[t]
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, pos=(0.0, cl_shrug('right_arm', t), 0.0), ease='linear')
    c.key('left_arm', t, qb, pos=(0.0, cl_shrug('left_arm', t), 0.0), ease='linear')
    for side in ('right_leg', 'left_leg'):
        leg_to(c, t, side, fr['pr'], cl_target(side, t)[0], toe=F)
    c.key('head', t, head_at(fr, cl_look(fr, t)), ease='linear')
c.mode('head', 'set')
seal(c)
for part in CL_PARTS:
    t_take = CL_LET_GO[part] + CL_MOVE
    for k in (t_take, t_take + CL_HOLD / 2, t_take + CL_HOLD):
        k = k % CL_LEN
        p = GRIP[part] if part.endswith('arm') else (0.0, 12.0, 0.0)
        got = ta.fk(c, k, part, p)
        want = cl_target(part, k)[0]
        if part.endswith('arm'):
            got = got.copy()
            for i, sl in ((0, CL_SLACK[0]), (1, CL_SLACK[1])):
                d = got[i] - want[i]
                if abs(d) <= sl:
                    got[i] = want[i]
        for i, sl in ((0, CL_SLACK[0]), (1, CL_SLACK[1])):
            d = got[i] - want[i]
            if part.endswith('arm') and abs(d) > sl:
                got[i] -= math.copysign(sl, d)
        check(c, f'{part} on its rung (t={k:g})', got, want, tol=0.35)

# ---- climb_top (64, once; played in climb's place over the last two blocks of a ladder that ends at a ledge ahead,
# by height as climb is: tick 0 two blocks under the top, where climb's own tick 0 is too, 64 with the feet level with
# the ledge). No Activity plays it: LivingProps does, when the climbable blocks someone is on end within two blocks
# over their feet and there is a ledge ahead to step up onto. The rungs run out: the right hand comes off the second
# rung from the top (tick 1) and up over the edge onto the ledge (9.5), the left off the top rung onto it beside it
# (10 to 19); the feet go on up the rungs as climb's do while the body rises between the hands, hanging back on
# straight arms as they pass the shoulders, then leaning out over them as they press down; the hands come away (48,
# 50) as the ledge sinks past the hips, the left foot steps off the last rung but one up onto the ledge (53 to 60),
# and the climber stands at the top, one foot on the top rung and one on the ledge, leaning into the step that walking
# away carries on (LivingProps fades it into the walk as the climber steps off forward).
CT_LEN = 64.0
CT_EDGE = -9.0                          # a fist on the ledge: its middle this far forward (the wall's face at -8)
CT_FOOT_Z = -9.8                        # the left foot's sole on the ledge, its middle
CT_HAND_X = 4.6                         # a fist on the ledge, out from the middle


def ct_ledge(t):
    """The ledge's top at [t] (y): 32 px over the feet at tick 0, level with them at 64."""
    return -8.0 + CL_RISE * t


def ct_rung(k, t):
    """The middle of rung [k] at [t] (0 the bottom one of the last two blocks, 7 the top one, 2 px under the ledge)."""
    return 22.0 + CL_RISE * t - 4.0 * k


# Each limb's holds, (from, to, (what, ...)), what is 'rung' (its number) or 'ledge'; between two holds a move. The
# right foot is still moving at tick 0 (climb's step that takes its rung at 4) and follows climb's own path there.
CT_HOLDS = {
    'right_arm': [(0.0, 1.0, ('rung', 6)), (9.5, 48.0, ('ledge',))],
    'left_arm': [(0.0, 10.0, ('rung', 7)), (19.0, 50.0, ('ledge',))],
    'right_leg': [(4.0, 13.0, ('rung', 1)), (20.0, 29.0, ('rung', 3)), (36.0, 45.0, ('rung', 5)), (52.0, 64.0, ('rung', 7))],
    'left_leg': [(0.0, 5.0, ('rung', 0)), (12.0, 21.0, ('rung', 2)), (28.0, 37.0, ('rung', 4)), (44.0, 53.0, ('rung', 6)),
                 (60.0, 64.0, ('ledge',))],
}
CT_FREE = {'right_arm': 48.0, 'left_arm': 50.0}     # the hands off the ledge for good from here


def ct_hold_point(part, what, t):
    """Where [part] holds [what] at [t]: a grip (a hand) or the middle of a sole (a foot)."""
    s = cl_side(part)
    if what[0] == 'rung':
        return cl_hold_point(part, ct_rung(what[1], t))
    if part.endswith('arm'):
        return np.array([s * CT_HAND_X, ct_ledge(t) - 1.4, CT_EDGE])
    return np.array([s * CL_FOOT[0], ct_ledge(t), CT_FOOT_Z])


def ct_target(part, t):
    """Where [part] is wanted at [t] and how: ('hold', point, what), ('move', point, None) or ('free', None, None) (a
    hand off the ledge for good, keyed by itself after)."""
    holds = CT_HOLDS[part]
    for a, b, what in holds:
        if a - 1e-9 <= t <= b + 1e-9:
            return 'hold', ct_hold_point(part, what, t), what
    if part in CT_FREE and t >= CT_FREE[part]:
        return 'free', None, None
    if part == 'right_leg' and t < holds[0][0]:
        return 'move', cl_target(part, t)[0], None
    i = next(i for i in range(len(holds) - 1) if holds[i][1] < t < holds[i + 1][0])
    (_, t0, w0), (t1, _, w1) = holds[i], holds[i + 1]
    p0, p1 = ct_hold_point(part, w0, t0), ct_hold_point(part, w1, t1)
    u = (t - t0) / (t1 - t0)
    s = cl_side(part)
    if part.endswith('arm'):
        # off the rung (back from it and a touch down), up past the top of the wall in front of it, over the edge and
        # down onto the ledge
        edge = ct_ledge(t0 + (t1 - t0) * 0.6)
        keys = [(0.0, p0), (0.14, p0 + np.array([0.0, 0.4, 1.6])),
                (0.6, np.array([s * (CT_HAND_X + 0.8), edge - 2.5, -7.0])),
                (0.86, p1 + np.array([0.0, -1.6, 0.8])), (1.0, p1)]
    elif w1[0] == 'ledge':
        # the heel up and the toes back off the rung, up in front of the wall to over the ledge, forward over the edge
        # and down onto it
        edge = ct_ledge(t0 + (t1 - t0) * 0.55)
        keys = [(0.0, p0), (0.18, p0 + np.array([0.0, -0.8, 1.8])),
                (0.55, np.array([s * (CL_FOOT[0] + 0.3), edge - 2.6, -5.6])),
                (0.85, p1 + np.array([0.0, -1.4, 1.2])), (1.0, p1)]
    else:
        keys = [(0.0, p0), (0.15, p0 + np.array([0.0, -0.5, 1.8])),
                (0.55, p0 + (p1 - p0) * 0.6 + np.array([s * 0.3, -1.2, 3.0])),
                (0.85, p1 + np.array([0.0, -1.0, 1.0])), (1.0, p1)]
    return 'move', KV(keys, u), None


def ct_fade(t, a, b):
    """1 until [a], easing to 0 at [b]."""
    return 1.0 - smooth(min(1.0, max(0.0, (t - a) / (b - a))))


# The torso and the hips: climb's lean back and sway at first, easing out once both hands are on the ledge; then
# upright as the body rises between the hands, leaning out over them as they press down, and into the step at the top.
CT_PITCH = [(0, -8.0), (6.0, -6.0), (12.0, -4.0), (19.0, -6.0), (27.0, -5.0), (34.0, 0.0), (40.0, 12.0), (45.0, 28.0),
            (49.0, 34.0), (53.0, 30.0), (58.0, 22.0), (64.0, 14.0)]
CT_ROOT_Z = [(0, CL_ROOT_Z), (24.0, -1.2), (34.0, -1.6), (42.0, -2.6), (50.0, -3.6), (58.0, -4.6), (64.0, -5.0)]
CT_SINK = [(0, 0.0), (48.0, 0.0), (58.0, 0.8), (64.0, 1.2)]


def ct_pose_aims(t):
    yaw = 14.0 * cl_wave(t, 1.0) * ct_fade(t, 2.0, 16.0)
    roll = 5.0 * math.sin(2 * math.pi * (t - 0.5) / CL_LEN) * ct_fade(t, 2.0, 16.0)
    drop = cl_drop(t) * ct_fade(t, 50.0, 58.0) + K(CT_SINK, t)
    return K(CT_PITCH, t), yaw, roll, K(CT_ROOT_Z, t), drop


ARM_END = {'right_arm': [np.array([x, 10.0, z]) for x in (-3.0, 1.0) for z in (-2.0, 2.0)],
           'left_arm': [np.array([x, 10.0, z]) for x in (-1.0, 3.0) for z in (-2.0, 2.0)]}


def ct_wall(p, t, w=3.0):
    """A world point inside the wall under the ledge (forward of its face, below its top): how far in, weighted."""
    x, y, z = p
    top = ct_ledge(t)
    if z < -8.0 and y > top:
        return min(-8.0 - z, y - top) * w
    return 0.0


def ct_shrug(side, t):
    """The shoulder raised to reach (-y) and pulled down to hold, as climb's, then pressed down on the ledge."""
    kind = ct_target(side, t)[0]
    if t <= 1.0 or (side == 'left_arm' and t <= 10.0):
        return cl_shrug(side, t)
    holds = CT_HOLDS[side]
    a, b = holds[-1][0], holds[-1][1]
    if kind == 'move':
        t0 = holds[0][1]
        u = (t - t0) / (a - t0)
        start = cl_shrug(side, t0)
        return start + (-1.0 - start) * smooth(u)
    if kind == 'hold':
        return -1.0 + 1.8 * smooth((t - a) / (b - a))
    return 0.8 * ct_fade(t, b, b + 8.0)


def ct_solve():
    sols, guess = {}, None
    for t in ticks(0, CT_LEN, 0.5):
        want = {p: ct_target(p, t) for p in CL_PARTS}
        pos = {s: np.array([0.0, ct_shrug(s, t), 0.0]) for s in ('right_arm', 'left_arm')}

        def res(fr, qa, qb, want=want, pos=pos, t=t):
            out = []
            for side, q in (('right_arm', qa), ('left_arm', qb)):
                kind, p, what = want[side]
                Rp, j = chain_frame(side, qr=fr['qr'], pr=fr['pr'], qt=fr['qt'], vt=fr['vt'], arm_pos=pos[side])
                Ra = Rp @ qmat(q)
                if kind == 'free':
                    out += [0.0, 0.0, 0.0, 0.0]
                    continue
                e = j + Ra @ GRIP[side] - p
                if kind == 'hold' and what[0] == 'rung':
                    ex = 0.0 if abs(e[0]) <= CL_SLACK[0] else e[0] - math.copysign(CL_SLACK[0], e[0])
                    ey = 0.0 if abs(e[1]) <= CL_SLACK[1] else e[1] - math.copysign(CL_SLACK[1], e[1])
                    out += [ex * 3.0, ey * 3.0, e[2] * 3.0, 0.0]
                elif kind == 'hold':
                    # on the ledge: the fist's lowest corner on its top, its middle where it was put (a little give)
                    low = max(float((j + Ra @ c_)[1]) for c_ in ARM_END[side])
                    ex = 0.0 if abs(e[0]) <= 0.4 else e[0] - math.copysign(0.4, e[0])
                    ez = 0.0 if abs(e[2]) <= 0.6 else e[2] - math.copysign(0.6, e[2])
                    out += [ex * 3.0, 0.0, ez * 3.0, (low - ct_ledge(t)) * 3.0]
                else:
                    out += list(e * 0.4) + [0.0]
            for side in ('right_leg', 'left_leg'):
                kind, p, _ = want[side]
                hip = np.array(PIVOTS[side], float) + fr['pr']
                n = float(np.linalg.norm(p - hip))
                out.append(max(0.0, n - 11.85) * (4.0 if kind == 'hold' else 1.0))
            for corner in ((-4.0, 0.0, -2.0), (4.0, 0.0, -2.0), (-4.0, 6.0, -2.0), (4.0, 6.0, -2.0), (0.0, 11.0, -2.0)):
                out.append(ct_wall(torso_to_world(fr, corner), t))
            return out
        pitch, yaw, roll, rz, drop = ct_pose_aims(t)
        fr, qa, qb, guess = body_solve(res, pitch0=pitch, yaw0=yaw, roll0=roll, root0=(0.0, 0.0, rz), guess=guess,
                                       pitch_w=0.05, yaw_w=0.04, roll_w=0.06, root_w=0.3, drop=drop)
        sols[t] = (fr, qa, qb)
    return sols


CT_SOL = ct_solve()


def ct_look(fr, t):
    """Up the ladder as climb looks, then at the edge between the hands, over the ledge, and ahead."""
    w1 = smooth(min(1.0, max(0.0, (t - 1.0) / 8.0)))
    w2 = smooth(min(1.0, max(0.0, (t - 30.0) / 16.0)))
    w3 = smooth(min(1.0, max(0.0, (t - 50.0) / 12.0)))
    edge = np.array([0.0, ct_ledge(t) - 1.0, -9.0])
    over = np.array([0.0, ct_ledge(t) - 1.0, -26.0])
    ahead = fr['pr'] + np.array([0.0, -4.0, -48.0])
    p = cl_look(fr, t % CL_LEN) * (1 - w1) + edge * w1
    p = p * (1 - w2) + over * w2
    return p * (1 - w3) + ahead * w3


c = clip('climb_top', 64, False, look=('citizen', 3), station_='ladder_top',
         events=[('step', 4), ('palm', 9.5), ('step', 12), ('palm', 19), ('step', 20), ('step', 28), ('step', 36),
                 ('step', 44), ('step', 52), ('ledge', 60)],
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255)),
                 ('left_leg', (0.0, 12.0, 0.0), (180, 255, 160))],
         views=((150, -10), (90, -2), (30, -8, True)), pan=(0.0, -4.0, -2.0))
ta.INFO['climb_top']['rise'] = lambda t: CL_RISE * t
for t in every(64):
    fr, qa, qb = CL_SOL[0.0] if t == 0 else CT_SOL[t]
    key_frame(c, t, fr, ease='linear')
    for side, q in (('right_arm', qa), ('left_arm', qb)):
        if ct_target(side, t)[0] == 'free':
            # off the ledge: from where it let go to hanging a little forward, swung against the step up
            t0 = CT_FREE[side]
            f0, a0, b0 = CT_SOL[t0]
            q0 = rel(fr, qmul(qmul(f0['qr'], f0['qt']), a0 if side == 'right_arm' else b0))
            u = smooth(min(1.0, (t - t0) / 8.0))
            swing = (14.0 if side == 'right_arm' else -9.0) * bump(t, 52.0, 66.0)
            q = qslerp(q0, hang(side, fr, out=6.0, fwd=6.0 + swing), u)
        c.key(side, t, q, pos=(0.0, ct_shrug(side, t), 0.0), ease='linear')
    for side in ('right_leg', 'left_leg'):
        leg_to(c, t, side, fr['pr'], ct_target(side, t)[1], toe=F)
    c.key('head', t, head_at(fr, ct_look(fr, t)), ease='linear')
c.mode('head', 'set')
for part in CL_PARTS:
    for a, b, what in CT_HOLDS[part]:
        for k in (a, (a + b) / 2, b):
            arm = part.endswith('arm')
            got = ta.fk(c, k, part, GRIP[part] if arm else (0.0, 12.0, 0.0))
            want = ct_hold_point(part, what, k)
            if arm:
                # a fist may sit a little off its mark (on a rung CL_SLACK; on the ledge 0.4 across, 0.6 along), and
                # on the ledge it is the fist's lowest corner, not its grip, that sits on the top
                got, want = got.copy(), want.copy()
                for i, sl in enumerate((CL_SLACK[0], CL_SLACK[1], 0.0) if what[0] == 'rung' else (0.4, None, 0.6)):
                    if sl is None:
                        got[i] = max(float(ta.fk(c, k, part, c_)[1]) for c_ in ARM_END[part])
                        want[i] = ct_ledge(k)
                        continue
                    d = got[i] - want[i]
                    got[i] = want[i] if abs(d) <= sl else got[i] - math.copysign(sl, d)
            label = f'{part} on the {what[0]}' + (f' {what[1]}' if what[0] == 'rung' else '')
            check(c, f'{label} (t={k:g})', got, want, tol=0.35)
join('climb_top starts where climb is at its tick 0', getc('climb'), 0, c, 0, tol=0.05)

# ================================================================== eating and washing

class Holds(Arms):
    """Arms that may roll the shoulder a little (the key's offset) to bring a held thing close to the face."""

    def slack(self, side, fr, points=(), dirs=(), slack=1.0, limit=1.6):
        q, off = solve_arm_slack(side, fr, list(points), list(dirs), start=self.prev.get(side), slack=slack, limit=limit)
        self.prev[side] = q
        return q, off


def mix_arm(a, b, w):
    """Two (key, offset) arm poses mixed: [w] 0 is [a], 1 is [b]."""
    return qslerp(a[0], b[0], w), np.asarray(a[1], float) * (1.0 - w) + np.asarray(b[1], float) * w


def key_arm(c, side, t, pose):
    q, off = pose
    off = np.asarray(off, float)
    c.key(side, t, q, pos=off if float(np.linalg.norm(off)) > 1e-6 else None, ease='linear')


def chew(t, a, b, amp=1.4, period=6.0):
    """A chewing bob of the head (degrees) from [a] to [b]."""
    return amp * bump(t, a, b) * math.sin(2 * math.pi * (t - a) / period)


# ---- eat (60, loop): a loaf (or an apple: the same clip) held at the chest; a look at it, raised to the mouth and bitten
# (tick 12) with a tug of the hand to tear the bite off, lowered, chewed while looking about; crumbs brushed off the
# chest with the left hand; a swallow, and round again
LOAF_TOP = (0.0, -3.2, -3.0)            # loaf part space: the bitten end
LOAF_MID = (0.0, -0.8, -3.0)
EAT_HOLD = np.array([-2.8, 8.0, -7.4])
EAT_BITE = [(0, 0.0), (6.0, 0.0), (11.0, 1.0), (14.0, 1.0), (19.5, 0.0), (60, 0.0)]
EAT_P = [(0, 1.0), (8.0, 3.0), (11.0, 7.0), (13.0, 7.5), (18.0, 2.0), (40.0, 0.5), (60, 1.0)]
EAT_HP = [(0, 10.0), (5.0, 14.0), (9.0, 4.0), (12.0, 3.0), (14.0, 0.0), (18.0, 4.0), (24.0, 0.0), (36.0, -2.0), (46.0, 4.0), (52.0, 6.0), (60, 10.0)]
EAT_HY = [(0, 6.0), (5.0, 8.0), (10.0, 2.0), (14.0, 0.0), (22.0, -4.0), (30.0, -14.0), (38.0, -10.0), (44.0, 8.0), (52.0, 6.0), (60, 6.0)]
EAT_SWIPE = [(0, 0.0), (28.0, 0.0), (31.0, 1.0), (36.0, 1.0), (39.5, 0.0), (60, 0.0)]
c = clip('eat', 60, True, look=('citizen', 0), tools_=('loaf',), events=[('bite', 12)],
         views=((32, -12), (90, -2), (0, -4)), traces=[('loaf', LOAF_TOP, (240, 200, 140))])
arms, bites, swipes = Arms(), Holds(), Arms()
for t in every(60):
    tear = bump(t, 12.0, 15.0)
    fr = F_(qt=bend(K(EAT_P, t, 60), 2.0 * math.sin(2 * math.pi * t / 60), 0.0))
    key_frame(c, t, fr, root=False, ease='linear')
    q_head = qeuler(x=K(EAT_HP, t, 60) - 5.0 * tear + chew(t, 14.0, 46.0), y=K(EAT_HY, t, 60))
    hold = (arms.solve('right_arm', fr, [(tool_point('loaf', LOAF_MID), EAT_HOLD + np.array([0.0, 0.3 * math.sin(2 * math.pi * t / 30), 0.0]), 1.0)],
                       [(tool_dir('loaf', (0, -1, 0)), v(u=1.0, f=-0.15), 0.8)]), np.zeros(3))
    w = smooth(K(EAT_BITE, t))
    if w > 1e-4:
        lips = head_point(fr, q_head, LIPS) + np.array([0.0, 1.2, -0.8]) * tear
        bite = bites.slack('right_arm', fr, [(tool_point('loaf', LOAF_TOP), lips, 3.0)],
                          [(tool_dir('loaf', (0, -1, 0)), v(f=-1.0, u=0.7, r=0.2), 0.4)], limit=2.4)
        pose = mix_arm(hold, bite, w)
    else:
        pose = hold
    key_arm(c, 'right_arm', t, pose)
    ws = smooth(K(EAT_SWIPE, t))
    swipe = swipes.reach('left_arm', fr, torso_to_world(fr, (0.4 + 1.8 * math.sin(2 * math.pi * (t - 31.0) / 2.5), 5.0, -3.2)), palm=B)
    c.key('left_arm', t, qslerp(hang('left_arm', fr), swipe, ws), ease='linear')
    c.key('head', t, q_head, ease='linear')
c.mode('head', 'set')
seal(c)
_fr = frame_at(c, 12)
check(c, 'loaf at the lips (t=12)', ta.fk(c, 12, 'loaf', LOAF_TOP),
      head_point(_fr, c.sample('head', 12)[0], LIPS), tol=0.6)

# ---- sit_eat (60, loop): seated at a table, a bowl of stew held up over it on the left hand; the spoon dipped, a scoop,
# up to the mouth as the head comes down to meet it (ticks 16 and 46), chewed; the second spoonful taken a little more
# hungrily, the bowl lifted towards it
BOWL_BASE = (0.0, -2.0, 1.0)            # bowl part space: the middle of its bottom (on the hand)
BOWL_TOP = (0.0, -3.9, 1.0)             # the middle of the stew's surface
SPOON_BOWL = (0.0, 4.7, -0.2)           # spoon part space: the middle of its bowl
BOWL_AT = [(0, (2.0, 12.0, -10.4)), (12.0, (1.8, 11.4, -10.0)), (20.0, (2.0, 12.0, -10.4)), (40.0, (1.6, 11.0, -9.8)),
           (48.0, (1.5, 10.6, -9.6)), (54.0, (2.0, 12.0, -10.4)), (60, (2.0, 12.0, -10.4))]     # the bottom's middle, held
                                                                                                # 3 px over the table
SE_P = [(0, 9.0), (4.0, 10.0), (10.0, 12.0), (16.0, 15.0), (20.0, 11.0), (30.0, 9.0), (34.0, 10.0), (40.0, 13.0), (46.0, 17.0), (50.0, 12.0), (60, 9.0)]
SE_HP = [(0, 18.0), (5.0, 22.0), (10.0, 12.0), (15.0, 3.0), (17.0, 2.0), (20.0, 8.0), (26.0, 16.0), (30.0, 18.0), (35.0, 22.0), (40.0, 10.0), (45.0, 1.0), (47.0, 0.0), (50.0, 8.0), (56.0, 16.0), (60, 18.0)]
SE_HY = [(0, 6.0), (10.0, 3.0), (16.0, 0.0), (24.0, 5.0), (30.0, 6.0), (40.0, 3.0), (46.0, 0.0), (54.0, 4.0), (60, 6.0)]
SE_MOUTH = [(0, 0.0), (9.0, 0.0), (15.0, 1.0), (17.0, 1.0), (21.0, 0.0), (39.0, 0.0), (45.0, 1.0), (47.0, 1.0), (51.0, 0.0), (60, 0.0)]
# the spoon's bowl about the stew (off its surface's middle) between mouthfuls: hovering, dipped in, scooped towards the
# near rim and lifted
SE_STEW = [(0, (-0.6, -3.0, 1.4)), (3.0, (0.4, 0.5, -0.8)), (6.0, (0.0, 0.4, 0.8)), (9.0, (-0.4, -2.4, 1.8)),
           (21.0, (-0.8, -3.4, 1.6)), (30.0, (-0.6, -3.0, 1.4)), (33.0, (0.4, 0.5, -0.8)), (36.0, (0.0, 0.4, 0.8)),
           (39.0, (-0.4, -2.4, 1.8)), (51.0, (-0.8, -3.4, 1.6)), (60, (-0.6, -3.0, 1.4))]
SE_AXIS_TABLE = v(f=0.35, u=-0.75, r=-0.55)
SE_AXIS_MOUTH = v(f=-0.6, u=0.2, r=-0.8)
c = clip('sit_eat', 60, True, look=('citizen', 3), tools_=('bowl', 'spoon'), station_='table_seat',
         events=[('spoon', 16), ('spoon', 46)], views=((40, -14, True), (90, -2), (-40, -20)),
         traces=[('spoon', SPOON_BOWL, (240, 220, 160))])
arms, bowls = Holds(), Holds()
for t in every(60):
    fr = F_(qt=bend(K(SE_P, t, 60), 0.0, 0.6 * math.sin(2 * math.pi * t / 60)), pr=(0, SEAT_DROP, 0))
    key_frame(c, t, fr, ease='linear')
    seat_legs(c, t, ease='linear', spread=10.0)
    q_bowl, off_bowl = bowls.slack('left_arm', fr, [(tool_point('bowl', BOWL_BASE), KV(BOWL_AT, t, 60), 3.0)],
                                   [(tool_dir('bowl', (0, -1, 0)), U, 2.0)], limit=2.4)
    key_arm(c, 'left_arm', t, (q_bowl, off_bowl))
    Ra, ja = arm_world('left_arm', q_bowl, arm_pos=off_bowl, **fr)
    stew = ja + Ra @ tool_point('bowl', BOWL_TOP)
    chewing = chew(t, 17.0, 32.0) + chew(t, 47.0, 60.0, period=6.0) + chew(t, -13.0, 2.0, period=6.0)
    q_head = qeuler(x=K(SE_HP, t, 60) + chewing, y=K(SE_HY, t, 60))
    wm = smooth(K(SE_MOUTH, t))
    lips = head_point(fr, q_head, LIPS) + np.array([0.0, 0.0, 0.4])
    target = (stew + KV(SE_STEW, t, 60)) * (1.0 - wm) + lips * wm
    axis = unit(SE_AXIS_TABLE * (1.0 - wm) + SE_AXIS_MOUTH * wm)
    pose = arms.slack('right_arm', fr, [(tool_point('spoon', SPOON_BOWL), target, 3.0)],
                      [(tool_dir('spoon', (0, 1, 0)), axis, 0.5)], limit=2.4)
    key_arm(c, 'right_arm', t, pose)
    c.key('head', t, q_head, ease='linear')
c.mode('head', 'set')
seal(c)
for _t in (16, 46):
    _fr = frame_at(c, _t)
    check(c, f'spoon at the lips (t={_t})', ta.fk(c, _t, 'spoon', SPOON_BOWL),
          head_point(_fr, c.sample('head', _t)[0], LIPS) + np.array([0.0, 0.0, 0.4]), tol=0.8)
for _t in (5.0, 35.0):
    check(c, f'spoon in the stew (t={_t:g})', (ta.fk(c, _t, 'spoon', SPOON_BOWL)[1] - ta.fk(c, _t, 'bowl', BOWL_TOP)[1],),
          (0.5,), tol=0.6)
_up = ta.fk(c, 30, 'bowl', (0.0, -3.9, 1.0)) - ta.fk(c, 30, 'bowl', BOWL_BASE)
check(c, 'bowl level (t=30: its up off the vertical, px over 1.9)', (math.hypot(_up[0], _up[2]),), (0.0,), tol=0.3)
check(c, 'bowl over the table (t=30: its bottom above the top)', (min(0.0, TABLE_TOP - 0.5 - ta.fk(c, 30, 'bowl', BOWL_BASE)[1]),), (0.0,), tol=0.01)

# ---- wash (50): at a basin (a cauldron or trough a block ahead): bending over it, both hands into the water, scooped
# (tick 12) and splashed up onto the face as the head comes down to meet them (tick 18), the face rubbed, the head shaken
# (tick 27), up again flicking the water off the hands
WASH_P = [(0, 0.0), (3.0, -2.0), (9.0, 38.0), (12.0, 40.0), (16.0, 30.0), (18.0, 31.0), (24.0, 30.0), (30.0, 28.0), (36.0, 4.0), (39.0, -3.0), (43.0, 1.0), (50, 0.0)]
WASH_Z = [(0, 0.0), (3.0, 0.0), (10.0, 1.8), (30.0, 1.6), (37.0, 0.0), (50, 0.0)]
WASH_DIP = [(0, 0.0), (4.0, 0.0), (8.0, 1.0), (12.0, 1.0), (14.0, 0.0), (50, 0.0)]      # hands in the water
WASH_FACE = [(0, 0.0), (12.5, 0.0), (17.0, 1.0), (24.0, 1.0), (27.0, 0.0), (50, 0.0)]  # hands at the face
WASH_HP = [(0, 0.0), (4.0, 6.0), (10.0, 18.0), (14.0, 4.0), (17.0, -4.0), (24.0, -2.0), (28.0, 8.0), (34.0, 4.0), (38.0, -6.0), (43.0, 0.0), (50, 0.0)]
WASH_FLICK = [(0, 0.0), (36.0, 0.0), (38.0, 1.0), (43.0, 1.0), (46.0, 0.0), (50, 0.0)]
c = clip('wash', 50, False, look=('citizen', 5), station_='cauldron', events=[('scoop', 12), ('splash', 18), ('shake', 27)],
         views=((32, -12, True), (90, -2), (150, -10)),
         traces=[('right_arm', GRIP['right_arm'], (160, 200, 255))])
dips, faces = Arms(), Arms()
for t in every(50):
    feet = {'right_leg': (-1.9, -0.5), 'left_leg': (1.9, 0.5)}
    pr = root_for(feet, z=K(WASH_Z, t), dip=0.6 * bump(t, 4.0, 34.0))
    fr = F_(qt=bend(K(WASH_P, t)), pr=pr)
    key_frame(c, t, fr, ease='linear')
    plant(c, t, fr, right=feet['right_leg'], left=feet['left_leg'])
    shake = 10.0 * bump(t, 25.0, 31.0) * math.sin(2 * math.pi * (t - 25.0) / 2.0)
    q_head = qeuler(x=K(WASH_HP, t), y=shake)
    rub = 1.4 * bump(t, 19.0, 25.0) * math.sin(2 * math.pi * (t - 19.0) / 3.0)
    wd, wf, wk = smooth(K(WASH_DIP, t)), smooth(K(WASH_FACE, t)), K(WASH_FLICK, t)
    for side in ('right_arm', 'left_arm'):
        s = -1.0 if side == 'right_arm' else 1.0
        water = np.array([s * 1.6, 11.0, -13.2])
        face = head_point(fr, q_head, np.array([s * 1.7, -2.0 + rub * (1 if s < 0 else -1), -6.6]))
        q_water = dips.reach(side, fr, water, palm=U, palm_w=0.2) if wd > 1e-4 else None
        q_face = faces.reach(side, fr, face, palm=B, palm_w=0.2) if wf > 1e-4 else None
        q = hang(side, fr, fwd=4.0 * bump(t, 2.0, 8.0))
        if q_water is not None:
            q = qslerp(q, q_water, wd)
        if q_face is not None:
            q = qslerp(q, q_face, wf)
        flick = 10.0 * wk * math.sin(2 * math.pi * (t - 38.0) / 2.5)
        if wk > 1e-4:
            q = qslerp(q, hang(side, fr, out=IDLE_ARM_OUT + 14.0 + flick, fwd=12.0 + flick), wk)
        c.key(side, t, q, ease='linear')
    c.key('head', t, q_head, ease='linear')
c.mode('head', 'add')
for side in ('right_leg', 'left_leg'):
    check(c, f'{side} foot on the ground (t=12)', (leg_end(c, 12, side)[1],), (24.0,), tol=0.3)
check(c, 'right hand in the water (t=12)', (ta.fk(c, 12, 'right_arm', GRIP['right_arm'])[1],), (11.0,), tol=0.8)

# ================================================================== goods changing hands

GOODS = 8.0                                         # what is carried, drawn about this big (a block item at half size)
GOODS_ON_GROUND = np.array([0.0, 24.0 - 4.5, -9.5])  # set down a little ahead (the middle of a 9 px thing on the ground)
CARRY_AT = np.array([-6.3, 7.6, -8.2])              # carry's right hand on the side of what is carried (the left mirrored)
HANDOFF = np.array([-6.0, 3.0, -14.0])              # the giver's right hand on the near half of goods held out over the
                                                    # counter's middle (the taker, 2 blocks off, takes the far half)


def mirror_x(p):
    return np.asarray(p, float) * np.array([-1.0, 1.0, 1.0])


def flat_forward(mats):
    f = mats['body'][:3, :3] @ np.array([0.0, 0.0, -1.0])
    f[1] = 0.0
    return f / max(1e-9, float(np.linalg.norm(f)))


def between_hands(mats, ahead=0.0):
    """The middle of what both hands hold by its sides, [ahead] px further forward (goods held by their near half)."""
    a, b = grips(mats)
    return (a + b) / 2 + flat_forward(mats) * ahead


def carry_keys():
    """carry's first frame: the arms and torso pick_up ends on and put_down starts from."""
    cc = getc('carry')
    return {b: cc.sample(b, 0.0) for b in ('right_arm', 'left_arm', 'torso', 'head')}


def goods_clip(name, keys, events, goods, look):
    """pick_up and put_down: the hands down to the ground and up to carry's hold (or back), the hips down and back over
    planted feet; [keys]: pitch, drop, back, head (add), the right hand's path (the left mirrors it) with the weight of
    it against hanging, and how far the clip has gone into carry's own first frame."""
    CK = carry_keys()
    c = clip(name, 20, False, look=look, events=events, views=((32, -12), (90, -2), (150, -10)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    set_goods(name, goods)
    arms = Arms()
    for t in every(20):
        feet = {'right_leg': (-1.9, 0.0), 'left_leg': (1.9, 0.0)}
        pr = np.array([0.0, K(keys['drop'], t), K(keys['back'], t)])
        fr = F_(qt=bend(K(keys['pitch'], t)), pr=pr)
        wc = smooth(K(keys['carry'], t))
        if wc > 1e-6:
            fr['qt'] = qslerp(fr['qt'], CK['torso'][0], wc)
        key_frame(c, t, fr, ease='linear')
        if t >= 20 - 1e-9 or t <= 1e-9:
            for side in ('right_leg', 'left_leg'):
                c.key(side, t, I, ease='linear')
        else:
            plant(c, t, fr, right=feet['right_leg'], left=feet['left_leg'])
        w = smooth(K(keys['reach'], t))
        for side, palm in (('right_arm', L), ('left_arm', R)):
            p = KV(keys['hand'], t)
            q = arms.reach(side, fr, p if side == 'right_arm' else mirror_x(p), palm=palm, palm_w=0.4)
            q = qslerp(hang(side, fr), q, w)
            if wc > 1e-6:
                q = qslerp(q, CK[side][0], wc)
            c.key(side, t, q, ease='linear')
        hq = qeuler(x=K(keys['head'], t))
        c.key('head', t, qslerp(hq, CK['head'][0], wc) if wc > 1e-6 else hq, ease='linear')
    c.mode('head', 'add')
    return c


PU_KEYS = {
    'pitch': [(0, 0.0), (1.5, -2.0), (5.0, 38.0), (8.0, 60.0), (9.5, 60.0), (12.5, 40.0), (15.5, 10.0), (17.5, -4.0), (20, -3.0)],
    'drop': [(0, 0.0), (2.0, 0.0), (6.5, 2.6), (10.0, 2.6), (14.0, 0.5), (17.0, 0.0), (20, 0.0)],
    'back': [(0, 0.0), (2.0, 0.0), (6.5, 1.5), (10.0, 1.5), (15.0, 0.2), (20, 0.0)],
    'head': [(0, 0.0), (2.0, 8.0), (6.0, 14.0), (9.0, 10.0), (13.0, 4.0), (17.0, -4.0), (20, -3.0)],
    'reach': [(0, 0.0), (1.5, 0.0), (4.5, 1.0), (20, 1.0)],
    'carry': [(0, 0.0), (17.0, 0.0), (20, 1.0)],
    'hand': [(0, (-7.0, 12.0, -6.0)), (2.0, (-7.0, 12.0, -6.0)), (6.0, (-7.4, 17.5, -9.5)), (8.5, (-6.3, 19.5, -9.5)),
             (10.0, (-6.3, 19.2, -9.5)), (13.0, (-6.3, 13.5, -10.4)), (16.0, (-6.3, 9.0, -9.4)), (18.5, CARRY_AT + (0, 0.1, -0.1)),
             (20, CARRY_AT)],
}
PU_GRAB = 8.5
c = goods_clip('pick_up', PU_KEYS, [('grab', PU_GRAB)],
               [(0, PU_GRAB, ('ground', GOODS_ON_GROUND), GOODS), (PU_GRAB, None, ('hands', 0.0), GOODS)], ('citizen', 3))
join('pick_up ends on carry', getc('pick_up'), 20, getc('carry'), 0, tol=0.3)
join('pick_up starts on idle', getc('pick_up'), 0, getc('idle'), 0, tol=1.0)
check(c, 'hands on the goods on the ground (t=8.5)', ta.fk(c, PU_GRAB, 'right_arm', GRIP['right_arm']),
      GOODS_ON_GROUND + np.array([-6.3, 0.0, 0.0]), tol=0.8)

PD_KEYS = {
    'pitch': [(0, -3.0), (2.0, 4.0), (6.0, 36.0), (9.5, 58.0), (11.5, 60.0), (13.5, 48.0), (17.0, 8.0), (18.5, -1.0), (20, 0.0)],
    'drop': [(0, 0.0), (3.0, 0.0), (8.5, 2.6), (12.0, 2.6), (16.0, 0.5), (18.5, 0.0), (20, 0.0)],
    'back': [(0, 0.0), (3.0, 0.0), (8.5, 1.5), (12.0, 1.5), (16.5, 0.2), (20, 0.0)],
    'head': [(0, -3.0), (3.0, 6.0), (8.0, 12.0), (12.0, 10.0), (16.0, 2.0), (20, 0.0)],
    'reach': [(0, 1.0), (13.0, 1.0), (18.5, 0.0), (20, 0.0)],
    'carry': [(0, 1.0), (3.0, 0.0), (20, 0.0)],
    'hand': [(0, CARRY_AT), (2.5, (-6.3, 8.5, -9.0)), (6.0, (-6.3, 13.5, -10.4)), (9.5, (-6.3, 19.2, -9.5)),
             (11.0, (-6.3, 19.5, -9.5)), (12.5, (-7.4, 18.5, -9.4)), (15.5, (-7.0, 13.0, -6.5)), (20, (-7.0, 12.0, -6.0))],
}
PD_DOWN = 11.0
c = goods_clip('put_down', PD_KEYS, [('set_down', PD_DOWN)],
               [(0, PD_DOWN, ('hands', 0.0), GOODS), (PD_DOWN, None, ('ground', GOODS_ON_GROUND), GOODS)], ('citizen', 6))
join('put_down starts on carry', getc('put_down'), 0, getc('carry'), 0, tol=0.3)
join('put_down ends on idle', getc('put_down'), 20, getc('idle'), 0, tol=1.0)
check(c, 'hands on the goods on the ground (t=11)', ta.fk(c, PD_DOWN, 'right_arm', GRIP['right_arm']),
      GOODS_ON_GROUND + np.array([-6.3, 0.0, 0.0]), tol=0.8)


def counter_clip(name, look, events, goods=None, tools=(), tools_at=None, partner=None):
    """hand_over, receive and pay: across a counter from someone 2 blocks off (the partner in the previews)."""
    c = clip(name, 30, False, look=look, tools_=tools, station_='counter', events=events, partner=partner,
             views=((40, -14), (90, -2), (200, -14)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    if goods:
        set_goods(name, goods)
    if tools_at:
        ta.INFO[name]['tools_at'] = tools_at
    return c


def both_hands_on(c, t, fr, arms, path, w, palms=(L, R)):
    """Both hands at the sides of something held by them: the right on [path] (the left mirrored), weighed [w] against
    hanging."""
    for side, palm in (('right_arm', palms[0]), ('left_arm', palms[1])):
        p = path if side == 'right_arm' else mirror_x(path)
        q = arms.reach(side, fr, p, palm=palm, palm_w=0.4)
        c.key(side, t, qslerp(hang(side, fr), q, w), ease='linear')


# ---- hand_over (30): goods lifted from under the counter and held out over it with both hands, leaning in; held while
# the other takes them (tick 16), the hands sliding off and open, and back with a nod
HO_P = [(0, 0.0), (3.0, -1.0), (9.0, 24.0), (16.0, 26.0), (19.0, 18.0), (24.0, 2.0), (27.0, -1.0), (30, 0.0)]
HO_HAND = [(0, (-6.0, 11.0, -5.5)), (5.0, (-6.0, 5.5, -8.0)), (12.0, HANDOFF + (0, 0.4, 0.2)), (16.0, HANDOFF),
           (19.0, (-7.6, 3.4, -12.6)), (23.0, (-7.0, 9.0, -7.0)), (30, (-7.0, 10.0, -5.0))]
HO_W = [(0, 1.0), (19.0, 1.0), (24.0, 0.0), (30, 0.0)]
HO_HEAD = [(0, 6.0), (5.0, 4.0), (12.0, 6.0), (16.0, 4.0), (20.0, -2.0), (24.0, 8.0), (27.0, 0.0), (30, 0.0)]
HANDOVER_T = 16.0
c = counter_clip('hand_over', ('merchant', 3), [('handoff', HANDOVER_T)], partner='receive',
                 goods=[(0, HANDOVER_T, ('hands', 2.0), GOODS)])
arms = Arms()
for t in every(30):
    fr = F_(qt=bend(K(HO_P, t)))
    key_frame(c, t, fr, root=False, ease='linear')
    both_hands_on(c, t, fr, arms, KV(HO_HAND, t), smooth(K(HO_W, t)), palms=(L, R) if t < 18 else (U, U))
    c.key('head', t, qeuler(x=K(HO_HEAD, t)), ease='linear')
c.mode('head', 'add')

# ---- receive (30): the hands come up and out over the counter, take the goods held out (tick 16) by their far half,
# draw them in to the chest and a look at them
RC_P = [(0, 0.0), (4.0, 2.0), (10.0, 22.0), (16.0, 24.0), (20.0, 10.0), (24.0, 2.0), (30, 1.0)]
RC_HAND = [(0, (-7.0, 10.0, -5.0)), (5.0, (-7.6, 5.0, -9.0)), (11.0, (-7.4, 3.2, -13.4)), (14.5, HANDOFF + (-0.6, 0.0, 0.0)),
           (16.0, HANDOFF), (20.0, (-6.0, 5.5, -10.0)), (24.0, (-6.0, 6.5, -8.5)), (30, (-6.0, 6.8, -8.3))]
RC_W = [(0, 0.0), (5.0, 1.0), (30, 1.0)]
RC_HEAD = [(0, 0.0), (5.0, 4.0), (12.0, 6.0), (16.0, 8.0), (21.0, 12.0), (25.0, 14.0), (28.0, 6.0), (30, 4.0)]
c = counter_clip('receive', ('citizen', 1), [('handoff', HANDOVER_T)], partner='hand_over',
                 goods=[(HANDOVER_T, None, ('hands', 2.0), GOODS)])
arms = Arms()
for t in every(30):
    fr = F_(qt=bend(K(RC_P, t), 0.0, 0.0))
    key_frame(c, t, fr, root=False, ease='linear')
    both_hands_on(c, t, fr, arms, KV(RC_HAND, t), smooth(K(RC_W, t)), palms=(U, U) if t < 13 else (L, R))
    c.key('head', t, qeuler(x=K(RC_HEAD, t), y=8.0 * bump(t, 21.0, 29.0)), ease='linear')
c.mode('head', 'add')


def goods_at(name, t, ahead=2.0, place=None):
    mats = part_matrices(ta.look_for(ta.INFO[name]['look']), ta.pose_at(getc(name), t, ta.walk_base(t)))
    p = between_hands(mats, ahead)
    return p if place is None else apply(place, p)


check(getc('hand_over'), 'the goods where the taker takes them (t=16, against receive)', goods_at('hand_over', HANDOVER_T),
      goods_at('receive', HANDOVER_T, place=ta.partner_place()), tol=0.8)

# ---- pay (30): the purse up in the left hand, coins taken from it, and counted one by one into the other's palm over
# the counter (ticks 11, 14, 17), the purse lowered
PURSE_MOUTH = (0.0, -2.5, -2.75)
COIN = (0.0, 0.5, -3.0)
PAY_DROPS = (11.0, 14.0, 17.0)
PAY_P = [(0, 0.0), (4.0, 4.0), (8.0, 8.0), (11.0, 20.0), (17.0, 22.0), (21.0, 8.0), (26.0, 0.0), (30, 0.0)]
PAY_PURSE = [(0, (3.0, 9.0, -5.5)), (4.0, (2.6, 7.0, -7.5)), (17.0, (2.6, 7.2, -7.3)), (24.0, (3.6, 10.0, -4.5)), (30, (3.6, 10.5, -4.0))]
PAY_W = [(0, 0.0), (1.0, 0.0), (4.0, 1.0), (30, 1.0)]
PAY_HAND = [(0, (-7.0, 11.0, -5.0)), (4.0, (1.2, 4.6, -7.6)), (6.0, (1.4, 5.4, -7.4)), (7.5, (0.4, 4.0, -8.5)),
            (10.5, (-1.0, 1.4, -13.3)), (11.0, (-1.0, 1.9, -13.4)), (13.5, (-0.5, 1.3, -13.4)), (14.0, (-0.5, 1.9, -13.5)),
            (16.5, (0.0, 1.3, -13.5)), (17.0, (0.0, 1.9, -13.6)), (20.0, (-4.0, 6.0, -10.0)), (24.0, (-7.0, 10.5, -5.5)),
            (30, (-7.0, 11.0, -5.0))]
PAY_HW = [(0, 0.0), (1.0, 0.0), (4.0, 1.0), (19.0, 1.0), (25.0, 0.0), (30, 0.0)]
PAY_HEAD = [(0, 10.0), (4.0, 16.0), (7.0, 14.0), (11.0, 6.0), (17.0, 6.0), (21.0, 2.0), (25.0, 8.0), (28.0, 0.0), (30, 0.0)]
c = counter_clip('pay', ('noble', 0), [('clink', k) for k in PAY_DROPS], tools=('coin_purse', 'coin'),
                 tools_at=lambda t: {'coin_purse'} | ({'coin'} if 7.0 <= t < PAY_DROPS[-1] else set()), partner='receive')
arms, purses = Arms(), Arms()
for t in every(30):
    fr = F_(qt=bend(K(PAY_P, t)))
    key_frame(c, t, fr, root=False, ease='linear')
    q_purse = purses.solve('left_arm', fr, [(tool_point('coin_purse', PURSE_MOUTH), KV(PAY_PURSE, t), 3.0)],
                           [(tool_dir('coin_purse', (0, -1, 0)), U, 1.0)])
    c.key('left_arm', t, qslerp(hang('left_arm', fr), q_purse, smooth(K(PAY_W, t))), ease='linear')
    q_hand = arms.reach('right_arm', fr, KV(PAY_HAND, t), palm=D, palm_w=0.3)
    c.key('right_arm', t, qslerp(hang('right_arm', fr), q_hand, smooth(K(PAY_HW, t))), ease='linear')
    c.key('head', t, qeuler(x=K(PAY_HEAD, t)), ease='linear')
c.mode('head', 'add')
# Paying with goods (when they carry something), it is counted out of the fingers in the coins' place
set_goods('pay', [(7.0, PAY_DROPS[-1], ('part', 'right_arm', GRIP['right_arm'] + np.array(COIN)), 3.0)])
# carry (townsfolk_anims.py's clip): goods carried between the hands in the crate's place. carry_sack draws none: the
# sack on the shoulder is what's carried (grain, flour, wool), and goods beside it would show the load twice
set_goods('carry', [(0, None, ('hands', 0.0), GOODS)])

# ================================================================== the market

APPLE_MID = (0.0, -1.3, -3.0)           # apple part space: its middle


def counter_edge(side, x_in=5.0):
    """A hand resting on the near edge of the counter a block ahead (its top at 8, its face at -8)."""
    s = -1.0 if side == 'right_arm' else 1.0
    return np.array([s * x_in, 6.2, -9.6])


# ---- browse (60, loop): a shopper at a stall, leaning on the counter's edge, looking the goods over; an apple picked
# up (tick 14), brought up to look at, turned over in the fingers, the other hand helping, put back (tick 36); a lean
# to the other end of the stall and a feel of the goods there with the left hand, and back
BR_P = [(0, 14.0), (8.0, 16.0), (14.0, 20.0), (20.0, 8.0), (30.0, 9.0), (36.0, 20.0), (42.0, 15.0), (48.0, 19.0), (54.0, 14.0), (60, 14.0)]
BR_YAW = [(0, 0.0), (8.0, 6.0), (14.0, 8.0), (22.0, 4.0), (30.0, 4.0), (36.0, 8.0), (44.0, -10.0), (50.0, -12.0), (56.0, -3.0), (60, 0.0)]
BR_ROLL = [(0, 0.0), (36.0, 0.0), (44.0, -2.0), (50.0, -2.5), (56.0, 0.0), (60, 0.0)]
BR_HY = [(0, -14.0), (5.0, 10.0), (10.0, 14.0), (16.0, 6.0), (24.0, 2.0), (30.0, 4.0), (36.0, 10.0), (42.0, -18.0), (48.0, -22.0), (54.0, -10.0), (60, -14.0)]
BR_HP = [(0, 18.0), (6.0, 22.0), (12.0, 26.0), (18.0, 10.0), (26.0, 12.0), (32.0, 20.0), (38.0, 24.0), (46.0, 22.0), (54.0, 18.0), (60, 18.0)]
BR_HR = [(0, 0.0), (18.0, 0.0), (21.0, 8.0), (27.0, -6.0), (31.0, 0.0), (60, 0.0)]
APPLES = np.array([-4.0, 5.6, -12.0])           # the apples on the counter
TOUCH = np.array([3.0, 5.6, -11.5])             # the goods at the other end
BR_RIGHT = [(0, counter_edge('right_arm')), (6.0, counter_edge('right_arm') + (0.0, -1.5, -1.0)), (11.0, APPLES + (0.0, -1.5, 0.0)),
            (14.0, APPLES), (17.0, APPLES + (1.0, -3.5, 2.0)), (21.0, (-2.6, 1.0, -8.6)), (30.0, (-2.4, 1.2, -8.8)),
            (33.0, APPLES + (0.6, -2.5, 1.2)), (36.0, APPLES), (39.0, APPLES + (-0.5, -1.5, 1.5)),
            (43.0, counter_edge('right_arm') + (-0.5, -0.5, 0.0)), (60, counter_edge('right_arm'))]
BR_TWIST = [(0, 0.0), (21.0, 0.0), (24.0, 40.0), (27.0, -30.0), (30.0, 0.0), (60, 0.0)]
BR_LEFT = [(0, counter_edge('left_arm')), (20.0, counter_edge('left_arm')), (23.0, (0.2, 1.6, -9.6)), (29.0, (0.0, 1.8, -9.8)),
           (32.0, counter_edge('left_arm') + (0.0, -1.0, -0.5)), (36.0, counter_edge('left_arm')), (42.0, counter_edge('left_arm')),
           (46.0, TOUCH + (0.0, -2.0, 0.0)), (48.0, TOUCH), (50.0, TOUCH + (-0.6, 0.0, 0.3)), (52.0, TOUCH + (0.0, -1.5, 0.5)),
           (56.0, counter_edge('left_arm')), (60, counter_edge('left_arm'))]
c = clip('browse', 60, True, look=('noble', 2), tools_=('apple',), station_='counter',
         events=[('pick', 14), ('look', 25), ('put back', 36), ('feel', 48)], views=((40, -14), (90, -2), (-30, -16)),
         traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
ta.INFO['browse']['tools_at'] = lambda t: {'apple'} if 14.0 <= t < 36.0 else set()
rights, lefts = Arms(), Arms()
for t in every(60):
    fr = F_(qt=bend(K(BR_P, t, 60), K(BR_YAW, t, 60), K(BR_ROLL, t, 60)))
    key_frame(c, t, fr, root=False, ease='linear')
    q = rights.reach('right_arm', fr, KV(BR_RIGHT, t, 60), palm=D, palm_w=0.25)
    tw = K(BR_TWIST, t)
    if abs(tw) > 1e-6:
        q = qmul(q, qaxis((0, 1, 0), tw))
    c.key('right_arm', t, q, ease='linear')
    c.key('left_arm', t, lefts.reach('left_arm', fr, KV(BR_LEFT, t, 60), palm=D, palm_w=0.25), ease='linear')
    c.key('head', t, qeuler(x=K(BR_HP, t, 60), y=K(BR_HY, t, 60), z=K(BR_HR, t, 60)), ease='linear')
c.mode('head', 'set')
seal(c)
check(c, 'hand at the apples (t=14)', ta.fk(c, 14, 'right_arm', GRIP['right_arm']), APPLES, tol=0.8)
check(c, 'hand at the apples (t=36)', ta.fk(c, 36, 'right_arm', GRIP['right_arm']), APPLES, tol=0.8)


# ---- set_up (60, loop): a merchant setting out the stall: a turn to the barrel at the left, goods taken out of it in
# the left hand (tick 9), turned back and set on the counter (tick 20), pushed into line by the right; then both hands
# up to the awning's edge, two tugs to straighten it (ticks 38, 42), a look over the stall, and back to the counter
STALL_BARREL = np.array([14.5, 12.0, -1.0])     # down in the barrel at the merchant's left (its top at 8.5)
STALL_SET = np.array([2.0, 5.0, -12.0])         # where the goods are set on the counter (their middle)
AWNING = np.array([0.0, -9.0, -7.0])            # the awning's front edge overhead
SU2_P = [(0, 12.0), (5.0, 20.0), (9.0, 30.0), (14.0, 12.0), (20.0, 22.0), (27.0, 18.0), (32.0, 2.0), (36.0, -8.0), (44.0, -8.0), (48.0, -2.0), (54.0, 4.0), (60, 12.0)]
SU2_YAW = [(0, 0.0), (5.0, -30.0), (9.0, -40.0), (15.0, -8.0), (20.0, 0.0), (44.0, 0.0), (48.0, 8.0), (52.0, -8.0), (56.0, 0.0), (60, 0.0)]
SU2_ROLL = [(0, 0.0), (5.0, 5.0), (9.0, 9.0), (15.0, 0.0), (60, 0.0)]
SU2_LEFT = [(0, counter_edge('left_arm', 4.0)), (4.0, (7.5, 5.0, -4.0)), (7.5, STALL_BARREL + (0.0, -1.2, 0.0)), (9.0, STALL_BARREL),
            (11.0, STALL_BARREL + (-1.0, -3.5, -1.0)), (16.0, STALL_SET + (0.5, -2.5, 1.5)), (20.0, STALL_SET + (0.0, -0.5, 0.0)),
            (23.0, STALL_SET + (1.5, -1.5, 2.5)), (27.0, counter_edge('left_arm', 4.0)), (31.0, (6.0, 2.0, -8.0)),
            (35.0, AWNING + (3.0, 0.5, 0.0)), (38.0, AWNING + (3.0, 1.5, 0.0)), (40.0, AWNING + (3.0, 0.4, 0.0)),
            (42.0, AWNING + (3.0, 1.6, 0.0)), (44.0, AWNING + (3.0, 0.6, 0.0)), (48.0, (6.5, 6.0, -5.0)), (52.0, (7.0, 11.0, -3.5)),
            (56.0, (6.5, 9.0, -6.0)), (60, counter_edge('left_arm', 4.0))]
SU2_RIGHT = [(0, counter_edge('right_arm', 4.0)), (8.0, counter_edge('right_arm', 4.0) + (0.0, -0.5, 0.5)), (16.0, counter_edge('right_arm', 4.0)),
             (21.0, STALL_SET + (-5.5, -0.5, 0.0)), (23.5, STALL_SET + (-4.6, 0.6, 0.0)), (25.5, STALL_SET + (-3.6, 0.6, 0.0)),
             (27.5, STALL_SET + (-5.0, -1.0, 0.5)), (31.0, (-6.0, 2.0, -8.0)), (35.0, AWNING + (-3.0, 0.5, 0.0)), (38.0, AWNING + (-3.0, 1.5, 0.0)),
             (40.0, AWNING + (-3.0, 0.4, 0.0)), (42.0, AWNING + (-3.0, 1.6, 0.0)), (44.0, AWNING + (-3.0, 0.6, 0.0)), (48.0, (-6.5, 6.0, -5.0)),
             (52.0, (-7.0, 11.0, -3.5)), (56.0, (-6.5, 9.0, -6.0)), (60, counter_edge('right_arm', 4.0))]
SU2_PALM = [(0, D), (30.0, D), (34.0, F), (44.0, F), (49.0, D), (60, D)]
SU2_HY = [(0, 0.0), (5.0, -30.0), (9.0, -14.0), (15.0, 4.0), (20.0, 6.0), (26.0, 2.0), (32.0, -30.0), (36.0, -40.0), (44.0, -36.0), (48.0, -6.0), (52.0, 14.0), (56.0, 0.0), (60, 0.0)]
SU2_HX = [(0, 0.0), (5.0, -20.0), (9.0, -12.0), (15.0, 4.0), (20.0, 8.0), (26.0, 4.0), (32.0, 0.0), (48.0, 0.0), (52.0, 4.0), (56.0, 0.0), (60, 0.0)]
SU2_TAKE, SU2_PLACE = 9.0, 20.0
c = clip('set_up', 60, True, look=('merchant', 1), station_='stall',
         events=[('take', SU2_TAKE), ('place', SU2_PLACE), ('tug', 38), ('tug', 42)], views=((40, -14, True), (90, -2), (200, -16)),
         traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
set_goods('set_up', [(SU2_TAKE, SU2_PLACE, ('part', 'left_arm', GRIP['left_arm'] + np.array([0.0, 1.0, -2.5])), 5.0),
                      (SU2_PLACE, 31.0, ('ground', STALL_SET), 5.0)])
rights, lefts = Arms(), Arms()
for t in every(60):
    fr = F_(qt=bend(K(SU2_P, t, 60), K(SU2_YAW, t, 60), K(SU2_ROLL, t, 60)))
    key_frame(c, t, fr, root=False, ease='linear')
    palm = unit(KV(SU2_PALM, t))
    c.key('right_arm', t, rights.reach('right_arm', fr, KV(SU2_RIGHT, t, 60), palm=palm, palm_w=0.3), ease='linear')
    c.key('left_arm', t, lefts.reach('left_arm', fr, KV(SU2_LEFT, t, 60), palm=mirror_x(palm), palm_w=0.3), ease='linear')
    c.key('head', t, qeuler(x=K(SU2_HX, t, 60), y=K(SU2_HY, t, 60)), ease='linear')
c.mode('head', 'add')
seal(c)
check(c, 'left hand in the barrel (t=9)', ta.fk(c, SU2_TAKE, 'left_arm', GRIP['left_arm']), STALL_BARREL, tol=0.8)

# ================================================================== the fields: sowing, mowing, gathering, the basket on the hip

def leg_to_r(c, t, side, fr, foot, toe=F, lift=0.0):
    """leg_to under a root that turns as well as moves: the leg's key keeps its foot on world [foot] (raised [lift] px), the
    toes to world [toe], a foot nearer than the leg's 12 px lifting the leg into the body (a bent knee)."""
    qr = fr['qr']
    hip = HIPS + qmat(qr) @ (np.array(PIVOTS[side], float) - HIPS) + fr['pr']
    d = np.asarray(foot, float) - np.array([0.0, lift, 0.0]) - hip
    raise_ = 0.0
    if float(np.linalg.norm(d)) < 12.0:
        horiz = math.hypot(d[0], d[2])
        new_dy = math.sqrt(max(0.0, 144.0 - horiz * horiz))
        raise_ = new_dy - d[1]
        d = np.array([d[0], new_dy, d[2]])
    c.key(side, t, qmul(qinv(qr), leg_q(d, toe)), pos=(0.0, -raise_, 0.0) if raise_ > 1e-6 else None, ease='linear')
    return raise_


def wrap(t, length):
    """[t] folded into (-length/2, length/2]: how far a loop's tick is from its start, either way round."""
    t = (t + length / 2.0) % length - length / 2.0
    return t


# ---- sow (40, loop, overlay): broadcasting seed down the furrow while walking: the right hand dips into the bag at the
# left hip, comes up across the chest and sweeps out to the right, the seed flung from the opening hand in a fan ahead
# (ticks 11 and 31, the first cast a little higher and wider than the second); the left hand holds the bag's mouth open.
# Only the arms and the head: the legs walk.
SEED_BAG_IN = np.array([2.2, 8.3, -4.4])        # torso space: down in the bag's mouth (living_props: seed_bag)
SOW_LEFT = np.array([3.4, 9.6, -5.6])           # torso space: the left hand on the bag's front
SOW_CAST = 20.0                                 # a cast every 20 ticks
SOW_SEED = (11.0, 31.0)
_fr0 = F_()
_, _sow_joint = chain_frame('right_arm', **_fr0)
SOW_DIP = unit(SEED_BAG_IN - _sow_joint)
SOW_DIRS = [(0.0, SOW_DIP), (3.0, v(f=0.6, u=-0.5, r=-0.5)), (6.0, v(f=0.85, u=0.05, r=-0.45)), (9.0, v(f=1.0, u=0.2, r=0.25)),
            (11.0, v(f=0.55, u=0.22, r=0.85)), (13.5, v(f=0.1, u=0.0, r=1.0)), (16.5, v(f=0.35, u=-0.85, r=0.3)),
            (18.5, v(f=0.55, u=-0.75, r=-0.2)), (20.0, SOW_DIP)]
SOW_PALMS = [(0.0, D), (3.0, B), (6.0, B), (9.0, F), (11.0, unit(F + D)), (13.5, D), (16.5, B), (18.5, D), (20.0, D)]
SOW_HX = [(0.0, 14.0), (6.0, 8.0), (11.0, 6.0), (15.0, 10.0), (20.0, 14.0)]
SOW_HY = [(0.0, -6.0), (6.0, -4.0), (11.0, 10.0), (14.0, 12.0), (18.0, 2.0), (20.0, -6.0)]
c = clip('sow', 40, True, look=('farmer', 0), tools_=('seed_bag',), overlay=True, events=[('seed', k) for k in SOW_SEED],
         traces=[('right_arm', GRIP['right_arm'], (255, 220, 140))], views=((32, -12), (90, -2), (160, -14)))
q_dip = reach('right_arm', _fr0, SEED_BAG_IN, palm=D, palm_w=0.3)
left_arm = Arms()
for t in every(40):
    u = t % SOW_CAST
    wide = math.sin(2 * math.pi * t / 40.0)          # +1 at the first cast, -1 at the second, 0 at the dips
    d = unit(np.asarray(spline_keys(SOW_DIRS, u, period=SOW_CAST), float) + 0.16 * wide * v(f=0.0, u=0.6, r=0.8) * bump(u, 6.0, 16.0))
    palm = unit(np.asarray(spline_keys(SOW_PALMS, u, period=SOW_CAST), float))
    q = free(_fr0, d, palm)
    w_dip = smooth(1.0 - abs(wrap(u, SOW_CAST)) / 2.5)
    c.key('right_arm', t, qslerp(q, q_dip, w_dip), ease='linear')
    tug = smooth(1.0 - abs(wrap(u, SOW_CAST)) / 3.0)
    c.key('left_arm', t, left_arm.reach('left_arm', _fr0, SOW_LEFT + np.array([0.0, -0.3, -0.7]) * tug, palm=B, palm_w=0.3),
          ease='linear')
    c.key('head', t, qeuler(x=K(SOW_HX, u, SOW_CAST), y=K(SOW_HY, u, SOW_CAST) + 3.0 * wide * bump(u, 6.0, 18.0)), ease='linear')
c.mode('head', 'add')
seal(c)
for k in (0.0, 20.0):
    check(c, f'hand in the seed bag (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']),
          torso_to_world(_fr0, SEED_BAG_IN), tol=0.9)

# ---- carry_basket (40, loop, overlay): the basket on the left hip, the left arm round it and the hand on its front rim,
# riding the stride; the right arm swings with the walk (added to, a little out for balance); a look about. What they
# carry sits in the basket (the game draws it there: the cloth over a load of bread shows when they carry nothing).
BASKET_TOP = np.array([5.6, 7.4, -4.0])         # torso space: the basket's mouth (living_props: basket_hip)
BASKET_RIM = np.array([8.4, 7.6, -6.4])         # torso space: the front rim's outer corner, where the left hand holds it
Q_BASKET = reach('left_arm', _fr0, BASKET_RIM, palm=R, palm_w=0.3)
c = clip('carry_basket', 40, True, look=('farmer', 2), tools_=('basket_hip',), overlay=True,
         views=((32, -12), (90, -2), (-60, -10)))
set_goods('carry_basket', [(0, None, ('part', 'body', BASKET_TOP + np.array([0.0, 0.4, 0.0])), 3.5)])
for t in every(40, 2.0):
    ph = 2 * math.pi * t / 40.0
    c.key('left_arm', t, Q_BASKET, ease='linear')
    c.key('right_arm', t, qeuler(x=-2.0 * math.sin(ph), z=4.0 + 1.0 * math.sin(2 * ph)), ease='linear')
    c.key('head', t, qeuler(x=1.5 * math.sin(2 * ph), y=9.0 * math.sin(ph), z=-1.0 * math.sin(ph)), ease='linear')
c.mode('right_arm', 'add')
c.mode('head', 'add')
seal(c)
check(c, 'left hand on the basket rim (t=0)', ta.fk(c, 0, 'left_arm', GRIP['left_arm']), BASKET_RIM, tol=1.2)

# ---- gather (40): down on the right knee at the crop ahead, the right hand to a plant's leaves, a twist and a pull (tick
# 13): up it comes in a spray of earth, held up to look at, and tossed into the basket on the hip (let go at tick 21, in at
# 24); back up onto the feet. The left hand keeps the basket on the hip all the while. What comes up is what they carry
# (the game draws it: a carrot when they carry nothing).
GATHER_CROP = np.array([-1.7, 19.3, -10.6])     # the plant's leaves, where the fist takes hold
G_PULL, G_TOSS, G_IN = 13.0, 21.0, 24.0
# (t, drop, pitch, yaw, hips back, right hand (None: hanging; 'crop': on the leaves), right foot back) keys
G_DROP = [(0, 0.0), (3.0, 0.5), (8.0, 5.0), (13.0, 4.9), (20.0, 4.6), (27.0, 4.2), (33.0, 1.0), (37.0, 0.0), (40, 0.0)]
G_PITCH = [(0, 0.0), (3.0, 5.0), (9.0, 41.0), (12.0, 43.0), (14.5, 32.0), (18.0, 17.0), (21.0, 13.0), (26.0, 15.0), (32.0, 6.0), (36.0, -1.5), (40, 0.0)]
G_YAW = [(0, 0.0), (9.0, 6.0), (13.0, 8.0), (18.0, 0.0), (21.0, -14.0), (25.0, -6.0), (32.0, 0.0), (40, 0.0)]
G_BACK = [(0, 0.0), (8.0, 1.5), (26.0, 1.5), (34.0, 0.3), (40, 0.0)]
G_KNEE = [(0, 0.0), (2.0, 0.0), (8.0, 1.0), (27.0, 1.0), (34.5, 0.0), (40, 0.0)]      # how far back the right foot has gone
G_HAND = [(0, (-6.2, 12.0, 0.2)), (5.0, (-5.0, 15.5, -6.0)), (9.0, GATHER_CROP + (0.0, -0.8, 0.3)), (11.0, GATHER_CROP),
          (13.0, GATHER_CROP), (15.0, GATHER_CROP + (0.5, -6.0, 1.0)), (18.0, (-1.0, 6.5, -9.0)), (G_TOSS, None),
          (25.0, (-4.0, 11.0, -6.5)), (31.0, (-5.6, 12.8, -2.5)), (40, (-6.2, 12.0, 0.2))]
G_TOSS_AT = np.array([2.4, 4.6, -7.0])         # torso space: where the hand lets go, as near the basket as it reaches
AHEAD = np.array([0.0, 6.0, -48.0])             # a look ahead, level


def gather_frame(t):
    d = K(G_DROP, t)
    p = K(G_PITCH, t)
    return F_(qt=bend(p, K(G_YAW, t), 0.0), vt=(0.0, sink_for(p), 0.0), pr=(0.0, d, K(G_BACK, t)))


def gather_feet(t, fr):
    """The left foot stays put; the right steps back as the hips go down, onto the knee (a straight leg to the ground
    behind), and forward again as they come up."""
    k = K(G_KNEE, t)
    hip_r = hip_of('right_leg', fr['pr'])
    dy = 24.0 - hip_r[1]
    back = math.sqrt(max(0.0, 144.0 - dy * dy - 0.25))
    z_r = (1.0 - k) * 0.0 + k * (hip_r[2] + back)
    lift = 2.0 * bump(t, 2.0, 7.5) + 1.6 * bump(t, 30.0, 35.0)
    return np.array([-2.4, 24.0, z_r]), np.array([1.9, 24.0, 0.0]), lift


c = clip('gather', 40, False, look=('farmer', 1), tools_=('basket_hip',), station_='crop_row',
         events=[('pull', G_PULL), ('toss', G_TOSS), ('in_basket', G_IN)],
         traces=[('right_arm', GRIP['right_arm'], (160, 230, 120))], views=((32, -12, True), (90, -2), (-50, -12)))
rights = Holds()
for t in every(40):
    fr = gather_frame(t)
    key_frame(c, t, fr, ease='linear')
    rf, lf, lift = gather_feet(t, fr)
    leg_to(c, t, 'right_leg', fr['pr'], rf - np.array([0.0, lift, 0.0]), toe=F)
    leg_to(c, t, 'left_leg', fr['pr'], lf, toe=F)
    c.key('left_arm', t, Q_BASKET, ease='linear')
    # the right hand: the keyed points are world places (the crop), and for the toss as near the basket as it goes
    keys = [(k, (np.asarray(p, float) if p is not None else torso_to_world(gather_frame(k), G_TOSS_AT))) for k, p in G_HAND]
    target = KV(keys, t)
    if t <= 0.0 or t >= 40.0:
        q = hang('right_arm', fr)
        rights.prev['right_arm'] = q
        c.key('right_arm', t, q, ease='linear')
    else:
        q, off = rights.slack('right_arm', fr, [(GRIP['right_arm'], target, 1.0)], [((0, 0, -1), D, 0.2)], slack=1.0, limit=1.4)
        w = smooth(min(t / 3.0, (40.0 - t) / 3.0, 1.0))
        q = qslerp(hang('right_arm', fr), q, w)
        key_arm(c, 'right_arm', t, (q, np.asarray(off) * w))
    look_keys = [(0, AHEAD), (6.0, GATHER_CROP), (14.0, GATHER_CROP), (17.0, KV(keys, 17.0)),
                 (21.0, torso_to_world(gather_frame(21.0), BASKET_TOP)), (24.5, torso_to_world(gather_frame(24.5), BASKET_TOP)),
                 (31.0, AHEAD), (40, AHEAD)]
    w_head = smooth(min(t / 4.0, (40.0 - t) / 6.0, 1.0))
    c.key('head', t, qslerp(qeuler(), head_at(fr, KV(look_keys, t)), w_head), ease='linear')
c.mode('head', 'set')


G_IN_HAND = GRIP['right_arm'] + np.array([0.0, 1.4, -1.0])     # the crop in the fist, by its leaves
G_IN_BASKET = BASKET_TOP + np.array([0.0, 0.4, 0.0])
set_goods('gather', [(G_PULL, G_TOSS, ('part', 'right_arm', G_IN_HAND), 3.0),
                     (G_TOSS, G_IN, ('toss', ta.fk(getc('gather'), G_TOSS, 'right_arm', G_IN_HAND), 'body', G_IN_BASKET, 2.0), 3.0),
                     (G_IN, None, ('part', 'body', G_IN_BASKET), 3.0)])
check(c, 'hand on the leaves (t=11)', ta.fk(c, 11, 'right_arm', GRIP['right_arm']), GATHER_CROP, tol=0.8)
check(c, 'right knee on the ground (t=16)', (leg_end(c, 16, 'right_leg')[1],), (24.0,), tol=0.4)
check(c, 'left foot on the ground (t=16)', (leg_end(c, 16, 'left_leg')[1],), (24.0,), tol=0.4)

# ---- scythe (32, loop): mowing: a long sweep of the scythe round from the right to the left, the blade flat on the
# ground (swish at 7, the cut at 10), the weight going over onto the left foot; the blade lifted a little and swung back
# round to the right as the body comes up, and set down for the next stroke. The arms hold the scythe as the mowing stance
# has it (living_props.SCYTHE_ROT is solved for that stance) and the turn of the hips and shoulders swings it.
SC_P, SC_DROP = 35.0, 3.0
SC_PHI, SC_ALPHA = 105.0, 15.0          # the snath's heading and the right arm's (degrees right of ahead) in the stance
S35, C35 = math.sin(math.radians(35.0)), math.cos(math.radians(35.0))
SC_HEEL_Y = 23.4                        # the blade's heel on the ground (its underside 0.3 lower)


def scythe_stance():
    """The right arm's key holding the scythe in the stance (the blade flat on the ground) and the scythe's own frame."""
    fr = F_(qt=bend(SC_P, 0.0, 0.0), pr=(0.0, SC_DROP, 0.0))
    _, sr = chain_frame('right_arm', **fr)
    h = np.array([-math.sin(math.radians(SC_PHI)), 0.0, -math.cos(math.radians(SC_PHI))])
    xs = C35 * h + S35 * D
    ah = np.array([-math.sin(math.radians(SC_ALPHA)), 0.0, -math.cos(math.radians(SC_ALPHA))])
    target_y = SC_HEEL_Y - 17.0 * S35
    q = None
    for _ in range(6):
        dy = target_y - sr[1]
        rh = sr + ah * math.sqrt(max(0.0, 10.05 ** 2 - dy * dy)) + np.array([0.0, dy, 0.0])
        q = reach('right_arm', fr, rh, palm=unit(L + 0.3 * B), palm_w=0.3)
        g = hand_point('right_arm', q, fr)
        target_y -= (g + 17.0 * xs)[1] - SC_HEEL_Y
    return fr, q


SC_FR0, Q_SCYTHE = scythe_stance()


def scythe_point(fr, q, p):
    """A scythe-space point in the world, the right arm keyed [q] under [fr]."""
    Ra, joint = arm_world('right_arm', q, **fr)
    return joint + Ra @ tool_point('scythe', p)


SC_TOP = (-11.0, 0.0, 0.0)              # scythe space: where the left hand holds the snath
SC_HEEL = (17.0, 0.0, 0.0)
SC_BLADE_MID = (17.0, 0.0, -6.5)
_q_left, _off_left = solve_arm_slack('left_arm', SC_FR0, [(GRIP['left_arm'], scythe_point(SC_FR0, Q_SCYTHE, SC_TOP), 1.0)],
                                     [((0, 0, -1), D, 0.1)], slack=1.0, limit=1.4)
Q_SC_LEFT = (_q_left, _off_left)
# the turn (the hips' share and the shoulders'), the lean and the sink, the weight across: a stroke and the swing back
SC_YAW = [(0, 6.0), (3.0, 9.0), (6.0, -4.0), (10.0, -38.0), (14.0, -74.0), (17.0, -82.0), (19.5, -78.0), (24.0, -40.0),
          (28.5, 0.0), (32, 6.0)]
SC_LIFT = [(0, 0.0), (3.0, 0.0), (15.5, 0.0), (19.5, 1.0), (27.5, 1.0), (31.0, 0.0), (32, 0.0)]     # the blade carried back up (monotone: never below 0)
SC_X = [(0, -1.0), (4.0, -1.2), (16.0, 1.2), (20.0, 1.0), (30.0, -1.0), (32, -1.0)]
SC_FEET = {'right_leg': (-4.6, 1.8), 'left_leg': (4.4, -2.4)}


def scythe_frame(t):
    y = K(SC_YAW, t, 32)
    lift = K(SC_LIFT, t)
    return F_(qt=bend(SC_P - 5.0 * lift, 0.6 * y, 0.0), pr=(K(SC_X, t, 32), SC_DROP - 2.0 * lift, 0.0),
              qr=qeuler(y=0.4 * y))


c = clip('scythe', 32, True, look=('farmer', 2), tools_=('scythe',), station_='wheat',
         events=[('swish', 7), ('cut', 10)], traces=[('scythe', SC_BLADE_MID, (255, 240, 160))],
         views=((40, -20, True), (90, -6), (200, -24)), pan=(0.0, 0.0, -8.0))
for t in every(32):
    fr = scythe_frame(t)
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, Q_SCYTHE, ease='linear')
    key_arm(c, 'left_arm', t, Q_SC_LEFT)
    for side, (x, z) in SC_FEET.items():
        leg_to_r(c, t, side, fr, np.array([x, 24.0, z]), toe=F)
    # the eyes on the grass just ahead of the blade
    look = scythe_point(fr, Q_SCYTHE, SC_BLADE_MID) + np.array([0.0, 0.0, -4.0])
    c.key('head', t, head_at(fr, look), ease='linear')
c.mode('head', 'set')
seal(c)
for k in (5.0, 10.0, 14.0):
    check(c, f'blade heel on the ground (t={k:g})', (ta.fk(c, k, 'scythe', SC_HEEL)[1],), (SC_HEEL_Y,), tol=0.4)
check(c, 'left hand on the snath (t=10)', ta.fk(c, 10, 'left_arm', GRIP['left_arm']), ta.fk(c, 10, 'scythe', SC_TOP), tol=0.6)
for side in ('right_leg', 'left_leg'):
    check(c, f'{side} foot planted (t=14)', leg_end(c, 14, side), np.array([SC_FEET[side][0], 24.0, SC_FEET[side][1]]), tol=0.4)

# ================================================================== the mill and the woodpile

# ---- grind (40, loop): the miller at a grindstone a block ahead (its axle pointing at them), turning its crank round
# with the right hand, a turn every 20 ticks (clockwise as they see it), the body rocking into each turn and sunk on bent
# knees; the left hand braced on the left thigh. The crank's web turns about the axle (its prop track: it points from
# the fist to the hub, GRIND_HUB, whatever the arm does); the stone grinds at the bottom of each turn (ticks 10 and 30)
# with a puff of flour.
GRIND_HUB = np.array([0.0, 14.0, -9.4])         # the crank's hub on the grindstone's axle
GRIND_TURN = 20.0
GRIND_THROW = lp.CRANK_THROW
GRIND_FIST_Z = GRIND_HUB[2] + 2.9               # the fist on the handle, 2.9 px nearer than the hub (living_props: crank)
GRIND_DROP = 4.0
GRIND_FEET = {'right_leg': (-3.2, 2.0), 'left_leg': (2.8, -2.6)}


def grind_hand_angle(t):
    """Where the fist is round the hub at [t] (radians: 0 to the hub's left as the miller sees it, a quarter turn below)."""
    return math.radians(-90.0) - 2 * math.pi * t / GRIND_TURN


def grind_crank_angle(t):
    """The crank web's turn about the axle at [t] (radians about the model's z): from the fist to the hub."""
    return grind_hand_angle(t) + math.pi


def grind_hand(t):
    a = grind_hand_angle(t)
    return np.array([GRIND_HUB[0] + GRIND_THROW * math.cos(a), GRIND_HUB[1] + GRIND_THROW * math.sin(a), GRIND_FIST_Z])


def crank_track(t, grip):
    """The crank's web turned about the model's z to point from the fist to the hub."""
    d = GRIND_HUB - np.asarray(grip, float)
    a = math.atan2(d[1], d[0])
    c_, s_ = math.cos(a), math.sin(a)
    return np.array([[c_, -s_, 0.0], [s_, c_, 0.0], [0.0, 0.0, 1.0]]), None, None


TRACKS['grind'] = {'crank': crank_track}


def grind_thigh(fr):
    """Where the left hand rests: on top of the left thigh, a little above the knee."""
    hip = hip_of('left_leg', fr['pr'])
    x, z = GRIND_FEET['left_leg']
    foot = np.array([x, 24.0, z])
    return hip + unit(foot - hip) * 6.0 + np.array([0.6, -2.4, -0.6])


def grind_solve():
    """One turn's body, solved tick by tick (each from the last, round twice so the turn's end meets its start)."""
    sols, guess = {}, None
    for lap in range(2):
        for k in range(0, 21):
            t = float(k)

            def res(fr, qa, qb, t=t):
                out = list((hand_point('right_arm', qa, fr) - grind_hand(t)) * 3.0)
                out += list((hand_point('left_arm', qb, fr) - grind_thigh(fr)) * 0.6)
                out += list((arm_dir('right_arm', qa, fr, (0, 0, -1)) - L) * 0.15)
                return out
            fr, qa, qb, guess = body_solve(res, pitch0=30.0, guess=guess, pitch_w=0.03, yaw_w=0.03, roll_w=0.05,
                                           drop=GRIND_DROP)
            sols[t] = (fr, qa, qb)
    return sols


GRIND_SOL = grind_solve()
c = clip('grind', 40, True, look=('citizen', 7), tools_=('crank',), station_='grindstone',
         events=[('grind', 10), ('grind', 30)], traces=[('right_arm', GRIP['right_arm'], (255, 220, 140))],
         views=((40, -14, True), (90, -4), (-50, -10)))
for t in every(40, 1.0):
    fr, qa, qb = GRIND_SOL[t % GRIND_TURN]
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    for side, (x, z) in GRIND_FEET.items():
        leg_to(c, t, side, fr['pr'], np.array([x, 24.0, z]), toe=F)
    # eyes on the stone; in the second turn a glance down to the left, where the flour comes out
    glance = bump(t, 24.0, 36.0)
    look = GRIND_HUB + np.array([0.0, 0.0, -5.0]) + np.array([6.0, 9.0, -2.0]) * glance
    c.key('head', t, head_at(fr, look), ease='linear')
c.mode('head', 'set')
seal(c)
for k in (0.0, 5.0, 10.0, 15.0):
    check(c, f'fist on the crank (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), grind_hand(k), tol=0.4)
    check(c, f'crank hub on the axle (t={k:g})', ta.fk(c, k, 'crank', (GRIND_THROW, 0.0, -2.9)), GRIND_HUB, tol=0.45)
for side in ('right_leg', 'left_leg'):
    check(c, f'{side} foot planted (t=10)', leg_end(c, 10, side), np.array([GRIND_FEET[side][0], 24.0, GRIND_FEET[side][1]]), tol=0.4)

# ---- chop (30, loop): splitting firewood on a chopping block a block ahead (a log on end, its top at the belly): both
# hands wrench the axe free of the last split, then the left lets go and the right swings it up and back over the
# shoulder as the back arches and the body rises (held a moment at the top); it comes down hard, the left hand meeting
# the handle on the way, the knees giving and the body following, and the bit splits the billet on the block (tick 18)
# with a jolt back up the arms; a breath, and round again. (Block arms cannot both go overhead, hands together: the swing
# is the right arm's.) The right fist holds the handle, the left 3 px nearer its end (living_props: axe).
AXE_BIT = (8.5, 0.0, 3.4)                      # axe space: the middle of the cutting edge
AXE_LEFT = (-3.0, 0.0, 0.0)                    # axe space: where the left fist holds the handle
CHOP_AT = np.array([0.0, 6.4, -20.5])          # where the bit splits the billet standing on the block (its top at 5)
CHOP_HIT = 18.0
CH_FEET = {'right_leg': (-3.0, 1.0), 'left_leg': (3.0, -1.0)}


def chop_arm(fr, a, g):
    """The right arm swung [a] degrees forward and up from hanging and turned [g] degrees in towards the middle, the axe's
    edge leading the way it comes down (the palm side back up the swing)."""
    ra, rg = math.radians(a), math.radians(g)
    fg = np.array([math.sin(rg), 0.0, -math.cos(rg)])
    d = math.cos(ra) * D + math.sin(ra) * fg
    palm = -math.sin(ra) * D + math.cos(ra) * fg
    return free(fr, d, palm)


CH_DROP = [(0, 0.8), (4.0, 0.6), (10.0, 0.3), (14.0, 0.3), (17.0, 0.8), (18.5, 1.8), (20.0, 2.0), (24.0, 1.2), (30, 0.8)]


def chop_frame(t, p=None):
    p = K(CH_P, t, 30) if p is None else p
    return F_(qt=bend(p, 0.0, 0.0), vt=(0.0, sink_for(p), 0.0), pr=(0.0, K(CH_DROP, t, 30), 0.0))


def chop_point(fr, q, p):
    Ra, joint = arm_world('right_arm', q, **fr)
    return joint + Ra @ tool_point('axe', p)


# the strike: the swing, the turn in and the lean that put the bit on the billet
def _chop_res(x):
    fr = chop_frame(CHOP_HIT, x[2])
    return list(chop_point(fr, chop_arm(fr, x[0], x[1]), AXE_BIT) - CHOP_AT) + [(x[2] - 12.0) * 0.05]


_x = fit(_chop_res, [70.0, 12.0, 12.0])
CH_A_HIT, CH_G_HIT, CH_P_HIT = float(_x[0]), float(_x[1]), float(_x[2])
# the swing (degrees forward and up from hanging), the lean
CH_A = [(0, CH_A_HIT + 4.0), (2.0, CH_A_HIT + 8.0), (3.5, CH_A_HIT + 2.0), (6.0, CH_A_HIT + 40.0), (9.5, 168.0),
        (12.0, 186.0), (14.0, 190.0), (15.5, 165.0), (17.0, CH_A_HIT + 32.0), (CHOP_HIT, CH_A_HIT), (19.0, CH_A_HIT - 2.0),
        (20.5, CH_A_HIT + 3.0), (24.0, CH_A_HIT + 5.0), (30, CH_A_HIT + 4.0)]
CH_P = [(0, CH_P_HIT - 3.0), (3.0, CH_P_HIT - 4.0), (8.0, 0.0), (12.0, -7.0), (14.0, -8.0), (16.0, 2.0), (CHOP_HIT, CH_P_HIT),
        (19.5, CH_P_HIT + 2.0), (22.0, CH_P_HIT - 1.0), (30, CH_P_HIT - 3.0)]
CH_LEFT = [(0, 1.0), (3.5, 1.0), (6.0, 0.0), (15.0, 0.0), (17.5, 1.0), (30, 1.0)]     # the left hand on the handle


def chop_turn(a):
    """The turn in towards the middle as the swing goes: all of it low down, none overhead."""
    return CH_G_HIT * (1.0 - smooth((a - CH_A_HIT) / 70.0))


c = clip('chop', 30, True, look=('citizen', 5), tools_=('axe',), station_='block', events=[('chop', CHOP_HIT)],
         traces=[('axe', AXE_BIT, (255, 230, 150))], views=((40, -12, True), (90, -2), (-60, -10)))
lefts = Holds()
for t in every(30):
    fr = chop_frame(t)
    key_frame(c, t, fr, ease='linear')
    a = K(CH_A, t, 30)
    q = chop_arm(fr, a, chop_turn(a))
    c.key('right_arm', t, q, ease='linear')
    w = smooth(K(CH_LEFT, t))
    on = lefts.slack('left_arm', fr, [(GRIP['left_arm'], chop_point(fr, q, AXE_LEFT), 2.0)], [((0, 0, -1), U, 0.15)],
                     slack=0.8, limit=1.6)
    rest = (hang('left_arm', fr, out=IDLE_ARM_OUT + 6.0, fwd=10.0), np.zeros(3))
    key_arm(c, 'left_arm', t, mix_arm(rest, on, w))
    for side, (x, z) in CH_FEET.items():
        leg_to(c, t, side, fr['pr'], np.array([x, 24.0, z]), toe=F)
    # the eyes on the billet all the while
    c.key('head', t, head_at(fr, CHOP_AT + np.array([0.0, -1.0, 0.0])), ease='linear')
c.mode('head', 'set')
seal(c)
check(c, 'axe bit on the billet (t=18)', ta.fk(c, CHOP_HIT, 'axe', AXE_BIT), CHOP_AT, tol=0.5)
for k in (0.0, 18.0, 24.0):
    check(c, f'left fist on the handle (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']), ta.fk(c, k, 'axe', AXE_LEFT), tol=0.8)
print('   chop strike: swing', round(CH_A_HIT, 1), 'turn', round(CH_G_HIT, 1), 'lean', round(CH_P_HIT, 1))

# ---- pour_sack (40): at a hopper or bin a block ahead, the sack carried on the shoulder (carry_sack) swung down off it by
# the neck, the left hand coming up under its bottom, the mouth over the bin; tipped up and poured out (ticks 11 to 27,
# the bottom lifted higher as it empties), shaken out (27), and the empty sack let hang from the fist (30) as they
# straighten up. The sack goes from the shoulder to the hand at the first tick, where carry_sack has it (living_props:
# sack_held); the empty one hangs straight down (sack_empty).
SACK_MOUTH = (0.0, -1.1, -6.2)                  # sack_held space: the open neck
SACK_BOTTOM = (0.0, -0.5, 4.4)                  # sack_held space: the bottom end's middle
POUR_MOUTH = np.array([0.0, 6.6, -13.2])        # the mouth over the bin (its rim at 8)
PS_POUR, PS_SHAKE, PS_EMPTY = 11.0, 27.0, 30.0
PS_RF = [(0, (-1.9, 24.0, 0.0)), (2.0, (-1.9, 24.0, 0.0)), (6.0, (-2.3, 24.0, 1.0)), (34.0, (-2.3, 24.0, 1.0)),
         (38.0, (-1.9, 24.0, 0.0)), (40, (-1.9, 24.0, 0.0))]
PS_LF = [(0, (1.9, 24.0, 0.0)), (2.0, (1.9, 24.0, 0.0)), (6.0, (2.5, 24.0, -2.2)), (34.0, (2.5, 24.0, -2.2)),
         (38.0, (1.9, 24.0, 0.0)), (40, (1.9, 24.0, 0.0))]
# the sack's tilt while pouring: its axis (mouth to bottom) from 40 degrees off upright to 15 as it empties
PS_TILT = [(9.0, 40.0), (PS_POUR, 36.0), (20.0, 24.0), (PS_SHAKE, 15.0), (PS_EMPTY, 15.0)]


def carry_sack_start():
    """carry_sack's first frame: its torso, right arm (holding the sack's neck) and the left arm and head it adds."""
    cs = getc('carry_sack')
    qt, vt = cs.sample('torso', 0.0)
    qa, pa = cs.sample('right_arm', 0.0)
    return F_(qt=qt, vt=vt), qa


def pour_axis(tilt, wobble=0.0):
    """The sack's axis, mouth to bottom: up and back towards the pourer, [tilt] degrees off upright."""
    r = math.radians(tilt)
    return unit(np.array([0.06 * wobble, -math.cos(r), math.sin(r)]))


def pour_feet(t):
    return KV(PS_RF, t), KV(PS_LF, t)


def pour_solve(t, guess):
    shake = bump(t, PS_SHAKE - 2.0, PS_SHAKE + 2.5) * math.sin(2 * math.pi * (t - (PS_SHAKE - 2.0)) / 2.25)
    mouth = POUR_MOUTH + np.array([0.0, -0.8 * shake, 0.0])
    axis = pour_axis(K(PS_TILT, t), wobble=shake)

    def res(fr, qa, qb):
        Ra, j = arm_world('right_arm', qa, **fr)
        m = j + Ra @ tool_point('sack_held', SACK_MOUTH)
        b = j + Ra @ tool_point('sack_held', SACK_BOTTOM)
        out = list((m - mouth) * 2.0) + list((unit(b - m) - axis) * 3.0)
        out += list((hand_point('left_arm', qb, fr) - (b + np.array([0.0, 2.2, 0.0]))) * 0.6)
        return out
    return body_solve(res, pitch0=26.0, guess=guess, pitch_w=0.05, drop=0.5)


PS_FR0, PS_QA0 = carry_sack_start()
PS_QB0 = hang('left_arm', out=3.0)
_sol, _guess = {}, None
for _t in ticks(9, PS_EMPTY, 0.5):
    _fr, _qa, _qb, _guess = pour_solve(_t, _guess)
    _sol[_t] = (_fr, _qa, _qb)
PS_SOL = _sol
PS_END_FR = F_()
PS_END_QA = hang('right_arm', fwd=6.0)
PS_END_QB = hang('left_arm')


def mix_frames(a, b, w):
    return F_(qt=qslerp(a['qt'], b['qt'], w), vt=a['vt'] + (b['vt'] - a['vt']) * w, pr=a['pr'] + (b['pr'] - a['pr']) * w,
              qr=qslerp(a['qr'], b['qr'], w))


c = clip('pour_sack', 40, False, look=('farmer', 2), tools_=('sack_held',), station_='hopper',
         events=[('swing', 4), ('pour', PS_POUR), ('shake', PS_SHAKE), ('empty', PS_EMPTY)],
         traces=[('sack_held', SACK_MOUTH, (255, 230, 170))], views=((40, -14, True), (90, -2), (-50, -10)))
ta.INFO['pour_sack']['tools_at'] = lambda t: {'sack_held'} if t < PS_EMPTY else {'sack_empty'}
PS_HX = [(0, 0.0), (6.0, 18.0), (26.0, 20.0), (32.0, 12.0), (40, 0.0)]
PS_HZ = [(0, -5.0), (6.0, -2.0), (40, 0.0)]
for t in every(40):
    if t <= 9.0:
        w = smooth(t / 9.0)
        f1, qa1, qb1 = PS_SOL[9.0]
        fr = mix_frames(PS_FR0, f1, w)
        qa = qslerp(PS_QA0, qa1, w)
        qb = qslerp(PS_QB0, qb1, smooth((t - 2.0) / 7.0))
    elif t <= PS_EMPTY:
        fr, qa, qb = PS_SOL[t]
    else:
        w = smooth((t - PS_EMPTY) / (40.0 - PS_EMPTY))
        f1, qa1, qb1 = PS_SOL[PS_EMPTY]
        fr = mix_frames(f1, PS_END_FR, w)
        qa = qslerp(qa1, PS_END_QA, w)
        qb = qslerp(qb1, PS_END_QB, smooth((t - PS_EMPTY) / 6.0))
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    rf, lf = pour_feet(t)
    lift = 1.6 * bump(t, 2.0, 6.0)
    leg_to(c, t, 'right_leg', fr['pr'], rf, toe=F)
    leg_to(c, t, 'left_leg', fr['pr'], lf - np.array([0.0, lift + 1.4 * bump(t, 34.0, 38.0), 0.0]), toe=F)
    c.key('head', t, qeuler(x=K(PS_HX, t), z=K(PS_HZ, t)), ease='linear')
c.mode('head', 'add')
check(c, 'sack mouth over the bin (t=11)', ta.fk(c, PS_POUR, 'sack_held', SACK_MOUTH), POUR_MOUTH, tol=0.5)
check(c, 'sack mouth over the bin (t=20)', ta.fk(c, 20.0, 'sack_held', SACK_MOUTH), POUR_MOUTH, tol=0.5)
check(c, 'the sack where the shoulder had it (t=0)', ta.fk(c, 0.0, 'sack_held', (0.0, 0.0, 0.0)),
      ta.fk(getc('carry_sack'), 0.0, 'sack', (0.0, 0.0, 0.0)), tol=0.3)
check(c, 'the sack where the shoulder had it, far end (t=0)', ta.fk(c, 0.0, 'sack_held', SACK_BOTTOM),
      ta.fk(getc('carry_sack'), 0.0, 'sack', SACK_BOTTOM), tol=0.3)

# ================================================================== the crier and the lamplighter

def arm_at(fr, side, fwd, out):
    """An arm swung [fwd] degrees forward and up from hanging and [out] degrees out to its side, its front face turned up
    the way it swings: the 'set' key under [fr]."""
    rf, ro = math.radians(fwd), math.radians(out)
    fo = math.cos(ro) * F + math.sin(ro) * (R if side == 'right_arm' else L)
    return free(fr, math.cos(rf) * D + math.sin(rf) * fo, -math.sin(rf) * D + math.cos(rf) * fo)


def snap(x, k=2.2):
    """A swing quick through the middle and sharp at the ends (x in -1..1, as a cosine gives it)."""
    return math.tanh(k * x) / math.tanh(k)


# ---- ring_bell (30, loop): the crier ringing a big handbell held up high to the right: shaken up and back, then down
# and forward, twice round, ringing at each end (ticks 4, 11, 19 and 26), the body jolting with each ring; the left arm
# out from the side; the chin up, calling the town to listen, the eyes going over the crowd. The bell stays in the hand
# for announce.
RB_RINGS = (4.0, 11.0, 19.0, 26.0)
BELL_MOUTH = (0.0, 5.4, 0.0)                    # handbell space: the mouth's middle


def rb_swing(t):
    """+1 at the up-and-back end of the shake, -1 at the forward one, snapped: the ends at the rings."""
    ends = list(RB_RINGS) + [RB_RINGS[0] + 30.0]
    t = t if t >= RB_RINGS[0] else t + 30.0
    for i in range(4):
        if ends[i] <= t <= ends[i + 1]:
            return snap(math.cos(math.pi * (i + (t - ends[i]) / (ends[i + 1] - ends[i]))))
    return 1.0


c = clip('ring_bell', 30, True, look=('citizen', 2), tools_=('handbell',), events=[('ring', k) for k in RB_RINGS],
         traces=[('handbell', BELL_MOUTH, (255, 230, 140))], views=((32, -12), (90, -2), (-40, -8)))
for t in every(30):
    sw = rb_swing(t)
    jolt = sum(bump(t, k - 0.5, k + 3.5, 30) for k in RB_RINGS)
    sway = math.sin(2 * math.pi * t / 30.0)
    fr = F_(qt=bend(0.5 + 1.6 * jolt, 6.0 * sway, 2.5 - 0.6 * sw))
    key_frame(c, t, fr, root=False, ease='linear')
    c.key('right_arm', t, arm_at(fr, 'right_arm', 140.0 + 18.0 * sw, 15.0 + 2.0 * sw), ease='linear')
    c.key('left_arm', t, hang('left_arm', fr, out=13.0, fwd=-4.0 + 3.0 * sw), ease='linear')
    c.key('head', t, qeuler(x=-10.0 + 1.5 * jolt, y=14.0 * math.sin(2 * math.pi * t / 30.0 - 0.5), z=-1.5 * sw), ease='linear')
c.mode('head', 'add')
seal(c)

# ---- announce (80, loop): the crier reading out a proclamation: the scroll held up in the left hand and read from (the
# head bowed to it), looked up from to the crowd with the right hand's gestures, the bell still in it: swept out wide
# (tick 18), beating out the words (33, 37), thrown out to show them (48), and the bell shaken up high twice to end on
# (it rings at 60 and 64); and back to the scroll.
SCROLL_GRIP = np.array([2.2, 0.6, -9.2])        # where the left fist holds the scroll up to read (living_props: SCROLL_ROT)
_fr_read = F_(qt=bend(3.0, 0.0, 0.0))
_, _read_joint = chain_frame('left_arm', **_fr_read)
Q_READ = free(_fr_read, unit(SCROLL_GRIP - _read_joint), U)
AN_UP = [(0, 0.0), (12.0, 0.0), (15.0, 1.0), (25.0, 1.0), (27.5, 0.25), (29.5, 1.0), (40.0, 1.0), (42.0, 0.25), (44.0, 1.0),
         (55.0, 1.0), (56.5, 0.4), (58.0, 1.0), (69.0, 1.0), (72.0, 0.0), (80, 0.0)]        # 1: looking up from the scroll
AN_HY = [(0, 0.0), (14.0, -14.0), (19.0, 4.0), (25.0, 18.0), (31.0, -6.0), (38.0, 8.0), (46.0, 20.0), (52.0, 0.0),
         (60.0, -16.0), (66.0, 6.0), (72.0, 0.0), (80, 0.0)]
AN_ARM = [(0, (6.0, 6.0)), (12.0, (6.0, 6.0)), (15.0, (40.0, 20.0)), (18.0, (74.0, 46.0)), (21.0, (70.0, 56.0)),
          (24.0, (52.0, 40.0)), (27.0, (24.0, 12.0)), (30.0, (60.0, 8.0)), (33.0, (72.0, 8.0)), (35.0, (58.0, 8.0)),
          (37.0, (72.0, 8.0)), (39.5, (54.0, 8.0)), (42.0, (24.0, 6.0)), (45.0, (60.0, 30.0)), (48.0, (86.0, 50.0)),
          (52.0, (82.0, 54.0)), (55.0, (34.0, 14.0)), (57.5, (112.0, 14.0)), (60.0, (138.0, 16.0)), (62.0, (116.0, 14.0)),
          (64.0, (138.0, 16.0)), (66.0, (112.0, 14.0)), (69.0, (60.0, 8.0)), (73.0, (8.0, 6.0)), (80, (6.0, 6.0))]
AN_P = [(0, 3.0), (14.0, 3.0), (18.0, 5.0), (24.0, 2.0), (33.0, 2.5), (37.0, 2.5), (48.0, 5.0), (56.0, 3.0), (60.0, 1.5),
        (62.0, 3.0), (64.0, 1.5), (67.0, 3.5), (80, 3.0)]
AN_YAW = [(0, 0.0), (14.0, 0.0), (18.0, 8.0), (25.0, 6.0), (33.0, 2.0), (44.0, -6.0), (48.0, 10.0), (55.0, 4.0), (60.0, 2.0),
          (72.0, 0.0), (80, 0.0)]
c = clip('announce', 80, True, look=('citizen', 2), tools_=('handbell', 'scroll'),
         events=[('sweep', 18), ('beat', 33), ('beat', 37), ('show', 48), ('ring', 60), ('ring', 64)],
         traces=[('handbell', BELL_MOUTH, (255, 200, 120))], views=((32, -12), (90, -2), (-30, -8)))
for t in every(80):
    up = smooth(K(AN_UP, t, 80))
    fr = F_(qt=bend(K(AN_P, t, 80) - 1.0 * up, K(AN_YAW, t, 80), 0.0))
    key_frame(c, t, fr, root=False, ease='linear')
    c.key('left_arm', t, qmul(Q_READ, qeuler(x=-2.0 * up)), ease='linear')
    fwd, out = KV(AN_ARM, t, 80)[:2]
    c.key('right_arm', t, arm_at(fr, 'right_arm', fwd, out), ease='linear')
    # reading: the head bowed to the scroll; looking up: over the crowd
    c.key('head', t, qeuler(x=24.0 * (1.0 - up) - 4.0 * up, y=-12.0 * (1.0 - up) + K(AN_HY, t, 80) * up), ease='linear')
c.mode('head', 'add')
seal(c)
# the scroll hangs upright, its writing to the reader
for k in (6.0, 40.0):
    sheet_down = ta.fk(c, k, 'scroll', (0.0, 8.0, 0.0)) - ta.fk(c, k, 'scroll', (0.0, 0.0, 0.0))
    check(c, f'scroll hanging straight (t={k:g}, the sheet\'s bottom under its top)', sheet_down / 8.0, D, tol=0.12)

# ---- light_lamp (50): the lamplighter at a street lamp a block ahead (the lamp station: a wall post, two fences and a
# lantern on top, its glass 52 px up): the left hand comes up onto the pole and the pole is raised, drawn in under the
# lantern and across, and its burning wick pushed up into the lantern's near face (tick 21), held there, a little worked
# about, until the lamp catches (24), drawn out and lowered to the slant it is carried at, the left hand letting go; the
# eyes up on the lamp the while, the body leaning back to look.
LAMP_DOOR = np.array([0.0, -27.5, -12.6])       # where the wick goes in: the lantern's near face, mid-height
LAMP_WICK = (42.0, 0.0, 0.0)                    # lamp_pole space: the flame's middle
LAMP_LEFT = 14.0                                # how far up the pole the left hand would hold it (it holds where it reaches)
LL_IN, LL_LIT, LL_OUT = 21.0, 24.0, 27.0
LL_A, LL_B = 6.0, 44.0                          # the solved stretch; before and after it, blends from and to the carry
LL_FR0 = F_()
LL_Q0 = hang('right_arm')


def ll_point(fr, q, p):
    Ra, joint = arm_world('right_arm', q, **fr)
    return joint + Ra @ tool_point('lamp_pole', p)


LL_REST = ll_point(LL_FR0, LL_Q0, LAMP_WICK)
# the wick's way: drawn in and down out of the lantern's way, across in front of it, up into it; out, down and back
LL_PATH = [(LL_A, LL_REST), (11.0, np.array([LL_REST[0] + 0.5, -24.5, -10.5])), (16.0, np.array([-1.0, -27.0, -10.0])),
           (LL_IN, LAMP_DOOR), (22.5, LAMP_DOOR + np.array([0.4, -0.3, -0.3])), (LL_LIT, LAMP_DOOR + np.array([-0.3, 0.2, -0.2])),
           (25.5, LAMP_DOOR + np.array([0.2, -0.2, -0.3])), (LL_OUT, LAMP_DOOR), (30.0, np.array([0.0, -26.8, -9.8])),
           (36.0, np.array([LL_REST[0] + 1.5, -24.0, -11.5])), (LL_B, LL_REST)]
LL_PITCH = [(LL_A, 0.0), (14.0, -5.0), (LL_IN, -7.0), (LL_OUT, -7.0), (36.0, -4.0), (LL_B, 0.0)]


def on_pole(fr, qa, p):
    """The point of the pole nearest world [p] (between 6 and 26 px up it), and how far up it that is."""
    Ra, j = arm_world('right_arm', qa, **fr)
    base = j + Ra @ tool_point('lamp_pole', (0.0, 0.0, 0.0))
    d = Ra @ tool_dir('lamp_pole', (1.0, 0.0, 0.0))
    k = min(26.0, max(6.0, float(np.dot(np.asarray(p, float) - base, d))))
    return base + k * d, k


def ll_solve():
    sols, guess = {}, None
    for t in ticks(LL_A, LL_B, 0.5):
        target = KV(LL_PATH, t)

        def res(fr, qa, qb, target=target):
            Ra, j = arm_world('right_arm', qa, **fr)
            out = list((j + Ra @ tool_point('lamp_pole', LAMP_WICK) - target) * 2.0)
            lg = hand_point('left_arm', qb, fr)
            near, k = on_pole(fr, qa, lg)
            return out + list((lg - near) * 1.2) + [(k - LAMP_LEFT) * 0.04]
        fr, qa, qb, guess = body_solve(res, pitch0=K(LL_PITCH, t), guess=guess, pitch_w=0.3, yaw_w=0.15, root_w=1.0)
        sols[t] = (fr, qa, qb)
    return sols


LL_SOL = ll_solve()
LL_LEFT_ON = [(0, 0.0), (1.0, 0.0), (LL_A, 1.0), (37.0, 1.0), (41.0, 0.0), (50, 0.0)]
c = clip('light_lamp', 50, False, look=('citizen', 4), tools_=('lamp_pole',), station_='lamp',
         events=[('reach', LL_IN), ('lit', LL_LIT)], traces=[('lamp_pole', LAMP_WICK, (255, 220, 120))],
         views=((40, -10, True), (90, -2), (-50, -10)), pan=(0.0, -12.0, -6.0))
for t in every(50):
    if t < LL_A:
        w = smooth(t / LL_A)
        f1, qa1, qb1 = LL_SOL[LL_A]
        fr, qa, qb = mix_frames(LL_FR0, f1, w), qslerp(LL_Q0, qa1, w), qb1
    elif t <= LL_B:
        fr, qa, qb = LL_SOL[t]
    else:
        w = smooth((t - LL_B) / (50.0 - LL_B))
        f1, qa1, qb1 = LL_SOL[LL_B]
        fr, qa, qb = mix_frames(f1, LL_FR0, w), qslerp(qa1, LL_Q0, w), qb1
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    # the left hand onto the pole and off it again
    c.key('left_arm', t, qslerp(hang('left_arm', fr), qb, smooth(K(LL_LEFT_ON, t))), ease='linear')
    plant(c, t, fr)
    look_up = smooth((t - 1.0) / 8.0) * (1.0 - smooth((t - 41.0) / 8.0))
    c.key('head', t, qslerp(qeuler(), head_at(fr, LAMP_DOOR + np.array([0.0, 1.0, -2.0])), 0.85 * look_up), ease='linear')
c.mode('head', 'set')
check(c, 'wick in the lantern\'s face (t=21)', ta.fk(c, LL_IN, 'lamp_pole', LAMP_WICK), LAMP_DOOR, tol=0.5)
check(c, 'wick in the lantern\'s face (t=24)', ta.fk(c, LL_LIT, 'lamp_pole', LAMP_WICK), LAMP_DOOR, tol=0.8)
for k in (10.0, LL_IN, 30.0):
    fr_k, qa_k, qb_k = LL_SOL[k]
    near_k, up_k = on_pole(fr_k, qa_k, ta.fk(c, k, 'left_arm', GRIP['left_arm']))
    check(c, f'left hand on the pole (t={k:g}, {up_k:.1f} px up it)', ta.fk(c, k, 'left_arm', GRIP['left_arm']), near_k, tol=0.8)
print('   light_lamp: the wick carried at', np.round(LL_REST, 1))

# ================================================================== at the well

# ---- draw_water (80): at the town well (the well station: a ring of stone a block high round the water, three blocks
# across, the water in the middle two blocks ahead; posts on the corners carry a roof of slabs). Standing a little back
# from the curb (WELL_BACK), the well's rope runs from the bucket down in the water up the shaft, over the curb's inner
# edge, across its top and over its near edge to the hands, and on behind them to a coil on the ground. Five strokes hand
# over hand, the left first (ticks 4 to 44), the body leaning into each reach and back as it pulls, haul the bucket up
# the shaft (knocking on the edge as it comes over, 29) and across the curb's top towards them (scraping, 33); a step in
# to the curb, the right hand takes it by the bail (48), the left lets the rope go and braces on the curb, and the bucket
# is lifted clear, brought back over the edge and let down to hang at the side, swinging, the water slopping (52, 60).
# The rope's stretches, the bucket in the well and its swing in the hand are prop tracks (living_props: haul_rope_*,
# tail_rope_*, curb_rope, well_rope, well_bucket, bucket).
WELL_BACK = 3.5                                 # how far back from the block's middle they stand to haul
WELL_STEP = 1.5                                 # where they step in to (from the block's middle) to take the bucket
CURB_TOP, CURB_NEAR, CURB_INNER = 8.0, -8.0, -24.0
WATER_Y = 25.8                                  # the water's face in the shaft, under the ground (24)
ROPE_IN = np.array([-0.6, 7.65, -23.9])         # the rope over the curb's inner edge
ROPE_OVER = np.array([-2.0, 7.65, -8.1])        # and over its near edge
ROPE_COIL = np.array([-8.0, 23.6, 9.0])         # where the hauled-in rope lies coiled, behind and to the right
_rope_end = np.array([-3.39, 10.96, 0.70])      # where each pull ends, by the right hip
ROPE_DIR = unit(_rope_end - ROPE_OVER)
S_FAR, S_NEAR = 2.3, 9.5                        # each stroke takes the rope from this far past the edge to this far
STROKE = S_NEAR - S_FAR
DW_STROKES = [('left_arm', 4.0), ('right_arm', 12.0), ('left_arm', 20.0), ('right_arm', 28.0), ('left_arm', 36.0)]
DW_PULL, DW_HOLD = 7.0, 8.5                     # in each 8-tick stroke: pulled for 7 ticks, held till 8.5 (the other hand has it)
DW_GRAB = 48.0                                  # the right hand takes the bucket
# the bucket's way (its bail's top): up the shaft from the water, over the inner edge, across the curb to stand by its
# near edge; it moves as far as the rope has been hauled in
BUCKET_WAY = [np.array([-0.6, WATER_Y - 1.6, -26.2]), np.array([-0.6, 1.6, -26.2]), np.array([-0.8, 1.6, -22.4]),
              np.array([-1.0, 1.6, -12.8])]


def _way_lengths():
    out, acc = [0.0], 0.0
    for a, b in zip(BUCKET_WAY, BUCKET_WAY[1:]):
        acc += float(np.linalg.norm(b - a))
        out.append(acc)
    return out


WAY_AT = _way_lengths()
UP_SHAFT = WAY_AT[1]                            # hauled this far, the bucket is at the inner edge


def stroke_s(u):
    """How far past the curb's near edge a hand [u] ticks into its stroke holds the rope (its pull: eased in and out)."""
    if u <= 0.0:
        return S_FAR
    if u >= DW_PULL:
        return S_NEAR
    x = u / DW_PULL
    return S_FAR + STROKE * (x * x * (3 - 2 * x)) ** 1.15


def hauled(t):
    """How much rope has come in by [t] (px): the strokes' pulls, one after another."""
    return sum(stroke_s(t - t0) - S_FAR for _, t0 in DW_STROKES)


def bucket_at(t):
    """Where the bucket's bail is, and how it tips (degrees, its top towards the hauler), as far as the rope has come."""
    h = min(hauled(t), WAY_AT[-1])
    for i in range(3):
        if h <= WAY_AT[i + 1] or i == 2:
            f = (h - WAY_AT[i]) / (WAY_AT[i + 1] - WAY_AT[i])
            p = BUCKET_WAY[i] + (BUCKET_WAY[i + 1] - BUCKET_WAY[i]) * min(1.0, max(0.0, f))
            tip = 24.0 * math.sin(math.pi * min(1.0, max(0.0, f))) if i == 1 else 0.0
            return p, tip
    return BUCKET_WAY[-1], 0.0


def gripping(side, t):
    """Whether [side]'s hand is on the rope at [t]."""
    for s_, t0 in DW_STROKES:
        if s_ == side and t0 <= t <= t0 + DW_HOLD + (DW_GRAB - 44.0 if t0 == DW_STROKES[-1][1] else 0.0):
            return True
    return False


def trailing(t):
    """The hand nearer the coil end of the rope (the last to have pulled) while any is on it."""
    on = [s_ for s_ in ('left_arm', 'right_arm') if gripping(s_, t)]
    if len(on) == 1:
        return on[0]
    if not on:
        return None
    last = max((t0, s_) for s_, t0 in DW_STROKES if t0 <= t)
    return [s_ for s_ in on if s_ != last[1]][0] if len(on) == 2 else last[1]


def rope_point(s):
    return ROPE_OVER + ROPE_DIR * s


DW_FEET = [(0, (-1.9, 0.0), (1.9, 0.0)), (0.5, (-1.9, 0.0), (1.9, 0.0)), (3.0, (-2.6, 4.5), (1.9, 0.0)),
           (4.5, (-2.6, 4.5), (2.4, 2.0)), (42.0, (-2.6, 4.5), (2.4, 2.0)), (45.0, (-2.4, 2.6), (2.4, 2.0)),
           (48.0, (-2.4, 2.6), (2.2, 0.0)), (50.5, (-2.4, 2.6), (2.2, 0.0)), (53.0, (-2.2, 4.2), (2.2, 0.0)),
           (53.5, (-2.2, 4.2), (2.2, 0.0)), (56.0, (-2.2, 4.2), (2.0, 2.4)), (66.0, (-2.2, 4.2), (2.0, 2.4)),
           (69.0, (-1.9, 3.1), (2.0, 2.4)), (70.0, (-1.9, 3.1), (2.0, 2.4)), (73.0, (-1.9, 3.1), (1.9, 2.9)),
           (80, (-1.9, 3.1), (1.9, 2.9))]
DW_BACK = 3.0                                   # where they step back to, the bucket in hand
DW_ROOT_Z = [(0, WELL_BACK), (42.0, WELL_BACK), (48.0, WELL_STEP), (50.0, WELL_STEP), (56.0, 3.0), (80, 3.0)]


def dw_feet(t):
    r = KV([(k, (a[0], 24.0, a[1])) for k, a, _ in DW_FEET], t)
    l = KV([(k, (b[0], 24.0, b[1])) for k, _, b in DW_FEET], t)
    return r, l


def hand_target(side, t):
    """Where [side]'s hand is wanted at [t] (None: left to the pose it is blended from)."""
    s = -1.0 if side == 'right_arm' else 1.0
    for s_, t0 in DW_STROKES:
        if s_ != side:
            continue
        u = t - t0
        if 0.0 <= u <= DW_HOLD:
            return rope_point(stroke_s(u))
        last = t0 == DW_STROKES[-1][1]
        side_last = t0 == max(t_ for s__, t_ in DW_STROKES if s__ == side)
        if last and side == 'left_arm' and DW_HOLD < u <= DW_GRAB - t0:
            return rope_point(S_NEAR)
        if DW_HOLD < u < 16.0 and not side_last:
            # back up the rope to take it again: up and out round the other hand
            v = (u - DW_HOLD) / (16.0 - DW_HOLD)
            e = v * v * (3 - 2 * v)
            p = rope_point(S_NEAR) + (rope_point(S_FAR) - rope_point(S_NEAR)) * e
            return p + np.array([s * 1.4, -3.6, -0.6]) * math.sin(math.pi * v)
    return None


DW_RIGHT_LATE = [(36.0 + DW_HOLD, None), (42.5, np.array([-0.6, 0.2, -6.0])), (46.0, np.array([-1.2, 0.6, -12.0])),
                 (DW_GRAB, BUCKET_WAY[-1])]
DW_LIFT = [(DW_GRAB, BUCKET_WAY[-1]), (51.0, np.array([-1.4, 0.4, -12.6])), (54.5, np.array([-2.6, 0.5, -7.4])),
           (56.0, np.array([-3.4, 1.3, -5.3])), (58.0, np.array([-4.8, 7.4, -4.6]))]
DW_BRACE = np.array([3.2, 6.3, -10.0])          # the left fist on the curb's top as they lift


def dw_pitch(t):
    haul = 9.0 + 6.0 * math.cos(2 * math.pi * (t - 4.0) / 8.0)
    if t <= 4.0:
        return 4.0 + (15.0 - 4.0) * smooth(t / 4.0)
    if t <= 44.0:
        return haul
    return K([(44.0, 9.0 + 6.0 * math.cos(2 * math.pi * 40.0 / 8.0)), (DW_GRAB, 34.0), (51.0, 31.0), (55.0, 9.0),
              (58.0, 4.0), (62.0, 1.0), (80, 1.0)], t)


def dw_yaw(t):
    if t <= 44.0:
        return 6.0 + 6.0 * math.sin(2 * math.pi * (t - 4.0) / 16.0) * smooth(t / 4.0)
    return K([(44.0, 6.0 + 6.0 * math.sin(2 * math.pi * 40.0 / 16.0)), (DW_GRAB, -12.0), (51.0, -10.0), (55.0, -4.0),
              (62.0, 0.0), (80, 0.0)], t)


DW_FR0 = F_()


def dw_solve():
    sols, guess = {}, None
    for t in ticks(4.0, 62.0, 0.5):
        rt, lt = dw_targets(t)
        rz = K(DW_ROOT_Z, t)

        lw = 3.0 if 40.0 <= t <= DW_GRAB else 1.5

        def res(fr, qa, qb, rt=rt, lt=lt, lw=lw):
            out = []
            if rt is not None:
                out += list((hand_point('right_arm', qa, fr) - rt) * 1.5)
            if lt is not None:
                out += list((hand_point('left_arm', qb, fr) - lt) * lw)
            return out
        fr, qa, qb, guess = body_solve(res, pitch0=dw_pitch(t), yaw0=dw_yaw(t), root0=(0.0, 0.0, rz), guess=guess,
                                        pitch_w=0.12, yaw_w=0.1, root_w=1.2, drop=0.6, arms_w=0.0)
        sols[t] = (fr, qa, qb)
    return sols


def dw_targets(t):
    """Both hands' targets at [t] (in the solved stretch, 4 to 62)."""
    if t <= DW_GRAB:
        rt = hand_target('right_arm', t)
        if rt is None and t < 12.0:
            # on the way from hanging to the rope (12): through a point in front, then to where it takes hold
            rt = KV([(4.0, np.array([-5.4, 9.4, -3.6])), (8.0, np.array([-3.8, 5.0, -6.6])), (12.0, rope_point(S_FAR))], t)
        if rt is None and t > 36.0:
            rt = KV([(36.0 + DW_HOLD, rope_point(S_NEAR))] + DW_RIGHT_LATE[1:], t)
    else:
        rt = KV(DW_LIFT, t) if t <= 58.0 else None
    if t <= DW_GRAB:
        lt = hand_target('left_arm', t)
    else:
        lt = KV([(DW_GRAB, rope_point(S_NEAR)), (49.5, DW_BRACE + np.array([0.0, -2.0, 1.0])), (50.5, DW_BRACE),
                 (53.0, DW_BRACE)], t) if t <= 53.0 else None
    return rt, lt


DW_SOL = dw_solve()
DW_END_FR = F_(pr=(0.0, 0.1, DW_BACK))


def dw_pose(t):
    """The frame and both arms at [t]: blended in from standing (0 to 4), solved (4 to 62), let down to rest (58 on)."""
    if t < 4.0:
        w = smooth(t / 4.0)
        f1, qa1, qb1 = DW_SOL[4.0]
        fr = mix_frames(DW_FR0, f1, w)
        return fr, qslerp(hang('right_arm', fr), qa1, w), qslerp(hang('left_arm', fr), qb1, w)
    if t <= 62.0:
        fr, qa, qb = DW_SOL[t]
    else:
        fr, qa, qb = mix_frames(DW_SOL[62.0][0], DW_END_FR, smooth((t - 62.0) / 10.0)), None, None
    end_a = hang('right_arm', fr, out=11.0, fwd=2.0)
    end_b = hang('left_arm', fr, out=6.0)
    if qa is None:
        return fr, end_a, end_b
    if t > 53.0:
        qb = qslerp(qb, end_b, smooth((t - 53.0) / 5.0))      # the left hand off the curb, down to the side
    if t > 58.0:
        qa = qslerp(qa, end_a, smooth((t - 58.0) / 4.0))      # the bucket let down to hang
    return fr, qa, qb


c = clip('draw_water', 80, False, look=('citizen', 6), station_='well',
         tools_=('curb_rope', 'well_rope', 'well_bucket', 'haul_rope_left', 'haul_rope_right', 'tail_rope_left',
                 'tail_rope_right', 'bucket'),
         events=[('pull', 8), ('pull', 16), ('pull', 24), ('pull', 32), ('pull', 40), ('knock', 29), ('scrape', 33),
                 ('grab', 48), ('slosh', 52), ('slosh', 60)],
         views=((50, -14, True), (90, -4), (160, -28)), pan=(0.0, 0.0, -9.0))
DW_HX = [(0, 0.0), (4.0, 16.0), (28.0, 18.0), (34.0, 24.0), (44.0, 30.0), (DW_GRAB, 26.0), (54.0, 14.0), (60.0, 20.0),
         (66.0, 22.0), (72.0, 6.0), (80, 2.0)]
for t in every(80):
    fr, qa, qb = dw_pose(t)
    key_frame(c, t, fr, ease='linear')
    c.key('right_arm', t, qa, ease='linear')
    c.key('left_arm', t, qb, ease='linear')
    rf, lf = dw_feet(t)
    rlift = 1.6 * bump(t, 0.5, 3.0) + 1.4 * bump(t, 42.0, 45.5) + 1.2 * bump(t, 50.5, 53.5) + 0.9 * bump(t, 66.0, 69.5)
    llift = 1.0 * bump(t, 2.5, 4.5) + 1.4 * bump(t, 44.5, 48.0) + 1.2 * bump(t, 53.0, 56.0) + 0.9 * bump(t, 69.5, 73.0)
    leg_to(c, t, 'right_leg', fr['pr'], rf - np.array([0.0, rlift, 0.0]), toe=F)
    leg_to(c, t, 'left_leg', fr['pr'], lf - np.array([0.0, llift, 0.0]), toe=F)
    c.key('head', t, qeuler(x=K(DW_HX, t), y=-8.0 * bump(t, 28.0, 46.0) + 10.0 * bump(t, 62.0, 74.0)), ease='linear')
c.mode('head', 'add')


def dw_shown(t):
    out = {'curb_rope', 'well_bucket'} if t < DW_GRAB else {'bucket'}
    if hauled(t) < UP_SHAFT + 0.5:
        out.add('well_rope')
    for side, name in (('left_arm', 'left'), ('right_arm', 'right')):
        if gripping(side, t):
            out.add(f'haul_rope_{name}')
    tr = trailing(t)
    if tr is not None and t < DW_GRAB:
        out.add('tail_rope_' + ('left' if tr == 'left_arm' else 'right'))
    return out


ta.INFO['draw_water']['tools_at'] = dw_shown


# the bucket's swing in the hand: a short pendulum (its bail to its middle) shaken by the fist's comings and goings
def _bucket_swing():
    g, L, damp = 0.392, 4.2, 0.07       # px per tick squared (a block a metre), px, per tick
    th = np.zeros(2)                     # swing about x (forward and back) and z (side to side), radians
    om = np.zeros(2)
    out = {}
    prev_v = None
    prev_p = None
    step = 0.5
    t = DW_GRAB
    while t <= 80.0 + 1e-6:
        fr, qa, qb = dw_pose(min(t, 80.0))
        p = hand_point('right_arm', qa, fr)
        if prev_p is not None:
            v = (p - prev_p) / step
            a = (v - prev_v) / step if prev_v is not None else np.zeros(3)
            prev_v = v
            # the fist's push sideways swings the bucket the other way; forward and back about x, sideways about z
            for i, ax in enumerate((2, 0)):
                acc = -(g / L) * math.sin(th[i]) - (a[ax] / L) * math.cos(th[i]) - damp * om[i]
                om[i] += acc * step
                th[i] += om[i] * step
        else:
            prev_v = np.zeros(3)
        prev_p = p
        out[round(t * 2)] = (float(th[0]), float(th[1]))
        t += step
    return out


DW_SWING = _bucket_swing()


def _bucket_track(t, pivot):
    if t < DW_GRAB:
        return np.eye(3), None, None
    a, b = DW_SWING.get(round(min(t, 80.0) * 2), (0.0, 0.0))
    # turned about x by a (its bottom swinging forward for a fist pushed back) and about z by b
    Rx = qmat(qeuler(x=math.degrees(a)))
    Rz = qmat(qeuler(z=-math.degrees(b)))
    return Rz @ Rx, None, None


def _well_bucket_track(t, pivot):
    p, tip = bucket_at(t)
    return qmat(qeuler(x=-tip)), p, None


def _well_rope_track(t, pivot):
    p, _ = bucket_at(t)
    R, S = aim(ROPE_IN, p, lp.ROPE_LEN)
    return R, ROPE_IN, S


def _curb_rope_track(t, pivot):
    p, _ = bucket_at(t)
    to = ROPE_IN if hauled(t) < UP_SHAFT else p
    R, S = aim(ROPE_OVER, to, lp.ROPE_LEN)
    return R, ROPE_OVER, S


def _haul_track(t, pivot):
    R, S = aim(pivot, ROPE_OVER, lp.ROPE_LEN)
    return R, None, S


def _tail_track(t, pivot):
    R, S = aim(pivot, ROPE_COIL, lp.ROPE_LEN)
    return R, None, S


TRACKS['draw_water'] = {'bucket': _bucket_track, 'well_bucket': _well_bucket_track, 'well_rope': _well_rope_track,
                        'curb_rope': _curb_rope_track, 'haul_rope_left': _haul_track, 'haul_rope_right': _haul_track,
                        'tail_rope_left': _tail_track, 'tail_rope_right': _tail_track}

for side in ('left_arm', 'right_arm'):
    for s_, t0 in DW_STROKES:
        if s_ == side:
            for k in (t0, t0 + DW_PULL):
                want = rope_point(stroke_s(k - t0))
                check(c, f'{side} on the rope (t={k:g})', ta.fk(c, k, side, GRIP[side]), want, tol=0.5)
check(c, 'right hand on the bucket\'s bail (t=48)', ta.fk(c, DW_GRAB, 'right_arm', GRIP['right_arm']), BUCKET_WAY[-1], tol=0.5)
for k in (50.0, 52.0, 54.0, 55.0, 56.0):
    got = ta.fk(c, k, 'right_arm', GRIP['right_arm'])
    check(c, f'the bucket clear of the curb (t={k:g}: its bottom above the top, or past the near edge)',
          np.array([0.0, min(0.0, CURB_TOP - (got[1] + 6.4)) if got[2] < CURB_NEAR + 2.2 else 0.0, 0.0]), np.zeros(3), tol=0.05)
check(c, 'left fist on the curb (t=52)', ta.fk(c, 52.0, 'left_arm', GRIP['left_arm']), DW_BRACE, tol=0.6)
print('   draw_water: rope hauled', round(hauled(44.0), 1), 'of', round(WAY_AT[-1], 1), '| bucket over the edge at t',
      next(k for k in ticks(0, 44, 0.5) if hauled(k) >= UP_SHAFT))

# ================================================================== talking: the gestures a line is said with (art/scripts/talk_clips.py)

import talk_clips
talk_clips.build(sys.modules[__name__])
WANTED += talk_clips.WANTED

# ================================================================== the lamplighter's round (art/scripts/lamp_clips.py)
import lamp_clips  # noqa: E402
lamp_clips.make(sys.modules[__name__])


# ================================================================== the caravan crew (art/scripts/road_clips.py)

# Built on this kit, written with the rest: their clips, their props' tracks and moments into LivingSchedule.kt, and
# CrewMoves.kt (the numbers the game places them by).
import road_clips  # noqa: E402
road_clips.build(sys.modules[__name__])


# ================================================================== the works and the streets (art/scripts/works_clips.py,
# street_clips.py: M6)

# Built on this kit, written with the rest: their clips, their props' tracks and moments into LivingSchedule.kt.
import works_clips  # noqa: E402
works_clips.build(sys.modules[__name__])
import street_clips  # noqa: E402
street_clips.build(sys.modules[__name__])
import cook_clips  # noqa: E402
cook_clips.build(sys.modules[__name__])
import trade_clips  # noqa: E402
trade_clips.build(sys.modules[__name__])


# ================================================================== the tales' six clips (art/scripts/tale_clips.py)

# Built on this kit, written with the rest: grieve, wring_hands, shake_hands, feed_fire, hobble and lay_posy.
import tale_clips  # noqa: E402
tale_clips.build(sys.modules[__name__])


if __name__ == '__main__':
    main(sys.argv)
