"""
Silvan Marrow's clips: the renegade boss of the Starwatch questline, a staff-fighter in a hood and a star-mantle.
Same skeleton and kit as the creatures' attacks (enemy_anims.py, animkit.py). Run:

    python3 -B art/scripts/renegade_anims.py src/main/resources/assets/guildmark <preview-dir> [names]

Each clip lasts as many ticks as its attack and the spell (or the blow) leaves on the release tick named in the table
below, exactly where the staff's head is at its moment of greatest intent. The staff is a real item in his right hand,
drawn by the game the way the Hollow Soldier's sword is (the Amethyst Staff's own model: weaponkit.STAFF), so the clips
place it by the arm: the shaft sits square to the forearm, a hand's breadth up from the butt, the head 22 px from the fist.

    bolt     24   release 12   a one-handed thrust of the staff at the target, the free hand drawn back
    fan      30   release 16   a wide sweep of the staff, right to left, pointing straight ahead at 16
    lance    44   release 30   drawn back over the right shoulder like a javelin 4-26, a hard thrust at 30
    blink    18   release 8    a gathering crouch with the mantle swept round, gone at 8, rising 8-18
    summon   36   release 24   the staff circled overhead in both hands 6-22, thrust up at 24
    starfall 56   release 34   both hands lift the staff high 0-30, the face to the sky, the butt slammed down at 34
    strike   20   blow 9       a quick backhand swipe at someone close
    shove    24   blow 10      a palm thrust of the free left hand
    intro    108  -            turns to the player and speaks (timed to his voice), arms wide, the staff swept up as the stones wake (90)
    death    60   -            a stagger, on his knees at 20, slumped forward over the staff by 50

Ranged casts face the target the whole wind-up: the body stays square to it (the torso winds, the root doesn't turn) and
the head is left to the game, which keeps it looking at the target. The previews draw the model itself (read from
RenegadeModel.kt) and its texture, the mantle hanging as the model's setupAnim hangs it, and the staff in the hand;
the yellow line is the path of the staff's head so far.
"""
import math, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import STAFF as G
from enemy_anims import MeshScene, euler_x

clips = []

def rn(name, length, **kw):
    clip = Clip(name, length, **kw)
    clips.append(clip)
    return clip

# ================================================================== the staff in the hand

# The shaft's line and its points, in the arm's own frame (model pixels). G.tp_point takes item-space points.
SHAFT = unit(G.tp_blade)                                   # grip to head: square to the forearm, give or take a few degrees
GRIP_L = G.tp_point(G.grip)                                # where the right fist closes
HEAD_L = G.tp_point(G.tip)
BUTT_L = G.tp_point((0.0, 0.0, 0.5))                       # the sprite's corner, the staff's butt end
STAFF_LEN = float(np.linalg.norm(HEAD_L - GRIP_L))
BUTT_LEN = float(np.linalg.norm(GRIP_L - BUTT_L))
R_SH = np.array(PIVOTS['right_arm'], float)
L_SH = np.array(PIVOTS['left_arm'], float)

# ================================================================== aiming the staff: the arms from where the staff should be

_COS = float(SHAFT[1])                                     # the shaft makes this angle's cosine with the arm's axis
_SIN = math.sqrt(1.0 - _COS * _COS)
HAND_L = GRIP_L * np.array([-1.0, 1.0, 1.0])               # where the left fist closes on a shaft, as the right one does
LEFT_REACH = float(np.linalg.norm(HAND_L))

def sp(left=0.0, up=0.0):
    """A direction in the character's frame: [left] degrees to his left of straight ahead, [up] degrees above level (past 90 it leans back)."""
    l, u = math.radians(left), math.radians(up)
    return v(f=math.cos(u) * math.cos(l), u=math.sin(u), r=-math.cos(u) * math.sin(l))

def cone(axis, half, turn):
    """A direction on a cone: [half] degrees from an axis that leans [axis] degrees forward of straight up, [turn] degrees round it
    (0 is the side leaning furthest forward, 90 towards his right, 180 the side leaning furthest back)."""
    a, h, p = math.radians(axis), math.radians(half), math.radians(turn)
    ax = np.array([math.sin(a), math.cos(a), 0.0])
    e1 = np.array([math.cos(a), -math.sin(a), 0.0])
    e2 = np.array([0.0, 0.0, 1.0])
    d = math.cos(h) * ax + math.sin(h) * (math.cos(p) * e1 + math.sin(p) * e2)
    return v(f=d[0], u=d[1], r=d[2])

def lean_twist(twist=0.0, lean=0.0, tilt=0.0):
    """The torso: [twist] degrees to his right (the right shoulder back), [lean] forward, [tilt] to his left."""
    return qmul(qmul(qeuler(z=tilt), qeuler(y=twist)), qeuler(x=lean))

def hold(tq, point, hang=D):
    """The right arm (relative to a torso turned [tq]) holding the staff with its head along [point] (his frame) and the arm
    pointing as near [hang] as the grip allows (the shaft is square to the forearm, give or take ten degrees, so [hang] says
    which way round the shaft the arm lies)."""
    s = unit(point)
    h = np.asarray(hang, float)
    p = h - np.dot(h, s) * s
    if np.linalg.norm(p) < 1e-6:
        p = np.cross(s, R)
    d = _COS * s + _SIN * unit(p)
    M = qmat(tq).T
    return arm(M @ d, M @ s, geo=G)

def free(tq, direction, palm=None):
    """The free left arm (relative to a torso turned [tq]) pointing along [direction] in his frame."""
    M = qmat(tq).T
    return free_arm(M @ unit(direction), None if palm is None else M @ unit(palm))

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F, geo=G)

def staff_in_torso(q, pos=None):
    """The staff's butt, fist and head in the torso's frame for the right arm turned [q] (pivot moved by [pos])."""
    M = qmat(q)
    o = R_SH + (np.zeros(3) if pos is None else np.asarray(pos, float))
    return o + M @ BUTT_L, o + M @ GRIP_L, o + M @ HEAD_L

def left_on_staff(qr, posr=None, lam=9.0, posl=None):
    """The left arm (relative to the torso) with its fist closed on the shaft [lam] px up it from the right fist, the shaft
    square to the forearm as in the right hand. The arms are rigid, so the shaft is only reached where it passes a fist's
    reach from the left shoulder: [lam] is moved along the shaft to where it is. Returns (rotation, how far it misses, lam used)."""
    butt, grip, head = staff_in_torso(qr, posr)
    b = unit(head - grip)
    sh = L_SH + (np.zeros(3) if posl is None else np.asarray(posl, float))
    w = grip - sh
    B = float(w @ b)
    C = float(w @ w) - LEFT_REACH ** 2
    disc = B * B - C
    if disc >= 0:
        roots = (-B - math.sqrt(disc), -B + math.sqrt(disc))
        lam = min(roots, key=lambda r_: abs(r_ - lam))
    else:
        lam = -B
    lam = max(-BUTT_LEN, min(STAFF_LEN, lam))
    d = grip + lam * b - sh
    return frame_to(HAND_L, SHAFT, d, b), abs(float(np.linalg.norm(d)) - LEFT_REACH), lam

