"""
The streets' moves (M6, influence: living-world/design-influence.md, sections 5, 6 and 8), on the Living World's kit.
The hard days: waiting in the bread queue (queue), shivering with the coal short (shiver), an empty basket turned over
at a bare stall (empty_basket), haggling (haggle), and a bare stall's flap let down (shut_stall). The alms table: hands
held out and a bowl taken in them (hold_out), the keeper ladling stew into it and handing it over the table (ladle:
the game starts the two together), the bowl drawn in with a little bow (take_bowl, which starts on hold_out's last frame
and ends on eat_bowl's first) and eaten standing (eat_bowl). A household coming and going: the handcart packed (pack), the neighbours' embrace (embrace, one clip that
both play, face to face), the newcomers taking in the street (look_about) and shown to their door (show_in, an overlay
played walking).

living_anims.py imports this at its end, after works_clips.py (whose pieces these share), and calls build() with
itself. The alms table's numbers come from works_art.py (its top, the pot and its stew, the stack of bowls), so the
ladle goes into the stew, the bowl comes off the stack and is handed over the table's middle, where the one who takes
it reaches; the handcart's from handcart.py, so the bundle lands on its bed as it rests tipped on its shafts.

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
                             key_frame, check, seal, smooth, bump, apply, GRIP, LIPS, arm_world, torso_to_world,
                             tool_point, tool_dir, head_at, qinv, sink_for)
import living_props as lp
import works_art as wa
import works_clips as wc
import handcart as hc
from works_clips import (K, KV, rest_arm, point_arm, mix, lifted, set_arm, reach_arm, stand, head_pt, part_M, joins_idle,
                         tools_when, frame_of, rot_about, stepping, bx, wave, REST, FEET, PARTNER_PLACE)

la = None           # living_anims, handed to build()

BOWL_BASE = np.array([0.0, -2.0, 1.0])          # the stew bowl's (and the empty one's) part space: its bottom's middle
BOWL_TOP = np.array([0.0, -3.9, 1.0])           # the stew's surface's middle
SPOON_BOWL = np.array([0.0, 4.7, -0.2])         # the spoon's part space: the middle of its bowl
FACING = Rq(qeuler(y=180.0))                    # a partner facing back

# ================================================================== the pieces these clips share


def tdir(fr, d):
    """A torso-space direction in the world, under [fr]."""
    return qmat(qmul(fr['qr'], fr['qt'])) @ np.asarray(d, float)


def level(fwd=B):
    """A bowl's turn held level: its -y up, its +z (where its middle sits from the fist) towards [fwd]."""
    return frame_of(D, fwd)


def blend_R(Ra, Rb, w):
    if w <= 0.0:
        return Ra
    if w >= 1.0:
        return Rb
    qa, qb = qfrommat(Ra), qfrommat(Rb)
    if float(np.dot(qa, qb)) < 0.0:
        qb = -qb
    return qmat(qslerp(qa, qb, w))


def arm_M(side, pose, fr):
    """An arm's 4 x 4 (its pivot and turn) for the pose [pose] under [fr]."""
    Ra, j = arm_world(side, pose[0], arm_pos=pose[1], **fr)
    M = np.eye(4)
    M[:3, :3] = Ra
    M[:3, 3] = j
    return M


def path(keys, t):
    """A point along keys (t, point), held at either end."""
    ks = [(k, tuple(float(x) for x in p)) for k, p in keys]
    return np.asarray(KV(ks, min(max(t, ks[0][0]), ks[-1][0])), float)


def head_level(fr, x=0.0, y=0.0, z=0.0):
    """A 'set' head key holding the head [x] degrees down, [y] to the left and [z] tipped left of the root's level, whatever
    the torso does."""
    return qmul(qinv(qmul(fr['qr'], fr['qt'])), qeuler(x=x, y=y, z=z))


def pose_keys(c, t):
    """[c]'s frame and limb keys at [t]: to start or end another clip exactly on."""
    return ta.frame_at(c, t), {b: c.sample(b, t) for b in ('right_arm', 'left_arm', 'head')}


def frame_toward(fr, held, w):
    """A frame eased toward [held]'s (pose_keys) by [w]."""
    if held is None or w <= 0.0:
        return fr
    h = held[0]
    return F_(qt=qslerp(fr['qt'], h['qt'], w), vt=np.asarray(fr['vt']) * (1 - w) + np.asarray(h['vt']) * w,
              pr=np.asarray(fr['pr']) * (1 - w) + np.asarray(h['pr']) * w, qr=qslerp(fr['qr'], h['qr'], w))


def arm_toward(pose, held, side, w):
    if held is None or w <= 0.0:
        return pose
    q, off = held[1][side]
    return mix(pose, (q, np.zeros(3) if off is None else off), w)

# ================================================================== the stations the previews draw

TABLE = (176, 136, 90)
TABLE_D = (124, 92, 58)
POTC = (70, 68, 72)
STEWC = (150, 92, 46)
BOWLC = (178, 132, 82)
CART = (140, 100, 62)
CART_D = (96, 66, 40)
WHEELC = (84, 58, 40)
SLATE = (52, 56, 60)


def _alms(taker=False):
    """The alms table a block ahead as the keeper sees it (or, [taker], from across it): the trestle, the pot of stew on
    the keeper's right, two bowls stacked on their left (LADLE takes the third from on top of them)."""
    def at(lo, hi):
        lo, hi = np.asarray(lo, float), np.asarray(hi, float)
        if taker:
            lo, hi = np.array([-hi[0], lo[1], -wa.ALMS_GAP - hi[2]]), np.array([-lo[0], hi[1], -wa.ALMS_GAP - lo[2]])
        return tuple(lo), tuple(hi)
    top = wa.ALMS_TOP
    out = [bx(*at((-8, top, -23), (8, top + 1.0, -9)), TABLE)]
    for x in (-6.5, 5.5):
        out.append(bx(*at((x, top + 1.0, -21), (x + 1.0, 24, -11)), TABLE_D))
    px, py, pz = (float(q) for q in wa.POT_MOUTH)
    r = wa.POT_R + 0.6
    out += [bx(*at((px - r, py, pz - r), (px + r, top, pz - r + 0.6)), POTC),
            bx(*at((px - r, py, pz + r - 0.6), (px + r, top, pz + r)), POTC),
            bx(*at((px - r, py, pz - r), (px - r + 0.6, top, pz + r)), POTC),
            bx(*at((px + r - 0.6, py, pz - r), (px + r, top, pz + r)), POTC)]
    sy = 24.0 - wa.pot_level_y(6)
    out.append(bx(*at((px - r + 0.6, sy, pz - r + 0.6), (px + r - 0.6, top, pz + r - 0.6)), STEWC))
    bxx, byy, bzz = (float(q) for q in wa.BOWL_TAKEN)
    out.append(bx(*at((bxx - 2.25, byy, bzz - 2.25), (bxx + 2.25, top, bzz + 2.25)), BOWLC))
    return out


def _bare_stall():
    """A stall's counter a block ahead, bare, the chalked slate leaning at its back."""
    return [bx((-8, 8, -24), (8, 24, -8), (140, 100, 62)), bx((-5.5, 1.6, -21.4), (5.5, 8, -20.0), SLATE)]

# ================================================================== the hard days

# ---- queue (80, loop): standing in line, the hands clasped before the belt, the weight going from foot to foot; a lean
# to the left to peer past whoever is ahead, chin up, and back; a glance back down the line; a sigh, the shoulders up
# and down, and a mutter (murmur); a lean to peer past on the right; three impatient taps of the right foot.
QU_LEN = 80
QU_PEER = [(0, 0.0), (13.0, 0.0), (18.0, 1.0), (24.0, 1.0), (28.0, 0.0), (53.0, 0.0), (58.0, -1.0), (64.0, -1.0),
           (68.0, 0.0), (80, 0.0)]
QU_SIGH = (41.0, 51.0)
QU_MURMUR = 46.0
QU_TAPS = (70.0, 72.5, 75.0)
QU_BACK = (30.0, 40.0)
QU_CLASP = {'right_arm': np.array([-0.7, 10.3, -3.9]), 'left_arm': np.array([0.7, 10.1, -3.7])}     # torso space
QU_PALM = {'right_arm': unit(L + 0.5 * B), 'left_arm': unit(R + 0.5 * B)}                           # torso space


def queue():
    c = la.clip('queue', QU_LEN, True, look=('citizen', 1), events=[('murmur', QU_MURMUR)],
                traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))], views=((32, -10), (90, -2), (0, -4)))
    holds = la.Holds()
    times = la.every(QU_LEN)
    for lap in range(2):
        for t in times:
            peer = K(QU_PEER, t)
            sway = 0.7 * math.sin(2 * math.pi * (t - 6.0) / 40.0)
            sigh = bump(t, *QU_SIGH)
            fr = F_(qt=bend(2.0 - 3.0 * sigh + 3.0 * abs(peer), -8.0 * peer + 2.0 * math.sin(2 * math.pi * t / QU_LEN),
                            12.0 * peer + 1.6 * sway), vt=(0.0, -0.6 * sigh, 0.0),
                    pr=la.root_for(FEET, x=sway - 1.3 * peer, dip=0.3 + 0.3 * (1.0 - abs(sway) / 0.7)))
            poses = {}
            for side in ('right_arm', 'left_arm'):
                at = torso_to_world(fr, QU_CLASP[side])
                poses[side] = lifted(reach_arm(holds, side, fr, at, palm=tdir(fr, QU_PALM[side]), palm_w=0.3), 0.8 * sigh)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr, lift=(sum(0.9 * bump(t, k - 1.2, k + 0.6) for k in QU_TAPS), 0.0))
                for side, pose in poses.items():
                    set_arm(c, side, t, pose)
                back = bump(t, *QU_BACK)
                c.key('head', t, qeuler(x=-3.0 + 9.0 * sigh - 10.0 * abs(peer) + 5.0 * bump(t, 68.0, 78.0),
                                        y=-6.0 * peer - 50.0 * back + 6.0 * math.sin(2 * math.pi * (t - 10.0) / QU_LEN),
                                        z=8.0 * peer), ease='linear')
    c.mode('head', 'add')
    seal(c)
    wc.feet_down(c, (0.0, 20.0, 46.0))
    return c

# ---- shiver (40, loop): the coal gone short and the wind cold: the arms wrapped across the chest, each hand rubbing the
# other arm up and down, the shoulders hunched and the head drawn in; a fit of shivers, a stamp of the right foot
# (stamp), a breath blown out (puff), another fit and a stamp of the left.
SH2_LEN = 40
SH2_STAMPS = (14.0, 34.0)
SH2_PUFF = 21.0
SH2_HUG = {'right_arm': np.array([3.0, 4.6, -5.6]), 'left_arm': np.array([-2.6, 5.8, -6.6])}       # torso space


