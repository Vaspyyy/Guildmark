"""
The spear's combat moves. Third-person clips pose the skeleton; first-person clips move the held spear (item).
Run:

    python3 -B art/scripts/anims_spear.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_spear_in_hand.png>

Timings match Move.kt: a clip lasts as many ticks as its move, and its blow lands inside the move's hit window.

How the spear sits: held in one hand near the middle of the shaft, point forward, the shaft square to the forearm
(like a fist round a pole). So the arm can only hang, or reach up or out to the side, while the point goes forward;
a thrust is the body driving the hand: the shoulder swings through (torso twist and lean, the root's step) and the
arm only trims the line. The helpers below aim the point in the character's frame and work out the arm from the
torso, so the point stays on its line through the twist.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import SPEAR as G

def tp(name, length, **kw):
    return Clip(name, length, **kw)

clips, fps = [], []

# ------------------------------------------------------------------ aiming helpers

def sp(left=0.0, up=0.0):
    """A direction for the point, in the character's frame: [left] degrees to its left, [up] degrees above level."""
    l, u = math.radians(left), math.radians(up)
    return v(f=math.cos(u) * math.cos(l), u=math.sin(u), r=-math.cos(u) * math.sin(l))

def lean_twist(twist=0.0, lean=0.0, tilt=0.0):
    """The torso: [twist] degrees to the character's right (the right shoulder back), [lean] forward, [tilt] to the left."""
    return qmul(qmul(qeuler(z=tilt), qeuler(y=twist)), qeuler(x=lean))

def hold(tq, point, hang=D):
    """The right arm (relative to a torso turned [tq]) holding the spear with its point along [point] (character
    frame) and the arm hanging as near [hang] as the shaft allows: the fist is square to the shaft."""
    b = unit(point)
    d = np.asarray(hang, float)
    d = d - np.dot(d, b) * b
    d = d / np.linalg.norm(d)
    M = qmat(tq).T
    return arm(M @ d, M @ b, geo=G)

