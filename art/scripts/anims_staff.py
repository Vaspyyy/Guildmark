"""
The staff's casts: sorceries cast with a staff held in one hand, the head (the crystal) leading each cast. Third-person
clips pose the skeleton; first-person clips move the held staff (item). Run:

    python3 -B art/scripts/anims_staff.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <amethyst_staff.png>

Timings match the clip table of the magic design: a clip lasts as many ticks as its cast, and the spell leaves the
staff's head on the release tick. There is no hit window: what matters is the pose at the release (the greatest intent)
and, for the charged casts, the pose at chargeAt, which the game freezes while the button is held.

How the staff sits: held in one hand a third of the way up, the shaft square to the forearm (like a fist round a pole).
So the arm can only hang, or reach forward or out to the side, while the head goes up or forward; a cast is the arm
swinging the staff round the shoulder and the body driving the hand (torso twist and lean, a small step of the root).
The helpers below aim the head in the character's frame and work out the arm from the torso, so the head stays on its
line through the twist. Casts are lighter than weapon swings: no lunges, the arm and the chest lead and the body
follows. The free (left) arm stays modest and low, since the game mirrors these clips to cast from the off hand, where
that arm may hold a sword.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import STAFF as G

def tp(name, length, **kw):
    return Clip('staff/' + name, length, **kw)

clips, fps = [], []

# ------------------------------------------------------------------ aiming helpers

_ANG = math.acos(float(G.tp_blade[1]))        # the angle between the arm's axis and the staff in the fist
_COS, _SIN = math.cos(_ANG), math.sin(_ANG)

def sp(left=0.0, up=0.0):
    """A direction for the staff's head, in the character's frame: [left] degrees to its left, [up] degrees above level
    (past 90 it leans back)."""
    l, u = math.radians(left), math.radians(up)
    return v(f=math.cos(u) * math.cos(l), u=math.sin(u), r=-math.cos(u) * math.sin(l))

def lean_twist(twist=0.0, lean=0.0, tilt=0.0):
    """The torso: [twist] degrees to the character's right (the right shoulder back), [lean] forward, [tilt] to the left."""
    return qmul(qmul(qeuler(z=tilt), qeuler(y=twist)), qeuler(x=lean))

def hold(tq, point, hang=D):
    """The right arm (relative to a torso turned [tq]) holding the staff with its head exactly along [point] (character
    frame) and the arm pointing as near [hang] as the grip allows (the shaft is square to the forearm, give or take a
    few degrees, so [hang] should be roughly square to [point])."""
    s = unit(point)
    h = np.asarray(hang, float)
    p = h - np.dot(h, s) * s
    if np.linalg.norm(p) < 1e-6:
        p = np.cross(s, R)
    d = _COS * s + _SIN * unit(p)
    M = qmat(tq).T
    return arm(M @ d, M @ s, geo=G)

def free(tq, direction, palm=None):
    """The free left arm (relative to a torso turned [tq]) pointing along [direction] in the character's frame."""
    M = qmat(tq).T
    return free_arm(M @ unit(direction), None if palm is None else M @ unit(palm))

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F, geo=G)

def ground(legs, legz=(0.0, 0.0)):
    """How far down (pixels) the root must sit for the lower foot to just meet the floor with the legs turned [legs]
    (degrees about x) and spread [legz]: the legs are rigid, so a wider stance sinks the body and a narrow one doesn't."""
    low = 0.0
    for a, z in zip(legs, legz):
        m = qmat(qeuler(x=a, z=z))
        for cx in (-2.0, 2.0):
            for cz in (-2.0, 2.0):
                low = max(low, float((m @ np.array([cx, 12.0, cz]))[1]))
    return 12.0 - low