def intrusion(tq, qr, qlt, posr=None, posl=None):
    """How deep (pixels) the staff's shaft or a fist goes into his head or his chest. The head keeps its own (level) turn
    while the torso bends, so its box is tested in the character's frame, the chest's in the torso's."""
    M = qmat(tq)
    butt, grip, head = staff_in_torso(qr, posr)
    sl = L_SH + (np.zeros(3) if posl is None else np.asarray(posl, float))
    pts = [butt + (head - butt) * k / 8 for k in range(9)]
    pts.append(sl + qmat(qlt) @ HAND_L)
    worst = 0.0
    for p in pts:
        c = M @ p                                          # relative to the head's pivot, in the character's frame
        inside = np.array([c[0] + 5.0, 5.0 - c[0], c[1] + 13.0, 0.5 - c[1], c[2] + 6.0, 6.0 - c[2]])
        if inside.min() > 0:
            worst = max(worst, float(inside.min()))
        inside = np.array([p[0] + 4.5, 4.5 - p[0], p[1] - 0.0, 12.0 - p[1], p[2] + 2.6, 2.6 - p[2]])
        if inside.min() > 0:
            worst = max(worst, float(inside.min()))
    return worst

def two_hands(tq, point, hang=D, lam=9.0, posr=None, posl=None, prior=None, n=120):
    """Both arms (relative to a torso turned [tq]) on the staff, its head along [point] (his frame): the roll of the arm about
    the shaft is picked so the left fist closes on the shaft, nothing goes through his head or chest, and the right arm
    lies as near [hang] as that allows (and, given the right arm's rotation [prior] a moment before, as near that as it
    allows, so the arm doesn't flip round the shaft between two keys). Returns (right rotation, left rotation, how far
    the left fist misses, lam used)."""
    s = unit(point)
    pref = unit(hang)
    e1 = unit(np.cross(s, U if abs(float(s @ U)) < 0.9 else F))
    e2 = np.cross(s, e1)
    M = qmat(tq)
    def trial(rho):
        pvec = math.cos(rho) * e1 + math.sin(rho) * e2
        qr = hold(tq, s, pvec)
        ql, miss, lu = left_on_staff(qr, posr, lam, posl)
        axis = M @ (qmat(qr) @ np.array([0.0, 1.0, 0.0]))
        ang = math.acos(float(np.clip(axis @ pref, -1.0, 1.0)))
        cost = 12.0 * miss + 3.0 * intrusion(tq, qr, ql, posr, posl) + 0.6 * ang
        if prior is not None:
            cost += 1.5 * 2.0 * math.acos(min(1.0, abs(float(np.dot(qr, prior)))))
        return cost, qr, ql, miss, lu
    best = min((trial(2 * math.pi * k / n) + (2 * math.pi * k / n,) for k in range(n)), key=lambda x: x[0])
    for step in (2 * math.pi / n / 4, 2 * math.pi / n / 16):
        centre = best[5]
        for k in range(-4, 5):
            cand = trial(centre + step * k) + (centre + step * k,)
            if cand[0] < best[0]:
                best = cand
    return best[1], best[2], best[3], best[4]

def ground(legs, legz=(0.0, 0.0)):
    """How far down (pixels) the root must sit for the lower foot to just meet the floor with the legs turned [legs] (degrees
    about x) and spread [legz]: the legs are rigid, so a wider stance sinks the body and a narrow one doesn't."""
    low = 0.0
    for a, z in zip(legs, legz):
        m = qmat(qeuler(x=a, z=z))
        for cx in (-2.5, 2.5):
            for cz in (-2.5, 2.5):
                low = max(low, float((m @ np.array([cx, 11.5, cz]))[1]))
    return 11.5 - low

MISS = []      # (clip, tick, how far a fist missed the shaft)

REST_POINT = qmat(rest_arm()) @ SHAFT                      # the staff as he stands with it
REST_HANG = qmat(rest_arm()) @ np.array([0.0, 1.0, 0.0])
DEFAULT = dict(tw=0.0, ln=0.0, tl=0.0, apos=(0.0, 0.0, 0.0), lpos=(0.0, 0.0, 0.0), legs=(0.0, 0.0), legz=(0.0, 0.0), legy=(0.0, 0.0),
               root=(0.0, 0.0, 0.0), sink=0.0, drop=None, point=REST_POINT, hang=REST_HANG, free_dir=(0.0, -90.0), lw=1.0,
               nod=0.0, rootq=I)

def pose(c, t, tw=0.0, ln=0.0, tl=0.0, point=None, hang=None, apos=(0.0, 0.0, 0.0), arm_q=None, free_dir=None, palm=None,
         free_q=None, two=None, lw=1.0, lpos=(0.0, 0.0, 0.0), legs=None, legz=(0.0, 0.0), legy=(0.0, 0.0), root=None, rootq=None,
         nod=None, look=None, ease='inout', sink=0.0, drop=None, tpos=None):
    """Keys the whole body at [t]: the torso (twist, lean, tilt), the staff arm aimed at [point] = (left, up) degrees or a
    direction (his rest pose when None) with the arm lying toward [hang], the free arm along [free_dir] = (left, up) or, with
    [two] (px up the shaft from the right fist), closed on the staff's shaft ([lw] of the way from where it would hang),
    the legs (right, left) degrees about x (turned [legy] degrees about the vertical first, to keep a planted foot pointing
    where it was while the hips turn over it), the root's offset (x, y, z) and turn, and a nod of the head ([look] = (yaw,
    pitch, roll) for more). [arm_q] and [free_q] give an arm's rotation outright. With [legs] the feet are kept on the
    floor: the root's height is worked out from them (plus [sink] pixels, a little give is fine; [drop] sets how far the
    root sinks outright, for a kneel the rigid legs can't show). [ease] is one name for every bone or a dict by bone."""
    e = (lambda b: ease.get(b, ease.get('all', 'inout'))) if isinstance(ease, dict) else (lambda b: ease)
    tq = lean_twist(tw, ln, tl)
    c.key('torso', t, tq, pos=tpos, ease=e('torso'))
    pv = REST_POINT if point is None else (sp(*point) if len(point) == 2 else np.asarray(point, float))
    hv = REST_HANG if hang is None else hang
    ql = None
    if arm_q is not None:
        qr = arm_q
    elif two is not None and free_q is None:
        qr, ql_on, miss, _ = two_hands(tq, pv, hv, two, apos, lpos, prior=getattr(c, 'prior', None))
        MISS.append((c.name, t, miss))
        ql = ql_on
    else:
        qr = hold(tq, pv, hv)
    c.prior = qr
    c.key('right_arm', t, qr, pos=apos if any(apos) else None, ease=e('right_arm'))
    if free_q is not None:
        ql_free = free_q
    elif free_dir is None:
        ql_free = free_arm(D)
    else:
        ql_free = free(tq, sp(*free_dir), palm)
    if ql is None and two is not None:
        ql, miss, _ = left_on_staff(qr, apos, two, lpos)
        MISS.append((c.name, t, miss))
    ql = ql_free if ql is None else (ql if lw >= 1.0 else qslerp(ql_free, ql, lw))
    c.key('left_arm', t, ql, pos=lpos if any(lpos) else None, ease=e('left_arm'))
    rp = np.zeros(3) if root is None else np.asarray(root, float).copy()
    if legs is not None:
        c.key('right_leg', t, qmul(qeuler(y=legy[0]), qeuler(x=legs[0], z=legz[0])), ease=e('legs'))
        c.key('left_leg', t, qmul(qeuler(y=legy[1]), qeuler(x=legs[1], z=legz[1])), ease=e('legs'))
        rp[1] = ground(legs, legz) + sink if drop is None else drop
    c.key('root', t, I if rootq is None else rootq, pos=rp, ease=e('root'))
    if nod is not None or look is not None:
        lk = look if look is not None else (0.0, nod, 0.0)
        c.key('head', t, qeuler(x=lk[1], y=lk[0], z=lk[2]), ease=e('head'))

