"""
The tales' six clips (living-world/stories/design.md, 13.3 and the clip lines of 14.11), on the Living World's kit:

    GRIEVE       80 loop    the face down in both hands, a slow uneven round of sobs
    WRING_HANDS  60 loop    waiting, afraid: the hands wrung before the chest in bursts, a hand to the throat
    SHAKE_HANDS  30 once    the right hands clasped across a block (both play it, face to face), the left over the arm
    FEED_FIRE    40 once    a note held to an oven's mouth, burned, and the heat flinched from (the paper in the hand)
    HOBBLE       40 loop    an overlay over the walk: stooped, the fist in the small of the back, the head low
    LAY_POSY     40 once    kneeling to a memorial stone: the posy set down, a hand to the stone, the head bowed, up again

Their names, lengths and loop flags are TownRole.kt's (Activity); living_anims.py imports this module at its end and calls
build(), which makes the clips, so its verify() and writer take them like the rest. `python3 -B tale_clips.py <out dir>`
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
from townsfolk_anims import (qeuler, qslerp, unit, F, U, D, R, L, T, Rq, F_, bend, key_frame, check, seal, smooth, bump,
                             GRIP, PIVOTS, torso_to_world, head_point, sink_for)
from works_clips import (rest_arm, point_arm, mix, lifted, set_arm, reach_arm, stand, joins_idle, maybe_join, stepping,
                         FEET, PARTNER_PLACE, grip_of)

la = None           # living_anims, handed to build()

FACING = Rq(qeuler(y=180.0))                    # a partner facing back

# ================================================================== the pieces these clips share


def K(keys, t, loop=None):
    return la.K(keys, t, loop)


def KV(keys, t, loop=None):
    return la.KV(keys, t, loop)


# the parts' blocks in their own spaces; for an arm only its hand, the block's last 4 px
BOXES = {'body': ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)), 'head': ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0)),
         'right_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)), 'left_leg': ((-2.0, 0.0, -2.0), (2.0, 12.0, 2.0)),
         'right_arm': ((-3.0, 6.0, -2.0), (1.0, 10.0, 2.0)), 'left_arm': ((-1.0, 6.0, -2.0), (3.0, 10.0, 2.0))}


def deepest(c, t, side, into=('body', 'head'), walking=False):
    """How deep (px) the hand of [side]'s arm goes into the parts [into] at [t] of [c] (0: clear). Only the hand: a block
    arm swung in front of the chest or up to the face always sinks its upper half into the torso's corner or the jaw's,
    as vanilla's own arms do, but a hand inside the chest or the face would show."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    pose = ta.pose_at(c, t, ta.walk_base(t, speed=ta.walk_speed(c, walking), age=t))
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


def deepest_in(c, t, part, boxes, walking=False):
    """How deep (px) [part] of [c] goes at [t] into any of the station's [boxes] ((lo, hi, ...) in the clip's space): for an
    arm its hand only, as deepest() takes it; 0 when it is clear."""
    look = ta.look_for(ta.INFO[c.name]['look'])
    pose = ta.pose_at(c, t, ta.walk_base(t, speed=ta.walk_speed(c, walking), age=t))
    mats = ta.part_matrices(look, pose)
    lo, hi = BOXES[part]
    pts = [np.array([lo[0] + (hi[0] - lo[0]) * i / 4, lo[1] + (hi[1] - lo[1]) * j / 4, lo[2] + (hi[2] - lo[2]) * k / 4])
           for i in range(5) for j in range(5) for k in range(5)]
    worst = 0.0
    for p in pts:
        w = mats[part][:3, :3] @ p + mats[part][:3, 3]
        for b in boxes:
            blo, bhi = np.asarray(b[0], float), np.asarray(b[1], float)
            inside = min(float(np.min(w - blo)), float(np.min(bhi - w)))
            if inside > 0.0:
                worst = max(worst, inside)
    return worst


