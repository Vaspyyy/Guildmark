"""
(placeholder: the real header is written when the clips are done)
"""
import copy
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.interpolate import PchipInterpolator
from scipy.optimize import least_squares

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from animkit import (Clip, qeuler, qmul, qaxis, qmat, qfrommat, qslerp, frame_to, unit, I, pose_clip, vanilla_pose, raster, ortho,
                     perspective, T, Rq, S, apply, box_quads, PLAYER, FACE_LIGHT, Geo, SWORD, FP_BASE, fp, sprite_quads, Scene)

GROUP = 'player'
FP_GROUP = 'first_person'
GROUND = 24.0
ZERO = np.zeros(3)

# ================================================================== the skeleton, as CombatAnimator has it (ship_anims.py's)

HIPS = np.array([0.0, 12.0, 0.0])
RIGHT, LEFT = 'right_arm', 'left_arm'
SIDES = (RIGHT, LEFT)
SHOULDER = {RIGHT: np.array([-5.0, 2.0, 0.0]), LEFT: np.array([5.0, 2.0, 0.0])}
HIP_JOINT = {'right_leg': np.array([-1.9, 12.0, 0.0]), 'left_leg': np.array([1.9, 12.0, 0.0])}
LEG_OF = {RIGHT: 'right_leg', LEFT: 'left_leg'}
ARM_X = {RIGHT: -1.0, LEFT: 1.0}                   # the arm's own middle line, off its joint
INNER = {RIGHT: np.array([1.0, 0.0, 0.0]), LEFT: np.array([-1.0, 0.0, 0.0])}   # the arm's inner (palm) side, in its own space
FIST_AT = 9.0                                      # how far down the arm a bare hand closes (px from the joint)
ARM_REACH = (8.0, 9.95)
ARM_TOUCH = 1.5                                    # px two forearms may overlap (a two-handed grip has the fists side by side)
FWD = np.array([0.0, 0.0, -1.0])
UP = np.array([0.0, -1.0, 0.0])
DOWN = -UP
RIGHTWARD = np.array([-1.0, 0.0, 0.0])              # the player's right is -x
LEFTWARD = -RIGHTWARD
HEAD_BOX = ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0))
BODY_BOX = ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0))
ARM_GRID = {RIGHT: np.array([(x, y, z) for x in (-3.0, -1.0, 1.0) for y in (3.0, 5.0, 7.0, 9.0, 10.0) for z in (-2.0, 0.0, 2.0)]),
            LEFT: np.array([(x, y, z) for x in (-1.0, 1.0, 3.0) for y in (3.0, 5.0, 7.0, 9.0, 10.0) for z in (-2.0, 0.0, 2.0)])}
ARM_CORNERS = {RIGHT: np.array([(x, y, z) for x in (-3.0, 1.0) for y in (-2.0, 4.0, 10.0) for z in (-2.0, 2.0)]),
               LEFT: np.array([(x, y, z) for x in (-1.0, 3.0) for y in (-2.0, 4.0, 10.0) for z in (-2.0, 2.0)])}


BODY_CORNERS = np.array([(x, y, z) for x in (-4.0, 4.0) for y in (0.0, 12.0) for z in (-2.0, 2.0)])
HEAD_CORNERS = np.array([(x, y, z) for x in (-4.0, 4.0) for y in (-4.0, 4.0) for z in (-4.0, 4.0)])      # about the head's middle


def qinv(q):
    x, y, z, w = q
    return np.array([-x, -y, -z, w])


def smooth01(x):
    x = min(1.0, max(0.0, float(x)))
    return x * x * (3.0 - 2.0 * x)


def ramp(t, a, b):
    """0 before [a], 1 after [b], smooth between."""
    return smooth01((t - a) / (b - a)) if b > a else float(t >= a)


def pulse(t, a, b, c, d):
    """Rises smoothly over [a, b], holds 1 to [c], falls smoothly over [c, d]."""
    return ramp(t, a, b) * (1.0 - ramp(t, c, d))


def curve(keys):
    """A smooth, no-overshoot (monotone cubic) curve through (t, value) keys; values may be vectors. Held at the ends."""
    ts = [float(k[0]) for k in keys]
    vs = np.array([k[1] for k in keys], float)
    if len(ts) == 1:
        return lambda t: vs[0].copy() if vs.ndim > 1 else float(vs[0])
    f = PchipInterpolator(ts, vs, axis=0, extrapolate=False)

    def at(t):
        t = min(max(float(t), ts[0]), ts[-1])
        out = f(t)
        return float(out) if vs.ndim == 1 else np.asarray(out, float)
    return at


def torso_q(pitch=0.0, yaw=0.0, roll=0.0):
    """The torso bent forward [pitch] degrees at the hips, turned [yaw] (positive turns to the character's right: the right
    shoulder goes back) and tipped [roll] (positive tips to the left: the right shoulder rises); as ship_anims.torso_q."""
    return qmul(qeuler(y=yaw, z=roll), qeuler(x=pitch))


def skew(v):
    x, y, z = v
    return np.array([[0.0, -z, y], [z, 0.0, -x], [-y, x, 0.0]])


def rodrigues(axis, ang):
    a = unit(axis)
    K = skew(a)
    return np.eye(3) + math.sin(ang) * K + (1.0 - math.cos(ang)) * (K @ K)


def rot_between(a, b):
    """The smallest turn (3 x 3) that takes direction [a] to direction [b]."""
    a, b = unit(a), unit(b)
    c = float(np.dot(a, b))
    if c < -0.999999:
        p = np.cross(a, np.array([1.0, 0.0, 0.0]))
        if np.linalg.norm(p) < 1e-6:
            p = np.cross(a, np.array([0.0, 1.0, 0.0]))
        return rodrigues(p, math.pi)
    K = skew(np.cross(a, b))
    return np.eye(3) + K + (K @ K) / (1.0 + c)


def roll_angle(axis, v0, vw):
    """The turn about [axis] that takes [v0] closest to [vw] (their parts square to the axis compared)."""
    a = unit(axis)
    p0 = v0 - np.dot(v0, a) * a
    pw = vw - np.dot(vw, a) * a
    if np.linalg.norm(p0) < 1e-6 or np.linalg.norm(pw) < 1e-6:
        return 0.0
    return math.atan2(float(np.dot(a, np.cross(p0, pw))), float(np.dot(p0, pw)))


def depth_in_boxes(points, lo, hi, margin=0.0):
    """For each of [points] (N x 3) how deep it is inside the box (lo, hi) grown by [margin]: its smallest way out, 0 outside."""
    p = np.atleast_2d(points)
    lo, hi = np.asarray(lo, float) - margin, np.asarray(hi, float) + margin
    inside = np.minimum(p - lo, hi - p).min(axis=1)
    return np.maximum(inside, 0.0)


def head_look_q(pitch, yaw=0.0):
    """The player's own head turn, as the game has it before the clip's: [pitch] degrees down, [yaw] to the right's... (as qeuler)."""
    return qeuler(x=pitch, y=yaw)


# ================================================================== the body: joints, arms, legs

class Body:
    """Where the body's joints are for a root offset [pr], a torso rotation [qt] and offset [vt] and the arms' joint offsets [pa]
    ({side: offset in the torso's frame}): CombatAnimator's chain, with the root not turned."""

    def __init__(self, pr=(0.0, 0.0, 0.0), qt=I, vt=(0.0, 0.0, 0.0), pa=None):
        self.pr = np.asarray(pr, float)
        self.qt = np.asarray(qt, float)
        self.vt = np.asarray(vt, float)
        self.pa = {k: np.asarray(v, float) for k, v in (pa or {}).items()}
        self.Rt = qmat(self.qt)

    def shoulder(self, side):
        return HIPS + self.Rt @ (SHOULDER[side] + self.pa.get(side, ZERO) - HIPS) + self.vt + self.pr

    def torso_point(self, p):
        """A point (or N x 3 points) of the torso's own space (the body box spans x -4..4, y 0..12, z -2..2) in the player's frame."""
        return (np.asarray(p, float) - HIPS) @ self.Rt.T + HIPS + self.vt + self.pr

    def to_torso(self, w):
        """A player's-frame point (or N x 3 points) in the torso's own space."""
        return HIPS + (np.asarray(w, float) - self.pr - self.vt - HIPS) @ self.Rt

    def neck(self):
        """The head's joint (the middle of the torso's top)."""
        return self.torso_point((0.0, 0.0, 0.0))

    def roll_terms(self, side, work, target, blade, want, ref):
        """What the roll of an arm that puts [work] on [target] is made of: the roll that brings [blade] nearest [want], the roll of the arm
        turned from [ref] by the smallest turn, and how well [want] fixes a roll at all (0 where it lies along the arm's line)."""
        J = self.shoulder(side)
        d = np.asarray(target, float) - J
        dist = float(np.linalg.norm(d))
        dn = d / dist if dist > 1e-6 else FWD.copy()
        work = np.asarray(work, float)
        R0 = rot_between(work, dn)
        b0 = R0 @ np.asarray(blade, float)
        wv = unit(np.asarray(want, float))
        phi = roll_angle(dn, b0, wv)
        R_ref = rot_between(ref @ work, dn) @ ref
        phi_ref = roll_angle(dn, b0, R_ref @ np.asarray(blade, float))
        cond = min(float(np.linalg.norm(wv - np.dot(wv, dn) * dn)), float(np.linalg.norm(b0 - np.dot(b0, dn) * dn) / np.linalg.norm(b0)))
        return phi, phi_ref, smooth01((cond - 0.15) / 0.35)

    def arm(self, side, work, target, blade=None, want=None, palm=None, ref=None, carry=0.0, sign=None, wrist=None):
        """The arm that puts the point [work] of its own space (px from the joint) on the world point [target] (as far as the arm's
        length allows: the miss is the reach left over), and turns it about itself to bring its [blade] direction (its own space)
        nearest the world direction [want], or else its inner face toward [palm]. [ref] (the arm's turn when it hangs, 3 x 3) and [carry]
        (0 to 1): the roll goes that far toward the one the arm has when it is turned from [ref] by the smallest turn (no roll of the wrist),
        the way round the one within half a turn of [sign] radians (None: the shorter). [wrist] (radians): the roll's turn from that smallest-turn
        roll, fixed beforehand (Spec.plan_wrists), in place of the one [want] asks for now. Returns (the 'set' key, the arm's turn in the world
        (3 x 3), its joint, the world point of [work])."""
        J = self.shoulder(side)
        d = np.asarray(target, float) - J
        dist = float(np.linalg.norm(d))
        if dist < 1e-6:
            d, dist = FWD.copy(), 1.0
        dn = d / dist
        work = np.asarray(work, float)
        R0 = rot_between(work, dn)
        if blade is not None and want is not None:
            phi, phi_ref, good = self.roll_terms(side, work, target, blade, want, ref) if ref is not None else (roll_angle(
                dn, R0 @ np.asarray(blade, float), unit(np.asarray(want, float))), 0.0, 1.0)
            if ref is not None:
                if wrist is not None:
                    phi = phi_ref + wrist * (1.0 - min(1.0, carry))
                else:
                    c = 0.0 if sign is None else float(sign)
                    d = (phi - phi_ref - c + math.pi) % (2.0 * math.pi) - math.pi + c
                    # where [want] lies nearly along the arm's line it says little about the roll: the roll then keeps to the hanging hand's
                    phi = phi_ref + d * (1.0 - min(1.0, carry)) * good
        elif palm is not None:
            phi = roll_angle(dn, R0 @ INNER[side], np.asarray(palm, float))
        else:
            phi = 0.0
        R = rodrigues(dn, phi) @ R0
        return qmul(qinv(self.qt), qfrommat(R)), R, J, J + R @ work

    def free_arm(self, side, direction, palm=None):
        """The arm hanging along [direction] (a vector in the torso's frame), its inner face toward [palm] (the torso's frame):
        the 'set' key relative to the torso, the arm's own middle line along the direction."""
        d = unit(direction)
        p = np.asarray(palm, float) if palm is not None else (-RIGHTWARD if side == RIGHT else RIGHTWARD)
        # arm-local +y is along the arm; its inner face (INNER) is the palm
        return frame_to(np.array([0.0, 1.0, 0.0]), INNER[side], d, p)


def leg_key(side, hip, foot, toe_out=8.0):
    """The leg's rotation (absolute, under the root) that points it from the hip joint [hip] (player's frame, root included)
    at the planted [foot], toes turned [toe_out] degrees outward. A foot nearer than the leg's 12 px has the leg's pivot raised
    into the body (a bent knee, as a block leg shows one: the CombatAnimator adds a leg track's position to the joint). Returns
    the rotation, how far the foot ends from the point (it can only fall short), where it ends, and the pivot's offset."""
    d = np.asarray(foot, float) - hip
    dist = float(np.linalg.norm(d))
    s = -1.0 if side == 'right_leg' else 1.0
    fwd = np.array([s * math.sin(math.radians(-toe_out)), 0.0, -math.cos(math.radians(toe_out))])
    q = frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), d, fwd)
    pl = -(12.0 - dist) * d / dist if dist < 12.0 and dist > 1e-6 else ZERO.copy()
    reached = hip + pl + qmat(q) @ np.array([0.0, 12.0, 0.0])
    return q, float(np.linalg.norm(reached - foot)), reached, pl


def stance_drop(feet, rx=0.0, rz=0.0):
    """How far the hips sit lowered, with the root moved [rx] and [rz] sideways and back, for the planted feet ({leg: (x, z, lift)})
    to reach their spots with the legs at their full 12 px: each leg wants its own drop, and the planted one counts for most."""
    need, wts = [], []
    for leg, (fx, fz, lift) in feet.items():
        h = HIP_JOINT[leg]
        dxz = math.hypot(fx - (h[0] + rx), fz - (h[2] + rz))
        need.append(GROUND - lift - math.sqrt(max(0.0, 144.0 - dxz * dxz)) - 12.0)
        wts.append(1.0 / (0.25 + lift))
    return float(np.dot(need, wts) / sum(wts))


# ================================================================== tools and items: how they sit in the hand

ITEM_TP = ((0, 0, 0), (0, 3, 1), (0.55, 0.55, 0.55))      # vanilla's `generated` model: a flat item in the hand (the bowl, food)
ITEM_FP = SWORD.fp


def px_geo(name, grip_px, tip_px, tp=None, fp_=None, size=32.0):
    """A held thing laid out on its sprite (pixels from the top left): where the hand closes, where its working end is."""
    g = (grip_px[0] / size, 1.0 - grip_px[1] / size)
    t = (tip_px[0] / size, 1.0 - tip_px[1] / size)
    return Geo(name, tp or SWORD.tp, fp_ or SWORD.fp, g, t)


# the tools' layout on their 32 px sprites: cook_tools.LAYOUT (M1), where the hand closes and the working end is
TOOL_LAYOUT = {'cooks_ladle': ((7, 25), (24, 8)), 'gilded_ladle': ((7, 25), (24, 8)), 'cooks_knife': ((7, 25), (27, 5)),
               'turner': ((7, 25), (25, 7))}
LADLE = px_geo('cooks_ladle', *TOOL_LAYOUT['cooks_ladle'])
KNIFE = px_geo('cooks_knife', *TOOL_LAYOUT['cooks_knife'])
TURNER = px_geo('turner', *TOOL_LAYOUT['turner'])
BOWL = Geo('bowl', ITEM_TP, ITEM_FP, grip=(0.5, 0.45), tip=(0.5, 0.45))          # the 16 px bowl: held by its belly
FOOD = Geo('food', ITEM_TP, ITEM_FP, grip=(0.5, 0.5), tip=(0.5, 0.5))              # any other flat item in a hand


def tool_pt(geo, side, u):
    """The point [u] of the way from the grip to the tip of [geo] (0: the hand, 1: the working end), in the arm's own space."""
    p = geo.grip + (geo.tip - geo.grip) * u
    return geo.tp_point(p, left=(side == LEFT))


def tool_blade(geo, side):
    """The direction grip to tip in the arm's own space."""
    return unit(tool_pt(geo, side, 1.0) - tool_pt(geo, side, 0.0))


def hand_pt(side, g=FIST_AT):
    """A bare hand's closing point in the arm's own space."""
    return np.array([ARM_X[side], g, 0.0])


# ================================================================== what a clip is made of, and the solve that poses it

MOUTH = np.array([0.0, -2.4, -4.0])        # the head's own space (its joint the origin): the lips, on the face
EYES = np.array([0.0, -4.5, -4.0])


class Plan:
    """What one arm is doing at a tick. [free]: where it hangs when it is not reaching: ('world' or 'torso', direction, palm) (the
    palm is where its inner face looks). [w]: how far it is into a reach (0: free, 1: reaching). A reach puts [work] (a point of the
    arm's own space) on [target] (a world point, or a function of the Ctx), its [blade] direction (the arm's own space) along
    [want] (a world direction, or a function of the Ctx) or else its inner face toward [palm]. [strength] scales the reach's weight in
    the solve (a hand that matters less gives way to the body's style). While [w] is under 1 the arm turns from its hanging key toward
    the reach's by [w] (a swing about the shoulder), bowed by [arc] = (outward, up, forward): at the middle of the way the arm's direction has
    that much added toward the side away from the body, toward the sky and toward the front (a number is a fraction of a radian: 0.35 is
    about 20 degrees). [roll]: radians, the middle of the roll the arm makes about itself on its way in and out (see swing_rot). That is where the swing starts: the solve then bends it, a sample at a time and no more than it takes, so that the arm
    goes round the body, the head, the other arm and the station, not through them."""

    def __init__(self, free=None, w=0.0, work=None, target=None, blade=None, want=None, palm=None, strength=1.0, arc=None, roll=None, carry=0.0,
                 lean=None, blade_k=1.0):
        self.free, self.w, self.work, self.target, self.blade, self.want, self.palm, self.strength = free, w, work, target, blade, want, palm, strength
        self.blade_k = blade_k                              # how firmly the blade's direction is held (0: only the roll about the arm is set by [want], the body is not
        #                                                     moved for it: a flat thing in the fist sits square to the arm, whatever way is wanted)
        self.lean = lean                                    # 0 to 1: how far the body is nudged for this reach (None: [w]); a reach made by a target that is
        #                                                     itself on its way out of the hang has w 1 and a lean of how far out it is
        self.arc = arc
        self.roll = roll                                    # radians: the middle of the rolls the arm makes (None: the shorter way)
        self.carry = carry                                  # 0 to 1: how far the reach keeps the roll of the hanging hand, not the [want]'s (see Body.arm)


