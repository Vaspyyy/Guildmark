"""
The trade's seven clips (living-world/trader/design.md, section 10 and the X4 row of 12.15), on the Living World's kit:

    STAMP       20 once    a seal pressed into wax on a counter: the stamp raised, slammed down, held and twisted, lifted
    CHECK_LOAD  30 once    at a covered cart's tail: the canvas's corner lifted, a look under, the goods felt, the canvas let fall
    LOAD_PACK   40 once    a laden basket heaved up to a pack animal's flank and tipped into its pannier
    HITCH       50 once    a horse called back into the shafts with the flat of the hand, a trace clipped on and tugged
    WEIGH       60 once    a sack lifted from the floor onto a scale's pan, the beam watched and read, the figure written
    HALT        25 once    the hand flung up for "Hold!", braced, and let fall
    RUMMAGE     60 loop    head bent over a satchel held open, both hands working through it

Their names, lengths and loop flags are TownRole.kt's (Activity); living_anims.py imports this module at its end and calls
build(), which makes the clips, so its verify() and writer take them like the rest. `python3 -B trade_clips.py <out dir>`
draws the GIF previews (2x2 grids) once living_anims has built everything.

Clip space as every townsfolk clip's: pixels, y down, facing -z, the right hand at -x, the feet at y 24. A clip that acts
on something beside the townsperson (the counter, the cart's tail, a pack animal's flank, the wagon's swingletree, the
scale) says in its comment where that stands (a block is 16 px), and the test guide says where the code must put the
townsperson for it to line up.
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
from townsfolk_anims import (qeuler, qslerp, unit, F, U, D, R, L, T, Rq, F_, bend, key_frame, check, seal, smooth, bump,
                             GRIP, PIVOTS, torso_to_world, sink_for, gaze_head)
import works_clips as wc
from works_clips import rest_arm, mix, lifted, set_arm, reach_arm, stand, joins_idle, stepping, FEET
import living_props as lp
import street_clips as sc

la = None           # living_anims, handed to build()

# ================================================================== the pieces these clips share


def K(keys, t, loop=None):
    return la.K(keys, t, loop)


def KV(keys, t, loop=None):
    return la.KV(keys, t, loop)


def bx(lo, hi, col, lit=True):
    return (tuple(float(x) for x in lo), tuple(float(x) for x in hi), col, lit)


def head_level(fr, x=0.0, y=0.0, z=0.0):
    """A 'set' head key holding the head [x] degrees down, [y] to the left and [z] tipped left of the root's level, whatever
    the torso does."""
    return ta.qmul(ta.qinv(ta.qmul(fr['qr'], fr['qt'])), qeuler(x=x, y=y, z=z))


def look_at(fr, target, w=1.0, x=0.0, y=0.0, z=0.0):
    """A 'set' head key [w] of the way from a level head ([x], [y], [z] as head_level takes them) to one looking at
    world [target]."""
    base = head_level(fr, x, y, z)
    if w <= 1e-6:
        return base
    return qslerp(base, gaze_head(fr, target), w) if w < 1.0 else gaze_head(fr, target)


# the parts' blocks in their own spaces; for an arm only its hand, the block's last 4 px
BOXES = {'body': ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)), 'head': ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0)),
         'right_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)), 'left_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)),
         'right_arm': ((-3.0, 6.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, 6.0, -2.0), (3.0, 10.0, 2.0))}


def _part_points(part, n=4):
    lo, hi = BOXES[part]
    return [np.array([lo[0] + (hi[0] - lo[0]) * i / n, lo[1] + (hi[1] - lo[1]) * j / n, lo[2] + (hi[2] - lo[2]) * k / n])
            for i in range(n + 1) for j in range(n + 1) for k in range(n + 1)]


def _mats(c, t, walking=False):
    look = ta.look_for(ta.INFO[c.name]['look'])
    return ta.part_matrices(look, ta.pose_at(c, t, ta.walk_base(t, speed=ta.walk_speed(c, walking), age=t)))


def deepest(c, t, side, into=('body', 'head')):
    """How deep (px) the hand of [side]'s arm goes into the parts [into] at [t] of [c] (0: clear). Only the hand: a block
    arm swung in front of the chest or up to the face always sinks its upper half into the torso's corner or the jaw's,
    as vanilla's own arms do, but a hand inside the chest or the face would show."""
    mats = _mats(c, t)
    world = [mats[side][:3, :3] @ p + mats[side][:3, 3] for p in _part_points(side)]
    worst = 0.0
    for name in into:
        blo, bhi = BOXES[name]
        Mi = np.linalg.inv(mats[name])
        for w in world:
            p = Mi[:3, :3] @ w + Mi[:3, 3]
            worst = max(worst, min(p[0] - blo[0], bhi[0] - p[0], p[1] - blo[1], bhi[1] - p[1], p[2] - blo[2], bhi[2] - p[2]))
    return worst


