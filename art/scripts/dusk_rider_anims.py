"""
The Dusk Rider's clips. Its parts nest (art/scripts/dusk_rider.py), so each clip keys the parts by name and the
game's rig animator (client/combat/RigAnimator.kt) turns each part from its rest pose ('set') or from where the
gaits left it ('add'), and moves it by an offset in pixels. Run:

    python3 art/scripts/dusk_rider_anims.py src/main/resources/assets/guildmark <preview-dir> [names]

Gaits: 'idle', 'walk' and 'gallop' loop. The game plays walk and gallop by how far the steed has gone (one stride
of WALK_STRIDE or GALLOP_STRIDE blocks per loop) and blends them by its speed (DuskRiderModel.kt), and idle by
time. Attacks match DuskRider.kt: each clip lasts as many ticks as its attack and its blows land on the ticks the
attack resolves them. Attacks that ride (ride_by, charge) leave the legs to the gallop.

The previews pose the real model with the same rules, the lantern hanging as the game hangs it.
"""
import math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from PIL import Image, ImageDraw
from animkit import Clip, qeuler, qmul, qaxis, qslerp, qmat, frame_to, unit, I, v, F, B, U, D, R, L
import dusk_rider as dr

GROUP = 'dusk_rider'
IDX = {p.name: p for p in dr.model.all_parts()}
PARENT = {}
for p in dr.model.all_parts():
    for ch in p.children:
        PARENT[ch.name] = p.name

# Blocks the steed covers in one loop of each gait (DuskRiderModel.WALK_STRIDE and GALLOP_STRIDE)
WALK_STRIDE = 2.4
GALLOP_STRIDE = 4.2


def qinv(q):
    x, y, z, w = q
    return np.array([-x, -y, -z, w])


def rest(bone):
    return qeuler(*IDX[bone].rot)


def rel(bone, q_abs):
    """A 'set' key that gives [bone] the rotation [q_abs] (in its parent's frame)."""
    return qmul(qinv(rest(bone)), q_abs)


def lantern_arm(arm_dir):
    return rel('rider_left_arm', frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), arm_dir, F))


def about(q, point):
    """The offset that turns a part by [q] about [point] (relative to its pivot) rather than about its pivot."""
    pnt = np.array(point, float)
    return pnt - qmat(q) @ pnt


HIND_HIPS = (0, 3, 8)       # the steed's hind hip joints, relative to its pivot (it rears about them)
FORE_HIPS = (0, 3, -8)      # and its shoulders (it bucks about them)


def steed_turn(c, t, q, point=None, ease='inout', lift=0.0):
    """Keys the steed (an 'add' track) turned by [q] about [point], raised by [lift] pixels."""
    pos = (about(q, point) if point is not None else np.zeros(3)) + np.array([0.0, -lift, 0.0])
    c.key('steed', t, q, pos=pos, ease=ease)


def smooth(a):
    a = min(1.0, max(0.0, a))
    return a * a * (3 - 2 * a)


# ================================================================== gaits

def stride(phase, land, stance, reach, push, fold):
    """A leg's swing in a gait: on the ground it sweeps from [reach] (forward, negative) back to [push]; in the air
    it folds at the lower joint by up to [fold] and swings forward again. Degrees, as (thigh, shin)."""
    t = (phase - land) % 1.0
    if t < stance:
        a = t / stance
        return reach + (push - reach) * a, 0.0
    a = (t - stance) / (1.0 - stance)
    return push + (reach - push) * smooth(a), fold * math.sin(math.pi * a)


def gait(name, length, legs, body):
    """A looping gait: [legs] maps each leg to (land, stance, reach, push, fold); [body] gives the rest of the
    pose at a phase as {bone: (q, pos)}."""
    c = Clip(name, length, blend_in=0, blend_out=0)
    n = int(length * 2)
    for i in range(n + 1):
        t = i / 2
        phase = (t / length) % 1.0
        for leg, (land, stance, reach, push, fold) in legs.items():
            thigh, shin = stride(phase, land, stance, reach, push, fold)
            c.key(leg + '_leg', t, qeuler(x=thigh), ease='linear')
            c.key(leg + '_shin', t, qeuler(x=shin), ease='linear')
        for bone, (q, pos) in body(phase).items():
            c.key(bone, t, q, pos=pos, ease='linear')
    return c


def gallop_body(phase):
    s = math.sin(2 * math.pi * phase)
    k = math.cos(2 * math.pi * phase)
    pitch = 5.0 * math.sin(2 * math.pi * (phase - 0.15))
    return {
        'steed': (qeuler(x=pitch), np.array([0.0, -0.9 * k - 0.4, 0.0])),
        'neck': (qeuler(x=-pitch * 1.4 - 6), None),
        'horse_head': (qeuler(x=pitch * 0.6 - 4), None),
        'tail': (qeuler(x=-26 + 4 * s), None),
        'tail_end': (qeuler(x=18 + 6 * k), None),
        'rider_body': (qeuler(x=14 - pitch * 0.7), np.array([0.0, 0.5 * k, 0.0])),
        'rider_head': (qeuler(x=-10 + pitch * 0.5), None),
        'cloak': (qeuler(x=9 + 3 * s), None),
        'plume': (qeuler(x=18 + 4 * k), None),
        'mane': (qeuler(x=8 + 3 * s), None),
        'rider_right_arm': (qeuler(x=-6 * k), None),
    }


