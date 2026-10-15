"""
The curved sword's (sabre's) combat moves: wide, flowing cuts that roll from one into the next, the wrist turning the
edge through each arc, a dancer's footwork under them. Third-person clips pose the skeleton, first-person clips move
the held item. Run:

    python3 -B art/scripts/anims_sabre.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_sabre.png>

Timings match Move.kt: a clip lasts as many ticks as its move, and its blows land inside the move's hit window. The clip
names are sabre/<anim> (the animation folder is "sabre", the class id is curved_sword).
"""
import os, sys, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import SABRE as G

def tp(name, length, **kw):
    return Clip('sabre/' + name, length, **kw)

clips, fps = [], []

# ------------------------------------------------------------------ poses

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F, geo=G)

# The blade stands square to the forearm like a hammer in a fist, so a cut is the arm and blade turning together as an
# L about an axis through the shoulder. Everything below is worked out in the character's own frame (forward, up and
# the right, as v() has it) and turned into the torso's frame with the torso and root as the clip keys them.

def _rot(axis, deg):
    return qmat(qaxis(axis, deg))

def facing(c, t):
    """The turn from the character's frame to the torso's at [t]: the root's, then the torso's."""
    return qmul(c.sample('root', t)[0], c.sample('torso', t)[0])

def aim(az, el=0.0):
    """A direction by compass in the character's frame: [az] degrees to the right of straight ahead, [el] above level."""
    a, e = math.radians(az), math.radians(el)
    return v(f=math.cos(e) * math.cos(a), u=math.sin(e), r=math.cos(e) * math.sin(a))

def plane(tilt=0.0, bank=0.0):
    """The normal of the plane a cut sweeps in. Straight up is a flat horizontal sweep; [tilt] degrees toward the left
    drops the left side (a forehand that descends), [bank] degrees toward the front drops the front."""
    a = unit(math.cos(math.radians(tilt)) * U + math.sin(math.radians(tilt)) * L)
    return unit(math.cos(math.radians(bank)) * a + math.sin(math.radians(bank)) * F)

def L_dirs(a, phi, roll=0.0, side=R):
    """Arm and blade directions with the L turned [phi] degrees about the plane normal [a]. At 0 the arm points to
    [side] and the blade ahead; turning one way sweeps from the right across the front to the left (a forehand, [side]
    the right and [a] the plane's normal), turning the other about -[a] with [side] the left sweeps back (a backhand).
    [roll] turns the blade about the forearm: the wrist."""
    d0 = unit(side - np.dot(side, a) * a)
    b0 = np.cross(a, d0)
    Q = _rot(a, phi)
    d, b = Q @ d0, Q @ b0
    if roll:
        b = _rot(d, roll) @ b
    return d, b

def sweep(c, t, a, phi, roll=0.0, ease='inout', back=False):
    """Keys the sword arm of [c] at [t]: a forehand's L turned [phi] about [a] (a backhand's, with [back]), wrist
    rolled [roll]. The torso (and root) keys must be in already."""
    Rt = qmat(facing(c, t))
    d, b = L_dirs(-a if back else a, phi, roll, side=L if back else R)
    c.key('right_arm', t, arm(Rt.T @ d, Rt.T @ b, geo=G), ease=ease)

def ramp(s, peak=0.5):
    """How far through its swing (0 to 1) a thrown blade has got at the fraction [s] of the time: it gathers speed up to
    [peak] of the way through, is quickest there, and slows after."""
    s = min(1.0, max(0.0, s))
    if s <= peak:
        return peak * (s / peak) ** 2
    w = (s - peak) / (1 - peak)
    return peak + (1 - peak) * (1 - (1 - w) ** 2)

def swing_keys(t0, t1, peak=0.5, step=0.5):
    """(tick, how far through) at every half tick from [t0] to [t1], for keying the body in step with a blow."""
    n = max(1, int(round((t1 - t0) / step)))
    return [(t0 + (t1 - t0) * i / n, ramp(i / n, peak)) for i in range(n + 1)]

def arc(c, t0, t1, a, phi0, phi1, back=False, roll0=0.0, roll1=0.0, roll_done=1.0, peak=0.5, lead='out', step=0.5):
    """Keys the sword arm every half tick from [t0] to [t1] as a blade thrown round the plane [a]: the L turns from [phi0]
    to [phi1] degrees, fastest [peak] of the way through, while the wrist rolls from [roll0] to [roll1] (done by the
    fraction [roll_done] of the way). [lead] eases the arm into the first key from whatever came before."""
    n = max(1, int(round((t1 - t0) / step)))
    for i in range(n + 1):
        s = i / n
        u = ramp(s, peak)
        r = roll0 + (roll1 - roll0) * min(1.0, u / roll_done)
        sweep(c, t0 + (t1 - t0) * s, a, phi0 + (phi1 - phi0) * u, roll=r, back=back, ease=lead if i == 0 else 'linear')

