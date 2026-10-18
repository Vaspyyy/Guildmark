"""
The townsfolk's talking gestures (living-world/design-voices.md, section 6): explaining, gossiping, hushing, waving off,
sighing, thinking, tipping the hat, introducing themselves, standing wary with folded arms, recoiling, a wave and a nod in
passing (walking overlays), gossip and laughter on a seat, and setting the work down to talk. Built with living_anims.py's
kit (the townsfolk skeleton, its IK and forward kinematics), which imports this module and calls build() with itself, so
these are checked and written with the Living World's other clips and LivingSchedule.kt stays one generated file:

    python3 -B art/scripts/living_anims.py src/main/resources/assets/guildmark <preview-dir> [clip ...]

and, for the animated previews (a GIF of each, three views side by side, at the game's pace):

    python3 -B art/scripts/talk_clips.py <gif-dir> [clip ...]

What the code does round them (living-world/design-voices.md, section 2): it turns the body to the player and the head
to their eyes (every head here is 'add', over that look), turns only the head of someone seated, times the listening nods
and plays the walking overlays over any walk. So the standing one-shots start and end on idle's first frame and the
seated ones on sit's; the overlays move only the right arm (from and back to hanging at rest) and the head, leaving the
left arm and the legs to the walk; the loops that are a gesture (explaining, gossip) come back to rest at their seam,
and only the wary fold of the arms holds its pose round it.
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

import townsfolk_anims as ta
from townsfolk_anims import (qeuler, qmul, qmat, qslerp, unit, I, v, B, U, D, L, F_, bend, key_frame, free, check, seal,
                             smooth, bump, torso_to_world, GRIP, PIVOTS, SEAT_DROP, seat_legs, knee_hand)

WANTED = ['explain', 'gossip', 'shush', 'wave_off', 'sigh', 'think', 'tip_hat', 'introduce', 'arms_folded', 'recoil',
          'wave_walk', 'nod_walk', 'sit_gossip', 'sit_laugh', 'pause_work']


def build(la):
    """Makes the gestures with living_anims' kit [la] (the module running), registering each clip there with its joins
    and checks."""
    global clip, K, KV, hang, Holds, mix_arm, key_arm, plant, root_for, leg_end, join, getc, every, IDLE_ARM_OUT
    clip, K, KV, hang, Holds, mix_arm, key_arm = la.clip, la.K, la.KV, la.hang, la.Holds, la.mix_arm, la.key_arm
    plant, root_for, leg_end, join, getc, every = la.plant, la.root_for, la.leg_end, la.join, la.getc, la.every
    IDLE_ARM_OUT = la.IDLE_ARM_OUT
    for make in (explain, gossip, shush, wave_off, sigh, think, tip_hat, introduce, arms_folded, recoil, wave_walk,
                 nod_walk, sit_gossip, sit_laugh, pause_work):
        make()

# ================================================================== the pieces these share

REST = F_()                                     # standing at rest: idle's first frame (the torso and root unmoved)
FEET = {'right_leg': (-1.9, 0.0), 'left_leg': (1.9, 0.0)}


def head_pt(fr, qh, p):
    """Where a head-space point (the lips, the brow) is for an 'add' head key [qh] under [fr]: CombatAnimator carries the
    head's pivot with the torso but turns it only by the look and the key (the previews' look is straight ahead), so a
    lean keeps the eyes on whoever they face."""
    return torso_to_world(fr, PIVOTS['head']) + qmat(qmul(fr['qr'], qh)) @ np.asarray(p, float)


def rest_arm(side, fr=REST, out=None, fwd=0.0, lift=0.0):
    """An arm hanging at rest (idle's own, [fwd] degrees forward), its shoulder up [lift] px: a (key, offset) pose."""
    return hang(side, fr, out=out, fwd=fwd), np.array([0.0, -lift, 0.0])


def point_arm(fr, d, palm=None, lift=0.0):
    """A free arm along world [d], its front face (the palm) towards world [palm], the shoulder up [lift] px."""
    return free(fr, unit(d), None if palm is None else unit(palm)), np.array([0.0, -lift, 0.0])


def reach_arm(holds, side, fr, target, palm=None, palm_w=0.3, slack=0.6, limit=1.6):
    """The arm with its hand (the grip, the middle of the block's end) on world [target], the shoulder rolling up to
    [limit] px to get it there, the palm towards world [palm]: solved from the last answer, so a hand on a path never
    flips."""
    dirs = [] if palm is None else [((0.0, 0.0, -1.0), unit(palm), palm_w)]
    return holds.slack(side, fr, [(GRIP[side], np.asarray(target, float), 3.0)], dirs, slack=slack, limit=limit)


def lifted(pose, lift):
    """[pose] with the shoulder raised [lift] px more."""
    return pose[0], np.asarray(pose[1], float) + np.array([0.0, -lift, 0.0])


def set_arm(c, side, t, pose):
    key_arm(c, side, t, pose)


def stand(c, t, fr, feet=FEET, lift=(0.0, 0.0)):
    """Both feet planted where [feet] has them, however the root moves the hips."""
    plant(c, t, fr, right=feet['right_leg'], left=feet['left_leg'], lift=lift)


# the parts' blocks in their own spaces; for an arm only its hand, the block's last 4 px
BOXES = {'body': ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)), 'head': ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0)),
         'right_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)), 'left_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)),
         'right_arm': ((-3.0, 6.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, 6.0, -2.0), (3.0, 10.0, 2.0))}