def walk_body(phase):
    s2 = math.sin(4 * math.pi * phase)
    s = math.sin(2 * math.pi * phase)
    return {
        'steed': (qeuler(x=0.8 * s2, z=1.4 * s), np.array([0.0, -0.3 * abs(s2), 0.0])),
        'neck': (qeuler(x=-3 * s2), None),
        'horse_head': (qeuler(x=2 * s2, y=2 * s), None),
        'tail': (qeuler(y=6 * s), None),
        'rider_body': (qeuler(x=2, z=-1.2 * s), np.array([0.0, 0.25 * abs(s2), 0.0])),
        'cloak': (qeuler(x=4 + 2 * s2), None),
    }


# Transverse gallop: hind left, hind right, then fore left, fore right, then the leap with all four gathered
gallop = gait('gallop', 12, {
    'hl': (0.00, 0.32, -30, 34, -60), 'hr': (0.10, 0.32, -30, 34, -60),
    'fl': (0.30, 0.30, -40, 30, 85), 'fr': (0.40, 0.30, -40, 30, 85),
}, gallop_body)
# Four-beat walk: hind left, fore left, hind right, fore right
walk = gait('walk', 24, {
    'hl': (0.00, 0.62, -16, 15, -32), 'fl': (0.25, 0.62, -17, 15, 40),
    'hr': (0.50, 0.62, -16, 15, -32), 'fr': (0.75, 0.62, -17, 15, 40),
}, walk_body)

# Standing: slow breaths, a swish of the tail, a toss of the head, a hoof pawing the ground; the rider waits
idle = Clip('idle', 80, blend_in=0, blend_out=0)
for i in range(161):
    t = i / 2
    a = 2 * math.pi * t / 80
    breath = math.sin(2 * a)
    idle.key('steed', t, qeuler(x=0.4 * breath), pos=np.array([0.0, 0.25 * breath, 0.0]), ease='linear')
    idle.key('tail', t, qeuler(y=12 * math.sin(a), x=-3 + 3 * math.sin(3 * a)), ease='linear')
    idle.key('rider_body', t, qeuler(x=1.0 * breath), pos=np.array([0.0, 0.15 * breath, 0.0]), ease='linear')
    idle.key('cloak', t, qeuler(x=2 * math.sin(a + 1)), ease='linear')
for t, x, z in ((0, 0, 0), (40, 0, 0), (44, -8, 9), (48, -6, -7), (52, -3, 4), (56, 0, 0), (80, 0, 0)):
    idle.key('horse_head', t, qeuler(x=x, z=z), ease='inout')
for t, x in ((0, 0), (40, 0), (44, -5), (52, -3), (58, 0), (80, 0)):
    idle.key('neck', t, qeuler(x=x), ease='inout')
for t, leg, shin in ((0, 0, 0), (60, 0, 0), (63, -24, 70), (66, 4, 10), (69, -20, 64), (72, 3, 6), (75, 0, 0), (80, 0, 0)):
    idle.key('fr_leg', t, qeuler(x=leg), ease='inout')
    idle.key('fr_shin', t, qeuler(x=shin), ease='inout')
for t, y in ((0, 0), (20, 12), (34, 12), (40, 0), (62, -10), (74, -10), (80, 0)):
    idle.key('rider_head', t, qeuler(y=y), ease='inout')

# ================================================================== attacks

clips = []

Y_AXIS = np.array([0.0, 1.0, 0.0])
NEG_Z = np.array([0.0, 0.0, -1.0])


def frame(a_from, b_from, a_to, b_to, spare):
    """frame_to, with [spare] standing in for [b_to] when it lies along [a_to]."""
    if abs(np.dot(unit(a_to), unit(b_to))) > 0.97:
        b_to = spare
    return frame_to(a_from, b_from, a_to, b_to)


def wield(arm_dir, haft_dir, edge=None):
    """(arm key, halberd key): the right arm along [arm_dir], its fist turned toward the haft, and the haft toward
    [haft_dir] with the axe's edge facing [edge] (or the way the arm points), all in the rider's frame."""
    arm = frame(Y_AXIS, NEG_Z, arm_dir, haft_dir, F)
    pole = frame(NEG_Z, Y_AXIS, haft_dir, edge if edge is not None else arm_dir, F)
    return rel('rider_right_arm', arm), rel('halberd', qmul(qinv(arm), pole))


def lead(haft):
    """Which way the blade goes, swung flat from the left round to the right, while the haft points along [haft]."""
    x, _, z = haft
    return unit(np.array([z, 0.0, -x]))