def vslerp(a, b, x):
    """The direction [x] of the way round from [a] to [b]."""
    a, b = unit(a), unit(b)
    d = float(np.clip(np.dot(a, b), -1.0, 1.0))
    th = math.acos(d)
    if th < 1e-4:
        return a
    return unit(a * math.sin((1 - x) * th) / math.sin(th) + b * math.sin(x * th) / math.sin(th))

def _sunk(P):
    """How far (pixels) the root of the pose arguments [P] sits below where its legs just meet the floor."""
    legs = P.get('legs')
    if P.get('drop') is not None:
        return P['drop'] - (0.0 if legs is None else ground(legs, P.get('legz', (0.0, 0.0))))
    return P.get('sink', 0.0)

def mix(A, B, u):
    """The pose arguments [A] and [B] (dicts of what pose() takes) [u] of the way from one to the other; whatever one lacks
    is where he stands at rest. A root dropped outright ('drop') is carried as how far it sinks below where the legs meet the
    floor, so the feet stay on the floor as the legs turn rather than going through it."""
    out = {}
    for k in set(A) | set(B):
        if k in ('ease', 'drop'):
            continue
        a, b = A.get(k, DEFAULT.get(k, B.get(k))), B.get(k, DEFAULT.get(k, A.get(k)))
        if k == 'two':
            out[k] = a if a is not None else b
            out['lw'] = (0.0 if A.get('two') is None else A.get('lw', 1.0)) * (1 - u) + (0.0 if B.get('two') is None else B.get('lw', 1.0)) * u
            if out[k] is None:
                del out[k]
            continue
        if k == 'lw':
            continue
        if a is None or b is None:
            out[k] = a if b is None else b
        elif k in ('hang', 'point'):
            a = sp(*a) if len(a) == 2 else a
            b = sp(*b) if len(b) == 2 else b
            out[k] = vslerp(a, b, u)
        elif k in ('arm_q', 'free_q', 'rootq'):
            out[k] = qslerp(a, b, u)
        elif isinstance(a, (tuple, list, np.ndarray)):
            out[k] = tuple(float(x) for x in np.asarray(a, float) * (1 - u) + np.asarray(b, float) * u)
        else:
            out[k] = a + (b - a) * u
    if A.get('drop') is not None or B.get('drop') is not None:
        legs = out.get('legs', DEFAULT['legs'])
        sunk = _sunk(A) * (1 - u) + _sunk(B) * u
        out['drop'] = ground(legs, out.get('legz', DEFAULT['legz'])) + sunk
    return out

def ramp(c, t0, A, t1, B, n=3, ease='inout'):
    """Keys the way from pose [A] (already keyed at [t0]) to pose [B] at [t1] with [n] keys between, each on the curve the
    ease [ease] makes of the whole stretch, so one smooth push (or ease) runs through instead of one for each key."""
    for k in range(1, n + 1):
        x = k / (n + 1)
        pose(c, t0 + (t1 - t0) * x, **mix(A, B, EASE[ease](x)), ease='linear')
    pose(c, t1, **B, ease='linear')

# ================================================================== where the staff is, and keeping its butt on the floor

def staff_world(c, t, **skeleton):
    """The staff's butt, fist and head in world space (the root's turn and offset included) for clip [c] at [t]."""
    ps = pose_clip(c, t, **skeleton)
    q, p = ps['right_arm']
    qr, pr = ps['root']
    M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q)
    return [apply(M, pt) for pt in (BUTT_L, GRIP_L, HEAD_L)]

def butt_of(kw):
    """Where the staff's butt is for the pose arguments [kw]."""
    probe = Clip('probe', 2.0, blend_in=0.0, blend_out=0.0)
    pose(probe, 1.0, **kw)
    return staff_world(probe, 1.0)[0]

def plant(kw, floor=24.0, lo=0.0, hi=80.0):
    """The pose [kw] with its lean worked out so the butt of the staff just touches the floor (the lean carries the shoulders
    down and forward, so there is one lean for each stance and staff angle)."""
    from scipy.optimize import brentq
    f = lambda ln: butt_of(dict(kw, ln=ln))[1] - floor
    if f(lo) * f(hi) > 0:
        return dict(kw, ln=min((lo, hi), key=lambda x: abs(f(x))))
    return dict(kw, ln=brentq(f, lo, hi, xtol=0.01))

# ================================================================== bolt: a one-handed thrust at the target, the free hand drawn back
# release 12. The staff is hauled up and back over the shoulder (the body winds to its right and leans away), held a
# beat, then driven down the line to the target: the shoulder, the chest and the lead foot all go with it and the head
# of the staff stops dead, pointing at the target, on the release. The free hand is flung back for balance.
c = rn('bolt', 24, blend_in=2.0, blend_out=6.0).mark('release', 12)
READY = dict(legs=(0, 0))
COCK = dict(tw=24, ln=-8, point=(-8, 76), hang=v(f=0.5, r=0.8, u=-0.3), free_dir=(125, -30), legs=(8, -10), root=(0, 0, 0.8))
STAB = dict(tw=-22, ln=16, point=(5, 2), hang=v(r=0.9, u=-0.5), apos=(0, 0, -1.4), free_dir=(150, -22), legs=(-16, 20), root=(0, 0, -2.0), sink=0.2)
pose(c, 0, **READY)
pose(c, 7, **COCK, ease='out')
pose(c, 9, **mix(READY, COCK, 1.0), ease='linear')
ramp(c, 9, COCK, 12, STAB, n=5, ease='in')
pose(c, 13, **mix(COCK, STAB, 1.04), ease='out')
pose(c, 15, **STAB, ease='inout')
pose(c, 17, **STAB, ease='linear')
pose(c, 24, **READY, ease='inout')

