"""
The caravan crew's moves (M4, trouble on the road: design-road.md section 5), on the Living World's kit. A driver on
the wagon's bench with the reins in both hands (drive), giving them a flick (drive_urge) and hauling on them with the
brake on (drive_brake); a guard riding the tailboard with the spear across the knees (ride_tail); getting down off
the wagon and back up (jump_down, climb_up); the driver cutting the horse loose and sending it off (cut_loose); a
carter cowering behind a wheel (cower); the load handed down off the tailboard to someone below (hand_down, with
receive); and the driver back at the gate: out of breath (catch_breath), telling it (recount), limping with a hand
to the side (wounded, an overlay) and grumbling (grumble).

living_anims.py imports this at its end and calls build() with itself, so these clips go out with the Living
World's and LivingSchedule.kt stays one generated file. The wagon's numbers come from wagon.py (its seats, bench,
footboard, brake lever and tailboard) and the horse's from draught_horse.py (its rein rings, flank and rump, as it
stands in the shafts), so a fist finds the lever and the reins run to the rings. CrewMoves.kt (client/caravan,
written by main() here and by living_anims.py's run) gives the game the numbers the clips are made to: where each
one is played from, where it leaves someone, the brake lever's lean through drive_brake, when the traces part.

Clip space as every townsfolk clip's: pixels, y down, facing -z, the right hand at -x, the feet at y 24 when
standing; a seated clip has the seat's top at y 24 under the hips (sit's). Wagon space as wagon.py's: the ground
under the middle of the back axle, facing -z (toward the horse).
"""
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import townsfolk_anims as ta
from townsfolk_anims import (qeuler, qmat, qslerp, unit, F, B, U, D, R, L, T, Rq, F_, bend, key_frame, check, smooth,
                             bump, apply, SEAT_DROP, GRIP, PIVOTS, HIPS, torso_to_world, body_solve, hand_point, sink_for)
import living_props as lp
import wagon as wg

la = None           # living_anims, handed to build()

# ================================================================== the wagon and the horse, as the crew meet them


def seat_space(seat, p):
    """A point of the wagon's space in the clip space of someone at [seat] ((point, yaw): the seat's top under their
    hips at clip y 24; yaw 0 facing ahead, 180 facing back)."""
    (sx, sy, sz), yaw = seat
    d = np.asarray(p, float) - np.array([sx, sy, sz])
    if yaw == 180.0:
        d = np.array([-d[0], d[1], -d[2]])
    elif yaw != 0.0:
        raise ValueError(yaw)
    return d + np.array([0.0, 24.0, 0.0])


def horse_point(p):
    """A point of the horse's model space (draught_horse.py: its ground at y 24, its origin under the middle of its
    body) in the wagon's, the horse standing in the shafts."""
    return np.array([p[0], p[1] - 24.0, p[2] + wg.HORSE_Z])


DRIVER = wg.SEATS['driver']
TAIL = wg.SEATS['tail']
STAND = wg.STAND

# the rein rings on the horse's cart saddle (x mirrored)
TERRET = {side: horse_point((s * wg.TERRET[0], 24.0 - wg.TERRET[1], wg.TUG_Z)) for side, s in (('right', -1), ('left', 1))}
TERRET_D = {side: seat_space(DRIVER, p) for side, p in TERRET.items()}

# the footboard the driver's soles press on: the middle of its back face, and that face's normal (toward the driver)
DASH_D = seat_space(DRIVER, wg.DASH)
_a = math.radians(wg.DASH_LEAN)
DASH_N = np.array([0.0, -math.sin(_a), math.cos(_a)])

# the brake lever, beside the driver's right hip: its pivot, and its grip at a lean (+ toward the driver)
BRAKE_PIVOT_D = seat_space(DRIVER, wg.BRAKE_PIVOT)
BRAKE_GRIP = wg.BRAKE_LEN - 1.2         # WagonMesh.BRAKE_LENGTH: the pivot to the grip's middle


def brake_grip(lean):
    a = math.radians(lean)
    return BRAKE_PIVOT_D + np.array([0.0, -BRAKE_GRIP * math.cos(a), -BRAKE_GRIP * math.sin(a)])


def brake_lean(p):
    """The lever's lean that brings its grip nearest [p] (driver space)."""
    d = np.asarray(p, float) - BRAKE_PIVOT_D
    return math.degrees(math.atan2(-d[2], -d[1]))


BENCH_EDGE_D = seat_space(DRIVER, (-wg.BENCH_X, wg.BENCH_TOP, wg.BENCH_FRONT))   # the bench's right front corner
GROUND_D = seat_space(DRIVER, (0.0, 0.0, 0.0))[1]                                  # the road under the bench, clip y
GROUND_T = seat_space(TAIL, (0.0, 0.0, 0.0))[1]                                    # and under the tailboard

# ================================================================== the reins

REIN_ARMS = {'right': 'right_arm', 'left': 'left_arm'}
REINS = list(lp.REINS)


def aim_flat(a, b, length0):
    """A prop track's turn and stretch for a strap modelled [length0] px along its +y from its pivot: from [a] to [b],
    its broad face kept up."""
    d = np.asarray(b, float) - np.asarray(a, float)
    n = float(np.linalg.norm(d))
    if n < 1e-6:
        return np.eye(3), (1.0, 1e-3, 1.0)
    y = d / n
    x = np.cross(y, np.array([0.0, 1.0, 0.0]))
    if np.linalg.norm(x) < 1e-6:
        x = np.array([1.0, 0.0, 0.0])
    x /= np.linalg.norm(x)
    z = np.cross(x, y)
    return np.column_stack([x, y, z]), (1.0, n / length0, 1.0)


class Reins:
    """A driving clip's reins: which fist holds each rein when ([held](side, t) -> arm), how far they sag at their
    middle ([sag](t), px) and any ripple running down them ([wave](t, s) -> px up, s 0 at the fist to 1 at the ring).
    install() reads the fists off the finished clip and lays each rein's pieces along the curve with prop tracks."""

    def __init__(self, c, sag, wave=None, held=None, loop=False):
        self.c, self.sag, self.wave, self.loop = c, sag, wave, loop
        self.held = held or (lambda side, t: REIN_ARMS[side])
        self.fists = None

    def install(self):
        c = self.c
        n = int(round(c.length * 2))
        self.fists = {arm: np.array([ta.fk(c, i / 2.0, arm, GRIP[arm]) for i in range(n + 1)]) for arm in ('right_arm', 'left_arm')}
        tracks = {}
        for side in ('right', 'left'):
            for i in range(lp.REIN_PIECES):
                tracks[f'rein_{side}_{i + 1}'] = (lambda t, pivot, side=side, i=i: self.piece(side, i, t))
        la.TRACKS[c.name] = tracks

    def fist(self, arm, t):
        tab = self.fists[arm]
        L_ = self.c.length
        if self.loop:
            t = t % L_
        x = min(max(t, 0.0), L_) * 2.0
        k = min(int(math.floor(x)), len(tab) - 2)
        f = x - k
        return tab[k] * (1 - f) + tab[k + 1] * f

    def points(self, side, t):
        a = self.fist(self.held(side, t), t)
        b = TERRET_D[side]
        sag = self.sag(t)
        out = []
        for k in range(lp.REIN_PIECES + 1):
            s = k / lp.REIN_PIECES
            p = a + (b - a) * s + np.array([0.0, sag * 4.0 * s * (1.0 - s), 0.0])
            if self.wave is not None:
                p = p + np.array([0.0, -self.wave(t, s) * math.sin(math.pi * s), 0.0])
            out.append(p)
        return out

    def piece(self, side, i, t):
        pts = self.points(side, t)
        Rm, S = aim_flat(pts[i], pts[i + 1], lp.REIN_LEN)
        return Rm, pts[i], S


def jolts(t, at, length, k=1.0):
    """The wagon's jolts over the road: a quick dip and a little rebound after each tick in [at], round a loop."""
    out = 0.0
    for a in at:
        dt = (t - a) % length
        if dt < 8.0:
            out += math.sin(math.pi * dt / 2.6) * math.exp(-dt / 1.9)
    return out * k

# ================================================================== the stations the previews draw

WOOD = (138, 100, 62)
WOOD_D = (104, 74, 46)
IRONC = (70, 70, 78)
ROAD = (128, 108, 80)
HORSE = (118, 70, 40)


def _bench_boxes():
    """The front of the wagon as the driver sees it: the bench, the box under it, the footboard, the brake lever (at
    rest), the right front wheel behind, and the horse's back and rings ahead (its rump, loins and saddle)."""
    S = lambda p: seat_space(DRIVER, p)
    b = []
    lo, hi = S((-wg.BENCH_X, wg.BENCH_TOP, wg.BENCH_FRONT)), S((wg.BENCH_X, wg.BENCH_TOP + wg.BENCH_T, wg.BENCH_BACK))
    b.append((lo, hi, WOOD, True))
    lo, hi = S((-wg.BOX_X, wg.SIDE_TOP + 0.2, wg.BOX_FRONT)), S((wg.BOX_X, wg.FLOOR_TOP, wg.BOX_FRONT + 1.0))
    b.append((lo, hi, WOOD_D, True))
    lo, hi = S((-wg.BOX_X, wg.SIDE_TOP + 0.2, wg.BOX_FRONT)), S((-wg.BOX_X + 1.0, wg.FLOOR_TOP + 2.0, wg.BOX_FRONT + 14.0))
    b.append((lo, hi, WOOD_D, True))
    # the footboard: drawn upright as a box over its raked face's height
    c = DASH_D
    b.append(((c[0] - 12.5, c[1] - 3.1, c[2] - 1.6), (c[0] + 12.5, c[1] + 3.1, c[2] - 0.4), WOOD, True))
    g = brake_grip(wg.BRAKE_REST)
    p = BRAKE_PIVOT_D
    for k in range(6):
        q = p + (g - p) * (k / 5.0)
        b.append(((q[0] - 0.5, q[1] - 1.3, q[2] - 0.5), (q[0] + 0.5, q[1] + 1.3, q[2] + 0.5), IRONC, True))
    w = S((-wg.WHEEL_X, -wg.RF, wg.ZF))
    b.append(((w[0] - 1.2, w[1] - wg.RF, w[2] - wg.RF), (w[0] + 1.2, w[1] + wg.RF, w[2] + wg.RF), WOOD_D, True))
    # the horse's back from the saddle to the croup, with the rings
    hb = [((-6.5, 29.4, -6.0), (6.5, 22.0, 6.5)), ((-6.8, 30.2, 6.5), (6.8, 22.0, 15.0))]
    for (x0, top, z0), (x1, bot, z1) in hb:
        lo_ = seat_space(DRIVER, horse_point((x0, 24.0 - top, z0)))
        hi_ = seat_space(DRIVER, horse_point((x1, 24.0 - bot, z1)))
        b.append((np.minimum(lo_, hi_), np.maximum(lo_, hi_), HORSE, True))
    for side, t_ in TERRET_D.items():
        b.append(((t_[0] - 0.7, t_[1] - 0.6, t_[2] - 0.4), (t_[0] + 0.7, t_[1] + 1.0, t_[2] + 0.4), (220, 180, 80), True))
    return b


def _tail_boxes():
    """The back of the wagon as the tail rider sees it: the tailboard let down, the floor and box sides behind."""
    S = lambda p: seat_space(TAIL, p)
    b = []
    lo, hi = S((-wg.BOX_X + 1.0, wg.FLOOR_TOP, wg.BOX_BACK)), S((wg.BOX_X - 1.0, wg.FLOOR_TOP + 1.2, wg.BOX_BACK + wg.TAIL_LEN))
    b.append((np.minimum(lo, hi), np.maximum(lo, hi), WOOD, True))
    lo, hi = S((-wg.BOX_X, wg.FLOOR_TOP, wg.BOX_BACK - 24.0)), S((wg.BOX_X, wg.FLOOR_TOP + 1.2, wg.BOX_BACK))
    b.append((np.minimum(lo, hi), np.maximum(lo, hi), WOOD_D, True))
    for s in (-1, 1):
        lo, hi = S((s * wg.BOX_X, wg.SIDE_TOP, wg.BOX_BACK - 24.0)), S((s * (wg.BOX_X - 1.0), wg.FLOOR_TOP, wg.BOX_BACK))
        b.append((np.minimum(lo, hi), np.maximum(lo, hi), WOOD_D, True))
    return b


STATION_BOXES = {'wagon_bench': _bench_boxes, 'wagon_tail': _tail_boxes}
STATION_GROUND = {'wagon_bench': GROUND_D, 'wagon_tail': GROUND_T}

# ================================================================== on the bench

DRIVE_LEN = 40
DRIVE_JOLTS = (6.0, 17.5, 25.0, 33.0)
# the soles braced on the footboard's back face (it leans; they meet it a hair short), a little apart
DRIVE_FEET = {'right_leg': np.array([-2.7, 24.8, -11.55]), 'left_leg': np.array([2.7, 24.8, -11.55])}
# where a fist holds its rein over the thigh, in the torso's own space (the left mirrored)
DRIVE_FIST = np.array([-2.1, 7.3, -7.4])
DRIVE_ROOT = np.array([0.0, SEAT_DROP, 0.0])


def fist_world(fr, side, p=DRIVE_FIST, follow=0.65):
    """A fist that rides with the torso [follow] of the way, the rest held still (a driver's hands give with the
    wagon's rocking to keep an even feel of the horse's mouth)."""
    s = 1.0 if side == 'right_arm' else -1.0
    pt = np.asarray(p, float) * np.array([s, 1.0, 1.0])
    still = pt + np.array([0.0, SEAT_DROP, 0.0])
    return torso_to_world(fr, pt) * follow + still * (1.0 - follow)


PALM_IN = {'right_arm': L, 'left_arm': R}


def key_from(c, t, src, ts):
    """Keys every bone [src] keys, at [t], as [src] has it at [ts] (a clip that starts or ends on another's frame)."""
    for bone in src.keys:
        q, p = src.sample(bone, ts)
        c.key(bone, t, q, pos=p if src.has_pos(bone) else None, ease='linear')


def blend_frames(a, b, w):
    """A frame [w] of the way from frame [a] to frame [b]."""
    return F_(qt=qslerp(a['qt'], b['qt'], w), vt=a['vt'] + (b['vt'] - a['vt']) * w,
              pr=a['pr'] + (b['pr'] - a['pr']) * w, qr=qslerp(a['qr'], b['qr'], w))


def bench_legs(c, t, fr, feet=DRIVE_FEET, ease='linear'):
    for side in ('right_leg', 'left_leg'):
        la.leg_to(c, t, side, fr['pr'], feet[side], ease=ease)