def arm_key(c, t, arm_dir, haft_dir, edge=None, ease='inout'):
    qa, qh = wield(arm_dir, haft_dir, edge)
    c.key('rider_right_arm', t, qa, ease=ease)
    c.key('halberd', t, qh, ease=ease)


def arm_rest(c, t, ease='inout'):
    c.key('rider_right_arm', t, I, ease=ease)
    c.key('halberd', t, I, ease=ease)


# The halberd's poses: (arm, haft, edge) in the rider's frame
SCYTHE = (v(u=-0.8, r=0.5, f=0.25), v(f=0.5, r=0.8, u=-0.1), v(f=1.0))               # out low to the right, edge first
COUCHED = (v(u=-1.0, f=0.35, r=0.25), v(f=1.0, u=0.45, r=-0.12), v(u=-1.0))         # levelled like a lance (as it leans)
RAISED = (v(u=0.95, f=0.25, r=0.2), v(u=1.0, f=0.35, r=0.1), v(f=1.0))              # held up high like a banner
OVERHEAD = (v(u=1.0, f=0.1, r=0.3), v(f=-0.85, u=0.5, r=0.1), v(f=0.5, u=0.85))     # raised, the blade behind the helm
CHOPPED = (v(u=-0.3, f=0.9, r=0.3), v(f=0.95, u=-0.05, r=0.2), v(f=-0.1, u=-1.0))    # brought down in front, right
ASIDE = (v(u=-0.9, r=0.4, f=0.2), v(f=0.3, u=0.95), v(f=1.0))                        # stood upright out of the way


def lamp_key(c, t, arm_dir=None, ease='inout', swing=None):
    """The lantern arm along [arm_dir] (or at rest), the lantern swung by [swing] in the world (or hanging)."""
    c.key('rider_left_arm', t, lantern_arm(arm_dir) if arm_dir is not None else I, ease=ease)
    c.key('lantern', t, swing if swing is not None else I, ease=ease)


def lean(c, t, x=0.0, y=0.0, z=0.0, ease='inout', pos=None):
    """The rider's body: forward (x), turned to its right (y), to its left side (z)."""
    c.key('rider_body', t, qmul(qeuler(y=y), qeuler(x=x, z=z)), pos=pos, ease=ease)


def adds(c, *bones):
    for b in bones:
        c.mode(b, 'add')
    return c


LEGS = ['fr_leg', 'fl_leg', 'hr_leg', 'hl_leg', 'fr_shin', 'fl_shin', 'hr_shin', 'hl_shin']


def legs_key(c, t, ease='inout', **angles):
    """Leg keys (added to the gait) in degrees: fr=(thigh, shin), and so on; legs left out are at rest."""
    for leg in ('fr', 'fl', 'hr', 'hl'):
        thigh, shin = angles.get(leg, (0, 0))
        c.key(leg + '_leg', t, qeuler(x=thigh), ease=ease)
        c.key(leg + '_shin', t, qeuler(x=shin), ease=ease)


def rear_key(c, t, pitch, ease='inout', flail=0):
    """The steed reared up by [pitch] degrees about its hind legs, which stay planted, its forelegs [flail]ing."""
    q = qeuler(x=-pitch)
    steed_turn(c, t, q, HIND_HIPS, ease=ease)
    k = pitch / 50.0
    legs_key(c, t, ease=ease,
             fr=(-60 * k + 25 * flail, 95 * k - 30 * flail), fl=(-30 * k - 25 * flail, 70 * k + 30 * flail),
             hr=(pitch * 0.75, -25 * k), hl=(pitch * 0.7, -20 * k))
    c.key('neck', t, qeuler(x=-22 * k), ease=ease)
    c.key('horse_head', t, qeuler(x=-18 * k), ease=ease)
    c.key('tail', t, qeuler(x=28 * k), ease=ease)


def level_key(c, t, ease='inout'):
    steed_turn(c, t, I, ease=ease)
    legs_key(c, t, ease=ease)
    for b in ('neck', 'horse_head', 'tail'):
        c.key(b, t, I, ease=ease)


def standing():
    """Under an attack done on the spot: the steed stands."""
    return lambda t: [(idle, 0.0, 1.0)]


def galloping(start, stop, ramp=3.0):
    """Under an attack that rides: the gallop from [start] to [stop] ticks, a walk either side."""
    def base(t):
        w = smooth((t - start + ramp) / ramp) * (1 - smooth((t - stop) / ramp))
        return [(idle, 0.0, 1.0), (walk, (t * 1.0) % walk.length, 1 - w), (gallop, t % gallop.length, w)]
    return base


