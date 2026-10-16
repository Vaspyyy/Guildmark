"""
The clip a player plays while the sea monster's arm holds them: snatched off the deck, held high in a tentacle coiled round the
waist, squeezed, and never still, until it hurls them. Same kit and skeleton as the combat, companion and ship clips (animkit.py),
played by the game's CombatAnimator the way the lookout's clip is: over a standing player, as a loop (time modulo 60), at weight
1. Run from the repo root:

    python3 -B art/scripts/sea_anims.py src/main/resources/assets/guildmark [preview-dir [steve.png]]

Writes assets/guildmark/animations/sea/seized.json and, with a preview dir, sea/seized.gif (front three-quarter view) and
sea/seized_side.gif (from the player's left, the player facing right) there: the clip on the player model with a stand-in coil,
a thick tapering ring of slate-violet above and ivory beneath, drawn round the waist, and a strip under the picture that shows
where in the loop it is. steve.png dresses the model, else it is a plain deckhand. Prints what it checked and exits 1 if a check
fails. Everything is computed, so a rerun gives the same file.

    seized   60 ticks   rate 2   blend 0/0   root, torso, both arms, both legs   played as a loop, tick 0 = tick 60

No head track, so the player keeps looking where he looks. The legs are posed (dangling and kicking), so the player is not seated.

The player and the coil, in the player's own frame (model pixels, y down, the player faces -z, feet at y = 24). The coil's middle
line is a ring of radius COIL_R = 4.8 round the body's vertical axis at COIL_Y = 8 (the torso's corners are 4.47 out, so the
tentacle is sunk into the body and bites it), a little under one turn, tapering from a tube of radius 3 (y 5 to 11) at its thick
end, which lies 150 degrees round from the front toward the player's left, to 1.6 at its tip, with a gap of 60 degrees between
the two ends, which are rounded.
The real arm's coil is drawn by the game's own code: these constants only say where the clip puts hands and feet, and the
whole clip is solved against them, so if that coil sits somewhere else, change the COIL_ constants and run again. The legs hang
a px under the coil (hips at y = 12), the shoulders (y = 2) only 3 px above its top, which is why a straight arm can only lie on
its upper outer rim: the hands bear on the rim in front and at the sides, the shoulders hunched up (the shrug is the arm
track's offset, as the lookout's left arm lifts to shade its eyes) and the body leaning a little back to give them reach.

How the arms rest on the coil. Each arm is aimed (azimuth outward of straight ahead, elevation) and then lowered on to the
coil by a solver (arm_contact) until the nearest point of its length is 0.05 px in, so a hand that bears down lies on the
outer surface of the tube, never through it; whatever the shoulders, the torso and the root are doing, the arm finds the
coil again. At the end of every pose the arms are lowered off the head and raised off the coil as far as it takes
(kept_clear), the legs eased back off the coil (kept_legs), and everything is checked against the baked file.

What happens when (a seamless loop of 60 ticks, 3 s; the pieces below overlap, and none of them is on a metronome).
Always: the hands pry at the coil, each in cycles of its own length so that they are never in step (the right 14, 9, 9, 14 and
14 ticks long, the left 11, 12, 11, 10 and 16): a hand lands high on the rim, bears down and slides outward along it, slips,
lifts 1.2 to 2.6 px clear, swings back and lands again; the shoulders hunch with the strain and the arms shake a little; the
torso heaves, twists and rolls against the coil (bent forward 13 degrees at most and back 3, turned 11 either way, tipped 15 one
way and 6 the other), and the whole body sways less than a px on the root; the legs dangle and swing with the body, each at a
pace of its own (five and seven swings to the loop), kicking now and again out of step (the right at ticks 2.5, 9 and 11.2, 25,
33, 46.5, 51.5 and 57; the left at 4, 10.2, 15.5 and 19, 29.8, 40.5 and 43, 52.5 and 58.5; each kick snaps out, overshoots and
rings back, and a few go the other way).
Squeezes: the coil bites at ticks 10.5, 30.5 (a little lighter) and 50.5, twenty apart as the arm itself squeezes
(MaelgraspArm's SQUEEZE_EVERY), so wherever on its 60 ticks the clip is started the bites keep that beat: the body doubles over,
the hands are clamped down on the coil, the shoulders hunch hard and the legs cramp up; then it slowly lets go over ten ticks
and the body heaves back in a gasp.
Ticks 12.5 to 31.5, the right fist hammers: it winds up (12.5 to 15, the body leaning back and turning, the fist cocked at the
side of the head) and falls on the coil at tick 17, hard; is thrown up again and winds up (17.8 to 20.2) and falls at 21.5;
recoils, rises slowly (23.2 to 26.2) as the body arches back to the heaviest blow, which lands at 28; every blow jolts the torso
and whips the legs, and the arm is back at prying by 31.5, the left going on prying throughout.
Ticks 36 to 52.5, the left arm flails: it is flung off the coil at 36 to 37.8 and up and out (38 to 40), whipped out and back at
42.4, across the front at 44.6, out again at 46.6, comes down at 48.4 and is dragged back to the coil from 49.1, to land on it
at 50.5 as the third squeeze bites, and is prying again by 52.5; the torso rolls away (that shoulder up) and twists, the legs
thrash, the right hand bearing down harder.

What it checks (all on the file as written, read the way client/combat/Clip.kt reads it: samples a tick, rotations slerped, offsets
lerped). The file against ship/lookout.json key by key (the same top-level keys and kinds of value, bones the game knows,
no head, every number finite, every rotation unit); the loop's seam (tick 0 against tick 60, and no kink across it); the file
against this script's own forward kinematics of the poses it solved; the least clearance, eight times a tick, of each arm and
leg against the coil, each hand against the torso, the arms against the head and the legs, the arms against each other and
the legs against each other; how far the arms stand off the coil whenever a hand bears down on it; when the blows land and
the left arm is flung clear; the fastest turns; and how far the baked straight lines between samples stray from the motion.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy.interpolate import PchipInterpolator

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from animkit import (Clip, qeuler, qmul, qaxis, qmat, qslerp, unit, I, pose_clip, raster, ortho, T, Rq, EASE, Scene, PLAYER)

GROUP = 'sea'
NAME = 'seized'
LENGTH = 60.0                  # ticks: tick 0 = tick 60
RATE = 2                       # samples a tick, as every other looping clip

# ================================================================== the skeleton, as CombatAnimator has it

HIPS = np.array([0.0, 12.0, 0.0])
SHOULDER = {'right_arm': np.array([-5.0, 2.0, 0.0]), 'left_arm': np.array([5.0, 2.0, 0.0])}
HIP_JOINT = {'right_leg': np.array([-1.9, 12.0, 0.0]), 'left_leg': np.array([1.9, 12.0, 0.0])}
ARM_X = {'right_arm': -1.0, 'left_arm': 1.0}      # the arm's own middle line, off its joint
SIDE = {'right_arm': -1.0, 'left_arm': 1.0, 'right_leg': -1.0, 'left_leg': 1.0}     # x of the side the part is on
ARM_END = 10.0                                     # px from the joint to the arm's end face
# the boxes, in their part's own frame (animkit.PLAYER): torso, head, arm (x range by side), leg
TORSO_BOX = (np.array([-4.0, 0.0, -2.0]), np.array([4.0, 12.0, 2.0]))
HEAD_BOX = (np.array([-4.0, -8.0, -4.0]), np.array([4.0, 0.0, 4.0]))
ARM_BOX = {'right_arm': (np.array([-3.0, -2.0, -2.0]), np.array([1.0, 10.0, 2.0])),
           'left_arm': (np.array([-1.0, -2.0, -2.0]), np.array([3.0, 10.0, 2.0]))}
LEG_BOX = (np.array([-2.0, 0.0, -2.0]), np.array([2.0, 12.0, 2.0]))


def qinv(q):
    x, y, z, w = q
    return np.array([-x, -y, -z, w])


def smooth01(x):
    x = min(1.0, max(0.0, float(x)))
    return x * x * (3 - 2 * x)


def swing_q(a, b):
    """The shortest turn taking direction a to direction b (the arm's rest direction to where it points): no twist about
    the arm that the swing doesn't carry."""
    a, b = unit(a), unit(b)
    c = np.cross(a, b)
    q = np.array([c[0], c[1], c[2], 1.0 + float(np.dot(a, b))])
    n = float(np.linalg.norm(q))
    if n < 1e-9:
        raise ValueError('arm pointing straight back on itself')
    return q / n


def torso_q(pitch=0.0, yaw=0.0, roll=0.0):
    """The torso bent forward [pitch] degrees at the hips, turned [yaw] (positive turns to the character's right: the right
    shoulder goes back) and tipped [roll] (positive tips to the left: the right shoulder rises)."""
    return qmul(qeuler(y=yaw, z=roll), qeuler(x=pitch))


def wrapd(t, t0):
    """Signed ticks from t0 to t round the loop, in [-30, 30)."""
    return (t - t0 + LENGTH / 2) % LENGTH - LENGTH / 2


def wave(t, k, phase=0.0):
    """A sine with [k] whole cycles to the loop, so it closes on itself."""
    return math.sin(2 * math.pi * k * t / LENGTH + phase)


EASES = dict(EASE, slam=lambda a: 0.35 * a + 0.65 * a * a)     # 'slam': a fall that gathers speed but is under way from the first


def kf(t, keys):
    """A value at [t] (round the loop) from keys (time, value[, ease]): between two keys the later key's ease shapes the
    move, as animkit's clips do; before the first and after the last the value holds."""
    t = t % LENGTH
    if t <= keys[0][0]:
        return keys[0][1]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            span = b[0] - a[0]
            x = 0.0 if span <= 0 else (t - a[0]) / span
            e = EASES[b[2] if len(b) > 2 else 'inout'](x)
            return a[1] + (b[1] - a[1]) * e
    return keys[-1][1]


# ================================================================== the coil (a stand-in: the arm is drawn elsewhere)

COIL_Y = 8.0              # the coil's middle, in the player's frame
COIL_R = 4.8              # radius of the tube's centre line round the body's axis (the torso's corners are 4.47 out)
COIL_RHO = (3.0, 1.6)     # the tube's radius at its thick end and at its tapering tip (3 either side of y = 8: y 5 to 11)
COIL_START = 150.0        # where the thick end lies, degrees round from the front (positive: the player's left)
COIL_SPAN = 300.0         # how far round it goes, degrees, running through the front to the player's right; the rest is a gap


class Coil:
    """The tentacle coiled round the waist: a tube whose centre line runs a little under one turn round the body's vertical
    axis at COIL_R, tapering from COIL_RHO[0] to COIL_RHO[1], with its middle at COIL_Y, and a rounded cap at either end.
    Azimuth is degrees from the front (-z), positive toward the player's left (+x). sdf() is the (approximate) signed distance
    to its solid, negative inside."""

    def __init__(self, y=COIL_Y, r=COIL_R, rho=COIL_RHO, start=COIL_START, span=COIL_SPAN):
        self.y, self.r, self.rho0, self.rho1, self.start, self.span = y, r, rho[0], rho[1], start, span

    def radius(self, s):
        return self.rho0 + (self.rho1 - self.rho0) * np.clip(s, 0.0, 1.0) ** 0.8

    def centre(self, s):
        phi = np.radians(self.start - self.span * np.asarray(s, float))
        return np.stack([self.r * np.sin(phi), np.full(np.shape(phi), self.y), -self.r * np.cos(phi)], axis=-1)

    def sdf(self, P):
        P = np.atleast_2d(np.asarray(P, float))
        x, y, z = P[:, 0], P[:, 1], P[:, 2]
        phi = np.degrees(np.arctan2(x, -z))
        r = np.hypot(x, z)
        raw = ((self.start - phi) % 360.0) / self.span
        s = np.clip(raw, 0.0, 1.0)
        d = np.hypot(r - self.r, y - self.y) - self.radius(s)
        gap = raw > 1.0                       # in the gap between the two ends: the nearer of their rounded caps
        if gap.any():
            Pg = P[gap]
            d0 = np.sqrt(np.sum((Pg - self.centre(0.0)) ** 2, axis=1)) - self.radius(0.0)
            d1 = np.sqrt(np.sum((Pg - self.centre(1.0)) ** 2, axis=1)) - self.radius(1.0)
            d[gap] = np.minimum(d0, d1)
        return d

    def quads(self, n_s=170, n_a=16, light_dir=(0.35, -0.8, -0.5)):
        """The tube as small quads for the previews: (A, B, D, uv, light, texture); dark slate-violet on top, pale ivory on
        the underside, the ends rounded off as the solid has them."""
        L = unit(np.array(light_dir, float))
        texs = []
        for k in range(12):
            u = k / 11.0
            dark, pale = np.array([96.0, 82.0, 142.0]), np.array([232.0, 220.0, 198.0])
            tex = np.zeros((2, 2, 4))
            tex[..., :3] = dark + (pale - dark) * smooth01(u)
            tex[..., 3] = 255
            texs.append(tex)
        arc = math.radians(self.span) * self.r             # px of centre line to the unit of s
        cap0, cap1 = self.rho0 / arc, self.rho1 / arc      # the caps' lengths in s
        up = np.array([0.0, -1.0, 0.0])
        rings = []
        for i in range(n_s + 1):
            s = -cap0 + (1.0 + cap0 + cap1) * i / n_s
            taper = 1.0
            if s < 0.0:
                taper = math.sqrt(max(0.0, 1.0 - (s / cap0) ** 2))
            elif s > 1.0:
                taper = math.sqrt(max(0.0, 1.0 - ((s - 1.0) / cap1) ** 2))
            phi = math.radians(self.start - self.span * s)
            er = np.array([math.sin(phi), 0.0, -math.cos(phi)])
            rho = float(self.radius(s)) * taper
            c = np.array([self.r * er[0], self.y, self.r * er[2]])
            ring = []
            for j in range(n_a):
                a = 2 * math.pi * j / n_a
                n = math.cos(a) * er + math.sin(a) * up
                ring.append((c + rho * n, n, a))
            rings.append(ring)
        quads = []
        for i in range(n_s):
            for j in range(n_a):
                p00, n00, a00 = rings[i][j]
                p01, n01, _ = rings[i][(j + 1) % n_a]
                p10, _, _ = rings[i + 1][j]
                nn = unit(n00 + n01)
                light = 0.5 + 0.5 * max(0.0, float(np.dot(nn, L)))
                # the underside: the sections' lower, inner half
                ang = math.degrees(a00 + math.pi / n_a)
                u = smooth01(1.0 - abs((ang - 235.0 + 180.0) % 360.0 - 180.0) / 75.0)
                quads.append((p00, p01, p10, (0, 0, 1, 1), light, texs[int(round(11 * u))]))
        return quads


# ================================================================== the body: CombatAnimator's chain, with the root turned too

class State:
    """One instant of the clip: the root's offset and turn, the torso's turn, each arm's pivot offset and turn (relative to the
    torso) and each leg's turn (absolute)."""

    def __init__(self):
        self.root_p = np.zeros(3)
        self.root_q = I.copy()
        self.torso_q = I.copy()
        self.pa = {'right_arm': np.zeros(3), 'left_arm': np.zeros(3)}
        self.arm_q = {'right_arm': I.copy(), 'left_arm': I.copy()}
        self.leg_q = {'right_leg': I.copy(), 'left_leg': I.copy()}

    def copy(self):
        o = State()
        o.root_p, o.root_q, o.torso_q = self.root_p.copy(), self.root_q.copy(), self.torso_q.copy()
        o.pa = {k: v.copy() for k, v in self.pa.items()}
        o.arm_q = {k: v.copy() for k, v in self.arm_q.items()}
        o.leg_q = {k: v.copy() for k, v in self.leg_q.items()}
        return o


def root_offset(st):
    """The root's pivot as CombatAnimator writes it: the turn about the hips, then the offset."""
    return HIPS - qmat(st.root_q) @ HIPS + st.root_p


def pose_dict(st):
    """The pose animkit's pose_clip would give (weight 1): part -> (rotation, pivot), the root's included."""
    Rt = qmat(st.torso_q)

    def attached(pivot, off=None):
        return HIPS + Rt @ (np.asarray(pivot, float) + (np.zeros(3) if off is None else off) - HIPS)
    pose = {'body': (st.torso_q, attached((0, 0, 0))), 'head': (I.copy(), attached((0, 0, 0)))}
    for a in ('right_arm', 'left_arm'):
        pose[a] = (qmul(st.torso_q, st.arm_q[a]), attached(SHOULDER[a], st.pa[a]))
    for l in ('right_leg', 'left_leg'):
        pose[l] = (st.leg_q[l], HIP_JOINT[l].copy())
    pose['root'] = (st.root_q, root_offset(st))
    return pose


def part_matrix(pose, part):
    """The 4x4 taking a point of a part's own frame to the player's (coil's) frame."""
    qr, pr = pose['root']
    q, pv = pose[part]
    return T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q)


