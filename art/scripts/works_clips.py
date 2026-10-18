"""
The town works' moves (M6, influence: living-world/design-influence.md, sections 3 and 8), on the Living World's kit.
The master builder's crew: turning mortar in the tub (mix_mortar), laying a course with the trowel (trowel), hauling
hand over hand on the hoist's rope (hoist), a beam carried on the shoulder by two in step (carry_beam, an overlay),
dressing a stone with mallet and chisel (chisel), the plumb line held against a wall (plumb) and the master sighting
along a staff (survey). The ceremonies: the cloth whipped off the patron's plaque (unveil) and the toast to the town
(toast). The watchtower: its watch on the lookout, a hand shading the eyes (lookout), the beacon lit with a torch
(light_beacon), and the horn blown when a strike is seen (sound_horn: no Activity plays it; the game plays it in
lookout's place).

living_anims.py imports this at its end and calls build() with itself, so these go out with the Living World's other
clips and LivingSchedule.kt stays one generated file; street_clips.py (the streets' clips) shares the pieces here. The
blocks they work at come from works_art.py (the mortar tub, the hoist's rope, the brazier, the plaque), so a hoe goes
into the mortar and a torch into the coals; living-world/works-art.md says where the game must stand each one.

Clip space as every townsfolk clip's: pixels, y down, facing -z, the right hand at -x, the feet at y 24.
"""
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import townsfolk_anims as ta
from townsfolk_anims import (qeuler, qmul, qmat, qslerp, qfrommat, unit, I, v, F, B, U, D, R, L, T, Rq, F_, bend,
                             key_frame, check, seal, smooth, bump, apply, GRIP, PIVOTS, HIPS, EYES, MOUTH, LIPS, free,
                             rel, head_at, arm_world, hand_point, body_solve, sink_for, torso_to_world, tool_point,
                             tool_dir, clear_of_body, qinv)
from animkit import qaxis
import living_props as lp
import works_art as wa

la = None           # living_anims, handed to build()

# ================================================================== the pieces these share (street_clips.py too)

REST = F_()                                     # standing at rest: idle's first frame
FEET = {'right_leg': (-1.9, 0.0), 'left_leg': (1.9, 0.0)}


def K(keys, t, loop=None):
    return la.K(keys, t, loop)


def KV(keys, t, loop=None):
    return la.KV(keys, t, loop)


def rest_arm(side, fr=REST, out=None, fwd=0.0, lift=0.0):
    """An arm hanging at rest ([fwd] degrees forward), its shoulder up [lift] px: a (key, offset) pose."""
    return la.hang(side, fr, out=out, fwd=fwd), np.array([0.0, -lift, 0.0])


def point_arm(fr, d, palm=None, lift=0.0):
    """A free arm along world [d], its front face (the palm) towards world [palm], the shoulder up [lift] px."""
    return free(fr, unit(d), None if palm is None else unit(palm)), np.array([0.0, -lift, 0.0])


def mix(a, b, w):
    """Two (key, offset) arm poses mixed: [w] 0 is [a], 1 is [b]."""
    return la.mix_arm(a, b, w)


def lifted(pose, lift):
    return pose[0], np.asarray(pose[1], float) + np.array([0.0, -lift, 0.0])


def set_arm(c, side, t, pose):
    la.key_arm(c, side, t, pose)


def grip_of(side, pose, fr, p=None):
    """Where the hand's end (the grip, or arm-space [p]) is for the arm [pose] under [fr]."""
    Ra, j = arm_world(side, pose[0], arm_pos=pose[1], **fr)
    return j + Ra @ (GRIP[side] if p is None else np.asarray(p, float))


def reach_arm(holds, side, fr, target, palm=None, palm_w=0.3, slack=0.6, limit=1.6, p=None, dirs=()):
    """The arm with its grip (or arm-space [p]) on world [target], the shoulder rolling up to [limit] px to get it there,
    the palm towards world [palm] (and any more arm-space directions [dirs] turned toward world ones): solved from the
    last answer, so a hand on a path never flips."""
    ds = list(dirs) + ([] if palm is None else [((0.0, 0.0, -1.0), unit(palm), palm_w)])
    g = GRIP[side] if p is None else np.asarray(p, float)
    return holds.slack(side, fr, [(g, np.asarray(target, float), 3.0)], ds, slack=slack, limit=limit)


def stand(c, t, fr, feet=FEET, lift=(0.0, 0.0), floor=24.0):
    la.plant(c, t, fr, right=feet['right_leg'], left=feet['left_leg'], floor=floor, lift=lift)


def stepping(t, start, steps, lift=1.4):
    """Feet that step: from [start] {side: (x, z)}, then [steps] (t0, t1, side, (x, z)) in time order, one foot at a time,
    each lifted [lift] px at its middle. Returns ({side: (x, z)}, (right lift, left lift)) at [t]."""
    feet = {k: np.asarray(v, float) for k, v in start.items()}
    lifts = {'right_leg': 0.0, 'left_leg': 0.0}
    for t0, t1, side, to in steps:
        if t <= t0:
            break
        u = smooth((t - t0) / (t1 - t0)) if t < t1 else 1.0
        feet[side] = feet[side] + (np.asarray(to, float) - feet[side]) * u
        if t < t1:
            lifts[side] = lift * math.sin(math.pi * (t - t0) / (t1 - t0))
    return {k: (float(v[0]), float(v[1])) for k, v in feet.items()}, (lifts['right_leg'], lifts['left_leg'])


def head_pt(fr, qh, p):
    """A head-space point for an 'add' head key [qh] under [fr] (the previews' look straight ahead)."""
    return torso_to_world(fr, PIVOTS['head']) + qmat(qmul(fr['qr'], qh)) @ np.asarray(p, float)


def wave(t, a, b, period, amp=1.0):
    """A shake between ticks [a] and [b]: a sine of [period] ticks under a hump that fades it in and out."""
    return amp * bump(t, a, b) * math.sin(2 * math.pi * (t - a) / period)


def pose_now(c, t):
    """[c]'s pose at [t], standing still, as the game poses it."""
    return ta.pose_at(c, t, ta.walk_base(t, speed=0.0, age=t))


def part_M(c, t, part):
    """A body part's 4x4 at [t] of [c] (the root, then the part's own key): for prop tracks, which can't ask the tracked
    part matrices (they are what is being worked out)."""
    pose = pose_now(c, t)
    qr, pr = pose['root']
    q, piv = pose[part]
    return T(*pr) @ Rq(qr) @ T(*piv) @ Rq(q)


def prop_M(c, t, prop):
    """A prop's 4x4 at [t] as its parent carries it, untracked."""
    return part_M(c, t, ta.tool_parent(prop)) @ ta.tool_local(prop)


def joins_idle(c, start=True, end=True, tol=1.0):
    try:
        idle = la.getc('idle')
    except StopIteration:
        return
    if start:
        la.join(f'{c.name} starts on idle', c, 0, idle, 0, tol=tol)
    if end:
        la.join(f'{c.name} ends on idle', c, c.length, idle, 0, tol=tol)


def maybe_join(label, a, ta_, b_name, tb, M=None, tol=0.6):
    """A join to a clip living_anims' full run builds (the quick previews may leave it out)."""
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


def feet_down(c, times, feet=FEET, floor=24.0, tol=0.4):
    for t in times:
        for side, (x, z) in feet.items():
            check(c, f'{side} foot planted (t={t:g})', la.leg_end(c, t, side), (x, floor, z), tol=tol)


def frame_of(y_axis, z_hint):
    """A rotation (3 x 3) whose y runs along [y_axis] and whose z is as near [z_hint] as it can be."""
    y = unit(y_axis)
    z = np.asarray(z_hint, float) - y * float(np.dot(z_hint, y))
    if np.linalg.norm(z) < 1e-6:
        z = np.cross(y, (1.0, 0.0, 0.0))
    z = unit(z)
    return np.column_stack([np.cross(y, z), y, z])


def rot_about(axis, deg):
    return qmat(qaxis(unit(axis), deg))


def solve_standing(res, feet, pitch, yaw, root_xz, dip=0.0, guess=None, roll=0.0, **kw):
    """body_solve for someone standing on planted [feet]: the hips as high as the legs to them allow at the step the
    solve finds, dipped [dip] px more (the knees giving). Returns (frame, right key, left key, solution)."""
    drop = la.root_for(feet, root_xz[0], root_xz[1], dip=dip)[1]
    for _ in range(3):
        fr, qa, qb, x = body_solve(res, pitch0=pitch, yaw0=yaw, roll0=roll, root0=(root_xz[0], 0.0, root_xz[1]),
                                   guess=guess, sink0=sink_for(pitch), drop=drop, **kw)
        want = la.root_for(feet, x[4], x[5], dip=dip)[1]
        if abs(want - drop) < 0.02:
            break
        drop, guess = want, x
    return fr, qa, qb, x


def loop_solve(times, step):
    """Solves a loop tick by tick, each from the answer before (step(t, guess) -> (solution, x)), then again from the
    end's answer round to where the second lap agrees with the first, so the seam joins the same family of answers."""
    sols, xs, guess = {}, {}, None
    for t in times:
        sols[t], guess = step(t, guess)
        xs[t] = guess
    for t in times:
        sol, x = step(t, guess)
        if float(np.max(np.abs(np.asarray(x) - np.asarray(xs[t])))) < 2e-3:
            break
        sols[t], xs[t], guess = sol, x, x
    return sols


def tool_contacts(tool, point_p, target, shaft=None, left_p=None, fist=None, square=1.0, w_point=5.0, w_shaft=4.0,
                  w_fist=2.0, w_left=2.5, left_across=1.0, clear=True):
    """Residuals for a two-handed tool held in the right fist: its point [point_p] (tool space) at world [target], its
    shaft (tool +y) along world [shaft], the right fist near world [fist], the left fist on it at [left_p] (tool space)
    with the shaft running across that fist (the arm's z), the blade's width (tool x) level, both fists clear of the
    body."""
    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        out = list((ja + Ra @ tool_point(tool, point_p) - np.asarray(target, float)) * w_point)
        d = Ra @ tool_dir(tool, (0, 1, 0))
        if shaft is not None:
            out += list((d - unit(shaft)) * w_shaft)
        if fist is not None:
            out += list((ja + Ra @ GRIP['right_arm'] - np.asarray(fist, float)) * w_fist)
        lh = hand_point('left_arm', qb, fr)
        if left_p is not None:
            out += list((lh - (ja + Ra @ tool_point(tool, left_p))) * w_left)
            Rb, _ = arm_world('left_arm', qb, **fr)
            out += list(np.cross(Rb @ np.array([0.0, 0.0, 1.0]), d) * left_across)
        if square:
            out += [(Ra @ tool_dir(tool, (1, 0, 0)))[1] * square]
        if clear:
            out += [clear_of_body(fr, ja + Ra @ GRIP['right_arm']), clear_of_body(fr, lh)]
        return out
    return res