def shiver():
    c = la.clip('shiver', SH2_LEN, True, look=('citizen', 0), events=[('stamp', k) for k in SH2_STAMPS] + [('puff', SH2_PUFF)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    times = la.every(SH2_LEN)
    for lap in range(2):
        for t in times:
            fit = bump(t, 3.0, 11.0) + bump(t, 23.0, 31.0)
            shake = fit * math.sin(2 * math.pi * t / 2.0)
            stamp_r, stamp_l = bump(t, 10.5, 14.0), bump(t, 30.5, 34.0)
            hit = bump(t, 13.8, 17.0) + bump(t, 33.8, 37.0)
            fr = F_(qt=bend(7.0 + 2.5 * hit, 2.2 * shake, 2.5 * (stamp_l - stamp_r)), vt=(0.0, 0.6 + 0.5 * hit, 0.0),
                    pr=la.root_for(FEET, x=0.9 * (stamp_r - stamp_l), dip=0.8 + 0.8 * hit))
            poses = {}
            for side, k in (('right_arm', 0.0), ('left_arm', 2.5)):
                rub = math.sin(2 * math.pi * (t - k) / 5.0)
                at = torso_to_world(fr, SH2_HUG[side] + np.array([0.0, rub, 0.0]))
                palm = tdir(fr, unit(B + (L if side == 'right_arm' else R)))
                poses[side] = lifted(reach_arm(holds, side, fr, at, palm=palm, palm_w=0.25), 0.9 + 0.4 * fit)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr, lift=(2.4 * stamp_r, 2.4 * stamp_l))
                for side, pose in poses.items():
                    set_arm(c, side, t, pose)
                puff = bump(t, SH2_PUFF - 2.5, SH2_PUFF + 2.0)
                c.key('head', t, qeuler(x=10.0 - 13.0 * puff + 1.5 * shake, y=1.5 * shake + 10.0 * bump(t, 26.0, 37.0),
                                        z=0.8 * shake), ease='linear')
    c.mode('head', 'add')
    seal(c)
    for k, side in zip(SH2_STAMPS, ('right_leg', 'left_leg')):
        check(c, f'{side} stamped down (t={k:g}: its height off the ground)', (la.leg_end(c, k, side)[1],), (24.0,), tol=0.6)
    return c

# ---- empty_basket (40): at a bare stall, the shopper's basket (carried on the left arm) taken up before the chest by
# its two ends, turned over (the right hand comes to its other end) and shaken twice over the counter (shake: what
# crumbs there are fall), turned back up and looked into, a sigh and a slow shake of the head, and let down to the side.
EB2_LEN = 40
EB2_SHAKES = (13.0, 16.0)
EB2_HELD = [(0, 0.0), (1.5, 0.0), (6.0, 1.0), (33.0, 1.0), (38.5, 0.0), (40, 0.0)]       # off the side, before the chest
EB2_TURN = [(0, 0.0), (6.5, 0.0), (10.5, 1.0), (18.5, 1.0), (23.0, 0.0), (40, 0.0)]      # upside down
EB2_RIGHT = [(0, 0.0), (2.5, 0.0), (6.0, 1.0), (31.0, 1.0), (35.5, 0.0), (40, 0.0)]
EB2_AT = np.array([0.6, 5.0, -8.8])             # the basket's middle held before the chest
EB2_BODY = np.array([0.0, 4.25, 0.0])           # basket space: the middle of its body
EB2_ENDS = {'left_arm': np.array([0.0, 4.25, 3.0]), 'right_arm': np.array([0.0, 4.25, -3.0])}


def eb2_rest_M():
    """The basket where idle's left hand carries it."""
    return arm_M('left_arm', rest_arm('left_arm', REST), REST) @ ta.tool_local('basket_turned')


def eb2_M(t):
    """The basket's 4 x 4 at [t]: carried at the side, or before the chest by its ends, its long way across, turned
    over about it and shaken."""
    rest = eb2_rest_M()
    w = smooth(K(EB2_HELD, t))
    if w <= 0.0:
        return rest
    turn = smooth(K(EB2_TURN, t))
    jolt = sum(wave(t, k - 1.0, k + 1.6, 1.3) for k in EB2_SHAKES)
    held_R = rot_about(F, 7.0 * math.sin(math.pi * turn)) @ rot_about(L, 180.0 * turn + 10.0 * jolt) @ frame_of(D, L)
    mid = EB2_AT + np.array([0.0, -1.2 * jolt - 1.6 * math.sin(math.pi * turn), 0.0])
    M = np.eye(4)
    M[:3, :3] = blend_R(rest[:3, :3], held_R, w)
    M[:3, 3] = rest[:3, 3] * (1.0 - w) + (mid - held_R @ EB2_BODY) * w
    return M


def empty_basket():
    c = la.clip('empty_basket', EB2_LEN, False, look=('citizen', 2), tools_=('basket_turned',), station_='bare_stall',
                events=[('shake', k) for k in EB2_SHAKES], traces=[('basket_turned', tuple(EB2_BODY), (200, 230, 255))],
                views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    for t in la.every(EB2_LEN):
        held = smooth(K(EB2_HELD, t))
        sigh = bump(t, 25.0, 33.0)
        fr = F_(qt=bend(4.0 * held - 3.0 * sigh, 0.0, 0.0), vt=(0.0, -0.5 * sigh, 0.0), pr=la.root_for(FEET, dip=0.4 * held))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        M = eb2_M(t)
        left = reach_arm(holds, 'left_arm', fr, apply(M, EB2_ENDS['left_arm'] * held), palm=tdir(fr, R), palm_w=0.3 * held)
        set_arm(c, 'left_arm', t, lifted(mix(rest_arm('left_arm', fr), left, held), 0.6 * sigh))
        rw = smooth(K(EB2_RIGHT, t))
        right = reach_arm(holds, 'right_arm', fr, apply(M, EB2_ENDS['right_arm']), palm=tdir(fr, L), palm_w=0.3)
        set_arm(c, 'right_arm', t, lifted(mix(rest_arm('right_arm', fr), right, rw), 0.6 * sigh))
        c.key('head', t, qeuler(x=8.0 * held + 12.0 * bump(t, 19.0, 28.0) - 5.0 * bump(t, 9.0, 18.0),
                                y=wave(t, 27.0, 36.0, 4.5, 12.0)), ease='linear')
    c.mode('head', 'add')
    la.TRACKS['empty_basket'] = {'basket_turned': lambda t, pivot: (eb2_M(t)[:3, :3], eb2_M(t)[:3, 3], None)}
    for k in (8.0, 15.0, 25.0):
        for side in ('left_arm', 'right_arm'):
            check(c, f'{side.replace("_arm", "")} fist on the basket\'s end (t={k:g})', ta.fk(c, k, side, GRIP[side]),
                  apply(eb2_M(k), EB2_ENDS[side]), tol=0.8)
    joins_idle(c)
    return c

# ---- haggle (50): bargaining at a stall: the left hand comes up before the chest, palm in, and the right counts points
# off on its fingers, one, two, three (count), with a nod to each and a lean in; a pause, the head on one side; then the
# right hand flat, palm down, swept out to the side (no) as the head shakes, and both hands down.
HG_LEN = 50
HG_COUNTS = (10.0, 15.0, 20.0)
HG_NO = 30.5
HG_LEFT_AT = np.array([2.4, 3.0, -9.0])         # torso space: the left hand up before the chest
HG_LEFT = [(0, 0.0), (2.0, 0.0), (6.5, 1.0), (24.0, 1.0), (28.5, 0.0), (50, 0.0)]
HG_RIGHT = [(0, 0.0), (3.0, 0.0), (7.5, 1.0), (35.0, 1.0), (41.0, 0.0), (50, 0.0)]
HG_FINGER = [(0, 0.0), (11.5, 0.0), (13.5, 1.0), (16.5, 1.0), (18.5, 2.0), (50, 2.0)]
HG_P = [(0, 0.0), (6.0, 4.0), (10.0, 7.0), (20.0, 8.0), (25.0, 3.0), (29.0, 1.0), (31.0, -3.0), (36.0, -2.0), (42.0, 0.0),
        (50, 0.0)]


def hg_right_target(t, fr, left_at):
    """The right fist: tapping the left's fingers, one further along at each count; then flat before the chest and swept
    out to the right (no)."""
    tap = sum(bump(t, k - 1.4, k + 1.0) for k in HG_COUNTS)
    finger = left_at + tdir(fr, np.array([-2.0 + 1.0 * K(HG_FINGER, t), -1.6, -0.2]))
    count = finger + tdir(fr, np.array([-1.4, 0.6, 0.8])) * (1.0 - tap)
    if t <= 25.0:
        return count
    w = smooth((t - 25.0) / 3.5)
    sweep = smooth((t - 28.5) / 3.0)
    flat = torso_to_world(fr, np.array([-1.6, 4.4, -9.0]) * (1.0 - sweep) + np.array([-11.0, 5.0, -6.0]) * sweep)
    return count * (1.0 - w) + flat * w


def haggle():
    c = la.clip('haggle', HG_LEN, False, look=('citizen', 5), station_='bare_stall',
                events=[('count', k) for k in HG_COUNTS] + [('no', HG_NO)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    for t in la.every(HG_LEN):
        pitch = K(HG_P, t)
        fr = F_(qt=bend(pitch, -6.0 * bump(t, 28.0, 38.0), 0.0), vt=(0.0, sink_for(pitch), 0.0), pr=la.root_for(FEET))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        left_at = torso_to_world(fr, HG_LEFT_AT)
        left = reach_arm(holds, 'left_arm', fr, left_at, palm=tdir(fr, unit(B + 0.4 * R)), palm_w=0.3)
        set_arm(c, 'left_arm', t, mix(rest_arm('left_arm', fr), left, smooth(K(HG_LEFT, t))))
        palm = tdir(fr, L) if t < 26.0 else D
        right = reach_arm(holds, 'right_arm', fr, hg_right_target(t, fr, left_at), palm=palm, palm_w=0.3)
        set_arm(c, 'right_arm', t, mix(rest_arm('right_arm', fr), right, smooth(K(HG_RIGHT, t))))
        nods = sum(6.0 * bump(t, k - 1.0, k + 2.2) for k in HG_COUNTS)
        c.key('head', t, qeuler(x=5.0 * smooth(K(HG_LEFT, t)) + nods - 5.0 * bump(t, 22.0, 28.0),
                                y=wave(t, 30.0, 40.0, 3.4, 16.0), z=10.0 * bump(t, 22.0, 28.5)), ease='linear')
    c.mode('head', 'add')
    for k in HG_COUNTS:
        fr = ta.frame_at(c, k)
        check(c, f'right fist on a finger of the left (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']),
              ta.fk(c, k, 'left_arm', GRIP['left_arm']) + tdir(fr, np.array([-2.0 + 1.0 * K(HG_FINGER, k), -1.6, -0.2])), tol=1.0)
    joins_idle(c)
    return c

# ---- shut_stall (30, once): the keeper of a stall with nothing to sell, behind the counter: a look up at the flap
# rolled under the awning over the counter's back edge, a step in and the right hand up to its cord; a tug (pull: the
# tie gives and the canvas comes unrolling down, its batten swinging), a step back, the batten taken by its ends and
# hooked on the counter (hook); a look up and down it, and the hands dusted.
SS_LEN = 30
SS_PULL, SS_HOOK = 6.5, 17.5
SS_FREE, SS_FALL = 7.0, 3.4                     # the batten let go of, and the ticks it takes to drop
SS_Z = -8.0                                     # the flap hangs over the counter's back edge
SS_RAIL = np.array([0.0, -23.4, SS_Z])          # the rail's middle, under the awning
SS_ROLLED = -21.0                               # the batten's height rolled up, and hanging free
SS_HANG = 4.4
SS_HOOKED = 7.2                                 # ... and hooked on the counter's edge
SS_CORD = 15.6                                  # the cord, from the batten's middle
SS_STEPS = [(0.5, 3.0, 'right_leg', (-1.9, -2.6)), (2.5, 5.0, 'left_leg', (1.9, -2.6)),
            (8.0, 10.5, 'right_leg', (-1.9, 0.0)), (10.0, 12.5, 'left_leg', (1.9, 0.0))]
SS_RIGHT = [(0, 0.0), (1.5, 0.0), (5.0, 1.0), (7.5, 1.0), (10.0, 0.0), (11.5, 0.0), (14.0, 1.0), (19.0, 1.0), (22.5, 0.0),
            (30, 0.0)]
SS_LEFT = [(0, 0.0), (11.5, 0.0), (14.0, 1.0), (19.0, 1.0), (22.5, 0.0), (30, 0.0)]
SS_P = [(0, 0.0), (3.0, -5.0), (7.0, -3.0), (10.0, 2.0), (13.0, 5.0), (SS_HOOK, 4.0), (20.0, 3.0), (23.0, -4.0),
        (26.5, -1.0), (30, 0.0)]
SS_HX = [(0, 0.0), (3.0, -30.0), (6.5, -26.0), (9.0, -8.0), (12.0, 10.0), (SS_HOOK, 16.0), (20.5, 4.0), (23.5, -22.0),
         (26.5, -8.0), (30, 0.0)]


def ss_batten(t):
    """The batten's middle (and how far the canvas is off its roll, 0 to 1) at [t]: rolled up under the rail, the tug,
    dropping free with a bounce and a swing, drawn down and hooked on the counter."""
    tug = 0.6 * bump(t, SS_PULL - 1.0, SS_FREE + 0.4)
    if t <= SS_FREE:
        return np.array([0.0, SS_ROLLED + tug, SS_Z]), 0.0
    u = (t - SS_FREE) / SS_FALL
    fall = u * u if u < 1.0 else 1.0 + 0.06 * math.sin(math.pi * (u - 1.0) * 2.4) * math.exp(-(u - 1.0) * 2.2)
    y = SS_ROLLED + (SS_HANG - SS_ROLLED) * fall
    swing = 1.6 * math.sin(2 * math.pi * (t - SS_FREE - SS_FALL) / 5.0) * math.exp(-max(0.0, t - SS_FREE - SS_FALL) / 3.5) \
        if t > SS_FREE + SS_FALL else 0.0
    take = smooth((t - 13.0) / 1.5)
    swing *= 1.0 - take
    down = smooth((t - 14.5) / (SS_HOOK - 14.5))
    y = y + (SS_HOOKED - SS_HANG) * down
    return np.array([0.0, y, SS_Z + swing]), min(1.0, fall)


def ss_hands(t):
    """Where each fist is wanted (None: at rest): the right on the cord's toggle, then each on an end of the batten."""
    b, _ = ss_batten(t)
    toggle = np.array([-0.4, SS_ROLLED + 1.2 + SS_CORD, SS_Z + 0.4])
    if t < 9.0:
        tug = np.array([0.0, 2.6 * smooth((t - SS_PULL + 0.8) / 1.2), 0.8 * smooth((t - SS_PULL + 0.8) / 1.2)])
        return {'right_arm': toggle + tug, 'left_arm': None}
    lift = np.array([0.0, -1.0, 0.6]) * smooth((t - SS_HOOK - 0.5) / 2.0)
    return {'right_arm': b + np.array([-6.6, 0.4, 0.8]) + lift, 'left_arm': b + np.array([6.6, 0.4, 0.8]) + lift}


def shut_stall():
    c = la.clip('shut_stall', SS_LEN, False, look=('merchant', 1), tools_=('well_rope', *lp.FLAPS), station_='stall',
                events=[('pull', SS_PULL), ('hook', SS_HOOK)], traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))],
                views=((40, -14, True), (90, -2), (200, -16)))
    holds = la.Holds()
    for t in la.every(SS_LEN):
        pitch = K(SS_P, t)
        feet, lift = stepping(t, FEET, SS_STEPS, lift=1.0)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, z=0.5 * (feet['right_leg'][1] + feet['left_leg'][1]), dip=0.5 * bump(t, 12.0, 21.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        want = ss_hands(t)
        for side, keys in (('right_arm', SS_RIGHT), ('left_arm', SS_LEFT)):
            w = smooth(K(keys, t))
            rest = rest_arm(side, fr, fwd=4.0 * bump(t, 23.0, 29.0) * (1.0 + 0.5 * math.sin(2 * math.pi * t / 1.5)))
            if w > 1e-4 and want[side] is not None:
                pose = reach_arm(holds, side, fr, want[side], palm=F if want[side][1] < 0.0 else unit(D + F), palm_w=0.3, limit=1.8)
                rest = mix(rest, pose, w)
            set_arm(c, side, t, rest)
        c.key('head', t, qeuler(x=K(SS_HX, t)), ease='linear')
    c.mode('head', 'add')
    la.TRACKS['shut_stall'] = {'well_rope': lambda t, pivot, c=c: _ss_cord(c, t),
                               'flap_rail': lambda t, pivot: (np.eye(3), SS_RAIL, None),
                               'flap_cloth': lambda t, pivot: _ss_cloth(t), 'flap_batten': lambda t, pivot: _ss_roll(t)}
    for k in (5.0, SS_PULL):
        check(c, f'right fist on the cord\'s toggle (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']), ss_hands(k)['right_arm'], tol=0.8)
    for side in ('right_arm', 'left_arm'):
        check(c, f'{side.replace("_arm", "")} fist on the batten (t={SS_HOOK:g})', ta.fk(c, SS_HOOK, side, GRIP[side]),
              ss_hands(SS_HOOK)[side], tol=0.8)
    joins_idle(c)
    return c