def to_world(M, pts):
    pts = np.asarray(pts, float)
    return pts @ M[:3, :3].T + M[:3, 3]


def to_local(M, pts):
    pts = np.asarray(pts, float)
    return (pts - M[:3, 3]) @ M[:3, :3]          # rotation matrices: the inverse is the transpose


def box_points(lo, hi, nx=3, ny=5, nz=3):
    """Points over the surface of a box (corners, edges, faces on a grid), in its own frame."""
    xs, ys, zs = np.linspace(lo[0], hi[0], nx), np.linspace(lo[1], hi[1], ny), np.linspace(lo[2], hi[2], nz)
    pts = []
    for x in xs:
        for y in ys:
            for z in zs:
                if x in (lo[0], hi[0]) or y in (lo[1], hi[1]) or z in (lo[2], hi[2]):
                    pts.append((x, y, z))
    return np.array(pts, float)


def sdf_box(p, lo, hi):
    """Signed distance of points [p] (in the box's own frame) to the box, negative inside."""
    c, h = (lo + hi) / 2, (hi - lo) / 2
    q = np.abs(p - c) - h
    return np.linalg.norm(np.maximum(q, 0.0), axis=-1) + np.minimum(np.max(q, axis=-1), 0.0)


ARM_PTS = {a: box_points(*ARM_BOX[a], nx=5, ny=13, nz=5) for a in ARM_BOX}     # all over each arm, in its own frame, a px apart
LEG_PTS = box_points(*LEG_BOX, nx=5, ny=13, nz=5)
TORSO_PTS = box_points(*TORSO_BOX, nx=9, ny=13, nz=5)
HEAD_PTS = box_points(*HEAD_BOX, nx=9, ny=9, nz=9)