# ================================================================== fan: the staff swept across the front, right to left
# release 16. The arm is held out to his right with the staff forward from the fist, level, and the body's turn does the
# sweeping: wound to the right (a slow draw and a held beat), then whipped through to the left, the head of the staff
# crossing straight ahead on the release at the fastest, and carried on round before it settles.
c = rn('fan', 30, blend_in=2.0, blend_out=6.0).mark('release', 16)
SWEEP = hold(I, sp(0, 12), v(r=0.9, u=-0.45, f=0.1))
WOUND = dict(tw=58, ln=-2, arm_q=SWEEP, free_dir=(35, -35), legs=(10, -12), root=(0, 0, 0.6))
HELD = dict(WOUND, tw=62)
THROUGH = dict(tw=0, ln=5, arm_q=SWEEP, free_dir=(80, -35), legs=(0, 0), root=(0, 0, -0.3))
FAR = dict(tw=-60, ln=6, arm_q=SWEEP, free_dir=(115, -28), legs=(-10, 12), root=(0, 0, -0.5))
pose(c, 0)
pose(c, 8, **WOUND, ease='out')
pose(c, 10, **HELD, ease='linear')
ramp(c, 10, HELD, 16, THROUGH, n=5, ease='in')
ramp(c, 16, THROUGH, 22, FAR, n=5, ease='out')
pose(c, 24, **dict(FAR, tw=-58), ease='out')
pose(c, 30)

# ================================================================== lance: drawn back like a javelin, then a hard thrust
# release 30. The staff is carried back over the right shoulder with its head aimed at the target, the body wound to the
# right, the weight on the back leg and the free hand out in front taking aim; it tightens for twenty ticks (the pose reads
# well held), coils a last notch at 28 and is driven out along the line at 30 with the whole body behind it.
c = rn('lance', 44, blend_in=2.0, blend_out=6.0).mark('release', 30)
DRAW = dict(tw=46, ln=-8, point=(0, 20), hang=v(f=-0.4, u=0.7, r=0.5), apos=(0, 0, 1.2), free_dir=(-4, 8), legs=(24, -26), root=(0, 0, 1.6))
DRAW2 = dict(DRAW, tw=48, ln=-9, point=(0, 22), root=(0, 0, 1.9))
DRAW3 = dict(DRAW, tw=50, ln=-10, point=(0, 24), root=(0, 0, 2.3), legs=(26, -27))
COIL = dict(DRAW, tw=54, ln=-12, point=(0, 26), apos=(0, 0, 2.0), root=(0, 0, 3.0), legs=(27, -28))
THROW = dict(tw=-24, ln=14, point=(2, 6), hang=v(r=0.6, u=0.8), apos=(0, 0, -2.4), free_dir=(75, -40), legs=(32, -14), root=(0, 0, -2.2))
pose(c, 0)
pose(c, 4, **mix({}, DRAW, 0.0), ease='linear')
pose(c, 12, **DRAW, ease='out')
pose(c, 20, **DRAW2, ease='inout')
pose(c, 26, **DRAW3, ease='inout')
pose(c, 28, **COIL, ease='out')
ramp(c, 28, COIL, 30, THROW, n=3, ease='in')
pose(c, 31.5, **mix(COIL, THROW, 1.04), ease='out')
pose(c, 34, **THROW, ease='inout')
pose(c, 37, **THROW, ease='linear')
pose(c, 44)

# ================================================================== blink: a gathering crouch, the mantle swept round
# release 8 (he is gone from here and appears elsewhere on this tick: the crouch is deepest and he is half way round in a
# whirl), 8-18 rising out of the crouch, the staff flung open and the turn finished.
c = rn('blink', 18, blend_in=1.0, blend_out=4.0).mark('gone', 8)
BPREF = v(r=-0.7, f=0.7)
B_ANTIC = dict(tw=16, ln=10, point=sp(-10, 55), hang=BPREF, two=9.0, lw=0.5, free_dir=(40, -20), legs=(-20, 22), legz=(2, -2), sink=1.0, nod=6,
               rootq=qeuler(y=14))
B_PEAK = dict(point=sp(0, 85), hang=BPREF, two=9.0, ln=26, legs=(-50, 52), legz=(3, -3), sink=2.6, nod=14, rootq=qeuler(y=-185))
B_OPEN = dict(tw=0, ln=12, point=sp(-80, 30), hang=v(r=0.9, u=0.2), free_dir=(95, 25), legs=(-22, 24), legz=(2, -2), sink=0.6, nod=2,
              rootq=qeuler(y=-300))
pose(c, 0)
pose(c, 3, **B_ANTIC, ease='out')
pose(c, 5.5, **dict(mix(B_ANTIC, B_PEAK, 0.55), rootq=qeuler(y=-80)), ease='in')
pose(c, 8, **B_PEAK, ease='out')
pose(c, 12, **B_OPEN, ease='out')
pose(c, 18)

# ================================================================== summon: the staff circled overhead, then thrust up
# release 24. Both hands on the staff, raised in front of his face with its head high above his hood, drawing a circle
# in the air (a turn and a half, gathering speed) while he watches it; then it is driven straight up and held.
c = rn('summon', 36, blend_in=2.0, blend_out=6.0).mark('release', 24)
def swirl(turn, **kw):
    """The staff on its cone at [turn] degrees round: the body leans the other way from the staff and the head follows it."""
    d = cone(40, 34, turn)
    side = -float(d @ R)                                   # how far the staff leans to his right (negative) or left
    return dict(point=d, hang=v(r=-0.7, f=0.7), two=9.0, ln=-6, tl=-7 * side, look=(-18 * side, -18, 0), legs=(-6, 8), legz=(3, -3), **kw)
UP = dict(point=sp(0, 93), hang=v(r=-0.4, f=0.7), two=9.0, ln=-12, look=(0, -42, 0), legs=(-4, 5), legz=(3, -3), root=(0, 0, 0.3))
pose(c, 0)
pose(c, 6, **swirl(180), ease='out')
N = 32
for k in range(1, N):
    turn = 180 + 540 * (k / N)
    pose(c, 6 + 16 * k / N, **swirl(turn), ease='linear')
pose(c, 22, **swirl(720), ease='linear')
ramp(c, 22, swirl(720), 24, UP, n=3, ease='in')
pose(c, 25.5, **dict(UP, point=sp(0, 96), ln=-14, look=(0, -46, 0)), ease='out')
pose(c, 29, **UP, ease='inout')
pose(c, 31, **UP, ease='linear')
pose(c, 36)

# ================================================================== starfall: the staff lifted to the sky, then slammed down
# release 34. Both hands raise the staff slowly (0-30), the body arching back with the face turned up to call the star down,
# held a heartbeat; then the whole body comes down on it and the butt hits the floor on the release, held, and he straightens
# up out of it (44-56).
c = rn('starfall', 56, blend_in=2.0, blend_out=8.0).mark('lifted', 30).mark('release', 34)
PREF = v(r=-0.7, f=0.7)
MID = dict(point=sp(0, 50), hang=PREF, two=9.0, ln=-8, nod=-18, legs=(-4, 5), tw=0)
LIFT = dict(point=sp(0, 98), hang=PREF, two=9.0, ln=-22, nod=-60, legs=(-6, 8), root=(0, 0, 0.6))
LIFT2 = dict(LIFT, point=sp(0, 102), ln=-25, nod=-64, root=(0, 0, 0.9))
SLAM = plant(dict(point=sp(0, 55), hang=v(f=0.7, u=-0.7), two=9.0, nod=14, legs=(66, -68), legz=(4, -4), drop=7.0, root=(0, 0, 0.5), apos=(0, 1.5, -0.5)), floor=23.6)
REBOUND = dict(SLAM, ln=SLAM['ln'] - 5, nod=2, drop=6.0)
pose(c, 0)
ramp(c, 0, {}, 14, MID, n=6, ease='in')
ramp(c, 14, MID, 30, LIFT, n=7, ease='out')
pose(c, 32, **LIFT2, ease='inout')
ramp(c, 32, LIFT2, 34, SLAM, n=3, ease='in')
pose(c, 35.5, **REBOUND, ease='out')
pose(c, 38, **SLAM, ease='inout')
pose(c, 44, **SLAM, ease='inout')
ramp(c, 44, SLAM, 56, {}, n=5, ease='inout')