class Chain:
    """Points hanging one from the next (a cord, a cloth's edge), simulated in Verlet steps with distance constraints,
    the first carried by anchor(t) -> world point: for props that swing and trail behind what moves them. run() steps
    over [t0, t1] [sub] times a tick and keeps the points every half tick; at(t) interpolates them."""

    def __init__(self, anchor, rest, links=None, gravity=0.06, damp=0.04, sub=8, iters=8):
        # [damp]: a number, or damp(t) -> one (a hand that stills the line)
        self.anchor, self.gravity, self.damp, self.sub, self.iters = anchor, gravity, damp, sub, iters
        self.rest = [np.asarray(p, float) for p in rest]
        n = len(self.rest)
        self.links = links if links is not None else [(i, i + 1) for i in range(n - 1)]
        self.lengths = [float(np.linalg.norm(self.rest[b] - self.rest[a])) for a, b in self.links]
        self.frames = {}

    def run(self, t0, t1, pins=None, planes=()):
        """[pins](t) -> {index: world point} holds points where they are put (the anchor is 0); [planes]: (point,
        normal) half-spaces the points stay on the normal's side of (a wall)."""
        p = [q.copy() for q in self.rest]
        prev = [q.copy() for q in p]
        n = int(round((t1 - t0) * self.sub))
        g = np.array([0.0, self.gravity, 0.0])
        for i in range(n + 1):
            t = t0 + i / self.sub
            damp = self.damp(t) if callable(self.damp) else self.damp
            pinned = dict(pins(t)) if pins else {}
            if self.anchor is not None:
                pinned[0] = np.asarray(self.anchor(t), float)
            for k in range(len(p)):
                if k in pinned:
                    prev[k] = p[k].copy() if i else np.asarray(pinned[k], float)
                    p[k] = np.asarray(pinned[k], float)
                    continue
                vel = (p[k] - prev[k]) * (1.0 - damp)
                prev[k] = p[k].copy()
                p[k] = p[k] + vel + g
            for _ in range(self.iters):
                for (a, b), ln in zip(self.links, self.lengths):
                    d = p[b] - p[a]
                    dist = float(np.linalg.norm(d))
                    if dist < 1e-9:
                        continue
                    err = (dist - ln) / dist
                    fa, fb = a in pinned, b in pinned
                    if fa and fb:
                        continue
                    if fa:
                        p[b] = p[b] - d * err
                    elif fb:
                        p[a] = p[a] + d * err
                    else:
                        p[a] = p[a] + d * err * 0.5
                        p[b] = p[b] - d * err * 0.5
                for pt, nrm in planes:
                    nrm = unit(nrm)
                    for k in range(len(p)):
                        if k in pinned:
                            continue
                        s = float(np.dot(p[k] - np.asarray(pt, float), nrm))
                        if s < 0:
                            p[k] = p[k] - nrm * s
                            prev[k] = prev[k] + (p[k] - prev[k]) * 0.6      # friction on the wall
            if abs(t * 2 - round(t * 2)) < 1e-6:
                self.frames[round(t * 2) / 2.0] = [q.copy() for q in p]
        return self

    def at(self, t):
        ks = sorted(self.frames)
        t = min(max(t, ks[0]), ks[-1])
        k = math.floor(t * 2) / 2.0
        a = self.frames[k]
        b = self.frames.get(k + 0.5, a)
        u = (t - k) / 0.5
        return [pa * (1 - u) + pb * u for pa, pb in zip(a, b)]

# ================================================================== the stations the previews draw

WOOD = (138, 100, 62)
WOOD_D = (104, 74, 46)
STONE = (150, 150, 156)
STONE_D = (112, 112, 120)
MORTAR_C = (196, 190, 172)
EARTH = (104, 80, 56)
PLASTER = (200, 192, 170)
IRONC = (60, 60, 66)
COALS = (200, 90, 30)
BRONZE = (170, 120, 60)


def bx(lo, hi, col, lit=True):
    return (tuple(float(x) for x in lo), tuple(float(x) for x in hi), col, lit)


def _tub():
    x0, x1, z0, z1, top = wa.TUB_X0, wa.TUB_X1, wa.TUB_Z0, wa.TUB_Z1, wa.TUB_TOP
    return [bx((x0, top, z0), (x1, 24, z0 + 1), WOOD), bx((x0, top, z1 - 1), (x1, 24, z1), WOOD),
            bx((x0, top, z0), (x0 + 1, 24, z1), WOOD_D), bx((x1 - 1, top, z0), (x1, 24, z1), WOOD_D),
            bx((x0 + 1, wa.TUB_MORTAR, z0 + 1), (x1 - 1, 24, z1 - 1), MORTAR_C)]


STATION_BOXES = {'mortar_tub': _tub}
STATION_GROUND = {}
STATION_COLOUR = {'mortar_tub': EARTH}

# ================================================================== the builders' crew

# ---- mix_mortar (40, loop): turning mortar in the tub a block ahead with the mortar hoe, the right fist at the shaft's
# end by the hip and the left down it, the left foot forward, standing back from the tub: twice round, each a heavy push
# of the blade along the tub's floor to its far side, leaning into it as the knees give and the weight goes onto the
# front foot, the blade lifted out there and drawn back over the top to fold the mortar in, the weight back onto the
# right foot; the second stroke a little to the left of the first. The mortar squelches at each push (stir).
MM_LEN = 40
MM_BACK = 2.5                                   # the stance this far back of the block's middle
MM_FEET = {'right_leg': (-3.0, MM_BACK + 3.2), 'left_leg': (2.8, MM_BACK - 3.0)}
MM_BLADE = lp.MORTAR_HOE_BLADE
MM_LEFT = (0.0, lp.MORTAR_HOE_LEFT, 0.0)
_bot, _top = wa.TUB_FLOOR - 1.4, wa.TUB_MORTAR - 0.3
_near, _far = wa.TUB_Z1 - 3.0, wa.TUB_Z0 + 3.4
MM_STROKE = [(0.0, (0.0, _bot + 0.6, _near - 0.6)), (2.5, (0.0, _bot, _near - 1.6)), (7.5, (0.0, _bot, _far + 0.4)),
             (9.5, (0.0, _top + 0.2, _far - 0.2)), (11.5, (0.0, _top - 1.0, _far + 1.8)), (16.0, (0.0, _top - 0.2, _near + 0.2)),
             (18.0, (0.0, _bot + 1.4, _near)), (20.0, (0.0, _bot + 0.6, _near - 0.6))]
MM_SLOPE = [(0.0, 42.0), (2.5, 41.0), (7.5, 33.0), (9.5, 29.0), (11.5, 28.0), (16.0, 36.0), (18.0, 41.0), (20.0, 42.0)]
MM_PITCH = [(0.0, 13.0), (2.5, 15.0), (7.5, 24.0), (9.5, 24.0), (11.5, 19.0), (16.0, 10.0), (18.0, 11.0), (20.0, 13.0)]
MM_ROOT_Z = [(0.0, 0.2), (2.5, -0.2), (7.5, -1.6), (9.5, -1.6), (11.5, -0.8), (16.0, 0.9), (18.0, 0.6), (20.0, 0.2)]
MM_DROP = [(0.0, 1.0), (2.5, 1.2), (7.5, 2.2), (9.5, 2.0), (11.5, 1.4), (16.0, 0.8), (18.0, 0.9), (20.0, 1.0)]
MM_YAW = 10.0
MM_HEADING = 0.30                               # the shaft comes in from the right: its sideways part


def _stroke(keys, t):
    return K(keys, t % 20.0, 20.0)


def mm_blade(t):
    p = np.asarray(KV(MM_STROKE, t % 20.0, 20.0), float)
    lateral = 1.0 * math.cos(2 * math.pi * (t - 8.0) / MM_LEN)          # the strokes a little apart, the second to the left
    return p + np.array([lateral, 0.0, 0.0])


def mm_shaft(t):
    a = math.radians(_stroke(MM_SLOPE, t))
    return unit(np.array([MM_HEADING, math.sin(a), -math.cos(a)]))


def mix_mortar():
    c = la.clip('mix_mortar', MM_LEN, True, look=('citizen', 6), tools_=('mortar_hoe',), station_='mortar_tub',
                events=[('stir', 3.0), ('stir', 23.0)], traces=[('mortar_hoe', MM_BLADE, (230, 220, 190))],
                views=((40, -14, True), (90, -4), (-50, -10)))
    times = la.every(MM_LEN)

    def step(t, guess):
        res = tool_contacts('mortar_hoe', MM_BLADE, mm_blade(t), shaft=mm_shaft(t), left_p=MM_LEFT, w_left=4.0)
        fr, qa, qb, x = solve_standing(res, MM_FEET, _stroke(MM_PITCH, t), MM_YAW, (0.0, MM_BACK + _stroke(MM_ROOT_Z, t)),
                                       dip=_stroke(MM_DROP, t), guess=guess, pitch_w=0.25, yaw_w=0.2, sink_w=2.0, root_w=1.5)
        return (fr, qa, qb), x
    sols = loop_solve(times, step)
    for t in times:
        fr, qa, qb = sols[t]
        key_frame(c, t, fr, ease='linear')
        c.key('right_arm', t, qa, ease='linear')
        c.key('left_arm', t, qb, ease='linear')
        stand(c, t, fr, feet=MM_FEET)
        look = mm_blade(t - 2.0) + np.array([0.0, -2.0, 1.5])         # the eyes on the mortar a beat behind the blade
        c.key('head', t, qslerp(I, head_at(fr, look), 0.7), ease='linear')
    c.mode('head', 'set')
    seal(c)
    for k in (0.0, 7.5, 12.0, 27.5):
        check(c, f'blade in the mortar (t={k:g})', ta.fk(c, k, 'mortar_hoe', MM_BLADE), mm_blade(k), tol=0.6)
        check(c, f'left fist on the shaft (t={k:g})', ta.fk(c, k, 'left_arm', GRIP['left_arm']),
              ta.fk(c, k, 'mortar_hoe', MM_LEFT), tol=0.8)
    feet_down(c, (0.0, 7.5, 16.0), MM_FEET)
    return c

# ---- trowel (40, loop): laying a course along a wall a block ahead (its top 16 px up), the mortar on a hawk in the left
# hand, standing back from the wall (TR_BACK) to have the room to work: the trowel's blade slid flat across the hawk under
# the heap's edge and lifted with a flick (scoop); over to the wall, the loaded blade laid on the course's right end and
# drawn along it to the left, wagging to furrow it (spread); two pats of its flat to settle the bed (tap); the blade tipped
# and scraped back along the course's near edge to take off what squeezes out (cut); and back to the hawk, which comes
# in to meet it. The body sways from the hawk to the wall and back, leaning into the spread.
TR_LEN = 40
TR_BACK = 3.5                           # the stance this far back of the block's middle
TR_WALL_TOP = 8.0                       # the wall's top (clip y) and its near face (z): a full block a block ahead
TR_WALL_FACE = -8.0
TR_BLADE = (0.0, 4.6, 2.3)              # trowel space: the middle of the blade's underside
TR_FEET = {'right_leg': (-2.6, TR_BACK + 1.2), 'left_leg': (2.6, TR_BACK - 1.0)}
TR_BED_Z = -11.4                        # the course's middle line, and its near edge
TR_EDGE_Z = -9.2
_bed = TR_WALL_TOP - 0.45
_tip = unit(D + 0.8 * F)                # the blade tipped toward the wall to cut along the joint
_across = unit(L + 0.8 * F)             # the point's way reaching over to the hawk
# (t, 'h': on the hawk (the blade's middle from the hawk board's top, world axes) or 'w': in the world, point, the
# blade's normal (trowel +z), its point's way (trowel +y))
TR_KEYS = [(0.0, 'h', (-3.6, -1.4, 0.4), D, _across),
           (2.0, 'h', (-3.2, -0.15, 0.2), D, _across),
           (4.0, 'h', (-0.9, -0.15, 0.0), D, _across),
           (5.5, 'h', (-1.6, -2.8, 0.6), unit(D + 0.5 * B), _across),
           (7.5, 'w', (-3.0, 3.6, -8.6), D, F),
           (10.5, 'w', (-5.2, _bed, TR_BED_Z), D, F),
           (17.5, 'w', (3.2, _bed, TR_BED_Z), D, unit(F + 0.4 * L)),
           (19.0, 'w', (2.4, 5.6, TR_BED_Z), D, F),
           (20.5, 'w', (0.8, _bed - 0.2, TR_BED_Z - 0.2), D, F),
           (22.0, 'w', (-0.2, 5.4, TR_BED_Z), D, F),
           (23.5, 'w', (-1.8, _bed - 0.2, TR_BED_Z), D, F),
           (25.5, 'w', (2.6, 5.4, TR_EDGE_Z - 0.4), _tip, unit(F + 0.3 * R)),
           (27.0, 'w', (3.6, _bed + 0.4, TR_EDGE_Z), _tip, unit(F + 0.3 * R)),
           (30.5, 'w', (-4.6, _bed + 0.4, TR_EDGE_Z), _tip, unit(F + 0.3 * R)),
           (32.5, 'w', (-3.6, 4.8, -8.4), D, F),
           (35.5, 'w', (0.6, 1.8, -6.6), D, _across),
           (37.5, 'h', (-2.8, -1.0, 0.8), unit(D + 0.3 * B), _across),
           (40.0, 'h', (-3.6, -1.4, 0.4), D, _across)]