def drive_pose(t):
    """drive at [t]: (frame, head (pitch, yaw, roll), jolt)."""
    ph = 2.0 * math.pi * t / DRIVE_LEN
    j = jolts(t, DRIVE_JOLTS, DRIVE_LEN)
    sway = math.sin(ph)
    pitch = 6.0 + 1.6 * j + 0.8 * math.sin(2 * ph + 0.6)
    roll = 2.6 * sway + 0.5 * math.sin(3 * ph)
    yaw = 1.2 * math.sin(ph + 1.1)
    fr = F_(qt=bend(pitch, yaw, roll), vt=(0.0, 0.35 * j, 0.0), pr=DRIVE_ROOT)
    look = la.K([(0, 0.0), (9.0, 0.0), (14.0, -26.0), (19.0, -22.0), (23.0, 0.0), (29.0, 2.0), (32.0, 12.0),
                 (35.0, 9.0), (38.0, 0.0), (40, 0.0)], t, loop=DRIVE_LEN)
    head = (4.0 + 2.5 * jolts(t - 0.6, DRIVE_JOLTS, DRIVE_LEN), look, -0.6 * roll)
    return fr, head, j


def drive_sag(t):
    return 1.0 + 0.45 * jolts(t - 0.8, DRIVE_JOLTS, DRIVE_LEN)


def build_drive():
    c = la.clip('drive', DRIVE_LEN, True, look=('farmer', 1), station_='wagon_bench', tools_=REINS,
                views=((32, -12), (90, -2), (200, -20)),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    arms = la.Arms()
    for t in la.every(DRIVE_LEN):
        fr, head, j = drive_pose(t)
        key_frame(c, t, fr, ease='linear')
        bench_legs(c, t, fr)
        for side in ('right_arm', 'left_arm'):
            p = fist_world(fr, side) + np.array([0.0, 0.3 * jolts(t - 1.2, DRIVE_JOLTS, DRIVE_LEN), 0.0])
            c.key(side, t, arms.reach(side, fr, p, palm=PALM_IN[side], palm_w=0.3), ease='linear')
        c.key('head', t, qeuler(x=head[0], y=head[1], z=head[2]), ease='linear')
    c.mode('head', 'add')
    ta.seal(c)
    reins = Reins(c, drive_sag, loop=True)
    for side in ('right_leg', 'left_leg'):
        check(c, f'{side} sole on the footboard (t=0)', (float(np.dot(la.leg_end(c, 0.0, side) - DASH_D, DASH_N)),), (0.0,), tol=0.4)
    return c, reins


# ---- drive_urge (20): the reins gathered, then flicked down the horse's back with a click of the tongue; the wagon
# lurches as the horse leans into the collar, and back to driving
URGE_LEN = 20
URGE_CLICK, URGE_FLICK = 3.0, 5.0
URGE_PITCH = [(0, 6.0), (3.0, 3.0), (5.0, 11.0), (7.0, 8.0), (9.5, -3.0), (12.0, 2.0), (15.5, 6.5), (20, 6.0)]
URGE_HEAD = [(0, 4.0), (2.5, -4.0), (4.0, -2.0), (5.5, 9.0), (8.0, 2.0), (10.0, -3.0), (14.0, 3.0), (20, 4.0)]
# the right fist's path in the torso's space (the left mirrored): gathered up and back, thrown forward and down, back
URGE_FIST = [(0, DRIVE_FIST), (3.0, (-2.0, 3.4, -5.2)), (4.2, (-2.1, 3.2, -5.8)), (5.6, (-2.4, 9.4, -11.8)),
             (7.5, (-2.3, 8.6, -10.2)), (11.0, (-2.1, 7.6, -8.2)), (20, DRIVE_FIST)]


def urge_sag(t):
    return la.K([(0, 1.0), (2.5, 0.6), (4.0, 0.4), (5.5, 2.6), (7.0, 3.6), (8.5, 2.4), (10.0, 1.9), (13.0, 1.2), (20, 1.0)], t)


def urge_wave(t, s):
    """The flick: a ripple thrown from the hands at 4.2 that runs down the reins to the horse's back by 6.4."""
    if t < 4.0 or t > 7.4:
        return 0.0
    mid = (t - 4.2) / 2.2
    amp = 3.4 * smooth((t - 4.0) / 0.6) * (1.0 - smooth((t - 6.4) / 1.0))
    return amp * math.exp(-((s - mid) / 0.24) ** 2)


def build_drive_urge():
    c = la.clip('drive_urge', URGE_LEN, False, look=('farmer', 1), station_='wagon_bench', tools_=REINS,
                events=[('click', URGE_CLICK), ('flick', URGE_FLICK)], views=((32, -12), (90, -2), (200, -20)),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    arms = la.Arms()
    drive = la.getc('drive')
    fr0, head0, _ = drive_pose(0.0)
    for t in la.every(URGE_LEN):
        if t <= 1e-9 or t >= URGE_LEN - 1e-9:
            key_from(c, t, drive, 0.0)
            continue
        pitch = la.K(URGE_PITCH, t)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, 0.0, 0.0), pr=DRIVE_ROOT)
        w0 = 1.0 - smooth(t / 3.0) + smooth((t - 15.0) / 5.0)
        fr = blend_frames(fr, fr0, max(0.0, min(1.0, w0)))
        key_frame(c, t, fr, ease='linear')
        bench_legs(c, t, fr)
        fist = la.KV(URGE_FIST, t)
        for side in ('right_arm', 'left_arm'):
            p = fist_world(fr, side, fist, follow=0.65 if t < 2 or t > 16 else 0.9)
            c.key(side, t, arms.reach(side, fr, p, palm=PALM_IN[side], palm_w=0.3), ease='linear')
        hp = la.K(URGE_HEAD, t)
        wh = max(0.0, min(1.0, w0))
        c.key('head', t, qeuler(x=hp + (head0[0] - hp) * wh, y=head0[1] * wh, z=head0[2] * wh), ease='linear')
    c.mode('head', 'add')
    reins = Reins(c, urge_sag, wave=urge_wave)
    la.join('drive_urge starts on drive', c, 0, la.getc('drive'), 0, tol=0.05)
    la.join('drive_urge ends on drive', c, URGE_LEN, la.getc('drive'), 0, tol=0.05)
    return c, reins


# ---- drive_brake (30): the right rein passed into the left hand, which hauls back on both; the right hand reaches back
# for the brake lever and leans on it till it's hard on (it stays on); the wagon pulls up, pitching the driver
# forward; the right hand comes back and takes its rein
BRAKE_LEN = 30
BRAKE_SWAP = (3.0, 25.0)            # the right rein in the left fist from the first to the second
BRAKE_GRAB, BRAKE_DONE, BRAKE_LET_GO = 8.0, 14.0, 21.0
BRAKE_PITCH = [(0, 6.0), (3.0, 4.0), (7.0, -6.0), (11.0, -9.0), (14.0, -7.0), (16.0, 4.0), (18.5, 7.0), (21.0, 1.0),
               (25.0, 3.0), (30, 6.0)]
BRAKE_YAW = [(0, 0.0), (3.0, 4.0), (7.0, 16.0), (10.0, 14.0), (14.0, 6.0), (21.0, 5.0), (25.0, 2.0), (30, 0.0)]
BRAKE_ROLL = [(0, 0.0), (3.0, 0.0), (7.0, -5.0), (12.0, -9.0), (14.0, -8.0), (21.0, -6.0), (25.0, -1.0), (30, 0.0)]
BRAKE_HEAD = [(0, (4.0, 0.0)), (4.0, (6.0, 10.0)), (7.0, (10.0, 34.0)), (9.0, (6.0, 28.0)), (12.0, (-6.0, 0.0)),
              (16.0, (-4.0, 0.0)), (19.0, (6.0, 0.0)), (23.0, (8.0, 18.0)), (26.0, (2.0, 0.0)), (30, (4.0, 0.0))]
# the left fist, in the torso's space: the reins taken in it together, hauled back to the chest, eased
BRAKE_LEFT = [(0, DRIVE_FIST * np.array([-1, 1, 1])), (3.0, (0.6, 6.8, -7.0)), (7.0, (1.2, 4.4, -4.6)),
              (14.0, (1.2, 3.8, -4.2)), (19.0, (1.4, 4.6, -5.0)), (25.0, (0.5, 6.8, -7.0)), (30, DRIVE_FIST * np.array([-1, 1, 1]))]


def brake_lever_lean(t):
    """The lever's lean through drive_brake: at rest till the hand takes it, hauled on, and left there."""
    return wg.BRAKE_REST + (wg.BRAKE_ON - wg.BRAKE_REST) * smooth((t - BRAKE_GRAB) / (BRAKE_DONE - BRAKE_GRAB))


def brake_sag(t):
    return la.K([(0, 1.0), (3.0, 0.8), (7.0, 0.0), (19.0, 0.0), (22.0, 0.6), (30, 1.0)], t)


def build_drive_brake():
    c = la.clip('drive_brake', BRAKE_LEN, False, look=('farmer', 1), station_='wagon_bench', tools_=REINS,
                events=[('haul', 6.0), ('brake', 11.0)], views=((32, -12), (90, -2), (-110, -12)),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    drive = la.getc('drive')
    lever_ticks = [t for t in la.every(BRAKE_LEN) if BRAKE_GRAB - 1e-9 <= t <= BRAKE_LET_GO + 1e-9]
    # first the ticks the right hand is on the lever: the whole body solved to put it there, the left fist hauling
    solved, prev = {}, None
    for t in lever_ticks:
        grip = brake_grip(brake_lever_lean(t))
        lhs = la.KV(BRAKE_LEFT, t)

        def res(fr, qa, qb, grip=grip, lhs=lhs):
            out = list((hand_point('right_arm', qa, fr) - grip) * 3.0)
            out += list((hand_point('left_arm', qb, fr) - torso_to_world(fr, lhs)) * 1.5)
            return out
        fr, qa, qb, prev = body_solve(res, la.K(BRAKE_PITCH, t), la.K(BRAKE_YAW, t), (0.0, 0.0, 0.0),
                                      roll0=la.K(BRAKE_ROLL, t), guess=prev, root_w=8.0, pitch_w=0.12, yaw_w=0.08,
                                      roll_w=0.12, sink_w=2.0, drop=SEAT_DROP)
        solved[t] = (fr, qa, qb)
    rsol, lsol = la.Arms(), la.Arms()
    for t in la.every(BRAKE_LEN):
        if t <= 1e-9 or t >= BRAKE_LEN - 1e-9:
            key_from(c, t, drive, 0.0)
            continue
        lfist_t = la.KV(BRAKE_LEFT, t)
        meet = lfist_t + np.array([-1.3, 0.3, 0.2])          # the right fist beside the left (torso space)
        free_fr = F_(qt=bend(la.K(BRAKE_PITCH, t), la.K(BRAKE_YAW, t), la.K(BRAKE_ROLL, t)), pr=DRIVE_ROOT)
        if t in solved:
            fr, qa, qb = solved[t]
            rsol.prev['right_arm'], lsol.prev['left_arm'] = qa, qb
        else:
            if t <= BRAKE_SWAP[0]:
                fr = free_fr
                target = torso_to_world(fr, DRIVE_FIST + (meet - DRIVE_FIST) * smooth(t / BRAKE_SWAP[0]))
            elif t < BRAKE_GRAB:
                w = smooth((t - BRAKE_SWAP[0]) / (BRAKE_GRAB - BRAKE_SWAP[0]))
                fr = blend_frames(free_fr, solved[BRAKE_GRAB][0], w)
                target = torso_to_world(fr, meet) * (1 - w) + brake_grip(wg.BRAKE_REST) * w + np.array([0.0, -2.0 * math.sin(math.pi * w), 0.0])
            elif t < BRAKE_SWAP[1]:
                w = smooth((t - BRAKE_LET_GO) / (BRAKE_SWAP[1] - BRAKE_LET_GO))
                fr = blend_frames(solved[BRAKE_LET_GO][0], free_fr, w)
                target = brake_grip(wg.BRAKE_ON) * (1 - w) + torso_to_world(fr, meet) * w + np.array([0.0, -1.2 * math.sin(math.pi * w), 0.0])
            else:
                fr = free_fr
                w = smooth((t - BRAKE_SWAP[1]) / (BRAKE_LEN - BRAKE_SWAP[1]))
                target = torso_to_world(fr, meet + (DRIVE_FIST - meet) * w)
            qa = rsol.reach('right_arm', fr, target, palm=L, palm_w=0.3)
            qb = lsol.reach('left_arm', fr, torso_to_world(fr, lfist_t), palm=R, palm_w=0.3)
        key_frame(c, t, fr, ease='linear')
        bench_legs(c, t, fr)
        c.key('right_arm', t, qa, ease='linear')
        c.key('left_arm', t, qb, ease='linear')
        hp, hy = la.KV([(k, (p[0], p[1], 0.0)) for k, p in BRAKE_HEAD], t)[:2]
        c.key('head', t, qeuler(x=hp, y=hy), ease='linear')
    c.mode('head', 'add')

    def held(side, t):
        return 'left_arm' if side == 'right' and BRAKE_SWAP[0] <= t < BRAKE_SWAP[1] else REIN_ARMS[side]
    reins = Reins(c, brake_sag, held=held)
    for k in (BRAKE_GRAB, 11.0, BRAKE_DONE, 18.0, BRAKE_LET_GO):
        check(c, f'right hand on the brake lever (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), brake_grip(brake_lever_lean(k)), tol=0.6)
    for k in BRAKE_SWAP:
        check(c, f'the hands together as the right rein changes hands (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']),
              ta.fk(c, k, 'left_arm', GRIP['left_arm']), tol=1.6)
    la.join('drive_brake starts on drive', c, 0, drive, 0, tol=0.05)
    la.join('drive_brake ends on drive', c, BRAKE_LEN, drive, 0, tol=0.05)
    return c, reins

# ================================================================== on the tailboard

RIDE_LEN = 60
RIDE_JOLTS = (5.0, 13.0, 24.5, 31.0, 44.0, 52.5)
RIDE_SPEAR_Z = -3.9                 # where the spear lies across the thighs (clip z)
RIDE_FIST_X = {'right_arm': -2.6, 'left_arm': 4.6}
# the spear lying level across the lap, its head out to the left: part -y to the left, its blade's flat up
SPEAR_ACROSS = np.column_stack([(0.0, 0.0, 1.0), (-1.0, 0.0, 0.0), (0.0, -1.0, 0.0)])