def _ss_roll(t):
    b, off = ss_batten(t)
    s = 1.0 - 0.55 * off
    return np.eye(3), b, (1.0, s, s)


def _ss_cloth(t):
    b, off = ss_batten(t)
    top = SS_RAIL + np.array([0.0, 0.6, 0.0])
    foot = b - np.array([0.0, lp.FLAP_ROLL * (1.0 - 0.55 * off) * 0.9, 0.0])
    Rm, S = la.aim(top, foot, lp.FLAP_LEN)
    return Rm, top, S


def _ss_cord(c, t):
    b, off = ss_batten(t)
    top = b + np.array([0.0, lp.FLAP_ROLL * (1.0 - 0.55 * off), 0.0])
    if t < SS_FREE:
        end = apply(part_M(c, t, 'right_arm'), GRIP['right_arm'])
    else:
        sway = b[2] - SS_Z
        end = top + np.array([0.0, SS_CORD, -0.6 * sway])
        if t < SS_FREE + 2.0:            # let go of: it falls in after the batten
            held = apply(part_M(c, SS_FREE, 'right_arm'), GRIP['right_arm'])
            hung = ss_batten(SS_FREE)[0] + np.array([0.0, lp.FLAP_ROLL + SS_CORD, 0.0])
            end = end + (held - hung) * (1.0 - smooth((t - SS_FREE) / 2.0))
    Rm, S = la.aim(top, end, lp.ROPE_LEN)
    return Rm, top, S

# ================================================================== the alms table

# ---- ladle (30, once): the keeper behind the alms table (a noble of the town, or the innkeeper), a short step in: a bowl
# taken off the stack on the left by its near rim (take); the ladle taken up from where it stands leaning in the pot,
# straightened and pushed down to the bottom, stirred round (dip) and lifted out full; the bowl brought in under it, over
# the pot's near left edge, as the ladle goes out over the bowl and tips to the right (pour: the stew runs off its lip
# into the bowl); the ladle righted and stood back in the pot as it was; the bowl taken by its right rim too, another
# step in, and held out over the table's middle to whoever is across it (give: they take it from beneath, in their
# hold_out, which the game starts on the same tick as this), a step back and the hands down. The ladle stands in the pot
# at the start and the end just where the table's own model has it (works_art.py), so the game can hide the table's
# while this plays; the bowl's spoon comes with its stew.
LA_LEN = 30
LA_TAKE, LA_GRAB, LA_DIP, LA_POUR, LA_BACK, LA_GIVE = 3.5, 4.5, 7.5, 12.5, 17.5, 21.0
LA_POURING = (12.2, 13.9)                       # the stew runs off the lip
LA_FILLED = 13.4                                # the bowl shows its stew
LA_BOWL_PT = np.asarray(lp.LADLE_BOWL, float)   # ladle space: its bowl's middle, and its lip
LA_LIP_PT = np.asarray(lp.LADLE_LIP, float)
LA_CORNERS = [np.array([x, y, z]) for x in (-1.3, 1.3) for y in (5.4, 7.4) for z in (-0.4, 2.2)]   # its bowl's box
LA_FLOOR = 24.0 - wa.POT_INSIDE_Y               # clip y of the pot's floor inside, and of its rim
LA_RIM = 24.0 - wa.POT_RIM_Y
_pot = np.asarray(wa.POT_MOUTH, float)


def la_R(tip=0.0):
    """The ladle's turn: upright (the handle straight down from the fist to its bowl, the bowl's mouth up, its lip to the
    keeper's right), tipped [tip] degrees to the right about the line ahead (the bowl swinging out to the left of the
    fist, the lip going down): as it leans standing in the pot, and as it pours."""
    a = math.radians(tip)
    return np.column_stack([B, np.array([math.sin(a), math.cos(a), 0.0]), np.array([-math.cos(a), math.sin(a), 0.0])])


def la_low(R):
    """How far under its bowl's middle the ladle's lowest corner is, turned [R]."""
    return max(float((R @ (c_ - LA_BOWL_PT))[1]) for c_ in LA_CORNERS)


