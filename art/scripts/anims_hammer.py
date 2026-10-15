"""
The hammer class's combat moves: vanilla's mace and Guildmark's war hammers (the war hammer is solved against the
mace, same grip and handle, so clips made with WAR_HAMMER fit both). Third-person clips pose the skeleton,
first-person clips move the held item. Run:

    python3 art/scripts/anims_hammer.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_war_hammer.png>

Timings match Move.kt: a clip lasts as many ticks as its move, and its blow lands inside the move's hit window.

The hammer is the heaviest thing in the game and is held in both hands, so these clips are authored from the
weapon outwards: each move says where the handle points and which way the striking face looks (in the
character's frame) and the right arm is worked out from that, whatever the hips and shoulders are doing; the left
hand closes on the second grip (hold_with_both). The feet are given as footprints, and the legs find the ground
wherever the hips go.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import WAR_HAMMER as G

clips, fps = [], []

# ------------------------------------------------------------------ the hammer in the hand

# The handle lies 80 degrees from the forearm and the striking face along it (the hand's grip is fixed), so a
# pose is a handle direction and a face direction; the arm is worked out from them.
A = float(G.tp_blade[1])
E = math.sqrt(1.0 - A * A)

def qinv(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])

def hz(az, el=0.0):
    """A direction by compass: [az] degrees from straight ahead, positive to the right, [el] degrees above level."""
    a, e = math.radians(az), math.radians(el)
    return v(f=math.cos(e) * math.cos(a), u=math.sin(e), r=math.cos(e) * math.sin(a))

def vp(alpha, az=0.0):
    """A handle in the vertical plane at compass bearing [az]: [alpha] degrees up from level ahead, going up and
    over the head (past 90 it points back), or down in front for a negative angle."""
    a = math.radians(alpha)
    return math.cos(a) * hz(az) + math.sin(a) * U

def clearance(q_arm, left=False):
    """How far the arm's axis stays out of the chest (pixels from the body's box, sampled along the forearm): the
    straight arms of the model can't cross the torso, so a hold that needs them to is no hold."""
    pivot = np.array(PIVOTS['left_arm' if left else 'right_arm'], float)
    axis = qmat(q_arm) @ np.array([0.0, 1.0, 0.0])
    worst = 99.0
    for dist in (6.0, 7.0, 8.0):
        p = pivot + dist * axis
        out = np.maximum(np.abs(p - np.array([0.0, 6.0, 0.0])) - np.array([4.0, 6.0, 2.0]), 0.0)
        worst = min(worst, float(np.linalg.norm(out)) if np.any(out > 0) else -1.0)
    return worst

CLEAR = 0.3     # pixels the right forearm keeps from the chest

def lead(handle, toward):
    """The face direction for a handle that leads toward [toward]: that direction made square to the handle."""
    h = unit(handle)
    t = np.asarray(toward, float)
    return unit(t - np.dot(t, h) * h)

def vslerp(a, b, x):
    """Direction a turned toward direction b by the fraction x of the way round."""
    a, b = unit(a), unit(b)
    d = float(np.clip(np.dot(a, b), -1.0, 1.0))
    th = math.acos(d)
    if th < 1e-6:
        return a
    return unit(a * math.sin((1 - x) * th) + b * math.sin(x * th))

# Where the right arm likes to point, in the torso's frame: forward, down and across the chest, so the fists meet
# in front of the belly. The handle lies 80 degrees from the arm, so choosing the handle and where the arm points
# fixes how the hammer is rolled in the hands.
ARM = v(f=0.5, u=-0.65, r=-0.55)