def ride_kicks(t):
    """The legs' swing (their pitch, degrees: 0 hanging straight down, -90 straight out ahead) at [t]."""
    ph = 2.0 * math.pi * t / RIDE_LEN
    j = jolts(t, RIDE_JOLTS, RIDE_LEN)
    kr = -58.0 + 12.0 * math.sin(2 * ph) + 3.5 * math.sin(4 * ph + 1.0) - 3.0 * j
    kl = -58.0 + 12.0 * math.sin(2 * ph + math.pi * 0.8) + 3.5 * math.sin(4 * ph + 2.4) - 3.0 * j
    return kr, kl


def thigh_top(kick, z, hip_y=12.0 + SEAT_DROP):
    """The top of a leg swung [kick] degrees, at clip [z] (its front face, as it runs down from the hip)."""
    a = math.radians(kick)
    y0, z0 = 2.0 * math.sin(a), -2.0 * math.cos(a)        # the front face's top edge, from the hip
    dy, dz = math.cos(a), math.sin(a)                     # down the leg
    s_ = (z - z0) / dz
    return hip_y + y0 + s_ * dy


def arm_reach(side, fr):
    """An arm's shoulder joint under [fr] and how far its grip is from it."""
    _, joint = ta.chain_frame(side, **fr)
    return joint, float(np.linalg.norm(GRIP[side]))


def spear_fist(fr, kr, kl):
    """Where the right fist holds the spear on the thighs: as far down the higher thigh as the arm reaches."""
    joint, n = arm_reach('right_arm', fr)
    x = RIDE_FIST_X['right_arm']

    def at(z):
        return np.array([x, min(thigh_top(kr, z), thigh_top(kl, z)) - 0.6, z])
    lo, hi = -10.0, -2.5                    # forward of the knees' tops, and back near the hips
    if np.linalg.norm(at(hi) - joint) >= n:
        return at(hi)
    for _ in range(40):
        mid = (lo + hi) / 2
        if np.linalg.norm(at(mid) - joint) > n:
            lo = mid
        else:
            hi = mid
    return at((lo + hi) / 2)


def shaft_hand(fr, fist):
    """Where the left hand rests on the spear's shaft (level along x through the right fist): as near its own thigh
    as the arm lets it."""
    joint, n = arm_reach('left_arm', fr)
    r2 = n * n - (fist[1] - joint[1]) ** 2 - (fist[2] - joint[2]) ** 2
    want = RIDE_FIST_X['left_arm']
    if r2 <= 0:
        return np.array([joint[0], fist[1], fist[2]])
    xs = (joint[0] - math.sqrt(r2), joint[0] + math.sqrt(r2))
    return np.array([min(xs, key=lambda x: abs(x - want)), fist[1], fist[2]])


def build_ride_tail():
    c = la.clip('ride_tail', RIDE_LEN, True, look=('guard', 1), station_='wagon_tail', tools_=('spear_knees',),
                views=((32, -12), (90, -2), (160, -16)),
                traces=[('right_leg', (0.0, 12.0, 0.0), (255, 200, 120)), ('left_leg', (0.0, 12.0, 0.0), (200, 230, 255))])
    arms = la.Arms()
    ROOT_T = np.array([0.0, SEAT_DROP, 0.0])
    FISTS = {}
    for t in la.every(RIDE_LEN):
        ph = 2.0 * math.pi * t / RIDE_LEN
        j = jolts(t, RIDE_JOLTS, RIDE_LEN)
        pitch = 11.0 + 1.8 * j + 1.0 * math.sin(2 * ph)
        roll = 2.2 * math.sin(ph + 0.4) + 0.6 * math.sin(3 * ph)
        fr = F_(qt=bend(pitch, 1.5 * math.sin(ph), roll), vt=(0.0, 0.3 * j, 0.0), pr=ROOT_T)
        key_frame(c, t, fr, ease='linear')
        kr, kl = ride_kicks(t)
        c.key('right_leg', t, qeuler(x=kr, y=4.0, z=3.0 + 1.5 * math.sin(ph)), ease='linear')
        c.key('left_leg', t, qeuler(x=kl, y=-4.0, z=-3.0 - 1.5 * math.sin(ph + 1.0)), ease='linear')
        # the spear rides on whichever thigh is higher under it
        qa = arms.reach('right_arm', fr, spear_fist(fr, kr, kl), palm=D, palm_w=0.3)
        got = hand_point('right_arm', qa, fr)
        qb = arms.reach('left_arm', fr, shaft_hand(fr, got), palm=D, palm_w=0.4)
        FISTS[t] = got
        c.key('right_arm', t, qa, ease='linear')
        c.key('left_arm', t, qb, ease='linear')
        # watching the road behind: a look to one side, back, to the other, now and then a glance up the tilt's side
        look = la.K([(0, 0.0), (8.0, 0.0), (14.0, -30.0), (21.0, -26.0), (26.0, 0.0), (33.0, 4.0), (38.0, 34.0),
                     (46.0, 30.0), (51.0, 0.0), (60, 0.0)], t, loop=RIDE_LEN)
        tilt = la.K([(0, 2.0), (14.0, -2.0), (21.0, 6.0), (30.0, 2.0), (40.0, -4.0), (48.0, 4.0), (60, 2.0)], t, loop=RIDE_LEN)
        c.key('head', t, qeuler(x=tilt + 2.0 * jolts(t - 0.6, RIDE_JOLTS, RIDE_LEN), y=look, z=-0.5 * roll), ease='linear')
    c.mode('head', 'add')
    ta.seal(c)
    la.TRACKS['ride_tail'] = {'spear_knees': lambda t, pivot: (SPEAR_ACROSS, None, None)}
    for k in (0.0, 15.0, 30.0, 45.0):
        kr, kl = ride_kicks(k)
        rf = ta.fk(c, k, 'right_arm', GRIP['right_arm'])
        check(c, f'the spear on the higher thigh (t={k:g}: px above it)',
              (min(thigh_top(kr, rf[2]), thigh_top(kl, rf[2])) - rf[1],), (0.6,), tol=0.3)
        check(c, f'left hand on the shaft (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm'])[1:], rf[1:], tol=0.4)
    return c

# ================================================================== helpers for clips that move the whole body about


def yaw_frame(pitch, yaw_t, roll, pr, yaw_r, vt=(0.0, 0.0, 0.0)):
    """A frame with the torso bent (pitch, yaw, roll) on a root moved to [pr] and turned [yaw_r] degrees (to the
    right: +)."""
    return F_(qt=bend(pitch, yaw_t, roll), vt=vt, pr=np.asarray(pr, float), qr=qeuler(y=yaw_r))


def hang(side, fr, out=None, fwd=0.0):
    """An arm hanging straight down in the world, [out] degrees out to the body's side and [fwd] forward of its facing,
    under [fr] (its root turned about the vertical only): living_anims' hang under the torso's bend alone, since under
    the turned root itself it would twist the arm about its own length by the turn."""
    return la.hang(side, F_(qt=fr['qt']), out=out, fwd=fwd)


def hip_world(side, fr):
    return HIPS + qmat(fr['qr']) @ (np.array(PIVOTS[side], float) - HIPS) + fr['pr']


def leg_dir_point(side, fr, pitch, splay):
    """Where a free leg's sole is, the leg swung [pitch] degrees (- forward) and [splay] out, in the root's frame."""
    s = -1.0 if side == 'right_leg' else 1.0
    d_local = qmat(qeuler(x=pitch, z=-s * splay)) @ np.array([0.0, 1.0, 0.0])
    return hip_world(side, fr) + qmat(fr['qr']) @ d_local * 12.0


def legs_to(c, t, fr, feet, toe=None):
    """Both legs to world soles [feet] (side -> point), under a root that turns as well as moves; the toes ahead of the
    root, or [toe] (side -> world direction)."""
    for side, p in feet.items():
        tw = (toe or {}).get(side)
        if tw is None:
            tw = qmat(fr['qr']) @ F
        la.leg_to_r(c, t, side, fr, p, toe=tw)


def spring(keys, t):
    """A number along [keys] that eases through them (a spline with its speed carried through the keys)."""
    return float(ta.spline_keys(keys, t))


def path3(keys, t):
    return np.asarray(ta.spline_keys(keys, t), float)


def idle_from(fr_pr, yaw):
    """The 4x4 that takes a pose standing at root [fr_pr] turned [yaw] back to idle's place (for joins)."""
    return T(*HIPS) @ Rq(qeuler(y=-yaw)) @ T(*(-(np.asarray(fr_pr, float) + HIPS)))


def maybe_join(label, a, ta_, b_name, tb, M=None, tol=0.6):
    """A join to a clip living_anims' full run builds (the quick previews leave it out)."""
    try:
        b = la.getc(b_name)
    except StopIteration:
        return
    la.join(label, a, ta_, b, tb, M=M, tol=tol)


def tools_when(spans):
    """INFO 'tools_at' from spans (part, from, to: None for on to the end)."""
    def fn(t):
        return {p for p, a, b in spans if a <= t + 1e-9 and (b is None or t < b - 1e-9)}
    return fn

# ================================================================== getting down off the wagon, and back up

BENCH_END_X = seat_space(DRIVER, (-wg.BENCH_X, 0.0, 0.0))[0]          # -6.5: the bench's right end (clip x)
BOX_SIDE_X = seat_space(DRIVER, (-wg.BOX_X, 0.0, 0.0))[0]              # -4.5: the box's right side, its outer face
SIDE_TOP_Y = seat_space(DRIVER, (0.0, wg.SIDE_TOP, 0.0))[1]            # 25.5: the top of the box's sides
HUB_STEP = seat_space(DRIVER, (-(wg.WHEEL_X + 1.2), -wg.RF - 1.9, wg.ZF))   # a sole on top of the front wheel's hub

# ---- jump_down (16): from the bench (drive's first frame) the reins let go, a turn to the right with the legs swung
# over the bench's end, hands pushing off (5), a drop to the road and a landing on bent knees (10), up straight,
# standing facing out from the wagon's right side
JD_LEN = 16
JD_OFF, JD_LAND = 5.0, 10.0
JD_TURN = 90.0
JD_FEET = np.array([-14.0, GROUND_D, -1.0])                            # the middle between the feet at the end
JD_PR_END = JD_FEET - np.array([0.0, 24.0, 0.0])
JD_YAW = [(0, 0.0), (1.0, 0.0), (2.5, 24.0), (4.0, 62.0), (5.0, 80.0), (7.0, 90.0), (16, 90.0)]
JD_PITCH = [(0, 6.4), (2.0, 4.0), (4.0, 14.0), (5.0, 18.0), (7.0, 6.0), (9.5, 4.0), (10.6, 22.0), (12.0, 17.0),
            (14.0, 4.0), (16, 0.0)]
JD_TYAW = [(0, 1.1), (2.0, -8.0), (4.0, -12.0), (6.0, -2.0), (8.0, 0.0), (16, 0.0)]
JD_ROLL = [(0, 0.0), (2.5, -6.0), (4.5, -10.0), (5.5, -4.0), (6.5, -5.0), (8.5, -3.0), (10.0, 0.0), (16, 0.0)]
JD_ON_BENCH = {'right_arm': np.array([-4.2, 23.9, 3.0]), 'left_arm': np.array([-1.0, 23.4, -0.8])}


def jd_root(t):
    if t <= JD_OFF:
        return path3([(0, (0.0, SEAT_DROP, 0.0)), (1.5, (0.0, SEAT_DROP, 0.0)), (3.0, (-0.8, SEAT_DROP, 0.3)),
                      (4.0, (-2.0, SEAT_DROP - 0.1, 0.5)), (JD_OFF, (-3.6, SEAT_DROP - 0.4, 0.4))], t)
    if t <= JD_LAND:
        u = t - JD_OFF
        f = u / (JD_LAND - JD_OFF)
        return np.array([-3.6 + (JD_PR_END[0] + 0.4 + 3.6) * f, SEAT_DROP - 0.4 - 0.8 * u + 1.0 * u * u, 0.4 + (JD_PR_END[2] - 0.4) * f])
    dip = la.K([(JD_LAND, 2.5), (11.0, 4.1), (12.5, 2.5), (14.0, 0.7), (16, 0.0)], t)
    x = la.K([(JD_LAND, JD_PR_END[0] + 0.4), (12.0, JD_PR_END[0]), (16, JD_PR_END[0])], t)
    return np.array([x, JD_PR_END[1] + dip, JD_PR_END[2]])


def jd_frame(t):
    return yaw_frame(la.K(JD_PITCH, t), la.K(JD_TYAW, t), la.K(JD_ROLL, t), jd_root(t), la.K(JD_YAW, t),
                     vt=(0.0, sink_for(la.K(JD_PITCH, t)), 0.0))


def jd_end_feet():
    fr = yaw_frame(0.0, 0.0, 0.0, JD_PR_END, JD_TURN)
    return {side: hip_world(side, fr) + np.array([0.0, 12.0, 0.0]) for side in ('right_leg', 'left_leg')}