# ------------------------------------------------------------------ ride_by: the halberd swung up and back, then
# down to be held out low to the right like a scythe while the steed thunders past. 40 ticks, the rush from 8 to 26,
# its blow live from 12 to 26.
c = Clip('ride_by', 40, blend_in=2.0, blend_out=6.0).mark('rush', 8).mark('hit', 12).mark('end', 26)
lean(c, 0)
lean(c, 6, x=8, y=-16, ease='out')
lean(c, 11, x=22, y=12, ease='in')
lean(c, 20, x=22, y=16)
lean(c, 26, x=16, y=8)
lean(c, 40)
arm_rest(c, 0)
arm_key(c, 6, v(u=0.45, r=0.85, f=-0.25), v(u=0.7, f=-0.65, r=0.25), v(f=0.6, u=0.7), ease='out')
arm_key(c, 9, v(u=-0.1, r=0.95, f=0.3), v(f=0.55, u=0.35, r=0.75), v(f=0.4, u=-0.9), ease='in')
arm_key(c, 11.5, *SCYTHE, ease='out')
arm_key(c, 20, v(u=-0.8, r=0.55, f=0.15), v(f=0.4, r=0.85, u=-0.12), v(f=1.0))
arm_key(c, 26, *SCYTHE)
arm_key(c, 31, *ASIDE)
arm_rest(c, 40)
lamp_key(c, 0)
lamp_key(c, 8, v(u=-0.6, f=0.2, r=-0.5), ease='out', swing=qeuler(x=30))
lamp_key(c, 26, v(u=-0.6, f=0.2, r=-0.5), swing=qeuler(x=24))
lamp_key(c, 40)
c.key('rider_head', 0, I)
c.key('rider_head', 10, qeuler(y=10, x=10))
c.key('rider_head', 22, qeuler(y=40, x=8))
c.key('rider_head', 40, I)
c.key('cloak', 0, I)
c.key('cloak', 11, qeuler(x=-8))
c.key('cloak', 24, qeuler(x=-8))
c.key('cloak', 36, I)
adds(c, 'rider_head', 'cloak')
c.base = galloping(8, 26)
clips.append(c)

# ------------------------------------------------------------------ charge: it rears and screams, then drops its
# halberd level like a lance and charges straight through. 56 ticks: rearing to 16, the charge from 18 to 40.
c = Clip('charge', 56, blend_in=2.0, blend_out=6.0).mark('rears', 8).mark('charge', 18).mark('hit', 20).mark('end', 40)
level_key(c, 0)
rear_key(c, 8, 42, ease='out', flail=1)
rear_key(c, 12, 46, flail=-1)
level_key(c, 17, ease='in')
steed_turn(c, 20, qeuler(x=4), ease='out')
steed_turn(c, 40, qeuler(x=4))
steed_turn(c, 46, qeuler(x=-6), ease='out')
level_key(c, 56)
adds(c, 'steed', 'neck', 'horse_head', 'tail', *LEGS)
lean(c, 0)
lean(c, 8, x=34, ease='out')
lean(c, 12, x=38)
lean(c, 18, x=22, ease='in')
lean(c, 22, x=26)
lean(c, 40, x=24)
lean(c, 46, x=-4, ease='out')
lean(c, 56)
arm_rest(c, 0)
arm_key(c, 8, *RAISED, ease='out')
arm_key(c, 13, *RAISED)
arm_key(c, 18, *COUCHED, ease='in')
arm_key(c, 40, *COUCHED)
arm_rest(c, 56)
lamp_key(c, 0)
lamp_key(c, 10, v(u=-0.3, f=0.5, r=-0.6), swing=qeuler(x=-20))
lamp_key(c, 20, v(u=-0.8, f=0.1, r=-0.4), swing=qeuler(x=35))
lamp_key(c, 40, v(u=-0.8, f=0.1, r=-0.4), swing=qeuler(x=32))
lamp_key(c, 56)
c.key('cloak', 0, I)
c.key('cloak', 8, qeuler(x=-14))
c.key('cloak', 14, qeuler(x=-16))
c.key('cloak', 20, qeuler(x=-10))
c.key('cloak', 40, qeuler(x=-10))
c.key('cloak', 50, I)
adds(c, 'cloak')
c.base = galloping(18, 40)
clips.append(c)

# ------------------------------------------------------------------ cleave: the halberd raised high, then chopped
# down at the front right. 34 ticks, the blow on 16.
c = Clip('cleave', 34, blend_in=2.0, blend_out=6.0).mark('hit', 16)
lean(c, 0)
lean(c, 11, x=-12, y=-22, ease='out')
lean(c, 13, x=-14, y=-24)
lean(c, 16, x=24, y=16, ease='in')
lean(c, 21, x=22, y=14)
lean(c, 34)
arm_rest(c, 0)
arm_key(c, 11, *OVERHEAD, ease='out')
arm_key(c, 13, v(u=1.0, f=-0.05, r=0.3), v(f=-0.95, u=0.3, r=0.1), v(f=0.3, u=0.95))
arm_key(c, 15, v(u=0.6, f=0.8, r=0.25), v(f=0.6, u=0.8, r=0.15), v(f=0.8, u=-0.6), ease='in')
arm_key(c, 16.5, *CHOPPED, ease='linear')
arm_key(c, 22, *CHOPPED)
arm_rest(c, 34)
lamp_key(c, 0)
lamp_key(c, 12, v(u=-0.7, f=0.4, r=-0.4), swing=qeuler(x=-15, z=10))
lamp_key(c, 17, v(u=-0.8, f=0.2, r=-0.5), swing=qeuler(x=25))
lamp_key(c, 34)
steed_turn(c, 0, I)
steed_turn(c, 14, qeuler(y=-6, x=-3), ease='out')
steed_turn(c, 17, qeuler(y=8, x=4), ease='in')
steed_turn(c, 34, I)
adds(c, 'steed')
c.base = standing()
clips.append(c)