class Ctx:
    """What a Plan's functions are given: the tick, the body, the head's turn (3 x 3) and the arms evaluated so far (right first)."""

    def __init__(self, t, body, Rh):
        self.t, self.body, self.Rh, self.sols = t, body, Rh, {}
        self.wrist = {}                                     # side -> the wrist's turn fixed for this tick (Spec.plan_wrists)

    def head_pt(self, p):
        return self.body.neck() + self.Rh @ np.asarray(p, float)

    def hand(self, side, local):
        """The arm [side]'s point [local] in the world (as it was evaluated: only for the right arm, before the left)."""
        s = self.sols[side]
        return s['J'] + s['R'] @ np.asarray(local, float)


REST_FREE = ('world', DOWN, None)


def free_key(body, side, free):
    kind, d, palm = free if free is not None else REST_FREE
    d = unit(d)
    if kind == 'world':
        d = body.Rt.T @ d
        palm = None if palm is None else body.Rt.T @ np.asarray(palm, float)
    return body.free_arm(side, d, palm)


Y_AXIS = np.array([0.0, 1.0, 0.0])


def roll_of(R):
    """An arm's turn [R] (3 x 3, its own y along the arm) as (the arm's direction, its roll about that direction after the shortest
    turn that takes the y axis there)."""
    d = R @ Y_AXIS
    M = R @ rot_between(Y_AXIS, d).T
    v = np.array([M[2, 1] - M[1, 2], M[0, 2] - M[2, 0], M[1, 0] - M[0, 1]]) / 2.0
    return d, math.atan2(float(np.dot(d, v)), (float(np.trace(M)) - 1.0) / 2.0)


def from_roll(d, phi):
    """The arm's turn with its direction [d] and roll [phi] (roll_of's inverse)."""
    return rodrigues(d, phi) @ rot_between(Y_AXIS, d)


def slerp_dir(d0, d1, s):
    """The direction [s] of the way from [d0] to [d1] along the great circle (at an even pace)."""
    ang = math.acos(max(-1.0, min(1.0, float(np.dot(d0, d1)))))
    if ang < 1e-6:
        return np.asarray(d1, float).copy()
    if ang > math.pi - 1e-6:
        p = np.cross(d0, np.array([1.0, 0.0, 0.0]))
        if np.linalg.norm(p) < 1e-6:
            p = np.cross(d0, np.array([0.0, 0.0, 1.0]))
        return unit(d0 * math.cos(math.pi * s) + unit(p) * math.sin(math.pi * s))
    return (math.sin((1.0 - s) * ang) * d0 + math.sin(s * ang) * d1) / math.sin(ang)


def swing_rot(R0, R1, s, bow_vec=None, roll=None):
    """The arm's turn [s] (0 to 1) of the way from [R0] to [R1]: its direction goes the great-circle way (no wandering off to one side
    as a straight blend of the two turns does when they differ in roll), its roll turns at an even pace the shorter way round (or, with
    [roll] a number of radians, the way that is within half a turn of it: a roll near half a turn would otherwise flip from one way to
    the other between two samples; the middle of the rolls the arm makes is the number to give),
    and the direction is bowed by [bow_vec] (a world vector added to the unit direction) where the arm would go through something."""
    d0, p0 = roll_of(R0)
    d1, p1 = roll_of(R1)
    d = slerp_dir(d0, d1, s)
    if bow_vec is not None:
        d = unit(d + bow_vec)
    c = 0.0 if roll is None else float(roll)
    dp = (p1 - p0 - c + math.pi) % (2.0 * math.pi) - math.pi + c           # the turn in (c - half a turn, c + half a turn]
    return from_roll(d, p0 + dp * s)


def arc3(arc):
    """A bow as three numbers (outward, up, forward), the ones left out 0."""
    a = list(np.asarray(arc, float).ravel())[:3]
    return np.array(a + [0.0] * (3 - len(a)))


def eval_arm(body, side, plan, ctx, full=False, disp=None):
    """The arm's key for [plan]: its free key mixed toward the reach by the plan's weight ([full]: all the way, as the solve wants
    it), displaced by [disp] (the solved bow of the swing; the plan's own arc where there is none). Returns a dict (the key, its turn
    in the world, its joint, and for a reach the miss and the blade's world direction)."""
    qf = free_key(body, side, plan.free)
    out = dict(plan=plan, key=qf, miss=ZERO, blade=None, reach=None)
    if plan.w > 1e-4 and plan.target is not None:
        target = plan.target(ctx) if callable(plan.target) else plan.target
        want = plan.want(ctx) if callable(plan.want) else plan.want
        palm = plan.palm(ctx) if callable(plan.palm) else plan.palm
        s_ = 1.0 if full else min(1.0, plan.w)
        carry = plan.carry(ctx) if callable(plan.carry) else plan.carry
        qr, R, J, at = body.arm(side, plan.work, target, blade=plan.blade, want=want, palm=palm,
                                ref=qmat(qmul(body.qt, qf)), carry=carry, sign=plan.roll, wrist=ctx.wrist.get(side))
        out.update(reach=qr, miss=at - np.asarray(target, float), target=np.asarray(target, float), want=want)
        if s_ >= 0.9999:
            out['key'] = qr
        else:
            if disp is None and plan.arc is not None:
                disp = arc3(plan.arc) * math.sin(math.pi * s_)
            outward = RIGHTWARD if side == RIGHT else LEFTWARD
            wd = None if disp is None else outward * disp[0] + UP * disp[1] + FWD * disp[2]
            Rs = swing_rot(qmat(qmul(body.qt, qf)), qmat(qmul(body.qt, qr)), s_, wd, plan.roll)
            out['key'] = qmul(qinv(body.qt), qfrommat(Rs))
            out['disp'] = disp
    q_world = qmul(body.qt, out['key'])
    out['R'] = qmat(q_world)
    out['J'] = body.shoulder(side)
    if plan.blade is not None:
        out['blade'] = out['R'] @ np.asarray(plan.blade, float)
    return out


class Spec:
    """A clip as it is asked for: the body's style (the torso's pitch, yaw and roll in degrees and the hips' sideways and back
    offsets in px, as functions of the tick), the feet, the head, the arms' plans, and what the previews draw and the checks hold
    it to. solve() poses it: for each sample the style is nudged the least it takes for the reaches to reach."""

    def __init__(self, name, length, rate=2, blend=(1.0, 3.0), group=GROUP):
        self.name, self.length, self.rate, self.blend, self.group = name, length, rate, blend, group
        z = lambda t: 0.0
        self.style = dict(pitch=z, yaw=z, roll=z, rx=z, rz=z)
        self.crouch = lambda t: 0.0               # fn(t) -> px the hips sit lower than the stance asks, the legs' tops drawn up into the body
        self.vt = None                            # fn(t) -> the torso's own offset (a breath, a hunch), or None
        self.pa = {}                              # side -> fn(t) -> the arm joint's offset in the torso's frame (a shrug)
        self.feet = {'right_leg': lambda t: (-1.9, 0.0, 0.0), 'left_leg': lambda t: (1.9, 0.0, 0.0)}
        self.toe = {'right_leg': 8.0, 'left_leg': 8.0}
        self.head = None                          # fn(t) -> (pitch, yaw, roll) degrees on top of the player's own look
        self.look = lambda t: (0.0, 0.0)          # the player's own look (pitch, yaw): the previews' head, and the solve's
        self.arms = {RIGHT: lambda t, ctx: Plan(), LEFT: lambda t, ctx: Plan()}
        self.prior = dict(pitch=0.05, yaw=0.05, roll=0.06, rx=0.35, rz=0.35, hp=0.25)    # per degree / per px: how firmly the style holds
        self.pos_w, self.blade_w = 20.0, 5.0
        self.cont = np.array([0.03, 0.03, 0.04, 0.2, 0.2, 0.05])     # per degree / per px: how firmly the first pass holds to the sample before it
        self.cont2 = np.array([0.12, 0.08, 0.12, 0.4, 0.4, 0.1])     # and the second, which has the smoothed first answers to start from
        self.solids = []                          # (lo, hi) boxes her arms, body and head keep out of
        self.items = {}                           # side -> fn(t) -> (geo, texture name) | None: what the previews put in her hand
        self.extras = lambda t: []                # fn(t) -> more quads for the previews (a thrown carrot, steam)
        self.station = None                       # the stand-in station's name
        self.station_dx = 0.0                     # px the stand-in block stands to her left of straight ahead (negative: to her right)
        self.station_dz = 0.0                     # px nearer than STAND the block stands
        self.marks = {}
        self.solved = False
        self.wrist_track = {}                     # side -> {sample: the wrist's turn}: fixed before the solve (plan_wrists)
        self.only = None                          # the tracks to write (None: all that there are)
        self.smooth = 1.2                         # samples: how much the solved style is smoothed
        self.bounds = ([-15, -70, -30, -9, -12], [75, 70, 30, 9, 12])
        self.hp_bounds = (-12.0, 8.0)             # degrees: how far the solve may tip her head (negative: back) to keep her arms off her face
        self.checks = []                          # fn(report, clip, spec): the clip's own checks
        self.notes = ''

    def x_style(self, t):
        """The style at tick [t] as the solve's unknowns: the torso's pitch, yaw and roll, the hips' sideways and back offsets, and the
        head's extra pitch (the solve tips it back to keep her arms off her face; 0 to begin with)."""
        s = self.style
        return np.array([s['pitch'](t), s['yaw'](t), s['roll'](t), s['rx'](t), s['rz'](t), 0.0], float)

    def feet_at(self, t):
        return {leg: f(t) for leg, f in self.feet.items()}

    def new_ctx(self, t, body, Rh):
        """A Ctx for tick [t] (a sample's), holding the wrist turns plan_wrists fixed for it."""
        ctx = Ctx(t, body, Rh)
        i = int(round(t * self.rate))
        for side in SIDES:
            v = self.wrist_track.get(side, {}).get(i)
            if v is not None:
                ctx.wrist[side] = v
        return ctx

    def plan_wrists(self, sigma=1.4, anchor=0.25):
        """Fixes the wrist's turn at every sample, once, before the solve. A reach that holds a tool's blade along a wanted direction asks the
        arm for a roll about itself. Where that direction lies nearly along the arm's line the roll it asks for turns half a way round for
        a hair's breadth of movement, and the solve, which moves the body a sample at a time, finds one side of it at one sample and the other
        at the next. So the roll is asked once of the body in its style alone, unwound over the ticks (where it is well fixed), laid across
        the samples where it is not, and smoothed over time; the solve then takes the wrist as given."""
        n = len(self.times)
        raw = {RIGHT: {}, LEFT: {}}
        for i, t in enumerate(self.times):
            body = self.body_at(t, self.x_style(t))
            ctx = Ctx(t, body, qmat(self.head_q(t)))
            for side in SIDES:
                plan = self.arms[side](t, ctx)
                ctx.sols[side] = eval_arm(body, side, plan, ctx, full=True)
                if plan.target is None or plan.blade is None or plan.want is None or plan.w <= 1e-4:
                    continue
                target = plan.target(ctx) if callable(plan.target) else plan.target
                want = plan.want(ctx) if callable(plan.want) else plan.want
                qf = free_key(body, side, plan.free)
                phi, phi_ref, good = body.roll_terms(side, plan.work, target, plan.blade, want, qmat(qmul(body.qt, qf)))
                c = 0.0 if plan.roll is None else float(plan.roll)
                raw[side][i] = ((phi - phi_ref - c + math.pi) % (2.0 * math.pi) - math.pi + c, good)
        self.wrist_track = {RIGHT: {}, LEFT: {}}
        self.wrist_raw = raw
        for side in SIDES:
            idx = sorted(raw[side])
            anchors = [i for i in idx if raw[side][i][1] >= anchor]
            if not anchors:
                continue
            prev, un = None, {}
            for i in anchors:                                   # unwound over the well-fixed samples
                d = raw[side][i][0]
                if prev is not None:
                    d += 2.0 * math.pi * round((prev - d) / (2.0 * math.pi))
                un[i] = prev = d
            fill = np.interp(idx, anchors, [un[i] for i in anchors])     # laid across the gaps (held at the ends)
            vals, wts = [], []
            for k, i in enumerate(idx):
                d, good = raw[side][i]
                d += 2.0 * math.pi * round((fill[k] - d) / (2.0 * math.pi))
                vals.append(good * d + (1.0 - good) * fill[k])
                wts.append(1.0)
            vals = np.array(vals)
            if len(vals) > 2 and sigma > 0:
                vals = smoothed(vals[:, None], sigma, keep=0)[:, 0]
            for k, i in enumerate(idx):
                self.wrist_track[side][i] = float(vals[k])

    def body_at(self, t, x):
        pitch, yaw, roll, rx, rz = x[:5]
        drop = stance_drop(self.feet_at(t), rx, rz) + self.crouch(t)
        pa = {s: f(t) for s, f in self.pa.items()}
        return Body(pr=(rx, drop, rz), qt=torso_q(pitch, yaw, roll), vt=self.vt(t) if self.vt else ZERO, pa=pa)

    def head_q(self, t, extra=0.0):
        """The head's turn in the world: the player's own look, then the clip's offsets (as the game adds them), and [extra] degrees of
        pitch (the solve's)."""
        p, y = self.look(t)
        q = head_look_q(p, y)
        if self.head is not None:
            hp, hy, hr = self.head(t)
            q = qmul(q, qeuler(x=hp + extra, y=hy, z=hr))
        elif abs(extra) > 0.0:
            q = qmul(q, qeuler(x=extra))
        return q

    def residual(self, t, x_prior, w_prior, x_prev=None, w_prev=0.0, cont=None):
        """The solve's residuals at tick [t] as a function of the style [x]."""
        sol_w = np.array([self.prior['pitch'], self.prior['yaw'], self.prior['roll'], self.prior['rx'], self.prior['rz'], self.prior.get('hp', 0.25)]) * w_prior

        def res(x):
            Rh = qmat(self.head_q(t, x[5]))
            body = self.body_at(t, x)
            ctx = self.new_ctx(t, body, Rh)
            out = list((x - x_prior) * sol_w)
            if x_prev is not None:
                out += list((x - x_prev) * (self.cont if cont is None else cont) * w_prev)
            head_c = body.neck() + Rh @ np.array([0.0, -4.0, 0.0])
            for side in SIDES:
                plan = self.arms[side](t, ctx)
                a = eval_arm(body, side, plan, ctx, full=True)
                ctx.sols[side] = a
                wgt = plan.strength
                if plan.w > 1e-4 and plan.target is not None:
                    out += list(a['miss'] * self.pos_w * wgt)
                    if plan.blade is not None and a.get('want') is not None and plan.blade_k > 0.0:
                        out += list((a['blade'] - unit(a['want'])) * self.blade_w * wgt * plan.blade_k)
                P = a['J'] + ARM_GRID[side] @ a['R'].T
                out += list(depth_in_boxes(body.to_torso(P), (-3.9, 0.1, -1.9), (3.9, 11.9, 1.9)) * 12.0)
                lP = (P - body.neck()) @ Rh
                out += list(depth_in_boxes(lP, (-3.7, -7.7, -3.7), (3.7, -0.3, 3.7)) * 12.0)
                for lo, hi in self.solids:
                    out += list(depth_in_boxes(P, lo, hi) * 5.0)
            for lo, hi in self.solids:
                out += list(depth_in_boxes(body.torso_point(BODY_CORNERS), lo, hi) * 5.0)
                out += list(depth_in_boxes(head_c + HEAD_CORNERS @ Rh.T, lo, hi) * 5.0)
            return np.array(out)
        return res

    def body_w(self, t):
        """How far the body is into the reaches at tick [t]: the biggest weight of an arm that has a target (0 to 1)."""
        body = self.body_at(t, self.x_style(t))
        ctx = Ctx(t, body, qmat(self.head_q(t)))
        w = 0.0
        for side in SIDES:
            plan = self.arms[side](t, ctx)
            if plan.target is not None and plan.w > 1e-4:
                lean = plan.w if plan.lean is None else plan.lean
                w = max(w, min(1.0, lean) * min(1.0, plan.strength + 0.4))
            ctx.sols[side] = eval_arm(body, side, Plan(), ctx)
        return w

    def solve(self, verbose=False):
        """Poses every sample. The style is the body's own motion; where an arm reaches, the style is nudged the least it takes for the
        reaches to be made (all the way: the solve is of the full reach), and the nudge is eased in by how far the arm is into its
        reach (the plan's weight), so a body never leans for a hand that is not on its way yet. Two passes: each sample alone, then
        the samples that are fully in a reach again, held near the smoothed first answers."""
        n = int(round(self.length * self.rate))
        self.times = [i / self.rate for i in range(n + 1)]
        lo, hi = list(self.bounds[0][:5]) + [self.hp_bounds[0]], list(self.bounds[1][:5]) + [self.hp_bounds[1]]
        self.plan_wrists()
        wb = [self.body_w(t) for t in self.times]
        X, x_prev = [], None
        for t, w in zip(self.times, wb):
            x0 = self.x_style(t)
            if w < 1e-3:
                X.append(x0)
                continue
            xf = least_squares(self.residual(t, x0, 1.0, x_prev, 1.0), x0 if x_prev is None else 0.5 * (x0 + x_prev),
                               bounds=(lo, hi)).x
            x_prev = xf
            X.append(x0 + w * (xf - x0))
        X = np.array(X)
        if self.smooth > 0 and len(X) > 3:
            Xs = smoothed(X, self.smooth)
            X2, x_prev = [], None
            for i, t in enumerate(self.times):
                if wb[i] < 0.999:
                    X2.append(Xs[i])
                    continue
                xs = least_squares(self.residual(t, Xs[i], 6.0, x_prev, 1.0, self.cont2), Xs[i], bounds=(lo, hi)).x
                X2.append(xs)
                x_prev = xs
            X = np.array(X2)
        self.X = X
        self.disp = {}
        self.solve_swings()
        self.solved = True
        return self

    def state(self):
        """What solve() found, to be kept and put back with load()."""
        return dict(X=self.X, times=self.times, disp=self.disp, wrist=self.wrist_track)

    def load(self, st):
        self.X, self.times, self.disp, self.solved = st['X'], st['times'], st['disp'], True
        self.wrist_track = st.get('wrist', {})
        return self

    def swing_residual(self, i, v, prior, w_prior):
        """The residuals of the bow [v] ((outward, up, forward) for the right arm, then the left) at sample [i]: how deep her arms go into her
        body, her head, each other and the station, and how far [v] is from [prior]."""
        t = self.times[i]
        body = self.body_at(t, self.X[i])
        Rh = qmat(self.head_q(t, self.X[i][5]))
        ctx = self.new_ctx(t, body, Rh)
        arms, out = {}, []
        for k, side in enumerate(SIDES):
            arms[side] = eval_arm(body, side, self.arms[side](t, ctx), ctx, disp=v[3 * k:3 * k + 3])
            ctx.sols[side] = arms[side]
        Ps = {}
        neck = body.neck()
        for side in SIDES:
            a = arms[side]
            P = a['J'] + ARM_GRID[side] @ a['R'].T
            Ps[side] = P
            out += list(depth_in_boxes(body.to_torso(P), (-3.9, 0.1, -1.9), (3.9, 11.9, 1.9)) * 12.0)
            out += list(depth_in_boxes((P - neck) @ Rh, (-3.7, -7.7, -3.7), (3.7, -0.3, 3.7)) * 12.0)
            for lo, hi in self.solids:
                out += list(depth_in_boxes(P, lo, hi) * 5.0)
        aL = arms[LEFT]
        out += list(depth_in_boxes((Ps[RIGHT] - aL['J']) @ aL['R'], (-1.0 + ARM_TOUCH, -2.0 + ARM_TOUCH, -2.0 + ARM_TOUCH),
                                   (3.0 - ARM_TOUCH, 10.0 - ARM_TOUCH, 2.0 - ARM_TOUCH)) * 6.0)
        out += list((np.asarray(v) - prior) * w_prior)
        return np.array(out)

    def solve_swings(self):
        """Where an arm goes into or out of a reach, its swing is bowed (outward, up and forward, as the sine of how far into the reach it
        is) by the least it takes for the arm to go round her body, her head, her other arm and the station, not through them (outward
        costs the most: an arm thrown out to the side reads as a flourish). Each run of samples in which an arm is partway has one bow for
        the whole of it (three numbers), so a swing is smooth whatever is in its way."""
        n = len(self.times)
        part = {RIGHT: [], LEFT: []}                          # side -> [(sample, how far into the reach)]
        prior = {RIGHT: np.zeros(3), LEFT: np.zeros(3)}
        for i, t in enumerate(self.times):
            body = self.body_at(t, self.X[i])
            ctx = self.new_ctx(t, body, qmat(self.head_q(t, self.X[i][5])))
            for side in SIDES:
                plan = self.arms[side](t, ctx)
                ctx.sols[side] = eval_arm(body, side, Plan(), ctx)
                if plan.target is not None and 1e-4 < plan.w < 0.9999:
                    part[side].append((i, plan.w))
                    if plan.arc is not None:
                        prior[side] = arc3(plan.arc)
        runs = []                                             # (side, [(sample, s), ...]): consecutive samples
        for side in SIDES:
            cur = []
            for i, w in part[side]:
                if cur and i != cur[-1][0] + 1:
                    runs.append((side, cur))
                    cur = []
                cur.append((i, w))
            if cur:
                runs.append((side, cur))
        if not runs:
            return
        amp = {id(run): prior[side].copy() for side, run in runs}
        lo, hi = np.array([-0.3, -0.6, -0.4]), np.array([1.4, 1.3, 1.8])
        pull = np.array([0.9, 0.4, 0.3])

        def disp_of(i):
            d = np.zeros(6)
            for side, run in runs:
                for j, w in run:
                    if j == i:
                        k = SIDES.index(side)
                        d[3 * k:3 * k + 3] = amp[id(run)] * math.sin(math.pi * w)
            return d

        for sweep in range(2):                                 # each run in turn against the others as they stand
            for side, run in runs:
                k = SIDES.index(side)
                shape = {j: math.sin(math.pi * w) for j, w in run}

                def res(a, side=side, run=run, k=k, shape=shape):
                    out = []
                    for j, w in run:
                        v = disp_of(j)
                        v[3 * k:3 * k + 3] = a * shape[j]
                        out.append(self.swing_residual(j, v, np.zeros(6), 0.0))
                    out.append((a - prior[side]) * pull)
                    return np.concatenate(out)
                amp[id(run)] = least_squares(res, np.clip(amp[id(run)], lo, hi), bounds=(lo, hi)).x
        for i in range(n):
            d = disp_of(i)
            if np.any(d != 0.0):
                self.disp[i] = {RIGHT: d[0:3].copy(), LEFT: d[3:6].copy()}
        self.swing_amps = {side: [amp[id(run)].copy() for s2, run in runs if s2 == side] for side in SIDES}

    def frame(self, i):
        """The solved sample [i]: the body, the arms' evaluation, the legs' keys and the head's."""
        t = self.times[i]
        x = self.X[i]
        body = self.body_at(t, x)
        Rh = qmat(self.head_q(t, x[5]))
        ctx = self.new_ctx(t, body, Rh)
        arms = {}
        for side in SIDES:
            arms[side] = eval_arm(body, side, self.arms[side](t, ctx), ctx, disp=self.disp.get(i, {}).get(side))
            ctx.sols[side] = arms[side]
        legs = {}
        feet = self.feet_at(t)
        for leg, (fx, fz, lift) in feet.items():
            hip = HIP_JOINT[leg] + body.pr
            legs[leg] = leg_key(leg, hip, np.array([fx, GROUND - lift, fz]), self.toe[leg])
        return dict(t=t, x=x, body=body, arms=arms, legs=legs, ctx=ctx)

    def bake(self):
        """The animkit Clip for the solved samples."""
        if not self.solved:
            self.solve()
        c = Clip(self.name, self.length, blend_in=self.blend[0], blend_out=self.blend[1], rate=self.rate)
        keyed_head = self.head is not None or bool(np.any(np.abs(np.asarray(self.X)[:, 5]) > 1e-3))
        for i, t in enumerate(self.times):
            f = self.frame(i)
            b = f['body']
            c.key('root', t, I, pos=b.pr, ease='linear')
            c.key('torso', t, b.qt, pos=b.vt if self.vt else None, ease='linear')
            for side in SIDES:
                c.key(side, t, f['arms'][side]['key'], pos=b.pa.get(side), ease='linear')
            for leg, (q, miss, reached, pl) in f['legs'].items():
                c.key(leg, t, q, pos=pl, ease='linear')
            if keyed_head:
                hp, hy, hr = self.head(t) if self.head is not None else (0.0, 0.0, 0.0)
                c.key('head', t, qeuler(x=hp + float(self.X[i][5]), y=hy, z=hr), ease='linear')
        if keyed_head:
            c.mode('head', 'add')
        for label, tt in self.marks.items():
            c.mark(label, tt)
        if self.only is not None:
            for bone in list(c.keys):
                if bone not in self.only:
                    del c.keys[bone]
        return c