def arm_points_world(st, side, q_rel=None, pa=None, lo_y=-9.0):
    """The arm's surface points (from [lo_y] px down it) in the coil's frame, for the state with the arm turned [q_rel] and
    its pivot moved [pa] instead, if given."""
    s = st
    if q_rel is not None or pa is not None:
        s = st.copy()
        if q_rel is not None:
            s.arm_q[side] = q_rel
        if pa is not None:
            s.pa[side] = pa
    pose = pose_dict(s)
    pts = ARM_PTS[side]
    pts = pts[pts[:, 1] >= lo_y]
    return to_world(part_matrix(pose, side), pts)


HAND_FROM = 4.0        # the hand is the arm from this far down it; above that is the shoulder, which may sink into the torso
ARM_FROM = 3.0         # the arm that must keep clear of things is from here down (the shoulder's cap is no limb)


def arm_part(side, from_y):
    """The arm from [from_y] px down it: its surface points and its box, in the arm's own frame."""
    lo, hi = ARM_BOX[side]
    lo = lo.copy()
    lo[1] = from_y
    pts = ARM_PTS[side]
    return pts[pts[:, 1] >= from_y - 1e-9], (lo, hi)


def box_clearance(Ma, part_a, Mb, part_b):
    """The least clearance (px, negative: overlapping) between two posed boxes, each given as (surface points, (lo, hi)) in its own
    frame, M the matrix to the player's: the points of each against the other's solid."""
    (pa, boxa), (pb, boxb) = part_a, part_b
    a_in_b = float(sdf_box(to_local(Mb, to_world(Ma, pa)), *boxb).min())
    b_in_a = float(sdf_box(to_local(Ma, to_world(Mb, pb)), *boxa).min())
    return min(a_in_b, b_in_a)


# ================================================================== the performance: what is going on when

# The three squeezes (tick, strength): the coil tightens, the body clenches and heaves, then gasps. Twenty ticks apart, as the arm
# (MaelgraspArm.SQUEEZE_EVERY) squeezes, so that a caller who wants the bite to meet the arm's can start the clip where it likes
# on the 60.
BEATS = [(10.5, 1.0), (30.5, 0.9), (50.5, 1.0)]
# The right fist's three blows: tick of the impact, how hard.
BLOWS = [(17.0, 0.7), (21.5, 0.8), (28.0, 1.0)]
# The right fist hammers from here to here (the pry cycle carries on underneath, unseen); the left arm flails from here to here.
HAMMER = (12.5, 31.5)
FLAIL = (36.0, 52.5)


def squeeze(tau):
    """How tight the coil is [tau] ticks after a squeeze begins to bite: a quick bite, a slow letting go."""
    if tau < -1.3 or tau > 12.0:
        return 0.0
    if tau < 0.3:
        return smooth01((tau + 1.3) / 1.6)
    return math.exp(-(tau - 0.3) / 3.2) * (1.0 - smooth01((tau - 7.0) / 5.0))


def gasp(tau):
    """The breath dragged in after a squeeze lets go: a hump from 2 to 11 ticks after."""
    if tau < 2.0 or tau > 11.0:
        return 0.0
    return math.sin(math.pi * (tau - 2.0) / 9.0) ** 2


def thud(tau):
    """A blow's jolt through the body [tau] ticks after the fist lands: forward as it comes, then back, ringing out in a few ticks
    (it begins at the blow, not before: the body feels the hit)."""
    if tau < 0.0 or tau > 9.0:
        return 0.0
    return smooth01(tau / 0.6) * math.exp(-tau / 1.7) * math.cos(1.9 * (tau - 0.3)) * (1.0 - smooth01((tau - 5.0) / 4.0))


def response(tau, omega, zeta):
    """The step response of a spring (natural frequency [omega] rad per tick, damping [zeta]) [tau] ticks after the step:
    what a limb does to a sudden command, a quick rise, an overshoot and a ringing that dies away."""
    if tau <= 0.0:
        return 0.0
    wd = omega * math.sqrt(1.0 - zeta * zeta)
    return 1.0 - math.exp(-zeta * omega * tau) * (math.cos(wd * tau) + zeta * omega / wd * math.sin(wd * tau))


def pulse(t, t0, hold, omega=1.15, zeta=0.42):
    """A command that comes on at [t0] and lasts [hold] ticks, as a limb's spring answers it."""
    tau = wrapd(t, t0)
    return response(tau, omega, zeta) - response(tau - hold, omega, zeta)


# ------------------------------------------------------------------ the hands at the coil: pry cycles

# Each hand works the coil in cycles of its own length, so they never fall in step: it lands high on the rim, bears down and
# slides outward along it (the arm sweeping from az_a to az_b, in degrees outward of straight ahead, pressing a little in), then
# slips, lifts clear of the coil and swings back to land again (the next cycle's az_a). (start, ticks, az_a, az_b, push, lift in px.)
# The cycles under the hammering and the flail are still there, unseen: the arm comes back to them.
CYCLES = {
    'right_arm': [(0.0, 14.0, 2.0, 40.0, 0.85, 2.2), (14.0, 9.0, 4.0, 34.0, 0.6, 1.6), (23.0, 9.0, 0.0, 32.0, 0.6, 1.6),
                  (32.0, 14.0, -3.0, 38.0, 1.0, 2.6), (46.0, 14.0, 6.0, 44.0, 0.95, 2.0)],
    'left_arm': [(53.0, 11.0, 8.0, 46.0, 0.8, 2.0), (4.0, 12.0, 0.0, 38.0, 0.95, 2.4), (16.0, 11.0, 5.0, 42.0, 0.85, 1.8),
                 (27.0, 10.0, -2.0, 36.0, 1.0, 2.2), (37.0, 16.0, 10.0, 40.0, 0.5, 1.2)],
}
SLIDE = 0.70           # the share of a cycle spent bearing down and sliding


def hand_cycle(side, t):
    """Where [side]'s hand is in its pry cycle at [t]: az (degrees outward of straight ahead, in the torso's frame), gap (px the
    arm is held off the coil: negative is pressed in), effort 0 to 1, and the tick into the cycle."""
    cyc = CYCLES[side]
    for i, (t0, dur, az_a, az_b, push, lift) in enumerate(cyc):
        x = (t - t0) % LENGTH
        if x < dur:
            nxt = cyc[(i + 1) % len(cyc)]
            u = x / dur
            if u < SLIDE:
                w = u / SLIDE
                az = az_a + (az_b - az_a) * w ** 1.7
                gap = -0.05 * push * smooth01(w / 0.35)
                e = push * (0.8 * smooth01(w / 0.45) + 0.2 * smooth01(w))
            else:
                ell = (u - SLIDE) / (1.0 - SLIDE)
                az = az_b + (nxt[2] - az_b) * smooth01(ell)
                gap = -0.05 * push * (1.0 - smooth01(ell * 1.6)) + lift * math.sin(math.pi * ell)
                e = push * (1.0 - smooth01(ell / 0.6))
            return dict(az=az, gap=gap, e=e, x=x, u=u)
    raise ValueError('the cycles do not cover tick %g' % t)


# ------------------------------------------------------------------ the fist that hammers

# a: how far the fist is wound up (0 on the coil, 1 cocked high); the three blows are at 17, 21.5 and 28
HAMMER_A = [(12.5, 0.0), (13.0, 0.0), (15.0, 0.92, 'out'), (15.4, 0.95, 'inout'), (17.0, 0.0, 'slam'), (17.8, 0.22, 'out'),
            (20.0, 0.62, 'inout'), (20.2, 0.64, 'inout'), (21.5, 0.0, 'slam'), (22.2, 0.10, 'out'), (23.2, 0.05, 'inout'),
            (26.2, 1.0, 'inout'), (26.4, 1.0, 'inout'), (28.0, 0.0, 'slam'), (30.2, -0.04, 'out'), (31.5, 0.0, 'inout')]