def clear_of(c, side, times, into=('body', 'head'), tol=0.6, walking=False):
    """Checks [side]'s hand keeps out of [into] (to within [tol] px) all through [times]."""
    worst, at = max((deepest(c, t, side, into, walking), t) for t in times)
    hand = side.replace('_arm', ' hand')
    check(c, f'{hand} clear of the {" and ".join(n.replace("_", " ") for n in into)} (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)


def feet_down(c, times, feet=FEET, floor=24.0, tol=0.4):
    for t in times:
        for side, (x, z) in feet.items():
            check(c, f'{side} foot planted (t={t:g})', la.leg_end(c, t, side), (x, floor, z), tol=tol)


def head_pt(fr, qh, p):
    """A head-space point for a 'set' head key [qh] under [fr] (the head turns with the torso)."""
    return head_point(fr, qh, np.asarray(p, float))


def mirror_to_partner(p, gap):
    """A point of this one's clip space in the clip space of the one who faces back across [gap] px (and the other way)."""
    return np.array([-p[0], p[1], -p[2] - gap])


def qslerp_dir(a, b, w):
    """A unit direction [w] of the way round from [a] to [b]."""
    a, b = unit(a), unit(b)
    ang = math.acos(max(-1.0, min(1.0, float(np.dot(a, b)))))
    if ang < 1e-6:
        return a
    return (math.sin((1 - w) * ang) * a + math.sin(w * ang) * b) / math.sin(ang)


# ================================================================== shake_hands

# ---- shake_hands (30, once): one clip that both play, face to face SH_GAP px apart (a block): the right arm out to the
# other's right hand across the middle, a grasp (8), two pumps (the nods go with them), the left hand coming over onto
# the other's forearm just behind the clasp, and let go, left first, the arms down.
SH_LEN = 30
SH_GAP = 16.0
SH_OUT = [(0, 0.0), (2.0, 0.0), (8.0, 1.0), (23.0, 1.0), (27.5, 0.0), (30, 0.0)]            # the right arm out to the clasp
SH_OVER = [(0, 0.0), (9.0, 0.0), (13.5, 1.0), (19.5, 1.0), (24.5, 0.0), (30, 0.0)]          # the left hand over their forearm
SH_LEAN = [(0, 0.0), (3.0, 0.0), (8.0, 1.0), (22.0, 1.0), (28.0, 0.0), (30, 0.0)]
SH_CLASP = np.array([-0.2, 5.4, -8.1])         # the right fist at the clasp (clip space): the other's is its mirror
SH_PUMP = 1.5                                  # px the clasp rises and falls
SH_ALONG = 0.2                                 # the left hand on their arm: this far from their fist to their shoulder
SH_TOP = 1.4                                   # and this much over it
SH_SWING = 62.0                                # the left arm swings this far forward at the side before it goes in across


def sh_pump(t):
    """The clasp's height off its middle (px, down is positive): two pumps from the grasp."""
    ramp = smooth((t - 8.0) / 1.6) * smooth((20.5 - t) / 1.6)
    return SH_PUMP * ramp * math.sin(2 * math.pi * (t - 8.0) / 5.0)


def sh_frame(t):
    """The torso's frame at [t]: a little lean in to the other, the pumps nodding the shoulders."""
    lean = smooth(K(SH_LEAN, t))
    pump = sh_pump(t)
    return F_(qt=bend(3.0 * lean + 1.2 * pump / SH_PUMP, -9.0 * lean, 0.0), vt=(0.0, sink_for(3.0 * lean), 0.0),
              pr=la.root_for(FEET, z=-0.8 * lean, dip=0.15 * lean))


def sh_clasp(t):
    """This one's right fist at the clasp at [t] (clip space)."""
    return SH_CLASP + np.array([0.0, sh_pump(t), 0.0])


def sh_on(fr, clasp):
    """Where the left hand lies on their forearm (clip space): [SH_ALONG] of the way along from their fist to their shoulder,
    a touch over it. Their fist and shoulder are this one's mirrored across the gap."""
    theirs = mirror_to_partner(clasp, SH_GAP)
    their_shoulder = mirror_to_partner(torso_to_world(fr, PIVOTS['right_arm']), SH_GAP)
    return theirs + (their_shoulder - theirs) * SH_ALONG + np.array([0.0, -SH_TOP, 0.0])


def shake_hands():
    c = la.clip('shake_hands', SH_LEN, False, look=('citizen', 4), events=[],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((62, -10), (90, -2), (0, -4)))
    holds = la.Holds()
    for t in la.every(SH_LEN):
        out = smooth(K(SH_OUT, t))
        over = K(SH_OVER, t)
        lean = smooth(K(SH_LEAN, t))
        fr = sh_frame(t)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        clasp = sh_clasp(t)
        rest_r = rest_arm('right_arm', fr)
        pose_r = reach_arm(holds, 'right_arm', fr, clasp, palm=unit(L + 0.5 * U), palm_w=0.2, slack=0.3, limit=1.4)
        pose_r = mix(rest_r, pose_r, out) if out < 1.0 else pose_r
        set_arm(c, 'right_arm', t, pose_r)
        # the left hand goes on their forearm by way of the side, swung up and forward first and then in across: straight
        # from rest to the arm it would cut through its own belly
        rest_l = rest_arm('left_arm', fr)
        side_l = rest_arm('left_arm', fr, fwd=SH_SWING)
        side_l = point_arm(fr, ta.arm_dir('left_arm', side_l[0], fr, (0.0, 1.0, 0.0)), palm=R)         # (the palm turned in, not up: a quarter turn to go over, not a half)
        on_l = reach_arm(holds, 'left_arm', fr, sh_on(fr, clasp), palm=D, palm_w=0.2, slack=0.4, limit=1.5)
        pose_l = mix(mix(rest_l, side_l, smooth(2.0 * over)), on_l, smooth(2.0 * over - 1.0)) if over < 1.0 else on_l
        set_arm(c, 'left_arm', t, pose_l)
        nod = 7.0 * (bump(t, 9.0, 13.0) + bump(t, 14.0, 18.0)) * 0.5 + 5.0 * bump(t, 6.0, 10.0)
        c.key('head', t, qeuler(x=nod - 2.0 * lean, z=1.5 * lean), ease='linear')
    c.mode('head', 'add')
    ta.INFO['shake_hands']['partner'] = ('shake_hands', ('citizen', 2))
    PARTNER_PLACE['shake_hands'] = (T(0.0, 0.0, -SH_GAP) @ FACING, 0.0)
    ta.INFO['shake_hands']['pan'] = np.array([0.0, 0.0, -SH_GAP / 2])
    for t in (14.0, 19.5):
        mine = ta.fk(c, t, 'right_arm', GRIP['right_arm'])
        check(c, f'the two right hands meet in the middle (t={t:g})', mirror_to_partner(mine, SH_GAP), mine, tol=0.8)
    check(c, 'the left hand lies on their forearm (t=17)', ta.fk(c, 17.0, 'left_arm', GRIP['left_arm']),
          sh_on(sh_frame(17.0), sh_clasp(17.0)), tol=1.0)
    times = [i * 0.5 for i in range(2 * SH_LEN + 1)]
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=0.8)
    joins_idle(c)
    return c


# ================================================================== feed_fire

# ---- feed_fire (40, once): the burning of a note at an oven, a stove or a fire a block ahead (the oven's mouth in its front
# face, 8 px off, low: hip high). The paper is carried in the right hand from the first tick (goods to FF_GONE): a glance
# down at it, bent and crouched to the mouth, the paper held to the flame (it takes at FF_TOUCH: the scene's Fx times to
# that tick), held there while it curls, let go as it takes, and then the heat: the head snapped back and turned away, the
# hand up before the face, a hop back; and a breath, and the hand down and a step in to the mark again by the end.
FF_LEN = 40
FF_TOUCH = 14.0
FF_GONE = 24.0
FF_AT = np.array([0.5, 14.2, -7.0])             # the paper held to the mouth's upper edge (the station's face is at z = -8)
FF_PAPER = np.array([0.0, 1.2, -1.5])           # the paper's middle from the right fist's grip (right arm space)
FF_REACH = [(0, 0.0), (1.5, 0.0), (11.5, 1.0), (23.0, 1.0), (26.0, 0.0), (40, 0.0)]      # the paper to the mouth
FF_SHIELD = [(0, 0.0), (23.5, 0.0), (28.0, 1.0), (34.0, 1.0), (38.5, 0.0), (40, 0.0)]    # the hand up from the heat
FF_PITCH = [(0, 0.0), (2.5, -1.5), (10.0, 20.0), (14.0, 21.0), (22.0, 19.0), (24.0, 15.0), (27.0, -4.0), (31.0, -2.5),
            (36.0, 1.0), (40, 0.0)]