TR_TAPS = (20.5, 23.5)
TR_CONTACTS = (2.0, 4.0, 10.5, 17.5, 27.0, 30.5) + TR_TAPS
# the hawk's board's top (world), and the body's lean, turn, step (x, z from TR_BACK) and knees: all soft, the solve
# finds the pose that puts the trowel where it goes
TR_HAWK = [(0.0, (7.6, 5.2, -5.2)), (4.0, (7.2, 5.0, -5.6)), (8.0, (8.8, 5.6, -4.0)), (12.0, (9.4, 5.8, -3.6)),
           (31.0, (9.4, 5.8, -3.6)), (35.5, (8.4, 5.4, -4.6)), (40.0, (7.6, 5.2, -5.2))]
TR_PITCH = [(0.0, 4.0), (4.0, 5.0), (7.5, 6.0), (10.5, 9.0), (17.5, 11.0), (20.5, 12.0), (23.5, 12.0), (27.0, 10.0),
            (30.5, 10.0), (33.0, 8.0), (37.5, 5.0), (40.0, 4.0)]
TR_YAW = [(0.0, 12.0), (4.0, 14.0), (7.5, 6.0), (10.5, 8.0), (17.5, -12.0), (20.5, -6.0), (23.5, -2.0), (27.0, -10.0),
          (30.5, 8.0), (33.0, 10.0), (37.5, 12.0), (40.0, 12.0)]
TR_ROOT = [(0.0, (0.2, 0.3)), (10.5, (-0.4, -0.6)), (17.5, (0.6, -0.8)), (23.5, (0.0, -0.8)), (30.5, (-0.5, -0.6)),
           (37.5, (0.2, 0.3)), (40.0, (0.2, 0.3))]
TR_DIP = [(0.0, 0.6), (11.0, 1.0), (18.0, 1.2), (24.0, 1.0), (31.0, 1.0), (37.5, 0.6), (40.0, 0.6)]


def _tool_R(n, d):
    """A tool's rotation in the world from its +z ([n]) and its +y ([d]) there."""
    y = unit(d)
    z = unit(np.asarray(n, float) - y * float(np.dot(n, y)))
    return np.column_stack([np.cross(y, z), y, z])


def tr_hawk(t):
    return np.asarray(KV(TR_HAWK, t, TR_LEN), float)


def tr_on_hawk(t):
    """How much the blade is on the hawk at [t] (1: its place is the hawk's, 0: the world's)."""
    t = t % TR_LEN
    if t <= 5.5 or t >= 37.5:
        return 1.0
    if t < 7.5:
        return 1.0 - smooth((t - 5.5) / 2.0)
    if t > 35.5:
        return smooth((t - 35.5) / 2.0)
    return 0.0


def _hawk_rel(t):
    """The blade's place on the hawk at [t] (only while tr_on_hawk), from its keys taken round the loop's seam."""
    ks = [(k, np.asarray(at, float)) for k, fr_, at, _, _ in TR_KEYS if fr_ == 'h']
    ks = [(k - TR_LEN, a) for k, a in ks if k >= 30.0] + [(k, a) for k, a in ks if k < 30.0]
    tt_ = t if t < 30.0 else t - TR_LEN
    if tt_ <= ks[0][0]:
        return ks[0][1]
    for (k0, a0), (k1, a1) in zip(ks, ks[1:]):
        if k0 <= tt_ <= k1:
            return a0 + (a1 - a0) * smooth((tt_ - k0) / max(1e-6, k1 - k0))
    return ks[-1][1]


def tr_target(t):
    """Where the trowel's blade (TR_BLADE) is wanted at [t] (in the world, the hawk taken where it is keyed to be), its
    place on the hawk (from the board's top), its normal and its point's way: the points splined round the loop, the
    turns eased from key to key."""
    rows = [(k, np.asarray(at, float) + (tr_hawk(k) if fr_ == 'h' else 0.0), qfrommat(_tool_R(n, d)))
            for k, fr_, at, n, d in TR_KEYS]
    p = KV([(k, tuple(a)) for k, a, _ in rows], t, TR_LEN)
    tt_ = t % TR_LEN
    rel = _hawk_rel(tt_)
    for (k0, _, q0), (k1, _, q1) in zip(rows, rows[1:]):
        if k0 <= tt_ <= k1:
            if float(np.dot(q0, q1)) < 0:
                q1 = -q1
            Rt = qmat(qslerp(q0, q1, smooth((tt_ - k0) / max(1e-6, k1 - k0))))
            return p, rel, Rt[:, 2], Rt[:, 1]
    Rt = qmat(rows[0][2])
    return p, rel, Rt[:, 2], Rt[:, 1]


def tr_contacts(t):
    p, rel, n, d = tr_target(t)
    p = p + np.array([0.0, 0.0, 0.5 * wave(t, 11.0, 17.5, 2.2)])        # the furrow: the blade wags across the bed
    w_h = tr_on_hawk(t)
    hk = tr_hawk(t)
    blade_p, n_p, d_p = tool_point('trowel', TR_BLADE), tool_dir('trowel', (0, 0, 1)), tool_dir('trowel', (0, 1, 0))
    top_p, up_p = tool_point('hawk', lp.HAWK_TOP), tool_dir('hawk', (0, -1, 0))

    def res(fr, qa, qb):
        Ra, ja = arm_world('right_arm', qa, **fr)
        Rb, jb = arm_world('left_arm', qb, **fr)
        top = jb + Rb @ top_p
        want = p * (1.0 - w_h) + (top + rel) * w_h
        out = list((ja + Ra @ blade_p - want) * 5.0)
        out += list((Ra @ n_p - n) * 2.0) + list((Ra @ d_p - d) * 0.2)
        out += list((top - hk) * 1.0) + list((Rb @ up_p - U) * 2.5)
        out += [clear_of_body(fr, ja + Ra @ GRIP['right_arm']), clear_of_body(fr, jb + Rb @ GRIP['left_arm'])]
        return out
    return res


def _wall():
    return [bx((-20, TR_WALL_TOP, TR_WALL_FACE - 16), (20, 24, TR_WALL_FACE), STONE),
            bx((-7, TR_WALL_TOP - 0.5, TR_BED_Z - 2.4), (5, TR_WALL_TOP, TR_EDGE_Z), MORTAR_C)]


def trowel():
    c = la.clip('trowel', TR_LEN, True, look=('citizen', 2), tools_=('trowel', 'hawk'), station_='wall_course',
                events=[('scoop', 4.0), ('spread', 11.0)] + [('tap', k) for k in TR_TAPS] + [('cut', 28.0)],
                traces=[('trowel', TR_BLADE, (230, 220, 190))], views=((40, -14, True), (90, -4), (-40, -20)))
    times = la.every(TR_LEN)

    def step(t, guess):
        rx, rz = KV([(k, (a, b, 0.0)) for k, (a, b) in TR_ROOT], t, TR_LEN)[:2]
        fr, qa, qb, x = solve_standing(tr_contacts(t), TR_FEET, K(TR_PITCH, t, TR_LEN), K(TR_YAW, t, TR_LEN),
                                       (rx, TR_BACK + rz), dip=K(TR_DIP, t, TR_LEN), guess=guess, pitch_w=0.25,
                                       yaw_w=0.15, sink_w=2.0, root_w=1.5)
        return (fr, qa, qb), x
    sols = loop_solve(times, step)
    for t in times:
        fr, qa, qb = sols[t]
        key_frame(c, t, fr, ease='linear')
        c.key('right_arm', t, qa, ease='linear')
        c.key('left_arm', t, qb, ease='linear')
        stand(c, t, fr, feet=TR_FEET)
        p, rel, n, d = tr_target(t - 1.5)
        w = tr_on_hawk(t - 1.5)
        look = p * (1.0 - w) + (tr_hawk(t - 1.5) + rel) * w
        c.key('head', t, qslerp(I, head_at(fr, look), 0.75), ease='linear')
    c.mode('head', 'set')
    seal(c)
    for k in TR_CONTACTS:
        p, rel, n, d = tr_target(k)
        if tr_on_hawk(k) > 0.99:
            p = ta.fk(c, k, 'hawk', lp.HAWK_TOP) + rel
        check(c, f'blade on the hawk / the course (t={k:g})', ta.fk(c, k, 'trowel', TR_BLADE), p, tol=0.7)
    for k in (4.0, 20.5):             # (between, it tips to the trowel coming back to it)
        up = ta.fk(c, k, 'hawk', (0.0, -1.0, 0.0)) - ta.fk(c, k, 'hawk', (0.0, 0.0, 0.0))
        check(c, f'hawk level (t={k:g}: degrees off)', (math.degrees(math.acos(min(1.0, float(-up[1])))),), (0.0,), tol=10.0)
    for k in (4.0, 10.5, 17.5, 20.5):
        n = ta.fk(c, k, 'trowel', (0.0, 0.0, 1.0)) - ta.fk(c, k, 'trowel', (0.0, 0.0, 0.0))
        check(c, f'blade flat (t={k:g}: degrees off)', (math.degrees(math.acos(min(1.0, float(n[1])))),), (0.0,), tol=15.0)
    feet_down(c, (0.0, 14.0, 28.0), TR_FEET)
    return c

# ---- hoist (40, loop): hauling hand over hand on the hoist's rope, hanging plumb a little ahead from the pulley up on
# the scaffold: four pulls, right, left, right, left, each a heave down from as high as the hand reaches to the chest as
# the body sits back into it and the knees give (heave), the other hand sliding up the rope meanwhile to take the next
# hold. The rope is drawn from where the block's own leaves off (HOIST_ROPE) down through the fists, its tail to a
# coil on the ground.
HO_LEN = 40
HO_TOP = wa.HOIST_ROPE
HO_HIGH = np.array([0.0, -4.6, -5.6])
HO_LOW = np.array([0.0, 6.6, -4.2])
HO_COIL = np.array([3.4, 23.6, -4.0])
HO_FEET = {'right_leg': (-2.4, 2.6), 'left_leg': (2.4, -2.2)}
HO_SIDE = {'right_arm': -0.55, 'left_arm': 0.55}
HO_HEAVES = (0.5, 10.5, 20.5, 30.5)


def ho_phase(side, t):
    """Where [side]'s hand is in its 20-tick round: ('grab'|'pull'|'hold'|'slide', 0..1 through it)."""
    ph = (t - (0.0 if side == 'right_arm' else 10.0)) % 20.0
    if ph < 0.5:
        return 'grab', 1.0
    if ph < 6.0:
        return 'pull', (ph - 0.5) / 5.5
    if ph < 10.5:
        return 'hold', (ph - 6.0) / 4.5
    if ph < 17.5:
        return 'slide', (ph - 10.5) / 7.0
    return 'grab', (ph - 17.5) / 2.5


def _pull_ease(u):
    """A heave: quick off the mark, slowing to the chest."""
    return 1.0 - (1.0 - u) ** 2.2


def ho_hand(side, t):
    """Where [side]'s fist is at [t], and whether it holds the rope."""
    what, u = ho_phase(side, t)
    sx = np.array([HO_SIDE[side], 0.0, 0.0])
    if what == 'pull':
        return HO_HIGH + (HO_LOW - HO_HIGH) * _pull_ease(u) + sx, True
    if what == 'hold':
        return HO_LOW + sx + np.array([0.0, 0.4 * smooth(u), 0.2 * smooth(u)]), True
    if what == 'grab':
        return HO_HIGH + sx + np.array([0.0, -0.5 * (1.0 - u), -0.3 * (1.0 - u)]), u > 0.6
    # sliding up the rope, a hand's width off it
    k = smooth(u)
    p = (HO_LOW + np.array([0.0, 0.4, 0.2])) + (HO_HIGH - HO_LOW - np.array([0.0, 0.4, 0.2])) * k
    off = np.array([HO_SIDE[side] * 2.4, 0.0, -0.8]) * math.sin(math.pi * u)
    return p + sx + off, False


def ho_holders(t):
    return [s for s in ('right_arm', 'left_arm') if ho_hand(s, t)[1]]


def ho_rope_points(t):
    """The rope's top, the holding fists in order from the top, and the coil."""
    held = sorted(ho_holders(t), key=lambda s: ho_hand(s, t)[0][1])
    return [HO_TOP] + [ho_hand(s, t)[0] for s in held] + [HO_COIL], held


def ho_hauled(t):
    """How far the rope has been hauled down at [t] since the loop's start (px)."""
    total = 0.0
    k = 0.0
    step = 0.25
    while k < t - 1e-9:
        for s in ('right_arm', 'left_arm'):
            what, u = ho_phase(s, k)
            if what == 'pull':
                a = _pull_ease(u)
                b = _pull_ease(min(1.0, u + step / 5.5))
                total += (b - a) * float(HO_LOW[1] - HO_HIGH[1])
        k += step
    return total