# ------------------------------------------------------------------ sweep: wound back to its left, then the halberd
# swept round in a wide flat circle. 38 ticks, the blow on 19.
c = Clip('sweep', 38, blend_in=2.0, blend_out=6.0).mark('hit', 19)
lean(c, 0)
lean(c, 13, x=8, y=-55, ease='out')
lean(c, 15, x=8, y=-58)
lean(c, 18, x=6, y=-5, ease='in')
lean(c, 21, x=6, y=50, ease='out')
lean(c, 27, x=4, y=55)
lean(c, 38)
arm_rest(c, 0)
for t, arm, haft, ease in ((13, v(u=-0.6, r=-0.55, f=0.45), v(f=0.42, r=-0.91, u=0.1), 'out'),
                           (15, v(u=-0.6, r=-0.6, f=0.4), v(f=0.3, r=-0.95, u=0.12), 'inout'),
                           (18.5, v(u=-0.65, f=0.75, r=0.1), v(f=1.0, r=-0.05, u=-0.1), 'in'),
                           (20.5, v(u=-0.65, f=0.5, r=0.5), v(f=0.85, r=0.5, u=-0.12), 'linear'),
                           (23, v(u=-0.7, f=0.2, r=0.7), v(f=0.45, r=0.88, u=-0.1), 'out'),
                           (27, v(u=-0.75, f=0.15, r=0.65), v(f=0.35, r=0.92, u=-0.15), 'inout')):
    arm_key(c, t, arm, haft, lead(haft), ease=ease)
arm_rest(c, 38)
lamp_key(c, 0)
lamp_key(c, 14, v(u=-0.8, f=-0.3, r=-0.4), swing=qeuler(z=-20))
lamp_key(c, 21, v(u=-0.9, f=0.1, r=-0.4), swing=qeuler(z=30))
lamp_key(c, 38)
steed_turn(c, 0, I)
steed_turn(c, 14, qeuler(y=-10), ease='out')
steed_turn(c, 22, qeuler(y=12), ease='out')
steed_turn(c, 38, I)
adds(c, 'steed')
c.base = standing()
clips.append(c)

# ------------------------------------------------------------------ rear: the steed rears high and stamps down
# before it. 40 ticks, the hooves land on 20.
c = Clip('rear', 40, blend_in=2.0, blend_out=6.0).mark('hit', 20)
level_key(c, 0)
rear_key(c, 9, 50, ease='out', flail=1)
rear_key(c, 13, 56, flail=-1)
rear_key(c, 16, 54, flail=1)
steed_turn(c, 20, qeuler(x=6), FORE_HIPS, ease='in')
legs_key(c, 20, ease='in', fr=(-12, 0), fl=(-10, 0), hr=(8, -10), hl=(6, -8))
c.key('neck', 20, qeuler(x=12), ease='in')
c.key('horse_head', 20, qeuler(x=6), ease='in')
c.key('tail', 20, qeuler(x=-10), ease='in')
steed_turn(c, 24, qeuler(x=2), ease='out')
level_key(c, 40)
adds(c, 'steed', 'neck', 'horse_head', 'tail', *LEGS)
lean(c, 0)
lean(c, 10, x=36, ease='out')
lean(c, 16, x=42)
lean(c, 20, x=-6, ease='in')
lean(c, 25, x=4)
lean(c, 40)
arm_rest(c, 0)
arm_key(c, 10, *RAISED, ease='out')
arm_key(c, 17, *RAISED)
arm_key(c, 21, *CHOPPED, ease='in')
arm_key(c, 28, *CHOPPED)
arm_rest(c, 40)
lamp_key(c, 0)
lamp_key(c, 12, v(u=0.4, f=0.8, r=-0.4), swing=qeuler(x=-30))
lamp_key(c, 21, v(u=-0.8, f=0.3, r=-0.4), swing=qeuler(x=30))
lamp_key(c, 40)
c.base = standing()
clips.append(c)