def pose(c, t, tw=0.0, ln=0.0, tl=0.0, point=None, hang=D, apos=(0.0, 0.0, 0.0), arm_q=None, free_dir=None, palm=None,
         free_q=None, legs=None, legz=(0.0, 0.0), root=None, rootq=None, nod=None, ease='inout', sink=0.0):
    """Keys the whole body at [t]: the torso (twist, lean, tilt), the staff arm aimed at [point] = (left, up) degrees
    (the rest pose when None), the free arm along [free_dir] = (left, up), the legs (right, left) degrees about x, the
    root's offset (x, y, z) and turn, and a nod of the head on top of where the player looks. [arm_q] and [free_q] give an
    arm's rotation outright (relative to the torso) instead. With [legs] the feet are kept on the floor: the root's
    height is worked out from them (plus [sink] pixels, a little give is fine) and [root]'s y is not used. [ease] is one
    name for every bone or a dict by bone."""
    e = (lambda b: ease.get(b, ease.get('all', 'inout'))) if isinstance(ease, dict) else (lambda b: ease)
    tq = lean_twist(tw, ln, tl)
    c.key('torso', t, tq, ease=e('torso'))
    if arm_q is not None:
        c.key('right_arm', t, arm_q, pos=apos, ease=e('right_arm'))
    elif point is None:
        c.key('right_arm', t, rest_arm(), pos=apos, ease=e('right_arm'))
    else:
        c.key('right_arm', t, hold(tq, sp(*point), hang), pos=apos, ease=e('right_arm'))
    if free_q is not None:
        c.key('left_arm', t, free_q, ease=e('left_arm'))
    elif free_dir is None:
        c.key('left_arm', t, free_arm(D), ease=e('left_arm'))
    else:
        c.key('left_arm', t, free(tq, sp(*free_dir), palm), ease=e('left_arm'))
    rp = np.zeros(3) if root is None else np.asarray(root, float).copy()
    if legs is not None:
        c.key('right_leg', t, qeuler(x=legs[0], z=legz[0]), ease=e('legs'))
        c.key('left_leg', t, qeuler(x=legs[1], z=legz[1]), ease=e('legs'))
        rp[1] = ground(legs, legz) + sink
    c.key('root', t, I if rootq is None else rootq, pos=rp, ease=e('root'))
    if nod is not None:
        c.key('head', t, qeuler(x=nod), ease=e('head'))

def vslerp(a, b, x):
    """The direction [x] of the way round from [a] to [b]."""
    a, b = unit(a), unit(b)
    d = float(np.clip(np.dot(a, b), -1.0, 1.0))
    th = math.acos(d)
    if th < 1e-4:
        return a
    return unit(a * math.sin((1 - x) * th) / math.sin(th) + b * math.sin(x * th) / math.sin(th))

def mix(A, B, u):
    """The pose arguments [A] and [B] (dicts of what pose() takes) [u] of the way from one to the other."""
    out = {}
    for k in set(A) | set(B):
        if k == 'ease':
            continue
        a, b = A.get(k, B.get(k)), B.get(k, A.get(k))
        if a is None or b is None:
            out[k] = a if b is None else b
        elif k == 'hang':
            out[k] = vslerp(a, b, u)
        elif k in ('arm_q', 'free_q', 'rootq'):
            out[k] = qslerp(a, b, u)
        elif isinstance(a, (tuple, list, np.ndarray)):
            out[k] = tuple(float(x) for x in np.asarray(a, float) * (1 - u) + np.asarray(b, float) * u)
        else:
            out[k] = a + (b - a) * u
    return out

def ramp(c, t0, A, t1, B, n=3, ease='inout'):
    """Keys the way from pose [A] (already keyed at [t0]) to pose [B] at [t1] with [n] keys between, each on the curve the
    ease [ease] makes of the whole stretch, so one smooth push (or ease) runs through instead of one for each key."""
    for k in range(1, n + 1):
        x = k / (n + 1)
        pose(c, t0 + (t1 - t0) * x, **mix(A, B, EASE[ease](x)), ease='linear')
    pose(c, t1, **B, ease='linear')

# ------------------------------------------------------------------ first-person helpers

_TAN = math.tan(math.radians(35))
_HEAD_REACH = float(np.linalg.norm(apply(G.fp_display(), G.tip) - G.fp_grip))   # grip to head, in blocks

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    """A first-person key: the grip at [hand] (camera space), the staff's head along [blade]."""
    off, q = fp(hand, blade, edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)
    clip.last_flat = qmat(q) @ G.fp_flat