def free(tq, direction, palm=None):
    """The free left arm (relative to a torso turned [tq]) pointing along [direction] in the character's frame."""
    M = qmat(tq).T
    return free_arm(M @ unit(direction), None if palm is None else M @ unit(palm))

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F, geo=G)

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    """A first-person key: the grip at [hand] (camera space), the point along [blade]."""
    off, q = fp(hand, blade, edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

def flat(blade, face=vv(r=1.0)):
    """The edge direction that turns the spear's broad face toward [face] (camera space) whatever way it points."""
    b = unit(np.asarray(blade, float))
    e = np.cross(b, face)
    if np.linalg.norm(e) < 1e-3:
        e = np.cross(b, vv(u=1.0))
    return unit(e)

def fs(clip, t, hand, blade, face=vv(r=1.0), ease='inout'):
    """A first-person key with the broad face of the spear turned toward [face]."""
    fk(clip, t, hand, blade, flat(blade, face), ease)

REST = (fp_rest(G), G.fp_blade)

# ------------------------------------------------------------------ light 1: a quick thrust from the hip, driven off the back foot
c = tp('spear/light_1', 13, blend_in=1.0, blend_out=4.0).mark('hit', 5).mark('chain', 8)
T3 = lean_twist(twist=38, lean=-6)
T35 = lean_twist(twist=43, lean=-8)
T55 = lean_twist(twist=-32, lean=12)
T7 = lean_twist(twist=-28, lean=11)
c.key('torso', 0)
c.key('torso', 3.0, T3, ease='out')
c.key('torso', 3.5, T35, ease='linear')
c.key('torso', 5.5, T55, ease='in')
c.key('torso', 7.0, T7, ease='out')
c.key('torso', 13, I, ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 3.0, hold(T3, sp(left=6, up=8)), pos=(-1.2, 0, 1.2), ease='out')
c.key('right_arm', 3.5, hold(T35, sp(left=6, up=8)), pos=(-1.5, 0, 1.6), ease='linear')
c.key('right_arm', 5.5, hold(T55, sp(left=8, up=14)), pos=(0, 0, -1.4), ease='in')
c.key('right_arm', 7.0, hold(T7, sp(left=8, up=15)), pos=(0, 0, -1.1), ease='out')
c.key('right_arm', 13, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 3.0, free(T3, sp(left=-30, up=-12)), ease='out')
c.key('left_arm', 5.5, free(T55, sp(left=-34, up=-10)), ease='in')
c.key('left_arm', 13, free_arm(D), ease='inout')
c.key('right_leg', 0)
c.key('right_leg', 3.0, qeuler(x=8), ease='out')
c.key('right_leg', 5.5, qeuler(x=24), ease='in')
c.key('right_leg', 13, qeuler(x=0), ease='inout')
c.key('left_leg', 0)
c.key('left_leg', 3.0, qeuler(x=-12), ease='out')
c.key('left_leg', 5.5, qeuler(x=-30), ease='in')
c.key('left_leg', 13, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 3.0, pos=(0, 0.2, 1.4), ease='out')
c.key('root', 3.5, pos=(0, 0.3, 1.6), ease='linear')
c.key('root', 5.5, pos=(0, 0.8, -3.0), ease='in')
c.key('root', 7.0, pos=(0, 0.7, -2.6), ease='out')
c.key('root', 13, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/light_1', 13, blend_in=1.0, blend_out=4.0)
fk(f, 0, *REST)
fs(f, 3.0, (0.68, -0.60, -0.45), vv(f=0.98, u=0.12, r=-0.12), ease='inout')
fs(f, 3.5, (0.70, -0.61, -0.42), vv(f=0.98, u=0.12, r=-0.12), ease='linear')
fs(f, 5.5, (0.40, -0.42, -1.05), vv(f=0.93, u=0.22, r=-0.30), ease='in')
fs(f, 7.0, (0.42, -0.44, -1.00), vv(f=0.93, u=0.23, r=-0.29), ease='out')
fk(f, 13, *REST)
fps.append(f)

# ------------------------------------------------------------------ light 2: a rising thrust toward the face, the body turned the other way
c = tp('spear/light_2', 13, blend_in=1.5, blend_out=4.0).mark('hit', 5).mark('chain', 8)
S0 = lean_twist(twist=-26, lean=12)
S3 = lean_twist(twist=26, lean=-5, tilt=3)
S35 = lean_twist(twist=30, lean=-6, tilt=3)
S55 = lean_twist(twist=4, lean=15, tilt=5)
S7 = lean_twist(twist=2, lean=13, tilt=4)
c.key('torso', 0, S0)
c.key('torso', 3.0, S3, ease='out')
c.key('torso', 3.5, S35, ease='linear')
c.key('torso', 5.5, S55, ease='in')
c.key('torso', 7.0, S7, ease='out')
c.key('torso', 13, I, ease='inout')
c.key('right_arm', 0, hold(S0, sp(left=8, up=14)), pos=(0, 0, -1.0))
c.key('right_arm', 3.0, hold(S3, sp(left=8, up=-4)), pos=(0, 0, 1.2), ease='out')
c.key('right_arm', 3.5, hold(S35, sp(left=8, up=-6)), pos=(0, 0, 1.6), ease='linear')
c.key('right_arm', 5.5, hold(S55, sp(left=10, up=34)), pos=(0, 0, -1.6), ease='in')
c.key('right_arm', 7.0, hold(S7, sp(left=10, up=36)), pos=(0, 0, -1.2), ease='out')
c.key('right_arm', 13, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free(S0, sp(left=-34, up=-10)))
c.key('left_arm', 3.0, free(S3, sp(left=-20, up=-4)), ease='out')
c.key('left_arm', 5.5, free(S55, sp(left=-14, up=14)), ease='in')
c.key('left_arm', 13, free_arm(D), ease='inout')
c.key('right_leg', 0, qeuler(x=24))
c.key('right_leg', 3.0, qeuler(x=0), ease='out')
c.key('right_leg', 5.5, qeuler(x=-26), ease='in')
c.key('right_leg', 13, qeuler(x=0), ease='inout')
c.key('left_leg', 0, qeuler(x=-30))
c.key('left_leg', 3.0, qeuler(x=0), ease='out')
c.key('left_leg', 5.5, qeuler(x=20), ease='in')
c.key('left_leg', 13, qeuler(x=0), ease='inout')
c.key('root', 0, pos=(0, 0.8, -2.6))
c.key('root', 3.0, pos=(0, 0.2, 1.0), ease='out')
c.key('root', 3.5, pos=(0, 0.3, 1.2), ease='linear')
c.key('root', 5.5, pos=(0, 0.9, -3.4), ease='in')
c.key('root', 7.0, pos=(0, 0.8, -3.0), ease='out')
c.key('root', 13, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/light_2', 13, blend_in=1.5, blend_out=4.0)
fs(f, 0, (0.42, -0.44, -1.00), vv(f=0.93, u=0.23, r=-0.29))
fs(f, 3.0, (0.64, -0.62, -0.50), vv(f=0.98, u=0.05, r=-0.12), ease='out')
fs(f, 3.5, (0.66, -0.63, -0.47), vv(f=0.98, u=0.04, r=-0.12), ease='linear')
fs(f, 5.5, (0.36, -0.34, -1.02), vv(f=0.86, u=0.46, r=-0.20), ease='in')
fs(f, 7.0, (0.38, -0.36, -0.98), vv(f=0.86, u=0.47, r=-0.20), ease='out')
fk(f, 13, *REST)
fps.append(f)

# ------------------------------------------------------------------ light 3: the sweep, the spear swung flat from right to left
c = tp('spear/light_3', 17, blend_in=1.5, blend_out=5.0).mark('hit', 7).mark('chain', 12)
# The arm is held out to the right and the shaft forward from it, level; the torso's twist does the sweeping, so the
# whole bar swings round the body. Its point crosses straight ahead when the torso is about 30 degrees to the left.
OUT = v(r=0.85, u=-0.4)
swept = arm(OUT, v(f=1.0, u=0.36), geo=G)
c.key('torso', 0, lean_twist(twist=4, lean=10))
c.key('torso', 3.0, lean_twist(twist=50, lean=0), ease='inout')
c.key('torso', 4.5, lean_twist(twist=66, lean=-4), ease='out')
c.key('torso', 7.5, lean_twist(twist=-28, lean=10), ease='in')
c.key('torso', 9.5, lean_twist(twist=-76, lean=12), ease='out')
c.key('torso', 14, lean_twist(twist=-14, lean=4), ease='inout')
c.key('torso', 17, I, ease='inout')
c.key('right_arm', 0, hold(lean_twist(twist=4, lean=10), sp(left=10, up=34)), pos=(0, 0, -1.0))
c.key('right_arm', 3.0, swept, ease='inout')
c.key('right_arm', 9.5, swept, ease='linear')
c.key('right_arm', 14, rest_arm(), ease='inout')
c.key('right_arm', 17, rest_arm(), ease='inout')
c.key('left_arm', 0, free(lean_twist(twist=4, lean=10), sp(left=-14, up=14)))
c.key('left_arm', 4.5, free_arm(v(f=0.6, r=-0.3, u=-0.4)), ease='out')
c.key('left_arm', 9.5, free(lean_twist(twist=-76, lean=12), sp(left=60, up=8)), ease='inout')
c.key('left_arm', 14, free_arm(D), ease='inout')
c.key('right_leg', 0, qeuler(x=-26))
c.key('right_leg', 4.5, qeuler(x=6), ease='out')
c.key('right_leg', 7.5, qeuler(x=22), ease='in')
c.key('right_leg', 14, qeuler(x=0), ease='inout')
c.key('left_leg', 0, qeuler(x=20))
c.key('left_leg', 4.5, qeuler(x=-6), ease='out')
c.key('left_leg', 7.5, qeuler(x=-28), ease='in')
c.key('left_leg', 14, qeuler(x=0), ease='inout')
c.key('root', 0, pos=(0, 0.8, -2.0))
c.key('root', 4.5, pos=(0, 0.2, 1.0), ease='out')
c.key('root', 7.5, pos=(0, 1.0, -3.0), ease='in')
c.key('root', 9.5, pos=(0, 1.0, -2.8), ease='out')
c.key('root', 14, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/light_3', 17, blend_in=1.5, blend_out=5.0)

def aimed(left=0.0, up=0.0):
    """A direction in camera space: [left] degrees to the left of the line of sight, [up] degrees above it."""
    l, u = math.radians(left), math.radians(up)
    return vv(f=math.cos(u) * math.cos(l), u=math.sin(u), r=-math.cos(u) * math.sin(l))

def fa(clip, t, hand, blade, ease='inout', turn=1):
    """A first-person key with the broad face of the spear turned toward the camera, so a sweep across the view
    shows the whole head. [turn] -1 shows its other side, which keeps the roll from passing edge-on on the way back
    to the rest pose."""
    mid = np.asarray(hand, float) + 0.55 * unit(blade)
    fs(clip, t, hand, blade, -mid * turn, ease)

# The point swings round the way the body does: the hand is drawn back out to the right, then circles across the front
fa(f, 0, (0.38, -0.36, -0.98), vv(f=0.86, u=0.47, r=-0.20))
for t, hand, yaw, up, ease in (
        (3.0, (0.60, -0.40, -0.66), -34, 16, 'inout'), (4.5, (0.64, -0.40, -0.62), -42, 15, 'out'),
        (6.0, (0.72, -0.40, -0.68), -40, 15, 'in'), (6.5, (0.78, -0.40, -0.72), -28, 15, 'linear'),
        (7.0, (0.78, -0.40, -0.80), -8, 14, 'linear'), (7.5, (0.66, -0.41, -0.90), 20, 14, 'linear'),
        (8.0, (0.50, -0.42, -0.98), 41, 13, 'linear'), (8.5, (0.32, -0.43, -1.04), 56, 12, 'linear'),
        (9.0, (0.14, -0.44, -1.08), 65, 12, 'out'), (9.5, (0.00, -0.46, -1.10), 68, 12, 'out')):
    fa(f, t, hand, aimed(yaw, up), ease, turn=-1 if t > 8.8 else 1)
fk(f, 14, *REST)
fk(f, 17, *REST)
fps.append(f)

# ------------------------------------------------------------------ heavy: coiled back on the rear leg, then a long lunge-thrust
c = tp('spear/heavy', 22, blend_in=1.5, blend_out=5.0).mark('hold', 6).mark('hit', 10)
H6 = lean_twist(twist=50, lean=-8, tilt=3)
H10 = lean_twist(twist=-30, lean=22)
H12 = lean_twist(twist=-26, lean=20)
c.key('torso', 0)
c.key('torso', 6.0, H6, ease='out')
c.key('torso', 10.0, H10, ease='in3')
c.key('torso', 12.0, H12, ease='out')
c.key('torso', 22, I, ease='inout')
# held far back: the hand behind the hip and out to the side of it, the point forward and level
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 6.0, hold(H6, sp(left=4, up=3), v(r=0.7, u=-0.7)), pos=(0, 0, 1.5), ease='out')
c.key('right_arm', 10.0, hold(H10, sp(left=8, up=16)), pos=(0, 0, -2.5), ease='in3')
c.key('right_arm', 12.0, hold(H12, sp(left=8, up=17)), pos=(0, 0, -2.0), ease='out')
c.key('right_arm', 22, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 6.0, free(H6, sp(left=-8, up=-6)), ease='out')
c.key('left_arm', 9.0, free(lean_twist(twist=2, lean=14), sp(left=-10, up=-4)), ease='in')
c.key('left_arm', 10.0, free(H10, sp(left=-50, up=-30)), ease='out')
c.key('left_arm', 22, free_arm(D), ease='inout')
c.key('right_leg', 0)
c.key('right_leg', 6.0, qeuler(x=20), ease='out')
c.key('right_leg', 9.0, qeuler(x=40), ease='in')
c.key('right_leg', 12.0, qeuler(x=38), ease='out')
c.key('right_leg', 22, qeuler(x=0), ease='inout')
c.key('left_leg', 0)
c.key('left_leg', 6.0, qeuler(x=-30), ease='out')
c.key('left_leg', 9.0, qeuler(x=-50), ease='in')
c.key('left_leg', 12.0, qeuler(x=-46), ease='out')
c.key('left_leg', 22, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 6.0, pos=(0, 2.4, 2.8), ease='out')
c.key('root', 9.0, pos=(0, 3.2, -1.6), ease='in')
c.key('root', 10.0, pos=(0, 3.4, -4.2), ease='in')
c.key('root', 12.0, pos=(0, 3.4, -4.0), ease='out')
c.key('root', 22, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/heavy', 22, blend_in=1.5, blend_out=5.0)
fk(f, 0, *REST)
fs(f, 3.0, (0.72, -0.62, -0.38), vv(f=0.70, u=0.62, r=-0.20), ease='out')
# the hold: drawn back to the hip, low and close, the point leaning in toward the crosshair
fs(f, 6.0, (0.70, -0.66, -0.26), vv(f=0.96, u=0.18, r=-0.14), ease='out')
fs(f, 8.0, (0.66, -0.62, -0.36), vv(f=0.95, u=0.20, r=-0.18), ease='in')
fs(f, 10.0, (0.36, -0.40, -1.15), vv(f=0.93, u=0.22, r=-0.30), ease='in3')
fs(f, 12.0, (0.38, -0.42, -1.08), vv(f=0.93, u=0.23, r=-0.29), ease='out')
fk(f, 22, *REST)
fps.append(f)

# ------------------------------------------------------------------ running: a charging thrust, the spear levelled out like a lance
c = tp('spear/running', 16, blend_in=1.0, blend_out=4.0).mark('hit', 5).mark('chain', 11)
R2 = lean_twist(twist=18, lean=14)
R35 = lean_twist(twist=24, lean=12)
R55 = lean_twist(twist=-22, lean=26)
R8 = lean_twist(twist=-26, lean=30)
c.key('torso', 0, lean_twist(twist=0, lean=8))
c.key('torso', 2.0, R2, ease='out')
c.key('torso', 3.5, R35, ease='inout')
c.key('torso', 5.5, R55, ease='in')
c.key('torso', 8.0, R8, ease='linear')
c.key('torso', 16, I, ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 2.0, hold(R2, sp(left=3, up=6), v(r=0.3, u=-0.9)), pos=(0, 0, 1.0), ease='out')
c.key('right_arm', 3.5, hold(R35, sp(left=3, up=6), v(r=0.3, u=-0.9)), pos=(0, 0, 1.4), ease='inout')
c.key('right_arm', 5.5, hold(R55, sp(left=5, up=12)), pos=(0, 0, -1.8), ease='in')
c.key('right_arm', 8.0, hold(R8, sp(left=5, up=13)), pos=(0, 0, -2.2), ease='linear')
c.key('right_arm', 16, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free_arm(v(f=0.8, u=-0.3, r=-0.3)))
c.key('left_arm', 3.5, free(R35, sp(left=-20, up=-8)), ease='inout')
c.key('left_arm', 5.5, free(R55, sp(left=-30, up=-10)), ease='in')
c.key('left_arm', 8.0, free(R8, sp(left=-34, up=-6)), ease='linear')
c.key('left_arm', 16, free_arm(D), ease='inout')
c.key('right_leg', 0, qeuler(x=-28))
c.key('right_leg', 3.0, qeuler(x=-38), ease='out')
c.key('right_leg', 5.5, qeuler(x=30), ease='in')
c.key('right_leg', 8.0, qeuler(x=26), ease='linear')
c.key('right_leg', 16, qeuler(x=0), ease='inout')
c.key('left_leg', 0, qeuler(x=24))
c.key('left_leg', 3.0, qeuler(x=28), ease='out')
c.key('left_leg', 5.5, qeuler(x=-38), ease='in')
c.key('left_leg', 8.0, qeuler(x=-34), ease='linear')
c.key('left_leg', 16, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 2.5, pos=(0, -1.8, 0.4), ease='out')
c.key('root', 3.5, pos=(0, -0.8, 0.8), ease='inout')
c.key('root', 5.5, pos=(0, 1.8, -3.0), ease='in')
c.key('root', 8.0, pos=(0, 1.6, -4.2), ease='linear')
c.key('root', 16, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/running', 16, blend_in=1.0, blend_out=4.0)
fk(f, 0, *REST)
fs(f, 2.5, (0.72, -0.62, -0.40), vv(f=0.95, u=0.14, r=-0.14), ease='inout')
fs(f, 3.5, (0.74, -0.64, -0.36), vv(f=0.96, u=0.10, r=-0.14), ease='inout')
fs(f, 5.5, (0.40, -0.45, -1.06), vv(f=0.92, u=0.22, r=-0.32), ease='in')
fs(f, 8.0, (0.42, -0.46, -1.16), vv(f=0.92, u=0.22, r=-0.32), ease='linear')
fk(f, 16, *REST)
fps.append(f)

# ------------------------------------------------------------------ jump: a plunging thrust from the air, the point driven down and forward
c = tp('spear/jump', 16, blend_in=1.0, blend_out=4.0).mark('hit', 6).mark('chain', 12)
J3 = lean_twist(twist=10, lean=-12)
J6 = lean_twist(twist=-8, lean=30)
J9 = lean_twist(twist=-6, lean=26)
J105 = lean_twist(twist=-5, lean=19)
J12 = lean_twist(twist=-4, lean=12)
c.key('torso', 0)
c.key('torso', 3.0, J3, ease='out')
c.key('torso', 6.0, J6, ease='in3')
c.key('torso', 9.0, J9, ease='out')
c.key('torso', 10.5, J105, ease='inout')
c.key('torso', 12.0, J12, ease='inout')
c.key('torso', 16, I, ease='inout')
# cocked over the shoulder, the hand beside the head and the point up and forward, then driven down in front
RAISED = v(f=-0.5, u=0.85, r=0.2)
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 3.0, hold(J3, sp(left=6, up=42), RAISED), pos=(0, 0, 0.8), ease='out')
c.key('right_arm', 6.0, hold(J6, sp(left=4, up=-56), v(f=0.8, u=0.5)), pos=(0, 0, -1.2), ease='in3')
c.key('right_arm', 9.0, hold(J9, sp(left=4, up=-50), v(f=0.8, u=0.5)), pos=(0, 0, -1.0), ease='out')
# the hand drops to the hip before the point comes up, so the shaft never crosses the face
c.key('right_arm', 10.5, hold(J105, sp(left=6, up=-36), v(f=0.9, u=-0.3)), pos=(0, 0, -0.8), ease='inout')
c.key('right_arm', 12.0, hold(J12, sp(left=8, up=-20)), pos=(0, 0, -0.6), ease='inout')
c.key('right_arm', 16, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 3.0, free(J3, sp(left=-20, up=40)), ease='out')
c.key('left_arm', 6.0, free(J6, sp(left=-14, up=-30)), ease='in3')
c.key('left_arm', 16, free_arm(D), ease='inout')
for leg, x0, x6, x9 in (('right_leg', -38, -16, 14), ('left_leg', -22, 10, -14)):
    c.key(leg, 0)
    c.key(leg, 3.0, qeuler(x=x0), ease='out')
    c.key(leg, 6.0, qeuler(x=x6), ease='in')
    c.key(leg, 9.0, qeuler(x=x9), ease='out')
    c.key(leg, 16, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 3.0, pos=(0, -1.2, 0.8), ease='out')
c.key('root', 6.0, pos=(0, 0.6, -2.0), ease='in3')
c.key('root', 9.0, pos=(0, 0.8, -1.4), ease='out')
c.key('root', 10.5, pos=(0, 0.6, -1.1), ease='inout')
c.key('root', 12.0, pos=(0, 0.4, -0.8), ease='inout')
c.key('root', 16, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/jump', 16, blend_in=1.0, blend_out=4.0)
fk(f, 0, *REST)
fs(f, 3.0, (0.52, -0.14, -0.58), vv(f=0.62, u=0.74, r=-0.20), vv(r=-0.97, f=-0.26), ease='out')
# the point comes down toward the ground ahead, the butt up by the camera
fs(f, 6.0, (0.55, 0.02, -0.66), vv(f=0.70, u=-0.55, r=-0.45), vv(f=-1.0), ease='in3')
fs(f, 9.0, (0.56, 0.00, -0.66), vv(f=0.72, u=-0.52, r=-0.44), vv(f=-1.0), ease='out')
fs(f, 12.0, (0.62, -0.46, -0.62), vv(f=0.72, u=0.20, r=-0.60), vv(r=0.5, f=-0.87), ease='inout')
fs(f, 14.0, (0.70, -0.54, -0.66), vv(f=0.30, u=0.82, r=-0.50), vv(r=-0.87, f=0.5), ease='inout')
fk(f, 16, *REST)
fps.append(f)

# ------------------------------------------------------------------ guard counter: a short hard pull-back off the block, then a quick thrust at the chest
c = tp('spear/guard_counter', 18, blend_in=1.0, blend_out=5.0).mark('hit', 7).mark('chain', 13)
G0 = lean_twist(twist=-12, lean=4)
G45 = lean_twist(twist=46, lean=-6)
G5 = lean_twist(twist=50, lean=-7)
G75 = lean_twist(twist=-30, lean=18)
G9 = lean_twist(twist=-26, lean=15)
FLARE = v(r=0.5, u=-0.85)
c.key('torso', 0, G0)
c.key('torso', 4.5, G45, ease='out3')
c.key('torso', 5.0, G5, ease='linear')
c.key('torso', 7.5, G75, ease='in3')
c.key('torso', 9.0, G9, ease='out')
c.key('torso', 18, I, ease='inout')
c.key('right_arm', 0, hold(G0, sp(left=12, up=10)), pos=(0, 0, -0.5))
c.key('right_arm', 4.5, hold(G45, sp(left=4, up=4), FLARE), pos=(0, 0, 1.5), ease='out3')
c.key('right_arm', 5.0, hold(G5, sp(left=4, up=4), FLARE), pos=(0, 0, 1.8), ease='linear')
c.key('right_arm', 7.5, hold(G75, sp(left=8, up=14)), pos=(0, 0, -2.2), ease='in3')
c.key('right_arm', 9.0, hold(G9, sp(left=8, up=15)), pos=(0, 0, -1.8), ease='out')
c.key('right_arm', 18, rest_arm(), pos=(0, 0, 0), ease='inout')
# the off hand is still up from the block, and drops away as the thrust lands
c.key('left_arm', 0, free_arm(v(f=1.0, u=0.0, r=-0.1)))
c.key('left_arm', 5.0, free(G5, sp(left=-30, up=2)), ease='out')
c.key('left_arm', 7.5, free(G75, sp(left=-30, up=-40)), ease='in')
c.key('left_arm', 18, free_arm(D), ease='inout')
c.key('right_leg', 0)
c.key('right_leg', 4.5, qeuler(x=10), ease='out')
c.key('right_leg', 7.5, qeuler(x=30), ease='in')
c.key('right_leg', 18, qeuler(x=0), ease='inout')
c.key('left_leg', 0)
c.key('left_leg', 4.5, qeuler(x=-14), ease='out')
c.key('left_leg', 7.5, qeuler(x=-38), ease='in')
c.key('left_leg', 18, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 4.5, pos=(0, 1.2, 1.8), ease='out3')
c.key('root', 5.0, pos=(0, 1.2, 2.0), ease='linear')
c.key('root', 7.5, pos=(0, 1.8, -3.4), ease='in3')
c.key('root', 9.0, pos=(0, 1.6, -3.0), ease='out')
c.key('root', 18, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/guard_counter', 18, blend_in=1.0, blend_out=5.0)
fk(f, 0, *REST)
# a short snap back at the shoulder, the point still up, then straight at the crosshair
fs(f, 4.5, (0.70, -0.52, -0.34), vv(f=0.93, u=0.30, r=-0.20), ease='out3')
fs(f, 5.0, (0.72, -0.53, -0.32), vv(f=0.93, u=0.30, r=-0.20), ease='linear')
fs(f, 7.5, (0.38, -0.40, -1.10), vv(f=0.94, u=0.19, r=-0.27), ease='in3')
fs(f, 9.0, (0.40, -0.42, -1.04), vv(f=0.94, u=0.20, r=-0.26), ease='out')
fk(f, 18, *REST)
fps.append(f)

# ------------------------------------------------------------------ skill: Piercing Charge, the spear braced level and carried through everything in the way
c = tp('spear/skill', 26, blend_in=1.5, blend_out=5.0).mark('brace', 3).mark('hit', 6).mark('charge ends', 15)
K3 = lean_twist(twist=14, lean=14)
KC = lean_twist(twist=-6, lean=28)
KU = lean_twist(twist=0, lean=8)
c.key('torso', 0)
c.key('torso', 3.0, K3, ease='out')
c.key('torso', 6.0, KC, ease='inout')
c.key('torso', 15, KC, ease='linear')
c.key('torso', 20, KU, ease='inout')
c.key('torso', 26, I, ease='inout')
# braced at the hip, level and dead ahead
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 3.0, hold(K3, sp(left=0, up=4), v(r=0.4, u=-0.9)), pos=(0, 0, 1.0), ease='out')
c.key('right_arm', 6.0, hold(KC, sp(left=0, up=4)), pos=(0, 0, -0.8), ease='inout')
c.key('right_arm', 15, hold(KC, sp(left=0, up=4)), pos=(0, 0, -0.8), ease='linear')
c.key('right_arm', 20, hold(KU, sp(left=0, up=22)), pos=(0, 0, -0.4), ease='inout')
c.key('right_arm', 26, rest_arm(), pos=(0, 0, 0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 3.0, free(K3, sp(left=-24, up=-6)), ease='out')
c.key('left_arm', 6.0, free(KC, sp(left=-22, up=-8)), ease='inout')
c.key('left_arm', 15, free(KC, sp(left=-22, up=-8)), ease='linear')
c.key('left_arm', 26, free_arm(D), ease='inout')
# the legs run: a stride every three ticks
c.key('right_leg', 0)
c.key('right_leg', 3.0, qeuler(x=14), ease='out')
for t, a in ((6.0, -34), (9.0, 32), (12.0, -34), (15.0, 28)):
    c.key('right_leg', t, qeuler(x=a), ease='inout')
c.key('right_leg', 20, qeuler(x=8), ease='inout')
c.key('right_leg', 26, qeuler(x=0), ease='inout')
c.key('left_leg', 0)
c.key('left_leg', 3.0, qeuler(x=-20), ease='out')
for t, a in ((6.0, 34), (9.0, -34), (12.0, 34), (15.0, -28)):
    c.key('left_leg', t, qeuler(x=a), ease='inout')
c.key('left_leg', 20, qeuler(x=-8), ease='inout')
c.key('left_leg', 26, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 3.0, pos=(0, 0.2, 0.6), ease='out')
for t, y in ((6.0, 1.0), (7.5, 0.0), (9.0, 1.0), (10.5, 0.0), (12.0, 1.0), (13.5, 0.0), (15.0, 0.7)):
    c.key('root', t, pos=(0, y, -2.0), ease='inout')
c.key('root', 20, pos=(0, 0.1, -0.6), ease='inout')
c.key('root', 26, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = Clip('spear/skill', 26, blend_in=1.5, blend_out=5.0)
fk(f, 0, *REST)
fs(f, 3.0, (0.56, -0.60, -0.50), vv(f=0.90, u=0.26, r=-0.30), ease='out')
fs(f, 6.0, (0.50, -0.55, -0.80), vv(f=0.88, u=0.34, r=-0.36), ease='inout')
fs(f, 15.0, (0.50, -0.55, -0.80), vv(f=0.88, u=0.34, r=-0.36), ease='linear')
fs(f, 20.0, (0.56, -0.40, -0.66), vv(f=0.70, u=0.68, r=-0.22), ease='inout')
fk(f, 26, *REST)
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
        if c.name not in only:
            continue
        sheet(scene, c, by_name.get(c.name)).save(os.path.join(previews, c.name.split('/')[-1] + '.png'))