# ================================================================== strike: a quick backhand swipe at someone close
# blow 9. The staff is swept across his body to the left, then whipped back out to his right at whoever is in front of him,
# the head of the staff crossing the line to the target on the blow and carrying on.
c = rn('strike', 20, blend_in=1.0, blend_out=5.0).mark('blow', 9)
CROSS = hold(I, sp(8, 40), v(r=-0.6, u=-0.8))
OPEN = hold(I, sp(-8, 22), v(r=0.9, u=-0.4))
WIND = dict(tw=-40, ln=3, arm_q=CROSS, free_dir=(50, -25), legs=(-10, 12), root=(0, 0, -0.4))
BLOW = dict(tw=2, ln=10, arm_q=qslerp(CROSS, OPEN, 0.55), free_dir=(85, -30), legs=(-16, 18), root=(0, 0, -1.2))
FOLLOW = dict(tw=40, ln=8, arm_q=OPEN, free_dir=(110, -20), legs=(-12, 14), root=(0, 0, -1.0))
pose(c, 0)
pose(c, 4.5, **WIND, ease='out')
ramp(c, 4.5, WIND, 9, BLOW, n=4, ease='in')
ramp(c, 9, BLOW, 12, FOLLOW, n=2, ease='out')
pose(c, 20)

# ================================================================== shove: a palm thrust of the free left hand
# blow 10. The staff is held up at his side out of the way; the free left arm is swung back and out to his left, the body winding
# away from it, then the body turns into the thrust and the arm sweeps round and is driven out, straight, at the chest of whoever
# is in front of him; held a beat at the full reach, then drawn back.
c = rn('shove', 24, blend_in=1.5, blend_out=6.0).mark('blow', 10)
WIND = dict(tw=-28, ln=0, point=(-20, 75), hang=v(f=0.3, r=0.9, u=-0.2), free_dir=(105, 14), legs=(8, -10), root=(0, 0, 0.6))
BLOW = dict(tw=26, ln=11, point=(-30, 62), hang=v(f=0.3, r=0.9, u=-0.2), free_dir=(0, 8), legs=(12, -16), root=(0, 0, -2.4))
pose(c, 0)
pose(c, 6, **WIND, ease='out')
pose(c, 7.5, **dict(WIND, tw=-31, root=(0, 0, 0.9), free_dir=(110, 16)), ease='linear')
ramp(c, 7.5, dict(WIND, tw=-31, root=(0, 0, 0.9), free_dir=(110, 16)), 10, BLOW, n=3, ease='in')
pose(c, 11.5, **dict(BLOW, tw=29, ln=12, root=(0, 0, -2.8), free_dir=(-2, 10)), ease='out')
pose(c, 14, **BLOW, ease='inout')
pose(c, 17, **mix(BLOW, {}, 0.3), ease='inout')
pose(c, 24)

# ================================================================== intro: turning to the player, his piece, the staff raised as the stones wake
# 108 ticks, timed to renegade_intro.ogg, which starts at tick 6 (the game plays the clip from tick 0 as he wakes, with the body
# already snapped to face the player, so the clip opens with the model turned back toward his stones and brings it round):
#   0-16    he turns from his stones to the player, the head first, then the shoulders, then the hips; the feet step round
#           (the left, then the right), each foot standing where it was put until it is lifted
#   6-47    "Idris sent you? Of course he did.": a small contemptuous tilt of the head and a lift of the chin on "you?"; the
#           free left hand rises and opens, palm up, toward the player on "Of course he did" (open by "course", 32, pushed
#           out a little on "did", 41)
#   47-53   the pause: held still
#   53-94   "He never could bear to look at the dark himself.": the arms spread wide, the staff held out to his right side, the
#           body leaning back and the head tipping slowly back to the sky, furthest back at 81 on "the dark" (held to 84)
#   84-96   the staff swept up high overhead in the right hand on "himself", the left hand rising too: it arrives at the
#           top at 90, the tick the stones wake (the sound and the shake), and stays up until 96
#   96-106  the staff brought down into his ready stance, the way he stands when the fight begins
# The feet are worked out: each foot is given a world position and heading and the legs tilt to reach it, so a planted foot
# does not slide as the hips turn or shift over it.
c = rn('intro', 108, blend_in=0.0, blend_out=6.0).mark('faces you', 16).mark('hand open', 32).mark('pause', 47) \
    .mark('arms wide', 66).mark('head back', 81).mark('staff up', 90).mark('ready', 106)
FOOT_R = 1.9                                                 # the feet stand on the circle the hips turn on
TURN = 72.0                                                  # degrees round to his right he is facing at tick 0
STEP = 3.4                                                   # how far (px) a stepping foot swings out wide of the hips' circle at mid-step
STANCE_L, STANCE_R = (FOOT_R, 0.0, 0.0), (-FOOT_R, 0.0, 0.0)  # world (x, z, heading) of the feet as he stands to face the player

def ss(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3.0 - 2.0 * x)

def foot_at(side, a):
    """Where a foot stands [a] degrees round the hips' circle from straight out to his side (x, z)."""
    s = 1.0 if side == 'left' else -1.0
    return np.array([s * FOOT_R * math.cos(math.radians(a)), -s * FOOT_R * math.sin(math.radians(a))])

def root_matrix(psi, off):
    q = qeuler(y=psi)
    return T(*(HIPS - qmat(q) @ HIPS + np.asarray(off, float))) @ Rq(q)

def leg_to(M, side, xz, yaw):
    """The leg's swing and sideways tilt (degrees) that put the sole of the [side] foot at world (x, z) = [xz] with the leg turned
    [yaw] about the vertical, given the root's matrix [M]; and the sole's drop below the hip."""
    from scipy.optimize import least_squares
    pivot = np.array(PIVOTS[side + '_leg'], float)
    down = np.array([0.0, 12.0, 0.0])
    def sole(a):
        return apply(M, pivot + qmat(qmul(qeuler(y=yaw), qeuler(x=a[0], z=a[1]))) @ down)
    a = least_squares(lambda a: [sole(a)[0] - xz[0], sole(a)[2] - xz[1]], [0.0, 0.0], xtol=1e-12, ftol=1e-12).x
    return float(a[0]), float(a[1]), float((qmat(qmul(qeuler(y=yaw), qeuler(x=a[0], z=a[1]))) @ down)[1])