def smoothed(series, sigma=1.0, keep=1):
    """A time series (n x k) filtered with a Gaussian of [sigma] samples, its first and last [keep] rows kept as they are."""
    series = np.asarray(series, float)
    n = len(series)
    r = int(math.ceil(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    pad = np.vstack([np.repeat(series[:1], r, axis=0), series, np.repeat(series[-1:], r, axis=0)])
    out = np.stack([np.convolve(pad[:, j], k, mode='valid') for j in range(series.shape[1])], axis=1)
    out[:keep], out[n - keep:] = series[:keep], series[n - keep:]
    return out


# ================================================================== reading a baked clip back with the game's own chain

def base_pose(look=(0.0, 0.0), held=()):
    """What vanilla shows standing still, the player looking [look] (pitch, yaw degrees), an arm that holds an item raised a little
    (vanilla's item pose, 18 degrees) for each side in [held]."""
    pose = vanilla_pose(item_pose=False)
    pose['head'] = (head_look_q(*look), pose['head'][1])
    for side in held:
        pose[side] = (qeuler(x=-18), pose[side][1])
    return pose


def part_pt(pose, part, p):
    """A point of a part's own space, in the player's frame."""
    qr, pr = pose['root']
    q, pv = pose[part]
    return apply(T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q), p)


def tool_pt_world(pose, side, geo, p_item):
    """A point of a held thing's sprite (item space, 0..1) in the player's frame."""
    qr, pr = pose['root']
    q, pv = pose[side]
    M = T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q) @ S(16) @ geo.tp_item(left=(side == LEFT))
    return apply(M, p_item)


def tool_along(pose, side, geo, u):
    p = geo.grip + (geo.tip - geo.grip) * u
    return tool_pt_world(pose, side, geo, p)


def held_sides(spec, t):
    return tuple(side for side, fn in spec.items.items() if fn(t))


def pose_at(spec, clip, t):
    return pose_clip(clip, t, base=base_pose(spec.look(t), held_sides(spec, t)))


# ================================================================== the stand-in stations the clips work at
# Hearthcraft M1's blocks are a block across, and the clips are in place: the player stands at the block's front, its near face
# STAND px ahead of the body's middle (the block fills x -8..8, z -STAND-16..-STAND, from the ground up), looking at it. The numbers are
# M1's own (cook_art.STATION_BOXES, the click regions, and the models' constants), in block px (x east, y up, z south, 16 to a
# block, every model facing north: its front toward -z); at() puts a block point in the player's frame.

STAND = 8.0           # px from the body's middle to the block's near face: a cook's stand at the block (the player's box is 0.6 across, so
#                       4.8 is the nearest there is, and a rigid arm that reaches a nearer thing has to fold over her chest)


def at(p, dx=0.0, dz=0.0):
    """A block-space point (x east, y up, z south) in the player's frame for a player standing at the block's front, [dx] px to the
    block's left (the block, and everything of it, [dx] px to her right when it is negative) and [dz] px nearer than STAND."""
    x, y, z = (float(v) for v in p)
    return np.array([x - 8.0 + dx, 24.0 - y, -STAND - z + dz])


def blk(lo, hi, dx=0.0, dz=0.0):
    """A box of block-space corners as (lo, hi) in the player's frame."""
    a, b = at(lo, dx, dz), at(hi, dx, dz)
    return np.minimum(a, b), np.maximum(a, b)


M1_STATION_BOXES = {                      # cook_art.STATION_BOXES: x0, y0, z0, x1, y1, z1 in block px, facing north
    'stewpot_hearth': {'vessel': (4.0, 4.0, 4.0, 12.0, 10.5, 12.0), 'fire': (3.2, 0.0, 3.2, 12.8, 4.0, 12.8), 'heat': (6.0, 10.5, 6.0, 10.0, 15.4, 10.0)},
    'hob': {'vessel': (2.5, 9.5, 2.5, 13.5, 14.0, 13.5), 'fire': (3.5, 0.0, 0.0, 12.5, 4.5, 6.0), 'heat': (1.0, 5.0, 0.0, 15.0, 9.0, 5.0)},
    'bake_oven': {'vessel': (3.6, 8.0, 0.0, 12.4, 14.6, 4.0), 'fire': (4.5, 0.0, 0.0, 11.5, 4.0, 6.0), 'heat': (3.4, 4.0, 0.0, 12.6, 8.0, 4.0)},
    'spit': {'vessel': (2.4, 6.4, 5.5, 12.0, 11.8, 10.5), 'fire': (2.4, 0.0, 4.0, 12.6, 4.0, 12.0), 'heat': (11.6, 5.5, 5.5, 16.0, 13.4, 10.5)},
}


def region(station, kind, dx=0.0, dz=0.0):
    x0, y0, z0, x1, y1, z1 = M1_STATION_BOXES[station][kind]
    return blk((x0, y0, z0), (x1, y1, z1), dx, dz)


IRON, IRON_D, HEARTH, BRICK, BRICK_D = (66, 64, 70), (46, 44, 50), (122, 116, 108), (132, 86, 68), (104, 66, 52)
EMBER, STEW, WOOD, WOOD_D, CRUST, MEAT, TRAY, PAN, STONE, CAKE = (214, 104, 40), (150, 92, 46), (150, 110, 68), (116, 82, 50), (176, 128, 70), \
    (150, 80, 52), (96, 98, 106), (58, 56, 62), (128, 126, 120), (214, 166, 80)

# the pot: its middle, the rim's height, the stew's surface (clip y, y down) and the mouth's radius
POT_C = at((8.0, 0.0, 8.0))                  # (0, 24, -STAND-8)
POT_RIM, POT_SURFACE, POT_BOTTOM = 24.0 - 10.0, 24.0 - 8.5, 24.0 - 5.6
POT_MOUTH = 3.0
CHAIN_TOP, CHAIN_BOTTOM = 24.0 - 15.2, 24.0 - 12.9
POT_DX = -4.5                                # at every pot clip she stands with the pot a little to her right (a right-handed cook does)
POT_DZ = 3.0                                 # and close to it: its hearth's front 5 px from her middle (a cook leans over the pot, not reaches)
POT_MID = POT_C + np.array([POT_DX, 0.0, POT_DZ])      # the pot's middle at the ground, in the player's frame


def _box(lo, hi, col, solid=True, dx=0.0, dz=0.0):
    a, b = blk(lo, hi, dx, dz)
    return (a, b, col, solid)


def station_boxes(name, dx=0.0, dz=0.0):
    """The stand-in station's boxes as (lo, hi, colour, solid) in the player's frame. The 'solid' ones are the station's shell, what a
    held tool keeps out of (an arm, the body and the head keep out of the larger regions of solids_of); the rest the previews only draw.
    The numbers are M1's (cook_stewpot.py, cook_hob.py, cook_oven.py, cook_spit.py, cook_helpers.py), the models in a few boxes. The
    crane, chain and bail over the pot are left out: a ladle circling a pot's mouth passes through their column in the real block."""
    b = []
    S_ = lambda lo, hi, col: b.append(_box(lo, hi, col, True, dx, dz))
    D_ = lambda lo, hi, col: b.append(_box(lo, hi, col, False, dx, dz))
    if name in ('pot', 'pot_chain'):
        cx, cz, top, r, w = 8.0, 8.0, 10.0, 3.6, 0.6
        for lo, hi in (((0.5, 0, 0.5), (15.5, 1.8, 3.2)), ((0.5, 0, 12.8), (15.5, 1.8, 15.5)),
                       ((0.5, 0, 3.2), (3.2, 1.8, 12.8)), ((12.8, 0, 3.2), (15.5, 1.8, 12.8))):
            S_(lo, hi, HEARTH)                                                                  # the paved hearth round the pit
        D_((3.2, 0.3, 3.2), (12.8, 0.7, 12.8), EMBER)
        D_((cx - 2.5, 5.6, cz - 2.5), (cx + 2.5, 6.2, cz + 2.5), IRON_D)                      # the pot's floor
        S_((cx - r, 6.2, cz - r), (cx + r, top, cz - r + w), IRON)                              # its walls
        S_((cx - r, 6.2, cz + r - w), (cx + r, top, cz + r), IRON)
        S_((cx - r, 6.2, cz - r + w), (cx - r + w, top, cz + r - w), IRON)
        S_((cx + r - w, 6.2, cz - r + w), (cx + r, top, cz + r - w), IRON)
        D_((cx - 2.9, 8.4, cz - 2.9), (cx + 2.9, 8.6, cz + 2.9), STEW)
        if name == 'pot_chain':                                                                # the chain a hand pulls to ease the heat
            D_((cx - 0.5, top + 2.4, cz - 0.5), (cx + 0.5, 15.2, cz + 0.5), IRON)
            D_((cx - 1.2, 12.9, cz - 1.2), (cx + 1.2, 14.4, cz + 1.2), IRON_D)
    elif name == 'hob':
        S_((1, 0, 1), (15, 9, 15), BRICK)
        S_((0.5, 9, 0.5), (15.5, 10, 15.5), IRON)
        r = 5.4
        D_((8 - r + 0.2, 10, 8 - r + 0.2), (8 + r - 0.2, 10.5, 8 + r - 0.2), PAN)
        S_((8 - r, 10.5, 8 - r), (8 + r, 11.5, 8 - r + 0.6), PAN)
        S_((8 - r, 10.5, 8 + r - 0.6), (8 + r, 11.5, 8 + r), PAN)
        S_((8 - r, 10.5, 8 - r + 0.6), (8 - r + 0.6, 11.5, 8 + r - 0.6), PAN)
        S_((8 + r - 0.6, 10.5, 8 - r + 0.6), (8 + r, 11.5, 8 + r - 0.6), PAN)
        D_((13.4, 11.0, 7.3), (15.9, 11.5, 8.7), PAN)
        for x, z in HOB_SLOTS:
            D_((x - 1.6, 10.5, z - 1.6), (x + 1.6, 11.4, z + 1.6), CAKE)
    elif name in ('oven', 'oven_door'):
        x0, y0, x1, y1 = 4.8, 8.8, 11.2, 12.4
        D_((0.6, 0, 1.0), (15.4, 1.0, 15.8), STONE)
        S_((1.0, 1.0, 1.2), (15.0, 8.0, 15.4), BRICK)
        S_((1.0, 8.0, 3.4), (3.6, 9.9, 15.0), BRICK_D)
        S_((12.4, 8.0, 3.4), (15.0, 9.9, 15.0), BRICK_D)
        S_((3.6, 8.0, 1.2), (x0, 14.6, 3.4), BRICK_D)
        S_((x1, 8.0, 1.2), (12.4, 14.6, 3.4), BRICK_D)
        S_((x0, 8.0, 1.2), (x1, y0, 3.4), BRICK_D)                                              # the sill
        S_((3.6, y1, 1.2), (12.4, 14.6, 3.4), BRICK_D)                                          # the lintel
        S_((x0, 8.0, 8.8), (x1, 12.8, 15.0), BRICK_D)
        S_((x0 - 0.2, 8.0, 3.4), (x0, 12.8, 8.8), BRICK_D)
        S_((x1, 8.0, 3.4), (x1 + 0.2, 12.8, 8.8), BRICK_D)
        S_((x0, 12.8, 3.4), (x1, 14.0, 8.8), BRICK_D)
        D_((5.4, 8.2, 3.0), (10.6, 8.6, 8.4), TRAY)
        S_((3.6, 14.6, 1.2), (12.4, 15.3, 14.0), BRICK_D)
        for k in range(6 if name == 'oven_door' else 0):                      # the door, open, standing out from the opening's right edge
            s_ = 6.4 * (k + 0.5) / 6.0
            a = math.radians(100.0)
            mx, mz = 4.8 + s_ * math.cos(a), 1.0 - s_ * math.sin(a)
            S_((mx - 0.75, 8.8, mz - 0.75), (mx + 0.75, 13.6, mz + 0.75), IRON_D)
    elif name == 'spit':
        ay, az = 9.1, 8.0
        for lo, hi in (((0.6, 0, 3.2), (15.4, 2.0, 4.2)), ((0.6, 0, 11.8), (15.4, 2.0, 12.8)),
                       ((0.6, 0, 4.2), (2.4, 2.0, 11.8)), ((12.6, 0, 4.2), (15.4, 2.0, 11.8))):
            S_(lo, hi, STONE)
        D_((3.2, 1.0, 6.4), (12.0, 1.9, 9.6), TRAY)
        S_((0.8, 2.0, 6.8), (2.2, 13.2, 9.2), IRON)
        S_((13.0, 2.0, 6.8), (14.4, 13.2, 9.2), IRON)
        S_((0.4, ay - 0.5, az - 0.5), (15.0, ay + 0.5, az + 0.5), IRON_D)
        for i in range(6):
            x = 3.0 + 1.82 * i
            S_((x - 0.9, ay - 1.1, az - 1.1), (x + 0.9, ay + 1.1, az + 1.1), MEAT)
    elif name == 'board':
        for lx in (1.4, 13.0):
            for lz in (3.0, 11.6):
                D_((lx, 0, lz), (lx + 1.6, 8.0, lz + 1.4), WOOD_D)
        S_((0.6, 8.0, 2.0), (15.4, 9.6, 14.0), WOOD)
        S_((3.2, 9.6, 4.2), (12.8, 10.2, 12.4), (176, 142, 94))
    return b


