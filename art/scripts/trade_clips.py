"""
The trade's seven clips (living-world/trader/design.md, section 10 and the X4 row of 12.15), on the Living World's kit:

    STAMP       20 once    a seal pressed into wax on a counter: the stamp raised, slammed down, held and twisted, lifted
    CHECK_LOAD  30 once    at a covered cart's tail: the canvas's corner lifted, a look under, the goods felt, the canvas let fall
    LOAD_PACK   40 once    a laden basket taken off the hip, heaved onto a wagon's let-down tailboard and slid home
    HITCH       50 once    a horse pushed back in the shafts with the flat of the hand, its trace drawn up, hooked on, tugged
    WEIGH       60 once    a sack swung up onto a scale's pan, the beam read at eye level, the quill made ready
    HALT        25 once    the hand flung up for "Hold!", braced, and let fall
    RUMMAGE     60 loop    head bent over a satchel held open, both hands working through it

Their names, lengths and loop flags are TownRole.kt's (Activity); living_anims.py imports this module at its end and calls
build(), which makes the clips, so its verify() and writer take them like the rest. `python3 -B trade_clips.py <out dir>`
draws the GIF previews (2x2 grids) once living_anims has built everything.

Clip space as every townsfolk clip's: pixels, y down, facing -z, the right hand at -x, the feet at y 24. A clip that acts
on something beside the townsperson (the counter, the cart's tail, the wagon's tailboard, the horse in the shafts, the
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
from townsfolk_anims import (qeuler, qslerp, unit, F, B, U, D, R, L, T, Rq, F_, bend, key_frame, check, seal, smooth, bump,
                             GRIP, PIVOTS, torso_to_world, sink_for, gaze_head)
import works_clips as wc
from works_clips import rest_arm, point_arm, mix, lifted, set_arm, reach_arm, stand, joins_idle, stepping, FEET
from animkit import v
import living_props as lp
import street_clips as sc
import wagon as wg
import store_art as sa

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


# ================================================================== load_pack

# ---- load_pack (40, once): a laden basket lifted off the hip onto a wagon's let-down tailboard and slid home. Made for
# the House's yard (YardShow), the one place the game plays it: the carter comes up with the basket on the left hip
# (carry_basket's first frame is this clip's) and stands square behind the wagon on its middle line, facing it, the
# feet LP_FEET_Z px behind the back axle (wagon space): the tailboard and the floor are one level 19 px up (clip y 5),
# from the board's end 9 px ahead to the box's back 15 px ahead and the bed beyond.
# The torso turns to the hip and the right hand takes the basket's far side (take); it is swung round in front of the
# belly, the knees dip, and it is heaved up chest high with the body leaning back under it (heave), carried on over the
# board's end and set down on it (set); a lean and a shove slide it along the board (shove), the hands let go and come
# away, the body straightens, the palms are brushed together twice (dust) and the arms hang. The basket and what is in it
# stay on the board to the end, where the code's heap of the load on the bed takes over.
LP_LEN = 40
LP_TAKE, LP_HEAVE, LP_SET, LP_SHOVE, LP_LET_GO = 4.0, 12.0, 17.0, 20.0, 22.0
LP_DUSTS = (30.0, 32.6)
LP_FEET_Z = wg.BOX_BACK + wg.TAIL_LEN + 9.0         # the feet's line, in the wagon's z (on its middle line)


def lp_w(x, y, z):
    """A point of the wagon's space (wagon.py: the ground under the back axle's middle, facing -z) in this clip's."""
    return np.array([float(x), 24.0 + float(y), float(z) - LP_FEET_Z])


LP_BOARD_Y = 24.0 + wg.FLOOR_TOP                    # the tailboard's top and the floor's (5)
LP_END_Z = lp_w(0, 0, wg.BOX_BACK + wg.TAIL_LEN)[2]  # the board's end (-9)
LP_BOX_Z = lp_w(0, 0, wg.BOX_BACK)[2]                # the box's back, where the floor begins (-15)
# pack_basket space (basket_hip's): its mouth's middle, its bottom's, and where each hand holds it once it is in front
LP_MOUTH = np.array([0.0, lp.PACK_BASKET_BOX[0][1], 0.0])
LP_BOTTOM = np.array([0.0, lp.PACK_BASKET_BOX[1][1], 0.0])
LP_SIDES = {'right_arm': np.array([lp.PACK_BASKET_BOX[0][0] - 0.7, LP_MOUTH[1] + 0.4, 0.0]),
            'left_arm': np.array([lp.PACK_BASKET_BOX[1][0] + 0.7, LP_MOUTH[1] + 0.4, 0.0])}
LP_HIP_P = np.array(lp.PACK_BASKET_PIVOT, float)    # on the hip (torso space), as basket_hip sits
LP_HIP_R = ta.qmat(ta.rot_part(lp.PACK_BASKET_ROT))
LP_RIM = np.array([8.4, 7.6, -6.4])                 # carry_basket's left hand on the front rim (living_anims BASKET_RIM)
LP_RIM_B = LP_HIP_R.T @ (LP_RIM - LP_HIP_P)         # ... in the basket's space
LP_SET_O = np.array([0.0, LP_BOARD_Y - LP_BOTTOM[1], -14.2])        # set down: its near side 2.6 in from the board's end
LP_HOME_O = np.array([0.0, LP_BOARD_Y - LP_BOTTOM[1], -16.8])       # slid in: its far half over the floor
LP_P = [(0, 0.0), (2.5, 7.0), (LP_TAKE, 8.0), (7.0, 5.0), (9.5, 13.0), (LP_HEAVE, -4.0), (14.0, -12.0), (15.6, 6.0),
        (LP_SET, 22.0), (18.6, 20.0), (21.5, 25.0), (LP_LET_GO, 25.0), (24.5, 10.0), (27.0, -2.0), (29.0, 3.0),
        (34.0, 3.0), (37.0, 0.5), (40, 0.0)]
LP_STEPS = [(17.4, 19.6, 'right_leg', (-1.9, -3.0)), (23.4, 26.0, 'right_leg', (-1.9, 0.0))]   # a step in to shove
LP_YAW = [(0, 0.0), (2.5, -12.0), (LP_TAKE, -14.0), (7.0, 0.0), (40, 0.0)]
LP_DIP = [(0, 0.0), (LP_TAKE, 0.8), (7.0, 0.4), (9.5, 2.6), (LP_HEAVE, 0.4), (14.0, 0.0), (LP_SET, 0.9), (18.4, 1.1),
          (21.5, 1.0), (24.5, 0.4), (27.0, 0.0), (40, 0.0)]
LP_RZ = [(0, 0.0), (9.5, 0.4), (LP_HEAVE, 1.0), (14.0, 0.8), (LP_SET, -0.8), (19.6, -2.0), (21.5, -2.4), (LP_LET_GO, -2.4),
         (26.0, 0.0), (40, 0.0)]


def lp_feet(t):
    return stepping(t, FEET, LP_STEPS, lift=1.4)


def lp_frame(t):
    pitch = K(LP_P, t)
    feet, _ = lp_feet(t)
    return F_(qt=bend(pitch, K(LP_YAW, t), 0.0), vt=(0.0, sink_for(pitch), 0.0),
              pr=la.root_for(feet, 0.0, K(LP_RZ, t), dip=K(LP_DIP, t)))


def lp_on_hip(fr):
    """The basket on the hip as the torso of [fr] carries it: its origin and turn."""
    return torso_to_world(fr, LP_HIP_P), ta.qmat(ta.qmul(fr['qr'], fr['qt'])) @ LP_HIP_R


def lp_path():
    o_take, r_take = lp_on_hip(lp_frame(LP_TAKE))
    return [(LP_TAKE, o_take), (7.0, np.array([0.0, 11.6, -5.9])), (9.5, np.array([0.0, 12.4, -5.8])),
            (LP_HEAVE, np.array([0.0, 6.0, -5.6])), (14.0, np.array([0.0, 1.5, -6.4])), (15.6, np.array([0.0, 1.4, -11.4])),
            (LP_SET, LP_SET_O), (18.4, LP_SET_O), (21.5, LP_HOME_O)], r_take


def lp_basket(t):
    """The basket's origin and turn (clip space) at [t]: on the hip, swung round square in front of the belly, heaved,
    set down and slid along the board, then left there."""
    if t <= LP_TAKE:
        return lp_on_hip(lp_frame(t))
    keys, r_take = LP_PATH
    O = KV(keys, min(t, 21.5))
    w = smooth((t - LP_TAKE) / (7.0 - LP_TAKE))
    q = qslerp(ta.qfrommat(r_take), ta.I, w)
    # it tips back a touch as it is heaved and lands on its far edge first
    tip = -6.0 * bump(t, 10.0, 15.0) + 5.0 * bump(t, 14.0, LP_SET)
    return O, ta.qmat(q) @ rot_x(tip)


def lp_grip(side, t):
    """Where [side]'s hand is on the basket (its space) at [t]: the left from the front rim's corner to its side as it
    comes round in front, the right on its far side."""
    if side == 'right_arm':
        return LP_SIDES['right_arm']
    return LP_RIM_B + (LP_SIDES['left_arm'] - LP_RIM_B) * smooth((t - LP_TAKE) / (7.0 - LP_TAKE))


def _pack_track(t, pivot):
    O, Rb = lp_basket(t)
    return Rb, O, None


LP_PATH = None
LP_DUST_R = np.array([-0.4, 9.4, -7.6])            # the palms together in front of the belly (the right's grip, the left's)
LP_DUST_L = np.array([1.4, 9.8, -7.2])