HO_PITCH = [(0.0, -7.0), (2.0, -13.0), (5.5, -10.0), (8.0, -7.0), (10.0, -7.0)]
HO_ROOTZ = [(0.0, 0.6), (2.0, 1.6), (5.5, 1.2), (8.0, 0.6), (10.0, 0.6)]
HO_DIP = [(0.0, 0.5), (2.0, 1.6), (5.5, 1.1), (8.0, 0.5), (10.0, 0.5)]


def hoist():
    c = la.clip('hoist', HO_LEN, True, look=('citizen', 4), tools_=('well_rope', 'curb_rope', 'haul_rope_right'),
                station_='hoist_rope', events=[('heave', k) for k in HO_HEAVES],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120)), ('left_arm', GRIP['left_arm'], (200, 230, 255))],
                views=((40, -10), (90, -2), (0, -20)))
    holds = la.Holds()
    times = la.every(HO_LEN)
    for lap in range(2):
        for t in times:
            u = t % 10.0
            pitch = K(HO_PITCH, u, 10.0)
            yaw = 4.0 * math.sin(2 * math.pi * (t - 3.0) / 20.0)
            fr = F_(qt=bend(pitch, yaw, -1.5 * math.sin(2 * math.pi * (t - 3.0) / 20.0)), vt=(0.0, sink_for(pitch), 0.0),
                    pr=la.root_for(HO_FEET, x=0.0, z=K(HO_ROOTZ, u, 10.0), dip=K(HO_DIP, u, 10.0)))
            poses = {}
            for side in ('right_arm', 'left_arm'):
                p, held = ho_hand(side, t)
                palm = unit(F + (L if side == 'right_arm' else R) * 0.6)
                poses[side] = reach_arm(holds, side, fr, p, palm=palm, palm_w=0.3, limit=1.8)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr, feet=HO_FEET)
                for side, pose in poses.items():
                    set_arm(c, side, t, pose)
                up = min(ho_hand('right_arm', t)[0][1], ho_hand('left_arm', t)[0][1])
                look = np.array([0.0, min(2.0, up - 4.0), -12.0])
                c.key('head', t, qslerp(I, head_at(fr, look), 0.6), ease='linear')
    c.mode('head', 'set')
    seal(c)
    la.TRACKS['hoist'] = {'well_rope': _ho_top_track, 'curb_rope': _ho_tail_track, 'haul_rope_right': _ho_between_track}
    ta.INFO['hoist']['tools_at'] = lambda t: {'well_rope', 'curb_rope'} | ({'haul_rope_right'} if len(ho_holders(t)) == 2 else set())
    for k in HO_HEAVES:
        for side in ('right_arm', 'left_arm'):
            p, held = ho_hand(side, k)
            check(c, f'{side.replace("_arm", "")} fist on the rope (t={k:g})', ta.fk(c, k, side, GRIP[side]), p, tol=0.7)
    feet_down(c, (0.0, 2.0, 22.0), HO_FEET)
    return c


def _ho_top_track(t, pivot):
    pts, held = ho_rope_points(t)
    R_, S = la.aim(pts[0], pts[1], lp.ROPE_LEN)
    return R_, pts[0], S


def _ho_tail_track(t, pivot):
    pts, held = ho_rope_points(t)
    R_, S = la.aim(pts[-2], pts[-1], lp.ROPE_LEN)
    return R_, pts[-2], S


def _ho_between_track(t, pivot):
    pts, held = ho_rope_points(t)
    if len(held) < 2:
        return np.eye(3), None, (1.0, 1e-3, 1.0)
    other = ho_hand('left_arm', t)[0]
    R_, S = la.aim(pivot, other, lp.ROPE_LEN)
    return R_, None, S


def _hoist_rope():
    x, y, z = HO_TOP
    return [bx((x - 0.35, y - 20, z - 0.35), (x + 0.35, y, z + 0.35), (180, 160, 120)),
            bx((x - 2.0, y - 24, z - 2.0), (x + 2.0, y - 20, z + 2.0), WOOD_D),
            bx(HO_COIL - np.array([2.2, 0.8, 2.2]), HO_COIL + np.array([2.2, 0.4, 2.2]), (180, 160, 120))]

# ---- carry_beam (40, loop, an overlay played walking): a beam on the right shoulder, two to a beam in step, the
# second BEAM_FOLLOW px behind the first: each carrier shows the half of it from BEAM_AHEAD px ahead of their shoulder to
# BEAM_BEHIND px behind, so the two halves meet between them (and one carrier alone carries a short beam). The right hand
# steadies it, reaching up ahead onto it, a hitch of the grip once round; the left arm keeps the walk's swing; the head
# tips away from the beam and glances back at the other carrier once.
CB_LEN = 40
CB_ON = np.array([-6.8, -3.4, -7.6])            # torso space: the right hand on the beam's top, ahead of the shoulder


def carry_beam():
    c = la.clip('carry_beam', CB_LEN, True, look=('citizen', 5), tools_=('beam',), overlay=True,
                events=[('hitch', 22.0)], views=((32, -10), (90, -2), (150, -14)))
    holds = la.Holds()
    times = la.every(CB_LEN)
    for lap in range(2):
        for t in times:
            hitch = bump(t, 19.0, 25.0)
            on = CB_ON + np.array([0.0, -1.2 * hitch, 0.9 * math.sin(math.pi * min(1.0, max(0.0, (t - 19.0) / 6.0)))])
            pose = reach_arm(holds, 'right_arm', REST, on, palm=D, palm_w=0.35, limit=1.4)
            if lap:
                set_arm(c, 'right_arm', t, pose)
                back = bump(t, 28.0, 37.0)
                c.key('head', t, qeuler(x=-3.0 + 2.0 * math.sin(2 * math.pi * t / 20.0), y=-34.0 * back,
                                        z=6.0 - 2.0 * back), ease='linear')
    seal(c)
    # the previews draw the other carrier, BEAM_FOLLOW px behind in step under the other half
    ta.INFO['carry_beam']['partner'] = ('carry_beam', ('citizen', 3))
    PARTNER_PLACE['carry_beam'] = (T(0.0, 0.0, lp.BEAM_FOLLOW), 0.0)
    ta.INFO['carry_beam']['pan'] = np.array([0.0, 0.0, lp.BEAM_FOLLOW / 2])
    for k in (0.0, 22.0):
        check(c, f'right hand on the beam (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm'])[1] <= -2.4, True, tol=0.1)
    return c

# ---- chisel (30, loop): dressing a stone on the banker a block ahead (a full block: its top 16 px up): the chisel in the
# left fist stood on its edge on the stone's near top edge, leaning back to the mason; three blows of the mallet (strike),
# each a lift of the right arm and a quick, heavy fall onto the chisel's head with a little rebound, the chisel walked a
# step along the edge between blows and back at the end of the round; a nod of the head with each blow.
CH_LEN = 30
CH_STRIKES = (6.0, 16.0, 26.0)
CH_X = [(0.0, -1.2), (2.0, -1.2), (4.0, 1.2), (12.0, 1.2), (14.0, 0.0), (22.0, 0.0), (24.0, -1.2), (30.0, -1.2)]
CH_TOP = 8.0
CH_FACE = -8.0
CH_EDGE_AT = (CH_TOP - 0.15, CH_FACE - 1.0)    # where the chisel's edge bites: (y, z)
CH_AXIS = unit(np.array([0.0, math.cos(math.radians(22.0)), -math.sin(math.radians(22.0))]))   # chisel +y: down, away
CH_HANDLE = unit(np.array([0.72, 0.22, -0.66]))     # the mallet's handle at the blow: in from the right, ahead
CH_FEET = {'right_leg': (-2.8, 3.6), 'left_leg': (2.2, 0.0)}
CH_RAISE = 48.0                                     # degrees the mallet arm lifts


def ch_edge(t):
    x = K(CH_X, t)
    return np.array([x, CH_EDGE_AT[0], CH_EDGE_AT[1]])


def ch_swing(t):
    """How far the mallet is lifted (0 on the chisel, 1 up) at [t]: a lift, a quick fall onto the blow, a rebound."""
    ph = (t - CH_STRIKES[0]) % 10.0             # 0 is a blow
    if ph < 1.2:
        return 0.28 * math.sin(math.pi * ph / 2.4)             # the rebound
    if ph < 3.5:
        return 0.28 + (0.12 - 0.28) * smooth((ph - 1.2) / 2.3)
    if ph < 8.6:
        return 0.12 + 0.88 * smooth((ph - 3.5) / 5.1)
    u = (ph - 8.6) / 1.4                                        # the fall: faster and faster
    return 1.0 - u * u


def turn_arm(fr, q, axis, deg):
    """A 'set' arm key [q] under [fr] turned [deg] about world [axis] through its shoulder."""
    qrt = qmul(fr['qr'], fr['qt'])
    return qmul(qinv(qrt), qmul(qaxis(unit(axis), deg), qmul(qrt, q)))


def ch_contacts(edge_at):
    """The blow's contacts: the chisel's edge on the stone at [edge_at], leaning back as CH_AXIS, the mallet's head on
    the chisel's, its handle in from the right."""
    edge_p, axis_p = tool_point('chisel', lp.CHISEL_EDGE), tool_dir('chisel', (0, 1, 0))
    head_p = tool_point('chisel', lp.CHISEL_HEAD)
    mh_p, mh_d = tool_point('mallet', lp.MALLET_HEAD), tool_dir('mallet', (0, 1, 0))

    def res(fr, qa, qb):
        Rb, jb = arm_world('left_arm', qb, **fr)
        ax = Rb @ axis_p
        out = list((jb + Rb @ edge_p - edge_at) * 4.0) + list((ax - CH_AXIS) * 2.0)
        Ra, ja = arm_world('right_arm', qa, **fr)
        out += list((ja + Ra @ mh_p - (jb + Rb @ head_p - ax * 1.85)) * 4.0)
        out += list((Ra @ mh_d - CH_HANDLE) * 0.8)
        out += [clear_of_body(fr, ja + Ra @ GRIP['right_arm']), clear_of_body(fr, jb + Rb @ GRIP['left_arm'])]
        return out
    return res


def chisel():
    c = la.clip('chisel', CH_LEN, True, look=('citizen', 1), tools_=('mallet', 'chisel'), station_='banker',
                events=[('strike', k) for k in CH_STRIKES],
                traces=[('mallet', lp.MALLET_HEAD, (230, 200, 160)), ('chisel', lp.CHISEL_EDGE, (200, 200, 220))],
                views=((-40, -14, True), (90, -4), (-90, -6)))
    times = la.every(CH_LEN)

    def step(t, guess):
        blow = sum(bump(t, k - 0.2, k + 3.0, CH_LEN) for k in CH_STRIKES)
        fr, qa, qb, x = solve_standing(ch_contacts(ch_edge(t)), CH_FEET, 12.0 + 3.0 * blow, 16.0, (0.0, 1.6),
                                       dip=0.5 + 0.5 * blow, guess=guess, pitch_w=0.3, yaw_w=0.15, sink_w=2.0, root_w=1.0)
        return (fr, qa, qb), x
    sols = loop_solve(times, step)
    for t in times:
        fr, on, left = sols[t]
        axis = unit(np.cross(CH_HANDLE, U))
        q_up = turn_arm(fr, on, axis, CH_RAISE)
        if grip_of('right_arm', (q_up, np.zeros(3)), fr)[1] > grip_of('right_arm', (on, np.zeros(3)), fr)[1]:
            q_up = turn_arm(fr, on, axis, -CH_RAISE)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=CH_FEET)
        c.key('right_arm', t, qslerp(on, q_up, ch_swing(t)), ease='linear')
        c.key('left_arm', t, left, ease='linear')
        c.key('head', t, qslerp(I, head_at(fr, ch_edge(t) + np.array([0.0, -1.0, 0.0])), 0.8), ease='linear')
    c.mode('head', 'set')
    seal(c)
    for k in CH_STRIKES:
        check(c, f'chisel edge on the stone (t={k:g})', ta.fk(c, k, 'chisel', lp.CHISEL_EDGE), ch_edge(k), tol=0.6)
        gap = float(np.linalg.norm(ta.fk(c, k, 'mallet', lp.MALLET_HEAD) - ta.fk(c, k, 'chisel', lp.CHISEL_HEAD)))
        check(c, f'mallet on the chisel\'s head (t={k:g}, px apart)', (gap,), (1.85,), tol=0.6)
    feet_down(c, (0.0, 16.0), CH_FEET)
    return c