FF_ROOTZ = [(0, 0.0), (2.5, 0.0), (9.0, 3.5), (24.0, 3.5), (27.0, 5.0), (34.0, 5.0), (38.5, 0.0), (40, 0.0)]
FF_DIP = [(0, 0.0), (2.5, 0.0), (9.5, 4.0), (24.0, 3.2), (28.0, 0.8), (34.0, 0.8), (38.0, 0.0), (40, 0.0)]
FF_HP = [(0, 0.0), (2.0, 8.0), (8.0, 10.0), (14.0, 12.0), (21.0, 12.0), (23.5, -8.0), (27.0, -10.0), (31.0, -4.0),
         (33.5, 6.0), (38.0, 1.0), (40, 0.0)]
FF_HY = [(0, 0.0), (2.0, 0.0), (8.0, -3.0), (14.0, -6.0), (21.0, -6.0), (23.5, -30.0), (27.0, -40.0), (31.0, -36.0),
         (33.5, -12.0), (38.0, -2.0), (40, 0.0)]
FF_STEPS = [(24.0, 27.5, 'right_leg', (-1.9, 3.0)), (25.0, 28.5, 'left_leg', (1.9, 3.0)),
            (34.0, 37.5, 'right_leg', (-1.9, 0.0)), (35.0, 38.5, 'left_leg', (1.9, 0.0))]
FF_SNATCH = (23.5, 28.0)                         # the hand snatched back from the flame to before the face
FF_LOWER = (34.0, 38.5)                          # and let down again
FF_SHIELD_T = np.array([-2.5, 2.4, -9.6])        # torso space: the arm thrust out ahead, the palm to the heat (up before the face it would meet the turned head)


def feed_fire():
    c = la.clip('feed_fire', FF_LEN, False, look=('citizen', 1), station_='oven', events=[],
                traces=[('right_arm', GRIP['right_arm'] + FF_PAPER, (255, 200, 120))], views=((50, -10), (90, -2), (0, -4)))
    holds = la.Holds()
    handle = GRIP['right_arm'] + FF_PAPER
    for t in la.every(FF_LEN):
        reach = smooth(K(FF_REACH, t))
        shield = smooth(K(FF_SHIELD, t))
        pitch = K(FF_PITCH, t)
        feet, lift = stepping(t, FEET, FF_STEPS, lift=1.0)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, z=K(FF_ROOTZ, t), dip=K(FF_DIP, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        qh = qeuler(x=K(FF_HP, t), y=K(FF_HY, t))
        # the paper to the flame, trembling a little as it curls
        shake = 0.3 * bump(t, FF_TOUCH, FF_GONE) * math.sin(2 * math.pi * (t - FF_TOUCH) / 3.0)
        at = FF_AT + np.array([0.0, shake, 0.0])
        held = reach_arm(holds, 'right_arm', fr, at, palm=L, palm_w=0.15, slack=0.5, limit=1.6, p=handle)
        rest = rest_arm('right_arm', fr)
        if t < FF_SNATCH[0]:
            pose = mix(rest, held, reach) if reach < 1.0 else held
        else:
            # snatched back from the flame to before the face, and later let down: each along an arc about the shoulder
            # (the hand never nearer than the arm's own length), in front of the body and not through it
            s = smooth((t - FF_SNATCH[0]) / (FF_SNATCH[1] - FF_SNATCH[0]))
            r = smooth((t - FF_LOWER[0]) / (FF_LOWER[1] - FF_LOWER[0]))
            shoulder = torso_to_world(fr, PIVOTS['right_arm'])
            a, b, z = FF_AT - shoulder, torso_to_world(fr, FF_SHIELD_T) - shoulder, grip_of('right_arm', rest, fr, p=handle) - shoulder
            ra, rb, rz = float(np.linalg.norm(a)), float(np.linalg.norm(b)), float(np.linalg.norm(z))
            out = np.zeros(3)
            if r <= 0.0:
                d, rad, palm = qslerp_dir(a, b, s), ra + (rb - ra) * s, L
            else:
                d, rad, palm = qslerp_dir(b, z, r), rb + (rz - rb) * r, unit((1.0 - r) * L + r * F)         # (the palm turning to the front as the arm comes down)
                out = np.array([-1.6 * math.sin(math.pi * r), 0.0, 0.0])             # swung out round the hip on the way down
            pose = reach_arm(holds, 'right_arm', fr, shoulder + rad * d + out, palm=palm, palm_w=0.3, slack=0.6, limit=1.6, p=handle)
            if r > 0.75:
                pose = mix(pose, rest, smooth((r - 0.75) / 0.25))
        set_arm(c, 'right_arm', t, pose)
        set_arm(c, 'left_arm', t, lifted(rest_arm('left_arm', fr, fwd=14.0 * smooth(K(FF_REACH, t)) - 22.0 * shield), 0.6 * shield))
        c.key('head', t, qh, ease='linear')
    c.mode('head', 'set')
    la.set_goods('feed_fire', [(0.0, FF_GONE, ('part', 'right_arm', handle), 5.0)])
    check(c, f'the paper at the flame (t={FF_TOUCH:g})', ta.fk(c, FF_TOUCH, 'right_arm', handle), FF_AT, tol=0.6)
    times = [i * 0.5 for i in range(2 * FF_LEN + 1)]
    oven = [b for b in la.station_boxes('oven') if b[1][1] - b[0][1] > 1.0]               # (the ground slab left out)
    for part, label, tol in (('right_arm', 'right hand', 0.8), ('left_arm', 'left hand', 0.6), ('head', 'head', 0.6),
                             ('body', 'body', 0.6), ('right_leg', 'right leg', 0.6), ('left_leg', 'left leg', 0.6)):
        worst, at_ = max((deepest_in(c, t, part, oven), t) for t in times)
        check(c, f'{label} clear of the oven (deepest, at t={at_:g})', (worst,), (0.0,), tol=tol)
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=0.8)
    joins_idle(c)
    return c


# ================================================================== grieve