def load_pack():
    global LP_PATH
    LP_PATH = lp_path()
    c = la.clip('load_pack', LP_LEN, False, look=('citizen', 3), tools_=('pack_basket',), station_='wagon_tail_load',
                events=[('take', LP_TAKE), ('heave', LP_HEAVE), ('set', LP_SET), ('shove', LP_SHOVE)] + [('dust', k) for k in LP_DUSTS],
                traces=[('pack_basket', tuple(LP_MOUTH), (230, 200, 160))], views=((130, -16), (90, -4), (160, -30)))
    holds = la.Holds()
    off = {}
    o_take = LP_PATH[0][0][1]
    looks = [(0, torso_to_world(lp_frame(0.0), LP_HIP_P + LP_HIP_R @ LP_MOUTH)), (LP_TAKE, o_take + np.array([0.0, -2.0, 0.0])),
             (7.0, np.array([0.0, 9.0, -6.0])), (9.5, np.array([0.0, 6.0, -14.0])), (LP_HEAVE, LP_SET_O + np.array([0.0, -2.0, 0.0])),
             (LP_SET, LP_SET_O), (18.8, np.array([0.0, -1.0, -26.0])), (24.0, np.array([0.0, -1.0, -30.0])), (26.0, lp_w(0, -22.0, -20.0)),
             (29.0, np.array([0.5, 9.4, -7.4])), (33.5, np.array([0.5, 9.4, -7.4])), (37.0, np.array([0.0, -2.0, -40.0])),
             (40, np.array([0.0, -2.0, -40.0]))]
    for t in la.every(LP_LEN):
        fr = lp_frame(t)
        key_frame(c, t, fr, ease='linear')
        feet, lift = lp_feet(t)
        stand(c, t, fr, feet=feet, lift=lift)
        O, Rb = lp_basket(t)
        for side in ('right_arm', 'left_arm'):
            rest = rest_arm(side, fr)
            on = O + Rb @ lp_grip(side, t)
            palm = Rb @ (L if side == 'right_arm' else R)
            if t <= LP_TAKE and side == 'left_arm':
                pose = reach_arm(holds, side, fr, on, palm=R, palm_w=0.3, slack=0.6, limit=1.6)
            elif t <= LP_LET_GO:
                pose = reach_arm(holds, side, fr, on, palm=palm, palm_w=0.4, slack=0.8, limit=1.8)
                if side == 'right_arm' and t < LP_TAKE:
                    pose = mix(rest, pose, smooth((t - 0.4) / (LP_TAKE - 0.4)))
            else:
                # let go: the hands come up off the rim and away, then together in front for the dusting, then hang
                if side not in off:
                    off[side] = O + Rb @ lp_grip(side, LP_LET_GO)
                lift = off[side] + np.array([0.0, -2.0, 1.6]) * smooth((t - LP_LET_GO) / 2.0)
                dust = LP_DUST_R if side == 'right_arm' else LP_DUST_L
                brush = sum(1.6 * bump(t, k - 1.2, k + 1.2) for k in LP_DUSTS) * (1.0 if side == 'right_arm' else 0.0)
                dust = dust + np.array([0.0, -0.3, -1.0]) * brush
                w_d = smooth(K([(LP_LET_GO, 0.0), (25.5, 0.0), (28.4, 1.0), (34.0, 1.0), (37.0, 0.0), (40, 0.0)], t))
                target = lift * (1.0 - w_d) + dust * w_d
                pose = reach_arm(holds, side, fr, target, palm=(L if side == 'right_arm' else R), palm_w=0.3, slack=0.6, limit=1.6)
                w_r = smooth(K([(LP_LET_GO, 0.0), (24.5, 0.0), (26.5, 0.6), (28.4, 0.0), (34.0, 0.0), (38.5, 1.0), (40, 1.0)], t))
                pose = mix(pose, rest, w_r)
            set_arm(c, side, t, pose)
        # the head: on the basket on the hip and as it comes round, up to the board for the heave and on the basket as
        # it is set and slid in; up along the bed, down to the hands brushed, level
        look = KV(looks, t)
        gaze = smooth(K([(0, 0.0), (1.5, 0.8), (3.0, 1.0), (26.0, 1.0), (29.0, 0.7), (33.5, 0.7), (37.5, 0.0), (40, 0.0)], t))
        c.key('head', t, look_at(fr, look, gaze), ease='linear')
    c.mode('head', 'set')
    la.TRACKS['load_pack'] = {'pack_basket': _pack_track}
    # the goods: in the basket on the hip where carry_basket has them, between the hands while they hold it by its rim,
    # then in its mouth where it is left on the board
    la.set_goods('load_pack', [(0.0, LP_TAKE, ('part', 'body', (5.6, 7.8, -4.0)), 3.5), (LP_TAKE, LP_LET_GO, ('hands', 0.0), 3.5),
                               (LP_LET_GO, None, ('ground', tuple(LP_HOME_O + LP_MOUTH + np.array([0.0, 0.4, 0.0]))), 3.5)])
    for k in (LP_TAKE, 7.0, 9.5, LP_HEAVE, 14.0, 15.6, LP_SET, LP_SHOVE, 21.5):
        O, Rb = lp_basket(k)
        for side in ('right_arm', 'left_arm'):
            check(c, f'{side.replace("_arm", "")} hand on the basket (t={k:g})', ta.fk(c, k, side, GRIP[side]), O + Rb @ lp_grip(side, k), tol=1.2)
    check(c, 'left hand on the basket\'s rim (t=0)', ta.fk(c, 0.0, 'left_arm', GRIP['left_arm']), LP_RIM, tol=1.2)
    for k in (8.0, 16.0, 21.0):
        O, Rb = lp_basket(k)
        check(c, f'the goods in the basket\'s mouth (t={k:g})', la.between_hands(_mats(c, k)), O + Rb @ (LP_MOUTH + np.array([0.0, 0.4, 0.0])), tol=1.0)
    O, Rb = lp_basket(LP_SET)
    check(c, 'the basket set down on the board (t=17: its bottom\'s middle)', O + Rb @ LP_BOTTOM, LP_SET_O + LP_BOTTOM, tol=0.2)
    times = [i * 0.5 for i in range(2 * LP_LEN + 1)]
    boxes = la.station_boxes('wagon_tail_load')
    basket_clear(c, boxes, [k for k in times if k < LP_SET - 0.4])
    clear_of_station(c, boxes, times, what='the wagon')
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.5)
    wc.maybe_join('load_pack starts on carry_basket (its arms; the basket where basket_hip is)', c, 0, 'carry_basket', 0, tol=1.0)
    joins_idle(c, start=False)
    return c


def basket_points(Rb, O, n=3):
    lo, hi = np.asarray(lp.PACK_BASKET_BOX[0], float), np.asarray(lp.PACK_BASKET_BOX[1], float)
    return [O + Rb @ (lo + (hi - lo) * np.array([i, j, k]) / n) for i in range(n + 1) for j in range(n + 1) for k in range(n + 1)]


