"""
The dagger's combat moves: quick, close and low. Third-person clips pose the skeleton; first-person clips move the
held item. Run:

    python3 -B art/scripts/anims_dagger.py src/main/resources/assets/guildmark <preview-dir> <steve.png> <iron_dagger.png>

Timings match Move.kt (DAGGER_*): a clip lasts as many ticks as its move, and its blow lands inside the move's hit
window. The dagger is a one-handed weapon: the free left hand guards in front and swings back to balance each cut.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *
from weaponkit import DAGGER as G

def tp(name, length, **kw):
    return Clip('dagger/' + name, length, **kw)

clips, fps = [], []

# Every arm pose below is solved for the dagger's own grip
_arm = arm
def arm(direction, blade, left=False, geo=G):
    return _arm(direction, blade, left, geo)

def aim(az, el):
    """A direction [az] degrees to the right of straight ahead (negative: to the left), [el] degrees above the horizontal."""
    a, e = math.radians(az), math.radians(el)
    return v(f=math.cos(e) * math.cos(a), u=math.sin(e), r=math.cos(e) * math.sin(a))

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F)

def legs(c, t, right=0.0, left=0.0, ease='inout'):
    """Both legs at once: degrees about x, positive swings the foot back (so the front foot is the negative one)."""
    c.key('right_leg', t, qeuler(x=right), ease=ease)
    c.key('left_leg', t, qeuler(x=left), ease=ease)

REST = fp_rest(G)
REST_BLADE = G.fp_blade

def flat_edge(blade):
    """The edge direction that turns the dagger's flat toward the player, so it never shows edge-on."""
    b = unit(np.asarray(blade, float))
    n = -VF - np.dot(-VF, b) * b
    return unit(np.cross(n, b))

def fk(clip, t, hand, blade, edge=None, ease='inout'):
    """A first-person key: the grip at [hand], the blade along [blade]; the flat shows unless [edge] says otherwise."""
    off, q = fp(hand, blade, flat_edge(blade) if edge is None else edge, geo=G)
    clip.key('item', t, q, pos=off, ease=ease)

def at_rest(clip, t):
    """The dagger where vanilla draws it: exactly the identity."""
    off, q = fp(REST, REST_BLADE, None, geo=G)
    clip.key('item', t, q, pos=off, ease='inout')

def cut(clip, t, hand, blade, motion, ease='inout'):
    """A first-person key for a cut: the flat shows to the player, rolled a little so the edge leads along [motion]."""
    b = unit(np.asarray(blade, float))
    flat = flat_edge(b)
    m = np.asarray(motion, float)
    m = m - np.dot(m, b) * b
    if np.linalg.norm(m) > 1e-6:
        fk(clip, t, hand, b, unit(flat + 0.45 * unit(m)), ease)
    else:
        fk(clip, t, hand, b, flat, ease)


# ------------------------------------------------------------------ the light combo
# Each cut ends where the next begins, so the poses it hands over are named here: the body turned left after the
# forehand, turned right after the backhand, and the arm driven out after the stab.
ENDS_LEFT = dict(torso=qmul(qeuler(y=-20), qeuler(x=6)), arm=arm(aim(-36, -50), aim(-36, 40)),
                 free=free_arm(v(f=-0.2, u=-1.0, r=-0.5)), legs=(12, -14), root=(0, 1.0, -1.0))
ENDS_RIGHT = dict(torso=qmul(qeuler(y=18), qeuler(x=5)), arm=arm(aim(38, -50), aim(38, 40)),
                  free=free_arm(aim(-60, -20)), legs=(-14, 12), root=(0, 1.0, -1.0))
STAB_END = dict(torso=qmul(qeuler(y=-10), qeuler(x=14)), arm=arm(aim(-2, -50), aim(-2, 40)),
                free=free_arm(aim(-28, -38)), legs=(20, -24), root=(0, 1.4, -1.8))

def begin(c, ends):
    """Key the first frame of a cut as the pose the one before it ended in."""
    c.key('torso', 0, ends['torso'])
    c.key('right_arm', 0, ends['arm'])
    c.key('left_arm', 0, ends['free'])
    legs(c, 0, *ends['legs'])
    c.key('root', 0, pos=ends['root'])

