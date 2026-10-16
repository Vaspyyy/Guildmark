"""
The two clips of the cog's standing crew: the helmsman at the wheel and the lookout at the bow. Same kit and skeleton as the
combat and companion clips (animkit.py), played by the game's CombatAnimator through client/ship/ShipClient.kt. Run from the
repo root:

    python3 -B art/scripts/ship_anims.py src/main/resources/assets/guildmark [preview-dir [steve.png]]

Writes assets/guildmark/animations/ship/{helm,lookout}.json and, with a preview dir, ship/helm.gif and ship/lookout.gif there
(the clips on the player model, the wheel and the stay drawn where the clips take hold of them; steve.png dresses the model,
else it is a plain sailor). Prints what it checked. Everything is computed, so a rerun gives the same files.

    helm       40 ticks   rate 4   blend 0/0   root, torso, both arms, both legs   NOT played: its time is the wheel's turn
    lookout   120 ticks   rate 2   blend 0/0   root, torso, both arms, both legs   played as a loop (time modulo 120)

Neither has a head track, so the player keeps looking where he looks.

How the helm clip is read. ShipClient sets the time to length * (0.5 + 0.5 * helm), helm being the cog's rudder, -1 (hard to
port) to 1 (hard to starboard), and CogRenderer turns the wheel by WHEEL_DEGREES * helm (270 either way, 540 end to end). So
the clip's time IS the wheel's angle: tick 0 is the wheel hard over to port (-270 degrees), tick 20 amidships, tick 40 hard over
to starboard (+270), 13.5 degrees of wheel to the tick. The clip is a pure function of that angle: the hands are on the wheel's
handles and go round with them, one hand over the other, however far or fast the helm is put over, and the body leans into
the turn. If WHEEL_DEGREES in CogRenderer changes, change it here and bake again (the hands only stay on the handles at the
angle baked).

The wheel and the player, in the player's own frame (model pixels, y down, the player faces -z, feet at y = 24). The axle is
(0, 9, -7): 15 px above the feet and 7 px ahead of the body's middle (ship.py's WHEEL_AT against CogStation.HELM), which puts
the rim's near face 3.8 px in front of the chest. The rim is 10 px out (sixteen blocks, 9.1 to the middle of them); four bars
through the hub end in eight handles 45 degrees apart, capped in brass at 13 to 15 px. Angles run clockwise from the top as
the helmsman sees them: +90 is his 3 o'clock, which is his right, which is -x.

How the hands go round (hand over hand). Each hand takes a handle and goes round with it through 25 degrees of rim, then lets go
and swings (a hair clear of the rim and toward the helmsman) back to the handle that has come round behind it, which takes
the next 20 degrees of wheel (HOP), and holds that. The right hand works the rim from 1:20 to 2:10 (40 to 65 degrees) and the
left from 9:35 to 10:25 (-72.5 to -47.5): about the 2 and 10 o'clock of a helmsman's hands, half a handle (22.5 degrees) out
of step so that they never let go together. Turning to starboard the right hand is pulled down and the left pushed up; to port
it is the other way, and since the clip is a pure function of the angle it simply plays back to front. Every hold is within
reach of a straight arm from the nearly upright body, so the body is free to lean into the turn: the shoulder on that side
down (6 degrees at hard over), a couple of degrees of twist, the hips a px the other way, the trunk otherwise upright, within
a degree and a half of plumb forward or back. The solver (least squares at every sample) nudges that style the least it takes
for both arms to reach their handles with the chest clear of the wheel. The legs are planted wide apart (a sailor's stance:
the knees can't bend, so the feet go out, the left a hair ahead, and the hips settle half a px) and aimed at the same two
spots on the deck whatever the body does.

How the lookout goes (a seamless 120 tick loop, tick 0 = tick 120). The right hand holds a stay at shoulder height, 8 px ahead
of the shoulder and 2 px outside it (HOLD: the model's own forestay climbs far above the station, so a rope or rail has to be
there for the hand to take); the weight is on the left leg (the hips over it, the right foot resting out to the side, toes
turned out); a slow swell rocks the body (a roll once in the loop, a pitch twice) and the shoulders scan the horizon. Twice
in the loop the right hand slips a px along the stay to shift its grip (ticks 14 to 26 and 74 to 84), and the left hand comes
up to shade the eyes, its shoulder lifting with it (ticks 30 to 70, held about 18, and a quicker look from 88 to 112), and
goes down.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy.optimize import least_squares

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from animkit import (Clip, qeuler, qmul, qaxis, qmat, qfrommat, qslerp, frame_to, unit, I, pose_clip, vanilla_pose,
                     raster, ortho, T, Rq, apply, box_quads, PLAYER, FACE_LIGHT)

GROUP = 'ship'
GROUND = 24.0

# ================================================================== the skeleton, as CombatAnimator has it

HIPS = np.array([0.0, 12.0, 0.0])
SHOULDER = {'right_arm': np.array([-5.0, 2.0, 0.0]), 'left_arm': np.array([5.0, 2.0, 0.0])}
HIP_JOINT = {'right_leg': np.array([-1.9, 12.0, 0.0]), 'left_leg': np.array([1.9, 12.0, 0.0])}
FIST = {'right_arm': np.array([-1.0, 10.0, 0.0]), 'left_arm': np.array([1.0, 10.0, 0.0])}   # the arm's end, its middle
ARM_X = {'right_arm': -1.0, 'left_arm': 1.0}      # the arm's own middle line, off its joint
ARM_REACH = (8.0, 9.95)                           # how far along the arm (px from the joint) a held thing may be gripped


def qinv(q):
    x, y, z, w = q
    return np.array([-x, -y, -z, w])


def torso_q(pitch=0.0, yaw=0.0, roll=0.0):
    """The torso bent forward [pitch] degrees at the hips, turned [yaw] (positive turns to the character's right: the right
    shoulder goes back) and tipped [roll] (positive tips to the left: the right shoulder rises); as townsfolk_anims.bend."""
    return qmul(qeuler(y=yaw, z=roll), qeuler(x=pitch))


def smooth01(x):
    x = min(1.0, max(0.0, float(x)))
    return x * x * (3 - 2 * x)


class Body:
    """Where the body's joints are for a root offset [pr], a torso rotation [qt] and offset [vt] and the arms' joint offsets:
    CombatAnimator's chain, with the root not turned."""

    def __init__(self, pr=(0, 0, 0), qt=I, vt=(0, 0, 0), pa=None):
        self.pr = np.asarray(pr, float)
        self.qt = np.asarray(qt, float)
        self.vt = np.asarray(vt, float)
        self.pa = pa or {}
        self.Rt = qmat(self.qt)

    def shoulder(self, side):
        return HIPS + self.Rt @ (SHOULDER[side] + self.pa.get(side, np.zeros(3)) - HIPS) + self.vt + self.pr

    def torso_point(self, p):
        """A point of the torso's own space (the body box spans x -4..4, y 0..12, z -2..2) in the player's frame."""
        return HIPS + self.Rt @ (np.asarray(p, float) - HIPS) + self.vt + self.pr

    def arm_key(self, side, target, grip=None, roll_toward=None):
        """The arm's 'set' key that puts the point [grip] of its length (px from the joint, along its middle line; by default
        wherever the target is) on the world point [target], and how far along that was (clamped to what a straight arm
        gives), with the miss in px. The arm turns about its own length as little as it can, keeping its inner face to the body."""
        joint = self.shoulder(side)
        d = np.asarray(target, float) - joint
        dist = float(np.linalg.norm(d))
        cx = ARM_X[side]
        g = math.sqrt(max(0.0, dist * dist - cx * cx)) if grip is None else grip
        g = min(max(g, ARM_REACH[0]), ARM_REACH[1])
        local = np.array([cx, g, 0.0])
        ref = self.Rt @ np.array([1.0, 0.0, 0.0]) if roll_toward is None else np.asarray(roll_toward, float)
        qw = frame_to(local, np.array([1.0, 0.0, 0.0]), d, ref)
        miss = float(np.linalg.norm(joint + qmat(qw) @ local - target))
        return qmul(qinv(self.qt), qw), g, miss

    def arm_point(self, side, q_set, p):
        """The player's-frame position of the arm-space point [p] for the 'set' key [q_set]."""
        return self.shoulder(side) + qmat(qmul(self.qt, q_set)) @ np.asarray(p, float)