def fs(clip, t, hand, blade, face=None, ease='inout'):
    """A first-person key with the staff's broad face turned toward [face] (the camera by default). The roll is chosen
    to stay on the same side as the key before (the sprite has two faces, either one turned toward the camera will
    do), so the staff never spins half a turn about its own axis between two keys."""
    b = unit(np.asarray(blade, float))
    h = np.asarray(hand, float)
    n = unit(-(h + 0.5 * _HEAD_REACH * b)) if face is None else unit(np.asarray(face, float))
    n = n - np.dot(n, b) * b
    last = getattr(clip, 'last_flat', None)
    if np.linalg.norm(n) < 0.2:
        n = last - np.dot(last, b) * b if last is not None else np.cross(b, vv(u=1.0))
    n = unit(n)
    flat = n
    if last is not None and np.dot(flat, last) < 0:
        flat = -flat
    # the item's flat normal comes out as b x (b x n) = -n for the edge b x n handed to fp()
    fk(clip, t, h, b, np.cross(b, -flat), ease)

def fh(clip, t, sx, sy, depth, blade, face=None, ease='inout'):
    """A first-person key placed by the staff's head: its place on the screen (-1 to 1 across and up), [depth] blocks
    ahead, the shaft pointing along [blade] (camera space) from the grip to the head."""
    b = unit(np.asarray(blade, float))
    head = np.array([sx * _TAN * 16 / 9 * depth, sy * _TAN * depth, -depth])
    fs(clip, t, head - _HEAD_REACH * b, b, face, ease)

def aimed(left=0.0, up=0.0):
    """A direction in camera space: [left] degrees to the left of the line of sight, [up] degrees above it."""
    l, u = math.radians(left), math.radians(up)
    return vv(f=math.cos(u) * math.cos(l), u=math.sin(u), r=-math.cos(u) * math.sin(l))

def hp(sx, sy, depth, blade):
    """A first-person pose placed by the staff's head: its place on the screen (-1 to 1 across and up) and [depth] blocks
    ahead, the shaft along [blade] (camera space) from the grip to the head. Returns (hand, blade)."""
    b = unit(np.asarray(blade, float))
    head = np.array([sx * _TAN * 16 / 9 * depth, sy * _TAN * depth, -depth])
    return head - _HEAD_REACH * b, b

def hh(x, y, depth, blade):
    """A first-person pose placed by the hand: the grip at (x, y) blocks right and up of the eye, [depth] blocks ahead,
    the shaft along [blade] (camera space). Returns (hand, blade)."""
    return np.array([x, y, -depth]), unit(np.asarray(blade, float))

def fp_key(clip, t, pose_, ease='inout'):
    fs(clip, t, pose_[0], pose_[1], None, ease)

def fseg(clip, t0, A, t1, B, n=None, ease='inout'):
    """Keys from pose A (already keyed at [t0]) to pose B at [t1] with [n] keys between (by default one for every sample the clip
    is baked at), so a quick swing keeps its broad face to the camera on the way and the hand and the shaft
    follow the ease [ease] across the whole stretch; B is keyed at [t1]."""
    if n is None:
        n = max(0, int(round((t1 - t0) * clip.rate)) - 1)
    for k in range(1, n + 1):
        x = k / (n + 1)
        u = EASE[ease](x)
        fs(clip, t0 + (t1 - t0) * x, A[0] + (B[0] - A[0]) * u, vslerp(A[1], B[1], u), None, 'linear')
    fs(clip, t1, B[0], B[1], None, 'linear')

REST = (fp_rest(G), G.fp_blade)

def rest(clip, t, ease='inout'):
    fk(clip, t, REST[0], REST[1], None, ease)