def feet(psi, off, left, right):
    """The leg arguments of pose() that put the feet at world (x, z, heading) [left] and [right] with the hips turned [psi] and
    offset [off]: the legs tilt to reach them and the root sinks until the lower foot just meets the floor."""
    M = root_matrix(psi, off)
    r = leg_to(M, 'right', right[:2], right[2] - psi)
    l = leg_to(M, 'left', left[:2], left[2] - psi)
    return dict(legs=(r[0], l[0]), legz=(r[1], l[1]), legy=(right[2] - psi, left[2] - psi), drop=12.0 - max(r[2], l[2]))

def key(t, psi=0.0, off=(0.0, 0.0), left=STANCE_L, right=STANCE_R, ease='linear', **kw):
    """A key of the intro with the feet worked out: [psi] the hips' turn, [off] the root's (x, z) offset, [left] and [right] the feet."""
    o = (off[0], 0.0, off[1])
    pose(c, t, rootq=None if psi == 0 else qeuler(y=psi), root=o, ease=ease, **feet(psi, o, left, right), **kw)

def keyed(t, P, ease='linear'):
    P = dict(P)
    r = P.pop('root', (0.0, 0.0, 0.0))
    key(t, off=(r[0], r[2]), ease=ease, **P)

def ramped(t0, A, t1, B, n=3, ease='inout'):
    """As ramp(), the keys between pose [A] (keyed at [t0]) and [B] (keyed at [t1]) on one curve, the feet worked out."""
    for k in range(1, n + 1):
        x = k / (n + 1)
        keyed(t0 + (t1 - t0) * x, mix(A, B, EASE[ease](x)))
    keyed(t1, B)

# ---- 0-16: the turn. The head leads, the shoulders follow, then the hips; each foot is planted until it steps (left 4.5-8.5,
# right 8.5-12.5), swung out wide of the hips' circle (so the leg tilts and the sole lifts) as it comes round, and the
# weight rides over the other foot.
def step(t, t0):
    return min(1.0, max(0.0, (t - t0) / 4.0))

for k in range(0, 33):
    t = k * 0.5
    psi = TURN * (1 - ss((t - 2.5) / 13.5))
    shoulders = TURN * (1 - ss((t - 1.6) / 11.5))
    head = TURN * (1 - ss((t - 0.6) / 5.8))
    feetnow = {}
    for side, t0 in (('left', 4.5), ('right', 8.5)):
        s = step(t, t0)
        a = TURN * (1 - ss(s))
        p = foot_at(side, a)
        p = p * (1.0 + STEP / FOOT_R * math.sin(math.pi * s))        # swung out wide of the hips' circle on its way, so lifted
        feetnow[side] = (float(p[0]), float(p[1]), a)
    shift = -0.7 * math.sin(math.pi * step(t, 4.5)) + 0.7 * math.sin(math.pi * step(t, 8.5))
    key(t, psi=psi, off=(shift, 0.0), left=feetnow['left'], right=feetnow['right'], tw=shoulders - psi, look=(head - psi, 0.0, 0.0))

# ---- 16-53: speech one. The head cocks and the chin lifts on "you?"; the left hand rises in the breath before "Of course" and
# is open, palm up, by "course"; the head dips and the hand pushes out a little on "did"; the pause is held.
def talk(t, look, left=None, palm=None, ln=0.0, tw=0.0, ease='inout', **kw):
    key(t, look=look, free_dir=left, palm=palm, ln=ln, tw=tw, ease=ease, **kw)
talk(20.5, (-1, -2, 5), ln=-0.5, tw=-1)
talk(24, (-3, -5, 9), ln=-1.5, tw=-3)
talk(26.5, (-3, -5, 9), left=(14, -62), ln=-1.5, tw=-3)
talk(29.5, (-3, -3, 9), left=(28, -22), ln=-1.8, tw=-5)
talk(32, (-4, -2, 11), left=(37, 4), palm=U, ln=-2, tw=-8)
talk(36.5, (-3, -3, 10), left=(39, 6), palm=U, ln=-2, tw=-8)
talk(39, (-3, -1, 10), left=(39, 6), palm=U, ln=-2.2, tw=-8)
talk(41.5, (-3, 3, 8), left=(42, 10), palm=U, ln=-1.5, tw=-8)
talk(44.5, (-2, 1, 7), left=(38, 6), palm=U, ln=-1.2, tw=-6)
talk(47, (-2, 0, 6), left=(37, 5), palm=U, ln=-1, tw=-5)
talk(52.5, (-1, 0, 5), left=(36, 4), palm=U, ln=-1, tw=-4)

# ---- 53-96: speech two. The arms spread (the staff leaning out at his right, the left hand out wide, palm up), the body leans
# back and the head tips slowly back to the sky; on "himself" the staff sweeps up and over his head and the left hand rises.
S0 = dict(point=REST_POINT, hang=REST_HANG, free_dir=(36, 4), palm=U, ln=-1.0, tw=-4.0, look=(-1, 0, 5), root=(0, 0, 0))
W1 = dict(point=sp(-35, 70), hang=v(r=1.0, u=-0.25), free_dir=(92, 8), palm=U, ln=-4.0, tw=0.0, look=(0, -6, 0), root=(0, 0, -0.3))
W3 = dict(point=sp(-44, 64), hang=v(r=1.0, u=-0.2), free_dir=(96, 16), palm=U, ln=-12.0, tw=0.0, look=(0, -44, 0), root=(0, 0, -1.0))
WIND = dict(W3, point=sp(-56, 56), hang=v(r=1.0, u=-0.4), free_dir=(90, 8))
OVER = dict(point=sp(45, 52), hang=v(r=0.55, u=0.8, f=0.15), free_dir=(78, 52), palm=U, ln=-16.0, tw=0.0, look=(0, -46, 0), root=(0, 0, -1.4))
keyed(53, S0, ease='inout')
ramped(53, S0, 66, W1, n=5, ease='out')          # "He never could": the arms spread
ramped(66, W1, 81, W3, n=6, ease='inout')        # "bear to look at the dark": leaning back, the head tipping back, furthest on "dark" (81)
keyed(83.5, WIND, ease='inout')                  # the breath after "dark": the staff dips out ready to swing
ramped(83.5, WIND, 90, OVER, n=5, ease='in')     # "himself": swept up and over, arriving at 90 as the stones wake
keyed(91, mix(WIND, OVER, 1.06), ease='out')
keyed(92.5, OVER, ease='inout')
keyed(96, OVER, ease='linear')
# the staff comes down into the stance he fights from, the arm easy and the staff carried before him
READY = dict(point=REST_POINT, hang=REST_HANG, free_dir=(0, -90), ln=0.0, tw=0.0, look=(0, 0, 0), root=(0, 0, 0))
ramped(96, OVER, 106, READY, n=6, ease='inout')
keyed(108, READY)

# ================================================================== death: a stagger, on his knees, slumped over the staff
# The blow rocks him back with the staff flung out; his legs go and he is on his knees at 20 with the staff in both hands; as
# he folds forward he drives its butt into the floor (27) and hangs over it, both hands on the shaft, the head bowed, still by 50.
# The rigid legs lie back along the floor for the kneel (the shins sink a pixel or two into it, as Halvard's kneel does).
c = rn('death', 60, blend_in=3.0, blend_out=0.0).mark('knees', 20).mark('planted', 27).mark('slumped', 50)
c.mode('head', 'set')