def basket_clear(c, boxes, times, tol=0.3):
    """Checks the basket keeps out of the station's [boxes] and the body and head all through [times]."""
    worst, at = 0.0, 0.0
    worst_b, at_b = 0.0, 0.0
    for t in times:
        O, Rb = lp_basket(t)
        pts = basket_points(Rb, O)
        for p in pts:
            for b in boxes:
                blo, bhi = np.asarray(b[0], float), np.asarray(b[1], float)
                inside = min(float(np.min(p - blo)), float(np.min(bhi - p)))
                if inside > worst:
                    worst, at = inside, t
        mats = _mats(c, t)
        for name in ('body', 'head'):
            blo, bhi = BOXES[name]
            Mi = np.linalg.inv(mats[name])
            for p in pts:
                q = Mi[:3, :3] @ p + Mi[:3, 3]
                inside = min(q[0] - blo[0], bhi[0] - q[0], q[1] - blo[1], bhi[1] - q[1], q[2] - blo[2], bhi[2] - q[2])
                if inside > worst_b:
                    worst_b, at_b = inside, t
    check(c, f'the basket clear of the wagon until it is set (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)
    check(c, f'the basket clear of the body and head (deepest, at t={at_b:g})', (worst_b,), (0.0,), tol=0.8)


WAGON_WOOD, WAGON_WOOD_D, WAGON_CANVAS = (138, 100, 62), (104, 74, 46), (214, 204, 176)
LOAD_SACK = (186, 160, 112)


def _wagon_tail_load():
    """The back of the House's wagon as the loader meets it: the tailboard let down level with the floor, the floor and
    the box's sides, the canvas tilt over the bed and the first sacks of the load heaped at its front (the wheels left
    out: from the side they hide the tail)."""
    out = []

    def box(lo, hi, col):
        a, b = lp_w(*lo), lp_w(*hi)
        out.append(bx(np.minimum(a, b), np.maximum(a, b), col))
    box((-wg.BOX_X + 1.0, wg.FLOOR_TOP, wg.BOX_BACK), (wg.BOX_X - 1.0, wg.FLOOR_TOP + 1.2, wg.BOX_BACK + wg.TAIL_LEN), WAGON_WOOD)
    box((-wg.BOX_X + 1.0, wg.FLOOR_TOP, wg.BOX_BACK - 34.0), (wg.BOX_X - 1.0, wg.FLOOR_TOP + wg.FLOOR_T, wg.BOX_BACK), WAGON_WOOD_D)
    for s in (-1, 1):
        box((s * wg.BOX_X, wg.SIDE_TOP, wg.BOX_BACK - 34.0), (s * (wg.BOX_X - 1.0), wg.FLOOR_TOP + 1.2, wg.BOX_BACK), WAGON_WOOD_D)
        # the tilt's sides over the bed, from its back hoop on
        box((s * (wg.BOX_X - 0.3), wg.ARCH_Y, wg.TILT_BACK - 26.0), (s * (wg.BOX_X - 1.3), wg.SIDE_TOP, wg.TILT_BACK), WAGON_CANVAS)
    box((-wg.BOX_X + 0.3, wg.ARCH_Y - wg.ARCH_H, wg.TILT_BACK - 26.0), (wg.BOX_X - 0.3, wg.ARCH_Y - wg.ARCH_H + 1.2, wg.TILT_BACK), WAGON_CANVAS)
    for x0, x1 in ((-6.7 - 3.2, -6.7 + 3.2), (-3.2, 3.2), (6.7 - 3.2, 6.7 + 3.2)):
        z = wg.ROWS_Z[-1]
        box((x0, wg.FLOOR_TOP - 5.5, z - 3.6), (x1, wg.FLOOR_TOP, z + 3.6), LOAD_SACK)
    return out


# ================================================================== hitch

# ---- hitch (50, once): at a draught horse's right shoulder as it stands in the wagon's shafts, facing it square, HT_OUT
# px out from its middle line and HT_FWD px ahead of its origin (draught_horse.py's model space: the ground under the
# middle of its body): where WagonBill's hitch() puts the hand each tick. The right palm flat on its shoulder over the
# shaft, two shoves to stand it back in the shafts (back); the left hand to the shaft, the right down under it to where
# the right trace will run (grab: the trace drawn out from there, the prop stretched from HT_ANCHOR), a step to the
# right, the trace carried under the shaft and up past its tip onto the hook on the hame (clip: where the code's
# harness() clips the traces on and the world's trace takes over from the prop), two tugs to see it is fast (tug), let go;
# two pats on the shoulder (pat) and the step back.
HT_LEN = 50
HT_BACKS = (3.5, 6.5)
HT_GRAB, HT_CLIP, HT_LET_GO = 13.0, 25.0, 29.0
HT_TUGS = (26.6, 28.0)
HT_PATS = (32.0, 34.4)
HT_OUT, HT_FWD = 16.0, 4.8


def ht_pt(p):
    """A point of the horse's model space in this clip's."""
    return np.array([float(p[2]) + HT_FWD, float(p[1]), -HT_OUT - float(p[0])])


HT_HAME = ht_pt((-wg.HAMES[0], 24.0 - wg.HAMES[1], wg.HAMES[2]))                 # the right trace's hook on the hame
HT_SWINGLE = ht_pt((-wg.SWINGLE_HALF, wg.SWINGLE[1] + 24.0, wg.SWINGLE[2] - wg.HORSE_Z))
HT_DIR = unit(HT_SWINGLE - HT_HAME)                                              # the hitched trace, from the hook back
HT_ANCHOR = HT_HAME + HT_DIR * 12.0                                               # the prop's root: 12 px back along it
_HT_TUG = np.array([-wg.SHAFT_X, 24.0 - wg.SHAFT_UP, wg.TUG_Z])
_HT_HINGE = np.array([-wg.SHAFT_HINGE[0], wg.SHAFT_HINGE[1] + 24.0, wg.SHAFT_HINGE[2] - wg.HORSE_Z])
_HT_SHAFT = unit(_HT_TUG - _HT_HINGE)


def ht_shaft(hz):
    """The right shaft's middle line at the horse's z [hz] (model space)."""
    return _HT_TUG + _HT_SHAFT * ((hz - _HT_TUG[2]) / _HT_SHAFT[2])


def ht_on_shaft(x):
    """The top of the right shaft at clip x [x], where a fist rests on it (its grip 3.45 px over the middle: the end
    of a block arm is its grip, and the fist's near corner comes down past it)."""
    return ht_pt(ht_shaft(x - HT_FWD)) + np.array([0.0, -wg.SHAFT_T / 2 - 2.6, 0.0])


HT_PUSH = ht_pt((-6.6 - 2.0, -1.0, -8.0))          # the right palm flat on the shoulder over the shaft (the grip 2 px off it)
HT_LEFT = ht_on_shaft(6.0)                          # the left fist on the shaft
HT_PAT = ht_pt((-6.6 - 1.6, -3.4, -15.3))           # the pats, on top of the shoulder behind the collar
# the trace's end (where the prop reaches) from its grab, under the shaft, up past its tip, over the hook and onto it
HT_END = [(HT_GRAB, HT_ANCHOR), (15.2, np.array([-6.0, 7.8, -7.6])), (18.0, np.array([-9.6, 7.6, -7.6])),
          (21.0, np.array([-13.0, 5.6, -8.2])), (23.0, HT_HAME + np.array([1.0, 1.4, 0.4])), (HT_CLIP, HT_HAME)]
# the right hand from the shoulder to the trace: back off over the shaft, down before it, in under it
HT_UNDER = [(7.6, HT_PUSH), (9.0, np.array([-7.6, 1.6, -5.6])), (10.6, np.array([-5.6, 7.0, -6.2]))]
HT_HOLD = HT_DIR * 1.6 + np.array([0.0, 0.0, 0.9])  # the fist on the trace just behind its end, on the near side
HT_STEPS = [(8.6, 11.4, 'right_leg', (-4.6, -0.6)), (10.0, 12.8, 'left_leg', (-0.8, -0.4)),
            (35.4, 38.2, 'left_leg', (1.9, 0.0)), (36.8, 39.8, 'right_leg', (-1.9, 0.0))]
HT_P = [(0, 0.0), (2.0, 4.0), (HT_BACKS[0], 7.0), (5.0, 4.0), (HT_BACKS[1], 7.0), (8.4, 2.0), (11.0, 3.0), (HT_GRAB, 4.0),
        (15.2, 5.0), (18.0, 4.0), (21.0, 1.0), (HT_CLIP, 0.0), (HT_LET_GO, 0.0), (31.0, 2.0), (35.0, 2.0), (37.0, 0.5),
        (40.0, -1.0), (43.0, 0.0), (50, 0.0)]
HT_YAW = [(0, 0.0), (8.4, 0.0), (11.0, -4.0), (15.0, 0.0), (20.6, 10.0), (HT_LET_GO, 10.0), (31.0, 4.0), (35.0, 2.0),
          (39.0, 0.0), (50, 0.0)]
HT_DIP = [(0, 0.0), (HT_BACKS[0], 0.5), (5.0, 0.2), (HT_BACKS[1], 0.5), (8.4, 0.1), (HT_GRAB, 0.9), (15.2, 1.4), (18.0, 1.2),
          (21.0, 0.3), (HT_CLIP, 0.3), (HT_LET_GO, 0.2), (40.0, 0.0), (50, 0.0)]


def ht_feet(t):
    return stepping(t, FEET, HT_STEPS, lift=1.6)


def ht_frame(t):
    pitch = K(HT_P, t)
    feet, _ = ht_feet(t)
    x = 0.5 * (feet['right_leg'][0] + feet['left_leg'][0])
    z = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
    lean = -0.8 * sum(bump(t, k - 1.2, k + 1.0) for k in HT_BACKS)
    return F_(qt=bend(pitch, K(HT_YAW, t), 0.0), vt=(0.0, sink_for(pitch), 0.0),
              pr=la.root_for(feet, x, z + lean, dip=K(HT_DIP, t)))


def ht_end(t):
    """Where the trace's end is (the prop's far end) at [t]: drawn from the anchor, then on the hook."""
    if t >= HT_CLIP:
        return HT_HAME
    return KV(HT_END, max(HT_GRAB, t))


def aim_arc(frm, to, length0):
    """A prop track's turn and stretch for a strap modelled [length0] px along its +y from its pivot, from [frm] to [to]:
    the shortest turn from +y, as the wagon's renderer turns its traces (so where the prop lies along the world's trace,
    the two lie alike)."""
    d = np.asarray(to, float) - np.asarray(frm, float)
    n = float(np.linalg.norm(d))
    if n < 1e-6:
        return np.eye(3), (1.0, 1e-3, 1.0)
    y = d / n
    axis = np.cross(np.array([0.0, 1.0, 0.0]), y)
    s_, c_ = float(np.linalg.norm(axis)), float(y[1])
    if s_ < 1e-6:
        return (np.eye(3) if c_ > 0 else np.diag([1.0, -1.0, -1.0])), (1.0, n / length0, 1.0)
    k = axis / s_
    Kx = np.array([[0.0, -k[2], k[1]], [k[2], 0.0, -k[0]], [-k[1], k[0], 0.0]])
    return np.eye(3) + s_ * Kx + (1.0 - c_) * Kx @ Kx, (1.0, n / length0, 1.0)


def _trace_track(t, pivot):
    Rm, S = aim_arc(HT_ANCHOR, ht_end(t), lp.TRACE_LEN)
    return Rm, HT_ANCHOR, S


def hitch():
    c = la.clip('hitch', HT_LEN, False, look=('farmer', 1), tools_=('trace_end',), station_='horse_hitch',
                events=[('back', k) for k in HT_BACKS] + [('grab', HT_GRAB), ('clip', HT_CLIP)] + [('tug', k) for k in HT_TUGS]
                + [('pat', k) for k in HT_PATS],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((135, -16), (215, -12), (180, -30)))
    ta.INFO['hitch']['tools_at'] = wc.tools_when([('trace_end', HT_GRAB, HT_LET_GO + 0.5)])
    ta.INFO['hitch']['pan'] = np.array([-6.0, 0.0, -4.0])
    holds = la.Holds()
    eye = ht_pt((-3.0, -12.0, -33.0))                  # the horse's right eye, near enough
    # (the eyes kept above the shaft's level: looking further down under it would put the face into the horse's side)
    looks = [(0, eye), (2.0, HT_PUSH + np.array([-4.0, -4.0, -2.0])), (HT_BACKS[1], eye)]
    looks += [(k, np.array([p[0], min(p[1], 0.5), p[2]])) for k, p in [(9.0, HT_ANCHOR)] + HT_END] + [(HT_LET_GO, HT_HAME), (31.0, HT_PAT), (35.0, HT_PAT), (37.5, eye), (41.0, eye),
                                                (45.0, np.array([0.0, -2.0, -40.0])), (50, np.array([0.0, -2.0, -40.0]))]
    for t in la.every(HT_LEN):
        fr = ht_frame(t)
        key_frame(c, t, fr, ease='linear')
        feet, lift = ht_feet(t)
        stand(c, t, fr, feet=feet, lift=lift)
        # the right hand: the palm on the shoulder and its two shoves; down under the shaft to the trace and along with
        # it onto the hook; two tugs; let go, two pats, down
        rest = rest_arm('right_arm', fr)
        shove = sum(bump(t, k - 1.2, k + 1.0) for k in HT_BACKS)
        if t < 2.0:
            # up from hanging out in front of the body, onto the shoulder
            keys = [(0, wc.grip_of('right_arm', rest_arm('right_arm', ht_frame(0.0)), ht_frame(0.0))),
                    (1.0, np.array([-6.4, 5.0, -6.0])), (2.0, HT_PUSH)]
            pose = reach_arm(holds, 'right_arm', fr, KV(keys, t), palm=F, palm_w=0.3 * smooth(t / 2.0), slack=0.8, limit=1.8)
            pose = mix(rest, pose, smooth(t / 0.8))
        elif t < 7.6:
            pose = reach_arm(holds, 'right_arm', fr, HT_PUSH + np.array([0.0, 0.0, -0.9]) * shove, palm=F, palm_w=0.5,
                             slack=0.8, limit=1.8)
        elif t < HT_GRAB:
            keys = HT_UNDER + [(HT_GRAB, HT_ANCHOR + HT_HOLD)]
            pose = reach_arm(holds, 'right_arm', fr, KV(keys, t), palm=F if t < 9.0 else U, palm_w=0.3, slack=0.8, limit=1.8)
        elif t <= HT_LET_GO:
            tug = sum(1.1 * bump(t, k - 0.8, k + 0.8) for k in HT_TUGS)
            pose = reach_arm(holds, 'right_arm', fr, ht_end(t) + HT_HOLD + HT_DIR * tug, palm=U, palm_w=0.3, slack=0.8, limit=1.8)
        else:
            pat = sum(bump(t, k - 1.0, k + 1.0) for k in HT_PATS)
            on = reach_arm(holds, 'right_arm', fr, HT_PAT + np.array([0.0, -0.4, 1.6]) * (1.0 - pat), palm=F, palm_w=0.5,
                           slack=0.8, limit=1.8)
            w_on = smooth(K([(HT_LET_GO, 0.0), (31.0, 1.0), (35.4, 1.0), (38.5, 0.0), (50, 0.0)], t))
            off = HT_HAME + HT_HOLD + np.array([0.0, -0.6, 2.4])
            if t < 31.0:
                pose = reach_arm(holds, 'right_arm', fr, off + (HT_PAT - off) * smooth((t - HT_LET_GO) / 2.0), palm=F, palm_w=0.3,
                                 slack=0.8, limit=1.8)
            else:
                pose = mix(rest, on, w_on)
        set_arm(c, 'right_arm', t, pose)
        # the left hand: hanging, on the shaft while the trace is brought round and hooked, hanging again
        rest = rest_arm('left_arm', fr)
        w = smooth(K([(0, 0.0), (10.4, 0.0), (13.4, 1.0), (HT_LET_GO, 1.0), (32.0, 0.0), (50, 0.0)], t))
        if w > 1e-4:
            pose = reach_arm(holds, 'left_arm', fr, HT_LEFT, palm=D, palm_w=0.3, slack=0.6, limit=1.6)
            rest = mix(rest, pose, w)
        set_arm(c, 'left_arm', t, rest)
        gaze = smooth(K([(0, 0.0), (2.0, 0.5), (HT_BACKS[1], 0.6), (9.0, 1.0), (HT_LET_GO, 1.0), (37.5, 0.6), (41.0, 0.5), (45.0, 0.0), (50, 0.0)], t))
        c.key('head', t, look_at(fr, KV(looks, t), gaze), ease='linear')
    c.mode('head', 'set')
    la.TRACKS['hitch'] = {'trace_end': _trace_track}
    for k in HT_BACKS:
        check(c, f'the palm on the shoulder (t={k:g}: the grip)', ta.fk(c, k, 'right_arm', GRIP['right_arm']),
              HT_PUSH + np.array([0.0, 0.0, -0.9]), tol=1.0)
    for k in (HT_GRAB, 15.0, 18.0, 20.6, 23.0, HT_CLIP):
        check(c, f'right hand on the trace (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), ht_end(k) + HT_HOLD, tol=1.0)
    check(c, 'the trace\'s end on the hook (t=25)', ht_end(HT_CLIP), HT_HAME, tol=0.05)
    for k in (16.0, 22.0, 27.0):
        check(c, f'left fist on the shaft (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']), HT_LEFT, tol=1.0)
    for k in HT_PATS:
        check(c, f'the pat on the shoulder (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), HT_PAT, tol=1.0)
    times = [i * 0.5 for i in range(2 * HT_LEN + 1)]
    clear_of_station(c, la.station_boxes('horse_hitch'), times, what='the horse and the shaft')
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.0)
    joins_idle(c)
    return c