def point(c, t, d, b, ease='inout'):
    """Keys the sword arm along [d] with the blade near [b], both in the character's frame."""
    Rt = qmat(facing(c, t))
    c.key('right_arm', t, arm(Rt.T @ unit(d), Rt.T @ unit(b), geo=G), ease=ease)

def larm(c, t, f=0.0, u=-1.0, r=-0.3, ease='inout'):
    """Keys the free arm along (forward, up, right) in the torso's own frame: it turns with the body (r is negative for out to
    the left)."""
    c.key('left_arm', t, free_arm(v(f=f, u=u, r=r)), ease=ease)

def body(c, t, y=0.0, x=0.0, z=0.0, ease='inout'):
    """Keys the torso: [y] turns it to the right, [x] bends it forward, [z] tips it to the left."""
    c.key('torso', t, qmul(qeuler(y=y), qmul(qeuler(z=z), qeuler(x=x))), ease=ease)

def hips(c, t, x=0.0, y=0.0, z=0.0, yaw=0.0, ease='inout'):
    """Keys the root: shifted [x] to the left, [y] down and [z] back in pixels, the whole body turned [yaw] to the right."""
    c.key('root', t, qeuler(y=yaw), pos=(x, y, z), ease=ease)

def stance(c, t, right, left, rz=0.0, lz=0.0, ease='inout'):
    """Keys both legs: [right] and [left] swing about the hip (negative strides forward), [rz]/[lz] spread them
    (the right leg turns out with positive [rz], the left with negative [lz])."""
    c.key('right_leg', t, qeuler(x=right, z=rz), ease=ease)
    c.key('left_leg', t, qeuler(x=left, z=lz), ease=ease)

def _low_foot(c, t):
    """The height (model y; 24 is the floor) of the lowest foot corner with the legs as keyed at [t] and the root level."""
    low = -1e9
    for leg in ('right_leg', 'left_leg'):
        m = qmat(c.sample(leg, t)[0])
        for x in (-2.0, 2.0):
            for z in (-2.0, 2.0):
                low = max(low, 12.0 + float((m @ np.array([x, 12.0, z]))[1]))
    return low