def leg_key(side, hip, foot, toe_out=8.0):
    """The leg's rotation (absolute, under the root) that points it from the hip joint [hip] (player's frame, root included)
    at the planted [foot], toes turned [toe_out] degrees outward; returns it and how far the foot ends from the point (the
    leg is 12 px, whatever the distance)."""
    d = np.asarray(foot, float) - hip
    dist = float(np.linalg.norm(d))
    s = -1.0 if side == 'right_leg' else 1.0
    fwd = np.array([s * math.sin(math.radians(-toe_out)), 0.0, -math.cos(math.radians(toe_out))])
    # the leg's own -z is its toes; frame_to squares them to the leg's length
    q = frame_to(np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, -1.0]), d, fwd)
    reached = hip + qmat(q) @ np.array([0.0, 12.0, 0.0])
    return q, float(np.linalg.norm(reached - foot)), reached


# ================================================================== things in the world the clips take hold of

WHEEL_DEGREES = 270.0                      # CogRenderer.WHEEL_DEGREES: the wheel's turn with the helm hard over
AXLE = np.array([0.0, 9.0, -7.0])          # the wheel's centre in the player's frame
RIM_R = 10.0                               # the rim's outer edge
GRIP_R = 10.4                              # where a hand takes a handle: just outside the rim
HANDLE_STEP = 45.0                         # the wheel has eight handles
HOP = 20.0                                 # degrees of wheel a hand takes to let go and take the next handle
HAND_FROM = {'right_arm': 40.0, 'left_arm': -72.5}     # where each hand's stretch of the wheel begins (degrees from 12 o'clock)
STANCE = {'right_leg': (-5.0, GROUND, 1.2), 'left_leg': (5.0, GROUND, -1.2)}    # where the feet are planted