HORSE_C, HORSE_D, HARNESS_C, BRASS_C, SHAFT_C = (118, 70, 40), (96, 58, 34), (64, 42, 28), (200, 160, 80), (138, 100, 62)


def _horse_hitch():
    """The horse's right side as the one hitching it meets it: its barrel, collar, neck and head, its legs, the right
    shaft along its side from the tip by its shoulder, and the hook on the hame."""
    out = []

    def hbox(lo, hi, col):
        a, b = ht_pt(lo), ht_pt(hi)
        out.append(bx(np.minimum(a, b), np.maximum(a, b), col))
    hbox((-6.6, 24.0 - 27.0, -15.0), (6.6, 24.0 - 16.0, 17.0), HORSE_C)
    hbox((-6.8, 24.0 - 31.0, -21.0), (6.8, 24.0 - 15.0, -15.0), HARNESS_C)
    hbox((-3.4, 24.0 - 33.0, -30.0), (3.4, 24.0 - 24.0, -17.0), HORSE_C)
    hbox((-2.6, 24.0 - 33.0, -38.0), (2.6, 24.0 - 26.0, -30.0), HORSE_D)
    for x, z in ((-3.7, -11.2), (3.7, -11.2), (-3.9, 11.0), (3.9, 11.0)):
        hbox((x - 1.6, 24.0 - 16.0, z - 1.8), (x + 1.6, 24.0, z + 1.8), HORSE_D)
    for k in range(28):
        z0 = wg.SHAFT_TIP_Z + k * 2.0
        a, b = ht_shaft(z0), ht_shaft(z0 + 2.0)
        hbox(np.minimum(a, b) - np.array([wg.SHAFT_T / 2] * 2 + [0.0]), np.maximum(a, b) + np.array([wg.SHAFT_T / 2] * 2 + [0.0]), SHAFT_C)
    h = HT_HAME
    out.append(bx(h - np.array([0.6, 0.6, 0.4]), h + np.array([0.6, 0.6, 0.6]), BRASS_C))
    return out


# ================================================================== weigh

# ---- weigh (60, once): at the Carriers' Store's scale (art stream X3's store_scale; StoreLife's weigh scene). Made for
# the clerk standing on the middle of the block south of the scale's, facing it square (north): the bench's top 12 px up
# from its near edge 10 px ahead, the beam's pivot 16 px ahead and 24.2 up, the goods pan hanging on the left (its floor
# at store_art.clip_pan(GOODS)) and the weights pan on the right.
# The sack hangs from the left fist by its neck: a look down at it and a swing back; swung up on the straight arm,
# the body leaning back under it, till it is held out at shoulder height; a half step in with the left foot, the body
# turned to bring the left shoulder on and tipped away from the weight, the sack carried over the pan's near rim between
# its two near chains and let down onto its floor (set, tick 18: StoreLife's SET_ON, when the scale's own sack takes this
# one's place and the beam starts to swing); the fist drawn back out of the chains, the step back. The knees bend till
# the eyes are level with the beam, the left hand on the bench's edge, and the head rides the goods end down and through
# its swings as the beam settles, along to the other end and back to the middle, a nod (read). Up again: the quill drawn
# from the sash and held ready, then jabbed at the weights pan as the first weight goes on there (tally, tick 48: the
# scale's own clock, 30 ticks after the sack), a nod; back into the sash, the arms hang. The figure is written at the
# desk next (SCRIBE).
WG_LEN = 60
WG_SET = 18.0                                   # StoreLife.SET_ON
WG_READ = 36.0
WG_TALLY = 48.0                                 # the first weight on: StoreScaleRenderer's WEIGHT_AT[0] after the sack
WG_DRAWN, WG_SHEATHED = 42.0, 55.0              # the quill out of the sash, and back in it
WG_WEIGHINGS = [(0.0, 12.0), (30.0, 7.0), (50.0, 2.5), (70.0, 0.0)]    # StoreScaleRenderer: WEIGHT_AT after the sack, TILTS
WG_FLOOR = np.array(sa.clip_pan(sa.GOODS), float)        # the goods pan's floor at rest (6.6, 7.8, -16)
WG_WFLOOR = np.array(sa.clip_pan(sa.WEIGHTS), float)
WG_PIVOT = sa.W.clip_pt(*sa.SCALE_PIVOT, bz=-1)          # the beam's pivot (0, -0.2, -16)
WG_BENCH_Y = 24.0 - sa.BENCH_TOP                         # the bench's top (12) ...
WG_BENCH_Z = sa.W.clip_pt(0.0, 0.0, 14.0, bz=-1)[2]      # ... and its near edge (-10)
WG_NECK = np.array(lp.WEIGH_SACK_NECK, float)
WG_NIB = (0.0, 4.0, 0.0)                                 # the quill's nib (townsfolk_tools' quill)
WG_SASH = np.array([-2.6, 10.4, -5.4])                   # the right fist at the sash's front, the quill's place
WG_READY = np.array([-4.0, 3.8, -10.0])                  # the quill held ready, chest high
WG_JAB = np.array([-5.2, 3.6, -11.6])                    # ... and jabbed at the weights pan

WG_STEPS = [(7.8, 11.8, 'left_leg', (2.6, -4.0)), (19.4, 22.8, 'left_leg', (1.9, 0.0))]
WG_P = [(0, 0.0), (2.0, 4.0), (3.6, 2.0), (6.5, -4.0), (8.4, -2.0), (11.0, 5.0), (14.0, 10.0), (WG_SET, 12.0), (20.0, 7.0),
        (23.0, 2.0), (25.0, 3.0), (28.5, 7.0), (WG_READ, 7.0), (39.0, 4.0), (42.0, 2.0), (WG_TALLY, 5.0), (51.0, 3.0),
        (55.0, 2.0), (58.0, 0.5), (60, 0.0)]
WG_YAW = [(0, 0.0), (2.0, -4.0), (4.0, 0.0), (6.5, 4.0), (11.0, 10.0), (WG_SET, 12.0), (21.0, 3.0), (24.0, 0.0),
          (WG_READ, 0.0), (44.0, 0.0), (WG_TALLY, -8.0), (51.0, -6.0), (55.0, -2.0), (60, 0.0)]
WG_ROLL = [(0, 0.0), (2.0, -1.0), (6.5, -2.5), (11.0, -3.0), (WG_SET, -1.5), (21.0, 0.0), (60, 0.0)]
WG_DIP = [(0, 0.0), (2.0, 0.5), (3.6, 0.2), (6.5, 0.8), (8.4, 0.4), (11.0, 0.3), (WG_SET, 0.9), (20.0, 0.6), (23.0, 0.3),
          (24.5, 0.6), (28.5, 3.4), (WG_READ, 3.4), (38.5, 3.0), (41.5, 0.4), (WG_TALLY, 0.6), (51.0, 0.3), (60, 0.0)]
WG_RX = [(0, 0.0), (8.0, 0.0), (11.8, 0.8), (WG_SET, 0.8), (22.8, 0.0), (60, 0.0)]
WG_RZ = [(0, 0.0), (2.0, 0.6), (3.6, 0.4), (6.5, 0.9), (8.4, 0.2), (11.8, -2.8), (WG_SET, -3.0), (19.4, -2.6), (22.8, 0.0),
         (60, 0.0)]