def build_jump_down():
    c = la.clip('jump_down', JD_LEN, False, look=('farmer', 1), station_='wagon_bench', tools_=REINS,
                events=[('push', JD_OFF), ('land', JD_LAND)], views=((32, -12), (90, -2), (-60, -14)),
                traces=[('body', (0.0, 12.0, 0.0), (255, 200, 120))])
    c_info = ta.INFO['jump_down']
    c_info['tools_at'] = tools_when([(p, 0.0, 1.5) for p in REINS])
    drive = la.getc('drive')
    fr0, head0, _ = drive_pose(0.0)
    arms = la.Holds()
    end_feet = jd_end_feet()
    for t in la.every(JD_LEN):
        if t <= 1e-9:
            key_from(c, t, drive, 0.0)
            arms.prev['right_arm'] = drive.sample('right_arm', 0.0)[0]
            arms.prev['left_arm'] = drive.sample('left_arm', 0.0)[0]
            last = {s_: (arms.prev[s_], np.zeros(3)) for s_ in ('right_arm', 'left_arm')}
            continue
        fr = jd_frame(t)
        if t < 2.0:
            fr = blend_frames(fr0, fr, smooth(t / 2.0))
        key_frame(c, t, fr, ease='linear')
        # the legs: braced on the footboard, swung round over the bench's end, dropped, reaching for the road, planted
        if t <= 1.5:
            legs_to(c, t, fr, {s: DRIVE_FEET[s] for s in ('right_leg', 'left_leg')})
        elif t < JD_LAND:
            p_ = la.K([(1.5, -74.0), (3.0, -84.0), (4.5, -80.0), (6.0, -42.0), (8.0, -16.0), (9.5, -7.0), (JD_LAND, -4.0)], t)
            sp = la.K([(1.5, 3.0), (3.0, 4.0), (6.0, 6.0), (8.0, 8.0), (JD_LAND, 4.0)], t)
            feet = {s: leg_dir_point(s, fr, p_, sp) for s in ('right_leg', 'left_leg')}
            if t > 9.0:
                w = smooth((t - 9.0) / (JD_LAND - 9.0))
                feet = {s: feet[s] * (1 - w) + end_feet[s] * w for s in feet}
            legs_to(c, t, fr, feet)
        else:
            legs_to(c, t, fr, end_feet)
        # the arms: off the reins, both hands on the bench pushing off, out for balance in the air, braced, hanging
        if t <= 4.8:
            w = smooth((t - 0.0) / 3.0)
            for side in ('right_arm', 'left_arm'):
                held = fist_world(fr0, side)
                lift = np.array([0.0, -2.5 * math.sin(math.pi * min(1.0, t / 3.0)), 0.0])
                p = held * (1 - w) + JD_ON_BENCH[side] * w + lift * (1 - w) * 0.6
                q, off = arms.slack(side, fr, [(GRIP[side], p, 1.0)], [((0, 0, -1), D if t > 2.5 else PALM_IN[side], 0.2)],
                                    slack=0.4, limit=1.6)
                last[side] = (q, off)
                la.key_arm(c, side, t, (q, off))
        else:
            # free: out and up in the air, forward and down to brace on landing, to hanging
            out = la.K([(4.8, 25.0), (6.0, 48.0), (8.0, 62.0), (9.5, 50.0), (10.5, 30.0), (12.0, 18.0), (14.0, 8.0), (16, 4.4)], t)
            fwd = la.K([(4.8, 30.0), (6.0, 40.0), (8.0, 30.0), (9.5, 26.0), (10.5, 42.0), (12.0, 30.0), (14.0, 8.0), (16, 0.0)], t)
            for side in ('right_arm', 'left_arm'):
                q = hang(side, fr, out=out, fwd=fwd)
                w = smooth((t - 4.8) / 1.2)
                la.key_arm(c, side, t, la.mix_arm(last[side], (q, np.zeros(3)), w))
        # the head: down to the road at the landing spot, the jolt of the landing, up
        hp = la.K([(0, head0[0]), (2.0, 18.0), (5.0, 28.0), (8.0, 20.0), (10.0, 12.0), (11.0, 20.0), (13.0, 6.0), (16, 0.0)], t)
        hy = la.K([(0, head0[1]), (2.0, 34.0), (4.5, 20.0), (7.0, 4.0), (16, 0.0)], t)
        c.key('head', t, qeuler(x=hp, y=hy, z=0.0), ease='linear')
    c.mode('head', 'add')
    reins = Reins(c, lambda t: 1.0 + 3.0 * smooth(t / 1.5))
    for side, p in jd_end_feet().items():
        check(c, f'{side} on the road (t=10)', la.leg_end(c, JD_LAND, side), p, tol=0.4)
        check(c, f'{side} on the road (t=16)', la.leg_end(c, JD_LEN, side), p, tol=0.3)
    for side, p in JD_ON_BENCH.items():
        check(c, f'{side} on the bench pushing off (t=4.5)', ta.fk(c, 4.5, side, GRIP[side]), p, tol=1.0)
    la.join('jump_down starts on drive', c, 0, drive, 0, tol=0.05)
    maybe_join('jump_down ends on idle (on the road)', c, JD_LEN, 'idle', 0, M=idle_from(JD_PR_END, JD_TURN), tol=1.2)
    return c, reins


# ---- climb_up (20): from the road by the front wheel facing the wagon's side: hands up onto the bench's end and the top
# of the box's side (3), the left foot up onto the wheel's hub (4) and a spring off it, hauled up and pressed up over
# the hands (the shoulders round them on straight arms, 4 to 8.5), the right leg over onto the bench and sat on its end
# (10), a turn to face ahead sliding along to the seat (14.5), the feet onto the footboard and the reins taken up (17):
# drive's first frame
CU_LEN = 20
CU_TURN = -90.0                                                         # facing the box's side
CU_GRAB, CU_STEP, CU_SIT, CU_REINS = 3.0, 4.0, 13.0, 17.0
CU_RIGHT_HOLD = np.array([BENCH_END_X + 0.9, 23.9, 4.6])               # the bench's end, near its back corner
CU_LEFT_HOLD = np.array([BOX_SIDE_X + 0.5, SIDE_TOP_Y - 0.1, 13.0])    # the top edge of the box's side
CU_H = (CU_RIGHT_HOLD + CU_LEFT_HOLD) / 2.0                             # the hands' middle while they hold
CU_FEET = np.array([-14.2, GROUND_D, CU_H[2]])
CU_PR_START = CU_FEET - np.array([0.0, 24.0, 0.0])
CU_ARM = 9.9
# the arms' angle under the level (- up to the holds, + down onto them) and the torso's lean, through the haul
CU_PHI = [(2.5, -29.5), (4.0, -18.0), (5.0, 0.0), (6.0, 24.0), (7.0, 48.0), (8.0, 70.0), (8.5, 82.0)]
CU_LEAN = [(0, 0.0), (2.5, 4.0), (4.0, 8.0), (6.0, 24.0), (8.0, 40.0), (8.5, 44.0), (10.0, 18.0), (12.5, 8.0), (14.5, 6.0),
           (20, 6.4)]
CU_YAW = [(0, CU_TURN), (8.5, CU_TURN), (9.3, -84.0), (10.0, -70.0), (12.5, -24.0), (14.5, 0.0), (20, 0.0)]
CU_TYAW = [(0, 0.0), (9.0, 0.0), (10.0, 10.0), (12.5, 6.0), (14.5, 0.0), (20, 1.1)]


def cu_hips(t):
    """Where the hips are at [t] (world): stood under the holds, then the shoulders swung round them on straight
    arms, then onto the bench's end and along to the seat."""
    p = la.K(CU_LEAN, t)
    if t <= 2.5:
        return CU_PR_START + HIPS + np.array([0.1 * smooth(t / 2.5), -0.2 * smooth(t / 2.5), 0.0])
    if t <= 8.5:
        phi = math.radians(la.K(CU_PHI, t))
        s = CU_H + np.array([-CU_ARM * math.cos(phi), -CU_ARM * math.sin(phi), 0.0])
        a = math.radians(p)
        return s + np.array([-10.0 * math.sin(a), 10.0 * math.cos(a), 0.0])
    end_arc = cu_hips(8.5)
    keys = [(8.5, tuple(end_arc)), (9.3, tuple(end_arc + np.array([3.2, -0.6, -0.8]))), (10.0, (-5.0, 22.0, 5.0)),
            (12.5, (-1.6, 21.7, 1.8)), (14.5, (0.0, 12.0 + SEAT_DROP, 0.0)), (20, (0.0, 12.0 + SEAT_DROP, 0.0))]
    return la.KV(keys, t)


def cu_frame(t):
    p = la.K(CU_LEAN, t)
    return yaw_frame(p, la.K(CU_TYAW, t), 0.0, cu_hips(t) - HIPS, la.K(CU_YAW, t))


def build_climb_up():
    c = la.clip('climb_up', CU_LEN, False, look=('farmer', 1), station_='wagon_bench', tools_=REINS,
                events=[('grab', CU_GRAB), ('step', CU_STEP), ('sit', CU_SIT)], views=((32, -12), (90, -2), (-60, -14)),
                traces=[('body', (0.0, 12.0, 0.0), (255, 200, 120))])
    ta.INFO['climb_up']['tools_at'] = tools_when([(p, CU_REINS, None) for p in REINS])
    drive = la.getc('drive')
    fr_end, head_end, _ = drive_pose(0.0)
    arms = la.Holds()
    fr_start = yaw_frame(0.0, 0.0, 0.0, CU_PR_START, CU_TURN)
    start_feet = {s: hip_world(s, fr_start) + np.array([0.0, 12.0, 0.0]) for s in ('right_leg', 'left_leg')}
    hub = HUB_STEP + np.array([-0.9, 0.0, -0.3])
    last = {}
    for t in la.every(CU_LEN):
        if t >= CU_LEN - 1e-9:
            key_from(c, t, drive, 0.0)
            continue
        fr = cu_frame(t)
        if t > 14.5:
            fr = blend_frames(fr, fr_end, smooth((t - 14.5) / 5.5))
        key_frame(c, t, fr, ease='linear')
        # the legs: the left up onto the hub and springing off it, both hanging as the body is hauled up, the right
        # swung over onto the bench, both forward with the turn and onto the footboard
        feet = {}
        if t <= 2.5:
            feet['left_leg'] = start_feet['left_leg']
        elif t <= 4.0:
            w = smooth((t - 2.5) / 1.5)
            feet['left_leg'] = start_feet['left_leg'] * (1 - w) + hub * w + np.array([0.0, -2.5 * math.sin(math.pi * w), 0.0])
        elif t <= 5.0:
            feet['left_leg'] = hub
        if t <= 4.0:
            feet['right_leg'] = start_feet['right_leg']
        for side in ('right_leg', 'left_leg'):
            if side in feet:
                continue
            if t >= 16.5:
                feet[side] = DRIVE_FEET[side]
                continue
            if side == 'right_leg':
                p_ = la.K([(4.0, 2.0), (6.0, 10.0), (7.5, -20.0), (8.5, -70.0), (9.5, -88.0), (12.0, -86.0), (14.5, -80.0),
                           (16.5, -74.0)], t)
            else:
                p_ = la.K([(5.0, 4.0), (7.0, 12.0), (8.5, 4.0), (10.0, -40.0), (11.5, -82.0), (14.5, -80.0), (16.5, -74.0)], t)
            sp = la.K([(4.0, 2.0), (10.0, 4.0), (16.5, 3.0)], t)
            f = leg_dir_point(side, fr, p_, sp)
            if t > 15.0:
                w = smooth((t - 15.0) / 1.5)
                f = f * (1 - w) + DRIVE_FEET[side] * w
            feet[side] = f
        legs_to(c, t, fr, feet)
        # the arms: up onto the holds, holding through the haul, let go onto the bench beside the hips, to the reins
        for side, hold, let_go in (('right_arm', CU_RIGHT_HOLD, 9.3), ('left_arm', CU_LEFT_HOLD, 9.8)):
            if t <= let_go:
                q, off = arms.slack(side, fr, [(GRIP[side], hold, 1.0)], [((0, 0, -1), D, 0.15)], slack=0.5, limit=1.6)
                if t < CU_GRAB:
                    w = smooth(t / CU_GRAB)
                    q, off = la.mix_arm((hang(side, fr), np.zeros(3)), (q, off), w)
                last[side] = (q, off)
            else:
                rein = fist_world(fr_end, side)
                lap = hip_world(side.replace('arm', 'leg'), fr) + qmat(fr['qr']) @ np.array([0.0, 6.0, -5.5])
                w = smooth((t - 14.5) / 4.0)
                target = lap * (1 - w) + rein * w
                q, off = arms.slack(side, fr, [(GRIP[side], target, 1.0)], [((0, 0, -1), PALM_IN[side], 0.2)], slack=1.0, limit=1.6)
                k = smooth((t - let_go) / 1.5)
                q, off = la.mix_arm(last[side], (q, off * (1.0 - smooth((t - 15.0) / 4.0))), k)
            la.key_arm(c, side, t, (q, off))
        hp = la.K([(0, 0.0), (1.5, -18.0), (4.0, -10.0), (7.0, 12.0), (9.0, 18.0), (11.0, 8.0), (14.0, 4.0), (20, head_end[0])], t)
        hy = la.K([(0, 0.0), (9.0, 0.0), (10.5, 24.0), (13.0, 12.0), (15.5, 0.0), (20, head_end[1])], t)
        c.key('head', t, qeuler(x=hp, y=hy, z=0.0), ease='linear')
    c.mode('head', 'add')
    reins = Reins(c, lambda t: 1.0 + 3.0 * (1.0 - smooth((t - CU_REINS) / 2.5)))
    for k in (3.5, 6.0, 8.0):
        check(c, f'right hand on the bench\'s end (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), CU_RIGHT_HOLD, tol=1.2)
        check(c, f'left hand on the box\'s side (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']), CU_LEFT_HOLD, tol=1.2)
    check(c, 'left foot on the hub (t=4.5)', la.leg_end(c, 4.5, 'left_leg'), hub, tol=0.6)
    for side in ('right_leg', 'left_leg'):
        check(c, f'{side} on the road (t=0)', la.leg_end(c, 0.0, side), start_feet[side], tol=0.3)
    la.join('climb_up ends on drive', c, CU_LEN, drive, 0, tol=0.05)
    maybe_join('climb_up starts on idle (on the road)', c, 0, 'idle', 0, M=idle_from(CU_PR_START, CU_TURN), tol=1.2)
    return c, reins

# ================================================================== cutting the horse loose

# The driver stands by the horse's right shoulder (the clip's -z toward the horse: CL_SPOT in the horse's model space,
# facing +x), saws through the right trace just behind its hame hook (4 strokes), puts the knife away, turns and walks
# three steps back along the horse to its haunch, turns to it and slaps it (the horse bolts), and watches it go.
CL_LEN = 50
CL_X0, CL_Z0 = -20.5, -14.0                                             # the spot, in the horse's model space
CL_SAWS = (5.0, 9.0, 13.0, 17.0)
CL_CUT, CL_SHEATHE, CL_SLAP = 20.0, 24.5, 39.0


def horse_clip(p):
    """A point of the horse's model space in cut_loose's clip space."""
    return np.array([p[2] - CL_Z0, p[1], CL_X0 - p[0]])


HAME_R = np.array([-wg.HAMES[0], 24.0 - wg.HAMES[1], wg.HAMES[2]])
SWINGLE_R = np.array([-wg.SWINGLE_HALF, wg.SWINGLE[1] + 24.0, wg.SWINGLE[2] - wg.HORSE_Z])
TRACE_DIR = unit(SWINGLE_R - HAME_R)
CL_CUT_AT = horse_clip(HAME_R + TRACE_DIR * 3.9)                        # where the knife goes through
CL_HOOK = horse_clip(HAME_R)
_tug = np.array([-wg.SHAFT_X, 24.0 - wg.SHAFT_UP, wg.TUG_Z])
_hinge = np.array([-wg.SHAFT_HINGE[0], wg.SHAFT_HINGE[1] + 24.0, wg.SHAFT_HINGE[2] - wg.HORSE_Z])
SHAFT_DIR = unit(_tug - _hinge)


def shaft_at(hz):
    """The right shaft's middle line at the horse's z [hz] (model space)."""
    return _tug + SHAFT_DIR * ((hz - _tug[2]) / SHAFT_DIR[2])


