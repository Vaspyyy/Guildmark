"""
The creatures' attacks for the new combat: the Hollow Soldier's sword and shield, and Halvard's greatsword.
Same skeleton and kit as the player's moves (animkit.py). Run:

    python3 art/scripts/enemy_anims.py src/main/resources/assets/guildmark <preview-dir> <unused> <iron_sword.png> [names]

Timings match HollowSoldier.kt and FallenWarden.kt: each clip lasts as many ticks as its attack and each
blow lands on the tick the attack resolves it. Wind-ups are slow and easy to read, blows fast, and every
attack ends in a recovery a player can punish. The previews draw each creature's own model and texture,
read from EnemyModels.kt; the soldier carries the iron sword item, Halvard the greatsword built into his model.
(The third argument is unused; it keeps the same command line as combat_anims.py.)
"""
import math, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animkit import *

soldier, warden = [], []

def tp(name, length, **kw):
    return Clip(name, length, **kw)

def legs(c, keys):
    """Leg keys as (t, right x, left x, ease); positive swings a leg back."""
    for t, rx, lx, ease in keys:
        c.key('right_leg', t, qeuler(x=rx), ease=ease)
        c.key('left_leg', t, qeuler(x=lx), ease=ease)

# ================================================================== the Hollow Soldier

def rest_arm():
    return arm(v(f=0.3, u=-1.0, r=0.05), F)

def shield_up():
    """The shield (or a fist) held up in front."""
    return free_arm(v(f=1.0, u=-0.25, r=-0.15))