# where on the rim each blow lands (az), and where the fist cocks (az, elevation), by tick
HAMMER_HIT_AZ = [(12.5, 6.0), (17.0, 6.0, 'inout'), (21.5, 16.0, 'inout'), (28.0, 2.0, 'inout'), (31.5, 2.0, 'inout')]
HAMMER_UP_AZ = [(12.5, 30.0), (15.2, 34.0, 'inout'), (20.1, 40.0, 'inout'), (26.3, 30.0, 'inout'), (31.5, 30.0, 'inout')]
HAMMER_UP_EL = [(12.5, 50.0), (15.2, 56.0, 'inout'), (20.1, 40.0, 'inout'), (26.3, 66.0, 'inout'), (31.5, 50.0, 'inout')]


def hammer_layer(t):
    """The right fist's hammering at [t]: its weight over the pry cycle, how wound up it is, and where it lands and cocks."""
    w = smooth01((t - HAMMER[0]) / 1.5) * (1.0 - smooth01((t - (HAMMER[1] - 2.0)) / 2.0))
    a = kf(t, HAMMER_A) if HAMMER[0] <= t <= HAMMER[1] else 0.0
    return dict(w=w, a=a, hit_az=kf(t, HAMMER_HIT_AZ), up_az=kf(t, HAMMER_UP_AZ), up_el=kf(t, HAMMER_UP_EL))


# ------------------------------------------------------------------ the arm that flails

# (tick, az, elevation, twist about the arm) of the left arm as it is flung up and out, whips about overhead and is dragged back
FLAIL_WP = [(36.0, 50.0, -34.0, 0.0), (37.8, 66.0, 0.0, 25.0), (40.0, 96.0, 44.0, 70.0), (42.4, 122.0, 44.0, -40.0),
            (44.6, 68.0, 36.0, 40.0), (46.6, 110.0, 40.0, -30.0), (48.4, 74.0, 16.0, 10.0), (49.6, 60.0, 6.0, 0.0)]
FLAIL_SLAM = (49.1, 50.5, 54.0)         # the arm is dragged back down to the coil: from, impact, where on the rim
_FL = [PchipInterpolator([w[0] for w in FLAIL_WP], [w[k] for w in FLAIL_WP]) for k in (1, 2, 3)]


def flail_layer(t):
    """The left arm's flailing at [t]: weight over the pry cycle, the direction (az, elevation) and twist it is flung to, and how
    far it has been dragged back to the rim."""
    w = smooth01((t - FLAIL[0]) / 1.8) * (1.0 - smooth01((t - FLAIL_SLAM[1]) / 2.0))
    tt = min(max(t, FLAIL_WP[0][0]), FLAIL_WP[-1][0])
    az, el, tw = (float(f(tt)) for f in _FL)
    live = w * smooth01((t - 37.0) / 2.0)
    # a quick shake on top, the more the arm is up
    az += 6.0 * live * wave(t, 13, 0.4)
    el += 5.0 * live * wave(t, 11, 1.6)
    tw += 25.0 * live * wave(t, 9, 0.9)
    slam = 0.0 if t < FLAIL_SLAM[0] else EASES['slam'](min(1.0, (t - FLAIL_SLAM[0]) / (FLAIL_SLAM[1] - FLAIL_SLAM[0])))
    return dict(w=w, az=az, el=el, twist=tw, slam=slam, live=live)


# ------------------------------------------------------------------ arms: from a direction, or a rest on the coil

def arm_end(side):
    """The middle of the arm's end face, in the arm's own frame."""
    return np.array([ARM_X[side], ARM_END, 0.0])


def arm_dir(side, az, el):
    """The direction (in the torso's frame) from the arm's joint to the middle of its end: [az] degrees out from straight ahead
    (outward is the arm's own side), [el] degrees above level (-90 is hanging)."""
    a, e = math.radians(az), math.radians(el)
    return np.array([SIDE[side] * math.sin(a) * math.cos(e), -math.sin(e), -math.cos(a) * math.cos(e)])


def arm_q_of(side, az, el, twist=0.0):
    """The arm's turn (relative to the torso) that points it along [az]/[el], then turned [twist] degrees about itself."""
    q = swing_q(arm_end(side), arm_dir(side, az, el))
    if twist:
        q = qmul(q, qaxis((0, 1, 0), twist))
    return q


def arm_contact(st, side, az, gap, coil, twist=0.0, tol=0.01):
    """How the arm rests on the coil at [az]: lowered from above until the nearest point of its length (below the shoulder cap)
    is [gap] px off the coil's surface (negative: pressed that far in). Returns its elevation and turn."""
    def clearance(el):
        P = arm_points_world(st, side, q_rel=arm_q_of(side, az, el, twist), lo_y=3.0)
        return float(coil.sdf(P).min()) - gap
    top, bottom = 50.0, -88.0
    hi = top
    if clearance(hi) <= 0.0:
        return top, arm_q_of(side, az, top, twist), False
    el = hi
    lo = None
    while el > bottom:
        el -= 3.0
        if clearance(el) <= 0.0:
            lo = el
            break
        hi = el
    if lo is None:
        return bottom, arm_q_of(side, az, bottom, twist), False       # nothing there to rest on
    while hi - lo > tol:
        mid = 0.5 * (hi + lo)
        if clearance(mid) > 0.0:
            hi = mid
        else:
            lo = mid
    el = 0.5 * (hi + lo)
    return el, arm_q_of(side, az, el, twist), True


# ------------------------------------------------------------------ keeping the arms clear

HEAD_MARGIN = 0.35        # px an arm is kept off the head
COIL_SKIN = -0.05         # px an arm may sink into the coil: it is a living thing, and gives that much under a hand
UP = np.array([0.0, -1.0, 0.0])


def nudged(side, q, degrees):
    """The arm turn [q] with the arm swung [degrees] further up (negative: down) about the horizontal line across it."""
    d = qmat(q) @ arm_end(side)
    axis = np.cross(d, UP)
    n = float(np.linalg.norm(axis))
    if n < 1e-6:
        return q
    return qmul(qaxis(axis / n, degrees), q)


def head_gap(st, side, q):
    """The arm's clearance from the head (px) with it turned [q] (the head looking straight ahead)."""
    s = st.copy()
    s.arm_q[side] = q
    pose = pose_dict(s)
    return box_clearance(part_matrix(pose, side), arm_part(side, ARM_FROM), part_matrix(pose, 'head'), (HEAD_PTS, HEAD_BOX))


def coil_gap(st, side, q, coil):
    """The arm's clearance from the coil (px) with it turned [q]."""
    return float(coil.sdf(arm_points_world(st, side, q_rel=q, lo_y=ARM_FROM)).min())


def first_clear(f, want, limit, step, tol=0.02):
    """The least x in [0, limit] at which f(x) >= want, f(0) not being so: found by stepping, then bisection (f taken to rise with
    x). 0 if f(0) already is; [limit] if f never gets there."""
    if f(0.0) >= want:
        return 0.0
    bad, x = 0.0, 0.0
    while x < limit:
        x = min(limit, x + step)
        if f(x) >= want:
            break
        bad = x
    else:
        return limit
    good = x
    while good - bad > tol:
        mid = 0.5 * (good + bad)
        if f(mid) >= want:
            good = mid
        else:
            bad = mid
    return good


def swung_clear(f, side, q, want, direction, limit=70.0, step=3.0):
    """The arm turn [q] swung up (direction +1) or down (-1) as little as it takes for the clearance f(turn) to be [want] or
    better (or by [limit] degrees, if that is not enough)."""
    return nudged(side, q, direction * first_clear(lambda x: f(nudged(side, q, direction * x)), want, limit, step))


HIT_GAP = -0.05          # how far the fists bite into the coil as they land


def kept_clear(st, side, q, coil):
    """The arm turn [q] with the arm lowered off the head and raised off the coil as far as it takes (the hands must neither go
    through the coil nor the head)."""
    q = swung_clear(lambda r: head_gap(st, side, r), side, q, HEAD_MARGIN, -1)
    return swung_clear(lambda r: coil_gap(st, side, r, coil), side, q, COIL_SKIN, +1)


# ------------------------------------------------------------------ legs: hanging, kicking, out of step