class Path:
    """The hammer over time: keys of (tick, handle direction in the character's frame, where the right arm points
    in the torso's frame, ease). Roll about the handle follows from the arm."""
    def __init__(self):
        self.keys = []

    def key(self, t, handle, arm=None, ease='inout'):
        self.keys.append((float(t), unit(handle), unit(ARM if arm is None else arm), ease))
        self.keys.sort(key=lambda k: k[0])
        return self

    def at(self, t):
        ks = self.keys
        if t <= ks[0][0]:
            return ks[0][1], ks[0][2]
        for a, b in zip(ks, ks[1:]):
            if t <= b[0]:
                x = (t - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 1.0
                e = EASE[b[3]](x)
                return vslerp(a[1], b[1], e), unit(a[2] + (b[2] - a[2]) * e)
        return ks[-1][1], ks[-1][2]

# --- the pose solver

_Y = np.array([0.0, 1.0, 0.0])
_PR = np.array(PIVOTS['right_arm'], float)
_PL = np.array(PIVOTS['left_arm'], float)
_SECOND = G.tp_point(G.second)
_REACH = float(np.linalg.norm(fist(left=True)))
_CHEST = np.array([0.0, 6.0, 0.0])
_HALF = np.array([4.0, 6.0, 2.0])

def _basis(a, b):
    """frame_to's basis, for arrays of directions: columns a, b made square to a, and their cross product."""
    a = a / np.linalg.norm(a, axis=-1, keepdims=True)
    b = b - np.sum(b * a, axis=-1, keepdims=True) * a
    b = b / np.linalg.norm(b, axis=-1, keepdims=True)
    return np.stack([a, b, np.cross(a, b)], axis=-1)

_B0 = _basis(_Y, np.asarray(G.tp_blade, float))

def arm_matrices(Rt, h_w, e_w):
    """The right arm's turn (relative to the torso, one 3x3 per torso and per hammer roll) that holds the hammer with
    its handle along [h_w] and its face along [e_w] (world directions), the torso turned [Rt] (array of 3x3)."""
    h = np.einsum('tji,j->ti', Rt, h_w)
    e = np.einsum('tji,rj->tri', Rt, e_w)
    a = E * e + A * h[:, None, :]
    hb = np.broadcast_to(h[:, None, :], a.shape)
    return _basis(a, hb) @ _B0.T

def arm_scores(M):
    """Left hand's miss and the right forearm's clearance from the chest, for arm turns [M]."""
    d = _PR + np.einsum('...ij,j->...i', M, _SECOND) - _PL
    miss = np.abs(np.linalg.norm(d, axis=-1) - _REACH)
    axis = M[..., :, 1]
    worst = np.full(axis.shape[:-1], 99.0)
    for dist in (6.0, 7.0, 8.0):
        out = np.maximum(np.abs(_PR + dist * axis - _CHEST) - _HALF, 0.0)
        n = np.where(out.max(axis=-1) > 0, np.linalg.norm(out, axis=-1), -1.0)
        worst = np.minimum(worst, n)
    return miss, worst, axis

def prior_at(clip, t):
    """The torso the clip was drawn with, as (twist, lean, tilt) degrees at [t] (what the solver stays near)."""
    ks = clip.body_keys
    if t <= ks[0][0]:
        return np.array(ks[0][1:4], float)
    for a, b in zip(ks, ks[1:]):
        if t <= b[0]:
            x = (t - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 1.0
            e = EASE[b[4]](x)
            return np.array(a[1:4], float) + (np.array(b[1:4], float) - np.array(a[1:4], float)) * e
    return np.array(ks[-1][1:4], float)

_HULL = None

def hull_points():
    """The outline of the hammer's sprite (corners of its opaque pixels that lie on the convex hull) in the arm's frame,
    in model pixels: the points that can be the lowest."""
    global _HULL
    if _HULL is None:
        pts = sprite_points()[:, :2]
        key = sorted(set((round(float(x), 5), round(float(y), 5)) for x, y in pts))
        def cross(o, a, b):
            return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        lower, upper = [], []
        for pt in key:
            while len(lower) >= 2 and cross(lower[-2], lower[-1], pt) <= 0:
                lower.pop()
            lower.append(pt)
        for pt in reversed(key):
            while len(upper) >= 2 and cross(upper[-2], upper[-1], pt) <= 0:
                upper.pop()
            upper.append(pt)
        hull = lower[:-1] + upper[:-1]
        _HULL = np.array([G.tp_point((x, y, 0.5)) for x, y in hull])
    return _HULL

def swing_arms(clip, path, step=0.5, tolerance=1.4, keep=260, twists=(-40, -30, -20, -10, 0, 10, 20, 30, 40),
               leans=(-10, 0, 10, 20, 30), still=(), pull=1.0, sticky=1.0, contact=(), floor=24.3, stay=0.3):
    """Works out the shoulders and the right arm for the whole clip from the hammer's path and the hips (call after
    the root is keyed with body()), then closes the left hand on the haft. At every half tick the shoulders may turn
    a little from how the clip drew them and the hammer may roll in the hands; all of it is chosen together, as the
    smoothest run that keeps the left hand within reach of the second grip and the right forearm out of the chest,
    with the arms where the path likes them (so nothing flips). [still] is a list of (from, to) ticks where the
    shoulders stay as drawn; [contact] is a list of (from, to) ticks where the hammer rests on the ground (its lowest
    point is kept at ground level), and it never goes below [floor]. Returns the worst miss in pixels."""
    rolls = np.arange(-180.0, 180.0, 6.0)
    times = []
    t = 0.0
    while t <= clip.length + 1e-6:
        times.append(t)
        t += step
    layers = []
    for t in times:
        root_q = clip.sample('root', t)[0]
        Rr = qmat(root_q)
        prior = prior_at(clip, t)
        h_w, pref = path.at(t)
        face0 = lead(h_w, qmat(qmul(root_q, torso_q(*prior))) @ pref)
        e_w = np.stack([face0 * math.cos(math.radians(r)) + np.cross(h_w, face0) * math.sin(math.radians(r)) for r in rolls])
        locked = any(a <= t <= b for a, b in still)
        offs = [(0.0, 0.0)] if locked else [(dt, dl) for dt in twists for dl in leans]
        Rt = np.stack([Rr @ qmat(torso_q(prior[0] + dt, prior[1] + dl, prior[2])) for dt, dl in offs])
        M = arm_matrices(Rt, h_w, e_w)                       # (torsos, rolls, 3, 3)
        miss, clr, axis = arm_scores(M)
        up = np.arccos(np.clip(axis @ pref, -1, 1))
        dev = np.array([[(dt / 20.0) ** 2 + (dl / 10.0) ** 2] for dt, dl in offs])
        pr = clip.sample('root', t)[1]
        v = (_PR - HIPS) + np.einsum('trij,pj->trpi', M, hull_points())
        low = HIPS[1] + pr[1] + np.max(np.einsum('ti,trpi->trp', Rt[:, 1, :], v), axis=-1)
        cost = (8.0 * np.maximum(miss - tolerance, 0.0) ** 2 + 6.0 * np.maximum(CLEAR - clr, 0.0) ** 2
                + pull * up ** 2 + stay * dev + 0.00002 * rolls[None, :] ** 2
                + 4.0 * np.maximum(low - floor, 0.0) ** 2)
        if any(a <= t <= b for a, b in contact):
            cost = cost + 4.0 * (low - GROUND) ** 2
        flat = np.argsort(cost, axis=None)[:keep]
        ti, ri = np.unravel_index(flat, cost.shape)
        layers.append(dict(cost=cost[ti, ri], M=M[ti, ri], tw=np.array([prior[0] + offs[i][0] for i in ti]),
                           ln=np.array([prior[1] + offs[i][1] for i in ti]), tilt=prior[2], ti=ti, ri=ri,
                           miss=miss[ti, ri], offs=[offs[i] for i in ti], roll=rolls[ri], prior=prior))
    # the smoothest run through the layers: Viterbi
    total = layers[0]['cost'].copy()
    back = []
    for a, b in zip(layers, layers[1:]):
        tr = np.einsum('iab,jab->ij', a['M'], b['M'])
        ang = np.arccos(np.clip((tr - 1.0) / 2.0, -1, 1))
        trans = sticky * (ang / 0.25) ** 2
        trans += ((a['tw'][:, None] - b['tw'][None, :]) / 8.0) ** 2 + ((a['ln'][:, None] - b['ln'][None, :]) / 8.0) ** 2
        step_cost = total[:, None] + trans
        pick = np.argmin(step_cost, axis=0)
        back.append(pick)
        total = b['cost'] + step_cost[pick, np.arange(len(pick))]
    j = int(np.argmin(total))
    chosen = [0] * len(layers)
    for i in range(len(layers) - 1, -1, -1):
        chosen[i] = j
        if i > 0:
            j = int(back[i - 1][j])
    clip.keys.pop('torso', None)
    clip.roll_trace = []
    for i, (t, L) in enumerate(zip(times, layers)):
        j = chosen[i]
        clip.key('torso', t, torso_q(L['tw'][j], L['ln'][j], L['tilt']), ease='linear')
        clip.key('right_arm', t, qfrommat(L['M'][j]), ease='linear')
        clip.roll_trace.append((t, float(L['roll'][j]), float(L['miss'][j])))
    return hold_with_both(clip, G)

# ------------------------------------------------------------------ feet on the ground

GROUND = 24.0

def _leg_world(direction):
    """A leg's turn in the character's frame, hanging along [direction] with the toes kept pointing ahead."""
    return frame_to(np.array([0.0, 1.0, 0.0]), F, direction, F)

def _lowest(hip, direction):
    """Lowest point (largest y) of a leg's sole when the leg hangs from [hip] along [direction]."""
    m = qmat(_leg_world(direction))
    foot = hip + 12.0 * direction
    return max((foot + m @ np.array([sx * 2.0, 0.0, sz * 2.0]))[1] for sx in (-1, 1) for sz in (-1, 1))

def feet(clip, marks, step=0.5):
    """Keys both legs every half tick so the soles stand on the ground wherever the hips are: [marks] are
    (tick, (left x, z), (right x, z), ease[, left lift, right lift]) footprints in pixels (x positive to the
    character's left, z negative ahead; a lift raises that foot off the ground by so many pixels). The foot lands
    on the line from the hip toward its footprint, as far out as the leg reaches from where the hips are: sink and
    the feet spread, rise and they close."""
    marks = [tuple(m) + (0.0, 0.0) if len(m) == 4 else tuple(m) for m in sorted(marks, key=lambda m: m[0])]
    def spots(t):
        def pack(m):
            return np.array(m[1], float), np.array(m[2], float), float(m[4]), float(m[5])
        if t <= marks[0][0]:
            return pack(marks[0])
        for a, b in zip(marks, marks[1:]):
            if t <= b[0]:
                x = (t - a[0]) / (b[0] - a[0]) if b[0] > a[0] else 1.0
                e = EASE[b[3]](x)
                pa, pb = pack(a), pack(b)
                return tuple(pa[i] + (pb[i] - pa[i]) * e for i in range(4))
        return pack(marks[-1])
    t = 0.0
    while t <= clip.length + 1e-6:
        qr, pr = clip.sample('root', t)
        left_spot, right_spot, left_lift, right_lift = spots(t)
        for name, pivot, spot, lift in (('left_leg', PIVOTS['left_leg'], left_spot, left_lift), ('right_leg', PIVOTS['right_leg'], right_spot, right_lift)):
            hip = HIPS + qmat(qr) @ (np.array(pivot, float) - HIPS) + pr
            toward = np.array([spot[0] - hip[0], 0.0, spot[1] - hip[2]])
            run = np.linalg.norm(toward)
            toward = toward / run if run > 1e-6 else np.array([0.0, 0.0, -1.0])
            def direction(tilt):
                return np.array([toward[0] * math.sin(tilt), math.cos(tilt), toward[2] * math.sin(tilt)])
            ground = GROUND - lift
            if _lowest(hip, direction(0.0)) <= ground:
                tilt = 0.0     # hips high: the leg hangs straight and the foot is off the ground
            else:
                lo, hi = 0.0, math.radians(85.0)
                for _ in range(30):
                    mid = (lo + hi) / 2
                    if _lowest(hip, direction(mid)) > ground:
                        lo = mid
                    else:
                        hi = mid
                tilt = hi
            q = qmul(qinv(qr), _leg_world(direction(tilt)))
            clip.key(name, t, q, ease='linear')
        t += step

def head_on_target(clip, share=0.85, step=0.5):
    """The head keeps looking where the player looks while the hips turn under it: a nod (added to the look)
    against the hips' yaw."""
    clip.mode('head', 'add')
    t = 0.0
    while t <= clip.length + 1e-6:
        qr, _ = clip.sample('root', t)
        yaw = math.degrees(math.atan2(2 * (qr[3] * qr[1] + qr[0] * qr[2]), 1 - 2 * (qr[1] ** 2 + qr[2] ** 2)))
        clip.key('head', t, qeuler(y=-yaw * share), ease='linear')
        t += step

# ------------------------------------------------------------------ first person

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    off, q = fp(hand, blade, edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

HEAD = float(np.linalg.norm(apply(G.fp_display(), G.tip) - G.fp_grip))     # grip to the head's centre, in blocks

def _carry(b):
    """The rest orientation's edge carried round with the handle by the smallest turn (the sprite not rolled)."""
    b0, e0 = G.fp_blade, G.fp_edge
    axis = np.cross(b0, b)
    sn = float(np.linalg.norm(axis))
    cs = float(np.dot(b0, b))
    if sn < 1e-9:
        return e0
    k = axis / sn
    ang = math.atan2(sn, cs)
    return e0 * math.cos(ang) + np.cross(k, e0) * math.sin(ang) + k * float(np.dot(k, e0)) * (1 - math.cos(ang))

def _toward_eye(b, head):
    """The edge direction that turns the sprite's visible face square to the eye (None when the handle points along the
    line of sight and no turn would show it)."""
    view = unit(-(np.asarray(head, float) - 0.3 * b))
    n = view - float(np.dot(view, b)) * b
    ln = float(np.linalg.norm(n))
    if ln < 0.05:
        return None
    return np.cross(n / ln, b)

def _spin(e, b, ang):
    return e * math.cos(ang) + np.cross(b, e) * math.sin(ang)

def hn(t, hand, blade, ease='inout', face=1.0):
    """A first-person key given by where the hands (the grip) are."""
    return (t, hand, blade, ease, face, 'hand')

def fp_clip(name, length, blend_in, blend_out, keys, step=0.5):
    """A first-person clip from the item at rest, along [keys] and back to rest. A key is (tick, where the hammer's head
    is in the view (camera space, blocks: x right, y up, -z ahead), the way the handle points from the hands to the head,
    ease, how far to roll the sprite from its resting roll to face the eye, 0..1), or hn(...) with where the hands
    are instead of the head. Worked out every half tick: the head, or the hands, carried along, the handle turned
    round, the sprite rolled about the handle so its flat side faces the eye, never turning edge-on or flipping over
    between two keys."""
    f = Clip(name, length, blend_in=blend_in, blend_out=blend_out)
    def entry(k):
        mode = k[5] if len(k) > 5 else 'head'
        pos, blade = np.asarray(k[1], float), unit(np.asarray(k[2], float))
        hand = pos if mode == 'hand' else pos - HEAD * blade
        return dict(t=float(k[0]), hand=hand, head=hand + HEAD * blade, blade=blade, ease=k[3] if len(k) > 3 else 'inout',
                    face=k[4] if len(k) > 4 else 1.0, mode=mode)
    rest = dict(t=0.0, hand=fp_rest(G), head=fp_rest(G) + HEAD * G.fp_blade, blade=G.fp_blade, ease='inout', face=0.0,
                mode='hand')
    ks = [rest] + [entry(k) for k in sorted(keys, key=lambda k: k[0])] + [dict(rest, t=float(length))]
    samples = []
    t = 0.0
    while t <= length + 1e-6:
        for a, b in zip(ks, ks[1:]):
            if t <= b['t'] + 1e-9:
                x = (t - a['t']) / (b['t'] - a['t']) if b['t'] > a['t'] else 1.0
                x = EASE[b['ease']](x)
                blade = vslerp(a['blade'], b['blade'], x)
                if b['mode'] == 'hand':
                    hand = a['hand'] + (b['hand'] - a['hand']) * x
                    head = hand + HEAD * blade
                else:
                    head = a['head'] + (b['head'] - a['head']) * x
                    hand = head - HEAD * blade
                face = a['face'] + (b['face'] - a['face']) * x
                break
        ref = _carry(blade)
        cam = _toward_eye(blade, head)
        psi = None if cam is None else math.atan2(float(np.dot(np.cross(blade, ref), cam)), float(np.dot(ref, cam)))
        samples.append((t, blade, hand, ref, face, psi))
        t += step
    # The roll that turns the sprite to face the eye, followed through time without spare turns about the handle and
    # without snapping where the handle points nearly along the line of sight (there the facing roll swings wildly
    # while the sprite is edge-on anyway): in that stretch it may turn only so far per half tick. The last ramp back
    # to the resting roll starts from the representative nearest zero, so it never rolls a spare turn.
    roll, prev_cam, prev, prev_cond = [], None, None, None
    for n, (t, blade, hand, ref, face, psi) in enumerate(samples):
        view = unit(-(hand + HEAD * blade - 0.3 * blade))
        cond = float(np.linalg.norm(view - float(np.dot(view, blade)) * blade))      # sin of the angle to the line of sight
        if psi is None:
            psi = prev_cam if prev_cam is not None else 0.0
        elif prev_cam is not None:
            psi += 2 * math.pi * round((prev_cam - psi) / (2 * math.pi))
        prev_cam = psi
        steady = cond if prev_cond is None else min(cond, prev_cond)
        prev_cond = cond
        rate = math.radians(40.0 + 320.0 * min(1.0, max(0.0, (steady - 0.2) / 0.25)))
        prev = psi if prev is None else prev + max(-rate, min(rate, psi - prev))
        roll.append(prev)
    last = max(n for n, smp in enumerate(samples) if smp[4] >= 1.0 - 1e-9)
    shift = 2 * math.pi * round(roll[last] / (2 * math.pi))
    for n in range(last, len(roll)):
        roll[n] -= shift
    for n, (t, blade, hand, ref, face, psi) in enumerate(samples):
        fk(f, t, hand, blade, _spin(ref, blade, face * roll[n]), ease='linear')
    fps.append(f)
    return f

# The overhead slam seen from the player's eyes, shared by the heavy slam, the jump and the Quake: the hammer held up
# (the head at the top of the view), tipped out to the right and down so the handle never points along the line of
# sight (the flat sprite would vanish), the crash with the head at the bottom of the view, and the swing back up the
# right side, like a clock hand, to the ready position.
UP = ((0.34, 0.25, -0.62), vv(u=0.97, f=-0.2, r=0.05))

def slam_keys(h, hold, tail=True):
    keys = [
        (h - 3.0, (0.34, 0.28, -0.7), vv(u=0.97, f=0.05, r=0.15), 'in'),
        (h - 2.0, (0.62, 0.15, -0.9), vv(u=0.6, f=0.35, r=0.7), 'in'),
        (h - 1.0, (0.55, -0.25, -1.1), vv(u=0.15, f=0.65, r=0.75), 'linear'),
        (h, (0.12, -0.6, -1.2), vv(f=0.55, u=-0.7, r=0.05), 'linear'),
        (h + hold, (0.12, -0.58, -1.2), vv(f=0.55, u=-0.68, r=0.05), 'out'),
        (h + hold + 2.0, (0.35, -0.45, -1.0), vv(f=0.35, u=-0.7, r=0.5)),
        (h + hold + 4.0, (0.8, -0.1, -0.85), vv(f=0.3, u=-0.2, r=0.9)),
        (h + hold + 6.0, (0.85, 0.2, -0.75), vv(f=0.2, u=0.6, r=0.7)),
    ]
    if tail:
        keys.append((h + hold + 9.0, (0.6, 0.2, -0.6), vv(u=0.9, f=-0.1, r=0.2)))
    return keys

def tp(name, length, **kw):
    return Clip(name, length, **kw)

def torso_q(twist=0.0, lean=0.0, tilt=0.0):
    """A torso turn: [twist] degrees of yaw (positive to the right), [lean] forward (negative back), [tilt] to the
    character's left (negative right), in the order a body does them: bend, then turn."""
    return qmul(qeuler(y=twist), qmul(qeuler(x=lean), qeuler(z=tilt)))

def body(clip, t, hips=0.0, twist=0.0, lean=0.0, tilt=0.0, pos=(0.0, 0.0, 0.0), ease='inout'):
    """The hips and the shoulders at one moment: [hips] yaw the whole body about the hips, [twist] is the
    shoulders' turn on top of it, [pos] moves the hips (x left, y down, z back). The root is keyed as given; the
    shoulders are only a first draft that swing_arms improves on."""
    clip.key('root', t, qeuler(y=hips), pos=pos, ease=ease)
    clip.key('torso', t, torso_q(twist, lean, tilt), ease=ease)
    if not hasattr(clip, 'body_keys'):
        clip.body_keys = []
    clip.body_keys.append((float(t), twist, lean, tilt, ease))
    clip.body_keys.sort(key=lambda k: k[0])
    return clip

# stance footprints: (left x, z), (right x, z) in pixels; the left foot leads
STANCE = ((5.4, -3.0), (-6.0, 2.6))
STAND = ((1.9, 0.0), (-1.9, 0.0))

MISS = {}

# --- where the hammer is: the head of the war hammer against the ground

_SPRITE = None

def sprite_points():
    """Corners of the opaque pixels of the war hammer's sprite, in item space (0..1, y up)."""
    global _SPRITE
    if _SPRITE is None:
        here = os.path.dirname(os.path.abspath(__file__))
        path = os.path.join(here, '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark', 'textures', 'item', 'iron_war_hammer.png')
        pts = []
        try:
            im = np.array(Image.open(path).convert('RGBA'))
            n = im.shape[0]
            for py in range(n):
                for px in range(im.shape[1]):
                    if im[py, px, 3] > 100:
                        for dx in (0, 1):
                            for dy in (0, 1):
                                pts.append(((px + dx) / n, 1 - (py + dy) / n, 0.5))
        except OSError:
            pass
        if not pts:     # the head block, near enough
            for s_ in np.linspace(0.45, 0.95, 12):
                for w_ in np.linspace(-0.12, 0.12, 6):
                    pts.append((s_ / math.sqrt(2) - w_ / math.sqrt(2), s_ / math.sqrt(2) + w_ / math.sqrt(2), 0.5))
        _SPRITE = np.array(pts)
    return _SPRITE

def hammer_world(clip, t):
    """Every sprite point of the hammer in the model's space at [t], and where the grip and the head are."""
    pose = pose_clip(clip, t)
    qr, pr = pose['root']
    q, p = pose['right_arm']
    M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q) @ S(16) @ G.tp_item()
    pts = np.array([apply(M, x) for x in sprite_points()])
    return pts, apply(M, G.grip), apply(M, G.tip)

def settle(clip, t, ticks, ground=GROUND):
    """Lowers or raises the hips at [ticks] (root keys, added where there are none) by as much as puts the hammer's
    lowest point on the ground at [t]. Returns where the lowest point then lies (y, z) and the head's z."""
    pts, grip, head = hammer_world(clip, t)
    delta = ground - float(pts[:, 1].max())
    for tk in ticks:
        if not any(abs(k[0] - tk) < 1e-6 for k in clip.keys['root']):
            q, pos = clip.sample('root', tk)
            clip.key('root', tk, q, pos=pos, ease='linear')
    keys = clip.keys['root']
    for i, (kt, q, pos, ease) in enumerate(keys):
        if any(abs(kt - tk) < 1e-6 for tk in ticks):
            pos = np.zeros(3) if pos is None else pos.copy()
            pos[1] += delta
            keys[i] = (kt, q, pos, ease)
    pts, grip, head = hammer_world(clip, t)
    low = pts[np.argmax(pts[:, 1])]
    return float(low[1]), float(low[2]), float(head[2])

def finish(c, path, plan=None, legs=None, solve=None, ground=None):
    """The shoulders and arms follow the hammer, the left hand closes on the haft, the feet find the ground, the head
    stays on the target. [ground] is a list of (tick, [root keys to move]) for a blow that lands the hammer on the
    ground: at each tick the hips go as low as puts the hammer's lowest point on the ground."""
    MISS[c.name] = swing_arms(c, path, **(solve or {}))
    c.ground = [settle(c, tk, keys) for tk, keys in (ground or [])]
    if legs:
        legs(c)
    else:
        feet(c, plan)
    head_on_target(c)
    clips.append(c)

READY = hz(8, 18)
ARM_LOW = v(f=0.3, u=-0.85, r=-0.4)      # hands at the belly
ARM_UP = v(f=0.55, u=0.65, r=-0.4)       # arms raised
ARM_DOWN = v(f=0.55, u=-0.75, r=-0.3)    # hands out in front of the thighs

# ------------------------------------------------------------------ light 1: a heavy horizontal swing, right to left
c = tp('hammer/light_1', 21, blend_in=2.0, blend_out=5.0).mark('hit', 9.5).mark('chain', 14)
body(c, 0, hips=10, twist=18, lean=-1, pos=(-0.3, 0.6, 0.2))
body(c, 2.0, hips=12, twist=22, lean=-2, pos=(-0.5, 0.8, 0.3), ease='out')
body(c, 4.5, hips=16, twist=30, lean=-2, pos=(-0.8, 1.0, 0.5))
body(c, 7.0, hips=22, twist=42, lean=-4, tilt=2, pos=(-1.4, 1.6, 0.8))
body(c, 8.0, hips=6, twist=44, lean=0, pos=(-1.0, 1.7, 0.2), ease='in')
body(c, 9.0, hips=-12, twist=34, lean=6, pos=(-0.2, 1.8, -0.8), ease='linear')
body(c, 10.0, hips=-28, twist=14, lean=11, tilt=-2, pos=(0.7, 1.8, -1.6), ease='linear')
body(c, 11.0, hips=-38, twist=-14, lean=12, tilt=-3, pos=(1.1, 1.6, -2.0), ease='linear')
body(c, 12.5, hips=-42, twist=-38, lean=10, tilt=-3, pos=(1.2, 1.4, -2.0), ease='out')
body(c, 16.0, hips=-18, twist=-14, lean=4, pos=(0.6, 0.8, -0.8))
body(c, 21.0)
path = Path()
path.key(0, hz(70, 18))
path.key(2.0, hz(80, 22), ease='out')
path.key(4.5, hz(108, 20))
path.key(7.0, hz(135, 12), ARM_LOW)
path.key(8.0, hz(92, 10), ease='in')
path.key(9.0, hz(38, 6), ease='linear')
path.key(10.0, hz(-35, 2), ease='linear')
path.key(11.0, hz(-85, -4), ease='linear')
path.key(12.0, hz(-112, -10), ease='out')
path.key(14.0, hz(-100, -18), ARM_LOW, ease='inout')
path.key(21.0, READY, ease='inout')
finish(c, path, [(0, *STANCE, 'inout'), (16, *STANCE, 'inout'), (21, *STAND, 'inout')])

# The hands stay low (the handle rises out of the bottom of the view) and the head carries the swing across it.
fp_clip('hammer/light_1', 21, 2.0, 5.0, [
    hn(2.0, (0.37, -0.57, -0.7), (0.55, 0.83, -0.11), 'out'),
    hn(4.5, (0.39, -0.45, -0.81), (0.65, 0.75, -0.11)),
    hn(7.0, (0.4, -0.4, -0.85), (0.75, 0.63, -0.1)),
    hn(8.0, (0.4, -0.45, -0.8), (0.65, 0.45, -0.6), 'in'),
    hn(9.0, (0.2, -0.6, -0.75), (0.25, 0.8, -0.55), 'linear'),
    hn(10.0, (-0.1, -0.6, -0.75), (-0.4, 0.75, -0.53), 'linear'),
    hn(11.0, (-0.3, -0.6, -0.75), (-0.75, 0.55, -0.37), 'linear'),
    hn(12.5, (-0.3, -0.55, -0.78), (-0.8, 0.52, -0.3), 'out'),
    hn(16.0, (-0.2, -0.5, -0.78), (-0.75, 0.58, -0.3)),
])

# ------------------------------------------------------------------ light 2: the rising backhand, low left to high right
c = tp('hammer/light_2', 22, blend_in=2.0, blend_out=5.0).mark('hit', 9.5).mark('chain', 15)
body(c, 0, hips=-30, twist=-22, lean=6, tilt=-1, pos=(1.0, 1.6, -1.2))
body(c, 3.0, hips=-36, twist=-30, lean=9, pos=(1.4, 1.9, -0.8), ease='out')
body(c, 5.0, hips=-36, twist=-36, lean=11, pos=(1.6, 2.3, -0.4), ease='inout')
body(c, 7.0, hips=-34, twist=-40, lean=12, tilt=-3, pos=(1.7, 2.5, -0.2), ease='inout')
body(c, 8.0, hips=-18, twist=-42, lean=10, pos=(1.2, 2.3, -0.6), ease='in')
body(c, 9.0, hips=4, twist=-38, lean=6, pos=(0.4, 1.9, -1.0), ease='linear')
body(c, 10.0, hips=28, twist=-22, lean=0, tilt=2, pos=(-0.6, 1.2, -1.4), ease='linear')
body(c, 11.0, hips=40, twist=6, lean=-4, tilt=3, pos=(-1.2, 0.5, -1.4), ease='linear')
body(c, 12.5, hips=44, twist=26, lean=-6, tilt=4, pos=(-1.4, 0.1, -1.0), ease='out')
body(c, 17.0, hips=22, twist=14, lean=-2, pos=(-0.9, 0.6, -0.4))
body(c, 22.0)
path = Path()
path.key(0, hz(-100, -22), ARM_LOW)
path.key(3.0, hz(-118, -28), ARM_LOW, ease='out')
path.key(7.0, hz(-130, -35), ARM_LOW, ease='inout')
path.key(8.0, hz(-100, -25), ease='in')
path.key(9.0, hz(-40, -6), ease='linear')
path.key(10.0, hz(40, 14), ease='linear')
path.key(11.0, hz(85, 38), ease='linear')
path.key(12.0, hz(110, 55), ease='out')
path.key(15.0, hz(110, 25), ARM_LOW, ease='inout')
path.key(22.0, READY, ease='inout')
finish(c, path, [(0, *STANCE, 'inout'), (17, *STANCE, 'inout'), (22, *STAND, 'inout')])

fp_clip('hammer/light_2', 22, 2.0, 5.0, [
    hn(1.0, (0.1, -0.6, -0.78), (-0.65, 0.7, -0.2), 'out'),
    hn(3.0, (-0.4, -0.6, -0.78), (-0.8, 0.2, -0.56), 'out'),
    hn(7.0, (-0.45, -0.6, -0.78), (-0.82, 0.15, -0.55)),
    hn(8.0, (-0.4, -0.6, -0.75), (-0.6, 0.5, -0.62), 'in'),
    hn(9.0, (-0.15, -0.6, -0.75), (-0.3, 0.7, -0.65), 'linear'),
    hn(10.0, (0.2, -0.6, -0.75), (0.3, 0.75, -0.59), 'linear'),
    hn(11.0, (0.4, -0.6, -0.75), (0.7, 0.6, -0.38), 'linear'),
    hn(12.5, (0.45, -0.58, -0.78), (0.55, 0.75, -0.35), 'out'),
    hn(17.0, (0.5, -0.6, -0.8), (0.35, 0.92, 0.1)),
])

# ------------------------------------------------------------------ heavy: a held overhead slam, the crash at 13
# The hold pose at 8 is where the game freezes the clip while the button stays down.
c = tp('hammer/heavy', 30, blend_in=2.0, blend_out=6.0).mark('hold', 8).mark('crash', 13).mark('chain', 22)
body(c, 0)
body(c, 3.0, lean=-6, pos=(0, 0.3, 0.8), ease='out')
body(c, 5.5, lean=-16, pos=(0, 0.5, 1.6))
body(c, 8.0, lean=-30, pos=(0, 0.7, 2.6))
body(c, 9.0, lean=-27, pos=(0, 0.9, 2.2), ease='in')
body(c, 10.0, lean=-16, pos=(0, 1.4, 1.2), ease='in')
body(c, 11.0, lean=0, pos=(0, 2.0, 0.0), ease='in')
body(c, 12.0, lean=16, pos=(0, 2.7, -1.0), ease='in')
body(c, 13.0, lean=24, pos=(0, 2.9, -1.8), ease='in')
body(c, 15.0, lean=25, pos=(0, 3.0, -1.9), ease='out')
body(c, 18.0, lean=18, pos=(0, 2.4, -1.2))
body(c, 21.0, lean=-4, pos=(0, 1.2, -0.4))
body(c, 24.0, lean=2, pos=(0, 0.6, 0))
body(c, 30.0)
path = Path()
path.key(0, READY)
path.key(3.0, vp(40), ease='out')
path.key(5.5, vp(88), ARM_UP)
path.key(8.0, vp(108), ARM_UP)
path.key(9.0, vp(105), ARM_UP, ease='in')
path.key(10.0, vp(96), ARM_UP, ease='in')
path.key(11.0, vp(74), ease='in')
path.key(12.0, vp(30), ARM_DOWN, ease='in')
path.key(13.0, vp(-32), ARM_DOWN, ease='in')
path.key(15.0, vp(-38), ARM_DOWN, ease='out')
path.key(18.0, vp(-10))
path.key(22.0, vp(28))
path.key(30.0, READY)
finish(c, path, [(0, *STAND, 'inout'), (3, (4.5, -4.0), (-5.0, 3.0), 'out'),
                 (8, (4.6, -9.0), (-4.4, 2.0), 'inout', 2.0, 0.0), (11, (5.0, -6.0), (-5.2, 3.5), 'in'),
                 (13, (5.0, -5.5), (-5.5, 4.0), 'in'), (18, (5.0, -5.5), (-5.5, 4.0), 'inout'),
                 (24, (4.5, -3.5), (-5.0, 3.0), 'inout'), (30, *STAND, 'inout')],
       solve=dict(contact=[(13.0, 16.0)], stay=1.5), ground=[(13.0, [13.0]), (14.0, [14.0]), (15.0, [15.0]), (16.0, [16.0])])

fp_clip('hammer/heavy', 30, 2.0, 6.0, [
    (3.0, (0.54, 0.28, -0.81), vv(u=0.9, f=0.1, r=0.1), 'out'),
    (5.5, (0.39, 0.28, -0.64), vv(u=0.96, f=-0.15, r=0.05)),
    (8.0, *UP),
    (9.0, *UP, 'linear'),
] + slam_keys(13.0, 2.0))

# ------------------------------------------------------------------ running: a big swing carried by the run, from the right
c = tp('hammer/running', 20, blend_in=2.0, blend_out=5.0).mark('hit', 8).mark('chain', 15)
body(c, 0, hips=22, twist=26, lean=10, pos=(-0.6, 0.6, 0.0))
body(c, 2.0, hips=26, twist=34, lean=12, pos=(-0.9, 0.5, -0.6), ease='out')
body(c, 5.0, hips=30, twist=42, lean=10, pos=(-1.2, 1.4, -0.8))
body(c, 6.5, hips=12, twist=40, lean=12, pos=(-0.4, 1.8, -1.4), ease='in')
body(c, 7.0, hips=-4, twist=34, lean=14, pos=(0.2, 2.0, -2.0), ease='linear')
body(c, 8.0, hips=-26, twist=14, lean=16, pos=(0.8, 2.1, -2.8), ease='linear')
body(c, 9.0, hips=-42, twist=-10, lean=16, pos=(1.2, 2.0, -3.2), ease='linear')
body(c, 10.5, hips=-50, twist=-34, lean=12, pos=(1.4, 1.6, -3.0), ease='out')
body(c, 14.0, hips=-30, twist=-20, lean=8, pos=(1.0, 1.0, -2.0))
body(c, 20.0)
path = Path()
path.key(0, hz(110, 14))
path.key(2.0, hz(125, 14), ease='out')
path.key(5.0, hz(142, 10), ARM_LOW)
path.key(6.5, hz(125, 8), ease='in')
path.key(7.0, hz(85, 6), ease='linear')
path.key(8.0, hz(22, 2), ease='linear')
path.key(9.0, hz(-48, -2), ease='linear')
path.key(10.5, hz(-95, -6), ease='out')
path.key(12.0, hz(-108, -12), ease='out')
path.key(15.0, hz(-95, -14), ARM_LOW)
path.key(20.0, READY)
finish(c, path, [(0, (4.5, -1.0), (-4.5, 3.5), 'inout', 0.0, 2.5), (2, (5.0, -4.0), (-5.0, 4.5), 'out', 0.0, 1.5),
                 (5, (5.4, -7.5), (-5.6, 3.0), 'inout'), (14, (5.4, -7.5), (-5.6, 3.0), 'inout'), (20, *STAND, 'inout')])

fp_clip('hammer/running', 20, 2.0, 5.0, [
    hn(2.0, (0.37, -0.57, -0.7), (0.55, 0.83, -0.11), 'out'),
    hn(5.0, (0.4, -0.4, -0.85), (0.75, 0.63, -0.1)),
    hn(6.5, (0.4, -0.45, -0.8), (0.65, 0.45, -0.6), 'in'),
    hn(7.0, (0.3, -0.55, -0.78), (0.5, 0.62, -0.6), 'linear'),
    hn(8.0, (0.05, -0.6, -0.75), (0.1, 0.8, -0.59), 'linear'),
    hn(9.0, (-0.2, -0.6, -0.75), (-0.6, 0.65, -0.46), 'linear'),
    hn(10.5, (-0.3, -0.55, -0.78), (-0.8, 0.52, -0.3), 'out'),
    hn(15.0, (-0.2, -0.5, -0.78), (-0.75, 0.58, -0.3)),
])

# ------------------------------------------------------------------ jump: leaps with the hammer overhead, lands it on the ground at 8
c = tp('hammer/jump', 21, blend_in=2.0, blend_out=5.0).mark('raised', 4).mark('slam', 8).mark('chain', 16)
body(c, 0, lean=-4, pos=(0, -1.0, 0.4))
body(c, 2.0, lean=-10, pos=(0, -2.2, 0.8), ease='out')
body(c, 4.0, lean=-18, pos=(0, -3.0, 1.0))
body(c, 5.0, lean=-16, pos=(0, -2.6, 0.8))
body(c, 6.0, lean=-4, pos=(0, -0.6, 0.0), ease='in')
body(c, 7.0, lean=14, pos=(0, 2.0, -1.0), ease='in')
body(c, 8.0, lean=24, pos=(0, 2.9, -1.8), ease='in')
body(c, 11.0, lean=25, pos=(0, 3.0, -1.9), ease='out')
body(c, 14.0, lean=18, pos=(0, 2.0, -1.2))
body(c, 17.0, lean=6, pos=(0, 1.0, -0.4))
body(c, 21.0)
path = Path()
path.key(0, vp(40))
path.key(2.0, vp(80), ARM_UP, ease='out')
path.key(4.0, vp(104), ARM_UP)
path.key(5.0, vp(106), ARM_UP)
path.key(6.0, vp(90), ARM_UP, ease='in')
path.key(7.0, vp(48), ARM_DOWN, ease='in')
path.key(8.0, vp(-32), ARM_DOWN, ease='in')
path.key(11.0, vp(-38), ARM_DOWN, ease='out')
path.key(14.0, vp(-18))
path.key(17.0, vp(12))
path.key(21.0, READY)
finish(c, path, [(0, (3.0, -6.0), (-3.0, -3.0), 'inout', 5.0, 6.0), (4, (3.0, -8.0), (-3.0, -4.0), 'inout', 7.0, 7.0),
                 (6, (4.0, -8.0), (-4.0, -1.0), 'in', 4.0, 5.0), (7.5, (5.0, -6.0), (-5.5, 3.5), 'in'),
                 (11, (5.0, -5.5), (-5.5, 4.0), 'inout'), (17, (4.0, -3.5), (-4.5, 3.0), 'inout'), (21, *STAND, 'inout')],
       solve=dict(contact=[(8.0, 11.0)], stay=1.5), ground=[(8.0, [8.0]), (9.0, [9.0]), (10.0, [10.0]), (11.0, [11.0])])

fp_clip('hammer/jump', 21, 2.0, 5.0, [
    (2.0, (0.54, 0.28, -0.81), vv(u=0.9, f=0.1, r=0.1), 'out'),
    (4.0, *UP),
] + slam_keys(8.0, 3.0, tail=False))

# ------------------------------------------------------------------ guard counter: an uppercut from low by the right foot
c = tp('hammer/guard_counter', 24, blend_in=2.0, blend_out=5.0).mark('hit', 10.5).mark('chain', 17)
body(c, 0, hips=6, twist=10, lean=4, pos=(-0.2, 1.2, 0.4))
body(c, 3.0, hips=12, twist=24, lean=8, pos=(-0.6, 1.9, 0.6), ease='out')
body(c, 6.0, hips=18, twist=30, lean=10, pos=(-1.0, 2.2, 0.8))
body(c, 8.0, hips=14, twist=28, lean=12, pos=(-0.8, 2.3, 0.2))
body(c, 9.0, hips=0, twist=14, lean=14, pos=(-0.2, 2.2, -1.0), ease='in')
body(c, 10.0, hips=-16, twist=-4, lean=8, pos=(0.4, 1.8, -1.8), ease='linear')
body(c, 11.0, hips=-24, twist=-12, lean=2, pos=(0.6, 1.2, -2.2), ease='linear')
body(c, 12.5, hips=-26, twist=-16, lean=-6, pos=(0.7, 0.6, -2.2), ease='out')
body(c, 17.0, hips=-10, twist=-6, lean=0, pos=(0.3, 0.8, -1.0))
body(c, 24.0)
path = Path()
path.key(0, hz(35, -25), ARM_LOW)
path.key(3.0, hz(50, -40), ARM_LOW, ease='out')
path.key(6.0, hz(62, -45), ARM_LOW)
path.key(8.0, hz(60, -44), ARM_LOW)
path.key(9.0, hz(25, -32), ease='in')
path.key(10.0, vp(5, 2), ease='linear')
path.key(11.0, vp(55, -4), ease='linear')
path.key(12.5, vp(100, -6), ease='out')
path.key(14.0, vp(104, -4))
path.key(17.0, vp(60, 0))
path.key(24.0, READY)
finish(c, path, [(0, *STANCE, 'inout'), (17, *STANCE, 'inout'), (24, *STAND, 'inout')])

# the hammer is held low at the right hip (the hands at the bottom of the view, the head out ahead of them toward the
# middle), then the head comes up and across through the middle at 10-11
fp_clip('hammer/guard_counter', 24, 2.0, 5.0, [
    hn(3.0, (0.6, -0.45, -0.75), (-0.7, -0.2, -0.68), 'out'),
    hn(6.0, (0.62, -0.5, -0.75), (-0.7, -0.22, -0.67)),
    hn(8.0, (0.62, -0.5, -0.75), (-0.7, -0.22, -0.67)),
    hn(9.0, (0.45, -0.5, -0.73), (-0.8, 0.2, -0.57), 'in'),
    hn(10.0, (0.2, -0.6, -0.7), (0.1, 0.7, -0.7), 'linear'),
    hn(11.0, (0.15, -0.55, -0.7), (-0.05, 0.88, -0.47), 'linear'),
    hn(12.5, (0.3, -0.5, -0.7), (0.05, 0.95, -0.3), 'out'),
    hn(17.0, (0.45, -0.55, -0.72), (0.1, 0.95, 0.0)),
])

# ------------------------------------------------------------------ skill: the Quake, a long rise and a ground-splitting slam at 16
c = tp('hammer/skill', 32, blend_in=2.0, blend_out=6.0).mark('raised', 12).mark('quake', 16).mark('chain', 25)
body(c, 0)
body(c, 4.0, lean=-8, pos=(0, -0.4, 0.8))
body(c, 8.0, lean=-20, pos=(0, -0.9, 1.8))
body(c, 12.0, lean=-30, pos=(0, -1.2, 2.6))
body(c, 13.0, lean=-32, pos=(0, -1.3, 2.9), ease='linear')
body(c, 14.0, lean=-24, pos=(0, -0.2, 2.0), ease='in')
body(c, 15.0, lean=-2, pos=(0, 1.8, 0.4), ease='in')
body(c, 16.0, lean=28, pos=(0, 3.4, -2.2), ease='in')
body(c, 18.0, lean=28, pos=(0, 3.6, -2.1), ease='out')
body(c, 22.0, lean=20, pos=(0, 2.8, -1.5))
body(c, 27.0, lean=0, pos=(0, 1.0, -0.4))
body(c, 32.0)
path = Path()
path.key(0, READY)
path.key(4.0, vp(45), ease='out')
path.key(8.0, vp(85), ARM_UP)
path.key(12.0, vp(108), ARM_UP)
path.key(13.0, vp(110), ARM_UP, ease='linear')
path.key(14.0, vp(98), ARM_UP, ease='in')
path.key(15.0, vp(52), ARM_DOWN, ease='in')
path.key(16.0, vp(-34), ARM_DOWN, ease='in')
path.key(18.0, vp(-40), ARM_DOWN, ease='out')
path.key(22.0, vp(-20))
path.key(27.0, vp(20))
path.key(32.0, READY)
finish(c, path, [(0, *STAND, 'inout'), (8, (4.6, -6.0), (-4.8, 2.0), 'inout'), (14, (5.0, -6.5), (-5.2, 3.0), 'inout'),
                 (16, (5.2, -6.5), (-5.6, 3.5), 'in'), (22, (5.0, -6.0), (-5.4, 3.5), 'inout'), (32, *STAND, 'inout')],
       solve=dict(contact=[(16.0, 18.0)], stay=1.0), ground=[(16.0, [16.0]), (17.0, [17.0]), (18.0, [18.0])])

fp_clip('hammer/skill', 32, 2.0, 6.0, [
    (4.0, (0.58, 0.25, -0.9), vv(u=0.8, f=0.2, r=0.1), 'out'),
    (8.0, (0.4, 0.27, -0.66), vv(u=0.92, f=-0.1, r=0.05)),
    (12.0, *UP),
] + slam_keys(16.0, 2.0))

# ------------------------------------------------------------------ output

if __name__ == '__main__':
    assets, previews, skin, weapon = sys.argv[1:5]
    os.makedirs(previews, exist_ok=True)
    scene = Scene(Image.open(skin), Image.open(weapon), geo=G)
    by_name = {f.name: f for f in fps}
    for c in clips:
        print(c.write(assets, 'player'), 'hold_with_both miss %.2f px' % MISS[c.name])
    for f in fps:
        print(f.write(assets, 'first_person'))
    only = sys.argv[5:] or [c.name for c in clips]
    for c in clips:
        if c.name not in only and c.name.split('/')[-1] not in only:
            continue
        sheet(scene, c, by_name.get(c.name)).save(os.path.join(previews, c.name.split('/')[-1] + '.png'))