# ---- grieve (80, loop): standing, half turned from the street, the face down into both hands. A slow, uneven round: three
# sobs closing in on each other (7, 14, 20.5), the shoulders heaved up and let drop, the head going down with each and a
# shudder after; a breath caught in three catches (31 to 37) and let go in a long slump (the knees giving); two small sobs
# more (52, 57); the right hand drawn across the cheek twice (61 to 68) and back to the face; and still again by the seam,
# but for a tremble that never quite stops.
GR_LEN = 80
GR_SOBS = [(7.0, 1.0), (14.0, 0.8), (20.5, 0.95), (52.0, 0.55), (57.0, 0.4)]
GR_FACE = {'right_arm': np.array([-1.9, -3.4, -8.3]), 'left_arm': np.array([1.9, -3.4, -8.3])}     # head space
GR_WIPE = (61.0, 68.0)
GR_INHALE = [(0, 0.0), (29.0, 0.0), (31.0, 0.35), (32.5, 0.3), (34.0, 0.7), (35.0, 0.65), (36.5, 1.0), (38.0, 1.0), (41.0, 0.0), (80, 0.0)]
GR_SLUMP = [(0, 0.0), (36.5, 0.0), (42.0, 1.0), (49.0, 1.0), (57.0, 0.0), (80, 0.0)]
GR_TWIST = -42.0                                # the half turn: the right shoulder forward, away from the street


def sob(t, t0, a=1.0, rise=1.3, fall=2.3):
    """One heave: up fast, let go in a ringing fall (0 before [t0] and from ten ticks after)."""
    u = t - t0
    if u <= 0.0 or u >= 10.0:
        return 0.0
    if u < rise:
        return a * smooth(u / rise)
    return a * math.exp(-(u - rise) / fall) * (1.0 + 0.3 * math.cos(2 * math.pi * (u - rise) / 4.2)) * smooth((10.0 - u) / 2.5)


def sobs(t, lag=0.0, scale=1.0):
    return scale * sum(sob(t - lag, t0, a) for t0, a in GR_SOBS)


def tremble(t, k=1.0):
    """A small trembling that is never quite still (periods of 80 / n ticks, so the loop closes)."""
    return k * (math.sin(2 * math.pi * (9 * t / GR_LEN + 0.15)) + 0.8 * math.sin(2 * math.pi * (14 * t / GR_LEN + 0.6))
                + 0.5 * math.sin(2 * math.pi * (23 * t / GR_LEN + 0.3))) / 2.3


def grieve():
    c = la.clip('grieve', GR_LEN, True, look=('citizen', 5), events=[],
                traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    times = la.every(GR_LEN)
    for lap in range(2):
        for t in times:
            s0, s1, sl = sobs(t), sobs(t, lag=1.0), sobs(t, lag=0.6, scale=0.85)
            inhale, slump = smooth(K(GR_INHALE, t)), smooth(K(GR_SLUMP, t))
            shiver = tremble(t, 0.25 + 0.7 * min(1.0, s0 + 0.6 * inhale))
            sway = math.sin(2 * math.pi * (t - 6.0) / GR_LEN)
            pitch = 13.0 + 3.4 * s0 - 2.5 * inhale + 3.0 * slump + 0.8 * math.sin(2 * math.pi * (t - 3.0) / 40.0) + 0.5 * shiver
            yaw = GR_TWIST - 3.0 * sway - 3.0 * s0 + 3.0 * slump + 0.6 * shiver
            roll = 1.2 * sway + 1.0 * s0
            fr = F_(qt=bend(pitch, yaw, roll), vt=(0.0, sink_for(pitch) - 0.5 * slump, 0.0),
                    pr=la.root_for(FEET, x=0.8 * sway, dip=0.3 + 0.6 * slump + 0.35 * s0))
            qh = qeuler(x=26.0 + 7.0 * s1 - 9.0 * inhale + 5.0 * slump + 1.5 * math.sin(2 * math.pi * (t - 12.0) / 80.0) + 0.8 * shiver,
                        y=4.0 * math.sin(2 * math.pi * (t - 20.0) / 80.0) + 0.8 * shiver,
                        z=-4.0 + 3.0 * math.sin(2 * math.pi * t / 80.0) + 2.5 * s1)
            poses = {}
            wipe = bump(t, *GR_WIPE)
            for side, sh in (('right_arm', s0), ('left_arm', sl)):
                face = GR_FACE[side] + np.array([0.0, 0.0, 0.15 * sh])
                if side == 'right_arm' and wipe > 1e-4:
                    stroke = 0.5 - 0.5 * math.cos(2 * math.pi * (t - GR_WIPE[0]) / 3.5)
                    face = face + wipe * (np.array([-1.3, 1.2, 0.5]) + stroke * np.array([-0.9, 0.9, 0.0]))
                at = head_pt(fr, qh, face)
                poses[side] = lifted(reach_arm(holds, side, fr, at, palm=head_pt(fr, qh, (0.0, -3.4, -2.0)) - at, palm_w=0.3,
                                               slack=0.4, limit=1.7), 0.5 + 1.6 * sh + 1.0 * inhale - 0.8 * slump + 0.15 * shiver)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr)
                for side, pose in poses.items():
                    set_arm(c, side, t, pose)
                c.key('head', t, qh, ease='linear')
    c.mode('head', 'set')
    seal(c)
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=1.4)         # (hands at the face touch it: the block's back corner)
    return c


# ================================================================== wring_hands

# ---- wring_hands (60, loop): waiting, and afraid of what for. The hands clasped before the chest and twisted against each
# other in bursts (2 to 14, quick; 20 to 33, slower and harder; 53 to 59, a flutter), a tight hold between with the
# shoulders up and a tremor; a small step in place (24 to 29, back again 44 to 49); the head turning to look along the
# road, one way and the other and down at the hands; a hand to the throat (38 to 48), and back to wringing; the seam is
# the hold.
WH_LEN = 60
WH_CLASP = np.array([0.0, 5.3, -8.2])           # torso space: the middle of the clasp
WH_BURSTS = [(2.0, 14.0, 4.4, 1.0), (20.0, 33.0, 6.2, 1.4), (53.0, 59.0, 3.6, 0.7)]       # from, to, period, size
WH_HOLD = [(0, 1.0), (2.0, 0.2), (14.0, 0.2), (16.0, 1.0), (19.0, 1.0), (21.0, 0.2), (33.0, 0.2), (35.0, 0.6), (52.0, 0.6),
           (53.0, 0.2), (59.0, 0.2), (60, 1.0)]                                   # how tight the grip, between
WH_THROAT = [(0, 0.0), (37.0, 0.0), (40.5, 1.0), (45.0, 1.0), (48.5, 0.0), (60, 0.0)]
WH_HY = [(0, 0.0), (4.0, 6.0), (9.0, -34.0), (17.0, -36.0), (21.0, 0.0), (24.0, 28.0), (33.0, 30.0), (36.0, 8.0), (38.0, 0.0),
         (44.0, -22.0), (47.0, -24.0), (51.0, 4.0), (60, 0.0)]