# Kicks (tick, ticks held, forward degrees (negative: back), outward degrees, spring omega, damping): each is a command the leg's
# own spring answers, so it snaps out, overshoots and rings back. The two legs' are kept well apart.
KICKS = {
    'right_leg': [(2.5, 1.6, 26.0, 6.0, 1.2, 0.40), (9.0, 1.2, -14.0, 10.0, 1.3, 0.45), (11.2, 1.4, 22.0, 6.0, 1.25, 0.42),
                  (25.0, 2.0, 30.0, 8.0, 1.1, 0.40), (33.0, 1.4, 22.0, 14.0, 1.25, 0.42), (46.5, 2.2, 34.0, 10.0, 1.1, 0.38),
                  (51.5, 1.0, -10.0, 6.0, 1.4, 0.50), (57.0, 1.2, 16.0, 6.0, 1.3, 0.45)],
    'left_leg': [(4.0, 1.2, 18.0, 6.0, 1.3, 0.45), (10.2, 1.6, 28.0, 10.0, 1.15, 0.40), (15.5, 1.2, 20.0, 6.0, 1.3, 0.45),
                 (19.0, 1.0, 14.0, 8.0, 1.4, 0.45), (29.8, 2.2, 32.0, 12.0, 1.1, 0.40), (40.5, 1.0, -12.0, 8.0, 1.35, 0.45),
                 (43.0, 1.4, 26.0, 6.0, 1.2, 0.40), (52.5, 1.8, 30.0, 12.0, 1.15, 0.38), (58.5, 1.2, 14.0, 5.0, 1.3, 0.45)],
}


def leg_q_of(side, fwd, out, toe):
    """A leg's turn (absolute, under the root): swung [fwd] degrees forward from hanging, splayed [out] degrees outward, the toes
    turned [toe] degrees outward about the leg itself."""
    s = SIDE[side]
    return qmul(qmul(qaxis((0, 0, 1), -s * out), qaxis((1, 0, 0), -fwd)), qaxis((0, 1, 0), -s * toe))


def leg_style(side, t, sq, gs, th=0.0, thrash=0.0):
    """Forward, outward and toe angles (degrees) of [side]'s leg at [t]: a loose dangle that swings with the body at a pace of its
    own, the kicks, a cramp at each squeeze, a throw back at each gasp, a whip as each blow jolts the body and, while the arm
    flails, a frantic thrashing at a pace of its own."""
    if side == 'right_leg':
        env = 0.55 + 0.45 * wave(t, 3, 1.0)
        ph = 2 * math.pi * 5 * t / LENGTH + 0.9 * wave(t, 2, 0.4)
        fwd = 4.0 + 12.0 * env * math.sin(ph)
        out = 7.0 + 5.0 * env * math.sin(ph - 1.3) + 2.5 * wave(t, 3, 0.5)
        toe = 7.0 + 5.0 * wave(t, 3, 0.2)
        fwd += 9.0 * thrash * wave(t, 11, 0.6)
        out += 3.0 * thrash * wave(t, 11, 2.0)
    else:
        env = 0.5 + 0.5 * wave(t, 2, 2.5)
        ph = 2 * math.pi * 7 * t / LENGTH + 0.7 * wave(t, 3, 2.0)
        fwd = 3.0 + 10.0 * env * math.sin(ph)
        out = 7.5 + 4.0 * env * math.sin(ph + 1.0) + 2.0 * wave(t, 4, 1.4)
        toe = 6.5 + 5.0 * wave(t, 4, 1.1)
        fwd += 10.0 * thrash * wave(t, 8, 2.3)
        out += 3.0 * thrash * wave(t, 8, 0.4)
    for (t0, hold, f, o, om, ze) in KICKS[side]:
        k = pulse(t, t0, hold, om, ze)
        fwd += f * k
        out += o * k
    fwd += 20.0 * sq - 8.0 * gs - 7.0 * th
    out -= 4.0 * sq
    return fwd, out, toe


# ------------------------------------------------------------------ keeping the legs clear

LEG_SKIN = 0.05           # px a leg is kept off the coil


def leg_matrix(st, side, q):
    """The leg's matrix to the player's frame with it turned [q], under the state's root."""
    return T(*root_offset(st)) @ Rq(st.root_q) @ T(*HIP_JOINT[side]) @ Rq(q)


def kept_legs(st, legs, coil):
    """[legs] ((forward, outward, toe) degrees of each) with each leg's swing eased back as far as it takes for it not to enter the
    coil above it. Returns the turns."""
    turns = {}
    for l, (fwd, out, toe) in legs.items():
        def off_coil(k, l=l, fwd=fwd, out=out, toe=toe):
            q = leg_q_of(l, fwd * (1.0 - k), out, toe)
            return float(coil.sdf(to_world(leg_matrix(st, l, q), LEG_PTS)).min())
        k = first_clear(off_coil, LEG_SKIN, 1.0, 0.05, tol=0.003)
        turns[l] = leg_q_of(l, fwd * (1.0 - k), out, toe)
    return turns


# ------------------------------------------------------------------ the whole body at a tick

def rotvec_q(r_deg):
    r = np.asarray(r_deg, float)
    a = float(np.linalg.norm(r))
    if a < 1e-9:
        return I.copy()
    return qaxis(r / a, a)


def lerp(a, b, k):
    return a + (b - a) * k


def performance(t):
    """Everything that is happening at [t] that needs no solving: the hands' cycles, the hammering and flailing, the squeezes and
    blows through the body, and the efforts that drive the torso, shoulders and legs."""
    hr, hl = hand_cycle('right_arm', t), hand_cycle('left_arm', t)
    ham, fl = hammer_layer(t), flail_layer(t)
    a = max(0.0, ham['a']) * ham['w']
    e_ham = 0.15 + 0.75 * (1.0 - min(1.0, max(0.0, ham['a']))) ** 1.5
    e_r = lerp(hr['e'], e_ham, ham['w'])
    e_l = lerp(hl['e'], 0.35, fl['w'])
    e_r = min(1.0, e_r + 0.35 * fl['w'])
    sq = sum(s * squeeze(wrapd(t, tb)) for tb, s in BEATS)
    gs = sum(s * gasp(wrapd(t, tb)) for tb, s in BEATS)
    for h in (hr, hl):                # as the coil bites it clamps the hands down on to it: they cannot lift clear
        h['gap'] = h['gap'] if h['gap'] <= 0.0 else h['gap'] * (1.0 - 0.85 * min(1.0, sq))
    th = sum(s * thud(wrapd(t, tb)) for tb, s in BLOWS)
    th_f = thud(wrapd(t, FLAIL_SLAM[1]))
    p = dict(hr=hr, hl=hl, ham=ham, fl=fl, e_r=e_r, e_l=e_l, sq=sq, gs=gs, th=th, th_f=th_f, a=a)
    d = e_r - e_l
    push = (e_r + e_l) / 2
    p['pitch'] = (6.0 + 3.0 * wave(t, 2, 0.7) + 1.4 * wave(t, 5, 2.1) + 0.7 * wave(t, 9, 0.4) + 8.5 * sq - 4.5 * gs
                  + 3.2 * th + 1.8 * th_f - 4.0 * a - 3.0 * fl['w'] - 5.0 * push)
    p['yaw'] = (5.0 * wave(t, 4, 1.0) + 2.5 * wave(t, 7, 0.3) + 1.2 * wave(t, 11, 2.2) - 5.5 * d + 6.0 * a - 2.4 * th
                + 5.0 * fl['live'] * wave(t, 5, 0.8))
    p['roll'] = 4.0 * wave(t, 3, 2.4) + 2.0 * wave(t, 6, 0.0) - 4.0 * d - 7.5 * fl['w']
    p['root_p'] = np.array([0.35 * wave(t, 3, 1.1) + 0.25 * wave(t, 5, 2.0),
                            0.45 + 0.3 * wave(t, 2, 2.2) + 0.12 * wave(t, 9, 1.0) - 0.55 * sq - 0.7 * push,
                            0.3 * wave(t, 2, 0.2) - 0.2 * th])
    p['root_q'] = qeuler(x=1.0 * wave(t, 4, 2.0), y=2.0 * wave(t, 2, 1.0) + 0.9 * wave(t, 5, 0.5),
                         z=1.8 * wave(t, 3, 0.3) + 0.8 * wave(t, 7, 1.5))
    p['legs'] = {l: leg_style(l, t, sq, gs, th + th_f, fl['w']) for l in ('right_leg', 'left_leg')}
    p['hunch'] = {}
    for side, e in (('right_arm', e_r), ('left_arm', e_l)):
        h = min(1.15, 0.2 + 0.8 * e + 0.35 * sq)
        breath = 0.18 * wave(t, 9, 0.8 if side == 'right_arm' else 2.0)
        p['hunch'][side] = np.array([-SIDE[side] * 0.3 * h, -1.5 * h + breath, -0.5 * h])
    return p


def tremble(side, t, e):
    """The arm's shake under strain: a few tenths of a degree about all three axes, a few ticks to the cycle."""
    k = 0.4 + 0.6 * e
    ph = 0.0 if side == 'right_arm' else 1.9
    return rotvec_q([0.8 * k * wave(t, 16, ph + 0.3), 0.6 * k * wave(t, 14, ph + 1.1), 0.7 * k * wave(t, 19, ph + 2.2)])