def wg_feet(t):
    return stepping(t, FEET, WG_STEPS, lift=1.4)


def wg_frame(t):
    pitch = K(WG_P, t)
    feet, _ = wg_feet(t)
    return F_(qt=bend(pitch, K(WG_YAW, t), K(WG_ROLL, t)), vt=(0.0, sink_for(pitch), 0.0),
              pr=la.root_for(feet, K(WG_RX, t), K(WG_RZ, t), dip=K(WG_DIP, t)))


def wg_tilt(t):
    """The beam's tilt (degrees, the goods end down) at [t] of the clip, as StoreScaleRenderer.weighing has it for a sack set
    on at WG_SET (each change swinging the beam on from wherever the last left it); its never-still idle sway left out."""
    a = t - WG_SET
    if a < 0.0:
        return 0.0
    frm = to = start = 0.0
    first = True
    for at, tilt in WG_WEIGHINGS:
        if a < at:
            break
        frm = 0.0 if first else sa.settle(at - start, frm, to)
        to, start, first = tilt, at, False
    return sa.settle(a - start, frm, to)


def wg_hook(side, t):
    """The hook of the pan on [side] (store_art.GOODS or WEIGHTS) at [t], in the clip's space."""
    hx, hy = sa.hook_at(side, wg_tilt(t))
    return sa.W.clip_pt(hx, hy, sa.SCALE_PIVOT[2], bz=-1)


def wg_pan_floor(side, t):
    return wg_hook(side, t) + np.array([0.0, sa.CHAIN + 0.7, 0.0])


def wg_weights(t):
    """How many weights are on the weights pan at [t]."""
    return sum(1 for at, _ in WG_WEIGHINGS[1:] if t - WG_SET >= at)


WG_PATH = None


# the swing up: the straight arm's angle from hanging (degrees, forward) and how far it is turned out, as it lifts the
# sack to shoulder height before the step in
WG_ARC = [(2.0, -12.0, 0.17), (3.6, 0.0, 0.17), (5.0, 38.0, 0.14), (6.8, 76.0, 0.1), (8.4, 90.0, 0.08), (10.4, 93.0, 0.08)]


def wg_hand_off(t):
    """The grip from the neck: just over it while the sack hangs, just behind it as the arm reaches out level."""
    w = smooth(K([(0, 0.0), (3.6, 0.0), (8.4, 1.0), (WG_SET, 1.0)], min(t, WG_SET)))
    return np.array([0.0, -0.7, 0.0]) * (1.0 - w) + np.array([0.0, -0.3, 0.7]) * w


def wg_path():
    """The neck's path (where the fist has the sack): from under the hand at rest, swung back and up on the straight arm
    to shoulder height, carried in over the near rim and let down until the sack stands on the pan's floor."""
    fr0 = wg_frame(0.0)
    n0 = wc.grip_of('left_arm', rest_arm('left_arm', fr0), fr0) - wg_hand_off(0.0)
    keys = [(0, n0)]
    for k, ang, out in WG_ARC:
        sh = torso_to_world(wg_frame(k), PIVOTS['left_arm'])
        a = math.radians(ang)
        d = unit(np.array([out, math.cos(a), -math.sin(a)]))
        keys.append((k, sh + 9.9 * d - wg_hand_off(k)))
    hold = np.array([0.0, -0.3, 0.7])
    keys += [(12.6, np.array([6.8, 2.9, -14.4]) - hold), (15.0, np.array([6.6, 3.3, -15.3]) - hold),
             (17.0, WG_FLOOR + WG_NECK + np.array([0.0, -0.3, 0.0])), (WG_SET, WG_FLOOR + WG_NECK)]
    return keys


# the sack's swing on its neck (degrees about x: positive, its bottom toward the clerk), lagging the hand
WG_SWING = [(0, 0.0), (1.2, 0.0), (2.4, -9.0), (4.0, 6.0), (5.6, 18.0), (7.2, 10.0), (8.8, -6.0), (10.4, -4.0), (12.0, 4.0),
            (13.6, 3.0), (15.4, -1.5), (17.0, 0.0), (WG_SET, 0.0)]


def wg_sack(t):
    """The sack's origin (the middle of its bottom) and turn at [t] (to WG_SET; then it is the scale's)."""
    t = min(t, WG_SET)
    R = rot_x(K(WG_SWING, t))
    N = KV(WG_PATH, t)
    return N - R @ WG_NECK, R


def wg_hold(t):
    """Where the left fist's grip is to have the neck."""
    O, R = wg_sack(t)
    return O + R @ WG_NECK + wg_hand_off(t)


def _sack_track(t, pivot):
    O, R = wg_sack(t)
    return R, O, None


def sack_points(O, R, n=2):
    out = []
    for lo, size in lp.WEIGH_SACK_CUBES.values():
        lo, size = np.asarray(lo, float), np.asarray(size, float)
        out += [O + R @ (lo + size * np.array([i, j, k]) / n) for i in range(n + 1) for j in range(n + 1) for k in range(n + 1)]
    return out


def weigh():
    global WG_PATH
    WG_PATH = wg_path()
    c = la.clip('weigh', WG_LEN, False, look=('merchant', 0), tools_=('weigh_sack', 'quill'), station_='scale_bench',
                events=[('set', WG_SET), ('read', WG_READ), ('tally', WG_TALLY)],
                traces=[('weigh_sack', tuple(WG_NECK), (230, 200, 160)), ('quill', WG_NIB, (255, 230, 160))],
                views=((140, -14), (90, -4), (200, -16)))
    ta.INFO['weigh']['tools_at'] = wc.tools_when([('weigh_sack', 0.0, WG_SET), ('quill', WG_DRAWN, WG_SHEATHED)])
    holds = la.Holds()
    left_off = None
    rec = {}
    for t in la.every(WG_LEN):
        fr = wg_frame(t)
        key_frame(c, t, fr, ease='linear')
        feet, lift = wg_feet(t)
        stand(c, t, fr, feet=feet, lift=lift)
        # the head: on the sack, on the pan as it is carried over and set down and as the pan dips; level with the beam,
        # riding its goods end, along to the other and back to the middle, a nod; on the quill, on the weights pan
        O, Rs = wg_sack(t)
        floor_g = wg_pan_floor(sa.GOODS, t)
        looks = [(0, O + np.array([0.0, -1.5, 0.0])), (2.4, O + np.array([0.0, -1.5, 0.0])), (6.5, WG_FLOOR + np.array([0.0, -1.0, 2.0])),
                 (WG_SET, WG_FLOOR + np.array([0.0, -1.0, 0.0])), (24.5, floor_g + np.array([0.0, -1.0, 0.0])),
                 (28.5, wg_hook(sa.GOODS, t)), (31.0, wg_hook(sa.GOODS, t)), (33.0, wg_hook(sa.WEIGHTS, t)),
                 (34.6, wg_hook(sa.WEIGHTS, t)), (WG_READ, WG_PIVOT + np.array([0.0, 1.0, 0.0])),
                 (38.5, WG_PIVOT + np.array([0.0, 3.0, 0.0])), (41.0, np.array([-2.0, 9.0, -9.0])),
                 (43.5, np.array([-2.0, 9.0, -9.0])), (46.0, WG_READY + np.array([0.0, 1.0, -2.0])),
                 (WG_TALLY, WG_WFLOOR + np.array([0.0, -1.0, 0.0])), (51.0, WG_WFLOOR + np.array([0.0, -1.0, 0.0])),
                 (54.0, WG_PIVOT + np.array([0.0, 2.0, 0.0])), (57.5, np.array([0.0, -2.0, -40.0])), (60, np.array([0.0, -2.0, -40.0]))]
        gaze = smooth(K([(0, 0.0), (1.2, 0.8), (3.0, 1.0), (40.0, 1.0), (42.0, 0.8), (44.0, 0.8), (46.0, 1.0), (54.0, 1.0),
                         (58.5, 0.0), (60, 0.0)], t))
        nods = 6.0 * bump(t, WG_READ - 1.4, WG_READ + 2.0) + 5.0 * bump(t, WG_TALLY + 0.4, WG_TALLY + 3.6)
        tip = 5.0 * bump(t, 29.0, 35.0)                          # the head tipped as it sights along the beam
        qh = look_at(fr, KV(looks, t), gaze, x=nods, z=tip)
        c.key('head', t, qh, ease='linear')
        # the left hand: the sack by the neck, swung, set on the pan; drawn back out between the chains; on the bench's
        # edge while the beam is read; hanging
        rest = rest_arm('left_arm', fr)
        if t <= WG_SET:
            pose = reach_arm(holds, 'left_arm', fr, wg_hold(t), palm=R if t < 6.0 else D, palm_w=0.3, slack=0.8, limit=1.8)
            if t < 1.0:
                pose = mix(rest, pose, smooth(t))
        else:
            if left_off is None:
                left_off = wg_hold(WG_SET)
            out = left_off + np.array([0.0, -0.4, 3.0]) * smooth((t - WG_SET) / 2.4)
            edge = np.array([5.4, WG_BENCH_Y - 0.8, WG_BENCH_Z - 0.6])
            w_edge = smooth(K([(WG_SET, 0.0), (24.5, 0.0), (28.5, 1.0), (38.5, 1.0), (41.5, 0.0), (60, 0.0)], t))
            w_rest = smooth(K([(WG_SET, 0.0), (20.4, 0.0), (24.5, 1.0), (60, 1.0)], t))
            on = reach_arm(holds, 'left_arm', fr, out if t < 21.0 else edge, palm=D, palm_w=0.3, slack=0.8, limit=1.8)
            if t < 21.0:
                pose = mix(on, rest, w_rest)
            else:
                pose = mix(mix(on, rest, w_rest), on, w_edge) if w_edge > 1e-4 else rest
        set_arm(c, 'left_arm', t, pose)
        # the right hand: hanging; to the sash for the quill, up to the lips, held ready and jabbed at the weights pan, back
        # into the sash, hanging
        rest = rest_arm('right_arm', fr)
        if t < 38.5 or t > 58.5:
            pose = rest
        elif t < WG_DRAWN + 0.5:
            pose = reach_arm(holds, 'right_arm', fr, WG_SASH, palm=L, palm_w=0.3, slack=0.8, limit=1.8)
            pose = mix(rest, pose, smooth((t - 38.5) / 3.0))
        elif t < 52.0:
            # up out of the sash and held ready, then jabbed at the weights pan as the weight goes on, and back
            if 'g0' not in rec:
                rec['g0'] = wc.grip_of('right_arm', rec['pose'], fr)
            ready = rec['g0'] + (WG_READY - rec['g0']) * smooth((t - (WG_DRAWN + 0.5)) / 3.0)
            hit = smooth(K([(46.0, 0.0), (WG_TALLY - 0.6, 0.0), (WG_TALLY + 0.4, 1.0), (49.6, 1.0), (51.2, 0.0), (52.0, 0.0)], t))
            pose = reach_arm(holds, 'right_arm', fr, ready + (WG_JAB - WG_READY) * hit, palm=L, palm_w=0.3, slack=0.8, limit=1.8)
        elif t < WG_SHEATHED + 0.5:
            u = smooth((t - 52.0) / (WG_SHEATHED - 52.0))
            pose = reach_arm(holds, 'right_arm', fr, WG_READY + (WG_SASH - WG_READY) * u, palm=L, palm_w=0.3, slack=0.8, limit=1.8)
        else:
            pose = reach_arm(holds, 'right_arm', fr, WG_SASH, palm=L, palm_w=0.3, slack=0.8, limit=1.8)
            pose = mix(pose, rest, smooth((t - WG_SHEATHED - 0.5) / 3.0))
        rec['pose'] = pose
        set_arm(c, 'right_arm', t, pose)
    c.mode('head', 'set')
    la.TRACKS['weigh'] = {'weigh_sack': _sack_track}
    O, Rs = wg_sack(WG_SET)
    check(c, 'the sack on the pan\'s floor, square (t=18: its bottom\'s middle)', O, WG_FLOOR, tol=0.05)
    check(c, 'the sack on the pan\'s floor, square (t=18: its turn)', (float(np.abs(Rs - np.eye(3)).max()),), (0.0,), tol=0.01)
    for k in (0.0, 3.6, 6.5, 9.5, 12.5, 15.0, 17.0, WG_SET):
        check(c, f'left hand on the sack\'s neck (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']), wg_hold(k), tol=1.0)
    check(c, 'the quill held ready (t=46.5)', ta.fk(c, 46.5, 'right_arm', GRIP['right_arm']), WG_READY, tol=1.0)
    check(c, 'the quill jabbed at the weights pan (t=48.5)', ta.fk(c, 48.5, 'right_arm', GRIP['right_arm']), WG_JAB, tol=1.0)
    for k in (30.0, 36.0):
        check(c, f'left hand on the bench\'s edge (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']),
              np.array([5.4, WG_BENCH_Y - 0.8, WG_BENCH_Z - 0.6]), tol=1.0)
    times = [i * 0.5 for i in range(2 * WG_LEN + 1)]
    sack_clear(c, [k for k in times if k <= WG_SET])
    scale_clear(c, times)
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.0)
    joins_idle(c)
    return c