def wheel_point(psi, r=GRIP_R, lift=0.0):
    """The point of the wheel [r] px from the axle at [psi] degrees clockwise from the top, as the helmsman sees it, [lift]
    px out of its plane toward him."""
    p = math.radians(psi)
    return np.array([AXLE[0] - r * math.sin(p), AXLE[1] - r * math.cos(p), AXLE[2] + lift])


def hand_on_wheel(side, phi):
    """Where [side]'s hand is for the wheel turned [phi] degrees (clockwise positive): the angle on the wheel, how far it has
    let go (0 holding, 1 clear of the handle), and the point. The hand holds the handle in its stretch, then swings to the one
    that has come round behind it."""
    lo = HAND_FROM[side]
    p = (phi - lo) % HANDLE_STEP
    u = (p - (HANDLE_STEP - HOP)) / HOP
    s = smooth01(u)
    psi = lo + p - HANDLE_STEP * s
    free = math.sin(math.pi * min(1.0, max(0.0, u)))
    return psi, free, wheel_point(psi, GRIP_R + 0.9 * free, 1.5 * free)


# ================================================================== the helm

HELM = dict(length=40.0, rate=4)


def helm_style(h):
    """How the body leans into the turn for the helm at [h] (-1 hard to port, 1 hard to starboard): pitch, yaw and roll
    degrees, and the root's x, y and z in px. Turning to starboard the right shoulder drops and comes forward and the hips
    shift the other way."""
    a = abs(h) ** 0.85 * (1 if h >= 0 else -1)
    return dict(pitch=2.2 + 1.2 * abs(a), yaw=-2.5 * a, roll=-7.0 * a, rx=1.2 * a, rz=1.1 - 0.3 * abs(a))


def solve_helm(phi, h, x_prev=None):
    """The body for the wheel at [phi]: the style's lean, nudged (pitch, yaw, roll, root x and z, each joint) the least it takes
    for both straight arms to reach their handles with the fists wrapped round them, the chest clear of the wheel. Returns the
    parts of a pose."""
    st = helm_style(h)
    hands = {s: hand_on_wheel(s, phi) for s in ('right_arm', 'left_arm')}
    base = np.array([st['pitch'], st['yaw'], st['roll'], st['rx'], st['rz']])

    def body_of(x):
        pitch, yaw, roll, rx, rz = x[:5]
        return Body(pr=(rx, drop_for_stance(rx, rz), rz), qt=torso_q(pitch, yaw, roll))

    def res(x):
        b = body_of(x)
        out = []
        for side in ('right_arm', 'left_arm'):
            d = float(np.linalg.norm(hands[side][2] - b.shoulder(side)))
            lo, hi = math.hypot(ARM_X[side], ARM_REACH[0]), math.hypot(ARM_X[side], ARM_REACH[1])
            out.append(max(0.0, d - hi) * 20 + max(0.0, lo - d) * 20)
            out.append((d - 9.5) * 0.12)                         # wrap the fist round the handle: about 9.5 px from the joint
        out += list((x[:5] - base) * np.array([0.03, 0.03, 0.03, 0.25, 0.25]))
        # the chest clear of the rim's near face (z = AXLE z + 1.2) by a px, at its upper corners
        zmin = min(b.torso_point((xs, ys, -2.0))[2] for xs in (-4, 4) for ys in (0.0, 4.0))
        out.append(max(0.0, (AXLE[2] + 1.2 + 1.0) - zmin) * 6.0)
        if x_prev is not None:
            out += list((np.asarray(x) - x_prev) * 0.08)
        return out
    x0 = base if x_prev is None else np.asarray(x_prev, float)
    lo = [-6, -25, -25, -3, -3]
    hi = [22, 25, 25, 3, 3]
    x = least_squares(res, x0, bounds=(lo, hi)).x
    return x, hands, body_of(x)