def ground(c, lift=()):
    """Plants the feet: the root's height is re-keyed at every half tick so the lowest foot rests on the floor, whatever
    the legs are doing (a stride or a spread lowers the body by itself). [lift] is a list of (tick, pixels) keys that raise
    the whole body off the floor (a leap). Call it once the root and leg keys are in."""
    ts = [i / 2 for i in range(int(c.length * 2) + 1)]
    lifts = sorted(lift)
    def raised(t):
        if not lifts: return 0.0
        if t <= lifts[0][0]: return lifts[0][1]
        for (a, pa), (b, pb) in zip(lifts, lifts[1:]):
            if t <= b: return pa + (pb - pa) * (t - a) / (b - a)
        return lifts[-1][1]
    keys = []
    for t in ts:
        q, p = c.sample('root', t)
        keys.append((t, q, np.array([p[0], 24.0 - raised(t) - _low_foot(c, t), p[2]]), 'linear'))
    c.keys['root'] = keys
    return c

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    off, q = fp(hand, blade, edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

def cut(clip, t, hand, blade, motion, ease='inout'):
    """A first-person key whose edge leads along [motion]."""
    b = unit(np.asarray(blade, float))
    m = np.asarray(motion, float)
    fk(clip, t, hand, b, unit(m - np.dot(m, b) * b), ease)

def fpv(theta, lean=0.0):
    """A direction in first person: [theta] degrees round the screen (0 to the right, 90 up), leaning [lean] into the screen."""
    t = math.radians(theta)
    return vv(r=math.cos(t), u=math.sin(t), f=lean)

def fparc(clip, t0, t1, hand0, hand1, th0, th1, lean=0.2, ccw=True, push=0.0, peak=0.5, lead='out', step=0.5):
    """Keys a first-person blow every half tick: the hand goes from [hand0] to [hand1] while the blade turns on the screen from
    [th0] to [th1] degrees (counter-clockwise for a forehand, [ccw]), edge first, with the same quickening and slowing as
    the third-person [arc]; [push] drives the hand ahead at the middle of the blow."""
    n = max(1, int(round((t1 - t0) / step)))
    h0, h1 = np.asarray(hand0, float), np.asarray(hand1, float)
    for i in range(n + 1):
        s = i / n
        u = ramp(s, peak)
        th = th0 + (th1 - th0) * u
        hand = h0 + (h1 - h0) * u + np.array([0.0, 0.0, -push * math.sin(math.pi * u)])
        cut(clip, t0 + (t1 - t0) * s, hand, fpv(th, lean), fpv(th + (90 if ccw else -90)), ease=lead if i == 0 else 'linear')

SWING = plane(tilt=12, bank=8)        # forehand falling to the left, backhand rising to the right
FLAT = plane(tilt=0, bank=4)          # the full flat sweep

# ------------------------------------------------------------------ light 1: a wide forehand, right to left, falling a little
c = tp('light_1', 11, blend_in=1.0, blend_out=4.0).mark('hit', 3).mark('chain', 6)
body(c, 0, y=8)
body(c, 2.25, y=42, x=-1, z=-4, ease='out')
body(c, 3.25, y=0, x=4, z=1, ease='in')
body(c, 4.25, y=-40, x=8, z=5, ease='out')
body(c, 5.5, y=-46, x=8, z=6)
body(c, 6.5, y=-46, x=7, z=6)
body(c, 11, y=-6)
c.key('right_arm', 0, rest_arm())
arc(c, 2.5, 4.5, SWING, -35, 125, roll0=45, roll1=-5, roll_done=0.2)
# the blade whips round and the hand comes back across the chest: the backhand's guard
sweep(c, 5.5, SWING, -12, back=True, ease='out')
c.key('right_arm', 7.0, c.sample('right_arm', 5.5)[0])
c.key('right_arm', 8.5, arm(v(f=0.7, u=-0.7, r=-0.25), v(r=-0.6, f=0.8), geo=G), ease='inout')
c.key('right_arm', 11, rest_arm())
c.key('left_arm', 0, free_arm(D))
larm(c, 2.5, f=0.55, u=0.15, r=-0.75, ease='out')
larm(c, 4.5, f=0.1, u=0.25, r=-0.95)
larm(c, 7.0, f=-0.2, u=0.1, r=-0.95)
c.key('left_arm', 11, free_arm(D))
hips(c, 0)
hips(c, 2.5, z=0.4, yaw=10, ease='out')
hips(c, 3.75, x=0.8, z=-2.0, yaw=-4)
hips(c, 7.0, x=0.6, z=-1.2, yaw=-10)
hips(c, 11)
stance(c, 0, 0, 0)
stance(c, 1.0, 6, -20, 3, -4, ease='out')
stance(c, 2.0, 10, -26, 5, -5)
stance(c, 3.0, 14, -30, 6, -6)
stance(c, 6.0, 12, -24, 5, -5)
stance(c, 11, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/light_1', 11, blend_in=1.0, blend_out=4.0)
fk(f, 0, fp_rest(G), G.fp_blade)
fparc(f, 2.5, 4.5, (0.62, -0.30, -0.76), (-0.28, -0.36, -0.80), 95, 168, push=0.06)
fk(f, 5.5, (-0.44, -0.38, -0.76), fpv(140, 0.2), fpv(230), ease='out')
fk(f, 6.5, (-0.44, -0.38, -0.76), fpv(138, 0.2), fpv(228), ease='inout')
fk(f, 8.0, (-0.1, -0.45, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 11, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ light 2: a wide backhand, left to right, rising a little
c = tp('light_2', 11, blend_in=1.0, blend_out=4.0).mark('hit', 3).mark('chain', 6)
body(c, 0, y=-46, x=7, z=6)
body(c, 2.25, y=-62, x=4, z=7, ease='out')
body(c, 2.75, y=-62, x=4, z=7, ease='inout')
body(c, 3.5, y=-12, x=3, z=0, ease='in')
body(c, 4.25, y=44, x=-2, z=-5, ease='out')
body(c, 5.5, y=48, x=-2, z=-5)
body(c, 6.5, y=48, x=-2, z=-5)
body(c, 11, y=8)
sweep(c, 0, SWING, -15, back=True)
arc(c, 2.5, 4.5, SWING, -27, 105, back=True, roll0=15, roll1=-12, roll_done=0.7)
sweep(c, 6.5, SWING, 112, roll=-12, back=True, ease='out')
c.key('right_arm', 11, rest_arm())
c.key('left_arm', 0, free_arm(v(f=-0.2, u=0.1, r=-0.95)))
larm(c, 2.5, f=0.5, u=0.45, r=-0.7, ease='out')
larm(c, 4.5, f=-0.1, u=0.2, r=-0.95)
larm(c, 6.5, f=-0.3, u=0.1, r=-0.9)
c.key('left_arm', 11, free_arm(D))
hips(c, 0, x=0.6, z=-1.2, yaw=-10)
hips(c, 2.5, x=0.2, z=0.4, yaw=-16, ease='out')
hips(c, 2.75, x=0.2, z=0.4, yaw=-16)
hips(c, 3.75, x=-0.8, z=-2.0, yaw=4)
hips(c, 7.0, x=-0.6, z=-1.2, yaw=10)
hips(c, 11)
stance(c, 0, 12, -24, 5, -5)
stance(c, 1.0, -8, 14, 4, -4, ease='out')
stance(c, 2.0, -24, 10, 5, -5)
stance(c, 3.0, -30, 14, 6, -6)
stance(c, 6.0, -24, 12, 5, -5)
stance(c, 11, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/light_2', 11, blend_in=1.0, blend_out=4.0)
fk(f, 0, (-0.44, -0.38, -0.76), fpv(138, 0.2), fpv(228))
fk(f, 1.5, (-0.55, -0.39, -0.76), fpv(121, 0.1), fpv(211), ease='out')
fk(f, 2.0, (-0.57, -0.40, -0.76), fpv(115, 0.05), fpv(25), ease='inout')       # the wrist turns the edge over
fk(f, 2.5, (-0.58, -0.40, -0.76), fpv(112, 0.0), fpv(22, 0.2), ease='out')
fparc(f, 2.5, 4.5, (-0.58, -0.40, -0.76), (0.30, -0.36, -0.80), 112, 30, ccw=False, push=0.06, lead='out')
fk(f, 5.0, (0.44, -0.36, -0.76), fpv(45, 0.2), fpv(135), ease='out')
fk(f, 6.5, (0.46, -0.36, -0.76), fpv(48, 0.2), fpv(138), ease='inout')
fk(f, 8.0, (0.52, -0.46, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 11, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ light 3: a turning sweep, flat and wide, all the way round
c = tp('light_3', 15, blend_in=1.5, blend_out=5.0).mark('hit', 5).mark('chain', 10)
body(c, 0, y=48, x=-2, z=-5)
body(c, 3.5, y=64, x=-3, z=-6, ease='out')
body(c, 4.0, y=64, x=-3, z=-6)
body(c, 5.25, y=0, x=4, z=2, ease='in')
body(c, 6.5, y=-50, x=7, z=6, ease='out')
body(c, 8.5, y=-52, x=6, z=6)
body(c, 15, y=-4)
sweep(c, 0, SWING, 110, roll=-12, back=True)
point(c, 2.0, aim(95, 10), aim(125, 80), ease='inout')
arc(c, 4.25, 6.75, FLAT, -55, 135, roll0=30, roll1=-5, roll_done=0.3)
c.key('right_arm', 9.0, c.sample('right_arm', 6.75)[0])
c.key('right_arm', 11.5, arm(v(f=0.7, u=-0.7, r=-0.25), v(r=-0.6, f=0.8), geo=G), ease='inout')
c.key('right_arm', 15, rest_arm())
c.key('left_arm', 0, free_arm(v(f=-0.3, u=0.1, r=-0.9)))
larm(c, 3.5, f=0.3, u=0.2, r=-0.9, ease='out')
larm(c, 6.0, f=0.0, u=0.35, r=-0.95)
larm(c, 9.0, f=-0.5, u=0.2, r=-0.85)
c.key('left_arm', 15, free_arm(D))
hips(c, 0, x=-0.6, z=-1.2, yaw=8)
hips(c, 3.5, x=0.0, z=0.4, yaw=16, ease='out')
hips(c, 4.25, x=0.0, z=0.4, yaw=16)
hips(c, 6.5, x=0.8, z=-2.4, yaw=-34, ease='inout')
hips(c, 9.0, x=0.6, z=-1.6, yaw=-36)
hips(c, 15)
stance(c, 0, -24, 12, 5, -5)
stance(c, 2.0, 6, -14, 5, -5, ease='out')
stance(c, 3.0, 16, -34, 6, -6)
stance(c, 5.5, 14, -34, 6, -6)
stance(c, 9.0, 12, -26, 5, -5)
stance(c, 15, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/light_3', 15, blend_in=1.5, blend_out=5.0)
fk(f, 0, (0.46, -0.36, -0.76), fpv(48, 0.2), fpv(138))
fk(f, 2.0, (0.62, -0.32, -0.74), fpv(100, 0.0), fpv(190), ease='out')
fparc(f, 4.25, 6.75, (0.66, -0.30, -0.76), (-0.30, -0.36, -0.80), 100, 170, push=0.08, lead='inout')
fk(f, 8.5, (-0.44, -0.38, -0.76), fpv(140, 0.2), fpv(230), ease='out')
fk(f, 10.0, (-0.44, -0.38, -0.76), fpv(138, 0.2), fpv(228), ease='inout')
fk(f, 12.0, (-0.1, -0.45, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 15, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ heavy: coiled behind the shoulder, then one big diagonal cut
DIAG = plane(tilt=40, bank=10)
c = tp('heavy', 20, blend_in=1.5, blend_out=5.0).mark('hold', 5).mark('hit', 8).mark('chain', 13)
body(c, 0)
body(c, 5.0, y=55, x=-10, z=-7, ease='out')
body(c, 6.5, y=62, x=-12, z=-8, ease='inout')
for t, u in swing_keys(6.75, 9.75):
    body(c, t, y=62 - 110 * u, x=-12 + 38 * u, z=-8 + 16 * u, ease='linear')
body(c, 12, y=-48, x=26, z=8)
body(c, 15, y=-22, x=12, z=3, ease='inout')
body(c, 20, y=0)
c.key('right_arm', 0, rest_arm())
arc(c, 7.0, 10.0, DIAG, -62, 128, roll0=70, roll1=-8, roll_done=0.5, lead='out')
c.key('right_arm', 12, c.sample('right_arm', 10.0)[0])
c.key('right_arm', 15, arm(v(f=0.7, u=-0.7, r=-0.25), v(r=-0.6, f=0.8), geo=G), ease='inout')
c.key('right_arm', 20, rest_arm())
c.key('left_arm', 0, free_arm(D))
larm(c, 5.0, f=0.4, u=0.25, r=-0.9, ease='out')
larm(c, 6.5, f=0.35, u=0.3, r=-0.9)
larm(c, 9.0, f=0.0, u=0.45, r=-0.95, ease='inout')
larm(c, 12, f=-0.3, u=0.2, r=-0.9)
c.key('left_arm', 20, free_arm(D))
hips(c, 0)
hips(c, 5.0, z=1.6, yaw=12, ease='out')
hips(c, 6.5, z=2.0, yaw=16)
hips(c, 8.0, x=0.6, z=-1.0, yaw=0, ease='in')
hips(c, 9.5, x=1.0, z=-3.4, yaw=-28, ease='out')
hips(c, 12, x=0.8, z=-3.0, yaw=-30)
hips(c, 20)
stance(c, 0, 0, 0)
stance(c, 5.0, 18, -10, 8, -8, ease='out')
stance(c, 6.5, 20, -10, 10, -10)
stance(c, 7.0, 20, -14, 10, -10)
stance(c, 8.0, 18, -38, 8, -8, ease='out')
stance(c, 12, 14, -36, 8, -8)
stance(c, 15, 8, -20, 4, -4)
stance(c, 20, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/heavy', 20, blend_in=1.5, blend_out=5.0)
fk(f, 0, fp_rest(G), G.fp_blade)
fk(f, 5.0, (0.58, -0.46, -0.66), fpv(82, -0.1), fpv(172, 0.2), ease='out')
fk(f, 6.5, (0.58, -0.44, -0.64), fpv(84, -0.1), fpv(174, 0.2), ease='inout')
fparc(f, 7.0, 10.0, (0.58, -0.44, -0.66), (-0.30, -0.30, -0.82), 84, 172, push=0.08, lead='inout')
fk(f, 12, (-0.44, -0.34, -0.76), fpv(150, 0.2), fpv(240), ease='out')
fk(f, 15, (-0.2, -0.44, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 20, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ running: a sweeping slash, low and wide
LOW = plane(tilt=10, bank=22)
c = tp('running', 14, blend_in=1.0, blend_out=4.0).mark('hit', 4).mark('chain', 9)
body(c, 0, y=26, x=14)
body(c, 2.75, y=42, x=18, z=-3, ease='out')
for t, u in swing_keys(3.0, 6.5):
    body(c, t, y=42 - 80 * u, x=18 + 6 * u, z=-3 + 8 * u, ease='linear')
body(c, 8.5, y=-38, x=18, z=5, ease='inout')
body(c, 14, y=0)
c.key('right_arm', 0, rest_arm())
arc(c, 3.25, 6.75, LOW, -60, 125, roll0=15, roll1=-12, roll_done=0.6)
c.key('right_arm', 8.5, c.sample('right_arm', 6.75)[0])
c.key('right_arm', 11, arm(v(f=0.7, u=-0.7, r=-0.25), v(r=-0.6, f=0.8), geo=G), ease='inout')
c.key('right_arm', 14, rest_arm())
c.key('left_arm', 0, free_arm(v(f=-0.5, u=-0.8, r=-0.3)))
larm(c, 3.0, f=0.6, u=-0.1, r=-0.75, ease='out')
larm(c, 6.5, f=-0.4, u=0.3, r=-0.85)
larm(c, 8.5, f=-0.5, u=0.1, r=-0.8)
c.key('left_arm', 14, free_arm(D))
hips(c, 0, z=-1.0)
hips(c, 2.75, x=-0.4, z=0.2, yaw=14, ease='out')
hips(c, 4.5, x=0.4, z=-1.8, yaw=0)
hips(c, 6.75, x=0.8, z=-2.6, yaw=-22, ease='out')
hips(c, 8.5, x=0.6, z=-2.4, yaw=-24)
hips(c, 14)
stance(c, 0, -30, 26, 2, -2)
stance(c, 1.5, -12, 10, 3, -3)
stance(c, 2.5, 18, -18, 4, -4)
stance(c, 4.0, 34, -46, 6, -6)
stance(c, 6.75, 26, -38, 5, -5)
stance(c, 9, 14, -20, 3, -3)
stance(c, 14, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/running', 14, blend_in=1.0, blend_out=4.0)
fk(f, 0, fp_rest(G), G.fp_blade)
fk(f, 3.25, (0.68, -0.44, -0.74), fpv(74, 0.15), fpv(164, 0.3), ease='out')
fparc(f, 3.25, 6.75, (0.68, -0.44, -0.74), (-0.38, -0.46, -0.80), 74, 166, push=0.08, lead='out')
fk(f, 8.5, (-0.46, -0.44, -0.76), fpv(140, 0.2), fpv(230), ease='out')
fk(f, 10.5, (-0.1, -0.47, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 14, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ jump: a diagonal cut falling out of the air
JUMP = plane(tilt=40, bank=16)
c = tp('jump', 14, blend_in=1.0, blend_out=4.0).mark('hit', 4).mark('chain', 10)
body(c, 0)
body(c, 3.0, y=40, x=-8, z=-5, ease='out')
body(c, 3.75, y=44, x=-8, z=-6, ease='inout')
for t, u in swing_keys(3.75, 7.5):
    body(c, t, y=44 - 86 * u, x=-8 + 34 * u, z=-6 + 12 * u, ease='linear')
body(c, 9.5, y=-40, x=22, z=6, ease='inout')
body(c, 14, y=0)
c.key('right_arm', 0, rest_arm())
arc(c, 4.0, 7.0, JUMP, -62, 125, roll0=70, roll1=-8, roll_done=0.5, lead='out')
c.key('right_arm', 9.5, c.sample('right_arm', 7.0)[0])
c.key('right_arm', 11.5, arm(v(f=0.7, u=-0.7, r=-0.25), v(r=-0.6, f=0.8), geo=G), ease='inout')
c.key('right_arm', 14, rest_arm())
c.key('left_arm', 0, free_arm(D))
larm(c, 3.0, f=0.4, u=0.4, r=-0.85, ease='out')
larm(c, 5.5, f=0.1, u=0.5, r=-0.9)
larm(c, 8.0, f=-0.3, u=0.3, r=-0.9)
c.key('left_arm', 14, free_arm(D))
hips(c, 0)
hips(c, 3.0, z=1.0, yaw=12, ease='out')
hips(c, 5.5, x=0.6, z=-1.0, yaw=0)
hips(c, 8.0, x=0.8, z=-2.2, yaw=-24, ease='out')
hips(c, 14)
stance(c, 0, 0, 0)
stance(c, 2.0, -42, -18, 5, -5, ease='out')
stance(c, 4.0, -50, -24, 6, -6)
stance(c, 6.0, -30, 6, 6, -6)
stance(c, 7.5, 16, -34, 8, -8, ease='out')
stance(c, 10, 10, -24, 6, -6)
stance(c, 14, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/jump', 14, blend_in=1.0, blend_out=4.0)
fk(f, 0, fp_rest(G), G.fp_blade)
fk(f, 3.0, (0.58, -0.44, -0.68), fpv(84, -0.1), fpv(174, 0.2), ease='out')
fparc(f, 4.0, 7.0, (0.58, -0.44, -0.68), (-0.30, -0.34, -0.82), 84, 168, push=0.08, lead='inout')
fk(f, 9.5, (-0.44, -0.38, -0.76), fpv(150, 0.2), fpv(240), ease='out')
fk(f, 11.5, (-0.1, -0.46, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 14, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ guard counter: a quick rising cut, low left to high right
RISE = plane(tilt=36, bank=0)
c = tp('guard_counter', 15, blend_in=1.0, blend_out=5.0).mark('hit', 5).mark('chain', 10)
body(c, 0, y=-20)
body(c, 3.0, y=-80, x=8, z=6, ease='out')
body(c, 4.5, y=-80, x=8, z=6)
body(c, 5.5, y=-34, x=4, z=0, ease='in')
body(c, 6.5, y=40, x=-3, z=-5, ease='out')
body(c, 8, y=44, x=-3, z=-5)
body(c, 15, y=0)
c.key('right_arm', 0, rest_arm())
sweep(c, 1.75, RISE, -12, roll=22, back=True, ease='out')
arc(c, 4.5, 6.5, RISE, -24, 105, back=True, roll0=22, roll1=-12, lead='out')
c.key('right_arm', 9, c.sample('right_arm', 6.5)[0])
c.key('right_arm', 15, rest_arm())
c.key('left_arm', 0, free_arm(v(f=1.0, u=0.0, r=-0.1)))
larm(c, 3.0, f=0.4, u=0.1, r=-0.9, ease='out')
larm(c, 6.0, f=-0.2, u=0.35, r=-0.95)
larm(c, 9.0, f=-0.3, u=0.2, r=-0.9)
c.key('left_arm', 15, free_arm(D))
hips(c, 0)
hips(c, 3.0, x=0.2, z=0.6, yaw=-28, ease='out')
hips(c, 4.5, x=0.2, z=0.4, yaw=-28)
hips(c, 5.75, x=-0.6, z=-2.2, yaw=2, ease='inout')
hips(c, 8, x=-0.6, z=-1.6, yaw=10)
hips(c, 15)
stance(c, 0, 0, 0)
stance(c, 2.5, 14, -10, 4, -4, ease='out')
stance(c, 4.0, -34, 16, 6, -6)
stance(c, 6.0, -30, 14, 6, -6)
stance(c, 9.0, -22, 10, 4, -4)
stance(c, 15, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/guard_counter', 15, blend_in=1.0, blend_out=5.0)
fk(f, 0, fp_rest(G), G.fp_blade)
fk(f, 1.0, (0.22, -0.55, -0.76), fpv(108, 0.1), fpv(198), ease='out')
fk(f, 1.5, (0.06, -0.54, -0.76), fpv(117, 0.15), fpv(27), ease='inout')        # the wrist turns the edge over
fk(f, 4.5, (-0.40, -0.50, -0.76), fpv(125, 0.2), fpv(35), ease='out')
fparc(f, 4.5, 6.5, (-0.40, -0.50, -0.76), (0.30, -0.12, -0.80), 125, 40, ccw=False, push=0.06, lead='out')
fk(f, 7.0, (0.36, -0.11, -0.78), fpv(47, 0.2), fpv(137), ease='inout')       # the wrist turns the edge back over
fk(f, 8.0, (0.40, -0.10, -0.76), fpv(52, 0.2), fpv(142), ease='out')
fk(f, 10.0, (0.48, -0.30, -0.76), fpv(60, 0.2), fpv(150), ease='inout')
fk(f, 12.0, (0.55, -0.48, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 15, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ skill: Crescent Dash, a low dash and two cuts that cross
X1 = plane(tilt=40, bank=14)           # falling right to left
X2 = plane(tilt=-34, bank=12)          # falling left to right
c = tp('skill', 22, blend_in=1.5, blend_out=5.0).mark('hit', 6).mark('hit 2', 10).mark('chain', 17)
body(c, 0)
body(c, 3.5, y=52, x=10, z=-5, ease='out')
for t, u in swing_keys(5.0, 7.5):
    body(c, t, y=52 - 98 * u, x=10 + 26 * u, z=-5 + 11 * u, ease='linear')
body(c, 9.0, y=-58, x=30, z=6, ease='inout')
body(c, 9.75, y=-58, x=30, z=6)
body(c, 10.75, y=-14, x=32, z=0, ease='in')
body(c, 12.0, y=40, x=28, z=-6, ease='out')
body(c, 14, y=44, x=26, z=-6)
body(c, 18, y=14, x=10, z=0, ease='inout')
body(c, 22, y=0)
c.key('right_arm', 0, rest_arm())
arc(c, 5.25, 7.75, X1, -60, 110, roll0=30, roll1=10, roll_done=0.5, lead='out')
arc(c, 9.25, 11.75, X2, -18, 100, back=True, roll0=-70, roll1=0, roll_done=0.55, lead='inout')
c.key('right_arm', 14, c.sample('right_arm', 11.75)[0])
c.key('right_arm', 18, arm(v(f=0.7, u=-0.7, r=-0.25), v(r=-0.6, f=0.8), geo=G), ease='inout')
c.key('right_arm', 22, rest_arm())
c.key('left_arm', 0, free_arm(D))
larm(c, 3.5, f=0.6, u=0.2, r=-0.75, ease='out')
larm(c, 7.5, f=-0.2, u=0.35, r=-0.95, ease='inout')
larm(c, 9.75, f=0.5, u=0.3, r=-0.8)
larm(c, 12.0, f=-0.3, u=0.3, r=-0.9)
larm(c, 14.0, f=-0.3, u=0.2, r=-0.9)
c.key('left_arm', 22, free_arm(D))
hips(c, 0)
hips(c, 3.5, z=1.6, yaw=14, ease='out')
hips(c, 5.0, x=0.4, z=-1.0, yaw=8, ease='in')
hips(c, 7.5, x=0.8, z=-4.0, yaw=-28, ease='out')
hips(c, 9.5, x=0.4, z=-4.4, yaw=-30)
hips(c, 11.5, x=-0.6, z=-4.2, yaw=10, ease='inout')
hips(c, 14, x=-0.4, z=-3.6, yaw=12)
hips(c, 22)
stance(c, 0, 0, 0)
stance(c, 2.0, 8, -8, 3, -3, ease='out')
stance(c, 3.5, 20, -16, 8, -8)
stance(c, 5.0, 36, -50, 8, -8, ease='in')
stance(c, 7.5, 38, -56, 8, -8)
stance(c, 9.5, 24, -26, 6, -6, ease='inout')
stance(c, 11.0, -46, 34, 8, -8, ease='out')
stance(c, 14, -40, 30, 6, -6)
stance(c, 18, -16, 12, 3, -3)
stance(c, 22, 0, 0)
ground(c)
clips.append(c)

f = Clip('sabre/skill', 22, blend_in=1.5, blend_out=5.0)
fk(f, 0, fp_rest(G), G.fp_blade)
fk(f, 3.5, (0.68, -0.40, -0.72), fpv(80, 0.15), fpv(170, 0.3), ease='out')
fparc(f, 5.25, 7.75, (0.68, -0.40, -0.74), (-0.34, -0.42, -0.82), 80, 165, push=0.08, lead='inout')
fk(f, 8.25, (-0.46, -0.37, -0.80), fpv(150, 0.15), fpv(60), ease='inout')       # the wrist turns the edge over
fk(f, 9.25, (-0.56, -0.30, -0.76), fpv(118, 0.15), fpv(28, 0.2), ease='inout')
fparc(f, 9.25, 11.75, (-0.56, -0.30, -0.76), (0.34, -0.46, -0.82), 118, 22, ccw=False, push=0.08, lead='inout')
fk(f, 12.25, (0.40, -0.46, -0.78), fpv(30, 0.2), fpv(120), ease='inout')       # and back again
fk(f, 14.0, (0.50, -0.44, -0.76), fpv(40, 0.2), fpv(130), ease='out')
fk(f, 18.0, (0.56, -0.50, -0.78), vv(u=0.95, r=0.1, f=0.15), vv(r=-0.95, u=0.1), ease='inout')
fk(f, 22, fp_rest(G), G.fp_blade)
fps.append(f)

# ------------------------------------------------------------------ output

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
        if c.name not in only and c.name.split('/')[-1] not in only:
            continue
        sheet(scene, c, by_name.get(c.name)).save(os.path.join(previews, c.name.split('/')[-1] + '.png'))