# ------------------------------------------------------------------ cast: a quick flick of the staff at the foe, the head leading
# release 6. The head swings up and back (the arm lifts), then flicks forward and down onto the line to the target as
# the shoulder drives; the free hand opens out behind for balance.
c = tp('cast', 16, blend_in=1.0, blend_out=4.0).mark('release', 6).mark('chain', 11)
OUTSIDE = v(r=0.85, u=-0.5)     # the arm out to the right and down, which shows the staff clear of the body from behind
pose(c, 0, legs=(0, 0))
pose(c, 2.5, tw=14, ln=-5, point=(-6, 65), hang=v(f=0.7, r=0.7, u=-0.2), apos=(0, 0, 0.5), free_dir=(70, -25), legs=(6, -6), ease='out')
pose(c, 6, tw=-14, ln=10, point=(5, 24), hang=OUTSIDE, apos=(0, 0, -2), free_dir=(80, -35), legs=(10, -14), root=(0, 0, -1.0), sink=0.4, ease='in')
pose(c, 7.5, tw=-10, ln=8, point=(4, 24), hang=OUTSIDE, apos=(0, 0, -1.6), free_dir=(80, -35), legs=(9, -12), root=(0, 0, -0.8), sink=0.3, ease='out')
pose(c, 11, tw=-3, ln=2, point=(2, 10), hang=D, apos=(0, 0, -0.5), free_dir=(60, -50), legs=(2, -3), ease='inout')
pose(c, 16, legs=(0, 0), ease='inout')
clips.append(c)

f = Clip('staff/cast', 16, blend_in=1.0, blend_out=4.0)
WIND = hp(0.60, 0.72, 0.95, vv(u=0.90, f=0.30, r=-0.10))
AT = hp(0.07, -0.07, 1.20, aimed(30, 40))
KICK = hp(0.10, 0.0, 1.15, aimed(30, 46))
rest(f, 0)
fs(f, 1.0, REST[0] + np.array([0.04, 0.02, 0.04]), vslerp(REST[1], WIND[1], 0.3), ease='out')
fp_key(f, 2.5, WIND, ease='out')
fseg(f, 2.5, WIND, 6, AT, ease='in')
fp_key(f, 7.5, KICK, ease='out')
fs(f, 11, REST[0] + np.array([-0.02, 0.0, -0.02]), vslerp(REST[1], KICK[1], 0.4), ease='inout')
rest(f, 16)
fps.append(f)

# ------------------------------------------------------------------ fan: the staff swept flat across the front, right to left
# release 7. The arm is held out to the right with the staff forward from the fist, level, and the torso's turn does the
# sweeping: wound to the right, then whipped through to the left, the head crossing straight ahead at the release.
c = tp('fan', 18, blend_in=1.5, blend_out=5.0).mark('release', 7).mark('chain', 12)
OUT = hold(I, sp(0, 15), v(r=0.9, u=-0.45, f=0.1))
OUT_EASED = qslerp(OUT, rest_arm(), 0.45)
pose(c, 0, legs=(0, 0))
pose(c, 3.5, tw=52, ln=-2, arm_q=OUT, free_dir=(40, -35), legs=(8, -10), root=(0, 0, 0.4), ease='out')
pose(c, 4.5, tw=56, ln=-2, arm_q=OUT, free_dir=(40, -35), legs=(8, -10), root=(0, 0, 0.4), ease='linear')
pose(c, 7, tw=0, ln=4, arm_q=OUT, free_dir=(70, -40), legs=(4, -6), root=(0, 0, -0.3), sink=0.2, ease='in')
pose(c, 9.5, tw=-52, ln=5, arm_q=OUT, free_dir=(100, -30), legs=(-6, 8), root=(0, 0, -0.4), sink=0.2, ease='out')
pose(c, 12, tw=-26, ln=3, arm_q=OUT_EASED, free_dir=(90, -40), legs=(-3, 4), root=(0, 0, -0.1), ease='out')
pose(c, 18, legs=(0, 0), ease='inout')
clips.append(c)