CL_SHAFT_HOLD = horse_clip(shaft_at(CL_Z0 + 5.0)) + np.array([0.0, -wg.SHAFT_T / 2 - 1.0, 0.0])   # the left fist on top of it
CL_SLAP_AT = horse_clip((-6.8 - 2.0, 24.0 - 21.5, 7.6))                # the palm on the haunch (the grip 2 px off it)
KNIFE_EDGE = np.array([-1.0, 10.95, -5.2])                              # right arm space: the middle of the knife's edge
KNIFE_BLADE = np.array([0.0, 0.0, -1.0])                                # right arm space: along the blade, to its tip
BLADE_UP = unit(np.array([0.0, -0.85, -0.53]))                          # the blade while sawing: up and ahead
SAW_PUSH = unit(np.array([0.0, 0.53, -0.85]))                           # pressing through the trace
CL_BELT = np.array([-4.7, 10.6, -0.6])                                  # torso space: the knife's sheath at the right hip
CL_END = np.array([24.6, 0.0, 1.0])                                    # the root at the end (in front of the haunch)
# the feet: (t, right sole, left sole) as they are planted: braced at the spot, three steps back along the horse
CL_STEPS = [('left_leg', 24.5, 27.5, (8.8, 24.0, 0.0)), ('right_leg', 27.5, 30.5, (17.0, 24.0, -0.8)),
            ('left_leg', 30.5, 33.5, (CL_END[0] + 1.9, 24.0, CL_END[2])), ('right_leg', 33.5, 36.0, (CL_END[0] - 1.9, 24.0, CL_END[2]))]
CL_FEET0 = {'right_leg': np.array([-2.2, 24.0, -0.3]), 'left_leg': np.array([2.2, 24.0, 0.3])}


def cl_feet(t):
    """Each sole at [t] and how high it's lifted."""
    out = {}
    for side in ('right_leg', 'left_leg'):
        p = CL_FEET0[side]
        lift = 0.0
        for s_, a, b, q in CL_STEPS:
            if s_ != side:
                continue
            if t >= b:
                p = np.array(q, float)
            elif t > a:
                w = smooth((t - a) / (b - a))
                p = p + (np.array(q, float) - p) * w
                lift = 2.6 * math.sin(math.pi * (t - a) / (b - a))
                break
            else:
                break
        out[side] = (np.asarray(p, float), lift)
    return out


CL_ROOT = [(0, (0.0, 0.0, 0.0)), (3.0, (0.0, 0.3, -0.8)), (20.0, (0.0, 0.3, -0.8)), (21.5, (0.0, 0.0, 0.3)),
           (24.5, (0.0, 0.0, 0.0)), (26.0, (2.8, 0.2, -0.2)), (28.0, (8.6, 0.0, -0.4)), (30.0, (13.4, 0.3, -0.8)),
           (32.0, (19.0, 0.0, -1.6)), (34.0, (23.4, 0.3, -2.6)), (36.0, (CL_END[0], 0.4, CL_END[2])),
           (38.5, (CL_END[0] + 0.4, 0.6, CL_END[2] - 0.6)), (39.5, (CL_END[0] - 0.3, 0.5, CL_END[2] - 0.4)),
           (42.0, (CL_END[0], 0.2, CL_END[2] + 0.5)), (46.0, (CL_END[0], 0.0, CL_END[2])), (50, (CL_END[0], 0.0, CL_END[2]))]
CL_YAW = [(0, 0.0), (24.0, 0.0), (26.5, -58.0), (29.0, -74.0), (32.0, -66.0), (34.5, -30.0), (36.5, 0.0), (50, 0.0)]
CL_PITCH = [(0, 0.0), (3.0, 14.0), (19.5, 14.0), (20.5, 18.0), (22.0, 6.0), (24.5, 2.0), (34.0, 4.0), (37.0, -4.0),
            (38.5, -6.0), (39.3, 14.0), (41.0, 8.0), (44.0, 0.0), (50, 0.0)]
CL_TYAW = [(0, 0.0), (3.0, -6.0), (19.5, -6.0), (21.0, 0.0), (24.0, 0.0), (36.0, 0.0), (37.5, 22.0), (39.0, -14.0),
           (41.5, -6.0), (44.0, 10.0), (47.0, 14.0), (50, 0.0)]
CL_ROLL = [(0, 0.0), (36.0, 0.0), (37.5, 6.0), (39.0, -4.0), (42.0, 0.0), (50, 0.0)]


def saw_offset(t):
    """How far up the blade the knife has slid (px along BLADE_UP) at [t]: four strokes, then through."""
    if t < 3.0 or t > CL_CUT + 0.6:
        return 0.0
    if t < 4.0:
        return 1.4 * smooth((t - 3.0) / 1.0)
    return 1.4 * math.cos(2.0 * math.pi * (t - 4.0) / 4.0) * (1.0 - smooth((t - 19.0) / 1.0))


def cl_frame(t):
    p = la.K(CL_PITCH, t)
    return yaw_frame(p, la.K(CL_TYAW, t), la.K(CL_ROLL, t), la.KV(CL_ROOT, t), la.K(CL_YAW, t), vt=(0.0, sink_for(p), 0.0))