def sack_clear(c, times):
    """Checks the sack keeps out of the body and head, the bench, and the pan's rim until it is over the pan's floor."""
    worst_b, at_b, worst_s, at_s = 0.0, 0.0, 0.0, 0.0
    fixed = _scale_fixed()
    for t in times:
        O, R = wg_sack(t)
        pts = sack_points(O, R)
        mats = _mats(c, t)
        for name in ('body', 'head'):
            blo, bhi = BOXES[name]
            Mi = np.linalg.inv(mats[name])
            for p in pts:
                q = Mi[:3, :3] @ p + Mi[:3, 3]
                inside = min(q[0] - blo[0], bhi[0] - q[0], q[1] - blo[1], bhi[1] - q[1], q[2] - blo[2], bhi[2] - q[2])
                if inside > worst_b:
                    worst_b, at_b = inside, t
        boxes = fixed + ([] if t >= WG_SET - 2.5 else _pan_boxes(sa.GOODS, t, chains=False))
        for p in pts:
            for b in boxes:
                blo, bhi = np.asarray(b[0], float), np.asarray(b[1], float)
                inside = min(float(np.min(p - blo)), float(np.min(bhi - p)))
                if inside > worst_s:
                    worst_s, at_s = inside, t
    check(c, f'the sack clear of the body and head (deepest, at t={at_b:g})', (worst_b,), (0.0,), tol=0.8)
    check(c, f'the sack clear of the scale and bench until it is down (deepest, at t={at_s:g})', (worst_s,), (0.0,), tol=0.3)