def deepest_in(c, t, part, boxes):
    """How deep (px) [part] of [c] goes at [t] into any of a station's [boxes] ((lo, hi, ...) in the clip's space); for an
    arm its hand only. 0 when it is clear."""
    mats = _mats(c, t)
    worst = 0.0
    for p in _part_points(part):
        w = mats[part][:3, :3] @ p + mats[part][:3, 3]
        for b in boxes:
            blo, bhi = np.asarray(b[0], float), np.asarray(b[1], float)
            inside = min(float(np.min(w - blo)), float(np.min(bhi - w)))
            if inside > 0.0:
                worst = max(worst, inside)
    return worst


def clear_of(c, side, times, into=('body', 'head'), tol=0.8):
    """Checks [side]'s hand keeps out of [into] (to within [tol] px) all through [times]."""
    worst, at = max((deepest(c, t, side, into), t) for t in times)
    hand = side.replace('_arm', ' hand')
    check(c, f'{hand} clear of the {" and ".join(n.replace("_", " ") for n in into)} (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


def clear_of_station(c, boxes, times, parts=(('right_arm', 'right hand', 0.8), ('left_arm', 'left hand', 0.8),
                                             ('head', 'head', 0.6), ('body', 'body', 0.6),
                                             ('right_leg', 'right leg', 0.6), ('left_leg', 'left leg', 0.6)), what='the station'):
    for part, label, tol in parts:
        worst, at = max((deepest_in(c, t, part, boxes), t) for t in times)
        check(c, f'{label} clear of {what} (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


def feet_down(c, times, feet=FEET, floor=24.0, tol=0.4):
    for t in times:
        for side, (x, z) in feet.items():
            check(c, f'{side} foot planted (t={t:g})', la.leg_end(c, t, side), (x, floor, z), tol=tol)


def tool_at(name, p):
    """A point of a tool (its own part space) in its arm's space."""
    return ta.tool_point(name, np.asarray(p, float))


def rot_x(deg):
    t = math.radians(deg)
    return np.array([[1.0, 0.0, 0.0], [0.0, math.cos(t), -math.sin(t)], [0.0, math.sin(t), math.cos(t)]])


def rot_y(deg):
    t = math.radians(deg)
    return np.array([[math.cos(t), 0.0, math.sin(t)], [0.0, 1.0, 0.0], [-math.sin(t), 0.0, math.cos(t)]])


def rot_z(deg):
    t = math.radians(deg)
    return np.array([[math.cos(t), -math.sin(t), 0.0], [math.sin(t), math.cos(t), 0.0], [0.0, 0.0, 1.0]])


# ================================================================== stamp

# ---- stamp (20, once): at a counter a block ahead (its top 16 px up, its near face 8 px ahead), a seal pressed into the
# wax on a sheet that lies on it: the press point ST_PP (13 px ahead of the townsperson, 2.5 px to their right, on the
# counter top). The seal is in the right fist from the first tick (its track holds it pointing down whichever way the arm
# reaches), the left hand comes to pin the sheet. The stamp is raised beside the head (the body nearly upright, the
# wind-up), slammed down in a tick and a half as the body drops on it (press: the scene's seal sound and wax splash fall
# here, as WarrantScene.seal does on its tick 9), held and ground a little to one side and back, peeled off (lift) and
# turned to the eye to look at the print, and let down: the hands home.
ST_LEN = 20
ST_PRESS, ST_LIFT = 9.0, 13.5
ST_FACE_Y = lp.STAMP_FACE[1]                    # the seal's face this far under the grip
ST_PP = np.array([-2.5, 8.0, -14.0])            # the seal's face on the wax (clip space): the counter top 16 px up
ST_PIN = np.array([3.6, 6.6, -13.0])            # the left fist on the sheet's corner (the sheet 0.3 px thick)
ST_REACH = [(0, 0.0), (2.0, 0.0), (6.0, 1.0), (16.4, 1.0), (19.4, 0.0), (20, 0.0)]       # the right hand out from rest to the stamp's path
ST_PINNED = [(0, 0.0), (3.0, 0.0), (6.5, 1.0), (15.0, 1.0), (18.5, 0.0), (20, 0.0)]
ST_PITCH = [(0, 0.0), (2.5, 7.0), (6.0, 3.0), (7.4, 2.0), (8.5, 15.0), (ST_PRESS, 22.0), (10.2, 21.0), (13.0, 22.5),
            (15.0, 14.0), (17.0, 7.0), (19.0, 1.5), (20, 0.0)]
ST_DIP = [(0, 0.0), (3.0, 0.3), (6.0, 0.1), (7.4, 0.0), (ST_PRESS, 1.3), (12.0, 1.0), (15.0, 0.4), (20, 0.0)]
ST_ROOTZ = [(0, 0.0), (3.0, 0.4), (7.4, 0.2), (ST_PRESS, 0.9), (13.0, 0.8), (17.0, 0.3), (20, 0.0)]
# the seal's face over the sheet, tick by tick (the press holds it on the wax from ST_PRESS to the peel): up on the right
# of the head (the arm beside it, never across the face), then down to the wax
ST_PATH = [(2.0, (-5.6, 4.0, -5.0)), (4.5, (-7.2, 2.0, -8.0)), (6.5, (-7.4, 1.2, -9.6)), (7.4, (-7.2, 1.0, -9.8)),
           (8.0, (-6.4, 3.2, -12.2)), (8.6, (-3.2, 6.8, -13.2)), (ST_PRESS, ST_PP + np.array([0.0, 0.2, 0.0])),
           (ST_LIFT, ST_PP + np.array([0.0, 0.2, 0.0])), (14.6, ST_PP + np.array([-0.6, -2.4, 0.0])),
           (16.0, (-5.0, 0.4, -9.8)), (17.6, (-5.6, 2.4, -7.0)), (19.2, (-5.4, 4.0, -5.0))]


def st_face(t):
    """Where the seal's face is meant to be at [t] (clip space)."""
    return KV(ST_PATH, max(2.0, min(19.2, t)))


def st_twist(t):
    """The wrist's grind on the wax (degrees about the vertical): a turn one way and back, from the press."""
    return 22.0 * bump(t, ST_PRESS, ST_LIFT + 0.5) * math.sin(2 * math.pi * (t - ST_PRESS) / 4.6)


def st_tilt(t):
    """How far the seal is turned back toward the eye (degrees about x) as it is lifted to be looked at."""
    return 62.0 * smooth(K([(0, 0.0), (14.4, 0.0), (15.8, 1.0), (17.2, 1.0), (18.8, 0.0), (20, 0.0)], t))


def _stamp_track(c):
    """The stamp part's rotation and pivot: the grip, the seal hanging straight down from it (the arm's own turns take no
    part), tilted back toward the eye while it is looked at and twisted about the vertical on the wax."""
    def fn(t, pivot):
        grip = (wc.part_M(c, t, 'right_arm') @ np.array([*GRIP['right_arm'], 1.0]))[:3]
        return rot_y(st_twist(t)) @ rot_x(st_tilt(t)), grip, None
    return fn


def stamp():
    c = la.clip('stamp', ST_LEN, False, look=('citizen', 3), tools_=('stamp',), station_='stamp_desk',
                events=[('press', ST_PRESS), ('lift', ST_LIFT)],
                traces=[('stamp', lp.STAMP_FACE, (255, 200, 120))], views=((50, -14), (90, -2), (-50, -14)))
    holds = la.Holds()
    for t in la.every(ST_LEN):
        pitch = K(ST_PITCH, t)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(FEET, z=K(ST_ROOTZ, t), dip=K(ST_DIP, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        w = smooth(K(ST_REACH, t))
        rest = rest_arm('right_arm', fr)
        if w > 1e-4:
            tilt = math.radians(st_tilt(t))
            grip = st_face(t) - ST_FACE_Y * np.array([0.0, math.cos(tilt), math.sin(tilt)])
            pose = reach_arm(holds, 'right_arm', fr, grip, palm=rot_y(st_twist(t)) @ L, palm_w=0.3, slack=0.8, limit=1.8)
            pose = mix(rest, pose, w) if w < 1.0 else pose
        else:
            pose = rest
        set_arm(c, 'right_arm', t, pose)
        pin = smooth(K(ST_PINNED, t))
        lrest = rest_arm('left_arm', fr)
        if pin > 1e-4:
            lp_ = reach_arm(holds, 'left_arm', fr, ST_PIN, palm=D, palm_w=0.4, slack=0.6, limit=1.6)
            lrest = mix(lrest, lp_, pin) if pin < 1.0 else lp_
        set_arm(c, 'left_arm', t, lrest)
        # the head: down to the wax as the stamp is raised, a nod into the slam, to the lifted seal and up
        gaze = smooth(K([(0, 0.0), (1.5, 0.0), (4.0, 1.0), (14.0, 1.0), (15.5, 0.0), (20, 0.0)], t))
        seal_look = smooth(K([(0, 0.0), (14.0, 0.0), (15.6, 1.0), (17.0, 1.0), (18.6, 0.0), (20, 0.0)], t))
        target = ST_PP * (1.0 - seal_look) + st_face(t) * seal_look
        nod = 5.0 * bump(t, ST_PRESS - 0.6, ST_PRESS + 3.0)
        c.key('head', t, look_at(fr, target, gaze, x=nod), ease='linear')
    c.mode('head', 'set')
    la.TRACKS['stamp'] = {'stamp': _stamp_track(c)}
    check(c, f'the seal on the wax (t={ST_PRESS:g})', ta.fk(c, ST_PRESS, 'stamp', lp.STAMP_FACE), ST_PP, tol=0.7)
    check(c, 'the seal still on the wax (t=11.5)', ta.fk(c, 11.5, 'stamp', lp.STAMP_FACE), ST_PP, tol=0.7)
    times = [i * 0.5 for i in range(2 * ST_LEN + 1)]
    clear_of_station(c, la.station_boxes('stamp_desk'), times, what='the counter')
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.0)
    joins_idle(c)
    return c


def _stamp_desk():
    """The counter a block ahead (top 16 px up, near face 8 px ahead) with a sheet lying on it and the wax on the sheet."""
    wood, wood_d = (150, 112, 70), (118, 86, 54)
    return [bx((-8, 8, -24), (8, 24, -8), wood),
            bx((-8, 8, -24), (8, 8.7, -8), wood_d),
            bx((-6.5, 7.7, -18.5), (4.2, 8.0, -9.5), (238, 228, 196)),
            bx((-4.6, 7.5, -16.2), (-0.4, 7.9, -11.8), (150, 26, 30)),
            bx((5.0, 6.0, -22.0), (7.2, 8.0, -19.8), (44, 40, 76)),          # an ink pot at the back
            ]


# ================================================================== check_load

# ---- check_load (30, once): at the tail of the covered handcart a block ahead (it rests tipped forward on its shafts, as
# street_clips' pack has it: its tail board 13.4 px ahead of the feet's start, shoulder high, the hoop's bar over it).
# The tail's curtain is the canvas_flap prop, CANVAS_W x CANVAS_H, hung from the hoop's bar and pinned at the tail's right
# end (CL_PIN): the world leaves the tail's own curtain out while this plays, and the prop at rest lies exactly where it
# was. The townsperson stands CL_SIDE px right of the tail's middle. They step in (to 1 px ahead of the start's feet), the
# right hand down on the board's right corner and the left taking the curtain's ring (its lower left corner); a second
# step in as the corner is drawn up and aside (lift: the curtain swings about its pin, tips out toward the hand and
# gathers), the head ducked in over the board to look the load over, the right hand in over the bed to press two sacks
# (feel), a nod, the corner let go (drop: it falls, bounces twice and lies), two pats of the right hand on the cloth by
# the tail's right edge (pat), a step back and the hands home. An arm is a block 10 px long, so each hand's mark is
# about 10 px from its shoulder in the pose it is reached in (the two stances, A and B, are chosen to make it so).
CL_LEN = 30
CL_LIFT, CL_DROP = 7.0, 19.0
CL_FEEL = (10.2, 12.8)
CL_PATS = (21.6, 23.2)
CL_PIN_C = (-5.5, sc.hc.SIDE_TOP - lp.CANVAS_H, sc.hc.BED_BACK + 0.9)       # cart space: the patch's pinned corner
CL_PIN = sc.cart_pt(*CL_PIN_C)
CL_R0 = sc.cart_R()                                                          # the patch at rest lies in the tail's plane
CL_RING = np.array([lp.CANVAS_W - 1.2, lp.CANVAS_H - 1.0, 0.0])             # patch space: where the hand pinches
CL_RING0 = CL_PIN + CL_R0 @ CL_RING                                          # the ring lying on the tail
CL_RING1 = np.array([6.2, -4.4, -14.8])                                      # ... and held up: drawn up and in, a little out
CL_SIDE = -1.5                                                               # stands this far to the right of the tail's middle
CL_STEPS = [(0.5, 3.0, 'left_leg', (1.9 + CL_SIDE, -1.0)), (1.8, 4.2, 'right_leg', (-1.9 + CL_SIDE, -1.0)),
            (6.2, 8.6, 'left_leg', (1.9 + CL_SIDE, -3.2)), (7.2, 9.6, 'right_leg', (-1.9 + CL_SIDE, -3.2)),
            (23.8, 26.6, 'right_leg', (-1.9, 0.0)), (25.0, 27.8, 'left_leg', (1.9, 0.0))]
CL_P = [(0, 0.0), (1.5, 5.0), (3.5, 14.0), (6.4, 14.0), (9.0, 18.5), (15.5, 18.5), (17.5, 18.0), (19.0, 16.0), (21.0, 14.0),
        (22.5, 12.0), (24.0, 9.0), (26.5, 4.0), (28.5, 1.0), (30, 0.0)]
CL_YAW = [(0, 0.0), (5.0, -4.0), (9.0, -7.0), (15.0, -5.0), (19.0, 0.0), (30, 0.0)]
CL_DIP = [(0, 0.0), (3.5, 0.8), (6.4, 0.8), (9.0, 0.9), (15.5, 0.9), (20.0, 0.6), (26.0, 0.1), (30, 0.0)]
CL_LEFT = [(0, 0.0), (3.0, 0.0), (6.4, 1.0), (CL_DROP, 1.0), (CL_DROP + 1.6, 1.0), (24.0, 0.0), (30, 0.0)]
CL_RIGHT = [(0, 0.0), (2.0, 0.0), (5.0, 1.0), (25.0, 1.0), (28.5, 0.0), (30, 0.0)]


def _cp(x, y, z):
    return sc.cart_pt(x, y, z)


CL_BOARD = _cp(-7.2, sc.hc.SIDE_TOP - 0.3, sc.hc.BED_BACK + 0.4)             # the right hand on the board's top, at the cart's right corner
CL_OVER = _cp(-6.0, -10.8, 7.4)                                               # ... lifted off it and out over the bed, to go in
CL_SACK1, CL_SACK2 = (-0.8, 7.6), (1.0, 7.4)                                 # cart (x, z) of the two sacks' tops pressed
CL_TOP, CL_HOVER = -9.9, -11.4                                               # cart y of a sack top, and a hand over it
CL_PAT = _cp(-7.0, sc.hc.SIDE_TOP - 3.0, sc.hc.BED_BACK + 1.45)              # a pat on the cloth by the tail's right edge
CL_PAT_AWAY = CL_PAT + np.array([-0.4, -2.2, 1.8])
CL_RH = [(5.0, CL_BOARD), (7.6, CL_BOARD), (8.8, CL_OVER), (9.8, _cp(CL_SACK1[0], CL_HOVER, CL_SACK1[1])),
         (CL_FEEL[0], _cp(CL_SACK1[0], CL_TOP, CL_SACK1[1])), (11.3, _cp(CL_SACK1[0], CL_HOVER, CL_SACK1[1])),
         (12.0, _cp(CL_SACK2[0], CL_HOVER, CL_SACK2[1])), (CL_FEEL[1], _cp(CL_SACK2[0], CL_TOP, CL_SACK2[1])),
         (14.4, _cp(CL_SACK2[0], CL_HOVER, CL_SACK2[1])), (15.8, _cp(CL_SACK1[0], CL_HOVER, CL_SACK1[1] + 1.0)),
         (16.8, CL_OVER), (17.8, CL_BOARD), (CL_DROP, CL_BOARD), (20.6, CL_PAT_AWAY), (CL_PATS[0], CL_PAT), (22.4, CL_PAT_AWAY),
         (CL_PATS[1], CL_PAT), (24.2, CL_PAT_AWAY), (25.4, np.array([-7.6, 8.0, -7.0]))]
CL_SCAN = [(2.0, _cp(1.0, sc.hc.SIDE_TOP - 1.0, sc.hc.BED_BACK + 1.0)), (6.5, _cp(4.0, -10.5, 10.4)),
           (8.4, _cp(3.0, -10.0, 4.0)), (CL_FEEL[0], _cp(CL_SACK1[0], CL_TOP, CL_SACK1[1])),
           (11.5, _cp(1.0, -9.6, 4.0)), (CL_FEEL[1], _cp(CL_SACK2[0], CL_TOP, CL_SACK2[1])), (14.6, _cp(2.0, -10.0, -2.0)),
           (16.2, _cp(-1.0, -10.0, 3.0)), (17.6, _cp(0.0, -10.0, 10.5))]


def _cl_held(t):
    """How far the corner is lifted (0 lying, 1 held up) while it is being lifted and held: heaved with a little
    overshoot and a slow tremble in the hand."""
    x = min(1.0, max(0.0, (t - CL_LIFT) / 2.2))
    return smooth(x) + 0.07 * bump(t, CL_LIFT + 0.8, CL_LIFT + 4.5) \
        + 0.025 * math.sin(2 * math.pi * (t - CL_LIFT) / 3.3) * bump(t, CL_LIFT + 2.0, CL_DROP)


def cl_open(t):
    """How far the corner is lifted at [t]: up on the hand's pull, falling when it is let go (a fall that gathers speed),
    and two bounces off the board before it lies."""
    if t < CL_LIFT:
        return 0.0
    if t <= CL_DROP:
        return _cl_held(t)
    u0 = _cl_held(CL_DROP)
    s = t - CL_DROP
    if s < 1.3:
        return u0 * (1.0 - (s / 1.3) ** 2)
    if s < 3.0:
        x = (s - 1.3) / 1.7
        return 0.17 * 4.0 * x * (1.0 - x)
    if s < 4.0:
        x = (s - 3.0) / 1.0
        return 0.05 * 4.0 * x * (1.0 - x)
    return 0.0


def cl_fit(target):
    """The patch's turn (part space to clip space) and scale that put its ring on clip point [target], the pin staying put:
    swung about the pin in its own plane, tipped out of that plane toward the hand, and gathered toward the pin."""
    q = CL_R0.T @ (np.asarray(target, float) - CL_PIN)
    y1 = math.copysign(math.hypot(q[1], q[2]), q[1]) if abs(q[1]) > 1e-6 else math.hypot(q[1], q[2])
    phi = math.degrees(math.atan2(q[2], q[1])) if abs(y1) > 1e-6 and y1 == math.hypot(q[1], q[2]) else \
        math.degrees(math.atan2(-q[2], -q[1]))
    s = math.hypot(q[0], y1) / float(np.linalg.norm(CL_RING))
    theta = math.degrees(math.atan2(y1, q[0]) - math.atan2(CL_RING[1], CL_RING[0]))
    return CL_R0 @ rot_x(phi) @ rot_z(theta), (s, s, 1.0)


def cl_RS(t):
    """The patch's turn and scale at [t]."""
    u = cl_open(t)
    return cl_fit(CL_RING0 + (CL_RING1 - CL_RING0) * u)


def cl_ring(t):
    """Where the ring (the cloth's pinched corner) is at [t] (clip space)."""
    R, S = cl_RS(t)
    return CL_PIN + R @ (np.asarray(S) * CL_RING)


def cl_left(t):
    """Where the left fist is meant to be at [t]: on the ring while it holds the corner, then hanging a moment where it let
    go, drawn up and away."""
    if t <= CL_DROP:
        return cl_ring(t)
    return cl_ring(CL_DROP) + np.array([0.6, -1.2, 1.6]) * smooth((t - CL_DROP) / 1.4)


def _flap_track(c):
    return lambda t, pivot: (cl_RS(t)[0], CL_PIN, cl_RS(t)[1])


def check_load():
    c = la.clip('check_load', CL_LEN, False, look=('citizen', 3), tools_=('canvas_flap',), station_='cart_covered',
                events=[('lift', CL_LIFT)] + [('feel', k) for k in CL_FEEL] + [('drop', CL_DROP)] + [('pat', k) for k in CL_PATS],
                traces=[('canvas_flap', tuple(CL_RING), (230, 220, 180))], views=((90, -8), (140, -18), (-50, -20)), pan=(0.0, 0.0, -7.0))
    holds = la.Holds()
    for t in la.every(CL_LEN):
        pitch = K(CL_P, t)
        feet, lift = stepping(t, FEET, CL_STEPS, lift=1.2)
        fz = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
        fr = F_(qt=bend(pitch, K(CL_YAW, t), 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, 0.5 * (feet['right_leg'][0] + feet['left_leg'][0]), fz, dip=K(CL_DIP, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        # the left hand: out to the ring, holding the corner up, letting go
        w = smooth(K(CL_LEFT, t))
        rest = rest_arm('left_arm', fr)
        if w > 1e-4:
            pose = reach_arm(holds, 'left_arm', fr, cl_left(t), slack=0.8, limit=1.8)
            rest = mix(rest, pose, w) if w < 1.0 else pose
        set_arm(c, 'left_arm', t, rest)
        # the right hand: on the board, in under the cloth to press the sacks, then patting the tail
        w = smooth(K(CL_RIGHT, t))
        rest = rest_arm('right_arm', fr)
        if w > 1e-4:
            pose = reach_arm(holds, 'right_arm', fr, KV(CL_RH, max(5.0, min(25.4, t))), slack=0.8, limit=1.8)
            rest = mix(rest, pose, w) if w < 1.0 else pose
        set_arm(c, 'right_arm', t, rest)
        # the head: to the corner, ducked under it, over the load, a nod, to the cloth falling, up
        gaze = smooth(K([(0, 0.0), (1.5, 0.0), (3.5, 0.9), (17.0, 0.9), (20.0, 0.6), (23.0, 0.0), (30, 0.0)], t))
        target = KV(CL_SCAN, max(2.0, min(17.6, t)))
        if t > 17.6:
            target = KV([(17.6, CL_SCAN[-1][1]), (CL_DROP + 0.8, cl_ring(CL_DROP + 0.8)), (22.0, np.array([0.0, -6.0, -50.0]))],
                        max(17.6, min(22.0, t)))
        nod = 6.0 * bump(t, 16.4, 19.6)
        c.key('head', t, look_at(fr, target, gaze, x=nod), ease='linear')
    c.mode('head', 'set')
    la.TRACKS['check_load'] = {'canvas_flap': _flap_track(c)}
    for k in (7.4, 12.0, 18.6):
        check(c, f'left hand on the ring (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']), cl_ring(k), tol=1.0)
    for k in (6.0, 7.6):
        check(c, f'right hand on the board (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), CL_BOARD, tol=1.0)
    for k, p in ((CL_FEEL[0], _cp(CL_SACK1[0], CL_TOP, CL_SACK1[1])), (CL_FEEL[1], _cp(CL_SACK2[0], CL_TOP, CL_SACK2[1])),
                 (CL_PATS[0], CL_PAT), (CL_PATS[1], CL_PAT)):
        check(c, f'right hand at its mark (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), p, tol=1.0)
    times = [i * 0.5 for i in range(2 * CL_LEN + 1)]
    boxes = la.station_boxes('cart_covered')
    clear_of_station(c, boxes, times, parts=(('head', 'head', 0.8), ('body', 'body', 0.8), ('right_leg', 'right leg', 0.6),
                                             ('left_leg', 'left leg', 0.6)), what='the cart')
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.3)
    joins_idle(c)
    return c


def _cart_span(xs, ys, zs):
    pts = np.array([sc.cart_pt(x, y, z) for x in xs for y in ys for z in zs])
    return tuple(pts.min(axis=0)), tuple(pts.max(axis=0))


CANVAS_C, CANVAS_D = (178, 170, 140), (150, 142, 114)
SACK_C = (192, 170, 122)
CART_HOOP = (110, 78, 50)


def _cart_covered():
    """The handcart as _cart_tail draws it, cut away so the tail's work shows: three sacks on its bed, the hoop at the
    tail and a canvas roof over the front of the bed with its sides; the tail itself is left open for the canvas_flap prop
    (the tail's curtain) to hang across."""
    hc_ = sc.hc
    out = [b for b in sc._cart_tail() if b[2] != sc.WHEELC]       # the wheels left out: they hide the tail from the side
    for (x0, x1, z0, z1, top) in ((-5.6, -0.6, 1.0, 8.6, -9.8), (0.6, 5.6, 1.0, 8.6, -9.4), (-4.4, 4.4, -10.0, -1.0, -9.2)):
        out.append(bx(*_cart_span((x0, x1), (top, hc_.FLOOR_TOP), (z0, z1)), SACK_C))
    roof = hc_.SIDE_TOP - 9.0
    zs = np.linspace(hc_.BED_FRONT, hc_.BED_BACK - 12.0, 4)
    for z0, z1 in zip(zs[:-1], zs[1:]):
        out.append(bx(*_cart_span((-4.5, 4.5), (roof - 0.8, roof), (z0, z1)), CANVAS_C))
        for x0, x1 in ((-7.6, -4.5), (4.5, 7.6)):
            out.append(bx(*_cart_span((x0, x1), (roof, hc_.SIDE_TOP - 4.0), (z0, z1)), CANVAS_D))
    zh = (hc_.BED_BACK - 0.6, hc_.BED_BACK + 0.2)                  # the hoop at the tail: two posts and the bar the curtain hangs from
    for x0, x1 in ((-7.4, -6.4), (6.4, 7.4)):
        out.append(bx(*_cart_span((x0, x1), (roof, hc_.SIDE_TOP), zh), CART_HOOP))
    out.append(bx(*_cart_span((-7.4, 7.4), (roof - 0.8, roof), zh), CART_HOOP))
    return out


# ================================================================== the registry

TRADE_CLIPS = ['stamp', 'check_load']
MAKERS = [('stamp', stamp), ('check_load', check_load)]
STATION_BOXES = {'stamp_desk': _stamp_desk, 'cart_covered': _cart_covered}
STATION_COLOUR = {}
NEEDS = {}
BUILT = {}


def trade_moment_specs():
    """Where the trade's clips' moments happen (living_anims' moment_specs forms)."""
    return {
        'stamp': {'press': ('part', 'stamp', tuple(lp.STAMP_FACE)), 'lift': ('part', 'stamp', tuple(lp.STAMP_FACE))},
        'check_load': {'lift': ('part', 'canvas_flap', tuple(CL_RING)), 'drop': ('part', 'canvas_flap', tuple(CL_RING)),
                       'feel': ('part', 'right_arm', tuple(GRIP['right_arm'])),
                       'pat': ('part', 'right_arm', tuple(GRIP['right_arm']))},
    }


def build(living, only=None):
    """Builds the trade's clips on living_anims' kit ([living]: the module), and teaches it their stations and moments.
    [only]: just these (the quick previews)."""
    global la
    la = living
    if wc.la is None:
        wc.la = living
    if BUILT:
        return
    la.STATIONS.update(STATION_BOXES)
    la.GROUND_COLOUR.update(STATION_COLOUR)
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
    specs_ = la.moment_specs

    def moment_specs():
        out = specs_()
        out.update(trade_moment_specs())
        return out
    la.moment_specs = moment_specs
    BUILT['done'] = True
    la.WANTED.extend(TRADE_CLIPS)