def shell_of(name, dx=0.0, dz=0.0):
    """The (lo, hi) boxes a held tool keeps out of at the stand-in station [name]: its solid parts (not what is inside them)."""
    return [(lo, hi) for lo, hi, col, solid in station_boxes(name, dx, dz) if solid]


HOB_SLOTS = [(5.0, 6.1), (8.0, 6.1), (11.0, 6.1), (5.0, 9.9), (8.0, 9.9), (11.0, 9.9)]       # M1's: cook_hob.PAN_SLOTS (x, z)
BOARD_TOP = 24.0 - 10.2                                                                          # the chopping board's top (clip y)
BOARD_X, BOARD_Z = (-4.8, 4.8), (float(at((0, 0, 12.4))[2]), float(at((0, 0, 4.2))[2]))


def solids_of(name, dx=0.0, dz=0.0):
    """The (lo, hi) boxes her hands, body and head keep out of at the stand-in station [name]: M1's vessel and fire click regions
    (the hob's fire sits under the plinth), the counter and the roast's rod."""
    if name in ('pot', 'pot_chain'):
        return [region('stewpot_hearth', 'vessel', dx, dz), region('stewpot_hearth', 'fire', dx, dz)]
    if name == 'hob':
        return [region('hob', 'vessel', dx, dz)]
    if name in ('oven', 'oven_door'):
        return [region('bake_oven', 'vessel', dx, dz)]
    if name == 'spit':
        return [region('spit', 'vessel', dx, dz)]
    if name == 'board':
        return [blk((0.6, 0.0, 2.0), (15.4, 9.6, 14.0), dx, dz), blk((3.2, 9.6, 4.2), (12.8, 10.2, 12.4), dx, dz)]
    return []


# ================================================================== the previews: the player, what is in her hands, the station

def solid_tex(rgb):
    t = np.zeros((2, 2, 4))
    t[..., :3] = rgb
    t[..., 3] = 255
    return t


def oriented_box(centre, axes, half, tex, light_dir=(0.25, -0.55, 0.8)):
    """The six faces of a box centred on [centre], its three unit [axes] and half sizes, as raster's quads."""
    quads = []
    L = unit(np.array(light_dir, float))
    c = np.asarray(centre, float)
    for i in range(3):
        j, k = (i + 1) % 3, (i + 2) % 3
        for s in (-1, 1):
            n = s * axes[i]
            fc = c + n * half[i]
            A = fc - axes[j] * half[j] - axes[k] * half[k]
            Bq = A + axes[j] * 2 * half[j]
            Dq = A + axes[k] * 2 * half[k]
            light = 0.55 + 0.45 * max(0.0, float(np.dot(n, L)))
            quads.append((A, Bq, Dq, (0, 0, 1, 1), light, tex))
    return quads


AXES = np.eye(3)
_TEX_CACHE = {}


def box_quads_world(boxes, flat=False):
    """Axis-aligned (lo, hi, colour, solid) boxes as quads."""
    quads = []
    for lo, hi, col, solid in boxes:
        key = ('solid', col)
        if key not in _TEX_CACHE:
            _TEX_CACHE[key] = solid_tex(col)
        lo, hi = np.asarray(lo, float), np.asarray(hi, float)
        quads += oriented_box((lo + hi) / 2.0, AXES, (hi - lo) / 2.0, _TEX_CACHE[key])
    return quads


def fallback_skin():
    """A plain 64x64 skin painted on the player model's own faces, for when no skin is to hand: a cook's whites."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cols = {'head': (214, 170, 136), 'body': (226, 224, 216), 'right_arm': (226, 224, 216), 'left_arm': (226, 224, 216),
            'right_leg': (96, 84, 70), 'left_leg': (96, 84, 70)}
    for part, cubes in PLAYER.items():
        for origin, size, uv, g in cubes:
            if g > 0:
                continue
            u, v = uv
            w, h, dd = [int(math.ceil(s)) for s in size]
            d.rectangle([u, v, u + 2 * dd + 2 * w - 1, v + dd + h - 1], fill=cols[part] + (255,))
            if part == 'head':
                d.rectangle([u + dd + 1, v + dd + 3, u + dd + 2, v + dd + 4], fill=(40, 40, 60, 255))
                d.rectangle([u + dd + 5, v + dd + 3, u + dd + 6, v + dd + 4], fill=(40, 40, 60, 255))
    return im


# a tiny sprite for a held thing whose texture is not to hand: the tool's colours along its sprite diagonal
FALLBACK_COLOURS = {'cooks_ladle': (170, 170, 176), 'gilded_ladle': (214, 180, 70), 'cooks_knife': (200, 204, 210), 'turner': (150, 154, 162),
                    'bowl': (176, 130, 90), 'mushroom_stew': (176, 130, 90), 'carrot': (232, 130, 40), 'coal': (40, 40, 44), 'bread': (196, 150, 84)}


def find_texture(name, dirs):
    for d in dirs:
        p = os.path.join(d, name + '.png')
        if os.path.exists(p):
            im = Image.open(p).convert('RGBA')
            w = im.size[0]
            return np.array(im.crop((0, 0, w, w)), float)           # (an animated strip: its first frame)
    # a fallback: a diagonal stroke in the thing's colour
    col = FALLBACK_COLOURS.get(name, (200, 200, 200))
    im = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if name in TOOL_LAYOUT:
        (gx, gy), (tx, ty) = TOOL_LAYOUT[name]
        d.line([gx, gy, tx, ty], fill=col + (255,), width=3)
        d.line([gx, gy, gx + (tx - gx) * 0.3, gy + (ty - gy) * 0.3], fill=(120, 84, 52, 255), width=3)
    else:
        d.ellipse([8, 8, 24, 24], fill=col + (255,))
    return np.array(im, float)


class Preview:
    """Draws a posed player with what is in her hands and the stand-in station."""

    def __init__(self, skin_path=None, tex_dirs=()):
        if skin_path and os.path.exists(skin_path):
            skin = Image.open(skin_path).convert('RGBA')
        else:
            skin = fallback_skin()
        self.scene = Scene(skin, Image.new('RGBA', (16, 16), (0, 0, 0, 0)))
        self.tex_dirs = list(tex_dirs)
        self.tex = {}

    def texture(self, name):
        if name not in self.tex:
            self.tex[name] = find_texture(name, self.tex_dirs)
        return self.tex[name]

    def held_quads(self, pose, side, geo, tex_name):
        qr, pr = pose['root']
        q, pv = pose[side]
        M = T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q) @ S(16) @ geo.tp_item(left=(side == LEFT))
        tex = self.texture(tex_name)
        return [(apply(M, A), apply(M, Bq), apply(M, Dq), rect, 0.95, tex) for A, Bq, Dq, rect, face in sprite_quads(size=tex.shape[1])]

    def quads(self, spec, clip, t, station=True):
        pose = pose_at(spec, clip, t)
        quads = self.scene.player_world(pose, sword=False)
        for side, fn in spec.items.items():
            held = fn(t)
            if held:
                geo, name = held
                quads += self.held_quads(pose, side, geo, name)
        if station and spec.station:
            quads += box_quads_world(station_boxes(spec.station, spec.station_dx, spec.station_dz))
        quads += spec.extras(t)
        quads += ground_quads()
        return quads

    def image(self, spec, clip, t, size, view, ss=2, bg=(54, 60, 74)):
        yaw, pitch, zoom, cy = view[:4]
        sx = view[4] if len(view) > 4 else 0.0
        W, H = size
        proj = ortho(yaw, pitch, zoom * ss, (W * ss, H * ss), cy)
        shift = np.array([sx * ss, 0.0, 0.0])
        img = raster(self.quads(spec, clip, t), lambda p: proj(p) + shift, (W * ss, H * ss), bg=bg)
        return img.resize(size, Image.LANCZOS) if ss > 1 else img


def ground_quads(colour=(62, 58, 50)):
    tex = solid_tex(colour)
    P = lambda x, z: np.array([x, 24.0, z])
    return [(P(-36, -36), P(36, -36), P(-36, 36), (0, 0, 1, 1), 1.0, tex)]


def contact_sheet(prev, spec, clip, times, views, cell=(220, 260), label=True):
    """A sheet: a column for each of [times], a row for each of [views] ((yaw, pitch, zoom, centre y)), as the clip is posed
    by the game's own chain."""
    W, H = cell
    out = Image.new('RGB', (W * len(times), H * len(views) + 16), (30, 32, 40))
    d = ImageDraw.Draw(out)
    d.text((4, 3), f'{spec.name}  {clip.length:g} ticks  rate {clip.rate}  blend {clip.blend_in:g}/{clip.blend_out:g}', fill=(235, 235, 235))
    for i, t in enumerate(times):
        for j, v in enumerate(views):
            out.paste(prev.image(spec, clip, t, cell, v), (i * W, 16 + j * H))
        d.text((i * W + 4, 20), f't={t:g}  w={clip.weight(t):.2f}', fill=(255, 235, 150))
    return out


# ================================================================== the checks a clip is held to
# Each looks at the baked clip, posed by the game's own chain at full weight (the blends taken off), twice a tick; none of them
# looks at the solve's own working, so they hold the clip to what the game will show.

LEG_CORNERS = np.array([(x, y, z) for x in (-2.0, 2.0) for y in (0.0, 12.0) for z in (-2.0, 2.0)])


class Report(list):
    """The printed checks: a line each, flagged '!! OFF' when the number is outside what it is held to."""
    bad = 0

    def line(self, text, ok=True, notes=''):
        if not ok:
            self.bad += 1
        self.append(f'  {text}' + ('' if ok else '   !! OFF') + notes)


_FULL, _POSED = {}, {}


def full_weight(clip):
    """The clip with its blends taken off, so that every tick of it is posed at full weight."""
    if id(clip) not in _FULL:
        c = copy.copy(clip)
        c.blend_in = c.blend_out = 0.0
        _FULL[id(clip)] = c
    return _FULL[id(clip)]


def pose_full(spec, clip, t):
    key = (id(clip), round(float(t), 4))
    if key not in _POSED:
        _POSED[key] = pose_clip(full_weight(clip), t, base=base_pose(spec.look(t), held_sides(spec, t)))
    return _POSED[key]


def ticks_of(clip, step=0.5):
    return [i * step for i in range(int(round(clip.length / step)) + 1)]


def part_pts(pose, part, pts):
    """Points of a part's own space (N x 3) in the player's frame."""
    qr, pr = pose['root']
    q, pv = pose[part]
    M = T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q)
    return np.atleast_2d(np.asarray(pts, float)) @ M[:3, :3].T + M[:3, 3]


def held_pts(pose, side, geo, us):
    """Points [us] of the way from the grip to the tip of a held [geo] (0 to 1) in the player's frame."""
    return np.array([tool_along(pose, side, geo, u) for u in us])


def whole_body(pose, legs=True):
    """The player's frame points of her arms, body, head and legs (the groups the keep-out checks look at)."""
    g = {RIGHT: part_pts(pose, RIGHT, ARM_GRID[RIGHT]), LEFT: part_pts(pose, LEFT, ARM_GRID[LEFT]),
         'body': part_pts(pose, 'body', BODY_CORNERS), 'head': part_pts(pose, 'head', HEAD_CORNERS + np.array([0.0, -4.0, 0.0]))}
    if legs:
        g['right_leg'] = part_pts(pose, 'right_leg', LEG_CORNERS)
        g['left_leg'] = part_pts(pose, 'left_leg', LEG_CORNERS)
    return g


def keepout(rep, spec, clip, label, solids, extra=None, tol=0.15, legs=True, only=None, window=None):
    """The deepest her arms, body, head and legs (and [extra]: {name: fn(pose) -> points}, a held tool) go into any of [solids] ((lo, hi)
    boxes in the player's frame), over the clip at full weight. [only]: just these groups of them."""
    worst = (0.0, '', 0.0)
    for t in ticks_of(clip):
        if window is not None and not (window[0] <= t <= window[1]):
            continue
        pose = pose_full(spec, clip, t)
        groups = whole_body(pose, legs) if only is None else {}
        for name, fn in (extra or {}).items():
            groups[name] = fn(pose)
        for name, pts in groups.items():
            for lo, hi in solids:
                d = float(depth_in_boxes(pts, lo, hi).max())
                if d > worst[0]:
                    worst = (d, name, t)
    where = f' ({worst[1].replace("_", " ")}, t={worst[2]:g})' if worst[0] > 0 else ''
    rep.line(f'{label}: deepest into the station {worst[0]:.2f} px (held to {tol:g}){where}', worst[0] <= tol)
    return worst[0]


def seg_dist(p, a, b):
    ab = b - a
    u = min(1.0, max(0.0, float(np.dot(p - a, ab)) / max(float(np.dot(ab, ab)), 1e-9)))
    return float(np.linalg.norm(p - (a + ab * u)))


def on_shaft(rep, spec, clip, label, geo, holder, other, times, lo=0.0, hi=1.0, tol=1.8):
    """The [other] hand's closing point on the shaft of the [geo] [holder] has: how far it is off the line of the shaft, between
    [lo] and [hi] of the way along it (the worst of [times])."""
    worst = (0.0, 0.0)
    for t in times:
        pose = pose_full(spec, clip, t)
        a, b = held_pts(pose, holder, geo, (lo, hi))
        h = part_pts(pose, other, [hand_pt(other)])[0]
        d = seg_dist(h, a, b)
        if d > worst[0]:
            worst = (d, t)
    rep.line(f'{label}: the {other.split("_")[0]} fist off the shaft by {worst[0]:.2f} px at the most (held to {tol:g}, t={worst[1]:g})', worst[0] <= tol)


def point_at(rep, spec, clip, label, times, got, want, tol=0.5):
    """[got] (fn(pose) -> a point) meets [want] (fn(t) -> a point, or a point) at each of [times]: the worst miss."""
    worst = (0.0, 0.0)
    for t in times:
        pose = pose_full(spec, clip, t)
        g = np.asarray(got(pose), float)
        w = np.asarray(want(t) if callable(want) else want, float)
        d = float(np.linalg.norm(g - w))
        if d > worst[0]:
            worst = (d, t)
    rep.line(f'{label}: off by {worst[0]:.2f} px at the most (held to {tol:g}, t={worst[1]:g})', worst[0] <= tol)


def self_clear(rep, spec, clip, label, tol=0.3, arm_tol=None):
    """Her arms and her body and head keep out of each other: the deepest a point of one goes into the other's box ([arm_tol]: for one
    arm into the other, where the two hold one thing the forearms may touch)."""
    worst, worst_arm = (0.0, '', 0.0), (0.0, '', 0.0)
    for t in ticks_of(clip):
        pose = pose_full(spec, clip, t)
        arms = {s: part_pts(pose, s, ARM_GRID[s]) for s in SIDES}
        body = part_pts(pose, 'body', [(-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)])
        # the body's box in its own space: the arms' points taken there
        qr, pr = pose['root']
        q, pv = pose['body']
        M = np.linalg.inv(T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q))
        for s in SIDES:
            local = arms[s] @ M[:3, :3].T + M[:3, 3]
            d = float(depth_in_boxes(local, (-4.0, 0.0, -2.0), (4.0, 12.0, 2.0)).max())
            if d > worst[0]:
                worst = (d, f'{s} in the body', t)
        qh, ph = pose['head']
        Mh = np.linalg.inv(T(*pr) @ Rq(qr) @ T(*ph) @ Rq(qh))
        for s in SIDES:
            local = arms[s] @ Mh[:3, :3].T + Mh[:3, 3]
            d = float(depth_in_boxes(local, (-4.0, -8.0, -4.0), (4.0, 0.0, 4.0)).max())
            if d > worst[0]:
                worst = (d, f'{s} in the head', t)
        # one arm into the other: the right's grid in the left arm's own box
        ql, pl = pose['left_arm']
        Ml = np.linalg.inv(T(*pr) @ Rq(qr) @ T(*pl) @ Rq(ql))
        local = arms[RIGHT] @ Ml[:3, :3].T + Ml[:3, 3]
        d = float(depth_in_boxes(local, (-1.0, -2.0, -2.0), (3.0, 10.0, 2.0)).max())
        if d > worst_arm[0]:
            worst_arm = (d, 'one arm in the other', t)
    where = f' ({worst[1]}, t={worst[2]:g})' if worst[0] > 0 else ''
    rep.line(f'{label}: deepest into her own body {worst[0]:.2f} px (held to {tol:g}){where}', worst[0] <= tol)
    a_tol = tol if arm_tol is None else arm_tol
    where = f' (t={worst_arm[2]:g})' if worst_arm[0] > 0 else ''
    rep.line(f'{label}: deepest one forearm goes into the other {worst_arm[0]:.2f} px (held to {a_tol:g}){where}', worst_arm[0] <= a_tol)