def planted(kw, prior=None, depth=0.4, lo=55.0, hi=130.0):
    """The pose [kw] with the staff's elevation worked out so its butt is [depth] px into the floor (the bent-over body
    brings the hands down near it; there is one elevation for each lean)."""
    from scipy.optimize import brentq
    def f(e):
        probe = Clip('probe', 2.0, blend_in=0.0, blend_out=0.0)
        probe.prior = prior
        pose(probe, 1.0, **dict(kw, point=sp(0, e)))
        return staff_world(probe, 1.0)[0][1] - (24.0 + depth)
    a, b = f(lo), f(hi)
    if a * b > 0:
        return dict(kw, point=sp(0, min((lo, hi), key=lambda x: abs(f(x)))))
    return dict(kw, point=sp(0, brentq(f, lo, hi, xtol=0.01)))

DPREF = v(f=0.7, u=-0.7)
STAGGER = dict(ln=-20, nod=-14, point=sp(-70, 40), hang=v(f=0.3, r=0.9, u=0.2), free_dir=(105, 25), legs=(14, -10), root=(0, 0, 1.6))
BUCKLE = dict(ln=-2, nod=8, point=sp(-20, 60), hang=v(f=0.3, r=0.9, u=-0.2), free_dir=(40, -15), legs=(36, 30), drop=3.5, root=(0, 0, 0.6))
KNEES = dict(ln=24, nod=14, point=sp(0, 80), hang=DPREF, two=9.0, legs=(70, 66), legz=(3, -3), drop=8.4, root=(0, 0, 0.0), apos=(0, 2.0, 0))
LOW = dict(KNEES, drop=8.5, nod=8)
pose(c, 0, nod=0)
pose(c, 5, **STAGGER, ease='out')
pose(c, 12, **BUCKLE, ease='inout')
pose(c, 20, **KNEES, ease='in')
PLANT = planted(dict(LOW, ln=46, nod=8), prior=c.prior)
ramp(c, 20, KNEES, 27, PLANT, n=4, ease='in')
SAG = planted(dict(LOW, ln=50, nod=12), prior=c.prior)
pose(c, 36, **SAG, ease='inout')
SLUMP = planted(dict(LOW, ln=55, nod=14), prior=c.prior)
pose(c, 50, **SLUMP, ease='out')
pose(c, 60, **planted(dict(LOW, ln=57, nod=15), prior=c.prior), ease='inout')

# ================================================================== checks

def angles(d):
    """A direction as (degrees to his left of straight ahead, degrees above level): negative is to his right."""
    d = unit(d)
    return (math.degrees(math.atan2(d[0], -d[2])), math.degrees(math.asin(float(np.clip(-d[1], -1, 1)))))

def report(clip, ticks=None):
    """Where the staff is at the ticks that matter (the marks, and a few either side): the head's position (x right of
    centre is negative, y up is negative, z forward is negative), which way the staff points, how fast the head moves,
    how high the butt is above the floor, and how far the left fist misses the shaft."""
    lines = []
    marks = sorted(set(round(t) for t in clip.marks.values()))
    if ticks is None:
        ticks = sorted(set(k for m in marks for k in (m - 3, m - 2, m - 1, m, m + 1, m + 2, m + 3) if 0 <= k <= clip.length))
    for t in ticks:
        b, g, h = staff_world(clip, t)
        h0 = staff_world(clip, t - 0.5)[2]
        h1 = staff_world(clip, t + 0.5)[2]
        speed = float(np.linalg.norm(h1 - h0))
        lf, up = angles(h - g)
        miss = [m for n, tt, m in MISS if n == clip.name and abs(tt - t) < 0.01]
        label = ' '.join(n for n, mt in clip.marks.items() if abs(mt - t) < 0.01)
        lines.append(f'{clip.name:9s} t={t:5.1f} head ({h[0]:6.1f},{h[1]:6.1f},{h[2]:6.1f}) points {lf:6.1f} left {up:6.1f} up  '
                     f'speed {speed:5.1f}/tick  butt {24 - b[1]:5.1f} above floor  {label}' + (f'  miss {miss[0]:.2f}' if miss else ''))
    return lines

# ================================================================== previews

class RenegadeScene(MeshScene):
    """The renegade's own model and texture (RenegadeModel.kt) posed by the clip, with the staff in his right hand. The
    mantle and its hem hang as the model's setupAnim hangs them: off his back, swung out by a long stride and kept hanging
    straight down when he bends back."""

    def __init__(self, kotlin, texture, staff, ground=True):
        super().__init__(kotlin, 'RenegadeModel', texture, staff, ground=False)
        self.geo = G
        self.k = texture.width // 128                      # the texture is shipped at twice the size the model declares
        self.floor_on = ground
        self.floor = np.zeros((4, 4, 4), float); self.floor[...] = (74, 80, 96, 255)

    def child_rot(self, name, rot, pose):
        stride = max(abs(euler_x(pose['right_leg'][0])), abs(euler_x(pose['left_leg'][0])))
        if name == 'mantle':
            return (math.degrees(max(0.105 + stride * 0.4, -euler_x(pose['body'][0]) + 0.06)), 0, 0)
        if name.startswith('hem_'):
            return (math.degrees(0.03 + stride * 0.3), 0, rot[2])
        return rot

    def player_world(self, pose, sword=True):
        quads = []
        k = self.k
        for name, (_, _, _, boxes) in self.parts.items():
            M = self.matrix(name, pose)
            for origin, size, uv, g in boxes:
                for A, Bq, Dq, rect, face in box_quads(origin, size, uv, g):
                    quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), tuple(k * r for r in rect), FACE_LIGHT[face], self.skin))
        if sword:
            q, p = pose['right_arm']
            qr, pr = pose['root']
            M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q) @ S(16) @ G.tp_item()
            for A, Bq, Dq, rect, face in sprite_quads(size=self.sword.shape[1]):
                quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), rect, 0.95, self.sword))
        if self.floor_on:
            P = lambda x, z: np.array([x, 24.0, z])
            quads.append((P(-26, -26), P(26, -26), P(-26, 26), (0, 0, 4, 4), 1.0, self.floor))
        return quads

    def staff_points(self, pose):
        """The staff's butt, grip and head in world space."""
        q, p = pose['right_arm']
        qr, pr = pose['root']
        M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q)
        return [apply(M, pt) for pt in (BUTT_L, GRIP_L, HEAD_L)]

    def sword_points(self, pose):
        b, g, h = self.staff_points(pose)
        return g, h

def load_scene(assets):
    from PIL import Image
    kotlin = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../src/main/kotlin/io/github/vaspyyy/guildmark/client/model/RenegadeModel.kt')
    textures = os.path.join(assets, 'textures')
    return RenegadeScene(kotlin, Image.open(os.path.join(textures, 'entity/renegade.png')), Image.open(os.path.join(textures, 'item/amethyst_staff.png')))

