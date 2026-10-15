"""
The censer's cast moves: prayers said over a pierced incense burner on a short haft, held like a mace. Third-person
clips pose the skeleton; first-person clips move the held censer (item). Run:

    python3 -B art/scripts/anims_censer.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_censer.png>

Timings match the cast table (MAGIC.md): a clip lasts as many ticks as its cast, and the prayer goes off at the
release tick, when the burner (the geometry's tip) is at the moment of greatest intent.

How the censer sits: the haft lies 80 degrees from the forearm (a fist round a mace's haft) and the sprite is flat
in the plane of arm and haft. So with the arm hanging the burner points ahead; to hold it up over the head the arm
goes up and out to the side with the haft leaning back in, a V seen from behind, the burner at the top of it. The
free left hand stays low (on the chest, at the thigh, out for balance below the shoulder): the game also casts with a
catalyst in the off hand, mirroring the clip, and then that hand may hold a sword.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from PIL import Image
from weaponkit import CENSER as G

def tp(name, length, **kw):
    return Clip('censer/' + name, length, **kw)

clips, fps = [], []

# Every arm pose below is solved for the censer's own grip
_arm = arm
def arm(direction, blade, left=False, geo=G):
    return _arm(direction, blade, left, geo)

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F)

def legs(c, t, right=0.0, left=0.0, ease='inout'):
    """Both legs at once: degrees about x, negative swings the foot forward."""
    c.key('right_leg', t, qeuler(x=right), ease=ease)
    c.key('left_leg', t, qeuler(x=left), ease=ease)

def torso_q(twist=0.0, lean=0.0, tilt=0.0):
    """A torso turn: [twist] degrees of yaw (positive to the right), [lean] forward (negative back), [tilt] to the
    character's left (negative right), in the order a body does them: bend, then turn."""
    return qmul(qeuler(y=twist), qmul(qeuler(x=lean), qeuler(z=tilt)))

def aimed(tq, direction, blade):
    """The right arm, relative to a torso turned [tq], pointing along [direction] and holding the haft along
    [blade] (both in the character's frame, so they stay put whatever the torso does)."""
    M = qmat(tq).T
    return arm(M @ unit(direction), M @ unit(blade))

def free_in(tq, direction, palm=None):
    """The free left arm (relative to a torso turned [tq]) pointing along [direction] in the character's frame."""
    M = qmat(tq).T
    return free_arm(M @ unit(direction), None if palm is None else M @ unit(palm))

# ------------------------------------------------------------------ feet on the ground

GROUND = 24.0          # the soles' height when standing, in model pixels (y runs down)
SOFT = 1.6             # how much sink (pixels) a leg takes to come fully to its tilt (see feet())

def qinv(q):
    return np.array([-q[0], -q[1], -q[2], q[3]])

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
            excess = _lowest(hip, direction(0.0)) - ground     # how far a straight leg would sink into the ground
            if excess <= 0.0:
                tilt = 0.0     # hips high: the leg hangs straight and the foot is off the ground
            else:
                lo, hi = 0.0, math.radians(120.0)
                for _ in range(30):
                    mid = (lo + hi) / 2
                    if _lowest(hip, direction(mid)) > ground:
                        lo = mid
                    else:
                        hi = mid
                # A straight leg with a flat foot has to swing out like the square root of the sink; let the first
                # inch of it come in smoothly instead of snapping (the sole sinks a hair into the ground meanwhile)
                s = min(1.0, excess / SOFT)
                tilt = hi * s * s * (3.0 - 2.0 * s)
            q = qmul(qinv(qr), _leg_world(direction(tilt)))
            clip.key(name, t, q, ease='linear')
        t += step

def smooth(clip, bone, taps=(1, 2, 3, 2, 1)):
    """Low-passes a bone's dense keys (as feet() lays them down, every half tick) once over, the first and last staying put."""
    keys = clip.keys[bone]
    half = len(taps) // 2
    out = []
    for i, (t, q, pos, ease) in enumerate(keys):
        if 0 < i < len(keys) - 1:
            acc = np.zeros(4)
            for j, w in enumerate(taps):
                qj = keys[min(max(i - half + j, 0), len(keys) - 1)][1]
                acc += w * (qj if np.dot(qj, q) >= 0 else -qj)
            q = acc / np.linalg.norm(acc)
        out.append((t, q, pos, ease))
    clip.keys[bone] = out