f = Clip('staff/fan', 18, blend_in=1.5, blend_out=5.0)
DRAWN = hh(0.54, -0.42, 0.62, aimed(-44, 26))
HELD = hh(0.56, -0.42, 0.62, aimed(-48, 26))
MID = hh(0.40, -0.44, 0.68, aimed(14, 28))
THROUGH = hh(0.0, -0.50, 0.70, aimed(60, 24))
rest(f, 0)
fs(f, 1.5, REST[0] + np.array([0.10, 0.04, 0.0]), vslerp(REST[1], DRAWN[1], 0.45), ease='out')
fp_key(f, 3.5, DRAWN, ease='out')
fseg(f, 3.5, DRAWN, 4.5, HELD, ease='linear')
fseg(f, 4.5, HELD, 7, MID, ease='in')
fseg(f, 7, MID, 9.5, THROUGH, ease='out')
fs(f, 12.5, THROUGH[0] + np.array([0.18, -0.04, 0.0]), vslerp(THROUGH[1], REST[1], 0.35), ease='inout')
rest(f, 18)
fps.append(f)

# ------------------------------------------------------------------ charged: drawn back over the shoulder like a javelin, then thrown forward
# chargeAt 9 (the hold, frozen while the button is held), release 12. The arm swings out and up behind the shoulder with the
# staff level in the fist, the torso wound to the right and the weight on the back leg, the free arm pointing at the
# target; then the whole body turns through and the staff is driven out along the line (it stays in the hand).
c = tp('charged', 26, blend_in=1.5, blend_out=6.0).mark('hold', 9).mark('release', 12).mark('chain', 17)
HOLD = dict(tw=44, ln=-7, point=(-4, 28), hang=v(f=-0.3, u=0.7, r=0.6), apos=(0, 0, 1.5), free_dir=(-4, -25), legs=(22, -26), root=(0, 0, 1.5), rootq=qeuler(y=10))
THROW = dict(tw=-24, ln=12, point=(4, 12), hang=v(r=0.6, u=0.8), apos=(0, 0, -2.5), free_dir=(70, -40), legs=(32, -14), root=(0, 0, -1.8), rootq=qeuler(y=-4))
pose(c, 0, legs=(0, 0))
pose(c, 4.5, tw=24, ln=-3, point=(-4, 14), hang=v(r=1.0, u=0.15), apos=(0, 0, 0.6), free_dir=(-4, -25), legs=(10, -12), root=(0, 0, 0.6), rootq=qeuler(y=5), ease='out')
pose(c, 9, **HOLD, ease='inout')
ramp(c, 9, HOLD, 12, THROW, n=5, ease='in')
pose(c, 14, tw=-20, ln=12, point=(4, 10), hang=v(r=0.7, u=0.5), apos=(0, 0, -2.0), free_dir=(70, -40), legs=(28, -14), root=(0, 0, -1.5), rootq=qeuler(y=-3), ease='out')
pose(c, 17, tw=-9, ln=6, point=(3, 10), hang=v(r=0.5, u=-0.6), apos=(0, 0, -1.0), free_dir=(60, -45), legs=(12, -8), root=(0, 0, -0.7), rootq=qeuler(y=-1), ease='inout')
pose(c, 20, tw=-4, ln=3, point=(2, 10), hang=D, apos=(0, 0, -0.4), free_dir=(60, -50), legs=(4, -4), root=(0, 0, -0.3), ease='inout')
pose(c, 26, legs=(0, 0), ease='inout')
clips.append(c)

f = Clip('staff/charged', 26, blend_in=1.5, blend_out=6.0)
DRAW = hp(0.58, 0.40, 0.95, vv(u=0.80, f=0.55, r=-0.2))
COCKED = hp(0.30, 0.16, 1.18, aimed(36, 30))
THROWN = hp(0.07, -0.04, 1.62, aimed(20, 26))
rest(f, 0)
fp_key(f, 4.5, DRAW, ease='out')
fseg(f, 4.5, DRAW, 9, COCKED, ease='inout')
fseg(f, 9, COCKED, 12, THROWN, ease='in')
fs(f, 14, THROWN[0] + np.array([0.02, 0.0, 0.0]), vslerp(THROWN[1], COCKED[1], 0.2), ease='out')
fs(f, 20, REST[0] + np.array([-0.02, 0.0, -0.04]), vslerp(REST[1], THROWN[1], 0.3), ease='inout')
rest(f, 26)
fps.append(f)

