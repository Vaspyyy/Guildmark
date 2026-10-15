"""
The greatsword's combat moves: slow two-handed cleaves swung with the whole body. Third-person clips pose the
skeleton, first-person clips move the held item. Run:

    python3 -B art/scripts/anims_greatsword.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_greatsword.png>

Timings match Move.kt: a clip lasts as many ticks as its move, and its blow lands inside the move's hit window.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import GREATSWORD as G

clips, fps = [], []

# ------------------------------------------------------------------ both hands on the hilt
#
# The off hand closes on the hilt 2.7 px below the main hand, and the arms are rigid, so a hilt held by both can only
# sit where both shoulders reach: on the circle in front of the chest where the two reach spheres meet. That leaves one
# roll of the main arm for each direction the blade points (relative to the torso), so the clips are written in blade
# directions and the arm that gets the blade there with both hands on the hilt is solved for.

_PSI = np.radians(np.arange(0.0, 360.0, 1.0))
_SHOULDER_R = np.array(PIVOTS['right_arm'], float)
_SHOULDER_L = np.array(PIVOTS['left_arm'], float)
_GRIP = G.tp_point(G.grip)
_TIP = G.tp_point(G.tip)
_POMMEL = G.tp_point(np.array([0.0, 0.0, 0.5]))
_SECOND = G.tp_point(G.second)
_REACH = float(np.linalg.norm(fist(left=True)))
_HEAD = (np.array([-4.0, -8.0, -4.0]), np.array([4.0, 0.0, 4.0]))
_BODY = (np.array([-4.0, 0.0, -2.0]), np.array([4.0, 12.0, 2.0]))
_ALONG = np.linspace(0.0, 1.0, 25)

def _turn_between(a, b):
    a, b = unit(a), unit(b)
    c = float(np.dot(a, b))
    if c > 0.999999:
        return np.eye(3)
    if c < -0.999999:
        side = np.cross(a, [1.0, 0.0, 0.0] if abs(a[0]) < 0.9 else [0.0, 1.0, 0.0])
        return qmat(qaxis(side, 180.0))
    return qmat(qaxis(np.cross(a, b), math.degrees(math.acos(c))))

def _inside(points, box):
    return np.all((points > box[0]) & (points < box[1]), axis=-1)

def _share_inside(a, b, box):
    """For each candidate, the fraction of the segment a-b that lies inside the box."""
    pts = a[:, None, :] + (b - a)[:, None, :] * _ALONG[None, :, None]
    return _inside(pts, box).mean(axis=1)

def _rolls(blade, torso, hint, nod):
    """Every way of holding the hilt with both hands and the blade along [blade] (a direction in the torso's frame): one
    arm rotation per roll about the blade, and what each costs. Misses of the off hand, the blade or the hands in the body
    or the head (tipped [nod] degrees; the torso, as a rotation, carries it about), and hands away from [hint] (torso
    frame, px) all cost."""
    blade = unit(blade)
    base = _turn_between(G.tp_blade, blade)
    cross = np.array([[0, -blade[2], blade[1]], [blade[2], 0, -blade[0]], [-blade[1], blade[0], 0]])
    c, s = np.cos(_PSI)[:, None, None], np.sin(_PSI)[:, None, None]
    R = c * base + s * (cross @ base) + (1 - c) * np.outer(blade, blade @ base)   # every roll about the blade
    grip, tip, pommel = (_SHOULDER_R + R @ p for p in (_GRIP, _TIP, _POMMEL))
    second = _SHOULDER_R + R @ _SECOND
    miss = np.abs(np.linalg.norm(second - _SHOULDER_L, axis=1) - _REACH)
    reach = np.linalg.norm(tip - grip, axis=1)
    butt = np.linalg.norm(grip - pommel, axis=1)
    cost = np.maximum(0.0, miss - 1.0) * 6.0 + 0.25 * np.linalg.norm(grip - np.asarray(hint, float), axis=1)
    cost += _share_inside(grip, tip, _BODY) * reach + 0.12 * _share_inside(pommel, grip, _BODY) * butt
    cost += 1.2 * np.maximum(0.0, grip[:, 2] + 1.2)                                  # hands in front of the chest
    about = qmat(torso)                                                              # the head keeps looking ahead
    to_root = lambda p: HIPS + (p - HIPS) @ about.T
    head = to_root(np.zeros(3))
    tilt = qmat(qeuler(x=nod))
    g, t_, p_ = ((to_root(p) - head) @ tilt for p in (grip, tip, pommel))
    cost += 2.5 * _share_inside(g, t_, _HEAD) * reach + 0.8 * _share_inside(p_, g, _HEAD) * butt
    cost += 3.0 * _inside(g, _HEAD)
    return R, cost

def solve_arms(c, bend=0.06):
    """Keys the right arm for every blade direction pose() was asked for. Each could be held in any roll about the blade;
    the rolls are chosen all together, the sequence that keeps the blade and hands clear of the body and head with the arm
    turning least from key to key (so no wrist flips)."""
    asks = sorted(c.asks, key=lambda a: a['t'])
    tables = [_rolls(a['blade'], a['torso'], a['hint'], a['nod']) for a in asks]
    unary = []
    for a, (R, cost) in zip(asks, tables):
        cost = cost.copy()
        if a['pin']:
            cost[np.arange(len(cost)) != int(np.argmin(cost))] = 1e9
        unary.append(cost)
    best, back = [unary[0]], []
    for k in range(1, len(asks)):
        trace = np.einsum('aij,bij->ab', tables[k - 1][0], tables[k][0])
        turn = np.degrees(np.arccos(np.clip((trace - 1) / 2, -1, 1)))
        total = best[-1][:, None] + bend * turn
        arg = np.argmin(total, axis=0)
        back.append(arg)
        best.append(total[arg, np.arange(total.shape[1])] + unary[k])
    i = int(np.argmin(best[-1]))
    path = [i]
    for k in range(len(asks) - 1, 0, -1):
        i = int(back[k - 1][i])
        path.append(i)
    for a, (R, _), i in zip(asks, tables, reversed(path)):
        c.key('right_arm', a['t'], qfrommat(R[i]), ease=a['ease'])
    return c

def sag(pitch, side=0.0):
    """A blade direction (az, el) in the vertical plane through the torso: [pitch] degrees up from straight ahead, past
    90 over the head and behind, leaning [side] degrees to the right."""
    return (side, pitch) if pitch <= 90 else (180.0 - side, 180.0 - pitch)

def bd(az, el):
    """A blade direction in the torso's frame: [az] degrees to the right of straight ahead, [el] degrees up."""
    a, e = math.radians(az), math.radians(el)
    return v(f=math.cos(e) * math.cos(a), u=math.sin(e), r=math.cos(e) * math.sin(a))

# ------------------------------------------------------------------ keys

_FOOT = np.array([[sx * 2.0, 12.0, sz * 2.0] for sx in (-1, 1) for sz in (-1, 1)])

def planted(ax, az, down):
    """How far a leg turned (ax forward/back, az out) must move down for its sole to stay on the floor with the root [down] px low."""
    lowest = max((qmat(qeuler(x=ax, z=az)) @ corner)[1] for corner in _FOOT)
    return 12.0 - down - lowest

def _put(c, bone, t, q=None, pos=None, ease='inout'):
    """A key that replaces any the bone already has at that tick."""
    c.keys[bone] = [k for k in c.keys.get(bone, []) if abs(k[0] - t) > 1e-6]
    c.key(bone, t, q, pos=pos, ease=ease)

def tp(name, length, **kw):
    c = Clip(name, length, **kw)
    c.asks, c.torso_q, c.nod, c.down = [], I.copy(), 0.0, 0.0
    return c

def pose(c, t, torso=None, blade=None, up=None, hips=None, right=0.0, down=0.0, fwd=0.0, legs=None, air=None, nod=None, ease='inout', hint=(0.0, 3.0, -7.0), pin=False):
    """One moment of a swing. [torso] = (yaw, lean): degrees to the right, degrees forward. [blade] = (az, el): where the
    blade points relative to the torso (both hands on the hilt). [hips] = the whole body's yaw in degrees (keys the root; it
    also moves [right], [down] and [fwd] px). [legs] = ((turn, out) for the right leg, (turn, out) for the left): degrees,
    forward negative, plus optionally how many px the foot is lifted; each leg is lowered or raised so its foot stays on the
    floor (unless the body is [air]borne: by default, whenever the root is raised). [nod] =
    the head tipped that many degrees down (negative looks up), on top of where the player looks. [up] = (pitch, side) as
    sag() takes it, for a blade swung through the vertical plane of the torso (so it passes over the head, not round it)."""
    if torso is not None:
        yaw, lean = torso
        c.torso_q = qmul(qeuler(y=yaw), qeuler(x=lean))
        _put(c, 'torso', t, c.torso_q, ease=ease)
    if nod is not None:
        c.mode('head', 'add')
        _put(c, 'head', t, qeuler(x=nod), ease=ease)
        c.nod = nod
    if up is not None:
        blade = sag(*up)
    if blade is not None:
        c.asks = [a for a in c.asks if abs(a['t'] - t) > 1e-6]
        c.asks.append(dict(t=float(t), blade=bd(*blade), torso=c.torso_q.copy(), nod=c.nod, hint=hint, ease=ease, pin=pin))
    if hips is not None:
        _put(c, 'root', t, qeuler(y=hips), pos=(-right, down, -fwd), ease=ease)
        c.down = down
    if legs is not None:
        for bone, (ax, az, *lift) in zip(('right_leg', 'left_leg'), legs):
            lifted = lift[0] if lift else 0.0
            airborne = c.down < -0.2 if air is None else air
            _put(c, bone, t, qeuler(x=ax, z=az), pos=(0.0, 0.0 if airborne else planted(ax, az, c.down) - lifted, 0.0), ease=ease)
    return c

def blend(a, b, x):
    """A pose's numbers (or tuples of them, nested) [x] of the way from [a] to [b]."""
    if isinstance(a, (tuple, list)):
        n = max(len(a), len(b))
        a, b = tuple(a) + (0.0,) * (n - len(a)), tuple(b) + (0.0,) * (n - len(b))
        return tuple(blend(p, q, x) for p, q in zip(a, b))
    return a + (b - a) * x

def lerp_pose(a, b, x):
    """The pose kwargs [x] of the way from pose [a] to pose [b]."""
    return {k: blend(a[k], b[k], x) if k in a and k in b else a.get(k, b.get(k)) for k in {*a, *b} if k != 'pin'}

def minjerk(x):
    """The smoothest start-to-stop: nothing for a while, a rush through the middle, nothing again."""
    x = min(1.0, max(0.0, x))
    return x * x * x * (10.0 + x * (6.0 * x - 15.0))

def smooth(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3.0 - 2.0 * x)

def ramp(x, power=2.4):
    """Faster and faster all the way, for a blow that ends on its target (the ground) at full speed."""
    return min(1.0, max(0.0, x)) ** power

def _along(way, x, lead, profile):
    """The pose kwargs [x] (0 to 1) of the way along [way], each channel [lead]ing or lagging as asked, speed set by [profile]."""
    kw = {}
    for name in {k for _, p in way for k in p if k not in ('pin', 'rest')}:
        d = lead.get(name, 0.0)
        xs = profile(min(1.0, x * (1.0 + d)) if d >= 0 else max(0.0, (x + d) / (1.0 + d)))
        have = [(q, p[name]) for q, p in way if name in p]
        if xs <= have[0][0]:
            kw[name] = have[0][1]
        elif xs >= have[-1][0]:
            kw[name] = have[-1][1]
        else:
            for (qa, a), (qb, b) in zip(have, have[1:]):
                if xs <= qb:
                    kw[name] = blend(a, b, (xs - qa) / (qb - qa))
                    break
    return kw

def sweep(c, t0, t1, way, lead=None, step=0.5, profile=minjerk, ease='inout'):
    """A fast stroke from [t0] to [t1] along the waypoints [way] = [(progress, pose kwargs), ...] (progress 0 to 1, each pose
    given as pose() takes it), keyed every [step] ticks so the speed follows [profile]: nothing at the start, a rush in the
    middle, nothing at the end. [lead] = {channel: fraction} sets a channel ahead of the torso (the legs, driving the body
    in) or, when negative, behind it (the blade, dragged round late)."""
    n = max(1, int(round((t1 - t0) / step)))
    for i in range(n + 1):
        pose(c, t0 + (t1 - t0) * i / n, ease=ease if i == 0 else 'linear', **_along(way, i / n, lead or {}, profile))
    return c

# A smooth path through waypoints: every channel runs a monotone cubic through its values (no overshoot, resting at both ends,
# so a pose held for a while stays held and a turn-round slows to a stop).

def _array(name, value):
    if name == 'legs':
        out = np.zeros((2, 3))
        for i, leg in enumerate(value):
            out[i, :len(leg)] = leg
        return out
    return np.array(value, float)

def _plain(name, a):
    if name == 'legs':
        return tuple(tuple(float(x) for x in row) for row in a)
    return float(a) if a.ndim == 0 else tuple(float(x) for x in a)

def _slopes(ts, ys):
    m = np.zeros_like(ys)
    if len(ts) < 3:
        return m
    h = np.diff(ts).reshape((-1,) + (1,) * (ys.ndim - 1))
    d = np.diff(ys, axis=0) / h
    for i in range(1, len(ts) - 1):
        w1, w2 = 2 * (ts[i + 1] - ts[i]) + (ts[i] - ts[i - 1]), (ts[i + 1] - ts[i]) + 2 * (ts[i] - ts[i - 1])
        with np.errstate(divide='ignore', invalid='ignore'):
            harmonic = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
        m[i] = np.where(d[i - 1] * d[i] > 0, harmonic, 0.0)
    return m

def _hermite(t, ts, ys, m):
    if t <= ts[0]:
        return ys[0]
    if t >= ts[-1]:
        return ys[-1]
    i = int(np.searchsorted(ts, t, side='right')) - 1
    h = ts[i + 1] - ts[i]
    s = (t - ts[i]) / h
    return ((2 * s**3 - 3 * s**2 + 1) * ys[i] + (s**3 - 2 * s**2 + s) * h * m[i]
            + (-2 * s**3 + 3 * s**2) * ys[i + 1] + (s**3 - s**2) * h * m[i + 1])

def curves(way):
    out = {}
    for name in {k for _, p in way for k in p if k not in ('pin', 'rest')}:
        pts = [(t, _array(name, p[name])) for t, p in way if name in p]
        ts, ys = np.array([t for t, _ in pts], float), np.array([y for _, y in pts])
        out[name] = (ts, ys, _slopes(ts, ys))
    return out

def at(way, t):
    """The pose kwargs of the path [way] at tick [t]."""
    return {name: _plain(name, _hermite(t, *crv)) for name, crv in curves(way).items()}

def flow(c, way, step=0.5, ease='inout', skip_first=False, skip_last=False):
    """Poses [c] along a smooth path through [way] = [(tick, pose kwargs), ...], a key every [step] ticks. A waypoint with
    pin=True holds the arm in the one roll that suits that pose best (so the ready pose is the same in every clip)."""
    crv = curves(way)
    t0, t1 = way[0][0], way[-1][0]
    pinned = {t for t, p in way if p.get('pin')}
    n = int(round((t1 - t0) / step))
    for i in range(n + 1):
        if (i == 0 and skip_first) or (i == n and skip_last):
            continue
        t = t0 + (t1 - t0) * i / n
        kw = {name: _plain(name, _hermite(t, *cv)) for name, cv in crv.items()}
        pose(c, t, ease=ease if i == 0 else 'linear', pin=any(abs(t - q) < 1e-6 for q in pinned), **kw)
    return c

def finish(c):
    """Solves the right arm's rolls, then closes the off hand on the hilt for the whole clip and says how far it misses."""
    solve_arms(c)
    c.miss = hold_with_both(c, G)
    return c

# ------------------------------------------------------------------ first person
#
# Seen from behind, a sword swung flat is a sliver, so the item is turned to show its flat to the player through the swing: a pose
# is the grip's place on the screen, how far the blade leans from upright, and how far it is turned about itself.

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    off, q = fp(hand, blade, edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

def look(hand, tilt, lean, roll=0.0):
    """The item's blade and edge for a grip at [hand] (camera space, blocks: x right, y up, z back): the blade leans [tilt] degrees
    from straight up across the screen (+ to the right) and [lean] degrees into the screen (negative toward the player's face), and is
    turned [roll] degrees about itself (0: the flat faces the player, the edge to the left; -90: the way it sits at rest)."""
    t, l, r = math.radians(tilt), math.radians(lean), math.radians(roll)
    blade = unit(np.array([math.sin(t) * math.cos(l), math.cos(t) * math.cos(l), -math.sin(l)]))
    left = np.array([-math.cos(t), math.sin(t), 0.0])
    e0 = unit(left - np.dot(left, blade) * blade)
    return blade, e0 * math.cos(r) + np.cross(blade, e0) * math.sin(r)

REST = fp_rest(G)
FP_REST = dict(hand=tuple(float(x) for x in REST), tilt=0.0, lean=-math.degrees(math.asin(G.fp_blade[2])), roll=-90.0, rest=True)

def fpath(f, way, step=0.5, skip_first=False, skip_last=False):
    """Keys item [f] along a smooth path through [way] = [(tick, dict(hand=, tilt=, lean=, roll=)), ...]; a waypoint with rest=True
    is exactly the rest pose (the item at identity)."""
    crv = curves(way)
    t0, t1 = way[0][0], way[-1][0]
    n = int(round((t1 - t0) / step))
    for i in range(n + 1):
        if (i == 0 and skip_first) or (i == n and skip_last):
            continue
        t = t0 + (t1 - t0) * i / n
        ease = 'inout' if i == 0 else 'linear'
        if any(p.get('rest') and abs(t - q) < 1e-6 for q, p in way):
            fk(f, t, REST, G.fp_blade, None, ease)
            continue
        v_ = {name: _plain(name, _hermite(t, *cv)) for name, cv in crv.items()}
        fk(f, t, v_['hand'], *look(v_['hand'], v_['tilt'], v_['lean'], v_['roll']), ease)
    return f

def fsweep(f, t0, t1, way, lead=None, step=0.5, profile=minjerk, ease='inout'):
    """A fast stroke of the item: as sweep(), over [way] = [(progress, dict(hand=, tilt=, lean=, roll=)), ...]."""
    n = max(1, int(round((t1 - t0) / step)))
    for i in range(n + 1):
        v_ = _along(way, i / n, lead or {}, profile)
        fk(f, t0 + (t1 - t0) * i / n, v_['hand'], *look(v_['hand'], v_['tilt'], v_['lean'], v_['roll']), ease if i == 0 else 'linear')
    return f

def fp_pose(hand, tilt, lean, roll=0.0):
    return dict(hand=tuple(hand), tilt=float(tilt), lean=float(lean), roll=float(roll))

# ------------------------------------------------------------------ the poses

NOWHERE = dict(right=0.0, down=0.0, fwd=0.0, legs=((0, 0), (0, 0)))

def ready(up=False, nod=False):
    """The carry the move starts and ends in: the hilt before the chest, the blade up and ahead (the same in every clip)."""
    return dict(torso=(0, 0), hips=0, pin=True, **({'up': (55, 20)} if up else {'blade': (20, 55)}), **({'nod': 0} if nod else {}), **NOWHERE)

# ------------------------------------------------------------------ light 1: a cleave from the right shoulder across to the left
c = tp('greatsword/light_1', 20, blend_in=2.0, blend_out=5.0).mark('hit', 8).mark('chain', 13)
L1_COIL = dict(torso=(58, -9),  blade=(45, 30), hips=10,  right=0.7,  down=1.3, fwd=-0.9, legs=((10, 4), (-10, -3)))
L1_END  = dict(torso=(-62, 10), blade=(6, 16),  hips=-13, right=-0.5, down=2.4, fwd=3.1,  legs=((15, 4), (-28, -4)))
flow(c, [
    (0.0, dict(torso=(20, -2), blade=(35, 50), hips=0, **NOWHERE)),
    (3.0, dict(torso=(40, -5), blade=(40, 42), hips=4, right=0.3, down=0.6, fwd=-0.4, legs=((5, 3), (-4, -2)))),
    (6.0, L1_COIL),
], skip_last=True)
sweep(c, 6.0, 11.0, [(0.0, L1_COIL), (1.0, L1_END)], lead=dict(legs=0.5, right=0.5, down=0.5, fwd=0.4, blade=-0.12))
L1_BACK = [
    (11.0, L1_END),
    (12.0, dict(torso=(-66, 13), blade=(4, 16),  hips=-15, right=-0.5, down=2.3, fwd=3.1, legs=((14, 4), (-26, -4)))),
    (16.0, dict(torso=(-34, 8),  blade=(12, 38), hips=-8,  right=-0.2, down=1.0, fwd=1.6, legs=((6, 2), (-12, -2)))),
    (20.0, ready()),
]
flow(c, L1_BACK, skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/light_1', 20, blend_in=2.0, blend_out=5.0)
F1_COCK = fp_pose((0.70, -0.40, -0.80), 40, -8, -20)
F1_MID  = fp_pose((0.00, -0.50, -1.00), 2, 15, 10)
F1_END  = fp_pose((-0.62, -0.52, -1.05), -38, 10, -15)
fpath(f, [(0.0, FP_REST), (3.0, fp_pose((0.72, -0.48, -0.78), 18, -6, -40)), (6.0, F1_COCK)], skip_last=True)
fsweep(f, 6.0, 11.0, [(0.0, F1_COCK), (0.5, F1_MID), (1.0, F1_END)], lead=dict(tilt=-0.06))
F1_BACK = [(11.0, F1_END), (12.0, fp_pose((-0.64, -0.54, -1.02), -42, 10, -20)),
           (16.0, fp_pose((0.15, -0.66, -0.86), -10, 0, -32)), (20.0, FP_REST)]
fpath(f, F1_BACK, skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ light 2: the return cleave, rising from low left to high right
c = tp('greatsword/light_2', 20, blend_in=2.0, blend_out=5.0).mark('hit', 8).mark('chain', 13)
L2_COIL = dict(torso=(-80, 19), blade=(2, 6),   hips=-19, right=0.0,  down=3.0, fwd=2.2, legs=((-20, 4), (6, -4)))
L2_END  = dict(torso=(49, -10), blade=(44, 36), hips=13,  right=-0.5, down=2.4, fwd=3.2, legs=((-26, 4), (12, -4)))
flow(c, [
    (0.0, at(L1_BACK, 13.0)),                                    # where light 1 has got to when this cuts in
    (2.5, dict(torso=(-76, 17), blade=(2, 8), hips=-18, right=-0.3, down=2.6, fwd=2.5, legs=((6, 4, 1.5), (-16, -4)))),
    (4.5, dict(torso=(-80, 19), blade=(2, 6), hips=-19, right=0.0,  down=3.0, fwd=2.2, legs=((-8, 4, 2.0), (-2, -4)))),
    (6.3, L2_COIL),
], skip_last=True)
sweep(c, 6.3, 11.3, [(0.0, L2_COIL), (1.0, L2_END)], lead=dict(legs=0.5, right=0.5, down=0.5, fwd=0.4, blade=-0.12))
flow(c, [
    (11.3, L2_END),
    (12.8, dict(torso=(53, -11), blade=(46, 38), hips=14, right=-0.5, down=2.3, fwd=3.2, legs=((-26, 4), (12, -4)))),
    (16.0, dict(torso=(30, -4),  blade=(34, 46), hips=6,  right=-0.2, down=1.0, fwd=1.6, legs=((-12, 2), (6, -2)))),
    (20.0, ready()),
], skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/light_2', 20, blend_in=2.0, blend_out=5.0)
F2_COIL = fp_pose((-0.52, -0.62, -0.98), -64, 0, -15)
F2_END  = fp_pose((0.58, -0.16, -0.92), 32, -6, -20)
fpath(f, [(0.0, at(F1_BACK, 13.0)), (3.0, fp_pose((-0.58, -0.60, -1.00), -56, 4, -18)), (6.3, F2_COIL)], skip_last=True)
fsweep(f, 6.3, 11.3, [(0.0, F2_COIL), (0.5, fp_pose((0.05, -0.42, -1.00), -4, 14, 8)), (1.0, F2_END)], lead=dict(tilt=-0.06))
fpath(f, [(11.3, F2_END), (12.8, fp_pose((0.60, -0.14, -0.90), 36, -6, -25)),
          (16.0, fp_pose((0.60, -0.42, -0.82), 24, -6, -50)), (20.0, FP_REST)], skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ heavy: an overhead chop, held raised while the button is
c = tp('greatsword/heavy', 28, blend_in=2.5, blend_out=6.0).mark('hold', 8).mark('hit', 12).mark('chain', 20)
H_HOLD = dict(torso=(30, -20), up=(124, 60), nod=-30, hips=6, right=0.5, down=1.6, fwd=-2.0, legs=((12, 5), (-14, -4)))
H_WIND = dict(torso=(32, -22), up=(128, 64), nod=-32, hips=7, right=0.5, down=2.0, fwd=-2.3, legs=((13, 5), (-16, -4)))
H_END  = dict(torso=(0, 31),   up=(12, 0),   nod=6,   hips=0, right=0.0, down=4.0, fwd=2.8,  legs=((18, 5), (-34, -4)))
flow(c, [
    (0.0, ready(up=True, nod=True)),
    (3.0, dict(torso=(10, -8),  up=(76, 14),  nod=-8,  hips=3, right=0.2, down=0.6, fwd=-0.4, legs=((4, 3), (-4, -3)))),
    (6.0, dict(torso=(22, -15), up=(106, 36), nod=-20, hips=5, right=0.4, down=1.2, fwd=-1.2, legs=((8, 4), (-8, -3)))),
    (8.0, H_HOLD),                                               # the clip is held here while the button is
])
flow(c, [(8.0, H_HOLD), (10.5, H_WIND)], skip_first=True)
sweep(c, 10.5, 15.5, [(0.0, H_WIND), (1.0, H_END)], lead=dict(legs=0.6, right=0.6, down=0.6, fwd=0.5, up=-0.1))
flow(c, [
    (15.5, H_END),
    (17.5, dict(torso=(0, 31), up=(10, 0),  nod=6, hips=0, right=0.0, down=3.9, fwd=2.8, legs=((18, 5), (-34, -4)))),
    (20.0, dict(torso=(0, 25), up=(22, 0),  nod=2, hips=0, right=0.0, down=3.2, fwd=2.2, legs=((15, 4), (-28, -3)))),
    (28.0, ready(up=True, nod=True)),
], skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/heavy', 28, blend_in=2.5, blend_out=6.0)
FH_HOLD = fp_pose((0.55, 0.02, -0.68), 22, -38, -25)
FH_WIND = fp_pose((0.55, 0.10, -0.62), 26, -42, -25)
FH_END  = fp_pose((0.05, -0.62, -1.05), 0, 55, 8)
fpath(f, [(0.0, FP_REST), (3.0, fp_pose((0.62, -0.40, -0.74), 6, -18, -60)), (6.0, fp_pose((0.58, -0.12, -0.66), 15, -34, -35)), (8.0, FH_HOLD)])
fpath(f, [(8.0, FH_HOLD), (10.5, FH_WIND)], skip_first=True)
fsweep(f, 10.5, 15.5, [(0.0, FH_WIND), (0.5, fp_pose((0.18, -0.15, -0.90), 8, 4, 5)), (1.0, FH_END)], lead=dict(lean=-0.05))
fpath(f, [(15.5, FH_END), (17.5, fp_pose((0.05, -0.64, -1.02), 0, 52, 8)),
          (20.0, fp_pose((0.12, -0.64, -0.96), -4, 36, -10)), (28.0, FP_REST)], skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ running: a rising cleave carried by the run
c = tp('greatsword/running', 20, blend_in=1.5, blend_out=5.0).mark('hit', 7).mark('chain', 14)
R_TRAIL = dict(torso=(50, 13),  blade=(48, 0), hips=9,   right=0.0,  down=1.5, fwd=1.0, legs=((26, 4), (-34, -4)))
R_END   = dict(torso=(-68, -6), blade=(4, 46), hips=-15, right=-0.3, down=2.4, fwd=3.1, legs=((15, 4), (-28, -4)))
flow(c, [
    (0.0, dict(torso=(16, 6),  blade=(24, 40), hips=4,  down=0.2, fwd=0.0, right=0.0, legs=((22, 2), (-24, -2)))),
    (2.0, dict(torso=(44, 12), blade=(38, 14), hips=8,  down=0.9, fwd=0.5, right=0.0, legs=((28, 4), (-34, -4)))),
    (3.5, dict(torso=(54, 14), blade=(50, -4), hips=10, down=1.2, fwd=0.8, right=0.0, legs=((30, 4), (-36, -4)))),
    (5.5, R_TRAIL),
], skip_last=True)
sweep(c, 5.5, 10.5, [(0.0, R_TRAIL), (1.0, R_END)], lead=dict(legs=0.5, down=0.5, fwd=0.4, blade=-0.1))
flow(c, [
    (10.5, R_END),
    (13.0, dict(torso=(-66, -5), blade=(4, 48),  hips=-15, right=0.0, down=2.0, fwd=3.0, legs=((14, 4), (-26, -4)))),
    (16.0, dict(torso=(-30, 0),  blade=(14, 50), hips=-6,  right=0.0, down=1.0, fwd=1.5, legs=((6, 2), (-12, -2)))),
    (20.0, ready()),
], skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/running', 20, blend_in=1.5, blend_out=5.0)
FR_TRAIL = fp_pose((0.68, -0.44, -0.86), 64, -6, -25)
FR_END   = fp_pose((-0.60, 0.02, -0.98), -46, 6, -15)
fpath(f, [(0.0, FP_REST), (2.0, fp_pose((0.70, -0.50, -0.82), 46, -4, -40)), (3.5, FR_TRAIL), (5.5, FR_TRAIL)], skip_last=True)
fsweep(f, 5.5, 10.5, [(0.0, FR_TRAIL), (0.5, fp_pose((0.02, -0.40, -1.00), 4, 15, 8)), (1.0, FR_END)], lead=dict(tilt=-0.06))
fpath(f, [(10.5, FR_END), (13.0, fp_pose((-0.62, 0.04, -0.98), -50, 6, -18)),
          (16.0, fp_pose((-0.05, -0.50, -0.88), -18, 0, -40)), (20.0, FP_REST)], skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ jump: an airborne overhead slam
c = tp('greatsword/jump', 20, blend_in=1.5, blend_out=5.0).mark('hit', 7).mark('chain', 15)
J_TOP = dict(torso=(0, -12), up=(110, 10), nod=-20, hips=0, right=0.0, down=-2.3, fwd=-0.2, legs=((-14, 5), (-32, -5)))
J_END = dict(torso=(0, 34),  up=(8, 0),    nod=8,   hips=0, right=0.0, down=4.6,  fwd=2.6,  legs=((18, 9), (-28, -9)))
flow(c, [
    (0.0, ready(up=True, nod=True)),
    (3.0, dict(torso=(0, -10), up=(104, 8),  nod=-14, hips=0, right=0.0, down=-1.5, fwd=0.0,  legs=((-8, 4), (-24, -4)))),
    (5.0, dict(torso=(0, -14), up=(112, 10), nod=-20, hips=0, right=0.0, down=-2.5, fwd=-0.3, legs=((-14, 5), (-34, -5)))),
    (6.0, J_TOP),
], skip_last=True)
sweep(c, 6.0, 10.5, [(0.0, J_TOP), (1.0, J_END)], lead=dict(up=-0.1))
flow(c, [
    (10.5, J_END),
    (15.0, dict(torso=(0, 22), up=(26, 0), nod=2, hips=0, right=0.0, down=3.0, fwd=1.6, legs=((12, 5), (-16, -5)))),
    (20.0, ready(up=True, nod=True)),
], skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/jump', 20, blend_in=1.5, blend_out=5.0)
FJ_TOP = fp_pose((0.50, 0.04, -0.62), 18, -38, -25)
FJ_END = fp_pose((0.00, -0.68, -1.10), 0, 55, 8)
fpath(f, [(0.0, FP_REST), (3.0, fp_pose((0.58, -0.20, -0.68), 12, -28, -40)), (6.0, FJ_TOP)], skip_last=True)
fsweep(f, 6.0, 10.5, [(0.0, FJ_TOP), (0.5, fp_pose((0.15, -0.20, -0.90), 6, 4, 6)), (1.0, FJ_END)], lead=dict(lean=-0.05))
fpath(f, [(10.5, FJ_END), (15.0, fp_pose((0.10, -0.62, -0.95), 0, 30, -20)), (20.0, FP_REST)], skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ guard counter: shove off the guard into a heavy diagonal cut, high right to low left
c = tp('greatsword/guard_counter', 22, blend_in=2.0, blend_out=5.0).mark('hit', 9).mark('chain', 16)
G_COCK = dict(torso=(35, 6),   blade=(30, 52), hips=8,   right=0.0, down=2.6, fwd=0.4, legs=((14, 4), (-22, -4)))
G_END  = dict(torso=(-60, 28), blade=(4, 22),  hips=-16, right=0.0, down=4.0, fwd=3.4, legs=((16, 5), (-32, -4)))
flow(c, [
    (0.0, dict(torso=(-18, 3), blade=(-24, 48), hips=-7,  down=0.8, fwd=0.0, right=0.0, legs=((5, 3), (-6, -3)))),      # the guard
    (2.5, dict(torso=(-40, 8), blade=(-42, 42), hips=-14, down=2.0, fwd=-0.6, right=0.0, legs=((12, 4), (-12, -4)))),
    (4.7, dict(torso=(0, 4),   blade=(8, 74),   hips=-2,  down=2.4, fwd=0.0, right=0.0, legs=((13, 4), (-18, -4)))),   # up and over
    (7.0, G_COCK),
], skip_last=True)
sweep(c, 7.0, 12.0, [(0.0, G_COCK), (1.0, G_END)], lead=dict(legs=0.6, down=0.6, fwd=0.5, blade=-0.1))
flow(c, [
    (12.0, G_END),
    (16.0, dict(torso=(-35, 14), blade=(12, 38), hips=-8, right=0.0, down=2.0, fwd=1.8, legs=((8, 3), (-16, -3)))),
    (22.0, ready()),
], skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/guard_counter', 22, blend_in=2.0, blend_out=5.0)
FG_COCK = fp_pose((0.72, -0.12, -0.74), 48, -12, -20)
FG_END  = fp_pose((-0.62, -0.60, -1.00), -52, 8, -15)
fpath(f, [(0.0, fp_pose((0.05, -0.55, -0.80), -52, -4, -20)), (2.5, fp_pose((-0.12, -0.58, -0.82), -62, 0, -18)),
          (4.7, fp_pose((0.35, -0.30, -0.80), 0, 10, -15)), (7.0, FG_COCK)], skip_last=True)
fsweep(f, 7.0, 12.0, [(0.0, FG_COCK), (0.5, fp_pose((0.05, -0.35, -0.95), 0, 12, 0)), (1.0, FG_END)], lead=dict(tilt=-0.06))
fpath(f, [(12.0, FG_END), (16.0, fp_pose((-0.15, -0.62, -0.86), -25, 0, -45)), (22.0, FP_REST)], skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ skill: Earthsplitter, a leaping slam that drives the blade along the ground
c = tp('greatsword/skill', 30, blend_in=2.0, blend_out=6.0).mark('impact', 14).mark('chain', 23)
S_TOP = dict(torso=(0, -14), up=(112, 8), nod=-18, hips=0, right=0.0, down=-4.0, fwd=0.2, legs=((-10, 5), (-26, -5)))
S_HIT = dict(torso=(0, 38),  up=(16, 0),  nod=10,  hips=0, right=0.0, down=5.0,  fwd=3.2, legs=((20, 12), (-30, -12)))
flow(c, [
    (0.0, ready(up=True, nod=True)),
    (2.5, dict(torso=(10, 14), up=(35, 35),  nod=4,   hips=4, right=0.4, down=3.0,  fwd=0.0,  legs=((12, 9), (-12, -9)))),         # crouch
    (5.0, dict(torso=(14, 18), up=(30, 40),  nod=6,   hips=6, right=0.6, down=4.6,  fwd=-0.4, legs=((16, 11), (-14, -11)))),
    (7.5, dict(torso=(0, -8),  up=(100, 6),  nod=-10, hips=0, right=0.0, down=-2.0, fwd=0.4,  legs=((-6, 5), (-20, -5)))),         # the leap
    (10.5, S_TOP),
], skip_last=True)
sweep(c, 10.5, 14.0, [(0.0, S_TOP), (1.0, S_HIT)], profile=lambda x: ramp(x, 2.0), ease='out')
flow(c, [
    (14.0, S_HIT),
    (16.0, dict(torso=(0, 40), up=(18, 0), nod=10, hips=0, right=0.0, down=5.4, fwd=3.4, legs=((20, 12), (-30, -12)))),
    (19.0, dict(torso=(0, 30), up=(30, 0), nod=4,  hips=0, right=0.0, down=3.6, fwd=2.4, legs=((16, 9), (-22, -9)))),
    (23.0, dict(torso=(0, 14), up=(55, 0), nod=0,  hips=0, right=0.0, down=1.6, fwd=1.0, legs=((8, 4), (-10, -4)))),
    (30.0, ready(up=True, nod=True)),
], skip_first=True)
finish(c)
clips.append(c)

f = Clip('greatsword/skill', 30, blend_in=2.0, blend_out=6.0)
FS_TOP = fp_pose((0.50, 0.08, -0.62), 18, -38, -25)
FS_HIT = fp_pose((0.00, -0.76, -1.15), 0, 72, 5)
fpath(f, [(0.0, FP_REST), (2.5, fp_pose((0.62, -0.72, -0.80), 8, 12, -60)), (5.0, fp_pose((0.60, -0.80, -0.82), 8, 18, -55)),
          (7.5, fp_pose((0.55, -0.20, -0.70), 15, -25, -40)), (10.5, FS_TOP)], skip_last=True)
fsweep(f, 10.5, 14.0, [(0.0, FS_TOP), (1.0, FS_HIT)], profile=lambda x: ramp(x, 2.0), ease='out')
fpath(f, [(14.0, FS_HIT), (16.0, fp_pose((0.00, -0.80, -1.12), 0, 74, 5)), (19.0, fp_pose((0.10, -0.66, -0.96), 0, 40, -15)),
          (23.0, fp_pose((0.30, -0.62, -0.84), 4, 10, -45)), (30.0, FP_REST)], skip_first=True)
fps.append(f)

# ------------------------------------------------------------------ output

if __name__ == '__main__':
    assets, previews, skin, weapon = sys.argv[1:5]
    os.makedirs(previews, exist_ok=True)
    from PIL import Image
    scene = Scene(Image.open(skin), Image.open(weapon), geo=G)
    by_name = {f.name: f for f in fps}
    for c in clips:
        print(c.write(assets, 'player'), f'two-handed miss {c.miss:.2f} px')
    for f in fps:
        print(f.write(assets, 'first_person'))
    only = sys.argv[5:] or [c.name for c in clips]
    for c in clips:
        if c.name not in only:
            continue
        sheet(scene, c, by_name.get(c.name)).save(os.path.join(previews, c.name.split('/')[-1] + '.png'))