WH_HX = [(0, 4.0), (4.0, 12.0), (9.0, 2.0), (17.0, 0.0), (21.0, 8.0), (24.0, 0.0), (33.0, -2.0), (36.0, 12.0), (39.0, 6.0),
         (44.0, -2.0), (47.0, -3.0), (51.0, 10.0), (60, 4.0)]
WH_STEPS = [(24.0, 29.0, 'left_leg', (3.1, -0.8)), (44.0, 49.0, 'left_leg', (1.9, 0.0))]
WH_THROAT_AT = np.array([2.0, 0.6, -6.9])       # torso space: the right hand held at the throat, before the left collarbone


def wh_osc(t):
    """The wringing as a pair (sine, cosine) in bursts, each with its own period and size."""
    s_ = c_ = 0.0
    for a, b, period, size in WH_BURSTS:
        env = size * bump(t, a, b) ** 0.5
        ph = 2 * math.pi * (t - a) / period
        s_ += env * math.sin(ph)
        c_ += env * math.cos(ph)
    return s_, c_


def wring_hands():
    c = la.clip('wring_hands', WH_LEN, True, look=('citizen', 3), events=[],
                traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    times = la.every(WH_LEN)
    for lap in range(2):
        for t in times:
            so, co = wh_osc(t)
            hold = K(WH_HOLD, t)
            throat = smooth(K(WH_THROAT, t))
            feet, lift = stepping(t, FEET, WH_STEPS, lift=1.1)
            pitch = 6.0 + 1.5 * hold + 1.0 * so + 1.2 * math.sin(2 * math.pi * (t - 8.0) / WH_LEN)
            fr = F_(qt=bend(pitch, 4.0 * math.sin(2 * math.pi * (t - 3.0) / WH_LEN) + 1.2 * so, 0.8 * so),
                    vt=(0.0, sink_for(pitch), 0.0),
                    pr=la.root_for(feet, x=-0.55 * 0.5 * (feet['left_leg'][0] - 1.9), dip=0.3 + 0.3 * hold))
            qh = qeuler(x=K(WH_HX, t) + 0.8 * hold, y=K(WH_HY, t), z=1.5 * math.sin(2 * math.pi * (t - 5.0) / 30.0))
            tremor = 0.18 * hold * math.sin(2 * math.pi * t / 3.0)
            poses = {}
            for side, sx in (('right_arm', -1.0), ('left_arm', 1.0)):
                # each hand rides a small ellipse about its place in the clasp, the two going round against each other
                at = WH_CLASP + np.array([sx * (0.9 - 0.5 * hold) - sx * 1.0 * co, -sx * 1.4 * so + tremor, -sx * 0.45 * co])
                roll = math.radians(30.0) * so * (-sx)
                palm = unit(-sx * math.cos(roll) * L + 0.45 * U + math.sin(roll) * F)
                pose = lifted(reach_arm(holds, side, fr, torso_to_world(fr, at), palm=palm, palm_w=0.5, slack=0.5, limit=1.7),
                              0.5 * hold + 0.2 * abs(so))
                if side == 'right_arm' and throat > 1e-4:
                    to = reach_arm(holds, side, fr, torso_to_world(fr, WH_THROAT_AT), palm=L + 0.3 * D, palm_w=0.4, slack=0.6, limit=1.7)
                    pose = mix(pose, to, throat)
                poses[side] = pose
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr, feet=feet, lift=lift)
                for side, pose in poses.items():
                    set_arm(c, side, t, pose)
                c.key('head', t, qh, ease='linear')
    c.mode('head', 'add')
    seal(c)
    for side in ('right_arm', 'left_arm'):
        clear_of(c, side, times, into=('body', 'head'), tol=0.8)
    return c


# ================================================================== hobble

# ---- hobble (40, loop, overlay): a stooped walk. An overlay moves only the arms and the head, so the walk goes on under it
# (the game's part) and the stoop is made of what is left: the head carried low and peering at the ground a step ahead, the
# right fist pressed into the small of the back, the shoulders drawn forward and up, the free arm hanging forward and
# swung a little as a balance. Two strides to the loop and never the same twice: the near foot takes the weight with a drop
# of the head, the weight rolls over it slowly (the long one), the other foot hurries after with a small lift, and once a
# loop the head comes up for a look along the way and goes down again.
HB_LEN = 40
HB_BACK = np.array([-4.1, 10.0, 5.0])           # torso space: the right fist at the small of the back
HB_PITCH = [(0, 21.0), (2.5, 28.0), (6.0, 25.0), (11.0, 22.0), (14.0, 18.5), (17.0, 19.5), (20.0, 22.0), (22.5, 26.5),
            (27.0, 23.5), (31.0, 21.0), (33.5, 12.5), (36.5, 10.0), (39.0, 17.0), (40, 21.0)]        # the head, degrees down
HB_YAW = [(0, 3.0), (7.0, -7.0), (14.0, -2.0), (21.0, 6.0), (27.0, 9.0), (33.5, 1.0), (37.0, -1.0), (40, 3.0)]
HB_HITCH = [(0, 0.1), (2.5, 1.0), (6.0, 0.6), (11.0, 0.15), (14.0, -0.35), (17.0, -0.2), (20.0, 0.1), (22.5, 0.75), (27.0, 0.4),
            (31.0, 0.05), (36.0, -0.3), (40, 0.1)]                    # the weight's drop and lift (a number to drive the rest)


def hobble():
    c = la.clip('hobble', HB_LEN, True, look=('citizen', 3), overlay=True, events=[],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
                views=((32, -8), (90, -2), (150, -6)))
    fr0 = F_()
    holds = la.Holds()
    for lap in range(2):
        for t in la.every(HB_LEN):
            hitch = K(HB_HITCH, t, HB_LEN)
            sw = math.sin(2 * math.pi * (t - 6.0) / 20.0)
            # the right fist at the small of the back, pressed in a little as the weight drops
            at = torso_to_world(fr0, HB_BACK + np.array([0.0, 0.5 * hitch, 0.15 * hitch]))
            right = lifted(reach_arm(holds, 'right_arm', fr0, at, palm=unit(F + 0.4 * L), palm_w=0.4, slack=0.5, limit=1.4),
                           0.7 + 0.5 * hitch)
            # the free arm hangs forward and swings with the stride, the shoulder drawn forward
            key, off = rest_arm('left_arm', fr0, fwd=15.0 + 9.0 * sw + 3.0 * math.sin(2 * math.pi * t / HB_LEN))
            if lap:
                la.key_arm(c, 'right_arm', t, right)
                la.key_arm(c, 'left_arm', t, (key, off + np.array([0.0, -0.5, -1.0])))
                c.key('head', t, qeuler(x=K(HB_PITCH, t, HB_LEN), y=K(HB_YAW, t, HB_LEN),
                                        z=3.0 * math.cos(2 * math.pi * (t - 2.5) / 20.0)), ease='linear')
    c.mode('head', 'add')
    seal(c)
    return c