def la_lip_off(tip):
    """Its lip from its bowl's middle, tipped [tip]."""
    return la_R(tip) @ (LA_LIP_PT - LA_BOWL_PT)


LA_REST_R = la_R(wa.LADLE_LEAN)
_r = wa.clip_pt(wa.LADLE_AT[0], 0.0, wa.LADLE_AT[1], bz=-1)
LA_REST_P = np.array([_r[0], LA_FLOOR - wa.LADLE_CLEAR - la_low(LA_REST_R), _r[2]])   # its bowl's middle, standing
LA_REST_GRIP = LA_REST_P - LA_REST_R @ LA_BOWL_PT                                      # its handle's end
LA_DIP_P = np.array([_pot[0] + 0.2, LA_FLOOR - 0.2 - la_low(la_R()), _pot[2] - 0.2])    # upright at the bottom
# the bowl's bottom's middle held over the pot's near left edge to be filled, its rim's top; the ladle tipped over it,
# its lip over the bowl's middle and its lowest corner clear of the rim
LA_POUR_BOWL = np.array([0.9, LA_RIM - 0.5, -13.4])
LA_BOWL_RIM = LA_POUR_BOWL[1] - wa.BOWL_TALL
LA_POUR_TIP = 50.0
LA_POUR_P = np.array([LA_POUR_BOWL[0] + 0.2 - la_lip_off(LA_POUR_TIP)[0], LA_BOWL_RIM - 0.25 - la_low(la_R(LA_POUR_TIP)),
                      LA_POUR_BOWL[2] - 0.4])
# its way (t, its bowl's middle, its tip): taken up where it stands, straightened and pushed down to the bottom, stirred
# round, lifted out full, carried left over the bowl and tipped, righted, back over the pot and stood in it as it was
LA_KEYS = [(0.0, LA_REST_P, wa.LADLE_LEAN), (LA_GRAB, LA_REST_P, wa.LADLE_LEAN),
           (5.9, LA_DIP_P + np.array([0.3, -1.6, 0.2]), 8.0), (LA_DIP, LA_DIP_P, 0.0),
           (8.3, LA_DIP_P + np.array([0.7, -0.3, 0.7]), -3.0), (9.1, LA_DIP_P + np.array([-0.5, -0.6, 0.8]), 3.0),
           (10.3, np.array([_pot[0] + 0.9, LA_RIM - 0.4 - la_low(la_R(6.0)), _pot[2] + 0.2]), 6.0),
           (11.4, LA_POUR_P + np.array([-1.4, -0.6, 0.3]), 28.0), (12.3, LA_POUR_P, LA_POUR_TIP),
           (13.6, LA_POUR_P + np.array([-0.2, 0.3, 0.0]), LA_POUR_TIP + 12.0),
           (14.4, LA_POUR_P + np.array([-0.6, -0.3, 0.0]), 30.0),
           (15.6, np.array([_pot[0] + 1.0, LA_RIM - 2.8, _pot[2]]), 10.0),
           (16.7, LA_REST_P + np.array([0.2, -1.4, 0.0]), 18.0), (LA_BACK, LA_REST_P, wa.LADLE_LEAN),
           (LA_LEN, LA_REST_P, wa.LADLE_LEAN)]
LA_HOLD_L = np.array([0.0, -3.3, 3.0])          # bowl space: its near rim, where the left fist has it
LA_HOLD_R = np.array([-2.0, -3.3, 1.0])         # ... and its right rim, where the right takes it to hand it over
LA_HANDOVER = np.array([0.4, 4.0, -wa.ALMS_GAP / 2])        # its bottom's middle where it changes hands
# the bowl's way (its bottom's middle): lifted off the stack, held to the side over it while the ladle dips, brought in
# under the ladle, a little dip as the stew lands, drawn up and held out over the table's middle
LA_BOWL = [(LA_TAKE, wa.BOWL_TAKEN), (4.6, wa.BOWL_TAKEN + np.array([-0.2, -1.6, 0.2])), (7.0, np.array([3.4, 6.4, -12.8])),
           (10.0, np.array([3.3, 6.3, -12.9])), (11.6, np.array([1.8, LA_POUR_BOWL[1], -13.2])), (12.3, LA_POUR_BOWL),
           (13.2, LA_POUR_BOWL + np.array([0.0, 0.3, 0.0])), (14.2, LA_POUR_BOWL + np.array([0.0, -0.1, 0.0])),
           (16.5, np.array([0.8, 4.6, -13.4])), (18.6, np.array([0.6, 4.3, -14.2])), (LA_GIVE, LA_HANDOVER),
           (22.0, LA_HANDOVER)]
LA_RW = [(0, 0.0), (1.0, 0.0), (LA_GRAB, 1.0), (LA_GIVE + 0.4, 1.0), (25.0, 0.0), (30, 0.0)]
LA_LW = [(0, 0.0), (0.3, 0.0), (LA_TAKE, 1.0), (LA_GIVE + 0.4, 1.0), (25.5, 0.0), (30, 0.0)]
LA_STEPS = [(0.3, 2.6, 'right_leg', (-1.9, -1.2)), (2.0, 4.3, 'left_leg', (1.9, -1.2)),
            (17.6, 19.8, 'left_leg', (1.9, -3.2)), (19.0, 21.0, 'right_leg', (-1.9, -3.2)),
            (23.5, 26.0, 'right_leg', (-1.9, 0.0)), (25.2, 27.7, 'left_leg', (1.9, 0.0))]
LA_P = [(0, 0.0), (3.0, 11.0), (LA_TAKE, 12.0), (LA_DIP, 14.0), (10.3, 13.0), (LA_POUR, 14.0), (15.0, 12.0),
        (LA_BACK, 13.0), (LA_GIVE, 20.0), (22.5, 15.0), (25.5, 6.0), (28.0, 1.0), (30, 0.0)]
LA_YAW = [(0, 0.0), (LA_TAKE, 6.0), (LA_DIP, -6.0), (10.3, -8.0), (LA_POUR, -12.0), (15.0, -8.0), (LA_BACK, -4.0),
          (LA_GIVE, 0.0), (30, 0.0)]
_taker = np.array([0.0, -2.0, -wa.ALMS_GAP])
LA_LOOK = [(0.0, np.array([0.0, 2.0, -40.0])), (1.0, wa.BOWL_TAKEN + np.array([0.0, -2.0, 0.0])),
           (3.4, wa.BOWL_TAKEN + np.array([0.0, -2.0, 0.0])), (5.0, _pot + np.array([0.0, 2.0, 0.0])),
           (9.6, _pot + np.array([0.0, 2.0, 0.0])), (11.2, LA_POUR_BOWL + np.array([0.0, -2.0, 0.0])),
           (14.3, LA_POUR_BOWL + np.array([0.0, -2.0, 0.0])), (16.2, _pot), (18.2, LA_HANDOVER), (19.4, _taker),
           (24.0, _taker), (27.5, np.array([0.0, 2.0, -40.0])), (30, np.array([0.0, 2.0, -40.0]))]


def la_pose(t):
    """The ladle's turn and its bowl's middle at [t], as they are wanted (the fist goes where they put its handle's end)."""
    P = path([(k, p) for k, p, _ in LA_KEYS], t)
    return la_R(K([(k, a) for k, _, a in LA_KEYS], t)), P


def la_hands(t):
    """Where each fist is wanted at [t] (before the take and the grab: where they will be)."""
    Pb = path(LA_BOWL, t)
    left = Pb + LA_HOLD_L - BOWL_BASE
    R, P = la_pose(t)
    ladle = P - R @ LA_BOWL_PT
    rim = Pb + LA_HOLD_R - BOWL_BASE
    right = ladle + (rim - ladle) * smooth((t - LA_BACK) / 1.1)
    off = smooth((t - LA_GIVE - 0.3) / 2.0)             # let go of: the hands open off it
    return {'left_arm': left + np.array([1.0, -0.8, 1.6]) * off, 'right_arm': right + np.array([-1.0, -0.8, 1.6]) * off}