def _banker():
    return [bx((-7, CH_TOP, CH_FACE - 14), (7, 24, CH_FACE), STONE), bx((-7, CH_TOP + 7, CH_FACE - 15), (7, 24, CH_FACE + 0.5), STONE_D)]

# ---- plumb (60): holding a plumb line up against a wall a block ahead: the right hand comes up to the eye with the line
# pinched in the fingertips, the bob swinging from the lift and settling (the line's own pendulum, run through the
# clip); the head leans in and tips to squint down it, the left hand flat on the wall; a touch to the line to still it,
# a long look, a satisfied nod (true), and the hand comes down, the bob swinging again.
PL_LEN = 60
PL_AT = np.array([-1.4, -3.0, -6.2])                # the fingers holding the line, before the eye near the wall
PL_LEFT_ON = np.array([4.0, 4.2, -8.4])             # the left palm on the wall
PL_UP = [(0, 0.0), (2.0, 0.0), (8.5, 1.0), (47.0, 1.0), (54.0, 0.0), (60, 0.0)]
PL_LEFT = [(0, 0.0), (6.0, 0.0), (11.0, 1.0), (44.0, 1.0), (50.0, 0.0), (60, 0.0)]
PL_P = [(0, 0.0), (6.0, 3.0), (12.0, 7.0), (40.0, 7.0), (42.5, 9.0), (45.0, 6.0), (52.0, 1.0), (60, 0.0)]
PL_ROLL = [(0, 0.0), (8.0, 0.0), (13.0, 1.0), (39.0, 1.0), (43.0, 0.0), (60, 0.0)]
PL_NOD = [(0, 0.0), (40.0, 0.0), (41.5, 9.0), (43.0, -1.0), (44.5, 7.0), (46.5, 0.0), (60, 0.0)]
PL_STILL = 27.0                                     # the left fingers touch the line to still it
PL_GRAVITY = 0.45 / 64.0                            # px a sub-step squared: a 14 px line swings about once in 35 ticks
PL_WALL = -8.0                                      # the wall's face (clip z)