# ================================================================== lay_posy

# ---- lay_posy (40, once): the mourner kneeling before the memorial stone (the previews' station 'memorial': a plinth and a
# narrowing shaft as tale_blocks.py builds them, its plinth's front face LP_D px ahead), the posy held at the chest between
# both hands, as the kneel's sword was. A shuffle forward on the knees (LP_S px), a lean in, the posy set down on the
# plinth's ledge at the shaft's foot (LP_LAND: the scene's Fx times to this tick) and let go of (LP_LET: the goods end), the
# left hand to the ground as a brace, the right hand across to the shaft's face and rested there, the head bowed on it; then
# the rise, a step back and the arms down: it ends standing, on idle, so whatever follows (the embrace) starts from a stand.
LP_LEN = 40
LP_D = 24.5                                     # the plinth's front face this far ahead of her: 2 blocks from the stone block's middle (S5's ring of radius 2)
LP_S = 10.5                                     # and the knees shuffle this far in, to 14 px from it
LP_LAND = 14.0
LP_LET = 18.0
LP_ON = 24.0
LP_RISE = 29.0
# the memorial block's pieces (tale_blocks.py: PLINTH, SEGS, CAP), in the block's own pixels: x across, y up from the foot,
# z from the front (the carved face) back
MEM_PLINTH = ((0.75, 0.0, 0.5), (15.25, 5.5, 13.5))
MEM_Y = (5.5, 10.5, 15.5, 20.5, 25.5, 30.0)
MEM_SEGS = [(MEM_Y[i], MEM_Y[i + 1], 2.0 + 0.25 * i, 14.0 - 0.25 * i, 3.5 + 0.25 * i, 12.5 - 0.25 * i) for i in range(5)]
MEM_CAP = ((2.5, 30.0, 4.0), (13.5, 32.0, 12.0))
MEM_SLAB = ((4.5, 9.0, 3.3), (11.5, 21.0, 3.45))            # the carved face's name slab, to see where a hand rests


def _memorial_boxes():
    """The memorial stone ahead of the kneeler, its plinth's front face LP_D px ahead, the block's foot on the ground."""
    def box(lo, hi, col):
        return ((lo[0] - 8.0, 24.0 - hi[1], -(LP_D - 0.5) - hi[2]), (hi[0] - 8.0, 24.0 - lo[1], -(LP_D - 0.5) - lo[2]), col, True)
    b = [box(*MEM_PLINTH, (118, 118, 126))]
    for i, (y0, y1, x0, x1, z0, z1) in enumerate(MEM_SEGS):
        b.append(box((x0, y0, z0), (x1, y1, z1), (142, 142, 150) if i % 2 == 0 else (132, 132, 140)))
    b.append(box(*MEM_CAP, (154, 154, 162)))
    b.append(box(*MEM_SLAB, (96, 96, 106)))
    return b


LP_LEDGE_Y = 24.0 - MEM_PLINTH[1][1]            # the plinth's top, where the posy is set down (clip space)
LP_DX = 2.8                                     # she shuffles in this far to her left of the stone's middle: the right arm then runs straight on to the shaft
LP_FEET0 = {'right_leg': (-2.4, 10.918), 'left_leg': (4.4, -10.318)}        # the kneel's feet at its tick 0 (clip space)
LP_STEPS = [(2.0, 5.5, 'right_leg', (LP_FEET0['right_leg'][0] + 0.5 * LP_DX, LP_FEET0['right_leg'][1] - 0.5 * LP_S)),         # two rounds of the knees
            (3.5, 7.0, 'left_leg', (LP_FEET0['left_leg'][0] + 0.5 * LP_DX, LP_FEET0['left_leg'][1] - 0.5 * LP_S)),
            (6.0, 9.5, 'right_leg', (LP_FEET0['right_leg'][0] + LP_DX, LP_FEET0['right_leg'][1] - LP_S)),
            (7.5, 11.0, 'left_leg', (LP_FEET0['left_leg'][0] + LP_DX, LP_FEET0['left_leg'][1] - LP_S)),
            (28.5, 34.5, 'right_leg', (-1.9, 0.0)), (31.0, 38.5, 'left_leg', (1.9, 0.0))]
LP_HOLD = 1.8                                   # the hands' half spread on the posy
LP_REACH = 9.5                                  # from the midpoint of the shoulders to the posy (the arms are 10 long)
LP_PICK = {'right_arm': 2.4, 'left_arm': 2.0}   # ticks the hands take from the kneel's hold to the posy's place (the left starts higher)
LP_TURN = 6.0                                   # and to turn their palms in round it (a slower turn: it is the forearms' roll)
LP_PITCH = [(0, 16.0), (3.0, 20.0), (6.0, 27.0), (9.5, 33.0), (LP_LAND, 36.0), (LP_LET, 36.0), (21.0, 42.0), (LP_ON, 47.0),
            (28.0, 47.0), (30.0, 44.0), (33.0, 30.0), (36.0, 12.0), (38.5, 2.0), (40, 0.0)]
LP_ELEV = [(0, 16.0), (2.5, 12.0), (5.0, 6.0), (7.5, -2.0), (10.0, -12.0), (12.0, -20.0), (LP_LAND, -23.0), (LP_LET, -23.0)]
LP_STONE = np.array([LP_DX - 6.4, 12.5, -(LP_D - 0.5) - 3.4])  # the right fist on the shaft's face (the name slab's)
LP_HEAD = [(0, -13.4), (2.0, -11.5), (4.0, -5.5), (7.0, 3.0), (10.5, 12.0), (LP_LAND, 15.0), (LP_LET, 14.0), (20.0, 4.0),
           (22.0, -6.0), (24.0, -2.0), (26.5, 9.0), (29.0, 10.0), (31.5, 3.0), (34.0, -6.0), (37.0, -4.0), (40, 0.0)]    # the head's pitch off the torso: up to the stone's name, then bowed