VIEWS = {'front': (0, 0), 'three': (35, 14), 'side': (90, 0), 'top': (0, 90), 'back': (180, 0), 'left': (-90, 0)}
PAN = {'side': (0.0, 0.0, -9.0), 'left': (0.0, 0.0, 9.0)}      # the side views are moved along so the staff in front of him is in the frame

def view_project(view, size, zoom, center_y):
    """The orthographic projection of [view] ('front', 'three', 'side', 'top', 'back', 'left') into a picture of [size]."""
    yaw, pitch = VIEWS[view]
    base = ortho(yaw, pitch, zoom, size, center_y if pitch < 45 else 0.0)
    pan = PAN.get(view)
    if pan is None:
        return base
    pan = np.array(pan)
    return lambda p: base(np.asarray(p, float) - pan)

def board(scene, clip, times, views=('front', 'three', 'side'), cell=(200, 250), zoom=4.6, center_y=8.0, title=None, path=True):
    """Stills of [clip] at [times], one row for each view; the yellow line is the staff head's path so far."""
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 18), (30, 32, 40))
    d = ImageDraw.Draw(out)
    d.text((4, 3), title or clip.name, fill=(235, 235, 235))
    trail = []
    k = 0
    while k / 4 <= clip.length + 1e-6:
        pose = pose_clip(clip, k / 4, **scene.skeleton)
        trail.append((k / 4, scene.staff_points(pose)[2]))
        k += 1
    for i, t in enumerate(times):
        pose = pose_clip(clip, t, **scene.skeleton)
        scene.at(clip, t)
        quads = scene.player_world(pose)
        for j, name in enumerate(views):
            pitch = VIEWS[name][1]
            project = view_project(name, (W, H), zoom, center_y)
            img = raster(quads, project, (W, H))
            dr = ImageDraw.Draw(img)
            if abs(pitch) < 1:
                y = project((0, 24, 0))[1]
                dr.line([(0, y), (W, y)], fill=(120, 170, 120), width=1)
            if path:
                pts = [tuple(project(p)[:2]) for tt, p in trail if tt <= t + 1e-6]
                if len(pts) > 1:
                    dr.line(pts, fill=(255, 200, 80), width=1)
            out.paste(img, (i * W, 18 + j * H))
        label = f't={t:g}'
        for nm, mt in clip.marks.items():
            if abs(mt - t) < 0.26:
                label += ' ' + nm
        d.text((i * W + 4, 21), label, fill=(255, 255, 255))
        d.line([(i * W, 18), (i * W, H * len(views) + 18)], fill=(20, 20, 26))
    return out

def flipbook(scene, clip, view='front', step=1.0, cols=12, cell=(110, 150), zoom=2.6, center_y=6.0, title=None):
    """Every [step] ticks of [clip] in one view, row after row: to spot a pop, a flip or a limb through the body."""
    W, H = cell
    times = [k * step for k in range(int(clip.length / step) + 1)]
    rows = (len(times) + cols - 1) // cols
    out = Image.new('RGB', (W * cols, H * rows + 14), (30, 32, 40))
    d = ImageDraw.Draw(out)
    d.text((4, 2), title or f'{clip.name} ({view})', fill=(235, 235, 235))
    pitch = VIEWS[view][1]
    for i, t in enumerate(times):
        pose = pose_clip(clip, t, **scene.skeleton)
        scene.at(clip, t)
        project = view_project(view, (W, H), zoom, center_y)
        img = raster(scene.player_world(pose), project, (W, H))
        if abs(pitch) < 1:
            y = project((0, 24, 0))[1]
            ImageDraw.Draw(img).line([(0, y), (W, y)], fill=(120, 170, 120), width=1)
        ImageDraw.Draw(img).text((3, 2), f'{t:g}', fill=(255, 255, 255))
        out.paste(img, ((i % cols) * W, 14 + (i // cols) * H))
    return out

def preview(assets, name, times=None, **kw):
    scene = load_scene(assets)
    clip = next(c for c in clips if c.name == name)
    if times is None:
        times = [clip.length * i / 8 for i in range(9)]
    return board(scene, clip, times, **kw)

def sheet_times(clip, n=11):
    """The ticks of a clip's contact sheet: [n] evenly spread, and the marked ticks (the release, the blow) with their neighbours."""
    ts = {round(clip.length * i / (n - 1), 1) for i in range(n)}
    ts |= {float(m) for m in clip.marks.values()}
    return sorted(ts)

def gif(scene, names, path, views=('three', 'side'), cell=(210, 270), zoom=4.0, hold=8):
    """An animated GIF of the clips [names] one after the other, a frame for each tick (the game's own rate is twenty ticks
    a second), each held a moment on its last frame; the marked ticks are called out."""
    W, H = cell
    frames = []
    for name in names:
        clip = next(c for c in clips if c.name == name)
        k = 0
        while k <= clip.length:
            pose = pose_clip(clip, float(k), **scene.skeleton)
            scene.at(clip, float(k))
            quads = scene.player_world(pose)
            img = Image.new('RGB', (W * len(views), H + 16), (30, 32, 40))
            for j, view in enumerate(views):
                pitch = VIEWS[view][1]
                project = view_project(view, (W, H), zoom, 10.0)
                cellimg = raster(quads, project, (W, H))
                if abs(pitch) < 1:
                    y = project((0, 24, 0))[1]
                    ImageDraw.Draw(cellimg).line([(0, y), (W, y)], fill=(120, 170, 120), width=1)
                img.paste(cellimg, (j * W, 16))
            label = f'{name}  t={k}'
            marks = [m for m, mt in clip.marks.items() if abs(mt - k) < 0.51]
            d = ImageDraw.Draw(img)
            d.text((4, 3), label + ('  ' + ' '.join(marks).upper() if marks else ''), fill=(255, 255, 160) if marks else (235, 235, 235))
            frames.append(img)
            k += 1
        frames += [frames[-1]] * hold
    pal = Image.new('RGB', (frames[0].width, frames[0].height * 4))
    for n in range(4):
        pal.paste(frames[(len(frames) - 1) * n // 3], (0, n * frames[0].height))
    pal = pal.quantize(colors=254, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    out = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    out[0].save(path, save_all=True, append_images=out[1:], duration=50, loop=0, optimize=False, disposal=1)
    return len(frames)

if __name__ == '__main__':
    assets, previews = sys.argv[1:3]
    only = sys.argv[3:]
    unknown = set(only) - {c.name for c in clips}
    if unknown:
        raise SystemExit(f'no such clip: {sorted(unknown)}')
    os.makedirs(previews, exist_ok=True)
    for c in clips:
        print(c.write(assets, 'renegade'))
    scene = load_scene(assets)
    for c in clips:
        if only and c.name not in only:
            continue
        for line in report(c):
            print(line)
        board(scene, c, sheet_times(c)).save(os.path.join(previews, f'renegade_{c.name}.png'))
    off = [(n, t, round(m, 2)) for n, t, m in MISS if m > 0.5]
    print('fists off the shaft by more than half a pixel:', off if off else 'none')
    if not only:
        n = gif(scene, [c.name for c in clips], os.path.join(previews, 'renegade.gif'))
        print(f'renegade.gif: {n} frames')