# ------------------------------------------------------------------ slash: a forehand cut, high right to low left
c = tp('slash', 24, blend_in=2.0, blend_out=5.0).mark('hit', 11)
c.key('torso', 0)
c.key('torso', 7.0, qmul(qeuler(y=36), qeuler(x=-6)), ease='out')
c.key('torso', 9.0, qmul(qeuler(y=38), qeuler(x=-7)), ease='inout')
c.key('torso', 10.5, qmul(qeuler(y=6), qeuler(x=6)), ease='in')
c.key('torso', 12.0, qmul(qeuler(y=-30), qeuler(x=12)), ease='linear')
c.key('torso', 15.0, qmul(qeuler(y=-36), qeuler(x=9)), ease='out')
c.key('torso', 24, qeuler(y=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 7.0, arm(v(f=0.1, u=1.0, r=0.5), v(f=-1.0, r=0.2)), ease='out')
c.key('right_arm', 9.0, arm(v(f=0.05, u=1.0, r=0.55), v(f=-1.0, r=0.25, u=0.1)), ease='inout')
c.key('right_arm', 10.5, arm(v(f=1.0, u=0.55, r=0.35), v(u=0.7, r=-0.6, f=0.2)), ease='in')
c.key('right_arm', 12.0, arm(v(f=1.0, u=-0.35, r=-0.55), v(r=-1.0, u=-0.3, f=0.3)), ease='linear')
c.key('right_arm', 15.0, arm(v(f=0.45, u=-0.9, r=-0.45), v(r=-0.8, f=-0.4, u=0.1)), ease='out')
c.key('right_arm', 24, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 7.0, shield_up(), ease='out')
c.key('left_arm', 12.0, free_arm(v(f=-0.3, u=-1.0, r=-0.3)), ease='inout')
c.key('left_arm', 24, free_arm(D), ease='inout')
legs(c, [(0, 0, 0, 'inout'), (7.0, 10, -12, 'out'), (12.0, 20, -24, 'in'), (16, 18, -20, 'out'), (24, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 7.0, pos=(0, 0.4, 0.8), ease='out')
c.key('root', 12.0, pos=(0, 1.2, -1.4), ease='in')
c.key('root', 24, pos=(0, 0, 0), ease='inout')
soldier.append(c)

# ------------------------------------------------------------------ double: the forehand, then a backhand straight back
c = tp('double', 34, blend_in=2.0, blend_out=5.0).mark('hit', 11).mark('hit 2', 21)
c.key('torso', 0)
c.key('torso', 7.0, qmul(qeuler(y=34), qeuler(x=-6)), ease='out')
c.key('torso', 9.0, qmul(qeuler(y=36), qeuler(x=-6)), ease='inout')
c.key('torso', 10.5, qmul(qeuler(y=6), qeuler(x=6)), ease='in')
c.key('torso', 12.0, qmul(qeuler(y=-30), qeuler(x=12)), ease='linear')
c.key('torso', 14.0, qmul(qeuler(y=-38), qeuler(x=8)), ease='out')
c.key('torso', 18.0, qmul(qeuler(y=-40), qeuler(x=6)), ease='inout')
c.key('torso', 20.0, qmul(qeuler(y=-6), qeuler(x=8)), ease='in')
c.key('torso', 22.0, qmul(qeuler(y=30), qeuler(x=4)), ease='linear')
c.key('torso', 25.0, qmul(qeuler(y=36), qeuler(x=0)), ease='out')
c.key('torso', 34, qeuler(y=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 7.0, arm(v(f=0.1, u=1.0, r=0.5), v(f=-1.0, r=0.2)), ease='out')
c.key('right_arm', 9.0, arm(v(f=0.05, u=1.0, r=0.55), v(f=-1.0, r=0.25, u=0.1)), ease='inout')
c.key('right_arm', 10.5, arm(v(f=1.0, u=0.55, r=0.35), v(u=0.7, r=-0.6, f=0.2)), ease='in')
c.key('right_arm', 12.0, arm(v(f=1.0, u=-0.35, r=-0.55), v(r=-1.0, u=-0.3, f=0.3)), ease='linear')
c.key('right_arm', 14.0, arm(v(f=0.45, u=-0.9, r=-0.45), v(r=-0.8, f=-0.4, u=0.1)), ease='out')
c.key('right_arm', 18.0, arm(v(f=0.35, u=-0.55, r=-0.8), v(f=-0.6, r=-0.5, u=0.3)), ease='inout')
c.key('right_arm', 20.0, arm(v(f=1.0, u=-0.2, r=-0.3), v(r=-0.2, f=0.2, u=0.95)), ease='in')
c.key('right_arm', 22.0, arm(v(f=0.9, u=0.15, r=0.7), v(r=1.0, u=0.25, f=0.2)), ease='linear')
c.key('right_arm', 25.0, arm(v(f=0.3, u=0.2, r=1.0), v(r=0.5, f=-0.7, u=0.4)), ease='out')
c.key('right_arm', 34, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 7.0, shield_up(), ease='out')
c.key('left_arm', 12.0, free_arm(v(f=-0.3, u=-1.0, r=-0.3)), ease='inout')
c.key('left_arm', 18.0, shield_up(), ease='inout')
c.key('left_arm', 22.0, free_arm(v(f=-0.2, u=-1.0, r=-0.4)), ease='inout')
c.key('left_arm', 34, free_arm(D), ease='inout')
legs(c, [(0, 0, 0, 'inout'), (7.0, 10, -12, 'out'), (12.0, 20, -24, 'in'), (18, 16, -20, 'inout'),
         (22.0, 24, -26, 'in'), (26, 20, -22, 'out'), (34, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 7.0, pos=(0, 0.4, 0.8), ease='out')
c.key('root', 12.0, pos=(0, 1.2, -1.4), ease='in')
c.key('root', 18.0, pos=(0, 1.0, -1.2), ease='inout')
c.key('root', 22.0, pos=(0, 1.4, -2.2), ease='in')
c.key('root', 34, pos=(0, 0, 0), ease='inout')
soldier.append(c)

# ------------------------------------------------------------------ overhead: raised high, held a beat too long, then down
c = tp('overhead', 34, blend_in=2.0, blend_out=6.0).mark('glint', 6).mark('hold', 12).mark('hit', 19)
c.key('torso', 0)
c.key('torso', 9.0, qmul(qeuler(y=10), qeuler(x=-14)), ease='out')
c.key('torso', 16.0, qmul(qeuler(y=12), qeuler(x=-16)), ease='inout')
c.key('torso', 19.0, qeuler(x=26), ease='in3')
c.key('torso', 23.0, qeuler(x=30), ease='out')
c.key('torso', 34, qeuler(x=0), ease='inout')
c.key('head', 0)
c.key('head', 9.0, qeuler(x=-8), ease='out')
c.key('head', 19.0, qeuler(x=10), ease='in')
c.key('head', 34, qeuler(x=0), ease='inout')
c.mode('head', 'add')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 9.0, arm(v(f=-0.15, u=1.0, r=0.2), v(f=-1.0, u=-0.15)), ease='out')
c.key('right_arm', 16.0, arm(v(f=-0.2, u=1.0, r=0.22), v(f=-1.0, u=-0.25)), ease='inout')
c.key('right_arm', 18.0, arm(v(f=1.0, u=0.6, r=0.05), v(u=0.6, f=0.8)), ease='in3')
c.key('right_arm', 19.5, arm(v(f=1.0, u=-0.35, r=-0.05), v(u=-0.9, f=0.4)), ease='linear')
c.key('right_arm', 23.0, arm(v(f=0.7, u=-0.8, r=-0.1), v(f=0.8, u=-0.6)), ease='out')
c.key('right_arm', 34, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 9.0, free_arm(v(f=0.6, u=0.2, r=-0.6)), ease='out')
c.key('left_arm', 19.0, free_arm(v(f=-0.4, u=-1.0, r=-0.4)), ease='in')
c.key('left_arm', 34, free_arm(D), ease='inout')
legs(c, [(0, 0, 0, 'inout'), (9.0, 14, -16, 'out'), (16, 14, -16, 'inout'), (19.0, 24, -28, 'in'), (34, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 9.0, pos=(0, -0.4, 1.0), ease='out')
c.key('root', 16.0, pos=(0, -0.5, 1.1), ease='inout')
c.key('root', 19.0, pos=(0, 2.0, -1.8), ease='in3')
c.key('root', 34, pos=(0, 0, 0), ease='inout')
soldier.append(c)

# ------------------------------------------------------------------ thrust: drawn back to the hip, then a lunge
c = tp('thrust', 28, blend_in=2.0, blend_out=6.0).mark('lunge', 10).mark('hit', 12)
c.key('torso', 0)
c.key('torso', 8.0, qmul(qeuler(y=36), qeuler(x=-4)), ease='out')
c.key('torso', 11.0, qmul(qeuler(y=-14), qeuler(x=18)), ease='in3')
c.key('torso', 15.0, qmul(qeuler(y=-16), qeuler(x=16)), ease='out')
c.key('torso', 28, qeuler(y=0), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 8.0, arm(v(f=-0.9, u=-0.7, r=0.35), F), ease='out')
c.key('right_arm', 11.0, arm(v(f=0.55, u=-0.75, r=0.05), v(f=1.0, u=0.15)), ease='in3')
c.key('right_arm', 15.0, arm(v(f=0.6, u=-0.7, r=0.0), v(f=1.0, u=0.2)), ease='out')
c.key('right_arm', 28, rest_arm(), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 8.0, shield_up(), ease='out')
c.key('left_arm', 11.0, free_arm(v(f=-0.6, u=-1.0, r=-0.5)), ease='in')
c.key('left_arm', 28, free_arm(D), ease='inout')
legs(c, [(0, 0, 0, 'inout'), (8.0, 10, -8, 'out'), (11.0, 28, -34, 'in'), (16, 26, -30, 'out'), (28, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 8.0, pos=(0, 0.4, 1.2), ease='out')
c.key('root', 11.0, pos=(0, 2.0, -2.6), ease='in3')
c.key('root', 16.0, pos=(0, 1.8, -2.2), ease='out')
c.key('root', 28, pos=(0, 0, 0), ease='inout')
soldier.append(c)

# ------------------------------------------------------------------ bash: the shield driven into a guard
c = tp('bash', 20, blend_in=1.5, blend_out=5.0).mark('hit', 8)
c.key('torso', 0)
c.key('torso', 5.0, qmul(qeuler(y=-28), qeuler(x=-4)), ease='out')
c.key('torso', 8.0, qmul(qeuler(y=24), qeuler(x=12)), ease='in3')
c.key('torso', 11.0, qmul(qeuler(y=26), qeuler(x=10)), ease='out')
c.key('torso', 20, qeuler(y=0), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 5.0, free_arm(v(f=0.3, u=-0.4, r=-0.8)), ease='out')
c.key('left_arm', 8.0, free_arm(v(f=1.0, u=-0.05, r=0.15)), ease='in3')
c.key('left_arm', 11.0, free_arm(v(f=1.0, u=-0.1, r=0.1)), ease='out')
c.key('left_arm', 20, free_arm(D), ease='inout')
c.key('right_arm', 0, rest_arm())
c.key('right_arm', 5.0, arm(v(f=0.2, u=-0.9, r=0.5), v(f=1.0, u=0.3)), ease='out')
c.key('right_arm', 20, rest_arm(), ease='inout')
legs(c, [(0, 0, 0, 'inout'), (5.0, 6, -10, 'out'), (8.0, 26, -30, 'in'), (12, 22, -26, 'out'), (20, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 5.0, pos=(0, 0.3, 0.6), ease='out')
c.key('root', 8.0, pos=(0, 1.2, -2.6), ease='in3')
c.key('root', 20, pos=(0, 0, 0), ease='inout')
soldier.append(c)

# ================================================================== Halvard, the Fallen Warden

# His greatsword is part of his model: square to his fist, its blade along the arm's -z (FallenWardenModel).
GS = np.array([0.0, 0.0, -1.0])
R_PIVOT = np.array([-5.0, 2.0, 0.0])
L_PIVOT = np.array([5.0, 2.0, 0.0])

def garm(direction, blade):
    """The sword arm pointing along [direction] with the greatsword's blade as near [blade] as it goes."""
    return frame_to(np.array([0.0, 1.0, 0.0]), GS, direction, blade)

def grip(q_right, along=-3.0):
    """The left hand on the hilt, [along] pixels from the right hand (negative: towards the pommel)."""
    hand = R_PIVOT + qmat(q_right) @ np.array([0.0, 10.0, 0.0])
    target = hand + (qmat(q_right) @ GS) * along
    return free_arm(unit(target - L_PIVOT))

def both(direction, blade, along=-3.0):
    q = garm(direction, blade)
    return q, grip(q, along)

def wkey(c, t, direction, blade, ease='inout', left=None, along=-3.0):
    """A two-handed key; [left] overrides the left arm (one-handed moments)."""
    q, l = both(direction, blade, along)
    c.key('right_arm', t, q, ease=ease)
    c.key('left_arm', t, l if left is None else left, ease=ease)

# His fighting stance: the sword in both hands, angled up and forward
GUARD = (v(f=0.85, u=-0.75, r=0.05), v(f=1.0, u=0.75, r=-0.1))
# At rest (as the model holds it when no clip plays): low and forward
REST = (v(f=0.25, u=-1.0), v(f=1.0, u=-0.7))

def kneel(c, t, ease='inout', bow=28, slump=10):
    """On one knee with the sword planted point-down before him, both hands on the pommel."""
    c.key('root', t, pos=(0, 6.0, 0.5), ease=ease)
    c.key('torso', t, qeuler(x=slump), ease=ease)
    c.key('head', t, qeuler(x=bow), ease=ease)
    c.key('left_leg', t, qeuler(x=-55, z=-4), ease=ease)
    c.key('right_leg', t, qeuler(x=62, z=4), ease=ease)
    q = garm(v(f=1.0, u=-0.12, r=-0.35), D)
    c.key('right_arm', t, q, ease=ease)
    c.key('left_arm', t, grip(q, -2.0), ease=ease)

def reel(c, t0, t1):
    """From where he stands at [t0], rocked back on his heels by [t1]: the blow that drops him to a knee."""
    c.key('torso', t0); c.key('head', t0); c.key('root', t0)
    c.key('right_leg', t0, qeuler(x=0)); c.key('left_leg', t0, qeuler(x=0))
    c.key('right_arm', t0, garm(*REST)); c.key('left_arm', t0, free_arm(D))
    c.key('torso', t1, qeuler(x=-13), ease='out')
    c.key('head', t1, qeuler(x=-17), ease='out')
    c.key('root', t1, pos=(0, 1.0, 1.5), ease='out')
    c.key('right_leg', t1, qeuler(x=12, z=3), ease='out')
    c.key('left_leg', t1, qeuler(x=-16, z=-3), ease='out')
    c.key('right_arm', t1, garm(v(f=0.15, u=-1.0, r=0.25), v(f=0.7, u=-0.75, r=0.1)), ease='out')
    c.key('left_arm', t1, free_arm(v(f=0.25, u=-0.75, r=-0.6)), ease='out')

def stand(c, t, ease='inout'):
    c.key('root', t, pos=(0, 0, 0), ease=ease)
    c.key('left_leg', t, qeuler(x=0), ease=ease)
    c.key('right_leg', t, qeuler(x=0), ease=ease)

# ------------------------------------------------------------------ dormant and death: held poses
c = tp('dormant', 10, blend_in=0.0, blend_out=0.0)
kneel(c, 0)
c.mode('head', 'set')
warden.append(c)

c = tp('death', 30, blend_in=4.0, blend_out=0.0).mark('down', 10)
reel(c, 0, 4.0)
kneel(c, 12, ease='in', bow=20, slump=8)
kneel(c, 30, ease='inout', bow=42, slump=22)
c.mode('head', 'set')
warden.append(c)

# ------------------------------------------------------------------ rise: his introduction (200 ticks)
c = tp('rise', 200, blend_in=0.0, blend_out=10.0).mark('looks up', 50).mark('stands', 125).mark('sword free', 140).mark('roar', 170)
kneel(c, 0)
kneel(c, 40)
# He lifts his head to the traveller, and speaks
c.key('head', 58, qeuler(x=-6), ease='inout')
c.key('head', 75, qeuler(x=-2, y=4), ease='inout')
c.key('head', 92, qeuler(x=-8, y=-3), ease='inout')
c.key('head', 110, qeuler(x=-4), ease='inout')
# He rises, his weight on the sword
c.key('torso', 110, qeuler(x=10), ease='inout')
c.key('torso', 124, qeuler(x=16), ease='inout')
c.key('root', 110, pos=(0, 6.0, 0.5), ease='inout')
c.key('root', 124, pos=(0, 3.0, 0.0), ease='inout')
c.key('left_leg', 110, qeuler(x=-55, z=-4), ease='inout')
c.key('right_leg', 110, qeuler(x=62, z=4), ease='inout')
c.key('left_leg', 124, qeuler(x=-30, z=-3), ease='inout')
c.key('right_leg', 124, qeuler(x=30, z=3), ease='inout')
stand(c, 136)
c.key('torso', 136, qeuler(x=4), ease='inout')
q = garm(v(f=1.0, u=-0.12, r=-0.35), D)
c.key('right_arm', 110, q)
c.key('left_arm', 110, grip(q, -2.0))
q = garm(v(f=1.0, u=-0.35, r=-0.3), D)
c.key('right_arm', 136, q, ease='inout')
c.key('left_arm', 136, grip(q, -2.0), ease='inout')
# He wrenches the sword free and swings it up beside him
c.key('right_arm', 141, garm(v(f=0.4, u=0.2, r=0.9), v(u=1.0, f=0.2)), ease='in3')
c.key('left_arm', 141, free_arm(v(f=0.3, u=-1.0, r=-0.3)), ease='out')
c.key('torso', 141, qmul(qeuler(y=22), qeuler(x=-4)), ease='in')
wkey(c, 158, *GUARD)
c.key('torso', 158, qeuler(y=0), ease='inout')
# The roar
c.key('torso', 166, qeuler(x=6), ease='inout')
c.key('torso', 172, qeuler(x=-16), ease='out3')
c.key('torso', 184, qeuler(x=-12), ease='inout')
c.key('head', 158, qeuler(x=0), ease='inout')
c.key('head', 166, qeuler(x=10), ease='inout')
c.key('head', 172, qeuler(x=-34), ease='out3')
c.key('head', 184, qeuler(x=-28), ease='inout')
c.key('right_arm', 166, garm(v(f=0.7, u=-0.9, r=0.1), v(f=1.0, u=0.4)), ease='inout')
c.key('left_arm', 166, free_arm(v(f=0.3, u=-1.0, r=-0.2)), ease='inout')
c.key('right_arm', 172, garm(v(f=0.1, u=-0.2, r=1.0), v(u=-0.7, f=0.35)), ease='out3')
c.key('left_arm', 172, free_arm(v(f=0.1, u=-0.2, r=-1.0)), ease='out3')
c.key('right_arm', 184, garm(v(f=0.15, u=-0.3, r=1.0), v(u=-0.75, f=0.3)), ease='inout')
c.key('left_arm', 184, free_arm(v(f=0.1, u=-0.3, r=-1.0)), ease='inout')
legs(c, [(166, 0, 0, 'inout'), (172, 10, -12, 'out'), (184, 10, -12, 'inout'), (200, 0, 0, 'inout')])
wkey(c, 196, *GUARD)
c.key('torso', 196, qeuler(x=0), ease='inout')
c.key('head', 196, qeuler(x=0), ease='inout')
c.key('right_arm', 200, garm(*REST), ease='inout')
c.key('left_arm', 200, free_arm(D), ease='inout')
c.mode('head', 'set')
warden.append(c)

# ------------------------------------------------------------------ sweep: drawn far back to his right, then all the way across
c = tp('sweep', 32, blend_in=2.0, blend_out=6.0).mark('hit', 14)
c.key('torso', 0)
c.key('torso', 9.0, qmul(qeuler(y=48), qeuler(x=4)), ease='out')
c.key('torso', 11.0, qmul(qeuler(y=50), qeuler(x=5)), ease='inout')
c.key('torso', 13.0, qmul(qeuler(y=12), qeuler(x=8)), ease='in')
c.key('torso', 15.0, qmul(qeuler(y=-34), qeuler(x=10)), ease='linear')
c.key('torso', 19.0, qmul(qeuler(y=-50), qeuler(x=8)), ease='out')
c.key('torso', 32, qeuler(y=0), ease='inout')
wkey(c, 0, *REST, left=free_arm(D))
wkey(c, 9.0, v(f=-0.3, u=-0.55, r=0.8), v(f=-0.9, r=0.4, u=0.1), ease='out')
wkey(c, 11.0, v(f=-0.35, u=-0.5, r=0.8), v(f=-0.9, r=0.45, u=0.12))
wkey(c, 13.0, v(f=0.8, u=-0.45, r=0.4), v(r=1.0, f=0.4), ease='in')
wkey(c, 15.0, v(f=0.85, u=-0.5, r=-0.4), v(f=0.4, r=-1.0, u=0.05), ease='linear')
wkey(c, 19.0, v(f=0.1, u=-0.6, r=-0.9), v(f=-0.6, r=-0.8, u=-0.1), ease='out')
wkey(c, 32, *REST, left=free_arm(D))
legs(c, [(0, 0, 0, 'inout'), (9.0, 14, -10, 'out'), (14.0, 22, -26, 'in'), (19, 20, -24, 'out'), (32, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 9.0, pos=(0, 1.0, 0.6), ease='out')
c.key('root', 14.0, pos=(0, 1.6, -1.6), ease='in')
c.key('root', 32, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ overhead: high above his head, then down into the ground
c = tp('overhead', 40, blend_in=2.0, blend_out=7.0).mark('glint', 8).mark('hit', 20).mark('pull free', 30)
c.key('torso', 0)
c.key('torso', 13.0, qmul(qeuler(y=8), qeuler(x=-16)), ease='out')
c.key('torso', 17.0, qmul(qeuler(y=8), qeuler(x=-18)), ease='inout')
c.key('torso', 20.0, qeuler(x=34), ease='in3')
c.key('torso', 29.0, qeuler(x=32), ease='out')
c.key('torso', 40, qeuler(x=0), ease='inout')
c.key('head', 0)
c.key('head', 13.0, qeuler(x=-10), ease='out')
c.key('head', 20.0, qeuler(x=-14), ease='in')
c.key('head', 40, qeuler(x=0), ease='inout')
c.mode('head', 'add')
wkey(c, 0, *REST, left=free_arm(D))
wkey(c, 13.0, v(f=-0.1, u=1.0, r=0.15), v(f=-1.0, u=-0.25), ease='out')
wkey(c, 17.0, v(f=-0.15, u=1.0, r=0.15), v(f=-1.0, u=-0.35))
wkey(c, 19.0, v(f=1.0, u=0.55), v(u=0.75, f=0.6), ease='in3')
wkey(c, 20.0, v(f=1.0, u=-0.2), v(u=-0.95, f=0.35), ease='linear')
wkey(c, 29.0, v(f=1.0, u=-0.3), v(u=-0.95, f=0.3), ease='out')
wkey(c, 34.0, v(f=0.8, u=-0.6), v(f=1.0, u=0.2), ease='in')
wkey(c, 40, *REST, left=free_arm(D))
legs(c, [(0, 0, 0, 'inout'), (13.0, 16, -16, 'out'), (17, 16, -16, 'inout'), (20.0, 30, -34, 'in'), (30, 28, -32, 'out'), (40, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 13.0, pos=(0, -0.6, 1.2), ease='out')
c.key('root', 17.0, pos=(0, -0.8, 1.3), ease='inout')
c.key('root', 20.0, pos=(0, 3.0, -2.0), ease='in3')
c.key('root', 30.0, pos=(0, 2.8, -1.8), ease='out')
c.key('root', 40, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ combo: a cut, a rising backhand, then a lunging chop
c = tp('combo', 52, blend_in=2.0, blend_out=8.0).mark('hit', 12).mark('hit 2', 22).mark('lunge', 32).mark('hit 3', 34)
c.key('torso', 0)
c.key('torso', 8.0, qmul(qeuler(y=40), qeuler(x=-8)), ease='out')
c.key('torso', 10.5, qmul(qeuler(y=10), qeuler(x=6)), ease='in')
c.key('torso', 13.0, qmul(qeuler(y=-34), qeuler(x=14)), ease='linear')
c.key('torso', 18.0, qmul(qeuler(y=-42), qeuler(x=10)), ease='out')
c.key('torso', 20.5, qmul(qeuler(y=-8), qeuler(x=8)), ease='in')
c.key('torso', 23.0, qmul(qeuler(y=34), qeuler(x=0)), ease='linear')
c.key('torso', 26.0, qmul(qeuler(y=30), qeuler(x=-6)), ease='out')
c.key('torso', 31.0, qmul(qeuler(y=10), qeuler(x=-18)), ease='inout')
c.key('torso', 34.0, qeuler(x=36), ease='in3')
c.key('torso', 42.0, qeuler(x=30), ease='out')
c.key('torso', 52, qeuler(x=0), ease='inout')
wkey(c, 0, *REST, left=free_arm(D))
wkey(c, 8.0, v(f=0.1, u=0.95, r=0.6), v(f=-1.0, r=0.3, u=0.1), ease='out')
wkey(c, 10.5, v(f=1.0, u=0.5, r=0.3), v(u=0.6, r=-0.6, f=0.4), ease='in')
wkey(c, 13.0, v(f=1.0, u=-0.4, r=-0.5), v(r=-1.0, u=-0.3, f=0.3), ease='linear')
wkey(c, 18.0, v(f=0.4, u=-0.75, r=-0.7), v(f=-0.4, r=-0.8, u=-0.2), ease='out')
wkey(c, 20.5, v(f=1.0, u=-0.3, r=-0.3), v(r=-0.2, f=0.3, u=0.95), ease='in')
wkey(c, 23.0, v(f=0.9, u=0.2, r=0.7), v(r=1.0, u=0.3, f=0.2), ease='linear')
wkey(c, 26.0, v(f=0.2, u=0.7, r=0.8), v(r=0.4, f=-0.7, u=0.5), ease='out')
wkey(c, 31.0, v(f=-0.15, u=1.0, r=0.15), v(f=-1.0, u=-0.3), ease='inout')
wkey(c, 34.0, v(f=1.0, u=-0.05), v(f=1.0, u=-0.45), ease='in3')
wkey(c, 42.0, v(f=1.0, u=-0.3), v(f=0.7, u=-0.8), ease='out')
wkey(c, 52, *REST, left=free_arm(D))
legs(c, [(0, 0, 0, 'inout'), (8.0, 10, -12, 'out'), (13.0, 20, -24, 'in'), (18, 16, -20, 'out'),
         (23.0, 22, -24, 'in'), (31, 8, -10, 'inout'), (34.0, 34, -38, 'in'), (44, 30, -34, 'out'), (52, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 8.0, pos=(0, 0.4, 0.8), ease='out')
c.key('root', 13.0, pos=(0, 1.2, -1.2), ease='in')
c.key('root', 23.0, pos=(0, 1.4, -1.8), ease='in')
c.key('root', 31.0, pos=(0, -0.4, 0.6), ease='inout')
c.key('root', 34.0, pos=(0, 3.4, -3.0), ease='in3')
c.key('root', 44.0, pos=(0, 3.0, -2.6), ease='out')
c.key('root', 52, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ charge: shoulder down, sword trailing, straight at you
c = tp('charge', 40, blend_in=2.0, blend_out=8.0).mark('go', 13).mark('stop', 24)
c.key('torso', 0)
c.key('torso', 10.0, qmul(qeuler(y=-24), qeuler(x=26)), ease='out')
c.key('torso', 13.0, qmul(qeuler(y=-26), qeuler(x=32)), ease='in')
c.key('torso', 24.0, qmul(qeuler(y=-24), qeuler(x=30)), ease='linear')
c.key('torso', 28.0, qmul(qeuler(y=-6), qeuler(x=-6)), ease='out')
c.key('torso', 40, qeuler(x=0), ease='inout')
c.key('head', 0)
c.key('head', 10.0, qeuler(x=-26), ease='out')
c.key('head', 28.0, qeuler(x=0), ease='inout')
c.mode('head', 'add')
c.key('right_arm', 0, garm(*REST))
# (Leaning in as he is, an arm hanging a little forward of his chest hangs straight down, and the blade trails level)
c.key('right_arm', 10.0, garm(v(f=0.5, u=-0.87, r=0.25), v(f=-0.87, u=-0.5, r=0.1)), ease='out')
c.key('right_arm', 24.0, garm(v(f=0.5, u=-0.87, r=0.25), v(f=-0.87, u=-0.45, r=0.1)), ease='linear')
c.key('right_arm', 28.0, garm(v(f=0.5, u=-0.85, r=0.2), v(f=1.0, u=0.1)), ease='out')
c.key('right_arm', 40, garm(*REST), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 10.0, free_arm(v(f=0.8, u=-0.6, r=0.4)), ease='out')
c.key('left_arm', 24.0, free_arm(v(f=0.8, u=-0.6, r=0.4)), ease='linear')
c.key('left_arm', 40, free_arm(D), ease='inout')
run = [(0, 0, 0, 'inout'), (10.0, 18, -20, 'out')]
for i, t in enumerate(range(13, 25, 2)):
    a = 34 if i % 2 == 0 else -34
    run.append((t, a, -a, 'inout'))
run += [(26, 20, -28, 'out'), (30, 14, -18, 'inout'), (40, 0, 0, 'inout')]
legs(c, run)
c.key('root', 0)
c.key('root', 10.0, pos=(0, 2.0, 0.4), ease='out')
c.key('root', 24.0, pos=(0, 2.0, -0.6), ease='linear')
c.key('root', 27.0, pos=(0, 1.6, 0.8), ease='out')
c.key('root', 40, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ stomp: for whoever crowds him or slips behind
c = tp('stomp', 24, blend_in=1.5, blend_out=6.0).mark('hit', 9)
c.key('torso', 0)
c.key('torso', 6.0, qeuler(x=-10), ease='out')
c.key('torso', 9.0, qeuler(x=14), ease='in3')
c.key('torso', 24, qeuler(x=0), ease='inout')
c.key('right_arm', 0, garm(*REST))
c.key('right_arm', 6.0, garm(v(f=0.2, u=-0.4, r=0.95), v(f=1.0, u=0.3)), ease='out')
c.key('right_arm', 10.0, garm(v(f=0.4, u=-0.85, r=0.4), v(f=1.0, u=-0.2)), ease='in')
c.key('right_arm', 24, garm(*REST), ease='inout')
c.key('left_arm', 0, free_arm(D))
c.key('left_arm', 6.0, free_arm(v(f=0.2, u=-0.4, r=-0.95)), ease='out')
c.key('left_arm', 10.0, free_arm(v(f=0.3, u=-0.85, r=-0.4)), ease='in')
c.key('left_arm', 24, free_arm(D), ease='inout')
c.key('right_leg', 0)
c.key('right_leg', 6.0, qeuler(x=-58, z=6), ease='out')
c.key('right_leg', 8.5, qeuler(x=-6, z=4), ease='in3')
c.key('right_leg', 14.0, qeuler(x=0), ease='out')
c.key('left_leg', 0)
c.key('left_leg', 6.0, qeuler(x=6), ease='out')
c.key('left_leg', 9.0, qeuler(x=0), ease='in')
c.key('root', 0)
c.key('root', 6.0, pos=(0, -1.0, 0.6), ease='out')
c.key('root', 9.0, pos=(0, 2.0, -0.6), ease='in3')
c.key('root', 13.0, pos=(0, 1.6, -0.4), ease='out')
c.key('root', 24, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ flame slam: a leap, and down sword-first into the fire
c = tp('flame_slam', 50, blend_in=2.0, blend_out=8.0).mark('leap', 10).mark('hit', 24).mark('ring', 32)
c.key('torso', 0)
c.key('torso', 8.0, qeuler(x=18), ease='out')
c.key('torso', 12.0, qeuler(x=-14), ease='out')
c.key('torso', 21.0, qeuler(x=-18), ease='inout')
c.key('torso', 24.0, qeuler(x=36), ease='in3')
c.key('torso', 34.0, qeuler(x=30), ease='out')
c.key('torso', 50, qeuler(x=0), ease='inout')
wkey(c, 0, *REST, left=free_arm(D))
wkey(c, 8.0, v(f=0.6, u=-0.8, r=0.2), v(f=0.4, u=0.9), ease='out')
wkey(c, 13.0, v(f=-0.1, u=1.0, r=0.1), v(f=-1.0, u=-0.25), ease='out')
wkey(c, 21.0, v(f=-0.2, u=1.0, r=0.1), v(f=-1.0, u=-0.35))
wkey(c, 23.0, v(f=1.0, u=0.6), v(u=0.7, f=0.7), ease='in3')
wkey(c, 24.0, v(f=1.0, u=-0.25), D, ease='linear')
wkey(c, 34.0, v(f=1.0, u=-0.3), D, ease='out')
wkey(c, 50, *REST, left=free_arm(D))
c.key('right_leg', 0)
c.key('left_leg', 0)
c.key('right_leg', 8.0, qeuler(x=24, z=6), ease='out')
c.key('left_leg', 8.0, qeuler(x=-24, z=-6), ease='out')
c.key('right_leg', 13.0, qeuler(x=-40, z=6), ease='out')
c.key('left_leg', 13.0, qeuler(x=-56, z=-6), ease='out')
c.key('right_leg', 22.0, qeuler(x=-20, z=4), ease='inout')
c.key('left_leg', 22.0, qeuler(x=-30, z=-4), ease='inout')
c.key('right_leg', 24.0, qeuler(x=62, z=4), ease='in3')
c.key('left_leg', 24.0, qeuler(x=-55, z=-4), ease='in3')
c.key('right_leg', 34.0, qeuler(x=59, z=4), ease='out')
c.key('left_leg', 34.0, qeuler(x=-52, z=-4), ease='out')
c.key('right_leg', 50, qeuler(x=0), ease='inout')
c.key('left_leg', 50, qeuler(x=0), ease='inout')
c.key('root', 0)
c.key('root', 8.0, pos=(0, 3.0, 0.4), ease='out')
c.key('root', 13.0, pos=(0, -1.0, 0.0), ease='out')
c.key('root', 22.0, pos=(0, -0.5, -0.4), ease='inout')
c.key('root', 24.0, pos=(0, 6.0, -1.0), ease='in3')
c.key('root', 34.0, pos=(0, 5.6, -0.8), ease='out')
c.key('root', 50, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ flame wave: dragged low behind him, then up and across
c = tp('flame_wave', 40, blend_in=2.0, blend_out=8.0).mark('hit', 16).mark('wave', 18)
c.key('torso', 0)
c.key('torso', 11.0, qmul(qeuler(y=46), qeuler(x=14)), ease='out')
c.key('torso', 13.0, qmul(qeuler(y=48), qeuler(x=16)), ease='inout')
c.key('torso', 15.0, qmul(qeuler(y=8), qeuler(x=8)), ease='in')
c.key('torso', 17.0, qmul(qeuler(y=-36), qeuler(x=-8)), ease='linear')
c.key('torso', 22.0, qmul(qeuler(y=-44), qeuler(x=-10)), ease='out')
c.key('torso', 40, qeuler(y=0), ease='inout')
wkey(c, 0, *REST, left=free_arm(D))
wkey(c, 11.0, v(f=-0.4, u=-0.8, r=0.5), v(f=-0.8, u=-0.55, r=0.2), ease='out')
wkey(c, 13.0, v(f=-0.45, u=-0.8, r=0.5), v(f=-0.8, u=-0.6, r=0.2))
wkey(c, 15.0, v(f=0.9, u=-0.6, r=0.35), v(r=0.6, f=0.6, u=-0.6), ease='in')
wkey(c, 17.0, v(f=0.9, u=0.3, r=-0.5), v(r=-0.8, u=0.6, f=0.3), ease='linear')
wkey(c, 22.0, v(f=0.3, u=0.85, r=-0.6), v(r=-0.5, u=0.7, f=-0.5), ease='out')
wkey(c, 40, *REST, left=free_arm(D))
legs(c, [(0, 0, 0, 'inout'), (11.0, 16, -12, 'out'), (16.0, 24, -28, 'in'), (22, 20, -26, 'out'), (40, 0, 0, 'inout')])
c.key('root', 0)
c.key('root', 11.0, pos=(0, 2.0, 0.6), ease='out')
c.key('root', 16.0, pos=(0, 1.6, -1.6), ease='in')
c.key('root', 22.0, pos=(0, 0.4, -1.4), ease='out')
c.key('root', 40, pos=(0, 0, 0), ease='inout')
warden.append(c)

# ------------------------------------------------------------------ kneel: the crown's fire takes him, then he throws everyone back
c = tp('kneel', 80, blend_in=2.0, blend_out=8.0).mark('down', 12).mark('fire', 45).mark('shockwave', 60)
reel(c, 0, 4.0)
kneel(c, 12, ease='in', bow=24)
for i, t in enumerate(range(16, 45, 4)):
    # Shaking as the fire takes hold
    s = 1 if i % 2 == 0 else -1
    c.key('torso', t, qmul(qeuler(x=12 + 2 * s), qeuler(z=2.5 * s)), ease='inout')
c.key('head', 44, qeuler(x=30), ease='inout')
c.key('head', 50, qeuler(x=-22), ease='out3')
c.key('torso', 50, qeuler(x=-4), ease='out3')
kneel_arm = garm(v(f=1.0, u=-0.12, r=-0.35), D)
c.key('right_arm', 50, kneel_arm, ease='inout')
c.key('left_arm', 50, grip(kneel_arm, -2.0), ease='inout')
c.key('root', 50, pos=(0, 6.0, 0.5), ease='inout')
c.key('left_leg', 50, qeuler(x=-55, z=-4), ease='inout')
c.key('right_leg', 50, qeuler(x=62, z=4), ease='inout')
c.key('root', 57, pos=(0, 6.4, 0.6), ease='in')
# Up in one surge, arms flung wide
c.key('root', 60, pos=(0, -0.6, 0.0), ease='out3')
c.key('left_leg', 60, qeuler(x=-6, z=-10), ease='out3')
c.key('right_leg', 60, qeuler(x=6, z=10), ease='out3')
c.key('torso', 60, qeuler(x=-18), ease='out3')
c.key('head', 60, qeuler(x=-30), ease='out3')
c.key('right_arm', 60, garm(v(f=0.1, u=-0.1, r=1.0), v(u=-0.7, f=0.35)), ease='out3')
c.key('left_arm', 60, free_arm(v(f=0.1, u=-0.1, r=-1.0)), ease='out3')
c.key('root', 66, pos=(0, 0.6, 0.2), ease='inout')
stand(c, 74)
c.key('torso', 72, qeuler(x=0), ease='inout')
c.key('head', 72, qeuler(x=0), ease='inout')
wkey(c, 74, *GUARD)
c.key('right_arm', 80, garm(*REST), ease='inout')
c.key('left_arm', 80, free_arm(D), ease='inout')
c.mode('head', 'set')
warden.append(c)

# ================================================================== previews

def euler_x(q):
    """A part's xRot (radians) once the animator writes [q] back as Euler angles (JOML getEulerAnglesZYX)."""
    x, y, z, w = q
    return math.atan2(y * z + w * x, 0.5 - x * x - y * y)

class MeshScene(Scene):
    """A creature's own model (read from its LayerDefinition in EnemyModels.kt) and texture, posed by the clip.
    Children turn as the model's setupAnim turns them; [sword] adds the held item, as the soldier carries one."""
    PART = re.compile(r'(?:val (\w+) = )?(\w+)\.addOrReplaceChild\("(\w+)", CubeListBuilder\.create\(\)(.*?), PartPose\.(offset|offsetAndRotation)\(([^)]*)\)\)')
    CUBE = re.compile(r'\.texOffs\((\d+), (\d+)\)\.addBox\(([-\d.f, ]+?)(?:, CubeDeformation\(([-\d.]+)f\))?\)')

    def __init__(self, kotlin, model_class, texture, sword=None, ground=True):
        super().__init__(texture, sword if sword is not None else Image.new('RGBA', (16, 16)))
        self.held = sword is not None
        src = open(kotlin).read()
        src = src[src.index('class ' + model_class):]
        src = src[:src.index('LayerDefinition.create')]
        self.parts = {}     # name -> (parent, pivot, rot degrees, cubes)
        alias = {'root': None}
        for m in self.PART.finditer(src):
            var, parent, name, cubes, kind, args = m.groups()
            nums = [float(a.strip().rstrip('f')) for a in args.split(',')]
            pivot, rot = nums[:3], [math.degrees(r) for r in nums[3:6]] if kind == 'offsetAndRotation' else [0.0, 0.0, 0.0]
            boxes = []
            for c in self.CUBE.finditer(cubes):
                u, v_, box, g = c.groups()
                b = [float(a.strip().rstrip('f')) for a in box.split(',')]
                boxes.append((tuple(b[:3]), tuple(b[3:6]), (int(u), int(v_)), float(g) if g else 0.0))
            self.parts[name] = (alias.get(parent, parent), pivot, rot, boxes)
            if var:
                alias[var] = name
        self.ground = ground
        self.weight = 1.0
        self.floor = np.zeros((4, 4, 4), float); self.floor[...] = (74, 80, 96, 255)

    def child_rot(self, name, rot, pose):
        """The extra turns setupAnim gives the creature's loose parts."""
        if name == 'cloak':
            return (math.degrees(max(0.105, -euler_x(pose['body'][0]) + 0.06)), 0, 0)
        if name == 'greatsword':
            return (math.degrees(-0.97 + (-1.571 + 0.97) * self.weight), 0, 0)
        if name == 'tabard':
            legs = max(abs(euler_x(pose['right_leg'][0])), abs(euler_x(pose['left_leg'][0])))
            return (math.degrees(-euler_x(pose['body'][0]) * 0.8 - legs * 0.5), 0, 0)
        return rot

    def matrix(self, name, pose):
        parent, pivot, rot, _ = self.parts[name]
        if parent is None:
            qr, pr = pose['root']
            q, p = pose[name]
            return T(*pr) @ Rq(qr) @ T(*p) @ Rq(q)
        return self.matrix(parent, pose) @ T(*pivot) @ Rq(qeuler(*self.child_rot(name, rot, pose)))

    def player_world(self, pose, sword=True):
        quads = []
        for name, (_, _, _, boxes) in self.parts.items():
            M = self.matrix(name, pose)
            for origin, size, uv, g in boxes:
                for A, Bq, Dq, rect, face in box_quads(origin, size, uv, g):
                    quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), rect, FACE_LIGHT[face], self.skin))
        if self.held and sword:
            q, p = pose['right_arm']
            qr, pr = pose['root']
            M = T(*pr) @ Rq(qr) @ T(*p) @ Rq(q) @ S(16) @ third_person_item()
            for A, Bq, Dq, rect, face in sprite_quads():
                quads.append((apply(M, A), apply(M, Bq), apply(M, Dq), rect, 0.95, self.sword))
        if self.ground:
            P = lambda x, z: np.array([x, 24.0, z])
            quads.append((P(-22, -22), P(22, -22), P(-22, 22), (0, 0, 4, 4), 1.0, self.floor))
        return quads

    def sword_points(self, pose):
        if 'greatsword' in self.parts:
            M = self.matrix('greatsword', pose)
            return apply(M, (-1, -1, 0)), apply(M, (-1, 26, 0))
        return super().sword_points(pose)

def poses(scene, clip, times, views=((35, -14), (90, 0), (0, 90)), scale=5.0, cell=(230, 250)):
    """Larger stills of [clip] at [times]: three-quarter, side and top by default, with the ground at the feet."""
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 18), (30, 32, 40))
    d = ImageDraw.Draw(out)
    d.text((4, 3), clip.name, fill=(235, 235, 235))
    for i, t in enumerate(times):
        pose = pose_clip(clip, t)
        scene.weight = clip.weight(t)
        quads = scene.player_world(pose)
        scene.weight = 1.0
        for j, (yaw, pitch) in enumerate(views):
            project = ortho(yaw, pitch, scale, (W, H), 6.0 if pitch < 45 else 0.0)
            img = raster(quads, project, (W, H))
            if abs(pitch) < 1:
                # Side and front views: the ground the feet stand on
                y = project((0, 24, 0))[1]
                ImageDraw.Draw(img).line([(0, y), (W, y)], fill=(120, 170, 120), width=1)
            out.paste(img, (i * W, 18 + j * H))
        d.text((i * W + 4, 21), f't={t:g}', fill=(255, 255, 255))
    return out

if __name__ == '__main__':
    from PIL import Image
    assets, previews, skin, sword = sys.argv[1:5]
    os.makedirs(previews, exist_ok=True)
    for c in soldier:
        print(c.write(assets, 'hollow_soldier'))
    for c in warden:
        print(c.write(assets, 'fallen_warden'))
    only = sys.argv[5:]
    models = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../src/main/kotlin/io/github/vaspyyy/guildmark/client/model/EnemyModels.kt')
    textures = os.path.join(assets, 'textures/entity')
    plain = MeshScene(models, 'HollowSoldierModel', Image.open(os.path.join(textures, 'hollow_soldier.png')), Image.open(sword), ground=False)
    big = MeshScene(models, 'FallenWardenModel', Image.open(os.path.join(textures, 'fallen_warden.png')), ground=False)
    for group, clips, scene in (('hollow_soldier', soldier, plain), ('fallen_warden', warden, big)):
        for c in clips:
            if only and c.name not in only and f'{group}/{c.name}' not in only:
                continue
            n = 13 if c.length > 60 else 9
            times = [c.length * i / (n - 1) for i in range(n)]
            sheet(scene, c, times=times, title=f'{group}/{c.name}').save(os.path.join(previews, f'{group}_{c.name}.png'))