# ------------------------------------------------------------------ kick: for anyone behind, the steed drops its
# head and lashes out with both hind hooves. 30 ticks, the kick lands on 12.
c = Clip('kick', 30, blend_in=1.5, blend_out=6.0).mark('hit', 12)
level_key(c, 0)
steed_turn(c, 8, qeuler(x=6), FORE_HIPS, ease='out')
legs_key(c, 8, ease='out', fr=(-6, 0), fl=(-6, 0), hr=(-24, -40), hl=(-22, -36))
steed_turn(c, 12, qeuler(x=26), FORE_HIPS, ease='in')
legs_key(c, 12, ease='in', fr=(-26, 0), fl=(-26, 0), hr=(80, 10), hl=(76, 6))
c.key('neck', 8, qeuler(x=6), ease='out')
c.key('neck', 12, qeuler(x=24), ease='in')
c.key('horse_head', 12, qeuler(x=12), ease='in')
c.key('tail', 12, qeuler(x=-40), ease='in')
steed_turn(c, 16, qeuler(x=20), FORE_HIPS)
legs_key(c, 16, fr=(-20, 0), fl=(-20, 0), hr=(64, 4), hl=(60, 4))
level_key(c, 30)
adds(c, 'steed', 'neck', 'horse_head', 'tail', *LEGS)
lean(c, 0)
lean(c, 10, x=-8, ease='out')
lean(c, 13, x=-26, ease='in')
lean(c, 17, x=-18)
lean(c, 30)
arm_rest(c, 0)
arm_key(c, 12, v(u=-0.6, r=0.6, f=0.3), v(f=0.5, u=0.85), v(f=1.0), ease='in')
arm_rest(c, 30)
lamp_key(c, 0)
lamp_key(c, 12, v(u=-0.5, f=0.5, r=-0.6), swing=qeuler(x=-35))
lamp_key(c, 30)
c.base = standing()
clips.append(c)

# ------------------------------------------------------------------ lantern: it raises the lantern and casts its
# cold light. 44 ticks; a wisp leaves the lantern on 20 (and in its second phase on 26 and 32 too).
c = Clip('lantern', 44, blend_in=2.0, blend_out=6.0).mark('cast', 20).mark('cast2', 26).mark('cast3', 32)
lean(c, 0)
lean(c, 14, x=-10, y=-18, ease='out')
lean(c, 18, x=-12, y=-20)
lean(c, 20, x=12, y=-6, ease='in')
lean(c, 24, x=8, y=-8)
lean(c, 26, x=12, y=-6, ease='in')
lean(c, 30, x=8, y=-8)
lean(c, 32, x=12, y=-6, ease='in')
lean(c, 44)
arm_rest(c, 0)
arm_key(c, 14, *ASIDE, ease='out')
arm_key(c, 38, *ASIDE)
arm_rest(c, 44)
lamp_key(c, 0)
lamp_key(c, 14, v(u=1.0, f=0.3, r=-0.35), ease='out', swing=qeuler(x=-12))
lamp_key(c, 18, v(u=1.0, f=0.15, r=-0.3), swing=qeuler(x=-20))
lamp_key(c, 20, v(f=1.0, u=0.25, r=-0.1), ease='in', swing=qeuler(x=-70))
lamp_key(c, 23, v(f=1.0, u=0.35, r=-0.1), swing=qeuler(x=-40))
lamp_key(c, 26, v(f=1.0, u=0.2, r=-0.1), ease='in', swing=qeuler(x=-75))
lamp_key(c, 29, v(f=1.0, u=0.35, r=-0.1), swing=qeuler(x=-40))
lamp_key(c, 32, v(f=1.0, u=0.2, r=-0.1), ease='in', swing=qeuler(x=-75))
lamp_key(c, 36, v(f=0.9, u=0.3, r=-0.2), swing=qeuler(x=-30))
lamp_key(c, 44)
c.base = standing()
clips.append(c)

# ------------------------------------------------------------------ ignite: wounded past half, it rears and lifts
# the lantern, and cold fire takes its steed; it crashes down in a burst of it. 60 ticks, the burst on 34.
c = Clip('ignite', 60, blend_in=2.0, blend_out=8.0).mark('lit', 26).mark('burst', 34)
level_key(c, 0)
rear_key(c, 12, 48, ease='out', flail=1)
rear_key(c, 18, 56, flail=-1)
rear_key(c, 24, 58, flail=1)
rear_key(c, 30, 56, flail=-1)
steed_turn(c, 34, qeuler(x=8), FORE_HIPS, ease='in')
legs_key(c, 34, ease='in', fr=(-14, 0), fl=(-12, 0), hr=(8, -10), hl=(6, -8))
c.key('neck', 34, qeuler(x=16), ease='in')
c.key('horse_head', 34, qeuler(x=8), ease='in')
c.key('tail', 34, qeuler(x=-15), ease='in')
steed_turn(c, 38, qeuler(x=2), ease='out')
level_key(c, 60)
adds(c, 'steed', 'neck', 'horse_head', 'tail', *LEGS)
lean(c, 0)
lean(c, 12, x=36, ease='out')
lean(c, 30, x=40)
lean(c, 34, x=-4, ease='in')
lean(c, 40, x=4)
lean(c, 60)
arm_rest(c, 0)
arm_key(c, 14, *ASIDE, ease='out')
arm_key(c, 30, *ASIDE)
arm_key(c, 34, *CHOPPED, ease='in')
arm_key(c, 44, *CHOPPED)
arm_rest(c, 60)
lamp_key(c, 0)
lamp_key(c, 16, v(u=1.0, f=0.2, r=-0.2), ease='out', swing=qeuler(x=-10))
lamp_key(c, 30, v(u=1.0, f=0.25, r=-0.15), swing=qeuler(x=-15))
lamp_key(c, 34, v(f=0.7, u=-0.6, r=-0.3), ease='in', swing=qeuler(x=50))
lamp_key(c, 44, v(f=0.6, u=-0.7, r=-0.3), swing=qeuler(x=25))
lamp_key(c, 60)
c.base = standing()
c.phase_two = True
clips.append(c)