# ------------------------------------------------------------------ flicker: a low crouch, the staff swept across the body, then up
# blink at 3 (the body is gone from there and appears a few blocks on), untouchable in ticks 1-6. Very quick: the body
# drops and winds, the staff whips across from the right hip to the left, and the caster springs up out of it.
c = tp('flicker', 12, blend_in=1.0, blend_out=3.0, rate=4).mark('blink', 3).mark('safe until', 6)
pose(c, 0, legs=(0, 0))
pose(c, 1, tw=34, ln=14, point=(-55, 4), hang=D, apos=(0, 0, 0.8), free_dir=(60, -30), legs=(26, -28), legz=(3, -3), root=(0, 0, 0.4), ease='out')
pose(c, 2, tw=0, ln=20, point=(0, 6), hang=D, apos=(0, 0, -0.4), free_dir=(75, -30), legs=(38, -42), legz=(5, -5), root=(0, 0, -0.4), ease='in')
pose(c, 3, tw=-34, ln=22, point=(58, 10), hang=D, apos=(0, 0, -0.8), free_dir=(85, -35), legs=(44, -46), legz=(6, -6), root=(0, 0, -0.8), ease='out')
pose(c, 5, tw=-12, ln=12, point=(20, 30), hang=D, apos=(0, 0, -0.6), free_dir=(70, -40), legs=(20, -22), legz=(3, -3), root=(0, 0, -0.4), ease='inout')
pose(c, 7, tw=-2, ln=5, point=(4, 16), hang=D, apos=(0, 0, -0.3), free_dir=(40, -50), legs=(5, -6), root=(0, 0, -0.1), ease='inout')
pose(c, 12, legs=(0, 0), ease='inout')
clips.append(c)

f = Clip('staff/flicker', 12, blend_in=1.0, blend_out=3.0, rate=4)
RIGHT = hp(0.80, -0.32, 0.95, aimed(-52, 18))
ACROSS = hp(0.10, -0.32, 1.20, aimed(8, 20))
LEFT = hp(-0.72, -0.30, 1.00, aimed(62, 18))
RISE = hp(0.30, -0.15, 1.10, aimed(14, 40))
rest(f, 0)
fseg(f, 0, (REST[0], REST[1]), 1, RIGHT, ease='out')
fseg(f, 1, RIGHT, 3, LEFT, ease='inout')
fseg(f, 3, LEFT, 6, RISE, ease='inout')
fseg(f, 6, RISE, 8, (REST[0] + np.array([-0.04, 0.02, -0.06]), vslerp(REST[1], aimed(14, 60), 0.5)), ease='inout')
rest(f, 12)
fps.append(f)

# ------------------------------------------------------------------ raise: the staff held up to the sky to call a star down, then brought down on the target
# chargeAt 10 (the hold, frozen while the button is held), release 16. The arm lifts the staff upright in front, the
# chest arched, the head back and the free arm out for balance; at the release the staff comes down hard to point at
# the target ahead, the body leaning into it.
c = tp('raise', 30, blend_in=2.0, blend_out=6.0).mark('hold', 10).mark('release', 16).mark('chain', 20)
pose(c, 0, legs=(0, 0), nod=0)
pose(c, 5, tw=-2, ln=-6, point=(-3, 60), hang=v(f=0.7, r=0.5, u=-0.4), apos=(0, 0, -0.4), free_dir=(85, -28), legs=(4, -6), legz=(3, -3), root=(0, 0, 0), nod=-8, ease='out')
pose(c, 10, tw=0, ln=-14, point=(-4, 94), hang=v(f=0.6, r=0.8), apos=(0, 0, -0.8), free_dir=(100, -18), legs=(6, -8), legz=(6, -6), root=(0, 0, 0.2), nod=-20, ease='inout')
BACK = dict(tw=0, ln=-18, point=(-4, 112), hang=v(f=0.6, r=0.8, u=0.25), apos=(0, 0, -0.4), free_dir=(100, -14), legs=(7, -9), legz=(7, -7), root=(0, 0, 0.4), nod=-24)
SLAM = dict(tw=-8, ln=20, point=(3, 20), hang=OUTSIDE, apos=(0, 0, -2.2), free_dir=(70, -55), legs=(16, -22), legz=(2, -2), root=(0, 0, -1.4), nod=8)
pose(c, 11.5, **BACK, ease='out')
ramp(c, 11.5, BACK, 16, SLAM, n=8, ease='in')
pose(c, 17.5, tw=-7, ln=20, point=(3, 16), hang=OUTSIDE, apos=(0, 0, -2.0), free_dir=(70, -55), legs=(15, -20), legz=(2, -2), root=(0, 0, -1.3), nod=6, ease='out')
pose(c, 20, tw=-4, ln=10, point=(2, 12), hang=v(r=0.5, u=-0.8), apos=(0, 0, -1.0), free_dir=(55, -58), legs=(7, -9), root=(0, 0, -0.6), nod=2, ease='inout')
pose(c, 25, tw=-1, ln=3, point=(2, 10), hang=D, apos=(0, 0, -0.3), free_dir=(40, -60), legs=(1, -2), root=(0, 0, -0.1), nod=0, ease='inout')
pose(c, 30, legs=(0, 0), nod=0, ease='inout')
c.mode('head', 'add')     # the head keeps looking where the player looks; the nod is on top of that
clips.append(c)