def feet_planted(rep, spec, clip, label, tol=0.3):
    """Each foot that is meant to be planted is on the ground, and does not slide while it is: the worst lift and slide."""
    worst_lift, worst_slide = 0.0, 0.0
    prev = {}
    for t in ticks_of(clip):
        pose = pose_full(spec, clip, t)
        for leg, f in spec.feet.items():
            fx, fz, lift = f(t)
            p = part_pts(pose, leg, [(0.0, 12.0, 0.0)])[0]
            worst_lift = max(worst_lift, abs(float(p[1]) - (GROUND - lift)))
            if lift < 0.01:
                if leg in prev:
                    worst_slide = max(worst_slide, float(np.hypot(p[0] - prev[leg][0], p[2] - prev[leg][1])))
                prev[leg] = (float(p[0]), float(p[2]))
            else:
                prev.pop(leg, None)
    rep.line(f'{label}: the soles within {worst_lift:.2f} px of the ground; a planted foot slides {worst_slide:.2f} px a half tick at the most',
             worst_lift <= tol)


def motion_report(rep, clip, label='', limit=None):
    """How fast each bone turns (degrees a tick) and how sharply its speed changes: what to look at if a clip seems to snap."""
    ts = [i * 0.5 for i in range(int(clip.length * 2) + 1)]
    parts = []
    for bone in clip.keys:
        qs = [clip.sample(bone, t)[0] for t in ts]
        sp = [math.degrees(2 * math.acos(min(1.0, abs(float(np.dot(a, b)))))) * 2.0 for a, b in zip(qs, qs[1:])]
        parts.append(f'{bone} {max(sp):.0f}')
    rep.line(f'{label}fastest turn per bone (deg per tick): ' + ', '.join(parts))


# ================================================================== helpers for building clips

def foot_track(rest, moves, lift_h=1.2):
    """fn(t) -> (x, z, lift) for a foot that starts at [rest] (x, z) and takes each of [moves] ((from, to, (x, z)) ticks) in turn: it
    lifts [lift_h] px and goes over, planted again at the end of each."""
    moves = sorted(moves, key=lambda m: m[0])

    def at_(t):
        x, z = rest
        for t0, t1, (nx, nz) in moves:
            if t <= t0:
                break
            u = smooth01((t - t0) / (t1 - t0))
            if t < t1:
                return (x + (nx - x) * u, z + (nz - z) * u, lift_h * math.sin(math.pi * u))
            x, z = nx, nz
        return (x, z, 0.0)
    return at_


REST_FEET = {'right_leg': (-1.9, 0.0), 'left_leg': (1.9, 0.0)}


def circle_pos(centre, r, phi, y=None):
    """The point on a horizontal circle round [centre], [r] out, [phi] radians clockwise from straight ahead as seen from above."""
    c = np.asarray(centre, float)
    return np.array([c[0] - r * math.sin(phi), c[1] if y is None else y, c[2] - r * math.cos(phi)])


def stroke_phase(t, t0, t1, turns, wobble=0.0):
    """The angle (radians) of a stroke that turns [turns] times between ticks [t0] and [t1], easing in and out, its speed varying by
    [wobble] (a fraction) once a turn so that it has a heavy side and a light one."""
    u = min(max((t - t0) / (t1 - t0), 0.0), 1.0)
    e = u * u * (3.0 - 2.0 * u) * 0.5 + u * 0.5            # ease in and out, but keep most of the middle at an even pace
    phi = 2.0 * math.pi * turns * e
    return phi + wobble * math.sin(phi)


# ================================================================== cook_stir

SPECS = {}                    # name -> builder: fn() -> Spec


def spec_stir():
    """cook_stir (24): the ladle let down into the pot and stirred round it, the body rocking with the stroke from the hips, the head
    following the swirl a beat behind. One hand holds the ladle (it is 10 px, a block arm has no elbow, and two fists on it overlap
    and fold the arms across her face); the other arm hangs from the leaning body and trails its rock."""
    sp = Spec('cook_stir', 24, rate=4, blend=(1.5, 3.0))
    DX = POT_DX                                           # she stands with the pot a little to her right, as a right-handed cook does
    ROLL = 1.5                                            # the arm's roll on its way in and out runs from about -0.3 to 3.0 radians
    sp.station = 'pot'
    sp.station_dx, sp.station_dz = DX, POT_DZ
    sp.solids = solids_of('pot', DX, POT_DZ)
    sp.items = {RIGHT: lambda t: (LADLE, 'cooks_ladle')}
    work = tool_pt(LADLE, RIGHT, 0.93)                    # the ladle's bowl
    blade = tool_blade(LADLE, RIGHT)
    T0, T1, TURNS = 5.5, 18.0, 1.0                        # the circles: from, to, and how many
    R_BOWL = 1.5                                          # the bowl's circle (the pot's mouth is 3 across a side)
    K_TILT = 0.2                                          # how far the ladle leans over its pivot at the pot's mouth: the hand circles the other way
    phi = lambda t: stroke_phase(t, T0 - 1.5, T1, TURNS + 0.1, wobble=0.14)
    env_r = lambda t: pulse(t, 0.0, 5.5, 19.5, 24.0)

    def tip(t):
        """The ladle's bowl: let down into the stew, round it, and lifted out (and out to her right, away from her face)."""
        p = phi(t)
        r = R_BOWL * (0.3 + 0.7 * ramp(t, T0 - 1.0, T0 + 2.5))
        down = ramp(t, 0.5, T0) * (1.0 - ramp(t, T1, 20.0))
        y = POT_SURFACE + 1.4 + 0.35 * math.sin(p + 0.6) - 6.5 * (1.0 - down)
        out = ramp(t, 19.0, 21.5) * 2.5
        return circle_pos(POT_MID, r, p, y=y) + RIGHTWARD * out

    FREE = ('torso', unit(DOWN * 0.97 + FWD * 0.12 + RIGHTWARD * 0.1), None)         # hanging from the leaning body, a little forward and out

    def want_blade(t):
        # nearly upright, its bowl leaning ahead of the hand, and over toward where the bowl is on its circle: the ladle turns about
        # the pot's mouth, so the hand goes round the other way
        off = tip(t) - POT_MID
        off[1] = 0.0
        return unit(DOWN * 0.97 + FWD * 0.2 + K_TILT * off / R_BOWL)

    def carried(t):
        # the ladle is carried as the hanging hand has it, bowl forward: it tips down into the pot as the hand comes over it, and back up
        # as it leaves, so the wrist rolls late in the swing in and early in the swing out, when the arm is well clear of her body
        return 1.0 - env_r(t)

    def right_plan(t, ctx):
        return Plan(free=FREE, w=env_r(t), work=work, target=tip(t), blade=blade, want=want_blade(t), arc=(0.3, 0.1), roll=ROLL, carry=carried(t))

    def left_plan(t, ctx):
        e = env_r(t)
        # hanging from the leaning body, a little forward of it, and trailing its rock
        d = unit(DOWN * 0.97 + FWD * (0.22 + 0.1 * math.sin(phi(t) + 0.5)) + LEFTWARD * (0.12 + 0.1 * math.sin(phi(t) + 1.7)))
        return Plan(free=('torso', d, None))
    sp.arms = {RIGHT: right_plan, LEFT: left_plan}
    e = env_r
    sp.crouch = lambda t: 3.0 * e(t)
    sp.style.update(pitch=lambda t: 28.0 * e(t) + 3.5 * math.sin(phi(t) + 0.4) * e(t),
                    yaw=lambda t: 4.0 * e(t) + 5.0 * math.sin(phi(t) + 0.9) * e(t),
                    roll=lambda t: 3.5 * math.sin(phi(t) - 0.6) * e(t),
                    rx=lambda t: 0.9 * math.sin(phi(t) + 1.5) * e(t),
                    rz=lambda t: -2.0 * e(t) + 0.9 * math.sin(phi(t) + 0.2) * e(t))
    sp.look = lambda t: (13.0 * pulse(t, 0.0, 5.0, 17.5, 22.5), 0.0)
    # the head kept up off her arm (she looks into the pot over it), following the swirl a beat behind
    sp.head = lambda t: (-3.0 * e(t) - 2.5 * e(t) * math.sin(phi(t - 2.5) + 1.2), -5.0 * e(t) - 4.0 * e(t) * math.sin(phi(t - 2.5)),
                         2.0 * e(t) * math.sin(phi(t - 2.5) + 0.5))
    sp.prior = dict(pitch=0.03, yaw=0.3, roll=0.3, rx=0.6, rz=0.4)       # the body keeps to its own sway: the reach is made by the arm and the lean
    sp.bounds = ([-15, -20, -12, -5, -7], [70, 20, 12, 5, 7])
    rest = REST_FEET
    sp.feet = {'right_leg': foot_track(rest['right_leg'], [(1.5, 4.0, (-3.2, 2.0)), (19.5, 23.0, rest['right_leg'])], 0.8),
               'left_leg': foot_track(rest['left_leg'], [(0.5, 3.0, (3.0, -2.5)), (19.0, 22.5, rest['left_leg'])], 1.2)}

    def checks(rep, clip, spec):
        keepout(rep, spec, clip, 'the pot and the fire: her arms, body, head and legs', spec.solids, tol=0.15)
        keepout(rep, spec, clip, "the pot's walls and the hearth: the ladle", shell_of('pot', DX, POT_DZ), tol=0.35, legs=False, only=('ladle',),
                extra={'ladle': lambda p: held_pts(p, RIGHT, LADLE, np.linspace(0.0, 0.97, 12))})
        mid = [t for t in ticks_of(clip) if T0 + 1.0 <= t <= T1 - 1.0]
        point_at(rep, spec, clip, "the ladle's bowl in the stew, round the pot", mid, lambda p: tool_along(p, RIGHT, LADLE, 0.93), tip, tol=0.4)
        self_clear(rep, spec, clip, 'arms clear of the body, the head and each other', tol=0.7, arm_tol=0.7)
        feet_planted(rep, spec, clip, 'feet')
    sp.checks.append(checks)
    return sp


SPECS['cook_stir'] = spec_stir


# ================================================================== the pot's clips

ROLL_MID = -1.5                                         # radians: the middle of the wrist rolls of a ladle going in and out of the pot
LADLE_WORK = tool_pt(LADLE, RIGHT, 0.93)               # the ladle's bowl, in the arm's own space
LADLE_BLADE = tool_blade(LADLE, RIGHT)
RFREE = ('torso', unit(DOWN * 0.97 + FWD * 0.12 + RIGHTWARD * 0.1), None)     # the arms hang from the leaning body, a little forward
LFREE = ('torso', unit(DOWN * 0.97 + FWD * 0.22 + LEFTWARD * 0.12), None)


def over_pot(x=0.0, y=POT_SURFACE, z=0.0):
    """A point over the pot: [x] px from its middle toward her left, [z] toward her, at clip height [y] (y down)."""
    return np.array([POT_MID[0] + x, y, POT_MID[2] + z])


def vec_path(keys):
    """A curve through (tick, direction) keys that comes out a unit direction."""
    f = curve([(t, unit(v)) for t, v in keys])
    return lambda t: unit(f(t))


def pot_body(sp, e, look=13.0, pitch=28.0, step_in=(1.5, 4.0), step_out=(19.5, 23.0), crouch=3.0, rz=-2.0):
    """The body at the stewpot as cook_stir has it: leaning in over the pot from a crouch, hips back, the feet stepped into a stance
    (the right foot back and out, the left forward) and out of it again, the head looking down into the stew; [e] is how far into the
    stance she is, tick by tick."""
    sp.station, sp.station_dx, sp.station_dz = 'pot', POT_DX, POT_DZ
    sp.solids = solids_of('pot', POT_DX, POT_DZ)
    sp.crouch = lambda t: crouch * e(t)
    sp.style.update(pitch=lambda t: pitch * e(t), rz=lambda t: rz * e(t))
    sp.look = lambda t: (look * e(t), 0.0)
    sp.prior = dict(pitch=0.03, yaw=0.3, roll=0.3, rx=0.6, rz=0.4)
    sp.bounds = ([-15, -20, -12, -5, -7], [70, 20, 12, 5, 7])
    rest = REST_FEET
    sp.feet = {'right_leg': foot_track(rest['right_leg'], [(step_in[0], step_in[1], (-3.2, 2.0)), (step_out[0], step_out[1], rest['right_leg'])], 0.8),
               'left_leg': foot_track(rest['left_leg'], [(step_in[0] - 1.0, step_in[1] - 1.0, (3.0, -2.5)), (step_out[0] - 0.5, step_out[1] - 0.5, rest['left_leg'])], 1.2)}


def hang_point(ctx, side, free, work):
    """Where the arm's point [work] is when the arm hangs as [free] says, for the body in [ctx]."""
    R = qmat(qmul(ctx.body.qt, free_key(ctx.body, side, free)))
    return ctx.body.shoulder(side) + R @ np.asarray(work, float)


def hang_palm(ctx, side, free):
    """Where the arm's inner face looks when it hangs as [free] says."""
    return qmat(qmul(ctx.body.qt, free_key(ctx.body, side, free))) @ INNER[side]


def toward(side, free, work, point, k, bow=(2.6, 0.3, 0.8)):
    """fn(ctx) -> the point [k] (0 to 1) of the way from where the hanging hand has [work] to [point] (a world point, or fn(ctx)): an arm
    that goes from its hang to a place and back with a hand that moves in a line, whatever the body does meanwhile, bowed by [bow] px
    (outward, up, forward) at the middle of the way so that the arm goes round her body, not through it."""
    outward = RIGHTWARD if side == RIGHT else LEFTWARD
    bowv = (outward * bow[0] + UP * bow[1] + FWD * bow[2]) * math.sin(math.pi * min(1.0, max(0.0, k)))

    def target(ctx):
        h = hang_point(ctx, side, free, work)
        p = point(ctx) if callable(point) else np.asarray(point, float)
        return h + (p - h) * k + bowv
    return target


def ladle_plan(t, k, point, want, kr=None):
    """The right arm with the ladle: its bowl [k] (0 to 1) of the way from where the hanging hand has it to [point] (a world point, or
    fn(ctx)), the ladle along [want], and its wrist turned from the hanging hand's roll as far as [kr] (0 to 1; [k] if not given)."""
    kr = k if kr is None else kr
    return Plan(free=RFREE, w=1.0, work=LADLE_WORK, target=toward(RIGHT, RFREE, LADLE_WORK, point, k), blade=LADLE_BLADE, want=want,
                roll=ROLL_MID, carry=1.0 - kr, lean=k)


def bare_plan(t, side, free, work, point, k):
    """An arm that carries [work] [k] of the way from the hang to [point], keeping the inner face the way the hanging hand has it."""
    return Plan(free=free, w=1.0, work=work, target=toward(side, free, work, point, k), palm=lambda ctx: hang_palm(ctx, side, free), lean=k)


def reach_on(side, work, p):
    """fn(ctx) -> where the arm's [work] point can be that is nearest [p] (a world point, or fn(ctx)): an arm is rigid, so its work point
    keeps its distance from the shoulder; this is the point at that distance on the way from the shoulder through [p]."""
    r = float(np.linalg.norm(work))

    def target(ctx):
        q = p(ctx) if callable(p) else np.asarray(p, float)
        J = ctx.body.shoulder(side)
        return J + r * unit(q - J)
    return target


def pot_checks(tip_fn, window, tip_label, tip_tol=0.4, self_tol=1.5):
    """The checks of a clip at the stewpot with the ladle: nothing of her goes through the pot or the fire, the ladle's bowl is where
    the clip puts it for [window], her arms keep out of her body, her head and each other, and her feet stay planted."""
    def checks(rep, clip, spec):
        keepout(rep, spec, clip, 'the pot and the fire: her arms, body, head and legs', spec.solids, tol=0.15)
        keepout(rep, spec, clip, "the pot's walls and the hearth: the ladle", shell_of('pot', POT_DX, POT_DZ), tol=0.35, legs=False, only=('ladle',),
                extra={'ladle': lambda p: held_pts(p, RIGHT, LADLE, np.linspace(0.0, 0.97, 12))})
        times = [t for t in ticks_of(clip) if window[0] <= t <= window[1]]
        point_at(rep, spec, clip, tip_label, times, lambda p: tool_along(p, RIGHT, LADLE, 0.93), tip_fn, tol=tip_tol)
        self_clear(rep, spec, clip, 'arms clear of the body, the head and each other', tol=self_tol, arm_tol=0.7)
        feet_planted(rep, spec, clip, 'feet')
    return checks


# ================================================================== cook_skim