def build_cut_loose():
    c = la.clip('cut_loose', CL_LEN, False, look=('farmer', 1), station_='horse_side', tools_=('driver_knife',),
                events=[('saw', k) for k in CL_SAWS] + [('cut', CL_CUT), ('sheathe', CL_SHEATHE), ('slap', CL_SLAP)],
                views=((40, -12), (110, -6), (-40, -16)),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    ta.INFO['cut_loose']['tools_at'] = tools_when([('driver_knife', 0.0, CL_SHEATHE)])
    ta.INFO['cut_loose']['pan'] = np.array([12.0, 0.0, -4.0])
    rights, lefts = la.Holds(), la.Arms()
    for t in la.every(CL_LEN):
        fr = cl_frame(t)
        key_frame(c, t, fr, ease='linear')
        fl = cl_feet(t)
        legs_to(c, t, fr, {s: p - np.array([0.0, lift, 0.0]) for s, (p, lift) in fl.items()})
        # the right hand: the knife onto the trace, sawing, through; to the sheath; free; the slap; hanging
        if t <= CL_CUT + 1.0:
            through = SAW_PUSH * 1.6 * smooth((t - CL_CUT + 0.4) / 0.8)
            target = CL_CUT_AT + BLADE_UP * saw_offset(t) + through
            q, off = rights.slack('right_arm', fr, [(KNIFE_EDGE, target, 1.0)], [(KNIFE_BLADE, BLADE_UP, 0.6)], slack=0.8, limit=1.5)
            if t < 3.0:
                w = smooth(t / 3.0)
                q, off = la.mix_arm((hang('right_arm', fr), np.zeros(3)), (q, off), w)
            la.key_arm(c, 'right_arm', t, (q, off))
            rights.prev['right_arm'] = q
            last = (q, off)
        elif t <= CL_SHEATHE:
            w = smooth((t - CL_CUT - 1.0) / (CL_SHEATHE - CL_CUT - 1.0))
            q = rights.reach('right_arm', fr, torso_to_world(fr, CL_BELT), palm=L, palm_w=0.3)
            q, off = la.mix_arm(last, (q, np.zeros(3)), w)
            la.key_arm(c, 'right_arm', t, (q, off))
        elif t < 36.0:
            swing = 14.0 * math.sin(2 * math.pi * (t - 24.5) / 6.0) * bump(t, 24.5, 36.0)
            q0 = rights.prev['right_arm']
            q = hang('right_arm', fr, fwd=-swing)
            w = smooth((t - CL_SHEATHE) / 1.5)
            c.key('right_arm', t, qslerp(q0, q, w), ease='linear')
            if t >= CL_SHEATHE + 1.5:
                rights.prev['right_arm'] = q
        else:
            # wound up high and back, swung down onto the haunch, bounced off, down to the side
            wind = torso_to_world(fr, np.array([-6.5, -6.0, 2.5]))
            hit = CL_SLAP_AT
            after = torso_to_world(fr, np.array([-7.2, 6.0, -6.0]))
            if t <= 38.0:
                q = rights.reach('right_arm', fr, wind, palm=F, palm_w=0.3)
                q = qslerp(rights.prev['right_arm'], q, smooth((t - 36.0) / 2.0))
            elif t <= CL_SLAP:
                w = smooth((t - 38.0) / (CL_SLAP - 38.0))
                p = wind * (1 - w) + hit * w + np.array([0.0, -2.0 * math.sin(math.pi * w), -1.5 * math.sin(math.pi * w)])
                q = rights.reach('right_arm', fr, p, palm=F, palm_w=0.4)
            elif t <= 41.0:
                w = smooth((t - CL_SLAP) / 2.0)
                q = rights.reach('right_arm', fr, hit * (1 - w) + after * w, palm=F, palm_w=0.3)
            else:
                w = smooth((t - 41.0) / 7.0)
                q = qslerp(rights.reach('right_arm', fr, after, palm=F, palm_w=0.3), hang('right_arm', fr), w)
            c.key('right_arm', t, q, ease='linear')
        # the left hand: on the shaft while sawing, off it as the trace goes, swinging with the walk, hanging
        if t <= CL_CUT + 1.5:
            q = lefts.reach('left_arm', fr, CL_SHAFT_HOLD, palm=D, palm_w=0.3)
            if t < 3.0:
                q = qslerp(hang('left_arm', fr), q, smooth(t / 3.0))
            c.key('left_arm', t, q, ease='linear')
            lq = q
        else:
            swing = -14.0 * math.sin(2 * math.pi * (t - 24.5) / 6.0) * bump(t, 24.5, 36.0)
            out = la.K([(21.5, 4.4), (38.0, 4.4), (39.5, 14.0), (43.0, 8.0), (50, 4.4)], t)
            q = hang('left_arm', fr, out=out, fwd=-swing)
            c.key('left_arm', t, qslerp(lq, q, smooth((t - CL_CUT - 1.5) / 2.5)), ease='linear')
        # the head: on the knife's work, a glance at the horse's head as it goes, along the horse, on the haunch,
        # after the horse as it bolts
        hp = la.K([(0, 0.0), (3.0, 16.0), (19.5, 18.0), (21.5, 6.0), (24.0, 2.0), (27.0, 4.0), (36.0, 4.0), (38.5, 10.0),
                   (40.0, 6.0), (43.0, -4.0), (50, 0.0)], t)
        hy = la.K([(0, 0.0), (3.0, -10.0), (19.5, -10.0), (21.5, -28.0), (24.0, -16.0), (26.5, 30.0), (33.0, 40.0), (36.0, 20.0),
                   (38.5, 18.0), (40.0, 6.0), (43.0, -40.0), (47.0, -56.0), (50, 0.0)], t)
        c.key('head', t, qeuler(x=hp, y=hy, z=0.0), ease='linear')
    c.mode('head', 'add')
    for k in CL_SAWS:
        got = ta.fk(c, k, 'right_arm', KNIFE_EDGE)
        check(c, f'the knife\'s edge on the trace (t={k:g}: px off its line)',
              (float(np.linalg.norm(np.cross(got - CL_CUT_AT, BLADE_UP))),), (0.0,), tol=0.8)
    check(c, 'left fist on the shaft (t=10)', ta.fk(c, 10.0, 'left_arm', GRIP['left_arm']), CL_SHAFT_HOLD, tol=0.8)
    check(c, 'the slap on the haunch (t=39)', ta.fk(c, CL_SLAP, 'right_arm', GRIP['right_arm']), CL_SLAP_AT, tol=0.9)
    for side, (p, _) in cl_feet(CL_LEN).items():
        check(c, f'{side} planted at the end (t=50)', la.leg_end(c, CL_LEN, side), p, tol=0.3)
    maybe_join('cut_loose starts on idle', c, 0, 'idle', 0, tol=1.0)
    maybe_join('cut_loose ends on idle (by the haunch)', c, CL_LEN, 'idle', 0, M=idle_from(CL_END, 0.0), tol=1.0)
    return c

# ================================================================== the load handed down off the tailboard

# On the floor at STAND facing out over the tailboard: a turn about to the load behind, a crate lifted off the floor
# (the goods in the hands from 7), turned back, down on the right knee at the tailboard's edge with it held out and down
# to someone standing on the road below (receive's partner: 24 px out, 19 px down, facing back), let go as they take it
# (16), and back up. receive's goods meet the giver's at the handoff.
HD_LEN = 30
HD_LIFT, HD_HANDOFF = 7.0, 16.0
STAND_SEAT = (STAND[0], STAND[1])
HD_DROP = seat_space(STAND_SEAT, (0.0, 0.0, 0.0))[1] - 24.0             # how far below the floor the road is (19)
HD_GAP = 24.0                                                           # the taker stands this far out
HD_TAKER = T(0.0, HD_DROP, -HD_GAP) @ Rq(qeuler(y=180.0))               # the taker's place in the giver's space
HD_GOODS_AT = apply(HD_TAKER, np.array([0.0, 3.0, -16.0]))              # receive's goods' middle at its handoff, here
HD_PICK = np.array([0.0, 20.0, 9.0])                                    # the crate's middle on the floor behind
HD_ROOT = [(0, (0.0, 0.0, 0.0)), (2.0, (0.0, 0.5, 0.6)), (4.0, (0.0, 2.6, 1.6)), (6.0, (0.0, 4.4, 1.6)), (7.0, (0.0, 4.2, 1.4)),
           (9.5, (0.0, 1.2, 0.8)), (12.0, (0.0, 0.6, -0.4)), (14.0, (0.0, 3.6, -1.2)), (16.0, (0.0, 5.4, -1.4)),
           (18.0, (0.0, 5.2, -1.2)), (22.0, (0.0, 3.0, -0.8)), (26.0, (0.0, 0.4, -0.2)), (30, (0.0, 0.0, 0.0))]
HD_YAW = [(0, 0.0), (1.0, 0.0), (4.0, 168.0), (8.5, 176.0), (10.0, 150.0), (12.5, 20.0), (14.0, 0.0), (30, 0.0)]
HD_PITCH = [(0, 0.0), (2.0, 10.0), (4.5, 52.0), (6.0, 66.0), (7.0, 64.0), (9.5, 18.0), (12.0, 8.0), (14.0, 32.0), (16.0, 50.0),
            (18.0, 48.0), (21.0, 30.0), (25.0, 6.0), (28.0, -1.5), (30, 0.0)]
# the hands' middle (world), what they hold 2 px further ahead
HD_HANDS = [(4.5, HD_PICK + np.array([0.0, 0.0, -2.0])), (HD_LIFT, HD_PICK + np.array([0.0, 0.0, -2.0])),
            (9.5, np.array([0.0, 7.0, 4.6])), (12.0, np.array([0.0, 7.6, -4.0])), (14.0, np.array([0.0, 13.0, -6.6])),
            (HD_HANDOFF, HD_GOODS_AT + np.array([0.0, 0.0, 2.0]))]
HD_KNEE = [(0, 0.0), (12.5, 0.0), (15.0, 1.0), (21.0, 1.0), (24.5, 0.0), (30, 0.0)]


def hd_frame(t):
    p = la.K(HD_PITCH, t)
    return yaw_frame(p, 0.0, 0.0, la.KV(HD_ROOT, t), la.K(HD_YAW, t), vt=(0.0, sink_for(p), 0.0))


def hd_feet(t, fr):
    """The soles: pivoting where they stand through the turns; the left a step out onto the tailboard and the right
    knee down on the floor behind for the handoff."""
    k = smooth(la.K(HD_KNEE, t))
    yaw = la.K(HD_YAW, t)
    turn = qmat(qeuler(y=yaw))
    out = {}
    for side, x in (('right_leg', -1.9), ('left_leg', 1.9)):
        base = turn @ np.array([x, 0.0, 0.0]) + np.array([0.0, 24.0, 0.0])
        if side == 'left_leg':
            p = base + (np.array([1.9, 24.0, -2.6]) - base) * k
            lift = 2.2 * bump(t, 12.5, 15.0)
        else:
            hip = hip_world('right_leg', fr)
            dy = 24.0 - hip[1]
            back = math.sqrt(max(0.0, 144.0 - dy * dy - 0.25))
            knee = np.array([-1.9, 24.0, hip[2] + back])
            p = base + (knee - base) * k
            lift = 1.6 * bump(t, 12.5, 15.0) + 1.6 * bump(t, 21.0, 24.5)
        out[side] = p - np.array([0.0, lift, 0.0])
    return out


def build_hand_down():
    has_taker = any(k.name == 'receive' for k in ta.clips)
    c = la.clip('hand_down', HD_LEN, False, look=('farmer', 1), station_='wagon_stand',
                events=[('lift', HD_LIFT), ('handoff', HD_HANDOFF)], partner='receive' if has_taker else None,
                views=((40, -14), (100, -4), (200, -14)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    la.set_goods('hand_down', [(HD_LIFT, HD_HANDOFF, ('hands', 2.0), la.GOODS)])
    arms = la.Holds()
    held = {}
    for t in la.every(HD_LEN):
        fr = hd_frame(t)
        key_frame(c, t, fr, ease='linear')
        legs_to(c, t, fr, hd_feet(t, fr))
        right = qmat(fr['qr']) @ np.array([-1.0, 0.0, 0.0])                # the body's right, level
        for side, s_ in (('right_arm', 1.0), ('left_arm', -1.0)):
            if t <= HD_HANDOFF:
                mid = la.KV(HD_HANDS, max(t, 4.5))
                q, off = arms.slack(side, fr, [(GRIP[side], mid + right * 6.0 * s_, 1.0)], [((0, 0, -1), -right * s_, 0.4)],
                                    slack=0.5, limit=1.6)
                if t < 4.5:
                    q, off = la.mix_arm((hang(side, fr), np.zeros(3)), (q, off), smooth((t - 0.5) / 4.0))
                held[side] = (q, off)
                la.key_arm(c, side, t, (q, off))
            else:
                # let go: the hands slide off and open, and back to the sides
                la.key_arm(c, side, t, la.mix_arm(held[side], (hang(side, fr), np.zeros(3)), smooth((t - HD_HANDOFF) / 7.0)))
        hp = la.K([(0, 0.0), (2.0, 10.0), (4.5, 30.0), (7.0, 24.0), (9.5, 4.0), (12.0, 6.0), (14.0, 18.0), (16.0, 20.0),
                   (18.0, 8.0), (22.0, -4.0), (26.0, 2.0), (30, 0.0)], t)
        c.key('head', t, qeuler(x=hp), ease='linear')
    c.mode('head', 'add')
    check(c, 'the goods where the taker takes them (t=16, against receive)', la.goods_at('hand_down', HD_HANDOFF),
          HD_GOODS_AT, tol=0.8)
    if has_taker:
        check(c, 'receive\'s goods where hand_down holds them out (t=16)',
              apply(HD_TAKER, la.goods_at('receive', HD_HANDOFF)), HD_GOODS_AT, tol=0.3)
    check(c, 'right knee down on the floor (t=16)', (la.leg_end(c, HD_HANDOFF, 'right_leg')[1],), (24.0,), tol=0.4)
    check(c, 'left foot on the tailboard (t=16)', (la.leg_end(c, HD_HANDOFF, 'left_leg')[1],), (24.0,), tol=0.4)
    maybe_join('hand_down starts on idle', c, 0, 'idle', 0, tol=1.0)
    maybe_join('hand_down ends on idle', c, HD_LEN, 'idle', 0, tol=1.0)
    return c

# ================================================================== cowering behind a wheel

# ---- cower (40, loop): down on the haunches facing a wheel (its middle plane CW_WHEEL_Z ahead, the hub CW_HUB_X to
# the left), the head tucked and the hands clasped over the back of it, shaking; a peek out round the wheel's right edge
# with the right hand on the rim (17 to 28), and down again with a flinch (30.5)
CW_LEN = 40
CW_FEET = {'right_leg': np.array([-2.9, 24.0, 0.8]), 'left_leg': np.array([2.9, 24.0, -0.4])}
CW_WHEEL_Z = -15.0                                      # the wheel's middle plane, ahead
CW_HUB_X = 6.0                                          # the hub, to the left
CW_RIM_FACE = CW_WHEEL_Z + wg.FELLOE_W / 2.0            # the rim's face toward the one hiding
CW_RIM_GRIP = np.array([CW_HUB_X - wg.RR * math.cos(math.radians(24.0)), 24.0 - wg.RR - wg.RR * math.sin(math.radians(24.0)),
                        CW_RIM_FACE + 0.9])            # the right hand round the rim's right edge while peeking
CW_HEAD_SET = 26.0                                      # the head tucked, its own frame on the torso's ('set')
CW_FIST = {'right_arm': np.array([-2.0, -8.3, 2.6]), 'left_arm': np.array([1.8, -8.6, 1.8])}   # head space: the back of the crown
CW_PEEK = [(0, 0.0), (17.0, 0.0), (20.5, 1.0), (25.5, 1.0), (28.0, 0.0), (40, 0.0)]
CW_FLINCH = 30.5
CW_HEAD_CORNERS = [np.array([x, y, z]) for x in (-4.0, 4.0) for y in (-8.0, 0.0) for z in (-4.0, 4.0)]


def cw_shake(t):
    """The shaking: two quick trembles that both come round in the loop's 40 ticks."""
    return 0.9 * math.sin(2.0 * math.pi * t / 2.0) + 0.5 * math.sin(2.0 * math.pi * 26.0 * t / CW_LEN + 0.7)


def cw_frame(t):
    ph = 2.0 * math.pi * t / CW_LEN
    shake = cw_shake(t)
    peek = la.K(CW_PEEK, t, loop=CW_LEN)
    flinch = bump(t, 30.0, 33.5)
    pitch = 22.0 - 7.0 * peek + 7.0 * flinch + 1.2 * math.sin(2 * ph)
    drop = 6.6 - 1.4 * peek + 0.9 * flinch + 0.15 * shake
    fr = F_(qt=bend(pitch, 0.5 * shake + 12.0 * peek, 0.4 * shake - 7.0 * peek), vt=(0.0, sink_for(pitch), 0.0),
            pr=(-0.8 * peek, drop, 1.4 - 2.4 * peek + 0.4 * flinch))
    return fr, peek, flinch


def build_cower():
    c = la.clip('cower', CW_LEN, True, look=('citizen', 2), station_='wheel_cover', events=[('flinch', CW_FLINCH)],
                views=((40, -12), (90, -2), (180, -10)), traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))])
    arms = la.Holds()
    for t in la.every(CW_LEN):
        fr, peek, flinch = cw_frame(t)
        key_frame(c, t, fr, ease='linear')
        legs_to(c, t, fr, CW_FEET)
        shake = math.sin(2.0 * math.pi * t / 2.0 + 1.3)
        hp = CW_HEAD_SET - 40.0 * peek + 8.0 * flinch
        hy = 34.0 * peek
        q_head = qeuler(x=hp, y=hy, z=0.8 * shake)
        c.key('head', t, q_head, ease='linear')
        # the hands clasped over the back of the head; the right off it and round the rim's edge to peek
        for side in ('right_arm', 'left_arm'):
            p = ta.head_point(fr, q_head, CW_FIST[side], mode='set') + np.array([0.0, 0.25 * shake, 0.0])
            palm = D
            if side == 'right_arm' and peek > 0.0:
                w = smooth(peek)
                p = p * (1 - w) + CW_RIM_GRIP * w + math.sin(math.pi * w) * np.array([-3.2, 0.6, -1.8])
                palm = unit(D * (1 - w) + unit(L + 0.4 * F) * w)
            q, off = arms.slack(side, fr, [(GRIP[side], p, 1.0)], [((0, 0, -1), palm, 0.15)], slack=0.6, limit=1.5)
            la.key_arm(c, side, t, (q, off))
    c.mode('head', 'set')
    ta.seal(c)
    for k in (0.0, 10.0, 32.0):
        for side in ('right_arm', 'left_arm'):
            q_head = c.sample('head', k)[0]
            fr, _, _ = cw_frame(k)
            check(c, f'{side} over the back of the head (t={k:g})', ta.fk(c, k, side, GRIP[side]),
                  ta.head_point(fr, q_head, CW_FIST[side], mode='set'), tol=1.0)
    check(c, 'right hand round the rim (t=23)', ta.fk(c, 23.0, 'right_arm', GRIP['right_arm']), CW_RIM_GRIP, tol=1.0)
    near, near_t = min((float(ta.fk(c, k, 'head', p)[2]), k) for k in la.every(CW_LEN) for p in CW_HEAD_CORNERS)
    check(c, f'the head clear of the rim (its front {near:.2f} at t={near_t:g}, the rim {CW_RIM_FACE:.2f})',
          (min(0.0, near - CW_RIM_FACE - 0.4),), (0.0,), tol=0.05)
    return c

# ================================================================== back at the gate

# ---- catch_breath (40, loop): bent over with the hands on the thighs, heaving: five breaths, two of them gasps (1.5,
# 17.5), a cough (30), a look up the road (22 to 28)
CB_LEN = 40
CB_FEET = {'right_leg': np.array([-2.7, 24.0, -0.4]), 'left_leg': np.array([2.7, 24.0, 0.2])}
CB_HANDS = {'right_arm': np.array([-2.5, 16.9, -4.0]), 'left_arm': np.array([2.5, 17.1, -3.9])}
CB_GASPS = (1.5, 17.5)


def cb_breath(t):
    """1 at the top of a breath, 0 at the bottom: in quick, out slower, every 8 ticks."""
    u = (t % 8.0) / 8.0
    return smooth(u / 0.3) if u < 0.3 else 1.0 - smooth((u - 0.3) / 0.7)


def cb_frame(t):
    b = cb_breath(t)
    gasp = sum(bump(t, g - 1.5, g + 2.0) for g in CB_GASPS)
    cough = bump(t, 29.5, 31.5) * math.sin(math.pi * (t - 29.5) / 0.7) ** 2 if 29.5 <= t <= 31.5 else 0.0
    look = la.K([(0, 0.0), (21.0, 0.0), (23.5, 1.0), (27.0, 1.0), (29.0, 0.0), (40, 0.0)], t, loop=CB_LEN)
    pitch = 50.0 - 5.0 * b - 3.0 * gasp + 7.0 * cough - 6.0 * look
    return F_(qt=bend(pitch, 1.5 * math.sin(2 * math.pi * t / CB_LEN), 0.0),
              vt=(0.0, sink_for(pitch) - 0.5 * b - 0.4 * gasp, 0.0), pr=(0.0, 3.0 + 0.3 * b, 0.4)), b, look, cough


def build_catch_breath():
    c = la.clip('catch_breath', CB_LEN, True, look=('farmer', 1), events=[('gasp', g) for g in CB_GASPS] + [('cough', 30.0)],
                views=((32, -12), (90, -2), (-40, -8)), traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))])
    arms = la.Holds()
    for t in la.every(CB_LEN):
        fr, b, look, cough = cb_frame(t)
        key_frame(c, t, fr, ease='linear')
        legs_to(c, t, fr, CB_FEET)
        for side, p in CB_HANDS.items():
            q, off = arms.slack(side, fr, [(GRIP[side], p, 1.0)], [((0, 0, -1), B, 0.3)], slack=0.6, limit=1.5)
            la.key_arm(c, side, t, (q, off))
        hp = 10.0 - 12.0 * b - 34.0 * look + 10.0 * cough
        c.key('head', t, qeuler(x=hp, y=10.0 * look, z=0.0), ease='linear')
    c.mode('head', 'add')
    ta.seal(c)
    for k in (0.0, 4.0):
        for side, p in CB_HANDS.items():
            check(c, f'{side} on the thigh (t={k:g})', ta.fk(c, k, side, GRIP[side]), p, tol=0.8)
    return c


# ---- recount (80, loop): telling it with the whole body: a point back down the road over the right shoulder (6), the
# hands up, the rush acted out with a lunge and clawing hands (21), the wagon going over swept from left to right (38), a
# fist banged into the palm twice (47, 50), a shake of the head with the hands low, a shrug with the palms up, and round
RC_LEN = 80
RC_R = [(0, (-6.4, 10.4, -3.0)), (3.0, (-11.0, 0.4, 7.0)), (6.0, (-11.4, -0.6, 7.8)), (10.0, (-11.0, 0.0, 7.4)),
        (13.0, (-5.4, 6.4, -6.0)), (15.5, (-4.2, 2.6, -8.4)), (18.0, (-6.2, -2.4, -8.2)), (21.0, (-5.0, -5.6, -9.4)),
        (24.5, (-5.6, -4.2, -9.0)), (28.0, (-5.6, 3.6, -7.4)), (31.0, (2.4, 4.8, -8.8)), (34.0, (3.0, 4.4, -9.0)),
        (38.0, (-9.2, 4.6, -6.2)), (41.0, (-10.2, 5.6, -3.4)), (44.0, (-5.6, 5.0, -7.4)), (45.8, (-7.0, 4.0, -7.0)),
        (47.0, (-2.4, 6.4, -8.5)), (48.5, (-6.6, 4.2, -7.2)), (50.0, (-2.4, 6.4, -8.5)), (52.5, (-4.6, 6.4, -8.0)),
        (55.0, (-6.6, 9.0, -5.2)), (63.0, (-6.8, 9.2, -5.0)), (67.0, (-7.8, 5.6, -6.6)), (73.0, (-7.8, 5.4, -6.8)),
        (77.0, (-6.6, 9.6, -4.0)), (80, (-6.4, 10.4, -3.0))]