def scale_clear(c, times, parts=(('right_arm', 'right hand', 0.8), ('left_arm', 'left hand', 0.8), ('head', 'head', 0.6),
                                  ('body', 'body', 0.6), ('right_leg', 'right leg', 0.6), ('left_leg', 'left leg', 0.6))):
    """clear_of_station for the scale, whose pans move: the boxes of [t] at each [t]."""
    fixed = _scale_fixed()
    for part, label, tol in parts:
        worst, at = 0.0, 0.0
        for t in times:
            d = deepest_in(c, t, part, fixed + _scale_moving(t, sack=False))
            if d > worst:
                worst, at = d, t
        check(c, f'{label} clear of the scale (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


OAK_C, OAK_DC, BRASS_C2, IRON_C, CHAIN_C = (150, 112, 70), (118, 86, 54), (204, 164, 84), (74, 74, 80), (160, 150, 128)
LEDGER_C, PARCH_C, SACKCLOTH_C, TIE_C = (112, 64, 42), (232, 222, 192), (204, 194, 168), (120, 94, 56)


def _sb(lo, hi, col, out):
    """A box of the scale's block (model px, x east, y up) as clip boxes, to [out]."""
    a, b = sa.W.clip_pt(*lo, bz=-1), sa.W.clip_pt(*hi, bz=-1)
    out.append(bx(np.minimum(a, b), np.maximum(a, b), col))


def _scale_fixed():
    """What of the scale stands still: the bench and its shelf, the ledger and the row of weights on it, the plinth, the
    post, its fork and cap (store_art.scale_model)."""
    out = []
    _sb((0.5, 10.0, 2.0), (15.5, 12.0, 14.0), OAK_C, out)
    for x0 in (1.2, 13.0):
        for z0 in (2.8, 11.4):
            _sb((x0, 0.0, z0), (x0 + 1.8, 10.0, z0 + 1.8), OAK_DC, out)
    _sb((3.0, 3.0, 4.6), (13.0, 4.0, 11.4), OAK_DC, out)
    _sb((1.4, 12.0, 2.5), (5.6, 12.9, 6.1), LEDGER_C, out)
    _sb((1.9, 12.9, 2.9), (5.2, 13.3, 5.7), PARCH_C, out)
    for i, x in enumerate((7.0, 9.0, 11.2, 13.2)):
        r, hgt = (1.0, 1.1) if i % 2 == 0 else (0.8, 0.8)
        _sb((x - r, 12.0, 3.6 - r), (x + r, 12.0 + hgt, 3.6 + r), IRON_C, out)
    _sb((5.2, 12.0, 5.4), (10.8, 12.9, 10.6), BRASS_C2, out)
    _sb((6.8, 12.9, 6.8), (9.2, 21.0, 9.2), OAK_C, out)
    for y0, y1 in ((12.9, 13.7), (20.2, 21.0)):
        _sb((6.4, y0, 6.4), (9.6, y1, 9.6), BRASS_C2, out)
    for z0, z1 in ((6.3, 7.3), (8.7, 9.7)):
        _sb((6.6, 21.0, z0), (9.4, 27.0, z1), OAK_C, out)
    _sb((6.2, 27.0, 6.1), (9.8, 27.8, 9.9), BRASS_C2, out)
    _sb((7.3, 27.8, 7.3), (8.7, 28.8, 8.7), BRASS_C2, out)
    return out


def _strut(a, b, r, col, out, n=6):
    for k in range(n):
        p = a + (b - a) * (k + 0.5) / n
        h = np.abs(b - a) / (2 * n) + r
        out.append(bx(p - h, p + h, col))


def _pan_boxes(side, t, chains=True, sack=None, weights=None):
    """The pan on [side] at [t], hanging plumb from its hook (its sway left out): its rim, dish, foot and chains; on the
    goods pan the scale's sack once it is on ([sack]: None, by the clip's time), on the other its weights."""
    out = []
    h = wg_hook(side, t)
    fl = h + np.array([0.0, sa.CHAIN + 0.7, 0.0])
    r, w = sa.RIM_R, 0.6
    for lo, hi in (((-r, -0.9, -r), (r, 0.0, -r + w)), ((-r, -0.9, r - w), (r, 0.0, r)),
                   ((-r, -0.9, -r + w), (-r + w, 0.0, r - w)), ((r - w, -0.9, -r + w), (r, 0.0, r - w))):
        out.append(bx(fl + np.array(lo), fl + np.array(hi), BRASS_C2))
    out.append(bx(fl + np.array([-r + 0.3, 0.0, -r + 0.3]), fl + np.array([r - 0.3, 0.9, r - 0.3]), BRASS_C2))
    out.append(bx(fl + np.array([-1.7, 0.9, -1.7]), fl + np.array([1.7, 1.5, 1.7]), BRASS_C2))
    if chains:
        for ang in (-90.0, 150.0, 30.0):
            a = math.radians(ang)
            foot = h + np.array([-(r - 0.4) * math.cos(a), sa.CHAIN, (r - 0.4) * math.sin(a)])
            _strut(foot, h, 0.25, CHAIN_C, out)
    if side == sa.GOODS:
        if sack if sack is not None else t >= WG_SET:
            for name, (lo, size) in lp.WEIGH_SACK_CUBES.items():
                lo = np.asarray(lo, float)
                out.append(bx(fl + lo, fl + lo + np.asarray(size, float), TIE_C if name == 'tie' else SACKCLOTH_C))
    else:
        n = wg_weights(t) if weights is None else weights
        for i, (x0, x1, z0, z1, hgt) in enumerate(((-1.9, -0.3, -0.8, 0.8, 1.3), (0.2, 1.6, -0.7, 0.7, 0.9),
                                                   (-0.9, 0.5, 0.9, 2.2, 0.6))[:n]):
            out.append(bx(fl + np.array([-x1, -hgt, z0]), fl + np.array([-x0, 0.0, z1]), IRON_C))
    return out


def _scale_moving(t, sack=None, weights=None):
    """The beam at its tilt at [t] (in steps along it: the boxes stand square), its hooks and the two pans."""
    out = []
    a = math.radians(wg_tilt(t))
    for k in range(15):
        u = -7.5 + k + 0.5
        half = 1.25 if abs(u) < 2.2 else 0.85 if abs(u) < 4.6 else 0.55 if abs(u) < 6.6 else 0.75
        p = sa.W.clip_pt(sa.SCALE_PIVOT[0] + u * math.cos(a), sa.SCALE_PIVOT[1] + u * math.sin(a), sa.SCALE_PIVOT[2], bz=-1)
        out.append(bx(p - np.array([0.5, half, 0.55]), p + np.array([0.5, half, 0.55]), BRASS_C2))
    for side in (sa.GOODS, sa.WEIGHTS):
        h = wg_hook(side, t)
        out.append(bx(h - np.array([0.35, 1.35, 0.3]), h + np.array([0.35, 0.0, 0.3]), IRON_C))
        out += _pan_boxes(side, t, sack=sack, weights=weights)
    return out


def _scale_bench():
    """The Store's scale on its bench in the block ahead, as it stands before the sack goes on (the previews that follow
    the clip's time draw its beam and pans from scale_at)."""
    return _scale_fixed() + _scale_moving(0.0, sack=False, weights=0)


def scale_at(t):
    """The scale's boxes at [t] of WEIGH: its beam tipped and swinging, the scale's sack on the goods pan from tick 18 and
    the first weight on the other from tick 48."""
    return _scale_fixed() + _scale_moving(t)


STATION_AT = {'weigh': scale_at}            # the previews' stations that move with the clip's time


def preview_station(name, t):
    """For a preview of [name] at [t]: its station's boxes as they stand then (with the ground), or None if it stands still."""
    fn = STATION_AT.get(name)
    if fn is None:
        return None
    return fn(t) + [b for b in la.station_boxes(ta.INFO[name]['station']) if b[1][1] - b[0][1] < 0.5 and b[1][0] - b[0][0] > 30]


# ================================================================== halt

# ---- halt (25, once): "Hold!" at someone ahead, made to be read from a dozen blocks. A gather (the weight drawn back,
# the arms in); the right foot stamped out ahead and the stance braced wide as the right arm is flung up high and a
# little out, the palm to the front, and the left arm thrown out low to the side to bar the way (hold: the shout and the
# stamp fall here); the chest forward and the head up, the raised hand driven at the one ahead twice (push); then the arms
# let fall, the feet gathered, the shoulders eased. A big Y from the front and a raised, leaning arm from the side;
# nothing is held, nothing stood at.
HL_LEN = 25
HL_HOLD = 4.4
HL_PUSHES = (9.0, 13.0)
HL_STEPS = [(2.2, HL_HOLD, 'right_leg', (-3.2, -3.6)), (2.6, HL_HOLD + 0.4, 'left_leg', (3.2, 0.9)),
            (18.6, 22.0, 'right_leg', (-1.9, 0.0)), (19.6, 23.2, 'left_leg', (1.9, 0.0))]
HL_P = [(0, 0.0), (1.4, -3.0), (2.8, -5.0), (HL_HOLD, 6.0), (5.4, 8.0), (7.0, 5.0), (HL_PUSHES[0], 7.5), (11.0, 5.0),
        (HL_PUSHES[1], 7.5), (15.0, 5.0), (17.5, 3.5), (21.0, 1.0), (25, 0.0)]
HL_YAW = [(0, 0.0), (2.8, 6.0), (HL_HOLD, -4.0), (7.0, -3.0), (17.5, -2.0), (22.0, 0.0), (25, 0.0)]
HL_ROLL = [(0, 0.0), (2.8, -1.0), (HL_HOLD + 0.4, 3.0), (17.0, 2.5), (21.0, 0.6), (25, 0.0)]
HL_DIP = [(0, 0.0), (2.8, 1.2), (HL_HOLD, 2.0), (HL_HOLD + 1.6, 1.2), (17.0, 1.0), (21.0, 0.3), (25, 0.0)]


def hl_arm(fr, t):
    """The right arm: drawn in, flung up high with an overshoot, held, driven forward twice and let fall."""
    hang = rest_arm('right_arm', fr)
    drawn = point_arm(fr, v(f=0.35, u=-0.9, r=0.05), palm=L, lift=0.0)
    snap = point_arm(fr, v(f=0.22, u=1.0, r=0.42), palm=F, lift=1.0)        # past where it holds, the shoulder hunched
    up = point_arm(fr, v(f=0.38, u=1.0, r=0.34), palm=F, lift=0.8)
    pushed = point_arm(fr, v(f=1.0, u=0.85, r=0.28), palm=F, lift=1.0)
    push = sum(bump(t, k - 1.4, k + 1.8) for k in HL_PUSHES)
    if t < 2.8:
        return mix(hang, drawn, smooth(t / 2.8))
    if t < HL_HOLD:
        return mix(drawn, snap, smooth((t - 2.8) / (HL_HOLD - 2.8)))
    if t < 6.4:
        return mix(snap, up, smooth((t - HL_HOLD) / (6.4 - HL_HOLD)))
    if t < 16.6:
        return mix(up, pushed, min(1.0, push))
    # let fall: down through the front to hang
    w = smooth((t - 16.6) / 5.4)
    return mix(up, hang, w) if w < 1.0 else hang


def hl_left(fr, t):
    """The left arm: drawn in with the right, thrown out low to the side (the palm to the front) to bar the way, held,
    let fall."""
    hang = rest_arm('left_arm', fr)
    drawn = point_arm(fr, v(f=0.3, u=-0.92, r=-0.12), palm=R, lift=0.0)
    out_ = point_arm(fr, v(f=0.36, u=-0.18, r=-1.0), palm=F, lift=0.4)
    overshoot = point_arm(fr, v(f=0.3, u=-0.05, r=-1.0), palm=F, lift=0.5)
    if t < 2.8:
        return mix(hang, drawn, smooth(t / 2.8))
    if t < HL_HOLD + 0.4:
        return mix(drawn, overshoot, smooth((t - 2.8) / (HL_HOLD + 0.4 - 2.8)))
    if t < 7.0:
        return mix(overshoot, out_, smooth((t - HL_HOLD - 0.4) / (7.0 - HL_HOLD - 0.4)))
    if t < 16.0:
        return out_
    w = smooth((t - 16.0) / 5.5)
    return mix(out_, hang, w) if w < 1.0 else hang


def halt():
    c = la.clip('halt', HL_LEN, False, look=('guard', 1), events=[('hold', HL_HOLD)] + [('push', k) for k in HL_PUSHES],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((20, -8), (90, -2), (-35, -12)))
    for t in la.every(HL_LEN):
        pitch = K(HL_P, t)
        feet, lift = stepping(t, FEET, HL_STEPS, lift=2.4)
        fr = F_(qt=bend(pitch, K(HL_YAW, t), K(HL_ROLL, t)), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, 0.5 * (feet['right_leg'][0] + feet['left_leg'][0]),
                               0.5 * (feet['right_leg'][1] + feet['left_leg'][1]) + 0.6 * smooth(K([(0, 0.0), (2.8, 1.0), (HL_HOLD, -1.0), (17.0, -1.0), (22.0, 0.0), (25, 0.0)], t)),
                               dip=K(HL_DIP, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        set_arm(c, 'right_arm', t, hl_arm(fr, t))
        set_arm(c, 'left_arm', t, hl_left(fr, t))
        # the head: down a touch in the gather, then up and level at the one ahead, thrust at the shout and each push
        thrust = 6.0 * bump(t, HL_HOLD - 1.0, HL_HOLD + 2.5) + sum(3.5 * bump(t, k - 1.0, k + 1.4) for k in HL_PUSHES)
        c.key('head', t, head_level(fr, x=thrust + 6.0 * bump(t, 0.6, HL_HOLD) - 3.0 * bump(t, HL_HOLD, 19.0)), ease='linear')
    c.mode('head', 'set')
    times = [i * 0.5 for i in range(2 * HL_LEN + 1)]
    for t in (6.5, 8.0, 11.0, 15.0):
        hand = ta.fk(c, t, 'right_arm', GRIP['right_arm'])
        head_top = ta.fk(c, t, 'head', (0.0, -8.0, 0.0))
        check(c, f'the raised hand above the head (t={t:g}: how far under the crown it is)', (max(0.0, hand[1] - head_top[1]),), (0.0,), tol=0.1)
    for t in (6.5, 12.0):
        hand = ta.fk(c, t, 'left_arm', GRIP['left_arm'])
        check(c, f'the left hand out from the side (t={t:g}: how far it is in from 12 px out)', (max(0.0, 12.0 - hand[0]),), (0.0,), tol=0.5)
    clear_of(c, 'right_arm', times, into=('body', 'head'), tol=1.0)
    clear_of(c, 'left_arm', times, into=('body', 'head'), tol=1.0)
    joins_idle(c)
    return c


# ================================================================== rummage

# ---- rummage (60, loop): going through a pack on a counter a block ahead (its top 16 px up, its near face 8 px away),
# where the game has the merchant look for the hall's copy. The pack stands on the counter, the body leaning over it, the
# left hand on its side holding the mouth open and the head bent to it; the right hand digs: down one side and the other,
# up with something to look at and let fall back (look), deeper; the pack lifted by its side and dropped to settle what is
# in it (shake); a straightening and a glance up, the hand resting on the rim; in again, the pack tipped toward the eyes
# and the head bent in close.
RM_LEN = 60
RM_LOOK = 9.0
RM_SHAKE = 21.0
RM_MOUTH = np.array([-0.6, 24.0 - 16.0 - lp.RUMMAGE_PACK_BOX[1][1], -14.0])     # standing on the counter (its bottom at y 8)
RM_EDGE = RM_MOUTH + np.array([0.0, lp.RUMMAGE_PACK_BOX[1][1], -lp.RUMMAGE_PACK_BOX[0][2]])  # its near bottom edge
RM_LIFT = [(0, 0.0), (RM_SHAKE - 2.0, 0.0), (RM_SHAKE - 0.6, 1.6), (RM_SHAKE, 0.0), (RM_SHAKE + 1.2, 0.9), (RM_SHAKE + 1.8, 0.0),
           (60, 0.0)]
RM_TIP = [(0, -6.0), (8.0, -8.0), (12.0, -6.0), (RM_SHAKE - 2.0, -4.0), (RM_SHAKE + 3.0, -4.0), (30.0, 0.0), (36.0, 0.0),
          (42.0, -6.0), (48.0, -14.0), (55.0, -14.0), (60, -6.0)]
RM_TURN = [(0, 0.0), (10.0, 4.0), (20.0, -3.0), (30.0, 0.0), (44.0, -4.0), (52.0, 3.0), (60, 0.0)]
RM_LEFT = np.array([lp.RUMMAGE_PACK_BOX[0][0] + lp.RUMMAGE_PACK_BOX[1][0] + 0.6, 0.6, 0.0])   # the left fist on its side, at the rim
# the right hand's grip in the pack (pack space: x across, y down from the mouth, z toward the body), and how hard it works
RM_DIG = [(0, (-1.2, 1.4, 0.2)), (4.0, (1.2, 2.2, -0.5)), (6.6, (0.4, 1.6, 0.2)), (RM_LOOK, (-3.0, -2.6, 0.6)),
          (11.0, (-2.2, -1.4, 0.6)), (12.6, (-0.4, 1.2, 0.4)), (15.4, (-1.6, 2.0, 0.3)), (RM_SHAKE - 2.0, (0.6, 1.2, -0.2)),
          (RM_SHAKE + 3.0, (0.6, 1.4, -0.2)), (27.0, (1.0, 1.8, -0.6)), (29.6, (-2.6, -0.4, 1.6)), (36.0, (-2.6, -0.5, 1.6)),
          (38.6, (-0.6, 2.0, 0.4)), (42.0, (1.0, 2.4, -0.2)), (45.0, (-1.4, 1.6, 0.6)), (48.6, (0.2, 1.2, -1.2)),
          (52.0, (0.4, 1.0, -1.3)), (55.0, (0.6, 2.2, -0.4)), (57.6, (-1.0, 1.8, 0.0)), (60, (-1.2, 1.4, 0.2))]
RM_WORK = [(0, 1.0), (6.6, 1.0), (8.0, 0.0), (12.6, 0.0), (14.0, 1.0), (RM_SHAKE - 3.0, 1.0), (RM_SHAKE - 2.0, 0.0),
           (RM_SHAKE + 3.0, 0.0), (25.0, 1.0), (28.0, 1.0), (29.0, 0.0), (38.0, 0.0), (39.6, 1.0), (47.6, 1.0), (48.6, 0.0),
           (53.0, 0.0), (54.0, 1.0), (60, 1.0)]
RM_P = [(0, 17.0), (6.0, 19.0), (RM_LOOK, 11.0), (12.0, 17.0), (18.0, 18.0), (RM_SHAKE, 15.0), (26.0, 17.0), (30.0, 10.0),
        (36.0, 9.0), (40.0, 16.0), (46.0, 18.0), (52.0, 21.0), (56.0, 19.0), (60, 17.0)]
RM_YAW = [(0, 0.0), (8.0, -3.0), (16.0, 3.0), (RM_SHAKE, 0.0), (30.0, -2.0), (40.0, 2.0), (50.0, -2.0), (60, 0.0)]
RM_DIP = [(0, 0.6), (RM_SHAKE - 1.0, 0.8), (RM_SHAKE + 0.4, 0.3), (RM_SHAKE + 1.6, 0.9), (30.0, 0.2), (36.0, 0.2),
          (44.0, 0.6), (52.0, 0.9), (60, 0.6)]
RM_GAZE = [(0, 1.0), (28.0, 1.0), (31.0, 0.0), (35.0, 0.0), (38.0, 1.0), (60, 1.0)]
RM_UP = np.array([0.0, -4.0, -40.0])            # the glance up, ahead over the counter


def rm_pack(t):
    """The pack's mouth's middle and its turn at [t]: standing on the counter, tipped toward the body on its near bottom
    edge (about x) and a little round (about y), lifted and dropped for the shake."""
    R = rot_y(K(RM_TURN, t, RM_LEN)) @ rot_x(K(RM_TIP, t, RM_LEN))
    return RM_EDGE + R @ (RM_MOUTH - RM_EDGE) + np.array([0.0, -K(RM_LIFT, t, RM_LEN), 0.0]), R


def rm_dig(t):
    """The right hand's grip in the world at [t]: its path through the pack and a quick working of the fingers."""
    O, R = rm_pack(t)
    p = np.asarray(KV(RM_DIG, t, RM_LEN), float)
    w = K(RM_WORK, t, RM_LEN)
    p = p + w * np.array([0.5 * math.sin(2 * math.pi * t / 4.0), 0.35 * math.sin(2 * math.pi * t / 2.0 + 1.0),
                          0.4 * math.cos(2 * math.pi * t / 4.0)])
    return O + R @ p


def _rm_track(t, pivot):
    O, R = rm_pack(t)
    return R, O, None


def rm_frame(t):
    pitch = K(RM_P, t, RM_LEN)
    sway = 0.4 * math.sin(2 * math.pi * (t - 6.0) / RM_LEN)
    return F_(qt=bend(pitch, K(RM_YAW, t, RM_LEN), 0.6 * math.sin(2 * math.pi * t / RM_LEN)), vt=(0.0, sink_for(pitch), 0.0),
              pr=la.root_for(FEET, x=sway, dip=K(RM_DIP, t, RM_LEN)))


def rummage():
    c = la.clip('rummage', RM_LEN, True, look=('merchant', 2), tools_=('rummage_pack',), station_='stamp_desk',
                events=[('look', RM_LOOK), ('shake', RM_SHAKE)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((35, -16), (90, -4), (-30, -20)))
    holds = la.Holds()
    times = la.every(RM_LEN)
    for lap in range(2):
        for t in times:
            fr = rm_frame(t)
            O, R = rm_pack(t)
            lpose = reach_arm(holds, 'left_arm', fr, O + R @ RM_LEFT, palm=R @ np.array([-1.0, 0.0, 0.0]), palm_w=0.3, slack=0.8, limit=1.8)
            rpose = reach_arm(holds, 'right_arm', fr, rm_dig(t), palm=R @ np.array([0.0, 1.0, 0.0]), palm_w=0.3, slack=0.8,
                              limit=1.8)
            look = rm_dig(t) * 0.7 + rm_pack(t)[0] * 0.3
            q_head = look_at(fr, look if K(RM_GAZE, t, RM_LEN) > 0.999 else look * K(RM_GAZE, t, RM_LEN) + RM_UP * (1.0 - K(RM_GAZE, t, RM_LEN)), 1.0)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr)
                set_arm(c, 'left_arm', t, lpose)
                set_arm(c, 'right_arm', t, rpose)
                c.key('head', t, q_head, ease='linear')
    c.mode('head', 'set')
    seal(c)
    la.TRACKS['rummage'] = {'rummage_pack': _rm_track}
    for k in (0.0, 15.0, 30.0, 45.0):
        O, R = rm_pack(k)
        check(c, f'left fist on the pack\'s rim (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']), O + R @ RM_LEFT, tol=1.0)
    for k in (0.0, 4.0, RM_LOOK, 15.4, 27.0, 42.0, 50.0, 55.0):
        check(c, f'right hand where it works (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), rm_dig(k), tol=1.0)
    times = [i * 0.5 for i in range(2 * RM_LEN)]
    pack_clear(c, times)
    clear_of_station(c, la.station_boxes('stamp_desk'), times, what='a counter a block ahead')
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.0)
    return c


def pack_clear(c, times):
    """Checks the pack keeps out of the body and the head, and out of a counter a block ahead."""
    lo, size = (np.asarray(x, float) for x in lp.RUMMAGE_PACK_BOX)
    worst, at, worst_c, at_c = 0.0, 0.0, 0.0, 0.0
    counter = la.station_boxes('stamp_desk')[0]
    for t in times:
        O, R = rm_pack(t)
        pts = [O + R @ (lo + size * np.array([i, j, k]) / 3) for i in range(4) for j in range(4) for k in range(4)]
        mats = _mats(c, t)
        for name in ('body', 'head'):
            blo, bhi = BOXES[name]
            Mi = np.linalg.inv(mats[name])
            for p in pts:
                q = Mi[:3, :3] @ p + Mi[:3, 3]
                inside = min(q[0] - blo[0], bhi[0] - q[0], q[1] - blo[1], bhi[1] - q[1], q[2] - blo[2], bhi[2] - q[2])
                if inside > worst:
                    worst, at = inside, t
        for p in pts:
            inside = min(float(np.min(p - np.asarray(counter[0]))), float(np.min(np.asarray(counter[1]) - p)))
            if inside > worst_c:
                worst_c, at_c = inside, t
    check(c, f'the pack clear of the body and head (deepest, at t={at:g})', (worst,), (0.0,), tol=0.6)
    check(c, f'the pack clear of a counter a block ahead (deepest, at t={at_c:g})', (worst_c,), (0.0,), tol=0.3)


# ================================================================== the registry

TRADE_CLIPS = ['stamp', 'check_load', 'load_pack', 'hitch', 'weigh', 'halt', 'rummage']
MAKERS = [('stamp', stamp), ('check_load', check_load), ('load_pack', load_pack), ('hitch', hitch), ('weigh', weigh),
          ('halt', halt), ('rummage', rummage)]
STATION_BOXES = {'stamp_desk': _stamp_desk, 'cart_covered': _cart_covered, 'wagon_tail_load': _wagon_tail_load,
                 'horse_hitch': _horse_hitch, 'scale_bench': _scale_bench}
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
        'load_pack': {'take': ('hands', 0.0), 'heave': ('hands', 0.0), 'set': ('part', 'pack_basket', tuple(LP_BOTTOM)),
                      'shove': ('part', 'pack_basket', tuple(LP_BOTTOM)), 'dust': ('hands', 0.0)},
        'hitch': {'back': ('part', 'right_arm', tuple(GRIP['right_arm'])), 'grab': ('point', tuple(HT_ANCHOR)),
                  'clip': ('point', tuple(HT_HAME)), 'tug': ('point', tuple(HT_HAME)),
                  'pat': ('part', 'right_arm', tuple(GRIP['right_arm']))},
        'weigh': {'set': ('point', tuple(WG_FLOOR)), 'read': ('point', tuple(WG_PIVOT)), 'tally': ('point', tuple(WG_WFLOOR))},
        'halt': {'hold': ('part', 'right_arm', tuple(GRIP['right_arm'])), 'push': ('part', 'right_arm', tuple(GRIP['right_arm']))},
        'rummage': {'look': ('part', 'right_arm', tuple(GRIP['right_arm'])), 'shake': ('part', 'rummage_pack', (0.0, 2.8, 0.0))},
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