def spec_skim():
    """cook_skim (16): the ladle laid on the far side of the stew, drawn across the surface, and flicked out over the rim."""
    sp = Spec('cook_skim', 16, rate=4, blend=(1.0, 3.0))
    e = lambda t: pulse(t, 0.0, 3.5, 11.0, 16.0)
    pot_body(sp, e, look=12.0, step_in=(0.5, 3.0), step_out=(12.0, 15.0))
    sp.items = {RIGHT: lambda t: (LADLE, 'cooks_ladle')}
    S0 = POT_SURFACE + 0.5
    path = curve([(0.0, over_pot(3.8, S0 - 5.5, -1.5)), (3.5, over_pot(2.7, S0, -1.5)), (8.5, over_pot(-2.2, S0, 0.8)),
                  (10.0, over_pot(-3.4, S0 - 3.6, 1.2)), (11.5, over_pot(-6.4, POT_RIM - 4.0, 1.6)), (16.0, over_pot(-7.5, POT_RIM - 5.0, 2.0))])
    want = vec_path([(0.0, DOWN * 0.7 + FWD * 0.7), (3.5, DOWN * 0.62 + FWD * 0.75 + RIGHTWARD * 0.2), (8.5, DOWN * 0.55 + FWD * 0.7 + RIGHTWARD * 0.45),
                     (10.5, DOWN * 0.2 + FWD * 0.3 + RIGHTWARD * 0.9), (16.0, DOWN * 0.2 + FWD * 0.3 + RIGHTWARD * 0.9)])
    sp.arms = {RIGHT: lambda t, ctx: ladle_plan(t, e(t), path(t), want(t)), LEFT: lambda t, ctx: Plan(free=LFREE)}
    sp.head = lambda t: (-2.0 * e(t), -3.0 * e(t) * math.sin(math.pi * min(1.0, max(0.0, (t - 3.5) / 5.5))), 0.0)
    sp.checks.append(pot_checks(path, (3.5, 8.5), "the ladle's bowl on the stew's surface, drawn across it", tip_tol=0.4))
    return sp


SPECS['cook_skim'] = spec_skim


# ================================================================== cook_serve

def spec_serve():
    """cook_serve (20): the ladle dips into the stew and rises full, goes to the pot's left lip and tips toward the bowl her left hand holds
    out there, and comes back, as the arms come down."""
    sp = Spec('cook_serve', 20, rate=4, blend=(1.0, 3.0))
    e = lambda t: pulse(t, 0.0, 4.5, 15.5, 20.0)
    el = lambda t: pulse(t, 1.0, 5.5, 16.0, 20.0)
    pot_body(sp, e, look=10.0, step_in=(0.5, 3.0), step_out=(16.0, 19.0), crouch=3.0, pitch=26.0)
    sp.items = {RIGHT: lambda t: (LADLE, 'cooks_ladle'), LEFT: lambda t: (BOWL, 'bowl')}
    bowl_at = np.array([POT_MID[0] + 6.8, 13.2, POT_MID[2] + 1.2])        # held out over the pot's left lip, level with it
    work_l = tool_pt(BOWL, LEFT, 0.0)
    S0 = POT_SURFACE + 1.6
    path = curve([(0.0, over_pot(0.0, S0 - 6.0, 0.0)), (4.5, over_pot(0.2, S0, 0.2)), (6.0, over_pot(0.3, S0 + 0.4, 0.3)),
                  (8.5, over_pot(1.0, POT_RIM - 0.8, 1.0)), (11.0, over_pot(2.2, POT_RIM - 1.0, 0.8)), (13.0, over_pot(2.6, POT_RIM - 0.6, 0.6)),
                  (14.5, over_pot(0.8, POT_RIM - 1.0, 1.0)), (16.5, over_pot(-1.5, POT_RIM - 2.5, 2.0)), (20.0, over_pot(-3.0, POT_RIM - 4.0, 3.0))])
    want = vec_path([(0.0, DOWN * 0.7 + FWD * 0.6), (4.5, DOWN * 0.85 + FWD * 0.55), (8.5, DOWN * 0.45 + FWD * 0.9), (11.0, DOWN * 0.45 + FWD * 0.6 + LEFTWARD * 0.5),
                     (13.0, DOWN * 0.45 + FWD * 0.4 + LEFTWARD * 0.85), (14.5, DOWN * 0.5 + FWD * 0.8 + LEFTWARD * 0.2), (16.5, DOWN * 0.5 + FWD * 0.85),
                     (20.0, DOWN * 0.7 + FWD * 0.6)])

    def bowl_pos(t):
        # held out, level with the lip, and offered up a little as the ladle comes to it
        return bowl_at + np.array([0.0, -0.8 * pulse(t, 8.5, 11.0, 14.0, 16.0), 0.0])
    sp.arms = {RIGHT: lambda t, ctx: ladle_plan(t, e(t), path(t), want(t)),
               LEFT: lambda t, ctx: bare_plan(t, LEFT, LFREE, work_l, bowl_pos(t), el(t))}
    sp.head = lambda t: (-2.0 * e(t), 4.0 * pulse(t, 8.0, 11.0, 14.0, 17.0), 0.0)
    sp.checks.append(pot_checks(path, (5.0, 6.5), "the ladle's bowl in the stew while it dips", tip_tol=0.4))

    def bowl_checks(rep, clip, spec):
        times = [t for t in ticks_of(clip) if 6.5 <= t <= 15.0]
        point_at(rep, spec, clip, 'the bowl in her left hand held out where it is meant to be', times,
                 lambda p: part_pts(p, LEFT, [work_l])[0], bowl_pos, tol=0.6)
        times = [t for t in ticks_of(clip) if 11.0 <= t <= 14.0]
        point_at(rep, spec, clip, "the ladle's bowl at the lip while it tips", times, lambda p: tool_along(p, RIGHT, LADLE, 0.93), path, tol=0.4)
    sp.checks.append(bowl_checks)
    return sp


SPECS['cook_serve'] = spec_serve


# ================================================================== cook_taste

def spec_taste():
    """cook_taste (40): the ladle dipped, lifted out of the pot and held up in front of her face, the head bowing to it (a blow, a sip), a look
    aside, and the ladle lowered again. A straight arm that holds a ladle by its end cannot bring its bowl to her lips: the bowl is always
    14.3 px from her shoulder and her lips 8 to 9, and the arm itself would be in her face; so the ladle is held out ahead of her face and
    she meets it with her head."""
    sp = Spec('cook_taste', 40, rate=2, blend=(1.5, 4.0))
    e = lambda t: pulse(t, 0.0, 5.0, 33.0, 38.0)                      # at the pot, and leaving it
    stand = lambda t: 1.0 - ramp(t, 9.0, 15.0)                        # how far over the pot she still is (she straightens to taste)
    pot_body(sp, e, look=13.0, step_in=(1.0, 4.0), step_out=(33.0, 37.0))
    sip = lambda t: pulse(t, 21.5, 23.5, 24.5, 26.5)
    sp.style.update(pitch=lambda t: 26.0 * e(t) * stand(t) + 5.0 * e(t) * (1.0 - stand(t)) + 3.0 * sip(t),
                    rz=lambda t: -2.0 * e(t) * stand(t))
    sp.crouch = lambda t: 3.0 * e(t) * stand(t)
    sp.look = lambda t: (13.0 * e(t) * stand(t), 0.0)
    sp.items = {RIGHT: lambda t: (LADLE, 'cooks_ladle')}
    S0 = POT_SURFACE + 1.5
    scoop = curve([(0.0, over_pot(0.0, S0 - 6.0, 0.0)), (5.0, over_pot(0.3, S0, 0.2)), (7.5, over_pot(0.4, S0 + 0.3, 0.3)), (10.0, over_pot(1.0, POT_RIM - 1.5, 1.0))])
    HELD = np.array([1.0, -1.2, -8.6])                                # the point in front of her face (in her torso's own space) the bowl is held toward
    held = reach_on(RIGHT, LADLE_WORK, lambda ctx: ctx.body.torso_point(HELD))

    def tip(t):
        k = ramp(t, 10.0, 15.0) * (1.0 - ramp(t, 30.0, 35.0))                  # how far it is at her face
        p_pot = scoop(min(t, 10.0))
        return lambda ctx: p_pot + (held(ctx) - p_pot) * k if k > 1e-4 else p_pot

    want_pot = vec_path([(0.0, DOWN * 0.7 + FWD * 0.6), (5.0, DOWN * 0.85 + FWD * 0.55), (10.0, DOWN * 0.45 + FWD * 0.9)])
    want_face = unit(UP * 0.8 + LEFTWARD * 0.35 + FWD * 0.5)

    def want(t):
        k = ramp(t, 10.0, 15.0) * (1.0 - ramp(t, 30.0, 35.0))
        w_face = unit(want_face + UP * 0.25 * sip(t) + FWD * 0.15 * sip(t))
        return slerp_dir(want_pot(min(t, 10.0)), w_face, k)

    # the hand goes out of its hang (k) and the wrist turns (kr), both over by the time the ladle is out of the pot, and back at the end
    k_of = lambda t: pulse(t, 0.0, 5.0, 31.0, 38.0)
    sp.arms = {RIGHT: lambda t, ctx: ladle_plan(t, k_of(t), tip(t), want(t), kr=k_of(t)), LEFT: lambda t, ctx: Plan(free=LFREE)}
    # the head turns to the ladle (to her left: her lips go the farthest from her right shoulder), nods to blow, bows to sip, looks aside to her right
    sp.head = lambda t: (-3.0 * e(t) * stand(t) + 5.0 * pulse(t, 12.0, 15.0, 33.0, 37.0) + 6.0 * pulse(t, 15.5, 17.0, 18.0, 19.5) + 7.0 * sip(t)
                         - 8.0 * pulse(t, 27.0, 29.0, 30.5, 33.0),
                         -22.0 * pulse(t, 11.0, 15.0, 26.5, 29.0) + 30.0 * pulse(t, 27.5, 30.0, 31.0, 34.0), 3.0 * pulse(t, 27.0, 29.0, 30.5, 33.0))
    sp.marks = {'blow': 17.0, 'sip': 23.5, 'aside': 29.0}

    def checks(rep, clip, spec):
        keepout(rep, spec, clip, 'the pot and the fire: her arms, body, head and legs', spec.solids, tol=0.15)
        keepout(rep, spec, clip, "the pot's walls and the hearth: the ladle", shell_of('pot', POT_DX, POT_DZ), tol=0.35, legs=False, only=('ladle',),
                extra={'ladle': lambda p: held_pts(p, RIGHT, LADLE, np.linspace(0.0, 0.97, 12))})
        times = [t for t in ticks_of(clip) if 5.0 <= t <= 7.5]
        point_at(rep, spec, clip, "the ladle's bowl in the stew while it scoops", times, lambda p: tool_along(p, RIGHT, LADLE, 0.93), lambda t: scoop(t), tol=0.4)

        def lip_gap(t):
            pose = pose_full(spec, clip, t)
            bowl = tool_along(pose, RIGHT, LADLE, 0.93)
            qr, pr = pose['root']
            q, pv = pose['head']
            M = T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q)
            return float(np.linalg.norm(bowl - apply(M, MOUTH)))
        sipping = [t for t in ticks_of(clip) if 22.0 <= t <= 25.0]
        worst = max((lip_gap(t), t) for t in sipping)
        best = min((lip_gap(t), t) for t in sipping)
        rep.line(f"the ladle's bowl from her lips at the sip: {best[0]:.1f} px at the nearest (t={best[1]:g}), {worst[0]:.1f} at the farthest; a straight arm "
                 f"holding a ladle by its end cannot bring it nearer than about 6 (held to 8)", worst[0] <= 8.0)
        self_clear(rep, spec, clip, 'arms clear of the body, the head and each other', tol=1.5, arm_tol=0.7)
        feet_planted(rep, spec, clip, 'feet', tol=0.7)
    sp.checks.append(checks)
    return sp


SPECS['cook_taste'] = spec_taste


# ================================================================== the counter's clips (the prep board, the hob)

def tool_plan(side, geo, u, free, point, want, k, kr=None, roll=0.0, bow=None, lean=None):
    """The arm [side] with [geo] in its hand: the tool's point [u] (0: the hand, 1: the working end) [k] (0 to 1) of the way from where the
    hanging hand has it to [point] (a world point, or fn(ctx)), the tool along [want], the wrist turned from the hanging hand's as far as [kr]
    ([k] if not given)."""
    work, blade = tool_pt(geo, side, u), tool_blade(geo, side)
    kr = k if kr is None else kr
    tgt = toward(side, free, work, point, k) if bow is None else toward(side, free, work, point, k, bow)
    return Plan(free=free, w=1.0, work=work, target=tgt, blade=blade, want=want, roll=roll, carry=1.0 - kr, lean=k if lean is None else lean)


def hand_plan(side, free, point, k, palm=None, work=None):
    """A bare hand that goes [k] of the way from the hang to [point], its inner face toward [palm] (a world direction; the hang's if None)."""
    work = hand_pt(side) if work is None else work
    pm = (lambda ctx: hang_palm(ctx, side, free)) if palm is None else palm
    return Plan(free=free, w=1.0, work=work, target=toward(side, free, work, point, k), palm=pm, lean=k)


def loosely(plan, strength):
    """[plan] with its reach held less firmly (a hand that matters less gives way to the body and the other hand)."""
    plan.strength = strength
    return plan


def work_body(sp, e, name, dx, dz, pitch=24.0, crouch=2.0, look=12.0, rz=-1.5, yaw=0.0, step_in=(0.0, 1.5), step_out=(6.0, 8.0),
              stance=((-3.0, 1.5), (2.8, -2.0)), lift=(0.8, 1.0)):
    """The body at a station in front of her: leaning over it from a small crouch, the right foot back and out and the left forward,
    the head looking down at the work; [e] is how far into the stance she is, tick by tick."""
    sp.station, sp.station_dx, sp.station_dz = name, dx, dz
    sp.solids = solids_of(name, dx, dz)
    sp.crouch = lambda t: crouch * e(t)
    sp.style.update(pitch=lambda t: pitch * e(t), rz=lambda t: rz * e(t), yaw=lambda t: yaw * e(t))
    sp.look = lambda t: (look * e(t), 0.0)
    sp.prior = dict(pitch=0.03, yaw=0.3, roll=0.3, rx=0.6, rz=0.4)
    sp.bounds = ([-15, -14, -9, -5, -7], [70, 14, 9, 5, 7])
    rest = REST_FEET
    sp.feet = {'right_leg': foot_track(rest['right_leg'], [(step_in[0], step_in[1], stance[0]), (step_out[0], step_out[1], rest['right_leg'])], lift[0]),
               'left_leg': foot_track(rest['left_leg'], [(step_in[0], step_in[1], stance[1]), (step_out[0], step_out[1], rest['left_leg'])], lift[1])}


def station_checks(name, dx, dz, geo, tip_label, tip_times, tip_fn, tip_u=1.0, tip_tol=0.5, self_tol=1.5, side=RIGHT, shaft_to=0.97, solid_tol=0.15,
                   tool_tol=0.2, window=None):
    """The checks of a clip at a station with a tool in [side]'s hand: nothing of her goes through the station's click regions, the tool
    stays out of its shell, the tool's working end is where the clip puts it at [tip_times], her arms keep out of her body, head and each
    other, her feet stay planted."""
    def checks(rep, clip, spec):
        keepout(rep, spec, clip, 'the station: her arms, body, head and legs', spec.solids, tol=solid_tol, window=window)
        if geo is not None:
            keepout(rep, spec, clip, 'the station\'s shell: the tool', shell_of(name, dx, dz), tol=tool_tol, legs=False, only=('tool',),
                    extra={'tool': lambda p: held_pts(p, side, geo, np.linspace(0.0, shaft_to, 12))}, window=window)
        if tip_times:
            point_at(rep, spec, clip, tip_label, tip_times, lambda p: tool_along(p, side, geo, tip_u), tip_fn, tol=tip_tol)
        self_clear(rep, spec, clip, 'arms clear of the body, the head and each other', tol=self_tol, arm_tol=0.7)
        feet_planted(rep, spec, clip, 'feet', tol=0.7)
    return checks


# ================================================================== cook_chop

def spec_chop():
    """cook_chop (8): the knife's short chop at the board, twice, the left hand steadying the food."""
    sp = Spec('cook_chop', 8, rate=4, blend=(0.75, 2.5))
    DX, DZ = -3.0, 3.0
    e = lambda t: pulse(t, 0.0, 1.6, 5.2, 8.0)
    work_body(sp, e, 'board', DX, DZ, pitch=30.0, crouch=2.5, look=14.0, step_in=(0.0, 1.5), step_out=(5.5, 8.0))
    sp.bounds = ([-15, -18, -10, -6, -8], [70, 18, 10, 6, 8])
    sp.items = {RIGHT: lambda t: (KNIFE, 'cooks_knife'), LEFT: lambda t: None}
    cut = at((8.0, 10.2, 5.0), DX, DZ)                                    # the food on the board, near its front edge
    P = lambda dy, dz: cut + np.array([0.0, dy, dz])
    path = curve([(0.0, P(-5.0, 2.5)), (1.6, P(-3.6, 1.0)), (2.4, P(-0.5, 0.0)), (3.0, P(-2.6, 0.5)), (3.5, P(-3.2, 0.7)), (4.2, P(-0.6, 0.25)),
                  (5.0, P(-2.8, 1.0)), (8.0, P(-5.0, 2.5))])
    w_up, w_dn = unit(FWD * 0.95 + UP * 0.30), unit(FWD * 0.93 + DOWN * 0.37)
    want = vec_path([(0.0, w_up), (1.6, w_up), (2.4, w_dn), (3.0, w_up), (3.5, w_up), (4.2, w_dn), (5.0, w_up), (8.0, w_up)])
    k_of = lambda t: pulse(t, 0.0, 1.6, 5.2, 8.0)
    steady = cut + np.array([2.6, -2.2, 0.8])                              # the left hand's fingers on the food's far side
    sp.arms = {RIGHT: lambda t, ctx: tool_plan(RIGHT, KNIFE, 1.0, RFREE, path(t), want(t), k_of(t)),
               LEFT: lambda t, ctx: loosely(hand_plan(LEFT, LFREE, steady, k_of(t), palm=unit(DOWN * 0.9 + LEFTWARD * 0.3)), 0.35)}
    sp.head = lambda t: (-2.0 * e(t), 0.0, 0.0)
    sp.marks = {'chop1': 2.4, 'chop2': 4.2}
    sp.checks.append(station_checks('board', DX, DZ, KNIFE, "the knife's tip on the board at each chop", [2.5, 4.25], path, tip_tol=1.0, self_tol=2.0,
                                    solid_tol=0.4, tool_tol=0.3))
    return sp


SPECS['cook_chop'] = spec_chop