RC_L = [(0, (6.4, 10.4, -3.0)), (3.0, (6.4, 10.6, -1.0)), (10.0, (6.4, 10.6, -1.0)), (13.0, (5.6, 6.6, -6.0)),
        (15.5, (4.2, 2.6, -8.4)), (18.0, (6.2, -2.4, -8.2)), (21.0, (5.0, -5.6, -9.4)), (24.5, (5.6, -4.2, -9.0)),
        (28.0, (5.6, 3.6, -7.4)), (31.0, (8.0, 4.6, -6.2)), (34.0, (8.6, 4.8, -5.0)), (38.0, (-2.4, 5.0, -8.8)),
        (41.0, (0.6, 6.0, -8.6)), (44.0, (1.6, 6.6, -8.4)), (52.5, (1.6, 6.6, -8.4)), (55.0, (6.6, 9.0, -5.2)),
        (63.0, (6.8, 9.2, -5.0)), (67.0, (7.8, 5.6, -6.6)), (73.0, (7.8, 5.4, -6.8)), (77.0, (6.6, 9.6, -4.0)),
        (80, (6.4, 10.4, -3.0))]
RC_PALM_R = [(0, L), (2.0, D), (11.0, D), (14.0, F), (19.0, F), (25.0, D), (29.0, D), (35.0, D), (40.0, D), (43.0, L),
             (53.0, L), (55.0, D), (63.5, D), (65.0, unit(F + 0.2 * U)), (66.5, U), (74.0, U), (77.0, L), (80, L)]
RC_PALM_L = [(0, R), (2.0, R), (11.0, R), (14.0, F), (19.0, F), (25.0, D), (29.0, D), (36.0, D), (41.0, R), (53.0, R),
             (55.0, D), (63.5, D), (65.0, unit(F + 0.2 * U)), (66.5, U), (74.0, U), (77.0, R), (80, R)]
RC_PITCH = [(0, 1.0), (6.0, -2.0), (12.0, 2.0), (17.0, -3.0), (21.0, 14.0), (25.0, 10.0), (30.0, 0.0), (34.0, 3.0),
            (38.0, 5.0), (44.0, 4.0), (47.0, 8.0), (50.0, 8.0), (54.0, 3.0), (60.0, 6.0), (66.0, -2.0), (72.0, -3.0),
            (76.0, 0.0), (80, 1.0)]
RC_YAW = [(0, 0.0), (3.0, 24.0), (10.0, 26.0), (13.0, 0.0), (21.0, 0.0), (31.0, -22.0), (34.0, -24.0), (38.0, 18.0),
          (42.0, 20.0), (46.0, 0.0), (80, 0.0)]
RC_ROLL = [(0, 0.0), (6.0, 4.0), (12.0, 0.0), (34.0, -2.0), (38.0, 3.0), (44.0, 0.0), (80, 0.0)]
RC_ROOT = [(0, (0.0, 0.0, 0.0)), (17.0, (0.0, 0.3, 0.5)), (21.0, (0.0, 1.6, -2.6)), (26.0, (0.0, 1.2, -2.0)),
           (30.0, (0.0, 0.0, 0.0)), (46.0, (0.0, 0.6, 0.0)), (50.0, (0.0, 0.6, 0.0)), (54.0, (0.0, 0.0, 0.0)), (80, (0.0, 0.0, 0.0))]
RC_RYAW = [(0, 0.0), (3.0, 10.0), (10.0, 10.0), (13.0, 0.0), (80, 0.0)]
RC_HP = [(0, 0.0), (3.0, -4.0), (6.0, -6.0), (12.0, 2.0), (17.0, -4.0), (21.0, 10.0), (25.0, 6.0), (30.0, 0.0), (38.0, 4.0),
         (47.0, 8.0), (50.0, 8.0), (54.0, 4.0), (60.0, 4.0), (66.0, -6.0), (72.0, -4.0), (76.0, 0.0), (80, 0.0)]
RC_HY = [(0, 0.0), (3.0, 50.0), (9.0, 56.0), (13.0, 0.0), (31.0, -14.0), (34.0, -18.0), (38.0, 16.0), (42.0, 14.0),
         (46.0, 0.0), (55.0, 0.0), (56.5, 16.0), (58.0, -16.0), (59.5, 14.0), (61.0, -12.0), (62.5, 6.0), (64.0, 0.0), (80, 0.0)]
RC_FEET = {'right_leg': np.array([-2.1, 24.0, 0.0]), 'left_leg': np.array([2.1, 24.0, 0.0])}


def build_recount():
    c = la.clip('recount', RC_LEN, True, look=('farmer', 1),
                events=[('point', 6.0), ('rush', 21.0), ('sweep', 38.0), ('clap', 47.0), ('clap', 50.0)],
                views=((32, -12), (90, -2), (-40, -8)),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    arms = la.Arms()
    for t in la.every(RC_LEN):
        fr = yaw_frame(la.K(RC_PITCH, t, RC_LEN), la.K(RC_YAW, t, RC_LEN), la.K(RC_ROLL, t, RC_LEN), la.KV(RC_ROOT, t, RC_LEN),
                       la.K(RC_RYAW, t, RC_LEN), vt=(0.0, sink_for(la.K(RC_PITCH, t, RC_LEN)), 0.0))
        key_frame(c, t, fr, ease='linear')
        legs_to(c, t, fr, RC_FEET)
        rest = smooth(t / 2.5) * (1.0 - smooth((t - (RC_LEN - 3.0)) / 3.0))
        for side, keys, palms in (('right_arm', RC_R, RC_PALM_R), ('left_arm', RC_L, RC_PALM_L)):
            p = torso_to_world(fr, la.KV(keys, t, RC_LEN))
            palm = unit(la.KV(palms, t))
            q = arms.reach(side, fr, p, palm=palm, palm_w=0.3)
            c.key(side, t, qslerp(hang(side, fr), q, rest), ease='linear')
        c.key('head', t, qeuler(x=la.K(RC_HP, t, RC_LEN), y=la.K(RC_HY, t, RC_LEN)), ease='linear')
    c.mode('head', 'add')
    ta.seal(c)
    for k in (47.0, 50.0):
        gap = float(np.linalg.norm(ta.fk(c, k, 'right_arm', GRIP['right_arm']) - ta.fk(c, k, 'left_arm', GRIP['left_arm'])))
        check(c, f'the fist into the palm (t={k:g}: the grips a fist apart)', (gap,), (4.0,), tol=1.0)
    maybe_join('recount starts on idle', c, 0, 'idle', 0, tol=1.5)
    return c


# ---- wounded (40, loop, overlay): the left hand pressed to the side, a wince (4, 24) as the weight comes on the hurt
# side, the head low. Only the left arm and the head: the walk goes on under it (slowly, the game's part), the right
# hand keeps whatever it holds.
WD_LEN = 40
WD_HAND = np.array([2.6, 10.0, -4.2])                    # torso space: pressed to the left of the belly
WD_WINCES = (4.0, 24.0)


def build_wounded():
    c = la.clip('wounded', WD_LEN, True, look=('guard', 2), overlay=True, events=[('wince', k) for k in WD_WINCES],
                views=((32, -12), (90, -2), (-40, -8)), traces=[('left_arm', GRIP['left_arm'], (200, 230, 255))])
    fr0 = F_()
    arms = la.Holds()
    for t in la.every(WD_LEN):
        w = sum(bump(t, k - 1.0, k + 4.0) for k in WD_WINCES) + bump(t + WD_LEN, WD_WINCES[0] + WD_LEN - 1.0, WD_WINCES[0] + WD_LEN + 4.0)
        p = WD_HAND + np.array([0.3, -0.5, 0.6]) * w
        q, off = arms.slack('left_arm', fr0, [(GRIP['left_arm'], p, 1.0)], [((0, 0, -1), B, 0.35)], slack=0.6, limit=1.2)
        la.key_arm(c, 'left_arm', t, (q, off))
        ph = 2.0 * math.pi * t / 20.0
        c.key('head', t, qeuler(x=12.0 + 9.0 * w + 2.0 * math.sin(ph), y=0.0, z=-3.0 - 4.0 * w), ease='linear')
    c.mode('head', 'add')
    ta.seal(c)
    check(c, 'left hand pressed to the side (t=0)', ta.fk(c, 0.0, 'left_arm', GRIP['left_arm']), torso_to_world(fr0, WD_HAND), tol=1.0)
    return c


# ---- grumble (40): the arms folded, a huff (7) and a shake of the head, the right hand swept out to brush it all away
# (27), the arms down again
GR_LEN = 40
GR_R = [(0, None), (5.5, (2.2, 7.4, -4.6)), (21.0, (2.2, 7.4, -4.6)), (24.0, (-1.6, 4.6, -8.8)), (25.5, (-3.2, 4.4, -9.2)),
        (27.0, (-8.8, 5.0, -6.6)), (29.0, (-11.0, 6.6, -2.4)), (33.0, None)]
GR_L = [(0, None), (6.0, (-2.4, 7.0, -5.2)), (29.0, (-2.4, 7.0, -5.2)), (35.0, None)]
GR_PITCH = [(0, 0.0), (6.0, -3.0), (9.0, -1.0), (18.0, -2.0), (24.0, 2.0), (27.0, 6.0), (31.0, 2.0), (36.0, 0.0), (40, 0.0)]
GR_YAW = [(0, 0.0), (6.0, 0.0), (22.0, 0.0), (24.5, 10.0), (27.5, -14.0), (31.0, -6.0), (36.0, 0.0), (40, 0.0)]
GR_ROLL = [(0, 0.0), (5.0, 2.0), (20.0, 2.5), (26.0, 0.0), (40, 0.0)]
GR_ROOT = [(0, (0.0, 0.0, 0.0)), (5.0, (0.6, 0.0, 0.2)), (20.0, (0.7, 0.0, 0.3)), (27.0, (-0.2, 0.2, -0.3)), (34.0, (0.0, 0.0, 0.0)),
           (40, (0.0, 0.0, 0.0))]
GR_HP = [(0, 0.0), (5.0, 4.0), (7.0, -4.0), (9.0, 6.0), (18.0, 6.0), (22.0, 0.0), (27.0, -2.0), (31.0, 4.0), (36.0, 0.0), (40, 0.0)]
GR_HY = [(0, 0.0), (8.0, 0.0), (9.5, 18.0), (11.5, -18.0), (13.5, 16.0), (15.5, -14.0), (17.0, 6.0), (18.5, 0.0), (24.0, 0.0),
         (27.5, -22.0), (31.0, -26.0), (36.0, 0.0), (40, 0.0)]
GR_FEET = {'right_leg': np.array([-1.9, 24.0, 0.0]), 'left_leg': np.array([1.9, 24.0, 0.0])}


def build_grumble():
    c = la.clip('grumble', GR_LEN, False, look=('farmer', 1), events=[('huff', 7.0), ('sweep', 27.0)],
                views=((32, -12), (90, -2), (-40, -8)),
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))])
    arms = la.Arms()
    for t in la.every(GR_LEN):
        fr = F_(qt=bend(la.K(GR_PITCH, t), la.K(GR_YAW, t), la.K(GR_ROLL, t)), pr=la.KV(GR_ROOT, t))
        key_frame(c, t, fr, ease='linear')
        legs_to(c, t, fr, GR_FEET)
        for side, keys, palm in (('right_arm', GR_R, L), ('left_arm', GR_L, R)):
            held = [(k, p) for k, p in keys if p is not None]
            first, last = held[0][0], held[-1][0]
            start = keys[0][0]
            end = keys[-1][0]
            if t <= start or t >= end:
                q = hang(side, fr)
                arms.prev.pop(side, None)
            else:
                target = torso_to_world(fr, la.KV(held, min(max(t, first), last)))
                pl = palm if not (side == 'right_arm' and t > 24.0) else unit(D + 0.6 * F)
                q = arms.reach(side, fr, target, palm=pl, palm_w=0.3)
                if t < first:
                    q = qslerp(hang(side, fr), q, smooth((t - start) / (first - start)))
                elif t > last:
                    q = qslerp(q, hang(side, fr), smooth((t - last) / (end - last)))
            c.key(side, t, q, ease='linear')
        c.key('head', t, qeuler(x=la.K(GR_HP, t), y=la.K(GR_HY, t)), ease='linear')
    c.mode('head', 'add')
    maybe_join('grumble starts on idle', c, 0, 'idle', 0, tol=1.0)
    maybe_join('grumble ends on idle', c, GR_LEN, 'idle', 0, tol=1.0)
    return c

# ================================================================== the previews' stations for these


def _horse_side_boxes():
    """The horse's right side as cut_loose meets it: its body, collar, legs, the shaft and the trace."""
    b = []

    def hbox(lo, hi, col):
        a, z = horse_clip(lo), horse_clip(hi)
        b.append((np.minimum(a, z), np.maximum(a, z), col, True))
    hbox((-6.6, 24.0 - 27.0, -15.0), (6.6, 24.0 - 16.0, 17.0), HORSE)
    hbox((-6.8, 24.0 - 31.0, -21.0), (6.8, 24.0 - 15.0, -15.0), (70, 46, 30))
    hbox((-3.4, 24.0 - 33.0, -30.0), (3.4, 24.0 - 24.0, -17.0), HORSE)
    for x, z in ((-3.7, -11.2), (3.7, -11.2), (-3.9, 11.0), (3.9, 11.0)):
        hbox((x - 1.6, 24.0 - 16.0, z - 1.8), (x + 1.6, 24.0, z + 1.8), (96, 58, 34))
    # the shaft from its tip back toward the wagon, in short pieces
    for k in range(12):
        z0 = wg.SHAFT_TIP_Z + k * 5.0
        a, z1 = shaft_at(z0), shaft_at(z0 + 5.0)
        lo = np.minimum(a, z1) - np.array([0.85, 0.85, 0.0])
        hi = np.maximum(a, z1) + np.array([0.85, 0.85, 0.0])
        hbox(lo, hi, WOOD)
    # the trace from the hame hook
    for k in range(12):
        a = HAME_R + TRACE_DIR * (k * 5.0)
        z1 = HAME_R + TRACE_DIR * ((k + 1) * 5.0)
        hbox(np.minimum(a, z1) - 0.4, np.maximum(a, z1) + 0.4, (78, 46, 26))
    return b