_SPRITE = None

def sprite_points():
    """Corners of the opaque pixels of the iron censer's sprite, in item space (0..1, y up): the same layout serves
    all three censers (grip and burner sit where weaponkit puts them)."""
    global _SPRITE
    if _SPRITE is None:
        here = os.path.dirname(os.path.abspath(__file__))
        path = os.path.join(here, '..', '..', 'src', 'main', 'resources', 'assets', 'guildmark', 'textures', 'item', 'iron_censer.png')
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
        if not pts:     # the burner as a disc round its centre, a finial beyond it
            cx, cy = G.tip[0], G.tip[1]
            for a in np.linspace(0, 2 * math.pi, 24, endpoint=False):
                pts.append((cx + 0.1 * math.cos(a), cy + 0.1 * math.sin(a), 0.5))
            pts.append((cx + 0.1, cy + 0.1, 0.5))
        _SPRITE = np.array(pts)
    return _SPRITE

def burner(clip, t):
    """Where the burner's centre is at [t], in the model's space (pixels)."""
    pose = pose_clip(clip, t)
    qr, pr = pose['root']
    q, p = pose['right_arm']
    M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q) @ S(16) @ G.tp_item()
    return apply(M, G.tip)

def lowest(clip, t):
    """The lowest point (largest y) of the censer's sprite at [t]."""
    pose = pose_clip(clip, t)
    qr, pr = pose['root']
    q, p = pose['right_arm']
    M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q) @ S(16) @ G.tp_item()
    pts = np.array([apply(M, x) for x in sprite_points()])
    return pts[np.argmax(pts[:, 1])]

# ------------------------------------------------------------------ first person

REST = fp_rest(G)
REST_BLADE = G.fp_blade
HEAD = float(np.linalg.norm(apply(G.fp_display(), G.tip) - G.fp_grip))     # grip to the burner's centre, in blocks