# ================================================================== cook_slice

def spec_slice():
    """cook_slice (16): the knife comes down through the pie twice, a wedge apart, then lies flat and slides the slice aside."""
    sp = Spec('cook_slice', 16, rate=4, blend=(1.0, 3.0))
    DX, DZ = -3.0, 3.0
    e = lambda t: pulse(t, 0.0, 1.5, 12.5, 16.0)
    work_body(sp, e, 'board', DX, DZ, pitch=30.0, crouch=2.5, look=14.0, step_in=(0.0, 2.0), step_out=(12.5, 15.0))
    sp.bounds = ([-15, -18, -10, -6, -8], [70, 18, 10, 6, 8])
    sp.items = {RIGHT: lambda t: (KNIFE, 'cooks_knife'), LEFT: lambda t: None}
    pie = at((8.0, 10.2, 6.4), DX, DZ)                                     # the pie's middle on the board
    P = lambda dx_, dy, dz: pie + np.array([dx_, dy, dz])
    path = curve([(0.0, P(0.5, -5.0, 3.5)), (1.5, P(0.5, -4.4, 2.6)), (3.0, P(0.2, -0.15, 1.6)), (3.8, P(0.2, -0.15, 1.6)), (5.0, P(-0.8, -4.0, 2.0)),
                  (6.5, P(-2.0, -0.15, 1.6)), (7.3, P(-2.0, -0.15, 1.6)), (8.3, P(-1.6, -2.0, 1.0)), (9.0, P(-1.6, -0.6, 0.2)),
                  (11.0, P(-4.2, -0.5, 0.4)), (12.5, P(-4.4, -3.0, 1.4)), (16.0, P(-4.0, -5.0, 3.0))])
    w_cut, w_up, w_flat = unit(FWD * 0.5 + DOWN * 0.85), unit(FWD * 0.85 + UP * 0.45), unit(FWD * 0.97 + DOWN * 0.2)
    want = vec_path([(0.0, w_up), (1.5, w_up), (3.0, w_cut), (3.8, w_cut), (5.0, w_up), (6.5, w_cut), (7.3, w_cut), (8.3, w_up), (9.0, w_flat),
                     (11.0, w_flat), (12.5, w_up), (16.0, w_up)])
    k_of = lambda t: pulse(t, 0.0, 1.5, 12.5, 16.0)
    steady = pie + np.array([5.2, -2.2, 1.4])                              # the left hand holds the pie's far side
    sp.arms = {RIGHT: lambda t, ctx: tool_plan(RIGHT, KNIFE, 1.0, RFREE, path(t), want(t), k_of(t)),
               LEFT: lambda t, ctx: loosely(hand_plan(LEFT, LFREE, steady, k_of(t), palm=unit(DOWN * 0.9 + LEFTWARD * 0.3)), 0.35)}
    sp.head = lambda t: (-2.0 * e(t), 0.0, 0.0)
    sp.marks = {'cut1': 3.0, 'cut2': 6.5, 'slide': 10.0}
    sp.checks.append(station_checks('board', DX, DZ, KNIFE, "the knife's tip on the board at the cuts and the slide", [3.0, 3.5, 6.5, 7.0, 9.5, 10.5], path, tip_tol=1.0, self_tol=2.0,
                                    solid_tol=0.4, tool_tol=0.3))
    return sp


SPECS['cook_slice'] = spec_slice


# ================================================================== cook_flip

def spec_flip():
    """cook_flip (14): the turner slid under the food in the pan, a flick of the wrist, the turner level again to catch it, and drawn out."""
    sp = Spec('cook_flip', 14, rate=4, blend=(1.0, 3.0))
    DX, DZ = -3.0, 3.0
    e = lambda t: pulse(t, 0.0, 2.0, 11.5, 14.0)
    work_body(sp, e, 'hob', DX, DZ, pitch=24.0, crouch=2.0, look=14.0, step_in=(0.0, 2.0), step_out=(11.0, 13.5))
    sp.items = {RIGHT: lambda t: (TURNER, 'turner'), LEFT: lambda t: None}
    slot = at((8.0, 10.5, 6.1), DX, DZ)                                   # the pan's floor under the food
    P = lambda dy, dz: slot + np.array([0.0, dy, dz])
    path = curve([(0.0, P(-6.0, 3.5)), (2.5, P(-1.8, 2.4)), (3.5, P(-0.1, 2.0)), (5.0, P(-0.1, -1.4)), (6.0, P(-0.8, -1.4)), (6.6, P(-3.6, -0.9)),
                  (7.2, P(-3.0, -0.8)), (7.9, P(-0.4, -1.0)), (9.5, P(-0.1, 1.8)), (11.0, P(-1.8, 2.4)), (14.0, P(-6.0, 3.5))])
    w_lvl, w_flick = unit(FWD * 0.97 + DOWN * 0.1), unit(FWD * 0.6 + UP * 0.8)
    want = vec_path([(0.0, w_lvl), (5.0, w_lvl), (6.0, w_lvl), (6.6, w_flick), (7.2, w_flick), (7.9, w_lvl), (11.0, w_lvl), (14.0, w_lvl)])
    k_of = lambda t: pulse(t, 0.0, 2.0, 11.5, 14.0)
    sp.arms = {RIGHT: lambda t, ctx: tool_plan(RIGHT, TURNER, 1.0, RFREE, path(t), want(t), k_of(t)), LEFT: lambda t, ctx: Plan(free=LFREE)}
    sp.head = lambda t: (-2.0 * e(t), 0.0, 0.0)
    sp.marks = {'flip': 6.6, 'land': 7.9}
    sp.checks.append(station_checks('hob', DX, DZ, TURNER, "the turner's tip under the food and level again to catch it", [5.0, 5.5, 8.0, 8.5], path, tool_tol=0.6))
    return sp


SPECS['cook_flip'] = spec_flip


# ================================================================== the pot's other clips: cook_add, cook_feed, cook_ease, and the spit's crank

FRONT = np.array([0.0, 0.0, -1.0])        # an arm's own front: where a flat thing in its fist (a bowl, a carrot) sits off the line of the arm
FOOD_COL = {'carrot': (232, 130, 40), 'coal': (52, 52, 58), 'bread': (196, 150, 84), 'bowl': (176, 130, 90)}


def item_plan(side, free, point, k, want, kr=None, work=None, roll=0.0, bow=None):
    """A flat thing in [side]'s fist: the fist [k] (0 to 1) of the way from where the arm hangs to [point] (a world point, or fn(ctx)), the
    thing on the side of the arm that looks along [want] (a world direction), the wrist turned from the hanging hand's as far as [kr] ([k] if
    not given)."""
    work = hand_pt(side) if work is None else work
    kr = k if kr is None else kr
    tgt = toward(side, free, work, point, k) if bow is None else toward(side, free, work, point, k, bow)
    return Plan(free=free, w=1.0, work=work, target=tgt, blade=FRONT, want=want, roll=roll, carry=1.0 - kr, lean=k, blade_k=0.0)


def torso_pts(path, t):
    """fn(ctx) -> the point [path] (fn(t) -> a point of the torso's own space) has at [t], in the world."""
    p = path(t)
    return lambda ctx: ctx.body.torso_point(p)


def fist_dir(side, d):
    """The point of the torso's own space that is a fist's reach from [side]'s shoulder along [d]."""
    return SHOULDER[side] + FIST_AT * unit(np.asarray(d, float))


def fist_path(side, keys):
    """fn(t) -> a point of the torso's own space a fist's reach from [side]'s shoulder, its direction going through the keys' directions
    ((tick, direction), ...): the interpolated points all keep the arm's own length, as a rigid arm must."""
    f = vec_path(keys)
    return lambda t: SHOULDER[side] + FIST_AT * f(t)


def cube_at(p, col, half=1.1):
    p = np.asarray(p, float)
    h = np.full(3, half)
    return box_quads_world([(p - h, p + h, col, False)])


def toss_path(t0, p0, g=14.0, v=None):
    """fn(t) -> a thrown thing's place [t] ticks in: it leaves [p0] at tick [t0] with the velocity [v] (px a tick), and falls at [g] px a tick squared
    (a real toss, so the previews' flight is not a straight line)."""
    v = np.zeros(3) if v is None else np.asarray(v, float)

    def at_(t):
        s = max(0.0, t - t0)
        return np.asarray(p0, float) + v * s + np.array([0.0, 0.5 * g * s * s, 0.0])
    return at_


# ================================================================== cook_add

def spec_add():
    """cook_add (10): the ingredient swung low and forward from behind the hip, over the pot's near rim and flicked out above the stew."""
    sp = Spec('cook_add', 10, rate=4, blend=(1.0, 3.0))
    e = lambda t: pulse(t, 0.0, 2.5, 7.0, 10.0)
    pot_body(sp, e, look=10.0, pitch=22.0, step_in=(0.0, 3.0), step_out=(7.0, 10.0), crouch=2.0)
    sp.feet = {'right_leg': foot_track(REST_FEET['right_leg'], [(0.5, 3.0, (-2.6, 1.0)), (7.0, 10.0, REST_FEET['right_leg'])], 0.6),
               'left_leg': foot_track(REST_FEET['left_leg'], [(0.0, 2.5, (2.6, -3.0)), (7.0, 10.0, REST_FEET['left_leg'])], 1.4)}
    T_REL = 4.9
    low = fist_path(RIGHT, [(0.0, (-0.1, 0.99, -0.12)), (1.6, (-0.1, 0.9, 0.42)), (3.0, (0.0, 0.84, -0.54))])
    pot = curve([(2.4, over_pot(-0.2, POT_RIM - 1.0, 5.2)), (3.6, over_pot(0.0, POT_RIM - 2.4, 2.0)), (4.9, over_pot(0.3, POT_RIM - 4.4, 0.2)),
                 (6.2, over_pot(0.3, POT_RIM - 5.4, 0.0))])
    a = lambda t: ramp(t, 2.4, 4.0)                                           # from the swing in her own frame to the pot's
    k_of = lambda t: pulse(t, 0.0, 1.6, 6.0, 9.4)

    def point(t):
        def fn(ctx):
            p_low = ctx.body.torso_point(low(min(t, 3.0)))
            if a(t) <= 1e-4:
                return p_low
            p_pot = pot(min(max(t, 2.4), 6.2))
            return p_low + (p_pot - p_low) * a(t)
        return fn
    # the thing sits up on the fist through the swing (the palm up), and the wrist flicks it over at the release
    want = vec_path([(0.0, FWD * 0.7 + UP * 0.3), (1.6, UP * 0.9 + FWD * 0.3), (3.6, UP * 0.9 + FWD * 0.4), (4.9, FWD * 0.9 + UP * 0.2),
                     (5.5, DOWN * 0.8 + FWD * 0.5), (7.0, DOWN * 0.5 + FWD * 0.7)])
    sp.items = {RIGHT: lambda t: (FOOD, 'carrot') if t < T_REL else None}
    sp.arms = {RIGHT: lambda t, ctx: item_plan(RIGHT, RFREE, point(t), k_of(t), want(t)), LEFT: lambda t, ctx: Plan(free=LFREE)}
    sp.style.update(yaw=lambda t: 4.0 * pulse(t, 0.6, 1.6, 2.2, 3.0) - 5.0 * pulse(t, 3.2, 4.2, 5.4, 7.0))
    sp.head = lambda t: (-2.0 * e(t) + 2.0 * pulse(t, 4.0, 5.0, 6.0, 7.5), -4.0 * pulse(t, 1.0, 3.0, 5.0, 7.0), 0.0)
    flight = toss_path(T_REL, over_pot(0.3, POT_RIM - 6.4, 0.2), v=np.array([0.0, -1.2, -0.6]))
    sp.extras = lambda t: cube_at(flight(t), FOOD_COL['carrot']) if T_REL <= t <= T_REL + 1.9 else []
    sp.marks = {'release': T_REL}
    sp.checks.append(station_checks('pot', POT_DX, POT_DZ, None, '', [], None, self_tol=1.5, solid_tol=0.3))
    return sp


SPECS['cook_add'] = spec_add


# ================================================================== cook_feed

def spec_feed():
    """cook_feed (12): a slight crouch, the fuel swung up from behind the hip, underhand, and let go low over the hearth's ring toward the fire."""
    sp = Spec('cook_feed', 12, rate=4, blend=(1.0, 3.0))
    e = lambda t: pulse(t, 0.0, 2.5, 8.5, 12.0)
    pot_body(sp, e, look=12.0, pitch=18.0, step_in=(0.0, 3.0), step_out=(8.5, 11.5), crouch=4.5)
    sp.feet = {'right_leg': foot_track(REST_FEET['right_leg'], [(0.5, 3.0, (-3.0, 1.5)), (8.5, 11.5, REST_FEET['right_leg'])], 0.6),
               'left_leg': foot_track(REST_FEET['left_leg'], [(0.0, 2.5, (3.0, -3.2)), (8.5, 11.5, REST_FEET['left_leg'])], 1.4)}
    T_REL = 5.4
    low = fist_path(RIGHT, [(0.0, (-0.1, 0.99, -0.12)), (1.8, (-0.1, 0.88, 0.46)), (3.2, (0.0, 0.90, -0.44))])
    pot = curve([(2.6, over_pot(-0.4, 18.6, 10.0)), (4.2, over_pot(-0.1, 18.8, 8.2)), (5.4, over_pot(0.2, 18.6, 6.6)), (7.0, over_pot(0.3, 17.6, 5.6))])
    a = lambda t: ramp(t, 2.6, 4.4)
    k_of = lambda t: pulse(t, 0.0, 1.8, 7.5, 11.2)

    def point(t):
        def fn(ctx):
            p_low = ctx.body.torso_point(low(min(t, 3.2)))
            if a(t) <= 1e-4:
                return p_low
            p_pot = pot(min(max(t, 2.6), 7.0))
            return p_low + (p_pot - p_low) * a(t)
        return fn
    want = vec_path([(0.0, FWD * 0.7 + UP * 0.3), (1.8, UP * 0.9 + FWD * 0.3), (4.2, UP * 0.9 + FWD * 0.5), (5.4, FWD * 0.9 + UP * 0.3),
                     (6.2, FWD * 0.9 + DOWN * 0.4), (8.0, DOWN * 0.5 + FWD * 0.7)])
    sp.items = {RIGHT: lambda t: (FOOD, 'coal') if t < T_REL else None}
    sp.arms = {RIGHT: lambda t, ctx: item_plan(RIGHT, RFREE, point(t), k_of(t), want(t)), LEFT: lambda t, ctx: Plan(free=LFREE)}
    sp.style.update(yaw=lambda t: 4.0 * pulse(t, 0.6, 1.8, 2.4, 3.2) - 4.0 * pulse(t, 3.4, 4.6, 6.0, 8.0))
    sp.head = lambda t: (-2.0 * e(t), -3.0 * pulse(t, 1.0, 3.0, 6.0, 8.0), 0.0)
    flight = toss_path(T_REL, over_pot(0.2, 18.4, 6.2), g=8.0, v=np.array([0.0, -0.4, -1.4]))
    sp.extras = lambda t: cube_at(flight(t), FOOD_COL['coal']) if T_REL <= t <= T_REL + 2.4 else []
    sp.marks = {'release': T_REL}
    sp.checks.append(station_checks('pot', POT_DX, POT_DZ, None, '', [], None, self_tol=1.5, solid_tol=0.6))
    return sp


SPECS['cook_feed'] = spec_feed


# ================================================================== cook_ease

def spec_ease():
    """cook_ease (14): both hands up on the chain in front of her, and a hand-over-hand pull: the right comes down while the left reaches up,
    the left comes down while the right reaches up, twice each, her body rocking back with each pull."""
    sp = Spec('cook_ease', 14, rate=4, blend=(1.0, 3.0))
    e = lambda t: pulse(t, 0.0, 1.5, 11.0, 14.0)
    sp.look = lambda t: (-12.0 * e(t), 0.0)
    sp.style.update(pitch=lambda t: 3.0 * e(t) - 3.0 * (pulse(t, 1.5, 2.5, 3.6, 4.4) + pulse(t, 5.2, 6.0, 7.2, 8.0) * 0.0 + pulse(t, 7.0, 7.8, 9.0, 10.0)),
                    yaw=lambda t: -3.0 * pulse(t, 1.5, 2.5, 3.6, 4.4) + 3.0 * pulse(t, 4.4, 5.2, 6.6, 7.4) - 3.0 * pulse(t, 7.4, 8.2, 9.2, 10.0))
    sp.prior = dict(pitch=0.06, yaw=0.3, roll=0.3, rx=0.6, rz=0.4)
    sp.bounds = ([-15, -20, -12, -5, -7], [40, 20, 12, 5, 7])
    dHi = {RIGHT: (0.38, -0.42, -0.84), LEFT: (-0.38, -0.42, -0.84)}
    dLo = {RIGHT: (0.30, 0.64, -0.70), LEFT: (-0.30, 0.64, -0.70)}
    hi = {s: fist_dir(s, dHi[s]) for s in SIDES}

    def path(side, keys):
        """keys: [(t, 'hi' | 'lo'), ...]: the fist's place in the torso's own space (its direction goes from one to the other)."""
        return fist_path(side, [(t, dHi[side] if where == 'hi' else dLo[side]) for t, where in keys])
    # right: up (1.6), pulls down (1.6 to 3.8), up again (3.8 to 5.6), pulls (5.6 to 7.8), lets go and comes away (7.8 to 10)
    pr = path(RIGHT, [(0.0, 'lo'), (1.6, 'hi'), (3.8, 'lo'), (5.6, 'hi'), (7.8, 'lo'), (10.0, 'lo')])
    pl = path(LEFT, [(0.0, 'lo'), (1.6, 'lo'), (3.0, 'hi'), (3.8, 'hi'), (6.0, 'lo'), (7.8, 'hi'), (10.0, 'lo')])
    kr = lambda t: pulse(t, 0.0, 1.6, 9.0, 12.5)
    kl = lambda t: pulse(t, 0.6, 3.0, 9.0, 12.5)
    sp.arms = {RIGHT: lambda t, ctx: hand_plan(RIGHT, RFREE, torso_pts(pr, t), kr(t)), LEFT: lambda t, ctx: hand_plan(LEFT, LFREE, torso_pts(pl, t), kl(t))}
    sp.head = lambda t: (-3.0 * e(t) + 2.0 * pulse(t, 3.0, 4.0, 4.6, 5.4) + 2.0 * pulse(t, 6.4, 7.4, 8.0, 9.0), 0.0, 0.0)
    chain_x = float(hi[RIGHT][0] + hi[LEFT][0]) / 2.0

    def extras(t):
        # a stand-in chain in front of her, between her fists (the previews' only; the real one hangs over the pot)
        w = e(t)
        if w < 0.05:
            return []
        return box_quads_world([(np.array([chain_x - 0.4, -12.0, -7.6]), np.array([chain_x + 0.4, 6.0 + 2.5, -6.8]), IRON, False)])
    sp.extras = extras
    sp.marks = {'pull1': 3.8, 'pull2': 6.0, 'pull3': 7.8}

    def checks(rep, clip, spec):
        self_clear(rep, spec, clip, 'arms clear of the body, the head and each other', tol=1.5, arm_tol=1.8)
        feet_planted(rep, spec, clip, 'feet', tol=0.7)
    sp.checks.append(checks)
    return sp