def deepest(c, t, side, into=('body', 'head')):
    """How deep (px) the hand of [side]'s arm goes into the parts [into] at [t] of [c] (0: clear). Only the hand: a block
    arm swung in front of the chest or up to the face always sinks its upper half into the torso's corner or the jaw's,
    as vanilla's own arms do, but a hand inside the chest or the face would show."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    pose = ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t))
    mats = ta.part_matrices(look, pose)
    lo, hi = BOXES[side]
    pts = [np.array([lo[0] + (hi[0] - lo[0]) * i / 4, lo[1] + (hi[1] - lo[1]) * j / 4, lo[2] + (hi[2] - lo[2]) * k / 4])
           for i in range(5) for j in range(5) for k in range(5)]
    world = [mats[side][:3, :3] @ p + mats[side][:3, 3] for p in pts]
    worst = 0.0
    for name in into:
        blo, bhi = BOXES[name]
        Mi = np.linalg.inv(mats[name])
        for w in world:
            p = Mi[:3, :3] @ w + Mi[:3, 3]
            worst = max(worst, min(p[0] - blo[0], bhi[0] - p[0], p[1] - blo[1], bhi[1] - p[1], p[2] - blo[2], bhi[2] - p[2]))
    return worst


def clear_of(c, side, times, into=('body', 'head'), tol=0.6):
    """Checks [side]'s hand keeps out of [into] (to within [tol] px) all through [times]."""
    worst, at = max((deepest(c, t, side, into), t) for t in times)
    hand = side.replace('_arm', ' hand')
    check(c, f'{hand} clear of the {" and ".join(n.replace("_", " ") for n in into)} (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


def at_hand(c, t, side, want, label, tol=0.6):
    check(c, f'{label} (t={t:g})', ta.fk(c, t, side, GRIP[side]), want, tol=tol)


def at_least(c, label, got, least, tol=0.05):
    """Checks every one of [got] is [least] or more."""
    got = [float(g) for g in got]
    check(c, f'{label}: at least {least:g}', got, [max(g, least) for g in got], tol=tol)


def hands_apart(c, times, least=4.0):
    """Checks the hands' ends never come nearer than a hand's width, [least] px (centre to centre): they'd be in each
    other."""
    gap, at = min((float(np.linalg.norm(ta.fk(c, t, 'right_arm', GRIP['right_arm']) - ta.fk(c, t, 'left_arm', GRIP['left_arm']))), t)
                  for t in times)
    at_least(c, f'the hands apart (nearest, t={at:g})', (gap,), least)


def feet_down(c, times):
    for t in times:
        for side in ('right_leg', 'left_leg'):
            check(c, f'{side} foot on the ground (t={t:g})', (leg_end(c, t, side)[1],), (24.0,), tol=0.3)


def wave(t, a, b, period, amp=1.0):
    """A shake between ticks [a] and [b]: a sine of [period] ticks under a hump that fades it in and out."""
    return amp * bump(t, a, b) * math.sin(2 * math.pi * (t - a) / period)


def joins_standing(c):
    join(f'{c.name} starts on idle', c, 0, getc('idle'), 0, tol=1.0)
    join(f'{c.name} ends on idle', c, c.length, getc('idle'), 0, tol=1.0)

# ================================================================== explaining

# ---- explain (60, loop): their work, the way somewhere, a price. A breath, then both hands come up together and shape
# the thing between them, palms in: round, down and under, and wide on the beat ("about so big", tick 20); the left
# hand settles low before them, palm up, as if holding what's told, while the right lays three points out over it with
# the edge of the hand, from left to right ("there, there and there": 30, 35, 40), a little nod with each; both hands
# open out palms up with a firm nod ("and there you are", 45), and come down to the sides for a breath before the next
# go (the seam is at rest).
EX_SHAPE_DIR = [(2.0, (0.55, -0.85, 0.12)), (7.0, (1.0, -0.20, 0.22)), (10.5, (1.0, -0.30, 0.62)),
                (14.0, (1.0, -0.62, 0.36)), (16.5, (1.0, -0.52, 0.20)), (20.0, (0.90, -0.28, 0.88)), (23.5, (1.0, -0.40, 0.55))]
EX_SHAPE_PALM = [(2.0, (0.6, 0.6, -0.6)), (7.0, (0.0, 0.25, -1.0)), (10.5, (0.0, 0.2, -1.0)), (14.0, (0.0, 0.7, -0.7)),
                 (16.5, (0.0, 0.5, -1.0)), (20.0, (0.35, 0.55, -0.6)), (23.5, (0.2, 0.5, -0.8))]
EX_OPEN = (1.0, -0.42, 0.78)                    # both hands open out, palms up
EX_BEATS = (30.0, 35.0, 40.0)
EX_BASE = (0.85, -0.62, 0.10)                   # the left hand low before them, palm up, pointing in a little
EX_UP = [(0, 0.0), (1.0, 0.0), (7.0, 1.0), (60, 1.0)]               # off the sides
EX_POINTS = [(0, 0.0), (22.0, 0.0), (27.0, 1.0), (60, 1.0)]         # the points laid out
EX_OPEN_W = [(0, 0.0), (41.0, 0.0), (44.5, 1.0), (60, 1.0)]         # opened out
EX_DOWN = [(0, 0.0), (47.5, 0.0), (55.0, 1.0), (60, 1.0)]           # back to the sides
EX_SWING = [(0, 0.0), (53.0, 0.0), (56.0, -4.0), (58.5, 1.0), (60, 0.0)]
# the right hand laying the points out: across (to its right as r grows), and up and down as it lifts and comes down
# on each beat, fast into it (the key just before) and stopping there with a little bounce
EX_PT_R = [(22.0, -0.30), (28.0, -0.22), (30.0, -0.20), (32.5, 0.08), (35.0, 0.12), (37.5, 0.38), (40.0, 0.42), (44.0, 0.42)]
EX_PT_U = [(22.0, -0.10), (28.0, 0.10), (29.3, -0.24), (30.0, -0.36), (31.5, -0.27), (33.5, 0.08), (34.3, -0.24),
           (35.0, -0.36), (36.5, -0.27), (38.5, 0.08), (39.3, -0.25), (40.0, -0.38), (41.5, -0.30), (44.0, -0.30)]
EX_P = [(0, 0.0), (2.5, -1.2), (7.0, 1.0), (10.5, 2.5), (14.0, 1.5), (17.0, 1.2), (20.0, 4.0), (23.5, 2.0), (27.0, 3.0),
        (30.0, 4.5), (33.0, 3.2), (35.0, 4.3), (38.0, 3.3), (40.0, 4.6), (42.5, 2.5), (45.0, 5.5), (48.5, 1.5), (54.0, -0.6),
        (57.0, 0.3), (60, 0.0)]
EX_Y = [(0, 0.0), (22.0, 0.0), (27.0, -4.0), (30.0, -4.5), (35.0, -0.5), (40.0, 3.0), (45.0, 0.0), (60, 0.0)]
EX_HX = [(0, 0.0), (2.5, -3.0), (7.0, 2.0), (10.5, -1.0), (14.0, 4.0), (17.0, 1.0), (20.0, -2.5), (24.0, 1.0), (28.0, -1.5),
         (30.0, 6.5), (33.0, 0.0), (35.0, 5.5), (38.0, 0.0), (40.0, 6.5), (42.5, -1.0), (45.0, 11.0), (48.0, 1.0), (52.0, -1.5),
         (56.0, 0.5), (60, 0.0)]
EX_HY = [(0, 0.0), (7.0, 3.0), (14.0, -3.0), (20.0, 4.0), (24.0, 0.0), (28.0, -5.0), (30.0, -7.0), (34.0, -1.0), (35.0, 0.0),
         (39.0, 5.0), (40.0, 6.0), (43.0, 0.0), (60, 0.0)]
EX_HZ = [(0, 0.0), (7.0, 3.0), (14.0, -2.5), (20.0, 4.0), (25.0, 0.0), (45.0, -3.0), (50.0, 0.0), (60, 0.0)]
EX_RX = [(0, 0.0), (10.0, 0.3), (24.0, -0.2), (36.0, 0.5), (48.0, 0.0), (60, 0.0)]


def explain():
    c = clip('explain', 60, True, look=('smith', 1), events=[('beat', 20)] + [('point', k) for k in EX_BEATS] + [('nod', 45)],
             views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    for t in every(60):
        fr = F_(qt=bend(K(EX_P, t), K(EX_Y, t), 0.0), pr=root_for(FEET, x=K(EX_RX, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        c.key('head', t, qeuler(x=K(EX_HX, t), y=K(EX_HY, t), z=K(EX_HZ, t)), ease='linear')
        beat = sum(bump(t, k - 0.8, k + 1.6) for k in EX_BEATS)
        for side in ('right_arm', 'left_arm'):
            s = 1.0 if side == 'right_arm' else -1.0
            tt = t - (0.0 if side == 'right_arm' else 0.8)      # the left a little behind the right
            rest = rest_arm(side, fr, fwd=K(EX_SWING, tt))
            d = KV([(k, (f, u, s * r)) for k, (f, u, r) in EX_SHAPE_DIR], min(max(tt, 2.0), 23.5))
            p = KV([(k, (f, u, s * r)) for k, (f, u, r) in EX_SHAPE_PALM], min(max(tt, 2.0), 23.5))
            pose = mix_arm(rest, point_arm(fr, v(*d), v(*p), lift=0.4), smooth(K(EX_UP, tt)))
            if side == 'right_arm':
                points = point_arm(fr, v(f=1.0, u=K(EX_PT_U, t), r=K(EX_PT_R, t)), L, lift=0.4)
            else:
                points = point_arm(fr, v(f=EX_BASE[0], u=EX_BASE[1] - 0.05 * beat, r=EX_BASE[2]), U, lift=0.2)
            pose = mix_arm(pose, points, smooth(K(EX_POINTS, tt)))
            pose = mix_arm(pose, point_arm(fr, v(EX_OPEN[0], EX_OPEN[1], s * EX_OPEN[2]), U, lift=0.5), smooth(K(EX_OPEN_W, tt)))
            set_arm(c, side, t, mix_arm(pose, rest, smooth(K(EX_DOWN, tt))))
    c.mode('head', 'add')
    seal(c)
    xs = [ta.fk(c, k, 'right_arm', GRIP['right_arm'])[0] for k in EX_BEATS]
    at_least(c, 'the points laid out from left to right, apart (px, t=30 to 35, 35 to 40)', (xs[0] - xs[1], xs[1] - xs[2]), 2.5)
    drops = [ta.fk(c, k, 'right_arm', GRIP['right_arm'])[1] - ta.fk(c, k - 1.5, 'right_arm', GRIP['right_arm'])[1] for k in EX_BEATS]
    at_least(c, 'the hand coming down onto each point (px in the last 1.5 ticks)', drops, 2.0)
    hands_apart(c, every(60))
    clear_of(c, 'right_arm', every(60))
    clear_of(c, 'left_arm', every(60))
    join('explain starts on idle', c, 0, getc('idle'), 0, tol=1.0)

# ================================================================== gossip

# ---- gossip (60, loop): a rumour, a secret. A quick look aside, then a lean in toward the one they face, the hips going
# back over the heels and the right shoulder coming round, the right hand up beside the mouth to keep the words from the
# street; a glance over to the left (16), back and leaning closer with the head tipped in, a glance to the right (38), a
# knowing nod ("mark my words", 45), and easing back upright, the hand coming down, for the next go (the seam is at rest).
MOUTH_SIDE_R = np.array([-4.4, -2.0, -8.4])     # head space: beside the mouth at its right, out before the face (the
                                                # mouth itself left showing to the one they face)
GS_LEAN = [(0, 0.0), (2.0, 0.0), (8.0, 1.0), (14.0, 1.0), (18.5, 0.7), (23.0, 1.1), (36.0, 1.0), (40.0, 0.85),
           (46.0, 1.1), (50.0, 1.0), (56.0, 0.0), (60, 0.0)]
GS_HAND = [(0, 0.0), (2.5, 0.0), (8.5, 1.0), (49.5, 1.0), (55.5, 0.0), (60, 0.0)]
GS_SWING = [(0, 0.0), (53.5, 0.0), (56.5, -4.0), (58.5, 0.8), (60, 0.0)]
GS_HY = [(0, 0.0), (1.5, -10.0), (4.0, -7.0), (7.0, 0.0), (14.0, 0.0), (16.5, -27.0), (19.5, -25.0), (22.5, 0.0), (36.0, 0.0),
         (38.5, 24.0), (41.5, 21.0), (44.0, 0.0), (60, 0.0)]
GS_HX = [(0, 0.0), (4.0, 2.0), (8.0, 5.0), (10.5, 2.5), (12.5, 5.0), (15.0, 1.0), (19.5, -1.0), (24.0, 6.0), (27.0, 3.5),
         (30.0, 7.0), (33.0, 4.0), (36.0, 6.0), (39.0, 1.0), (42.5, 3.0), (45.5, 11.0), (48.5, 5.0), (54.0, -1.5), (57.0, 0.5), (60, 0.0)]
GS_HZ = [(0, 0.0), (8.0, -4.0), (14.0, -4.0), (17.0, 1.0), (22.0, -6.0), (35.0, -5.0), (39.0, 2.0), (45.0, -6.0), (52.0, 0.0), (60, 0.0)]


def gossip():
    c = clip('gossip', 60, True, look=('citizen', 6), events=[('glance', 16.5), ('glance', 38.5), ('nod', 45.5)],
             views=((32, -12), (90, -2), (0, -4)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    holds = Holds()
    for t in every(60):
        lean = K(GS_LEAN, t)
        pitch = 9.5 * lean
        pr = root_for(FEET, z=0.9 * lean, dip=0.35 * lean)
        fr = F_(qt=bend(pitch, -8.0 * lean, -1.5 * lean), vt=(0.0, ta.sink_for(pitch), 0.0), pr=pr)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        qh = qeuler(x=K(GS_HX, t), y=K(GS_HY, t), z=K(GS_HZ, t))
        c.key('head', t, qh, ease='linear')
        rest = rest_arm('right_arm', fr, fwd=K(GS_SWING, t))
        w = smooth(K(GS_HAND, t))
        pose = rest
        if w > 1e-4:
            mouth = head_pt(fr, qslerp(I, qh, 0.55), MOUTH_SIDE_R)
            pose = mix_arm(rest, lifted(reach_arm(holds, 'right_arm', fr, mouth, palm=v(f=0.3, u=0.1, r=-1.0)), 0.2), w)
        set_arm(c, 'right_arm', t, pose)
        c.key('left_arm', t, hang('left_arm', fr, fwd=1.5 * lean + 1.2 * bump(t, 44.0, 52.0)), ease='linear')
    c.mode('head', 'add')
    seal(c)
    for k in (12.0, 30.0):
        at_hand(c, k, 'right_arm', head_pt(ta.frame_at(c, k), qslerp(I, c.sample('head', k)[0], 0.55), MOUTH_SIDE_R),
                'right hand by the mouth')
    clear_of(c, 'right_arm', every(60))
    feet_down(c, (8.0, 30.0))
    join('gossip starts on idle', c, 0, getc('idle'), 0, tol=1.0)

# ================================================================== shush

# ---- shush (30): "Hush!" A breath in, and the right hand comes straight up with a finger to the lips (6.5) as the body
# leans in and the head dips to meet it; a little push of it ("shh!", 8.5), then a look round, left (13.5) and right
# (18.5), the finger kept to the lips; back to the listener with a nod, and the hand comes down.
FINGER_LIPS = np.array([-0.8, -1.3, -8.6])      # head space: just before the lips
SH_HAND = [(0, 0.0), (1.2, 0.0), (6.5, 1.0), (21.5, 1.0), (26.0, 0.0), (30, 0.0)]
SH_PUSH = [(0, 0.0), (6.5, 0.0), (8.5, 1.0), (11.0, 0.0), (30, 0.0)]
SH_SWING = [(0, 0.0), (24.5, 0.0), (27.0, -4.0), (29.0, 0.6), (30, 0.0)]
SH_P = [(0, 0.0), (1.5, -1.5), (6.5, 5.0), (8.5, 6.5), (11.0, 5.0), (13.5, 3.5), (18.5, 3.5), (21.0, 4.5), (24.0, 1.0), (27.5, -0.6), (30, 0.0)]
SH_Y = [(0, 0.0), (11.0, 0.0), (13.5, -4.0), (16.0, -3.5), (18.5, 3.0), (21.0, 0.0), (30, 0.0)]
SH_HX = [(0, 0.0), (1.5, -3.0), (6.5, 4.0), (8.5, 6.5), (11.0, 3.0), (13.5, 1.0), (18.5, 1.0), (22.0, 7.0), (24.5, 0.0), (27.0, -1.0), (30, 0.0)]
SH_HY = [(0, 0.0), (11.0, 0.0), (13.5, -32.0), (15.5, -30.0), (18.5, 26.0), (20.5, 22.0), (22.5, 0.0), (30, 0.0)]
SH_HZ = [(0, 0.0), (6.5, -2.0), (11.0, -2.0), (13.5, 3.0), (18.5, -3.0), (22.5, 0.0), (30, 0.0)]


def shush():
    c = clip('shush', 30, False, look=('citizen', 4), events=[('shush', 6.5), ('look', 13.5), ('look', 18.5)],
             views=((32, -12), (90, -2), (0, -4)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    holds = Holds()
    for t in every(30):
        push = K(SH_PUSH, t)
        fr = F_(qt=bend(K(SH_P, t), K(SH_Y, t), 0.0), pr=root_for(FEET, z=-0.3 * smooth(K(SH_HAND, t)) - 0.3 * push))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        qh = qeuler(x=K(SH_HX, t), y=K(SH_HY, t), z=K(SH_HZ, t))
        c.key('head', t, qh, ease='linear')
        rest = rest_arm('right_arm', fr, fwd=K(SH_SWING, t))
        w = smooth(K(SH_HAND, t))
        pose = rest
        if w > 1e-4:
            lips = head_pt(fr, qslerp(I, qh, 0.7), FINGER_LIPS + np.array([0.0, 0.0, -0.6 * push]))
            pose = mix_arm(rest, reach_arm(holds, 'right_arm', fr, lips, palm=v(f=0.0, u=0.2, r=-1.0)), w)
        set_arm(c, 'right_arm', t, pose)
        c.key('left_arm', t, hang('left_arm', fr, fwd=1.2 * bump(t, 4.0, 12.0), out=IDLE_ARM_OUT + 1.0 * bump(t, 12.0, 21.0)),
              ease='linear')
    c.mode('head', 'add')
    at_hand(c, 6.5, 'right_arm', head_pt(ta.frame_at(c, 6.5), qslerp(I, c.sample('head', 6.5)[0], 0.7), FINGER_LIPS),
            'finger to the lips')
    clear_of(c, 'right_arm', every(30))
    joins_standing(c)

# ================================================================== waving off

# ---- wave_off (24): "Can't stop!", "Don't be daft." The right hand comes up before the chest, its back to the listener,
# and is flung out and down away to the right, palm out (tick 6.5), as the head turns aside to the left and tips back,
# the body leaning back and the right shoulder going back with the flick; a smaller second flick ("pah", 10.5), and the
# hand drops back to the side as the head comes round again.
WO_IN = [(0, 0.0), (0.5, 0.0), (3.5, 1.0), (4.5, 1.0), (7.0, 0.0), (24, 0.0)]
WO_OUT = [(0, 0.0), (3.5, 0.0), (6.5, 1.0), (8.8, 0.82), (10.5, 1.04), (12.5, 1.0), (24, 1.0)]
WO_DOWN = [(0, 0.0), (12.5, 0.0), (18.5, 1.0), (24, 1.0)]
WO_SWING = [(0, 0.0), (17.0, 0.0), (19.5, -5.0), (22.0, 1.0), (24, 0.0)]
WO_P = [(0, 0.0), (3.0, 1.2), (6.5, -3.0), (10.5, -2.5), (14.0, -1.0), (18.0, 0.5), (24, 0.0)]
WO_Y = [(0, 0.0), (3.0, -3.0), (6.5, 6.0), (11.0, 5.0), (16.0, 1.0), (24, 0.0)]
WO_HX = [(0, 0.0), (3.0, 2.0), (6.5, -6.0), (11.0, -5.0), (15.0, 1.0), (19.0, -0.5), (24, 0.0)]
WO_HY = [(0, 0.0), (3.0, 4.0), (6.5, -27.0), (12.0, -24.0), (17.0, -3.0), (20.0, 1.0), (24, 0.0)]
WO_HZ = [(0, 0.0), (3.0, -1.0), (6.5, 7.0), (12.0, 5.0), (17.0, 0.0), (24, 0.0)]


def wave_off():
    c = clip('wave_off', 24, False, look=('merchant', 0), events=[('flick', 6.5)], views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    for t in every(24):
        fr = F_(qt=bend(K(WO_P, t), K(WO_Y, t), 0.0), pr=root_for(FEET, z=0.4 * bump(t, 4.0, 16.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        c.key('head', t, qeuler(x=K(WO_HX, t), y=K(WO_HY, t), z=K(WO_HZ, t)), ease='linear')
        rest = rest_arm('right_arm', fr, fwd=K(WO_SWING, t))
        flick2 = 0.08 * bump(t, 8.8, 12.5)
        inner = point_arm(fr, v(f=0.85, u=-0.45, r=-0.18), B, lift=0.3)
        outer = point_arm(fr, v(f=0.55 - flick2, u=-0.6 + 0.9 * flick2, r=0.85), v(f=0.5, u=-0.4, r=1.0), lift=0.4)
        pose = mix_arm(rest, inner, smooth(K(WO_IN, t)))
        pose = mix_arm(pose, outer, K(WO_OUT, t) if t > 6.0 else smooth(K(WO_OUT, t)))
        pose = mix_arm(pose, rest, smooth(K(WO_DOWN, t)))
        set_arm(c, 'right_arm', t, pose)
        c.key('left_arm', t, hang('left_arm', fr, fwd=-1.5 * bump(t, 4.0, 12.0)), ease='linear')
    c.mode('head', 'add')
    clear_of(c, 'right_arm', every(24))
    joins_standing(c)

# ================================================================== sigh

# ---- sigh (40): bad news, sympathy. A long breath in, the chest and shoulders rising and the chin coming up (9.5);
# let out with the shoulders dropping below where they were, the body slumping and the knees giving a little, the head
# going down; a slow shake of the head over it (twice, 19 to 32), and back up.
SG_B = [(0, 0.0), (9.5, 1.0), (11.0, 0.9), (17.0, -0.4), (26.0, -0.35), (33.0, -0.1), (40, 0.0)]
SG_P = [(0, 0.0), (9.5, -3.0), (12.0, -1.0), (17.0, 6.5), (30.0, 5.5), (35.0, 2.0), (38.0, -0.4), (40, 0.0)]
SG_HX = [(0, 0.0), (9.5, -6.0), (12.0, 0.0), (17.0, 17.0), (30.0, 14.0), (34.5, 4.0), (38.0, -1.0), (40, 0.0)]


def sigh():
    c = clip('sigh', 40, False, look=('citizen', 3), events=[('sigh', 12)], views=((32, -12), (90, -2), (0, -4)),
             traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))])
    for t in every(40):
        b = K(SG_B, t)
        pitch = K(SG_P, t)
        fr = F_(qt=bend(pitch), vt=(0.0, ta.sink_for(pitch), 0.0), pr=root_for(FEET, dip=0.45 * max(0.0, -b) / 0.4))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        shake = wave(t, 18.5, 33.0, 7.25, 11.0)
        c.key('head', t, qeuler(x=K(SG_HX, t), y=shake, z=-0.25 * shake), ease='linear')
        for side in ('right_arm', 'left_arm'):
            lag = 0.0 if side == 'right_arm' else 0.6
            bb = K(SG_B, t - lag)
            set_arm(c, side, t, rest_arm(side, fr, out=IDLE_ARM_OUT + 2.0 * max(0.0, bb), fwd=1.5 * bb, lift=1.3 * bb))
    c.mode('head', 'add')
    feet_down(c, (20.0,))
    joins_standing(c)

# ================================================================== think

# ---- think (50): before the news. "Hmm", a little lean back; the left arm comes across the belly and the right hand up
# to the chin, the eyes going up and away (9.5); a finger taps the chin three times (16, 20, 24) while the head drifts,
# the weight on the left leg; then "ah!" (37.5): the finger goes up beside the face as the body straightens and the head
# comes back to the listener with a nod, and both hands come down.
CHIN = np.array([-0.4, 0.6, -7.6])              # head space: under the chin, a hand's width out
TK_TAPS = (16.0, 20.0, 24.0)
TK_FINGER = (0.75, 0.6, 0.08)                   # "ah!": the finger up beside the face
TK_CHIN = [(0, 0.0), (2.5, 0.0), (9.5, 1.0), (50, 1.0)]
TK_AH = [(0, 0.0), (34.0, 0.0), (37.5, 1.0), (50, 1.0)]
TK_DOWN = [(0, 0.0), (39.5, 0.0), (45.0, 1.0), (50, 1.0)]
TK_ACROSS = [(0, 0.0), (3.0, 0.0), (9.0, 1.0), (37.0, 1.0), (42.5, 0.0), (50, 0.0)]
TK_SWING = [(0, 0.0), (43.5, 0.0), (46.0, -4.0), (48.5, 0.8), (50, 0.0)]
TK_P = [(0, 0.0), (2.5, -1.5), (9.5, 1.0), (34.0, 1.5), (37.5, -1.5), (40.0, 2.0), (43.0, -0.5), (46.0, 0.3), (50, 0.0)]
TK_Y = [(0, 0.0), (9.5, -4.0), (34.0, -4.0), (38.0, 0.0), (50, 0.0)]
TK_HX = [(0, 0.0), (2.5, 2.0), (9.5, -11.0), (16.0, -9.0), (22.0, -12.0), (30.0, -10.0), (34.0, -8.0), (37.5, -3.0),
         (40.0, 7.0), (42.5, -1.0), (45.5, 0.5), (50, 0.0)]
TK_HY = [(0, 0.0), (2.5, -4.0), (9.5, 14.0), (20.0, 8.0), (28.0, 16.0), (34.0, 12.0), (37.5, 0.0), (50, 0.0)]
TK_HZ = [(0, 0.0), (3.0, 4.0), (9.5, 6.0), (30.0, 5.0), (36.5, 0.0), (50, 0.0)]
TK_RX = [(0, 0.0), (8.0, 0.0), (14.0, 0.7), (34.0, 0.7), (42.0, 0.0), (50, 0.0)]
BELLY_R = np.array([-2.6, 9.2, -5.2])           # torso space: the left hand across the belly, at the right side


def think():
    c = clip('think', 50, False, look=('citizen', 1), events=[('tap', k) for k in TK_TAPS] + [('ah', 37.5)],
             views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    holds, across = Holds(), Holds()
    for t in every(50):
        fr = F_(qt=bend(K(TK_P, t), K(TK_Y, t), -0.8 * K(TK_RX, t)), pr=root_for(FEET, x=K(TK_RX, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        qh = qeuler(x=K(TK_HX, t), y=K(TK_HY, t), z=K(TK_HZ, t))
        c.key('head', t, qh, ease='linear')
        tap = sum(bump(t, k - 1.8, k) for k in TK_TAPS)        # the finger off the chin and back onto it at each tap
        w_chin, w_ah, w_down = smooth(K(TK_CHIN, t)), smooth(K(TK_AH, t)), smooth(K(TK_DOWN, t))
        rest = rest_arm('right_arm', fr, fwd=K(TK_SWING, t))
        pose = rest
        if w_chin > 1e-4 and w_ah < 1.0 - 1e-4:
            chin = head_pt(fr, qslerp(I, qh, 0.5), CHIN + np.array([0.0, 0.5 * tap, -1.2 * tap]))
            pose = mix_arm(pose, reach_arm(holds, 'right_arm', fr, chin, palm=v(f=-0.2, u=0.4, r=-1.0)), w_chin)
        pose = mix_arm(pose, point_arm(fr, v(*TK_FINGER), L, lift=0.6), w_ah)
        set_arm(c, 'right_arm', t, mix_arm(pose, rest, w_down))
        w_across = smooth(K(TK_ACROSS, t))
        pose = rest_arm('left_arm', fr, fwd=K(TK_SWING, t - 1.0))
        if w_across > 1e-4:
            pose = mix_arm(pose, reach_arm(across, 'left_arm', fr, torso_to_world(fr, BELLY_R), palm=B, palm_w=0.2), w_across)
        set_arm(c, 'left_arm', t, pose)
    c.mode('head', 'add')
    for k in TK_TAPS:
        at_hand(c, k, 'right_arm', head_pt(ta.frame_at(c, k), qslerp(I, c.sample('head', k)[0], 0.5), CHIN),
                'finger tapping the chin')
    at_hand(c, 12, 'left_arm', torso_to_world(ta.frame_at(c, 12), BELLY_R), 'left arm across the belly')
    clear_of(c, 'right_arm', every(50))
    clear_of(c, 'left_arm', every(50))
    feet_down(c, (20.0,))
    joins_standing(c)

# ================================================================== tipping the hat

# ---- tip_hat (24): a greeting in passing or a farewell: a quick lift of the chin, the right hand comes up and two
# fingers touch the hat's brim at its front corner (or the brow, bareheaded) at tick 7 with a small nod of the head and
# shoulders over them; the hand leaves the brim with a little flick forward and out, and drops back to the side with a
# swing past it.
TIP_BROW = np.array([-6.3, -6.7, -7.4])     # head space: two fingers at the brim's front right corner (by the brow)
TH_UP = [(0, 0.0), (1.5, 0.0), (7.0, 1.0), (11.5, 1.0), (13.0, 0.0), (24, 0.0)]          # the hand at the brow
TH_FLICK = [(0, 0.0), (11.0, 0.0), (13.5, 1.0), (15.5, 0.8), (19.0, 0.0), (24, 0.0)]       # off the brim, forward and out
TH_DROP = [(0, 0.0), (13.0, 0.0), (19.5, 1.0), (24, 1.0)]                                  # back down to the side
TH_SWING = [(0, 0.0), (18.0, 0.0), (20.0, -5.0), (22.0, 1.5), (23.5, 0.0), (24, 0.0)]     # the arm's swing past rest (deg fwd)
TH_P = [(0, 0.0), (2.0, -1.2), (6.0, 0.5), (9.5, 4.0), (12.5, 1.0), (16.0, -0.6), (19.0, 0.3), (24, 0.0)]
TH_HX = [(0, 0.0), (2.0, -4.0), (5.5, -1.0), (9.0, 13.0), (11.5, 9.0), (14.5, -2.0), (17.5, 0.8), (21.0, 0.0), (24, 0.0)]
TH_HZ = [(0, 0.0), (3.0, 0.0), (7.0, -2.0), (11.0, -1.5), (15.0, 0.0), (24, 0.0)]
TH_SHOULDER = [(0, 0.0), (2.0, 0.0), (6.5, 1.0), (11.5, 1.0), (15.0, 0.3), (19.0, 0.0), (24, 0.0)]


def brow_touch(holds, fr, qh, up, flick, drop, swing):
    """The right arm touching the brim: [up] of the way from rest to two fingers on it, flicked [flick] of the way off it
    forward and out, and [drop]ped back down."""
    rest = rest_arm('right_arm', fr, fwd=swing)
    if up < 1e-4 and flick < 1e-4:
        return rest
    brow = head_pt(fr, qh, TIP_BROW)
    off = head_pt(fr, qh, TIP_BROW + np.array([-2.5, 1.5, -3.0]))
    target = brow + (off - brow) * flick
    q, sh = reach_arm(holds, 'right_arm', fr, target, palm=v(f=-0.3, u=0.2, r=-1.0), slack=0.5)
    return mix_arm(rest, (q, sh), max(up, flick * (1.0 - smooth(drop))))


def tip_hat():
    c = clip('tip_hat', 24, False, look=('farmer', 0), events=[('touch', 7)], views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    holds = Holds()
    for t in every(24):
        fr = F_(qt=bend(K(TH_P, t), 0.0, -0.6 * K(TH_SHOULDER, t)))
        key_frame(c, t, fr, ease='linear')
        qh = qeuler(x=K(TH_HX, t), z=K(TH_HZ, t))
        c.key('head', t, qh, ease='linear')
        set_arm(c, 'right_arm', t, brow_touch(holds, fr, qh, smooth(K(TH_UP, t)), smooth(K(TH_FLICK, t)), K(TH_DROP, t),
                                              K(TH_SWING, t)))
        c.key('left_arm', t, hang('left_arm', fr, fwd=1.5 * bump(t, 8.0, 16.0)), ease='linear')
    c.mode('head', 'add')
    at_hand(c, 7, 'right_arm', head_pt(ta.frame_at(c, 7), c.sample('head', 7)[0], TIP_BROW), 'two fingers at the brim')
    clear_of(c, 'right_arm', every(24))
    joins_standing(c)

# ================================================================== introducing themselves

# ---- introduce (40): "Mara. Pleased to meet you." A breath and the chest up, the right hand comes to the chest (8.5),
# held there with the name; a nod of the head and shoulders over it (18.5); coming up from it the hand opens out toward
# the listener, palm up ("pleased to meet you", 27), and goes back to the side.
HEART = np.array([2.6, 4.6, -5.6])              # torso space: the right hand across the chest, over the heart
IN_HAND = [(0, 0.0), (2.5, 0.0), (8.5, 1.0), (21.0, 1.0), (26.5, 0.0), (40, 0.0)]
IN_OFFER = [(0, 0.0), (20.5, 0.0), (26.5, 1.0), (29.0, 1.0), (34.0, 0.0), (40, 0.0)]
IN_SWING = [(0, 0.0), (32.0, 0.0), (35.0, -4.0), (38.0, 0.8), (40, 0.0)]
IN_P = [(0, 0.0), (2.5, -1.8), (8.5, -1.0), (14.0, -1.4), (18.5, 9.0), (21.0, 7.5), (25.0, 0.5), (28.0, 1.5), (33.0, -0.5), (37.0, 0.2), (40, 0.0)]
IN_HX = [(0, 0.0), (2.5, -3.0), (8.5, -2.0), (14.0, -3.0), (19.0, 13.0), (21.5, 10.0), (25.0, -1.0), (28.0, 2.0), (33.0, -0.5), (40, 0.0)]
IN_HZ = [(0, 0.0), (8.5, 3.0), (14.0, 3.0), (19.0, 0.0), (27.0, -3.0), (33.0, 0.0), (40, 0.0)]


def introduce():
    c = clip('introduce', 40, False, look=('citizen', 7), events=[('chest', 8.5), ('nod', 18.5), ('offer', 27)],
             views=((32, -12), (90, -2), (0, -4)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    holds = Holds()
    for t in every(40):
        pitch = K(IN_P, t)
        fr = F_(qt=bend(pitch, -2.0 * smooth(K(IN_HAND, t)), 0.0), vt=(0.0, ta.sink_for(pitch), 0.0),
                pr=root_for(FEET, z=0.5 * bump(t, 13.0, 25.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        c.key('head', t, qeuler(x=K(IN_HX, t), z=K(IN_HZ, t)), ease='linear')
        rest = rest_arm('right_arm', fr, fwd=K(IN_SWING, t))
        w_hand, w_offer = smooth(K(IN_HAND, t)), smooth(K(IN_OFFER, t))
        pose = rest
        if w_hand > 1e-4:
            pose = mix_arm(pose, reach_arm(holds, 'right_arm', fr, torso_to_world(fr, HEART), palm=B, palm_w=0.25, limit=2.0), w_hand)
        pose = mix_arm(pose, point_arm(fr, v(f=1.0, u=-0.4, r=0.25), v(f=0.3, u=1.0, r=-0.2), lift=0.3), w_offer)
        set_arm(c, 'right_arm', t, pose)
        c.key('left_arm', t, hang('left_arm', fr, fwd=1.5 * bump(t, 15.0, 24.0)), ease='linear')
    c.mode('head', 'add')
    at_hand(c, 12, 'right_arm', torso_to_world(ta.frame_at(c, 12), HEART), 'hand on the chest', tol=0.8)
    clear_of(c, 'right_arm', every(40))
    feet_down(c, (18.5,))
    joins_standing(c)

# ================================================================== wary, the arms folded

# ---- arms_folded (80, loop): wary of the player, the whole talk long. The arms folded high across the chest, the chin
# up, the weight back on the left leg with the right foot forward; breathing (twice round), the right foot tapping three
# times (23, 27, 31), a slow look up and down at the one before them (36 to 48), a "hmph" of the shoulders (52), still
# again. The seam holds the fold.
FOLD_R = np.array([3.4, 5.0, -5.8])             # torso space: the right hand tucked at the left arm
FOLD_L = np.array([-3.2, 6.4, -5.6])            # the left hand under the right arm
AF_TAPS = (23.0, 27.0, 31.0)
AF_FOOT = {'right_leg': (-2.3, -1.6), 'left_leg': (1.9, 0.4)}


def af_breath(t):
    return math.sin(2 * math.pi * t / 40.0)


def arms_folded():
    c = clip('arms_folded', 80, True, look=('fisher', 1), events=[('tap', k) for k in AF_TAPS] + [('hmph', 52)],
             views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    holds = Holds()
    for t in every(80):
        br = af_breath(t)
        ph = 2 * math.pi * t / 80.0
        hmph = bump(t, 49.0, 56.0)
        pitch = -3.0 - 0.6 * br - 1.0 * hmph
        fr = F_(qt=bend(pitch, -3.0 + 1.2 * math.sin(ph), 1.0 + 0.6 * math.sin(ph + 1.0)),
                pr=root_for(AF_FOOT, x=0.7 + 0.15 * math.sin(ph), z=0.6))
        key_frame(c, t, fr, ease='linear')
        tap = sum(bump(t, k - 2.0, k) for k in AF_TAPS)
        stand(c, t, fr, AF_FOOT, lift=(0.9 * tap, 0.0))
        look = bump(t, 36.0, 48.0)
        c.key('head', t, qeuler(x=-7.0 + 0.6 * br + 13.0 * bump(t, 36.0, 44.0) - 3.0 * bump(t, 42.0, 48.0) - 2.0 * hmph,
                                y=4.0 * math.sin(ph + 0.5) - 3.0 * look, z=-2.0 * math.sin(ph) - 3.0 * bump(t, 56.0, 70.0)),
              ease='linear')
        shrug = 0.3 * br + 0.7 * hmph
        for side, target in (('right_arm', FOLD_R), ('left_arm', FOLD_L)):
            pose = reach_arm(holds, side, fr, torso_to_world(fr, target), palm=B, palm_w=0.25, limit=2.0)
            set_arm(c, side, t, lifted(pose, shrug))
    c.mode('head', 'add')
    seal(c)
    at_hand(c, 0, 'right_arm', torso_to_world(ta.frame_at(c, 0), FOLD_R), 'right hand tucked at the left arm', tol=0.8)
    at_hand(c, 0, 'left_arm', torso_to_world(ta.frame_at(c, 0), FOLD_L), 'left hand under the right arm', tol=0.8)
    clear_of(c, 'right_arm', every(80, 2.0))
    clear_of(c, 'left_arm', every(80, 2.0))
    feet_down(c, (0.0, 40.0))

# ================================================================== recoil

# ---- recoil (20): a hostile player comes up. A start (the shoulders jump), and the body jerks back over the right foot
# as it steps half a step back (4.5), both hands coming up before the chest to fend them off, palms out; held a moment
# with a tremor, the foot comes back under them and the hands go down.
RC_U = [(0, 0.0), (0.8, 0.12), (4.0, 1.0), (9.0, 0.85), (16.0, 0.0), (20, 0.0)]
RC_FOOT = [(0, 0.0), (1.0, 0.0), (4.5, 4.5), (10.0, 4.5), (15.0, 0.0), (20, 0.0)]
RC_HANDS = [(0, 0.0), (1.0, 0.0), (3.5, 1.0), (9.5, 0.9), (15.5, 0.0), (20, 0.0)]
RC_JUMP = [(0, 0.0), (1.2, 1.0), (4.0, 0.5), (9.0, 0.3), (13.0, 0.0), (20, 0.0)]
RC_SWING = [(0, 0.0), (14.5, 0.0), (17.0, -4.0), (19.0, 0.5), (20, 0.0)]
RC_HX = [(0, 0.0), (1.0, -5.0), (3.5, 5.0), (9.0, 3.0), (14.0, -1.0), (17.0, 0.5), (20, 0.0)]


def recoil():
    c = clip('recoil', 20, False, look=('citizen', 0), events=[('start', 2)], views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    for t in every(20):
        u = K(RC_U, t)
        tremor = wave(t, 4.0, 11.0, 2.2, 1.0)
        feet = {'right_leg': (-1.9, K(RC_FOOT, t)), 'left_leg': (1.9, 0.0)}
        pr = root_for(feet, z=2.4 * u, dip=0.3 * u)
        fr = F_(qt=bend(-11.0 * u + 0.6 * tremor, 1.5 * tremor, 0.0), pr=pr)
        key_frame(c, t, fr, ease='linear')
        lift = 1.3 * (bump(t, 1.0, 4.5) + bump(t, 10.0, 15.0))
        stand(c, t, fr, feet, lift=(lift, 0.0))
        c.key('head', t, qeuler(x=K(RC_HX, t), y=2.0 * tremor), ease='linear')
        w = smooth(K(RC_HANDS, t))
        jump = 0.8 * K(RC_JUMP, t)
        for side in ('right_arm', 'left_arm'):
            s = 1.0 if side == 'right_arm' else -1.0
            up = point_arm(fr, v(f=0.8, u=0.25 + 0.03 * tremor, r=-s * 0.1), v(f=-0.2, u=1.0, r=0.0), lift=0.4)
            pose = mix_arm(rest_arm(side, fr, fwd=K(RC_SWING, t - (0.0 if s > 0 else 0.7))), up, w)
            set_arm(c, side, t, lifted(pose, jump))
    c.mode('head', 'add')
    for side in ('right_leg', 'left_leg'):
        check(c, f'{side} foot on the ground (t=7)', (leg_end(c, 7, side)[1],), (24.0,), tol=0.3)
    check(c, 'right foot half a step back (t=7)', (leg_end(c, 7, 'right_leg')[2],), (4.5,), tol=0.3)
    hands_apart(c, every(20))
    clear_of(c, 'right_arm', every(20))
    clear_of(c, 'left_arm', every(20))
    joins_standing(c)

# ================================================================== in passing: walking overlays

# ---- wave_walk (30, overlay): a hand raised in passing. Only the right arm and the head: the left arm and the legs
# keep the walk. The hand comes up beside the head, palm out (7), waves twice, and comes back down to swing on.
WW_UP = [(0, 0.0), (1.5, 0.0), (7.0, 1.0), (20.0, 1.0), (26.5, 0.0), (30, 0.0)]
WW_HX = [(0, 0.0), (4.0, -3.0), (9.0, 7.0), (12.0, 2.0), (15.0, 5.0), (18.0, 1.0), (24.0, 0.0), (30, 0.0)]
WW_HZ = [(0, 0.0), (6.0, -3.0), (18.0, -3.0), (24.0, 0.0), (30, 0.0)]
WW_SWING = [(0, 0.0), (25.0, 0.0), (27.5, -3.0), (29.5, 0.2), (30, 0.0)]


def wave_walk():
    c = clip('wave_walk', 30, False, look=('farmer', 1), overlay=True, events=[('wave', 10)],
             views=((32, -12), (90, -2), (0, -4)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    for t in every(30):
        w = smooth(K(WW_UP, t))
        sway = wave(t, 7.5, 21.0, 6.0, 0.28)
        up = point_arm(REST, v(f=0.28, u=1.0, r=0.66 + sway), v(f=1.0, u=0.1, r=-0.25 - 0.6 * sway), lift=0.7)
        set_arm(c, 'right_arm', t, mix_arm(rest_arm('right_arm', fwd=K(WW_SWING, t)), up, w))
        c.key('head', t, qeuler(x=K(WW_HX, t), z=K(WW_HZ, t) - 1.5 * sway), ease='linear')
    c.mode('head', 'add')
    clear_of(c, 'right_arm', every(30))
    joins_standing(c)


# ---- nod_walk (20, overlay): a nod and a touch of the brow in passing. Only the right arm and the head: the hand to
# the brim (5.5) and a nod over it (7.5), off it with a flick, and back down to swing on.
NW_UP = [(0, 0.0), (1.0, 0.0), (5.5, 1.0), (9.5, 1.0), (11.0, 0.0), (20, 0.0)]
NW_FLICK = [(0, 0.0), (9.0, 0.0), (11.5, 1.0), (13.0, 0.7), (16.0, 0.0), (20, 0.0)]
NW_DROP = [(0, 0.0), (11.0, 0.0), (16.5, 1.0), (20, 1.0)]
NW_SWING = [(0, 0.0), (15.5, 0.0), (17.5, -4.0), (19.5, 0.3), (20, 0.0)]
NW_HX = [(0, 0.0), (1.5, -2.0), (5.0, 2.0), (7.5, 12.0), (10.0, 5.0), (12.5, -1.0), (15.0, 0.5), (20, 0.0)]


def nod_walk():
    c = clip('nod_walk', 20, False, look=('merchant', 1), overlay=True, events=[('touch', 5.5), ('nod', 7.5)],
             views=((32, -12), (90, -2), (0, -4)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    holds = Holds()
    for t in every(20):
        qh = qeuler(x=K(NW_HX, t), z=-1.5 * bump(t, 3.0, 12.0))
        c.key('head', t, qh, ease='linear')
        set_arm(c, 'right_arm', t, brow_touch(holds, REST, qh, smooth(K(NW_UP, t)), smooth(K(NW_FLICK, t)), K(NW_DROP, t),
                                              K(NW_SWING, t)))
    c.mode('head', 'add')
    at_hand(c, 5.5, 'right_arm', head_pt(REST, c.sample('head', 5.5)[0], TIP_BROW), 'two fingers at the brim')
    clear_of(c, 'right_arm', every(20))
    joins_standing(c)

# ================================================================== on a seat

# ---- sit_gossip (60, loop, seated): a rumour told on a bench. From sitting easy (sit's first frame), a glance aside
# and a lean in to the one beside or across, the right hand coming up off the knee beside the mouth, the left staying
# on its knee; a glance over the left shoulder (19), closer, a glance to the right (41), a knowing nod (47), and back
# to sitting easy (the seam is at rest).
SGS_LEAN = [(0, 0.0), (2.0, 0.0), (9.0, 1.0), (17.0, 1.0), (21.0, 0.75), (25.0, 1.1), (38.0, 1.0), (42.0, 0.85),
            (47.5, 1.1), (51.0, 1.0), (57.0, 0.0), (60, 0.0)]
SGS_HAND = [(0, 0.0), (3.0, 0.0), (9.5, 1.0), (50.5, 1.0), (57.0, 0.0), (60, 0.0)]
SGS_HY = [(0, 0.0), (2.0, -9.0), (5.0, -5.0), (8.0, 0.0), (16.5, 0.0), (19.0, -28.0), (22.0, -26.0), (25.0, 0.0),
          (38.5, 0.0), (41.0, 24.0), (44.0, 21.0), (46.5, 0.0), (60, 0.0)]
SGS_HX = [(0, 0.0), (5.0, 2.0), (9.0, 6.0), (12.0, 3.0), (14.5, 6.0), (17.5, 1.5), (22.0, 0.0), (26.0, 7.0), (30.0, 4.0),
          (33.0, 7.0), (37.0, 4.5), (41.0, 1.0), (44.5, 3.0), (47.5, 11.0), (50.5, 5.0), (55.0, -1.0), (58.0, 0.4), (60, 0.0)]
SGS_HZ = [(0, 0.0), (9.0, -5.0), (16.0, -5.0), (19.0, 1.0), (25.0, -6.0), (37.0, -6.0), (41.0, 2.0), (47.5, -6.0), (54.0, 0.0), (60, 0.0)]


def sit_gossip():
    c = clip('sit_gossip', 60, True, look=('citizen', 7), station_='seat', events=[('glance', 19), ('glance', 41), ('nod', 47.5)],
             views=((32, -12), (90, -2), (0, -4)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    holds = Holds()
    for t in every(60):
        lean = K(SGS_LEAN, t)
        pitch = 3.0 + 12.0 * lean
        fr = F_(qt=bend(pitch, -3.0 * lean, -3.0 * lean), vt=(0.0, ta.sink_for(pitch) - ta.sink_for(3.0), 0.0), pr=(0.0, SEAT_DROP, 0.0))
        key_frame(c, t, fr, ease='linear')
        seat_legs(c, t, ease='linear')
        qh = qeuler(x=K(SGS_HX, t), y=K(SGS_HY, t), z=K(SGS_HZ, t))
        c.key('head', t, qh, ease='linear')
        knee = knee_hand(fr, 'right_arm'), np.zeros(3)
        w = smooth(K(SGS_HAND, t))
        pose = knee
        if w > 1e-4:
            mouth = head_pt(fr, qslerp(I, qh, 0.55), MOUTH_SIDE_R)
            pose = mix_arm(knee, lifted(reach_arm(holds, 'right_arm', fr, mouth, palm=v(f=0.3, u=0.1, r=-1.0)), 0.2), w)
        set_arm(c, 'right_arm', t, pose)
        c.key('left_arm', t, knee_hand(fr, 'left_arm'), ease='linear')
    c.mode('head', 'add')
    seal(c)
    for k in (13.0, 31.0):
        at_hand(c, k, 'right_arm', head_pt(ta.frame_at(c, k), qslerp(I, c.sample('head', k)[0], 0.55), MOUTH_SIDE_R),
                'right hand by the mouth')
    clear_of(c, 'right_arm', every(60))
    clear_of(c, 'right_arm', every(60), into=('right_leg',), tol=1.1)      # sit's own hand on the thigh: 0.73
    join('sit_gossip starts on sit', c, 0, getc('sit'), 0, tol=0.3)


# ---- sit_laugh (40, seated): good news with a friend, on a bench. A catch of breath leaning in, then the laugh: the head
# thrown back and the body with it, the right hand up off the knee, and down onto the thigh by the knee with a slap as
# the body comes forward (12.5); laughing on, the shoulders shaking, the left hand on the belly, a second smaller slap
# (21.5), and settling back to sitting easy with a contented nod.
KNEE_SLAP = np.array([-3.4, 19.0, -9.0])        # on the right thigh, by the knee (the hand's end on its top)
SL_SLAPS = (12.5, 21.5)
SL_P = [(0, 3.0), (3.0, 6.0), (7.5, -6.0), (9.5, -7.0), (12.5, 8.0), (14.5, 4.0), (17.0, -3.0), (20.0, -1.0), (21.5, 6.0),
        (24.0, 1.0), (28.0, -1.0), (33.0, 2.0), (36.0, 3.6), (40, 3.0)]
SL_HX = [(0, 0.0), (3.0, 3.0), (7.5, -22.0), (10.0, -20.0), (12.5, 4.0), (15.0, -14.0), (19.5, -12.0), (21.5, 2.0),
         (24.0, -10.0), (29.0, -6.0), (33.5, 2.0), (36.5, 4.0), (38.5, -0.5), (40, 0.0)]
SL_HAND = [(0, 0.0), (3.5, 0.0), (8.5, 1.0), (12.5, 0.0), (15.5, 0.55), (18.5, 0.5), (21.5, 0.0), (24.5, 0.3), (28.5, 0.0), (40, 0.0)]
SL_BELLY = [(0, 0.0), (5.0, 0.0), (10.0, 1.0), (27.0, 1.0), (33.0, 0.0), (40, 0.0)]
SL_SHAKE = [(0, 0.0), (13.5, 0.0), (15.0, 1.0), (25.0, 1.0), (31.0, 0.0), (40, 0.0)]
BELLY_SEAT = np.array([0.6, 8.6, -6.0])         # torso space: the left hand on the belly


def sit_laugh():
    c = clip('sit_laugh', 40, False, look=('citizen', 1), station_='seat', events=[('slap', k) for k in SL_SLAPS],
             views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    up_hold, belly_hold = Holds(), Holds()
    for t in every(40):
        shake = K(SL_SHAKE, t) * math.sin(2 * math.pi * (t - 13.5) / 3.2)
        pitch = K(SL_P, t) + 2.2 * shake
        fr = F_(qt=bend(pitch, 0.0, 1.0 * shake), vt=(0.0, ta.sink_for(pitch) - ta.sink_for(3.0), 0.0), pr=(0.0, SEAT_DROP, 0.0))
        key_frame(c, t, fr, ease='linear')
        seat_legs(c, t, ease='linear')
        c.key('head', t, qeuler(x=K(SL_HX, t) + 3.0 * shake, z=1.5 * shake), ease='linear')
        knee = knee_hand(fr, 'right_arm'), np.zeros(3)
        h = K(SL_HAND, t)
        slap_on = reach_arm(up_hold, 'right_arm', fr, KNEE_SLAP + np.array([0.0, -7.0 * max(0.0, h), 0.0]), palm=D, palm_w=0.3)
        set_arm(c, 'right_arm', t, mix_arm(knee, slap_on, smooth(min(1.0, (t - 3.5) / 2.0)) * (1.0 - smooth((t - 28.5) / 6.0))
                                           if 3.5 < t < 34.5 else 0.0))
        lk = knee_hand(fr, 'left_arm'), np.zeros(3)
        wb = smooth(K(SL_BELLY, t))
        pose = lk
        if wb > 1e-4:
            pose = mix_arm(lk, reach_arm(belly_hold, 'left_arm', fr, torso_to_world(fr, BELLY_SEAT), palm=B, palm_w=0.3), wb)
        set_arm(c, 'left_arm', t, pose)
    c.mode('head', 'add')
    for k in SL_SLAPS:
        at_hand(c, k, 'right_arm', KNEE_SLAP, 'the slap on the thigh by the knee', tol=0.8)
    clear_of(c, 'right_arm', every(40))
    clear_of(c, 'left_arm', every(40))
    # a hand resting on the thigh sinks its edge 0.73 px into it in sit itself: the slap goes no deeper
    clear_of(c, 'right_arm', every(40), into=('right_leg',), tol=1.1)
    join('sit_laugh starts on sit', c, 0, getc('sit'), 0, tol=0.3)
    join('sit_laugh ends on sit', c, 40, getc('sit'), 0, tol=0.3)

# ================================================================== from work to talk

# ---- pause_work (30): stopping work to talk. From bent over the work, the hands on it at counter height, they're
# pressed down once (setting it down, 2.5) and lifted off; straightening part way the hands come back to the apron and
# are wiped on it, down and up by turns (12 and 16), the head coming up to the listener; then off the apron out to the
# sides and a straightening up, the chest out and the shoulders back (23), the hands dropping to the sides.
PW_SURFACE = np.array([2.5, 7.8, -10.6])        # the left hand on the work (x mirrored for the right)
PW_APRON = np.array([2.3, 11.6, -5.7])          # torso space: the left hand on the apron, over the thigh (x mirrored)
PW_PRESS = [(0, 0.0), (1.0, 0.0), (2.5, 0.6), (3.5, 0.4), (5.5, -1.2), (9.0, -1.0), (30, 0.0)]
PW_TO_APRON = [(0, 0.0), (4.0, 0.0), (10.0, 1.0), (30, 1.0)]       # from the work back to the apron
PW_OFF = [(0, 0.0), (19.5, 0.0), (22.0, 1.0), (30, 1.0)]           # off the apron, out to the sides
PW_DOWN = [(0, 0.0), (21.0, 0.0), (25.0, 1.0), (30, 1.0)]          # and down
PW_BACK = [(0, 0.0), (19.0, 0.0), (23.0, 1.0), (25.0, 0.9), (29.0, 0.0), (30, 0.0)]
PW_SWING = [(0, 0.0), (23.5, 0.0), (26.0, -4.0), (28.5, 0.8), (30, 0.0)]
PW_P = [(0, 14.0), (2.5, 15.0), (5.0, 12.0), (10.0, 5.0), (14.0, 4.5), (18.0, 4.0), (21.0, -2.5), (23.5, -4.0), (26.0, -1.0),
        (28.0, 0.3), (30, 0.0)]
PW_HX = [(0, 16.0), (2.5, 18.0), (6.0, 10.0), (10.0, 2.0), (13.0, 5.0), (17.0, 3.0), (21.0, -5.0), (24.0, -3.0), (27.0, 0.5),
         (30, 0.0)]
PW_Z = [(0, 0.9), (4.0, 0.9), (10.0, 0.3), (19.0, 0.2), (23.0, -0.3), (27.0, 0.0), (30, 0.0)]


def pw_apron(s, t):
    """Where a hand is on the apron at [t] (torso space; [s] 1 the left, -1 the right): rubbed down and up it by turns,
    pressing in on the way down."""
    ph = 2 * math.pi * (t - 8.0) / 8.0 + (0.0 if s < 0 else math.pi)
    k = bump(t, 8.0, 22.0)
    return PW_APRON * np.array([s, 1.0, 1.0]) + np.array([0.0, 1.6 * k * math.sin(ph), -0.3 * k * math.cos(ph)])


def pause_work():
    c = clip('pause_work', 30, False, look=('baker', 0), events=[('set_down', 2.5), ('wipe', 12), ('wipe', 16)],
             views=((32, -12), (90, -2), (0, -4)),
             traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    on_work = {side: Holds() for side in ('right_arm', 'left_arm')}
    on_apron = {side: Holds() for side in ('right_arm', 'left_arm')}
    for t in every(30):
        pitch = K(PW_P, t)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, ta.sink_for(pitch), 0.0), pr=root_for(FEET, z=K(PW_Z, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        c.key('head', t, qeuler(x=K(PW_HX, t)), ease='linear')
        to_apron, off, down, back = (smooth(K(keys, t)) for keys in (PW_TO_APRON, PW_OFF, PW_DOWN, PW_BACK))
        for side in ('right_arm', 'left_arm'):
            s = 1.0 if side == 'left_arm' else -1.0
            pose = None
            if to_apron < 1.0 - 1e-4:
                work = PW_SURFACE * np.array([s, 1.0, 1.0]) + np.array([0.0, K(PW_PRESS, t), 0.0])
                pose = reach_arm(on_work[side], side, fr, work, palm=D, palm_w=0.25)
            if to_apron > 1e-4:
                on = reach_arm(on_apron[side], side, fr, torso_to_world(fr, pw_apron(s, t)), palm=B, palm_w=0.3, limit=2.0)
                pose = on if pose is None else mix_arm(pose, on, to_apron)
            pose = mix_arm(pose, rest_arm(side, fr, out=IDLE_ARM_OUT + 9.0, fwd=18.0), off)
            pose = mix_arm(pose, rest_arm(side, fr, fwd=K(PW_SWING, t - (0.0 if side == 'right_arm' else 0.7))), down)
            # straightening up at the end: the shoulders drawn back and down
            set_arm(c, side, t, (pose[0], np.asarray(pose[1], float) + np.array([0.0, 0.5 * back, 0.6 * back])))
    c.mode('head', 'add')
    for side in ('right_arm', 'left_arm'):
        s = 1.0 if side == 'left_arm' else -1.0
        at_hand(c, 2.5, side, PW_SURFACE * np.array([s, 1.0, 1.0]) + np.array([0.0, 0.6, 0.0]), f'{side.replace("_", " ")} on the work')
        for k in (12.0, 14.0, 16.0):
            at_hand(c, k, side, torso_to_world(ta.frame_at(c, k), pw_apron(s, k)), f'{side.replace("_", " ")} wiped on the apron', tol=0.8)
        clear_of(c, side, every(30), into=('body', 'head', 'right_leg', 'left_leg'))
    feet_down(c, (0.0, 23.0))
    join('pause_work ends on idle', c, 30, getc('idle'), 0, tol=1.0)

# ================================================================== the previews


def gif(c, out_dir, views=((32, -10), (90, -2), (0, -6)), cell=(220, 280), zoom=5.2, rest=10):
    """An animated GIF of [c] at the game's pace (a frame a tick), drawn as the contact sheets are: from the three views
    side by side, a one-shot held on its last frame for [rest] ticks before it goes again (as `act` plays it)."""
    info = ta.INFO[c.name]
    look = ta.look_for(info['look'])
    walking = info['walking']
    W, H = cell
    n = c.length if info['loop'] else c.length + 1 + rest
    frames = []
    for i in range(n):
        t = float(min(i, c.length))
        img = Image.new('RGB', (W * len(views), H + 22), (30, 32, 40))
        for j, (yaw, pitch) in enumerate(views):
            project = ta.ortho(yaw, pitch, zoom, (W, H), 7.0)
            cell_img, _ = ta.render_cell(look, c, t, project, (W, H), walking)
            img.paste(cell_img, (j * W, 22))
        d = ImageDraw.Draw(img)
        beat = [name for name, k in info['events'] if abs(k - t) < 0.75]
        d.text((6, 5), f'{c.name}  {c.length}t {"loop" if info["loop"] else "once"}'
                       f'{"  overlay (walking)" if walking else ""}   t={t:g}' + (f'  {beat[0]}!' if beat else ''),
               fill=(255, 255, 160) if beat else (235, 235, 235))
        frames.append(img.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE))
    path = os.path.join(out_dir, f'{c.name}.gif')
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=50, loop=0, optimize=True, disposal=1)
    return path


if __name__ == '__main__':
    # The animated previews: living_anims.py (imported here) builds every clip, these among them, and they're drawn
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    import living_anims as la_
    names = sys.argv[2:] or WANTED
    for name in names:
        print('wrote', gif(la_.getc(name), out))