def ladle():
    c = la.clip('ladle', LA_LEN, False, look=('noble', 2),
                tools_=('ladle', 'ladle_full', 'soup_pour', 'bowl_empty', 'bowl', 'spoon'),
                station_='alms_keeper', events=[('take', LA_TAKE), ('dip', LA_DIP), ('pour', LA_POUR), ('give', LA_GIVE)],
                traces=[('ladle', tuple(LA_BOWL_PT), (230, 190, 140))], views=((40, -14), (90, -4), (-40, -16)))
    holds = la.Holds()
    for t in la.every(LA_LEN):
        pitch = K(LA_P, t)
        feet, lift = stepping(t, FEET, LA_STEPS, lift=1.0)
        fr = F_(qt=bend(pitch, K(LA_YAW, t), 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, z=0.5 * (feet['right_leg'][1] + feet['left_leg'][1]), dip=0.5 * bump(t, 2.0, 27.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        want = la_hands(t)
        for side, keys, palm in (('left_arm', LA_LW, tdir(fr, F)), ('right_arm', LA_RW, tdir(fr, L))):
            w = smooth(K(keys, t))
            pose = rest_arm(side, fr)
            if w > 1e-4:
                pose = mix(pose, reach_arm(holds, side, fr, want[side], palm=palm, palm_w=0.15, limit=1.8), w)
            set_arm(c, side, t, pose)
        look = path(LA_LOOK, t)
        c.key('head', t, qslerp(I, head_at(fr, look), 0.8 * bump(t, 0.0, 30.0) ** 0.4), ease='linear')
    c.mode('head', 'set')
    track = lambda t, pivot, c=c: _la_track(c, t)
    bowl = lambda t, pivot, c=c: (level(), _la_bowl_pivot(c, t), None)
    la.TRACKS['ladle'] = {'ladle': track, 'ladle_full': track, 'soup_pour': lambda t, pivot, c=c: _la_pour(c, t),
                          'bowl_empty': bowl, 'bowl': bowl,
                          'spoon': lambda t, pivot, c=c: (*spoon_in(_la_bowl_pivot(c, t) + level() @ BOWL_BASE, keeper=True), None)}
    ta.INFO['ladle']['tools_at'] = tools_when([('ladle', 0.0, LA_DIP + 0.5), ('ladle_full', LA_DIP + 0.5, LA_POURING[1]),
                                               ('ladle', LA_POURING[1], None), ('soup_pour', *LA_POURING),
                                               ('bowl_empty', LA_TAKE, LA_FILLED), ('bowl', LA_FILLED, LA_GIVE),
                                               ('spoon', LA_FILLED, LA_GIVE)])
    ta.INFO['ladle']['partner'] = ('hold_out', ('citizen', 4))
    PARTNER_PLACE['ladle'] = (T(0.0, 0.0, -wa.ALMS_GAP) @ FACING, 0.0)
    check(c, f'a bowl off the stack (t={LA_TAKE:g})', ta.fk(c, LA_TAKE, 'bowl_empty', BOWL_BASE), wa.BOWL_TAKEN, tol=0.6)
    for k in (LA_GRAB, LA_BACK):
        check(c, f'right fist on the ladle where it stands (t={k:g})', ta.fk(c, k, 'right_arm', GRIP['right_arm']),
              LA_REST_GRIP, tol=0.6)
    got = _la_world(c, LA_DIP, LA_BOWL_PT)
    check(c, f'the ladle in the stew (t={LA_DIP:g}: under its lowest level, inside the pot, over its floor)',
          (max(0.0, (24.0 - wa.pot_level_y(1)) - 0.6 - got[1]),
           max(0.0, float(np.hypot(got[0] - _pot[0], got[2] - _pot[2])) - (wa.POT_R - 1.9)),
           max(0.0, max(_la_world(c, LA_DIP, q)[1] for q in LA_CORNERS) - LA_FLOOR)), (0.0, 0.0, 0.0), tol=0.15)
    for k in (12.5, 13.5):
        lip, stew = _la_world(c, k, LA_LIP_PT), ta.fk(c, k, 'bowl_empty', BOWL_TOP)
        check(c, f'the ladle\'s lip over the bowl, above its rim (t={k:g}: x, z off its middle; under the rim)',
              (float(np.hypot(*(lip - stew)[[0, 2]])), max(0.0, lip[1] - stew[1] + 0.5)), (0.0, 0.0), tol=1.4)
        low = max(_la_world(c, k, q)[1] for q in LA_CORNERS)
        check(c, f'the ladle clear of the bowl (t={k:g}: its lowest corner under the rim)', (max(0.0, low - stew[1] + 0.1),),
              (0.0,), tol=0.15)
    check(c, f'the bowl over the table\'s middle (t={LA_GIVE:g})', ta.fk(c, LA_GIVE - 0.01, 'bowl', BOWL_BASE),
          LA_HANDOVER, tol=0.6)
    for side, hold in (('left_arm', LA_HOLD_L), ('right_arm', LA_HOLD_R)):
        check(c, f'{side.replace("_arm", "")} fist on the bowl\'s rim (t={LA_GIVE:g})', ta.fk(c, LA_GIVE, side, GRIP[side]),
              LA_HANDOVER + hold - BOWL_BASE, tol=0.8)
    joins_idle(c)
    return c


def _la_track(c, t):
    """The ladle: its turn as wanted, its handle's end standing in the pot or in the fist."""
    R, _ = la_pose(t)
    hand = apply(part_M(c, t, 'right_arm'), ta.tool_local('ladle')[:3, 3])
    w = smooth((t - LA_GRAB + 0.3) / 0.6) * (1.0 - smooth((t - LA_BACK + 0.1) / 0.6))
    return R, LA_REST_GRIP * (1.0 - w) + hand * w, None


def _la_world(c, t, p):
    R, P, _ = _la_track(c, t)
    return P + R @ np.asarray(p, float)


def _la_bowl_pivot(c, t):
    """The bowl hangs from the left fist by its near rim."""
    return apply(part_M(c, t, 'left_arm'), GRIP['left_arm']) - level() @ LA_HOLD_L


def _la_pour(c, t):
    """The stew poured: from the ladle's lip to the stew in the bowl, its foot reaching the bowl as it starts and its
    head leaving the lip as it stops."""
    lip = _la_world(c, t, LA_LIP_PT)
    stew = _la_bowl_pivot(c, t) + level() @ BOWL_TOP
    a = smooth((t - LA_POURING[0]) / 0.6)
    b = smooth((t - LA_POURING[1] + 0.6) / 0.6)
    top = lip + (stew - lip) * b
    foot = lip + (stew - lip) * a
    Rm, S = la.aim(top, foot, lp.ROPE_LEN)
    return Rm, top, S

# ---- hold_out (30, once, its last frame held): at the alms table, the game starting it with the keeper's ladle (the
# two on the same tick, so they meet over the table): the hands cupped together and held out over the table's near edge
# to the keeper across it, a small bow of the head and a hopeful look up; a step in and a lean as the keeper brings the
# filled bowl over the table's middle, the hands in under it, and it taken from beneath (take: the keeper's give), drawn
# in a little and held (take_bowl starts where this ends).
HD2_LEN = 30
HD2_TAKE = LA_GIVE                              # 21: the keeper lets go of it
HD2_OUT = [(0, 0.0), (3.0, 0.0), (10.0, 1.0), (30, 1.0)]
HD2_P = [(0, 0.0), (4.0, 2.0), (10.0, 9.0), (13.0, 11.0), (16.0, 8.0), (19.0, 17.0), (HD2_TAKE, 20.0), (24.5, 14.0),
         (30, 13.0)]
HD2_HX = [(0, 0.0), (5.0, 10.0), (10.0, 6.0), (13.0, 14.0), (16.5, -6.0), (19.0, 6.0), (22.0, 12.0), (26.0, 0.0),
          (30, -2.0)]
HD2_HANDS = {'right_arm': np.array([-1.1, 6.6, -10.4]), 'left_arm': np.array([1.1, 6.6, -10.4])}
HD2_STEPS = [(14.0, 16.8, 'left_leg', (1.9, -3.0)), (15.6, 18.4, 'right_leg', (-1.9, -3.0))]
TB_HANDOVER = np.array([-LA_HANDOVER[0], LA_HANDOVER[1], -wa.ALMS_GAP - LA_HANDOVER[2]])
TB_UNDER = {'right_arm': np.array([-1.6, 2.0, 0.5]), 'left_arm': np.array([1.6, 2.0, 0.5])}    # the fists under it
HD2_HELD = TB_HANDOVER + np.array([0.3, 0.2, 2.4])      # its bottom's middle, drawn in a little and held


def hd2_bob(t):
    return np.array([0.0, 0.3 * bump(t, 9.0, 16.0) * math.sin(2 * math.pi * (t - 9.0) / 7.0), 0.0])


def hd2_bowl_path(t):
    """The bowl's bottom's middle once it's taken: drawn in a little and held."""
    return path([(HD2_TAKE, TB_HANDOVER), (24.5, HD2_HELD), (HD2_LEN, HD2_HELD)], t)


def spoon_in(base, keeper=False):
    """The spoon stood in a bowl of stew (whose bottom's middle is [base]), leaning back toward whoever eats from it
    ([keeper]: as the keeper holds it out, across the table from them): its turn, and its pivot (its handle's end)."""
    if keeper:
        Rs = frame_of(unit(np.array([-0.55, 1.0, -0.25])), F)
        off = np.array([-0.4, 0.4, 0.0])
    else:
        Rs = frame_of(unit(np.array([0.55, 1.0, 0.25])), B)
        off = np.array([0.4, 0.4, 0.0])
    stew = base + level() @ (BOWL_TOP - BOWL_BASE) + off
    return Rs, stew - Rs @ SPOON_BOWL


def hold_out():
    c = la.clip('hold_out', HD2_LEN, False, look=('citizen', 4), tools_=('bowl', 'spoon'), station_='alms_taker',
                events=[('offer', 10.0), ('take', HD2_TAKE)], traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))],
                views=((32, -10), (90, -2), (150, -14)))
    holds = la.Holds()
    for t in la.every(HD2_LEN):
        pitch = K(HD2_P, t)
        w = smooth(K(HD2_OUT, t))
        feet, lift = stepping(t, FEET, HD2_STEPS, lift=1.0)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, z=0.5 * (feet['right_leg'][1] + feet['left_leg'][1]) - 0.4 * w,
                               dip=0.4 * bump(t, 13.0, 30.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        base = hd2_bowl_path(t)
        reach = smooth((t - 15.0) / 4.5)
        hold = smooth((t - HD2_TAKE + 1.6) / 1.6)
        for side in ('right_arm', 'left_arm'):
            want = (HD2_HANDS[side] + hd2_bob(t)) * (1.0 - reach) + \
                (TB_HANDOVER + TB_UNDER[side] + np.array([0.0, 0.8, 0.6])) * reach
            want = want * (1.0 - hold) + (base + TB_UNDER[side]) * hold
            pose = reach_arm(holds, side, fr, want, palm=U, palm_w=0.5)
            set_arm(c, side, t, mix(rest_arm(side, fr), pose, w))
        c.key('head', t, head_level(fr, K(HD2_HX, t)), ease='linear')
    c.mode('head', 'set')
    la.TRACKS['hold_out'] = {'bowl': lambda t, pivot: (level(), hd2_bowl_path(t) - level() @ BOWL_BASE, None),
                             'spoon': lambda t, pivot: (*spoon_in(hd2_bowl_path(t)), None)}
    ta.INFO['hold_out']['tools_at'] = tools_when([('bowl', HD2_TAKE, None), ('spoon', HD2_TAKE, None)])
    ta.INFO['hold_out']['partner'] = ('ladle', ('noble', 2))
    PARTNER_PLACE['hold_out'] = (T(0.0, 0.0, -wa.ALMS_GAP) @ FACING, 0.0)
    joins_idle(c, end=False)
    for side in ('right_arm', 'left_arm'):
        check(c, f'{side.replace("_arm", "")} hand held out (t=12)', ta.fk(c, 12.0, side, GRIP[side]),
              HD2_HANDS[side] + hd2_bob(12.0), tol=0.8)
        for k in (HD2_TAKE, HD2_LEN):
            check(c, f'{side.replace("_arm", "")} hand under the bowl (t={k:g})', ta.fk(c, k, side, GRIP[side]),
                  hd2_bowl_path(k) + TB_UNDER[side], tol=0.8)
    check(c, f'the bowl taken over the table\'s middle (t={HD2_TAKE:g})', ta.fk(c, HD2_TAKE, 'bowl', BOWL_BASE), TB_HANDOVER,
          tol=0.6)
    return c

# ---- take_bowl (24, once): from hold_out's last frame (the bowl just taken over the table, held out in both hands):
# drawn in to the chest, a step back, a small bow of thanks (bow), and the spoon taken out of the bowl; it ends on
# eat_bowl's first frame.
TB_LEN = 24
TB_BOW = 9.5
TB_SPOON = 17.0
TB_STEPS = [(2.5, 5.3, 'right_leg', (-1.9, 0.0)), (4.2, 7.0, 'left_leg', (1.9, 0.0))]
TB_FEET = {'right_leg': (-1.9, -3.0), 'left_leg': (1.9, -3.0)}      # where hold_out's step in left them
TB_P = [(0, 13.0), (4.0, 9.0), (7.0, 11.0), (TB_BOW, 17.0), (12.5, 9.0), (17.0, 7.0), (24, 6.0)]


def tb_bowl_path(t):
    """The bowl's bottom's middle: from where hold_out holds it, drawn in and up to where eat_bowl holds it."""
    keys = [(0.0, HD2_HELD), (6.5, EB_BOWL_AT[0][1] + np.array([-0.2, 1.6, -0.8])),
            (12.0, EB_BOWL_AT[0][1] + np.array([0.0, 0.4, -0.2])), (TB_LEN, EB_BOWL_AT[0][1])]
    return path(keys, t)


def take_bowl():
    ho, eat = la.getc('hold_out'), la.getc('eat_bowl')
    start, end = pose_keys(ho, ho.length), pose_keys(eat, 0.0)
    c = la.clip('take_bowl', TB_LEN, False, look=('citizen', 4), tools_=('bowl', 'spoon'), station_='alms_taker',
                events=[('bow', TB_BOW)], traces=[('bowl', tuple(BOWL_BASE), (220, 190, 140))],
                views=((32, -10), (90, -2), (150, -14)))
    holds = la.Holds()
    for t in la.every(TB_LEN):
        pitch = K(TB_P, t)
        feet, lift = stepping(t, TB_FEET, TB_STEPS, lift=1.0)
        fr = F_(qt=bend(pitch, 0.0, 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, z=0.5 * (feet['right_leg'][1] + feet['left_leg'][1])))
        w0 = 1.0 - smooth(t / 2.0)
        w1 = smooth((t - 20.5) / 3.5)
        fr = frame_toward(frame_toward(fr, start, w0), end, w1)
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        base = tb_bowl_path(t)
        poses = {}
        for side in ('right_arm', 'left_arm'):
            want = base + TB_UNDER[side]
            if side == 'left_arm':          # it settles onto the left hand as eat_bowl holds it
                want = want + (base - level() @ BOWL_BASE - want) * smooth((t - 13.0) / 4.0)
            else:                           # the right lets go and takes the spoon
                want = want + (spoon_in(base)[1] - want) * smooth((t - 12.5) / 3.5)
            palm = U if (side == 'left_arm' or t < 12.5) else L
            poses[side] = reach_arm(holds, side, fr, want, palm=palm, palm_w=0.35)
        for side, pose in poses.items():
            set_arm(c, side, t, arm_toward(arm_toward(pose, start, side, w0), end, side, w1))
        q = head_level(fr, -2.0 + 20.0 * bump(t, 7.5, 12.5) + 8.0 * bump(t, 0.5, 6.0))
        q = qslerp(qslerp(q, start[1]['head'][0], w0), end[1]['head'][0], w1)
        c.key('head', t, q, ease='linear')
    c.mode('head', 'set')
    la.TRACKS['take_bowl'] = {'bowl': lambda t, pivot, c=c: _tb_bowl(c, t), 'spoon': lambda t, pivot, c=c: _tb_spoon(c, t)}
    ta.INFO['take_bowl']['tools_at'] = tools_when([('bowl', 0.0, None), ('spoon', 0.0, None)])
    ta.INFO['take_bowl']['partner'] = ('ladle', ('noble', 2))
    PARTNER_PLACE['take_bowl'] = (T(0.0, 0.0, -wa.ALMS_GAP) @ FACING, -float(LA_LEN))
    la.join('take_bowl starts where hold_out ends', ho, ho.length, c, 0, tol=0.1)
    la.join('take_bowl ends on eat_bowl', c, TB_LEN, eat, 0, tol=0.1)
    check(c, 'the bowl where hold_out left it (t=0)', ta.fk(c, 0.0, 'bowl', BOWL_BASE), HD2_HELD, tol=0.3)
    check(c, f'right fist on the spoon (t={TB_SPOON:g})', ta.fk(c, TB_SPOON, 'right_arm', GRIP['right_arm']),
          spoon_in(ta.fk(c, TB_SPOON, 'bowl', BOWL_BASE))[1], tol=0.8)
    return c


def _tb_bowl(c, t):
    """Level, on the hands under it as it's drawn in; settling onto the left hand as eat_bowl holds it."""
    on_hand = apply(part_M(c, t, 'left_arm'), ta.tool_local('bowl')[:3, 3])
    w = smooth((t - 13.0) / 4.0)
    return level(), (tb_bowl_path(t) - level() @ BOWL_BASE) * (1.0 - w) + on_hand * w, None


def _tb_spoon(c, t):
    base = _tb_bowl(c, t)[1] + level() @ BOWL_BASE
    Rs, Ps = spoon_in(base)
    M = part_M(c, t, 'right_arm') @ ta.tool_local('spoon')
    w = smooth((t - TB_SPOON + 0.5) / 2.0)
    return blend_R(Rs, M[:3, :3], w), Ps * (1.0 - w) + M[:3, 3] * w, None

# ---- eat_bowl (60, loop): eating the stew standing, the bowl held up level on the left hand before the chest, the spoon
# in the right: dipped, a scoop, up to the mouth as the head comes down to meet it (spoon), chewed, the eyes going about;
# the second spoonful a little more hungrily, the bowl lifted towards it.
EB_LEN = 60
EB_SPOONS = (16.0, 46.0)
EB_BOWL_AT = [(0, (1.6, 4.4, -8.6)), (12.0, (1.4, 3.8, -8.4)), (20.0, (1.6, 4.4, -8.6)), (40.0, (1.3, 3.6, -8.2)),
              (48.0, (1.2, 3.2, -8.0)), (54.0, (1.6, 4.4, -8.6)), (60, (1.6, 4.4, -8.6))]     # its bottom's middle
EB_P = [(0, 7.0), (4.0, 8.0), (10.0, 11.0), (16.0, 15.0), (20.0, 10.0), (30.0, 7.0), (34.0, 8.0), (40.0, 12.0), (46.0, 17.0),
        (50.0, 11.0), (60, 7.0)]
EB_HP = [(0, 18.0), (5.0, 22.0), (10.0, 12.0), (15.0, 3.0), (17.0, 2.0), (20.0, 8.0), (26.0, 16.0), (30.0, 18.0), (35.0, 22.0),
         (40.0, 10.0), (45.0, 1.0), (47.0, 0.0), (50.0, 8.0), (56.0, 16.0), (60, 18.0)]
EB_HY = [(0, -2.0), (10.0, -6.0), (16.0, -9.0), (24.0, 8.0), (30.0, -2.0), (40.0, -7.0), (46.0, -9.0), (54.0, 6.0), (60, -2.0)]
EB_MOUTH = [(0, 0.0), (9.0, 0.0), (15.0, 1.0), (17.0, 1.0), (21.0, 0.0), (39.0, 0.0), (45.0, 1.0), (47.0, 1.0), (51.0, 0.0),
            (60, 0.0)]
EB_STEW = [(0, (-0.6, -3.0, 1.4)), (3.0, (0.4, 0.5, -0.8)), (6.0, (0.0, 0.4, 0.8)), (9.0, (-0.4, -2.4, 1.8)),
           (21.0, (-0.8, -3.4, 1.6)), (30.0, (-0.6, -3.0, 1.4)), (33.0, (0.4, 0.5, -0.8)), (36.0, (0.0, 0.4, 0.8)),
           (39.0, (-0.4, -2.4, 1.8)), (51.0, (-0.8, -3.4, 1.6)), (60, (-0.6, -3.0, 1.4))]
EB_AXIS_BOWL = v(f=0.35, u=-0.75, r=-0.55)
EB_AXIS_MOUTH = v(f=-0.6, u=0.2, r=-0.8)


def eat_bowl():
    c = la.clip('eat_bowl', EB_LEN, True, look=('citizen', 4), tools_=('bowl', 'spoon'), events=[('spoon', k) for k in EB_SPOONS],
                traces=[('spoon', tuple(SPOON_BOWL), (240, 220, 160))], views=((40, -14), (90, -2), (0, -6)))
    arms = la.Holds()
    times = la.every(EB_LEN)
    for lap in range(2):
        for t in times:
            sway = 0.5 * math.sin(2 * math.pi * (t - 8.0) / EB_LEN)
            pitch = K(EB_P, t, EB_LEN)
            fr = F_(qt=bend(pitch, 0.0, 0.8 * math.sin(2 * math.pi * t / EB_LEN)), vt=(0.0, sink_for(pitch), 0.0),
                    pr=la.root_for(FEET, x=sway, dip=0.4))
            base = np.asarray(KV(EB_BOWL_AT, t, EB_LEN), float)
            lpose = reach_arm(arms, 'left_arm', fr, base - level() @ BOWL_BASE, palm=U, palm_w=0.5)
            stew = base + level() @ (BOWL_TOP - BOWL_BASE)
            chewing = la.chew(t, 17.0, 32.0) + la.chew(t, 47.0, 60.0, period=6.0) + la.chew(t, -13.0, 2.0, period=6.0)
            q_head = qeuler(x=K(EB_HP, t, EB_LEN) + chewing, y=K(EB_HY, t, EB_LEN))
            wm = smooth(K(EB_MOUTH, t))
            lips = ta.head_point(fr, q_head, LIPS) + np.array([0.0, 0.0, 0.4])
            target = (stew + np.asarray(KV(EB_STEW, t, EB_LEN), float)) * (1.0 - wm) + lips * wm
            axis = unit(EB_AXIS_BOWL * (1.0 - wm) + EB_AXIS_MOUTH * wm)
            pose = arms.slack('right_arm', fr, [(tool_point('spoon', SPOON_BOWL), target, 3.0)],
                              [(tool_dir('spoon', (0, 1, 0)), axis, 0.5 - 0.25 * wm)], limit=2.4)
            if lap:
                key_frame(c, t, fr, ease='linear')
                stand(c, t, fr)
                set_arm(c, 'left_arm', t, lpose)
                la.key_arm(c, 'right_arm', t, pose)
                c.key('head', t, q_head, ease='linear')
    c.mode('head', 'set')
    seal(c)
    la.TRACKS['eat_bowl'] = {'bowl': lambda t, pivot: (level(), None, None)}
    for k in EB_SPOONS:
        fr = ta.frame_at(c, k)
        check(c, f'spoon at the lips (t={k:g})', ta.fk(c, k, 'spoon', SPOON_BOWL),
              ta.head_point(fr, c.sample('head', k)[0], LIPS) + np.array([0.0, 0.0, 0.4]), tol=0.8)
    for k in (0.0, 30.0):
        check(c, f'the bowl on the left hand (t={k:g})', ta.fk(c, k, 'bowl', BOWL_BASE), KV(EB_BOWL_AT, k, EB_LEN), tol=0.6)
    return c


# ================================================================== comings and goings

# ---- pack (40, once): packing the handcart at the door, standing at its tail (it rests tipped forward on its shafts a
# block ahead, the tail toward the packer): a stoop to the bundle on the ground ahead and to the left, knees bent, both
# hands taking it by its sides; lifted (lift) to the chest, a step in, heaved up over the tail board and pushed onto the
# bed (set), let go of; a step back, up straight, the hands dusted off.
PK_LEN = 40
PK_LIFT, PK_SET = 10.0, 25.0
PK_TILT = hc.parked_tilt()
PK_AXLE = np.array([0.0, 24.0 - hc.R, -19.0])   # the cart's axle (its origin), its tail toward the packer
PK_SLOT = hc.SLOTS[5][:2]                       # cart (x, z): where on the bed the bundle goes (the tail, to the left):
PK_SLOT_YAW = hc.SLOTS[5][2]                    # place 5, turned as it is, where a household's heap has its second
                                                # bundle (HandcartMesh.HOUSEHOLD): the cart's own takes over there


def cart_R():
    """The cart's turn as it rests tipped forward on its shafts (cart space to clip space)."""
    th = math.radians(PK_TILT)
    c_, s_ = math.cos(th), math.sin(th)
    return np.array([[1.0, 0.0, 0.0], [0.0, c_, -s_], [0.0, s_, c_]])


def cart_pt(x, y, z):
    return PK_AXLE + cart_R() @ np.array([x, y, z], float)


PK_ON_CART = cart_pt(PK_SLOT[0], hc.FLOOR_TOP, PK_SLOT[1])         # the bundle's underside's middle on the bed
PK_TAIL_TOP = cart_pt(0.0, hc.SIDE_TOP, hc.BED_BACK + 0.5)          # the top of the tail board
PK_GROUND = np.array([3.0, 24.0, -6.8])                             # the bundle on the ground to start with
PK_BUNDLE = [(0, PK_GROUND), (PK_LIFT - 0.5, PK_GROUND), (PK_LIFT + 0.5, PK_GROUND + np.array([0.0, -0.8, 0.0])),
             (14.0, np.array([1.2, 11.0, -6.6])), (17.5, np.array([1.0, 6.4, -9.8])),
             (21.0, PK_TAIL_TOP + np.array([1.2, -1.8, 0.4])), (23.0, PK_ON_CART + np.array([0.0, -2.4, 2.6])),
             (PK_SET, PK_ON_CART + np.array([0.0, -0.3, 0.6])), (26.5, PK_ON_CART)]
PK_HANDS = [(0, 0.0), (3.0, 0.0), (PK_LIFT - 1.0, 1.0), (26.5, 1.0), (30.0, 0.0), (40, 0.0)]
PK_P = [(0, 0.0), (4.0, 22.0), (PK_LIFT - 1.0, 44.0), (PK_LIFT, 44.0), (14.0, 14.0), (17.5, 4.0), (21.0, 10.0),
        (PK_SET, 18.0), (27.0, 16.0), (31.0, 4.0), (34.0, -3.0), (37.0, 1.0), (40, 0.0)]
PK_DIP = [(0, 0.0), (4.0, 1.6), (PK_LIFT - 1.0, 4.4), (PK_LIFT, 4.2), (14.0, 1.2), (17.5, 0.2), (PK_SET, 0.6), (31.0, 0.2),
          (40, 0.0)]
PK_YAW = [(0, 0.0), (PK_LIFT - 1.0, -12.0), (14.0, -4.0), (20.0, 4.0), (PK_SET, 2.0), (31.0, 0.0), (40, 0.0)]
PK_STEPS = [(15.0, 18.0, 'left_leg', (1.9, -3.2)), (17.0, 20.0, 'right_leg', (-1.9, -3.2)),
            (29.0, 32.0, 'right_leg', (-1.9, 0.0)), (31.0, 34.0, 'left_leg', (1.9, 0.0))]
PK_SIDES = {'right_arm': np.array([-3.9, -2.8, 0.0]), 'left_arm': np.array([3.9, -2.8, 0.0])}   # bundle space: its sides


def pk_bundle(t):
    """The bundle's underside's middle and its turn at [t]: on the ground, turned a little; lifted; tipping back as it
    goes up over the tail board; lying on the tipped bed."""
    p = path(PK_BUNDLE, t)
    ground = rot_about(U, 14.0)
    held = rot_about(U, 14.0 * (1.0 - smooth((t - PK_LIFT) / 6.0))) @ rot_about(L, -8.0 * bump(t, 18.0, 24.0))
    on = smooth((t - 22.0) / (PK_SET - 22.0))
    return p, blend_R(held if t > PK_LIFT else ground, cart_R() @ hc.roty(-PK_SLOT_YAW), on)


def pack():
    c = la.clip('pack', PK_LEN, False, look=('citizen', 3), tools_=('bundle',), station_='cart_tail',
                events=[('lift', PK_LIFT), ('set', PK_SET)], traces=[('bundle', (0.0, -2.8, 0.0), (230, 200, 160))],
                views=((40, -12), (90, -2), (-50, -14)))
    holds = la.Holds()
    for t in la.every(PK_LEN):
        pitch = K(PK_P, t)
        feet, lift = stepping(t, FEET, PK_STEPS, lift=1.2)
        fz = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
        fr = F_(qt=bend(pitch, K(PK_YAW, t), 0.0), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, z=fz + 1.4 * bump(t, 2.0, 13.0), dip=K(PK_DIP, t)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        p, Rb = pk_bundle(t)
        w = smooth(K(PK_HANDS, t))
        for side in ('right_arm', 'left_arm'):
            want = p + Rb @ PK_SIDES[side]
            if t > 26.5:
                want = want + np.array([0.0, -1.0, 2.0]) * smooth((t - 26.5) / 2.5)
            palm = Rb @ (L if side == 'right_arm' else R)
            pose = reach_arm(holds, side, fr, want, palm=palm, palm_w=0.4, limit=1.8)
            dust = wave(t, 33.0, 38.5, 1.6, 6.0) * (1.0 if side == 'right_arm' else -1.0)
            rest = rest_arm(side, fr, fwd=10.0 * bump(t, 32.0, 39.0) + dust)
            set_arm(c, side, t, mix(rest, pose, w))
        c.key('head', t, head_level(fr, 26.0 * bump(t, 1.0, 12.5) + 12.0 * bump(t, 17.0, 27.5) - 4.0 * bump(t, 31.0, 38.0),
                                    y=-8.0 * bump(t, 1.0, 12.0)), ease='linear')
    c.mode('head', 'set')
    la.TRACKS['pack'] = {'bundle': lambda t, pivot: (pk_bundle(t)[1], pk_bundle(t)[0], None)}
    for k in (PK_LIFT, 18.0, PK_SET):
        p, Rb = pk_bundle(k)
        for side in ('right_arm', 'left_arm'):
            check(c, f'{side.replace("_arm", "")} hand on the bundle (t={k:g})', ta.fk(c, k, side, GRIP[side]),
                  p + Rb @ PK_SIDES[side], tol=1.0)
    check(c, 'the bundle over the tail board (t=21: its underside above the board\'s top)',
          (max(0.0, (pk_bundle(21.0)[0][1] + 0.6) - PK_TAIL_TOP[1]),), (0.0,), tol=0.1)
    joins_idle(c)
    return c


def _cart_tail():
    """The handcart a block ahead, resting tipped forward on its shafts, its tail toward the packer: its bed in steps
    (the previews draw only level boxes), its sides, the tail board and the wheels."""
    out = []

    def span(xs, ys, zs):
        pts = np.array([cart_pt(x, y, z) for x in xs for y in ys for z in zs])
        return tuple(pts.min(axis=0)), tuple(pts.max(axis=0))
    zs = np.linspace(hc.BED_FRONT, hc.BED_BACK, 7)
    for z0, z1 in zip(zs[:-1], zs[1:]):
        out.append(bx(*span((-hc.BED_X, hc.BED_X), (hc.FLOOR_TOP, hc.FLOOR_TOP + 1.2), (z0, z1)), CART))
        for x0 in (-hc.BED_X - 1.0, hc.BED_X):
            out.append(bx(*span((x0, x0 + 1.0), (hc.SIDE_TOP, hc.FLOOR_TOP), (z0, z1)), CART_D))
    out.append(bx(*span((-hc.BED_X - 1.0, hc.BED_X + 1.0), (hc.SIDE_TOP, hc.FLOOR_TOP + 1.2), (hc.BED_BACK, hc.BED_BACK + 1.0)), CART_D))
    for xw in (-hc.WHEEL_X, hc.WHEEL_X):
        for a, b in ((hc.R, 4.0), (7.4, 7.4), (4.0, hc.R)):
            out.append(bx((xw - 0.9, PK_AXLE[1] - a, PK_AXLE[2] - b), (xw + 0.9, PK_AXLE[1] + a, PK_AXLE[2] + b), WHEELC))
    return out

# ---- embrace (50, once): a neighbour's embrace, one clip that both play, face to face EM_GAP px apart: a step in with
# the arms opened, a hug (the right arm round under the other's left arm, high on their back, the left low round their
# waist), three pats on the back (pat), a rock from side to side, let go, a step back, and a nod. A head is as wide as
# the shoulders and sits on them down to the neck, so no arm goes over a shoulder (a head lies over each), and the heads
# pass on the diagonal: each steps in EM_SIDE px to their own right with the shoulders tipped EM_ROLL that way, and
# turns the head EM_TURN to the right, out past the other's shoulder (two heads turned the same way slide by each
# other), as they come together, keeping it there until they part.
EM_LEN = 50
EM_GAP = 16.0
EM_IN = 3.5
EM_PATS = (19.0, 22.5, 26.0)
EM_SIDE = 1.5
EM_ROLL, EM_TILT, EM_TURN = 7.0, 4.0, 40.0
EM_STEPS = [(2.0, 5.5, 'left_leg', (1.9 - EM_SIDE, -EM_IN)), (4.5, 8.0, 'right_leg', (-1.9 - EM_SIDE, -EM_IN)),
            (37.0, 40.5, 'right_leg', (-1.9, 0.0)), (39.5, 43.0, 'left_leg', (1.9, 0.0))]
EM_HUG = [(0, 0.0), (8.0, 0.0), (12.5, 1.0), (30.0, 1.0), (34.5, 0.0), (50, 0.0)]
EM_OPEN = [(0, 0.0), (3.0, 0.0), (7.0, 1.0), (11.0, 1.0), (13.0, 0.0), (50, 0.0)]
EM_P = [(0, 0.0), (6.0, 2.0), (12.0, 5.0), (30.0, 5.0), (35.0, 2.0), (40.0, 3.0), (45.0, 0.0), (50, 0.0)]
EM_ASIDE = [(0, 0.0), (2.5, 0.0), (7.5, 1.0), (36.5, 1.0), (41.0, 0.0), (50, 0.0)]
EM_OTHER = -(EM_GAP - EM_IN)                    # the other's root (clip z) once both have stepped in (x: EM_SIDE)
EM_HIGH = np.array([EM_SIDE - 5.2, 4.2, EM_OTHER - 2.6])    # the right hand high on their back, under their left arm
EM_LOW = np.array([EM_SIDE + 5.2, 8.4, EM_OTHER - 2.4])     # the left round their waist


def em_high(t):
    pat = sum(bump(t, k - 1.6, k + 0.4) for k in EM_PATS)
    return EM_HIGH + np.array([0.0, -0.8, -1.2]) * pat


def embrace():
    c = la.clip('embrace', EM_LEN, False, look=('citizen', 6), events=[('pat', k) for k in EM_PATS],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((70, -8), (120, -14), (20, -6)))
    holds = la.Holds()
    for t in la.every(EM_LEN):
        hug = smooth(K(EM_HUG, t))
        rock = 5.0 * wave(t, 26.0, 33.0, 7.0)
        pitch = K(EM_P, t)
        feet, lift = stepping(t, FEET, EM_STEPS, lift=1.1)
        fx = 0.5 * (feet['right_leg'][0] + feet['left_leg'][0])
        fz = 0.5 * (feet['right_leg'][1] + feet['left_leg'][1])
        aside = smooth(K(EM_ASIDE, t))
        fr = F_(qt=bend(pitch, rock, -EM_ROLL * aside), vt=(0.0, sink_for(pitch), 0.0),
                pr=la.root_for(feet, x=fx, z=fz, dip=0.4 * hug))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr, feet=feet, lift=lift)
        wopen = smooth(K(EM_OPEN, t))
        opened = {'right_arm': point_arm(fr, v(f=0.5, u=-0.4, r=0.8), unit(F + L), lift=0.2),
                  'left_arm': point_arm(fr, v(f=0.5, u=-0.25, r=-0.85), unit(F + R), lift=0.3)}
        for side, hold in (('right_arm', em_high(t)), ('left_arm', EM_LOW)):
            pose = mix(rest_arm(side, fr), opened[side], wopen)
            if hug > 1e-4:
                pose = mix(pose, reach_arm(holds, side, fr, hold, palm=F, palm_w=0.3, limit=1.8), hug)
            set_arm(c, side, t, pose)
        c.key('head', t, qeuler(x=-4.0 * hug + 9.0 * bump(t, 42.0, 46.5), y=EM_TURN * aside, z=-EM_TILT * aside),
              ease='linear')
    c.mode('head', 'add')
    ta.INFO['embrace']['partner'] = ('embrace', ('citizen', 2))
    PARTNER_PLACE['embrace'] = (T(0.0, 0.0, -EM_GAP) @ FACING, 0.0)
    for k in EM_PATS:
        check(c, f'right hand on the other\'s back (t={k - 1.6:g}: before the pat)', ta.fk(c, k - 1.6, 'right_arm', GRIP['right_arm']),
              em_high(k - 1.6), tol=1.0)
    check(c, 'left hand round their waist (t=20)', ta.fk(c, 20.0, 'left_arm', GRIP['left_arm']), EM_LOW, tol=1.0)
    joins_idle(c)
    return c

# ---- look_about (80, once): a newcomer by the handcart, taking in the street: a slow turn of the head and shoulders to
# the left along the houses, up at the eaves, a hand to the brow against the sky, back across and round to the right, a
# long look, a breath, and front again.
LK_LEN = 80
LK_YAW = [(0, 0.0), (6.0, 0.0), (18.0, -30.0), (28.0, -32.0), (36.0, -10.0), (50.0, 26.0), (62.0, 30.0), (72.0, 6.0), (80, 0.0)]
LK_HY = [(0, 0.0), (6.0, 0.0), (18.0, -38.0), (28.0, -30.0), (36.0, -8.0), (50.0, 36.0), (62.0, 44.0), (72.0, 8.0), (80, 0.0)]
LK_HX = [(0, 0.0), (8.0, 2.0), (18.0, -6.0), (23.0, -22.0), (30.0, -18.0), (36.0, -4.0), (50.0, 0.0), (56.0, -10.0),
         (62.0, -6.0), (72.0, 2.0), (80, 0.0)]
LK_BROW = [(0, 0.0), (19.0, 0.0), (23.0, 1.0), (30.0, 1.0), (34.0, 0.0), (80, 0.0)]


def look_about():
    c = la.clip('look_about', LK_LEN, False, look=('citizen', 1), events=[('look', 24.0), ('look', 58.0)],
                traces=[('head', (0.0, -4.0, -3.0), (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    holds = la.Holds()
    for t in la.every(LK_LEN):
        yaw = K(LK_YAW, t)
        breath = bump(t, 64.0, 72.0)
        fr = F_(qt=bend(-2.0 * bump(t, 18.0, 32.0) - 2.0 * breath, yaw, 0.0), vt=(0.0, -0.4 * breath, 0.0),
                pr=la.root_for(FEET, x=0.6 * math.sin(math.pi * yaw / 60.0)))
        key_frame(c, t, fr, ease='linear')
        stand(c, t, fr)
        qh = qeuler(x=K(LK_HX, t), y=K(LK_HY, t))
        brow = smooth(K(LK_BROW, t))
        lpose = rest_arm('left_arm', fr, fwd=2.0 * math.sin(2 * math.pi * t / 40.0))
        if brow > 1e-4:
            lpose = mix(lpose, reach_arm(holds, 'left_arm', fr, head_pt(fr, qh, np.array([1.2, -5.6, -6.2])), palm=D, palm_w=0.4), brow)
        set_arm(c, 'left_arm', t, lifted(lpose, 0.5 * breath))
        set_arm(c, 'right_arm', t, lifted(rest_arm('right_arm', fr, fwd=-2.0 * math.sin(2 * math.pi * t / 40.0)), 0.5 * breath))
        c.key('head', t, qh, ease='linear')
    c.mode('head', 'add')
    joins_idle(c)
    return c

# ---- show_in (30, once, an overlay played walking): a neighbour walking the newcomers to their door: a look back at
# them, the right arm swept out open toward the door ahead and to the right (show), a nod to them, and the arm down.
SI_LEN = 30
SI_SHOW = 12.0
SI_ARM = [(0, 0.0), (4.0, 0.0), (10.0, 1.0), (21.0, 1.0), (27.0, 0.0), (30, 0.0)]
SI_HY = [(0, 0.0), (3.0, -34.0), (8.0, -36.0), (12.0, 24.0), (17.0, 22.0), (21.0, -30.0), (25.0, -22.0), (30, 0.0)]
SI_HX = [(0, 0.0), (8.0, 0.0), (12.0, -4.0), (21.0, 2.0), (22.5, 9.0), (24.0, 0.0), (30, 0.0)]


def show_in():
    c = la.clip('show_in', SI_LEN, False, look=('citizen', 2), overlay=True, events=[('show', SI_SHOW)],
                traces=[('right_arm', GRIP['right_arm'], (255, 200, 120))], views=((32, -10), (0, -4), (90, -2)))
    for t in la.every(SI_LEN):
        w = smooth(K(SI_ARM, t))
        swing = bump(t, 14.0, 20.0)
        out = point_arm(REST, v(f=0.75 - 0.25 * swing, u=0.2 + 0.08 * swing, r=0.6 + 0.3 * swing), unit(U + 0.6 * F), lift=0.4)
        set_arm(c, 'right_arm', t, mix(rest_arm('right_arm', REST), out, w))
        c.key('head', t, qeuler(x=K(SI_HX, t), y=K(SI_HY, t)), ease='linear')
    c.mode('head', 'add')
    joins_idle(c, tol=1.2)
    return c

# ================================================================== the registry

STREET_CLIPS = ['queue', 'hold_out', 'ladle', 'take_bowl', 'eat_bowl', 'shiver', 'empty_basket', 'haggle', 'pack',
                'embrace', 'look_about', 'show_in', 'shut_stall']
# hold_out and eat_bowl before take_bowl, which starts on the one and ends on the other
MAKERS = [('queue', queue), ('shiver', shiver), ('empty_basket', empty_basket), ('haggle', haggle), ('ladle', ladle),
          ('hold_out', hold_out), ('eat_bowl', eat_bowl), ('take_bowl', take_bowl), ('pack', pack), ('embrace', embrace),
          ('look_about', look_about), ('show_in', show_in), ('shut_stall', shut_stall)]
NEEDS = {'take_bowl': ('hold_out', 'eat_bowl', 'ladle'), 'ladle': ('hold_out',), 'hold_out': ('ladle',)}
STATION_BOXES = {'alms_keeper': lambda: _alms(False), 'alms_taker': lambda: _alms(True), 'cart_tail': _cart_tail,
                 'bare_stall': _bare_stall}
STATION_COLOUR = {'alms_keeper': wc.EARTH, 'alms_taker': wc.EARTH, 'cart_tail': wc.EARTH}


def street_moment_specs():
    """Where the streets' clips' moments happen (living_anims' moment_specs forms)."""
    right = ('part', 'right_arm', tuple(GRIP['right_arm']))
    mouth = ('part', 'head', (0.0, -2.2, -4.4))
    return {
        'queue': {'murmur': mouth},
        'hold_out': {'offer': ('hands', 0.0), 'take': ('part', 'bowl', tuple(BOWL_BASE))},
        'ladle': {'take': ('part', 'bowl_empty', tuple(BOWL_BASE)), 'dip': ('part', 'ladle', tuple(lp.LADLE_BOWL)),
                  'pour': ('part', 'bowl_empty', tuple(BOWL_TOP)), 'give': ('part', 'bowl', tuple(BOWL_BASE))},
        'take_bowl': {'bow': ('part', 'head', (0.0, -4.0, -3.0))},
        'eat_bowl': {'spoon': mouth},
        'shiver': {'stamp': {SH2_STAMPS[0]: ('part', 'right_leg', (0.0, 12.0, -1.0)),
                             SH2_STAMPS[1]: ('part', 'left_leg', (0.0, 12.0, -1.0))}, 'puff': mouth},
        'empty_basket': {'shake': ('part', 'basket_turned', tuple(EB2_BODY))},
        'haggle': {'count': ('hands', 0.0), 'no': right},
        'pack': {'lift': ('part', 'bundle', (0.0, -2.8, 0.0)), 'set': ('point', tuple(PK_ON_CART))},
        'embrace': {'pat': right},
        'look_about': {'look': ('part', 'head', (0.0, -4.0, -3.0))},
        'show_in': {'show': right},
        'shut_stall': {'pull': ('point', (0.0, SS_ROLLED, SS_Z)), 'hook': ('point', (0.0, SS_HOOKED, SS_Z))},
    }


BUILT = {}


def build(living, only=None):
    """Builds the streets' clips on living_anims' kit ([living]: the module), and teaches it their stations, moments
    and props' tracks. [only]: just these (the quick previews), with the clips they start or end on."""
    global la
    la = living
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
        out.update(street_moment_specs())
        return out
    la.moment_specs = moment_specs
    BUILT['done'] = True
    la.WANTED.extend(STREET_CLIPS)