def solve_state(t, coil, perf=None):
    """The body at [t]: the performance's torso, root, shoulders and legs, and each arm resting on the coil (or hammering it, or
    flung up in the air) with the pose that comes of the torso's and shoulders' movement; then the arms and legs kept clear of
    the coil and the head."""
    p = perf or performance(t)
    st = State()
    st.root_p, st.root_q = p['root_p'], p['root_q']
    st.torso_q = torso_q(p['pitch'], p['yaw'], p['roll'])
    for side in ('right_arm', 'left_arm'):
        st.pa[side] = p['hunch'][side]
    # the pry candidates (always there), then what overrides them
    cyc = {'right_arm': p['hr'], 'left_arm': p['hl']}
    for side in ('right_arm', 'left_arm'):
        el, q, ok = arm_contact(st, side, cyc[side]['az'], cyc[side]['gap'], coil)
        st.arm_q[side] = q
    ham, fl = p['ham'], p['fl']
    if ham['w'] > 0.0:
        _, q_hit, _ = arm_contact(st, 'right_arm', ham['hit_az'], HIT_GAP, coil)
        q_up = arm_q_of('right_arm', ham['up_az'], ham['up_el'])
        q_ham = qslerp(q_hit, q_up, min(1.0, max(0.0, ham['a'])))
        st.arm_q['right_arm'] = qslerp(st.arm_q['right_arm'], q_ham, ham['w'])
    if fl['w'] > 0.0:
        q_fl = arm_q_of('left_arm', fl['az'], fl['el'], fl['twist'])
        if fl['slam'] > 0.0:
            _, q_slam, _ = arm_contact(st, 'left_arm', FLAIL_SLAM[2], HIT_GAP, coil)
            q_fl = qslerp(q_fl, q_slam, fl['slam'])
        st.arm_q['left_arm'] = qslerp(st.arm_q['left_arm'], q_fl, fl['w'])
    for side, e in (('right_arm', p['e_r']), ('left_arm', p['e_l'])):
        st.arm_q[side] = kept_clear(st, side, qmul(tremble(side, t, e), st.arm_q[side]), coil)
    legs = kept_legs(st, p['legs'], coil)
    for l in legs:
        st.leg_q[l] = legs[l]
    return st


# ================================================================== what touches what