LP_YAW = [(0, 0.0), (18.0, 0.0), (21.0, -8.0), (23.5, -6.0), (26.0, 0.0), (40, 0.0)]                       # a look aside at the name before the bow
LP_ROLL = [(0, 0.0), (20.0, 0.0), (23.5, 8.0), (29.0, 8.0), (33.0, 3.0), (36.0, 0.0), (40, 0.0)]
LP_OFF = (LP_ON + 4.5, LP_ON + 12.0)            # the right arm leaves the stone and comes down


def lp_dx(t):
    """How far the kneeler has moved to her left (px): over the shuffle in, and back with the rise."""
    return LP_DX * smooth((t - 2.0) / 9.0) * (1.0 - smooth((t - 28.5) / 10.0))


def lay_posy():
    kneel = la.getc('kneel')
    c = la.clip('lay_posy', LP_LEN, False, look=('citizen', 5), station_='memorial', events=[],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
                views=((-55, 8), (115, 8), (90, 2)), pan=(0.0, 0.0, -(LP_D / 2 + 0.5)))
    la.set_goods('lay_posy', [(0.0, LP_LET, ('hands', 1.5), 6.0)])
    holds = la.Holds()
    k0 = {s: (kneel.sample(s, 0.0)[0], kneel.sample(s, 0.0)[1]) for s in ('right_arm', 'left_arm')}
    k0_head = kneel.sample('head', 0.0)[0]
    # the kneel's hands at its tick 0, and which way their palms face: the hands set off from there
    g0 = {s: ta.fk(kneel, 0.0, s, GRIP[s]) for s in ('right_arm', 'left_arm')}
    p0 = {s: unit(ta.fk(kneel, 0.0, s, np.asarray(GRIP[s], float) + np.array([0.0, 0.0, -1.0])) - g0[s]) for s in g0}
    start = {}
    d_off = {}
    for t in la.every(LP_LEN):
        feet, lift = stepping(t, LP_FEET0, LP_STEPS, lift=1.4)
        rz = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
        dx = lp_dx(t)
        pitch = K(LP_PITCH, t)
        down = 7.0 * (1.0 - smooth((t - 28.5) / 10.0))
        base = la.root_for(feet, x=dx, z=rz)
        fr = F_(qt=bend(pitch), vt=(0.0, sink_for(pitch), 0.0), pr=np.array([dx, max(down, base[1]), rz]))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        shoulders = torso_to_world(fr, (0.0, 2.0, 0.0))
        e = math.radians(K(LP_ELEV, t))
        mid = shoulders + LP_REACH * np.array([0.0, -math.sin(e), -math.cos(e)])
        hold = smooth(min(1.0, t / 4.0))
        turn = smooth(min(1.0, t / LP_TURN))
        poses = {}
        for side, sx in (('right_arm', -1.0), ('left_arm', 1.0)):
            pick = smooth(min(1.0, t / LP_PICK[side]))
            if side not in start:
                start[side] = ta.torso_local(fr, g0[side])          # (the first sample is the kneel's own frame)
            at = mid + np.array([sx * LP_HOLD, 0.0, 0.0])
            palm = L if side == 'right_arm' else R
            if side == 'right_arm' and t > LP_LET:
                u = smooth((t - LP_LET) / (LP_ON - LP_LET))
                on = LP_STONE + np.array([0.0, 0.9 * smooth((t - 25.5) / 2.5), 0.0])      # the fingers slide a little down the face
                at = at * (1.0 - u) + on * u
                palm = unit((1.0 - u) * L + u * F)
            if pick < 1.0:
                # off from the kneel's hold, carried with the torso, to the posy's place; the palms turn in more slowly
                at = torso_to_world(fr, start[side]) * (1.0 - pick) + at * pick
            if turn < 1.0:
                palm = unit(p0[side] * (1.0 - turn) + palm * turn)
            pose = reach_arm(holds, side, fr, at, palm=palm, palm_w=0.3, slack=0.6, limit=1.6)
            if t < 1.0:
                pose = mix(k0[side], pose, smooth(t))                # (solved from nothing, the first sample may sit a little off)
            poses[side] = pose
        # the left hand lets go and hangs as a brace; the right comes off the stone and down
        lw = smooth((t - LP_LET) / 4.5)
        if lw > 0.0:
            poses['left_arm'] = mix(poses['left_arm'], rest_arm('left_arm', fr), lw)
        if t > LP_OFF[0]:
            if 'r0' not in d_off:
                d_off['r0'] = unit(LP_STONE - shoulders)
            dr = unit(qslerp_dir(d_off['r0'], D, smooth((t - LP_OFF[0]) / (LP_OFF[1] - LP_OFF[0]))))
            free_r = point_arm(fr, dr, palm=L)
            poses['right_arm'] = mix(poses['right_arm'], free_r, smooth((t - LP_OFF[0]) / 2.0))
            poses['right_arm'] = mix(poses['right_arm'], rest_arm('right_arm', fr), smooth((t - 33.0) / 5.0))
        for side in ('right_arm', 'left_arm'):
            set_arm(c, side, t, poses[side])
        # the head: over the posy as it is carried and set down, up to the stone's name, bowed on it, then up as the body rises
        c.key('head', t, qslerp(k0_head, qeuler(x=K(LP_HEAD, t), y=K(LP_YAW, t), z=K(LP_ROLL, t)), hold), ease='linear')
    c.mode('head', 'set')
    check(c, f'the posy on the ledge (t={LP_LAND:g})', posy_at(c, LP_LAND), np.array([LP_DX, LP_LEDGE_Y - 3.0, -(LP_D + 1.5)]), tol=1.0)
    check(c, 'the right fist on the stone (t=26)', ta.fk(c, 26.0, 'right_arm', GRIP['right_arm']), LP_STONE, tol=0.8)
    stone = _memorial_boxes()
    times = [i * 0.5 for i in range(2 * LP_LEN + 1)]
    for part, label, tol in (('head', 'head', 0.6), ('body', 'body', 0.6), ('right_arm', 'right hand', 0.8), ('left_arm', 'left hand', 0.6),
                             ('right_leg', 'right leg', 0.6), ('left_leg', 'left leg', 0.6)):
        worst, at = max((deepest_in(c, t, part, stone), t) for t in times)
        check(c, f'{label} clear of the stone (deepest, at t={at:g})', (worst,), (0.0,), tol=tol)
    ground = [((-60.0, 24.0, -60.0), (60.0, 40.0, 60.0))]
    for part, label in (('right_arm', 'right hand'), ('left_arm', 'left hand')):
        worst, at = max((deepest_in(c, t, part, ground), t) for t in times)
        check(c, f'{label} above the ground (deepest, at t={at:g})', (worst,), (0.0,), tol=0.6)
    for side in ('right_arm', 'left_arm'):
        # (from tick 1.5: she sets off from the kneel's own hold, whose hands are against the chin, 2 px into the jaw's box)
        clear_of(c, side, [t for t in times if t >= 1.5], into=('body', 'head'), tol=0.8)
    maybe_join('lay_posy starts on kneel', c, 0, 'kneel', 0, tol=0.6)
    joins_idle(c, start=False)
    return c


