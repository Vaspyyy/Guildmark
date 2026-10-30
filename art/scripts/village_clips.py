"""
The villages' moves (living-world/villages/design.md, 17.1), on the Living World's kit:

    BOLT_DOOR  50 once   shutting a door against the dark: a step in, the left hand on the frame, a shove that slams the door
                         (the task closes it at tick 10), a fumble for the bolt, the bolt shot home (tick 30), a tug to try
                         it, the long breath out, a step back and a look round the room
    LAY_CANDLE 60 once   a candle carried cupped to a memorial, the right knee down, the candle set on the ledge (24), the
                         hand cupped round its flame and drawn off as it stands (34), the head bowed, up again with a hand to
                         the chest
    CRADLE     60 loop   a baby held in the crook of the left arm and rocked: the body sways, the head follows the bundle, a
                         coo (16, 46) and the free hand tucks the cloth with a nod after it (20, 50)
    READ_SCROLL 180 loop the crier's news read over from the scroll before the cry, a look about the square between whiles
    RING_ALARM 10 loop   the great bell struck by hand, hard and quick, a stroke on each strike (6): the alarm (and its
                         ring_alarm_high, at a bell hung a block up)
and, for RING_BELL at a great bell (Townsperson.greatBell), ring_great_bell and ring_great_bell_high: a stroke a loop (6 of
30), struck low (a bell in its frame on the ground) or at the height of the shoulder (one hung a block up).

Their names, lengths and loop flags are TownRole.kt's (Activity); living_anims.py imports this module at its end and calls
build(), which makes the clips, so its verify() and writer take them like the rest. `python3 -B village_clips.py <out dir>`
draws the GIF previews (2x2 grids) once living_anims has built everything.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import townsfolk_anims as ta
from townsfolk_anims import (qeuler, qmat, qmul, unit, F, U, D, R, L, B, F_, bend, key_frame, check, seal, smooth, bump, GRIP,
                             PIVOTS, torso_to_world, sink_for, head_at)
from works_clips import rest_arm, mix, lifted, set_arm, reach_arm, stand, joins_idle, stepping, FEET, grip_of, tools_when
import tale_clips as tcl
import works_clips as wc
import living_props as lp

la = None           # living_anims, handed to build()


def K(keys, t, loop=None):
    return la.K(keys, t, loop)


def KV(keys, t, loop=None):
    return la.KV(keys, t, loop)


def clear_hands(c, times, tol=0.8, into=('body', 'head')):
    """Both hands kept out of the body and the head all through [times] (to within [tol] px)."""
    for side in ('right_arm', 'left_arm'):
        tcl.clear_of(c, side, list(times), into=into, tol=tol)


def keep_out(c, times, boxes, label, parts=('head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'), tol=0.6):
    """Every one of [parts] (an arm: its hand) kept out of the solid [boxes] ((lo, hi, ...) in the clip's space)."""
    for part in parts:
        worst, at = max((tcl.deepest_in(c, t, part, boxes), t) for t in times)
        check(c, f'{part.replace("_", " ")} clear of the {label} (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


def below_ground(c, times, tol=0.6):
    ground = [((-60.0, 24.0, -60.0), (60.0, 40.0, 60.0))]
    for part, label in (('right_arm', 'right hand'), ('left_arm', 'left hand')):
        worst, at = max((tcl.deepest_in(c, t, part, ground), t) for t in times)
        check(c, f'{label} above the ground (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


# ================================================================== bolt_door

# ---- bolt_door (50, once): shutting a house's door against the dark. The door's inner face is BD_Z ahead (half a block:
# they stand in the block before it, the leaf flush with that block's edge); the task swings it shut at BD_SLAM and the
# bolt is home at BD_BOLT_T. A start (the shoulders up, the head up, looking out), both hands flung onto the door at head
# height and the weight thrown in behind them, the right foot back to push (BD_SLAM: it slams); a jolt and a lean on it,
# the head turned to listen, the breath short; the right hand off the door and fumbling for the bolt (once too far, once
# too low), the knob found, dragged stiffly and then freely home into the keeper, the wrist turning as it seats
# (BD_BOLT_T); a tug to try it, and it holds; the breath let out, the head down, the hands sliding off the door to the
# chest; a step back and a look round the room, and on to idle. The arms are rigid: a hand on the door has its shoulder an
# arm's length off it, so the body stands where the hands put it (bd_rz).
BD_LEN = 50
BD_Z = -8.0                                     # the door's inner face (and the wall's)
BD_SLAM = 10.0
BD_BOLT_T = 30.0
BD_REACH = 9.85                                 # a straight arm's reach to the grip, less a hair so the hand lies on the door
BD_KNOB_Y = 1.6                                 # the bolt at shoulder height, on the door's right half
BD_ON = BD_Z + 0.7                              # a hand flat on the door: its grip this far off it (the fist's edges touch)
BD_KNOB = np.array([-3.2, BD_KNOB_Y, BD_ON])           # its knob drawn back ...
BD_HOME = np.array([-7.3, BD_KNOB_Y, BD_ON])           # ... and shot home into the keeper on the frame
BD_STIFF = 25.0                                 # it drags stiffly to here, then runs
BD_FEET0 = {'right_leg': (-1.9, 0.0), 'left_leg': (1.9, 0.0)}
BD_STEPS = [(2.0, 6.0, 'right_leg', (-2.2, 4.6)), (3.0, 7.0, 'left_leg', (2.1, 0.8)),        # braced to push
            (15.0, 19.5, 'right_leg', (-2.0, 2.4)),                                         # up under them for the bolt
            (40.5, 44.0, 'right_leg', (-2.1, 3.4)), (42.0, 45.5, 'left_leg', (2.0, 2.0)),    # the step back
            (45.5, 48.5, 'right_leg', (-1.9, 0.0)), (46.5, 49.5, 'left_leg', (1.9, 0.0))]    # and the turn back to idle
BD_P = [(0, 0.0), (1.2, -3.0), (3.0, 6.0), (7.0, 13.0), (9.0, 17.0), (10.0, 18.0), (11.0, 13.5), (13.0, 14.5), (16.0, 10.0),
        (21.0, 6.0), (26.0, 7.0), (29.5, 8.5), (31.0, 4.0), (32.5, 9.0), (35.0, 7.0), (38.0, 12.0), (41.0, 6.0), (45.0, 1.0),
        (50, 0.0)]
BD_YAW = [(0, 0.0), (3.0, 0.0), (10.0, -4.0), (13.0, 6.0), (20.0, 4.0), (26.0, -6.0), (30.0, -7.0), (34.0, -3.0), (40.0, 0.0),
          (43.0, 6.0), (46.0, -6.0), (50, 0.0)]                                            # (+: the right shoulder back)
BD_SINK = [(0, 0.0), (1.2, -0.8), (3.0, 0.0), (34.0, 0.0), (37.0, 0.9), (40.0, 0.7), (44.0, 0.2), (50, 0.0)]   # px down
# the hands: on the door ('door' weight), at the chest after; their places on the door
BD_LW = [(0, 0.0), (2.0, 0.0), (6.0, 1.0), (36.0, 1.0), (40.0, 0.0), (50, 0.0)]
BD_RW = [(0, 0.0), (1.5, 0.0), (5.5, 1.0), (37.0, 1.0), (40.5, 0.0), (50, 0.0)]
BD_CW = [(0, 0.0), (37.5, 0.0), (41.5, 1.0), (46.5, 1.0), (49.8, 0.0), (50, 0.0)]       # both hands to the chest
BD_DROP = [(0, 0.0), (44.5, 0.0), (47.0, 1.0), (50, 1.0)]                                # and from the clasp down, apart, before
BD_LOW = {'right_arm': np.array([-4.2, 10.8, -5.6]), 'left_arm': np.array([4.2, 10.8, -5.6])}    # they swing back to the sides
BD_CHEST = {'right_arm': np.array([-0.9, 8.6, -6.8]), 'left_arm': np.array([0.9, 8.4, -6.8])}    # torso space: clasped before the belly (at arm's length)
BD_LEFT = [(0, np.array([2.8, -1.6, BD_ON])), (7.0, np.array([2.8, -1.6, BD_ON])),
           (10.0, np.array([2.8, -0.6, BD_ON])), (14.0, np.array([3.0, 1.0, BD_ON])), (18.0, np.array([3.0, 0.4, BD_ON])),
           (33.0, np.array([3.0, -0.4, BD_ON])), (36.0, np.array([3.2, 1.8, BD_ON])), (50, np.array([3.2, 3.0, BD_ON]))]
BD_RIGHT = [(0, np.array([-2.9, -1.2, BD_ON])), (7.0, np.array([-2.9, -1.2, BD_ON])),
            (10.0, np.array([-2.8, -0.2, BD_ON])), (13.0, np.array([-2.9, 1.2, BD_ON])),
            (15.5, np.array([-5.6, 0.0, BD_ON])),               # the first grope: too far over, on the frame side
            (17.5, np.array([-5.0, 3.6, BD_ON])),               # then too low
            (19.5, np.array([-3.9, 2.4, BD_ON])),
            (21.0, BD_KNOB), (37.0, BD_HOME), (40.5, np.array([-6.6, 3.4, BD_ON])), (50, np.array([-6.6, 3.4, BD_ON]))]
BD_TWIST = [(0, 0.0), (28.6, 0.0), (30.0, 1.0), (32.0, 1.0), (35.0, 0.0), (50, 0.0)]     # the wrist turned as the bolt seats
BD_TUG = [(0, 0.0), (31.0, 0.0), (32.0, 1.0), (33.0, 0.2), (33.8, 0.8), (35.5, 0.0), (50, 0.0)]  # the tug: px the hand pulls back
BD_HP = [(0, 0.0), (1.2, -16.0), (4.0, -12.0), (8.0, -2.0), (10.0, 4.0), (11.0, -2.0), (13.0, 6.0), (16.0, 10.0), (21.0, 14.0),
         (29.0, 12.0), (30.0, 16.0), (33.0, 10.0), (36.0, 14.0), (38.5, 26.0), (41.0, 18.0), (44.0, 2.0), (47.0, 0.0), (50, 0.0)]
BD_HY = [(0, 0.0), (1.2, 6.0), (4.0, 2.0), (10.0, 0.0), (12.0, 17.0), (16.0, 19.0), (18.0, 8.0), (21.0, -18.0), (29.0, -22.0),
         (31.0, -14.0), (34.0, -4.0), (38.0, 0.0), (41.0, 10.0), (43.5, 52.0), (45.5, 50.0), (47.5, -24.0), (49.0, -10.0),
         (50, 0.0)]
BD_HR = [(0, 0.0), (11.0, 0.0), (13.0, 13.0), (17.0, 11.0), (20.0, 0.0), (43.5, -4.0), (47.5, 3.0), (50, 0.0)]


def bd_wall():
    """The wall each side of the doorway and its lintel (what the hands must stay out of), the leaf once it is shut."""
    return [((8.0, -24.0, -24.0), (24.0, 24.0, BD_Z), (116, 108, 98), True),
            ((-24.0, -24.0, -24.0), (-8.0, 24.0, BD_Z), (116, 108, 98), True),
            ((-8.0, -24.0, -24.0), (8.0, -8.0, BD_Z), (104, 96, 88), True)]


def bd_leaf():
    return ((-8.0, -8.0, BD_Z - 3.0), (8.0, 24.0, BD_Z), (118, 84, 54), True)


def bd_bolt_x(t):
    """The bolt's knob along the door at [t]: drawn back, dragged stiffly to BD_STIFF and then freely home, seated at
    BD_BOLT_T with a last push."""
    return K([(0, BD_KNOB[0]), (21.0, BD_KNOB[0]), (BD_STIFF, BD_KNOB[0] - 0.9), (28.6, BD_HOME[0] + 0.25),
              (BD_BOLT_T, BD_HOME[0]), (50, BD_HOME[0])], t)


def bd_station():
    """The doorway for the previews: the wall, the leaf shut from the slam on with the bolt on it, its guide and the
    keeper on the frame (the game draws the real door)."""
    t = la._CUR[1] if la._CUR[1] is not None else 0.0
    b = list(bd_wall())
    if t >= BD_SLAM:
        b.append(bd_leaf())
        x = bd_bolt_x(t)
        y = BD_KNOB_Y
        b.append(((-7.6, y - 0.9, BD_Z), (-1.6, y + 0.9, BD_Z + 0.35), (84, 84, 92), True))            # its guide
        b.append(((min(x, -3.0) - 3.2, y - 0.45, BD_Z + 0.1), (x - 0.4, y + 0.45, BD_Z + 0.55), (70, 70, 78), True))  # the bar
        b.append(((x - 0.45, y - 0.45, BD_Z + 0.1), (x + 0.45, y + 0.45, BD_Z + 1.3), (50, 50, 58), True))   # its knob
        b.append(((-9.8, y - 1.0, BD_Z - 0.2), (-8.0, y + 1.0, BD_Z + 0.9), (60, 60, 68), True))      # the keeper in the frame
    return b


def shoulder_need(fr, side, target, reach=BD_REACH):
    """How far (along z) the shoulder of [side] must move from where frame [fr] has it for the grip to sit on [target] with
    the straight arm just reaching it."""
    s = torso_to_world(fr, PIVOTS[side])
    dx, dy = target[0] - s[0], target[1] - s[1]
    return float(target[2] + math.sqrt(max(0.0, reach * reach - dx * dx - dy * dy)) - s[2])


def bd_target(side, t):
    """The point of the door the hand of [side] is on at [t] (the right on the bolt's knob while it drags it)."""
    if side == 'right_arm':
        at = KV(BD_RIGHT, t).copy()
        if 21.0 <= t <= 36.0:
            at[0] = bd_bolt_x(t)
            at[2] += K(BD_TUG, t)
        return at
    return KV(BD_LEFT, t)


def bd_rz(fr0, t):
    """The root's z at [t]: where the body must be for the hands on the door to reach it with straight arms, blended by how
    much each is on it (the feet's midpoint when neither is)."""
    need, weight = 0.0, 0.0
    for side, keys in (('right_arm', BD_RW), ('left_arm', BD_LW)):
        w = smooth(K(keys, t))
        if w > 1e-3:
            need += w * shoulder_need(fr0, side, bd_target(side, t))
            weight += w
    return need / weight if weight > 1e-6 else 0.0, min(1.0, weight)


def arc(fr, side, a, b, u):
    """A point [u] of the way from [a] to [b] round the shoulder of [side] (as a straight arm swings it, never across the
    body), its distance from the shoulder going evenly from [a]'s to [b]'s."""
    s = torso_to_world(fr, PIVOTS[side])
    da, db = np.asarray(a, float) - s, np.asarray(b, float) - s
    ra, rb = float(np.linalg.norm(da)), float(np.linalg.norm(db))
    return s + tcl.qslerp_dir(da, db, u) * (ra + (rb - ra) * u)


def bd_hand(side, t, fr, holds, tremble):
    """The arm for [side] at [t]: hanging, on the door or at the chest, swung between them round the shoulder."""
    # hanging a little forward, braced, from the first steps to the last (as idle hangs them at either end, to join it)
    rest = rest_arm(side, fr, fwd=8.0 * smooth(t / 2.0) * smooth((BD_LEN - t) / 3.0))
    inward = L if side == 'right_arm' else R
    w = smooth(K(BD_RW if side == 'right_arm' else BD_LW, t))
    cw = smooth(K(BD_CW, t))
    if max(w, cw) < 1e-4:
        return rest
    at = grip_of(side, rest, fr)
    palm = inward
    if w > 0.0:
        twist = K(BD_TWIST, t) if side == 'right_arm' else 0.0
        door = bd_target(side, t) + np.array([0.0, tremble, 0.0])
        at = arc(fr, side, at, door, w)
        palm = unit(inward * (1.0 - w) + w * unit(F * (1.0 - 0.7 * twist) + inward * (0.15 + 0.9 * twist) + D * 0.1))
    if cw > 0.0:
        held = BD_CHEST[side] + (BD_LOW[side] - BD_CHEST[side]) * smooth(K(BD_DROP, t))
        at = arc(fr, side, at, torso_to_world(fr, held), cw)
        palm = unit(palm * (1.0 - cw) + cw * unit(U * 0.3 + B * 0.6 + inward * 0.5))
    pose = reach_arm(holds, side, fr, at, palm=palm, palm_w=0.35, slack=0.9, limit=2.0)
    return mix(rest, pose, smooth(min(1.0, 5.0 * max(w, cw))))


def bolt_door():
    c = la.clip('bolt_door', BD_LEN, False, look=('citizen', 4), station_='bolt_door', events=[('bolt', BD_BOLT_T)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
                views=((150, -10, True), (100, -4), (-140, -8)), pan=(0.0, 0.0, -4.0))
    holds = {'right_arm': la.Holds(), 'left_arm': la.Holds()}
    for t in la.every(BD_LEN):
        feet, lift = stepping(t, BD_FEET0, BD_STEPS, lift=1.3)
        pitch = K(BD_P, t)
        fear = 1.0 - smooth((t - 34.0) / 6.0)
        # short shallow breaths while it is still open and in the fumble, one long one let out, then easy breathing
        breath = 0.45 * fear * math.sin(2 * math.pi * t / 4.4) + 0.3 * (1.0 - fear) * math.sin(2 * math.pi * (t - 34.0) / 11.0)
        tremble = 0.22 * fear * smooth(t / 3.0) * math.sin(2 * math.pi * t / 1.7) * math.sin(2 * math.pi * t / 7.3)
        qt = bend(pitch, K(BD_YAW, t), 0.0)
        vt = (0.0, sink_for(pitch) + K(BD_SINK, t) - 0.35 * breath, 0.0)
        mid = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
        fr0 = F_(qt=qt, vt=vt, pr=(0.0, 0.0, 0.0))
        rz, w = bd_rz(fr0, t)
        z = mid * (1.0 - w) + rz * w
        fr = F_(qt=qt, vt=vt, pr=la.root_for(feet, x=0.0, z=z, dip=0.0))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        for side in ('right_arm', 'left_arm'):
            pose = bd_hand(side, t, fr, holds[side], tremble)
            set_arm(c, side, t, lifted(pose, 0.5 * breath))
        c.key('head', t, qeuler(x=K(BD_HP, t) + 0.8 * breath, y=K(BD_HY, t) + 6.0 * tremble, z=K(BD_HR, t)), ease='linear')
    c.mode('head', 'add')
    # contacts
    for k in (8.0, BD_SLAM, 12.0):
        for side, label in (('left_arm', 'left'), ('right_arm', 'right')):
            check(c, f'{label} hand on the door (t={k:g})', ta.fk(c, k, side, GRIP[side])[2:], (BD_ON,), tol=0.5)
    for k in (21.0, 26.0, BD_BOLT_T):
        got = ta.fk(c, k, 'right_arm', GRIP['right_arm'])
        check(c, f'right hand on the bolt\'s knob (t={k:g})', got, np.array([bd_bolt_x(k), BD_KNOB_Y, BD_ON]), tol=0.6)
    times = [i * 0.5 for i in range(2 * BD_LEN + 1)]
    walls = bd_wall()
    keep_out(c, [t for t in times if t < BD_SLAM], walls, 'wall', tol=0.6)
    keep_out(c, [t for t in times if t >= BD_SLAM], walls + [bd_leaf()], 'wall and door', tol=0.6)
    below_ground(c, times)
    clear_hands(c, times)
    joins_idle(c)
    return c


# ================================================================== lay_candle

# ---- lay_candle (60, once): a lit candle carried to a memorial and set on it. The memorial is Tales G's stone (its plinth
# and shaft as tale_clips' MEM_* boxes), its carved face LC_D px ahead: the stone's middle 1.4 blocks off, where a mourner
# is put beside it. The candle (candle_lit, its flame candle_flame) rides upright in the right fist, cupped at the waist,
# the left hand shielding the flame; a settling half step; down on the right knee (the left foot forward, the right knee
# back) with the left hand to the left knee; the right hand leaning out over the ledge and setting the candle on it where
# the stone's first candle stands (LC_SET: 'set', the task shows the stone's candle there, burning, and the carried one goes);
# the fingers held on it a moment, then the hand cupped round the flame to keep it as it settles on the wick, the eyes on it,
# and drawn off it once it stands ('lit': the candle_light heard, its own moment apart from the candle_lay of the set); both
# hands on the knee, the head bowed, the breath moving the shoulders; up again over the left foot, the right hand to the chest,
# and down, standing on idle.
LC_LEN = 60
LC_D = 14.9                                     # the plinth's face ahead: the stone's middle 22.4 px (1.4 blocks) off
LC_SET = 24.0
LC_LIT = 34.0
LC_DX = 2.6                                     # the kneeler's hips shifted this far to their left: the right hand comes down in front of the candle's place
# the stone's first candle (Tales G's memorial_stone_candle_1: wax x 7.6..9.6, z 1.1..3.1, its foot at 5.9 on its puddle)
LC_FOOT = np.array([8.6 - 8.0, 24.0 - 5.9, -(LC_D - 0.5) - 2.1])
LC_WICK = np.array([LC_FOOT[0], 24.0 - 10.9, LC_FOOT[2]])
LC_GRIP_SET = LC_FOOT - np.asarray(lp.CANDLE_BASE, float)   # the fist's grip with the candle's foot on the stone
LC_FEET0 = {'right_leg': (-1.9, 0.0), 'left_leg': (1.9, 0.0)}
LC_STEPS = [(1.0, 6.0, 'left_leg', (2.0, -1.4)), (4.0, 9.0, 'right_leg', (-1.8, -0.6)),                  # the settling half step
            (12.0, 16.0, 'left_leg', (LC_DX + 4.4, -10.8)), (14.5, 20.0, 'right_leg', (LC_DX - 2.4, 9.6)),  # down on the right knee
            (48.0, 53.5, 'right_leg', (-1.4, -1.6)), (52.5, 57.5, 'left_leg', (1.9, 0.0)), (55.0, 59.0, 'right_leg', (-1.9, 0.0))]
LC_PITCH = [(0, 0.0), (3.0, 5.0), (9.0, 4.0), (12.0, 8.0), (16.0, 20.0), (19.5, 30.0), (22.0, 41.0), (LC_SET, 44.0),
            (30.0, 44.0), (LC_LIT, 42.0), (37.0, 30.0), (40.0, 22.0), (43.0, 20.0), (46.0, 22.0), (49.0, 26.0), (53.0, 16.0),
            (57.0, 3.0), (60, 0.0)]
LC_YAW = [(0, 0.0), (19.0, 0.0), (22.0, -8.0), (LC_LIT, -8.0), (39.0, 0.0), (60, 0.0)]       # the right shoulder forward to set it
LC_HEAD = [(0, 0.0), (3.0, 18.0), (9.0, 14.0), (12.0, 4.0), (16.0, 0.0), (20.0, 4.0), (LC_SET, 6.0), (29.0, 10.0), (LC_LIT, 8.0),
           (36.0, 2.0), (39.0, 12.0), (42.0, 22.0), (45.0, 20.0), (46.5, 14.0), (48.0, 18.0), (50.0, 6.0), (54.0, -2.0),
           (60, 0.0)]       # the head's pitch off the torso: down to the flame, up to the stone, bowed (once looking up to it)
LC_HROLL = [(0, 0.0), (37.0, 0.0), (41.0, 5.0), (46.0, 5.0), (50.0, 0.0), (60, 0.0)]
# the hands' places (anchors, worked out frame by frame from the body and legs) and the ticks they are at each
LC_RIGHT = [(0, 'rest'), (2.0, 'cup'), (4.0, 'carry'), (19.0, 'carry'), (22.0, 'hover'), (LC_SET, 'set'), (26.0, 'set'),
            (28.5, 'shelter'), (32.0, 'shelter'), (LC_LIT, 'lift'), (35.5, 'lift'), (39.0, 'lap'), (48.0, 'lap'), (51.5, 'chest'),
            (54.0, 'chest'), (56.5, 'cup'), (59.5, 'rest'), (60, 'rest')]
LC_LEFT = [(0, 'rest'), (2.0, 'cup'), (4.5, 'shield'), (12.0, 'shield'), (16.5, 'knee'), (50.5, 'knee'), (53.0, 'off'), (56.5, 'rest'),
           (60, 'rest')]
LC_KNEE = [(0, 0.72), (19.0, 0.72), (22.0, 0.82), (35.0, 0.82), (38.0, 0.72), (60, 0.72)]    # how far down the leg it rests (on the shin while leaning out)
LC_CARRY = np.array([-1.5, 8.4, -6.9])          # torso space: the fist cupped at the waist, the candle up from it
LC_CUP = {'right_arm': np.array([-4.4, 9.2, -7.2]), 'left_arm': np.array([4.6, 8.4, -7.4])}  # torso space: a hand brought up before its hip on the way in
LC_OFF = np.array([6.6, 9.0, -4.6])             # torso space: the left hand coming off the knee out by the left hip
LC_SHIELD = np.array([0.6, 4.6, -8.6])          # torso space: the left hand before the flame, its palm to it
LC_CHEST = np.array([-0.6, 6.0, -7.6])          # torso space: the right hand to the breast
LC_SHELTER = np.array([-2.6, -1.6, 1.0])        # from the wick: the right hand cupped round the new flame


def lc_memorial():
    """The memorial stone ahead (Tales G's plinth, shaft, cap and name slab), its face LC_D px ahead, its foot on the ground."""
    def box(lo, hi, col):
        return ((lo[0] - 8.0, 24.0 - hi[1], -(LC_D - 0.5) - hi[2]), (hi[0] - 8.0, 24.0 - lo[1], -(LC_D - 0.5) - lo[2]), col, True)
    b = [box(*tcl.MEM_PLINTH, (118, 118, 126))]
    for i, (y0, y1, x0, x1, z0, z1) in enumerate(tcl.MEM_SEGS):
        b.append(box((x0, y0, z0), (x1, y1, z1), (142, 142, 150) if i % 2 == 0 else (132, 132, 140)))
    b.append(box(*tcl.MEM_CAP, (154, 154, 162)))
    b.append(box(*tcl.MEM_SLAB, (96, 96, 106)))
    return b


def lc_station():
    """The stone, and from LC_SET its first candle on the ledge and its flame (the task sets it, burning: Tales G's candle lit)."""
    t = la._CUR[1] if la._CUR[1] is not None else 0.0
    b = lc_memorial()
    if t >= LC_SET:
        x, y, z = LC_FOOT
        b.append(((x - 1.5, y - 0.05, z - 1.45), (x + 1.5, y + 0.4, z + 1.45), (226, 214, 186), True))     # its puddle
        b.append(((x - 1.0, y - 4.1, z - 1.0), (x + 1.0, y, z + 1.0), (226, 214, 186), True))
    if t >= LC_SET:
        w = LC_WICK
        b.append(((w[0] - 0.6, w[1] - 3.0, w[2] - 0.6), (w[0] + 0.6, w[1] + 0.2, w[2] + 0.6), (255, 200, 90), True))
    return b


def lc_dx(t):
    return LC_DX * smooth((t - 12.0) / 7.0) * (1.0 - smooth((t - 49.0) / 9.0))


def lc_knee(feet, root, frac, side='left_leg'):
    """A point on top of the forward (left) leg, [frac] of the way from the hip to the foot: where a hand rests on the knee."""
    hip = la.hip_of(side, root)
    fx, fz = feet[side]
    foot = np.array([fx, 24.0, fz])
    return hip + frac * (foot - hip) + np.array([0.0, -2.7, 0.0])


def lc_anchor(name, side, t, fr, feet, rest):
    """Where the hand of [side] is wanted for anchor [name] at this frame, and which way its palm faces."""
    inward = L if side == 'right_arm' else R
    if name == 'rest':
        return grip_of(side, rest, fr), inward
    if name == 'cup':
        return torso_to_world(fr, LC_CUP[side]), unit(U * 0.4 + inward * 0.7)
    if name == 'off':
        return torso_to_world(fr, LC_OFF), unit(D + B * 0.4)
    if name == 'carry':
        return torso_to_world(fr, LC_CARRY), unit(U * 0.6 + inward * 0.6)
    if name == 'hover':
        return LC_GRIP_SET + np.array([0.0, -1.4, 0.6]), unit(F * 0.4 + inward)
    if name == 'set':
        return LC_GRIP_SET, unit(F * 0.4 + inward)
    if name == 'lift':
        return LC_GRIP_SET + np.array([-0.2, -1.2, 1.6]), unit(F * 0.2 + inward + D * 0.3)
    if name == 'shelter':
        return LC_WICK + LC_SHELTER, unit(L * 0.9 + B * 0.25)     # round the flame on its right, the palm to it
    if name == 'shield':
        return torso_to_world(fr, LC_SHIELD), unit(B * 0.8 + L * 0.5)
    if name == 'chest':
        return torso_to_world(fr, LC_CHEST), unit(B * 0.7 + inward * 0.5)
    knee = lc_knee(feet, fr['pr'], K(LC_KNEE, t))
    if name == 'knee':
        return knee, unit(D + B * 0.3)
    if name == 'lap':
        return knee + np.array([-1.4, -1.2, 0.3]), unit(D + inward * 0.3)        # over the left hand
    raise KeyError(name)


def lc_hand(side, t, fr, feet, holds):
    """The arm of [side] at [t]: from anchor to anchor of LC_RIGHT / LC_LEFT, swung round the shoulder between them."""
    rest = rest_arm(side, fr)
    keys = LC_RIGHT if side == 'right_arm' else LC_LEFT
    i = max(k for k in range(len(keys)) if keys[k][0] <= t + 1e-9)
    j = min(i + 1, len(keys) - 1)
    (t0, a), (t1, b) = keys[i], keys[j]
    pa, qa = lc_anchor(a, side, t, fr, feet, rest)
    if a == b or t1 <= t0:
        at, palm = pa, qa
    else:
        u = smooth((t - t0) / (t1 - t0))
        pb, qb = lc_anchor(b, side, t, fr, feet, rest)
        at, palm = arc(fr, side, pa, pb, u), unit(qa * (1.0 - u) + qb * u)
    if a == 'rest' and b == 'rest':
        return rest
    pose = reach_arm(holds, side, fr, at, palm=palm, palm_w=0.35, slack=0.9, limit=2.0)
    near = 1.0
    if a == 'rest' or b == 'rest':
        u = (t - t0) / max(1e-6, t1 - t0)
        near = smooth(min(1.0, 4.0 * (u if a == 'rest' else 1.0 - u)))
    return mix(rest, pose, near)


def lc_candle(c, t):
    """The candle's pivot (the fist's grip) in the clip's space at [t]."""
    return wc.apply(wc.part_M(c, t, 'right_arm'), np.asarray(lp.RIGHT_GRIP, float))


def _lc_candle_track(c):
    def track(t, pivot):
        return np.eye(3), None, None                            # upright wherever the fist turns
    return track


def _lc_flame_track(c):
    def track(t, pivot):
        p = lc_candle(c, t) + np.asarray(lp.CANDLE_WICK, float)
        q = lc_candle(c, max(0.0, t - 0.5)) + np.asarray(lp.CANDLE_WICK, float)
        lean = -(p - q) / 0.5 * 0.35
        lean[1] = 0.0
        shield = smooth(t / 4.0) * (1.0 - smooth((t - 12.0) / 4.0))     # behind the hand it burns small and steady
        R_ = wc.frame_of(unit(D + lean), B)
        f = (1.0 - 0.25 * shield) * (1.0 + 0.09 * math.sin(t * 3.1) + 0.05 * math.sin(t * 7.7 + 1.3))
        w = 1.0 + 0.05 * math.sin(t * 4.3 + 2.0)
        return R_, p, (w, f, w)
    return track


def lay_candle():
    c = la.clip('lay_candle', LC_LEN, False, look=('citizen', 5), tools_=('candle_lit', 'candle_flame'), station_='lay_candle',
                events=[('set', LC_SET), ('lit', LC_LIT)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
                views=((-55, 8), (115, 8), (90, 2)), pan=(0.0, 0.0, -(LC_D / 2)))
    ta.INFO['lay_candle']['tools_at'] = tools_when([('candle_lit', 0.0, LC_SET), ('candle_flame', 0.0, LC_SET)])
    la.TRACKS['lay_candle'] = {'candle_lit': _lc_candle_track(c), 'candle_flame': _lc_flame_track(c)}
    holds = {'right_arm': la.Holds(), 'left_arm': la.Holds()}
    for t in la.every(LC_LEN):
        feet, lift = stepping(t, LC_FEET0, LC_STEPS, lift=1.2)
        dx = lc_dx(t)
        rz = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
        pitch = K(LC_PITCH, t)
        bowed = smooth((t - 38.0) / 4.0) * (1.0 - smooth((t - 47.0) / 3.0))
        breath = bowed * math.sin(2 * math.pi * (t - 38.0) / 9.0)          # slow and deep while it is held
        root = la.root_for(feet, x=dx, z=rz)
        fr = F_(qt=bend(pitch + 1.2 * breath, K(LC_YAW, t), 0.0), vt=(0.0, sink_for(pitch) - 0.5 * breath, 0.0), pr=root)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        for side in ('right_arm', 'left_arm'):
            set_arm(c, side, t, lc_hand(side, t, fr, feet, holds[side]))
        c.key('head', t, qeuler(x=K(LC_HEAD, t) + 1.5 * breath, z=K(LC_HROLL, t)), ease='linear')
    c.mode('head', 'add')
    foot = lc_candle(c, LC_SET) + np.asarray(lp.CANDLE_BASE, float)
    check(c, f'the candle\'s foot on the stone\'s ledge (t={LC_SET:g})', foot, LC_FOOT, tol=0.6)
    stone = lc_memorial()
    times = [i * 0.5 for i in range(2 * LC_LEN + 1)]
    keep_out(c, times, stone, 'stone', tol=0.6)
    below_ground(c, times)
    clear_hands(c, [t for t in times if t >= 1.0])
    joins_idle(c)
    return c


# ================================================================== cradle

# ---- the swaddle (living_props.py): a baby lying across both forearms before the chest, on the body so it rocks with
# them. The arms are held under it, the hands out past it (CR_HOLD, in the torso's space); swaddle space: x along the
# bundle (its head end +x, at the holder's left), y down through it, z from its front back toward the chest.
CR_HOLD = {'right_arm': np.array([-4.6, 8.8, -8.2]), 'left_arm': np.array([4.6, 8.4, -8.4])}
CR_PALM = {'right_arm': unit(U * 0.4 + L), 'left_arm': unit(U * 0.4 + R)}
ARM_BOX = {'right_arm': ((-3.0, -2.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, -2.0, -2.0), (3.0, 10.0, 2.0))}
HAND_BOX = {'right_arm': ((-3.0, 6.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, 6.0, -2.0), (3.0, 10.0, 2.0))}


def sw_local():
    """The swaddle's 4x4 in the torso's space."""
    return ta.tool_local('swaddle')


def sw_torso(q):
    """A swaddle-space point in the torso's space."""
    M = sw_local()
    return M[:3, :3] @ np.asarray(q, float) + M[:3, 3]


def sw_dir(fr, d):
    """A swaddle-space direction in the world under [fr]."""
    return qmat(qmul(fr['qr'], fr['qt'])) @ (sw_local()[:3, :3] @ np.asarray(d, float))


def sw_depth(c, t, side, box=ARM_BOX):
    """How deep (px) [side]'s arm (its [box]) goes into the swaddle at [t] of [c] (0: clear)."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    pose = ta.pose_at(c, t, ta.walk_base(t, speed=ta.walk_speed(c, False), age=t))
    mats = ta.part_matrices(look, pose)
    Mi = np.linalg.inv(mats['body'] @ sw_local())
    cubes = [(np.array(q.origin, float), np.array(q.origin, float) + np.array(q.size, float)) for q in ta.tools()[0]['swaddle'].cubes]
    lo, hi = box[side]
    worst = 0.0
    for i in range(5):
        for j in range(9):
            for k in range(5):
                p = np.array([lo[0] + (hi[0] - lo[0]) * i / 4, lo[1] + (hi[1] - lo[1]) * j / 8, lo[2] + (hi[2] - lo[2]) * k / 4])
                w = mats[side][:3, :3] @ p + mats[side][:3, 3]
                q = Mi[:3, :3] @ w + Mi[:3, 3]
                for a, b_ in cubes:
                    worst = max(worst, min(q[0] - a[0], b_[0] - q[0], q[1] - a[1], b_[1] - q[1], q[2] - a[2], b_[2] - q[2]))
    return worst


def sw_clear(c, times, tol=0.6):
    for side in ('right_arm', 'left_arm'):
        worst, at = max((sw_depth(c, t, side), t) for t in times)
        check(c, f'{side.replace("_", " ")} clear of the swaddle (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


# the tuck: the right hand off from under the bundle and round to its foot end, two pushes of the cloth in round the feet
# (the inside of the hand on the bundle's end, pressing in and a little back toward the chest), and back under. Its
# places in swaddle space (where the inside of the hand is) and the ticks from the tuck's start.
TUCK_OUT = np.array([-6.4, 2.6, -2.4])          # off from under, out before and below the foot end
TUCK_ON = np.array([-4.3, -0.2, 0.0])           # the inside of the hand on the middle of the foot end
TUCK_IN = np.array([-3.9, 0.1, 0.6])            # pressed in round the feet and back toward the chest
TUCK_KEYS = [(0.0, 'hold'), (2.0, 'out'), (3.6, 'on'), (4.6, 'in'), (5.4, 'on'), (6.4, 'in'), (7.6, 'out'), (9.6, 'hold')]
TUCK_SIDE = np.array([1.0, 7.5, 0.0])           # arm space: the inside of the right hand, 2.5 px back from its end


def tuck_target(name, fr):
    """Where the right grip goes for tuck place [name], and which way the inside of the hand faces."""
    if name == 'hold':
        return torso_to_world(fr, CR_HOLD['right_arm']), None
    q = {'out': TUCK_OUT, 'on': TUCK_ON, 'in': TUCK_IN}[name]
    side = torso_to_world(fr, sw_torso(q))
    inward = sw_dir(fr, (1.0, 0.0, 0.0))
    along = unit(side - torso_to_world(fr, PIVOTS['right_arm']))
    # the grip is 2 px out from the inside of the hand and 2.5 px on along the forearm
    return side - 2.0 * unit(inward - along * float(np.dot(inward, along))) + 2.5 * along, inward


def tuck_hand(fr, t, start, holds):
    """The right arm at [t], tucking from [start] (or holding under the bundle outside the tuck)."""
    u = t - start
    if u <= 0.0 or u >= TUCK_KEYS[-1][0]:
        return hold_hand('right_arm', fr, holds)
    i = max(k for k in range(len(TUCK_KEYS)) if TUCK_KEYS[k][0] <= u)
    (t0, a), (t1, b) = TUCK_KEYS[i], TUCK_KEYS[i + 1]
    w = smooth((u - t0) / (t1 - t0))
    pa, qa = tuck_target(a, fr)
    pb, qb = tuck_target(b, fr)
    at = arc(fr, 'right_arm', pa, pb, w) if 'hold' in (a, b) else pa * (1.0 - w) + pb * w
    side = qa if qb is None else qb if qa is None else unit(qa * (1.0 - w) + qb * w)
    on = w if a == 'hold' else (1.0 - w) if b == 'hold' else 1.0
    pose = reach_arm(holds, 'right_arm', fr, at, palm=CR_PALM['right_arm'], palm_w=0.3 * (1.0 - on), slack=0.9, limit=2.0,
                     dirs=[((1.0, 0.0, 0.0), side, 0.4 * on)])
    return pose


def hold_hand(side, fr, holds):
    return reach_arm(holds, side, fr, torso_to_world(fr, CR_HOLD[side]), palm=CR_PALM[side], palm_w=0.3, slack=0.9, limit=2.0)


# ---- cradle (60, loop): rocking a baby held across the forearms. The whole body turns +/-4 degrees about the upright
# once a second (CR_ROCK ticks) and the weight goes with it, the knees giving a pixel as it swings through the middle; the
# swing is a little wider, then narrower, round the loop. The head bends over the bundle, tipped to the baby's face. A
# coo (16, 46): the head dips in close and tips further; then the free (right) hand tucks the cloth, two strokes in
# toward the chest (from 18 and 48: the tuck at 20 and 50), and the head lifts in a small nod as it goes back under.
CR_LEN = 60
CR_ROCK = 20.0
CR_COO = (16.0, 46.0)
CR_TUCK = (18.0, 48.0)                          # the tuck starts (the stroke falls at 20 and 50)
CR_PITCH = 7.0


def cr_amp(t):
    return 1.0 + 0.22 * math.sin(2 * math.pi * t / CR_LEN + 0.7)


def cr_head(t):
    """The head's (pitch, yaw, roll) off the torso at [t]: bent over the bundle and tipped to its face, dipping in for each
    coo, lifting and nodding after each tuck (the second time a little slower and deeper)."""
    pitch, yaw, roll = 21.0, -9.0, 7.0
    for k, (coo, tuck) in enumerate(zip(CR_COO, CR_TUCK)):
        deep = 1.0 + 0.3 * k
        pitch += deep * (5.0 * bump(t, coo - 4.0, coo + 3.5, CR_LEN))
        roll += deep * 6.0 * bump(t, coo - 4.5, coo + 5.0, CR_LEN)
        yaw -= 4.0 * bump(t, coo - 4.0, coo + 4.0, CR_LEN)
        pitch += -9.0 * bump(t, tuck + 2.5, tuck + 6.5, CR_LEN) + 6.0 * bump(t, tuck + 5.5, tuck + 9.5 + k, CR_LEN)
        roll -= 4.0 * bump(t, tuck + 2.5, tuck + 7.0, CR_LEN)
    # and over the rock it follows the bundle a little behind, as a held look does
    yaw += 1.5 * cr_amp(t) * math.sin(2 * math.pi * (t - 1.5) / CR_ROCK)
    return pitch, yaw, roll


def cradle():
    c = la.clip('cradle', CR_LEN, True, look=('citizen', 5), tools_=('swaddle',), events=[('coo', CR_COO[0]), ('coo', CR_COO[1])],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((30, -12), (90, -4), (-40, -22)))
    holds = {'right_arm': la.Holds(), 'left_arm': la.Holds()}
    times = la.every(CR_LEN)
    for t in times:
        ph = 2 * math.pi * t / CR_ROCK
        amp = cr_amp(t)
        yaw = 4.0 * amp * math.sin(ph)
        sway = 0.45 * amp * math.sin(ph - 0.5)              # the weight a beat behind the turn
        dip = 0.9 * (0.5 + 0.5 * math.cos(2 * ph))          # down through the middle of each swing
        breath = math.sin(2 * math.pi * t / 30.0)
        lean = 2.0 * sum(bump(t, coo - 4.0, coo + 4.0, CR_LEN) for coo in CR_COO)
        pitch = CR_PITCH + lean + 0.6 * breath
        fr = F_(qt=bend(pitch, -0.8 * math.sin(ph - 0.3), -1.2 * amp * math.sin(ph)), vt=(0.0, sink_for(pitch) - 0.25 * breath, 0.0),
                pr=la.root_for(FEET, x=sway, dip=dip), qr=qeuler(y=yaw))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        start = next((k for k in CR_TUCK if k <= t < k + TUCK_KEYS[-1][0]), None)
        right = tuck_hand(fr, t, start, holds['right_arm']) if start is not None else hold_hand('right_arm', fr, holds['right_arm'])
        set_arm(c, 'right_arm', t, right)
        set_arm(c, 'left_arm', t, hold_hand('left_arm', fr, holds['left_arm']))
        hp, hy, hr = cr_head(t)
        c.key('head', t, qeuler(x=hp, y=hy, z=hr), ease='linear')
    c.mode('head', 'add')
    seal(c)
    for k in (0.0, 10.0, 30.0, 40.0):
        for side in ('right_arm', 'left_arm'):
            check(c, f'{side.replace("_", " ")} under the bundle (t={k:g})', ta.fk(c, k, side, GRIP[side]),
                  torso_to_world(ta.frame_at(c, k), CR_HOLD[side]), tol=0.6)
    half = [i * 0.5 for i in range(2 * CR_LEN)]
    sw_clear(c, half)
    clear_hands(c, half)
    return c


# ---- carry_swaddle (40, loop, overlay: CARRY's clip for someone with a baby, LivingProps.swaddled): the baby held as
# CRADLE holds it while the legs walk; the head down on the bundle, up to see the way (8-18), down again, and a tuck on
# the way (from 26).
CS_LEN = 40
CS_TUCK = 26.0


def carry_swaddle():
    c = la.clip('carry_swaddle', CS_LEN, True, look=('citizen', 5), tools_=('swaddle',), overlay=True,
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((30, -12), (90, -4)))
    holds = {'right_arm': la.Holds(), 'left_arm': la.Holds()}
    fr = F_()
    for t in la.every(CS_LEN):
        start = CS_TUCK if CS_TUCK <= t < CS_TUCK + TUCK_KEYS[-1][0] else None
        set_arm(c, 'right_arm', t, tuck_hand(fr, t, start, holds['right_arm']) if start is not None else hold_hand('right_arm', fr, holds['right_arm']))
        set_arm(c, 'left_arm', t, hold_hand('left_arm', fr, holds['left_arm']))
        up = bump(t, 6.0, 20.0)
        pitch = 20.0 - 26.0 * up + 4.0 * bump(t, CS_TUCK, CS_TUCK + 6.0) - 5.0 * bump(t, CS_TUCK + 6.0, CS_TUCK + 10.0)
        c.key('head', t, qeuler(x=pitch, y=-8.0 * (1.0 - up) + 3.0 * bump(t, 9.0, 17.0), z=6.0 * (1.0 - up)), ease='linear')
    c.mode('head', 'add')
    seal(c)
    la.EXTRA['carry_swaddle'] = (CS_LEN, True)
    half = [i * 0.5 for i in range(2 * CS_LEN)]
    sw_clear(c, half)
    clear_hands(c, half)
    return c


# ================================================================== the great bell, struck by hand

# A village's bell (vanilla's bell block: its body 6 px across and 4 to 13 px up its block, the lip 8 px across under it) is
# struck as a player strikes one: the hand driven against its side. The ringer stands in the next block off the side it is
# struck from (BellKeeper.standAt: between a floor bell's posts, straight off its open face), so the bell's middle is GB_D px
# ahead; it stands on their own floor (a bell in its frame on the ground: struck low, bent over it with the knees given and
# the free hand on the knee) or hangs a block up (from a beam: struck at the height of the shoulder, up on the toes of the
# reach). The client picks which for the bell nearest them (TownsfolkClient.clipName): RING_BELL at a great bell plays
# ring_great_bell(_high), a stroke a loop; RING_ALARM plays ring_alarm(_high), the alarm's quick strokes.
#
#    ring_great_bell(_high)  30 loop   the arm drawn up and back (to 4.5), the strike (6: the stroke, Crier's and BellKeeper's
#                                      ticks 6 and 36), the hand off the ringing bell, the body easing as it hums, still to 30
#    ring_alarm(_high)       10 loop   the strike at 6 again and again, the arm hardly let down between: the Ringer's strokes
GB_D = 16.0
GB_RING = (30, 6.0)
GB_ALARM = (10, 6.0)
GB_REACH = {False: 8.6, True: 9.6}                                 # how far the shoulder is from the hand's mark at the strike: the arm all but straight


def gb_y(h, high):
    """The clip's y of a point [h] px up the bell's block (a block up for a bell hung [high])."""
    return 24.0 - h - (16.0 if high else 0.0)


def gb_bell(high):
    """The bell's body and its lip ((lo, hi) in the clip's space)."""
    body = (np.array([-3.0, gb_y(13.0, high), -GB_D - 3.0]), np.array([3.0, gb_y(6.0, high), -GB_D + 3.0]))
    lip = (np.array([-4.0, gb_y(6.0, high), -GB_D - 4.0]), np.array([4.0, gb_y(4.0, high), -GB_D + 4.0]))
    return body, lip


GB_MARK = {high: np.array([-2.6 if high else -1.0, gb_y(9.8 if high else 10.2, high), -GB_D + 3.0]) for high in (False, True)}   # the hand's mark on the body
GB_TURN = {False: -12.0, True: -17.0}           # the torso's turn at the strike: the right shoulder into it
GB_OFF = {False: np.array([-2.5, -0.8, 2.4]), True: np.array([-2.4, 2.4, 2.6])}     # where the hand comes back to off the bell, from its mark


def gb_station(high):
    """The bell and what holds it: a floor bell's two posts and its bar (vanilla's bell_floor, struck between the posts), or the rod
    and the beam a bell hung up hangs from."""
    def boxes():
        body, lip = gb_bell(high)
        out = [(tuple(body[0]), tuple(body[1]), (214, 170, 62), True), (tuple(lip[0]), tuple(lip[1]), (186, 142, 44), True)]
        z0, z1 = -GB_D - 2.0, -GB_D + 2.0
        if high:
            out.append(((-1.0, gb_y(16.0, True), -GB_D - 1.0), (1.0, gb_y(13.0, True), -GB_D + 1.0), (72, 72, 80), True))
            out.append(((-8.0, gb_y(32.0, True), -GB_D - 8.0), (8.0, gb_y(16.0, True), -GB_D + 8.0), (122, 92, 58), True))
        else:
            for x0 in (-8.0, 6.0):
                out.append(((x0, gb_y(16.0, False), z0), (x0 + 2.0, gb_y(0.0, False), z1), (128, 128, 134), True))
            out.append(((-6.0, gb_y(15.0, False), -GB_D - 1.0), (6.0, gb_y(13.0, False), -GB_D + 1.0), (96, 70, 44), True))
        return out
    return boxes


GB_FEET = {False: {'right_leg': (-2.4, 1.8), 'left_leg': (2.6, -4.0)},       # the left foot forward, the weight on it
           True: {'right_leg': (-2.2, 1.6), 'left_leg': (2.2, -3.2)}}
GB_ROOT = {False: (0.6, -1.4, 1.4), True: (0.3, -1.8, 0.3)}                   # the hips' shift across and forward, the knees' give


def gb_frame(high, pitch, yaw, dz=0.0, dip=0.0):
    x, z, d = GB_ROOT[high]
    root = la.root_for(GB_FEET[high], x=x, z=z + dz, dip=d + dip)
    return F_(qt=bend(pitch, yaw, 0.0), vt=(0.0, sink_for(pitch), 0.0), pr=root)


def gb_strike_pitch(high, yaw, mark):
    """The torso's bend at the strike that puts the right shoulder GB_REACH from the hand's [mark]."""
    lo, hi = 0.0, (40.0 if high else 70.0)
    for _ in range(40):
        mid = 0.5 * (lo + hi)
        _, joint = ta.chain_frame('right_arm', **gb_frame(high, mid, yaw))
        if float(np.linalg.norm(mark - joint)) > GB_REACH[high]:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def gb_hand_points(c, t, side):
    """The hand of [side] (the arm's last 4 px) as points of the clip's space at [t]."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    mats = ta.part_matrices(look, ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t)))
    lo, hi = tcl.BOXES[side]
    pts = [np.array([lo[0] + (hi[0] - lo[0]) * i / 4, lo[1] + (hi[1] - lo[1]) * j / 4, lo[2] + (hi[2] - lo[2]) * k / 4])
           for i in range(5) for j in range(5) for k in range(5)]
    return [mats[side][:3, :3] @ p + mats[side][:3, 3] for p in pts]


def gb_touch(c, t, box):
    """How the right hand meets [box] at [t]: how deep it goes in (px), or (negative) how far it is short of it."""
    lo, hi = box
    deepest, nearest = 0.0, 1e9
    for w in gb_hand_points(c, t, 'right_arm'):
        inside = min(float(np.min(w - lo)), float(np.min(hi - w)))
        if inside > 0.0:
            deepest = max(deepest, inside)
        nearest = min(nearest, float(np.linalg.norm(np.maximum(np.maximum(lo - w, 0.0), w - hi))))
    return deepest if deepest > 0.0 else -nearest


# The beats of a stroke, as (tick, pose) through the loop: 'rest' the arm let down before the thigh (the great bell: the bell
# left to hum), 'wind' drawn up and back, 'hit' on the mark, 'off' the hand come back off the bell; the strike from the wind
# to the hit is quickening (the hand driven at it), every other move eased.
GB_BEATS = {'ring': [(0.0, 'rest'), (3.4, 'wind'), (4.5, 'wind'), (6.0, 'hit'), (8.0, 'off'), (15.0, 'rest'), (30.0, 'rest')],
            'alarm': [(0.0, 'mid'), (2.8, 'wind'), (4.4, 'wind'), (6.0, 'hit'), (7.6, 'off'), (10.0, 'mid')]}
# the arm's swing up from hanging (degrees forward, degrees out) at rest, half up between the alarm's strokes, and drawn back
GB_SWING = {(False, 'rest'): (24.0, 7.0), (False, 'wind'): (112.0, 24.0), (False, 'alarm_wind'): (40.0, 72.0),
            (True, 'rest'): (22.0, 8.0), (True, 'wind'): (150.0, 42.0), (True, 'alarm_wind'): (140.0, 36.0)}


def gb_curve(keys, t, length):
    """A number through [keys] (t, value) round the loop: eased between keys, quickening into the strike (6)."""
    for (t0, a), (t1, b) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            u = (t - t0) / (t1 - t0) if t1 > t0 else 1.0
            u = u * u if abs(t1 - 6.0) < 1e-6 and t0 < 6.0 else smooth(u)
            return a + (b - a) * u
    return keys[-1][1]


def gb_clip(name, high, kind):
    length, hit = GB_RING if kind == 'ring' else GB_ALARM
    beats = GB_BEATS[kind]
    mark = GB_MARK[high].copy()
    station = gb_station(high)
    body, lip = gb_bell(high)
    middle = np.array([0.0, gb_y(9.5, high), -GB_D])
    # the body's sway through a stroke: back with the arm drawn, into the strike, eased off after it (yaw: + the right shoulder back)
    if kind == 'ring':
        yaw_keys = [(0.0, -3.0), (3.4, 9.0), (4.5, 10.0), (6.0, GB_TURN[high]), (8.0, GB_TURN[high] + 3.0), (15.0, -3.0), (30.0, -3.0)]
        bend_keys = [(0.0, -3.0), (3.4, -6.0), (4.5, -6.0), (6.0, 0.0), (8.0, -0.5), (15.0, -3.0), (30.0, -3.0)]
        dz_keys = [(0.0, 0.0), (3.4, 0.5), (4.5, 0.5), (6.0, -0.5), (8.0, -0.4), (15.0, 0.0), (30.0, 0.0)]
    else:
        yaw_keys = [(0.0, -4.0), (2.8, 7.0), (4.4, 8.0), (6.0, GB_TURN[high]), (7.6, GB_TURN[high] + 3.0), (10.0, -4.0)]
        bend_keys = [(0.0, -1.5), (2.8, -3.5), (4.4, -3.5), (6.0, 0.0), (7.6, -0.5), (10.0, -1.5)]
        dz_keys = [(0.0, 0.0), (2.8, 0.3), (4.4, 0.3), (6.0, -0.5), (7.6, -0.4), (10.0, 0.0)]
    strike = gb_strike_pitch(high, GB_TURN[high], mark)
    c = la.clip(name, length, True, look=('citizen', 6 if high else 4), station_=name, events=[('strike', hit)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((-50, -6), (95, -2), (30, -10)),
                pan=(0.0, -4.0 if high else 4.0, -GB_D / 2))
    holds = {'right_arm': la.Holds(), 'left_arm': la.Holds(), 'off': la.Holds()}
    for t in la.every(length):
        yaw = gb_curve(yaw_keys, t, length)
        pitch = strike + gb_curve(bend_keys, t, length)
        breath = 0.0 if kind == 'alarm' else math.sin(2 * math.pi * t / length)
        fr = gb_frame(high, pitch + 0.6 * breath, yaw, dz=gb_curve(dz_keys, t, length))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=GB_FEET[high])
        # the right arm, from beat to beat
        _, joint = ta.chain_frame('right_arm', **fr)
        d = unit(mark - joint)
        palm = unit(U - float(np.dot(U, d)) * d)
        hitp = reach_arm(holds['right_arm'], 'right_arm', fr, mark, palm=palm, palm_w=0.3, slack=0.8, limit=1.6)
        offp = reach_arm(holds['off'], 'right_arm', fr, mark + GB_OFF[high], palm=palm, palm_w=0.3, slack=0.8, limit=1.6)

        def pose(what):
            if what == 'hit':
                return hitp
            if what == 'off':
                return offp
            if what == 'mid':
                return mix(offp, pose('wind'), 0.45)
            key = (high, 'alarm_wind' if kind == 'alarm' and what == 'wind' else what)
            fwd, out = GB_SWING[key]
            return la.arm_at(fr, 'right_arm', fwd, out), np.zeros(3)
        i = max(k for k in range(len(beats)) if beats[k][0] <= t + 1e-9)
        j = min(i + 1, len(beats) - 1)
        (t0, a), (t1, b) = beats[i], beats[j]
        u = 0.0 if t1 <= t0 else (t - t0) / (t1 - t0)
        u = u * u if b == 'hit' else smooth(u)
        set_arm(c, 'right_arm', t, mix(pose(a), pose(b), u))
        # the left arm hanging out from the side, swung against the strike
        back = gb_curve(bend_keys, t, length) / 6.0
        set_arm(c, 'left_arm', t, (la.hang('left_arm', fr, out=12.0 if high else 9.0, fwd=(6.0 if high else 14.0) - 9.0 * back), np.zeros(3)))
        c.key('head', t, head_at(fr, middle + np.array([3.0 if high else 0.5, 0.0, 0.0])), ease='linear')
    c.mode('head', 'set')
    seal(c)
    if name != 'ring_alarm':
        la.EXTRA[name] = (length, True)         # RING_BELL's variants at a great bell, and RING_ALARM's for a bell hung up
    return c, body, station, strike


def gb_make(name, high, kind):
    """Builds [name], the hand's mark moved until it just meets the bell's body at the strike, and checks it."""
    length, hit = GB_RING if kind == 'ring' else GB_ALARM
    shift = 0.0
    for _ in range(4):
        GB_MARK[high][2] = -GB_D + 3.0 + shift
        c, body, station, strike = gb_clip(name, high, kind)
        touch = gb_touch(c, hit, body)
        if abs(touch - 0.05) < 0.08:
            break
        shift += touch - 0.05
    print(f'   {name}: bent {strike:.1f} degrees at the strike, the hand\'s mark {GB_MARK[high][2] + GB_D:.2f} px off the bell\'s middle')
    check(c, f'the hand meets the bell at the strike (t={hit:g}: how deep, or short)', (gb_touch(c, hit, body),), (0.1,), tol=0.25)
    times = [i * 0.5 for i in range(2 * length + 1)]
    boxes = station()
    keep_out(c, times, boxes, 'bell and its frame', parts=('head', 'body', 'left_arm', 'right_leg', 'left_leg'), tol=0.6)
    keep_out(c, [t for t in times if abs(t - hit) > 0.6], boxes, 'bell but at the strike', parts=('right_arm',), tol=0.6)
    below_ground(c, times)
    clear_hands(c, times)
    for k in (0.0, hit, length / 2.0):
        for side, (x, z) in GB_FEET[high].items():
            check(c, f'{side.replace("_", " ")} foot planted (t={k:g})', la.leg_end(c, k, side), (x, 24.0, z), tol=0.4)
    return c


def ring_great_bell():
    return gb_make('ring_great_bell', False, 'ring')


def ring_great_bell_high():
    return gb_make('ring_great_bell_high', True, 'ring')


def ring_alarm():
    return gb_make('ring_alarm', False, 'alarm')


def ring_alarm_high():
    return gb_make('ring_alarm_high', True, 'alarm')


# ================================================================== the crier's scroll read over

# ---- read_scroll (180, loop): the crier reading the news over to themselves from the scroll before the cry (Crier: until the
# hour), as announce holds it up: the scroll in the left hand, the head bowed to it, the eyes going along a line and back to the
# next (five lines, RS_LINE ticks each), a look up from it about the square with the lips going over a line, the right hand
# beating it out twice, a nod to it; back down to the scroll and three lines more; the scroll lifted a little to read the foot of
# it, and round again. The right hand otherwise hangs; the scroll shows all the while (tools_: the scroll), as it does in announce
# after it.
RS_LEN = 180
RS_LINE = 14.0
RS_READ = [(0.0, 5), (122.0, 3)]                # the reading spells: from, lines
RS_UP = [(0, 0.0), (70.0, 0.0), (77.0, 1.0), (110.0, 1.0), (117.0, 0.0), (180, 0.0)]       # 1: looking up from the scroll
RS_HY = [(0, 0.0), (72.0, 0.0), (80.0, -16.0), (88.0, -10.0), (96.0, 14.0), (104.0, 18.0), (110.0, 4.0), (117.0, 0.0), (180, 0.0)]
RS_BEATS = (90.0, 97.0)                         # the right hand's beats as the line is gone over
RS_NOD = 105.0
RS_LIFT = [(0, 0.0), (164.0, 0.0), (169.0, 1.0), (176.0, 1.0), (180, 0.0)]     # the scroll lifted to read its foot


def rs_scan(t):
    """The eyes along the lines: (yaw, pitch) off the reading look, a line read left to right and the eyes back to the next."""
    for start, lines in RS_READ:
        if start <= t < start + lines * RS_LINE:
            k = int((t - start) // RS_LINE)
            u = (t - start - k * RS_LINE) / RS_LINE
            along = smooth(u / 0.82) if u < 0.82 else 1.0 - smooth((u - 0.82) / 0.18)
            down = k + (smooth((u - 0.82) / 0.18) if u >= 0.82 else 0.0)
            w = smooth((t - start) / 5.0) * smooth((start + lines * RS_LINE - t) / 5.0)     # in from the look and out to it
            return w * (-7.0 + 12.0 * along), w * (1.4 * down - 2.0)
    return 0.0, 0.0


def read_scroll():
    c = la.clip('read_scroll', RS_LEN, True, look=('citizen', 2), tools_=('scroll',),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((32, -12), (90, -2), (-30, -8)))
    for t in la.every(RS_LEN):
        up = smooth(K(RS_UP, t))
        lift = smooth(K(RS_LIFT, t))
        sy, sp = rs_scan(t)
        sy, sp = sy * (1.0 - up), sp * (1.0 - up)
        hy = K(RS_HY, t) * up
        nod = bump(t, RS_NOD - 3.0, RS_NOD + 4.0)
        fr = F_(qt=bend(3.0 - 1.0 * up + 0.8 * nod, 0.15 * hy, 0.0))
        key_frame(c, t, fr, root=False, ease='linear')
        c.key('left_arm', t, qmul(la.Q_READ, qeuler(x=-2.0 * up - 4.0 * lift)), ease='linear')
        beat = sum(bump(t, b - 3.0, b + 3.0) for b in RS_BEATS)
        set_arm(c, 'right_arm', t, (la.hang('right_arm', fr, out=6.0, fwd=3.0 + 4.0 * up + 22.0 * beat), np.zeros(3)))
        # reading: the head bowed to the scroll, along its lines; looking up: over the square, and a nod
        c.key('head', t, qeuler(x=24.0 * (1.0 - up) - 4.0 * up + sp - 4.0 * lift + 9.0 * nod, y=-12.0 * (1.0 - up) + sy + hy),
              ease='linear')
    c.mode('head', 'add')
    seal(c)
    # the scroll hangs upright, its writing to the reader, as announce has it
    for k in (6.0, 90.0, 140.0, 172.0):
        sheet_down = ta.fk(c, k, 'scroll', (0.0, 8.0, 0.0)) - ta.fk(c, k, 'scroll', (0.0, 0.0, 0.0))
        check(c, f'scroll hanging straight (t={k:g}, the sheet\'s bottom under its top)', sheet_down / 8.0, D, tol=0.12)
    clear_hands(c, [i * 0.5 for i in range(2 * RS_LEN)])
    return c


# ================================================================== the registry

VILLAGE_CLIPS = ['bolt_door', 'lay_candle', 'cradle', 'read_scroll', 'ring_alarm']
MAKERS = [('bolt_door', bolt_door), ('lay_candle', lay_candle), ('cradle', cradle), ('carry_swaddle', carry_swaddle),
          ('ring_great_bell', ring_great_bell), ('ring_great_bell_high', ring_great_bell_high), ('ring_alarm', ring_alarm),
          ('ring_alarm_high', ring_alarm_high), ('read_scroll', read_scroll)]
NEEDS = {}
BUILT = {}
STATION_BOXES = {'bolt_door': bd_station, 'lay_candle': lc_station, 'ring_great_bell': gb_station(False),
                 'ring_great_bell_high': gb_station(True), 'ring_alarm': gb_station(False), 'ring_alarm_high': gb_station(True)}
VILLAGE_GLOW = ['candle_flame']
BD_KEEPER = (-8.6, BD_KNOB_Y, BD_Z + 0.4)       # the keeper on the frame, where the bolt goes home: 1 px off the door's edge


def village_moment_specs():
    """Where the villages' moments happen (living_anims' moment_specs forms)."""
    return {
        'bolt_door': {'bolt': ('point', BD_KEEPER)},
        'lay_candle': {'set': ('point', tuple(float(v) for v in LC_FOOT)), 'lit': ('point', tuple(float(v) for v in LC_WICK))},
        'cradle': {'coo': ('part', 'swaddle', tuple(lp.SWADDLE_FACE))},
        # the strike: on the bell's near face, where the hand meets it (the bell's own stroke is the server's)
        'ring_great_bell': {'strike': ('point', tuple(float(v) for v in GB_MARK[False]))},
        'ring_great_bell_high': {'strike': ('point', tuple(float(v) for v in GB_MARK[True]))},
        'ring_alarm': {'strike': ('point', tuple(float(v) for v in GB_MARK[False]))},
        'ring_alarm_high': {'strike': ('point', tuple(float(v) for v in GB_MARK[True]))},
    }


def build(living, only=None):
    """Builds the villages' clips on living_anims' kit ([living]: the module), and teaches it their stations, glows and
    moments. [only]: just these (the quick previews)."""
    global la
    la = living
    if wc.la is None:
        wc.la = living
    if BUILT:
        return
    la.STATIONS.update(STATION_BOXES)
    want = None
    if only:
        want = set(only)
        for _ in range(3):
            for name in list(want):
                want.update(NEEDS.get(name, ()))
    for name, make in MAKERS:
        if want is not None and name not in want:
            continue
        make()
    for part in VILLAGE_GLOW:
        if part not in la.GLOW_PROPS:
            la.GLOW_PROPS.append(part)
    specs_ = la.moment_specs

    def moment_specs():
        out = specs_()
        out.update(village_moment_specs())
        return out
    la.moment_specs = moment_specs
    BUILT['done'] = True
    la.WANTED.extend(n for n in VILLAGE_CLIPS if n not in la.WANTED)


# ================================================================== the previews

# python3 -B village_clips.py <out dir> [group ...]: the GIFs of the clips (living_anims builds them first), as the
# townsfolk previews are made: a 2x2 grid for each pair of clips, each clip from two views, at the game's pace (a frame a
# tick; a one-shot is held on its last frame for GIF_REST ticks and goes again). The scenes' moments are named on the
# frames they fall on.
GIF_GROUPS = {
    'bolt_door_lay_candle': ['bolt_door', 'lay_candle'],
    'cradle_carry_swaddle': ['cradle', 'carry_swaddle'],
    'great_bell': ['ring_great_bell', 'ring_great_bell_high'],
    'alarm': ['ring_alarm', 'ring_alarm_high'],
    'read_scroll': ['read_scroll'],
}
GIF_CELL = (240, 300)
GIF_REST = 10
GIF_SS = 2                                      # drawn at twice the size and scaled down: smooth edges
GIF_VIEWS = {'bolt_door': ((150, -8), (100, -4)), 'lay_candle': ((-55, 8), (115, 8)), 'cradle': ((25, -14), (-35, -10)),
             'carry_swaddle': ((30, -12), (90, -4)), 'ring_great_bell': ((-30, 25), (-95, -4)), 'ring_great_bell_high': ((-30, 10), (-95, -4)),
             'ring_alarm': ((-30, 25), (-95, -4)), 'ring_alarm_high': ((-30, 10), (-95, -4)), 'read_scroll': ((32, -12), (-30, -8))}
GIF_FRAME = {'bolt_door': (4.8, 7.0), 'lay_candle': (4.7, 9.0), 'cradle': (7.0, 6.0), 'carry_swaddle': (5.6, 8.0),
             'ring_great_bell': (4.6, 8.0), 'ring_great_bell_high': (4.2, 6.0), 'ring_alarm': (4.6, 8.0), 'ring_alarm_high': (4.2, 6.0)}  # zoom, the figure's middle
GIF_PACE = {}
GIF_MARKS = {'bolt_door': [('the door shuts', BD_SLAM), ('bolted', BD_BOLT_T)], 'lay_candle': [('set', LC_SET), ('lit', LC_LIT)],
             'cradle': [('coo', CR_COO[0]), ('coo', CR_COO[1])], 'ring_great_bell': [('strike', GB_RING[1])],
             'ring_great_bell_high': [('strike', GB_RING[1])], 'ring_alarm': [('strike', GB_ALARM[1])],
             'ring_alarm_high': [('strike', GB_ALARM[1])], 'read_scroll': [('beat', RS_BEATS[0]), ('beat', RS_BEATS[1])]}


def gif_cell(c, t, view):
    """[c] at [t] from [view] as one cell of a preview."""
    W, H = GIF_CELL
    info = ta.INFO[c.name]
    look = ta.look_for(info['look'])
    zoom, middle = GIF_FRAME.get(c.name, (5.6, 8.0))
    project = ta.ortho(view[0], view[1], zoom * GIF_SS, (W * GIF_SS, H * GIF_SS), middle)
    if info.get('pan') is not None:
        project = (lambda base_, pan_: (lambda p: base_(np.asarray(p, float) - pan_)))(project, np.asarray(info['pan'], float))
    img, _ = ta.render_cell(look, c, t, project, (W * GIF_SS, H * GIF_SS), info['walking'])
    return img.resize((W, H), Image.LANCZOS)


def gif_time(c, k):
    """The clip's tick at frame [k] of its preview."""
    if ta.INFO[c.name]['loop']:
        return float(k % int(c.length))
    return float(min(k % (int(c.length) + GIF_REST), c.length))


def make_gif(key, out_dir):
    """The preview [key] of GIF_GROUPS: a 2x2 grid, a clip to a row, its two views across; returns its path."""
    names = GIF_GROUPS[key]
    clips_ = [la.getc(n) for n in names]
    cycles = [int(c.length) if ta.INFO[c.name]['loop'] else int(c.length) + GIF_REST for c in clips_]
    total = 1
    for n in cycles:
        total = total * n // math.gcd(total, n)
    W, H = GIF_CELL
    font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 14)
    cells = {}
    for c, n in zip(clips_, cycles):
        for j, view in enumerate(GIF_VIEWS[c.name]):
            for k in range(n):
                cells[(c.name, j, k)] = gif_cell(c, gif_time(c, k), view)
    frames = []
    for k in range(total):
        sheet = Image.new('RGB', (W * 2, H * 2), (30, 32, 40))
        d = ImageDraw.Draw(sheet)
        for i, (c, n) in enumerate(zip(clips_, cycles)):
            t = gif_time(c, k % n)
            for j in range(2):
                x, y = j * W, i * H
                sheet.paste(cells[(c.name, j, k % n)], (x, y))
                label = f'{c.name}  {int(c.length)}t {"loop" if ta.INFO[c.name]["loop"] else "once"}   t={t:g}'
                mark = [m for m, at in GIF_MARKS.get(c.name, []) if abs(at - t) < 0.75]
                d.text((x + 7, y + 7), label, font=font, fill=(0, 0, 0))
                d.text((x + 6, y + 6), label, font=font, fill=(255, 255, 160) if mark else (245, 240, 225))
                if mark:
                    d.text((x + 7, y + 25), f'{mark[0]}!', font=font, fill=(0, 0, 0))
                    d.text((x + 6, y + 24), f'{mark[0]}!', font=font, fill=(255, 255, 160))
        d.line([(W, 0), (W, 2 * H)], fill=(30, 32, 40), width=2)
        d.line([(0, H), (2 * W, H)], fill=(30, 32, 40), width=2)
        frames.append(sheet)
    # one palette for the whole GIF, so the colours do not flicker from frame to frame
    probe = Image.new('RGB', (2 * W, 2 * H * 4))
    for n_, k in enumerate(range(0, total, max(1, total // 4))):
        if n_ < 4:
            probe.paste(frames[k], (0, n_ * 2 * H))
    pal = probe.quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    path = os.path.join(out_dir, f'village_clips_{key}.gif')
    q[0].save(path, save_all=True, append_images=q[1:], duration=50, loop=0, optimize=False, disposal=1)
    return path


def gifs(out_dir, only=None):
    """The previews (all of GIF_GROUPS, or the keys in [only]) written to [out_dir]; their paths."""
    os.makedirs(out_dir, exist_ok=True)
    return [make_gif(key, out_dir) for key in GIF_GROUPS if not only or key in only]


if __name__ == '__main__':
    out = sys.argv[1]
    import living_anims as la_          # noqa: F401  (builds every clip, these among them)
    import village_clips as vc_         # (the copy living_anims built)
    for p in vc_.gifs(out, set(sys.argv[2:])):
        print('wrote', p)