# ------------------------------------------------------------------ vanish: dawn finds it; it rears once, lifts the
# lantern, and is gone. 30 ticks; it fades on 24.
c = Clip('vanish', 30, blend_in=2.0, blend_out=4.0).mark('gone', 24)
level_key(c, 0)
rear_key(c, 12, 44, ease='out', flail=1)
rear_key(c, 20, 50, flail=-1)
rear_key(c, 30, 52, flail=1)
adds(c, 'steed', 'neck', 'horse_head', 'tail', *LEGS)
lean(c, 0)
lean(c, 12, x=32, ease='out')
lean(c, 30, x=36)
arm_rest(c, 0)
arm_key(c, 14, *RAISED, ease='out')
arm_key(c, 30, *RAISED)
lamp_key(c, 0)
lamp_key(c, 16, v(u=1.0, f=0.25, r=-0.2), ease='out', swing=qeuler(x=-10))
lamp_key(c, 30, v(u=1.0, f=0.3, r=-0.2), swing=qeuler(x=-12))
c.base = standing()
clips.append(c)

# ------------------------------------------------------------------ death: the steed staggers, its forelegs buckle,
# then its hind legs, and it lies down for good, the rider slumped over its neck. 70 ticks.
c = Clip('death', 70, blend_in=2.0, blend_out=0.0).mark('knees', 18).mark('down', 36).mark('falls', 50)
steed_turn(c, 0, I)
steed_turn(c, 6, qeuler(z=-7, x=-4), ease='out')
steed_turn(c, 11, qeuler(z=6, x=2), ease='inout')
steed_turn(c, 18, qeuler(x=16), np.array([0.0, 10.0, 8.0]), ease='in')
steed_turn(c, 26, qeuler(x=14), np.array([0.0, 10.0, 8.0]))
c.key('steed', 36, qeuler(x=3, z=-4), pos=np.array([0.0, 9.5, 0.0]), ease='in')
c.key('steed', 40, qeuler(x=2, z=-3), pos=np.array([0.0, 9.0, 0.0]), ease='out')
c.key('steed', 70, qeuler(x=2, z=-4), pos=np.array([0.0, 9.0, 0.0]))
legs_key(c, 0)
legs_key(c, 6, fr=(-8, 10), fl=(6, 0), hr=(4, 0), hl=(-6, -6), ease='out')
legs_key(c, 18, fr=(30, 120), fl=(26, 115), hr=(4, -10), hl=(0, -8), ease='in')
legs_key(c, 26, fr=(34, 125), fl=(30, 120), hr=(-10, -20), hl=(-8, -16))
legs_key(c, 36, fr=(55, 125), fl=(50, 125), hr=(-70, 120), hl=(-66, 118), ease='in')
legs_key(c, 70, fr=(58, 128), fl=(52, 126), hr=(-72, 124), hl=(-68, 120))
for t, nx, hx, tx in ((0, 0, 0, 0), (8, -14, -10, 10), (18, 16, 10, 0), (36, 30, 20, 20), (48, 44, 26, 30), (70, 46, 28, 32)):
    c.key('neck', t, qeuler(x=nx), ease='inout')
    c.key('horse_head', t, qeuler(x=hx), ease='inout')
    c.key('tail', t, qeuler(x=tx), ease='inout')
adds(c, 'steed', 'neck', 'horse_head', 'tail', *LEGS)
lean(c, 0)
lean(c, 6, x=-10, z=6, ease='out')
lean(c, 14, x=30, z=-4)
lean(c, 36, x=42, z=-8)
lean(c, 52, x=60, z=-16, ease='in')
lean(c, 70, x=62, z=-18)
arm_rest(c, 0)
arm_key(c, 12, v(u=-1.0, r=0.4, f=0.3), v(f=0.6, u=0.8), v(f=1.0))
arm_key(c, 36, v(u=-0.8, r=0.3, f=0.6), v(f=0.95, u=0.1, r=0.2), v(u=-1.0))
arm_key(c, 52, v(u=-0.45, r=0.25, f=0.85), v(f=0.5, u=0.85, r=0.4), v(r=-1.0), ease='in')
arm_key(c, 70, v(u=-0.4, r=0.25, f=0.9), v(f=0.5, u=0.85, r=0.4), v(r=-1.0))
lamp_key(c, 0)
lamp_key(c, 20, v(u=-1.0, f=0.2, r=-0.3), swing=qeuler(x=20))
lamp_key(c, 52, v(u=-0.5, f=0.85, r=-0.25), ease='in', swing=qeuler(z=40))
lamp_key(c, 70, v(u=-0.45, f=0.85, r=-0.25), swing=qeuler(z=75))
c.key('rider_head', 0, I)
c.key('rider_head', 20, qeuler(x=25))
c.key('rider_head', 52, qeuler(x=35, z=-10))
c.key('rider_head', 70, qeuler(x=38, z=-14))
c.key('cloak', 0, I)
c.key('cloak', 36, qeuler(x=-6))
c.key('cloak', 52, qeuler(x=-18, z=-10), ease='in')
c.key('cloak', 70, qeuler(x=-20, z=-12))
adds(c, 'cloak')
c.base = standing()
clips.append(c)