def _stand_boxes():
    """The back of the wagon from where the driver stands to hand the load down: the floor, the tailboard, the sides."""
    S = lambda p: seat_space(STAND_SEAT, p)
    b = []
    lo, hi = S((-wg.BOX_X + 1.0, wg.FLOOR_TOP, wg.BOX_BACK + wg.TAIL_LEN)), S((wg.BOX_X - 1.0, wg.FLOOR_TOP + 1.2, wg.BOX_BACK - 30.0))
    b.append((np.minimum(lo, hi), np.maximum(lo, hi), WOOD, True))
    for s in (-1, 1):
        lo, hi = S((s * wg.BOX_X, wg.SIDE_TOP, wg.BOX_BACK)), S((s * (wg.BOX_X - 1.0), wg.FLOOR_TOP, wg.BOX_BACK - 30.0))
        b.append((np.minimum(lo, hi), np.maximum(lo, hi), WOOD_D, True))
    # the back wheels, below the sides
    for s in (-1, 1):
        w = S((s * wg.WHEEL_X, -wg.RR, 0.0))
        b.append(((w[0] - 1.2, w[1] - wg.RR, w[2] - wg.RR), (w[0] + 1.2, w[1] + wg.RR, w[2] + wg.RR), WOOD_D, True))
    return b


def _wheel_boxes():
    """A wagon's back wheel standing across ahead (its middle plane at CW_WHEEL_Z), the hub to the left: the rim, the
    spokes and the nave sticking out toward the one hiding."""
    b = []
    hub = np.array([CW_HUB_X, 24.0 - wg.RR, CW_WHEEL_Z])
    w2 = wg.FELLOE_W / 2.0
    for k in range(28):
        a = 2.0 * math.pi * (k + 0.5) / 28.0
        q = hub + np.array([math.cos(a), math.sin(a), 0.0]) * (wg.RR - 0.8)
        b.append((q - np.array([1.4, 1.4, w2]), q + np.array([1.4, 1.4, w2]), WOOD_D, True))
    for k in range(7):
        a = math.pi * k / 7.0
        for j in range(-4, 5):
            q = hub + np.array([math.cos(a), math.sin(a), 0.0]) * (j * 2.6)
            b.append((q - np.array([0.6, 0.6, 0.55]), q + np.array([0.6, 0.6, 0.55]), WOOD, True))
    b.append((hub + np.array([-2.5, -2.5, -1.7]), hub + np.array([2.5, 2.5, 3.5]), (90, 70, 50), True))
    return b


STATION_BOXES.update({'horse_side': _horse_side_boxes, 'wagon_stand': _stand_boxes, 'wheel_cover': _wheel_boxes})
STATION_GROUND.update({'horse_side': 24.0, 'wagon_stand': seat_space(STAND_SEAT, (0.0, 0.0, 0.0))[1], 'wheel_cover': 24.0})

# ================================================================== the moments' sounds, and where they happen


def road_moment_specs():
    rg = tuple(GRIP['right_arm'])
    right = ('part', 'right_arm', rg)
    mouth = ('part', 'head', (0.0, -2.2, -4.4))
    fr0, _, _ = drive_pose(0.0)
    reins_mid = tuple(float(v) for v in (fist_world(fr0, 'right_arm') + TERRET_D['right']) / 2.0)
    return {
        'drive_urge': {'click': mouth, 'flick': ('point', reins_mid)},
        'drive_brake': {'haul': ('hands', 0.0), 'brake': ('point', tuple(float(v) for v in brake_grip(wg.BRAKE_ON)))},
        'jump_down': {'push': ('hands', 0.0), 'land': ('point', tuple(float(v) for v in JD_FEET))},
        'climb_up': {'grab': right, 'step': ('point', tuple(float(v) for v in HUB_STEP)), 'sit': ('point', (0.0, 24.0, 0.0))},
        'cut_loose': {'saw': ('point', tuple(float(v) for v in CL_CUT_AT)), 'cut': ('point', tuple(float(v) for v in CL_CUT_AT)),
                      'sheathe': ('part', 'body', tuple(float(v) for v in CL_BELT)),
                      'slap': ('point', tuple(float(v) for v in CL_SLAP_AT))},
        'hand_down': {'lift': ('hands', 2.0), 'handoff': ('hands', 2.0)},
        'cower': {'flinch': mouth},
        'catch_breath': {'gasp': mouth, 'cough': mouth},
        'recount': {'point': right, 'rush': ('hands', 0.0), 'sweep': ('hands', 0.0), 'clap': ('hands', 0.0)},
        'wounded': {'wince': mouth},
        'grumble': {'huff': mouth, 'sweep': right},
    }

# ================================================================== the previews' partner, placed per clip

PARTNER_PLACE = {'hand_down': HD_TAKER}
_MAIN = [None]


def install_preview_hooks():
    """hand_down's taker stands below on the road, not across a counter: the previews place each clip's partner by
    PARTNER_PLACE where it has one."""
    rc_, pp_ = ta.render_cell, ta.partner_place

    def render_cell(look, c, t, *a, **k):
        _MAIN[0] = c.name
        try:
            return rc_(look, c, t, *a, **k)
        finally:
            _MAIN[0] = None

    def partner_place():
        return PARTNER_PLACE[_MAIN[0]] if _MAIN[0] in PARTNER_PLACE else pp_()
    ta.render_cell = render_cell
    ta.partner_place = partner_place

# ================================================================== the game's half: CrewMoves.kt

CREW_MOVES_KT = os.path.join(os.path.dirname(HERE), '..', 'src/main/kotlin/io/github/vaspyyy/guildmark/client/caravan/CrewMoves.kt')


def kf(x):
    x = round(float(x), 3)
    if x == int(x):
        return f'{int(x)}f'
    return f'{x:.3f}'.rstrip('0').rstrip('.') + 'f'


def write_crew_moves(path=CREW_MOVES_KT):
    """The numbers the crew's clips are made to, for the game to place people by and to time the wagon and the horse
    to them."""
    bench_drop, tail_drop = -wg.BENCH_TOP, -wg.FLOOR_TOP
    lean = [brake_lever_lean(i / 2.0) for i in range(int(BRAKE_LEN * 2) + 1)]
    rows = []
    for i in range(0, len(lean), 10):
        rows.append('        ' + ', '.join(kf(v) for v in lean[i:i + 10]) + ',')
    out = f'''// Written by art/scripts/road_clips.py (write_crew_moves) when art/scripts/living_anims.py runs: run that rather
// than editing this by hand.
package io.github.vaspyyy.guildmark.client.caravan

/**
 * What the caravan crew's clips (art/scripts/road_clips.py; LivingSchedule has their props and moments) are made to,
 * for the game to place people by and to time the wagon and the horse to them. Distances are px (16 to a block) in
 * the clip's own frame as it starts: right, up and ahead of the townsperson's facing then; turns are degrees to their
 * right (clockwise seen from above).
 */
object CrewMoves {{
    /** How far the seats' tops are over the road: the bench's (jump_down and climb_up are made for it) and the tailboard's. */
    const val BENCH_DROP = {kf(bench_drop)}
    const val TAIL_DROP = {kf(tail_drop)}

    /**
     * jump_down, played on a seat on the bench (from drive's frame): ends standing on the road [JUMP_DOWN_RIGHT] px to
     * the right of the seat and [JUMP_DOWN_AHEAD] ahead, [BENCH_DROP] down, turned [JUMP_DOWN_TURN] (facing out from
     * the wagon's right side): put them there after it, unturned by the clip. The reins leave the hands at
     * [JUMP_DOWN_REINS] (show the horse's "reins_tied" then). From the tailboard, raise the body by BENCH_DROP -
     * TAIL_DROP over the ticks it is in the air ([JUMP_DOWN_AIR_FROM] to [JUMP_DOWN_LAND]), so it lands on the road.
     */
    const val JUMP_DOWN_RIGHT = {kf(-JD_PR_END[0])}
    const val JUMP_DOWN_AHEAD = {kf(-JD_PR_END[2])}
    const val JUMP_DOWN_TURN = {kf(JD_TURN)}
    const val JUMP_DOWN_REINS = 1.5f
    const val JUMP_DOWN_AIR_FROM = {kf(JD_OFF)}
    const val JUMP_DOWN_LAND = {kf(JD_LAND)}

    /**
     * climb_up: starts standing on the road [CLIMB_UP_RIGHT] px to the right of the bench seat it ends on and
     * [CLIMB_UP_BEHIND] behind it (beside the front wheel), [BENCH_DROP] down, turned [CLIMB_UP_TURN] (facing the
     * box's side), and ends sitting on the seat in drive's first frame, the reins in hand from [CLIMB_UP_REINS] (hide
     * "reins_tied" then). From the road to the tailboard, lower the body by BENCH_DROP - TAIL_DROP between
     * [CLIMB_UP_HAUL_FROM] and [CLIMB_UP_HAUL_TO].
     */
    const val CLIMB_UP_RIGHT = {kf(-CU_PR_START[0])}
    const val CLIMB_UP_BEHIND = {kf(CU_PR_START[2])}
    const val CLIMB_UP_TURN = {kf(CU_TURN)}
    const val CLIMB_UP_REINS = {kf(CU_REINS)}
    const val CLIMB_UP_HAUL_FROM = 3f
    const val CLIMB_UP_HAUL_TO = 10f

    /**
     * drive_brake: the brake lever's lean (WagonMesh.BRAKE_REST to BRAKE_ON, degrees) every half tick of the clip, as
     * the driver's hand hauls it on; it stays on after.
     */
    val BRAKE_LEAN = floatArrayOf(
{chr(10).join(rows)}
    )

    /** drive_urge: the click of the tongue and the flick of the reins (the horse leans into the collar after it). */
    const val URGE_CLICK = {kf(URGE_CLICK)}
    const val URGE_FLICK = {kf(URGE_FLICK)}

    /**
     * cut_loose is played standing by the horse's right shoulder facing it: at ([CUT_LOOSE_X], [CUT_LOOSE_Z]) in the
     * horse's model space (DraughtHorseMesh's: its origin under the middle of its body, facing -z), facing +x. The
     * right trace parts at [CUT_LOOSE_CUT] just behind its hame hook (show "trace_r_cut" and "trace_l_cut", lay the
     * wagon's traces on the road), the knife goes away at [CUT_LOOSE_SHEATHE], the slap on the haunch at
     * [CUT_LOOSE_SLAP] sends the horse off. It ends [CUT_LOOSE_LEFT] px to the left of where it started and
     * [CUT_LOOSE_AHEAD] ahead (by the haunch), facing the same way.
     */
    const val CUT_LOOSE_X = {kf(CL_X0)}
    const val CUT_LOOSE_Z = {kf(CL_Z0)}
    const val CUT_LOOSE_CUT = {kf(CL_CUT)}
    const val CUT_LOOSE_SHEATHE = {kf(CL_SHEATHE)}
    const val CUT_LOOSE_SLAP = {kf(CL_SLAP)}
    const val CUT_LOOSE_LEFT = {kf(CL_END[0])}
    const val CUT_LOOSE_AHEAD = {kf(-CL_END[2])}

    /**
     * hand_down is played standing on the wagon's floor at WagonMesh.STAND facing out; the one taking the load stands
     * on the road [HAND_DOWN_OUT] px out from there and [HAND_DOWN_DROP] down, facing back at the wagon, playing
     * receive from the same tick. The goods come off the load into the hands at [HAND_DOWN_LIFT] and go to the taker
     * at [HAND_DOWN_HANDOFF].
     */
    const val HAND_DOWN_OUT = {kf(HD_GAP)}
    const val HAND_DOWN_DROP = {kf(HD_DROP)}
    const val HAND_DOWN_LIFT = {kf(HD_LIFT)}
    const val HAND_DOWN_HANDOFF = {kf(HD_HANDOFF)}

    /**
     * cower (a loop) is played crouched by one of the wagon's back wheels (WagonMesh.REAR_RADIUS), outside it and
     * facing it square: the wheel's middle plane [COWER_WHEEL_AHEAD] px ahead, its hub [COWER_HUB_LEFT] px to the left,
     * so the peek from [COWER_PEEK_FROM] to [COWER_PEEK_TO] looks out round the wheel's right edge, the right hand on
     * the rim; the flinch at [COWER_FLINCH].
     */
    const val COWER_WHEEL_AHEAD = {kf(-CW_WHEEL_Z)}
    const val COWER_HUB_LEFT = {kf(CW_HUB_X)}
    const val COWER_PEEK_FROM = {kf(CW_PEEK[1][0])}
    const val COWER_PEEK_TO = {kf(CW_PEEK[4][0])}
    const val COWER_FLINCH = {kf(CW_FLINCH)}
}}
'''
    path = os.path.normpath(path)
    with open(path, 'w') as f:
        f.write(out)
    return path

# ================================================================== the registry

ROAD_CLIPS = ['drive', 'drive_urge', 'drive_brake', 'ride_tail', 'jump_down', 'climb_up', 'cut_loose', 'cower', 'hand_down',
              'catch_breath', 'recount', 'wounded', 'grumble']
BUILT = {}


def build(living):
    """Builds every road clip on living_anims' kit ([living]: the module), and teaches it their stations, moments,
    props' tracks, checks and CrewMoves.kt."""
    global la
    la = living
    if BUILT:
        return
    la.STATIONS.update(STATION_BOXES)
    la.GROUND_AT.update(STATION_GROUND)
    la.GROUND_COLOUR.update({k: ROAD for k in STATION_BOXES})
    install_preview_hooks()
    reins = []
    for fn in (build_drive, build_drive_urge, build_drive_brake):
        reins.append(fn()[1])
    build_ride_tail()
    reins.append(build_jump_down()[1])
    reins.append(build_climb_up()[1])
    for r in reins:
        r.install()
    build_cut_loose()
    build_hand_down()
    build_cower()
    build_catch_breath()
    build_recount()
    build_wounded()
    build_grumble()
    specs_ = la.moment_specs

    def moment_specs():
        out = specs_()
        out.update(road_moment_specs())
        return out
    la.moment_specs = moment_specs
    write_ = getattr(la, 'write_schedule', None)        # (the quick previews' loader may leave it out)

    def write_schedule(*a, **k):
        out = write_(*a, **k)
        print('wrote', os.path.relpath(write_crew_moves(), la.ROOT))
        return out
    if write_ is not None:
        la.write_schedule = write_schedule
    BUILT['reins'] = reins
    la.WANTED.extend(ROAD_CLIPS)