# ------------------------------------------------------------------ light 1: a quick forehand, right to left at chest height
c = tp('light_1', 9, blend_in=1.0, blend_out=4.0).mark('hit', 3).mark('chain', 5)
c.key('torso', 0)
c.key('torso', 1.5, qmul(qeuler(y=16), qeuler(x=2)), ease='out')
c.key('torso', 2.5, qmul(qeuler(y=14), qeuler(x=4)), ease='inout')
c.key('torso', 3.0, qmul(qeuler(y=8), qeuler(x=7)), ease='in')
c.key('torso', 4.0, qmul(qeuler(y=-10), qeuler(x=8)), ease='linear')
c.key('torso', 5.0, ENDS_LEFT['torso'], ease='out')
c.key('torso', 9, qeuler(y=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 1.5, arm(aim(44, -52), aim(44, 38)), ease='out')
c.key('right_arm', 2.5, arm(aim(40, -50), aim(40, 40)), ease='inout')
c.key('right_arm', 3.0, arm(aim(28, -46), aim(28, 44)), ease='in')
c.key('right_arm', 4.0, arm(aim(-30, -46), aim(-30, 44)), ease='linear')
c.key('right_arm', 5.0, ENDS_LEFT['arm'], ease='out')
c.key('right_arm', 9, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 1.5, free_arm(aim(-14, -30)), ease='out')
c.key('left_arm', 3.0, free_arm(aim(-20, -22)), ease='inout')
c.key('left_arm', 5.0, ENDS_LEFT['free'], ease='out')
c.key('left_arm', 9, free_arm(D), ease='inout')
legs(c, 0)
legs(c, 1.5, right=4, left=-4, ease='out')
legs(c, 3.5, *ENDS_LEFT['legs'])
legs(c, 9)
c.key('root', 0)
c.key('root', 1.5, pos=(0, 0.6, 0.4), ease='out')
c.key('root', 3.5, pos=ENDS_LEFT['root'], ease='inout')
c.key('root', 9, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('light_1', 9, blend_in=1.0, blend_out=4.0)
at_rest(f, 0)
fk(f, 1.5, (0.58, -0.42, -0.72), vv(u=0.75, f=0.2, r=0.55), ease='out')
fk(f, 2.5, (0.6, -0.41, -0.72), vv(u=0.72, f=0.2, r=0.6), ease='inout')
cut(f, 3.0, (0.42, -0.3, -0.76), vv(u=0.8, f=0.3, r=0.4), vv(r=-1.0), ease='in')
cut(f, 4.0, (-0.2, -0.28, -0.78), vv(u=0.8, f=0.35, r=-0.4), vv(r=-1.0), ease='linear')
cut(f, 5.0, (-0.52, -0.36, -0.7), vv(u=0.7, f=0.15, r=-0.6), vv(r=-1.0, f=-0.2), ease='out')
at_rest(f, 9)
fps.append(f)

# ------------------------------------------------------------------ light 2: the backhand, left to right
c = tp('light_2', 9, blend_in=1.0, blend_out=4.0).mark('hit', 3).mark('chain', 5)
begin(c, ENDS_LEFT)
c.key('torso', 1.5, qmul(qeuler(y=-24), qeuler(x=5)), ease='out')
c.key('torso', 2.5, qmul(qeuler(y=-22), qeuler(x=5)), ease='inout')
c.key('torso', 3.0, qmul(qeuler(y=-14), qeuler(x=7)), ease='in')
c.key('torso', 4.0, qmul(qeuler(y=6), qeuler(x=8)), ease='linear')
c.key('torso', 5.0, ENDS_RIGHT['torso'], ease='out')
c.key('torso', 9, qeuler(y=0), ease='inout')
c.key('right_arm', 1.5, arm(aim(-42, -46), aim(-42, 44)), ease='out')
c.key('right_arm', 2.5, arm(aim(-38, -44), aim(-38, 46)), ease='inout')
c.key('right_arm', 3.0, arm(aim(-28, -42), aim(-28, 48)), ease='in')
c.key('right_arm', 4.0, arm(aim(28, -42), aim(28, 48)), ease='linear')
c.key('right_arm', 5.0, ENDS_RIGHT['arm'], ease='out')
c.key('right_arm', 9, rest_arm(), ease='inout')
c.key('left_arm', 1.5, free_arm(aim(-34, -15)), ease='out')
c.key('left_arm', 3.0, free_arm(aim(-30, -10)), ease='inout')
c.key('left_arm', 5.0, ENDS_RIGHT['free'], ease='out')
c.key('left_arm', 9, free_arm(D), ease='inout')
legs(c, 1.5, right=8, left=-8, ease='out')
legs(c, 3.5, *ENDS_RIGHT['legs'])
legs(c, 9)
c.key('root', 1.5, pos=(0, 0.8, 0.2), ease='out')
c.key('root', 3.5, pos=ENDS_RIGHT['root'], ease='inout')
c.key('root', 9, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('light_2', 9, blend_in=1.0, blend_out=4.0)
fk(f, 0, (-0.52, -0.36, -0.7), vv(u=0.7, f=0.15, r=-0.6))
fk(f, 1.5, (-0.58, -0.38, -0.7), vv(u=0.75, f=0.15, r=-0.5), ease='out')
cut(f, 3.0, (-0.45, -0.3, -0.8), vv(u=0.8, f=0.3, r=-0.4), vv(r=1.0), ease='in')
cut(f, 4.0, (0.22, -0.28, -0.82), vv(u=0.8, f=0.35, r=0.4), vv(r=1.0), ease='linear')
cut(f, 5.0, (0.56, -0.36, -0.7), vv(u=0.7, f=0.15, r=0.6), vv(r=1.0, f=-0.2), ease='out')
at_rest(f, 9)
fps.append(f)

# ------------------------------------------------------------------ light 3: a straight stab
c = tp('light_3', 9, blend_in=1.0, blend_out=4.0).mark('hit', 3).mark('chain', 5)
begin(c, ENDS_RIGHT)
c.key('torso', 1.5, qmul(qeuler(y=26), qeuler(x=2)), ease='out')
c.key('torso', 3.0, qmul(qeuler(y=-6), qeuler(x=14)), ease='in')
c.key('torso', 3.6, STAB_END['torso'], ease='out')
c.key('torso', 4.5, STAB_END['torso'], ease='inout')
c.key('torso', 9, qeuler(y=0), ease='inout')
c.key('right_arm', 1.5, arm(v(f=-0.3, u=-0.95, r=0.3), F), ease='out')
c.key('right_arm', 3.0, arm(aim(-2, -44), aim(-2, 46)), ease='in')
c.key('right_arm', 3.6, STAB_END['arm'], ease='out')
c.key('right_arm', 4.5, STAB_END['arm'], ease='inout')
c.key('right_arm', 9, rest_arm(), ease='inout')
c.key('left_arm', 1.5, free_arm(aim(-8, -10)), ease='out')
c.key('left_arm', 3.0, free_arm(aim(-20, -28)), ease='in')
c.key('left_arm', 3.6, STAB_END['free'], ease='out')
c.key('left_arm', 9, free_arm(D), ease='inout')
legs(c, 1.5, right=8, left=-6, ease='out')
legs(c, 3.0, right=18, left=-22, ease='in')
legs(c, 3.6, *STAB_END['legs'], ease='out')
legs(c, 5.0, *STAB_END['legs'])
legs(c, 9)
c.key('root', 1.5, pos=(0, 0.5, 0.8), ease='out')
c.key('root', 3.0, pos=(0, 1.3, -1.6), ease='in')
c.key('root', 3.6, pos=STAB_END['root'], ease='out')
c.key('root', 5.0, pos=STAB_END['root'], ease='inout')
c.key('root', 9, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('light_3', 9, blend_in=1.0, blend_out=4.0)
fk(f, 0, (0.56, -0.36, -0.7), vv(u=0.7, f=0.15, r=0.6))
fk(f, 1.5, (0.6, -0.42, -0.6), vv(f=0.85, u=0.4, r=-0.1), ease='out')
fk(f, 3.0, (0.22, -0.36, -0.84), vv(f=0.8, u=0.55, r=-0.08), ease='in')
fk(f, 3.6, (0.16, -0.35, -0.9), vv(f=0.8, u=0.55, r=-0.08), ease='out')
fk(f, 4.5, (0.17, -0.36, -0.89), vv(f=0.8, u=0.55, r=-0.08), ease='inout')
at_rest(f, 9)
fps.append(f)

# ------------------------------------------------------------------ light 4: a double cut, the second blow turned back on the first
c = tp('light_4', 14, blend_in=1.0, blend_out=4.0).mark('hit', 4).mark('hit 2', 7).mark('chain', 9)
begin(c, STAB_END)
c.key('torso', 2.5, qmul(qeuler(y=24), qeuler(x=2)), ease='out')
c.key('torso', 4.0, qmul(qeuler(y=12), qeuler(x=8)), ease='in')
c.key('torso', 5.0, qmul(qeuler(y=-14), qeuler(x=8)), ease='linear')
c.key('torso', 6.5, qmul(qeuler(y=-22), qeuler(x=6)), ease='out')
c.key('torso', 7.0, qmul(qeuler(y=-20), qeuler(x=7)), ease='in')
c.key('torso', 8.5, qmul(qeuler(y=18), qeuler(x=6)), ease='linear')
c.key('torso', 9.5, qmul(qeuler(y=22), qeuler(x=5)), ease='out')
c.key('torso', 10.8, qmul(qeuler(y=6), qeuler(x=3)), ease='out')
c.key('torso', 14, qeuler(y=0), ease='inout')
c.key('right_arm', 2.5, arm(aim(46, -54), aim(46, 36)), ease='out')
c.key('right_arm', 4.0, arm(aim(28, -46), aim(28, 44)), ease='in')
c.key('right_arm', 5.0, arm(aim(-30, -46), aim(-30, 44)), ease='linear')
c.key('right_arm', 6.5, arm(aim(-38, -50), aim(-38, 40)), ease='out')
c.key('right_arm', 7.0, arm(aim(-36, -44), aim(-36, 46)), ease='in')
c.key('right_arm', 8.5, arm(aim(30, -44), aim(30, 46)), ease='linear')
c.key('right_arm', 9.5, arm(aim(42, -54), aim(42, 36)), ease='out')
c.key('right_arm', 10.8, arm(v(f=0.55, u=-0.8, r=0.15), aim(10, 70)), ease='out')
c.key('right_arm', 14, rest_arm(), ease='inout')
c.key('left_arm', 2.5, free_arm(aim(-14, -28)), ease='out')
c.key('left_arm', 5.0, free_arm(v(f=-0.2, u=-1.0, r=-0.5)), ease='inout')
c.key('left_arm', 7.0, free_arm(aim(-40, -15)), ease='out')
c.key('left_arm', 9.0, free_arm(aim(-60, -20)), ease='inout')
c.key('left_arm', 14, free_arm(D), ease='inout')
legs(c, 2.5, right=8, left=-8, ease='out')
legs(c, 4.5, right=18, left=-22)
legs(c, 7.5, right=-16, left=14)
legs(c, 10.8, right=-6, left=5, ease='out')
legs(c, 14)
c.key('root', 2.5, pos=(0, 1.2, 0.6), ease='out')
c.key('root', 4.5, pos=(0, 1.2, -1.2), ease='inout')
c.key('root', 7.5, pos=(0, 1.0, -1.0), ease='inout')
c.key('root', 10.8, pos=(0, 0.4, -0.3), ease='out')
c.key('root', 14, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('light_4', 14, blend_in=1.0, blend_out=4.0)
fk(f, 0, (0.17, -0.36, -0.89), vv(f=0.8, u=0.55, r=-0.08))
fk(f, 2.5, (0.6, -0.42, -0.72), vv(u=0.75, f=0.2, r=0.55), ease='out')
cut(f, 4.0, (0.42, -0.3, -0.76), vv(u=0.8, f=0.3, r=0.4), vv(r=-1.0), ease='in')
cut(f, 5.0, (-0.25, -0.28, -0.82), vv(u=0.8, f=0.35, r=-0.4), vv(r=-1.0), ease='linear')
cut(f, 6.5, (-0.52, -0.36, -0.7), vv(u=0.7, f=0.15, r=-0.6), vv(r=-1.0, f=-0.2), ease='out')
cut(f, 7.0, (-0.55, -0.32, -0.74), vv(u=0.7, f=0.2, r=-0.6), vv(r=1.0), ease='in')
cut(f, 8.5, (0.4, -0.28, -0.82), vv(u=0.8, f=0.35, r=0.4), vv(r=1.0), ease='linear')
cut(f, 9.5, (0.56, -0.38, -0.7), vv(u=0.7, f=0.15, r=0.6), vv(r=1.0, f=-0.2), ease='out')
fk(f, 10.8, (0.6, -0.46, -0.72), vv(f=0.5, u=0.8, r=0.2), ease='out')
at_rest(f, 14)
fps.append(f)

# ------------------------------------------------------------------ heavy: crouched and coiled while held, then a lunging stab
# The pose at tick 4 is the hold (the game freezes the clip there while the button is down): low and wide, the body
# wound round to the right, the dagger drawn back at the hip with its point forward, the free hand reaching out.
c = tp('heavy', 16, blend_in=1.5, blend_out=5.0).mark('hold', 4).mark('step', 6).mark('hit', 7).mark('chain', 11)
c.key('torso', 0)
c.key('torso', 4.0, qmul(qeuler(y=30), qeuler(x=14)), ease='out')
c.key('torso', 5.0, qmul(qeuler(y=28), qeuler(x=15)), ease='inout')
c.key('torso', 7.0, qmul(qeuler(y=-12), qeuler(x=22)), ease='in')
c.key('torso', 9.0, qmul(qeuler(y=-12), qeuler(x=21)), ease='out')
c.key('torso', 16, qeuler(x=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 4.0, arm(aim(10, -80), aim(-30, 8)), ease='out')
c.key('right_arm', 5.0, arm(aim(4, -84), aim(-30, 8)), ease='inout')
c.key('right_arm', 7.2, arm(aim(-8, -26), aim(-8, 64)), ease='in3')
c.key('right_arm', 8.5, arm(aim(-8, -29), aim(-8, 61)), ease='out')
c.key('right_arm', 16, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 4.0, free_arm(aim(-14, 10)), ease='out')
c.key('left_arm', 5.0, free_arm(aim(-14, 8)), ease='inout')
c.key('left_arm', 7.0, free_arm(v(f=-0.6, u=-0.7, r=-0.5)), ease='in')
c.key('left_arm', 16, free_arm(D), ease='inout')
c.key('right_leg', 0)
c.key('right_leg', 4.0, qeuler(x=34, z=6), ease='out')
c.key('right_leg', 6.0, qeuler(x=44, z=6), ease='in')
c.key('right_leg', 10.0, qeuler(x=40, z=5), ease='out')
c.key('right_leg', 16, qeuler(x=0), ease='inout')
c.key('left_leg', 0)
c.key('left_leg', 4.0, qeuler(x=-30, z=-6), ease='out')
c.key('left_leg', 6.0, qeuler(x=-52, z=-6), ease='in')
c.key('left_leg', 10.0, qeuler(x=-48, z=-5), ease='out')
c.key('left_leg', 16, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 4.0, pos=(0, 3.2, 1.2), ease='out')
c.key('root', 6.0, pos=(0, 3.8, -1.5), ease='in')
c.key('root', 7.5, pos=(0, 3.8, -3.2), ease='out')
c.key('root', 10.0, pos=(0, 3.5, -3.0), ease='inout')
c.key('root', 16, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('heavy', 16, blend_in=1.5, blend_out=5.0)
at_rest(f, 0)
fk(f, 4.0, (0.6, -0.4, -0.6), vv(f=0.9, u=0.35, r=-0.25), ease='out')
fk(f, 5.0, (0.63, -0.42, -0.58), vv(f=0.9, u=0.35, r=-0.25), ease='inout')
fk(f, 7.2, (0.14, -0.36, -0.86), vv(f=0.8, u=0.55, r=-0.08), ease='in3')
fk(f, 8.5, (0.12, -0.35, -0.9), vv(f=0.8, u=0.55, r=-0.08), ease='out')
at_rest(f, 16)
fps.append(f)

# ------------------------------------------------------------------ running: a rising cut as the body passes
# Low in a sprint, the dagger trailing at the hip; it comes up across the front, right to left, as the body goes by.
c = tp('running', 12, blend_in=1.0, blend_out=4.0).mark('hit', 3).mark('chain', 8)
c.key('torso', 0, qmul(qeuler(y=18), qeuler(x=18)))
c.key('torso', 2.0, qmul(qeuler(y=28), qeuler(x=20)), ease='out')
c.key('torso', 3.0, qmul(qeuler(y=20), qeuler(x=21)), ease='in')
c.key('torso', 5.0, qmul(qeuler(y=-16), qeuler(x=16)), ease='linear')
c.key('torso', 6.5, qmul(qeuler(y=-26), qeuler(x=12)), ease='out')
c.key('torso', 12, qeuler(y=0), ease='inout')
c.key('right_arm', 0, arm(v(f=-0.45, u=-0.8, r=0.4), F))
c.key('right_arm', 2.0, arm(v(f=-0.6, u=-0.75, r=0.5), F), ease='out')
# One sweep from low behind to high across the front: steady through the window, then easing into the finish
SWEEP_A = arm(v(f=-0.35, u=-0.85, r=0.4), v(f=1.0, u=0.2))
SWEEP_B = arm(aim(-32, 4), aim(-32, 94))
c.key('right_arm', 3.0, SWEEP_A, ease='in')
c.key('right_arm', 5.0, qslerp(SWEEP_A, SWEEP_B, 0.73), ease='linear')
c.key('right_arm', 6.5, SWEEP_B, ease='out')
c.key('right_arm', 12, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(v(f=0.4, u=-0.8, r=-0.3)))
c.key('left_arm', 3.0, free_arm(aim(-25, 10)), ease='inout')
c.key('left_arm', 6.0, free_arm(v(f=-0.5, u=-0.8, r=-0.5)), ease='inout')
c.key('left_arm', 12, free_arm(D), ease='inout')
legs(c, 0, right=-32, left=30)
legs(c, 3.0, right=34, left=-34)
legs(c, 6.0, right=-26, left=24)
legs(c, 12)
c.key('root', 0, pos=(0, 1.0, 0))
c.key('root', 2.5, pos=(0, 2.0, 0.5), ease='out')
c.key('root', 5.0, pos=(0, 0.6, -2.0), ease='inout')
c.key('root', 12, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('running', 12, blend_in=1.0, blend_out=4.0)
at_rest(f, 0)
fk(f, 2.0, (0.64, -0.42, -0.6), vv(f=0.8, u=0.5, r=0.2), ease='out')
cut(f, 3.0, (0.52, -0.4, -0.68), vv(f=0.7, u=0.6, r=0.2), vv(u=0.8, r=-0.5), ease='in')
cut(f, 5.0, (-0.17, -0.18, -0.74), vv(f=0.3, u=0.8, r=-0.4), vv(u=0.8, r=-0.5), ease='linear')
cut(f, 6.5, (-0.42, -0.1, -0.76), vv(f=0.15, u=0.8, r=-0.6), vv(u=0.8, r=-0.5), ease='out')
at_rest(f, 12)
fps.append(f)

# ------------------------------------------------------------------ jump: a plunging stab from the air
# The dagger goes up in an ice-pick grip, the point toward the target; the body folds over it and drives it down in
# front as the knees bend to land. The blade stays square to the arm, so it only hangs straight down once the arm
# is level: the torso's lean is worked out of the blade's pitch below.
c = tp('jump', 12, blend_in=1.0, blend_out=4.0).mark('hit', 4).mark('chain', 9)
c.key('torso', 0)
c.key('torso', 2.5, qeuler(x=-14), ease='out')
c.key('torso', 3.5, qeuler(x=-10), ease='inout')
c.key('torso', 5.5, qeuler(x=32), ease='in3')
c.key('torso', 8.0, qeuler(x=28), ease='out')
c.key('torso', 12, qeuler(x=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 2.5, arm(aim(22, 40), aim(22, -50)), ease='out')
c.key('right_arm', 3.5, arm(aim(20, 38), aim(20, -52)), ease='inout')
c.key('right_arm', 5.5, arm(aim(0, 22), aim(0, -58)), ease='in3')
c.key('right_arm', 8.0, arm(aim(0, 18), aim(0, -60)), ease='out')
c.key('right_arm', 12, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 2.5, free_arm(aim(-40, 55)), ease='out')
c.key('left_arm', 5.5, free_arm(v(f=-0.4, u=-0.5, r=-0.8)), ease='in')
c.key('left_arm', 12, free_arm(D), ease='inout')
legs(c, 0)
legs(c, 2.5, right=-34, left=-22, ease='out')
legs(c, 4.5, right=-14, left=-10, ease='inout')
legs(c, 6.5, right=32, left=-36, ease='out')
legs(c, 12)
c.key('root', 0)
c.key('root', 2.5, pos=(0, -1.0, 0.5), ease='out')
c.key('root', 3.5, pos=(0, -0.6, 0.2), ease='inout')
c.key('root', 5.5, pos=(0, 1.0, -1.5), ease='in')
c.key('root', 6.5, pos=(0, 3.3, -1.4), ease='out')
c.key('root', 8.5, pos=(0, 3.2, -1.2), ease='inout')
c.key('root', 12, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('jump', 12, blend_in=1.0, blend_out=4.0)
at_rest(f, 0)
fk(f, 2.5, (0.34, 0.16, -0.64), vv(f=0.5, u=-0.75, r=0.05), ease='out')
fk(f, 3.5, (0.36, 0.18, -0.64), vv(f=0.5, u=-0.75, r=0.05), ease='inout')
fk(f, 5.5, (0.14, -0.06, -0.84), vv(f=0.15, u=-0.95, r=0.0), ease='in3')
fk(f, 8.0, (0.16, -0.1, -0.82), vv(f=0.2, u=-0.95, r=0.0), ease='out')
fk(f, 10.0, (0.46, -0.34, -0.8), vv(f=0.9, u=0.1, r=0.2), ease='inout')
at_rest(f, 12)
fps.append(f)

# ------------------------------------------------------------------ guard counter: a rising rip, low left to high right
# Coiled to the left behind the guard with the point across the belly, then one quick rip up and over to the right.
c = tp('guard_counter', 12, blend_in=1.0, blend_out=4.0).mark('hit', 4).mark('chain', 8)
c.key('torso', 0, qeuler(y=-14))
c.key('torso', 2.5, qmul(qeuler(y=-26), qeuler(x=10)), ease='out')
c.key('torso', 3.25, qmul(qeuler(y=-28), qeuler(x=11)), ease='inout')
c.key('torso', 4.0, qmul(qeuler(y=-14), qeuler(x=8)), ease='in')
c.key('torso', 4.6, qmul(qeuler(y=4), qeuler(x=3)), ease='linear')
c.key('torso', 5.15, qmul(qeuler(y=20), qeuler(x=-2)), ease='linear')
c.key('torso', 6.0, qmul(qeuler(y=26), qeuler(x=-5)), ease='out')
c.key('torso', 12, qeuler(y=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 2.5, arm(aim(-22, -66), aim(-22, 24)), ease='out')
c.key('right_arm', 3.25, arm(aim(-24, -68), aim(-24, 22)), ease='inout')
c.key('right_arm', 4.0, arm(aim(-6, -40), aim(-6, 50)), ease='in')
c.key('right_arm', 4.6, arm(aim(14, -12), aim(14, 78)), ease='linear')
c.key('right_arm', 5.15, arm(aim(30, 8), aim(30, 98)), ease='linear')
c.key('right_arm', 6.0, arm(aim(36, 20), aim(36, 110)), ease='out')
c.key('right_arm', 12, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(aim(-5, 10)))
c.key('left_arm', 2.5, free_arm(aim(-8, 12)), ease='out')
c.key('left_arm', 4.6, free_arm(v(f=-0.3, u=-1.0, r=-0.5)), ease='inout')
c.key('left_arm', 12, free_arm(D), ease='inout')
legs(c, 0)
legs(c, 2.5, right=8, left=-12, ease='out')
legs(c, 4.0, right=18, left=-20)
legs(c, 6.0, right=10, left=-12, ease='out')
legs(c, 12)
c.key('root', 0)
c.key('root', 2.5, pos=(0, 1.6, 0.6), ease='out')
c.key('root', 4.5, pos=(0, 0.6, -1.4), ease='inout')
c.key('root', 12, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('guard_counter', 12, blend_in=1.0, blend_out=4.0)
at_rest(f, 0)
fk(f, 2.5, (-0.2, -0.42, -0.64), vv(f=0.5, u=0.8, r=-0.3), ease='out')
fk(f, 3.25, (-0.24, -0.43, -0.64), vv(f=0.5, u=0.8, r=-0.35), ease='inout')
cut(f, 4.0, (0.05, -0.36, -0.74), vv(f=0.6, u=0.6, r=0.2), vv(u=0.8, r=0.5), ease='in')
cut(f, 4.6, (0.28, -0.22, -0.76), vv(f=0.3, u=0.8, r=0.4), vv(u=0.8, r=0.5), ease='linear')
cut(f, 5.15, (0.42, -0.06, -0.72), vv(u=0.7, r=0.5, f=-0.1), vv(u=0.7, r=0.6), ease='linear')
cut(f, 6.0, (0.46, 0.0, -0.7), vv(u=0.75, r=0.5, f=-0.15), vv(u=0.7, r=0.6), ease='out')
at_rest(f, 12)
fps.append(f)

# ------------------------------------------------------------------ skill, Shadowstep: a low dash, then rise into a stab
# Ticks 1 to 7 are the dash (nothing can touch the player): dropped low, bent far forward, the dagger held back; the
# stab comes as the body rises out of it at 8.
c = tp('skill', 18, blend_in=1.0, blend_out=5.0).mark('dash', 2).mark('hit', 8).mark('chain', 13)
c.key('torso', 0)
c.key('torso', 2.0, qmul(qeuler(y=14), qeuler(x=36)), ease='out')
c.key('torso', 6.5, qmul(qeuler(y=16), qeuler(x=38)), ease='inout')
c.key('torso', 8.2, qmul(qeuler(y=-10), qeuler(x=24)), ease='in')
c.key('torso', 10.0, qmul(qeuler(y=-12), qeuler(x=22)), ease='out')
c.key('torso', 18, qeuler(x=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 2.0, arm(v(f=-0.55, u=-0.8, r=0.3), F), ease='out')
c.key('right_arm', 6.5, arm(v(f=-0.6, u=-0.75, r=0.35), F), ease='inout')
c.key('right_arm', 8.2, arm(aim(-2, -42), aim(-2, 48)), ease='in')
c.key('right_arm', 10.0, arm(aim(-2, -44), aim(-2, 46)), ease='out')
c.key('right_arm', 18, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 2.0, free_arm(v(f=-0.7, u=-0.6, r=-0.4)), ease='out')
c.key('left_arm', 6.5, free_arm(v(f=-0.6, u=-0.7, r=-0.5)), ease='inout')
c.key('left_arm', 8.2, free_arm(aim(-30, 0)), ease='out')
c.key('left_arm', 18, free_arm(D), ease='inout')
legs(c, 0)
legs(c, 2.0, right=44, left=-48, ease='out')
legs(c, 4.0, right=-46, left=44)
legs(c, 6.0, right=44, left=-46)
legs(c, 7.2, right=34, left=-40)
legs(c, 8.2, right=38, left=-46, ease='out')
legs(c, 11.0, right=34, left=-42)
legs(c, 18)
c.key('root', 0)
c.key('root', 2.0, pos=(0, 4.4, -1.0), ease='out')
c.key('root', 6.5, pos=(0, 4.2, -1.5), ease='inout')
c.key('root', 8.2, pos=(0, 2.8, -3.0), ease='out')
c.key('root', 11.0, pos=(0, 2.4, -2.6), ease='inout')
c.key('root', 18, pos=(0, 0, 0), ease='inout')
clips.append(c)

f = tp('skill', 18, blend_in=1.0, blend_out=5.0)
at_rest(f, 0)
fk(f, 2.0, (0.64, -0.4, -0.6), vv(f=0.85, u=0.4, r=-0.1), ease='out')
fk(f, 4.0, (0.68, -0.37, -0.58), vv(f=0.85, u=0.4, r=-0.1), ease='inout')
fk(f, 6.5, (0.66, -0.4, -0.6), vv(f=0.85, u=0.4, r=-0.1), ease='inout')
fk(f, 8.2, (0.14, -0.36, -0.86), vv(f=0.8, u=0.55, r=-0.08), ease='in')
fk(f, 10.0, (0.16, -0.36, -0.9), vv(f=0.8, u=0.55, r=-0.08), ease='out')
at_rest(f, 18)
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
    only = sys.argv[5:] or [c.name.split('/')[-1] for c in clips]
    for c in clips:
        short = c.name.split('/')[-1]
        if short not in only:
            continue
        sheet(scene, c, by_name.get(c.name)).save(os.path.join(previews, short + '.png'))