f = Clip('staff/raise', 30, blend_in=2.0, blend_out=6.0)
LIFT = hp(0.55, 0.55, 1.00, vv(u=0.92, f=0.35, r=-0.15))
SKY = hp(0.34, 0.80, 1.10, vv(u=0.88, f=0.45, r=-0.05))
BACK = hp(0.38, 0.84, 1.05, vv(u=0.93, f=0.20, r=-0.12))
POINT = hp(0.10, -0.06, 1.40, aimed(24, 34))
rest(f, 0)
fp_key(f, 5, LIFT, ease='out')
fseg(f, 5, LIFT, 10, SKY, ease='inout')
fseg(f, 10, SKY, 11.5, BACK, ease='out')
fseg(f, 11.5, BACK, 16, POINT, ease='in')
fp_key(f, 19, hp(0.12, -0.02, 1.35, aimed(24, 38)), ease='out')
fs(f, 24, REST[0] + np.array([-0.04, 0.0, -0.06]), vslerp(REST[1], POINT[1], 0.3), ease='inout')
rest(f, 30)
fps.append(f)

# ------------------------------------------------------------------ output

# The contact sheets show the moments that matter (the wind-up, the hold, the release, the recovery) rather than even steps,
# and frame the figure at the sheet's default zoom (the raise, with the staff held up to the sky, needs a taller frame).
# Clip names after the fourth argument limit which sheets are drawn (all the json is written regardless).
TIMES = {
    'staff/cast': [0, 1.5, 2.5, 4, 6, 7.5, 9.5, 12, 16],
    'staff/fan': [0, 2, 3.5, 5.5, 7, 8.5, 9.5, 12, 18],
    'staff/charged': [0, 4.5, 7, 9, 10.5, 12, 14, 17, 26],
    'staff/flicker': [0, 1, 2, 3, 4, 5, 6, 8, 12],
    'staff/raise': [0, 5, 10, 12.5, 14.5, 16, 18, 22, 30],
}
FRAMING = {'staff/raise': dict(zoom=3.6, center_y=4.0)}

if __name__ == '__main__':
    assets, previews, skin, weapon = sys.argv[1:5]
    os.makedirs(previews, exist_ok=True)
    from PIL import Image
    scene = Scene(Image.open(skin), Image.open(weapon), geo=G)
    by_name = {f.name: f for f in fps}
    for c in clips:
        print(c.write(assets, 'player'))
    for f in fps:
        print(f.write(assets, 'first_person'))
    only = sys.argv[5:] or [c.name for c in clips]
    for c in clips:
        if c.name not in only:
            continue
        sheet(scene, c, by_name.get(c.name), times=TIMES.get(c.name), **FRAMING.get(c.name, {})).save(os.path.join(previews, c.name.split('/')[-1] + '.png'))