def flat_edge(blade, hand):
    """The edge direction that turns the censer's flat toward the eye, so it never shows edge-on."""
    b = unit(np.asarray(blade, float))
    eye = -(np.asarray(hand, float) + 0.5 * HEAD * b)
    n = eye - np.dot(eye, b) * b
    if np.linalg.norm(n) < 1e-3:
        n = -VF
    return unit(np.cross(n, b))

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    """A first-person key: the grip at [hand] (camera space: x right, y up, -z ahead), the haft along [blade]; the flat
    faces the eye unless [edge] says otherwise."""
    b = unit(np.asarray(blade, float))
    off, q = fp(hand, b, flat_edge(b, hand) if edge is None else edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

def fb(clip, t, burner, blade, edge=None, ease='inout'):
    """A first-person key given by where the burner's centre is (camera space) rather than the grip."""
    b = unit(np.asarray(blade, float))
    fk(clip, t, np.asarray(burner, float) - HEAD * b, b, edge, ease)

def at_rest(clip, t, ease='inout'):
    """The censer where vanilla draws it: exactly the identity."""
    off, q = fp(REST, REST_BLADE, None, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

# ------------------------------------------------------------------ swing: a short underhand toss from the hip
# The censer is drawn back and down by the hip (the body turning away), then scooped forward and up, the burner leading
# straight ahead past the right shoulder at the release (5), carrying on up a little and falling back to rest by 10.
c = tp('swing', 14, blend_in=1.0, blend_out=4.0).mark('release', 5).mark('chain', 9)
c.key('torso', 0)
c.key('torso', 3.0, qmul(qeuler(y=22), qeuler(x=3)), ease='inout')
c.key('torso', 5.0, qmul(qeuler(y=-4), qeuler(x=-2)), ease='in')
c.key('torso', 6.8, qmul(qeuler(y=-8), qeuler(x=-3)), ease='out')
c.key('torso', 10.0, qmul(qeuler(y=-4), qeuler(x=0)), ease='inout')
c.key('torso', 14, qeuler(y=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 3.0, arm(v(f=-0.3, u=-0.9, r=0.2), v(f=0.75, u=-0.55)), ease='inout')
c.key('right_arm', 5.0, arm(v(f=0.45, u=-0.85, r=0.28), v(f=0.9, u=0.35, r=0.18)), ease='in')
c.key('right_arm', 6.8, arm(v(f=0.7, u=-0.6, r=0.32), v(f=0.75, u=0.55, r=0.2)), ease='out')
c.key('right_arm', 10.0, arm(v(f=0.36, u=-0.93, r=0.05), v(f=0.95, u=0.1)), ease='inout')
c.key('right_arm', 14, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 3.0, free_arm(v(f=0.3, u=-0.9, r=0.1)), ease='inout')
c.key('left_arm', 5.0, free_arm(v(f=-0.4, u=-0.85, r=-0.5)), ease='inout')
c.key('left_arm', 14, free_arm(D), ease='inout')
legs(c, 0)
legs(c, 3.0, right=6, left=-4, ease='inout')
legs(c, 5.0, right=8, left=-10)
legs(c, 10.0, right=3, left=-4)
legs(c, 14)
c.key('root', 0)
c.key('root', 3.0, pos=(0, 0.7, 0.3), ease='inout')
c.key('root', 5.0, pos=(0, 0.5, -0.8), ease='inout')
c.key('root', 10.0, pos=(0, 0.2, -0.3), ease='inout')
c.key('root', 14, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('swing', 14, blend_in=1.0, blend_out=4.0)
at_rest(f, 0)
fk(f, 3.0, (0.62, -0.8, -0.7), vv(u=0.75, f=0.3, r=0.35), ease='inout')
fk(f, 5.0, (0.3, -0.42, -0.5), vv(f=0.72, u=0.7), ease='in')
fk(f, 6.8, (0.3, -0.3, -0.58), vv(f=0.6, u=0.8, r=-0.05), ease='out')
fk(f, 10.0, (0.45, -0.55, -0.7), vv(f=0.4, u=0.9, r=0.05), ease='inout')
at_rest(f, 14)
fps.append(f)

# ------------------------------------------------------------------ raise: a prayer, the censer lifted over the head
# A small bow, then the censer rises in front of the face and opens out overhead (the arm up and out, the haft leaning
# back in: a V seen from behind) while the chest lifts and the head tips back; the light blooms at 12, held a breath,
# then the censer comes down in front of the body again and the body settles (well under way by 17).
CHEST = v(f=0.45, u=-0.45, r=0.55)         # the free hand held to the chest
HELD = arm(v(f=0.45, u=0.75, r=0.55), v(u=0.8, r=-0.55, f=-0.1))     # the censer overhead, a V seen from behind
c = tp('raise', 26, blend_in=2.0, blend_out=5.0).mark('release', 12).mark('chain', 17)
c.key('torso', 0)
c.key('torso', 2.5, qeuler(x=4), ease='inout')
c.key('torso', 6.5, qeuler(x=-2), ease='in')
c.key('torso', 12, qeuler(x=-7), ease='out')
c.key('torso', 14.5, qeuler(x=-8), ease='out')
c.key('torso', 17.5, qeuler(x=-4), ease='in')
c.key('torso', 21, qeuler(x=-1), ease='out')
c.key('torso', 26, qeuler(x=0), ease='inout')
c.mode('head', 'add')
c.key('head', 0)
c.key('head', 2.5, qeuler(x=9), ease='inout')
c.key('head', 6.5, qeuler(x=-5), ease='in')
c.key('head', 12, qeuler(x=-13), ease='out')
c.key('head', 14.5, qeuler(x=-15), ease='out')
c.key('head', 17.5, qeuler(x=-6), ease='in')
c.key('head', 21, qeuler(x=-1), ease='out')
c.key('head', 26, qeuler(x=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 2.5, arm(v(f=0.6, u=-0.78, r=-0.02), v(f=0.78, u=0.6)), ease='inout')
c.key('right_arm', 6.5, arm(v(f=0.9, u=0.25, r=0.38), v(u=1.0, f=0.1, r=-0.25)), ease='inout')
c.key('right_arm', 12, HELD, ease='inout')
c.key('right_arm', 13.0, arm(v(f=0.45, u=0.78, r=0.55), v(u=0.8, r=-0.55, f=-0.1)), ease='out')
c.key('right_arm', 20.0, arm(v(f=0.45, u=-0.85, r=0.08), v(f=0.95, u=0.2)), ease='inout')
c.key('right_arm', 26, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 6.5, free_arm(CHEST), ease='in')
c.key('left_arm', 14.5, free_arm(CHEST), ease='inout')
c.key('left_arm', 17.5, free_arm(v(f=0.45, u=-0.65, r=0.45)), ease='in')
c.key('left_arm', 21, free_arm(v(f=0.3, u=-0.9, r=0.25)), ease='out')
c.key('left_arm', 26, free_arm(D), ease='inout')
legs(c, 0)
c.key('root', 0)
c.key('root', 2.5, pos=(0, 0.3, 0), ease='inout')
c.key('root', 12, pos=(0, -0.5, 0), ease='inout')
c.key('root', 14.5, pos=(0, -0.5, 0), ease='inout')
c.key('root', 20, pos=(0, 0, 0), ease='inout')
c.key('root', 26, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('raise', 26, blend_in=2.0, blend_out=5.0)
at_rest(f, 0)
fb(f, 2.5, (0.5, 0.12, -0.65), vv(u=0.95, f=0.05), ease='inout')
fb(f, 6.5, (0.28, 0.2, -0.85), vv(u=0.97, f=0.1, r=-0.1), ease='inout')
fb(f, 12, (0.08, 0.3, -0.93), vv(u=0.95, f=0.25), ease='inout')
fb(f, 13, (0.08, 0.31, -0.93), vv(u=0.95, f=0.25), ease='inout')
fb(f, 16.5, (0.3, 0.12, -0.8), vv(u=0.95, f=0.2, r=-0.05), ease='inout')
fb(f, 20, (0.5, 0.01, -0.63), vv(u=0.97, f=0.05), ease='inout')
at_rest(f, 26)
fps.append(f)

# ------------------------------------------------------------------ ground: a step into a half kneel, the burner to the ground
# The left foot steps out and the body folds forward over it, the right leg going back; the right hand turns the censer
# over (the arm comes up and out to the front while the haft swings from pointing ahead to hanging down), and the burner
# comes to rest on the ground ahead of the front foot at 11, the free hand by the front knee. The skeleton has no knees,
# so the kneel is a deep lunge: hips down by half their height, the front leg reaching out, the torso folded over it.
REST_Q = rest_arm()
TQ = {0: torso_q(), 1.5: torso_q(0, -3), 3: torso_q(5, 5), 5.5: torso_q(0, 12), 8: torso_q(-4, 24), 11: torso_q(-10, 34),
      12.5: torso_q(-10, 34), 15: torso_q(-5, 20), 18: torso_q(-1, 7), 24: torso_q()}
PLANT_UP = 14.0          # degrees above level the arm points when the censer is planted, the haft hanging down from it
PLANT_OUT = 0.55         # and how far out to the right of straight ahead (the censer then shows its flat from behind)
PLANT = (v(f=math.cos(math.radians(PLANT_UP)), u=math.sin(math.radians(PLANT_UP)), r=PLANT_OUT), v(f=0.3, u=-0.95))
_TIP = G.tp_point(G.tip)
# The axis the rest pose is rolled about to turn the censer over: midway between where the burner points at rest and planted
TURN = unit(unit(qmat(REST_Q) @ _TIP) + unit(qmat(qmul(TQ[11], aimed(TQ[11], *PLANT))) @ _TIP))

def turned(tq, deg):
    """The right arm (relative to a torso turned [tq]) after the rest pose has been rolled [deg] degrees about the line
    to the burner: 0 is the rest, -174 the planted pose (arm out in front with the haft hanging down)."""
    return qmul(qinv(tq), qmul(qaxis(TURN, deg), REST_Q))

TURNS = {0: 0, 3: -15, 5.5: -50, 8: -125, 15: -105, 18: -45, 24: 0}
c = tp('ground', 24, blend_in=2.0, blend_out=5.0).mark('release', 11).mark('chain', 16)
for t in TQ:
    c.key('torso', t, TQ[t], ease='out' if t == 1.5 else 'inout')
    if t in (11, 12.5):
        c.key('right_arm', t, aimed(TQ[t], *PLANT), ease='inout')
    elif t == 1.5:      # the censer lifts a little as the breath is drawn
        c.key('right_arm', t, aimed(TQ[t], v(f=0.55, u=-0.8, r=0.05), v(f=0.9, u=0.35)), ease='out')
    else:
        c.key('right_arm', t, REST_Q if t in (0, 24) else turned(TQ[t], TURNS[t]), ease='out' if t == 1.5 else 'inout')
c.mode('head', 'add')
c.key('head', 0)
c.key('head', 1.5, qeuler(x=-3), ease='out')
c.key('head', 8, qeuler(x=6), ease='inout')
c.key('head', 11, qeuler(x=11), ease='inout')
c.key('head', 12.5, qeuler(x=11), ease='inout')
c.key('head', 18, qeuler(x=2), ease='inout')
c.key('head', 24, qeuler(x=0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 11, free_in(TQ[11], v(u=-1.0, r=0.1)), ease='inout')
c.key('left_arm', 12.5, free_in(TQ[12.5], v(u=-1.0, r=0.1)), ease='inout')
c.key('left_arm', 17, free_in(TQ[15], v(u=-1.0, r=0.05)), ease='inout')
c.key('left_arm', 24, free_arm(D), ease='inout')
# The hips sink with the body, by as much as puts the censer's lowest point on the ground at 11
c.key('root', 11, pos=(0, 0, -2.0), ease='inout')
DROP = GROUND - float(lowest(c, 11)[1])
c.keys['root'] = []
c.key('root', 0)
for t, (dy, dz) in {1.5: (-0.08, 0.0), 3: (0.08, 0.15), 5.5: (0.3, 0.45), 8: (0.68, 0.75), 11: (1.0, 1.0), 12.5: (1.0, 1.0), 15: (0.55, 0.6), 18: (0.16, 0.2), 24: (0.0, 0.0)}.items():
    c.key('root', t, pos=(0, DROP * dy, -2.0 * dz), ease='out' if t == 1.5 else 'inout')
feet(c, [(0, (1.9, 0.0), (-1.9, 0.0), 'inout'), (1.5, (1.9, 0.0), (-1.9, 0.0), 'inout'), (3.5, (2.0, -5.0), (-1.9, 1.0), 'inout', 1.2, 0.0), (5.5, (2.4, -8.5), (-2.4, 3.0), 'inout'),
         (8, (2.6, -10.5), (-3.0, 5.5), 'inout'), (11, (2.8, -11.0), (-3.4, 8.0), 'inout', 0.0, 2.0), (12.5, (2.8, -11.0), (-3.4, 8.0), 'inout', 0.0, 2.0),
         (15, (2.4, -7.0), (-2.6, 4.0), 'inout'), (18, (2.0, -2.5), (-2.0, 1.0), 'inout'), (24, (1.9, 0.0), (-1.9, 0.0), 'inout')])
for leg in ('left_leg', 'right_leg'):
    smooth(c, leg)
clips.append(c)

f = tp('ground', 24, blend_in=2.0, blend_out=5.0)
at_rest(f, 0)
fb(f, 1.5, (0.55, 0.12, -0.6), vv(u=0.95, f=0.15), ease='out')
fb(f, 4.0, (0.45, 0.12, -0.8), vv(u=0.5, f=0.8, r=-0.25), ease='inout')
fb(f, 7.0, (0.3, -0.05, -1.0), vv(f=0.8, u=-0.2, r=-0.5), ease='inout')
fb(f, 11, (0.2, -0.36, -1.0), vv(f=0.5, u=-0.7, r=-0.5), ease='inout')
fb(f, 12.5, (0.2, -0.36, -1.0), vv(f=0.5, u=-0.7, r=-0.5), ease='inout')
fb(f, 16, (0.35, -0.1, -0.9), vv(f=0.7, u=0.0, r=-0.3), ease='inout')
fb(f, 19.5, (0.5, 0.08, -0.65), vv(u=0.9, f=0.2), ease='inout')
at_rest(f, 24)
fps.append(f)

# ------------------------------------------------------------------ charged: the censer held high, then thrust up and out
# The censer rises in one sweep to a V overhead (the hold at 9, a strong upright stance); on release the body gathers for
# a moment (the censer drawn in, the stance widening), then bursts open at 14: censer up and out over the shoulder, chest
# out, head back, the free arm flung wide (still below the shoulder). Well into the lowering by 20.
HOLD = arm(v(f=0.35, u=0.78, r=0.6), v(u=0.8, r=-0.55, f=0.05))      # the censer high overhead, a V seen from behind
BURST = arm(v(f=0.45, u=0.45, r=0.9), v(u=0.8, r=-0.5, f=0.05))      # thrust up and out over the right shoulder
c = tp('charged', 30, blend_in=2.0, blend_out=6.0).mark('hold', 9).mark('release', 14).mark('chain', 20)
c.key('torso', 0)
c.key('torso', 1.5, qeuler(x=3), ease='out')
c.key('torso', 9, qeuler(x=-6), ease='inout')
c.key('torso', 11.5, qeuler(x=3), ease='inout')
c.key('torso', 14, qmul(qeuler(x=-12), qeuler(z=4)), ease='in')
c.key('torso', 15.5, qmul(qeuler(x=-13), qeuler(z=4)), ease='out')
c.key('torso', 20, qmul(qeuler(x=-6), qeuler(z=1)), ease='inout')
c.key('torso', 25, qeuler(x=-1), ease='inout')
c.key('torso', 30, qeuler(x=0), ease='inout')
c.mode('head', 'add')
c.key('head', 0)
c.key('head', 1.5, qeuler(x=5), ease='out')
c.key('head', 9, qeuler(x=-14), ease='inout')
c.key('head', 11.5, qeuler(x=5), ease='inout')
c.key('head', 14, qeuler(x=-18), ease='in')
c.key('head', 15.5, qeuler(x=-19), ease='out')
c.key('head', 20, qeuler(x=-10), ease='inout')
c.key('head', 25, qeuler(x=-1), ease='inout')
c.key('head', 30, qeuler(x=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 1.5, arm(v(f=0.6, u=-0.78, r=-0.02), v(f=0.78, u=0.6)), ease='out')
c.key('right_arm', 9, HOLD, ease='inout')
c.key('right_arm', 11.5, arm(v(f=0.85, u=0.1, r=0.35), v(f=0.6, u=0.75, r=-0.2)), ease='inout')
c.key('right_arm', 14, BURST, pos=(-0.3, -0.9, 0), ease='in')
c.key('right_arm', 15.5, arm(v(f=0.45, u=0.48, r=0.9), v(u=0.8, r=-0.5, f=0.05)), pos=(-0.2, -0.7, 0), ease='out')
c.key('right_arm', 20, arm(v(f=0.8, u=-0.4, r=0.25), v(f=0.85, u=0.45, r=-0.1)), pos=(-0.05, -0.2, 0), ease='inout')
c.key('right_arm', 24.5, arm(v(f=0.45, u=-0.85, r=0.08), v(f=0.95, u=0.2)), pos=(0, 0, 0), ease='inout')
c.key('right_arm', 30, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 4, free_arm(CHEST), ease='inout')
c.key('left_arm', 9, free_arm(v(f=0.1, u=-0.45, r=-1.0)), ease='inout')
c.key('left_arm', 11.5, free_arm(CHEST), ease='inout')
c.key('left_arm', 14, free_arm(v(f=0.0, u=-0.3, r=-1.0)), ease='inout')
c.key('left_arm', 15.5, free_arm(v(f=0.0, u=-0.33, r=-1.0)), ease='out')
c.key('left_arm', 22, free_arm(v(f=0.2, u=-0.8, r=-0.3)), ease='inout')
c.key('left_arm', 30, free_arm(D), ease='inout')
for leg, side in (('right_leg', 1), ('left_leg', -1)):
    c.key(leg, 0)
    c.key(leg, 1.5, qeuler(z=2 * side), ease='out')
    c.key(leg, 9, qeuler(z=6 * side), ease='inout')
    c.key(leg, 11.5, qeuler(z=13 * side), ease='inout')
    c.key(leg, 14, qeuler(z=5 * side), ease='in')
    c.key(leg, 15.5, qeuler(z=5 * side), ease='out')
    c.key(leg, 22, qeuler(z=2 * side), ease='inout')
    c.key(leg, 30, qeuler(), ease='inout')
c.key('root', 0)
c.key('root', 1.5, pos=(0, 0.2, 0), ease='out')
c.key('root', 9, pos=(0, 0.07, 0), ease='inout')
c.key('root', 11.5, pos=(0, 0.35, 0), ease='inout')
c.key('root', 14, pos=(0, -0.7, 0), ease='in')
c.key('root', 15.5, pos=(0, -0.7, 0), ease='out')
c.key('root', 21, pos=(0, 0, 0), ease='inout')
c.key('root', 30, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('charged', 30, blend_in=2.0, blend_out=6.0)
at_rest(f, 0)
fb(f, 1.5, (0.55, 0.12, -0.6), vv(u=0.95, f=0.05), ease='out')
fb(f, 9, (0.08, 0.3, -0.95), vv(u=0.95, f=0.25), ease='inout')
fb(f, 11.5, (0.2, 0.08, -0.72), vv(u=0.97, f=0.05), ease='inout')
fb(f, 14, (0.05, 0.4, -1.05), vv(u=0.9, f=0.4), ease='in')
fb(f, 15.5, (0.05, 0.42, -1.05), vv(u=0.9, f=0.4), ease='out')
fb(f, 20, (0.3, 0.15, -0.85), vv(u=0.95, f=0.2), ease='inout')
fb(f, 24.5, (0.5, 0.02, -0.65), vv(u=0.97, f=0.05), ease='inout')
at_rest(f, 30)
fps.append(f)

# ------------------------------------------------------------------ output

# Which moments each contact sheet shows (the release among them) and how it is framed (the tall poses reach over the head)
SHEETS = {
    'swing': dict(times=[0, 2, 3.5, 5, 6.5, 8, 10, 12, 14]),
    'raise': dict(times=[0, 3, 6.5, 9.5, 12, 14.5, 17, 20, 26], zoom=3.6, center_y=0.0),
    'ground': dict(times=[0, 3, 6, 9, 11, 13, 16, 20, 24]),
    'charged': dict(times=[0, 4, 7, 9, 11.5, 14, 17, 21, 30], zoom=3.6, center_y=0.0),
}

if __name__ == '__main__':
    assets, previews, skin, censer = sys.argv[1:5]
    os.makedirs(previews, exist_ok=True)
    from PIL import Image
    scene = Scene(Image.open(skin), Image.open(censer), geo=G)
    by_name = {f.name: f for f in fps}
    for c in clips:
        print(c.write(assets, 'player'))
    for f in fps:
        print(f.write(assets, 'first_person'))
    only = sys.argv[5:] or [c.name.split('/')[-1] for c in clips]
    for c in clips:
        short = c.name.split('/')[-1]
        if short not in only:
            continue
        sheet(scene, c, by_name.get(c.name), **SHEETS[short]).save(os.path.join(previews, short + '.png'))