def planted_legs(body):
    """The legs for a body: both feet aimed at their spots, the hips lowered to the height that splits the miss between them."""
    keys, misses = {}, {}
    for side in ('right_leg', 'left_leg'):
        hip = HIP_JOINT[side] + body.pr
        keys[side], misses[side], _ = leg_key(side, hip, np.array(STANCE[side]))
    return keys, misses


def drop_for_stance(rx=0.0, rz=0.0):
    """How far the hips sit lowered, with the root moved [rx] and [rz] sideways and back, for the feet to reach their spots
    with the legs at their full 12 px (the mean of what each leg wants)."""
    out = []
    for side in ('right_leg', 'left_leg'):
        f = np.array(STANCE[side])
        h = HIP_JOINT[side]
        dxz = math.hypot(f[0] - (h[0] + rx), f[2] - (h[2] + rz))
        out.append(GROUND - math.sqrt(max(0.0, 144.0 - dxz * dxz)) - 12.0)
    return float(np.mean(out))


def build_helm():
    c = Clip('helm', HELM['length'], blend_in=0.0, blend_out=0.0, rate=HELM['rate'])
    n = int(round(HELM['length'] * HELM['rate']))
    x = None
    rows = []
    # solve outward from amidships both ways, so each answer starts from its neighbour's
    order = sorted(range(n + 1), key=lambda i: abs(i - n / 2))
    sol = {}
    for i in order:
        h = (i - n / 2) / (n / 2)
        phi = WHEEL_DEGREES * h
        prev = None
        for j in (i - 1, i + 1):
            if j in sol and (prev is None or abs(j - n / 2) < abs(prev - n / 2)):
                prev = j
        x_prev = sol[prev][0] if prev is not None else None
        sol[i] = solve_helm(phi, h, x_prev)
    for i in range(n + 1):
        t = i / HELM['rate']
        x, hands, body = sol[i]
        c.key('root', t, I, pos=body.pr, ease='linear')
        c.key('torso', t, body.qt, ease='linear')
        for side in ('right_arm', 'left_arm'):
            q, g, miss = body.arm_key(side, hands[side][2])
            c.key(side, t, q, ease='linear')
        keys, misses = planted_legs(body)
        for side in ('right_leg', 'left_leg'):
            c.key(side, t, keys[side], ease='linear')
    return c, sol


# ================================================================== checking a baked clip with the game's own chain

def fk_pose(clip, t):
    """The pose the game would show for [clip] at [t]: part -> (rotation, pivot) in the player's frame, as animkit's pose_clip
    (CombatAnimator's chain), the root's turn included."""
    return pose_clip(clip, t)


def part_point(pose, part, p):
    """A point of a part's own space, in the player's frame."""
    qr, pr = pose['root']
    q, pv = pose[part]
    return apply(T(*pr) @ Rq(qr) @ T(*pv) @ Rq(q), p)


def arm_line(pose, side):
    """The arm's middle line in the player's frame: from its joint to its end."""
    return part_point(pose, side, (ARM_X[side], 0.0, 0.0)), part_point(pose, side, (ARM_X[side], 10.0, 0.0))


def along(a, b, p):
    """Where the point p is on the line a-b: px along it from a, and px off it."""
    d = unit(b - a)
    s = float(np.dot(p - a, d))
    return s, float(np.linalg.norm(p - a - s * d))