SPECS['cook_ease'] = spec_ease


# ================================================================== cook_turn

CRANK_C = np.array([-3.5, 15.0, -7.5])                # a stand-in crank's axle (the previews', and the hand's circle): in front of her, at the spit's rod height
CRANK_R = 3.4                                         # px from the axle to the handle


def crank_at(c, theta):
    """The handle's place for the crank turned [theta] degrees (0: the handle up, positive toward her left: the crank of the townsfolk's turn_spit)."""
    th = math.radians(theta)
    return c + np.array([CRANK_R * math.sin(th), -CRANK_R * math.cos(th), 0.0])


def crank_quads(c, theta, a=None):
    """The crank for the previews: an axle stub, the arm to the handle and the handle."""
    h = crank_at(c, theta)
    d = unit(h - c)
    ax = [d, np.array([0.0, 0.0, 1.0]), np.cross(d, np.array([0.0, 0.0, 1.0]))]
    quads = []
    key = ('solid', IRON)
    if key not in _TEX_CACHE:
        _TEX_CACHE[key] = solid_tex(IRON)
    quads += oriented_box(c + np.array([0.0, 0.0, 2.0]), AXES, np.array([0.7, 0.7, 3.2]), _TEX_CACHE[key])
    quads += oriented_box((c + h) / 2.0, ax, np.array([CRANK_R / 2.0 + 0.5, 0.45, 0.45]), _TEX_CACHE[key])
    key = ('solid', WOOD)
    if key not in _TEX_CACHE:
        _TEX_CACHE[key] = solid_tex(WOOD)
    quads += oriented_box(h + np.array([0.0, 0.0, 1.6]), AXES, np.array([0.7, 0.7, 1.8]), _TEX_CACHE[key])
    return quads


def spec_turn():
    """cook_turn (12): the crank at the spit's end turned a quarter turn with the whole arm: she reaches up to its handle, bears it down the
    right side of its circle (the way the townsfolk's turns go) with her weight behind it, the left hand on her thigh, and lets it go."""
    sp = Spec('cook_turn', 12, rate=4, blend=(1.0, 3.0))
    e = lambda t: pulse(t, 0.0, 2.0, 9.5, 12.0)
    work_body(sp, e, None, 0.0, 0.0, pitch=28.0, crouch=3.0, look=12.0, step_in=(0.0, 2.0), step_out=(9.5, 12.0))
    sp.station = None
    sp.solids = []
    sp.bounds = ([-15, -18, -12, -6, -8], [60, 18, 12, 6, 8])
    theta = lambda t: -90.0 * ramp(t, 2.6, 8.6)                                  # the handle: a quarter turn, clockwise as she sees it
    k_of = lambda t: pulse(t, 0.0, 2.6, 9.0, 12.0)
    kl = lambda t: pulse(t, 0.4, 2.8, 8.8, 11.6)
    brace = np.array([3.0, 15.8, -2.2])                                            # the left hand on her left thigh, above the knee

    def point(t):
        return crank_at(CRANK_C, theta(t))
    sp.arms = {RIGHT: lambda t, ctx: hand_plan(RIGHT, RFREE, point(t), k_of(t)),
               LEFT: lambda t, ctx: loosely(hand_plan(LEFT, LFREE, brace, kl(t), palm=unit(DOWN * 0.7 + RIGHTWARD * 0.6)), 0.4)}
    sp.style.update(pitch=lambda t: 28.0 * e(t) + 6.0 * pulse(t, 3.0, 5.0, 7.0, 9.0), yaw=lambda t: -5.0 * pulse(t, 2.6, 5.0, 6.0, 8.6),
                    roll=lambda t: 3.0 * pulse(t, 2.6, 5.0, 6.0, 8.6))
    sp.head = lambda t: (-2.0 * e(t), 3.0 * pulse(t, 3.0, 5.0, 7.0, 9.0), 0.0)
    sp.extras = lambda t: crank_quads(CRANK_C, theta(t))
    sp.marks = {'push': 4.0}

    def checks(rep, clip, spec):
        times = [t for t in ticks_of(clip) if 3.0 <= t <= 8.5]
        point_at(rep, spec, clip, 'the right fist on the crank handle through the quarter turn', times, lambda p: part_pts(p, RIGHT, [hand_pt(RIGHT)])[0],
                 lambda t: point(t), tol=0.6)
        self_clear(rep, spec, clip, 'arms clear of the body, the head and each other', tol=1.5, arm_tol=0.7)
        feet_planted(rep, spec, clip, 'feet', tol=0.7)
    sp.checks.append(checks)
    return sp


SPECS['cook_turn'] = spec_turn


# ================================================================== cook_serve_peel

def reach_path(side, work, keys):
    """fn(t) -> a point of the torso's own space as far from [side]'s shoulder as the arm's point [work] is, along the directions of the keys
    ((tick, direction), ...): a tool's working end held where an arm can put it."""
    f = vec_path(keys)
    r = float(np.linalg.norm(work))
    return lambda t: SHOULDER[side] + r * f(t)


def spec_serve_peel():
    """cook_serve_peel (22): the turner as a peel: its blade slid into the oven's mouth and in under the loaf on the tray, drawn out with it,
    and then she turns away from the oven with the loaf held out and a little up, her weight on her back foot."""
    sp = Spec('cook_serve_peel', 22, rate=4, blend=(1.5, 4.0))
    DX, DZ = -5.0, 1.0
    e = lambda t: pulse(t, 0.0, 3.0, 17.0, 22.0)
    work_body(sp, e, 'oven', DX, DZ, pitch=12.0, crouch=2.6, look=10.0, step_in=(0.0, 3.0), step_out=(17.0, 21.0))
    sp.bounds = ([-12, -18, -12, -6, -8], [60, 50, 12, 6, 8])
    sp.prior = dict(pitch=0.08, yaw=0.3, roll=0.3, rx=0.6, rz=0.4)
    sp.items = {RIGHT: lambda t: (TURNER, 'turner'), LEFT: lambda t: None}
    P = lambda z, y: at((8.0, y, z), DX, DZ)
    path = curve([(0.0, P(2.6, 9.9)), (3.0, P(2.6, 9.7)), (5.5, P(4.6, 9.35)), (8.0, P(6.4, 9.15)), (9.3, P(6.9, 8.95)), (10.3, P(6.8, 9.3)),
                  (12.5, P(5.2, 9.5)), (14.0, P(1.2, 9.8)), (15.0, P(0.2, 10.2))])
    tip = tool_pt(TURNER, RIGHT, 1.0)
    raised = reach_path(RIGHT, tip, [(0.0, (0.10, -0.10, -0.99))])(0.0)               # held out ahead of her right shoulder, in her own frame
    a = lambda t: ramp(t, 14.5, 17.5)
    want = vec_path([(0.0, FWD * 0.97 + UP * 0.12), (8.0, FWD * 0.97 + UP * 0.14), (9.3, FWD * 0.97 + UP * 0.05), (10.3, FWD * 0.96 + UP * 0.2),
                     (14.5, FWD * 0.96 + UP * 0.2), (17.5, FWD * 0.9 + UP * 0.4), (22.0, FWD * 0.9 + UP * 0.4)])
    k_of = lambda t: pulse(t, 0.0, 3.0, 18.5, 21.8)
    reach = float(np.linalg.norm(tip))

    def point(t):
        w = path(min(t, 15.0))
        a_ = a(t)

        def fn(ctx):
            if a_ <= 1e-4:
                return w
            # from the oven to where she holds it out, the tip goes round the arm's own sphere (a straight blend would be nearer her than the arm reaches)
            J = ctx.body.shoulder(RIGHT)
            return J + reach * slerp_dir(unit(w - J), unit(ctx.body.torso_point(raised) - J), a_)
        return fn
    sp.arms = {RIGHT: lambda t, ctx: tool_plan(RIGHT, TURNER, 1.0, RFREE, point(t), want(t), k_of(t)), LEFT: lambda t, ctx: Plan(free=LFREE)}
    sp.style.update(pitch=lambda t: 12.0 * e(t) - 6.0 * pulse(t, 15.5, 18.5, 19.5, 22.0), yaw=lambda t: 38.0 * pulse(t, 14.5, 17.5, 19.0, 22.0))
    sp.head = lambda t: (-2.0 * e(t) - 6.0 * pulse(t, 15.5, 18.0, 19.5, 22.0), 0.0, 0.0)
    sp.marks = {'in': 8.0, 'out': 14.5, 'lift': 18.0}
    sp.checks.append(station_checks('oven', DX, DZ, TURNER, "the turner's tip at the mouth, in under the loaf and out again", [3.0, 3.5, 5.5, 8.0, 8.5, 9.5, 12.5],
                                    lambda t: path(t), tip_tol=0.8, self_tol=1.5, solid_tol=0.3, tool_tol=0.4, window=(1.5, 19.0)))
    return sp


SPECS['cook_serve_peel'] = spec_serve_peel


# ================================================================== cook_carve

def spec_carve():
    """cook_carve (20): the knife laid to the roast's face and drawn across it, down and to her right, twice, the second cut deeper, and flicked
    away as the slice falls; her other hand held out to steady the joint."""
    sp = Spec('cook_carve', 20, rate=4, blend=(1.0, 4.0))
    DX, DZ = -1.0, 6.0
    e = lambda t: pulse(t, 0.0, 3.0, 16.0, 20.0)
    work_body(sp, e, 'spit', DX, DZ, pitch=22.0, crouch=2.0, look=12.0, step_in=(0.0, 2.5), step_out=(16.0, 19.0))
    sp.bounds = ([-15, -18, -12, -6, -8], [60, 18, 12, 6, 8])
    sp.items = {RIGHT: lambda t: (KNIFE, 'cooks_knife'), LEFT: lambda t: None}
    face = at((8.0, 9.6, 6.7), DX, DZ)                                          # the roast's face at the rod's height (the joints' fronts are at 6.9)
    P = lambda x, dy, dz: face + np.array([x, dy, dz])
    path = curve([(0.0, P(1.5, -5.0, 3.0)), (3.0, P(1.2, -1.6, 0.9)), (4.0, P(1.0, -1.3, 0.1)), (8.0, P(-3.0, 0.5, 0.1)), (9.0, P(-3.4, 0.5, 0.9)),
                  (10.2, P(0.0, -0.4, 0.9)), (11.0, P(0.6, -0.3, 0.1)), (14.5, P(-3.8, 1.4, 0.1)), (15.6, P(-4.4, 0.4, 1.4)), (17.0, P(-4.0, -3.5, 2.6))])
    want = vec_path([(0.0, FWD * 0.9 + DOWN * 0.4), (4.0, FWD * 0.88 + DOWN * 0.42 + RIGHTWARD * 0.12), (8.0, FWD * 0.88 + DOWN * 0.42 + RIGHTWARD * 0.16),
                     (10.2, FWD * 0.9 + DOWN * 0.4), (14.5, FWD * 0.88 + DOWN * 0.44 + RIGHTWARD * 0.16), (15.6, FWD * 0.9 + UP * 0.2),
                     (20.0, FWD * 0.9 + DOWN * 0.1)])
    k_of = lambda t: pulse(t, 0.0, 3.0, 16.0, 19.6)
    steady = face + np.array([3.6, -3.0, 1.6])                                  # the other hand held out over the joint's far side
    sp.arms = {RIGHT: lambda t, ctx: tool_plan(RIGHT, KNIFE, 1.0, RFREE, path(t), want(t), k_of(t)),
               LEFT: lambda t, ctx: loosely(hand_plan(LEFT, LFREE, steady, k_of(t), palm=unit(DOWN * 0.9 + LEFTWARD * 0.3)), 0.35)}
    sp.style.update(yaw=lambda t: -3.0 * pulse(t, 3.5, 8.0, 9.0, 10.2) - 3.0 * pulse(t, 10.5, 14.5, 15.0, 16.5))
    sp.head = lambda t: (-2.0 * e(t), 2.0 * pulse(t, 3.0, 5.0, 8.0, 10.0) - 2.0 * pulse(t, 10.5, 12.5, 14.5, 16.0), 0.0)
    slice_ = toss_path(14.6, face + np.array([-3.4, 0.4, 0.8]), g=5.0, v=np.array([-0.2, 0.0, 0.2]))
    sp.extras = lambda t: cube_at(slice_(t), MEAT, 0.8) if 14.6 <= t <= 17.4 else []
    sp.marks = {'cut1': 8.0, 'cut2': 14.5}
    sp.checks.append(station_checks('spit', DX, DZ, KNIFE, "the knife's tip on the roast's face through the two cuts", [4.0, 5.0, 6.0, 7.0, 8.0, 11.0, 12.0, 13.0, 14.0],
                                    path, tip_tol=0.8, self_tol=2.0, solid_tol=0.3, tool_tol=0.3, window=(1.0, 17.5)))
    return sp


SPECS['cook_carve'] = spec_carve


# ================================================================== cook_shiver

class FkSpec(Spec):
    """A clip that is keyed straight from [keys_fn](t) -> {bone: (rotation, offset or None)}, with no solve: for a body that is posed, not reaching."""

    def __init__(self, name, length, rate, blend, keys_fn, modes=None):
        Spec.__init__(self, name, length, rate=rate, blend=blend)
        self.keys_fn = keys_fn
        self.times = [i / rate for i in range(int(round(length * rate)) + 1)]
        self.modes = dict(modes or {})
        self.solved = True
        self.X = np.zeros((len(self.times), 6))

    def solve(self, verbose=False):
        return self

    def state(self):
        return dict(X=self.X, times=self.times, disp={}, wrist={})

    def load(self, st):
        return self

    def bake(self):
        c = Clip(self.name, self.length, blend_in=self.blend[0], blend_out=self.blend[1], rate=self.rate)
        for t in self.times:
            for bone, (q, pos) in self.keys_fn(t).items():
                c.key(bone, t, q, pos=pos, ease='linear')
        for bone, mode in self.modes.items():
            c.mode(bone, mode)
        for label, tt in self.marks.items():
            c.mark(label, tt)
        return c


def spec_shiver():
    """cook_shiver (40, loop): her arms hugged across her chest, her shoulders drawn up and forward and her head sunk and tucked, all of it trembling
    in waves; only her arms, shoulders and head move. The tremor is made of whole numbers of cycles to the loop, so that it joins."""
    LEN = 40.0
    body = Body()
    dR0, dL0 = unit((0.84, 0.44, -0.32)), unit((-0.78, 0.40, -0.48))               # the right arm across her chest under, the left across it over
    palmR, palmL = unit((0.0, 0.3, 1.0)), unit((0.0, 0.3, 1.0))
    shrug = {RIGHT: np.array([1.0, -0.8, -1.5]), LEFT: np.array([-1.0, -0.8, -2.2])}

    def keys(t):
        ph = 2.0 * math.pi * t / LEN
        wave = 0.5 + 0.5 * math.sin(4.0 * ph - 0.6)                                 # the shudders come in waves, four to the loop
        amp = 0.4 + 0.6 * wave
        tr = lambda n, p0=0.0: math.sin(n * ph + p0)
        out = {}
        for side, d0, palm, ph0 in ((RIGHT, dR0, palmR, 0.0), (LEFT, dL0, palmL, 1.9)):
            d = unit(d0 + np.array([0.035 * amp * tr(26, ph0), 0.05 * amp * tr(26, ph0 + 1.3), 0.035 * amp * tr(26, ph0 + 2.4)]) + 0.02 * np.array([0.0, tr(4, ph0), 0.0]))
            pos = shrug[side] + np.array([0.0, 0.3 * amp * tr(26, ph0 + 2.0), 0.0]) + np.array([0.0, -0.35 * wave, 0.0])
            out[side] = (body.free_arm(side, d, palm), pos)
        out['head'] = (qeuler(x=6.0 + 1.0 * amp * tr(26, 0.4) + 1.5 * wave, y=1.2 * amp * tr(17, 0.9), z=2.0 + 1.0 * amp * tr(13, 2.1)), None)
        return out
    sp = FkSpec('cook_shiver', LEN, 4, (0.0, 0.0), keys, modes={'head': 'add'})
    sp.marks = {'wave': 10.0}

    def checks(rep, clip, spec):
        bones = sorted(clip.keys)
        rep.line(f"only her arms, shoulders and head are keyed: {', '.join(bones)}", set(bones) <= {'right_arm', 'left_arm', 'head'})
        worst = 0.0
        for bone in clip.keys:
            q0, p0 = clip.sample(bone, 0.0)
            q1, p1 = clip.sample(bone, clip.length)
            worst = max(worst, float(np.abs(q0 - q1).max()), float(np.abs(p0 - p1).max()))
        rep.line(f'the loop joins: the first and the last samples differ by {worst:.4f} at the most', worst < 1e-3)
        self_clear(rep, spec, clip, 'her arms against her chest, her head and each other (a hug is pressed in)', tol=2.2, arm_tol=3.2)
    sp.checks.append(checks)
    return sp


SPECS['cook_shiver'] = spec_shiver


# ================================================================== end of the kit
# ---- (clips go above this line)