def posy_at(c, t):
    """The posy's middle at [t]: between the two hands, 1.5 px on ahead (how LivingSchedule draws it)."""
    a, b = ta.fk(c, t, 'right_arm', GRIP['right_arm']), ta.fk(c, t, 'left_arm', GRIP['left_arm'])
    return (a + b) / 2 + np.array([0.0, 0.0, -1.5])


# ================================================================== the registry

TALE_CLIPS = ['grieve', 'wring_hands', 'shake_hands', 'feed_fire', 'hobble', 'lay_posy']
MAKERS = [('grieve', grieve), ('wring_hands', wring_hands), ('shake_hands', shake_hands), ('feed_fire', feed_fire),
          ('hobble', hobble), ('lay_posy', lay_posy)]
NEEDS = {}
BUILT = {}
STATION_BOXES = {'memorial': _memorial_boxes}


def build(living, only=None):
    """Builds the tales' clips on living_anims' kit ([living]: the module). [only]: just these (the quick previews)."""
    global la
    la = living
    if BUILT:
        return
    la.STATIONS.update(STATION_BOXES)
    want = None if not only else set(only)
    for name, make in MAKERS:
        if want is not None and name not in want:
            continue
        make()
    BUILT['done'] = True
    la.WANTED.extend(TALE_CLIPS)


# ================================================================== the previews

# python3 -B tale_clips.py <out dir> [group ...]: the GIFs of the six clips (living_anims builds them first), as the
# townsfolk previews are made: a 2x2 grid for each pair of clips, each clip from two views, at the game's pace (a frame a
# tick; a one-shot is held on its last frame for GIF_REST ticks and goes again).
GIF_GROUPS = {
    'grieve_wring_hands': ['grieve', 'wring_hands'],
    'shake_hands_feed_fire': ['shake_hands', 'feed_fire'],
    'hobble_lay_posy': ['hobble', 'lay_posy'],
}
GIF_CELL = (240, 300)
GIF_REST = 10
GIF_SS = 2                                      # drawn at twice the size and scaled down: smooth edges
GIF_VIEWS = {'grieve': ((32, -10), (-40, -8)), 'wring_hands': ((32, -10), (0, -4)), 'shake_hands': ((75, -8), (90, 30)),
             'feed_fire': ((50, -10), (90, -2)), 'hobble': ((32, -8), (90, -2)), 'lay_posy': ((-55, 8), (115, 8))}
GIF_FRAME = {'shake_hands': (4.6, 8.0), 'feed_fire': (5.0, 8.0), 'lay_posy': (4.7, 9.0)}      # zoom, the figure's middle
GIF_PACE = {'hobble': (0.3, 20)}                # the walk under an overlay: speed and period (slow: a bad leg)
GIF_MARKS = {'feed_fire': [('the paper takes', 14.0)], 'lay_posy': [('the posy is down', 13.5)]}     # where the scene's Fx fall


def gif_cell(c, t, view):
    """[c] at [t] from [view] as one cell of a preview."""
    W, H = GIF_CELL
    info = ta.INFO[c.name]
    look = ta.look_for(info['look'])
    zoom, middle = GIF_FRAME.get(c.name, (5.6, 8.0))
    project = ta.ortho(view[0], view[1], zoom * GIF_SS, (W * GIF_SS, H * GIF_SS), middle)
    if info.get('pan') is not None:
        project = (lambda base_, pan_: (lambda p: base_(np.asarray(p, float) - pan_)))(project, np.asarray(info['pan'], float))
    walking = info['walking']
    pace = GIF_PACE.get(c.name)
    keep = ta.walk_base
    if pace and walking:
        ta.walk_base = lambda t_, **k: keep(t_, **{**k, 'speed': pace[0], 'period': pace[1]})
    try:
        img, _ = ta.render_cell(look, c, t, project, (W * GIF_SS, H * GIF_SS), walking)
    finally:
        ta.walk_base = keep
    return img.resize((W, H), Image.LANCZOS)


def gif_time(c, k):
    """The clip's tick at frame [k] of its preview."""
    if ta.INFO[c.name]['loop']:
        return float(k % int(c.length))
    return float(min(k % (int(c.length) + GIF_REST), c.length))


def make_gif(key, out_dir):
    """The preview [key] of GIF_GROUPS: a 2x2 grid, a clip to a row, its two views across; returns its path."""
    import shutil
    import subprocess
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
    path = os.path.join(out_dir, f'tale_clips_{key}.gif')
    q[0].save(path, save_all=True, append_images=q[1:], duration=50, loop=0, optimize=False, disposal=1)
    if shutil.which('convert'):
        # the pixels that do not change from the frame before go transparent, which packs far smaller
        subprocess.run(['convert', path, '-layers', 'OptimizeTransparency', '+map', path + '.tmp.gif'], check=True)
        os.replace(path + '.tmp.gif', path)
    return path


def gifs(out_dir, only=None):
    """The previews (all of GIF_GROUPS, or the keys in [only]) written to [out_dir]; their paths."""
    os.makedirs(out_dir, exist_ok=True)
    return [make_gif(key, out_dir) for key in GIF_GROUPS if not only or key in only]


if __name__ == '__main__':
    out = sys.argv[1]
    import living_anims as la_          # noqa: F401  (builds every clip, these among them)
    import tale_clips as tc_            # (the copy living_anims built)
    for p in tc_.gifs(out, set(sys.argv[2:])):
        print('wrote', p)