def check_helm(clip, report):
    """How well the baked clip, read the way the game reads it, keeps the hands on the handles (each hand's handle point's
    distance from the arm's middle line, and how far along the arm it is held), the feet on their spots, the chest off
    the wheel. Tested at times between the samples too."""
    worst = dict(hand=0.0, hand_at=None, feet=0.0, grip_lo=99.0, grip_hi=0.0, chest=99.0, pitch=(99, -99), roll=(99, -99), yaw=(99, -99))
    n = int(round(clip.length * clip.rate))
    for k in range(0, 40 * n // 2 * 2, 1):
        t = clip.length * k / (40 * n // 2 * 2)
        h = (t - clip.length / 2) / (clip.length / 2)
        phi = WHEEL_DEGREES * h
        pose = fk_pose(clip, t)
        for side in ('right_arm', 'left_arm'):
            psi, free, target = hand_on_wheel(side, phi)
            a, b = arm_line(pose, side)
            s, off = along(a, b, target)
            if off > worst['hand']:
                worst['hand'], worst['hand_at'] = off, (round(phi, 1), side)
            worst['grip_lo'] = min(worst['grip_lo'], s)
            worst['grip_hi'] = max(worst['grip_hi'], s)
        for side, leg in (('right_arm', 'right_leg'), ('left_arm', 'left_leg')):
            foot = part_point(pose, leg, (0.0, 12.0, 0.0))
            worst['feet'] = max(worst['feet'], float(np.linalg.norm(foot - np.array(STANCE[leg]))))
        zmin = min(part_point(pose, 'body', (xs, ys, -2.0))[2] for xs in (-4, 4) for ys in (0.0, 4.0))
        worst['chest'] = min(worst['chest'], zmin - (AXLE[2] + 1.2))
    report.append(f"hands on the handles: worst {worst['hand']:.2f} px off the arm's line (at wheel {worst['hand_at']}), held "
                  f"{worst['grip_lo']:.1f} to {worst['grip_hi']:.1f} px down the arm (it is 10 long)")
    report.append(f"feet on their spots: worst {worst['feet']:.2f} px; chest {worst['chest']:.1f} px behind the rim's near face at its closest")
    return worst


# ================================================================== previews: the player, the wheel and the deck

SKIN_RECTS = None


def fallback_skin():
    """A plain 64x64 skin painted on the player model's own faces, for when no skin is to hand: sailor's clothes."""
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cols = {'head': (214, 170, 136), 'body': (88, 104, 128), 'right_arm': (88, 104, 128), 'left_arm': (88, 104, 128),
            'right_leg': (74, 62, 52), 'left_leg': (74, 62, 52)}
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
            B = A + axes[j] * 2 * half[j]
            D = A + axes[k] * 2 * half[k]
            light = 0.55 + 0.45 * max(0.0, float(np.dot(n, L)))
            # the quad must face along n for the painter's depth test to be consistent; either way it is drawn
            quads.append((A, B, D, (0, 0, 1, 1), light, tex))
    return quads


TIMBER, BRASS, IRONC, DECK = solid_tex((112, 80, 50)), solid_tex((190, 150, 72)), solid_tex((70, 72, 80)), solid_tex((92, 70, 48))


def wheel_quads(phi):
    """The ship's wheel as ship.py builds it (sixteen rim blocks, four bars through a hub, brass caps and boss), turned [phi]
    degrees clockwise as the helmsman sees it, with its two posts and foot, in the player's frame."""
    quads = []
    uz = np.array([0.0, 0.0, 1.0])

    def frame(psi):
        p = math.radians(psi)
        ur = np.array([-math.sin(p), -math.cos(p), 0.0])
        ut = np.array([-math.cos(p), math.sin(p), 0.0])
        return ur, ut

    ur, ut = frame(phi)
    quads += oriented_box(AXLE, [ur, ut, uz], (2.8, 2.8, 1.6), TIMBER)
    quads += oriented_box(AXLE + uz * -2.1, [ur, ut, uz], (2.2, 2.2, 0.5), BRASS)
    for k in range(16):
        ur, ut = frame(phi + 22.5 * k)
        quads += oriented_box(AXLE + ur * 9.1, [ur, ut, uz], (0.9, 2.1, 1.2), TIMBER)
    for k in range(4):
        ur, ut = frame(phi + 45.0 * k)
        quads += oriented_box(AXLE, [ur, ut, uz], (13.0, 0.8, 1.0), TIMBER)
        for sgn in (-1, 1):
            quads += oriented_box(AXLE + ur * 14.0 * sgn, [ur, ut, uz], (1.2, 1.3, 1.3), BRASS)
    # the posts either side of it, the axle between them, the foot plate on the deck (ship.py: wheel_post_f/b, wheel_axle, wheel_foot)
    ex, ey, ez = np.eye(3)
    quads += oriented_box((0.0, 15.0, -10.5), [ex, ey, ez], (2.2, 9.0, 1.1), TIMBER)
    quads += oriented_box((0.0, 15.0, -3.5), [ex, ey, ez], (2.2, 9.0, 1.1), TIMBER)
    quads += oriented_box((0.0, 8.0, -7.0), [ex, ey, ez], (1.0, 1.0, 4.6), IRONC)
    quads += oriented_box((0.0, 23.3, -7.0), [ex, ey, ez], (3.6, 0.7, 5.4), IRONC)
    return quads


def deck_quads():
    ex, ey, ez = np.eye(3)
    return oriented_box((0.0, GROUND + 1.5, 0.0), [ex, ey, ez], (34.0, 1.5, 34.0), DECK)


def make_scene(skin_path=None):
    from animkit import Scene
    if skin_path and os.path.exists(skin_path):
        skin = Image.open(skin_path).convert('RGBA')
    else:
        skin = fallback_skin()
    return Scene(skin, Image.new('RGBA', (16, 16), (0, 0, 0, 0)))


def draw_frame(scene, clip, t, extras, views, size, labels=()):
    """One frame of the preview: the posed player and [extras] (quads of the wheel, the deck) from each of [views] (yaw,
    pitch, zoom, centre y), side by side."""
    pose = pose_clip(clip, t)
    quads = scene.player_world(pose, sword=False) + list(extras)
    W, H = size
    out = Image.new('RGB', (W * len(views), H), (58, 64, 80))
    for i, view in enumerate(views):
        yaw, pitch, zoom, cy = view[:4]
        sx = view[4] if len(view) > 4 else 0.0
        proj = ortho(yaw, pitch, zoom, (W, H), cy)
        shift = np.array([sx, 0.0, 0.0])
        img = raster(quads, lambda p, proj=proj, shift=shift: proj(p) + shift, (W, H), bg=(60, 70, 92))
        out.paste(img, (i * W, 0))
    d = ImageDraw.Draw(out)
    for k, text in enumerate(labels):
        d.text((4, 3 + 11 * k), text, fill=(240, 236, 220))
    return out


HELM_VIEWS = [(150.0, -22.0, 7.0, 10.0), (28.0, -16.0, 7.0, 10.0)]


def helm_sheet(scene, clip, phis, size=(190, 250)):
    """A contact sheet of the helm at [phis] wheel angles: from behind-right and from the front-right."""
    out = Image.new('RGB', (size[0] * 2 * len(phis) // 2, size[1] * 2), (30, 32, 40))
    for i, phi in enumerate(phis):
        t = clip.length * (0.5 + 0.5 * phi / WHEEL_DEGREES)
        extras = wheel_quads(phi) + deck_quads()
        for j, view in enumerate(HELM_VIEWS):
            f = draw_frame(scene, clip, t, extras, [view], size, labels=(f'wheel {phi:+.0f}  t={t:.1f}',) if j == 0 else ())
            out.paste(f, (i * size[0], j * size[1]))
    return out


# ================================================================== the lookout

LOOKOUT = dict(length=120.0, rate=2)
HOLD = np.array([-7.0, 1.0, -8.0])          # where the right hand takes the forestay: shoulder height, forward and to the right
STAY_DIR = unit(np.array([0.0, -0.86, 0.5]))   # the stay rises toward the stern from there (the real one climbs to the masthead)
BROW = np.array([2.6, -7.0, -5.0])          # where the left hand shades the eyes: at the left brow, a hair ahead of the face
SHRUG = np.array([0.0, -0.9, -0.5])         # the left shoulder lifts and comes forward that far (the arm's pivot, in the torso's frame) as the hand goes up
LOOK_STANCE = {'left_leg': (2.9, GROUND, -0.8), 'right_leg': (-4.6, GROUND, -2.6)}    # the weight on the left, the right foot resting out


def ease_pulse(t, a, b, up, down):
    """0 before [a], rising over [up] ticks to 1, holding, falling over [down] ticks to 0 at [b]."""
    if t <= a or t >= b:
        return 0.0
    return smooth01((t - a) / up) * smooth01((b - t) / down)


def lookout_style(t):
    """The body's slow life at tick [t] of the loop: the swell's roll and pitch, the weight on the left leg, the hips and
    shoulders answering it, and a breath. Everything has a period of 120, 60 or 40 ticks, so the loop closes on itself."""
    a = 2 * math.pi * t / 120.0
    b = 2 * math.pi * t / 40.0
    swell = math.sin(a)
    roll_sea = math.sin(a + 0.35)
    pitch_sea = math.sin(2 * a + 0.9)
    return dict(
        rx=1.25 + 0.8 * math.sin(a + 0.6),                     # the hips over the left leg, rocking with the swell
        rz=0.4 * math.sin(2 * a + 0.2),
        pitch=3.2 + 1.6 * pitch_sea + 0.25 * math.sin(b),
        yaw=-2.2 + 2.2 * math.sin(a + 2.0),                    # the shoulders scanning the horizon
        roll=2.4 - 2.6 * roll_sea + 0.2 * math.sin(b + 1.0),   # the right shoulder up, answering the swell
        swell=swell)


def left_rest_q(t):
    """The left arm hanging loose, a little out and ahead, swinging a hair against the swell (relative to the torso)."""
    a = 2 * math.pi * t / 120.0
    d = unit(np.array([0.20 + 0.04 * math.sin(a + 1.2), 1.0, -0.10 + 0.05 * math.sin(2 * a)]))
    return frame_to(np.array([0.0, 1.0, 0.0]), np.array([1.0, 0.0, 0.0]), d, np.array([1.0, 0.0, 0.0]))


def lookout_gestures(t):
    """How far the left hand has come up to the brow (0 hanging, 1 shading the eyes), and how far the right hand has slid down
    the stay (px) as it shifts its grip."""
    shade = max(ease_pulse(t, 30.0, 70.0, 11.0, 11.0), ease_pulse(t, 88.0, 112.0, 7.0, 8.0))
    shade = shade * (1.0 + 0.0 * math.sin(t))
    slide = 1.3 * ease_pulse(t, 14.0, 26.0, 6.0, 6.0) + 1.0 * ease_pulse(t, 74.0, 84.0, 5.0, 5.0)
    return shade, slide


def lookout_frame(t):
    st = lookout_style(t)
    shade, slide = lookout_gestures(t)
    drop = mean_drop(LOOK_STANCE, st['rx'], st['rz'])
    body = Body(pr=(st['rx'], drop, st['rz']), qt=torso_q(st['pitch'], st['yaw'], st['roll']), pa={'left_arm': SHRUG * shade})
    return st, shade, slide, body


def mean_drop(stance, rx=0.0, rz=0.0):
    out = []
    for side, f in stance.items():
        h = HIP_JOINT[side]
        dxz = math.hypot(f[0] - (h[0] + rx), f[2] - (h[2] + rz))
        out.append(GROUND - math.sqrt(max(0.0, 144.0 - dxz * dxz)) - 12.0)
    return float(np.mean(out))


def build_lookout():
    c = Clip('lookout', LOOKOUT['length'], blend_in=0.0, blend_out=0.0, rate=LOOKOUT['rate'])
    n = int(round(LOOKOUT['length'] * LOOKOUT['rate']))
    for i in range(n + 1):
        t = (i % n) / LOOKOUT['rate']              # the last key is the first, exactly: the loop closes
        tt = i / LOOKOUT['rate']
        st, shade, slide, body = lookout_frame(t)
        c.key('root', tt, I, pos=body.pr, ease='linear')
        c.key('torso', tt, body.qt, ease='linear')
        grip = HOLD + STAY_DIR * slide
        q, g, miss = body.arm_key('right_arm', grip)
        c.key('right_arm', tt, q, ease='linear')
        q_rest = left_rest_q(t)
        if shade > 0.0:
            q_brow, g, miss = body.arm_key('left_arm', BROW)
            q_left = qslerp(q_rest, q_brow, shade)
        else:
            q_left = q_rest
        c.key('left_arm', tt, q_left, pos=body.pa['left_arm'], ease='linear')
        for side in ('right_leg', 'left_leg'):
            hip = HIP_JOINT[side] + body.pr
            q, miss, reached = leg_key(side, hip, np.array(LOOK_STANCE[side]), toe_out=(18.0 if side == 'right_leg' else 6.0))
            c.key(side, tt, q, ease='linear')
    return c


def check_lookout(clip, report):
    n = int(round(clip.length * clip.rate))
    worst = dict(hold=0.0, feet=0.0, brow=99.0, loop=0.0)
    for k in range(0, 4 * n + 1):
        t = clip.length * k / (4 * n)
        pose = fk_pose(clip, t)
        st, shade, slide, body = lookout_frame(t % clip.length)
        a, b = arm_line(pose, 'right_arm')
        s, off = along(a, b, HOLD + STAY_DIR * slide)
        worst['hold'] = max(worst['hold'], off)
        for leg in ('right_leg', 'left_leg'):
            foot = part_point(pose, leg, (0.0, 12.0, 0.0))
            worst['feet'] = max(worst['feet'], float(np.linalg.norm(foot - np.array(LOOK_STANCE[leg]))))
        if shade > 0.999:
            a, b = arm_line(pose, 'left_arm')
            s, off = along(a, b, BROW)
            worst['brow'] = min(worst['brow'], off) if worst['brow'] != 99.0 else off
    for bone, keys in clip.keys.items():
        q0, q1 = keys[0][1], keys[-1][1]
        worst['loop'] = max(worst['loop'], math.degrees(2 * math.acos(min(1.0, abs(float(np.dot(q0, q1)))))))
    report.append(f"right hand on the stay: worst {worst['hold']:.2f} px off the arm's line; feet: worst {worst['feet']:.2f} px off their spots; "
                  f"left hand at the brow: {worst['brow']:.2f} px off the arm's line; loop seam {worst['loop']:.3f} degrees")
    return worst


# ================================================================== the moving previews

GIF_PANEL = (190, 250)
FPS = 25


def stay_quads():
    """A short length of the forestay through the lookout's hold, to show where the hand is meant to be."""
    ex = np.array([1.0, 0.0, 0.0])
    side = unit(np.cross(STAY_DIR, ex))
    up = np.cross(STAY_DIR, side)
    return oriented_box(HOLD, [STAY_DIR, side, up], (24.0, 0.6, 0.6), solid_tex((176, 146, 92)))


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


HELM_GIF_VIEWS = [(160.0, 14.0, 7.4, 8.0, 14.0), (-24.0, 8.0, 7.4, 8.0, 0.0)]
LOOK_GIF_VIEWS = [(158.0, 12.0, 7.4, 6.0, -14.0), (24.0, 8.0, 7.4, 6.0, -16.0)]


def helm_gif(scene, clip, path, per_frame=6.75):
    """The wheel put hard over to port, through amidships to hard over to starboard and back, at [per_frame] degrees a frame
    (the game does about 16 degrees a tick, so this is a slowed sweep), with holds at the ends and amidships."""
    steady = lambda phi, n: [phi] * n
    sweep = lambda a, b: list(np.linspace(a, b, int(round(abs(b - a) / per_frame)) + 1)[1:])
    seq = steady(0.0, 14) + sweep(0.0, -WHEEL_DEGREES) + steady(-WHEEL_DEGREES, 12) + sweep(-WHEEL_DEGREES, WHEEL_DEGREES) \
        + steady(WHEEL_DEGREES, 12) + sweep(WHEEL_DEGREES, 0.0)
    deck = deck_quads()
    frames = []
    for phi in seq:
        t = clip.length * (0.5 + 0.5 * phi / WHEEL_DEGREES)
        side = 'to starboard' if phi > 0.5 else ('to port' if phi < -0.5 else 'amidships')
        frames.append(draw_frame(scene, clip, t, wheel_quads(phi) + deck, HELM_GIF_VIEWS, GIF_PANEL,
                                 labels=(f'helm {phi / WHEEL_DEGREES * 100:+.0f}%  wheel {phi:+.0f}  ({side})',)))
    return gif_save(frames, path, int(1000 / FPS)), len(frames)


def lookout_gif(scene, clip, path):
    """One lap of the loop (120 ticks, 20 a second, shown at 25 frames a second), the stay drawn through the hold."""
    n = int(round(clip.length / 20.0 * FPS))
    extras = deck_quads() + stay_quads()
    frames = []
    for i in range(n):
        t = clip.length * i / n
        frames.append(draw_frame(scene, clip, t, extras, LOOK_GIF_VIEWS, GIF_PANEL, labels=(f'lookout  tick {t:5.1f} / {clip.length:.0f}',)))
    return gif_save(frames, path, int(1000 / FPS)), len(frames)


# ================================================================== main

def describe(clip):
    parts = [b for b in ('root', 'torso', 'head', 'right_arm', 'left_arm', 'right_leg', 'left_leg') if b in clip.keys]
    return ', '.join(parts)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return
    assets = argv[1]
    previews = argv[2] if len(argv) > 2 else None
    skin = argv[3] if len(argv) > 3 else None
    helm, _ = build_helm()
    lookout = build_lookout()
    for c in (helm, lookout):
        path = c.write(assets, GROUP)
        print(f'wrote {path}  ({os.path.getsize(path) / 1000:.0f} kB)')
    for c in (helm, lookout):
        print(f'{c.name:8s} {c.length:>4g} ticks  rate {c.rate}  blend {c.blend_in:g}/{c.blend_out:g}  [{describe(c)}]')
    rep = []
    check_helm(helm, rep)
    check_lookout(lookout, rep)
    print('\n'.join(rep))
    if previews:
        scene = make_scene(skin)
        out = os.path.join(previews, GROUP)
        size, n = helm_gif(scene, helm, os.path.join(out, 'helm.gif'))
        print(f'helm.gif: {n} frames, {size / 1e6:.2f} MB')
        size, n = lookout_gif(scene, lookout, os.path.join(out, 'lookout.gif'))
        print(f'lookout.gif: {n} frames, {size / 1e6:.2f} MB')


if __name__ == '__main__':
    main(sys.argv)