def clearances(pose, coil):
    """The least clearance (px, negative: inside) between parts of the posed body and what they must not enter: each arm and each
    leg against the coil, each hand against the torso, the arms against the head and the legs, the arms against each other and
    the legs against each other. The head is taken looking straight ahead. (The two legs overlap by 0.2 px at rest, as the
    game's player model has them.)"""
    names = ('body', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg')
    M = {p: part_matrix(pose, p) for p in names}
    arm = {a: arm_part(a, ARM_FROM) for a in ('right_arm', 'left_arm')}
    hand = {a: arm_part(a, HAND_FROM) for a in ('right_arm', 'left_arm')}
    leg = (LEG_PTS, LEG_BOX)
    out = {}
    for a in ('right_arm', 'left_arm'):
        out[a + '/coil'] = float(coil.sdf(to_world(M[a], arm[a][0])).min())
    for l in ('right_leg', 'left_leg'):
        out[l + '/coil'] = float(coil.sdf(to_world(M[l], LEG_PTS)).min())
    for a in ('right_arm', 'left_arm'):
        out[a + '/torso'] = box_clearance(M[a], hand[a], M['body'], (TORSO_PTS, TORSO_BOX))
        out[a + '/head'] = box_clearance(M[a], arm[a], M['head'], (HEAD_PTS, HEAD_BOX))
        for l in ('right_leg', 'left_leg'):
            out[a + '/' + l] = box_clearance(M[a], arm[a], M[l], leg)
    out['arms'] = box_clearance(M['right_arm'], hand['right_arm'], M['left_arm'], hand['left_arm'])
    out['legs'] = box_clearance(M['right_leg'], leg, M['left_leg'], leg)
    return out


# ================================================================== the clip

def sample_states(coil, per_tick=RATE):
    """The body at every sample of the loop (the closing one is the first again, exactly)."""
    n = int(round(LENGTH * per_tick))
    states = [solve_state(i / per_tick, coil) for i in range(n)]
    states.append(states[0])
    return states


def clip_of(states, per_tick=RATE):
    c = Clip(NAME, LENGTH, blend_in=0.0, blend_out=0.0, rate=per_tick)
    for i, st in enumerate(states):
        t = i / per_tick
        c.key('root', t, st.root_q, pos=st.root_p, ease='linear')
        c.key('torso', t, st.torso_q, ease='linear')
        for a in ('right_arm', 'left_arm'):
            c.key(a, t, st.arm_q[a], pos=st.pa[a], ease='linear')
        for l in ('right_leg', 'left_leg'):
            c.key(l, t, st.leg_q[l], ease='linear')
    return c


def build():
    """The clip, the states it was baked from and the coil it was solved against."""
    coil = Coil()
    states = sample_states(coil)
    return clip_of(states), states, coil


# ================================================================== the checks: the file as the game reads it

GAME_BONES = ('root', 'torso', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg')
REFERENCE = os.path.join('animations', 'ship', 'lookout.json')       # a clip the game already plays, to compare the file with


def angle_between(q0, q1):
    """The angle (degrees) between two rotations, as quaternions that need not be exactly unit length (a file's are rounded)."""
    c = abs(float(np.dot(q0, q1))) / (float(np.linalg.norm(q0)) * float(np.linalg.norm(q1)))
    return math.degrees(2 * math.acos(min(1.0, c)))


def read_clip(path):
    """The file read the way the game reads it (client/combat/Clip.kt): each bone a track of samples a tick over [rate], the
    rotations slerped and the offsets lerped between neighbours. Returns it as a kit Clip, and the parsed JSON."""
    with open(path) as f:
        d = json.load(f)
    c = Clip(NAME, d['length'], blend_in=d['blend_in'], blend_out=d['blend_out'], rate=d['rate'])
    n = int(math.floor(d['length'] * d['rate'])) + 1
    for bone, track in d['bones'].items():
        rot = np.array(track['rot'], float).reshape(n, 4)
        pos = np.array(track['pos'], float).reshape(n, 3) if 'pos' in track else None
        for i in range(n):
            c.key(bone, i / d['rate'], rot[i], pos=None if pos is None else pos[i], ease='linear')
    return c, d


def check_file(d, ref, report, failures):
    """The file against ship/lookout.json, key by key: the same top-level keys and kinds of value, only bones the game knows (and
    no head), every bone a rotation track of four numbers a sample (and an offset track of three), every number finite and every
    rotation a unit quaternion."""
    problems = []
    if list(d) != list(ref):
        problems.append(f'top-level keys {list(d)} against {list(ref)}')
    for k in ref:
        if k in d and type(d[k]) is not type(ref[k]):
            problems.append(f'{k} is a {type(d[k]).__name__}, as a {type(ref[k]).__name__} in the reference')
    n = int(math.floor(d['length'] * d['rate'])) + 1
    count, worst_norm = 0, 0.0
    for bone, track in d['bones'].items():
        if bone not in GAME_BONES or bone == 'head':
            problems.append(f'bone {bone}')
        extra = set(track) - {'rot', 'pos', 'mode'}
        if 'rot' not in track or extra:
            problems.append(f'{bone}: keys {sorted(track)}')
            continue
        if len(track['rot']) != 4 * n or len(track.get('pos', [0.0] * (3 * n))) != 3 * n:
            problems.append(f'{bone}: {len(track["rot"])} rotation numbers for {n} samples')
            continue
        rot = np.array(track['rot'], float).reshape(n, 4)
        values = np.concatenate([rot.ravel(), np.array(track.get('pos', []), float).ravel()])
        count += values.size
        if not np.isfinite(values).all():
            problems.append(f'{bone}: not every number is finite')
        worst_norm = max(worst_norm, float(np.abs(np.linalg.norm(rot, axis=1) - 1.0).max()))
    if worst_norm > 2e-3:
        problems.append(f'a rotation is {worst_norm:.4f} off unit length')
    ref_bones = {b: sorted(t) for b, t in ref['bones'].items()}
    mine = {b: sorted(t) for b, t in d['bones'].items()}
    kinds = sorted({tuple(v) for v in mine.values()} - {tuple(v) for v in ref_bones.values()})
    if kinds:
        problems.append(f'bone entries {kinds} the reference does not have')
    report.append(f"file: {len(d['bones'])} bones, {n} samples each, {count} numbers, all finite, rotations within {worst_norm:.5f} of "
                  f"unit length; top-level keys {list(d)} and entry kinds as in {os.path.basename(REFERENCE)}"
                  + ('' if not problems else '   PROBLEMS: ' + '; '.join(problems)))
    failures.extend(problems)


def endpoints(pose):
    """Where the arms' ends, the feet and the head's middle are, in the player's frame."""
    pts = {}
    for a in ('right_arm', 'left_arm'):
        pts[a] = to_world(part_matrix(pose, a), np.array([[ARM_X[a], ARM_END, 0.0]]))[0]
    for l in ('right_leg', 'left_leg'):
        pts[l] = to_world(part_matrix(pose, l), np.array([[0.0, 12.0, 0.0]]))[0]
    pts['head'] = to_world(part_matrix(pose, 'head'), np.array([[0.0, -4.0, 0.0]]))[0]
    pts['torso'] = to_world(part_matrix(pose, 'body'), np.array([[0.0, 0.0, 0.0]]))[0]
    return pts


def rotvec(q):
    """The rotation [q] as a vector (degrees): its axis times its angle."""
    q = np.asarray(q, float) / float(np.linalg.norm(q))
    if q[3] < 0:
        q = -q
    s = float(np.linalg.norm(q[:3]))
    return np.zeros(3) if s < 1e-12 else q[:3] / s * math.degrees(2 * math.atan2(s, q[3]))


def check_seam(clip, report, failures):
    """The loop closes: at the loop's end every bone is where it was at its start (the clip's last sample is its first), and what
    each bone is doing is going on across the seam: the change of its turn from the sample before to the sample after is
    compared with the same change at every other sample of the bone (the share of them it is larger than)."""
    jump, rank = 0.0, 0.0
    for bone, keys in clip.keys.items():
        qs = [k[1] for k in keys]
        jump = max(jump, angle_between(qs[0], qs[-1]))
        if keys[0][2] is not None:
            jump = max(jump, float(np.linalg.norm(keys[0][2] - keys[-1][2])))
        steps = [rotvec(qmul(qs[i + 1], qinv(qs[i] / np.linalg.norm(qs[i])))) for i in range(len(qs) - 1)]   # sample to sample
        change = [float(np.linalg.norm(steps[i] - steps[i - 1])) for i in range(len(steps))]     # change[0]: across the seam
        rank = max(rank, float(np.mean([c < change[0] for c in change[1:]])))
    p0, p1 = endpoints(pose_clip(clip, 0.0)), endpoints(pose_clip(clip, clip.length))
    far = max(float(np.linalg.norm(p0[k] - p1[k])) for k in p0)
    report.append(f"loop seam (tick 0 against tick {clip.length:g}): every bone's rotation and offset differ by {jump:.4f} (degrees, "
                  f"px), the arms' ends, feet, head and torso by {far:.4f} px; across the seam each bone's change of turn is "
                  f"larger than {rank * 100:.0f}% of its changes elsewhere at worst (a kink would be near 100%)")
    if jump > 1e-6 or far > 0.01 or rank > 0.95:
        failures.append('the loop does not close')


def check_poses(file_clip, states, report, failures):
    """The file, read as the game reads it (the kit's pose_clip) against this script's own forward kinematics of the states it
    baked: the arms' ends, the feet and the head, at every sample. They differ only by the file's rounding."""
    worst = 0.0
    for i, st in enumerate(states):
        a = endpoints(pose_dict(st))
        b = endpoints(pose_clip(file_clip, i / file_clip.rate))
        worst = max(worst, max(float(np.linalg.norm(a[k] - b[k])) for k in a))
    report.append(f"the file against the solved poses (the kit's CombatAnimator chain against mine): worst {worst:.4f} px at the "
                  f"arms' ends, feet, head and shoulders")
    if worst > 0.01:
        failures.append('the file does not give the poses that were solved')


def check_fidelity(file_clip, coil, report):
    """Two samples a tick, straight lines between: how far the file's motion is, midway between samples, from the motion the
    samples were taken from (the arms' ends and the feet, px)."""
    worst = {}
    n = int(round(file_clip.length * file_clip.rate))
    for i in range(n):
        t = (i + 0.5) / file_clip.rate
        a = endpoints(pose_dict(solve_state(t, coil)))
        b = endpoints(pose_clip(file_clip, t))
        for k in a:
            d = float(np.linalg.norm(a[k] - b[k]))
            if k not in worst or d > worst[k][0]:
                worst[k] = (d, t)
    report.append('between samples (the baked straight lines against the motion): ' + ', '.join(
        f"{k.replace('_arm', ' arm').replace('_leg', ' leg')} {d:.2f} px at {t:g}" for k, (d, t) in worst.items() if k != 'torso'))
    return worst


# what each pair of things may come to: the least clearance (px), where negative is inside. Arms may sink into the coil a
# hair (it gives); the legs overlap each other 0.2 px at rest in the game's model.
LIMITS = {'arm/coil': -0.15, 'leg/coil': 0.0, 'arm/head': 0.0, 'hand/torso': -0.1, 'arm/leg': 0.0, 'hand/hand': 0.0, 'legs': -0.5}


def check_clear(file_clip, coil, report, failures, per_tick=8):
    """What touches what, on the file as the game plays it, at [per_tick] times a tick: each arm and leg against the coil, each
    hand against the torso, the arms against the head and the legs, the arms against each other, the legs against each other."""
    kind = lambda k: ('arm/coil' if k.endswith('_arm/coil') else 'leg/coil' if k.endswith('_leg/coil') else
                      'hand/torso' if k.endswith('/torso') else 'arm/head' if k.endswith('/head') else
                      'hand/hand' if k == 'arms' else 'legs' if k == 'legs' else 'arm/leg')
    worst = {}
    n = int(round(file_clip.length * per_tick))
    for i in range(n):
        t = file_clip.length * i / n
        for k, v in clearances(pose_clip(file_clip, t), coil).items():
            kk = kind(k)
            if kk not in worst or v < worst[kk][0]:
                worst[kk] = (v, t, k)
    rows = []
    for kk, lim in LIMITS.items():
        v, t, k = worst[kk]
        ok = v >= lim - 1e-9
        rows.append(f"{kk} {v:+.2f} at {t:g}" + ('' if ok else f' (below {lim:+.2f}: {k})'))
        if not ok:
            failures.append(f'{kk} clearance {v:.2f} at tick {t:g}')
    report.append(f'least clearance in px, {per_tick} times a tick (no hand through the torso, no limb into the coil or the head): '
                  + '; '.join(rows))
    return worst


def check_hands(file_clip, coil, report, failures):
    """The hands at the coil: whenever a hand is bearing down on it (mid-cycle, effort above half, not hammering or flailing) how
    far its arm stands off the coil's surface (px, negative: pressed in), as a share of those moments: the arms rest on the coil."""
    gaps = {'right_arm': [], 'left_arm': []}
    n = int(round(file_clip.length * 4))
    for i in range(n):
        t = file_clip.length * i / n
        p = performance(t)
        pose = pose_clip(file_clip, t)
        for side, h, busy in (('right_arm', p['hr'], p['ham']['w'] > 0.0), ('left_arm', p['hl'], p['fl']['w'] > 0.0)):
            if busy or h['e'] < 0.5 or h['u'] >= SLIDE:
                continue
            pts = to_world(part_matrix(pose, side), arm_part(side, ARM_FROM)[0])
            gaps[side].append(float(coil.sdf(pts).min()))
    rows = []
    for side, g in gaps.items():
        g = np.array(g)
        rows.append(f"{side.replace('_', ' ')}: {len(g)} moments, {np.percentile(g, 5):+.2f} to {np.percentile(g, 95):+.2f} px "
                    f"(middle {np.median(g):+.2f}, worst {g.min():+.2f}..{g.max():+.2f})")
        if g.max() > 0.35 or g.min() < LIMITS['arm/coil']:
            failures.append(f'{side} does not rest on the coil')
    report.append('hands bearing down on the coil, arm off its surface: ' + '; '.join(rows))


def check_speeds(file_clip, report):
    """How fast each bone turns (degrees a tick, between neighbouring samples): the most, and when."""
    rows = []
    for bone, keys in file_clip.keys.items():
        sp = [angle_between(keys[i][1], keys[i + 1][1]) * file_clip.rate for i in range(len(keys) - 1)]
        i = int(np.argmax(sp))
        rows.append(f"{bone} {sp[i]:.0f} at {(i + 0.5) / file_clip.rate:g} (mean {np.mean(sp):.1f})")
    report.append('fastest turns, degrees a tick: ' + ', '.join(rows))


def check_events(file_clip, coil, report, failures):
    """That the clip does what it is said to do, when: the right fist's three blows (the arm's end, falling on to the coil from
    well off it: the ticks it arrives), the squeezes (the torso's bend about its mean) and the flail (the left arm's end, high
    off the coil)."""
    ts = np.arange(0.0, file_clip.length, 0.25)
    gap = {}
    for side in ('right_arm', 'left_arm'):
        pts = arm_part(side, ARM_FROM)[0]
        gap[side] = np.array([float(coil.sdf(to_world(part_matrix(pose_clip(file_clip, t), side), pts)).min()) for t in ts])
    landed = []
    for i in range(1, len(ts)):
        g = gap['right_arm']
        if g[i] < 0.4 <= g[i - 1] and HAMMER[0] < ts[i] < HAMMER[1] + 1.0 and g[max(0, i - 6):i].max() > 3.0:
            landed.append(float(ts[i]))
    up = ts[gap['left_arm'] > 4.0]
    flail = (float(up.min()), float(up.max())) if len(up) else None
    report.append(f"events: the right fist lands on the coil from well above it at ticks {', '.join('%.2f' % x for x in landed)} (blows at "
                  f"{', '.join('%g' % b[0] for b in BLOWS)}); the left arm is flung more than 4 px clear of the coil from tick "
                  f"{flail[0]:g} to {flail[1]:g}; squeezes bite at {', '.join('%g' % b[0] for b in BEATS)}")
    if len(landed) != len(BLOWS) or any(abs(a - b[0]) > 1.0 for a, b in zip(landed, BLOWS)):
        failures.append('the blows do not land where they are meant to')


def run_checks(clip, states, coil, path, assets):
    report, failures = [], []
    file_clip, d = read_clip(path)
    reference = os.path.join(assets, REFERENCE)
    if os.path.exists(reference):
        with open(reference) as f:
            check_file(d, json.load(f), report, failures)
    else:
        report.append(f'file: {reference} is not there to compare the file with')
    check_seam(file_clip, report, failures)
    check_poses(file_clip, states, report, failures)
    check_clear(file_clip, coil, report, failures)
    check_hands(file_clip, coil, report, failures)
    check_events(file_clip, coil, report, failures)
    check_speeds(file_clip, report)
    check_fidelity(file_clip, coil, report)
    return report, failures


# ================================================================== previews: the player in the coil

def fallback_skin():
    """A plain 64x64 skin painted on the player model's own faces, for when no skin is to hand: a deckhand's clothes."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cols = {'head': (214, 170, 136), 'body': (88, 104, 128), 'right_arm': (214, 170, 136), 'left_arm': (214, 170, 136),
            'right_leg': (74, 62, 52), 'left_leg': (74, 62, 52)}
    for part, cubes in PLAYER.items():
        for origin, size, uv, g in cubes:
            if g > 0:
                continue
            u, v = uv
            w, h, dd = [int(math.ceil(x)) for x in size]
            d.rectangle([u, v, u + 2 * dd + 2 * w - 1, v + dd + h - 1], fill=cols[part] + (255,))
            if part == 'head':
                d.rectangle([u + dd + 1, v + dd + 3, u + dd + 2, v + dd + 4], fill=(40, 40, 60, 255))
                d.rectangle([u + dd + 5, v + dd + 3, u + dd + 6, v + dd + 4], fill=(40, 40, 60, 255))
    return im


def make_scene(skin_path=None):
    if skin_path and os.path.exists(skin_path):
        skin = Image.open(skin_path).convert('RGBA')
    else:
        skin = fallback_skin()
    return Scene(skin, Image.new('RGBA', (16, 16), (0, 0, 0, 0)))


def gif_save(frames, path, ms):
    """Writes [frames] (RGB images) as a looping GIF with one shared 128-colour palette (a sample of the frames), so the
    colours never flicker and little changes frame to frame."""
    sample = [frames[i] for i in range(0, len(frames), max(1, len(frames) // 24))]
    sheet_ = Image.new('RGB', (frames[0].width * 6, frames[0].height * ((len(sample) + 5) // 6)))
    for i, f in enumerate(sample):
        sheet_.paste(f, ((i % 6) * f.width, (i // 6) * f.height))
    pal = sheet_.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    out = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out[0].save(path, save_all=True, append_images=out[1:], duration=ms, loop=0, optimize=False, disposal=1)
    return os.path.getsize(path)


PANEL = (450, 450)
FPS = 25
# (yaw, pitch, zoom, centre y) of the cameras: the front three-quarter view, and from the player's left side (he faces right)
VIEW_FRONT = (28.0, 12.0, 10.6, 8.0)
VIEW_SIDE = (90.0, 8.0, 10.6, 8.0)


def phase_label(t):
    """What the performance is doing at [t], in a few words, for the previews."""
    p = performance(t)
    bits = []
    if p['fl']['w'] > 0.5:
        bits.append('flail')
    if p['ham']['w'] > 0.5:
        bits.append('hammer')
        for k, (tb, _) in enumerate(BLOWS):
            if -0.3 < wrapd(t, tb) < 1.2:
                bits.append(f'blow {k + 1}')
    if p['sq'] > 0.3:
        bits.append('squeeze')
    return ' + '.join(bits) or 'pry'


def timeline(width, t, height=38):
    """A strip under the picture: the loop's 60 ticks with the hammering and flailing windows, the squeezes and blows, and where
    the picture is."""
    im = Image.new('RGB', (width, height), (30, 32, 40))
    d = ImageDraw.Draw(im)
    X = lambda tt: 8 + (width - 16) * tt / LENGTH
    d.rectangle([X(HAMMER[0]), 4, X(HAMMER[1]), 11], fill=(190, 128, 64))
    d.rectangle([X(FLAIL[0]), 4, X(FLAIL[1]), 11], fill=(190, 84, 130))
    for tb, _ in BEATS:
        d.polygon([(X(tb), 22), (X(tb) - 3, 15), (X(tb) + 3, 15)], fill=(220, 80, 80))
    for tb, _ in BLOWS:
        d.rectangle([X(tb) - 1, 3, X(tb) + 1, 12], fill=(255, 244, 214))
    d.line([(X(t), 1), (X(t), 24)], fill=(255, 255, 255), width=2)
    x = 8
    for word, col in (('hammer', (214, 160, 100)), ('flail', (214, 120, 160)), ('squeeze', (220, 90, 90)),
                      ('blow', (255, 244, 214))):
        d.text((x, height - 13), word, fill=col)
        x += 6 * len(word) + 14
    return im


def draw_frame(scene, clip, t, extras, view, label):
    """One frame of a preview: the player posed by [clip] at [t] and [extras] (the coil's quads), seen from [view], a label on top
    and the timeline beneath."""
    pose = pose_clip(clip, t)
    quads = scene.player_world(pose, sword=False) + list(extras)
    yaw, pitch, zoom, cy = view
    proj = ortho(yaw, pitch, zoom, PANEL, cy)
    img = raster(quads, proj, PANEL, bg=(58, 66, 88))
    d = ImageDraw.Draw(img)
    d.text((6, 4), label, fill=(240, 236, 220))
    out = Image.new('RGB', (PANEL[0], PANEL[1] + 38))
    out.paste(img, (0, 0))
    out.paste(timeline(PANEL[0], t), (0, PANEL[1]))
    return out


def seized_gif(scene, clip, coil, path, view):
    """One lap of the loop (60 ticks, 20 a second, shown at 25 frames a second), the coil drawn as a stand-in: a thick tapering
    ring, slate-violet above and pale beneath."""
    n = int(round(clip.length / 20.0 * FPS))
    extras = coil.quads()
    frames = []
    for i in range(n):
        t = clip.length * i / n
        frames.append(draw_frame(scene, clip, t, extras, view, f'seized  tick {t:4.1f} / {clip.length:.0f}   {phase_label(t)}'))
    return gif_save(frames, path, int(1000 / FPS)), len(frames)


# ================================================================== main

def describe(clip):
    return ', '.join(b for b in GAME_BONES if b in clip.keys)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 0
    assets = argv[1]
    previews = argv[2] if len(argv) > 2 else None
    skin = argv[3] if len(argv) > 3 else None
    clip, states, coil = build()
    path = clip.write(assets, GROUP)
    print(f'wrote {path}  ({os.path.getsize(path) / 1000:.0f} kB)')
    print(f'{clip.name:8s} {clip.length:>4g} ticks  rate {clip.rate}  blend {clip.blend_in:g}/{clip.blend_out:g}  [{describe(clip)}]')
    report, failures = run_checks(clip, states, coil, path, assets)
    print('\n'.join(report))
    if previews:
        scene = make_scene(skin)
        out = os.path.join(previews, GROUP)
        for name, view in (('seized.gif', VIEW_FRONT), ('seized_side.gif', VIEW_SIDE)):
            size, n = seized_gif(scene, clip, coil, os.path.join(out, name), view)
            print(f'{name}: {n} frames, {size / 1e6:.2f} MB')
    if failures:
        print('FAILED: ' + '; '.join(failures))
        return 1
    print('all checks passed')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