# ================================================================== previews

def world_rot(pose, bone):
    """A part's rotation in model space, through its parents."""
    q = pose[bone][0]
    while bone in PARENT:
        bone = PARENT[bone]
        q = qmul(pose[bone][0], q)
    return q


def hang_lantern(pose, sway):
    """The lantern hangs straight down from the fist whatever the arm does (as the game hangs it): what the clips
    gave it is how it swings in the world, and [sway] swings it a little more."""
    arm = world_rot(pose, 'rider_left_arm')
    pose['lantern'][0] = qmul(qinv(arm), qmul(pose['lantern'][0], sway))


def layer(pose, clip, t, w):
    """Blends [clip] at [t] over [pose] by [w], as the game's rig animator does."""
    if w <= 0:
        return
    for bone in clip.keys:
        if bone not in pose:
            continue
        qk, pk = clip.sample(bone, t)
        q0, v0 = pose[bone]
        if clip.modes.get(bone) == 'add':
            gq, gv = qmul(q0, qk), v0 + pk
        else:
            gq, gv = qmul(rest(bone), qk), pk
        pose[bone] = [qslerp(q0, gq, w), v0 + (gv - v0) * w]


def posed(layers, swing=None):
    pose = {name: [rest(name), np.zeros(3)] for name in IDX}
    for clip, t, w in layers:
        layer(pose, clip, t, w)
    hang_lantern(pose, swing if swing is not None else I)
    return {name: (qmat(q), off) for name, (q, off) in pose.items()}


def render(layers, yaw, pitch=-10, scale=4.0, size=(260, 300), phase_two=False, swing=None, y_shift=22):
    m = dr.model if phase_two else dr.without(dr.FLAME_PARTS)
    faces = m.world_faces(posed(layers, swing))
    g = dr.lit(dr.glow2 if phase_two else dr.glow)
    return m.render(dr.tex, yaw=yaw, pitch=pitch, scale=scale, size=size, glow=g, y_shift=y_shift, faces=faces)


def gait_sheet(clip, n=8, views=(-90, 30)):
    W, H = 260, 300
    out = Image.new('RGBA', (W * n, H * len(views) + 18), (30, 32, 40, 255))
    d = ImageDraw.Draw(out)
    d.text((4, 3), clip.name, fill=(235, 235, 235))
    for i in range(n):
        t = clip.length * i / n
        for j, yaw in enumerate(views):
            out.paste(render([(clip, t, 1.0)], yaw), (i * W, 18 + j * H))
        d.text((i * W + 4, 21), f't={t:.1f}', fill=(255, 255, 255))
    return out


def attack_sheet(clip, base=None, n=9, views=(-90, 35), phase_two=False):
    """Moments through an attack, over the standing pose (or [base], a gait at a fixed phase)."""
    W, H = 260, 300
    out = Image.new('RGBA', (W * n, H * len(views) + 18), (30, 32, 40, 255))
    d = ImageDraw.Draw(out)
    d.text((4, 3), clip.name + ' ' + ' '.join(f'{k}@{v}' for k, v in clip.marks.items()), fill=(235, 235, 235))
    for i in range(n):
        t = clip.length * i / (n - 1)
        under = base(t) if base else [(idle, 0.0, 1.0)]
        for j, yaw in enumerate(views):
            out.paste(render(under + [(clip, t, clip.weight(t))], yaw, phase_two=phase_two), (i * W, 18 + j * H))
        label = f't={t:.1f}'
        for name, mt in clip.marks.items():
            if abs(mt - t) < clip.length / 16:
                label += ' ' + name
        d.text((i * W + 4, 21), label, fill=(255, 255, 255))
    return out


if __name__ == '__main__':
    assets, prev = sys.argv[1], sys.argv[2]
    only = set(sys.argv[3].split(',')) if len(sys.argv) > 3 else None
    for c in [idle, walk, gallop] + clips:
        if only and c.name not in only:
            continue
        print(c.write(assets, GROUP))
    os.makedirs(prev, exist_ok=True)
    for c in (idle, walk, gallop):
        if only and c.name not in only:
            continue
        gait_sheet(c).save(os.path.join(prev, f'rider_{c.name}.png'))
    for c in clips:
        if only and c.name not in only:
            continue
        attack_sheet(c, getattr(c, 'base', None), phase_two=getattr(c, 'phase_two', False)).save(os.path.join(prev, f'rider_{c.name}.png'))