def plumb():
    c = la.clip('plumb', PL_LEN, False, look=('noble', 3), tools_=('plumb_line',), station_='wall',
                events=[('true', 42.0)], traces=[('plumb_line', (0.0, lp.PLUMB_LEN, 0.0), (230, 200, 120))],
                views=((40, -10, True), (90, -2), (-30, -6)))
    holds = la.Holds()
    times = la.every(PL_LEN)
    piv = np.asarray(lp.PLUMB_PIVOT, float)
    for t in times:
        pitch = K(PL_P, t)
        fr = F_(qt=bend(pitch, -4.0 * smooth(K(PL_ROLL, t)), 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(FEET, z=-0.6 * smooth(K(PL_UP, t))))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        w = smooth(K(PL_UP, t))
        target = PL_AT + np.array([0.15 * math.sin(t * 1.7), 0.1 * math.sin(t * 2.3), 0.0]) * bump(t, 9.0, 46.0)
        target = target + np.array([0.7, 0.0, 0.0]) * smooth((t - PL_STILL + 2.0) / 3.0) * (1.0 - smooth((t - 46.0) / 4.0))
        pose = mix(rest_arm('right_arm', fr), reach_arm(holds, 'right_arm', fr, target, p=piv, palm=unit(F + L), palm_w=0.2), w)
        set_arm(c, 'right_arm', t, pose)
        wl = smooth(K(PL_LEFT, t))
        lp_ = mix(rest_arm('left_arm', fr), reach_arm(holds, 'left_arm', fr, PL_LEFT_ON, palm=F, palm_w=0.5), wl)
        set_arm(c, 'left_arm', t, lp_)
        roll = smooth(K(PL_ROLL, t))
        c.key('head', t, qeuler(x=12.0 * roll + K(PL_NOD, t), y=-6.0 * roll, z=-11.0 * roll), ease='linear')
    c.mode('head', 'add')
    # the line's pendulum, run through the clip from the fingertips' path
    anchor = lambda t: apply(part_M(c, t, 'right_arm'), piv)
    a0 = anchor(0.0)
    damp = lambda t: 0.012 + 0.07 * bump(t, PL_STILL - 1.0, PL_STILL + 4.0)
    sim = Chain(anchor, [a0, a0 + np.array([0.0, lp.PLUMB_LEN, 0.0])], gravity=PL_GRAVITY, damp=damp)
    sim.run(0.0, PL_LEN, planes=[((0.0, 0.0, PL_WALL + 0.6), (0.0, 0.0, 1.0)), ((0.0, 23.6, 0.0), (0.0, -1.0, 0.0))])
    PL_SIM['sim'] = sim
    la.TRACKS['plumb'] = {'plumb_line': _plumb_track}
    for k in (20.0, 35.0):
        bob = ta.fk(c, k, 'plumb_line', (0.0, lp.PLUMB_LEN, 0.0))
        check(c, f'the bob hangs by the wall (t={k:g}: px off the face)', (bob[2] - PL_WALL,), (1.6,), tol=1.2)
    joins_idle(c)
    return c


PL_SIM = {}


def _plumb_track(t, pivot):
    a, b = PL_SIM['sim'].at(t)
    return frame_of(b - a, B), None, None


def _wall_tall():
    return [bx((-20, -24, -24), (20, 24, PL_WALL), PLASTER)]

# ---- survey (60): the master sighting a line with the ranging staff: it stands at his left side in his left hand; he
# lifts it and plants it ahead and to the left (plant), leans to it and sights past it along the line to a far stake,
# squinting; then waves his helper over to the right with a sweep of the arm, twice (wave), a "there!" with the hand flat
# (there), straightens and takes the staff back to his side.
SV_LEN = 60
SV_GRIP = np.array([1.0, 10.0, -2.6])               # left arm space: the hand's hold on the staff (the part's pivot)
SV_PLANT = np.array([6.4, 24.0, -10.0])             # the staff's foot planted ahead and to the left
SV_FAR = np.array([16.0, 4.0, -80.0])               # where the line runs: the far stake
SV_FOOT = [(0.0, 0.0), (3.0, 0.0), (10.0, 1.0), (44.0, 1.0), (51.0, 0.0), (60.0, 0.0)]     # side (0) to planted (1)
SV_HAND_UP = [(0.0, 0.0), (3.0, 0.0), (10.0, 6.4), (14.0, 7.4), (40.0, 7.4), (44.0, 6.4), (51.0, 0.0), (60.0, 0.0)]
SV_LEAN = [(0.0, 0.0), (9.0, 0.0), (14.0, 1.0), (24.0, 1.0), (28.0, 0.35), (40.0, 0.35), (44.0, 0.0), (60.0, 0.0)]
SV_WAVES = (27.0, 32.0)
SV_THERE = 38.0
SV_RIGHT = [(0.0, 0.0), (23.0, 0.0), (25.5, 1.0), (40.5, 1.0), (45.0, 0.0), (60.0, 0.0)]


def sv_side_foot(c=None):
    pose = rest_arm('left_arm', REST)
    return grip_of('left_arm', pose, REST, p=SV_GRIP) + np.array([0.0, -lp.STAFF_FOOT[0], 0.0])


def sv_foot(t):
    """The staff's foot at [t]: at the left side, carried up and over, planted ahead."""
    side = sv_side_foot()
    w = K(SV_FOOT, t)
    p = side + (SV_PLANT - side) * w
    arc = 4.0 * math.sin(math.pi * w) if (3.0 < t < 10.0 or 44.0 < t < 51.0) else 0.0
    return p + np.array([0.0, -arc, 0.0])


def sv_staff_pivot(t):
    return sv_foot(t) + np.array([0.0, lp.STAFF_FOOT[0], 0.0])


def survey():
    c = la.clip('survey', SV_LEN, False, look=('merchant', 2), tools_=('sighting_staff',), station_='stakes',
                events=[('plant', 10.0)] + [('wave', k) for k in SV_WAVES] + [('there', SV_THERE)],
                traces=[('sighting_staff', lp.STAFF_FOOT, (230, 120, 120)), ('right_arm', GRIP['right_arm'], (255, 200, 120))],
                views=((32, -12), (90, -4), (-140, -10)))
    holds = la.Holds()
    for t in la.every(SV_LEN):
        lean = smooth(K(SV_LEAN, t))
        pitch = 10.0 * lean
        fr = F_(qt=bend(pitch, -16.0 * lean, 3.0 * lean), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(FEET, x=0.8 * lean, z=-0.6 * lean, dip=0.8 * lean))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        hold = sv_staff_pivot(t) + np.array([0.0, -K(SV_HAND_UP, t), 0.0])
        left = reach_arm(holds, 'left_arm', fr, hold, p=SV_GRIP, palm=unit(R + 0.3 * F), palm_w=0.2, limit=1.6)
        # at either end the hand hangs as idle's does, the staff in it (its place is that hand's hold)
        ends = max(1.0 - smooth(t / 3.0), smooth((t - 52.0) / 6.0))
        set_arm(c, 'left_arm', t, mix(left, rest_arm('left_arm', fr), ends))
        wr = smooth(K(SV_RIGHT, t))
        rest = rest_arm('right_arm', fr)
        sweep = sum(math.sin(math.pi * min(1.0, max(0.0, (t - k + 2.5) / 5.0))) for k in SV_WAVES) if 24.5 < t < 34.5 else 0.0
        d = v(f=0.25 + 0.55 * sweep, u=0.35, r=1.0 - 0.45 * sweep)
        out = point_arm(fr, d, unit(R + 0.3 * F), lift=0.6)
        if 34.0 < t:
            there = point_arm(fr, v(f=1.0, u=0.3 - 0.35 * smooth((t - SV_THERE + 1.0) / 1.0), r=-0.1), D, lift=0.4)
            out = mix(out, there, smooth((t - 34.0) / 2.5))
        set_arm(c, 'right_arm', t, mix(rest, out, wr))
        sight = smooth(K(SV_LEAN, t)) * (1.0 - wr * 0.7)
        q_sight = head_at(fr, SV_FAR, mode='add')
        q_look = qeuler(x=-6.0, y=55.0 * min(1.0, wr * 1.3), z=0.0)
        c.key('head', t, qslerp(qslerp(I, q_look, wr), q_sight, sight), ease='linear')
    c.mode('head', 'add')
    la.TRACKS['survey'] = {'sighting_staff': _staff_track}
    check(c, 'staff\'s foot planted (t=20)', ta.fk(c, 20.0, 'sighting_staff', lp.STAFF_FOOT), SV_PLANT, tol=0.4)
    check(c, 'left hand on the staff (t=20)', ta.fk(c, 20.0, 'left_arm', SV_GRIP)[[0, 2]], SV_PLANT[[0, 2]], tol=0.6)
    joins_idle(c)
    return c


def _staff_track(t, pivot):
    # the staff stands upright on its foot (staff x up): it rides in the hand only in that the hand holds it
    R_ = np.column_stack([np.array([0.0, -1.0, 0.0]), np.array([1.0, 0.0, 0.0]), np.array([0.0, 0.0, 1.0])])
    return R_, sv_staff_pivot(t), None


def _stakes():
    return [bx((SV_FAR[0] - 0.6, 14, SV_FAR[2] - 0.6), (SV_FAR[0] + 0.6, 24, SV_FAR[2] + 0.6), WOOD),
            bx((-0.6, 18, -40.6), (0.6, 24, -39.4), WOOD)]

# ================================================================== the ceremonies

# ---- unveil (40): the master under the patron's plaque (on the wall of the block above his, wrapped in its cloth: the
# block's plaque_covered): he reaches up to the cloth's foot and takes it, a breath with the hand lifting it a little,
# then whips it down and out to his right (pull: the game turns the block to the bare plaque on that tick) as he turns
# with it; the cloth flies off the bronze and swings from his fist as his arm sweeps wide; he presents the plaque with
# the left hand open to it (present), looking round at the crowd, bundles the cloth up to his chest and lets his arms
# fall.
UV_LEN = 40
UV_PULL = 9.0
UV_FOOT = np.array([0.0, 6.3 - 0.4, -6.9])      # where he takes the cloth: its foot's middle
UV_KEYS = [(0.0, None), (2.0, None), (6.5, UV_FOOT + np.array([0.0, 0.6, -0.2])), (8.0, UV_FOOT + np.array([0.0, -0.8, -0.1])),
           (UV_PULL, UV_FOOT + np.array([0.0, 0.4, 0.0])), (11.0, np.array([-5.0, 13.0, -4.0])),
           (13.0, np.array([-11.0, 6.0, 1.0])), (16.0, np.array([-12.5, 1.0, 1.5])), (24.0, np.array([-12.2, 1.6, 0.6])),
           (30.0, np.array([-6.0, 6.0, -5.0])), (33.0, np.array([-2.4, 7.4, -5.6]))]
UV_HOLD_TO = 35.0
UV_PRESENT = 17.0
UV_YAW = [(0, 0.0), (6.0, 0.0), (8.0, -3.0), (11.0, 16.0), (14.0, 24.0), (20.0, 14.0), (28.0, 10.0), (34.0, 2.0), (40, 0.0)]
UV_PITCH = [(0, 0.0), (6.0, -3.0), (8.0, -1.0), (10.5, 9.0), (13.0, 2.0), (17.0, -3.0), (24.0, -2.0), (30.0, 3.0),
            (34.0, 0.0), (40, 0.0)]
UV_LEFT = [(0, 0.0), (14.0, 0.0), (17.5, 1.0), (27.0, 1.0), (31.0, 0.0), (40, 0.0)]
UV_HEAD_Y = [(0, 0.0), (8.0, 0.0), (12.0, 10.0), (16.0, -20.0), (20.0, 30.0), (25.0, 32.0), (29.0, 6.0), (34.0, 0.0), (40, 0.0)]
UV_HEAD_X = [(0, 0.0), (2.0, -24.0), (8.0, -20.0), (11.0, 6.0), (16.0, -10.0), (20.0, -4.0), (30.0, 4.0), (34.0, 0.0), (40, 0.0)]
UV_ROWS, UV_COLS = lp.VEIL_PIECES + 1, 3
UV_ASIDE = -5.0                                 # he steps this far to his right (x) to show the plaque
UV_STEPS = [(10.0, 12.5, 'right_leg', (-1.9 + UV_ASIDE - 0.6, 0.6)), (12.5, 15.0, 'left_leg', (1.9 + UV_ASIDE, 0.2)),
            (31.0, 33.5, 'left_leg', (1.9, 0.0)), (33.5, 36.0, 'right_leg', (-1.9, 0.0))]
UV_X = [(0, 0.0), (9.5, 0.0), (11.0, 0.8), (12.5, UV_ASIDE * 0.55), (15.0, UV_ASIDE), (31.0, UV_ASIDE),
        (33.5, UV_ASIDE * 0.45), (36.0, 0.0), (40, 0.0)]


def uv_hand(t):
    """Where the right fist is wanted at [t] (None: at rest): after the pull it goes where he steps."""
    ks = [(k, p) for k, p in UV_KEYS if p is not None]
    if t <= 2.0:
        return None
    if t >= UV_KEYS[-1][0]:
        p = ks[-1][1]
    else:
        p = KV([(k, tuple(p)) for k, p in ks], max(t, ks[0][0]))
    return np.asarray(p, float) + np.array([K(UV_X, t) if t > UV_PULL else 0.0, 0.0, 0.0])


def uv_grid0():
    """The cloth's points flat on the plaque (rows from its top, columns from the right as he faces it)."""
    pts = []
    for r in range(UV_ROWS):
        for col in range(UV_COLS):
            x = -lp.VEIL_W / 2 + lp.VEIL_W * col / (UV_COLS - 1)
            pts.append(np.array([x, wa.VEIL_TOP + lp.VEIL_LEN * r, wa.VEIL_FRONT - 0.15]))
    return pts


def unveil():
    c = la.clip('unveil', UV_LEN, False, look=('merchant', 2), tools_=tuple(lp.VEILS), station_='plaque_wall',
                events=[('pull', UV_PULL), ('present', UV_PRESENT)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((160, -8), (90, -2), (215, -24)))
    holds = la.Holds()
    for t in la.every(UV_LEN):
        pitch = K(UV_PITCH, t)
        yaw = K(UV_YAW, t)
        feet, lift = stepping(t, FEET, UV_STEPS)
        fr = F_(qt=bend(pitch, yaw, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, x=K(UV_X, t), z=0.6 * bump(t, 9.0, 20.0), dip=1.0 * bump(t, 7.0, 13.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        h = uv_hand(t)
        rest = rest_arm('right_arm', fr)
        if h is None:
            pose = rest
        else:
            w = smooth((t - 2.0) / 4.5) * (1.0 - smooth((t - 35.0) / 5.0))
            palm = F if t < UV_PULL + 1.0 else unit(D + R)
            pose = mix(rest, reach_arm(holds, 'right_arm', fr, h, palm=palm, palm_w=0.25, limit=1.6), w)
        set_arm(c, 'right_arm', t, pose)
        wl = smooth(K(UV_LEFT, t))
        present = point_arm(fr, v(f=0.75, u=0.55, r=-0.35), unit(U + 0.4 * B), lift=0.4)
        lrest = rest_arm('left_arm', fr)
        if t > 29.0:
            gather = reach_arm(holds, 'left_arm', fr, np.array([1.6, 7.0, -5.4]), palm=R, palm_w=0.3)
            lrest = mix(lrest, gather, bump(t, 29.0, 37.0))
        set_arm(c, 'left_arm', t, mix(lrest, present, wl))
        c.key('head', t, qeuler(x=K(UV_HEAD_X, t), y=K(UV_HEAD_Y, t), z=0.0), ease='linear')
    c.mode('head', 'add')
    # the cloth: flat on the plaque until the pull, then free but for the corner of its foot in his fist, the wall at
    # its back
    grip = lambda t: apply(part_M(c, t, 'right_arm'), GRIP['right_arm'] + np.array([0.0, 0.4, -0.4]))
    pts0 = uv_grid0()
    links = []
    for r in range(UV_ROWS):
        for col in range(UV_COLS):
            i = r * UV_COLS + col
            if col + 1 < UV_COLS:
                links.append((i, i + 1))
            if r + 1 < UV_ROWS:
                links.append((i, i + UV_COLS))
                if col + 1 < UV_COLS:
                    links.append((i, i + UV_COLS + 1))
                    links.append((i + 1, i + UV_COLS))
    held = (UV_ROWS - 1) * UV_COLS + 1

    def pins(t):
        if t < UV_PULL:
            return {k: p for k, p in enumerate(pts0)}
        return {held: grip(t)} if t < UV_HOLD_TO else {held: grip(t), held - UV_COLS: grip(t) + np.array([0.0, -1.0, 0.0])}
    order = [held] + [k for k in range(len(pts0)) if k != held]
    sim = Chain(None, [pts0[k] for k in order], links=[(order.index(a), order.index(b)) for a, b in links],
                gravity=0.32 / 64.0, damp=0.012, iters=10)
    sim.run(0.0, UV_LEN, pins=lambda t: {order.index(k): p for k, p in pins(t).items()},
            planes=[((0.0, 0.0, wa.VEIL_FRONT - 0.3), (0.0, 0.0, 1.0))])
    UV_SIM['sim'], UV_SIM['order'] = sim, order
    la.TRACKS['unveil'] = {name: (lambda t, pivot, i=i: _veil_track(t, i)) for i, name in enumerate(lp.VEILS)}
    ta.INFO['unveil']['tools_at'] = tools_when([(name, UV_PULL, 36.0) for name in lp.VEILS])
    check(c, 'his fist at the cloth\'s foot (t=8)', ta.fk(c, 8.0, 'right_arm', GRIP['right_arm']), uv_hand(8.0), tol=0.8)
    joins_idle(c)
    return c


UV_SIM = {}


def uv_points(t):
    sim, order = UV_SIM['sim'], UV_SIM['order']
    got = sim.at(t)
    pts = [None] * len(order)
    for j, k in enumerate(order):
        pts[k] = got[j]
    return pts


def _veil_track(t, i):
    pts = uv_points(t)
    row = lambda r, col: pts[r * UV_COLS + col]
    a, m, b = row(i, 0), row(i, 1), row(i, 2)
    a2, m2, b2 = row(i + 1, 0), row(i + 1, 1), row(i + 1, 2)
    x = b - a
    wx = float(np.linalg.norm(x))
    x = unit(x) if wx > 1e-6 else np.array([1.0, 0.0, 0.0])
    d = (a2 + m2 * 2.0 + b2) / 4.0 - (a + m * 2.0 + b) / 4.0
    y = d - x * float(np.dot(d, x))
    wy = float(np.linalg.norm(y))
    y = unit(y) if wy > 1e-6 else np.array([0.0, 1.0, 0.0])
    z = np.cross(x, y)
    top = (a + m * 2.0 + b) / 4.0
    return np.column_stack([x, y, z]), top, (max(0.25, wx / lp.VEIL_W), max(0.2, wy / lp.VEIL_LEN), 1.0)


def _plaque_wall():
    lo, hi = wa.clip_pt(*wa.PLAQUE_LO, by=1), wa.clip_pt(*wa.PLAQUE_HI, by=1)
    return [bx((-24, -40, -24), (24, 24, -8), PLASTER), bx(np.minimum(lo, hi), np.maximum(lo, hi), BRONZE)]

# ---- toast (40): a cup raised to the town: the goblet comes up from the side and high over the head at arm's length
# (raise), held there a moment with a little lift for the cheer, then brought down to the lips and drunk with the head
# back (drink), lowered, the back of the left hand wiped across the mouth, and the arm down at the side again, the cup
# kept upright in the fingers all the while.
TO_LEN = 40
TO_RAISE = 9.0
TO_DRINK = 21.0
TO_UP = np.array([-5.6, -8.0, -5.0])                # the fist held high, a little ahead
TO_W = [(0, 0.0), (1.0, 0.0), (5.0, 0.55), (9.0, 1.0), (14.0, 1.0), (17.0, 0.0), (40, 0.0)]          # up high
TO_LIPS = [(0, 0.0), (14.0, 0.0), (18.0, 1.0), (24.0, 1.0), (27.0, 0.0), (40, 0.0)]                  # at the lips
TO_LOW = [(0, 0.0), (1.0, 0.0), (4.0, 1.0), (14.0, 1.0), (14.5, 0.0), (24.0, 0.0), (28.0, 1.0), (33.0, 1.0), (38.0, 0.0), (40, 0.0)]
TO_TIP = [(0, 0.0), (17.0, 0.0), (19.5, 45.0), (22.5, 75.0), (25.0, 20.0), (27.0, 0.0), (40, 0.0)]
TO_P = [(0, 0.0), (5.0, -2.0), (9.0, -5.0), (12.0, -3.5), (13.0, -5.5), (15.0, -3.0), (19.0, -1.0), (22.0, -6.0), (25.0, 0.0),
        (29.0, 2.0), (34.0, 0.0), (40, 0.0)]
TO_HX = [(0, 0.0), (5.0, -10.0), (9.0, -26.0), (14.0, -22.0), (18.0, 4.0), (22.0, -20.0), (25.0, 2.0), (29.0, 6.0), (34.0, 0.0), (40, 0.0)]
TO_WIPE = [(0, 0.0), (24.0, 0.0), (27.0, 1.0), (30.0, 1.0), (33.0, 0.0), (40, 0.0)]
TO_LOWER = np.array([-4.6, 7.4, -7.6])              # the cup held before the chest


def to_frame(t):
    pitch = K(TO_P, t)
    return F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, sink_for(max(0.0, pitch)), 0.0), pr=la.root_for(FEET))


def to_cup_R(t, M_arm):
    """The cup's turn at [t]: upright (its -y up), its bowl on the palm's side of the fingers; at the lips tipped [TO_TIP]
    back toward the drinker."""
    palm = -M_arm[:3, 2]
    fwd = palm - U * float(np.dot(palm, U))
    if np.linalg.norm(fwd) < 1e-3:
        fwd = F
    fwd = unit(fwd)
    R0 = np.column_stack([np.cross(D, -fwd), D, -fwd])        # cup y down, cup z back from the bowl (-z toward it)
    tip = K(TO_TIP, t)
    return rot_about(np.cross(U, B), tip) @ R0 if tip else R0  # its mouth tipped back to the drinker's


def toast():
    c = la.clip('toast', TO_LEN, False, look=('merchant', 2), tools_=('cup',), events=[('raise', TO_RAISE), ('drink', TO_DRINK)],
                traces=[('cup', lp.CUP_RIM, (220, 200, 230))], views=((32, -8), (90, -2), (0, -4)))
    holds = la.Holds()
    rim = np.asarray(lp.CUP_RIM, float)
    for t in la.every(TO_LEN):
        fr = to_frame(t)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        qh = qeuler(x=K(TO_HX, t))
        c.key('head', t, qh, ease='linear')
        rest = rest_arm('right_arm', fr, fwd=4.0)
        low = reach_arm(holds, 'right_arm', fr, TO_LOWER, palm=L, palm_w=0.2)
        pose = mix(rest, low, smooth(K(TO_LOW, t)))
        wu = smooth(K(TO_W, t))
        if wu > 1e-4:
            up = TO_UP + np.array([0.0, -0.8 * bump(t, 11.0, 14.0), 0.0])
            pose = mix(pose, reach_arm(holds, 'right_arm', fr, up, palm=unit(F + L * 0.4), palm_w=0.2), wu)
        wl = smooth(K(TO_LIPS, t))
        if wl > 1e-4:
            lips = head_pt(fr, qh, LIPS + np.array([0.0, 0.0, -0.4]))
            q, off = pose
            for _ in range(5):
                Ra, j = arm_world('right_arm', q, arm_pos=off, **fr)
                M = np.eye(4)
                M[:3, :3] = Ra
                cupR = to_cup_R(t, M)
                want = lips - cupR @ rim
                q, off = reach_arm(holds, 'right_arm', fr, want, p=ta.tool_local('cup')[:3, 3], palm=B, palm_w=0.2)
            pose = mix(pose, (q, off), wl)
        set_arm(c, 'right_arm', t, pose)
        ww = smooth(K(TO_WIPE, t))
        lrest = rest_arm('left_arm', fr)
        if ww > 1e-4:
            sweep = (t - 26.0) / 4.0
            mouth = head_pt(fr, qh, np.array([1.5 - 3.5 * min(1.0, max(0.0, sweep)), -1.2, -5.6]))
            lrest = mix(lrest, reach_arm(holds, 'left_arm', fr, mouth, palm=B, palm_w=0.3), ww)
        set_arm(c, 'left_arm', t, lrest)
    c.mode('head', 'add')
    la.TRACKS['toast'] = {'cup': lambda t, pivot, c=c: (to_cup_R(t, part_M(c, t, 'right_arm')), None, None)}
    check(c, 'the rim at the lips (t=21)', ta.fk(c, TO_DRINK, 'cup', lp.CUP_RIM),
          head_pt(ta.frame_at(c, TO_DRINK), c.sample('head', TO_DRINK)[0], LIPS + np.array([0.0, 0.0, -0.4])), tol=0.9)
    joins_idle(c)
    return c

# ================================================================== the watchtower

# ---- lookout (80, loop): the watch on the platform, the right hand flat over the brow shading the eyes, scanning the road
# slowly from far left to far right and back, the body turning a little with the head; a stop to stare hard at something
# far off to the right, leaning in (stare), then on round. The horn is slung at the left hip (shown), the left arm
# hangs (it may carry a lantern by night: give lookout the hands of RIGHT_ONLY).
LO_LEN = 80
LO_BROW = np.array([-1.0, -5.4, -6.4])              # head space: the hand's end before the brow
LO_YAW = [(0, -10.0), (8.0, -38.0), (16.0, -46.0), (22.0, -40.0), (34.0, 8.0), (42.0, 30.0), (50.0, 38.0), (54.0, 40.0),
          (62.0, 38.0), (68.0, 22.0), (74.0, 2.0), (80, -10.0)]
LO_LEAN = [(0, 0.0), (48.0, 0.0), (51.0, 1.0), (61.0, 1.0), (65.0, 0.0), (80, 0.0)]
LO_PITCH = [(0, -4.0), (16.0, -2.0), (30.0, -5.0), (48.0, -3.0), (52.0, 1.0), (61.0, 1.0), (66.0, -4.0), (80, -4.0)]


def lookout():
    c = la.clip('lookout', LO_LEN, True, look=('guard', 0), tools_=('horn', 'horn_baldric'), events=[('stare', 52.0)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    times = la.every(LO_LEN)
    for lap in range(2):
        for t in times:
            yaw_h = K(LO_YAW, t, LO_LEN)
            lean = K(LO_LEAN, t)
            pitch = 3.0 + 6.0 * lean
            fr = F_(qt=bend(pitch, 0.35 * yaw_h, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                    pr=la.root_for(FEET, z=-0.9 * lean, dip=0.4 * lean))
            qh = qeuler(x=K(LO_PITCH, t, LO_LEN), y=0.65 * yaw_h, z=2.0 * lean)
            brow = head_pt(fr, qh, LO_BROW)
            right = reach_arm(holds, 'right_arm', fr, brow, palm=D, palm_w=0.5, limit=1.6)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr)
                c.key('head', t, qh, ease='linear')
                set_arm(c, 'right_arm', t, right)
                set_arm(c, 'left_arm', t, rest_arm('left_arm', fr, fwd=1.0 * math.sin(2 * math.pi * t / 40.0)))
    c.mode('head', 'add')
    seal(c)
    for k in (0.0, 52.0):
        at = head_pt(ta.frame_at(c, k), c.sample('head', k)[0], LO_BROW)
        check(c, f'hand over the brow (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), at, tol=0.9)
    return c

# ---- light_beacon (50): the beacon brazier a block ahead, its coals 13 px up: the watch steps up to it with the lit
# torch held high and dips it head first into the coals (dip); the brazier flares (flare: the game sets it flaring that
# tick) and he starts back a step, the left forearm up before his face and the head turned from the blaze, then lowers
# it and watches the fire take, the torch held upright again.
LB_LEN = 50
LB_DIP = 11.0
LB_FLARE = 14.0
LB_IN = wa.BRAZIER_COALS + np.array([-0.6, -1.0, 3.2])     # the torch's head in the coals' near side
LB_TORCH_IN = unit(np.array([0.12, 1.0, 0.06]))            # the torch's top end plunged down into the brazier
LB_STEP = [(0, 0.0), (2.0, 0.0), (6.0, -1.0), (13.0, -1.2), (15.0, -0.4), (18.0, 2.2), (22.0, 1.8), (30.0, 0.6), (40.0, 0.0), (50, 0.0)]
LB_IN_W = [(0, 0.0), (3.0, 0.0), (8.0, 0.7), (LB_DIP, 1.0), (LB_FLARE, 1.0), (17.0, 0.0), (50, 0.0)]
LB_UP_W = [(0, 0.0), (2.0, 0.0), (6.0, 1.0), (8.0, 1.0), (10.0, 0.0), (15.0, 0.0), (18.0, 0.7), (26.0, 0.6), (36.0, 0.0), (50, 0.0)]
LB_SHIELD = [(0, 0.0), (LB_FLARE, 0.0), (15.5, 1.0), (22.0, 1.0), (27.0, 0.0), (50, 0.0)]
LB_PITCH = [(0, 0.0), (5.0, 4.0), (LB_DIP, 16.0), (LB_FLARE, 14.0), (16.0, -8.0), (19.0, -6.0), (24.0, -2.0), (34.0, 0.0), (50, 0.0)]
LB_YAW = [(0, 0.0), (LB_FLARE, -4.0), (16.5, 12.0), (22.0, 10.0), (27.0, 2.0), (50, 0.0)]
LB_HX = [(0, 0.0), (4.0, 6.0), (LB_DIP, 20.0), (LB_FLARE, 18.0), (16.0, -6.0), (20.0, -4.0), (24.0, -18.0), (30.0, -22.0),
         (38.0, -10.0), (44.0, 0.0), (50, 0.0)]
LB_HY = [(0, 0.0), (LB_FLARE, 0.0), (15.5, 34.0), (21.0, 28.0), (26.0, 4.0), (50, 0.0)]


def lb_frame(t):
    pitch = K(LB_PITCH, t)
    z = K(LB_STEP, t)
    feet = {'right_leg': (-1.9, max(0.0, z) * 0.5 + 0.0), 'left_leg': (1.9, min(0.0, z) * 1.6 + max(0.0, z) * 0.8)}
    return F_(qt=bend(pitch, K(LB_YAW, t), 0.0), vt=(0.0, sink_for(max(0.0, pitch)), 0.0),
              pr=la.root_for(feet, z=z, dip=0.8 * bump(t, 8.0, 20.0))), feet


def lb_torch_R(t, M_arm):
    """The torch's turn: as the hand holds it (upright before the fist) but stood up straight when carried, and tipped
    head first into the coals at the dip."""
    up = np.column_stack([np.array([1.0, 0.0, 0.0]), np.array([0.0, 1.0, 0.0]), np.array([0.0, 0.0, 1.0])])
    yaw_fwd = M_arm[:3, 2] - U * float(np.dot(M_arm[:3, 2], U))
    held = frame_of(D, unit(yaw_fwd) if np.linalg.norm(yaw_fwd) > 1e-3 else B)
    w = smooth(K(LB_IN_W, t))
    tipped = frame_of(-LB_TORCH_IN, unit(yaw_fwd) if np.linalg.norm(yaw_fwd) > 1e-3 else B)
    q = qslerp(qfrommat(held), qfrommat(tipped), w)
    return qmat(q)


def light_beacon():
    c = la.clip('light_beacon', LB_LEN, False, look=('guard', 1), tools_=('torch', 'torch_flame', 'horn', 'horn_baldric'),
                station_='brazier', events=[('dip', LB_DIP), ('flare', LB_FLARE)],
                traces=[('torch', lp.TORCH_TOP, (255, 180, 80))], views=((40, -12), (90, -4), (-40, -14)))
    holds = la.Holds()
    piv = np.asarray(lp.TORCH_PIVOT, float)
    top = np.asarray(lp.TORCH_TOP, float)
    for t in la.every(LB_LEN):
        fr, feet = lb_frame(t)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet)
        rest = rest_arm('right_arm', fr, fwd=10.0 * smooth(t / 3.0) * (1.0 - smooth((t - 44.0) / 5.0)))
        up = point_arm(fr, v(f=0.75, u=-0.35, r=0.2), L, lift=0.3)
        pose = mix(rest, up, smooth(K(LB_UP_W, t)))
        w = smooth(K(LB_IN_W, t))
        if w > 1e-4:
            q, off = pose
            for _ in range(2):
                Ra, j = arm_world('right_arm', q, arm_pos=off, **fr)
                M = np.eye(4)
                M[:3, :3] = Ra
                Rt = lb_torch_R(t, M)
                want = LB_IN - Rt @ top
                q, off = reach_arm(holds, 'right_arm', fr, want, p=piv, palm=L, palm_w=0.15)
            pose = mix(pose, (q, off), w)
        set_arm(c, 'right_arm', t, pose)
        ws = smooth(K(LB_SHIELD, t))
        lpose = rest_arm('left_arm', fr)
        if ws > 1e-4:
            shield = point_arm(fr, v(f=0.75, u=0.62, r=-0.5), unit(F + 0.3 * D), lift=0.6)
            lpose = mix(lpose, shield, ws)
        set_arm(c, 'left_arm', t, lpose)
        c.key('head', t, qeuler(x=K(LB_HX, t), y=K(LB_HY, t)), ease='linear')
    c.mode('head', 'add')
    la.TRACKS['light_beacon'] = {'torch': lambda t, pivot, c=c: (lb_torch_R(t, part_M(c, t, 'right_arm')), None, None),
                                 'torch_flame': lambda t, pivot, c=c: _flame_track(c, t)}
    check(c, 'torch head in the coals (t=11)', ta.fk(c, LB_DIP, 'torch', lp.TORCH_TOP), LB_IN, tol=0.9)
    joins_idle(c, tol=1.2)
    return c


def _flame_track(c, t):
    """The torch's flame: on the top of its head wherever the torch is turned, standing up, leaning back from the way it
    moves, and flickering."""
    def top_at(k):
        M = part_M(c, k, 'right_arm')
        Rt = lb_torch_R(k, M)
        return apply(M, lp.TORCH_PIVOT) + Rt @ np.asarray(lp.TORCH_TOP, float)
    p = top_at(t)
    vel = (p - top_at(t - 0.5)) / 0.5
    lean = -vel * 0.18
    lean[1] = 0.0
    y = unit(D + lean)
    R_ = frame_of(y, B)
    f = 1.0 + 0.10 * math.sin(t * 2.9) + 0.06 * math.sin(t * 7.3 + 1.0)
    w = 1.0 + 0.06 * math.sin(t * 4.1 + 2.0)
    if LB_DIP - 1.0 < t < LB_FLARE + 1.0:
        f *= 0.6
    return R_, p, (w, f, w)


def _brazier():
    x, y, z = wa.BRAZIER_COALS
    r = wa.BRAZIER_R
    rim = 24.0 - wa.BRAZIER_RIM_Y
    out = [bx((x - r, rim, z - r), (x + r, rim + 4.5, z + r), IRONC), bx((x - r + 1, y, z - r + 1), (x + r - 1, y + 1, z + r - 1), COALS)]
    for dx, dz in ((-3.5, 2.5), (3.5, 2.5), (0.0, -3.8)):
        out.append(bx((x + dx - 0.5, rim + 4.5, z + dz - 0.5), (x + dx + 0.5, 24, z + dz + 0.5), IRONC))
    return out

# ---- sound_horn (40; no Activity: the game plays it in lookout's place when a strike is seen): the horn taken up from
# the hip in the left hand to the lips, a deep breath, and a long blast (blow) with the body leaning back and the bell
# lifting, a tremble at its end; lowered and slung at the hip again.
SH_LEN = 40
SH_BLOW = 12.0
SH_UP = [(0, 0.0), (2.0, 0.0), (8.0, 1.0), (28.0, 1.0), (35.0, 0.0), (40, 0.0)]
SH_LIFT = [(0, 0.0), (SH_BLOW, 0.0), (16.0, 1.0), (24.0, 1.0), (28.0, 0.0), (40, 0.0)]
SH_P = [(0, 0.0), (8.0, -1.0), (11.0, -5.0), (SH_BLOW, -6.0), (24.0, -8.0), (28.0, -2.0), (34.0, 1.0), (40, 0.0)]
SH_HX = [(0, 0.0), (6.0, 6.0), (10.0, -4.0), (SH_BLOW, -6.0), (24.0, -12.0), (29.0, -2.0), (34.0, 2.0), (40, 0.0)]
SH_HOLD = np.array([2.4, 1.1, 0.0])                  # horn space: where the left fist holds it, under its tip


def sh_horn_world(c, t, qh, fr):
    """The horn's 4x4 at [t]: slung at the hip (its rest: the body carries it), or up at the lips, the bell ahead and up
    to the left."""
    rest = part_M(c, t, 'body') @ ta.tool_local('horn')
    lift = smooth(K(SH_LIFT, t))
    x = unit(0.86 * B + (0.35 - 0.35 * lift) * D + 0.25 * R)
    y = unit(D - x * float(np.dot(D, x)))
    R_ = np.column_stack([x, y, np.cross(x, y)])
    lips = head_pt(fr, qh, LIPS + np.array([0.6, 0.0, 0.2]))
    P = lips - R_ @ np.asarray(lp.HORN_MOUTH, float)
    w = smooth(K(SH_UP, t))
    q = qslerp(qfrommat(rest[:3, :3]), qfrommat(R_), w)
    M = np.eye(4)
    M[:3, :3] = qmat(q)
    M[:3, 3] = rest[:3, 3] * (1.0 - w) + P * w
    return M


def sound_horn():
    c = la.clip('sound_horn', SH_LEN, False, look=('guard', 0), tools_=('horn', 'horn_baldric'), events=[('blow', SH_BLOW)],
                traces=[('horn', lp.HORN_MOUTH, (240, 220, 160))], views=((32, -8), (90, -2), (-30, -6)))
    holds = la.Holds()
    for t in la.every(SH_LEN):
        pitch = K(SH_P, t)
        fr = F_(qt=bend(pitch, 6.0 * smooth(K(SH_UP, t)), 0.0), vt=(0.0, -0.5 * bump(t, 8.0, 13.0), 0.0), pr=la.root_for(FEET))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        qh = qeuler(x=K(SH_HX, t) + 0.8 * wave(t, 20.0, 27.0, 1.6), y=-8.0 * smooth(K(SH_UP, t)))
        c.key('head', t, qh, ease='linear')
        set_arm(c, 'right_arm', t, rest_arm('right_arm', fr, fwd=2.0 * bump(t, 10.0, 28.0)))
        w = smooth(K(SH_UP, t) * 1.6) if t < 20 else smooth(K(SH_UP, t))
        lrest = rest_arm('left_arm', fr)
        if w > 1e-4:
            # (the horn's place depends on the head, which is keyed: solve against this tick's frame)
            M = _sh_world_now(fr, qh, t)
            hold = apply(M, SH_HOLD)
            lrest = mix(lrest, reach_arm(holds, 'left_arm', fr, hold, palm=unit(U + R), palm_w=0.3), w)
        set_arm(c, 'left_arm', t, lrest)
    c.mode('head', 'add')
    SH_C['c'] = c
    la.TRACKS['sound_horn'] = {'horn': lambda t, pivot, c=c: _horn_track(c, t)}
    la.EXTRA['sound_horn'] = (SH_LEN, False)
    joins_idle(c)
    return c


SH_C = {}


def _sh_world_now(fr, qh, t):
    """The horn's 4x4 while building the clip (the body as [fr] carries it)."""
    lift = smooth(K(SH_LIFT, t))
    x = unit(0.86 * B + (0.35 - 0.35 * lift) * D + 0.25 * R)
    y = unit(D - x * float(np.dot(D, x)))
    R_ = np.column_stack([x, y, np.cross(x, y)])
    lips = head_pt(fr, qh, LIPS + np.array([0.6, 0.0, 0.2]))
    P = lips - R_ @ np.asarray(lp.HORN_MOUTH, float)
    body = T(*fr['pr']) @ Rq(fr['qr'])
    Mb = body @ T(*(HIPS + qmat(fr['qt']) @ (np.zeros(3) - HIPS) + fr['vt'])) @ Rq(fr['qt'])
    rest = Mb @ ta.tool_local('horn')
    w = smooth(K(SH_UP, t))
    q = qslerp(qfrommat(rest[:3, :3]), qfrommat(R_), w)
    M = np.eye(4)
    M[:3, :3] = qmat(q)
    M[:3, 3] = rest[:3, 3] * (1.0 - w) + P * w
    return M


def _horn_track(c, t):
    qh = c.sample('head', t)[0]
    M = sh_horn_world(c, t, qh, ta.frame_at(c, t))
    return M[:3, :3], M[:3, 3], None

# ================================================================== the registry

WORKS_CLIPS = ['mix_mortar', 'trowel', 'hoist', 'carry_beam', 'chisel', 'plumb', 'survey', 'unveil', 'toast', 'lookout',
               'light_beacon', 'sound_horn']
MAKERS = {'mix_mortar': mix_mortar, 'trowel': trowel, 'hoist': hoist, 'carry_beam': carry_beam, 'chisel': chisel,
          'plumb': plumb, 'survey': survey, 'unveil': unveil, 'toast': toast, 'lookout': lookout,
          'light_beacon': light_beacon, 'sound_horn': sound_horn}
STATION_BOXES.update({'wall_course': _wall, 'hoist_rope': _hoist_rope, 'banker': _banker, 'wall': _wall_tall,
                      'stakes': _stakes, 'plaque_wall': _plaque_wall, 'brazier': _brazier})
STATION_COLOUR.update({'wall_course': EARTH, 'hoist_rope': EARTH, 'banker': EARTH, 'stakes': (96, 120, 70)})


def works_moment_specs():
    """Where the works' clips' moments happen (living_anims' moment_specs forms)."""
    rg = tuple(GRIP['right_arm'])
    right = ('part', 'right_arm', rg)
    mouth = ('part', 'head', (0.0, -2.2, -4.4))
    return {
        'mix_mortar': {'stir': ('part', 'mortar_hoe', tuple(MM_BLADE))},
        'trowel': {'scoop': ('part', 'hawk', tuple(lp.HAWK_TOP)), 'spread': ('part', 'trowel', TR_BLADE),
                   'cut': ('part', 'trowel', TR_BLADE), 'tap': ('part', 'trowel', TR_BLADE)},
        'hoist': {'heave': ('hands', 0.0)},
        'carry_beam': {'hitch': right},
        'chisel': {'strike': ('part', 'chisel', tuple(lp.CHISEL_EDGE))},
        'plumb': {'true': ('part', 'head', (0.0, -4.0, -3.0))},
        'survey': {'plant': ('point', tuple(SV_PLANT)), 'wave': right, 'there': right},
        'unveil': {'pull': right, 'present': ('part', 'left_arm', tuple(GRIP['left_arm']))},
        'toast': {'raise': ('part', 'cup', tuple(lp.CUP_RIM)), 'drink': mouth},
        'lookout': {'stare': ('part', 'head', (0.0, -4.0, -3.0))},
        'light_beacon': {'dip': ('point', tuple(wa.BRAZIER_COALS)), 'flare': ('point', tuple(wa.BRAZIER_COALS))},
        'sound_horn': {'blow': mouth},
    }


BUILT = {}


def build(living, only=None):
    """Builds the works' clips on living_anims' kit ([living]: the module), and teaches it their stations, moments and
    props' tracks. [only]: just these (the quick previews)."""
    global la
    la = living
    if BUILT:
        return
    la.STATIONS.update(STATION_BOXES)
    la.GROUND_AT.update(STATION_GROUND)
    la.GROUND_COLOUR.update(STATION_COLOUR)
    for name, make in MAKERS.items():
        if only and name not in only:
            continue
        make()
    specs_ = la.moment_specs

    def moment_specs():
        out = specs_()
        out.update(works_moment_specs())
        return out
    la.moment_specs = moment_specs
    if 'torch' not in getattr(la, 'GLOW_PROPS', ['torch']):
        la.GLOW_PROPS.extend(['torch', 'torch_flame'])
    install_preview_hooks()
    BUILT['done'] = True
    la.WANTED.extend(c for c in WORKS_CLIPS if c != 'sound_horn')

# ================================================================== the previews

# A second figure in a clip's previews, placed per clip: (its 4 x 4 from the first's place, how many ticks later it
# starts its own clip), walking with the first where the first is played walking (the carriers in step under one beam;
# the alms table's keeper and taker; the embrace's two).
PARTNER_PLACE = {}
_MAIN = [None]


def install_preview_hooks():
    if getattr(ta, '_M6_PARTNERS', False):
        return
    ta._M6_PARTNERS = True
    rc_, pq_ = ta.render_cell, la.partner_quads

    def render_cell(look, c, t, *a, **k):
        _MAIN[0] = c.name
        try:
            return rc_(look, c, t, *a, **k)
        finally:
            _MAIN[0] = None

    def partner_quads(partner, t):
        if _MAIN[0] not in PARTNER_PLACE:
            return pq_(partner, t)
        G, later = PARTNER_PLACE[_MAIN[0]]
        name, look_key = (partner, None) if isinstance(partner, str) else partner
        pc = la.getc(name)
        tp = min(max(t - later, 0.0), float(pc.length))
        look2 = ta.look_for(look_key or ta.INFO[name]['look'])
        walking = ta.INFO[_MAIN[0]]['walking']
        base = ta.walk_base(t, speed=ta.walk_speed(pc, walking) if walking else 0.0, age=t)
        keep = la._CUR[1]
        la._CUR[1] = tp
        try:
            pose2 = ta.pose_at(pc, tp, base)
            mats2 = {k: G @ M for k, M in la.part_matrices(look2, pose2).items()}
            return ta.model_quads(look2, mats2, la.shown_tools(name, tp)) + la.goods_quads(name, tp, mats2)
        finally:
            la._CUR[1] = keep
    ta.render_cell = render_cell
    la.partner_quads = partner_quads


def gif(c, out_dir, views=((32, -10), (0, -6), (90, -2)), cell=(230, 300), zoom=6.0, rest=10):
    """An animated GIF of [c] at the game's pace (a frame a tick), from three views side by side."""
    import talk_clips
    return talk_clips.gif(c, out_dir, views=views, cell=cell, zoom=zoom, rest=rest)
